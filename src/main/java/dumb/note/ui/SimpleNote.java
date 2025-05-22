package dumb.note.ui;

import dumb.note.Netention;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class SimpleNote extends UI.BaseAppFrame {
    private final SimpleNoteListPanel noteListPanel;
    private final JPanel editorPanelContainer;

    public SimpleNote(Netention.Core core) {
        super(core, "SimpleNote ✨", 800, 600);
        this.editorPanelContainer = super.defaultEditorHostPanel;

        noteListPanel = new SimpleNoteListPanel(core, this::displayNote);
        var splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, noteListPanel, editorPanelContainer);
        splitPane.setDividerLocation(200);

        remove(super.defaultEditorHostPanel);
        add(splitPane, BorderLayout.CENTER);

        populateActions();
        setJMenuBar(createSimpleNoteMenuBar());
        core.addCoreEventListener(this::handleSimpleNoteCoreEvent);
        displayNote(null);
        updateActionStates();
    }

    public static void main(String[] ignoredArgs) {
        SwingUtilities.invokeLater(() -> new SimpleNote(new Netention.Core()).setVisible(true));
    }

    @Override
    protected Optional<UI.DirtyableSavableComponent> getActiveDirtyableSavableComponent() {
        if (editorPanelContainer.getComponentCount() > 0 &&
                editorPanelContainer.getComponent(0) instanceof UI.DirtyableSavableComponent dsc) {
            return Optional.of(dsc);
        }
        return Optional.empty();
    }

    private void populateActions() {
        actionRegistry.register(UI.ActionID.NEW_NOTE, new UI.AppAction("New Note", KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK), e -> createNewNote()));
        actionRegistry.register(UI.ActionID.EXIT, new UI.AppAction("Exit", e -> handleWindowClose()));
        actionRegistry.register(UI.ActionID.SAVE_NOTE, new UI.AppAction("Save Note", KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), e -> saveActiveDirtyableSavableComponent(false))
                .setEnabledCalculator(() -> getActiveDirtyableSavableComponent().map(UI.DirtyableSavableComponent::isDirty).orElse(false)));
        actionRegistry.register(UI.ActionID.ABOUT, new UI.AppAction("About SimpleNote", e ->
                JOptionPane.showMessageDialog(this, "SimpleNote ✨\nA basic notes editor.", "About SimpleNote", JOptionPane.INFORMATION_MESSAGE)
        ));
        actionRegistry.register(UI.ActionID.CUT, new UI.AppAction("Cut", KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK), e -> getActiveTextComponent().ifPresent(JTextComponent::cut))
                .setEnabledCalculator(() -> getActiveTextComponent().isPresent()));
        actionRegistry.register(UI.ActionID.COPY, new UI.AppAction("Copy", KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), e -> getActiveTextComponent().ifPresent(JTextComponent::copy))
                .setEnabledCalculator(() -> getActiveTextComponent().isPresent()));
        actionRegistry.register(UI.ActionID.PASTE, new UI.AppAction("Paste", KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), e -> getActiveTextComponent().ifPresent(JTextComponent::paste))
                .setEnabledCalculator(() -> getActiveTextComponent().isPresent()));
    }

    private void handleSimpleNoteCoreEvent(Netention.Core.CoreEvent event) {
        super.handleCoreEventBase(event);
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> handleSimpleNoteCoreEvent(event));
            return;
        }

        if (Set.of(Netention.Core.CoreEventType.NOTE_ADDED, Netention.Core.CoreEventType.NOTE_DELETED).contains(event.type())) {
            noteListPanel.refreshNotes();
        } else if (event.type() == Netention.Core.CoreEventType.NOTE_UPDATED && event.data() instanceof Netention.Note updatedNote) {
            getActiveDirtyableSavableComponent()
                    .filter(UI.NoteEditorPanel.class::isInstance).map(UI.NoteEditorPanel.class::cast)
                    .ifPresent(editor -> {
                        if (editor.currentNote != null && editor.currentNote.id != null && editor.currentNote.id.equals(updatedNote.id)) {
                            if (editor.isUserModified()) editor.setExternallyUpdated(true);
                            else editor.populateFields(updatedNote);
                        }
                    });
            noteListPanel.refreshNotes();
        }
        updateActionStates();
    }

    private void displayNote(@Nullable Netention.Note note) {
        if (note != null && (note.tags.contains(Netention.SystemTag.CHAT.value) || note.tags.contains(Netention.SystemTag.CONFIG.value))) {
            super.displayNoteInEditor(null);
            updateStatus("Selected note type not supported in SimpleNote.");
            return;
        }
        if (!canSwitchOrCloseContent(note == null || note.id == null, null)) return;
        super.displayNoteInEditor(note);
    }

    private void createNewNote() {
        if (!canSwitchOrCloseContent(true, null)) return;
        displayNote(new Netention.Note("Untitled", ""));
    }

    private JMenuBar createSimpleNoteMenuBar() {
        var mb = new JMenuBar();
        var fileMenu = new JMenu("File");
        fileMenu.add(createMenuItem(UI.ActionID.NEW_NOTE));
        fileMenu.addSeparator();
        fileMenu.add(createMenuItem(UI.ActionID.EXIT));
        mb.add(fileMenu);

        var editMenu = new JMenu("Edit");
        editMenu.add(createMenuItem(UI.ActionID.CUT));
        editMenu.add(createMenuItem(UI.ActionID.COPY));
        editMenu.add(createMenuItem(UI.ActionID.PASTE));
        mb.add(editMenu);

        var noteMenu = new JMenu("Note");
        noteMenu.add(createMenuItem(UI.ActionID.SAVE_NOTE));
        mb.add(noteMenu);

        var helpMenu = new JMenu("Help");
        helpMenu.add(createMenuItem(UI.ActionID.ABOUT));
        mb.add(helpMenu);
        return mb;
    }

    static class SimpleNoteListPanel extends JPanel {
        private final Netention.Core core;
        private final DefaultListModel<Netention.Note> listModel = new DefaultListModel<>();
        private final JList<Netention.Note> noteJList = new JList<>(listModel);
        private final JTextField searchField = new JTextField(15);

        public SimpleNoteListPanel(Netention.Core core, Consumer<Netention.Note> onNoteSelected) {
            this.core = core;
            setLayout(new BorderLayout(5, 5));
            setBorder(new EmptyBorder(5, 5, 5, 5));

            noteJList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            noteJList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting() && noteJList.getSelectedValue() != null) {
                    onNoteSelected.accept(noteJList.getSelectedValue());
                }
            });
            add(new JScrollPane(noteJList), BorderLayout.CENTER);

            var topPanel = new JPanel(new BorderLayout(5, 5));
            topPanel.add(UIUtil.button("➕", "New Note", _ -> onNoteSelected.accept(new Netention.Note("Untitled", ""))), BorderLayout.WEST);

            var searchBox = new JPanel(new BorderLayout(2, 0));
            searchBox.add(new JLabel("🔍"), BorderLayout.WEST);
            searchField.getDocument().addDocumentListener(new UI.FieldUpdateListener(_ -> refreshNotes()));
            searchBox.add(searchField, BorderLayout.CENTER);
            topPanel.add(searchBox, BorderLayout.CENTER);
            add(topPanel, BorderLayout.NORTH);
            refreshNotes();
        }

        public void refreshNotes() {
            var selected = noteJList.getSelectedValue();
            listModel.clear();
            var searchTerm = searchField.getText().toLowerCase();

            Predicate<Netention.Note> isSimpleUserNote = n ->
                    !n.tags.contains(Netention.SystemTag.CHAT.value) &&
                            !n.tags.contains(Netention.SystemTag.CONFIG.value) &&
                            !n.tags.contains(Netention.SystemTag.CONTACT.value) &&
                            !n.tags.contains(Netention.SystemTag.TEMPLATE.value) &&
                            !n.tags.contains(Netention.SystemTag.NOSTR_FEED.value) &&
                            !n.tags.contains(Netention.SystemTag.SYSTEM_EVENT.value);

            core.notes.getAll(isSimpleUserNote.and(n1 -> {
                        if (searchTerm.isEmpty()) return true;
                        return n1.getTitle().toLowerCase().contains(searchTerm) ||
                                n1.getText().toLowerCase().contains(searchTerm) ||
                                n1.tags.stream().anyMatch(t -> t.toLowerCase().contains(searchTerm));
                    })).stream()
                    .sorted(Comparator.comparing((Netention.Note n) -> n.updatedAt).reversed())
                    .forEach(listModel::addElement);

            if (selected != null && listModel.contains(selected)) {
                noteJList.setSelectedValue(selected, true);
            }
        }
    }
}
