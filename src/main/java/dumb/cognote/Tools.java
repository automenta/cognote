package dumb.cognote;

import dumb.cognote.tools.BaseTool;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tool registry
 */
public class Tools {
    private final Map<String, BaseTool> tools = new ConcurrentHashMap<>();

    /**
     * Registers a tool with the registry.
     *
     * @param tool The tool to register.
     * @throws IllegalArgumentException if a tool with the same name is already registered.
     */
    public void register(BaseTool tool) {
        var n = tool.name();
        if (tools.containsKey(n))
            throw new IllegalArgumentException("Tool with name '" + n + "' already registered.");

        tools.put(n, tool);
        System.out.println("Registered tool: " + n);
    }

    /**
     * Retrieves a tool by its name.
     *
     * @param name The name of the tool.
     * @return An Optional containing the tool if found, otherwise empty.
     */
    public Optional<BaseTool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * Gets all registered tools.
     *
     * @return A collection of all registered tools.
     */
    public Collection<BaseTool> getAll() {
        return tools.values();
    }

    /**
     * Gets all registered tools that are callable by the LLM (i.e., have an @Tool annotation
     * on their execute method). This requires reflection.
     *
     * @return A collection of LLM-callable tools.
     */
    public Collection<BaseTool> getLlmCallableTools() {
        return tools.values().stream()
                .filter(tool -> {
                    try {
                        // Check if the execute method has the LangChain4j @Tool annotation
                        // This is a bit of a hack, ideally BaseTool would have a marker interface
                        // or a method to indicate LLM callability.
                        // Assuming dev.langchain4j.agent.tool.Tool is available.
                        // We need to check for a method annotated with @Tool that LangChain4j can call.
                        // In our design, the BaseTool.execute(Map) method is for internal calls.
                        // The @Tool annotated method is a separate method (e.g., addKifAssertionToolMethod).
                        // We need to find any method in the tool's class that has the @Tool annotation.
                        for (var method : tool.getClass().getDeclaredMethods()) {
                            if (method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                                return true; // Found an LLM-callable method
                            }
                        }
                        return false; // No LLM-callable method found
                    } catch (Exception e) {
                        System.err.println("Error reflecting on tool class " + tool.getClass().getName() + " for @Tool annotation: " + e.getMessage());
                        return false;
                    }
                })
                .toList();
    }
}
