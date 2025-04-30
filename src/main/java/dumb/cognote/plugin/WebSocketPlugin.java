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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

import static dumb.cognote.Cog.INPUT_ASSERTION_BASE_PRIORITY;
import static dumb.cognote.Cog.MAX_WS_PARSE_PREVIEW;
import static dumb.cognote.Log.error;
import static dumb.cognote.Log.message;
import static dumb.cognote.Protocol.*;
import static dumb.cognote.Term.Lst;

public class WebSocketPlugin extends Plugin.BasePlugin {

    private final InetSocketAddress address;
    private final Set<WebSocket> clients = new CopyOnWriteArraySet<>();
    private WebSocketServer server;

    public WebSocketPlugin(InetSocketAddress address, Cog cog) {
        this.address = address;
        this.cog = cog;
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
        events.on(Cog.NoteStatusEvent.class, this::broadcastEvent);
        events.on(Truths.ContradictionDetectedEvent.class, this::broadcastEvent);
        events.on(Events.LogMessageEvent.class, this::broadcastEvent);
        events.on(Event.AssertedEvent.class, this::broadcastEvent);
        events.on(Event.RetractedEvent.class, this::broadcastEvent);
        events.on(Event.RuleAddedEvent.class, this::broadcastEvent);
        events.on(Event.RuleRemovedEvent.class, this::broadcastEvent);
        events.on(Event.AssertionStateEvent.class, this::broadcastEvent);
        events.on(Event.AssertionEvictedEvent.class, this::broadcastEvent);
        events.on(Event.TemporaryAssertionEvent.class, this::broadcastEvent);
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
                case SIGNAL_TYPE_INPUT -> handleInputSignal(conn, id, payload);
                case SIGNAL_TYPE_INITIAL_STATE_REQUEST -> sendInitialState(conn);
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

    private void handleInputSignal(WebSocket conn, String signalId, JsonNode payload) {
        var kifStringsNode = payload.get("kifStrings");
        var sourceIdNode = payload.get("sourceId");
        var noteIdNode = payload.get("noteId");

        if (kifStringsNode == null || !kifStringsNode.isArray()) {
            sendFailureResponse(conn, signalId, "Invalid INPUT signal: missing or invalid 'kifStrings' (array of strings).");
            return;
        }

        var sourceId = sourceIdNode != null && sourceIdNode.isTextual() ? sourceIdNode.asText() : "client:" + conn.getRemoteSocketAddress().toString();
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
                    if (term instanceof Lst termList) {
                        var potentialAssertion = new Assertion.PotentialAssertion(
                                termList,
                                INPUT_ASSERTION_BASE_PRIORITY,
                                Set.of(),
                                sourceId,
                                false, false, false,
                                noteId,
                                Logic.AssertionType.GROUND,
                                List.of(),
                                0
                        );
                        context.tryCommit(potentialAssertion, sourceId);
                        successCount++;
                    } else {
                        errorMessages.append("Input term is not a list: ").append(term.toKif()).append("; ");
                    }
                }
            } catch (KifParser.ParseException e) {
                errorMessages.append("Failed to parse KIF '").append(kifString).append("': ").append(e.getMessage()).append("; ");
            } catch (Exception e) {
                errorMessages.append("Error processing KIF '").append(kifString).append("': ").append(e.getMessage()).append("; ");
                error("Error processing input KIF: " + e.getMessage());
                e.printStackTrace();
            }
        }

        if (!errorMessages.isEmpty()) {
            sendFailureResponse(conn, signalId, "Processed " + successCount + " terms with errors: " + errorMessages);
        } else {
            sendSuccessResponse(conn, signalId, null, "Successfully processed and asserted " + successCount + " terms.");
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
                        future -> {
                        },
                        () -> sendErrorResponse(conn, responseId, "No pending dialogue request found for ID: " + dialogueId)
                );
    }

    private void broadcastEvent(Event event) {
        if (event instanceof Answer.AnswerEvent || event instanceof Event.TaskUpdateEvent || event instanceof Events.DialogueRequestEvent) {
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
        var snapshot = cog.getSystemStateSnapshot();
        var payload = Json.node();

        payload.set("systemStatus", Json.node(new Event.SystemStatusEvent(cog.status, cog.context.kbCount(), cog.context.kbTotalCapacity(), cog.lm.activeLlmTasks.size(), cog.context.ruleCount())));
        payload.set("configuration", Json.node(snapshot.configuration()));

        var notesArray = Json.the.createArrayNode();
        snapshot.notes().stream().map(Json::node).forEach(notesArray::add);

        var assertionsArray = Json.the.createArrayNode();
        snapshot.assertions().stream().map(Json::node).forEach(assertionsArray::add);

        var rulesArray = Json.the.createArrayNode();
        snapshot.rules().stream().map(Json::node).forEach(rulesArray::add);

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
}
