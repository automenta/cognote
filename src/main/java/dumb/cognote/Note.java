package dumb.cognote;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import static java.util.Objects.requireNonNull;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Note(String id, String title, String text, Status status) {

    public Note {
        requireNonNull(id);
        requireNonNull(title);
        requireNonNull(text);
        requireNonNull(status);
    }

    public Note(String id, String title, String text) {
        this(id, title, text, Status.IDLE);
    }

    public Note withStatus(Status newStatus) {
        return new Note(this.id, this.title, this.text, newStatus);
    }

    public JsonNode toJson() {
        return Json.node(this);
    }

    @Override
    public String toString() {
        return title;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Note n && id.equals(n.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    public enum Status {
        IDLE, ACTIVE, PAUSED, COMPLETED
    }
}
