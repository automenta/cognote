package dumb.cog.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dumb.cog.Cog;
import dumb.cog.Event;
import dumb.cog.Tool;
import dumb.cog.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static dumb.cog.util.Log.error;
import static dumb.cog.util.Log.message;

public class GenerateQuestionsTool implements Tool {

    private final Cog cog;

    public GenerateQuestionsTool(Cog cog) {
        this.cog = cog;
    }

    @Override
    public String name() {
        return "generate_questions";
    }

    @Override
    public String description() {
        return "Generates a list of questions based on the content of a note. Returns a JSON array of question strings.";
    }

    @dev.langchain4j.agent.tool.Tool("Generates a list of questions based on the content of a note. Returns a JSON array of question strings.")
    public CompletableFuture<String> generateQuestions(@dev.langchain4j.agent.tool.P("note_id") String noteId) {
        var taskId = Cog.id(Cog.ID_PREFIX_LLM_ITEM);
        cog.events.emit(new Event.TaskUpdateEvent(taskId, Cog.TaskStatus.SENDING, "Generating questions for note: " + noteId));

        return CompletableFuture.supplyAsync(() -> {
                    var note = cog.note(noteId).orElseThrow(() -> new ToolExecutionException("Note not found: " + noteId));

                    var systemMessage = SystemMessage.from("""
                            You are a question generation assistant. Your task is to read the provided text and generate a list of insightful questions that could be answered based on the text, or that the text prompts you to ask.
                            The output MUST be a JSON array of strings, where each string is a question.
                            Example: ["What is the main topic?", "How does X relate to Y?", "What are the implications of Z?"]
                            Output ONLY the JSON array, nothing else.
                            """);

                    var userMessage = UserMessage.from("Text:\n" + note.text());

                    List<ChatMessage> history = new ArrayList<>();
                    history.add(systemMessage);
                    history.add(userMessage);

                    return cog.lm.llmAsync(taskId, history, "Question Generation", noteId).join();

                }, cog.events.exe)
                .thenApply(AiMessage::text)
                .thenApply(jsonString -> {
                    try {
                        var jsonNode = Json.obj(jsonString, JsonNode.class);

                        if (jsonNode == null || !jsonNode.isArray()) {
                            throw new ToolExecutionException("LLM returned invalid JSON format (not an array): " + jsonString);
                        }

                        var jsonArray = (ArrayNode) jsonNode;
                        List<String> questions = new ArrayList<>();

                        for (var element : jsonArray) {
                            if (!element.isTextual()) {
                                throw new ToolExecutionException("LLM returned JSON array containing non-string elements.");
                            }
                            questions.add(element.asText());
                        }

                        message("Question generation result for note '" + noteId + "': " + jsonString);

                        questions.stream()
                                .map(q -> new dumb.cog.Term.Lst(dumb.cog.Term.Atom.of(dumb.cog.Logic.PRED_NOTE_QUESTION), dumb.cog.Term.Atom.of(q)))
                                .forEach(kif -> cog.events.emit(new Event.ExternalInputEvent(kif, "tool:generate_questions", noteId)));

                        return jsonString; // Return the original JSON string from LLM
                    } catch (JsonProcessingException e) {
                        error("LLM returned invalid JSON for question generation: " + jsonString + " Error: " + e.getMessage());
                        throw new ToolExecutionException("LLM failed to return valid JSON array: " + e.getMessage());
                    }
                })
                .exceptionally(ex -> {
                    error("Question generation failed for note '" + noteId + "': " + ex.getMessage());
                    throw new ToolExecutionException("Question generation failed: " + ex.getMessage());
                });
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> parameters) {
        var noteId = (String) parameters.get("note_id");

        if (noteId == null || noteId.isBlank()) {
            error("GenerateQuestionsTool requires a 'note_id' parameter.");
            return CompletableFuture.failedFuture(new ToolExecutionException("Missing 'note_id' parameter."));
        }

        return generateQuestions(noteId);
    }
}
