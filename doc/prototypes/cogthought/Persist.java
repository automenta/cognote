package dumb.cogthought;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import dumb.cogthought.util.Json;
import dumb.cogthought.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant; // Added Instant import
import java.util.Collection;
import java.util.List;
import java.util.Map; // Added Map import
import java.util.ArrayList; // Added ArrayList import
import java.util.HashMap; // Added HashMap import

import static dumb.cogthought.util.Log.error;
import static dumb.cogthought.util.Log.message;
import static java.util.Objects.requireNonNull;

// This class will be refactored into a generic Persistence layer used by KnowledgeBase
public class Persist {

    private final Cog cog; // Keep for now, will be removed
    private final Cognition context; // Keep for now, will be replaced by KnowledgeBase
    private final Truths.BasicTMS tms; // Keep for now, will be refactored
    private final ObjectMapper mapper;

    public Persist(Cog cog, Cognition context, Truths truths) {
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
                context.getAllNotes(), // Needs refactoring to use KnowledgeBase
                context.getAllAssertions(), // Needs refactoring to use KnowledgeBase
                context.rules(), // Needs refactoring to use KnowledgeBase
                new Cog.Configuration(cog) // Needs refactoring
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
            // Apply default state if file not found
            applySnapshot(new SystemStateSnapshot(List.of(), List.of(), List.of(), new Cog.Configuration()));
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
            // Apply default state on error
            applySnapshot(new SystemStateSnapshot(List.of(), List.of(), List.of(), new Cog.Configuration()));
        }
    }

    private void applySnapshot(SystemStateSnapshot snapshot) {
        // This method needs significant refactoring to use the new KnowledgeBase interface
        cog.notes.clear();
        context.clear(); // This clears the old KB/TMS structure

        cog.applyConfig(snapshot.configuration());

        // Load notes - need to adapt to new Note structure
        snapshot.notes().forEach(note -> {
            // Create new Note objects with default values for new fields if missing in snapshot
            var loadedNote = new Note(
                note.id(),
                note.type() != null ? note.type() : "Note", // Default type
                note.title(),
                note.text(),
                note.status() != null ? note.status() : Note.Status.IDLE.name(), // Default status
                note.priority(),
                note.color(),
                note.updated() != null ? note.updated() : Instant.now(), // Default timestamp
                note.metadata() != null ? new HashMap<>(note.metadata()) : new HashMap<>(), // Default metadata
                note.graph() != null ? new ArrayList<>(note.graph()) : new ArrayList<>(), // Default graph
                note.associatedTerms() != null ? new ArrayList<>(note.associatedTerms()) : new ArrayList<>() // Default associatedTerms
            );
            cog.notes.put(loadedNote.id(), loadedNote);
        });


        // Ensure system notes exist with new structure
        if (!cog.notes.containsKey(Cog.CONFIG_NOTE_ID)) {
            cog.notes.put(Cog.CONFIG_NOTE_ID, Cog.createDefaultConfigNote());
        } else {
             // Update existing config note to new structure if necessary (basic update)
             var existingConfig = cog.notes.get(Cog.CONFIG_NOTE_ID);
             var updatedConfig = new Note(
                 existingConfig.id(), "Configuration", existingConfig.title(), existingConfig.text(),
                 existingConfig.status(), existingConfig.priority(), existingConfig.color(), existingConfig.updated(),
                 existingConfig.metadata(), existingConfig.graph(), existingConfig.associatedTerms()
             );
             cog.notes.put(Cog.CONFIG_NOTE_ID, updatedConfig);
        }
         if (!cog.notes.containsKey(Cog.GLOBAL_KB_NOTE_ID)) {
            var globalKbNote = new Note(Cog.GLOBAL_KB_NOTE_ID, "KnowledgeBase", Cog.GLOBAL_KB_NOTE_TITLE, "Global KB Assertions", Note.Status.IDLE.name(), null, null, Instant.now(), Map.of(), List.of(), List.of());
            cog.notes.put(Cog.GLOBAL_KB_NOTE_ID, globalKbNote);
        } else {
             // Update existing global KB note to new structure if necessary (basic update)
             var existingGlobalKb = cog.notes.get(Cog.GLOBAL_KB_NOTE_ID);
             var updatedGlobalKb = new Note(
                 existingGlobalKb.id(), "KnowledgeBase", existingGlobalKb.title(), existingGlobalKb.text(),
                 existingGlobalKb.status(), existingGlobalKb.priority(), existingGlobalKb.color(), existingGlobalKb.updated(),
                 existingGlobalKb.metadata(), existingGlobalKb.graph(), existingGlobalKb.associatedTerms()
             );
             cog.notes.put(Cog.GLOBAL_KB_NOTE_ID, updatedGlobalKb);
        }


        // Load rules - needs refactoring to use KnowledgeBase
        snapshot.rules().forEach(context::addRule);

        // Load assertions - needs refactoring to use KnowledgeBase
        tms.clearInternal(); // Clear old TMS state
        snapshot.assertions().forEach(tms::addInternal); // Add to old TMS state
        tms.rebuildIndicesAndStatus(); // Rebuild old TMS indices

        // Active notes - needs refactoring to be managed by SystemControl
        context.activeNoteIds.clear();
        context.activeNoteIds.add(Cog.GLOBAL_KB_NOTE_ID);
        cog.notes.values().stream()
                .filter(note -> Note.Status.valueOf(note.status()) == Note.Status.ACTIVE) // Use new status field
                .forEach(note -> context.addActiveNote(note.id()));

        cog.lm.reconfigure(); // Keep for now, will be part of LLMService setup
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    // This record will be refactored or removed as state is managed differently
    private record SystemStateSnapshot(
            List<Note> notes, // Note structure changed
            Collection<Assertion> assertions,
            Collection<Rule> rules,
            Cog.Configuration configuration
    ) {
    }
}
