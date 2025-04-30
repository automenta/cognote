package dumb.cognote;

import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static dumb.cognote.Cog.DERIVED_PRIORITY_DECAY;
import static dumb.cognote.Cog.GLOBAL_KB_NOTE_ID;
import static dumb.cognote.util.Log.error;
import static java.util.Optional.ofNullable;

public class Cognition {
    public final Cog cog;

    public final Truths truth;
    public final Operators operators;
    public final Set<String> activeNoteIds = ConcurrentHashMap.newKeySet();

    private final ConcurrentMap<String, Knowledge> noteKbs = new ConcurrentHashMap<>();
    private final Knowledge globalKb;

    private final Set<Rule> rules = ConcurrentHashMap.newKeySet();

    public Cognition(int globalKbCapacity, Truths truth, Cog cog) {
        this.cog = cog;
        this.truth = truth;
        this.operators = new Operators();
        this.globalKb = new Knowledge(GLOBAL_KB_NOTE_ID, globalKbCapacity, cog.events, truth);
        activeNoteIds.add(GLOBAL_KB_NOTE_ID);
    }

    public static Term.Lst performSkolemization(Term.Lst body, Collection<Term.Var> existentialVars, Map<Term.Var, Term> contextBindings) {
        return Logic.Skolemizer.skolemize(new Term.Lst(Term.Atom.of(Logic.KIF_OP_EXISTS), new Term.Lst(new ArrayList<>(existentialVars)), body), contextBindings);
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
            error("Warning: Simplification depth limit reached for: " + term.toKif());
        return current;
    }

    private static Term.Lst simplifyLogicalTermOnce(Term.Lst term) {
        if (term.op().filter(Logic.KIF_OP_NOT::equals).isPresent() && term.size() == 2 && term.get(1) instanceof Term.Lst nl && nl.op().filter(Logic.KIF_OP_NOT::equals).isPresent() && nl.size() == 2 && nl.get(1) instanceof Term.Lst inner)
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
        return (noteId == null || GLOBAL_KB_NOTE_ID.equals(noteId)) ? globalKb : noteKbs.computeIfAbsent(noteId, id -> new Knowledge(id, globalKb.capacity, cog.events, truth));
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
        if (added) cog.events.emit(new Event.RuleAddedEvent(rule));
        return added;
    }

    public boolean removeRule(Rule rule) {
        var removed = rules.remove(rule);
        if (removed) cog.events.emit(new Event.RuleRemovedEvent(rule));
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
        activeNoteIds.add(GLOBAL_KB_NOTE_ID);
    }

    public Optional<Assertion> findAssertionByIdAcrossKbs(String assertionId) {
        return truth.get(assertionId);
    }

    public Optional<Assertion> findAssertionByKif(Term.Lst kif, @Nullable String kbId) {
        return findAssertionsAcrossActiveKbs(kif, a -> kif.equals(a.kif())).findFirst();
    }

    public Stream<Assertion> findAssertionsAcrossActiveKbs(Term pattern, Predicate<Assertion> filter) {
        var kbsToSearch = Stream.concat(Stream.of(globalKb), noteKbs.values().stream())
                .filter(kb -> activeNoteIds.contains(kb.id));

        return kbsToSearch
                .flatMap(kb -> kb.findUnifiableAssertions(pattern))
                .filter(Assertion::isActive)
                .filter(a -> activeNoteIds.contains(a.kb()) || activeNoteIds.contains(a.sourceNoteId()))
                .filter(filter);
    }

    public Stream<Assertion> getAllActiveAssertionsAcrossActiveKbs() {
        var kbsToSearch = Stream.concat(Stream.of(globalKb), noteKbs.values().stream())
                .filter(kb -> activeNoteIds.contains(kb.id));

        return kbsToSearch
                .flatMap(kb -> kb.getAllAssertions().stream())
                .filter(Assertion::isActive)
                .filter(a -> activeNoteIds.contains(a.kb()) || activeNoteIds.contains(a.sourceNoteId()));
    }

    public List<Note> getAllNotes() {
        return cog.getAllNotes();
    }

    public Collection<Assertion> getAllAssertions() {
        if (truth instanceof Truths.BasicTMS basicTMS) {
            return basicTMS.getAllAssertionsInternal();
        } else {
            error("Cognition.getAllAssertions() requires BasicTMS implementation.");
            return Collections.emptyList();
        }
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
