package dumb.cognote.tool;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dumb.cognote.Cog;
import dumb.cognote.CogNote;
import dumb.cognote.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static dumb.cognote.Log.error;
import static dumb.cognote.Log.message;

public class SummarizeTool implements Tool {

    private final CogNote cog;

    public SummarizeTool(CogNote cog) {
        this.cog = cog;
    }

    @Override
    public String name() {
        return "summarize";
    }

    @Override
    public String description() {
        return "Summarizes the content of a specific note.";
    }

    @dev.langchain4j.agent.tool.Tool("Summarizes the content of a specific note.")
    public CompletableFuture<String> summarize(@dev.langchain4j.agent.tool.P("note_id") String noteId) {
        var taskId = Cog.id(Cog.ID_PREFIX_LLM_ITEM);
        cog.events.emit(new Cog.TaskUpdateEvent(taskId, Cog.TaskStatus.SENDING, "Summarizing note: " + noteId));

        return CompletableFuture.supplyAsync(() -> {
                    var note = cog.note(noteId).orElseThrow(() -> new ToolExecutionException("Note not found: " + noteId));

                    var systemMessage = SystemMessage.from("""
                            You are a summarization expert. Your task is to provide a concise summary of the provided text.
                            Focus on the main points and key information.
                            Output ONLY the summary text, nothing else.
                            """);

                    var userMessage = UserMessage.from("Text:\n" + note.text());

                    List<ChatMessage> history = new ArrayList<>();
                    history.add(systemMessage);
                    history.add(userMessage);

                    return cog.lm.llmAsync(taskId, history, "Summarization", noteId).join();

                }, cog.events.exe)
                .thenApply(AiMessage::text)
                .thenApply(summaryText -> {
                    message("Summarization result for note '" + noteId + "': " + summaryText);

                    var summaryKif = new dumb.cognote.Term.Lst(dumb.cognote.Term.Atom.of(dumb.cognote.Logic.PRED_NOTE_SUMMARY), dumb.cognote.Term.Atom.of(summaryText));
                    cog.events.emit(new Cog.ExternalInputEvent(summaryKif, "tool:summarize", noteId));

                    return summaryText;
                })
                .exceptionally(ex -> {
                    error("Summarization failed for note '" + noteId + "': " + ex.getMessage());
                    throw new ToolExecutionException("Summarization failed: " + ex.getMessage());
                });
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> parameters) {
        var noteId = (String) parameters.get("note_id");

        if (noteId == null || noteId.isBlank()) {
            error("SummarizeTool requires a 'note_id' parameter.");
            return CompletableFuture.failedFuture(new ToolExecutionException("Missing 'note_id' parameter."));
        }

        return summarize(noteId);
    }
}
