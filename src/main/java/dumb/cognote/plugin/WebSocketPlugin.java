package dumb.cognote.plugin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dumb.cognote.*;
import dumb.cognote.util.Events;
import dumb.cognote.util.Json;
import dumb.cognote.util.KifParser;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

import static dumb.cognote.Cog.INPUT_ASSERTION_BASE_PRIORITY;
import static dumb.cognote.Cog.MAX_WS_PARSE_PREVIEW;
import static dumb.cognote.Protocol.*;
import static dumb.cognote.Term.Lst;
import static dumb.cognote.util.Log.error;
import static dumb.cognote.util.Log.message;
import static dumb.cognote.util.Log.warning;

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
                case SIGNAL_TYPE_COMMAND -> handleCommandSignal(conn, id, payload); // Handle command signals
                case SIGNAL_TYPE_INITIAL_STATE_REQUEST -> sendInitialState(conn);
                case SIGNAL_TYPE_DIALOGUE_RESPONSE -> handleDialogueResponse(conn, id, inReplyToId, payload);
                case SIGNAL_TYPE_UI_ACTION -> handleUiActionSignal(conn, id, payload); // Handle UI Action signal
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

    // Added handler for command signals
    private void handleCommandSignal(WebSocket conn, String signalId, JsonNode payload) {
        var commandNameNode = payload.get("command");
        var parametersNode = payload.get("parameters");

        if (commandNameNode == null || !commandNameNode.isTextual() || parametersNode == null || !parametersNode.isObject()) {
            sendFailureResponse(conn, signalId, "Invalid COMMAND signal: missing 'command' (string) or 'parameters' (object).");
            return;
        }

        var commandName = commandNameNode.asText();
        // For now, just log and acknowledge. Actual command execution logic
        // would need to be integrated here or routed to a command processor.
        // The REPL's /query, /tool, /wait, /retract commands are currently
        // handled by the RequestProcessorPlugin which listens for assertions
        // in kb://client-input. This handler is for future direct commands.
        message(String.format("Received COMMAND signal: %s with params %s", commandName, parametersNode.toString()));

        // Example: Route to RequestProcessorPlugin by asserting into kb://client-input
        // This is a simplified example; a proper command handler might be better.
        // The REPL's /query, /tool, /wait, /retract commands are currently
        // handled by the RequestProcessorPlugin which listens for assertions
        // in kb://client-input. This handler is for future direct commands.
        // Given the REPL code, it seems it *does* send SIGNAL_TYPE_COMMAND.
        // So, we need to parse the command and parameters and assert the request.

        try {
            Term.Lst requestTerm = null;
            String sourceId = "client:" + conn.getRemoteSocketAddress().toString();
            String noteId = null; // Commands might not have a specific note context

            switch (commandName) {
                case "query":
                    var patternNode = parametersNode.get("pattern");
                    if (patternNode == null || !patternNode.isTextual()) throw new IllegalArgumentException("Missing 'pattern' for query command.");
                    var pattern = KifParser.parseKif(patternNode.asText()).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Invalid KIF pattern for query."));
                    if (!(pattern instanceof Term.Lst)) throw new IllegalArgumentException("Query pattern must be a KIF list.");
                    // Corrected: Convert JsonNode parameters to Term
                    requestTerm = new Term.Lst(Term.Atom.of(Protocol.PRED_REQUEST), Term.Atom.of("query"), pattern, jsonNodeToTerm(parametersNode));
                    break;
                case "runTool":
                    var toolNameNode = parametersNode.get("name");
                     if (toolNameNode == null || !toolNameNode.isTextual()) throw new IllegalArgumentException("Missing 'name' for runTool command.");
                    // Corrected: Convert JsonNode parameters to Term
                    requestTerm = new Term.Lst(Term.Atom.of(Protocol.PRED_REQUEST), Term.Atom.of("runTool"), Term.Atom.of(toolNameNode.asText()), jsonNodeToTerm(parametersNode));
                    break;
                case "wait":
                    var conditionNode = parametersNode.get("condition"); // Assuming condition is passed as a KIF string in params
                     if (conditionNode == null || !conditionNode.isTextual()) throw new IllegalArgumentException("Missing 'condition' for wait command.");
                    var conditionTerm = KifParser.parseKif(conditionNode.asText()).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Invalid KIF condition for wait."));
                    if (!(conditionTerm instanceof Term.Lst)) throw new IllegalArgumentException("Wait condition must be a KIF list.");
                    // Corrected: Convert JsonNode parameters to Term
                    requestTerm = new Term.Lst(Term.Atom.of(Protocol.PRED_REQUEST), Term.Atom.of("wait"), conditionTerm, jsonNodeToTerm(parametersNode));
                    break;
                 case "retract":
                    var retractTypeNode = parametersNode.get("type");
                    var retractTargetNode = parametersNode.get("target");
                    if (retractTypeNode == null || !retractTypeNode.isTextual() || retractTargetNode == null || !retractTargetNode.isTextual()) throw new IllegalArgumentException("Missing 'type' or 'target' for retract command.");
                    var retractType = retractTypeNode.asText();
                    var retractTarget = retractTargetNode.asText();
                    // Retract command is handled directly by RetractionPlugin listening for RetractionRequestEvent
                    // We should emit that event here, not assert a request term.
                    Logic.RetractionType typeEnum;
                    try {
                        typeEnum = Logic.RetractionType.valueOf(retractType.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Invalid retraction type: " + retractType);
                    }
                    events.emit(new Event.RetractionRequestEvent(retractTarget, typeEnum, sourceId, noteId));
                    sendSuccessResponse(conn, signalId, null, "Retraction request emitted.");
                    return; // Handled directly, no assertion needed
                 case "cancelDialogue":
                    var dialogueIdNode = parametersNode.get("dialogueId");
                    if (dialogueIdNode == null || !dialogueIdNode.isTextual()) throw new IllegalArgumentException("Missing 'dialogueId' for cancelDialogue command.");
                    cog.dialogue.cancel(dialogueIdNode.asText());
                    sendSuccessResponse(conn, signalId, null, "Dialogue cancellation requested.");
                    return; // Handled directly, no assertion needed

                default:
                    throw new IllegalArgumentException("Unsupported command: " + commandName);
            }

            if (requestTerm != null) {
                 var potentialAssertion = new Assertion.PotentialAssertion(
                        requestTerm,
                        INPUT_ASSERTION_BASE_PRIORITY, // Or a specific command priority
                        Set.of(),
                        sourceId,
                        false, false, false,
                        KB_CLIENT_INPUT, // Assert into the client input KB
                        Logic.AssertionType.GROUND,
                        List.of(),
                        0
                );
                context.tryCommit(potentialAssertion, sourceId);
                sendSuccessResponse(conn, signalId, null, "Command asserted as request: " + requestTerm.toKif());
            } else {
                 sendFailureResponse(conn, signalId, "Command did not result in an assertion.");
            }


        } catch (Exception e) {
            error("Error processing COMMAND signal: " + e.getMessage());
            e.printStackTrace();
            sendErrorResponse(conn, signalId, "Error processing command: " + e.getMessage());
        }
    }


    // Added handler for UI Action signals
    private void handleUiActionSignal(WebSocket conn, String signalId, JsonNode payload) {
        var actionTypeNode = payload.get("actionType");
        var actionDataNode = payload.get("actionData");

        if (actionTypeNode == null || !actionTypeNode.isTextual() || actionDataNode == null) {
            sendFailureResponse(conn, signalId, "Invalid UI_ACTION signal: missing 'actionType' (string) or 'actionData'.");
            return;
        }

        var actionType = actionTypeNode.asText();
        var actionData = actionDataNode; // Keep as JsonNode

        try {
            // Create a KIF term representing the UI action
            var uiActionTerm = new Term.Lst(
                    Term.Atom.of(Protocol.PRED_UI_ACTION),
                    Term.Atom.of(actionType),
                    jsonNodeToTerm(actionData) // Convert actionData JsonNode to Term
            );

            // Assert this term into the dedicated KB for UI actions
            var potentialAssertion = new Assertion.PotentialAssertion(
                    uiActionTerm,
                    INPUT_ASSERTION_BASE_PRIORITY, // Or a specific UI action priority
                    Set.of(),
                    "client:" + conn.getRemoteSocketAddress().toString(),
                    false, false, false,
                    Protocol.KB_UI_ACTIONS, // Assert into the UI Actions KB
                    Logic.AssertionType.GROUND,
                    List.of(),
                    0
            );

            context.tryCommit(potentialAssertion, "websocket-ui-action");

            sendSuccessResponse(conn, signalId, null, "UI action asserted: " + uiActionTerm.toKif());

        } catch (Exception e) {
            error("Error processing UI_ACTION signal: " + e.getMessage());
            e.printStackTrace();
            sendErrorResponse(conn, signalId, "Error processing UI action: " + e.getMessage());
        }
    }

    // Helper method to convert JsonNode to Term
    private Term jsonNodeToTerm(JsonNode node) {
        if (node == null || node.isNull()) {
            return Term.Atom.of("null"); // Represent JSON null as KIF atom 'null'
        } else if (node.isBoolean()) {
            return Term.Atom.of(String.valueOf(node.asBoolean())); // Represent JSON boolean as KIF atom 'true' or 'false'
        } else if (node.isNumber()) {
            // Represent JSON number as KIF atom (string representation)
            return Term.Atom.of(node.asText());
        } else if (node.isTextual()) {
            // Represent JSON string as KIF atom (quoted if necessary, handled by Term.Atom.of)
            return Term.Atom.of(node.asText());
        } else if (node.isArray()) {
            // Represent JSON array as KIF list
            List<Term> elements = new ArrayList<>();
            for (JsonNode element : node) {
                elements.add(jsonNodeToTerm(element)); // Recursively convert array elements
            }
            return new Term.Lst(elements);
        } else if (node.isObject()) {
            // Represent JSON object as KIF list of (key value) pairs
            List<Term> pairs = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                Term keyTerm = Term.Atom.of(field.getKey()); // JSON key becomes KIF atom
                Term valueTerm = jsonNodeToTerm(field.getValue()); // JSON value is converted recursively
                pairs.add(new Term.Lst(keyTerm, valueTerm)); // Create (key value) pair list
            }
            return new Term.Lst(pairs); // The object becomes a list of these pairs
        } else {
            // Handle other JSON node types if necessary, or default
            warning("Unsupported JsonNode type for conversion to Term: " + node.getNodeType());
            return Term.Atom.of("unsupported_json_type");
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
                            // Response handled, future will complete
                            // We could wait for the future here and send a success/failure response
                            // based on its outcome, but for now, just handling the response is enough.
                            // The original request that triggered the dialogue will get its result
                            // when the dialogue future completes.
                            sendSuccessResponse(conn, responseId, null, "Dialogue response processed.");
                        },
                        () -> sendErrorResponse(conn, responseId, "No pending dialogue request found for ID: " + dialogueId)
                );
    }

    private void broadcastEvent(Event event) {
        // Filter out events that are handled by specific broadcast methods
        if (event instanceof Answer.AnswerEvent || event instanceof Event.TaskUpdateEvent || event instanceof Events.DialogueRequestEvent || event instanceof Events.LogMessageEvent) {
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
        payload.set("tasks", Json.the.createArrayNode()); // Tasks are not part of snapshot currently

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
