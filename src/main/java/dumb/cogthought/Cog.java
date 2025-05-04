package dumb.cogthought;

import dumb.cogthought.persistence.FilePersistence;
import dumb.cogthought.tool.LogMessageTool; // Example Primitive Tool
import dumb.cogthought.util.Events;
import dumb.cogthought.util.Log;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static dumb.cogthought.util.Log.error;
import static dumb.cogthought.util.Log.info;

// Refactored from dumb.cognote.Cog to be the minimal entry point
public class Cog {

    // --- Constants (potentially moved to KB Ontology later) ---
    public static final String GLOBAL_KB_NOTE_ID = "global-kb";
    public static final String GLOBAL_KB_NOTE_TITLE = "Global Knowledge Base";
    public static final String CONFIG_NOTE_ID = "system-config";
    public static final String ID_PREFIX_PLUGIN = "plugin:"; // Still needed for plugin IDs? Or are plugins also KB data?
    public static final String ID_PREFIX_TOOL = "tool:"; // Used for tool IDs/names
    public static final String ID_PREFIX_AGENT = "agent:"; // Used for agent IDs?
    public static final String ID_PREFIX_ASSERTION = "assertion:"; // Used for assertion IDs
    public static final String ID_PREFIX_RULE = "rule:"; // Used for rule IDs
    public static final double DERIVED_PRIORITY_DECAY = 0.9; // Example decay factor

    // --- Core Components (Minimal Kernel) ---
    private final KnowledgeBase knowledgeBase; // The central KB
    private final TermLogicEngine termLogicEngine; // The generic interpreter
    private final ToolRegistry toolRegistry; // Manages tool implementations
    // private final ApiGateway apiGateway; // To be implemented
    // private final SystemControl systemControl; // To be implemented

    // Keep for now, will be refactored or removed
    public final Events events; // Event bus, asserts into KB
    // public final ConcurrentMap<String, Note> notes = new ConcurrentHashMap<>(); // Removed: Notes managed by KB

    // Old components to be removed or refactored into the above
    // private final JVM jvm; // Refactored into a Tool?
    // private final ScheduledExecutorService executor; // Managed by SystemControl?
    // private final Map<String, Agent> agents; // Managed by SystemControl/KB?
    // private final Map<Object, ScheduledFuture<?>> scheduledTasks; // Managed by SystemControl?
    // private final LM lm; // Refactored into LLMService used by a Tool?
    // private final Tools tools; // Refactored into ToolRegistry?
    // private final Persist persist; // Removed, replaced by Persistence interface used by KB
    // private final Cognition cognition; // Removed

    // Configuration (potentially loaded from KB)
    private Configuration config;

    // Unique ID generator (potentially moved to KB or SystemControl)
    private final AtomicLong idCounter = new AtomicLong(System.currentTimeMillis());

    public Cog(String persistencePath) {
        // Initialize core components
        // Events needs to assert into KB, but KB needs Events for status updates.
        // This is a circular dependency issue.
        // Temporary solution: Initialize Events first, then KB, then set KB on Events/Log.
        // A better solution might involve passing a KB provider or using a message queue.
        this.events = new Events(Executors.newCachedThreadPool()); // Events will assert into KB

        // Initialize Persistence and KnowledgeBase
        var persistence = new FilePersistence(persistencePath);
        this.knowledgeBase = new KnowledgeBaseImpl(persistence);

        // Set KB dependency for Log and Events
        Log.setKnowledgeBase(this.knowledgeBase);
        // events.setKnowledgeBase(this.knowledgeBase); // Need to add setKnowledgeBase to Events

        // Initialize ToolRegistry
        this.toolRegistry = new ToolRegistryImpl(knowledgeBase);

        // Register Primitive Tools (defined in Java)
        registerPrimitiveTools();

        // Initialize TermLogicEngine
        this.termLogicEngine = new TermLogicEngine(knowledgeBase, toolRegistry);

        // Initialize ApiGateway (to be implemented)
        // this.apiGateway = new ApiGateway(knowledgeBase);

        // Initialize SystemControl (to be implemented)
        // this.systemControl = new SystemControl(knowledgeBase, termLogicEngine, toolRegistry, apiGateway);

        // Load configuration from KB or use default
        this.config = knowledgeBase.loadNote(CONFIG_NOTE_ID)
                                   .map(this::loadConfigFromNote)
                                   .orElseGet(() -> {
                                       info("Config note not found. Using default configuration.");
                                       var defaultConfig = new Configuration();
                                       // Create and save default config note
                                       var configNote = createDefaultConfigNote(defaultConfig);
                                       knowledgeBase.saveNote(configNote);
                                       return defaultConfig;
                                   });

        // Old initialization logic removed/refactored:
        // this.jvm = new JVM();
        // this.executor = Executors.newScheduledThreadPool(config.concurrency); // Use config
        // this.agents = new ConcurrentHashMap<>();
        // this.scheduledTasks = new ConcurrentHashMap<>();
        // this.lm = new LM(this);
        // this.tools = new Tools(this);
        // this.cognition = new Cognition(config.globalKbCapacity, new Truths.BasicTMS(events), this); // Old KB/TMS
        // this.persist = new Persist(this, cognition, cognition.truth); // Old persistence

        // Load initial state (now handled by KnowledgeBaseImpl constructor)
        // persist.load(config.persistenceFilePath);

        // Start SystemControl loop (to be implemented)
        // systemControl.start();

        info("Cog initialized.");
    }

    // Register Java-defined Primitive Tools
    private void registerPrimitiveTools() {
        info("Registering Primitive Tools...");
        toolRegistry.registerTool(new LogMessageTool(knowledgeBase)); // Example tool
        // Register other primitive tools here...
        // toolRegistry.registerTool(new AssertTool(knowledgeBase)); // Example
        // toolRegistry.registerTool(new RetractTool(knowledgeBase)); // Example
        // toolRegistry.registerTool(new QueryKBTool(knowledgeBase)); // Example
        // toolRegistry.registerTool(new CallLLMTool(...)); // Needs LLMService
        // toolRegistry.registerTool(new SendApiMessageTool(...)); // Needs ApiGateway
        // toolRegistry.registerTool(new AskUserTool(...)); // Needs ApiGateway/Dialogue
        info("Primitive Tools registration complete.");
    }


    // --- Public API (Minimal, interacts with KB/SystemControl) ---

    public KnowledgeBase getKnowledgeBase() {
        return knowledgeBase;
    }

    public TermLogicEngine getTermLogicEngine() {
        return termLogicEngine;
    }

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    // Placeholder for getting notes - Notes should be queried from KB
    // This method is temporary and should be removed once UI/API uses KB directly
    public Collection<Note> getAllNotes() {
        // This is inefficient; should be replaced by KB query/streaming
        List<Note> allNotes = new ArrayList<>();
        knowledgeBase.listNoteIds().forEach(id -> knowledgeBase.loadNote(id).ifPresent(allNotes::add));
        return allNotes;
    }

    // Placeholder for getting config - Config should be queried from KB
    // This method is temporary and should be removed once config is fully KB-driven
    public Configuration getConfig() {
        // Reload config from KB note if it might have changed externally?
        // For now, return the loaded config.
        return config;
    }

    // Placeholder for applying config - Config updates should be done by updating the KB note
    // This method is temporary and should be removed
    public void applyConfig(Configuration newConfig) {
        this.config = newConfig;
        // TODO: Update system behavior based on new config (e.g., executor pool size, LM settings)
        // This logic should ideally be driven by rules reacting to config note changes in the KB.
        info("Applied new configuration.");
    }

    // Placeholder for creating default config note - Should be part of KB bootstrap
    // This method is temporary
    public static Note createDefaultConfigNote(Configuration defaultConfig) {
        var configNote = new Note(
            CONFIG_NOTE_ID,
            "Configuration", // Type defined in Ontology
            "System Configuration",
            "Contains system settings.",
            Note.Status.IDLE.name(), // Status defined in Ontology
            null, null, Instant.now(),
            new HashMap<>(Map.of(
                "persistenceFilePath", defaultConfig.persistenceFilePath(),
                "globalKbCapacity", defaultConfig.globalKbCapacity(), // This might become irrelevant
                "llmModel", defaultConfig.llmModel(),
                "llmTemperature", defaultConfig.llmTemperature(),
                "concurrency", defaultConfig.concurrency()
                // Add other default settings here
            )),
            List.of(), List.of()
        );
        return configNote;
    }

    // Placeholder for loading config from note - Should be part of KB bootstrap/SystemControl
    // This method is temporary
    private Configuration loadConfigFromNote(Note configNote) {
        var metadata = configNote.metadata();
        var persistenceFilePath = (String) metadata.getOrDefault("persistenceFilePath", "data/kb");
        var globalKbCapacity = (Integer) metadata.getOrDefault("globalKbCapacity", 10000); // Might be ignored
        var llmModel = (String) metadata.getOrDefault("llmModel", "gpt-4o-mini");
        var llmTemperature = (Double) metadata.getOrDefault("llmTemperature", 0.7);
        var concurrency = (Integer) metadata.getOrDefault("concurrency", 4);

        var loadedConfig = new Configuration(persistenceFilePath, globalKbCapacity, llmModel, llmTemperature, concurrency);
        info("Loaded configuration from KB note: " + loadedConfig);
        return loadedConfig;
    }


    // --- Utility Methods (potentially moved or refactored) ---

    public static String id(String prefix) {
        // Simple ID generation, potentially replaced by a more robust KB-based ID service
        return prefix + UUID.randomUUID();
    }

    // Old methods removed:
    // public JVM jvm() { return jvm; }
    // public ScheduledExecutorService executor() { return executor; }
    // public Map<String, Agent> agents() { return agents; }
    // public Map<Object, ScheduledFuture<?>> scheduledTasks() { return scheduledTasks; }
    // public LM lm() { return lm; }
    // public Tools tools() { return tools; }
    // public Persist persist() { return persist; }
    // public Cognition cognition() { return cognition; } // Removed

    // --- Configuration Record (potentially defined as KB Ontology/Schema) ---
    public record Configuration(
            String persistenceFilePath,
            int globalKbCapacity, // This might become irrelevant with generic KB
            String llmModel,
            double llmTemperature,
            int concurrency // For executor pool size, etc.
    ) {
        public Configuration() {
            this("data/kb", 10000, "gpt-4o-mini", 0.7, 4);
        }
    }

    // --- Main Method (Entry Point) ---
    public static void main(String[] args) {
        info("Starting Cog...");
        // The main method will initialize Cog and start the SystemControl loop
        // For now, just initialize Cog.
        var cog = new Cog("data/kb"); // Use default persistence path from config
        // cog.systemControl.start(); // Start the main loop
        info("Cog started.");

        // Keep the application running (temporary, SystemControl will manage lifecycle)
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            error("Cog interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        }
    }
}
