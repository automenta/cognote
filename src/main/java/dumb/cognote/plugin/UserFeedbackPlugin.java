package dumb.cognote.plugin;

import dumb.cognote.Cog;
import dumb.cognote.Events;
import dumb.cognote.Logic;
import dumb.cognote.Term;

import java.util.Map;

import static dumb.cognote.Log.message;
import static dumb.cognote.ProtocolConstants.*;

/**
 * Plugin that listens for assertions in the user feedback KB and processes them.
 */
public class UserFeedbackPlugin extends Plugin.BasePlugin {

    @Override
    public void start(Events ev, Logic.Cognition ctx) {
        super.start(ev, ctx);
        // Listen for any assertion being added
        events.on(Cog.AssertedEvent.class, this::handleAssertionAdded);
        log("UserFeedbackPlugin started.");
    }

    private void handleAssertionAdded(Cog.AssertedEvent event) {
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
                if (feedbackList.size() == 3 && feedbackList.get(1) instanceof Term.Atom noteIdAtom && feedbackList.get(2) instanceof Term.Atom kifStringAtom) {
                    message("UserFeedbackPlugin: Received user asserted KIF in note [" + noteIdAtom.value() + "]: " + kifStringAtom.value());
                } else {
                    logWarning("UserFeedbackPlugin: Invalid format for userAssertedKif feedback: " + kif.toKif());
                }
                break;
            case PRED_USER_EDITED_NOTE_TEXT:
                // Already handled by WebSocketPlugin
                if (feedbackList.size() == 3 && feedbackList.get(1) instanceof Term.Atom noteIdAtom) {
                    message("UserFeedbackPlugin: Received user edited note text for note [" + noteIdAtom.value() + "]");
                } else {
                    logWarning("UserFeedbackPlugin: Invalid format for userEditedNoteText feedback: " + kif.toKif());
                }
                break;
            case PRED_USER_EDITED_NOTE_TITLE:
                // Already handled by WebSocketPlugin
                if (feedbackList.size() == 3 && feedbackList.get(1) instanceof Term.Atom noteIdAtom) {
                    message("UserFeedbackPlugin: Received user edited note title for note [" + noteIdAtom.value() + "]");
                } else {
                    logWarning("UserFeedbackPlugin: Invalid format for userEditedNoteTitle feedback: " + kif.toKif());
                }
                break;
            case PRED_USER_CLICKED:
                if (feedbackList.size() == 2 && feedbackList.get(1) instanceof Term.Atom elementIdAtom) {
                    var elementId = elementIdAtom.value();
                    message("UserFeedbackPlugin: Received user click on element: " + elementId);
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
