package dumb.cognote;

// LangChain4j Core Imports

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static dumb.cognote.Cog.ID_PREFIX_QUERY;

public class LM {
    static final String DEFAULT_LLM_URL = "http://localhost:11434"; // Base URL for Ollama
    static final String DEFAULT_LLM_MODEL = "hf.co/mradermacher/phi-4-GGUF:Q4_K_S";
    static final double LLM_ASSERTION_BASE_PRIORITY = 15.0;
    final Map<String, CompletableFuture<?>> activeLlmTasks = new ConcurrentHashMap<>();
    final Tools tools; // Instance of the class containing @Tool methods
    private final Cog cog;
    volatile String llmApiUrl; // Now base URL
    volatile String llmModel;
    private volatile ChatLanguageModel chatModel;
    private volatile LlmService llmService; // The AI Service interface instance

    public LM(Cog cog) {
        this.cog = cog;
        this.tools = new Tools(cog); // Instantiate Tools here
        // Initial configuration will happen via loadNotesAndConfig -> updateConfig -> reconfigure
    }

    void reconfigure() {
        try {
            // OllamaChatModel uses baseUrl, not the full /api/chat endpoint
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
            System.out.printf("LM ChatModel reconfigured: Base URL=%s, Model=%s%n", baseUrl, llmModel);

            // Build the AiService using the configured model and tools instance
            // AiServices handles the tool execution loop internally.
            this.llmService = AiServices.builder(LlmService.class)
                    .chatLanguageModel(this.chatModel)
                    .tools(this.tools) // Provide the instance containing @Tool methods
                    //.chatMemory() // Optional: Add memory for multi-turn conversations managed by AiServices
                    .build();
            System.out.println("LM AiService configured.");

        } catch (Exception e) {
            System.err.println("Failed to reconfigure LLM or AiService: " + e.getMessage());
            e.printStackTrace(); // Print stack trace for detailed debugging
            this.chatModel = null;
            this.llmService = null; // Ensure service is null if config fails
        }
    }

    // Simplified llmAsync: It now delegates the core interaction (including tool calls)
    // to the AiService. It primarily handles task management, status updates,
    // adding the system message, and wrapping the call in a CompletableFuture.
    // The 'toolSpecifications' parameter is removed as AiServices handles tool discovery.
    CompletableFuture<AiMessage> llmAsync(String taskId, List<ChatMessage> history, String interactionType, String noteId) {
        // Check if the AiService is configured, not just the chatModel
        if (llmService == null) {
            var errorMsg = interactionType + " Error: LLM Service not configured.";
            cog.updateLlmItemStatus(taskId, UI.LlmStatus.ERROR, errorMsg);
            return CompletableFuture.failedFuture(new IllegalStateException(errorMsg));
        }

        // Add a system message explaining the context and available tools.
        // AiServices will pass this along with the user history to the LLM.
        // Tool details are implicitly known by AiServices via the .tools() builder method.
        var systemMessage = SystemMessage.from("""
                You are an intelligent agent interacting with a knowledge base system.
                Your primary goal is to assist the user by processing information, answering questions, or performing tasks using the available tools and your knowledge.
                Available tools have been provided to you. If a tool call is required to fulfill the request, use the tool(s). Otherwise, provide a direct text response.
                When using tools, output ONLY the tool call(s) in the specified format. Your response will be intercepted, the tool(s) executed, and the results provided back to you in the next turn.
                When providing a final answer or explanation after any necessary tool use, output plain text.
                """);

        var conversationHistory = new ArrayList<ChatMessage>();
        conversationHistory.add(systemMessage);
        conversationHistory.addAll(history); // Add the user-provided history


        return CompletableFuture.supplyAsync(() -> {
            cog.waitIfPaused();
            cog.updateLlmItemStatus(taskId, UI.LlmStatus.PROCESSING, interactionType + ": Sending to LLM Service...");

            try {
                // Delegate the chat interaction (including potential tool calls and retries)
                // entirely to the AiService instance.
                AiMessage finalAiMessage = llmService.chat(conversationHistory);

                // AiServices has handled the tool execution loop internally if needed.
                // The returned AiMessage is the final response from the LLM.
                cog.updateLlmItemStatus(taskId, UI.LlmStatus.DONE, interactionType + ": Received final response.");
                return finalAiMessage;

            } catch (Exception e) {
                var cause = (e instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : e;
                if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
                System.err.println("LLM Service interaction error (" + interactionType + "): " + cause.getMessage());
                cause.printStackTrace(); // Log stack trace for debugging
                cog.updateLlmItemStatus(taskId, UI.LlmStatus.ERROR, interactionType + " Error: " + cause.getMessage());
                // Wrap the original cause for better debugging downstream
                throw new CompletionException("LLM Service interaction error (" + interactionType + "): " + cause.getMessage(), cause);
            }
        }, cog.events.exe); // Use the event executor for async processing
    }

    public CompletableFuture<AiMessage> enhanceNoteWithLlmAsync(String taskId, Cog.Note n) {
        var promptText = """
                You are a helpful assistant. Please revise and enhance the following note for clarity, conciseness, and improved structure. Keep the core meaning intact.
                Focus on improving readability and flow. Correct any grammatical errors or awkward phrasing.
                Output ONLY the revised note text, without any introductory or concluding remarks.
                
                Original Note:
                "%s"
                
                Enhanced Note:""".formatted(n.text);
        var history = new ArrayList<ChatMessage>();
        history.add(UserMessage.from(promptText));
        // Call the simplified llmAsync, no tool specs needed here
        var future = llmAsync(taskId, history, "Note Enhancement", n.id);
        activeLlmTasks.put(taskId, future);
        return future;
    }

    // Update existing LLM action methods to use the new llmAsync signature
    // and expect CompletableFuture<AiMessage> instead of CompletableFuture<Response<AiMessage>>.
    // Remove the passing of toolSpecifications.

    public CompletableFuture<AiMessage> summarizeNoteWithLlmAsync(String taskId, Cog.Note n) {
        var promptText = """
                Summarize the following note in one or two concise sentences. Output ONLY the summary.
                
                Note:
                "%s"
                
                Summary:""".formatted(n.text);
        var history = new ArrayList<ChatMessage>();
        history.add(UserMessage.from(promptText));
        var future = llmAsync(taskId, history, "Note Summarization", n.id);
        activeLlmTasks.put(taskId, future);
        return future;
    }

    public CompletableFuture<AiMessage> keyConceptsWithLlmAsync(String taskId, Cog.Note n) {
        var promptText = """
                Identify the key concepts or entities mentioned in the following note. List them separated by newlines. Output ONLY the newline-separated list.
                
                Note:
                "%s"
                
                Key Concepts:""".formatted(n.text);
        var history = new ArrayList<ChatMessage>();
        history.add(UserMessage.from(promptText));
        var future = llmAsync(taskId, history, "Key Concept Identification", n.id);
        activeLlmTasks.put(taskId, future);
        return future;
    }

    public CompletableFuture<AiMessage> generateQuestionsWithLlmAsync(String taskId, Cog.Note n) {
        var promptText = """
                Based on the following note, generate 1-3 insightful questions that could lead to further exploration or clarification. Output ONLY the questions, each on a new line starting with '- '.
                
                Note:
                "%s"
                
                Questions:""".formatted(n.text);
        var history = new ArrayList<ChatMessage>();
        history.add(UserMessage.from(promptText));
        var future = llmAsync(taskId, history, "Question Generation", n.id);
        activeLlmTasks.put(taskId, future);
        return future;
    }

    // This method now relies on AiServices to see the tool descriptions (via the system prompt
    // and internal mechanisms) and decide to call add_kif_assertion.
    public CompletableFuture<AiMessage> text2kifAsync(String taskId, String noteText, String noteId) {
        var promptText = """
                Convert the following note into a set of concise SUMO KIF assertions (standard Lisp-like syntax, e.g., (instance MyCat Cat)).
                
                 * standard SUMO predicates like 'instance', 'subclass', 'domain', 'range', 'attribute', 'partOf', etc.
                 * '=' for equality between terms. Use unique names for new entities derived from the note (start with uppercase, use CamelCase).
                 * '(not ...)' for negation where appropriate, e.g., (not (instance Pluto Planet)).
                 * '(forall (?X) (=> (instance ?X Dog) (attribute ?X Canine)))' for universal statements.
                 * '(exists (?Y) (and (instance ?Y Cat) (attribute ?Y BlackColor)))' for existential statements.
                 * Avoid trivial assertions like (instance X X) or (= X X) or (not (= X X)).
                
                Note:
                ```
                "%s"
                ```
                
                For each assertion you generate, use the `add_kif_assertion` tool with the note ID "%s".
                Do NOT output the KIF assertions directly in your response. Only use the tool.
                Generate the KIF Assertions by adding them using the tool:""".formatted(noteId, noteText);
        // AiServices calls the 'add_kif_assertion' tool as needed.
        var future = llmAsync(taskId, List.of(UserMessage.from(promptText)), "KIF Generation", noteId);
        activeLlmTasks.put(taskId, future);
        return future;
    }

    // This method now relies on AiServices to see the tool descriptions and decide
    // to call add_kif_assertion.
    public CompletableFuture<AiMessage> decomposeGoalAsync(String taskId, String goalDescription, @Nullable String targetNoteId) {
        var finalTargetKbId = (targetNoteId == null || targetNoteId.trim().isEmpty()) ? Cog.GLOBAL_KB_NOTE_ID : targetNoteId.trim();
        var promptText = """
                You are a task decomposition agent. Your goal is to break down a high-level goal into smaller, actionable steps or sub-goals.
                Express each step as a concise KIF assertion representing the step itself (e.g., a goal, action, or query).
                For each step you identify, use the `add_kif_assertion` tool to add it to the knowledge base.
                The target knowledge base ID for the assertions should be "%s".
                Do NOT output the KIF assertions directly in your response. Only use the tool.
                
                Examples of KIF assertions for steps:
                - (goal (findInformation about Cats))
                - (action (createNote "Summary of Cats"))
                - (query (instance ?X Cat))
                - (action (summarizeNote "%s")) ; Assuming a tool or process exists for this
                
                Break down the following goal:
                "%s"
                
                Generate KIF assertions for the steps and add them using the tool:""".formatted(finalTargetKbId, finalTargetKbId, goalDescription); // Use finalTargetKbId consistently

        var history = new ArrayList<ChatMessage>();
        history.add(UserMessage.from(promptText));
        // AiServices will handle calling the 'add_kif_assertion' tool as needed.
        var future = llmAsync(taskId, history, "Task Decomposition", targetNoteId); // noteId param in llmAsync is just for context/logging
        activeLlmTasks.put(taskId, future);
        return future;
    }

    // Define the AI Service Interface
    interface LlmService {
        // This method will interact with the LLM.
        // AiServices will automatically handle tool discovery and execution
        // based on the tools provided when building the service.
        // It takes the conversation history and returns the final AI message
        // after any necessary tool calls have been completed internally.
        AiMessage chat(List<ChatMessage> messages);
    }

    // The Tools class remains largely the same, defining methods with @Tool.
    // However, the executeToolCall method and ToolExecutor dependency are removed,
    // as AiServices handles execution internally.
    static class Tools {
        private final Cog cog;
        // Removed toolMethods map as it's not needed for AiServices execution flow
        // Removed discoverTools method for the same reason

        Tools(Cog cog) {
            this.cog = cog;
            // No explicit discovery needed here for AiServices; it reflects on the passed object.
            System.out.println("Tools instance created for AiServices.");
        }

        // This method might still be useful for introspection/debugging, but is not
        // directly used by the AiServices execution flow in LM.java anymore.
        // It requires ToolSpecifications which might not be in langchain4j-core.
        // If ToolSpecifications is unavailable, this method should be removed or adapted.
        // Assuming ToolSpecifications is available in the full langchain4j artifact:
        /*
        List<ToolSpecification> discoverAndGetToolSpecifications() {
            List<ToolSpecification> specs = new ArrayList<>();
            for (Method method : Tools.class.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    try {
                         // Requires dev.langchain4j.agent.tool.ToolSpecifications
                         specs.add(ToolSpecifications.toolSpecificationFrom(method));
                    } catch (Exception e) {
                         System.err.println("Warning: Could not generate ToolSpecification for method " + method.getName() + ": " + e.getMessage());
                    }
                }
            }
            return specs;
        }
        */


        // Tool methods remain the same, annotated with @Tool and @P.
        // AiServices will find these methods via reflection on the 'tools' instance
        // passed to its builder.

        @Tool(name = "add_kif_assertion", value = "Add a KIF assertion string to a knowledge base. Input is a JSON object with 'kif_assertion' (string) and optional 'target_kb_id' (string, defaults to global KB). Returns success or error message.")
        public String addKifAssertion(@P(value = "The KIF assertion string to add.") String kifAssertion, @P(value = "Optional ID of the knowledge base (note ID) to add the assertion to. Defaults to global KB if not provided or empty.") @Nullable String targetKbId) {
            try {
                var terms = Logic.KifParser.parseKif(kifAssertion);
                if (terms.size() != 1 || !(terms.getFirst() instanceof Logic.KifList list)) {
                    return "Error: Invalid KIF format provided. Must be a single KIF list.";
                }

                var isNeg = list.op().filter(Logic.KIF_OP_NOT::equals).isPresent();
                if (isNeg && list.size() != 2) {
                    return "Error: Invalid 'not' format in KIF.";
                }
                var isEq = !isNeg && list.op().filter(Logic.KIF_OP_EQUAL::equals).isPresent();
                var isOriented = isEq && list.size() == 3 && list.get(1).weight() > list.get(2).weight();
                var type = list.containsSkolemTerm() ? Logic.AssertionType.SKOLEMIZED : Logic.AssertionType.GROUND;
                var pri = LM.LLM_ASSERTION_BASE_PRIORITY / (1.0 + list.weight());
                var sourceId = "llm-tool:add_kif_assertion";

                // Use the provided targetKbId, defaulting to global if null or empty
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
            } catch (Logic.KifParser.ParseException e) {
                System.err.println("Error parsing KIF in tool 'add_kif_assertion': " + e.getMessage());
                return "Error parsing KIF: " + e.getMessage();
            } catch (Exception e) {
                System.err.println("Error executing tool 'add_kif_assertion': " + e.getMessage());
                e.printStackTrace();
                return "Error executing tool: " + e.getMessage();
            }
        }

        @Tool(name = "get_note_text", value = "Retrieve the full text content of a specific note by its ID. Input is a JSON object with 'note_id' (string). Returns the note text or an error message.")
        public String getNoteText(@P(value = "The ID of the note to retrieve text from.") String noteId) {
            if (cog == null || cog.ui == null) {
                return "Error: System UI not available.";
            }
            return cog.ui.findNoteById(noteId)
                    .map(note -> note.text)
                    .orElse("Error: Note with ID '" + noteId + "' not found.");
        }

        @Tool(name = "find_assertions", value = "Query the knowledge base for assertions matching a KIF pattern. Input is a JSON object with 'kif_pattern' (string) and optional 'target_kb_id' (string, defaults to global KB). Returns a list of bindings found or a message indicating no matches or an error.")
        public String findAssertions(@P(value = "The KIF pattern to query the knowledge base with.") String kifPattern, @P(value = "Optional ID of the knowledge base (note ID) to query. Defaults to global KB if not provided or empty.") @Nullable String targetKbId) {
            if (cog == null) {
                return "Error: System not available.";
            }
            try {
                var terms = Logic.KifParser.parseKif(kifPattern);
                if (terms.size() != 1 || !(terms.getFirst() instanceof Logic.KifList patternList)) {
                    return "Error: Invalid KIF pattern format. Must be a single KIF list.";
                }

                // Use the provided targetKbId, defaulting to global if null or empty
                var finalTargetKbId = (targetKbId == null || targetKbId.trim().isEmpty()) ? Cog.GLOBAL_KB_NOTE_ID : targetKbId.trim();

                // Execute the query synchronously via Cog
                var queryId = Cog.generateId(ID_PREFIX_QUERY + "tool_");
                var query = new Cog.Query(queryId, Cog.QueryType.ASK_BINDINGS, patternList, finalTargetKbId, Map.of());
                var answer = cog.executeQuerySync(query); // Call the sync method in Cog

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

        // Removed the executeToolCall method as AiServices handles this.
        // String executeToolCall(ToolExecutionRequest request) { ... }
    }
}