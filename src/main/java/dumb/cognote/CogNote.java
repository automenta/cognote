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

public class CogNote extends Cog {

    private final ConcurrentMap<String, Note> notes = new ConcurrentHashMap<>();

    public CogNote() {
        load();
    }

    static List<Note> loadNotesFromFile() {
        var filePath = Paths.get(NOTES_FILE);
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
            notesToSave.add(createDefaultConfigNote());
        }

        notesToSave.forEach(note -> jsonArray.put(new JSONObject()
                .put("id", note.id)
                .put("title", note.title)
                .put("text", note.text)));
        try {
            Files.writeString(filePath, jsonArray.toString(2));
        } catch (IOException e) {
            System.err.println("Error saving notes to " + NOTES_FILE + ": " + e.getMessage());
        }
    }

    @Override
    public Optional<Note> note(String id) {
        return Optional.ofNullable(notes.get(id));
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
        Optional.ofNullable(notes.remove(noteId)).ifPresent(note -> {
            // Trigger retraction of associated assertions via event
            events.emit(new RetractionRequestEvent(noteId, Logic.RetractionType.BY_NOTE, "CogNote-Remove", noteId));
            // Note: The RetractionPlugin handles removing the NoteKb and emitting RemovedEvent
            save();
        });
    }

    @Override
    public synchronized void clear() {
        super.clear();
        notes.clear(); // Clear internal map after triggering retractions
        // Add default notes back
        var globalKbNote = new Note(GLOBAL_KB_NOTE_ID, GLOBAL_KB_NOTE_TITLE, "Global KB Assertions");
        var configNote = createDefaultConfigNote();
        notes.put(GLOBAL_KB_NOTE_ID, globalKbNote);
        notes.put(CONFIG_NOTE_ID, configNote);
        events.emit(new AddedEvent(globalKbNote));
        events.emit(new AddedEvent(configNote));
        save();
    }

    public boolean updateConfig(String newConfigJsonText) {
        try {
            var newConfigJson = new JSONObject(new JSONTokener(newConfigJsonText));
            parseConfig(newConfigJsonText);
            note(CONFIG_NOTE_ID).ifPresent(note -> {
                note.text = newConfigJson.toString(2);
                save();
            });
            return true;
        } catch (org.json.JSONException e) {
            System.err.println("Failed to parse new configuration JSON: " + e.getMessage());
            return false;
        }
    }

    private void load() {
        var loadedNotes = loadNotesFromFile();
        loadedNotes.forEach(note -> notes.put(note.id, note));

        var configNoteOpt = note(CONFIG_NOTE_ID);
        if (configNoteOpt.isPresent()) {
            parseConfig(configNoteOpt.get().text);
        } else {
            System.out.println("Configuration note not found, using defaults and creating one.");
            var configNote = createDefaultConfigNote();
            notes.put(CONFIG_NOTE_ID, configNote);
            parseConfig(configNote.text);
            save();
        }

        // Ensure Global KB note exists internally, even if not loaded from file
        if (!notes.containsKey(GLOBAL_KB_NOTE_ID)) {
            notes.put(GLOBAL_KB_NOTE_ID, new Note(GLOBAL_KB_NOTE_ID, GLOBAL_KB_NOTE_TITLE, "Global KB Assertions"));
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
        save();
        super.stop();
    }

    public void save() {
        saveNotesToFile(getAllNotes());
    }

}
