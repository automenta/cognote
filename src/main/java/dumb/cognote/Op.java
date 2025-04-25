package dumb.cognote;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.function.DoubleBinaryOperator;
import java.util.function.Function;

import static java.util.Optional.ofNullable;

public class Op {

    interface Operator {
        String id();

        Term.Atom pred();

        CompletableFuture<Term> exe(Term.Lst arguments, Reason.Reasoning context);
    }

    public static class Operators {
        private final ConcurrentMap<Term.Atom, Operator> ops = new ConcurrentHashMap<>();

        void add(Operator operator) {
            ops.put(operator.pred(), operator);
            System.out.println("Registered operator: " + operator.pred().toKif());
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
                        // Ignore if parsing fails
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
                        // Ignore if parsing fails
                    }
                }
                return Optional.empty();
            };
            add(new Op.BasicOperator(Term.Atom.of("+"), args -> numeric.apply(args, Double::sum)));
            add(new Op.BasicOperator(Term.Atom.of("-"), args -> numeric.apply(args, (a, b) -> a - b)));
            add(new Op.BasicOperator(Term.Atom.of("*"), args -> numeric.apply(args, (a, b) -> a * b)));
            add(new Op.BasicOperator(Term.Atom.of("/"), args -> numeric.apply(args, (a, b) -> b == 0 ? Double.NaN : a / b)));
            add(new Op.BasicOperator(Term.Atom.of("<"), args -> comparison.apply(args, (a, b) -> a < b)));
            add(new Op.BasicOperator(Term.Atom.of(">"), args -> comparison.apply(args, (a, b) -> a > b)));
            add(new Op.BasicOperator(Term.Atom.of("<="), args -> comparison.apply(args, (a, b) -> a <= b)));
            add(new Op.BasicOperator(Term.Atom.of(">="), args -> comparison.apply(args, (a, b) -> a >= b)));
        }
    }

    static class BasicOperator implements Operator {
        private final String id = Cog.id(Logic.ID_PREFIX_OPERATOR);
        private final Term.Atom pred;
        private final Function<Term.Lst, Optional<Term>> function;

        BasicOperator(Term.Atom pred, Function<Term.Lst, Optional<Term>> function) {
            this.pred = pred;
            this.function = function;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Term.Atom pred() {
            return pred;
        }

        @Override
        public CompletableFuture<Term> exe(Term.Lst arguments, Reason.Reasoning context) {
            return CompletableFuture.completedFuture(function.apply(arguments).orElse(null));
        }
    }
}
