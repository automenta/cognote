package dumb.cognote;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.*;
import java.awt.event.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static dumb.cognote.Logic.*;
import static java.util.Objects.requireNonNullElse;
import static java.util.Optional.ofNullable;

public class UI extends JFrame {
    private static final int UI_FONT_SIZE = 16;
    public static final Font MONOSPACED_FONT = new Font(Font.MONOSPACED, Font.PLAIN, UI_FONT_SIZE - 2);
    public static final Font UI_DEFAULT_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, UI_FONT_SIZE);
    public static final Font UI_SMALL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, UI_FONT_SIZE - 4);
    public final NoteListPanel noteListPanel;
    public final EditorPanel editorPanel;
    public final MainControlPanel mainControlPanel;
    final Map<String, DefaultListModel<AttachmentViewModel>> noteAttachmentModels = new ConcurrentHashMap<>();
    private final AttachmentPanel attachmentPanel;
    private final MenuBarHandler menuBarHandler;
    Cog.Note currentNote = null;
    private Cog cog;

    public UI(@Nullable Cog cog) {
        super("Cognote - Event Driven");
        this.cog = cog;
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
        updateUIForSelection();
        applyFonts();
    }

    private static void handleLlmFailure(Throwable ex, Cog.Note contextNote) {
        var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
        if (!(cause instanceof CancellationException)) {
            var action = (cause instanceof KifParser.ParseException) ? "KIF Parse Error" : "LLM Interaction Failed";
            System.err.println("LLM Failure for note " + contextNote.id + ": " + action + ": " + cause.getMessage());
        } else {
            System.out.println("LLM task cancelled for note " + contextNote.id);
        }
    }

    private static void updateOrAddModelItem(DefaultListModel<AttachmentViewModel> sourceModel, AttachmentViewModel newItem) {
        var existingIndex = findViewModelIndexById(sourceModel, newItem.id);
        if (existingIndex != -1) {
            var existingItem = sourceModel.getElementAt(existingIndex);
            if (newItem.status != existingItem.status || !newItem.content().equals(existingItem.content()) ||
                    newItem.priority() != existingItem.priority() || !Objects.equals(newItem.associatedNoteId(), existingItem.associatedNoteId()) ||
                    !Objects.equals(newItem.kbId(), existingItem.kbId()) || newItem.llmStatus != existingItem.llmStatus ||
                    newItem.attachmentType != existingItem.attachmentType) {
                sourceModel.setElementAt(newItem, existingIndex);
            }
        } else if (newItem.status == AttachmentStatus.ACTIVE || !newItem.isKifBased()) {
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
            if (terms.size() == 1 && terms.getFirst() instanceof Logic.KifList list && list.size() >= 4 && list.get(3) instanceof KifAtom(
                    var value
            )) {
                return value;
            }
        } catch (KifParser.ParseException e) {
            System.err.println("Failed to parse KIF for content extraction: " + kifString);
        }
        return kifString;
    }

    private static @NotNull JList<Logic.Rule> createRuleList(DefaultListModel<Logic.Rule> ruleListModel) {
        var ruleJList = new JList<>(ruleListModel);
        ruleJList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ruleJList.setFont(MONOSPACED_FONT);
        ruleJList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                var lbl = (JLabel) super.getListCellRendererComponent(l, v, i, s, f);
                if (v instanceof Logic.Rule r)
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

    void setSystemReference(Cog system) {
        this.cog = system;
        updateUIForSelection();
        mainControlPanel.updatePauseResumeButton();
    }

    private void applyFonts() {
        Stream.of(
                noteListPanel.noteList, editorPanel.noteEditor, editorPanel.noteTitleField,
                attachmentPanel.filterField, attachmentPanel.queryInputField, attachmentPanel.queryButton,
                mainControlPanel.addButton, mainControlPanel.pauseResumeButton, mainControlPanel.clearAllButton,
                mainControlPanel.statusLabel, menuBarHandler.settingsItem, menuBarHandler.viewRulesItem,
                menuBarHandler.askQueryItem, noteListPanel.analyzeItem, noteListPanel.enhanceItem,
                noteListPanel.summarizeItem, noteListPanel.keyConceptsItem, noteListPanel.generateQuestionsItem,
                noteListPanel.removeItem, attachmentPanel.retractItem, attachmentPanel.showSupportItem,
                attachmentPanel.queryItem, attachmentPanel.cancelLlmItem, attachmentPanel.insertSummaryItem,
                attachmentPanel.answerQuestionItem, attachmentPanel.findRelatedConceptsItem
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
                saveCurrentNote();
                ofNullable(cog).ifPresent(Cog::stopSystem);
                dispose();
                System.exit(0);
            }
        });
    }

    private void saveCurrentNote() {
        ofNullable(currentNote).filter(n -> !Cog.GLOBAL_KB_NOTE_ID.equals(n.id)).ifPresent(n -> {
            n.text = editorPanel.noteEditor.getText();
            if (!Cog.CONFIG_NOTE_ID.equals(n.id)) {
                n.title = editorPanel.noteTitleField.getText();
            } else if (cog != null && !cog.updateConfig(n.text)) {
                JOptionPane.showMessageDialog(this, "Invalid JSON format in Configuration note. Changes not applied.", "Configuration Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    void updateUIForSelection() {
        var noteSelected = (currentNote != null);
        var isGlobalSelected = noteSelected && Cog.GLOBAL_KB_NOTE_ID.equals(currentNote.id);
        var isConfigSelected = noteSelected && Cog.CONFIG_NOTE_ID.equals(currentNote.id);

        editorPanel.updateForSelection(currentNote, isGlobalSelected, isConfigSelected);
        attachmentPanel.updateForSelection(currentNote);
        mainControlPanel.updatePauseResumeButton();

        if (noteSelected) {
            setTitle("Cognote - " + currentNote.title + (isGlobalSelected || isConfigSelected ? "" : " [" + currentNote.id + "]"));
            SwingUtilities.invokeLater(() -> {
                if (!isGlobalSelected && !isConfigSelected) editorPanel.noteEditor.requestFocusInWindow();
                else if (!isGlobalSelected) editorPanel.noteEditor.requestFocusInWindow();
                else attachmentPanel.filterField.requestFocusInWindow();
            });
        } else {
            setTitle("Cognote - Event Driven");
        }
        setControlsEnabled(true);
    }

    private void setControlsEnabled(boolean enabled) {
        var noteSelected = (currentNote != null);
        var isGlobalSelected = noteSelected && Cog.GLOBAL_KB_NOTE_ID.equals(currentNote.id);
        var isConfigSelected = noteSelected && Cog.CONFIG_NOTE_ID.equals(currentNote.id);
        var systemReady = (cog != null && cog.running.get() && !cog.paused.get());

        mainControlPanel.setControlsEnabled(enabled);
        editorPanel.setControlsEnabled(enabled, noteSelected, isGlobalSelected, isConfigSelected);
        attachmentPanel.setControlsEnabled(enabled, noteSelected, systemReady);
        noteListPanel.setControlsEnabled(enabled);
    }

    private void performNoteAction(String actionName, String confirmTitle, String confirmMsgFormat, int confirmMsgType, Consumer<Cog.Note> action) {
        ofNullable(currentNote).filter(_ -> cog != null).filter(n -> !Cog.GLOBAL_KB_NOTE_ID.equals(n.id) && !Cog.CONFIG_NOTE_ID.equals(n.id))
                .filter(note -> JOptionPane.showConfirmDialog(this, String.format(confirmMsgFormat, note.title), confirmTitle, JOptionPane.YES_NO_OPTION, confirmMsgType) == JOptionPane.YES_OPTION)
                .ifPresent(note -> {
                    updateStatus(String.format("%s '%s'...", actionName, note.title));
                    setControlsEnabled(false);
                    try {
                        action.accept(note);
                        updateStatus(String.format("Finished %s '%s'.", actionName, note.title));
                    } catch (Exception ex) {
                        updateStatus(String.format("Error %s '%s'.", actionName, note.title));
                        handleActionError(actionName, ex);
                    } finally {
                        setControlsEnabled(true);
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
        var targetNoteIdForList = requireNonNullElse(displayNoteId, Cog.GLOBAL_KB_NOTE_ID);
        var sourceModel = noteAttachmentModels.computeIfAbsent(targetNoteIdForList, id -> new DefaultListModel<>());
        updateOrAddModelItem(sourceModel, vm);

        if (vm.isKifBased()) {
            var status = vm.status;
            if (status == AttachmentStatus.RETRACTED || status == AttachmentStatus.EVICTED || status == AttachmentStatus.INACTIVE)
                updateAttachmentStatusInOtherNoteModels(vm.id, status, targetNoteIdForList);
            ofNullable(cog).flatMap(s -> s.context.findAssertionByIdAcrossKbs(vm.id))
                    .ifPresent(assertion -> editorPanel.highlightAffectedNoteText(assertion, status));
        }

        if (currentNote != null && currentNote.id.equals(targetNoteIdForList))
            attachmentPanel.refreshAttachmentDisplay();
        attachmentPanel.updateAttachmentPanelTitle();
    }

    private void updateAttachmentStatusInOtherNoteModels(String attachmentId, AttachmentStatus newStatus, @Nullable String primaryNoteId) {
        noteAttachmentModels.forEach((noteId, model) -> {
            if (!noteId.equals(primaryNoteId)) {
                var idx = findViewModelIndexById(model, attachmentId);
                if (idx != -1 && model.getElementAt(idx).status != newStatus) {
                    model.setElementAt(model.getElementAt(idx).withStatus(newStatus), idx);
                    if (currentNote != null && currentNote.id.equals(noteId))
                        attachmentPanel.refreshAttachmentDisplay();
                }
            }
        });
    }

    public void updateLlmItem(String taskId, LlmStatus status, String content) {
        findViewModelInAnyModel(taskId).ifPresent(entry -> {
            var model = entry.getKey();
            var index = entry.getValue();
            var oldVm = model.getElementAt(index);

            var newContent = oldVm.content();
            if (content != null && !content.isBlank()) {
                if (!newContent.endsWith(content)) {
                    newContent += "\n" + content;
                }
            }

            var newVm = oldVm.withLlmUpdate(status, newContent);
            model.setElementAt(newVm, index);
            if (currentNote != null && currentNote.id.equals(oldVm.noteId()))
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
        currentNote = null;
        setTitle("Cognote - Event Driven");
        attachmentPanel.updateAttachmentPanelTitle();
        updateStatus("Cleared");
    }

    private void clearNoteAttachmentList(String noteId) {
        ofNullable(noteAttachmentModels.get(noteId)).ifPresent(DefaultListModel::clear);
        if (currentNote != null && currentNote.id.equals(noteId)) {
            attachmentPanel.refreshAttachmentDisplay();
            attachmentPanel.updateAttachmentPanelTitle();
        }
    }

    public void addNoteToList(Cog.Note note) {
        noteListPanel.addNoteToList(note);
    }

    public void removeNoteFromList(String noteId) {
        noteListPanel.removeNoteFromList(noteId);
    }

    public Optional<Cog.Note> findNoteById(String noteId) {
        return noteListPanel.findNoteById(noteId);
    }

    public java.util.List<Cog.Note> getAllNotes() {
        return noteListPanel.getAllNotes();
    }

    public void loadNotes(java.util.List<Cog.Note> notes) {
        noteListPanel.loadNotes(notes);
        updateUIForSelection();
        updateStatus("Notes loaded");
    }

    void noteTitleUpdated(Cog.Note note) {
        setTitle("Cognote - " + note.title + " [" + note.id + "]");
        noteListPanel.updateNoteDisplay(note);
    }

    private void displaySupportChain(Logic.Assertion startingAssertion) {
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
                var displayNoteId = a.sourceNoteId() != null ? a.sourceNoteId() : cog.context.findCommonSourceNodeId(a.justificationIds());
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
        if (cog != null) cog.systemStatus = statusText;
    }

    enum AttachmentStatus {ACTIVE, RETRACTED, EVICTED, INACTIVE}

    public enum LlmStatus {IDLE, SENDING, PROCESSING, DONE, ERROR, CANCELLED}

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
            if (value instanceof Cog.Note note) {
                var f = l.getFont();
                var noteID = note.id;
                if (Cog.CONFIG_NOTE_ID.equals(noteID)) {
                    f = f.deriveFont(Font.ITALIC);
                    l.setForeground(Color.GRAY);
                } else if (Cog.GLOBAL_KB_NOTE_ID.equals(noteID)) {
                    f = f.deriveFont(Font.BOLD);
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
                                      LlmStatus llmStatus) implements Comparable<AttachmentViewModel> {
        public static AttachmentViewModel fromAssertion(Logic.Assertion a, String callbackType, @Nullable String associatedNoteId) {
            return new AttachmentViewModel(a.id(), a.sourceNoteId(), a.toKifString(), determineTypeFromAssertion(a), determineStatusFromCallback(callbackType, a.isActive()), a.pri(), a.derivationDepth(), a.timestamp(), requireNonNullElse(associatedNoteId, a.sourceNoteId()), a.kb(), a.justificationIds(), LlmStatus.IDLE);
        }

        public static AttachmentViewModel forLlm(String id, @Nullable String noteId, String content, AttachmentType type, long timestamp, @Nullable String kbId, LlmStatus llmStatus) {
            return new AttachmentViewModel(id, noteId, content, type, AttachmentStatus.ACTIVE, 0.0, -1, timestamp, noteId, kbId, null, llmStatus);
        }

        public static AttachmentViewModel forQuery(String id, @Nullable String noteId, String content, AttachmentType type, long timestamp, @Nullable String kbId) {
            return new AttachmentViewModel(id, noteId, content, type, AttachmentStatus.ACTIVE, 0.0, -1, timestamp, noteId, kbId, null, LlmStatus.IDLE);
        }

        private static AttachmentType determineTypeFromAssertion(Logic.Assertion a) {
            return a.kif().op().map(op -> switch (op) {
                case PRED_NOTE_SUMMARY -> AttachmentType.SUMMARY;
                case PRED_NOTE_CONCEPT -> AttachmentType.CONCEPT;
                case PRED_NOTE_QUESTION -> AttachmentType.QUESTION;
                default -> detTypeFromAssertionType(a);
            }).orElse(detTypeFromAssertionType(a));
        }

        private static AttachmentType detTypeFromAssertionType(Logic.Assertion a) {
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

        public AttachmentViewModel withLlmUpdate(LlmStatus newLlmStatus, String newContent) {
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
                case Cog.GLOBAL_KB_NOTE_ID -> " [KB:G]";
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
        private final Cog systemRef;

        SettingsDialog(Frame owner, Cog cog) {
            super(owner, "Settings", true);
            this.systemRef = cog;
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
            if (systemRef.updateConfig(newConfigJson.toString())) dispose();
            else
                JOptionPane.showMessageDialog(this, "Invalid JSON format in Configuration. Please correct.", "Configuration Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    class NoteListPanel extends JPanel {
        public final DefaultListModel<Cog.Note> noteListModel = new DefaultListModel<>();
        public final JList<Cog.Note> noteList = new JList<>(noteListModel);
        final JPopupMenu noteContextMenu = new JPopupMenu();
        final JMenuItem analyzeItem = new JMenuItem("Analyze Note (LLM -> KIF)");
        final JMenuItem enhanceItem = new JMenuItem("Enhance Note (LLM Replace)");
        final JMenuItem summarizeItem = new JMenuItem("Summarize Note (LLM)");
        final JMenuItem keyConceptsItem = new JMenuItem("Identify Key Concepts (LLM)");
        final JMenuItem generateQuestionsItem = new JMenuItem("Generate Questions (LLM)");
        final JMenuItem removeItem = new JMenuItem("Remove Note");

        NoteListPanel() {
            setLayout(new BorderLayout());
            noteList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            noteList.setCellRenderer(new NoteListCellRenderer());

            Stream.of(analyzeItem, enhanceItem, summarizeItem, keyConceptsItem, generateQuestionsItem, removeItem).forEach(item -> {
                if (item != analyzeItem) noteContextMenu.addSeparator();
                noteContextMenu.add(item);
            });
            setupActionListeners();
        }

        private void setupActionListeners() {
            noteList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    saveCurrentNote();
                    currentNote = noteList.getSelectedValue();
                    updateUIForSelection();
                }
            });
            analyzeItem.addActionListener(e -> executeNoteTool("text_to_kif", currentNote));
            enhanceItem.addActionListener(e -> executeNoteTool("enhance_note", currentNote));
            summarizeItem.addActionListener(e -> executeNoteTool("summarize_note", currentNote));
            keyConceptsItem.addActionListener(e -> executeNoteTool("identify_concepts", currentNote));
            generateQuestionsItem.addActionListener(e -> executeNoteTool("generate_questions", currentNote));
            removeItem.addActionListener(e -> removeNoteAction());
            noteList.addMouseListener(createContextMenuMouseListener(noteList, noteContextMenu, this::updateNoteContextMenuState));
        }

        private void updateNoteContextMenuState() {
            var noteSelected = currentNote != null;
            var isEditableNote = noteSelected && !Cog.GLOBAL_KB_NOTE_ID.equals(currentNote.id) && !Cog.CONFIG_NOTE_ID.equals(currentNote.id);
            var systemReady = cog != null && cog.running.get() && !cog.paused.get();
            Stream.of(analyzeItem, enhanceItem, summarizeItem, keyConceptsItem, generateQuestionsItem, removeItem)
                    .forEach(i -> i.setEnabled(isEditableNote && systemReady));
        }

        void setControlsEnabled(boolean enabled) {
            noteList.setEnabled(enabled);
        }

        private void removeNoteAction() {
            performNoteAction("Removing", "Confirm Removal", "Remove note '%s' and retract all associated assertions?", JOptionPane.WARNING_MESSAGE, note -> ofNullable(cog).ifPresent(s -> s.events.emit(new Cog.RetractionRequestEvent(note.id, Logic.RetractionType.BY_NOTE, "UI-Remove", note.id))));
        }

        private void addNoteToListInternal(Cog.Note note) {
            if (findNoteById(note.id).isEmpty()) {
                noteListModel.addElement(note);
                noteAttachmentModels.computeIfAbsent(note.id, id -> new DefaultListModel<>());
            }
        }

        void removeNoteFromList(String noteId) {
            noteAttachmentModels.remove(noteId);
            findNoteIndexById(noteId).ifPresent(indexToRemove -> {
                var selectedIdxBeforeRemove = noteList.getSelectedIndex();
                noteListModel.removeElementAt(indexToRemove);
                var stateChanged = false;
                if (currentNote != null && currentNote.id.equals(noteId)) {
                    currentNote = null;
                    stateChanged = true;
                }
                if (!noteListModel.isEmpty()) {
                    var newIndex = Math.max(0, Math.min(selectedIdxBeforeRemove, noteListModel.getSize() - 1));
                    if (noteList.getSelectedIndex() != newIndex) {
                        noteList.setSelectedIndex(newIndex);
                    } else if (stateChanged) {
                        currentNote = noteList.getSelectedValue();
                        updateUIForSelection();
                    }
                } else {
                    currentNote = null;
                    updateUIForSelection();
                }
            });
        }

        void updateNoteDisplay(Cog.Note note) {
            findNoteIndexById(note.id).ifPresent(index -> {
                if (index >= 0 && index < noteListModel.getSize()) noteListModel.setElementAt(note, index);
            });
        }

        void clearNotes() {
            noteListModel.clear();
        }

        Optional<Cog.Note> findNoteById(String noteId) {
            return Collections.list(noteListModel.elements()).stream().filter(n -> n.id.equals(noteId)).findFirst();
        }

        OptionalInt findNoteIndexById(String noteId) {
            return IntStream.range(0, noteListModel.size()).filter(i -> noteListModel.getElementAt(i).id.equals(noteId)).findFirst();
        }

        List<Cog.Note> getAllNotes() {
            return Collections.list(noteListModel.elements());
        }

        void loadNotes(List<Cog.Note> notes) {
            noteListModel.clear();
            notes.forEach(this::addNoteToListInternal);
            if (findNoteById(Cog.GLOBAL_KB_NOTE_ID).isEmpty())
                addNoteToListInternal(new Cog.Note(Cog.GLOBAL_KB_NOTE_ID, Cog.GLOBAL_KB_NOTE_TITLE, "Global KB assertions."));
            if (findNoteById(Cog.CONFIG_NOTE_ID).isEmpty())
                addNoteToListInternal(cog != null ? Cog.createDefaultConfigNote() : new Cog.Note(Cog.CONFIG_NOTE_ID, Cog.CONFIG_NOTE_TITLE, "{}"));
            if (!noteListModel.isEmpty()) {
                var firstSelectable = IntStream.range(0, noteListModel.getSize())
                        .filter(i -> !noteListModel.getElementAt(i).id.equals(Cog.GLOBAL_KB_NOTE_ID) && !noteListModel.getElementAt(i).id.equals(Cog.CONFIG_NOTE_ID))
                        .findFirst().orElse(findNoteIndexById(Cog.GLOBAL_KB_NOTE_ID).orElse(0));
                noteList.setSelectedIndex(firstSelectable);
            }
        }

        private void executeNoteTool(String toolName, Cog.Note note) {
            if (cog == null || note == null || Cog.GLOBAL_KB_NOTE_ID.equals(note.id) || Cog.CONFIG_NOTE_ID.equals(note.id)) {
                System.err.println("Cannot execute tool '" + toolName + "': System not ready or invalid note selected.");
                return;
            }

            cog.toolRegistry().get(toolName).ifPresentOrElse(tool -> {
                updateStatus(tool.description() + " for '" + note.title + "'...");
                setControlsEnabled(false);

                Map<String, Object> params = Map.of("note_id", note.id);

                tool.execute(params).whenCompleteAsync((result, ex) -> {
                    setControlsEnabled(true);
                    updateStatus("Running");

                    if (ex != null) {
                        handleActionError(tool.name(), ex);
                    } else {
                        System.out.println("Tool '" + tool.name() + "' completed for note '" + note.id + "'. Result: " + result);
                        if (result instanceof String msg && !msg.isBlank()) {
                            updateStatus(tool.name() + " Result: " + msg);
                        }
                    }
                }, SwingUtilities::invokeLater);

            }, () -> System.err.println("Tool '" + toolName + "' not found in registry."));
        }
    }

    private class EditorPanel extends JPanel {
        public final JTextArea noteEditor = new JTextArea();
        final JTextField noteTitleField = new JTextField();
        private boolean isUpdatingTitleField = false;

        EditorPanel() {
            setLayout(new BorderLayout());
            noteEditor.setLineWrap(true);
            noteEditor.setWrapStyleWord(true);
            var titlePanel = new JPanel(new BorderLayout());
            titlePanel.add(new JLabel("Title: "), BorderLayout.WEST);
            titlePanel.add(noteTitleField, BorderLayout.CENTER);
            titlePanel.setBorder(new EmptyBorder(5, 5, 5, 5));
            add(titlePanel, BorderLayout.NORTH);
            add(new JScrollPane(noteEditor), BorderLayout.CENTER);
            setupActionListeners();
        }

        private void setupActionListeners() {
            noteEditor.getDocument().addDocumentListener((SimpleDocumentListener) e -> {
                if (currentNote != null && !Cog.GLOBAL_KB_NOTE_ID.equals(currentNote.id))
                    currentNote.text = noteEditor.getText();
            });
            noteEditor.addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent e) {
                    saveCurrentNote();
                }
            });
            noteTitleField.getDocument().addDocumentListener((SimpleDocumentListener) e -> {
                if (!isUpdatingTitleField && currentNote != null && !Cog.GLOBAL_KB_NOTE_ID.equals(currentNote.id) && !Cog.CONFIG_NOTE_ID.equals(currentNote.id)) {
                    currentNote.title = noteTitleField.getText();
                    noteTitleUpdated(currentNote);
                }
            });
        }

        void updateForSelection(Cog.Note note, boolean isGlobal, boolean isConfig) {
            var noteSelected = (note != null);
            var isEditableNoteContent = noteSelected && !isGlobal;
            var isEditableNoteTitle = noteSelected && !isGlobal && !isConfig;
            isUpdatingTitleField = true;
            noteTitleField.setText(noteSelected ? note.title : "");
            noteTitleField.setEditable(isEditableNoteTitle);
            noteTitleField.setEnabled(noteSelected && !isGlobal);
            isUpdatingTitleField = false;
            noteEditor.setText(noteSelected ? note.text : "");
            noteEditor.setEditable(isEditableNoteContent);
            noteEditor.setEnabled(isEditableNoteContent);
            noteEditor.getHighlighter().removeAllHighlights();
            noteEditor.setCaretPosition(0);
        }

        void setControlsEnabled(boolean enabled, boolean noteSelected, boolean isGlobal, boolean isConfig) {
            noteTitleField.setEnabled(enabled && noteSelected && !isGlobal);
            noteTitleField.setEditable(enabled && noteSelected && !isGlobal && !isConfig);
            noteEditor.setEnabled(enabled && noteSelected && !isGlobal);
            noteEditor.setEditable(enabled && noteSelected && !isGlobal);
        }

        void clearEditor() {
            noteTitleField.setText("");
            noteEditor.setText("");
        }

        void highlightAffectedNoteText(Logic.Assertion assertion, AttachmentStatus status) {
            if (cog == null || currentNote == null || Cog.GLOBAL_KB_NOTE_ID.equals(currentNote.id) || Cog.CONFIG_NOTE_ID.equals(currentNote.id))
                return;
            var displayNoteId = assertion.sourceNoteId();
            if (displayNoteId == null && assertion.derivationDepth() > 0)
                displayNoteId = cog.context.findCommonSourceNodeId(assertion.justificationIds());
            if (displayNoteId == null && !Cog.GLOBAL_KB_NOTE_ID.equals(assertion.kb())) displayNoteId = assertion.kb();
            if (currentNote.id.equals(displayNoteId)) {
                var searchTerm = extractHighlightTerm(assertion.kif());
                if (searchTerm == null || searchTerm.isBlank()) return;
                var highlighter = noteEditor.getHighlighter();
                Highlighter.HighlightPainter painter = switch (status) {
                    case ACTIVE -> new DefaultHighlighter.DefaultHighlightPainter(new Color(200, 255, 200));
                    case RETRACTED, INACTIVE ->
                            new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 200, 200));
                    case EVICTED -> new DefaultHighlighter.DefaultHighlightPainter(Color.LIGHT_GRAY);
                };
                try {
                    var text = noteEditor.getText();
                    var pos = text.toLowerCase().indexOf(searchTerm.toLowerCase());
                    while (pos >= 0) {
                        highlighter.addHighlight(pos, pos + searchTerm.length(), painter);
                        pos = text.toLowerCase().indexOf(searchTerm.toLowerCase(), pos + 1);
                    }
                } catch (BadLocationException e) { /* Ignore */ }
            }
        }

        private String extractHighlightTerm(Logic.KifList kif) {
            return kif.terms().stream().filter(Logic.KifAtom.class::isInstance).map(Logic.KifAtom.class::cast).map(Logic.KifAtom::value)
                    .filter(s -> s.length() > 2 && !Set.of(Logic.KIF_OP_AND, Logic.KIF_OP_OR, Logic.KIF_OP_NOT, Logic.KIF_OP_IMPLIES, Logic.KIF_OP_EQUIV, Logic.KIF_OP_EQUAL, Logic.KIF_OP_EXISTS, Logic.KIF_OP_FORALL, PRED_NOTE_SUMMARY, PRED_NOTE_CONCEPT, PRED_NOTE_QUESTION).contains(s))
                    .filter(s -> !s.startsWith(Cog.ID_PREFIX_NOTE) && !s.startsWith(Cog.ID_PREFIX_LLM_RESULT))
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
                    if (e.getClickCount() == 2) showSupportAction();
                }
            };
            attachmentList.addMouseListener(itemMouseListener);
            attachmentList.addMouseListener(itemDblClickListener);
        }

        void updateForSelection(Cog.Note note) {
            filterField.setText("");
            queryInputField.setText("");
            refreshAttachmentDisplay();
            updateAttachmentPanelTitle();
        }

        void setControlsEnabled(boolean enabled, boolean noteSelected, boolean systemReady) {
            attachmentList.setEnabled(enabled && noteSelected);
            filterField.setEnabled(enabled && noteSelected);
            queryInputField.setEnabled(enabled && noteSelected && systemReady);
            queryButton.setEnabled(enabled && noteSelected && systemReady);
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
            if (currentNote == null) {
                clearAttachments();
                return;
            }
            var filterText = filterField.getText().trim().toLowerCase();
            var sourceModel = noteAttachmentModels.computeIfAbsent(currentNote.id, id -> new DefaultListModel<>());
            List<AttachmentViewModel> sortedSource = Collections.list(sourceModel.elements());
            sortedSource.sort(Comparator.naturalOrder());
            var displayModel = new DefaultListModel<AttachmentViewModel>();
            sortedSource.stream().filter(vm -> filterText.isEmpty() || vm.content().toLowerCase().contains(filterText)).forEach(displayModel::addElement);
            attachmentList.setModel(displayModel);
        }

        void updateAttachmentPanelTitle() {
            var count = (currentNote != null) ? noteAttachmentModels.getOrDefault(currentNote.id, new DefaultListModel<>()).getSize() : 0;
            ((TitledBorder) getBorder()).setTitle("Attachments" + (count > 0 ? " (" + count + ")" : ""));
            repaint();
        }

        private void updateItemContextMenuState() {
            var vm = attachmentList.getSelectedValue();
            var isSelected = vm != null;
            var isKif = isSelected && vm.isKifBased();
            var isActiveKif = isKif && vm.status == AttachmentStatus.ACTIVE;
            var isLlmTask = isSelected && vm.attachmentType.name().startsWith("LLM_");
            var isCancelableLlm = isLlmTask && (vm.llmStatus == LlmStatus.SENDING || vm.llmStatus == LlmStatus.PROCESSING);
            var isSummary = isActiveKif && vm.attachmentType == AttachmentType.SUMMARY;
            var isQuestion = isActiveKif && vm.attachmentType == AttachmentType.QUESTION;
            var isConcept = isActiveKif && vm.attachmentType == AttachmentType.CONCEPT;
            retractItem.setEnabled(isActiveKif);
            showSupportItem.setEnabled(isKif);
            queryItem.setEnabled(isKif);
            cancelLlmItem.setEnabled(isCancelableLlm);
            insertSummaryItem.setEnabled(isSummary);
            answerQuestionItem.setEnabled(isQuestion);
            findRelatedConceptsItem.setEnabled(isConcept);
        }

        private Optional<AttachmentViewModel> getSelectedAttachmentViewModel() {
            return Optional.ofNullable(attachmentList.getSelectedValue());
        }

        private void retractSelectedAttachmentAction() {
            getSelectedAttachmentViewModel().filter(AttachmentViewModel::isKifBased).filter(vm -> vm.status == AttachmentStatus.ACTIVE).map(AttachmentViewModel::id).ifPresent(id -> {
                if (cog == null) return;
                cog.toolRegistry().get("retract_assertion").ifPresentOrElse(tool -> {
                    Map<String, Object> params = new HashMap<>();
                    params.put("target", id);
                    params.put("type", "BY_ID");
                    if (currentNote != null && !Cog.GLOBAL_KB_NOTE_ID.equals(currentNote.id)) {
                        params.put("target_note_id", currentNote.id);
                    }

                    tool.execute(params).whenCompleteAsync((result, ex) -> {
                        System.out.println("WS Retract tool result: " + result);
                        if (ex != null) System.err.println("WS Retract tool error: " + ex.getMessage());
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
                ofNullable(cog.lm.activeLlmTasks.remove(vm.id)).ifPresent(future -> future.cancel(true));
                updateLlmItem(vm.id, LlmStatus.CANCELLED, "Task cancelled by user.");
            });
        }

        private void insertSummaryAction() {
            getSelectedAttachmentViewModel().filter(vm -> vm.attachmentType == AttachmentType.SUMMARY && vm.status == AttachmentStatus.ACTIVE).ifPresent(vm -> {
                try {
                    var doc = editorPanel.noteEditor.getDocument();
                    doc.insertString(editorPanel.noteEditor.getCaretPosition(), extractContentFromKif(vm.content()) + "\n", null);
                    saveCurrentNote();
                } catch (BadLocationException e) {
                    System.err.println("Error inserting summary: " + e.getMessage());
                }
            });
        }

        private void answerQuestionAction() {
            getSelectedAttachmentViewModel().filter(vm -> vm.attachmentType == AttachmentType.QUESTION && vm.status == AttachmentStatus.ACTIVE).ifPresent(vm -> {
                var qText = extractContentFromKif(vm.content());
                var answer = JOptionPane.showInputDialog(UI.this, "Q: " + qText + "\n\nEnter your answer:", "Answer Question", JOptionPane.PLAIN_MESSAGE);
                if (answer != null && !answer.isBlank()) {
                    try {
                        var doc = editorPanel.noteEditor.getDocument();
                        doc.insertString(doc.getLength(), String.format("\n\nQ: %s\nA: %s\n", qText, answer.trim()), null);
                        saveCurrentNote();
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

            cog.toolRegistry().get("run_query").ifPresentOrElse(tool -> {
                Map<String, Object> params = new HashMap<>();
                params.put("kif_pattern", queryText);
                if (currentNote != null && !Cog.GLOBAL_KB_NOTE_ID.equals(currentNote.id)) {
                    params.put("target_kb_id", currentNote.id);
                }

                queryInputField.setText("");

                tool.execute(params).whenCompleteAsync((result, ex) -> {
                    if (ex != null) {
                        System.err.println("Error executing run_query tool: " + ex.getMessage());
                        JOptionPane.showMessageDialog(UI.this, "Query Error: " + ex.getMessage(), "Tool Execution Error", JOptionPane.ERROR_MESSAGE);
                    } else {
                        System.out.println("Query tool result:\n" + result);
                        JOptionPane.showMessageDialog(UI.this, result, "Query Result", JOptionPane.INFORMATION_MESSAGE);
                    }
                }, SwingUtilities::invokeLater);

            }, () -> {
                System.err.println("Tool 'run_query' not found.");
                JOptionPane.showMessageDialog(UI.this, "Query tool not available.", "Tool Error", JOptionPane.ERROR_MESSAGE);
            });
        }
    }

    class MainControlPanel extends JPanel {
        public final JLabel statusLabel = new JLabel("Status: Initializing...");
        final JButton addButton = new JButton("Add Note"), pauseResumeButton = new JButton("Pause"), clearAllButton = new JButton("Clear All");

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
            pauseResumeButton.setText((cog != null && cog.running.get()) ? (cog.isPaused() ? "Resume" : "Pause") : "Pause");
        }

        void setControlsEnabled(boolean enabled) {
            boolean systemExists = cog != null, systemRunning = systemExists && cog.running.get();
            addButton.setEnabled(enabled && systemExists);
            clearAllButton.setEnabled(enabled && systemExists);
            pauseResumeButton.setEnabled(enabled && systemRunning);
            updatePauseResumeButton();
        }

        private void addNoteAction() {
            ofNullable(JOptionPane.showInputDialog(UI.this, "Enter note title:", "New Note", JOptionPane.PLAIN_MESSAGE)).map(String::trim).filter(Predicate.not(String::isEmpty)).ifPresent(title -> {
                var newNote = new Cog.Note(Cog.generateId(Cog.ID_PREFIX_NOTE), title, "");
                addNoteToList(newNote);
                noteListPanel.noteList.setSelectedValue(newNote, true);
                if (cog != null) cog.events.emit(new Cog.AddedEvent(newNote));
            });
        }

        private void togglePauseAction() {
            ofNullable(cog).ifPresent(r -> {
                r.setPaused(!r.isPaused());
                updatePauseResumeButton();
                updateStatus(r.isPaused() ? "Paused" : "Running");
                setControlsEnabled(true);
            });
        }

        private void clearAllAction() {
            if (cog != null && JOptionPane.showConfirmDialog(UI.this, "Clear all notes (except config), assertions, and rules? This cannot be undone.", "Confirm Clear All", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
                cog.clear();
                updateStatus("Clearing all...");
            }
        }
    }

    private class MenuBarHandler {
        final JMenuItem settingsItem = new JMenuItem("Settings..."), viewRulesItem = new JMenuItem("View Rules"), askQueryItem = new JMenuItem("Ask Query...");
        private final JMenuBar menuBar = new JMenuBar();

        MenuBarHandler() {
            var fm = new JMenu("File");
            var em = new JMenu("Edit");
            var vm = new JMenu("View");
            var qm = new JMenu("Query");
            var hm = new JMenu("Help");

            settingsItem.addActionListener(e -> showSettingsDialog());
            fm.add(settingsItem);

            viewRulesItem.addActionListener(e -> viewRulesAction());
            vm.add(viewRulesItem);

            askQueryItem.addActionListener(e -> attachmentPanel.queryInputField.requestFocusInWindow());
            qm.add(askQueryItem);

            Stream.of(fm, em, vm, qm, hm).forEach(menuBar::add);
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
            var rules = cog.context.rules().stream().sorted(Comparator.comparing(Logic.Rule::id)).toList();
            if (rules.isEmpty()) {
                JOptionPane.showMessageDialog(UI.this, "<No rules defined>", "Current Rules", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            var ruleListModel = new DefaultListModel<Logic.Rule>();
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
                    ruleListModel.removeElement(selectedRule);
                }
            });
            var panel = new JPanel(new BorderLayout(0, 10));
            panel.add(scrollPane, BorderLayout.CENTER);
            panel.add(removeButton, BorderLayout.SOUTH);
            JOptionPane.showMessageDialog(UI.this, panel, "Current Rules", JOptionPane.PLAIN_MESSAGE);
        }
    }
}
