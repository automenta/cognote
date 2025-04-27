package dumb.cognote;

import dumb.cognote.Cog.QueryStatus;
import dumb.cognote.Cog.QueryType;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dumb.cognote.Cog.ID_PREFIX_PLUGIN;
import static dumb.cognote.Log.error;
import static dumb.cognote.Log.message;
import static dumb.cognote.Logic.*;
import static java.util.Optional.ofNullable;

public class Reason {
    private static final int MAX_BACKWARD_CHAIN_DEPTH = 8;
    private static final int MAX_DERIVED_TERM_WEIGHT = 150;

    public record Reasoning(Logic.Cognition cognition, Events events, DialogueManager dialogueManager) {
        Knowledge getKb(@Nullable String noteId) {
            return cognition.kb(noteId);
        }

        Set<Rule> rules() {
            return cognition.rules();
        }

        Cog.Configuration getConfig() {
            return new Cog.Configuration(cognition.cog);
        }

        Truths getTMS() {
            return cognition.truth;
        }

        Op.Operators operators() {
            return cognition.operators;
        }

        Set<String> getActiveNoteIds() {
            return cognition.activeNoteIds;
        }
    }

    static class ReasonerManager {
        private final Events events;
        private final Reasoning reasoning;
        private final List<Plugin.ReasonerPlugin> plugins = new CopyOnWriteArrayList<>();
        private final AtomicBoolean initialized = new AtomicBoolean(false);

        ReasonerManager(Events events, Logic.Cognition ctx, DialogueManager dialogueManager) {
            this.events = events;
            this.reasoning = new Reasoning(ctx, events, dialogueManager);
        }

        public void loadPlugin(Plugin.ReasonerPlugin plugin) {
            if (initialized.get()) {
                error("Cannot load reasoner plugin " + plugin.id() + " after initialization.");
                return;
            }
            plugins.add(plugin);
            message("Reasoner plugin loaded: " + plugin.id());
        }

        public void initializeAll() {
            if (!initialized.compareAndSet(false, true)) return;
            message("Initializing " + plugins.size() + " reasoner plugins...");
            plugins.forEach(plugin -> {
                try {
                    plugin.initialize(reasoning);
                    message("Initialized reasoner plugin: " + plugin.id());
                } catch (Exception e) {
                    error("Failed to initialize reasoner plugin " + plugin.id() + ": " + e.getMessage());
                    e.printStackTrace();
                    plugins.remove(plugin);
                }
            });

            events.on(Query.QueryEvent.class, this::handleQueryRequest);
            message("Reasoner plugin initialization complete.");
        }

        private void handleQueryRequest(Query.QueryEvent event) {
            var query = event.query();
            var futures = plugins.stream()
                    .filter(p -> p.getSupportedQueryTypes().contains(query.type()))
                    .map(p -> p.executeQuery(query))
                    .toList();

            if (futures.isEmpty()) {
                events.emit(new Answer.AnswerEvent(Answer.failure(query.id())));
                return;
            }

            // Combine results from all relevant reasoners
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .thenApplyAsync(v -> {
                        List<Map<Term.Var, Term>> allBindings = new ArrayList<>();
                        var overallStatus = QueryStatus.FAILURE;
                        Explanation combinedExplanation = null;

                        for (var future : futures) {
                            try {
                                var result = future.join(); // Wait for each reasoner's result
                                var e = result.explanation();
                                var s = result.status();

                                if (s == QueryStatus.SUCCESS) {
                                    if (query.type() == QueryType.ASK_BINDINGS || query.type() == QueryType.ASK_TRUE_FALSE) {
                                        allBindings.addAll(result.bindings());
                                    }
                                    overallStatus = QueryStatus.SUCCESS; // If any reasoner succeeds, the overall query succeeds
                                    if (e != null) combinedExplanation = e; // Keep the last explanation? Or combine? For now, last one wins.
                                    if (query.type() == QueryType.ACHIEVE_GOAL) break; // For ACHIEVE_GOAL, first success is enough

                                } else if (s != QueryStatus.FAILURE && overallStatus == QueryStatus.FAILURE) {
                                    // If no reasoner succeeded, but some had non-FAILURE status (e.g., TIMEOUT, ERROR),
                                    // propagate the first non-FAILURE status.
                                    overallStatus = s;
                                    if (e != null) combinedExplanation = e;
                                }
                            } catch (CompletionException | CancellationException e) {
                                error("Query execution error for " + query.id() + ": " + e.getMessage());
                                if (overallStatus != QueryStatus.ERROR) {
                                    overallStatus = QueryStatus.ERROR;
                                    combinedExplanation = new Explanation(e.getMessage());
                                }
                            }
                        }

                        // For ASK_TRUE_FALSE, success means finding at least one binding
                        if (query.type() == QueryType.ASK_TRUE_FALSE) {
                            overallStatus = allBindings.isEmpty() ? QueryStatus.FAILURE : QueryStatus.SUCCESS;
                        }

                        // Ensure unique bindings for ASK_BINDINGS
                        if (query.type() == QueryType.ASK_BINDINGS) {
                            // Convert bindings to a comparable format (e.g., sorted string representation)
                            // and use a Set to get unique ones, then convert back to List<Map<Var, Term>>
                            var uniqueBindings = allBindings.stream()
                                    .map(bindingMap -> {
                                        List<String> entryStrings = new ArrayList<>();
                                        bindingMap.forEach((var, term) -> entryStrings.add(var.name() + "=" + term.toKif()));
                                        Collections.sort(entryStrings);
                                        return Map.entry(String.join(",", entryStrings), bindingMap);
                                    })
                                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (existing, replacement) -> existing, HashMap::new)) // Use HashMap for mutable map
                                    .values().stream().toList();
                            allBindings = uniqueBindings;
                        }


                        return new Answer(query.id(), overallStatus, allBindings, combinedExplanation);
                    }, reasoning.events.exe) // Use the events executor for combining results
                    .thenAccept(result -> events.emit(new Answer.AnswerEvent(result)));
        }

        public void shutdownAll() {
            message("Shutting down " + plugins.size() + " reasoner plugins...");
            plugins.forEach(plugin -> {
                try {
                    plugin.stop();
                    message("Shutdown reasoner plugin: " + plugin.id());
                } catch (Exception e) {
                    error("Error shutting down plugin " + plugin.id() + ": " + e.getMessage());
                    e.printStackTrace();
                }
            });
            plugins.clear();
            message("Reasoner plugin shutdown complete.");
        }

        private Truths getTMS() {
            return reasoning.getTMS();
        }
    }


    abstract static class BaseReasonerPlugin implements Plugin.ReasonerPlugin {
        protected final String id = Cog.id(ID_PREFIX_PLUGIN + getClass().getSimpleName().replace("ReasonerPlugin", "").toLowerCase() + "_");
        protected Reasoning context;

        @Override
        public String id() {
            return id;
        }

        @Override
        public void initialize(Reasoning ctx) {
            this.context = ctx;
        }

        protected void publish(Cog.CogEvent event) {
            if (context != null && context.events() != null) context.events().emit(event);
        }

        protected Knowledge getKb(@Nullable String noteId) {
            return context.getKb(noteId);
        }

        protected Truths getTMS() {
            return context.getTMS();
        }

        protected Logic.Cognition getCogNoteContext() {
            return context.cognition();
        }

        protected int getDerivationDepthMax() {
            return context.getConfig().reasoningDepthLimit();
        }

        @Nullable
        protected Assertion tryCommit(Assertion.PotentialAssertion pa, String source) {
            return getCogNoteContext().tryCommit(pa, source);
        }

        protected boolean isActiveContext(@Nullable String noteId) {
            return noteId != null && context.getActiveNoteIds().contains(noteId);
        }

        @Override
        public CompletableFuture<Answer> executeQuery(Query query) {
            return CompletableFuture.completedFuture(Answer.failure(query.id()));
        }

        @Override
        public Set<QueryType> getSupportedQueryTypes() {
            return Set.of();
        }

        @Override
        public Set<Cog.Feature> getSupportedFeatures() {
            return Set.of();
        }
    }

    static class ForwardChainingReasonerPlugin extends BaseReasonerPlugin {
        @Override
        public void initialize(Reasoning ctx) {
            super.initialize(ctx);
            ctx.events().on(Cog.AssertedEvent.class, this::handleAssertionAdded);
        }

        @Override
        public Set<Cog.Feature> getSupportedFeatures() {
            return Set.of(Cog.Feature.FORWARD_CHAINING);
        }

        private void handleAssertionAdded(Cog.AssertedEvent event) {
            var newAssertion = event.assertion();
            var sourceKbId = event.kbId();
            if (!isActiveContext(sourceKbId) && !isActiveContext(newAssertion.sourceNoteId())) return;

            if (!newAssertion.isActive() || (newAssertion.type() != Logic.AssertionType.GROUND && newAssertion.type() != Logic.AssertionType.SKOLEMIZED))
                return;

            context.rules().stream()
                    .filter(rule -> isActiveContext(rule.sourceNoteId()))
                    .forEach(rule -> rule.antecedents().forEach(clause -> {
                        var neg = (clause instanceof Term.Lst l && l.op().filter(KIF_OP_NOT::equals).isPresent());
                        if (neg == newAssertion.negated()) {
                            var pattern = neg ? ((Term.Lst) clause).get(1) : clause;
                            ofNullable(Logic.Unifier.unify(pattern, newAssertion.getEffectiveTerm(), Map.of()))
                                    .ifPresent(bindings -> findMatchesRecursive(rule, rule.antecedents(), bindings, Set.of(newAssertion.id()), sourceKbId)
                                            .forEach(match -> processDerivedAssertion(rule, match)));
                        }
                    }));
        }

        private Stream<MatchResult> findMatchesRecursive(Rule rule, List<Term> remaining, Map<Term.Var, Term> bindings, Set<String> support, String currentKbId) {
            if (getCogNoteContext().calculateDerivedDepth(support) + 1 > getDerivationDepthMax())
                return Stream.empty();
            if (remaining.isEmpty()) return Stream.of(new MatchResult(bindings, support));

            var clause = Logic.Unifier.substFully(remaining.getFirst(), bindings);
            var nextRemaining = remaining.subList(1, remaining.size());
            var neg = (clause instanceof Term.Lst l && l.op().filter(KIF_OP_NOT::equals).isPresent());
            var pattern = neg ? ((Term.Lst) clause).get(1) : clause;
            if (!(pattern instanceof Term.Lst)) return Stream.empty();

            var currentKb = getKb(currentKbId);
            var globalKb = context.getKb(Cog.GLOBAL_KB_NOTE_ID);


            final Stream<Assertion>[] assertionStream = new Stream[]{currentKb.findUnifiableAssertions(pattern)};

            context.getActiveNoteIds().stream()
                    .filter(id -> !id.equals(currentKbId) && !id.equals(Cog.GLOBAL_KB_NOTE_ID))
                    .map(this::getKb)
                    .forEach(kb -> assertionStream[0] = Stream.concat(assertionStream[0], kb.findUnifiableAssertions(pattern)));

            if (!currentKbId.equals(Cog.GLOBAL_KB_NOTE_ID)) {
                assertionStream[0] = Stream.concat(assertionStream[0], globalKb.findUnifiableAssertions(pattern));
            }

            return assertionStream[0]
                    .distinct()
                    .filter(Assertion::isActive)
                    .filter(a -> isActiveContext(a.kb()) || isActiveContext(a.sourceNoteId()))
                    .filter(c -> c.negated() == neg)
                    .flatMap(c -> ofNullable(Logic.Unifier.unify(pattern, c.getEffectiveTerm(), bindings))
                            .map(newB -> findMatchesRecursive(rule, nextRemaining, newB,
                                    Stream.concat(support.stream(), Stream.of(c.id())).collect(Collectors.toSet()), c.kb()))
                            .orElse(Stream.empty()));
        }

        private void processDerivedAssertion(Rule rule, MatchResult result) {
            var consequent = Logic.Unifier.substFully(rule.consequent(), result.bindings());
            if (consequent == null) return;

            var simplified = (consequent instanceof Term.Lst kl) ? Cognition.simplifyLogicalTerm(kl) : consequent;
            var targetNoteId = getCogNoteContext().commonSourceNodeId(result.supportIds());

            if (!isActiveContext(targetNoteId) && !isActiveContext(rule.sourceNoteId())) return;


            switch (simplified) {
                case Term.Lst derived when derived.op().filter(KIF_OP_AND::equals).isPresent() ->
                        processDerivedConjunction(rule, derived, result, targetNoteId);
                case Term.Lst derived when derived.op().filter(KIF_OP_FORALL::equals).isPresent() ->
                        processDerivedForall(rule, derived, result, targetNoteId);
                case Term.Lst derived when derived.op().filter(KIF_OP_EXISTS::equals).isPresent() ->
                        processDerivedExists(rule, derived, result, targetNoteId);
                case Term.Lst derived -> processDerivedStandard(rule, derived, result, targetNoteId);
                case Term term when !(term instanceof Term.Var) ->
                        error("Warning: Rule " + rule.id() + " derived non-list/non-var consequent: " + term.toKif());
                default -> {
                }
            }
        }

        private void processDerivedConjunction(Rule rule, Term.Lst conj, MatchResult result, @Nullable String targetNoteId) {
            conj.terms.stream().skip(1).forEach(term -> {
                var simp = (term instanceof Term.Lst kl) ? Cognition.simplifyLogicalTerm(kl) : term;
                if (simp instanceof Term.Lst c)
                    processDerivedAssertion(new Rule(rule.id(), rule.form(), rule.antecedent(), c, rule.pri(), rule.antecedents(), rule.sourceNoteId()), result);
                else if (!(simp instanceof Term.Var))
                    error("Warning: Rule " + rule.id() + " derived (and ...) with non-list/non-var conjunct: " + term.toKif());
            });
        }

        private void processDerivedForall(Rule rule, Term.Lst forall, MatchResult result, @Nullable String targetNoteId) {
            if (forall.size() != 3 || !(forall.get(1) instanceof Term.Lst || forall.get(1) instanceof Term.Var) || !(forall.get(2) instanceof Term.Lst body))
                return;
            var vars = Term.collectSpecVars(forall.get(1));
            if (vars.isEmpty()) {
                processDerivedStandard(rule, body, result, targetNoteId);
                return;
            }

            var depth = getCogNoteContext().calculateDerivedDepth(result.supportIds()) + 1;
            if (depth > getDerivationDepthMax()) return;

            if (body.op().filter(op -> op.equals(KIF_OP_IMPLIES) || op.equals(KIF_OP_EQUIV)).isPresent()) {
                try {
                    var pri = getCogNoteContext().calculateDerivedPri(result.supportIds(), rule.pri());
                    var derivedRule = Rule.parseRule(Cog.id(Cog.ID_PREFIX_RULE + "derived_"), body, pri, targetNoteId);
                    getCogNoteContext().addRule(derivedRule);
                    if (KIF_OP_EQUIV.equals(body.op().orElse(null))) {
                        var revList = new Term.Lst(new Term.Atom(KIF_OP_IMPLIES), body.get(2), body.get(1));
                        var revRule = Rule.parseRule(Cog.id(Cog.ID_PREFIX_RULE + "derived_"), revList, pri, targetNoteId);
                        getCogNoteContext().addRule(revRule);
                    }
                } catch (IllegalArgumentException e) {
                    error("Invalid derived rule format ignored: " + body.toKif() + " from rule " + rule.id() + " | Error: " + e.getMessage());
                }
            } else {
                tryCommit(new Assertion.PotentialAssertion(forall, getCogNoteContext().calculateDerivedPri(result.supportIds(), rule.pri()), result.supportIds(), rule.id(), false, false, false, targetNoteId, AssertionType.UNIVERSAL, List.copyOf(vars), depth), rule.id());
            }
        }

        private void processDerivedExists(Rule rule, Term.Lst exists, MatchResult result, @Nullable String targetNoteId) {
            if (exists.size() != 3 || !(exists.get(1) instanceof Term.Lst || exists.get(1) instanceof Term.Var) || !(exists.get(2) instanceof Term.Lst body)) {
                error("Rule " + rule.id() + " derived invalid 'exists' structure: " + exists.toKif());
                return;
            }
            var vars = Term.collectSpecVars(exists.get(1));
            if (vars.isEmpty()) {
                processDerivedStandard(rule, body, result, targetNoteId);
                return;
            }

            var depth = getCogNoteContext().calculateDerivedDepth(result.supportIds()) + 1;
            if (depth > getDerivationDepthMax()) return;

            var skolemBody = Cognition.performSkolemization(body, vars, result.bindings());
            var isNeg = skolemBody.op().filter(KIF_OP_NOT::equals).isPresent();
            var isEq = !isNeg && skolemBody.op().filter(KIF_OP_EQUAL::equals).isPresent();
            var isOriented = isEq && skolemBody.size() == 3 && skolemBody.get(1).weight() > skolemBody.get(2).weight();
            var type = skolemBody.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
            var pa = new Assertion.PotentialAssertion(skolemBody, getCogNoteContext().calculateDerivedPri(result.supportIds(), rule.pri()), result.supportIds(), rule.id(), isEq, isNeg, isOriented, targetNoteId, type, List.of(), depth);
            tryCommit(pa, rule.id());
        }

        private void processDerivedStandard(Rule rule, Term.Lst derived, MatchResult result, @Nullable String targetNoteId) {
            if (derived.containsVar() || isTrivial(derived)) return;

            var depth = getCogNoteContext().calculateDerivedDepth(result.supportIds()) + 1;
            if (depth > getDerivationDepthMax() || derived.weight() > MAX_DERIVED_TERM_WEIGHT) return;

            var isNeg = derived.op().filter(KIF_OP_NOT::equals).isPresent();
            if (isNeg && derived.size() != 2) {
                error("Rule " + rule.id() + " derived invalid 'not': " + derived.toKif());
                return;
            }
            var isEq = !isNeg && derived.op().filter(KIF_OP_EQUAL::equals).isPresent();
            var isOriented = isEq && derived.size() == 3 && derived.get(1).weight() > derived.get(2).weight();
            var type = derived.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
            var pa = new Assertion.PotentialAssertion(derived, getCogNoteContext().calculateDerivedPri(result.supportIds(), rule.pri()), result.supportIds(), rule.id(), isEq, isNeg, isOriented, targetNoteId, type, List.of(), depth);
            tryCommit(pa, rule.id());
        }

        record MatchResult(Map<Term.Var, Term> bindings, Set<String> supportIds) {
        }
    }

    static class RewriteRuleReasonerPlugin extends BaseReasonerPlugin {
        private static Rule renameRuleVariables(Rule rule, int depth) {
            var suffix = "_d" + depth + "_" + Cog.id.incrementAndGet();
            Map<Term.Var, Term> renameMap = rule.form().vars().stream().collect(Collectors.toMap(Function.identity(), v -> Term.Var.of(v.name() + suffix)));
            var renamedForm = (Term.Lst) Unifier.subst(rule.form(), renameMap);
            try {
                return Rule.parseRule(rule.id() + suffix, renamedForm, rule.pri(), rule.sourceNoteId());
            } catch (IllegalArgumentException e) {
                error("Error renaming rule variables: " + e.getMessage());
                return rule;
            }
        }

        @Override
        public Set<Cog.Feature> getSupportedFeatures() {
            return Set.of(Cog.Feature.REWRITE_RULES);
        }

        @Override
        public void initialize(Reasoning ctx) {
            super.initialize(ctx);
            ctx.events().on(Cog.AssertedEvent.class, this::handleAssertionAdded);
        }

        private void handleAssertionAdded(Cog.AssertedEvent event) {
            var newA = event.assertion();
            var kbId = event.kbId();
            if (!isActiveContext(kbId) && !isActiveContext(newA.sourceNoteId())) return;

            if (!newA.isActive() || (newA.type() != AssertionType.GROUND && newA.type() != AssertionType.SKOLEMIZED))
                return;

            var kb = getKb(kbId);
            var globalKb = context.getKb(Cog.GLOBAL_KB_NOTE_ID);
            var relevantKbs = Stream.of(kb, globalKb).filter(Objects::nonNull).distinct();
            var allActiveAssertions = relevantKbs.flatMap(k -> k.getAllAssertions().stream())
                    .filter(Assertion::isActive)
                    .filter(a -> isActiveContext(a.kb()) || isActiveContext(a.sourceNoteId()))
                    .distinct().toList();

            if (newA.isEquality() && newA.isOrientedEquality() && !newA.negated() && newA.kif().size() == 3) {
                var lhs = newA.kif().get(1);
                allActiveAssertions.stream()
                        .filter(t -> !t.id().equals(newA.id()))
                        .filter(t -> Unifier.match(lhs, t.getEffectiveTerm(), Map.of()) != null)
                        .forEach(t -> applyRewrite(newA, t));
            }

            allActiveAssertions.stream()
                    .filter(r -> r.isEquality() && r.isOrientedEquality() && !r.negated() && r.kif().size() == 3)
                    .filter(r -> isActiveContext(r.sourceNoteId()))
                    .filter(r -> !r.id().equals(newA.id()))
                    .filter(r -> Unifier.match(r.kif().get(1), newA.getEffectiveTerm(), Map.of()) != null)
                    .forEach(r -> applyRewrite(r, newA));
        }

        private void applyRewrite(Assertion rule, Assertion target) {
            if (!isActiveContext(rule.sourceNoteId()) || (!isActiveContext(target.kb()) && !isActiveContext(target.sourceNoteId()))) {
                return;
            }

            var rk = rule.kif();
            var lhs = rk.get(1);
            var rhs = rk.get(2);

            var ruleID = rule.id();
            var support = Stream.concat(target.justificationIds().stream(), Stream.of(target.id(), ruleID)).collect(Collectors.toSet());
            var depth = Math.max(target.derivationDepth(), rule.derivationDepth()) + 1;
            var derivationDepthMax = getDerivationDepthMax();
            if (depth > derivationDepthMax)
                return;

            var ctx = getCogNoteContext();
            var targetNoteId = ctx.commonSourceNodeId(support);
            var pri = ctx.calculateDerivedPri(support, (rule.pri() + target.pri()) / 2.0);

            Unifier.rewrite(target.kif(), lhs, rhs)
                    .filter(rw -> rw instanceof Term.Lst && !rw.equals(target.kif()))
                    .map(Term.Lst.class::cast)
                    .filter(Predicate.not(Logic::isTrivial))
                    .ifPresent(l -> {
                        if (l.weight() > MAX_DERIVED_TERM_WEIGHT)
                            return;

                        var o = l.op();
                        var isNeg = o.filter(KIF_OP_NOT::equals).isPresent();
                        var isEq = !isNeg && o.filter(KIF_OP_EQUAL::equals).isPresent();
                        var isOriented = isEq && l.size() == 3 && l.get(1).weight() > l.get(2).weight();
                        var type = l.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;

                        tryCommit(new Assertion.PotentialAssertion(l, pri, support, ruleID,
                                        isEq, isNeg, isOriented, targetNoteId, type, List.of(), depth),
                                ruleID);
                    });
        }
    }

    static class UniversalInstantiationReasonerPlugin extends BaseReasonerPlugin {
        private static Stream<Map<Term.Var, Term>> findSubExpressionMatches(Term expr, Term target) {
            return Stream.concat(
                    ofNullable(Unifier.match(expr, target, Map.of())).stream(),
                    (expr instanceof Term.Lst l) ? l.terms.stream().flatMap(sub -> findSubExpressionMatches(sub, target)) : Stream.empty()
            );
        }

        @Override
        public void initialize(Reasoning ctx) {
            super.initialize(ctx);
            ctx.events().on(Cog.AssertedEvent.class, this::handleAssertionAdded);
        }

        @Override
        public Set<Cog.Feature> getSupportedFeatures() {
            return Set.of(Cog.Feature.UNIVERSAL_INSTANTIATION);
        }

        private void handleAssertionAdded(Cog.AssertedEvent event) {
            var newA = event.assertion();
            var kbId = event.kbId();
            if (!isActiveContext(kbId) && !isActiveContext(newA.sourceNoteId())) return;

            if (!newA.isActive()) return;

            var kb = getKb(kbId);
            var globalKb = context.getKb(Cog.GLOBAL_KB_NOTE_ID);
            var relevantKbs = Stream.of(kb, globalKb).filter(Objects::nonNull).distinct();
            var allActiveAssertions = relevantKbs.flatMap(k -> k.getAllAssertions().stream())
                    .filter(Assertion::isActive)
                    .filter(a -> isActiveContext(a.kb()) || isActiveContext(a.sourceNoteId()))
                    .distinct().toList();

            if ((newA.type() == AssertionType.GROUND || newA.type() == AssertionType.SKOLEMIZED) && newA.kif().get(0) instanceof Term.Atom pred) {
                allActiveAssertions.stream()
                        .filter(u -> u.type() == AssertionType.UNIVERSAL && u.derivationDepth() < getDerivationDepthMax())
                        .filter(u -> isActiveContext(u.sourceNoteId()))
                        .filter(u -> u.getReferencedPredicates().contains(pred))
                        .forEach(u -> tryInstantiate(u, newA));
            } else if (newA.type() == AssertionType.UNIVERSAL && newA.derivationDepth() < getDerivationDepthMax()) {
                if (!isActiveContext(newA.sourceNoteId())) return;

                ofNullable(newA.getEffectiveTerm()).filter(Term.Lst.class::isInstance).map(Term.Lst.class::cast)
                        .flatMap(Term.Lst::op).map(Term.Atom::of)
                        .ifPresent(pred -> allActiveAssertions.stream()
                                .filter(g -> g.type() == AssertionType.GROUND || g.type() == AssertionType.SKOLEMIZED)
                                .filter(g -> isActiveContext(g.kb()) || isActiveContext(g.sourceNoteId()))
                                .filter(g -> g.getReferencedPredicates().contains(pred))
                                .forEach(g -> tryInstantiate(newA, g)));
            }
        }

        private void tryInstantiate(Assertion uniA, Assertion groundA) {
            if (!isActiveContext(uniA.sourceNoteId()) || (!isActiveContext(groundA.kb()) && !isActiveContext(groundA.sourceNoteId())))
                return;
            var vars = uniA.quantifiedVars();
            if (vars.isEmpty())
                return;
            var formula = uniA.getEffectiveTerm();
            if (!(formula instanceof Term.Lst))
                return;

            findSubExpressionMatches(formula, groundA.kif())
                    .filter(bindings -> bindings.keySet().containsAll(vars))
                    .forEach(bindings -> {
                        var instFormula = Unifier.subst(formula, bindings);
                        if (instFormula instanceof Term.Lst instList && !instFormula.containsVar() && !isTrivial(instList)) {
                            var support = Stream.concat(Stream.of(groundA.id(), uniA.id()), Stream.concat(groundA.justificationIds().stream(), uniA.justificationIds().stream())).collect(Collectors.toSet());
                            var pri = getCogNoteContext().calculateDerivedPri(support, (groundA.pri() + uniA.pri()) / 2.0);
                            var depth = Math.max(groundA.derivationDepth(), uniA.derivationDepth()) + 1;

                            if (depth <= getDerivationDepthMax() && instList.weight() <= MAX_DERIVED_TERM_WEIGHT) {
                                var isNeg = instList.op().filter(KIF_OP_NOT::equals).isPresent();
                                var isEq = !isNeg && instList.op().filter(KIF_OP_EQUAL::equals).isPresent();
                                var isOriented = isEq && instList.size() == 3 && instList.get(1).weight() > instList.get(2).weight();
                                var type = instList.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
                                var targetNoteId = getCogNoteContext().commonSourceNodeId(support);
                                var pa = new Assertion.PotentialAssertion(instList, pri, support, uniA.id(), isEq, isNeg, isOriented, targetNoteId, type, List.of(), depth);
                                tryCommit(pa, uniA.id());
                            }
                        }
                    });
        }
    }

    static class BackwardChainingReasonerPlugin extends BaseReasonerPlugin {
        private static Rule renameRuleVariables(Rule rule, int depth) {
            var suffix = "_d" + depth + "_" + Cog.id.incrementAndGet();
            var renamedForm = (Term.Lst) Unifier.subst(rule.form(),
                    rule.form().vars().stream().collect(
                            Collectors.toMap(Function.identity(), v -> Term.Var.of(v.name() + suffix))));
            try {
                return Rule.parseRule(rule.id() + suffix, renamedForm, rule.pri(), rule.sourceNoteId());
            } catch (IllegalArgumentException e) {
                error("Error renaming rule variables: " + e.getMessage());
                return rule;
            }
        }

        @Override
        public Set<Cog.Feature> getSupportedFeatures() {
            return Set.of(Cog.Feature.BACKWARD_CHAINING, Cog.Feature.OPERATOR_SUPPORT);
        }

        @Override
        public Set<QueryType> getSupportedQueryTypes() {
            return Set.of(QueryType.ASK_BINDINGS, QueryType.ASK_TRUE_FALSE, QueryType.ACHIEVE_GOAL);
        }

        @Override
        public CompletableFuture<Answer> executeQuery(Query query) {
            if (isActiveContext(query.targetKbId())) {
                return CompletableFuture.supplyAsync(() -> {
                    var results = new ArrayList<Map<Term.Var, Term>>();
                    var maxDepth = (Integer) query.parameters().getOrDefault("maxDepth", MAX_BACKWARD_CHAIN_DEPTH);
                    try {
                        // Use a separate proof stack for each top-level query execution
                        prove(query.pattern(), query.targetKbId(), Map.of(), maxDepth, new HashSet<>()).forEach(results::add);

                        var status = results.isEmpty() ? QueryStatus.FAILURE : QueryStatus.SUCCESS;

                        return new Answer(query.id(), status, results, null);

                    } catch (Exception e) {
                        error("Backward chaining query failed: " + e.getMessage());
                        e.printStackTrace();
                        return Answer.error(query.id(), e.getMessage());
                    }
                }, context.events().exe); // Use events executor for query execution
            } else {
                message("Query skipped: Target KB '" + query.targetKbId() + "' is not active.");
                return CompletableFuture.completedFuture(Answer.failure(query.id()));
            }
        }

        private Stream<Map<Term.Var, Term>> prove(Term goal, @Nullable String kbId, Map<Term.Var, Term> bindings, int depth, Set<Term> proofStack) {
            if (depth <= 0) return Stream.empty();

            var currentGoal = Unifier.substFully(goal, bindings);

            // Occurs check for recursion depth
            if (!proofStack.add(currentGoal)) {
                // message("BC: Cycle detected for goal: " + currentGoal.toKif()); // Optional: log cycles
                return Stream.empty();
            }

            Stream<Map<Term.Var, Term>> resultStream = Stream.empty();

            if (currentGoal instanceof Term.Lst goalList) {
                var opOpt = goalList.op();
                if (opOpt.isPresent()) {
                    var op = opOpt.get();
                    switch (op) {
                        case KIF_OP_AND -> {
                            if (goalList.size() > 1) {
                                resultStream = proveAntecedents(goalList.terms.stream().skip(1).toList(), kbId, bindings, depth, proofStack);
                            } else {
                                resultStream = Stream.of(bindings); // (and) is true
                            }
                        }
                        case KIF_OP_OR -> {
                            if (goalList.size() > 1) {
                                // For OR, we need to explore each disjunct independently.
                                // Each disjunct gets its own copy of the proof stack to avoid cycles within the disjunct,
                                // but cycles across disjuncts are allowed (though might not terminate if not careful).
                                resultStream = goalList.terms.stream().skip(1)
                                        .flatMap(orClause -> prove(orClause, kbId, bindings, depth, new HashSet<>(proofStack)));
                            } else {
                                resultStream = Stream.empty(); // (or) is false
                            }
                        }
                        case KIF_OP_NOT -> {
                            if (goalList.size() == 2) {
                                var negatedGoal = goalList.get(1);
                                // To prove NOT G, try to prove G. If G fails (no bindings found), then NOT G succeeds.
                                // This is negation as failure.
                                var negatedResults = prove(negatedGoal, kbId, bindings, depth, new HashSet<>(proofStack)).toList();
                                if (negatedResults.isEmpty()) {
                                    resultStream = Stream.of(bindings); // NOT G succeeds with the current bindings
                                } else {
                                    resultStream = Stream.empty(); // NOT G fails if G succeeds
                                }
                            } else {
                                error("Warning: Invalid 'not' format in query: " + goalList.toKif());
                                resultStream = Stream.empty();
                            }
                        }
                        default -> {
                            // Try executing as an operator first
                            resultStream = context.operators().get(Term.Atom.of(op))
                                    .flatMap(opInstance -> {
                                        try {
                                            // Execute operator asynchronously and wait for result
                                            var opResultFuture = opInstance.exe(goalList, context);
                                            var opResult = opResultFuture.join(); // Blocking wait for operator result

                                            if (opResult == null) return Optional.empty();

                                            // If operator returns a boolean atom ("true" or "false")
                                            if (opResult instanceof Term.Atom value) {
                                                if ("true".equals(value.value())) {
                                                    return Optional.of(Stream.of(bindings)); // Operator evaluated to true, goal is proven
                                                } else if ("false".equals(value.value())) {
                                                    return Optional.of(Stream.empty()); // Operator evaluated to false, goal fails
                                                }
                                            }

                                            // If operator returns a term, try to unify it with the goal
                                            return Optional.of(ofNullable(Unifier.unify(currentGoal, opResult, bindings)).stream());

                                        } catch (CompletionException | CancellationException e) {
                                            error("Operator execution exception for " + opInstance.pred().toKif() + ": " + e.getMessage());
                                            return Optional.of(Stream.empty()); // Operator error causes goal failure
                                        } catch (Exception e) {
                                            error("Unexpected error during operator execution for " + opInstance.pred().toKif() + ": " + e.getMessage());
                                            e.printStackTrace();
                                            return Optional.of(Stream.empty()); // Unexpected error causes goal failure
                                        }
                                    })
                                    .orElse(Stream.empty()); // If operator not found or execution fails, stream is empty
                        }
                    }
                } else {
                    // List without operator - treat as a predicate/fact lookup
                    // Fall through to fact/rule lookup below
                }
            }

            // If operator execution didn't yield results, try facts and rules
            if (!resultStream.findAny().isPresent()) {
                resultStream = Stream.empty(); // Reset stream

                // 1. Try matching against existing facts in the current KB and global KB
                Stream<Assertion>[] factStream = new Stream[]{getKb(kbId).findUnifiableAssertions(currentGoal)};

                // Also check active notes' KBs
                context.getActiveNoteIds().stream()
                        .filter(id -> !id.equals(kbId) && !id.equals(Cog.GLOBAL_KB_NOTE_ID))
                        .map(this::getKb)
                        .forEach(kb -> factStream[0] = Stream.concat(factStream[0], kb.findUnifiableAssertions(currentGoal)));

                // Ensure global KB is checked if not the current KB
                if (kbId == null || !kbId.equals(Cog.GLOBAL_KB_NOTE_ID)) {
                    factStream[0] = Stream.concat(factStream[0], context.getKb(Cog.GLOBAL_KB_NOTE_ID).findUnifiableAssertions(currentGoal));
                }

                var factBindingsStream = factStream[0]
                        .distinct() // Avoid processing the same assertion multiple times
                        .filter(Assertion::isActive)
                        .filter(a -> isActiveContext(a.kb()) || isActiveContext(a.sourceNoteId())) // Only use facts from active contexts
                        .flatMap(fact -> ofNullable(Unifier.unify(currentGoal, fact.kif(), bindings)).stream()); // Unify goal with fact

                // 2. Try backward chaining using rules
                var ruleStream = context.rules().stream()
                        .filter(rule -> isActiveContext(rule.sourceNoteId())) // Only use rules from active contexts
                        .flatMap(rule -> {
                            // Rename variables in the rule to avoid conflicts with query bindings
                            var renamedRule = renameRuleVariables(rule, depth);
                            // Try to unify the rule's consequent with the current goal
                            return ofNullable(Unifier.unify(renamedRule.consequent(), currentGoal, bindings))
                                    .map(consequentBindings -> {
                                        // If consequent unifies, try to prove the rule's antecedents recursively
                                        // Pass the new bindings and reduced depth
                                        return proveAntecedents(renamedRule.antecedents(), kbId, consequentBindings, depth - 1, new HashSet<>(proofStack));
                                    })
                                    .orElse(Stream.empty()); // If consequent doesn't unify, this rule doesn't apply
                        });

                // Combine results from facts and rules
                resultStream = Stream.concat(factBindingsStream, ruleStream);
            }


            proofStack.remove(currentGoal); // Remove goal from stack when done exploring its possibilities
            return resultStream.distinct(); // Ensure unique bindings are returned
        }

        private Stream<Map<Term.Var, Term>> proveAntecedents(List<Term> antecedents, @Nullable String kbId, Map<Term.Var, Term> bindings, int depth, Set<Term> proofStack) {
            var n = antecedents.size();
            if (n == 0) return Stream.of(bindings); // Empty antecedent list is true
            else {
                var firstAntecedent = antecedents.getFirst();
                var rest = antecedents.subList(1, n);

                // Recursively prove the first antecedent. For each successful binding,
                // try to prove the rest of the antecedents with the updated bindings.
                return prove(firstAntecedent, kbId, bindings, depth, proofStack)
                        .flatMap(newBindings -> proveAntecedents(rest, kbId, newBindings, depth, proofStack));
            }
        }
    }
}
