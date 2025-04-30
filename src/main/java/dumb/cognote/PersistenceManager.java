package dumb.cognote;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;

import static dumb.cognote.Log.error;
import static dumb.cognote.Log.message;
import static java.util.Objects.requireNonNull;

public class PersistenceManager {

    private final Cog cog;
    private final Logic.Cognition context;
    private final Truths.BasicTMS tms;
    private final ObjectMapper mapper;

    public PersistenceManager(Cog cog, Logic.Cognition context, Truths truths) {
        this.cog = requireNonNull(cog);
        this.context = requireNonNull(context);
        if (!(truths instanceof Truths.BasicTMS)) {
            throw new IllegalArgumentException("PersistenceManager requires BasicTMS implementation");
        }
        this.tms = (Truths.BasicTMS) truths;
        this.mapper = Json.the;
    }

    public void save(String filePath) {
        var snapshot = new SystemStateSnapshot(
                context.getAllNotes(),
                context.getAllAssertions(),
                context.rules(),
                new Cog.Configuration(cog)
        );

        var path = Paths.get(filePath);
        try {
            var json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot);
            Files.writeString(path, json);
            message("System state saved to " + filePath);
        } catch (IOException e) {
            error("Error saving system state to " + filePath + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void load(String filePath) {
        var path = Paths.get(filePath);
        if (!Files.exists(path) || !Files.isReadable(path)) {
            message("State file not found or not readable: " + filePath + ". Starting with empty state.");
            return;
        }

        try {
            var json = Files.readString(path);
            var snapshot = mapper.readValue(json, SystemStateSnapshot.class);
            applySnapshot(snapshot);
            message("System state loaded from " + filePath);
        } catch (IOException e) {
            error("Error loading system state from " + filePath + ": " + e.getMessage());
            e.printStackTrace();
            applySnapshot(new SystemStateSnapshot(List.of(), List.of(), List.of(), new Cog.Configuration()));
        }
    }

    private void applySnapshot(SystemStateSnapshot snapshot) {
        cog.notes.clear();
        context.clear();

        cog.applyConfig(snapshot.configuration());

        snapshot.notes().forEach(note -> cog.notes.put(note.id(), note));

        if (!cog.notes.containsKey(Cog.CONFIG_NOTE_ID)) {
            cog.notes.put(Cog.CONFIG_NOTE_ID, Cog.createDefaultConfigNote());
        }
        if (!cog.notes.containsKey(Cog.GLOBAL_KB_NOTE_ID)) {
            cog.notes.put(Cog.GLOBAL_KB_NOTE_ID, new Note(Cog.GLOBAL_KB_NOTE_ID, Cog.GLOBAL_KB_NOTE_TITLE, "Global KB Assertions", Note.Status.IDLE));
        }

        snapshot.rules().forEach(context::addRule);

        tms.clearInternal();
        snapshot.assertions().forEach(tms::addInternal);

        tms.rebuildIndicesAndStatus();

        context.activeNoteIds.clear();
        context.activeNoteIds.add(Cog.GLOBAL_KB_NOTE_ID);
        cog.notes.values().stream()
                .filter(note -> note.status() == Note.Status.ACTIVE)
                .forEach(note -> context.addActiveNote(note.id()));

        cog.lm.reconfigure();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record SystemStateSnapshot(
            List<Note> notes,
            Collection<Assertion> assertions,
            Collection<Rule> rules,
            Cog.Configuration configuration
    ) {
    }
}
