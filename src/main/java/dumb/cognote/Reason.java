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

    interface ReasonerPlugin extends Plugin {
        void initialize(ReasonerContext context);

        CompletableFuture<Cog.Answer> executeQuery(Cog.Query query);

        Set<Cog.QueryType> getSupportedQueryTypes();

        Set<Cog.Feature> getSupportedFeatures();

        @Override
        default void start(Events events, Cognition ctx) {
        }
    }

    public record ReasonerContext(Logic.Cognition cognition, Events events) {
        Logic.Knowledge getKb(@Nullable String noteId) {
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
    }

    static class ReasonerManager {
        private final Events events;
        private final ReasonerContext reasonerContext;
        private final List<ReasonerPlugin> plugins = new CopyOnWriteArrayList<>();
        private final AtomicBoolean initialized = new AtomicBoolean(false);

        ReasonerManager(Events events, Logic.Cognition ctx) {
            this.events = events;
            this.reasonerContext = new ReasonerContext(ctx, events);
        }

        public void loadPlugin(ReasonerPlugin plugin) {
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
                    plugin.initialize(reasonerContext);
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
                events.emit(new Cog.Answer.AnswerEvent(Cog.Answer.failure(query.id())));
                return;
            }

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
                                    overallStatus = Cog.QueryStatus.SUCCESS;
                                    allBindings.addAll(result.bindings());
                                    if (e != null)
                                        combinedExplanation = e;
                                } else if (s != Cog.QueryStatus.FAILURE && overallStatus == Cog.QueryStatus.FAILURE) {
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
                        return new Cog.Answer(query.id(), overallStatus, allBindings, combinedExplanation);
                    }, reasonerContext.events.exe)
                    .thenAccept(result -> events.emit(new Cog.Answer.AnswerEvent(result)));
        }

        public void shutdownAll() {
            System.out.println("Shutting down " + plugins.size() + " reasoner plugins...");
            plugins.forEach(plugin -> {
                try {
                    plugin.stop();
                    System.out.println("Shutdown reasoner plugin: " + plugin.id());
                } catch (Exception e) {
                    System.err.println("Error shutting down reasoner plugin " + plugin.id() + ": " + e.getMessage());
                    e.printStackTrace();
                }
            });
            plugins.clear();
            System.out.println("Reasoner plugin shutdown complete.");
        }

        private Truths getTMS() {
            return reasonerContext.getTMS();
        }
    }


    abstract static class BaseReasonerPlugin implements ReasonerPlugin {
        protected final String id = Cog.id(ID_PREFIX_PLUGIN + getClass().getSimpleName().replace("ReasonerPlugin", "").toLowerCase() + "_");
        protected ReasonerContext context;

        @Override
        public String id() {
            return id;
        }

        @Override
        public void initialize(ReasonerContext ctx) {
            this.context = ctx;
        }

        protected void publish(Cog.CogEvent event) {
            if (context != null && context.events() != null) context.events().emit(event);
        }

        protected Logic.Knowledge getKb(@Nullable String noteId) {
            return context.getKb(noteId);
        }

        protected Truths getTMS() {
            return context.getTMS();
        }

        protected Logic.Cognition getCogNoteContext() {
            return context.cognition();
        }

        protected int getMaxDerivationDepth() {
            return context.getConfig().reasoningDepthLimit();
        }

        @Nullable
        protected Assertion tryCommit(Assertion.PotentialAssertion pa, String source) {
            return getCogNoteContext().tryCommit(pa, source);
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
        public void initialize(ReasonerContext ctx) {
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
            if (!newAssertion.isActive() || (newAssertion.type() != Logic.AssertionType.GROUND && newAssertion.type() != Logic.AssertionType.SKOLEMIZED))
                return;

            context.rules().forEach(rule -> rule.antecedents().forEach(clause -> {
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
            if (remaining.isEmpty()) return Stream.of(new MatchResult(bindings, support));

            var clause = Logic.Unifier.substFully(remaining.getFirst(), bindings);
            var nextRemaining = remaining.subList(1, remaining.size());
            var neg = (clause instanceof Term.Lst l && l.op().filter(KIF_OP_NOT::equals).isPresent());
            var pattern = neg ? ((Term.Lst) clause).get(1) : clause;
            if (!(pattern instanceof Term.Lst)) return Stream.empty();

            var currentKb = getKb(currentKbId);
            var globalKb = context.getKb(Cog.GLOBAL_KB_NOTE_ID);
            return Stream.concat(currentKb.findUnifiableAssertions(pattern),
                            (!currentKb.id.equals(Cog.GLOBAL_KB_NOTE_ID)) ? globalKb.findUnifiableAssertions(pattern) : Stream.empty())
                    .distinct()
                    .filter(Assertion::isActive)
                    .filter(c -> c.negated() == neg)
                    .flatMap(c -> ofNullable(Logic.Unifier.unify(pattern, c.getEffectiveTerm(), bindings))
                            .map(newB -> findMatchesRecursive(rule, nextRemaining, newB,
                                    Stream.concat(support.stream(), Stream.of(c.id())).collect(Collectors.toSet()), c.kb()))
                            .orElse(Stream.empty()));
        }

        private void processDerivedAssertion(Rule rule, MatchResult result) {
            var consequent = Logic.Unifier.subst(rule.consequent(), result.bindings());
            if (consequent == null) return;

            Term simplified = (consequent instanceof Term.Lst kl) ? Cognition.simplifyLogicalTerm(kl) : consequent;
            var targetNoteId = getCogNoteContext().findCommonSourceNodeId(result.supportIds());

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
                Term simp = (term instanceof Term.Lst kl) ? Cognition.simplifyLogicalTerm(kl) : term;
                if (simp instanceof Term.Lst c)
                    processDerivedAssertion(new Rule(rule.id(), rule.form(), rule.antecedent(), c, rule.pri(), rule.antecedents()), result);
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
            if (depth > getMaxDerivationDepth()) return;

            if (body.op().filter(op -> op.equals(KIF_OP_IMPLIES) || op.equals(KIF_OP_EQUIV)).isPresent()) {
                try {
                    var pri = getCogNoteContext().calculateDerivedPri(result.supportIds(), rule.pri());
                    var derivedRule = Rule.parseRule(Cog.id(Cog.ID_PREFIX_RULE + "derived_"), body, pri);
                    getCogNoteContext().addRule(derivedRule);
                    if (KIF_OP_EQUIV.equals(body.op().orElse(null))) {
                        var revList = new Term.Lst(new Term.Atom(KIF_OP_IMPLIES), body.get(2), body.get(1));
                        var revRule = Rule.parseRule(Cog.id(Cog.ID_PREFIX_RULE + "derived_"), revList, pri);
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
            if (depth > getMaxDerivationDepth()) return;

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
            if (depth > getMaxDerivationDepth() || derived.weight() > MAX_DERIVED_TERM_WEIGHT) return;

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
        @Override
        public void initialize(ReasonerContext ctx) {
            super.initialize(ctx);
            ctx.events().on(Cog.AssertedEvent.class, this::handleAssertionAdded);
        }

        @Override
        public Set<Cog.Feature> getSupportedFeatures() {
            return Set.of(Cog.Feature.REWRITE_RULES);
        }

        private void handleAssertionAdded(Cog.AssertedEvent event) {
            var newA = event.assertion();
            var kbId = event.kbId();
            if (!newA.isActive() || (newA.type() != AssertionType.GROUND && newA.type() != AssertionType.SKOLEMIZED))
                return;

            var kb = getKb(kbId);
            var globalKb = context.getKb(Cog.GLOBAL_KB_NOTE_ID);
            var relevantKbs = Stream.of(kb, globalKb).filter(Objects::nonNull).distinct();
            var allActiveAssertions = relevantKbs.flatMap(k -> k.getAllAssertions().stream()).filter(Assertion::isActive).distinct().toList();

            if (newA.isEquality() && newA.isOrientedEquality() && !newA.negated() && newA.kif().size() == 3) {
                var lhs = newA.kif().get(1);
                allActiveAssertions.stream()
                        .filter(t -> !t.id().equals(newA.id()))
                        .filter(t -> Unifier.match(lhs, t.getEffectiveTerm(), Map.of()) != null)
                        .forEach(t -> applyRewrite(newA, t));
            }

            allActiveAssertions.stream()
                    .filter(r -> r.isEquality() && r.isOrientedEquality() && !r.negated() && r.kif().size() == 3)
                    .filter(r -> !r.id().equals(newA.id()))
                    .filter(r -> Unifier.match(r.kif().get(1), newA.getEffectiveTerm(), Map.of()) != null)
                    .forEach(r -> applyRewrite(r, newA));
        }

        private void applyRewrite(Assertion ruleA, Assertion targetA) {
            var lhs = ruleA.kif().get(1);
            var rhs = ruleA.kif().get(2);

            Unifier.rewrite(targetA.kif(), lhs, rhs)
                    .filter(rw -> rw instanceof Term.Lst && !rw.equals(targetA.kif()))
                    .map(Term.Lst.class::cast)
                    .filter(Predicate.not(Logic::isTrivial))
                    .ifPresent(rwList -> {
                        var support = Stream.concat(targetA.justificationIds().stream(), Stream.of(targetA.id(), ruleA.id())).collect(Collectors.toSet());
                        var depth = Math.max(targetA.derivationDepth(), ruleA.derivationDepth()) + 1;
                        if (depth > getMaxDerivationDepth() || rwList.weight() > MAX_DERIVED_TERM_WEIGHT)
                            return;

                        var isNeg = rwList.op().filter(KIF_OP_NOT::equals).isPresent();
                        var isEq = !isNeg && rwList.op().filter(KIF_OP_EQUAL::equals).isPresent();
                        var isOriented = isEq && rwList.size() == 3 && rwList.get(1).weight() > rwList.get(2).weight();
                        var type = rwList.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
                        var targetNoteId = getCogNoteContext().findCommonSourceNodeId(support);
                        var pa = new Assertion.PotentialAssertion(rwList, getCogNoteContext().calculateDerivedPri(support, (ruleA.pri() + targetA.pri()) / 2.0), support, ruleA.id(), isEq, isNeg, isOriented, targetNoteId, type, List.of(), depth);
                        tryCommit(pa, ruleA.id());
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
        public void initialize(ReasonerContext ctx) {
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
            if (!newA.isActive()) return;

            var kb = getKb(kbId);
            var globalKb = context.getKb(Cog.GLOBAL_KB_NOTE_ID);
            var relevantKbs = Stream.of(kb, globalKb).filter(Objects::nonNull).distinct();
            var allActiveAssertions = relevantKbs.flatMap(k -> k.getAllAssertions().stream()).filter(Assertion::isActive).distinct().toList();

            if ((newA.type() == AssertionType.GROUND || newA.type() == AssertionType.SKOLEMIZED) && newA.kif().get(0) instanceof Term.Atom pred) {
                allActiveAssertions.stream()
                        .filter(u -> u.type() == AssertionType.UNIVERSAL && u.derivationDepth() < getMaxDerivationDepth())
                        .filter(u -> u.getReferencedPredicates().contains(pred))
                        .forEach(u -> tryInstantiate(u, newA));
            } else if (newA.type() == AssertionType.UNIVERSAL && newA.derivationDepth() < getMaxDerivationDepth()) {
                ofNullable(newA.getEffectiveTerm()).filter(Term.Lst.class::isInstance).map(Term.Lst.class::cast)
                        .flatMap(Term.Lst::op).map(Term.Atom::of)
                        .ifPresent(pred -> allActiveAssertions.stream()
                                .filter(g -> g.type() == AssertionType.GROUND || g.type() == AssertionType.SKOLEMIZED)
                                .filter(g -> g.getReferencedPredicates().contains(pred))
                                .forEach(g -> tryInstantiate(newA, g)));
            }
        }

        private void tryInstantiate(Assertion uniA, Assertion groundA) {
            var formula = uniA.getEffectiveTerm();
            var vars = uniA.quantifiedVars();
            if (vars.isEmpty() || !(formula instanceof Term.Lst))
                return;

            findSubExpressionMatches(formula, groundA.kif())
                    .filter(bindings -> bindings.keySet().containsAll(vars))
                    .forEach(bindings -> {
                        var instFormula = Unifier.subst(formula, bindings);
                        if (instFormula instanceof Term.Lst instList && !instFormula.containsVar() && !isTrivial(instList)) {
                            var support = Stream.concat(Stream.of(groundA.id(), uniA.id()), Stream.concat(groundA.justificationIds().stream(), uniA.justificationIds().stream())).collect(Collectors.toSet());
                            var pri = getCogNoteContext().calculateDerivedPri(support, (groundA.pri() + uniA.pri()) / 2.0);
                            var depth = Math.max(groundA.derivationDepth(), uniA.derivationDepth()) + 1;

                            if (depth <= getMaxDerivationDepth() && instList.weight() <= MAX_DERIVED_TERM_WEIGHT) {
                                var isNeg = instList.op().filter(KIF_OP_NOT::equals).isPresent();
                                var isEq = !isNeg && instList.op().filter(KIF_OP_EQUAL::equals).isPresent();
                                var isOriented = isEq && instList.size() == 3 && instList.get(1).weight() > instList.get(2).weight();
                                var type = instList.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
                                var targetNoteId = getCogNoteContext().findCommonSourceNodeId(support);
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
            Map<Term.Var, Term> renameMap = rule.form().vars().stream().collect(Collectors.toMap(Function.identity(), v -> Term.Var.of(v.name() + suffix)));
            var renamedForm = (Term.Lst) Unifier.subst(rule.form(), renameMap);
            try {
                return Rule.parseRule(rule.id() + suffix, renamedForm, rule.pri());
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
            return Set.of(Cog.QueryType.ASK_BINDINGS, Cog.QueryType.ASK_TRUE_FALSE);
        }

        @Override
        public CompletableFuture<Cog.Answer> executeQuery(Cog.Query query) {
            return CompletableFuture.supplyAsync(() -> {
                var results = new ArrayList<Map<Term.Var, Term>>();
                var maxDepth = (Integer) query.parameters().getOrDefault("maxDepth", MAX_BACKWARD_CHAIN_DEPTH);
                try {
                    prove(query.pattern(), query.targetKbId(), Map.of(), maxDepth, new HashSet<>()).forEach(results::add);
                    return Cog.Answer.success(query.id(), results);
                } catch (Exception e) {
                    System.err.println("Backward chaining query failed: " + e.getMessage());
                    e.printStackTrace();
                    return Cog.Answer.error(query.id(), e.getMessage());
                }
            }, context.events().exe);
        }

        private Stream<Map<Term.Var, Term>> prove(Term goal, @Nullable String kbId, Map<Term.Var, Term> bindings, int depth, Set<Term> proofStack) {
            if (depth <= 0) return Stream.empty();
            var currentGoal = Unifier.substFully(goal, bindings);
            if (!proofStack.add(currentGoal)) return Stream.empty();

            Stream<Map<Term.Var, Term>> resultStream = Stream.empty();

            if (currentGoal instanceof Term.Lst goalList && !goalList.terms.isEmpty() && goalList.get(0) instanceof Term.Atom opAtom) {
                resultStream = context.operators().get(opAtom)
                        .flatMap(op -> executeOperator(op, goalList, bindings, currentGoal))
                        .stream();
            }

            var kbStream = Stream.concat(getKb(kbId).findUnifiableAssertions(currentGoal),
                            (kbId != null && !kbId.equals(Cog.GLOBAL_KB_NOTE_ID)) ? context.getKb(Cog.GLOBAL_KB_NOTE_ID).findUnifiableAssertions(currentGoal) : Stream.empty())
                    .distinct()
                    .filter(Assertion::isActive)
                    .flatMap(fact -> ofNullable(Unifier.unify(currentGoal, fact.kif(), bindings)).stream());
            resultStream = Stream.concat(resultStream, kbStream);

            var ruleStream = context.rules().stream().flatMap(rule -> {
                var renamedRule = renameRuleVariables(rule, depth);
                return ofNullable(Unifier.unify(renamedRule.consequent(), currentGoal, bindings))
                        .map(consequentBindings -> proveAntecedents(renamedRule.antecedents(), kbId, consequentBindings, depth - 1, new HashSet<>(proofStack)))
                        .orElse(Stream.empty());
            });
            resultStream = Stream.concat(resultStream, ruleStream);

            proofStack.remove(currentGoal);
            return resultStream.distinct();
        }

        private Optional<Map<Term.Var, Term>> executeOperator(Op.Operator op, Term.Lst goalList, Map<Term.Var, Term> bindings, Term currentGoal) {
            try {
                return op.exe(goalList, context).handle((opResult, ex) -> {
                    if (ex != null) {
                        System.err.println("Operator execution failed for " + op.pred().toKif() + ": " + ex.getMessage());
                        return Optional.<Map<Term.Var, Term>>empty();
                    }
                    if (opResult == null) return Optional.<Map<Term.Var, Term>>empty();
                    if (opResult.equals(Term.Atom.of("true")))
                        return Optional.of(bindings);
                    return ofNullable(Unifier.unify(currentGoal, opResult, bindings));
                }).join();
            } catch (Exception e) {
                System.err.println("Operator execution exception for " + op.pred().toKif() + ": " + e.getMessage());
                return Optional.empty();
            }
        }

        private Stream<Map<Term.Var, Term>> proveAntecedents(List<Term> antecedents, @Nullable String kbId, Map<Term.Var, Term> bindings, int depth, Set<Term> proofStack) {
            if (antecedents.isEmpty()) return Stream.of(bindings);
            var first = antecedents.getFirst();
            var rest = antecedents.subList(1, antecedents.size());
            return prove(first, kbId, bindings, depth, proofStack)
                    .flatMap(newBindings -> proveAntecedents(rest, kbId, newBindings, depth, proofStack));
        }
    }
}
