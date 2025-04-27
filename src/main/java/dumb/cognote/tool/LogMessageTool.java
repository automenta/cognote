package dumb.cognote.tool;


import dumb.cognote.Cog;
import dumb.cognote.Log;
import dumb.cognote.ProtocolConstants;
import dumb.cognote.Term;
import dumb.cognote.Tool;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static dumb.cognote.Log.error;

public class LogMessageTool implements Tool {

    private final Cog cog; // Need access to cog to assert UI action

    public LogMessageTool(Cog cog) {
        this.cog = cog;
    }

    @Override
    public String name() {
        return "log_message";
    }

    @Override
    public String description() {
        return "Logs a message to the system console/log and displays it in the UI. Use this for internal thoughts or debugging.";
    }

    @dev.langchain4j.agent.tool.Tool("Logs a message to the system console/log and displays it in the UI. Use this for internal thoughts or debugging.")
    public CompletableFuture<Void> execute(@dev.langchain4j.agent.tool.P("message") String message) {
        Log.message("LLM Tool Log: " + message);

        // Also assert a UI action to display the message in the UI
        // The message content needs to be JSON-escaped if it contains quotes or other special characters
        // For simplicity, we'll just put it directly into a JSON object and convert that to a string atom.
        var uiActionData = new JSONObject().put("message", message).toString();
        var uiActionTerm = new Term.Lst(
                Term.Atom.of(ProtocolConstants.PRED_UI_ACTION),
                Term.Atom.of(ProtocolConstants.UI_ACTION_DISPLAY_MESSAGE),
                Term.Atom.of(uiActionData) // Store JSON string in an atom
        );

        // Assert the UI action into the dedicated KB
        var potentialAssertion = new dumb.cognote.Assertion.PotentialAssertion(
                uiActionTerm,
                Cog.INPUT_ASSERTION_BASE_PRIORITY, // Use a base priority
                java.util.Set.of(), // No justifications needed for a UI action
                "tool:log_message", // Source
                false, false, false, // Not equality, not negated
                ProtocolConstants.KB_UI_ACTIONS, // Target KB for UI actions
                dumb.cognote.Logic.AssertionType.GROUND, // UI actions are ground facts
                List.of(), // No quantified variables
                0 // Derivation depth 0
        );
        cog.context.tryCommit(potentialAssertion, "tool:log_message");


        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> parameters) {
        var message = (String) parameters.get("message");
        if (message == null) {
            error("LogMessageTool requires a 'message' parameter.");
            return CompletableFuture.failedFuture(new ToolExecutionException("Missing 'message' parameter."));
        }

        return execute(message);
    }
}
