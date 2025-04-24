package dumb.cognote.tools;

import dumb.cognote.Cog;
import dumb.cognote.Logic;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static dumb.cognote.Cog.ID_PREFIX_QUERY;
import static java.util.Objects.requireNonNullElse;

public class RunQueryTool implements BaseTool {

    private final Cog cog;

    public RunQueryTool(Cog cog) {
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

    @Override
    public CompletableFuture<Object> execute(Map<String, Object> parameters) {
        String kifPattern = (String) parameters.get("kif_pattern");
        String targetKbId = (String) parameters.get("target_kb_id");
        String queryTypeStr = (String) parameters.getOrDefault("query_type", "ASK_BINDINGS"); // Default type

        return CompletableFuture.supplyAsync(() -> {
            if (cog == null) {
                return "Error: System not available.";
            }
            if (kifPattern == null || kifPattern.isBlank()) {
                return "Error: Missing required parameter 'kif_pattern'.";
            }

            Logic.QueryType queryType;
            try {
                queryType = Logic.QueryType.valueOf(queryTypeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                return "Error: Invalid query type '" + queryTypeStr + "'. Must be ASK_BINDINGS, ASK_TRUE_FALSE, or ACHIEVE_GOAL.";
            }

            try {
                var terms = Logic.KifParser.parseKif(kifPattern);
                if (terms.size() != 1 || !(terms.getFirst() instanceof Logic.KifList patternList)) {
                    return "Error: Invalid KIF pattern format. Must be a single KIF list.";
                }

                var finalTargetKbId = requireNonNullElse(targetKbId, Cog.GLOBAL_KB_NOTE_ID);

                var queryId = Cog.generateId(ID_PREFIX_QUERY + "tool_");
                var query = new Cog.Query(queryId, queryType, patternList, finalTargetKbId, Map.of());

                // Execute the query synchronously via Cog
                var answer = cog.executeQuerySync(query);

                return formatQueryResult(answer);

            } catch (Logic.KifParser.ParseException e) {
                System.err.println("Error parsing KIF pattern in tool 'run_query': " + e.getMessage());
                return "Error parsing KIF pattern: " + e.getMessage();
            } catch (Exception e) {
                System.err.println("Error executing tool 'run_query': " + e.getMessage());
                e.printStackTrace();
                return "Error executing tool: " + e.getMessage();
            }
        }, cog.events.exe);
    }

    private String formatQueryResult(Cog.Answer answer) {
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
            case ERROR -> "Query error: " + (answer.explanation() != null ? answer.explanation().details() : "Unknown error.");
        };
    }
}
