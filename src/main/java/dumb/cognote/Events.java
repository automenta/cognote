package dumb.cognote;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

public class Events {
    public final ExecutorService exe;
    final ConcurrentMap<Class<? extends Cog.CogEvent>, CopyOnWriteArrayList<Consumer<Cog.CogEvent>>> listeners = new ConcurrentHashMap<>();
    private final ConcurrentMap<Term, CopyOnWriteArrayList<BiConsumer<Cog.CogEvent, Map<Term.Var, Term>>>> patternListeners = new ConcurrentHashMap<>();

    Events(ExecutorService exe) {
        this.exe = requireNonNull(exe);
    }

    private static void exeSafe(Consumer<Cog.CogEvent> listener, Cog.CogEvent event, String type) {
        try {
            listener.accept(event);
        } catch (Exception e) {
            Log.error("Error processing " + type + " event listener for " + event.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public <T extends Cog.CogEvent> void on(Class<T> eventType, Consumer<T> listener) {
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(event -> listener.accept(eventType.cast(event)));
    }

    public void on(Term pattern, BiConsumer<Cog.CogEvent, Map<Term.Var, Term>> listener) {
        patternListeners.computeIfAbsent(pattern, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public void emit(Cog.CogEvent event) {
        if (exe.isShutdown()) {
            //Log.warning("Events executor shutdown. Cannot publish event: " + event.getClass().getSimpleName());
            return;
        }
        exe.submit(() -> {
            listeners.getOrDefault(event.getClass(), new CopyOnWriteArrayList<>()).forEach(listener -> exeSafe(listener, event, "class"));
            switch (event) {
                case Cog.AssertedEvent aaEvent -> handlePatternMatching(aaEvent.assertion().kif(), aaEvent);
                case Cog.TemporaryAssertionEvent taEvent ->
                        handlePatternMatching(taEvent.temporaryAssertion(), taEvent);
                default -> {
                }
            }
        });
    }

    private void handlePatternMatching(Term term, Cog.CogEvent event) {
        patternListeners.forEach((pattern, listeners) -> {
            if (term instanceof Term.Lst termList) {
                var bindings = Logic.Unifier.match(pattern, termList, Map.of());
                if (bindings != null)
                    listeners.forEach(listener -> exeSafe(e -> listener.accept(e, bindings), event, "pattern"));
            }
        });
    }

    public void shutdown() {
        exe.shutdown();
    }

    public record LogMessageEvent(String message, Log.LogLevel level) implements Cog.CogEvent {
        public LogMessageEvent {
            requireNonNull(message);
            requireNonNull(level);
        }

        public JSONObject toJson() {
            return new JSONObject()
                    .put("type", "event")
                    .put("eventType", "LogMessageEvent")
                    .put("eventData", new JSONObject()
                            .put("message", message)
                            .put("level", level.name()));
        }
    }

    public record DialogueRequestEvent(String dialogueId, String requestType, String prompt, JSONObject options,
                                       JSONObject context) implements Cog.CogEvent {
        public DialogueRequestEvent {
            requireNonNull(dialogueId);
            requireNonNull(requestType);
            requireNonNull(prompt);
            requireNonNull(options);
            requireNonNull(context);
        }

        public JSONObject toJson() {
            return new JSONObject()
                    .put("type", "event")
                    .put("eventType", "DialogueRequestEvent")
                    .put("eventData", new JSONObject()
                            .put("dialogueId", dialogueId)
                            .put("requestType", requestType)
                            .put("prompt", prompt)
                            .put("options", options)
                            .put("context", context));
        }
    }
}
