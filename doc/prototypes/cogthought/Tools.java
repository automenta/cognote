package dumb.cogthought;

import dev.langchain4j.agent.tool.Tool;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static dumb.cogthought.util.Log.error;
import static dumb.cogthought.util.Log.message;

// This class will be refactored into the ToolRegistry
public class Tools {
    private final Map<String, dumb.cogthought.Tool> tools = new ConcurrentHashMap<>();

    // Keep for now, tool registration will be refactored
    public void add(dumb.cogthought.Tool tool) {
        var n = tool.name();
        if (tools.containsKey(n))
            throw new IllegalArgumentException("Tool with name '" + n + "' already registered.");

        tools.put(n, tool);
        message("Registered tool: " + n);
    }

    // Keep for now, tool lookup will be refactored
    public Optional<dumb.cogthought.Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    // Keep for now, tool listing will be refactored
    public Collection<dumb.cogthought.Tool> getAll() {
        return tools.values();
    }

    // This method is specific to LangChain4j integration and will be refactored or removed
    public Stream<dumb.cogthought.Tool> getLlmCallableTools() {
        return tools.values().stream().filter(tool -> {
            try {
                for (var method : tool.getClass().getDeclaredMethods()) {
                    if (method.isAnnotationPresent(Tool.class))
                        return true;
                }
                return false;
            } catch (Exception e) {
                error("Error reflecting on tool class " + tool.getClass().getName() + " for @Tool annotation: " + e.getMessage());
                return false;
            }
        });
    }
}
