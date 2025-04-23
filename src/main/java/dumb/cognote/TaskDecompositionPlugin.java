package dumb.cognote;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatResponse;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static dumb.cognote.Cog.ID_PREFIX_LLM_ITEM;
import static dumb.cognote.Logic.KIF_OP_AND;
import static dumb.cognote.Logic.KifAtom;
import static dumb.cognote.Logic.KifList;
import static dumb.cognote.Logic.KifTerm;
import static java.util.Optional.ofNullable;

/**
 * Plugin that listens for (goal ...) assertions and uses the LLM to decompose them into sub-tasks.
 */
public class TaskDecompositionPlugin extends Cog.BasePlugin {

    @Override
    public void start(Cog.Events e, Cog.Cognition ctx) {
        super.start(e, ctx);
        // Listen for ExternalInputEvents that are KifLists starting with "goal"
        e.on(KifList.of(KifAtom.of("goal"), Logic.KifVar.of("?_")), this::handleGoalAssertion);
    }

    private void handleGoalAssertion(Cog.CogEvent event, java.util.Map<Logic.KifVar, Logic.KifTerm> bindings) {
        if (!(event instanceof Cog.ExternalInputEvent inputEvent)) {
            return; // Only process goals from external input for now
        }

        if (!(inputEvent.term() instanceof KifList goalList) || goalList.size() < 2 || !goalList.op().filter("goal"::equals).isPresent()) {
            return; // Should not happen due to pattern matching, but defensive check
        }

        // Extract the goal description (everything after the "goal" atom)
        // Convert the terms to KIF string for the LLM prompt
        var goalDescription = goalList.terms().stream().skip(1)
                .map(KifTerm::toKif)
                .collect(java.util.stream.Collectors.joining(" "));

        System.out.println("TaskDecompositionPlugin received goal: " + goalDescription + " from " + inputEvent.sourceId());

        // Trigger LLM for decomposition
        triggerLlmDecomposition(goalDescription, inputEvent.targetNoteId());
    }

    private void triggerLlmDecomposition(String goalDescription, @Nullable String targetNoteId) {
        if (getCog().lm == null) {
            System.err.println("TaskDecompositionPlugin: LLM not available.");
            return;
        }

        var taskId = Cog.generateId(ID_PREFIX_LLM_ITEM + "decompose_");
        var interactionType = "Task Decomposition";

        // Add a UI placeholder for the LLM task
        getCog().ui.addLlmUiPlaceholder(targetNoteId, interactionType + ": " + goalDescription);

        // Call the LLM asynchronously
        CompletableFuture<ChatResponse> future = getCog().lm.decomposeGoalAsync(taskId, goalDescription, targetNoteId);

        // Handle the result (primarily for logging and UI status updates)
        getCog().lm.activeLlmTasks.put(taskId, future);
        future.whenCompleteAsync((chatResponse, ex) -> {
            getCog().lm.activeLlmTasks.remove(taskId);
            if (ex != null) {
                var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
                if (!(cause instanceof CancellationException)) {
                    System.err.println(interactionType + " failed for goal '" + goalDescription + "': " + cause.getMessage());
                    getCog().ui.updateLlmItem(taskId, UI.LlmStatus.ERROR, interactionType + " failed: " + cause.getMessage());
                } else {
                    System.out.println(interactionType + " cancelled for goal '" + goalDescription + "'.");
                    getCog().ui.updateLlmItem(taskId, UI.LlmStatus.CANCELLED, interactionType + " cancelled.");
                }
            } else {
                System.out.println(interactionType + " completed for goal '" + goalDescription + "'. Sub-tasks should have been added by tool calls.");
                // The LLM should have used the tool to add assertions.
                // We can optionally log the final text response if any.
                if (chatResponse.content() != null && !chatResponse.content().isBlank()) {
                    System.out.println("LLM final message for decomposition: " + chatResponse.content());
                }
                getCog().ui.updateLlmItem(taskId, UI.LlmStatus.DONE, interactionType + " completed.");
            }
        }, getCog().events.exe); // Use the event executor for async processing
    }
}
