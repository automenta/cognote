package dumb.cogthought.tool;

import dumb.cogthought.Assertion;
import dumb.cogthought.Term;
import dumb.cogthought.Tool;
import dumb.cogthought.ToolContext;
import dumb.cogthought.util.Log;

import java.util.concurrent.CompletableFuture;

import static java.util.Objects.requireNonNull;
import static dumb.cogthought.util.Log.info;
import static dumb.cogthought.util.Log.warn;

/**
 * Primitive Tool for retracting assertions from the Knowledge Base.
 * Expected parameters: (Retract <target>)
 * The <target> can be an Assertion ID (Atom) or a KIF pattern (Lst).
 */
public class _RetractTool implements Tool {

    private final ToolContext context;

    public _RetractTool(ToolContext context) {
        this.context = requireNonNull(context);
    }

    @Override
    public String name() {
        return "_Retract";
    }

    @Override
    public String description() {
        return "Retracts assertions from the Knowledge Base by ID or KIF pattern. Parameters: (<target>)";
    }

    @Override
    public CompletableFuture<?> execute(Term parameters, ToolContext context) {
        return CompletableFuture.supplyAsync(() -> {
            if (!(parameters instanceof Term.Lst params) || params.size() != 2) {
                throw new ToolExecutionException("Invalid parameters for _Retract tool. Expected (<target>)");
            }

            Term target = params.get(1);

            if (target instanceof Term.Atom targetIdAtom) {
                String assertionId = targetIdAtom.name();
                context.getKnowledgeBase().loadAssertion(assertionId)
                       .ifPresentOrElse(
                           assertionToRetract -> {
                               context.getKnowledgeBase().deleteAssertion(assertionToRetract.id());
                               info("Retracted assertion by ID via _Retract tool: " + assertionId);
                           },
                           () -> warn("_Retract tool attempted to retract non-existent assertion by ID: " + assertionId)
                       );
            } else if (target instanceof Term.Lst targetKif) {
                 // Retract the first active assertion matching the KIF pattern
                 // TODO: Add option to retract all matching assertions
                 context.getKnowledgeBase().queryAssertions(targetKif)
                        .findFirst() // Retract only the first match for now
                        .ifPresentOrElse(
                            assertionToRetract -> {
                                context.getKnowledgeBase().deleteAssertion(assertionToRetract.id());
                                info("Retracted assertion by KIF pattern via _Retract tool: " + targetKif.toKif());
                            },
                            () -> warn("_Retract tool attempted to retract non-existent assertion by KIF pattern: " + targetKif.toKif())
                        );
            } else {
                throw new ToolExecutionException("Invalid target for _Retract tool. Expected Assertion ID (Atom) or KIF pattern (Lst). Got: " + target.toKif());
            }

            return null; // Primitive tools can return null or a simple success indicator
        }, context.getKnowledgeBase().getExecutor()); // Assuming KB has an executor or use a shared one
    }
}
