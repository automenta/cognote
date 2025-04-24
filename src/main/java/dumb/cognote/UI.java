package dumb.cognote;

import dumb.cognote.Logic.KifParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter.DefaultHighlightPainter;
import java.awt.*;
import java.awt.event.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static dumb.cognote.Cog.*;
import static dumb.cognote.Logic.*;
import static java.util.Objects.requireNonNullElse;
import static java.util.Optional.ofNullable;
import static javax.swing.SwingUtilities.invokeLater;

public class UI extends JFrame {

    private static final int UI_FONT_SIZE = 16;
    public static final Font MONOSPACED_FONT = new Font(Font.MONOSPACED, Font.PLAIN, UI_FONT_SIZE - 2);
    public static final Font UI_DEFAULT_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, UI_FONT_SIZE);
    public static final Font UI_SMALL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, UI_FONT_SIZE - 4);
    public final NoteListPanel noteListPanel;
    final Map<String, DefaultListModel<AttachmentViewModel>> noteAttachmentModels = new ConcurrentHashMap<>();
    private final EditorPanel editorPanel;
    private final MainControlPanel mainControlPanel;
    private final AttachmentPanel attachmentPanel;
    private final MenuBarHandler menuBarHandler;
    Note note = null;
    private CogNote cog;

    public UI(CogNote cog) {
        super("Cognote - Event Driven");

        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1200, 700);
        setLocationRelativeTo(null);

        noteListPanel = new NoteListPanel();
        editorPanel = new EditorPanel();
        attachmentPanel = new AttachmentPanel();
        mainControlPanel = new MainControlPanel();
        menuBarHandler = new MenuBarHandler();

        setupLayout();
        setupWindowListener();
        setJMenuBar(menuBarHandler.getMenuBar());
        applyFonts();

        setCog(cog); // Set cog and load notes into UI
    }

    public static void main(String[] args) {
        String rulesFile = null;

        for (var i = 0; i < args.length; i++) {
            try {
                switch (args[i]) {
                    case "-r", "--rules" -> rulesFile = args[++i];
                    default -> System.err.println("Warning: Unknown option: " + args[i] + ". Config via UI/JSON.");
                }
            } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
                System.err.printf("Error parsing argument for %s: %s%n", (i > 0 ? args[i - 1] : args[i]), e.getMessage());
                printUsageAndExit();
            }
        }

        try {
            var c = new CogNote(); // CogNote loads notes and config in constructor
            c.start(); // Initialize Cog components, but keep paused

            if (rulesFile != null) {
                // Load rules after Cog is initialized but before UI is fully ready/unpaused
                // Rules are processed via events, which are handled by the executor
                // The executor is running even when paused, but reasoning might be limited
                // This is acceptable for loading static rules.
                c.loadRules(rulesFile);
            }


            SwingUtilities.invokeLater(() -> {
                var ui = new UI(c);
                ui.setVisible(true);
                // UI is now responsible for calling c.setPaused(false) when the user starts the system
            });

        } catch (Exception e) {
            System.err.println("Initialization/Startup failed: " + e.getMessage());
            e.printStackTrace();
            //ofNullable(ui).ifPresent(JFrame::dispose);
            System.exit(1);
        }
    }

    private static void printUsageAndExit() {
        System.err.printf("Usage: java %s [-p port] [-r rules_file.kif]%n", Cog.class.getName());
        System.err.println("Note: Most configuration is now managed via the UI and persisted in " + NOTES_FILE);
        System.exit(1);
    }

    private static void updateOrAddModelItem(DefaultListModel<AttachmentViewModel> sourceModel, AttachmentViewModel newItem) {
        var existingIndex = findViewModelIndexById(sourceModel, newItem.id);
        if (existingIndex != -1) {
            var existingItem = sourceModel.getElementAt(existingIndex);
            // Only update if relevant fields have changed
            if (newItem.status != existingItem.status || !newItem.content().equals(existingItem.content()) ||
                    newItem.priority() != existingItem.priority() || !Objects.equals(newItem.associatedNoteId(), existingItem.associatedNoteId()) ||
                    !Objects.equals(newItem.kbId(), existingItem.kbId()) || newItem.llmStatus != existingItem.llmStatus ||
                    newItem.attachmentType != existingItem.attachmentType || newItem.depth() != existingItem.depth() || newItem.timestamp() != existingItem.timestamp()) {
                sourceModel.setElementAt(newItem, existingIndex);
            }
        } else {
            sourceModel.addElement(newItem);
        }
    }

    private static int findViewModelIndexById(DefaultListModel<AttachmentViewModel> model, String id) {
        return IntStream.range(0, model.getSize())
                .filter(i -> model.getElementAt(i).id.equals(id))
                .findFirst()
                .orElse(-1);
    }

    private static String extractContentFromKif(String kifString) {
        try {
            var terms = Logic.KifParser.parseKif(kifString);
            if (terms.size() == 1 && terms.getFirst() instanceof Term.Lst list) {
                // Attempt to extract a string literal from common positions
                // This is a heuristic and might need refinement
                if (list.size() >= 2 && list.get(1) instanceof Term.Atom atom) {
                    return atom.value();
                }
                if (list.size() >= 3 && list.get(2) instanceof Term.Atom atom) {
                    return atom.value();
                }
                if (list.size() >= 4 && list.get(3) instanceof Term.Atom atom) {
                    return atom.value();
                }
            }
        } catch (KifParser.ParseException e) {
            System.err.println("Failed to parse KIF for content extraction: " + kifString);
        }
        // Fallback: return the original KIF string
        return kifString;
    }

    private static @NotNull JList<Rule> createRuleList(DefaultListModel<Rule> ruleListModel) {
        var ruleJList = new JList<>(ruleListModel);
        ruleJList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ruleJList.setFont(MONOSPACED_FONT);
        ruleJList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                var lbl = (JLabel) super.getListCellRendererComponent(l, v, i, s, f);
                if (v instanceof Rule r)
                    lbl.setText(String.format("[%s] %.2f %s", r.id(), r.pri(), r.form().toKif()));
                return lbl;
            }
        });
        return ruleJList;
    }

    private static MouseAdapter createContextMenuMouseListener(@Nullable JList<?> list, JPopupMenu popup, Runnable... preShowActions) {
        return new MouseAdapter() {
            private void maybeShowPopup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                var targetList = (list != null) ? list : (JList<?>) e.getSource();
                var idx = targetList.locationToIndex(e.getPoint());
                if (idx != -1) {
                    if (targetList.getSelectedIndex() != idx) targetList.setSelectedIndex(idx);
                    Stream.of(preShowActions).forEach(Runnable::run);
                    popup.show(e.getComponent(), e.getX(), e.getY());
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }
        };
    }

    private void setCog(CogNote c) {
        this.cog = c;

        // Load notes from CogNote into the UI list model
        loadNotes(cog.getAllNotes());

        updateUIForSelection();

        mainControlPanel.updatePauseResumeButton();

        var ev = cog.events;
        ev.on(Cog.AssertedEvent.class, e -> handleUiUpdate("assert-added", e.assertion()));
        ev.on(Cog.RetractedEvent.class, e -> handleUiUpdate("retract", e.assertion()));
        ev.on(Cog.AssertionEvictedEvent.class, e -> handleUiUpdate("evict", e.assertion()));
        ev.on(Cog.AssertionStateEvent.class, this::handleStatusChange);
        ev.on(LlmInfoEvent.class, e -> handleUiUpdate("llm-info", e.llmItem()));
        ev.on(Cog.TaskUpdateEvent.class, this::updateLlmItem);
        ev.on(Cog.AddedEvent.class, e -> invokeLater(() -> addNoteToList(e.note())));
        ev.on(Cog.RemovedEvent.class, e -> invokeLater(() -> removeNoteFromList(e.note()))); // RemovedEvent now carries Note object
        ev.on(Cog.Answer.AnswerEvent.class, e -> handleUiUpdate("query-result", e.result()));
        ev.on(Cog.Query.QueryEvent.class, e -> handleUiUpdate("query-sent", e.query()));
        ev.on(Cog.NoteStatusEvent.class, this::handleNoteStatusChange);


        cog.events.on(Cog.SystemStatusEvent.class, statusText ->
                SwingUtilities.invokeLater(() -> mainControlPanel.statusLabel.setText("Status: " + statusText.statusMessage())
                ));
    }


    private void handleUiUpdate(String type, Object payload) {
        AttachmentViewModel vm = null;
        String displayNoteId = null;

        if (payload instanceof Assertion assertion) {
            var sourceNoteId = assertion.sourceNoteId();
            // Find the most specific note ID to display this assertion under
            // Prefer sourceNoteId if present, otherwise try to find a common source from justifications
            // Fallback to the KB ID if no specific note source is found
            var derivedNoteId = (sourceNoteId == null && assertion.derivationDepth() > 0 && cog != null) ? cog.context.commonSourceNodeId(assertion.justificationIds()) : null;
            displayNoteId = requireNonNullElse(sourceNoteId != null ? sourceNoteId : derivedNoteId, assertion.kb());
            vm = AttachmentViewModel.fromAssertion(assertion, type, displayNoteId);
        } else if (payload instanceof AttachmentViewModel llmVm) {
            vm = llmVm;
            displayNoteId = vm.noteId();
        } else if (payload instanceof Answer result) {
            // Query results are typically global or associated with the query's target KB
            displayNoteId = result.query().startsWith(ID_PREFIX_QUERY) && cog != null ?
                    cog.context.findAssertionByIdAcrossKbs(result.query()).map(Assertion::kb).orElse(GLOBAL_KB_NOTE_ID) : GLOBAL_KB_NOTE_ID; // Attempt to find KB from query ID if it's an assertion-based query
            var content = String.format("Query Result (%s): %s -> %d bindings", result.status(), result.query(), result.bindings().size());
            vm = AttachmentViewModel.forQuery(result.query() + "_res", displayNoteId, content, AttachmentType.QUERY_RESULT, System.currentTimeMillis(), displayNoteId);
        } else if (payload instanceof Query query) {
            // Query sent is associated with its target KB
            displayNoteId = requireNonNullElse(query.targetKbId(), GLOBAL_KB_NOTE_ID);
            var content = "Query Sent: " + query.pattern().toKif();
            vm = AttachmentViewModel.forQuery(query.id() + "_sent", displayNoteId, content, AttachmentType.QUERY_SENT, System.currentTimeMillis(), displayNoteId);
        }


        if (vm != null && displayNoteId != null)
            handleSystemUpdate(vm, displayNoteId);
    }

    private void handleStatusChange(Cog.AssertionStateEvent e) {
        if (cog == null) return;
        cog.context.findAssertionByIdAcrossKbs(e.assertionId())
                .ifPresent(a -> handleUiUpdate(e.isActive() ? "status-active" : "status-inactive", a));
    }

    private void handleNoteStatusChange(Cog.NoteStatusEvent e) {
        invokeLater(() -> {
            // Update the Note object in the UI list model
            noteListPanel.updateNoteDisplay(e.note());
            // If the currently selected note is the one that changed status, update UI elements that might depend on it
            if (note != null && note.id.equals(e.note().id)) {
                note = e.note(); // Update the UI's reference to the Note object
                updateUIForSelection(); // Refresh UI based on the new status
            }
        });
    }


    private void updateLlmItem(Cog.TaskUpdateEvent e) {
        invokeLater(() -> updateLlmItem(e.taskId(), e.status(), e.content()));
    }

    private void applyFonts() {
        Stream.of(
                noteListPanel.noteList, editorPanel.edit, editorPanel.title,
                attachmentPanel.filterField, attachmentPanel.queryInputField, attachmentPanel.queryButton,
                mainControlPanel.addButton, mainControlPanel.pauseResumeButton, mainControlPanel.clearAllButton,
                mainControlPanel.statusLabel, menuBarHandler.settingsItem, menuBarHandler.viewRulesItem,
                menuBarHandler.askQueryItem, menuBarHandler.runTestsItem, // Add runTestsItem
                noteListPanel.analyzeItem, noteListPanel.enhanceItem,
                noteListPanel.summarizeItem, noteListPanel.conceptsItem, noteListPanel.questionsItem,
                noteListPanel.removeItem, attachmentPanel.retractItem, attachmentPanel.showSupportItem,
                attachmentPanel.queryItem, attachmentPanel.cancelLlmItem, attachmentPanel.insertSummaryItem,
                attachmentPanel.answerQuestionItem, attachmentPanel.findRelatedConceptsItem,
                noteListPanel.startNoteItem, noteListPanel.pauseNoteItem, noteListPanel.completeNoteItem // Add new menu items
        ).forEach(c -> c.setFont(UI_DEFAULT_FONT));
        attachmentPanel.attachmentList.setFont(MONOSPACED_FONT);
    }

    private void setupLayout() {
        var leftPane = new JScrollPane(noteListPanel.noteList);
        leftPane.setPreferredSize(new Dimension(250, 0));

        var rightPanel = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorPanel, attachmentPanel);
        rightPanel.setResizeWeight(0.7);

        var mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPane, rightPanel);
        mainSplitPane.setResizeWeight(0.2);

        var p = getContentPane();
        p.setLayout(new BorderLayout());
        p.add(mainSplitPane, BorderLayout.CENTER);
        p.add(mainControlPanel, BorderLayout.SOUTH);
    }

    private void setupWindowListener() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                saveCurrentNote(); // Save current note text before stopping
                ofNullable(cog).ifPresent(Cog::stop);
                dispose();
                System.exit(0);
            }
        });
    }

    private void saveCurrentNote() {
        ofNullable(note).filter(_ -> cog != null).ifPresent(n -> {
            // Only save text/title for non-system notes
            if (!GLOBAL_KB_NOTE_ID.equals(n.id) && !Cog.CONFIG_NOTE_ID.equals(n.id) && !Cog.TEST_DEFINITIONS_NOTE_ID.equals(n.id) && !Cog.TEST_RESULTS_NOTE_ID.equals(n.id)) {
                n.text = editorPanel.edit.getText();
                n.title = editorPanel.title.getText();
                cog.save(); // Save all notes if a regular note is edited
            } else if (Cog.CONFIG_NOTE_ID.equals(n.id)) {
                // Handle config note separately
                if (!cog.updateConfig(editorPanel.edit.getText())) {
                    JOptionPane.showMessageDialog(this, "Invalid JSON format in Configuration note. Changes not applied.", "Configuration Error", JOptionPane.ERROR_MESSAGE);
                } else {
                    // Config update already calls save internally on success
                }
            } else if (Cog.TEST_DEFINITIONS_NOTE_ID.equals(n.id)) {
                // Save changes to Test Definitions note text
                n.text = editorPanel.edit.getText();
                cog.save();
            }
            // Status changes are saved via CogNote.updateNoteStatus
        });
    }

    void updateUIForSelection() {
        var noteSelected = (note != null);
        var isGlobalSelected = noteSelected && GLOBAL_KB_NOTE_ID.equals(note.id);
        var isConfigSelected = noteSelected && Cog.CONFIG_NOTE_ID.equals(note.id);
        var isTestDefsSelected = noteSelected && Cog.TEST_DEFINITIONS_NOTE_ID.equals(note.id);
        var isTestResultsSelected = noteSelected && Cog.TEST_RESULTS_NOTE_ID.equals(note.id);


        editorPanel.updateForSelection(note, isGlobalSelected, isConfigSelected);
        attachmentPanel.updateForSelection(note);
        mainControlPanel.updatePauseResumeButton(); // Update button text based on system state

        if (noteSelected) {
            setTitle("Cognote - " + note.title + (isGlobalSelected || isConfigSelected || isTestDefsSelected || isTestResultsSelected ? "" : " [" + note.id + "]"));
            SwingUtilities.invokeLater(() -> {
                if (!isGlobalSelected && !isConfigSelected && !isTestResultsSelected)
                    editorPanel.edit.requestFocusInWindow(); // Focus editor for editable notes
                else if (isConfigSelected || isTestDefsSelected)
                    editorPanel.edit.requestFocusInWindow(); // Allow editing config/test defs text
                else attachmentPanel.filterField.requestFocusInWindow(); // Focus filter for global KB or test results
            });
        } else {
            setTitle("Cognote - Event Driven");
        }
        setControlsEnabled(true); // Re-enable controls after selection change
    }

    private void setControlsEnabled(boolean enabled) {
        var noteSelected = (note != null);
        var isGlobalSelected = noteSelected && GLOBAL_KB_NOTE_ID.equals(note.id);
        var isConfigSelected = noteSelected && Cog.CONFIG_NOTE_ID.equals(note.id);
        var isTestDefsSelected = noteSelected && Cog.TEST_DEFINITIONS_NOTE_ID.equals(note.id);
        var isTestResultsSelected = noteSelected && Cog.TEST_RESULTS_NOTE_ID.equals(note.id);
        var systemReady = (cog != null && cog.running.get() && !cog.paused.get()); // System is running and not explicitly paused

        mainControlPanel.setControlsEnabled(enabled);
        editorPanel.setControlsEnabled(enabled, noteSelected, isGlobalSelected, isConfigSelected);
        attachmentPanel.setControlsEnabled(enabled, noteSelected, systemReady);
        noteListPanel.setControlsEnabled(enabled);
    }

    private void performNoteAction(String actionName, String confirmTitle, String confirmMsgFormat, int confirmMsgType, Consumer<Note> action) {
        ofNullable(note).filter(_ -> cog != null).filter(n -> !GLOBAL_KB_NOTE_ID.equals(n.id) && !Cog.CONFIG_NOTE_ID.equals(n.id) && !Cog.TEST_DEFINITIONS_NOTE_ID.equals(n.id) && !Cog.TEST_RESULTS_NOTE_ID.equals(n.id))
                .filter(note -> JOptionPane.showConfirmDialog(this, String.format(confirmMsgFormat, note.title), confirmTitle, JOptionPane.YES_NO_OPTION, confirmMsgType) == JOptionPane.YES_OPTION)
                .ifPresent(note -> {
                    updateStatus(String.format("%s '%s'...", actionName, note.title));
                    setControlsEnabled(false); // Disable controls during action
                    try {
                        action.accept(note);
                        // Status update and re-enabling controls will happen via events (e.g., TaskUpdateEvent)
                    } catch (Exception ex) {
                        updateStatus(String.format("Error %s '%s'.", actionName, note.title));
                        handleActionError(actionName, ex);
                        setControlsEnabled(true); // Re-enable on immediate error
                    }
                });
    }

    private void handleActionError(String actionName, Throwable ex) {
        var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
        System.err.println("Error during " + actionName + ": " + cause.getMessage());
        cause.printStackTrace();
        JOptionPane.showMessageDialog(this, "Error during " + actionName + ":\n" + cause.getMessage(), "Action Error", JOptionPane.ERROR_MESSAGE);
    }

    public void handleSystemUpdate(AttachmentViewModel vm, @Nullable String displayNoteId) {
        if (!isDisplayable()) return;
        var targetNoteIdForList = requireNonNullElse(displayNoteId, GLOBAL_KB_NOTE_ID);
        var sourceModel = noteAttachmentModels.computeIfAbsent(targetNoteIdForList, id -> new DefaultListModel<>());
        updateOrAddModelItem(sourceModel, vm);

        if (vm.isKifBased()) {
            var status = vm.status;
            if (status == AttachmentStatus.RETRACTED || status == AttachmentStatus.EVICTED || status == AttachmentStatus.INACTIVE)
                updateAttachmentStatusInOtherNoteModels(vm.id, status, targetNoteIdForList);
            if (cog != null) {
                cog.context.findAssertionByIdAcrossKbs(vm.id)
                        .ifPresent(assertion -> editorPanel.highlightAffectedNoteText(assertion, status));
            }
        }

        if (note != null && note.id.equals(targetNoteIdForList))
            attachmentPanel.refreshAttachmentDisplay();
        attachmentPanel.updateAttachmentPanelTitle();
    }

    private void updateAttachmentStatusInOtherNoteModels(String attachmentId, AttachmentStatus newStatus, @Nullable String primaryNoteId) {
        noteAttachmentModels.forEach((noteId, model) -> {
            if (!Objects.equals(noteId, primaryNoteId)) { // Only update in *other* models
                var idx = findViewModelIndexById(model, attachmentId);
                if (idx != -1) { // Found in another note's attachments
                    var existingItem = model.getElementAt(idx);
                    if (existingItem.status != newStatus) {
                        // Create a new ViewModel with updated status
                        var updatedItem = new AttachmentViewModel(
                                existingItem.id(), existingItem.noteId(), existingItem.content(),
                                existingItem.attachmentType(), newStatus, existingItem.priority(),
                                existingItem.depth(), existingItem.timestamp(), existingItem.associatedNoteId(),
                                existingItem.kbId(), existingItem.justifications(), existingItem.llmStatus()
                        );
                        model.setElementAt(updatedItem, idx);
                        if (note != null && note.id.equals(noteId)) {
                            // If that other note is currently selected, refresh its display
                            attachmentPanel.refreshAttachmentDisplay();
                        }
                    }
                }
            }
        });
    }


    public void updateLlmItem(String taskId, Cog.TaskStatus status, String content) {
        findViewModelInAnyModel(taskId).ifPresent(entry -> {
            var model = entry.getKey();
            var index = entry.getValue();
            var oldVm = model.getElementAt(index);

            var newContent = oldVm.content();
            // Append content only if it's new and not just status updates
            if (content != null && !content.isBlank()) {
                // Simple check to avoid appending the same status message repeatedly
                // Also avoid appending if the content is just the status itself
                if (!newContent.endsWith(content) && !content.equals(status.toString()) && !(oldVm.llmStatus != status && newContent.endsWith(oldVm.llmStatus.toString()))) {
                    newContent += (newContent.isBlank() ? "" : "\n") + content;
                } else if (newContent.isBlank() && !content.isBlank()) {
                    newContent = content; // If content was blank, just set it
                }
            }


            var newVm = oldVm.withLlmUpdate(status, newContent);
            model.setElementAt(newVm, index);
            if (note != null && note.id.equals(oldVm.noteId()))
                attachmentPanel.refreshAttachmentDisplay();
        });
    }

    private Optional<Map.Entry<DefaultListModel<AttachmentViewModel>, Integer>> findViewModelInAnyModel(String id) {
        return noteAttachmentModels.entrySet().stream()
                .flatMap(entry -> {
                    var model = entry.getValue();
                    var index = findViewModelIndexById(model, id);
                    return (index != -1) ? Stream.of(Map.entry(model, index)) : Stream.empty();
                })
                .findFirst();
    }

    public void clearAllUILists() {
        noteListPanel.clearNotes();
        noteAttachmentModels.clear();
        attachmentPanel.clearAttachments();
        editorPanel.clearEditor();
        note = null;
        setTitle("Cognote - Event Driven");
        attachmentPanel.updateAttachmentPanelTitle();
        updateStatus("Cleared");
    }

    private void clearNoteAttachmentList(String noteId) {
        ofNullable(noteAttachmentModels.get(noteId)).ifPresent(DefaultListModel::clear);
        if (note != null && note.id.equals(noteId)) {
            attachmentPanel.refreshAttachmentDisplay();
            attachmentPanel.updateAttachmentPanelTitle();
        }
    }


    public void addNoteToList(Note note) {
        // Add to the UI list model
        if (noteListPanel.findNoteById(note.id).isEmpty()) {
            //notes.addElement(note);
            // Ensure attachment model exists for this note
            noteAttachmentModels.computeIfAbsent(note.id, id -> new DefaultListModel<>());
        } else {
            // If note already exists, update it in the list model (e.g., status might have changed)
            noteListPanel.updateNoteDisplay(note);
        }
    }

    public void removeNoteFromList(Note note) {
        noteListPanel.remove(note.id);
        noteAttachmentModels.remove(note.id); // Remove the attachment model for the removed note
        // The note selection listener in NoteListPanel handles selecting a new note or clearing the editor
    }

    @Deprecated // UI should not directly find notes, rely on events or CogNote API
    public Optional<Note> findNoteById(String noteId) {
        return noteListPanel.findNoteById(noteId);
    }

    public java.util.List<Note> getAllNotes() {
        return noteListPanel.getAllNotes();
    }

    // This method is now called by setCog to populate the UI list from CogNote's internal list
    public void loadNotes(java.util.List<Note> notes) {
        SwingUtilities.invokeLater(() -> {
            noteListPanel.clearNotes(); // Clear existing UI list
            notes.forEach(this::addNoteToList); // Add notes from CogNote's list
            // Ensure default notes are in the UI list if they weren't loaded
            if (noteListPanel.findNoteById(GLOBAL_KB_NOTE_ID).isEmpty())
                addNoteToList(new Note(GLOBAL_KB_NOTE_ID, GLOBAL_KB_NOTE_TITLE, "Global KB assertions.", Note.Status.IDLE));
            if (noteListPanel.findNoteById(Cog.CONFIG_NOTE_ID).isEmpty())
                addNoteToList(cog != null ? CogNote.createDefaultConfigNote() : new Note(Cog.CONFIG_NOTE_ID, Cog.CONFIG_NOTE_TITLE, "{}", Note.Status.IDLE));
            if (noteListPanel.findNoteById(Cog.TEST_DEFINITIONS_NOTE_ID).isEmpty())
                addNoteToList(cog != null ? CogNote.createDefaultTestDefinitionsNote() : new Note(Cog.TEST_DEFINITIONS_NOTE_ID, Cog.TEST_DEFINITIONS_NOTE_TITLE, "", Note.Status.IDLE));
            if (noteListPanel.findNoteById(Cog.TEST_RESULTS_NOTE_ID).isEmpty())
                addNoteToList(cog != null ? CogNote.createDefaultTestResultsNote() : new Note(Cog.TEST_RESULTS_NOTE_ID, Cog.TEST_RESULTS_NOTE_TITLE, "", Note.Status.IDLE));


            if (!noteListPanel.notes.isEmpty()) {
                // Select the first non-system note, or global KB if none
                var firstSelectable = IntStream.range(0, noteListPanel.notes.getSize())
                        .filter(i -> {
                            var id = noteListPanel.notes.getElementAt(i).id;
                            return !id.equals(Cog.CONFIG_NOTE_ID); // Config note not selectable initially
                        })
                        .findFirst().orElse(noteListPanel.findNoteIndexById(GLOBAL_KB_NOTE_ID).orElse(0)); // Fallback to Global KB

                if (firstSelectable >= 0 && firstSelectable < noteListPanel.notes.getSize()) {
                    noteListPanel.noteList.setSelectedIndex(firstSelectable);
                } else {
                    noteListPanel.noteList.clearSelection();
                    note = null;
                }
            } else {
                noteListPanel.noteList.clearSelection();
                note = null;
            }
            updateUIForSelection(); // Update UI based on the new selection (or lack thereof)
            updateStatus("Notes loaded");
        });
    }

    void noteTitleUpdated(Note note) {
        setTitle("Cognote - " + note.title + " [" + note.id + "]");
        noteListPanel.updateNoteDisplay(note);
        if (cog != null) cog.save(); // Save notes when title changes
    }

    private void displaySupportChain(Assertion startingAssertion) {
        if (cog == null) return;
        var dialog = new JDialog(this, "Support Chain for: " + startingAssertion.id(), false);
        dialog.setSize(600, 400);
        dialog.setLocationRelativeTo(this);
        var model = new DefaultListModel<AttachmentViewModel>();
        var list = new JList<>(model);
        list.setCellRenderer(new AttachmentListCellRenderer());
        list.setFont(MONOSPACED_FONT);
        var visited = new HashSet<String>();
        var queue = new LinkedList<String>();
        queue.offer(startingAssertion.id());
        while (!queue.isEmpty()) {
            var currentId = queue.poll();
            if (currentId == null || !visited.add(currentId)) continue;
            cog.context.findAssertionByIdAcrossKbs(currentId).ifPresent(a -> {
                var displayNoteId = a.sourceNoteId() != null ? a.sourceNoteId() : cog.context.commonSourceNodeId(a.justificationIds());
                model.addElement(AttachmentViewModel.fromAssertion(a, "support", displayNoteId));
                a.justificationIds().forEach(queue::offer);
            });
        }
        List<AttachmentViewModel> sortedList = Collections.list(model.elements());
        sortedList.sort(Comparator.naturalOrder());
        model.clear();
        sortedList.forEach(model::addElement);
        dialog.add(new JScrollPane(list));
        dialog.setVisible(true);
    }

    void updateStatus(String statusText) {
        mainControlPanel.statusLabel.setText("Status: " + statusText);
        if (cog != null) cog.status = statusText; // Update Cog's internal status too
    }

    enum AttachmentStatus {ACTIVE, RETRACTED, EVICTED, INACTIVE}

    public enum AttachmentType {FACT, DERIVED, UNIVERSAL, SKOLEMIZED, SUMMARY, CONCEPT, QUESTION, LLM_INFO, LLM_ERROR, QUERY_SENT, QUERY_RESULT, OTHER}

    @FunctionalInterface
    interface SimpleDocumentListener extends DocumentListener {
        void update(DocumentEvent e);

        @Override
        default void insertUpdate(DocumentEvent e) {
            update(e);
        }

        @Override
        default void removeUpdate(DocumentEvent e) {
            update(e);
        }

        @Override
        default void changedUpdate(DocumentEvent e) {
            update(e);
        }
    }

    static class NoteListCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            var l = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            l.setBorder(new EmptyBorder(5, 10, 5, 10));
            l.setFont(UI_DEFAULT_FONT);
            if (value instanceof Note note) {
                var f = l.getFont();
                var noteID = note.id;
                var statusText = switch (note.status) {
                    case IDLE -> " (Idle)";
                    case ACTIVE -> " (Active)";
                    case PAUSED -> " (Paused)";
                    case COMPLETED -> " (Done)";
                };
                l.setText(note.title + statusText);

                if (Cog.CONFIG_NOTE_ID.equals(noteID) || Cog.TEST_DEFINITIONS_NOTE_ID.equals(noteID) || Cog.TEST_RESULTS_NOTE_ID.equals(noteID)) {
                    f = f.deriveFont(Font.ITALIC);
                    l.setForeground(Color.GRAY);
                } else if (GLOBAL_KB_NOTE_ID.equals(noteID)) {
                    f = f.deriveFont(Font.BOLD);
                } else {
                    // Color based on note status for regular notes
                    l.setForeground(switch (note.status) {
                        case IDLE -> Color.BLACK;
                        case ACTIVE -> Color.BLUE;
                        case PAUSED -> Color.ORANGE;
                        case COMPLETED -> Color.GREEN.darker();
                    });
                }
                l.setFont(f);
            }
            return l;
        }
    }

    public record AttachmentViewModel(String id, @Nullable String noteId, String content, AttachmentType attachmentType,
                                      AttachmentStatus status, double priority, int depth, long timestamp,
                                      @Nullable String associatedNoteId, @Nullable String kbId,
                                      @Nullable Set<String> justifications,
                                      Cog.TaskStatus llmStatus) implements Comparable<AttachmentViewModel> {
        public static AttachmentViewModel fromAssertion(Assertion a, String callbackType, @Nullable String associatedNoteId) {
            return new AttachmentViewModel(a.id(), a.sourceNoteId(), a.toKifString(), determineTypeFromAssertion(a), determineStatusFromCallback(callbackType, a.isActive()), a.pri(), a.derivationDepth(), a.timestamp(), requireNonNullElse(associatedNoteId, a.sourceNoteId()), a.kb(), a.justificationIds(), Cog.TaskStatus.IDLE);
        }

        public static AttachmentViewModel forLlm(String id, @Nullable String noteId, String content, AttachmentType type, long timestamp, @Nullable String kbId, Cog.TaskStatus taskStatus) {
            return new AttachmentViewModel(id, noteId, content, type, AttachmentStatus.ACTIVE, 0.0, -1, timestamp, noteId, kbId, null, taskStatus);
        }

        public static AttachmentViewModel forQuery(String id, @Nullable String noteId, String content, AttachmentType type, long timestamp, @Nullable String kbId) {
            return new AttachmentViewModel(id, noteId, content, type, AttachmentStatus.ACTIVE, 0.0, -1, timestamp, noteId, kbId, null, Cog.TaskStatus.IDLE);
        }

        private static AttachmentType determineTypeFromAssertion(Assertion a) {
            return a.kif().op().map(op -> switch (op) {
                case PRED_NOTE_SUMMARY -> AttachmentType.SUMMARY;
                case PRED_NOTE_CONCEPT -> AttachmentType.CONCEPT;
                case PRED_NOTE_QUESTION -> AttachmentType.QUESTION;
                default -> detTypeFromAssertionType(a);
            }).orElse(detTypeFromAssertionType(a));
        }

        private static AttachmentType detTypeFromAssertionType(Assertion a) {
            return switch (a.type()) {
                case GROUND -> (a.derivationDepth() == 0) ? AttachmentType.FACT : AttachmentType.DERIVED;
                case SKOLEMIZED -> AttachmentType.SKOLEMIZED;
                case UNIVERSAL -> AttachmentType.UNIVERSAL;
            };
        }

        private static AttachmentStatus determineStatusFromCallback(String callbackType, boolean isActive) {
            return switch (callbackType) {
                case "retract" -> AttachmentStatus.RETRACTED;
                case "evict" -> AttachmentStatus.EVICTED;
                case "status-inactive" -> AttachmentStatus.INACTIVE;
                default -> isActive ? AttachmentStatus.ACTIVE : AttachmentStatus.INACTIVE;
            };
        }

        public AttachmentViewModel withStatus(AttachmentStatus newStatus) {
            return new AttachmentViewModel(id, noteId, content, attachmentType, newStatus, priority, depth, timestamp, associatedNoteId, kbId, justifications, llmStatus);
        }

        public AttachmentViewModel withLlmUpdate(Cog.TaskStatus newLlmStatus, String newContent) {
            return new AttachmentViewModel(id, noteId, newContent, attachmentType, status, priority, depth, timestamp, associatedNoteId, kbId, justifications, newLlmStatus);
        }

        public boolean isKifBased() {
            return switch (attachmentType) {
                case FACT, DERIVED, UNIVERSAL, SKOLEMIZED, SUMMARY, CONCEPT, QUESTION -> true;
                default -> false;
            };
        }

        @Override
        public int compareTo(AttachmentViewModel other) {
            var cmp = Integer.compare(status.ordinal(), other.status.ordinal());
            if (cmp != 0) return cmp;
            cmp = Integer.compare(attachmentType.ordinal(), other.attachmentType.ordinal());
            if (cmp != 0) return cmp;
            if (isKifBased()) {
                cmp = Double.compare(other.priority, this.priority);
                if (cmp != 0) return cmp;
                cmp = Integer.compare(this.depth, other.depth);
                if (cmp != 0) return cmp;
            }
            return Long.compare(other.timestamp, this.timestamp);
        }
    }

    static class AttachmentListCellRenderer extends JPanel implements ListCellRenderer<AttachmentViewModel> {
        private final JLabel iconLabel = new JLabel();
        private final JLabel contentLabel = new JLabel();
        private final JLabel detailLabel = new JLabel();
        private final Border activeBorder = new CompoundBorder(new LineBorder(Color.LIGHT_GRAY, 1), new EmptyBorder(3, 5, 3, 5));
        private final Border inactiveBorder = new CompoundBorder(new LineBorder(new Color(240, 240, 240), 1), new EmptyBorder(3, 5, 3, 5));
        private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

        AttachmentListCellRenderer() {
            setLayout(new BorderLayout(5, 0));
            setOpaque(true);
            var textPanel = new JPanel(new BorderLayout());
            textPanel.setOpaque(false);
            textPanel.add(contentLabel, BorderLayout.CENTER);
            textPanel.add(detailLabel, BorderLayout.SOUTH);
            add(iconLabel, BorderLayout.WEST);
            add(textPanel, BorderLayout.CENTER);
            contentLabel.setFont(MONOSPACED_FONT);
            detailLabel.setFont(UI_SMALL_FONT);
            iconLabel.setFont(UI_DEFAULT_FONT.deriveFont(Font.BOLD));
            iconLabel.setBorder(new EmptyBorder(0, 4, 0, 4));
            iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends AttachmentViewModel> list, AttachmentViewModel value, int index, boolean isSelected, boolean cellHasFocus) {
            contentLabel.setText(value.content());
            contentLabel.setFont(MONOSPACED_FONT);
            String iconText;
            Color iconColor, bgColor = Color.WHITE, fgColor = Color.BLACK;
            switch (value.attachmentType) {
                case FACT -> {
                    iconText = "F";
                    iconColor = new Color(0, 128, 0);
                    bgColor = new Color(235, 255, 235);
                }
                case DERIVED -> {
                    iconText = "D";
                    iconColor = Color.BLUE;
                    bgColor = new Color(230, 240, 255);
                    contentLabel.setFont(MONOSPACED_FONT.deriveFont(Font.ITALIC));
                }
                case UNIVERSAL -> {
                    iconText = "∀";
                    iconColor = new Color(0, 0, 128);
                    bgColor = new Color(235, 235, 255);
                }
                case SKOLEMIZED -> {
                    iconText = "∃";
                    iconColor = new Color(139, 69, 19);
                    bgColor = new Color(255, 255, 230);
                    contentLabel.setFont(MONOSPACED_FONT.deriveFont(Font.ITALIC));
                }
                case SUMMARY -> {
                    iconText = "Σ";
                    iconColor = Color.DARK_GRAY;
                    bgColor = new Color(240, 240, 240);
                }
                case CONCEPT -> {
                    iconText = "C";
                    iconColor = Color.DARK_GRAY;
                    bgColor = new Color(240, 240, 240);
                }
                case QUESTION -> {
                    iconText = "?";
                    iconColor = Color.MAGENTA;
                    bgColor = new Color(255, 240, 255);
                }
                case LLM_ERROR -> {
                    iconText = "!";
                    iconColor = Color.RED;
                    bgColor = new Color(255, 230, 230);
                }
                case LLM_INFO -> {
                    iconText = "i";
                    iconColor = Color.GRAY;
                }
                case QUERY_SENT -> {
                    iconText = "->";
                    iconColor = new Color(0, 150, 150);
                }
                case QUERY_RESULT -> {
                    iconText = "<-";
                    iconColor = new Color(0, 150, 150);
                }
                default -> {
                    iconText = "*";
                    iconColor = Color.BLACK;
                }
            }
            var kbDisp = ofNullable(value.kbId()).map(id -> switch (id) {
                case GLOBAL_KB_NOTE_ID -> " [KB:G]";
                case "unknown" -> "";
                default -> " [KB:" + id.replace(Cog.ID_PREFIX_NOTE, "") + "]";
            }).orElse("");
            var assocNoteDisp = ofNullable(value.associatedNoteId()).filter(id -> !id.equals(value.kbId())).map(id -> " (N:" + id.replace(Cog.ID_PREFIX_NOTE, "") + ")").orElse("");
            var timeStr = timeFormatter.format(Instant.ofEpochMilli(value.timestamp()));
            var details = switch (value.attachmentType) {
                case FACT, DERIVED, UNIVERSAL, SKOLEMIZED, SUMMARY, CONCEPT, QUESTION ->
                        String.format("P:%.3f|D:%d|%s%s%s", value.priority(), value.depth(), timeStr, assocNoteDisp, kbDisp);
                case LLM_INFO, LLM_ERROR -> String.format("%s|%s%s", value.llmStatus(), timeStr, kbDisp);
                case QUERY_SENT, QUERY_RESULT -> String.format("%s|%s%s", value.attachmentType, timeStr, kbDisp);
                default -> String.format("%s%s", timeStr, kbDisp);
            };
            detailLabel.setText(details);
            iconLabel.setText(iconText);
            if (value.status != AttachmentStatus.ACTIVE && value.isKifBased()) {
                fgColor = Color.LIGHT_GRAY;
                contentLabel.setText("<html><strike>" + value.content().replace("<", "<").replace(">", ">") + "</strike></html>");
                detailLabel.setText(value.status + "|" + details);
                bgColor = new Color(248, 248, 248);
                setBorder(inactiveBorder);
                iconColor = Color.LIGHT_GRAY;
            } else {
                setBorder(activeBorder);
            }
            if (value.attachmentType == AttachmentType.LLM_INFO || value.attachmentType == AttachmentType.LLM_ERROR) {
                switch (value.llmStatus) {
                    case SENDING, PROCESSING -> {
                        bgColor = new Color(255, 255, 200);
                        iconColor = Color.ORANGE;
                    }
                    case ERROR -> {
                        bgColor = new Color(255, 220, 220);
                        iconColor = Color.RED;
                    }
                    case CANCELLED -> {
                        fgColor = Color.GRAY;
                        contentLabel.setText("<html><strike>" + value.content().replace("<", "<").replace(">", ">") + "</strike></html>");
                        bgColor = new Color(230, 230, 230);
                        iconColor = Color.GRAY;
                    }
                    default -> {
                    }
                }
            }
            if (isSelected) {
                setBackground(list.getSelectionBackground());
                contentLabel.setForeground(list.getSelectionForeground());
                detailLabel.setForeground(list.getSelectionForeground());
                iconLabel.setForeground(list.getSelectionForeground());
            } else {
                setBackground(bgColor);
                contentLabel.setForeground(fgColor);
                detailLabel.setForeground((value.status == AttachmentStatus.ACTIVE || !value.isKifBased()) ? Color.GRAY : Color.LIGHT_GRAY);
                iconLabel.setForeground(iconColor);
            }
            var justList = (value.justifications() == null || value.justifications().isEmpty()) ? "None" : String.join(", ", value.justifications());
            setToolTipText(String.format("<html>ID: %s<br>KB: %s<br>Assoc Note: %s<br>Status: %s<br>LLM Status: %s<br>Type: %s<br>Pri: %.4f<br>Depth: %d<br>Time: %s<br>Just: %s</html>", value.id, value.kbId() != null ? value.kbId() : "N/A", value.associatedNoteId() != null ? value.associatedNoteId() : "N/A", value.status, value.llmStatus, value.attachmentType, value.priority(), value.depth(), Instant.ofEpochMilli(value.timestamp()).toString(), justList));
            return this;
        }
    }

    static class SettingsDialog extends JDialog {
        private final JTextField llmUrlField, llmModelField;
        private final JSpinner kbCapacitySpinner, depthLimitSpinner;
        private final JCheckBox broadcastInputCheckbox;
        private final CogNote cog;

        SettingsDialog(Frame owner, CogNote cog) {
            super(owner, "Settings", true);
            this.cog = cog;
            setSize(500, 300);
            setLocationRelativeTo(owner);
            setLayout(new BorderLayout(10, 10));
            var config = new Cog.Configuration(cog);
            llmUrlField = new JTextField(config.llmApiUrl());
            llmModelField = new JTextField(config.llmModel());
            kbCapacitySpinner = new JSpinner(new SpinnerNumberModel(config.globalKbCapacity(), 1024, 1024 * 1024, 1024));
            depthLimitSpinner = new JSpinner(new SpinnerNumberModel(config.reasoningDepthLimit(), 1, 32, 1));
            broadcastInputCheckbox = new JCheckBox("Broadcast Input Assertions via WebSocket", config.broadcastInputAssertions());
            var formPanel = new JPanel(new GridBagLayout());
            var gbc = new GridBagConstraints();
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.anchor = GridBagConstraints.WEST;
            gbc.insets = new Insets(5, 5, 5, 5);
            formPanel.add(new JLabel("LLM API URL:"), gbc);
            gbc.gridx++;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;
            formPanel.add(llmUrlField, gbc);
            gbc.gridx = 0;
            gbc.gridy++;
            gbc.fill = GridBagConstraints.NONE;
            gbc.weightx = 0;
            formPanel.add(new JLabel("LLM Model:"), gbc);
            gbc.gridx++;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;
            formPanel.add(llmModelField, gbc);
            gbc.gridx = 0;
            gbc.gridy++;
            gbc.fill = GridBagConstraints.NONE;
            gbc.weightx = 0;
            formPanel.add(new JLabel("Global KB Capacity:"), gbc);
            gbc.gridx++;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;
            formPanel.add(kbCapacitySpinner, gbc);
            gbc.gridx = 0;
            gbc.gridy++;
            gbc.fill = GridBagConstraints.NONE;
            gbc.weightx = 0;
            formPanel.add(new JLabel("Reasoning Depth Limit:"), gbc);
            gbc.gridx++;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;
            formPanel.add(depthLimitSpinner, gbc);
            gbc.gridx = 0;
            gbc.gridy++;
            gbc.gridwidth = 2;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            formPanel.add(broadcastInputCheckbox, gbc);
            var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            var saveButton = new JButton("Save");
            var cancelButton = new JButton("Cancel");
            saveButton.addActionListener(e -> saveSettings());
            cancelButton.addActionListener(e -> dispose());
            buttonPanel.add(saveButton);
            buttonPanel.add(cancelButton);
            add(formPanel, BorderLayout.CENTER);
            add(buttonPanel, BorderLayout.SOUTH);
            ((JPanel) getContentPane()).setBorder(new EmptyBorder(10, 10, 10, 10));
        }

        private void saveSettings() {
            var newConfigJson = new JSONObject().put("llmApiUrl", llmUrlField.getText()).put("llmModel", llmModelField.getText()).put("globalKbCapacity", kbCapacitySpinner.getValue()).put("reasoningDepthLimit", depthLimitSpinner.getValue()).put("broadcastInputAssertions", broadcastInputCheckbox.isSelected());
            if (cog.updateConfig(newConfigJson.toString())) dispose();
            else
                JOptionPane.showMessageDialog(this, "Invalid JSON format in Configuration. Please correct.", "Configuration Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    class NoteListPanel extends JPanel {
        public final DefaultListModel<Note> notes = new DefaultListModel<>();
        public final JList<Note> noteList = new JList<>(notes);
        final JPopupMenu noteContextMenu = new JPopupMenu();

        final JMenuItem startNoteItem = new JMenuItem("Start Note");
        final JMenuItem pauseNoteItem = new JMenuItem("Pause Note");
        final JMenuItem completeNoteItem = new JMenuItem("Complete Note");
        final JMenuItem analyzeItem = new JMenuItem("Analyze Note (LLM -> KIF)");
        final JMenuItem enhanceItem = new JMenuItem("Enhance Note (LLM Replace)");
        final JMenuItem summarizeItem = new JMenuItem("Summarize Note (LLM)");
        final JMenuItem conceptsItem = new JMenuItem("Identify Key Concepts (LLM)");
        final JMenuItem questionsItem = new JMenuItem("Generate Questions (LLM)");
        final JMenuItem removeItem = new JMenuItem("Remove Note");

        NoteListPanel() {
            setLayout(new BorderLayout());
            noteList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            noteList.setCellRenderer(new NoteListCellRenderer());

            noteContextMenu.add(startNoteItem);
            noteContextMenu.add(pauseNoteItem);
            noteContextMenu.add(completeNoteItem);
            noteContextMenu.addSeparator();
            Stream.of(analyzeItem, enhanceItem, summarizeItem, conceptsItem, questionsItem).forEach(noteContextMenu::add);
            noteContextMenu.addSeparator();
            noteContextMenu.add(removeItem);

            setupActionListeners();
        }

        private void setupActionListeners() {
            noteList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    saveCurrentNote(); // Save the previously selected note
                    note = noteList.getSelectedValue(); // Update the current note
                    updateUIForSelection(); // Update UI based on the new selection
                }
            });
            startNoteItem.addActionListener(e -> ofNullable(note).ifPresent(n -> cog.startNote(n.id)));
            pauseNoteItem.addActionListener(e -> ofNullable(note).ifPresent(n -> cog.pauseNote(n.id)));
            completeNoteItem.addActionListener(e -> ofNullable(note).ifPresent(n -> cog.completeNote(n.id)));
            analyzeItem.addActionListener(e -> executeNoteTool("text_to_kif", note));
            enhanceItem.addActionListener(e -> executeNoteTool("enhance_note", note));
            summarizeItem.addActionListener(e -> executeNoteTool("summarize", note));
            conceptsItem.addActionListener(e -> executeNoteTool("identify_concepts", note));
            questionsItem.addActionListener(e -> executeNoteTool("generate_questions", note));
            removeItem.addActionListener(e -> removeNoteAction());
            noteList.addMouseListener(createContextMenuMouseListener(noteList, noteContextMenu, this::updateNoteContextMenuState));
        }

        private void updateNoteContextMenuState() {
            var noteSelected = note != null;
            var isEditableNote = noteSelected && !GLOBAL_KB_NOTE_ID.equals(note.id) && !Cog.CONFIG_NOTE_ID.equals(note.id) && !Cog.TEST_DEFINITIONS_NOTE_ID.equals(note.id) && !Cog.TEST_RESULTS_NOTE_ID.equals(note.id);
            var systemRunning = cog != null && cog.running.get() && !cog.isPaused(); // System is running and not paused by user

            startNoteItem.setEnabled(isEditableNote && (note.status == Note.Status.IDLE || note.status == Note.Status.PAUSED));
            pauseNoteItem.setEnabled(isEditableNote && note.status == Note.Status.ACTIVE);
            completeNoteItem.setEnabled(isEditableNote && (note.status == Note.Status.ACTIVE || note.status == Note.Status.PAUSED));

            Stream.of(analyzeItem, enhanceItem, summarizeItem, conceptsItem, questionsItem)
                    .forEach(i -> i.setEnabled(isEditableNote && systemRunning && note.status == Note.Status.ACTIVE)); // LLM tools only on active notes
            removeItem.setEnabled(isEditableNote); // Allow removing non-system notes
        }

        void setControlsEnabled(boolean enabled) {
            noteList.setEnabled(enabled);
            // Context menu items are handled by updateNoteContextMenuState
        }

        private void removeNoteAction() {
            performNoteAction("Removing", "Confirm Removal", "Remove note '%s' and retract all associated assertions?", JOptionPane.WARNING_MESSAGE, note -> ofNullable(cog).ifPresent(s -> s.removeNote(note.id))); // Call CogNote method
        }

        void add(Note note) {
            // Add to the UI list model
            if (findNoteById(note.id).isEmpty()) {
                notes.addElement(note);
            }
        }

        void remove(String noteId) {
            // Remove from the UI list model
            findNoteIndexById(noteId).ifPresent(indexToRemove -> {
                var selectedIdxBeforeRemove = noteList.getSelectedIndex();
                notes.removeElementAt(indexToRemove);

                // If the removed note was selected, select a new one or clear selection
                if (note != null && note.id.equals(noteId)) {
                    note = null; // Clear the reference to the removed note
                    if (!notes.isEmpty()) {
                        var newIndex = Math.max(0, Math.min(selectedIdxBeforeRemove, notes.getSize() - 1));
                        noteList.setSelectedIndex(newIndex); // This will trigger updateUIForSelection
                    } else {
                        noteList.clearSelection(); // This will trigger updateUIForSelection
                    }
                } else {
                    // If a different note was selected, just update the UI for the current selection state
                    updateUIForSelection();
                }
            });
        }

        void updateNoteDisplay(Note note) {
            findNoteIndexById(note.id).ifPresent(index -> {
                if (index >= 0 && index < notes.getSize()) notes.setElementAt(note, index);
            });
        }

        void clearNotes() {
            notes.clear();
        }

        @Deprecated
            // UI should not directly find notes, rely on events or CogNote API
        Optional<Note> findNoteById(String noteId) {
            return Collections.list(notes.elements()).stream().filter(n -> n.id.equals(noteId)).findFirst();
        }

        OptionalInt findNoteIndexById(String noteId) {
            return IntStream.range(0, notes.size()).filter(i -> notes.getElementAt(i).id.equals(noteId)).findFirst();
        }

        List<Note> getAllNotes() {
            return Collections.list(notes.elements());
        }

        // This method is now called by setCog to populate the UI list from CogNote's internal list
        void load(List<Note> notes) {
            this.notes.clear();
            notes.forEach(this::add);
            // Selection and UI update handled by loadNotes in UI class
        }

        private void executeNoteTool(String toolName, Note note) {
            if (cog == null || note == null || GLOBAL_KB_NOTE_ID.equals(note.id) || Cog.CONFIG_NOTE_ID.equals(note.id) || Cog.TEST_DEFINITIONS_NOTE_ID.equals(note.id) || Cog.TEST_RESULTS_NOTE_ID.equals(note.id)) {
                System.err.println("Cannot execute tool '" + toolName + "': System not ready or invalid note selected.");
                return;
            }
            // Check if the note is active before running LLM tools
            if (!cog.context.isActiveNote(note.id)) {
                JOptionPane.showMessageDialog(UI.this, "Note must be Active to run LLM tools.", "Note Status", JOptionPane.WARNING_MESSAGE);
                return;
            }


            cog.tools.get(toolName).ifPresentOrElse(tool -> {
                updateStatus(tool.description() + " for '" + note.title + "'...");
                setControlsEnabled(false); // Disable controls while tool is running

                Map<String, Object> params = Map.of("note_id", note.id);

                tool.execute(params).whenCompleteAsync((result, ex) -> {
                    setControlsEnabled(true); // Re-enable controls
                    updateStatus(cog.status); // Reset status to current system status

                    if (ex != null) {
                        handleActionError(tool.name(), ex);
                    } else {
                        System.out.println("Tool '" + tool.name() + "' completed for note '" + note.id + "'. Result: " + result);
                        if (result instanceof String msg && !msg.isBlank()) {
                            // Optionally display tool result message in status bar or a dialog
                            // updateStatus(tool.name() + " Result: " + msg);
                        }
                    }
                }, SwingUtilities::invokeLater); // Ensure UI updates happen on EDT

            }, () -> System.err.println("Tool '" + toolName + "' not found in registry."));
        }
    }

    private class EditorPanel extends JPanel {
        public final JTextArea edit = new JTextArea();
        final JTextField title = new JTextField();
        private boolean isUpdatingTitleField = false;

        EditorPanel() {
            setLayout(new BorderLayout());
            edit.setLineWrap(true);
            edit.setWrapStyleWord(true);

            var titlePane = new JPanel(new BorderLayout());
            titlePane.add(new JLabel("Title: "), BorderLayout.WEST);
            titlePane.add(title, BorderLayout.CENTER);
            titlePane.setBorder(new EmptyBorder(5, 5, 5, 5));
            add(titlePane, BorderLayout.NORTH);

            add(new JScrollPane(edit), BorderLayout.CENTER);
            setupActionListeners();
        }

        private void setupActionListeners() {
            edit.getDocument().addDocumentListener((SimpleDocumentListener) e -> {
                // Text changes are saved when focus is lost or window closes
                if (note != null && !GLOBAL_KB_NOTE_ID.equals(note.id) && !Cog.TEST_RESULTS_NOTE_ID.equals(note.id)) { // Don't save text changes for Test Results note
                    note.text = edit.getText();
                    // Don't save immediately on every keystroke, save on focus lost or explicit save
                }
            });
            edit.addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent e) {
                    saveCurrentNote(); // Save when editor loses focus
                }
            });
            title.getDocument().addDocumentListener((SimpleDocumentListener) e -> {
                if (!isUpdatingTitleField && note != null && !GLOBAL_KB_NOTE_ID.equals(note.id) && !Cog.CONFIG_NOTE_ID.equals(note.id) && !Cog.TEST_DEFINITIONS_NOTE_ID.equals(note.id) && !Cog.TEST_RESULTS_NOTE_ID.equals(note.id)) {
                    note.title = title.getText();
                    noteTitleUpdated(note); // Update UI list display and save
                }
            });
        }

        void updateForSelection(Note note, boolean isGlobal, boolean isConfig) {
            var noteSelected = (note != null);
            var isEditableNoteContent = noteSelected && !isGlobal && !Cog.TEST_RESULTS_NOTE_ID.equals(note.id); // Global KB and Test Results text are not editable
            var isEditableNoteTitle = noteSelected && !isGlobal && !isConfig && !Cog.TEST_DEFINITIONS_NOTE_ID.equals(note.id) && !Cog.TEST_RESULTS_NOTE_ID.equals(note.id); // System note titles not editable
            isUpdatingTitleField = true; // Prevent document listener from firing during update
            title.setText(noteSelected ? note.title : "");
            title.setEditable(isEditableNoteTitle);
            title.setEnabled(noteSelected && !isGlobal); // Title field enabled if any non-global note selected
            isUpdatingTitleField = false;
            edit.setText(noteSelected ? note.text : "");
            edit.setEditable(isEditableNoteContent);
            edit.setEnabled(noteSelected && !isGlobal); // Editor enabled if non-global note selected
            edit.getHighlighter().removeAllHighlights(); // Clear highlights on selection change
            edit.setCaretPosition(0); // Move caret to start
        }

        void setControlsEnabled(boolean enabled, boolean noteSelected, boolean isGlobal, boolean isConfig) {
            var isEditableNoteContent = noteSelected && !isGlobal && !Cog.TEST_RESULTS_NOTE_ID.equals(note.id);
            var isEditableNoteTitle = noteSelected && !isGlobal && !isConfig && !Cog.TEST_DEFINITIONS_NOTE_ID.equals(note.id) && !Cog.TEST_RESULTS_NOTE_ID.equals(note.id);

            title.setEnabled(enabled && noteSelected && !isGlobal);
            title.setEditable(enabled && isEditableNoteTitle);
            edit.setEnabled(enabled && noteSelected && !isGlobal);
            edit.setEditable(enabled && isEditableNoteContent);
        }

        void clearEditor() {
            title.setText("");
            edit.setText("");
            title.setEditable(false);
            title.setEnabled(false);
            edit.setEditable(false);
            edit.setEnabled(false);
            edit.getHighlighter().removeAllHighlights();
        }

        void highlightAffectedNoteText(Assertion assertion, AttachmentStatus status) {
            if (cog == null || note == null || GLOBAL_KB_NOTE_ID.equals(note.id) || Cog.CONFIG_NOTE_ID.equals(note.id) || Cog.TEST_RESULTS_NOTE_ID.equals(note.id))
                return; // Don't highlight in system notes or global KB
            // Determine which note this assertion is primarily associated with for UI display
            var displayNoteId = assertion.sourceNoteId();
            if (displayNoteId == null && assertion.derivationDepth() > 0)
                displayNoteId = cog.context.commonSourceNodeId(assertion.justificationIds());
            // If still no specific note and it's not in the global KB, use its KB ID (which might be a note ID)
            if (displayNoteId == null && !GLOBAL_KB_NOTE_ID.equals(assertion.kb())) displayNoteId = assertion.kb();

            // Only highlight if the currently selected note is the one associated with the assertion
            if (note.id.equals(displayNoteId)) {
                var searchTerm = extractHighlightTerm(assertion.kif());
                if (searchTerm == null || searchTerm.isBlank()) return;
                var highlighter = edit.getHighlighter();
                var painter = switch (status) {
                    case ACTIVE -> new DefaultHighlightPainter(new Color(200, 255, 200)); // Light Green
                    case RETRACTED, INACTIVE -> new DefaultHighlightPainter(new Color(255, 200, 200)); // Light Red
                    case EVICTED -> new DefaultHighlightPainter(Color.LIGHT_GRAY); // Light Gray
                };
                try {
                    var text = edit.getText();
                    // Find all occurrences of the search term (case-insensitive)
                    var pos = text.toLowerCase().indexOf(searchTerm.toLowerCase());
                    while (pos >= 0) {
                        highlighter.addHighlight(pos, pos + searchTerm.length(), painter);
                        pos = text.toLowerCase().indexOf(searchTerm.toLowerCase(), pos + 1);
                    }
                } catch (BadLocationException e) { /* Ignore */ }
            }
        }

        // Extracts a suitable term from KIF for highlighting in text
        private String extractHighlightTerm(Term.Lst kif) {
            // Look for the first non-operator, non-ID atom with length > 2
            return kif.terms.stream()
                    .filter(Term.Atom.class::isInstance)
                    .map(Term.Atom.class::cast)
                    .map(Term.Atom::value)
                    .filter(s -> s.length() > 2)
                    .filter(s -> !Set.of(Logic.KIF_OP_AND, Logic.KIF_OP_OR, Logic.KIF_OP_NOT, Logic.KIF_OP_IMPLIES, Logic.KIF_OP_EQUIV, Logic.KIF_OP_EQUAL, Logic.KIF_OP_EXISTS, Logic.KIF_OP_FORALL, PRED_NOTE_SUMMARY, PRED_NOTE_CONCEPT, PRED_NOTE_QUESTION).contains(s))
                    .filter(s -> !s.startsWith(Cog.ID_PREFIX_NOTE) && !s.startsWith(Cog.ID_PREFIX_LLM_RESULT) && !s.startsWith(Logic.ID_PREFIX_SKOLEM_CONST) && !s.startsWith(Logic.ID_PREFIX_SKOLEM_FUNC))
                    .findFirst().orElse(null);
        }
    }

    private class AttachmentPanel extends JPanel {
        final JList<AttachmentViewModel> attachmentList = new JList<>();
        final JTextField filterField = new JTextField();
        final JTextField queryInputField = new JTextField();
        final JButton queryButton = new JButton("Query KB");
        final JPopupMenu itemContextMenu = new JPopupMenu();
        final JMenuItem retractItem = new JMenuItem("Delete Attachment"), showSupportItem = new JMenuItem("Show Support Chain"), queryItem = new JMenuItem("Query This Pattern"),
                cancelLlmItem = new JMenuItem("Cancel LLM Task"), insertSummaryItem = new JMenuItem("Insert Summary into Note"),
                answerQuestionItem = new JMenuItem("Answer Question in Note"), findRelatedConceptsItem = new JMenuItem("Find Related Notes (Concept)");

        AttachmentPanel() {
            setLayout(new BorderLayout(0, 5));
            setBorder(BorderFactory.createTitledBorder("Attachments"));
            setPreferredSize(new Dimension(0, 250));
            attachmentList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            attachmentList.setCellRenderer(new AttachmentListCellRenderer());
            filterField.setToolTipText("Filter attachments (case-insensitive contains)");
            queryInputField.setToolTipText("Enter KIF query pattern and press Enter or click Query");
            var filterQueryPanel = new JPanel(new BorderLayout(5, 0));
            filterQueryPanel.add(filterField, BorderLayout.CENTER);
            var queryActionPanel = new JPanel(new BorderLayout(5, 0));
            queryActionPanel.add(queryInputField, BorderLayout.CENTER);
            queryActionPanel.add(queryButton, BorderLayout.EAST);
            filterQueryPanel.add(queryActionPanel, BorderLayout.SOUTH);
            filterQueryPanel.setBorder(new EmptyBorder(5, 0, 5, 0));
            add(filterQueryPanel, BorderLayout.NORTH);
            add(new JScrollPane(attachmentList), BorderLayout.CENTER);
            Stream.of(retractItem, showSupportItem, queryItem, cancelLlmItem, insertSummaryItem, answerQuestionItem, findRelatedConceptsItem).forEach(item -> {
                if (item != retractItem) itemContextMenu.addSeparator();
                itemContextMenu.add(item);
            });
            setupActionListeners();
        }

        private void setupActionListeners() {
            filterField.getDocument().addDocumentListener((SimpleDocumentListener) e -> refreshAttachmentDisplay());
            queryButton.addActionListener(e -> askQueryAction());
            queryInputField.addActionListener(e -> askQueryAction());
            retractItem.addActionListener(e -> retractSelectedAttachmentAction());
            showSupportItem.addActionListener(e -> showSupportAction());
            queryItem.addActionListener(e -> querySelectedAttachmentAction());
            cancelLlmItem.addActionListener(e -> cancelSelectedLlmTaskAction());
            insertSummaryItem.addActionListener(e -> insertSummaryAction());
            answerQuestionItem.addActionListener(e -> answerQuestionAction());
            findRelatedConceptsItem.addActionListener(e -> findRelatedConceptsAction());
            MouseListener itemMouseListener = createContextMenuMouseListener(attachmentList, itemContextMenu, this::updateItemContextMenuState);
            MouseListener itemDblClickListener = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) showSupportAction(); // Double-click shows support chain
                }
            };
            attachmentList.addMouseListener(itemMouseListener);
            attachmentList.addMouseListener(itemDblClickListener);
        }

        void updateForSelection(Note note) {
            filterField.setText(""); // Clear filter on note change
            queryInputField.setText(""); // Clear query field on note change
            refreshAttachmentDisplay(); // Refresh list for the new note
            updateAttachmentPanelTitle(); // Update title based on new note's attachments
        }

        void setControlsEnabled(boolean enabled, boolean noteSelected, boolean systemReady) {
            attachmentList.setEnabled(enabled && noteSelected);
            filterField.setEnabled(enabled && noteSelected);
            queryInputField.setEnabled(enabled && noteSelected && systemReady);
            queryButton.setEnabled(enabled && noteSelected && systemReady);
            // Context menu items are handled by updateItemContextMenuState
        }

        void clearAttachments() {
            attachmentList.setModel(new DefaultListModel<>());
        }

        private void findRelatedConceptsAction() {
            getSelectedAttachmentViewModel().filter(vm ->
                    vm.attachmentType == AttachmentType.CONCEPT && vm.status == AttachmentStatus.ACTIVE).ifPresent(vm ->
                    JOptionPane.showMessageDialog(UI.this, "Find related notes for concept '" + extractContentFromKif(vm.content()) + "' NYI.", "Find Related Notes", JOptionPane.INFORMATION_MESSAGE));
        }


        void refreshAttachmentDisplay() {
            if (note == null) {
                clearAttachments();
                return;
            }
            var filterText = filterField.getText().trim().toLowerCase();
            List<AttachmentViewModel> allRelevantItems = new ArrayList<>();

            // Get items associated with the currently selected note (sourced from it or committed to its KB)
            var currentNoteModel = noteAttachmentModels.get(note.id);
            if (currentNoteModel != null) {
                allRelevantItems.addAll(Collections.list(currentNoteModel.elements()));
            }

            // If the selected note is NOT the global KB, also include items from the Global KB model
            if (!GLOBAL_KB_NOTE_ID.equals(note.id)) {
                var globalKbModel = noteAttachmentModels.get(Cog.GLOBAL_KB_NOTE_ID);
                if (globalKbModel != null) {
                    // Add items from the global KB model, avoiding duplicates if any somehow exist
                    Collections.list(globalKbModel.elements()).stream()
                            .filter(vm -> allRelevantItems.stream().noneMatch(existing -> existing.id.equals(vm.id))) // Avoid duplicates
                            .forEach(allRelevantItems::add);
                }
            }
            // Note: Attachments associated with *other* notes are not shown here unless they are also in the global KB.
            // This keeps the attachment list focused on the current note and global context.


            // Sort and filter the combined list
            allRelevantItems.sort(Comparator.naturalOrder());
            var displayModel = new DefaultListModel<AttachmentViewModel>();
            allRelevantItems.stream()
                    .filter(vm -> filterText.isEmpty() || vm.content().toLowerCase().contains(filterText))
                    .forEach(displayModel::addElement);

            // Set the display model
            attachmentList.setModel(displayModel);
        }


        void updateAttachmentPanelTitle() {
            // Count attachments only for the currently selected note's model (excluding global KB items shown when a note is selected)
            var count = (note != null) ? noteAttachmentModels.getOrDefault(note.id, new DefaultListModel<>()).getSize() : 0;
            ((TitledBorder) getBorder()).setTitle("Attachments" + (count > 0 ? " (" + count + ")" : ""));
            repaint();
        }

        private void updateItemContextMenuState() {
            var vm = attachmentList.getSelectedValue();
            var isSelected = vm != null;
            var isKif = isSelected && vm.isKifBased();
            var isActiveKif = isKif && vm.status == AttachmentStatus.ACTIVE;
            var isLlmTask = isSelected && vm.attachmentType.name().startsWith("LLM_");
            var isCancelableLlm = isLlmTask && (vm.llmStatus == Cog.TaskStatus.SENDING || vm.llmStatus == Cog.TaskStatus.PROCESSING);
            var isSummary = isActiveKif && vm.attachmentType == AttachmentType.SUMMARY;
            var isQuestion = isActiveKif && vm.attachmentType == AttachmentType.QUESTION;
            var isConcept = isActiveKif && vm.attachmentType == AttachmentType.CONCEPT;
            var systemRunning = cog != null && cog.running.get() && !cog.isPaused(); // System is running and not paused by user


            // Enable/disable menu items based on selected attachment properties and system state
            retractItem.setEnabled(isActiveKif && systemRunning); // Can only retract if system is running
            showSupportItem.setEnabled(isKif && cog != null); // Need cog to show support
            queryItem.setEnabled(isKif && systemRunning); // Can only query if system is running
            cancelLlmItem.setEnabled(isCancelableLlm && cog != null); // Need cog to cancel LLM task
            insertSummaryItem.setEnabled(isSummary && note != null && !GLOBAL_KB_NOTE_ID.equals(note.id) && !Cog.CONFIG_NOTE_ID.equals(note.id) && !Cog.TEST_DEFINITIONS_NOTE_ID.equals(note.id) && !Cog.TEST_RESULTS_NOTE_ID.equals(note.id)); // Can only insert into editable notes
            answerQuestionItem.setEnabled(isQuestion && note != null && !GLOBAL_KB_NOTE_ID.equals(note.id) && !Cog.CONFIG_NOTE_ID.equals(note.id) && !Cog.TEST_DEFINITIONS_NOTE_ID.equals(note.id) && !Cog.TEST_RESULTS_NOTE_ID.equals(note.id)); // Can only answer in editable notes
            findRelatedConceptsItem.setEnabled(isConcept && cog != null && systemRunning); // Need cog for concept search (NYI) and system running
        }

        private Optional<AttachmentViewModel> getSelectedAttachmentViewModel() {
            return Optional.ofNullable(attachmentList.getSelectedValue());
        }

        private void retractSelectedAttachmentAction() {
            getSelectedAttachmentViewModel().filter(AttachmentViewModel::isKifBased).filter(vm -> vm.status == AttachmentStatus.ACTIVE).map(AttachmentViewModel::id).ifPresent(id -> {
                if (cog == null) return;
                cog.tools.get("retract_assertion").ifPresentOrElse(tool -> {
                    Map<String, Object> params = new HashMap<>();
                    params.put("target", id);
                    params.put("type", "BY_ID");
                    // Pass the KB ID the assertion belongs to, not necessarily the currently selected note's ID
                    // The assertion's KB ID is stored in the ViewModel
                    ofNullable(getSelectedAttachmentViewModel().get().kbId()).ifPresent(kbId -> params.put("target_kb_id", kbId));


                    tool.execute(params).whenCompleteAsync((result, ex) -> {
                        System.out.println("WS Retract tool result: " + result);
                        if (ex != null) System.err.println("WS Retract tool error: " + ex.getMessage());
                        // UI update happens via RetractedEvent
                    }, SwingUtilities::invokeLater);

                }, () -> System.err.println("Tool 'retract_assertion' not found."));
            });
        }

        private void showSupportAction() {
            getSelectedAttachmentViewModel().filter(AttachmentViewModel::isKifBased).map(AttachmentViewModel::id).flatMap(id -> cog.context.findAssertionByIdAcrossKbs(id)).ifPresent(UI.this::displaySupportChain);
        }

        private void querySelectedAttachmentAction() {
            getSelectedAttachmentViewModel().filter(AttachmentViewModel::isKifBased).ifPresent(vm -> queryInputField.setText(vm.content()));
        }

        private void cancelSelectedLlmTaskAction() {
            getSelectedAttachmentViewModel().filter(vm -> vm.attachmentType.name().startsWith("LLM_")).ifPresent(vm -> {
                if (cog == null) return;
                ofNullable(cog.lm.activeLlmTasks.remove(vm.id)).ifPresent(future -> future.cancel(true));
                // Update UI immediately
                updateLlmItem(vm.id, Cog.TaskStatus.CANCELLED, "Task cancelled by user.");
            });
        }

        private void insertSummaryAction() {
            getSelectedAttachmentViewModel().filter(vm -> vm.attachmentType == AttachmentType.SUMMARY && vm.status == AttachmentStatus.ACTIVE).ifPresent(vm -> {
                if (note == null || GLOBAL_KB_NOTE_ID.equals(note.id) || Cog.CONFIG_NOTE_ID.equals(note.id) || Cog.TEST_DEFINITIONS_NOTE_ID.equals(note.id) || Cog.TEST_RESULTS_NOTE_ID.equals(note.id))
                    return; // Should be disabled by menu state, but defensive
                try {
                    var doc = editorPanel.edit.getDocument();
                    doc.insertString(editorPanel.edit.getCaretPosition(), extractContentFromKif(vm.content()) + "\n", null);
                    saveCurrentNote(); // Save the note after insertion
                } catch (BadLocationException e) {
                    System.err.println("Error inserting summary: " + e.getMessage());
                }
            });
        }

        private void answerQuestionAction() {
            getSelectedAttachmentViewModel().filter(vm -> vm.attachmentType == AttachmentType.QUESTION && vm.status == AttachmentStatus.ACTIVE).ifPresent(vm -> {
                if (note == null || GLOBAL_KB_NOTE_ID.equals(note.id) || Cog.CONFIG_NOTE_ID.equals(note.id) || Cog.TEST_DEFINITIONS_NOTE_ID.equals(note.id) || Cog.TEST_RESULTS_NOTE_ID.equals(note.id))
                    return; // Should be disabled by menu state, but defensive
                var qText = extractContentFromKif(vm.content());
                var answer = JOptionPane.showInputDialog(UI.this, "Q: " + qText + "\n\nEnter your answer:", "Answer Question", JOptionPane.PLAIN_MESSAGE);
                if (answer != null && !answer.isBlank()) {
                    try {
                        var doc = editorPanel.edit.getDocument();
                        doc.insertString(doc.getLength(), String.format("\n\nQ: %s\nA: %s\n", qText, answer.trim()), null);
                        saveCurrentNote(); // Save the note after appending Q/A
                    } catch (BadLocationException e) {
                        System.err.println("Error appending Q/A: " + e.getMessage());
                    }
                }
            });
        }

        private void askQueryAction() {
            if (cog == null) return;
            var queryText = queryInputField.getText().trim();
            if (queryText.isBlank()) return;

            cog.tools.get("run_query").ifPresentOrElse(tool -> {
                Map<String, Object> params = new HashMap<>();
                params.put("kif_pattern", queryText);
                // If a specific note is selected, target its KB, otherwise use global
                if (note != null && !GLOBAL_KB_NOTE_ID.equals(note.id)) {
                    params.put("target_kb_id", note.id);
                } else {
                    params.put("target_kb_id", GLOBAL_KB_NOTE_ID);
                }

                queryInputField.setText(""); // Clear input field immediately

                tool.execute(params).whenCompleteAsync((result, ex) -> {
                    if (ex != null) {
                        System.err.println("Error executing run_query tool: " + ex.getMessage());
                        JOptionPane.showMessageDialog(UI.this, "Query Error: " + ex.getMessage(), "Tool Execution Error", JOptionPane.ERROR_MESSAGE);
                    } else {
                        System.out.println("Query tool result:\n" + result);
                        // Display result in a dialog
                        JOptionPane.showMessageDialog(UI.this, result, "Query Result", JOptionPane.INFORMATION_MESSAGE);
                        // UI update for query sent/result happens via events
                    }
                }, SwingUtilities::invokeLater); // Ensure dialog and UI updates happen on EDT

            }, () -> {
                System.err.println("Tool 'run_query' not found.");
                JOptionPane.showMessageDialog(UI.this, "Query tool not available.", "Tool Error", JOptionPane.ERROR_MESSAGE);
            });
        }
    }

    class MainControlPanel extends JPanel {
        public final JLabel statusLabel = new JLabel("Status: Initializing...");
        final JButton addButton = new JButton("Add Note"), pauseResumeButton = new JButton("Start"), clearAllButton = new JButton("Clear All"); // Button text defaults to "Start"

        MainControlPanel() {
            setLayout(new BorderLayout());
            setBorder(new EmptyBorder(5, 5, 5, 5));
            var buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
            Stream.of(addButton, pauseResumeButton, clearAllButton).forEach(buttonPanel::add);
            statusLabel.setBorder(new EmptyBorder(5, 10, 5, 10));
            statusLabel.setHorizontalAlignment(SwingConstants.LEFT);
            add(buttonPanel, BorderLayout.WEST);
            add(statusLabel, BorderLayout.CENTER);
            setupActionListeners();
        }

        private void setupActionListeners() {
            addButton.addActionListener(e -> addNoteAction());
            pauseResumeButton.addActionListener(e -> togglePauseAction());
            clearAllButton.addActionListener(e -> clearAllAction());
        }

        void updatePauseResumeButton() {
            if (cog == null || !cog.running.get()) {
                pauseResumeButton.setText("Stopped");
                pauseResumeButton.setEnabled(false);
            } else if (cog.isPaused()) {
                // Check if it's the initial paused state or user-paused
                if ("Paused (Ready to Start)".equals(cog.status)) {
                    pauseResumeButton.setText("Start");
                } else {
                    pauseResumeButton.setText("Resume");
                }
                pauseResumeButton.setEnabled(true);
            } else {
                pauseResumeButton.setText("Pause");
                pauseResumeButton.setEnabled(true);
            }
        }

        void setControlsEnabled(boolean enabled) {
            boolean systemExists = cog != null, systemRunning = systemExists && cog.running.get();
            addButton.setEnabled(enabled && systemExists);
            clearAllButton.setEnabled(enabled && systemExists);
            // Pause/Resume/Start button enabled state is handled by updatePauseResumeButton
            updatePauseResumeButton();
        }

        private void addNoteAction() {
            ofNullable(JOptionPane.showInputDialog(UI.this, "Enter note title:", "New Note", JOptionPane.PLAIN_MESSAGE)).map(String::trim).filter(Predicate.not(String::isEmpty)).ifPresent(title -> {
                var newNote = new Note(Cog.id(Cog.ID_PREFIX_NOTE), title, "", Note.Status.IDLE); // New notes start as IDLE
                if (cog != null) {
                    cog.addNote(newNote); // Add note via CogNote
                    // UI update happens via AddedEvent
                }
            });
        }

        private void togglePauseAction() {
            ofNullable(cog).ifPresent(r -> {
                r.setPaused(!r.isPaused());
                updatePauseResumeButton();
                // Status update is handled by Cog.setPaused and SystemStatusEvent
                setControlsEnabled(true); // Re-enable controls after pause/resume
            });
        }

        private void clearAllAction() {
            if (cog != null && JOptionPane.showConfirmDialog(UI.this, "Clear all notes (except config, tests, global KB), assertions, and rules? This cannot be undone.", "Confirm Clear All", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
                cog.clear(); // Clear via CogNote
                // Status update and UI clear happens via events
            }
        }
    }

    private class MenuBarHandler {
        final JMenuItem settingsItem = new JMenuItem("Settings..."), viewRulesItem = new JMenuItem("View Rules"), askQueryItem = new JMenuItem("Ask Query..."), runTestsItem = new JMenuItem("Run Tests");
        private final JMenuBar menuBar = new JMenuBar();

        MenuBarHandler() {
            var fm = new JMenu("File");
            var em = new JMenu("Edit");
            var vm = new JMenu("View");
            var qm = new JMenu("Query");
            var tm = new JMenu("Tests"); // New Tests menu
            var hm = new JMenu("Help");

            settingsItem.addActionListener(e -> showSettingsDialog());
            fm.add(settingsItem);

            viewRulesItem.addActionListener(e -> viewRulesAction());
            vm.add(viewRulesItem);

            askQueryItem.addActionListener(e -> attachmentPanel.queryInputField.requestFocusInWindow());
            qm.add(askQueryItem);

            runTestsItem.addActionListener(e -> runTestsAction());
            tm.add(runTestsItem); // Add Run Tests to Tests menu


            Stream.of(fm, em, vm, qm, tm, hm).forEach(menuBar::add); // Add Tests menu
        }

        JMenuBar getMenuBar() {
            return menuBar;
        }

        private void showSettingsDialog() {
            if (cog == null) return;
            new SettingsDialog(UI.this, cog).setVisible(true);
        }

        private void viewRulesAction() {
            if (cog == null) return;
            var rules = cog.context.rules().stream().sorted(Comparator.comparing(Rule::id)).toList();
            if (rules.isEmpty()) {
                JOptionPane.showMessageDialog(UI.this, "<No rules defined>", "Current Rules", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            var ruleListModel = new DefaultListModel<Rule>();
            rules.forEach(ruleListModel::addElement);
            var ruleJList = createRuleList(ruleListModel);
            var scrollPane = new JScrollPane(ruleJList);
            scrollPane.setPreferredSize(new Dimension(700, 400));
            var removeButton = new JButton("Remove Selected Rule");
            removeButton.setFont(UI_DEFAULT_FONT);
            removeButton.setEnabled(false);
            ruleJList.addListSelectionListener(ev -> removeButton.setEnabled(ruleJList.getSelectedIndex() != -1));
            removeButton.addActionListener(ae -> {
                var selectedRule = ruleJList.getSelectedValue();
                if (selectedRule != null && JOptionPane.showConfirmDialog(UI.this, "Remove rule: " + selectedRule.id() + "?", "Confirm Rule Removal", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
                    cog.events.emit(new Cog.RetractionRequestEvent(selectedRule.form().toKif(), Logic.RetractionType.BY_RULE_FORM, "UI-RuleView", null));
                    // Rule removal from context happens via RetractionPlugin, UI list update is manual here
                    ruleListModel.removeElement(selectedRule);
                }
            });
            var panel = new JPanel(new BorderLayout(0, 10));
            panel.add(scrollPane, BorderLayout.CENTER);
            panel.add(removeButton, BorderLayout.SOUTH);
            JOptionPane.showMessageDialog(UI.this, panel, "Current Rules", JOptionPane.PLAIN_MESSAGE);
        }

        private void runTestsAction() {
            if (cog == null || !cog.running.get() || cog.isPaused()) {
                JOptionPane.showMessageDialog(UI.this, "System must be running to run tests.", "System Status", JOptionPane.WARNING_MESSAGE);
                return;
            }
            // Emit the event to trigger the TestRunnerPlugin
            cog.events.emit(new Cog.RunTestsEvent());
            updateStatus("Running tests...");
        }
    }
}
