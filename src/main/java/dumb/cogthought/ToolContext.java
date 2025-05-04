package dumb.cogthought;

import dumb.cogthought.util.Events;

// This interface provides context and access to core system components for Tools
public interface ToolContext {
    KnowledgeBase getKnowledgeBase();
    LLMService getLlmService();
    ApiGateway getApiGateway();
    Events getEvents();
}
