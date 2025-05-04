package dumb.cogthought;

import dumb.cogthought.util.Events;
import dumb.cogthought.util.Log;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static dumb.cogthought.Logic.KIF_OP_NOT;
import static dumb.cogthought.util.Log.error;
import static dumb.cogthought.util.Log.info;

public class TermLogicEngine {

    private final KnowledgeBase knowledgeBase;
    private final ToolRegistry toolRegistry;
    private final LLMService llmService; // Keep reference for ToolContext
    private final ApiGateway apiGateway; // Keep reference for ToolContext
    private final Events events; // Keep reference for ToolContext

    public TermLogicEngine(KnowledgeBase knowledgeBase, ToolRegistry toolRegistry, LLMService llmService, ApiGateway apiGateway, Events events) {
        this.knowledgeBase = knowledgeBase;
        this.toolRegistry = toolRegistry;
        this.llmService = llmService;
        this.apiGateway = apiGateway;
        this.events = events;
        info("TermLogicEngine initialized.");
    }

    /**
     * Processes an input term by finding matching rules and interpreting their consequent actions.
     *
     * @param inputTerm The term to process (e.g., an asserted fact, an API request term, an event term).
     * @return A CompletableFuture that completes when all triggered actions have finished.
     */
    public CompletableFuture<Void> processTerm(Term inputTerm) {
        info("Processing term: " + inputTerm.toKif());

        // Find rules whose antecedent unifies with the input term
        var matchingRules = knowledgeBase.findMatchingRules(inputTerm);

        var futures = matchingRules.map(rule -> {
            Optional<Map<Term.Var, Term>> bindingsOpt = Logic.unify(rule.antecedent(), inputTerm);

            return bindingsOpt.map(bindings -> {
                info("Rule matched: " + rule.id() + " with bindings: " + bindings);

                // Substitute variables in the consequent to get the action term
                Term actionTerm = Logic.subst(rule.consequent(), bindings);

                // Interpret and execute the action term
                return interpretActionTerm(actionTerm, rule, bindings);

            }).orElseGet(() -> {
                // This case should ideally not happen if findMatchingRules uses unification correctly,
                // but included for robustness.
                Log.warn("Rule " + rule.id() + " matched pattern but failed unification with " + inputTerm.toKif());
                return CompletableFuture.completedFuture(null);
            });
        }).toList(); // Collect futures to a list before combining

        // Wait for all action futures to complete
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    /**
     * Interprets a term produced by a rule consequent as an action and executes it.
     * Actions are typically calls to Primitive Tools.
     *
     * @param actionTerm The term representing the action (e.g., (Assert ...), (ExecuteTool ...)).
     * @param sourceRule The rule that produced this action term.
     * @param bindings   The bindings from the rule match.
     * @return A CompletableFuture that completes when the action execution finishes.
     */
    private CompletableFuture<Void> interpretActionTerm(Term actionTerm, Rule sourceRule, Map<Term.Var, Term> bindings) {
        if (!(actionTerm instanceof Term.Lst actionList) || actionList.terms.isEmpty()) {
            Log.warn("Rule " + sourceRule.id() + " produced non-list or empty action term: " + actionTerm.toKif());
            return CompletableFuture.completedFuture(null);
        }

        Optional<Term.Atom> opOpt = actionList.op();
        if (opOpt.isEmpty()) {
            Log.warn("Rule " + sourceRule.id() + " produced action term without operator: " + actionTerm.toKif());
            return CompletableFuture.completedFuture(null);
        }

        String operator = opOpt.get().name();
        // The parameters for the tool are the terms *after* the operator in the action list
        Term parameters = actionList.size() > 1 ? new Term.Lst(actionList.terms.subList(1, actionList.size())) : Term.Lst.of();

        // Create ToolContext for the tool execution
        ToolContext toolContext = new ToolContext() {
            @Override public KnowledgeBase getKnowledgeBase() { return knowledgeBase; }
            @Override public LLMService getLlmService() { return llmService; }
            @Override public ApiGateway getApiGateway() { return apiGateway; }
            @Override public Events getEvents() { return events; }
        };

        // Delegate action execution to the ToolRegistry
        return toolRegistry.getTool(operator)
                           .map(tool -> {
                               info("Executing tool '" + operator + "' triggered by rule " + sourceRule.id() + " with parameters: " + parameters.toKif());
                               try {
                                   return tool.execute(parameters, toolContext)
                                              .thenAccept(result -> {
                                                  info("Tool execution complete: " + operator + ". Result: " + result);
                                                  // TODO: Assert tool result into KB as a Term (Phase 3)
                                              })
                                              .exceptionally(e -> {
                                                  error("Tool execution failed for '" + operator + "' triggered by rule " + sourceRule.id() + ": " + e.getMessage());
                                                  e.printStackTrace();
                                                  // TODO: Assert tool error into KB as a Term (Phase 3)
                                                  return null; // Return null to prevent cascading exceptions in allOf
                                              });
                               } catch (Exception e) {
                                    error("Error calling tool.execute for '" + operator + "' triggered by rule " + sourceRule.id() + ": " + e.getMessage());
                                    e.printStackTrace();
                                    // TODO: Assert tool error into KB
                                    return CompletableFuture.completedFuture(null); // Return completed future on immediate error
                               }
                           })
                           .orElseGet(() -> {
                               Log.warn("Rule " + sourceRule.id() + " produced action for unknown tool: " + operator);
                               // TODO: Assert unknown tool error into KB
                               return CompletableFuture.completedFuture(null); // Return completed future for unknown tool
                           });
    }


    // --- Placeholder/Refactoring Candidates ---
    // These methods are remnants from the old Reasoner and need to be refactored
    // or replaced by data-driven rules and primitive tools in Phase 3.

    /**
     * Placeholder for logical term simplification.
     * This logic might be implemented by rules or a dedicated tool later.
     */
    public static Term.Lst simplifyLogicalTerm(Term.Lst term) {
        // TODO: Implement proper simplification logic or replace with rules/tools
        Log.warn("Logical term simplification logic is a placeholder.");
        final var MAX_DEPTH = 5;
        Term current = term;
        for (var depth = 0; depth < MAX_DEPTH; depth++) {
            Term next = simplifyLogicalTermOnce(current);
            if (next.equals(current)) return (Term.Lst) current;
            current = next;
        }
        if (!term.equals(current))
            error("Warning: Simplification depth limit reached for: " + term.toKif());
        return (Term.Lst) current;
    }

    private static Term simplifyLogicalTermOnce(Term term) {
        if (!(term instanceof Term.Lst list)) return term;

        if (list.op().filter(Logic.KIF_OP_NOT::equals).isPresent() && list.size() == 2 && list.get(1) instanceof Term.Lst nl && nl.op().filter(Logic.KIF_OP_NOT::equals).isPresent() && nl.size() == 2 && nl.get(1) instanceof Term.Lst inner)
            return simplifyLogicalTermOnce(inner);

        boolean changed = false;
        List<Term> newTerms = new ArrayList<>();
        for (Term subTerm : list.terms) {
            Term simplifiedSub = simplifyLogicalTermOnce(subTerm);
            if (!simplifiedSub.equals(subTerm)) changed = true;
            newTerms.add(simplifiedSub);
        }

        return changed ? new Term.Lst(newTerms) : term;
    }

    /**
     * Placeholder for Skolemization logic.
     * This logic might be implemented by rules or a dedicated tool later.
     */
    public static Term performSkolemization(Term existentialFormula, Map<Term.Var, Term> contextBindings) {
        // TODO: Implement proper Skolemization logic or replace with rules/tools
        Log.warn("Skolemization logic is a placeholder.");
        if (existentialFormula instanceof Term.Lst list && list.size() == 3 &&
            (list.op().filter(Logic.KIF_OP_EXISTS::equals).isPresent() || list.op().filter(Logic.KIF_OP_FORALL::equals).isPresent())) {
            // Return the body of the quantified formula as a simplification placeholder
            return list.get(2);
        }
        return existentialFormula;
    }

    /**
     * Placeholder for Variable Renaming logic.
     * This logic might be implemented by rules or a dedicated tool later.
     */
    public static Rule renameRuleVariables(Rule rule, int depth) {
        // TODO: Implement proper Variable Renaming logic or replace with rules/tools
        Log.warn("Variable renaming logic is a placeholder.");
        return rule; // Return original rule as placeholder
    }
}
