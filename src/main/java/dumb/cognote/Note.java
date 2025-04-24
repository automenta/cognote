package dumb.cognote;

import static java.util.Objects.requireNonNull;

public class Note {
    public final String id;
    public String text;
    String title;

    public Note(String id, String title, String text) {
        this.id = requireNonNull(id);
        this.title = requireNonNull(title);
        this.text = requireNonNull(text);
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
}
