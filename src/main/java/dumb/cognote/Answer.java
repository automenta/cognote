package dumb.cognote;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Answer(@JsonProperty("queryId") String queryId, Cog.QueryStatus status,
                     @JsonProperty("bindingsJson") List<Map<Term.Var, Term>> bindings,
                     @Nullable Logic.Explanation explanation) {
    public Answer {
        requireNonNull(queryId);
        requireNonNull(status);
        requireNonNull(bindings);
    }

    static Answer success(String queryId, List<Map<Term.Var, Term>> bindings) {
        return new Answer(queryId, Cog.QueryStatus.SUCCESS, bindings, null);
    }

    static Answer failure(String queryId) {
        return new Answer(queryId, Cog.QueryStatus.FAILURE, List.of(), null);
    }

    static Answer error(String queryId, String message) {
        return new Answer(queryId, Cog.QueryStatus.ERROR, List.of(), new Logic.Explanation(message));
    }

    public JsonNode toJson() {
        return Json.node(this);
    }

    public record AnswerEvent(Answer result) implements CogEvent {
        public AnswerEvent {
            requireNonNull(result);
        }

        @Override
        public String getEventType() {
            return "AnswerEvent";
        }
    }
}
