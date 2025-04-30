package dumb.cognote;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static dumb.cognote.Log.error;
import static dumb.cognote.Log.message;
import static dumb.cognote.Logic.RetractionType;

public class Cog {

    public static final String ID_PREFIX_NOTE = "note-", ID_PREFIX_LLM_ITEM = "llm_", ID_PREFIX_QUERY = "query_", ID_PREFIX_LLM_RESULT = "llmres_", ID_PREFIX_RULE = "rule_", ID_PREFIX_INPUT_ITEM = "input_", ID_PREFIX_PLUGIN = "plugin_";

    public static final String GLOBAL_KB_NOTE_ID = "kb://global";
    public static final String GLOBAL_KB_NOTE_TITLE = "Global Knowledge";
    public static final String CONFIG_NOTE_ID = "note-config";
    public static final String CONFIG_NOTE_TITLE = "System Configuration";
    public static final double INPUT_ASSERTION_BASE_PRIORITY = 10;
    public static final int MAX_WS_PARSE_PREVIEW = 100;
    public static final double DEFAULT_RULE_PRIORITY = 1;
    static final int KB_SIZE_THRESHOLD_WARN_PERCENT = 90, KB_SIZE_THRESHOLD_HALT_PERCENT = 98;
    static final double DERIVED_PRIORITY_DECAY = 0.95;
    static final AtomicLong id = new AtomicLong(System.currentTimeMillis());
    static final int DEFAULT_KB_CAPACITY = 64 * 1024;
    static final int DEFAULT_REASONING_DEPTH = 4;
    static final boolean DEFAULT_BROADCAST_INPUT = false;
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 2;
    private static final int MAX_KIF_PARSE_PREVIEW = 50;
    private static final int PORT = 8080;
    public final Events events;

    public final Tools tools;

    public final ScheduledExecutorService mainExecutor =
            Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors(), Thread.ofVirtual().factory());

    final AtomicBoolean running = new AtomicBoolean(true), paused = new AtomicBoolean(true);
    private final Object pauseLock = new Object();
    public volatile String status = "Initializing";
    public volatile boolean broadcastInputAssertions;
    public volatile int globalKbCapacity = DEFAULT_KB_CAPACITY;
    public volatile int reasoningDepthLimit = DEFAULT_REASONING_DEPTH;


    public Cog() {
        this.events = new Events(mainExecutor);
        Log.setEvents(this.events);
        this.tools = new Tools();
    }

    public static String id(String prefix) {
        return prefix + id.incrementAndGet();
    }

    private static void shutdownExecutor(ExecutorService executor, String name) {
        if (executor == null || executor.isShutdown()) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                error(name + " did not terminate gracefully, forcing shutdown.");
                executor.shutdownNow();
                if (!executor.awaitTermination(1, TimeUnit.SECONDS))
                    error(name + " did not terminate after forced shutdown.");
            }
        } catch (InterruptedException e) {
            error("Interrupted while waiting for " + name + " shutdown.");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    static Note createDefaultConfigNote() {
        var config = new CogNote.Configuration(
                LM.DEFAULT_LLM_URL,
                LM.DEFAULT_LLM_MODEL,
                DEFAULT_KB_CAPACITY,
                DEFAULT_REASONING_DEPTH,
                DEFAULT_BROADCAST_INPUT
        );
        return new Note(CONFIG_NOTE_ID, CONFIG_NOTE_TITLE, Json.str(config), Note.Status.IDLE);
    }

    public void start() {
        if (!running.get()) {
            error("Cannot restart a stopped system.");
            return;
        }
        paused.set(true);
        status("Initializing");
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        message("Stopping system...");
        status("Stopping");
        paused.set(false);
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }

        shutdownExecutor(mainExecutor, "Main Executor");
        events.shutdown();
        status("Stopped");
        message("System stopped.");
    }

    public boolean isPaused() {
        return paused.get();
    }

    public void setPaused(boolean pause) {
        if (paused.get() == pause || !running.get()) return;
        paused.set(pause);
        status(pause ? "Paused" : "Running");
        if (!pause) {
            synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
        }
    }

    public void status(String status) {
        this.status = status;
    }

    public void loadRules(String filename) throws IOException {
        message("Loading expressions from: " + filename);
        var path = Paths.get(filename);
        if (!Files.exists(path) || !Files.isReadable(path))
            throw new IOException("File not found or not readable: " + filename);

        var kifBuffer = new StringBuilder();
        long[] counts = {0, 0, 0};
        try (var reader = Files.newBufferedReader(path)) {
            String line;
            var parenDepth = 0;
            while ((line = reader.readLine()) != null) {
                counts[0]++;
                var commentStart = line.indexOf(';');
                if (commentStart != -1) line = line.substring(0, commentStart);
                line = line.trim();
                if (line.isEmpty()) continue;

                parenDepth += line.chars().filter(c -> c == '(').count() - line.chars().filter(c -> c == ')').count();
                kifBuffer.append(line).append(' ');

                if (parenDepth == 0 && !kifBuffer.isEmpty()) {
                    var kifText = kifBuffer.toString().trim();
                    kifBuffer.setLength(0);
                    if (!kifText.isEmpty()) {
                        counts[1]++;
                        try {
                            KifParser.parseKif(kifText).forEach(term -> events.emit(new ExternalInputEvent(term, "file:" + filename, null)));
                            counts[2]++;
                        } catch (Exception e) {
                            error(String.format("File Processing Error (line ~%d): %s for '%s...'", counts[0], e.getMessage(), kifText.substring(0, Math.min(kifText.length(), MAX_KIF_PARSE_PREVIEW))));
                            e.printStackTrace();
                        }
                    }
                } else if (parenDepth < 0) {
                    error(String.format("Mismatched parentheses near line %d: '%s'", counts[0], line));
                    parenDepth = 0;
                    kifBuffer.setLength(0);
                }
            }
            if (parenDepth != 0) error("Warning: Unbalanced parentheses at end of file: " + filename);
        }
        message(String.format("Processed %d KIF blocks from %s, published %d input events.", counts[1], filename, counts[2]));
    }

    void waitIfPaused() {
        synchronized (pauseLock) {
            while (paused.get() && running.get()) {
                try {
                    pauseLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running.set(false);
                }
            }
        }
        if (!running.get()) throw new RuntimeException("System stopped");
    }

    public Answer querySync(Query query) {
        var answerFuture = new CompletableFuture<Answer>();
        var queryID = query.id();
        Consumer<CogEvent> listener = e -> {
            // Replaced pattern matching instanceof with traditional check and cast
            if (e instanceof Answer.AnswerEvent answerEvent) {
                Answer result = answerEvent.result();
                if (result.query().equals(queryID)) {
                    answerFuture.complete(result);
                }
            }
        };
        @SuppressWarnings("unchecked")
        var listeners = events.listeners.computeIfAbsent(Answer.AnswerEvent.class, k -> new CopyOnWriteArrayList<>());
        listeners.add(listener);
        try {
            events.emit(new Query.QueryEvent(query));
            return answerFuture.get(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Query execution interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Error during query execution", e.getCause());
        } catch (TimeoutException e) {
            throw new RuntimeException("Query execution timed out after 60 seconds", e);
        } finally {
            listeners.remove(listener);
        }
    }

    public enum QueryType {ASK_BINDINGS, ASK_TRUE_FALSE, ACHIEVE_GOAL}

    public enum Feature {FORWARD_CHAINING, BACKWARD_CHAINING, TRUTH_MAINTENANCE, CONTRADICTION_DETECTION, UNCERTAINTY_HANDLING, OPERATOR_SUPPORT, REWRITE_RULES, UNIVERSAL_INSTANTIATION}

    public enum QueryStatus {SUCCESS, FAILURE, TIMEOUT, ERROR}

    public enum TaskStatus {IDLE, SENDING, PROCESSING, DONE, ERROR, CANCELLED}

    @FunctionalInterface
    interface DoubleDoublePredicate {
        boolean test(double a, double b);
    }

    @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "eventType",
            visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = AssertedEvent.class, name = "AssertedEvent"),
            @JsonSubTypes.Type(value = RetractedEvent.class, name = "RetractedEvent"),
            @JsonSubTypes.Type(value = AssertionEvictedEvent.class, name = "AssertionEvictedEvent"),
            @JsonSubTypes.Type(value = AssertionStateEvent.class, name = "AssertionStateEvent"),
            @JsonSubTypes.Type(value = TemporaryAssertionEvent.class, name = "TemporaryAssertionEvent"),
            @JsonSubTypes.Type(value = RuleAddedEvent.class, name = "RuleAddedEvent"),
            @JsonSubTypes.Type(value = RuleRemovedEvent.class, name = "RuleRemovedEvent"),
            @JsonSubTypes.Type(value = TaskUpdateEvent.class, name = "TaskUpdateEvent"),
            @JsonSubTypes.Type(value = SystemStatusEvent.class, name = "SystemStatusEvent"),
            @JsonSubTypes.Type(value = AddedEvent.class, name = "AddedEvent"),
            @JsonSubTypes.Type(value = RemovedEvent.class, name = "RemovedEvent"),
            @JsonSubTypes.Type(value = ExternalInputEvent.class, name = "ExternalInputEvent"),
            @JsonSubTypes.Type(value = RetractionRequestEvent.class, name = "RetractionRequestEvent"),
            @JsonSubTypes.Type(value = Events.LogMessageEvent.class, name = "LogMessageEvent"), // From Events.java
            @JsonSubTypes.Type(value = Events.DialogueRequestEvent.class, name = "DialogueRequestEvent"), // From Events.java
            @JsonSubTypes.Type(value = Truths.ContradictionDetectedEvent.class, name = "ContradictionDetectedEvent"), // From Truths.java
            @JsonSubTypes.Type(value = Answer.AnswerEvent.class, name = "AnswerEvent"), // From Answer.java
            @JsonSubTypes.Type(value = Query.QueryEvent.class, name = "QueryEvent"), // From Query.java
            @JsonSubTypes.Type(value = CogNote.NoteStatusEvent.class, name = "NoteStatusEvent") // From CogNote.java
    })
    public interface CogEvent {
        default String assocNote() {
            return null;
        }

        JsonNode toJson(); // Return JsonNode instead of JSONObject

        String getEventType(); // Required for @JsonTypeInfo
    }

    public interface NoteEvent extends CogEvent {
        Note note();

        @Override
        default String assocNote() {
            return note().id;
        }
    }

    public interface NoteIDEvent extends CogEvent {
        String noteId();

        @Override
        default String assocNote() {
            return noteId();
        }
    }

    private interface AssertionEvent extends CogEvent {
        Assertion assertion();

        @Override
        default String assocNote() {
            return assertion().sourceNoteId();
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AssertedEvent(Assertion assertion, String kbId) implements AssertionEvent {

        @Override
        public String assocNote() {
            return assertion.sourceNoteId() != null ? assertion.sourceNoteId() : kbId;
        }

        public JsonNode toJson() {
            return Json.node(this);
        }

        @Override
        public String getEventType() {
            return "AssertedEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RetractedEvent(Assertion assertion, String kbId, String reason) implements AssertionEvent {

        @Override
        public String assocNote() {
            return assertion.sourceNoteId() != null ? assertion.sourceNoteId() : kbId;
        }

        public JsonNode toJson() {
            return Json.node(this);
        }

        @Override
        public String getEventType() {
            return "RetractedEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AssertionEvictedEvent(Assertion assertion, String kbId) implements AssertionEvent {
        @Override
        public String assocNote() {
            return assertion.sourceNoteId() != null ? assertion.sourceNoteId() : kbId;
        }

        public JsonNode toJson() {
            return Json.node(this);
        }

        @Override
        public String getEventType() {
            return "AssertionEvictedEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AssertionStateEvent(String assertionId, boolean isActive, String kbId) implements CogEvent {

        public JsonNode toJson() {
            return Json.node(this);
        }

        @Override
        public String getEventType() {
            return "AssertionStateEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TemporaryAssertionEvent(Term.Lst temporaryAssertion, Map<Term.Var, Term> bindings,
                                          String noteId) implements NoteIDEvent {

        @JsonProperty("temporaryAssertionJson") // Map temporaryAssertion field to temporaryAssertionJson
        public JsonNode getTemporaryAssertionJson() {
            return temporaryAssertion.toJson();
        }

        @JsonProperty("bindingsJson") // Map bindings field to bindingsJson
        public JsonNode getBindingsJson() {
            var jsonBindings = Json.node();
            bindings.forEach((var, term) -> jsonBindings.set(var.name(), term.toJson()));
            return jsonBindings;
        }

        public JsonNode toJson() {
            return Json.node(this);
        }

        @Override
        public String getEventType() {
            return "TemporaryAssertionEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RuleAddedEvent(Rule rule) implements CogEvent {

        @Override
        public String assocNote() {
            return rule.sourceNoteId();
        }

        public JsonNode toJson() {
            return Json.node(this);
        }

        @Override
        public String getEventType() {
            return "RuleAddedEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RuleRemovedEvent(Rule rule) implements CogEvent {

        @Override
        public String assocNote() {
            return rule.sourceNoteId();
        }

        public JsonNode toJson() {
            return Json.node(this);
        }

        @Override
        public String getEventType() {
            return "RuleRemovedEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskUpdateEvent(String taskId, TaskStatus status, String content) implements CogEvent {

        public JsonNode toJson() {
            return Json.node(this);
        }

        @Override
        public String getEventType() {
            return "TaskUpdateEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SystemStatusEvent(String statusMessage, int kbCount, int kbCapacity, int taskQueueSize,
                                    int ruleCount) implements CogEvent {

        public JsonNode toJson() {
            return Json.node(this);
        }

        @Override
        public String getEventType() {
            return "SystemStatusEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AddedEvent(Note note) implements NoteEvent {

        public JsonNode toJson() {
            return Json.node(this);
        }

        @Override
        public String getEventType() {
            return "AddedEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RemovedEvent(Note note) implements NoteEvent {

        public JsonNode toJson() {
            return Json.node(this);
        }

        @Override
        public String getEventType() {
            return "RemovedEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExternalInputEvent(Term term, String sourceId, @Nullable String noteId) implements NoteIDEvent {

        @JsonProperty("termJson") // Map term field to termJson
        public JsonNode getTermJson() {
            return term.toJson();
        }

        @JsonProperty("termString") // Add termString property
        public String getTermString() {
            return term.toKif();
        }

        public JsonNode toJson() {
            return Json.node(this);
        }

        @Override
        public String getEventType() {
            return "ExternalInputEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RetractionRequestEvent(String target, RetractionType type, String sourceId,
                                         @Nullable String noteId) implements NoteIDEvent {

        public JsonNode toJson() {
            return Json.node(this);
        }

        @Override
        public String getEventType() {
            return "RetractionRequestEvent";
        }
    }
}
