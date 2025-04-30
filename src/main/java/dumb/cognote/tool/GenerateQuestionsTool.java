package dumb.cognote.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dumb.cognote.Cog;
import dumb.cognote.CogNote;
import dumb.cognote.Json;
import dumb.cognote.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static dumb.cognote.Log.error;
import static dumb.cognote.Log.message;

public class GenerateQuestionsTool implements Tool {

    private final CogNote cog;

    public GenerateQuestionsTool(CogNote cog) {
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
        cog.events.emit(new Cog.TaskUpdateEvent(taskId, Cog.TaskStatus.SENDING, "Generating questions for note: " + noteId));

        return CompletableFuture.supplyAsync(() -> {
                    var note = cog.note(noteId).orElseThrow(() -> new ToolExecutionException("Note not found: " + noteId));

                    var systemMessage = SystemMessage.from("""
                            You are a question generation assistant. Your task is to read the provided text and generate a list of insightful questions that could be answered based on the text, or that the text prompts you to ask.
                            The output MUST be a JSON array of strings, where each string is a question.
                            Example: ["What is the main topic?", "How does X relate to Y?", "What are the implications of Z?"]
                            Output ONLY the JSON array, nothing else.
                            """);

                    var userMessage = UserMessage.from("Text:\n" + note.text);

                    List<ChatMessage> history = new ArrayList<>();
                    history.add(systemMessage);
                    history.add(userMessage);

                    return cog.lm.llmAsync(taskId, history, "Question Generation", noteId).join();

                }, cog.events.exe)
                .thenApply(AiMessage::text)
                .thenApply(jsonString -> {
                    try {
                        JsonNode jsonNode = Json.obj(jsonString, JsonNode.class);

                        if (jsonNode == null || !jsonNode.isArray()) {
                            throw new ToolExecutionException("LLM returned invalid JSON format (not an array): " + jsonString);
                        }

                        ArrayNode jsonArray = (ArrayNode) jsonNode;
                        List<String> questions = new ArrayList<>();

                        for (JsonNode element : jsonArray) {
                            if (!element.isTextual()) {
                                throw new ToolExecutionException("LLM returned JSON array containing non-string elements.");
                            }
                            questions.add(element.asText());
                        }

                        message("Question generation result for note '" + noteId + "': " + jsonString);

                        questions.stream()
                                .map(q -> new dumb.cognote.Term.Lst(dumb.cognote.Term.Atom.of(dumb.cognote.Logic.PRED_NOTE_QUESTION), dumb.cognote.Term.Atom.of(q)))
                                .forEach(kif -> cog.events.emit(new Cog.ExternalInputEvent(kif, "tool:generate_questions", noteId)));

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
