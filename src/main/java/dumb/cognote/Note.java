package dumb.cognote;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import static java.util.Objects.requireNonNull;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true) // Ignore any extra fields during deserialization
public class Note {
    public final String id;
    public String text;
    public String title; // Make public for Jackson
    public Status status; // Make public for Jackson

    // Default constructor for Jackson
    public Note() {
        this(null, null, null, Status.IDLE); // Will be populated by Jackson
    }

    public Note(String id, String title, String text) {
        this(id, title, text, Status.IDLE);
    }

    public Note(String id, String title, String text, Status status) {
        this.id = requireNonNull(id);
        this.title = requireNonNull(title);
        this.text = requireNonNull(text);
        this.status = requireNonNull(status);
    }

    // Getters for Jackson (if fields were private)
    public String getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    public String getTitle() {
        return title;
    }

    public Status getStatus() {
        return status;
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
