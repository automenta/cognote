package dumb.cognote.tool;

import dumb.cognote.Cog;
import dumb.cognote.Cog.Answer;
import dumb.cognote.Cog.QueryType;
import dumb.cognote.Logic;
import dumb.cognote.Logic.KifParser.ParseException;
import dumb.cognote.Term;
import dumb.cognote.Tool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import static dumb.cognote.Cog.ID_PREFIX_QUERY;
import static dumb.cognote.Cog.QueryType.valueOf;
import static java.util.Objects.requireNonNullElse;

public class QueryTool implements Tool {

    private final Cog cog;

    public QueryTool(Cog cog) {
        this.cog = cog;
    }

    @Override
    public String name() {
        return "run_query";
    }

    @Override
    public String description() {
        return "Executes a KIF query against the knowledge base. Input is a JSON object with 'kif_pattern' (string) and optional 'target_kb_id' (string, defaults to global KB). Returns the query result as a formatted string.";
    }

    // This method is called by LangChain4j's AiServices.
    // It needs to block or return a simple type.
    // It calls the internal execute logic and blocks for the result.
    @dev.langchain4j.agent.tool.Tool(name = "run_query", value = "Executes a KIF query against the knowledge base. Input is a JSON object with 'kif_pattern' (string) and optional 'target_kb_id' (string, defaults to global KB). Returns the query result as a formatted string.")
    public String runQueryToolMethod(@P(value = "The KIF pattern to query the knowledge base with.") String kifPattern, @P(value = "Optional ID of the knowledge base (note ID) to query. Defaults to global KB if not provided or empty.") @Nullable String targetKbId) {
        try {
            // Call the internal execute logic and block for the result.
            // The execute method now returns Answer, so format it here.
            Answer answer = (Answer) execute(Map.of("kif_pattern", kifPattern, "target_kb_id", targetKbId)).join();
            return formatQueryResult(answer);
        } catch (Exception e) {
            System.err.println("Error in blocking tool method 'runQueryToolMethod': " + e.getMessage());
            e.printStackTrace();
            return "Error executing tool: " + e.getMessage();
        }
    }

    // The BaseTool execute method signature for internal calls.
    // It parses parameters from the map and returns a CompletableFuture<Answer>.
    @Override
    public CompletableFuture<Object> execute(Map<String, Object> parameters) {
        var kifPattern = (String) parameters.get("kif_pattern");
        var targetKbId = (String) parameters.get("target_kb_id");
        var queryTypeStr = (String) parameters.getOrDefault("query_type", "ASK_BINDINGS"); // Default type

        return CompletableFuture.supplyAsync(() -> {
            if (cog == null) {
                throw new IllegalStateException("System not available.");
            }
            if (kifPattern == null || kifPattern.isBlank()) {
                throw new IllegalArgumentException("Missing required parameter 'kif_pattern'.");
            }

            QueryType queryType;
            try {
                queryType = valueOf(queryTypeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid query type '" + queryTypeStr + "'. Must be ASK_BINDINGS, ASK_TRUE_FALSE, or ACHIEVE_GOAL.");
            }

            try {
                // Execute the query synchronously via Cog and return the Answer object
                return cog.querySync(
                        new Cog.Query(
                                Cog.id(ID_PREFIX_QUERY + "tool_"),
                                queryType,
                                (Term.Lst) Logic.KifParser.parseKif(kifPattern).getFirst(), // Assuming parseKif returns a single list for a query pattern
                                requireNonNullElse(targetKbId, Cog.GLOBAL_KB_NOTE_ID),
                                Map.of()));
            } catch (ParseException e) {
                System.err.println("Error parsing KIF pattern in tool 'run_query': " + e.getMessage());
                throw new CompletionException(new IllegalArgumentException("Error parsing KIF pattern: " + e.getMessage()));
            } catch (Exception e) {
                System.err.println("Error executing tool 'run_query': " + e.getMessage());
                e.printStackTrace();
                throw new CompletionException(new RuntimeException("Error executing tool: " + e.getMessage(), e));
            }
        }, cog.events.exe);
    }

    private String formatQueryResult(Answer answer) {
        return switch (answer.status()) {
            case SUCCESS -> {
                if (answer.bindings().isEmpty()) {
                    yield "Query successful, but no matches found.";
                } else {
                    yield "Query successful. Found " + answer.bindings().size() + " matches:\n" +
                            answer.bindings().stream()
                                    .map(b -> b.entrySet().stream()
                                            .map(e -> e.getKey().name() + " = " + e.getValue().toKif())
                                            .collect(Collectors.joining(", ")))
                                    .collect(Collectors.joining("\n"));
                }
            }
            case FAILURE -> "Query failed.";
            case TIMEOUT -> "Query timed out.";
            case ERROR ->
                    "Query error: " + (answer.explanation() != null ? answer.explanation().details() : "Unknown error.");
        };
    }
}
