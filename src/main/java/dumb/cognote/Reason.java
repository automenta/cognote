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

/** uses Logic to implement higher-level Reasoning functions */
public class Reason {
    private static final int MAX_BACKWARD_CHAIN_DEPTH = 8;
    private static final int MAX_DERIVED_TERM_WEIGHT = 150;

    interface ReasonerPlugin extends Cog.Plugin {
        void initialize(ReasonerContext context);

        default void processAssertionEvent(Cog.AssertionEvent event) {
        }

        default void processRuleEvent(Cog.RuleEvent event) {
        }

        CompletableFuture<Cog.Answer> executeQuery(Cog.Query query);

        Set<Cog.QueryType> getSupportedQueryTypes();

        Set<Cog.Feature> getSupportedFeatures();

        @Override
        default void start(Cog.Events events, Cognition ctx) {
        } // Not used directly for ReasonerPlugins
    }

    // --- Reasoner Related Records & Classes ---
    record ReasonerContext(Logic.Cognition cognition, Cog.Events events) {
        Logic.Knowledge getKb(@Nullable String noteId) {
            return cognition.kb(noteId);
        }

        Set<Logic.Rule> rules() {
            return cognition.rules();
        }

        Cog.Configuration getConfig() {
            return new Cog.Configuration(cognition.cog);
        }

        Logic.Truths getTMS() {
            return cognition.truth();
        }

        Logic.Operators operators() {
            return cognition.operators();
        }
    }

    static class ReasonerManager {
        private final Cog.Events events;
        private final ReasonerContext reasonerContext;
        private final List<ReasonerPlugin> plugins = new CopyOnWriteArrayList<>();
        private final AtomicBoolean initialized = new AtomicBoolean(false);

        ReasonerManager(Cog.Events events, Logic.Cognition ctx) {
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
                    plugins.remove(plugin); // Remove failed plugin
                }
            });

            events.on(Cog.AssertionAddedEvent.class, this::dispatchAssertionEvent);
            events.on(Cog.AssertionRetractedEvent.class, this::dispatchAssertionEvent);
            events.on(Cog.AssertionStatusChangedEvent.class, this::dispatchAssertionEvent);
            events.on(Cog.RuleAddedEvent.class, this::dispatchRuleEvent);
            events.on(Cog.RuleRemovedEvent.class, this::dispatchRuleEvent);
            events.on(Cog.QueryRequestEvent.class, this::handleQueryRequest);
            System.out.println("Reasoner plugin initialization complete.");
        }

        private void dispatchAssertionEvent(Cog.CogEvent event) {
            switch (event) {
                case Cog.AssertionAddedEvent aae ->
                        plugins.forEach(p -> p.processAssertionEvent(new Cog.AssertionEvent(aae.assertion(), aae.kbId())));
                case Cog.AssertionRetractedEvent are ->
                        plugins.forEach(p -> p.processAssertionEvent(new Cog.AssertionEvent(are.assertion(), are.kbId())));
                case Cog.AssertionStatusChangedEvent asce -> getTMS().getAssertion(asce.assertionId())
                        .ifPresent(a -> plugins.forEach(p -> p.processAssertionEvent(new Cog.AssertionEvent(a, asce.kbId()))));
                default -> {
                }
            }
        }

        private void dispatchRuleEvent(Cog.CogEvent event) {
            switch (event) {
                case Cog.RuleAddedEvent(var rule) -> plugins.forEach(p -> p.processRuleEvent(new Cog.RuleEvent(rule)));
                case Cog.RuleRemovedEvent(var rule) ->
                        plugins.forEach(p -> p.processRuleEvent(new Cog.RuleEvent(rule)));
                default -> {
                }
            }
        }

        private void handleQueryRequest(Cog.QueryRequestEvent event) {
            var query = event.query();
            var futures = plugins.stream()
                    .filter(p -> p.getSupportedQueryTypes().contains(query.type()))
                    .map(p -> p.executeQuery(query))
                    .toList();

            if (futures.isEmpty()) {
                events.emit(new Cog.QueryResultEvent(Cog.Answer.failure(query.id())));
                return;
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApplyAsync(v -> {
                        List<Map<KifVar, KifTerm>> allBindings = new ArrayList<>();
                        var overallStatus = Cog.QueryStatus.FAILURE;
                        Explanation combinedExplanation = null;
                        for (var future : futures) {
                            try {
                                var result = future.join(); // Already completed
                                var e = result.explanation();
                                var s = result.status();
                                if (s == Cog.QueryStatus.SUCCESS) {
                                    overallStatus = Cog.QueryStatus.SUCCESS;
                                    allBindings.addAll(result.bindings());
                                    if (e != null)
                                        combinedExplanation = e; // Take first explanation for now
                                } else if (s != Cog.QueryStatus.FAILURE && overallStatus == Cog.QueryStatus.FAILURE) {
                                    overallStatus = s; // Upgrade status from failure
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
                    }, reasonerContext.events.exe) // Use event executor for final aggregation
                    .thenAccept(result -> events.emit(new Cog.QueryResultEvent(result)));
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

        private Logic.Truths getTMS() {
            return reasonerContext.getTMS();
        }
    }


    abstract static class BaseReasonerPlugin implements ReasonerPlugin {
        protected final String id = Cog.generateId(ID_PREFIX_PLUGIN + getClass().getSimpleName().replace("ReasonerPlugin", "").toLowerCase() + "_");
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

        protected Logic.Truths getTMS() {
            return context.getTMS();
        }

        protected Logic.Cognition getCogNoteContext() {
            return context.cognition();
        }

        protected int getMaxDerivationDepth() {
            return context.getConfig().reasoningDepthLimit();
        }

        @Nullable
        protected Logic.Assertion tryCommit(Logic.PotentialAssertion pa, String source) {
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
            ctx.events().on(Cog.AssertionAddedEvent.class, this::handleAssertionAdded);
        }

        @Override
        public Set<Cog.Feature> getSupportedFeatures() {
            return Set.of(Cog.Feature.FORWARD_CHAINING);
        }

        private void handleAssertionAdded(Cog.AssertionAddedEvent event) {
            var newAssertion = event.assertion();
            var sourceKbId = event.kbId();
            if (!newAssertion.isActive() || (newAssertion.type() != Logic.AssertionType.GROUND && newAssertion.type() != Logic.AssertionType.SKOLEMIZED))
                return;

            context.rules().forEach(rule -> rule.antecedents().forEach(clause -> {
                boolean neg = (clause instanceof Logic.KifList l && l.op().filter(KIF_OP_NOT::equals).isPresent());
                if (neg == newAssertion.negated()) {
                    var pattern = neg ? ((Logic.KifList) clause).get(1) : clause;
                    ofNullable(Logic.Unifier.unify(pattern, newAssertion.getEffectiveTerm(), Map.of()))
                            .ifPresent(bindings -> findMatchesRecursive(rule, rule.antecedents(), bindings, Set.of(newAssertion.id()), sourceKbId)
                                    .forEach(match -> processDerivedAssertion(rule, match)));
                }
            }));
        }

        private Stream<MatchResult> findMatchesRecursive(Logic.Rule rule, List<Logic.KifTerm> remaining, Map<Logic.KifVar, Logic.KifTerm> bindings, Set<String> support, String currentKbId) {
            if (remaining.isEmpty()) return Stream.of(new MatchResult(bindings, support));

            var clause = Logic.Unifier.substFully(remaining.getFirst(), bindings);
            var nextRemaining = remaining.subList(1, remaining.size());
            boolean neg = (clause instanceof Logic.KifList l && l.op().filter(KIF_OP_NOT::equals).isPresent());
            var pattern = neg ? ((Logic.KifList) clause).get(1) : clause;
            if (!(pattern instanceof Logic.KifList)) return Stream.empty(); // Cannot match non-lists

            var currentKb = getKb(currentKbId);
            var globalKb = context.getKb(Cog.GLOBAL_KB_NOTE_ID);
            return Stream.concat(currentKb.findUnifiableAssertions(pattern),
                            (!currentKb.id.equals(Cog.GLOBAL_KB_NOTE_ID)) ? globalKb.findUnifiableAssertions(pattern) : Stream.empty())
                    .distinct()
                    .filter(Logic.Assertion::isActive) // Only match active assertions
                    .filter(c -> c.negated() == neg) // Match negation status
                    .flatMap(c -> ofNullable(Logic.Unifier.unify(pattern, c.getEffectiveTerm(), bindings))
                            .map(newB -> findMatchesRecursive(rule, nextRemaining, newB,
                                    Stream.concat(support.stream(), Stream.of(c.id())).collect(Collectors.toSet()), c.kb())) // Use KB ID of the matched fact
                            .orElse(Stream.empty()));
        }

        private void processDerivedAssertion(Logic.Rule rule, MatchResult result) {
            var consequent = Logic.Unifier.subst(rule.consequent(), result.bindings());
            if (consequent == null) return; // Substitution failed (shouldn't happen if antecedents matched)

            KifTerm simplified;
            if ((consequent instanceof KifList kl)) {
                getCogNoteContext();
                simplified = Cognition.simplifyLogicalTerm(kl);
            } else {
                simplified = consequent;
            }
            var targetNoteId = getCogNoteContext().findCommonSourceNodeId(result.supportIds());

            switch (simplified) {
                case Logic.KifList derived when derived.op().filter(KIF_OP_AND::equals).isPresent() ->
                        processDerivedConjunction(rule, derived, result, targetNoteId);
                case Logic.KifList derived when derived.op().filter(KIF_OP_FORALL::equals).isPresent() ->
                        processDerivedForall(rule, derived, result, targetNoteId);
                case KifList derived when derived.op().filter(KIF_OP_EXISTS::equals).isPresent() ->
                        processDerivedExists(rule, derived, result, targetNoteId);
                case KifList derived -> processDerivedStandard(rule, derived, result, targetNoteId);
                case KifTerm term when !(term instanceof KifVar) ->
                        System.err.println("Warning: Rule " + rule.id() + " derived non-list/non-var consequent: " + term.toKif());
                default -> {
                } // Ignore derived variables or empty results
            }
        }

        private void processDerivedConjunction(Rule rule, KifList conj, MatchResult result, @Nullable String targetNoteId) {
            conj.terms().stream().skip(1).forEach(term -> {
                KifTerm simp;
                if ((term instanceof KifList kl)) {
                    getCogNoteContext();
                    simp = Cognition.simplifyLogicalTerm(kl);
                } else {
                    simp = term;
                }
                if (simp instanceof KifList c) // Recurse for each conjunct
                    processDerivedAssertion(new Rule(rule.id(), rule.form(), rule.antecedent(), c, rule.pri(), rule.antecedents()), result);
                else if (!(simp instanceof KifVar))
                    System.err.println("Warning: Rule " + rule.id() + " derived (and ...) with non-list/non-var conjunct: " + term.toKif());
            });
        }

        private void processDerivedForall(Rule rule, KifList forall, MatchResult result, @Nullable String targetNoteId) {
            if (forall.size() != 3 || !(forall.get(1) instanceof KifList || forall.get(1) instanceof KifVar) || !(forall.get(2) instanceof KifList body))
                return;
            var vars = KifTerm.collectSpecVars(forall.get(1));
            if (vars.isEmpty()) {
                processDerivedStandard(rule, body, result, targetNoteId);
                return;
            } // Treat as standard if no vars

            var depth = getCogNoteContext().calculateDerivedDepth(result.supportIds()) + 1;
            if (depth > getMaxDerivationDepth()) return;

            if (body.op().filter(op -> op.equals(KIF_OP_IMPLIES) || op.equals(KIF_OP_EQUIV)).isPresent()) { // Derived rule
                try {
                    var pri = getCogNoteContext().calculateDerivedPri(result.supportIds(), rule.pri());
                    var derivedRule = Rule.parseRule(Cog.generateId(Cog.ID_PREFIX_RULE + "derived_"), body, pri);
                    getCogNoteContext().addRule(derivedRule);
                    if (KIF_OP_EQUIV.equals(body.op().orElse(null))) { // Add reverse for equivalence
                        var revList = new KifList(new KifAtom(KIF_OP_IMPLIES), body.get(2), body.get(1));
                        var revRule = Rule.parseRule(Cog.generateId(Cog.ID_PREFIX_RULE + "derived_"), revList, pri);
                        getCogNoteContext().addRule(revRule);
                    }
                } catch (IllegalArgumentException e) {
                    System.err.println("Invalid derived rule format ignored: " + body.toKif() + " from rule " + rule.id() + " | Error: " + e.getMessage());
                }
            } else { // Derived universal assertion
                tryCommit(new PotentialAssertion(forall, getCogNoteContext().calculateDerivedPri(result.supportIds(), rule.pri()), result.supportIds(), rule.id(), false, false, false, targetNoteId, AssertionType.UNIVERSAL, List.copyOf(vars), depth), rule.id());
            }
        }

        private void processDerivedExists(Rule rule, KifList exists, MatchResult result, @Nullable String targetNoteId) {
            if (exists.size() != 3 || !(exists.get(1) instanceof KifList || exists.get(1) instanceof KifVar) || !(exists.get(2) instanceof KifList body)) {
                System.err.println("Rule " + rule.id() + " derived invalid 'exists' structure: " + exists.toKif());
                return;
            }
            var vars = KifTerm.collectSpecVars(exists.get(1));
            if (vars.isEmpty()) {
                processDerivedStandard(rule, body, result, targetNoteId);
                return;
            } // Treat as standard if no vars

            var depth = getCogNoteContext().calculateDerivedDepth(result.supportIds()) + 1;
            if (depth > getMaxDerivationDepth()) return;

            var skolemBody = Cognition.performSkolemization(body, vars, result.bindings());
            var isNeg = skolemBody.op().filter(KIF_OP_NOT::equals).isPresent();
            var isEq = !isNeg && skolemBody.op().filter(KIF_OP_EQUAL::equals).isPresent();
            var isOriented = isEq && skolemBody.size() == 3 && skolemBody.get(1).weight() > skolemBody.get(2).weight();
            var pa = new PotentialAssertion(skolemBody, getCogNoteContext().calculateDerivedPri(result.supportIds(), rule.pri()), result.supportIds(), rule.id(), isEq, isNeg, isOriented, targetNoteId, AssertionType.SKOLEMIZED, List.of(), depth);
            tryCommit(pa, rule.id());
        }

        private void processDerivedStandard(Rule rule, KifList derived, MatchResult result, @Nullable String targetNoteId) {
            if (derived.containsVar() || isTrivial(derived)) return; // Ignore non-ground or trivial results

            var depth = getCogNoteContext().calculateDerivedDepth(result.supportIds()) + 1;
            if (depth > getMaxDerivationDepth() || derived.weight() > MAX_DERIVED_TERM_WEIGHT) return; // Check limits

            var isNeg = derived.op().filter(KIF_OP_NOT::equals).isPresent();
            if (isNeg && derived.size() != 2) {
                System.err.println("Rule " + rule.id() + " derived invalid 'not': " + derived.toKif());
                return;
            }
            var isEq = !isNeg && derived.op().filter(KIF_OP_EQUAL::equals).isPresent();
            var isOriented = isEq && derived.size() == 3 && derived.get(1).weight() > derived.get(2).weight();
            var type = derived.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
            var pa = new PotentialAssertion(derived, getCogNoteContext().calculateDerivedPri(result.supportIds(), rule.pri()), result.supportIds(), rule.id(), isEq, isNeg, isOriented, targetNoteId, type, List.of(), depth);
            tryCommit(pa, rule.id());
        }

        record MatchResult(Map<KifVar, KifTerm> bindings, Set<String> supportIds) {
        }
    }

    static class RewriteRuleReasonerPlugin extends BaseReasonerPlugin {
        @Override
        public void initialize(ReasonerContext ctx) {
            super.initialize(ctx);
            ctx.events().on(Cog.AssertionAddedEvent.class, this::handleAssertionAdded);
        }

        @Override
        public Set<Cog.Feature> getSupportedFeatures() {
            return Set.of(Cog.Feature.REWRITE_RULES);
        }

        private void handleAssertionAdded(Cog.AssertionAddedEvent event) {
            var newA = event.assertion();
            var kbId = event.kbId();
            if (!newA.isActive() || (newA.type() != AssertionType.GROUND && newA.type() != AssertionType.SKOLEMIZED))
                return;

            var kb = getKb(kbId);
            var globalKb = context.getKb(Cog.GLOBAL_KB_NOTE_ID);
            var relevantKbs = Stream.of(kb, globalKb).filter(Objects::nonNull).distinct();
            var allActiveAssertions = relevantKbs.flatMap(k -> k.getAllAssertions().stream()).filter(Assertion::isActive).distinct().toList();

            // If new assertion is a rewrite rule (= L R) where L > R
            if (newA.isEquality() && newA.isOrientedEquality() && !newA.negated() && newA.kif().size() == 3) {
                var lhs = newA.kif().get(1);
                allActiveAssertions.stream()
                        .filter(t -> !t.id().equals(newA.id())) // Don't rewrite self
                        .filter(t -> Unifier.match(lhs, t.getEffectiveTerm(), Map.of()) != null) // Check if LHS matches target term
                        .forEach(t -> applyRewrite(newA, t));
            }

            // If new assertion can be rewritten by existing rules
            allActiveAssertions.stream()
                    .filter(r -> r.isEquality() && r.isOrientedEquality() && !r.negated() && r.kif().size() == 3) // Find existing rewrite rules
                    .filter(r -> !r.id().equals(newA.id())) // Don't use self as rule
                    .filter(r -> Unifier.match(r.kif().get(1), newA.getEffectiveTerm(), Map.of()) != null) // Check if rule LHS matches new term
                    .forEach(r -> applyRewrite(r, newA));
        }

        private void applyRewrite(Assertion ruleA, Assertion targetA) {
            var lhs = ruleA.kif().get(1);
            var rhs = ruleA.kif().get(2);

            Unifier.rewrite(targetA.kif(), lhs, rhs)
                    .filter(rw -> rw instanceof KifList && !rw.equals(targetA.kif())) // Ensure rewrite happened and result is a list
                    .map(KifList.class::cast)
                    .filter(Predicate.not(Logic::isTrivial)) // Avoid trivial rewrites
                    .ifPresent(rwList -> {
                        var support = Stream.concat(targetA.justificationIds().stream(), Stream.of(targetA.id(), ruleA.id())).collect(Collectors.toSet());
                        var depth = Math.max(targetA.derivationDepth(), ruleA.derivationDepth()) + 1;
                        if (depth > getMaxDerivationDepth() || rwList.weight() > MAX_DERIVED_TERM_WEIGHT)
                            return; // Check limits

                        var isNeg = rwList.op().filter(KIF_OP_NOT::equals).isPresent();
                        var isEq = !isNeg && rwList.op().filter(KIF_OP_EQUAL::equals).isPresent();
                        var isOriented = isEq && rwList.size() == 3 && rwList.get(1).weight() > rwList.get(2).weight();
                        var type = rwList.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
                        var targetNoteId = getCogNoteContext().findCommonSourceNodeId(support);
                        var pa = new PotentialAssertion(rwList, getCogNoteContext().calculateDerivedPri(support, (ruleA.pri() + targetA.pri()) / 2.0), support, ruleA.id(), isEq, isNeg, isOriented, targetNoteId, type, List.of(), depth);
                        tryCommit(pa, ruleA.id());
                    });
        }
    }

    static class UniversalInstantiationReasonerPlugin extends BaseReasonerPlugin {
        @Override
        public void initialize(ReasonerContext ctx) {
            super.initialize(ctx);
            ctx.events().on(Cog.AssertionAddedEvent.class, this::handleAssertionAdded);
        }

        @Override
        public Set<Cog.Feature> getSupportedFeatures() {
            return Set.of(Cog.Feature.UNIVERSAL_INSTANTIATION);
        }

        private void handleAssertionAdded(Cog.AssertionAddedEvent event) {
            var newA = event.assertion();
            var kbId = event.kbId();
            if (!newA.isActive()) return;

            var kb = getKb(kbId);
            var globalKb = context.getKb(Cog.GLOBAL_KB_NOTE_ID);
            var relevantKbs = Stream.of(kb, globalKb).filter(Objects::nonNull).distinct();
            var allActiveAssertions = relevantKbs.flatMap(k -> k.getAllAssertions().stream()).filter(Assertion::isActive).distinct().toList();

            // Case 1: New assertion is ground/skolemized, try matching against existing universals
            if ((newA.type() == AssertionType.GROUND || newA.type() == AssertionType.SKOLEMIZED) && newA.kif().get(0) instanceof KifAtom pred) {
                allActiveAssertions.stream()
                        .filter(u -> u.type() == AssertionType.UNIVERSAL && u.derivationDepth() < getMaxDerivationDepth())
                        .filter(u -> u.getReferencedPredicates().contains(pred)) // Quick check
                        .forEach(u -> tryInstantiate(u, newA));
            }
            // Case 2: New assertion is universal, try matching against existing ground/skolemized facts
            else if (newA.type() == AssertionType.UNIVERSAL && newA.derivationDepth() < getMaxDerivationDepth()) {
                ofNullable(newA.getEffectiveTerm()).filter(KifList.class::isInstance).map(KifList.class::cast)
                        .flatMap(KifList::op).map(KifAtom::of) // Get predicate if possible
                        .ifPresent(pred -> allActiveAssertions.stream()
                                .filter(g -> g.type() == AssertionType.GROUND || g.type() == AssertionType.SKOLEMIZED)
                                .filter(g -> g.getReferencedPredicates().contains(pred)) // Quick check
                                .forEach(g -> tryInstantiate(newA, g)));
            }
        }

        private void tryInstantiate(Assertion uniA, Assertion groundA) {
            var formula = uniA.getEffectiveTerm();
            var vars = uniA.quantifiedVars();
            if (vars.isEmpty() || !(formula instanceof KifList))
                return; // Cannot instantiate non-lists or non-universals

            findSubExpressionMatches(formula, groundA.kif())
                    .filter(bindings -> bindings.keySet().containsAll(vars)) // Ensure all universal vars are bound
                    .forEach(bindings -> {
                        var instFormula = Unifier.subst(formula, bindings);
                        if (instFormula instanceof KifList instList && !instFormula.containsVar() && !isTrivial(instList)) {
                            var support = Stream.concat(Stream.of(groundA.id(), uniA.id()), Stream.concat(groundA.justificationIds().stream(), uniA.justificationIds().stream())).collect(Collectors.toSet());
                            var pri = getCogNoteContext().calculateDerivedPri(support, (groundA.pri() + uniA.pri()) / 2.0);
                            var depth = Math.max(groundA.derivationDepth(), uniA.derivationDepth()) + 1;

                            if (depth <= getMaxDerivationDepth() && instList.weight() <= MAX_DERIVED_TERM_WEIGHT) {
                                var isNeg = instList.op().filter(KIF_OP_NOT::equals).isPresent();
                                var isEq = !isNeg && instList.op().filter(KIF_OP_EQUAL::equals).isPresent();
                                var isOriented = isEq && instList.size() == 3 && instList.get(1).weight() > instList.get(2).weight();
                                var type = instList.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
                                var targetNoteId = getCogNoteContext().findCommonSourceNodeId(support);
                                var pa = new PotentialAssertion(instList, pri, support, uniA.id(), isEq, isNeg, isOriented, targetNoteId, type, List.of(), depth);
                                tryCommit(pa, uniA.id());
                            }
                        }
                    });
        }

        // Finds bindings if any sub-expression of 'expr' matches 'target'
        private static Stream<Map<KifVar, KifTerm>> findSubExpressionMatches(KifTerm expr, KifTerm target) {
            return Stream.concat(
                    ofNullable(Unifier.match(expr, target, Map.of())).stream(), // Match whole expression
                    (expr instanceof KifList l) ? l.terms().stream().flatMap(sub -> findSubExpressionMatches(sub, target)) : Stream.empty() // Match sub-expressions recursively
            );
        }
    }

    static class BackwardChainingReasonerPlugin extends BaseReasonerPlugin {
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
                var results = new ArrayList<Map<KifVar, KifTerm>>();
                var maxDepth = (Integer) query.parameters().getOrDefault("maxDepth", MAX_BACKWARD_CHAIN_DEPTH);
                try {
                    prove(query.pattern(), query.targetKbId(), Map.of(), maxDepth, new HashSet<>()).forEach(results::add);
                    return Cog.Answer.success(query.id(), results);
                } catch (Exception e) {
                    System.err.println("Backward chaining query failed: " + e.getMessage());
                    e.printStackTrace();
                    return Cog.Answer.error(query.id(), e.getMessage());
                }
            }, context.events().exe); // Run on event executor
        }

        private Stream<Map<KifVar, KifTerm>> prove(KifTerm goal, @Nullable String kbId, Map<KifVar, KifTerm> bindings, int depth, Set<KifTerm> proofStack) {
            if (depth <= 0) return Stream.empty();
            var currentGoal = Unifier.substFully(goal, bindings);
            if (!proofStack.add(currentGoal)) return Stream.empty(); // Cycle detection

            Stream<Map<KifVar, KifTerm>> resultStream = Stream.empty();

            // Try operators first
            if (currentGoal instanceof KifList goalList && !goalList.terms().isEmpty() && goalList.get(0) instanceof KifAtom opAtom) {
                resultStream = context.operators().get(opAtom)
                        .flatMap(op -> executeOperator(op, goalList, bindings, currentGoal))
                        .stream();
            }

            // Try matching facts in current and global KB
            var kbStream = Stream.concat(getKb(kbId).findUnifiableAssertions(currentGoal),
                            (kbId != null && !kbId.equals(Cog.GLOBAL_KB_NOTE_ID)) ? context.getKb(Cog.GLOBAL_KB_NOTE_ID).findUnifiableAssertions(currentGoal) : Stream.empty())
                    .distinct()
                    .filter(Assertion::isActive)
                    .flatMap(fact -> ofNullable(Unifier.unify(currentGoal, fact.kif(), bindings)).stream());
            resultStream = Stream.concat(resultStream, kbStream);

            // Try applying rules
            var ruleStream = context.rules().stream().flatMap(rule -> {
                var renamedRule = renameRuleVariables(rule, depth); // Avoid variable capture
                return ofNullable(Unifier.unify(renamedRule.consequent(), currentGoal, bindings))
                        .map(consequentBindings -> proveAntecedents(renamedRule.antecedents(), kbId, consequentBindings, depth - 1, new HashSet<>(proofStack)))
                        .orElse(Stream.empty());
            });
            resultStream = Stream.concat(resultStream, ruleStream);

            proofStack.remove(currentGoal); // Backtrack
            return resultStream.distinct(); // Remove duplicate solutions
        }

        private Optional<Map<KifVar, KifTerm>> executeOperator(Operator op, KifList goalList, Map<KifVar, KifTerm> bindings, KifTerm currentGoal) {
            try {
                // Operators run asynchronously but we need the result here
                return op.exe(goalList, context).handle((opResult, ex) -> {
                    if (ex != null) {
                        System.err.println("Operator execution failed for " + op.pred().toKif() + ": " + ex.getMessage());
                        return Optional.<Map<KifVar, KifTerm>>empty();
                    }
                    if (opResult == null) return Optional.<Map<KifVar, KifTerm>>empty();
                    if (opResult.equals(KifAtom.of("true")))
                        return Optional.of(bindings); // Operator succeeded as boolean test
                    return ofNullable(Unifier.unify(currentGoal, opResult, bindings)); // Try to unify result with goal
                }).join(); // Block for result (running within supplyAsync)
            } catch (Exception e) {
                System.err.println("Operator execution exception for " + op.pred().toKif() + ": " + e.getMessage());
                return Optional.empty();
            }
        }

        private Stream<Map<KifVar, KifTerm>> proveAntecedents(List<KifTerm> antecedents, @Nullable String kbId, Map<KifVar, KifTerm> bindings, int depth, Set<KifTerm> proofStack) {
            if (antecedents.isEmpty()) return Stream.of(bindings); // Base case: all antecedents proven
            var first = antecedents.getFirst();
            var rest = antecedents.subList(1, antecedents.size());
            // Prove first, then recursively prove rest with updated bindings
            return prove(first, kbId, bindings, depth, proofStack)
                    .flatMap(newBindings -> proveAntecedents(rest, kbId, newBindings, depth, proofStack));
        }

        private static Rule renameRuleVariables(Rule rule, int depth) {
            var suffix = "_d" + depth + "_" + Cog.idCounter.incrementAndGet();
            Map<KifVar, KifTerm> renameMap = rule.form().vars().stream().collect(Collectors.toMap(Function.identity(), v -> KifVar.of(v.name() + suffix)));
            var renamedForm = (KifList) Unifier.subst(rule.form(), renameMap);
            try {
                return Rule.parseRule(rule.id() + suffix, renamedForm, rule.pri());
            } catch (IllegalArgumentException e) {
                System.err.println("Error renaming rule variables: " + e.getMessage());
                return rule;
            } // Fallback
        }
    }
}
