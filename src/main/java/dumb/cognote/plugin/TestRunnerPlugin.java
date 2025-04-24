package dumb.cognote.plugin;

import dumb.cognote.Cog;
import dumb.cognote.Events;
import dumb.cognote.Logic;
import dumb.cognote.Term;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static dumb.cognote.Cog.TEST_DEFINITIONS_NOTE_ID;
import static dumb.cognote.Cog.TEST_RESULTS_NOTE_ID;

public class TestRunnerPlugin extends Plugin.BasePlugin {

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
        List<TestDefinition> tests = parseTestDefinitions(testDefinitionsText);

        if (tests.isEmpty()) {
            updateTestResults("No tests found in Test Definitions note.");
            cog().status("Tests Complete (No Tests)");
            return;
        }

        List<TestResult> results = new ArrayList<>();
        for (var test : tests) {
            System.out.println("TestRunnerPlugin: Running test '" + test.name + "'");
            try {
                var query = new Cog.Query(
                        Cog.id("test_query_"),
                        test.queryType,
                        test.queryPattern,
                        Cog.GLOBAL_KB_NOTE_ID, // Run tests against the global KB for now
                        Map.of()
                );
                var answer = cog().querySync(query);
                results.add(runTest(test, answer));
            } catch (Exception e) {
                System.err.println("TestRunnerPlugin: Error running test '" + test.name + "': " + e.getMessage());
                e.printStackTrace();
                results.add(new TestResult(test.name, false, "Error: " + e.getMessage()));
            }
        }

        updateTestResults(formatTestResults(results));
        cog().status("Tests Complete");
        System.out.println("TestRunnerPlugin: Tests finished.");
    }

    private List<TestDefinition> parseTestDefinitions(String text) {
        List<TestDefinition> definitions = new ArrayList<>();
        if (text == null || text.isBlank()) return definitions;

        try (var reader = new StringReader(text)) {
            var parser = new Logic.KifParser(reader);
            List<Term> terms = parser.parseKif(text);

            for (var term : terms) {
                if (term instanceof Term.Lst list && list.size() >= 4 && list.op().filter("test"::equals).isPresent()) {
                    var nameTerm = list.get(1);
                    var queryTerm = list.get(2);
                    var expectedTerm = list.get(3);

                    if (!(nameTerm instanceof Term.Atom nameAtom)) {
                        System.err.println("TestRunnerPlugin: Skipping test with invalid name format: " + list.toKif());
                        continue;
                    }
                    if (!(queryTerm instanceof Term.Lst queryList) || queryList.size() < 2 || !queryList.op().filter("query"::equals).isPresent() || queryList.size() != 2 || !(queryList.get(1) instanceof Term.Lst patternList)) {
                        System.err.println("TestRunnerPlugin: Skipping test with invalid query format: " + list.toKif());
                        continue;
                    }

                    var testName = nameAtom.value();
                    var queryPattern = patternList;

                    Cog.QueryType queryType;
                    Object expectedValue;

                    if (expectedTerm instanceof Term.Lst expectedList) {
                        if (expectedList.op().filter("expectedBindings"::equals).isPresent()) {
                            queryType = Cog.QueryType.ASK_BINDINGS;
                            // Parse expected bindings: ((?V1 Val1) (?V2 Val2) ...)
                            List<Map<Term.Var, Term>> expectedBindings = new ArrayList<>();
                            if (expectedList.size() > 1) {
                                for (var bindingListTerm : expectedList.terms.stream().skip(1).toList()) {
                                    if (bindingListTerm instanceof Term.Lst bindingList && bindingList.size() == 2 && bindingList.get(0) instanceof Term.Var var && bindingList.get(1) instanceof Term value) {
                                        expectedBindings.add(Map.of(var, value));
                                    } else {
                                        System.err.println("TestRunnerPlugin: Skipping test '" + testName + "' due to invalid expectedBindings format: " + expectedList.toKif());
                                        expectedBindings = null; // Mark as invalid
                                        break;
                                    }
                                }
                            }
                            if (expectedBindings != null) {
                                expectedValue = expectedBindings;
                            } else {
                                continue; // Skip this test due to invalid format
                            }

                        } else {
                            System.err.println("TestRunnerPlugin: Skipping test '" + testName + "' with unknown expected format: " + expectedList.toKif());
                            continue;
                        }
                    } else if (expectedTerm instanceof Term.Atom expectedAtom) {
                        if (expectedAtom.value().equals("true") || expectedAtom.value().equals("false")) {
                            queryType = Cog.QueryType.ASK_TRUE_FALSE;
                            expectedValue = Boolean.parseBoolean(expectedAtom.value());
                        } else {
                            System.err.println("TestRunnerPlugin: Skipping test '" + testName + "' with unknown expected format: " + expectedAtom.toKif());
                            continue;
                        }
                    } else {
                        System.err.println("TestRunnerPlugin: Skipping test '" + testName + "' with invalid expected format: " + expectedTerm.toKif());
                        continue;
                    }

                    definitions.add(new TestDefinition(testName, queryType, queryPattern, expectedValue));

                } else {
                    // Ignore non-(test ...) top-level terms
                    if (!(term instanceof Term.Lst list && list.op().filter("test"::equals).isPresent())) {
                         System.out.println("TestRunnerPlugin: Ignoring non-test top-level term in definitions: " + term.toKif());
                    }
                }
            }

        } catch (Logic.KifParser.ParseException e) {
            System.err.println("TestRunnerPlugin: Error parsing test definitions KIF: " + e.getMessage());
        }
        return definitions;
    }

    private TestResult runTest(TestDefinition test, Cog.Answer answer) {
        boolean passed = false;
        String details = "";

        if (answer.status() == Cog.QueryStatus.ERROR) {
            passed = false;
            details = "Query Error: " + (answer.explanation() != null ? answer.explanation().details() : "Unknown");
        } else if (answer.status() == Cog.QueryStatus.TIMEOUT) {
            passed = false;
            details = "Query Timed Out";
        } else { // SUCCESS or FAILURE
            switch (test.queryType) {
                case ASK_TRUE_FALSE -> {
                    boolean expected = (Boolean) test.expectedValue;
                    boolean actual = (answer.status() == Cog.QueryStatus.SUCCESS);
                    passed = (expected == actual);
                    details = String.format("Expected: %b, Actual: %b", expected, actual);
                }
                case ASK_BINDINGS -> {
                    @SuppressWarnings("unchecked")
                    List<Map<Term.Var, Term>> expected = (List<Map<Term.Var, Term>>) test.expectedValue;
                    List<Map<Term.Var, Term>> actual = answer.bindings();

                    // Simple comparison: check if the lists of bindings are equal (order matters for now)
                    passed = Objects.equals(expected, actual);

                    details = String.format("Expected Bindings (%d): %s\nActual Bindings (%d): %s",
                            expected.size(), formatBindings(expected),
                            actual.size(), formatBindings(actual));
                }
                case ACHIEVE_GOAL -> {
                    // For ACHIEVE_GOAL, success means the goal was proven (answer.status == SUCCESS)
                    // We don't have a specific expected value format for ACHIEVE_GOAL yet,
                    // so we just check if the status matches the expectation (e.g., expectedResult true/false)
                    if (test.expectedValue instanceof Boolean expected) {
                         boolean actual = (answer.status() == Cog.QueryStatus.SUCCESS);
                         passed = (expected == actual);
                         details = String.format("Expected Goal Achieved: %b, Actual Goal Achieved: %b", expected, actual);
                    } else {
                         // Default success check for ACHIEVE_GOAL if no specific expectation
                         passed = (answer.status() == Cog.QueryStatus.SUCCESS);
                         details = "Goal Achievement Status: " + answer.status();
                    }
                }
            }
        }

        return new TestResult(test.name, passed, details);
    }

    private String formatBindings(List<Map<Term.Var, Term>> bindings) {
        return bindings.stream()
                .map(b -> b.entrySet().stream()
                        .map(e -> e.getKey().name() + "=" + e.getValue().toKif())
                        .collect(Collectors.joining(", ")))
                .collect(Collectors.joining("; "));
    }


    private String formatTestResults(List<TestResult> results) {
        var sb = new StringBuilder();
        sb.append("--- Test Results (").append(java.time.LocalDateTime.now()).append(") ---\n\n");
        int passedCount = 0;
        int failedCount = 0;

        for (var result : results) {
            sb.append(result.passed ? "PASS" : "FAIL").append(": ").append(result.name).append("\n");
            if (!result.passed) {
                sb.append("  Details: ").append(result.details.replace("\n", "\n    ")).append("\n");
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
            cog().save(); // Save the updated test results note
        });
    }


    private record TestDefinition(String name, Cog.QueryType queryType, Term.Lst queryPattern, Object expectedValue) {
    }

    private record TestResult(String name, boolean passed, String details) {
    }
}
