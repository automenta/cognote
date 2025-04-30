package dumb.cognote;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import static dumb.cognote.Cog.id;
import static dumb.cognote.Log.error;
import static dumb.cognote.Log.message;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;

public interface Truths {
    SupportTicket add(Assertion assertion, Set<String> justificationIds, String source);

    void remove(String assertionId, String source);

    Set<String> getActiveSupport(String assertionId);

    boolean isActive(String assertionId);

    Optional<Assertion> get(String assertionId);

    Collection<Assertion> getAllActiveAssertions();

    void resolveContradiction(Contradiction contradiction, ResolutionStrategy strategy);

    Set<Contradiction> findContradictions();

    enum ResolutionStrategy {RETRACT_WEAKEST, LOG_ONLY}

    record SupportTicket(String ticketId, String assertionId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Contradiction(Set<String> conflictingAssertionIds, String kbId) {
        public Contradiction {
            requireNonNull(conflictingAssertionIds);
            requireNonNull(kbId);
        }

        public JsonNode toJson() {
            return Json.node(this);
        }
    }

    class BasicTMS implements Truths {
        private final Events events;
        private final ConcurrentMap<String, Assertion> assertions = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Set<String>> justifications = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Set<String>> dependents = new ConcurrentHashMap<>();
        private final ReadWriteLock lock = new ReentrantReadWriteLock();

        BasicTMS(Events e) {
            this.events = e;
        }

        @Override
        public SupportTicket add(Assertion assertion, Set<String> justificationIds, String source) {
            lock.writeLock().lock();
            try {
                if (assertions.containsKey(assertion.id())) return null;
                var assertionToAdd = assertion.withStatus(true);
                var supportingAssertions = justificationIds.stream().map(assertions::get).filter(Objects::nonNull).toList();
                if (!justificationIds.isEmpty() && supportingAssertions.size() != justificationIds.size()) {
                    error(String.format("TMS Warning: Justification missing for %s. Supporters: %s, Found: %s", assertion.id(), justificationIds, supportingAssertions.stream().map(Assertion::id).toList()));
                    return null;
                }
                if (!justificationIds.isEmpty() && supportingAssertions.stream().noneMatch(Assertion::isActive))
                    assertionToAdd = assertionToAdd.withStatus(false);

                return _add(justificationIds, assertionToAdd);

            } finally {
                lock.writeLock().unlock();
            }
        }

        private SupportTicket _add(Set<String> justificationIds, Assertion assertionToAdd) {
            var aid = assertionToAdd.id();
            assertions.put(aid, assertionToAdd);
            justifications.put(aid, Set.copyOf(justificationIds));

            var finalAssertionToAdd = assertionToAdd.id();
            justificationIds.forEach(supporterId -> dependents.computeIfAbsent(supporterId, k -> ConcurrentHashMap.newKeySet()).add(finalAssertionToAdd));

            if (!assertionToAdd.isActive())
                events.emit(new Cog.AssertionStateEvent(aid, false, assertionToAdd.kb()));
            else
                checkForContradictions(assertionToAdd);

            return new SupportTicket(id(Logic.ID_PREFIX_TICKET), aid);
        }

        @Override
        public void remove(String assertionId, String source) {
            lock.writeLock().lock();
            try {
                _remove(assertionId, source, new HashSet<>());
            } finally {
                lock.writeLock().unlock();
            }
        }

        private void _remove(String assertionId, String source, Set<String> visited) {
            if (!visited.add(assertionId)) return;

            var assertion = assertions.remove(assertionId);
            if (assertion == null) return;
            justifications.remove(assertionId);

            assertion.justificationIds().forEach(supporterId -> ofNullable(dependents.get(supporterId)).ifPresent(deps -> deps.remove(assertionId)));
            var depsToProcess = dependents.remove(assertionId);
            if (depsToProcess != null) {
                depsToProcess.forEach(depId -> updateStatus(depId, visited));
            }

            var kb = assertion.kb();
            if (assertion.isActive()) events.emit(new Cog.RetractedEvent(assertion, kb, source));
            else events.emit(new Cog.AssertionStateEvent(assertion.id(), false, kb));
        }

        private void updateStatus(String assertionId, Set<String> visited) {
            if (!visited.add(assertionId)) return;
            var assertion = assertions.get(assertionId);
            if (assertion == null) return;
            var just = justifications.getOrDefault(assertionId, Set.of());
            var supportActive = just.stream().map(assertions::get).filter(Objects::nonNull).allMatch(Assertion::isActive);
            var newActiveStatus = !just.isEmpty() && supportActive;
            if (newActiveStatus != assertion.isActive()) {
                var updatedAssertion = assertion.withStatus(newActiveStatus);
                assertions.put(assertionId, updatedAssertion);
                events.emit(new Cog.AssertionStateEvent(assertionId, newActiveStatus, assertion.kb()));
                if (newActiveStatus) checkForContradictions(updatedAssertion);
                dependents.getOrDefault(assertionId, Set.of()).forEach(depId -> updateStatus(depId, visited));
            }
        }

        @Override
        public Set<String> getActiveSupport(String assertionId) {
            lock.readLock().lock();
            try {
                return justifications.getOrDefault(assertionId, Set.of()).stream().filter(this::isActive).collect(Collectors.toSet());
            } finally {
                lock.readLock().unlock();
            }
        }

        @Override
        public boolean isActive(String assertionId) {
            lock.readLock().lock();
            try {
                return ofNullable(assertions.get(assertionId)).map(Assertion::isActive).orElse(false);
            } finally {
                lock.readLock().unlock();
            }
        }

        @Override
        public Optional<Assertion> get(String assertionId) {
            lock.readLock().lock();
            try {
                return ofNullable(assertions.get(assertionId));
            } finally {
                lock.readLock().unlock();
            }
        }

        @Override
        public Collection<Assertion> getAllActiveAssertions() {
            lock.readLock().lock();
            try {
                return assertions.values().stream().filter(Assertion::isActive).toList();
            } finally {
                lock.readLock().unlock();
            }
        }

        private void checkForContradictions(Assertion newlyActive) {
            if (!newlyActive.isActive()) return;
            var oppositeForm = newlyActive.negated() ? newlyActive.getEffectiveTerm() : new Term.Lst(Term.Atom.of(Logic.KIF_OP_NOT), newlyActive.kif());
            if (!(oppositeForm instanceof Term.Lst)) return;
            findMatchingAssertion((Term.Lst) oppositeForm, newlyActive.kb(), !newlyActive.negated())
                    .ifPresent(match -> {
                        error(String.format("TMS Contradiction Detected in KB %s: %s and %s", newlyActive.kb(), newlyActive.id(), match.id()));
                        events.emit(new ContradictionDetectedEvent(Set.of(newlyActive.id(), match.id()), newlyActive.kb()));
                    });
        }

        private Optional<Assertion> findMatchingAssertion(Term.Lst formToMatch, String kbId, boolean matchIsNegated) {
            lock.readLock().lock();
            try {
                return assertions.values().stream()
                        .filter(Assertion::isActive)
                        .filter(a -> a.negated() == matchIsNegated && kbId.equals(a.kb()) && formToMatch.equals(a.kif()))
                        .findFirst();
            } finally {
                lock.readLock().unlock();
            }
        }

        @Override
        public void resolveContradiction(Contradiction contradiction, ResolutionStrategy strategy) {
            lock.writeLock().lock();
            try {
                var conflictingIds = contradiction.conflictingAssertionIds();
                error(String.format("TMS Contradiction Resolution (Strategy: %s) for IDs: %s", strategy, conflictingIds));

                switch (strategy) {
                    case LOG_ONLY -> message("Strategy LOG_ONLY: No assertions retracted.");
                    case RETRACT_WEAKEST -> {
                        var activeConflicts = conflictingIds.stream()
                                .map(assertions::get)
                                .filter(Objects::nonNull)
                                .filter(Assertion::isActive)
                                .toList();

                        if (activeConflicts.isEmpty()) {
                            message("Strategy RETRACT_WEAKEST: No active conflicting assertions found.");
                            return;
                        }

                        var minPri = activeConflicts.stream().mapToDouble(Assertion::pri).min().orElseThrow();
                        var weakestAssertions = activeConflicts.stream().filter(a -> Double.compare(a.pri(), minPri) == 0).toList();

                        Set<String> idsToRetract = new HashSet<>();
                        if (weakestAssertions.size() > 1) {
                            var maxTimestamp = weakestAssertions.stream().mapToLong(Assertion::timestamp).max().orElseThrow();
                            weakestAssertions.stream().filter(a -> a.timestamp() == maxTimestamp).map(Assertion::id).forEach(idsToRetract::add);
                            message(String.format("Strategy RETRACT_WEAKEST: Tie in priority (%.3f), retracting %d oldest assertions.", minPri, idsToRetract.size()));
                        } else {
                            idsToRetract.add(weakestAssertions.getFirst().id());
                            message(String.format("Strategy RETRACT_WEAKEST: Retracting weakest assertion with priority %.3f.", minPri));
                        }
                        idsToRetract.forEach(id -> _remove(id, "ContradictionResolution", new HashSet<>()));
                    }
                }
            } finally {
                lock.writeLock().unlock();
            }
        }

        @Override
        public Set<Contradiction> findContradictions() {
            return Set.of();
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ContradictionDetectedEvent(Set<String> contradictoryAssertionIds, String kbId) implements Cog.CogEvent {
        public ContradictionDetectedEvent {
            requireNonNull(contradictoryAssertionIds);
            requireNonNull(kbId);
        }

        @Override
        public String assocNote() {
            return kbId;
        }

        public JsonNode toJson() {
            return Json.node(this);
        }

        @Override
        public String getEventType() {
            return "ContradictionDetectedEvent";
        }
    }
}
