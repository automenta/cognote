package dumb.cogthought;

import dumb.cogthought.util.Log;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.requireNonNull;
import static dumb.cogthought.util.Log.error;
import static dumb.cogthought.util.Log.info;

/**
 * Implementation of the ToolRegistry interface.
 * Manages registration and retrieval of Tool implementations.
 * Currently loads Primitive Tools registered in Java.
 * Will be extended to load ToolDefinitions from the KnowledgeBase.
 */
public class ToolRegistryImpl implements ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final KnowledgeBase knowledgeBase; // Keep reference if needed for loading ToolDefinitions from KB

    public ToolRegistryImpl(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = requireNonNull(knowledgeBase);
        info("ToolRegistry initialized.");
    }

    /**
     * Registers a Tool implementation with the registry.
     * This is primarily used for registering Primitive Tools defined in Java.
     *
     * @param tool The Tool instance to register.
     */
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

    /**
     * Retrieves a Tool implementation by its name.
     *
     * @param name The name of the tool.
     * @return An Optional containing the Tool if found, otherwise empty.
     */
    @Override
    public Optional<Tool> getTool(String name) {
        // TODO: Add logic here to first check registered Java tools,
        // then attempt to load and instantiate a ToolDefinition from the KB
        // if the name is not found among registered tools.
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * Lists all registered Tools.
     *
     * @return A collection of all registered Tools.
     */
    @Override
    public Collection<Tool> getAllTools() {
        // TODO: Include tools loaded from KB ToolDefinitions in the future
        return tools.values();
    }

    // TODO: Implement method for loading ToolDefinitions from the KnowledgeBase
    // Tool definitions in the KB would describe non-primitive tools that are
    // implemented as compositions of rules and primitive tools.
    // The ToolRegistry would need to know how to "execute" such a definition,
    // likely by asserting a task term into the KB that the TermLogicEngine processes.
    // void loadToolDefinitionsFromKB();
}
