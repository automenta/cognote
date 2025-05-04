package dumb.cogthought;

import dumb.cogthought.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static dumb.cogthought.util.Log.error;
import static dumb.cogthought.util.Log.info;

/**
 * The SystemControl manages the main processing loop of the Cog system.
 * It monitors the Knowledge Base for new tasks (like API requests, events, etc.)
 * and feeds them to the TermLogicEngine for interpretation and execution.
 */
public class SystemControl {

    private final KnowledgeBase knowledgeBase;
    private final TermLogicEngine termLogicEngine;
    private final ToolRegistry toolRegistry; // Keep reference if needed for future tasks
    private final ApiGateway apiGateway;
    private final LLMService llmService; // Keep reference if needed for future tasks
    private final Events events; // Keep reference if needed for future tasks

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final AtomicBoolean running = new AtomicBoolean(false);

    // TODO: Define Ontology terms for task types (e.g., ApiRequest, Event, InternalTask)
    private static final Term API_REQUEST_PREDICATE = Term.Atom.of("ApiRequest"); // Placeholder
    private static final Term API_RESPONSE_PREDICATE = Term.Atom.of("ApiResponse"); // Placeholder
    private static final Term SENT_API_RESPONSE_PREDICATE = Term.Atom.of("SentApiResponse"); // Marker for sent responses

    public SystemControl(KnowledgeBase knowledgeBase, TermLogicEngine termLogicEngine, ToolRegistry toolRegistry, ApiGateway apiGateway, LLMService llmService, Events events) {
        this.knowledgeBase = requireNonNull(knowledgeBase);
        this.termLogicEngine = requireNonNull(termLogicEngine);
        this.toolRegistry = requireNonNull(toolRegistry);
        this.apiGateway = requireNonNull(apiGateway);
        this.llmService = requireNonNull(llmService);
        this.events = requireNonNull(events);
        info("SystemControl initialized.");
    }

    /**
     * Starts the main processing loop.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            info("SystemControl starting main processing loop...");
            // Schedule the task processing loop
            // TODO: Make polling interval configurable via KB settings
            scheduler.scheduleAtFixedRate(this::processTasks, 100, 500, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Stops the main processing loop and shuts down the scheduler.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            info("SystemControl stopping main processing loop...");
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                error("SystemControl shutdown interrupted.");
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            info("SystemControl stopped.");
        }
    }

    /**
     * The main loop logic. Queries the KB for tasks and processes them.
     */
    private void processTasks() {
        if (!running.get()) return;

        try {
            // TODO: Implement a more sophisticated task selection/prioritization mechanism
            // For now, process API Requests and then send API Responses.

            // 1. Process incoming API Requests
            // Query for ApiRequest terms that haven't been marked as processed
            // This requires a way to mark assertions as processed or query for specific states.
            // A simple approach for now is to query for the predicate and process them.
            // A better approach would involve a dedicated 'Task' Note type or assertion structure.

            // Placeholder query for ApiRequest terms
            Term apiRequestPattern = new Term.Lst(java.util.List.of(API_REQUEST_PREDICATE, Term.Var.of("_RequestId"), Term.Var.of("_CommandTerm")));

            knowledgeBase.queryAssertions(apiRequestPattern)
                         .filter(Assertion::isActive) // Only process active assertions
                         // TODO: Add filtering for assertions that haven't been processed yet
                         .forEach(this::processAssertionAsTask);

            // TODO: Add processing for other task types (e.g., Event terms, internal tasks)

            // 2. Process outgoing API Responses
            // Query for ApiResponse terms that haven't been marked as sent
            Term apiResponsePattern = new Term.Lst(java.util.List.of(API_RESPONSE_PREDICATE, Term.Var.of("_RequestId"), Term.Var.of("_ResponseContent")));
            Term sentApiResponsePattern = new Term.Lst(java.util.List.of(SENT_API_RESPONSE_PREDICATE, Term.Var.of("_AssertionId")));

            // Get IDs of responses that have already been marked as sent
            java.util.Set<String> sentResponseIds = knowledgeBase.queryAssertions(sentApiResponsePattern)
                                                                .filter(Assertion::isActive)
                                                                .flatMap(assertion -> {
                                                                    // Assuming (SentApiResponse <assertionId>) structure
                                                                    if (assertion.kif() instanceof Term.Lst sentList && sentList.size() == 2 && sentList.get(1) instanceof Term.Atom idAtom) {
                                                                        return Stream.of(idAtom.name());
                                                                    }
                                                                    return Stream.empty();
                                                                })
                                                                .collect(java.util.stream.Collectors.toSet());


            knowledgeBase.queryAssertions(apiResponsePattern)
                         .filter(Assertion::isActive) // Only process active responses
                         .filter(responseAssertion -> !sentResponseIds.contains(responseAssertion.id())) // Filter out already sent responses
                         .forEach(this::processAssertionAsApiResponse);


        } catch (Exception e) {
            error("Error in SystemControl processing loop: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Processes a single assertion that represents a task (e.g., an ApiRequest).
     *
     * @param taskAssertion The assertion representing the task.
     */
    private void processAssertionAsTask(Assertion taskAssertion) {
        // TODO: Implement proper task processing logic.
        // This might involve:
        // 1. Marking the task assertion as 'processing' in the KB.
        // 2. Extracting the actual task/command term from the assertion.
        // 3. Feeding the task term to the TermLogicEngine.
        // 4. Handling the result or errors from the TermLogicEngine.
        // 5. Marking the task assertion as 'completed' or 'failed' in the KB.

        info("Processing task assertion: " + taskAssertion.id() + " - " + taskAssertion.toKifString());

        // For now, directly feed the assertion's KIF term to the TermLogicEngine
        // This is a simplification; the TermLogicEngine should ideally process
        // the *content* of the task (e.g., the command term within ApiRequest),
        // not the ApiRequest wrapper itself. This needs refinement based on Ontology.

        // Assuming the taskAssertion KIF is the term to process (e.g., (ApiRequest ...))
        // The TermLogicEngine will need rules that match (ApiRequest ...) and trigger actions.
        termLogicEngine.processTerm(taskAssertion.kif())
                       .thenRun(() -> {
                           info("Task processing complete for assertion: " + taskAssertion.id());
                           // TODO: Mark taskAssertion as processed/completed in KB
                           // This might involve asserting a new fact like (Processed taskAssertion.id())
                           // or updating the taskAssertion itself if the structure supports status.
                           // For now, we just process it once. A more robust system needs state management.
                       })
                       .exceptionally(e -> {
                           error("Task processing failed for assertion " + taskAssertion.id() + ": " + e.getMessage());
                           e.printStackTrace();
                           // TODO: Mark taskAssertion as failed in KB and potentially assert an error term
                           return null;
                       });
    }

    /**
     * Processes a single assertion that represents an API Response.
     * Converts it to a message and sends it via the ApiGateway.
     *
     * @param responseAssertion The assertion representing the API response (e.g., an ApiResponse term).
     */
    private void processAssertionAsApiResponse(Assertion responseAssertion) {
        info("Processing ApiResponse assertion for sending: " + responseAssertion.id() + " - " + responseAssertion.toKifString());

        try {
            // Convert the ApiResponse assertion to an outgoing message string (e.g., JSON)
            String message = apiGateway.convertApiResponseToMessage(responseAssertion);

            // Send the message
            apiGateway.sendOutgoingMessage(message);

            // Mark the assertion as sent in the KB
            // This prevents sending it again in the next loop iteration.
            Term sentMarkerTerm = new Term.Lst(java.util.List.of(SENT_API_RESPONSE_PREDICATE, Term.Atom.of(responseAssertion.id())));
            knowledgeBase.saveAssertion(new Assertion(
                dumb.cogthought.Cog.id(dumb.cogthought.Cog.ID_PREFIX_ASSERTION),
                (Term.Lst) sentMarkerTerm,
                0.1, // Low priority for marker
                java.time.Instant.now().toEpochMilli(),
                null,
                java.util.Collections.emptySet(),
                Assertion.Type.GROUND,
                false, false, false,
                java.util.Collections.emptyList(),
                0, true, // Active
                "system" // System KB for markers
            ));
            info("Marked ApiResponse assertion as sent: " + responseAssertion.id());

        } catch (Exception e) {
            error("Error processing ApiResponse assertion " + responseAssertion.id() + " for sending: " + e.getMessage());
            e.printStackTrace();
            // TODO: Assert an error term into KB if sending fails
        }
    }
}
