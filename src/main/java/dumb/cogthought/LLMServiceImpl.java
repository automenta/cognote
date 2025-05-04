package dumb.cogthought;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;
import dumb.cogthought.util.Log;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

import static java.util.Objects.requireNonNull;
import static dumb.cogthought.util.Log.error;
import static dumb.cogthought.util.Log.info;

/**
 * Implementation of the LLMService interface using LangChain4j and Ollama.
 */
public class LLMServiceImpl implements LLMService {
    private volatile ChatLanguageModel chatModel;
    private volatile LlmService aiService; // Internal LangChain4j service
    private final ExecutorService executor;

    public LLMServiceImpl(ExecutorService executor) {
        this.executor = requireNonNull(executor);
        info("LLMService initialized.");
    }

    @Override
    public void reconfigure(String baseUrl, String modelName, double temperature, int timeoutSeconds) {
        try {
            if (baseUrl == null || baseUrl.isBlank()) {
                error("LLM Reconfiguration skipped: API URL is not set.");
                chatModel = null;
                aiService = null;
                return;
            }
            String cleanedBaseUrl = baseUrl;
            // Clean up common LangChain4j Ollama base URL suffixes
            if (cleanedBaseUrl.endsWith("/api/chat")) cleanedBaseUrl = cleanedBaseUrl.substring(0, cleanedBaseUrl.length() - "/api/chat".length());
            else if (cleanedBaseUrl.endsWith("/api")) cleanedBaseUrl = cleanedBaseUrl.substring(0, cleanedBaseUrl.length() - "/api".length());

            chatModel = OllamaChatModel.builder()
                    .baseUrl(cleanedBaseUrl)
                    .modelName(modelName)
                    .temperature(temperature)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();
            info(String.format("LLM ChatModel reconfigured: Base URL=%s, Model=%s%s", cleanedBaseUrl, modelName, (chatModel == null ? " (Failed)" : "")));

            if (chatModel == null) {
                aiService = null;
                return;
            }

            // Note: LangChain4j AiServices can bind tools. This LLMService implementation
            // does NOT bind tools here. Tool execution triggered by LLM function calls
            // should be handled by a dedicated Primitive Tool (_CallLLMTool) which
            // would receive the tool call request from the LLM response and delegate
            // execution back to the TermLogicEngine/ToolRegistry.
            aiService = AiServices.builder(LlmService.class)
                    .chatLanguageModel(chatModel)
                    // .tools(...) // Do NOT bind tools here
                    .build();

            info("LLM AiService configured.");

        } catch (Exception e) {
            error("Failed to reconfigure LLM or AiService: " + e.getMessage());
            e.printStackTrace();
            chatModel = null;
            aiService = null;
        }
    }

    @Override
    public CompletableFuture<AiMessage> chatAsync(List<ChatMessage> messages) {
        if (aiService == null) {
            String errorMsg = "LLM Service not configured.";
            error(errorMsg);
            return CompletableFuture.failedFuture(new IllegalStateException(errorMsg));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                // TODO: Add system pause/wait logic here if needed, similar to old Cog.waitIfPaused()
                return aiService.chat(messages);
            } catch (Exception e) {
                Throwable cause = (e instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : e;
                if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
                error("LLM Service interaction error: " + cause.getMessage());
                cause.printStackTrace();
                throw new CompletionException("LLM Service interaction error: " + cause.getMessage(), cause);
            }
        }, executor);
    }

    // Internal interface used by LangChain4j AiServices
    interface LlmService {
        AiMessage chat(List<ChatMessage> messages);
    }
}
