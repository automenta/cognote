package dumb.cognote;

import dumb.cognote.plugin.*;
import dumb.cognote.tool.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static dumb.cognote.Log.error;
import static dumb.cognote.Log.message;
import static dumb.cognote.Logic.Cognition;
import static dumb.cognote.Logic.RetractionType;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;

public class CogNote extends Cog {

    private final ConcurrentMap<String, Note> notes = new ConcurrentHashMap<>();

    public CogNote() {
        // Initialize executors and events first
        // mainExecutor is initialized in Cog constructor
        // events is initialized in Cog constructor, using mainExecutor
        // dialogueManager is initialized in Cog constructor, using mainExecutor

        // Load notes and config
        load();

        // Initialize TMS and OperatorRegistry
        var tms = new Truths.BasicTMS(events);
        var operatorRegistry = new Op.Operators();

        // Initialize Cognition context
        // Pass dialogueManager to Cognition context
        this.context = new Cognition(globalKbCapacity, events, tms, operatorRegistry, this);

        // Initialize ReasonerManager and Plugins
        // Pass dialogueManager to ReasonerManager
        this.reasonerManager = new Reason.ReasonerManager(events, context, dialogueManager);
        this.plugins = new Plugins(events, context);

        // Setup default plugins, reasoners, and tools
        setupDefaultPlugins();

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
    }

    public static void main(String[] args) {
        String rulesFile = null;
        int port = 8080;

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
            var c = new CogNote();
            // WebSocketPlugin needs the port, so it's registered here after parsing args
            c.plugins.loadPlugin(new dumb.cognote.plugin.WebSocketPlugin(new java.net.InetSocketAddress(port), c));

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
        System.err.printf("Usage: java %s [-p port] [-r rules_file.kif]%n", CogNote.class.getName());
        System.err.println("Note: Most configuration is now managed via the Configuration note and persisted in " + NOTES_FILE);
        System.exit(1);
    }

    private static synchronized void saveNotesToFile(List<Note> notes) {

        var toSave = notes.stream()
                .filter(note -> !note.id.equals(GLOBAL_KB_NOTE_ID)) // Don't save the global KB note itself
                .toList();

        // Ensure the config note is always saved, creating a default if necessary
        if (toSave.stream().noneMatch(n -> n.id.equals(CONFIG_NOTE_ID))) {
            toSave = new ArrayList<>(toSave); // Make mutable copy
            toSave.add(createDefaultConfigNote());
        }

        var jsonArray = new JSONArray();
        toSave.forEach(n -> jsonArray.put(new JSONObject()
                .put("id", n.id)
                .put("title", n.title)
                .put("text", n.text)
                .put("status", n.status.name())));
        try {
            Files.writeString(Paths.get(NOTES_FILE), jsonArray.toString(2));
            message("Notes saved to " + NOTES_FILE);
        } catch (IOException e) {
            error("Error saving notes to " + NOTES_FILE + ": " + e.getMessage());
        }
    }

    private static List<Note> loadNotesFromFile() {
        var path = Paths.get(NOTES_FILE);
        if (!Files.exists(path) || !Files.isReadable(path)) {
            message("Notes file not found or not readable: " + NOTES_FILE + ". Starting with defaults.");
            return new ArrayList<>();
        }
        try {
            var jsonText = Files.readString(path);
            var jsonArray = new JSONArray(new JSONTokener(jsonText));
            return IntStream.range(0, jsonArray.length())
                    .mapToObj(jsonArray::getJSONObject)
                    .map(json -> new Note(
                            json.getString("id"),
                            json.getString("title"),
                            json.optString("text", ""), // Handle missing text field
                            Note.Status.valueOf(json.optString("status", Note.Status.IDLE.name())) // Handle missing status
                    ))
                    .collect(Collectors.toList());
        } catch (IOException | org.json.JSONException e) {
            error("Error loading notes from " + NOTES_FILE + ": " + e.getMessage());
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    @Override
    protected void setupDefaultPlugins() {
        // Add core plugins
        plugins.loadPlugin(new InputPlugin());
        plugins.loadPlugin(new RetractionPlugin());
        plugins.loadPlugin(new TmsPlugin());
        plugins.loadPlugin(new UserFeedbackPlugin()); // Register the new feedback plugin

        // Add task/goal plugins
        plugins.loadPlugin(new TaskDecomposePlugin());

        // Add UI/Protocol plugins (WebSocketPlugin is registered in main)
        plugins.loadPlugin(new StatusUpdaterPlugin());

        // Add reasoner plugins
        reasonerManager.loadPlugin(new Reason.ForwardChainingReasonerPlugin());
        reasonerManager.loadPlugin(new Reason.RewriteRuleReasonerPlugin());
        reasonerManager.loadPlugin(new Reason.UniversalInstantiationReasonerPlugin());
        reasonerManager.loadPlugin(new Reason.BackwardChainingReasonerPlugin());

        // Add tools
        tools.register(new AssertKIFTool(this));
        tools.register(new GetNoteTextTool(this));
        tools.register(new FindAssertionsTool(this));
        tools.register(new RetractTool(this));
        tools.register(new QueryTool(this));
        tools.register(new LogMessageTool(this)); // Pass cog to LogMessageTool

        // Add LLM-based tools
        tools.register(new SummarizeTool(this));
        tools.register(new IdentifyConceptsTool(this));
        tools.register(new GenerateQuestionsTool(this));
        tools.register(new TextToKifTool(this));
        tools.register(new DecomposeGoalTool(this));
    }


    public Optional<Note> note(String id) {
        return ofNullable(notes.get(id));
    }

    public List<Note> getAllNotes() {
        return new ArrayList<>(notes.values());
    }

    public void addNote(Note note) {
        if (notes.putIfAbsent(note.id, note) == null) {
            events.emit(new AddedEvent(note));
            save();
            message("Added note: " + note.title + " [" + note.id + "]");
            // Signal UI to update note list
            assertUiAction(ProtocolConstants.UI_ACTION_UPDATE_NOTE_LIST, new JSONObject());
        } else {
            message("Note with ID " + note.id + " already exists.");
        }
    }

    public void removeNote(String noteId) {
        if ((noteId.equals(GLOBAL_KB_NOTE_ID) || noteId.equals(CONFIG_NOTE_ID))) {
            error("Attempted to remove system note: " + noteId + ". Operation ignored.");
            return;
        }

        ofNullable(notes.remove(noteId)).ifPresent(note -> {
            events.emit(new RetractionRequestEvent(noteId, Logic.RetractionType.BY_NOTE, "CogNote-Remove", noteId));
            events.emit(new RemovedEvent(note));
            context.removeActiveNote(noteId);
            save();
            message("Removed note: " + note.title + " [" + note.id + "]");
            // Signal UI to update note list
            assertUiAction(ProtocolConstants.UI_ACTION_UPDATE_NOTE_LIST, new JSONObject());
        });
    }

    public void updateNoteStatus(String noteId, Note.Status newStatus) {
        ofNullable(notes.get(noteId)).ifPresent(note -> {
            if (note.status != newStatus) {
                var oldStatus = note.status;
                note.status = newStatus;

                switch (newStatus) {
                    case ACTIVE -> context.addActiveNote(noteId);
                    case IDLE, PAUSED, COMPLETED -> context.removeActiveNote(noteId);
                }

                events.emit(new NoteStatusEvent(note, oldStatus, newStatus));
                save();
                message("Updated note status for [" + note.id + "] to " + newStatus);

                if (newStatus == Note.Status.ACTIVE) {
                    // Re-assert existing content when note becomes active
                    // This ensures reasoners/plugins process the note's content
                    context.kb(noteId).getAllAssertions().forEach(assertion ->
                            events.emit(new ExternalInputEvent(assertion.kif(), "note-start:" + noteId, noteId))
                    );
                    context.rules().stream()
                            .filter(rule -> noteId.equals(rule.sourceNoteId()))
                            .forEach(rule -> events.emit(new ExternalInputEvent(rule.form(), "note-start:" + noteId, noteId)));
                }
            }
        });
    }

    public void startNote(String noteId) {
        ofNullable(notes.get(noteId)).ifPresent(note -> {
            if (note.status == Note.Status.IDLE || note.status == Note.Status.PAUSED) {
                message("Starting note: " + note.title + " [" + note.id + "]");
                updateNoteStatus(noteId, Note.Status.ACTIVE);
            } else {
                message("Note " + note.title + " [" + note.id + "] is already " + note.status + ". Cannot start.");
            }
        });
    }

    public void pauseNote(String noteId) {
        ofNullable(notes.get(noteId)).ifPresent(note -> {
            if (note.status == Note.Status.ACTIVE) {
                message("Pausing note: " + note.title + " [" + note.id + "]");
                updateNoteStatus(noteId, Note.Status.PAUSED);
            } else {
                message("Note " + note.title + " [" + note.id + "] is not ACTIVE. Cannot pause.");
            }
        });
    }

    public void completeNote(String noteId) {
        ofNullable(notes.get(noteId)).ifPresent(note -> {
            if (note.status == Note.Status.ACTIVE || note.status == Note.Status.PAUSED) {
                message("Completing note: " + note.title + " [" + note.id + "]");
                updateNoteStatus(noteId, Note.Status.COMPLETED);
            } else {
                message("Note " + note.title + " [" + note.id + "] is already " + note.status + ". Cannot complete.");
            }
        });
    }

    public void updateNoteText(String noteId, String newText) {
        ofNullable(notes.get(noteId)).ifPresent(note -> {
            if (!note.text.equals(newText)) {
                note.text = newText;
                save();
                message("Updated text for note [" + note.id + "]");
            }
        });
    }

    public void updateNoteTitle(String noteId, String newTitle) {
        ofNullable(notes.get(noteId)).ifPresent(note -> {
            if (!note.title.equals(newTitle)) {
                note.title = newTitle;
                save();
                message("Updated title for note [" + note.id + "]");
            }
        });
    }

    public synchronized void clear() {
        message("Clearing all knowledge...");
        setPaused(true);
        // Retract from all KBs except system ones
        context.getAllNoteIds().stream()
                .filter(noteId -> !noteId.equals(GLOBAL_KB_NOTE_ID) && !noteId.equals(CONFIG_NOTE_ID))
                .forEach(noteId -> events.emit(new RetractionRequestEvent(noteId, Logic.RetractionType.BY_NOTE, "System-ClearAll", noteId)));
        // Retract from Global KB
        context.kbGlobal().getAllAssertionIds().forEach(id -> context.truth.remove(id, "System-ClearAll"));
        // Clear rules
        new HashSet<>(context.rules()).forEach(rule -> context.removeRule(rule));

        // Clear internal context state
        context.clear();

        // Reset notes to default/loaded state
        var configNote = notes.get(CONFIG_NOTE_ID);
        var globalKbNote = notes.get(GLOBAL_KB_NOTE_ID);

        notes.clear();
        notes.put(CONFIG_NOTE_ID, configNote != null ? configNote.withStatus(Note.Status.IDLE) : createDefaultConfigNote());
        notes.put(GLOBAL_KB_NOTE_ID, globalKbNote != null ? globalKbNote.withStatus(Note.Status.IDLE) : new Note(GLOBAL_KB_NOTE_ID, GLOBAL_KB_NOTE_TITLE, "Global KB assertions.", Note.Status.IDLE));

        // Re-add system notes to active context
        context.addActiveNote(GLOBAL_KB_NOTE_ID);
        // CONFIG_NOTE is not typically active for reasoning, but keep it in notes map

        // Emit events for the re-added system notes
        events.emit(new AddedEvent(notes.get(GLOBAL_KB_NOTE_ID)));
        events.emit(new AddedEvent(notes.get(CONFIG_NOTE_ID)));

        save();

        status("Cleared");
        setPaused(false);
        message("Knowledge cleared.");
        // Emit status event reflecting the cleared state
        events.emit(new SystemStatusEvent(status, context.kbCount(), globalKbCapacity, lm.activeLlmTasks.size(), context.ruleCount()));
        // Signal UI to update note list
        assertUiAction(ProtocolConstants.UI_ACTION_UPDATE_NOTE_LIST, new JSONObject());
    }

    public boolean updateConfig(String newConfigJsonText) {
        try {
            var newConfigJson = new JSONObject(new JSONTokener(newConfigJsonText));
            parseConfig(newConfigJsonText);
            note(CONFIG_NOTE_ID).ifPresent(note -> {
                note.text = newConfigJson.toString(2);
                save();
                message("Configuration updated and saved.");
            });
            // Reconfigure LM immediately after config update
            lm.reconfigure();
            return true;
        } catch (org.json.JSONException e) {
            error("Failed to parse new configuration JSON: " + e.getMessage());
            return false;
        }
    }

    private void load() {
        var loadedNotes = loadNotesFromFile();
        loadedNotes.forEach(note -> {
            notes.put(note.id, note);
            if (note.status == Note.Status.ACTIVE) {
                // Notes loaded as ACTIVE will be added to active context during start()
                // after plugins are initialized.
            }
        });

        var configNoteOpt = note(CONFIG_NOTE_ID);
        if (configNoteOpt.isPresent()) {
            parseConfig(configNoteOpt.get().text);
        } else {
            message("Configuration note not found, using defaults and creating one.");
            var configNote = createDefaultConfigNote();
            notes.put(CONFIG_NOTE_ID, configNote);
            parseConfig(configNote.text);
            save();
        }

        if (!notes.containsKey(GLOBAL_KB_NOTE_ID)) {
            notes.put(GLOBAL_KB_NOTE_ID, new Note(GLOBAL_KB_NOTE_ID, GLOBAL_KB_NOTE_TITLE, "Global KB Assertions", Note.Status.IDLE));
        }
        // Global KB is always active
        // context.addActiveNote(GLOBAL_KB_NOTE_ID); // This happens in start()
    }

    private void parseConfig(String jsonText) {
        try {
            var configJson = new JSONObject(new JSONTokener(jsonText));
            this.lm.llmApiUrl = configJson.optString("llmApiUrl", LM.DEFAULT_LLM_URL);
            this.lm.llmModel = configJson.optString("llmModel", LM.DEFAULT_LLM_MODEL);
            this.globalKbCapacity = configJson.optInt("globalKbCapacity", DEFAULT_KB_CAPACITY);
            this.reasoningDepthLimit = configJson.optInt("reasoningDepthLimit", DEFAULT_REASONING_DEPTH);
            this.broadcastInputAssertions = configJson.optBoolean("broadcastInputAssertions", DEFAULT_BROADCAST_INPUT);
            message(String.format("System config loaded: KBSize=%d, BroadcastInput=%b, LLM_URL=%s, LLM_Model=%s, MaxDepth=%d",
                    globalKbCapacity, broadcastInputAssertions, lm.llmApiUrl, lm.llmModel, reasoningDepthLimit));
        } catch (Exception e) {
            error("Error parsing configuration JSON, using defaults: " + e.getMessage());
            this.lm.llmApiUrl = LM.DEFAULT_LLM_URL;
            this.lm.llmModel = LM.DEFAULT_LLM_MODEL;
            this.globalKbCapacity = DEFAULT_KB_CAPACITY;
            this.reasoningDepthLimit = DEFAULT_REASONING_DEPTH;
            this.broadcastInputAssertions = DEFAULT_BROADCAST_INPUT;
        }
        // LM reconfig happens in start() and updateConfig()
    }

    /**
     * Asserts a UI action into the dedicated KB for UI communication.
     *
     * @param actionType The type of UI action (e.g., ProtocolConstants.UI_ACTION_DISPLAY_MESSAGE).
     * @param actionData A JSONObject containing data for the UI action.
     */
    public void assertUiAction(String actionType, JSONObject actionData) {
        var uiActionTerm = new Term.Lst(
                Term.Atom.of(ProtocolConstants.PRED_UI_ACTION),
                Term.Atom.of(actionType),
                Term.Atom.of(actionData.toString()) // Store JSON string in an atom
        );

        var potentialAssertion = new Assertion.PotentialAssertion(
                uiActionTerm,
                Cog.INPUT_ASSERTION_BASE_PRIORITY, // Use a base priority
                java.util.Set.of(), // No justifications needed for a UI action
                "backend:ui-action", // Source
                false, false, false, // Not equality, not negated
                ProtocolConstants.KB_UI_ACTIONS, // Target KB for UI actions
                Logic.AssertionType.GROUND, // UI actions are ground facts
                List.of(), // No quantified variables
                0 // Derivation depth 0
        );
        context.tryCommit(potentialAssertion, "backend:ui-action");
    }


    @Override
    public void start() {
        if (!running.get()) {
            error("Cannot restart a stopped system.");
            return;
        }
        paused.set(true);
        status("Initializing");

        // Initialize built-in operators
        context.operators.addBuiltin();

        // Plugins and Reasoners are loaded in the constructor now
        // setupDefaultPlugins(); // Called in constructor

        // Initialize plugins and reasoners
        plugins.initializeAll();
        reasonerManager.initializeAll();

        // Reconfigure LM after plugins/tools are registered
        lm.reconfigure();

        // Add notes that were loaded as ACTIVE to the active context
        notes.values().stream()
                .filter(note -> note.status == Note.Status.ACTIVE)
                .forEach(note -> context.addActiveNote(note.id));

        // Ensure Global KB is active
        context.addActiveNote(GLOBAL_KB_NOTE_ID);


        status("Paused (Ready to Start)");
        message("System initialized and paused.");
    }


    @Override
    public void stop() {
        save();
        super.stop();
    }

    public void save() {
        saveNotesToFile(getAllNotes());
    }

    public record NoteStatusEvent(Note note, Note.Status oldStatus, Note.Status newStatus) implements NoteEvent {
        public NoteStatusEvent {
            requireNonNull(note);
            requireNonNull(oldStatus);
            requireNonNull(newStatus);
        }

        public JSONObject toJson() {
            return new JSONObject()
                    .put("type", "event")
                    .put("eventType", "NoteStatusEvent")
                    .put("eventData", new JSONObject()
                            .put("note", note.toJson())
                            .put("oldStatus", oldStatus.name())
                            .put("newStatus", newStatus.name()));
        }
    }
}
