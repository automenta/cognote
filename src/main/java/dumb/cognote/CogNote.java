package dumb.cognote;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dumb.cognote.plugin.*;
import dumb.cognote.tool.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.IntStream;

import static dumb.cognote.Log.error;
import static dumb.cognote.Log.message;
import static dumb.cognote.Logic.Cognition;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;

public class CogNote extends Cog {

    private static final String NOTES_FILE = "cognote_notes.json";
    public final Cognition context;
    public final LM lm;
    public final Dialogue dialogue;
    protected final Reason.ReasonerManager reasoner;
    final Plugins plugins;
    private final ConcurrentMap<String, Note> notes = new ConcurrentHashMap<>();

    public CogNote() {
        super();

        this.context = new Cognition(globalKbCapacity,
                new Truths.BasicTMS(events),
                this);

        this.dialogue = new Dialogue(this);
        this.reasoner = new Reason.ReasonerManager(events, context, dialogue);
        this.plugins = new Plugins(events, context);

        this.lm = new LM(this);

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));

        initPlugins();

        load();
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
            // Assuming WebSocketPlugin handles JSON parsing/serialization internally or uses a different mechanism
            // If it uses org.json, it would need refactoring too, but it's not in the provided summaries.
            // For now, we assume it's compatible or will be refactored separately.
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

        try {
            // Use Jackson to write the list of Note objects directly
            JsonUtil.getMapper().writeValue(Paths.get(NOTES_FILE).toFile(), toSave);
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
            // Use Jackson to read the list of Note objects directly
            return JsonUtil.getMapper().readValue(path.toFile(), new TypeReference<List<Note>>() {});
        } catch (IOException e) {
            error("Error loading notes from " + NOTES_FILE + ": " + e.getMessage());
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    protected void initPlugins() {
        plugins.add(new InputPlugin());
        plugins.add(new RetractionPlugin());
        plugins.add(new TmsPlugin());
        plugins.add(new UserFeedbackPlugin());

        plugins.add(new TaskDecomposePlugin());

        //plugins.add(new StatusUpdaterPlugin());

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

    @Override
    public void status(String status) {
        super.status(status);
        events.emit(new SystemStatusEvent(status, context.kbCount(), context.kbTotalCapacity(), lm.activeLlmTasks.size(), context.ruleCount()));
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
            assertUiAction(Protocol.UI_ACTION_UPDATE_NOTE_LIST, JsonUtil.getMapper().createObjectNode()); // Use Jackson ObjectNode
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
            assertUiAction(Protocol.UI_ACTION_UPDATE_NOTE_LIST, JsonUtil.getMapper().createObjectNode()); // Use Jackson ObjectNode
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
        new HashSet<>(context.rules()).forEach(context::removeRule);

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
        assertUiAction(Protocol.UI_ACTION_UPDATE_NOTE_LIST, JsonUtil.getMapper().createObjectNode()); // Use Jackson ObjectNode
    }

    public boolean updateConfig(String newConfigJsonText) {
        try {
            // Use Jackson to read the config from JSON string
            var newConfig = JsonUtil.fromJsonString(newConfigJsonText, Configuration.class);
            applyConfig(newConfig);
            note(CONFIG_NOTE_ID).ifPresent(note -> {
                // Use Jackson to write the config back to JSON string (pretty printed)
                note.text = JsonUtil.toJsonString(newConfig);
                save();
                message("Configuration updated and saved.");
            });
            // Reconfigure LM immediately after config update
            lm.reconfigure();
            return true;
        } catch (Exception e) { // Catch JsonProcessingException and others
            error("Failed to parse or apply new configuration JSON: " + e.getMessage());
            e.printStackTrace();
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
        Configuration config;
        if (configNoteOpt.isPresent()) {
            try {
                // Use Jackson to parse config from note text
                config = JsonUtil.fromJsonString(configNoteOpt.get().text, Configuration.class);
                message("Configuration note found and parsed.");
            } catch (Exception e) { // Catch JsonProcessingException
                error("Error parsing configuration note, using defaults and creating a new one: " + e.getMessage());
                e.printStackTrace();
                config = new Configuration(); // Default config
                var configNote = createDefaultConfigNote();
                notes.put(CONFIG_NOTE_ID, configNote);
                save(); // Save the new default config note
            }
        } else {
            message("Configuration note not found, using defaults and creating one.");
            config = new Configuration(); // Default config
            var configNote = createDefaultConfigNote();
            notes.put(CONFIG_NOTE_ID, configNote);
            save(); // Save the new default config note
        }
        applyConfig(config);


        if (!notes.containsKey(GLOBAL_KB_NOTE_ID)) {
            notes.put(GLOBAL_KB_NOTE_ID, new Note(GLOBAL_KB_NOTE_ID, GLOBAL_KB_NOTE_TITLE, "Global KB Assertions", Note.Status.IDLE));
        }
        // Global KB is always active
        // context.addActiveNote(GLOBAL_KB_NOTE_ID); // This happens in start()
    }

    private void applyConfig(Configuration config) {
        this.lm.llmApiUrl = config.llmApiUrl();
        this.lm.llmModel = config.llmModel();
        this.globalKbCapacity = config.globalKbCapacity();
        this.reasoningDepthLimit = config.reasoningDepthLimit();
        this.broadcastInputAssertions = config.broadcastInputAssertions();
        message(String.format("System config applied: KBSize=%d, BroadcastInput=%b, LLM_URL=%s, LLM_Model=%s, MaxDepth=%d",
                globalKbCapacity, broadcastInputAssertions, lm.llmApiUrl, lm.llmModel, reasoningDepthLimit));
        // LM reconfig happens in start() and updateConfig()
    }

    /**
     * Asserts a UI action into the dedicated KB for UI communication.
     *
     * @param actionType The type of UI action (e.g., ProtocolConstants.UI_ACTION_DISPLAY_MESSAGE).
     * @param actionData A JsonNode containing data for the UI action.
     */
    public void assertUiAction(String actionType, JsonNode actionData) { // Use JsonNode
        // Create the UI action term using the provided JsonNode data
        var uiActionTerm = new Term.Lst(
                Term.Atom.of(Protocol.PRED_UI_ACTION),
                Term.Atom.of(actionType),
                Term.Atom.of(JsonUtil.toJsonString(actionData)) // Store JSON string in an atom
        );

        context.tryCommit(new Assertion.PotentialAssertion(
                uiActionTerm,
                Cog.INPUT_ASSERTION_BASE_PRIORITY, // Use a base priority
                java.util.Set.of(), // No justifications needed for a UI action
                "backend:ui-action", // Source
                false, false, false, // Not equality, not negated
                Protocol.KB_UI_ACTIONS, // Target KB for UI actions
                Logic.AssertionType.GROUND, // UI actions are ground facts
                List.of(), // No quantified variables
                0 // Derivation depth 0
        ), "backend:ui-action");
    }


    @Override
    public void start() {
        super.start();

        lm.reconfigure();

        plugins.initializeAll();

        reasoner.initializeAll();

        // Add notes that were loaded as ACTIVE to the active context
        notes.values().stream()
                .filter(note -> note.status == Note.Status.ACTIVE)
                .forEach(note -> context.addActiveNote(note.id));

        context.addActiveNote(GLOBAL_KB_NOTE_ID); // Ensure Global KB is active
    }

    @Override
    public void stop() {

        dialogue.clear();

        reasoner.shutdownAll();

        lm.activeLlmTasks.values().forEach(f -> f.cancel(true));
        lm.activeLlmTasks.clear();

        save();

        plugins.shutdownAll();

        super.stop();
    }

    public void save() {
        saveNotesToFile(getAllNotes());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NoteStatusEvent(Note note, Note.Status oldStatus, Note.Status newStatus) implements NoteEvent {
        public NoteStatusEvent {
            requireNonNull(note);
            requireNonNull(oldStatus);
            requireNonNull(newStatus);
        }

        public JsonNode toJson() {
            return JsonUtil.toJsonNode(this);
        }

        @Override
        public String getEventType() {
            return "NoteStatusEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Configuration(
            @JsonProperty("llmApiUrl") String llmApiUrl,
            @JsonProperty("llmModel") String llmModel,
            @JsonProperty("globalKbCapacity") int globalKbCapacity,
            @JsonProperty("reasoningDepthLimit") int reasoningDepthLimit,
            @JsonProperty("broadcastInputAssertions") boolean broadcastInputAssertions
    ) {
        // Default constructor for Jackson deserialization when fields are not present
        @JsonCreator
        public Configuration(
                @JsonProperty("llmApiUrl") String llmApiUrl,
                @JsonProperty("llmModel") String llmModel,
                @JsonProperty("globalKbCapacity") Integer globalKbCapacity, // Use Integer for nullable default
                @JsonProperty("reasoningDepthLimit") Integer reasoningDepthLimit, // Use Integer for nullable default
                @JsonProperty("broadcastInputAssertions") Boolean broadcastInputAssertions // Use Boolean for nullable default
        ) {
            this(
                    llmApiUrl != null ? llmApiUrl : LM.DEFAULT_LLM_URL,
                    llmModel != null ? llmModel : LM.DEFAULT_LLM_MODEL,
                    globalKbCapacity != null ? globalKbCapacity : DEFAULT_KB_CAPACITY,
                    reasoningDepthLimit != null ? reasoningDepthLimit : DEFAULT_REASONING_DEPTH,
                    broadcastInputAssertions != null ? broadcastInputAssertions : DEFAULT_BROADCAST_INPUT
            );
        }

        // Constructor for creating default config programmatically
        public Configuration() {
            this(LM.DEFAULT_LLM_URL, LM.DEFAULT_LLM_MODEL, DEFAULT_KB_CAPACITY, DEFAULT_REASONING_DEPTH, DEFAULT_BROADCAST_INPUT);
        }

        // Private constructor for the main record fields
        public Configuration(String llmApiUrl, String llmModel, int globalKbCapacity, int reasoningDepthLimit, boolean broadcastInputAssertions) {
            this.llmApiUrl = llmApiUrl;
            this.llmModel = llmModel;
            this.globalKbCapacity = globalKbCapacity;
            this.reasoningDepthLimit = reasoningDepthLimit;
            this.broadcastInputAssertions = broadcastInputAssertions;
        }

        public Configuration(CogNote cog) {
            this();
            //TODO
        }
    }
}
