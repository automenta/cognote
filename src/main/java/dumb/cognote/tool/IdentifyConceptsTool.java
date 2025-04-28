package dumb.cognote.tool;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dumb.cognote.Cog;
import dumb.cognote.CogNote;
import dumb.cognote.Tool;
import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static dumb.cognote.Log.error;
import static dumb.cognote.Log.message;

public class IdentifyConceptsTool implements Tool {

    private final CogNote cog;

    public IdentifyConceptsTool(CogNote cog) {
        this.cog = cog;
    }

    @Override
    public String name() {
        return "identify_concepts";
    }

    @Override
    public String description() {
        return "Identifies key concepts, entities, or topics mentioned in the content of a note. Returns a JSON array of concept strings.";
    }

    @dev.langchain4j.agent.tool.Tool("Identifies key concepts, entities, or topics mentioned in the content of a note. Returns a JSON array of concept strings.")
    public CompletableFuture<String> identifyConcepts(@dev.langchain4j.agent.tool.P("note_id") String noteId) {
        var taskId = Cog.id(Cog.ID_PREFIX_LLM_ITEM);
        cog.events.emit(new Cog.TaskUpdateEvent(taskId, Cog.TaskStatus.SENDING, "Identifying concepts for note: " + noteId));

        return CompletableFuture.supplyAsync(() -> {
                    var note = cog.note(noteId).orElseThrow(() -> new ToolExecutionException("Note not found: " + noteId));

                    var systemMessage = SystemMessage.from("""
                            You are a concept extraction assistant. Your task is to read the provided text and identify the most important concepts, entities, or topics mentioned.
                            The output MUST be a JSON array of strings, where each string is a key concept or entity.
                            Example: ["Artificial Intelligence", "Machine Learning", "Neural Networks", "Deep Learning"]
                            Output ONLY the JSON array, nothing else.
                            """);

                    var userMessage = UserMessage.from("Text:\n" + note.text);

                    List<ChatMessage> history = new ArrayList<>();
                    history.add(systemMessage);
                    history.add(userMessage);

                    return cog.lm.llmAsync(taskId, history, "Concept Identification", noteId).join();

                }, cog.events.exe)
                .thenApply(AiMessage::text)
                .thenApply(jsonString -> {
                    try {
                        var jsonArray = new JSONArray(jsonString);
                        for (var i = 0; i < jsonArray.length(); i++) {
                            if (!(jsonArray.get(i) instanceof String)) {
                                throw new ToolExecutionException("LLM returned JSON array containing non-string elements.");
                            }
                        }
                        message("Concept identification result for note '" + noteId + "': " + jsonString);

                        jsonArray.toList().stream()
                                .filter(String.class::isInstance)
                                .map(String.class::cast)
                                .map(c -> new dumb.cognote.Term.Lst(dumb.cognote.Term.Atom.of(dumb.cognote.Logic.PRED_NOTE_CONCEPT), dumb.cognote.Term.Atom.of(c)))
                                .forEach(kif -> cog.events.emit(new Cog.ExternalInputEvent(kif, "tool:identify_concepts", noteId)));

                        return jsonString;
                    } catch (org.json.JSONException e) {
                        error("LLM returned invalid JSON for concept identification: " + jsonString + " Error: " + e.getMessage());
                        throw new ToolExecutionException("LLM failed to return valid JSON array: " + e.getMessage());
                    }
                })
                .exceptionally(ex -> {
                    error("Concept identification failed for note '" + noteId + "': " + ex.getMessage());
                    throw new ToolExecutionException("Concept identification failed: " + ex.getMessage());
                });
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> parameters) {
        var noteId = (String) parameters.get("note_id");

        if (noteId == null || noteId.isBlank()) {
            error("IdentifyConceptsTool requires a 'note_id' parameter.");
            return CompletableFuture.failedFuture(new ToolExecutionException("Missing 'note_id' parameter."));
        }

        return identifyConcepts(noteId);
    }
}
