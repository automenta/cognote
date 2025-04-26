package dumb.cognote;

import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface Plugin {
    String id();

    default void start(Logic.Cognition c) {
        start(c.events, c);
    }

    void start(Events e, Logic.Cognition c);

    default void stop() {
    }

    interface ReasonerPlugin extends Plugin {
        void initialize(Reason.Reasoning context);

        CompletableFuture<Cog.Answer> executeQuery(Cog.Query query);

        Set<Cog.QueryType> getSupportedQueryTypes();

        Set<Cog.Feature> getSupportedFeatures();

        @Override
        default void start(Events events, Logic.Cognition ctx) {
        }
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

        protected Knowledge getKb(@Nullable String noteId) {
            return context.kb(noteId);
        }

        protected Cog cog() {
            return context.cog;
        }

        protected void log(String message) {
            Log.message(message);
        }

        protected void logError(String message) {
            Log.error(message);
        }

        protected void logWarning(String message) {
            Log.warning(message);
        }
    }
}
