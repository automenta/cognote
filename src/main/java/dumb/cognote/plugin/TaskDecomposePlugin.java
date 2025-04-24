package dumb.cognote.plugin;

import dumb.cognote.Cog;
import dumb.cognote.Logic;

import java.util.Map;

import static dumb.cognote.Logic.Cognition;
import static dumb.cognote.Logic.KifList;

/**
 * Plugin that listens for (goal ...) assertions and uses the LLM to decompose them into sub-tasks.
 */
public class TaskDecomposePlugin extends Cog.BasePlugin {

    @Override
    public void start(Cog.Events e, Cognition ctx) {
        super.start(e, ctx);
        // Listen for ExternalInputEvents that are KifLists starting with "goal"
        e.on(new KifList(Logic.KifAtom.of("goal"), Logic.KifVar.of("?_")), this::handleGoalAssertion);
    }

    private void handleGoalAssertion(Cog.CogEvent event, java.util.Map<Logic.KifVar, Logic.KifTerm> bindings) {
        // The event is guaranteed to be ExternalInputEvent by the pattern listener registration
        if (!(event instanceof Cog.ExternalInputEvent(Logic.KifTerm term, String sourceId, String noteId))) {
            return; // Should not happen with correct registration, but defensive
        }
        // This is the targetNoteId from the input event


        if (!(term instanceof Logic.KifList goalList) || goalList.size() < 2 || goalList.op().filter("goal"::equals).isEmpty()) {
            return; // Should not happen due to pattern matching, but defensive check
        }

        // Extract the goal description (everything after the "goal" atom)
        // Convert the terms to KIF string for the LLM prompt
        var goalDescription = goalList.terms().stream().skip(1)
                .map(Logic.KifTerm::toKif)
                .collect(java.util.stream.Collectors.joining(" "));

        System.out.println("TaskDecompositionPlugin received goal: " + goalDescription + " from " + sourceId);

        // Use the DecomposeGoalTool
        cog().toolRegistry().get("decompose_goal").ifPresentOrElse(tool -> {
            Map<String, Object> params = new java.util.HashMap<>();
            params.put("goal_description", goalDescription);
            if (noteId != null) {
                params.put("target_note_id", noteId);
            }

            // Execute the tool asynchronously
            tool.execute(params).whenComplete((result, ex) -> {
                // The tool handles its own LLM task status updates.
                // We can log the final result here if needed.
                if (ex != null) {
                    System.err.println("DecomposeGoalTool execution failed for goal '" + goalDescription + "': " + ex.getMessage());
                } else {
                    System.out.println("DecomposeGoalTool execution completed for goal '" + goalDescription + "'. Result: " + result);
                }
            });

        }, () -> System.err.println("TaskDecompositionPlugin: DecomposeGoalTool not found in registry."));
    }

}
