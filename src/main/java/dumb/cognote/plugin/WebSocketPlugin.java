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

public class WebSocketPlugin extends Plugin.BasePlugin {

    private final InetSocketAddress address;
    private final Set<WebSocket> clients = new CopyOnWriteArraySet<>();
    private final Map<String, BiConsumer<WebSocket, JsonNode>> commandHandlers = new ConcurrentHashMap<>();
    private final Map<String, BiConsumer<WebSocket, JsonNode>> feedbackHandlers = new ConcurrentHashMap<>();
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
            // Attempt to send a generic error if parsing failed before getting the ID
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

        if (commandTypeNode == null || !commandTypeNode.isTextual() || parametersNode == null || !parametersNode.isObject()) {
            sendFailureResponse(conn, commandId, "Invalid command format: missing commandType (string) or parameters (object).");
            return;
        }
        var commandType = commandTypeNode.asText();

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

    private void handleFeedback(WebSocket conn, String feedbackId, @Nullable String inReplyToId, JsonNode payload) {
        var feedbackTypeNode = payload.get("feedbackType");
        var feedbackDataNode = payload.get("feedbackData");

        if (feedbackTypeNode == null || !feedbackTypeNode.isTextual() || feedbackDataNode == null || !feedbackDataNode.isObject()) {
            sendFailureResponse(conn, feedbackId, "Invalid feedback format: missing feedbackType (string) or feedbackData (object).");
            return;
        }
        var feedbackType = feedbackTypeNode.asText();

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
        var dialogueIdNode = payload.get("dialogueId");
        var responseDataNode = payload.get("responseData");

        if (dialogueIdNode == null || !dialogueIdNode.isTextual() || responseDataNode == null) {
            sendErrorResponse(conn, responseId, "Invalid dialogue response format: missing dialogueId (string) or responseData.");
            return;
        }
        var dialogueId = dialogueIdNode.asText();

        cog.dialogue.handleResponse(dialogueId, responseDataNode)
                .ifPresentOrElse(
                        future -> sendSuccessResponse(conn, responseId, null, "Dialogue response processed."),
                        () -> sendErrorResponse(conn, responseId, "No pending dialogue request found for ID: " + dialogueId)
                );
    }


    private void broadcastEvent(Cog.CogEvent event) {
        // Don't broadcast LogMessageEvents back to clients, they are handled by the server's Log class
        if (event instanceof Events.LogMessageEvent) return;

        // Assuming CogEvent has a method to get its data as a serializable object or JsonNode
        // If not, we might need to add one or serialize the event object itself.
        // Let's assume we serialize the event object directly.
        var eventJson = Json.node(event);
        if (eventJson == null || eventJson.isNull()) {
            error("Failed to serialize CogEvent to JSON: " + event.getClass().getName());
            return;
        }

        ObjectNode signal = Json.node()
                .put("type", SIGNAL_TYPE_EVENT)
                .put("id", UUID.randomUUID().toString())
                .set("payload", eventJson); // Use set for JsonNode

        broadcast(Json.str(signal));
    }

    private void broadcast(String message) {
        clients.forEach(client -> {
            if (client.isOpen()) client.send(message);
        });
    }

    private void sendInitialState(WebSocket conn) {
        var payload = Json.node();

        // Serialize various state components to JsonNode
        payload.set("systemStatus", Json.node(new Cog.SystemStatusEvent(cog.status, cog.context.kbCount(), cog.context.kbTotalCapacity(), cog.lm.activeLlmTasks.size(), cog.context.ruleCount())));
        payload.set("configuration", Json.node(new CogNote.Configuration(cog)));

        var notesArray = Json.the.createArrayNode();
        cog.getAllNotes().stream().map(Json::node).forEach(notesArray::add); // Assuming Note has toJsonNode()

        var assertionsArray = Json.the.createArrayNode();
        cog.context.truth.getAllActiveAssertions().stream().map(Json::node).forEach(assertionsArray::add); // Assuming Assertion has toJsonNode()

        var rulesArray = Json.the.createArrayNode();
        cog.context.rules().stream().map(Json::node).forEach(rulesArray::add); // Assuming Rule has toJsonNode()

        payload.set("notes", notesArray);
        payload.set("assertions", assertionsArray);
        payload.set("rules", rulesArray);
        payload.set("tasks", Json.the.createArrayNode()); // Tasks can be an empty array initially

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

        // Check if both are Atoms and extract values within the successful branch
        if (typeTerm instanceof Term.Atom(var typeValueString) && dataTerm instanceof Term.Atom(
                var dataValueString
        )) {
            // Now typeValueString and dataValueString are in scope

            JsonNode uiActionDataNode;
            try {
                // Attempt to parse the data atom's value as JSON
                uiActionDataNode = Json.obj(dataValueString, JsonNode.class);
                if (uiActionDataNode == null || uiActionDataNode.isNull()) {
                    // If parsing results in null/empty, treat it as a simple string value
                    uiActionDataNode = Json.node().put("value", dataValueString);
                }
            } catch (JsonProcessingException e) {
                logError("Failed to parse uiAction data JSON from assertion " + assertion.id() + ": " + dataValueString + ". Treating as simple string value. Error: " + e.getMessage());
                // If data is not valid JSON, send it as a string instead
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
            // This is the case where one or both are NOT Atoms
            logWarning("Invalid uiAction assertion arguments (must be atoms): " + kif.toKif());
        }
    }


    // --- Command Handlers ---

    private void handleAddNoteCommand(WebSocket conn, JsonNode payload) {
        var commandId = payload.get("id") != null ? payload.get("id").asText() : null;
        var titleNode = payload.get("title");
        var textNode = payload.get("text");

        if (titleNode == null || !titleNode.isTextual() || titleNode.asText().isBlank()) {
            sendFailureResponse(conn, commandId, "Note title cannot be empty.");
            return;
        }
        var title = titleNode.asText();
        var text = textNode != null && textNode.isTextual() ? textNode.asText() : "";

        var newNote = new Note(Cog.id(Cog.ID_PREFIX_NOTE), title, text, Note.Status.IDLE);
        cog.addNote(newNote);
        sendSuccessResponse(conn, commandId, Json.node(newNote), "Note added."); // Assuming Note has toJsonNode()
    }

    private void handleRemoveNoteCommand(WebSocket conn, JsonNode payload) {
        var commandId = payload.get("id") != null ? payload.get("id").asText() : null;
        var noteIdNode = payload.get("noteId");

        if (noteIdNode == null || !noteIdNode.isTextual() || noteIdNode.asText().isBlank()) {
            sendFailureResponse(conn, commandId, "Note ID is required.");
            return;
        }
        var noteId = noteIdNode.asText();

        cog.removeNote(noteId);
        sendSuccessResponse(conn, commandId, null, "Note removal requested.");
    }

    private void handleStartNoteCommand(WebSocket conn, JsonNode payload) {
        var commandId = payload.get("id") != null ? payload.get("id").asText() : null;
        var noteIdNode = payload.get("noteId");

        if (noteIdNode == null || !noteIdNode.isTextual() || noteIdNode.asText().isBlank()) {
            sendFailureResponse(conn, commandId, "Note ID is required.");
            return;
        }
        var noteId = noteIdNode.asText();

        cog.startNote(noteId);
        sendSuccessResponse(conn, commandId, null, "Note start requested.");
    }

    private void handlePauseNoteCommand(WebSocket conn, JsonNode payload) {
        var commandId = payload.get("id") != null ? payload.get("id").asText() : null;
        var noteIdNode = payload.get("noteId");

        if (noteIdNode == null || !noteIdNode.isTextual() || noteIdNode.asText().isBlank()) {
            sendFailureResponse(conn, commandId, "Note ID is required.");
            return;
        }
        var noteId = noteIdNode.asText();

        cog.pauseNote(noteId);
        sendSuccessResponse(conn, commandId, null, "Note pause requested.");
    }

    private void handleCompleteNoteCommand(WebSocket conn, JsonNode payload) {
        var commandId = payload.get("id") != null ? payload.get("id").asText() : null;
        var noteIdNode = payload.get("noteId");

        if (noteIdNode == null || !noteIdNode.isTextual() || noteIdNode.asText().isBlank()) {
            sendFailureResponse(conn, commandId, "Note ID is required.");
            return;
        }
        var noteId = noteIdNode.asText();

        cog.completeNote(noteId);
        sendSuccessResponse(conn, commandId, null, "Note completion requested.");
    }

    private void handleRunToolCommand(WebSocket conn, JsonNode payload) {
        var commandId = payload.get("id") != null ? payload.get("id").asText() : null;
        var toolNameNode = payload.get("toolName");
        var parametersNode = payload.get("parameters");

        if (toolNameNode == null || !toolNameNode.isTextual() || toolNameNode.asText().isBlank() || parametersNode == null || !parametersNode.isObject()) {
            sendFailureResponse(conn, commandId, "Invalid run_tool command: missing toolName (string) or parameters (object).");
            return;
        }
        var toolName = toolNameNode.asText();

        cog.tools.get(toolName).ifPresentOrElse(tool -> {
            // Convert JsonNode parameters to Map<String, Object>
            Map<String, Object> paramMap;
            try {
                paramMap = Json.the.convertValue(parametersNode, Map.class);
            } catch (IllegalArgumentException e) {
                sendFailureResponse(conn, commandId, "Invalid tool parameters format: " + e.getMessage());
                return;
            }

            tool.execute(paramMap).whenCompleteAsync((result, ex) -> {
                if (ex != null) {
                    error("Error executing tool '" + toolName + "': " + ex.getMessage());
                    ex.printStackTrace();
                    sendErrorResponse(conn, commandId, "Error executing tool: " + ex.getMessage());
                } else {
                    // Convert tool result to JsonNode
                    var resultJson = Json.node(result);
                    sendSuccessResponse(conn, commandId, resultJson, "Tool executed successfully.");
                }
            }, cog.events.exe); // Use events executor for tool result handling

        }, () -> sendFailureResponse(conn, commandId, "Tool not found: " + toolName));
    }

    private void handleRunQueryCommand(WebSocket conn, JsonNode payload) {
        var commandId = payload.get("id") != null ? payload.get("id").asText() : null;
        var queryId = payload.get("queryId") != null ? payload.get("queryId").asText() : Cog.id(Cog.ID_PREFIX_QUERY);
        var queryTypeNode = payload.get("queryType");
        var patternStringNode = payload.get("patternString");
        var targetKbIdNode = payload.get("targetKbId");
        var parametersNode = payload.get("parameters"); // This is already JsonNode

        if (queryTypeNode == null || !queryTypeNode.isTextual() || patternStringNode == null || !patternStringNode.isTextual()) {
            sendFailureResponse(conn, commandId, "Invalid run_query command: missing queryType (string) or patternString (string).");
            return;
        }
        var queryTypeStr = queryTypeNode.asText();
        var patternString = patternStringNode.asText();
        var targetKbId = targetKbIdNode != null && targetKbIdNode.isTextual() ? targetKbIdNode.asText() : null;

        try {
            var queryType = Cog.QueryType.valueOf(queryTypeStr.toUpperCase());
            var terms = KifParser.parseKif(patternString);
            if (terms.size() != 1 || !(terms.getFirst() instanceof Term.Lst pattern)) {
                throw new KifParser.ParseException("Query pattern must be a single KIF list.");
            }

            // Convert JsonNode parameters to Map<String, Object>
            Map<String, Object> paramMap = Map.of(); // Default empty map
            if (parametersNode != null && parametersNode.isObject()) {
                try {
                    paramMap = Json.the.convertValue(parametersNode, Map.class);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Invalid query parameters format: " + e.getMessage(), e);
                }
            }


            var query = new Query(queryId, queryType, pattern, targetKbId, paramMap);

            // Emit the query event for the ReasonerManager to handle asynchronously
            cog.events.emit(new Query.QueryEvent(query));

            // Send an immediate success response indicating the query was submitted
            sendSuccessResponse(conn, commandId, Json.node().put("queryId", queryId), "Query submitted.");

        } catch (IllegalArgumentException e) {
            sendFailureResponse(conn, commandId, "Invalid queryType or parameters: " + e.getMessage());
        } catch (KifParser.ParseException e) {
            sendFailureResponse(conn, commandId, "Failed to parse query pattern KIF: " + e.getMessage());
        } catch (Exception e) {
            error("Error submitting query: " + e.getMessage());
            e.printStackTrace();
            sendErrorResponse(conn, commandId, "Error submitting query: " + e.getMessage());
        }
    }

    private void handleClearAllCommand(WebSocket conn, JsonNode payload) {
        var commandId = payload.get("id") != null ? payload.get("id").asText() : null;
        cog.clear();
        sendSuccessResponse(conn, commandId, null, "Clear all requested.");
    }

    private void handleSetConfigCommand(WebSocket conn, JsonNode payload) {
        var commandId = payload.get("id") != null ? payload.get("id").asText() : null;
        var configJsonTextNode = payload.get("configJsonText");

        if (configJsonTextNode == null || !configJsonTextNode.isTextual() || configJsonTextNode.asText().isBlank()) {
            sendFailureResponse(conn, commandId, "configJsonText parameter (string) is required.");
            return;
        }
        var configJsonText = configJsonTextNode.asText();

        if (cog.updateConfig(configJsonText)) {
            sendSuccessResponse(conn, commandId, Json.node(new CogNote.Configuration(cog)), "Configuration updated.");
        } else {
            sendFailureResponse(conn, commandId, "Failed to update configuration. Invalid JSON?");
        }
    }

    private void handleGetInitialStateCommand(WebSocket conn, JsonNode payload) {
        var commandId = payload.get("id") != null ? payload.get("id").asText() : null;
        sendInitialState(conn);
        sendSuccessResponse(conn, commandId, null, "Initial state sent.");
    }

    private void handleSaveNotesCommand(WebSocket conn, JsonNode payload) {
        var commandId = payload.get("id") != null ? payload.get("id").asText() : null;
        cog.save();
        sendSuccessResponse(conn, commandId, null, "Notes saved.");
    }


    // --- Feedback Handlers ---

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

        // Assert the feedback into the user feedback KB
        var feedbackTerm = new Term.Lst(Term.Atom.of(PRED_USER_EDITED_NOTE_TEXT), Term.Atom.of(noteId), Term.Atom.of(text));
        context.tryCommit(new Assertion.PotentialAssertion(feedbackTerm, Cog.INPUT_ASSERTION_BASE_PRIORITY, Set.of(), "client-feedback:" + conn.getRemoteSocketAddress().toString(), false, false, false, KB_USER_FEEDBACK, Logic.AssertionType.GROUND, List.of(), 0), "client-feedback");

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

        // Assert the feedback into the user feedback KB
        var feedbackTerm = new Term.Lst(Term.Atom.of(PRED_USER_EDITED_NOTE_TITLE), Term.Atom.of(noteId), Term.Atom.of(title));
        context.tryCommit(new Assertion.PotentialAssertion(feedbackTerm, Cog.INPUT_ASSERTION_BASE_PRIORITY, Set.of(), "client-feedback:" + conn.getRemoteSocketAddress().toString(), false, false, false, KB_USER_FEEDBACK, Logic.AssertionType.GROUND, List.of(), 0), "client-feedback");

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

        // Assert the feedback into the user feedback KB
        var feedbackTerm = new Term.Lst(Term.Atom.of(PRED_USER_CLICKED), Term.Atom.of(elementId));
        context.tryCommit(new Assertion.PotentialAssertion(feedbackTerm, Cog.INPUT_ASSERTION_BASE_PRIORITY, Set.of(), "client-feedback:" + conn.getRemoteSocketAddress().toString(), false, false, false, KB_USER_FEEDBACK, Logic.AssertionType.GROUND, List.of(), 0), "client-feedback");

        sendSuccessResponse(conn, feedbackId, null, "User clicked feedback processed.");
    }
}
