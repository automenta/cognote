package dumb.cognote;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
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
    static final String DEFAULT_LLM_URL = "http://localhost:11434";
    static final String DEFAULT_LLM_MODEL = "hf.co/bartowski/Meta-Llama-3.1-8B-Instruct-GGUF:Q8_0";
    static final int HTTP_TIMEOUT_SECONDS = 90;
    public final Map<String, CompletableFuture<?>> activeLlmTasks = new ConcurrentHashMap<>();

    private final Cog cog;
    volatile String llmApiUrl, llmModel;
    private volatile ChatLanguageModel chatModel;
    private volatile LlmService llmService;

    public LM(Cog cog) {
        this.cog = cog;
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
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .build();
            System.out.printf("LM ChatModel reconfigured: Base URL=%s, Model=%s%s%n", baseUrl, llmModel, (chatModel == null ? " (Failed)" : ""));

            if (this.chatModel == null) {
                this.llmService = null;
                return;
            }

            var llmCallableTools = cog.tools.getLlmCallableTools().stream().toList();

            this.llmService = AiServices.builder(LlmService.class)
                    .chatLanguageModel(this.chatModel)
                    .tools(llmCallableTools.toArray())
                    .build();
            System.out.println("LM AiService configured with " + llmCallableTools.size() + " LLM-callable tools.");

        } catch (Exception e) {
            System.err.println("Failed to reconfigure LLM or AiService: " + e.getMessage());
            e.printStackTrace();
            this.chatModel = null;
            this.llmService = null;
        }
    }

    public CompletableFuture<AiMessage> llmAsync(String taskId, List<ChatMessage> history, String interactionType, String noteId) {
        if (llmService == null) {
            var errorMsg = interactionType + " Error: LLM Service not configured.";
            cog.updateTaskStatus(taskId, Cog.TaskStatus.ERROR, errorMsg);
            return CompletableFuture.failedFuture(new IllegalStateException(errorMsg));
        }

        //        var systemMessage = dev.langchain4j.data.message.SystemMessage.from("""
//                You are an intelligent cognitive agent interacting with a semantic knowledge system.
//                Your primary goal is to assist the user by processing information, answering questions, or performing tasks using the available tools and your knowledge.
//                Available tools have been provided to you. If a tool call is required to fulfill the request, use the tool(s). Otherwise, provide a direct text response.
//                When using tools, output ONLY the tool call(s) in the specified format. Your response will be intercepted, the tool(s) executed, and the results provided back to you in the next turn.
//                When providing a final answer or explanation after any necessary tool use, output plain text.
//                """);
//        conversationHistory.add(systemMessage);

        var conversationHistory = new ArrayList<ChatMessage>(history);

        return CompletableFuture.supplyAsync(() -> {
            cog.waitIfPaused();
            cog.updateTaskStatus(taskId, Cog.TaskStatus.PROCESSING, interactionType + ": Sending to LLM Service...");

            try {

                var m = llmService.chat(conversationHistory);

                cog.updateTaskStatus(taskId, Cog.TaskStatus.DONE, interactionType + ": Received final response.");

                return m;

            } catch (Exception e) {
                var cause = (e instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : e;
                if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
                System.err.println("LLM Service interaction error (" + interactionType + "): " + cause.getMessage());
                cause.printStackTrace();
                cog.updateTaskStatus(taskId, Cog.TaskStatus.ERROR, interactionType + " Error: " + cause.getMessage());
                throw new CompletionException("LLM Service interaction error (" + interactionType + "): " + cause.getMessage(), cause);
            }
        }, cog.events.exe);
    }

    interface LlmService {
        AiMessage chat(List<ChatMessage> messages);
    }
}
