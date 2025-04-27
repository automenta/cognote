package dumb.cognote.tool;


import dumb.cognote.Log;
import dumb.cognote.Tool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static dumb.cognote.Log.error;

public class LogMessageTool implements Tool {
    @Override
    public String name() {
        return "log_message";
    }

    @Override
    public String description() {
        return "Logs a message to the system console/log. Use this for internal thoughts or debugging.";
    }

    @dev.langchain4j.agent.tool.Tool("Logs a message to the system console/log. Use this for internal thoughts or debugging.")
    public CompletableFuture<Void> execute(@dev.langchain4j.agent.tool.P("message") String message) {
        Log.message("LLM Tool Log: " + message);
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
