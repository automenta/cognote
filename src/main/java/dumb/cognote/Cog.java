package dumb.cognote;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dumb.cognote.plugin.*;
import dumb.cognote.tool.*;
import dumb.cognote.util.Events;
import dumb.cognote.util.Json;
import dumb.cognote.util.KifParser;
import dumb.cognote.util.Log;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static dumb.cognote.Note.Status.IDLE;
import static dumb.cognote.util.Log.error;
import static dumb.cognote.util.Log.message;
import static java.util.Optional.ofNullable;

public class Cog {

    public static final String ID_PREFIX_NOTE = "note-";
    public static final String ID_PREFIX_LLM_ITEM = "llm_";
    public static final String ID_PREFIX_QUERY = "query_";
    public static final String ID_PREFIX_LLM_RESULT = "llmres_";
    public static final String ID_PREFIX_RULE = "rule_";
    public static final String ID_PREFIX_INPUT_ITEM = "input_";
    public static final String ID_PREFIX_PLUGIN = "plugin_";
    public static final String GLOBAL_KB_NOTE_ID = "kb://global";
    public static final String GLOBAL_KB_NOTE_TITLE = "Global Knowledge";
    public static final String CONFIG_NOTE_ID = "note-config";
    public static final String CONFIG_NOTE_TITLE = "System Configuration";
    public static final double INPUT_ASSERTION_BASE_PRIORITY = 10;
    public static final int MAX_WS_PARSE_PREVIEW = 100;
    public static final double DEFAULT_RULE_PRIORITY = 1;
    public static final AtomicLong id = new AtomicLong(System.currentTimeMillis());
    static final int KB_SIZE_THRESHOLD_WARN_PERCENT = 90;
    static final int KB_SIZE_THRESHOLD_HALT_PERCENT = 98;
    static final double DERIVED_PRIORITY_DECAY = 0.95;
    static final int DEFAULT_KB_CAPACITY = 64 * 1024;
    static final int DEFAULT_REASONING_DEPTH = 4;
    static final boolean DEFAULT_BROADCAST_INPUT = false;
    private static final String STATE_FILE = "cognote_state.json";
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 2;
    private static final int MAX_KIF_PARSE_PREVIEW = 50;
    public final Cognition context;
    public final LM lm;
    public final Dialogue dialogue;
    public final Events events;
    public final Tools tools;
    public final ScheduledExecutorService mainExecutor =
            Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors(), Thread.ofVirtual().factory());
    protected final Reason.ReasonerManager reasoner;
    final Plugins plugins;
    final AtomicBoolean running = new AtomicBoolean(true);
    final AtomicBoolean paused = new AtomicBoolean(true);
    final ConcurrentMap<String, Note> notes = new ConcurrentHashMap<>();
    private final Object pauseLock = new Object();
    private final Persist persistenceManager;
    public volatile String status = "Initializing";
    public volatile boolean broadcastInputAssertions;
    public volatile int globalKbCapacity = DEFAULT_KB_CAPACITY;
    public volatile int reasoningDepthLimit = DEFAULT_REASONING_DEPTH;

    public Cog() {
        this.events = new Events(Cog.this.mainExecutor);
        Log.setEvents(this.events);
        this.tools = new Tools();

        var tms = new Truths.BasicTMS(events);
        this.context = new Cognition(globalKbCapacity, tms, this);

        this.dialogue = new Dialogue(this);
        this.reasoner = new Reason.ReasonerManager(events, context, dialogue);
        this.plugins = new Plugins(this.events, this.context);
        this.persistenceManager = new Persist(this, context, tms);

        this.lm = new LM(this);

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));

        initPlugins();
    }

    public static void main(String[] args) {
        String rulesFile = null;
        var port = 8081;

        for (var i = 0; i < args.length; i++) {
            try {
                switch (args[i]) {
                    case "-r", "--rules" -> rulesFile = args[++i];
                    case "-p", "--port" -> port = Integer.parseInt(args[++i]);
                    default -> Log.warning("Unknown option: " + args[i] + ". Config via JSON.");
                }
            } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
                error(String.format("Error parsing argument for %s: %s", (i > 0 ? args[i - 1] : args[i]), e.getMessage()));
                printUsageAndExit();
            }
        }

        try {
            var c = new Cog();
            c.plugins.add(new dumb.cognote.plugin.WebSocketPlugin(new java.net.InetSocketAddress(port), c));

            c.start();

            if (rulesFile != null) {
                c.loadRules(rulesFile);
            }

            try {
                Thread.currentThread().join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error("Main thread interrupted.");
            }
        } catch (Exception e) {
            error("Initialization/Startup failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void printUsageAndExit() {
        System.err.printf("Usage: java %s [-p port] [-r rules_file.kif]%n", Cog.class.getName());
        System.err.println("Note: Most configuration is now managed via the Configuration note and persisted in " + STATE_FILE);
        System.exit(1);
    }

    static Note createDefaultConfigNote() {
        return new Note(CONFIG_NOTE_ID, CONFIG_NOTE_TITLE, Json.str(new Configuration(
                LM.DEFAULT_LLM_URL,
                LM.DEFAULT_LLM_MODEL,
                DEFAULT_KB_CAPACITY,
                DEFAULT_REASONING_DEPTH,
                DEFAULT_BROADCAST_INPUT
        )), Note.Status.IDLE);
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

    protected void initPlugins() {
        plugins.add(new InputPlugin());
        plugins.add(new RetractionPlugin());
        plugins.add(new TmsPlugin());
        plugins.add(new UserFeedbackPlugin());
        plugins.add(new TaskDecomposePlugin());

        reasoner.add(new Reason.ForwardChainingReasonerPlugin());
        reasoner.add(new Reason.RewriteRuleReasonerPlugin());
        reasoner.add(new Reason.UniversalInstantiationReasonerPlugin());
        reasoner.add(new Reason.BackwardChainingReasonerPlugin());

        tools.add(new AssertKIFTool(this));
        tools.add(new GetNoteTextTool(this));
        tools.add(new FindAssertionsTool(this));
        tools.add(new RetractTool(this));
        tools.add(new QueryTool(this));
        tools.add(new LogMessageTool(this));
        tools.add(new SummarizeTool(this));
        tools.add(new IdentifyConceptsTool(this));
        tools.add(new GenerateQuestionsTool(this));
        tools.add(new TextToKifTool(this));
        tools.add(new DecomposeGoalTool(this));
        tools.add(new EnhanceTool(this));
    }

    public void status(String status) {
        this.status = status;
        events.emit(new Event.SystemStatusEvent(status, context.kbCount(), context.kbTotalCapacity(), lm.activeLlmTasks.size(), context.ruleCount()));
    }

    public Optional<Note> note(String id) {
        return ofNullable(notes.get(id));
    }

    public List<Note> getAllNotes() {
        return new ArrayList<>(notes.values());
    }

    public void addNote(Note note) {
        if (notes.putIfAbsent(note.id(), note) == null) {
            events.emit(new Event.AddedEvent(note));
            message("Added note: " + note.title() + " [" + note.id() + "]");
        } else {
            message("Note with ID " + note.id() + " already exists.");
        }
    }

    public void removeNote(String noteId) {
        if ((noteId.equals(GLOBAL_KB_NOTE_ID) || noteId.equals(CONFIG_NOTE_ID))) {
            error("Attempted to remove system note: " + noteId + ". Operation ignored.");
            return;
        }

        ofNullable(notes.remove(noteId)).ifPresent(note -> {
            events.emit(new Event.RetractionRequestEvent(noteId, Logic.RetractionType.BY_NOTE, "CogNote-Remove", noteId));
            events.emit(new Event.NoteDeletedEvent(noteId));
            context.removeNoteKb(noteId, "CogNote-Remove");
            context.removeActiveNote(noteId);
            message("Removed note: " + note.title() + " [" + note.id() + "]");
        });
    }

    public void updateNoteStatus(String noteId, Note.Status newStatus) {
        ofNullable(notes.get(noteId)).ifPresent(note -> {
            if (note.status() != newStatus) {
                var oldStatus = note.status();
                note.status = newStatus;
                note.updated = System.currentTimeMillis();

                switch (newStatus) {
                    case ACTIVE -> context.addActiveNote(noteId);
                    case IDLE, PAUSED, COMPLETED -> context.removeActiveNote(noteId);
                }

                events.emit(new Event.NoteStatusEvent(note, oldStatus, newStatus));
                message("Updated note status for [" + note.id() + "] to " + newStatus);

                if (newStatus == Note.Status.ACTIVE) {
                    context.kb(noteId).getAllAssertions().forEach(assertion ->
                            events.emit(new Event.ExternalInputEvent(assertion.kif(), "note-start:" + noteId, noteId))
                    );
                    context.rules().stream()
                            .filter(rule -> noteId.equals(rule.sourceNoteId()))
                            .toList()
                            .forEach(rule -> events.emit(new Event.ExternalInputEvent(rule.form(), "note-start:" + noteId, noteId)));
                }
            }
        });
    }

    public void startNote(String noteId) {
        ofNullable(notes.get(noteId)).ifPresent(note -> {
            if (note.status() == IDLE || note.status() == Note.Status.PAUSED) {
                message("Starting note: " + note.title() + " [" + note.id() + "]");
                updateNoteStatus(noteId, Note.Status.ACTIVE);
            } else {
                message("Note " + note.title() + " [" + note.id() + "] is already " + note.status() + ". Cannot start.");
            }
        });
    }

    public void pauseNote(String noteId) {
        ofNullable(notes.get(noteId)).ifPresent(note -> {
            if (note.status() == Note.Status.ACTIVE) {
                message("Pausing note: " + note.title() + " [" + note.id() + "]");
                updateNoteStatus(noteId, Note.Status.PAUSED);
            } else {
                message("Note " + note.title() + " [" + note.id() + "] is not ACTIVE. Cannot pause.");
            }
        });
    }

    public void completeNote(String noteId) {
        ofNullable(notes.get(noteId)).ifPresent(note -> {
            if (note.status() == Note.Status.ACTIVE || note.status() == Note.Status.PAUSED) {
                message("Completing note: " + note.title() + " [" + note.id() + "]");
                updateNoteStatus(noteId, Note.Status.COMPLETED);
            } else {
                message("Note " + note.title() + " [" + note.id() + "] is already " + note.status() + ". Cannot complete.");
            }
        });
    }

    public void updateNoteText(String noteId, String newText) {
        ofNullable(notes.get(noteId)).ifPresent(note -> {
            if (!note.text.equals(newText)) {
                note.text = newText;
                note.updated = System.currentTimeMillis();
                events.emit(new Event.NoteUpdatedEvent(note));
                message("Updated text for note [" + note.id() + "]");
            }
        });
    }

    public void updateNoteTitle(String noteId, String newTitle) {
        ofNullable(notes.get(noteId)).ifPresent(note -> {
            if (!note.title.equals(newTitle)) {
                note.title = newTitle;
                note.updated = System.currentTimeMillis();
                events.emit(new Event.NoteUpdatedEvent(note));
                message("Updated title for note [" + note.id() + "]");
            }
        });
    }

    public void updateNotePriority(String noteId, int newPriority) {
        ofNullable(notes.get(noteId)).ifPresent(note -> {
            if (note.pri != newPriority) {
                note.pri = newPriority;
                note.updated = System.currentTimeMillis();
                events.emit(new Event.NoteUpdatedEvent(note));
                message("Updated priority for note [" + note.id() + "] to " + newPriority);
            }
        });
    }

    public void updateNoteColor(String noteId, String newColor) {
        ofNullable(notes.get(noteId)).ifPresent(note -> {
            if (!Objects.equals(note.color, newColor)) {
                note.color = newColor;
                note.updated = System.currentTimeMillis();
                events.emit(new Event.NoteUpdatedEvent(note));
                message("Updated color for note [" + note.id() + "] to " + newColor);
            }
        });
    }

    public void updateNote(String noteId, @Nullable String title, @Nullable String content, @Nullable Note.Status state, @Nullable Integer priority, @Nullable String color) {
        ofNullable(notes.get(noteId)).ifPresent(note -> {
            boolean changed = false;
            if (title != null && !note.title.equals(title)) {
                note.title = title;
                changed = true;
            }
            if (content != null && !note.text.equals(content)) {
                note.text = content;
                changed = true;
            }
            if (state != null && note.status() != state) {
                updateNoteStatus(noteId, state);
            }
            if (priority != null && note.pri != priority) {
                note.pri = priority;
                changed = true;
            }
            if (color != null && !Objects.equals(note.color, color)) {
                note.color = color;
                changed = true;
            }

            if (changed) {
                note.updated = System.currentTimeMillis();
                events.emit(new Event.NoteUpdatedEvent(note));
                message("Updated note [" + note.id() + "]");
            }
        });
    }

    public void cloneNote(String noteId) {
        ofNullable(notes.get(noteId)).ifPresentOrElse(originalNote -> {
            var c = new Note(
                    Cog.id(Cog.ID_PREFIX_NOTE),
                    "Clone of " + originalNote.title(),
                    originalNote.text(),
                    Note.Status.IDLE
            );
            c.pri = originalNote.pri;
            c.color = originalNote.color;
            c.updated = System.currentTimeMillis();
            addNote(c);
            message("Cloned note [" + noteId + "] to [" + c.id() + "]");
        }, () -> error("Attempted to clone unknown note ID: " + noteId));
    }

    public synchronized void clear() {
        message("Clearing all knowledge...");
        setPaused(true);
        context.getAllNoteIds().stream()
                .filter(noteId -> !noteId.equals(GLOBAL_KB_NOTE_ID) && !noteId.equals(CONFIG_NOTE_ID))
                .forEach(noteId -> events.emit(new Event.RetractionRequestEvent(noteId, Logic.RetractionType.BY_NOTE, "System-ClearAll", noteId)));
        context.kbGlobal().getAllAssertionIds().forEach(id -> context.truth.remove(id, "System-ClearAll"));
        new HashSet<>(context.rules()).forEach(context::removeRule);

        context.clear();

        var configNote = notes.get(CONFIG_NOTE_ID);
        var globalKbNote = notes.get(GLOBAL_KB_NOTE_ID);

        notes.clear();

        globalKbNote.status = configNote.status = IDLE;

        notes.put(CONFIG_NOTE_ID, configNote);
        notes.put(GLOBAL_KB_NOTE_ID, globalKbNote);

        context.addActiveNote(GLOBAL_KB_NOTE_ID);

        events.emit(new Event.AddedEvent(notes.get(GLOBAL_KB_NOTE_ID)));
        events.emit(new Event.AddedEvent(notes.get(CONFIG_NOTE_ID)));

        status("Cleared");
        setPaused(false);
        message("Knowledge cleared.");
        events.emit(new Event.SystemStatusEvent(status, context.kbCount(), context.kbTotalCapacity(), lm.activeLlmTasks.size(), context.ruleCount()));
    }

    public boolean updateConfig(String newConfigJsonText) {
        try {
            var newConfig = Json.obj(newConfigJsonText, Configuration.class);
            applyConfig(newConfig);
            note(CONFIG_NOTE_ID).ifPresent(note -> {
                note.text = Json.str(newConfig);
                note.updated = System.currentTimeMillis();
                events.emit(new Event.NoteUpdatedEvent(note));
                message("Configuration updated.");
            });
            lm.reconfigure();
            return true;
        } catch (Exception e) {
            error("Failed to parse or apply new configuration JSON: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void load() {
        persistenceManager.load(STATE_FILE);
    }

    void applyConfig(Configuration config) {
        this.lm.llmApiUrl = config.llmApiUrl();
        this.lm.llmModel = config.llmModel();
        this.globalKbCapacity = config.globalKbCapacity();
        this.reasoningDepthLimit = config.reasoningDepthLimit();
        this.broadcastInputAssertions = config.broadcastInputAssertions();
        message(String.format("System config applied: KBSize=%d, BroadcastInput=%b, LLM_URL=%s, LLM_Model=%s, MaxDepth=%d",
                globalKbCapacity, broadcastInputAssertions, lm.llmApiUrl, lm.llmModel, reasoningDepthLimit));
    }

    public void start() {
        if (!running.get()) {
            error("Cannot restart a stopped system.");
            return;
        }
        paused.set(true);
        status("Initializing");

        load();
        lm.reconfigure();
        plugins.initializeAll();
        reasoner.initializeAll();

        notes.values().stream()
                .filter(note -> note.status() == Note.Status.ACTIVE)
                .forEach(note -> context.addActiveNote(note.id()));

        context.addActiveNote(GLOBAL_KB_NOTE_ID);

        setPaused(false);
        status("Running");
        message("System started.");
        events.emit(new Event.SystemStatusEvent(status, context.kbCount(), context.kbTotalCapacity(), lm.activeLlmTasks.size(), context.ruleCount()));
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        message("Stopping system...");
        status("Stopping");
        paused.set(false);
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }

        dialogue.clear();
        reasoner.shutdownAll();
        lm.activeLlmTasks.values().forEach(f -> f.cancel(true));
        lm.activeLlmTasks.clear();
        save();
        plugins.shutdownAll();

        shutdownExecutor(mainExecutor, "Main Executor");
        events.shutdown();
        status("Stopped");
        message("System stopped.");
    }

    public void save() {
        persistenceManager.save(STATE_FILE);
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
                            KifParser.parseKif(kifText).forEach(term -> events.emit(new Event.ExternalInputEvent(term, "file:" + filename, null)));
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
        Consumer<Event> listener = e -> {
            if (e instanceof Answer.AnswerEvent resultEvent && resultEvent.result().queryId().equals(queryID)) {
                answerFuture.complete(resultEvent.result());
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

    public SystemStateSnapshot getSystemStateSnapshot() {
        return new SystemStateSnapshot(
                getAllNotes(),
                context.truth.getAllActiveAssertions(),
                context.rules(),
                new Configuration(this)
        );
    }

    public enum QueryType {ASK_BINDINGS, ASK_TRUE_FALSE, ACHIEVE_GOAL}

    public enum Feature {FORWARD_CHAINING, BACKWARD_CHAINING, TRUTH_MAINTENANCE, CONTRADICTION_DETECTION, UNCERTAINTY_HANDLING, OPERATOR_SUPPORT, REWRITE_RULES, UNIVERSAL_INSTANTIATION}

    public enum QueryStatus {SUCCESS, FAILURE, TIMEOUT, ERROR}

    public enum TaskStatus {IDLE, SENDING, PROCESSING, DONE, ERROR, CANCELLED}

    @FunctionalInterface
    interface DoubleDoublePredicate {
        boolean test(double a, double b);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Configuration(
            @JsonProperty("llmApiUrl") String llmApiUrl,
            @JsonProperty("llmModel") String llmModel,
            @JsonProperty("globalKbCapacity") int globalKbCapacity,
            @JsonProperty("reasoningDepthLimit") int reasoningDepthLimit,
            @JsonProperty("broadcastInputAssertions") boolean broadcastInputAssertions
    ) {
        @JsonCreator
        public Configuration(
                @JsonProperty("llmApiUrl") String llmApiUrl,
                @JsonProperty("llmModel") String llmModel,
                @JsonProperty("globalKbCapacity") Integer globalKbCapacity,
                @JsonProperty("reasoningDepthLimit") Integer reasoningDepthLimit,
                @JsonProperty("broadcastInputAssertions") Boolean broadcastInputAssertions
        ) {
            this(
                    llmApiUrl != null ? llmApiUrl : LM.DEFAULT_LLM_URL,
                    llmModel != null ? llmModel : LM.DEFAULT_LLM_MODEL,
                    globalKbCapacity != null ? globalKbCapacity : DEFAULT_KB_CAPACITY,
                    reasoningDepthLimit != null ? reasoningDepthLimit : DEFAULT_REASONING_DEPTH,
                    broadcastInputAssertions != null ? broadcastInputAssertions : DEFAULT_BROADCAST_INPUT
            );
        }

        public Configuration() {
            this(LM.DEFAULT_LLM_URL, LM.DEFAULT_LLM_MODEL, DEFAULT_KB_CAPACITY, DEFAULT_REASONING_DEPTH, DEFAULT_BROADCAST_INPUT);
        }

        public Configuration(String llmApiUrl, String llmModel, int globalKbCapacity, int reasoningDepthLimit, boolean broadcastInputAssertions) {
            this.llmApiUrl = llmApiUrl;
            this.llmModel = llmModel;
            this.globalKbCapacity = globalKbCapacity;
            this.reasoningDepthLimit = reasoningDepthLimit;
            this.broadcastInputAssertions = broadcastInputAssertions;
        }

        public Configuration(Cog cog) {
            this(cog.lm.llmApiUrl, cog.lm.llmModel, cog.globalKbCapacity, cog.reasoningDepthLimit, cog.broadcastInputAssertions);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SystemStateSnapshot(
            List<Note> notes,
            Collection<Assertion> assertions,
            Collection<Rule> rules,
            Cog.Configuration configuration
    ) {
    }
}
