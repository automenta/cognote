package dumb.cognote.tool;

import dev.langchain4j.agent.tool.P;
import dumb.cognote.Cog;
import dumb.cognote.Tool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

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
        return "Retrieve the full text content of a specific note by its ID. Input is a JSON object with 'note_id' (string). Returns the note text or an error message.";
    }

    // This method is called by LangChain4j's AiServices.
    // It needs to block or return a simple type.
    // It calls the internal execute logic and blocks for the result.
    @dev.langchain4j.agent.tool.Tool(name = "get_note_text", value = "Retrieve the full text content of a specific note by its ID. Input is a JSON object with 'note_id' (string). Returns the note text or an error message.")
    public String getNoteTextToolMethod(@P(value = "The ID of the note to retrieve text from.") String noteId) {
        try {
            // Call the internal execute logic and block for the result.
            return (String) execute(Map.of("note_id", noteId)).join();
        } catch (Exception e) {
            System.err.println("Error in blocking tool method 'getNoteTextToolMethod': " + e.getMessage());
            e.printStackTrace();
            return "Error executing tool: " + e.getMessage();
        }
    }

    // The BaseTool execute method signature for internal calls.
    // It parses parameters from the map and returns a CompletableFuture.
    @Override
    public CompletableFuture<Object> execute(Map<String, Object> parameters) {
        var noteId = (String) parameters.get("note_id");
        return CompletableFuture.supplyAsync(() -> {
            if (noteId == null || noteId.isBlank()) {
                return "Error: Missing required parameter 'note_id'.";
            }
            if (cog == null || cog.ui == null) {
                return "Error: System UI not available.";
            }
            return cog.ui.findNoteById(noteId)
                    .map(note -> note.text)
                    .orElse("Error: Note with ID '" + noteId + "' not found.");
        }, cog.events.exe);
    }
}
