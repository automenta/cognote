package dumb.cognote;

import dumb.cognote.plugin.TestPlugin;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static dumb.cognote.Cog.*;
import static dumb.cognote.Note.Status.IDLE;

public class Test {

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

        c.events.on(SystemStatusEvent.class, s -> {
            // Check for the specific status messages indicating test completion
            if ("Tests Complete".equals(s.statusMessage())) {
                completion.complete(null);
            } else if ("Tests Failed".equals(s.statusMessage())) {
                // The plugin now reports "Tests Complete" even if tests failed,
                // but the results note will show failures. We rely on checking
                // the results note content after completion.
                // However, if the plugin reports "Tests Failed" due to a parse error,
                // we should also complete the future to unblock the main thread.
                 completion.complete(null); // Complete on any final test status
            }
        });

        c.events.emit(new TestPlugin.RunTestsEvent());

        try {
            // Wait for the test run to complete (either successfully or with parse errors)
            // Increased timeout to 15 seconds
            completion.get(15, TimeUnit.SECONDS);

            // Retrieve and print the detailed test results
            c.note(TEST_RESULTS_NOTE_ID).ifPresent(note -> System.out.println(note.text));

            // Check the results note content to determine overall success/failure
            var resultsNote = c.note(TEST_RESULTS_NOTE_ID);
            if (resultsNote.isPresent()) {
                String resultsText = resultsNote.get().text;
                // Simple check: look for "Failed: " followed by a number greater than 0
                if (resultsText.contains("\nFailed: ") && !resultsText.contains("\nFailed: 0\n")) {
                    throw new RuntimeException("Some tests failed.");
                }
            } else {
                 throw new RuntimeException("Test results note not found after run.");
            }


        } catch (Exception e) {
            System.err.println("Test runner error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1); // Exit with non-zero status on failure
        } finally {
            c.stop();
        }
    }

    private static final String TESTS = """
            ; Define your tests here using the (test ...) format
            ; Each test should have a unique name (an Atom).
            ; Sections: (setup ...), (action ...), (expected ...), (teardown ...)
            ; Only (action ...) is mandatory.

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

            (test "Retract Assertion"
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

        """;
}
