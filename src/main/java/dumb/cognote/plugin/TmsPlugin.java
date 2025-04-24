package dumb.cognote.plugin;

import dumb.cognote.Cog;
import dumb.cognote.Logic;

import static dumb.cognote.Logic.TruthMaintenance.ResolutionStrategy.RETRACT_WEAKEST;

/**
 * Plugin that listens for TMS events, such as contradiction detection,
 * and triggers appropriate actions like contradiction resolution.
 */
public class TmsPlugin extends Cog.BasePlugin {

    @Override
    public void start(Cog.Events ev, Logic.Cognition ctx) {
        super.start(ev, ctx);
        // Listen for contradiction detection events
        ev.on(Cog.ContradictionDetectedEvent.class, this::handleContradiction);
        // Add other TMS event handlers here if needed in the future
    }

    /**
     * Handles a detected contradiction by triggering the TMS resolution process.
     *
     * @param event The ContradictionDetectedEvent.
     */
    private void handleContradiction(Cog.ContradictionDetectedEvent event) {
        System.out.printf("TmsEventHandlerPlugin: Handling contradiction in KB %s involving assertions %s%n",
                event.kbId(), event.contradictoryAssertionIds());

        // Create a Contradiction object from the event data
        var contradiction = new Logic.TruthMaintenance.Contradiction(event.contradictoryAssertionIds());

        // Trigger the TMS to resolve the contradiction using the chosen strategy
        // We use RETRACT_WEAKEST here as the default strategy.
        context.truth().resolveContradiction(contradiction, RETRACT_WEAKEST);
    }
}
