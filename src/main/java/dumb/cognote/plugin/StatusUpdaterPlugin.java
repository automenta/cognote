package dumb.cognote.plugin;

import dumb.cognote.*;

public class StatusUpdaterPlugin extends Plugin.BasePlugin {

    @Override
    public void start(Events ev, Logic.Cognition ctx) {
        super.start(ev, ctx);
        ev.on(Cog.AssertedEvent.class, e -> updateStatus());
        ev.on(Cog.RetractedEvent.class, e -> updateStatus());
        ev.on(Cog.AssertionEvictedEvent.class, e -> updateStatus());
        ev.on(Cog.AssertionStateEvent.class, e -> updateStatus());
        ev.on(Cog.RuleAddedEvent.class, e -> updateStatus());
        ev.on(Cog.RuleRemovedEvent.class, e -> updateStatus());
        ev.on(Cog.TaskUpdateEvent.class, e -> updateStatus());
        ev.on(CogNote.NoteStatusEvent.class, e -> updateStatus());
    }

    private void updateStatus() {
    }
}
