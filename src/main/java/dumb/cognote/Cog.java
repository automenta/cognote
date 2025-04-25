package dumb.cognote;

import dumb.cognote.plugin.StatusUpdaterPlugin;
import dumb.cognote.plugin.TaskDecomposePlugin;
import dumb.cognote.plugin.TmsPlugin;
import dumb.cognote.tool.*;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.jetbrains.annotations.Nullable;
import org.json.JSONException;
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
import static java.util.Objects.requireNonNullElse;
import static java.util.Optional.ofNullable;

public class Cog {

    public static final String ID_PREFIX_NOTE = "note-", ID_PREFIX_LLM_ITEM = "llm_", ID_PREFIX_QUERY = "query_", ID_PREFIX_LLM_RESULT = "llmres_", ID_PREFIX_RULE = "rule_", ID_PREFIX_INPUT_ITEM = "input_", ID_PREFIX_PLUGIN = "plugin_";

    public static final String GLOBAL_KB_NOTE_ID = "kb://global";
    public static final String GLOBAL_KB_NOTE_TITLE = "Global Knowledge";
    public static final String CONFIG_NOTE_ID = "note-config";
    public static final String CONFIG_NOTE_TITLE = "System Configuration";
    public static final double INPUT_ASSERTION_BASE_PRIORITY = 10;
    static final int KB_SIZE_THRESHOLD_WARN_PERCENT = 90, KB_SIZE_THRESHOLD_HALT_PERCENT = 98;
    static final double DERIVED_PRIORITY_DECAY = 0.95;
    static final AtomicLong id = new AtomicLong(System.currentTimeMillis());
    static final String NOTES_FILE = "cognote_notes.json";
    static final int DEFAULT_KB_CAPACITY = 64 * 1024;
    static final int DEFAULT_REASONING_DEPTH = 4;
    static final boolean DEFAULT_BROADCAST_INPUT = false;
    private static final double DEFAULT_RULE_PRIORITY = 1;
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 2;
    private static final int MAX_KIF_PARSE_PREVIEW = 50, MAX_WS_PARSE_PREVIEW = 100;
    private static final int PORT = 8080;
    public final Events events;
    public final Cognition context;
    public final LM lm;
    public final Tools tools;
    final AtomicBoolean running = new AtomicBoolean(true), paused = new AtomicBoolean(true); // Start paused
    private final Plugins plugins;
    private final Reason.ReasonerManager reasonerManager;
    private final ExecutorService mainExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Object pauseLock = new Object();
    public volatile String status = "Initializing";
    volatile boolean broadcastInputAssertions;
    volatile int globalKbCapacity = DEFAULT_KB_CAPACITY;
    volatile int reasoningDepthLimit = DEFAULT_REASONING_DEPTH;

    public Cog() {
        this.events = new Events(mainExecutor);

        this.tools = new Tools();

        this.lm = new LM(this);

        var tms = new Truths.BasicTMS(events);
        var operatorRegistry = new Op.Operators();

        this.context = new Cognition(globalKbCapacity, events, tms, operatorRegistry, this);
        this.reasonerManager = new Reason.ReasonerManager(events, context);
        this.plugins = new Plugins(events, context);

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));


//        System.out.printf("System config: KBSize=%d, BroadcastInput=%b, LLM_URL=%s, LLM_Model=%s, MaxDepth=%d%n",
//                globalKbCapacity, broadcastInputAssertions, lm.llmApiUrl, lm.llmModel, reasoningDepthLimit);
    }


    public static String id(String prefix) {
        return prefix + id.incrementAndGet();
    }

    private static void shutdownExecutor(ExecutorService executor, String name) {
        if (executor == null || executor.isShutdown()) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                System.err.println(name + " did not terminate gracefully, forcing shutdown.");
                executor.shutdownNow();
                if (!executor.awaitTermination(1, TimeUnit.SECONDS))
                    System.err.println(name + " did not terminate after forced shutdown.");
            }
        } catch (InterruptedException e) {
            System.err.println("Interrupted while waiting for " + name + " shutdown.");
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
        return new Note(CONFIG_NOTE_ID, CONFIG_NOTE_TITLE, configJson.toString(2), Note.Status.IDLE); // Default status
    }


    private void setupDefaultPlugins() {
        plugins.loadPlugin(new InputPlugin());
        plugins.loadPlugin(new RetractionPlugin());
        plugins.loadPlugin(new TmsPlugin());

        plugins.loadPlugin(new TaskDecomposePlugin());
        //plugins.loadPlugin(new TestPlugin()); // Add the new test runner plugin

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
            System.err.println("Cannot restart a stopped system.");
            return;
        }
        // System starts paused, UI will unpause it
        paused.set(true);
        status = "Initializing";

        context.operators.addBuiltin();
        setupDefaultPlugins();
        plugins.initializeAll();
        reasonerManager.initializeAll();


        status("Paused (Ready to Start)"); // Initial status
        System.out.println("System initialized and paused.");
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        System.out.println("Stopping system...");

        status("Stopping");
        paused.set(false); // Ensure pause lock is released
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }

        lm.activeLlmTasks.values().forEach(f -> f.cancel(true));
        lm.activeLlmTasks.clear();

        plugins.shutdownAll();
        reasonerManager.shutdownAll();


        shutdownExecutor(mainExecutor, "Main Executor");
        events.shutdown();

        status("Stopped");
        System.out.println("System stopped.");
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
        events.emit(new SystemStatusEvent(this.status = status, context.kbCount(), context.kbTotalCapacity(), lm.activeLlmTasks.size(), context.ruleCount()));
    }

    /**
     * TODO create a 'ClearEvent' which UI listens to, decoupling it
     */
    public synchronized void clear() {
        System.out.println("Clearing all knowledge...");
        setPaused(true);
        // Retract assertions from all notes except config, global, and test notes
        context.getAllNoteIds().stream()
                .filter(noteId -> !noteId.equals(GLOBAL_KB_NOTE_ID) && !noteId.equals(CONFIG_NOTE_ID))
                .forEach(noteId -> events.emit(new RetractionRequestEvent(noteId, RetractionType.BY_NOTE, "UI-ClearAll", noteId)));
        // Retract assertions from the global KB
        context.kbGlobal().getAllAssertionIds().forEach(id -> context.truth.remove(id, "UI-ClearAll"));
        // Clear the logic context (KBs, rules, active notes)
        context.clear();


        status("Cleared");
        setPaused(false); // Unpause after clearing
        System.out.println("Knowledge cleared.");
        events.emit(new SystemStatusEvent(status, 0, globalKbCapacity, 0, 0));
    }

    public void loadRules(String filename) throws IOException {
        System.out.println("Loading expressions from: " + filename);
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
                            System.err.printf("File Processing Error (line ~%d): %s for '%s...'%n", counts[0], e.getMessage(), kifText.substring(0, Math.min(kifText.length(), MAX_KIF_PARSE_PREVIEW)));
                            e.printStackTrace();
                        }
                    }
                } else if (parenDepth < 0) {
                    System.err.printf("Mismatched parentheses near line %d: '%s'%n", counts[0], line);
                    parenDepth = 0;
                    kifBuffer.setLength(0);
                }
            }
            if (parenDepth != 0) System.err.println("Warning: Unbalanced parentheses at end of file: " + filename);
        }
        System.out.printf("Processed %d KIF blocks from %s, published %d input events.%n", counts[1], filename, counts[2]);
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

    public void updateTaskStatus(String taskId, TaskStatus status, String content) {
        events.emit(new TaskUpdateEvent(taskId, status, content));
    }

    public Answer querySync(Query query) {
        var resultFuture = new CompletableFuture<Answer>();

        Consumer<CogEvent> listener = event -> {
            // Use a standard type pattern variable for broader compatibility
            if (event instanceof Answer.AnswerEvent) {
                var result = ((Answer.AnswerEvent) event).result();
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

    public Optional<Note> note(String id) {
        // This method is implemented in CogNote
        return Optional.empty();
    }

    public void save() {

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
    }

    public record RetractedEvent(Assertion assertion, String kbId, String reason) implements AssertionEvent {
    }

    public record AssertionEvictedEvent(Assertion assertion, String kbId) implements AssertionEvent {
    }

    public record AssertionStateEvent(String assertionId, boolean isActive, String kbId) implements CogEvent {
    }

    public record TemporaryAssertionEvent(Term.Lst temporaryAssertion, Map<Term.Var, Term> bindings,
                                          String noteId) implements NoteIDEvent {
    }

    public record RuleAddedEvent(Rule rule) implements CogEvent {
    }

    public record RuleRemovedEvent(Rule rule) implements CogEvent {
    }


    public record TaskUpdateEvent(String taskId, TaskStatus status, String content) implements CogEvent {
    }

    public record SystemStatusEvent(String statusMessage, int kbCount, int kbCapacity, int taskQueueSize,
                                    int ruleCount) implements CogEvent {
    }

    public record AddedEvent(Note note) implements NoteEvent {
    }

    public record RemovedEvent(Note note) implements NoteEvent {
    }

    public record ExternalInputEvent(Term term, String sourceId, @Nullable String noteId) implements NoteIDEvent {
    }

    public record RetractionRequestEvent(String target, RetractionType type, String sourceId,
                                         @Nullable String noteId) implements NoteIDEvent {
    }

    record WebSocketBroadcastEvent(String message) implements CogEvent {
    }

    public record Query(String id, QueryType type, Term pattern, @Nullable String targetKbId,
                        Map<String, Object> parameters) {

        record QueryEvent(Query query) implements CogEvent {
        }

    }

    public record Answer(String query, QueryStatus status, List<Map<Term.Var, Term>> bindings,
                         @Nullable Explanation explanation) {
        static Answer success(String queryId, List<Map<Term.Var, Term>> bindings) {
            return new Answer(queryId, QueryStatus.SUCCESS, bindings, null);
        }

        static Answer failure(String queryId) {
            return new Answer(queryId, QueryStatus.FAILURE, List.of(), null);
        }

        static Answer error(String queryId, String message) {
            return new Answer(queryId, QueryStatus.ERROR, List.of(), new Explanation(message));
        }

        record AnswerEvent(Answer result) implements CogEvent {
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

        JSONObject toJson() {
            return new JSONObject()
                    .put("llmApiUrl", llmApiUrl())
                    .put("llmModel", llmModel())
                    .put("globalKbCapacity", globalKbCapacity())
                    .put("reasoningDepthLimit", reasoningDepthLimit())
                    .put("broadcastInputAssertions", broadcastInputAssertions());
        }
    }

    public record NoteStatusEvent(Note note, Note.Status oldStatus, Note.Status newStatus) implements NoteEvent {
    }


    static class InputPlugin extends Plugin.BasePlugin {
        @Override
        public void start(Events e, Cognition ctx) {
            super.start(e, ctx);
            e.on(ExternalInputEvent.class, this::input);
        }

        private void input(ExternalInputEvent event) {
            final var src = event.sourceId();
            var id = event.noteId();
            switch (event.term()) {
                case Term.Lst list when !list.terms.isEmpty() -> list.op().ifPresentOrElse(op -> {
                            switch (op) {
                                case KIF_OP_IMPLIES, KIF_OP_EQUIV -> inputRule(list, src, id);
                                case KIF_OP_EXISTS -> inputExists(list, src, id);
                                case KIF_OP_FORALL -> inputForall(list, src, id);
                                case "goal" -> { /* Handled by TaskDecompositionPlugin */ }
                                case "test" -> { /* Handled by TestRunnerPlugin */ }
                                default -> inputAssertion(list, src, id);
                            }
                        },
                        () -> inputAssertion(list, src, id)
                );
                case Term term when !(term instanceof Term.Lst) ->
                        System.err.println("Warning: Ignoring non-list top-level term from " + src + ": " + term.toKif());
                default -> {
                }
            }
        }

        private void inputRule(Term.Lst list, String sourceId, @Nullable String noteId) {
            try {
                var r = Rule.parseRule(Cog.id(ID_PREFIX_RULE), list, DEFAULT_RULE_PRIORITY, noteId);
                context.addRule(r);

                if (KIF_OP_EQUIV.equals(list.op().orElse(null))) {
                    context.addRule(
                            Rule.parseRule(Cog.id(ID_PREFIX_RULE),
                                    new Term.Lst(new Term.Atom(KIF_OP_IMPLIES), list.get(2), list.get(1)),
                                    DEFAULT_RULE_PRIORITY, noteId));
                }
            } catch (IllegalArgumentException e) {
                System.err.println("Invalid rule format ignored (" + sourceId + "): " + list.toKif() + " | Error: " + e.getMessage());
            }
        }

        private void inputAssertion(Term.Lst list, String sourceId, @Nullable String targetNoteId) {
            if (list.containsVar()) {
                System.err.println("Warning: Non-ground assertion input ignored (" + sourceId + "): " + list.toKif());
                return;
            }
            var op = list.op();
            var isNeg = op.filter(KIF_OP_NOT::equals).isPresent();
            var s = list.size();
            if (isNeg && s != 2) {
                System.err.println("Invalid 'not' format ignored (" + sourceId + "): " + list.toKif());
                return;
            }
            var isEq = !isNeg && op.filter(Logic.KIF_OP_EQUAL::equals).isPresent();
            var isOriented = isEq && s == 3 && list.get(1).weight() > list.get(2).weight();
            var type = list.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
            var pri = (sourceId.startsWith("llm-") ? LM.LLM_ASSERTION_BASE_PRIORITY : INPUT_ASSERTION_BASE_PRIORITY) / (1.0 + list.weight());
            context.tryCommit(new Assertion.PotentialAssertion(list, pri, Set.of(), sourceId, isEq, isNeg, isOriented, targetNoteId, type, List.of(), 0), sourceId);
        }

        private void inputExists(Term.Lst existsExpr, String sourceId, @Nullable String targetNoteId) {
            if (existsExpr.size() != 3 || !(existsExpr.get(1) instanceof Term.Lst || existsExpr.get(1) instanceof Term.Var) || !(existsExpr.get(2) instanceof Term.Lst body)) {
                System.err.println("Invalid 'exists' format ignored (" + sourceId + "): " + existsExpr.toKif());
                return;
            }
            var vars = Term.collectSpecVars(existsExpr.get(1));
            if (vars.isEmpty()) {
                publish(new ExternalInputEvent(existsExpr.get(2), sourceId + "-existsBody", targetNoteId));
            } else {
                var skolemBody = Cognition.performSkolemization(body, vars, Map.of());
                var isNeg = skolemBody.op().filter(KIF_OP_NOT::equals).isPresent();
                var isEq = !isNeg && skolemBody.op().filter(Logic.KIF_OP_EQUAL::equals).isPresent();
                var isOriented = isEq && skolemBody.size() == 3 && skolemBody.get(1).weight() > skolemBody.get(2).weight();
                var type = skolemBody.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
                var pri = (sourceId.startsWith("llm-") ? LM.LLM_ASSERTION_BASE_PRIORITY : INPUT_ASSERTION_BASE_PRIORITY) / (1.0 + skolemBody.weight());
                context.tryCommit(new Assertion.PotentialAssertion(skolemBody, pri, Set.of(), sourceId + "-skolemized", isEq, isNeg, isOriented, targetNoteId, type, List.of(), 0), sourceId + "-skolemized");
            }
        }

        private void inputForall(Term.Lst forallExpr, String sourceId, @Nullable String targetNoteId) {
            if (forallExpr.size() != 3 || !(forallExpr.get(1) instanceof Term.Lst || forallExpr.get(1) instanceof Term.Var) || !(forallExpr.get(2) instanceof Term.Lst body)) {
                System.err.println("Invalid 'forall' format ignored (" + sourceId + "): " + forallExpr.toKif());
                return;
            }
            var vars = Term.collectSpecVars(forallExpr.get(1));
            if (vars.isEmpty()) {
                publish(new ExternalInputEvent(forallExpr.get(2), sourceId + "-forallBody", targetNoteId));
            } else {
                if (body.op().filter(op -> op.equals(KIF_OP_IMPLIES) || op.equals(KIF_OP_EQUIV)).isPresent()) {
                    inputRule(body, sourceId, targetNoteId);
                } else {
                    System.out.println("Storing 'forall' as universal fact from " + sourceId + ": " + forallExpr.toKif());
                    var pri = (sourceId.startsWith("llm-") ? LM.LLM_ASSERTION_BASE_PRIORITY : INPUT_ASSERTION_BASE_PRIORITY) / (1.0 + forallExpr.weight());
                    context.tryCommit(new Assertion.PotentialAssertion(forallExpr, pri, Set.of(), sourceId, false, false, false, targetNoteId, AssertionType.UNIVERSAL, List.copyOf(vars), 0), sourceId);
                }
            }
        }
    }

    static class RetractionPlugin extends Plugin.BasePlugin {
        private static final Set<String> SYSTEM_NOTE_IDS = Set.of(GLOBAL_KB_NOTE_ID, CONFIG_NOTE_ID);

        @Override
        public void start(Events e, Cognition ctx) {
            super.start(e, ctx);
            e.on(RetractionRequestEvent.class, this::retractRequest);
            e.on(RetractedEvent.class, this::retract);
            e.on(AssertionStateEvent.class, this::changeState);
        }

        private void retractRequest(RetractionRequestEvent event) {
            var s = event.sourceId();
            switch (event.type) {
                case BY_ID -> {
                    // Check if the assertion belongs to a system note KB
                    var assertion = context.findAssertionByIdAcrossKbs(event.target());
                    if (assertion.isPresent() && SYSTEM_NOTE_IDS.contains(assertion.get().kb())) {
                        System.err.println("Attempted to retract assertion " + event.target() + " from system KB " + assertion.get().kb() + " by " + s + ". Operation ignored.");
                        return;
                    }
                    context.truth.remove(event.target(), s);
                    System.out.printf("Retraction requested for [%s] by %s in KB '%s'.%n", event.target(), s, getKb(event.noteId()).id);
                }
                case BY_NOTE -> {
                    var noteId = event.target();
                    if (SYSTEM_NOTE_IDS.contains(noteId)) {
                        System.err.println("Attempted to retract system note " + noteId + " from " + s + ". Operation ignored.");
                        return;
                    }
                    var kb = context.getAllNoteKbs().get(noteId);
                    if (kb != null) {
                        var ids = kb.getAllAssertionIds();
                        if (!ids.isEmpty()) {
                            System.out.printf("Initiating retraction of %d assertions for note %s from %s.%n", ids.size(), noteId, s);
                            new HashSet<>(ids).forEach(id -> context.truth.remove(id, s));
                        } else
                            System.out.printf("Retraction by Note ID %s from %s: No associated assertions found in its KB.%n", noteId, s);
                        context.removeNoteKb(noteId, s);
                        // RemovedEvent is now emitted by CogNote.removeNote
                        // publish(new RemovedEvent(new Note(noteId, "Removed", "")));
                    } else
                        System.out.printf("Retraction by Note ID %s from %s failed: Note KB not found.%n", noteId, s);
                }
                case BY_RULE_FORM -> {
                    try {
                        var terms = KifParser.parseKif(event.target());
                        if (terms.size() == 1 && terms.getFirst() instanceof Term.Lst rf) {
                            var removed = context.removeRule(rf);
                            System.out.println("Retract rule from " + s + ": " + (removed ? "Success" : "No match found") + " for: " + rf.toKif());
                        } else
                            System.err.println("Retract rule from " + s + ": Input is not a single valid rule KIF list: " + event.target());
                    } catch (KifParser.ParseException e) {
                        System.err.println("Retract rule from " + s + ": Parse error: " + e.getMessage());
                    }
                }
            }
        }

        private void retract(RetractedEvent event) {
            ofNullable(getKb(event.kbId())).ifPresent(kb -> kb.retractExternal(event.assertion()));
        }

        private void changeState(AssertionStateEvent event) {
            context.truth.get(event.assertionId())
                    .flatMap(a -> ofNullable(getKb(event.kbId())).map(kb -> Map.entry(kb, a)))
                    .ifPresent(e -> e.getKey().handleExternalStatusChange(e.getValue()));
        }
    }

    static class WebSocketPlugin extends Plugin.BasePlugin {
        private final MyWebSocketServer websocket;
        private final Map<String, CommandHandler> commandHandlers = new HashMap<>();

        WebSocketPlugin(InetSocketAddress addr, Cog cog) {
            this.websocket = new MyWebSocketServer(addr, cog);
        }

        @Override
        public void start(Events ev, Cognition ctx) {
            super.start(ev, ctx);

            // Register Command Handlers
            registerCommandHandler("add", new AddKifHandler(ctx.cog));
            registerCommandHandler("retract", new RetractHandler(ctx.cog));
            registerCommandHandler("query", new QueryHandler(ctx.cog));
            registerCommandHandler("pause", new PauseHandler(ctx.cog));
            registerCommandHandler("unpause", new UnpauseHandler(ctx.cog));
            registerCommandHandler("get_status", new GetStatusHandler(ctx.cog));
            registerCommandHandler("clear", new ClearHandler(ctx.cog));
            registerCommandHandler("get_config", new GetConfigHandler(ctx.cog));
            registerCommandHandler("set_config", new SetConfigHandler(ctx.cog));
            // Add more handlers for other tools/commands as needed

            // Register Event Listeners for Broadcasting
            ev.on(AssertedEvent.class, e -> sendEvent("assertion_added", assertionToJson(e.assertion(), e.kbId())));
            ev.on(RetractedEvent.class, e -> sendEvent("assertion_removed", assertionToJson(e.assertion(), e.kbId()).put("reason", e.reason())));
            ev.on(AssertionEvictedEvent.class, e -> sendEvent("assertion_evicted", assertionToJson(e.assertion(), e.kbId())));
            ev.on(AssertionStateEvent.class, e -> sendEvent("assertion_state_changed", new JSONObject().put("id", e.assertionId()).put("isActive", e.isActive()).put("kbId", e.kbId())));
            ev.on(RuleAddedEvent.class, e -> sendEvent("rule_added", ruleToJson(e.rule())));
            ev.on(RuleRemovedEvent.class, e -> sendEvent("rule_removed", ruleToJson(e.rule())));
            ev.on(TaskUpdateEvent.class, e -> sendEvent("task_update", taskUpdateToJson(e)));
            ev.on(SystemStatusEvent.class, e -> sendEvent("system_status", systemStatusToJson(e)));
            ev.on(WebSocketBroadcastEvent.class, e -> safeBroadcast(e.message())); // Keep for raw messages if needed

            // Broadcast external input if configured
            if (ctx.cog.broadcastInputAssertions) {
                ev.on(ExternalInputEvent.class, this::onExternalInput);
            }

            websocket.start();
        }

        @Override
        public void stop() {
            try {
                websocket.stop(MyWebSocketServer.WS_STOP_TIMEOUT_MS);
                System.out.println("WebSocket server stopped.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Interrupted while stopping WebSocket server.");
            } catch (Exception e) {
                System.err.println("Error stopping WebSocket server: " + e.getMessage());
            }
        }

        private void registerCommandHandler(String command, CommandHandler handler) {
            commandHandlers.put(command, handler);
        }

        private void onExternalInput(ExternalInputEvent event) {
            // Only broadcast if it's a list term (potential assertion/rule/goal)
            if (event.term() instanceof Term.Lst list) {
                 sendEvent("external_input", new JSONObject()
                         .put("sourceId", event.sourceId())
                         .put("noteId", requireNonNullElse(event.noteId(), JSONObject.NULL))
                         .put("kif", list.toKif()));
            }
        }

        private void sendEvent(String eventType, JSONObject payload) {
            JSONObject eventMessage = new JSONObject();
            eventMessage.put("type", "event");
            eventMessage.put("event", eventType);
            eventMessage.put("payload", payload);
            safeBroadcast(eventMessage.toString());
        }

        private void sendResponse(WebSocket conn, String command, @Nullable String requestId, JSONObject payload, @Nullable JSONObject error) {
            JSONObject responseMessage = new JSONObject();
            responseMessage.put("type", "response");
            responseMessage.put("command", command);
            if (requestId != null) {
                responseMessage.put("id", requestId);
            }
            if (payload != null) {
                responseMessage.put("payload", payload);
            }
            if (error != null) {
                responseMessage.put("error", error);
            }
            conn.send(responseMessage.toString());
        }

        private void safeBroadcast(String message) {
            try {
                if (!websocket.getConnections().isEmpty()) websocket.broadcast(message);
            } catch (Exception e) {
                if (!(e instanceof ConcurrentModificationException || ofNullable(e.getMessage()).map(m -> m.contains("closed") || m.contains("reset") || m.contains("Broken pipe")).orElse(false)))
                    System.err.println("Error during WebSocket broadcast: " + e.getMessage());
            }
        }

        // --- JSON Payload Helpers ---

        private JSONObject assertionToJson(Assertion assertion, String kbId) {
            return new JSONObject()
                    .put("id", assertion.id())
                    .put("kif", assertion.toKifString())
                    .put("priority", assertion.pri())
                    .put("timestamp", assertion.timestamp())
                    .put("sourceNoteId", requireNonNullElse(assertion.sourceNoteId(), JSONObject.NULL))
                    .put("type", assertion.type().name())
                    .put("isEqual", assertion.isEqual())
                    .put("isNegative", assertion.isNegative())
                    .put("isOriented", assertion.isOriented())
                    .put("derivationDepth", assertion.derivationDepth())
                    .put("isActive", assertion.isActive())
                    .put("kbId", kbId);
        }

        private JSONObject ruleToJson(Rule rule) {
            return new JSONObject()
                    .put("id", rule.id())
                    .put("kif", rule.toKifString())
                    .put("priority", rule.priority())
                    .put("sourceNoteId", requireNonNullElse(rule.sourceNoteId(), JSONObject.NULL));
        }

        private JSONObject taskUpdateToJson(TaskUpdateEvent event) {
            return new JSONObject()
                    .put("taskId", event.taskId())
                    .put("status", event.status().name())
                    .put("content", event.content());
        }

        private JSONObject systemStatusToJson(SystemStatusEvent event) {
            return new JSONObject()
                    .put("statusMessage", event.statusMessage())
                    .put("kbCount", event.kbCount())
                    .put("kbCapacity", event.kbCapacity())
                    .put("taskQueueSize", event.taskQueueSize())
                    .put("ruleCount", event.ruleCount());
        }


        // --- Command Handling Interface and Implementations ---

        private interface CommandHandler {
            CompletableFuture<JSONObject> handle(JSONObject payload, WebSocket conn);
        }

        private class AddKifHandler implements CommandHandler {
            private final Cog cog;

            AddKifHandler(Cog cog) {
                this.cog = cog;
            }

            @Override
            public CompletableFuture<JSONObject> handle(JSONObject payload, WebSocket conn) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        String kif = payload.getString("kif");
                        String noteId = payload.optString("noteId", null);
                        String sourceId = "ws:" + conn.getRemoteSocketAddress().toString();

                        // Use the existing ExternalInputEvent mechanism
                        KifParser.parseKif(kif).forEach(term ->
                                cog.events.emit(new ExternalInputEvent(term, sourceId, noteId))
                        );

                        return new JSONObject().put("status", "success").put("message", "KIF submitted for processing.");
                    } catch (JSONException e) {
                        throw new IllegalArgumentException("Invalid payload for 'add' command: Missing 'kif' or invalid format.", e);
                    } catch (KifParser.ParseException e) {
                        throw new IllegalArgumentException("Failed to parse KIF: " + e.getMessage(), e);
                    } catch (Exception e) {
                        throw new RuntimeException("Internal error processing 'add' command: " + e.getMessage(), e);
                    }
                }, cog.mainExecutor); // Execute asynchronously
            }
        }

        private class RetractHandler implements CommandHandler {
            private final Cog cog;

            RetractHandler(Cog cog) {
                this.cog = cog;
            }

            @Override
            public CompletableFuture<JSONObject> handle(JSONObject payload, WebSocket conn) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        String target = payload.getString("target"); // Assertion ID, Note ID, or Rule KIF
                        String typeStr = payload.optString("type", "BY_ID"); // BY_ID, BY_NOTE, BY_RULE_FORM
                        RetractionType type;
                        try {
                            type = RetractionType.valueOf(typeStr.toUpperCase());
                        } catch (IllegalArgumentException e) {
                            throw new IllegalArgumentException("Invalid retraction type: " + typeStr + ". Must be BY_ID, BY_NOTE, or BY_RULE_FORM.");
                        }
                        String noteId = payload.optString("noteId", null); // Relevant for BY_NOTE
                        String sourceId = "ws:" + conn.getRemoteSocketAddress().toString();

                        cog.events.emit(new RetractionRequestEvent(target, type, sourceId, noteId));

                        return new JSONObject().put("status", "success").put("message", "Retraction request submitted.");
                    } catch (JSONException e) {
                        throw new IllegalArgumentException("Invalid payload for 'retract' command: Missing 'target' or invalid format.", e);
                    } catch (IllegalArgumentException e) {
                        throw e; // Re-throw specific validation errors
                    } catch (Exception e) {
                        throw new RuntimeException("Internal error processing 'retract' command: " + e.getMessage(), e);
                    }
                }, cog.mainExecutor);
            }
        }

        private class QueryHandler implements CommandHandler {
            private final Cog cog;

            QueryHandler(Cog cog) {
                this.cog = cog;
            }

            @Override
            public CompletableFuture<JSONObject> handle(JSONObject payload, WebSocket conn) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        String kifPattern = payload.getString("kif_pattern");
                        String typeStr = payload.optString("type", "ASK_BINDINGS"); // ASK_BINDINGS, ASK_TRUE_FALSE, ACHIEVE_GOAL
                        QueryType type;
                        try {
                            type = QueryType.valueOf(typeStr.toUpperCase());
                        } catch (IllegalArgumentException e) {
                            throw new IllegalArgumentException("Invalid query type: " + typeStr + ". Must be ASK_BINDINGS, ASK_TRUE_FALSE, or ACHIEVE_GOAL.");
                        }
                        String targetKbId = payload.optString("targetKbId", null);
                        JSONObject paramsJson = payload.optJSONObject("parameters");
                        Map<String, Object> parameters = new HashMap<>();
                        if (paramsJson != null) {
                            paramsJson.keySet().forEach(key -> parameters.put(key, paramsJson.get(key)));
                        }

                        Term pattern = KifParser.parseKif(kifPattern).stream()
                                .findFirst()
                                .orElseThrow(() -> new IllegalArgumentException("Failed to parse KIF pattern: No term found."));

                        String queryId = Cog.id(ID_PREFIX_QUERY);
                        Query query = new Query(queryId, type, pattern, targetKbId, parameters);

                        // Use the synchronous query method for simplicity over WS
                        Answer answer = cog.querySync(query);

                        JSONObject resultPayload = new JSONObject();
                        resultPayload.put("queryId", answer.query());
                        resultPayload.put("status", answer.status().name());
                        if (answer.explanation() != null) {
                            resultPayload.put("explanation", answer.explanation().message());
                        }

                        if (answer.bindings() != null && !answer.bindings().isEmpty()) {
                            var bindingsArray = new org.json.JSONArray();
                            for (Map<Term.Var, Term> bindingSet : answer.bindings()) {
                                var bindingJson = new JSONObject();
                                bindingSet.forEach((var, term) -> bindingJson.put(var.name(), term.toKif()));
                                bindingsArray.put(bindingJson);
                            }
                            resultPayload.put("bindings", bindingsArray);
                        } else {
                             resultPayload.put("bindings", new org.json.JSONArray()); // Ensure bindings is always an array
                        }


                        return resultPayload;

                    } catch (JSONException e) {
                        throw new IllegalArgumentException("Invalid payload for 'query' command: Missing 'kif_pattern' or invalid format.", e);
                    } catch (KifParser.ParseException e) {
                        throw new IllegalArgumentException("Failed to parse KIF pattern: " + e.getMessage(), e);
                    } catch (IllegalArgumentException e) {
                        throw e; // Re-throw specific validation errors
                    } catch (RuntimeException e) {
                         // querySync can throw RuntimeException (Timeout, ExecutionException)
                         throw e;
                    } catch (Exception e) {
                        throw new RuntimeException("Internal error processing 'query' command: " + e.getMessage(), e);
                    }
                }, cog.mainExecutor);
            }
        }

        private class PauseHandler implements CommandHandler {
            private final Cog cog;

            PauseHandler(Cog cog) {
                this.cog = cog;
            }

            @Override
            public CompletableFuture<JSONObject> handle(JSONObject payload, WebSocket conn) {
                 return CompletableFuture.supplyAsync(() -> {
                     cog.setPaused(true);
                     return new JSONObject().put("status", "success").put("message", "System paused.");
                 }, cog.mainExecutor);
            }
        }

        private class UnpauseHandler implements CommandHandler {
            private final Cog cog;

            UnpauseHandler(Cog cog) {
                this.cog = cog;
            }

            @Override
            public CompletableFuture<JSONObject> handle(JSONObject payload, WebSocket conn) {
                return CompletableFuture.supplyAsync(() -> {
                    cog.setPaused(false);
                    return new JSONObject().put("status", "success").put("message", "System unpaused.");
                }, cog.mainExecutor);
            }
        }

        private class GetStatusHandler implements CommandHandler {
            private final Cog cog;

            GetStatusHandler(Cog cog) {
                this.cog = cog;
            }

            @Override
            public CompletableFuture<JSONObject> handle(JSONObject payload, WebSocket conn) {
                return CompletableFuture.completedFuture(systemStatusToJson(
                        new SystemStatusEvent(cog.status, cog.context.kbCount(), cog.context.kbTotalCapacity(), cog.lm.activeLlmTasks.size(), cog.context.ruleCount())
                ));
            }
        }

        private class ClearHandler implements CommandHandler {
            private final Cog cog;

            ClearHandler(Cog cog) {
                this.cog = cog;
            }

            @Override
            public CompletableFuture<JSONObject> handle(JSONObject payload, WebSocket conn) {
                return CompletableFuture.supplyAsync(() -> {
                    cog.clear();
                    return new JSONObject().put("status", "success").put("message", "Knowledge base cleared.");
                }, cog.mainExecutor);
            }
        }

        private class GetConfigHandler implements CommandHandler {
            private final Cog cog;

            GetConfigHandler(Cog cog) {
                this.cog = cog;
            }

            @Override
            public CompletableFuture<JSONObject> handle(JSONObject payload, WebSocket conn) {
                return CompletableFuture.completedFuture(new Configuration(cog).toJson());
            }
        }

        private class SetConfigHandler implements CommandHandler {
            private final Cog cog;

            SetConfigHandler(Cog cog) {
                this.cog = cog;
            }

            @Override
            public CompletableFuture<JSONObject> handle(JSONObject payload, WebSocket conn) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        if (payload.has("llmApiUrl")) cog.lm.llmApiUrl = payload.getString("llmApiUrl");
                        if (payload.has("llmModel")) cog.lm.llmModel = payload.getString("llmModel");
                        if (payload.has("globalKbCapacity")) cog.globalKbCapacity = payload.getInt("globalKbCapacity");
                        if (payload.has("reasoningDepthLimit")) cog.reasoningDepthLimit = payload.getInt("reasoningDepthLimit");
                        if (payload.has("broadcastInputAssertions")) {
                            boolean broadcast = payload.getBoolean("broadcastInputAssertions");
                            if (cog.broadcastInputAssertions != broadcast) {
                                cog.broadcastInputAssertions = broadcast;
                                // Re-register/unregister the listener based on the new value
                                if (broadcast) {
                                    cog.events.on(ExternalInputEvent.class, WebSocketPlugin.this::onExternalInput);
                                } else {
                                    cog.events.off(ExternalInputEvent.class, WebSocketPlugin.this::onExternalInput);
                                }
                            }
                        }

                        // Optionally save config to note-config here if persistence is implemented

                        return new JSONObject().put("status", "success").put("message", "Configuration updated.").put("config", new Configuration(cog).toJson());
                    } catch (JSONException e) {
                        throw new IllegalArgumentException("Invalid payload for 'set_config' command: Invalid format.", e);
                    } catch (Exception e) {
                        throw new RuntimeException("Internal error processing 'set_config' command: " + e.getMessage(), e);
                    }
                }, cog.mainExecutor);
            }
        }


        class MyWebSocketServer extends WebSocketServer {
            private static final int WS_STOP_TIMEOUT_MS = 1000;
            private static final int WS_CONNECTION_LOST_TIMEOUT_MS = 100;
            private final Cog cog;

            MyWebSocketServer(InetSocketAddress address, Cog cog) {
                super(address);
                this.cog = cog;
            }

            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
                System.out.println("WS Client connected: " + conn.getRemoteSocketAddress());
                // Optionally send initial status or config on connect
                sendEvent("system_status", systemStatusToJson(
                        new SystemStatusEvent(cog.status, cog.context.kbCount(), cog.context.kbTotalCapacity(), cog.lm.activeLlmTasks.size(), cog.context.ruleCount())
                ));
                sendEvent("config_updated", new Configuration(cog).toJson());
            }

            @Override
            public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                System.out.println("WS Client disconnected: " + conn.getRemoteSocketAddress() + " Code: " + code + " Reason: " + requireNonNullElse(reason, "N/A"));
            }

            @Override
            public void onStart() {
                System.out.println("System WebSocket listener active on port " + getPort() + ".");
                setConnectionLostTimeout(WS_CONNECTION_LOST_TIMEOUT_MS);
            }

            @Override
            public void onError(WebSocket conn, Exception ex) {
                var addr = ofNullable(conn).map(WebSocket::getRemoteSocketAddress).map(Object::toString).orElse("server");
                var msg = ofNullable(ex.getMessage()).orElse("");
                if (ex instanceof IOException && (msg.contains("Socket closed") || msg.contains("Connection reset") || msg.contains("Broken pipe")))
                    System.err.println("WS Network Info from " + addr + ": " + msg);
                else {
                    System.err.println("WS Error from " + addr + ": " + msg);
                    ex.printStackTrace();
                }
            }

            @Override
            public void onMessage(WebSocket conn, String message) {
                JSONObject requestJson;
                String requestId = null;
                String command = null;

                try {
                    requestJson = new JSONObject(message);
                    String type = requestJson.optString("type");
                    requestId = requestJson.optString("id", null); // Optional request ID
                    command = requestJson.optString("command");
                    JSONObject payload = requestJson.optJSONObject("payload");

                    if (!"command".equals(type) || command.isEmpty()) {
                        sendResponse(conn, command, requestId, null, new JSONObject().put("message", "Invalid message format. Expected type 'command' with a 'command' field.").put("code", "INVALID_FORMAT"));
                        return;
                    }

                    CommandHandler handler = commandHandlers.get(command);

                    if (handler == null) {
                        sendResponse(conn, command, requestId, null, new JSONObject().put("message", "Unknown command: " + command).put("code", "UNKNOWN_COMMAND"));
                        return;
                    }

                    // Execute handler asynchronously and send response when complete
                    handler.handle(payload, conn)
                            .whenComplete((resultPayload, ex) -> {
                                if (ex == null) {
                                    sendResponse(conn, command, requestId, resultPayload, null);
                                } else {
                                    // Unwrap common exception types
                                    Throwable cause = ex;
                                    if (cause instanceof CompletionException || cause instanceof ExecutionException) {
                                        cause = cause.getCause();
                                    }
                                    String errorMessage = ofNullable(cause.getMessage()).orElse("An unexpected error occurred.");
                                    String errorCode = "INTERNAL_ERROR"; // Default error code

                                    if (cause instanceof IllegalArgumentException) {
                                        errorCode = "INVALID_ARGUMENT";
                                    } else if (cause instanceof TimeoutException) {
                                        errorCode = "TIMEOUT";
                                        errorMessage = "Command execution timed out.";
                                    } else if (cause instanceof Tool.ToolExecutionException) {
                                         errorCode = "TOOL_EXECUTION_ERROR";
                                    }
                                    // Add more specific error code mappings if needed

                                    System.err.println("Error handling command '" + command + "' from " + conn.getRemoteSocketAddress() + ": " + errorMessage);
                                    if (!(cause instanceof IllegalArgumentException || cause instanceof Tool.ToolExecutionException)) { // Don't print stack trace for expected validation errors
                                         cause.printStackTrace();
                                    }

                                    sendResponse(conn, command, requestId, null, new JSONObject().put("message", errorMessage).put("code", errorCode));
                                }
                            });

                } catch (JSONException e) {
                    System.err.printf("WS JSON Parse Error from %s: %s | Original: %s...%n", conn.getRemoteSocketAddress(), e.getMessage(), message.substring(0, Math.min(message.length(), MAX_WS_PARSE_PREVIEW)));
                    sendResponse(conn, command, requestId, null, new JSONObject().put("message", "Failed to parse JSON message: " + e.getMessage()).put("code", "JSON_PARSE_ERROR"));
                } catch (Exception e) {
                    System.err.println("Unexpected WS message processing error from " + conn.getRemoteSocketAddress() + ": " + e.getMessage());
                    e.printStackTrace();
                    sendResponse(conn, command, requestId, null, new JSONObject().put("message", "Internal server error processing message.").put("code", "INTERNAL_ERROR"));
                }
            }
        }
    }


    @Deprecated
    public record LlmInfoEvent(UI.AttachmentViewModel llmItem) implements CogEvent {
        @Override
        public String assocNote() {
            return llmItem.noteId();
        }
    }
}
