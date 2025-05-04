package dumb.cogthought;

import dumb.cogthought.util.Log;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static dumb.cogthought.util.Log.error;
import static dumb.cogthought.util.Log.info;

// Refactored from dumb.cognote.Tools to implement ToolRegistry
public class ToolRegistryImpl implements ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final KnowledgeBase knowledgeBase; // Dependency for loading ToolDefinitions

    public ToolRegistryImpl(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
        // TODO: Load ToolDefinitions from KB on initialization (Phase 2)
        // loadToolDefinitionsFromKB();
        info("ToolRegistry initialized.");
    }

    @Override
    public void registerTool(Tool tool) {
        var name = tool.name();
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

    // TODO: Implement loading ToolDefinitions from KB (Phase 2)
    // private void loadToolDefinitionsFromKB() {
    //     info("Loading ToolDefinitions from KnowledgeBase...");
    //     // Example: Query KB for Notes of type 'ToolDefinition'
    //     // knowledgeBase.queryNotes(Term.Lst.of(Term.Atom.of("isa"), Term.Var.of("?tool"), Term.Atom.of("ToolDefinition")))
    //     //              .forEach(toolDefinitionNote -> {
    //     //                  try {
    //     //                      // Parse tool definition from note metadata/text
    //     //                      // Create a proxy Tool instance or register implementation
    //     //                      // registerTool(new ProxyTool(toolDefinitionNote)); // Example
    //     //                  } catch (Exception e) {
    //     //                      error("Failed to load tool definition from note " + toolDefinitionNote.id() + ": " + e.getMessage());
    //     //                  }
    //     //              });
    //     info("ToolDefinition loading complete (placeholder).");
    // }
}
