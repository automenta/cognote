package dumb.cogthought;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Note {
    public final String id;
    public final String type;
    public String title; // Made mutable as per original Note
    public String text; // Made mutable as per original Note
    public String status; // Made mutable as per original Note
    public Integer priority; // Made mutable as per original Note
    public String color; // Made mutable as per original Note
    public Instant updated; // Made mutable as per original Note
    public Map<String, Object> metadata; // Made mutable for updates
    public List<Relationship> graph; // Made mutable for updates
    public List<Term> associatedTerms; // Made mutable for updates

    // Constructor matching the new fields
    public Note(String id, String type, String title, String text, String status, Integer priority, String color, Instant updated, Map<String, Object> metadata, List<Relationship> graph, List<Term> associatedTerms) {
        this.id = id;
        this.type = type;
        this.title = title;
        this.text = text;
        this.status = status;
        this.priority = priority;
        this.color = color;
        this.updated = updated;
        this.metadata = metadata;
        this.graph = graph;
        this.associatedTerms = associatedTerms;
    }

    // Minimal constructor for compatibility with existing code creating Notes
    // This will need to be refactored later to use the full constructor or a builder
    public Note(String id, String title, String text, Status status) {
         this(id, "Note", title, text, status.name(), null, null, Instant.now(), Map.of(), List.of(), List.of());
    }

    // Enum Status kept for compatibility, will likely be replaced by Ontology terms
    public enum Status {
        IDLE, ACTIVE, PAUSED, COMPLETED
    }

    // Getters for the fields (as requested by "Add necessary imports")
    public String getId() { return id; }
    public String getType() { return type; }
    public String getTitle() { return title; }
    public String getText() { return text; }
    public String getStatus() { return status; }
    public Integer getPriority() { return priority; }
    public String getColor() { return color; }
    public Instant getUpdated() { return updated; }
    public Map<String, Object> getMetadata() { return metadata; }
    public List<Relationship> getGraph() { return graph; }
    public List<Term> getAssociatedTerms() { return associatedTerms; }

    // Keep equals and hashCode based on ID
    @Override
    public boolean equals(Object o) {
        return o instanceof Note n && id.equals(n.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return title;
    }
}
