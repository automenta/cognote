package dumb.cog.plugin;

import dumb.cog.Cognition;
import dumb.cog.Plugin;
import dumb.cog.Truths;
import dumb.cog.util.Events;

import static dumb.cog.util.Log.message;

public class TmsPlugin extends Plugin.BasePlugin {

    @Override
    public void start(Events e, Cognition ctx) {
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
