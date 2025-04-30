package dumb.cognote.plugin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dumb.cognote.*;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.jetbrains.annotations.Nullable;

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
import static dumb.cognote.Term.Atom;
import static dumb.cognote.Term.Lst;

public class WebSocketPlugin extends Plugin.BasePlugin {

    public static final String COMMAND_ADD_NOTE = "add_note";
    public static final String COMMAND_REMOVE_NOTE = "remove_note";
    public static final String COMMAND_START_NOTE = "start_note";
    public static final String COMMAND_PAUSE_NOTE = "pause_note";
    public static final String COMMAND_COMPLETE_NOTE = "complete_note";
    public static final String COMMAND_RUN_TOOL = "run_tool";
    public static final String COMMAND_RUN_QUERY = "run_query";
    public static final String COMMAND_CLEAR_ALL = "clear_all";
    public static final String COMMAND_SET_CONFIG = "set_config";
    public static final String COMMAND_GET_INITIAL_STATE = "get_initial_state";
    public static final String COMMAND_SAVE_NOTES = "save_notes";
    private final InetSocketAddress address;
    private final Set<WebSocket> clients = new CopyOnWriteArraySet<>();
    private final Map<String, BiConsumer<WebSocket, JsonNode>> feedbackHandlers = new ConcurrentHashMap<>();
    private WebSocketServer server;
    public WebSocketPlugin(InetSocketAddress address, Cog cog) {
        this.address = address;
        this.cog = cog;
        setupFeedbackHandlers();
    }

    private void setupFeedbackHandlers() {
        feedbackHandlers.put(FEEDBACK_USER_ASSERTED_KIF, this::handleUserAssertedKifFeedback);
        feedbackHandlers.put(FEEDBACK_USER_EDITED_NOTE_TEXT, this::handleUserEditedNoteTextFeedback);
        feedbackHandlers.put(FEEDBACK_USER_EDITED_NOTE_TITLE, this::handleUserEditedNoteTitleFeedback);
        feedbackHandlers.put(FEEDBACK_USER_CLICKED, this::handleUserClickedFeedback);
    }

    @Override
    public void start(Events e, Logic.Cognition ctx) {
        super.start(e, ctx);
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

        events.on(Event.class, this::broadcastEvent);
        events.on(Answer.AnswerEvent.class, this::broadcastAnswerEvent);
        events.on(Event.TaskUpdateEvent.class, this::broadcastTaskUpdateEvent);
        events.on(Events.DialogueRequestEvent.class, this::broadcastDialogueRequestEvent);

        cog.events.on(new Lst(Atom.of(PRED_UI_ACTION), Term.Var.of("?type"), Term.Var.of("?data")), this::handleUiActionAssertion);
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
        JsonNode signal;
        String id = null;
        String type;
        JsonNode payload;
        String inReplyToId;

        try {
            signal = Json.obj(message, JsonNode.class);

            if (signal == null || !signal.isObject()) {
                sendErrorResponse(conn, null, "Invalid signal format: not a JSON object.");
                return;
            }

            id = signal.get("id") != null ? signal.get("id").asText() : null;
            type = signal.get("type") != null ? signal.get("type").asText() : null;
            payload = signal.get("payload");
            inReplyToId = signal.get("inReplyToId") != null ? signal.get("inReplyToId").asText() : null;


            if (type == null || id == null || payload == null || !payload.isObject()) {
                sendErrorResponse(conn, id, "Invalid signal format: missing type, id, or payload (or payload is not an object).");
                return;
            }

            switch (type) {
                case SIGNAL_TYPE_COMMAND -> handleCommand(conn, id, payload);
                case SIGNAL_TYPE_INTERACTION_FEEDBACK -> handleFeedback(conn, id, inReplyToId, payload);
                case SIGNAL_TYPE_DIALOGUE_RESPONSE -> handleDialogueResponse(conn, id, inReplyToId, payload);
                default -> sendErrorResponse(conn, id, "Unknown signal type: " + type);
            }

        } catch (JsonProcessingException e) {
            error("Failed to parse incoming WebSocket message as JSON: " + message.substring(0, Math.min(message.length(), MAX_WS_PARSE_PREVIEW)) + "... Error: " + e.getMessage());
            try {
                conn.send(Json.str(Json.node()
                        .put("type", SIGNAL_TYPE_RESPONSE)
                        .put("id", UUID.randomUUID().toString())
                        .put("status", RESPONSE_STATUS_ERROR)
                        .put("message", "Failed to parse incoming JSON message.")));
            } catch (Exception ex) {
                error("Failed to send generic JSON parse error response: " + ex.getMessage());
            }

        } catch (Exception e) {
            error("Error handling incoming WebSocket message: " + e.getMessage());
            e.printStackTrace();
            if (id != null) {
                sendErrorResponse(conn, id, "Internal server error: " + e.getMessage());
            }
        }
    }

    private void handleCommand(WebSocket conn, String commandId, JsonNode payload) {
        var commandTypeNode = payload.get("commandType");
        var parametersNode = payload.get("parameters");

        if (commandTypeNode == null || !commandTypeNode.isTextual() || commandTypeNode.asText().isBlank()) {
            sendFailureResponse(conn, commandId, "Invalid command format: missing commandType (string).");
            return;
        }
        var commandType = commandTypeNode.asText();

        Lst requestKif;
        try {
            requestKif = commandJsonToKif(commandType, parametersNode);
        } catch (IllegalArgumentException e) {
            sendFailureResponse(conn, commandId, "Invalid command parameters: " + e.getMessage());
            return;
        }

        var potentialAssertion = new Assertion.PotentialAssertion(
                requestKif,
                Cog.INPUT_ASSERTION_BASE_PRIORITY,
                Set.of(),
                "client:" + conn.getRemoteSocketAddress().toString(),
                false, false, false,
                KB_CLIENT_INPUT,
                Logic.AssertionType.GROUND,
                List.of(),
                0
        );

        context.kb(KB_CLIENT_INPUT).commit(potentialAssertion, "client:" + conn.getRemoteSocketAddress().toString());

        sendSuccessResponse(conn, commandId, null, "Command received and queued for processing.");
    }

    private Lst commandJsonToKif(String commandType, @Nullable JsonNode parametersNode) {
        Lst requestPayload;
        switch (commandType) {
            case COMMAND_ADD_NOTE -> {
                var title = parametersNode != null && parametersNode.has("title") ? parametersNode.get("title").asText("") : "";
                var text = parametersNode != null && parametersNode.has("text") ? parametersNode.get("text").asText("") : "";
                requestPayload = new Lst(Atom.of(REQUEST_ADD_NOTE), new Lst(Atom.of("title"), Atom.of(title)), new Lst(Atom.of("text"), Atom.of(text)));
            }
            case COMMAND_REMOVE_NOTE -> {
                var noteId = parametersNode != null && parametersNode.has("noteId") ? parametersNode.get("noteId").asText("") : "";
                if (noteId.isBlank()) throw new IllegalArgumentException("noteId is required for remove_note.");
                requestPayload = new Lst(Atom.of(REQUEST_REMOVE_NOTE), new Lst(Atom.of("noteId"), Atom.of(noteId)));
            }
            case COMMAND_START_NOTE -> {
                var noteId = parametersNode != null && parametersNode.has("noteId") ? parametersNode.get("noteId").asText("") : "";
                if (noteId.isBlank()) throw new IllegalArgumentException("noteId is required for start_note.");
                requestPayload = new Lst(Atom.of(REQUEST_START_NOTE), new Lst(Atom.of("noteId"), Atom.of(noteId)));
            }
            case COMMAND_PAUSE_NOTE -> {
                var noteId = parametersNode != null && parametersNode.has("noteId") ? parametersNode.get("noteId").asText("") : "";
                if (noteId.isBlank()) throw new IllegalArgumentException("noteId is required for pause_note.");
                requestPayload = new Lst(Atom.of(REQUEST_PAUSE_NOTE), new Lst(Atom.of("noteId"), Atom.of(noteId)));
            }
            case COMMAND_COMPLETE_NOTE -> {
                var noteId = parametersNode != null && parametersNode.has("noteId") ? parametersNode.get("noteId").asText("") : "";
                if (noteId.isBlank()) throw new IllegalArgumentException("noteId is required for complete_note.");
                requestPayload = new Lst(Atom.of(REQUEST_COMPLETE_NOTE), new Lst(Atom.of("noteId"), Atom.of(noteId)));
            }
            case COMMAND_RUN_TOOL -> {
                var toolName = parametersNode != null && parametersNode.has("toolName") ? parametersNode.get("toolName").asText("") : "";
                var toolParamsJson = parametersNode != null && parametersNode.has("parameters") ? parametersNode.get("parameters") : Json.node();
                if (toolName.isBlank()) throw new IllegalArgumentException("toolName is required for run_tool.");
                Lst toolParamsKif = new Lst(Atom.of("params"));
                if (toolParamsJson.isObject()) {
                    toolParamsJson.fields().forEachRemaining(entry -> {
                        var key = entry.getKey();
                        var valueNode = entry.getValue();
                        toolParamsKif.terms.add(new Lst(Atom.of(key), Atom.of(valueNode.asText())));
                    });
                }
                requestPayload = new Lst(Atom.of(REQUEST_RUN_TOOL), new Lst(Atom.of("toolName"), Atom.of(toolName)), new Lst(Atom.of("parameters"), toolParamsKif));
            }
            case COMMAND_RUN_QUERY -> {
                var queryType = parametersNode != null && parametersNode.has("queryType") ? parametersNode.get("queryType").asText("") : "";
                var patternString = parametersNode != null && parametersNode.has("patternString") ? parametersNode.get("patternString").asText("") : "";
                var targetKbId = parametersNode != null && parametersNode.has("targetKbId") ? parametersNode.get("targetKbId").asText("") : "";
                var queryParamsJson = parametersNode != null && parametersNode.has("parameters") ? parametersNode.get("parameters") : Json.node();
                if (queryType.isBlank() || patternString.isBlank())
                    throw new IllegalArgumentException("queryType and patternString are required for run_query.");

                Lst queryParamsKif = new Lst(Atom.of("params"));
                if (queryParamsJson.isObject()) {
                    queryParamsJson.fields().forEachRemaining(entry -> {
                        var key = entry.getKey();
                        var valueNode = entry.getValue();
                        queryParamsKif.terms.add(new Lst(Atom.of(key), Atom.of(valueNode.asText())));
                    });
                }

                requestPayload = new Lst(Atom.of(REQUEST_RUN_QUERY), new Lst(Atom.of("queryType"), Atom.of(queryType)), new Lst(Atom.of("patternString"), Atom.of(patternString)), new Lst(Atom.of("targetKbId"), Atom.of(targetKbId)), new Lst(Atom.of("parameters"), queryParamsKif));
            }
            case COMMAND_CLEAR_ALL -> requestPayload = new Lst(Atom.of(REQUEST_CLEAR_ALL));
            case COMMAND_SET_CONFIG -> {
                var configJsonText = parametersNode != null && parametersNode.has("configJsonText") ? parametersNode.get("configJsonText").asText("") : "";
                if (configJsonText.isBlank())
                    throw new IllegalArgumentException("configJsonText is required for set_config.");
                requestPayload = new Lst(Atom.of(REQUEST_SET_CONFIG), new Lst(Atom.of("configJsonText"), Atom.of(configJsonText)));
            }
            case COMMAND_GET_INITIAL_STATE -> requestPayload = new Lst(Atom.of(REQUEST_GET_INITIAL_STATE));
            case COMMAND_SAVE_NOTES -> requestPayload = new Lst(Atom.of(REQUEST_SAVE_NOTES));
            default -> throw new IllegalArgumentException("Unknown command type: " + commandType);
        }
        return new Lst(Atom.of(PRED_REQUEST), requestPayload);
    }


    private void handleFeedback(WebSocket conn, String feedbackId, @Nullable String inReplyToId, JsonNode payload) {
        var feedbackTypeNode = payload.get("feedbackType");
        var feedbackDataNode = payload.get("feedbackData");

        if (feedbackTypeNode == null || !feedbackTypeNode.isTextual() || feedbackTypeNode.asText().isBlank() || feedbackDataNode == null || !feedbackDataNode.isObject()) {
            sendFailureResponse(conn, feedbackId, "Invalid feedback format: missing feedbackType (string) or feedbackData (object).");
            return;
        }
        var feedbackType = feedbackTypeNode.asText();

        var handler = feedbackHandlers.get(feedbackType);
        if (handler != null) {
            try {
                handler.accept(conn, payload);
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
        var dialogueIdNode = payload.get("dialogueId");
        var responseDataNode = payload.get("responseData");

        if (dialogueIdNode == null || !dialogueIdNode.isTextual() || dialogueIdNode.asText().isBlank() || responseDataNode == null) {
            sendErrorResponse(conn, responseId, "Invalid dialogue response format: missing dialogueId (string) or responseData.");
            return;
        }
        var dialogueId = dialogueIdNode.asText();

        cog.dialogue.handleResponse(dialogueId, responseDataNode)
                .ifPresentOrElse(
                        future -> { /* Response handled by Dialogue */ },
                        () -> sendErrorResponse(conn, responseId, "No pending dialogue request found for ID: " + dialogueId)
                );
    }


    private void broadcastEvent(Event event) {
        if (event instanceof Events.LogMessageEvent || event instanceof Answer.AnswerEvent || event instanceof Event.TaskUpdateEvent || event instanceof Events.DialogueRequestEvent) {
            return;
        }

        var eventJson = Json.node(event);
        if (eventJson == null || eventJson.isNull()) {
            error("Failed to serialize CogEvent to JSON: " + event.getClass().getName());
            return;
        }

        ObjectNode signal = Json.node()
                .put("type", SIGNAL_TYPE_EVENT)
                .put("id", UUID.randomUUID().toString())
                .set("payload", eventJson);

        broadcast(Json.str(signal));
    }

    private void broadcastAnswerEvent(Answer.AnswerEvent event) {
        var answerJson = Json.node(event.result());
        ObjectNode signal = Json.node()
                .put("type", SIGNAL_TYPE_RESPONSE)
                .put("id", UUID.randomUUID().toString())
                .put("inReplyToId", event.result().queryId())
                .set("payload", answerJson);
        broadcast(Json.str(signal));
    }

    private void broadcastTaskUpdateEvent(Event.TaskUpdateEvent event) {
        var taskJson = Json.node(event);
        ObjectNode signal = Json.node()
                .put("type", SIGNAL_TYPE_EVENT)
                .put("id", UUID.randomUUID().toString())
                .set("payload", taskJson);
        broadcast(Json.str(signal));
    }

    private void broadcastDialogueRequestEvent(Events.DialogueRequestEvent event) {
        var dialogueJson = Json.node(event);
        ObjectNode signal = Json.node()
                .put("type", SIGNAL_TYPE_DIALOGUE_REQUEST)
                .put("id", UUID.randomUUID().toString())
                .set("payload", dialogueJson);
        broadcast(Json.str(signal));
    }


    private void broadcast(String message) {
        clients.forEach(client -> {
            if (client.isOpen()) client.send(message);
        });
    }

    private void sendInitialState(WebSocket conn) {
        var payload = Json.node();

        payload.set("systemStatus", Json.node(new Event.SystemStatusEvent(cog.status, cog.context.kbCount(), cog.context.kbTotalCapacity(), cog.lm.activeLlmTasks.size(), cog.context.ruleCount())));
        payload.set("configuration", Json.node(new Cog.Configuration(cog)));

        var notesArray = Json.the.createArrayNode();
        cog.getAllNotes().stream().map(Json::node).forEach(notesArray::add);

        var assertionsArray = Json.the.createArrayNode();
        cog.context.truth.getAllActiveAssertions().stream().map(Json::node).forEach(assertionsArray::add);

        var rulesArray = Json.the.createArrayNode();
        cog.context.rules().stream().map(Json::node).forEach(rulesArray::add);

        payload.set("notes", notesArray);
        payload.set("assertions", assertionsArray);
        payload.set("rules", rulesArray);
        payload.set("tasks", Json.the.createArrayNode());

        ObjectNode initialState = Json.node()
                .put("type", SIGNAL_TYPE_INITIAL_STATE)
                .put("id", UUID.randomUUID().toString())
                .set("payload", payload);

        conn.send(Json.str(initialState));
    }

    private void sendResponse(WebSocket conn, String inReplyToId, String status, @Nullable JsonNode result, @Nullable String message) {
        var payload = Json.node()
                .put("status", status);
        if (result != null) {
            payload.set("result", result);
        }
        if (message != null) {
            payload.put("message", message);
        }

        ObjectNode response = Json.node()
                .put("type", SIGNAL_TYPE_RESPONSE)
                .put("id", UUID.randomUUID().toString())
                .put("inReplyToId", inReplyToId)
                .set("payload", payload);

        if (conn.isOpen()) conn.send(Json.str(response));
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

    private void handleUiActionAssertion(Event event, Map<Term.Var, Term> bindings) {
        if (!(event instanceof Event.AssertedEvent assertedEvent)) return;
        var assertion = assertedEvent.assertion();
        if (!assertion.kb().equals(KB_UI_ACTIONS) || !assertion.isActive()) return;

        var kif = assertion.kif();
        if (kif.size() != 3 || kif.op().filter(PRED_UI_ACTION::equals).isEmpty()) {
            logWarning("Invalid uiAction assertion format: " + kif.toKif());
            return;
        }

        var typeTerm = kif.get(1);
        var dataTerm = kif.get(2);

        if (typeTerm instanceof Atom(var typeValueString) && dataTerm instanceof Atom(
                var dataValueString
        )) {
            JsonNode uiActionDataNode;
            try {
                uiActionDataNode = Json.obj(dataValueString, JsonNode.class);
                if (uiActionDataNode == null || uiActionDataNode.isNull()) {
                    uiActionDataNode = Json.node().put("value", dataValueString);
                }
            } catch (JsonProcessingException e) {
                logError("Failed to parse uiAction data JSON from assertion " + assertion.id() + ": " + dataValueString + ". Treating as simple string value. Error: " + e.getMessage());
                uiActionDataNode = Json.node().put("value", dataValueString);
            }

            ObjectNode payload = Json.node()
                    .put("uiActionType", typeValueString)
                    .set("uiActionData", uiActionDataNode);

            ObjectNode signal = Json.node()
                    .put("type", SIGNAL_TYPE_UI_ACTION)
                    .put("id", UUID.randomUUID().toString())
                    .set("payload", payload);

            broadcast(Json.str(signal));

        } else {
            logWarning("Invalid uiAction assertion arguments (must be atoms): " + kif.toKif());
        }
    }


    private void handleUserAssertedKifFeedback(WebSocket conn, JsonNode payload) {
        var feedbackId = payload.get("id") != null ? payload.get("id").asText() : null;
        var feedbackDataNode = payload.get("feedbackData");

        if (feedbackDataNode == null || !feedbackDataNode.isObject()) {
            sendFailureResponse(conn, feedbackId, "Invalid user_asserted_kif feedback: missing feedbackData (object).");
            return;
        }

        var noteIdNode = feedbackDataNode.get("noteId");
        var kifStringNode = feedbackDataNode.get("kifString");

        if (noteIdNode == null || !noteIdNode.isTextual() || noteIdNode.asText().isBlank() || kifStringNode == null || !kifStringNode.isTextual() || kifStringNode.asText().isBlank()) {
            sendFailureResponse(conn, feedbackId, "Invalid user_asserted_kif feedback: missing noteId (string) or kifString (string).");
            return;
        }
        var noteId = noteIdNode.asText();
        var kifString = kifStringNode.asText();


        try {
            var terms = KifParser.parseKif(kifString);
            if (terms.isEmpty()) {
                sendFailureResponse(conn, feedbackId, "No KIF terms parsed from input.");
                return;
            }
            terms.forEach(term -> events.emit(new Event.ExternalInputEvent(term, "client:" + conn.getRemoteSocketAddress().toString(), noteId)));

            var feedbackTerm = new Lst(Atom.of(PRED_USER_ASSERTED_KIF), Atom.of(noteId), Atom.of(kifString));
            context.kb(KB_USER_FEEDBACK).commit(new Assertion.PotentialAssertion(feedbackTerm, Cog.INPUT_ASSERTION_BASE_PRIORITY, Set.of(), "client-feedback:" + conn.getRemoteSocketAddress().toString(), false, false, false, KB_USER_FEEDBACK, Logic.AssertionType.GROUND, List.of(), 0), "client-feedback");

            sendSuccessResponse(conn, feedbackId, null, "KIF asserted.");

        } catch (KifParser.ParseException e) {
            sendFailureResponse(conn, feedbackId, "Failed to parse KIF: " + e.getMessage());
        } catch (Exception e) {
            error("Error processing user_asserted_kif feedback: " + e.getMessage());
            e.printStackTrace();
            sendErrorResponse(conn, feedbackId, "Error processing feedback: " + e.getMessage());
        }
    }

    private void handleUserEditedNoteTextFeedback(WebSocket conn, JsonNode payload) {
        var feedbackId = payload.get("id") != null ? payload.get("id").asText() : null;
        var feedbackDataNode = payload.get("feedbackData");

        if (feedbackDataNode == null || !feedbackDataNode.isObject()) {
            sendFailureResponse(conn, feedbackId, "Invalid user_edited_note_text feedback: missing feedbackData (object).");
            return;
        }

        var noteIdNode = feedbackDataNode.get("noteId");
        var textNode = feedbackDataNode.get("text");

        if (noteIdNode == null || !noteIdNode.isTextual() || noteIdNode.asText().isBlank() || textNode == null || !textNode.isTextual()) {
            sendFailureResponse(conn, feedbackId, "Invalid user_edited_note_text feedback: missing noteId (string) or text (string).");
            return;
        }
        var noteId = noteIdNode.asText();
        var text = textNode.asText();

        cog.updateNoteText(noteId, text);

        var feedbackTerm = new Lst(Atom.of(PRED_USER_EDITED_NOTE_TEXT), Atom.of(noteId), Atom.of(text));
        context.kb(KB_USER_FEEDBACK).commit(new Assertion.PotentialAssertion(feedbackTerm, Cog.INPUT_ASSERTION_BASE_PRIORITY, Set.of(), "client-feedback:" + conn.getRemoteSocketAddress().toString(), false, false, false, KB_USER_FEEDBACK, Logic.AssertionType.GROUND, List.of(), 0), "client-feedback");

        sendSuccessResponse(conn, feedbackId, null, "Note text updated.");
    }

    private void handleUserEditedNoteTitleFeedback(WebSocket conn, JsonNode payload) {
        var feedbackId = payload.get("id") != null ? payload.get("id").asText() : null;
        var feedbackDataNode = payload.get("feedbackData");

        if (feedbackDataNode == null || !feedbackDataNode.isObject()) {
            sendFailureResponse(conn, feedbackId, "Invalid user_edited_note_title feedback: missing feedbackData (object).");
            return;
        }

        var noteIdNode = feedbackDataNode.get("noteId");
        var titleNode = feedbackDataNode.get("title");

        if (noteIdNode == null || !noteIdNode.isTextual() || noteIdNode.asText().isBlank() || titleNode == null || !titleNode.isTextual() || titleNode.asText().isBlank()) {
            sendFailureResponse(conn, feedbackId, "Invalid user_edited_note_title feedback: missing noteId (string) or title (string).");
            return;
        }
        var noteId = noteIdNode.asText();
        var title = titleNode.asText();

        cog.updateNoteTitle(noteId, title);

        var feedbackTerm = new Lst(Atom.of(PRED_USER_EDITED_NOTE_TITLE), Atom.of(noteId), Atom.of(title));
        context.kb(KB_USER_FEEDBACK).commit(new Assertion.PotentialAssertion(feedbackTerm, Cog.INPUT_ASSERTION_BASE_PRIORITY, Set.of(), "client-feedback:" + conn.getRemoteSocketAddress().toString(), false, false, false, KB_USER_FEEDBACK, Logic.AssertionType.GROUND, List.of(), 0), "client-feedback");

        sendSuccessResponse(conn, feedbackId, null, "Note title updated.");
    }

    private void handleUserClickedFeedback(WebSocket conn, JsonNode payload) {
        var feedbackId = payload.get("id") != null ? payload.get("id").asText() : null;
        var feedbackDataNode = payload.get("feedbackData");

        if (feedbackDataNode == null || !feedbackDataNode.isObject()) {
            sendFailureResponse(conn, feedbackId, "Invalid user_clicked feedback: missing feedbackData (object).");
            return;
        }

        var elementIdNode = feedbackDataNode.get("elementId");

        if (elementIdNode == null || !elementIdNode.isTextual() || elementIdNode.asText().isBlank()) {
            sendFailureResponse(conn, feedbackId, "Invalid user_clicked feedback: missing elementId (string).");
            return;
        }
        var elementId = elementIdNode.asText();

        var feedbackTerm = new Lst(Atom.of(PRED_USER_CLICKED), Atom.of(elementId));
        context.kb(KB_USER_FEEDBACK).commit(new Assertion.PotentialAssertion(feedbackTerm, Cog.INPUT_ASSERTION_BASE_PRIORITY, Set.of(), "client-feedback:" + conn.getRemoteSocketAddress().toString(), false, false, false, KB_USER_FEEDBACK, Logic.AssertionType.GROUND, List.of(), 0), "client-feedback");

        sendSuccessResponse(conn, feedbackId, null, "User clicked feedback processed.");
    }
}
