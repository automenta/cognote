package dumb.cog.plugin;

import dumb.cog.Cognition;
import dumb.cog.Event;
import dumb.cog.Plugin;
import dumb.cog.Term;
import dumb.cog.util.Events;
import dumb.cog.util.KifParser;

import static dumb.cog.util.Log.error;
import static dumb.cog.util.Log.message;

public class RetractionPlugin extends Plugin.BasePlugin {

    @Override
    public void start(Events e, Cognition ctx) {
        super.start(e, ctx);
        events.on(Event.RetractionRequestEvent.class, this::handleRetractionRequest);
        log("RetractionPlugin started.");
    }

    private void handleRetractionRequest(Event.RetractionRequestEvent event) {
        var target = event.target();
        var type = event.type();
        var sourceId = event.sourceId();
        var noteId = event.noteId();

        message("Processing retraction request from " + sourceId + ": target=" + target + ", type=" + type + (noteId != null ? ", noteId=" + noteId : ""));

        try {
            switch (type) {
                case BY_ID -> {
                    context.truth.remove(target, sourceId);
                    message("RetractionPlugin: Retracted assertion by ID: " + target);
                }
                case BY_NOTE -> {
                    if (target == null || target.isBlank()) {
                        logWarning("RetractionPlugin: BY_NOTE retraction request missing target note ID.");
                        return;
                    }
                    context.kb(target).getAllAssertionIds().forEach(assertionId -> context.truth.remove(assertionId, sourceId + "-by-note"));
                    // Also remove rules associated with the note
                    context.rules().stream()
                            .filter(rule -> target.equals(rule.sourceNoteId()))
                            .toList() // Collect to avoid concurrent modification
                            .forEach(rule -> context.removeRule(rule));
                    message("RetractionPlugin: Retracted all assertions and rules for note: " + target);
                }
                case BY_RULE_FORM -> {
                    if (target == null || target.isBlank()) {
                        logWarning("RetractionPlugin: BY_RULE_FORM retraction request missing target rule form.");
                        return;
                    }
                    try {
                        var terms = KifParser.parseKif(target);
                        if (terms.size() != 1 || !(terms.getFirst() instanceof Term.Lst ruleForm)) {
                            logWarning("RetractionPlugin: BY_RULE_FORM target is not a single KIF list: " + target);
                            return;
                        }
                        if (context.removeRule(ruleForm)) {
                            message("RetractionPlugin: Retracted rule by form: " + target);
                        } else {
                            logWarning("RetractionPlugin: No rule found matching form: " + target);
                        }
                    } catch (KifParser.ParseException e) {
                        logError("RetractionPlugin: Failed to parse KIF rule form for retraction: " + target + " Error: " + e.getMessage());
                    }
                }
                case BY_KIF -> {
                    if (target == null || target.isBlank()) {
                        logWarning("RetractionPlugin: BY_KIF retraction request missing target KIF.");
                        return;
                    }
                    try {
                        var terms = KifParser.parseKif(target);
                        if (terms.size() != 1 || !(terms.getFirst() instanceof Term.Lst kif)) {
                            logWarning("RetractionPlugin: BY_KIF target is not a single KIF list: " + target);
                            return;
                        }
                        // Find and retract matching assertions in the specified KB or globally if noteId is null
                        context.findAssertionByKif(kif, noteId)
                                .ifPresentOrElse(
                                        assertion -> context.truth.remove(assertion.id(), sourceId + "-by-kif"),
                                        () -> logWarning("RetractionPlugin: No active assertion found matching KIF: " + target + (noteId != null ? " in KB " + noteId : ""))
                                );
                        message("RetractionPlugin: Retraction by KIF requested for: " + target);
                    } catch (KifParser.ParseException e) {
                        logError("RetractionPlugin: Failed to parse target KIF for retraction: " + target + " Error: " + e.getMessage());
                    }
                }
                default -> logWarning("RetractionPlugin: Unknown retraction type: " + type);
            }
        } catch (Exception e) {
            error("RetractionPlugin: Error processing retraction request from " + sourceId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
}
