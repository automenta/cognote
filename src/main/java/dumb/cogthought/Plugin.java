package dumb.cogthought;

import dumb.cogthought.util.Events;
import dumb.cogthought.util.Log;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface Plugin {
    String id();

    // Keep for now, will be refactored to use SystemControl/KnowledgeBase
    default void start(Cognition c) {
        start(c.cog.events, c);
    }

    // Keep for now, will be refactored to use SystemControl/KnowledgeBase
    void start(Events e, Cognition c);

    default void stop() {
    }

    // Keep for now, will be refactored into TermLogicEngine plugins
    interface ReasonerPlugin extends Plugin {
        void initialize(Reason.Reasoning context);

        CompletableFuture<Answer> executeQuery(Query query);

        Set<Cog.QueryType> getSupportedQueryTypes();

        Set<Cog.Feature> getSupportedFeatures();

        @Override
        default void start(Events events, Cognition ctx) {
        }
    }

    // Keep for now, will be refactored to use SystemControl/KnowledgeBase
    abstract class BasePlugin implements Plugin {
        protected final String id = Cog.id(Cog.ID_PREFIX_PLUGIN + getClass().getSimpleName().replace("Plugin", "").toLowerCase() + "_");
        protected Events events; // Keep for now, will be accessed via ToolContext or SystemControl
        protected Cognition context; // Keep for now, will be replaced by KnowledgeBase
        protected Cog cog; // Keep for now, will be removed

        @Override
        public String id() {
            return id;
        }

        @Override
        public void start(Events e, Cognition ctx) {
            this.events = e;
            this.context = ctx;
            this.cog = ctx.cog;
        }

        protected void emit(Event event) {
            if (events != null) events.emit(event);
        }

        protected Knowledge getKb(@Nullable String noteId) {
            return context.kb(noteId); // Needs refactoring to use KnowledgeBase
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
