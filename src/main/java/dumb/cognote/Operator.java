package dumb.cognote;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import dumb.cognote.util.Json;
import dumb.cognote.util.Log;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static dumb.cognote.Protocol.DIALOGUE_RESPONSE_KEY_TEXT;
import static dumb.cognote.Protocol.DIALOGUE_TYPE_TEXT_INPUT;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type",
        visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = Operator.BasicOperator.class, name = "basic"),
        @JsonSubTypes.Type(value = Operator.DialogueOperator.class, name = "dialogue")
})
interface Operator {
    String id();

    Term.Atom pred();

    CompletableFuture<Term> exe(Term.Lst arguments, Reason.Reasoning context);

    JsonNode toJson();

    String getType();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    class BasicOperator implements Operator {
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
    class DialogueOperator implements Operator {
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
            if (arguments.size() != 2 || !(arguments.get(1) instanceof Term.Atom)) {
                Log.error("Invalid arguments for (ask-user): Expected (ask-user \"Prompt string\"). Found: " + arguments.toKif());
                return CompletableFuture.completedFuture(null);
            }

            var value = ((Term.Atom) arguments.get(1)).value();
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
