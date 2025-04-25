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
                                return new TestResult(test.name, false, "Execution Error: " + cause.getMessage());
                            })
                            .thenAccept(results::add)
            );
        }

        allTestsFuture.whenCompleteAsync((v, ex) -> {
            updateTestResults(formatTestResults(results));
            cog().status("Tests Complete");
        }, cog().events.exe);
    }

    private CompletableFuture<TestResult> runTest(TestDefinition test) {
        var testKbId = Cog.id(TEST_KB_PREFIX);

        var testNote = new Note(testKbId, "Test KB: " + test.name, "", Note.Status.IDLE);
        ((CogNote) cog()).addNote(testNote);

        context.addActiveNote(testKbId);

        CompletableFuture<TestResult> c = executeActionList(test, testKbId, test.setup)
                .thenCompose(setupResult -> executeActionList(test, testKbId, test.action))
                .thenCompose(actionResult -> checkExpectations(test.expected, testKbId, actionResult))
                .thenCompose(expectedResult -> executeActionList(test, testKbId, test.teardown)
                        .thenApply(teardownResult -> new TestResult(test.name, expectedResult, "Details handled in formatting")))
                .whenComplete((result, ex) -> {
                    context.removeActiveNote(testKbId);
                    ((CogNote) cog()).removeNote(testKbId);
                });
        return c;
    }

    private CompletableFuture<Object> executeActionList(TestDefinition test, String testKbId, List<TestAction> actions) {
        CompletableFuture<Object> future = CompletableFuture.completedFuture(null);
        for (var action : actions) {
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
                    throw new CompletionException(new RuntimeException("Action failed: " + action.type + " - " + cause.getMessage(), cause));
                });
    }

    private CompletableFuture<Object> executeActionLogic(TestDefinition test, String testKbId, TestAction action) {
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
                    var pa = new Assertion.PotentialAssertion(list, pri, Set.of(), testKbId, isEq, isNeg, isOriented, testKbId, type, List.of(), 0);

                    var committed = context.tryCommit(pa, "test-runner:" + test.name);
                    yield CompletableFuture.completedFuture(committed != null ? committed.id() : null);
                }
                case "addRule" -> {
                    if (!(action.payload instanceof Term.Lst ruleForm))
                        throw new IllegalArgumentException("Invalid payload for addRule: " + action.payload);
                    var r = Rule.parseRule(Cog.id(ID_PREFIX_RULE), ruleForm, 1.0, testKbId);
                    var added = context.addRule(r);
                    yield CompletableFuture.completedFuture(added);
                }
                case "removeRuleForm" -> {
                    if (!(action.payload instanceof Term.Lst ruleForm))
                        throw new IllegalArgumentException("Invalid payload for removeRuleForm: " + action.payload);
                    var removed = context.removeRule(ruleForm);
                    yield CompletableFuture.completedFuture(removed);
                }
                case "retract" -> {
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
                    yield CompletableFuture.completedFuture(targetStr);
                }
                case "runTool" -> {
                    var toolName = (String) action.toolParams.get("name");
                    if (toolName == null || toolName.isBlank())
                        throw new IllegalArgumentException("runTool requires 'name' parameter.");

                    Map<String, Object> toolParams = new HashMap<>(action.toolParams);
                    toolParams.remove("name");
                    toolParams.putIfAbsent("target_kb_id", testKbId);
                    toolParams.putIfAbsent("note_id", testKbId);

                    var toolOpt = cog().tools.get(toolName);
                    if (toolOpt.isEmpty()) throw new IllegalArgumentException("Tool not found: " + toolName);
                    yield toolOpt.get().execute(toolParams).thenApply(r -> r);
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
                    yield CompletableFuture.completedFuture(cog().querySync(new Query(Cog.id(ID_PREFIX_QUERY + "test_"), queryType, pattern, testKbId, action.toolParams)));
                }
                case "wait" -> {
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

                    yield CompletableFuture.supplyAsync(() -> {
                        long startTime = System.currentTimeMillis();
                        while (System.currentTimeMillis() - startTime < timeoutMillis) {
                            boolean conditionMet = false;
                            try {
                                switch (conditionOp) {
                                    case "assertionExists" -> {
                                        if (!(conditionTarget instanceof Term.Lst kif))
                                            throw new IllegalArgumentException("wait assertionExists requires a KIF list.");
                                        conditionMet = findAssertionInTestOrGlobalKb(kif, testKbId).isPresent();
                                    }
                                    case "assertionDoesNotExist" -> {
                                        if (!(conditionTarget instanceof Term.Lst kif))
                                            throw new IllegalArgumentException("wait assertionDoesNotExist requires a KIF list.");
                                        conditionMet = findAssertionInTestOrGlobalKb(kif, testKbId).isEmpty();
                                    }
                                    default -> throw new IllegalArgumentException("Unknown wait condition type: " + conditionOp);
                                }

                                if (conditionMet) return null;

                            } catch (IllegalArgumentException e) {
                                 throw new CompletionException(e);
                            } catch (Exception e) {
                                System.err.println("Error checking wait condition: " + e.getMessage());
                            }

                            try {
                                Thread.sleep(intervalMillis);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new CompletionException(new InterruptedException("Wait interrupted"));
                            }
                        }
                        throw new CompletionException(new TimeoutException("Wait condition not met within " + timeoutSeconds + " seconds: " + conditionList.toKif()));
                    }, cog().events.exe);
                }
                default -> throw new IllegalArgumentException("Unknown action type: " + action.type);
            };
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private Optional<Assertion> findAssertionInTestOrGlobalKb(Term.Lst kif, String testKbId) {
        return context.findAssertionByKif(kif, testKbId).or(() -> context.findAssertionByKif(kif, context.kbGlobal().id));
    }

    private CompletableFuture<Boolean> checkExpectations(List<TestExpected> expectations, String testKbId, @Nullable Object actionResult) {
        CompletableFuture<Boolean> future = CompletableFuture.completedFuture(true);

        for (var expected : expectations) {
            future = future.thenCompose(currentOverallResult -> currentOverallResult ? checkSingleExpectation(expected, testKbId, actionResult) : CompletableFuture.completedFuture(false));
        }
        return future;
    }

    private CompletableFuture<Boolean> checkSingleExpectation(TestExpected expected, String testKbId, @Nullable Object actionResult) {
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
                        System.err.println("Expectation '" + expected.type + "' failed: requires action result to be a query answer, but got: " + (actionResult == null ? "null" : actionResult.getClass().getSimpleName()));
                        return false;
                    }
                    answer = queryAnswer;
                }

                return switch (expected.type) {
                    case "expectedResult" -> {
                        if (!(expected.value instanceof Boolean expectedBoolean)) {
                            System.err.println("Expectation '" + expected.type + "' failed: Internal error - expected value is not a Boolean. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
                            yield false;
                        }
                        boolean passed = expectedBoolean == (answer.status() == Cog.QueryStatus.SUCCESS);
                        if (!passed) System.err.println("Expectation '" + expected.type + "' failed: Expected status " + (expectedBoolean ? "SUCCESS" : "FAILURE") + ", but got " + answer.status());
                        yield passed;
                    }
                    case "expectedBindings" -> {
                        if (!(expected.value instanceof Term.Lst expectedBindingsListTerm)) {
                            System.err.println("Expectation '" + expected.type + "' failed: Internal error - expected value is not a Term.Lst. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
                            yield false;
                        }

                        List<Map<Term.Var, Term>> expectedBindings = new ArrayList<>();
                        boolean parseError = false;

                        if (expectedBindingsListTerm.terms.size() == 1 && expectedBindingsListTerm.get(0) instanceof Term.Lst innerList && innerList.terms.isEmpty()) {
                             expectedBindings.add(Collections.emptyMap());
                        } else {
                            for (var bindingPairTerm : expectedBindingsListTerm.terms) {
                                 if (bindingPairTerm instanceof Term.Lst bindingPair && bindingPair.size() == 2 && bindingPair.get(0) instanceof Term.Var var && bindingPair.get(1) instanceof Term value) {
                                    expectedBindings.add(Map.of(var, value));
                                } else {
                                    System.err.println("Expectation '" + expected.type + "' failed: Invalid binding pair format in expected value: " + bindingPairTerm.toKif());
                                    parseError = true;
                                    break;
                                }
                            }
                        }

                        if (parseError) yield false;

                        List<Map<Term.Var, Term>> actualBindings = answer.bindings();

                        Set<String> expectedBindingStrings = expectedBindings.stream()
                            .map(bindingMap -> {
                                List<String> entryStrings = new ArrayList<>();
                                bindingMap.forEach((var, term) -> entryStrings.add(var.name() + "=" + term.toKif()));
                                Collections.sort(entryStrings);
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

                        if (!passed) System.err.println("Expectation '" + expected.type + "' failed: Expected bindings " + expectedBindings + ", but got " + actualBindings);
                        yield passed;
                    }
                    case "expectedAssertionExists" -> {
                        if (!(expected.value instanceof Term.Lst expectedKif)) {
                            System.err.println("Expectation '" + expected.type + "' failed: Internal error - expected value is not a Term.Lst. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
                            yield false;
                        }
                        boolean passed = findAssertionInTestOrGlobalKb(expectedKif, testKbId).isPresent();
                         if (!passed) System.err.println("Expectation '" + expected.type + "' failed: Assertion not found: " + expectedKif.toKif());
                        yield passed;
                    }
                    case "expectedAssertionDoesNotExist" -> {
                        if (!(expected.value instanceof Term.Lst expectedKif)) {
                            System.err.println("Expectation '" + expected.type + "' failed: Internal error - expected value is not a Term.Lst. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
                            yield false;
                        }
                        boolean passed = findAssertionInTestOrGlobalKb(expectedKif, testKbId).isEmpty();
                        if (!passed) System.err.println("Expectation '" + expected.type + "' failed: Assertion found unexpectedly: " + expectedKif.toKif());
                        yield passed;
                    }
                    case "expectedRuleExists" -> {
                        if (!(expected.value instanceof Term.Lst expectedRuleForm)) {
                            System.err.println("Expectation '" + expected.type + "' failed: Internal error - expected value is not a Term.Lst. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
                            yield false;
                        }
                        boolean passed = context.rules().stream().anyMatch(r -> r.form().equals(expectedRuleForm));
                        if (!passed) System.err.println("Expectation '" + expected.type + "' failed: Rule not found: " + expectedRuleForm.toKif());
                        yield passed;
                    }
                    case "expectedRuleDoesNotExist" -> {
                        if (!(expected.value instanceof Term.Lst expectedRuleForm)) {
                            System.err.println("Expectation '" + expected.type + "' failed: Internal error - expected value is not a Term.Lst. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
                            yield false;
                        }
                        boolean passed = context.rules().stream().noneMatch(r -> r.form().equals(expectedRuleForm));
                         if (!passed) System.err.println("Expectation '" + expected.type + "' failed: Rule found unexpectedly: " + expectedRuleForm.toKif());
                        yield passed;
                    }
                    case "expectedKbSize" -> {
                        if (!(expected.value instanceof Integer expectedSize)) {
                            System.err.println("Expectation '" + expected.type + "' failed: Internal error - expected value is not an Integer. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
                            yield false;
                        }
                        boolean passed = kb.getAssertionCount() == expectedSize;
                        if (!passed) System.err.println("Expectation '" + expected.type + "' failed: Expected KB size " + expectedSize + ", but got " + kb.getAssertionCount());
                        yield passed;
                    }
                    case "expectedToolResult" -> {
                        boolean passed = Objects.equals(actionResult, expected.value);
                        if (!passed && actionResult instanceof String actualString && expected.value instanceof Term.Atom) {
                             String expectedString = ((Term.Atom) expected.value).value();
                             if (expectedString != null) passed = actualString.startsWith(expectedString);
                        }
                         if (!passed) {
                            System.err.println("Expectation '" + expected.type + "' failed: Expected tool result " + expected.value + ", but got " + actionResult);
                            if (actionResult instanceof String actualResultString && expected.value instanceof Term.Atom && ((Term.Atom) expected.value).value() != null) {
                                System.err.println("(String startsWith check failed: Expected '" + ((Term.Atom) expected.value).value() + "', got '" + actualResultString + "')");
                            }
                        }
                        yield passed;
                    }
                     case "expectedToolResultContains" -> {
                        if (!(expected.value instanceof Term.Atom expectedAtom)) {
                            System.err.println("Expectation '" + expected.type + "' failed: requires a single Atom containing the expected substring.");
                            yield false;
                        }
                        String expectedSubstring = expectedAtom.value();
                        if (expectedSubstring == null) {
                             System.err.println("Expectation '" + expected.type + "' failed: expected substring Atom has null value.");
                             yield false;
                        }

                        String actualResultString = actionResult != null ? actionResult.toString() : "null";
                        String trimmedExpectedSubstring = expectedSubstring.trim();
                        boolean passed = actualResultString.contains(trimmedExpectedSubstring);

                        if (!passed) System.err.println("Expectation '" + expected.type + "' failed: Expected tool result to contain '" + trimmedExpectedSubstring + "', but got '" + actualResultString + "'");
                        yield passed;
                    }
                    default -> {
                        System.err.println("Expectation check failed: Unknown expectation type: " + expected.type);
                        yield false;
                    }
                };
            } catch (Exception e) {
                System.err.println("Expectation check failed with error: " + e.getMessage());
                e.printStackTrace();
                return false;
            }
        }, cog().events.exe);
    }

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
            case "assert", "addRule", "removeRuleForm" -> {
                if (actionList.size() != 2)
                    throw new IllegalArgumentException(op + " action requires exactly one argument (the KIF form). Found size: " + actionList.size() + ". Term: " + actionList.toKif());
                Term payload = actionList.get(1);
                if (!(payload instanceof Term.Lst)) {
                     throw new IllegalArgumentException(op + " action requires a KIF list as its argument. Found: " + payload.getClass().getSimpleName() + ". Term: " + actionList.toKif());
                }
                yield new TestAction(op, payload, new HashMap<>());
            }
            case "retract" -> {
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
                yield new TestAction(op, retractTargetList, new HashMap<>());
            }
            case "runTool" -> {
                if (actionList.size() < 2)
                    throw new IllegalArgumentException("runTool action requires at least tool name.");
                Term toolNameTerm = actionList.get(1);
                if (!(toolNameTerm instanceof Term.Atom))
                    throw new IllegalArgumentException("runTool action requires tool name as the second argument (after the operator): " + actionList.toKif());
                String toolName = ((Term.Atom) toolNameTerm).value();

                Map<String, Object> toolParams = new HashMap<>();
                toolParams.put("name", toolName);

                if (actionList.size() > 2) {
                    if (actionList.size() == 3 && actionList.get(2) instanceof Term.Lst paramsList && paramsList.op().filter("params"::equals).isPresent()) {
                        toolParams.putAll(parseParams(paramsList));
                    } else {
                        throw new IllegalArgumentException("runTool action requires parameters in a (params (...)) list as the third argument.");
                    }
                }
                yield new TestAction(op, null, toolParams);
            }
            case "query" -> {
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
                yield new TestAction(op, payload, toolParams);
            }
            case "wait" -> {
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
                yield new TestAction(op, payload, toolParams);
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
            case "expectedResult" -> {
                if (!(expectedValueTerm instanceof Term.Atom))
                    throw new IllegalArgumentException("expectedResult requires a single boolean atom (true/false).");
                String value = ((Term.Atom) expectedValueTerm).value();

                if (!value.equals("true") && !value.equals("false"))
                    throw new IllegalArgumentException("expectedResult value must be 'true' or 'false'.");
                yield new TestExpected(op, Boolean.parseBoolean(value));
            }
            case "expectedBindings" -> {
                if (!(expectedValueTerm instanceof Term.Lst expectedBindingsListTerm)) {
                     throw new IllegalArgumentException("expectedBindings requires a list of binding pairs ((?V1 Val1) ...) or (()) or (). Found: " + expectedValueTerm.getClass().getSimpleName() + ". Term: " + expectedList.toKif());
                }
                yield new TestExpected(op, expectedBindingsListTerm);
            }
            case "expectedAssertionExists", "expectedAssertionDoesNotExist" -> {
                if (!(expectedValueTerm instanceof Term.Lst kif))
                    throw new IllegalArgumentException(op + " requires a single KIF list.");
                yield new TestExpected(op, kif);
            }
            case "expectedRuleExists", "expectedRuleDoesNotExist" -> {
                if (!(expectedValueTerm instanceof Term.Lst ruleForm))
                    throw new IllegalArgumentException(op + " requires a single rule KIF list.");
                yield new TestExpected(op, ruleForm);
            }
            case "expectedKbSize" -> {
                if (!(expectedValueTerm instanceof Term.Atom))
                    throw new IllegalArgumentException("expectedKbSize requires a single integer atom.");
                String value = ((Term.Atom) expectedValueTerm).value();
                try {
                    yield new TestExpected(op, Integer.parseInt(value));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("expectedKbSize value must be an integer.");
                }
            }
            case "expectedToolResult" -> yield new TestExpected(op, expectedValueTerm);
             case "expectedToolResultContains" -> {
                if (!(expectedValueTerm instanceof Term.Atom))
                    throw new IllegalArgumentException(op + " requires a single Atom containing the expected substring.");
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
            sb.append(result.passed ? "PASS" : "FAIL").append(": ").append(result.name).append("\n");
            if (!result.passed) {
                sb.append("  Status: FAILED\n");
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
            cog().save();
        });
    }

    private record TestDefinition(String name, List<TestAction> setup, List<TestAction> action, List<TestExpected> expected,
                                  List<TestAction> teardown) {
    }

    private record TestAction(String type, @Nullable Term payload, Map<String, Object> toolParams) {
    }

    private record TestExpected(String type, Object value) {
    }

    private record TestResult(String name, boolean passed, String details) {
    }

    public record RunTestsEvent() implements CogEvent {
    }
}
