package dumb.cognote;

import dumb.cognote.plugin.TestPlugin;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static dumb.cognote.Cog.*;
import static dumb.cognote.Note.Status.IDLE;

public class Test {

    private static String finalResultsText = "; Test results: pending"; // Store results here

    public static void main(String[] args) throws IOException {

        // Use a minimal CogNote implementation for testing that doesn't rely on UI or file saving
        var c = new CogNote() {
            // Override save to do nothing for tests
            @Deprecated @Override public void save() { }

        };

        c.start();

        //c.loadRules("/home/me/sumo/Merge.kif"); // Keep commented out for isolated tests

        c.addNote(new Note(TEST_DEFINITIONS_NOTE_ID, TEST_DEFINITIONS_NOTE_TITLE, TESTS, IDLE));
        c.addNote(new Note(TEST_RESULTS_NOTE_ID, TEST_RESULTS_NOTE_TITLE, "; Test results: pending", IDLE));

        c.setPaused(false);

        var completion = new CompletableFuture<Void>();

        // Listen for the new TestRunCompleteEvent
        c.events.on(TestPlugin.TestRunCompleteEvent.class, event -> {
            System.out.println("Test.java: Received TestRunCompleteEvent."); // Added logging
            finalResultsText = event.resultsText(); // Store the results
            completion.complete(null); // Signal completion to the main thread
        });

        System.out.println("Test.java: Emitting RunTestsEvent..."); // Added logging
        c.events.emit(new TestPlugin.RunTestsEvent());

        try {
            // Wait for the TestRunCompleteEvent to be received
            // Increased timeout significantly to 120 seconds to allow for more tests and potential delays
            completion.get(5, TimeUnit.SECONDS);
            System.out.println("Test.java: Completion future completed."); // Added logging

            // Print the stored detailed test results
            System.out.println("\n--- Final Test Results ---");
            System.out.println(finalResultsText);
            System.out.println("--------------------------");


            // Check the results text content to determine overall success/failure
            // Look for the summary line "Failed: X" where X is greater than 0
            if (finalResultsText.contains("\nFailed: ") && !finalResultsText.contains("\nFailed: 0\n")) {
                throw new RuntimeException("Some tests failed.");
            }

        } catch (TimeoutException e) {
            System.err.println("Test runner error: Timeout waiting for tests to complete.");
            // Attempt to print current results note content on timeout
            c.note(TEST_RESULTS_NOTE_ID).ifPresent(note -> {
                System.err.println("\n--- Current Test Results (on Timeout) ---");
                System.err.println(note.text);
                System.err.println("-----------------------------------------");
            });
            e.printStackTrace();
            System.exit(1); // Exit with non-zero status on failure
        } catch (Exception e) {
            System.err.println("Test runner error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1); // Exit with non-zero status on failure
        } finally {
            System.out.println("Test.java: Stopping system..."); // Added logging
            c.stop();
            System.out.println("Test.java: System stopped."); // Added logging
        }
    }

    private static final String TESTS = """
            ; Define your tests here using the (test ...) format
            ; Each test should have a unique name (an Atom).
            ; Sections: (setup ...), (action ...), (expected ...), (teardown ...)
            ; Only (action ...) is mandatory.

            ; --- Fundamental Logic/Querying Tests ---

            (test "Simple Fact Query"\s
              (setup (assert (instance MyCat Cat)))
              (action (query (instance ?X Cat)))
              (expected (expectedResult true) (expectedBindings ((?X MyCat))))
              (teardown (retract (BY_KIF (instance MyCat Cat)))))

            (test "Query with Multiple Bindings"
              (setup
                (assert (instance MyCat Cat))
                (assert (instance YourCat Cat))
                (assert (instance MyDog Dog)))
              (action (query (instance ?X Cat)))
              (expected
                (expectedResult true)
                (expectedBindings ((?X MyCat) (?X YourCat)))) ; Note: Order of bindings in result list is not guaranteed, but the set of bindings should match.
              (teardown
                (retract (BY_KIF (instance MyCat Cat)))
                (retract (BY_KIF (instance YourCat Cat)))
                (retract (BY_KIF (instance MyDog Dog)))))

            (test "Query Failure"
              (setup (assert (instance MyDog Dog)))
              (action (query (instance MyDog Cat)))
              (expected (expectedResult false) (expectedBindings ()))) ; Expected empty bindings list
              (teardown (retract (BY_KIF (instance MyDog Dog))))

            (test "Forward Chaining Rule"
              (setup\s
                (addRule (=> (instance ?X Dog) (attribute ?X Canine)))
                (assert (instance MyDog Dog)))
              (action (wait (assertionExists (attribute MyDog Canine)))) ; Wait for inference to happen
              (expected
                (expectedAssertionExists (attribute MyDog Canine)))
              (teardown
                (retract (BY_KIF (instance MyDog Dog)))
                (retract (BY_KIF (attribute MyDog Canine)))
                (removeRuleForm (=> (instance ?X Dog) (attribute ?X Canine)))))

            (test "Retract Assertion BY_KIF"
              (setup (assert (instance TempFact Something)))
              (action
                (retract (BY_KIF (instance TempFact Something)))
                (wait (assertionDoesNotExist (instance TempFact Something))))
              (expected
                (expectedAssertionDoesNotExist (instance TempFact Something)))
              (teardown))

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

            (test "Run LogMessageTool"
              (setup)
              (action (runTool (params (name "log_message") (message "Hello from test!"))))
              (expected (expectedToolResult "Message logged."))
              (teardown))

            (test "Run GetNoteTextTool"
              (setup)
              (action (runTool (params (name "get_note_text") (note_id "note-test-definitions"))))
              (expected (expectedToolResultContains "; Define your tests here using the (test ...) format"))
              (teardown))

            (test "Wait Timeout (Expected Failure)"
              (setup)
              (action (wait (assertionExists (this_will_never_exist)) (params (timeout 1)))) ; Wait for 1 second
              (expected (expectedAssertionExists (this_will_never_exist))) ; This expectation should fail
              (teardown))

            (test "Wait Success"
              (setup)
              (action
                (assert (tempFact ToBeWaitedFor))
                (wait (assertionExists (tempFact ToBeWaitedFor)) (params (timeout 5))) ; Wait for 5 seconds max
              )
              (expected (expectedAssertionExists (tempFact ToBeWaitedFor))) ; Expect it to exist after waiting
              (teardown (retract (BY_KIF (tempFact ToBeWaitedFor))))
            )

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

            ; --- Added tests for basic logic/querying ---

            (test "Query with Variable in Predicate"
              (setup
                (assert (isA Dog Animal))
                (assert (isA Cat Animal)))
              (action (query (?Rel Dog Animal)))
              (expected
                (expectedResult true)
                (expectedBindings ((?Rel isA))))
              (teardown
                (retract (BY_KIF (isA Dog Animal)))
                (retract (BY_KIF (isA Cat Animal)))))

            (test "Query with Multiple Variables"
              (setup
                (assert (parent John Jane))
                (assert (parent Jane Jim))
                (assert (parent John Jill)))
              (action (query (parent ?Child ?Parent)))
              (expected
                (expectedResult true)
                (expectedBindings ((?Child John) (?Parent Jane)) ((?Child Jane) (?Parent Jim)) ((?Child John) (?Parent Jill))))
              (teardown
                (retract (BY_KIF (parent John Jane)))
                (retract (BY_KIF (parent Jane Jim)))
                (retract (BY_KIF (parent John Jill)))))

            (test "Query with Nested Structure"
              (setup
                (assert (hasProperty (color Red) Apple))
                (assert (hasProperty (color Green) Apple)))
              (action (query (hasProperty (color ?C) Apple)))
              (expected
                (expectedResult true)
                (expectedBindings ((?C Red)) ((?C Green))))
              (teardown
                (retract (BY_KIF (hasProperty (color Red) Apple)))
                (retract (BY_KIF (hasProperty (color Green) Apple)))))

            (test "Query with List"
              (setup
                (assert (items (1 2 3)))
                (assert (items (A B C))))
              (action (query (items (?X ?Y ?Z))))
              (expected
                (expectedResult true)
                (expectedBindings ((?X 1) (?Y 2) (?Z 3)) ((?X A) (?Y B) (?Z C))))
              (teardown
                (retract (BY_KIF (items (1 2 3))))
                (retract (BY_KIF (items (A B C))))))

            (test "Query with Empty List"
              (setup
                (assert (emptyList ())))
              (action (query (emptyList ?L)))
              (expected
                (expectedResult true)
                (expectedBindings ((?L ()))))
              (teardown
                (retract (BY_KIF (emptyList ())))))

            (test "Query with Partial List Match"
              (setup
                (assert (sequence (a b c d)))
                (assert (sequence (x y z))))
              (action (query (sequence (a b ?Rest))))
              (expected
                (expectedResult true)
                (expectedBindings ((?Rest (c d)))))
              (teardown
                (retract (BY_KIF (sequence (a b c d))))
                (retract (BY_KIF (sequence (x y z))))))

            ; --- Tests for Test Framework Error Conditions ---

            ; Test: Action section is empty or missing (should skip the test unless parsing errors exist)
            (test "Test with Missing Action Section"
              (setup (assert (fact A)))
              (expected (expectedAssertionExists (fact A)))
              (teardown (retract (BY_KIF (fact A))))) ; This test should be skipped and not appear in results

            ; Test: Test definition has invalid structure (should be skipped by top-level parser)
            (test "Test with Invalid Top-Level Structure"
              (this is not a test definition)
              (test "Another Valid Test" (action (query (a)))) ; This one should run
              (expected (expectedResult false))
              (teardown)
            ) ; The first term should cause a parse error reported by handleRunTests, the second should run.

            ; Test: Action section contains invalid terms (should skip invalid terms, report parsing errors)
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
                ; The invalid actions should result in parsing errors reported for this test,
                ; but not necessarily action execution errors if they were skipped during parsing.
                ; We expect this test to FAIL due to parsing errors.
              )
              (teardown
                (retract (BY_KIF (fact A)))
                (retract (BY_KIF (fact B)))
              )
            )

            ; Test: Expectation section contains invalid terms (should skip invalid terms, report parsing errors)
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

            ; Test: Action Execution - Invalid Payloads/Params leading to Execution Error
            (test "Action Error: Assert Bad Payload Type"
              (setup)
              (action (assert "not a list")) ; Invalid payload type - Parsing Error
              (expected (expectedResult false)) ; This expectation won't be checked if parsing fails. Test fails due to parsing error.
              (teardown))

            (test "Action Error: AddRule Bad Payload Type"
              (setup)
              (action (addRule "not a list")) ; Invalid payload type - Parsing Error
              (expected (expectedResult false)) ; Test fails due to parsing error.
              (teardown))

            (test "Action Error: RemoveRuleForm Bad Payload Type"
              (setup)
              (action (removeRuleForm "not a list")) ; Invalid payload type - Parsing Error
              (expected (expectedResult false)) ; Test fails due to parsing error.
              (teardown))

            (test "Action Error: Retract Bad Payload Type"
              (setup)
              (action (retract "not a list")) ; Invalid payload type - Parsing Error
              (expected (expectedResult false)) ; Test fails due to parsing error.
              (teardown))

            (test "Action Error: Retract Bad Target List Size"
              (setup)
              (action (retract (BY_KIF))) ; Invalid target list size - Parsing Error
              (expected (expectedResult false)) ; Test fails due to parsing error.
              (teardown))

            (test "Action Error: Retract Bad Type Atom"
              (setup)
              (action (retract (123 (fact A)))) ; Invalid type (not atom) - Parsing Error
              (expected (expectedResult false)) ; Test fails due to parsing error.
              (teardown))

            (test "Action Error: Retract Invalid Type Value"
              (setup)
              (action (retract (UNKNOWN_TYPE (fact A)))) ; Invalid type value - Parsing Error
              (expected (expectedResult false)) ; Test fails due to parsing error.
              (teardown))

            (test "Action Error: Retract BY_KIF Bad Target Type"
              (setup)
              (action (retract (BY_KIF AtomValue))) ; Invalid target type for BY_KIF - Execution Error
              (expected (expectedResult false)) ; Expect the action chain to fail
              (teardown))

             (test "Action Error: Retract BY_ID Bad Target Type"
              (setup)
              (action (retract (BY_ID (fact A)))) ; Invalid target type for BY_ID - Execution Error
              (expected (expectedResult false)) ; Expect the action chain to fail
              (teardown))


            (test "Action Error: RunTool No Name Param"
              (setup)
              (action (runTool (params (message "hi")))) ; Missing name param - Parsing Error
              (expected (expectedResult false)) ; Test fails due to parsing error.
              (teardown))

            (test "Action Error: RunTool Nonexistent Tool"
              (setup)
              (action (runTool (params (name "nonexistent_tool")))) ; Tool not found - Execution Error
              (expected (expectedResult false)) ; Expect the action chain to fail
              (teardown))

            (test "Action Error: Query Bad Params Format"
              (setup)
              (action (query (a) (params name))) ; Invalid params format - Parsing Error
              (expected (expectedResult false)) ; Test fails due to parsing error.
              (teardown))

            (test "Action Error: Query Invalid query_type Param"
              (setup)
              (action (query (a) (params (query_type "BAD_TYPE")))) ; Invalid query_type value - Parsing Error
              (expected (expectedResult false)) ; Test fails due to parsing error.
              (teardown))

            (test "Action Error: Wait Bad Payload Type"
              (setup)
              (action (wait "not a list")) ; Invalid payload type - Parsing Error
              (expected (expectedResult false)) ; Test fails due to parsing error.
              (teardown))

            (test "Action Error: Wait Bad Condition List Size"
              (setup)
              (action (wait (assertionExists))) ; Invalid condition list size - Parsing Error
              (expected (expectedResult false)) ; Test fails due to parsing error.
              (teardown))

            (test "Action Error: Wait Bad Condition Type"
              (setup)
              (action (wait (unknownCondition (fact A)))) ; Unknown condition type - Parsing Error
              (expected (expectedResult false)) ; Test fails due to parsing error.
              (teardown))

            (test "Action Error: Wait Bad Timeout Param Value"
              (setup)
              (action (wait (assertionExists (fact A)) (params (timeout -5)))) ; Invalid timeout value - Parsing Error
              (expected (expectedResult false)) ; Test fails due to parsing error.
              (teardown))

            (test "Action Error: Wait Bad Timeout Param Type"
              (setup)
              (action (wait (assertionExists (fact A)) (params (timeout "abc")))) ; Invalid timeout type - Parsing Error
              (expected (expectedResult false)) ; Test fails due to parsing error.
              (teardown))

            ; Test: Expectation Check - Wrong Result Type (e.g., expectedBindings on non-query action)
            (test "Expectation Error: ExpectedBindings on NonQuery"
              (setup (assert (fact A)))
              (action (assert (fact B))) ; Not a query
              (expected
                (expectedAssertionExists (fact A)) ; PASS
                (expectedBindings ((?X A))) ; FAIL - actionResult is not a Query Answer
              )
              (teardown
                (retract (BY_KIF (fact A)))
                (retract (BY_KIF (fact B)))
              )
            )

            ; Test: Expectation Check - Wrong Result Type (e.g., expectedToolResult on non-tool action)
            (test "Expectation Error: ExpectedToolResult on NonTool"
              (setup (assert (fact A)))
              (action (query (fact ?X))) ; Not a tool run
              (expected
                (expectedAssertionExists (fact A)) ; PASS
                (expectedToolResult "abc") ; FAIL - actionResult is not a String (it's a Cog.Answer)
              )
              (teardown
                (retract (BY_KIF (fact A)))
              )
            )

            ; Test: Expectation Check - Invalid Expected Value Format
            (test "Expectation Error: ExpectedResult Bad Value Type"
              (setup)
              (action (query (a)))
              (expected (expectedResult "maybe")) ; Invalid boolean string - Parsing Error
              (teardown))

            (test "Expectation Error: ExpectedBindings Bad Value Type"
              (setup)
              (action (query (a)))
              (expected (expectedBindings "not a list")) ; Not a list - Parsing Error
              (teardown))

            (test "Expectation Error: ExpectedBindings Bad Pair Format"
              (setup)
              (action (query (a)))
              (expected (expectedBindings ((?X)))) ; Pair size != 2 - Parsing Error
              (teardown))

            (test "Expectation Error: ExpectedAssertion Bad Value Type"
              (setup)
              (action (query (a)))
              (expected (expectedAssertionExists "not a list")) ; Not a KIF list - Parsing Error
              (teardown))

            (test "Expectation Error: ExpectedRule Bad Value Type"
              (setup)
              (action (query (a)))
              (expected (expectedRuleExists "not a list")) ; Not a KIF list - Parsing Error
              (teardown))

            (test "Expectation Error: ExpectedKbSize Bad Value Type"
              (setup)
              (action (query (a)))
              (expected (expectedKbSize "big")) ; Not an integer string - Parsing Error
              (teardown))

            (test "Expectation Error: ExpectedToolResultContains Bad Value Type"
              (setup)
              (action (runTool (params (name "log_message") (message "hi"))))
              (expected (expectedToolResultContains 123)) ; Not a string - Parsing Error
              (teardown))

        """;
}
