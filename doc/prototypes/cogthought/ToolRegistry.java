package dumb.cogthought;

import java.util.Collection;
import java.util.Optional;

/**
 * Minimal interface for managing and retrieving Tools.
 * Tools are executable capabilities defined in the Knowledge Base.
 */
public interface ToolRegistry {

    /**
     * Registers a Tool implementation with the registry.
     * This is primarily for registering Primitive Tools defined in Java.
     *
     * @param tool The Tool instance to register.
     */
    void registerTool(Tool tool);

    /**
     * Retrieves a Tool implementation by its name.
     *
     * @param name The name of the tool.
     * @return An Optional containing the Tool if found, otherwise empty.
     */
    Optional<Tool> getTool(String name);

    /**
     * Lists all registered Tools.
     *
     * @return A collection of all registered Tools.
     */
    Collection<Tool> getAllTools();

    // TODO: Add methods for loading ToolDefinitions from the KnowledgeBase
    // void loadToolDefinitionsFromKB();
}
