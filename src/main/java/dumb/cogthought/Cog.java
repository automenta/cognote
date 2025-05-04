package dumb.cogthought;

import dumb.cogthought.persistence.FilePersistence;
import dumb.cogthought.tool.LogMessageTool; // Example Primitive Tool
import dumb.cogthought.tool._AssertTool; // New Primitive Tool
import dumb.cogthought.tool._RetractTool; // New Primitive Tool
import dumb.cogthought.util.Events;
import dumb.cogthought.util.Log;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
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

public class Cog {

    public static final String GLOBAL_KB_NOTE_ID = "global-kb";
    public static final String GLOBAL_KB_NOTE_TITLE = "Global Knowledge Base";
    public static final String CONFIG_NOTE_ID = "system-config";
    public static final String ID_PREFIX_PLUGIN = "plugin:"; // Keep for now, might be refactored
    public static final String ID_PREFIX_TOOL = "tool:"; // Keep for now, might be refactored
    public static final String ID_PREFIX_AGENT = "agent:"; // Keep for now, might be refactored
    public static final String ID_PREFIX_ASSERTION = "assertion:";
    public static final String ID_PREFIX_RULE = "rule:";
    public static final double DERIVED_PRIORITY_DECAY = 0.9; // Keep for now, will be config/rule driven

    private final KnowledgeBase knowledgeBase;
    private final TermLogicEngine termLogicEngine;
    private final ToolRegistry toolRegistry;
    private final LLMService llmService; // Use LLMService interface
    private final ApiGateway apiGateway;
    private final SystemControl systemControl;

    public final Events events;
    private final ExecutorService kernelExecutor; // Keep reference to the shared executor

    private Configuration config;

    private final AtomicLong idCounter = new AtomicLong(System.currentTimeMillis()); // Keep for now, might be refactored

    public Cog(String persistencePath) {
        // Use a shared executor for kernel tasks (KB, Tools, LLM, Events)
        kernelExecutor = Executors.newCachedThreadPool(); // Initialize the executor

        // Initialize core components
        events = new Events(kernelExecutor);

        Persistence persistence = new FilePersistence(persistencePath);
        knowledgeBase = new KnowledgeBaseImpl(persistence, kernelExecutor); // Pass executor to KB

        // Set KB dependency for Log and Events
        Log.setKnowledgeBase(knowledgeBase); // Log now asserts via KB
        events.setKnowledgeBase(knowledgeBase); // Events now asserts via KB

        toolRegistry = new ToolRegistryImpl(knowledgeBase);

        llmService = new LLMServiceImpl(kernelExecutor); // Use LLMServiceImpl implementing the interface

        apiGateway = new ApiGatewayImpl(knowledgeBase);

        // Load configuration early to configure LLM service and other components
        config = knowledgeBase.loadNote(CONFIG_NOTE_ID)
                                   .map(this::loadConfigFromNote)
                                   .orElseGet(() -> {
                                       info("Config note not found. Using default configuration.");
                                       Configuration defaultConfig = new Configuration();
                                       Note configNote = createDefaultConfigNote(defaultConfig);
                                       knowledgeBase.saveNote(configNote);
                                       return defaultConfig;
                                   });

        // Reconfigure LLM service based on loaded config
        llmService.reconfigure(config.llmApiUrl(), config.llmModel(), config.llmTemperature(), config.llmTimeoutSeconds());

        // Create ToolContext instance (can be reused or created per task/tool execution)
        // This context provides tools with access to other core components.
        ToolContext kernelToolContext = new ToolContext() {
            @Override public KnowledgeBase getKnowledgeBase() { return knowledgeBase; }
            @Override public LLMService getLlmService() { return llmService; }
            @Override public ApiGateway getApiGateway() { return apiGateway; }
            @Override public Events getEvents() { return events; }
            @Override public ExecutorService getExecutor() { return kernelExecutor; } // Provide the shared executor
        };

        registerPrimitiveTools(kernelToolContext); // Register core Java tools

        termLogicEngine = new TermLogicEngine(knowledgeBase, toolRegistry, llmService, apiGateway, events);

        // Initialize SystemControl - the main loop manager
        systemControl = new SystemControl(knowledgeBase, termLogicEngine, toolRegistry, apiGateway, llmService, events);

        info("Cog initialized.");
    }

    /**
     * Registers the minimal set of Java-implemented Primitive Tools.
     * These tools perform atomic operations not expressible purely in rules.
     *
     * @param context The ToolContext to provide to the tools.
     */
    private void registerPrimitiveTools(ToolContext context) {
        info("Registering Primitive Tools...");
        toolRegistry.registerTool(new LogMessageTool(knowledgeBase)); // Already existed
        toolRegistry.registerTool(new _AssertTool(context)); // New Assert tool
        toolRegistry.registerTool(new _RetractTool(context)); // New Retract tool
        // TODO: Register other primitive tools here (_QueryKBTool, _CallLLMTool, _SendApiMessageTool, _AskUserTool, etc.)
        info("Primitive Tools registration complete.");
    }

    // --- Public Getters for Core Components ---
    // These allow external components (like a WebSocket server) to interact with Cog.

    public KnowledgeBase getKnowledgeBase() {
        return knowledgeBase;
    }

    public TermLogicEngine getTermLogicEngine() {
        return termLogicEngine;
    }

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    public LLMService getLlmService() {
        return llmService;
    }

    public ApiGateway getApiGateway() {
        return apiGateway;
    }

    public SystemControl getSystemControl() {
        return systemControl;
    }

    public Events getEvents() {
        return events;
    }

    public ExecutorService getKernelExecutor() { return kernelExecutor; } // Added getter for executor

    // --- Configuration Management ---

    public Collection<Note> getAllNotes() {
        List<Note> allNotes = new ArrayList<>();
        knowledgeBase.listNoteIds().forEach(id -> knowledgeBase.loadNote(id).ifPresent(allNotes::add));
        return allNotes;
    }

    public Configuration getConfig() {
        return config;
    }

    public void applyConfig(Configuration newConfig) {
        config = newConfig;
        llmService.reconfigure(config.llmApiUrl(), config.llmModel(), config.llmTemperature(), config.llmTimeoutSeconds());
        // TODO: Update other system behavior based on new config (e.g., SystemControl polling interval, executor pool size)
        info("Applied new configuration.");
    }

    public static Note createDefaultConfigNote(Configuration defaultConfig) {
        Note configNote = new Note(
            CONFIG_NOTE_ID,
            "Configuration", // Note type (placeholder, needs Ontology)
            "System Configuration",
            "Contains system settings.",
            Note.Status.IDLE.name(), // Status (placeholder, needs Ontology)
            null, null, Instant.now(),
            new HashMap<>(Map.of(
                "persistenceFilePath", defaultConfig.persistenceFilePath(),
                "globalKbCapacity", defaultConfig.globalKbCapacity(),
                "llmApiUrl", defaultConfig.llmApiUrl(),
                "llmModel", defaultConfig.llmModel(),
                "llmTemperature", defaultConfig.llmTemperature(),
                "llmTimeoutSeconds", defaultConfig.llmTimeoutSeconds(),
                "concurrency", defaultConfig.concurrency() // Concurrency might affect executor config
            )),
            List.of(), List.of()
        );
        return configNote;
    }

    private Configuration loadConfigFromNote(Note configNote) {
        Map<String, Object> metadata = configNote.metadata();
        // Use safe casting and default values
        String persistenceFilePath = (String) metadata.getOrDefault("persistenceFilePath", "data/kb");
        int globalKbCapacity = ((Number) metadata.getOrDefault("globalKbCapacity", 10000)).intValue(); // Ensure int casting
        String llmApiUrl = (String) metadata.getOrDefault("llmApiUrl", "http://localhost:11434");
        String llmModel = (String) metadata.getOrDefault("llmModel", "gpt-4o-mini");
        // Need to handle potential Double vs Integer from JSON
        double llmTemperature = ((Number) metadata.getOrDefault("llmTemperature", 0.7)).doubleValue();
        int llmTimeoutSeconds = ((Number) metadata.getOrDefault("llmTimeoutSeconds", 90)).intValue();
        int concurrency = ((Number) metadata.getOrDefault("concurrency", 4)).intValue();


        Configuration loadedConfig = new Configuration(persistenceFilePath, globalKbCapacity, llmApiUrl, llmModel, llmTemperature, llmTimeoutSeconds, concurrency);
        info("Loaded configuration from KB note: " + loadedConfig);
        return loadedConfig;
    }

    // --- Utility Methods ---

    public static String id(String prefix) {
        return prefix + UUID.randomUUID();
    }

    // --- Configuration Record ---
    // This record defines the structure of system configuration.
    // It should align with the metadata stored in the config Note.
    public record Configuration(
            String persistenceFilePath,
            int globalKbCapacity,
            String llmApiUrl,
            String llmModel,
            double llmTemperature,
            int llmTimeoutSeconds,
            int concurrency
    ) {
        // Default constructor
        public Configuration() {
            this("data/kb", 10000, "http://localhost:11434", "gpt-4o-mini", 0.7, 90, 4);
        }
    }

    // --- Main Entry Point ---

    public static void main(String[] args) {
        info("Starting Cog...");
        // Initialize Cog with persistence path (can be passed as arg)
        Cog cog = new Cog("data/kb");

        // Start the main system control loop
        cog.systemControl.start();

        info("Cog started. SystemControl loop is running.");

        // Keep the main thread alive
        try {
            // TODO: Implement a proper shutdown hook
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            error("Cog main thread interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            // Ensure SystemControl is stopped on shutdown
            cog.systemControl.stop();
            // Shutdown the kernel executor
            cog.kernelExecutor.shutdown();
            try {
                if (!cog.kernelExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    cog.kernelExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                error("Kernel executor shutdown interrupted.");
                cog.kernelExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            info("Cog stopped.");
        }
    }
}
