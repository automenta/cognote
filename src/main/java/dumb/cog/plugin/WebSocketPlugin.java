package dumb.cog.plugin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dumb.cog.*;
import dumb.cog.util.Events;
import dumb.cog.util.Json;
import dumb.cog.util.KifParser;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

import static dumb.cog.Cog.INPUT_ASSERTION_BASE_PRIORITY;
import static dumb.cog.Cog.MAX_WS_PARSE_PREVIEW;
import static dumb.cog.Protocol.*;
import static dumb.cog.Term.Lst;
import static dumb.cog.util.Log.*;

public class WebSocketPlugin extends Plugin.BasePlugin {

    private final InetSocketAddress address;
    private final Set<WebSocket> clients = new CopyOnWriteArraySet<>();
    private WebSocketServer server;

    public WebSocketPlugin(InetSocketAddress address, Cog cog) {
        this.address = address;
        this.cog = cog;
    }

    @Override
    public void start(Events e, Cognition ctx) {
        super.start(e, ctx);
        server = new WebSocketServer(address) {
            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
                clients.add(conn);
                message("WebSocket client connected: " + conn.getRemoteSocketAddress());
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
                // Log the port the WebSocket server actually started on
                message("WebSocket server started on port " + getPort());
            }
        };
        server.start();

        events.on(Event.class, this::broadcastEventAsUpdate);
        events.on(Answer.AnswerEvent.class, this::broadcastAnswerEventAsUpdate);
        events.on(Events.DialogueRequestEvent.class, this::broadcastDialogueRequestEventAsUpdate);
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
        String signalId = null;
        String signalType;

        try {
            signal = Json.obj(message, JsonNode.class);

            if (signal == null || !signal.isObject()) {
                sendErrorResponse(conn, null, "Invalid signal format: not a JSON object.");
                return;
            }

            signalId = signal.get("id") != null ? signal.get("id").asText() : null;
            signalType = signal.get("type") != null ? signal.get("type").asText() : null;
            JsonNode payload = signal.get("payload");

            if (signalType == null || signalId == null || payload == null || !payload.isObject()) {
                sendErrorResponse(conn, signalId, "Invalid signal format: missing type, id, or payload (or payload is not an object).");
                return;
            }

            if (!signalType.equals(SIGNAL_TYPE_REQUEST)) {
                sendErrorResponse(conn, signalId, "Unknown signal type: " + signalType + ". Expected '" + SIGNAL_TYPE_REQUEST + "'.");
                return;
            }

            String command = payload.get("command") != null ? payload.get("command").asText() : null;
            JsonNode parameters = payload.get("parameters");

            if (command == null || parameters == null || !parameters.isObject()) {
                sendFailureResponse(conn, signalId, "Invalid '" + SIGNAL_TYPE_REQUEST + "' payload: missing 'command' (string) or 'parameters' (object).");
                return;
            }

            handleRequest(conn, signalId, command, parameters);

        } catch (JsonProcessingException e) {
            error("Failed to parse incoming WebSocket message as JSON: " + message.substring(0, Math.min(message.length(), MAX_WS_PARSE_PREVIEW)) + "... Error: " + e.getMessage());
            sendErrorResponse(conn, signalId, "Failed to parse incoming JSON message.");
        } catch (Exception e) {
            error("Error handling incoming WebSocket message: " + e.getMessage());
            e.printStackTrace();
            sendErrorResponse(conn, signalId, "Internal server error: " + e.getMessage());
        }
    }

    private void handleRequest(WebSocket conn, String requestId, String command, JsonNode parameters) throws JsonProcessingException {
        try {
            String sourceId = "client:" + conn.getRemoteSocketAddress().toString();

            switch (command) {
                case COMMAND_GET_INITIAL_STATE -> sendInitialState(conn, requestId);
                case COMMAND_ASSERT_KIF -> handleAssertKifRequest(conn, requestId, parameters, sourceId);
                case COMMAND_RUN_TOOL -> handleRunToolRequest(conn, requestId, parameters, sourceId);
                case COMMAND_RUN_QUERY -> handleRunQueryRequest(conn, requestId, parameters, sourceId);
                case COMMAND_WAIT -> handleWaitRequest(conn, requestId, parameters, sourceId);
                case COMMAND_RETRACT -> handleRetractRequest(conn, requestId, parameters, sourceId);
                case COMMAND_CANCEL_DIALOGUE -> handleCancelDialogueRequest(conn, requestId, parameters);
                case COMMAND_DIALOGUE_RESPONSE -> handleDialogueResponseRequest(conn, requestId, parameters);
                case COMMAND_ADD_NOTE -> handleAddNoteRequest(conn, requestId, parameters);
                case COMMAND_UPDATE_NOTE -> handleUpdateNoteRequest(conn, requestId, parameters);
                case COMMAND_DELETE_NOTE -> handleDeleteNoteRequest(conn, requestId, parameters);
                case COMMAND_CLONE_NOTE -> handleCloneNoteRequest(conn, requestId, parameters);
                case COMMAND_CLEAR_ALL -> handleClearAllRequest(conn, requestId);
                case COMMAND_UPDATE_SETTINGS -> handleUpdateSettingsRequest(conn, requestId, parameters);
                default -> sendFailureResponse(conn, requestId, "Unknown command: " + command);
            }
        } catch (Exception e) {
            error("Error processing command '" + command + "' for request " + requestId + ": " + e.getMessage());
            e.printStackTrace();
            sendErrorResponse(conn, requestId, "Error processing command '" + command + "': " + e.getMessage());
        }
    }

    private void handleAssertKifRequest(WebSocket conn, String requestId, JsonNode parameters, String sourceId) {
        var kifStringsNode = parameters.get("kifStrings");
        var noteIdNode = parameters.get("noteId");

        if (kifStringsNode == null || !kifStringsNode.isArray()) {
            sendFailureResponse(conn, requestId, "Invalid parameters for '" + COMMAND_ASSERT_KIF + "': missing or invalid 'kifStrings' (array of strings).");
            return;
        }

        var noteId = noteIdNode != null && noteIdNode.isTextual() ? noteIdNode.asText() : null;
        var successCount = 0;
        var errorMessages = new StringBuilder();

        for (var kifStringNode : kifStringsNode) {
            if (!kifStringNode.isTextual()) {
                errorMessages.append("Invalid element in kifStrings array (not a string): ").append(kifStringNode).append("; ");
                continue;
            }
            var kifString = kifStringNode.asText();
            try {
                var terms = KifParser.parseKif(kifString);
                for (var term : terms) {
                    var targetKb = noteId != null ? noteId : Cog.GLOBAL_KB_NOTE_ID;
                    var potentialAssertion = new Assertion.PotentialAssertion(
                            (Lst) term,
                            INPUT_ASSERTION_BASE_PRIORITY,
                            Set.of(),
                            sourceId,
                            false, false, false,
                            targetKb,
                            Logic.AssertionType.GROUND,
                            List.of(),
                            0
                    );
                    context.tryCommit(potentialAssertion, sourceId);
                    successCount++;
                }
            } catch (KifParser.ParseException e) {
                errorMessages.append("Failed to parse KIF '").append(kifString).append("': ").append(e.getMessage()).append("; ");
            } catch (Exception e) {
                errorMessages.append("Error processing KIF '").append(kifString).append("': ").append(e.getMessage()).append("; ");
                error("Error processing asserted KIF: " + e.getMessage());
                e.printStackTrace();
            }
        }

        if (!errorMessages.isEmpty()) {
            sendFailureResponse(conn, requestId, "Processed " + successCount + " terms with errors: " + errorMessages);
        } else {
            sendSuccessResponse(conn, requestId, null, "Successfully processed and asserted " + successCount + " terms.");
        }
    }

    private void handleRunToolRequest(WebSocket conn, String requestId, JsonNode parameters, String sourceId) throws JsonProcessingException {
        var toolNameNode = parameters.get("name");
        var toolParamsNode = parameters.get("parameters");

        if (toolNameNode == null || !toolNameNode.isTextual() || toolNameNode.asText().isBlank() || toolParamsNode == null || !toolParamsNode.isObject()) {
            sendFailureResponse(conn, requestId, "Invalid parameters for '" + COMMAND_RUN_TOOL + "': missing 'name' (string) or 'parameters' (object).");
            return;
        }

        var toolName = toolNameNode.asText();
        Map<String, Object> toolParams = Json.obj(Json.str(toolParamsNode), Map.class);

        cog.tools.get(toolName).ifPresentOrElse(tool -> {
            tool.execute(toolParams).whenCompleteAsync((result, ex) -> {
                if (ex != null) {
                    error("Error executing tool '" + toolName + "' for request " + requestId + ": " + ex.getMessage());
                    ex.printStackTrace();
                    sendErrorResponse(conn, requestId, "Tool execution failed for '" + toolName + "': " + ex.getMessage());
                } else {
                    message("Tool '" + toolName + "' executed successfully for request " + requestId + ".");
                    sendSuccessResponse(conn, requestId, null, "Tool '" + toolName + "' execution started.");
                }
            }, cog.events.exe);
        }, () -> sendFailureResponse(conn, requestId, "Tool not found: " + toolName));
    }

    private void handleRunQueryRequest(WebSocket conn, String requestId, JsonNode parameters, String sourceId) throws JsonProcessingException {
        var queryTypeNode = parameters.get("queryType");
        var patternStringNode = parameters.get("pattern");
        var targetKbIdNode = parameters.get("targetKbId");
        var queryParamsNode = parameters.get("parameters");

        if (queryTypeNode == null || !queryTypeNode.isTextual() || queryTypeNode.asText().isBlank() ||
                patternStringNode == null || !patternStringNode.isTextual() || patternStringNode.asText().isBlank()) {
            sendFailureResponse(conn, requestId, "Invalid parameters for '" + COMMAND_RUN_QUERY + "': missing 'queryType' (string) or 'pattern' (string).");
            return;
        }

        var queryTypeStr = queryTypeNode.asText();
        var patternString = patternStringNode.asText();
        var targetKbId = targetKbIdNode != null && targetKbIdNode.isTextual() ? targetKbIdNode.asText() : null;
        Map<String, Object> queryParams = queryParamsNode != null && queryParamsNode.isObject() ? Json.obj(Json.str(queryParamsNode), Map.class) : Map.of();

        try {
            var queryType = Cog.QueryType.valueOf(queryTypeStr.toUpperCase());
            var terms = KifParser.parseKif(patternString);
            if (terms.size() != 1 || !(terms.getFirst() instanceof Lst pattern)) {
                throw new KifParser.ParseException("Query pattern must be a single KIF list.");
            }

            var queryId = Cog.id(Cog.ID_PREFIX_QUERY);
            var query = new Query(queryId, queryType, pattern, targetKbId, queryParams);

            cog.events.emit(new Query.QueryEvent(query));
            message("Processed RunQuery request: Submitted query " + queryId + " for request " + requestId);
            sendSuccessResponse(conn, requestId, Json.node().put("queryId", queryId), "Query submitted.");

        } catch (IllegalArgumentException e) {
            sendFailureResponse(conn, requestId, "Invalid queryType or parameters for '" + COMMAND_RUN_QUERY + "': " + e.getMessage());
        } catch (KifParser.ParseException e) {
            sendFailureResponse(conn, requestId, "Failed to parse query pattern KIF for '" + COMMAND_RUN_QUERY + "': " + e.getMessage());
        } catch (Exception e) {
            error("Error processing RunQuery request " + requestId + ": " + e.getMessage());
            e.printStackTrace();
            sendErrorResponse(conn, requestId, "Error processing query: " + e.getMessage());
        }
    }

    private void handleWaitRequest(WebSocket conn, String requestId, JsonNode parameters, String sourceId) throws JsonProcessingException {
        var conditionStringNode = parameters.get("condition");
        var waitParamsNode = parameters.get("parameters");

        if (conditionStringNode == null || !conditionStringNode.isTextual() || conditionStringNode.asText().isBlank()) {
            sendFailureResponse(conn, requestId, "Invalid parameters for '" + COMMAND_WAIT + "': missing 'condition' (string KIF).");
            return;
        }

        var conditionString = conditionStringNode.asText();
        Map<String, Object> waitParams = waitParamsNode != null && waitParamsNode.isObject() ? Json.obj(Json.str(waitParamsNode), Map.class) : Map.of();

        try {
            var terms = KifParser.parseKif(conditionString);
            if (terms.size() != 1 || !(terms.getFirst() instanceof Lst conditionTerm)) {
                throw new KifParser.ParseException("Wait condition must be a single KIF list.");
            }

            message("Processed Wait request: Condition '" + conditionString + "' for request " + requestId);
            sendSuccessResponse(conn, requestId, null, "Wait condition submission acknowledged. Backend processing required.");

        } catch (KifParser.ParseException e) {
            sendFailureResponse(conn, requestId, "Failed to parse wait condition KIF for '" + COMMAND_WAIT + "': " + e.getMessage());
        } catch (Exception e) {
            error("Error processing Wait request " + requestId + ": " + e.getMessage());
            e.printStackTrace();
            sendErrorResponse(conn, requestId, "Error processing wait command: " + e.getMessage());
        }
    }

    private void handleRetractRequest(WebSocket conn, String requestId, JsonNode parameters, String sourceId) {
        var typeNode = parameters.get("type");
        var targetNode = parameters.get("target");
        var noteIdNode = parameters.get("noteId");

        if (typeNode == null || !typeNode.isTextual() || typeNode.asText().isBlank() ||
                targetNode == null || !targetNode.isTextual() || targetNode.asText().isBlank()) {
            sendFailureResponse(conn, requestId, "Invalid parameters for '" + COMMAND_RETRACT + "': missing 'type' (string) or 'target' (string).");
            return;
        }

        var typeStr = typeNode.asText();
        var target = targetNode.asText();
        var noteId = noteIdNode != null && noteIdNode.isTextual() ? noteIdNode.asText() : null;

        try {
            Logic.RetractionType typeEnum = Logic.RetractionType.valueOf(typeStr.toUpperCase());
            events.emit(new Event.RetractionRequestEvent(target, typeEnum, sourceId, noteId));
            message("Processed Retract request: Type=" + typeStr + ", Target='" + target + "' for request " + requestId);
            sendSuccessResponse(conn, requestId, null, "Retraction request emitted.");

        } catch (IllegalArgumentException e) {
            sendFailureResponse(conn, requestId, "Invalid retraction type for '" + COMMAND_RETRACT + "': " + typeStr);
        } catch (Exception e) {
            error("Error processing Retract request " + requestId + ": " + e.getMessage());
            e.printStackTrace();
            sendErrorResponse(conn, requestId, "Error processing retract command: " + e.getMessage());
        }
    }

    private void handleCancelDialogueRequest(WebSocket conn, String requestId, JsonNode parameters) {
        var dialogueIdNode = parameters.get("dialogueId");

        if (dialogueIdNode == null || !dialogueIdNode.isTextual() || dialogueIdNode.asText().isBlank()) {
            sendFailureResponse(conn, requestId, "Invalid parameters for '" + COMMAND_CANCEL_DIALOGUE + "': missing 'dialogueId' (string).");
            return;
        }

        var dialogueId = dialogueIdNode.asText();
        cog.dialogue.cancel(dialogueId);
        message("Processed CancelDialogue request for ID: " + dialogueId + " (request " + requestId + ")");
        sendSuccessResponse(conn, requestId, null, "Dialogue cancellation requested.");
    }

    private void handleDialogueResponseRequest(WebSocket conn, String requestId, JsonNode parameters) {
        var dialogueIdNode = parameters.get("dialogueId");
        var responseDataNode = parameters.get("responseData");

        if (dialogueIdNode == null || !dialogueIdNode.isTextual() || dialogueIdNode.asText().isBlank() || responseDataNode == null) {
            sendFailureResponse(conn, requestId, "Invalid parameters for '" + COMMAND_DIALOGUE_RESPONSE + "': missing 'dialogueId' (string) or 'responseData'.");
            return;
        }
        var dialogueId = dialogueIdNode.asText();

        cog.dialogue.handleResponse(dialogueId, responseDataNode)
                .ifPresentOrElse(
                        future -> {
                            message("Processed DialogueResponse request for ID " + dialogueId + " (request " + requestId + ")");
                            sendSuccessResponse(conn, requestId, null, "Dialogue response processed.");
                        },
                        () -> sendFailureResponse(conn, requestId, "No pending dialogue request found for ID: " + dialogueId)
                );
    }

    private void handleAddNoteRequest(WebSocket conn, String requestId, JsonNode parameters) {
        var titleNode = parameters.get("title");
        var contentNode = parameters.get("content");
        var stateNode = parameters.get("state");
        var priorityNode = parameters.get("priority");
        var colorNode = parameters.get("color");

        if (titleNode == null || !titleNode.isTextual() || titleNode.asText().isBlank()) {
            sendFailureResponse(conn, requestId, "Invalid parameters for '" + COMMAND_ADD_NOTE + "': missing 'title' (string).");
            return;
        }

        var title = titleNode.asText();
        var content = contentNode != null && contentNode.isTextual() ? contentNode.asText() : "";
        Note.Status state = Note.Status.IDLE;
        if (stateNode != null && stateNode.isTextual()) {
            try {
                state = Note.Status.valueOf(stateNode.asText().toUpperCase());
            } catch (IllegalArgumentException e) {
                warning("Invalid 'state' value for '" + COMMAND_ADD_NOTE + "': " + stateNode.asText() + ". Defaulting to IDLE.");
            }
        }
        var priority = priorityNode != null && priorityNode.isInt() ? priorityNode.asInt() : 0;
        var color = colorNode != null && colorNode.isTextual() ? colorNode.asText() : null;

        var newNote = new Note(Cog.id(Cog.ID_PREFIX_NOTE), title, content, state);
        newNote.pri = priority;
        newNote.color = color;
        cog.addNote(newNote);

        message("Processed AddNote request: " + title + " (request " + requestId + ")");
        sendSuccessResponse(conn, requestId, Json.node().put("noteId", newNote.id()), "Note added.");
    }

    private void handleUpdateNoteRequest(WebSocket conn, String requestId, JsonNode parameters) {
        var noteIdNode = parameters.get("noteId");
        var titleNode = parameters.get("title");
        var contentNode = parameters.get("content");
        var stateNode = parameters.get("state");
        var priorityNode = parameters.get("priority");
        var colorNode = parameters.get("color");

        if (noteIdNode == null || !noteIdNode.isTextual() || noteIdNode.asText().isBlank()) {
            sendFailureResponse(conn, requestId, "Invalid parameters for '" + COMMAND_UPDATE_NOTE + "': missing 'noteId' (string).");
            return;
        }

        var noteId = noteIdNode.asText();
        var title = titleNode != null && titleNode.isTextual() ? titleNode.asText() : null;
        var content = contentNode != null && contentNode.isTextual() ? contentNode.asText() : null;
        Note.Status state = null;
        if (stateNode != null && stateNode.isTextual()) {
            try {
                state = Note.Status.valueOf(stateNode.asText().toUpperCase());
            } catch (IllegalArgumentException e) {
                sendFailureResponse(conn, requestId, "Invalid 'state' value for '" + COMMAND_UPDATE_NOTE + "': " + stateNode.asText());
                return;
            }
        }
        var priority = priorityNode != null && priorityNode.isInt() ? priorityNode.asInt() : null;
        var color = colorNode != null && colorNode.isTextual() ? colorNode.asText() : null;

        Note.Status STATE = state;
        cog.note(noteId).ifPresentOrElse(note -> {
            cog.updateNote(noteId, title, content, STATE, priority, color);
            message("Processed UpdateNote request for ID: " + noteId + " (request " + requestId + ")");
            sendSuccessResponse(conn, requestId, null, "Note updated.");
        }, () -> sendFailureResponse(conn, requestId, "Note not found: " + noteId));
    }

    private void handleDeleteNoteRequest(WebSocket conn, String requestId, JsonNode parameters) {
        var noteIdNode = parameters.get("noteId");

        if (noteIdNode == null || !noteIdNode.isTextual() || noteIdNode.asText().isBlank()) {
            sendFailureResponse(conn, requestId, "Invalid parameters for '" + COMMAND_DELETE_NOTE + "': missing 'noteId' (string).");
            return;
        }

        var noteId = noteIdNode.asText();
        cog.removeNote(noteId);

        message("Processed DeleteNote request for ID: " + noteId + " (request " + requestId + ")");
        sendSuccessResponse(conn, requestId, null, "Note deletion requested.");
    }

    private void handleCloneNoteRequest(WebSocket conn, String requestId, JsonNode parameters) {
        var noteIdNode = parameters.get("noteId");

        if (noteIdNode == null || !noteIdNode.isTextual() || noteIdNode.asText().isBlank()) {
            sendFailureResponse(conn, requestId, "Invalid parameters for '" + COMMAND_CLONE_NOTE + "': missing 'noteId' (string).");
            return;
        }

        var noteId = noteIdNode.asText();
        cog.note(noteId).ifPresentOrElse(originalNote -> {
            cog.cloneNote(noteId);
            message("Processed CloneNote request for ID: " + noteId + " (request " + requestId + ")");
            sendSuccessResponse(conn, requestId, null, "Note cloning requested.");
        }, () -> sendFailureResponse(conn, requestId, "Note not found: " + noteId));
    }

    private void handleClearAllRequest(WebSocket conn, String requestId) {
        cog.clear();
        message("Processed ClearAll request (request " + requestId + ")");
        sendSuccessResponse(conn, requestId, null, "System clear initiated.");
    }

    private void handleUpdateSettingsRequest(WebSocket conn, String requestId, JsonNode parameters) {
        var settingsNode = parameters.get("settings");

        if (settingsNode == null || !settingsNode.isObject()) {
            sendFailureResponse(conn, requestId, "Invalid parameters for '" + COMMAND_UPDATE_SETTINGS + "': missing 'settings' (object).");
            return;
        }

        var settingsJsonText = Json.str(settingsNode);

        if (cog.updateConfig(settingsJsonText)) {
            message("Processed UpdateSettings request (request " + requestId + ")");
            sendSuccessResponse(conn, requestId, null, "Settings updated.");
        } else {
            sendFailureResponse(conn, requestId, "Failed to apply settings.");
        }
    }

    private void broadcastEventAsUpdate(Event event) {
        if (event instanceof Answer.AnswerEvent || event instanceof Events.DialogueRequestEvent) return;

        var eventJson = Json.node(event);
        if (eventJson == null || eventJson.isNull()) {
            error("Failed to serialize CogEvent to JSON for broadcast: " + event.getClass().getName());
            return;
        }

        ObjectNode update = Json.node()
                .put("type", SIGNAL_TYPE_UPDATE)
                .put("id", UUID.randomUUID().toString())
                .put("updateType", UPDATE_TYPE_EVENT)
                .set("payload", eventJson);

        broadcast(Json.str(update));
    }

    private void broadcastAnswerEventAsUpdate(Answer.AnswerEvent event) {
        var answerJson = Json.node(event.result());
        ObjectNode update = Json.node()
                .put("type", SIGNAL_TYPE_UPDATE)
                .put("id", UUID.randomUUID().toString())
                .put("updateType", UPDATE_TYPE_RESPONSE)
                .put("inReplyToId", event.result().queryId())
                .set("payload", answerJson);
        broadcast(Json.str(update));
    }

    private void broadcastDialogueRequestEventAsUpdate(Events.DialogueRequestEvent event) {
        var dialogueJson = Json.node(event);
        ObjectNode update = Json.node()
                .put("type", SIGNAL_TYPE_UPDATE)
                .put("id", UUID.randomUUID().toString())
                .put("updateType", UPDATE_TYPE_DIALOGUE_REQUEST)
                .set("payload", dialogueJson);
        broadcast(Json.str(update));
    }

    private void sendInitialState(WebSocket conn, String requestId) {
        var snapshot = cog.getSystemStateSnapshot();
        var payload = Json.node();

        payload.set("systemStatus", Json.node(new Event.SystemStatusEvent(cog.status, cog.context.kbCount(), cog.context.kbTotalCapacity(), cog.lm.activeLlmTasks.size(), cog.context.ruleCount())));
        payload.set("configuration", Json.node(snapshot.configuration()));

        var notesArray = Json.the.createArrayNode();
        snapshot.notes().stream().map(Json::node).forEach(notesArray::add);
        payload.set("notes", notesArray);

        var assertionsArray = Json.the.createArrayNode();
        snapshot.assertions().stream().map(Json::node).forEach(assertionsArray::add);
        payload.set("assertions", assertionsArray);

        var rulesArray = Json.the.createArrayNode();
        snapshot.rules().stream().map(Json::node).forEach(rulesArray::add);
        payload.set("rules", rulesArray);

        payload.set("tasks", Json.the.createArrayNode());

        ObjectNode update = Json.node()
                .put("type", SIGNAL_TYPE_UPDATE)
                .put("id", UUID.randomUUID().toString())
                .put("updateType", UPDATE_TYPE_INITIAL_STATE)
                .put("inReplyToId", requestId)
                .set("payload", payload);

        if (conn.isOpen()) conn.send(Json.str(update));
    }

    private void sendResponse(WebSocket conn, String inReplyToId, String status, @Nullable JsonNode result, @Nullable String message) {
        var payload = Json.node().put("status", status);
        if (result != null) payload.set("result", result);
        if (message != null) payload.put("message", message);

        ObjectNode update = Json.node()
                .put("type", SIGNAL_TYPE_UPDATE)
                .put("id", UUID.randomUUID().toString())
                .put("updateType", UPDATE_TYPE_RESPONSE)
                .put("inReplyToId", inReplyToId)
                .set("payload", payload);

        if (conn.isOpen()) conn.send(Json.str(update));
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

    private void broadcast(String message) {
        clients.forEach(client -> {
            if (client.isOpen()) client.send(message);
        });
    }
}
