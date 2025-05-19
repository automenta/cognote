package dumb.cogthought;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;
import dumb.cogthought.util.Log;
import dumb.cogthought.Event;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import static dumb.cogthought.util.Log.error;
import static dumb.cogthought.util.Log.message;

// This class will be refactored into a minimal LLMService interface and implementation
public class LM {
    static final String DEFAULT_LLM_URL = "http://localhost:11434";
    static final String DEFAULT_LLM_MODEL = "hf.co/bartowski/Meta-Llama-3.1-8B-Instruct-GGUF:Q8_0";
    static final int HTTP_TIMEOUT_SECONDS = 90;
    public final Map<String, CompletableFuture<?>> activeLlmTasks = new ConcurrentHashMap<>(); // Keep for now, task management will be refactored

    private final Cog cog; // Keep for now, will be replaced by ToolContext
    public volatile String llmApiUrl;
    public volatile String llmModel;
    private volatile ChatLanguageModel chatModel; // Keep for now, implementation detail
    private volatile LlmService llmService; // Keep for now, implementation detail

    public LM(Cog cog) {
        this.cog = cog;
    }

    // This method will be part of the LLMService implementation
    void reconfigure() {
        try {
            var baseUrl = llmApiUrl;
            if (baseUrl == null || baseUrl.isBlank()) {
                error("LLM Reconfiguration skipped: API URL is not set.");
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
            message(String.format("LM ChatModel reconfigured: Base URL=%s, Model=%s%s", baseUrl, llmModel, (chatModel == null ? " (Failed)" : "")));

            if (this.chatModel == null) {
                this.llmService = null;
                return;
            }

            // This part needs significant refactoring - tools should not be directly accessed here
            var llmCallableTools = cog.tools.getLlmCallableTools().toList();

            this.llmService = AiServices.builder(LlmService.class)
                    .chatLanguageModel(this.chatModel)
                    .tools(llmCallableTools)
                    .build();

            message("LM AiService configured with " + llmCallableTools.size() + " LLM-callable tools.");

        } catch (Exception e) {
            error("Failed to reconfigure LLM or AiService: " + e.getMessage());
            e.printStackTrace();
            this.chatModel = null;
            this.llmService = null;
        }
    }

    // This method will be part of the LLMService interface
    public CompletableFuture<AiMessage> llmAsync(String taskId, List<ChatMessage> history, String interactionType, String noteId) {
        if (llmService == null) {
            var errorMsg = interactionType + " Error: LLM Service not configured.";
            // Event emission will be handled differently, likely via ToolContext
            cog.events.emit(new Event.TaskUpdateEvent(taskId, Cog.TaskStatus.ERROR, errorMsg));
            return CompletableFuture.failedFuture(new IllegalStateException(errorMsg));
        }

        var conversationHistory = new ArrayList<>(history);

        var taskFuture = CompletableFuture.supplyAsync(() -> {
            // Cog dependency needs removal
            cog.waitIfPaused();
            // Event emission will be handled differently, likely via ToolContext
            cog.events.emit(new Event.TaskUpdateEvent(taskId, Cog.TaskStatus.PROCESSING, interactionType + ": Sending to LLM Service..."));

            try {
                var m = llmService.chat(conversationHistory);
                // Event emission will be handled differently, likely via ToolContext
                cog.events.emit(new Event.TaskUpdateEvent(taskId, Cog.TaskStatus.DONE, interactionType + ": Received final response."));
                return m;
            } catch (Exception e) {
                var cause = (e instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : e;
                if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
                error("LLM Service interaction error (" + interactionType + "): " + cause.getMessage());
                cause.printStackTrace();
                throw new CompletionException("LLM Service interaction error (" + interactionType + "): " + cause.getMessage(), cause);
            }
        }, cog.mainExecutor); // Executor dependency needs removal/replacement

        activeLlmTasks.put(taskId, taskFuture); // Task management needs refactoring

        taskFuture.whenComplete((result, error) -> {
            activeLlmTasks.remove(taskId); // Task management needs refactoring
            if (error != null) {
                var cause = (error instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : error;
                // Event emission will be handled differently, likely via ToolContext
                cog.events.emit(new Event.TaskUpdateEvent(taskId, Cog.TaskStatus.ERROR, interactionType + " Error: " + cause.getMessage()));
            }
        });


        return taskFuture;
    }

    // This interface will be the public LLMService interface
    interface LlmService {
        AiMessage chat(List<ChatMessage> messages);
    }
}
