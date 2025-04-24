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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class CogNote extends Cog {

    @Deprecated
    public UI ui;

    public CogNote() {
        load();
    }

    static List<Note> loadNotes() {
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

    private static synchronized void save(List<Note> notes) {
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
        return ui.findNoteById(id);
    }

    @Override
    public synchronized void clear() {
        super.clear();

        SwingUtilities.invokeLater(() -> {
            ui.clearAllUILists();
            ui.addNoteToList(new Note(GLOBAL_KB_NOTE_ID, GLOBAL_KB_NOTE_TITLE, "Global KB Assertions"));
            if (ui.findNoteById(CONFIG_NOTE_ID).isEmpty()) {
                ui.addNoteToList(createDefaultConfigNote());
            }
            ui.noteListPanel.noteList.setSelectedIndex(0);
        });
    }

    public boolean updateConfig(String newConfigJsonText) {
        try {
            var newConfigJson = new JSONObject(new JSONTokener(newConfigJsonText));
            parseConfig(newConfigJsonText);
            ui.findNoteById(CONFIG_NOTE_ID).ifPresent(note -> {
                note.text = newConfigJson.toString(2);
                save();
            });
//                System.out.println("Configuration updated and saved.");
//                System.out.printf("New Config: KBSize=%d, LLM_URL=%s, LLM_Model=%s, MaxDepth=%d, BroadcastInput=%b%n",
//                        globalKbCapacity, lm.llmApiUrl, lm.llmModel, reasoningDepthLimit, broadcastInputAssertions);
            return true;
        } catch (org.json.JSONException e) {
            System.err.println("Failed to parse new configuration JSON: " + e.getMessage());
            return false;
        }
    }

    private void load() {
        var notes = loadNotes();

        var configNoteOpt = notes.stream().filter(n -> n.id.equals(CONFIG_NOTE_ID)).findFirst();
        if (configNoteOpt.isPresent()) {
            parseConfig(configNoteOpt.get().text);
        } else {
            System.out.println("Configuration note not found, using defaults and creating one.");
            var configNote = createDefaultConfigNote();
            notes.add(configNote);
            parseConfig(configNote.text);
            save(notes);
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
        save(ui.getAllNotes());
    }

}
