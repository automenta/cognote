package dumb.cognote;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

import static dumb.cognote.Cog.*;
import static dumb.cognote.Logic.AssertionType.GROUND;
import static dumb.cognote.Logic.AssertionType.SKOLEMIZED;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static java.util.Optional.ofNullable;

public class Logic {
    public static final String KIF_OP_IMPLIES = "=>";
    public static final String KIF_OP_EQUIV = "<=>";
    public static final String KIF_OP_AND = "and";
    public static final String KIF_OP_OR = "or";
    public static final String KIF_OP_EXISTS = "exists";
    public static final String KIF_OP_FORALL = "forall";
    public static final String KIF_OP_EQUAL = "=";
    public static final String KIF_OP_NOT = "not";
    public static final String PRED_NOTE_SUMMARY = "noteSummary", PRED_NOTE_CONCEPT = "noteConcept", PRED_NOTE_QUESTION = "noteQuestion";
    public static final String ID_PREFIX_OPERATOR = "op_", ID_PREFIX_SKOLEM_CONST = "skc_", ID_PREFIX_SKOLEM_FUNC = "skf_", ID_PREFIX_FACT = "fact_", ID_PREFIX_TICKET = "tms_";
    private static final Set<String> REFLEXIVE_PREDICATES = Set.of("instance", "subclass", "subrelation", "equivalent", "same", "equal", "domain", "range");

    static boolean isTrivial(Term.Lst l) {
        var s = l.size();
        var opOpt = l.op();
        if (s >= 3 && l.get(1).equals(l.get(2)))
            return opOpt.filter(op -> REFLEXIVE_PREDICATES.contains(op) || op.equals(KIF_OP_EQUAL)).isPresent();
        else if (opOpt.filter(KIF_OP_NOT::equals).isPresent() && s == 2 && l.get(1) instanceof Term.Lst inner)
            return inner.size() >= 3 && inner.get(1).equals(inner.get(2)) && inner.op().filter(op -> REFLEXIVE_PREDICATES.contains(op) || op.equals(KIF_OP_EQUAL)).isPresent();
        else
            return false;
    }

    private static boolean groundOrSkolemized(Assertion a) {
        var t = a.type();
        return t == GROUND || t == SKOLEMIZED;
    }

    public enum AssertionType {GROUND, UNIVERSAL, SKOLEMIZED}

    public enum RetractionType {BY_ID, BY_NOTE, BY_RULE_FORM, BY_KIF}

    enum Unifier {
        ;

        private static final int MAX_SUBST_DEPTH = 50;

        @Nullable
        static Map<Term.Var, Term> unify(Term x, Term y, Map<Term.Var, Term> bindings) {
            return unifyRecursive(x, y, bindings, 0);
        }

        @Nullable
        static Map<Term.Var, Term> match(Term pattern, Term term, Map<Term.Var, Term> bindings) {
            return matchRecursive(pattern, term, bindings, 0);
        }

        static Term subst(Term term, Map<Term.Var, Term> bindings) {
            return substRecursive(term, bindings, 0, false);
        }

        static Term substFully(Term term, Map<Term.Var, Term> bindings) {
            return substRecursive(term, bindings, 0, true);
        }

        static Optional<Term> rewrite(Term target, Term lhsPattern, Term rhsTemplate) {
            return rewriteRecursive(target, lhsPattern, rhsTemplate, 0);
        }

        @Nullable
        private static Map<Term.Var, Term> unifyRecursive(Term x, Term y, Map<Term.Var, Term> bindings, int depth) {
            if (bindings == null || depth > MAX_SUBST_DEPTH) return null;
            var xSubst = substRecursive(x, bindings, depth + 1, true);
            var ySubst = substRecursive(y, bindings, depth + 1, true);
            if (xSubst.equals(ySubst)) return bindings;
            if (xSubst instanceof Term.Var varX) return bindVariable(varX, ySubst, bindings, true, depth);
            if (ySubst instanceof Term.Var varY) return bindVariable(varY, xSubst, bindings, true, depth);
            if (xSubst instanceof Term.Lst lx && ySubst instanceof Term.Lst ly) {
                var s = lx.size();
                if (s == ly.size()) {
                    var current = bindings;
                    for (var i = 0; i < s; i++) {
                        current = unifyRecursive(lx.get(i), ly.get(i), current, depth + 1);
                        if (current == null) return null;
                    }
                    return current;
                }
            }
            return null;
        }

        @Nullable
        private static Map<Term.Var, Term> matchRecursive(Term pattern, Term term, Map<Term.Var, Term> bindings, int depth) {
            if (bindings == null || depth > MAX_SUBST_DEPTH) return null;
            var patternSubst = substRecursive(pattern, bindings, depth + 1, true);
            if (patternSubst instanceof Term.Var varP) return bindVariable(varP, term, bindings, false, depth);
            if (patternSubst.equals(term)) return bindings;
            if (patternSubst instanceof Term.Lst lp && term instanceof Term.Lst lt) {
                var s = lp.size();
                if (s == lt.size()) {
                    var current = bindings;
                    for (var i = 0; i < s; i++) {
                        current = matchRecursive(lp.get(i), lt.get(i), current, depth + 1);
                        if (current == null) return null;
                    }
                    return current;
                }
            }
            return null;
        }

        private static Term substRecursive(Term term, Map<Term.Var, Term> bindings, int depth, boolean fully) {
            if (bindings.isEmpty() || depth > MAX_SUBST_DEPTH || (term instanceof Term.Lst l && !l.containsVar()))
                return term;
            return switch (term) {
                case Term.Atom atom -> atom;
                case Term.Var var -> {
                    var binding = bindings.get(var);
                    yield (binding != null && fully) ? substRecursive(binding, bindings, depth + 1, true) : requireNonNullElse(binding, var);
                }
                case Term.Lst list -> {
                    var changed = new boolean[]{false};
                    var newTerms = list.terms.stream().map(sub -> {
                        var subSubst = substRecursive(sub, bindings, depth + 1, fully);
                        if (subSubst != sub) changed[0] = true;
                        return subSubst;
                    }).toList();
                    yield changed[0] ? new Term.Lst(newTerms) : list;
                }
            };
        }

        @Nullable
        private static Map<Term.Var, Term> bindVariable(Term.Var var, Term value, Map<Term.Var, Term> bindings, boolean doOccursCheck, int depth) {
            if (var.equals(value)) return bindings;
            if (bindings.containsKey(var))
                return doOccursCheck ? unifyRecursive(bindings.get(var), value, bindings, depth + 1) : matchRecursive(bindings.get(var), value, bindings, depth + 1);
            var finalValue = substRecursive(value, bindings, depth + 1, true);
            if (doOccursCheck && occursCheckRecursive(var, finalValue, bindings, depth + 1)) return null;
            Map<Term.Var, Term> newBindings = new HashMap<>(bindings);
            newBindings.put(var, finalValue);
            return Collections.unmodifiableMap(newBindings);
        }

        private static boolean occursCheckRecursive(Term.Var var, Term term, Map<Term.Var, Term> bindings, int depth) {
            if (depth > MAX_SUBST_DEPTH) return true;
            var substTerm = substRecursive(term, bindings, depth + 1, true);
            return switch (substTerm) {
                case Term.Var v -> var.equals(v);
                case Term.Lst l -> l.terms.stream().anyMatch(t -> occursCheckRecursive(var, t, bindings, depth + 1));
                case Term.Atom ignored -> false;
            };
        }

        private static Optional<Term> rewriteRecursive(Term target, Term lhs, Term rhs, int depth) {
            if (depth > MAX_SUBST_DEPTH) return Optional.empty();
            return ofNullable(matchRecursive(lhs, target, Map.of(), depth + 1))
                    .map(b -> substRecursive(rhs, b, depth + 1, true))
                    .or(() -> (target instanceof Term.Lst tl) ? rewriteSubterms(tl, lhs, rhs, depth + 1) : Optional.empty());
        }

        private static Optional<Term> rewriteSubterms(Term.Lst targetList, Term lhs, Term rhs, int depth) {
            var changed = new boolean[]{false};
            var newSubs = targetList.terms.stream().map(sub ->
                    rewriteRecursive(sub, lhs, rhs, depth + 1).map(rewritten -> {
                        changed[0] = true;
                        return rewritten;
                    }).orElse(sub)
            ).toList();
            return changed[0] ? Optional.of(new Term.Lst(newSubs)) : Optional.empty();
        }
    }

    public record Explanation(String details) {
    }

    static class Skolemizer {
        static Term.Lst skolemize(Term.Lst existentialFormula, Map<Term.Var, Term> contextBindings) {
            if (!KIF_OP_EXISTS.equals(existentialFormula.op().orElse("")))
                throw new IllegalArgumentException("Input must be an 'exists' formula");
            if (existentialFormula.size() != 3 || !(existentialFormula.get(1) instanceof Term.Lst || existentialFormula.get(1) instanceof Term.Var) || !(existentialFormula.get(2) instanceof Term.Lst body))
                throw new IllegalArgumentException("Invalid 'exists' format: " + existentialFormula.toKif());

            var vars = Term.collectSpecVars(existentialFormula.get(1));
            if (vars.isEmpty()) return body;

            Set<Term.Var> freeVars = new HashSet<>(body.vars());
            freeVars.removeAll(vars);
            var skolemArgs = freeVars.stream().map(fv -> Unifier.substFully(contextBindings.getOrDefault(fv, fv), contextBindings)).sorted(Comparator.comparing(Term::toKif)).toList();

            Map<Term.Var, Term> skolemMap = new HashMap<>();
            for (var exVar : vars) {
                var skolemNameBase = ID_PREFIX_SKOLEM_CONST + exVar.name().substring(1) + "_" + Cog.id.incrementAndGet();
                var skolemTerm = skolemArgs.isEmpty()
                        ? Term.Atom.of(skolemNameBase)
                        : new Term.Lst(Stream.concat(Stream.of(Term.Atom.of(ID_PREFIX_SKOLEM_FUNC + exVar.name().substring(1) + "_" + id.incrementAndGet())), skolemArgs.stream()).toList());
                skolemMap.put(exVar, skolemTerm);
            }
            var substituted = Unifier.subst(body, skolemMap);
            return (substituted instanceof Term.Lst sl) ? sl : new Term.Lst(substituted);
        }
    }

    static class Path {

        static class PathNode {
            static final Class<Term.Var> VAR_MARKER = Term.Var.class;
            static final Object LIST_MARKER = new Object();
            final ConcurrentMap<Object, PathNode> children = new ConcurrentHashMap<>();
            final Set<String> assertionIdsHere = ConcurrentHashMap.newKeySet();
        }

        static class PathIndex {
            private final PathNode root = new PathNode();
            private final Truths tms;

            PathIndex(Truths tms) {
                this.tms = tms;
            }

            private static void addPathsRecursive(Term term, String assertionId, PathNode currentNode) {
                if (currentNode == null) return;
                currentNode.assertionIdsHere.add(assertionId);
                var key = getIndexKey(term);
                var termNode = currentNode.children.computeIfAbsent(key, _ -> new PathNode());
                termNode.assertionIdsHere.add(assertionId);
                if (term instanceof Term.Lst list)
                    list.terms.forEach(subTerm -> addPathsRecursive(subTerm, assertionId, termNode));
            }

            private static boolean removePathsRecursive(Term term, String assertionId, PathNode currentNode) {
                if (currentNode == null) return false;
                currentNode.assertionIdsHere.remove(assertionId);
                var key = getIndexKey(term);
                var termNode = currentNode.children.get(key);
                if (termNode != null) {
                    termNode.assertionIdsHere.remove(assertionId);
                    var canPruneChild = true;
                    if (term instanceof Term.Lst list)
                        canPruneChild = list.terms.stream().allMatch(subTerm -> removePathsRecursive(subTerm, assertionId, termNode));
                    if (canPruneChild && termNode.assertionIdsHere.isEmpty() && termNode.children.isEmpty())
                        currentNode.children.remove(key, termNode);
                }
                return currentNode.assertionIdsHere.isEmpty() && currentNode.children.isEmpty();
            }

            private static Object getIndexKey(Term term) {
                return switch (term) {
                    case Term.Atom a -> a.value();
                    case Term.Var _ -> PathNode.VAR_MARKER;
                    case Term.Lst l -> l.op().map(op -> (Object) op).orElse(PathNode.LIST_MARKER);
                };
            }

            private static void collectAllAssertionIds(PathNode node, Set<String> ids) {
                if (node == null) return;
                ids.addAll(node.assertionIdsHere);
                node.children.values().forEach(child -> collectAllAssertionIds(child, ids));
            }

            private static void findUnifiableRecursive(Term queryTerm, PathNode indexNode, Set<String> candidates) {
                if (indexNode == null) return;
                ofNullable(indexNode.children.get(PathNode.VAR_MARKER)).ifPresent(varNode -> collectAllAssertionIds(varNode, candidates));
                if (queryTerm instanceof Term.Lst)
                    ofNullable(indexNode.children.get(PathNode.LIST_MARKER)).ifPresent(listNode -> collectAllAssertionIds(listNode, candidates));
                var specificNode = indexNode.children.get(getIndexKey(queryTerm));
                if (specificNode != null) {
                    candidates.addAll(specificNode.assertionIdsHere);
                    if (queryTerm instanceof Term.Lst) collectAllAssertionIds(specificNode, candidates);
                }
                if (queryTerm instanceof Term.Var)
                    indexNode.children.values().forEach(childNode -> collectAllAssertionIds(childNode, candidates));
            }

            private static void findInstancesRecursive(Term queryPattern, PathNode indexNode, Set<String> candidates) {
                if (indexNode == null) return;
                if (queryPattern instanceof Term.Var) {
                    collectAllAssertionIds(indexNode, candidates);
                    return;
                }
                var specificNode = indexNode.children.get(getIndexKey(queryPattern));
                if (specificNode != null) {
                    candidates.addAll(specificNode.assertionIdsHere);
                    if (queryPattern instanceof Term.Lst listPattern && !listPattern.terms.isEmpty()) {
                        collectAllAssertionIds(specificNode, candidates);
                    }
                }
            }

            private static void findGeneralizationsRecursive(Term queryTerm, PathNode indexNode, Set<String> candidates) {
                if (indexNode == null) return;
                ofNullable(indexNode.children.get(PathNode.VAR_MARKER)).ifPresent(varNode -> collectAllAssertionIds(varNode, candidates));
                if (queryTerm instanceof Term.Lst)
                    ofNullable(indexNode.children.get(PathNode.LIST_MARKER)).ifPresent(listNode -> candidates.addAll(listNode.assertionIdsHere));
                ofNullable(indexNode.children.get(getIndexKey(queryTerm))).ifPresent(nextNode -> {
                    candidates.addAll(nextNode.assertionIdsHere);
                    if (queryTerm instanceof Term.Lst queryList && !queryList.terms.isEmpty()) {
                        queryList.terms.forEach(subTerm -> findGeneralizationsRecursive(subTerm, nextNode, candidates));
                    }
                });
            }

            void add(Assertion assertion) {
                if (tms.isActive(assertion.id())) addPathsRecursive(assertion.kif(), assertion.id(), root);
            }

            void remove(Assertion assertion) {
                removePathsRecursive(assertion.kif(), assertion.id(), root);
            }

            void clear() {
                root.children.clear();
                root.assertionIdsHere.clear();
            }

            Stream<Assertion> findUnifiableAssertions(Term queryTerm) {
                return findCandidates(queryTerm, PathIndex::findUnifiableRecursive).stream().map(tms::get).flatMap(Optional::stream).filter(Assertion::isActive);
            }

            Stream<Assertion> findInstancesOf(Term queryPattern) {
                var neg = (queryPattern instanceof Term.Lst ql && ql.op().filter(KIF_OP_NOT::equals).isPresent());
                return findCandidates(queryPattern, PathIndex::findInstancesRecursive).stream().map(tms::get).flatMap(Optional::stream).filter(Assertion::isActive).filter(a -> a.negated() == neg).filter(a -> Unifier.match(queryPattern, a.kif(), Map.of()) != null);
            }

            Stream<Assertion> findGeneralizationsOf(Term queryTerm) {
                return findCandidates(queryTerm, PathIndex::findGeneralizationsRecursive).stream().map(tms::get).flatMap(Optional::stream).filter(Assertion::isActive);
            }

            private Set<String> findCandidates(Term query, TriConsumer<Term, PathNode, Set<String>> searchFunc) {
                Set<String> candidates = ConcurrentHashMap.newKeySet();
                searchFunc.accept(query, root, candidates);
                return Set.copyOf(candidates);
            }

            @FunctionalInterface
            private interface TriConsumer<T, U, V> {
                void accept(T t, U u, V v);
            }
        }
    }

    public static class Knowledge {
        public final String id;
        final int capacity;
        final Events events;
        final Truths truth;
        final Path.PathIndex paths;
        final ConcurrentMap<Term.Atom, Set<String>> universalIndex = new ConcurrentHashMap<>();
        final PriorityBlockingQueue<String> groundEvictionQueue;
        private final ReadWriteLock lock = new ReentrantReadWriteLock();

        Knowledge(String kbId, int capacity, Events events, Truths truth) {
            this.id = requireNonNull(kbId);
            this.capacity = capacity;
            this.events = requireNonNull(events);
            this.truth = requireNonNull(truth);
            this.paths = new Path.PathIndex(truth);
            this.groundEvictionQueue = new PriorityBlockingQueue<>(1024,
                    Comparator.<String, Double>
                                    comparing(id -> truth.get(id).map(Assertion::pri).orElse(Double.MAX_VALUE))
                            .thenComparing(id -> truth.get(id).map(Assertion::timestamp).orElse(Long.MAX_VALUE)));
        }

        public int getAssertionCount() {
            return (int) truth.getAllActiveAssertions().stream().filter(a -> a.kb().equals(id)).count();
        }

        List<String> getAllAssertionIds() {
            return truth.getAllActiveAssertions().stream().filter(a -> a.kb().equals(id)).map(Assertion::id).toList();
        }

        Optional<Assertion> assertion(String id) {
            return truth.get(id).filter(a -> a.kb().equals(this.id));
        }

        List<Assertion> getAllAssertions() {
            return truth.getAllActiveAssertions().stream().filter(a -> a.kb().equals(id)).toList();
        }

        @Nullable Assertion commit(Assertion.PotentialAssertion pa, String source) {
            var k = pa.kif();
            if (k instanceof Term.Lst kl && isTrivial(kl)) return null;
            lock.writeLock().lock();
            try {
                var dt = pa.derivedType();
                var finalType = dt == GROUND && k.containsSkolemTerm() ? SKOLEMIZED : dt;

                var existingMatch = findExactMatchInternal(k);
                if (existingMatch.isPresent() && truth.isActive(existingMatch.get().id())) return null;
                if (isSubsumedInternal(k, pa.isNegated())) return null;

                enforceKbCapacityInternal(source);
                if (getAssertionCount() >= capacity) {
                    System.err.printf("Warning: KB '%s' full (%d/%d) after eviction attempt. Cannot add: %s%n", id, getAssertionCount(), capacity, k.toKif());
                    return null;
                }

                var newId = id(ID_PREFIX_FACT + finalType.name().toLowerCase() + "_");
                var newAssertion = new Assertion(newId, k, pa.pri(), System.currentTimeMillis(), pa.sourceNoteId(), pa.support(), finalType, pa.isEquality(), pa.isOrientedEquality(), pa.isNegated(), pa.quantifiedVars(), pa.derivationDepth(), true, id);

                var ticket = truth.add(newAssertion, pa.support(), source);
                if (ticket == null) return null;

                var addedAssertion = truth.get(newId).orElse(null);
                if (addedAssertion == null || !addedAssertion.isActive()) return null;

                switch (finalType) {
                    case GROUND, SKOLEMIZED -> {
                        paths.add(addedAssertion);
                        groundEvictionQueue.offer(newId);
                    }
                    case UNIVERSAL ->
                            addedAssertion.getReferencedPredicates().forEach(pred -> universalIndex.computeIfAbsent(pred, _ -> ConcurrentHashMap.newKeySet()).add(newId));
                }
                checkResourceThresholds();
                events.emit(new AssertedEvent(addedAssertion, id));
                return addedAssertion;
            } finally {
                lock.writeLock().unlock();
            }
        }

        void retract(String id, String source) {
            lock.writeLock().lock();
            try {
                truth.remove(id, source);
            } finally {
                lock.writeLock().unlock();
            }
        }

        void clear(String source) {
            lock.writeLock().lock();
            try {
                new HashSet<>(getAllAssertionIds()).forEach(id -> truth.remove(id, source));
                paths.clear();
                universalIndex.clear();
                groundEvictionQueue.clear();
            } finally {
                lock.writeLock().unlock();
            }
        }

        Stream<Assertion> findUnifiableAssertions(Term queryTerm) {
            return paths.findUnifiableAssertions(queryTerm);
        }

        Stream<Assertion> findInstancesOf(Term queryPattern) {
            return paths.findInstancesOf(queryPattern);
        }

        List<Assertion> findRelevantUniversalAssertions(Term.Atom predicate) {
            return universalIndex.getOrDefault(predicate, Set.of()).stream().map(truth::get).flatMap(Optional::stream).filter(Assertion::isActive).filter(a -> a.kb().equals(id)).toList();
        }

        private boolean isSubsumedInternal(Term term, boolean isNegated) {
            return paths.findGeneralizationsOf(term)
                    .filter(Logic::groundOrSkolemized)
                    .anyMatch(candidate -> candidate.negated() == isNegated && Unifier.match(candidate.getEffectiveTerm(), term, Map.of()) != null);
        }

        private Optional<Assertion> findExactMatchInternal(Term.Lst kif) {
            return paths.findInstancesOf(kif).filter(a -> kif.equals(a.kif())).findFirst();
        }

        private void enforceKbCapacityInternal(String source) {
            while (getAssertionCount() >= capacity && !groundEvictionQueue.isEmpty()) {
                ofNullable(groundEvictionQueue.poll())
                        .flatMap(truth::get)
                        .filter(a -> groundOrSkolemized(a) && a.kb().equals(id))
                        .ifPresent(toEvict -> {
                            truth.remove(toEvict.id(), source + "-evict");
                            events.emit(new AssertionEvictedEvent(toEvict, id));
                        });
            }
        }

        private void checkResourceThresholds() {
            var currentSize = getAssertionCount();
            var warnT = capacity * KB_SIZE_THRESHOLD_WARN_PERCENT / 100;
            var haltT = capacity * KB_SIZE_THRESHOLD_HALT_PERCENT / 100;
            if (currentSize >= haltT)
                System.err.printf("KB CRITICAL (KB: %s): Size %d/%d (%.1f%%)%n", id, currentSize, capacity, 100.0 * currentSize / capacity);
            else if (currentSize >= warnT)
                System.out.printf("KB WARNING (KB: %s): Size %d/%d (%.1f%%)%n", id, currentSize, capacity, 100.0 * currentSize / capacity);
        }

        void retractExternal(Assertion a) {
            lock.writeLock().lock();
            try {
                switch (a.type()) {
                    case GROUND, SKOLEMIZED -> retractStandard(a);
                    case UNIVERSAL -> retractUniversal(a);
                }
            } finally {
                lock.writeLock().unlock();
            }
        }

        private void retractStandard(Assertion a) {
            paths.remove(a);
            groundEvictionQueue.remove(a.id());
        }

        private void retractUniversal(Assertion a) {
            a.getReferencedPredicates().forEach(pred -> universalIndex.computeIfPresent(pred, (_, ids) -> {
                ids.remove(a.id());
                return ids.isEmpty() ? null : ids;
            }));
        }

        void handleExternalStatusChange(Assertion a) {
            lock.writeLock().lock();
            try {
                if (a.isActive()) {
                    switch (a.type()) {
                        case GROUND, SKOLEMIZED -> paths.add(a);
                        case UNIVERSAL ->
                                a.getReferencedPredicates().forEach(pred -> universalIndex.computeIfAbsent(pred, _ -> ConcurrentHashMap.newKeySet()).add(a.id()));
                    }
                } else
                    retractExternal(a);
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    public static class Cognition {
        public final Truths truth;
        public final Op.Operators operators;
        public final Set<String> activeNoteIds = ConcurrentHashMap.newKeySet();
        public final Events events;
        final Cog cog;
        private final ConcurrentMap<String, Knowledge> noteKbs = new ConcurrentHashMap<>();
        private final Knowledge globalKb;
        private final Set<Rule> rules = ConcurrentHashMap.newKeySet();


        Cognition(int globalKbCapacity, Events events, Truths truth, Op.Operators operators, Cog cog) {
            this.cog = cog;
            this.events = requireNonNull(events);
            this.truth = requireNonNull(truth);
            this.operators = requireNonNull(operators);
            this.globalKb = new Knowledge(GLOBAL_KB_NOTE_ID, globalKbCapacity, events, truth);
            activeNoteIds.add(GLOBAL_KB_NOTE_ID); // Global KB is always active
        }

        public static Term.Lst performSkolemization(Term.Lst body, Collection<Term.Var> existentialVars, Map<Term.Var, Term> contextBindings) {
            return Skolemizer.skolemize(new Term.Lst(Term.Atom.of(KIF_OP_EXISTS), new Term.Lst(new ArrayList<>(existentialVars)), body), contextBindings);
        }

        public static Term.Lst simplifyLogicalTerm(Term.Lst term) {
            final var MAX_DEPTH = 5;
            var current = term;
            for (var depth = 0; depth < MAX_DEPTH; depth++) {
                var next = simplifyLogicalTermOnce(current);
                if (next.equals(current)) return current;
                current = next;
            }
            if (!term.equals(current))
                System.err.println("Warning: Simplification depth limit reached for: " + term.toKif());
            return current;
        }

        private static Term.Lst simplifyLogicalTermOnce(Term.Lst term) {
            if (term.op().filter(KIF_OP_NOT::equals).isPresent() && term.size() == 2 && term.get(1) instanceof Term.Lst nl && nl.op().filter(KIF_OP_NOT::equals).isPresent() && nl.size() == 2 && nl.get(1) instanceof Term.Lst inner)
                return simplifyLogicalTermOnce(inner);
            var changed = new boolean[]{false};
            var newTerms = term.terms.stream().map(subTerm -> {
                var simplifiedSub = (subTerm instanceof Term.Lst sl) ? simplifyLogicalTermOnce(sl) : subTerm;
                if (!simplifiedSub.equals(subTerm)) changed[0] = true;
                return simplifiedSub;
            }).toList();
            return changed[0] ? new Term.Lst(newTerms) : term;
        }

        public Knowledge kb(@Nullable String noteId) {
            return (noteId == null || GLOBAL_KB_NOTE_ID.equals(noteId)) ? globalKb : noteKbs.computeIfAbsent(noteId, id -> new Knowledge(id, globalKb.capacity, events, truth));
        }

        public Knowledge kbGlobal() {
            return globalKb;
        }

        public Map<String, Knowledge> getAllNoteKbs() {
            return Collections.unmodifiableMap(noteKbs);
        }

        public Set<String> getAllNoteIds() {
            return Collections.unmodifiableSet(noteKbs.keySet());
        }

        public Set<Rule> rules() {
            return Collections.unmodifiableSet(rules);
        }

        public int ruleCount() {
            return rules.size();
        }

        public int kbCount() {
            return (int) truth.getAllActiveAssertions().stream().filter(a -> a.kb().equals(GLOBAL_KB_NOTE_ID) || noteKbs.containsKey(a.kb())).count();
        }

        public int kbTotalCapacity() {
            return globalKb.capacity + noteKbs.size() * globalKb.capacity;
        }

        public boolean addRule(Rule rule) {
            var added = rules.add(rule);
            if (added) events.emit(new RuleAddedEvent(rule));
            return added;
        }

        public boolean removeRule(Rule rule) {
            var removed = rules.remove(rule);
            if (removed) events.emit(new RuleRemovedEvent(rule));
            return removed;
        }

        public boolean removeRule(Term.Lst ruleForm) {
            return rules.stream().filter(r -> r.form().equals(ruleForm)).findFirst().map(this::removeRule).orElse(false);
        }

        public void removeNoteKb(String noteId, String source) {
            ofNullable(noteKbs.remove(noteId)).ifPresent(kb -> kb.clear(source));
        }

        public void clear() {
            globalKb.clear("clearAll");
            noteKbs.values().forEach(kb -> kb.clear("clearAll"));
            noteKbs.clear();
            rules.clear();
            activeNoteIds.clear();
            activeNoteIds.add(GLOBAL_KB_NOTE_ID); // Global KB is always active
        }

        public Optional<Assertion> findAssertionByIdAcrossKbs(String assertionId) {
            return truth.get(assertionId);
        }

        // Method to find an assertion by its KIF form in a specific KB or across all active KBs
        public Optional<Assertion> findAssertionByKif(Term.Lst kif, @Nullable String kbId) {
            Stream<Knowledge> kbsToSearch;
            if (kbId == null || GLOBAL_KB_NOTE_ID.equals(kbId)) {
                // Search global KB and all active note KBs
                kbsToSearch = Stream.concat(Stream.of(globalKb), noteKbs.values().stream().filter(kb -> activeNoteIds.contains(kb.id)));
            } else {
                // Search only the specified KB if it exists and is active
                kbsToSearch = ofNullable(noteKbs.get(kbId)).filter(kb -> activeNoteIds.contains(kb.id)).stream();
            }

            return kbsToSearch
                    .flatMap(kb -> kb.findInstancesOf(kif)) // Use findInstancesOf which does a match check
                    .filter(a -> kif.equals(a.kif())) // Ensure exact KIF match
                    .findFirst();
        }


        @Nullable
        public String commonSourceNodeId(Set<String> supportIds) {
            if (supportIds == null || supportIds.isEmpty()) return null;
            String commonId = null;
            var firstFound = false;
            Set<String> visited = new HashSet<>();
            Queue<String> toCheck = new LinkedList<>(supportIds);
            while (!toCheck.isEmpty()) {
                var currentId = toCheck.poll();
                if (currentId == null || !visited.add(currentId)) continue;

                var assertion = findAssertionByIdAcrossKbs(currentId).orElse(null);
                if (assertion != null) {
                    var s = assertion.sourceNoteId();
                    if (s != null) {
                        if (!firstFound) {
                            commonId = s;
                            firstFound = true;
                        } else if (!commonId.equals(s))
                            return null;
                    } else if (assertion.derivationDepth() > 0 && !assertion.justificationIds().isEmpty()) {
                        assertion.justificationIds().forEach(toCheck::offer);
                    }
                }
            }
            return commonId;
        }

        public double calculateDerivedPri(Set<String> supportIds, double basePri) {
            return supportIds.isEmpty() ? basePri : supportIds.stream().map(this::findAssertionByIdAcrossKbs).flatMap(Optional::stream).mapToDouble(Assertion::pri).min().orElse(basePri) * DERIVED_PRIORITY_DECAY;
        }

        public int calculateDerivedDepth(Set<String> supportIds) {
            return supportIds.stream().map(this::findAssertionByIdAcrossKbs).flatMap(Optional::stream).mapToInt(Assertion::derivationDepth).max().orElse(-1);
        }

        @Nullable
        public Assertion tryCommit(Assertion.PotentialAssertion pa, String source) {
            return kb(pa.sourceNoteId()).commit(pa, source);
        }

        public boolean isActiveNote(@Nullable String noteId) {
            return noteId != null && activeNoteIds.contains(noteId);
        }

        public void addActiveNote(String noteId) {
            activeNoteIds.add(noteId);
        }

        public void removeActiveNote(String noteId) {
            activeNoteIds.remove(noteId);
        }
    }

    public static class KifParser {
        private final Reader reader;
        private int currentChar = -2;
        private int line = 1;
        private int col = 0;
        private int charPos = 0;

        private static final int CONTEXT_BUFFER_SIZE = 50;
        private final StringBuilder contextBuffer = new StringBuilder(CONTEXT_BUFFER_SIZE);


        public KifParser(Reader reader) {
            this.reader = reader;
        }

        public static List<Term> parseKif(String input) throws ParseException {
            if (input == null || input.isBlank()) return List.of();
            try (var sr = new StringReader(input.trim())) {
                return new KifParser(sr).parseTopLevel();
            } catch (IOException e) {
                // This should ideally not happen with StringReader, but handle defensively
                throw new ParseException("Internal Read error: " + e.getMessage(), 0, 0, "");
            }
        }

        private static boolean isValidAtomChar(int c) {
            return c != -1 && !Character.isWhitespace(c) && "()\";?".indexOf(c) == -1 && c != ';';
        }

        private List<Term> parseTopLevel() throws IOException, ParseException {
            List<Term> terms = new ArrayList<>();
            consumeWhitespaceAndComments();
            while (peek() != -1) {
                terms.add(parseTerm());
                consumeWhitespaceAndComments();
            }
            return Collections.unmodifiableList(terms);
        }

        private Term parseTerm() throws IOException, ParseException {
            consumeWhitespaceAndComments();
            var next = peek();
            return switch (next) {
                case -1 -> throw createParseException("Unexpected EOF while looking for term", "EOF");
                case '(' -> parseList();
                case '"' -> parseQuotedString();
                case '?' -> parseVariable();
                default -> {
                    if (isValidAtomChar(next)) {
                        yield parseAtom();
                    } else {
                        throw createParseException("Invalid character at start of term", "'" + (char) next + "'");
                    }
                }
            };
        }

        private Term.Lst parseList() throws IOException, ParseException {
            consumeChar('(');
            List<Term> terms = new ArrayList<>();
            while (true) {
                consumeWhitespaceAndComments();
                var next = peek();
                if (next == ')') {
                    consumeChar(')');
                    return new Term.Lst(terms);
                }
                if (next == -1) throw createParseException("Unmatched parenthesis", "EOF");

                if (next != '(' && next != '"' && next != '?' && !isValidAtomChar(next)) {
                    throw createParseException("Invalid character inside list", "'" + (char) next + "'");
                }

                terms.add(parseTerm());
            }
        }

        private Term.Var parseVariable() throws IOException, ParseException {
            consumeChar('?');
            var sb = new StringBuilder("?");
            var next = peek();
            if (!isValidAtomChar(next)) {
                throw createParseException("Variable name character expected after '?'", (next == -1) ? "EOF" : "'" + (char) next + "'");
            }
            while (isValidAtomChar(peek())) {
                sb.append((char) consumeChar());
            }
            if (sb.length() < 2) {
                throw createParseException("Empty variable name after '?'", null);
            }
            return Term.Var.of(sb.toString());
        }

        private Term.Atom parseAtom() throws IOException, ParseException {
            var sb = new StringBuilder();
            var next = peek();
            if (!isValidAtomChar(next)) {
                throw createParseException("Invalid character at start of atom", "'" + (char) next + "'");
            }
            while (isValidAtomChar(peek())) {
                sb.append((char) consumeChar());
            }
            return Term.Atom.of(sb.toString());
        }

        private Term.Atom parseQuotedString() throws IOException, ParseException {
            consumeChar('"');
            var sb = new StringBuilder();
            while (true) {
                var c = consumeChar();
                if (c == '"') return Term.Atom.of(sb.toString());
                if (c == -1) throw createParseException("Unmatched quote in string literal", "EOF");
                if (c == '\\') {
                    var next = consumeChar();
                    if (next == -1) throw createParseException("EOF after escape character in string literal", "EOF");
                    sb.append((char) switch (next) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        case '"' -> '"';
                        case '\\' -> '\\';
                        default ->
                                throw createParseException("Invalid escape sequence in string literal", "'\\" + (char) next + "'");
                    });
                } else sb.append((char) c);
            }
        }

        private int peek() throws IOException {
            if (currentChar == -2) {
                currentChar = reader.read();
            }
            return currentChar;
        }

        private int consumeChar() throws IOException {
            var c = peek();
            if (c != -1) {
                currentChar = -2;
                charPos++;
                if (c == '\n') {
                    line++;
                    col = 0;
                } else {
                    col++;
                }
                // Add to buffer and trim if necessary
                contextBuffer.append((char) c);
                if (contextBuffer.length() > CONTEXT_BUFFER_SIZE) {
                    contextBuffer.delete(0, contextBuffer.length() - CONTEXT_BUFFER_SIZE);
                }
            }
            return c;
        }

        private void consumeChar(char expected) throws IOException, ParseException {
            var actual = consumeChar();
            if (actual != expected) {
                throw createParseException("Expected '" + expected + "'", ((actual == -1) ? "EOF" : "'" + (char) actual + "'"));
            }
        }

        private void consumeWhitespaceAndComments() throws IOException {
            while (true) {
                var c = peek();
                if (c == -1) break;
                if (Character.isWhitespace(c)) {
                    consumeChar();
                } else if (c == ';') {
                    do {
                        consumeChar();
                    } while (peek() != '\n' && peek() != '\r' && peek() != -1);
                } else {
                    break;
                }
            }
        }

        private ParseException createParseException(String message) {
            return new ParseException(message, line, col, contextBuffer.toString());
        }

        private ParseException createParseException(String message, @Nullable String foundToken) {
            var foundInfo = foundToken != null ? " found " + foundToken : "";
            return new ParseException(message + foundInfo, line, col, contextBuffer.toString());
        }

        public static class ParseException extends Exception {
            private final int line;
            private final int col;
            private final String context;

            public ParseException(String message) {
                this(message, "");
            }

            public ParseException(String message, String context) {
                this(message, 0, 0, context);
            }
            public ParseException(String message, int line, int col, String context) {
                super(message);
                this.line = line;
                this.col = col;
                this.context = context;
            }

            @Override
            public String getMessage() {
                return super.getMessage() + " at line " + line + " col " + col + ". Context: \"" + context + "\"";
            }
        }
    }

}
