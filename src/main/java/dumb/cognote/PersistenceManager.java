package dumb.cognote;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

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
            // Start with empty state on error
            applySnapshot(new SystemStateSnapshot(List.of(), List.of(), List.of(), new Cog.Configuration()));
        }
    }

    private void applySnapshot(SystemStateSnapshot snapshot) {
        // Clear current state
        cog.notes.clear();
        context.clear(); // Clears KBs, rules, activeNoteIds (except global)

        // Apply configuration first
        cog.applyConfig(snapshot.configuration());

        // Load notes
        snapshot.notes().forEach(note -> cog.notes.put(note.id(), note));

        // Ensure system notes exist
        if (!cog.notes.containsKey(Cog.CONFIG_NOTE_ID)) {
            cog.notes.put(Cog.CONFIG_NOTE_ID, Cog.createDefaultConfigNote());
        }
        if (!cog.notes.containsKey(Cog.GLOBAL_KB_NOTE_ID)) {
            cog.notes.put(Cog.GLOBAL_KB_NOTE_ID, new Note(Cog.GLOBAL_KB_NOTE_ID, Cog.GLOBAL_KB_NOTE_TITLE, "Global KB Assertions", Note.Status.IDLE));
        }

        // Load rules
        snapshot.rules().forEach(context::addRule);

        // Load assertions into TMS
        // Need to add them directly to TMS internal map and rebuild indices/status
        tms.clearInternal(); // Clear TMS internal state
        snapshot.assertions().forEach(tms::addInternal); // Add assertions without triggering events/checks yet

        // Rebuild TMS indices and status based on loaded data
        tms.rebuildIndicesAndStatus();

        // Re-add active notes to context based on loaded state
        context.activeNoteIds.clear();
        context.activeNoteIds.add(Cog.GLOBAL_KB_NOTE_ID); // Global KB is always active
        cog.notes.values().stream()
                .filter(note -> note.status() == Note.Status.ACTIVE)
                .forEach(note -> context.addActiveNote(note.id()));

        // Re-initialize KBs based on loaded assertions
        // Knowledge KBs are created on demand, but need to ensure they reflect loaded state
        // This is handled by Truths.BasicTMS.rebuildIndicesAndStatus and Knowledge.handleExternalStatusChange
        // which are triggered by the state events emitted during rebuild.

        // Reconfigure LM based on loaded config
        cog.lm.reconfigure();

        // Re-initialize reasoners and plugins (handled by Cog.start)
        // Cog.start is called after load in the main method
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
