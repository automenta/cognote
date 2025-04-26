package dumb.cognote.plugin;

import dumb.cognote.Cog;
import dumb.cognote.Events;
import dumb.cognote.Logic;
import dumb.cognote.Truths;

import static dumb.cognote.Log.message;

public class TmsPlugin extends Plugin.BasePlugin {

    @Override
    public void start(Events e, Logic.Cognition ctx) {
        super.start(e, ctx);
        events.on(Truths.ContradictionDetectedEvent.class, this::handleContradictionDetected);
        log("TmsPlugin started.");
    }

    private void handleContradictionDetected(Truths.ContradictionDetectedEvent event) {
        var contradiction = new Truths.Contradiction(event.contradictoryAssertionIds(), event.kbId());
        message("TmsPlugin detected contradiction in KB " + contradiction.kbId() + ": " + contradiction.conflictingAssertionIds());

        // Default strategy: Retract the weakest assertion(s)
        context.truth.resolveContradiction(contradiction, Truths.ResolutionStrategy.RETRACT_WEAKEST);
    }
}
