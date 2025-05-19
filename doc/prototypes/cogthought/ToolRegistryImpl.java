package dumb.cogthought;

import dumb.cogthought.tool.LogMessageTool; // Import primitive tool classes
import dumb.cogthought.tool._AssertTool;
import dumb.cogthought.tool._RetractTool;
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
    private final KnowledgeBase knowledgeBase;

    // Map from toolName (from KB ToolDefinition) to the Java Primitive Tool instance
    private final Map<String, Tool> primitiveToolInstances = new ConcurrentHashMap<>();

    public ToolRegistryImpl(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = requireNonNull(knowledgeBase);
        info("ToolRegistry initialized.");
        // Primitive tools are registered in Cog.java, not here directly.
        // This map will be populated by Cog.registerPrimitiveTools.
    }

    /**
     * Registers a Tool implementation with the registry.
     * This is primarily for registering Primitive Tools defined in Java.
     * Called by Cog during initialization.
     *
     * @param tool The Tool instance to register.
     */
    @Override
    public void registerTool(Tool tool) {
        String name = tool.name();
        if (primitiveToolInstances.containsKey(name)) {
            error("Primitive Tool with name '" + name + "' already registered.");
            throw new IllegalArgumentException("Primitive Tool with name '" + name + "' already registered.");
        }
        primitiveToolInstances.put(name, tool);
        info("Registered primitive tool: " + name);
    }

    /**
     * Retrieves a Tool implementation by its name.
     * First checks registered Java primitive tools, then attempts to find
     * a ToolDefinition Note in the KB.
     *
     * @param name The name of the tool.
     * @return An Optional containing the Tool if found, otherwise empty.
     */
    @Override
    public Optional<Tool> getTool(String name) {
        // 1. Check if it's a registered Java Primitive Tool
        Tool primitiveTool = primitiveToolInstances.get(name);
        if (primitiveTool != null) {
            return Optional.of(primitiveTool);
        }

        // 2. Check the Knowledge Base for a ToolDefinition Note
        // TODO: Implement loading ToolDefinitions from KB in Phase 3
        // This would involve querying for Notes of type 'ToolDefinition'
        // with metadata indicating the tool name.
        // For now, this only supports Java Primitive Tools registered in Cog.
        info("Tool '" + name + "' not found among registered primitives. KB lookup not yet implemented.");
        return Optional.empty();
    }

    /**
     * Lists all registered Tools.
     * Currently only lists Java Primitive Tools.
     *
     * @return A collection of all registered Tools.
     */
    @Override
    public Collection<Tool> getAllTools() {
        // TODO: Include tools loaded from KB ToolDefinitions in the future
        return primitiveToolInstances.values();
    }

    // TODO: Implement method for loading ToolDefinitions from the KnowledgeBase (Phase 3)
    // Tool definitions in the KB would describe non-primitive tools that are
    // implemented as compositions of rules and primitive tools.
    // The ToolRegistry would need to know how to "execute" such a definition,
    // likely by asserting a task term into the KB that the TermLogicEngine processes.
    // void loadToolDefinitionsFromKB();
}
