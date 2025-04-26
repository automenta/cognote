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

import static dumb.cognote.Log.error;
import static dumb.cognote.Log.message;
import static java.util.Optional.ofNullable;

public class CogNote extends Cog {

    private final ConcurrentMap<String, Note> notes = new ConcurrentHashMap<>();

    public CogNote() {
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
                .filter(note -> !note.id.equals(GLOBAL_KB_NOTE_ID))
                .toList();

        if (toSave.stream().noneMatch(n -> n.id.equals(CONFIG_NOTE_ID))) {
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
            message("Added note: " + note.title + " [" + note.id + "]");
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


    @Override
    public synchronized void clear() {
        message("Clearing all knowledge...");
        setPaused(true);
        context.getAllNoteIds().stream()
                .filter(noteId -> !noteId.equals(GLOBAL_KB_NOTE_ID) && !noteId.equals(CONFIG_NOTE_ID))
                .forEach(noteId -> events.emit(new RetractionRequestEvent(noteId, Logic.RetractionType.BY_NOTE, "System-ClearAll", noteId)));
        context.kbGlobal().getAllAssertionIds().forEach(id -> context.truth.remove(id, "System-ClearAll"));
        context.clear();

        var configNote = notes.get(CONFIG_NOTE_ID);
        var globalKbNote = notes.get(GLOBAL_KB_NOTE_ID);

        notes.clear();
        notes.put(CONFIG_NOTE_ID, configNote != null ? configNote.withStatus(Note.Status.IDLE) : createDefaultConfigNote());
        notes.put(GLOBAL_KB_NOTE_ID, globalKbNote != null ? globalKbNote.withStatus(Note.Status.IDLE) : new Note(GLOBAL_KB_NOTE_ID, GLOBAL_KB_NOTE_TITLE, "Global KB assertions.", Note.Status.IDLE));

        context.addActiveNote(GLOBAL_KB_NOTE_ID);

        events.emit(new AddedEvent(notes.get(GLOBAL_KB_NOTE_ID)));
        events.emit(new AddedEvent(notes.get(CONFIG_NOTE_ID)));

        save();

        status("Cleared");
        setPaused(false);
        message("Knowledge cleared.");
        events.emit(new SystemStatusEvent(status, 0, globalKbCapacity, 0, 0));
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
                context.addActiveNote(note.id);
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

    Logic.Cognition cogNoteContext() {
        return context;
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
