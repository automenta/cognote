package dumb.cognote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

sealed public interface Term permits Term.Atom, Term.Var, Term.Lst {
    static Set<Var> collectSpecVars(Term varsTerm) {
        return switch (varsTerm) {
            case Var v -> Set.of(v);
            case Lst l ->
                    l.terms.stream().filter(Var.class::isInstance).map(Var.class::cast).collect(Collectors.toUnmodifiableSet());
            default -> {
                Log.warning("Invalid variable specification in quantifier: " + varsTerm.toKif());
                yield Set.of();
            }
        };
    }

    String toKif();

    boolean containsVar();

    Set<Var> vars();

    int weight();

    default boolean containsSkolemTerm() {
        return switch (this) {
            case Atom a -> a.value().startsWith(Logic.ID_PREFIX_SKOLEM_CONST);
            case Lst l ->
                    l.op().filter(op -> op.startsWith(Logic.ID_PREFIX_SKOLEM_FUNC)).isPresent() || l.terms.stream().anyMatch(Term::containsSkolemTerm);
            case Var _ -> false;
        };
    }

    JSONObject toJson();

    record Var(String name) implements Term {
        private static final Map<String, Var> internCache = new ConcurrentHashMap<>(256);

        public Var {
            requireNonNull(name);
            if (!name.startsWith("?") || name.length() < 2)
                throw new IllegalArgumentException("Variable name must start with '?' and have length > 1: " + name);
        }

        public static Var of(String name) {
            return internCache.computeIfAbsent(name, Var::new);
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
        public Set<Var> vars() {
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

        @Override
        public JSONObject toJson() {
            return new JSONObject()
                    .put("type", "var")
                    .put("name", name)
                    .put("kifString", toKif());
        }
    }

    final class Lst implements Term {
        public final List<Term> terms;
        private volatile int hashCodeCache;
        private volatile boolean hashCodeCalculated = false;
        private volatile String kifStringCache;
        private volatile int weightCache = -1;
        private volatile Set<Var> variablesCache;
        private volatile Boolean containsVariableCache, containsSkolemCache;

        public Lst(List<Term> terms) {
            this.terms = List.copyOf(terms);
        }

        public Lst(Term... terms) {
            this(List.of(terms));
        }

        public Term get(int index) {
            return terms.get(index);
        }

        public int size() {
            return terms.size();
        }

        public Optional<String> op() {
            return terms.isEmpty() || !(terms.getFirst() instanceof Atom(var v)) ? Optional.empty() : Optional.of(v);
        }

        @Override
        public String toKif() {
            if (kifStringCache == null)
                kifStringCache = terms.stream().map(Term::toKif).collect(Collectors.joining(" ", "(", ")"));
            return kifStringCache;
        }

        @Override
        public boolean containsVar() {
            if (containsVariableCache == null) containsVariableCache = terms.stream().anyMatch(Term::containsVar);
            return containsVariableCache;
        }

        @Override
        public boolean containsSkolemTerm() {
            if (containsSkolemCache == null) containsSkolemCache = Term.super.containsSkolemTerm();
            return containsSkolemCache;
        }

        @Override
        public Set<Var> vars() {
            if (variablesCache == null)
                variablesCache = terms.stream().flatMap(t -> t.vars().stream()).collect(Collectors.toUnmodifiableSet());
            return variablesCache;
        }

        @Override
        public int weight() {
            if (weightCache == -1) weightCache = 1 + terms.stream().mapToInt(Term::weight).sum();
            return weightCache;
        }

        @Override
        public boolean equals(Object o) {
            return this == o || (o instanceof Lst that && this.hashCode() == that.hashCode() && terms.equals(that.terms));
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

        @Override
        public JSONObject toJson() {
            var jsonTerms = new JSONArray();
            terms.forEach(term -> jsonTerms.put(term.toJson()));
            return new JSONObject()
                    .put("type", "list")
                    .put("terms", jsonTerms)
                    .put("kifString", toKif());
        }
    }

    record Atom(String value) implements Term {
        private static final Pattern SAFE_ATOM_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-+*/.<>=:!#%&']+$");
        private static final Map<String, Atom> internCache = new ConcurrentHashMap<>(1024);

        public Atom {
            requireNonNull(value);
        }

        public static Atom of(String value) {
            return internCache.computeIfAbsent(value, Atom::new);
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
        public Set<Var> vars() {
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

        @Override
        public JSONObject toJson() {
            return new JSONObject()
                    .put("type", "atom")
                    .put("value", value)
                    .put("kifString", toKif());
        }
    }
}
