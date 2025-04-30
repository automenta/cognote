package dumb.cognote.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import dumb.cognote.*;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.BiConsumer;

import static dumb.cognote.Cog.MAX_WS_PARSE_PREVIEW;
import static dumb.cognote.Log.error;
import static dumb.cognote.Log.message;
import static dumb.cognote.Protocol.*;

public class WebSocketPlugin extends Plugin.BasePlugin {

    private final InetSocketAddress address;
    private final Set<WebSocket> clients = new CopyOnWriteArraySet<>();
    private final Map<String, BiConsumer<WebSocket, JSONObject>> commandHandlers = new ConcurrentHashMap<>();
    private final Map<String, BiConsumer<WebSocket, JSONObject>> feedbackHandlers = new ConcurrentHashMap<>();
    private WebSocketServer server;

    public WebSocketPlugin(InetSocketAddress address, CogNote cog) {
        this.address = address;
        this.cog = cog; // Initialize cog here as well, though BasePlugin.start will also set it
        setupCommandHandlers();
        setupFeedbackHandlers();
    }

    private void setupCommandHandlers() {
        commandHandlers.put(COMMAND_ADD_NOTE, this::handleAddNoteCommand);
        commandHandlers.put(COMMAND_REMOVE_NOTE, this::handleRemoveNoteCommand);
        commandHandlers.put(COMMAND_START_NOTE, this::handleStartNoteCommand);
        commandHandlers.put(COMMAND_PAUSE_NOTE, this::handlePauseNoteCommand);
        commandHandlers.put(COMMAND_COMPLETE_NOTE, this::handleCompleteNoteCommand);
        commandHandlers.put(COMMAND_RUN_TOOL, this::handleRunToolCommand);
        commandHandlers.put(COMMAND_RUN_QUERY, this::handleRunQueryCommand);
        commandHandlers.put(COMMAND_CLEAR_ALL, this::handleClearAllCommand);
        commandHandlers.put(COMMAND_SET_CONFIG, this::handleSetConfigCommand);
        commandHandlers.put(COMMAND_GET_INITIAL_STATE, this::handleGetInitialStateCommand);
        commandHandlers.put(COMMAND_SAVE_NOTES, this::handleSaveNotesCommand);
    }

    private void setupFeedbackHandlers() {
        feedbackHandlers.put(FEEDBACK_USER_ASSERTED_KIF, this::handleUserAssertedKifFeedback);
        feedbackHandlers.put(FEEDBACK_USER_EDITED_NOTE_TEXT, this::handleUserEditedNoteTextFeedback);
        feedbackHandlers.put(FEEDBACK_USER_EDITED_NOTE_TITLE, this::handleUserEditedNoteTitleFeedback);
        feedbackHandlers.put(FEEDBACK_USER_CLICKED, this::handleUserClickedFeedback);
    }

    @Override
    public void start(Events e, Logic.Cognition ctx) {
        super.start(e, ctx); // This will also set the cog field
        server = new WebSocketServer(address) {
            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
                clients.add(conn);
                message("WebSocket client connected: " + conn.getRemoteSocketAddress());
                sendInitialState(conn);
            }

            @Override
            public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                clients.remove(conn);
                message("WebSocket client disconnected: " + conn.getRemoteSocketAddress() + " code: " + code + " reason: " + reason);
            }

            @Override
            public void onMessage(WebSocket conn, String message) {
                handleMessage(conn, message);
            }

            @Override
            public void onError(WebSocket conn, Exception ex) {
                error("WebSocket error: " + (conn != null ? conn.getRemoteSocketAddress() : "unknown") + " " + ex.getMessage());
                ex.printStackTrace();
                if (conn != null) clients.remove(conn);
            }

            @Override
            public void onStart() {
                message("WebSocket server started on port " + address.getPort());
            }
        };
        server.start();

        events.on(Cog.CogEvent.class, this::broadcastEvent);

        // Listen for UI Action assertions to broadcast them
        cog.events.on(new Term.Lst(Term.Atom.of(PRED_UI_ACTION), Term.Var.of("?type"), Term.Var.of("?data")), this::handleUiActionAssertion);
    }

    @Override
    public void stop() {
        if (server != null) {
            try {
                server.stop();
                message("WebSocket server stopped.");
            } catch (InterruptedException e) {
                error("Error stopping WebSocket server: " + e.getMessage());
                e.printStackTrace();
            }
        }
        clients.clear();
    }

    private void handleMessage(WebSocket conn, String message) {
        JSONObject signal;
        String id = null;
        String type;
        JSONObject payload;
        String inReplyToId;

        try {
            signal = new JSONObject(new JSONTokener(message));
            type = signal.optString("type");
            id = signal.optString("id");
            inReplyToId = signal.optString("inReplyToId", null);
            payload = signal.optJSONObject("payload");

            if (type == null || id == null || payload == null) {
                sendErrorResponse(conn, id, "Invalid signal format: missing type, id, or payload.");
                return;
            }

            switch (type) {
                case SIGNAL_TYPE_COMMAND -> handleCommand(conn, id, payload);
                case SIGNAL_TYPE_INTERACTION_FEEDBACK -> handleFeedback(conn, id, inReplyToId, payload);
                case SIGNAL_TYPE_DIALOGUE_RESPONSE -> handleDialogueResponse(conn, id, inReplyToId, payload);
                default -> sendErrorResponse(conn, id, "Unknown signal type: " + type);
            }

        } catch (org.json.JSONException e) {
            error("Failed to parse incoming WebSocket message as JSON: " + message.substring(0, Math.min(message.length(), MAX_WS_PARSE_PREVIEW)) + "... Error: " + e.getMessage());
            // If id is null, we can't send a response back related to the message
            if (id != null) {
                sendErrorResponse(conn, id, "Failed to parse JSON: " + e.getMessage());
            }
        } catch (Exception e) {
            error("Error handling incoming WebSocket message: " + e.getMessage());
            e.printStackTrace();
            if (id != null) {
                sendErrorResponse(conn, id, "Internal server error: " + e.getMessage());
            }
        }
    }

    private void handleCommand(WebSocket conn, String commandId, JSONObject payload) {
        var commandType = payload.optString("commandType");
        var parameters = payload.optJSONObject("parameters");

        if (commandType == null || parameters == null) {
            sendFailureResponse(conn, commandId, "Invalid command format: missing commandType or parameters.");
            return;
        }

        var handler = commandHandlers.get(commandType);
        if (handler != null) {
            try {
                handler.accept(conn, payload); // Handlers are responsible for sending response
            } catch (Exception e) {
                error("Error executing command '" + commandType + "': " + e.getMessage());
                e.printStackTrace();
                sendErrorResponse(conn, commandId, "Error executing command: " + e.getMessage());
            }
        } else {
            sendErrorResponse(conn, commandId, "Unknown command type: " + commandType);
        }
    }

    private void handleFeedback(WebSocket conn, String feedbackId, @Nullable String inReplyToId, JSONObject payload) {
        var feedbackType = payload.optString("feedbackType");
        var feedbackData = payload.optJSONObject("feedbackData");

        if (feedbackType == null || feedbackData == null) {
            sendFailureResponse(conn, feedbackId, "Invalid feedback format: missing feedbackType or feedbackData.");
            return;
        }

        var handler = feedbackHandlers.get(feedbackType);
        if (handler != null) {
            try {
                handler.accept(conn, payload); // Handlers are responsible for sending response
            } catch (Exception e) {
                error("Error processing feedback '" + feedbackType + "': " + e.getMessage());
                e.printStackTrace();
                sendErrorResponse(conn, feedbackId, "Error processing feedback: " + e.getMessage());
            }
        } else {
            sendErrorResponse(conn, feedbackId, "Unknown feedback type: " + feedbackType);
        }
    }

    private void handleDialogueResponse(WebSocket conn, String responseId, @Nullable String inReplyToId, JsonNode payload) {
        var dialogueId = payload.get("dialogueId").asText();
        var responseData = payload.get("responseData");

        if (dialogueId == null || responseData == null) {
            sendErrorResponse(conn, responseId, "Invalid dialogue response format: missing dialogueId or responseData.");
            return;
        }

        cog.dialogue.handleResponse(dialogueId, responseData)
                .ifPresentOrElse(
                        future -> sendSuccessResponse(conn, responseId, null, "Dialogue response processed."),
                        () -> sendErrorResponse(conn, responseId, "No pending dialogue request found for ID: " + dialogueId)
                );
    }


    private void broadcastEvent(Cog.CogEvent event) {
        // Don't broadcast LogMessageEvents back to clients, they are handled by the server's Log class
        if (event instanceof Events.LogMessageEvent) return;

        var signal = new JSONObject()
                .put("type", SIGNAL_TYPE_EVENT)
                .put("id", UUID.randomUUID().toString())
                .put("payload", event.toJson());
        broadcast(signal.toString());
    }

    private void broadcast(String message) {
        clients.forEach(client -> {
            if (client.isOpen()) client.send(message);
        });
    }

    private void sendInitialState(WebSocket conn) {
        var initialState = new JSONObject()
                .put("type", SIGNAL_TYPE_INITIAL_STATE)
                .put("id", UUID.randomUUID().toString())
                .put("payload", new JSONObject()
                        .put("systemStatus", new Cog.SystemStatusEvent(cog.status, cog.context.kbCount(), cog.context.kbTotalCapacity(), cog.lm.activeLlmTasks.size(), cog.context.ruleCount()).toJson())
                        .put("configuration", new CogNote.Configuration(cog).toJson())
                        .put("notes", new JSONArray(cog.getAllNotes().stream().map(Note::toJson).toList()))
                        .put("assertions", new JSONArray(cog.context.truth.getAllActiveAssertions().stream().map(Assertion::toJson).toList()))
                        .put("rules", new JSONArray(cog.context.rules().stream().map(Rule::toJson).toList()))
                        .put("tasks", new JSONArray())); // Tasks can be an empty array initially
        conn.send(initialState.toString());
    }

    private void sendResponse(WebSocket conn, String inReplyToId, String status, @Nullable JsonNode result, @Nullable String message) {
        var response = new JSONObject()
                .put("type", SIGNAL_TYPE_RESPONSE)
                .put("id", UUID.randomUUID().toString())
                .put("inReplyToId", inReplyToId)
                .put("payload", new JSONObject()
                        .put("status", status)
                        .put("result", result)
                        .put("message", message));
        if (conn.isOpen()) conn.send(response.toString());
    }

    private void sendSuccessResponse(WebSocket conn, String inReplyToId, @Nullable JsonNode result, @Nullable String message) {
        sendResponse(conn, inReplyToId, RESPONSE_STATUS_SUCCESS, result, message);
    }

    private void sendFailureResponse(WebSocket conn, String inReplyToId, @Nullable String message) {
        sendResponse(conn, inReplyToId, RESPONSE_STATUS_FAILURE, null, message);
    }

    private void sendErrorResponse(WebSocket conn, String inReplyToId, @Nullable String message) {
        sendResponse(conn, inReplyToId, RESPONSE_STATUS_ERROR, null, message);
    }

    private void handleUiActionAssertion(Cog.CogEvent event, Map<Term.Var, Term> bindings) {
        if (!(event instanceof Cog.AssertedEvent assertedEvent)) return;
        var assertion = assertedEvent.assertion();
        // Only process UI actions asserted into the dedicated KB
        if (!assertion.kb().equals(KB_UI_ACTIONS) || !assertion.isActive()) return;

        var kif = assertion.kif();
        if (kif.size() != 3 || kif.op().filter(PRED_UI_ACTION::equals).isEmpty()) {
            logWarning("Invalid uiAction assertion format: " + kif.toKif());
            return;
        }

        var typeTerm = kif.get(1);
        var dataTerm = kif.get(2);

        if (!(typeTerm instanceof Term.Atom(String value)) || !(dataTerm instanceof Term.Atom(String value1))) {
            logWarning("Invalid uiAction assertion arguments (must be atoms): " + kif.toKif());
            return;
        }

        JSONObject uiActionData;
        try {
            // Attempt to parse the data atom's value as JSON
            uiActionData = new JSONObject(new JSONTokener(value1));
        } catch (org.json.JSONException e) {
            logError("Failed to parse uiAction data JSON from assertion " + assertion.id() + ": " + value1);
            // If data is not valid JSON, send it as a string instead
            uiActionData = new JSONObject().put("value", value1);
        }

        var signal = new JSONObject()
                .put("type", SIGNAL_TYPE_UI_ACTION)
                .put("id", UUID.randomUUID().toString())
                .put("payload", new JSONObject()
                        .put("uiActionType", value)
                        .put("uiActionData", uiActionData));

        broadcast(signal.toString());
    }


    // --- Command Handlers ---

    private void handleAddNoteCommand(WebSocket conn, JSONObject payload) {
        var title = payload.optString("title");
        var text = payload.optString("text", "");
        var commandId = payload.optString("id");
        if (title == null || title.isBlank()) {
            sendFailureResponse(conn, commandId, "Note title cannot be empty.");
            return;
        }
        var newNote = new Note(Cog.id(Cog.ID_PREFIX_NOTE), title, text, Note.Status.IDLE);
        cog.addNote(newNote);
        sendSuccessResponse(conn, commandId, newNote.toJson(), "Note added.");
    }

    private void handleRemoveNoteCommand(WebSocket conn, JSONObject payload) {
        var noteId = payload.optString("noteId");
        var commandId = payload.optString("id");
        if (noteId == null || noteId.isBlank()) {
            sendFailureResponse(conn, commandId, "Note ID is required.");
            return;
        }
        cog.removeNote(noteId);
        sendSuccessResponse(conn, commandId, null, "Note removal requested.");
    }

    private void handleStartNoteCommand(WebSocket conn, JSONObject payload) {
        var noteId = payload.optString("noteId");
        var commandId = payload.optString("id");
        if (noteId == null || noteId.isBlank()) {
            sendFailureResponse(conn, commandId, "Note ID is required.");
            return;
        }
        cog.startNote(noteId);
        sendSuccessResponse(conn, commandId, null, "Note start requested.");
    }

    private void handlePauseNoteCommand(WebSocket conn, JSONObject payload) {
        var noteId = payload.optString("noteId");
        var commandId = payload.optString("id");
        if (noteId == null || noteId.isBlank()) {
            sendFailureResponse(conn, commandId, "Note ID is required.");
            return;
        }
        cog.pauseNote(noteId);
        sendSuccessResponse(conn, commandId, null, "Note pause requested.");
    }

    private void handleCompleteNoteCommand(WebSocket conn, JSONObject payload) {
        var noteId = payload.optString("noteId");
        var commandId = payload.optString("id");
        if (noteId == null || noteId.isBlank()) {
            sendFailureResponse(conn, commandId, "Note ID is required.");
            return;
        }
        cog.completeNote(noteId);
        sendSuccessResponse(conn, commandId, null, "Note completion requested.");
    }

    private void handleRunToolCommand(WebSocket conn, JSONObject payload) {
        var toolName = payload.optString("toolName");
        var parameters = payload.optJSONObject("parameters");
        var commandId = payload.optString("id");

        if (toolName == null || toolName.isBlank() || parameters == null) {
            sendFailureResponse(conn, commandId, "Invalid run_tool command: missing toolName or parameters.");
            return;
        }

        cog.tools.get(toolName).ifPresentOrElse(tool -> {
            Map<String, Object> paramMap = parameters.toMap();

            tool.execute(paramMap).whenCompleteAsync((result, ex) -> {
                if (ex != null) {
                    error("Error executing tool '" + toolName + "': " + ex.getMessage());
                    ex.printStackTrace();
                    sendErrorResponse(conn, commandId, "Error executing tool: " + ex.getMessage());
                } else {
                    JSONObject resultJson = null;
                    if (result != null) {
                        try {
                            if (result instanceof String s) {
                                resultJson = new JSONObject().put("result", s);
                            } else if (result instanceof Boolean b) {
                                resultJson = new JSONObject().put("result", b);
                            } else if (result instanceof Number n) {
                                resultJson = new JSONObject().put("result", n);
                            } else if (result instanceof JSONObject jo) {
                                resultJson = jo; // Assume tool returned a JSONObject directly
                            } else if (result instanceof JSONArray ja) {
                                resultJson = new JSONObject().put("result", ja); // Wrap JSONArray
                            } else {
                                resultJson = new JSONObject().put("result", result.toString());
                            }
                        } catch (Exception e) {
                            error("Failed to serialize tool result for '" + toolName + "': " + e.getMessage());
                            resultJson = new JSONObject().put("result", result.toString());
                        }
                    }
                    sendSuccessResponse(conn, commandId, resultJson, "Tool executed successfully.");
                }
            }, cog.events.exe); // Use events executor for tool result handling

        }, () -> sendFailureResponse(conn, commandId, "Tool not found: " + toolName));
    }

    private void handleRunQueryCommand(WebSocket conn, JsonNode payload) {
        var queryId = payload.optString("queryId", Cog.id(Cog.ID_PREFIX_QUERY));
        var queryTypeStr = payload.optString("queryType");
        var patternString = payload.optString("patternString");
        var targetKbId = payload.optString("targetKbId");
        var parameters = payload.optJSONObject("parameters", new JSONObject());
        var commandId = payload.optString("id");

        if (queryTypeStr == null || patternString == null) {
            sendFailureResponse(conn, commandId, "Invalid run_query command: missing queryType or patternString.");
            return;
        }

        try {
            var queryType = Cog.QueryType.valueOf(queryTypeStr.toUpperCase());
            var terms = KifParser.parseKif(patternString);
            if (terms.size() != 1 || !(terms.getFirst() instanceof Term.Lst pattern)) {
                throw new KifParser.ParseException("Query pattern must be a single KIF list.");
            }

            Map<String, Object> paramMap = parameters.toMap();

            var query = new Query(queryId, queryType, pattern, targetKbId, paramMap);

            // Emit the query event for the ReasonerManager to handle asynchronously
            cog.events.emit(new Query.QueryEvent(query));

            // Send an immediate success response indicating the query was submitted
            sendSuccessResponse(conn, commandId, new JSONObject().put("queryId", queryId), "Query submitted.");

        } catch (IllegalArgumentException e) {
            sendFailureResponse(conn, commandId, "Invalid queryType: " + queryTypeStr);
        } catch (KifParser.ParseException e) {
            sendFailureResponse(conn, commandId, "Failed to parse query pattern KIF: " + e.getMessage());
        } catch (Exception e) {
            error("Error submitting query: " + e.getMessage());
            e.printStackTrace();
            sendErrorResponse(conn, commandId, "Error submitting query: " + e.getMessage());
        }
    }

    private void handleClearAllCommand(WebSocket conn, JsonNode payload) {
        var commandId = payload.optString("id");
        cog.clear();
        sendSuccessResponse(conn, commandId, null, "Clear all requested.");
    }

    private void handleSetConfigCommand(WebSocket conn, JsonNode payload) {
        var configJsonText = payload.optString("configJsonText");
        var commandId = payload.optString("id");
        if (configJsonText == null || configJsonText.isBlank()) {
            sendFailureResponse(conn, commandId, "configJsonText parameter is required.");
            return;
        }
        if (cog.updateConfig(configJsonText)) {
            sendSuccessResponse(conn, commandId, JsonUtil.toJsonNode(new CogNote.Configuration(cog)), "Configuration updated.");
        } else {
            sendFailureResponse(conn, commandId, "Failed to update configuration. Invalid JSON?");
        }
    }

    private void handleGetInitialStateCommand(WebSocket conn, JsonNode payload) {
        var commandId = payload.optString("id");
        sendInitialState(conn);
        sendSuccessResponse(conn, commandId, null, "Initial state sent.");
    }

    private void handleSaveNotesCommand(WebSocket conn, JsonNode payload) {
        var commandId = payload.optString("id");
        cog.save();
        sendSuccessResponse(conn, commandId, null, "Notes saved.");
    }


    // --- Feedback Handlers ---

    private void handleUserAssertedKifFeedback(WebSocket conn, JSONObject payload) {
        var feedbackId = payload.optString("id");
        var feedbackData = payload.optJSONObject("feedbackData");
        var noteId = feedbackData.optString("noteId");
        var kifString = feedbackData.optString("kifString");

        if (noteId == null || kifString == null) {
            sendFailureResponse(conn, feedbackId, "Invalid user_asserted_kif feedback: missing noteId or kifString.");
            return;
        }

        try {
            var terms = KifParser.parseKif(kifString);
            if (terms.isEmpty()) {
                sendFailureResponse(conn, feedbackId, "No KIF terms parsed from input.");
                return;
            }
            // Emit ExternalInputEvent for each parsed term
            terms.forEach(term -> events.emit(new Cog.ExternalInputEvent(term, "client:" + conn.getRemoteSocketAddress().toString(), noteId)));

            // Also assert the feedback itself into the user feedback KB
            var feedbackTerm = new Term.Lst(Term.Atom.of(PRED_USER_ASSERTED_KIF), Term.Atom.of(noteId), Term.Atom.of(kifString));
            context.tryCommit(new Assertion.PotentialAssertion(feedbackTerm, Cog.INPUT_ASSERTION_BASE_PRIORITY, Set.of(), "client-feedback:" + conn.getRemoteSocketAddress().toString(), false, false, false, KB_USER_FEEDBACK, Logic.AssertionType.GROUND, List.of(), 0), "client-feedback");

            sendSuccessResponse(conn, feedbackId, null, "KIF asserted.");

        } catch (KifParser.ParseException e) {
            sendFailureResponse(conn, feedbackId, "Failed to parse KIF: " + e.getMessage());
        } catch (Exception e) {
            error("Error processing user_asserted_kif feedback: " + e.getMessage());
            e.printStackTrace();
            sendErrorResponse(conn, feedbackId, "Error processing feedback: " + e.getMessage());
        }
    }

    private void handleUserEditedNoteTextFeedback(WebSocket conn, JSONObject payload) {
        var feedbackId = payload.optString("id");
        var feedbackData = payload.optJSONObject("feedbackData");
        var noteId = feedbackData.optString("noteId");
        var text = feedbackData.optString("text");

        if (noteId == null || text == null) {
            sendFailureResponse(conn, feedbackId, "Invalid user_edited_note_text feedback: missing noteId or text.");
            return;
        }

        cog.updateNoteText(noteId, text);

        // Assert the feedback into the user feedback KB
        var feedbackTerm = new Term.Lst(Term.Atom.of(PRED_USER_EDITED_NOTE_TEXT), Term.Atom.of(noteId), Term.Atom.of(text));
        context.tryCommit(new Assertion.PotentialAssertion(feedbackTerm, Cog.INPUT_ASSERTION_BASE_PRIORITY, Set.of(), "client-feedback:" + conn.getRemoteSocketAddress().toString(), false, false, false, KB_USER_FEEDBACK, Logic.AssertionType.GROUND, List.of(), 0), "client-feedback");

        sendSuccessResponse(conn, feedbackId, null, "Note text updated.");
    }

    private void handleUserEditedNoteTitleFeedback(WebSocket conn, JSONObject payload) {
        var feedbackId = payload.optString("id");
        var feedbackData = payload.optJSONObject("feedbackData");
        var noteId = feedbackData.optString("noteId");
        var title = feedbackData.optString("title");

        if (noteId == null || title == null) {
            sendFailureResponse(conn, feedbackId, "Invalid user_edited_note_title feedback: missing noteId or title.");
            return;
        }

        cog.updateNoteTitle(noteId, title);

        // Assert the feedback into the user feedback KB
        var feedbackTerm = new Term.Lst(Term.Atom.of(PRED_USER_EDITED_NOTE_TITLE), Term.Atom.of(noteId), Term.Atom.of(title));
        context.tryCommit(new Assertion.PotentialAssertion(feedbackTerm, Cog.INPUT_ASSERTION_BASE_PRIORITY, Set.of(), "client-feedback:" + conn.getRemoteSocketAddress().toString(), false, false, false, KB_USER_FEEDBACK, Logic.AssertionType.GROUND, List.of(), 0), "client-feedback");

        sendSuccessResponse(conn, feedbackId, null, "Note title updated.");
    }

    private void handleUserClickedFeedback(WebSocket conn, JSONObject payload) {
        var feedbackId = payload.optString("id");
        var feedbackData = payload.optJSONObject("feedbackData");
        var elementId = feedbackData.optString("elementId");

        if (elementId == null) {
            sendFailureResponse(conn, feedbackId, "Invalid user_clicked feedback: missing elementId.");
            return;
        }

        // Assert the feedback into the user feedback KB
        var feedbackTerm = new Term.Lst(Term.Atom.of(PRED_USER_CLICKED), Term.Atom.of(elementId));
        context.tryCommit(new Assertion.PotentialAssertion(feedbackTerm, Cog.INPUT_ASSERTION_BASE_PRIORITY, Set.of(), "client-feedback:" + conn.getRemoteSocketAddress().toString(), false, false, false, KB_USER_FEEDBACK, Logic.AssertionType.GROUND, List.of(), 0), "client-feedback");

        sendSuccessResponse(conn, feedbackId, null, "User clicked feedback processed.");
    }
}
