package dumb.cognote;

import org.json.JSONObject;

import java.util.Optional;
import java.util.concurrent.*;

import static dumb.cognote.Log.error;
import static dumb.cognote.Log.message;
import static java.util.Optional.ofNullable;

public class Dialogue {

    private final CogNote cog;

    private final ConcurrentMap<String, CompletableFuture<JSONObject>> pending = new ConcurrentHashMap<>();

    private final long requestTimeoutSeconds = 60;

    public Dialogue(CogNote cog) {
        this.cog = cog;
    }

    public CompletableFuture<JSONObject> request(String dialogueId, String requestType, String prompt, JSONObject options, JSONObject context) {
        if (pending.containsKey(dialogueId)) {
            error("Dialogue request with ID " + dialogueId + " already pending.");
            return CompletableFuture.failedFuture(new IllegalStateException("Dialogue request with ID " + dialogueId + " already pending."));
        }

        var future = new CompletableFuture<JSONObject>();
        pending.put(dialogueId, future);

        cog.events.emit(new DialogueRequestEvent(dialogueId, requestType, prompt, options, context));

        cog.mainExecutor.schedule(() -> {
            if (pending.remove(dialogueId) != null) {
                error("Dialogue request " + dialogueId + " timed out.");
                future.completeExceptionally(new TimeoutException("Dialogue request timed out."));
            }
        }, requestTimeoutSeconds, TimeUnit.SECONDS);

        return future;
    }

    public Optional<CompletableFuture<JSONObject>> handleResponse(String dialogueId, JSONObject responseData) {
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

    public record DialogueRequestEvent(String dialogueId, String requestType, String prompt, JSONObject options,
                                       JSONObject context) implements Cog.CogEvent {

        public JSONObject toJson() {
            return new JSONObject()
                    .put("type", "event")
                    .put("eventType", "DialogueRequestEvent")
                    .put("eventData", new JSONObject()
                            .put("dialogueId", dialogueId)
                            .put("requestType", requestType)
                            .put("prompt", prompt)
                            .put("options", options)
                            .put("context", context));
        }
    }
}
