package dumb.cog.tool;

import dumb.cog.Cog;
import dumb.cog.Event;
import dumb.cog.Logic;
import dumb.cog.Tool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static dumb.cog.util.Log.error;

public class RetractTool implements Tool {

    private final Cog cog;

    public RetractTool(Cog cog) {
        this.cog = cog;
    }

    @Override
    public String name() {
        return "retract_assertion";
    }

    @Override
    public String description() {
        return "Retracts an assertion from the knowledge base by its ID.";
    }

    @dev.langchain4j.agent.tool.Tool("Retracts an assertion from the knowledge base by its ID.")
    public CompletableFuture<String> retract(@dev.langchain4j.agent.tool.P("assertion_id") String assertionId) {
        return CompletableFuture.supplyAsync(() -> {
            if (assertionId == null || assertionId.isBlank()) {
                throw new ToolExecutionException("Assertion ID is required.");
            }
            cog.events.emit(new Event.RetractionRequestEvent(assertionId, Logic.RetractionType.BY_ID, "tool:retract_assertion", null));
            return "Retraction requested for assertion ID: " + assertionId;
        }, cog.events.exe);
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> parameters) {
        var assertionId = (String) parameters.get("assertion_id");

        if (assertionId == null || assertionId.isBlank()) {
            error("RetractTool requires an 'assertion_id' parameter.");
            return CompletableFuture.failedFuture(new ToolExecutionException("Missing 'assertion_id' parameter."));
        }

        return retract(assertionId);
    }
}
