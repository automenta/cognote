package dumb.cognote;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.Optional.ofNullable;

public class CogNote extends Cog {

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


        return notes;
    }

    private static synchronized void saveNotesToFile(List<Note> notes) {

        // Filter out system notes that are not persisted (only GLOBAL_KB_NOTE_ID for now)
        var toSave = notes.stream()
                .filter(note -> !note.id.equals(GLOBAL_KB_NOTE_ID))
                .toList();

        // Ensure config, test defs, and test results notes are included if they weren't in the filtered list (shouldn't happen if loaded correctly)
        if (toSave.stream().noneMatch(n -> n.id.equals(CONFIG_NOTE_ID))) {
            toSave.add(createDefaultConfigNote());
        }

        var jsonArray = new JSONArray();
        toSave.forEach(n -> jsonArray.put(new JSONObject()
                .put("id", n.id)
                .put("title", n.title)
                .put("text", n.text)
                .put("status", n.status.name()))); // Save status
        try {
            Files.writeString(Paths.get(NOTES_FILE), jsonArray.toString(2));
        } catch (IOException e) {
            System.err.println("Error saving notes to " + NOTES_FILE + ": " + e.getMessage());
        }
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
            save();
        }
    }

    public void removeNote(String noteId) {
        // Prevent removal of system notes (except temporary test KBs)
        if ((noteId.equals(GLOBAL_KB_NOTE_ID) || noteId.equals(CONFIG_NOTE_ID))) {
            System.err.println("Attempted to remove system note: " + noteId + ". Operation ignored.");
            return;
        }

        ofNullable(notes.remove(noteId)).ifPresent(note -> {
            // Trigger retraction of associated assertions via event
            events.emit(new RetractionRequestEvent(noteId, Logic.RetractionType.BY_NOTE, "CogNote-Remove", noteId));
            // Note: The RetractionPlugin handles removing the NoteKb and emitting RemovedEvent
            events.emit(new RemovedEvent(note)); // Emit RemovedEvent with the Note object
            context.removeActiveNote(noteId); // Ensure it's removed from active set
            save();
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
                save();
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
                .filter(noteId -> !noteId.equals(GLOBAL_KB_NOTE_ID) && !noteId.equals(CONFIG_NOTE_ID)) // Also exclude temporary test KBs
                .forEach(noteId -> events.emit(new RetractionRequestEvent(noteId, Logic.RetractionType.BY_NOTE, "UI-ClearAll", noteId)));
        // Retract assertions from the global KB
        context.kbGlobal().getAllAssertionIds().forEach(id -> context.truth.remove(id, "UI-ClearAll"));
        // Clear the logic context (KBs, rules, active notes)
        context.clear();

        // Clear internal note map, but keep system notes
        var configNote = notes.get(CONFIG_NOTE_ID);
        var globalKbNote = notes.get(GLOBAL_KB_NOTE_ID);

        notes.clear();
        notes.put(CONFIG_NOTE_ID, configNote != null ? configNote.withStatus(Note.Status.IDLE) : createDefaultConfigNote());
        notes.put(GLOBAL_KB_NOTE_ID, globalKbNote != null ? globalKbNote.withStatus(Note.Status.IDLE) : new Note(GLOBAL_KB_NOTE_ID, GLOBAL_KB_NOTE_TITLE, "Global KB assertions.", Note.Status.IDLE));


        // Ensure system notes are marked as active in context (Global KB is always active)
        context.addActiveNote(GLOBAL_KB_NOTE_ID);
        // Config and Test notes are not typically active for reasoning, so don't add them

        // Re-emit Added events for the system notes so UI can add them back if needed
        events.emit(new AddedEvent(notes.get(GLOBAL_KB_NOTE_ID)));
        events.emit(new AddedEvent(notes.get(CONFIG_NOTE_ID)));


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

    public record NoteStatusEvent(Note note, Note.Status oldStatus, Note.Status newStatus) implements NoteEvent {
    }
}
