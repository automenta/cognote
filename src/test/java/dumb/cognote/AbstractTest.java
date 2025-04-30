package dumb.cognote;

import dumb.cognote.KifParser.ParseException;
import dumb.cognote.Logic.AssertionType;
import dumb.cognote.Logic.RetractionType;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static dumb.cognote.Cog.*;
import static dumb.cognote.Logic.KIF_OP_NOT;
import static org.junit.jupiter.api.Assertions.fail;

abstract class AbstractTest {

    private static final long TEST_ACTION_TIMEOUT_SECONDS = 5;
    private static final long TEST_WAIT_DEFAULT_TIMEOUT_SECONDS = 5;
    private static final long TEST_WAIT_DEFAULT_INTERVAL_MILLIS = 100;
    private static final Pattern PARSE_ERROR_LOCATION_PATTERN = Pattern.compile(" at line (\\d+) col (\\d+)$");
    protected Cog cog;
    private Logic.Cognition context;
    private String testKbId;

    @BeforeEach
    void setUp() {
        cog = new Cog() {
            @Deprecated
            @Override
            public void save() {
            }
        };
        cog.start();
        context = cog.context;

        testKbId = Cog.id("test-kb-");
        var testNote = new Note(testKbId, "Test KB: " + testKbId, "", Note.Status.IDLE);
        cog.addNote(testNote);
        context.addActiveNote(testKbId);

        context.addActiveNote(GLOBAL_KB_NOTE_ID);
    }

    @AfterEach
    void tearDown() {
        context.removeActiveNote(testKbId);
        cog.removeNote(testKbId);

        context.removeActiveNote(GLOBAL_KB_NOTE_ID);

        cog.stop();
    }

    private List<Term> parseKif(String kifString) {
        try {
            return KifParser.parseKif(kifString);
        } catch (ParseException e) {
            fail("Failed to parse KIF string:\n" + formatParseException(e, kifString));
            return Collections.emptyList();
        }
    }

    private Term parseSingleTerm(String kifString) {
        var terms = parseKif(kifString);
        if (terms.size() != 1) {
            fail("Expected exactly one top-level term, but found " + terms.size() + " in: " + kifString);
        }
        return terms.getFirst();
    }

    private List<TestAction> parseActions(List<Term> terms) {
        List<TestAction> actions = new ArrayList<>();
        for (var term : terms) {
            try {
                actions.add(parseAction(term));
            } catch (IllegalArgumentException e) {
                fail("Invalid action term: " + term.toKif() + " | Error: " + e.getMessage());
            } catch (Exception e) {
                fail("Unexpected error parsing action term: " + e.getMessage() + " | Term: " + term.toKif(), e);
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
            default ->
                    throw new IllegalArgumentException("Unknown action operator: " + op + ". Term: " + actionList.toKif());
        };
    }

    private TestAction parseAssertAction(Term.Lst actionList) {
        if (actionList.size() != 2)
            throw new IllegalArgumentException("assert action requires exactly one argument (the KIF form). Found size: " + actionList.size() + ". Term: " + actionList.toKif());
        var payload = actionList.get(1);
        if (!(payload instanceof Term.Lst)) {
            throw new IllegalArgumentException("assert action requires a KIF list as its argument. Found: " + payload.getClass().getSimpleName() + ". Term: " + actionList.toKif());
        }
        return new TestAction("assert", payload, new HashMap<>());
    }

    private TestAction parseAddRuleAction(Term.Lst actionList) {
        if (actionList.size() != 2)
            throw new IllegalArgumentException("addRule action requires exactly one argument (the Rule KIF form). Found size: " + actionList.size() + ". Term: " + actionList.toKif());
        var payload = actionList.get(1);
        if (!(payload instanceof Term.Lst)) {
            throw new IllegalArgumentException("addRule action requires a KIF list as its argument. Found: " + payload.getClass().getSimpleName() + ". Term: " + actionList.toKif());
        }
        return new TestAction("addRule", payload, new HashMap<>());
    }

    private TestAction parseRemoveRuleFormAction(Term.Lst actionList) {
        if (actionList.size() != 2)
            throw new IllegalArgumentException("removeRuleForm action requires exactly one argument (the Rule KIF form). Found size: " + actionList.size() + ". Term: " + actionList.toKif());
        var payload = actionList.get(1);
        if (!(payload instanceof Term.Lst)) {
            throw new IllegalArgumentException("removeRuleForm action requires a KIF list as its argument. Found: " + payload.getClass().getSimpleName() + ". Term: " + actionList.toKif());
        }
        return new TestAction("removeRuleForm", payload, new HashMap<>());
    }

    private TestAction parseRetractAction(Term.Lst actionList) {
        if (actionList.size() != 2) {
            throw new IllegalArgumentException("retract action requires exactly one argument (the target list). Found size: " + actionList.size() + ". Term: " + actionList.toKif());
        }
        var targetTerm = actionList.get(1);
        if (!(targetTerm instanceof Term.Lst retractTargetList)) {
            throw new IllegalArgumentException("retract action's argument must be a list (TYPE TARGET). Found: " + targetTerm.getClass().getSimpleName() + ". Term: " + actionList.toKif());
        }
        if (retractTargetList.size() != 2) {
            throw new IllegalArgumentException("retract target list must be size 2 (TYPE TARGET). Found size: " + retractTargetList.size() + ". Term: " + actionList.toKif());
        }
        var typeTerm = retractTargetList.get(0);
        if (!(typeTerm instanceof Term.Atom(var value))) {
            throw new IllegalArgumentException("retract target list's first element must be an Atom (TYPE). Found: " + typeTerm.getClass().getSimpleName() + ". Term: " + actionList.toKif());
        }
        try {
            RetractionType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid retraction type: " + value + ". Must be BY_KIF or BY_ID.");
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
        var payload = actionList.get(1);
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
        var payload = actionList.get(1);

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
        var conditionOpOpt = conditionList.op();
        if (conditionOpOpt.isEmpty()) {
            throw new IllegalArgumentException("wait condition list must have an operator. Term: " + conditionList.toKif());
        }
        var conditionOp = conditionOpOpt.get();
        if (!Set.of("assertionExists", "assertionDoesNotExist").contains(conditionOp)) {
            throw new IllegalArgumentException("Unknown wait condition type: " + conditionOp + ". Must be assertionExists or assertionDoesNotExist. Term: " + conditionList.toKif());
        }
        if (conditionList.size() != 2) {
            throw new IllegalArgumentException("wait condition list must have exactly one argument (the target KIF list). Found size: " + conditionList.size() + ". Term: " + actionList.toKif());
        }
        if (!(conditionList.get(1) instanceof Term.Lst)) {
            throw new IllegalArgumentException("wait condition target must be a KIF list. Found: " + conditionList.get(1).getClass().getSimpleName() + ". Term: " + actionList.toKif());
        }

        if (toolParams.containsKey("timeout")) {
            try {
                var timeout = ((Number) toolParams.get("timeout")).longValue();
                if (timeout <= 0) throw new NumberFormatException();
            } catch (ClassCastException | NumberFormatException e) {
                throw new IllegalArgumentException("Invalid 'timeout' parameter for wait: Must be a positive number. Term: " + actionList.toKif());
            }
        }
        if (toolParams.containsKey("interval")) {
            try {
                var interval = ((Number) toolParams.get("interval")).longValue();
                if (interval <= 0) throw new NumberFormatException();
            } catch (ClassCastException | NumberFormatException e) {
                throw new IllegalArgumentException("Invalid 'interval' parameter for wait: Must be a positive number. Term: " + actionList.toKif());
            }
        }

        return new TestAction("wait", payload, toolParams);
    }

    private List<TestExpected> parseExpectations(List<Term> terms) {
        List<TestExpected> expectations = new ArrayList<>();
        for (var term : terms) {
            try {
                expectations.add(parseExpectation(term));
            } catch (IllegalArgumentException e) {
                fail("Invalid expectation term: " + term.toKif() + " | Error: " + e.getMessage());
            } catch (Exception e) {
                fail("Unexpected error parsing expectation term: " + e.getMessage() + " | Term: " + term.toKif(), e);
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
        var expectedValueTerm = expectedList.get(1);

        return switch (op) {
            case "expectedResult" -> parseExpectedResult(op, expectedValueTerm, expectedList);
            case "expectedBindings" -> parseExpectedBindings(op, expectedValueTerm, expectedList);
            case "expectedAssertionExists" -> parseExpectedAssertionExists(op, expectedValueTerm, expectedList);
            case "expectedAssertionDoesNotExist" ->
                    parseExpectedAssertionDoesNotExist(op, expectedValueTerm, expectedList);
            case "expectedRuleExists" -> parseExpectedRuleExists(op, expectedValueTerm, expectedList);
            case "expectedRuleDoesNotExist" -> parseExpectedRuleDoesNotExist(op, expectedValueTerm, expectedList);
            case "expectedKbSize" -> parseExpectedKbSize(op, expectedValueTerm, expectedList);
            case "expectedToolResult" -> parseExpectedToolResult(op, expectedValueTerm, expectedList);
            case "expectedToolResultContains" -> parseExpectedToolResultContains(op, expectedValueTerm, expectedList);
            default ->
                    throw new IllegalArgumentException("Unknown expectation operator: " + op + ". Term: " + expectedList.toKif());
        };
    }

    private TestExpected parseExpectedResult(String op, Term expectedValueTerm, Term.Lst fullTerm) {
        if (!(expectedValueTerm instanceof Term.Atom))
            throw new IllegalArgumentException(op + " requires a single boolean atom (true/false). Found: " + expectedValueTerm.getClass().getSimpleName() + ". Term: " + fullTerm.toKif());
        var value = ((Term.Atom) expectedValueTerm).value();

        if (!value.equals("true") && !value.equals("false"))
            throw new IllegalArgumentException(op + " value must be 'true' or 'false'. Found: '" + value + "'. Term: " + fullTerm.toKif());
        return new TestExpected(op, Boolean.parseBoolean(value));
    }

    private TestExpected parseExpectedBindings(String op, Term expectedValueTerm, Term.Lst fullTerm) {
        if (!(expectedValueTerm instanceof Term.Lst solutionsListTerm)) {
            throw new IllegalArgumentException(op + " requires a list of solutions, where each solution is a list of binding pairs ((?V1 Val1) ...). Found: " + expectedValueTerm.getClass().getSimpleName() + ". Term: " + fullTerm.toKif());
        }

        if (solutionsListTerm.terms.isEmpty()) {
            return new TestExpected(op, Collections.emptyList());
        }

        List<Map<Term.Var, Term>> expectedBindings = new ArrayList<>();
        for (var solutionTerm : solutionsListTerm.terms) {
            if (!(solutionTerm instanceof Term.Lst bindingPairsList)) {
                throw new IllegalArgumentException("Each element in the expected bindings list must be a list of binding pairs. Found: " + solutionTerm.getClass().getSimpleName() + ". Term: " + fullTerm.toKif());
            }

            Map<Term.Var, Term> solutionMap = new HashMap<>();
            for (var bindingPairTerm : bindingPairsList.terms) {
                if (bindingPairTerm instanceof Term.Lst bindingPair && bindingPair.size() == 2 && bindingPair.get(0) instanceof Term.Var var && bindingPair.get(1) instanceof Term value) {
                    solutionMap.put(var, value);
                } else {
                    throw new IllegalArgumentException("Invalid binding pair format in a solution: " + bindingPairTerm.toKif() + ". Expected (?Var Value). Term: " + fullTerm.toKif());
                }
            }
            expectedBindings.add(solutionMap);
        }

        return new TestExpected(op, expectedBindings);
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
        var value = ((Term.Atom) expectedValueTerm).value();
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
                var value = ((Term.Atom) paramPair.get(0)).value();
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
                var v = atom.value();
                try {
                    yield Integer.parseInt(v);
                } catch (NumberFormatException e1) {
                    try {
                        yield Double.parseDouble(v);
                    } catch (NumberFormatException e2) {
                        if (v.equalsIgnoreCase("true")) yield true;
                        if (v.equalsIgnoreCase("false")) yield false;
                        yield v;
                    }
                }
            }
            case Term.Lst list -> list;
            case Term.Var var -> var;
        };
    }

    private String termValueToString(Object value) {
        return value instanceof Term term ? term.toKif() : String.valueOf(value);
    }

    private String formatParseException(ParseException e, String sourceText) {
        var message = e.getMessage();
        var matcher = PARSE_ERROR_LOCATION_PATTERN.matcher(message);
        var lineNum = -1;
        var colNum = -1;
        var baseMessage = message;

        if (matcher.find()) {
            try {
                lineNum = Integer.parseInt(matcher.group(1));
                colNum = Integer.parseInt(matcher.group(2));
                baseMessage = message.substring(0, matcher.start());
            } catch (NumberFormatException ex) {
            }
        }

        var sb = new StringBuilder();
        sb.append(baseMessage);

        if (lineNum > 0 && colNum > 0) {
            var lines = sourceText.split("\\r?\\n");
            if (lineNum <= lines.length) {
                var errorLine = lines[lineNum - 1];
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

    private @Nullable Object executeActionList(List<TestAction> actions) {
        Object lastResult = null;
        for (var action : actions) {
            lastResult = executeSingleAction(action);
        }
        return lastResult;
    }

    private @Nullable Object executeSingleAction(TestAction action) {
        var actionFuture = executeActionLogic(action);

        try {
            return actionFuture.orTimeout(TEST_ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
        } catch (Exception e) {
            var cause = (e instanceof java.util.concurrent.CompletionException ce && ce.getCause() != null) ? ce.getCause() : e;
            if (cause instanceof TimeoutException) {
                throw new RuntimeException("Action '" + action.type + "' timed out after " + TEST_ACTION_TIMEOUT_SECONDS + " seconds.", cause);
            }
            throw new RuntimeException("Action '" + action.type + "' failed: " + cause.getMessage(), cause);
        }
    }

    private CompletableFuture<Object> executeActionLogic(TestAction action) {
        try {
            return switch (action.type) {
                case "assert" -> CompletableFuture.completedFuture(executeAssertAction(action));
                case "addRule" -> CompletableFuture.completedFuture(executeAddRuleAction(action));
                case "removeRuleForm" -> CompletableFuture.completedFuture(executeRemoveRuleFormAction(action));
                case "retract" -> CompletableFuture.completedFuture(executeRetractAction(action));
                case "runTool" -> executeRunToolAction(action);
                case "query" -> CompletableFuture.completedFuture(executeQueryAction(action));
                case "wait" -> executeWaitAction(action);
                default ->
                        CompletableFuture.failedFuture(new IllegalArgumentException("Unknown action type: " + action.type));
            };
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new RuntimeException("Action '" + action.type + "' setup failed: " + e.getMessage(), e));
        }
    }

    private Object executeAssertAction(TestAction action) {
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

        var committed = context.tryCommit(pa, "test-runner:" + testKbId);
        return committed != null ? committed.id() : null;
    }

    private Object executeAddRuleAction(TestAction action) {
        if (!(action.payload instanceof Term.Lst ruleForm))
            throw new IllegalArgumentException("Invalid payload for addRule: Expected Rule KIF list. Found: " + (action.payload == null ? "null" : action.payload.getClass().getSimpleName()));
        var r = Rule.parseRule(Cog.id(ID_PREFIX_RULE), ruleForm, 1.0, testKbId);
        return context.addRule(r);
    }

    private Object executeRemoveRuleFormAction(TestAction action) {
        if (!(action.payload instanceof Term.Lst ruleForm))
            throw new IllegalArgumentException("Invalid payload for removeRuleForm: Expected Rule KIF list. Found: " + (action.payload == null ? "null" : action.payload.getClass().getSimpleName()));
        return context.removeRule(ruleForm);
    }

    private Object executeRetractAction(TestAction action) {
        if (!(action.payload instanceof Term.Lst retractTargetList) || retractTargetList.size() != 2 || !(retractTargetList.get(0) instanceof Term.Atom(
                var value
        ))) {
            throw new IllegalArgumentException("Invalid payload for retract: Expected (TYPE TARGET). Found: " + (action.payload == null ? "null" : action.payload.toKif()));
        }
        var target = retractTargetList.get(1);
        String targetStr;
        RetractionType retractionType;

        try {
            retractionType = RetractionType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid retraction type: " + value + ". Must be BY_KIF or BY_ID.");
        }

        if (retractionType == RetractionType.BY_KIF) {
            if (!(target instanceof Term.Lst list)) {
                throw new IllegalArgumentException("Invalid target for retract BY_KIF: Expected KIF list. Found: " + target.getClass().getSimpleName() + ". Term: " + target.toKif());
            }
            targetStr = list.toKif();
        } else if (retractionType == RetractionType.BY_ID) {
            if (!(target instanceof Term.Atom(var v)))
                throw new IllegalArgumentException("Invalid target for retract BY_ID: Expected Atom (ID). Found: " + target.getClass().getSimpleName() + ". Term: " + target.toKif());
            targetStr = v;
        } else {
            throw new IllegalArgumentException("Unsupported retraction type: " + value);
        }

        cog.events.emit(new Event.RetractionRequestEvent(targetStr, retractionType, "test-runner:" + testKbId, testKbId));
        return targetStr;
    }

    private CompletableFuture<Object> executeRunToolAction(TestAction action) {
        var toolName = (String) action.toolParams.get("name");
        if (toolName == null || toolName.isBlank())
            throw new IllegalArgumentException("runTool requires 'name' parameter.");

        Map<String, Object> toolParams = new HashMap<>(action.toolParams);
        toolParams.putIfAbsent("target_kb_id", testKbId);
        toolParams.putIfAbsent("note_id", testKbId);

        var toolOpt = cog.tools.get(toolName);
        if (toolOpt.isEmpty()) throw new IllegalArgumentException("Tool not found: " + toolName);

        return toolOpt.get().execute(toolParams).thenApply(r -> r);
    }

    private Object executeQueryAction(TestAction action) {
        if (!(action.payload instanceof Term.Lst pattern))
            throw new IllegalArgumentException("Invalid payload for query: Expected KIF list pattern. Found: " + (action.payload == null ? "null" : action.payload.getClass().getSimpleName()));
        var queryTypeStr = (String) action.toolParams.getOrDefault("query_type", "ASK_BINDINGS");
        Cog.QueryType queryType;
        try {
            queryType = QueryType.valueOf(queryTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid query_type for query action: " + queryTypeStr + ". Must be ASK_BINDINGS or ASK_BOOLEAN.");
        }
        return cog.querySync(new Query(Cog.id(ID_PREFIX_QUERY + "test_"), queryType, pattern, testKbId, action.toolParams));
    }

    private CompletableFuture<Object> executeWaitAction(TestAction action) {
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
        var conditionTarget = conditionList.get(1);

        var timeoutSeconds = ((Number) action.toolParams.getOrDefault("timeout", TEST_WAIT_DEFAULT_TIMEOUT_SECONDS)).longValue();
        var timeoutMillis = timeoutSeconds * 1000;
        var intervalMillis = ((Number) action.toolParams.getOrDefault("interval", TEST_WAIT_DEFAULT_INTERVAL_MILLIS)).longValue();
        if (timeoutMillis <= 0 || intervalMillis <= 0) {
            throw new IllegalArgumentException("Wait timeout and interval must be positive.");
        }

        return CompletableFuture.supplyAsync(() -> {
            var startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < timeoutMillis) {
                var conditionMet = false;
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

                    if (conditionMet) return null;

                } catch (IllegalArgumentException e) {
                    throw new RuntimeException(e);
                } catch (Exception e) {
                    System.err.println("TestPlugin: Error checking wait condition: " + e.getMessage());
                }

                try {
                    Thread.sleep(intervalMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Wait interrupted", e);
                }
            }
            throw new RuntimeException("Wait condition not met within " + timeoutSeconds + " seconds: " + conditionList.toKif());
        });
    }

    private Optional<Assertion> findAssertionInTestOrGlobalKb(Term.Lst kif, String testKbId) {
        return context.findAssertionByKif(kif, testKbId).or(() -> context.findAssertionByKif(kif, context.kbGlobal().id));
    }

    private void checkExpectations(List<TestExpected> expectations, @Nullable Object actionResult) {
        for (var expected : expectations) {
            checkSingleExpectation(expected, actionResult);
        }
    }

    private void checkSingleExpectation(TestExpected expected, @Nullable Object actionResult) {
        try {
            var kb = context.kb(testKbId);

            Answer answer = null;
            var requiresAnswer = switch (expected.type) {
                case "expectedResult", "expectedBindings" -> true;
                default -> false;
            };

            if (requiresAnswer) {
                if (!(actionResult instanceof Answer queryAnswer)) {
                    fail("Expectation '" + expected.type + "' failed: Requires action result to be a query answer, but got: " + (actionResult == null ? "null" : actionResult.getClass().getSimpleName()) + " (Actual: " + termValueToString(actionResult) + ")");
                    return;
                }
                answer = queryAnswer;
            }

            var failureReason = switch (expected.type) {
                case "expectedResult" -> checkExpectedResult(expected, answer);
                case "expectedBindings" -> checkExpectedBindings(expected, answer);
                case "expectedAssertionExists" -> checkExpectedAssertionExists(expected, testKbId);
                case "expectedAssertionDoesNotExist" -> checkExpectedAssertionDoesNotExist(expected, testKbId);
                case "expectedRuleExists" -> checkExpectedRuleExists(expected);
                case "expectedRuleDoesNotExist" -> checkExpectedRuleDoesNotExist(expected);
                case "expectedKbSize" -> checkExpectedKbSize(expected, kb);
                case "expectedToolResult" -> checkExpectedToolResult(expected, actionResult);
                case "expectedToolResultContains" -> checkExpectedToolResultContains(expected, actionResult);
                default ->
                        Optional.of("Internal error: Unknown expectation type '" + expected.type + "' passed to checkSingleExpectation.");
            };

            failureReason.ifPresent(s -> fail("Expectation '" + expected.type + " " + termValueToString(expected.value()) + "' failed: " + s));

        } catch (Exception e) {
            fail("Expectation check failed with error for '" + expected.type + " " + termValueToString(expected.value()) + "': " + e.getMessage(), e);
        }
    }

    private Optional<String> checkExpectedResult(TestExpected expected, Answer answer) {
        if (!(expected.value instanceof Boolean expectedBoolean)) {
            return Optional.of("Internal error - expected value is not a Boolean. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
        }
        var passed = expectedBoolean == (answer.status() == Cog.QueryStatus.SUCCESS);
        return passed ? Optional.empty() : Optional.of("Expected status " + (expectedBoolean ? "SUCCESS" : "FAILURE") + ", but got " + answer.status());
    }

    private Optional<String> checkExpectedBindings(TestExpected expected, Answer answer) {
        if (!(expected.value instanceof List<?> expectedBindingsList)) {
            return Optional.of("Internal error - expected value is not a List. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
        }

        var actualBindings = answer.bindings();

        if (expectedBindingsList.isEmpty()) {
            var passed = actualBindings.isEmpty();
            return passed ? Optional.empty() : Optional.of("Expected no bindings, but got " + actualBindings.size() + " bindings.");
        }

        if (!(expectedBindingsList.getFirst() instanceof Map)) {
            return Optional.of("Internal error - expected value list does not contain Maps.");
        }

        var expectedBindingStrings = ((List<Map<Term.Var, Term>>) expectedBindingsList).stream()
                .map(bindingMap -> {
                    List<String> entryStrings = new ArrayList<>();
                    bindingMap.forEach((var, term) -> entryStrings.add(var.name() + "=" + termValueToString(term)));
                    Collections.sort(entryStrings);
                    return String.join(",", entryStrings);
                })
                .collect(Collectors.toSet());

        var actualBindingStrings = actualBindings.stream()
                .map(bindingMap -> {
                    List<String> entryStrings = new ArrayList<>();
                    bindingMap.forEach((var, term) -> entryStrings.add(var.name() + "=" + termValueToString(term)));
                    Collections.sort(entryStrings);
                    return String.join(",", entryStrings);
                })
                .collect(Collectors.toSet());

        var passed = Objects.equals(expectedBindingStrings, actualBindingStrings);

        return passed ? Optional.empty() : Optional.of("Expected bindings " + expectedBindingStrings + ", but got " + actualBindingStrings);
    }

    private Optional<String> checkExpectedAssertionExists(TestExpected expected, String testKbId) {
        if (!(expected.value instanceof Term.Lst expectedKif)) {
            return Optional.of("Internal error - expected value is not a Term.Lst. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
        }
        var passed = findAssertionInTestOrGlobalKb(expectedKif, testKbId).isPresent();
        return passed ? Optional.empty() : Optional.of("Assertion not found: " + expectedKif.toKif());
    }

    private Optional<String> checkExpectedAssertionDoesNotExist(TestExpected expected, String testKbId) {
        if (!(expected.value instanceof Term.Lst expectedKif)) {
            return Optional.of("Internal error - expected value is not a Term.Lst. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
        }
        var passed = findAssertionInTestOrGlobalKb(expectedKif, testKbId).isEmpty();
        return passed ? Optional.empty() : Optional.of("Assertion found unexpectedly: " + expectedKif.toKif());
    }

    private Optional<String> checkExpectedRuleExists(TestExpected expected) {
        if (!(expected.value instanceof Term.Lst ruleForm)) {
            return Optional.of("Internal error - expected value is not a Term.Lst. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
        }
        var passed = context.rules().stream().anyMatch(r -> r.form().equals(ruleForm));
        return passed ? Optional.empty() : Optional.of("Rule not found: " + ruleForm.toKif());
    }

    private Optional<String> checkExpectedRuleDoesNotExist(TestExpected expected) {
        if (!(expected.value instanceof Term.Lst ruleForm)) {
            return Optional.of("Internal error - expected value is not a Term.Lst. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
        }
        var passed = context.rules().stream().noneMatch(r -> r.form().equals(ruleForm));
        return passed ? Optional.empty() : Optional.of("Rule found unexpectedly: " + ruleForm.toKif());
    }

    private Optional<String> checkExpectedKbSize(TestExpected expected, Knowledge kb) {
        if (!(expected.value instanceof Integer expectedSize)) {
            return Optional.of("Internal error - expected value is not an Integer. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
        }
        var actualSize = kb.getAssertionCount();
        var passed = actualSize == expectedSize;
        return passed ? Optional.empty() : Optional.of("Expected KB size " + expectedSize + ", but got " + actualSize);
    }

    private Optional<String> checkExpectedToolResult(TestExpected expected, @Nullable Object actionResult) {
        var passed = Objects.equals(actionResult, expected.value);
        return passed ? Optional.empty() : Optional.of("Expected tool result " + expected.value + ", but got " + actionResult);
    }

    private Optional<String> checkExpectedToolResultContains(TestExpected expected, @Nullable Object actionResult) {
        if (!(expected.value instanceof String expectedSubstring)) {
            return Optional.of("Internal error - expected value is not a String. Found: " + (expected.value == null ? "null" : expected.value.getClass().getSimpleName()));
        }
        if (!(actionResult instanceof String actualResultString)) {
            return Optional.of("Action result is not a String. Found: " + (actionResult == null ? "null" : actionResult.getClass().getSimpleName()));
        }
        var passed = actualResultString.contains(expectedSubstring);
        return passed ? Optional.empty() : Optional.of("Tool result '" + actualResultString + "' does not contain '" + expectedSubstring + "'");
    }

    protected void runKifTest(String testKif) {
        var testTerm = parseSingleTerm(testKif);

        if (!(testTerm instanceof Term.Lst testList) || testList.terms.isEmpty() || testList.op().filter("test"::equals).isEmpty()) {
            fail("Top-level term is not a valid (test ...) list: " + testTerm.toKif());
            return;
        }

        if (testList.size() < 2 || !(testList.get(1) instanceof Term.Atom)) {
            fail("Test definition must have a name as the second element. Term: " + testList.toKif());
            return;
        }
        var testName = ((Term.Atom) testList.get(1)).value();

        List<TestAction> setup = new ArrayList<>();
        List<TestAction> action = new ArrayList<>();
        List<TestExpected> expected = new ArrayList<>();
        List<TestAction> teardown = new ArrayList<>();

        for (var i = 2; i < testList.size(); i++) {
            var sectionTerm = testList.get(i);
            if (!(sectionTerm instanceof Term.Lst sectionList) || sectionList.terms.isEmpty()) {
                fail("Invalid section format in test '" + testName + "': " + sectionTerm.toKif());
                continue;
            }

            var sectionOpOpt = sectionList.op();
            if (sectionOpOpt.isEmpty()) {
                fail("Section without operator in test '" + testName + "': " + sectionList.toKif());
                continue;
            }
            var sectionOp = sectionOpOpt.get();
            var sectionContents = sectionList.terms.stream().skip(1).toList();

            try {
                switch (sectionOp) {
                    case "setup" -> setup.addAll(parseActions(sectionContents));
                    case "action" -> action = parseActions(sectionContents);
                    case "expected" -> expected.addAll(parseExpectations(sectionContents));
                    case "teardown" -> teardown.addAll(parseActions(sectionContents));
                    default ->
                            fail("Unknown section type '" + sectionOp + "' in test '" + testName + "': " + sectionList.toKif());
                }
            } catch (Exception e) {
                fail("Unexpected error parsing section '" + sectionOp + "' in test '" + testName + "': " + e.getMessage() + " | Term: " + sectionList.toKif(), e);
            }
        }

        if (action.isEmpty()) {
            fail("Test '" + testName + "' is missing the mandatory 'action' section.");
            return;
        }

        Object actionResult;
        try {
            executeActionList(setup);
            actionResult = executeActionList(action);
            checkExpectations(expected, actionResult);
        } finally {
            try {
                executeActionList(teardown);
            } catch (Exception e) {
                System.err.println("Error during teardown for test '" + testName + "': " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private record TestAction(String type, @Nullable Term payload, Map<String, Object> toolParams) {
    }

    private record TestExpected(String type, Object value) {
    }
}
