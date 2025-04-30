package dumb.cognote;

import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

import static dumb.cognote.Cog.*;
import static dumb.cognote.Log.error;
import static dumb.cognote.Log.warning;
import static dumb.cognote.Logic.AssertionType.GROUND;
import static dumb.cognote.Logic.AssertionType.SKOLEMIZED;
import static java.util.Optional.ofNullable;

public class Knowledge {
    public final String id;
    public final Truths truth;
    final int capacity;
    final Events events;
    final Logic.Path.PathIndex paths;
    final ConcurrentMap<Term.Atom, Set<String>> universalIndex = new ConcurrentHashMap<>();
    final PriorityBlockingQueue<String> groundEvictionQueue;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    Knowledge(String kbId, int capacity, Events events, Truths truth) {
        this.id = kbId;
        this.capacity = capacity;
        this.events = events;
        this.truth = truth;
        this.paths = new Logic.Path.PathIndex(truth);
        this.groundEvictionQueue = new PriorityBlockingQueue<>(1024,
                Comparator.<String, Double>
                                comparing(id -> truth.get(id).map(Assertion::pri).orElse(Double.MAX_VALUE))
                        .thenComparingLong(id -> truth.get(id).map(Assertion::timestamp).orElse(Long.MAX_VALUE)));

        events.on(CogEvent.AssertionStateEvent.class, this::handleAssertionStateChange);
        events.on(CogEvent.RetractedEvent.class, this::handleAssertionRetracted);
    }

    public int getAssertionCount() {
        return (int) truth.getAllActiveAssertions().stream().filter(a -> a.kb().equals(id)).count();
    }

    public List<String> getAllAssertionIds() {
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
        if (k instanceof Term.Lst kl && Logic.isTrivial(kl)) return null;
        lock.writeLock().lock();
        try {
            var dt = pa.derivedType();
            var finalType = dt == GROUND && k.containsSkolemTerm() ? SKOLEMIZED : dt;

            var existingMatch = findExactMatchInternal(k);
            if (existingMatch.isPresent() && truth.isActive(existingMatch.get().id())) return null;
            if (isSubsumedInternal(k, pa.isNegated())) return null;

            enforceKbCapacityInternal(source);
            if (getAssertionCount() >= capacity) {
                error(String.format("Warning: KB '%s' full (%d/%d) after eviction attempt. Cannot add: %s", id, getAssertionCount(), capacity, k.toKif()));
                return null;
            }

            var newId = id(Logic.ID_PREFIX_FACT + finalType.name().toLowerCase() + "_");
            var newAssertion = new Assertion(newId, k, pa.pri(), System.currentTimeMillis(), pa.sourceNoteId(), pa.support(), finalType, pa.isEquality(), pa.isOrientedEquality(), pa.isNegated(), pa.quantifiedVars(), pa.derivationDepth(), true, id);

            var ticket = truth.add(newAssertion, pa.support(), source);
            if (ticket == null) return null;

            var addedAssertion = truth.get(newId).orElse(null);
            if (addedAssertion == null || !addedAssertion.isActive()) return null;

            checkResourceThresholds();
            events.emit(new CogEvent.AssertedEvent(addedAssertion, id));
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

    public Stream<Assertion> findInstancesOf(Term queryPattern) {
        return paths.findInstancesOf(queryPattern);
    }

    List<Assertion> findRelevantUniversalAssertions(Term.Atom predicate) {
        return universalIndex.getOrDefault(predicate, Set.of()).stream().map(truth::get).flatMap(Optional::stream).filter(Assertion::isActive).filter(a -> a.kb().equals(id)).toList();
    }

    private boolean isSubsumedInternal(Term term, boolean isNegated) {
        return paths.findGeneralizationsOf(term)
                .filter(Logic::groundOrSkolemized)
                .anyMatch(candidate -> candidate.negated() == isNegated && Logic.Unifier.match(candidate.getEffectiveTerm(), term, Map.of()) != null);
    }

    private Optional<Assertion> findExactMatchInternal(Term.Lst kif) {
        return paths.findInstancesOf(kif).filter(a -> kif.equals(a.kif())).findFirst();
    }

    private void enforceKbCapacityInternal(String source) {
        while (getAssertionCount() >= capacity && !groundEvictionQueue.isEmpty()) {
            ofNullable(groundEvictionQueue.poll())
                    .flatMap(truth::get)
                    .filter(a -> Logic.groundOrSkolemized(a) && a.kb().equals(id))
                    .ifPresent(toEvict -> {
                        truth.remove(toEvict.id(), source + "-evict");
                        events.emit(new CogEvent.AssertionEvictedEvent(toEvict, id));
                    });
        }
    }

    private void checkResourceThresholds() {
        var currentSize = getAssertionCount();
        var warnT = capacity * KB_SIZE_THRESHOLD_WARN_PERCENT / 100;
        var haltT = capacity * KB_SIZE_THRESHOLD_HALT_PERCENT / 100;
        if (currentSize >= haltT)
            error(String.format("KB CRITICAL (KB: %s): Size %d/%d (%.1f%%)", id, currentSize, capacity, 100.0 * currentSize / capacity));
        else if (currentSize >= warnT)
            warning(String.format("KB WARNING (KB: %s): Size %d/%d (%.1f%%)", id, currentSize, capacity, 100.0 * currentSize / capacity));
    }

    private void handleAssertionStateChange(CogEvent.AssertionStateEvent event) {
        if (!event.kbId().equals(this.id)) return;
        lock.writeLock().lock();
        try {
            truth.get(event.assertionId()).ifPresent(a -> {
                if (a.isActive() != event.isActive()) {
                    handleExternalStatusChange(a);
                }
            });
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void handleAssertionRetracted(CogEvent.RetractedEvent event) {
        if (!event.kbId().equals(this.id)) return;
        lock.writeLock().lock();
        try {
            retractExternal(event.assertion());
        } finally {
            lock.writeLock().unlock();
        }
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
                    case GROUND, SKOLEMIZED -> {
                        paths.add(a);
                        groundEvictionQueue.offer(a.id());
                    }
                    case UNIVERSAL ->
                            a.getReferencedPredicates().forEach(pred -> universalIndex.computeIfAbsent(pred, _ -> ConcurrentHashMap.newKeySet()).add(a.id()));
                }
            } else
                retractExternal(a);
        } finally {
            lock.writeLock().unlock();
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
                var neg = (queryPattern instanceof Term.Lst ql && ql.op().filter(Logic.KIF_OP_NOT::equals).isPresent());
                return findCandidates(queryPattern, PathIndex::findInstancesRecursive).stream().map(tms::get).flatMap(Optional::stream).filter(Assertion::isActive).filter(a -> a.negated() == neg).filter(a -> Logic.Unifier.match(queryPattern, a.kif(), Map.of()) != null);
            }

            Stream<Assertion> findGeneralizationsOf(Term queryTerm) {
                return findCandidates(queryTerm, PathIndex::findGeneralizationsRecursive).stream().map(tms::get).flatMap(Optional::stream).filter(Assertion::isActive);
            }

            private Set<String> findCandidates(Term queryTerm, TriConsumer<Term, PathNode, Set<String>> findMethod) {
                Set<String> candidates = new HashSet<>();
                findMethod.accept(queryTerm, root, candidates);
                return candidates;
            }

            @FunctionalInterface
            private interface TriConsumer<T, U, V> {
                void accept(T t, U u, V v);
            }
        }
    }
}
