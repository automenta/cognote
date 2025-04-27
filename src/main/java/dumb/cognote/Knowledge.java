package dumb.cognote;

import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;

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
import static dumb.cognote.Log.error;
import static dumb.cognote.Log.message;
import static dumb.cognote.Log.warning;
import static java.util.Objects.requireNonNull;
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
        this.id = requireNonNull(kbId);
        this.capacity = capacity;
        this.events = requireNonNull(events);
        this.truth = requireNonNull(truth);
        this.paths = new Logic.Path.PathIndex(truth);
        this.groundEvictionQueue = new PriorityBlockingQueue<>(1024,
                Comparator.<String, Double>
                                comparing(id -> truth.get(id).map(Assertion::pri).orElse(Double.MAX_VALUE))
                        .thenComparingLong(id -> truth.get(id).map(Assertion::timestamp).orElse(Long.MAX_VALUE))); // Tie-break by timestamp (oldest first)

        // Listen for TMS events to update local indices
        events.on(Cog.AssertionStateEvent.class, this::handleAssertionStateChange);
        events.on(Cog.RetractedEvent.class, this::handleAssertionRetracted);
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

            // Indices are updated by handleAssertionStateChange
            checkResourceThresholds();
            events.emit(new Cog.AssertedEvent(addedAssertion, id));
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
                        events.emit(new Cog.AssertionEvictedEvent(toEvict, id));
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

    private void handleAssertionStateChange(Cog.AssertionStateEvent event) {
        if (!event.kbId().equals(this.id)) return;
        lock.writeLock().lock();
        try {
            truth.get(event.assertionId()).ifPresent(a -> {
                if (a.isActive() != event.isActive()) {
                    // This event is from TMS, the assertion status is already updated in the central store.
                    // We just need to update local indices.
                    handleExternalStatusChange(a);
                }
            });
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void handleAssertionRetracted(Cog.RetractedEvent event) {
        if (!event.kbId().equals(this.id)) return;
        lock.writeLock().lock();
        try {
            // The assertion is already removed from the central store by TMS.
            // We just need to remove it from local indices.
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
}
