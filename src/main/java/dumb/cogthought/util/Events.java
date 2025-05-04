package dumb.cogthought.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import dumb.cogthought.Event;
import dumb.cogthought.Logic;
import dumb.cogthought.Term;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

// This class will be refactored to assert events into the KnowledgeBase
public class Events {
    public final ExecutorService exe; // Keep for now, will be managed by SystemControl
    public final ConcurrentMap<Class<? extends Event>, CopyOnWriteArrayList<Consumer<Event>>> listeners = new ConcurrentHashMap<>(); // Keep for now, event handling will be refactored
    private final ConcurrentMap<Term, CopyOnWriteArrayList<BiConsumer<Event, Map<Term.Var, Term>>>> patternListeners = new ConcurrentHashMap<>(); // Keep for now, pattern matching will be refactored

    public Events(ExecutorService exe) {
        this.exe = requireNonNull(exe);
    }

    private static void exeSafe(Consumer<Event> listener, Event event, String type) {
        try {
            listener.accept(event);
        } catch (Exception e) {
            Log.error("Error processing " + type + " event listener for " + event.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Keep for now, event handling will be refactored
    public <T extends Event> void on(Class<T> eventType, Consumer<T> listener) {
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(event -> listener.accept(eventType.cast(event)));
    }

    // Keep for now, pattern matching will be refactored
    public void on(Term pattern, BiConsumer<Event, Map<Term.Var, Term>> listener) {
        patternListeners.computeIfAbsent(pattern, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    // This method will be refactored to assert events into the KnowledgeBase
    public void emit(Event event) {
        if (exe.isShutdown()) {
            return;
        }
        exe.submit(() -> {
            listeners.getOrDefault(event.getClass(), new CopyOnWriteArrayList<>()).forEach(listener -> exeSafe(listener, event, "class"));
            switch (event) {
                case Event.AssertedEvent aaEvent -> handlePatternMatching(aaEvent.assertion().kif(), aaEvent);
                case Event.TemporaryAssertionEvent taEvent ->
                        handlePatternMatching(taEvent.temporaryAssertion(), taEvent);
                default -> {
                }
            }
        });
    }

    // Keep for now, pattern matching will be refactored
    private void handlePatternMatching(Term term, Event event) {
        patternListeners.forEach((pattern, listeners) -> {
            if (term instanceof Term.Lst termList) {
                var bindings = Logic.Unifier.match(pattern, termList, Map.of());
                if (bindings != null)
                    listeners.forEach(listener -> exeSafe(e -> listener.accept(e, bindings), event, "pattern"));
            }
        });
    }

    // Keep for now, shutdown will be managed by SystemControl
    public void shutdown() {
        exe.shutdown();
    }

    // Keep for now, LogMessageEvent will be asserted into KB
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LogMessageEvent(String message, Log.LogLevel level) implements Event {
        public LogMessageEvent {
            requireNonNull(message);
            requireNonNull(level);
        }

        @Override
        public String getEventType() {
            return "LogMessageEvent";
        }
    }

    // Keep for now, DialogueRequestEvent will be asserted into KB
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DialogueRequestEvent(String dialogueId, String requestType, String prompt, JsonNode options,
                                       JsonNode context) implements Event {
        public DialogueRequestEvent {
            requireNonNull(dialogueId);
            requireNonNull(requestType);
            requireNonNull(prompt);
            requireNonNull(options);
            requireNonNull(context);
        }

        @Override
        public String getEventType() {
            return "DialogueRequestEvent";
        }
    }
}
