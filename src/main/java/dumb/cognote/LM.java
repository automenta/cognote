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
import dev.langchain4j.model.tool.ToolParameter;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;


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
    CompletableFuture<ChatResponse> llmAsync(String taskId, String prompt, String interactionType, String noteId, List<ToolSpecification> toolSpecifications, List<ChatMessage> history) {
        if (chatModel == null) {
            var errorMsg = interactionType + " Error: LLM not configured.";
            // Update UI status for the task
            cog.updateLlmItemStatus(taskId, UI.LlmStatus.ERROR, errorMsg);
            return CompletableFuture.failedFuture(new IllegalStateException(errorMsg));
        }

        // Add the current user prompt to the history for this turn if it's the first message
        var currentHistory = new java.util.ArrayList<>(history);
        if (history.isEmpty()) {
             currentHistory.add(UserMessage.from(prompt));
        } else {
             // If history is not empty, assume the initial prompt was already added
             // and subsequent messages are AI/Tool messages from previous turns.
             // This simplifies the loop logic; the initial prompt is only added once.
        }


        return CompletableFuture.supplyAsync(() -> {
            cog.waitIfPaused();
            cog.updateLlmItemStatus(taskId, UI.LlmStatus.PROCESSING, interactionType + ": Sending to LLM...");

            try {
                // Generate response with current history and tools
                ChatResponse response = chatModel.generate(currentHistory, toolSpecifications);

                // Process the response
                if (response.toolExecutionRequests() != null && !response.toolExecutionRequests().isEmpty()) {
                    // LLM wants to call tools
                    cog.updateLlmItemStatus(taskId, UI.LlmStatus.PROCESSING, interactionType + ": LLM requested tool call(s)...");

                    var toolResults = new java.util.ArrayList<ChatMessage>();
                    toolResults.add(response.aiMessage()); // Add the AI message containing tool requests to history

                    for (ToolExecutionRequest toolRequest : response.toolExecutionRequests()) {
                        cog.updateLlmItemStatus(taskId, UI.LlmStatus.PROCESSING, interactionType + ": Executing Tool: " + toolRequest.toolName() + "...");
                        System.out.println("Executing Tool: " + toolRequest.toolName() + " with arguments: " + toolRequest.arguments());

                        // Execute the tool call synchronously
                        String toolResult = tools.executeToolCall(toolRequest);

                        System.out.println("Tool Result: " + toolResult);
                        cog.updateLlmItemStatus(taskId, UI.LlmStatus.PROCESSING, interactionType + ": Tool Result: " + toolResult);

                        // Add the tool result message to history
                        toolResults.add(ToolExecutionResultMessage.from(toolRequest.toolName(), toolRequest.id(), toolResult));
                    }

                    // Recursively call llmAsync with updated history including tool calls and results
                    // This creates the multi-turn tool interaction loop
                    // Add a depth limit to prevent infinite loops
                    int currentDepth = history.size(); // Simple depth based on message count
                    final int MAX_TOOL_CALL_DEPTH = 10; // Limit the number of tool call turns

                    if (currentDepth / 2 >= MAX_TOOL_CALL_DEPTH) { // Each tool turn adds ~2 messages (AI request + User result)
                         var errorMsg = interactionType + ": Max tool call depth reached.";
                         cog.updateLlmItemStatus(taskId, UI.LlmStatus.ERROR, errorMsg);
                         throw new RuntimeException(errorMsg);
                    }

                    // Continue the conversation with the tool results
                    currentHistory.addAll(toolResults);
                    return llmAsync(taskId, prompt, interactionType, noteId, toolSpecifications, currentHistory).join(); // Wait for the next turn's result

                } else if (response.content() != null && !response.content().isBlank()) {
                    // LLM provided a final text response
                    cog.updateLlmItemStatus(taskId, UI.LlmStatus.DONE, interactionType + ": Received final response.");
                    currentHistory.add(response.aiMessage()); // Add the final AI message to history
                    // The returned ChatResponse now contains the full history up to this point
                    return response;

                } else {
                    // Empty response, task finished without content or tool calls
                    cog.updateLlmItemStatus(taskId, UI.LlmStatus.DONE, interactionType + ": Received empty response.");
                    return response;
                }

            } catch (Exception e) {
                var cause = (e instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : e;
                if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
                cog.updateLlmItemStatus(taskId, UI.LlmStatus.ERROR, interactionType + " Error: " + cause.getMessage());
                throw new CompletionException("LLM API communication or processing error (" + interactionType + ")", cause);
            }
        }, cog.events.exe); // Use the event executor for async processing
    }

    // Update existing LLM action methods to use the new llmAsync signature
    // They now return CompletableFuture<ChatResponse> and need to extract content

    public CompletableFuture<ChatResponse> enhanceNoteWithLlmAsync(String taskId, Cog.Note n) {
        var finalPrompt = """
                You are a helpful assistant. Please revise and enhance the following note for clarity, conciseness, and improved structure. Keep the core meaning intact.
                Focus on improving readability and flow. Correct any grammatical errors or awkward phrasing.
                Output ONLY the revised note text, without any introductory or concluding remarks.

                Original Note:
                "%s"

                Enhanced Note:""".formatted(n.text);
        // Pass empty history initially
        var future = llmAsync(taskId, finalPrompt, "Note Enhancement", n.id, tools.discoverAndGetToolSpecifications(), new java.util.ArrayList<>());
        activeLlmTasks.put(taskId, future);
        // The UI handler will now process the ChatResponse
        return future;
    }

    public CompletableFuture<ChatResponse> summarizeNoteWithLlmAsync(String taskId, Cog.Note n) {
        var finalPrompt = """
                Summarize the following note in one or two concise sentences. Output ONLY the summary.

                Note:
                "%s"

                Summary:""".formatted(n.text);
        // Pass empty history initially
        var future = llmAsync(taskId, finalPrompt, "Note Summarization", n.id, tools.discoverAndGetToolSpecifications(), new java.util.ArrayList<>());
        activeLlmTasks.put(taskId, future);
        // The UI handler will now process the ChatResponse
        return future;
    }

    public CompletableFuture<ChatResponse> keyConceptsWithLlmAsync(String taskId, Cog.Note n) {
        var finalPrompt = """
                Identify the key concepts or entities mentioned in the following note. List them separated by newlines. Output ONLY the newline-separated list.

                Note:
                "%s"

                Key Concepts:""".formatted(n.text);
        // Pass empty history initially
        var future = llmAsync(taskId, finalPrompt, "Key Concept Identification", n.id, tools.discoverAndGetToolSpecifications(), new java.util.ArrayList<>());
        activeLlmTasks.put(taskId, future);
        // The UI handler will now process the ChatResponse
        return future;
    }

    public CompletableFuture<ChatResponse> generateQuestionsWithLlmAsync(String taskId, Cog.Note n) {
        var finalPrompt = """
                Based on the following note, generate 1-3 insightful questions that could lead to further exploration or clarification. Output ONLY the questions, each on a new line starting with '- '.

                Note:
                "%s"

                Questions:""".formatted(n.text);
        // Pass empty history initially
        var future = llmAsync(taskId, finalPrompt, "Question Generation", n.id, tools.discoverAndGetToolSpecifications(), new java.util.ArrayList<>());
        activeLlmTasks.put(taskId, future);
        // The UI handler will now process the ChatResponse
        return future;
    }

    public CompletableFuture<ChatResponse> text2kifAsync(String taskId, String noteText, String noteId) {
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
        // Pass empty history initially
        var future = llmAsync(taskId, finalPrompt, "KIF Generation", noteId, tools.discoverAndGetToolSpecifications(), new java.util.ArrayList<>());
        activeLlmTasks.put(taskId, future);
        // The UI handler will now process the ChatResponse
        return future;
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
        @Tool(name = "add_kif_assertion", description = "Add a KIF assertion string to a knowledge base. Input is a JSON object with 'kif_assertion' (string) and optional 'target_kb_id' (string, defaults to global KB).")
        public String addKifAssertion(@ToolParameter(name = "kif_assertion", description = "The KIF assertion string to add.") String kifAssertion, @ToolParameter(name = "target_kb_id", description = "Optional ID of the knowledge base (note ID) to add the assertion to. Defaults to global KB if not provided.") @Nullable String targetKbId) {
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
                    var sourceId = "llm-tool:add_kif_assertion";

                    // Use the provided targetNoteId, defaulting to global if null or empty
                    var finalTargetKbId = (targetKbId == null || targetKbId.trim().isEmpty()) ? Cog.GLOBAL_KB_NOTE_ID : targetKbId.trim();

                    var pa = new Logic.PotentialAssertion(list, pri, java.util.Set.of(), sourceId, isEq, isNeg, isOriented, finalTargetKbId, type, java.util.List.of(), 0);
                    var committedAssertion = cog.context.tryCommit(pa, sourceId);

                    if (committedAssertion != null) {
                        System.out.println("Tool 'add_kif_assertion' successfully added: " + committedAssertion.toKifString() + " to KB " + committedAssertion.kb());
                        return "Assertion added successfully: " + committedAssertion.toKifString() + " [ID: " + committedAssertion.id() + "]";
                    } else {
                        System.out.println("Tool 'add_kif_assertion' failed to add assertion (might be duplicate, trivial, or KB full): " + list.toKif());
                        return "Assertion not added (might be duplicate, trivial, or KB full).";
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

        // Add the getNoteText tool method
        @Tool(name = "get_note_text", description = "Retrieve the full text content of a specific note by its ID.")
        public String getNoteText(@ToolParameter(name = "note_id", description = "The ID of the note to retrieve text from.") String noteId) {
            if (cog == null || cog.ui == null) {
                return "Error: System UI not available.";
            }
            return cog.ui.findNoteById(noteId)
                    .map(note -> note.text)
                    .orElse("Error: Note with ID '" + noteId + "' not found.");
        }

        // Add the findAssertions tool method
        @Tool(name = "find_assertions", description = "Query the knowledge base for assertions matching a KIF pattern. Returns a list of bindings found.")
        public String findAssertions(@ToolParameter(name = "kif_pattern", description = "The KIF pattern to query the knowledge base with.") String kifPattern, @ToolParameter(name = "target_kb_id", description = "Optional ID of the knowledge base (note ID) to query. Defaults to global KB if not provided.") @Nullable String targetKbId) {
            if (cog == null) {
                return "Error: System not available.";
            }
            try {
                var terms = Logic.KifParser.parseKif(kifPattern);
                if (terms.size() != 1 || !(terms.getFirst() instanceof Logic.KifList patternList)) {
                    return "Error: Invalid KIF pattern format. Must be a single KIF list.";
                }

                // Use the provided targetNoteId, defaulting to global if null or empty
                var finalTargetKbId = (targetKbId == null || targetKbId.trim().isEmpty()) ? Cog.GLOBAL_KB_NOTE_ID : targetKbId.trim();

                // Execute the query synchronously via Cog
                var queryId = Cog.generateId(Cog.ID_PREFIX_QUERY + "tool_");
                var query = new Cog.Query(queryId, Cog.QueryType.ASK_BINDINGS, patternList, finalTargetKbId, Map.of());
                var answer = cog.executeQuerySync(query); // Call the new sync method

                if (answer.status() == Cog.QueryStatus.SUCCESS) {
                    if (answer.bindings().isEmpty()) {
                        return "Query successful, but no matching assertions found.";
                    } else {
                        // Format bindings for LLM consumption
                        return "Query successful. Found " + answer.bindings().size() + " bindings:\n" +
                               answer.bindings().stream()
                                       .map(b -> b.entrySet().stream()
                                               .map(e -> e.getKey().name() + " = " + e.getValue().toKif())
                                               .collect(Collectors.joining(", ")))
                                       .collect(Collectors.joining("\n"));
                    }
                } else {
                    return "Query failed with status " + answer.status() + ". " + (answer.explanation() != null ? "Details: " + answer.explanation().details() : "");
                }

            } catch (Logic.KifParser.ParseException e) {
                System.err.println("Error parsing KIF pattern in tool 'find_assertions': " + e.getMessage());
                return "Error parsing KIF pattern: " + e.getMessage();
            } catch (Exception e) {
                System.err.println("Error executing tool 'find_assertions': " + e.getMessage());
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
