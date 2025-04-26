package dumb.cognote.tool;

import dev.langchain4j.agent.tool.Tool;
import dumb.cognote.Cog;
import dumb.cognote.KifParser;
import dumb.cognote.Tool;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static dumb.cognote.Log.error;

public class AssertKIFTool implements Tool {

    private final Cog cog;

    public AssertKIFTool(Cog cog) {
        this.cog = cog;
    }

    @Override
    public String name() {
        return "assert_kif";
    }

    @Override
    public String description() {
        return "Asserts one or more KIF expressions into the knowledge base. Use this to add new facts or rules.";
    }

    @Tool("Asserts one or more KIF expressions into the knowledge base. Use this to add new facts or rules.")
    public CompletableFuture<String> execute(@dev.langchain4j.agent.tool.P("kif_string") String kifString, @dev.langchain4j.agent.tool.P("note_id") String noteId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var terms = KifParser.parseKif(kifString);
                if (terms.isEmpty()) return "No KIF terms parsed.";
                terms.forEach(term -> cog.events.emit(new Cog.ExternalInputEvent(term, "tool:assert_kif", noteId)));
                return "Asserted " + terms.size() + " terms.";
            } catch (KifParser.ParseException e) {
                error("AssertKIFTool parse error: " + e.getMessage());
                throw new ToolExecutionException("Failed to parse KIF: " + e.getMessage());
            } catch (Exception e) {
                error("AssertKIFTool execution error: " + e.getMessage());
                throw new ToolExecutionException("Error asserting KIF: " + e.getMessage());
            }
        }, cog.events.exe);
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> parameters) {
        var kifString = (String) parameters.get("kif_string");
        var noteId = (String) parameters.get("note_id");

        if (kifString == null || kifString.isBlank()) {
            error("AssertKIFTool requires a 'kif_string' parameter.");
            return CompletableFuture.failedFuture(new ToolExecutionException("Missing 'kif_string' parameter."));
        }

        return execute(kifString, noteId);
    }
}
