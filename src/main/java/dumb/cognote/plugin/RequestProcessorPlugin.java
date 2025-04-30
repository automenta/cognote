//package dumb.cognote.plugin;
//
//import dumb.cognote.*;
//import dumb.cognote.util.Events;
//import dumb.cognote.util.Json;
//
//import java.util.List;
//import java.util.Set;
//
//import static dumb.cognote.Protocol.*;
//import static dumb.cognote.Term.Atom;
//import static dumb.cognote.Term.Lst;
//import static dumb.cognote.util.Log.error;
//
//public class RequestProcessorPlugin extends Plugin.BasePlugin {
//
//    // Deprecated patterns for client-originated requests via KIF assertion
//    // Keeping them commented out for reference during transition
//    /*
//    private static final Lst ADD_NOTE_PATTERN = new Lst(Atom.of(PRED_REQUEST), new Lst(Atom.of("REQUEST_ADD_NOTE"), new Lst(Atom.of("title"), of("?title")), new Lst(Atom.of("text"), of("?text"))));
//    private static final Lst REMOVE_NOTE_PATTERN = new Lst(Atom.of(PRED_REQUEST), new Lst(Atom.of("REQUEST_REMOVE_NOTE"), new Lst(Atom.of("noteId"), of("?noteId"))));
//    private static final Lst START_NOTE_PATTERN = new Lst(Atom.of(PRED_REQUEST), new Lst(Atom.of("REQUEST_START_NOTE"), new Lst(Atom.of("noteId"), of("?noteId"))));
//    private static final Lst PAUSE_NOTE_PATTERN = new Lst(Atom.of(PRED_REQUEST), new Lst(Atom.of("REQUEST_PAUSE_NOTE"), new Lst(Atom.of("noteId"), of("?noteId"))));
//    private static final Lst COMPLETE_NOTE_PATTERN = new Lst(Atom.of(PRED_REQUEST), new Lst(Atom.of("REQUEST_COMPLETE_NOTE"), new Lst(Atom.of("noteId"), of("?noteId"))));
//    private static final Lst RUN_TOOL_PATTERN = new Lst(Atom.of(PRED_REQUEST), new Lst(Atom.of("REQUEST_RUN_TOOL"), new Lst(Atom.of("toolName"), of("?toolName")), new Lst(Atom.of("parameters"), of("?parameters"))));
//    private static final Lst RUN_QUERY_PATTERN = new Lst(Atom.of(PRED_REQUEST), new Lst(Atom.of("REQUEST_RUN_QUERY"), new Lst(Atom.of("queryType"), of("?queryType")), new Lst(Atom.of("patternString"), of("?patternString")), new Lst(Atom.of("targetKbId"), of("?targetKbId")), new Lst(Atom.of("parameters"), of("?parameters"))));
//    private static final Lst CLEAR_ALL_PATTERN = new Lst(Atom.of(PRED_REQUEST), new Lst(Atom.of("REQUEST_CLEAR_ALL")));
//    private static final Lst SET_CONFIG_PATTERN = new Lst(Atom.of(PRED_REQUEST), new Lst(Atom.of("REQUEST_SET_CONFIG"), new Lst(Atom.of("configJsonText"), of("?configJsonText"))));
//    private static final Lst SAVE_NOTES_PATTERN = new Lst(Atom.of(PRED_REQUEST), new Lst(Atom.of("REQUEST_SAVE_NOTES")));
//    private static final Lst GET_INITIAL_STATE_PATTERN = new Lst(Atom.of(PRED_REQUEST), new Lst(Atom.of("REQUEST_GET_INITIAL_STATE")));
//    */
//
//
//    @Override
//    public void start(Events ev, Cognition ctx) {
//        super.start(ev, ctx);
//        // The primary role of processing client requests from KB_CLIENT_INPUT is moved
//        // to the WebSocketPlugin's direct command handling.
//        // This plugin might still be used for other internal request processing if needed.
//        // For now, we remove the listener for client input assertions.
//        // events.on(Event.AssertedEvent.class, this::handleAssertionAdded);
//        log("RequestProcessorPlugin started (client request handling via KIF assertion deprecated).");
//    }
//
//    // Removed handleAssertionAdded and all process*Request methods
//    // The logic is moved to WebSocketPlugin.handleRequest
//
//    // Keeping assertError for now, as backend plugins might still use KB_UI_ACTIONS
//    // for backend-initiated UI messages. This might be refactored later.
//    private void assertError(String message) {
//        var uiActionDataNode = Json.node().put("message", message);
//        var uiActionDataString = Json.str(uiActionDataNode);
//
//        var uiActionTerm = new Lst(
//                Atom.of(PRED_UI_ACTION),
//                Atom.of(UI_ACTION_DISPLAY_MESSAGE),
//                Atom.of(uiActionDataString)
//        );
//
//        context.tryCommit(new Assertion.PotentialAssertion(
//                uiActionTerm,
//                Cog.INPUT_ASSERTION_BASE_PRIORITY,
//                Set.of(),
//                id,
//                false, false, false,
//                KB_UI_ACTIONS,
//                Logic.AssertionType.GROUND,
//                List.of(),
//                0
//        ), id);
//        error("RequestProcessorPlugin Error: " + message);
//    }
//}
