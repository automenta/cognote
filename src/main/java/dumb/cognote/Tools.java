package dumb.cognote;

import dumb.cognote.tools.BaseTool;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class Tools {
    private final Map<String, BaseTool> tools = new ConcurrentHashMap<>();

    public void register(BaseTool tool) {
        var n = tool.name();
        if (tools.containsKey(n))
            throw new IllegalArgumentException("Tool with name '" + n + "' already registered.");

        tools.put(n, tool);
        System.out.println("Registered tool: " + n);
    }

    public Optional<BaseTool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Collection<BaseTool> getAll() {
        return tools.values();
    }

    public Collection<BaseTool> getLlmCallableTools() {
        return tools.values().stream()
                .filter(tool -> {
                    try {
                        for (var method : tool.getClass().getDeclaredMethods()) {
                            if (method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class))
                                return true;
                        }
                        return false;
                    } catch (Exception e) {
                        System.err.println("Error reflecting on tool class " + tool.getClass().getName() + " for @Tool annotation: " + e.getMessage());
                        return false;
                    }
                })
                .toList();
    }
}
