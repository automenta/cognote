package dumb.cognote.plugin;

import dumb.cognote.*;

import static dumb.cognote.Log.message;
import static dumb.cognote.Protocol.KB_USER_FEEDBACK;

/**
 * Plugin that listens for assertions in the user feedback KB and processes them.
 */
public class UserFeedbackPlugin extends Plugin.BasePlugin {

    private static final String PRED_USER_ASSERTED_KIF = "asserted", PRED_USER_EDITED_NOTE_TEXT = "edited_text", PRED_USER_EDITED_NOTE_TITLE = "asserted_title", PRED_USER_CLICKED = "clicked";

    @Override
    public void start(Events ev, Logic.Cognition ctx) {
        super.start(ev, ctx);
        // Listen for any assertion being added
        events.on(Event.AssertedEvent.class, this::handleAssertionAdded);
        log("UserFeedbackPlugin started.");
    }

    private void handleAssertionAdded(Event.AssertedEvent event) {
        var assertion = event.assertion();
        // Only process assertions in the user feedback KB
        if (!assertion.kb().equals(KB_USER_FEEDBACK) || !assertion.isActive()) {
            return;
        }

        var kif = assertion.kif();
        if (!(kif instanceof Term.Lst feedbackList) || feedbackList.terms.isEmpty() || feedbackList.op().isEmpty()) {
            logWarning("UserFeedbackPlugin: Ignoring invalid feedback assertion format: " + kif.toKif());
            return;
        }

        var feedbackType = feedbackList.op().get();

        // Process specific feedback types
        switch (feedbackType) {
            case PRED_USER_ASSERTED_KIF:
                // This is already handled by InputPlugin when the ExternalInputEvent is emitted by WebSocketPlugin.
                // Re-processing it here would cause a loop.
                // We could add additional logic here if needed, but for now, just acknowledge.
                if (feedbackList.size() == 3 && feedbackList.get(1) instanceof Term.Atom && feedbackList.get(2) instanceof Term.Atom(
                        var v
                )) {
                    message("UserFeedbackPlugin: Received user asserted KIF in note [" + v + "]: " + v);
                } else {
                    logWarning("UserFeedbackPlugin: Invalid format for userAssertedKif feedback: " + kif.toKif());
                }
                break;
            case PRED_USER_EDITED_NOTE_TEXT:
                // Already handled by WebSocketPlugin
                if (feedbackList.size() == 3 && feedbackList.get(1) instanceof Term.Atom(var value)) {
                    message("UserFeedbackPlugin: Received user edited note text for note [" + value + "]");
                } else {
                    logWarning("UserFeedbackPlugin: Invalid format for userEditedNoteText feedback: " + kif.toKif());
                }
                break;
            case PRED_USER_EDITED_NOTE_TITLE:
                // Already handled by WebSocketPlugin
                if (feedbackList.size() == 3 && feedbackList.get(1) instanceof Term.Atom(var value)) {
                    message("UserFeedbackPlugin: Received user edited note title for note [" + value + "]");
                } else {
                    logWarning("UserFeedbackPlugin: Invalid format for userEditedNoteTitle feedback: " + kif.toKif());
                }
                break;
            case PRED_USER_CLICKED:
                if (feedbackList.size() == 2 && feedbackList.get(1) instanceof Term.Atom(var value)) {
                    message("UserFeedbackPlugin: Received user click on element: " + value);
                    // Example backend action: Trigger a query or tool based on the clicked element ID
                    // This is where you'd add logic like:
                    // if ("button-summarize".equals(elementId)) {
                    //     cog().tools.get("summarize").ifPresent(tool -> tool.execute(Map.of("note_id", "current-note-id")));
                    // }
                } else {
                    logWarning("UserFeedbackPlugin: Invalid format for userClicked feedback: " + kif.toKif());
                }
                break;
            // Add handlers for other feedback types here
            default:
                logWarning("UserFeedbackPlugin: Received unhandled feedback type: " + feedbackType + " in assertion: " + kif.toKif());
                break;
        }
    }
}
