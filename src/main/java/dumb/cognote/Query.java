package dumb.cognote;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

import static java.util.Objects.requireNonNull;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Query(String id, Cog.QueryType type, Term pattern, @Nullable String targetKbId,
                    Map<String, Object> parameters) {
    public Query {
        requireNonNull(id);
        requireNonNull(type);
        requireNonNull(pattern);
        requireNonNull(parameters);
    }

    @JsonProperty("queryType") // Map type field to queryType in JSON
    public Cog.QueryType getQueryType() {
        return type;
    }

    @JsonProperty("patternJson") // Map pattern field to patternJson in JSON
    public JsonNode getPatternJson() {
        return pattern.toJson();
    }

    @JsonProperty("patternString") // Add patternString property to JSON
    public String getPatternString() {
        return pattern.toKif();
    }

    public JsonNode toJson() {
        return Json.node(this);
    }

    public record QueryEvent(Query query) implements Cog.CogEvent {
        public QueryEvent {
            requireNonNull(query);
        }

        @Override
        public String assocNote() {
            return query.targetKbId();
        }

        public JsonNode toJson() {
            return Json.node(this);
        }

        @Override
        public String getEventType() {
            return "QueryEvent";
        }
    }
}
