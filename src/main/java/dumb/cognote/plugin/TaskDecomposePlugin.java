package dumb.cognote.plugin;

import dumb.cognote.Cog;
import dumb.cognote.Events;
import dumb.cognote.Plugin;
import dumb.cognote.Term;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static dumb.cognote.Cog.ID_PREFIX_LLM_ITEM;
import static java.util.Objects.requireNonNullElse;

/**
 * Plugin that listens for (goal ...) assertions and uses the LLM to decompose them into sub-tasks.
 */
public class TaskDecomposePlugin extends Plugin.BasePlugin {

    @Override
    public void start(Events e, Cog.Cognition ctx) {
        super.start(e, ctx);
        // Listen for ExternalInputEvents that are KifLists starting with "goal"
        e.on(new Term.Lst(Term.Atom.of("goal"), Term.Var.of("?_")), this::handleGoalAssertion);
    }

    private void handleGoalAssertion(Cog.CogEvent event, java.util.Map<Term.Var, Term> bindings) {
        // The event is guaranteed to be ExternalInputEvent by the pattern listener registration
        if (!(event instanceof Cog.ExternalInputEvent(Term term, String sourceId, String noteId))) {
            return; // Should not happen with correct registration, but defensive
        }
        // This is the targetNoteId from the input event


        if (!(term instanceof Term.Lst goalList) || goalList.size() < 2 || goalList.op().filter("goal"::equals).isEmpty()) {
            return; // Should not happen due to pattern matching, but defensive check
        }

        // Extract the goal description (everything after the "goal" atom)
        // Convert the terms to KIF string for the LLM prompt
        var goalDescription = goalList.terms.stream().skip(1)
                .map(Term::toKif)
                .collect(java.util.stream.Collectors.joining(" "));

        System.out.println("TaskDecompositionPlugin received goal: " + goalDescription + " from " + sourceId);

        // Option 1: Use LLM to generate KIF steps (current implementation)
        // Option 2: Formulate a KIF query for the BC reasoner (ACHIEVE_GOAL)
        // For now, sticking with Option 1 as requested, but the ACHIEVE_GOAL type is added.

        // Use the DecomposeGoalTool
        cog().tools.get("decompose_goal").ifPresentOrElse(tool -> {
            Map<String, Object> params = new java.util.HashMap<>();
            params.put("goal_description", goalDescription);
            if (noteId != null) {
                params.put("target_note_id", noteId);
            }

            // Execute the tool asynchronously
            tool.execute(params).whenCompleteAsync((result, ex) -> {
                // The tool handles its own LLM task status updates.
                // We can log the final result here if needed.
                if (ex != null) {
                    System.err.println("DecomposeGoalTool execution failed for goal '" + goalDescription + "': " + ex.getMessage());
                } else {
                    System.out.println("DecomposeGoalTool execution completed for goal '" + goalDescription + "'. Result: " + result);
                }
            }, cog().events.exe); // Use the Cog's event executor

        }, () -> System.err.println("TaskDecompositionPlugin: DecomposeGoalTool not found in registry."));
    }

}
