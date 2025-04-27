package dumb.cognote;

import dumb.cognote.Term.Atom;
import org.json.JSONObject;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.function.DoubleBinaryOperator;
import java.util.function.Function;

import static dumb.cognote.Log.message;
import static dumb.cognote.ProtocolConstants.DIALOGUE_RESPONSE_KEY_TEXT;
import static dumb.cognote.ProtocolConstants.DIALOGUE_TYPE_TEXT_INPUT;
import static java.util.Optional.ofNullable;

public class Op {

    interface Operator {
        String id();

        Atom pred();

        CompletableFuture<Term> exe(Term.Lst arguments, Reason.Reasoning context);

        JSONObject toJson();
    }

    public static class Operators {
        private final ConcurrentMap<Atom, Operator> ops = new ConcurrentHashMap<>();

        void add(Operator operator) {
            ops.put(operator.pred(), operator);
            message("Registered operator: " + operator.pred().toKif());
        }

        Optional<Operator> get(Atom predicate) {
            return ofNullable(ops.get(predicate));
        }

        public void addBuiltin() {
            BiFunction<Term.Lst, DoubleBinaryOperator, Optional<Term>> numeric = (args, op) -> {
                if (args.size() == 3 && args.get(1) instanceof Atom && args.get(2) instanceof Atom) {
                    try {
                        var value1 = ((Atom) args.get(1)).value();
                        var value2 = ((Atom) args.get(2)).value();
                        return Optional.of(Atom.of(String.valueOf(op.applyAsDouble(Double.parseDouble(value1), Double.parseDouble(value2)))));
                    } catch (NumberFormatException e) {
                        // Ignore if parsing fails
                    }
                }
                return Optional.empty();
            };
            BiFunction<Term.Lst, Cog.DoubleDoublePredicate, Optional<Term>> comparison = (args, op) -> {
                if (args.size() == 3 && args.get(1) instanceof Atom && args.get(2) instanceof Atom) {
                    try {
                        var value1 = ((Atom) args.get(1)).value();
                        var value2 = ((Atom) args.get(2)).value();
                        return Optional.of(Atom.of(op.test(Double.parseDouble(value1), Double.parseDouble(value2)) ? "true" : "false"));
                    } catch (NumberFormatException e) {
                        // Ignore if parsing fails
                    }
                }
                return Optional.empty();
            };
            add(new Op.BasicOperator(Atom.of("+"), args -> numeric.apply(args, Double::sum)));
            add(new Op.BasicOperator(Atom.of("-"), args -> numeric.apply(args, (a, b) -> a - b)));
            add(new Op.BasicOperator(Atom.of("*"), args -> numeric.apply(args, (a, b) -> a * b)));
            add(new Op.BasicOperator(Atom.of("/"), args -> numeric.apply(args, (a, b) -> b == 0 ? Double.NaN : a / b)));
            add(new Op.BasicOperator(Atom.of("<"), args -> comparison.apply(args, (a, b) -> a < b)));
            add(new Op.BasicOperator(Atom.of(">"), args -> comparison.apply(args, (a, b) -> a > b)));
            add(new Op.BasicOperator(Atom.of("<="), args -> comparison.apply(args, (a, b) -> a <= b)));
            add(new Op.BasicOperator(Atom.of(">="), args -> comparison.apply(args, (a, b) -> a >= b)));

            // Add the new (ask-user ?Prompt) operator
            add(new DialogueOperator(Atom.of(ProtocolConstants.OP_ASK_USER)));
        }
    }

    static class BasicOperator implements Operator {
        private final String id = Cog.id(Logic.ID_PREFIX_OPERATOR);
        private final Atom pred;
        private final Function<Term.Lst, Optional<Term>> function;

        BasicOperator(Atom pred, Function<Term.Lst, Optional<Term>> function) {
            this.pred = pred;
            this.function = function;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Atom pred() {
            return pred;
        }

        @Override
        public CompletableFuture<Term> exe(Term.Lst arguments, Reason.Reasoning context) {
            // Basic operators are synchronous, wrap the result in a completed future
            return CompletableFuture.completedFuture(function.apply(arguments).orElse(null));
        }

        @Override
        public JSONObject toJson() {
            return new JSONObject()
                    .put("type", "operator")
                    .put("id", id)
                    .put("predicate", pred.toJson());
        }
    }

    static class DialogueOperator implements Operator {
        private final String id = Cog.id(Logic.ID_PREFIX_OPERATOR);
        private final Atom pred;

        DialogueOperator(Atom pred) {
            this.pred = pred;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Atom pred() {
            return pred;
        }

        @Override
        public CompletableFuture<Term> exe(Term.Lst arguments, Reason.Reasoning context) {
            if (arguments.size() != 2 || !(arguments.get(1) instanceof Atom(String value))) {
                Log.error("Invalid arguments for (ask-user): Expected (ask-user \"Prompt string\"). Found: " + arguments.toKif());
                return CompletableFuture.completedFuture(null); // Fail the goal if arguments are invalid
            }

            var dialogueId = Cog.id("dialogue_");
            var options = new JSONObject(); // No specific options for text input for now
            var dialogueContext = new JSONObject(); // Add context if needed, e.g., related assertion IDs

            // Request dialogue from the UI via DialogueManager
            return context.dialogueManager().requestDialogue(dialogueId, DIALOGUE_TYPE_TEXT_INPUT, value, options, dialogueContext)
                    .thenApply(responseJson -> {
                        // Process the response from the UI
                        var responseText = responseJson.optString(DIALOGUE_RESPONSE_KEY_TEXT, null);
                        if (responseText != null) {
                            // Convert the response text into a KIF Term (e.g., a string atom)
                            // This term will be unified with the variable in the original goal (if any)
                            return (Term) Atom.of(responseText);
                        } else {
                            Log.warning("Dialogue response for " + dialogueId + " did not contain expected key: " + DIALOGUE_RESPONSE_KEY_TEXT);
                            return null; // Dialogue failed or user cancelled/provided no text
                        }
                    })
                    .exceptionally(ex -> {
                        Log.error("Dialogue request failed for " + dialogueId + ": " + ex.getMessage());
                        return null; // Dialogue failed
                    });
        }

        @Override
        public JSONObject toJson() {
            return new JSONObject()
                    .put("type", "operator")
                    .put("id", id)
                    .put("predicate", pred.toJson());
        }
    }
}
