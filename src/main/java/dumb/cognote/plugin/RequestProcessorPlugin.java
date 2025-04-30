package dumb.cognote.plugin;

import dumb.cognote.*;
import dumb.cognote.Term.Var;
import dumb.cognote.util.Events;
import dumb.cognote.util.Json;
import dumb.cognote.util.KifParser;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static dumb.cognote.Protocol.*;
import static dumb.cognote.Term.Atom;
import static dumb.cognote.Term.Lst;
import static dumb.cognote.Term.Var.of;
import static dumb.cognote.util.Log.error;
import static dumb.cognote.util.Log.message;
import static java.util.Optional.ofNullable;

public class RequestProcessorPlugin extends Plugin.BasePlugin {

    private static final Lst ADD_NOTE_PATTERN = new Lst(Atom.of(PRED_REQUEST), new Lst(Atom.of("REQUEST_ADD_NOTE"), new Lst(Atom.of("title"), of("?title")), new Lst(Atom.of("text"), of("?text"))));
    private static final Lst REMOVE_NOTE_PATTERN = new Lst(Atom.of(PRED_REQUEST), new Lst(Atom.of("REQUEST_REMOVE_NOTE"), new Lst(Atom.of("noteId"), of("?noteId"))));
    private static final Lst START_NOTE_PATTERN = new Lst(Atom.of(PRED_REQUEST), new Lst(Atom.of("REQUEST_START_NOTE"), new Lst(Atom.of("noteId"), of("?noteId"))));
    private static final Lst PAUSE_NOTE_PATTERN = new Lst(Atom.of(PRED_REQUEST), new Lst(Atom.of("REQUEST_PAUSE_NOTE"), new Lst(Atom.of("noteId"), of("?noteId"))));
    private static final Lst COMPLETE_NOTE_PATTERN = new Lst(Atom.of(PRED_REQUEST), new Lst(Atom.of("REQUEST_COMPLETE_NOTE"), new Lst(Atom.of("noteId"), of("?noteId"))));
    private static final Lst RUN_TOOL_PATTERN = new Lst(Atom.of(PRED_REQUEST), new Lst(Atom.of("REQUEST_RUN_TOOL"), new Lst(Atom.of("toolName"), of("?toolName")), new Lst(Atom.of("parameters"), of("?parameters"))));
    private static final Lst RUN_QUERY_PATTERN = new Lst(Atom.of(PRED_REQUEST), new Lst(Atom.of("REQUEST_RUN_QUERY"), new Lst(Atom.of("queryType"), of("?queryType")), new Lst(Atom.of("patternString"), of("?patternString")), new Lst(Atom.of("targetKbId"), of("?targetKbId")), new Lst(Atom.of("parameters"), of("?parameters"))));
    private static final Lst CLEAR_ALL_PATTERN = new Lst(Atom.of(PRED_REQUEST), new Lst(Atom.of("REQUEST_CLEAR_ALL")));
    private static final Lst SET_CONFIG_PATTERN = new Lst(Atom.of(PRED_REQUEST), new Lst(Atom.of("REQUEST_SET_CONFIG"), new Lst(Atom.of("configJsonText"), of("?configJsonText"))));
    private static final Lst SAVE_NOTES_PATTERN = new Lst(Atom.of(PRED_REQUEST), new Lst(Atom.of("REQUEST_SAVE_NOTES")));
    private static final Lst GET_INITIAL_STATE_PATTERN = new Lst(Atom.of(PRED_REQUEST), new Lst(Atom.of("REQUEST_GET_INITIAL_STATE")));


    @Override
    public void start(Events ev, Cognition ctx) {
        super.start(ev, ctx);
        events.on(Event.AssertedEvent.class, this::handleAssertionAdded);
        log("RequestProcessorPlugin started.");
    }

    private void handleAssertionAdded(Event.AssertedEvent event) {
        var assertion = event.assertion();
        if (!assertion.kb().equals(KB_CLIENT_INPUT) || !assertion.isActive()) {
            return;
        }

        var kif = assertion.kif();
        if (!(kif instanceof Lst requestList) || requestList.terms.isEmpty() || requestList.op().filter(PRED_REQUEST::equals).isEmpty()) {
            return; // Not a (request ...) assertion
        }

        // Attempt to match against known request patterns
        matchAndProcess(requestList, ADD_NOTE_PATTERN, this::processAddNoteRequest);
        matchAndProcess(requestList, REMOVE_NOTE_PATTERN, this::processRemoveNoteRequest);
        matchAndProcess(requestList, START_NOTE_PATTERN, this::processStartNoteRequest);
        matchAndProcess(requestList, PAUSE_NOTE_PATTERN, this::processPauseNoteRequest);
        matchAndProcess(requestList, COMPLETE_NOTE_PATTERN, this::processCompleteNoteRequest);
        matchAndProcess(requestList, RUN_TOOL_PATTERN, this::processRunToolRequest);
        matchAndProcess(requestList, RUN_QUERY_PATTERN, this::processRunQueryRequest);
        matchAndProcess(requestList, CLEAR_ALL_PATTERN, this::processClearAllRequest);
        matchAndProcess(requestList, SET_CONFIG_PATTERN, this::processSetConfigRequest);
        matchAndProcess(requestList, SAVE_NOTES_PATTERN, this::processSaveNotesRequest);
        matchAndProcess(requestList, GET_INITIAL_STATE_PATTERN, this::processGetInitialStateRequest);

        // Note: If no pattern matches, it's an unknown request type.
        // We could add a default handler here if needed, but for now, unhandled requests are just logged implicitly.
    }

    private void matchAndProcess(Lst requestKif, Lst pattern, java.util.function.Consumer<Map<Var, Term>> processor) {
        ofNullable(Logic.Unifier.match(pattern, requestKif, Map.of()))
                .ifPresent(processor);
    }

    private void processAddNoteRequest(Map<Var, Term> bindings) {
        var titleTerm = bindings.get(of("?title"));
        var textTerm = bindings.get(of("?text"));

        if (!(titleTerm instanceof Atom(var title)) || title.isBlank()) {
            assertError("AddNote request failed: Missing or invalid 'title' parameter.");
            return;
        }
        var text = textTerm instanceof Atom(var t) ? t : "";

        var newNote = new Note(Cog.id(Cog.ID_PREFIX_NOTE), title, text, Note.Status.IDLE);
        cog.addNote(newNote);
        message("Processed AddNote request: " + title);
    }

    private void processRemoveNoteRequest(Map<Var, Term> bindings) {
        var noteIdTerm = bindings.get(of("?noteId"));

        if (!(noteIdTerm instanceof Atom(var noteId)) || noteId.isBlank()) {
            assertError("RemoveNote request failed: Missing or invalid 'noteId' parameter.");
            return;
        }

        cog.removeNote(noteId);
        message("Processed RemoveNote request for ID: " + noteId);
    }

    private void processStartNoteRequest(Map<Var, Term> bindings) {
        var noteIdTerm = bindings.get(of("?noteId"));

        if (!(noteIdTerm instanceof Atom(var noteId)) || noteId.isBlank()) {
            assertError("StartNote request failed: Missing or invalid 'noteId' parameter.");
            return;
        }

        cog.startNote(noteId);
        message("Processed StartNote request for ID: " + noteId);
    }

    private void processPauseNoteRequest(Map<Var, Term> bindings) {
        var noteIdTerm = bindings.get(of("?noteId"));

        if (!(noteIdTerm instanceof Atom(var noteId)) || noteId.isBlank()) {
            assertError("PauseNote request failed: Missing or invalid 'noteId' parameter.");
            return;
        }

        cog.pauseNote(noteId);
        message("Processed PauseNote request for ID: " + noteId);
    }

    private void processCompleteNoteRequest(Map<Var, Term> bindings) {
        var noteIdTerm = bindings.get(of("?noteId"));

        if (!(noteIdTerm instanceof Atom(var noteId)) || noteId.isBlank()) {
            assertError("CompleteNote request failed: Missing or invalid 'noteId' parameter.");
            return;
        }

        cog.completeNote(noteId);
        message("Processed CompleteNote request for ID: " + noteId);
    }

    private void processRunToolRequest(Map<Var, Term> bindings) {
        var toolNameTerm = bindings.get(of("?toolName"));
        var parametersTerm = bindings.get(of("?parameters"));

        if (!(toolNameTerm instanceof Atom(var toolName)) || toolName.isBlank()) {
            assertError("RunTool request failed: Missing or invalid 'toolName' parameter.");
            return;
        }

        Map<String, Object> paramMap = Map.of();
        if (parametersTerm instanceof Lst paramsList && paramsList.op().filter("params"::equals).isPresent()) {
            try {
                paramMap = paramsList.terms.stream().skip(1)
                        .filter(t -> t instanceof Lst pair && pair.size() == 2 && pair.get(0) instanceof Atom)
                        .collect(Collectors.toMap(
                                t -> ((Atom) ((Lst) t).get(0)).value(),
                                t -> termToObject(((Lst) t).get(1))
                        ));
            } catch (Exception e) {
                assertError("RunTool request failed: Invalid 'parameters' format. Error: " + e.getMessage());
                return;
            }
        } else if (parametersTerm != null) {
            assertError("RunTool request failed: Invalid 'parameters' format. Expected (params (...)).");
            return;
        }

        Map<String, Object> p = paramMap;
        cog.tools.get(toolName).ifPresentOrElse(tool -> {
            tool.execute(p).whenCompleteAsync((result, ex) -> {
                if (ex != null) {
                    error("Error executing tool '" + toolName + "': " + ex.getMessage());
                    ex.printStackTrace();
                    assertError("Tool execution failed for '" + toolName + "': " + ex.getMessage());
                } else {
                    message("Tool '" + toolName + "' executed successfully.");
                }
            }, cog.events.exe);

            message("Processed RunTool request for tool: " + toolName);

        }, () -> assertError("RunTool request failed: Tool not found: " + toolName));
    }

    private void processRunQueryRequest(Map<Var, Term> bindings) {
        var queryTypeTerm = bindings.get(of("?queryType"));
        var patternStringTerm = bindings.get(of("?patternString"));
        var targetKbIdTerm = bindings.get(of("?targetKbId"));
        var parametersTerm = bindings.get(of("?parameters"));

        if (!(queryTypeTerm instanceof Atom(
                var queryTypeStr
        )) || queryTypeStr.isBlank() || !(patternStringTerm instanceof Atom(
                var patternString
        )) || patternString.isBlank()) {
            assertError("RunQuery request failed: Missing or invalid 'queryType' or 'patternString' parameters.");
            return;
        }

        var targetKbId = targetKbIdTerm instanceof Atom(var kbId) ? kbId : null;

        Map<String, Object> paramMap = Map.of();
        if (parametersTerm instanceof Lst paramsList && paramsList.op().filter("params"::equals).isPresent()) {
            try {
                paramMap = paramsList.terms.stream().skip(1)
                        .filter(t -> t instanceof Lst pair && pair.size() == 2 && pair.get(0) instanceof Atom)
                        .collect(Collectors.toMap(
                                t -> ((Atom) ((Lst) t).get(0)).value(),
                                t -> termToObject(((Lst) t).get(1))
                        ));
            } catch (Exception e) {
                assertError("RunQuery request failed: Invalid 'parameters' format. Error: " + e.getMessage());
                return;
            }
        } else if (parametersTerm != null) {
            assertError("RunQuery request failed: Invalid 'parameters' format. Expected (params (...)).");
            return;
        }


        try {
            var queryType = Cog.QueryType.valueOf(queryTypeStr.toUpperCase());
            var terms = KifParser.parseKif(patternString);
            if (terms.size() != 1 || !(terms.getFirst() instanceof Lst pattern)) {
                throw new KifParser.ParseException("Query pattern must be a single KIF list.");
            }

            var queryId = Cog.id(Cog.ID_PREFIX_QUERY);
            var query = new Query(queryId, queryType, pattern, targetKbId, paramMap);

            cog.events.emit(new Query.QueryEvent(query));
            message("Processed RunQuery request: Submitted query " + queryId);

        } catch (IllegalArgumentException e) {
            assertError("RunQuery request failed: Invalid queryType or parameters: " + e.getMessage());
        } catch (KifParser.ParseException e) {
            assertError("RunQuery request failed: Failed to parse query pattern KIF: " + e.getMessage());
        } catch (Exception e) {
            error("Error processing RunQuery request: " + e.getMessage());
            e.printStackTrace();
            assertError("Error processing RunQuery request: " + e.getMessage());
        }
    }

    private void processClearAllRequest(Map<Var, Term> bindings) {
        cog.clear();
        message("Processed ClearAll request.");
    }

    private void processSetConfigRequest(Map<Var, Term> bindings) {
        var configJsonTextTerm = bindings.get(of("?configJsonText"));

        if (!(configJsonTextTerm instanceof Atom(var configJsonText)) || configJsonText.isBlank()) {
            assertError("SetConfig request failed: Missing or invalid 'configJsonText' parameter.");
            return;
        }

        if (cog.updateConfig(configJsonText)) {
            message("Processed SetConfig request.");
        } else {
            assertError("SetConfig request failed: Failed to apply configuration.");
        }
    }

    private void processSaveNotesRequest(Map<Var, Term> bindings) {
        cog.save();
        message("Processed SaveNotes request.");
    }

    private void processGetInitialStateRequest(Map<Var, Term> bindings) {
        logWarning("Received GetInitialState request via KIF assertion. This request type is usually handled directly by the WebSocket connection.");
    }


    private Object termToObject(Term term) {
        return switch (term) {
            case Atom atom -> {
                var v = atom.value();
                try {
                    yield Integer.parseInt(v);
                } catch (NumberFormatException e1) {
                    try {
                        yield Double.parseDouble(v);
                    } catch (NumberFormatException e2) {
                        if (v.equalsIgnoreCase("true")) yield true;
                        if (v.equalsIgnoreCase("false")) yield false;
                        yield v;
                    }
                }
            }
            case Lst list -> list;
            case Var var -> var;
        };
    }

    private void assertError(String message) {
        var uiActionDataNode = Json.node().put("message", message);
        var uiActionDataString = Json.str(uiActionDataNode);

        var uiActionTerm = new Lst(
                Atom.of(PRED_UI_ACTION),
                Atom.of(UI_ACTION_DISPLAY_MESSAGE),
                Atom.of(uiActionDataString)
        );

        context.tryCommit(new Assertion.PotentialAssertion(
                uiActionTerm,
                Cog.INPUT_ASSERTION_BASE_PRIORITY,
                Set.of(),
                id,
                false, false, false,
                KB_UI_ACTIONS,
                Logic.AssertionType.GROUND,
                List.of(),
                0
        ), id);
        error("RequestProcessorPlugin Error: " + message);
    }
}
