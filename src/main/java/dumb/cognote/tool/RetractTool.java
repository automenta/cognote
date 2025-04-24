package dumb.cognote.tool;

import dumb.cognote.Cog;
import dumb.cognote.Logic;
import dumb.cognote.Tool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class RetractTool implements Tool {

    private final Cog cog;

    public RetractTool(Cog cog) {
        this.cog = cog;
    }

    @Override
    public String name() {
        return "retract_assertion";
    }

    @Override
    public String description() {
        return "Retracts an assertion or rule from the knowledge base. Input is a JSON object with 'target' (string, the ID or KIF form) and 'type' (string, 'BY_ID' or 'BY_RULE_FORM'). Optional 'target_note_id' (string) for BY_ID type.";
    }

    @Override
    public CompletableFuture<Object> execute(Map<String, Object> parameters) {
        var target = (String) parameters.get("target");
        var typeStr = (String) parameters.get("type");
        var targetNoteId = (String) parameters.get("target_note_id"); // Optional

        return CompletableFuture.supplyAsync(() -> {
            if (cog == null) {
                return "Error: System not available.";
            }
            if (target == null || target.isBlank() || typeStr == null || typeStr.isBlank()) {
                return "Error: Missing required parameters 'target' or 'type'.";
            }

            Logic.RetractionType type;
            try {
                type = Logic.RetractionType.valueOf(typeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                return "Error: Invalid retraction type '" + typeStr + "'. Must be BY_ID or BY_RULE_FORM.";
            }

            // Note: BY_NOTE retraction is handled by the UI directly when removing a note.
            // This tool focuses on ID or Rule form retraction.
            if (type == Logic.RetractionType.BY_NOTE) {
                return "Error: Retraction type BY_NOTE is not supported by this tool. Use the UI to remove notes.";
            }

            var sourceId = "tool:retract_assertion";
            cog.events.emit(new Cog.RetractionRequestEvent(target, type, sourceId, targetNoteId));

            // Retraction is asynchronous via event. We can't confirm success immediately.
            // Return a message indicating the request was sent.
            return "Retraction request sent for target '" + target + "' (type: " + type + ").";

        }, cog.events.exe);
    }
}
