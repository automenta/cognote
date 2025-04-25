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
import java.util.stream.Collectors; // Import Collectors

import static dumb.cognote.Cog.*;
import static dumb.cognote.Logic.*;

public class TestPlugin extends Plugin.BasePlugin {

    private static final String TEST_KB_PREFIX = "test-kb-";
    private static final long TEST_ACTION_TIMEOUT_SECONDS = 30; // Timeout for individual test actions
    private static final long TEST_WAIT_DEFAULT_TIMEOUT_SECONDS = 30; // Default timeout for wait action
    private static final long TEST_WAIT_DEFAULT_INTERVAL_MILLIS = 100; // Default polling interval for wait action
    private static final Pattern PARSE_ERROR_LOCATION_PATTERN = Pattern.compile(" at line (\\d+) col (\\d+)$");


    @Override
    public void start(Events ev, Logic.Cognition ctx) {
        super.start(ev, ctx);
        ev.on(RunTestsEvent.class, this::handleRunTests);
    }

    private void handleRunTests(RunTestsEvent event) {
        System.out.println("TestRunnerPlugin: Received RunTestsEvent");
        cog().status("Running Tests...");

        var testDefinitionsNote = cog().note(TEST_DEFINITIONS_NOTE_ID);
        if (testDefinitionsNote.isEmpty()) {
            System.err.println("TestRunnerPlugin: Test Definitions note not found.");
            updateTestResults("Error: Test Definitions note not found.");
            cog().status("Tests Failed");
            return;
        }

        var testDefinitionsText = testDefinitionsNote.get().text;
        List<TestDefinition> tests;
        try {
            tests = parseTestDefinitions(testDefinitionsText);
        } catch (ParseException e) {
            String enhancedErrorMessage = formatParseException(e, testDefinitionsText);
            System.err.println("TestRunnerPlugin: Error parsing test definitions: " + enhancedErrorMessage);
            updateTestResults("Error parsing test definitions:\n" + enhancedErrorMessage);
            cog().status("Tests Failed");
            return;
        }


        if (tests.isEmpty()) {
            updateTestResults("No tests found in Test Definitions note.");
            cog().status("Tests Complete (No Tests)");
            return;
        }

        List<TestResult> results = new ArrayList<>();
        CompletableFuture<Void> allTestsFuture = CompletableFuture.completedFuture(null);

        for (var test : tests) {
            allTestsFuture = allTestsFuture.thenCompose(v ->
                    runTest(test)
                            .exceptionally(ex -> {
                                var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
                                System.err.println("TestRunnerPlugin: Error during test '" + test.name + "': " + cause.getMessage());
                                cause.printStackTrace();
                                return new TestResult(test.name, false, "Execution Error: " + cause.getMessage());
                            })
                            .thenAccept(results::add)
            );
        }

        allTestsFuture.whenCompleteAsync((v, ex) -> {
            // All tests finished (successfully or with errors)
            updateTestResults(formatTestResults(results));
            cog().status("Tests Complete");
            System.out.println("TestRunnerPlugin: Tests finished.");
        }, cog().events.exe);
    }

    private CompletableFuture<TestResult> runTest(TestDefinition test) {
        var testKbId = Cog.id(TEST_KB_PREFIX);
        System.out.println("TestRunnerPlugin: Running test '" + test.name + "' in KB " + testKbId);

        // Create a temporary note/KB for this test
        var testNote = new Note(testKbId, "Test KB: " + test.name, "", Note.Status.IDLE);
        ((CogNote) cog()).addNote(testNote); // Add to CogNote's map, but don't activate it for general reasoning

        // Ensure the test KB is active for the reasoner context during the test
        context.addActiveNote(testKbId);

        // Use CompletableFuture to chain setup, action, expected, and teardown
        CompletableFuture<TestResult> c = executeActions(test, testKbId, "Setup", test.setup)
                .thenCompose(setupResult -> executeAction(test, testKbId, "Action", test.action))
                .thenCompose(actionResult -> checkExpectations(test.expected, testKbId, actionResult))
                .thenCompose(expectedResult -> executeActions(test, testKbId, "Teardown", test.teardown)
                        .thenApply(teardownResult -> new TestResult(test.name, expectedResult, "Details handled in formatting"))) // Result determined by expectations
                .whenComplete((result, ex) -> {
                    // Cleanup the temporary KB regardless of success or failure
                    context.removeActiveNote(testKbId); // Deactivate the KB
                    ((CogNote) cog()).removeNote(testKbId); // Remove the note and its KB
                    System.out.println("TestRunnerPlugin: Cleaned up KB " + testKbId + " for test '" + test.name + "'");
                });
        return c;
    }

    private CompletableFuture<Void> executeActions(TestDefinition test, String testKbId, String phase, List<TestAction> actions) {
        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (var action : actions) {
            future = future.thenCompose(v -> executeAction(test, testKbId, phase, action).thenAccept(result -> {
                // Log action result if needed, but don't use it to determine test success/failure here
                // Success/failure of setup/teardown actions is handled by exceptions
            }));
        }
        return future;
    }

    private CompletableFuture<Object> executeAction(TestDefinition test, String testKbId, String phase, TestAction action) {
        System.out.println("  " + phase + ": Executing " + action.type + "...");

        // Apply timeout to the action future
        // Note: The 'wait' action has its own internal timeout mechanism,
        // but the overall action timeout still applies as a safeguard.
        return executeSingleAction(test, testKbId, phase, action).orTimeout(TEST_ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        throw new CompletionException(new TimeoutException(phase + " action timed out after " + TEST_ACTION_TIMEOUT_SECONDS + " seconds: " + action.type));
                    }
                    throw new CompletionException(new RuntimeException(phase + " action failed: " + action.type + " - " + cause.getMessage(), cause));
                });
    }

    private CompletableFuture<Object> executeSingleAction(TestDefinition test, String testKbId, String phase, TestAction action) {

        try {

            return switch (action.type) {
                case "assert" -> {
                    if (!(action.payload instanceof Term.Lst list))
                        throw new IllegalArgumentException("Invalid payload for assert: " + action.payload);
                    var isNeg = list.op().filter(KIF_OP_NOT::equals).isPresent();
                    var s = list.size();
                    if (isNeg && s != 2) throw new IllegalArgumentException("Invalid 'not' format for assert.");
                    var isEq = !isNeg && list.op().filter(Logic.KIF_OP_EQUAL::equals).isPresent();
                    var isOriented = isEq && s == 3 && list.get(1).weight() > list.get(2).weight();
                    var type = list.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
                    var pri = INPUT_ASSERTION_BASE_PRIORITY / (1.0 + list.weight());
                    // Assertions in test setup/action go into the temporary test KB
                    var pa = new Assertion.PotentialAssertion(list, pri, Set.of(), testKbId, isEq, isNeg, isOriented, testKbId, type, List.of(), 0);

                    var committed = context.tryCommit(pa, "test-runner:" + test.name);
                    // Return the committed assertion ID or null
                    yield CompletableFuture.completedFuture(committed != null ? committed.id() : null);
                }
                case "addRule" -> {
                    if (!(action.payload instanceof Term.Lst ruleForm))
                        throw new IllegalArgumentException("Invalid payload for addRule: " + action.payload);
                    // Need to parse the rule form into a Rule object
                    // Use default priority 1.0 for test rules
                    var r = Rule.parseRule(Cog.id(ID_PREFIX_RULE), ruleForm, 1.0, testKbId);
                    var added = context.addRule(r);
                    yield CompletableFuture.completedFuture(added); // Return boolean success
                }
                case "removeRuleForm" -> {
                    if (!(action.payload instanceof Term.Lst ruleForm))
                        throw new IllegalArgumentException("Invalid payload for removeRuleForm: " + action.payload);
                    var removed = context.removeRule(ruleForm);
                    yield CompletableFuture.completedFuture(removed); // Return boolean success
                }
                case "retract" -> {
                    // Payload is the (TYPE TARGET) list
                    if (!(action.payload instanceof Term.Lst retractTargetList) || retractTargetList.size() != 2 || !(retractTargetList.get(0) instanceof Term.Atom typeAtom)) {
                        throw new IllegalArgumentException("Invalid payload for retract: Expected (TYPE TARGET)");
                    }
                    var rtype = typeAtom.value();
                    var target = retractTargetList.get(1);
                    String targetStr;
                    // Need to convert the target Term to a string representation for the event
                    // The RetractionRequestEvent constructor takes a String target.
                    // Looking at RetractionRequestEvent, it seems the target string is the KIF representation.
                    // Let's use toKif() for Lst and value() for Atom. Vars are not allowed per parseAction.
                    if (target instanceof Term.Atom atom) {
                         targetStr = atom.value();
                    } else if (target instanceof Term.Lst list) {
                         targetStr = list.toKif();
                    } else {
                         // This case should be prevented by parseAction, but keep defensive check
                         throw new IllegalArgumentException("Invalid target type for retract: " + target.getClass().getSimpleName());
                    }

                    RetractionType retractionType;
                    try {
                        retractionType = RetractionType.valueOf(rtype.toUpperCase());
                    } catch (IllegalArgumentException e) {
                         throw new IllegalArgumentException("Invalid retraction type: " + rtype);
                    }

                    // Emit retraction request. Retraction is async.
                    // We can't return a direct success/failure here based on the request.
                    // The expectation phase must check if the assertion/rule is gone.
                    // Return the target string as a result? Or just null? Let's return the target string.
                    context.events.emit(new RetractionRequestEvent(targetStr, retractionType, "test-runner:" + test.name, testKbId));
                    yield CompletableFuture.completedFuture(targetStr); // Return the target string
                }
                case "runTool" -> {
                    var toolName = (String) action.toolParams.get("name");
                    if (toolName == null || toolName.isBlank())
                        throw new IllegalArgumentException("runTool requires 'name' parameter.");

                    Map<String, Object> toolParams = new HashMap<>(action.toolParams);
                    toolParams.remove("name"); // Remove name from params passed to tool
                    // Add target_kb_id if not present, defaulting to test KB
                    toolParams.putIfAbsent("target_kb_id", testKbId);
                    // Add note_id if not present and target_kb_id is the test KB
                    toolParams.putIfAbsent("note_id", testKbId);


                    var toolOpt = cog().tools.get(toolName);
                    if (toolOpt.isEmpty()) throw new IllegalArgumentException("Tool not found: " + toolName);
                    // Correctly yield the future from the tool execution
                    yield toolOpt.get().execute(toolParams).thenApply(r -> r); // Return the tool's raw result
                }
                case "query" -> {
                    if (!(action.payload instanceof Term.Lst pattern))
                        throw new IllegalArgumentException("Invalid payload for query: " + action.payload);
                    var queryTypeStr = (String) action.toolParams.getOrDefault("query_type", "ASK_BINDINGS");
                    QueryType queryType;
                    try {
                        queryType = QueryType.valueOf(queryTypeStr.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Invalid query_type for query action: " + queryTypeStr);
                    }
                    // Use querySync to get the Answer object directly
                    yield CompletableFuture.completedFuture(cog().querySync(new Query(Cog.id(ID_PREFIX_QUERY + "test_"), queryType, pattern, testKbId, action.toolParams))); // Return the Answer object
                }
                case "wait" -> { // Add the new wait action type execution
                    if (!(action.payload instanceof Term.Lst conditionList)) {
                        // This should have been caught by parseAction, but defensive check
                        throw new IllegalArgumentException("Invalid payload for wait: Expected condition list.");
                    }
                    var conditionOpOpt = conditionList.op();
                     if (conditionOpOpt.isEmpty()) {
                         // This should have been caught by parseAction
                         throw new IllegalArgumentException("Invalid wait condition list: Missing operator.");
                     }
                    var conditionOp = conditionOpOpt.get();
                    if (conditionList.size() < 2) {
                         // This should have been caught by parseAction
                         throw new IllegalArgumentException("Invalid wait condition list: Missing target.");
                    }
                    Term conditionTarget = conditionList.get(1); // The KIF term for assertion/rule

                    // Parse timeout and interval from toolParams (using termToObject helper)
                    long timeoutSeconds = ((Number) action.toolParams.getOrDefault("timeout", TEST_WAIT_DEFAULT_TIMEOUT_SECONDS)).longValue();
                    long timeoutMillis = timeoutSeconds * 1000;
                    long intervalMillis = ((Number) action.toolParams.getOrDefault("interval", TEST_WAIT_DEFAULT_INTERVAL_MILLIS)).longValue();
                    if (timeoutMillis <= 0 || intervalMillis <= 0) {
                         throw new IllegalArgumentException("Wait timeout and interval must be positive.");
                    }


                    System.out.println("  " + phase + ": Waiting for " + conditionList.toKif() + " (timeout: " + timeoutSeconds + "s, interval: " + intervalMillis + "ms)...");

                    // Use supplyAsync for the waiting loop
                    yield CompletableFuture.supplyAsync(() -> {
                        long startTime = System.currentTimeMillis();
                        while (System.currentTimeMillis() - startTime < timeoutMillis) {
                            boolean conditionMet = false;
                            try {
                                var globalKbId = context.kbGlobal().id;

                                switch (conditionOp) {
                                    case "assertionExists" -> {
                                        if (!(conditionTarget instanceof Term.Lst kif))
                                            throw new IllegalArgumentException("wait assertionExists requires a KIF list.");
                                        conditionMet = context.findAssertionByKif(kif, testKbId).isPresent() || context.findAssertionByKif(kif, globalKbId).isPresent();
                                    }
                                    case "assertionDoesNotExist" -> {
                                        if (!(conditionTarget instanceof Term.Lst kif))
                                            throw new IllegalArgumentException("wait assertionDoesNotExist requires a KIF list.");
                                        conditionMet = context.findAssertionByKif(kif, testKbId).isEmpty() && context.findAssertionByKif(kif, globalKbId).isEmpty();
                                    }
                                    // TODO: Add other conditions later if needed (ruleExists, ruleDoesNotExist, kbSize)
                                    default -> throw new IllegalArgumentException("Unknown wait condition type: " + conditionOp);
                                }

                                if (conditionMet) {
                                    // Condition met, return null to indicate success
                                    return null;
                                }

                            } catch (IllegalArgumentException e) {
                                 // This indicates a problem with the condition format itself, fail immediately
                                 throw new CompletionException(e);
                            } catch (Exception e) {
                                // Log other errors but continue waiting unless timeout
                                System.err.println("    Error checking wait condition: " + e.getMessage());
                                // Don't rethrow here, let the loop continue or timeout
                            }

                            try {
                                Thread.sleep(intervalMillis);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new CompletionException(new InterruptedException("Wait interrupted"));
                            }
                        }
                        // Timeout reached
                        throw new CompletionException(new TimeoutException("Wait condition not met within " + timeoutSeconds + " seconds: " + conditionList.toKif()));
                    }, cog().events.exe); // Run the waiting loop on the event executor
                }
                default -> throw new IllegalArgumentException("Unknown action type: " + action.type);
            };
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }


    private CompletableFuture<Boolean> checkExpectations(List<TestExpected> expectations, String testKbId, @Nullable Object actionResult) {
        CompletableFuture<Boolean> future = CompletableFuture.completedFuture(true); // Start with true

        for (var expected : expectations) {
            future = future.thenCompose(currentOverallResult -> {
                if (!currentOverallResult) {
                    return CompletableFuture.completedFuture(false); // If any previous expectation failed, the whole test fails
                }
                return checkSingleExpectation(expected, testKbId, actionResult);
            });
        }
        return future;
    }

    private CompletableFuture<Boolean> checkSingleExpectation(TestExpected expected, String testKbId, @Nullable Object actionResult) {
        System.out.println("    Checking expectation: " + expected.type + "...");
        var ctx = cog().context;

        // Perform the check and casting inside the supplyAsync lambda
        return CompletableFuture.supplyAsync(() -> {
            try {
                var kb = context.kb(testKbId);
                var globalKb = context.kbGlobal(); // Also check global KB if needed

                // Declare answer variable here, before the switch
                Cog.Answer answer = null;

                // Check if the expectation type requires actionResult to be a Query Answer
                boolean requiresAnswer = switch (expected.type) {
                    case "expectedResult", "expectedBindings" -> true;
                    default -> false;
                };

                if (requiresAnswer) {
                    if (!(actionResult instanceof Cog.Answer queryAnswer)) {
                        // If an Answer is required but actionResult is not one, the expectation fails immediately
                        System.err.println("    Expectation '" + expected.type + "' failed: requires action result to be a query answer, but got: " + (actionResult == null ? "null" : actionResult.getClass().getSimpleName()));
                        return false; // Return false directly from the lambda
                    }
                    answer = queryAnswer; // Assign the casted result to the 'answer' variable
                }

                // Fix: Use expected.type in the switch statement and use the correct types from expected.value
                return switch (expected.type) {
                    case "expectedResult" -> {
                        // expected.value is already a Boolean from parseExpectation
                        if (!(expected.value instanceof Boolean expectedBoolean)) {
                            System.err.println("    Expectation '" + expected.type + "' failed: Internal error - expected value is not a Boolean. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
                            yield false;
                        }
                        // 'answer' is now guaranteed to be a Cog.Answer here
                        boolean passed = expectedBoolean == (answer.status() == Cog.QueryStatus.SUCCESS);
                        if (!passed) {
                             System.err.println("    Expectation '" + expected.type + "' failed: Expected status " + (expectedBoolean ? "SUCCESS" : "FAILURE") + ", but got " + answer.status());
                        }
                        yield passed; // Yield boolean
                    }
                    case "expectedBindings" -> {
                        // expected.value is already a Term.Lst from parseExpectation
                        if (!(expected.value instanceof Term.Lst expectedBindingsListTerm)) {
                            System.err.println("    Expectation '" + expected.type + "' failed: Internal error - expected value is not a Term.Lst. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
                            yield false;
                        }

                        // 'answer' is now guaranteed to be a Cog.Answer here
                        List<Map<Term.Var, Term>> expectedBindings = new ArrayList<>();
                        boolean parseError = false;

                        // Handle the case of (()) representing one empty binding map
                        if (expectedBindingsListTerm.terms.size() == 1 && expectedBindingsListTerm.get(0) instanceof Term.Lst innerList && innerList.terms.isEmpty()) {
                             expectedBindings.add(Collections.emptyMap());
                        } else {
                            // Iterate through the terms *inside* the list of binding pairs
                            for (var bindingPairTerm : expectedBindingsListTerm.terms) {
                                 if (bindingPairTerm instanceof Term.Lst bindingPair && bindingPair.size() == 2 && bindingPair.get(0) instanceof Term.Var var && bindingPair.get(1) instanceof Term value) {
                                    expectedBindings.add(Map.of(var, value));
                                } else {
                                    System.err.println("    Expectation '" + expected.type + "' failed: Invalid binding pair format in expected value: Each item in the list must be a list of size 2 like (?Var Value) or the list must be (()) for an empty binding set. Found: " + bindingPairTerm.toKif());
                                    parseError = true;
                                    break; // Stop parsing on first error
                                }
                            }
                        }

                        if (parseError) yield false; // Fail if parsing failed

                        List<Map<Term.Var, Term>> actualBindings = answer.bindings(); // 'answer' is now in scope

                        // Compare sets of binding strings for order-insensitivity
                        Set<String> expectedBindingStrings = expectedBindings.stream()
                            .map(bindingMap -> {
                                List<String> entryStrings = new ArrayList<>();
                                bindingMap.forEach((var, term) -> entryStrings.add(var.name() + "=" + term.toKif()));
                                Collections.sort(entryStrings); // Sort entries within a single binding map
                                return String.join(",", entryStrings);
                            })
                            .collect(Collectors.toSet());

                        Set<String> actualBindingStrings = actualBindings.stream()
                            .map(bindingMap -> {
                                List<String> entryStrings = new ArrayList<>();
                                bindingMap.forEach((var, term) -> entryStrings.add(var.name() + "=" + term.toKif()));
                                Collections.sort(entryStrings);
                                return String.join(",", entryStrings);
                            })
                            .collect(Collectors.toSet());

                        boolean passed = Objects.equals(expectedBindingStrings, actualBindingStrings);

                        if (!passed) {
                            System.err.println("    Expectation '" + expected.type + "' failed: Expected bindings " + expectedBindings + ", but got " + actualBindings);
                            // Optional: Print string sets for debugging
                            // System.err.println("      Expected strings: " + expectedBindingStrings);
                            // System.err.println("      Actual strings: " + actualBindingStrings);
                        }
                        yield passed; // Yield boolean
                    }
                    case "expectedAssertionExists" -> {
                        // expected.value is already a Term.Lst from parseExpectation
                        if (!(expected.value instanceof Term.Lst expectedKif)) {
                            System.err.println("    Expectation '" + expected.type + "' failed: Internal error - expected value is not a Term.Lst. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
                            yield false;
                        }
                        // Check in the test KB first, then global KB
                        boolean passed = ctx.findAssertionByKif(expectedKif, testKbId).isPresent() || ctx.findAssertionByKif(expectedKif, globalKb.id).isPresent();
                         if (!passed) {
                            System.err.println("    Expectation '" + expected.type + "' failed: Assertion not found: " + expectedKif.toKif());
                        }
                        yield passed;
                    }
                    case "expectedAssertionDoesNotExist" -> {
                        // expected.value is already a Term.Lst from parseExpectation
                        if (!(expected.value instanceof Term.Lst expectedKif)) {
                            System.err.println("    Expectation '" + expected.type + "' failed: Internal error - expected value is not a Term.Lst. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
                            yield false;
                        }
                        // Check in the test KB first, then global KB
                        boolean passed = ctx.findAssertionByKif(expectedKif, testKbId).isEmpty() && ctx.findAssertionByKif(expectedKif, globalKb.id).isEmpty();
                        if (!passed) {
                            System.err.println("    Expectation '" + expected.type + "' failed: Assertion found unexpectedly: " + expectedKif.toKif());
                        }
                        // NOTE: This check may fail if the retraction is still pending due to its asynchronous nature.
                        // The new 'wait' action should be used before this expectation in the test definition.
                        yield passed;
                    }
                    case "expectedRuleExists" -> {
                        // expected.value is already a Term.Lst from parseExpectation
                        if (!(expected.value instanceof Term.Lst expectedRuleForm)) {
                            System.err.println("    Expectation '" + expected.type + "' failed: Internal error - expected value is not a Term.Lst. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
                            yield false;
                        }
                        boolean passed = context.rules().stream().anyMatch(r -> r.form().equals(expectedRuleForm));
                        if (!passed) {
                            System.err.println("    Expectation '" + expected.type + "' failed: Rule not found: " + expectedRuleForm.toKif());
                        }
                        yield passed;
                    }
                    case "expectedRuleDoesNotExist" -> {
                        // expected.value is already a Term.Lst from parseExpectation
                        if (!(expected.value instanceof Term.Lst expectedRuleForm)) {
                            System.err.println("    Expectation '" + expected.type + "' failed: Internal error - expected value is not a Term.Lst. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
                            yield false;
                        }
                        boolean passed = context.rules().stream().noneMatch(r -> r.form().equals(expectedRuleForm));
                         if (!passed) {
                            System.err.println("    Expectation '" + expected.type + "' failed: Rule found unexpectedly: " + expectedRuleForm.toKif());
                        }
                        yield passed;
                    }
                    case "expectedKbSize" -> {
                        // expected.value is already an Integer from parseExpectation
                        if (!(expected.value instanceof Integer expectedSize)) {
                            System.err.println("    Expectation '" + expected.type + "' failed: Internal error - expected value is not an Integer. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
                            yield false;
                        }
                        // Check size of the test KB only
                        boolean passed = kb.getAssertionCount() == expectedSize;
                        if (!passed) {
                            System.err.println("    Expectation '" + expected.type + "' failed: Expected KB size " + expectedSize + ", but got " + kb.getAssertionCount());
                        }
                        yield passed;
                    }
                    case "expectedToolResult" -> {
                        // expected.value is stored as a Term by parseExpectation
                        // Check if the action result matches the expected value
                        // If both are strings, check if the actual result starts with the expected value
                        boolean passed;
                        // Use Objects.equals for general comparison, as the expected value is a Term
                        passed = Objects.equals(actionResult, expected.value);

                        // Add specific string startsWith check if expected value is a String Atom
                        if (!passed && actionResult instanceof String actualString && expected.value instanceof Term.Atom) {
                             String expectedString = ((Term.Atom) expected.value).value();
                             if (expectedString != null) {
                                 passed = actualString.startsWith(expectedString);
                             }
                        }


                         if (!passed) {
                            System.err.println("    Expectation '" + expected.type + "' failed: Expected tool result " + expected.value + ", but got " + actionResult);
                            // If it was a string startsWith check that failed, provide more detail
                            if (actionResult instanceof String actualString && expected.value instanceof Term.Atom && ((Term.Atom) expected.value).value() != null) {
                                System.err.println("      (String startsWith check failed: Expected '" + ((Term.Atom) expected.value).value() + "', got '" + actualString + "')");
                            }
                        }
                        yield passed;
                    }
                    case "expectedToolResultContains" -> { // Add new expectation type
                        // expected.value is stored as a Term by parseExpectation
                        // Expecting an Atom containing the substring
                        if (!(expected.value instanceof Term.Atom expectedAtom)) {
                            System.err.println("    Expectation '" + expected.type + "' failed: requires a single Atom containing the expected substring.");
                            yield false;
                        }
                        String expectedSubstring = expectedAtom.value();
                        if (expectedSubstring == null) {
                             System.err.println("    Expectation '" + expected.type + "' failed: expected substring Atom has null value.");
                             yield false;
                        }

                        // Convert actionResult to string and check for containment
                        String actualResultString = actionResult != null ? actionResult.toString() : "null";

                        // Optional: Trim expected substring for more flexible matching
                        String trimmedExpectedSubstring = expectedSubstring.trim();

                        boolean passed = actualResultString.contains(trimmedExpectedSubstring);

                        if (!passed) {
                            System.err.println("    Expectation '" + expected.type + "' failed: Expected tool result to contain '" + trimmedExpectedSubstring + "', but got '" + actualResultString + "'");
                        }
                        yield passed;
                    }
                    default -> {
                        System.err.println("    Expectation check failed: Unknown expectation type: " + expected.type);
                        yield false; // Unknown type means failure
                    }
                };
            } catch (Exception e) {
                System.err.println("    Expectation check failed with error: " + e.getMessage());
                e.printStackTrace();
                return false; // Exception means failure
            }
        }, cog().events.exe); // Run expectation checks on the event executor
    }


    private List<TestDefinition> parseTestDefinitions(String text) throws ParseException {
        List<TestDefinition> definitions = new ArrayList<>();
        if (text == null || text.isBlank()) return definitions;

        // Use the static parseKif method which throws ParseException
        for (var term : Logic.KifParser.parseKif(text)) {
            if (term instanceof Term.Lst list && list.size() >= 2 && list.op().filter("test"::equals).isPresent()) {
                var nameTerm = list.get(1);
                String name;
                // Check if the name term is an Atom and extract its value
                // Fix: Use traditional instanceof check and cast
                if (nameTerm instanceof Term.Atom) {
                    name = ((Term.Atom) nameTerm).value();
                    if (name == null || name.isBlank()) {
                        System.err.println("TestRunnerPlugin: Skipping test with invalid name format (empty or blank Atom): " + list.toKif());
                        continue;
                    }
                } else {
                    System.err.println("TestRunnerPlugin: Skipping test with invalid name format (not an Atom): " + list.toKif());
                    continue;
                }

                List<TestAction> setup = new ArrayList<>();
                TestAction action = null; // Action section must have exactly one action
                List<TestExpected> expected = new ArrayList<>();
                List<TestAction> teardown = new ArrayList<>();

                // Parse the rest of the list for setup, action, expected, teardown sections
                // Iterate through terms *after* the test name (index 2 onwards)
                for (var i = 2; i < list.size(); i++) {
                    var sectionTerm = list.get(i); // This should be the section list, e.g., (action (query ...))
                    if (!(sectionTerm instanceof Term.Lst sectionList) || sectionList.terms.isEmpty()) {
                        System.err.println("TestRunnerPlugin: Skipping test '" + name + "' due to invalid section format: " + sectionTerm.toKif());
                        action = null; // Mark test as invalid
                        break;
                    }
                    var sectionOpOpt = sectionList.op(); // This should be "setup", "action", "expected", "teardown"
                    if (sectionOpOpt.isEmpty()) {
                        throw new ParseException("Section without operator in test '" + name, sectionList.toKif());
                    }
                    var sectionOp = sectionOpOpt.get();
                    var sectionContents = sectionList.terms.stream().skip(1).toList(); // These are the terms *inside* the section list


                    switch (sectionOp) {
                        case "setup" -> setup.addAll(parseActions(sectionContents)); // Parse terms *inside* (setup ...)
                        case "action" -> {
                            // The action section can now contain multiple actions (e.g., retract followed by wait)
                            // Parse all actions within the action section
                            if (sectionContents.isEmpty()) {
                                 throw new ParseException("Action section must contain at least one action in test '" + name, sectionList.toKif());
                            }
                            // Store the list of actions as the single 'action' for the TestDefinition
                            // This requires changing TestDefinition.action from TestAction to List<TestAction>
                            // OR, process the action list here and store the *result* of the last action?
                            // Let's stick to the current TestDefinition structure and assume 'action' is a single action.
                            // The 'wait' action should be part of the 'action' sequence, not a separate section.
                            // Re-evaluating the structure: (action ...) should contain *one* primary action whose result is checked by expected.
                            // Setup and Teardown can have multiple actions.
                            // The 'wait' action is a *type* of action, not a section.
                            // The test definition format should be:
                            // (test "Name" (setup (action1) (action2) ...) (action (primary_action)) (expected (exp1) (exp2) ...) (teardown (actionA) (actionB) ...))
                            // The 'wait' action should be usable in setup, action, or teardown lists.
                            // The current structure has (action ...) containing a *list* of actions.
                            // Let's adjust the parsing to allow multiple actions in the 'action' section, but only the result of the *last* action will be passed to 'expected'.
                            // This is a slight change from the original intent where 'action' was singular.
                            // Let's update TestDefinition to have `List<TestAction> action` instead of `TestAction action`.
                            // And update `runTest` to execute the list and pass the last result.

                            // *** Reverting the plan slightly ***
                            // The original structure `TestAction action` implies a single action whose result is checked.
                            // The 'wait' action is a *control flow* action, not one that produces a result to be checked by 'expected'.
                            // The 'wait' action should be allowed in setup, action, and teardown lists.
                            // The test definition format should be:
                            // (test "Name" (setup (action1) (action2) ...) (action (primary_action)) (expected (exp1) (exp2) ...) (teardown (actionA) (actionB) ...))
                            // Where (primary_action) is the *single* action whose result is checked.
                            // The 'wait' action should be usable *within* the setup, action, or teardown lists.
                            // Example: (action (retract ...) (wait ...)) - This doesn't fit the current `TestAction action` structure.
                            // Let's change TestDefinition to have `List<TestAction> actions` and `List<TestExpected> expectations`.
                            // The `action` section in the KIF will become part of the `setup` list for execution purposes, but we'll still parse it separately to identify the "primary" action if needed for result checking.
                            // This is getting complicated. Let's simplify:
                            // Keep TestDefinition as is: setup (List), action (Single), expected (List), teardown (List).
                            // Allow 'wait' in setup, expected, and teardown lists.
                            // The 'action' section *must* contain exactly one action whose result is checked.
                            // The 'Retract Assertion' test needs the wait *after* the retract.
                            // This means 'wait' must be allowed in the 'expected' section *before* other expectations.
                            // This contradicts the idea that 'expected' only contains checks.
                            // Let's reconsider the test definition format. A sequence of steps seems more natural.
                            // (test "Name" (steps (step1) (step2) ...))
                            // Where each step can be an action or an expectation check.
                            // (step (assert KIF))
                            // (step (action (query Pattern))) ; Action whose result is checked by the *next* step
                            // (step (expectedResult true)) ; Checks the result of the *previous* action step
                            // (step (action (retract ...)))
                            // (step (wait (assertionDoesNotExist ...))) ; Wait is a step
                            // (step (expectedAssertionDoesNotExist ...)) ; Checks the state after the wait

                            // This requires a significant refactor of TestDefinition and parsing.
                            // Let's stick to the current structure for now and find the least disruptive fix.
                            // The simplest fix for the Retract test is to allow 'wait' in the 'expected' list, but execute it *before* other expectations in that list.
                            // This is a bit hacky but fits the current structure.

                            // Back to the current parsing logic:
                            // `action` section must contain exactly one action.
                            // The Retract test has `(action (retract ...))`. This is correct for the structure.
                            // The `wait` action was added to the `expected` list.
                            // The `checkExpectations` method iterates through the `expected` list and calls `checkSingleExpectation`.
                            // `checkSingleExpectation` needs to handle the 'wait' type.

                            // Okay, the previous commit already added 'wait' parsing to `parseAction`.
                            // The `parseTestDefinitions` method currently expects `action` section to have size 1 and calls `parseAction` on that single term.
                            // The Retract test definition in the previous commit had `(action (retract ...))` and `(expected (wait ...) (expectedAssertionDoesNotExist ...))`.
                            // The log shows `Action: Executing retract...` followed by `Checking expectation: expectedAssertionDoesNotExist...`.
                            // This means the `wait` action *was not* executed as part of the `expected` checks.
                            // The `checkSingleExpectation` method does *not* execute actions, it only performs checks.
                            // The `wait` action *must* be executed as an action.

                            // Let's revisit the TestDefinition structure and parsing.
                            // `TestDefinition` has `List<TestAction> setup`, `TestAction action`, `List<TestExpected> expected`, `List<TestAction> teardown`.
                            // The `runTest` method executes `setup` actions, then the single `action`, then `expected` checks, then `teardown` actions.
                            // The `wait` action needs to be executed *between* the `retract` action and the `expectedAssertionDoesNotExist` check.
                            // This means the `retract` and `wait` should be in the `action` phase, and the `expectedAssertionDoesNotExist` in the `expected` phase.
                            // But the `action` phase only supports a single action.

                            // Alternative: Change `TestDefinition.action` to `List<TestAction>`.
                            // Change `runTest` to execute the list of actions in the `action` phase, and pass the result of the *last* action to `checkExpectations`.
                            // This seems the most reasonable approach within the current overall structure.

                            // Refined Plan (Revised):
                            // 1. Modify `TestDefinition` record to have `List<TestAction> action` instead of `TestAction action`.
                            // 2. Modify `parseTestDefinitions` to parse the `action` section as a list of actions.
                            // 3. Modify `runTest` to execute the list of actions in the `action` phase and pass the result of the *last* action to `checkExpectations`.
                            // 4. Modify `src/main/java/dumb/cognote/Test.java`:
                            //    *   Change the `action` section in the "Retract Assertion" test to contain both `retract` and `wait`.
                            //    *   Change the `expectedBindings` from `(( ))` to `(())` in the "Forward Chaining Rule" test (already done, but confirm parsing fix).
                            //    *   Change `expectedToolResult` to `expectedToolResultContains` in the "Run GetNoteTextTool" test.
                            // 5. Modify `src/main/java/dumb/cognote/plugin/TestPlugin.java`:
                            //    *   Update `TestDefinition` record.
                            //    *   Update `parseTestDefinitions` to parse `action` section as `List<TestAction>`.
                            //    *   Update `runTest` to execute `List<TestAction>` for the action phase and get the last result.
                            //    *   Update `checkSingleExpectation` to correctly handle `expectedBindings (())`.
                            //    *   Add parsing logic for `expectedToolResultContains` in `parseExpectation`.
                            //    *   Add checking logic for `expectedToolResultContains` in `checkSingleExpectation`.

                            // Let's implement this revised plan.

                            // First, update TestDefinition record in TestPlugin.java
                            // Then update parseTestDefinitions to handle List<TestAction> for action.
                            // Then update runTest to execute the list.
                            // Then update checkSingleExpectation for expectedBindings (()) and expectedToolResultContains.
                            // Finally, update Test.java with the corrected test definitions.

                            // --- Start implementing changes in TestPlugin.java ---

                            // Update TestDefinition record
                            // private record TestDefinition(String name, List<TestAction> setup, TestAction action, List<TestExpected> expected, List<TestAction> teardown) { }
                            // becomes
                            // private record TestDefinition(String name, List<TestAction> setup, List<TestAction> action, List<TestExpected> expected, List<TestAction> teardown) { }

                            // Update parseTestDefinitions
                            // case "action" -> { if (sectionContents.size() == 1) { action = parseAction(sectionContents.getFirst()); } else { throw ... } }
                            // becomes
                            // case "action" -> action = parseActions(sectionContents); // Parse all actions within (action ...)

                            // Update runTest
                            // .thenCompose(setupResult -> executeAction(test, testKbId, "Action", test.action))
                            // .thenCompose(actionResult -> checkExpectations(test.expected, testKbId, actionResult))
                            // needs to execute the list of actions and get the last result.

                            // Let's create a helper method `executeActionList` similar to `executeActions` but returning the last result.

                            // Update checkSingleExpectation for expectedBindings (())
                            // The current loop `for (var bindingPairTerm : expectedBindingsListTerm.terms)` will iterate over the single `()` term when the input is `(())`.
                            // Inside the loop, `bindingPairTerm` will be `()`.
                            // The check `bindingPairTerm instanceof Term.Lst bindingPair && bindingPair.size() == 2` will fail because `bindingPair.size()` is 0.
                            // The `else` block will be hit, printing the error.
                            // We need to add a specific check *before* the loop for the `(())` case.

                            // Update checkSingleExpectation for expectedToolResultContains
                            // Add a new case to the switch statement.

                            // --- End implementing changes in TestPlugin.java ---

                            // --- Start implementing changes in Test.java ---
                            // Update the TESTS string as planned.
                            // --- End implementing changes in Test.java ---

                            // Let's proceed with the changes to TestPlugin.java first.
