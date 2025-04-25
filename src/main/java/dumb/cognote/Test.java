package dumb.cognote;

import javax.swing.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static dumb.cognote.Cog.TEST_RESULTS_NOTE_ID;

public class Test {

    public static void main(String[] args) {
        String rulesFile = null;

        for (var i = 0; i < args.length; i++) {
            try {
                switch (args[i]) {
                    case "-r", "--rules" -> rulesFile = args[++i];
                    default -> System.err.println("Warning: Unknown option: " + args[i] + ". Config via UI/JSON.");
                }
            } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
                System.err.printf("Error parsing argument for %s: %s%n", (i > 0 ? args[i - 1] : args[i]), e.getMessage());
                System.exit(1);
            }
        }

        try {
            var c = new CogNote(); // CogNote loads notes and config in constructor
            c.start(); // Initialize Cog components, but keep paused

            if (rulesFile != null) {
                // Load rules after Cog is initialized but before UI is fully ready/unpaused
                // Rules are processed via events, which are handled by the executor
                // The executor is running even when paused, but reasoning might be limited
                // This is acceptable for loading static rules.
                c.loadRules(rulesFile);
            }

            System.out.println("Running tests from command line...");
            c.setPaused(false); // Unpause the system to allow plugins/reasoners to run
            CompletableFuture<Void> testsCompleteFuture = new CompletableFuture<>();

            // Listen for the "Tests Complete" status update
            c.events.on(Cog.SystemStatusEvent.class, statusEvent -> {
                if ("Tests Complete".equals(statusEvent.statusMessage())) {
                    testsCompleteFuture.complete(null);
                } else if ("Tests Failed".equals(statusEvent.statusMessage())) {
                    testsCompleteFuture.completeExceptionally(new RuntimeException("Tests failed."));
                }
            });

            // Emit the event to trigger the TestRunnerPlugin
            c.events.emit(new Cog.RunTestsEvent());

            try {
                // Wait for the tests to complete (or timeout)
                testsCompleteFuture.get(120, TimeUnit.SECONDS); // Adjust timeout as needed

                // Print results from the Test Results note
                c.note(TEST_RESULTS_NOTE_ID).ifPresent(note -> System.out.println("\n" + note.text));

                System.out.println("Command-line test run finished.");
                System.exit(0); // Exit successfully after tests
            } catch (Exception e) {
                System.err.println("Error during command-line test execution: " + e.getMessage());
                e.printStackTrace();
                System.exit(1); // Exit with error code
            } finally {
                c.stop(); // Ensure system is stopped
            }

        } catch (Exception e) {
            System.err.println("Initialization/Startup failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

}
