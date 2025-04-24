package dumb.cognote;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dumb.cognote.Logic.AssertionType.UNIVERSAL;
import static java.util.Objects.requireNonNull;

public record Assertion(String id, Term.Lst kif, double pri, long timestamp, @Nullable String sourceNoteId,
                        Set<String> justificationIds, Logic.AssertionType type,
                        boolean isEquality, boolean isOrientedEquality, boolean negated,
                        List<Term.Var> quantifiedVars, int derivationDepth, boolean isActive,
                        String kb) implements Comparable<Assertion> {
    public Assertion {
        requireNonNull(id);
        requireNonNull(kif);
        requireNonNull(type);
        requireNonNull(kb);
        justificationIds = Set.copyOf(requireNonNull(justificationIds));
        quantifiedVars = List.copyOf(requireNonNull(quantifiedVars));
        if (negated != kif.op().filter(Logic.KIF_OP_NOT::equals).isPresent())
            throw new IllegalArgumentException("Negation flag mismatch for KIF: " + kif.toKif());
        if (type == UNIVERSAL && (kif.op().filter(Logic.KIF_OP_FORALL::equals).isEmpty() || quantifiedVars.isEmpty()))
            throw new IllegalArgumentException("Universal assertion must be (forall ...) with quantified vars: " + kif.toKif());
        if (type != UNIVERSAL && !quantifiedVars.isEmpty())
            throw new IllegalArgumentException("Only Universal assertions should have quantified vars: " + kif.toKif());
    }

    private static void collectPredicatesRecursive(Term term, Set<Term.Atom> predicates) {
        switch (term) {
            case Term.Lst list when !list.terms.isEmpty() && list.get(0) instanceof Term.Atom pred -> {
                predicates.add(pred);
                list.terms.stream().skip(1).forEach(sub -> collectPredicatesRecursive(sub, predicates));
            }
            case Term.Lst list -> list.terms.forEach(sub -> collectPredicatesRecursive(sub, predicates));
            default -> {
            }
        }
    }

    @Override
    public int compareTo(Assertion other) {
        var cmp = Boolean.compare(other.isActive, this.isActive);
        if (cmp != 0) return cmp;
        cmp = Double.compare(other.pri, this.pri);
        if (cmp != 0) return cmp;
        cmp = Integer.compare(this.derivationDepth, other.derivationDepth);
        if (cmp != 0) return cmp;
        return Long.compare(other.timestamp, this.timestamp);
    }

    public String toKifString() {
        return kif.toKif();
    }

    Term getEffectiveTerm() {
        return switch (type) {
            case GROUND, SKOLEMIZED -> negated ? kif.get(1) : kif;
            case UNIVERSAL -> kif.get(2);
        };
    }

    Set<Term.Atom> getReferencedPredicates() {
        Set<Term.Atom> p = new HashSet<>();
        collectPredicatesRecursive(getEffectiveTerm(), p);
        return Collections.unmodifiableSet(p);
    }

    Assertion withStatus(boolean newActiveStatus) {
        return new Assertion(id, kif, pri, timestamp, sourceNoteId, justificationIds, type, isEquality, isOrientedEquality, negated, quantifiedVars, derivationDepth, newActiveStatus, kb);
    }

    public record PotentialAssertion(Term.Lst kif, double pri, Set<String> support, String sourceId, boolean isEquality,
                                     boolean isNegated, boolean isOrientedEquality, @Nullable String sourceNoteId,
                                     Logic.AssertionType derivedType, List<Term.Var> quantifiedVars,
                                     int derivationDepth) {
        public PotentialAssertion {
            requireNonNull(kif);
            requireNonNull(sourceId);
            requireNonNull(derivedType);
            support = Set.copyOf(requireNonNull(support));
            quantifiedVars = List.copyOf(requireNonNull(quantifiedVars));
            if (isNegated != kif.op().filter(Logic.KIF_OP_NOT::equals).isPresent())
                throw new IllegalArgumentException("Negation flag mismatch for potential KIF: " + kif.toKif());
            if (derivedType == UNIVERSAL && (kif.op().filter(Logic.KIF_OP_FORALL::equals).isEmpty() || quantifiedVars.isEmpty()))
                throw new IllegalArgumentException("Potential Universal assertion must be (forall ...) with quantified vars: " + kif.toKif());
            if (derivedType != UNIVERSAL && !quantifiedVars.isEmpty())
                throw new IllegalArgumentException("Only potential Universal assertions should have quantified vars: " + kif.toKif());
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof PotentialAssertion pa && kif.equals(pa.kif);
        }

        @Override
        public int hashCode() {
            return kif.hashCode();
        }

    }
}
