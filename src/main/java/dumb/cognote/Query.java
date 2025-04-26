package dumb.cognote;

import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;

import java.util.Map;

import static java.util.Objects.requireNonNull;

public record Query(String id, Cog.QueryType type, Term pattern, @Nullable String targetKbId,
                    Map<String, Object> parameters) {
    public Query {
        requireNonNull(id);
        requireNonNull(type);
        requireNonNull(pattern);
        requireNonNull(parameters);
    }

    public JSONObject toJson() {
        var json = new JSONObject()
                .put("type", "query")
                .put("id", id)
                .put("queryType", type.name())
                .put("patternJson", pattern.toJson())
                .put("patternString", pattern.toKif());
        if (targetKbId != null) json.put("targetKbId", targetKbId);
        if (!parameters.isEmpty()) json.put("parameters", new JSONObject(parameters));
        return json;
    }

    public record QueryEvent(Query query) implements Cog.CogEvent {
        public QueryEvent {
            requireNonNull(query);
        }

        @Override
        public String assocNote() {
            return query.targetKbId();
        }

        public JSONObject toJson() {
            return new JSONObject()
                    .put("type", "event")
                    .put("eventType", "QueryEvent")
                    .put("eventData", query.toJson());
        }
    }
}
