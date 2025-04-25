package dumb.cognote.plugin;

import dumb.cognote.*;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.jetbrains.annotations.Nullable;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNullElse;
import static java.util.Optional.ofNullable;

public class WebSocketPlugin extends Plugin.BasePlugin {
    private final MyWebSocketServer websocket;
    private final Map<String, CommandHandler> commandHandlers = new HashMap<>();

    public WebSocketPlugin(InetSocketAddress addr, Cog cog) {
        this.websocket = new MyWebSocketServer(addr, cog);
    }

    @Override
    public void start(Events ev, Logic.Cognition ctx) {
        super.start(ev, ctx);

        // Register Command Handlers
        var c = ctx.cog;
        registerCommandHandler("add", new AddKifHandler(c));
        registerCommandHandler("retract", new RetractHandler(c));
        registerCommandHandler("query", new QueryHandler(c));
        registerCommandHandler("pause", new PauseHandler(c));
        registerCommandHandler("unpause", new UnpauseHandler(c));
        registerCommandHandler("get_status", new GetStatusHandler(c));
        registerCommandHandler("clear", new ClearHandler(c));
        registerCommandHandler("get_config", new GetConfigHandler(c));
        registerCommandHandler("set_config", new SetConfigHandler(c));
        // Add more handlers for other tools/commands as needed

        // Register Event Listeners for Broadcasting
        ev.on(Cog.AssertedEvent.class, e -> sendEvent("assertion_added", assertionToJson(e.assertion(), e.kbId())));
        ev.on(Cog.RetractedEvent.class, e -> sendEvent("assertion_removed", assertionToJson(e.assertion(), e.kbId()).put("reason", e.reason())));
        ev.on(Cog.AssertionEvictedEvent.class, e -> sendEvent("assertion_evicted", assertionToJson(e.assertion(), e.kbId())));
        ev.on(Cog.AssertionStateEvent.class, e -> sendEvent("assertion_state_changed", new JSONObject().put("id", e.assertionId()).put("isActive", e.isActive()).put("kbId", e.kbId())));
        ev.on(Cog.RuleAddedEvent.class, e -> sendEvent("rule_added", ruleToJson(e.rule())));
        ev.on(Cog.RuleRemovedEvent.class, e -> sendEvent("rule_removed", ruleToJson(e.rule())));
        ev.on(Cog.TaskUpdateEvent.class, e -> sendEvent("task_update", taskUpdateToJson(e)));
        ev.on(Cog.SystemStatusEvent.class, e -> sendEvent("system_status", systemStatusToJson(e)));
//        ev.on(WebSocketBroadcastEvent.class, e -> safeBroadcast(e.message())); // Keep for raw messages if needed

        // Broadcast external input if configured
        if (c.broadcastInputAssertions) {
            ev.on(Cog.ExternalInputEvent.class, this::onExternalInput);
        }

        websocket.start();
    }

    @Override
    public void stop() {
        try {
            websocket.stop(MyWebSocketServer.WS_STOP_TIMEOUT_MS);
            System.out.println("WebSocket server stopped.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted while stopping WebSocket server.");
        } catch (Exception e) {
            System.err.println("Error stopping WebSocket server: " + e.getMessage());
        }
    }

    private void registerCommandHandler(String command, CommandHandler handler) {
        commandHandlers.put(command, handler);
    }

    private void onExternalInput(Cog.ExternalInputEvent event) {
        // Only broadcast if it's a list term (potential assertion/rule/goal)
        if (event.term() instanceof Term.Lst list) {
            sendEvent("external_input", new JSONObject()
                    .put("sourceId", event.sourceId())
                    .put("noteId", requireNonNullElse(event.noteId(), JSONObject.NULL))
                    .put("kif", list.toKif()));
        }
    }

    private void sendEvent(String eventType, JSONObject payload) {
        JSONObject eventMessage = new JSONObject();
        eventMessage.put("type", "event");
        eventMessage.put("event", eventType);
        eventMessage.put("payload", payload);
        safeBroadcast(eventMessage.toString());
    }

    private void sendResponse(WebSocket conn, String command, @Nullable String requestId, JSONObject payload, @Nullable JSONObject error) {
        JSONObject responseMessage = new JSONObject();
        responseMessage.put("type", "response");
        responseMessage.put("command", command);
        if (requestId != null) {
            responseMessage.put("id", requestId);
        }
        if (payload != null) {
            responseMessage.put("payload", payload);
        }
        if (error != null) {
            responseMessage.put("error", error);
        }
        conn.send(responseMessage.toString());
    }

    private void safeBroadcast(String message) {
        try {
            if (!websocket.getConnections().isEmpty()) websocket.broadcast(message);
        } catch (Exception e) {
            if (!(e instanceof ConcurrentModificationException || ofNullable(e.getMessage()).map(m -> m.contains("closed") || m.contains("reset") || m.contains("Broken pipe")).orElse(false)))
                System.err.println("Error during WebSocket broadcast: " + e.getMessage());
        }
    }

    // --- JSON Payload Helpers ---

    private JSONObject assertionToJson(Assertion assertion, String kbId) {
        return new JSONObject()
                .put("id", assertion.id())
                .put("kif", assertion.toKifString())
                .put("priority", assertion.pri())
                .put("timestamp", assertion.timestamp())
                .put("sourceNoteId", requireNonNullElse(assertion.sourceNoteId(), JSONObject.NULL))
                .put("type", assertion.type().name())
                .put("isEqual", assertion.isEquality())
                .put("isNegative", assertion.negated())
                .put("isOriented", assertion.isOrientedEquality())
                .put("derivationDepth", assertion.derivationDepth())
                .put("isActive", assertion.isActive())
                .put("kbId", kbId);
    }

    private JSONObject ruleToJson(Rule rule) {
        return new JSONObject()
                .put("id", rule.id())
                .put("kif", rule.form().toKif())
                .put("priority", rule.pri())
                .put("sourceNoteId", requireNonNullElse(rule.sourceNoteId(), JSONObject.NULL));
    }

    private JSONObject taskUpdateToJson(Cog.TaskUpdateEvent event) {
        return new JSONObject()
                .put("taskId", event.taskId())
                .put("status", event.status().name())
                .put("content", event.content());
    }

    private JSONObject systemStatusToJson(Cog.SystemStatusEvent event) {
        return new JSONObject()
                .put("statusMessage", event.statusMessage())
                .put("kbCount", event.kbCount())
                .put("kbCapacity", event.kbCapacity())
                .put("taskQueueSize", event.taskQueueSize())
                .put("ruleCount", event.ruleCount());
    }


    // --- Command Handling Interface and Implementations ---

    private interface CommandHandler {
        CompletableFuture<JSONObject> handle(JSONObject payload, WebSocket conn);
    }

    private record AddKifHandler(Cog cog) implements CommandHandler {

        @Override
        public CompletableFuture<JSONObject> handle(JSONObject payload, WebSocket conn) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    String kif = payload.getString("kif");
                    String noteId = payload.optString("noteId", null);
                    String sourceId = "ws:" + conn.getRemoteSocketAddress().toString();

                    // Use the existing ExternalInputEvent mechanism
                    KifParser.parseKif(kif).forEach(term ->
                            cog.events.emit(new Cog.ExternalInputEvent(term, sourceId, noteId))
                    );

                    return new JSONObject().put("status", "success").put("message", "KIF submitted for processing.");
                } catch (JSONException e) {
                    throw new IllegalArgumentException("Invalid payload for 'add' command: Missing 'kif' or invalid format.", e);
                } catch (KifParser.ParseException e) {
                    throw new IllegalArgumentException("Failed to parse KIF: " + e.getMessage(), e);
                } catch (Exception e) {
                    throw new RuntimeException("Internal error processing 'add' command: " + e.getMessage(), e);
                }
            }, cog.mainExecutor); // Execute asynchronously
        }
    }

    private record RetractHandler(Cog cog) implements CommandHandler {

        @Override
        public CompletableFuture<JSONObject> handle(JSONObject payload, WebSocket conn) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    String target = payload.getString("target"); // Assertion ID, Note ID, or Rule KIF
                    String typeStr = payload.optString("type", "BY_ID"); // BY_ID, BY_NOTE, BY_RULE_FORM
                    Logic.RetractionType type;
                    try {
                        type = Logic.RetractionType.valueOf(typeStr.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Invalid retraction type: " + typeStr + ". Must be BY_ID, BY_NOTE, or BY_RULE_FORM.");
                    }
                    String noteId = payload.optString("noteId", null); // Relevant for BY_NOTE
                    String sourceId = "ws:" + conn.getRemoteSocketAddress().toString();

                    cog.events.emit(new Cog.RetractionRequestEvent(target, type, sourceId, noteId));

                    return new JSONObject().put("status", "success").put("message", "Retraction request submitted.");
                } catch (JSONException e) {
                    throw new IllegalArgumentException("Invalid payload for 'retract' command: Missing 'target' or invalid format.", e);
                } catch (IllegalArgumentException e) {
                    throw e; // Re-throw specific validation errors
                } catch (Exception e) {
                    throw new RuntimeException("Internal error processing 'retract' command: " + e.getMessage(), e);
                }
            }, cog.mainExecutor);
        }
    }

    private record QueryHandler(Cog cog) implements CommandHandler {

        @Override
        public CompletableFuture<JSONObject> handle(JSONObject payload, WebSocket conn) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    String kifPattern = payload.getString("kif_pattern");
                    String typeStr = payload.optString("type", "ASK_BINDINGS"); // ASK_BINDINGS, ASK_TRUE_FALSE, ACHIEVE_GOAL
                    Cog.QueryType type;
                    try {
                        type = Cog.QueryType.valueOf(typeStr.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Invalid query type: " + typeStr + ". Must be ASK_BINDINGS, ASK_TRUE_FALSE, or ACHIEVE_GOAL.");
                    }
                    String targetKbId = payload.optString("targetKbId", null);
                    JSONObject paramsJson = payload.optJSONObject("parameters");
                    Map<String, Object> parameters = new HashMap<>();
                    if (paramsJson != null) {
                        paramsJson.keySet().forEach(key -> parameters.put(key, paramsJson.get(key)));
                    }

                    Term pattern = KifParser.parseKif(kifPattern).stream()
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("Failed to parse KIF pattern: No term found."));

                    String queryId = Cog.id(Cog.ID_PREFIX_QUERY);
                    Cog.Query query = new Cog.Query(queryId, type, pattern, targetKbId, parameters);

                    // Use the synchronous query method for simplicity over WS
                    Cog.Answer answer = cog.querySync(query);

                    JSONObject resultPayload = new JSONObject();
                    resultPayload.put("queryId", answer.query());
                    resultPayload.put("status", answer.status().name());
                    if (answer.explanation() != null) {
                        resultPayload.put("explanation", answer.explanation().details());
                    }

                    var b = answer.bindings();
                    if (b != null && !b.isEmpty()) {
                        var bindingsArray = new org.json.JSONArray();
                        for (Map<Term.Var, Term> bindingSet : b) {
                            var bindingJson = new JSONObject();
                            bindingSet.forEach((var, term) -> bindingJson.put(var.name(), term.toKif()));
                            bindingsArray.put(bindingJson);
                        }
                        resultPayload.put("bindings", bindingsArray);
                    } else {
                        resultPayload.put("bindings", new org.json.JSONArray()); // Ensure bindings is always an array
                    }

                    return resultPayload;
                } catch (JSONException e) {
                    throw new IllegalArgumentException("Invalid payload for 'query' command: Missing 'kif_pattern' or invalid format.", e);
                } catch (KifParser.ParseException e) {
                    throw new IllegalArgumentException("Failed to parse KIF pattern: " + e.getMessage(), e);
                } catch (IllegalArgumentException e) {
                    throw e; // Re-throw specific validation errors
                } catch (RuntimeException e) {
                    // querySync can throw RuntimeException (Timeout, ExecutionException)
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException("Internal error processing 'query' command: " + e.getMessage(), e);
                }
            }, cog.mainExecutor);
        }
    }

    private record PauseHandler(Cog cog) implements CommandHandler {
        @Override
        public CompletableFuture<JSONObject> handle(JSONObject payload, WebSocket conn) {
            return CompletableFuture.supplyAsync(() -> {
                cog.setPaused(true);
                return new JSONObject().put("status", "success").put("message", "System paused.");
            }, cog.mainExecutor);
        }
    }

    private record UnpauseHandler(Cog cog) implements CommandHandler {

        @Override
        public CompletableFuture<JSONObject> handle(JSONObject payload, WebSocket conn) {
            return CompletableFuture.supplyAsync(() -> {
                cog.setPaused(false);
                return new JSONObject().put("status", "success").put("message", "System unpaused.");
            }, cog.mainExecutor);
        }
    }

    private record ClearHandler(Cog cog) implements CommandHandler {

        @Override
        public CompletableFuture<JSONObject> handle(JSONObject payload, WebSocket conn) {
            return CompletableFuture.supplyAsync(() -> {
                cog.clear();
                return new JSONObject().put("status", "success").put("message", "Knowledge base cleared.");
            }, cog.mainExecutor);
        }
    }

    private record GetConfigHandler(Cog cog) implements CommandHandler {

        @Override
        public CompletableFuture<JSONObject> handle(JSONObject payload, WebSocket conn) {
            return CompletableFuture.completedFuture(new Cog.Configuration(cog).toJson());
        }
    }

    private class GetStatusHandler implements CommandHandler {
        private final Cog cog;

        GetStatusHandler(Cog cog) {
            this.cog = cog;
        }

        @Override
        public CompletableFuture<JSONObject> handle(JSONObject payload, WebSocket conn) {
            return CompletableFuture.completedFuture(systemStatusToJson(
                    new Cog.SystemStatusEvent(cog.status, cog.context.kbCount(), cog.context.kbTotalCapacity(), cog.lm.activeLlmTasks.size(), cog.context.ruleCount())
            ));
        }
    }

    private class SetConfigHandler implements CommandHandler {
        private final Cog cog;

        SetConfigHandler(Cog cog) {
            this.cog = cog;
        }

        @Override
        public CompletableFuture<JSONObject> handle(JSONObject payload, WebSocket conn) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    if (payload.has("llmApiUrl")) cog.lm.llmApiUrl = payload.getString("llmApiUrl");
                    if (payload.has("llmModel")) cog.lm.llmModel = payload.getString("llmModel");
                    if (payload.has("globalKbCapacity")) cog.globalKbCapacity = payload.getInt("globalKbCapacity");
                    if (payload.has("reasoningDepthLimit"))
                        cog.reasoningDepthLimit = payload.getInt("reasoningDepthLimit");
                    if (payload.has("broadcastInputAssertions")) {
                        boolean broadcast = payload.getBoolean("broadcastInputAssertions");
                        if (cog.broadcastInputAssertions != broadcast) {
                            cog.broadcastInputAssertions = broadcast;
                            // Re-register/unregister the listener based on the new value
                            Consumer<Cog.ExternalInputEvent> i = WebSocketPlugin.this::onExternalInput;
                            if (broadcast) {
                                cog.events.on(Cog.ExternalInputEvent.class, i);
                            } else {
                                cog.events.off(Cog.ExternalInputEvent.class, i);
                            }
                        }
                    }

                    // Optionally save config to note-config here if persistence is implemented

                    return new JSONObject().put("status", "success").put("message", "Configuration updated.").put("config",
                            new Cog.Configuration(cog).toJson());
                } catch (JSONException e) {
                    throw new IllegalArgumentException("Invalid payload for 'set_config' command: Invalid format.", e);
                } catch (Exception e) {
                    throw new RuntimeException("Internal error processing 'set_config' command: " + e.getMessage(), e);
                }
            }, cog.mainExecutor);
        }
    }


    class MyWebSocketServer extends WebSocketServer {
        private static final int WS_STOP_TIMEOUT_MS = 1000;
        private static final int WS_CONNECTION_LOST_TIMEOUT_MS = 100;
        private final Cog cog;

        MyWebSocketServer(InetSocketAddress address, Cog cog) {
            super(address);
            this.cog = cog;
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            System.out.println("WS Client connected: " + conn.getRemoteSocketAddress());
            // Optionally send initial status or config on connect
            sendEvent("system_status", systemStatusToJson(
                    new Cog.SystemStatusEvent(cog.status, cog.context.kbCount(), cog.context.kbTotalCapacity(), cog.lm.activeLlmTasks.size(), cog.context.ruleCount())
            ));
            sendEvent("config_updated", new Cog.Configuration(cog).toJson());
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            System.out.println("WS Client disconnected: " + conn.getRemoteSocketAddress() + " Code: " + code + " Reason: " + requireNonNullElse(reason, "N/A"));
        }

        @Override
        public void onStart() {
            System.out.println("System WebSocket listener active on port " + getPort() + ".");
            setConnectionLostTimeout(WS_CONNECTION_LOST_TIMEOUT_MS);
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            var addr = ofNullable(conn).map(WebSocket::getRemoteSocketAddress).map(Object::toString).orElse("server");
            var msg = ofNullable(ex.getMessage()).orElse("");
            if (ex instanceof IOException && (msg.contains("Socket closed") || msg.contains("Connection reset") || msg.contains("Broken pipe")))
                System.err.println("WS Network Info from " + addr + ": " + msg);
            else {
                System.err.println("WS Error from " + addr + ": " + msg);
                ex.printStackTrace();
            }
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            JSONObject requestJson;
            String requestId = null;
            String command = null;

            try {
                requestJson = new JSONObject(message);
                String type = requestJson.optString("type");
                requestId = requestJson.optString("id", null); // Optional request ID
                command = requestJson.optString("command");
                JSONObject payload = requestJson.optJSONObject("payload");

                if (!"command".equals(type) || command.isEmpty()) {
                    sendResponse(conn, command, requestId, null, new JSONObject().put("message", "Invalid message format. Expected type 'command' with a 'command' field.").put("code", "INVALID_FORMAT"));
                    return;
                }

                CommandHandler handler = commandHandlers.get(command);

                if (handler == null) {
                    sendResponse(conn, command, requestId, null, new JSONObject().put("message", "Unknown command: " + command).put("code", "UNKNOWN_COMMAND"));
                    return;
                }

                // Execute handler asynchronously and send response when complete
                String COMMAND = command;
                String REQUEST_ID = requestId;
                handler.handle(payload, conn)
                        .whenComplete((resultPayload, ex) -> {
                            if (ex == null) {
                                sendResponse(conn, COMMAND, REQUEST_ID, resultPayload, null);
                            } else {
                                // Unwrap common exception types
                                Throwable cause = ex;
                                if (cause instanceof CompletionException || cause instanceof ExecutionException) {
                                    cause = cause.getCause();
                                }
                                String errorMessage = ofNullable(cause.getMessage()).orElse("An unexpected error occurred.");
                                String errorCode = "INTERNAL_ERROR"; // Default error code

                                switch (cause) {
                                    case IllegalArgumentException _ -> errorCode = "INVALID_ARGUMENT";
                                    case TimeoutException _ -> {
                                        errorCode = "TIMEOUT";
                                        errorMessage = "Command execution timed out.";
                                    }
                                    case Tool.ToolExecutionException _ -> errorCode = "TOOL_EXECUTION_ERROR";
                                    default -> {
                                    }
                                }
                                // Add more specific error code mappings if needed

                                System.err.println("Error handling command '" + COMMAND + "' from " + conn.getRemoteSocketAddress() + ": " + errorMessage);
                                if (!(cause instanceof IllegalArgumentException || cause instanceof Tool.ToolExecutionException)) { // Don't print stack trace for expected validation errors
                                    cause.printStackTrace();
                                }

                                sendResponse(conn, COMMAND, REQUEST_ID, null, new JSONObject().put("message", errorMessage).put("code", errorCode));
                            }
                        });

            } catch (JSONException e) {
                System.err.printf("WS JSON Parse Error from %s: %s | Original: %s...%n", conn.getRemoteSocketAddress(), e.getMessage(),
                        message.substring(0, Math.min(message.length(), Cog.MAX_WS_PARSE_PREVIEW)));
                sendResponse(conn, command, requestId, null, new JSONObject().put("message", "Failed to parse JSON message: " + e.getMessage()).put("code", "JSON_PARSE_ERROR"));
            } catch (Exception e) {
                System.err.println("Unexpected WS message processing error from " + conn.getRemoteSocketAddress() + ": " + e.getMessage());
                e.printStackTrace();
                sendResponse(conn, command, requestId, null, new JSONObject().put("message", "Internal server error processing message.").put("code", "INTERNAL_ERROR"));
            }
        }
    }

//    public record WebSocketBroadcastEvent(String message) implements Cog.CogEvent {
//    }
}
