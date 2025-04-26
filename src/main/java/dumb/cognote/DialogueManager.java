package dumb.cognote;

import org.json.JSONObject;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static dumb.cognote.Log.error;
import static dumb.cognote.Log.message;
import static java.util.Objects.requireNonNull;

public class DialogueManager {

    private final Cog cog;
    private final ConcurrentMap<String, CompletableFuture<JSONObject>> pendingRequests = new ConcurrentHashMap<>();
    private final long requestTimeoutSeconds = 60;

    public DialogueManager(Cog cog) {
        this.cog = requireNonNull(cog);
    }

    public CompletableFuture<JSONObject> requestDialogue(String dialogueId, String requestType, String prompt, JSONObject options, JSONObject context) {
        if (pendingRequests.containsKey(dialogueId)) {
            error("Dialogue request with ID " + dialogueId + " already pending.");
            return CompletableFuture.failedFuture(new IllegalStateException("Dialogue request with ID " + dialogueId + " already pending."));
        }

        var future = new CompletableFuture<JSONObject>();
        pendingRequests.put(dialogueId, future);

        cog.events.emit(new Cog.DialogueRequestEvent(dialogueId, requestType, prompt, options, context));

        cog.mainExecutor.schedule(() -> {
            if (pendingRequests.remove(dialogueId) != null) {
                error("Dialogue request " + dialogueId + " timed out.");
                future.completeExceptionally(new TimeoutException("Dialogue request timed out."));
            }
        }, requestTimeoutSeconds, TimeUnit.SECONDS);

        return future;
    }

    public Optional<CompletableFuture<JSONObject>> handleDialogueResponse(String dialogueId, JSONObject responseData) {
        return Optional.ofNullable(pendingRequests.remove(dialogueId))
                .map(future -> {
                    future.complete(responseData);
                    message("Dialogue response received for ID " + dialogueId);
                    return future;
                });
    }

    public void cancelDialogue(String dialogueId) {
        Optional.ofNullable(pendingRequests.remove(dialogueId))
                .ifPresent(future -> {
                    future.cancel(true);
                    message("Dialogue request " + dialogueId + " cancelled.");
                });
    }

    public void clear() {
        pendingRequests.forEach((id, future) -> future.cancel(true));
        pendingRequests.clear();
        message("Cleared all pending dialogue requests.");
    }
}
