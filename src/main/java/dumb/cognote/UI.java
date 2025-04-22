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
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static dumb.cognote.Logic.*;
import static java.util.Objects.requireNonNullElse;
import static java.util.Optional.ofNullable;

class UI extends JFrame {
    private static final int UI_FONT_SIZE = 16;
    public static final Font MONOSPACED_FONT = new Font(Font.MONOSPACED, Font.PLAIN, UI_FONT_SIZE - 2);
    public static final Font UI_DEFAULT_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, UI_FONT_SIZE);
    public static final Font UI_SMALL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, UI_FONT_SIZE - 4);

    final JLabel statusLabel = new JLabel("Status: Initializing...");
    final Map<String, DefaultListModel<AttachmentViewModel>> noteAttachmentModels = new ConcurrentHashMap<>();
    final DefaultListModel<Cog.Note> noteListModel = new DefaultListModel<>();
    final JList<Cog.Note> noteList = new JList<>(noteListModel);
    final JTextArea noteEditor = new JTextArea();
    final JTextField noteTitleField = new JTextField();
    final JList<AttachmentViewModel> attachmentList = new JList<>();
    private final JButton addButton = new JButton("Add Note"), pauseResumeButton = new JButton("Pause"), clearAllButton = new JButton("Clear All");
    private final JPopupMenu noteContextMenu = new JPopupMenu(), itemContextMenu = new JPopupMenu();
    private final JMenuItem analyzeItem = new JMenuItem("Analyze Note (LLM -> KIF)"), enhanceItem = new JMenuItem("Enhance Note (LLM Replace)"),
            summarizeItem = new JMenuItem("Summarize Note (LLM)"), keyConceptsItem = new JMenuItem("Identify Key Concepts (LLM)"),
            generateQuestionsItem = new JMenuItem("Generate Questions (LLM)"), removeItem = new JMenuItem("Remove Note");
    private final JMenuItem retractItem = new JMenuItem("Delete Attachment"), showSupportItem = new JMenuItem("Show Support Chain"),
            queryItem = new JMenuItem("Query This Pattern"), cancelLlmItem = new JMenuItem("Cancel LLM Task"),
            insertSummaryItem = new JMenuItem("Insert Summary into Note"), answerQuestionItem = new JMenuItem("Answer Question in Note"),
            findRelatedConceptsItem = new JMenuItem("Find Related Notes (Concept)");
    Cog.Note currentNote = null;
    private JTextField filterField, queryInputField;
    private JButton queryButton;
    private Cog cog;
    private boolean isUpdatingTitleField = false;
    private JPanel attachmentPanel;


    public UI(@Nullable Cog cog) {
        super("Cognote - Event Driven");
        this.cog = cog;
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);
        setupComponents();
        setupLayout();
        setupActionListeners();
        setupWindowListener();
        setupMenuBar();
        updateUIForSelection();
        setupFonts();
    }

    private static @NotNull JList<Logic.Rule> ruleList(DefaultListModel<Logic.Rule> ruleListModel) {
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

    void setSystemReference(Cog system) {
        this.cog = system;
        updateUIForSelection();
    }

    private void setupFonts() {
        Stream.of(noteList, noteEditor, noteTitleField, filterField, queryInputField, addButton, pauseResumeButton, clearAllButton, queryButton, statusLabel, analyzeItem, enhanceItem, summarizeItem, keyConceptsItem, generateQuestionsItem, removeItem, retractItem, showSupportItem, queryItem, cancelLlmItem, insertSummaryItem, answerQuestionItem, findRelatedConceptsItem).forEach(c -> c.setFont(UI_DEFAULT_FONT));
        attachmentList.setFont(MONOSPACED_FONT);
    }

    private void setupComponents() {
        noteEditor.setLineWrap(true);
        noteEditor.setWrapStyleWord(true);
        noteList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        noteList.setCellRenderer(new NoteListCellRenderer());
        statusLabel.setBorder(new EmptyBorder(5, 10, 5, 10));
        statusLabel.setHorizontalAlignment(SwingConstants.LEFT);
        attachmentList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        attachmentList.setCellRenderer(new AttachmentListCellRenderer());
        filterField = new JTextField();
        filterField.setToolTipText("Filter attachments (case-insensitive contains)");
        filterField.getDocument().addDocumentListener((SimpleDocumentListener) e -> refreshAttachmentDisplay());
        queryInputField = new JTextField();
        queryInputField.setToolTipText("Enter KIF query pattern and press Enter or click Query");
        queryButton = new JButton("Query KB");
        Stream.of(analyzeItem, enhanceItem, summarizeItem, keyConceptsItem, generateQuestionsItem, removeItem).forEach(item -> {
            if (item != analyzeItem) noteContextMenu.addSeparator();
            noteContextMenu.add(item);
        });
        Stream.of(retractItem, showSupportItem, queryItem, cancelLlmItem, insertSummaryItem, answerQuestionItem, findRelatedConceptsItem).forEach(item -> {
            if (item != retractItem) itemContextMenu.addSeparator();
            itemContextMenu.add(item);
        });
    }

    private void setupLayout() {
        var leftPane = new JScrollPane(noteList);
        leftPane.setPreferredSize(new Dimension(250, 0));
        var titlePanel = new JPanel(new BorderLayout());
        titlePanel.add(new JLabel("Title: "), BorderLayout.WEST);
        titlePanel.add(noteTitleField, BorderLayout.CENTER);
        titlePanel.setBorder(new EmptyBorder(5, 5, 5, 5));
        var editorPane = new JScrollPane(noteEditor);
        var editorPanel = new JPanel(new BorderLayout());
        editorPanel.add(titlePanel, BorderLayout.NORTH);
        editorPanel.add(editorPane, BorderLayout.CENTER);
        var filterQueryPanel = new JPanel(new BorderLayout(5, 0));
        filterQueryPanel.add(filterField, BorderLayout.CENTER);
        var queryActionPanel = new JPanel(new BorderLayout(5, 0));
        queryActionPanel.add(queryInputField, BorderLayout.CENTER);
        queryActionPanel.add(queryButton, BorderLayout.EAST);
        filterQueryPanel.add(queryActionPanel, BorderLayout.SOUTH);
        filterQueryPanel.setBorder(new EmptyBorder(5, 0, 5, 0));
        attachmentPanel = new JPanel(new BorderLayout(0, 5));
        attachmentPanel.setBorder(BorderFactory.createTitledBorder("Attachments"));
        attachmentPanel.add(filterQueryPanel, BorderLayout.NORTH);
        attachmentPanel.add(new JScrollPane(attachmentList), BorderLayout.CENTER);
        attachmentPanel.setPreferredSize(new Dimension(0, 250));
        var rightPanel = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorPanel, attachmentPanel);
        rightPanel.setResizeWeight(0.7);
        var mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPane, rightPanel);
        mainSplitPane.setResizeWeight(0.2);
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        Stream.of(addButton, pauseResumeButton, clearAllButton).forEach(buttonPanel::add);
        var bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(buttonPanel, BorderLayout.WEST);
        bottomPanel.add(statusLabel, BorderLayout.CENTER);
        bottomPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
        var p = getContentPane();
        p.setLayout(new BorderLayout());
        p.add(mainSplitPane, BorderLayout.CENTER);
        p.add(bottomPanel, BorderLayout.SOUTH);
    }

    private void setupMenuBar() {
        var mb = new JMenuBar();
        var fm = new JMenu("File");
        var em = new JMenu("Edit");
        var vm = new JMenu("View");
        var qm = new JMenu("Query");
        var hm = new JMenu("Help");
        var settingsItem = new JMenuItem("Settings...");
        settingsItem.addActionListener(e -> showSettingsDialog());
        fm.add(settingsItem);
        var viewRulesItem = new JMenuItem("View Rules");
        viewRulesItem.addActionListener(e -> viewRulesAction());
        vm.add(viewRulesItem);
        var askQueryItem = new JMenuItem("Ask Query...");
        askQueryItem.addActionListener(e -> queryInputField.requestFocusInWindow());
        qm.add(askQueryItem);
        Stream.of(fm, em, vm, qm, hm).forEach(mb::add);
        setJMenuBar(mb);
    }

    private void setupActionListeners() {
        addButton.addActionListener(e -> addNoteAction());
        pauseResumeButton.addActionListener(e -> togglePauseAction());
        clearAllButton.addActionListener(e -> clearAllAction());
        analyzeItem.addActionListener(e -> analyzeNoteAction());
        enhanceItem.addActionListener(e -> enhanceNoteAction());
        summarizeItem.addActionListener(e -> summarizeNoteAction());
        keyConceptsItem.addActionListener(e -> keyConceptsAction());
        generateQuestionsItem.addActionListener(e -> generateQuestionsAction());
        removeItem.addActionListener(e -> removeNoteAction());
        retractItem.addActionListener(e -> retractSelectedAttachmentAction());
        showSupportItem.addActionListener(e -> showSupportAction());
        queryItem.addActionListener(e -> querySelectedAttachmentAction());
        cancelLlmItem.addActionListener(e -> cancelSelectedLlmTaskAction());
        insertSummaryItem.addActionListener(e -> insertSummaryAction());
        answerQuestionItem.addActionListener(e -> answerQuestionAction());
        findRelatedConceptsItem.addActionListener(e -> findRelatedConceptsAction());
        queryButton.addActionListener(e -> askQueryAction());
        queryInputField.addActionListener(e -> askQueryAction());

        noteList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                saveCurrentNote();
                currentNote = noteList.getSelectedValue();
                updateUIForSelection();
            }
        });
        noteEditor.getDocument().addDocumentListener((SimpleDocumentListener) e -> {
            if (currentNote != null && !Cog.GLOBAL_KB_NOTE_ID.equals(currentNote.id)) {
                currentNote.text = noteEditor.getText();
            }
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
                noteListModel.setElementAt(currentNote, noteList.getSelectedIndex());
                setTitle("Cognote - " + currentNote.title + " [" + currentNote.id + "]");
            }
        });
        noteList.addMouseListener(createContextMenuMouseListener(noteList, noteContextMenu, this::updateNoteContextMenuState));
        MouseListener itemMouseListener = createContextMenuMouseListener(attachmentList, itemContextMenu, this::updateItemContextMenuState);
        MouseListener itemDoubleClickListener = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) showSupportAction();
            }
        };
        attachmentList.addMouseListener(itemMouseListener);
        attachmentList.addMouseListener(itemDoubleClickListener);
    }

    private MouseAdapter createContextMenuMouseListener(@Nullable JList<?> list, JPopupMenu popup, Runnable... preShowActions) {
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

    private void updateNoteContextMenuState() {
        var noteSelected = currentNote != null;
        var isEditableNote = noteSelected && !Cog.GLOBAL_KB_NOTE_ID.equals(currentNote.id) && !Cog.CONFIG_NOTE_ID.equals(currentNote.id);
        var systemReady = cog != null && cog.running.get() && !cog.paused.get();
        Stream.of(analyzeItem, enhanceItem, summarizeItem, keyConceptsItem, generateQuestionsItem, removeItem).forEach(i -> i.setEnabled(isEditableNote && systemReady));
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
            n.text = noteEditor.getText();
            if (!Cog.CONFIG_NOTE_ID.equals(n.id)) n.title = noteTitleField.getText();
            else if (cog != null && !cog.updateConfig(n.text))
                JOptionPane.showMessageDialog(this, "Invalid JSON format in Configuration note. Changes not applied.", "Configuration Error", JOptionPane.ERROR_MESSAGE);
        });
    }

    private void updateUIForSelection() {
        var noteSelected = (currentNote != null);
        var isGlobalSelected = noteSelected && Cog.GLOBAL_KB_NOTE_ID.equals(currentNote.id);
        var isConfigSelected = noteSelected && Cog.CONFIG_NOTE_ID.equals(currentNote.id);
        var isEditableNoteContent = noteSelected && !isGlobalSelected;
        var isEditableNoteTitle = noteSelected && !isGlobalSelected && !isConfigSelected;
        isUpdatingTitleField = true;
        noteTitleField.setText(noteSelected ? currentNote.title : "");
        noteTitleField.setEditable(isEditableNoteTitle);
        noteTitleField.setEnabled(noteSelected && !isGlobalSelected);
        isUpdatingTitleField = false;
        noteEditor.setText(noteSelected ? currentNote.text : "");
        noteEditor.setEditable(isEditableNoteContent);
        noteEditor.setEnabled(isEditableNoteContent);
        noteEditor.getHighlighter().removeAllHighlights();
        noteEditor.setCaretPosition(0);
        ofNullable(cog).ifPresent(r -> pauseResumeButton.setText(r.isPaused() ? "Resume" : "Pause"));
        filterField.setText("");
        queryInputField.setText("");
        if (noteSelected) {
            setTitle("Cognote - " + currentNote.title + (isGlobalSelected || isConfigSelected ? "" : " [" + currentNote.id + "]"));
            SwingUtilities.invokeLater(isEditableNoteContent ? noteEditor::requestFocusInWindow : filterField::requestFocusInWindow);
        } else {
            setTitle("Cognote - Event Driven");
        }
        refreshAttachmentDisplay();
        updateAttachmentPanelTitle();
        setControlsEnabled(true);
    }

    private void refreshAttachmentDisplay() {
        if (currentNote == null) {
            attachmentList.setModel(new DefaultListModel<>());
            return;
        }
        var filterText = filterField.getText().trim().toLowerCase();
        var sourceModel = noteAttachmentModels.computeIfAbsent(currentNote.id, id -> new DefaultListModel<>());

        java.util.List<AttachmentViewModel> sortedSource = Collections.list(sourceModel.elements());
        sortedSource.sort(Comparator.naturalOrder());

        var displayModel = new DefaultListModel<AttachmentViewModel>();
        var stream = sortedSource.stream();
        if (!filterText.isEmpty()) {
            stream = stream.filter(vm -> vm.content().toLowerCase().contains(filterText));
        }
        stream.forEach(displayModel::addElement);

        attachmentList.setModel(displayModel);
    }

    private void filterAttachmentList() {
        refreshAttachmentDisplay();
    }

    private void updateAttachmentPanelTitle() {
        var count = (currentNote != null) ? noteAttachmentModels.getOrDefault(currentNote.id, new DefaultListModel<>()).getSize() : 0;
        ((TitledBorder) attachmentPanel.getBorder()).setTitle("Attachments" + (count > 0 ? " (" + count + ")" : ""));
        attachmentPanel.repaint();
    }

    private void addNoteAction() {
        ofNullable(JOptionPane.showInputDialog(this, "Enter note title:", "New Note", JOptionPane.PLAIN_MESSAGE))
                .map(String::trim).filter(Predicate.not(String::isEmpty))
                .ifPresent(title -> {
                    var newNote = new Cog.Note(Cog.generateId(Cog.ID_PREFIX_NOTE), title, "");
                    addNoteToList(newNote);
                    noteList.setSelectedValue(newNote, true);
                    if (cog != null) cog.events.emit(new Cog.AddedEvent(newNote));
                });
    }

    private void removeNoteAction() {
        performNoteAction("Removing", "Confirm Removal", "Remove note '%s' and retract all associated assertions?", JOptionPane.WARNING_MESSAGE, note -> ofNullable(cog).ifPresent(s -> s.events.emit(new Cog.RetractionRequestEvent(note.id, Logic.RetractionType.BY_NOTE, "UI-Remove", note.id))));
    }

    private void enhanceNoteAction() {
        performNoteActionAsync("Enhancing", cog.lm::enhanceNoteWithLlmAsync, (resp, n) -> {
        }, this::handleLlmFailure);
    }

    private void analyzeNoteAction() {
        performNoteActionAsync("Analyzing", (taskId, note) -> {
            // Retracting BY_NOTE caused issues, including removing the KB.
            // Just clear the UI list. Duplicates should be handled by the KB commit logic.
            clearNoteAttachmentList(note.id); // Clears UI list immediately
            return cog.lm.text2kifAsync(taskId, note.text, note.id); // LLM call starts
        }, (kif, note) -> {
        }, this::handleLlmFailure);
    }

    private void summarizeNoteAction() {
        performNoteActionAsync("Summarizing", cog.lm::summarizeNoteWithLlmAsync, (resp, n) -> {
        }, this::handleLlmFailure);
    }

    private void keyConceptsAction() {
        performNoteActionAsync("Identifying Concepts", cog.lm::keyConceptsWithLlmAsync, (resp, n) -> {
        }, this::handleLlmFailure);
    }

    private void generateQuestionsAction() {
        performNoteActionAsync("Generating Questions", cog.lm::generateQuestionsWithLlmAsync, (resp, n) -> {
        }, this::handleLlmFailure);
    }

    private void retractSelectedAttachmentAction() {
        getSelectedAttachmentViewModel().filter(AttachmentViewModel::isKifBased).filter(vm -> vm.status == AttachmentStatus.ACTIVE).map(AttachmentViewModel::id).ifPresent(id -> {
            System.out.println("UI Requesting retraction for: " + id);
            ofNullable(cog).ifPresent(s -> s.events.emit(new Cog.RetractionRequestEvent(id, Logic.RetractionType.BY_ID, "UI-Retract", currentNote != null ? currentNote.id : null)));
        });
    }

    private void showSupportAction() {
        getSelectedAttachmentViewModel().filter(AttachmentViewModel::isKifBased).map(AttachmentViewModel::id).flatMap(id -> cog.context.findAssertionByIdAcrossKbs(id)).ifPresent(this::displaySupportChain);
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
                var doc = noteEditor.getDocument();
                var caretPos = noteEditor.getCaretPosition();
                var summaryText = extractContentFromKif(vm.content());
                doc.insertString(caretPos, summaryText + "\n", null);
                saveCurrentNote();
            } catch (
                    BadLocationException e) {
                System.err.println("Error inserting summary: " + e.getMessage());
            }
        });
    }

    private void answerQuestionAction() {
        getSelectedAttachmentViewModel().filter(vm -> vm.attachmentType == AttachmentType.QUESTION && vm.status == AttachmentStatus.ACTIVE).ifPresent(vm -> {
            var questionText = extractContentFromKif(vm.content());
            var answer = JOptionPane.showInputDialog(this, "Q: " + questionText + "\n\nEnter your answer:", "Answer Question", JOptionPane.PLAIN_MESSAGE);
            if (answer != null && !answer.isBlank()) {
                try {
                    var doc = noteEditor.getDocument();
                    var qaText = String.format("\n\nQ: %s\nA: %s\n", questionText, answer.trim());
                    doc.insertString(doc.getLength(), qaText, null);
                    saveCurrentNote();
                } catch (BadLocationException e) {
                    System.err.println("Error appending Q/A: " + e.getMessage());
                }
            }
        });
    }

    private void findRelatedConceptsAction() {
        getSelectedAttachmentViewModel().filter(vm -> vm.attachmentType == AttachmentType.CONCEPT && vm.status == AttachmentStatus.ACTIVE).ifPresent(vm -> {
            var conceptText = extractContentFromKif(vm.content());
            JOptionPane.showMessageDialog(this, "Functionality to find related notes for concept '" + conceptText + "' is not yet implemented.", "Find Related Notes", JOptionPane.INFORMATION_MESSAGE);
        });
    }

    private String extractContentFromKif(String kifString) {
        try {
            var terms = Logic.KifParser.parseKif(kifString);
            if (terms.size() == 1 && terms.getFirst() instanceof Logic.KifList list && list.size() >= 4 && list.get(3) instanceof Logic.KifAtom(
                    var value
            ))
                return value;
        } catch (Logic.ParseException e) {
            System.err.println("Failed to parse KIF for content extraction: " + kifString);
        }
        return kifString;
    }

    private Optional<AttachmentViewModel> getSelectedAttachmentViewModel() {
        return Optional.ofNullable(attachmentList.getSelectedValue());
    }

    private void askQueryAction() {
        if (cog == null) return;
        var queryText = queryInputField.getText().trim();
        if (queryText.isBlank()) return;
        try {
            var terms = Logic.KifParser.parseKif(queryText);
            if (terms.size() != 1 || !(terms.getFirst() instanceof Logic.KifList queryPattern)) {
                JOptionPane.showMessageDialog(this, "Invalid query: Must be a single KIF list.", "Query Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            var queryId = Cog.generateId(Cog.ID_PREFIX_QUERY);
            var targetKbId = (currentNote != null && !Cog.GLOBAL_KB_NOTE_ID.equals(currentNote.id)) ? currentNote.id : null;
            var query = new Cog.Query(queryId, Cog.QueryType.ASK_BINDINGS, queryPattern, targetKbId, Map.of());
            cog.events.emit(new Cog.QueryRequestEvent(query));
            queryInputField.setText("");
        } catch (Logic.ParseException ex) {
            JOptionPane.showMessageDialog(this, "Query Parse Error: " + ex.getMessage(), "Query Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void viewRulesAction() {
        if (cog == null) return;
        var rules = cog.context.rules().stream().sorted(Comparator.comparing(Logic.Rule::id)).toList();
        if (rules.isEmpty()) {
            JOptionPane.showMessageDialog(this, "<No rules defined>", "Current Rules", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        var ruleListModel = new DefaultListModel<Logic.Rule>();
        rules.forEach(ruleListModel::addElement);
        var ruleJList = ruleList(ruleListModel);
        var scrollPane = new JScrollPane(ruleJList);
        scrollPane.setPreferredSize(new Dimension(700, 400));
        var removeButton = new JButton("Remove Selected Rule");
        removeButton.setFont(UI_DEFAULT_FONT);
        removeButton.setEnabled(false);
        ruleJList.addListSelectionListener(ev -> removeButton.setEnabled(ruleJList.getSelectedIndex() != -1));
        removeButton.addActionListener(ae -> {
            var selectedRule = ruleJList.getSelectedValue();
            if (selectedRule != null && JOptionPane.showConfirmDialog(this, "Remove rule: " + selectedRule.id() + "?", "Confirm Rule Removal", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
                cog.events.emit(new Cog.RetractionRequestEvent(selectedRule.form().toKif(),
                        Logic.RetractionType.BY_RULE_FORM, "UI-RuleView", null));
                ruleListModel.removeElement(selectedRule);
            }
        });
        var panel = new JPanel(new BorderLayout(0, 10));
        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(removeButton, BorderLayout.SOUTH);
        JOptionPane.showMessageDialog(this, panel, "Current Rules", JOptionPane.PLAIN_MESSAGE);
    }

    private void togglePauseAction() {
        ofNullable(cog).ifPresent(r -> r.setPaused(!r.isPaused()));
    }

    private void clearAllAction() {
        if (cog != null && JOptionPane.showConfirmDialog(this, "Clear all notes (except config), assertions, and rules? This cannot be undone.", "Confirm Clear All", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION)
            cog.clear();
    }

    private void performNoteAction(String actionName, String confirmTitle, String confirmMsgFormat, int confirmMsgType, Consumer<Cog.Note> action) {
        ofNullable(currentNote).filter(_ -> cog != null).filter(n -> !Cog.GLOBAL_KB_NOTE_ID.equals(n.id) && !Cog.CONFIG_NOTE_ID.equals(n.id))
                .filter(note -> JOptionPane.showConfirmDialog(this, String.format(confirmMsgFormat, note.title), confirmTitle, JOptionPane.YES_NO_OPTION, confirmMsgType) == JOptionPane.YES_OPTION)
                .ifPresent(note -> {
                    cog.systemStatus = String.format("%s '%s'...", actionName, note.title);
                    cog.updateStatusLabel();
                    setControlsEnabled(false);
                    try {
                        action.accept(note);
                        cog.systemStatus = String.format("Finished %s '%s'.", actionName, note.title);
                    } catch (Exception ex) {
                        cog.systemStatus = String.format("Error %s '%s'.", actionName, note.title);
                        handleActionError(actionName, ex);
                    } finally {
                        cog.updateStatusLabel();
                        setControlsEnabled(true);
                    }
                });
    }

    String addLlmUiPlaceholder(String noteId, String actionName) {
        var vm = UI.AttachmentViewModel.forLlm(
                Cog.generateId(Cog.ID_PREFIX_LLM_ITEM + actionName.toLowerCase().replaceAll("\\s+", "")),
                noteId,
                actionName + ": Starting...",
                UI.AttachmentType.LLM_INFO,
                System.currentTimeMillis(),
                noteId,
                UI.LlmStatus.SENDING
        );
        cog.events.emit(new Cog.LlmInfoEvent(vm));
        return vm.id;
    }


    private <T> void performNoteActionAsync(String actionName, NoteAsyncAction<T> asyncAction, BiConsumer<T, Cog.Note> successCallback, BiConsumer<Throwable, Cog.Note> failureCallback) {
        ofNullable(currentNote).filter(_ -> cog != null).filter(n -> !Cog.GLOBAL_KB_NOTE_ID.equals(n.id) && !Cog.CONFIG_NOTE_ID.equals(n.id))
                .ifPresent(n -> {
                    cog.systemStatus = actionName + " Note: " + n.title;
                    cog.updateStatusLabel();
                    setControlsEnabled(false);
                    saveCurrentNote();
                    var taskId = addLlmUiPlaceholder(n.id, actionName);
                    try {
                        var future = asyncAction.execute(taskId, n);
                        cog.lm.activeLlmTasks.put(taskId, future);
                        future.whenCompleteAsync((result, ex) -> {
                            cog.lm.activeLlmTasks.remove(taskId);
                            // Status updates (Done/Error/Cancelled) are handled by LlmUpdateEvent handler
                            if (ex != null) failureCallback.accept(ex, n);
                            else successCallback.accept(result, n);
                            setControlsEnabled(true);
                            cog.systemStatus = "Running";
                            cog.updateStatusLabel();
                        }, SwingUtilities::invokeLater);
                    } catch (Exception e) {
                        cog.lm.activeLlmTasks.remove(taskId);
                        cog.updateLlmItemStatus(taskId, LlmStatus.ERROR, "Failed to start: " + e.getMessage());
                        failureCallback.accept(e, n);
                        setControlsEnabled(true);
                        cog.systemStatus = "Running";
                        cog.updateStatusLabel();
                    }
                });
    }

    private void handleActionError(String actionName, Throwable ex) {
        var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
        System.err.println("Error during " + actionName + ": " + cause.getMessage());
        cause.printStackTrace();
        JOptionPane.showMessageDialog(this, "Error during " + actionName + ":\n" + cause.getMessage(), "Action Error", JOptionPane.ERROR_MESSAGE);
    }

    private void handleLlmFailure(Throwable ex, Cog.Note contextNote) {
        var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
        var action = (cause instanceof Logic.ParseException) ? "KIF Parse Error" : "LLM Interaction Failed";
        if (!(cause instanceof CancellationException)) {
            System.err.println("LLM Failure for note " + contextNote.id + ": " + action + ": " + cause.getMessage());
            // Error status is set via LlmUpdateEvent in the calling completion handler
        } else {
            System.out.println("LLM task cancelled for note " + contextNote.id);
        }
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
            ofNullable(cog).flatMap(s -> s.context.findAssertionByIdAcrossKbs(vm.id)).ifPresent(assertion -> highlightAffectedNoteText(assertion, status));
        }

        if (currentNote != null && currentNote.id.equals(targetNoteIdForList)) refreshAttachmentDisplay();
        updateAttachmentPanelTitle();
    }


    private void updateOrAddModelItem(DefaultListModel<AttachmentViewModel> sourceModel, AttachmentViewModel newItem) {
        var existingIndex = findViewModelIndexById(sourceModel, newItem.id);
        if (existingIndex != -1) {
            var existingItem = sourceModel.getElementAt(existingIndex);
            if (newItem.status != existingItem.status || !newItem.content().equals(existingItem.content()) || newItem.priority() != existingItem.priority() || !Objects.equals(newItem.associatedNoteId(), existingItem.associatedNoteId()) || !Objects.equals(newItem.kbId(), existingItem.kbId()) || newItem.llmStatus != existingItem.llmStatus || newItem.attachmentType != existingItem.attachmentType) {
                sourceModel.setElementAt(newItem, existingIndex);
            }
        } else if (newItem.status == AttachmentStatus.ACTIVE || !newItem.isKifBased()) { // Only add active KIF or any non-KIF
            sourceModel.addElement(newItem);
        }
    }

    private void updateAttachmentStatusInOtherNoteModels(String attachmentId, AttachmentStatus newStatus, @Nullable String primaryNoteId) {
        noteAttachmentModels.forEach((noteId, model) -> {
            if (!noteId.equals(primaryNoteId)) {
                var idx = findViewModelIndexById(model, attachmentId);
                if (idx != -1 && model.getElementAt(idx).status != newStatus) {
                    model.setElementAt(model.getElementAt(idx).withStatus(newStatus), idx);
                    if (currentNote != null && currentNote.id.equals(noteId)) refreshAttachmentDisplay();
                }
            }
        });
    }

    public void updateLlmItem(String taskId, LlmStatus status, String content) {
        findViewModelInAnyModel(taskId).ifPresent(entry -> {
            var model = entry.getKey();
            var index = entry.getValue();
            var oldVm = model.getElementAt(index);
            var newVm = oldVm.withLlmUpdate(status, content);
            model.setElementAt(newVm, index);
            if (currentNote != null && currentNote.id.equals(oldVm.noteId())) refreshAttachmentDisplay();
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

    private int findViewModelIndexById(DefaultListModel<AttachmentViewModel> model, String id) {
        for (var i = 0; i < model.getSize(); i++) if (model.getElementAt(i).id.equals(id)) return i;
        return -1;
    }

    public void clearAllUILists() {
        noteListModel.clear();
        noteAttachmentModels.clear();
        attachmentList.setModel(new DefaultListModel<>());
        noteEditor.setText("");
        noteTitleField.setText("");
        currentNote = null;
        setTitle("Cognote - Event Driven");
        updateAttachmentPanelTitle();
    }

    private void clearNoteAttachmentList(String noteId) {
        var modelToClear = noteAttachmentModels.get(noteId);
        if (modelToClear != null) {
            modelToClear.clear(); // Clear the model stored in the map
        }
        // If this note is currently displayed, refresh its display
        if (currentNote != null && currentNote.id.equals(noteId)) {
            refreshAttachmentDisplay(); // Will show the now-empty list
            updateAttachmentPanelTitle(); // Update count shown in title
        }
    }

    public void addNoteToList(Cog.Note note) {
        if (findNoteById(note.id).isEmpty()) {
            noteListModel.addElement(note);
            noteAttachmentModels.computeIfAbsent(note.id, id -> new DefaultListModel<>());
        }
    }

    public void removeNoteFromList(String noteId) {
        noteAttachmentModels.remove(noteId);
        findNoteIndexById(noteId).ifPresent(i -> {
            var idx = noteList.getSelectedIndex();
            noteListModel.removeElementAt(i);
            if (currentNote != null && currentNote.id.equals(noteId)) currentNote = null;
            if (!noteListModel.isEmpty())
                noteList.setSelectedIndex(Math.max(0, Math.min(idx, noteListModel.getSize() - 1)));
            else updateUIForSelection();
        });
    }

    public Optional<Cog.Note> findNoteById(String noteId) {
        return Collections.list(noteListModel.elements()).stream().filter(n -> n.id.equals(noteId)).findFirst();
    }

    private OptionalInt findNoteIndexById(String noteId) {
        return IntStream.range(0, noteListModel.size()).filter(i -> noteListModel.getElementAt(i).id.equals(noteId)).findFirst();
    }

    public java.util.List<Cog.Note> getAllNotes() {
        return Collections.list(noteListModel.elements());
    }

    public void loadNotes(java.util.List<Cog.Note> notes) {
        noteListModel.clear();
        notes.forEach(this::addNoteToList);
        if (findNoteById(Cog.GLOBAL_KB_NOTE_ID).isEmpty())
            addNoteToList(new Cog.Note(Cog.GLOBAL_KB_NOTE_ID, Cog.GLOBAL_KB_NOTE_TITLE, "Assertions in the global knowledge base."));
        if (findNoteById(Cog.CONFIG_NOTE_ID).isEmpty())
            addNoteToList(cog != null ? cog.createDefaultConfigNote() : new Cog.Note(Cog.CONFIG_NOTE_ID, Cog.CONFIG_NOTE_TITLE, "{}"));
        if (!noteListModel.isEmpty()) {
            var firstSelectable = IntStream.range(0, noteListModel.getSize()).filter(i -> !noteListModel.getElementAt(i).id.equals(Cog.GLOBAL_KB_NOTE_ID) && !noteListModel.getElementAt(i).id.equals(Cog.CONFIG_NOTE_ID)).findFirst().orElse(findNoteIndexById(Cog.GLOBAL_KB_NOTE_ID).orElse(0));
            noteList.setSelectedIndex(firstSelectable);
        }
        updateUIForSelection();
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
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
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

    private void highlightAffectedNoteText(Logic.Assertion assertion, AttachmentStatus status) {
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
                case RETRACTED, INACTIVE -> new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 200, 200));
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

    private void setControlsEnabled(boolean enabled) {
        var noteSelected = (currentNote != null);
        var isGlobalSelected = noteSelected && Cog.GLOBAL_KB_NOTE_ID.equals(currentNote.id);
        var isConfigSelected = noteSelected && Cog.CONFIG_NOTE_ID.equals(currentNote.id);
        var isEditableNote = noteSelected && !isGlobalSelected && !isConfigSelected;
        var systemReady = (cog != null && cog.running.get() && !cog.paused.get());
        Stream.of(addButton, clearAllButton).forEach(c -> c.setEnabled(enabled && cog != null));
        pauseResumeButton.setEnabled(enabled && cog != null && cog.running.get());
        noteTitleField.setEnabled(enabled && noteSelected && !isGlobalSelected);
        noteTitleField.setEditable(enabled && isEditableNote);
        noteEditor.setEnabled(enabled && noteSelected && !isGlobalSelected);
        noteEditor.setEditable(enabled && noteSelected && !isGlobalSelected);
        filterField.setEnabled(enabled && noteSelected);
        queryInputField.setEnabled(enabled && noteSelected && systemReady);
        queryButton.setEnabled(enabled && noteSelected && systemReady);
        attachmentList.setEnabled(enabled && noteSelected);
        if (cog != null && cog.running.get())
            pauseResumeButton.setText(cog.isPaused() ? "Resume" : "Pause");
    }

    private void showSettingsDialog() {
        if (cog == null) return;
        var dialog = new SettingsDialog(this, cog);
        dialog.setVisible(true);
    }

    enum AttachmentStatus {ACTIVE, RETRACTED, EVICTED, INACTIVE}

    enum LlmStatus {IDLE, SENDING, PROCESSING, DONE, ERROR, CANCELLED}

    enum AttachmentType {
        FACT, DERIVED, UNIVERSAL, SKOLEMIZED, SUMMARY, CONCEPT, QUESTION, LLM_INFO, LLM_ERROR, QUERY_SENT, QUERY_RESULT, OTHER
    }

    @FunctionalInterface
    interface NoteAsyncAction<T> {
        CompletableFuture<T> execute(String taskId, Cog.Note note);
    }

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

    record AttachmentViewModel(String id, @Nullable String noteId, String content, AttachmentType attachmentType,
                               AttachmentStatus status, double priority, int depth, long timestamp,
                               @Nullable String associatedNoteId, @Nullable String kbId,
                               @Nullable Set<String> justifications, LlmStatus llmStatus
    ) implements Comparable<AttachmentViewModel> {
        static AttachmentViewModel fromAssertion(Logic.Assertion a, String callbackType, @Nullable String associatedNoteId) {
            return new AttachmentViewModel(a.id(), a.sourceNoteId(), a.toKifString(),
                    determineTypeFromAssertion(a),
                    determineStatusFromCallback(callbackType, a.isActive()),
                    a.pri(), a.derivationDepth(), a.timestamp(),
                    requireNonNullElse(associatedNoteId, a.sourceNoteId()),
                    a.kb(), a.justificationIds(), LlmStatus.IDLE);
        }

        static AttachmentViewModel forLlm(String id, @Nullable String noteId, String content, AttachmentType type, long timestamp, @Nullable String kbId, LlmStatus llmStatus) {
            return new AttachmentViewModel(id, noteId, content, type, AttachmentStatus.ACTIVE, 0.0, -1, timestamp, noteId, kbId, null, llmStatus);
        }

        static AttachmentViewModel forQuery(String id, @Nullable String noteId, String content, AttachmentType type, long timestamp, @Nullable String kbId) {
            return new AttachmentViewModel(id, noteId, content, type, AttachmentStatus.ACTIVE, 0.0, -1, timestamp, noteId, kbId, null, LlmStatus.IDLE);
        }

        private static AttachmentType determineTypeFromAssertion(Logic.Assertion a) {
            return a.kif().op().map(op -> switch (op) {
                case PRED_NOTE_SUMMARY -> AttachmentType.SUMMARY;
                case PRED_NOTE_CONCEPT -> AttachmentType.CONCEPT;
                case PRED_NOTE_QUESTION -> AttachmentType.QUESTION;
                default -> switch (a.type()) {
                    case GROUND -> (a.derivationDepth() == 0) ? AttachmentType.FACT : AttachmentType.DERIVED;
                    case SKOLEMIZED -> AttachmentType.SKOLEMIZED;
                    case UNIVERSAL -> AttachmentType.UNIVERSAL;
                };
            }).orElse(switch (a.type()) {
                case GROUND -> (a.derivationDepth() == 0) ? AttachmentType.FACT : AttachmentType.DERIVED;
                case SKOLEMIZED -> AttachmentType.SKOLEMIZED;
                case UNIVERSAL -> AttachmentType.UNIVERSAL;
            });
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
            var kbDisplay = ofNullable(value.kbId()).map(id -> switch (id) {
                case Cog.GLOBAL_KB_NOTE_ID -> " [KB:G]";
                case "unknown" -> "";
                default -> " [KB:" + id.replace(Cog.ID_PREFIX_NOTE, "") + "]";
            }).orElse("");
            var assocNoteDisplay = ofNullable(value.associatedNoteId()).filter(id -> !id.equals(value.kbId())).map(id -> " (N:" + id.replace(Cog.ID_PREFIX_NOTE, "") + ")").orElse("");
            var timeStr = timeFormatter.format(Instant.ofEpochMilli(value.timestamp()));
            var details = switch (value.attachmentType) {
                case FACT, DERIVED, UNIVERSAL, SKOLEMIZED, SUMMARY, CONCEPT, QUESTION ->
                        String.format("P:%.3f | D:%d | %s%s%s", value.priority(), value.depth(), timeStr, assocNoteDisplay, kbDisplay);
                case LLM_INFO, LLM_ERROR -> String.format("%s | %s%s", value.llmStatus(), timeStr, kbDisplay);
                case QUERY_SENT, QUERY_RESULT -> String.format("%s | %s%s", value.attachmentType, timeStr, kbDisplay);
                default -> String.format("%s%s", timeStr, kbDisplay);
            };
            detailLabel.setText(details);
            iconLabel.setText(iconText);
            if (value.status != AttachmentStatus.ACTIVE && value.isKifBased()) {
                fgColor = Color.LIGHT_GRAY;
                contentLabel.setText("<html><strike>" + value.content().replace("<", "&lt;").replace(">", "&gt;") + "</strike></html>");
                detailLabel.setText(value.status + " | " + details);
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
                        contentLabel.setText("<html><strike>" + value.content().replace("<", "&lt;").replace(">", "&gt;") + "</strike></html>");
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
            setToolTipText(String.format("<html>ID: %s<br>KB: %s<br>Associated Note: %s<br>Status: %s<br>LLM Status: %s<br>Type: %s<br>Priority: %.4f<br>Depth: %d<br>Timestamp: %s<br>Justifications: %s</html>", value.id, value.kbId() != null ? value.kbId() : "N/A", value.associatedNoteId() != null ? value.associatedNoteId() : "N/A", value.status, value.llmStatus, value.attachmentType, value.priority(), value.depth(), Instant.ofEpochMilli(value.timestamp()).toString(), justList));
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
            if (systemRef.updateConfig(newConfigJson.toString()))
                dispose();
            else
                JOptionPane.showMessageDialog(this, "Invalid JSON format in Configuration. Please correct.", "Configuration Error", JOptionPane.ERROR_MESSAGE);
        }
    }
}
