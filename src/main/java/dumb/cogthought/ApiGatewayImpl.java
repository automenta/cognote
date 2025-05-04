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
import static dumb.cogthought.Protocol.*; // Import constants from old Protocol

/**
 * Implementation of the ApiGateway interface.
 * Translates external messages (e.g., JSON over WebSocket) into KB terms (ApiRequest)
 * and potentially translates KB terms (ApiResponse, Event) into outgoing messages.
 */
public class ApiGatewayImpl implements ApiGateway {
    private final KnowledgeBase knowledgeBase;
    private final ObjectMapper mapper;
    // TODO: Add dependency on SystemControl or a dedicated KB monitor to trigger sending outgoing messages

    // TODO: Define Ontology terms for API requests, commands, and responses
    private static final Term API_REQUEST_PREDICATE = Term.Atom.of("ApiRequest"); // Placeholder
    private static final Term API_RESPONSE_PREDICATE = Term.Atom.of("ApiResponse"); // Placeholder
    private static final Term COMMAND_PREDICATE = Term.Atom.of("Command"); // Placeholder

    public ApiGatewayImpl(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = requireNonNull(knowledgeBase);
        this.mapper = Json.the;
        info("ApiGateway initialized.");
    }

    /**
     * Handles an incoming message string, parses it, and asserts it as an ApiRequest term into the KB.
     *
     * @param message The incoming message string (e.g., JSON).
     * @return A CompletableFuture that completes when the message has been processed (asserted into KB).
     */
    @Override
    public CompletableFuture<Void> handleIncomingMessage(String message) {
        info("Received incoming message: " + message);
        try {
            JsonNode root = mapper.readTree(message);
            if (root.isObject()) {
                ObjectNode msgNode = (ObjectNode) root;
                String messageType = msgNode.has("type") ? msgNode.get("type").asText() : null;
                String command = msgNode.has("command") ? msgNode.get("command").asText() : null;
                String requestId = msgNode.has("requestId") ? msgNode.get("requestId").asText() : Cog.id("api-req:");

                if ("command".equals(messageType) && command != null) {
                    Term commandTerm = null;

                    // Translate known commands into Term structures
                    switch (command) {
                        case COMMAND_ASSERT_KIF:
                            if (msgNode.has("kif")) {
                                String kifString = msgNode.get("kif").asText();
                                try {
                                    Term parsedKif = Logic.KifParser.parse(kifString);
                                    if (parsedKif instanceof Term.Lst kifList) {
                                        // Command term: (AssertKif <kif-list>)
                                        commandTerm = new Term.Lst(List.of(Term.Atom.of("AssertKif"), kifList));
                                    } else {
                                        warn("Received assertKif command with non-list KIF: " + kifString);
                                    }
                                } catch (Logic.KifParser.ParseException e) {
                                    warn("Failed to parse KIF from incoming message: " + kifString + " Error: " + e.getMessage());
                                }
                            } else {
                                warn("Received assertKif command without 'kif' field.");
                            }
                            break;
                        case COMMAND_RUN_TOOL:
                            if (msgNode.has("toolName") && msgNode.has("parameters")) {
                                String toolName = msgNode.get("toolName").asText();
                                JsonNode paramsNode = msgNode.get("parameters");
                                // TODO: Implement robust JSON to Term conversion
                                // For now, represent parameters as a string or basic term
                                Term paramsTerm = Term.Str.of(paramsNode.toString()); // Placeholder conversion
                                // Command term: (RunTool <tool-name> <parameters-term>)
                                commandTerm = new Term.Lst(List.of(Term.Atom.of("RunTool"), Term.Atom.of(toolName), paramsTerm));
                            } else {
                                warn("Received runTool command without 'toolName' or 'parameters' fields.");
                            }
                            break;
                        case COMMAND_RUN_QUERY:
                             if (msgNode.has("queryType") && msgNode.has("pattern")) {
                                 String queryType = msgNode.get("queryType").asText();
                                 String patternString = msgNode.get("pattern").asText();
                                 try {
                                     Term patternTerm = Logic.KifParser.parse(patternString);
                                     // Command term: (RunQuery <query-type> <pattern-term>)
                                     commandTerm = new Term.Lst(List.of(Term.Atom.of("RunQuery"), Term.Atom.of(queryType), patternTerm));
                                     // TODO: Include optional parameters like maxDepth
                                 } catch (Logic.KifParser.ParseException e) {
                                     warn("Failed to parse pattern from query command: " + patternString + " Error: " + e.getMessage());
                                 }
                             } else {
                                 warn("Received query command without 'queryType' or 'pattern' fields.");
                             }
                             break;
                        // TODO: Add cases for other commands like COMMAND_ADD_NOTE, COMMAND_UPDATE_NOTE, etc.
                        default:
                            warn("Received unhandled command: " + command);
                            // Command term: (UnknownCommand <command-name> <full-message-json-string>)
                            commandTerm = new Term.Lst(List.of(Term.Atom.of("UnknownCommand"), Term.Atom.of(command), Term.Str.of(message)));
                            break;
                    }

                    if (commandTerm != null) {
                        // Create the ApiRequest term: (ApiRequest <requestId> <commandTerm>)
                        Term apiRequestTerm = new Term.Lst(List.of(API_REQUEST_PREDICATE, Term.Atom.of(requestId), commandTerm));

                        // Assert the ApiRequest term into the KB
                        // TODO: Define a specific KB ID for API requests in Ontology/Config
                        String apiKbId = "api-inbox"; // Placeholder KB ID
                        knowledgeBase.saveAssertion(new Assertion(
                            Cog.id(Cog.ID_PREFIX_ASSERTION),
                            (Term.Lst) apiRequestTerm,
                            0.9, // High priority for API requests? Needs Ontology/Config
                            java.time.Instant.now().toEpochMilli(), // Use epoch milliseconds
                            null, // Source Note ID? Maybe a 'User' note ID?
                            java.util.Collections.emptySet(),
                            Assertion.Type.GROUND,
                            false, false, false,
                            java.util.Collections.emptyList(),
                            0, true, // Active by default
                            apiKbId // KB ID
                        ));
                        info("Asserted ApiRequest into KB: " + apiRequestTerm.toKif());
                        // SystemControl will monitor the KB for these ApiRequest terms.
                    }

                } else {
                    warn("Received unhandled message format or type: " + message);
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

    /**
     * Sends an outgoing message string to the connected client(s).
     * This method is called by other system components (e.g., SystemControl, Tools)
     * when an ApiResponse or Event term is generated in the KB that needs to be sent out.
     *
     * @param message The outgoing message string (e.g., JSON).
     */
    @Override
    public void sendOutgoingMessage(String message) {
        info("Sending outgoing message: " + message);
        // TODO: Implement actual sending logic (e.g., via WebSocket connection)
        // This implementation is a placeholder.
    }

    // TODO: Add a mechanism (likely in SystemControl) to monitor the KB for
    // ApiResponse and Event terms and call sendOutgoingMessage to send them.
    // This would involve querying the KB for terms like (ApiResponse <requestId> <responseContent>)
    // or (Event <EventDetails>) that haven't been sent yet, converting them to JSON,
    // sending them, and marking them as sent in the KB.
}
