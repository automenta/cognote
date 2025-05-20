package dumb.note;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.empty;

public class LM {
    private static final Logger logger = LoggerFactory.getLogger(LM.class);
    private final Netention.Config.LMSettings cfg;
    private EmbeddingModel embedding;
    private ChatLanguageModel chat;
    private volatile boolean isInitialized = false, isReady = false;

    public LM(Netention.Config cs) {
        this.cfg = cs.lm;
    }

    public static double cosineSimilarity(float[] vA, float[] vB) {
        if (vA == null || vB == null || vA.length == 0 || vA.length != vB.length) return 0.0;
        double dotProduct = 0.0, normA = 0.0, normB = 0.0;
        for (var i = 0; i < vA.length; i++) {
            dotProduct += vA[i] * vB[i];
            normA += vA[i] * vA[i];
            normB += vB[i] * vB[i];
        }
        return (normA == 0 || normB == 0) ? 0.0 : dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public synchronized void init() {
        var currentProvider = (embedding instanceof OllamaEmbeddingModel) ? "OLLAMA" : (embedding == null && chat == null ? "NONE" : "UNKNOWN");
        if (isInitialized && isReady && cfg.provider.equalsIgnoreCase(currentProvider)) return;

        isInitialized = false;
        isReady = false;
        logger.info("Initializing LLMService with provider: {}", cfg.provider);
        try {
            switch (cfg.provider.toUpperCase()) {
                case "OLLAMA" -> {
                    embedding = OllamaEmbeddingModel.builder().baseUrl(cfg.ollamaBaseUrl).modelName(cfg.ollamaEmbeddingModelName).timeout(Duration.ofSeconds(60)).build();
                    chat = OllamaChatModel.builder().baseUrl(cfg.ollamaBaseUrl).modelName(cfg.ollamaChatModelName).timeout(Duration.ofSeconds(120)).build();
                }
                default -> {
                    logger.info("LLM provider NONE/unsupported. LLM features disabled.");
                    embedding = null;
                    chat = null;
                    isInitialized = true;
                    return;
                }
            }
            isReady = true;
            logger.info("LLMService initialized successfully for provider: {}", cfg.provider);
        } catch (Exception e) {
            logger.error("Failed to initialize LLM provider {}: {}. LLM features disabled.", cfg.provider, e.getMessage(), e);
            embedding = null;
            chat = null;
        }
        isInitialized = true;
    }

    public boolean isReady() {
        if (!isInitialized) init();
        return isReady;
    }

    public Optional<float[]> generateEmbedding(String t) {
        if (!isReady()) {
            logger.warn("LLM not ready, cannot gen embedding.");
            return empty();
        }
        try {
            return Optional.of(embedding.embed(t).content().vector());
        } catch (Exception e) {
            logger.error("Error gen embedding: {}", e.getMessage(), e);
            return empty();
        }
    }

    public Optional<String> chat(String p) {
        if (!isReady()) {
            logger.warn("LLM not ready, cannot chat.");
            return empty();
        }
        try {
            return Optional.of(chat.chat(p));
        } catch (Exception e) {
            logger.error("Error during chat: {}", e.getMessage(), e);
            return empty();
        }
    }

    public Optional<String> summarize(String t) {
        return (t == null || t.trim().isEmpty()) ? Optional.of("") : chat("Summarize concisely:\n\n" + t);
    }

    public Optional<String> askAboutText(String t, String q) {
        return (t == null || t.trim().isEmpty() || q == null || q.trim().isEmpty()) ? empty() : chat("Context:\n\"\"\"\n" + t + "\n\"\"\"\n\nQuestion: " + q + "\nAnswer:");
    }

    public Optional<List<String>> decomposeTask(String task) {
        return (task == null || task.trim().isEmpty()) ? empty() :
                chat("Decompose this goal into a sequence of actionable sub-tasks. Prefix each sub-task with '- '. If the goal is simple, return just one task. Be concise.\nGoal: " + task)
                        .map(r -> Stream.of(r.split("\\n")).map(String::trim).filter(s -> s.startsWith("- ")).map(s -> s.substring(2).trim()).filter(s -> !s.isEmpty()).collect(Collectors.toList()));
    }
}
