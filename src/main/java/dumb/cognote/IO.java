package dumb.cognote;

import javax.swing.*;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNullElse;
import static java.util.Optional.ofNullable;

public class IO {
    static class StatusUpdaterPlugin extends Cog.BasePlugin {
        private final Consumer<Cog.SystemStatusEvent> uiUpdater;

        StatusUpdaterPlugin(Consumer<Cog.SystemStatusEvent> uiUpdater) {
            this.uiUpdater = uiUpdater;
        }

        @Override
        public void start(Cog.Events ev, Logic.Cognition ctx) {
            super.start(ev, ctx);
            // Listen to events that change system state counts
            ev.on(Cog.AssertionAddedEvent.class, e -> updateStatus());
            ev.on(Cog.AssertionRetractedEvent.class, e -> updateStatus());
            ev.on(Cog.AssertionEvictedEvent.class, e -> updateStatus());
            ev.on(Cog.AssertionStatusChangedEvent.class, e -> updateStatus());
            ev.on(Cog.RuleAddedEvent.class, e -> updateStatus());
            ev.on(Cog.RuleRemovedEvent.class, e -> updateStatus());
            ev.on(Cog.AddedEvent.class, e -> updateStatus()); // Note added
            ev.on(Cog.RemovedEvent.class, e -> updateStatus()); // Note removed
            ev.on(Cog.LlmInfoEvent.class, e -> updateStatus()); // LLM task started
            ev.on(Cog.LlmUpdateEvent.class, e -> {
                var s = e.status();
                if (s == UI.LlmStatus.DONE || s == UI.LlmStatus.ERROR || s == UI.LlmStatus.CANCELLED)
                    updateStatus();
            }); // LLM task finished
            ev.on(Cog.SystemStatusEvent.class, uiUpdater); // Directly pass through status updates
            updateStatus(); // Initial status
        }

        private void updateStatus() {
            publish(new Cog.SystemStatusEvent(getCog().systemStatus, context.kbCount(), context.kbTotalCapacity(), getCog().lm.activeLlmTasks.size(), context.ruleCount()));
        }
    }

    static class WebSocketBroadcasterPlugin extends Cog.BasePlugin {
        private final Cog server;

        WebSocketBroadcasterPlugin(Cog server) {
            this.server = server;
        }

        @Override
        public void start(Cog.Events ev, Logic.Cognition ctx) {
            super.start(ev, ctx);
            ev.on(Cog.AssertionAddedEvent.class, e -> broadcastMessage("assert-added", e.assertion(), e.kbId()));
            ev.on(Cog.AssertionRetractedEvent.class, e -> broadcastMessage("retract", e.assertion(), e.kbId()));
            ev.on(Cog.AssertionEvictedEvent.class, e -> broadcastMessage("evict", e.assertion(), e.kbId()));
            ev.on(Cog.LlmInfoEvent.class, e -> broadcastMessage("llm-info", e.llmItem()));
            ev.on(Cog.LlmUpdateEvent.class, e -> broadcastMessage("llm-update", e));
            ev.on(Cog.WebSocketBroadcastEvent.class, e -> safeBroadcast(e.message()));
            if (server.broadcastInputAssertions) ev.on(Cog.ExternalInputEvent.class, this::onExternalInput);
        }

        private void onExternalInput(Cog.ExternalInputEvent event) {
            if (event.term() instanceof Logic.KifList list) {
                var tempId = Cog.generateId(Cog.ID_PREFIX_INPUT_ITEM);
                var pri = (event.sourceId().startsWith("llm-") ? LM.LLM_ASSERTION_BASE_PRIORITY : Cog.INPUT_ASSERTION_BASE_PRIORITY) / (1.0 + list.weight());
                var type = list.containsSkolemTerm() ? Logic.AssertionType.SKOLEMIZED : Logic.AssertionType.GROUND;
                var kbId = requireNonNullElse(event.targetNoteId(), Cog.GLOBAL_KB_NOTE_ID);
                // Create a temporary Assertion object just for broadcasting
                broadcastMessage("assert-input", new Logic.Assertion(tempId, list, pri, System.currentTimeMillis(), event.targetNoteId(), Set.of(), type, false, false, false, List.of(), 0, true, kbId), kbId);
            }
        }

        private void broadcastMessage(String type, Logic.Assertion assertion, String kbId) {
            var kif = assertion.toKifString();
            var msg = switch (type) {
                case "assert-added", "assert-input" ->
                        String.format("%s %.4f %s [%s] {type:%s, depth:%d, kb:%s}", type, assertion.pri(), kif, assertion.id(), assertion.type(), assertion.derivationDepth(), kbId);
                case "retract", "evict" -> String.format("%s %s", type, assertion.id());
                default -> String.format("%s %.4f %s [%s]", type, assertion.pri(), kif, assertion.id()); // Fallback
            };
            safeBroadcast(msg);
        }

        private void broadcastMessage(String type, UI.AttachmentViewModel llmItem) {
            if (type.equals("llm-info") && llmItem.noteId() != null) {
                safeBroadcast(String.format("llm-info %s [%s] {type:%s, status:%s, content:\"%s\"}",
                        llmItem.noteId(), llmItem.id(), llmItem.attachmentType(), llmItem.llmStatus(), llmItem.content().replace("\"", "\\\"")));
            }
        }

        private void broadcastMessage(String type, Cog.LlmUpdateEvent event) {
            if (type.equals("llm-update")) {
                safeBroadcast(String.format("llm-update %s {status:%s, content:\"%s\"}",
                        event.taskId(), event.status(), event.content().replace("\"", "\\\"")));
            }
        }

        private void safeBroadcast(String message) {
            try {
                if (!server.websocket.getConnections().isEmpty()) server.websocket.broadcast(message);
            } catch (Exception e) {
                // Reduce log noise for common closure exceptions
                if (!(e instanceof ConcurrentModificationException || ofNullable(e.getMessage()).map(m -> m.contains("closed") || m.contains("reset") || m.contains("Broken pipe")).orElse(false)))
                    System.err.println("Error during WebSocket broadcast: " + e.getMessage());
            }
        }
    }

    static class UiUpdatePlugin extends Cog.BasePlugin {
        private final UI swingUI;

        UiUpdatePlugin(UI ui) {
            this.swingUI = ui;
        }

        @Override
        public void start(Cog.Events ev, Logic.Cognition ctx) {
            super.start(ev, ctx);
            ev.on(Cog.AssertionAddedEvent.class, e -> handleUiUpdate("assert-added", e.assertion()));
            ev.on(Cog.AssertionRetractedEvent.class, e -> handleUiUpdate("retract", e.assertion()));
            ev.on(Cog.AssertionEvictedEvent.class, e -> handleUiUpdate("evict", e.assertion()));
            ev.on(Cog.AssertionStatusChangedEvent.class, this::handleStatusChange);
            ev.on(Cog.LlmInfoEvent.class, e -> handleUiUpdate("llm-info", e.llmItem()));
            ev.on(Cog.LlmUpdateEvent.class, this::handleLlmUpdate);
            ev.on(Cog.AddedEvent.class, e -> SwingUtilities.invokeLater(() -> swingUI.addNoteToList(e.note())));
            ev.on(Cog.RemovedEvent.class, e -> SwingUtilities.invokeLater(() -> swingUI.removeNoteFromList(e.note().id)));
            ev.on(Cog.QueryResultEvent.class, e -> handleUiUpdate("query-result", e.result()));
            ev.on(Cog.QueryRequestEvent.class, e -> handleUiUpdate("query-sent", e.query()));
        }

        private void handleUiUpdate(String type, Object payload) {
            if (swingUI == null || !swingUI.isDisplayable()) return;

            UI.AttachmentViewModel vm = null;
            String displayNoteId = null;

            switch (payload) {
                case Logic.Assertion assertion -> {
                    var sourceNoteId = assertion.sourceNoteId();
                    var derivedNoteId = (sourceNoteId == null && assertion.derivationDepth() > 0) ? context.findCommonSourceNodeId(assertion.justificationIds()) : null;
                    displayNoteId = requireNonNullElse(sourceNoteId != null ? sourceNoteId : derivedNoteId, assertion.kb()); // Default to KB ID if no source/derived note
                    vm = UI.AttachmentViewModel.fromAssertion(assertion, type, displayNoteId);
                }
                case UI.AttachmentViewModel llmVm -> {
                    vm = llmVm;
                    displayNoteId = vm.noteId();
                }
                case Cog.Answer result -> {
                    displayNoteId = Cog.GLOBAL_KB_NOTE_ID; // Query results shown globally for now
                    var content = String.format("Query Result (%s): %s -> %d bindings", result.status(), result.query(), result.bindings().size());
                    vm = UI.AttachmentViewModel.forQuery(result.query() + "_res", displayNoteId, content, UI.AttachmentType.QUERY_RESULT, System.currentTimeMillis(), Cog.GLOBAL_KB_NOTE_ID);
                }
                case Cog.Query query -> {
                    displayNoteId = requireNonNullElse(query.targetKbId(), Cog.GLOBAL_KB_NOTE_ID); // Show query in target KB or global
                    var content = "Query Sent: " + query.pattern().toKif();
                    vm = UI.AttachmentViewModel.forQuery(query.id() + "_sent", displayNoteId, content, UI.AttachmentType.QUERY_SENT, System.currentTimeMillis(), displayNoteId);
                }
                default -> {
                    return;
                } // Unknown payload type
            }

            if (vm != null && displayNoteId != null) {
                final var finalVm = vm;
                final var finalDisplayNoteId = displayNoteId;
                SwingUtilities.invokeLater(() -> swingUI.handleSystemUpdate(finalVm, finalDisplayNoteId));
            }
        }

        private void handleStatusChange(Cog.AssertionStatusChangedEvent event) {
            context.findAssertionByIdAcrossKbs(event.assertionId())
                    .ifPresent(a -> handleUiUpdate(event.isActive() ? "status-active" : "status-inactive", a));
        }

        private void handleLlmUpdate(Cog.LlmUpdateEvent event) {
            SwingUtilities.invokeLater(() -> swingUI.updateLlmItem(event.taskId(), event.status(), event.content()));
        }
    }
}
