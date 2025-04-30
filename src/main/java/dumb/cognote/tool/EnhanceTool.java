package dumb.cognote.tool;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dumb.cognote.Cog;
import dumb.cognote.Event;
import dumb.cognote.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static dumb.cognote.Log.error;
import static dumb.cognote.Log.message;

public class EnhanceTool implements Tool {

    private final Cog cog;

    public EnhanceTool(Cog cog) {
        this.cog = cog;
    }

    @Override
    public String name() {
        return "enhance_note";
    }

    @Override
    public String description() {
        return "Uses the LLM to enhance or expand upon the content of a specific note.";
    }

    @dev.langchain4j.agent.tool.Tool("Uses the LLM to enhance or expand upon the content of a specific note.")
    public CompletableFuture<String> enhance(@dev.langchain4j.agent.tool.P("note_id") String noteId) {
        var taskId = Cog.id(Cog.ID_PREFIX_LLM_ITEM);
        cog.events.emit(new Event.TaskUpdateEvent(taskId, Cog.TaskStatus.SENDING, "Enhancing note: " + noteId));

        return CompletableFuture.supplyAsync(() -> {
                    var note = cog.note(noteId).orElseThrow(() -> new ToolExecutionException("Note not found: " + noteId));

                    var systemMessage = SystemMessage.from("""
                            You are a text enhancement expert. Your task is to read the provided text and enhance it by adding relevant details, expanding on ideas, or improving clarity.
                            Output ONLY the enhanced text, nothing else.
                            """);

                    var userMessage = UserMessage.from("Text to enhance:\n" + note.text());

                    List<ChatMessage> history = new ArrayList<>();
                    history.add(systemMessage);
                    history.add(userMessage);

                    return cog.lm.llmAsync(taskId, history, "Note Enhancement", noteId).join();

                }, cog.events.exe)
                .thenApply(AiMessage::text)
                .thenApply(enhancedText -> {
                    message("Enhancement result for note '" + noteId + "': " + enhancedText);

                    // Update the note's text with the enhanced version
                    cog.updateNoteText(noteId, enhancedText);

                    // Optionally, assert a fact about the enhancement
                    var enhancementKif = new dumb.cognote.Term.Lst(
                            dumb.cognote.Term.Atom.of("noteEnhanced"),
                            dumb.cognote.Term.Atom.of(noteId),
                            dumb.cognote.Term.Atom.of(enhancedText) // Could assert the new text or just a timestamp/tool ID
                    );
                    cog.events.emit(new Event.ExternalInputEvent(enhancementKif, "tool:enhance_note", noteId));


                    return "Note '" + noteId + "' enhanced.";
                })
                .exceptionally(ex -> {
                    error("Note enhancement failed for note '" + noteId + "': " + ex.getMessage());
                    throw new ToolExecutionException("Note enhancement failed: " + ex.getMessage());
                });
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> parameters) {
        var noteId = (String) parameters.get("note_id");

        if (noteId == null || noteId.isBlank()) {
            error("EnhanceTool requires a 'note_id' parameter.");
            return CompletableFuture.failedFuture(new ToolExecutionException("Missing 'note_id' parameter."));
        }

        return enhance(noteId);
    }
}
