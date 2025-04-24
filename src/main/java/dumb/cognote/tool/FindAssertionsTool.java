package dumb.cognote.tool;

import dev.langchain4j.agent.tool.P;
import dumb.cognote.Cog;
import dumb.cognote.Logic;
import dumb.cognote.Term;
import dumb.cognote.Tool;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import static dumb.cognote.Cog.ID_PREFIX_QUERY;
import static java.util.Objects.requireNonNullElse;

public class FindAssertionsTool implements Tool {

    private final Cog cog;

    public FindAssertionsTool(Cog cog) {
        this.cog = cog;
    }

    @Override
    public String name() {
        return "find_assertions";
    }

    @Override
    public String description() {
        return "Query the knowledge base for assertions matching a KIF pattern. Input is a JSON object with 'kif_pattern' (string) and optional 'target_kb_id' (string, defaults to global KB). Returns a list of bindings found or a message indicating no matches or an error.";
    }

    // This method is called by LangChain4j's AiServices.
    // It needs to block or return a simple type.
    // It calls the internal execute logic and blocks for the result.
    @dev.langchain4j.agent.tool.Tool(name = "find_assertions", value = "Query the knowledge base for assertions matching a KIF pattern. Input is a JSON object with 'kif_pattern' (string) and optional 'target_kb_id' (string, defaults to global KB). Returns a list of bindings found or a message indicating no matches or an error.")
    public String findAssertionsToolMethod(@P(value = "The KIF pattern to query the knowledge base with.") String kifPattern, @P(value = "Optional ID of the knowledge base (note ID) to query. Defaults to global KB if not provided or empty.") @Nullable String targetKbId) {
        try {
            // Call the internal execute logic and block for the result.
            // The execute method now returns Answer, so format it here.
            Cog.Answer answer = (Cog.Answer) execute(Map.of("kif_pattern", kifPattern, "target_kb_id", targetKbId)).join();

            if (answer.status() == Cog.QueryStatus.SUCCESS) {
                if (answer.bindings().isEmpty()) {
                    return "Query successful, but no matching assertions found.";
                } else {
                    return "Query successful. Found " + answer.bindings().size() + " bindings:\n" +
                            answer.bindings().stream()
                                    .map(b -> b.entrySet().stream()
                                            .map(e -> e.getKey().name() + " = " + e.getValue().toKif())
                                            .collect(Collectors.joining(", ")))
                                    .collect(Collectors.joining("\n"));
                }
            } else {
                return "Query failed with status " + answer.status() + ". " + (answer.explanation() != null ? "Details: " + answer.explanation().details() : "");
            }

        } catch (Exception e) {
            System.err.println("Error in blocking tool method 'findAssertionsToolMethod': " + e.getMessage());
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

        return CompletableFuture.supplyAsync(() -> {
            if (cog == null) {
                throw new IllegalStateException("System not available.");
            }
            if (kifPattern == null || kifPattern.isBlank()) {
                throw new IllegalArgumentException("Missing required parameter 'kif_pattern'.");
            }
            try {
                var terms = Logic.KifParser.parseKif(kifPattern);
                if (terms.size() != 1 || !(terms.getFirst() instanceof Term.Lst patternList)) {
                    throw new IllegalArgumentException("Invalid KIF pattern format. Must be a single KIF list.");
                }

                var finalTargetKbId = requireNonNullElse(targetKbId, Cog.GLOBAL_KB_NOTE_ID);

                var queryId = Cog.id(ID_PREFIX_QUERY + "tool_");
                // This tool specifically uses ASK_BINDINGS
                var query = new Cog.Query(queryId, Cog.QueryType.ASK_BINDINGS, patternList, finalTargetKbId, Map.of());
                return cog.querySync(query); // Call the sync method in Cog and return the Answer object

            } catch (Logic.KifParser.ParseException e) {
                System.err.println("Error parsing KIF pattern in tool 'find_assertions' (internal): " + e.getMessage());
                throw new CompletionException(new IllegalArgumentException("Error parsing KIF pattern: " + e.getMessage()));
            } catch (Exception e) {
                System.err.println("Error executing tool 'find_assertions' (internal): " + e.getMessage());
                e.printStackTrace();
                throw new CompletionException(new RuntimeException("Error executing tool: " + e.getMessage(), e));
            }
        }, cog.events.exe);
    }
}
