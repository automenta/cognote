package dumb.cognote;

import dev.langchain4j.data.message.AiMessage;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static dumb.cognote.Cog.ID_PREFIX_LLM_ITEM;
import static dumb.cognote.Logic.*;

/**
 * Plugin that listens for (goal ...) assertions and uses the LLM to decompose them into sub-tasks.
 */
public class TaskDecompositionPlugin extends Cog.BasePlugin {

    @Override
    public void start(Cog.Events e, Logic.Cognition ctx) {
        super.start(e, ctx);
        // Listen for ExternalInputEvents that are KifLists starting with "goal"
        e.on(new KifList(KifAtom.of("goal"), Logic.KifVar.of("?_")), this::handleGoalAssertion);
    }

    private void handleGoalAssertion(Cog.CogEvent event, java.util.Map<Logic.KifVar, Logic.KifTerm> bindings) {
        // The event is guaranteed to be ExternalInputEvent by the pattern listener registration
        if (!(event instanceof Cog.ExternalInputEvent)) {
            return; // Should not happen with correct registration, but defensive
        }
        Cog.ExternalInputEvent externalInputEvent = (Cog.ExternalInputEvent) event;
        KifTerm term = externalInputEvent.term();
        String sourceId = externalInputEvent.sourceId();
        String noteId = externalInputEvent.targetNoteId(); // This is the targetNoteId from the input event


        if (!(term instanceof KifList goalList) || goalList.size() < 2 || !goalList.op().filter("goal"::equals).isPresent()) {
            return; // Should not happen due to pattern matching, but defensive check
        }

        // Extract the goal description (everything after the "goal" atom)
        // Convert the terms to KIF string for the LLM prompt
        var goalDescription = goalList.terms().stream().skip(1)
                .map(KifTerm::toKif)
                .collect(java.util.stream.Collectors.joining(" "));

        System.out.println("TaskDecompositionPlugin received goal: " + goalDescription + " from " + sourceId);

        // Trigger LLM for decomposition
        triggerLlmDecomposition(goalDescription, noteId);
    }

    private void triggerLlmDecomposition(String goalDescription, @Nullable String targetNoteId) {
        if (cog().lm == null) {
            System.err.println("TaskDecompositionPlugin: LLM not available.");
            return;
        }

        var taskId = Cog.generateId(ID_PREFIX_LLM_ITEM + "decompose_");
        var interactionType = "Task Decomposition";

        // Add a UI placeholder for the LLM task
        cog().ui.addLlmUiPlaceholder(targetNoteId, interactionType + ": " + goalDescription);

        // Call the LLM asynchronously
        CompletableFuture<AiMessage> future = cog().lm.decomposeGoalAsync(taskId, goalDescription, targetNoteId);

        // Handle the result (primarily for logging and UI status updates)
        cog().lm.activeLlmTasks.put(taskId, future);
        future.whenCompleteAsync((chatResponse, ex) -> {
            cog().lm.activeLlmTasks.remove(taskId);
            if (ex != null) {
                var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
                if (!(cause instanceof CancellationException)) {
                    System.err.println(interactionType + " failed for goal '" + goalDescription + "': " + cause.getMessage());
                    cog().ui.updateLlmItem(taskId, UI.LlmStatus.ERROR, interactionType + " failed: " + cause.getMessage());
                } else {
                    System.out.println(interactionType + " cancelled for goal '" + goalDescription + "'.");
                    cog().ui.updateLlmItem(taskId, UI.LlmStatus.CANCELLED, interactionType + " cancelled.");
                }
            } else {
                System.out.println(interactionType + " completed for goal '" + goalDescription + "'. Sub-tasks should have been added by tool calls.");
                // The LLM should have used the tool to add assertions.
                // We can optionally log the final text response if any.
                var text = chatResponse.text();
                if (text != null && !text.isBlank()) {
                    System.out.println("LLM final message for decomposition: " + text);
                }
                cog().ui.updateLlmItem(taskId, UI.LlmStatus.DONE, interactionType + " completed.");
            }
        }, cog().events.exe); // Use the event executor for async processing
    }
}
