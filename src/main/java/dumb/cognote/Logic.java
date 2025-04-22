package dumb.cognote;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dumb.cognote.Cog.*;
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
    public static final String PRED_NOTE_SUMMARY = "noteSummary";
    public static final String PRED_NOTE_CONCEPT = "noteConcept";
    public static final String PRED_NOTE_QUESTION = "noteQuestion";
    private static final String ID_PREFIX_FACT = "fact_";
    private static final String ID_PREFIX_SKOLEM_FUNC = "skf_";
    private static final String ID_PREFIX_SKOLEM_CONST = "skc_";
    private static final String ID_PREFIX_TEMP_ITEM = "temp_";
    private static final String ID_PREFIX_TICKET = "tms_";

    static boolean isTrivial(KifList list) {
        var s = list.size();
        var opOpt = list.op();
        if (s >= 3 && list.get(1).equals(list.get(2)))
            return opOpt.filter(op -> REFLEXIVE_PREDICATES.contains(op) || op.equals(KIF_OP_EQUAL)).isPresent();
        else if (opOpt.filter(KIF_OP_NOT::equals).isPresent() && s == 2 && list.get(1) instanceof KifList inner)
            return inner.size() >= 3 && inner.get(1).equals(inner.get(2)) && inner.op().filter(op -> REFLEXIVE_PREDICATES.contains(op) || op.equals(KIF_OP_EQUAL)).isPresent();
        return false;
    }

    enum RetractionType {BY_ID, BY_NOTE, BY_RULE_FORM}

    enum AssertionType {GROUND, UNIVERSAL, SKOLEMIZED}


    enum ResolutionStrategy {RETRACT_WEAKEST, LOG_ONLY}

    interface Truths {
        SupportTicket addAssertion(Assertion assertion, Set<String> justificationIds, String source);

        void retractAssertion(String assertionId, String source);

        Set<String> getActiveSupport(String assertionId);

        boolean isActive(String assertionId);

        Optional<Assertion> getAssertion(String assertionId);

        Collection<Assertion> getAllActiveAssertions();

        void resolveContradiction(Contradiction contradiction, ResolutionStrategy strategy);

        Set<Contradiction> findContradictions();
    }

    interface Operator {
        String id();

        KifAtom pred();

        CompletableFuture<KifTerm> exe(KifList arguments, ReasonerContext context);
    }

    sealed interface KifTerm permits KifAtom, KifVar, KifList {
        static Set<KifVar> collectSpecVars(KifTerm varsTerm) {
            return switch (varsTerm) {
                case KifVar v -> Set.of(v);
                case KifList l ->
                        l.terms().stream().filter(KifVar.class::isInstance).map(KifVar.class::cast).collect(Collectors.toUnmodifiableSet());
                default -> {
                    System.err.println("Warning: Invalid variable specification in quantifier: " + varsTerm.toKif());
                    yield Set.of();
                }
            };
        }

        String toKif();

        boolean containsVar();

        Set<KifVar> vars();

        int weight();

        default boolean containsSkolemTerm() {
            return switch (this) {
                case KifAtom a -> a.value.startsWith(ID_PREFIX_SKOLEM_CONST);
                case KifList l ->
                        l.op().filter(op -> op.startsWith(ID_PREFIX_SKOLEM_FUNC)).isPresent() || l.terms.stream().anyMatch(KifTerm::containsSkolemTerm);
                case KifVar ignored -> false;
            };
        }
    }

    record Explanation(String details) {
    }

    record SupportTicket(String ticketId, String assertionId) {
    }

    record Contradiction(Set<String> conflictingAssertionIds) {
    }

    static class Skolemizer {
        KifList skolemize(KifList existentialFormula, Map<KifVar, KifTerm> contextBindings) {
            if (!KIF_OP_EXISTS.equals(existentialFormula.op().orElse("")))
                throw new IllegalArgumentException("Input must be an 'exists' formula");
            if (existentialFormula.size() != 3 || !(existentialFormula.get(1) instanceof KifList || existentialFormula.get(1) instanceof KifVar) || !(existentialFormula.get(2) instanceof KifList body))
                throw new IllegalArgumentException("Invalid 'exists' format: " + existentialFormula.toKif());

            var vars = KifTerm.collectSpecVars(existentialFormula.get(1));
            if (vars.isEmpty()) return body;

            Set<KifVar> freeVars = new HashSet<>(body.vars());
            freeVars.removeAll(vars);
            var skolemArgs = freeVars.stream().map(fv -> Unifier.substFully(contextBindings.getOrDefault(fv, fv), contextBindings)).sorted(Comparator.comparing(KifTerm::toKif)).toList();

            Map<KifVar, KifTerm> skolemMap = new HashMap<>();
            for (var exVar : vars) {
                var skolemNameBase = ID_PREFIX_SKOLEM_CONST + exVar.name().substring(1) + "_" + idCounter.incrementAndGet();
                var skolemTerm = skolemArgs.isEmpty()
                        ? KifAtom.of(skolemNameBase)
                        : new KifList(Stream.concat(Stream.of(KifAtom.of(ID_PREFIX_SKOLEM_FUNC + exVar.name().substring(1) + "_" + idCounter.incrementAndGet())), skolemArgs.stream()).toList());
                skolemMap.put(exVar, skolemTerm);
            }
            var substituted = Unifier.subst(body, skolemMap);
            return (substituted instanceof KifList sl) ? sl : new KifList(substituted);
        }
    }

    record KifAtom(String value) implements KifTerm {
        private static final Pattern SAFE_ATOM_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-+*/.<>=:]+$");
        private static final Map<String, KifAtom> internCache = new ConcurrentHashMap<>(1024);

        KifAtom {
            requireNonNull(value);
        }

        public static KifAtom of(String value) {
            return internCache.computeIfAbsent(value, KifAtom::new);
        }

        @Override
        public String toKif() {
            var needsQuotes = value.isEmpty() || !SAFE_ATOM_PATTERN.matcher(value).matches() || value.chars().anyMatch(c -> Character.isWhitespace(c) || "()\";?".indexOf(c) != -1);
            return needsQuotes ? '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"' : value;
        }

        @Override
        public boolean containsVar() {
            return false;
        }

        @Override
        public Set<KifVar> vars() {
            return Set.of();
        }

        @Override
        public int weight() {
            return 1;
        }

        @Override
        public String toString() {
            return "KifAtom[" + value + ']';
        }
    }

    record KifVar(String name) implements KifTerm {
        private static final Map<String, KifVar> internCache = new ConcurrentHashMap<>(256);

        KifVar {
            requireNonNull(name);
            if (!name.startsWith("?") || name.length() < 2)
                throw new IllegalArgumentException("Variable name must start with '?' and have length > 1: " + name);
        }

        public static KifVar of(String name) {
            return internCache.computeIfAbsent(name, KifVar::new);
        }

        @Override
        public String toKif() {
            return name;
        }

        @Override
        public boolean containsVar() {
            return true;
        }

        @Override
        public Set<KifVar> vars() {
            return Set.of(this);
        }

        @Override
        public int weight() {
            return 1;
        }

        @Override
        public String toString() {
            return "KifVar[" + name + ']';
        }
    }

    static final class KifList implements KifTerm {
        final List<KifTerm> terms;
        private volatile int hashCodeCache;
        private volatile boolean hashCodeCalculated = false;
        private volatile String kifStringCache;
        private volatile int weightCache = -1;
        private volatile Set<KifVar> variablesCache;
        private volatile Boolean containsVariableCache;
        private volatile Boolean containsSkolemCache;

        KifList(List<KifTerm> terms) {
            this.terms = List.copyOf(requireNonNull(terms));
        }

        KifList(KifTerm... terms) {
            this(List.of(terms));
        }

        public List<KifTerm> terms() {
            return terms;
        }

        KifTerm get(int index) {
            return terms.get(index);
        }

        int size() {
            return terms.size();
        }

        Optional<String> op() {
            return terms.isEmpty() || !(terms.getFirst() instanceof KifAtom(var v)) ? Optional.empty() : Optional.of(v);
        }

        @Override
        public String toKif() {
            if (kifStringCache == null)
                kifStringCache = terms.stream().map(KifTerm::toKif).collect(Collectors.joining(" ", "(", ")"));
            return kifStringCache;
        }

        @Override
        public boolean containsVar() {
            if (containsVariableCache == null) containsVariableCache = terms.stream().anyMatch(KifTerm::containsVar);
            return containsVariableCache;
        }

        @Override
        public boolean containsSkolemTerm() {
            if (containsSkolemCache == null) containsSkolemCache = KifTerm.super.containsSkolemTerm();
            return containsSkolemCache;
        }

        @Override
        public Set<KifVar> vars() {
            if (variablesCache == null)
                variablesCache = terms.stream().flatMap(t -> t.vars().stream()).collect(Collectors.toUnmodifiableSet());
            return variablesCache;
        }

        @Override
        public int weight() {
            if (weightCache == -1) weightCache = 1 + terms.stream().mapToInt(KifTerm::weight).sum();
            return weightCache;
        }

        @Override
        public boolean equals(Object o) {
            return this == o || (o instanceof KifList that && this.hashCode() == that.hashCode() && terms.equals(that.terms));
        }

        @Override
        public int hashCode() {
            if (!hashCodeCalculated) {
                hashCodeCache = terms.hashCode();
                hashCodeCalculated = true;
            }
            return hashCodeCache;
        }

        @Override
        public String toString() {
            return "KifList" + terms;
        }
    }

    record Assertion(String id, KifList kif, double pri, long timestamp, @Nullable String sourceNoteId,
                     Set<String> justificationIds, AssertionType type,
                     boolean isEquality, boolean isOrientedEquality, boolean negated,
                     List<KifVar> quantifiedVars, int derivationDepth, boolean isActive,
                     String kb) implements Comparable<Assertion> {
        Assertion {
            requireNonNull(id);
            requireNonNull(kif);
            requireNonNull(type);
            requireNonNull(kb);
            justificationIds = Set.copyOf(requireNonNull(justificationIds));
            quantifiedVars = List.copyOf(requireNonNull(quantifiedVars));
            if (negated != kif.op().filter(KIF_OP_NOT::equals).isPresent())
                throw new IllegalArgumentException("Negation flag mismatch for KIF: " + kif.toKif());
            if (type == AssertionType.UNIVERSAL && (kif.op().filter(KIF_OP_FORALL::equals).isEmpty() || quantifiedVars.isEmpty()))
                throw new IllegalArgumentException("Universal assertion must be (forall ...) with quantified vars: " + kif.toKif());
            if (type != AssertionType.UNIVERSAL && !quantifiedVars.isEmpty())
                throw new IllegalArgumentException("Only Universal assertions should have quantified vars: " + kif.toKif());
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

        String toKifString() {
            return kif.toKif();
        }

        KifTerm getEffectiveTerm() {
            return switch (type) {
                case GROUND, SKOLEMIZED -> negated ? kif.get(1) : kif;
                case UNIVERSAL -> kif.get(2);
            };
        }

        Set<KifAtom> getReferencedPredicates() {
            Set<KifAtom> p = new HashSet<>();
            collectPredicatesRecursive(getEffectiveTerm(), p);
            return Collections.unmodifiableSet(p);
        }

        private void collectPredicatesRecursive(KifTerm term, Set<KifAtom> predicates) {
            switch (term) {
                case KifList list when !list.terms().isEmpty() && list.get(0) instanceof KifAtom pred -> {
                    predicates.add(pred);
                    list.terms().stream().skip(1).forEach(sub -> collectPredicatesRecursive(sub, predicates));
                }
                case KifList list -> list.terms().forEach(sub -> collectPredicatesRecursive(sub, predicates));
                default -> {
                }
            }
        }

        Assertion withStatus(boolean newActiveStatus) {
            return new Assertion(id, kif, pri, timestamp, sourceNoteId, justificationIds, type, isEquality, isOrientedEquality, negated, quantifiedVars, derivationDepth, newActiveStatus, kb);
        }
    }

    record Rule(String id, KifList form, KifTerm antecedent, KifTerm consequent, double pri,
                List<KifTerm> antecedents) {
        Rule {
            requireNonNull(id);
            requireNonNull(form);
            requireNonNull(antecedent);
            requireNonNull(consequent);
            antecedents = List.copyOf(requireNonNull(antecedents));
        }

        static Rule parseRule(String id, KifList ruleForm, double pri) throws IllegalArgumentException {
            if (!(ruleForm.op().filter(op -> op.equals(KIF_OP_IMPLIES) || op.equals(KIF_OP_EQUIV)).isPresent() && ruleForm.size() == 3))
                throw new IllegalArgumentException("Rule form must be (=> ant con) or (<=> ant con): " + ruleForm.toKif());
            var antTerm = ruleForm.get(1);
            var conTerm = ruleForm.get(2);
            var parsedAntecedents = switch (antTerm) {
                case KifList list when list.op().filter(KIF_OP_AND::equals).isPresent() ->
                        list.terms().stream().skip(1).map(Rule::validateAntecedentClause).toList();
                case KifList list -> List.of(validateAntecedentClause(list));
                case KifTerm t when t.equals(KifAtom.of("true")) -> List.<KifTerm>of();
                default ->
                        throw new IllegalArgumentException("Antecedent must be a KIF list, (not list), (and ...), or true: " + antTerm.toKif());
            };
            validateUnboundVariables(ruleForm, antTerm, conTerm);
            return new Rule(id, ruleForm, antTerm, conTerm, pri, parsedAntecedents);
        }

        private static KifTerm validateAntecedentClause(KifTerm term) {
            return switch (term) {
                case KifList list -> {
                    if (list.op().filter(KIF_OP_NOT::equals).isPresent() && (list.size() != 2 || !(list.get(1) instanceof KifList)))
                        throw new IllegalArgumentException("Argument of 'not' in rule antecedent must be a list: " + list.toKif());
                    yield list;
                }
                default ->
                        throw new IllegalArgumentException("Elements of rule antecedent must be lists or (not list): " + term.toKif());
            };
        }

        private static void validateUnboundVariables(KifList ruleForm, KifTerm antecedent, KifTerm consequent) {
            var unbound = new HashSet<KifVar>(consequent.vars());
            unbound.removeAll(antecedent.vars());
            unbound.removeAll(getQuantifierBoundVariables(consequent));
            if (!unbound.isEmpty() && ruleForm.op().filter(KIF_OP_IMPLIES::equals).isPresent())
                System.err.println("Warning: Rule consequent has variables not bound by antecedent or local quantifier: " + unbound.stream().map(KifVar::name).collect(Collectors.joining(", ")) + " in " + ruleForm.toKif());
        }

        private static Set<KifVar> getQuantifierBoundVariables(KifTerm term) {
            Set<KifVar> bound = new HashSet<>();
            collectQuantifierBoundVariablesRecursive(term, bound);
            return Collections.unmodifiableSet(bound);
        }

        private static void collectQuantifierBoundVariablesRecursive(KifTerm term, Set<KifVar> boundVars) {
            switch (term) {
                case KifList list when list.size() == 3 && list.op().filter(op -> op.equals(KIF_OP_EXISTS) || op.equals(KIF_OP_FORALL)).isPresent() -> {
                    boundVars.addAll(KifTerm.collectSpecVars(list.get(1)));
                    collectQuantifierBoundVariablesRecursive(list.get(2), boundVars);
                }
                case KifList list ->
                        list.terms().forEach(sub -> collectQuantifierBoundVariablesRecursive(sub, boundVars));
                default -> {
                }
            }
        }

        @Override
        public boolean equals(Object o) {
            return this == o || (o instanceof Rule r && form.equals(r.form));
        }

        @Override
        public int hashCode() {
            return form.hashCode();
        }
    }

    record PotentialAssertion(KifList kif, double pri, Set<String> support, String sourceId, boolean isEquality,
                              boolean isNegated, boolean isOrientedEquality, @Nullable String sourceNoteId,
                              AssertionType derivedType, List<KifVar> quantifiedVars, int derivationDepth) {
        PotentialAssertion {
            requireNonNull(kif);
            requireNonNull(sourceId);
            requireNonNull(derivedType);
            support = Set.copyOf(requireNonNull(support));
            quantifiedVars = List.copyOf(requireNonNull(quantifiedVars));
            if (isNegated != kif.op().filter(KIF_OP_NOT::equals).isPresent())
                throw new IllegalArgumentException("Negation flag mismatch for potential KIF: " + kif.toKif());
            if (derivedType == AssertionType.UNIVERSAL && (kif.op().filter(KIF_OP_FORALL::equals).isEmpty() || quantifiedVars.isEmpty()))
                throw new IllegalArgumentException("Potential Universal assertion must be (forall ...) with quantified vars: " + kif.toKif());
            if (derivedType != AssertionType.UNIVERSAL && !quantifiedVars.isEmpty())
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

        KifTerm getEffectiveTerm() {
            return switch (derivedType) {
                case GROUND, SKOLEMIZED -> isNegated ? kif.get(1) : kif;
                case UNIVERSAL -> kif.get(2);
            };
        }
    }

    static class PathNode {
        static final Class<KifVar> VAR_MARKER = KifVar.class;
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

        void add(Assertion assertion) {
            if (tms.isActive(assertion.id)) addPathsRecursive(assertion.kif, assertion.id, root);
        }

        void remove(Assertion assertion) {
            removePathsRecursive(assertion.kif, assertion.id, root);
        }

        void clear() {
            root.children.clear();
            root.assertionIdsHere.clear();
        }

        Stream<Assertion> findUnifiableAssertions(KifTerm queryTerm) {
            return findCandidates(queryTerm, this::findUnifiableRecursive).stream().map(tms::getAssertion).flatMap(Optional::stream).filter(Assertion::isActive);
        }

        Stream<Assertion> findInstancesOf(KifTerm queryPattern) {
            var neg = (queryPattern instanceof KifList ql && ql.op().filter(KIF_OP_NOT::equals).isPresent());
            return findCandidates(queryPattern, this::findInstancesRecursive).stream().map(tms::getAssertion).flatMap(Optional::stream).filter(Assertion::isActive).filter(a -> a.negated == neg).filter(a -> Unifier.match(queryPattern, a.kif, Map.of()) != null);
        }

        Stream<Assertion> findGeneralizationsOf(KifTerm queryTerm) {
            return findCandidates(queryTerm, this::findGeneralizationsRecursive).stream().map(tms::getAssertion).flatMap(Optional::stream).filter(Assertion::isActive);
        }

        private Set<String> findCandidates(KifTerm query, TriConsumer<KifTerm, PathNode, Set<String>> searchFunc) {
            Set<String> candidates = ConcurrentHashMap.newKeySet();
            searchFunc.accept(query, root, candidates);
            return Set.copyOf(candidates);
        }

        private void addPathsRecursive(KifTerm term, String assertionId, PathNode currentNode) {
            if (currentNode == null) return;
            currentNode.assertionIdsHere.add(assertionId);
            var key = getIndexKey(term);
            var termNode = currentNode.children.computeIfAbsent(key, _ -> new PathNode());
            termNode.assertionIdsHere.add(assertionId);
            if (term instanceof KifList list)
                list.terms().forEach(subTerm -> addPathsRecursive(subTerm, assertionId, termNode));
        }

        private boolean removePathsRecursive(KifTerm term, String assertionId, PathNode currentNode) {
            if (currentNode == null) return false;
            currentNode.assertionIdsHere.remove(assertionId);
            var key = getIndexKey(term);
            var termNode = currentNode.children.get(key);
            if (termNode != null) {
                termNode.assertionIdsHere.remove(assertionId);
                var canPruneChild = true;
                if (term instanceof KifList list)
                    canPruneChild = list.terms().stream().allMatch(subTerm -> removePathsRecursive(subTerm, assertionId, termNode));
                if (canPruneChild && termNode.assertionIdsHere.isEmpty() && termNode.children.isEmpty())
                    currentNode.children.remove(key, termNode);
            }
            return currentNode.assertionIdsHere.isEmpty() && currentNode.children.isEmpty();
        }

        private Object getIndexKey(KifTerm term) {
            return switch (term) {
                case KifAtom a -> a.value();
                case KifVar _ -> PathNode.VAR_MARKER;
                case KifList l -> l.op().map(op -> (Object) op).orElse(PathNode.LIST_MARKER);
            };
        }

        private void collectAllAssertionIds(PathNode node, Set<String> ids) {
            if (node == null) return;
            ids.addAll(node.assertionIdsHere);
            node.children.values().forEach(child -> collectAllAssertionIds(child, ids));
        }

        private void findUnifiableRecursive(KifTerm queryTerm, PathNode indexNode, Set<String> candidates) {
            if (indexNode == null) return;
            ofNullable(indexNode.children.get(PathNode.VAR_MARKER)).ifPresent(varNode -> collectAllAssertionIds(varNode, candidates));
            if (queryTerm instanceof KifList)
                ofNullable(indexNode.children.get(PathNode.LIST_MARKER)).ifPresent(listNode -> collectAllAssertionIds(listNode, candidates));
            var specificNode = indexNode.children.get(getIndexKey(queryTerm));
            if (specificNode != null) {
                candidates.addAll(specificNode.assertionIdsHere);
                if (queryTerm instanceof KifList) collectAllAssertionIds(specificNode, candidates);
            }
            if (queryTerm instanceof KifVar)
                indexNode.children.values().forEach(childNode -> collectAllAssertionIds(childNode, candidates));
        }

        private void findInstancesRecursive(KifTerm queryPattern, PathNode indexNode, Set<String> candidates) {
            if (indexNode == null) return;
            if (queryPattern instanceof KifVar) {
                collectAllAssertionIds(indexNode, candidates);
                return;
            }
            var specificNode = indexNode.children.get(getIndexKey(queryPattern));
            if (specificNode != null) {
                candidates.addAll(specificNode.assertionIdsHere);
                if (queryPattern instanceof KifList listPattern && !listPattern.terms().isEmpty()) {
                    collectAllAssertionIds(specificNode, candidates);
                }
            }
        }

        private void findGeneralizationsRecursive(KifTerm queryTerm, PathNode indexNode, Set<String> candidates) {
            if (indexNode == null) return;
            ofNullable(indexNode.children.get(PathNode.VAR_MARKER)).ifPresent(varNode -> collectAllAssertionIds(varNode, candidates));
            if (queryTerm instanceof KifList)
                ofNullable(indexNode.children.get(PathNode.LIST_MARKER)).ifPresent(listNode -> candidates.addAll(listNode.assertionIdsHere));
            ofNullable(indexNode.children.get(getIndexKey(queryTerm))).ifPresent(nextNode -> {
                candidates.addAll(nextNode.assertionIdsHere);
                if (queryTerm instanceof KifList queryList && !queryList.terms().isEmpty()) {
                    queryList.terms().forEach(subTerm -> findGeneralizationsRecursive(subTerm, nextNode, candidates));
                }
            });
        }

        @FunctionalInterface
        private interface TriConsumer<T, U, V> {
            void accept(T t, U u, V v);
        }
    }

    static class Knowledge {
        final String id;
        final int capacity;
        final Events events;
        final Truths truth;
        final PathIndex paths;
        final ConcurrentMap<KifAtom, Set<String>> universalIndex = new ConcurrentHashMap<>();
        final PriorityBlockingQueue<String> groundEvictionQueue;
        private final ReadWriteLock lock = new ReentrantReadWriteLock();

        Knowledge(String kbId, int capacity, Events events, Truths truth) {
            this.id = requireNonNull(kbId);
            this.capacity = capacity;
            this.events = requireNonNull(events);
            this.truth = requireNonNull(truth);
            this.paths = new PathIndex(truth);
            this.groundEvictionQueue = new PriorityBlockingQueue<>(1024,
                    Comparator.<String, Double>comparing(id -> truth.getAssertion(id).map(Assertion::pri).orElse(Double.MAX_VALUE))
                            .thenComparing(id -> truth.getAssertion(id).map(Assertion::timestamp).orElse(Long.MAX_VALUE)));
        }

        int getAssertionCount() {
            return (int) truth.getAllActiveAssertions().stream().filter(a -> a.kb.equals(id)).count();
        }

        List<String> getAllAssertionIds() {
            return truth.getAllActiveAssertions().stream().filter(a -> a.kb.equals(id)).map(Assertion::id).toList();
        }

        Optional<Assertion> getAssertion(String id) {
            return truth.getAssertion(id).filter(a -> a.kb.equals(this.id));
        }

        List<Assertion> getAllAssertions() {
            return truth.getAllActiveAssertions().stream().filter(a -> a.kb.equals(id)).toList();
        }

        @Nullable Assertion commit(PotentialAssertion pa, String source) {
            if (pa.kif instanceof KifList kl && isTrivial(kl)) return null;
            lock.writeLock().lock();
            try {
                var finalType = (pa.derivedType == AssertionType.GROUND && pa.kif.containsSkolemTerm()) ? AssertionType.SKOLEMIZED : pa.derivedType;

                var existingMatch = findExactMatchInternal(pa.kif);
                if (existingMatch.isPresent() && truth.isActive(existingMatch.get().id)) return null;
                if (isSubsumedInternal(pa.kif, pa.isNegated())) return null;

                enforceKbCapacityInternal(source);
                if (getAssertionCount() >= capacity) {
                    System.err.printf("Warning: KB '%s' full (%d/%d) after eviction attempt. Cannot add: %s%n", id, getAssertionCount(), capacity, pa.kif.toKif());
                    return null;
                }

                var newId = generateId(ID_PREFIX_FACT + finalType.name().toLowerCase() + "_");
                var newAssertion = new Assertion(newId, pa.kif, pa.pri, System.currentTimeMillis(), pa.sourceNoteId(), pa.support(), finalType, pa.isEquality(), pa.isOrientedEquality(), pa.isNegated(), pa.quantifiedVars(), pa.derivationDepth(), true, id);

                var ticket = truth.addAssertion(newAssertion, pa.support(), source);
                if (ticket == null) return null;

                var addedAssertion = truth.getAssertion(newId).orElse(null);
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
                events.emit(new AssertionAddedEvent(addedAssertion, id));
                return addedAssertion;
            } finally {
                lock.writeLock().unlock();
            }
        }

        void retractAssertion(String id, String source) {
            lock.writeLock().lock();
            try {
                truth.retractAssertion(id, source);
            } finally {
                lock.writeLock().unlock();
            }
        }

        void clear(String source) {
            lock.writeLock().lock();
            try {
                new HashSet<>(getAllAssertionIds()).forEach(id -> truth.retractAssertion(id, source));
                paths.clear();
                universalIndex.clear();
                groundEvictionQueue.clear();
            } finally {
                lock.writeLock().unlock();
            }
        }

        Stream<Assertion> findUnifiableAssertions(KifTerm queryTerm) {
            return paths.findUnifiableAssertions(queryTerm);
        }

        Stream<Assertion> findInstancesOf(KifTerm queryPattern) {
            return paths.findInstancesOf(queryPattern);
        }

        List<Assertion> findRelevantUniversalAssertions(KifAtom predicate) {
            return universalIndex.getOrDefault(predicate, Set.of()).stream().map(truth::getAssertion).flatMap(Optional::stream).filter(Assertion::isActive).filter(a -> a.kb.equals(id)).toList();
        }

        private boolean isSubsumedInternal(KifTerm term, boolean isNegated) {
            return paths.findGeneralizationsOf(term)
                    .filter(a -> a.type == AssertionType.GROUND || a.type == AssertionType.SKOLEMIZED)
                    .anyMatch(candidate -> candidate.negated == isNegated && Unifier.match(candidate.getEffectiveTerm(), term, Map.of()) != null);
        }

        private Optional<Assertion> findExactMatchInternal(KifList kif) {
            return paths.findInstancesOf(kif).filter(a -> kif.equals(a.kif)).findFirst();
        }

        private void enforceKbCapacityInternal(String source) {
            while (getAssertionCount() >= capacity && !groundEvictionQueue.isEmpty()) {
                ofNullable(groundEvictionQueue.poll())
                        .flatMap(truth::getAssertion)
                        .filter(a -> a.kb.equals(id) && (a.type == AssertionType.GROUND || a.type == AssertionType.SKOLEMIZED))
                        .ifPresent(toEvict -> {
                            truth.retractAssertion(toEvict.id, source + "-evict");
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

        void handleExternalRetraction(Assertion a) {
            lock.writeLock().lock();
            try {
                switch (a.type) {
                    case GROUND, SKOLEMIZED -> {
                        paths.remove(a);
                        groundEvictionQueue.remove(a.id);
                    }
                    case UNIVERSAL ->
                            a.getReferencedPredicates().forEach(pred -> universalIndex.computeIfPresent(pred, (_, ids) -> {
                                ids.remove(a.id);
                                return ids.isEmpty() ? null : ids;
                            }));
                }
            } finally {
                lock.writeLock().unlock();
            }
        }

        void handleExternalStatusChange(Assertion a) {
            lock.writeLock().lock();
            try {
                if (a.isActive()) {
                    switch (a.type) {
                        case GROUND, SKOLEMIZED -> paths.add(a);
                        case UNIVERSAL ->
                                a.getReferencedPredicates().forEach(pred -> universalIndex.computeIfAbsent(pred, _ -> ConcurrentHashMap.newKeySet()).add(a.id));
                    }
                } else handleExternalRetraction(a);
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    static class Cognition {
        final Cog cog;
        private final ConcurrentMap<String, Knowledge> noteKbs = new ConcurrentHashMap<>();
        private final Knowledge globalKb;
        private final Set<Rule> rules = ConcurrentHashMap.newKeySet();
        private final Events events;
        private final Truths tms;
        private final Skolemizer skolemizer;
        private final Operators operators;

        Cognition(int globalKbCapacity, Events events, Truths tms, Skolemizer skolemizer, Operators operators, Cog cog) {
            this.cog = cog;
            this.events = events;
            this.tms = tms;
            this.skolemizer = skolemizer;
            this.operators = operators;
            this.globalKb = new Knowledge(GLOBAL_KB_NOTE_ID, globalKbCapacity, events, tms);
        }

        public Knowledge kb(@Nullable String noteId) {
            return (noteId == null || GLOBAL_KB_NOTE_ID.equals(noteId)) ? globalKb : noteKbs.computeIfAbsent(noteId, id -> new Knowledge(id, globalKb.capacity, events, tms));
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
            return (int) tms.getAllActiveAssertions().stream().filter(a -> a.kb.equals(GLOBAL_KB_NOTE_ID) || noteKbs.containsKey(a.kb)).count();
        }

        public int kbTotalCapacity() {
            return globalKb.capacity + noteKbs.size() * globalKb.capacity;
        }

        public Truths truth() {
            return tms;
        }

        public Skolemizer skolemizer() {
            return skolemizer;
        }

        public Operators operators() {
            return operators;
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

        public boolean removeRule(KifList ruleForm) {
            return rules.stream().filter(r -> r.form.equals(ruleForm)).findFirst().map(this::removeRule).orElse(false);
        }

        public void removeNoteKb(String noteId, String source) {
            ofNullable(noteKbs.remove(noteId)).ifPresent(kb -> kb.clear(source));
        }

        public void clearAll() {
            globalKb.clear("clearAll");
            noteKbs.values().forEach(kb -> kb.clear("clearAll"));
            noteKbs.clear();
            rules.clear();
        }

        public Optional<Assertion> findAssertionByIdAcrossKbs(String assertionId) {
            return tms.getAssertion(assertionId);
        }

        @Nullable
        public String findCommonSourceNodeId(Set<String> supportIds) {
            if (supportIds == null || supportIds.isEmpty()) return null;
            String commonId = null;
            var firstFound = false;
            Set<String> visited = new HashSet<>();
            Queue<String> toCheck = new LinkedList<>(supportIds);
            while (!toCheck.isEmpty()) {
                var currentId = toCheck.poll();
                if (currentId == null || !visited.add(currentId)) continue;
                var assertionOpt = findAssertionByIdAcrossKbs(currentId);
                if (assertionOpt.isPresent()) {
                    var assertion = assertionOpt.get();
                    if (assertion.sourceNoteId() != null) {
                        if (!firstFound) {
                            commonId = assertion.sourceNoteId();
                            firstFound = true;
                        } else if (!commonId.equals(assertion.sourceNoteId())) return null;
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

        public KifList performSkolemization(KifList body, Collection<KifVar> existentialVars, Map<KifVar, KifTerm> contextBindings) {
            return skolemizer.skolemize(new KifList(KifAtom.of(KIF_OP_EXISTS), new KifList(new ArrayList<>(existentialVars)), body), contextBindings);
        }

        public KifList simplifyLogicalTerm(KifList term) {
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

        private KifList simplifyLogicalTermOnce(KifList term) {
            if (term.op().filter(KIF_OP_NOT::equals).isPresent() && term.size() == 2 && term.get(1) instanceof KifList nl && nl.op().filter(KIF_OP_NOT::equals).isPresent() && nl.size() == 2 && nl.get(1) instanceof KifList inner)
                return simplifyLogicalTermOnce(inner);
            var changed = new boolean[]{false};
            var newTerms = term.terms().stream().map(subTerm -> {
                var simplifiedSub = (subTerm instanceof KifList sl) ? simplifyLogicalTermOnce(sl) : subTerm;
                if (!simplifiedSub.equals(subTerm)) changed[0] = true;
                return simplifiedSub;
            }).toList();
            return changed[0] ? new KifList(newTerms) : term;
        }

        @Nullable
        public Assertion tryCommit(PotentialAssertion pa, String source) {
            return kb(pa.sourceNoteId()).commit(pa, source);
        }
    }

    static class BasicTMS implements Truths {
        private final Events events;
        private final ConcurrentMap<String, Assertion> assertions = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Set<String>> justifications = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Set<String>> dependents = new ConcurrentHashMap<>();
        private final ReadWriteLock lock = new ReentrantReadWriteLock();

        BasicTMS(Events e) {
            this.events = e;
        }

        @Override
        public SupportTicket addAssertion(Assertion assertion, Set<String> justificationIds, String source) {
            lock.writeLock().lock();
            try {
                if (assertions.containsKey(assertion.id)) return null;
                var assertionToAdd = assertion.withStatus(true);
                var supportingAssertions = justificationIds.stream().map(assertions::get).filter(Objects::nonNull).toList();
                if (!justificationIds.isEmpty() && supportingAssertions.size() != justificationIds.size()) {
                    System.err.printf("TMS Warning: Justification missing for %s. Supporters: %s, Found: %s%n", assertion.id, justificationIds, supportingAssertions.stream().map(Assertion::id).toList());
                    return null;
                }
                if (!justificationIds.isEmpty() && supportingAssertions.stream().noneMatch(Assertion::isActive))
                    assertionToAdd = assertionToAdd.withStatus(false);

                assertions.put(assertionToAdd.id, assertionToAdd);
                justifications.put(assertionToAdd.id, Set.copyOf(justificationIds));
                var finalAssertionToAdd = assertionToAdd;
                justificationIds.forEach(supporterId -> dependents.computeIfAbsent(supporterId, k -> ConcurrentHashMap.newKeySet()).add(finalAssertionToAdd.id));

                if (!assertionToAdd.isActive())
                    events.emit(new AssertionStatusChangedEvent(assertionToAdd.id, false, assertionToAdd.kb));
                else checkForContradictions(assertionToAdd);
                return new SupportTicket(generateId(ID_PREFIX_TICKET), assertionToAdd.id);
            } finally {
                lock.writeLock().unlock();
            }
        }

        @Override
        public void retractAssertion(String assertionId, String source) {
            lock.writeLock().lock();
            try {
                retractInternal(assertionId, source, new HashSet<>());
            } finally {
                lock.writeLock().unlock();
            }
        }

        private void retractInternal(String assertionId, String source, Set<String> visited) {
            if (!visited.add(assertionId)) return;
            var assertion = assertions.remove(assertionId);
            if (assertion == null) return;
            justifications.remove(assertionId);
            assertion.justificationIds().forEach(supporterId -> ofNullable(dependents.get(supporterId)).ifPresent(deps -> deps.remove(assertionId)));
            var depsToProcess = new HashSet<>(dependents.remove(assertionId));
            if (assertion.isActive()) events.emit(new AssertionRetractedEvent(assertion, assertion.kb, source));
            else events.emit(new AssertionStatusChangedEvent(assertion.id, false, assertion.kb));
            depsToProcess.forEach(depId -> updateStatus(depId, visited));
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
                events.emit(new AssertionStatusChangedEvent(assertionId, newActiveStatus, assertion.kb));
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
        public Optional<Assertion> getAssertion(String assertionId) {
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
            var oppositeForm = newlyActive.negated ? newlyActive.getEffectiveTerm() : new KifList(KifAtom.of(KIF_OP_NOT), newlyActive.kif);
            if (!(oppositeForm instanceof KifList)) return;
            findMatchingAssertion((KifList) oppositeForm, newlyActive.kb, !newlyActive.negated)
                    .ifPresent(match -> {
                        System.err.printf("TMS Contradiction Detected in KB %s: %s and %s%n", newlyActive.kb, newlyActive.id, match.id);
                        events.emit(new ContradictionDetectedEvent(Set.of(newlyActive.id, match.id), newlyActive.kb));
                    });
        }

        private Optional<Assertion> findMatchingAssertion(KifList formToMatch, String kbId, boolean matchIsNegated) {
            lock.readLock().lock();
            try {
                return assertions.values().stream()
                        .filter(Assertion::isActive)
                        .filter(a -> a.negated == matchIsNegated && a.kb.equals(kbId) && a.kif.equals(formToMatch))
                        .findFirst();
            } finally {
                lock.readLock().unlock();
            }
        }

        @Override
        public void resolveContradiction(Contradiction contradiction, ResolutionStrategy strategy) {
            System.err.println("Contradiction resolution not implemented. Strategy: " + strategy + ", Conflicting: " + contradiction.conflictingAssertionIds());
        }

        @Override
        public Set<Contradiction> findContradictions() {
            return Set.of();
        }
    }

    static class Unifier {
        private static final int MAX_SUBST_DEPTH = 50;

        @Nullable
        static Map<KifVar, KifTerm> unify(KifTerm x, KifTerm y, Map<KifVar, KifTerm> bindings) {
            return unifyRecursive(x, y, bindings, 0);
        }

        @Nullable
        static Map<KifVar, KifTerm> match(KifTerm pattern, KifTerm term, Map<KifVar, KifTerm> bindings) {
            return matchRecursive(pattern, term, bindings, 0);
        }

        static KifTerm subst(KifTerm term, Map<KifVar, KifTerm> bindings) {
            return substRecursive(term, bindings, 0, false);
        }

        static KifTerm substFully(KifTerm term, Map<KifVar, KifTerm> bindings) {
            return substRecursive(term, bindings, 0, true);
        }

        static Optional<KifTerm> rewrite(KifTerm target, KifTerm lhsPattern, KifTerm rhsTemplate) {
            return rewriteRecursive(target, lhsPattern, rhsTemplate, 0);
        }

        @Nullable
        private static Map<KifVar, KifTerm> unifyRecursive(KifTerm x, KifTerm y, Map<KifVar, KifTerm> bindings, int depth) {
            if (bindings == null || depth > MAX_SUBST_DEPTH) return null;
            var xSubst = substRecursive(x, bindings, depth + 1, true);
            var ySubst = substRecursive(y, bindings, depth + 1, true);
            if (xSubst.equals(ySubst)) return bindings;
            if (xSubst instanceof KifVar varX) return bindVariable(varX, ySubst, bindings, true, depth);
            if (ySubst instanceof KifVar varY) return bindVariable(varY, xSubst, bindings, true, depth);
            if (xSubst instanceof KifList lx && ySubst instanceof KifList ly && lx.size() == ly.size()) {
                var current = bindings;
                for (var i = 0; i < lx.size(); i++) {
                    current = unifyRecursive(lx.get(i), ly.get(i), current, depth + 1);
                    if (current == null) return null;
                }
                return current;
            }
            return null;
        }

        @Nullable
        private static Map<KifVar, KifTerm> matchRecursive(KifTerm pattern, KifTerm term, Map<KifVar, KifTerm> bindings, int depth) {
            if (bindings == null || depth > MAX_SUBST_DEPTH) return null;
            var patternSubst = substRecursive(pattern, bindings, depth + 1, true);
            if (patternSubst instanceof KifVar varP) return bindVariable(varP, term, bindings, false, depth);
            if (patternSubst.equals(term)) return bindings;
            if (patternSubst instanceof KifList lp && term instanceof KifList lt && lp.size() == lt.size()) {
                var current = bindings;
                for (var i = 0; i < lp.size(); i++) {
                    current = matchRecursive(lp.get(i), lt.get(i), current, depth + 1);
                    if (current == null) return null;
                }
                return current;
            }
            return null;
        }

        private static KifTerm substRecursive(KifTerm term, Map<KifVar, KifTerm> bindings, int depth, boolean fully) {
            if (bindings.isEmpty() || depth > MAX_SUBST_DEPTH || !term.containsVar()) return term;
            return switch (term) {
                case KifAtom atom -> atom;
                case KifVar var -> {
                    var binding = bindings.get(var);
                    yield (binding != null && fully) ? substRecursive(binding, bindings, depth + 1, true) : requireNonNullElse(binding, var);
                }
                case KifList list -> {
                    var changed = new boolean[]{false};
                    var newTerms = list.terms().stream().map(sub -> {
                        var subSubst = substRecursive(sub, bindings, depth + 1, fully);
                        if (subSubst != sub) changed[0] = true;
                        return subSubst;
                    }).toList();
                    yield changed[0] ? new KifList(newTerms) : list;
                }
            };
        }

        @Nullable
        private static Map<KifVar, KifTerm> bindVariable(KifVar var, KifTerm value, Map<KifVar, KifTerm> bindings, boolean doOccursCheck, int depth) {
            if (var.equals(value)) return bindings;
            if (bindings.containsKey(var))
                return doOccursCheck ? unifyRecursive(bindings.get(var), value, bindings, depth + 1) : matchRecursive(bindings.get(var), value, bindings, depth + 1);
            var finalValue = substRecursive(value, bindings, depth + 1, true);
            if (doOccursCheck && occursCheckRecursive(var, finalValue, bindings, depth + 1)) return null;
            Map<KifVar, KifTerm> newBindings = new HashMap<>(bindings);
            newBindings.put(var, finalValue);
            return Collections.unmodifiableMap(newBindings);
        }

        private static boolean occursCheckRecursive(KifVar var, KifTerm term, Map<KifVar, KifTerm> bindings, int depth) {
            if (depth > MAX_SUBST_DEPTH) return true;
            var substTerm = substRecursive(term, bindings, depth + 1, true);
            return switch (substTerm) {
                case KifVar v -> var.equals(v);
                case KifList l -> l.terms().stream().anyMatch(t -> occursCheckRecursive(var, t, bindings, depth + 1));
                case KifAtom ignored -> false;
            };
        }

        private static Optional<KifTerm> rewriteRecursive(KifTerm target, KifTerm lhs, KifTerm rhs, int depth) {
            if (depth > MAX_SUBST_DEPTH) return Optional.empty();
            return ofNullable(matchRecursive(lhs, target, Map.of(), depth + 1))
                    .map(b -> substRecursive(rhs, b, depth + 1, true))
                    .or(() -> (target instanceof KifList tl) ? rewriteSubterms(tl, lhs, rhs, depth + 1) : Optional.empty());
        }

        private static Optional<KifTerm> rewriteSubterms(KifList targetList, KifTerm lhs, KifTerm rhs, int depth) {
            var changed = false;
            List<KifTerm> newSubs = new ArrayList<>(targetList.size());
            for (var sub : targetList.terms()) {
                var rewritten = rewriteRecursive(sub, lhs, rhs, depth);
                if (rewritten.isPresent()) {
                    changed = true;
                    newSubs.add(rewritten.get());
                } else {
                    newSubs.add(sub);
                }
            }
            return changed ? Optional.of(new KifList(newSubs)) : Optional.empty();
        }
    }

    static class Operators {
        private final ConcurrentMap<KifAtom, Operator> ops = new ConcurrentHashMap<>();

        void add(Operator operator) {
            ops.put(operator.pred(), operator);
            System.out.println("Registered operator: " + operator.pred().toKif());
        }

        Optional<Operator> get(KifAtom predicate) {
            return ofNullable(ops.get(predicate));
        }
    }

    static class BasicOperator implements Operator {
        private final String id = generateId(ID_PREFIX_OPERATOR);
        private final KifAtom pred;
        private final Function<KifList, Optional<KifTerm>> function;

        BasicOperator(KifAtom pred, Function<KifList, Optional<KifTerm>> function) {
            this.pred = pred;
            this.function = function;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public KifAtom pred() {
            return pred;
        }

        @Override
        public CompletableFuture<KifTerm> exe(KifList arguments, ReasonerContext context) {
            return CompletableFuture.completedFuture(function.apply(arguments).orElse(null));
        }
    }

    static class KifParser {
        private final Reader reader;
        private int currentChar = -2;
        private int line = 1;
        private int col = 0;

        private KifParser(Reader reader) {
            this.reader = reader;
        }

        static List<KifTerm> parseKif(String input) throws ParseException {
            if (input == null || input.isBlank()) return List.of();
            try (var sr = new StringReader(input.trim())) {
                return new KifParser(sr).parseTopLevel();
            } catch (IOException e) {
                throw new ParseException("Internal Read error: " + e.getMessage());
            }
        }

        private List<KifTerm> parseTopLevel() throws IOException, ParseException {
            List<KifTerm> terms = new ArrayList<>();
            consumeWhitespaceAndComments();
            while (peek() != -1) {
                terms.add(parseTerm());
                consumeWhitespaceAndComments();
            }
            return Collections.unmodifiableList(terms);
        }

        private KifTerm parseTerm() throws IOException, ParseException {
            consumeWhitespaceAndComments();
            return switch (peek()) {
                case -1 -> throw createParseException("Unexpected EOF");
                case '(' -> parseList();
                case '"' -> parseQuotedString();
                case '?' -> parseVariable();
                default -> parseAtom();
            };
        }

        private KifList parseList() throws IOException, ParseException {
            consumeChar('(');
            List<KifTerm> terms = new ArrayList<>();
            while (true) {
                consumeWhitespaceAndComments();
                var next = peek();
                if (next == ')') {
                    consumeChar(')');
                    return new KifList(terms);
                }
                if (next == -1) throw createParseException("Unmatched parenthesis");
                terms.add(parseTerm());
            }
        }

        private KifVar parseVariable() throws IOException, ParseException {
            consumeChar('?');
            var sb = new StringBuilder("?");
            if (!isValidAtomChar(peek())) throw createParseException("Variable name character expected after '?'");
            while (isValidAtomChar(peek())) sb.append((char) consumeChar());
            if (sb.length() < 2) throw createParseException("Empty variable name after '?'");
            return KifVar.of(sb.toString());
        }

        private KifAtom parseAtom() throws IOException, ParseException {
            var sb = new StringBuilder();
            if (!isValidAtomChar(peek())) throw createParseException("Invalid character at start of atom");
            while (isValidAtomChar(peek())) sb.append((char) consumeChar());
            return KifAtom.of(sb.toString());
        }

        private boolean isValidAtomChar(int c) {
            return c != -1 && !Character.isWhitespace(c) && "()\";?".indexOf(c) == -1 && c != ';';
        }

        private KifAtom parseQuotedString() throws IOException, ParseException {
            consumeChar('"');
            var sb = new StringBuilder();
            while (true) {
                var c = consumeChar();
                if (c == '"') return KifAtom.of(sb.toString());
                if (c == -1) throw createParseException("Unmatched quote in string literal");
                if (c == '\\') {
                    var next = consumeChar();
                    if (next == -1) throw createParseException("EOF after escape character");
                    sb.append((char) switch (next) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        case '\\', '"' -> next;
                        default -> next;
                    });
                } else sb.append((char) c);
            }
        }

        private int peek() throws IOException {
            if (currentChar == -2) currentChar = reader.read();
            return currentChar;
        }

        private int consumeChar() throws IOException {
            var c = peek();
            if (c != -1) {
                currentChar = -2;
                if (c == '\n') {
                    line++;
                    col = 0;
                } else col++;
            }
            return c;
        }

        private void consumeChar(char expected) throws IOException, ParseException {
            var actual = consumeChar();
            if (actual != expected)
                throw createParseException("Expected '" + expected + "' but found " + ((actual == -1) ? "EOF" : "'" + (char) actual + "'"));
        }

        private void consumeWhitespaceAndComments() throws IOException {
            while (true) {
                var c = peek();
                if (c == -1) break;
                if (Character.isWhitespace(c)) consumeChar();
                else if (c == ';') {
                    do consumeChar(); while (peek() != '\n' && peek() != '\r' && peek() != -1);
                } else break;
            }
        }

        private ParseException createParseException(String message) {
            return new ParseException(message + " at line " + line + " col " + col);
        }
    }

    static class ParseException extends Exception {
        ParseException(String message) {
            super(message);
        }
    }
}
