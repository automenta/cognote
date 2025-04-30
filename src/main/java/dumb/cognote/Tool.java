package dumb.cognote;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface Tool {

    String name();

    String description();

    CompletableFuture<?> execute(Map<String, Object> parameters);

    class ToolExecutionException extends RuntimeException {
        public ToolExecutionException(String msg) {
            super(msg);
        }
    }
}
