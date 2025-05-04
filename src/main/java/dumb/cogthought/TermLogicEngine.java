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
    private final LLMService llmService;
    private final ApiGateway apiGateway;
    private final Events events;

    public TermLogicEngine(KnowledgeBase knowledgeBase, ToolRegistry toolRegistry, LLMService llmService, ApiGateway apiGateway, Events events) {
        this.knowledgeBase = knowledgeBase;
        this.toolRegistry = toolRegistry;
        this.llmService = llmService;
        this.apiGateway = apiGateway;
        this.events = events;
        info("TermLogicEngine initialized.");
    }

    public CompletableFuture<Void> processTerm(Term inputTerm) {
        info("Processing term: " + inputTerm.toKif());

        var matchingRules = knowledgeBase.findMatchingRules(inputTerm);

        var futures = matchingRules.map(rule -> {
            Optional<Map<Term.Var, Term>> bindingsOpt = Logic.unify(rule.antecedent(), inputTerm);

            return bindingsOpt.map(bindings -> {
                info("Rule matched: " + rule.id() + " with bindings: " + bindings);

                Term actionTerm = Logic.subst(rule.consequent(), bindings);

                return interpretActionTerm(actionTerm, rule, bindings);

            }).orElseGet(() -> {
                Log.warn("Rule " + rule.id() + " matched pattern but failed unification with " + inputTerm.toKif());
                return CompletableFuture.completedFuture(null);
            });
        }).toList();

        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    private CompletableFuture<Void> interpretActionTerm(Term actionTerm, Rule sourceRule, Map<Term.Var, Term> bindings) {
        if (!(actionTerm instanceof Term.Lst actionList) || actionList.terms.isEmpty()) {
            Log.warn("Rule " + sourceRule.id() + " produced non-action term: " + actionTerm.toKif());
            return CompletableFuture.completedFuture(null);
        }

        Optional<Term.Atom> opOpt = actionList.op();
        if (opOpt.isEmpty()) {
            Log.warn("Rule " + sourceRule.id() + " produced action term without operator: " + actionTerm.toKif());
            return CompletableFuture.completedFuture(null);
        }

        String operator = opOpt.get().name();

        return switch (operator) {
            case "Assert" -> {
                if (actionList.size() >= 2 && actionList.get(1) instanceof Term.Lst assertionKif) {
                    var potentialAssertion = new Assertion.PotentialAssertion(
                        assertionKif,
                        sourceRule.pri(),
                        Collections.singleton(sourceRule.id()),
                        sourceRule.id(),
                        Logic.isEquality(assertionKif),
                        Logic.isNegated(assertionKif),
                        Logic.isOrientedEquality(assertionKif),
                        sourceRule.sourceNoteId(),
                        Logic.determineAssertionType(assertionKif),
                        Logic.collectQuantifiedVars(assertionKif),
                        sourceRule.derivationDepth() + 1
                    );
                    knowledgeBase.saveAssertion(new Assertion(
                        Cog.id(Cog.ID_PREFIX_ASSERTION),
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
                        true,
                        potentialAssertion.sourceNoteId() != null ? potentialAssertion.sourceNoteId() : Cog.GLOBAL_KB_NOTE_ID
                    ));
                    info("Asserted: " + assertionKif.toKif());
                    yield CompletableFuture.completedFuture(null);
                } else {
                    Log.warn("Rule " + sourceRule.id() + " produced invalid Assert action: " + actionTerm.toKif());
                    yield CompletableFuture.completedFuture(null);
                }
            }
            case "Retract" -> {
                 if (actionList.size() >= 2) {
                     Term target = actionList.get(1);
                     if (target instanceof Term.Lst targetKif) {
                         knowledgeBase.queryAssertions(targetKif)
                                      .filter(a -> a.sourceNoteId() == null || a.sourceNoteId().equals(sourceRule.sourceNoteId()))
                                      .findFirst()
                                      .ifPresentOrElse(
                                          assertionToRetract -> {
                                              knowledgeBase.deleteAssertion(assertionToRetract.id());
                                              info("Retracted assertion by KIF: " + targetKif.toKif());
                                          },
                                          () -> Log.warn("Rule " + sourceRule.id() + " attempted to retract non-existent assertion by KIF: " + targetKif.toKif())
                                      );
                     } else if (target instanceof Term.Atom targetId) {
                         knowledgeBase.loadAssertion(targetId.name())
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
                if (actionList.size() >= 2 && actionList.get(1) instanceof Term.Atom toolNameAtom) {
                    String toolName = toolNameAtom.name();
                    Term parameters = actionList.size() > 2 ? actionList.get(2) : Term.Lst.of();

                    return toolRegistry.getTool(toolName)
                                       .map(tool -> {
                                           info("Executing tool: " + toolName + " with parameters: " + parameters.toKif());
                                           // Create and pass the proper ToolContext
                                           ToolContext toolContext = new ToolContext() {
                                               @Override public KnowledgeBase getKnowledgeBase() { return knowledgeBase; }
                                               @Override public LLMService getLlmService() { return llmService; }
                                               @Override public ApiGateway getApiGateway() { return apiGateway; }
                                               @Override public Events getEvents() { return events; }
                                           };
                                           try {
                                               return tool.execute(parameters, toolContext)
                                                          .thenAccept(result -> {
                                                              info("Tool execution complete: " + toolName + ". Result: " + result);
                                                              // TODO: Assert tool result into KB as a Term (Phase 3)
                                                          })
                                                          .exceptionally(e -> {
                                                              error("Tool execution failed for " + toolName + ": " + e.getMessage());
                                                              e.printStackTrace();
                                                              // TODO: Assert tool error into KB as a Term (Phase 3)
                                                              return null;
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
            default -> {
                Log.warn("Rule " + sourceRule.id() + " produced unhandled action term: " + actionTerm.toKif());
                yield CompletableFuture.completedFuture(null);
            }
        };
    }

    public static Term.Lst simplifyLogicalTerm(Term.Lst term) {
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

    public static Term performSkolemization(Term existentialFormula, Map<Term.Var, Term> contextBindings) {
        Log.warn("Skolemization logic is a placeholder.");
        if (existentialFormula instanceof Term.Lst list && list.size() == 3 &&
            (list.op().filter(Logic.KIF_OP_EXISTS::equals).isPresent() || list.op().filter(Logic.KIF_OP_FORALL::equals).isPresent())) {
            return list.get(2);
        }
        return existentialFormula;
    }

    public static Rule renameRuleVariables(Rule rule, int depth) {
        Log.warn("Variable renaming logic is a placeholder.");
        return rule;
    }
}
