package dumb.cognote;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import javax.swing.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static dumb.cognote.Logic.*;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static java.util.Optional.ofNullable;

public class Cog {

    public static final String ID_PREFIX_NOTE = "note-";
    public static final String ID_PREFIX_LLM_ITEM = "llm_";
    public static final String ID_PREFIX_QUERY = "query_";
    public static final String ID_PREFIX_LLM_RESULT = "llmres_";
    public static final String GLOBAL_KB_NOTE_ID = "kb://global";
    public static final String GLOBAL_KB_NOTE_TITLE = "Global Knowledge";
    public static final String CONFIG_NOTE_ID = "note-config";
    public static final String CONFIG_NOTE_TITLE = "System Configuration";
    static final Set<String> REFLEXIVE_PREDICATES = Set.of("instance", "subclass", "subrelation", "equivalent", "same", "equal", "domain", "range");
    static final AtomicLong idCounter = new AtomicLong(System.currentTimeMillis());
    static final String ID_PREFIX_OPERATOR = "op_";
    static final double DERIVED_PRIORITY_DECAY = 0.95;
    static final int KB_SIZE_THRESHOLD_WARN_PERCENT = 90;
    static final int KB_SIZE_THRESHOLD_HALT_PERCENT = 98;
    private static final String ID_PREFIX_RULE = "rule_";
    private static final String ID_PREFIX_INPUT_ITEM = "input_";
    private static final String ID_PREFIX_PLUGIN = "plugin_";
    private static final String NOTES_FILE = "cognote_notes.json";
    private static final String DEFAULT_LLM_URL = "http://localhost:11434/api/chat";
    private static final String DEFAULT_LLM_MODEL = "llama3";
    private static final int DEFAULT_KB_CAPACITY = 64 * 1024;
    private static final int DEFAULT_REASONING_DEPTH = 4;
    private static final int HTTP_TIMEOUT_SECONDS = 90;
    private static final double DEFAULT_RULE_PRIORITY = 1.0;
    private static final double INPUT_ASSERTION_BASE_PRIORITY = 10.0;
    private static final double LLM_ASSERTION_BASE_PRIORITY = 15.0;
    private static final int MAX_BACKWARD_CHAIN_DEPTH = 8;
    private static final int MAX_DERIVED_TERM_WEIGHT = 150;
    private static final int WS_STOP_TIMEOUT_MS = 1000;
    private static final int WS_CONNECTION_LOST_TIMEOUT_MS = 100;
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 2;
    private static final int MAX_KIF_PARSE_PREVIEW = 50;
    private static final int MAX_WS_PARSE_PREVIEW = 100;

    final Events events;
    final Plugins plugins;
    final ReasonerManager reasonerManager;
    final Cognition context;
    final HttpClient http;
    final UI swingUI;
    final MyWebSocketServer websocket;
    final ExecutorService mainExecutor = Executors.newVirtualThreadPerTaskExecutor();
    final Map<String, CompletableFuture<?>> activeLlmTasks = new ConcurrentHashMap<>();
    final AtomicBoolean running = new AtomicBoolean(true);
    final AtomicBoolean paused = new AtomicBoolean(false);
    private final Object pauseLock = new Object();
    boolean broadcastInputAssertions;
    String llmApiUrl;
    String llmModel;
    int globalKbCapacity;
    int reasoningDepthLimit;
    volatile String systemStatus = "Initializing";

    public Cog(int port, UI ui) {
        this.swingUI = requireNonNull(ui, "SwingUI cannot be null");
        this.events = new Events(mainExecutor);
        var skolemizer = new Skolemizer();
        var tms = new BasicTMS(events);
        var operatorRegistry = new Operators();

        loadNotesAndConfig();

        this.context = new Cognition(globalKbCapacity, events, tms, skolemizer, operatorRegistry, this);
        this.reasonerManager = new ReasonerManager(events, context);
        this.plugins = new Plugins(events, context);

        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .executor(mainExecutor)
                .build();
        this.websocket = new MyWebSocketServer(new InetSocketAddress(port));

        System.out.printf("System config: Port=%d, KBSize=%d, BroadcastInput=%b, LLM_URL=%s, LLM_Model=%s, MaxDepth=%d%n",
                port, globalKbCapacity, broadcastInputAssertions, llmApiUrl, llmModel, reasoningDepthLimit);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            var port = 8887;
            String rulesFile = null;

            for (var i = 0; i < args.length; i++) {
                try {
                    switch (args[i]) {
                        case "-p", "--port" -> port = Integer.parseInt(args[++i]);
                        case "-r", "--rules" -> rulesFile = args[++i];
                        default ->
                                System.err.println("Warning: Unknown or deprecated command-line option: " + args[i] + ". Configuration is now managed via UI/JSON.");
                    }
                } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
                    System.err.printf("Error parsing argument for %s: %s%n", (i > 0 ? args[i - 1] : args[i]), e.getMessage());
                    printUsageAndExit();
                }
            }

            UI ui = null;
            try {
                ui = new UI(null);
                var server = new Cog(port, ui);
                ui.setSystemReference(server);
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    System.out.println("Shutdown hook activated.");
                    server.stopSystem();
                }));
                server.startSystem();
                if (rulesFile != null) server.loadExpressionsFromFile(rulesFile);
                else System.out.println("No initial rules/facts file specified via command line.");
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

    private void setupDefaultPlugins() {
        plugins.loadPlugin(new InputProcessingPlugin());
        plugins.loadPlugin(new RetractionPlugin());
        plugins.loadPlugin(new StatusUpdaterPlugin(statusEvent -> updateStatusLabel(statusEvent.statusMessage())));
        plugins.loadPlugin(new WebSocketBroadcasterPlugin(this));
        plugins.loadPlugin(new UiUpdatePlugin(swingUI, this));

        reasonerManager.loadPlugin(new ForwardChainingReasonerPlugin());
        reasonerManager.loadPlugin(new RewriteRuleReasonerPlugin());
        reasonerManager.loadPlugin(new UniversalInstantiationReasonerPlugin());
        reasonerManager.loadPlugin(new BackwardChainingReasonerPlugin());

        var or = context.operators();
        BiFunction<KifList, DoubleBinaryOperator, Optional<KifTerm>> numeric = (args, op) -> {
            if (args.size() != 3 || !(args.get(1) instanceof KifAtom(var a1)) || !(args.get(2) instanceof KifAtom(
                    var a2
            ))) return Optional.empty();
            try {
                return Optional.of(KifAtom.of(String.valueOf(op.applyAsDouble(Double.parseDouble(a1), Double.parseDouble(a2)))));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        };
        BiFunction<KifList, DoubleDoublePredicate, Optional<KifTerm>> comparison = (args, op) -> {
            if (args.size() != 3 || !(args.get(1) instanceof KifAtom(var a1)) || !(args.get(2) instanceof KifAtom(
                    var a2
            ))) return Optional.empty();
            try {
                return Optional.of(KifAtom.of(op.test(Double.parseDouble(a1), Double.parseDouble(a2)) ? "true" : "false"));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        };
        or.add(new BasicOperator(KifAtom.of("+"), args -> numeric.apply(args, Double::sum)));
        or.add(new BasicOperator(KifAtom.of("-"), args -> numeric.apply(args, (a, b) -> a - b)));
        or.add(new BasicOperator(KifAtom.of("*"), args -> numeric.apply(args, (a, b) -> a * b)));
        or.add(new BasicOperator(KifAtom.of("/"), args -> numeric.apply(args, (a, b) -> b == 0 ? Double.NaN : a / b)));
        or.add(new BasicOperator(KifAtom.of("<"), args -> comparison.apply(args, (a, b) -> a < b)));
        or.add(new BasicOperator(KifAtom.of(">"), args -> comparison.apply(args, (a, b) -> a > b)));
        or.add(new BasicOperator(KifAtom.of("<="), args -> comparison.apply(args, (a, b) -> a <= b)));
        or.add(new BasicOperator(KifAtom.of(">="), args -> comparison.apply(args, (a, b) -> a >= b)));
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
            var globalNote = new Note(GLOBAL_KB_NOTE_ID, GLOBAL_KB_NOTE_TITLE, "Assertions in the global knowledge base.");
            swingUI.addNoteToList(globalNote);
            swingUI.loadNotes(loadNotesFromFile());
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
        System.out.println("Stopping system and services...");
        systemStatus = "Stopping";
        updateStatusLabel();
        paused.set(false);
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }

        activeLlmTasks.values().forEach(f -> f.cancel(true));
        activeLlmTasks.clear();
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
        events.emit(new SystemStatusEvent(systemStatus, context.kbCount(), context.kbTotalCapacity(), activeLlmTasks.size(), 0, context.ruleCount()));
    }

    public void clearAllKnowledge() {
        System.out.println("Clearing all knowledge...");
        setPaused(true);
        context.getAllNoteIds().forEach(noteId -> {
            if (!noteId.equals(GLOBAL_KB_NOTE_ID) && !noteId.equals(CONFIG_NOTE_ID))
                events.emit(new RetractionRequestEvent(noteId, RetractionType.BY_NOTE, "UI-ClearAll", noteId));
        });
        context.kbGlobal().getAllAssertionIds().forEach(assertionId -> context.truth().retractAssertion(assertionId, "UI-ClearAll"));
        context.clearAll();

        SwingUtilities.invokeLater(() -> {
            swingUI.clearAllUILists();
            var globalNote = new Note(GLOBAL_KB_NOTE_ID, GLOBAL_KB_NOTE_TITLE, "Assertions in the global knowledge base.");
            swingUI.addNoteToList(globalNote);
            if (swingUI.findNoteById(CONFIG_NOTE_ID).isEmpty()) {
                var configNote = createDefaultConfigNote();
                swingUI.addNoteToList(configNote);
            }
            swingUI.noteList.setSelectedIndex(0);
        });

        systemStatus = "Cleared";
        updateStatusLabel();
        setPaused(false);
        System.out.println("Knowledge cleared.");
        events.emit(new SystemStatusEvent(systemStatus, 0, globalKbCapacity, 0, 0, 0));
    }

    public void loadExpressionsFromFile(String filename) throws IOException {
        System.out.println("Loading expressions from: " + filename);
        var path = Paths.get(filename);
        if (!Files.exists(path) || !Files.isReadable(path))
            throw new IOException("File not found or not readable: " + filename);

        var kifBuffer = new StringBuilder();
        long[] counts = {0, 0, 0}; // lines, blocks, events
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
                        } catch (ParseException e) {
                            System.err.printf("File Parse Error (line ~%d): %s near '%s...'%n", counts[0], e.getMessage(), kifText.substring(0, Math.min(kifText.length(), MAX_KIF_PARSE_PREVIEW)));
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

    private CompletableFuture<String> llmAsync(String taskId, String prompt, String interactionType, String noteId) {
        return CompletableFuture.supplyAsync(() -> {
            waitIfPaused();
            updateLlmItemStatus(taskId, UI.LlmStatus.PROCESSING, interactionType + ": Waiting for LLM...");
            var payload = new JSONObject()
                    .put("model", llmModel)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt)))
                    .put("stream", false)
                    .put("options", new JSONObject().put("temperature", 0.2));
            var request = HttpRequest.newBuilder(URI.create(llmApiUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();
            try {
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                var responseBody = response.body();
                if (response.statusCode() < 200 || response.statusCode() >= 300)
                    throw new IOException("LLM API request failed (" + interactionType + "): " + response.statusCode() + " Body: " + responseBody);
                return extractLlmContent(new JSONObject(new JSONTokener(responseBody)))
                        .orElseThrow(() -> new IOException("LLM response missing expected content field. Body: " + responseBody));
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new CompletionException("LLM API communication error (" + interactionType + ")", e);
            } catch (Exception e) {
                throw new CompletionException("LLM response processing error (" + interactionType + ")", e);
            }
        }, mainExecutor);
    }

    private void handleLlmKifResponse(String taskId, String noteId, String kifResult, Throwable ex) {
        activeLlmTasks.remove(taskId);
        if (ex instanceof CancellationException) {
            updateLlmItemStatus(taskId, UI.LlmStatus.CANCELLED, "KIF Generation Cancelled.");
            return;
        }

        if (ex == null && kifResult != null) {
            var cleanedKif = kifResult.lines()
                    .map(s -> s.replaceAll("(?i)```kif", "").replaceAll("```", "").trim())
                    .filter(line -> line.startsWith("(") && line.endsWith(")") && !line.matches("^\\(\\s*\\)$"))
                    .collect(Collectors.joining("\n"));

            if (!cleanedKif.trim().isEmpty()) {
                System.out.printf("LLM Success (KIF %s): Extracted KIF assertions.%n", noteId);
                try {
                    KifParser.parseKif(cleanedKif).forEach(term -> events.emit(new ExternalInputEvent(term, "llm-kif:" + noteId, noteId)));
                    updateLlmItemStatus(taskId, UI.LlmStatus.DONE, "KIF Generation Complete. Assertions added to KB.");
                } catch (ParseException parseEx) {
                    System.err.printf("LLM Error (KIF %s): Failed to parse generated KIF: %s%n", noteId, parseEx.getMessage());
                    updateLlmItemStatus(taskId, UI.LlmStatus.ERROR, "KIF Parse Error: " + parseEx.getMessage());
                }
            } else {
                System.err.printf("LLM Warning (KIF %s): Result contained text but no valid KIF lines found after cleaning.%n", noteId);
                updateLlmItemStatus(taskId, UI.LlmStatus.DONE, "KIF Generation Warning: No valid KIF found in response.");
            }
        } else {
            var errorMsg = (ex != null) ? "KIF Generation Error: " + ex.getMessage() : "KIF Generation Failed: Empty or null response.";
            System.err.printf("LLM Error (KIF %s): %s%n", noteId, errorMsg);
            updateLlmItemStatus(taskId, UI.LlmStatus.ERROR, errorMsg);
        }
    }

    private void handleLlmGenericResponse(String taskId, String noteId, String interactionType, String response, Throwable ex, String kifPredicate) {
        activeLlmTasks.remove(taskId);
        if (ex instanceof CancellationException) {
            updateLlmItemStatus(taskId, UI.LlmStatus.CANCELLED, interactionType + " Cancelled.");
            return;
        }

        if (ex == null && response != null && !response.isBlank()) {
            response.lines()
                    .map(String::trim)
                    .filter(Predicate.not(String::isBlank))
                    .forEach(lineContent -> {
                        var resultId = generateId(ID_PREFIX_LLM_RESULT);
                        var kifTerm = new KifList(KifAtom.of(kifPredicate), KifAtom.of(noteId), KifAtom.of(resultId), KifAtom.of(lineContent));
                        events.emit(new ExternalInputEvent(kifTerm, "llm-" + kifPredicate + ":" + noteId, noteId));
                    });
            updateLlmItemStatus(taskId, UI.LlmStatus.DONE, interactionType + " Complete. Result added to KB.");
        } else {
            var errorMsg = (ex != null) ? interactionType + " Error: " + ex.getMessage() : interactionType + " Warning: Empty response.";
            System.err.printf("LLM %s (%s): %s%n", (ex != null ? "Error" : "Warning"), interactionType, errorMsg);
            updateLlmItemStatus(taskId, (ex != null ? UI.LlmStatus.ERROR : UI.LlmStatus.DONE), errorMsg);
        }
    }

    private void handleLlmEnhancementResponse(String taskId, String noteId, String response, Throwable ex) {
        activeLlmTasks.remove(taskId);
        if (ex instanceof CancellationException) {
            updateLlmItemStatus(taskId, UI.LlmStatus.CANCELLED, "Enhancement Cancelled.");
            return;
        }

        if (ex == null && response != null && !response.isBlank()) {
            swingUI.findNoteById(noteId).ifPresent(note -> {
                note.text = response.trim();
                SwingUtilities.invokeLater(() -> {
                    if (note.equals(swingUI.currentNote)) {
                        swingUI.noteEditor.setText(note.text);
                        swingUI.noteEditor.setCaretPosition(0);
                    }
                });
                saveNotesToFile();
                updateLlmItemStatus(taskId, UI.LlmStatus.DONE, "Note Enhanced and Updated.");
            });
        } else {
            var errorMsg = (ex != null) ? "Enhancement Error: " + ex.getMessage() : "Enhancement Warning: Empty response.";
            System.err.printf("LLM Enhancement (%s): %s%n", noteId, errorMsg);
            updateLlmItemStatus(taskId, (ex != null ? UI.LlmStatus.ERROR : UI.LlmStatus.DONE), errorMsg);
        }
    }

    public CompletableFuture<String> text2kifAsync(String taskId, String noteText, String noteId) {
        var finalPrompt = """
                Convert the following note into a set of concise SUMO KIF assertions (standard Lisp-like syntax, e.g., (instance MyCat Cat)).
                Output ONLY the KIF assertions, each on a new line, enclosed in parentheses.
                Use standard SUMO predicates like 'instance', 'subclass', 'domain', 'range', 'attribute', 'partOf', etc.
                Use '=' for equality between terms. Use unique names for new entities derived from the note (start with uppercase, use CamelCase).
                Use '(not ...)' for negation where appropriate, e.g., (not (instance Pluto Planet)).
                Use '(forall (?X) (=> (instance ?X Dog) (attribute ?X Canine)))' for universal statements.
                Use '(exists (?Y) (and (instance ?Y Cat) (attribute ?Y BlackColor)))' for existential statements.
                Avoid trivial assertions like (instance X X) or (= X X) or (not (= X X)).
                Example: (instance Fluffy Cat) (attribute Fluffy OrangeColor) (= (age Fluffy) 3) (not (attribute Fluffy BlackColor)) (exists (?K) (instance ?K Kitten))
                
                Note:
                "%s"
                
                KIF Assertions:""".formatted(noteText);
        var future = llmAsync(taskId, finalPrompt, "KIF Generation", noteId);
        activeLlmTasks.put(taskId, future);
        future.whenCompleteAsync((kifResult, ex) -> handleLlmKifResponse(taskId, noteId, kifResult, ex), mainExecutor);
        return future;
    }

    public CompletableFuture<String> enhanceNoteWithLlmAsync(String taskId, Note n) {
        var noteId = n.id;
        var finalPrompt = """
                You are a helpful assistant. Please revise and enhance the following note for clarity, conciseness, and improved structure. Keep the core meaning intact.
                Focus on improving readability and flow. Correct any grammatical errors or awkward phrasing.
                Output ONLY the revised note text, without any introductory or concluding remarks.
                
                Original Note:
                "%s"
                
                Enhanced Note:""".formatted(n.text);
        var future = llmAsync(taskId, finalPrompt, "Note Enhancement", noteId);
        activeLlmTasks.put(taskId, future);
        future.whenCompleteAsync((response, ex) -> handleLlmEnhancementResponse(taskId, noteId, response, ex), mainExecutor);
        return future;
    }

    public CompletableFuture<String> summarizeNoteWithLlmAsync(String taskId, Note n) {
        String noteText = n.text, noteId = n.id;
        var finalPrompt = """
                Summarize the following note in one or two concise sentences. Output ONLY the summary.
                
                Note:
                "%s"
                
                Summary:""".formatted(noteText);
        var future = llmAsync(taskId, finalPrompt, "Note Summarization", noteId);
        activeLlmTasks.put(taskId, future);
        future.whenCompleteAsync((response, ex) -> handleLlmGenericResponse(taskId, noteId, "Summary", response, ex, PRED_NOTE_SUMMARY), mainExecutor);
        return future;
    }

    public CompletableFuture<String> keyConceptsWithLlmAsync(String taskId, Note n) {
        String noteText = n.text, noteId = n.id;
        var finalPrompt = """
                Identify the key concepts or entities mentioned in the following note. List them separated by newlines. Output ONLY the newline-separated list.
                
                Note:
                "%s"
                
                Key Concepts:""".formatted(noteText);
        var future = llmAsync(taskId, finalPrompt, "Key Concept Identification", noteId);
        activeLlmTasks.put(taskId, future);
        future.whenCompleteAsync((response, ex) -> handleLlmGenericResponse(taskId, noteId, "Concepts", response, ex, PRED_NOTE_CONCEPT), mainExecutor);
        return future;
    }

    public CompletableFuture<String> generateQuestionsWithLlmAsync(String taskId, Note n) {
        String noteText = n.text, noteId = n.id;
        var finalPrompt = """
                Based on the following note, generate 1-3 insightful questions that could lead to further exploration or clarification. Output ONLY the questions, each on a new line starting with '- '.
                
                Note:
                "%s"
                
                Questions:""".formatted(noteText);
        var future = llmAsync(taskId, finalPrompt, "Question Generation", noteId);
        activeLlmTasks.put(taskId, future);
        future.whenCompleteAsync((response, ex) -> handleLlmGenericResponse(taskId, noteId, "Question Gen", response, ex, PRED_NOTE_QUESTION), mainExecutor);
        return future;
    }

    private Optional<String> extractLlmContent(JSONObject r) {
        return Stream.<Supplier<Optional<String>>>of(
                        () -> ofNullable(r.optJSONObject("message")).map(m -> m.optString("content", null)),
                        () -> ofNullable(r.optString("response", null)),
                        () -> ofNullable(r.optJSONArray("choices")).filter(Predicate.not(JSONArray::isEmpty)).map(a -> a.optJSONObject(0)).map(c -> c.optJSONObject("message")).map(m -> m.optString("content", null)),
                        () -> ofNullable(r.optJSONArray("results")).filter(Predicate.not(JSONArray::isEmpty)).map(a -> a.optJSONObject(0)).map(res -> res.optJSONObject("candidates")).map(cand -> cand.optJSONObject("content")).map(cont -> cont.optJSONArray("parts")).filter(Predicate.not(JSONArray::isEmpty)).map(p -> p.optJSONObject(0)).map(p -> p.optString("text", null)),
                        () -> findNestedContent(r)
                ).map(Supplier::get)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private Optional<String> findNestedContent(Object jsonValue) {
        return switch (jsonValue) {
            case JSONObject obj -> obj.keySet().stream()
                    .filter(key -> key.toLowerCase().contains("content") || key.toLowerCase().contains("text") || key.toLowerCase().contains("response"))
                    .map(obj::opt)
                    .flatMap(val -> (val instanceof String s && !s.isBlank()) ? Stream.of(s) : Stream.empty())
                    .findFirst()
                    .or(() -> obj.keySet().stream().map(obj::opt).map(this::findNestedContent).flatMap(Optional::stream).findFirst());
            case JSONArray arr -> IntStream.range(0, arr.length()).mapToObj(arr::opt)
                    .map(this::findNestedContent)
                    .flatMap(Optional::stream)
                    .findFirst();
            case String s -> Optional.of(s).filter(Predicate.not(String::isBlank));
            default -> Optional.empty();
        };
    }

    void updateStatusLabel() {
        if (swingUI != null && swingUI.isDisplayable()) {
            var kbCount = context.kbCount();
            var kbCapacityTotal = context.kbTotalCapacity();
            var notesCount = swingUI.noteListModel.size();
            var tasksCount = activeLlmTasks.size();
            var statusText = String.format("KB: %d/%d | Rules: %d | Notes: %d | Tasks: %d | Status: %s",
                    kbCount, kbCapacityTotal, context.ruleCount(), notesCount, tasksCount, systemStatus);
            updateStatusLabel(statusText);
        }
    }

    private void updateStatusLabel(String statusText) {
        SwingUtilities.invokeLater(() -> swingUI.statusLabel.setText(statusText));
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
            parseConfig(configNote.text);
            saveNotesToFile(notes);
        }
    }

    private void parseConfig(String jsonText) {
        try {
            var configJson = new JSONObject(new JSONTokener(jsonText));
            this.llmApiUrl = configJson.optString("llmApiUrl", DEFAULT_LLM_URL);
            this.llmModel = configJson.optString("llmModel", DEFAULT_LLM_MODEL);
            this.globalKbCapacity = configJson.optInt("globalKbCapacity", DEFAULT_KB_CAPACITY);
            this.reasoningDepthLimit = configJson.optInt("reasoningDepthLimit", DEFAULT_REASONING_DEPTH);
            this.broadcastInputAssertions = configJson.optBoolean("broadcastInputAssertions", false);
        } catch (Exception e) {
            System.err.println("Error parsing configuration JSON, using defaults: " + e.getMessage());
            this.llmApiUrl = DEFAULT_LLM_URL;
            this.llmModel = DEFAULT_LLM_MODEL;
            this.globalKbCapacity = DEFAULT_KB_CAPACITY;
            this.reasoningDepthLimit = DEFAULT_REASONING_DEPTH;
            this.broadcastInputAssertions = false;
        }
    }

    Note createDefaultConfigNote() {
        var configJson = new JSONObject()
                .put("llmApiUrl", DEFAULT_LLM_URL)
                .put("llmModel", DEFAULT_LLM_MODEL)
                .put("globalKbCapacity", DEFAULT_KB_CAPACITY)
                .put("reasoningDepthLimit", DEFAULT_REASONING_DEPTH)
                .put("broadcastInputAssertions", false);
        return new Note(CONFIG_NOTE_ID, CONFIG_NOTE_TITLE, configJson.toString(2));
    }

    public boolean updateConfig(String newConfigJsonText) {
        try {
            var newConfigJson = new JSONObject(new JSONTokener(newConfigJsonText));
            parseConfig(newConfigJsonText);
            swingUI.findNoteById(CONFIG_NOTE_ID).ifPresent(note -> {
                note.text = newConfigJson.toString(2);
                saveNotesToFile();
            });
            System.out.println("Configuration updated and saved.");
            System.out.printf("New Config: KBSize=%d, LLM_URL=%s, LLM_Model=%s, MaxDepth=%d%n",
                    globalKbCapacity, llmApiUrl, llmModel, reasoningDepthLimit);
            return true;
        } catch (org.json.JSONException e) {
            System.err.println("Failed to parse new configuration JSON: " + e.getMessage());
            return false;
        }
    }

    private List<Note> loadNotesFromFile() {
        var filePath = Paths.get(NOTES_FILE);
        if (!Files.exists(filePath)) return new ArrayList<>(List.of(createDefaultConfigNote()));
        try {
            var jsonText = Files.readString(filePath);
            var jsonArray = new JSONArray(new JSONTokener(jsonText));
            List<Note> notes = new ArrayList<>();
            for (var i = 0; i < jsonArray.length(); i++) {
                var obj = jsonArray.getJSONObject(i);
                notes.add(new Note(obj.getString("id"), obj.getString("title"), obj.getString("text")));
            }
            if (notes.stream().noneMatch(n -> n.id.equals(CONFIG_NOTE_ID))) {
                notes.add(createDefaultConfigNote());
            }
            System.out.println("Loaded " + notes.size() + " notes from " + NOTES_FILE);
            return notes;
        } catch (IOException | org.json.JSONException e) {
            System.err.println("Error loading notes from " + NOTES_FILE + ": " + e.getMessage());
            return new ArrayList<>(List.of(createDefaultConfigNote()));
        }
    }

    private void saveNotesToFile() {
        saveNotesToFile(swingUI.getAllNotes());
    }

    private void saveNotesToFile(List<Note> notes) {
        var filePath = Paths.get(NOTES_FILE);
        var jsonArray = new JSONArray();
        List<Note> notesToSave = new ArrayList<>(notes);
        if (notesToSave.stream().noneMatch(n -> n.id.equals(CONFIG_NOTE_ID))) {
            notesToSave.add(createDefaultConfigNote());
        }

        notesToSave.forEach(note -> jsonArray.put(new JSONObject()
                .put("id", note.id)
                .put("title", note.title)
                .put("text", note.text)));
        try {
            Files.writeString(filePath, jsonArray.toString(2));
            System.out.println("Saved " + notesToSave.size() + " notes to " + NOTES_FILE);
        } catch (IOException e) {
            System.err.println("Error saving notes to " + NOTES_FILE + ": " + e.getMessage());
        }
    }


    void updateLlmItemStatus(String taskId, UI.LlmStatus status, String content) {
        events.emit(new LlmUpdateEvent(taskId, status, content));
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

        void start(Events events, Cognition context);

        default void stop() { }
    }

    interface ReasonerPlugin extends Plugin {
        void initialize(ReasonerContext context);

        default void processAssertionEvent(AssertionEvent event) {
        }

        default void processRuleEvent(RuleEvent event) {
        }

        CompletableFuture<Answer> executeQuery(Query query);

        Set<QueryType> getSupportedQueryTypes();

        Set<Feature> getSupportedFeatures();

        @Override
        default void start(Events events, Cognition ctx) {
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

        public String getKbId() {
            return kbId;
        }
    }

    record AssertionRetractedEvent(Assertion assertion, String kbId, String reason) implements CogEvent {
        @Override
        public String assocNote() {
            return assertion.sourceNoteId();
        }

        public String getKbId() {
            return kbId;
        }
    }

    record AssertionEvictedEvent(Assertion assertion, String kbId) implements CogEvent {
        @Override
        public String assocNote() {
            return assertion.sourceNoteId();
        }

        public String getKbId() {
            return kbId;
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

    record SystemStatusEvent(String statusMessage, int kbCount, int kbCapacity, int taskQueueSize, int commitQueueSize,
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

    /**
     * event bus
     */
    static class Events {
        private final ConcurrentMap<Class<? extends CogEvent>, CopyOnWriteArrayList<Consumer<CogEvent>>> listeners = new ConcurrentHashMap<>();
        private final ConcurrentMap<KifTerm, CopyOnWriteArrayList<BiConsumer<CogEvent, Map<KifVar, KifTerm>>>> patternListeners = new ConcurrentHashMap<>();
        private final ExecutorService exe;

        Events(ExecutorService exe) {
            this.exe = requireNonNull(exe);
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

        private void exeSafe(Consumer<CogEvent> listener, CogEvent event, String type) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                logExeError(e, type, event.getClass().getSimpleName());
            }
        }

        private void exeSafe(BiConsumer<CogEvent, Map<KifVar, KifTerm>> listener, CogEvent event, Map<KifVar, KifTerm> bindings, String type) {
            try {
                listener.accept(event, bindings);
            } catch (Exception e) {
                logExeError(e, type, event.getClass().getSimpleName() + " (Pattern Match)");
            }
        }

        private void logExeError(Exception e, String type, String eventName) {
            System.err.printf("Error in %s for %s: %s%n", type, eventName, e.getMessage());
            e.printStackTrace();
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
                    plugins.remove(plugin);
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

    record ReasonerContext(Cognition cognition, Events events) {
        Knowledge getKb(@Nullable String noteId) {
            return cognition.kb(noteId);
        }

        Set<Rule> rules() {
            return cognition.rules();
        }

        Configuration getConfig() {
            return new Configuration(cognition.cog);
        }

        Skolemizer getSkolemizer() {
            return cognition.skolemizer();
        }

        Truths getTMS() {
            return cognition.truth();
        }

        Operators operators() {
            return cognition.operators();
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

    public record Configuration(Cog cog) {
        String llmApiUrl() {
            return cog.llmApiUrl;
        }

        String llmModel() {
            return cog.llmModel;
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

    static class ReasonerManager {
        private final Events events;
        private final ReasonerContext reasonerContext;
        private final List<ReasonerPlugin> plugins = new CopyOnWriteArrayList<>();
        private final AtomicBoolean initialized = new AtomicBoolean(false);

        ReasonerManager(Events events, Cognition ctx) {
            this.events = events;
            this.reasonerContext = new ReasonerContext(ctx, events);
        }

        public void loadPlugin(ReasonerPlugin plugin) {
            if (initialized.get()) {
                System.err.println("Cannot load reasoner plugin " + plugin.id() + " after initialization.");
                return;
            }
            plugins.add(plugin);
            System.out.println("Reasoner plugin loaded: " + plugin.id());
        }

        public void initializeAll() {
            if (!initialized.compareAndSet(false, true)) return;
            System.out.println("Initializing " + plugins.size() + " reasoner plugins...");
            plugins.forEach(plugin -> {
                try {
                    plugin.initialize(reasonerContext);
                    System.out.println("Initialized reasoner plugin: " + plugin.id());
                } catch (Exception e) {
                    System.err.println("Failed to initialize reasoner plugin " + plugin.id() + ": " + e.getMessage());
                    e.printStackTrace();
                    plugins.remove(plugin);
                }
            });

            events.on(AssertionAddedEvent.class, this::dispatchAssertionEvent);
            events.on(AssertionRetractedEvent.class, this::dispatchAssertionEvent);
            events.on(AssertionStatusChangedEvent.class, this::dispatchAssertionEvent);
            events.on(RuleAddedEvent.class, this::dispatchRuleEvent);
            events.on(RuleRemovedEvent.class, this::dispatchRuleEvent);
            events.on(QueryRequestEvent.class, this::handleQueryRequest);
            System.out.println("Reasoner plugin initialization complete.");
        }

        private void dispatchAssertionEvent(CogEvent event) {
            switch (event) {
                case AssertionAddedEvent aae ->
                        plugins.forEach(p -> p.processAssertionEvent(new AssertionEvent(aae.assertion(), aae.getKbId())));
                case AssertionRetractedEvent are ->
                        plugins.forEach(p -> p.processAssertionEvent(new AssertionEvent(are.assertion(), are.getKbId())));
                case AssertionStatusChangedEvent asce ->
                        getTMS().getAssertion(asce.assertionId()).ifPresent(a -> plugins.forEach(p -> p.processAssertionEvent(new AssertionEvent(a, asce.kbId()))));
                default -> {
                }
            }
        }

        private void dispatchRuleEvent(CogEvent event) {
            switch (event) {
                case RuleAddedEvent(var rule) -> plugins.forEach(p -> p.processRuleEvent(new RuleEvent(rule)));
                case RuleRemovedEvent(var rule) -> plugins.forEach(p -> p.processRuleEvent(new RuleEvent(rule)));
                default -> {
                }
            }
        }

        private void handleQueryRequest(QueryRequestEvent event) {
            var query = event.query();
            var futures = plugins.stream().filter(p -> p.getSupportedQueryTypes().contains(query.type)).map(p -> p.executeQuery(query)).toList();

            if (futures.isEmpty()) {
                events.emit(new QueryResultEvent(Answer.failure(query.id())));
                return;
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApplyAsync(v -> {
                        List<Map<KifVar, KifTerm>> allBindings = new ArrayList<>();
                        var overallStatus = QueryStatus.FAILURE;
                        Explanation combinedExplanation = null;
                        for (var future : futures) {
                            try {
                                var result = future.join();
                                if (result.status == QueryStatus.SUCCESS) {
                                    overallStatus = QueryStatus.SUCCESS;
                                    allBindings.addAll(result.bindings());
                                    if (result.explanation() != null) combinedExplanation = result.explanation();
                                } else if (result.status != QueryStatus.FAILURE && overallStatus == QueryStatus.FAILURE) {
                                    overallStatus = result.status;
                                    if (result.explanation() != null) combinedExplanation = result.explanation();
                                }
                            } catch (CompletionException | CancellationException e) {
                                System.err.println("Query execution error for " + query.id() + ": " + e.getMessage());
                                if (overallStatus != QueryStatus.ERROR) {
                                    overallStatus = QueryStatus.ERROR;
                                    combinedExplanation = new Explanation(e.getMessage());
                                }
                            }
                        }
                        return new Answer(query.id(), overallStatus, allBindings, combinedExplanation);
                    }, reasonerContext.events.exe)
                    .thenAccept(result -> events.emit(new QueryResultEvent(result)));
        }

        public void shutdownAll() {
            System.out.println("Shutting down " + plugins.size() + " reasoner plugins...");
            plugins.forEach(plugin -> {
                try {
                    plugin.stop();
                    System.out.println("Shutdown reasoner plugin: " + plugin.id());
                } catch (Exception e) {
                    System.err.println("Error shutting down reasoner plugin " + plugin.id() + ": " + e.getMessage());
                    e.printStackTrace();
                }
            });
            plugins.clear();
            System.out.println("Reasoner plugin shutdown complete.");
        }

        private Truths getTMS() {
            return reasonerContext.getTMS();
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
    }

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
                if (KIF_OP_EQUIV.equals(list.op().orElse(""))) {
                    var revList = new KifList(KifAtom.of(KIF_OP_IMPLIES), list.get(2), list.get(1));
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
            var pri = (sourceId.startsWith("llm-") ? LLM_ASSERTION_BASE_PRIORITY : INPUT_ASSERTION_BASE_PRIORITY) / (1.0 + list.weight());
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
            var skolemBody = context.performSkolemization(body, vars, Map.of());
            var isNeg = skolemBody.op().filter(KIF_OP_NOT::equals).isPresent();
            var isEq = !isNeg && skolemBody.op().filter(KIF_OP_EQUAL::equals).isPresent();
            var isOriented = isEq && skolemBody.size() == 3 && skolemBody.get(1).weight() > skolemBody.get(2).weight();
            var pri = (sourceId.startsWith("llm-") ? LLM_ASSERTION_BASE_PRIORITY : INPUT_ASSERTION_BASE_PRIORITY) / (1.0 + skolemBody.weight());
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
                handleRuleInput(body, sourceId);
            } else {
                System.out.println("Storing 'forall' as universal fact from " + sourceId + ": " + forallExpr.toKif());
                var pri = (sourceId.startsWith("llm-") ? LLM_ASSERTION_BASE_PRIORITY : INPUT_ASSERTION_BASE_PRIORITY) / (1.0 + forallExpr.weight());
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
                            new HashSet<>(ids).forEach(id -> context.truth().retractAssertion(id, s));
                        } else
                            System.out.printf("Retraction by Note ID %s from %s: No associated assertions found in its KB.%n", noteId, s);
                        context.removeNoteKb(noteId, s);
                        publish(new RemovedEvent(new Note(noteId, "Removed", "")));
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
                    } catch (ParseException e) {
                        System.err.println("Retract rule from " + s + ": Parse error: " + e.getMessage());
                    }
                }
            }
        }

        private void handleExternalRetraction(AssertionRetractedEvent event) {
            ofNullable(getKb(event.getKbId())).ifPresent(kb -> kb.handleExternalRetraction(event.assertion()));
        }

        private void handleExternalStatusChange(AssertionStatusChangedEvent event) {
            context.truth().getAssertion(event.assertionId()).flatMap(a -> ofNullable(getKb(event.kbId())).map(kb -> Map.entry(kb, a))).ifPresent(e -> e.getKey().handleExternalStatusChange(e.getValue()));
        }
    }

    abstract static class BaseReasonerPlugin implements ReasonerPlugin {
        protected final String id = generateId(ID_PREFIX_PLUGIN + getClass().getSimpleName().replace("ReasonerPlugin", "").toLowerCase() + "_");
        protected ReasonerContext context;

        @Override
        public String id() {
            return id;
        }

        @Override
        public void initialize(ReasonerContext ctx) {
            this.context = ctx;
        }

        protected void publish(CogEvent event) {
            if (context != null && context.events() != null) context.events().emit(event);
        }

        protected Knowledge getKb(@Nullable String noteId) {
            return context.getKb(noteId);
        }

        protected Truths getTMS() {
            return context.getTMS();
        }

        protected Cognition getCogNoteContext() {
            return context.cognition();
        }

        protected int getMaxDerivationDepth() {
            return context.getConfig().reasoningDepthLimit();
        }

        @Nullable
        protected Assertion tryCommit(PotentialAssertion pa, String source) {
            return getCogNoteContext().tryCommit(pa, source);
        }

        @Override
        public CompletableFuture<Answer> executeQuery(Query query) {
            return CompletableFuture.completedFuture(Answer.failure(query.id()));
        }

        @Override
        public Set<QueryType> getSupportedQueryTypes() {
            return Set.of();
        }
    }

    static class ForwardChainingReasonerPlugin extends BaseReasonerPlugin {
        @Override
        public void initialize(ReasonerContext ctx) {
            super.initialize(ctx);
            ctx.events().on(AssertionAddedEvent.class, this::handleAssertionAdded);
        }

        @Override
        public Set<Feature> getSupportedFeatures() {
            return Set.of(Feature.FORWARD_CHAINING);
        }

        private void handleAssertionAdded(AssertionAddedEvent event) {
            var newAssertion = event.assertion();
            var sourceKbId = event.getKbId();
            if (!newAssertion.isActive() || (newAssertion.type() != AssertionType.GROUND && newAssertion.type() != AssertionType.SKOLEMIZED))
                return;
            context.rules().forEach(rule -> rule.antecedents().forEach(clause -> {
                var neg = (clause instanceof KifList l && l.op().filter(KIF_OP_NOT::equals).isPresent());
                if (neg == newAssertion.negated()) {
                    var pattern = neg ? ((KifList) clause).get(1) : clause;
                    ofNullable(Unifier.unify(pattern, newAssertion.getEffectiveTerm(), Map.of()))
                            .ifPresent(bindings -> findMatchesRecursive(rule, rule.antecedents(), bindings, Set.of(newAssertion.id()), sourceKbId).forEach(match -> processDerivedAssertion(rule, match)));
                }
            }));
        }

        private Stream<MatchResult> findMatchesRecursive(Rule rule, List<KifTerm> remaining, Map<KifVar, KifTerm> bindings, Set<String> support, String currentKbId) {
            if (remaining.isEmpty()) return Stream.of(new MatchResult(bindings, support));
            var clause = Unifier.substFully(remaining.getFirst(), bindings);
            var nextRemaining = remaining.subList(1, remaining.size());
            var neg = (clause instanceof KifList l && l.op().filter(KIF_OP_NOT::equals).isPresent());
            var pattern = neg ? ((KifList) clause).get(1) : clause;
            if (!(pattern instanceof KifList)) return Stream.empty();
            var currentKb = getKb(currentKbId);
            var globalKb = context.getKb(GLOBAL_KB_NOTE_ID);
            return Stream.concat(currentKb.findUnifiableAssertions(pattern), (!currentKb.id.equals(GLOBAL_KB_NOTE_ID)) ? globalKb.findUnifiableAssertions(pattern) : Stream.empty())
                    .distinct().filter(c -> c.negated() == neg)
                    .flatMap(c -> ofNullable(Unifier.unify(pattern, c.getEffectiveTerm(), bindings))
                            .map(newB -> findMatchesRecursive(rule, nextRemaining, newB, Stream.concat(support.stream(), Stream.of(c.id())).collect(Collectors.toSet()), c.kb()))
                            .orElse(Stream.empty()));
        }

        private void processDerivedAssertion(Rule rule, MatchResult result) {
            var consequent = Unifier.subst(rule.consequent(), result.bindings());
            if (consequent == null) return;
            var simplified = (consequent instanceof KifList kl) ? getCogNoteContext().simplifyLogicalTerm(kl) : consequent;
            var targetNoteId = getCogNoteContext().findCommonSourceNodeId(result.supportIds());
            switch (simplified) {
                case KifList derived when derived.op().filter(KIF_OP_AND::equals).isPresent() ->
                        processDerivedConjunction(rule, derived, result, targetNoteId);
                case KifList derived when derived.op().filter(KIF_OP_FORALL::equals).isPresent() ->
                        processDerivedForall(rule, derived, result, targetNoteId);
                case KifList derived when derived.op().filter(KIF_OP_EXISTS::equals).isPresent() ->
                        processDerivedExists(rule, derived, result, targetNoteId);
                case KifList derived -> processDerivedStandard(rule, derived, result, targetNoteId);
                case KifTerm term when !(term instanceof KifVar) ->
                        System.err.println("Warning: Rule " + rule.id() + " derived non-list/non-var consequent: " + term.toKif());
                default -> {
                }
            }
        }

        private void processDerivedConjunction(Rule rule, KifList conj, MatchResult result, @Nullable String targetNoteId) {
            conj.terms().stream().skip(1).forEach(term -> {
                var simp = (term instanceof KifList kl) ? getCogNoteContext().simplifyLogicalTerm(kl) : term;
                if (simp instanceof KifList c)
                    processDerivedAssertion(new Rule(rule.id(), rule.form(), rule.antecedent(), c, rule.pri(), rule.antecedents()), result);
                else if (!(simp instanceof KifVar))
                    System.err.println("Warning: Rule " + rule.id() + " derived (and ...) with non-list/non-var conjunct: " + term.toKif());
            });
        }

        private void processDerivedForall(Rule rule, KifList forall, MatchResult result, @Nullable String targetNoteId) {
            if (forall.size() != 3 || !(forall.get(1) instanceof KifList || forall.get(1) instanceof KifVar) || !(forall.get(2) instanceof KifList body))
                return;
            var vars = KifTerm.collectSpecVars(forall.get(1));
            if (vars.isEmpty()) {
                processDerivedStandard(rule, body, result, targetNoteId);
                return;
            }
            var depth = getCogNoteContext().calculateDerivedDepth(result.supportIds()) + 1;
            if (depth > getMaxDerivationDepth()) return;
            if (body.op().filter(op -> op.equals(KIF_OP_IMPLIES) || op.equals(KIF_OP_EQUIV)).isPresent()) {
                try {
                    var pri = getCogNoteContext().calculateDerivedPri(result.supportIds(), rule.pri());
                    var derivedRule = Rule.parseRule(generateId(ID_PREFIX_RULE + "derived_"), body, pri);
                    getCogNoteContext().addRule(derivedRule);
                    if (KIF_OP_EQUIV.equals(body.op().orElse(""))) {
                        var revList = new KifList(KifAtom.of(KIF_OP_IMPLIES), body.get(2), body.get(1));
                        var revRule = Rule.parseRule(generateId(ID_PREFIX_RULE + "derived_"), revList, pri);
                        getCogNoteContext().addRule(revRule);
                    }
                } catch (IllegalArgumentException e) {
                    System.err.println("Invalid derived rule format ignored: " + body.toKif() + " from rule " + rule.id() + " | Error: " + e.getMessage());
                }
            } else {
                var pa = new PotentialAssertion(forall, getCogNoteContext().calculateDerivedPri(result.supportIds(), rule.pri()), result.supportIds(), rule.id(), false, false, false, targetNoteId, AssertionType.UNIVERSAL, List.copyOf(vars), depth);
                tryCommit(pa, rule.id());
            }
        }

        private void processDerivedExists(Rule rule, KifList exists, MatchResult result, @Nullable String targetNoteId) {
            if (exists.size() != 3 || !(exists.get(1) instanceof KifList || exists.get(1) instanceof KifVar) || !(exists.get(2) instanceof KifList body)) {
                System.err.println("Rule " + rule.id() + " derived invalid 'exists' structure: " + exists.toKif());
                return;
            }
            var vars = KifTerm.collectSpecVars(exists.get(1));
            if (vars.isEmpty()) {
                processDerivedStandard(rule, body, result, targetNoteId);
                return;
            }
            var depth = getCogNoteContext().calculateDerivedDepth(result.supportIds()) + 1;
            if (depth > getMaxDerivationDepth()) return;
            var skolemBody = getCogNoteContext().performSkolemization(body, vars, result.bindings());
            var isNeg = skolemBody.op().filter(KIF_OP_NOT::equals).isPresent();
            var isEq = !isNeg && skolemBody.op().filter(KIF_OP_EQUAL::equals).isPresent();
            var isOriented = isEq && skolemBody.size() == 3 && skolemBody.get(1).weight() > skolemBody.get(2).weight();
            var pa = new PotentialAssertion(skolemBody, getCogNoteContext().calculateDerivedPri(result.supportIds(), rule.pri()), result.supportIds(), rule.id(), isEq, isNeg, isOriented, targetNoteId, AssertionType.SKOLEMIZED, List.of(), depth);
            tryCommit(pa, rule.id());
        }

        private void processDerivedStandard(Rule rule, KifList derived, MatchResult result, @Nullable String targetNoteId) {
            if (derived.containsVar() || isTrivial(derived)) return;
            var depth = getCogNoteContext().calculateDerivedDepth(result.supportIds()) + 1;
            if (depth > getMaxDerivationDepth() || derived.weight() > MAX_DERIVED_TERM_WEIGHT) return;
            var isNeg = derived.op().filter(KIF_OP_NOT::equals).isPresent();
            if (isNeg && derived.size() != 2) {
                System.err.println("Rule " + rule.id() + " derived invalid 'not': " + derived.toKif());
                return;
            }
            var isEq = !isNeg && derived.op().filter(KIF_OP_EQUAL::equals).isPresent();
            var isOriented = isEq && derived.size() == 3 && derived.get(1).weight() > derived.get(2).weight();
            var type = derived.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
            var pa = new PotentialAssertion(derived, getCogNoteContext().calculateDerivedPri(result.supportIds(), rule.pri()), result.supportIds(), rule.id(), isEq, isNeg, isOriented, targetNoteId, type, List.of(), depth);
            tryCommit(pa, rule.id());
        }

        record MatchResult(Map<KifVar, KifTerm> bindings, Set<String> supportIds) {
        }
    }

    static class RewriteRuleReasonerPlugin extends BaseReasonerPlugin {
        @Override
        public void initialize(ReasonerContext ctx) {
            super.initialize(ctx);
            ctx.events().on(AssertionAddedEvent.class, this::handleAssertionAdded);
        }

        @Override
        public Set<Feature> getSupportedFeatures() {
            return Set.of(Feature.REWRITE_RULES);
        }

        private void handleAssertionAdded(AssertionAddedEvent event) {
            var newA = event.assertion();
            var kbId = event.getKbId();
            if (!newA.isActive() || (newA.type() != AssertionType.GROUND && newA.type() != AssertionType.SKOLEMIZED))
                return;
            var kb = getKb(kbId);
            var globalKb = context.getKb(GLOBAL_KB_NOTE_ID);
            var relevantKbs = Stream.of(kb, globalKb).filter(Objects::nonNull).distinct();
            if (newA.isEquality() && newA.isOrientedEquality() && !newA.negated() && newA.kif().size() == 3) {
                var lhs = newA.kif().get(1);
                relevantKbs.flatMap(k -> k.findUnifiableAssertions(lhs)).distinct()
                        .filter(t -> !t.id().equals(newA.id()) && Unifier.match(lhs, t.getEffectiveTerm(), Map.of()) != null)
                        .forEach(t -> applyRewrite(newA, t));
            }
            relevantKbs.flatMap(k -> k.getAllAssertions().stream()).distinct()
                    .filter(Assertion::isActive)
                    .filter(r -> r.isEquality() && r.isOrientedEquality() && !r.negated() && r.kif().size() == 3 && !r.id().equals(newA.id()))
                    .filter(r -> Unifier.match(r.kif().get(1), newA.getEffectiveTerm(), Map.of()) != null)
                    .forEach(r -> applyRewrite(r, newA));
        }

        private void applyRewrite(Assertion ruleA, Assertion targetA) {
            var lhs = ruleA.kif().get(1);
            var rhs = ruleA.kif().get(2);
            Unifier.rewrite(targetA.kif(), lhs, rhs)
                    .filter(rw -> rw instanceof KifList && !rw.equals(targetA.kif())).map(KifList.class::cast).filter(Predicate.not(Logic::isTrivial))
                    .ifPresent(rwList -> {
                        var support = Stream.concat(targetA.justificationIds().stream(), Stream.of(targetA.id(), ruleA.id())).collect(Collectors.toSet());
                        var depth = Math.max(targetA.derivationDepth(), ruleA.derivationDepth()) + 1;
                        if (depth > getMaxDerivationDepth() || rwList.weight() > MAX_DERIVED_TERM_WEIGHT) return;
                        var isNeg = rwList.op().filter(KIF_OP_NOT::equals).isPresent();
                        var isEq = !isNeg && rwList.op().filter(KIF_OP_EQUAL::equals).isPresent();
                        var isOriented = isEq && rwList.size() == 3 && rwList.get(1).weight() > rwList.get(2).weight();
                        var type = rwList.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
                        var targetNoteId = getCogNoteContext().findCommonSourceNodeId(support);
                        var pa = new PotentialAssertion(rwList, getCogNoteContext().calculateDerivedPri(support, (ruleA.pri() + targetA.pri()) / 2.0), support, ruleA.id(), isEq, isNeg, isOriented, targetNoteId, type, List.of(), depth);
                        tryCommit(pa, ruleA.id());
                    });
        }
    }

    static class UniversalInstantiationReasonerPlugin extends BaseReasonerPlugin {
        @Override
        public void initialize(ReasonerContext ctx) {
            super.initialize(ctx);
            ctx.events().on(AssertionAddedEvent.class, this::handleAssertionAdded);
        }

        @Override
        public Set<Feature> getSupportedFeatures() {
            return Set.of(Feature.UNIVERSAL_INSTANTIATION);
        }

        private void handleAssertionAdded(AssertionAddedEvent event) {
            var newA = event.assertion();
            var kbId = event.getKbId();
            var kb = getKb(kbId);
            var globalKb = context.getKb(GLOBAL_KB_NOTE_ID);
            if (!newA.isActive()) return;
            var relevantKbs = Stream.of(kb, globalKb).filter(Objects::nonNull).distinct();
            if ((newA.type() == AssertionType.GROUND || newA.type() == AssertionType.SKOLEMIZED) && newA.kif().get(0) instanceof KifAtom pred) {
                relevantKbs.flatMap(k -> k.findRelevantUniversalAssertions(pred).stream()).distinct()
                        .filter(u -> u.derivationDepth() < getMaxDerivationDepth())
                        .forEach(u -> tryInstantiate(u, newA));
            } else if (newA.type() == AssertionType.UNIVERSAL && newA.derivationDepth() < getMaxDerivationDepth()) {
                ofNullable(newA.getEffectiveTerm()).filter(KifList.class::isInstance).map(KifList.class::cast)
                        .flatMap(KifList::op).map(KifAtom::of)
                        .ifPresent(pred -> relevantKbs.flatMap(k -> k.getAllAssertions().stream()).distinct()
                                .filter(Assertion::isActive).filter(g -> g.type() == AssertionType.GROUND || g.type() == AssertionType.SKOLEMIZED)
                                .filter(g -> g.getReferencedPredicates().contains(pred))
                                .forEach(g -> tryInstantiate(newA, g)));
            }
        }

        private void tryInstantiate(Assertion uniA, Assertion groundA) {
            var formula = uniA.getEffectiveTerm();
            var vars = uniA.quantifiedVars();
            if (vars.isEmpty()) return;
            findSubExpressionMatches(formula, groundA.kif())
                    .filter(bindings -> bindings.keySet().containsAll(vars))
                    .forEach(bindings -> {
                        var instFormula = Unifier.subst(formula, bindings);
                        if (instFormula instanceof KifList instList && !instFormula.containsVar() && !isTrivial(instList)) {
                            var support = Stream.concat(Stream.of(groundA.id(), uniA.id()), Stream.concat(groundA.justificationIds().stream(), uniA.justificationIds().stream())).collect(Collectors.toSet());
                            var pri = getCogNoteContext().calculateDerivedPri(support, (groundA.pri() + uniA.pri()) / 2.0);
                            var depth = Math.max(groundA.derivationDepth(), uniA.derivationDepth()) + 1;
                            if (depth <= getMaxDerivationDepth()) {
                                var isNeg = instList.op().filter(KIF_OP_NOT::equals).isPresent();
                                var isEq = !isNeg && instList.op().filter(KIF_OP_EQUAL::equals).isPresent();
                                var isOriented = isEq && instList.size() == 3 && instList.get(1).weight() > instList.get(2).weight();
                                var type = instList.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
                                var targetNoteId = getCogNoteContext().findCommonSourceNodeId(support);
                                var pa = new PotentialAssertion(instList, pri, support, uniA.id(), isEq, isNeg, isOriented, targetNoteId, type, List.of(), depth);
                                tryCommit(pa, uniA.id());
                            }
                        }
                    });
        }

        private Stream<Map<KifVar, KifTerm>> findSubExpressionMatches(KifTerm expr, KifTerm target) {
            return Stream.concat(
                    ofNullable(Unifier.match(expr, target, Map.of())).stream(),
                    (expr instanceof KifList l) ? l.terms().stream().flatMap(sub -> findSubExpressionMatches(sub, target)) : Stream.empty()
            );
        }
    }

    static class BackwardChainingReasonerPlugin extends BaseReasonerPlugin {
        @Override
        public Set<Feature> getSupportedFeatures() {
            return Set.of(Feature.BACKWARD_CHAINING, Feature.OPERATOR_SUPPORT);
        }

        @Override
        public Set<QueryType> getSupportedQueryTypes() {
            return Set.of(QueryType.ASK_BINDINGS, QueryType.ASK_TRUE_FALSE);
        }

        @Override
        public CompletableFuture<Answer> executeQuery(Query query) {
            return CompletableFuture.supplyAsync(() -> {
                var results = new ArrayList<Map<KifVar, KifTerm>>();
                var maxDepth = (Integer) query.parameters().getOrDefault("maxDepth", MAX_BACKWARD_CHAIN_DEPTH);
                try {
                    prove(query.pattern(), query.targetKbId(), Map.of(), maxDepth, new HashSet<>()).forEach(results::add);
                    return Answer.success(query.id(), results);
                } catch (Exception e) {
                    System.err.println("Backward chaining query failed: " + e.getMessage());
                    e.printStackTrace();
                    return Answer.error(query.id(), e.getMessage());
                }
            }, context.events().exe);
        }

        private Stream<Map<KifVar, KifTerm>> prove(KifTerm goal, @Nullable String kbId, Map<KifVar, KifTerm> bindings, int depth, Set<KifTerm> proofStack) {
            if (depth <= 0) return Stream.empty();
            var currentGoal = Unifier.substFully(goal, bindings);
            if (!proofStack.add(currentGoal)) return Stream.empty();
            Stream<Map<KifVar, KifTerm>> resultStream = Stream.empty();
            if (currentGoal instanceof KifList goalList && !goalList.terms().isEmpty() && goalList.get(0) instanceof KifAtom opAtom) {
                resultStream = context.operators().get(opAtom).flatMap(op -> executeOperator(op, goalList, bindings, currentGoal)).stream();
            }
            var kbStream = Stream.concat(getKb(kbId).findUnifiableAssertions(currentGoal), (kbId != null && !kbId.equals(GLOBAL_KB_NOTE_ID)) ? context.getKb(GLOBAL_KB_NOTE_ID).findUnifiableAssertions(currentGoal) : Stream.empty())
                    .distinct().flatMap(fact -> ofNullable(Unifier.unify(currentGoal, fact.kif(), bindings)).stream());
            resultStream = Stream.concat(resultStream, kbStream);
            var ruleStream = context.rules().stream().flatMap(rule -> {
                var renamedRule = renameRuleVariables(rule, depth);
                return ofNullable(Unifier.unify(renamedRule.consequent(), currentGoal, bindings))
                        .map(consequentBindings -> proveAntecedents(renamedRule.antecedents(), kbId, consequentBindings, depth - 1, new HashSet<>(proofStack)))
                        .orElse(Stream.empty());
            });
            resultStream = Stream.concat(resultStream, ruleStream);
            proofStack.remove(currentGoal);
            return resultStream.distinct();
        }

        private Optional<Map<KifVar, KifTerm>> executeOperator(Operator op, KifList goalList, Map<KifVar, KifTerm> bindings, KifTerm currentGoal) {
            try {
                return op.exe(goalList, context).handle((opResult, ex) -> {
                    if (ex != null) {
                        System.err.println("Operator execution failed for " + op.pred().toKif() + ": " + ex.getMessage());
                        return Optional.<Map<KifVar, KifTerm>>empty();
                    }
                    if (opResult == null) return Optional.<Map<KifVar, KifTerm>>empty();
                    if (opResult.equals(KifAtom.of("true"))) return Optional.of(bindings);
                    return ofNullable(Unifier.unify(currentGoal, opResult, bindings));
                }).join();
            } catch (Exception e) {
                System.err.println("Operator execution exception for " + op.pred().toKif() + ": " + e.getMessage());
                return Optional.empty();
            }
        }

        private Stream<Map<KifVar, KifTerm>> proveAntecedents(List<KifTerm> antecedents, @Nullable String kbId, Map<KifVar, KifTerm> bindings, int depth, Set<KifTerm> proofStack) {
            if (antecedents.isEmpty()) return Stream.of(bindings);
            var first = antecedents.getFirst();
            var rest = antecedents.subList(1, antecedents.size());
            return prove(first, kbId, bindings, depth, proofStack).flatMap(newBindings -> proveAntecedents(rest, kbId, newBindings, depth, proofStack));
        }

        private Rule renameRuleVariables(Rule rule, int depth) {
            var suffix = "_d" + depth + "_" + idCounter.incrementAndGet();
            Map<KifVar, KifTerm> renameMap = rule.form().vars().stream().collect(Collectors.toMap(Function.identity(), v -> KifVar.of(v.name() + suffix)));
            var renamedForm = (KifList) Unifier.subst(rule.form(), renameMap);
            try {
                return Rule.parseRule(rule.id() + suffix, renamedForm, rule.pri());
            } catch (IllegalArgumentException e) {
                System.err.println("Error renaming rule variables: " + e.getMessage());
                return rule;
            }
        }
    }

    static class StatusUpdaterPlugin extends BasePlugin {
        private final Consumer<SystemStatusEvent> uiUpdater;

        StatusUpdaterPlugin(Consumer<SystemStatusEvent> uiUpdater) {
            this.uiUpdater = uiUpdater;
        }

        @Override
        public void start(Events ev, Cognition ctx) {
            super.start(ev, ctx);
            ev.on(AssertionAddedEvent.class, e -> updateStatus());
            ev.on(AssertionRetractedEvent.class, e -> updateStatus());
            ev.on(AssertionEvictedEvent.class, e -> updateStatus());
            ev.on(AssertionStatusChangedEvent.class, e -> updateStatus());
            ev.on(RuleAddedEvent.class, e -> updateStatus());
            ev.on(RuleRemovedEvent.class, e -> updateStatus());
            ev.on(AddedEvent.class, e -> updateStatus());
            ev.on(RemovedEvent.class, e -> updateStatus());
            ev.on(LlmInfoEvent.class, e -> updateStatus());
            ev.on(LlmUpdateEvent.class, e -> updateStatus());
            ev.on(SystemStatusEvent.class, uiUpdater);
            updateStatus();
        }

        private void updateStatus() {
            publish(new SystemStatusEvent(context.cog.systemStatus, context.kbCount(), context.kbTotalCapacity(), context.cog.activeLlmTasks.size(), 0, context.ruleCount()));
        }
    }

    static class WebSocketBroadcasterPlugin extends BasePlugin {
        private final Cog server;

        WebSocketBroadcasterPlugin(Cog server) {
            this.server = server;
        }

        @Override
        public void start(Events ev, Cognition ctx) {
            super.start(ev, ctx);
            ev.on(AssertionAddedEvent.class, e -> broadcastMessage("assert-added", e.assertion(), e.getKbId()));
            ev.on(AssertionRetractedEvent.class, e -> broadcastMessage("retract", e.assertion(), e.getKbId()));
            ev.on(AssertionEvictedEvent.class, e -> broadcastMessage("evict", e.assertion(), e.getKbId()));
            ev.on(LlmInfoEvent.class, e -> broadcastMessage("llm-info", e.llmItem()));
            ev.on(LlmUpdateEvent.class, e -> broadcastMessage("llm-update", e));
            ev.on(WebSocketBroadcastEvent.class, e -> safeBroadcast(e.message()));
            if (server.broadcastInputAssertions) ev.on(ExternalInputEvent.class, this::onExternalInput);
        }

        private void onExternalInput(ExternalInputEvent event) {
            if (event.term() instanceof KifList list) {
                var tempId = generateId(ID_PREFIX_INPUT_ITEM);
                var pri = (event.sourceId().startsWith("llm-") ? LLM_ASSERTION_BASE_PRIORITY : INPUT_ASSERTION_BASE_PRIORITY) / (1.0 + list.weight());
                var type = list.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
                var kbId = requireNonNullElse(event.targetNoteId(), GLOBAL_KB_NOTE_ID);
                var tempAssertion = new Assertion(tempId, list, pri, System.currentTimeMillis(), event.targetNoteId(), Set.of(), type, false, false, false, List.of(), 0, true, kbId);
                broadcastMessage("assert-input", tempAssertion, kbId);
            }
        }

        private void broadcastMessage(String type, Assertion assertion, String kbId) {
            var kif = assertion.toKifString();
            var msg = switch (type) {
                case "assert-added", "assert-input" -> String.format("%s %.4f %s [%s] {type:%s, depth:%d, kb:%s}", type,
                        assertion.pri(), kif, assertion.id(), assertion.type(), assertion.derivationDepth(), kbId);
                case "retract", "evict" -> String.format("%s %s", type, assertion.id());
                default -> String.format("%s %.4f %s [%s]", type, assertion.pri(), kif, assertion.id());
            };
            safeBroadcast(msg);
        }

        private void broadcastMessage(String type, UI.AttachmentViewModel llmItem) {
            if (!type.equals("llm-info") || llmItem.noteId() == null) return;
            var msg = String.format("llm-info %s [%s] {type:%s, status:%s, content:\"%s\"}",
                    llmItem.noteId(), llmItem.id(), llmItem.attachmentType(), llmItem.llmStatus(), llmItem.content().replace("\"", "\\\""));
            safeBroadcast(msg);
        }

        private void broadcastMessage(String type, LlmUpdateEvent event) {
            if (!type.equals("llm-update")) return;
            var msg = String.format("llm-update %s {status:%s, content:\"%s\"}",
                    event.taskId(), event.status(), event.content().replace("\"", "\\\""));
            safeBroadcast(msg);
        }

        private void safeBroadcast(String message) {
            try {
                if (!server.websocket.getConnections().isEmpty()) server.websocket.broadcast(message);
            } catch (Exception e) {
                if (!(e instanceof ConcurrentModificationException || ofNullable(e.getMessage()).map(m -> m.contains("closed")).orElse(false)))
                    System.err.println("Error during WebSocket broadcast: " + e.getMessage());
            }
        }
    }

    static class UiUpdatePlugin extends BasePlugin {
        private final UI swingUI;

        UiUpdatePlugin(UI ui, Cog cog) {
            this.swingUI = ui;
        }

        @Override
        public void start(Events events, Cognition ctx) {
            super.start(events, ctx);
            events.on(AssertionAddedEvent.class, e -> handleUiUpdate("assert-added", e.assertion()));
            events.on(AssertionRetractedEvent.class, e -> handleUiUpdate("retract", e.assertion()));
            events.on(AssertionEvictedEvent.class, e -> handleUiUpdate("evict", e.assertion()));
            events.on(AssertionStatusChangedEvent.class, this::handleStatusChange);
            events.on(LlmInfoEvent.class, e -> handleUiUpdate("llm-info", e.llmItem()));
            events.on(LlmUpdateEvent.class, this::handleLlmUpdate);
            events.on(AddedEvent.class, e -> SwingUtilities.invokeLater(() -> swingUI.addNoteToList(e.note())));
            events.on(RemovedEvent.class, e -> SwingUtilities.invokeLater(() -> swingUI.removeNoteFromList(e.note().id)));
            events.on(QueryResultEvent.class, e -> handleUiUpdate("query-result", e.result()));
            events.on(QueryRequestEvent.class, e -> handleUiUpdate("query-sent", e.query()));
        }

        private void handleUiUpdate(String type, Object payload) {
            if (swingUI == null || !swingUI.isDisplayable()) return;
            UI.AttachmentViewModel vm = null;
            String displayNoteId = null;

            switch (payload) {
                case Assertion assertion -> {
                    var sourceNoteId = assertion.sourceNoteId();
                    var derivedNoteId = (sourceNoteId == null && assertion.derivationDepth() > 0) ? context.findCommonSourceNodeId(assertion.justificationIds()) : null;

                    var i = sourceNoteId != null ? sourceNoteId : derivedNoteId;
                    displayNoteId = i != null ? i : assertion.kb().equals(GLOBAL_KB_NOTE_ID) ? GLOBAL_KB_NOTE_ID : assertion.kb();

                    if (displayNoteId == null)
                        return;

                    vm = UI.AttachmentViewModel.fromAssertion(assertion, type, displayNoteId);
                }
                case UI.AttachmentViewModel llmVm -> {
                    vm = llmVm;
                    displayNoteId = vm.noteId();
                }
                case Answer result -> {
                    displayNoteId = GLOBAL_KB_NOTE_ID;
                    var content = String.format("Query Result (%s): %s -> %d bindings", result.status, result.query, result.bindings().size());
                    vm = UI.AttachmentViewModel.forQuery(result.query + "_res", displayNoteId, content, UI.AttachmentType.QUERY_RESULT, System.currentTimeMillis(), GLOBAL_KB_NOTE_ID);
                }
                case Query query -> {
                    displayNoteId = requireNonNullElse(query.targetKbId(), GLOBAL_KB_NOTE_ID);
                    var content = "Query Sent: " + query.pattern().toKif();
                    vm = UI.AttachmentViewModel.forQuery(query.id() + "_sent", displayNoteId, content, UI.AttachmentType.QUERY_SENT, System.currentTimeMillis(), displayNoteId);
                }
                default -> {
                    return;
                }
            }
            final var finalVm = vm;
            final var finalDisplayNoteId = displayNoteId;
            SwingUtilities.invokeLater(() -> swingUI.handleSystemUpdate(finalVm, finalDisplayNoteId));
        }

        private void handleStatusChange(AssertionStatusChangedEvent event) {
            context.findAssertionByIdAcrossKbs(event.assertionId()).ifPresent(a -> handleUiUpdate(event.isActive() ? "status-active" : "status-inactive", a));
        }

        private void handleLlmUpdate(LlmUpdateEvent event) {
            SwingUtilities.invokeLater(() -> swingUI.updateLlmItem(event.taskId(), event.status(), event.content()));
        }
    }

    private class MyWebSocketServer extends WebSocketServer {
        public MyWebSocketServer(InetSocketAddress address) {
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
                        var query = new Query(queryId, QueryType.ASK_BINDINGS, queryPattern, null, Map.of());
                        events.emit(new QueryRequestEvent(query));
                    } catch (ParseException e) {
                        conn.send("error Parse error: " + e.getMessage());
                    }
                }
                default -> {
                    try {
                        KifParser.parseKif(trimmed).forEach(term -> events.emit(new ExternalInputEvent(term, sourceId, null)));
                    } catch (ParseException | ClassCastException e) {
                        System.err.printf("WS Message Parse Error from %s: %s | Original: %s...%n", sourceId, e.getMessage(), trimmed.substring(0, Math.min(trimmed.length(), MAX_WS_PARSE_PREVIEW)));
                    } catch (Exception e) {
                        System.err.println("Unexpected WS message processing error from " + sourceId + ": " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
        }
    }
}
