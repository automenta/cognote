package dumb.cogthought;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

// This interface defines the contract for Tools, which are primitive actions
public interface Tool {

    String name();

    String description();

    // Updated signature to use Term parameters and ToolContext
    CompletableFuture<?> execute(Term parameters, ToolContext context);

    class ToolExecutionException extends RuntimeException {
        public ToolExecutionException(String msg) {
            super(msg);
        }
        public ToolExecutionException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
