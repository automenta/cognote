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

        var testDefinitionsNote = cog().note(TEST_DEFINITIONS_NOTE_ID);
        if (testDefinitionsNote.isEmpty()) {
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
            System.err.println("Error parsing test definitions: " + enhancedErrorMessage);
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
                                System.err.println("Error during test '" + test.name + "': " + cause.getMessage());
                                cause.printStackTrace();
                                // Create a TestResult indicating failure due to execution error
                                List<String> errors = new ArrayList<>();
                                errors.add("Execution Error: " + cause.getMessage());
                                return new TestResult(test.name, Collections.emptyList(), errors);
                            })
                            .thenAccept(results::add)
            );
        }

        allTestsFuture.whenCompleteAsync((v, ex) -> {
            // Note: ex here would catch errors from the thenAccept or the final completion,
            // not individual test errors which are handled in the exceptionally block above.
            // We rely on the results list containing the outcomes.
            updateTestResults(formatTestResults(results));
            cog().status("Tests Complete");
        }, cog().events.exe);
    }

    private CompletableFuture<TestResult> runTest(TestDefinition test) {
        var testKbId = Cog.id(TEST_KB_PREFIX);
        List<String> actionErrors = new ArrayList<>();
        Object actionResult = null; // To hold the result of the main action block

        var testNote = new Note(testKbId, "Test KB: " + test.name, "", Note.Status.IDLE);
        // TODO: Decouple from CogNote - need a generic way to manage temporary KBs/Notes
        ((CogNote) cog()).addNote(testNote);
        context.addActiveNote(testKbId);

        CompletableFuture<Object> setupFuture = executeActionList(test, testKbId, test.setup)
                .exceptionally(ex -> {
                    var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
                    actionErrors.add("Setup failed: " + cause.getMessage());
                    System.err.println("Setup failed for test '" + test.name + "': " + cause.getMessage());
                    cause.printStackTrace();
                    return null; // Allow action/expectations to potentially run or fail gracefully
                });

        CompletableFuture<Object> actionFuture = setupFuture.thenCompose(setupResult -> {
            if (!actionErrors.isEmpty()) {
                return CompletableFuture.completedFuture(null); // Skip action if setup failed
            }
            return executeActionList(test, testKbId, test.action)
                    .exceptionally(ex -> {
                        var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
                        actionErrors.add("Action failed: " + cause.getMessage());
                        System.err.println("Action failed for test '" + test.name + "': " + cause.getMessage());
                        cause.printStackTrace();
                        return null; // Allow expectations/teardown to potentially run
                    });
        });

        CompletableFuture<List<ExpectationResult>> expectationsFuture = actionFuture.thenCompose(result -> {
            // Capture the action result for expectation checks
            // Note: This assumes the *last* action in the action list provides the result for expectations like query/runTool
            // A more robust system might pass results explicitly or allow expectations to target specific action results
            // For now, we'll just pass the result of the actionFuture chain.
            // If actionFuture completed exceptionally, result will be null here.
            Object finalActionResult = result; // Capture the result before checking errors
            if (!actionErrors.isEmpty()) {
                 // If action failed, expectations might not be meaningful, but we still run them
                 // to report what *would* have happened or if they check for absence.
                 // However, for simplicity in this refactor, we'll just pass null as actionResult
                 // if there were action errors. A more complex approach could try to run expectations
                 // even with action errors, but it adds complexity. Let's pass null.
                 finalActionResult = null;
            }
            return checkExpectations(test.expected, testKbId, finalActionResult);
        });


        CompletableFuture<TestResult> c = expectationsFuture.thenCompose(expectationResults ->
                executeActionList(test, testKbId, test.teardown)
                        .exceptionally(ex -> {
                            var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
                            actionErrors.add("Teardown failed: " + cause.getMessage());
                            System.err.println("Teardown failed for test '" + test.name + "': " + cause.getMessage());
                            cause.printStackTrace();
                            return null; // Teardown failure shouldn't fail the test itself, but should be reported
                        })
                        .thenApply(teardownResult -> new TestResult(test.name, expectationResults, actionErrors))
        ).whenComplete((result, ex) -> {
            // Clean up the temporary KB/Note regardless of test outcome
            context.removeActiveNote(testKbId);
            // TODO: Decouple from CogNote
            ((CogNote) cog()).removeNote(testKbId);
        });

        return c;
    }

    private CompletableFuture<Object> executeActionList(TestDefinition test, String testKbId, List<TestAction> actions) {
        CompletableFuture<Object> future = CompletableFuture.completedFuture(null);
        for (var action : actions) {
            // Chain actions sequentially, passing the result of the previous to the next (though not currently used)
            future = future.thenCompose(v -> executeSingleAction(test, testKbId, action));
        }
        return future;
    }

    private CompletableFuture<Object> executeSingleAction(TestDefinition test, String testKbId, TestAction action) {
        return executeActionLogic(test, testKbId, action).orTimeout(TEST_ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        throw new CompletionException(new TimeoutException("Action timed out after " + TEST_ACTION_TIMEOUT_SECONDS + " seconds: " + action.type));
                    }
                    // Wrap other exceptions in CompletionException for consistent handling
                    throw new CompletionException(new RuntimeException("Action '" + action.type + "' failed: " + cause.getMessage(), cause));
                });
    }

    private CompletableFuture<Object> executeActionLogic(TestDefinition test, String testKbId, TestAction action) {
        try {
            return switch (action.type) {
                case "assert" -> executeAssertAction(test, testKbId, action);
                case "addRule" -> executeAddRuleAction(test, testKbId, action);
                case "removeRuleForm" -> executeRemoveRuleFormAction(test, testKbId, action);
                case "retract" -> executeRetractAction(test, testKbId, action);
                case "runTool" -> executeRunToolAction(test, testKbId, action);
                case "query" -> executeQueryAction(test, testKbId, action);
                case "wait" -> executeWaitAction(test, testKbId, action);
                default -> CompletableFuture.failedFuture(new IllegalArgumentException("Unknown action type: " + action.type));
            };
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<Object> executeAssertAction(TestDefinition test, String testKbId, TestAction action) {
        if (!(action.payload instanceof Term.Lst list))
            throw new IllegalArgumentException("Invalid payload for assert: " + action.payload);
        var isNeg = list.op().filter(KIF_OP_NOT::equals).isPresent();
        var s = list.size();
        if (isNeg && s != 2) throw new IllegalArgumentException("Invalid 'not' format for assert.");
        var isEq = !isNeg && list.op().filter(Logic.KIF_OP_EQUAL::equals).isPresent();
        var isOriented = isEq && s == 3 && list.get(1).weight() > list.get(2).weight();
        var type = list.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
        var pri = INPUT_ASSERTION_BASE_PRIORITY / (1.0 + list.weight());
        var pa = new Assertion.PotentialAssertion(list, pri, Set.of(), testKbId, isEq, isNeg, isOriented, testKbId, type, List.of(), 0);

        var committed = context.tryCommit(pa, "test-runner:" + test.name);
        return CompletableFuture.completedFuture(committed != null ? committed.id() : null);
    }

    private CompletableFuture<Object> executeAddRuleAction(TestDefinition test, String testKbId, TestAction action) {
        if (!(action.payload instanceof Term.Lst ruleForm))
            throw new IllegalArgumentException("Invalid payload for addRule: " + action.payload);
        // TODO: Implement KB-scoped rules. Currently adds to global context.
        var r = Rule.parseRule(Cog.id(ID_PREFIX_RULE), ruleForm, 1.0, testKbId); // Rule is tagged with testKbId, but context.addRule is global
        var added = context.addRule(r);
        return CompletableFuture.completedFuture(added);
    }

    private CompletableFuture<Object> executeRemoveRuleFormAction(TestDefinition test, String testKbId, TestAction action) {
        if (!(action.payload instanceof Term.Lst ruleForm))
            throw new IllegalArgumentException("Invalid payload for removeRuleForm: " + action.payload);
        // TODO: Implement KB-scoped rules. Currently removes from global context.
        var removed = context.removeRule(ruleForm);
        return CompletableFuture.completedFuture(removed);
    }

    private CompletableFuture<Object> executeRetractAction(TestDefinition test, String testKbId, TestAction action) {
        if (!(action.payload instanceof Term.Lst retractTargetList) || retractTargetList.size() != 2 || !(retractTargetList.get(0) instanceof Term.Atom typeAtom)) {
            throw new IllegalArgumentException("Invalid payload for retract: Expected (TYPE TARGET)");
        }
        var rtype = typeAtom.value();
        var target = retractTargetList.get(1);
        String targetStr;
        if (target instanceof Term.Atom atom) {
             targetStr = atom.value();
        } else if (target instanceof Term.Lst list) {
             targetStr = list.toKif();
        } else {
             throw new IllegalArgumentException("Invalid target type for retract: " + target.getClass().getSimpleName());
        }

        RetractionType retractionType;
        try {
            retractionType = RetractionType.valueOf(rtype.toUpperCase());
        } catch (IllegalArgumentException e) {
             throw new IllegalArgumentException("Invalid retraction type: " + rtype);
        }

        context.events.emit(new RetractionRequestEvent(targetStr, retractionType, "test-runner:" + test.name, testKbId));
        return CompletableFuture.completedFuture(targetStr);
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
        return toolOpt.get().execute(toolParams).thenApply(r -> r);
    }

    private CompletableFuture<Object> executeQueryAction(TestDefinition test, String testKbId, TestAction action) {
        if (!(action.payload instanceof Term.Lst pattern))
            throw new IllegalArgumentException("Invalid payload for query: " + action.payload);
        var queryTypeStr = (String) action.toolParams.getOrDefault("query_type", "ASK_BINDINGS");
        QueryType queryType;
        try {
            queryType = QueryType.valueOf(queryTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid query_type for query action: " + queryTypeStr);
        }
        return CompletableFuture.completedFuture(cog().querySync(new Query(Cog.id(ID_PREFIX_QUERY + "test_"), queryType, pattern, testKbId, action.toolParams)));
    }

    private CompletableFuture<Object> executeWaitAction(TestDefinition test, String testKbId, TestAction action) {
        if (!(action.payload instanceof Term.Lst conditionList)) {
            throw new IllegalArgumentException("Invalid payload for wait: Expected condition list.");
        }
        var conditionOpOpt = conditionList.op();
         if (conditionOpOpt.isEmpty()) {
             throw new IllegalArgumentException("Invalid wait condition list: Missing operator.");
         }
        var conditionOp = conditionOpOpt.get();
        if (conditionList.size() < 2) {
             throw new IllegalArgumentException("Invalid wait condition list: Missing target.");
        }
        Term conditionTarget = conditionList.get(1);

        long timeoutSeconds = ((Number) action.toolParams.getOrDefault("timeout", TEST_WAIT_DEFAULT_TIMEOUT_SECONDS)).longValue();
        long timeoutMillis = timeoutSeconds * 1000;
        long intervalMillis = ((Number) action.toolParams.getOrDefault("interval", TEST_WAIT_DEFAULT_INTERVAL_MILLIS)).longValue();
        if (timeoutMillis <= 0 || intervalMillis <= 0) {
             throw new IllegalArgumentException("Wait timeout and interval must be positive.");
        }

        // TODO: Implement event-driven wait instead of polling
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < timeoutMillis) {
                boolean conditionMet = false;
                try {
                    conditionMet = switch (conditionOp) {
                        case "assertionExists" -> {
                            if (!(conditionTarget instanceof Term.Lst kif))
                                throw new IllegalArgumentException("wait assertionExists requires a KIF list.");
                            yield findAssertionInTestOrGlobalKb(kif, testKbId).isPresent();
                        }
                        case "assertionDoesNotExist" -> {
                            if (!(conditionTarget instanceof Term.Lst kif))
                                throw new IllegalArgumentException("wait assertionDoesNotExist requires a KIF list.");
                            yield findAssertionInTestOrGlobalKb(kif, testKbId).isEmpty();
                        }
                        default -> throw new IllegalArgumentException("Unknown wait condition type: " + conditionOp);
                    };

                    if (conditionMet) return null; // Condition met, complete successfully

                } catch (IllegalArgumentException e) {
                     throw new CompletionException(e); // Wrap parsing/argument errors
                } catch (Exception e) {
                    // Log other exceptions during condition check but don't fail the wait immediately
                    System.err.println("Error checking wait condition: " + e.getMessage());
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
        }, cog().events.exe); // Run on the event executor
    }


    private Optional<Assertion> findAssertionInTestOrGlobalKb(Term.Lst kif, String testKbId) {
        // TODO: Adjust based on KB-scoped rules/assertions
        return context.findAssertionByKif(kif, testKbId).or(() -> context.findAssertionByKif(kif, context.kbGlobal().id));
    }

    private CompletableFuture<List<ExpectationResult>> checkExpectations(List<TestExpected> expectations, String testKbId, @Nullable Object actionResult) {
        List<CompletableFuture<ExpectationResult>> futures = new ArrayList<>();
        for (var expected : expectations) {
            futures.add(checkSingleExpectation(expected, testKbId, actionResult));
        }
        // Combine all futures and collect results
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join) // Join each completed future to get its result
                        .collect(Collectors.toList()));
    }

    private CompletableFuture<ExpectationResult> checkSingleExpectation(TestExpected expected, String testKbId, @Nullable Object actionResult) {
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
                        System.err.println("Expectation '" + expected.type + "' failed: " + reason);
                        return new ExpectationResult(expected, false, reason);
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
                    default -> Optional.of("Unknown expectation type: " + expected.type); // Should be caught by parseExpectation, but defensive check
                };

                if (failureReason.isEmpty()) {
                    return new ExpectationResult(expected, true, null);
                } else {
                    System.err.println("Expectation '" + expected.type + "' failed: " + failureReason.get());
                    return new ExpectationResult(expected, false, failureReason.get());
                }

            } catch (Exception e) {
                String reason = "Check failed with error: " + e.getMessage();
                System.err.println("Expectation check failed with error: " + e.getMessage());
                e.printStackTrace();
                return new ExpectationResult(expected, false, reason);
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
        if (!(expected.value instanceof Term.Lst expectedBindingsListTerm)) {
             return Optional.of("Internal error - expected value is not a Term.Lst. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
        }

        List<Map<Term.Var, Term>> expectedBindings = new ArrayList<>();
        boolean parseError = false;

        // Handle empty bindings case: () or (())
        if (expectedBindingsListTerm.terms.isEmpty() || (expectedBindingsListTerm.terms.size() == 1 && expectedBindingsListTerm.get(0) instanceof Term.Lst innerList && innerList.terms.isEmpty())) {
             expectedBindings.add(Collections.emptyMap());
        } else {
            for (var bindingPairTerm : expectedBindingsListTerm.terms) {
                 if (bindingPairTerm instanceof Term.Lst bindingPair && bindingPair.size() == 2 && bindingPair.get(0) instanceof Term.Var var && bindingPair.get(1) instanceof Term value) {
                    expectedBindings.add(Map.of(var, value));
                } else {
                    return Optional.of("Invalid binding pair format in expected value: " + bindingPairTerm.toKif());
                }
            }
        }

        List<Map<Term.Var, Term>> actualBindings = answer.bindings();

        // Convert bindings to a comparable format (sorted strings)
        Set<String> expectedBindingStrings = expectedBindings.stream()
            .map(bindingMap -> {
                List<String> entryStrings = new ArrayList<>();
                bindingMap.forEach((var, term) -> entryStrings.add(var.name() + "=" + term.toKif()));
                Collections.sort(entryStrings); // Sort entries within a binding
                return String.join(",", entryStrings);
            })
            .collect(Collectors.toSet());

        Set<String> actualBindingStrings = actualBindings.stream()
            .map(bindingMap -> {
                List<String> entryStrings = new ArrayList<>();
                bindingMap.forEach((var, term) -> entryStrings.add(var.name() + "=" + term.toKif()));
                Collections.sort(entryStrings); // Sort entries within a binding
                return String.join(",", entryStrings);
            })
            .collect(Collectors.toSet());

        boolean passed = Objects.equals(expectedBindingStrings, actualBindingStrings);

        if (!passed) {
            return Optional.of("Expected bindings " + expectedBindingStrings + ", but got " + actualBindingStrings);
        }
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


    // --- Parsing Logic (No changes needed for reporting) ---

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
                        System.err.println("Skipping test with invalid name format (empty or blank Atom): " + list.toKif());
                        continue;
                    }
                } else {
                    System.err.println("Skipping test with invalid name format (not an Atom): " + list.toKif());
                    continue;
                }

                List<TestAction> setup = new ArrayList<>();
                List<TestAction> action = new ArrayList<>();
                List<TestExpected> expected = new ArrayList<>();
                List<TestAction> teardown = new ArrayList<>();

                for (var i = 2; i < list.size(); i++) {
                    var sectionTerm = list.get(i);
                    if (!(sectionTerm instanceof Term.Lst sectionList) || sectionList.terms.isEmpty()) {
                        System.err.println("Skipping test '" + name + "' due to invalid section format: " + sectionTerm.toKif());
                        continue;
                    }
                    var sectionOpOpt = sectionList.op();
                    if (sectionOpOpt.isEmpty()) {
                        throw new ParseException("Section without operator in test '" + name, sectionList.toKif());
                    }
                    var sectionOp = sectionOpOpt.get();
                    var sectionContents = sectionList.terms.stream().skip(1).toList();

                    switch (sectionOp) {
                        case "setup" -> setup.addAll(parseActions(sectionContents));
                        case "action" -> action = parseActions(sectionContents);
                        case "expected" -> expected.addAll(parseExpectations(sectionContents));
                        case "teardown" -> teardown.addAll(parseActions(sectionContents));
                        default -> throw new ParseException("Unknown section type '" + sectionOp + "' in test '" + name, sectionList.toKif());
                    }
                }

                if (action.isEmpty()) {
                     System.err.println("Skipping test '" + name + "' because the 'action' section is missing or empty.");
                     continue;
                }

                definitions.add(new TestDefinition(name, setup, action, expected, teardown));
            } else {
                System.out.println("Ignoring non-test top-level term in definitions: " + term.toKif());
            }
        }
        return definitions;
    }

    private List<TestAction> parseActions(List<Term> terms) {
        List<TestAction> actions = new ArrayList<>();
        for (var term : terms) {
            try {
                actions.add(parseAction(term));
            } catch (IllegalArgumentException e) {
                System.err.println("Skipping invalid action term: " + term.toKif() + " | Error: " + e.getMessage());
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

        return switch (op) {
            case "assert" -> parseAssertAction(actionList);
            case "addRule" -> parseAddRuleAction(actionList);
            case "removeRuleForm" -> parseRemoveRuleFormAction(actionList);
            case "retract" -> parseRetractAction(actionList);
            case "runTool" -> parseRunToolAction(actionList);
            case "query" -> parseQueryAction(actionList);
            case "wait" -> parseWaitAction(actionList);
            default -> throw new IllegalArgumentException("Unknown action operator: " + op);
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
        return new TestAction("retract", retractTargetList, new HashMap<>());
    }

    private TestAction parseRunToolAction(Term.Lst actionList) {
        Map<String, Object> toolParams = new HashMap<>();
        if (actionList.size() > 1) {
             if (actionList.size() == 2 && actionList.get(1) instanceof Term.Lst paramsList && paramsList.op().filter("params"::equals).isPresent()) {
                toolParams = parseParams(paramsList);
            } else {
                 throw new IllegalArgumentException("runTool action parameters must be in a (params (...)) list as the second argument.");
            }
        }
        var toolName = (String) toolParams.get("name");
        if (toolName == null || toolName.isBlank())
            throw new IllegalArgumentException("runTool requires 'name' parameter within params.");

        return new TestAction("runTool", null, toolParams);
    }

    private TestAction parseQueryAction(Term.Lst actionList) {
        if (actionList.size() < 2)
            throw new IllegalArgumentException("query action requires at least one argument (the pattern).");
        Term payload = actionList.get(1);

        Map<String, Object> toolParams = new HashMap<>();
        if (actionList.size() > 2) {
            if (actionList.size() == 3 && actionList.get(2) instanceof Term.Lst paramsList && paramsList.op().filter("params"::equals).isPresent()) {
                toolParams = parseParams(paramsList);
            } else {
                 throw new IllegalArgumentException("query action parameters must be in a (params (...)) list as the third argument.");
            }
        }
        return new TestAction("query", payload, toolParams);
    }

    private TestAction parseWaitAction(Term.Lst actionList) {
        if (actionList.size() < 2)
            throw new IllegalArgumentException("wait action requires at least one argument (the condition list).");
        Term payload = actionList.get(1);

        Map<String, Object> toolParams = new HashMap<>();
        if (actionList.size() > 2) {
            if (actionList.size() == 3 && actionList.get(2) instanceof Term.Lst paramsList && paramsList.op().filter("params"::equals).isPresent()) {
                toolParams = parseParams(paramsList);
            } else {
                 throw new IllegalArgumentException("wait action parameters must be in a (params (...)) list as the third argument.");
            }
        }
        if (!(payload instanceof Term.Lst conditionList) || conditionList.terms.isEmpty() || conditionList.op().isEmpty()) {
             throw new IllegalArgumentException("wait action requires a non-empty list with an operator as its argument (the condition list). Found: " + payload.getClass().getSimpleName() + ". Term: " + actionList.toKif());
        }
        return new TestAction("wait", payload, toolParams);
    }


    private List<TestExpected> parseExpectations(List<Term> terms) {
        List<TestExpected> expectations = new ArrayList<>();
        for (var term : terms) {
            try {
                expectations.add(parseExpectation(term));
            } catch (IllegalArgumentException e) {
                System.err.println("Skipping invalid expectation term: " + term.toKif() + " | Error: " + e.getMessage());
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

        if (expectedList.size() < 2) {
             throw new IllegalArgumentException("Expectation '" + op + "' requires at least one argument.");
        }
        Term expectedValueTerm = expectedList.get(1);

        return switch (op) {
            case "expectedResult" -> parseExpectedResult(op, expectedValueTerm);
            case "expectedBindings" -> parseExpectedBindings(op, expectedValueTerm);
            case "expectedAssertionExists" -> parseExpectedAssertionExists(op, expectedValueTerm);
            case "expectedAssertionDoesNotExist" -> parseExpectedAssertionDoesNotExist(op, expectedValueTerm);
            case "expectedRuleExists" -> parseExpectedRuleExists(op, expectedValueTerm);
            case "expectedRuleDoesNotExist" -> parseExpectedRuleDoesNotExist(op, expectedValueTerm);
            case "expectedKbSize" -> parseExpectedKbSize(op, expectedValueTerm);
            case "expectedToolResult" -> parseExpectedToolResult(op, expectedValueTerm);
            case "expectedToolResultContains" -> parseExpectedToolResultContains(op, expectedValueTerm);
            default -> throw new IllegalArgumentException("Unknown expectation operator: " + op);
        };
    }

    private TestExpected parseExpectedResult(String op, Term expectedValueTerm) {
        if (!(expectedValueTerm instanceof Term.Atom))
            throw new IllegalArgumentException(op + " requires a single boolean atom (true/false).");
        String value = ((Term.Atom) expectedValueTerm).value();

        if (!value.equals("true") && !value.equals("false"))
            throw new IllegalArgumentException(op + " value must be 'true' or 'false'.");
        return new TestExpected(op, Boolean.parseBoolean(value));
    }

    private TestExpected parseExpectedBindings(String op, Term expectedValueTerm) {
        if (!(expectedValueTerm instanceof Term.Lst expectedBindingsListTerm)) {
             throw new IllegalArgumentException(op + " requires a list of binding pairs ((?V1 Val1) ...) or (()) or (). Found: " + expectedValueTerm.getClass().getSimpleName() + ". Term: " + expectedValueTerm.toKif());
        }
        return new TestExpected(op, expectedBindingsListTerm);
    }

    private TestExpected parseExpectedAssertionExists(String op, Term expectedValueTerm) {
        if (!(expectedValueTerm instanceof Term.Lst kif))
            throw new IllegalArgumentException(op + " requires a single KIF list.");
        return new TestExpected(op, kif);
    }

    private TestExpected parseExpectedAssertionDoesNotExist(String op, Term expectedValueTerm) {
        if (!(expectedValueTerm instanceof Term.Lst kif))
            throw new IllegalArgumentException(op + " requires a single KIF list.");
        return new TestExpected(op, kif);
    }

    private TestExpected parseExpectedRuleExists(String op, Term expectedValueTerm) {
        if (!(expectedValueTerm instanceof Term.Lst ruleForm))
            throw new IllegalArgumentException(op + " requires a single rule KIF list.");
        return new TestExpected(op, ruleForm);
    }

    private TestExpected parseExpectedRuleDoesNotExist(String op, Term expectedValueTerm) {
        if (!(expectedValueTerm instanceof Term.Lst ruleForm))
            throw new IllegalArgumentException(op + " requires a single rule KIF list.");
        return new TestExpected(op, ruleForm);
    }

    private TestExpected parseExpectedKbSize(String op, Term expectedValueTerm) {
        if (!(expectedValueTerm instanceof Term.Atom))
            throw new IllegalArgumentException(op + " requires a single integer atom.");
        String value = ((Term.Atom) expectedValueTerm).value();
        try {
            return new TestExpected(op, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(op + " value must be an integer.");
        }
    }

    private TestExpected parseExpectedToolResult(String op, Term expectedValueTerm) {
        return new TestExpected(op, termToObject(expectedValueTerm));
    }

    private TestExpected parseExpectedToolResultContains(String op, Term expectedValueTerm) {
        if (!(expectedValueTerm instanceof Term.Atom)) {
            throw new IllegalArgumentException(op + " requires a single Atom containing the expected substring.");
        }
        return new TestExpected(op, termToObject(expectedValueTerm));
    }


    private Map<String, Object> parseParams(Term.Lst paramsList) {
        Map<String, Object> params = new HashMap<>();
        if (paramsList.op().filter("params"::equals).isEmpty()) {
            throw new IllegalArgumentException("Parameter list must start with 'params'.");
        }
        for (var paramTerm : paramsList.terms.stream().skip(1).toList()) {
            if (paramTerm instanceof Term.Lst paramPair && paramPair.size() == 2 && paramPair.get(0) instanceof Term.Atom) {
                String value = ((Term.Atom) paramPair.get(0)).value();
                params.put(value, termToObject(paramPair.get(1)));
            } else {
                throw new IllegalArgumentException("Invalid parameter format: " + paramTerm.toKif());
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
                System.err.println("Could not parse line/col from ParseException message: " + message);
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
            boolean overallPassed = result.expectationResults.stream().allMatch(ExpectationResult::passed) && result.actionErrors.isEmpty();
            sb.append(overallPassed ? "PASS" : "FAIL").append(": ").append(result.name).append("\n");

            if (!result.actionErrors.isEmpty()) {
                sb.append("  Action Errors:\n");
                result.actionErrors.forEach(error -> sb.append("    - ").append(error).append("\n"));
            }

            if (!result.expectationResults.isEmpty()) {
                 sb.append("  Expectations:\n");
                 for (var expResult : result.expectationResults) {
                     sb.append("    ").append(expResult.passed ? "PASS" : "FAIL").append(": ").append(expResult.expected.type).append(" ").append(termValueToString(expResult.expected.value)); // Use helper for value
                     if (!expResult.passed) {
                         sb.append(" (Reason: ").append(expResult.failureReason).append(")");
                     }
                     sb.append("\n");
                 }
            } else if (result.actionErrors.isEmpty()) {
                 sb.append("  No expectations defined.\n");
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

    private record TestDefinition(String name, List<TestAction> setup, List<TestAction> action, List<TestExpected> expected,
                                  List<TestAction> teardown) {
    }

    private record TestAction(String type, @Nullable Term payload, Map<String, Object> toolParams) {
    }

    private record TestExpected(String type, Object value) {
    }

    // New record for detailed expectation results
    private record ExpectationResult(TestExpected expected, boolean passed, @Nullable String failureReason) {
    }

    // Modified record for overall test result
    private record TestResult(String name, List<ExpectationResult> expectationResults, List<String> actionErrors) {
        // Helper to determine overall pass/fail status
        public boolean isOverallPassed() {
            return expectationResults.stream().allMatch(ExpectationResult::passed) && actionErrors.isEmpty();
        }
    }

    public record RunTestsEvent() implements CogEvent {
    }
}
