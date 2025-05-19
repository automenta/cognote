package dumb.cogthought;

import dumb.cogthought.util.Events;

import java.util.concurrent.ExecutorService; // Added import

/**
 * This interface provides context and access to core system components for Tools.
 */
public interface ToolContext {
    KnowledgeBase getKnowledgeBase();
    LLMService getLlmService();
    ApiGateway getApiGateway();
    Events getEvents();
    // Add access to the shared executor service
    ExecutorService getExecutor(); // Added method
}
