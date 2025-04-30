package dumb.cognote;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import dumb.cognote.util.Json;
import dumb.cognote.util.Log;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Rule(String id, @JsonProperty("formJson") Term.Lst form, Term antecedent, Term consequent, double pri,
                   List<Term> antecedents, @Nullable String sourceNoteId) {
    public Rule {
        requireNonNull(id);
        requireNonNull(form);
        requireNonNull(antecedent);
        requireNonNull(consequent);
        antecedents = List.copyOf(requireNonNull(antecedents));
    }

    public static Rule parseRule(String id, Term.Lst ruleForm, double pri, @Nullable String sourceNoteId) throws IllegalArgumentException {
        if (!(ruleForm.op().filter(op -> op.equals(Logic.KIF_OP_IMPLIES) || op.equals(Logic.KIF_OP_EQUIV)).isPresent() && ruleForm.size() == 3))
            throw new IllegalArgumentException("Rule form must be (=> ant con) or (<=> ant con): " + ruleForm.toKif());

        var antTerm = ruleForm.get(1);
        var conTerm = ruleForm.get(2);
        var parsedAntecedents = switch (antTerm) {
            case Term.Lst list when list.op().filter(Logic.KIF_OP_AND::equals).isPresent() ->
                    list.terms.stream().skip(1).map(Rule::validateAntecedentClause).toList();
            case Term.Lst list -> List.of(validateAntecedentClause(list));
            case Term t when t.equals(Term.Atom.of("true")) -> List.<Term>of();
            default ->
                    throw new IllegalArgumentException("Antecedent must be a KIF list, (not list), (and ...), or true: " + antTerm.toKif());
        };
        validateUnboundVariables(ruleForm, antTerm, conTerm);
        return new Rule(id, ruleForm, antTerm, conTerm, pri, parsedAntecedents, sourceNoteId);
    }

    private static Term validateAntecedentClause(Term term) {
        return switch (term) {
            case Term.Lst list -> {
                if (list.op().filter(Logic.KIF_OP_NOT::equals).isPresent() && (list.size() != 2 || !(list.get(1) instanceof Term.Lst)))
                    throw new IllegalArgumentException("Argument of 'not' in rule antecedent must be a list: " + list.toKif());
                yield list;
            }
            default ->
                    throw new IllegalArgumentException("Elements of rule antecedent must be lists or (not list): " + term.toKif());
        };
    }

    private static void validateUnboundVariables(Term.Lst ruleForm, Term antecedent, Term consequent) {
        var unbound = new HashSet<>(consequent.vars());
        unbound.removeAll(antecedent.vars());
        unbound.removeAll(getQuantifierBoundVariables(consequent));
        if (!unbound.isEmpty() && ruleForm.op().filter(Logic.KIF_OP_IMPLIES::equals).isPresent())
            Log.warning("Rule consequent has variables not bound by antecedent or local quantifier: " + unbound.stream().map(Term.Var::name).collect(Collectors.joining(", ")) + " in " + ruleForm.toKif());
    }

    private static Set<Term.Var> getQuantifierBoundVariables(Term term) {
        Set<Term.Var> bound = new HashSet<>();
        collectQuantifierBoundVariablesRecursive(term, bound);
        return Collections.unmodifiableSet(bound);
    }

    private static void collectQuantifierBoundVariablesRecursive(Term term, Set<Term.Var> boundVars) {
        switch (term) {
            case Term.Lst list when list.size() == 3 && list.op().filter(op -> op.equals(Logic.KIF_OP_EXISTS) || op.equals(Logic.KIF_OP_FORALL)).isPresent() -> {
                boundVars.addAll(Term.collectSpecVars(list.get(1)));
                collectQuantifierBoundVariablesRecursive(list.get(2), boundVars);
            }
            case Term.Lst list -> list.terms.forEach(sub -> collectQuantifierBoundVariablesRecursive(sub, boundVars));
            default -> {
            }
        }
    }

    @JsonProperty("formString")
    public String formString() {
        return form.toKif();
    }

    public JsonNode toJson() {
        return Json.node(this);
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
