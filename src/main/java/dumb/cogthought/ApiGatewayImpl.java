package dumb.cogthought;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dumb.cogthought.util.Json;
import dumb.cogthought.util.Log;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static java.util.Objects.requireNonNull;
import static dumb.cogthought.util.Log.error;
import static dumb.cogthought.util.Log.info;
import static dumb.cogthought.util.Log.warn;

public class ApiGatewayImpl implements ApiGateway {
    private final KnowledgeBase knowledgeBase;
    private final ObjectMapper mapper;
    // TODO: Add dependency on SystemControl to trigger processing of asserted requests

    public ApiGatewayImpl(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = requireNonNull(knowledgeBase);
        this.mapper = Json.the;
        info("ApiGateway initialized.");
    }

    @Override
    public CompletableFuture<Void> handleIncomingMessage(String message) {
        info("Received incoming message: " + message);
        try {
            JsonNode root = mapper.readTree(message);
            if (root.isObject()) {
                ObjectNode msgNode = (ObjectNode) root;
                if (msgNode.has("type") && msgNode.get("type").asText().equals("command") &&
                    msgNode.has("command") && msgNode.get("command").asText().equals("assertKif") &&
                    msgNode.has("kif")) {
                    String kifString = msgNode.get("kif").asText();
                    try {
                        Term kifTerm = Logic.KifParser.parse(kifString);
                        if (kifTerm instanceof Term.Lst kifList) {
                            String requestId = msgNode.has("requestId") ? msgNode.get("requestId").asText() : Cog.id("api-req:");
                            Term apiRequestTerm = new Term.Lst(List.of(
                                Term.Atom.of("ApiRequest"),
                                Term.Atom.of(requestId),
                                new Term.Lst(List.of(
                                    Term.Atom.of("AssertKif"),
                                    kifList
                                ))
                            ));

                            knowledgeBase.saveAssertion(new Assertion(
                                Cog.id(Cog.ID_PREFIX_ASSERTION),
                                (Term.Lst) apiRequestTerm,
                                0.9,
                                java.time.Instant.now(),
                                null,
                                java.util.Collections.emptySet(),
                                Assertion.Type.GROUND,
                                false, false, false,
                                java.util.Collections.emptyList(),
                                0, true,
                                Cog.GLOBAL_KB_NOTE_ID
                            ));
                            info("Asserted ApiRequest into KB: " + apiRequestTerm.toKif());
                            // TODO: Notify SystemControl that a new request is in the KB
                        } else {
                            warn("Received assertKif command with non-list KIF: " + kifString);
                        }
                    } catch (Logic.KifParser.ParseException e) {
                        warn("Failed to parse KIF from incoming message: " + kifString + " Error: " + e.getMessage());
                    }
                } else {
                    warn("Received unhandled message format or command: " + message);
                }
            } else {
                warn("Received non-object JSON message: " + message);
            }
        } catch (Exception e) {
            error("Error handling incoming message: " + e.getMessage());
            e.printStackTrace();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void sendOutgoingMessage(String message) {
        info("Sending outgoing message: " + message);
    }
}
