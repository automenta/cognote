package dumb.cognote.plugin;

import dumb.cognote.*;

import java.util.List;
import java.util.Set;

import static dumb.cognote.Cog.DEFAULT_RULE_PRIORITY;
import static dumb.cognote.Log.message;
import static dumb.cognote.Protocol.KB_CLIENT_INPUT;

public class InputPlugin extends Plugin.BasePlugin {

    @Override
    public void start(dumb.cognote.Events e, Logic.Cognition ctx) {
        super.start(e, ctx);
        events.on(Event.ExternalInputEvent.class, this::handleExternalInput);
        log("InputPlugin started.");
    }

    private void handleExternalInput(Event.ExternalInputEvent event) {
        var term = event.term();
        var sourceId = event.sourceId();
        var noteId = event.noteId();

        log("Processing external input from " + sourceId + (noteId != null ? " for note " + noteId : "") + ": " + term.toKif());

        if (term instanceof Term.Lst termList) {
            var opOpt = termList.op();
            if (opOpt.isPresent()) {
                var op = opOpt.get();
                switch (op) {
                    case Logic.KIF_OP_IMPLIES, Logic.KIF_OP_EQUIV -> {
                        try {
                            var rule = Rule.parseRule(Cog.id(Cog.ID_PREFIX_RULE), termList, DEFAULT_RULE_PRIORITY, noteId);
                            if (context.addRule(rule)) {
                                message("InputPlugin: Added rule from " + sourceId + ": " + rule.form().toKif());
                            } else {
                                message("InputPlugin: Rule already exists from " + sourceId + ": " + rule.form().toKif());
                            }
                            return;
                        } catch (IllegalArgumentException e) {
                            logWarning("InputPlugin: Input looks like a rule but is invalid: " + e.getMessage());
                        }
                    }
                    case Protocol.PRED_REQUEST -> {
                        assertIntoKb(termList, sourceId, KB_CLIENT_INPUT, Logic.AssertionType.GROUND, List.of(), noteId);
                        message("InputPlugin: Asserted client request into " + KB_CLIENT_INPUT + " from " + sourceId + ": " + termList.toKif());
                        return;
                    }
                    default -> {
                    }
                }
            }

            var targetKb = (noteId != null && context.kb(noteId) != null) ? noteId : Cog.GLOBAL_KB_NOTE_ID;

            var isNegated = termList.op().filter(Logic.KIF_OP_NOT::equals).isPresent();
            var isEquality = !isNegated && termList.op().filter(Logic.KIF_OP_EQUAL::equals).isPresent();
            var isOrientedEquality = isEquality && termList.size() == 3 && termList.get(1).weight() > termList.get(2).weight();
            var type = termList.containsVar() ? Logic.AssertionType.UNIVERSAL : (termList.containsSkolemTerm() ? Logic.AssertionType.SKOLEMIZED : Logic.AssertionType.GROUND);
            var quantifiedVars = (type == Logic.AssertionType.UNIVERSAL && termList.op().filter(Logic.KIF_OP_FORALL::equals).isPresent())
                    ? List.copyOf(Term.collectSpecVars(termList.get(1))) : List.<Term.Var>of();


            assertIntoKb(termList, sourceId, targetKb, type, quantifiedVars, noteId);
            message("InputPlugin: Asserted fact into " + targetKb + " from " + sourceId + ": " + termList.toKif());


        } else {
            logWarning("InputPlugin: Ignoring non-list input term from " + sourceId + ": " + term.toKif());
        }
    }

    private void assertIntoKb(Term.Lst term, String sourceId, String kbId, Logic.AssertionType type, List<Term.Var> quantifiedVars, String sourceNoteId) {
        var isNegated = term.op().filter(Logic.KIF_OP_NOT::equals).isPresent();
        var isEquality = !isNegated && term.op().filter(Logic.KIF_OP_EQUAL::equals).isPresent();
        var isOrientedEquality = isEquality && term.size() == 3 && term.get(1).weight() > term.get(2).weight();

        var potentialAssertion = new Assertion.PotentialAssertion(
                term,
                Cog.INPUT_ASSERTION_BASE_PRIORITY,
                Set.of(),
                sourceId,
                isEquality,
                isNegated,
                isOrientedEquality,
                sourceNoteId,
                type,
                quantifiedVars,
                0
        );

        context.kb(kbId).commit(potentialAssertion, sourceId);
    }
}
