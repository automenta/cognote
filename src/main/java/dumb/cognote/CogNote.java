package dumb.cognote;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.Optional.ofNullable;

public class CogNote extends Cog {

    private static final String TEST_KB_PREFIX = "test-kb-"; // Define the prefix here
    private final ConcurrentMap<String, Note> notes = new ConcurrentHashMap<>();

    public CogNote() {
        // Notes are loaded in the constructor
        load();
    }



    static List<Note> loadNotesFromFile() {
        var filePath = Paths.get(NOTES_FILE);
        List<Note> notes = new ArrayList<>();

        if (Files.exists(filePath)) {
            try {
                var jsonText = Files.readString(filePath);
                var jsonArray = new JSONArray(new JSONTokener(jsonText));
                notes = IntStream.range(0, jsonArray.length())
                        .mapToObj(jsonArray::getJSONObject)
                        .map(obj -> {
                            var id = obj.getString("id");
                            var title = obj.getString("title");
                            var text = obj.getString("text");
                            // Load status, default to IDLE if not present (for backward compatibility)
                            var status = Note.Status.valueOf(obj.optString("status", Note.Status.IDLE.name()).toUpperCase());
                            return new Note(id, title, text, status);
                        })
                        .collect(Collectors.toCollection(ArrayList::new));

                System.out.println("Loaded " + notes.size() + " notes from " + NOTES_FILE);
            } catch (IOException | org.json.JSONException e) {
                System.err.println("Error loading notes from " + NOTES_FILE + ": " + e.getMessage() + ". Starting with default system notes.");
                notes.clear(); // Clear any partially loaded notes
            }
        } else {
            System.out.println("Notes file not found: " + NOTES_FILE + ". Starting with default system notes.");
        }

        // Ensure system notes exist (config, global KB, test defs, test results)
        // Global KB note is not saved, but needed internally
        // Config, Test Definitions, and Test Results notes *are* saved.
        // Ensure they are in the list if they weren't loaded.
        if (notes.stream().noneMatch(n -> n.id.equals(CONFIG_NOTE_ID))) {
            notes.add(createDefaultConfigNote());
        }
        // Global KB note is internal, not persisted in this file, but needed in the map
        if (notes.stream().noneMatch(n -> n.id.equals(GLOBAL_KB_NOTE_ID))) {
            notes.add(new Note(GLOBAL_KB_NOTE_ID, GLOBAL_KB_NOTE_TITLE, "Global KB assertions.", Note.Status.IDLE));
        }
        if (notes.stream().noneMatch(n -> n.id.equals(TEST_DEFINITIONS_NOTE_ID))) {
            notes.add(createDefaultTestDefinitionsNote());
        }
        if (notes.stream().noneMatch(n -> n.id.equals(TEST_RESULTS_NOTE_ID))) {
            notes.add(createDefaultTestResultsNote());
        }


        return notes;
    }

    private static synchronized void saveNotesToFile(List<Note> notes) {
        var filePath = Paths.get(NOTES_FILE);
        var jsonArray = new JSONArray();
        // Filter out system notes that are not persisted (only GLOBAL_KB_NOTE_ID for now)
        List<Note> notesToSave = notes.stream()
                .filter(note -> !note.id.equals(GLOBAL_KB_NOTE_ID))
                .collect(Collectors.toCollection(ArrayList::new));

        // Ensure config, test defs, and test results notes are included if they weren't in the filtered list (shouldn't happen if loaded correctly)
        if (notesToSave.stream().noneMatch(n -> n.id.equals(CONFIG_NOTE_ID))) {
            notesToSave.add(createDefaultConfigNote());
        }
        if (notesToSave.stream().noneMatch(n -> n.id.equals(TEST_DEFINITIONS_NOTE_ID))) {
            notesToSave.add(createDefaultTestDefinitionsNote());
        }
        if (notesToSave.stream().noneMatch(n -> n.id.equals(TEST_RESULTS_NOTE_ID))) {
            notesToSave.add(createDefaultTestResultsNote());
        }


        notesToSave.forEach(note -> jsonArray.put(new JSONObject()
                .put("id", note.id)
                .put("title", note.title)
                .put("text", note.text)
                .put("status", note.status.name()))); // Save status
        try {
            Files.writeString(filePath, jsonArray.toString(2));
        } catch (IOException e) {
            System.err.println("Error saving notes to " + NOTES_FILE + ": " + e.getMessage());
        }
    }

    static Note createDefaultTestDefinitionsNote() {
        return new Note(TEST_DEFINITIONS_NOTE_ID, TEST_DEFINITIONS_NOTE_TITLE,
                "; Define your tests here using the (test ...) format\n\n" +
                        "; Test structure: (test \"Test Name\" (setup ...) (action ...) (expected ...) (teardown ...))\n" +
                        "; setup/teardown actions: (assert KIF), (addRule RuleKIF), (retract (BY_ID \"id\")), (retract (BY_KIF KIF)), (removeRuleForm RuleKIF)\n" +
                        "; action types: (query Pattern), (runTool (name \"tool_name\") (params (key1 value1) ...))\n" +
                        "; expected types: (expectedResult boolean), (expectedBindings ((?V1 Val1) ...)), (expectedAssertionExists KIF), (expectedAssertionDoesNotExist KIF), (expectedRuleExists RuleKIF), (expectedRuleDoesNotExist RuleKIF), (expectedKbSize integer), (expectedToolResult value))\n\n" +

                        "; Example 1: Simple Fact Query\n" +
                        "(test \"Simple Fact Query\" \n" +
                        "  (setup (assert (instance MyCat Cat)))\n" +
                        "  (action (query (instance ?X Cat)))\n" +
                        "  (expected (expectedResult true) (expectedBindings ((?X MyCat))))\n" +
                        "  (teardown (retract (BY_KIF (instance MyCat Cat)))))\n\n" +

                        "; Example 2: Query with Multiple Bindings\n" +
                        "(test \"Query with Multiple Bindings\" \n" +
                        "  (setup \n" +
                        "    (assert (instance MyCat Cat))\n" +
                        "    (assert (instance YourCat Cat))\n" +
                        "    (assert (instance MyDog Dog)))\n" +
                        "  (action (query (instance ?X Cat)))\n" +
                        "  (expected \n" +
                        "    (expectedResult true)\n" +
                        "    ; Note: Order of bindings in expectedBindings list matters for now\n" +
                        "    (expectedBindings ((?X MyCat) (?X YourCat))))\n" +
                        "  (teardown \n" +
                        "    (retract (BY_KIF (instance MyCat Cat)))\n" +
                        "    (retract (BY_KIF (instance YourCat Cat)))\n" +
                        "    (retract (BY_KIF (instance MyDog Dog)))))\n\n" +

                        "; Example 3: Query that should fail\n" +
                        "(test \"Query Failure\" \n" +
                        "  (setup (assert (instance MyDog Dog)))\n" +
                        "  (action (query (instance MyDog Cat)))\n" +
                        "  (expected (expectedResult false) (expectedBindings ())))\n" +
                        "  (teardown (retract (BY_KIF (instance MyDog Dog)))))\n\n" +

                        "; Example 4: Test Forward Chaining Rule\n" +
                        "(test \"Forward Chaining Rule\" \n" +
                        "  (setup \n" +
                        "    (addRule (=> (instance ?X Dog) (attribute ?X Canine)))\n" +
                        "    (assert (instance MyDog Dog)))\n" +
                        "  (action (query (attribute MyDog Canine)))\n" +
                        "  (expected \n" +
                        "    (expectedResult true)\n" +
                        "    (expectedBindings ())\n" +
                        "    (expectedAssertionExists (attribute MyDog Canine)))\n" +
                        "  (teardown \n" +
                        "    (retract (BY_KIF (instance MyDog Dog)))\n" +
                        "    (retract (BY_KIF (attribute MyDog Canine)))\n" +
                        "    (removeRuleForm (=> (instance ?X Dog) (attribute ?X Canine)))))\n\n" +

                        "; Example 5: Test Retraction\n" +
                        "(test \"Retract Assertion\" \n" +
                        "  (setup (assert (instance TempFact Something)))\n" +
                        "  (action (retract (BY_KIF (instance TempFact Something))))\n" +
                        "  (expected (expectedAssertionDoesNotExist (instance TempFact Something))))\n" +
                        "  (teardown))\n\n" + // Teardown is empty, cleanup is automatic

                        "; Example 6: Test KB Size\n" +
                        "(test \"KB Size Check\" \n" +
                        "  (setup \n" +
                        "    (assert (fact1 a))\n" +
                        "    (assert (fact2 b)))\n" +
                        "  (action (assert (fact3 c)))\n" +
                        "  (expected (expectedKbSize 3))\n" +
                        "  (teardown \n" +
                        "    (retract (BY_KIF (fact1 a)))\n" +
                        "    (retract (BY_KIF (fact2 b)))\n" +
                        "    (retract (BY_KIF (fact3 c)))))\n\n" +

                        "; Example 7: Test runTool (LogMessageTool)\n" +
                        "(test \"Run LogMessageTool\" \n" +
                        "  (setup)\n" +
                        "  (action (runTool (name \"log_message\") (params (message \"Hello from test!\"))))\n" +
                        "  (expected (expectedToolResult \"Message logged.\")))\n" +
                        "  (teardown))\n\n" +

                        "; Example 8: Test runTool (GetNoteTextTool) - requires a note to exist\n" +
                        "; This test assumes the Test Definitions note itself exists and has text.\n" +
                        "; It runs the tool against the Test Definitions note KB.\n" +
                        "(test \"Run GetNoteTextTool\" \n" +
                        "  (setup)\n" +
                        "  (action (runTool (name \"get_note_text\") (params (note_id \"" + TEST_DEFINITIONS_NOTE_ID + "\"))))\n" +
                        "  (expected (expectedToolResult \"; Define your tests here using the (test ...) format\")))\n" + // Corrected expected result
                        "  (teardown))\n",
                Note.Status.IDLE);
    }

    @Override
    public Optional<Note> note(String id) {
        return ofNullable(notes.get(id));
    }

    public List<Note> getAllNotes() {
        return new ArrayList<>(notes.values());
    }

    public void addNote(Note note) {
        if (notes.putIfAbsent(note.id, note) == null) {
            events.emit(new AddedEvent(note));
            // Don't save temporary test KBs
            if (!note.id.startsWith(TEST_KB_PREFIX)) {
                save();
            }
        }
    }

    public void removeNote(String noteId) {
        // Prevent removal of system notes (except temporary test KBs)
        if ((noteId.equals(GLOBAL_KB_NOTE_ID) || noteId.equals(CONFIG_NOTE_ID) || noteId.equals(TEST_DEFINITIONS_NOTE_ID) || noteId.equals(TEST_RESULTS_NOTE_ID))) { // Allow removing temporary test KBs even if they match system ID pattern
            System.err.println("Attempted to remove system note: " + noteId + ". Operation ignored.");
            return;
        }

        ofNullable(notes.remove(noteId)).ifPresent(note -> {
            // Trigger retraction of associated assertions via event
            events.emit(new RetractionRequestEvent(noteId, Logic.RetractionType.BY_NOTE, "CogNote-Remove", noteId));
            // Note: The RetractionPlugin handles removing the NoteKb and emitting RemovedEvent
            events.emit(new RemovedEvent(note)); // Emit RemovedEvent with the Note object
            context.removeActiveNote(noteId); // Ensure it's removed from active set
            // Don't save temporary test KBs
            if (!note.id.startsWith(TEST_KB_PREFIX)) {
                save();
            }
        });
    }

    public void updateNoteStatus(String noteId, Note.Status newStatus) {
        ofNullable(notes.get(noteId)).ifPresent(note -> {
            if (note.status != newStatus) {
                var oldStatus = note.status;
                note.status = newStatus; // Update status in the internal map

                switch (newStatus) {
                    case ACTIVE -> context.addActiveNote(noteId);
                    case IDLE, PAUSED, COMPLETED -> context.removeActiveNote(noteId);
                }

                events.emit(new NoteStatusEvent(note, oldStatus, newStatus)); // Emit event
                // Don't save temporary test KBs status
                if (!note.id.startsWith(TEST_KB_PREFIX)) {
                    save();
                }
            }
        });
    }

    public void startNote(String noteId) {
        ofNullable(notes.get(noteId)).ifPresent(note -> {
            if (note.status == Note.Status.IDLE || note.status == Note.Status.PAUSED) {
                System.out.println("Starting note: " + note.title + " [" + note.id + "]");
                updateNoteStatus(noteId, Note.Status.ACTIVE);
                // Re-emit assertions to kick off reasoning
                context.kb(noteId).getAllAssertions().forEach(assertion ->
                        events.emit(new ExternalInputEvent(assertion.kif(), "note-start:" + noteId, noteId))
                );
                // Re-emit rules associated with this note
                context.rules().stream()
                        .filter(rule -> noteId.equals(rule.sourceNoteId()))
                        .forEach(rule -> events.emit(new ExternalInputEvent(rule.form(), "note-start:" + noteId, noteId)));

            } else {
                System.out.println("Note " + note.title + " [" + note.id + "] is already " + note.status + ". Cannot start.");
            }
        });
    }

    public void pauseNote(String noteId) {
        ofNullable(notes.get(noteId)).ifPresent(note -> {
            if (note.status == Note.Status.ACTIVE) {
                System.out.println("Pausing note: " + note.title + " [" + note.id + "]");
                updateNoteStatus(noteId, Note.Status.PAUSED);
            } else {
                System.out.println("Note " + note.title + " [" + note.id + "] is not ACTIVE. Cannot pause.");
            }
        });
    }

    public void completeNote(String noteId) {
        ofNullable(notes.get(noteId)).ifPresent(note -> {
            if (note.status == Note.Status.ACTIVE || note.status == Note.Status.PAUSED) {
                System.out.println("Completing note: " + note.title + " [" + note.id + "]");
                updateNoteStatus(noteId, Note.Status.COMPLETED);
            } else {
                System.out.println("Note " + note.title + " [" + note.id + "] is already " + note.status + ". Cannot complete.");
            }
        });
    }

    @Override
    public synchronized void clear() {
        System.out.println("Clearing all knowledge...");
        setPaused(true);
        // Retract assertions from all notes except config, global, and test notes
        context.getAllNoteIds().stream()
                .filter(noteId -> !noteId.equals(GLOBAL_KB_NOTE_ID) && !noteId.equals(CONFIG_NOTE_ID) && !noteId.equals(TEST_DEFINITIONS_NOTE_ID) && !noteId.equals(TEST_RESULTS_NOTE_ID) && !noteId.startsWith(TEST_KB_PREFIX)) // Also exclude temporary test KBs
                .forEach(noteId -> events.emit(new RetractionRequestEvent(noteId, Logic.RetractionType.BY_NOTE, "UI-ClearAll", noteId)));
        // Retract assertions from the global KB
        context.kbGlobal().getAllAssertionIds().forEach(id -> context.truth.remove(id, "UI-ClearAll"));
        // Clear the logic context (KBs, rules, active notes)
        context.clear();

        // Clear internal note map, but keep system notes
        var configNote = notes.get(CONFIG_NOTE_ID);
        var globalKbNote = notes.get(GLOBAL_KB_NOTE_ID);
        var testDefsNote = notes.get(TEST_DEFINITIONS_NOTE_ID);
        var testResultsNote = notes.get(TEST_RESULTS_NOTE_ID);

        notes.clear();
        notes.put(CONFIG_NOTE_ID, configNote != null ? configNote.withStatus(Note.Status.IDLE) : createDefaultConfigNote());
        notes.put(GLOBAL_KB_NOTE_ID, globalKbNote != null ? globalKbNote.withStatus(Note.Status.IDLE) : new Note(GLOBAL_KB_NOTE_ID, GLOBAL_KB_NOTE_TITLE, "Global KB assertions.", Note.Status.IDLE));
        notes.put(TEST_DEFINITIONS_NOTE_ID, testDefsNote != null ? testDefsNote.withStatus(Note.Status.IDLE) : createDefaultTestDefinitionsNote());
        notes.put(TEST_RESULTS_NOTE_ID, testResultsNote != null ? testResultsNote.withStatus(Note.Status.IDLE) : createDefaultTestResultsNote());


        // Ensure system notes are marked as active in context (Global KB is always active)
        context.addActiveNote(GLOBAL_KB_NOTE_ID);
        // Config and Test notes are not typically active for reasoning, so don't add them

        // Re-emit Added events for the system notes so UI can add them back if needed
        events.emit(new AddedEvent(notes.get(GLOBAL_KB_NOTE_ID)));
        events.emit(new AddedEvent(notes.get(CONFIG_NOTE_ID)));
        events.emit(new AddedEvent(notes.get(TEST_DEFINITIONS_NOTE_ID)));
        events.emit(new AddedEvent(notes.get(TEST_RESULTS_NOTE_ID)));


        save(); // Save the cleared state (only system notes remain)

        status("Cleared");
        setPaused(false); // Unpause after clearing
        System.out.println("Knowledge cleared.");
        events.emit(new SystemStatusEvent(status, 0, globalKbCapacity, 0, 0));
    }

    public boolean updateConfig(String newConfigJsonText) {
        try {
            var newConfigJson = new JSONObject(new JSONTokener(newConfigJsonText));
            parseConfig(newConfigJsonText);
            note(CONFIG_NOTE_ID).ifPresent(note -> {
                note.text = newConfigJson.toString(2);
                // Status is not changed by config update
                save(); // Save config note text
            });
            return true;
        } catch (org.json.JSONException e) {
            System.err.println("Failed to parse new configuration JSON: " + e.getMessage());
            return false;
        }
    }

    private void load() {
        var loadedNotes = loadNotesFromFile();
        loadedNotes.forEach(note -> {
            notes.put(note.id, note);
            if (note.status == Note.Status.ACTIVE) {
                context.addActiveNote(note.id);
            }
        });

        // Ensure config note exists and parse it
        var configNoteOpt = note(CONFIG_NOTE_ID);
        if (configNoteOpt.isPresent()) {
            parseConfig(configNoteOpt.get().text);
        } else {
            System.out.println("Configuration note not found, using defaults and creating one.");
            var configNote = createDefaultConfigNote();
            notes.put(CONFIG_NOTE_ID, configNote);
            parseConfig(configNote.text);
            save(); // Save the newly created config note
        }

        // Ensure Global KB note exists internally, even if not loaded from file
        if (!notes.containsKey(GLOBAL_KB_NOTE_ID)) {
            notes.put(GLOBAL_KB_NOTE_ID, new Note(GLOBAL_KB_NOTE_ID, GLOBAL_KB_NOTE_TITLE, "Global KB Assertions", Note.Status.IDLE));
        }
        // Ensure Global KB is always active in context
        context.addActiveNote(GLOBAL_KB_NOTE_ID);

        // Ensure Test Definition note exists internally
        if (!notes.containsKey(TEST_DEFINITIONS_NOTE_ID)) {
            notes.put(TEST_DEFINITIONS_NOTE_ID, createDefaultTestDefinitionsNote());
        }

        // Ensure Test Results note exists internally
        if (!notes.containsKey(TEST_RESULTS_NOTE_ID)) {
            notes.put(TEST_RESULTS_NOTE_ID, createDefaultTestResultsNote());
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
        lm.reconfigure();
    }

    @Override
    public void stop() {
        save(); // Save notes before stopping
        super.stop();
    }

    public void save() {
        saveNotesToFile(getAllNotes());
    }

    // Provide access to the CogNote context for plugins/reasoners that extend BaseReasonerPlugin
    Logic.Cognition cogNoteContext() {
        return context;
    }
}
