package dumb.cognote.tools;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Base interface for all tools in the system.
 * Tools can be callable by the LLM (if annotated with @Tool) or invoked internally.
 */
public interface BaseTool {

    /**
     * Gets the unique name of the tool. Used for lookup in the registry and by the LLM.
     * @return The tool name.
     */
    String name();

    /**
     * Gets a description of the tool's purpose and usage. Used by the LLM.
     * @return The tool description.
     */
    String description();

    /**
     * Executes the tool's action.
     * @param parameters A map of parameters for the tool execution.
     * @return A CompletableFuture representing the asynchronous execution result.
     *         The type of the result depends on the tool (e.g., String, List, Void).
     */
    CompletableFuture<Object> execute(Map<String, Object> parameters);
}
