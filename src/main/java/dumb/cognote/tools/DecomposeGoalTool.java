package dumb.cognote.tools;

import dev.langchain4j.data.message.UserMessage;
import dumb.cognote.Cog;
import dumb.cognote.UI;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static dumb.cognote.Cog.ID_PREFIX_LLM_ITEM;
import static java.util.Objects.requireNonNullElse;

public class DecomposeGoalTool implements BaseTool {

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
        return "Decomposes a high-level goal into smaller, actionable steps or sub-goals using the LLM. Input is a JSON object with 'goal_description' (string) and optional 'target_note_id' (string, defaults to global KB). The LLM should use the 'add_kif_assertion' tool to add the generated steps.";
    }

    @Override
    public CompletableFuture<Object> execute(Map<String, Object> parameters) {
        var goalDescription = (String) parameters.get("goal_description");
        var targetNoteId = (String) parameters.get("target_note_id");

        if (goalDescription == null || goalDescription.isBlank()) {
            return CompletableFuture.completedFuture("Error: Missing required parameter 'goal_description'.");
        }

        var finalTargetKbId = requireNonNullElse(targetNoteId, Cog.GLOBAL_KB_NOTE_ID);

        var taskId = Cog.generateId(ID_PREFIX_LLM_ITEM + "decompose_");
        var interactionType = "Task Decomposition";

        // Add a UI placeholder for the LLM task
        // cog.ui.addLlmUiPlaceholder(finalTargetKbId, interactionType + ": " + goalDescription); // Use target KB ID for UI
        var vm = UI.AttachmentViewModel.forLlm(
                taskId,
                finalTargetKbId, interactionType + ": Starting...", UI.AttachmentType.LLM_INFO,
                System.currentTimeMillis(), finalTargetKbId, UI.LlmStatus.SENDING
        );
        cog.events.emit(new Cog.LlmInfoEvent(vm));


        var promptText = """
                You are a task decomposition agent. Your goal is to break down a high-level goal into smaller, actionable steps or sub-goals.
                Express each step as a concise KIF assertion representing the step itself (e.g., a goal, action, or query).
                For each step you identify, use the `add_kif_assertion` tool with the note ID "%s".
                Do NOT output the KIF assertions directly in your response. Only use the tool.
                
                Examples of KIF assertions for steps:
                - (goal (findInformation about Cats))
                - (action (createNote "Summary of Cats"))
                - (query (instance ?X Cat))
                - (action (summarizeNote "%s")) ; Assuming a tool or process exists for this
                
                Break down the following goal:
                "%s"
                
                Generate KIF assertions for the steps and add them using the tool:""".formatted(finalTargetKbId, finalTargetKbId, goalDescription); // Use finalTargetKbId consistently

        var history = new ArrayList<dev.langchain4j.data.message.ChatMessage>();
        history.add(UserMessage.from(promptText));

        var llmFuture = cog.lm.llmAsync(taskId, history, interactionType, finalTargetKbId); // Use target KB ID for LLM context/logging

        cog.lm.activeLlmTasks.put(taskId, llmFuture);

        return llmFuture.handleAsync((chatResponse, ex) -> {
            cog.lm.activeLlmTasks.remove(taskId);
            if (ex != null) {
                var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
                if (!(cause instanceof CancellationException)) {
                    System.err.println(interactionType + " failed for goal '" + goalDescription + "': " + cause.getMessage());
                    cog.updateLlmItemStatus(taskId, UI.LlmStatus.ERROR, interactionType + " failed: " + cause.getMessage());
                    return "Error decomposing goal: " + cause.getMessage();
                } else {
                    System.out.println(interactionType + " cancelled for goal '" + goalDescription + "'.");
                    cog.updateLlmItemStatus(taskId, UI.LlmStatus.CANCELLED, interactionType + " cancelled.");
                    return "Goal decomposition cancelled.";
                }
            } else {
                System.out.println(interactionType + " completed for goal '" + goalDescription + "'. Sub-tasks should have been added by tool calls.");
                cog.updateLlmItemStatus(taskId, UI.LlmStatus.DONE, interactionType + " completed.");
                var text = chatResponse.text();
                if (text != null && !text.isBlank()) {
                    System.out.println("LLM final message for decomposition: " + text);
                }
                return "Goal decomposition completed. Check attachments for added assertions.";
            }
        }, cog.events.exe);
    }
}
