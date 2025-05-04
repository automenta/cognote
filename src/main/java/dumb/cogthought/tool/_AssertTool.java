package dumb.cogthought.tool;

import dumb.cogthought.Assertion;
import dumb.cogthought.Cog;
import dumb.cogthought.Logic;
import dumb.cogthought.Term;
import dumb.cogthought.Tool;
import dumb.cogthought.ToolContext;
import dumb.cogthought.util.Log;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static java.util.Objects.requireNonNull;
import static dumb.cogthought.util.Log.error;
import static dumb.cogthought.util.Log.info;
import static dumb.cogthought.util.Log.warn;

/**
 * Primitive Tool for asserting a KIF term into the Knowledge Base.
 * Expected parameters: (Assert <kif-term> [<source-note-id>] [<priority>])
 * The <kif-term> must be a Term.Lst.
 */
public class _AssertTool implements Tool {

    private final ToolContext context;

    public _AssertTool(ToolContext context) {
        this.context = requireNonNull(context);
    }

    @Override
    public String name() {
        return "_Assert";
    }

    @Override
    public String description() {
        return "Asserts a KIF term into the Knowledge Base. Parameters: (<kif-term> [<source-note-id>] [<priority>])";
    }

    @Override
    public CompletableFuture<?> execute(Term parameters, ToolContext context) {
        return CompletableFuture.supplyAsync(() -> {
            if (!(parameters instanceof Term.Lst params) || params.size() < 2) {
                throw new ToolExecutionException("Invalid parameters for _Assert tool. Expected (<kif-term> [<source-note-id>] [<priority>])");
            }

            Term kifTerm = params.get(1);
            if (!(kifTerm instanceof Term.Lst assertionKif)) {
                throw new ToolExecutionException("First parameter must be a KIF list term.");
            }

            // Optional sourceNoteId (second parameter)
            String sourceNoteId = null;
            if (params.size() > 2 && params.get(2) instanceof Term.Atom sourceNoteIdAtom) {
                sourceNoteId = sourceNoteIdAtom.name();
            } else if (params.size() > 2 && !(params.get(2) instanceof Term.Var)) {
                 warn("_Assert tool: Optional source-note-id parameter should be an Atom or Var, got " + params.get(2).getClass().getSimpleName());
            }

            // Optional priority (third parameter)
            double priority = 0.5; // Default priority
            if (params.size() > 3 && params.get(3) instanceof Term.Num priorityNum) {
                priority = priorityNum.value();
            } else if (params.size() > 3 && !(params.get(3) instanceof Term.Var)) {
                 warn("_Assert tool: Optional priority parameter should be a Number or Var, got " + params.get(3).getClass().getSimpleName());
            }


            // Determine KB ID - use sourceNoteId if provided, otherwise global
            String kbId = sourceNoteId != null ? sourceNoteId : Cog.GLOBAL_KB_NOTE_ID;

            // Create the Assertion object
            var assertionId = Cog.id(Cog.ID_PREFIX_ASSERTION);
            var timestamp = Instant.now();

            // These properties should ideally be determined by the TermLogicEngine or rules,
            // but for a primitive tool, we can infer some basics or rely on the term structure.
            // A more advanced system might have a rule that calls _Assert after determining these.
            // For now, let's use basic inference from the term structure.
            boolean isEquality = Logic.isEquality(assertionKif);
            boolean isNegated = Logic.isNegated(assertionKif);
            boolean isOrientedEquality = Logic.isOrientedEquality(assertionKif);
            Logic.AssertionType type = Logic.determineAssertionType(assertionKif);
            List<Term.Var> quantifiedVars = Logic.collectQuantifiedVars(assertionKif);
            int derivationDepth = 0; // Primitive assertions are base facts

            // Primitive assertions don't have justifications from other assertions,
            // but they might have a source (e.g., a rule ID or API request ID).
            // The 'sourceId' in PotentialAssertion is more about *why* it was proposed.
            // For _Assert, the justificationIds set should probably be empty,
            // and the sourceNoteId/kbId indicate where it came from.
            // Let's assume for this primitive tool, justifications are empty.
            Set<String> justificationIds = Collections.emptySet();

            Assertion newAssertion = new Assertion(
                assertionId,
                assertionKif,
                priority,
                timestamp.toEpochMilli(), // Use epoch milliseconds
                sourceNoteId, // Use the provided sourceNoteId
                justificationIds,
                type,
                isEquality,
                isOrientedEquality,
                isNegated,
                quantifiedVars,
                derivationDepth,
                true, // Assertions from this tool are active by default
                kbId // Use the determined KB ID
            );

            context.getKnowledgeBase().saveAssertion(newAssertion);
            info("Asserted via _Assert tool: " + assertionKif.toKif());

            return null; // Primitive tools can return null or a simple success indicator
        }, context.getKnowledgeBase().getExecutor()); // Assuming KB has an executor or use a shared one
    }
}
