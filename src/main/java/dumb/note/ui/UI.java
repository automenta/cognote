package dumb.note.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import dumb.note.Crypto;
import dumb.note.LM;
import dumb.note.Netention;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.text.*;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

public class UI {
    private static final Logger logger = LoggerFactory.getLogger(UI.class);


    public enum ActionID {
        NEW_NOTE, NEW_FROM_TEMPLATE, EXIT, CUT, COPY, PASTE,
        TOGGLE_INSPECTOR, TOGGLE_NAV_PANEL, SAVE_NOTE, PUBLISH_NOTE, SET_GOAL,
        LINK_NOTE, LLM_ACTIONS_MENU, DELETE_NOTE, TOGGLE_NOSTR, MY_PROFILE,
        PUBLISH_PROFILE, ADD_NOSTR_FRIEND, MANAGE_RELAYS, LLM_SETTINGS, SYNC_ALL, ABOUT,
        SHOW_MY_NOSTR_PROFILE_EDITOR, MANAGE_NOSTR_RELAYS_POPUP, CONFIGURE_NOSTR_IDENTITY_POPUP,
        WINDOW_MENU, CASCADE_WINDOWS, TILE_WINDOWS_HORIZONTALLY, TILE_WINDOWS_VERTICALLY, CLOSE_ACTIVE_WINDOW, CLOSE_ALL_WINDOWS,
        SHOW_INBOX, VIEW_CONTACT_PROFILE // NEW
    }

    interface Dirtyable {
        boolean isDirty();
    }

    interface Savable {
        void saveChanges();
    }


    interface NoteProvider {
        @Nullable Netention.Note getAssociatedNote();

        String getResourceTitle();

        String getResourceType();
    }

    interface DirtyableSavableComponent extends Dirtyable, Savable, NoteProvider {
    }

    public static class AppAction extends AbstractAction {
        private final Consumer<ActionEvent> performer;
        private Supplier<Boolean> enabledCalculator;
        private Supplier<Boolean> selectedCalculator;

        public AppAction(String name, @Nullable Icon icon, @Nullable KeyStroke accelerator, Consumer<ActionEvent> performer) {
            super(name, icon);
            if (accelerator != null) putValue(ACCELERATOR_KEY, accelerator);
            this.performer = performer;
        }

        public AppAction(String name, KeyStroke accelerator, Consumer<ActionEvent> performer) {
            this(name, null, accelerator, performer);
        }

        public AppAction(String name, Consumer<ActionEvent> performer) {
            this(name, null, null, performer);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (performer != null) performer.accept(e);
        }

        public AppAction setEnabledCalculator(Supplier<Boolean> calculator) {
            this.enabledCalculator = calculator;
            return this;
        }

        public AppAction setSelectedCalculator(Supplier<Boolean> calculator) {
            this.selectedCalculator = calculator;
            return this;
        }

        public void updateState() {
            if (enabledCalculator != null) setEnabled(enabledCalculator.get());
            if (selectedCalculator != null) putValue(Action.SELECTED_KEY, selectedCalculator.get());
        }
    }

    protected static class ActionRegistry {
        private final Map<ActionID, Action> actions = new EnumMap<>(ActionID.class);

        public void register(ActionID id, Action action) {
            actions.put(id, action);
        }

        public Action get(ActionID id) {
            Action action = actions.get(id);
            if (action == null) {
                logger.warn("Action not found for ID: {}. Returning a no-op action.", id);
                return new AppAction("Missing: " + id.name(), e -> {
                });
            }
            return action;
        }

        public Collection<Action> getAllActions() {
            return actions.values();
        }
    }

    public record ActionableItem(String id, String planNoteId, String description, String type, Object rawData,
                                 Runnable action, String sourceEventId) {
    }

    abstract static class BaseAppFrame extends JFrame {
        protected final Netention.Core core;
        protected final String baseTitle;
        protected final JLabel statusBarLabel;
        protected final JPanel defaultEditorHostPanel;
        protected final ActionRegistry actionRegistry = new ActionRegistry();

        public BaseAppFrame(Netention.Core core, String title, int width, int height) {
            this.core = core;
            this.baseTitle = title;
            setTitle(title);
            setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            setSize(width, height);
            setLocationRelativeTo(null);

            this.defaultEditorHostPanel = new JPanel(new BorderLayout());
            add(this.defaultEditorHostPanel, BorderLayout.CENTER);

            statusBarLabel = new JLabel("Ready.");
            statusBarLabel.setBorder(new EmptyBorder(2, 5, 2, 5));
            add(statusBarLabel, BorderLayout.SOUTH);

            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    handleWindowClose();
                }
            });
            core.addCoreEventListener(this::handleCoreEventBase);
            updateTheme(core.cfg.ui.theme);
        }

        protected JPanel getEditorHostPanel() {
            return defaultEditorHostPanel;
        }

        protected void setEditorComponent(JComponent panel) {
            var targetHost = getEditorHostPanel();
            targetHost.removeAll();
            if (panel != null) targetHost.add(panel, BorderLayout.CENTER);
            targetHost.revalidate();
            targetHost.repaint();
            updateFrameTitleWithDirtyState(false); // Initial state
            updateActionStates();
        }

        protected Optional<Netention.Note> getCurrentEditedNote() {
            return getActiveDirtyableSavableComponent().flatMap(dsc -> ofNullable(dsc.getAssociatedNote()));
        }

        protected abstract Optional<DirtyableSavableComponent> getActiveDirtyableSavableComponent();

        protected void saveActiveDirtyableSavableComponent(boolean andPublish) {
            getActiveDirtyableSavableComponent().ifPresent(dsc -> {
                if (dsc instanceof NoteEditorPanel nep) nep.saveNote(andPublish);
                else if (dsc instanceof ConfigNoteEditorPanel cnep) cnep.saveChanges(); // andPublish ignored
                else dsc.saveChanges();
            });
        }

        protected void deleteActiveNote() {
            // Implemented by subclasses (App for MDI, potentially SimpleNote)
        }


        protected void handleCoreEventBase(Netention.Core.CoreEvent event) {
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater(() -> handleCoreEventBase(event));
                return;
            }
            if (event.type() == Netention.Core.CoreEventType.CONFIG_CHANGED) {
                if ("ui_theme_updated".equals(event.data())) updateTheme(core.cfg.ui.theme);
                updateActionStates(); // Config changes might affect action enablement
            }
            if (event.type() == Netention.Core.CoreEventType.STATUS_MESSAGE && event.data() instanceof String msg) {
                updateStatus(msg);
            }
        }

        public void updateActionStates() {
            actionRegistry.getAllActions().forEach(action -> {
                if (action instanceof AppAction appAction) appAction.updateState();
            });
        }

        protected void updateStatus(String message) {
            SwingUtilities.invokeLater(() -> statusBarLabel.setText("ℹ️ " + message));
        }

        protected void handleWindowClose() {
            if (!canSwitchOrCloseContent(false, null)) return;
            if (core.net.isEnabled()) core.net.setEnabled(false);
            System.exit(0);
        }

        protected boolean canSwitchOrCloseContent(boolean ignoredIsSwitchingToNewUnsaved, @Nullable JInternalFrame frameToClose) {
            DirtyableSavableComponent dirtyable = null;
            Component dialogParentComponent = this;

            if (frameToClose instanceof App.BaseInternalFrame bif) {
                dialogParentComponent = bif;
                if (bif.getMainPanel() instanceof DirtyableSavableComponent dsc) dirtyable = dsc;
                else if (bif instanceof DirtyableSavableComponent dscFrame) dirtyable = dscFrame;
            } else if (frameToClose == null) { // SDI context or MDI closing all
                dirtyable = getActiveDirtyableSavableComponent().orElse(null);
            }


            if (dirtyable != null && dirtyable.isDirty()) {
                String title = Optional.ofNullable(dirtyable.getResourceTitle()).filter(s -> !s.isEmpty()).orElse("Untitled");
                String resourceType = dirtyable.getResourceType();
                int result = JOptionPane.showConfirmDialog(dialogParentComponent,
                        resourceType + " '" + title + "' has unsaved changes. Save them?",
                        "❓ Unsaved " + resourceType,
                        JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);

                if (result == JOptionPane.YES_OPTION) {
                    dirtyable.saveChanges();
                    return !dirtyable.isDirty();
                }
                return result == JOptionPane.NO_OPTION;
            }
            return true;
        }


        public void updateFrameTitleWithDirtyState(boolean isDirty) {
            setTitle(isDirty ? baseTitle + " *" : baseTitle);
            updateActionStates();
        }

        protected void updateTheme(String themeName) {
            try {
                UIManager.setLookAndFeel("Nimbus (Dark)".equalsIgnoreCase(themeName) ? "javax.swing.plaf.nimbus.NimbusLookAndFeel" : UIManager.getSystemLookAndFeelClassName());
                SwingUtilities.updateComponentTreeUI(this);
            } catch (Exception e) {
                logger.warn("Failed to set theme '{}': {}", themeName, e.getMessage());
            }
        }

        protected Optional<JTextComponent> getActiveTextComponent() {
            var focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (focusOwner instanceof JTextComponent tc) return Optional.of(tc);

            return getActiveDirtyableSavableComponent()
                    .filter(NoteEditorPanel.class::isInstance)
                    .map(NoteEditorPanel.class::cast)
                    .map(nep -> nep.contentPane);
        }

        protected JMenuItem createMenuItem(ActionID actionId) {
            return new JMenuItem(actionRegistry.get(actionId));
        }

        protected JMenuItem createMenuItem(String text, ActionID actionId) {
            Action action = actionRegistry.get(actionId);
            JMenuItem item = new JMenuItem(action);
            item.setText(text); // Override text if action's name is not desired
            return item;
        }

        protected JCheckBoxMenuItem createCheckBoxMenuItem(ActionID actionId) {
            return new JCheckBoxMenuItem(actionRegistry.get(actionId));
        }

        protected JRadioButtonMenuItem createRadioButtonMenuItem(String text, boolean selected, ButtonGroup group, ActionListener listener) {
            var item = new JRadioButtonMenuItem(text, selected);
            item.addActionListener(listener);
            group.add(item);
            return item;
        }


        protected void displayNoteInEditor(@Nullable Netention.Note note) {
            var nep = new NoteEditorPanel(core, note, this,
                    () -> { // onSave
                        var host = getEditorHostPanel();
                        if (host.getComponentCount() > 0 && host.getComponent(0) instanceof NoteEditorPanel currentNep) {
                            var savedNote = currentNep.getCurrentNote();
                            updateStatus(savedNote == null || savedNote.id == null ? "📝 Note created" : "💾 Note saved: " + savedNote.getTitle());
                        }
                    },
                    null, // inspector panel for SDI is null
                    this::updateFrameTitleWithDirtyState // onDirtyStateChange
            );
            setEditorComponent(nep);
        }
    }

    public static class NavPanel extends JPanel {
        private final Netention.Core core;
        private final App uiRef;
        private final DefaultListModel<Netention.Note> listModel = new DefaultListModel<>();
        private final JList<Netention.Note> list = new JList<>(listModel);
        private final JTextField searchField = new JTextField(15);
        private final JButton semanticSearchButton;
        private final JComboBox<View> viewSelector;
        private final JComboBox<SortOption> sortSelector;
        private final JPanel tagFilterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        private final Set<String> activeTagFilters = new HashSet<>();

        private enum SortOption {
            UPDATED_DESC("📝 Updated (Newest)"),
            UPDATED_ASC("📝 Updated (Oldest)"),
            TITLE_ASC("🔤 Title (A-Z)"),
            TITLE_DESC("🔤 Title (Z-A)"),
            PRIORITY_DESC("❗ Priority (High-Low)"),
            PRIORITY_ASC("❕ Priority (Low-High)"),
            STATUS_ASC("📊 Status (A-Z)"),
            STATUS_DESC("📊 Status (Z-A)");

            private final String displayName;
            SortOption(String displayName) { this.displayName = displayName; }
            @Override public String toString() { return displayName; }

            public Comparator<Netention.Note> getComparator() {
                return switch (this) {
                    case UPDATED_ASC -> Comparator.comparing(n -> n.updatedAt);
                    case TITLE_ASC -> Comparator.comparing(Netention.Note::getTitle, String.CASE_INSENSITIVE_ORDER);
                    case TITLE_DESC -> Comparator.comparing(Netention.Note::getTitle, String.CASE_INSENSITIVE_ORDER).reversed();
                    case PRIORITY_ASC -> Comparator.comparingInt(Netention.Note::getPriority);
                    case PRIORITY_DESC -> Comparator.comparingInt(Netention.Note::getPriority).reversed();
                    case STATUS_ASC -> Comparator.comparing(Netention.Note::getStatus, String.CASE_INSENSITIVE_ORDER);
                    case STATUS_DESC -> Comparator.comparing(Netention.Note::getStatus, String.CASE_INSENSITIVE_ORDER).reversed();
                    default -> Comparator.comparing((Netention.Note n) -> n.updatedAt).reversed();
                };
            }
        }

        public NavPanel(Netention.Core core, App uiRef, Consumer<Netention.Note> ignoredOnShowNote, Consumer<Netention.Note> ignoredOnShowChat, Consumer<Netention.Note> ignoredOnShowConfigNote, Runnable onNewNote, Consumer<Netention.Note> onNewNoteFromTemplate) {
            this.core = core;
            this.uiRef = uiRef;
            setLayout(new BorderLayout(5, 5));
            setBorder(new EmptyBorder(5, 5, 5, 5));
            core.addCoreEventListener(event -> {
                if (Set.of(Netention.Core.CoreEventType.NOTE_ADDED, Netention.Core.CoreEventType.NOTE_UPDATED, Netention.Core.CoreEventType.NOTE_DELETED, Netention.Core.CoreEventType.CONFIG_CHANGED, Netention.Core.CoreEventType.CHAT_MESSAGE_ADDED).contains(event.type()))
                    SwingUtilities.invokeLater(this::refreshNotes);
            });
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    ofNullable(list.getSelectedValue()).ifPresent(uiRef::display);
                }
            });
            list.addMouseListener(new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e)) {
                        int index = list.locationToIndex(e.getPoint());
                        if (index != -1 && list.getCellBounds(index, index).contains(e.getPoint())) {
                            list.setSelectedIndex(index);
                            ofNullable(list.getSelectedValue()).ifPresent(selectedNote -> showNoteContextMenu(selectedNote, e));
                        }
                    }
                }
            });
            add(new JScrollPane(list), BorderLayout.CENTER);
            var topControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
            topControls.add(UIUtil.button("➕", "New Note", onNewNote));
            topControls.add(UIUtil.button("📄", "New from Template", _ -> {
                var templates = core.notes.getAll(n -> n.tags.contains(Netention.SystemTag.TEMPLATE.value));
                if (templates.isEmpty()) {
                    JOptionPane.showMessageDialog(this, "No templates found.", "🤷 No Templates", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                var selectedTemplate = (Netention.Note) JOptionPane.showInputDialog(this, "Select a template:", "📄 New from Template", JOptionPane.PLAIN_MESSAGE, null, templates.toArray(), templates.stream().findFirst().orElse(null));
                if (selectedTemplate != null) onNewNoteFromTemplate.accept(selectedTemplate);
            }));

            var viewAndSortPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2,0));

            viewSelector = new JComboBox<>(View.values());
            viewSelector.setToolTipText("Select Note View");
            viewSelector.setSelectedItem(View.NOTES);
            viewSelector.addActionListener(_ -> {
                refreshNotes();
                updateServiceDependentButtonStates();
            });
            viewAndSortPanel.add(viewSelector);

            sortSelector = new JComboBox<>(SortOption.values());
            sortSelector.setToolTipText("Sort Notes By");
            sortSelector.setSelectedItem(SortOption.UPDATED_DESC);
            sortSelector.addActionListener(_ -> refreshNotes());
            viewAndSortPanel.add(sortSelector);


            var searchPanel = new JPanel(new BorderLayout(5, 0));
            searchPanel.add(new JLabel("🔍"), BorderLayout.WEST);
            searchField.setToolTipText("Search notes by title, content, or tags");
            searchPanel.add(searchField, BorderLayout.CENTER);
            searchField.getDocument().addDocumentListener(new FieldUpdateListener(_ -> refreshNotes()));
            semanticSearchButton = UIUtil.button("🧠", "AI Search", _ -> performSemanticSearch());
            var combinedSearchPanel = new JPanel(new BorderLayout());
            combinedSearchPanel.add(searchPanel, BorderLayout.CENTER);
            combinedSearchPanel.add(semanticSearchButton, BorderLayout.EAST);
            var tagScrollPane = new JScrollPane(tagFilterPanel, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            tagScrollPane.setPreferredSize(new Dimension(200, 60));
            var topBox = Box.createVerticalBox();
            Stream.of(topControls, viewAndSortPanel, combinedSearchPanel, tagScrollPane).forEach(comp -> {
                comp.setAlignmentX(Component.LEFT_ALIGNMENT);
                if (comp.getLayout() instanceof FlowLayout) {
                     comp.setMaximumSize(new Dimension(Integer.MAX_VALUE, comp.getPreferredSize().height));
                } else {
                     comp.setMaximumSize(new Dimension(Integer.MAX_VALUE, comp.getMaximumSize().height));
                }
                topBox.add(comp);
            });
            add(topBox, BorderLayout.NORTH);
            refreshNotes();
            updateServiceDependentButtonStates();
        }

        private void performSemanticSearch() {
            if (!core.lm.isReady()) {
                JOptionPane.showMessageDialog(this, "LLM Service not ready.", "LLM Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            var query = JOptionPane.showInputDialog(this, "Semantic search query:");
            if (query == null || query.trim().isEmpty()) return;

            core.fireCoreEvent(Netention.Core.CoreEventType.STATUS_MESSAGE, "🧠 Performing semantic search...");
            CompletableFuture.supplyAsync(() -> core.lm.generateEmbedding(query))
                    .thenAcceptAsync(queryEmbOpt -> queryEmbOpt.ifPresentOrElse(qEmb -> {
                        var notesWithEmb = core.notes.getAllNotes().stream().filter(n ->
                                !n.tags.contains(Netention.SystemTag.CONFIG.value) &&
                                        n.getEmbeddingV1() != null && n.getEmbeddingV1().length == qEmb.length
                        ).toList();
                        if (notesWithEmb.isEmpty()) {
                            JOptionPane.showMessageDialog(this, "No notes with embeddings found for comparison.", "🧠 Semantic Search", JOptionPane.INFORMATION_MESSAGE);
                            return;
                        }
                        var scored = notesWithEmb.stream()
                                .map(n -> Map.entry(n, LM.cosineSimilarity(qEmb, n.getEmbeddingV1())))
                                .filter(entry -> entry.getValue() > 0.1)
                                .sorted(Map.Entry.<Netention.Note, Double>comparingByValue().reversed())
                                .map(Map.Entry::getKey)
                                .collect(Collectors.toList());
                        if (scored.isEmpty())
                            JOptionPane.showMessageDialog(this, "No relevant notes found.", "🧠 Semantic Search", JOptionPane.INFORMATION_MESSAGE);
                        else refreshNotes(scored);
                        core.fireCoreEvent(Netention.Core.CoreEventType.STATUS_MESSAGE, "🧠 Semantic search complete. " + scored.size() + " results.");
                    }, () -> JOptionPane.showMessageDialog(this, "Failed to generate embedding for query.", "LLM Error", JOptionPane.ERROR_MESSAGE)), SwingUtilities::invokeLater)
                    .exceptionally(ex -> {
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Error during semantic search: " + ex.getCause().getMessage(), "Search Error", JOptionPane.ERROR_MESSAGE));
                        core.fireCoreEvent(Netention.Core.CoreEventType.STATUS_MESSAGE, "⚠️ Semantic search failed.");
                        return null;
                    });
        }

        public void selectViewAndNote(View view, String noteId) {
            viewSelector.setSelectedItem(view);
            SwingUtilities.invokeLater(() -> core.notes.get(noteId).ifPresent(noteToSelect -> {
                int index = -1;
                for (int i = 0; i < listModel.getSize(); i++) {
                    if (listModel.getElementAt(i).id.equals(noteToSelect.id)) {
                        index = i;
                        break;
                    }
                }
                if (index >= 0) {
                    list.setSelectedIndex(index);
                    list.ensureIndexIsVisible(index);
                }
                uiRef.display(noteToSelect);
            }));
        }

        @SuppressWarnings("unchecked")
        private void showNoteContextMenu(Netention.Note note, MouseEvent e) {
            var contextMenu = new JPopupMenu();
            if (note.tags.contains(Netention.SystemTag.NOSTR_FEED.value) && core.lm.isReady() && core.net.isEnabled()) {
                var processMenu = new JMenu("💡 Process with My LM");
                processMenu.setEnabled(core.lm.isReady());
                Stream.of("Summarize", "Decompose Task", "Identify Concepts").forEach(tool ->
                        processMenu.add(UIUtil.menuItem(tool, _ -> processSharedNoteWithLM(note, tool)))
                );
                contextMenu.add(processMenu);
            }
            if (note.tags.contains(Netention.SystemTag.SYSTEM_EVENT.value)) {
                var status = (String) note.content.getOrDefault(Netention.ContentKey.STATUS.getKey(), "");
                if (Set.of("PROCESSED", "FAILED_PROCESSING", Netention.PlanState.FAILED.name()).contains(status)) {
                    contextMenu.add(UIUtil.menuItem("🗑️ Delete Processed/Failed Event", _ -> {
                        if (JOptionPane.showConfirmDialog(this, "Delete system event note '" + note.getTitle() + "'?", "Confirm Delete", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                            core.deleteNote(note.id);
                        }
                    }));
                }
            }
            if (!note.tags.contains(Netention.SystemTag.CONFIG.value)) {
                contextMenu.add(UIUtil.menuItem("🗑️ Delete Note", _ -> {
                    if (JOptionPane.showConfirmDialog(this, "Delete note '" + note.getTitle() + "'?", "Confirm Delete", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                        core.deleteNote(note.id);
                    }
                }));
            }
            if (contextMenu.getComponentCount() > 0) contextMenu.show(e.getComponent(), e.getX(), e.getY());
        }

        private void processSharedNoteWithLM(Netention.Note sharedNote, String toolName) {
            if (!core.lm.isReady()) {
                JOptionPane.showMessageDialog(this, "LLM not ready.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            var originalPublisherNpub = (String) sharedNote.meta.get(Netention.Metadata.NOSTR_PUB_KEY.key);
            if (originalPublisherNpub == null) {
                JOptionPane.showMessageDialog(this, "Original publisher (npub) not found in note metadata.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            core.fireCoreEvent(Netention.Core.CoreEventType.STATUS_MESSAGE, "💡 Processing with LLM: " + toolName);
            CompletableFuture.supplyAsync(() -> {
                        var contentToProcess = sharedNote.getContentForEmbedding();
                        return switch (toolName) {
                            case "Summarize" -> core.lm.summarize(contentToProcess);
                            case "Decompose Task" ->
                                    core.lm.decomposeTask(contentToProcess).map(list -> String.join("\n- ", list));
                            case "Identify Concepts" -> core.lm.chat("Identify key concepts in:\n\n" + contentToProcess);
                            default -> Optional.empty();
                        };
                    }).thenAcceptAsync(resultOpt -> resultOpt.ifPresentOrElse(result -> {
                        try {
                            var payload = Map.of("type", "netention_lm_result", "sourceNoteId", sharedNote.id, "tool", toolName, "result", result, "processedByNpub", core.net.getPublicKeyBech32());
                            core.net.sendDirectMessage(originalPublisherNpub, core.json.writeValueAsString(payload));
                            JOptionPane.showMessageDialog(this, toolName + " result sent to " + originalPublisherNpub.substring(0, 10) + "...", "💡 LM Result Sent", JOptionPane.INFORMATION_MESSAGE);
                            core.fireCoreEvent(Netention.Core.CoreEventType.STATUS_MESSAGE, "💡 LM Result sent for " + toolName);
                        } catch (JsonProcessingException jpe) {
                            JOptionPane.showMessageDialog(this, "Error packaging LM result for DM: " + jpe.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                            core.fireCoreEvent(Netention.Core.CoreEventType.STATUS_MESSAGE, "⚠️ Error sending LM Result");
                        }
                    }, () -> core.fireCoreEvent(Netention.Core.CoreEventType.STATUS_MESSAGE, "💡 LLM processing yielded no result for " + toolName)), SwingUtilities::invokeLater)
                    .exceptionally(ex -> {
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Error processing with LM: " + ex.getCause().getMessage(), "Error", JOptionPane.ERROR_MESSAGE));
                        core.fireCoreEvent(Netention.Core.CoreEventType.STATUS_MESSAGE, "⚠️ LLM processing error for " + toolName);
                        return null;
                    });
        }

        public void updateServiceDependentButtonStates() {
            boolean llmReadyForSearch = core.lm.isReady() && Set.of(View.NOTES, View.GOALS, View.INBOX).contains(viewSelector.getSelectedItem());
            semanticSearchButton.setEnabled(llmReadyForSearch);
        }

        public void refreshNotes() {
            refreshNotes(null);
        }

        public void refreshNotes(@Nullable List<Netention.Note> notesToDisplay) {
            var listSelectedNoteIdBeforeRefresh = ofNullable(list.getSelectedValue()).map(n -> n.id).orElse(null);
            listModel.clear();

            var finalFilter = getPredicate();
            var selectedSortOption = (SortOption) Objects.requireNonNullElse(sortSelector.getSelectedItem(), SortOption.UPDATED_DESC);
            var comparator = selectedSortOption.getComparator();

            var filteredNotes = (notesToDisplay != null ? notesToDisplay.stream().filter(finalFilter)
                    : core.notes.getAll(finalFilter).stream())
                    .sorted(comparator)
                    .toList();

            filteredNotes.forEach(listModel::addElement);
            updateTagFilterPanel(filteredNotes);

            Netention.Note noteToReselect = uiRef.getActiveInternalFrame()
                    .map(App.BaseInternalFrame::getAssociatedNote)
                    .filter(editorNote -> editorNote.id != null)
                    .flatMap(editorNote -> filteredNotes.stream().filter(n -> editorNote.id.equals(n.id)).findFirst())
                    .orElse(null);

            if (noteToReselect == null && listSelectedNoteIdBeforeRefresh != null) {
                final String finalSelectedId = listSelectedNoteIdBeforeRefresh;
                noteToReselect = filteredNotes.stream().filter(n -> finalSelectedId.equals(n.id)).findFirst().orElse(null);
            }

            if (noteToReselect != null) {
                list.setSelectedValue(noteToReselect, true);
            } else if (!filteredNotes.isEmpty() && list.getSelectedIndex() == -1) {
            }
        }

        private @NotNull Predicate<Netention.Note> getPredicate() {
            var viewFilter = ((View) Objects.requireNonNullElse(viewSelector.getSelectedItem(), View.NOTES)).getFilter();
            Predicate<Netention.Note> tagFilter = n -> activeTagFilters.isEmpty() || n.tags.containsAll(activeTagFilters);
            Predicate<Netention.Note> searchFilter = n -> {
                var term = searchField.getText().toLowerCase().trim();
                if (term.isEmpty()) return true;
                return n.getTitle().toLowerCase().contains(term) ||
                        n.getText().toLowerCase().contains(term) ||
                        n.tags.stream().anyMatch(t -> t.toLowerCase().contains(term));
            };
            return viewFilter.and(tagFilter).and(searchFilter);
        }

        private void updateTagFilterPanel(List<Netention.Note> currentNotesInList) {
            tagFilterPanel.removeAll();
            var alwaysExclude = Stream.of(Netention.SystemTag.values()).map(tag -> tag.value).collect(Collectors.toSet());

            var counts = currentNotesInList.stream()
                    .flatMap(n -> n.tags.stream())
                    .filter(t -> !alwaysExclude.contains(t) && !t.startsWith("#"))
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

            counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .limit(15)
                    .forEach(entry -> {
                        var tagButton = new JToggleButton(entry.getKey() + " (" + entry.getValue() + ")");
                        tagButton.setMargin(new Insets(1, 3, 1, 3));
                        tagButton.setSelected(activeTagFilters.contains(entry.getKey()));
                        tagButton.addActionListener(_ -> {
                            if (tagButton.isSelected()) activeTagFilters.add(entry.getKey());
                            else activeTagFilters.remove(entry.getKey());
                            refreshNotes();
                        });
                        tagFilterPanel.add(tagButton);
                    });
            tagFilterPanel.revalidate();
            tagFilterPanel.repaint();
        }

        public enum View {
            INBOX("📥 Inbox", n -> n.tags.contains(Netention.SystemTag.NOSTR_FEED.value) || n.tags.contains(Netention.SystemTag.CHAT.value)),
            NOTES("📝 My Notes", n -> isUserNote(n) && !n.tags.contains(Netention.SystemTag.GOAL_WITH_PLAN.value) && !n.tags.contains(Netention.SystemTag.TEMPLATE.value) && !n.tags.contains(Netention.SystemTag.CONTACT.value)),
            GOALS("🎯 Goals", n -> n.tags.contains(Netention.SystemTag.GOAL_WITH_PLAN.value) && isUserNote(n)),
            TEMPLATES("📄 Templates", n -> n.tags.contains(Netention.SystemTag.TEMPLATE.value) && isUserNote(n)),
            CONTACTS("👥 Contacts", n -> n.tags.contains(Netention.SystemTag.CONTACT.value)),
            SETTINGS("⚙️ Settings", n -> n.tags.contains(Netention.SystemTag.CONFIG.value)),
            SYSTEM("🛠️ System Internals", n -> n.tags.contains(Netention.SystemTag.SYSTEM_EVENT.value) || n.tags.contains(Netention.SystemTag.SYSTEM_PROCESS_HANDLER.value));
            private final String displayName;
            private final Predicate<Netention.Note> filter;

            View(String displayName, Predicate<Netention.Note> filter) {
                this.displayName = displayName;
                this.filter = filter;
            }

            private static boolean isUserNote(Netention.Note n) {
                return Stream.of(Netention.SystemTag.NOSTR_FEED, Netention.SystemTag.CONFIG,
                                Netention.SystemTag.SYSTEM_EVENT, Netention.SystemTag.SYSTEM_PROCESS_HANDLER,
                                Netention.SystemTag.NOSTR_RELAY, Netention.SystemTag.SYSTEM_NOTE,
                                Netention.SystemTag.CHAT)
                        .map(tag -> tag.value).noneMatch(n.tags::contains);
            }

            @Override
            public String toString() {
                return displayName;
            }

            public Predicate<Netention.Note> getFilter() {
                return filter;
            }
        }
    }

    public static class NoteEditorPanel extends JPanel implements DirtyableSavableComponent {
        final JTextField titleF = new JTextField(40);
        final JTextPane contentPane = new JTextPane();
        final JTextField tagsF = new JTextField(40);
        final JComboBox<String> statusComboBox;
        final JSpinner prioritySpinner;

        final JToolBar toolBar = new JToolBar();
        private final Netention.Core core;
        private final BaseAppFrame appFrame;
        @Nullable
        private final InspectorPanel inspectorPanelRef;
        private final HTMLEditorKit htmlEditorKit = new HTMLEditorKit();
        private final JLabel embStatusL = new JLabel("Embedding: Unknown");
        private final List<DocumentListener> activeDocumentListeners = new ArrayList<>();
        private final Consumer<Boolean> onDirtyStateChangeCallback;
        public Runnable onSaveCb;
        public Netention.Note currentNote;
        private boolean userModified = false;
        private boolean readOnlyMode = false;
        private boolean externallyUpdated = false;
        private JComboBox<String> fontComboBoxField; // Made field for listener access
        private JComboBox<String> fontSizeComboBoxField; // Made field for listener access
        private String[] commonFontsField; // Made field for listener access


        public NoteEditorPanel(Netention.Core core, Netention.Note note, BaseAppFrame appFrame, Runnable onSaveCb, @Nullable InspectorPanel inspectorPanelRef, Consumer<Boolean> onDirtyStateChangeCallback) {
            super(new BorderLayout(5, 5));
            setBorder(new EmptyBorder(5, 5, 5, 5));
            this.core = core;
            this.currentNote = note;
            this.appFrame = appFrame;
            this.onSaveCb = onSaveCb;
            this.inspectorPanelRef = inspectorPanelRef;
            this.onDirtyStateChangeCallback = onDirtyStateChangeCallback;

            // Initialize status and priority controls
            String[] statuses = {"Idle", "In Progress", "Completed", "Blocked"};
            statusComboBox = new JComboBox<>(statuses);
            prioritySpinner = new JSpinner(new SpinnerNumberModel(0, -100, 100, 1));

            setupToolbar(); // Toolbar setup needs to happen before form panel if it uses fields initialized here
            add(toolBar, BorderLayout.NORTH);

            var formP = new JPanel(new GridBagLayout());
            var gbc = new GridBagConstraints();
            gbc.insets = new Insets(2, 2, 2, 2);
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.anchor = GridBagConstraints.WEST;
            gbc.insets = new Insets(4, 4, 4, 4); // Slightly increased padding for better visual separation

            // Row 0: Title and Status
            UIUtil.addLabelAndComponent(formP, gbc, 0, "Title:", titleF);
            titleF.setToolTipText("The title of your note");
            gbc.gridx = 2;
            gbc.weightx = 0.2;
            UIUtil.addLabelAndComponent(formP, gbc, 0, "Status:", statusComboBox);
            statusComboBox.setToolTipText("Set the current status of the note (e.g., Idle, In Progress)");

            // Row 1: Tags and Priority
            gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.8;
            UIUtil.addLabelAndComponent(formP, gbc, 1, "Tags:", tagsF);
            tagsF.setToolTipText("Comma-separated tags. System tags like '" + Netention.SystemTag.TEMPLATE.value + "' or '" + Netention.SystemTag.GOAL_WITH_PLAN.value + "' can enable special features.");
            gbc.gridx = 2;
            gbc.weightx = 0.2;
            UIUtil.addLabelAndComponent(formP, gbc, 1, "Priority:", prioritySpinner);
            prioritySpinner.setToolTipText("Set the numerical priority of the note (higher is more important)");

            // Row 2: Content Pane (takes remaining space)
            gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 4;
            gbc.weightx = 1.0; gbc.weighty = 1.0;
            gbc.fill = GridBagConstraints.BOTH;
            formP.add(new JScrollPane(contentPane), gbc);

            // Row 3: Embedding Status
            gbc.gridy = 3; gbc.weighty = 0.0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            formP.add(embStatusL, gbc);

            add(formP, BorderLayout.CENTER);
            populateFields(note);
        }

        public void setReadOnlyMode(boolean readOnly) {
            this.readOnlyMode = readOnly;
            if (currentNote != null) {
                boolean isMyProfileNote = currentNote.tags.contains(Netention.SystemTag.MY_PROFILE.value);
                boolean editable = !isEffectivelyReadOnly() && !isMyProfileNote;
                titleF.setEditable(editable);
                tagsF.setEditable(editable);
                statusComboBox.setEnabled(editable);
                prioritySpinner.setEnabled(editable);
                contentPane.setEditable(!isEffectivelyReadOnly());
                updateServiceDependentButtonStates();
            }
        }

        private void clearDocumentListeners() {
            activeDocumentListeners.forEach(dl -> {
                titleF.getDocument().removeDocumentListener(dl);
                tagsF.getDocument().removeDocumentListener(dl);
                contentPane.getDocument().removeDocumentListener(dl);
            });
            activeDocumentListeners.clear();
            // Clear listeners for status and priority separately if they are not DocumentListeners
            for (ActionListener al : statusComboBox.getActionListeners()) { statusComboBox.removeActionListener(al); }
            for (javax.swing.event.ChangeListener cl : prioritySpinner.getChangeListeners()) { prioritySpinner.removeChangeListener(cl); }
        }

        private void setupDocumentListeners() {
            clearDocumentListeners();
            var listener = new FieldUpdateListener(_ -> {
                if (!userModified) {
                    userModified = true;
                    if (onDirtyStateChangeCallback != null) onDirtyStateChangeCallback.accept(true);
                    updateServiceDependentButtonStates();
                }
            });
            Stream.of(titleF, tagsF, contentPane).forEach(tc -> tc.getDocument().addDocumentListener(listener));
            activeDocumentListeners.add(listener);

            ActionListener dirtyingActionListener = e -> {
                if (!userModified) { userModified = true; if (onDirtyStateChangeCallback != null) onDirtyStateChangeCallback.accept(true); updateServiceDependentButtonStates(); }
            };
            statusComboBox.addActionListener(dirtyingActionListener);
            prioritySpinner.addChangeListener(e -> { // ChangeListener for JSpinner
                if (!userModified) { userModified = true; if (onDirtyStateChangeCallback != null) onDirtyStateChangeCallback.accept(true); updateServiceDependentButtonStates(); }
            });
        }

        private void setupToolbar() {
            toolBar.setFloatable(false);
            toolBar.setRollover(true);
            toolBar.setMargin(new Insets(2, 2, 2, 2));

            // Standard text actions with tooltips ensured by styleButton
            Stream.of(new StyledEditorKit.BoldAction(), new StyledEditorKit.ItalicAction(), new StyledEditorKit.UnderlineAction())
                    .map(this::styleButton).forEach(toolBar::add); // styleButton already sets tooltips

            toolBar.add(styleButton(new StrikeThroughAction()));
            toolBar.addSeparator();

            commonFontsField = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
            String[] displayFonts = Arrays.stream(commonFontsField)
                                .filter(f -> new Font(f, Font.PLAIN, 12).canDisplay('a'))
                                .limit(50)
                                .toArray(String[]::new);
            fontComboBoxField = new JComboBox<>(displayFonts);
            fontComboBoxField.setMaximumSize(new Dimension(150, fontComboBoxField.getPreferredSize().height));
            fontComboBoxField.setToolTipText("Select Font Family"); // Enhanced tooltip
            fontComboBoxField.addActionListener(e -> {
                if (contentPane.isEditable() && contentPane.isEnabled()) {
                    String selectedFont = (String) fontComboBoxField.getSelectedItem();
                    if (selectedFont != null) {
                        new StyledEditorKit.FontFamilyAction(selectedFont, selectedFont).actionPerformed(e);
                    }
                }
            });
            toolBar.add(fontComboBoxField);

            String[] fontSizes = {"8", "10", "12", "14", "16", "18", "20", "22", "24", "36", "48"};
            fontSizeComboBoxField = new JComboBox<>(fontSizes);
            fontSizeComboBoxField.setMaximumSize(new Dimension(60, fontSizeComboBoxField.getPreferredSize().height));
            fontSizeComboBoxField.setToolTipText("Select Font Size"); // Enhanced tooltip
            fontSizeComboBoxField.addActionListener(e -> {
                if (contentPane.isEditable() && contentPane.isEnabled()) {
                    int selectedSize = Integer.parseInt((String) fontSizeComboBoxField.getSelectedItem());
                    new StyledEditorKit.FontSizeAction(String.valueOf(selectedSize), selectedSize).actionPerformed(e);
                }
            });
            toolBar.add(fontSizeComboBoxField);

            JButton colorButton = UIUtil.button( "🎨", "Select Text Color", e -> { // Enhanced tooltip
                if (contentPane.isEditable() && contentPane.isEnabled()) {
                    Color newColor = JColorChooser.showDialog(this, "Choose Text Color", contentPane.getForeground());
                    if (newColor != null) {
                        new StyledEditorKit.ForegroundAction("color", newColor).actionPerformed(e);
                    }
                }
            });
            toolBar.add(colorButton);
            toolBar.addSeparator();

            toolBar.add(UIUtil.button("•", "Insert Unordered List", new InsertListAction(HTML.Tag.UL)));
            toolBar.add(UIUtil.button("1.", "Insert Ordered List", new InsertListAction(HTML.Tag.OL)));
            toolBar.addSeparator();

            Action saveAction = appFrame.actionRegistry.get(ActionID.SAVE_NOTE);
            if (saveAction != null) {
                JButton saveButton = new JButton(saveAction);
                saveButton.setText(""); // Assume icon is set by Action, make it icon-only
                saveButton.setToolTipText((String)saveAction.getValue(Action.NAME));
                toolBar.add(saveButton);
            }

            if (appFrame instanceof App) {
                Action publishAction = appFrame.actionRegistry.get(ActionID.PUBLISH_NOTE);
                if (publishAction != null) {
                    JButton publishButton = new JButton(publishAction);
                    publishButton.setText(""); // Icon-only
                    publishButton.setToolTipText((String)publishAction.getValue(Action.NAME));
                    toolBar.add(publishButton);
                }
            }

            toolBar.add(UIUtil.button("🔄", "Refresh Note", "Refresh content from external changes (if any)", _ -> refreshFromSource()));
            toolBar.addSeparator();

            Action setGoalAction = appFrame.actionRegistry.get(ActionID.SET_GOAL);
            if (appFrame instanceof App && setGoalAction != null) {
                 JButton setGoalButton = new JButton(setGoalAction);
                 setGoalButton.setText("🎯"); // Use icon text
                 setGoalButton.setToolTipText(setGoalAction.getValue(Action.NAME) + " (Alt: Click icon in toolbar)");
                 toolBar.add(setGoalButton);
            } else {
                toolBar.add(UIUtil.button("🎯", "Set as Goal/Plan", "Convert this note into a goal with a plan", _ -> setGoal()));
            }

            toolBar.addSeparator();
            JMenu llmMenu = getLlmMenu();
            llmMenu.setToolTipText("Perform Large Language Model actions on this note");
            toolBar.add(llmMenu);
        }

        private @NotNull JButton styleButton(StyledEditorKit.StyledTextAction action) {
            var btn = new JButton(action);
            String name = action.toString(), text; // Use action's defined name for tooltip
            Object actionNameValue = action.getValue(Action.NAME);
            if (actionNameValue != null) name = actionNameValue.toString();

            switch (action) {
                case StyledEditorKit.BoldAction _ -> text = "B";
                case StyledEditorKit.ItalicAction _ -> text = "I";
                case StyledEditorKit.UnderlineAction _ -> text = "U";
                case StrikeThroughAction _ -> text = "S";
                default -> text = name.length() > 0 ? name.substring(0, 1) : "?";
            }
            btn.setText(text);
            btn.setToolTipText(name); // Set tooltip from action name
            return btn;
        }

        private void setGoal() {
            if (currentNote == null || isEffectivelyReadOnly()) return;
            updateNoteFromFields();
            if (!currentNote.tags.contains(Netention.SystemTag.GOAL_WITH_PLAN.value)) {
                currentNote.tags.add(Netention.SystemTag.GOAL_WITH_PLAN.value);
            }
            if (core.lm.isReady() && currentNote.content.get(Netention.ContentKey.PLAN_STEPS.getKey()) == null) {
                try {
                    @SuppressWarnings("unchecked")
                    var steps = (List<Netention.Planner.PlanStep>) core.executeTool(Netention.Core.Tool.SUGGEST_PLAN_STEPS, Map.of(Netention.ToolParam.GOAL_TEXT.getKey(), currentNote.getContentForEmbedding()));
                    if (steps != null && !steps.isEmpty()) {
                        currentNote.content.put(Netention.ContentKey.PLAN_STEPS.getKey(), steps.stream().map(s -> core.json.convertValue(s, Map.class)).collect(Collectors.toList()));
                    }
                } catch (Exception ex) {
                    logger.error("Failed to suggest plan steps for goal '{}': {}", currentNote.getTitle(), ex.getMessage());
                }
            }
            saveNoteInternal(false);
            if (inspectorPanelRef != null) inspectorPanelRef.switchToPlanTab();
        }

        private JMenu getLlmMenu() {
            var llmMenu = new JMenu("💡 LLM");
            llmMenu.setToolTipText("LLM Actions for this note");
            Stream.of(LLMAction.values()).forEach(actionEnum ->
                    llmMenu.add(UIUtil.menuItem(actionEnum.label, actionEnum.name(), this::handleLLMActionFromToolbar)));

            Timer t = new Timer(500, e -> llmMenu.setEnabled(currentNote != null && !isEffectivelyReadOnly() && core.lm.isReady()));
            t.setRepeats(false);
            t.start();

            contentPane.addCaretListener(ce -> {
                if (!contentPane.isFocusOwner()) return;
                SwingUtilities.invokeLater(() -> {
                    AttributeSet attrs = contentPane.getCharacterAttributes();
                    String fontName = StyleConstants.getFontFamily(attrs);
                    if (fontName != null && fontComboBoxField != null) {
                        boolean found = false;
                        for (int i = 0; i < fontComboBoxField.getItemCount(); i++) {
                            if (fontComboBoxField.getItemAt(i).equalsIgnoreCase(fontName)) {
                                fontComboBoxField.setSelectedIndex(i);
                                found = true;
                                break;
                            }
                        }
                         if (!found && commonFontsField != null && Arrays.asList(commonFontsField).contains(fontName)) {
                         }
                    }

                    Integer fontSize = StyleConstants.getFontSize(attrs);
                    if (fontSize != null && fontSizeComboBoxField != null) {
                         boolean found = false;
                        for (int i = 0; i < fontSizeComboBoxField.getItemCount(); i++) {
                            if (fontSizeComboBoxField.getItemAt(i).equals(String.valueOf(fontSize))) {
                                fontSizeComboBoxField.setSelectedIndex(i);
                                found = true;
                                break;
                            }
                        }
                         if (!found) {
                         }
                    }
                });
            });
            return llmMenu;
        }

        static class StrikeThroughAction extends StyledEditorKit.StyledTextAction {
            public StrikeThroughAction() {
                super("font-strikethrough");
                putValue(Action.NAME, "Strikethrough");
            }

            @Override
            public void actionPerformed(ActionEvent e) {
                JEditorPane editor = getEditor(e);
                if (editor != null) {
                    StyledEditorKit kit = getStyledEditorKit(editor);
                    MutableAttributeSet attr = kit.getInputAttributes();
                    boolean strike = !StyleConstants.isStrikeThrough(attr);
                    SimpleAttributeSet sas = new SimpleAttributeSet();
                    StyleConstants.setStrikeThrough(sas, strike);
                    setCharacterAttributes(editor, sas, false);
                }
            }
             @Override
            public String toString() { return "Strikethrough"; }
        }

        static class InsertListAction extends AbstractAction {
            private final HTML.Tag listTag;

            public InsertListAction(HTML.Tag listTag) {
                super(listTag == HTML.Tag.UL ? "• Unordered List" : "1. Ordered List");
                this.listTag = listTag;
                putValue(SHORT_DESCRIPTION, "Insert " + (listTag == HTML.Tag.UL ? "Unordered" : "Ordered") + " List");
            }

            @Override
            public void actionPerformed(ActionEvent e) {
                JEditorPane editor = null;
                Object source = e.getSource();
                 if (source instanceof AbstractButton) { // Check if source is a button
                    Container parent = ((AbstractButton)source).getParent(); // Get toolbar
                    while (parent != null && !(parent instanceof NoteEditorPanel)) { // Find NoteEditorPanel
                        if (parent.getParent() == null) { // Avoid infinite loop if not found
                             parent = null; break;
                        }
                        parent = parent.getParent();
                    }
                    if (parent instanceof NoteEditorPanel nep) {
                        editor = nep.contentPane;
                    }
                }


                if (editor instanceof JTextPane textPane && editor.isEditable() && editor.isEnabled()) {
                    NoteEditorPanel nep = (NoteEditorPanel) SwingUtilities.getAncestorOfClass(NoteEditorPanel.class, textPane);

                    if (!(textPane.getEditorKit() instanceof HTMLEditorKit)) {
                        if (JOptionPane.showConfirmDialog(textPane,
                                "To insert a list, the note content type will be set to HTML. Continue?",
                                "Switch to HTML", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                            if (nep != null && nep.currentNote != null) {
                                nep.currentNote.setContentType(Netention.ContentType.TEXT_HTML.getValue());
                                textPane.setEditorKit(nep.htmlEditorKit);
                            } else { return; }
                        } else { return; }
                    }

                    HTMLEditorKit kit = (HTMLEditorKit) textPane.getEditorKit();
                    Document doc = textPane.getDocument();
                    int selectionStart = textPane.getSelectionStart();
                    int selectionEnd = textPane.getSelectionEnd();

                    String listStartTag = "<" + listTag.toString() + ">"; // Use toString() for HTML.Tag
                    String listEndTag = "</" + listTag + ">";
                    String listItemTag = "<li></li>";

                    try {
                        if (selectionStart == selectionEnd) {
                            kit.insertHTML((HTMLDocument) doc, selectionStart, listStartTag + listItemTag + listEndTag, 0, 0, listTag);
                        } else {
                            String selectedText = doc.getText(selectionStart, selectionEnd - selectionStart);
                            String[] lines = selectedText.split("\\r?\\n");
                            StringBuilder listItems = new StringBuilder();
                            for (String line : lines) {
                                listItems.append("<li>").append(line.isEmpty() ? "&nbsp;" : line).append("</li>"); // Handle empty lines
                            }
                            String htmlToInsert = listStartTag + listItems + listEndTag;
                            if (doc instanceof AbstractDocument) {
                                ((AbstractDocument)doc).replace(selectionStart, selectionEnd - selectionStart, htmlToInsert, null);
                            } else {
                                textPane.select(selectionStart, selectionEnd);
                                textPane.replaceSelection(htmlToInsert);
                            }
                        }
                    } catch (Exception ex) {
                        logger.warn("Error inserting list: {}", ex.getMessage());
                        JOptionPane.showMessageDialog(textPane, "Could not insert list: " + ex.getMessage(), "List Error", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }
        }


        private void handleLLMActionFromToolbar(ActionEvent e) {
            if (currentNote == null || !core.lm.isReady() || isEffectivelyReadOnly()) {
                JOptionPane.showMessageDialog(this, "LLM not ready, no note, or note is read-only.", "LLM Action", JOptionPane.WARNING_MESSAGE);
                return;
            }
            updateNoteFromFields();
            var action = LLMAction.valueOf(e.getActionCommand());
            core.fireCoreEvent(Netention.Core.CoreEventType.STATUS_MESSAGE, "💡 LLM Action: " + action.label + "...");

            switch (action) {
                case EMBED -> handleEmbedAction();
                case SUMMARIZE -> handleSummarizeAction();
                case ASK -> handleAskAction();
                case DECOMPOSE -> handleDecomposeAction();
                case SUGGEST_PLAN -> handleSuggestPlanAction();
            }
        }

        private void llmAsyncHandler(CompletableFuture<?> future, String successMessage, String failureMessage) {
            future.thenRunAsync(() -> {
                        populateFields(this.currentNote);
                        if (successMessage != null)
                            JOptionPane.showMessageDialog(this, successMessage, "💡 LLM", JOptionPane.INFORMATION_MESSAGE);
                        core.fireCoreEvent(Netention.Core.CoreEventType.STATUS_MESSAGE, "💡 LLM: " + (successMessage != null ? successMessage : "Action completed."));
                    }, SwingUtilities::invokeLater)
                    .exceptionally(ex -> {
                        logger.error("Error during LLM action: {}", ex.getMessage(), ex);
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, failureMessage + ": " + ex.getCause().getMessage(), "LLM Error", JOptionPane.ERROR_MESSAGE));
                        core.fireCoreEvent(Netention.Core.CoreEventType.STATUS_MESSAGE, "⚠️ LLM: " + failureMessage);
                        return null;
                    });
        }

        private void handleEmbedAction() {
            var future = CompletableFuture.runAsync(() ->
                    core.lm.generateEmbedding(currentNote.getContentForEmbedding()).ifPresentOrElse(emb -> {
                        currentNote.setEmbeddingV1(emb);
                        core.saveNote(currentNote);
                        SwingUtilities.invokeLater(() -> {
                            updateEmbeddingStatus();
                            if (inspectorPanelRef != null) inspectorPanelRef.loadRelatedNotes();
                        });
                    }, () -> {
                        throw new RuntimeException("Failed to generate embedding.");
                    })
            );
            llmAsyncHandler(future, "Embedding generated.", "Embedding failed");
        }

        private void handleSummarizeAction() {
            var future = CompletableFuture.runAsync(() ->
                    core.lm.summarize(currentNote.getContentForEmbedding()).ifPresent(s -> {
                        currentNote.meta.put(Netention.Metadata.LLM_SUMMARY.key, s);
                        core.saveNote(currentNote);
                        SwingUtilities.invokeLater(() -> {
                            if (inspectorPanelRef != null) inspectorPanelRef.displayLLMAnalysis();
                        });
                    })
            );
            llmAsyncHandler(future, "Summary saved to metadata.", "Summarization failed");
        }

        private void handleAskAction() {
            SwingUtilities.invokeLater(() -> {
                var q = JOptionPane.showInputDialog(this, "Ask about note content:");
                if (q != null && !q.trim().isEmpty()) {
                    core.fireCoreEvent(Netention.Core.CoreEventType.STATUS_MESSAGE, "💡 LLM: Asking question...");
                    CompletableFuture.supplyAsync(() -> core.lm.askAboutText(currentNote.getContentForEmbedding(), q))
                            .thenAcceptAsync(answerOpt -> answerOpt.ifPresentOrElse(
                                    a -> {
                                        JOptionPane.showMessageDialog(this, a, "Answer", JOptionPane.INFORMATION_MESSAGE);
                                        core.fireCoreEvent(Netention.Core.CoreEventType.STATUS_MESSAGE, "💡 LLM: Question answered.");
                                    },
                                    () -> {
                                        JOptionPane.showMessageDialog(this, "No answer provided by LLM.", "LLM", JOptionPane.INFORMATION_MESSAGE);
                                        core.fireCoreEvent(Netention.Core.CoreEventType.STATUS_MESSAGE, "💡 LLM: No answer for question.");
                                    }), SwingUtilities::invokeLater)
                            .exceptionally(ex -> {
                                logger.error("Error asking LLM: {}", ex.getMessage(), ex);
                                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Error asking LLM: " + ex.getCause().getMessage(), "LLM Error", JOptionPane.ERROR_MESSAGE));
                                core.fireCoreEvent(Netention.Core.CoreEventType.STATUS_MESSAGE, "⚠️ LLM: Error answering question.");
                                return null;
                            });
                }
            });
        }

        private void handleDecomposeAction() {
            var textToDecompose = currentNote.getTitle().isEmpty() ? currentNote.getContentForEmbedding() : currentNote.getTitle();
            var future = CompletableFuture.runAsync(() ->
                    core.lm.decomposeTask(textToDecompose).ifPresent(d -> {
                        currentNote.meta.put(Netention.Metadata.LLM_DECOMPOSITION.key, d);
                        core.saveNote(currentNote);
                        SwingUtilities.invokeLater(() -> {
                            if (inspectorPanelRef != null) inspectorPanelRef.displayLLMAnalysis();
                        });
                    })
            );
            llmAsyncHandler(future, "Decomposition saved to metadata.", "Decomposition failed");
        }

        @SuppressWarnings("unchecked")
        private void handleSuggestPlanAction() {
            var future = CompletableFuture.runAsync(() -> {
                var steps = (List<Netention.Planner.PlanStep>) core.executeTool(Netention.Core.Tool.SUGGEST_PLAN_STEPS, Map.of(Netention.ToolParam.GOAL_TEXT.getKey(), currentNote.getContentForEmbedding()));
                if (steps != null && !steps.isEmpty()) {
                    currentNote.content.put(Netention.ContentKey.PLAN_STEPS.getKey(), steps.stream().map(s -> core.json.convertValue(s, Map.class)).collect(Collectors.toList()));
                    if (!currentNote.tags.contains(Netention.SystemTag.GOAL_WITH_PLAN.value)) {
                        currentNote.tags.add(Netention.SystemTag.GOAL_WITH_PLAN.value);
                    }
                    core.saveNote(currentNote);
                    SwingUtilities.invokeLater(() -> {
                        if (inspectorPanelRef != null) inspectorPanelRef.switchToPlanTab();
                    });
                } else {
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "No plan steps suggested.", "💡 LLM", JOptionPane.INFORMATION_MESSAGE));
                }
            });
            llmAsyncHandler(future, "Suggested plan steps added.", "Plan suggestion failed");
        }


        private boolean isSystemReadOnly() {
            return currentNote != null && Stream.of(Netention.SystemTag.NOSTR_FEED, Netention.SystemTag.CONFIG,
                            Netention.SystemTag.SYSTEM_PROCESS_HANDLER, Netention.SystemTag.SYSTEM_EVENT,
                            Netention.SystemTag.SYSTEM_NOTE, Netention.SystemTag.NOSTR_RELAY)
                    .map(tag -> tag.value).anyMatch(currentNote.tags::contains);
        }

        private boolean isEffectivelyReadOnly() {
            return readOnlyMode || isSystemReadOnly();
        }

        public void populateFields(Netention.Note noteToDisplay) {
            this.currentNote = noteToDisplay;
            clearDocumentListeners();

            if (currentNote == null) {
                titleF.setText("");
                contentPane.setText("Select or create a note.");
                tagsF.setText("");
                statusComboBox.setSelectedItem("Idle");
                prioritySpinner.setValue(0);
                Stream.of(titleF, contentPane, tagsF, statusComboBox, prioritySpinner).forEach(c -> c.setEnabled(false));
                embStatusL.setText("No note loaded.");
            } else {
                titleF.setText(currentNote.getTitle());
                contentPane.setEditorKit(Netention.ContentType.TEXT_HTML.equals(currentNote.getContentTypeEnum()) ? htmlEditorKit : new StyledEditorKit());
                contentPane.setText(currentNote.getText());
                contentPane.setCaretPosition(0);
                tagsF.setText(String.join(", ", currentNote.tags));
                statusComboBox.setSelectedItem(currentNote.getStatus());
                prioritySpinner.setValue(currentNote.getPriority());
                updateEmbeddingStatus();

                boolean isMyProfileNote = currentNote.tags.contains(Netention.SystemTag.MY_PROFILE.value);
                boolean effectivelyRO = isEffectivelyReadOnly();
                boolean editable = !effectivelyRO && !isMyProfileNote;

                titleF.setEditable(editable);
                tagsF.setEditable(editable);
                contentPane.setEditable(!effectivelyRO); // Content always editable if not system RO
                statusComboBox.setEnabled(editable);
                prioritySpinner.setEnabled(editable);

                Stream.of(titleF, contentPane, tagsF, statusComboBox, prioritySpinner).forEach(c -> c.setEnabled(true)); // Enable all if note loaded
                // Then disable specific ones if conditions met
                if (effectivelyRO) { // If effectively read-only, disable content related ones beyond system tags
                     contentPane.setEditable(false);
                }
                if (isMyProfileNote) { // My profile note has specific fields non-editable
                    titleF.setEditable(false); // Title often derived from pubkey or fixed
                    tagsF.setEditable(false); // Tags are system-managed for profile
                    statusComboBox.setEnabled(false);
                    prioritySpinner.setEnabled(false);
                }
            }

            userModified = false;
            setExternallyUpdated(false);
            if (onDirtyStateChangeCallback != null) onDirtyStateChangeCallback.accept(false);
            setupDocumentListeners();
            updateServiceDependentButtonStates();
        }

        public void updateNonTextParts(Netention.Note note) {
            this.currentNote = note;
            updateEmbeddingStatus();
            if (!userModified) {
                clearDocumentListeners(); // Temporarily clear to prevent firing listeners
                tagsF.setText(String.join(", ", currentNote.tags));
                statusComboBox.setSelectedItem(currentNote.getStatus());
                prioritySpinner.setValue(currentNote.getPriority());
                setupDocumentListeners(); // Re-attach
            }
            updateServiceDependentButtonStates();
        }

        public boolean isUserModified() {
            return userModified;
        }

        @Override
        public boolean isDirty() {
            return isUserModified();
        }

        @Override
        public String getResourceTitle() {
            return currentNote != null ? currentNote.getTitle() : "Untitled";
        }

        @Override
        public String getResourceType() {
            return "Note";
        }

        @Override
        public Netention.Note getAssociatedNote() {
            return currentNote;
        }


        private void updateEmbeddingStatus() {
            embStatusL.setText("Embedding: " + (currentNote != null && currentNote.getEmbeddingV1() != null ? "Generated (" + currentNote.getEmbeddingV1().length + "d)" : "N/A"));
        }

        public void updateServiceDependentButtonStates() {
            appFrame.updateActionStates();
        }

        public Netention.Note getCurrentNote() {
            if (userModified && currentNote != null) updateNoteFromFields();
            return currentNote;
        }


        public void updateNoteFromFields() {
            if (currentNote == null) currentNote = new Netention.Note();

            boolean isMyProfile = currentNote.tags.contains(Netention.SystemTag.MY_PROFILE.value);
            boolean effectivelyRO = isEffectivelyReadOnly();

            if (!isMyProfile) { // Profile fields might be non-editable or handled differently
                if (titleF.isEditable()) currentNote.setTitle(titleF.getText());
                if (tagsF.isEditable()) {
                    currentNote.tags.clear();
                    Stream.of(tagsF.getText().split(","))
                            .map(String::trim).filter(s -> !s.isEmpty())
                            .forEach(currentNote.tags::add);
                }
                if (statusComboBox.isEnabled()) currentNote.setStatus((String) statusComboBox.getSelectedItem());
                if (prioritySpinner.isEnabled()) currentNote.setPriority((Integer) prioritySpinner.getValue());
            }


            if (contentPane.isEditable()) { // Content is generally editable unless system RO
                var doc = contentPane.getDocument();
                if (Netention.ContentType.TEXT_HTML.getValue().equals(contentPane.getContentType())) {
                    var sw = new StringWriter();
                    try {
                        htmlEditorKit.write(sw, doc, 0, doc.getLength());
                        currentNote.setHtmlText(sw.toString());
                    } catch (IOException | BadLocationException e) {
                        logger.warn("Error writing HTML content, falling back to plain text: {}", e.getMessage());
                        currentNote.setText(contentPane.getText()); // Fallback
                    }
                } else {
                    try {
                        currentNote.setText(doc.getText(0, doc.getLength()));
                    } catch (BadLocationException e) {
                        logger.warn("Error getting plain text content: {}", e.getMessage());
                        currentNote.setText("Error reading content: " + e.getMessage()); // Fallback
                    }
                }
            }
        }

        @Override
        public void saveChanges() {
            saveNoteInternal(false);
        }

        public void saveNote(boolean andPublish) {
            saveNoteInternal(andPublish);
        }

        private void saveNoteInternal(boolean andPublish) {
            if (currentNote != null && isEffectivelyReadOnly() && !currentNote.tags.contains(Netention.SystemTag.MY_PROFILE.value) /* Allow saving MyProfile content even if some fields are RO */) {
                 // Check if it's a system note that's generally read-only, but not the profile note which has editable content part.
                if (isSystemReadOnly() && !currentNote.tags.contains(Netention.SystemTag.MY_PROFILE.value)) {
                    JOptionPane.showMessageDialog(this, "Cannot save read-only system items.", "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }
            updateNoteFromFields();

            var savedNote = core.saveNote(this.currentNote);
            if (savedNote != null) {
                this.currentNote = savedNote; // Update currentNote to the saved one (might have new ID, updatedAt)
                userModified = false;
                setExternallyUpdated(false);
                if (onDirtyStateChangeCallback != null) onDirtyStateChangeCallback.accept(false);

                if (andPublish && !currentNote.tags.contains(Netention.SystemTag.MY_PROFILE.value)) {
                    if (core.net.isEnabled()) core.net.publishNote(this.currentNote);
                    else
                        JOptionPane.showMessageDialog(this, "Nostr not enabled. Note saved locally.", "Nostr Disabled", JOptionPane.WARNING_MESSAGE);
                }
                if (onSaveCb != null) onSaveCb.run();
                populateFields(this.currentNote); // Repopulate with the (potentially new) note data
                if (inspectorPanelRef != null) inspectorPanelRef.setContextNote(this.currentNote);
            } else {
                JOptionPane.showMessageDialog(this, "Failed to save note.", "Error", JOptionPane.ERROR_MESSAGE);
            }
        }

        public void setExternallyUpdated(boolean updated) {
            this.externallyUpdated = updated;
            updateServiceDependentButtonStates();
            for (Component c : toolBar.getComponents()) {
                if (c instanceof JButton btn && "Refresh".equals(btn.getToolTipText())) { // Tooltip check
                    btn.setEnabled(updated && currentNote != null);
                }
            }
        }

        private void refreshFromSource() {
            if (currentNote == null || !externallyUpdated) return;
            var latestNoteOpt = core.notes.get(currentNote.id);
            if (latestNoteOpt.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Note no longer exists in store.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            var latestNote = latestNoteOpt.get();
            if (userModified) {
                int choice = JOptionPane.showConfirmDialog(this,
                        "You have unsaved local changes. Discard your changes and refresh from the latest version?",
                        "Confirm Refresh", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (choice == JOptionPane.NO_OPTION) return;
            }
            populateFields(latestNote);
            if (inspectorPanelRef != null) inspectorPanelRef.setContextNote(latestNote);
        }

        private enum LLMAction {
            EMBED("Embed"), SUMMARIZE("Summarize"), ASK("Ask Question"), DECOMPOSE("Decompose Task"), SUGGEST_PLAN("Suggest Plan");
            final String label;

            LLMAction(String label) {
                this.label = label;
            }
        }
    }

    public static class ChatPanel extends JPanel {
        private final Netention.Core core;
        private final Netention.Note note;
        private final String partnerNpub;
        private final JTextPane chatPane;
        private final JTextField messageInput = new JTextField(40);
        private final DateTimeFormatter chatTSFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
        private final DateTimeFormatter chatDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
        private final Consumer<Netention.Core.CoreEvent> coreEventListener;
        private final Consumer<String> statusUpdater;

        private final SimpleAttributeSet userMessageStyle;
        private final SimpleAttributeSet contactMessageStyle;
        private final SimpleAttributeSet timestampStyle;

        public ChatPanel(Netention.Core core, Netention.Note note, String partnerNpub, Consumer<String> statusUpdater) {
            super(new BorderLayout(5, 5));
            setBorder(new EmptyBorder(10, 10, 10, 10));
            this.core = core;
            this.note = note;
            this.partnerNpub = partnerNpub;
            this.statusUpdater = statusUpdater;

            chatPane = new JTextPane();
            chatPane.setEditable(false);
            chatPane.setMargin(new Insets(5, 5, 5, 5));
            chatPane.setContentType("text/html");

            userMessageStyle = new SimpleAttributeSet();
            StyleConstants.setAlignment(userMessageStyle, StyleConstants.ALIGN_RIGHT);
            StyleConstants.setBackground(userMessageStyle, new Color(200, 220, 255));
            StyleConstants.setLeftIndent(userMessageStyle, 0.2f);
            StyleConstants.setRightIndent(userMessageStyle, 0.05f);
            StyleConstants.setLineSpacing(userMessageStyle, 0.1f);

            contactMessageStyle = new SimpleAttributeSet();
            StyleConstants.setAlignment(contactMessageStyle, StyleConstants.ALIGN_LEFT);
            StyleConstants.setBackground(contactMessageStyle, new Color(230, 230, 230));
            StyleConstants.setLeftIndent(contactMessageStyle, 0.05f);
            StyleConstants.setRightIndent(contactMessageStyle, 0.2f);
            StyleConstants.setLineSpacing(contactMessageStyle, 0.1f);

            timestampStyle = new SimpleAttributeSet();
            StyleConstants.setFontSize(timestampStyle, 10);
            StyleConstants.setForeground(timestampStyle, Color.GRAY.darker());
            StyleConstants.setItalic(timestampStyle, true);


            add(new JScrollPane(chatPane), BorderLayout.CENTER);
            var inputP = new JPanel(new BorderLayout(5, 0));
            inputP.add(messageInput, BorderLayout.CENTER);
            inputP.add(UIUtil.button("➡️", "Send", _ -> sendMessage()), BorderLayout.EAST);
            add(inputP, BorderLayout.SOUTH);
            messageInput.addActionListener(_ -> sendMessage());
            loadMessages();
            coreEventListener = this::handleChatPanelCoreEvent;
            core.addCoreEventListener(coreEventListener);
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentHidden(ComponentEvent e) {
                    core.removeCoreEventListener(coreEventListener);
                }

                @Override
                public void componentShown(ComponentEvent e) {
                    core.removeCoreEventListener(coreEventListener);
                    core.addCoreEventListener(coreEventListener);
                    loadMessages();
                }
            });
        }

        private void handleChatPanelCoreEvent(Netention.Core.CoreEvent event) {
            if (event.type() == Netention.Core.CoreEventType.CHAT_MESSAGE_ADDED &&
                    event.data() instanceof Map<?, ?> data &&
                    note.id.equals(data.get("chatNoteId"))) {
                SwingUtilities.invokeLater(this::loadMessages);
            }
        }

        @SuppressWarnings("unchecked")
        public void loadMessages() {
            chatPane.setText("");
            StyledDocument doc = chatPane.getStyledDocument();
            core.notes.get(this.note.id).ifPresent(freshNote -> {
                var messages = (List<Map<String, String>>) freshNote.content.getOrDefault(Netention.ContentKey.MESSAGES.getKey(), Collections.emptyList());
                messages.stream()
                        .sorted(Comparator.comparing(m -> Instant.parse(m.get("timestamp"))))
                        .forEach(m -> formatAndAppendMsg(doc, m));
            });
            scrollToBottom();
        }

        private void formatAndAppendMsg(StyledDocument doc, Map<String, String> m) {
            var senderNpubHex = m.get("sender");
            var text = m.get("text");
            var timestampStr = m.get("timestamp");
            if (senderNpubHex == null || text == null || timestampStr == null) return;

            try {
                var timestamp = Instant.parse(timestampStr);
                String displayName;
                boolean isUserMessage = senderNpubHex.equals(core.net.getPublicKeyXOnlyHex());
                if (isUserMessage) {
                    displayName = "Me";
                } else {
                    displayName = core.notes.getAll(n -> n.tags.contains(Netention.SystemTag.CONTACT.value) &&
                                    senderNpubHex.equals(n.meta.get(Netention.Metadata.NOSTR_PUB_KEY_HEX.key)))
                            .stream().findFirst()
                            .map(Netention.Note::getTitle)
                            .orElseGet(() -> senderNpubHex.substring(0, Math.min(senderNpubHex.length(), 8)) + "...");
                }

                SimpleAttributeSet currentStyle = isUserMessage ? userMessageStyle : contactMessageStyle;
                SimpleAttributeSet paragraphStyle = new SimpleAttributeSet(currentStyle);
                StyleConstants.setSpaceBelow(paragraphStyle, 5);

                String formattedTimestamp = chatTSFormatter.format(timestamp);

                doc.insertString(doc.getLength(), text + "\n", currentStyle);
                doc.insertString(doc.getLength(), formattedTimestamp + "\n\n", timestampStyle);

                doc.setParagraphAttributes(doc.getLength() - (formattedTimestamp.length() + text.length() + 3), (formattedTimestamp.length() + text.length() + 3), paragraphStyle, false);

            } catch (Exception e) {
                logger.warn("Failed to parse chat message timestamp or format message: {}", m, e);
            }
        }


        private void sendMessage() {
            var textToSend = messageInput.getText().trim();
            if (textToSend.isEmpty()) return;
            if (!core.net.isEnabled() || core.cfg.net.privateKeyBech32 == null || core.cfg.net.privateKeyBech32.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Nostr not enabled/configured.", "Nostr Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            core.net.sendDirectMessage(partnerNpub, textToSend);
            core.notes.get(this.note.id).ifPresent(currentChatNote -> {
                var messageEntry = Map.of("sender", core.net.getPublicKeyXOnlyHex(), "timestamp", Instant.now().toString(), "text", textToSend);
                @SuppressWarnings("unchecked")
                var messagesList = (List<Map<String, String>>) currentChatNote.content.computeIfAbsent(Netention.ContentKey.MESSAGES.getKey(), k -> new ArrayList<Map<String, String>>());
                messagesList.add(messageEntry);
                core.saveNote(currentChatNote);
            });
            messageInput.setText("");
            statusUpdater.accept("Message sent to " + partnerNpub.substring(0, 10) + "...");
        }

        private void scrollToBottom() {
            chatPane.setCaretPosition(chatPane.getDocument().getLength());
        }

        public Netention.Note getChatNote() {
            return note;
        }
    }

    public static class InspectorPanel extends JPanel {
        private final Netention.Core core;
        private final JTabbedPane tabbedPane;
        private final JPanel planPanel;
        private final JTextArea llmAnalysisArea;
        private final JLabel noteInfoLabel;
        private final JButton copyPubKeyButton;
        private final DefaultListModel<Netention.Link> linksListModel = new DefaultListModel<>();
        private final JList<Netention.Link> linksJList;
        private final DefaultListModel<Netention.Note> relatedNotesListModel = new DefaultListModel<>();
        private final JList<Netention.Note> relatedNotesJList;
        private final PlanStepsTableModel planStepsTableModel;
        private final JLabel overallPlanStatusLabel;
        private final JButton executePlanButton;
        private final DefaultTreeModel planDepsTreeModel;
        private final JTree planDepsTree;
        private final DefaultListModel<ActionableItem> actionableItemsListModel = new DefaultListModel<>();
        private final JList<ActionableItem> actionableItemsJList;
        private final App uiRef;
        Netention.Note contextNote;
        private final DateTimeFormatter planTimestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

        public InspectorPanel(Netention.Core core, App uiRef, Consumer<Netention.Note> noteDisplayCallback, Function<String, List<ActionableItem>> actionableItemsProvider) {
            super(new BorderLayout(5, 5));
            this.core = core;
            this.uiRef = uiRef;
            setPreferredSize(new Dimension(350, 0));
            tabbedPane = new JTabbedPane();

            var infoPanel = new JPanel(new BorderLayout());
            noteInfoLabel = new JLabel("No note selected.");
            noteInfoLabel.setBorder(new EmptyBorder(5, 5, 5, 5));
            copyPubKeyButton = UIUtil.button("📋", "Copy PubKey", _ -> {
                if (contextNote != null && contextNote.meta.get(Netention.Metadata.NOSTR_PUB_KEY.key) instanceof String npub) {
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(npub), null);
                    uiRef.statusPanel.updateStatus("📋 Copied PubKey: " + npub.substring(0, Math.min(12, npub.length())) + "...");
                }
            });
            copyPubKeyButton.setVisible(false);
            var infoTopPanel = new JPanel(new BorderLayout());
            infoTopPanel.add(noteInfoLabel, BorderLayout.CENTER);
            infoTopPanel.add(copyPubKeyButton, BorderLayout.EAST);
            infoPanel.add(infoTopPanel, BorderLayout.NORTH);
            llmAnalysisArea = new JTextArea(5, 20);
            llmAnalysisArea.setEditable(false);
            llmAnalysisArea.setLineWrap(true);
            llmAnalysisArea.setWrapStyleWord(true);
            llmAnalysisArea.setFont(llmAnalysisArea.getFont().deriveFont(Font.ITALIC));
            Color textAreaBg = UIManager.getColor("TextArea.background");
            llmAnalysisArea.setBackground(textAreaBg != null ? textAreaBg.darker() : UIManager.getColor("controlShadow"));
            infoPanel.add(new JScrollPane(llmAnalysisArea), BorderLayout.CENTER);
            tabbedPane.addTab(Tab.INFO.title, infoPanel);

            var linksPanel = new JPanel(new BorderLayout());
            linksJList = new JList<>(linksListModel);
            linksJList.setCellRenderer(new LinkListCellRenderer(core));
            linksJList.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent evt) {
                    if (evt.getClickCount() == 2)
                        ofNullable(linksJList.getSelectedValue()).flatMap(l -> core.notes.get(l.targetNoteId)).ifPresent(noteDisplayCallback);
                }
            });
            linksPanel.add(new JScrollPane(linksJList), BorderLayout.CENTER);
            var linkButtonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            linkButtonsPanel.add(UIUtil.button("➕", "Add Link", _ -> addLink()));
            linkButtonsPanel.add(UIUtil.button("➖", "Remove Link", _ -> removeLink()));
            linksPanel.add(linkButtonsPanel, BorderLayout.SOUTH);
            tabbedPane.addTab(Tab.LINKS.title, linksPanel);

            var relatedNotesPanel = new JPanel(new BorderLayout());
            relatedNotesJList = new JList<>(relatedNotesListModel);
            relatedNotesJList.setCellRenderer(new NoteTitleListCellRenderer());
            relatedNotesJList.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent evt) {
                    if (evt.getClickCount() == 2)
                        ofNullable(relatedNotesJList.getSelectedValue()).ifPresent(noteDisplayCallback);
                }
            });
            relatedNotesPanel.add(new JScrollPane(relatedNotesJList), BorderLayout.CENTER);
            tabbedPane.addTab(Tab.RELATED.title, relatedNotesPanel);

            planPanel = new JPanel(new BorderLayout());
            planStepsTableModel = new PlanStepsTableModel();
            var planStepsTable = new JTable(planStepsTableModel);

            var tooltipRenderer = new TooltipCellRenderer();
            int paramColIdx = Arrays.asList(planStepsTableModel.columnNames).indexOf("Parameters");
            int resultColIdx = Arrays.asList(planStepsTableModel.columnNames).indexOf("Result");
            int logsColIdx = Arrays.asList(planStepsTableModel.columnNames).indexOf("Logs/Messages");

            if (paramColIdx != -1) planStepsTable.getColumnModel().getColumn(paramColIdx).setCellRenderer(tooltipRenderer);
            if (resultColIdx != -1) planStepsTable.getColumnModel().getColumn(resultColIdx).setCellRenderer(tooltipRenderer);
            if (logsColIdx != -1) planStepsTable.getColumnModel().getColumn(logsColIdx).setCellRenderer(tooltipRenderer);

            planStepsTable.getColumnModel().getColumn(0).setPreferredWidth(150);
            planStepsTable.getColumnModel().getColumn(1).setPreferredWidth(80);
            planStepsTable.getColumnModel().getColumn(2).setPreferredWidth(100);
            if (paramColIdx != -1) planStepsTable.getColumnModel().getColumn(paramColIdx).setPreferredWidth(150);
            if (resultColIdx != -1) planStepsTable.getColumnModel().getColumn(resultColIdx).setPreferredWidth(150);
            if (logsColIdx != -1) planStepsTable.getColumnModel().getColumn(logsColIdx).setPreferredWidth(200);
            int lastUpdatedColIdx = Arrays.asList(planStepsTableModel.columnNames).indexOf("Last Updated");
            if (lastUpdatedColIdx != -1) planStepsTable.getColumnModel().getColumn(lastUpdatedColIdx).setPreferredWidth(80);
            planStepsTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

            planPanel.add(new JScrollPane(planStepsTable), BorderLayout.CENTER);

            var planControlsPanel = new JPanel(new BorderLayout(5, 5));
            overallPlanStatusLabel = new JLabel("Plan Status: Idle");
            overallPlanStatusLabel.setBorder(new EmptyBorder(2, 5, 2, 5));
            planControlsPanel.add(overallPlanStatusLabel, BorderLayout.CENTER);

            executePlanButton = UIUtil.button("▶️", "Execute Plan", _ -> {
                if (contextNote != null) {
                    if (!contextNote.tags.contains(Netention.SystemTag.GOAL_WITH_PLAN.value)) {
                        contextNote.tags.add(Netention.SystemTag.GOAL_WITH_PLAN.value);
                        core.saveNote(contextNote);
                    }
                    core.planner.execute(contextNote);
                }
            });
            planControlsPanel.add(executePlanButton, BorderLayout.EAST);
            planPanel.add(planControlsPanel, BorderLayout.SOUTH);

            tabbedPane.addTab(Tab.PLAN.title, planPanel);

            var planDependenciesPanel = new JPanel(new BorderLayout());
            planDepsTreeModel = new DefaultTreeModel(new DefaultMutableTreeNode("Plan Dependencies"));
            planDepsTree = new JTree(planDepsTreeModel);
            planDepsTree.setRootVisible(false);
            planDepsTree.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        var treeNode = (DefaultMutableTreeNode) planDepsTree.getLastSelectedPathComponent();
                        if (treeNode != null && treeNode.getUserObject() instanceof Map<?, ?> nodeData && nodeData.get(Netention.NoteProperty.ID.getKey()) instanceof String nodeId) {
                            core.notes.get(nodeId).ifPresent(noteDisplayCallback);
                        }
                    }
                }
            });
            planDependenciesPanel.add(new JScrollPane(planDepsTree), BorderLayout.CENTER);
            tabbedPane.addTab(Tab.PLAN_DEPS.title, planDependenciesPanel);

            var actionableItemsPanel = new JPanel(new BorderLayout());
            actionableItemsJList = new JList<>(actionableItemsListModel);
            actionableItemsJList.setCellRenderer(new ActionableItemCellRenderer());
            actionableItemsJList.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2)
                        ofNullable(actionableItemsJList.getSelectedValue()).map(ActionableItem::action).ifPresent(Runnable::run);
                }
            });
            actionableItemsPanel.add(new JScrollPane(actionableItemsJList), BorderLayout.CENTER);
            tabbedPane.addTab(Tab.INBOX.title, actionableItemsPanel);

            add(tabbedPane, BorderLayout.CENTER);
            core.addCoreEventListener(event -> SwingUtilities.invokeLater(() -> handleCoreEvent(event, actionableItemsProvider)));
        }

        private void handleCoreEvent(Netention.Core.CoreEvent event, Function<String, List<ActionableItem>> actionableItemsProvider) {
            switch (event.type()) {
                case PLAN_UPDATED:
                    if (event.data() instanceof Netention.Planner.PlanExecution exec && contextNote != null && contextNote.id.equals(exec.planNoteId)) {
                        updateOverallPlanStatusLabel(exec);
                        loadPlanDependencies();
                    }
                    break;
                case CONFIG_CHANGED:
                    updateServiceDependentButtonStates();
                    break;
                case ACTIONABLE_ITEM_ADDED, ACTIONABLE_ITEM_REMOVED:
                    loadActionableItems(actionableItemsProvider.apply(contextNote != null ? contextNote.id : null));
                    break;
                default:
                    break;
            }
        }

        public void switchToPlanTab() {
            if (contextNote != null) {
                tabbedPane.setSelectedComponent(planPanel);
                core.planner.getPlanExecution(contextNote.id).ifPresentOrElse(
                    this::updateOverallPlanStatusLabel, // Ensure button state is updated too
                    () -> updateOverallPlanStatusLabel(null)
                );
            }
        }

        public void setContextNote(Netention.Note note) {
            this.contextNote = note;
            if (note != null) {
                var title = note.getTitle();
                if (title.length() > 35) title = title.substring(0, 32) + "...";
                var tags = String.join(", ", note.tags);
                if (tags.length() > 35) tags = tags.substring(0, 32) + "...";

                String pubKeyInfo = "";
                boolean isNostrEntity = note.tags.contains(Netention.SystemTag.NOSTR_CONTACT.value) || note.tags.contains(Netention.SystemTag.NOSTR_FEED.value) || note.tags.contains(Netention.SystemTag.CHAT.value);
                if (isNostrEntity && note.meta.get(Netention.Metadata.NOSTR_PUB_KEY.key) instanceof String npub) {
                    pubKeyInfo = "<br>PubKey: " + npub.substring(0, Math.min(npub.length(), 12)) + "...";
                    copyPubKeyButton.setVisible(true);
                } else {
                    copyPubKeyButton.setVisible(false);
                }
                noteInfoLabel.setText(String.format("<html><b>%s</b><br>Tags: %s<br>Updated: %s%s</html>",
                        title, tags, DateTimeFormatter.ISO_INSTANT.format(note.updatedAt.atZone(ZoneId.systemDefault())).substring(0, 19), pubKeyInfo));

                displayLLMAnalysis();
                loadLinks();
                loadRelatedNotes();
                loadPlanDependencies();
                core.planner.getPlanExecution(note.id).ifPresentOrElse(
                        this::updateOverallPlanStatusLabel,
                        () -> updateOverallPlanStatusLabel(null)
                );
                loadActionableItems(uiRef.getActionableItemsForNote(note.id));
            } else {
                noteInfoLabel.setText("No note selected.");
                llmAnalysisArea.setText("");
                copyPubKeyButton.setVisible(false);
                linksListModel.clear();
                relatedNotesListModel.clear();
                planDepsTreeModel.setRoot(new DefaultMutableTreeNode("Plan Dependencies"));
                updateOverallPlanStatusLabel(null);
                actionableItemsListModel.clear();
            }
        }

        private void updateOverallPlanStatusLabel(@Nullable Netention.Planner.PlanExecution exec) {
            planStepsTableModel.setPlanExecution(exec);

            if (exec != null) {
                String statusText = "Status: " + exec.currentStatus.name();
                if (exec.lastPlanUpdatedAt != null) {
                    statusText += " (Updated: " + planTimestampFormatter.format(exec.lastPlanUpdatedAt) + ")";
                }

                String tooltip = null;
                if (exec.errorMessage != null && !exec.errorMessage.isEmpty()) {
                    statusText += " | Error: " + exec.errorMessage.substring(0, Math.min(exec.errorMessage.length(), 50)) + (exec.errorMessage.length() > 50 ? "..." : "");
                    overallPlanStatusLabel.setForeground(Color.RED.darker());
                    tooltip = "<html>Error: " + exec.errorMessage.replace("\n", "<br>") + "</html>";
                } else if (exec.currentStatus == Netention.PlanState.COMPLETED) {
                    overallPlanStatusLabel.setForeground(new Color(0, 128, 0));
                } else if (exec.currentStatus == Netention.PlanState.RUNNING) {
                    overallPlanStatusLabel.setForeground(Color.BLUE.darker());
                } else if (Set.of(Netention.PlanState.STUCK, Netention.PlanState.FAILED_PARSING, Netention.PlanState.FAILED_NO_STEPS, Netention.PlanState.FAILED).contains(exec.currentStatus)) {
                     overallPlanStatusLabel.setForeground(Color.ORANGE.darker());
                } else {
                    overallPlanStatusLabel.setForeground(UIManager.getColor("Label.foreground"));
                }
                overallPlanStatusLabel.setText(statusText);
                overallPlanStatusLabel.setToolTipText(tooltip);

                switch (exec.currentStatus) {
                    case RUNNING, PARSING:
                        executePlanButton.setText("⏳ Running...");
                        executePlanButton.setEnabled(false);
                        break;
                    case COMPLETED, FAILED, FAILED_PARSING, FAILED_NO_STEPS, STUCK:
                        executePlanButton.setText("🔁 Re-run Plan");
                        executePlanButton.setEnabled(true);
                        break;
                    case PENDING:
                        executePlanButton.setText("▶️ Execute Plan");
                        executePlanButton.setEnabled(true);
                        break;
                    default:
                        executePlanButton.setText("▶️ Execute Plan");
                        executePlanButton.setEnabled(true);
                }
            } else {
                overallPlanStatusLabel.setText("Plan Status: Idle / No Plan");
                overallPlanStatusLabel.setForeground(UIManager.getColor("Label.foreground"));
                overallPlanStatusLabel.setToolTipText(null);
                executePlanButton.setText("▶️ Execute Plan");
                boolean isGoal = contextNote != null && contextNote.tags.contains(Netention.SystemTag.GOAL_WITH_PLAN.value);
                executePlanButton.setEnabled(contextNote != null && isGoal);
                 if (contextNote != null && !isGoal) {
                    executePlanButton.setText("Declare as Goal & Plan");
                    executePlanButton.setToolTipText("Convert this note into a goal to enable planning features.");
                    executePlanButton.setEnabled(true);
                 } else if (contextNote != null && isGoal) { // Already a goal
                    executePlanButton.setToolTipText("Execute or re-run the plan for this goal.");
                 } else { // No context note
                    executePlanButton.setToolTipText("Select a goal note to manage its plan.");
                 }
            }
        }

        private void loadLinks() {
            linksListModel.clear();
            if (contextNote != null && contextNote.links != null) contextNote.links.forEach(linksListModel::addElement);
        }

        private void addLink() {
            if (contextNote == null) return;
            var allNotes = core.notes.getAllNotes().stream().filter(n -> !n.id.equals(contextNote.id)).toList();
            if (allNotes.isEmpty()) {
                JOptionPane.showMessageDialog(this, "No other notes to link to.", "Add Link", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            var noteSelector = new JComboBox<>(allNotes.toArray(new Netention.Note[0]));
            noteSelector.setRenderer(new NoteTitleListCellRenderer());
            var relationTypes = new String[]{"relates_to", "supports", "elaborates", "depends_on", "plan_subgoal_of", "plan_depends_on"};
            var relationTypeSelector = new JComboBox<>(relationTypes);
            if (contextNote.tags.contains(Netention.SystemTag.GOAL_WITH_PLAN.value))
                relationTypeSelector.setSelectedItem("plan_subgoal_of");

            var panel = new JPanel(new GridLayout(0, 1, 5, 5));
            panel.add(new JLabel("Select target note:"));
            panel.add(noteSelector);
            panel.add(new JLabel("Relation type:"));
            panel.add(relationTypeSelector);

            if (JOptionPane.showConfirmDialog(this, panel, "🔗 Add Link", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                var targetNote = (Netention.Note) noteSelector.getSelectedItem();
                var relationType = (String) relationTypeSelector.getSelectedItem();
                if (targetNote != null && relationType != null && !relationType.isEmpty()) {
                    contextNote.links.add(new Netention.Link(targetNote.id, relationType));
                    core.saveNote(contextNote);
                    loadLinks();
                }
            }
        }

        private void removeLink() {
            if (contextNote == null || linksJList.getSelectedValue() == null) return;
            var selectedLink = linksJList.getSelectedValue();
            var targetTitle = core.notes.get(selectedLink.targetNoteId).map(Netention.Note::getTitle).orElse("Unknown");
            if (JOptionPane.showConfirmDialog(this, "Remove link to '" + targetTitle + "' (" + selectedLink.relationType + ")?", "Confirm Remove Link", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                contextNote.links.remove(selectedLink);
                core.saveNote(contextNote);
                loadLinks();
            }
        }

        public void loadRelatedNotes() {
            relatedNotesListModel.clear();
            if (contextNote != null && contextNote.getEmbeddingV1() != null && core.lm.isReady()) {
                CompletableFuture.supplyAsync(() -> core.findRelatedNotes(contextNote, 5, 0.65))
                        .thenAcceptAsync(related -> related.forEach(relatedNotesListModel::addElement), SwingUtilities::invokeLater)
                        .exceptionally(ex -> {
                            logger.warn("Failed to load related notes", ex);
                            return null;
                        });
            }
        }

        @SuppressWarnings("unchecked")
        private void loadPlanDependencies() {
            var root = new DefaultMutableTreeNode("Plan Dependencies");
            if (contextNote != null && contextNote.tags.contains(Netention.SystemTag.GOAL_WITH_PLAN.value)) {
                try {
                    var graphContext = (Map<String, Object>) core.executeTool(Netention.Core.Tool.GET_PLAN_GRAPH_CONTEXT, Map.of(Netention.ToolParam.NOTE_ID.getKey(), contextNote.id));
                    if (graphContext != null && !graphContext.isEmpty()) {
                        var mainNodeData = new HashMap<>(graphContext);
                        mainNodeData.put(Netention.NoteProperty.TITLE.getKey(), "Self: " + graphContext.get(Netention.NoteProperty.TITLE.getKey()));
                        var mainNode = new DefaultMutableTreeNode(mainNodeData);
                        root.add(mainNode);

                        ((List<Map<String, Object>>) graphContext.getOrDefault("children", Collections.emptyList()))
                                .forEach(childData -> mainNode.add(new DefaultMutableTreeNode(childData)));

                        ((List<Map<String, Object>>) graphContext.getOrDefault("parents", Collections.emptyList()))
                                .forEach(parentData -> {
                                    var parentNodeData = new HashMap<>(parentData);
                                    parentNodeData.put(Netention.NoteProperty.TITLE.getKey(), "Parent: " + parentData.get(Netention.NoteProperty.TITLE.getKey()));
                                    root.insert(new DefaultMutableTreeNode(parentNodeData), 0);
                                });
                    }
                } catch (Exception e) {
                    logger.error("Failed to load plan dependencies graph for note {}: {}", contextNote.id, e.getMessage(), e);
                }
            }
            planDepsTreeModel.setRoot(root);
            planDepsTreeModel.reload();
            IntStream.range(0, planDepsTree.getRowCount()).forEach(planDepsTree::expandRow);
        }

        private void loadActionableItems(List<ActionableItem> items) {
            actionableItemsListModel.clear();
            if (items != null) items.forEach(actionableItemsListModel::addElement);

            int inboxTabIndex = tabbedPane.indexOfTab(Tab.INBOX.title);
            if (inboxTabIndex != -1) {
                boolean hasItems = items != null && !items.isEmpty();
                tabbedPane.setForegroundAt(inboxTabIndex, hasItems ? Color.ORANGE.darker() : UIManager.getColor("Label.foreground"));
                tabbedPane.setTitleAt(inboxTabIndex, Tab.INBOX.title + (hasItems ? " (" + items.size() + ")" : ""));
            }
        }

        public void updateServiceDependentButtonStates() { /* e.g. if LLM status changes, refresh related notes button enable state */ }

        public void displayLLMAnalysis() {
            if (contextNote == null) {
                llmAnalysisArea.setText("");
                return;
            }
            var sb = new StringBuilder();
            ofNullable(contextNote.meta.get(Netention.Metadata.LLM_SUMMARY.key)).ifPresent(s -> sb.append("Summary:\n").append(s).append("\n\n"));
            ofNullable(contextNote.meta.get(Netention.Metadata.LLM_DECOMPOSITION.key)).ifPresent(d -> {
                if (d instanceof List<?> list) {
                    sb.append("Task Decomposition:\n");
                    list.forEach(i -> sb.append("- ").append(i).append("\n"));
                    sb.append("\n");
                }
            });
            llmAnalysisArea.setText(sb.toString().trim());
            llmAnalysisArea.setCaretPosition(0);
        }

        private enum Tab {
            INFO("ℹ️ Info"), LINKS("🔗 Links"), RELATED("🤝 Related"), PLAN("🗺️ Plan"), PLAN_DEPS("🌳 Plan Deps"), INBOX("📥 Inbox");
            final String title;

            Tab(String title) {
                this.title = title;
            }
        }

        static class LinkListCellRenderer extends DefaultListCellRenderer {
            private final Netention.Core core;

            public LinkListCellRenderer(Netention.Core core) {
                this.core = core;
            }

            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Netention.Link link) {
                    var targetTitle = core.notes.get(link.targetNoteId).map(Netention.Note::getTitle).orElse("Unknown Note");
                    if (targetTitle.length() > 25) targetTitle = targetTitle.substring(0, 22) + "...";
                    setText(String.format("%s → %s", link.relationType, targetTitle));
                }
                return this;
            }
        }

        static class NoteTitleListCellRenderer extends DefaultListCellRenderer {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Netention.Note note) setText(note.getTitle());
                else if (value != null) setText(value.toString());
                return this;
            }
        }

        public static class ActionableItemCellRenderer extends DefaultListCellRenderer {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof ActionableItem item) {
                    String icon = "";
                    Color textColor = UIManager.getColor("List.foreground");
                    Font font = getFont().deriveFont(Font.PLAIN);

                    if (item.type().equals("FRIEND_REQUEST")) {
                        icon = "🫂 ";
                        textColor = Color.ORANGE.darker();
                        font = getFont().deriveFont(Font.BOLD);
                    } else if (item.type().equals("SYSTEM_NOTIFICATION")) {
                        icon = "🔔 ";
                        textColor = Color.GRAY.darker();
                    }

                    setText("<html>" + icon + "<b>" + item.type().replace("_", " ") + ":</b> " + item.description() + "</html>");
                    setForeground(textColor);
                    setFont(font);
                }
                return this;
            }
        }

        static class PlanStepsTableModel extends AbstractTableModel {
            final String[] columnNames = {"Description", "Status", "Tool", "Parameters", "Result", "Logs/Messages", "Last Updated"}; // Made final
            private List<Netention.Planner.PlanStep> steps = new ArrayList<>();
            private final DateTimeFormatter timestampFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());


            public void setPlanExecution(Netention.Planner.PlanExecution exec) {
                this.steps = exec != null ? new ArrayList<>(exec.steps) : new ArrayList<>();
                fireTableDataChanged();
            }

            @Override
            public int getRowCount() {
                return steps.size();
            }

            @Override
            public int getColumnCount() {
                return columnNames.length;
            }

            @Override
            public String getColumnName(int column) {
                return columnNames[column];
            }

            @Override
            public Object getValueAt(int rowIndex, int columnIndex) {
                var step = steps.get(rowIndex);
                return switch (columnIndex) {
                    case 0 -> step.description;
                    case 1 -> step.status;
                    case 2 -> step.toolName;
                    case 3 -> formatParams(step.toolParams);
                    case 4 -> formatResult(step.result);
                    case 5 -> formatLogs(step.logs);
                    case 6 -> step.lastUpdatedAt != null ? timestampFormatter.format(step.lastUpdatedAt) : (step.endTime != null ? timestampFormatter.format(step.endTime) : (step.startTime != null ? timestampFormatter.format(step.startTime) : ""));
                    default -> null;
                };
            }

            private String formatParams(Map<String, Object> params) {
                if (params == null || params.isEmpty()) return "";
                return params.entrySet().stream()
                        .map(e -> e.getKey() + "=" + String.valueOf(e.getValue()).substring(0, Math.min(String.valueOf(e.getValue()).length(), 30)) + (String.valueOf(e.getValue()).length() > 30 ? "..." : ""))
                        .collect(Collectors.joining(", "));
            }

            private String formatResult(Object result) {
                if (result == null) return "";
                String s = String.valueOf(result);
                return s.substring(0, Math.min(s.length(), 50)) + (s.length() > 50 ? "..." : "");
            }

            private String formatLogs(List<String> logs) {
                if (logs == null || logs.isEmpty()) return "";
                String lastLog = logs.get(logs.size() - 1);
                return lastLog.substring(0, Math.min(lastLog.length(), 70)) + (lastLog.length() > 70 ? "..." : "");
            }
        }
    }

    public static class TooltipCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (c instanceof JComponent jc) {
                String tooltipText = null;
                if (table.getModel() instanceof InspectorPanel.PlanStepsTableModel model) {
                    // Check if row is valid for the model
                    int modelRow = table.convertRowIndexToModel(row);
                    if (modelRow < model.steps.size()) {
                        var step = model.steps.get(modelRow);
                        // Use column identifiers from the model to make it robust to column reordering
                        String colName = model.getColumnName(column);

                        if ("Parameters".equals(colName)) {
                             tooltipText = step.toolParams != null && !step.toolParams.isEmpty() ? step.toolParams.entrySet().stream()
                                .map(e -> e.getKey() + ": " + e.getValue())
                                .collect(Collectors.joining("\n")) : "No parameters";
                        } else if ("Result".equals(colName)) {
                            tooltipText = step.result != null ? String.valueOf(step.result) : "No result";
                        } else if ("Logs/Messages".equals(colName)) {
                             tooltipText = step.logs != null && !step.logs.isEmpty() ?
                                String.join("\n", step.logs) : "No logs";
                        }
                    }
                }
                jc.setToolTipText(tooltipText != null && !tooltipText.isBlank() ? "<html><pre>" + tooltipText.replace("\n", "<br>") + "</pre></html>" : null);
            }
            return c;
        }
    }

    public static class ActionableItemsPanel extends JPanel {
        private final DefaultListModel<ActionableItem> listModel = new DefaultListModel<>();
        private final JList<ActionableItem> list = new JList<>(listModel);

        public ActionableItemsPanel(Netention.Core core, Consumer<ActionableItem> onExecuteAction) {
            super(new BorderLayout(5, 5));
            setBorder(new EmptyBorder(5, 5, 5, 5)); // Corrected from EmptyBordern

            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setCellRenderer(new InspectorPanel.ActionableItemCellRenderer());
            list.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        ofNullable(list.getSelectedValue()).ifPresent(item -> {
                            onExecuteAction.accept(item);
                            var ii = item.id();
                            refreshList(Stream.of(listModel.toArray())
                                .filter(i -> i instanceof ActionableItem a && !a.id().equals(ii))
                                .map(z -> (ActionableItem)z).toList());
                        });
                    }
                }
            });

            add(new JScrollPane(list), BorderLayout.CENTER);
            add(new JLabel("Double-click an item to act on it.", SwingConstants.CENTER), BorderLayout.SOUTH);
        }

        public void refreshList(List<ActionableItem> items) {
            SwingUtilities.invokeLater(() -> {
                listModel.clear();
                items.forEach(listModel::addElement);
            });
        }
    }

    public static class ConfigNoteEditorPanel extends JPanel implements DirtyableSavableComponent {
        private final Netention.Core core;
        private final Netention.Note configNote;
        private final Runnable onSaveCb;
        private final Map<Field, JComponent> fieldToComponentMap = new HashMap<>();
        private final Consumer<Boolean> onDirtyStateChangeCallback;
        boolean userModifiedConfig = false;

        public ConfigNoteEditorPanel(Netention.Core core, Netention.Note configNote, Runnable onSaveCb, Consumer<Boolean> onDirtyStateChangeCallback) {
            super(new BorderLayout(10, 10));
            setBorder(new EmptyBorder(10, 10, 10, 10));
            this.core = core;
            this.configNote = configNote;
            this.onSaveCb = onSaveCb;
            this.onDirtyStateChangeCallback = onDirtyStateChangeCallback;

            var targetConfigObject = getTargetConfigObject(configNote.id);
            boolean isKnownEditableType = targetConfigObject != null || "config.nostr_relays".equals(configNote.id);

            if ("config.nostr_relays".equals(configNote.id)) {
                add(buildNostrRelaysPanel(), BorderLayout.CENTER);
            } else if (targetConfigObject != null) {
                var formPanel = new JPanel(new GridBagLayout());
                var gbc = new GridBagConstraints();
                gbc.insets = new Insets(4, 4, 4, 4);
                gbc.fill = GridBagConstraints.HORIZONTAL;
                gbc.anchor = GridBagConstraints.WEST;
                var factory = new ConfigFieldEditorFactory(core, this::markDirty);
                var fields = Stream.of(targetConfigObject.getClass().getDeclaredFields())
                        .filter(f -> f.isAnnotationPresent(Netention.Field.class)).toList();
                for (int i = 0; i < fields.size(); i++) {
                    var field = fields.get(i);
                    var editorComp = factory.createEditor(field, targetConfigObject);
                    fieldToComponentMap.put(field, editorComp);
                    UIUtil.addLabelAndComponent(formPanel, gbc, i, field.getAnnotation(Netention.Field.class).label() + ":", editorComp);
                }
                gbc.gridy++;
                gbc.weighty = 1.0;
                formPanel.add(new JPanel(), gbc);
                add(new JScrollPane(formPanel), BorderLayout.CENTER);
            } else {
                add(buildGenericConfigViewer(configNote), BorderLayout.CENTER);
            }

            var bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            if (isKnownEditableType) bottomPanel.add(UIUtil.button("💾", "Save Settings", _ -> saveChanges()));
            add(bottomPanel, BorderLayout.SOUTH);

            if (targetConfigObject != null && !"config.nostr_relays".equals(configNote.id)) {
                refreshFieldsFromConfig();
            } else {
                userModifiedConfig = false;
                if (onDirtyStateChangeCallback != null) onDirtyStateChangeCallback.accept(false);
            }
        }

        private JComponent buildGenericConfigViewer(Netention.Note note) {
            var panel = new JPanel(new BorderLayout(5, 5));
            panel.setBorder(new EmptyBorder(5, 5, 5, 5));
            panel.add(new JLabel("Generic Configuration Viewer for: " + note.getTitle(), SwingConstants.CENTER), BorderLayout.NORTH);
            var contentArea = new JTextArea(10, 40);
            contentArea.setEditable(false);
            contentArea.setLineWrap(true);
            contentArea.setWrapStyleWord(true);
            try {
                contentArea.setText(core.json.writerWithDefaultPrettyPrinter().writeValueAsString(note.content));
            } catch (JsonProcessingException e) {
                contentArea.setText("Error displaying content as JSON: " + e.getMessage() + "\n\nRaw content:\n" + note.content.toString());
            }
            contentArea.setCaretPosition(0);
            panel.add(new JScrollPane(contentArea), BorderLayout.CENTER);
            return panel;
        }


        private void markDirty() {
            if (!userModifiedConfig) {
                userModifiedConfig = true;
                if (onDirtyStateChangeCallback != null) onDirtyStateChangeCallback.accept(true);
            }
        }

        private Object getTargetConfigObject(String noteId) {
            return switch (noteId) {
                case "config.ui" -> core.cfg.ui;
                case "config.nostr_identity" -> core.cfg.net;
                case "config.llm" -> core.cfg.lm;
                default -> null;
            };
        }

        public void refreshFieldsFromConfig() {
            var targetConfigObject = getTargetConfigObject(configNote.id);
            if (targetConfigObject == null || "config.nostr_relays".equals(configNote.id)) return;

            var factory = new ConfigFieldEditorFactory(core, this::markDirty);
            fieldToComponentMap.forEach((field, component) -> factory.refreshFieldComponent(field, component, targetConfigObject));

            userModifiedConfig = false;
            if (onDirtyStateChangeCallback != null) onDirtyStateChangeCallback.accept(false);
        }

        @Override
        public void saveChanges() {
            var targetConfigObject = getTargetConfigObject(configNote.id);
            if (!"config.nostr_relays".equals(configNote.id) && targetConfigObject != null) {
                var factory = new ConfigFieldEditorFactory(core, this::markDirty);
                fieldToComponentMap.forEach((field, component) -> factory.saveComponentValueToField(field, component, targetConfigObject));
            }
            core.cfg.saveAllConfigs();
            userModifiedConfig = false;
            if (onDirtyStateChangeCallback != null) onDirtyStateChangeCallback.accept(false);
            JOptionPane.showMessageDialog(this, "Settings saved.", "⚙️ Settings Saved", JOptionPane.INFORMATION_MESSAGE);
            if (onSaveCb != null) onSaveCb.run();
            refreshFieldsFromConfig();
        }

        @Override
        public boolean isDirty() {
            return userModifiedConfig;
        }

        @Override
        public String getResourceTitle() {
            return configNote.getTitle();
        }

        @Override
        public String getResourceType() {
            return "Configuration";
        }

        @Override
        public Netention.Note getAssociatedNote() {
            return configNote;
        }


        private JComponent buildNostrRelaysPanel() {
            var panel = new JPanel(new BorderLayout(5, 5));
            var listModel = new DefaultListModel<Netention.Note>();
            core.notes.getAll(n -> n.tags.contains(Netention.SystemTag.NOSTR_RELAY.value)).forEach(listModel::addElement);
            var relayList = relayList(listModel);
            panel.add(new JScrollPane(relayList), BorderLayout.CENTER);

            var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttonPanel.add(UIUtil.button("➕", "Add", _ -> editRelayNote(null, listModel)));
            buttonPanel.add(UIUtil.button("✏️", "Edit", _ -> ofNullable(relayList.getSelectedValue()).ifPresent(val -> editRelayNote(val, listModel))));
            buttonPanel.add(UIUtil.button("➖", "Remove", _ -> ofNullable(relayList.getSelectedValue()).ifPresent(sel -> {
                if (JOptionPane.showConfirmDialog(panel, "Delete relay " + sel.content.get(Netention.ContentKey.RELAY_URL.getKey()) + "?", "Confirm Delete", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    core.deleteNote(sel.id);
                    listModel.removeElement(sel);
                    core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, "nostr_relays_updated");
                    markDirty();
                }
            })));
            panel.add(buttonPanel, BorderLayout.SOUTH);
            return panel;
        }

        private @NotNull JList<Netention.Note> relayList(DefaultListModel<Netention.Note> listModel) {
            var relayList = new JList<>(listModel);
            relayList.setCellRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int i, boolean sel, boolean foc) {
                    super.getListCellRendererComponent(list, value, i, sel, foc);
                    if (value instanceof Netention.Note n) {
                        setText(n.content.getOrDefault(Netention.ContentKey.RELAY_URL.getKey(), "N/A") +
                                (Boolean.TRUE.equals(n.content.get(Netention.ContentKey.RELAY_ENABLED.getKey())) ? "" : " (Disabled)"));
                    }
                    return this;
                }
            });
            return relayList;
        }

        private void editRelayNote(@Nullable Netention.Note relayNote, DefaultListModel<Netention.Note> listModel) {
            var editorDialog = new RelayEditDialog((Frame) SwingUtilities.getWindowAncestor(this), core, relayNote);
            editorDialog.setVisible(true);
            if (editorDialog.isSaved()) {
                var savedNote = editorDialog.getRelayNote();
                if (relayNote == null) listModel.addElement(savedNote);
                else listModel.setElementAt(savedNote, listModel.indexOf(relayNote));
                core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, "nostr_relays_updated");
                markDirty();
            }
        }

        static class RelayEditDialog extends JDialog {
            private final Netention.Core core;
            private final boolean isNew;
            private final JTextField urlField;
            private final JCheckBox enabledCheck, readCheck, writeCheck;
            private Netention.Note relayNote;
            private boolean saved = false;

            public RelayEditDialog(Frame owner, Netention.Core core, @Nullable Netention.Note relayNote) {
                super(owner, (relayNote == null ? "Add" : "Edit") + " Relay", true);
                this.core = core;
                this.relayNote = relayNote;
                this.isNew = relayNote == null;

                String initialUrl = isNew ? "wss://" : (String) this.relayNote.content.getOrDefault(Netention.ContentKey.RELAY_URL.getKey(), "wss://");
                boolean initialEnabled = isNew || Boolean.TRUE.equals(this.relayNote.content.get(Netention.ContentKey.RELAY_ENABLED.getKey()));
                boolean initialRead = isNew || Boolean.TRUE.equals(this.relayNote.content.get(Netention.ContentKey.RELAY_READ.getKey()));
                boolean initialWrite = isNew || Boolean.TRUE.equals(this.relayNote.content.get(Netention.ContentKey.RELAY_WRITE.getKey()));

                urlField = new JTextField(initialUrl, 30);
                enabledCheck = new JCheckBox("Enabled", initialEnabled);
                readCheck = new JCheckBox("Read (Subscribe)", initialRead);
                writeCheck = new JCheckBox("Write (Publish)", initialWrite);

                var formPanel = new JPanel(new GridBagLayout());
                var gbc = new GridBagConstraints();
                gbc.insets = new Insets(5, 5, 5, 5);
                gbc.anchor = GridBagConstraints.WEST;
                UIUtil.addLabelAndComponent(formPanel, gbc, 0, "Relay URL:", urlField);
                gbc.gridx = 0;
                gbc.gridy = 1;
                gbc.gridwidth = 2;
                formPanel.add(enabledCheck, gbc);
                gbc.gridy = 2;
                formPanel.add(readCheck, gbc);
                gbc.gridy = 3;
                formPanel.add(writeCheck, gbc);

                var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
                buttonPanel.add(UIUtil.button("💾 Save", null, _ -> saveRelay()));
                buttonPanel.add(UIUtil.button("❌ Cancel", null, _ -> dispose()));

                setLayout(new BorderLayout(10, 10));
                add(formPanel, BorderLayout.CENTER);
                add(buttonPanel, BorderLayout.SOUTH);
                pack();
                setLocationRelativeTo(owner);
            }

            private void saveRelay() {
                var url = urlField.getText().trim();
                if (url.isEmpty() || (!url.startsWith("ws://") && !url.startsWith("wss://"))) {
                    JOptionPane.showMessageDialog(this, "Invalid relay URL. Must start with ws:// or wss://", "Invalid URL", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                var noteToSave = isNew ? new Netention.Note("Relay: " + url, "") : this.relayNote;
                if (isNew) noteToSave.tags.add(Netention.SystemTag.NOSTR_RELAY.value);

                noteToSave.content.putAll(Map.of(
                        Netention.ContentKey.RELAY_URL.getKey(), url,
                        Netention.ContentKey.RELAY_ENABLED.getKey(), enabledCheck.isSelected(),
                        Netention.ContentKey.RELAY_READ.getKey(), readCheck.isSelected(),
                        Netention.ContentKey.RELAY_WRITE.getKey(), writeCheck.isSelected()
                ));
                this.relayNote = core.saveNote(noteToSave);
                this.saved = true;
                dispose();
            }

            public boolean isSaved() {
                return saved;
            }

            public Netention.Note getRelayNote() {
                return relayNote;
            }
        }

        record ConfigFieldEditorFactory(Netention.Core core, Runnable dirtyMarker) {
            public JComponent createEditor(Field field, Object configObjInstance) {
                var cf = field.getAnnotation(Netention.Field.class);
                JComponent comp;
                try {
                    field.setAccessible(true);
                    var currentValue = field.get(configObjInstance);
                    DocumentListener dl = new FieldUpdateListener(_ -> dirtyMarker.run());
                    ActionListener al = _ -> dirtyMarker.run();

                    if (field.getName().equals("privateKeyBech32") && configObjInstance instanceof Netention.Config.NostrSettings ns) {
                        comp = createNostrIdentityEditor(ns, dl);
                    } else {
                        comp = switch (cf.type()) {
                            case TEXT_AREA -> createTextArea(currentValue, dl);
                            case COMBO_BOX -> createComboBox(currentValue, cf.choices(), al);
                            case CHECK_BOX -> createCheckBox(currentValue, al);
                            case PASSWORD_FIELD -> createPasswordField(currentValue, dl);
                            default -> createTextField(currentValue, dl);
                        };
                    }
                    if (!cf.tooltip().isEmpty()) comp.setToolTipText(cf.tooltip());
                    comp.setName(field.getName());
                    return comp;
                } catch (IllegalAccessException e) {
                    logger.error("Error creating editor for field {}: {}", field.getName(), e.getMessage(), e);
                    return new JLabel("Error: " + e.getMessage());
                }
            }

            private JComponent createNostrIdentityEditor(Netention.Config.NostrSettings ns, DocumentListener privateKeyDocListener) {
                var panel = new JPanel(new GridBagLayout());
                var gbc = new GridBagConstraints();
                gbc.insets = new Insets(2, 0, 2, 0);
                gbc.fill = GridBagConstraints.HORIZONTAL;
                gbc.anchor = GridBagConstraints.WEST;

                var privateKeyField = new JPasswordField(30);
                privateKeyField.setName("privateKeyBech32_field");
                if (ns.privateKeyBech32 != null) privateKeyField.setText(ns.privateKeyBech32);
                var publicKeyLabel = new JLabel("Public Key (npub): " + (ns.publicKeyBech32 != null ? ns.publicKeyBech32 : ""));

                privateKeyField.getDocument().addDocumentListener(new FieldUpdateListener(de -> {
                    try {
                        var pkNsec = new String(privateKeyField.getPassword());
                        ns.publicKeyBech32 = !pkNsec.trim().isEmpty() ? Crypto.Bech32.nip19Encode("npub", Crypto.getPublicKeyXOnly(Crypto.Bech32.nip19Decode(pkNsec))) : "Enter nsec to derive";
                    } catch (Exception ex) {
                        ns.publicKeyBech32 = "Invalid nsec format";
                    }
                    publicKeyLabel.setText("Public Key (npub): " + ns.publicKeyBech32);
                    privateKeyDocListener.insertUpdate(de);
                }));

                var generateButton = UIUtil.button("🔑 Generate New", "Generate New Nostr Keys", _ -> {
                    if (JOptionPane.showConfirmDialog(panel, "Generate new Nostr keys & overwrite current ones? BACKUP EXISTING KEYS FIRST!", "Confirm Key Generation", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
                        var keysInfo = core.cfg.generateNewNostrKeysAndUpdateConfig();
                        privateKeyField.setText(core.cfg.net.privateKeyBech32);
                        publicKeyLabel.setText("Public Key (npub): " + core.cfg.net.publicKeyBech32);
                        dirtyMarker.run();
                        var kda = new JTextArea(keysInfo, 5, 50);
                        kda.setEditable(false);
                        kda.setWrapStyleWord(true);
                        kda.setLineWrap(true);
                        JOptionPane.showMessageDialog(panel, new JScrollPane(kda), "New Keys Generated (BACKUP THESE!)", JOptionPane.INFORMATION_MESSAGE);
                    }
                });
                gbc.gridx = 0;
                gbc.gridy = 0;
                gbc.weightx = 1.0;
                panel.add(privateKeyField, gbc);
                gbc.gridy = 1;
                gbc.weightx = 0.0;
                panel.add(publicKeyLabel, gbc);
                gbc.gridy = 2;
                gbc.anchor = GridBagConstraints.EAST;
                panel.add(generateButton, gbc);
                return panel;
            }

            private JScrollPane createTextArea(Object currentValue, DocumentListener dl) {
                var ta = new JTextArea(3, 30);
                if (currentValue instanceof List<?> listVal) {
                    ta.setText(String.join("\n", listVal.stream().map(String::valueOf).toList()));
                } else if (currentValue != null) {
                    ta.setText(currentValue.toString());
                }
                ta.getDocument().addDocumentListener(dl);
                return new JScrollPane(ta);
            }

            private JComboBox<String> createComboBox(Object currentValue, String[] choices, ActionListener al) {
                var cb = new JComboBox<>(choices);
                if (currentValue != null) cb.setSelectedItem(currentValue.toString());
                cb.addActionListener(al);
                return cb;
            }

            private JCheckBox createCheckBox(Object currentValue, ActionListener al) {
                var chkbx = new JCheckBox();
                if (currentValue instanceof Boolean b) chkbx.setSelected(b);
                chkbx.addActionListener(al);
                return chkbx;
            }

            private JPasswordField createPasswordField(Object currentValue, DocumentListener dl) {
                var pf = new JPasswordField(30);
                if (currentValue != null) pf.setText(currentValue.toString());
                pf.getDocument().addDocumentListener(dl);
                return pf;
            }

            private JTextField createTextField(Object currentValue, DocumentListener dl) {
                var tf = new JTextField(30);
                if (currentValue != null) tf.setText(currentValue.toString());
                tf.getDocument().addDocumentListener(dl);
                return tf;
            }

            @SuppressWarnings("unchecked")
            public void refreshFieldComponent(Field field, JComponent component, Object configObjInstance) {
                try {
                    field.setAccessible(true);
                    var value = field.get(configObjInstance);
                    var cf = field.getAnnotation(Netention.Field.class);

                    if (component instanceof JPanel nostrIdentityPanel && "privateKeyBech32".equals(field.getName())) {
                        var pkField = (JPasswordField) Arrays.stream(nostrIdentityPanel.getComponents()).filter(JPasswordField.class::isInstance).findFirst().orElseThrow();
                        var pubKeyLabel = (JLabel) Arrays.stream(nostrIdentityPanel.getComponents()).filter(JLabel.class::isInstance).findFirst().orElseThrow();
                        pkField.setText(value != null ? value.toString() : "");
                        if (configObjInstance instanceof Netention.Config.NostrSettings ns)
                            pubKeyLabel.setText("Public Key (npub): " + ns.publicKeyBech32);
                        return;
                    }

                    var editor = (component instanceof JScrollPane scp) ? (JComponent) scp.getViewport().getView() : component;
                    if (editor instanceof JTextArea ta) {
                        if (cf.type() == Netention.FieldType.TEXT_AREA && value instanceof List<?> listVal)
                            ta.setText(String.join("\n", (List<String>) listVal));
                        else ta.setText(value != null ? value.toString() : "");
                    } else if (editor instanceof JTextComponent tc) {
                        tc.setText(value != null ? value.toString() : "");
                    } else if (editor instanceof JComboBox<?> cb) {
                        cb.setSelectedItem(value != null ? value.toString() : null);
                    } else if (editor instanceof JCheckBox chkbx) {
                        chkbx.setSelected(Boolean.TRUE.equals(value));
                    }
                } catch (IllegalAccessException e) {
                    logger.error("Error refreshing config field {}: {}", field.getName(), e.getMessage(), e);
                }
            }

            public void saveComponentValueToField(Field field, JComponent component, Object configObjInstance) {
                try {
                    field.setAccessible(true);
                    var cf = field.getAnnotation(Netention.Field.class);

                    if (component instanceof JPanel nostrIdentityPanel && "privateKeyBech32".equals(field.getName())) {
                        var pkField = (JPasswordField) Arrays.stream(nostrIdentityPanel.getComponents()).filter(JPasswordField.class::isInstance).findFirst().orElseThrow();
                        field.set(configObjInstance, new String(pkField.getPassword()));
                        return;
                    }

                    var editor = (component instanceof JScrollPane scp) ? (JComponent) scp.getViewport().getView() : component;
                    Object v = null;
                    switch (editor) {
                        case JTextArea ta when cf.type() == Netention.FieldType.TEXT_AREA ->
                                v = new ArrayList<>(Arrays.asList(ta.getText().split("\\n")));
                        case JPasswordField pf -> v = new String(pf.getPassword());
                        case JTextField tf -> v = tf.getText();
                        case JComboBox<?> cb -> v = cb.getSelectedItem();
                        case JCheckBox chkbx -> v = chkbx.isSelected();
                        default -> {
                            logger.warn("Unhandled component type for field {}: {}", field.getName(), editor.getClass().getName());
                            return;
                        }
                    }
                    field.set(configObjInstance, v);
                } catch (IllegalAccessException e) {
                    logger.error("Error saving config field {}: {}", field.getName(), e.getMessage(), e);
                }
            }
        }
    }

    public static class StatusPanel extends JPanel {
        private final JLabel label, nostrStatusLabel, llmStatusLabel, systemHealthLabel;
        private final Netention.Core core;

        public StatusPanel(Netention.Core core) {
            super(new FlowLayout(FlowLayout.LEFT));
            this.core = core;
            setBorder(new EmptyBorder(2, 5, 2, 5));
            label = new JLabel("⏳ Initializing...");
            nostrStatusLabel = new JLabel();
            llmStatusLabel = new JLabel();
            systemHealthLabel = new JLabel();
            Stream.of(label, createSeparator(), nostrStatusLabel, createSeparator(), llmStatusLabel, createSeparator(), systemHealthLabel)
                    .forEach(this::add);

            updateStatus("🚀 Application ready.");
            core.addCoreEventListener(e -> {
                if (e.type() == Netention.Core.CoreEventType.CONFIG_CHANGED || e.type() == Netention.Core.CoreEventType.STATUS_MESSAGE) {
                    String msg = label.getText().replaceFirst(".*?: ", "").split(" \\| ")[0];
                    if (e.type() == Netention.Core.CoreEventType.STATUS_MESSAGE && e.data() instanceof String s)
                        msg = s;
                    updateStatus(msg);
                }
            });
            var healthMetricsTimer = new Timer(15000, _ -> updateSystemHealthMetrics());
            healthMetricsTimer.setInitialDelay(1000);
            healthMetricsTimer.start();
        }

        private JSeparator createSeparator() {
            var sep = new JSeparator(SwingConstants.VERTICAL);
            sep.setPreferredSize(new Dimension(sep.getPreferredSize().width, label.getPreferredSize().height > 0 ? label.getPreferredSize().height : 16));
            return sep;
        }

        private void updateSystemHealthMetrics() {
            try {
                @SuppressWarnings("unchecked")
                var m = (Map<String, Object>) core.executeTool(Netention.Core.Tool.GET_SYSTEM_HEALTH_METRICS, Collections.emptyMap());
                systemHealthLabel.setText(String.format("🩺 Evts:%s Plans:%s Fails:%s",
                        m.getOrDefault("pendingSystemEvents", "?"),
                        m.getOrDefault("activePlans", "?"),
                        m.getOrDefault("failedPlanStepsInActivePlans", "?")));
            } catch (Exception ex) {
                systemHealthLabel.setText("🩺 Health: Error");
                logger.warn("Failed to get system health metrics", ex);
            }
        }

        public void updateStatus(String message) {
            SwingUtilities.invokeLater(() -> {
                label.setText("ℹ️ " + message);
                boolean nostrEnabled = core.net.isEnabled();
                int connectedRelays = nostrEnabled ? core.net.getConnectedRelayCount() : 0;
                int configuredRelays = nostrEnabled ? core.net.getConfiguredRelayCount() : 0;
                nostrStatusLabel.setText("💜 Nostr: " + (nostrEnabled ? (connectedRelays + "/" + configuredRelays + " Relays") : "OFF"));
                nostrStatusLabel.setForeground(nostrEnabled ? (connectedRelays > 0 ? new Color(0, 153, 51) : Color.ORANGE.darker()) : Color.RED);

                llmStatusLabel.setText("💡 LLM: " + (core.lm.isReady() ? "READY" : "NOT READY"));
                llmStatusLabel.setForeground(core.lm.isReady() ? new Color(0, 153, 51) : Color.RED);
            });
        }
    }

    record FieldUpdateListener(Consumer<DocumentEvent> consumer) implements DocumentListener {
        @Override
        public void insertUpdate(DocumentEvent e) {
            consumer.accept(e);
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            consumer.accept(e);
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
            consumer.accept(e);
        }
    }

}
