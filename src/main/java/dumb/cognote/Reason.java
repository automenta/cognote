package dumb.cognote;

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
import static dumb.cognote.Logic.*;
import static java.util.Optional.ofNullable;

public class Reason {
    private static final int MAX_BACKWARD_CHAIN_DEPTH = 8;
    private static final int MAX_DERIVED_TERM_WEIGHT = 150;

    public record Reasoning(Logic.Cognition cognition, Events events) {
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

        ReasonerManager(Events events, Logic.Cognition ctx) {
            this.events = events;
            this.reasoning = new Reasoning(ctx, events);
        }

        public void loadPlugin(Plugin.ReasonerPlugin plugin) {
            if (initialized.get()) {
                System.err.println("Cannot load reasoner plugin " + plugin.id() + " after initialization.");
                return;
            }
            plugins.add(plugin);
            System.out.println("Reasoner plugin loaded: " + plugin.id());
        }

        public void initializeAll() {
            if (!initialized.compareAndSet(false, true)) return;
            System.out.println("Initializing " + plugins.size() + " reasoner plugins...");
            plugins.forEach(plugin -> {
                try {
                    plugin.initialize(reasoning);
                    System.out.println("Initialized reasoner plugin: " + plugin.id());
                } catch (Exception e) {
                    System.err.println("Failed to initialize reasoner plugin " + plugin.id() + ": " + e.getMessage());
                    e.printStackTrace();
                    plugins.remove(plugin);
                }
            });

            events.on(Cog.Query.QueryEvent.class, this::handleQueryRequest);
            System.out.println("Reasoner plugin initialization complete.");
        }

        private void handleQueryRequest(Cog.Query.QueryEvent event) {
            var query = event.query();
            var futures = plugins.stream()
                    .filter(p -> p.getSupportedQueryTypes().contains(query.type()))
                    .map(p -> p.executeQuery(query))
                    .toList();

            if (futures.isEmpty()) {
                // If no plugin supports the query type, return failure
                events.emit(new Cog.Answer.AnswerEvent(Cog.Answer.failure(query.id())));
                return;
            }

            // For ASK queries, combine results. For ACHIEVE_GOAL, the first success is enough (or combine results if multiple paths).
            // For now, let's just take the first successful result for ACHIEVE_GOAL or combine for ASK.
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .thenApplyAsync(v -> {
                        List<Map<Term.Var, Term>> allBindings = new ArrayList<>();
                        var overallStatus = Cog.QueryStatus.FAILURE;
                        Explanation combinedExplanation = null;

                        for (var future : futures) {
                            try {
                                var result = future.join();
                                var e = result.explanation();
                                var s = result.status();

                                if (s == Cog.QueryStatus.SUCCESS) {
                                    // For ASK queries, collect all bindings
                                    if (query.type() == Cog.QueryType.ASK_BINDINGS || query.type() == Cog.QueryType.ASK_TRUE_FALSE) {
                                        allBindings.addAll(result.bindings());
                                    }
                                    // For ACHIEVE_GOAL, success means the goal is provable
                                    // We might want to collect proof paths or actions here in the future
                                    overallStatus = Cog.QueryStatus.SUCCESS;
                                    if (e != null) combinedExplanation = e;
                                    // If ACHIEVE_GOAL is successful, we might stop processing other plugins
                                    if (query.type() == Cog.QueryType.ACHIEVE_GOAL) break;

                                } else if (s != Cog.QueryStatus.FAILURE && overallStatus == Cog.QueryStatus.FAILURE) {
                                    // If the first successful plugin sets a status other than SUCCESS/FAILURE, use that
                                    overallStatus = s;
                                    if (e != null) combinedExplanation = e;
                                }
                            } catch (CompletionException | CancellationException e) {
                                System.err.println("Query execution error for " + query.id() + ": " + e.getMessage());
                                if (overallStatus != Cog.QueryStatus.ERROR) {
                                    overallStatus = Cog.QueryStatus.ERROR;
                                    combinedExplanation = new Explanation(e.getMessage());
                                }
                            }
                        }

                        // For ASK_TRUE_FALSE, success means at least one binding was found
                        if (query.type() == Cog.QueryType.ASK_TRUE_FALSE) {
                            overallStatus = allBindings.isEmpty() ? Cog.QueryStatus.FAILURE : Cog.QueryStatus.SUCCESS;
                        }


                        return new Cog.Answer(query.id(), overallStatus, allBindings, combinedExplanation);
                    }, reasoning.events.exe)
                    .thenAccept(result -> events.emit(new Cog.Answer.AnswerEvent(result)));
        }

        public void shutdownAll() {
            System.out.println("Shutting down " + plugins.size() + " reasoner plugins...");
            plugins.forEach(plugin -> {
                try {
                    plugin.stop();
                    System.out.println("Shutdown reasoner plugin: " + plugin.id());
                } catch (Exception e) {
                    System.err.println("Error shutting down plugin " + plugin.id() + ": " + e.getMessage());
                    e.printStackTrace();
                }
            });
            plugins.clear();
            System.out.println("Reasoner plugin shutdown complete.");
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
        public CompletableFuture<Cog.Answer> executeQuery(Cog.Query query) {
            return CompletableFuture.completedFuture(Cog.Answer.failure(query.id()));
        }

        @Override
        public Set<Cog.QueryType> getSupportedQueryTypes() {
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
            // Only process assertions added to an active context (active note or global KB)
            if (!isActiveContext(sourceKbId) && !isActiveContext(newAssertion.sourceNoteId())) return;

            if (!newAssertion.isActive() || (newAssertion.type() != Logic.AssertionType.GROUND && newAssertion.type() != Logic.AssertionType.SKOLEMIZED))
                return;

            context.rules().stream()
                    // Only consider rules from active contexts (active notes or global KB)
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
                return Stream.empty(); // Prevent infinite recursion in matching
            if (remaining.isEmpty()) return Stream.of(new MatchResult(bindings, support));

            var clause = Logic.Unifier.substFully(remaining.getFirst(), bindings);
            var nextRemaining = remaining.subList(1, remaining.size());
            var neg = (clause instanceof Term.Lst l && l.op().filter(KIF_OP_NOT::equals).isPresent());
            var pattern = neg ? ((Term.Lst) clause).get(1) : clause;
            if (!(pattern instanceof Term.Lst)) return Stream.empty();

            // Prioritize searching for matching assertions: Current KB -> Other Active Note KBs -> Global KB
            var currentKb = getKb(currentKbId);
            var globalKb = context.getKb(Cog.GLOBAL_KB_NOTE_ID);


            // 1. Search current KB
            final Stream<Assertion>[] assertionStream = new Stream[]{currentKb.findUnifiableAssertions(pattern)};

            // 2. Search other active note KBs (excluding current and global)
            context.getActiveNoteIds().stream()
                    .filter(id -> !id.equals(currentKbId) && !id.equals(Cog.GLOBAL_KB_NOTE_ID))
                    .map(this::getKb)
                    .forEach(kb -> assertionStream[0] = Stream.concat(assertionStream[0], kb.findUnifiableAssertions(pattern)));

            // 3. Search global KB (if not already the current KB)
            if (!currentKbId.equals(Cog.GLOBAL_KB_NOTE_ID)) {
                assertionStream[0] = Stream.concat(assertionStream[0], globalKb.findUnifiableAssertions(pattern));
            }

            return assertionStream[0]
                    .distinct()
                    .filter(Assertion::isActive)
                    // Only use assertions from active contexts (active notes or global KB)
                    .filter(a -> isActiveContext(a.kb()) || isActiveContext(a.sourceNoteId()))
                    .filter(c -> c.negated() == neg)
                    .flatMap(c -> ofNullable(Logic.Unifier.unify(pattern, c.getEffectiveTerm(), bindings))
                            .map(newB -> findMatchesRecursive(rule, nextRemaining, newB,
                                    Stream.concat(support.stream(), Stream.of(c.id())).collect(Collectors.toSet()), c.kb()))
                            .orElse(Stream.empty()));
        }

        private void processDerivedAssertion(Rule rule, MatchResult result) {
            var consequent = Logic.Unifier.subst(rule.consequent(), result.bindings());
            if (consequent == null) return;

            var simplified = (consequent instanceof Term.Lst kl) ? Cognition.simplifyLogicalTerm(kl) : consequent;
            var targetNoteId = getCogNoteContext().commonSourceNodeId(result.supportIds());

            // Only derive assertions if the common source note is active or if the rule is global
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
                        System.err.println("Warning: Rule " + rule.id() + " derived non-list/non-var consequent: " + term.toKif());
                default -> {
                }
            }
        }

        private void processDerivedConjunction(Rule rule, Term.Lst conj, MatchResult result, @Nullable String targetNoteId) {
            conj.terms.stream().skip(1).forEach(term -> {
                var simp = (term instanceof Term.Lst kl) ? Cognition.simplifyLogicalTerm(kl) : term;
                if (simp instanceof Term.Lst c)
                    processDerivedAssertion(new Rule(rule.id(), rule.form(), rule.antecedent(), c, rule.pri(), rule.antecedents(), rule.sourceNoteId()), result); // Pass sourceNoteId
                else if (!(simp instanceof Term.Var))
                    System.err.println("Warning: Rule " + rule.id() + " derived (and ...) with non-list/non-var conjunct: " + term.toKif());
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
                    // Derived rules inherit the source note context if available
                    var derivedRule = Rule.parseRule(Cog.id(Cog.ID_PREFIX_RULE + "derived_"), body, pri, targetNoteId);
                    getCogNoteContext().addRule(derivedRule);
                    if (KIF_OP_EQUIV.equals(body.op().orElse(null))) {
                        var revList = new Term.Lst(new Term.Atom(KIF_OP_IMPLIES), body.get(2), body.get(1));
                        var revRule = Rule.parseRule(Cog.id(Cog.ID_PREFIX_RULE + "derived_"), revList, pri, targetNoteId); // Pass sourceNoteId
                        getCogNoteContext().addRule(revRule);
                    }
                } catch (IllegalArgumentException e) {
                    System.err.println("Invalid derived rule format ignored: " + body.toKif() + " from rule " + rule.id() + " | Error: " + e.getMessage());
                }
            } else {
                tryCommit(new Assertion.PotentialAssertion(forall, getCogNoteContext().calculateDerivedPri(result.supportIds(), rule.pri()), result.supportIds(), rule.id(), false, false, false, targetNoteId, AssertionType.UNIVERSAL, List.copyOf(vars), depth), rule.id());
            }
        }

        private void processDerivedExists(Rule rule, Term.Lst exists, MatchResult result, @Nullable String targetNoteId) {
            if (exists.size() != 3 || !(exists.get(1) instanceof Term.Lst || exists.get(1) instanceof Term.Var) || !(exists.get(2) instanceof Term.Lst body)) {
                System.err.println("Rule " + rule.id() + " derived invalid 'exists' structure: " + exists.toKif());
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
                System.err.println("Rule " + rule.id() + " derived invalid 'not': " + derived.toKif());
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
                return Rule.parseRule(rule.id() + suffix, renamedForm, rule.pri(), rule.sourceNoteId()); // Pass sourceNoteId
            } catch (IllegalArgumentException e) {
                System.err.println("Error renaming rule variables: " + e.getMessage());
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
            // Only trigger rewrite if the added assertion is in an active context
            if (!isActiveContext(kbId) && !isActiveContext(newA.sourceNoteId())) return;

            if (!newA.isActive() || (newA.type() != AssertionType.GROUND && newA.type() != AssertionType.SKOLEMIZED))
                return;

            var kb = getKb(kbId);
            var globalKb = context.getKb(Cog.GLOBAL_KB_NOTE_ID);
            var relevantKbs = Stream.of(kb, globalKb).filter(Objects::nonNull).distinct();
            var allActiveAssertions = relevantKbs.flatMap(k -> k.getAllAssertions().stream())
                    .filter(Assertion::isActive)
                    // Only consider assertions from active contexts
                    .filter(a -> isActiveContext(a.kb()) || isActiveContext(a.sourceNoteId()))
                    .distinct().toList();

            // Case 1: New assertion is a rewrite rule (equality)
            if (newA.isEquality() && newA.isOrientedEquality() && !newA.negated() && newA.kif().size() == 3) {
                var lhs = newA.kif().get(1);
                allActiveAssertions.stream()
                        .filter(t -> !t.id().equals(newA.id()))
                        .filter(t -> Unifier.match(lhs, t.getEffectiveTerm(), Map.of()) != null)
                        .forEach(t -> applyRewrite(newA, t)); // Apply the new rule to existing facts
            }

            // Case 2: New assertion is a fact, apply existing rewrite rules to it
            allActiveAssertions.stream()
                    .filter(r -> r.isEquality() && r.isOrientedEquality() && !r.negated() && r.kif().size() == 3)
                    // Only consider rules from active contexts
                    .filter(r -> isActiveContext(r.sourceNoteId()))
                    .filter(r -> !r.id().equals(newA.id()))
                    .filter(r -> Unifier.match(r.kif().get(1), newA.getEffectiveTerm(), Map.of()) != null)
                    .forEach(r -> applyRewrite(r, newA)); // Apply existing rules to the new fact
        }

        private void applyRewrite(Assertion rule, Assertion target) {
            // Ensure both the rule and the target assertion are from active contexts
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
            // Only trigger instantiation if the added assertion is in an active context
            if (!isActiveContext(kbId) && !isActiveContext(newA.sourceNoteId())) return;

            if (!newA.isActive()) return;

            var kb = getKb(kbId);
            var globalKb = context.getKb(Cog.GLOBAL_KB_NOTE_ID);
            var relevantKbs = Stream.of(kb, globalKb).filter(Objects::nonNull).distinct();
            var allActiveAssertions = relevantKbs.flatMap(k -> k.getAllAssertions().stream())
                    .filter(Assertion::isActive)
                    // Only consider assertions from active contexts
                    .filter(a -> isActiveContext(a.kb()) || isActiveContext(a.sourceNoteId()))
                    .distinct().toList();

            // Case 1: New assertion is ground/skolemized, try instantiating universals with it
            if ((newA.type() == AssertionType.GROUND || newA.type() == AssertionType.SKOLEMIZED) && newA.kif().get(0) instanceof Term.Atom pred) {
                allActiveAssertions.stream()
                        .filter(u -> u.type() == AssertionType.UNIVERSAL && u.derivationDepth() < getDerivationDepthMax())
                        // Only consider universal rules from active contexts
                        .filter(u -> isActiveContext(u.sourceNoteId()))
                        .filter(u -> u.getReferencedPredicates().contains(pred))
                        .forEach(u -> tryInstantiate(u, newA));
            }
            // Case 2: New assertion is universal, try instantiating it with existing ground/skolemized facts
            else if (newA.type() == AssertionType.UNIVERSAL && newA.derivationDepth() < getDerivationDepthMax()) {
                // Only process if the new universal is from an active context
                if (!isActiveContext(newA.sourceNoteId())) return;

                ofNullable(newA.getEffectiveTerm()).filter(Term.Lst.class::isInstance).map(Term.Lst.class::cast)
                        .flatMap(Term.Lst::op).map(Term.Atom::of)
                        .ifPresent(pred -> allActiveAssertions.stream()
                                .filter(g -> g.type() == AssertionType.GROUND || g.type() == AssertionType.SKOLEMIZED)
                                // Only consider ground facts from active contexts
                                .filter(g -> isActiveContext(g.kb()) || isActiveContext(g.sourceNoteId()))
                                .filter(g -> g.getReferencedPredicates().contains(pred))
                                .forEach(g -> tryInstantiate(newA, g)));
            }
        }

        private void tryInstantiate(Assertion uniA, Assertion groundA) {
            // Ensure both the universal and the ground assertion are from active contexts
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
                return Rule.parseRule(rule.id() + suffix, renamedForm, rule.pri(), rule.sourceNoteId()); // Pass sourceNoteId
            } catch (IllegalArgumentException e) {
                System.err.println("Error renaming rule variables: " + e.getMessage());
                return rule;
            }
        }

        @Override
        public Set<Cog.Feature> getSupportedFeatures() {
            return Set.of(Cog.Feature.BACKWARD_CHAINING, Cog.Feature.OPERATOR_SUPPORT);
        }

        @Override
        public Set<Cog.QueryType> getSupportedQueryTypes() {
            return Set.of(Cog.QueryType.ASK_BINDINGS, Cog.QueryType.ASK_TRUE_FALSE, Cog.QueryType.ACHIEVE_GOAL);
        }

        @Override
        public CompletableFuture<Cog.Answer> executeQuery(Cog.Query query) {
            // Only execute query if the target KB is active or global
            if (isActiveContext(query.targetKbId())) {
                return CompletableFuture.supplyAsync(() -> {
                    var results = new ArrayList<Map<Term.Var, Term>>();
                    var maxDepth = (Integer) query.parameters().getOrDefault("maxDepth", MAX_BACKWARD_CHAIN_DEPTH);
                    try {
                        // Use a mutable set for the proof stack
                        prove(query.pattern(), query.targetKbId(), Map.of(), maxDepth, new HashSet<>()).forEach(results::add);

                        // For ASK_BINDINGS and ASK_TRUE_FALSE, results are the bindings found.
                        // For ACHIEVE_GOAL, success means the goal is provable (results is non-empty).
                        // We don't currently extract actions for ACHIEVE_GOAL.
                        var status = results.isEmpty() ? Cog.QueryStatus.FAILURE : Cog.QueryStatus.SUCCESS;

                        return new Cog.Answer(query.id(), status, results, null);

                    } catch (Exception e) {
                        System.err.println("Backward chaining query failed: " + e.getMessage());
                        e.printStackTrace();
                        return Cog.Answer.error(query.id(), e.getMessage());
                    }
                }, context.events().exe);
            } else {
                System.out.println("Query skipped: Target KB '" + query.targetKbId() + "' is not active.");
                return CompletableFuture.completedFuture(Cog.Answer.failure(query.id()));
            }
        }

        private Stream<Map<Term.Var, Term>> prove(Term goal, @Nullable String kbId, Map<Term.Var, Term> bindings, int depth, Set<Term> proofStack) {
            if (depth <= 0) return Stream.empty();

            var currentGoal = Unifier.substFully(goal, bindings);

            // Base Case 2: Loop detection
            if (!proofStack.add(currentGoal)) {
                // System.out.println("Loop detected for goal: " + currentGoal.toKif()); // Optional: log loop detection
                return Stream.empty();
            }

            Stream<Map<Term.Var, Term>> resultStream = Stream.empty();

            // Handle Logical Connectives (and, or, not)
            if (currentGoal instanceof Term.Lst goalList) {
                var opOpt = goalList.op();
                if (opOpt.isPresent()) {
                    var op = opOpt.get();
                    switch (op) {
                        case KIF_OP_AND -> {
                            // (and A B C) is proven by proving A, then B with bindings from A, then C with bindings from B, etc.
                            // This is exactly what proveAntecedents does.
                            if (goalList.size() > 1) {
                                resultStream = proveAntecedents(goalList.terms.stream().skip(1).toList(), kbId, bindings, depth, proofStack);
                            } else {
                                // (and) with no arguments is true
                                resultStream = Stream.of(bindings);
                            }
                        }
                        case KIF_OP_OR -> {
                            // (or A B) is proven if A succeeds OR B succeeds. Combine streams.
                            if (goalList.size() > 1) {
                                resultStream = goalList.terms.stream().skip(1)
                                        .flatMap(orClause -> prove(orClause, kbId, bindings, depth, new HashSet<>(proofStack))); // Start new proof stack branch for each OR
                            } else {
                                // (or) with no arguments is false
                                resultStream = Stream.empty();
                            }
                        }
                        case KIF_OP_NOT -> {
                            // (not A) succeeds if prove(A, ...) yields an empty stream.
                            if (goalList.size() == 2) {
                                var negatedGoal = goalList.get(1);
                                var negatedResults = prove(negatedGoal, kbId, bindings, depth, new HashSet<>(proofStack)).toList(); // Collect all results for the negated goal
                                if (negatedResults.isEmpty()) {
                                    resultStream = Stream.of(bindings); // Not succeeds if the goal cannot be proven
                                } else {
                                    resultStream = Stream.empty(); // Not fails if the goal can be proven
                                }
                            } else {
                                System.err.println("Warning: Invalid 'not' format in query: " + goalList.toKif());
                                resultStream = Stream.empty(); // Invalid format fails
                            }
                        }
                        default -> {
                            // Not a special connective, proceed with operator/fact/rule matching
                            // 1. Try operators
                            resultStream = context.operators().get(Term.Atom.of(op))
                                    .flatMap(opInstance -> executeOperator(opInstance, goalList, bindings, currentGoal))
                                    .stream();
                        }
                    }
                } else {
                    // List without an operator (shouldn't happen based on KIF spec, but handle defensively)
                    System.err.println("Warning: List term without operator in query: " + goalList.toKif());
                    resultStream = Stream.empty();
                }
            }


            // If not handled by connectives or operators, try facts and rules
            // Check if resultStream has any elements without consuming it fully
            var hasOperatorResult = resultStream.findAny().isPresent();
            if (!hasOperatorResult) {
                resultStream = Stream.empty(); // Reset stream if it was consumed by findAny()

                // 2a. Search target KB first
                Stream<Assertion>[] factStream = new Stream[]{getKb(kbId).findUnifiableAssertions(currentGoal)};

                // 2b. Search other active note KBs (excluding target and global)
                context.getActiveNoteIds().stream()
                        .filter(id -> !id.equals(kbId) && !id.equals(Cog.GLOBAL_KB_NOTE_ID))
                        .map(this::getKb)
                        .forEach(kb -> factStream[0] = Stream.concat(factStream[0], kb.findUnifiableAssertions(currentGoal)));

                // 2c. Search global KB (if not already the target KB)
                if (kbId == null || !kbId.equals(Cog.GLOBAL_KB_NOTE_ID)) {
                    factStream[0] = Stream.concat(factStream[0], context.getKb(Cog.GLOBAL_KB_NOTE_ID).findUnifiableAssertions(currentGoal));
                }

                var factBindingsStream = factStream[0]
                        .distinct()
                        .filter(Assertion::isActive)
                        // Only use assertions from active contexts
                        .filter(a -> isActiveContext(a.kb()) || isActiveContext(a.sourceNoteId()))
                        .flatMap(fact -> ofNullable(Unifier.unify(currentGoal, fact.kif(), bindings)).stream());

                // 3. Try backward chaining on active rules
                var ruleStream = context.rules().stream()
                        // Only consider rules from active contexts
                        .filter(rule -> isActiveContext(rule.sourceNoteId()))
                        .flatMap(rule -> {
                            var renamedRule = renameRuleVariables(rule, depth); // Rename variables for this rule application
                            return ofNullable(Unifier.unify(renamedRule.consequent(), currentGoal, bindings))
                                    .map(consequentBindings -> proveAntecedents(renamedRule.antecedents(), kbId, consequentBindings, depth - 1, new HashSet<>(proofStack))) // Pass a copy of proofStack
                                    .orElse(Stream.empty());
                        });
                resultStream = Stream.concat(Stream.concat(resultStream, factBindingsStream), ruleStream);
            }


            proofStack.remove(currentGoal); // Remove goal from stack before returning
            return resultStream.distinct(); // Ensure unique bindings are returned
        }

        private Optional<Map<Term.Var, Term>> executeOperator(Op.Operator op, Term.Lst goalList, Map<Term.Var, Term> bindings, Term currentGoal) {
            try {
                // Operators are synchronous for now in backward chaining
                var opResult = op.exe(goalList, context).join();

                if (opResult == null) return Optional.empty();

                // If the operator returns a boolean atom ("true" or "false")
                if (opResult instanceof Term.Atom(var value)) {
                    if ("true".equals(value)) {
                        return Optional.of(bindings); // Operator evaluated to true, goal is proven with current bindings
                    } else if ("false".equals(value)) {
                        return Optional.empty(); // Operator evaluated to false, goal is not proven
                    }
                }

                // If the operator returns a non-boolean term, try to unify it with the goal
                return ofNullable(Unifier.unify(currentGoal, opResult, bindings));

            } catch (Exception e) {
                System.err.println("Operator execution exception for " + op.pred().toKif() + ": " + e.getMessage());
                // e.printStackTrace(); // Optional: print stack trace for debugging
                return Optional.empty(); // Operator execution failed, goal is not proven via this operator
            }
        }

        private Stream<Map<Term.Var, Term>> proveAntecedents(List<Term> antecedents, @Nullable String kbId, Map<Term.Var, Term> bindings, int depth, Set<Term> proofStack) {
            var n = antecedents.size();
            if (n == 0) return Stream.of(bindings); // All antecedents proven, yield current bindings
            else {
                var rest = antecedents.subList(1, n);

                // Prove the first antecedent
                return prove(antecedents.getFirst(), kbId, bindings, depth, proofStack)
                        // For each successful binding set from the first antecedent, recursively prove the rest
                        .flatMap(newBindings -> proveAntecedents(rest, kbId, newBindings, depth, proofStack));
            }
        }
    }
}
