package dumb.cognote;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import javax.swing.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.DoubleBinaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static dumb.cognote.Logic.*;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static java.util.Optional.ofNullable;

/**
 * Cognition Reasoning engine
 */
public class Cog {

    // --- ID Prefixes ---
    public static final String ID_PREFIX_NOTE = "note-";
    public static final String ID_PREFIX_LLM_ITEM = "llm_";
    public static final String ID_PREFIX_QUERY = "query_";
    public static final String ID_PREFIX_LLM_RESULT = "llmres_";
    // --- Special Note IDs & Titles ---
    public static final String GLOBAL_KB_NOTE_ID = "kb://global";
    public static final String GLOBAL_KB_NOTE_TITLE = "Global Knowledge";
    public static final String CONFIG_NOTE_ID = "note-config";
    // --- End ID Prefixes ---
    public static final String CONFIG_NOTE_TITLE = "System Configuration";
    static final double DERIVED_PRIORITY_DECAY = 0.95;
    static final int KB_SIZE_THRESHOLD_WARN_PERCENT = 90;
    static final int KB_SIZE_THRESHOLD_HALT_PERCENT = 98;
    // --- End Special Note IDs & Titles ---
    static final AtomicLong idCounter = new AtomicLong(System.currentTimeMillis());
    static final String ID_PREFIX_RULE = "rule_";
    static final String ID_PREFIX_INPUT_ITEM = "input_";
    // --- System Parameters ---
    static final int HTTP_TIMEOUT_SECONDS = 90; // Still relevant for LLM calls via LangChain4j config
    static final double INPUT_ASSERTION_BASE_PRIORITY = 10.0;
    static final String ID_PREFIX_PLUGIN = "plugin_";
    // --- Configuration Defaults ---
    private static final String NOTES_FILE = "cognote_notes.json";
    private static final int DEFAULT_KB_CAPACITY = 64 * 1024;
    private static final int DEFAULT_REASONING_DEPTH = 4;
    private static final boolean DEFAULT_BROADCAST_INPUT = false;
    private static final double DEFAULT_RULE_PRIORITY = 1.0;
    private static final int WS_STOP_TIMEOUT_MS = 1000;
    private static final int WS_CONNECTION_LOST_TIMEOUT_MS = 100;
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 2;
    private static final int MAX_KIF_PARSE_PREVIEW = 50;
    // --- End System Parameters ---
    private static final int MAX_WS_PARSE_PREVIEW = 100;
    final Events events;
    final Cognition context;
    final AtomicBoolean running = new AtomicBoolean(true);
    final AtomicBoolean paused = new AtomicBoolean(false);
    final LM lm = new LM(this); // LM instance
    // final HttpClient http; // Removed - LM now uses LangChain4j's internal client
    final UI ui;
    final MyWebSocketServer websocket;
    private final Plugins plugins;
    private final Reason.ReasonerManager reasonerManager;
    private final ExecutorService mainExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Object pauseLock = new Object();
    volatile String systemStatus = "Initializing";
    volatile boolean broadcastInputAssertions;
    private volatile int globalKbCapacity;
    private volatile int reasoningDepthLimit;

    public Cog(int port, UI ui) {
        this.ui = requireNonNull(ui, "SwingUI cannot be null");
        this.events = new Events(mainExecutor);
        // Load notes and config first to set initial LM properties
        loadNotesAndConfig();
        // Now reconfigure LM based on loaded config
        lm.reconfigure();


        var tms = new TruthMaintenance.BasicTMS(events);
        var operatorRegistry = new Op.Operators();

        this.context = new Cognition(globalKbCapacity, events, tms, operatorRegistry, this);
        this.reasonerManager = new Reason.ReasonerManager(events, context);
        this.plugins = new Plugins(events, context);

        this.websocket = new MyWebSocketServer(new InetSocketAddress(port));

        System.out.printf("System config: Port=%d, KBSize=%d, BroadcastInput=%b, LLM_URL=%s, LLM_Model=%s, MaxDepth=%d%n",
                port, globalKbCapacity, broadcastInputAssertions, lm.llmApiUrl, lm.llmModel, reasoningDepthLimit);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            var port = 8887;
            String rulesFile = null;

            for (int i = 0; i < args.length; i++) {
                try {
                    switch (args[i]) {
                        case "-p", "--port" -> port = Integer.parseInt(args[++i]);
                        case "-r", "--rules" -> rulesFile = args[++i];
                        default -> System.err.println("Warning: Unknown option: " + args[i] + ". Config via UI/JSON.");
                    }
                } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
                    System.err.printf("Error parsing argument for %s: %s%n", (i > 0 ? args[i - 1] : args[i]), e.getMessage());
                    printUsageAndExit();
                }
            }

            UI ui = null;
            try {
                ui = new UI(null);
                var server = new Cog(port, ui); // Cog constructor now loads config and reconfigures LM
                ui.setSystemReference(server);
                Runtime.getRuntime().addShutdownHook(new Thread(server::stopSystem));
                server.startSystem();
                if (rulesFile != null) server.loadExpressionsFromFile(rulesFile);
                ui.setVisible(true);
            } catch (Exception e) {
                System.err.println("Initialization/Startup failed: " + e.getMessage());
                e.printStackTrace();
                ofNullable(ui).ifPresent(JFrame::dispose);
                System.exit(1);
            }
        });
    }

    private static void printUsageAndExit() {
        System.err.printf("Usage: java %s [-p port] [-r rules_file.kif]%n", Cog.class.getName());
        System.err.println("Note: Most configuration is now managed via the UI and persisted in " + NOTES_FILE);
        System.exit(1);
    }

    public static String generateId(String prefix) {
        return prefix + idCounter.incrementAndGet();
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
        return new Note(CONFIG_NOTE_ID, CONFIG_NOTE_TITLE, configJson.toString(2));
    }

    private static List<Note> loadNotesFromFile() {
        Path filePath = Paths.get(NOTES_FILE);
        if (!Files.exists(filePath)) return new ArrayList<>(List.of(createDefaultConfigNote()));
        try {
            var jsonText = Files.readString(filePath);
            var jsonArray = new JSONArray(new JSONTokener(jsonText));
            List<Note> notes = IntStream.range(0, jsonArray.length())
                    .mapToObj(jsonArray::getJSONObject)
                    .map(obj -> new Note(obj.getString("id"), obj.getString("title"), obj.getString("text")))
                    .collect(Collectors.toCollection(ArrayList::new));

            if (notes.stream().noneMatch(n -> n.id.equals(CONFIG_NOTE_ID))) {
                notes.add(createDefaultConfigNote());
            }
            System.out.println("Loaded " + notes.size() + " notes from " + NOTES_FILE);
            return notes;
        } catch (IOException | org.json.JSONException e) {
            System.err.println("Error loading notes from " + NOTES_FILE + ": " + e.getMessage() + ". Returning default config note.");
            return new ArrayList<>(List.of(createDefaultConfigNote()));
        }
    }

    private static synchronized void saveNotesToFile(List<Note> notes) {
        var filePath = Paths.get(NOTES_FILE);
        var jsonArray = new JSONArray();
        List<Note> notesToSave = new ArrayList<>(notes);
        if (notesToSave.stream().noneMatch(n -> n.id.equals(CONFIG_NOTE_ID))) {
            notesToSave.add(createDefaultConfigNote()); // Ensure config note is always saved
        }

        notesToSave.forEach(note -> jsonArray.put(new JSONObject()
                .put("id", note.id)
                .put("title", note.title)
                .put("text", note.text)));
        try {
            Files.writeString(filePath, jsonArray.toString(2));
            // System.out.println("Saved " + notesToSave.size() + " notes to " + NOTES_FILE); // Reduce log noise
        } catch (IOException e) {
            System.err.println("Error saving notes to " + NOTES_FILE + ": " + e.getMessage());
        }
    }

    private void setupDefaultPlugins() {
        plugins.loadPlugin(new InputProcessingPlugin());
        plugins.loadPlugin(new RetractionPlugin());
        plugins.loadPlugin(new IO.StatusUpdaterPlugin(statusEvent -> updateStatusLabel(statusEvent.statusMessage())));
        plugins.loadPlugin(new IO.WebSocketBroadcasterPlugin(this));
        plugins.loadPlugin(new IO.UiUpdatePlugin(ui));
        plugins.loadPlugin(new TaskDecompositionPlugin()); // Add the new plugin

        reasonerManager.loadPlugin(new Reason.ForwardChainingReasonerPlugin());
        reasonerManager.loadPlugin(new Reason.RewriteRuleReasonerPlugin());
        reasonerManager.loadPlugin(new Reason.UniversalInstantiationReasonerPlugin());
        reasonerManager.loadPlugin(new Reason.BackwardChainingReasonerPlugin());

        var or = context.operators();
        BiFunction<KifList, DoubleBinaryOperator, Optional<KifTerm>> numeric = (args, op) -> {
            if (args.size() == 3 && args.get(1) instanceof KifAtom(String value1) && args.get(2) instanceof KifAtom(
                    String value2
            )) {
                try {
                    return Optional.of(KifAtom.of(String.valueOf(op.applyAsDouble(Double.parseDouble(value1), Double.parseDouble(value2)))));
                } catch (NumberFormatException e) {
                }
            }
            return Optional.empty();
        };
        BiFunction<KifList, DoubleDoublePredicate, Optional<KifTerm>> comparison = (args, op) -> {
            if (args.size() == 3 && args.get(1) instanceof KifAtom(String value1) && args.get(2) instanceof KifAtom(
                    String value2
            )) {
                try {
                    return Optional.of(KifAtom.of(op.test(Double.parseDouble(value1), Double.parseDouble(value2)) ? "true" : "false"));
                } catch (NumberFormatException e) {
                }
            }
            return Optional.empty();
        };
        or.add(new Op.BasicOperator(KifAtom.of("+"), args -> numeric.apply(args, Double::sum)));
        or.add(new Op.BasicOperator(KifAtom.of("-"), args -> numeric.apply(args, (a, b) -> a - b)));
        or.add(new Op.BasicOperator(KifAtom.of("*"), args -> numeric.apply(args, (a, b) -> a * b)));
        or.add(new Op.BasicOperator(KifAtom.of("/"), args -> numeric.apply(args, (a, b) -> b == 0 ? Double.NaN : a / b)));
        or.add(new Op.BasicOperator(KifAtom.of("<"), args -> comparison.apply(args, (a, b) -> a < b)));
        or.add(new Op.BasicOperator(KifAtom.of(">"), args -> comparison.apply(args, (a, b) -> a > b)));
        or.add(new Op.BasicOperator(KifAtom.of("<="), args -> comparison.apply(args, (a, b) -> a <= b)));
        or.add(new Op.BasicOperator(KifAtom.of(">="), args -> comparison.apply(args, (a, b) -> a >= b)));
    }

    public void startSystem() {
        if (!running.get()) {
            System.err.println("Cannot restart a stopped system.");
            return;
        }
        paused.set(false);
        systemStatus = "Starting";
        updateStatusLabel();

        SwingUtilities.invokeLater(() -> {
            ui.addNoteToList(new Note(GLOBAL_KB_NOTE_ID, GLOBAL_KB_NOTE_TITLE, "Assertions in the global knowledge base."));
            ui.loadNotes(loadNotesFromFile()); // This will reload notes, including config, and trigger updateConfig again
        });

        setupDefaultPlugins();
        plugins.initializeAll();
        reasonerManager.initializeAll();

        try {
            websocket.start();
            System.out.println("WebSocket server started on port " + websocket.getPort());
        } catch (Exception e) {
            System.err.println("WebSocket server failed to start: " + e.getMessage());
            stopSystem();
            return;
        }

        systemStatus = "Running";
        updateStatusLabel();
        System.out.println("System started.");
    }

    public void stopSystem() {
        if (!running.compareAndSet(true, false)) return;
        System.out.println("Stopping system...");
        systemStatus = "Stopping";
        updateStatusLabel();
        paused.set(false);
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }

        lm.activeLlmTasks.values().forEach(f -> f.cancel(true));
        lm.activeLlmTasks.clear();
        saveNotesToFile();

        plugins.shutdownAll();
        reasonerManager.shutdownAll();

        try {
            websocket.stop(WS_STOP_TIMEOUT_MS);
            System.out.println("WebSocket server stopped.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted while stopping WebSocket server.");
        } catch (Exception e) {
            System.err.println("Error stopping WebSocket server: " + e.getMessage());
        }

        shutdownExecutor(mainExecutor, "Main Executor");
        events.shutdown();
        systemStatus = "Stopped";
        updateStatusLabel();
        System.out.println("System stopped.");
    }

    public boolean isPaused() {
        return paused.get();
    }

    public void setPaused(boolean pause) {
        if (paused.get() == pause || !running.get()) return;
        paused.set(pause);
        systemStatus = pause ? "Paused" : "Running";
        updateStatusLabel();
        if (!pause) {
            synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
        }
        events.emit(new SystemStatusEvent(systemStatus, context.kbCount(), context.kbTotalCapacity(), lm.activeLlmTasks.size(), context.ruleCount()));
    }

    public void clear() {
        System.out.println("Clearing all knowledge...");
        setPaused(true);
        context.getAllNoteIds().stream()
                .filter(noteId -> !noteId.equals(GLOBAL_KB_NOTE_ID) && !noteId.equals(CONFIG_NOTE_ID))
                .forEach(noteId -> events.emit(new RetractionRequestEvent(noteId, RetractionType.BY_NOTE, "UI-ClearAll", noteId)));
        context.kbGlobal().getAllAssertionIds().forEach(id -> context.truth().retractAssertion(id, "UI-ClearAll"));
        context.clearAll();

        SwingUtilities.invokeLater(() -> {
            ui.clearAllUILists();
            ui.addNoteToList(new Note(GLOBAL_KB_NOTE_ID, GLOBAL_KB_NOTE_TITLE, "Global KB Assertions"));
            if (ui.findNoteById(CONFIG_NOTE_ID).isEmpty()) {
                ui.addNoteToList(createDefaultConfigNote());
            }
            ui.noteListPanel.noteList.setSelectedIndex(0);
        });

        systemStatus = "Cleared";
        updateStatusLabel();
        setPaused(false);
        System.out.println("Knowledge cleared.");
        events.emit(new SystemStatusEvent(systemStatus, 0, globalKbCapacity, 0, 0));
    }

    public void loadExpressionsFromFile(String filename) throws IOException {
        System.out.println("Loading expressions from: " + filename);
        Path path = Paths.get(filename);
        if (!Files.exists(path) || !Files.isReadable(path))
            throw new IOException("File not found or not readable: " + filename);

        var kifBuffer = new StringBuilder();
        long[] counts = {0, 0, 0}; // lines, blocks, events
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            int parenDepth = 0;
            while ((line = reader.readLine()) != null) {
                counts[0]++;
                int commentStart = line.indexOf(';');
                if (commentStart != -1) line = line.substring(0, commentStart);
                line = line.trim();
                if (line.isEmpty()) continue;

                parenDepth += line.chars().filter(c -> c == '(').count() - line.chars().filter(c -> c == ')').count();
                kifBuffer.append(line).append(' ');

                if (parenDepth == 0 && !kifBuffer.isEmpty()) {
                    String kifText = kifBuffer.toString().trim();
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
                    parenDepth = 0; // Reset parsing state
                    kifBuffer.setLength(0);
                }
            }
            if (parenDepth != 0) System.err.println("Warning: Unbalanced parentheses at end of file: " + filename);
        }
        System.out.printf("Processed %d KIF blocks from %s, published %d input events.%n", counts[1], filename, counts[2]);
    }

    void updateStatusLabel() {
        if (ui != null && ui.isDisplayable()) {
            var kbCount = context.kbCount();
            var kbCapacityTotal = context.kbTotalCapacity();
            var notesCount = ui.noteListPanel.noteListModel.size();
            var tasksCount = lm.activeLlmTasks.size();
            var statusText = String.format("KB: %d/%d | Rules: %d | Notes: %d | Tasks: %d | Status: %s",
                    kbCount, kbCapacityTotal, context.ruleCount(), notesCount, tasksCount, systemStatus);
            updateStatusLabel(statusText);
        }
    }

    private void updateStatusLabel(String statusText) {
        SwingUtilities.invokeLater(() -> ui.mainControlPanel.statusLabel.setText(statusText));
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

    private void loadNotesAndConfig() {
        var notes = loadNotesFromFile();
        var configNoteOpt = notes.stream().filter(n -> n.id.equals(CONFIG_NOTE_ID)).findFirst();

        if (configNoteOpt.isPresent()) {
            parseConfig(configNoteOpt.get().text);
        } else {
            System.out.println("Configuration note not found, using defaults and creating one.");
            var configNote = createDefaultConfigNote();
            notes.add(configNote);
            parseConfig(configNote.text); // Parse defaults first
            saveNotesToFile(notes); // Save the newly created config note
        }
    }

    private void parseConfig(String jsonText) {
        try {
            var configJson = new JSONObject(new JSONTokener(jsonText));
            this.lm.llmApiUrl = configJson.optString("llmApiUrl", LM.DEFAULT_LLM_URL);
            this.lm.llmModel = configJson.optString("llmModel", LM.DEFAULT_LLM_MODEL);
            this.globalKbCapacity = configJson.optInt("globalKbCapacity", DEFAULT_KB_CAPACITY);
            this.reasoningDepthLimit = configJson.optInt("reasoningDepthLimit", DEFAULT_REASONING_DEPTH);
            this.broadcastInputAssertions = configJson.optBoolean("broadcastInputAssertions", DEFAULT_BROADCAST_INPUT);
        } catch (Exception e) {
            System.err.println("Error parsing configuration JSON, using defaults: " + e.getMessage());
            this.lm.llmApiUrl = LM.DEFAULT_LLM_URL;
            this.lm.llmModel = LM.DEFAULT_LLM_MODEL;
            this.globalKbCapacity = DEFAULT_KB_CAPACITY;
            this.reasoningDepthLimit = DEFAULT_REASONING_DEPTH;
            this.broadcastInputAssertions = DEFAULT_BROADCAST_INPUT;
        }
    }

    public boolean updateConfig(String newConfigJsonText) {
        try {
            var newConfigJson = new JSONObject(new JSONTokener(newConfigJsonText)); // Validate first
            parseConfig(newConfigJsonText); // Apply changes to volatile fields
            lm.reconfigure(); // Reconfigure the LLM instance
            ui.findNoteById(CONFIG_NOTE_ID).ifPresent(note -> {
                note.text = newConfigJson.toString(2); // Update note text
                saveNotesToFile(); // Persist changes
            });
            System.out.println("Configuration updated and saved.");
            System.out.printf("New Config: KBSize=%d, LLM_URL=%s, LLM_Model=%s, MaxDepth=%d, BroadcastInput=%b%n",
                    globalKbCapacity, lm.llmApiUrl, lm.llmModel, reasoningDepthLimit, broadcastInputAssertions);
            return true;
        } catch (org.json.JSONException e) {
            System.err.println("Failed to parse new configuration JSON: " + e.getMessage());
            return false;
        }
    }

    public void saveNotesToFile() {
        saveNotesToFile(ui.getAllNotes());
    }

    void updateLlmItemStatus(String taskId, UI.LlmStatus status, String content) {
        events.emit(new LlmUpdateEvent(taskId, status, content));
    }

    /**
     * Executes a query synchronously by emitting a QueryRequestEvent and waiting for the corresponding QueryResultEvent.
     * This method is intended for use by components that need a direct query result, such as LLM tools.
     * It blocks the calling thread until the result is received or a timeout occurs.
     *
     * @param query The query to execute.
     * @return The Answer containing the query result.
     * @throws RuntimeException if interrupted or if waiting for the result fails.
     */
    public Answer executeQuerySync(Query query) {
        // Use a CompletableFuture to signal when the result is received
        CompletableFuture<Answer> resultFuture = new CompletableFuture<>();

        // Register a temporary listener for QueryResultEvent with this specific query ID
        Consumer<CogEvent> listener = event -> {
            if (event instanceof QueryResultEvent(Answer result) && result.query().equals(query.id())) {
                resultFuture.complete(result);
            }
        };

        // Add the listener. Need a way to remove it later.
        // The Events class doesn't currently support removing specific listeners easily.
        // A simple approach for this temporary listener is to rely on the CompletableFuture
        // completing and the listener being short-lived. A more robust system would
        // involve listener registration handles. For this prototype, we'll add directly
        // and rely on the future completion.
        // NOTE: This relies on the event executor processing the QueryResultEvent
        // and completing the future *before* the calling thread times out or is interrupted.
        // Since the event executor is separate, this should generally work, but could
        // be a point of failure under heavy load or complex event processing.
        // events.on(QueryResultEvent.class, listener); // This doesn't work directly as 'on' is not public and doesn't return handle

        // Workaround: Manually add to the listeners map. This is fragile and bypasses
        // the intended event system encapsulation. A proper fix requires modifying Events.
        // For this prototype, we'll use this direct access.
        @SuppressWarnings("unchecked")
        CopyOnWriteArrayList<Consumer<CogEvent>> queryResultListeners = events.listeners.computeIfAbsent(QueryResultEvent.class, k -> new CopyOnWriteArrayList<>());
        queryResultListeners.add(listener);


        try {
            // Emit the query request event
            events.emit(new QueryRequestEvent(query));

            // Wait for the result future to complete
            // Use a reasonable timeout to prevent indefinite blocking
            return resultFuture.get(60, TimeUnit.SECONDS); // Wait up to 60 seconds

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Query execution interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Error during query execution", e.getCause());
        } catch (TimeoutException e) {
            throw new RuntimeException("Query execution timed out after 60 seconds", e);
        } finally {
            // Attempt to remove the listener. This is tricky without a removal handle.
            // In a real system, Events.on would return a handle to allow removal.
            // For this prototype, we'll remove directly from the list.
            queryResultListeners.remove(listener);
        }
    }


    enum QueryType {ASK_BINDINGS, ASK_TRUE_FALSE, ACHIEVE_GOAL}

    enum Feature {FORWARD_CHAINING, BACKWARD_CHAINING, TRUTH_MAINTENANCE, CONTRADICTION_DETECTION, UNCERTAINTY_HANDLING, OPERATOR_SUPPORT, REWRITE_RULES, UNIVERSAL_INSTANTIATION}

    enum QueryStatus {SUCCESS, FAILURE, TIMEOUT, ERROR}

    @FunctionalInterface
    interface DoubleDoublePredicate {
        boolean test(double a, double b);
    }

    interface CogEvent {
        default String assocNote() {
            return null;
        }
    }


    interface Plugin {
        String id();

        default void start(Cognition c) {
            start(c.events, c);
        }

        void start(Events e, Cognition c);

        default void stop() {
        }
    }

    record AssertionEvent(Assertion assertion, String noteId) implements CogEvent {
        @Override
        public String assocNote() {
            return noteId;
        }
    }

    record AssertionAddedEvent(Assertion assertion, String kbId) implements CogEvent {
        @Override
        public String assocNote() {
            return assertion.sourceNoteId();
        }
    }

    record AssertionRetractedEvent(Assertion assertion, String kbId, String reason) implements CogEvent {
        @Override
        public String assocNote() {
            return assertion.sourceNoteId();
        }
    }

    record AssertionEvictedEvent(Assertion assertion, String kbId) implements CogEvent {
        @Override
        public String assocNote() {
            return assertion.sourceNoteId();
        }
    }

    record AssertionStatusChangedEvent(String assertionId, boolean isActive, String kbId) implements CogEvent {
    }

    record TemporaryAssertionEvent(KifList temporaryAssertion, Map<KifVar, KifTerm> bindings,
                                   String sourceNoteId) implements CogEvent {
        @Override
        public String assocNote() {
            return sourceNoteId;
        }
    }

    record RuleEvent(Rule rule) implements CogEvent {
    }

    record RuleAddedEvent(Rule rule) implements CogEvent {
    }

    record RuleRemovedEvent(Rule rule) implements CogEvent {
    }

    record LlmInfoEvent(UI.AttachmentViewModel llmItem) implements CogEvent {
        @Override
        public String assocNote() {
            return llmItem.noteId();
        }
    }

    record LlmUpdateEvent(String taskId, UI.LlmStatus status, String content) implements CogEvent {
    }

    record SystemStatusEvent(String statusMessage, int kbCount, int kbCapacity, int taskQueueSize,
                             int ruleCount) implements CogEvent {
    }

    record AddedEvent(Note note) implements CogEvent {
        @Override
        public String assocNote() {
            return note.id;
        }
    }

    record RemovedEvent(Note note) implements CogEvent {
        @Override
        public String assocNote() {
            return note.id;
        }
    }

    record ExternalInputEvent(KifTerm term, String sourceId, @Nullable String targetNoteId) implements CogEvent {
        @Override
        public String assocNote() {
            return targetNoteId;
        }
    }

    record RetractionRequestEvent(String target, RetractionType type, String sourceId,
                                  @Nullable String targetNoteId) implements CogEvent {
        @Override
        public String assocNote() {
            return targetNoteId;
        }
    }

    record WebSocketBroadcastEvent(String message) implements CogEvent {
    }

    record ContradictionDetectedEvent(Set<String> contradictoryAssertionIds, String kbId) implements CogEvent {
    }

    record QueryRequestEvent(Query query) implements CogEvent {
    }

    record QueryResultEvent(Answer result) implements CogEvent {
    }

    static class Events {
        final ExecutorService exe;
        private final ConcurrentMap<Class<? extends CogEvent>, CopyOnWriteArrayList<Consumer<CogEvent>>> listeners = new ConcurrentHashMap<>();
        private final ConcurrentMap<KifTerm, CopyOnWriteArrayList<BiConsumer<CogEvent, Map<KifVar, KifTerm>>>> patternListeners = new ConcurrentHashMap<>();

        Events(ExecutorService exe) {
            this.exe = requireNonNull(exe);
        }

        private static void exeSafe(Consumer<CogEvent> listener, CogEvent event, String type) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                logExeError(e, type, event.getClass().getSimpleName());
            }
        }

        private static void exeSafe(BiConsumer<CogEvent, Map<KifVar, KifTerm>> listener, CogEvent event, Map<KifVar, KifTerm> bindings, String type) {
            try {
                listener.accept(event, bindings);
            } catch (Exception e) {
                logExeError(e, type, event.getClass().getSimpleName() + " (Pattern Match)");
            }
        }

        private static void logExeError(Exception e, String type, String eventName) {
            System.err.printf("Error in %s for %s: %s%n", type, eventName, e.getMessage());
            e.printStackTrace();
        }

        public <T extends CogEvent> void on(Class<T> eventType, Consumer<T> listener) {
            listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(event -> listener.accept(eventType.cast(event)));
        }

        public void on(KifTerm pattern, BiConsumer<CogEvent, Map<KifVar, KifTerm>> listener) {
            patternListeners.computeIfAbsent(pattern, k -> new CopyOnWriteArrayList<>()).add(listener);
        }

        public void emit(CogEvent event) {
            if (exe.isShutdown()) {
                System.err.println("Warning: Events executor shutdown. Cannot publish event: " + event.getClass().getSimpleName());
                return;
            }
            exe.submit(() -> {
                listeners.getOrDefault(event.getClass(), new CopyOnWriteArrayList<>()).forEach(listener -> exeSafe(listener, event, "Direct Listener"));
                switch (event) {
                    case AssertionAddedEvent aaEvent -> handlePatternMatching(aaEvent.assertion().kif(), event);
                    case TemporaryAssertionEvent taEvent -> handlePatternMatching(taEvent.temporaryAssertion(), event);
                    case ExternalInputEvent eiEvent ->
                            handlePatternMatching(eiEvent.term(), event); // Also match patterns on external input
                    default -> {
                    }
                }
            });
        }

        private void handlePatternMatching(KifTerm eventTerm, CogEvent event) {
            patternListeners.forEach((pattern, listeners) ->
                    ofNullable(Unifier.match(pattern, eventTerm, Map.of()))
                            .ifPresent(bindings -> listeners.forEach(listener -> exeSafe(listener, event, bindings, "Pattern Listener")))
            );
        }

        public void shutdown() {
            listeners.clear();
            patternListeners.clear();
        }
    }

    static class Plugins {
        private final Events events;
        private final Cognition context;
        private final List<Plugin> plugins = new CopyOnWriteArrayList<>();
        private final AtomicBoolean initialized = new AtomicBoolean(false);

        Plugins(Events events, Cognition context) {
            this.events = events;
            this.context = context;
        }

        public void loadPlugin(Plugin plugin) {
            if (initialized.get()) {
                System.err.println("Cannot load plugin " + plugin.id() + " after initialization.");
                return;
            }
            plugins.add(plugin);
            System.out.println("Plugin loaded: " + plugin.id());
        }

        public void initializeAll() {
            if (!initialized.compareAndSet(false, true)) return;
            System.out.println("Initializing " + plugins.size() + " general plugins...");
            plugins.forEach(plugin -> {
                try {
                    plugin.start(events, context);
                    System.out.println("Initialized plugin: " + plugin.id());
                } catch (Exception e) {
                    System.err.println("Failed to initialize plugin " + plugin.id() + ": " + e.getMessage());
                    e.printStackTrace();
                    plugins.remove(plugin); // Remove failed plugin
                }
            });
            System.out.println("General plugin initialization complete.");
        }

        public void shutdownAll() {
            System.out.println("Shutting down " + plugins.size() + " general plugins...");
            plugins.forEach(plugin -> {
                try {
                    plugin.stop();
                    System.out.println("Shutdown plugin: " + plugin.id());
                } catch (Exception e) {
                    System.err.println("Error shutting down plugin " + plugin.id() + ": " + e.getMessage());
                    e.printStackTrace();
                }
            });
            plugins.clear();
            System.out.println("General plugin shutdown complete.");
        }
    }

    record Query(String id, QueryType type, KifTerm pattern, @Nullable String targetKbId,
                 Map<String, Object> parameters) {
    }

    record Answer(String query, QueryStatus status, List<Map<KifVar, KifTerm>> bindings,
                  @Nullable Explanation explanation) {
        static Answer success(String queryId, List<Map<KifVar, KifTerm>> bindings) {
            return new Answer(queryId, QueryStatus.SUCCESS, bindings, null);
        }

        static Answer failure(String queryId) {
            return new Answer(queryId, QueryStatus.FAILURE, List.of(), null);
        }

        static Answer error(String queryId, String message) {
            return new Answer(queryId, QueryStatus.ERROR, List.of(), new Explanation(message));
        }
    }
    // --- End Reasoner Related ---

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

    static class Note {
        final String id;
        String title;
        String text;

        Note(String id, String title, String text) {
            this.id = requireNonNull(id);
            this.title = requireNonNull(title);
            this.text = requireNonNull(text);
        }

        @Override
        public String toString() {
            return title;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Note n && id.equals(n.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }

    // --- Base Plugin Classes ---
    abstract static class BasePlugin implements Plugin {
        protected final String id = generateId(ID_PREFIX_PLUGIN + getClass().getSimpleName().replace("Plugin", "").toLowerCase() + "_");
        protected Events events;
        protected Cognition context;

        @Override
        public String id() {
            return id;
        }

        @Override
        public void start(Events e, Cognition ctx) {
            this.events = e;
            this.context = ctx;
        }

        protected void publish(CogEvent event) {
            if (events != null) events.emit(event);
        }

        protected Knowledge getKb(@Nullable String noteId) {
            return context.kb(noteId);
        }

        protected Cog cog() {
            return context.cog;
        }
    }

    // --- End Base Plugin Classes ---

    // --- Concrete Plugins ---
    static class InputProcessingPlugin extends BasePlugin {
        @Override
        public void start(Events e, Cognition ctx) {
            super.start(e, ctx);
            e.on(ExternalInputEvent.class, this::handleExternalInput);
        }

        private void handleExternalInput(ExternalInputEvent event) {
            switch (event.term()) {
                case KifList list when !list.terms().isEmpty() -> list.op().ifPresentOrElse(
                        op -> {
                            switch (op) {
                                case KIF_OP_IMPLIES, KIF_OP_EQUIV -> handleRuleInput(list, event.sourceId());
                                case KIF_OP_EXISTS -> handleExistsInput(list, event.sourceId(), event.targetNoteId());
                                case KIF_OP_FORALL -> handleForallInput(list, event.sourceId(), event.targetNoteId());
                                case "goal" -> { /* Handled by TaskDecompositionPlugin */ }
                                default -> handleStandardAssertionInput(list, event.sourceId(), event.targetNoteId());
                            }
                        }, () -> handleStandardAssertionInput(list, event.sourceId(), event.targetNoteId())
                );
                case KifTerm term when !(term instanceof KifList) ->
                        System.err.println("Warning: Ignoring non-list top-level term from " + event.sourceId() + ": " + term.toKif());
                default -> {
                }
            }
        }

        private void handleRuleInput(KifList list, String sourceId) {
            try {
                var rule = Rule.parseRule(generateId(ID_PREFIX_RULE), list, DEFAULT_RULE_PRIORITY);
                context.addRule(rule);
                if (KIF_OP_EQUIV.equals(list.op().orElse(null))) { // Handle equivalence by adding reverse implication
                    var revList = new KifList(new KifAtom(KIF_OP_IMPLIES), list.get(2), list.get(1));
                    var revRule = Rule.parseRule(generateId(ID_PREFIX_RULE), revList, DEFAULT_RULE_PRIORITY);
                    context.addRule(revRule);
                }
            } catch (IllegalArgumentException e) {
                System.err.println("Invalid rule format ignored (" + sourceId + "): " + list.toKif() + " | Error: " + e.getMessage());
            }
        }

        private void handleStandardAssertionInput(KifList list, String sourceId, @Nullable String targetNoteId) {
            if (list.containsVar()) {
                System.err.println("Warning: Non-ground assertion input ignored (" + sourceId + "): " + list.toKif());
                return;
            }
            var isNeg = list.op().filter(KIF_OP_NOT::equals).isPresent();
            if (isNeg && list.size() != 2) {
                System.err.println("Invalid 'not' format ignored (" + sourceId + "): " + list.toKif());
                return;
            }
            var isEq = !isNeg && list.op().filter(KIF_OP_EQUAL::equals).isPresent();
            var isOriented = isEq && list.size() == 3 && list.get(1).weight() > list.get(2).weight();
            var type = list.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
            var pri = (sourceId.startsWith("llm-") ? LM.LLM_ASSERTION_BASE_PRIORITY : INPUT_ASSERTION_BASE_PRIORITY) / (1.0 + list.weight());
            context.tryCommit(new PotentialAssertion(list, pri, Set.of(), sourceId, isEq, isNeg, isOriented, targetNoteId, type, List.of(), 0), sourceId);
        }

        private void handleExistsInput(KifList existsExpr, String sourceId, @Nullable String targetNoteId) {
            if (existsExpr.size() != 3 || !(existsExpr.get(1) instanceof KifList || existsExpr.get(1) instanceof KifVar) || !(existsExpr.get(2) instanceof KifList body)) {
                System.err.println("Invalid 'exists' format ignored (" + sourceId + "): " + existsExpr.toKif());
                return;
            }
            var vars = KifTerm.collectSpecVars(existsExpr.get(1));
            if (vars.isEmpty()) {
                publish(new ExternalInputEvent(existsExpr.get(2), sourceId + "-existsBody", targetNoteId));
                return;
            }

            var skolemBody = Cognition.performSkolemization(body, vars, Map.of());
            var isNeg = skolemBody.op().filter(KIF_OP_NOT::equals).isPresent();
            var isEq = !isNeg && skolemBody.op().filter(KIF_OP_EQUAL::equals).isPresent();
            var isOriented = isEq && skolemBody.size() == 3 && skolemBody.get(1).weight() > skolemBody.get(2).weight();
            var pri = (sourceId.startsWith("llm-") ? LM.LLM_ASSERTION_BASE_PRIORITY : INPUT_ASSERTION_BASE_PRIORITY) / (1.0 + skolemBody.weight());
            context.tryCommit(new PotentialAssertion(skolemBody, pri, Set.of(), sourceId + "-skolemized", isEq, isNeg, isOriented, targetNoteId, AssertionType.SKOLEMIZED, List.of(), 0), sourceId + "-skolemized");
        }

        private void handleForallInput(KifList forallExpr, String sourceId, @Nullable String targetNoteId) {
            if (forallExpr.size() != 3 || !(forallExpr.get(1) instanceof KifList || forallExpr.get(1) instanceof KifVar) || !(forallExpr.get(2) instanceof KifList body)) {
                System.err.println("Invalid 'forall' format ignored (" + sourceId + "): " + forallExpr.toKif());
                return;
            }
            var vars = KifTerm.collectSpecVars(forallExpr.get(1));
            if (vars.isEmpty()) {
                publish(new ExternalInputEvent(forallExpr.get(2), sourceId + "-forallBody", targetNoteId));
                return;
            }

            if (body.op().filter(op -> op.equals(KIF_OP_IMPLIES) || op.equals(KIF_OP_EQUIV)).isPresent()) {
                handleRuleInput(body, sourceId); // Treat quantified implications/equivalences as rules
            } else {
                System.out.println("Storing 'forall' as universal fact from " + sourceId + ": " + forallExpr.toKif());
                var pri = (sourceId.startsWith("llm-") ? LM.LLM_ASSERTION_BASE_PRIORITY : INPUT_ASSERTION_BASE_PRIORITY) / (1.0 + forallExpr.weight());
                context.tryCommit(new PotentialAssertion(forallExpr, pri, Set.of(), sourceId, false, false, false, targetNoteId, AssertionType.UNIVERSAL, List.copyOf(vars), 0), sourceId);
            }
        }
    }

    static class RetractionPlugin extends BasePlugin {
        @Override
        public void start(Events e, Cognition ctx) {
            super.start(e, ctx);
            e.on(RetractionRequestEvent.class, this::handleRetractionRequest);
            e.on(AssertionRetractedEvent.class, this::handleExternalRetraction);
            e.on(AssertionStatusChangedEvent.class, this::handleExternalStatusChange);
        }

        private void handleRetractionRequest(RetractionRequestEvent event) {
            final var s = event.sourceId();
            switch (event.type) {
                case BY_ID -> {
                    context.truth().retractAssertion(event.target(), s);
                    System.out.printf("Retraction requested for [%s] by %s in KB '%s'.%n", event.target(), s, getKb(event.targetNoteId()).id);
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
                            new HashSet<>(ids).forEach(id -> context.truth().retractAssertion(id, s)); // Copy to avoid CME
                        } else
                            System.out.printf("Retraction by Note ID %s from %s: No associated assertions found in its KB.%n", noteId, s);
                        context.removeNoteKb(noteId, s);
                        publish(new RemovedEvent(new Note(noteId, "Removed", ""))); // Notify UI
                    } else
                        System.out.printf("Retraction by Note ID %s from %s failed: Note KB not found.%n", noteId, s);
                }
                case BY_RULE_FORM -> {
                    try {
                        var terms = KifParser.parseKif(event.target());
                        if (terms.size() == 1 && terms.getFirst() instanceof KifList rf) {
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

        private void handleExternalRetraction(AssertionRetractedEvent event) {
            ofNullable(getKb(event.kbId())).ifPresent(kb -> kb.handleExternalRetraction(event.assertion()));
        }

        private void handleExternalStatusChange(AssertionStatusChangedEvent event) {
            context.truth().getAssertion(event.assertionId())
                    .flatMap(a -> ofNullable(getKb(event.kbId())).map(kb -> Map.entry(kb, a)))
                    .ifPresent(e -> e.getKey().handleExternalStatusChange(e.getValue()));
        }
    }

    // --- End Concrete Plugins ---

    class MyWebSocketServer extends WebSocketServer {
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
                System.err.println("WS Network Info from " + addr + ": " + msg); // Less alarming log level
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

            switch (command) {
                case "retract" -> {
                    if (!argument.isEmpty())
                        events.emit(new RetractionRequestEvent(argument, RetractionType.BY_ID, sourceId, null));
                    else System.err.println("WS Retract Error from " + sourceId + ": Missing assertion ID.");
                }
                case "query" -> {
                    try {
                        var terms = KifParser.parseKif(argument);
                        if (terms.size() != 1 || !(terms.getFirst() instanceof KifList queryPattern)) {
                            conn.send("error Query must be a single KIF list.");
                            return;
                        }
                        var queryId = generateId(ID_PREFIX_QUERY);
                        var query = new Query(queryId, QueryType.ASK_BINDINGS, queryPattern, null, Map.of()); // Default to ASK_BINDINGS, global KB
                        events.emit(new QueryRequestEvent(query));
                    } catch (KifParser.ParseException e) {
                        conn.send("error Parse error: " + e.getMessage());
                    }
                }
                default -> { // Assume it's a KIF assertion/rule
                    try {
                        KifParser.parseKif(trimmed).forEach(term -> events.emit(new ExternalInputEvent(term, sourceId, null))); // Default to global KB
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
