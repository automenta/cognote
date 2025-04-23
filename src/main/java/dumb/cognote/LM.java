package dumb.cognote;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;

import javax.swing.*;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import dumb.cognote.Tool;
import dev.langchain4j.model.tool.ToolExecutionRequest;
import dev.langchain4j.model.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatResponse;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;


public class LM {
    static final String DEFAULT_LLM_URL = "http://localhost:11434"; // Base URL for Ollama
    static final String DEFAULT_LLM_MODEL = "llama3";
    static final double LLM_ASSERTION_BASE_PRIORITY = 15.0;
    final Map<String, CompletableFuture<?>> activeLlmTasks = new ConcurrentHashMap<>();
    private final Cog cog;
    volatile String llmApiUrl; // Now base URL
    volatile String llmModel;

    private volatile ChatLanguageModel chatModel;
    final Tools tools; // Add the field for the Tools instance

    public LM(Cog cog) {
        this.cog = cog;
        // Initial configuration will happen via loadNotesAndConfig -> updateConfig
        this.tools = new Tools(cog); // Instantiate Tools here
    }

    void reconfigure() {
        try {
            // OllamaChatModel uses baseUrl, not the full /api/chat endpoint
            var baseUrl = llmApiUrl;
            if (baseUrl.endsWith("/api/chat")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - "/api/chat".length());
            } else if (baseUrl.endsWith("/api")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - "/api".length());
            }

            this.chatModel = OllamaChatModel.builder()
                    .baseUrl(baseUrl)
                    .modelName(llmModel)
                    .temperature(0.2) // Matches original temperature
                    .timeout(Duration.ofSeconds(Cog.HTTP_TIMEOUT_SECONDS))
                    .build();
            System.out.printf("LM reconfigured: Base URL=%s, Model=%s%n", baseUrl, llmModel);
        } catch (Exception e) {
            System.err.println("Failed to reconfigure LLM: " + e.getMessage());
            this.chatModel = null; // Ensure it's null if config fails
        }
    }

    // Modify the llmAsync method signature and implementation
    // Change return type to ChatResponse
    // Add List<ToolSpecification> parameter
    CompletableFuture<ChatResponse> llmAsync(String taskId, String prompt, String interactionType, String noteId, List<ToolSpecification> toolSpecifications) {
        if (chatModel == null) {
            var errorMsg = interactionType + " Error: LLM not configured.";
            // Update UI status for the task
            cog.updateLlmItemStatus(taskId, UI.LlmStatus.ERROR, errorMsg);
            return CompletableFuture.failedFuture(new IllegalStateException(errorMsg));
        }

        return CompletableFuture.supplyAsync(() -> {
            cog.waitIfPaused();
            cog.updateLlmItemStatus(taskId, UI.LlmStatus.PROCESSING, interactionType + ": Waiting for LLM...");

            try {
                // Pass tool specifications to the generate call
                ChatResponse response = chatModel.generate(prompt, toolSpecifications.toArray(new ToolSpecification[0]));

                // Update UI status based on response type (text or tool call)
                if (response.toolExecutionRequests() != null && !response.toolExecutionRequests().isEmpty()) {
                     cog.updateLlmItemStatus(taskId, UI.LlmStatus.PROCESSING, interactionType + ": Tool call requested...");
                } else if (response.content() != null && !response.content().isBlank()) {
                     cog.updateLlmItemStatus(taskId, UI.LlmStatus.PROCESSING, interactionType + ": Text response received...");
                } else {
                     cog.updateLlmItemStatus(taskId, UI.LlmStatus.DONE, interactionType + ": Empty response.");
                }

                return response;

            } catch (Exception e) {
                var cause = (e instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : e;
                if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
                // Update UI status for the task error
                cog.updateLlmItemStatus(taskId, UI.LlmStatus.ERROR, interactionType + " Error: " + cause.getMessage());
                throw new CompletionException("LLM API communication or processing error (" + interactionType + ")", cause);
            }
        }, cog.events.exe);
    }

    // Remove the following methods:
    // void handleLlmResponse(String taskId, String noteId, String interactionType, String kifPredicate, String response, Throwable ex, BiConsumer<String, String> successHandler)
    // void handleLlmKifResponse(String taskId, String noteId, String kifResult, Throwable ex)
    // void handleLlmGenericResponse(String taskId, String noteId, String interactionType, String response, Throwable ex, String kifPredicate)
    // void handleLlmEnhancementResponse(String taskId, String noteId, String response, Throwable ex)

    // The methods below (enhanceNoteWithLlmAsync, summarizeNoteWithLlmAsync, etc.)
    // currently call the old handleLlm...Response methods.
    // They will need to be updated in a future step to handle the ChatResponse
    // returned by the new llmAsync method. For now, they are left as-is but will
    // likely cause compilation errors or runtime issues until updated.
    // This is expected as per the request to defer the full integration.
    // You can comment them out temporarily if needed for compilation, but the
    // instruction is just to remove the handlers they call.

    public CompletableFuture<String> enhanceNoteWithLlmAsync(String taskId, Cog.Note n) {
        var finalPrompt = """
                You are a helpful assistant. Please revise and enhance the following note for clarity, conciseness, and improved structure. Keep the core meaning intact.
                Focus on improving readability and flow. Correct any grammatical errors or awkward phrasing.
                Output ONLY the revised note text, without any introductory or concluding remarks.

                Original Note:
                "%s"

                Enhanced Note:""".formatted(n.text);
        // This call needs to be updated to handle ChatResponse
        var future = llmAsync(taskId, finalPrompt, "Note Enhancement", n.id, List.of()); // No tools for this task yet
        activeLlmTasks.put(taskId, future);
        // This completion handler needs to be updated
        future.whenCompleteAsync((response, ex) -> { /* handle ChatResponse */ }, cog.events.exe);
        return future.thenApply(ChatResponse::content); // Placeholder: assumes text response
    }

    public CompletableFuture<String> summarizeNoteWithLlmAsync(String taskId, Cog.Note n) {
        var finalPrompt = """
                Summarize the following note in one or two concise sentences. Output ONLY the summary.

                Note:
                "%s"

                Summary:""".formatted(n.text);
        // This call needs to be updated to handle ChatResponse
        var future = llmAsync(taskId, finalPrompt, "Note Summarization", n.id, List.of()); // No tools for this task yet
        activeLlmTasks.put(taskId, future);
        // This completion handler needs to be updated
        future.whenCompleteAsync((response, ex) -> { /* handle ChatResponse */ }, cog.events.exe);
        return future.thenApply(ChatResponse::content); // Placeholder: assumes text response
    }

    public CompletableFuture<String> keyConceptsWithLlmAsync(String taskId, Cog.Note n) {
        var finalPrompt = """
                Identify the key concepts or entities mentioned in the following note. List them separated by newlines. Output ONLY the newline-separated list.

                Note:
                "%s"

                Key Concepts:""".formatted(n.text);
        // This call needs to be updated to handle ChatResponse
        var future = llmAsync(taskId, finalPrompt, "Key Concept Identification", n.id, List.of()); // No tools for this task yet
        activeLlmTasks.put(taskId, future);
        // This completion handler needs to be updated
        future.whenCompleteAsync((response, ex) -> { /* handle ChatResponse */ }, cog.events.exe);
        return future.thenApply(ChatResponse::content); // Placeholder: assumes text response
    }

    public CompletableFuture<String> generateQuestionsWithLlmAsync(String taskId, Cog.Note n) {
        var finalPrompt = """
                Based on the following note, generate 1-3 insightful questions that could lead to further exploration or clarification. Output ONLY the questions, each on a new line starting with '- '.

                Note:
                "%s"

                Questions:""".formatted(n.text);
        // This call needs to be updated to handle ChatResponse
        var future = llmAsync(taskId, finalPrompt, "Question Generation", n.id, List.of()); // No tools for this task yet
        activeLlmTasks.put(taskId, future);
        // This completion handler needs to be updated
        future.whenCompleteAsync((response, ex) -> { /* handle ChatResponse */ }, cog.events.exe);
        return future.thenApply(ChatResponse::content); // Placeholder: assumes text response
    }

    public CompletableFuture<String> text2kifAsync(String taskId, String noteText, String noteId) {
        var finalPrompt = """
                Convert the following note into a set of concise SUMO KIF assertions (standard Lisp-like syntax, e.g., (instance MyCat Cat)).
                Output ONLY the KIF assertions, each on a new line, enclosed in parentheses.
                Use standard SUMO predicates like 'instance', 'subclass', 'domain', 'range', 'attribute', 'partOf', etc.
                Use '=' for equality between terms. Use unique names for new entities derived from the note (start with uppercase, use CamelCase).
                Use '(not ...)' for negation where appropriate, e.g., (not (instance Pluto Planet)).
                Use '(forall (?X) (=> (instance ?X Dog) (attribute ?X Canine)))' for universal statements.
                Use '(exists (?Y) (and (instance ?Y Cat) (attribute ?Y BlackColor)))' for existential statements.
                Avoid trivial assertions like (instance X X) or (= X X) or (not (= X X)).
                Example: (instance Fluffy Cat) (attribute Fluffy OrangeColor) (= (age Fluffy) 3) (not (attribute Fluffy BlackColor)) (exists (?K) (instance ?K Kitten))

                Note:
                "%s"

                KIF Assertions:""".formatted(noteText);
        // This call needs to be updated to handle ChatResponse
        var future = llmAsync(taskId, finalPrompt, "KIF Generation", noteId, List.of()); // No tools for this task yet
        activeLlmTasks.put(taskId, future);
        // This completion handler needs to be updated
        future.whenCompleteAsync((kifResult, ex) -> { /* handle ChatResponse */ }, cog.events.exe);
        return future.thenApply(ChatResponse::content); // Placeholder: assumes text response
    }


    // Add the Tools nested static class definition
    static class Tools {
        private final Cog cog;
        private final Map<String, Method> toolMethods = new ConcurrentHashMap<>();

        Tools(Cog cog) {
            this.cog = cog;
            discoverTools();
        }

        private void discoverTools() {
            // Simple discovery: look for @Tool methods within this class
            for (Method method : Tools.class.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    Tool toolAnnotation = method.getAnnotation(Tool.class);
                    toolMethods.put(toolAnnotation.name(), method);
                    System.out.println("Discovered tool method: " + toolAnnotation.name() + " -> " + method.getName());
                }
            }
        }

        /**
         * Discovers and returns LangChain4j ToolSpecifications for all methods
         * annotated with @Tool in this class.
         */
        List<ToolSpecification> discoverAndGetToolSpecifications() {
            return toolMethods.entrySet().stream()
                    .map(entry -> {
                        String toolName = entry.getKey();
                        Method method = entry.getValue();
                        Tool toolAnnotation = method.getAnnotation(Tool.class);
                        // LangChain4j provides a helper to build spec from method
                        return ToolSpecification.builder()
                                .from(method)
                                .name(toolName) // Use the name from the annotation
                                .description(toolAnnotation.description())
                                .build();
                    })
                    .collect(Collectors.toList());
        }

        // Example Tool Method: Add a KIF assertion to the global KB
        // LangChain4j will expect a JSON object like {"kifAssertion": "..."}
        @Tool(name = "add_kif_assertion", description = "Add a KIF assertion string to the global knowledge base. Input is the KIF assertion string.")
        public String addKifAssertion(String kifAssertion) {
            try {
                var terms = Logic.KifParser.parseKif(kifAssertion);
                if (terms.size() == 1 && terms.getFirst() instanceof Logic.KifList list) {
                    var isNeg = list.op().filter(Logic.KIF_OP_NOT::equals).isPresent();
                    if (isNeg && list.size() != 2) {
                        return "Error: Invalid 'not' format in KIF.";
                    }
                    var isEq = !isNeg && list.op().filter(Logic.KIF_OP_EQUAL::equals).isPresent();
                    var isOriented = isEq && list.size() == 3 && list.get(1).weight() > list.get(2).weight();
                    var type = list.containsSkolemTerm() ? Logic.AssertionType.SKOLEMIZED : Logic.AssertionType.GROUND;
                    var pri = LM.LLM_ASSERTION_BASE_PRIORITY / (1.0 + list.weight());
                    var sourceId = "llm-tool:add_kif_assertion"; // Simple source ID for now

                    var pa = new Logic.PotentialAssertion(list, pri, java.util.Set.of(), sourceId, isEq, isNeg, isOriented, null, type, java.util.List.of(), 0); // null targetNoteId for global KB
                    var committedAssertion = cog.context.tryCommit(pa, sourceId);

                    if (committedAssertion != null) {
                        System.out.println("Tool 'add_kif_assertion' successfully added: " + committedAssertion.toKifString());
                        return "Assertion added successfully: " + committedAssertion.toKifString();
                    } else {
                        System.out.println("Tool 'add_kif_assertion' failed to add assertion (might be duplicate or trivial): " + list.toKif());
                        return "Assertion not added (might be duplicate or trivial).";
                    }
                }
                return "Error: Invalid KIF format provided.";
            } catch (Logic.KifParser.ParseException e) {
                System.err.println("Error parsing KIF in tool 'add_kif_assertion': " + e.getMessage());
                return "Error parsing KIF: " + e.getMessage();
            } catch (Exception e) {
                System.err.println("Error executing tool 'add_kif_assertion': " + e.getMessage());
                e.printStackTrace();
                return "Error executing tool: " + e.getMessage();
            }
        }

        /**
         * Executes a tool call request received from the LLM.
         * Uses LangChain4j's ToolExecutor for argument parsing and method invocation.
         *
         * @param request The tool execution request from the LLM.
         * @return The result of the tool execution as a String, to be sent back to the LLM.
         */
        String executeToolCall(ToolExecutionRequest request) {
            Method method = toolMethods.get(request.toolName());
            if (method == null) {
                System.err.println("Tool method not found for name: " + request.toolName());
                return "Error: Tool not found.";
            }

            try {
                // Use LangChain4j's ToolExecutor for simplicity in argument mapping and invocation
                dev.langchain4j.model.tool.ToolExecutor toolExecutor = dev.langchain4j.model.tool.ToolExecutor.builder()
                        .object(this) // Execute methods on this Tools instance
                        .build();

                return toolExecutor.execute(request); // Let ToolExecutor handle parsing and invocation

            } catch (Exception e) {
                System.err.println("Error invoking tool method " + method.getName() + " via ToolExecutor: " + e.getMessage());
                e.printStackTrace();
                return "Error executing tool: " + e.getMessage();
            }
        }
    }
}
