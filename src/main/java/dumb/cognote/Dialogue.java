package dumb.cognote;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.concurrent.*;

import static dumb.cognote.Log.error;
import static dumb.cognote.Log.message;
import static java.util.Optional.ofNullable;

public class Dialogue {

    private final CogNote cog;

    private final ConcurrentMap<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>(); // Use JsonNode

    private final long requestTimeoutSeconds = 60;

    public Dialogue(CogNote cog) {
        this.cog = cog;
    }

    public CompletableFuture<JsonNode> request(String dialogueId, String requestType, String prompt, JsonNode options, JsonNode context) { // Use JsonNode
        if (pending.containsKey(dialogueId)) {
            error("Dialogue request with ID " + dialogueId + " already pending.");
            return CompletableFuture.failedFuture(new IllegalStateException("Dialogue request with ID " + dialogueId + " already pending."));
        }

        var future = new CompletableFuture<JsonNode>(); // Use JsonNode
        pending.put(dialogueId, future);

        cog.events.emit(new Events.DialogueRequestEvent(dialogueId, requestType, prompt, options, context)); // Use Events.DialogueRequestEvent

        cog.mainExecutor.schedule(() -> {
            if (pending.remove(dialogueId) != null) {
                error("Dialogue request " + dialogueId + " timed out.");
                future.completeExceptionally(new TimeoutException("Dialogue request timed out."));
            }
        }, requestTimeoutSeconds, TimeUnit.SECONDS);

        return future;
    }

    public Optional<CompletableFuture<JsonNode>> handleResponse(String dialogueId, JsonNode responseData) { // Use JsonNode
        return ofNullable(pending.remove(dialogueId))
                .map(future -> {
                    future.complete(responseData);
                    message("Dialogue response received for ID " + dialogueId);
                    return future;
                });
    }

    public void cancel(String dialogueId) {
        ofNullable(pending.remove(dialogueId))
                .ifPresent(future -> {
                    future.cancel(true);
                    message("Dialogue request " + dialogueId + " cancelled.");
                });
    }

    public void clear() {
        pending.forEach((id, future) -> future.cancel(true));
        pending.clear();
        message("Cleared all pending dialogue requests.");
    }

    // DialogueRequestEvent moved to Events.java
}
