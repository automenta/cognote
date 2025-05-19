package dumb.cogthought;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal interface for interacting with a Language Model.
 * This service is intended to be used by Primitive Tools (like _CallLLMTool).
 */
public interface LLMService {

    /**
     * Reconfigures the LLM service connection and settings.
     *
     * @param baseUrl The base URL of the LLM API.
     * @param modelName The name of the LLM model to use.
     * @param temperature The temperature setting for the LLM.
     * @param timeoutSeconds The timeout for LLM calls in seconds.
     */
    void reconfigure(String baseUrl, String modelName, double temperature, int timeoutSeconds);

    /**
     * Sends a list of chat messages to the LLM for completion asynchronously.
     *
     * @param messages The list of chat messages representing the conversation history.
     * @return A CompletableFuture that will complete with the AI's response message.
     */
    CompletableFuture<AiMessage> chatAsync(List<ChatMessage> messages);

    // TODO: Add methods for other LLM interactions if needed (e.g., embeddings, tool calls)
}
