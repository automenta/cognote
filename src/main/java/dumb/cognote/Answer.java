package dumb.cognote;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Answer(String query, Cog.QueryStatus status, List<Map<Term.Var, Term>> bindings,
                     @Nullable Logic.Explanation explanation) { // Use Logic.Explanation
    public Answer {
        requireNonNull(query);
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
        return new Answer(queryId, Cog.QueryStatus.ERROR, List.of(), new Logic.Explanation(message)); // Use Logic.Explanation
    }

    @JsonProperty("queryId") // Map query field to queryId in JSON
    public String getQueryId() {
        return query;
    }

    @JsonProperty("bindingsJson") // Add bindingsJson property to JSON
    public JsonNode getBindingsJson() {
        if (bindings.isEmpty()) return null;
        var jsonBindings = Json.the.createArrayNode();
        bindings.forEach(bindingMap -> {
            var jsonBinding = Json.node();
            bindingMap.forEach((var, term) -> jsonBinding.set(var.name(), term.toJson()));
            jsonBindings.add(jsonBinding);
        });
        return jsonBindings;
    }

    public JsonNode toJson() {
        return Json.node(this);
    }

    public record AnswerEvent(Answer result) implements Cog.CogEvent {
        public AnswerEvent {
            requireNonNull(result);
        }

        public JsonNode toJson() {
            return Json.node(this);
        }

        @Override
        public String getEventType() {
            return "AnswerEvent";
        }
    }
}
