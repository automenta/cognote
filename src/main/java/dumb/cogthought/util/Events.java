package dumb.cogthought.util;

import dumb.cogthought.Assertion;
import dumb.cogthought.KnowledgeBase;
import dumb.cogthought.Term;
import dumb.cogthought.Term.Atom;
import dumb.cogthought.Term.Lst;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.util.Objects.requireNonNull;
import static dumb.cogthought.util.Log.error;
import static dumb.cogthought.util.Log.warn;

// This class provides a simple event bus and asserts events into the KB
public class Events {

    private final ExecutorService executor;
    private final ConcurrentMap<Class<? extends Event>, List<Consumer<Event>>> listeners = new ConcurrentHashMap<>();

    // New: Dependency on KnowledgeBase
    private KnowledgeBase knowledgeBase;

    public Events(ExecutorService executor) {
        this.executor = requireNonNull(executor);
    }

    // New: Set the KnowledgeBase dependency
    public void setKnowledgeBase(KnowledgeBase kb) {
        this.knowledgeBase = requireNonNull(kb);
    }

    public <T extends Event> void on(Class<T> eventType, Consumer<T> listener) {
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                 .add(event -> listener.accept(eventType.cast(event)));
    }

    public void emit(Event event) {
        // New: Assert event into the Knowledge Base
        if (knowledgeBase != null) {
            try {
                // LogMessageEvent is handled by Log.java asserting LogMessage terms.
                // Skip asserting it again as a generic Event term.
                if (event instanceof LogMessageEvent) {
                    // Still emit to internal listeners if any
                } else {
                    // Create a KIF term for the event, e.g., (Event <EventType> (<field1> <value1> ...))
                    // This requires a mapping from Event objects to Term structures.
                    // For now, create a basic term like (Event <EventClassName> "<EventDetails>")
                    // A more robust approach would use reflection or specific handlers per event type.

                    // Basic representation: (Event <ClassName> <EventDetailsTerm>)
                    // This is a placeholder; needs proper serialization to Term.
                    Term eventDetailsTerm = Atom.of("\"" + event.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"");


                    var eventTerm = new Lst(List.of(
                        Atom.of("Event"), // Ontology term for events
                        Atom.of(event.getClass().getSimpleName()), // Event type as Atom
                        eventDetailsTerm // Event details as a Term
                    ));

                    // Create an Assertion for the event
                    var assertionId = "event-" + UUID.randomUUID(); // Generate a unique ID
                    var timestamp = Instant.now();
                    // Assuming a default KB for events, or maybe a specific 'system' KB
                    var kbId = "system"; // Placeholder KB ID for system events

                    var eventAssertion = new Assertion(
                        assertionId,
                        (Lst) eventTerm, // Cast is safe here
                        0.5, // Default priority for events? Needs Ontology/Config
                        timestamp,
                        null, // Source Note ID? Maybe a 'System' note ID?
                        Collections.emptySet(), // No justifications
                        Assertion.Type.GROUND, // Ground fact
                        false, false, false,
                        Collections.emptyList(), // No quantified variables
                        0, true, // Active by default
                        kbId // KB ID
                    );

                    knowledgeBase.saveAssertion(eventAssertion);
                }

            } catch (Exception e) {
                // Fallback logging if KB assertion fails
                error("Error asserting event into KB: " + event.getClass().getSimpleName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            warn("KnowledgeBase not set for Events. Cannot assert event: " + event.getClass().getSimpleName());
        }


        // Emit event to internal listeners (if any)
        List<Consumer<Event>> eventListeners = listeners.get(event.getClass());
        if (eventListeners != null) {
            for (Consumer<Event> listener : eventListeners) {
                executor.submit(() -> {
                    try {
                        listener.accept(event);
                    } catch (Exception e) {
                        error("Error processing event listener for " + event.getClass().getSimpleName() + ": " + e.getMessage());
                        e.printStackTrace();
                    }
                });
            }
        }
    }

    // Base interface for all events
    public interface Event {}

    // Example Event types (these might become part of the Ontology later)
    public record LogMessageEvent(String message, Log.LogLevel level) implements Event {}
    // Other old event types removed as they are handled via KB assertions/queries now
}
