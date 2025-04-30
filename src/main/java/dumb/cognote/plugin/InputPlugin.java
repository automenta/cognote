package dumb.cognote.plugin;

import dumb.cognote.*;

import java.util.List;
import java.util.Set;

import static dumb.cognote.Cog.DEFAULT_RULE_PRIORITY;
import static dumb.cognote.Log.message;

public class InputPlugin extends Plugin.BasePlugin {

    @Override
    public void start(dumb.cognote.Events e, Logic.Cognition ctx) {
        super.start(e, ctx);
        events.on(CogEvent.ExternalInputEvent.class, this::handleExternalInput);
        log("InputPlugin started.");
    }

    private void handleExternalInput(CogEvent.ExternalInputEvent event) {
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
                        // Handle as a rule
                        try {
                            var rule = Rule.parseRule(Cog.id(Cog.ID_PREFIX_RULE), termList, DEFAULT_RULE_PRIORITY, noteId);
                            if (context.addRule(rule)) {
                                message("InputPlugin: Added rule from " + sourceId + ": " + rule.form().toKif());
                            } else {
                                message("InputPlugin: Rule already exists from " + sourceId + ": " + rule.form().toKif());
                            }
                            return; // Processed as a rule
                        } catch (IllegalArgumentException e) {
                            // Not a valid rule format, log and fall through to try as assertion
                            logWarning("InputPlugin: Input looks like a rule but is invalid: " + e.getMessage());
                        }
                    }
                    case "query" -> {
                        // Handle as a query request
                        try {
                            if (termList.size() < 2 || !(termList.get(1) instanceof Term.Lst pattern)) {
                                throw new IllegalArgumentException("Query format must be (query <pattern> [params]).");
                            }
                            var queryId = Cog.id(Cog.ID_PREFIX_QUERY);
                            var queryTypeStr = "ASK_BINDINGS"; // Default query type
                            var parameters = java.util.Map.<String, Object>of();

                            if (termList.size() > 2) {
                                if (termList.size() == 3 && termList.get(2) instanceof Term.Lst paramsList && paramsList.op().filter("params"::equals).isPresent()) {
                                    // Simple parsing for (params (key value) ...)
                                    parameters = paramsList.terms.stream().skip(1)
                                            .filter(t -> t instanceof Term.Lst pair && pair.size() == 2 && pair.get(0) instanceof Term.Atom)
                                            .collect(java.util.stream.Collectors.toMap(
                                                    t -> ((Term.Atom) ((Term.Lst) t).get(0)).value(),
                                                    t -> ((Term.Lst) t).get(1) // Keep value as Term for now, conversion happens later if needed
                                            ));
                                } else {
                                    throw new IllegalArgumentException("Query parameters must be in a (params (...)) list.");
                                }
                            }

                            // Check for query_type parameter
                            if (parameters.containsKey("query_type") && parameters.get("query_type") instanceof Term.Atom(
                                    var value
                            )) {
                                queryTypeStr = value;
                                parameters = new java.util.HashMap<>(parameters);
                                parameters.remove("query_type"); // Remove from params map passed to reasoner
                            }

                            Cog.QueryType queryType;
                            try {
                                queryType = Cog.QueryType.valueOf(queryTypeStr.toUpperCase());
                            } catch (IllegalArgumentException e) {
                                throw new IllegalArgumentException("Invalid queryType: " + queryTypeStr);
                            }

                            var query = new Query(queryId, queryType, pattern, noteId, parameters);
                            events.emit(new Query.QueryEvent(query));
                            message("InputPlugin: Submitted query from " + sourceId + ": " + query.pattern().toKif());
                            return; // Processed as a query

                        } catch (IllegalArgumentException e) {
                            logWarning("InputPlugin: Input looks like a query but is invalid: " + e.getMessage());
                            // Fall through to try as assertion if query parsing fails
                        }
                    }
                    // Add other top-level KIF forms here if needed (e.g., "command")
                    default -> {
                        // Fall through to handle as assertion
                    }
                }
            }

            // If not handled as rule or query, try to commit as an assertion
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

        } else {
            // Handle non-list terms if necessary, currently just logs a warning
            logWarning("InputPlugin: Ignoring non-list input term from " + sourceId + ": " + term.toKif());
        }
    }
}
