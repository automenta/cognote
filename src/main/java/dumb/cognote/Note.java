package dumb.cognote;

import org.json.JSONObject;

import static java.util.Objects.requireNonNull;

public class Note {
    public final String id;
    public String text;
    String title;
    Status status;

    public Note(String id, String title, String text) {
        this(id, title, text, Status.IDLE);
    }

    public Note(String id, String title, String text, Status status) {
        this.id = requireNonNull(id);
        this.title = requireNonNull(title);
        this.text = requireNonNull(text);
        this.status = requireNonNull(status);
    }

    public Note withStatus(Status newStatus) {
        return new Note(this.id, this.title, this.text, newStatus);
    }

    public JSONObject toJson() {
        return new JSONObject()
                .put("type", "note")
                .put("id", id)
                .put("title", title)
                .put("text", text)
                .put("status", status.name());
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
