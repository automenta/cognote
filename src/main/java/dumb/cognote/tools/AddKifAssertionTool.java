package dumb.cognote.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dumb.cognote.Cog;
import dumb.cognote.Logic;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static dumb.cognote.Logic.*;
import static java.util.Objects.requireNonNullElse;

public class AddKifAssertionTool implements BaseTool {

    private final Cog cog;

    public AddKifAssertionTool(Cog cog) {
        this.cog = cog;
    }

    @Override
    public String name() {
        return "add_kif_assertion";
    }

    @Override
    public String description() {
        return "Add a KIF assertion string to a knowledge base. Input is a JSON object with 'kif_assertion' (string) and optional 'target_kb_id' (string, defaults to global KB). Returns success or error message.";
    }

    // This method is called by LangChain4j's AiServices.
    // It needs to block or return a simple type.
    // It calls the internal execute logic and blocks for the result.
    @Tool(name = "add_kif_assertion", value = "Add a KIF assertion string to a knowledge base. Input is a JSON object with 'kif_assertion' (string) and optional 'target_kb_id' (string, defaults to global KB). Returns success or error message.")
    public String addKifAssertionToolMethod(@P(value = "The KIF assertion string to add.") String kifAssertion, @P(value = "Optional ID of the knowledge base (note ID) to add the assertion to. Defaults to global KB if not provided or empty.") @Nullable String targetKbId) {
        try {
            // Call the internal execute logic and block for the result
            return (String) execute(Map.of("kif_assertion", kifAssertion, "target_kb_id", targetKbId)).join();
        } catch (Exception e) {
            System.err.println("Error in blocking tool method 'addKifAssertionToolMethod': " + e.getMessage());
            e.printStackTrace();
            return "Error executing tool: " + e.getMessage();
        }
    }

    // The BaseTool execute method signature for internal calls.
    // It parses parameters from the map and returns a CompletableFuture.
    @Override
    public CompletableFuture<Object> execute(Map<String, Object> parameters) {
        String kifAssertion = (String) parameters.get("kif_assertion");
        String targetKbId = (String) parameters.get("target_kb_id");

        return CompletableFuture.supplyAsync(() -> {
            if (kifAssertion == null || kifAssertion.isBlank()) {
                 return "Error: Missing required parameter 'kif_assertion'.";
            }
            try {
                var terms = Logic.KifParser.parseKif(kifAssertion);
                if (terms.size() != 1 || !(terms.getFirst() instanceof Logic.KifList list)) {
                    return "Error: Invalid KIF format provided. Must be a single KIF list.";
                }

                var isNeg = list.op().filter(Logic.KIF_OP_NOT::equals).isPresent();
                if (isNeg && list.size() != 2) {
                    return "Error: Invalid 'not' format in KIF.";
                }
                var isEq = !isNeg && list.op().filter(Logic.KIF_OP_EQUAL::equals).isPresent();
                var isOriented = isEq && list.size() == 3 && list.get(1).weight() > list.get(2).weight();
                var type = list.containsSkolemTerm() ? Logic.AssertionType.SKOLEMIZED : Logic.AssertionType.GROUND;
                var pri = LM.LLM_ASSERTION_BASE_PRIORITY / (1.0 + list.weight());
                var sourceId = "internal-tool:add_kif_assertion"; // Differentiate source

                var finalTargetKbId = requireNonNullElse(targetKbId, Cog.GLOBAL_KB_NOTE_ID);

                var pa = new Logic.PotentialAssertion(list, pri, Set.of(), sourceId, isEq, isNeg, isOriented, finalTargetKbId, type, List.of(), 0);
                var committedAssertion = cog.context.tryCommit(pa, sourceId);

                if (committedAssertion != null) {
                    System.out.println("Tool 'add_kif_assertion' (internal) successfully added: " + committedAssertion.toKifString() + " to KB " + committedAssertion.kb());
                    return "Assertion added successfully: " + committedAssertion.toKifString() + " [ID: " + committedAssertion.id() + "]";
                } else {
                    System.out.println("Tool 'add_kif_assertion' (internal) failed to add assertion (might be duplicate, trivial, or KB full): " + list.toKif());
                    return "Assertion not added (might be duplicate, trivial, or KB full).";
                }
            } catch (Logic.KifParser.ParseException e) {
                System.err.println("Error parsing KIF in tool 'add_kif_assertion' (internal): " + e.getMessage());
                return "Error parsing KIF: " + e.getMessage();
            } catch (Exception e) {
                System.err.println("Error executing tool 'add_kif_assertion' (internal): " + e.getMessage());
                e.printStackTrace();
                return "Error executing tool: " + e.getMessage();
            }
        }, cog.events.exe);
    }
}
