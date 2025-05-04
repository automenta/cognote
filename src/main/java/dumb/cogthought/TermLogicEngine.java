package dumb.cogthought;

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

// Refactored from dumb.cognote.Reason to be the minimal, generic interpreter
public class TermLogicEngine {

    private final KnowledgeBase knowledgeBase;
    private final ToolRegistry toolRegistry;
    // private final ToolContext toolContext; // ToolContext needs to be created per execution? Or passed in?

    public TermLogicEngine(KnowledgeBase knowledgeBase, ToolRegistry toolRegistry) {
        this.knowledgeBase = knowledgeBase;
        this.toolRegistry = toolRegistry;
        // this.toolContext = new ToolContextImpl(knowledgeBase, ...); // Needs other dependencies
        info("TermLogicEngine initialized.");
    }

    /**
     * Processes an input Term by attempting to match it against rule antecedents
     * and firing matching rules.
     *
     * @param inputTerm The Term to process (e.g., an asserted fact, an event term, a query term).
     * @return A CompletableFuture that completes when processing triggered by this term is done.
     *         The actual processing might involve asserting new terms or executing tools asynchronously.
     */
    public CompletableFuture<Void> processTerm(Term inputTerm) {
        // This is a simplified, initial implementation of the rule firing loop.
        // More sophisticated control flow, prioritization, and handling of different
        // rule types (forward, backward, rewrite) will be defined by the rules themselves
        // and managed by the SystemControl loop (Phase 3).

        info("Processing term: " + inputTerm.toKif());

        // Find rules whose antecedent unifies with the input term
        var matchingRules = knowledgeBase.findMatchingRules(inputTerm);

        var futures = matchingRules.map(rule -> {
            // Unify the rule's antecedent with the input term
            Optional<Map<Term.Var, Term>> bindingsOpt = Logic.unify(rule.antecedent(), inputTerm);

            return bindingsOpt.map(bindings -> {
                info("Rule matched: " + rule.id() + " with bindings: " + bindings);

                // Substitute bindings into the consequent
                Term actionTerm = Logic.subst(rule.consequent(), bindings);

                // Interpret the resulting action term
                return interpretActionTerm(actionTerm, rule, bindings); // Pass rule and bindings for context/justification

            }).orElseGet(() -> {
                // This case should ideally not happen if findMatchingRules uses unify correctly,
                // but included for safety.
                Log.warn("Rule " + rule.id() + " matched pattern but failed unification with " + inputTerm.toKif());
                return CompletableFuture.completedFuture(null);
            });
        }).toList(); // Collect futures to wait for all rule firings

        // Wait for all triggered actions/assertions from this input term to complete
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    /**
     * Interprets a Term resulting from a rule consequent after substitution.
     * This term is expected to be an action (e.g., (Assert ...), (ExecuteTool ...)).
     *
     * @param actionTerm The Term to interpret as an action.
     * @param sourceRule The rule that produced this action term.
     * @param bindings The bindings from the rule match.
     * @return A CompletableFuture representing the execution of the action.
     */
    private CompletableFuture<Void> interpretActionTerm(Term actionTerm, Rule sourceRule, Map<Term.Var, Term> bindings) {
        if (!(actionTerm instanceof Term.Lst actionList) || actionList.terms.isEmpty()) {
            Log.warn("Rule " + sourceRule.id() + " produced non-action term: " + actionTerm.toKif());
            return CompletableFuture.completedFuture(null);
        }

        var opOpt = actionList.op();
        if (opOpt.isEmpty()) {
            Log.warn("Rule " + sourceRule.id() + " produced action term without operator: " + actionTerm.toKif());
            return CompletableFuture.completedFuture(null);
        }

        var operator = opOpt.get().name();

        // Basic action interpretation (these should correspond to Primitive Tools or KB operations)
        return switch (operator) {
            case "Assert" -> {
                // Example: (Assert (<kif> <priority> <sourceNoteId>))
                if (actionList.size() >= 2 && actionList.get(1) instanceof Term.Lst assertionKif) {
                    // TODO: Extract priority, sourceNoteId, etc. from actionList parameters
                    // For now, create a basic potential assertion
                    var potentialAssertion = new Assertion.PotentialAssertion(
                        assertionKif,
                        sourceRule.pri(), // Use rule priority as base
                        Collections.singleton(sourceRule.id()), // Justified by the rule
                        sourceRule.id(), // Source ID is the rule ID
                        Logic.isEquality(assertionKif), // Determine flags
                        Logic.isNegated(assertionKif),
                        Logic.isOrientedEquality(assertionKif),
                        sourceRule.sourceNoteId(), // Inherit source note from rule
                        Logic.determineAssertionType(assertionKif), // Determine type
                        Logic.collectQuantifiedVars(assertionKif), // Collect quantified vars
                        sourceRule.derivationDepth() + 1 // Increment depth
                    );
                    // Save the assertion to the KB
                    // This should ideally be done via a Primitive Tool (_AssertTool)
                    // For now, direct KB interaction for core Assert/Retract
                    knowledgeBase.saveAssertion(new Assertion(
                        Cog.id(Cog.ID_PREFIX_ASSERTION), // Generate new ID
                        potentialAssertion.kif(),
                        potentialAssertion.pri(),
                        Instant.now(),
                        potentialAssertion.sourceNoteId(),
                        potentialAssertion.support(),
                        potentialAssertion.derivedType(),
                        potentialAssertion.isEquality(),
                        potentialAssertion.isOrientatedEquality(),
                        potentialAssertion.isNegated(),
                        potentialAssertion.quantifiedVars(),
                        potentialAssertion.derivationDepth(),
                        true, // Start active, TMS will adjust
                        potentialAssertion.sourceNoteId() != null ? potentialAssertion.sourceNoteId() : Cog.GLOBAL_KB_NOTE_ID // Determine KB ID
                    ));
                    info("Asserted: " + assertionKif.toKif());
                    yield CompletableFuture.completedFuture(null); // Assertion is synchronous with KB save
                } else {
                    Log.warn("Rule " + sourceRule.id() + " produced invalid Assert action: " + actionTerm.toKif());
                    yield CompletableFuture.completedFuture(null);
                }
            }
            case "Retract" -> {
                 // Example: (Retract (<kif> <sourceNoteId>)) or (Retract <assertionId>)
                 if (actionList.size() >= 2) {
                     var target = actionList.get(1);
                     // This should ideally be done via a Primitive Tool (_RetractTool)
                     // For now, direct KB interaction for core Assert/Retract
                     if (target instanceof Term.Lst targetKif) {
                         // Find assertion by KIF and retract
                         knowledgeBase.queryAssertions(targetKif)
                                      .filter(a -> a.sourceNoteId() == null || a.sourceNoteId().equals(sourceRule.sourceNoteId())) // Optional: filter by source note
                                      .findFirst() // Retract only the first match? Or all? Plan needs to specify.
                                      .ifPresentOrElse(
                                          assertionToRetract -> {
                                              knowledgeBase.deleteAssertion(assertionToRetract.id());
                                              info("Retracted assertion by KIF: " + targetKif.toKif());
                                          },
                                          () -> Log.warn("Rule " + sourceRule.id() + " attempted to retract non-existent assertion by KIF: " + targetKif.toKif())
                                      );
                     } else if (target instanceof Term.Atom targetId) {
                         // Retract by ID
                         knowledgeBase.loadAssertion(targetId.name()) // Assuming Atom name is the ID
                                      .ifPresentOrElse(
                                          assertionToRetract -> {
                                              knowledgeBase.deleteAssertion(assertionToRetract.id());
                                              info("Retracted assertion by ID: " + targetId.name());
                                          },
                                          () -> Log.warn("Rule " + sourceRule.id() + " attempted to retract non-existent assertion by ID: " + targetId.name())
                                      );
                     } else {
                         Log.warn("Rule " + sourceRule.id() + " produced invalid Retract action target: " + target.toKif());
                     }
                     yield CompletableFuture.completedFuture(null);
                 } else {
                     Log.warn("Rule " + sourceRule.id() + " produced invalid Retract action: " + actionTerm.toKif());
                     yield CompletableFuture.completedFuture(null);
                 }
            }
            case "ExecuteTool" -> {
                // Example: (ExecuteTool <toolName> (<param1> <param2> ...))
                if (actionList.size() >= 2 && actionList.get(1) instanceof Term.Atom toolNameAtom) {
                    var toolName = toolNameAtom.name();
                    var parameters = actionList.size() > 2 ? actionList.get(2) : Term.Lst.of(); // Tool parameters are the 3rd element

                    return toolRegistry.getTool(toolName)
                                       .map(tool -> {
                                           info("Executing tool: " + toolName + " with parameters: " + parameters.toKif());
                                           // TODO: Create and pass a proper ToolContext
                                           // For now, pass null or a minimal placeholder
                                           ToolContext placeholderContext = new ToolContext() {
                                               @Override public Object getKnowledgeBase() { return knowledgeBase; }
                                               @Override public Object getLlmService() { return null; } // Needs LLMService
                                               @Override public Object getApiGateway() { return null; } // Needs ApiGateway
                                               @Override public Object getEvents() { return null; } // Needs Events
                                           };
                                           try {
                                               return tool.execute(parameters, placeholderContext)
                                                          .thenAccept(result -> {
                                                              info("Tool execution complete: " + toolName + ". Result: " + result);
                                                              // TODO: Assert tool result into KB as a Term (Phase 3)
                                                              // E.g., (ToolResult <toolName> <resultTerm> <sourceRuleId>)
                                                          })
                                                          .exceptionally(e -> {
                                                              error("Tool execution failed for " + toolName + ": " + e.getMessage());
                                                              e.printStackTrace();
                                                              // TODO: Assert tool error into KB as a Term (Phase 3)
                                                              // E.g., (ToolError <toolName> "<errorMessage>" <sourceRuleId>")
                                                              return null; // Handle exception, don't re-throw
                                                          });
                                           } catch (Exception e) {
                                                error("Error calling tool.execute for " + toolName + ": " + e.getMessage());
                                                e.printStackTrace();
                                                // TODO: Assert tool error into KB
                                                return CompletableFuture.completedFuture(null);
                                           }
                                       })
                                       .orElseGet(() -> {
                                           Log.warn("Rule " + sourceRule.id() + " attempted to execute unknown tool: " + toolName);
                                           // TODO: Assert unknown tool error into KB
                                           return CompletableFuture.completedFuture(null);
                                       });
                } else {
                    Log.warn("Rule " + sourceRule.id() + " produced invalid ExecuteTool action: " + actionTerm.toKif());
                    yield CompletableFuture.completedFuture(null);
                }
            }
            // TODO: Add other core actions like 'Query', 'UpdateNote', 'CreateNote', 'DeleteNote', etc.
            // These should also ideally map to Primitive Tools.
            default -> {
                // If the operator is not a known action, treat it as a potential new term to process?
                // Or log a warning? For now, log warning.
                Log.warn("Rule " + sourceRule.id() + " produced unhandled action term: " + actionTerm.toKif());
                yield CompletableFuture.completedFuture(null);
            }
        };
    }

    // Helper methods adapted from old Cognition/Reason logic, potentially moved or refactored further
    // These might become Primitive Tools or part of the TermLogicEngine's internal logic
    // depending on the final design. For now, keeping them here as placeholders.

    // Adapted from Cognition::simplifyLogicalTerm
    public static Term.Lst simplifyLogicalTerm(Term.Lst term) {
        final var MAX_DEPTH = 5; // Should be configurable?
        var current = term;
        for (var depth = 0; depth < MAX_DEPTH; depth++) {
            var next = simplifyLogicalTermOnce(current);
            if (next.equals(current)) return current;
            current = next;
        }
        if (!term.equals(current))
            error("Warning: Simplification depth limit reached for: " + term.toKif());
        return current;
    }

    private static Term.Lst simplifyLogicalTermOnce(Term.Lst term) {
        // Example simplification: (not (not X)) -> X
        if (term.op().filter(Logic.KIF_OP_NOT::equals).isPresent() && term.size() == 2 && term.get(1) instanceof Term.Lst nl && nl.op().filter(Logic.KIF_OP_NOT::equals).isPresent() && nl.size() == 2 && nl.get(1) instanceof Term.Lst inner)
            return simplifyLogicalTermOnce(inner); // Recursive simplification

        // Recursively simplify sub-terms
        var changed = new boolean[]{false};
        var newTerms = term.terms.stream().map(subTerm -> {
            var simplifiedSub = (subTerm instanceof Term.Lst sl) ? simplifyLogicalTermOnce(sl) : subTerm;
            if (!simplifiedSub.equals(subTerm)) changed[0] = true;
            return simplifiedSub;
        }).toList();

        // If any sub-term changed, create a new list term
        return changed[0] ? new Term.Lst(newTerms) : term;
    }

    // Adapted from Cognition::performSkolemization
    public static Term performSkolemization(Term existentialFormula, Map<Term.Var, Term> contextBindings) {
        // This is a complex logic that might be better as a dedicated Primitive Tool or a helper class
        // For now, keeping the placeholder. The actual implementation needs to handle
        // generating unique skolem constants/functions based on the context bindings.
        Log.warn("Skolemization logic is a placeholder.");
        // Example placeholder: just remove the quantifier and variables
        if (existentialFormula instanceof Term.Lst list && list.size() == 3 &&
            (list.op().filter(Logic.KIF_OP_EXISTS::equals).isPresent() || list.op().filter(Logic.KIF_OP_FORALL::equals).isPresent())) {
            return list.get(2); // Return the body without quantifier/vars
        }
        return existentialFormula; // Return as is if not an existential/universal
    }

    // Adapted from Reason::BackwardChainingReasonerPlugin::renameRuleVariables
    public static Rule renameRuleVariables(Rule rule, int depth) {
        // This logic is specific to backward chaining and might be implemented by
        // specific backward chaining rules or a helper tool, not the core engine.
        // Keeping placeholder for now.
        Log.warn("Variable renaming logic is a placeholder.");
        return rule; // Return original rule for now
    }

    // Old methods like prove, proveAntecedents, handleAssertionAdded, applyRewrite, tryInstantiate
    // are removed as their logic is specific to reasoning strategies that should be defined as Rules.
}
