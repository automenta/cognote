package dumb.cognote.tool;

import dumb.cognote.Cog;
import dumb.cognote.KifParser;
import dumb.cognote.Tool;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static dumb.cognote.Log.error;

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
        return "Runs a KIF query against the knowledge base. Supports ASK_BINDINGS, ASK_TRUE_FALSE, and ACHIEVE_GOAL query types. Returns a JSON object with status and bindings.";
    }

    @dev.langchain4j.agent.tool.Tool("Runs a KIF query against the knowledge base. Supports ASK_BINDINGS, ASK_TRUE_FALSE, and ACHIEVE_GOAL query types. Returns a JSON object with status and bindings.")
    public CompletableFuture<String> execute(
            @dev.langchain4j.agent.tool.P("query_type") String queryTypeStr,
            @dev.langchain4j.agent.tool.P("kif_pattern") String kifPattern,
            @dev.langchain4j.agent.tool.P("target_kb_id") String targetKbId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var queryType = Cog.QueryType.valueOf(queryTypeStr.toUpperCase());
                var terms = KifParser.parseKif(kifPattern);
                if (terms.size() != 1 || !(terms.getFirst() instanceof dumb.cognote.Term.Lst pattern)) {
                    throw new ToolExecutionException("Query pattern must be a single KIF list.");
                }

                var query = new dumb.cognote.Query(Cog.id(Cog.ID_PREFIX_QUERY), queryType, pattern, targetKbId, Map.of());

                var answer = cog.querySync(query);

                return answer.toJson().toString();

            } catch (IllegalArgumentException e) {
                error("QueryTool invalid queryType: " + queryTypeStr);
                throw new ToolExecutionException("Invalid queryType: " + queryTypeStr);
            } catch (KifParser.ParseException e) {
                error("QueryTool parse error: " + e.getMessage());
                throw new ToolExecutionException("Failed to parse KIF pattern: " + e.getMessage());
            } catch (RuntimeException e) {
                error("QueryTool execution error: " + e.getMessage());
                throw new ToolExecutionException("Query execution failed: " + e.getMessage());
            } catch (Exception e) {
                error("QueryTool unexpected error: " + e.getMessage());
                e.printStackTrace();
                throw new ToolExecutionException("An unexpected error occurred during query execution: " + e.getMessage());
            }
        }, cog.events.exe);
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> parameters) {
        var queryTypeStr = (String) parameters.get("query_type");
        var kifPattern = (String) parameters.get("kif_pattern");
        var targetKbId = (String) parameters.get("target_kb_id");

        if (queryTypeStr == null || queryTypeStr.isBlank()) {
            error("QueryTool requires a 'query_type' parameter.");
            return CompletableFuture.failedFuture(new ToolExecutionException("Missing 'query_type' parameter."));
        }
        if (kifPattern == null || kifPattern.isBlank()) {
            error("QueryTool requires a 'kif_pattern' parameter.");
            return CompletableFuture.failedFuture(new ToolExecutionException("Missing 'kif_pattern' parameter."));
        }

        return execute(queryTypeStr, kifPattern, targetKbId);
    }
}
