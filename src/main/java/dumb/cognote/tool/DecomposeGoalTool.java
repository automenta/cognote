package dumb.cognote.tool;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dumb.cognote.Cog;
import dumb.cognote.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static dumb.cognote.Log.error;
import static dumb.cognote.Log.message;

public class DecomposeGoalTool implements Tool {

    private final Cog cog;

    public DecomposeGoalTool(Cog cog) {
        this.cog = cog;
    }

    @Override
    public String name() {
        return "decompose_goal";
    }

    @Override
    public String description() {
        return "Decomposes a high-level goal into a sequence of smaller, actionable steps or sub-goals, expressed as a KIF list.";
    }

    @dev.langchain4j.agent.tool.Tool("Decomposes a high-level goal into a sequence of smaller, actionable steps or sub-goals, expressed as a KIF list.")
    public CompletableFuture<String> execute(@dev.langchain4j.agent.tool.P("goal") String goal, @dev.langchain4j.agent.tool.P("context") String context, @dev.langchain4j.agent.tool.P("note_id") String noteId) {
        var taskId = Cog.id(Cog.ID_PREFIX_LLM_ITEM);
        cog.events.emit(new Cog.TaskUpdateEvent(taskId, Cog.TaskStatus.SENDING, "Decomposing goal: " + goal));

        var systemMessage = SystemMessage.from("""
                You are a goal decomposition expert. Your task is to break down a given high-level goal into a sequence of smaller, concrete, actionable steps or sub-goals.
                The output MUST be a single KIF list representing the sequence of steps. Each step should be a KIF term.
                Example: (and (step "Research topic") (step "Write outline") (step "Draft content"))
                Consider the provided context when decomposing the goal.
                Output ONLY the KIF list, nothing else.
                """);

        var userMessage = UserMessage.from(String.format("Goal: %s\nContext: %s", goal, context));

        List<ChatMessage> history = new ArrayList<>();
        history.add(systemMessage);
        history.add(userMessage);

        return cog.lm.llmAsync(taskId, history, "Goal Decomposition", noteId)
                .thenApply(AiMessage::text)
                .thenApply(kifString -> {
                    if (kifString == null || !kifString.trim().startsWith("(") || !kifString.trim().endsWith(")")) {
                        error("LLM returned non-KIF for goal decomposition: " + kifString);
                        throw new ToolExecutionException("LLM failed to return valid KIF list.");
                    }
                    message("Goal decomposition result for '" + goal + "': " + kifString);
                    return kifString;
                })
                .exceptionally(ex -> {
                    error("Goal decomposition failed for '" + goal + "': " + ex.getMessage());
                    throw new ToolExecutionException("Goal decomposition failed: " + ex.getMessage());
                });
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> parameters) {
        var goal = (String) parameters.get("goal");
        var context = (String) parameters.get("context");
        var noteId = (String) parameters.get("note_id");

        if (goal == null || goal.isBlank()) {
            error("DecomposeGoalTool requires a 'goal' parameter.");
            return CompletableFuture.failedFuture(new ToolExecutionException("Missing 'goal' parameter."));
        }

        return execute(goal, context, noteId);
    }
}
