package dumb.cogthought;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dumb.cogthought.util.Json;
import dumb.cogthought.util.Log;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static java.util.Objects.requireNonNull;
import static dumb.cogthought.util.Log.error;
import static dumb.cogthought.util.Log.info;
import static dumb.cogthought.util.Log.warn;
import static dumb.cogthought.ProtocolConstants.*; // Import constants from new ProtocolConstants

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
    private static final Term QUERY_RESULT_PREDICATE = Term.Atom.of("QueryResult"); // Matches the term structure from _QueryKBTool

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

                if (SIGNAL_TYPE_REQUEST.equals(messageType) && command != null) { // Use constant
                    Term commandTerm = null;

                    // Translate known commands into Term structures
                    switch (command) {
                        case COMMAND_ASSERT_KIF: // Use constant
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
                        case COMMAND_RUN_TOOL: // Use constant
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
                        case COMMAND_RUN_QUERY: // Use constant
                             if (msgNode.has("queryType") && msgNode.has("pattern")) {
                                 String queryType = msgNode.get("queryType").asText();
                                 String patternString = msgNode.get("pattern").asText();
                                 try {
                                     Term patternTerm = Logic.KifParser.parse(patternString);
                                     // Command term: (RunQuery <query-type> <pattern-term>)
                                     commandTerm = new Term.Lst(List.of(Term.Atom.of("RunQuery"), Term.Atom.of(queryType), patternTerm));
                                     // TODO: Include optional parameters like maxDepth from JSON
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
     * Converts an ApiResponse assertion from the KB into an outgoing message string (e.g., JSON).
     * Expected assertion KIF structure: (ApiResponse <requestId> <responseContentTerm>)
     * where <responseContentTerm> is typically (QueryResult <query-type> <status> <results> [<explanation>])
     * or other response structures defined by tools/rules.
     *
     * @param responseAssertion The assertion containing the ApiResponse term.
     * @return A JSON string representing the outgoing message.
     * @throws IllegalArgumentException if the assertion KIF does not match the expected ApiResponse structure.
     */
    public String convertApiResponseToMessage(Assertion responseAssertion) {
        Term.Lst apiResponseTerm = responseAssertion.kif();
        if (!(apiResponseTerm.op().filter(API_RESPONSE_PREDICATE.name()::equals).isPresent() && apiResponseTerm.size() >= 3)) {
            throw new IllegalArgumentException("Assertion KIF is not a valid ApiResponse term: " + apiResponseTerm.toKif());
        }

        Term requestIdTerm = apiResponseTerm.get(1);
        Term responseContentTerm = apiResponseTerm.get(2);

        if (!(requestIdTerm instanceof Term.Atom requestIdAtom)) {
             warn("ApiResponse term has non-Atom request ID: " + requestIdTerm.toKif());
             // Proceed with a string representation or handle as error
        }
        String requestId = (requestIdTerm instanceof Term.Atom) ? ((Term.Atom) requestIdTerm).name() : requestIdTerm.toKif();


        ObjectNode messageNode = mapper.createObjectNode();
        messageNode.put("type", SIGNAL_TYPE_UPDATE); // Use constant
        messageNode.put("updateType", UPDATE_TYPE_RESPONSE); // Use constant
        messageNode.put("requestId", requestId);

        // Convert the response content term to a JSON structure
        messageNode.set("content", termToJsonNode(responseContentTerm));

        try {
            return mapper.writeValueAsString(messageNode);
        } catch (Exception e) {
            error("Failed to convert ApiResponse JSON node to string: " + e.getMessage());
            e.printStackTrace();
            // Fallback to a simple error message
            return String.format("{\"type\":\"%s\",\"updateType\":\"%s\",\"requestId\":\"%s\",\"content\":{\"status\":\"%s\",\"message\":\"Error converting response term to JSON: %s\"}}",
                SIGNAL_TYPE_UPDATE, UPDATE_TYPE_RESPONSE, requestId, RESPONSE_STATUS_ERROR, e.getMessage());
        }
    }

    /**
     * Converts a KIF Term into a simple JSONNode representation.
     * This is a basic conversion and may need refinement based on the desired API JSON structure.
     *
     * @param term The KIF Term to convert.
     * @return A JsonNode representing the term.
     */
    private JsonNode termToJsonNode(Term term) {
        return switch (term) {
            case Term.Atom atom -> {
                // Attempt to parse as number or boolean, otherwise treat as string
                try {
                    return mapper.getNodeFactory().numberNode(Double.parseDouble(atom.name()));
                } catch (NumberFormatException e) {
                    if ("true".equalsIgnoreCase(atom.name())) yield mapper.getNodeFactory().booleanNode(true);
                    if ("false".equalsIgnoreCase(atom.name())) yield mapper.getNodeFactory().booleanNode(false);
                    yield mapper.getNodeFactory().textNode(atom.name());
                }
            }
            case Term.Str str -> mapper.getNodeFactory().textNode(str.value());
            case Term.Num num -> mapper.getNodeFactory().numberNode(num.value());
            case Term.Var var -> mapper.getNodeFactory().textNode("?" + var.name()); // Represent variables as strings
            case Term.Lst list -> {
                // Convert list to a JSON array or object based on structure
                // If the first element is an atom, treat it as an operator/predicate
                if (!list.terms.isEmpty() && list.get(0) instanceof Term.Atom opAtom) {
                    ObjectNode objNode = mapper.createObjectNode();
                    objNode.put("op", opAtom.name());
                    ArrayNode argsNode = mapper.createArrayNode();
                    list.terms.stream().skip(1).forEach(arg -> argsNode.add(termToJsonNode(arg)));
                    objNode.set("args", argsNode);
                    yield objNode;
                } else {
                    // Otherwise, treat as a simple array of terms
                    ArrayNode arrayNode = mapper.createArrayNode();
                    list.terms.forEach(item -> arrayNode.add(termToJsonNode(item)));
                    yield arrayNode;
                }
            }
            // Add other Term types if necessary
            default -> mapper.getNodeFactory().textNode(term.toKif()); // Fallback to KIF string
        };
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
    // This is now partially implemented in SystemControl.
}
