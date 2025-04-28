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
                  (expected (expectedResult true) (expectedBindings (((?X MyCat)))))
                  (teardown (retract (BY_KIF (instance MyCat Cat)))))
                """);
    }

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
                    (expectedBindings (((?X MyCat)) ((?X YourCat)))))
                  (teardown
                    (retract (BY_KIF (instance MyCat Cat)))
                    (retract (BY_KIF (instance YourCat Cat)))
                    (retract (BY_KIF (instance MyDog Dog)))))
                """);
    }

    @Test
    void queryFailure() {
        runKifTest("""
                (test "Query Failure"
                  (setup (assert (instance MyDog Dog)))
                  (action (query (instance MyDog Cat)))
                  (expected (expectedResult false) (expectedBindings ()))
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
                  (action (wait (assertionExists (attribute MyDog Canine))))
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
                    (retract (BY_KIF (fact3 c))))
                  )
                """);
    }

    @Test
    void runLogMessageTool() {
        cog.tools.add(new Tool() {
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

        cog.addNote(new Note("note-dummy-for-tool", "Dummy Note", "; Define your tests here using the (test ...) format", Note.Status.IDLE));

        runKifTest("""
                (test "Run LogMessageTool"
                  (setup)
                  (action (runTool (params (name "log_message2") (message "Hello from test!"))))
                  (expected (expectedToolResultContains "; Define your tests here using the (test ...) format"))
                  (teardown))
                """);
    }

    @Test
    void runGetNoteTextTool() {
        cog.tools.add(new Tool() {
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
        assertThrows(RuntimeException.class, () -> runKifTest("""
                (test "Wait Timeout (Expected Failure)"
                  (setup)
                  (action (wait (assertionExists (this_will_never_exist)) (params (timeout 1))))
                  (expected (expectedAssertionExists (this_will_never_exist)))
                  (teardown))
                """), "The wait action was expected to time out and throw an exception.");
    }

    @Test
    void waitSuccess() {
        runKifTest("""
                (test "Wait Success"
                  (setup)
                  (action
                    (assert (tempFact ToBeWaitedFor))
                    (wait (assertionExists (tempFact ToBeWaitedFor)) (params (timeout 5)))
                  )
                  (expected (expectedAssertionExists (tempFact ToBeWaitedFor)))
                  (teardown (retract (BY_KIF (tempFact ToBeWaitedFor))))
                )
                """);
    }

    @Test
    void multipleExpectationFailures() {
        assertThrows(AssertionFailedError.class, () -> runKifTest("""
                (test "Multiple Expectation Failures"
                  (setup (assert (fact A)))
                  (action (assert (fact B)))
                  (expected
                    (expectedAssertionExists (fact A))
                    (expectedAssertionExists (fact B))
                    (expectedAssertionExists (fact C))
                    (expectedAssertionDoesNotExist (fact A))
                    (expectedKbSize 10)
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
                    (expectedBindings (((?Rel isA)))))
                  (teardown
                    (retract (BY_KIF (isA Dog Animal)))
                    (retract (BY_KIF (isA Cat Animal)))))
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
                    (expectedBindings (((?Child John) (?Parent Jane)) ((?Child Jane) (?Parent Jim)) ((?Child John) (?Parent Jill)))))
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
                    (expectedBindings (((?C Red)) ((?C Green)))))
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
                    (expectedBindings (((?X 1) (?Y 2) (?Z 3)) ((?X A) (?Y B) (?Z C)))))
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
                    (expectedBindings (((?L ())))) )
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
                    (expectedBindings (((?Rest (c d))))) )
                  (teardown
                    (retract (BY_KIF (sequence (a b c d))))
                    (retract (BY_KIF (sequence (x y z))))))
                """);
    }

    @Test
    void testWithMissingActionSection() {
        assertThrows(AssertionFailedError.class, () -> runKifTest("""
                (test "Test with Missing Action Section"
                  (setup (assert (fact A)))
                  (expected (expectedAssertionExists (fact A)))
                  (teardown (retract (BY_KIF (fact A)))))
                """), "Test was expected to fail due to missing action section.");
    }

    @Test
    void testWithInvalidActionTerms() {
        assertThrows(AssertionFailedError.class, () -> runKifTest("""
                (test "Test with Invalid Action Terms"
                  (setup (assert (fact A)))
                  (action
                    (assert (fact B))
                    (invalidActionType (arg1 arg2))
                    (assert)
                    (runTool (params name "log_message"))
                    (query "not a list")
                  )
                  (expected
                    (expectedAssertionExists (fact A))
                    (expectedAssertionExists (fact B))
                  )
                  (teardown
                    (retract (BY_KIF (fact A)))
                    (retract (BY_KIF (fact B)))
                  )
                )
                """), "Test was expected to fail due to invalid action terms.");
    }

    @Test
    void testWithInvalidExpectationTerms() {
        assertThrows(AssertionFailedError.class, () -> runKifTest("""
                (test "Test with Invalid Expectation Terms"
                  (setup (assert (fact A)))
                  (action (assert (fact B)))
                  (expected
                    (expectedAssertionExists (fact A))
                    (invalidExpectationType (arg1 arg2))
                    (expectedResult)
                    (expectedBindings "not a list")
                    (expectedKbSize (not an integer))
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
            "(assert \"not a list\")",
            "(addRule \"not a list\")",
            "(removeRuleForm \"not a list\")",
            "(retract \"not a list\")",
            "(retract (BY_KIF))",
            "(retract (123 (fact A)))",
            "(retract (UNKNOWN_TYPE (fact A)))",
            "(runTool (params name \"log_message\"))",
            "(runTool (params (message \"hi\")))",
            "(query \"not a list\")",
            "(query (a) (params name))",
            "(query (a) (params (query_type \"BAD_TYPE\")))",
            "(wait \"not a list\")",
            "(wait (assertionExists))",
            "(wait (unknownCondition (fact A)))",
            "(wait (assertionExists (fact A)) (params (timeout -5)))",
            "(wait (assertionExists (fact A)) (params (timeout \"abc\")))"
    })
    void actionErrorParsing(String actionKif) {
        assertThrows(AssertionFailedError.class, () ->
                        runKifTest(String.format("""
                                (test "Action Error Parsing: %s"
                                  (setup)
                                  (action %s)
                                  (expected (expectedResult false))
                                  (teardown))
                                """, actionKif.replace("\"", "\\\""), actionKif)),
                "Action '" + actionKif + "' was expected to fail during parsing.");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "(retract (BY_KIF AtomValue))",
            "(retract (BY_ID (fact A)))",
            "(runTool (params (name \"nonexistent_tool\")))"
    })
    void actionErrorExecution(String actionKif) {
        assertThrows(RuntimeException.class, () ->
                        runKifTest(String.format("""
                                (test "Action Error Execution: %s"
                                  (setup)
                                  (action %s)
                                  (expected (expectedResult false))
                                  (teardown))
                                """, actionKif.replace("\"", "\\\""), actionKif)),
                "Action '" + actionKif + "' was expected to fail during execution.");
    }

    @Test
    void expectationErrorExpectedBindingsOnNonQuery() {
        assertThrows(AssertionFailedError.class, () -> runKifTest("""
                (test "Expectation Error: ExpectedBindings on NonQuery"
                  (setup (assert (fact A)))
                  (action (assert (fact B)))
                  (expected
                    (expectedAssertionExists (fact A))
                    (expectedBindings (((?X A))))
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
        assertThrows(AssertionFailedError.class, () -> runKifTest("""
                (test "Expectation Error: ExpectedToolResult on NonTool"
                  (setup (assert (fact A)))
                  (action (query (fact ?X)))
                  (expected
                    (expectedAssertionExists (fact A))
                    (expectedToolResult "abc")
                  )
                  (teardown
                    (retract (BY_KIF (fact A)))
                  )
                )
                """), "Expectation 'expectedToolResult' was expected to fail on non-tool result.");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "(expectedResult \"maybe\")",
            "(expectedBindings \"not a list\")",
            "(expectedBindings ((?X)))",
            "(expectedAssertionExists \"not a list\")",
            "(expectedAssertionDoesNotExist \"not a list\")",
            "(expectedRuleExists \"not a list\")",
            "(expectedRuleDoesNotExist \"not a list\")",
            "(expectedKbSize \"big\")",
            "(expectedToolResultContains 123)"
    })
    void expectationErrorParsing(String expectationKif) {
        assertThrows(AssertionFailedError.class, () ->
                        runKifTest(String.format("""
                                (test "Expectation Error Parsing: %s"
                                  (setup)
                                  (action (query (a)))
                                  (expected %s)
                                  (teardown))
                                """, expectationKif.replace("\"", "\\\""), expectationKif)),
                "Expectation '" + expectationKif + "' was expected to fail during parsing.");
    }
}
