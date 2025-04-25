package dumb.cognote.plugin;

import dumb.cognote.*;
import dumb.cognote.Logic.KifParser.ParseException;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dumb.cognote.Cog.*;
import static dumb.cognote.Logic.*;

public class TestPlugin extends Plugin.BasePlugin {

    private static final String TEST_KB_PREFIX = "test-kb-";
    private static final long TEST_ACTION_TIMEOUT_SECONDS = 30;
    private static final long TEST_WAIT_DEFAULT_TIMEOUT_SECONDS = 30;
    private static final long TEST_WAIT_DEFAULT_INTERVAL_MILLIS = 100;
    private static final Pattern PARSE_ERROR_LOCATION_PATTERN = Pattern.compile(" at line (\\d+) col (\\d+)$");

    @Override
    public void start(Events ev, Logic.Cognition ctx) {
        super.start(ev, ctx);
        ev.on(RunTestsEvent.class, this::handleRunTests);
    }

    private void handleRunTests(RunTestsEvent event) {
        cog().status("Running Tests...");
        System.out.println("TestPlugin: Starting test run..."); // Keep this log

        var testDefinitionsNote = cog().note(TEST_DEFINITIONS_NOTE_ID);
        if (testDefinitionsNote.isEmpty()) {
            String errorMsg = "Error: Test Definitions note not found.";
            updateTestResults(errorMsg);
            cog().status("Tests Failed");
            System.err.println("TestPlugin: " + errorMsg); // Keep this error log
            // Emit completion event even on parse/setup failure
            cog().events.emit(new TestRunCompleteEvent(errorMsg));
            return;
        }

        var testDefinitionsText = testDefinitionsNote.get().text;
        List<TestDefinition> tests;
        try {
            tests = parseTestDefinitions(testDefinitionsText);
            // System.out.println("TestPlugin: Parsed " + tests.size() + " tests."); // Remove verbose log
        } catch (ParseException e) {
            String enhancedErrorMessage = formatParseException(e, testDefinitionsText);
            String errorMsg = "Error parsing test definitions:\n" + enhancedErrorMessage;
            System.err.println("TestPlugin: " + errorMsg); // Keep this error log
            updateTestResults(errorMsg);
            cog().status("Tests Failed");
            // Emit completion event even on parse/setup failure
            cog().events.emit(new TestRunCompleteEvent(errorMsg));
            return;
        }

        if (tests.isEmpty()) {
            String resultsMsg = "No tests found in Test Definitions note.";
            updateTestResults(resultsMsg);
            cog().status("Tests Complete (No Tests)");
            System.out.println("TestPlugin: " + resultsMsg); // Keep this log
            // Emit completion event
            cog().events.emit(new TestRunCompleteEvent(resultsMsg));
            return;
        }

        List<TestResult> results = new ArrayList<>(); // Use regular list, populated sequentially
        CompletableFuture<Void> allTestsFuture = CompletableFuture.completedFuture(null);

        for (var test : tests) {
            // Chain tests sequentially, ensuring each test runs on the event executor
            allTestsFuture = allTestsFuture.thenComposeAsync(v ->
                    runTest(test)
                            .exceptionally(ex -> {
                                var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
                                System.err.println("TestPlugin: Error during test '" + test.name + "' execution chain: " + cause.getMessage()); // Keep this error log
                                cause.printStackTrace(); // Keep stack trace for unexpected errors
                                // If the *entire test's future chain* fails exceptionally *before* producing a TestResult,
                                // we need to create a failure result here.
                                List<ActionError> errors = new ArrayList<>();
                                // Create a dummy action for the error report
                                errors.add(new ActionError(new TestAction("Execution Chain", null, Collections.emptyMap()), "Unhandled error: " + cause.getMessage()));
                                // Create a TestResult with the parsing errors already collected for this test, empty expectation results, empty action errors, and empty KB state
                                return new TestResult(test.name, test.parsingErrors(), Collections.emptyList(), errors, Collections.emptyList());
                            })
                            .thenAccept(results::add), // Add result to the list (runs on the executor that completed runTest)
                    cog().events.exe // Ensure the next test starts on the event executor
            );
        }

        // This block runs when all individual test futures have completed (either successfully or exceptionally)
        allTestsFuture.whenCompleteAsync((v, ex) -> { // Explicitly on event executor
            // System.out.println("TestPlugin: All test futures completed. Formatting results..."); // Remove verbose log
            String finalResultsText;
            if (ex != null) {
                System.err.println("TestPlugin: Unhandled error after all tests futures completed: " + ex.getMessage()); // Keep this error log
                ex.printStackTrace(); // Keep stack trace for unexpected errors
                // If the overall chain fails *after* tests have produced results, append the error
                finalResultsText = "Internal Error after test run: " + ex.getMessage() + "\n" + formatTestResults(results);
                cog().status("Tests Failed"); // Explicitly set status on error
            } else {
                finalResultsText = formatTestResults(results);
                // Determine overall status based on results content
                boolean anyFailed = results.stream().anyMatch(r -> !r.isOverallPassed());
                cog().status(anyFailed ? "Tests Failed" : "Tests Complete");
            }

            updateTestResults(finalResultsText);
            System.out.println("TestPlugin: Test run finished. Status updated and results note updated."); // Keep this log
            // Emit the completion event with the final results text
            cog().events.emit(new TestRunCompleteEvent(finalResultsText));

        }, cog().events.exe); // Run on the event executor
    }

    private CompletableFuture<TestResult> runTest(TestDefinition test) {
        var testKbId = Cog.id(TEST_KB_PREFIX);
        List<ActionError> actionErrors = new ArrayList<>(); // Use regular list, populated sequentially

        var testNote = new Note(testKbId, "Test KB: " + test.name, "", Note.Status.IDLE);
        // TODO: Decouple from CogNote - need a generic way to manage temporary KBs/Notes
        ((CogNote) cog()).addNote(testNote);
        context.addActiveNote(testKbId);

        CompletableFuture<Object> setupFuture = executeActionList(test, testKbId, test.setup, actionErrors, "Setup")
                .exceptionally(ex -> {
                    // Error is already captured in actionErrors list by executeActionList
                    // System.err.println("TestPlugin: Setup stage failed for test '" + test.name + "'. Skipping Action and Expectations."); // Remove verbose log
                    return null; // Allow action/expectations to potentially run or fail gracefully
                });

        CompletableFuture<Object> actionFuture = setupFuture.thenComposeAsync(setupResult -> { // Ensure action stage starts on event executor
            if (!actionErrors.isEmpty()) {
                return CompletableFuture.completedFuture(null); // Skip action if setup failed
            }
            return executeActionList(test, testKbId, test.action, actionErrors, "Action")
                    .exceptionally(ex -> {
                        // Error is already captured in actionErrors list by executeActionList
                        // System.err.println("TestPlugin: Action stage failed for test '" + test.name + "'. Skipping Expectations."); // Remove verbose log
                        return null; // Allow expectations/teardown to potentially run
                    });
        }, cog().events.exe); // Schedule action stage on event executor

        // Capture KB state *after* action phase completes, but before expectations/teardown
        CompletableFuture<List<Assertion>> kbStateFuture = actionFuture.thenApplyAsync(result -> {
            if (!actionErrors.isEmpty()) {
                return Collections.emptyList(); // Don't capture state if action failed
            }
            try {
                var kb = context.kb(testKbId);
                // Capture active assertions in the test KB
                return new ArrayList<>(kb.truth.getAllActiveAssertions().stream()
                                        .filter(a -> a.kb().equals(testKbId))
                                        .toList());
            } catch (Exception e) {
                System.err.println("TestPlugin: Error capturing KB state for test '" + test.name + "': " + e.getMessage()); // Keep this error log
                e.printStackTrace(); // Keep stack trace for unexpected errors
                return Collections.emptyList(); // Return empty list on error
            }
        }, cog().events.exe); // Capture KB state on event executor


        CompletableFuture<List<ExpectationResult>> expectationsFuture = CompletableFuture.allOf(actionFuture, kbStateFuture)
            .thenComposeAsync(v -> { // Ensure expectations stage starts on event executor after action and state capture
                Object finalActionResult = actionFuture.join(); // Get result from actionFuture
                if (!actionErrors.isEmpty()) {
                     finalActionResult = null; // Pass null result if action failed
                }
                return checkExpectations(test.expected, testKbId, finalActionResult);
            }, cog().events.exe); // Schedule expectations stage on event executor


        CompletableFuture<TestResult> c = CompletableFuture.allOf(expectationsFuture, kbStateFuture)
            .thenComposeAsync(v -> { // Ensure teardown stage starts on event executor after expectations and state capture
                List<ExpectationResult> expectationResults = expectationsFuture.join(); // Get results from expectationsFuture
                List<Assertion> finalKbState = kbStateFuture.join(); // Get state from kbStateFuture

                return executeActionList(test, testKbId, test.teardown, actionErrors, "Teardown")
                    .exceptionally(ex -> {
                        // Error is already captured in actionErrors list by executeActionList
                        // System.err.println("TestPlugin: Teardown stage failed for test '" + test.name + "'."); // Remove verbose log
                        return null; // Teardown failure shouldn't fail the test itself, but should be reported
                    })
                    // Pass parsing errors, expectation results, action errors, and KB state to the TestResult
                    .thenApply(teardownResult -> new TestResult(test.name, test.parsingErrors(), expectationResults, actionErrors, finalKbState));
            }, cog().events.exe) // Schedule teardown stage on event executor
            .whenCompleteAsync((result, ex) -> { // Keep whenCompleteAsync on event executor for cleanup
                // Clean up the temporary KB/Note regardless of test outcome
                context.removeActiveNote(testKbId);
                // TODO: Decouple from CogNote
                ((CogNote) cog()).removeNote(testKbId);
                // System.out.println("TestPlugin: Test '" + test.name + "' cleanup complete."); // Remove verbose log
            }, cog().events.exe); // Explicitly on event executor

        // System.out.println("TestPlugin: Test '" + test.name + "' run future created."); // Remove verbose log

        return c;
    }

    // Modified to accept actionErrors list and actionType context
    private CompletableFuture<Object> executeActionList(TestDefinition test, String testKbId, List<TestAction> actions, List<ActionError> actionErrors, String actionStageType) {
        CompletableFuture<Object> future = CompletableFuture.completedFuture(null);
        for (var action : actions) {
            // Chain actions sequentially, ensuring each runs on the event executor
            future = future.thenComposeAsync(v -> executeSingleAction(test, testKbId, action)
                    .exceptionally(ex -> {
                        var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
                        // Capture error with action type context and the action itself
                        actionErrors.add(new ActionError(action, actionStageType + " failed: " + cause.getMessage()));
                        System.err.println("TestPlugin: " + actionStageType + " action failed for test '" + test.name + "': " + cause.getMessage()); // Keep this error log
                        cause.printStackTrace(); // Keep stack trace for unexpected errors
                        return null; // Return null to allow subsequent actions/stages to potentially run
                    }),
                cog().events.exe);
        }
        return future;
    }

    private CompletableFuture<Object> executeSingleAction(TestDefinition test, String testKbId, TestAction action) {
        // Execute the action logic. If it returns a completed future, the next stage
        // will be scheduled on the executor provided to thenComposeAsync in executeActionList.
        // If it returns an async future (like from supplyAsync or a tool), the continuation
        // will run on the executor that completes that future (which we try to ensure is cog().events.exe).
        CompletableFuture<Object> actionFuture = executeActionLogic(test, testKbId, action);

        // Apply timeout and exception handling. Note: The exception handling is now primarily
        // done in executeActionList's exceptionally block to capture the specific action.
        // This block primarily handles the timeout wrapping.
        return actionFuture.orTimeout(TEST_ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        // Include action type in timeout message
                        throw new CompletionException(new TimeoutException("Action '" + action.type + "' timed out after " + TEST_ACTION_TIMEOUT_SECONDS + " seconds."));
                    }
                    // Re-throw other exceptions to be caught by the executeActionList exceptionally block
                    throw new CompletionException(cause);
                });
                // Note: No explicit thenApplyAsync here, as the executor is handled by the chaining in executeActionList
    }

    private CompletableFuture<Object> executeActionLogic(TestDefinition test, String testKbId, TestAction action) {
        try {
            return switch (action.type) {
                case "assert" -> CompletableFuture.completedFuture(executeAssertAction(test, testKbId, action));
                case "addRule" -> CompletableFuture.completedFuture(executeAddRuleAction(test, testKbId, action));
                case "removeRuleForm" -> CompletableFuture.completedFuture(executeRemoveRuleFormAction(test, testKbId, action));
                case "retract" -> CompletableFuture.completedFuture(executeRetractAction(test, testKbId, action));
                case "runTool" -> executeRunToolAction(test, testKbId, action); // This might return an async future
                case "query" -> CompletableFuture.completedFuture(executeQueryAction(test, testKbId, action));
                case "wait" -> executeWaitAction(test, testKbId, action); // This uses supplyAsync
                default -> CompletableFuture.failedFuture(new IllegalArgumentException("Unknown action type: " + action.type));
            };
        } catch (Exception e) {
            // Wrap immediate exceptions from action logic setup
            return CompletableFuture.failedFuture(new RuntimeException("Action '" + action.type + "' setup failed: " + e.getMessage(), e));
        }
    }

    private Object executeAssertAction(TestDefinition test, String testKbId, TestAction action) {
        if (!(action.payload instanceof Term.Lst list))
            throw new IllegalArgumentException("Invalid payload for assert: Expected KIF list. Found: " + (action.payload == null ? "null" : action.payload.getClass().getSimpleName()));
        var isNeg = list.op().filter(KIF_OP_NOT::equals).isPresent();
        var s = list.size();
        if (isNeg && s != 2) throw new IllegalArgumentException("Invalid 'not' format for assert.");
        var isEq = !isNeg && list.op().filter(Logic.KIF_OP_EQUAL::equals).isPresent();
        var isOriented = isEq && s == 3 && list.get(1).weight() > list.get(2).weight();
        var type = list.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
        var pri = INPUT_ASSERTION_BASE_PRIORITY / (1.0 + list.weight());
        var pa = new Assertion.PotentialAssertion(list, pri, Set.of(), testKbId, isEq, isNeg, isOriented, testKbId, type, List.of(), 0);

        var committed = context.tryCommit(pa, "test-runner:" + test.name);
        return committed != null ? committed.id() : null;
    }

    private Object executeAddRuleAction(TestDefinition test, String testKbId, TestAction action) {
        if (!(action.payload instanceof Term.Lst ruleForm))
            throw new IllegalArgumentException("Invalid payload for addRule: Expected Rule KIF list. Found: " + (action.payload == null ? "null" : action.payload.getClass().getSimpleName()));
        // TODO: Implement KB-scoped rules. Currently adds to global context.
        var r = Rule.parseRule(Cog.id(ID_PREFIX_RULE), ruleForm, 1.0, testKbId); // Rule is tagged with testKbId, but context.addRule is global
        var added = context.addRule(r);
        return added;
    }

    private Object executeRemoveRuleFormAction(TestDefinition test, String testKbId, TestAction action) {
        if (!(action.payload instanceof Term.Lst ruleForm))
            throw new IllegalArgumentException("Invalid payload for removeRuleForm: Expected Rule KIF list. Found: " + (action.payload == null ? "null" : action.payload.getClass().getSimpleName()));
        // TODO: Implement KB-scoped rules. Currently removes from global context.
        var removed = context.removeRule(ruleForm);
        return removed;
    }

    private Object executeRetractAction(TestDefinition test, String testKbId, TestAction action) {
        if (!(action.payload instanceof Term.Lst retractTargetList) || retractTargetList.size() != 2 || !(retractTargetList.get(0) instanceof Term.Atom typeAtom)) {
            throw new IllegalArgumentException("Invalid payload for retract: Expected (TYPE TARGET). Found: " + (action.payload == null ? "null" : action.payload.toKif()));
        }
        var rtype = typeAtom.value();
        var target = retractTargetList.get(1);
        String targetStr;
        RetractionType retractionType;

        try {
            retractionType = RetractionType.valueOf(rtype.toUpperCase());
        } catch (IllegalArgumentException e) {
             throw new IllegalArgumentException("Invalid retraction type: " + rtype + ". Must be BY_KIF or BY_ID.");
        }

        // Currently only BY_KIF is fully supported by the parsing logic below
        if (retractionType == RetractionType.BY_KIF) {
             if (!(target instanceof Term.Lst list)) {
                 throw new IllegalArgumentException("Invalid target for retract BY_KIF: Expected KIF list. Found: " + target.getClass().getSimpleName() + ". Term: " + target.toKif());
             }
             targetStr = list.toKif();
        } else if (retractionType == RetractionType.BY_ID) {
             if (!(target instanceof Term.Atom atom)) {
                 throw new IllegalArgumentException("Invalid target for retract BY_ID: Expected Atom (ID). Found: " + target.getClass().getSimpleName() + ". Term: " + target.toKif());
             }
             targetStr = atom.value();
        } else {
             // Should not happen due to valueOf check above, but defensive
             throw new IllegalArgumentException("Unsupported retraction type: " + rtype);
        }


        context.events.emit(new RetractionRequestEvent(targetStr, retractionType, "test-runner:" + test.name, testKbId));
        return targetStr;
    }

    private CompletableFuture<Object> executeRunToolAction(TestDefinition test, String testKbId, TestAction action) {
        var toolName = (String) action.toolParams.get("name");
        if (toolName == null || toolName.isBlank())
            throw new IllegalArgumentException("runTool requires 'name' parameter.");

        Map<String, Object> toolParams = new HashMap<>(action.toolParams);
        // TODO: Remove hardcoded KB/Note ID injection. Test should specify if needed.
        toolParams.putIfAbsent("target_kb_id", testKbId);
        toolParams.putIfAbsent("note_id", testKbId);

        var toolOpt = cog().tools.get(toolName);
        if (toolOpt.isEmpty()) throw new IllegalArgumentException("Tool not found: " + toolName);
        // Tool execution might be async, let it run on its own executor if it has one,
        // but the continuation in executeSingleAction will be scheduled on cog().events.exe
        return toolOpt.get().execute(toolParams).thenApply(r -> r);
    }

    private Object executeQueryAction(TestDefinition test, String testKbId, TestAction action) {
        if (!(action.payload instanceof Term.Lst pattern))
            throw new IllegalArgumentException("Invalid payload for query: Expected KIF list pattern. Found: " + (action.payload == null ? "null" : action.payload.getClass().getSimpleName()));
        var queryTypeStr = (String) action.toolParams.getOrDefault("query_type", "ASK_BINDINGS");
        QueryType queryType;
        try {
            queryType = QueryType.valueOf(queryTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid query_type for query action: " + queryTypeStr + ". Must be ASK_BINDINGS or ASK_BOOLEAN.");
        }
        // querySync is synchronous, runs on the calling thread (which should be cog().events.exe)
        return cog().querySync(new Query(Cog.id(ID_PREFIX_QUERY + "test_"), queryType, pattern, testKbId, action.toolParams));
    }

    private CompletableFuture<Object> executeWaitAction(TestDefinition test, String testKbId, TestAction action) {
        if (!(action.payload instanceof Term.Lst conditionList)) {
            throw new IllegalArgumentException("Invalid payload for wait: Expected condition list. Found: " + (action.payload == null ? "null" : action.payload.getClass().getSimpleName()));
        }
        var conditionOpOpt = conditionList.op();
         if (conditionOpOpt.isEmpty()) {
             throw new IllegalArgumentException("Invalid wait condition list: Missing operator. Term: " + conditionList.toKif());
         }
        var conditionOp = conditionOpOpt.get();
        if (conditionList.size() < 2) {
             throw new IllegalArgumentException("Invalid wait condition list: Missing target. Term: " + conditionList.toKif());
         }
        Term conditionTarget = conditionList.get(1);

        long timeoutSeconds = ((Number) action.toolParams.getOrDefault("timeout", TEST_WAIT_DEFAULT_TIMEOUT_SECONDS)).longValue();
        long timeoutMillis = timeoutSeconds * 1000;
        long intervalMillis = ((Number) action.toolParams.getOrDefault("interval", TEST_WAIT_DEFAULT_INTERVAL_MILLIS)).longValue();
        if (timeoutMillis <= 0 || intervalMillis <= 0) {
             throw new IllegalArgumentException("Wait timeout and interval must be positive.");
        }

        // Use supplyAsync to run the polling loop on the event executor without blocking the chain setup
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < timeoutMillis) {
                boolean conditionMet = false;
                try {
                    conditionMet = switch (conditionOp) {
                        case "assertionExists" -> {
                            if (!(conditionTarget instanceof Term.Lst kif))
                                throw new IllegalArgumentException("wait assertionExists requires a KIF list. Found: " + conditionTarget.getClass().getSimpleName() + ". Term: " + conditionTarget.toKif());
                            yield findAssertionInTestOrGlobalKb(kif, testKbId).isPresent();
                        }
                        case "assertionDoesNotExist" -> {
                            if (!(conditionTarget instanceof Term.Lst kif))
                                throw new IllegalArgumentException("wait assertionDoesNotExist requires a KIF list. Found: " + conditionTarget.getClass().getSimpleName() + ". Term: " + conditionTarget.toKif());
                            yield findAssertionInTestOrGlobalKb(kif, testKbId).isEmpty();
                        }
                        default -> throw new IllegalArgumentException("Unknown wait condition type: " + conditionOp);
                    };

                    if (conditionMet) return null; // Condition met, complete successfully

                } catch (IllegalArgumentException e) {
                     throw new CompletionException(e); // Wrap parsing/argument errors
                } catch (Exception e) {
                    // Log other exceptions during condition check but don't fail the wait immediately
                    System.err.println("TestPlugin: Error checking wait condition: " + e.getMessage()); // Keep this error log
                }

                try {
                    Thread.sleep(intervalMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(new InterruptedException("Wait interrupted"));
                }
            }
            // Timeout occurred
            throw new CompletionException(new TimeoutException("Wait condition not met within " + timeoutSeconds + " seconds: " + conditionList.toKif()));
        }, cog().events.exe); // Run the polling loop on the event executor
    }


    private Optional<Assertion> findAssertionInTestOrGlobalKb(Term.Lst kif, String testKbId) {
        // TODO: Adjust based on KB-scoped rules/assertions
        return context.findAssertionByKif(kif, testKbId).or(() -> context.findAssertionByKif(kif, context.kbGlobal().id));
    }

    private CompletableFuture<List<ExpectationResult>> checkExpectations(List<TestExpected> expectations, String testKbId, @Nullable Object actionResult) {
        List<ExpectationResult> results = new ArrayList<>(); // Use regular list, populated sequentially
        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (var expected : expectations) {
            // Chain expectation checks sequentially on the event executor
            future = future.thenComposeAsync(v ->
                checkSingleExpectation(expected, testKbId, actionResult)
                    .thenAccept(results::add), // Add result to the list (runs on the executor that completed checkSingleExpectation)
                cog().events.exe // Ensure the next expectation check starts on the event executor
            );
        }
        return future.thenApply(v -> results); // Return the list of results
    }

    private CompletableFuture<ExpectationResult> checkSingleExpectation(TestExpected expected, String testKbId, @Nullable Object actionResult) {
        // Use supplyAsync to run the check logic on the event executor
        return CompletableFuture.supplyAsync(() -> {
            try {
                var kb = context.kb(testKbId);

                Cog.Answer answer = null;
                boolean requiresAnswer = switch (expected.type) {
                    case "expectedResult", "expectedBindings" -> true;
                    default -> false;
                };

                if (requiresAnswer) {
                    if (!(actionResult instanceof Cog.Answer queryAnswer)) {
                        String reason = "Requires action result to be a query answer, but got: " + (actionResult == null ? "null" : actionResult.getClass().getSimpleName());
                        System.err.println("TestPlugin: Expectation '" + expected.type + "' failed: " + reason); // Keep this error log
                        // Pass the actual result (or lack thereof) to the result object
                        return new ExpectationResult(expected, false, reason, actionResult);
                    }
                    answer = queryAnswer;
                }

                Optional<String> failureReason = switch (expected.type) {
                    case "expectedResult" -> checkExpectedResult(expected, answer);
                    case "expectedBindings" -> checkExpectedBindings(expected, answer);
                    case "expectedAssertionExists" -> checkExpectedAssertionExists(expected, testKbId);
                    case "expectedAssertionDoesNotExist" -> checkExpectedAssertionDoesNotExist(expected, testKbId);
                    case "expectedRuleExists" -> checkExpectedRuleExists(expected);
                    case "expectedRuleDoesNotExist" -> checkExpectedRuleDoesNotExist(expected);
                    case "expectedKbSize" -> checkExpectedKbSize(expected, kb);
                    case "expectedToolResult" -> checkExpectedToolResult(expected, actionResult);
                    case "expectedToolResultContains" -> checkExpectedToolResultContains(expected, actionResult);
                    default -> Optional.of("Internal error: Unknown expectation type '" + expected.type + "' passed to checkSingleExpectation."); // Should be caught by parseExpectation
                };

                if (failureReason.isEmpty()) {
                    // Pass the actionResult even on success, for potential future reporting needs
                    return new ExpectationResult(expected, true, null, actionResult);
                } else {
                    System.err.println("TestPlugin: Expectation '" + expected.type + "' failed: " + failureReason.get()); // Keep this error log
                    // Pass the actual result to the result object on failure
                    return new ExpectationResult(expected, false, failureReason.get(), actionResult);
                }

            } catch (Exception e) {
                String reason = "Check failed with error: " + e.getMessage();
                System.err.println("TestPlugin: Expectation check failed with error: " + e.getMessage()); // Keep this error log
                e.printStackTrace(); // Keep stack trace for unexpected errors
                // Pass the actual result to the result object on error
                return new ExpectationResult(expected, false, reason, actionResult);
            }
        }, cog().events.exe); // Run expectation checks on the event executor
    }

    // --- Expectation Check Implementations (Modified to return Optional<String>) ---

    private Optional<String> checkExpectedResult(TestExpected expected, Cog.Answer answer) {
        if (!(expected.value instanceof Boolean expectedBoolean)) {
            return Optional.of("Internal error - expected value is not a Boolean. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
        }
        boolean passed = expectedBoolean == (answer.status() == Cog.QueryStatus.SUCCESS);
        if (!passed) {
            return Optional.of("Expected status " + (expectedBoolean ? "SUCCESS" : "FAILURE") + ", but got " + answer.status());
        }
        return Optional.empty();
    }

    private Optional<String> checkExpectedBindings(TestExpected expected, Cog.Answer answer) {
        // Expected value is now stored as List<Map<Term.Var, Term>> by parseExpectedBindings
        if (!(expected.value instanceof List<?> expectedBindingsList)) {
             return Optional.of("Internal error - expected value is not a List. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
        }

        List<Map<Term.Var, Term>> actualBindings = answer.bindings();

        // Case 1: Expecting no bindings (empty list of solutions)
        if (expectedBindingsList.isEmpty()) {
            boolean passed = actualBindings.isEmpty();
            if (!passed) {
                // System.err.println("TestPlugin DEBUG: checkExpectedBindings - Expected empty, got non-empty."); // Remove verbose log
                // System.err.println("TestPlugin DEBUG: Actual bindings: " + actualBindings); // Remove verbose log
                return Optional.of("Expected no bindings, but got " + actualBindings.size() + " bindings.");
            }
            // System.out.println("TestPlugin DEBUG: checkExpectedBindings - Expected empty, got empty. Passed."); // Remove verbose log
            return Optional.empty();
        }

        // Case 2: Expecting specific bindings
        // Ensure the list contains maps (check first element if not empty)
        if (!(expectedBindingsList.get(0) instanceof Map)) {
             return Optional.of("Internal error - expected value list does not contain Maps.");
        }

        // Convert expected and actual bindings to a comparable format (sets of sorted strings)
        Set<String> expectedBindingStrings = ((List<Map<Term.Var, Term>>) expectedBindingsList).stream()
            .map(bindingMap -> {
                List<String> entryStrings = new ArrayList<>();
                bindingMap.forEach((var, term) -> entryStrings.add(var.name() + "=" + termValueToString(term))); // Use helper
                Collections.sort(entryStrings); // Sort entries within a binding
                return String.join(",", entryStrings);
            })
            .collect(Collectors.toSet());

        Set<String> actualBindingStrings = actualBindings.stream()
            .map(bindingMap -> {
                List<String> entryStrings = new ArrayList<>();
                bindingMap.forEach((var, term) -> entryStrings.add(var.name() + "=" + termValueToString(term))); // Use helper
                Collections.sort(entryStrings); // Sort entries within a binding
                return String.join(",", entryStrings);
            })
            .collect(Collectors.toSet());

        boolean passed = Objects.equals(expectedBindingStrings, actualBindingStrings);

        if (!passed) {
            // System.err.println("TestPlugin DEBUG: checkExpectedBindings - Expected specific, got different."); // Remove verbose log
            // System.err.println("TestPlugin DEBUG: Expected binding strings: " + expectedBindingStrings); // Remove verbose log
            // System.err.println("TestPlugin DEBUG: Actual binding strings: " + actualBindingStrings); // Remove verbose log
            return Optional.of("Expected bindings " + expectedBindingStrings + ", but got " + actualBindingStrings);
        }
        // System.out.println("TestPlugin DEBUG: checkExpectedBindings - Expected specific, got match. Passed."); // Remove verbose log
        return Optional.empty();
    }

    private Optional<String> checkExpectedAssertionExists(TestExpected expected, String testKbId) {
        if (!(expected.value instanceof Term.Lst expectedKif)) {
            return Optional.of("Internal error - expected value is not a Term.Lst. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
        }
        boolean passed = findAssertionInTestOrGlobalKb(expectedKif, testKbId).isPresent();
         if (!passed) {
             return Optional.of("Assertion not found: " + expectedKif.toKif());
         }
        return Optional.empty();
    }

    private Optional<String> checkExpectedAssertionDoesNotExist(TestExpected expected, String testKbId) {
        if (!(expected.value instanceof Term.Lst expectedKif)) {
            return Optional.of("Internal error - expected value is not a Term.Lst. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
        }
        boolean passed = findAssertionInTestOrGlobalKb(expectedKif, testKbId).isEmpty();
        if (!passed) {
            return Optional.of("Assertion found unexpectedly: " + expectedKif.toKif());
        }
        return Optional.empty();
    }

    private Optional<String> checkExpectedRuleExists(TestExpected expected) {
        if (!(expected.value instanceof Term.Lst ruleForm)) {
            return Optional.of("Internal error - expected value is not a Term.Lst. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
        }
        // TODO: Adjust based on KB-scoped rules. Currently checks global context.
        boolean passed = context.rules().stream().anyMatch(r -> r.form().equals(ruleForm));
        if (!passed) {
            return Optional.of("Rule not found: " + ruleForm.toKif());
        }
        return Optional.empty();
    }

    private Optional<String> checkExpectedRuleDoesNotExist(TestExpected expected) {
        if (!(expected.value instanceof Term.Lst ruleForm)) {
            return Optional.of("Internal error - expected value is not a Term.Lst. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
        }
        // TODO: Adjust based on KB-scoped rules. Currently checks global context.
        boolean passed = context.rules().stream().noneMatch(r -> r.form().equals(ruleForm));
        if (!passed) {
            return Optional.of("Rule found unexpectedly: " + ruleForm.toKif());
        }
        return Optional.empty();
    }

    private Optional<String> checkExpectedKbSize(TestExpected expected, Logic.Knowledge kb) {
        if (!(expected.value instanceof Integer expectedSize)) {
            return Optional.of("Internal error - expected value is not an Integer. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
        }
        int actualSize = kb.getAssertionCount();
        boolean passed = actualSize == expectedSize;
        if (!passed) {
            return Optional.of("Expected KB size " + expectedSize + ", but got " + actualSize);
        }
        return Optional.empty();
    }

    private Optional<String> checkExpectedToolResult(TestExpected expected, @Nullable Object actionResult) {
        boolean passed = Objects.equals(actionResult, expected.value);
        if (!passed) {
            return Optional.of("Expected tool result " + expected.value + ", but got " + actionResult);
        }
        return Optional.empty();
    }

    private Optional<String> checkExpectedToolResultContains(TestExpected expected, @Nullable Object actionResult) {
        if (!(expected.value instanceof String expectedSubstring)) {
            return Optional.of("Internal error - expected value is not a String. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
        }
        if (!(actionResult instanceof String actualResultString)) {
            return Optional.of("Action result is not a String. Found: " + (actionResult == null ? "null" : actionResult.getClass().getSimpleName()));
        }
        boolean passed = actualResultString.contains(expectedSubstring);
        if (!passed) {
            return Optional.of("Tool result '" + actualResultString + "' does not contain '" + expectedSubstring + "'");
        }
        return Optional.empty();
    }


    // --- Parsing Logic (Modified to collect errors) ---

    private List<TestDefinition> parseTestDefinitions(String text) throws ParseException {
        List<TestDefinition> definitions = new ArrayList<>();
        if (text == null || text.isBlank()) return definitions;

        for (var term : Logic.KifParser.parseKif(text)) {
            if (term instanceof Term.Lst list && list.size() >= 2 && list.op().filter("test"::equals).isPresent()) {
                var nameTerm = list.get(1);
                String name;
                if (nameTerm instanceof Term.Atom) {
                    name = ((Term.Atom) nameTerm).value();
                    if (name == null || name.isBlank()) {
                        String error = "Skipping test with invalid name format (empty or blank Atom): " + list.toKif();
                        System.err.println("TestPlugin: " + error); // Keep this error log
                        // Add a dummy test result to report this parsing error
                        definitions.add(new TestDefinition("Unnamed Test (Parse Error)", Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), List.of(error)));
                        continue;
                    }
                } else {
                    String error = "Skipping test with invalid name format (not an Atom): " + list.toKif();
                    System.err.println("TestPlugin: " + error); // Keep this error log
                    // Add a dummy test result to report this parsing error
                    definitions.add(new TestDefinition("Unnamed Test (Parse Error)", Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), List.of(error)));
                    continue;
                }

                List<TestAction> setup = new ArrayList<>();
                List<TestAction> action = new ArrayList<>();
                List<TestExpected> expected = new ArrayList<>();
                List<TestAction> teardown = new ArrayList<>();
                List<String> parsingErrors = new ArrayList<>(); // Collect errors for this test

                for (var i = 2; i < list.size(); i++) {
                    var sectionTerm = list.get(i);
                    if (!(sectionTerm instanceof Term.Lst sectionList) || sectionList.terms.isEmpty()) {
                        String error = "Invalid section format: " + sectionTerm.toKif();
                        System.err.println("TestPlugin: Test '" + name + "' | " + error); // Keep this error log
                        parsingErrors.add(error);
                        continue;
                    }
                    var sectionOpOpt = sectionList.op();
                    if (sectionOpOpt.isEmpty()) {
                        String error = "Section without operator: " + sectionList.toKif();
                        System.err.println("TestPlugin: Test '" + name + "' | " + error); // Keep this error log
                        parsingErrors.add(error);
                        continue;
                    }
                    var sectionOp = sectionOpOpt.get();
                    var sectionContents = sectionList.terms.stream().skip(1).toList();

                    try {
                        switch (sectionOp) {
                            case "setup" -> setup.addAll(parseActions(sectionContents, parsingErrors));
                            case "action" -> action = parseActions(sectionContents, parsingErrors);
                            case "expected" -> expected.addAll(parseExpectations(sectionContents, parsingErrors));
                            case "teardown" -> teardown.addAll(parseActions(sectionContents, parsingErrors));
                            default -> {
                                String error = "Unknown section type '" + sectionOp + "': " + sectionList.toKif();
                                System.err.println("TestPlugin: Test '" + name + "' | " + error); // Keep this error log
                                parsingErrors.add(error);
                            }
                        }
                    } catch (Exception e) {
                         // Catch unexpected errors during section parsing
                         String error = "Unexpected error parsing section '" + sectionOp + "': " + e.getMessage() + " | Term: " + sectionList.toKif();
                         System.err.println("TestPlugin: Test '" + name + "' | " + error); // Keep this error log
                         e.printStackTrace(); // Log stack trace for unexpected errors
                         parsingErrors.add(error);
                    }
                }

                if (action.isEmpty() && parsingErrors.isEmpty()) { // Only skip if no action AND no parsing errors in other sections
                     System.err.println("TestPlugin: Skipping test '" + name + "' because the 'action' section is missing or empty."); // Keep this log
                     continue; // Skip tests with no action section unless there were parsing errors we need to report
                }

                definitions.add(new TestDefinition(name, setup, action, expected, teardown, parsingErrors));
            } else {
                // System.out.println("TestPlugin: Ignoring non-test top-level term in definitions: " + term.toKif()); // Remove verbose log
            }
        }
        return definitions;
    }

    private List<TestAction> parseActions(List<Term> terms, List<String> errors) {
        List<TestAction> actions = new ArrayList<>();
        for (var term : terms) {
            try {
                actions.add(parseAction(term));
            } catch (IllegalArgumentException e) {
                String error = "Invalid action term: " + term.toKif() + " | Error: " + e.getMessage();
                System.err.println("TestPlugin: " + error); // Keep this error log
                errors.add(error);
            } catch (Exception e) {
                 // Catch unexpected errors during action parsing
                 String error = "Unexpected error parsing action term: " + e.getMessage() + " | Term: " + term.toKif();
                 System.err.println("TestPlugin: " + error); // Keep this error log
                 e.printStackTrace(); // Log stack trace for unexpected errors
                 errors.add(error);
            }
        }
        return actions;
    }

    private TestAction parseAction(Term x) {
        if (!(x instanceof Term.Lst actionList) || actionList.terms.isEmpty()) {
            throw new IllegalArgumentException("Action must be a non-empty list.");
        }
        var opOpt = actionList.op();
        if (opOpt.isEmpty()) {
            throw new IllegalArgumentException("Action list must have an operator. Term: " + actionList.toKif());
        }
        var op = opOpt.get();

        return switch (op) {
            case "assert" -> parseAssertAction(actionList);
            case "addRule" -> parseAddRuleAction(actionList);
            case "removeRuleForm" -> parseRemoveRuleFormAction(actionList);
            case "retract" -> parseRetractAction(actionList);
            case "runTool" -> parseRunToolAction(actionList);
            case "query" -> parseQueryAction(actionList);
            case "wait" -> parseWaitAction(actionList);
            default -> throw new IllegalArgumentException("Unknown action operator: " + op + ". Term: " + actionList.toKif());
        };
    }

    private TestAction parseAssertAction(Term.Lst actionList) {
        if (actionList.size() != 2)
            throw new IllegalArgumentException("assert action requires exactly one argument (the KIF form). Found size: " + actionList.size() + ". Term: " + actionList.toKif());
        Term payload = actionList.get(1);
        if (!(payload instanceof Term.Lst)) {
             throw new IllegalArgumentException("assert action requires a KIF list as its argument. Found: " + payload.getClass().getSimpleName() + ". Term: " + actionList.toKif());
        }
        return new TestAction("assert", payload, new HashMap<>());
    }

    private TestAction parseAddRuleAction(Term.Lst actionList) {
        if (actionList.size() != 2)
            throw new IllegalArgumentException("addRule action requires exactly one argument (the Rule KIF form). Found size: " + actionList.size() + ". Term: " + actionList.toKif());
        Term payload = actionList.get(1);
        if (!(payload instanceof Term.Lst)) {
             throw new IllegalArgumentException("addRule action requires a KIF list as its argument. Found: " + payload.getClass().getSimpleName() + ". Term: " + actionList.toKif());
        }
        return new TestAction("addRule", payload, new HashMap<>());
    }

    private TestAction parseRemoveRuleFormAction(Term.Lst actionList) {
        if (actionList.size() != 2)
            throw new IllegalArgumentException("removeRuleForm action requires exactly one argument (the Rule KIF form). Found size: " + actionList.size() + ". Term: " + actionList.toKif());
        Term payload = actionList.get(1);
        if (!(payload instanceof Term.Lst)) {
             throw new IllegalArgumentException("removeRuleForm action requires a KIF list as its argument. Found: " + payload.getClass().getSimpleName() + ". Term: " + actionList.toKif());
        }
        return new TestAction("removeRuleForm", payload, new HashMap<>());
    }

    private TestAction parseRetractAction(Term.Lst actionList) {
        if (actionList.size() != 2) {
            throw new IllegalArgumentException("retract action requires exactly one argument (the target list). Found size: " + actionList.size() + ". Term: " + actionList.toKif());
        }
        Term targetTerm = actionList.get(1);
        if (!(targetTerm instanceof Term.Lst retractTargetList)) {
             throw new IllegalArgumentException("retract action's argument must be a list (TYPE TARGET). Found: " + targetTerm.getClass().getSimpleName() + ". Term: " + actionList.toKif());
        }
        if (retractTargetList.size() != 2) {
             throw new IllegalArgumentException("retract target list must be size 2 (TYPE TARGET). Found size: " + retractTargetList.size() + ". Term: " + actionList.toKif());
        }
        Term typeTerm = retractTargetList.get(0);
        if (!(typeTerm instanceof Term.Atom typeAtom)) {
             throw new IllegalArgumentException("retract target list's first element must be an Atom (TYPE). Found: " + typeTerm.getClass().getSimpleName() + ". Term: " + actionList.toKif());
        }
        // Validate type atom value early
        try {
            RetractionType.valueOf(typeAtom.value().toUpperCase());
        } catch (IllegalArgumentException e) {
             throw new IllegalArgumentException("Invalid retraction type: " + typeAtom.value() + ". Must be BY_KIF or BY_ID.");
        }

        return new TestAction("retract", retractTargetList, new HashMap<>());
    }

    private TestAction parseRunToolAction(Term.Lst actionList) {
        Map<String, Object> toolParams = new HashMap<>();
        if (actionList.size() > 1) {
             if (actionList.size() == 2 && actionList.get(1) instanceof Term.Lst paramsList && paramsList.op().filter("params"::equals).isPresent()) {
                toolParams = parseParams(paramsList);
            } else {
                 throw new IllegalArgumentException("runTool action parameters must be in a (params (...)) list as the second argument. Term: " + actionList.toKif());
            }
        }
        var toolName = (String) toolParams.get("name");
        if (toolName == null || toolName.isBlank())
            throw new IllegalArgumentException("runTool requires 'name' parameter within params. Term: " + actionList.toKif());

        return new TestAction("runTool", null, toolParams);
    }

    private TestAction parseQueryAction(Term.Lst actionList) {
        if (actionList.size() < 2)
            throw new IllegalArgumentException("query action requires at least one argument (the pattern). Term: " + actionList.toKif());
        Term payload = actionList.get(1);
        if (!(payload instanceof Term.Lst)) {
             throw new IllegalArgumentException("query action requires a KIF list pattern as its first argument. Found: " + payload.getClass().getSimpleName() + ". Term: " + actionList.toKif());
        }


        Map<String, Object> toolParams = new HashMap<>();
        if (actionList.size() > 2) {
            if (actionList.size() == 3 && actionList.get(2) instanceof Term.Lst paramsList && paramsList.op().filter("params"::equals).isPresent()) {
                toolParams = parseParams(paramsList);
            } else {
                 throw new IllegalArgumentException("query action parameters must be in a (params (...)) list as the third argument. Term: " + actionList.toKif());
            }
        }
        // Validate query_type param early
        if (toolParams.containsKey("query_type")) {
            var queryTypeStr = (String) toolParams.get("query_type");
            try {
                QueryType.valueOf(queryTypeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                 throw new IllegalArgumentException("Invalid query_type parameter: " + queryTypeStr + ". Must be ASK_BINDINGS or ASK_BOOLEAN.");
            }
        }

        return new TestAction("query", payload, toolParams);
    }

    private TestAction parseWaitAction(Term.Lst actionList) {
        if (actionList.size() < 2)
            throw new IllegalArgumentException("wait action requires at least one argument (the condition list). Term: " + actionList.toKif());
        Term payload = actionList.get(1);

        Map<String, Object> toolParams = new HashMap<>();
        if (actionList.size() > 2) {
            if (actionList.size() == 3 && actionList.get(2) instanceof Term.Lst paramsList && paramsList.op().filter("params"::equals).isPresent()) {
                toolParams = parseParams(paramsList);
            } else {
                 throw new IllegalArgumentException("wait action parameters must be in a (params (...)) list as the third argument. Term: " + actionList.toKif());
            }
        }
        if (!(payload instanceof Term.Lst conditionList) || conditionList.terms.isEmpty() || conditionList.op().isEmpty()) {
             throw new IllegalArgumentException("wait action requires a non-empty list with an operator as its argument (the condition list). Found: " + (payload == null ? "null" : payload.getClass().getSimpleName()) + ". Term: " + actionList.toKif());
        }
        // Validate condition type early
        var conditionOpOpt = conditionList.op();
        if (conditionOpOpt.isEmpty()) {
             throw new IllegalArgumentException("wait condition list must have an operator. Term: " + conditionList.toKif());
        }
        var conditionOp = conditionOpOpt.get();
        if (!Set.of("assertionExists", "assertionDoesNotExist").contains(conditionOp)) {
             throw new IllegalArgumentException("Unknown wait condition type: " + conditionOp + ". Must be assertionExists or assertionDoesNotExist. Term: " + actionList.toKif());
        }
        if (conditionList.size() != 2) {
             throw new IllegalArgumentException("wait condition list must have exactly one argument (the target KIF list). Found size: " + conditionList.size() + ". Term: " + actionList.toKif());
        }
        if (!(conditionList.get(1) instanceof Term.Lst)) {
             throw new IllegalArgumentException("wait condition target must be a KIF list. Found: " + conditionList.get(1).getClass().getSimpleName() + ". Term: " + actionList.toKif());
        }

        // Validate timeout/interval params early
        if (toolParams.containsKey("timeout")) {
            try {
                long timeout = ((Number) toolParams.get("timeout")).longValue();
                if (timeout <= 0) throw new NumberFormatException();
            } catch (ClassCastException | NumberFormatException e) {
                 throw new IllegalArgumentException("Invalid 'timeout' parameter for wait: Must be a positive number. Term: " + actionList.toKif());
            }
        }
         if (toolParams.containsKey("interval")) {
            try {
                long interval = ((Number) toolParams.get("interval")).longValue();
                if (interval <= 0) throw new NumberFormatException();
            } catch (ClassCastException | NumberFormatException e) {
                 throw new IllegalArgumentException("Invalid 'interval' parameter for wait: Must be a positive number. Term: " + actionList.toKif());
            }
        }


        return new TestAction("wait", payload, toolParams);
    }


    private List<TestExpected> parseExpectations(List<Term> terms, List<String> errors) {
        List<TestExpected> expectations = new ArrayList<>();
        for (var term : terms) {
            try {
                expectations.add(parseExpectation(term));
            } catch (IllegalArgumentException e) {
                String error = "Invalid expectation term: " + term.toKif() + " | Error: " + e.getMessage();
                System.err.println("TestPlugin: " + error); // Keep this error log
                errors.add(error);
            } catch (Exception e) {
                 // Catch unexpected errors during expectation parsing
                 String error = "Unexpected error parsing expectation term: " + e.getMessage() + " | Term: " + term.toKif();
                 System.err.println("TestPlugin: " + error); // Keep this error log
                 e.printStackTrace(); // Log stack trace for unexpected errors
                 errors.add(error);
            }
        }
        return expectations;
    }

    private TestExpected parseExpectation(Term term) {
        if (!(term instanceof Term.Lst expectedList) || expectedList.terms.isEmpty()) {
            throw new IllegalArgumentException("Expectation must be a non-empty list. Term: " + term.toKif());
        }
        var opOpt = expectedList.op();
        if (opOpt.isEmpty()) {
            throw new IllegalArgumentException("Expectation list must have an operator. Term: " + expectedList.toKif());
        }
        var op = opOpt.get();

        if (expectedList.size() < 2) {
             throw new IllegalArgumentException("Expectation '" + op + "' requires at least one argument. Term: " + expectedList.toKif());
        }
        Term expectedValueTerm = expectedList.get(1);

        return switch (op) {
            case "expectedResult" -> parseExpectedResult(op, expectedValueTerm, expectedList);
            case "expectedBindings" -> parseExpectedBindings(op, expectedValueTerm, expectedList);
            case "expectedAssertionExists" -> parseExpectedAssertionExists(op, expectedValueTerm, expectedList);
            case "expectedAssertionDoesNotExist" -> parseExpectedAssertionDoesNotExist(op, expectedValueTerm, expectedList);
            case "expectedRuleExists" -> parseExpectedRuleExists(op, expectedValueTerm, expectedList);
            case "expectedRuleDoesNotExist" -> parseExpectedRuleDoesNotExist(op, expectedValueTerm, expectedList);
            case "expectedKbSize" -> parseExpectedKbSize(op, expectedValueTerm, expectedList);
            case "expectedToolResult" -> parseExpectedToolResult(op, expectedValueTerm, expectedList);
            case "expectedToolResultContains" -> parseExpectedToolResultContains(op, expectedValueTerm, expectedList);
            default -> throw new IllegalArgumentException("Unknown expectation operator: " + op + ". Term: " + expectedList.toKif());
        };
    }

    private TestExpected parseExpectedResult(String op, Term expectedValueTerm, Term.Lst fullTerm) {
        if (!(expectedValueTerm instanceof Term.Atom))
            throw new IllegalArgumentException(op + " requires a single boolean atom (true/false). Found: " + expectedValueTerm.getClass().getSimpleName() + ". Term: " + fullTerm.toKif());
        String value = ((Term.Atom) expectedValueTerm).value();

        if (!value.equals("true") && !value.equals("false"))
            throw new IllegalArgumentException(op + " value must be 'true' or 'false'. Found: '" + value + "'. Term: " + fullTerm.toKif());
        return new TestExpected(op, Boolean.parseBoolean(value));
    }

    private TestExpected parseExpectedBindings(String op, Term expectedValueTerm, Term.Lst fullTerm) {
        if (!(expectedValueTerm instanceof Term.Lst expectedBindingsListTerm)) {
             throw new IllegalArgumentException(op + " requires a list of binding pairs ((?V1 Val1) ...) or () or (()). Found: " + expectedValueTerm.getClass().getSimpleName() + ". Term: " + fullTerm.toKif());
        }

        // Handle empty list case: () or (())
        if (expectedBindingsListTerm.terms.isEmpty() || (expectedBindingsListTerm.terms.size() == 1 && expectedBindingsListTerm.get(0) instanceof Term.Lst innerList && innerList.terms.isEmpty())) {
             // Represents expectation of *no* bindings (empty list of solutions)
             return new TestExpected(op, Collections.emptyList()); // Store as empty list of maps
        }

        List<Map<Term.Var, Term>> expectedBindings = new ArrayList<>();
        for (var bindingPairTerm : expectedBindingsListTerm.terms) {
             if (bindingPairTerm instanceof Term.Lst bindingPair && bindingPair.size() == 2 && bindingPair.get(0) instanceof Term.Var var && bindingPair.get(1) instanceof Term value) {
                expectedBindings.add(Map.of(var, value));
            } else {
                throw new IllegalArgumentException("Invalid binding pair format in expected value: " + bindingPairTerm.toKif() + ". Term: " + fullTerm.toKif());
            }
        }
        return new TestExpected(op, expectedBindings); // Store as list of maps
    }

    private TestExpected parseExpectedAssertionExists(String op, Term expectedValueTerm, Term.Lst fullTerm) {
        if (!(expectedValueTerm instanceof Term.Lst kif))
            throw new IllegalArgumentException(op + " requires a single KIF list. Found: " + expectedValueTerm.getClass().getSimpleName() + ". Term: " + fullTerm.toKif());
        return new TestExpected(op, kif);
    }

    private TestExpected parseExpectedAssertionDoesNotExist(String op, Term expectedValueTerm, Term.Lst fullTerm) {
        if (!(expectedValueTerm instanceof Term.Lst kif))
            throw new IllegalArgumentException(op + " requires a single KIF list. Found: " + expectedValueTerm.getClass().getSimpleName() + ". Term: " + fullTerm.toKif());
        return new TestExpected(op, kif);
    }

    private TestExpected parseExpectedRuleExists(String op, Term expectedValueTerm, Term.Lst fullTerm) {
        if (!(expectedValueTerm instanceof Term.Lst ruleForm))
            throw new IllegalArgumentException(op + " requires a single rule KIF list. Found: " + expectedValueTerm.getClass().getSimpleName() + ". Term: " + fullTerm.toKif());
        return new TestExpected(op, ruleForm);
    }

    private TestExpected parseExpectedRuleDoesNotExist(String op, Term expectedValueTerm, Term.Lst fullTerm) {
        if (!(expectedValueTerm instanceof Term.Lst ruleForm))
            throw new IllegalArgumentException(op + " requires a single rule KIF list. Found: " + expectedValueTerm.getClass().getSimpleName() + ". Term: " + fullTerm.toKif());
        return new TestExpected(op, ruleForm);
    }

    private TestExpected parseExpectedKbSize(String op, Term expectedValueTerm, Term.Lst fullTerm) {
        if (!(expectedValueTerm instanceof Term.Atom))
            throw new IllegalArgumentException(op + " requires a single integer atom. Found: " + expectedValueTerm.getClass().getSimpleName() + ". Term: " + fullTerm.toKif());
        String value = ((Term.Atom) expectedValueTerm).value();
        try {
            return new TestExpected(op, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(op + " value must be an integer. Found: '" + value + "'. Term: " + fullTerm.toKif());
        }
    }

    private TestExpected parseExpectedToolResult(String op, Term expectedValueTerm, Term.Lst fullTerm) {
        return new TestExpected(op, termToObject(expectedValueTerm));
    }

    private TestExpected parseExpectedToolResultContains(String op, Term expectedValueTerm, Term.Lst fullTerm) {
        if (!(expectedValueTerm instanceof Term.Atom)) {
            throw new IllegalArgumentException(op + " requires a single Atom containing the expected substring. Found: " + expectedValueTerm.getClass().getSimpleName() + ". Term: " + fullTerm.toKif());
        }
        return new TestExpected(op, termToObject(expectedValueTerm));
    }


    private Map<String, Object> parseParams(Term.Lst paramsList) {
        Map<String, Object> params = new HashMap<>();
        if (paramsList.op().filter("params"::equals).isEmpty()) {
            throw new IllegalArgumentException("Parameter list must start with 'params'. Term: " + paramsList.toKif());
        }
        for (var paramTerm : paramsList.terms.stream().skip(1).toList()) {
            if (paramTerm instanceof Term.Lst paramPair && paramPair.size() == 2 && paramPair.get(0) instanceof Term.Atom) {
                String value = ((Term.Atom) paramPair.get(0)).value();
                params.put(value, termToObject(paramPair.get(1)));
            } else {
                throw new IllegalArgumentException("Invalid parameter format: " + paramTerm.toKif() + ". Expected (key value). Term: " + paramsList.toKif());
            }
        }
        return params;
    }

    private Object termToObject(Term term) {
        return switch (term) {
            case Term.Atom atom -> {
                try {
                    yield Integer.parseInt(atom.value());
                } catch (NumberFormatException e1) {
                    try {
                        yield Double.parseDouble(atom.value());
                    } catch (NumberFormatException e2) {
                        if (atom.value().equalsIgnoreCase("true")) yield true;
                        if (atom.value().equalsIgnoreCase("false")) yield false;
                        yield atom.value();
                    }
                }
            }
            case Term.Lst list -> list;
            case Term.Var var -> var;
        };
    }

    private String termValueToString(Object value) {
        if (value instanceof Term term) {
            return term.toKif();
        }
        return String.valueOf(value);
    }

    private String formatParseException(ParseException e, String sourceText) {
        String message = e.getMessage();
        Matcher matcher = PARSE_ERROR_LOCATION_PATTERN.matcher(message);
        int lineNum = -1;
        int colNum = -1;
        String baseMessage = message;

        if (matcher.find()) {
            try {
                lineNum = Integer.parseInt(matcher.group(1));
                colNum = Integer.parseInt(matcher.group(2));
                baseMessage = message.substring(0, matcher.start());
            } catch (NumberFormatException ex) {
                System.err.println("TestPlugin: Could not parse line/col from ParseException message: " + message); // Keep this error log
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(baseMessage);

        if (lineNum > 0 && colNum > 0) {
            String[] lines = sourceText.split("\\r?\\n");
            if (lineNum <= lines.length) {
                String errorLine = lines[lineNum - 1];
                sb.append("\n  --> at line ").append(lineNum).append(" col ").append(colNum).append(":\n");
                sb.append("  ").append(errorLine).append("\n");
                sb.append("  ").append(" ".repeat(Math.max(0, colNum - 1))).append("^\n");
            } else {
                sb.append("\n  (Could not retrieve line context for line ").append(lineNum).append(")");
            }
        } else {
             sb.append("\n  (No line/column information available)");
        }

        return sb.toString();
    }

    private String formatTestResults(List<TestResult> results) {
        var sb = new StringBuilder();
        sb.append("--- Test Results (").append(java.time.LocalDateTime.now()).append(") ---\n\n");
        var passedCount = 0;
        var failedCount = 0;

        for (var result : results) {
            boolean overallPassed = result.isOverallPassed(); // Use the helper method
            sb.append(overallPassed ? "PASS" : "FAIL").append(": ").append(result.name).append("\n");

            if (!result.parsingErrors().isEmpty()) { // Report parsing errors first
                sb.append("  Parsing Errors:\n");
                result.parsingErrors().forEach(error -> sb.append("    - ").append(error).append("\n"));
            }

            if (!result.actionErrors.isEmpty()) {
                sb.append("  Action Errors:\n");
                // Iterate through ActionError objects
                result.actionErrors.forEach(error -> {
                    sb.append("    - [").append(error.action().type()).append("] ").append(error.message()).append("\n");
                    // Only show payload/params if they exist
                    if (error.action().payload() != null) {
                         sb.append("      Payload: ").append(error.action().payload().toKif()).append("\n");
                    }
                    if (!error.action().toolParams().isEmpty()) {
                        sb.append("      Params: ").append(error.action().toolParams()).append("\n");
                    }
                });
            }

            if (!result.expectationResults.isEmpty()) {
                 sb.append("  Expectations:\n");
                 for (var expResult : result.expectationResults) {
                     sb.append("    ").append(expResult.passed ? "PASS" : "FAIL").append(": ").append(expResult.expected.type()).append(" ").append(termValueToString(expResult.expected().value())); // Use helper for value
                     if (!expResult.passed()) { // Use getter
                         sb.append(" (Reason: ").append(expResult.failureReason()).append(")"); // Use getter
                         // If the expectation failed and it checked an action result, show the actual result
                         if (expResult.actionResult() != null && (expResult.expected().type().equals("expectedResult") || expResult.expected().type().equals("expectedBindings") || expResult.expected().type().equals("expectedToolResult") || expResult.expected().type().equals("expectedToolResultContains"))) {
                             sb.append(" (Actual: ").append(termValueToString(expResult.actionResult())).append(")");
                         } else if (expResult.actionResult() != null) {
                             // Optionally show actual result for other failed expectations if it exists, even if not directly compared
                             // sb.append(" (Action Result: ").append(termValueToString(expResult.actionResult())).append(")");
                         }
                     }
                     sb.append("\n");
                 }
            } else if (result.parsingErrors().isEmpty() && result.actionErrors.isEmpty()) {
                 sb.append("  No expectations defined.\n");
            }

            // Report final KB state for failed tests
            if (!overallPassed && !result.finalKbState().isEmpty()) {
                sb.append("  Final Test KB State:\n");
                result.finalKbState().forEach(assertion -> sb.append("    - ").append(assertion.kif().toKif()).append("\n"));
            }


            if (!overallPassed) {
                failedCount++;
            } else {
                passedCount++;
            }
            sb.append("\n");
        }

        sb.append("--- Summary ---\n");
        sb.append("Total: ").append(results.size()).append("\n");
        sb.append("Passed: ").append(passedCount).append("\n");
        sb.append("Failed: ").append(failedCount).append("\n");
        sb.append("---------------\n");

        return sb.toString();
    }

    private void updateTestResults(String resultsText) {
        cog().note(TEST_RESULTS_NOTE_ID).ifPresent(note -> {
            note.text = resultsText;
            // TODO: Need a generic way to save notes, not tied to UI or specific Cog implementation
            // cog().save(); // This calls UI.save() in CogNote, which might not be desired or available
            // For now, we just update the note object in memory. A real system needs a persistence layer.
        });
    }

    // Modified record to include parsing errors
    private record TestDefinition(String name, List<TestAction> setup, List<TestAction> action, List<TestExpected> expected,
                                  List<TestAction> teardown, List<String> parsingErrors) {
    }

    private record TestAction(String type, @Nullable Term payload, Map<String, Object> toolParams) {
    }

    private record TestExpected(String type, Object value) {
    }

    // Modified record for detailed expectation results to include actionResult
    private record ExpectationResult(TestExpected expected, boolean passed, @Nullable String failureReason, @Nullable Object actionResult) {
    }

    // Modified record for action errors to include the action itself
    private record ActionError(TestAction action, String message) {}


    // Modified record for overall test result to include finalKbState
    private record TestResult(String name, List<String> parsingErrors, List<ExpectationResult> expectationResults, List<ActionError> actionErrors, List<Assertion> finalKbState) { // Added finalKbState
        // Helper to determine overall pass/fail status
        public boolean isOverallPassed() {
            return parsingErrors.isEmpty() && expectationResults.stream().allMatch(ExpectationResult::passed) && actionErrors.isEmpty();
        }
    }

    public record RunTestsEvent() implements CogEvent {
    }

    // New event to signal test run completion with results
    public record TestRunCompleteEvent(String resultsText) implements CogEvent {
    }
}
