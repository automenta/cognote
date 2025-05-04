package dumb.cogthought.util;

import dumb.cogthought.Assertion;
import dumb.cogthought.KnowledgeBase;
import dumb.cogthought.Term;
import dumb.cogthought.Term.Atom;
import dumb.cogthought.Term.Lst;
import dumb.cogthought.Term.Num;
import dumb.cogthought.Term.Str;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
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

/**
 * This class provides a simple event bus and asserts events into the KB.
 * Events are converted into KB Terms and asserted as facts.
 */
public class Events {

    private final ExecutorService executor;
    private final ConcurrentMap<Class<? extends Event>, List<Consumer<Event>>> listeners = new ConcurrentHashMap<>();

    private KnowledgeBase knowledgeBase;

    public Events(ExecutorService executor) {
        this.executor = requireNonNull(executor);
    }

    /**
     * Sets the KnowledgeBase dependency. Events will be asserted into this KB.
     *
     * @param kb The KnowledgeBase instance.
     */
    public void setKnowledgeBase(KnowledgeBase kb) {
        this.knowledgeBase = requireNonNull(kb);
    }

    /**
     * Registers a listener for a specific event type.
     *
     * @param eventType The class of the event to listen for.
     * @param listener  The consumer function to execute when the event occurs.
     * @param <T>       The event type.
     */
    public <T extends Event> void on(Class<T> eventType, Consumer<T> listener) {
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                 .add(event -> listener.accept(eventType.cast(event)));
    }

    /**
     * Emits an event. The event is asserted into the Knowledge Base and
     * delivered to any registered internal listeners.
     *
     * @param event The event to emit.
     */
    public void emit(Event event) {
        // Assert event into the Knowledge Base
        if (knowledgeBase != null) {
            try {
                // LogMessageEvent is handled by Log.java asserting LogMessage terms.
                // Skip asserting it again as a generic Event term to avoid duplication.
                if (!(event instanceof LogMessageEvent)) {
                    // Convert the Event object to a KIF Term
                    Term eventTerm = convertEventToTerm(event);

                    // Create an Assertion for the event
                    var assertionId = "event-" + UUID.randomUUID(); // Generate a unique ID
                    var timestamp = Instant.now();
                    // Assuming a default KB for events, or maybe a specific 'system' KB
                    // TODO: Define a specific KB ID for system events in Ontology/Config
                    var kbId = "system"; // Placeholder KB ID for system events

                    var eventAssertion = new Assertion(
                        assertionId,
                        (Lst) eventTerm, // Cast is safe if convertEventToTerm returns Lst
                        0.5, // Default priority for events? Needs Ontology/Config
                        timestamp.toEpochMilli(), // Use epoch milliseconds for timestamp
                        null, // Source Note ID? Maybe a 'System' note ID?
                        Collections.emptySet(), // No justifications
                        Assertion.Type.GROUND, // Events are typically ground facts
                        false, false, false, // Assuming events are not equality/negated by default
                        Collections.emptyList(), // No quantified variables
                        0, true, // Active by default
                        kbId // KB ID
                    );

                    knowledgeBase.saveAssertion(eventAssertion);
                    // info("Asserted event into KB: " + event.getClass().getSimpleName()); // Too noisy
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
                // Execute listeners asynchronously to avoid blocking the emitter
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

    /**
     * Converts an Event object into a KIF Term representation.
     * This is a basic implementation using reflection. Needs refinement based on Ontology.
     *
     * @param event The event object.
     * @return A KIF Term representing the event.
     */
    private Term convertEventToTerm(Event event) {
        // Basic representation: (Event <ClassName> (<field1> <value1>) (<field2> <value2>) ...)
        // This uses reflection to get public fields. Record components are accessible this way.
        // Needs refinement for complex objects, private fields, methods, etc.

        List<Term> terms = new ArrayList<>();
        terms.add(Atom.of("Event")); // Ontology term for events (placeholder)
        terms.add(Atom.of(event.getClass().getSimpleName())); // Event type as Atom

        List<Term> detailTerms = new ArrayList<>();
        // Use reflection to get fields and their values
        for (Field field : event.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true); // Allow access to private fields (common in records)
                Object value = field.get(event);
                Term valueTerm = convertObjectToTerm(value); // Convert field value to Term
                detailTerms.add(new Lst(List.of(Atom.of(field.getName()), valueTerm))); // (fieldName value)
            } catch (Exception e) {
                // Log error but continue with other fields
                error("Failed to convert event field '" + field.getName() + "' to Term: " + e.getMessage());
            }
        }

        terms.add(new Lst(detailTerms)); // Add details as a list term

        return new Lst(terms); // (Event ClassName (field1 value1) ...)
    }

    /**
     * Converts a Java object value to a KIF Term.
     * This is a basic implementation. Needs refinement for complex types.
     *
     * @param value The Java object value.
     * @return A KIF Term representation.
     */
    private Term convertObjectToTerm(Object value) {
        if (value == null) {
            return Atom.of("null"); // Represent null as a specific atom? Or just omit?
        } else if (value instanceof String s) {
            // Escape quotes and backslashes for KIF string literal
            return Str.of(s);
        } else if (value instanceof Number n) {
            return Num.of(n.doubleValue()); // Convert all numbers to double for simplicity
        } else if (value instanceof Boolean b) {
            return Atom.of(b.toString()); // "true" or "false" atoms
        } else if (value instanceof Instant inst) {
            return Num.of(inst.toEpochMilli()); // Represent Instant as epoch milliseconds
        } else if (value instanceof Enum<?> e) {
            return Atom.of(e.name()); // Represent enum as its name atom
        } else if (value instanceof Term t) {
            return t; // If it's already a Term, use it directly
        } else if (value instanceof List<?> list) {
            // Convert list to a KIF list term (Lst)
            List<Term> listTerms = new ArrayList<>();
            // TODO: Decide on the operator for generic lists. Maybe a special atom like "List"?
            // For now, just put elements in a list without an operator. This is non-standard KIF.
            // A better approach is needed based on Ontology.
            // Let's use a placeholder operator like "ListElements"
            listTerms.add(Atom.of("ListElements")); // Placeholder operator
            list.forEach(item -> listTerms.add(convertObjectToTerm(item)));
            return new Lst(listTerms);
        } else if (value instanceof java.util.Map<?, ?> map) {
             // Convert map to a KIF list of (key value) pairs
             List<Term> mapTerms = new ArrayList<>();
             // TODO: Decide on the operator for maps. Maybe a special atom like "MapEntries"?
             mapTerms.add(Atom.of("MapEntries")); // Placeholder operator
             map.forEach((key, val) -> {
                 Term keyTerm = convertObjectToTerm(key);
                 Term valueTerm = convertObjectToTerm(val);
                 mapTerms.add(new Lst(List.of(keyTerm, valueTerm))); // (key value)
             });
             return new Lst(mapTerms);
        }
        // For other complex objects, use a string representation as a fallback
        return Str.of(value.toString());
    }


    // Base interface for all events
    public interface Event {}

    // Example Event types (these might become part of the Ontology later)
    // LogMessageEvent is handled separately by Log.java
    public record LogMessageEvent(String message, Log.LogLevel level) implements Event {}

    // Add other event types here as needed, e.g.:
    // public record AssertionAddedEvent(String assertionId, String kbId) implements Event {}
    // public record RuleAddedEvent(String ruleId) implements Event {}
    // public record ToolExecutedEvent(String toolName, Term parameters, Term result) implements Event {}
    // public record ApiRequestReceivedEvent(String requestId, Term requestTerm) implements Event {}
    // public record ApiResponseSentEvent(String requestId, Term responseTerm) implements Event {}
}
