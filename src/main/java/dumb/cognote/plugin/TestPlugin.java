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

import static dumb.cognote.Cog.*;
import static dumb.cognote.Logic.*;

public class TestPlugin extends Plugin.BasePlugin {

    private static final String TEST_KB_PREFIX = "test-kb-";
    private static final long TEST_ACTION_TIMEOUT_SECONDS = 30; // Timeout for individual test actions
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
                        // Iterate through the terms *inside* the list of binding pairs
                        boolean parseError = false;
                        for (var bindingPairTerm : expectedBindingsListTerm.terms) {
                             if (bindingPairTerm instanceof Term.Lst bindingPair && bindingPair.size() == 2 && bindingPair.get(0) instanceof Term.Var var && bindingPair.get(1) instanceof Term value) {
                                expectedBindings.add(Map.of(var, value));
                            } else {
                                System.err.println("    Expectation '" + expected.type + "' failed: Invalid binding pair format in expected value: Each item in the list must be a list of size 2 like (?Var Value). Found: " + bindingPairTerm.toKif());
                                parseError = true;
                                break; // Stop parsing on first error
                            }
                        }
                        if (parseError) yield false; // Fail if parsing failed

                        List<Map<Term.Var, Term>> actualBindings = answer.bindings(); // 'answer' is now in scope

                        // Simple comparison: check if the lists of bindings are equal (order matters for now)
                        boolean passed = Objects.equals(expectedBindings, actualBindings);
                        if (!passed) {
                            System.err.println("    Expectation '" + expected.type + "' failed: Expected bindings " + expectedBindings + ", but got " + actualBindings);
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
                        // Fix: Use traditional instanceof check and cast for expected.value
                        if (actionResult instanceof String actualString && expected.value instanceof Term.Atom) {
                            String expectedString = ((Term.Atom) expected.value).value();
                            if (expectedString != null) {
                                passed = actualString.startsWith(expectedString);
                                 if (!passed) {
                                    System.err.println("    Expectation '" + expected.type + "' failed: Expected tool result starting with '" + expectedString + "', but got '" + actualString + "'");
                                }
                            } else {
                                // Expected value is a null atom value, unlikely but handle
                                passed = actualString == null || actualString.isEmpty(); // Or whatever null atom means for tool result
                                 if (!passed) {
                                    System.err.println("    Expectation '" + expected.type + "' failed: Expected tool result starting with null, but got '" + actualString + "'");
                                }
                            }
                        } else {
                            // Otherwise, use exact equality check (comparing Term to Object)
                            // This might need refinement depending on how tool results are represented
                            passed = Objects.equals(actionResult, expected.value);
                             if (!passed) {
                                System.err.println("    Expectation '" + expected.type + "' failed: Expected tool result " + expected.value + ", but got " + actionResult);
                            }
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
                TestAction action = null;
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
                            if (sectionContents.size() == 1) {
                                action = parseAction(sectionContents.getFirst()); // Parse the single action term inside (action ...)
                            } else {
                                throw new ParseException("Action section must contain exactly one action in test '" + name, sectionList.toKif());
                            }
                        }
                        case "expected" -> expected.addAll(parseExpectations(sectionContents)); // Parse terms *inside* (expected ...)
                        case "teardown" -> teardown.addAll(parseActions(sectionContents)); // Parse terms *inside* (teardown ...)
                        default -> throw new ParseException("Unknown section type '" + sectionOp + "' in test '" + name, sectionList.toKif());
                    }
                }

                if (action != null) { // Only add if parsing was successful
                    definitions.add(new TestDefinition(name, setup, action, expected, teardown));
                }

            } else {
                // Ignore non-(test ...) top-level terms
                System.out.println("TestRunnerPlugin: Ignoring non-test top-level term in definitions: " + term.toKif());
            }
        }

        return definitions; // Return definitions list
    }

    private List<TestAction> parseActions(List<Term> terms) {
        List<TestAction> actions = new ArrayList<>();
        for (var term : terms) {
            try {
                actions.add(parseAction(term));
            } catch (IllegalArgumentException e) {
                System.err.println("TestRunnerPlugin: Skipping invalid action term: " + term.toKif() + " | Error: " + e.getMessage());
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
            throw new IllegalArgumentException("Action list must have an operator.");
        }
        var op = opOpt.get();

        switch (op) {
            case "assert", "addRule", "removeRuleForm" -> {
                // These actions take a single KIF term (usually a list) as their payload
                if (actionList.size() != 2) // Expecting exactly one argument after the operator
                    throw new IllegalArgumentException(op + " action requires exactly one argument (the KIF form). Found size: " + actionList.size() + ". Term: " + actionList.toKif());
                Term payload = actionList.get(1); // The payload is the single term after the operator
                // Optional: Add a check that the payload is a Lst if required by the action
                if (!(payload instanceof Term.Lst)) {
                     throw new IllegalArgumentException(op + " action requires a KIF list as its argument. Found: " + payload.getClass().getSimpleName() + ". Term: " + actionList.toKif());
                }
                return new TestAction(op, payload, new HashMap<>()); // No toolParams for these
            }
            case "retract" -> {
                // Expecting (retract (TYPE TARGET))
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
                // If all checks pass, store the inner list as payload
                return new TestAction(op, retractTargetList, new HashMap<>()); // Retract doesn't use toolParams
            }
            case "runTool" -> {
                if (actionList.size() < 2)
                    throw new IllegalArgumentException("runTool action requires at least tool name.");
                // The tool name is the second term in the action list, not part of the payload
                Term toolNameTerm = actionList.get(1);
                if (!(toolNameTerm instanceof Term.Atom))
                    throw new IllegalArgumentException("runTool action requires tool name as the second argument (after the operator): " + actionList.toKif());
                String toolName = ((Term.Atom) toolNameTerm).value();

                Map<String, Object> toolParams = new HashMap<>();
                toolParams.put("name", toolName); // Put the extracted tool name here

                // Parameters are in an optional third term, which must be (params (...))
                if (actionList.size() > 2) {
                    if (actionList.size() == 3 && actionList.get(2) instanceof Term.Lst paramsList && paramsList.op().filter("params"::equals).isPresent()) {
                        toolParams.putAll(parseParams(paramsList));
                    } else {
                        throw new IllegalArgumentException("runTool action requires parameters in a (params (...)) list as the third argument.");
                    }
                }
                return new TestAction(op, null, toolParams); // Payload is null for runTool
            }
            case "query" -> {
                if (actionList.size() < 2)
                    throw new IllegalArgumentException("query action requires at least one argument (the pattern).");
                Term payload = actionList.get(1); // Payload is the pattern

                Map<String, Object> toolParams = new HashMap<>();
                // Parameters are in an optional third term, which must be (params (...))
                if (actionList.size() > 2) {
                    if (actionList.size() == 3 && actionList.get(2) instanceof Term.Lst paramsList && paramsList.op().filter("params"::equals).isPresent()) {
                        toolParams = parseParams(paramsList);
                    } else {
                         throw new IllegalArgumentException("query action parameters must be in a (params (...)) list as the third argument.");
                    }
                }
                return new TestAction(op, payload, toolParams);
            }
            default -> throw new IllegalArgumentException("Unknown action operator: " + op);
        }
    }

    private List<TestExpected> parseExpectations(List<Term> terms) {
        List<TestExpected> expectations = new ArrayList<>();
        for (var term : terms) {
            try {
                expectations.add(parseExpectation(term));
            } catch (IllegalArgumentException e) {
                System.err.println("TestRunnerPlugin: Skipping invalid expectation term: " + term.toKif() + " | Error: " + e.getMessage());
            }
        }
        return expectations;
    }

    private TestExpected parseExpectation(Term term) {
        if (!(term instanceof Term.Lst expectedList) || expectedList.terms.isEmpty()) {
            throw new IllegalArgumentException("Expectation must be a non-empty list.");
        }
        var opOpt = expectedList.op();
        if (opOpt.isEmpty()) {
            throw new IllegalArgumentException("Expectation list must have an operator.");
        }
        var op = opOpt.get();

        // The value of the expectation is the term immediately following the operator
        if (expectedList.size() < 2) {
             throw new IllegalArgumentException("Expectation '" + op + "' requires at least one argument.");
        }
        Term expectedValueTerm = expectedList.get(1);


        return switch (op) {
            case "expectedResult" -> {
                // expectedResult expects a boolean atom like "true" or "false"
                if (!(expectedValueTerm instanceof Term.Atom))
                    throw new IllegalArgumentException("expectedResult requires a single boolean atom (true/false).");
                String value = ((Term.Atom) expectedValueTerm).value();

                if (!value.equals("true") && !value.equals("false"))
                    throw new IllegalArgumentException("expectedResult value must be 'true' or 'false'.");
                // Store as Boolean
                yield new TestExpected(op, Boolean.parseBoolean(value));
            }
            case "expectedBindings" -> {
                // Expecting (expectedBindings ((?V1 Val1) (?V2 Val2) ...)) or (expectedBindings ())
                // The value is the list of binding pairs ((?V1 Val1) ...) or ()
                if (!(expectedValueTerm instanceof Term.Lst expectedBindingsListTerm)) {
                     throw new IllegalArgumentException("expectedBindings requires a list of binding pairs ((?V1 Val1) ...) or (). Found: " + expectedValueTerm.getClass().getSimpleName() + ". Term: " + expectedList.toKif());
                }
                // Store as Term.Lst
                yield new TestExpected(op, expectedBindingsListTerm);
            }
            case "expectedAssertionExists", "expectedAssertionDoesNotExist" -> {
                // Expecting a KIF list
                if (!(expectedValueTerm instanceof Term.Lst kif))
                    throw new IllegalArgumentException(op + " requires a single KIF list.");
                // Store as Term.Lst
                yield new TestExpected(op, kif);
            }
            case "expectedRuleExists", "expectedRuleDoesNotExist" -> {
                // Expecting a rule KIF list
                if (!(expectedValueTerm instanceof Term.Lst ruleForm))
                    throw new IllegalArgumentException(op + " requires a single rule KIF list.");
                // Store as Term.Lst
                yield new TestExpected(op, ruleForm);
            }
            case "expectedKbSize" -> {
                // Expecting an integer atom
                if (!(expectedValueTerm instanceof Term.Atom))
                    throw new IllegalArgumentException("expectedKbSize requires a single integer atom.");
                String value = ((Term.Atom) expectedValueTerm).value();
                try {
                    // Store as Integer
                    yield new TestExpected(op, Integer.parseInt(value));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("expectedKbSize value must be an integer.");
                }
            }
            case "expectedToolResult" -> {
                // The expected value can be any Term. Store it as is.
                yield new TestExpected(op, expectedValueTerm);
            }
            default -> throw new IllegalArgumentException("Unknown expectation operator: " + op);
        };
    }

    private Map<String, Object> parseParams(Term.Lst paramsList) {
        Map<String, Object> params = new HashMap<>();
        if (paramsList.op().filter("params"::equals).isEmpty()) {
            throw new IllegalArgumentException("Parameter list must start with 'params'.");
        }
        for (var paramTerm : paramsList.terms.stream().skip(1).toList()) {
            // Fix: Use traditional instanceof check
            if (paramTerm instanceof Term.Lst paramPair && paramPair.size() == 2 && paramPair.get(0) instanceof Term.Atom) {
                // Fix: Explicit cast to Term.Atom to get the value
                String value = ((Term.Atom) paramPair.get(0)).value();
                // Simple key-value pair: (key value)
                // Fix: Move this line inside the if block
                params.put(value, termToObject(paramPair.get(1)));
            } else {
                throw new IllegalArgumentException("Invalid parameter format: " + paramTerm.toKif());
            }
        }
        return params;
    }

    // Helper to convert Term to Java Object for tool parameters
    private Object termToObject(Term term) {
        return switch (term) {
            case Term.Atom atom -> {
                // Attempt to parse as number or boolean
                try {
                    yield Integer.parseInt(atom.value());
                } catch (NumberFormatException e1) {
                    try {
                        yield Double.parseDouble(atom.value());
                    } catch (NumberFormatException e2) {
                        if (atom.value().equalsIgnoreCase("true")) yield true;
                        if (atom.value().equalsIgnoreCase("false")) yield false;
                        yield atom.value(); // Default to string
                    }
                }
            }
            case Term.Lst list -> list; // Keep lists as Terms for now
            case Term.Var var -> var; // Keep vars as Terms for now
        };
    }

    /**
     * Formats a ParseException to include the line and column context from the source text.
     * Assumes the ParseException message ends with " at line X col Y".
     *
     * @param e The ParseException.
     * @param sourceText The text that was being parsed.
     * @return A formatted error message with context.
     */
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
                baseMessage = message.substring(0, matcher.start()); // Get message before " at line..."
            } catch (NumberFormatException ex) {
                // Should not happen if regex matches, but handle defensively
                System.err.println("TestRunnerPlugin: Could not parse line/col from ParseException message: " + message);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(baseMessage);

        if (lineNum > 0 && colNum > 0) {
            String[] lines = sourceText.split("\\r?\\n");
            if (lineNum <= lines.length) {
                String errorLine = lines[lineNum - 1]; // lineNum is 1-based
                sb.append("\n  --> at line ").append(lineNum).append(" col ").append(colNum).append(":\n");
                sb.append("  ").append(errorLine).append("\n");
                // Add pointer below the column
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
            sb.append(result.passed ? "PASS" : "FAIL").append(": ").append(result.name).append("\n");
            // Details are now generated during expectation checking and can be stored in TestResult if needed
            // For now, just indicate failure
            if (!result.passed) {
                sb.append("  Status: FAILED\n");
                // Optionally add more details from the TestResult object if it stored them
                // sb.append("  Details: ").append(result.details.replace("\n", "\n    ")).append("\n");
                failedCount++;
            } else {
                sb.append("  Status: PASSED\n");
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
            cog().save(); // Save the updated test results note
        });
    }


    private record TestDefinition(String name, List<TestAction> setup, TestAction action, List<TestExpected> expected,
                                  List<TestAction> teardown) {
    }

    private record TestAction(String type, @Nullable Term payload, Map<String, Object> toolParams) {
        // Payload is used for assert, addRule, retract, removeRuleForm, query (the KIF term or list)
        // ToolParams is used for runTool (name, params) and query (query_type)
    }

    private record TestExpected(String type, Object value) {
        // value type depends on type:
        // expectedResult: Boolean
        // expectedBindings: Term.Lst (the list of binding pairs ((?V1 Val1) ...))
        // expectedAssertionExists/DoesNotExist: Term.Lst (KIF)
        // expectedRuleExists/DoesNotExist: Term.Lst (Rule Form KIF)
        // expectedKbSize: Integer
        // expectedToolResult: Object (raw tool result)
    }

    private record TestResult(String name, boolean passed, String details) {
    }

    public record RunTestsEvent() implements CogEvent {
    }
}
