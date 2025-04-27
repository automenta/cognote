package dumb.cognote;

import dumb.cognote.Logic.Explanation;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

public record Answer(String query, Cog.QueryStatus status, List<Map<Term.Var, Term>> bindings,
                     @Nullable Explanation explanation) {
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
        return new Answer(queryId, Cog.QueryStatus.ERROR, List.of(), new Explanation(message));
    }

    public JSONObject toJson() {
        var json = new JSONObject()
                .put("type", "answer")
                .put("queryId", query)
                .put("status", status.name());

        if (!bindings.isEmpty()) {
            var jsonBindings = new JSONArray();
            bindings.forEach(bindingMap -> {
                var jsonBinding = new JSONObject();
                bindingMap.forEach((var, term) -> jsonBinding.put(var.name(), term.toJson()));
                jsonBindings.put(jsonBinding);
            });
            json.put("bindingsJson", jsonBindings);
        }

        if (explanation != null) json.put("explanation", explanation.toJson());

        return json;
    }

    public record AnswerEvent(Answer result) implements Cog.CogEvent {
        public AnswerEvent {
            requireNonNull(result);
        }

        @Override
        public String assocNote() {
            return null;
        }

        public JSONObject toJson() {
            return new JSONObject()
                    .put("type", "event")
                    .put("eventType", "AnswerEvent")
                    .put("eventData", result.toJson());
        }
    }
}
