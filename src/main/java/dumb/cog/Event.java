package dumb.cog;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import dumb.cog.util.Events;
import dumb.cog.util.Json;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

import static java.util.Objects.requireNonNull;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "eventType",
        visible = true)
@JsonSubTypes({
        @Type(value = Event.AssertedEvent.class, name = "AssertedEvent"),
        @Type(value = Event.RetractedEvent.class, name = "RetractedEvent"),
        @Type(value = Event.AssertionEvictedEvent.class, name = "AssertionEvictedEvent"),
        @Type(value = Event.AssertionStateEvent.class, name = "AssertionStateEvent"),
        @Type(value = Event.TemporaryAssertionEvent.class, name = "TemporaryAssertionEvent"),
        @Type(value = Event.RuleAddedEvent.class, name = "RuleAddedEvent"),
        @Type(value = Event.RuleRemovedEvent.class, name = "RuleRemovedEvent"),
        @Type(value = Event.TaskUpdateEvent.class, name = "TaskUpdateEvent"),
        @Type(value = Event.SystemStatusEvent.class, name = "SystemStatusEvent"),
        @Type(value = Event.AddedEvent.class, name = "AddedEvent"),
        @Type(value = Event.ExternalInputEvent.class, name = "ExternalInputEvent"),
        @Type(value = Event.RetractionRequestEvent.class, name = "RetractionRequestEvent"),
        @Type(value = Events.LogMessageEvent.class, name = "LogMessageEvent"),
        @Type(value = Events.DialogueRequestEvent.class, name = "DialogueRequestEvent"),
        @Type(value = Truths.ContradictionDetectedEvent.class, name = "ContradictionDetectedEvent"),
        @Type(value = Answer.AnswerEvent.class, name = "AnswerEvent"),
        @Type(value = Query.QueryEvent.class, name = "QueryEvent"),
        @Type(value = Event.NoteStatusEvent.class, name = "NoteStatusEvent"),
        @Type(value = Event.NoteUpdatedEvent.class, name = "NoteUpdatedEvent"),
        @Type(value = Event.NoteDeletedEvent.class, name = "NoteDeletedEvent")
})
public interface Event {

    default String assocNote() {
        return null;
    }

    default JsonNode toJson() {
        return Json.node(this);
    }

    String getEventType();

    interface NoteEvent extends Event {
        Note note();

        @Override
        default String assocNote() {
            return note().id();
        }
    }

    interface NoteIDEvent extends Event {
        String noteId();

        @Override
        default String assocNote() {
            return noteId();
        }
    }

    interface AssertionEvent extends Event {
        Assertion assertion();

        @Override
        default String assocNote() {
            return assertion().sourceNoteId();
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AssertedEvent(Assertion assertion, String kbId) implements AssertionEvent {

        @Override
        public String assocNote() {
            return assertion.sourceNoteId() != null ? assertion.sourceNoteId() : kbId;
        }

        @Override
        public String getEventType() {
            return "AssertedEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RetractedEvent(Assertion assertion, String kbId, String reason) implements AssertionEvent {

        @Override
        public String assocNote() {
            return assertion.sourceNoteId() != null ? assertion.sourceNoteId() : kbId;
        }

        @Override
        public String getEventType() {
            return "RetractedEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AssertionEvictedEvent(Assertion assertion, String kbId) implements AssertionEvent {
        @Override
        public String assocNote() {
            return assertion.sourceNoteId() != null ? assertion.sourceNoteId() : kbId;
        }

        @Override
        public String getEventType() {
            return "AssertionEvictedEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AssertionStateEvent(String assertionId, boolean isActive, String kbId) implements Event {

        @Override
        public String getEventType() {
            return "AssertionStateEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TemporaryAssertionEvent(@JsonProperty("temporaryAssertionJson") Term.Lst temporaryAssertion,
                                   @JsonProperty("bindingsJson") Map<Term.Var, Term> bindings,
                                   String noteId) implements NoteIDEvent {

        @JsonProperty("temporaryAssertionString")
        public String temporaryAssertionString() {
            return temporaryAssertion.toKif();
        }

        @Override
        public String getEventType() {
            return "TemporaryAssertionEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RuleAddedEvent(Rule rule) implements Event {

        @Override
        public String assocNote() {
            return rule.sourceNoteId();
        }

        @Override
        public String getEventType() {
            return "RuleAddedEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RuleRemovedEvent(Rule rule) implements Event {

        @Override
        public String assocNote() {
            return rule.sourceNoteId();
        }

        @Override
        public String getEventType() {
            return "RuleRemovedEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TaskUpdateEvent(String taskId, Cog.TaskStatus status, String content) implements Event {

        @Override
        public String getEventType() {
            return "TaskUpdateEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SystemStatusEvent(String statusMessage, int kbCount, int kbCapacity, int taskQueueSize,
                             int ruleCount) implements Event {

        @Override
        public String getEventType() {
            return "SystemStatusEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AddedEvent(Note note) implements NoteEvent {

        @Override
        public String getEventType() {
            return "AddedEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ExternalInputEvent(@JsonProperty("termJson") Term term, String sourceId,
                              @Nullable String noteId) implements NoteIDEvent {

        @JsonProperty("termString")
        public String termString() {
            return term.toKif();
        }

        @Override
        public String getEventType() {
            return "ExternalInputEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RetractionRequestEvent(String target, Logic.RetractionType type, String sourceId,
                                  @Nullable String noteId) implements NoteIDEvent {

        @Override
        public String getEventType() {
            return "RetractionRequestEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record NoteStatusEvent(Note note, Note.Status oldStatus,
                           Note.Status newStatus) implements NoteEvent {
        public NoteStatusEvent {
            requireNonNull(note);
            requireNonNull(oldStatus);
            requireNonNull(newStatus);
        }

        @Override
        public String getEventType() {
            return "NoteStatusEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record NoteUpdatedEvent(Note updatedNote) implements NoteEvent {
        public NoteUpdatedEvent {
            requireNonNull(updatedNote);
        }

        @Override
        public Note note() {
            return updatedNote;
        }

        @Override
        public String getEventType() {
            return "NoteUpdatedEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record NoteDeletedEvent(String noteId) implements NoteIDEvent {
        public NoteDeletedEvent {
            requireNonNull(noteId);
        }

        @Override
        public String getEventType() {
            return "NoteDeletedEvent";
        }
    }
}
