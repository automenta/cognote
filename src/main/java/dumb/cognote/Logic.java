package dumb.cognote;

import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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

    static boolean groundOrSkolemized(Assertion a) {
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

    public static class Cognition {
        public final Truths truth;
        public final Op.Operators operators;
        public final Set<String> activeNoteIds = ConcurrentHashMap.newKeySet();
        public final Events events;
        public final Cog cog;
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

}
