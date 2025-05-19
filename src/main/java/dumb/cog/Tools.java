package dumb.cog;

import dev.langchain4j.agent.tool.Tool;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static dumb.cog.util.Log.error;
import static dumb.cog.util.Log.message;

public class Tools {
    private final Map<String, dumb.cog.Tool> tools = new ConcurrentHashMap<>();

    public void add(dumb.cog.Tool tool) {
        var n = tool.name();
        if (tools.containsKey(n))
            throw new IllegalArgumentException("Tool with name '" + n + "' already registered.");

        tools.put(n, tool);
        message("Registered tool: " + n);
    }

    public Optional<dumb.cog.Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Collection<dumb.cog.Tool> getAll() {
        return tools.values();
    }

    public Stream<dumb.cog.Tool> getLlmCallableTools() {
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
