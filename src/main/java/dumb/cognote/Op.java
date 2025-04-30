package dumb.cognote;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.function.DoubleBinaryOperator;
import java.util.function.Function;

import static dumb.cognote.Log.message;
import static dumb.cognote.Protocol.DIALOGUE_RESPONSE_KEY_TEXT;
import static dumb.cognote.Protocol.DIALOGUE_TYPE_TEXT_INPUT;
import static java.util.Optional.ofNullable;

public class Op {

    @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "type",
            visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = BasicOperator.class, name = "basic"),
            @JsonSubTypes.Type(value = DialogueOperator.class, name = "dialogue")
    })
    interface Operator {
        String id();

        Term.Atom pred();

        CompletableFuture<Term> exe(Term.Lst arguments, Reason.Reasoning context);

        JsonNode toJson();

        String getType();
    }

    public static class Operators {
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

            add(new DialogueOperator(Term.Atom.of(Protocol.OP_ASK_USER)));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class BasicOperator implements Operator {
        private final String id = Cog.id(Logic.ID_PREFIX_OPERATOR);
        private final Term.Atom pred;
        private final Function<Term.Lst, Optional<Term>> function;

        private BasicOperator() {
            this(null, null);
        }

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

        @Override
        public JsonNode toJson() {
            return Json.node(this);
        }

        @Override
        public String getType() {
            return "basic";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class DialogueOperator implements Operator {
        private final String id = Cog.id(Logic.ID_PREFIX_OPERATOR);
        private final Term.Atom pred;

        private DialogueOperator() {
            this(null);
        }

        DialogueOperator(Term.Atom pred) {
            this.pred = pred;
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
            if (arguments.size() != 2 || !(arguments.get(1) instanceof Term.Atom promptAtom)) {
                Log.error("Invalid arguments for (ask-user): Expected (ask-user \"Prompt string\"). Found: " + arguments.toKif());
                return CompletableFuture.completedFuture(null);
            }
            String value = promptAtom.value();


            var dialogueId = Cog.id("dialogue_");
            var options = Json.node();
            var dialogueContext = Json.node();

            return context.dialogue().request(dialogueId, DIALOGUE_TYPE_TEXT_INPUT, value, options, dialogueContext)
                    .thenApply(responseJson -> {
                        var responseTextNode = responseJson.get(DIALOGUE_RESPONSE_KEY_TEXT);
                        if (responseTextNode != null && responseTextNode.isTextual()) {
                            var responseText = responseTextNode.asText();
                            return (Term) Term.Atom.of(responseText);
                        } else {
                            Log.warning("Dialogue response for " + dialogueId + " did not contain expected key '" + DIALOGUE_RESPONSE_KEY_TEXT + "' or it was not text.");
                            return null;
                        }
                    })
                    .exceptionally(ex -> {
                        Log.error("Dialogue request failed for " + dialogueId + ": " + ex.getMessage());
                        return null;
                    });
        }

        @Override
        public JsonNode toJson() {
            return Json.node(this);
        }

        @Override
        public String getType() {
            return "dialogue";
        }
    }
}
