package dumb.cog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Note {
    private final String id;
    public String text;
    public float pri;
    public String color;
    public long updated;
    String title;
    Status status;

    public Note(String id, String title, String text, Status status) {
        this.id = id;
        this.title = title;
        this.text = text;
        this.status = status;
    }

    public Note(String id, String title, String text) {
        this(id, title, text, Status.IDLE);
    }

    public Note withStatus(Status newStatus) {
        return new Note(this.id, this.title, this.text, newStatus);
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

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String text() {
        return text;
    }

    public Status status() {
        return status;
    }

    public enum Status {
        IDLE, ACTIVE, PAUSED, COMPLETED
    }
}
