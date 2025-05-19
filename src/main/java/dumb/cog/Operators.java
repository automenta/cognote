package dumb.cog;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.function.DoubleBinaryOperator;

import static dumb.cog.util.Log.message;
import static java.util.Optional.ofNullable;

public class Operators {
    private final ConcurrentMap<Term.Atom, Operator> ops = new ConcurrentHashMap<>();

    void add(Operator operator) {
        ops.put(operator.pred(), operator);
        message("Registered operator: " + operator.pred().toKif());
    }

    Optional<Operator> get(Term.Atom predicate) {
        return ofNullable(ops.get(predicate));
    }

    public void addBuiltin() {
        BiFunction<Term.Lst, DoubleBinaryOperator, Optional<Term>> numeric = (args, op) -> {
            if (args.size() == 3 && args.get(1) instanceof Term.Atom && args.get(2) instanceof Term.Atom) {
                try {
                    var value1 = ((Term.Atom) args.get(1)).value();
                    var value2 = ((Term.Atom) args.get(2)).value();
                    return Optional.of(Term.Atom.of(String.valueOf(op.applyAsDouble(Double.parseDouble(value1), Double.parseDouble(value2)))));
                } catch (NumberFormatException e) {
                    return Optional.empty();
                }
            }
            return Optional.empty();
        };
        BiFunction<Term.Lst, Cog.DoubleDoublePredicate, Optional<Term>> comparison = (args, op) -> {
            if (args.size() == 3 && args.get(1) instanceof Term.Atom && args.get(2) instanceof Term.Atom) {
                try {
                    var value1 = ((Term.Atom) args.get(1)).value();
                    var value2 = ((Term.Atom) args.get(2)).value();
                    return Optional.of(Term.Atom.of(op.test(Double.parseDouble(value1), Double.parseDouble(value2)) ? "true" : "false"));
                } catch (NumberFormatException e) {
                    return Optional.empty();
                }
            }
            return Optional.empty();
        };
        add(new Operator.BasicOperator(Term.Atom.of("+"), args -> numeric.apply(args, Double::sum)));
        add(new Operator.BasicOperator(Term.Atom.of("-"), args -> numeric.apply(args, (a, b) -> a - b)));
        add(new Operator.BasicOperator(Term.Atom.of("*"), args -> numeric.apply(args, (a, b) -> a * b)));
        add(new Operator.BasicOperator(Term.Atom.of("/"), args -> numeric.apply(args, (a, b) -> b == 0 ? Double.NaN : a / b)));
        add(new Operator.BasicOperator(Term.Atom.of("<"), args -> comparison.apply(args, (a, b) -> a < b)));
        add(new Operator.BasicOperator(Term.Atom.of(">"), args -> comparison.apply(args, (a, b) -> a > b)));
        add(new Operator.BasicOperator(Term.Atom.of("<="), args -> comparison.apply(args, (a, b) -> a <= b)));
        add(new Operator.BasicOperator(Term.Atom.of(">="), args -> comparison.apply(args, (a, b) -> a >= b)));

        add(new Operator.DialogueOperator(Term.Atom.of(Protocol.OP_ASK_USER)));
    }
}
