package dumb.cognote;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

import static java.util.Objects.requireNonNull;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Query(String id, @JsonProperty("queryType") Cog.QueryType type,
                    @JsonProperty("patternJson") Term pattern,
                    @Nullable String targetKbId,
                    Map<String, Object> parameters) {

    @JsonProperty("patternString")
    public String patternString() {
        return pattern.toKif();
    }

    public JsonNode toJson() {
        return Json.node(this);
    }

    public record QueryEvent(Query query) implements Event {
        public QueryEvent {
            requireNonNull(query);
        }

        @Override
        public String assocNote() {
            return query.targetKbId();
        }

        @Override
        public String getEventType() {
            return "QueryEvent";
        }
    }
}
