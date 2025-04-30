package dumb.cognote;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "eventType",
        visible = true)
@JsonSubTypes({
        @Type(value = CogEvent.AssertedEvent.class, name = "AssertedEvent"),
        @Type(value = CogEvent.RetractedEvent.class, name = "RetractedEvent"),
        @Type(value = CogEvent.AssertionEvictedEvent.class, name = "AssertionEvictedEvent"),
        @Type(value = CogEvent.AssertionStateEvent.class, name = "AssertionStateEvent"),
        @Type(value = CogEvent.TemporaryAssertionEvent.class, name = "TemporaryAssertionEvent"),
        @Type(value = CogEvent.RuleAddedEvent.class, name = "RuleAddedEvent"),
        @Type(value = CogEvent.RuleRemovedEvent.class, name = "RuleRemovedEvent"),
        @Type(value = CogEvent.TaskUpdateEvent.class, name = "TaskUpdateEvent"),
        @Type(value = CogEvent.SystemStatusEvent.class, name = "SystemStatusEvent"),
        @Type(value = CogEvent.AddedEvent.class, name = "AddedEvent"),
        @Type(value = CogEvent.RemovedEvent.class, name = "RemovedEvent"),
        @Type(value = CogEvent.ExternalInputEvent.class, name = "ExternalInputEvent"),
        @Type(value = CogEvent.RetractionRequestEvent.class, name = "RetractionRequestEvent"),
        @Type(value = Events.LogMessageEvent.class, name = "LogMessageEvent"),
        @Type(value = Events.DialogueRequestEvent.class, name = "DialogueRequestEvent"),
        @Type(value = Truths.ContradictionDetectedEvent.class, name = "ContradictionDetectedEvent"),
        @Type(value = Answer.AnswerEvent.class, name = "AnswerEvent"),
        @Type(value = Query.QueryEvent.class, name = "QueryEvent"),
        @Type(value = Cog.NoteStatusEvent.class, name = "NoteStatusEvent")
})
public interface CogEvent {

    default String assocNote() {
        return null;
    }

    default JsonNode toJson() {
        return Json.node(this);
    }

    String getEventType();

    interface NoteEvent extends CogEvent {
        Note note();

        @Override
        default String assocNote() {
            return note().id();
        }
    }

    interface NoteIDEvent extends CogEvent {
        String noteId();

        @Override
        default String assocNote() {
            return noteId();
        }
    }

    interface AssertionEvent extends CogEvent {
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
    record AssertionStateEvent(String assertionId, boolean isActive, String kbId) implements CogEvent {

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
    record RuleAddedEvent(Rule rule) implements CogEvent {

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
    record RuleRemovedEvent(Rule rule) implements CogEvent {

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
    record TaskUpdateEvent(String taskId, Cog.TaskStatus status, String content) implements CogEvent {

        @Override
        public String getEventType() {
            return "TaskUpdateEvent";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SystemStatusEvent(String statusMessage, int kbCount, int kbCapacity, int taskQueueSize,
                             int ruleCount) implements CogEvent {

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
    record RemovedEvent(Note note) implements NoteEvent {

        @Override
        public String getEventType() {
            return "RemovedEvent";
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
}
