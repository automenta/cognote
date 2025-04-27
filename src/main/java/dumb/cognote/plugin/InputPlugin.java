package dumb.cognote.plugin;

import dumb.cognote.*;

import java.util.List;
import java.util.Set;

import static dumb.cognote.Cog.DEFAULT_RULE_PRIORITY;
import static dumb.cognote.Log.error;
import static dumb.cognote.Log.message;
import static java.rmi.server.LogStream.log;

public class InputPlugin extends Plugin.BasePlugin {

    @Override
    public void start(dumb.cognote.Events e, Logic.Cognition ctx) {
        super.start(e, ctx);
        events.on(Cog.ExternalInputEvent.class, this::handleExternalInput);
        log("InputPlugin started.");
    }

    private void handleExternalInput(Cog.ExternalInputEvent event) {
        var term = event.term();
        var sourceId = event.sourceId();
        var noteId = event.noteId();

        log("Processing external input from " + sourceId + (noteId != null ? " for note " + noteId : "") + ": " + term.toKif());

        if (term instanceof Term.Lst termList) {
            try {
                // Attempt to parse as a rule first
                if (termList.op().filter(op -> op.equals(Logic.KIF_OP_IMPLIES) || op.equals(Logic.KIF_OP_EQUIV)).isPresent()) {
                    try {
                        var rule = Rule.parseRule(Cog.id(Cog.ID_PREFIX_RULE), termList, DEFAULT_RULE_PRIORITY, noteId);
                        if (context.addRule(rule)) {
                            message("InputPlugin: Added rule from " + sourceId + ": " + rule.form().toKif());
                            return; // Processed as a rule
                        } else {
                            message("InputPlugin: Rule already exists from " + sourceId + ": " + rule.form().toKif());
                            return; // Rule already exists
                        }
                    } catch (IllegalArgumentException e) {
                        // Not a valid rule format, try as assertion
                        logWarning("InputPlugin: Input looks like a rule but is invalid: " + e.getMessage());
                    }
                }

                // If not a rule, try to commit as an assertion
                var isNegated = termList.op().filter(Logic.KIF_OP_NOT::equals).isPresent();
                var isEquality = !isNegated && termList.op().filter(Logic.KIF_OP_EQUAL::equals).isPresent();
                var isOrientedEquality = isEquality && termList.size() == 3 && termList.get(1).weight() > termList.get(2).weight();
                var type = termList.containsVar() ? Logic.AssertionType.UNIVERSAL : (termList.containsSkolemTerm() ? Logic.AssertionType.SKOLEMIZED : Logic.AssertionType.GROUND);
                var quantifiedVars = (type == Logic.AssertionType.UNIVERSAL && termList.op().filter(Logic.KIF_OP_FORALL::equals).isPresent())
                        ? List.copyOf(Term.collectSpecVars(termList.get(1))) : List.<Term.Var>of();

                var potentialAssertion = new Assertion.PotentialAssertion(
                        termList,
                        Cog.INPUT_ASSERTION_BASE_PRIORITY,
                        Set.of(), // Input assertions have no justifications within the system
                        sourceId,
                        isEquality,
                        isNegated,
                        isOrientedEquality,
                        noteId,
                        type,
                        quantifiedVars,
                        0 // Input assertions have derivation depth 0
                );

                if (context.tryCommit(potentialAssertion, sourceId) != null) {
                    message("InputPlugin: Asserted fact from " + sourceId + ": " + termList.toKif());
                } else {
                    message("InputPlugin: Fact already exists or was subsumed from " + sourceId + ": " + termList.toKif());
                }

            } catch (Exception e) {
                error("InputPlugin: Error processing input term from " + sourceId + ": " + term.toKif() + " Error: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            logWarning("InputPlugin: Ignoring non-list input term from " + sourceId + ": " + term.toKif());
        }
    }
}
