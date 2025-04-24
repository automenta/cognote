package dumb.cognote;

import org.jetbrains.annotations.Nullable;

public interface Plugin {
    String id();

    default void start(Logic.Cognition c) {
        start(c.events, c);
    }

    void start(Events e, Logic.Cognition c);

    default void stop() {
    }

    abstract class BasePlugin implements Plugin {
        protected final String id = Cog.id(Cog.ID_PREFIX_PLUGIN + getClass().getSimpleName().replace("Plugin", "").toLowerCase() + "_");
        protected Events events;
        protected Logic.Cognition context;

        @Override
        public String id() {
            return id;
        }

        @Override
        public void start(Events e, Logic.Cognition ctx) {
            this.events = e;
            this.context = ctx;
        }

        protected void publish(Cog.CogEvent event) {
            if (events != null) events.emit(event);
        }

        protected Logic.Knowledge getKb(@Nullable String noteId) {
            return context.kb(noteId);
        }

        protected Cog cog() {
            return context.cog;
        }
    }
}
