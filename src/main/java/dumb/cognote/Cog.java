package dumb.cognote;

import dumb.cognote.plugin.StatusUpdaterPlugin;
import dumb.cognote.plugin.TaskDecomposePlugin;
import dumb.cognote.plugin.TmsPlugin;
import dumb.cognote.tool.*;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
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
import static java.util.Objects.requireNonNullElse;
import static java.util.Optional.ofNullable;

public class Cog {

    public static final String ID_PREFIX_NOTE = "note-", ID_PREFIX_LLM_ITEM = "llm_", ID_PREFIX_QUERY = "query_", ID_PREFIX_LLM_RESULT = "llmres_", ID_PREFIX_RULE = "rule_", ID_PREFIX_INPUT_ITEM = "input_", ID_PREFIX_PLUGIN = "plugin_";

    public static final String GLOBAL_KB_NOTE_ID = "kb://global";
    public static final String GLOBAL_KB_NOTE_TITLE = "Global Knowledge";
    public static final String CONFIG_NOTE_ID = "note-config";
    public static final String CONFIG_NOTE_TITLE = "System Configuration";
    static final int KB_SIZE_THRESHOLD_WARN_PERCENT = 90, KB_SIZE_THRESHOLD_HALT_PERCENT = 98;
    static final double INPUT_ASSERTION_BASE_PRIORITY = 10;
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

    private void status(String status) {
        events.emit(new SystemStatusEvent(this.status = status, context.kbCount(), context.kbTotalCapacity(), lm.activeLlmTasks.size(), context.ruleCount()));
    }

    /**
     * TODO create a 'ClearEvent' which UI listens to, decoupling it
     */
    public synchronized void clear() {
        System.out.println("Clearing all knowledge...");
        setPaused(true);
        context.getAllNoteIds().stream()
                .filter(noteId -> !noteId.equals(GLOBAL_KB_NOTE_ID) && !noteId.equals(CONFIG_NOTE_ID))
                .forEach(noteId -> events.emit(new RetractionRequestEvent(noteId, RetractionType.BY_NOTE, "UI-ClearAll", noteId)));
        context.kbGlobal().getAllAssertionIds().forEach(id -> context.truth.remove(id, "UI-ClearAll"));
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
            if (event instanceof Cog.Answer.AnswerEvent answerEvent && answerEvent.result().query().equals(query.id())) {
                resultFuture.complete(answerEvent.result());
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
        return Optional.empty();
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
                                         String noteId) implements NoteIDEvent {
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
                                case KIF_OP_IMPLIES, KIF_OP_EQUIV -> inputRule(list, src);
                                case KIF_OP_EXISTS -> inputExists(list, src, id);
                                case KIF_OP_FORALL -> inputForall(list, src, id);
                                case "goal" -> { /* Handled by TaskDecompositionPlugin */ }
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

        private void inputRule(Term.Lst list, String sourceId) {
            try {
                var r = Rule.parseRule(Cog.id(ID_PREFIX_RULE), list, DEFAULT_RULE_PRIORITY);
                context.addRule(r);

                if (KIF_OP_EQUIV.equals(list.op().orElse(null))) {
                    context.addRule(
                            Rule.parseRule(Cog.id(ID_PREFIX_RULE),
                                    new Term.Lst(new Term.Atom(KIF_OP_IMPLIES), list.get(2), list.get(1)),
                                    DEFAULT_RULE_PRIORITY));
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
            var isEq = !isNeg && op.filter(KIF_OP_EQUAL::equals).isPresent();
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
                var isEq = !isNeg && skolemBody.op().filter(KIF_OP_EQUAL::equals).isPresent();
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
                    inputRule(body, sourceId);
                } else {
                    System.out.println("Storing 'forall' as universal fact from " + sourceId + ": " + forallExpr.toKif());
                    var pri = (sourceId.startsWith("llm-") ? LM.LLM_ASSERTION_BASE_PRIORITY : INPUT_ASSERTION_BASE_PRIORITY) / (1.0 + forallExpr.weight());
                    context.tryCommit(new Assertion.PotentialAssertion(forallExpr, pri, Set.of(), sourceId, false, false, false, targetNoteId, AssertionType.UNIVERSAL, List.copyOf(vars), 0), sourceId);
                }
            }
        }
    }

    static class RetractionPlugin extends Plugin.BasePlugin {
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
                    context.truth.remove(event.target(), s);
                    System.out.printf("Retraction requested for [%s] by %s in KB '%s'.%n", event.target(), s, getKb(event.noteId()).id);
                }
                case BY_NOTE -> {
                    var noteId = event.target();
                    if (CONFIG_NOTE_ID.equals(noteId)) {
                        System.err.println("Attempted to retract config note " + noteId + " from " + s + ". Operation ignored.");
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

        WebSocketPlugin(InetSocketAddress addr, Cog cog) {
            this.websocket = new MyWebSocketServer(addr);
        }

        @Override
        public void start(Events ev, Cognition ctx) {
            ev.on(AssertedEvent.class, e -> broadcastMessage("assert-added", e.assertion(), e.kbId()));
            ev.on(RetractedEvent.class, e -> broadcastMessage("retract", e.assertion(), e.kbId()));
            ev.on(AssertionEvictedEvent.class, e -> broadcastMessage("evict", e.assertion(), e.kbId()));
            ev.on(TaskUpdateEvent.class, e -> broadcastMessage("llm-update", e));
            ev.on(WebSocketBroadcastEvent.class, e -> safeBroadcast(e.message()));
            if (ctx.cog.broadcastInputAssertions) ev.on(ExternalInputEvent.class, this::onExternalInput);

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

        private void onExternalInput(ExternalInputEvent event) {
            if (event.term() instanceof Term.Lst list) {
                var tempId = Cog.id(ID_PREFIX_INPUT_ITEM);
                var pri = (event.sourceId().startsWith("llm-") ? LM.LLM_ASSERTION_BASE_PRIORITY : INPUT_ASSERTION_BASE_PRIORITY) / (1.0 + list.weight());
                var type = list.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
                var kbId = requireNonNullElse(event.noteId(), GLOBAL_KB_NOTE_ID);
                broadcastMessage("assert-input", new Assertion(tempId, list, pri, System.currentTimeMillis(), event.noteId(), Set.of(), type, false, false, false, List.of(), 0, true, kbId), kbId);
            }
        }

        private void broadcastMessage(String type, Assertion assertion, String kbId) {
            var kif = assertion.toKifString();
            var msg = switch (type) {
                case "assert-added", "assert-input" ->
                        String.format("%s %.4f %s [%s] {type:%s, depth:%d, kb:%s}", type, assertion.pri(), kif, assertion.id(), assertion.type(), assertion.derivationDepth(), kbId);
                case "retract", "evict" -> String.format("%s %s", type, assertion.id());
                default -> String.format("%s %.4f %s [%s]", type, assertion.pri(), kif, assertion.id());
            };
            safeBroadcast(msg);
        }

        private void broadcastMessage(String type, TaskUpdateEvent event) {
            safeBroadcast(String.format("TaskUpdate %s {status:%s, content:\"%s\"}",
                    event.taskId(), event.status(), event.content().replace("\"", "\\\"")));
        }

        private void safeBroadcast(String message) {
            try {
                if (!websocket.getConnections().isEmpty()) websocket.broadcast(message);
            } catch (Exception e) {
                if (!(e instanceof ConcurrentModificationException || ofNullable(e.getMessage()).map(m -> m.contains("closed") || m.contains("reset") || m.contains("Broken pipe")).orElse(false)))
                    System.err.println("Error during WebSocket broadcast: " + e.getMessage());
            }
        }

        class MyWebSocketServer extends WebSocketServer {
            private static final int WS_STOP_TIMEOUT_MS = 1000;
            private static final int WS_CONNECTION_LOST_TIMEOUT_MS = 100;

            MyWebSocketServer(InetSocketAddress address) {
                super(address);
            }

            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
                System.out.println("WS Client connected: " + conn.getRemoteSocketAddress());
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
                var trimmed = message.trim();
                if (trimmed.isEmpty()) return;
                var sourceId = "ws:" + conn.getRemoteSocketAddress().toString();
                var parts = trimmed.split("\\s+", 2);
                var command = parts[0].toLowerCase();
                var argument = (parts.length > 1) ? parts[1] : "";

                var tools = cog().tools;
                var retractToolOpt = tools.get("retract_assertion");
                var queryToolOpt = tools.get("run_query");
                var addKifToolOpt = tools.get("assert_kif");

                switch (command) {
                    case "retract" -> {
                        if (!argument.isEmpty()) {
                            retractToolOpt.ifPresentOrElse(tool -> {
                                Map<String, Object> params = new HashMap<>();
                                params.put("target", argument);
                                params.put("type", "BY_ID");
                                tool.execute(params).whenComplete((result, ex) -> {
                                    System.out.println("WS Retract tool result: " + result);
                                    if (ex != null) System.err.println("WS Retract tool error: " + ex.getMessage());
                                    conn.send("result: " + (ex != null ? "Error: " + ex.getMessage() : result.toString()));
                                });
                            }, () -> conn.send("error: Retract tool not available."));
                        } else conn.send("error: Missing assertion ID for retract.");
                    }
                    case "query" -> {
                        if (!argument.isEmpty()) {
                            queryToolOpt.ifPresentOrElse(tool -> {
                                Map<String, Object> params = new HashMap<>();
                                params.put("kif_pattern", argument);
                                tool.execute(params).whenComplete((result, ex) -> {
                                    System.out.println("WS Query tool result:\n" + result);
                                    if (ex != null) System.err.println("WS Query tool error: " + ex.getMessage());
                                    conn.send("result: " + (ex != null ? "Error: " + ex.getMessage() : result.toString()));
                                });
                            }, () -> conn.send("error: Query tool not available."));
                        } else conn.send("error: Missing KIF pattern for query.");
                    }
                    case "add" -> {
                        if (!argument.isEmpty()) {
                            addKifToolOpt.ifPresentOrElse(tool -> {
                                Map<String, Object> params = new HashMap<>();
                                params.put("kif_assertion", argument);
                                tool.execute(params).whenComplete((result, ex) -> {
                                    System.out.println("WS Add tool result: " + result);
                                    if (ex != null) System.err.println("WS Add tool error: " + ex.getMessage());
                                    conn.send("result: " + (ex != null ? "Error: " + ex.getMessage() : result.toString()));
                                });
                            }, () -> conn.send("error: Add KIF tool not available."));
                        } else conn.send("error: Missing KIF assertion for add.");
                    }
                    default -> {
                        try {
                            KifParser.parseKif(trimmed).forEach(term -> events.emit(new ExternalInputEvent(term, sourceId, null)));
                        } catch (ClassCastException e) {
                            System.err.printf("WS Message Parse Error from %s: %s | Original: %s...%n", sourceId, e.getMessage(), trimmed.substring(0, Math.min(trimmed.length(), MAX_WS_PARSE_PREVIEW)));
                            conn.send("error Parse error: " + e.getMessage());
                        } catch (Exception e) {
                            System.err.println("Unexpected WS message processing error from " + sourceId + ": " + e.getMessage());
                            e.printStackTrace();
                            conn.send("error Internal server error processing message.");
                        }
                    }
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
