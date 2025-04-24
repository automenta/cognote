package dumb.cognote.tool;

import dumb.cognote.Tool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class LogMessageTool implements Tool {

    @Override
    public String name() {
        return "log_message";
    }

    @Override
    public String description() {
        return "Logs a message to the system console. Input is a JSON object with 'message' (string).";
    }

    @Override
    public CompletableFuture<Object> execute(Map<String, Object> parameters) {
        var message = (String) parameters.get("message");
        return CompletableFuture.supplyAsync(() -> {
            if (message != null) {
                System.out.println("TOOL LOG: " + message);
                return "Message logged.";
            } else {
                System.err.println("TOOL LOG: Received empty message parameter.");
                return "Error: Missing 'message' parameter.";
            }
        }); // Can run on a common pool or event executor
    }
}
