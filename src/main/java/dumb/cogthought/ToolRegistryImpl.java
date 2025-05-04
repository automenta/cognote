package dumb.cogthought;

import dumb.cogthought.util.Log;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.requireNonNull;
import static dumb.cogthought.util.Log.error;
import static dumb.cogthought.util.Log.info;

public class ToolRegistryImpl implements ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final KnowledgeBase knowledgeBase;

    public ToolRegistryImpl(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = requireNonNull(knowledgeBase);
        info("ToolRegistry initialized.");
    }

    @Override
    public void registerTool(Tool tool) {
        String name = tool.name();
        if (tools.containsKey(name)) {
            error("Tool with name '" + name + "' already registered.");
            throw new IllegalArgumentException("Tool with name '" + name + "' already registered.");
        }
        tools.put(name, tool);
        info("Registered tool: " + name);
    }

    @Override
    public Optional<Tool> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public Collection<Tool> getAllTools() {
        return tools.values();
    }
}
