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
    public static final String ID_PREFIX_PLUGIN = "plugin:";
    public static final String ID_PREFIX_TOOL = "tool:";
    public static final String ID_PREFIX_AGENT = "agent:";
    public static final String ID_PREFIX_ASSERTION = "assertion:";
    public static final String ID_PREFIX_RULE = "rule:";
    public static final double DERIVED_PRIORITY_DECAY = 0.9;

    private final KnowledgeBase knowledgeBase;
    private final TermLogicEngine termLogicEngine;
    private final ToolRegistry toolRegistry;
    private final LLMService llmService;
    private final ApiGateway apiGateway;
    // private final SystemControl systemControl;

    public final Events events;

    private Configuration config;

    private final AtomicLong idCounter = new AtomicLong(System.currentTimeMillis());

    public Cog(String persistencePath) {
        ExecutorService kernelExecutor = Executors.newCachedThreadPool();

        events = new Events(kernelExecutor);

        Persistence persistence = new FilePersistence(persistencePath);
        knowledgeBase = new KnowledgeBaseImpl(persistence);

        Log.setKnowledgeBase(knowledgeBase);
        events.setKnowledgeBase(knowledgeBase); // Set KB dependency for Events

        toolRegistry = new ToolRegistryImpl(knowledgeBase);

        llmService = new LLMServiceImpl(kernelExecutor);

        apiGateway = new ApiGatewayImpl(knowledgeBase);

        // systemControl = new SystemControl(knowledgeBase, termLogicEngine, toolRegistry, apiGateway, llmService, events);

        // Load configuration early to configure LLM service
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

        // Create ToolContext instance (can be reused or created per task)
        ToolContext kernelToolContext = new ToolContext() {
            @Override public KnowledgeBase getKnowledgeBase() { return knowledgeBase; }
            @Override public LLMService getLlmService() { return llmService; }
            @Override public ApiGateway getApiGateway() { return apiGateway; }
            @Override public Events getEvents() { return events; }
        };

        registerPrimitiveTools(kernelToolContext); // Pass context to tool registration

        termLogicEngine = new TermLogicEngine(knowledgeBase, toolRegistry, llmService, apiGateway, events);

        info("Cog initialized.");
    }

    private void registerPrimitiveTools(ToolContext context) {
        info("Registering Primitive Tools...");
        toolRegistry.registerTool(new LogMessageTool(knowledgeBase));
        // Register other primitive tools here, passing the context
        // toolRegistry.registerTool(new AssertTool(context)); // Example
        // toolRegistry.registerTool(new RetractTool(context)); // Example
        // toolRegistry.registerTool(new QueryKBTool(context)); // Example
        // toolRegistry.registerTool(new CallLLMTool(context)); // Needs LLMService from context
        // toolRegistry.registerTool(new SendApiMessageTool(context)); // Needs ApiGateway from context
        // toolRegistry.registerTool(new AskUserTool(context)); // Needs ApiGateway/Dialogue from context
        info("Primitive Tools registration complete.");
    }

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
        // TODO: Update other system behavior based on new config (e.g., executor pool size)
        info("Applied new configuration.");
    }

    public static Note createDefaultConfigNote(Configuration defaultConfig) {
        Note configNote = new Note(
            CONFIG_NOTE_ID,
            "Configuration",
            "System Configuration",
            "Contains system settings.",
            Note.Status.IDLE.name(),
            null, null, Instant.now(),
            new HashMap<>(Map.of(
                "persistenceFilePath", defaultConfig.persistenceFilePath(),
                "globalKbCapacity", defaultConfig.globalKbCapacity(),
                "llmApiUrl", defaultConfig.llmApiUrl(),
                "llmModel", defaultConfig.llmModel(),
                "llmTemperature", defaultConfig.llmTemperature(),
                "llmTimeoutSeconds", defaultConfig.llmTimeoutSeconds(),
                "concurrency", defaultConfig.concurrency()
            )),
            List.of(), List.of()
        );
        return configNote;
    }

    private Configuration loadConfigFromNote(Note configNote) {
        Map<String, Object> metadata = configNote.metadata();
        String persistenceFilePath = (String) metadata.getOrDefault("persistenceFilePath", "data/kb");
        int globalKbCapacity = (Integer) metadata.getOrDefault("globalKbCapacity", 10000);
        String llmApiUrl = (String) metadata.getOrDefault("llmApiUrl", "http://localhost:11434");
        String llmModel = (String) metadata.getOrDefault("llmModel", "gpt-4o-mini");
        double llmTemperature = (Double) metadata.getOrDefault("llmTemperature", 0.7);
        int llmTimeoutSeconds = (Integer) metadata.getOrDefault("llmTimeoutSeconds", 90);
        int concurrency = (Integer) metadata.getOrDefault("concurrency", 4);

        Configuration loadedConfig = new Configuration(persistenceFilePath, globalKbCapacity, llmApiUrl, llmModel, llmTemperature, llmTimeoutSeconds, concurrency);
        info("Loaded configuration from KB note: " + loadedConfig);
        return loadedConfig;
    }

    public static String id(String prefix) {
        return prefix + UUID.randomUUID();
    }

    public record Configuration(
            String persistenceFilePath,
            int globalKbCapacity,
            String llmApiUrl,
            String llmModel,
            double llmTemperature,
            int llmTimeoutSeconds,
            int concurrency
    ) {
        public Configuration() {
            this("data/kb", 10000, "http://localhost:11434", "gpt-4o-mini", 0.7, 90, 4);
        }
    }

    public static void main(String[] args) {
        info("Starting Cog...");
        Cog cog = new Cog("data/kb");
        // cog.systemControl.start();
        info("Cog started.");

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            error("Cog interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        }
    }
}
