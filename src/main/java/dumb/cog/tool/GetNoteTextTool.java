package dumb.cog.tool;

import dumb.cog.Cog;
import dumb.cog.Tool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static dumb.cog.util.Log.error;

public class GetNoteTextTool implements Tool {

    private final Cog cog;

    public GetNoteTextTool(Cog cog) {
        this.cog = cog;
    }

    @Override
    public String name() {
        return "get_note_text";
    }

    @Override
    public String description() {
        return "Retrieves the plain text content of a specific note.";
    }

    @dev.langchain4j.agent.tool.Tool("Retrieves the plain text content of a specific note.")
    public CompletableFuture<String> getNoteText(@dev.langchain4j.agent.tool.P("note_id") String noteId) {
        return CompletableFuture.supplyAsync(() -> {
            var note = cog.note(noteId).orElseThrow(() -> new ToolExecutionException("Note not found: " + noteId));
            return note.text;
        }, cog.events.exe);
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> parameters) {
        var noteId = (String) parameters.get("note_id");

        if (noteId == null || noteId.isBlank()) {
            error("GetNoteTextTool requires a 'note_id' parameter.");
            return CompletableFuture.failedFuture(new ToolExecutionException("Missing 'note_id' parameter."));
        }

        return getNoteText(noteId);
    }
}
