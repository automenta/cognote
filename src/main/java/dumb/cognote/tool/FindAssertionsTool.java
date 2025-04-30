package dumb.cognote.tool;


import dumb.cognote.CogNote;
import dumb.cognote.KifParser;
import dumb.cognote.Tool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static dumb.cognote.Log.error;

public class FindAssertionsTool implements Tool {

    private final CogNote cog;

    public FindAssertionsTool(CogNote cog) {
        this.cog = cog;
    }

    @Override
    public String name() {
        return "find_assertions";
    }

    @Override
    public String description() {
        return "Finds assertions in the knowledge base that match a given KIF pattern. Returns a JSON array of matching assertion IDs.";
    }

    @dev.langchain4j.agent.tool.Tool("Finds assertions in the knowledge base that match a given KIF pattern. Returns a JSON array of matching assertion IDs.")
    public CompletableFuture<String> findAssertions(@dev.langchain4j.agent.tool.P("kif_pattern") String kifPattern, @dev.langchain4j.agent.tool.P("target_kb_id") String targetKbId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var terms = KifParser.parseKif(kifPattern);
                if (terms.size() != 1 || !(terms.getFirst() instanceof dumb.cognote.Term.Lst pattern)) {
                    throw new ToolExecutionException("Pattern must be a single KIF list.");
                }

                var matchingAssertions = cog.context.kb(targetKbId).findInstancesOf(pattern)
                        .map(dumb.cognote.Assertion::id)
                        .collect(Collectors.toList());

                return new JSONArray(matchingAssertions).toString();

            } catch (KifParser.ParseException e) {
                error("FindAssertionsTool parse error: " + e.getMessage());
                throw new ToolExecutionException("Failed to parse KIF pattern: " + e.getMessage());
            } catch (Exception e) {
                error("FindAssertionsTool execution error: " + e.getMessage());
                throw new ToolExecutionException("Error finding assertions: " + e.getMessage());
            }
        }, cog.events.exe);
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> parameters) {
        var kifPattern = (String) parameters.get("kif_pattern");
        var targetKbId = (String) parameters.get("target_kb_id");

        if (kifPattern == null || kifPattern.isBlank()) {
            error("FindAssertionsTool requires a 'kif_pattern' parameter.");
            return CompletableFuture.failedFuture(new ToolExecutionException("Missing 'kif_pattern' parameter."));
        }

        return findAssertions(kifPattern, targetKbId);
    }
}
