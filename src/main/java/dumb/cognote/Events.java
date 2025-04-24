package dumb.cognote;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;

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
            logExeError(e, type, event.getClass().getSimpleName());
        }
    }

    private static void exeSafe(BiConsumer<Cog.CogEvent, Map<Term.Var, Term>> listener, Cog.CogEvent event, Map<Term.Var, Term> bindings, String type) {
        try {
            listener.accept(event, bindings);
        } catch (Exception e) {
            logExeError(e, type, event.getClass().getSimpleName() + " (Pattern Match)");
        }
    }

    private static void logExeError(Exception e, String type, String eventName) {
        System.err.printf("Error in %s for %s: %s%n", type, eventName, e.getMessage());
        e.printStackTrace();
    }

    public <T extends Cog.CogEvent> void on(Class<T> eventType, Consumer<T> listener) {
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(event -> listener.accept(eventType.cast(event)));
    }

    public void on(Term pattern, BiConsumer<Cog.CogEvent, Map<Term.Var, Term>> listener) {
        patternListeners.computeIfAbsent(pattern, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public void emit(Cog.CogEvent event) {
        if (exe.isShutdown()) {
            System.err.println("Warning: Events executor shutdown. Cannot publish event: " + event.getClass().getSimpleName());
            return;
        }
        exe.submit(() -> {
            listeners.getOrDefault(event.getClass(), new CopyOnWriteArrayList<>()).forEach(listener -> exeSafe(listener, event, "Direct Listener"));
            switch (event) {
                case Cog.AssertedEvent aaEvent -> handlePatternMatching(aaEvent.assertion().kif(), event);
                case Cog.TemporaryAssertionEvent taEvent -> handlePatternMatching(taEvent.temporaryAssertion(), event);
                case Cog.ExternalInputEvent eiEvent ->
                        handlePatternMatching(eiEvent.term(), event); // Also match patterns on external input
                default -> {
                }
            }
        });
    }

    private void handlePatternMatching(Term eventTerm, Cog.CogEvent event) {
        patternListeners.forEach((pattern, listeners) ->
                ofNullable(Logic.Unifier.match(pattern, eventTerm, Map.of()))
                        .ifPresent(bindings -> listeners.forEach(listener -> exeSafe(listener, event, bindings, "Pattern Listener")))
        );
    }

    public void shutdown() {
        listeners.clear();
        patternListeners.clear();
    }
}
