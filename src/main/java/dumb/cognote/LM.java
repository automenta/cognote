package dumb.cognote;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;


public class LM {
    public static final double LLM_ASSERTION_BASE_PRIORITY = 15.0;
    static final String DEFAULT_LLM_URL = "http://localhost:11434"; // Base URL for Ollama
    static final String DEFAULT_LLM_MODEL = "hf.co/mradermacher/phi-4-GGUF:Q4_K_S";
    public final Map<String, CompletableFuture<?>> activeLlmTasks = new ConcurrentHashMap<>();
    // Removed final Tools tools; // No longer needed

    private final Cog cog; // Keep reference to Cog
    volatile String llmApiUrl; // Now base URL
    volatile String llmModel;
    private volatile ChatLanguageModel chatModel;
    private volatile LlmService llmService; // The AI Service interface instance

    public LM(Cog cog) { // Constructor takes Cog
        this.cog = cog;
        // Initial configuration will happen via loadNotesAndConfig -> updateConfig -> reconfigure
    }

    void reconfigure() {
        try {
            var baseUrl = llmApiUrl;
            if (baseUrl == null || baseUrl.isBlank()) {
                System.err.println("LLM Reconfiguration skipped: API URL is not set.");
                this.chatModel = null;
                this.llmService = null;
                return;
            }
            if (baseUrl.endsWith("/api/chat")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - "/api/chat".length());
            } else if (baseUrl.endsWith("/api")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - "/api".length());
            }

            this.chatModel = OllamaChatModel.builder()
                    .baseUrl(baseUrl)
                    .modelName(llmModel)
                    .temperature(0.2)
                    .timeout(Duration.ofSeconds(Cog.HTTP_TIMEOUT_SECONDS))
                    .build();
            System.out.printf("LM ChatModel reconfigured: Base URL=%s, Model=%s%s%n", baseUrl, llmModel, (chatModel == null ? " (Failed)" : ""));

            if (this.chatModel == null) {
                this.llmService = null;
                return;
            }

            // Get LLM-callable tools from the registry
            // AiServices.builder().tools() can accept a list of objects containing @Tool methods
            // or a list of ToolSpecification.
            // Let's pass the list of BaseTool instances that have @Tool methods.
            // AiServices will reflect on these instances.
            var llmCallableTools = cog.toolRegistry().getLlmCallableTools().stream().toList();


            this.llmService = AiServices.builder(LlmService.class)
                    .chatLanguageModel(this.chatModel)
                    .tools(llmCallableTools.toArray()) // Pass the instances of LLM-callable tools
                    //.chatMemory() // Optional: Add memory for multi-turn conversations managed by AiServices
                    .build();
            System.out.println("LM AiService configured with " + llmCallableTools.size() + " LLM-callable tools.");

        } catch (Exception e) {
            System.err.println("Failed to reconfigure LLM or AiService: " + e.getMessage());
            e.printStackTrace();
            this.chatModel = null;
            this.llmService = null;
        }
    }

    // Simplified llmAsync: Delegates to AiService.chat()
    public CompletableFuture<dev.langchain4j.data.message.AiMessage> llmAsync(String taskId, List<dev.langchain4j.data.message.ChatMessage> history, String interactionType, String noteId) {
        if (llmService == null) {
            var errorMsg = interactionType + " Error: LLM Service not configured.";
            cog.updateLlmItemStatus(taskId, UI.LlmStatus.ERROR, errorMsg);
            return CompletableFuture.failedFuture(new IllegalStateException(errorMsg));
        }

        var systemMessage = dev.langchain4j.data.message.SystemMessage.from("""
                You are an intelligent agent interacting with a knowledge base system.
                Your primary goal is to assist the user by processing information, answering questions, or performing tasks using the available tools and your knowledge.
                Available tools have been provided to you. If a tool call is required to fulfill the request, use the tool(s). Otherwise, provide a direct text response.
                When using tools, output ONLY the tool call(s) in the specified format. Your response will be intercepted, the tool(s) executed, and the results provided back to you in the next turn.
                When providing a final answer or explanation after any necessary tool use, output plain text.
                """);

        var conversationHistory = new ArrayList<dev.langchain4j.data.message.ChatMessage>();
        conversationHistory.add(systemMessage);
        conversationHistory.addAll(history);


        return CompletableFuture.supplyAsync(() -> {
            cog.waitIfPaused();
            cog.updateLlmItemStatus(taskId, UI.LlmStatus.PROCESSING, interactionType + ": Sending to LLM Service...");

            try {
                var finalAiMessage = llmService.chat(conversationHistory);

                cog.updateLlmItemStatus(taskId, UI.LlmStatus.DONE, interactionType + ": Received final response.");
                return finalAiMessage;

            } catch (Exception e) {
                var cause = (e instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : e;
                if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
                System.err.println("LLM Service interaction error (" + interactionType + "): " + cause.getMessage());
                cause.printStackTrace();
                cog.updateLlmItemStatus(taskId, UI.LlmStatus.ERROR, interactionType + " Error: " + cause.getMessage());
                throw new CompletionException("LLM Service interaction error (" + interactionType + "): " + cause.getMessage(), cause);
            }
        }, cog.events.exe);
    }

    // Remove all specific LLM action methods (enhanceNoteWithLlmAsync, summarizeNoteWithLlmAsync, etc.)

    // Define the AI Service Interface
    interface LlmService {
        dev.langchain4j.data.message.AiMessage chat(List<dev.langchain4j.data.message.ChatMessage> messages);
    }
}
