package dumb.cognote;

import dumb.cognote.plugin.TestPlugin;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static dumb.cognote.Cog.*;
import static dumb.cognote.Cog.TEST_RESULTS_NOTE_ID;
import static dumb.cognote.Cog.TEST_RESULTS_NOTE_TITLE;
import static dumb.cognote.Note.Status.IDLE;

public class Test {

    public static void main(String[] args) throws IOException {

        var c = new CogNote() { @Deprecated @Override public void save() { } };

        c.start();

        c.loadRules("/home/me/sumo/Merge.kif");

        c.addNote(new Note(TEST_DEFINITIONS_NOTE_ID, TEST_DEFINITIONS_NOTE_TITLE, TESTS, IDLE));
        c.addNote(new Note(TEST_RESULTS_NOTE_ID, TEST_RESULTS_NOTE_TITLE, "; Test results: pending", IDLE));

        c.setPaused(false); // Unpause

        var completion = new CompletableFuture<Void>();

        c.events.on(SystemStatusEvent.class, s -> {
            switch (s.statusMessage()) {
                case "Tests Complete" -> completion.complete(null);
                case "Tests Failed" -> completion.completeExceptionally(new RuntimeException("Tests failed."));
            }
        });

        // trigger the TestRunnerPlugin
        c.events.emit(new TestPlugin.RunTestsEvent());

        try {
            // Wait for the tests to complete (or timeout)
            completion.get(120, TimeUnit.SECONDS); // Adjust timeout as needed

            c.note(TEST_RESULTS_NOTE_ID).ifPresent(note -> System.out.println("\n" + note.text));
        } catch (Exception e) {
            System.err.println("Test error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            c.stop();
        }
    }


    private static final String TESTS = """
            ; Define tests here using the (test ...) format:
            
            ; Test structure: (test "Test Name" (setup ...) (action ...) (expected ...) (teardown ...))
            ; setup/teardown actions: (assert KIF), (addRule RuleKIF), (retract (BY_ID "id")), (retract (BY_KIF KIF)), (removeRuleForm RuleKIF)
            ; action types: (query Pattern), (runTool (name "tool_name") (params (key1 value1) ...)), (wait (condition) (params (timeout seconds) (interval millis)))
            ; wait conditions: (assertionExists KIF), (assertionDoesNotExist KIF)
            ; expected types: (expectedResult boolean), (expectedBindings ((?V1 Val1) ...)), (expectedAssertionExists KIF), (expectedAssertionDoesNotExist KIF), (expectedRuleExists RuleKIF), (expectedRuleDoesNotExist RuleKIF), (expectedKbSize integer), (expectedToolResult value))
            
            ; Example 1: Simple Fact Query
            (test "Simple Fact Query"\s
              (setup (assert (instance MyCat Cat)))
              (action (query (instance ?X Cat)))
              (expected (expectedResult true) (expectedBindings ((?X MyCat))))
              (teardown (retract (BY_KIF (instance MyCat Cat)))))
            
            ; Example 2: Query with Multiple Bindings
            (test "Query with Multiple Bindings"\s
              (setup\s
                (assert (instance MyCat Cat))
                (assert (instance YourCat Cat))
                (assert (instance MyDog Dog)))
              (action (query (instance ?X Cat)))
              (expected\s
                (expectedResult true)
                ; Note: Order of bindings in expectedBindings list does NOT matter now (compares sets)
                (expectedBindings ((?X MyCat) (?X YourCat))))
              (teardown\s
                (retract (BY_KIF (instance MyCat Cat)))
                (retract (BY_KIF (instance YourCat Cat)))
                (retract (BY_KIF (instance MyDog Dog)))))
            
            ; Example 3: Query that should fail
            (test "Query Failure"\s
              (setup (assert (instance MyDog Dog)))
              (action (query (instance MyDog Cat)))
              (expected (expectedResult false) (expectedBindings ())))
              (teardown (retract (BY_KIF (instance MyDog Dog))))
            
            ; Example 4: Test Forward Chaining Rule
            (test "Forward Chaining Rule"\s
              (setup\s
                (addRule (=> (instance ?X Dog) (attribute ?X Canine)))
                (assert (instance MyDog Dog)))
              (action (query (attribute MyDog Canine)))
              (expected\s
                (expectedResult true)
                ; Query for a fact with no variables should return SUCCESS with one empty binding set (( ))
                (expectedBindings (( )))
                (expectedAssertionExists (attribute MyDog Canine)))
              (teardown\s
                (retract (BY_KIF (instance MyDog Dog)))
                (retract (BY_KIF (attribute MyDog Canine)))
                (removeRuleForm (=> (instance ?X Dog) (attribute ?X Canine)))))
            
            ; Example 5: Test Retraction (requires waiting for async completion)
            (test "Retract Assertion"\s
              (setup (assert (instance TempFact Something)))
              (action (retract (BY_KIF (instance TempFact Something))))
              (expected
                ; Wait for the assertion to disappear after the async retract request
                (wait (assertionDoesNotExist (instance TempFact Something)))
                (expectedAssertionDoesNotExist (instance TempFact Something)))
              (teardown))
            
            ; Example 6: Test KB Size
            (test "KB Size Check"\s
              (setup\s
                (assert (fact1 a))
                (assert (fact2 b)))
              (action (assert (fact3 c)))
              (expected (expectedKbSize 3))
              (teardown\s
                (retract (BY_KIF (fact1 a)))
                (retract (BY_KIF (fact2 b)))
                (retract (BY_KIF (fact3 c)))))
            
            ; Example 7: Test runTool (LogMessageTool)
            (test "Run LogMessageTool"\s
              (setup)
              (action (runTool log_message (params (message "Hello from test!"))))
              (expected (expectedToolResult "Message logged."))
              (teardown))
            
            ; Example 8: Test runTool (GetNoteTextTool) - requires a note to exist
            ; This test assumes the Test Definitions note itself exists and has text.
            ; It runs the tool against the Test Definitions note KB.
            (test "Run GetNoteTextTool"\s
              (setup)
              (action (runTool get_note_text (params (note_id "note-test-definitions"))))
              (expected (expectedToolResult "; Define your tests here using the (test ...) format"))
              (teardown))
                                            
        """;

    /** TODO use */
    public static final String queryTests = """
            ; Example: Test a simple fact query
            (test "Simple Fact Query" (query (instance MyCat Cat)) (expectedResult true))
            
            ; Example: Test a query with bindings
            ; Note: Order of bindings in expectedBindings list matters for now
            (test "Query with Bindings" (query (instance ?X Cat)) (expectedBindings ((?X MyCat) (?X YourCat))))
            
            ; Example: Test a query that should fail
            (test "Query Failure" (query (instance MyDog Cat)) (expectedResult false))
            
            ; Example: Test a query with no matches
            (test "Query No Matches" (query (instance ?Y Bird)) (expectedBindings ()))

        """;


}
