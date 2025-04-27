package dumb.cognote;

import dumb.cognote.plugin.*;
import dumb.cognote.tool.*;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static dumb.cognote.Logic.*;
import static dumb.cognote.Log.error;
import static dumb.cognote.Log.message;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;

public class Cog {

    public static final String ID_PREFIX_NOTE = "note-", ID_PREFIX_LLM_ITEM = "llm_", ID_PREFIX_QUERY = "query_", ID_PREFIX_LLM_RESULT = "llmres_", ID_PREFIX_RULE = "rule_", ID_PREFIX_INPUT_ITEM = "input_", ID_PREFIX_PLUGIN = "plugin_";

    public static final String GLOBAL_KB_NOTE_ID = "kb://global";
    public static final String GLOBAL_KB_NOTE_TITLE = "Global Knowledge";
    public static final String CONFIG_NOTE_ID = "note-config";
    public static final String CONFIG_NOTE_TITLE = "System Configuration";
    public static final double INPUT_ASSERTION_BASE_PRIORITY = 10;
    public static final int MAX_WS_PARSE_PREVIEW = 100;
    static final int KB_SIZE_THRESHOLD_WARN_PERCENT = 90, KB_SIZE_THRESHOLD_HALT_PERCENT = 98;
    static final double DERIVED_PRIORITY_DECAY = 0.95;
    static final AtomicLong id = new AtomicLong(System.currentTimeMillis());
    static final String NOTES_FILE = "cognote_notes.json";
    static final int DEFAULT_KB_CAPACITY = 64 * 1024;
    static final int DEFAULT_REASONING_DEPTH = 4;
    static final boolean DEFAULT_BROADCAST_INPUT = false;
    public static final double DEFAULT_RULE_PRIORITY = 1;
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 2;
    private static final int MAX_KIF_PARSE_PREVIEW = 50;
    private static final int PORT = 8080;
    public final Events events;
    public final Cognition context;
    public final LM lm;
    public final Tools tools;

//    public final ExecutorService mainExecutor = Executors.newVirtualThreadPerTaskExecutor();
    public final ScheduledExecutorService mainExecutor =
        Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors(), Thread.ofVirtual().factory())
    ;

    public final DialogueManager dialogueManager;
    final AtomicBoolean running = new AtomicBoolean(true), paused = new AtomicBoolean(true);
    final Plugins plugins;
    private final Reason.ReasonerManager reasonerManager;
    private final Object pauseLock = new Object();
    public volatile String status = "Initializing";
    public volatile boolean broadcastInputAssertions;
    public volatile int globalKbCapacity = DEFAULT_KB_CAPACITY;
    public volatile int reasoningDepthLimit = DEFAULT_REASONING_DEPTH;

    public Cog() {
        this.events = new Events(mainExecutor);
        Log.setEvents(this.events);

        this.tools = new Tools();

        this.lm = new LM(this);

        this.dialogueManager = new DialogueManager(this);

        var tms = new Truths.BasicTMS(events);
        var operatorRegistry = new Op.Operators();

        this.context = new Cognition(globalKbCapacity, events, tms, operatorRegistry, this);
        this.reasonerManager = new Reason.ReasonerManager(events, context);
        this.plugins = new Plugins(events, context);

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
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
        var configJson = new JSONObject()
                .put("llmApiUrl", LM.DEFAULT_LLM_URL)
                .put("llmModel", LM.DEFAULT_LLM_MODEL)
                .put("globalKbCapacity", DEFAULT_KB_CAPACITY)
                .put("reasoningDepthLimit", DEFAULT_REASONING_DEPTH)
                .put("broadcastInputAssertions", DEFAULT_BROADCAST_INPUT);
        return new Note(CONFIG_NOTE_ID, CONFIG_NOTE_TITLE, configJson.toString(2), Note.Status.IDLE);
    }


    private void setupDefaultPlugins() {
        plugins.loadPlugin(new InputPlugin());
        plugins.loadPlugin(new RetractionPlugin());
        plugins.loadPlugin(new TmsPlugin());

        plugins.loadPlugin(new TaskDecomposePlugin());

        plugins.loadPlugin(new WebSocketPlugin(new InetSocketAddress(PORT), this));
        plugins.loadPlugin(new StatusUpdaterPlugin());

        reasonerManager.loadPlugin(new Reason.ForwardChainingReasonerPlugin());
        reasonerManager.loadPlugin(new Reason.RewriteRuleReasonerPlugin());
        reasonerManager.loadPlugin(new Reason.UniversalInstantiationReasonerPlugin());
        reasonerManager.loadPlugin(new Reason.BackwardChainingReasonerPlugin());

        tools.register(new AssertKIFTool(this));
        tools.register(new GetNoteTextTool(this));
        tools.register(new FindAssertionsTool(this));
        tools.register(new RetractTool(this));
        tools.register(new QueryTool(this));
        tools.register(new LogMessageTool());

        tools.register(new SummarizeTool(this));
        tools.register(new IdentifyConceptsTool(this));
        tools.register(new GenerateQuestionsTool(this));
        tools.register(new TextToKifTool(this));
        tools.register(new DecomposeGoalTool(this));
    }

    public void start() {
        if (!running.get()) {
            error("Cannot restart a stopped system.");
            return;
        }
        paused.set(true);
        status("Initializing");

        context.operators.addBuiltin();
        setupDefaultPlugins();
        plugins.initializeAll();
        reasonerManager.initializeAll();


        status("Paused (Ready to Start)");
        message("System initialized and paused.");
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        message("Stopping system...");

        status("Stopping");
        paused.set(false);
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }

        lm.activeLlmTasks.values().forEach(f -> f.cancel(true));
        lm.activeLlmTasks.clear();
        dialogueManager.clear();

        plugins.shutdownAll();
        reasonerManager.shutdownAll();


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
        events.emit(new SystemStatusEvent(status, context.kbCount(), context.kbTotalCapacity(), lm.activeLlmTasks.size(), context.ruleCount()));
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
        var resultFuture = new CompletableFuture<Answer>();

        Consumer<CogEvent> listener = event -> {
            if (event instanceof Answer.AnswerEvent answerEvent) {
                var result = answerEvent.result();
                if (result.query().equals(query.id())) {
                    resultFuture.complete(result);
                }
            }
        };

        @SuppressWarnings("unchecked")
        var queryResultListeners = events.listeners.computeIfAbsent(Answer.AnswerEvent.class, k -> new CopyOnWriteArrayList<>());
        queryResultListeners.add(listener);

        try {
            events.emit(new Query.QueryEvent(query));

            return resultFuture.get(60, TimeUnit.SECONDS);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Query execution interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Error during query execution", e.getCause());
        } catch (TimeoutException e) {
            throw new RuntimeException("Query execution timed out after 60 seconds", e);
        } finally {
            queryResultListeners.remove(listener);
        }
    }

    public enum QueryType {ASK_BINDINGS, ASK_TRUE_FALSE, ACHIEVE_GOAL}

    enum Feature {FORWARD_CHAINING, BACKWARD_CHAINING, TRUTH_MAINTENANCE, CONTRADICTION_DETECTION, UNCERTAINTY_HANDLING, OPERATOR_SUPPORT, REWRITE_RULES, UNIVERSAL_INSTANTIATION}

    public enum QueryStatus {SUCCESS, FAILURE, TIMEOUT, ERROR}

    public enum TaskStatus {IDLE, SENDING, PROCESSING, DONE, ERROR, CANCELLED}

    @FunctionalInterface
    interface DoubleDoublePredicate {
        boolean test(double a, double b);
    }

    public interface CogEvent {
        default String assocNote() {
            return null;
        }

        JSONObject toJson();
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

    public record AssertedEvent(Assertion assertion, String kbId) implements AssertionEvent {
        public AssertedEvent {
            requireNonNull(assertion);
            requireNonNull(kbId);
        }

        @Override
        public String assocNote() {
            return assertion.sourceNoteId() != null ? assertion.sourceNoteId() : kbId;
        }

        public JSONObject toJson() {
            return new JSONObject()
                    .put("type", "event")
                    .put("eventType", "AssertedEvent")
                    .put("eventData", new JSONObject()
                            .put("assertion", assertion.toJson())
                            .put("kbId", kbId));
        }
    }

    public record RetractedEvent(Assertion assertion, String kbId, String reason) implements AssertionEvent {
        public RetractedEvent {
            requireNonNull(assertion);
            requireNonNull(kbId);
            requireNonNull(reason);
        }

        @Override
        public String assocNote() {
            return assertion.sourceNoteId() != null ? assertion.sourceNoteId() : kbId;
        }

        public JSONObject toJson() {
            return new JSONObject()
                    .put("type", "event")
                    .put("eventType", "RetractedEvent")
                    .put("eventData", new JSONObject()
                            .put("assertion", assertion.toJson())
                            .put("kbId", kbId)
                            .put("reason", reason));
        }
    }

    public record AssertionEvictedEvent(Assertion assertion, String kbId) implements AssertionEvent {
        public AssertionEvictedEvent {
            requireNonNull(assertion);
            requireNonNull(kbId);
        }

        @Override
        public String assocNote() {
            return assertion.sourceNoteId() != null ? assertion.sourceNoteId() : kbId;
        }

        public JSONObject toJson() {
            return new JSONObject()
                    .put("type", "event")
                    .put("eventType", "AssertionEvictedEvent")
                    .put("eventData", new JSONObject()
                            .put("assertion", assertion.toJson())
                            .put("kbId", kbId));
        }
    }

    public record AssertionStateEvent(String assertionId, boolean isActive, String kbId) implements CogEvent {
        public AssertionStateEvent {
            requireNonNull(assertionId);
            requireNonNull(kbId);
        }

        @Override
        public String assocNote() {
            return null;
        }

        public JSONObject toJson() {
            return new JSONObject()
                    .put("type", "event")
                    .put("eventType", "AssertionStateEvent")
                    .put("eventData", new JSONObject()
                            .put("assertionId", assertionId)
                            .put("isActive", isActive)
                            .put("kbId", kbId));
        }
    }

    public record TemporaryAssertionEvent(Term.Lst temporaryAssertion, Map<Term.Var, Term> bindings,
                                          String noteId) implements NoteIDEvent {
        public TemporaryAssertionEvent {
            requireNonNull(temporaryAssertion);
            requireNonNull(bindings);
            requireNonNull(noteId);
        }

        public JSONObject toJson() {
            var jsonBindings = new JSONObject();
            bindings.forEach((var, term) -> jsonBindings.put(var.name(), term.toJson()));
            return new JSONObject()
                    .put("type", "event")
                    .put("eventType", "TemporaryAssertionEvent")
                    .put("eventData", new JSONObject()
                            .put("temporaryAssertion", temporaryAssertion.toJson())
                            .put("bindings", jsonBindings)
                            .put("noteId", noteId));
        }
    }

    public record RuleAddedEvent(Rule rule) implements CogEvent {
        public RuleAddedEvent {
            requireNonNull(rule);
        }

        @Override
        public String assocNote() {
            return rule.sourceNoteId();
        }

        public JSONObject toJson() {
            return new JSONObject()
                    .put("type", "event")
                    .put("eventType", "RuleAddedEvent")
                    .put("eventData", new JSONObject()
                            .put("rule", rule.toJson()));
        }
    }

    public record RuleRemovedEvent(Rule rule) implements CogEvent {
        public RuleRemovedEvent {
            requireNonNull(rule);
        }

        @Override
        public String assocNote() {
            return rule.sourceNoteId();
        }

        public JSONObject toJson() {
            return new JSONObject()
                    .put("type", "event")
                    .put("eventType", "RuleRemovedEvent")
                    .put("eventData", new JSONObject()
                            .put("rule", rule.toJson()));
        }
    }


    public record TaskUpdateEvent(String taskId, TaskStatus status, String content) implements CogEvent {
        public TaskUpdateEvent {
            requireNonNull(taskId);
            requireNonNull(status);
            requireNonNull(content);
        }

        @Override
        public String assocNote() {
            return null;
        }

        public JSONObject toJson() {
            return new JSONObject()
                    .put("type", "event")
                    .put("eventType", "TaskUpdateEvent")
                    .put("eventData", new JSONObject()
                            .put("taskId", taskId)
                            .put("status", status.name())
                            .put("content", content));
        }
    }

    public record SystemStatusEvent(String statusMessage, int kbCount, int kbCapacity, int taskQueueSize,
                                    int ruleCount) implements CogEvent {
        public SystemStatusEvent {
            requireNonNull(statusMessage);
        }

        public JSONObject toJson() {
            return new JSONObject()
                    .put("type", "event")
                    .put("eventType", "SystemStatusEvent")
                    .put("eventData", new JSONObject()
                            .put("statusMessage", statusMessage)
                            .put("kbCount", kbCount)
                            .put("kbCapacity", kbCapacity)
                            .put("taskQueueSize", taskQueueSize)
                            .put("ruleCount", ruleCount));
        }
    }

    public record AddedEvent(Note note) implements NoteEvent {
        public AddedEvent {
            requireNonNull(note);
        }

        public JSONObject toJson() {
            return new JSONObject()
                    .put("type", "event")
                    .put("eventType", "AddedEvent")
                    .put("eventData", new JSONObject()
                            .put("note", note.toJson()));
        }
    }

    public record RemovedEvent(Note note) implements NoteEvent {
        public RemovedEvent {
            requireNonNull(note);
        }

        public JSONObject toJson() {
            return new JSONObject()
                    .put("type", "event")
                    .put("eventType", "RemovedEvent")
                    .put("eventData", new JSONObject()
                            .put("note", note.toJson()));
        }
    }

    public record ExternalInputEvent(Term term, String sourceId, @Nullable String noteId) implements NoteIDEvent {
        public ExternalInputEvent {
            requireNonNull(term);
            requireNonNull(sourceId);
        }

        public JSONObject toJson() {
            var json = new JSONObject()
                    .put("type", "event")
                    .put("eventType", "ExternalInputEvent")
                    .put("eventData", new JSONObject()
                            .put("termJson", term.toJson())
                            .put("termString", term.toKif())
                            .put("sourceId", sourceId));
            if (noteId != null) json.put("noteId", noteId);
            return json;
        }
    }

    public record RetractionRequestEvent(String target, RetractionType type, String sourceId,
                                         @Nullable String noteId) implements NoteIDEvent {
        public RetractionRequestEvent {
            requireNonNull(target);
            requireNonNull(type);
            requireNonNull(sourceId);
        }

        public JSONObject toJson() {
            var json = new JSONObject()
                    .put("type", "event")
                    .put("eventType", "RetractionRequestEvent")
                    .put("eventData", new JSONObject()
                            .put("target", target)
                            .put("type", type.name())
                            .put("sourceId", sourceId));
            if (noteId != null) json.put("noteId", noteId);
            return json;
        }
    }

    public record DialogueRequestEvent(String dialogueId, String requestType, String prompt, JSONObject options, JSONObject context) implements CogEvent {
        public DialogueRequestEvent {
            requireNonNull(dialogueId);
            requireNonNull(requestType);
            requireNonNull(prompt);
            requireNonNull(options);
            requireNonNull(context);
        }

        public JSONObject toJson() {
            return new JSONObject()
                    .put("type", "event")
                    .put("eventType", "DialogueRequestEvent")
                    .put("eventData", new JSONObject()
                            .put("dialogueId", dialogueId)
                            .put("requestType", requestType)
                            .put("prompt", prompt)
                            .put("options", options)
                            .put("context", context));
        }
    }

    public record Configuration(Cog cog) {
        String llmApiUrl() {
            return cog.lm.llmApiUrl;
        }

        String llmModel() {
            return cog.lm.llmModel;
        }

        int globalKbCapacity() {
            return cog.globalKbCapacity;
        }

        int reasoningDepthLimit() {
            return cog.reasoningDepthLimit;
        }

        boolean broadcastInputAssertions() {
            return cog.broadcastInputAssertions;
        }

        public JSONObject toJson() {
            return new JSONObject()
                    .put("type", "configuration")
                    .put("llmApiUrl", llmApiUrl())
                    .put("llmModel", llmModel())
                    .put("globalKbCapacity", globalKbCapacity())
                    .put("reasoningDepthLimit", reasoningDepthLimit())
                    .put("broadcastInputAssertions", broadcastInputAssertions());
        }
    }
}
