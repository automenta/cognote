package dumb.cognote.plugin;

import dumb.cognote.Cog;
import dumb.cognote.Events;
import dumb.cognote.Logic;
import dumb.cognote.Plugin;

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

        ev.on(Cog.AddedEvent.class, e -> updateStatus());
        ev.on(Cog.RemovedEvent.class, e -> updateStatus());

        ev.on(Cog.LlmInfoEvent.class, e -> updateStatus());
        ev.on(Cog.TaskUpdateEvent.class, e -> {
            var s = e.status();
            if (s == Cog.TaskStatus.DONE || s == Cog.TaskStatus.ERROR || s == Cog.TaskStatus.CANCELLED)
                updateStatus();
        });

        updateStatus();
    }

    private void updateStatus() {
        publish(new Cog.SystemStatusEvent(cog().status, context.kbCount(), context.kbTotalCapacity(), cog().lm.activeLlmTasks.size(), context.ruleCount()));
    }
}
