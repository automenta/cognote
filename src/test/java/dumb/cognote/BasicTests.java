package dumb.cognote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentest4j.AssertionFailedError;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class BasicTests extends AbstractTest {

    @Test
    void simpleFactQuery() {
        runKifTest("""
                (test "Simple Fact Query"
                  (setup (assert (instance MyCat Cat)))
                  (action (query (instance ?X Cat)))
                  (expected (expectedResult true) (expectedBindings (((?X MyCat))))) ; Corrected KIF for expectedBindings
                  (teardown (retract (BY_KIF (instance MyCat Cat)))))
                """);
    }


    // --- JUnit Test Methods ---

    @Test
    void queryWithMultipleBindings() {
        runKifTest("""
                (test "Query with Multiple Bindings"
                  (setup
                    (assert (instance MyCat Cat))
                    (assert (instance YourCat Cat))
                    (assert (instance MyDog Dog)))
                  (action (query (instance ?X Cat)))
                  (expected
                    (expectedResult true)
                    (expectedBindings (((?X MyCat)) ((?X YourCat))))) ; Corrected KIF for expectedBindings (two solutions)
                  (teardown
                    (retract (BY_KIF (instance MyCat Cat)))
                    (retract (BY_KIF (instance YourCat Cat)))
                    (retract (BY_KIF (instance MyDog Dog)))))
                """);
    }


    // --- Translated Tests ---

    @Test
    void queryFailure() {
        runKifTest("""
                (test "Query Failure"
                  (setup (assert (instance MyDog Dog)))
                  (action (query (instance MyDog Cat)))
                  (expected (expectedResult false) (expectedBindings ())) ; Expected empty bindings list (no solutions)
                  (teardown (retract (BY_KIF (instance MyDog Dog)))))
                """);
    }

    @Test
    void forwardChainingRule() {
        runKifTest("""
                (test "Forward Chaining Rule"
                  (setup
                    (addRule (=> (instance ?X Dog) (attribute ?X Canine)))
                    (assert (instance MyDog Dog)))
                  (action (wait (assertionExists (attribute MyDog Canine)))) ; Wait for inference to happen
                  (expected
                    (expectedAssertionExists (attribute MyDog Canine)))
                  (teardown
                    (retract (BY_KIF (instance MyDog Dog)))
                    (retract (BY_KIF (attribute MyDog Canine)))
                    (removeRuleForm (=> (instance ?X Dog) (attribute ?X Canine)))))
                """);
    }

    @Test
    void retractAssertionByKif() {
        runKifTest("""
                (test "Retract Assertion BY_KIF"
                  (setup (assert (instance TempFact Something)))
                  (action
                    (retract (BY_KIF (instance TempFact Something)))
                    (wait (assertionDoesNotExist (instance TempFact Something))))
                  (expected
                    (expectedAssertionDoesNotExist (instance TempFact Something)))
                  (teardown))
                """);
    }

    @Test
    void kbSizeCheck() {
        runKifTest("""
                (test "KB Size Check"
                  (setup
                    (assert (fact1 a))
                    (assert (fact2 b))
                  )
                  (action (assert (fact3 c)))
                  (expected (expectedKbSize 3))
                  (teardown
                    (retract (BY_KIF (fact1 a)))
                    (retract (BY_KIF (fact2 b)))
                    (retract (BY_KIF (fact3 c)))) ; Use BY_KIF for retracting assertions added by assert
                  )
                """);
    }

    @Test
    void runLogMessageTool() {
        // Need to register the tool manually for the test Cog instance
        cog.tools.register(new Tool() {
            @Override
            public String name() {
                return "log_message2";
            }

            @Override
            public String description() {
                return "Logs a message";
            }

            @Override
            public CompletableFuture<Object> execute(Map<String, Object> params) {
                var message = (String) params.get("message");
                System.out.println("TOOL LOG: " + message);
                return CompletableFuture.completedFuture("Message logged.");
            }
        });

        runKifTest("""
                (test "Run LogMessageTool"
                  (setup)
                  (action (runTool (params (name "log_message2") (message "Hello from test!"))))
                  (expected (expectedToolResult "Message logged."))
                  (teardown))
                """);
    }

    @Test
    void runGetNoteTextTool() {
        // Need to register the tool manually
        cog.tools.register(new Tool() {
            @Override
            public String name() {
                return "get_note_text2";
            }

            @Override
            public String description() {
                return "Gets note text";
            }

            @Override
            public CompletableFuture<Object> execute(Map<String, Object> params) {
                var noteId = (String) params.get("note_id");
                return CompletableFuture.completedFuture(cog.note(noteId).map(n -> (Object) n.text).orElse("Note not found"));
            }
        });

        // Add a dummy note for the tool to read
        cog.addNote(new Note("note-dummy-for-tool", "Dummy Note", "; Define your tests here using the (test ...) format", Note.Status.IDLE));

        runKifTest("""
                (test "Run GetNoteTextTool"
                  (setup)
                  (action (runTool (params (name "get_note_text2") (note_id "note-dummy-for-tool"))))
                  (expected (expectedToolResultContains "; Define your tests here using the (test ...) format"))
                  (teardown))
                """);
    }

    @Test
    void waitTimeoutExpectedFailure() {
        // This test is expected to fail due to the wait timing out.
        // We use assertThrows to verify that the action execution throws an exception.
        assertThrows(RuntimeException.class, () -> runKifTest("""
                (test "Wait Timeout (Expected Failure)"
                  (setup)
                  (action (wait (assertionExists (this_will_never_exist)) (params (timeout 1)))) ; Wait for 1 second
                  (expected (expectedAssertionExists (this_will_never_exist))) ; This expectation should fail *if* the action didn't throw first
                  (teardown))
                """), "The wait action was expected to time out and throw an exception.");
        // Note: The expectation check won't run because the action execution throws.
        // The failure is the exception from the action itself.
    }

    @Test
    void waitSuccess() {
        runKifTest("""
                (test "Wait Success"
                  (setup)
                  (action
                    (assert (tempFact ToBeWaitedFor))
                    (wait (assertionExists (tempFact ToBeWaitedFor)) (params (timeout 5))) ; Wait for 5 seconds max
                  )
                  (expected (expectedAssertionExists (tempFact ToBeWaitedFor))) ; Expect it to exist after waiting
                  (teardown (retract (BY_KIF (tempFact ToBeWaitedFor))))
                )
                """);
    }

    @Test
    void multipleExpectationFailures() {
        // This test is expected to fail because some expectations will not be met.
        // We use assertThrows to verify that the expectation checking throws an exception.
        assertThrows(AssertionFailedError.class, () -> runKifTest("""
                (test "Multiple Expectation Failures"
                  (setup (assert (fact A)))
                  (action (assert (fact B)))
                  (expected
                    (expectedAssertionExists (fact A)) ; PASS
                    (expectedAssertionExists (fact B)) ; PASS
                    (expectedAssertionExists (fact C)) ; FAIL
                    (expectedAssertionDoesNotExist (fact A)) ; FAIL
                    (expectedKbSize 10) ; FAIL
                  )
                  (teardown
                    (retract (BY_KIF (fact A)))
                    (retract (BY_KIF (fact B)))
                  )
                )
                """), "Multiple expectations were expected to fail.");
    }

    @Test
    void queryWithVariableInPredicate() {
        runKifTest("""
                (test "Query with Variable in Predicate"
                  (setup
                    (assert (isA Dog Animal))
                    (assert (isA Cat Animal)))
                  (action (query (?Rel Dog Animal)))
                  (expected
                    (expectedResult true)
                    (expectedBindings (((?Rel isA))))) ; Corrected KIF for expectedBindings
                  (teardown
                    (retract (BY_KIF (isA Dog Animal)))
                    (retract (BY_KIF (isA Cat Animal))))) ; Corrected KIF syntax
                """);
    }

    @Test
    void queryWithMultipleVariables() {
        runKifTest("""
                (test "Query with Multiple Variables"
                  (setup
                    (assert (parent John Jane))
                    (assert (parent Jane Jim))
                    (assert (parent John Jill)))
                  (action (query (parent ?Child ?Parent)))
                  (expected
                    (expectedResult true)
                    (expectedBindings (((?Child John) (?Parent Jane)) ((?Child Jane) (?Parent Jim)) ((?Child John) (?Parent Jill))))) ; Corrected KIF for expectedBindings
                  (teardown
                    (retract (BY_KIF (parent John Jane)))
                    (retract (BY_KIF (parent Jane Jim)))
                    (retract (BY_KIF (parent John Jill)))))
                """);
    }

    @Test
    void queryWithNestedStructure() {
        runKifTest("""
                (test "Query with Nested Structure"
                  (setup
                    (assert (hasProperty (color Red) Apple))
                    (assert (hasProperty (color Green) Apple)))
                  (action (query (hasProperty (color ?C) Apple)))
                  (expected
                    (expectedResult true)
                    (expectedBindings (((?C Red)) ((?C Green))))) ; Corrected KIF for expectedBindings
                  (teardown
                    (retract (BY_KIF (hasProperty (color Red) Apple)))
                    (retract (BY_KIF (hasProperty (color Green) Apple)))))
                """);
    }

    @Test
    void queryWithList() {
        runKifTest("""
                (test "Query with List"
                  (setup
                    (assert (items (1 2 3)))
                    (assert (items (A B C))))
                  (action (query (items (?X ?Y ?Z))))
                  (expected
                    (expectedResult true)
                    (expectedBindings (((?X 1) (?Y 2) (?Z 3)) ((?X A) (?Y B) (?Z C))))) ; Corrected KIF for expectedBindings
                  (teardown
                    (retract (BY_KIF (items (1 2 3))))
                    (retract (BY_KIF (items (A B C))))))
                """);
    }

    @Test
    void queryWithEmptyList() {
        runKifTest("""
                (test "Query with Empty List"
                  (setup
                    (assert (emptyList ())))
                  (action (query (emptyList ?L)))
                  (expected
                    (expectedResult true)
                    (expectedBindings (((?L ())))) ) ; Corrected KIF for expectedBindings
                  (teardown
                    (retract (BY_KIF (emptyList ())))))
                """);
    }

    @Test
    void queryWithPartialListMatch() {
        runKifTest("""
                (test "Query with Partial List Match"
                  (setup
                    (assert (sequence (a b c d)))
                    (assert (sequence (x y z))))
                  (action (query (sequence (a b ?Rest))))
                  (expected
                    (expectedResult true)
                    (expectedBindings (((?Rest (c d))))) ) ; Corrected KIF for expectedBindings
                  (teardown
                    (retract (BY_KIF (sequence (a b c d))))
                    (retract (BY_KIF (sequence (x y z))))))
                """);
    }

    @Test
    void testWithMissingActionSection() {
        // This test should fail because the 'action' section is missing.
        assertThrows(AssertionFailedError.class, () -> runKifTest("""
                (test "Test with Missing Action Section"
                  (setup (assert (fact A)))
                  (expected (expectedAssertionExists (fact A)))
                  (teardown (retract (BY_KIF (fact A)))))
                """), "Test was expected to fail due to missing action section.");
    }

    @Test
    void testWithInvalidActionTerms() {
        // This test should fail during parsing of the action section.
        assertThrows(AssertionFailedError.class, () -> runKifTest("""
                (test "Test with Invalid Action Terms"
                  (setup (assert (fact A)))
                  (action
                    (assert (fact B)) ; Valid
                    (invalidActionType (arg1 arg2)) ; Invalid action type - Parsing Error
                    (assert) ; Invalid assert payload size - Parsing Error
                    (runTool (params name "log_message")) ; Invalid runTool params format - Parsing Error
                    (query "not a list") ; Invalid query payload type - Parsing Error
                  )
                  (expected
                    (expectedAssertionExists (fact A)) ; PASS (from setup)
                    (expectedAssertionExists (fact B)) ; PASS (from valid action)
                  )
                  (teardown
                    (retract (BY_KIF (fact A)))
                    (retract (BY_KIF (fact B)))
                  )
                )
                """), "Test was expected to fail due to invalid action terms.");
    }

    // --- Tests for Test Framework Error Conditions (Now JUnit Failures) ---

    @Test
    void testWithInvalidExpectationTerms() {
        // This test should fail during parsing of the expectation section.
        assertThrows(AssertionFailedError.class, () -> runKifTest("""
                (test "Test with Invalid Expectation Terms"
                  (setup (assert (fact A)))
                  (action (assert (fact B)))
                  (expected
                    (expectedAssertionExists (fact A)) ; Valid
                    (invalidExpectationType (arg1 arg2)) ; Invalid expectation type - Parsing Error
                    (expectedResult) ; Invalid expectedResult payload size - Parsing Error
                    (expectedBindings "not a list") ; Invalid expectedBindings payload type - Parsing Error
                    (expectedKbSize (not an integer)) ; Invalid expectedKbSize value - Parsing Error
                  )
                  (teardown
                    (retract (BY_KIF (fact A)))
                    (retract (BY_KIF (fact B)))
                  )
                )
                """), "Test was expected to fail due to invalid expectation terms.");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "(assert \"not a list\")", // Invalid payload type
            "(addRule \"not a list\")", // Invalid payload type
            "(removeRuleForm \"not a list\")", // Invalid payload type
            "(retract \"not a list\")", // Invalid payload type
            "(retract (BY_KIF))", // Invalid target list size
            "(retract (123 (fact A)))", // Invalid type (not atom)
            "(retract (UNKNOWN_TYPE (fact A)))", // Invalid type value
            "(runTool (params name \"log_message\"))", // Invalid runTool params format (missing value)
            "(runTool (params (message \"hi\")))", // Missing name param
            "(query \"not a list\")", // Invalid query payload type
            "(query (a) (params name))", // Invalid params format
            "(query (a) (params (query_type \"BAD_TYPE\")))", // Invalid query_type value
            "(wait \"not a list\")", // Invalid payload type
            "(wait (assertionExists))", // Invalid condition list size
            "(wait (unknownCondition (fact A)))", // Unknown condition type
            "(wait (assertionExists (fact A)) (params (timeout -5)))", // Invalid timeout value
            "(wait (assertionExists (fact A)) (params (timeout \"abc\")))" // Invalid timeout type
    })
    void actionErrorParsing(String actionKif) {
        // These actions should fail during the parsing phase within runKifTest
        assertThrows(AssertionFailedError.class, () -> {
            runKifTest(String.format("""
                    (test "Action Error Parsing: %s"
                      (setup)
                      (action %s)
                      (expected (expectedResult false)) ; This expectation won't be checked
                      (teardown))
                    """, actionKif.replace("\"", "\\\""), actionKif)); // Escape quotes for string literal
        }, "Action '" + actionKif + "' was expected to fail during parsing.");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "(retract (BY_KIF AtomValue))", // Invalid target type for BY_KIF (expects list)
            "(retract (BY_ID (fact A)))", // Invalid target type for BY_ID (expects atom)
            "(runTool (params (name \"nonexistent_tool\")))" // Tool not found
    })
    void actionErrorExecution(String actionKif) {
        // These actions should parse correctly but fail during execution.
        // The failure should be a RuntimeException from executeSingleAction.
        assertThrows(RuntimeException.class, () -> {
            runKifTest(String.format("""
                    (test "Action Error Execution: %s"
                      (setup)
                      (action %s)
                      (expected (expectedResult false)) ; This expectation won't be checked
                      (teardown))
                    """, actionKif.replace("\"", "\\\""), actionKif)); // Escape quotes for string literal
        }, "Action '" + actionKif + "' was expected to fail during execution.");
    }

    // --- Action Execution Error Tests (Now JUnit Failures) ---

    @Test
    void expectationErrorExpectedBindingsOnNonQuery() {
        // This test should fail because expectedBindings is used on a non-query action result.
        assertThrows(AssertionFailedError.class, () -> runKifTest("""
                (test "Expectation Error: ExpectedBindings on NonQuery"
                  (setup (assert (fact A)))
                  (action (assert (fact B))) ; Not a query
                  (expected
                    (expectedAssertionExists (fact A)) ; PASS
                    (expectedBindings (((?X A)))) ; FAIL - actionResult is not a Query Answer
                  )
                  (teardown
                    (retract (BY_KIF (fact A)))
                    (retract (BY_KIF (fact B)))
                  )
                )
                """), "Expectation 'expectedBindings' was expected to fail on non-query result.");
    }

    @Test
    void expectationErrorExpectedToolResultOnNonTool() {
        // This test should fail because expectedToolResult is used on a non-tool action result.
        assertThrows(AssertionFailedError.class, () -> runKifTest("""
                (test "Expectation Error: ExpectedToolResult on NonTool"
                  (setup (assert (fact A)))
                  (action (query (fact ?X))) ; Not a tool run (result is Cog.Answer)
                  (expected
                    (expectedAssertionExists (fact A)) ; PASS
                    (expectedToolResult "abc") ; FAIL - actionResult is not a String
                  )
                  (teardown
                    (retract (BY_KIF (fact A)))
                  )
                )
                """), "Expectation 'expectedToolResult' was expected to fail on non-tool result.");
    }


    // --- Expectation Check Error Tests (Now JUnit Failures) ---

    @ParameterizedTest
    @ValueSource(strings = {
            "(expectedResult \"maybe\")", // Invalid boolean string
            "(expectedBindings \"not a list\")", // Not a list
            "(expectedBindings ((?X)))", // Pair size != 2
            "(expectedAssertionExists \"not a list\")", // Not a KIF list
            "(expectedAssertionDoesNotExist \"not a list\")", // Not a KIF list
            "(expectedRuleExists \"not a list\")", // Not a KIF list
            "(expectedRuleDoesNotExist \"not a list\")", // Not a KIF list
            "(expectedKbSize \"big\")", // Not an integer string
            "(expectedToolResultContains 123)" // Not a string
    })
    void expectationErrorParsing(String expectationKif) {
        // These expectations should fail during the parsing phase within runKifTest
        assertThrows(AssertionFailedError.class, () -> {
            runKifTest(String.format("""
                    (test "Expectation Error Parsing: %s"
                      (setup)
                      (action (query (a))) ; Provide a query action so expectedResult/Bindings parsing is attempted
                      (expected %s)
                      (teardown))
                    """, expectationKif.replace("\"", "\\\""), expectationKif)); // Escape quotes for string literal
        }, "Expectation '" + expectationKif + "' was expected to fail during parsing.");
    }

}
