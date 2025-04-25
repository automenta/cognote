package dumb.cognote;

import dumb.cognote.plugin.TestPlugin;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static dumb.cognote.Cog.*;
import static dumb.cognote.Note.Status.IDLE;

public class Test {

    public static void main(String[] args) throws IOException {

        var c = new CogNote() { @Deprecated @Override public void save() { } };

        c.start();

        c.loadRules("/home/me/sumo/Merge.kif");

        c.addNote(new Note(TEST_DEFINITIONS_NOTE_ID, TEST_DEFINITIONS_NOTE_TITLE, TESTS, IDLE));
        c.addNote(new Note(TEST_RESULTS_NOTE_ID, TEST_RESULTS_NOTE_TITLE, "; Test results: pending", IDLE));

        c.setPaused(false);

        var completion = new CompletableFuture<Void>();

        c.events.on(SystemStatusEvent.class, s -> {
            switch (s.statusMessage()) {
                case "Tests Complete" -> completion.complete(null);
                case "Tests Failed" -> completion.completeExceptionally(new RuntimeException("Tests failed."));
            }
        });

        c.events.emit(new TestPlugin.RunTestsEvent());

        try {
            completion.get(120, TimeUnit.SECONDS);

            c.note(TEST_RESULTS_NOTE_ID).ifPresent(note -> System.out.println(note.text));
        } catch (Exception e) {
            System.err.println("Test error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            c.stop();
        }
    }

    private static final String TESTS = """
            (test "Simple Fact Query"\s
              (setup (assert (instance MyCat Cat)))
              (action (query (instance ?X Cat)))
              (expected (expectedResult true) (expectedBindings ((?X MyCat))))
              (teardown (retract (BY_KIF (instance MyCat Cat)))))
            
            (test "Query with Multiple Bindings"\s
              (setup\s
                (assert (instance MyCat Cat))
                (assert (instance YourCat Cat))
                (assert (instance MyDog Dog)))
              (action (query (instance ?X Cat)))
              (expected\s
                (expectedResult true)
                (expectedBindings ((?X MyCat) (?X YourCat))))
              (teardown\s
                (retract (BY_KIF (instance MyCat Cat)))
                (retract (BY_KIF (instance YourCat Cat)))
                (retract (BY_KIF (instance MyDog Dog)))))
            
            (test "Query Failure"\s
              (setup (assert (instance MyDog Dog)))
              (action (query (instance MyDog Cat)))
              (expected (expectedResult false) (expectedBindings ())))
              (teardown (retract (BY_KIF (instance MyDog Dog))))
            
            (test "Forward Chaining Rule"\s
              (setup\s
                (addRule (=> (instance ?X Dog) (attribute ?X Canine)))
                (assert (instance MyDog Dog)))
              (action (query (attribute MyDog Canine)))
              (expected\s
                (expectedResult true)
                (expectedBindings (()))
                (expectedAssertionExists (attribute MyDog Canine)))
              (teardown\s
                (retract (BY_KIF (instance MyDog Dog)))
                (retract (BY_KIF (attribute MyDog Canine)))
                (removeRuleForm (=> (instance ?X Dog) (attribute ?X Canine)))))
            
            (test "Retract Assertion"\s
              (setup (assert (instance TempFact Something)))
              (action
                (retract (BY_KIF (instance TempFact Something)))
                (wait (assertionDoesNotExist (instance TempFact Something))))
              (expected
                (expectedAssertionDoesNotExist (instance TempFact Something)))
              (teardown))
            
            (test "KB Size Check"\s
              (setup\s
                (assert (fact1 a))
                (assert (fact2 b)))
              (action (assert (fact3 c)))
              (expected (expectedKbSize 3))
              (teardown\s
                (retract (BY_KIF (fact1 a)))
                (retract (BY_KIF (fact2 b)))
                (retract (fact3 c)))))
            
            (test "Run LogMessageTool"\s
              (setup)
              (action (runTool (params (name "log_message") (message "Hello from test!"))))
              (expected (expectedToolResult "Message logged."))
              (teardown))
            
            (test "Run GetNoteTextTool"\s
              (setup)
              (action (runTool (params (name "get_note_text") (note_id "note-test-definitions"))))
              (expected (expectedToolResultContains "; Define your tests here using the (test ...) format"))
              (teardown))
                                            
        """;
}
