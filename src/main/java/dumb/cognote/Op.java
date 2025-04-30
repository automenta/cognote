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

        JsonNode toJson(); // Return JsonNode instead of JSONObject

        String getType(); // Required for @JsonTypeInfo
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

            // Add the new (ask-user ?Prompt) operator
            add(new DialogueOperator(Term.Atom.of(Protocol.OP_ASK_USER)));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class BasicOperator implements Operator {
        private final String id = Cog.id(Logic.ID_PREFIX_OPERATOR);
        private final Term.Atom pred;
        private final Function<Term.Lst, Optional<Term>> function;

        // Default constructor for Jackson
        private BasicOperator() {
            this(null, null); // Will be populated by Jackson
        }

        BasicOperator(Term.Atom pred, Function<Term.Lst, Optional<Term>> function) {
            this.pred = pred;
            this.function = function; // Note: Function is not serialized/deserialized by default
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
            // Basic operators are synchronous, wrap the result in a completed future
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

        // Default constructor for Jackson
        private DialogueOperator() {
            this(null); // Will be populated by Jackson
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
            // Replaced pattern matching instanceof with traditional check and cast
            if (arguments.size() != 2 || !(arguments.get(1) instanceof Term.Atom promptAtom)) {
                Log.error("Invalid arguments for (ask-user): Expected (ask-user \"Prompt string\"). Found: " + arguments.toKif());
                return CompletableFuture.completedFuture(null); // Fail the goal if arguments are invalid
            }
            String value = promptAtom.value();


            var dialogueId = Cog.id("dialogue_");
            var options = Json.node(); // Use Jackson ObjectNode
            var dialogueContext = Json.node(); // Use Jackson ObjectNode
            // Add context if needed, e.g., related assertion IDs

            // Request dialogue from the UI via DialogueManager
            return context.dialogue().request(dialogueId, DIALOGUE_TYPE_TEXT_INPUT, value, options, dialogueContext)
                    .thenApply(responseJson -> { // responseJson is now JsonNode
                        // Process the response from the UI
                        var responseTextNode = responseJson.get(DIALOGUE_RESPONSE_KEY_TEXT);
                        if (responseTextNode != null && responseTextNode.isTextual()) {
                            var responseText = responseTextNode.asText();
                            // Convert the response text into a KIF Term (e.g., a string atom)
                            // This term will be unified with the variable in the original goal (if any)
                            return (Term) Term.Atom.of(responseText);
                        } else {
                            Log.warning("Dialogue response for " + dialogueId + " did not contain expected key '" + DIALOGUE_RESPONSE_KEY_TEXT + "' or it was not text.");
                            return null; // Dialogue failed or user cancelled/provided no text
                        }
                    })
                    .exceptionally(ex -> {
                        Log.error("Dialogue request failed for " + dialogueId + ": " + ex.getMessage());
                        return null; // Dialogue failed
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
