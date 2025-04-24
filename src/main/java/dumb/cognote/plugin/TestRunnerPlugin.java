package dumb.cognote.plugin;

import dumb.cognote.*;
import dumb.cognote.Logic.KifParser.ParseException;
import dumb.cognote.Term;
import org.jetbrains.annotations.Nullable;

import java.io.StringReader;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dumb.cognote.Cog.TEST_DEFINITIONS_NOTE_ID;
import static dumb.cognote.Cog.TEST_RESULTS_NOTE_ID;
import static dumb.cognote.Logic.*;
import static java.util.Optional.ofNullable;

public class TestRunnerPlugin extends Plugin.BasePlugin {

    private static final String TEST_KB_PREFIX = "test-kb-";
    private static final long TEST_ACTION_TIMEOUT_SECONDS = 30; // Timeout for individual test actions

    @Override
    public void start(Events ev, Logic.Cognition ctx) {
        super.start(ev, ctx);
        ev.on(Cog.RunTestsEvent.class, this::handleRunTests);
    }

    private void handleRunTests(Cog.RunTestsEvent event) {
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
            System.err.println("TestRunnerPlugin: Error parsing test definitions: " + e.getMessage());
            updateTestResults("Error parsing test definitions: " + e.getMessage());
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
        cog().addNote(testNote); // Add to CogNote's map, but don't activate it for general reasoning

        // Ensure the test KB is active for the reasoner context during the test
        context.addActiveNote(testKbId);

        // Use CompletableFuture to chain setup, action, expected, and teardown
        return executeActions(test.setup, testKbId, "Setup")
                .thenCompose(setupResult -> executeAction(test.action, testKbId, "Action"))
                .thenCompose(actionResult -> checkExpectations(test.expected, testKbId, actionResult))
                .thenCompose(expectedResult -> executeActions(test.teardown, testKbId, "Teardown")
                        .thenApply(teardownResult -> new TestResult(test.name, expectedResult, "Details handled in formatting"))) // Result determined by expectations
                .whenComplete((result, ex) -> {
                    // Cleanup the temporary KB regardless of success or failure
                    context.removeActiveNote(testKbId); // Deactivate the KB
                    cog().removeNote(testKbId); // Remove the note and its KB
                    System.out.println("TestRunnerPlugin: Cleaned up KB " + testKbId + " for test '" + test.name + "'");
                });
    }

    private CompletableFuture<Void> executeActions(List<TestAction> actions, String testKbId, String phase) {
        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (var action : actions) {
            future = future.thenCompose(v -> executeAction(action, testKbId, phase).thenAccept(result -> {
                // Log action result if needed, but don't use it to determine test success/failure here
                // Success/failure of setup/teardown actions is handled by exceptions
            }));
        }
        return future;
    }

    private CompletableFuture<Object> executeAction(TestAction action, String testKbId, String phase) {
        System.out.println("  " + phase + ": Executing " + action.type + "...");
        CompletableFuture<Object> actionFuture;
        try {
            actionFuture = switch (action.type) {
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
                    // Use tryCommit which handles duplicates, trivial, etc.
                    // We need to wait for the assertion to be processed by the TMS
                    // This is tricky with the event-driven nature. For tests, we can emit the event
                    // and then potentially wait for the AssertedEvent for this specific assertion ID.
                    // A simpler approach for now is to just emit the event and assume it will be processed.
                    // The checkExpectations phase will verify the state.
                    // However, for the action phase, we might need the *result* of the action (e.g., query bindings).
                    // Let's refine: setup/teardown just emit events. The main 'action' needs its result captured.

                    if (phase.equals("Action") && action.type.equals("query")) {
                         // Query action is handled below to capture result
                         throw new IllegalStateException("Query action should be handled directly in the 'query' case.");
                    }

                    // For assert/addRule/retract/removeRuleForm in setup/teardown/action phases (except query action)
                    // We just emit the event and return a completed future.
                    // The success/failure is implicit (no exception thrown here).
                    // The actual state change is verified in checkExpectations.
                    switch (action.type) {
                         case "assert" -> {
                             var committed = context.tryCommit(pa, "test-runner:" + test.name);
                             // Return the committed assertion ID or null
                             actionFuture = CompletableFuture.completedFuture(committed != null ? committed.id() : null);
                         }
                         case "addRule" -> {
                             if (!(action.payload instanceof Term.Lst ruleForm))
                                 throw new IllegalArgumentException("Invalid payload for addRule: " + action.payload);
                             var r = Rule.parseRule(Cog.id(ID_PREFIX_RULE), ruleForm, 1.0, testKbId);
                             var added = context.addRule(r);
                             actionFuture = CompletableFuture.completedFuture(added); // Return boolean success
                         }
                         case "retract" -> {
                             if (!(action.payload instanceof Term.Lst retractTarget))
                                 throw new IllegalArgumentException("Invalid payload for retract: " + action.payload);
                             // Assume payload is (BY_ID "id") or (BY_KIF (kif form))
                             if (retractTarget.size() == 2 && retractTarget.get(0) instanceof Term.Atom typeAtom) {
                                 var type = Logic.RetractionType.valueOf(typeAtom.value().toUpperCase());
                                 var target = retractTarget.get(1);
                                 String targetStr;
                                 if (target instanceof Term.Atom atom) targetStr = atom.value();
                                 else if (target instanceof Term.Lst list) targetStr = list.toKif();
                                 else throw new IllegalArgumentException("Invalid target for retract: " + target);

                                 // Emit retraction request. Retraction is async.
                                 // We can't return a direct success/failure here based on the request.
                                 // The expectation phase must check if the assertion/rule is gone.
                                 // Return the target string as a result? Or just null? Let's return the target.
                                 context.events.emit(new Cog.RetractionRequestEvent(targetStr, type, "test-runner:" + test.name, testKbId));
                                 actionFuture = CompletableFuture.completedFuture(targetStr);
                             } else {
                                 throw new IllegalArgumentException("Invalid retract payload format: " + retractTarget.toKif());
                             }
                         }
                         case "removeRuleForm" -> {
                             if (!(action.payload instanceof Term.Lst ruleForm))
                                 throw new IllegalArgumentException("Invalid payload for removeRuleForm: " + action.payload);
                             var removed = context.removeRule(ruleForm);
                             actionFuture = CompletableFuture.completedFuture(removed); // Return boolean success
                         }
                         default -> throw new IllegalArgumentException("Unknown action type: " + action.type);
                    }
                }
                case "runTool" -> {
                    var toolName = (String) action.toolParams.get("name");
                    if (toolName == null || toolName.isBlank())
                        throw new IllegalArgumentException("runTool requires 'name' parameter.");
                    var toolParams = new HashMap<>(action.toolParams);
                    toolParams.remove("name"); // Remove name from params passed to tool
                    // Add target_kb_id if not present, defaulting to test KB
                    toolParams.putIfAbsent("target_kb_id", testKbId);
                    // Add note_id if not present and target_kb_id is the test KB
                    toolParams.putIfAbsent("note_id", testKbId);


                    var toolOpt = cog().tools.get(toolName);
                    if (toolOpt.isEmpty()) throw new IllegalArgumentException("Tool not found: " + toolName);
                    var tool = toolOpt.get();
                    actionFuture = tool.execute(toolParams).thenApply(r -> r); // Return the tool's raw result
                }
                case "query" -> {
                    if (!(action.payload instanceof Term.Lst pattern))
                        throw new IllegalArgumentException("Invalid payload for query: " + action.payload);
                    var queryTypeStr = (String) action.toolParams.getOrDefault("query_type", "ASK_BINDINGS");
                    Cog.QueryType queryType;
                    try {
                        queryType = Cog.QueryType.valueOf(queryTypeStr.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Invalid query_type for query action: " + queryTypeStr);
                    }
                    var queryId = Cog.id(ID_PREFIX_QUERY + "test_");
                    var query = new Cog.Query(queryId, queryType, pattern, testKbId, action.toolParams);
                    // Use querySync to get the Answer object directly
                    actionFuture = CompletableFuture.completedFuture(cog().querySync(query)); // Return the Answer object
                }
                default -> throw new IllegalArgumentException("Unknown action type: " + action.type);
            };
        } catch (Exception e) {
            // Wrap synchronous errors in a failed future
            actionFuture = CompletableFuture.failedFuture(e);
        }

        // Apply timeout to the action future
        return actionFuture.orTimeout(TEST_ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        throw new CompletionException(new TimeoutException(phase + " action timed out after " + TEST_ACTION_TIMEOUT_SECONDS + " seconds: " + action.type));
                    }
                    throw new CompletionException(new RuntimeException(phase + " action failed: " + action.type + " - " + cause.getMessage(), cause));
                });
    }


    private CompletableFuture<Boolean> checkExpectations(List<TestExpected> expectations, String testKbId, @Nullable Object actionResult) {
        CompletableFuture<Boolean> future = CompletableFuture.completedFuture(true); // Start with true

        for (var expected : expectations) {
            future = future.thenCompose(currentOverallResult -> {
                if (!currentOverallResult) {
                    return CompletableFuture.completedFuture(false); // If any previous expectation failed, the whole test fails
                }
                return checkExpectation(expected, testKbId, actionResult);
            });
        }
        return future;
    }

    private CompletableFuture<Boolean> checkExpectation(TestExpected expected, String testKbId, @Nullable Object actionResult) {
        System.out.println("    Checking expectation: " + expected.type + "...");
        return CompletableFuture.supplyAsync(() -> {
            try {
                var kb = context.kb(testKbId);
                var globalKb = context.kbGlobal(); // Also check global KB if needed

                return switch (expected.type) {
                    case "expectedResult" -> {
                        if (!(expected.value instanceof Boolean expectedBoolean))
                            throw new IllegalArgumentException("expectedResult requires a boolean value.");
                        if (!(actionResult instanceof Cog.Answer answer))
                            throw new IllegalArgumentException("expectedResult requires the action to be a query.");
                        yield expectedBoolean == (answer.status() == Cog.QueryStatus.SUCCESS);
                    }
                    case "expectedBindings" -> {
                        if (!(expected.value instanceof List<?> expectedBindingsList))
                            throw new IllegalArgumentException("expectedBindings requires a list of bindings.");
                        if (!(actionResult instanceof Cog.Answer answer))
                            throw new IllegalArgumentException("expectedBindings requires the action to be a query.");

                        @SuppressWarnings("unchecked")
                        List<Map<Term.Var, Term>> expectedBindings = (List<Map<Term.Var, Term>>) expectedBindingsList;
                        List<Map<Term.Var, Term>> actualBindings = answer.bindings();

                        // Simple comparison: check if the lists of bindings are equal (order matters for now)
                        yield Objects.equals(expectedBindings, actualBindings);
                    }
                    case "expectedAssertionExists" -> {
                        if (!(expected.value instanceof Term.Lst expectedKif))
                            throw new IllegalArgumentException("expectedAssertionExists requires a KIF list.");
                        // Check in the test KB first, then global KB
                        yield kb.findAssertionByKif(expectedKif).isPresent() || globalKb.findAssertionByKif(expectedKif).isPresent();
                    }
                    case "expectedAssertionDoesNotExist" -> {
                        if (!(expected.value instanceof Term.Lst expectedKif))
                            throw new IllegalArgumentException("expectedAssertionDoesNotExist requires a KIF list.");
                        // Check in the test KB first, then global KB
                        yield kb.findAssertionByKif(expectedKif).isEmpty() && globalKb.findAssertionByKif(expectedKif).isEmpty();
                    }
                    case "expectedRuleExists" -> {
                        if (!(expected.value instanceof Term.Lst expectedRuleForm))
                            throw new IllegalArgumentException("expectedRuleExists requires a rule KIF list.");
                        yield context.rules().stream().anyMatch(r -> r.form().equals(expectedRuleForm));
                    }
                    case "expectedRuleDoesNotExist" -> {
                        if (!(expected.value instanceof Term.Lst expectedRuleForm))
                            throw new IllegalArgumentException("expectedRuleDoesNotExist requires a rule KIF list.");
                        yield context.rules().stream().noneMatch(r -> r.form().equals(expectedRuleForm));
                    }
                    case "expectedKbSize" -> {
                        if (!(expected.value instanceof Integer expectedSize))
                            throw new IllegalArgumentException("expectedKbSize requires an integer value.");
                        // Check size of the test KB only
                        yield kb.assertionCount() == expectedSize;
                    }
                    case "expectedToolResult" -> {
                        // Check if the action result matches the expected value
                        yield Objects.equals(actionResult, expected.value);
                    }
                    default -> throw new IllegalArgumentException("Unknown expectation type: " + expected.type);
                };
            } catch (Exception e) {
                System.err.println("    Expectation check failed with error: " + e.getMessage());
                e.printStackTrace();
                return false; // Expectation check itself failed
            }
        }, cog().events.exe); // Run expectation checks on the event executor
    }


    private List<TestDefinition> parseTestDefinitions(String text) throws ParseException {
        List<TestDefinition> definitions = new ArrayList<>();
        if (text == null || text.isBlank()) return definitions;

        try (var reader = new StringReader(text)) {
            for (var term : Logic.KifParser.parseKif(text)) {
                if (term instanceof Term.Lst list && list.size() >= 2 && list.op().filter("test"::equals).isPresent()) {
                    var nameTerm = list.get(1);
                    String name;
                    // Check if the name term is an Atom and extract its value
                    if (nameTerm instanceof Term.Atom atom) {
                        name = atom.value();
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
                    for (var i = 2; i < list.size(); i++) {
                        var sectionTerm = list.get(i);
                        if (!(sectionTerm instanceof Term.Lst sectionList) || sectionList.isEmpty()) {
                            System.err.println("TestRunnerPlugin: Skipping test '" + name + "' due to invalid section format: " + sectionTerm.toKif());
                            action = null; // Mark test as invalid
                            break;
                        }
                        var sectionOpOpt = sectionList.op();
                        if (sectionOpOpt.isEmpty()) {
                            System.err.println("TestRunnerPlugin: Skipping test '" + name + "' due to section without operator: " + sectionList.toKif());
                            action = null; // Mark test as invalid
                            break;
                        }
                        var sectionOp = sectionOpOpt.get();

                        switch (sectionOp) {
                            case "setup" -> {
                                if (sectionList.size() > 1) {
                                    setup.addAll(parseActions(sectionList.terms.stream().skip(1).toList()));
                                }
                            }
                            case "action" -> {
                                if (sectionList.size() == 2) {
                                    action = parseAction(sectionList.get(1));
                                } else {
                                    System.err.println("TestRunnerPlugin: Skipping test '" + name + "' due to invalid action section size: " + sectionList.toKif());
                                    action = null; // Mark test as invalid
                                }
                            }
                            case "expected" -> {
                                if (sectionList.size() > 1) {
                                    expected.addAll(parseExpectations(sectionList.terms.stream().skip(1).toList()));
                                }
                            }
                            case "teardown" -> {
                                if (sectionList.size() > 1) {
                                    teardown.addAll(parseActions(sectionList.terms.stream().skip(1).toList()));
                                }
                            }
                            default -> {
                                System.err.println("TestRunnerPlugin: Skipping test '" + name + "' due to unknown section type: " + sectionList.toKif());
                                action = null; // Mark test as invalid
                                break;
                            }
                        }
                    }

                    if (action != null) { // Only add if parsing was successful
                        definitions.add(new TestDefinition(name, setup, action, expected, teardown));
                    }

                } else {
                    // Ignore non-(test ...) top-level terms
                    // This check is redundant with the outer if, but kept for clarity if needed later
                    // if (!(term instanceof Term.Lst list && list.op().filter("test"::equals).isPresent())) {
                         System.out.println("TestRunnerPlugin: Ignoring non-test top-level term in definitions: " + term.toKif());
                    // }
                }
            }

        } catch (ParseException e) {
            throw e; // Re-throw ParseException
        } catch (Exception e) {
            System.err.println("TestRunnerPlugin: Unexpected error parsing test definitions: " + e.getMessage());
            e.printStackTrace();
            throw new ParseException("Unexpected error parsing test definitions: " + e.getMessage());
        }
        return definitions;
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

    private TestAction parseAction(Term term) {
        if (!(term instanceof Term.Lst actionList) || actionList.isEmpty()) {
            throw new IllegalArgumentException("Action must be a non-empty list.");
        }
        var opOpt = actionList.op();
        if (opOpt.isEmpty()) {
            throw new IllegalArgumentException("Action list must have an operator.");
        }
        var op = opOpt.get();

        return switch (op) {
            case "assert", "addRule", "retract", "removeRuleForm", "query" -> {
                if (actionList.size() < 2) throw new IllegalArgumentException(op + " action requires at least one argument.");
                // For query, the payload is the pattern, toolParams might contain query_type
                // For runTool, the payload is the tool name and params
                // Let's make payload the main argument(s) and toolParams for extra config like query_type
                Term payload = null;
                Map<String, Object> toolParams = new HashMap<>();

                if (op.equals("query")) {
                    if (actionList.size() != 2) throw new IllegalArgumentException("query action requires exactly one argument (the pattern).");
                    payload = actionList.get(1);
                    // Check for optional query_type parameter
                    if (actionList.size() > 2 && actionList.get(2) instanceof Term.Lst paramsList && paramsList.op().filter("params"::equals).isPresent()) {
                         toolParams = parseParams(paramsList);
                    }
                } else if (op.equals("retract")) {
                     if (actionList.size() != 3 || !(actionList.get(1) instanceof Term.Atom typeAtom)) throw new IllegalArgumentException("retract action requires type and target.");
                     payload = new Term.Lst(typeAtom, actionList.get(2)); // Store type and target as a list payload
                }
                else { // assert, addRule, removeRuleForm
                    payload = new Term.Lst(actionList.terms.stream().skip(1).toList()); // Payload is the rest of the list
                }

                yield new TestAction(op, payload, toolParams);
            }
            case "runTool" -> {
                if (actionList.size() < 2) throw new IllegalArgumentException("runTool action requires at least tool name.");
                if (!(actionList.get(1) instanceof Term.Atom toolNameAtom)) throw new IllegalArgumentException("runTool action requires tool name as the first argument.");
                Map<String, Object> toolParams = new HashMap<>();
                toolParams.put("name", toolNameAtom.value());
                if (actionList.size() > 2) {
                    // Assume remaining arguments are key-value pairs or a single params list
                    if (actionList.size() == 3 && actionList.get(2) instanceof Term.Lst paramsList && paramsList.op().filter("params"::equals).isPresent()) {
                         toolParams.putAll(parseParams(paramsList));
                    } else {
                        // Simple key-value pairs? Let's stick to the (params (...)) format for clarity
                        throw new IllegalArgumentException("runTool action requires parameters in a (params (...)) list.");
                    }
                }
                yield new TestAction(op, null, toolParams);
            }
            default -> throw new IllegalArgumentException("Unknown action operator: " + op);
        };
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
        if (!(term instanceof Term.Lst expectedList) || expectedList.isEmpty()) {
            throw new IllegalArgumentException("Expectation must be a non-empty list.");
        }
        var opOpt = expectedList.op();
        if (opOpt.isEmpty()) {
            throw new IllegalArgumentException("Expectation list must have an operator.");
        }
        var op = opOpt.get();

        return switch (op) {
            case "expectedResult" -> {
                if (expectedList.size() != 2 || !(expectedList.get(1) instanceof Term.Atom valueAtom))
                    throw new IllegalArgumentException("expectedResult requires a single boolean atom (true/false).");
                var value = valueAtom.value();
                if (!value.equals("true") && !value.equals("false"))
                    throw new IllegalArgumentException("expectedResult value must be 'true' or 'false'.");
                yield new TestExpected(op, Boolean.parseBoolean(value));
            }
            case "expectedBindings" -> {
                if (expectedList.size() < 1) throw new IllegalArgumentException("expectedBindings requires a list of bindings.");
                // Parse expected bindings: ((?V1 Val1) (?V2 Val2) ...)
                List<Map<Term.Var, Term>> expectedBindings = new ArrayList<>();
                for (var bindingListTerm : expectedList.terms.stream().skip(1).toList()) {
                    if (bindingListTerm instanceof Term.Lst bindingList && bindingList.size() == 2 && bindingList.get(0) instanceof Term.Var var && bindingList.get(1) instanceof Term value) {
                        expectedBindings.add(Map.of(var, value));
                    } else {
                        throw new IllegalArgumentException("Invalid expectedBindings format: " + bindingListTerm.toKif());
                    }
                }
                yield new TestExpected(op, expectedBindings);
            }
            case "expectedAssertionExists", "expectedAssertionDoesNotExist" -> {
                if (expectedList.size() != 2 || !(expectedList.get(1) instanceof Term.Lst kif))
                    throw new IllegalArgumentException(op + " requires a single KIF list.");
                yield new TestExpected(op, kif);
            }
            case "expectedRuleExists", "expectedRuleDoesNotExist" -> {
                if (expectedList.size() != 2 || !(expectedList.get(1) instanceof Term.Lst ruleForm))
                    throw new IllegalArgumentException(op + " requires a single rule KIF list.");
                yield new TestExpected(op, ruleForm);
            }
            case "expectedKbSize" -> {
                if (expectedList.size() != 2 || !(expectedList.get(1) instanceof Term.Atom sizeAtom))
                    throw new IllegalArgumentException("expectedKbSize requires a single integer atom.");
                try {
                    yield new TestExpected(op, Integer.parseInt(sizeAtom.value()));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("expectedKbSize value must be an integer.");
                }
            }
             case "expectedToolResult" -> {
                if (expectedList.size() != 2)
                    throw new IllegalArgumentException("expectedToolResult requires a single value argument.");
                // The expected value can be any Term
                yield new TestExpected(op, expectedList.get(1));
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
            if (paramTerm instanceof Term.Lst paramPair && paramPair.size() == 2 && paramPair.get(0) instanceof Term.Atom keyAtom) {
                // Simple key-value pair: (key value)
                params.put(keyAtom.value(), termToObject(paramPair.get(1)));
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


    private record TestDefinition(String name, List<TestAction> setup, TestAction action, List<TestExpected> expected, List<TestAction> teardown) {
    }

    private record TestAction(String type, @Nullable Term payload, Map<String, Object> toolParams) {
        // Payload is used for assert, addRule, retract, removeRuleForm, query (the KIF term)
        // ToolParams is used for runTool (name, params) and query (query_type)
    }

    private record TestExpected(String type, Object value) {
        // value type depends on type:
        // expectedResult: Boolean
        // expectedBindings: List<Map<Term.Var, Term>>
        // expectedAssertionExists/DoesNotExist: Term.Lst (KIF)
        // expectedRuleExists/DoesNotExist: Term.Lst (Rule Form KIF)
        // expectedKbSize: Integer
        // expectedToolResult: Object (raw tool result)
    }

    private record TestResult(String name, boolean passed, String details) {
    }
}
