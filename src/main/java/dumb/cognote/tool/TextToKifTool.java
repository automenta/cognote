package dumb.cognote.tool;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dumb.cognote.Cog;
import dumb.cognote.CogNote;
import dumb.cognote.KifParser;
import dumb.cognote.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static dumb.cognote.Log.error;
import static dumb.cognote.Log.message;

public class TextToKifTool implements Tool {

    private final Cog cog;

    public TextToKifTool(Cog cog) {
        this.cog = cog;
    }

    @Override
    public String name() {
        return "text_to_kif";
    }

    @Override
    public String description() {
        return "Converts the content of a note into KIF expressions and asserts them into the note's knowledge base.";
    }

    @dev.langchain4j.agent.tool.Tool("Converts the content of a note into KIF expressions and asserts them into the note's knowledge base.")
    public CompletableFuture<String> text2kif(@dev.langchain4j.agent.tool.P("note_id") String noteId) {
        var taskId = Cog.id(Cog.ID_PREFIX_LLM_ITEM);
        cog.events.emit(new Cog.TaskUpdateEvent(taskId, Cog.TaskStatus.SENDING, "Converting note to KIF: " + noteId));

        return CompletableFuture.supplyAsync(() -> {
                    var note = ((CogNote) cog).note(noteId).orElseThrow(() -> new ToolExecutionException("Note not found: " + noteId));

                    var systemMessage = SystemMessage.from("""
                            You are a KIF conversion expert. Your task is to read the provided text and convert the factual statements and relationships into KIF expressions.
                            Represent the information accurately and concisely using KIF syntax.
                            Output ONLY the KIF expressions, one per line or grouped logically, nothing else.
                            Example:
                            (instance John Person)
                            (hasAge John 30)
                            (likes John Mary)
                            """);

                    var userMessage = UserMessage.from("Text:\n" + note.text);

                    List<ChatMessage> history = new ArrayList<>();
                    history.add(systemMessage);
                    history.add(userMessage);

                    return cog.lm.llmAsync(taskId, history, "Text to KIF Conversion", noteId).join();

                }, cog.events.exe)
                .thenApply(AiMessage::text)
                .thenApply(kifString -> {
                    try {
                        var terms = KifParser.parseKif(kifString);
                        if (terms.isEmpty()) {
                            message("Text to KIF result for note '" + noteId + "': No KIF terms parsed.");
                            return "No KIF terms parsed.";
                        }
                        terms.forEach(term -> cog.events.emit(new Cog.ExternalInputEvent(term, "tool:text_to_kif", noteId)));
                        message("Text to KIF result for note '" + noteId + "': Asserted " + terms.size() + " terms.");
                        return "Asserted " + terms.size() + " terms.";
                    } catch (KifParser.ParseException e) {
                        error("LLM returned invalid KIF for text conversion: " + kifString + " Error: " + e.getMessage());
                        throw new ToolExecutionException("LLM failed to return valid KIF: " + e.getMessage());
                    }
                })
                .exceptionally(ex -> {
                    error("Text to KIF conversion failed for note '" + noteId + "': " + ex.getMessage());
                    throw new ToolExecutionException("Text to KIF conversion failed: " + ex.getMessage());
                });
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> parameters) {
        var noteId = (String) parameters.get("note_id");

        if (noteId == null || noteId.isBlank()) {
            error("TextToKifTool requires a 'note_id' parameter.");
            return CompletableFuture.failedFuture(new ToolExecutionException("Missing 'note_id' parameter."));
        }

        return text2kif(noteId);
    }
}
