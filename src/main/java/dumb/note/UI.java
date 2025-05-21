package dumb.note;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import javax.swing.text.StyledEditorKit;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

public class UI {
    private static final Logger logger = LoggerFactory.getLogger(UI.class);

    public static void main(String[] args) {
        var core = new Netention.Core();
        // Example: Launch the full App by default
        // To launch others: new SimpleNote(core).setVisible(true); or new SimpleChat(core).setVisible(true);
        SwingUtilities.invokeLater(() -> {
            // Check for initial Nostr key generation, common for apps using Nostr
            if (core.cfg.net.privateKeyBech32 == null || core.cfg.net.privateKeyBech32.isEmpty()) {
                logger.info("Nostr identity missing. Generating new identity...");
                var keysInfo = core.cfg.generateNewNostrKeysAndUpdateConfig();
                core.net.setEnabled(true); // Enable after generation
                var keyArea = new JTextArea(keysInfo);
                keyArea.setEditable(false);
                keyArea.setWrapStyleWord(true);
                keyArea.setLineWrap(true);
                var scrollPane = new JScrollPane(keyArea);
                scrollPane.setPreferredSize(new Dimension(450, 150));
                JOptionPane.showMessageDialog(null, scrollPane, "🔑 New Nostr Identity Created - BACKUP YOUR nsec KEY!", JOptionPane.INFORMATION_MESSAGE);
            } else if (!core.net.isEnabled() && Arrays.asList(args).contains("App")) { // Enable for full app if configured
                core.net.setEnabled(true);
            }

            // Default to App, could be configurable
            BaseAppFrame appInstance = new App(core);
            // String appType = args.length > 0 ? args[0] : "App";
            // switch (appType) {
            //     case "SimpleNote" -> appInstance = new SimpleNote(core);
            //     case "SimpleChat" -> appInstance = new SimpleChat(core);
            //     default -> appInstance = new App(core);
            // }
            appInstance.setVisible(true);
        });
    }

    enum UIAction {
        NEW_NOTE, NEW_FROM_TEMPLATE, EXIT, SEPARATOR, CUT, COPY, PASTE,
        TOGGLE_INSPECTOR, TOGGLE_NAV_PANEL, SAVE_NOTE, PUBLISH_NOTE, SET_GOAL,
        LINK_NOTE, LLM_ACTIONS_MENU, DELETE_NOTE, TOGGLE_NOSTR, MY_PROFILE,
        PUBLISH_PROFILE, ADD_NOSTR_FRIEND, MANAGE_RELAYS, LLM_SETTINGS, SYNC_ALL, ABOUT,
        // SimpleChat specific actions
        SHOW_MY_NOSTR_PROFILE_EDITOR, MANAGE_NOSTR_RELAYS_POPUP, CONFIGURE_NOSTR_IDENTITY_POPUP
    }

    public record ActionableItem(String id, String planNoteId, String description, String type, Object rawData, Runnable action) {
    }

    abstract static class BaseAppFrame extends JFrame {
        protected final Netention.Core core;
        protected final JPanel contentPanelHost;
        protected final String baseTitle;
        protected final JLabel statusBarLabel;

        public BaseAppFrame(Netention.Core core, String title, int width, int height) {
            this.core = core;
            this.baseTitle = title;
            setTitle(title);
            setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            setSize(width, height);
            setLocationRelativeTo(null);

            contentPanelHost = new JPanel(new BorderLayout());
            add(contentPanelHost, BorderLayout.CENTER);

            statusBarLabel = new JLabel("Ready.");
            statusBarLabel.setBorder(new EmptyBorder(2,5,2,5));
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

        protected void handleCoreEventBase(Netention.Core.CoreEvent event) {
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater(() -> handleCoreEventBase(event));
                return;
            }
            if (event.type() == Netention.Core.CoreEventType.CONFIG_CHANGED && "ui_theme_updated".equals(event.data())) {
                updateTheme(core.cfg.ui.theme);
            }
            if (event.type() == Netention.Core.CoreEventType.STATUS_MESSAGE && event.data() instanceof String msg) {
                updateStatus(msg);
            }
        }

        protected void updateStatus(String message) {
            SwingUtilities.invokeLater(() -> statusBarLabel.setText("ℹ️ " + message));
        }

        protected void handleWindowClose() {
            if (!canSwitchEditorContent(false)) return;
            // Base implementation: just exit. Full App will override for tray.
            if (core.net.isEnabled()) core.net.setEnabled(false); // Basic cleanup
            System.exit(0);
        }

        protected boolean canSwitchEditorContent(boolean switchingToNewUnsavedNote) {
            var currentEditorComp = (contentPanelHost.getComponentCount() > 0) ? contentPanelHost.getComponent(0) : null;
            if (currentEditorComp instanceof NoteEditorPanel nep && nep.isUserModified()) {
                var currentDirtyNote = nep.getCurrentNote();
                var title = (currentDirtyNote != null && currentDirtyNote.getTitle() != null && !currentDirtyNote.getTitle().isEmpty()) ? currentDirtyNote.getTitle() : "Untitled";
                var result = JOptionPane.showConfirmDialog(this, "Note '" + title + "' has unsaved changes. Save them?", "❓ Unsaved Changes", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
                if (result == JOptionPane.YES_OPTION) {
                    nep.saveNote(false); // Don't auto-publish on switch
                    return !nep.isUserModified();
                }
                return result == JOptionPane.NO_OPTION;
            } else if (currentEditorComp instanceof ConfigNoteEditorPanel cnep && cnep.isUserModified()) {
                var result = JOptionPane.showConfirmDialog(this, "Configuration '" + cnep.getConfigNote().getTitle() + "' has unsaved changes. Save them?", "❓ Unsaved Configuration", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
                if (result == JOptionPane.YES_OPTION) {
                    cnep.saveChanges();
                    return !cnep.isUserModified();
                }
                return result == JOptionPane.NO_OPTION;
            }
            return true;
        }

        protected void setContentPanel(JComponent panel) {
            contentPanelHost.removeAll();
            if (panel != null) contentPanelHost.add(panel, BorderLayout.CENTER);
            contentPanelHost.revalidate();
            contentPanelHost.repaint();
            updateFrameTitleWithDirtyState(false);
        }

        public void updateFrameTitleWithDirtyState(boolean isDirty) {
            setTitle(isDirty ? baseTitle + " *" : baseTitle);
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
            if (contentPanelHost.getComponentCount() > 0 && contentPanelHost.getComponent(0) instanceof NoteEditorPanel nep)
                return Optional.of(nep.contentPane);
            return Optional.empty();
        }

        protected JMenuItem createMenuItem(String text, UIAction action, @Nullable KeyStroke accelerator, ActionListener customHandler) {
            var item = new JMenuItem(text);
            if (action != null) item.setActionCommand(action.name());
            item.addActionListener(customHandler != null ? customHandler : this::handleGenericMenuAction);
            if (accelerator != null) item.setAccelerator(accelerator);
            return item;
        }

        protected void handleGenericMenuAction(ActionEvent e) { // Meant to be overridden or unused if custom handlers are always provided
            logger.warn("Unhandled generic menu action: {}", e.getActionCommand());
        }

        protected void displayNoteInEditor(@Nullable Netention.Note note, @Nullable InspectorPanel inspectorPanel) {
            setContentPanel(new NoteEditorPanel(core, note, () -> {
                var editorPanel = (NoteEditorPanel) contentPanelHost.getComponent(0);
                var currentNoteInEditor = editorPanel.getCurrentNote();
                updateStatus(currentNoteInEditor == null || currentNoteInEditor.id == null ? "📝 Note created" : "💾 Note saved: " + currentNoteInEditor.getTitle());
                if (inspectorPanel != null) inspectorPanel.setContextNote(currentNoteInEditor);
            }, inspectorPanel, this::updateFrameTitleWithDirtyState));
        }
    }

    public static class App extends BaseAppFrame {
        final NavPanel navPanel;
        final JSplitPane mainSplitPane;
        final JSplitPane contentInspectorSplit;
        final InspectorPanel inspectorPanel;
        final StatusPanel statusPanel; // Use the more detailed status panel
        private final Map<String, ActionableItem> actionableItems = new ConcurrentHashMap<>();
        private TrayIcon trayIcon;
        private SystemTray tray;

        public App(Netention.Core core) {
            super(core, "Netention ✨", 1280, 800);

            // Replace simple status bar with full one
            remove(super.statusBarLabel);
            statusPanel = new StatusPanel(core);
            add(statusPanel, BorderLayout.SOUTH);

            core.addCoreEventListener(this::handleCoreEvent); // App-specific event handler
            inspectorPanel = new InspectorPanel(core, this, this::display, this::getActionableItemsForNote);
            navPanel = new NavPanel(core, this, this::display, this::displayChatInEditor, this::displayConfigNoteInEditor, this::createNewNote, this::createNewNoteFromTemplate);
            setJMenuBar(createMenuBarFull());

            contentInspectorSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, contentPanelHost, inspectorPanel);
            contentInspectorSplit.setResizeWeight(0.70);
            contentInspectorSplit.setOneTouchExpandable(true);
            mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, navPanel, contentInspectorSplit);
            mainSplitPane.setDividerLocation(280);
            mainSplitPane.setOneTouchExpandable(true);
            // Remove the base contentPanelHost directly from JFrame's content pane
            getContentPane().remove(super.contentPanelHost);
            // Add mainSplitPane to the JFrame's content pane
            getContentPane().add(mainSplitPane, BorderLayout.CENTER);


            initSystemTray();
            displayNoteInEditor(null); // Initial empty editor
            inspectorPanel.setVisible(false);
            contentInspectorSplit.setDividerLocation(1.0);
        }

        @Override
        protected void updateStatus(String message) { // Override to use full status panel
            if (statusPanel != null) statusPanel.updateStatus(message);
            else super.updateStatus(message);
        }


        @Override
        protected void setContentPanel(JComponent panel) { // Need to use the one that takes contextNote
            throw new UnsupportedOperationException("App must use setContentPanel(JComponent, Note)");
        }

        protected void setContentPanel(JComponent panel, @Nullable Netention.Note contextNote) {
            super.contentPanelHost.removeAll(); // contentPanelHost is from BaseAppFrame
            if (panel != null) super.contentPanelHost.add(panel, BorderLayout.CENTER);
            super.contentPanelHost.revalidate();
            super.contentPanelHost.repaint();

            inspectorPanel.setContextNote(contextNote);
            var showInspector = contextNote != null || inspectorPanel.isPlanViewActive() || inspectorPanel.isActionItemsViewActiveAndNotEmpty();
            if (inspectorPanel.isVisible() != showInspector) {
                inspectorPanel.setVisible(showInspector);
                contentInspectorSplit.setDividerLocation(showInspector ? contentInspectorSplit.getResizeWeight() : 1.0);
            }
            updateFrameTitleWithDirtyState(false);
        }


        public List<ActionableItem> getActionableItemsForNote(String noteId) {
            return actionableItems.values().stream()
                    .filter(item -> (item.planNoteId() != null && item.planNoteId().equals(noteId)) ||
                            ("DISTRIBUTED_LM_RESULT".equals(item.type()) && item.rawData() instanceof Map && noteId.equals(((Map<?, ?>) item.rawData()).get("sourceNoteId"))))
                    .toList();
        }

        @SuppressWarnings("unchecked")
        private void handleCoreEvent(Netention.Core.CoreEvent event) { // App-specific full event handler
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater(() -> handleCoreEvent(event));
                return;
            }
            // Call base handler for common events like theme changes
            super.handleCoreEventBase(event);

            switch (event.type()) {
                case USER_INTERACTION_REQUESTED -> {
                    if (event.data() instanceof Map data) {
                        var prompt = (String) data.getOrDefault(Netention.Planner.ToolParam.PROMPT.getKey(), "Input required:");
                        var callbackKey = (String) data.get(Netention.Planner.ToolParam.CALLBACK_KEY.getKey());
                        var planNoteId = (String) data.get(Netention.Planner.ToolParam.PLAN_NOTE_ID.getKey());
                        if (callbackKey != null) {
                            var itemId = "user_input_" + callbackKey;
                            var item = new ActionableItem(itemId, planNoteId, "❓ Plan requires input: " + prompt, "USER_INTERACTION", data, () -> {
                                var input = JOptionPane.showInputDialog(this, prompt, "❓ User Interaction", JOptionPane.QUESTION_MESSAGE);
                                core.planner.postUserInteractionResult(callbackKey, input);
                                actionableItems.remove(itemId);
                                core.fireCoreEvent(Netention.Core.CoreEventType.ACTIONABLE_ITEM_REMOVED, itemId);
                            });
                            actionableItems.put(itemId, item);
                            core.fireCoreEvent(Netention.Core.CoreEventType.ACTIONABLE_ITEM_ADDED, item);
                        }
                    }
                }
                case DISTRIBUTED_LM_RESULT -> {
                    if (event.data() instanceof Map data) {
                        var sourceNoteId = (String) data.get("sourceNoteId");
                        var tool = (String) data.get("tool");
                        var result = data.get("result");
                        var processedByNpub = (String) data.get("processedByNpub");
                        var itemId = "lm_result_" + sourceNoteId + "_" + System.currentTimeMillis();
                        var item = new ActionableItem(itemId, null, String.format("💡 LM Result from %s for %s", processedByNpub.substring(0, 10) + "...", tool), "DISTRIBUTED_LM_RESULT", data, () -> {
                            core.notes.get(sourceNoteId).ifPresent(sourceNote -> {
                                var message = String.format("User %s processed your note '%s' using %s.\nResult:\n%s", processedByNpub.substring(0, 10) + "...", sourceNote.getTitle(), tool, result);
                                Object[] options = {"Apply to Metadata", "Copy Result", "Dismiss"};
                                var choice = JOptionPane.showOptionDialog(this, message, "💡 Distributed LM Result", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[2]);
                                if (choice == 0) { // Apply
                                    sourceNote.meta.put(Netention.Note.Metadata.LLM_SUMMARY.key.replace("summary", tool.toLowerCase()) + "_by_" + processedByNpub, result);
                                    core.saveNote(sourceNote);
                                    if (inspectorPanel.contextNote != null && inspectorPanel.contextNote.id.equals(sourceNoteId))
                                        inspectorPanel.setContextNote(sourceNote);
                                    statusPanel.updateStatus("Applied LM result from " + processedByNpub.substring(0, 10));
                                } else if (choice == 1) { // Copy
                                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(String.valueOf(result)), null);
                                    statusPanel.updateStatus("Copied LM result from " + processedByNpub.substring(0, 10));
                                }
                            });
                            actionableItems.remove(itemId);
                            core.fireCoreEvent(Netention.Core.CoreEventType.ACTIONABLE_ITEM_REMOVED, itemId);
                        });
                        actionableItems.put(itemId, item);
                        core.fireCoreEvent(Netention.Core.CoreEventType.ACTIONABLE_ITEM_ADDED, item);
                    }
                }
                case CHAT_MESSAGE_ADDED, NOTE_ADDED, NOTE_UPDATED, NOTE_DELETED -> {
                    if (navPanel != null) navPanel.refreshNotes();
                }
                case CONFIG_CHANGED -> { // Base handler already called for theme
                    navPanel.updateServiceDependentButtonStates();
                    var currentEditor = super.contentPanelHost.getComponentCount() > 0 ? super.contentPanelHost.getComponent(0) : null;
                    if (currentEditor instanceof NoteEditorPanel nep) nep.updateServiceDependentButtonStates();
                    else if (currentEditor instanceof ConfigNoteEditorPanel cnep) cnep.refreshFieldsFromConfig();
                    inspectorPanel.updateServiceDependentButtonStates();
                    statusPanel.updateStatus("⚙️ Configuration reloaded/changed.");
                    ofNullable(getJMenuBar()).map(JMenuBar::getComponents)
                            .flatMap(menus -> Arrays.stream(menus).filter(c -> c instanceof JMenu && "Nostr 💜".equals(((JMenu) c).getText())).findFirst()
                                    .flatMap(m -> Arrays.stream(((JMenu) m).getMenuComponents()).filter(mc -> mc instanceof JCheckBoxMenuItem && UIAction.TOGGLE_NOSTR.name().equals(((JCheckBoxMenuItem) mc).getActionCommand())).findFirst()))
                            .ifPresent(cbm -> ((JCheckBoxMenuItem) cbm).setSelected(core.net.isEnabled()));
                }
                default -> {}
            }
        }

        @Override
        protected void handleWindowClose() {
            if (!canSwitchEditorContent(false)) return;
            if (core.cfg.ui.minimizeToTray && tray != null && trayIcon != null) {
                setVisible(false);
                trayIcon.displayMessage("Netention ✨", "Running in background.", TrayIcon.MessageType.INFO);
            } else {
                if (core.net.isEnabled()) core.net.setEnabled(false);
                System.exit(0);
            }
        }

        private void addWindowListener(WindowAdapter adapter) { // Override to add specific behavior
            super.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    handleWindowClose(); // Uses App's overridden method
                }
                @Override
                public void windowIconified(WindowEvent e) {
                    if (core.cfg.ui.minimizeToTray && tray != null) setVisible(false);
                }
            });
        }


        private void initSystemTray() {
            if (!SystemTray.isSupported()) {
                logger.warn("SystemTray is not supported.");
                return;
            }
            tray = SystemTray.getSystemTray();
            var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            var g2d = image.createGraphics();
            g2d.setColor(new Color(0x33, 0x66, 0x99));
            g2d.fillRect(0, 0, 16, 16);
            g2d.setColor(Color.WHITE);
            g2d.setFont(new Font("SansSerif", Font.BOLD, 12));
            g2d.drawString("N", 3, 13);
            g2d.dispose();
            trayIcon = new TrayIcon(image, "Netention ✨", createTrayPopupMenu());
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> restoreWindow());
            try {
                tray.add(trayIcon);
            } catch (AWTException e) {
                logger.error("Failed to add tray icon: {}", e.getMessage(), e);
                trayIcon = null;
                tray = null;
            }
        }

        private PopupMenu createTrayPopupMenu() {
            var trayMenu = new PopupMenu();
            Stream.of(new MenuItem("✨ Open Netention") {{ addActionListener(e -> restoreWindow()); }},
                    new MenuItem("➕ Quick Add Note") {{ addActionListener(e -> quickAddNoteFromTray()); }},
                    null,
                    new MenuItem("🚪 Exit") {{ addActionListener(e -> {
                        if (canSwitchEditorContent(false)) { // Ensure clean exit from tray
                            tray.remove(trayIcon);
                            if (core.net.isEnabled()) core.net.setEnabled(false);
                            System.exit(0);
                        }
                    }); }}
            ).forEach(item -> {
                if (item == null) trayMenu.addSeparator(); else trayMenu.add(item);
            });
            return trayMenu;
        }

        private void restoreWindow() {
            setVisible(true);
            setState(JFrame.NORMAL);
            toFront();
            requestFocus();
        }

        private void quickAddNoteFromTray() {
            restoreWindow();
            createNewNote();
        }

        public void display(@Nullable Netention.Note note) {
            var tryingToDisplaySameNote = false;
            if (super.contentPanelHost.getComponentCount() > 0) {
                var currentEditorComp = super.contentPanelHost.getComponent(0);
                Netention.Note currentNoteInPanel = null;
                if (currentEditorComp instanceof NoteEditorPanel nep) currentNoteInPanel = nep.getCurrentNote();
                else if (currentEditorComp instanceof ConfigNoteEditorPanel cnep) currentNoteInPanel = cnep.getConfigNote();
                else if (currentEditorComp instanceof ChatPanel cp) currentNoteInPanel = cp.getChatNote();

                if (currentNoteInPanel != null && note != null && Objects.equals(currentNoteInPanel.id, note.id)) {
                    tryingToDisplaySameNote = true;
                    if (currentEditorComp instanceof NoteEditorPanel nep) {
                        nep.updateNonTextParts(note);
                        inspectorPanel.setContextNote(note);
                        return;
                    }
                }
            }

            if (!tryingToDisplaySameNote && !canSwitchEditorContent(note == null || note.id == null)) return;

            if (note == null) displayNoteInEditor(null);
            else if (note.tags.contains(Netention.Note.SystemTag.CHAT.value)) displayChatInEditor(note);
            else if (note.tags.contains(Netention.Note.SystemTag.CONFIG.value)) displayConfigNoteInEditor(note);
            else displayNoteInEditor(note);
        }

        private void displayNoteInEditor(@Nullable Netention.Note note) {
            setContentPanel(new NoteEditorPanel(core, note, () -> {
                var editorPanel = (NoteEditorPanel) super.contentPanelHost.getComponent(0);
                var currentNoteInEditor = editorPanel.getCurrentNote();
                statusPanel.updateStatus(currentNoteInEditor == null || currentNoteInEditor.id == null ? "📝 Note created" : "💾 Note saved: " + currentNoteInEditor.getTitle());
                inspectorPanel.setContextNote(currentNoteInEditor);
            }, inspectorPanel, this::updateFrameTitleWithDirtyState), note);
        }

        public void createNewNote() {
            if (!canSwitchEditorContent(true)) return;
            displayNoteInEditor(new Netention.Note("Untitled", ""));
        }

        public void createNewNoteFromTemplate(Netention.Note templateNote) {
            if (!canSwitchEditorContent(true)) return;
            var newNote = new Netention.Note(templateNote.getTitle().replaceFirst("\\[Template\\]", "[New]").replaceFirst(Netention.Note.SystemTag.TEMPLATE.value, "").trim(), templateNote.getText());
            newNote.tags.addAll(templateNote.tags.stream().filter(t -> !t.equals(Netention.Note.SystemTag.TEMPLATE.value)).toList());
            if (Netention.ContentType.TEXT_HTML.equals(templateNote.getContentTypeEnum())) newNote.setHtmlText(templateNote.getText());
            core.saveNote(newNote); // Save first to get an ID
            displayNoteInEditor(newNote);
        }

        public void displayChatInEditor(Netention.Note chatNote) {
            if (!chatNote.tags.contains(Netention.Note.SystemTag.CHAT.value)) { display(chatNote); return; }
            var partnerNpub = (String) chatNote.meta.get(Netention.Note.Metadata.NOSTR_PUB_KEY.key);
            if (partnerNpub == null) {
                JOptionPane.showMessageDialog(this, "Chat partner PK (npub) not found.", "💬 Chat Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            setContentPanel(new ChatPanel(core, chatNote, partnerNpub, statusPanel::updateStatus), chatNote);
        }

        public void displayConfigNoteInEditor(Netention.Note configNote) {
            if (!configNote.tags.contains(Netention.Note.SystemTag.CONFIG.value)) { display(configNote); return; }
            setContentPanel(new ConfigNoteEditorPanel(core, configNote, () -> {
                statusPanel.updateStatus("⚙️ Configuration potentially updated.");
                navPanel.updateServiceDependentButtonStates();
                core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, "config_note_saved");
            }, this::updateFrameTitleWithDirtyState), configNote);
        }

        private void updateThemeAndRestartMessage(String themeName) {
            updateTheme(themeName); // Call BaseAppFrame's method
            JOptionPane.showMessageDialog(this, "🎨 Theme changed to " + themeName + ". Some L&F changes may require restart.", "Theme Changed", JOptionPane.INFORMATION_MESSAGE);
        }

        private JMenuBar createMenuBarFull() {
            var mb = new JMenuBar();
            // File Menu
            var fileMenu = new JMenu("File 📁");
            fileMenu.add(createMenuItem("➕ New Note", UIAction.NEW_NOTE, KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK), this::handleMenuAction));
            fileMenu.add(createMenuItem("📄 New Note from Template...", UIAction.NEW_FROM_TEMPLATE, KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), this::handleMenuAction));
            fileMenu.addSeparator();
            fileMenu.add(createMenuItem("🚪 Exit", UIAction.EXIT, null, this::handleMenuAction));
            mb.add(fileMenu);

            // Edit Menu
            var editMenu = new JMenu("Edit ✏️");
            editMenu.add(UIUtil.menuItem("✂️ Cut", UIAction.CUT.name(), e -> getActiveTextComponent().ifPresent(JTextComponent::cut), KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK)));
            editMenu.add(UIUtil.menuItem("📋 Copy", UIAction.COPY.name(), e -> getActiveTextComponent().ifPresent(JTextComponent::copy), KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK)));
            editMenu.add(UIUtil.menuItem("📝 Paste", UIAction.PASTE.name(), e -> getActiveTextComponent().ifPresent(JTextComponent::paste), KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK)));
            mb.add(editMenu);

            // View Menu
            mb.add(createViewMenu());

            // Note Menu
            var noteMenu = new JMenu("Note 📝");
            noteMenu.add(createMenuItem("💾 Save Note", UIAction.SAVE_NOTE, KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), this::handleMenuAction));
            noteMenu.add(createMenuItem("🚀 Publish Note (Nostr)", UIAction.PUBLISH_NOTE, KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK), this::handleMenuAction));
            noteMenu.add(createMenuItem("🎯 Convert to/Edit Goal", UIAction.SET_GOAL, null, this::handleMenuAction));
            noteMenu.add(createMenuItem("🔗 Link to Another Note...", UIAction.LINK_NOTE, null, this::handleMenuAction));
            noteMenu.add(createMenuItem("💡 LLM Actions...", UIAction.LLM_ACTIONS_MENU, null, this::handleMenuAction));
            noteMenu.addSeparator();
            noteMenu.add(createMenuItem("🗑️ Delete Note", UIAction.DELETE_NOTE, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), this::handleMenuAction));
            mb.add(noteMenu);

            // Nostr Menu
            var nostrMenu = new JMenu("Nostr 💜");
            nostrMenu.add(createNostrToggleMenuItem()); // Specific creation method
            nostrMenu.add(createMenuItem("👤 My Nostr Profile", UIAction.MY_PROFILE, null, this::handleMenuAction));
            nostrMenu.add(createMenuItem("🚀 Publish My Profile", UIAction.PUBLISH_PROFILE, null, this::handleMenuAction));
            nostrMenu.add(createMenuItem("➕ Add Nostr Contact...", UIAction.ADD_NOSTR_FRIEND, null, this::handleMenuAction));
            nostrMenu.add(createMenuItem("📡 Manage Relays...", UIAction.MANAGE_RELAYS, null, this::handleMenuAction));
            mb.add(nostrMenu);

            // Tools Menu
            var toolsMenu = new JMenu("Tools 🛠️");
            toolsMenu.add(createMenuItem("💡 LLM Service Status/Settings", UIAction.LLM_SETTINGS, null, this::handleMenuAction));
            toolsMenu.add(createMenuItem("🔄 Synchronize/Refresh All", UIAction.SYNC_ALL, null, this::handleMenuAction));
            mb.add(toolsMenu);

            // Help Menu
            var helpMenu = new JMenu("Help ❓");
            helpMenu.add(createMenuItem("ℹ️ About Netention", UIAction.ABOUT, null, this::handleMenuAction));
            mb.add(helpMenu);

            return mb;
        }

        private JMenu createViewMenu() {
            var viewMenu = new JMenu("View 👁️");
            var toggleInspectorItem = new JCheckBoxMenuItem("Toggle Inspector Panel");
            toggleInspectorItem.setActionCommand(UIAction.TOGGLE_INSPECTOR.name());
            toggleInspectorItem.setSelected(inspectorPanel.isVisible());
            toggleInspectorItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
            toggleInspectorItem.addActionListener(this::handleMenuAction);
            viewMenu.add(toggleInspectorItem);

            var toggleNavPanelItem = new JCheckBoxMenuItem("Toggle Navigation Panel");
            toggleNavPanelItem.setActionCommand(UIAction.TOGGLE_NAV_PANEL.name());
            toggleNavPanelItem.setSelected(navPanel.isVisible());
            toggleNavPanelItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
            toggleNavPanelItem.addActionListener(this::handleMenuAction);
            viewMenu.add(toggleNavPanelItem);
            viewMenu.addSeparator();

            var themesMenu = new JMenu("🎨 Themes");
            var themeGroup = new ButtonGroup();
            Stream.of("System Default", "Nimbus (Dark)").forEach(themeName -> {
                var themeItem = new JRadioButtonMenuItem(themeName, themeName.equals(core.cfg.ui.theme));
                themeItem.addActionListener(e -> {
                    core.cfg.ui.theme = themeName;
                    updateThemeAndRestartMessage(themeName); // App specific message
                    core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, "ui_theme_updated");
                });
                themeGroup.add(themeItem);
                themesMenu.add(themeItem);
            });
            viewMenu.add(themesMenu);
            return viewMenu;
        }

        private JCheckBoxMenuItem createNostrToggleMenuItem() {
            var toggleNostr = new JCheckBoxMenuItem("🌐 Enable Nostr Connection");
            toggleNostr.setActionCommand(UIAction.TOGGLE_NOSTR.name());
            toggleNostr.setSelected(core.net.isEnabled());
            toggleNostr.addActionListener(this::handleMenuAction);
            return toggleNostr;
        }

        private void handleMenuAction(ActionEvent e) {
            UIAction action;
            try {
                action = UIAction.valueOf(e.getActionCommand());
            } catch (IllegalArgumentException ex) {
                logger.warn("Unknown menu action: {}", e.getActionCommand());
                return;
            }

            var currentEditorComp = super.contentPanelHost.getComponentCount() > 0 ? super.contentPanelHost.getComponent(0) : null;
            var currentEditorPanel = (currentEditorComp instanceof NoteEditorPanel nep) ? nep : null;

            switch (action) {
                case NEW_NOTE -> createNewNote();
                case NEW_FROM_TEMPLATE -> {
                    if (!canSwitchEditorContent(true)) return;
                    var templates = core.notes.getAll(n -> n.tags.contains(Netention.Note.SystemTag.TEMPLATE.value));
                    if (templates.isEmpty()) {
                        JOptionPane.showMessageDialog(this, "No templates found...", "🤷 No Templates", JOptionPane.INFORMATION_MESSAGE);
                        return;
                    }
                    var selectedTemplate = (Netention.Note) JOptionPane.showInputDialog(this, "Select a template:", "📄 New from Template", JOptionPane.PLAIN_MESSAGE, null, templates.toArray(), templates.isEmpty() ? null : templates.getFirst());
                    if (selectedTemplate != null) createNewNoteFromTemplate(selectedTemplate);
                }
                case EXIT -> handleWindowClose(); // Uses App's specific close handler
                case TOGGLE_INSPECTOR -> {
                    var show = ((JCheckBoxMenuItem) e.getSource()).isSelected();
                    inspectorPanel.setVisible(show);
                    contentInspectorSplit.setDividerLocation(show ? contentInspectorSplit.getResizeWeight() : (contentInspectorSplit.getWidth() - contentInspectorSplit.getDividerSize()));
                    contentInspectorSplit.revalidate();
                }
                case TOGGLE_NAV_PANEL -> {
                    var show = ((JCheckBoxMenuItem) e.getSource()).isSelected();
                    navPanel.setVisible(show);
                    mainSplitPane.setDividerLocation(show ? navPanel.getPreferredSize().width : 0);
                    mainSplitPane.revalidate();
                }
                case SAVE_NOTE -> { if (currentEditorPanel != null) currentEditorPanel.saveNote(false); }
                case PUBLISH_NOTE -> { if (currentEditorPanel != null) currentEditorPanel.saveNote(true); }
                case SET_GOAL -> {
                    if (currentEditorPanel != null && currentEditorPanel.getCurrentNote() != null)
                        Stream.of(currentEditorPanel.toolBar.getComponents()).filter(c -> c instanceof JButton && "🎯".equals(((JButton) c).getText())).findFirst().map(c -> (JButton) c).ifPresent(JButton::doClick);
                }
                case DELETE_NOTE -> {
                    if (currentEditorPanel != null && currentEditorPanel.getCurrentNote() != null) {
                        var noteToDelete = currentEditorPanel.getCurrentNote();
                        if (JOptionPane.showConfirmDialog(this, "🗑️ Delete note '" + noteToDelete.getTitle() + "'?", "Confirm Delete", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                            var isCurrentNote = noteToDelete.id != null && noteToDelete.id.equals(currentEditorPanel.getCurrentNote().id);
                            if (isCurrentNote && !canSwitchEditorContent(false)) return;
                            core.deleteNote(noteToDelete.id);
                            if (isCurrentNote) displayNoteInEditor(null);
                        }
                    }
                }
                case MY_PROFILE -> {
                    if (!canSwitchEditorContent(false)) return;
                    ofNullable(core.cfg.net.myProfileNoteId).filter(id -> !id.isEmpty()).flatMap(core.notes::get)
                            .ifPresentOrElse(this::displayNoteInEditor, // Will display in NoteEditorPanel
                                    () -> JOptionPane.showMessageDialog(this, "My Profile note ID not configured or note not found.", "👤 Profile Error", JOptionPane.ERROR_MESSAGE));
                }
                case PUBLISH_PROFILE ->
                        ofNullable(core.cfg.net.myProfileNoteId).filter(id -> !id.isEmpty()).flatMap(core.notes::get).ifPresentOrElse(profileNote -> {
                            core.net.publishProfile(profileNote);
                            JOptionPane.showMessageDialog(this, "Profile publish request sent.", "🚀 Nostr Profile", JOptionPane.INFORMATION_MESSAGE);
                        }, () -> JOptionPane.showMessageDialog(this, "My Profile note ID not configured or note not found.", "👤 Profile Error", JOptionPane.ERROR_MESSAGE));
                case TOGGLE_NOSTR -> {
                    var wantsEnable = ((JCheckBoxMenuItem) e.getSource()).isSelected();
                    String statusMsg;
                    if (wantsEnable) {
                        if (core.cfg.net.privateKeyBech32 == null || core.cfg.net.privateKeyBech32.isEmpty()) {
                            JOptionPane.showMessageDialog(this, "Nostr private key (nsec) not configured...", "Nostr Configuration Needed", JOptionPane.WARNING_MESSAGE);
                            statusMsg = "Nostr setup required.";
                            ((JCheckBoxMenuItem) e.getSource()).setSelected(false); // Revert checkbox
                        } else {
                            core.net.setEnabled(true);
                            statusMsg = core.net.isEnabled() ? "Nostr enabled." : "Nostr enabling failed.";
                            JOptionPane.showMessageDialog(this, statusMsg, "💜 Nostr Status", core.net.isEnabled() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE);
                            ((JCheckBoxMenuItem) e.getSource()).setSelected(core.net.isEnabled()); // Sync checkbox with actual state
                        }
                    } else {
                        core.net.setEnabled(false);
                        statusMsg = "Nostr disabled by user.";
                        JOptionPane.showMessageDialog(this, statusMsg, "💜 Nostr Status", JOptionPane.INFORMATION_MESSAGE);
                    }
                    statusPanel.updateStatus(statusMsg);
                    core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, "nostr_status_changed");
                }
                case ADD_NOSTR_FRIEND -> {
                    var pkNpub = JOptionPane.showInputDialog(this, "Friend's Nostr public key (npub):");
                    if (pkNpub != null && !pkNpub.trim().isEmpty()) {
                        try {
                            var cleanNpub = pkNpub.trim();
                            var hexPubKey = Crypto.bytesToHex(Crypto.Bech32.nip19Decode(cleanNpub));
                            core.net.sendFriendRequest(cleanNpub); // Send DM for introduction

                            // Create/update contact note
                            core.notes.get("contact_" + hexPubKey).orElseGet(() -> {
                                var contactN = new Netention.Note("Contact: " + cleanNpub.substring(0, Math.min(12, cleanNpub.length())) + "...", "");
                                contactN.id = "contact_" + hexPubKey;
                                contactN.tags.addAll(Arrays.asList(Netention.Note.SystemTag.CONTACT.value, Netention.Note.SystemTag.NOSTR_CONTACT.value));
                                contactN.meta.putAll(Map.of(
                                        Netention.Note.Metadata.NOSTR_PUB_KEY.key, cleanNpub,
                                        Netention.Note.Metadata.NOSTR_PUB_KEY_HEX.key, hexPubKey
                                ));
                                return core.saveNote(contactN);
                            });

                            // Create chat note if not exists
                            var chatId = "chat_" + cleanNpub; // Use npub for chat ID for simplicity
                            if (core.notes.get(chatId).isEmpty()) {
                                var chatNote = new Netention.Note("Chat with " + cleanNpub.substring(0, Math.min(10, cleanNpub.length())) + "...", "");
                                chatNote.id = chatId;
                                chatNote.tags.addAll(List.of(Netention.Note.SystemTag.CHAT.value, "nostr"));
                                chatNote.meta.put(Netention.Note.Metadata.NOSTR_PUB_KEY.key, cleanNpub);
                                chatNote.content.put(Netention.Note.ContentKey.MESSAGES.getKey(), new ArrayList<Map<String, String>>());
                                core.saveNote(chatNote);
                                JOptionPane.showMessageDialog(this, "Friend " + cleanNpub.substring(0,10) + "... added & intro DM sent.", "🤝 Friend Added", JOptionPane.INFORMATION_MESSAGE);
                            } else {
                                JOptionPane.showMessageDialog(this, "Friend " + cleanNpub.substring(0,10) + "... already exists.", "ℹ️ Friend Exists", JOptionPane.INFORMATION_MESSAGE);
                            }
                        } catch (Exception ex) {
                            JOptionPane.showMessageDialog(this, "Invalid Nostr public key (npub) or error: " + ex.getMessage(), "Error Adding Friend", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }
                case MANAGE_RELAYS -> {
                    if (!canSwitchEditorContent(false)) return;
                    navPanel.selectViewAndNote(NavPanel.View.SETTINGS, "config.nostr_relays");
                }
                case LLM_SETTINGS -> {
                    if (!canSwitchEditorContent(false)) return;
                    navPanel.selectViewAndNote(NavPanel.View.SETTINGS, "config.llm");
                }
                case SYNC_ALL -> {
                    core.fireCoreEvent(Netention.Core.CoreEventType.STATUS_MESSAGE, "🔄 Syncing all...");
                    if (core.net.isEnabled()) core.net.requestSync();
                    navPanel.refreshNotes(); // Refresh local view
                    JOptionPane.showMessageDialog(this, "Synchronization requested.", "🔄 Sync All", JOptionPane.INFORMATION_MESSAGE);
                }
                case ABOUT -> JOptionPane.showMessageDialog(this, "Netention ✨ (Full App)\nVersion: (dev)\nYour awesome note-taking and Nostr app!", "ℹ️ About Netention", JOptionPane.INFORMATION_MESSAGE);
                default -> logger.warn("Menu action {} not fully handled in App.", action);
            }
        }
    }

    public static class SimpleNote extends BaseAppFrame {
        private final SimpleNoteListPanel noteListPanel;

        public SimpleNote(Netention.Core core) {
            super(core, "SimpleNote ✨", 800, 600);

            noteListPanel = new SimpleNoteListPanel(core, this::displayNote);
            JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, noteListPanel, contentPanelHost);
            splitPane.setDividerLocation(200);

            // Replace the base contentPanelHost directly from JFrame's content pane
            getContentPane().remove(super.contentPanelHost);
            // Add mainSplitPane to the JFrame's content pane
            getContentPane().add(splitPane, BorderLayout.CENTER);

            setJMenuBar(createSimpleNoteMenuBar());
            core.addCoreEventListener(this::handleSimpleNoteCoreEvent);
            displayNote(null); // Start with an empty editor
        }

        public static void main(String[] args) {
            new SimpleNote(new Netention.Core()).setVisible(true);
        }

        private void handleSimpleNoteCoreEvent(Netention.Core.CoreEvent event) {
            super.handleCoreEventBase(event); // Handle common events
            if (Set.of(Netention.Core.CoreEventType.NOTE_ADDED, Netention.Core.CoreEventType.NOTE_UPDATED, Netention.Core.CoreEventType.NOTE_DELETED).contains(event.type())) {
                SwingUtilities.invokeLater(noteListPanel::refreshNotes);
            }
        }

        private void displayNote(@Nullable Netention.Note note) {
            if (note != null && (note.tags.contains(Netention.Note.SystemTag.CHAT.value) || note.tags.contains(Netention.Note.SystemTag.CONFIG.value))) {
                // SimpleNote doesn't handle these types, show blank or a message.
                displayNoteInEditor(null, null); // Pass null for inspectorPanelRef
                updateStatus("Selected note type not supported in SimpleNote.");
                return;
            }
            if (!canSwitchEditorContent(note == null || note.id == null)) return;
            displayNoteInEditor(note, null); // Pass null for inspectorPanelRef
        }

        private void createNewNote() {
            if (!canSwitchEditorContent(true)) return;
            displayNote(new Netention.Note("Untitled", ""));
        }

        private JMenuBar createSimpleNoteMenuBar() {
            var mb = new JMenuBar();
            var fileMenu = new JMenu("File");
            fileMenu.add(createMenuItem("New Note", UIAction.NEW_NOTE, KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK), e -> createNewNote()));
            fileMenu.addSeparator();
            fileMenu.add(createMenuItem("Exit", UIAction.EXIT, null, e -> handleWindowClose()));
            mb.add(fileMenu);

            var editMenu = new JMenu("Edit");
            editMenu.add(UIUtil.menuItem("Cut", UIAction.CUT.name(), e -> getActiveTextComponent().ifPresent(JTextComponent::cut), KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK)));
            editMenu.add(UIUtil.menuItem("Copy", UIAction.COPY.name(), e -> getActiveTextComponent().ifPresent(JTextComponent::copy), KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK)));
            editMenu.add(UIUtil.menuItem("Paste", UIAction.PASTE.name(), e -> getActiveTextComponent().ifPresent(JTextComponent::paste), KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK)));
            mb.add(editMenu);

            var noteMenu = new JMenu("Note");
            noteMenu.add(createMenuItem("Save Note", UIAction.SAVE_NOTE, KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), e -> {
                if (contentPanelHost.getComponent(0) instanceof NoteEditorPanel nep) nep.saveNote(false);
            }));
            mb.add(noteMenu);

            var helpMenu = new JMenu("Help");
            helpMenu.add(createMenuItem("About SimpleNote", UIAction.ABOUT, null, e ->
                    JOptionPane.showMessageDialog(this, "SimpleNote ✨\nA basic notes editor.", "About SimpleNote", JOptionPane.INFORMATION_MESSAGE)
            ));
            mb.add(helpMenu);
            return mb;
        }
    }

    public static class SimpleChat extends BaseAppFrame {
        private final BuddyListPanel buddyPanel;
        private JCheckBoxMenuItem nostrToggleMenuItem;

        public SimpleChat(Netention.Core core) {
            super(core, "SimpleChat ✨", 900, 700);

            buddyPanel = new BuddyListPanel(core, this::displayContentForIdentifier, this::showMyProfileEditor, this::addNostrContact);
            JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buddyPanel, contentPanelHost);
            splitPane.setDividerLocation(250);

            getContentPane().remove(super.contentPanelHost);
            getContentPane().add(splitPane, BorderLayout.CENTER);

            setJMenuBar(createSimpleChatMenuBar());
            core.addCoreEventListener(this::handleSimpleChatCoreEvent);

            // Initial view: could be empty or a welcome message
            setContentPanel(new JLabel("Select a chat or contact.", SwingConstants.CENTER));

            // Enable Nostr by default for SimpleChat if configured
            if (core.cfg.net.privateKeyBech32 != null && !core.cfg.net.privateKeyBech32.isEmpty()) {
                core.net.setEnabled(true);
            }
            updateNostrToggleState();
        }

        public static void main(String[] args) {
            new SimpleChat(new Netention.Core()).setVisible(true);
        }

        private void handleSimpleChatCoreEvent(Netention.Core.CoreEvent event) {
            super.handleCoreEventBase(event);
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater(() -> handleSimpleChatCoreEvent(event));
                return;
            }
            switch (event.type()) {
                case CHAT_MESSAGE_ADDED, NOTE_ADDED, NOTE_UPDATED, NOTE_DELETED -> { // Note changes might affect contact list or chat notes
                    SwingUtilities.invokeLater(buddyPanel::refreshList);
                    // If current chat is updated, refresh its view
                    if (contentPanelHost.getComponentCount() > 0 && contentPanelHost.getComponent(0) instanceof ChatPanel cp) {
                        if (event.data() instanceof Map dataMap && cp.getChatNote().id.equals(dataMap.get("chatNoteId"))) {
                            // ChatPanel itself listens to core events for its specific chat, so direct refresh might be redundant
                        } else if (event.data() instanceof Netention.Note note && cp.getChatNote().id.equals(note.id)) {
                            // Potentially refresh if the note itself changed (e.g. title)
                        }
                    }
                }
                case CONFIG_CHANGED -> { // Nostr status might have changed
                    updateNostrToggleState();
                    buddyPanel.refreshList(); // Relays might affect contact discovery/presence
                    // If a config editor was open, it might need refresh (handled by ConfigNoteEditorPanel itself)
                }
                default -> {}
            }
        }

        private void updateNostrToggleState() {
            if (nostrToggleMenuItem != null) {
                nostrToggleMenuItem.setSelected(core.net.isEnabled());
            }
            updateStatus(core.net.isEnabled() ? "Nostr Connected" : "Nostr Disconnected");
        }

        // Identifier can be a note ID for a chat, or an npub for a contact profile
        private void displayContentForIdentifier(String identifier) {
            if (!canSwitchEditorContent(false)) return;

            var chatNoteOpt = core.notes.get(identifier).filter(n -> n.tags.contains(Netention.Note.SystemTag.CHAT.value));
            if (chatNoteOpt.isPresent()) {
                var chatNote = chatNoteOpt.get();
                var partnerNpub = (String) chatNote.meta.get(Netention.Note.Metadata.NOSTR_PUB_KEY.key);
                if (partnerNpub != null) {
                    setContentPanel(new ChatPanel(core, chatNote, partnerNpub, this::updateStatus));
                } else {
                    setContentPanel(new JLabel("Error: Chat partner not found.", SwingConstants.CENTER));
                    updateStatus("Error: Chat partner not found for " + chatNote.getTitle());
                }
            } else {
                // Try to see if it's an npub for a contact profile to show as "mini Note view"
                // This is a simplified "mini Note view": show in a dialog using NoteEditorPanel (read-only)
                var contactNpub = identifier;
                if (!identifier.startsWith("npub1")) { // If not an npub, try to find contact note by ID
                    core.notes.get(identifier)
                            .filter(n -> n.tags.contains(Netention.Note.SystemTag.CONTACT.value))
                            .ifPresent(contactNote -> showProfileForContact(contactNote));
                    return;
                }

                // Find contact note by npub
                core.notes.getAll(n -> n.tags.contains(Netention.Note.SystemTag.CONTACT.value) &&
                                contactNpub.equals(n.meta.get(Netention.Note.Metadata.NOSTR_PUB_KEY.key)))
                        .stream().findFirst()
                        .ifPresentOrElse(
                                this::showProfileForContact,
                                () -> {
                                    setContentPanel(new JLabel("Contact profile not found for: " + contactNpub.substring(0,12) + "...", SwingConstants.CENTER));
                                    updateStatus("Contact profile not found for " + contactNpub.substring(0,12) + "...");
                                }
                        );
            }
        }

        private void showProfileForContact(Netention.Note contactNote) {
            // Show profile in a dialog as a "mini Note view"
            var profileDialog = new JDialog(this, "Profile: " + contactNote.getTitle(), false); // false = not modal
            var profileEditor = new NoteEditorPanel(core, contactNote, () -> {}, null, dirty -> {}); // No save, no inspector, no dirty title
            profileEditor.setReadOnlyMode(true); // Make it read-only
            profileDialog.add(profileEditor);
            profileDialog.pack();
            profileDialog.setSize(400, 500);
            profileDialog.setLocationRelativeTo(this);
            profileDialog.setVisible(true);
            updateStatus("Viewing profile: " + contactNote.getTitle());
        }


        private void showMyProfileEditor() {
            if (!canSwitchEditorContent(false)) return; // Check main panel before popup
            var myProfileNoteId = core.cfg.net.myProfileNoteId;
            if (myProfileNoteId == null || myProfileNoteId.isEmpty()) {
                // Option to create one if it doesn't exist
                if (JOptionPane.showConfirmDialog(this, "My Profile note ID not configured. Create one now?", "👤 Profile Setup", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    var newProfileNote = new Netention.Note("My Nostr Profile", "Bio: ...");
                    newProfileNote.tags.add(Netention.Note.SystemTag.MY_PROFILE.value);
                    // Potentially add other default fields or link to Nostr identity
                    core.saveNote(newProfileNote);
                    core.cfg.net.myProfileNoteId = newProfileNote.id;
                    core.cfg.saveAllConfigs(); // Save config change
                    myProfileNoteId = newProfileNote.id;
                    updateStatus("Created and set new profile note.");
                } else {
                    JOptionPane.showMessageDialog(this, "My Profile note ID not configured.", "👤 Profile Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }

            core.notes.get(myProfileNoteId).ifPresentOrElse(profileNote -> {
                var profileDialog = new JDialog(this, "Edit My Nostr Profile", true); // Modal
                var profileEditor = new NoteEditorPanel(core, profileNote,
                        () -> { // OnSave callback
                            updateStatus("Profile note saved.");
                            core.fireCoreEvent(Netention.Core.CoreEventType.NOTE_UPDATED, profileNote); // Notify other parts if needed
                        },
                        null, // No inspector panel
                        isDirty -> profileDialog.setTitle("Edit My Nostr Profile" + (isDirty ? " *" : ""))
                );
                profileDialog.add(profileEditor);

                var publishButton = UIUtil.button("🚀 Publish Profile", null, e -> {
                    profileEditor.saveNote(false); // Save locally first
                    core.net.publishProfile(profileEditor.getCurrentNote());
                    updateStatus("Profile publish request sent.");
                    JOptionPane.showMessageDialog(profileDialog, "Profile publish request sent.", "🚀 Nostr Profile", JOptionPane.INFORMATION_MESSAGE);
                });
                publishButton.setEnabled(core.net.isEnabled());

                var bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
                bottomPanel.add(publishButton);
                profileDialog.add(bottomPanel, BorderLayout.SOUTH);

                profileDialog.pack();
                profileDialog.setSize(500,600);
                profileDialog.setLocationRelativeTo(this);
                profileDialog.setVisible(true);
            }, () -> JOptionPane.showMessageDialog(this, "My Profile note not found (ID: " + core.cfg.net.myProfileNoteId + ").", "👤 Profile Error", JOptionPane.ERROR_MESSAGE));
        }

        private void addNostrContact() {
            // Simplified version of App's add Nostr friend logic
            var pkNpub = JOptionPane.showInputDialog(this, "Friend's Nostr public key (npub):");
            if (pkNpub != null && !pkNpub.trim().isEmpty()) {
                try {
                    var cleanNpub = pkNpub.trim();
                    var hexPubKey = Crypto.bytesToHex(Crypto.Bech32.nip19Decode(cleanNpub));

                    if (core.net.isEnabled()) core.net.sendFriendRequest(cleanNpub);

                    core.notes.get("contact_" + hexPubKey).orElseGet(() -> {
                        var contactN = new Netention.Note("Contact: " + cleanNpub.substring(0, Math.min(12, cleanNpub.length())) + "...", "");
                        contactN.id = "contact_" + hexPubKey;
                        contactN.tags.addAll(Arrays.asList(Netention.Note.SystemTag.CONTACT.value, Netention.Note.SystemTag.NOSTR_CONTACT.value));
                        contactN.meta.putAll(Map.of(Netention.Note.Metadata.NOSTR_PUB_KEY.key, cleanNpub, Netention.Note.Metadata.NOSTR_PUB_KEY_HEX.key, hexPubKey));
                        return core.saveNote(contactN);
                    });

                    var chatId = "chat_" + cleanNpub;
                    if (core.notes.get(chatId).isEmpty()) {
                        var chatNote = new Netention.Note("Chat with " + cleanNpub.substring(0, Math.min(10, cleanNpub.length())) + "...", "");
                        chatNote.id = chatId;
                        chatNote.tags.addAll(List.of(Netention.Note.SystemTag.CHAT.value, "nostr"));
                        chatNote.meta.put(Netention.Note.Metadata.NOSTR_PUB_KEY.key, cleanNpub);
                        chatNote.content.put(Netention.Note.ContentKey.MESSAGES.getKey(), new ArrayList<Map<String, String>>());
                        core.saveNote(chatNote);
                        updateStatus("Friend " + cleanNpub.substring(0,10) + "... added.");
                    } else {
                        updateStatus("Friend " + cleanNpub.substring(0,10) + "... already exists.");
                    }
                    buddyPanel.refreshList();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "Invalid Nostr public key (npub) or error: " + ex.getMessage(), "Error Adding Friend", JOptionPane.ERROR_MESSAGE);
                }
            }
        }

        private void manageNostrRelays() {
            showConfigEditorDialog("config.nostr_relays", "Manage Nostr Relays");
        }
        private void configureNostrIdentity() {
            showConfigEditorDialog("config.nostr_identity", "Configure Nostr Identity");
        }

        private void showConfigEditorDialog(String configNoteId, String dialogTitle) {
            if (!canSwitchEditorContent(false)) return; // Check main panel before popup

            core.notes.get(configNoteId).ifPresentOrElse(configNote -> {
                var configDialog = new JDialog(this, dialogTitle, true); // Modal
                var configEditor = new ConfigNoteEditorPanel(core, configNote,
                        () -> { // OnSave callback
                            updateStatus(dialogTitle + " saved.");
                            core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, configNoteId + "_updated");
                            // If it was nostr_identity, refresh buddy list as pubkey might have changed
                            if ("config.nostr_identity".equals(configNoteId)) buddyPanel.refreshList();
                        },
                        isDirty -> configDialog.setTitle(dialogTitle + (isDirty ? " *" : ""))
                );
                configDialog.add(configEditor);
                configDialog.pack();
                configDialog.setSize(600,400);
                configDialog.setLocationRelativeTo(this);
                configDialog.setVisible(true);
            }, () -> JOptionPane.showMessageDialog(this, "Configuration note '" + configNoteId + "' not found.", "Config Error", JOptionPane.ERROR_MESSAGE));
        }


        private JMenuBar createSimpleChatMenuBar() {
            var mb = new JMenuBar();
            var fileMenu = new JMenu("File");
            fileMenu.add(createMenuItem("Exit", UIAction.EXIT, null, e -> handleWindowClose()));
            mb.add(fileMenu);

            var nostrMenu = new JMenu("Nostr 💜");
            nostrToggleMenuItem = new JCheckBoxMenuItem("Enable Nostr Connection");
            nostrToggleMenuItem.setActionCommand(UIAction.TOGGLE_NOSTR.name());
            nostrToggleMenuItem.addActionListener(e -> {
                var wantsEnable = nostrToggleMenuItem.isSelected();
                if (wantsEnable && (core.cfg.net.privateKeyBech32 == null || core.cfg.net.privateKeyBech32.isEmpty())) {
                    JOptionPane.showMessageDialog(this, "Nostr private key (nsec) not configured. Please configure it via Nostr -> Configure Identity.", "Nostr Configuration Needed", JOptionPane.WARNING_MESSAGE);
                    nostrToggleMenuItem.setSelected(false);
                } else {
                    core.net.setEnabled(wantsEnable);
                }
                updateNostrToggleState(); // Reflects actual state
                core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, "nostr_status_changed_simple_chat");
            });
            nostrMenu.add(nostrToggleMenuItem);
            nostrMenu.addSeparator();
            nostrMenu.add(createMenuItem("My Profile...", UIAction.SHOW_MY_NOSTR_PROFILE_EDITOR, null, e -> showMyProfileEditor()));
            nostrMenu.add(createMenuItem("Add Nostr Contact...", UIAction.ADD_NOSTR_FRIEND, null, e -> addNostrContact()));
            nostrMenu.addSeparator();
            nostrMenu.add(createMenuItem("Manage Relays...", UIAction.MANAGE_NOSTR_RELAYS_POPUP, null, e -> manageNostrRelays()));
            nostrMenu.add(createMenuItem("Configure Identity...", UIAction.CONFIGURE_NOSTR_IDENTITY_POPUP, null, e -> configureNostrIdentity()));
            mb.add(nostrMenu);

            var helpMenu = new JMenu("Help");
            helpMenu.add(createMenuItem("About SimpleChat", UIAction.ABOUT, null, e ->
                    JOptionPane.showMessageDialog(this, "SimpleChat ✨\nA basic Nostr IM client.", "About SimpleChat", JOptionPane.INFORMATION_MESSAGE)
            ));
            mb.add(helpMenu);
            return mb;
        }
    }

    // New Panel for SimpleNote's list
    static class SimpleNoteListPanel extends JPanel {
        private final Netention.Core core;
        private final DefaultListModel<Netention.Note> listModel = new DefaultListModel<>();
        private final JList<Netention.Note> noteJList = new JList<>(listModel);
        private final JTextField searchField = new JTextField(15);

        public SimpleNoteListPanel(Netention.Core core, Consumer<Netention.Note> onNoteSelected) {
            this.core = core;
            setLayout(new BorderLayout(5,5));
            setBorder(new EmptyBorder(5,5,5,5));

            noteJList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            noteJList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting() && noteJList.getSelectedValue() != null) {
                    onNoteSelected.accept(noteJList.getSelectedValue());
                }
            });
            add(new JScrollPane(noteJList), BorderLayout.CENTER);

            var topPanel = new JPanel(new BorderLayout(5,5));
            topPanel.add(UIUtil.button("➕", "New Note", e -> onNoteSelected.accept(new Netention.Note("Untitled", ""))), BorderLayout.WEST);

            var searchBox = new JPanel(new BorderLayout(2,0));
            searchBox.add(new JLabel("🔍"), BorderLayout.WEST);
            searchField.getDocument().addDocumentListener(new FieldUpdateListener(e -> refreshNotes()));
            searchBox.add(searchField, BorderLayout.CENTER);
            topPanel.add(searchBox, BorderLayout.CENTER);
            add(topPanel, BorderLayout.NORTH);
            refreshNotes();
        }

        public void refreshNotes() {
            var selected = noteJList.getSelectedValue();
            listModel.clear();
            var searchTerm = searchField.getText().toLowerCase();
            core.notes.getAll(n -> {
                        // Filter for "user notes": not chat, not config, not contact, not template, etc.
                        var isUserNote = !n.tags.contains(Netention.Note.SystemTag.CHAT.value) &&
                                !n.tags.contains(Netention.Note.SystemTag.CONFIG.value) &&
                                !n.tags.contains(Netention.Note.SystemTag.CONTACT.value) &&
                                !n.tags.contains(Netention.Note.SystemTag.TEMPLATE.value) &&
                                !n.tags.contains(Netention.Note.SystemTag.NOSTR_FEED.value) &&
                                !n.tags.contains(Netention.Note.SystemTag.SYSTEM_EVENT.value);
                        if (!isUserNote) return false;
                        if (searchTerm.isEmpty()) return true;
                        return n.getTitle().toLowerCase().contains(searchTerm) ||
                                n.getText().toLowerCase().contains(searchTerm) ||
                                n.tags.stream().anyMatch(t -> t.toLowerCase().contains(searchTerm));
                    }).stream()
                    .sorted(Comparator.comparing((Netention.Note n) -> n.updatedAt).reversed())
                    .forEach(listModel::addElement);

            if (selected != null && listModel.contains(selected)) {
                noteJList.setSelectedValue(selected, true);
            } else if (!listModel.isEmpty()) {
                noteJList.setSelectedIndex(0); // Triggers selection listener
            }
        }
    }

    // New Panel for SimpleChat's buddy list
    static class BuddyListPanel extends JPanel {
        private final Netention.Core core;
        private final DefaultListModel<Object> listModel = new DefaultListModel<>(); // Stores Notes (chats/contacts) or npub Strings
        private final JList<Object> buddyJList = new JList<>(listModel);


        public BuddyListPanel(Netention.Core core, Consumer<String> onIdentifierSelected, Runnable onShowMyProfile, Runnable onAddNostrContact) {
            this.core = core;
            // String can be note ID (for chat) or npub (for profile)
            setLayout(new BorderLayout(5,5));
            setBorder(new EmptyBorder(5,5,5,5));

            buddyJList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            buddyJList.setCellRenderer(new BuddyListRenderer(core));
            buddyJList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting() && buddyJList.getSelectedValue() != null) {
                    var selected = buddyJList.getSelectedValue();
                    if (selected instanceof Netention.Note note) {
                        onIdentifierSelected.accept(note.id); // Existing chat or contact note
                    } else if (selected instanceof String npub) {
                        onIdentifierSelected.accept(npub); // For showing profile of a known npub not yet a full contact
                    }
                }
            });
            buddyJList.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        var selected = buddyJList.getSelectedValue();
                        if (selected instanceof Netention.Note note) onIdentifierSelected.accept(note.id);
                        // else if (selected instanceof String npub) onIdentifierSelected.accept(npub); // Handled by selection listener too
                    }
                }
            });

            add(new JScrollPane(buddyJList), BorderLayout.CENTER);

            var buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2,2));
            buttonPanel.add(UIUtil.button("👤", "My Profile", onShowMyProfile));
            buttonPanel.add(UIUtil.button("➕🫂", "Add Contact", onAddNostrContact));
            add(buttonPanel, BorderLayout.NORTH);
            refreshList();
        }

        public void refreshList() {
            var selected = buddyJList.getSelectedValue();
            listModel.clear();

            // Add active chats
            core.notes.getAll(n -> n.tags.contains(Netention.Note.SystemTag.CHAT.value))
                    .stream()
                    .sorted(Comparator.comparing((Netention.Note n) -> n.updatedAt).reversed())
                    .forEach(listModel::addElement);

            // Add contacts (Nostr contacts)
            core.notes.getAll(n -> n.tags.contains(Netention.Note.SystemTag.CONTACT.value) && n.tags.contains(Netention.Note.SystemTag.NOSTR_CONTACT.value))
                    .stream()
                    .filter(contactNote -> { // Don't add if a chat already exists for this contact
                        var contactNpub = (String) contactNote.meta.get(Netention.Note.Metadata.NOSTR_PUB_KEY.key);
                        if (contactNpub == null) return true; // Should not happen for NOSTR_CONTACT
                        return !IntStream.range(0, listModel.getSize())
                                .mapToObj(listModel::getElementAt)
                                .filter(item -> item instanceof Netention.Note)
                                .map(item -> (Netention.Note) item)
                                .anyMatch(chatNote -> chatNote.tags.contains(Netention.Note.SystemTag.CHAT.value) &&
                                        contactNpub.equals(chatNote.meta.get(Netention.Note.Metadata.NOSTR_PUB_KEY.key)));
                    })
                    .sorted(Comparator.comparing(Netention.Note::getTitle))
                    .forEach(listModel::addElement);

            if (selected != null && listModel.contains(selected)) {
                buddyJList.setSelectedValue(selected, true);
            } else if (!listModel.isEmpty()) {
                // buddyJList.setSelectedIndex(0); // Don't auto-select, let user choose
            }
        }

        static class BuddyListRenderer extends DefaultListCellRenderer {
            private final Netention.Core core;
            public BuddyListRenderer(Netention.Core core) { this.core = core; }

            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Netention.Note note) {
                    if (note.tags.contains(Netention.Note.SystemTag.CHAT.value)) {
                        var partnerNpub = (String) note.meta.get(Netention.Note.Metadata.NOSTR_PUB_KEY.key);
                        var displayName = note.getTitle();
                        if (partnerNpub != null && (displayName.startsWith("Chat with") || displayName.isEmpty())) {
                            // Try to find contact display name
                            var contactName = core.notes.getAll(c -> c.tags.contains(Netention.Note.SystemTag.CONTACT.value) && partnerNpub.equals(c.meta.get(Netention.Note.Metadata.NOSTR_PUB_KEY.key)))
                                    .stream().findFirst().map(Netention.Note::getTitle);
                            displayName = "💬 " + contactName.orElse(partnerNpub.substring(0, Math.min(10, partnerNpub.length())) + "...");
                        } else {
                            displayName = "💬 " + displayName;
                        }
                        setText(displayName);
                    } else if (note.tags.contains(Netention.Note.SystemTag.CONTACT.value)) {
                        setText("👤 " + note.getTitle());
                    } else {
                        setText(note.getTitle());
                    }
                } else if (value instanceof String str) {
                    setText("❔ " + str); // e.g. raw npub
                }
                return this;
            }
        }
    }


    // Existing inner classes (NavPanel, NoteEditorPanel, InspectorPanel, ChatPanel, ConfigNoteEditorPanel, StatusPanel)
    // need to be adjusted.
    // For brevity, I'll show adjustments for NoteEditorPanel and ChatPanel constructor.
    // Other large classes (NavPanel, InspectorPanel, ConfigNoteEditorPanel, StatusPanel) are assumed to be mostly
    // compatible or used as-is by App, and their full code is retained from the original problem description.
    // The key is that they are now static inner classes of UI.

    public static class NavPanel extends JPanel { // Copied from original, ensure it's static
        private final Netention.Core core;
        private final App uiRef; // Changed from UI to App
        private final DefaultListModel<Netention.Note> listModel = new DefaultListModel<>();
        private final JList<Netention.Note> noteJList = new JList<>(listModel);
        private final JTextField searchField = new JTextField(15);
        private final JButton semanticSearchButton;
        private final JComboBox<View> viewSelector;
        private final JPanel tagFilterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        private final Set<String> activeTagFilters = new HashSet<>();

        public NavPanel(Netention.Core core, App uiRef, Consumer<Netention.Note> onShowNote, Consumer<Netention.Note> onShowChat, Consumer<Netention.Note> onShowConfigNote, Runnable onNewNote, Consumer<Netention.Note> onNewNoteFromTemplate) {
            this.core = core;
            this.uiRef = uiRef; // App instance
            setLayout(new BorderLayout(5, 5));
            setBorder(new EmptyBorder(5, 5, 5, 5));
            core.addCoreEventListener(event -> {
                if (Set.of(Netention.Core.CoreEventType.NOTE_ADDED, Netention.Core.CoreEventType.NOTE_UPDATED, Netention.Core.CoreEventType.NOTE_DELETED, Netention.Core.CoreEventType.CONFIG_CHANGED, Netention.Core.CoreEventType.CHAT_MESSAGE_ADDED).contains(event.type()))
                    SwingUtilities.invokeLater(this::refreshNotes);
            });
            noteJList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            noteJList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    var newlySelectedNoteInList = noteJList.getSelectedValue();
                    if (newlySelectedNoteInList == null) return;
                    Netention.Note currentNoteInEditor = null;
                    // Access contentPanelHost via uiRef (App instance) which gets it from BaseAppFrame
                    var currentEditorComp = uiRef.contentPanelHost.getComponentCount() > 0 ? uiRef.contentPanelHost.getComponent(0) : null;
                    if (currentEditorComp instanceof NoteEditorPanel nep) currentNoteInEditor = nep.getCurrentNote();
                    else if (currentEditorComp instanceof ConfigNoteEditorPanel cnep)
                        currentNoteInEditor = cnep.getConfigNote();
                    else if (currentEditorComp instanceof ChatPanel cp) currentNoteInEditor = cp.getChatNote();

                    if (currentNoteInEditor == null || (currentNoteInEditor.id == null && newlySelectedNoteInList.id != null) || (currentNoteInEditor.id != null && !currentNoteInEditor.id.equals(newlySelectedNoteInList.id))) {
                        uiRef.display(newlySelectedNoteInList);
                    }
                }
            });
            noteJList.addMouseListener(new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e)) {
                        var index = noteJList.locationToIndex(e.getPoint());
                        if (index != -1 && noteJList.getCellBounds(index, index).contains(e.getPoint())) {
                            noteJList.setSelectedIndex(index);
                            ofNullable(noteJList.getSelectedValue()).ifPresent(selectedNote -> showNoteContextMenu(selectedNote, e));
                        }
                    }
                }
            });
            add(new JScrollPane(noteJList), BorderLayout.CENTER);
            var topControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
            topControls.add(UIUtil.button("➕", "New Note", e -> onNewNote.run()));
            topControls.add(UIUtil.button("📄", "New from Template", e -> {
                var templates = core.notes.getAll(n -> n.tags.contains(Netention.Note.SystemTag.TEMPLATE.value));
                if (templates.isEmpty()) {
                    JOptionPane.showMessageDialog(this, "No templates found.", "🤷 No Templates", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                var selectedTemplate = (Netention.Note) JOptionPane.showInputDialog(this, "Select a template:", "📄 New from Template", JOptionPane.PLAIN_MESSAGE, null, templates.toArray(), templates.isEmpty() ? null: templates.getFirst());
                if (selectedTemplate != null) onNewNoteFromTemplate.accept(selectedTemplate);
            }));
            viewSelector = new JComboBox<>(View.values());
            viewSelector.setSelectedItem(View.NOTES);
            viewSelector.addActionListener(e -> {
                refreshNotes();
                updateServiceDependentButtonStates();
            });
            var searchPanel = new JPanel(new BorderLayout(5, 0));
            searchPanel.add(new JLabel("🔍"), BorderLayout.WEST);
            searchField.setToolTipText("Search notes by title, content, or tags");
            searchPanel.add(searchField, BorderLayout.CENTER);
            searchField.getDocument().addDocumentListener(new FieldUpdateListener(e -> refreshNotes()));
            semanticSearchButton = UIUtil.button("🧠", "AI Search", e -> performSemanticSearch());
            var combinedSearchPanel = new JPanel(new BorderLayout());
            combinedSearchPanel.add(searchPanel, BorderLayout.CENTER);
            combinedSearchPanel.add(semanticSearchButton, BorderLayout.EAST);
            var tagScrollPane = new JScrollPane(tagFilterPanel, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            tagScrollPane.setPreferredSize(new Dimension(200, 60)); // Adjust height as needed
            var topBox = Box.createVerticalBox();
            Stream.of(topControls, viewSelector, combinedSearchPanel, tagScrollPane).forEach(comp -> {
                comp.setAlignmentX(Component.LEFT_ALIGNMENT);
                // Ensure components don't stretch vertically beyond preferred height
                comp.setMaximumSize(new Dimension(Integer.MAX_VALUE, comp.getPreferredSize().height));
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
            CompletableFuture.supplyAsync(() -> core.lm.generateEmbedding(query))
                    .thenAcceptAsync(queryEmbOpt -> queryEmbOpt.ifPresentOrElse(qEmb -> {
                        var notesWithEmb = core.notes.getAllNotes().stream().filter(n -> {
                            // Exclude config notes and ensure embedding compatibility
                            return !n.tags.contains(Netention.Note.SystemTag.CONFIG.value) &&
                                    n.getEmbeddingV1() != null && n.getEmbeddingV1().length == qEmb.length;
                        }).toList();
                        if (notesWithEmb.isEmpty()) {
                            JOptionPane.showMessageDialog(this, "No notes with embeddings found for comparison.", "🧠 Semantic Search", JOptionPane.INFORMATION_MESSAGE);
                            return;
                        }
                        var scored = notesWithEmb.stream()
                                .map(n -> Map.entry(n, LM.cosineSimilarity(qEmb, n.getEmbeddingV1())))
                                .filter(entry -> entry.getValue() > 0.1) // Basic relevance threshold
                                .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
                                .map(Map.Entry::getKey)
                                .collect(Collectors.toList());
                        if (scored.isEmpty()) JOptionPane.showMessageDialog(this, "No relevant notes found.", "🧠 Semantic Search", JOptionPane.INFORMATION_MESSAGE);
                        else refreshNotes(scored); // Display scored notes
                    }, () -> JOptionPane.showMessageDialog(this, "Failed to generate embedding for query.", "LLM Error", JOptionPane.ERROR_MESSAGE)), SwingUtilities::invokeLater)
                    .exceptionally(ex -> {
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Error during semantic search: " + ex.getCause().getMessage(), "Search Error", JOptionPane.ERROR_MESSAGE));
                        return null;
                    });
        }
        public void selectViewAndNote(View view, String noteId) {
            viewSelector.setSelectedItem(view); // This will trigger refreshNotes via its ActionListener
            // After refresh, try to select the note
            SwingUtilities.invokeLater(() -> { // Ensure refresh has completed
                core.notes.get(noteId).ifPresent(noteToSelect -> {
                    var index = listModel.indexOf(noteToSelect);
                    if (index >= 0) {
                        noteJList.setSelectedIndex(index);
                        noteJList.ensureIndexIsVisible(index);
                    } else {
                        logger.warn("Note {} not found in view {} after refresh", noteId, view);
                    }
                });
            });
        }
        @SuppressWarnings("unchecked")
        private void showNoteContextMenu(Netention.Note note, MouseEvent e) {
            var contextMenu = new JPopupMenu();
            // Process with My LM (for NOSTR_FEED notes)
            if (note.tags.contains(Netention.Note.SystemTag.NOSTR_FEED.value) && core.lm.isReady() && core.net.isEnabled()) {
                var processMenu = new JMenu("💡 Process with My LM");
                processMenu.setEnabled(core.lm.isReady()); // Should always be true if outer if passed
                Stream.of("Summarize", "Decompose Task", "Identify Concepts").forEach(tool ->
                        processMenu.add(UIUtil.menuItem(tool, ae -> processSharedNoteWithLM(note, tool)))
                );
                contextMenu.add(processMenu);
            }
            // Delete Processed/Failed Event (for SYSTEM_EVENT notes)
            if (note.tags.contains(Netention.Note.SystemTag.SYSTEM_EVENT.value)) {
                var status = (String) note.content.getOrDefault(Netention.Note.ContentKey.STATUS.getKey(), "");
                if (Set.of("PROCESSED", "FAILED_PROCESSING", Netention.Planner.PlanState.FAILED.name()).contains(status)) {
                    contextMenu.add(UIUtil.menuItem("🗑️ Delete Processed/Failed Event", ae -> {
                        if (JOptionPane.showConfirmDialog(this, "Delete system event note '" + note.getTitle() + "'?", "Confirm Delete", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                            core.deleteNote(note.id); // NavPanel refresh will handle list update
                        }
                    }));
                }
            }
            // Generic Delete Note (not for CONFIG notes)
            if (!(note.tags.contains(Netention.Note.SystemTag.CONFIG.value))) {
                contextMenu.add(UIUtil.menuItem("🗑️ Delete Note", ae -> {
                    if (JOptionPane.showConfirmDialog(this, "Delete note '" + note.getTitle() + "'?", "Confirm Delete", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                        // Check if the note being deleted is the one currently in the editor
                        if (uiRef.contentPanelHost.getComponent(0) instanceof NoteEditorPanel nep && nep.getCurrentNote() != null && nep.getCurrentNote().id.equals(note.id)) {
                            if (!uiRef.canSwitchEditorContent(false)) return; // User cancelled saving/discarding
                        }
                        core.deleteNote(note.id);
                        // If the deleted note was in editor, clear the editor
                        if (uiRef.contentPanelHost.getComponent(0) instanceof NoteEditorPanel nep && nep.getCurrentNote() != null && nep.getCurrentNote().id.equals(note.id)) {
                            uiRef.display(null);
                        }
                        // NavPanel refresh will remove it from list.
                    }
                }));
            }
            if (contextMenu.getComponentCount() > 0) contextMenu.show(e.getComponent(), e.getX(), e.getY());
        }
        private void processSharedNoteWithLM(Netention.Note sharedNote, String toolName) {
            if (!core.lm.isReady()) { // Should be checked before calling, but good for safety
                JOptionPane.showMessageDialog(this, "LLM not ready.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            var originalPublisherNpub = (String) sharedNote.meta.get(Netention.Note.Metadata.NOSTR_PUB_KEY.key);
            if (originalPublisherNpub == null) {
                JOptionPane.showMessageDialog(this, "Original publisher (npub) not found in note metadata.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            CompletableFuture.supplyAsync(() -> {
                        var contentToProcess = sharedNote.getContentForEmbedding(); // Use method that gets text or HTML content
                        return switch (toolName) {
                            case "Summarize" -> core.lm.summarize(contentToProcess);
                            case "Decompose Task" -> core.lm.decomposeTask(contentToProcess).map(list -> String.join("\n- ", list));
                            case "Identify Concepts" -> core.lm.chat("Identify key concepts in:\n\n" + contentToProcess);
                            default -> Optional.empty();
                        };
                    }).thenAcceptAsync(resultOpt -> resultOpt.ifPresent(result -> {
                        try {
                            // Construct payload for Nostr DM
                            var payload = Map.of(
                                    "type", "netention_lm_result", // Custom type for recipient to parse
                                    "sourceNoteId", sharedNote.id, // ID of the note that was processed
                                    "tool", toolName,
                                    "result", result,
                                    "processedByNpub", core.net.getPublicKeyBech32() // Your npub
                            );
                            core.net.sendDirectMessage(originalPublisherNpub, core.json.writeValueAsString(payload));
                            JOptionPane.showMessageDialog(this, toolName + " result sent to " + originalPublisherNpub.substring(0,10) + "...", "💡 LM Result Sent", JOptionPane.INFORMATION_MESSAGE);
                        } catch (JsonProcessingException jpe) {
                            JOptionPane.showMessageDialog(this, "Error packaging LM result for DM: " + jpe.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                        }
                    }), SwingUtilities::invokeLater)
                    .exceptionally(ex -> {
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Error processing with LM: " + ex.getCause().getMessage(), "Error", JOptionPane.ERROR_MESSAGE));
                        return null;
                    });
        }
        public void updateServiceDependentButtonStates() {
            semanticSearchButton.setEnabled(core.lm.isReady() && Set.of(View.NOTES, View.GOALS, View.INBOX).contains(viewSelector.getSelectedItem()));
        }
        public void refreshNotes() { refreshNotes(null); }

        public void refreshNotes(List<Netention.Note> notesToDisplay) {
            var listSelectedNoteBeforeRefresh = noteJList.getSelectedValue();
            var listSelectedNoteIdBeforeRefresh = (listSelectedNoteBeforeRefresh != null) ? listSelectedNoteBeforeRefresh.id : null;

            Netention.Note currentNoteInEditor = null;
            var editorIsDirtyWithUnsavedNewNote = false;

            // Determine current state of the editor panel
            if (uiRef.contentPanelHost.getComponentCount() > 0) {
                var currentEditorComp = uiRef.contentPanelHost.getComponent(0);
                if (currentEditorComp instanceof NoteEditorPanel nep) {
                    currentNoteInEditor = nep.getCurrentNote();
                    if (currentNoteInEditor != null && currentNoteInEditor.id == null && nep.isUserModified()) {
                        editorIsDirtyWithUnsavedNewNote = true;
                    }
                } else if (currentEditorComp instanceof ConfigNoteEditorPanel cnep) {
                    // Config notes are less likely to be "new unsaved" in the same way, but check anyway
                    currentNoteInEditor = cnep.getConfigNote();
                    if (currentNoteInEditor != null && currentNoteInEditor.id == null && cnep.isUserModified()) {
                        editorIsDirtyWithUnsavedNewNote = true;
                    }
                }
                // ChatPanel doesn't have the same "new unsaved" concept for the main note object
            }

            listModel.clear();
            var finalFilter = getPredicate(); // Combined filter from view, tags, and search term

            var filteredNotes = (notesToDisplay != null ? notesToDisplay.stream().filter(finalFilter)
                    : core.notes.getAll(finalFilter).stream())
                    .sorted(Comparator.comparing((Netention.Note n) -> n.updatedAt).reversed())
                    .toList();

            filteredNotes.forEach(listModel::addElement);
            updateTagFilterPanel(filteredNotes); // Update available tags based on current list

            if (editorIsDirtyWithUnsavedNewNote) return; // Don't change selection if editing a new unsaved note

            Netention.Note noteToReselect = null;
            // Try to reselect based on the note currently in the editor (if it has an ID)
            if (currentNoteInEditor != null && currentNoteInEditor.id != null) {
                final var editorNoteId = currentNoteInEditor.id;
                noteToReselect = filteredNotes.stream().filter(n -> editorNoteId.equals(n.id)).findFirst().orElse(null);
            }
            // If not found via editor, try to reselect based on previous list selection
            if (noteToReselect == null && listSelectedNoteIdBeforeRefresh != null) {
                final var finalSelectedIdBefore = listSelectedNoteIdBeforeRefresh;
                noteToReselect = filteredNotes.stream().filter(n -> finalSelectedIdBefore.equals(n.id)).findFirst().orElse(null);
            }

            if (noteToReselect != null) {
                noteJList.setSelectedValue(noteToReselect, true);
            } else if (!listModel.isEmpty()) {
                // noteJList.setSelectedIndex(0); // This would auto-select and display the first note
                // Consider if this is always desired, or if no selection is better
            } else {
                // List is empty, potentially clear the editor if nothing was previously selected or if selected item is gone
                // uiRef.display(null); // This might be too aggressive
            }
        }

        private @NotNull Predicate<Netention.Note> getPredicate() {
            var viewFilter = ((View) Objects.requireNonNullElse(viewSelector.getSelectedItem(), View.NOTES)).getFilter();
            Predicate<Netention.Note> tagFilter = n -> activeTagFilters.isEmpty() || n.tags.containsAll(activeTagFilters);
            Predicate<Netention.Note> searchFilter = n -> {
                var term = searchField.getText().toLowerCase();
                if (term.isEmpty()) return true;
                return n.getTitle().toLowerCase().contains(term) ||
                        n.getText().toLowerCase().contains(term) || // Assumes getText() is efficient enough
                        n.tags.stream().anyMatch(t -> t.toLowerCase().contains(term));
            };
            return viewFilter.and(tagFilter).and(searchFilter);
        }

        private void updateTagFilterPanel(List<Netention.Note> currentNotesInList) {
            tagFilterPanel.removeAll();
            // Tags to always exclude from the filter button list (system tags, etc.)
            var alwaysExclude = Stream.of(Netention.Note.SystemTag.values()).map(tag -> tag.value).collect(Collectors.toSet());
            // Add other common structural tags if needed, e.g. "nostr" if it's too broad
            // alwaysExclude.add("nostr"); 

            var counts = currentNotesInList.stream()
                    .flatMap(n -> n.tags.stream())
                    .filter(t -> !alwaysExclude.contains(t) && !t.startsWith("#")) // Exclude system tags and hashtags (if desired)
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

            counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .limit(15) // Show top N tags
                    .forEach(entry -> {
                        var tagButton = new JToggleButton(entry.getKey() + " (" + entry.getValue() + ")");
                        tagButton.setMargin(new Insets(1, 3, 1, 3)); // Smaller margin
                        tagButton.setSelected(activeTagFilters.contains(entry.getKey()));
                        tagButton.addActionListener(e -> {
                            if (tagButton.isSelected()) activeTagFilters.add(entry.getKey());
                            else activeTagFilters.remove(entry.getKey());
                            refreshNotes(); // Re-filter list when a tag filter changes
                        });
                        tagFilterPanel.add(tagButton);
                    });
            tagFilterPanel.revalidate();
            tagFilterPanel.repaint();
        }

        public enum View { // This enum is specific to the full App's NavPanel
            INBOX("📥 Inbox", n -> n.tags.contains(Netention.Note.SystemTag.NOSTR_FEED.value) || (n.tags.contains(Netention.Note.SystemTag.CHAT.value))),
            NOTES("📝 My Notes", n -> isUserNote(n) && !n.tags.contains(Netention.Note.SystemTag.GOAL_WITH_PLAN.value) && !n.tags.contains(Netention.Note.SystemTag.TEMPLATE.value) && !n.tags.contains(Netention.Note.SystemTag.CONTACT.value)),
            GOALS("🎯 Goals", n -> n.tags.contains(Netention.Note.SystemTag.GOAL_WITH_PLAN.value) && isUserNote(n)),
            TEMPLATES("📄 Templates", n -> n.tags.contains(Netention.Note.SystemTag.TEMPLATE.value) && isUserNote(n)),
            CONTACTS("👥 Contacts", n -> n.tags.contains(Netention.Note.SystemTag.CONTACT.value)),
            SETTINGS("⚙️ Settings", n -> n.tags.contains(Netention.Note.SystemTag.CONFIG.value)),
            SYSTEM("🛠️ System Internals", n -> n.tags.contains(Netention.Note.SystemTag.SYSTEM_EVENT.value) || n.tags.contains(Netention.Note.SystemTag.SYSTEM_PROCESS_HANDLER.value));

            private final String displayName;
            private final Predicate<Netention.Note> filter;

            View(String displayName, Predicate<Netention.Note> filter) {
                this.displayName = displayName;
                this.filter = filter;
            }

            private static boolean isUserNote(Netention.Note n) {
                // A user note is one not primarily managed by the system for specific functions like feeds, configs, etc.
                return Stream.of(Netention.Note.SystemTag.NOSTR_FEED, Netention.Note.SystemTag.CONFIG,
                                Netention.Note.SystemTag.SYSTEM_EVENT, Netention.Note.SystemTag.SYSTEM_PROCESS_HANDLER,
                                Netention.Note.SystemTag.NOSTR_RELAY, Netention.Note.SystemTag.SYSTEM_NOTE,
                                Netention.Note.SystemTag.CHAT // Chats are often special, but View.INBOX handles them
                                // Netention.Note.SystemTag.CONTACT // Contacts are special, View.CONTACTS handles them
                        )
                        .map(tag -> tag.value)
                        .noneMatch(n.tags::contains);
            }
            @Override public String toString() { return displayName; }
            public Predicate<Netention.Note> getFilter() { return filter; }
        }
    }

    public static class NoteEditorPanel extends JPanel {
        final JTextField titleF = new JTextField(40);
        final JTextPane contentPane = new JTextPane();
        final JTextField tagsF = new JTextField(40);
        final JToolBar toolBar = new JToolBar();
        private final Netention.Core core;
        private final Runnable onSaveCb;
        @Nullable private final InspectorPanel inspectorPanelRef; // Made nullable
        private final HTMLEditorKit htmlEditorKit = new HTMLEditorKit();
        private final JLabel embStatusL = new JLabel("Embedding: Unknown");
        private final List<DocumentListener> activeDocumentListeners = new ArrayList<>();
        private final Consumer<Boolean> onDirtyStateChange;
        private Netention.Note currentNote;
        private boolean userModified = false;
        private boolean readOnlyMode = false;


        public NoteEditorPanel(Netention.Core core, Netention.Note note, Runnable onSaveCb, @Nullable InspectorPanel inspectorPanelRef, Consumer<Boolean> onDirtyStateChange) {
            super(new BorderLayout(5, 5));
            setBorder(new EmptyBorder(5, 5, 5, 5));
            this.core = core;
            this.currentNote = note;
            this.onSaveCb = onSaveCb;
            this.inspectorPanelRef = inspectorPanelRef;
            this.onDirtyStateChange = onDirtyStateChange;
            setupToolbar();
            add(toolBar, BorderLayout.NORTH);
            var formP = new JPanel(new GridBagLayout());
            var gbc = new GridBagConstraints();
            gbc.insets = new Insets(2, 2, 2, 2);
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.anchor = GridBagConstraints.WEST;
            UIUtil.addLabelAndComponent(formP, gbc, 0, "Title:", titleF);
            UIUtil.addLabelAndComponent(formP, gbc, 1, "Tags:", tagsF);
            tagsF.setToolTipText("Comma-separated. Special: " + Netention.Note.SystemTag.TEMPLATE.value + ", " + Netention.Note.SystemTag.GOAL_WITH_PLAN.value);
            gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH;
            formP.add(new JScrollPane(contentPane), gbc);
            gbc.gridy = 3; gbc.weighty = 0.0; gbc.fill = GridBagConstraints.HORIZONTAL;
            formP.add(embStatusL, gbc);
            add(formP, BorderLayout.CENTER);
            populateFields(note);
        }

        public void setReadOnlyMode(boolean readOnly) {
            this.readOnlyMode = readOnly;
            if (currentNote != null) { // Re-evaluate editability
                var editable = !isEffectivelyReadOnly() && !currentNote.tags.contains(Netention.Note.SystemTag.MY_PROFILE.value);
                titleF.setEditable(editable);
                tagsF.setEditable(editable);
                contentPane.setEditable(!isEffectivelyReadOnly());
                updateServiceDependentButtonStates(); // Toolbar buttons might change
            }
        }

        private void clearDocumentListeners() {
            activeDocumentListeners.forEach(dl -> {
                titleF.getDocument().removeDocumentListener(dl);
                tagsF.getDocument().removeDocumentListener(dl);
                contentPane.getDocument().removeDocumentListener(dl);
            });
            activeDocumentListeners.clear();
        }

        private void setupDocumentListeners() {
            clearDocumentListeners();
            var listener = new FieldUpdateListener(e -> {
                if (!userModified) {
                    userModified = true;
                    if (onDirtyStateChange != null) onDirtyStateChange.accept(true);
                    updateServiceDependentButtonStates(); // Save button may become active
                }
            });
            titleF.getDocument().addDocumentListener(listener);
            tagsF.getDocument().addDocumentListener(listener);
            contentPane.getDocument().addDocumentListener(listener);
            activeDocumentListeners.add(listener);
        }

        private void setupToolbar() {
            toolBar.setFloatable(false);
            toolBar.setRollover(true);
            toolBar.setMargin(new Insets(2, 2, 2, 2));

            for (var action : Arrays.asList(new StyledEditorKit.BoldAction(), new StyledEditorKit.ItalicAction(), new StyledEditorKit.UnderlineAction()))
                toolBar.add(styleButton(action));

            toolBar.addSeparator();
            toolBar.add(UIUtil.button("💾", "Save", "Save Note", e -> saveNote(false)));
            toolBar.add(UIUtil.button("🚀", "Publish", "Save & Publish to Nostr", e -> saveNote(true)));
            toolBar.addSeparator();
            toolBar.add(UIUtil.button("🎯", "Set Goal", "Make this note a Goal/Plan", e -> setGoal()));
            toolBar.addSeparator();
            toolBar.add(getLlmMenu());
        }

        private @NotNull JButton styleButton(StyledEditorKit.StyledTextAction action) {
            var btn = new JButton(action);
            var name = action.getClass().getSimpleName().replace("StyledEditorKit$", "").replace("Action", "");
            btn.setText(name.substring(0, 1));
            btn.setToolTipText(name);
            return btn;
        }

        private void setGoal() {
            if (currentNote == null || isEffectivelyReadOnly()) return;
            updateNoteFromFields();
            if (!currentNote.tags.contains(Netention.Note.SystemTag.GOAL_WITH_PLAN.value)) {
                currentNote.tags.add(Netention.Note.SystemTag.GOAL_WITH_PLAN.value);
            }
            if (core.lm.isReady() && currentNote.content.get(Netention.Note.ContentKey.PLAN_STEPS.getKey()) == null) {
                try {
                    @SuppressWarnings("unchecked") var steps = (List<Netention.Planner.PlanStep>) core.executeTool(Netention.Core.Tool.SUGGEST_PLAN_STEPS, Map.of(Netention.Planner.ToolParam.GOAL_TEXT.getKey(), currentNote.getContentForEmbedding()));
                    if (steps != null && !steps.isEmpty())
                        currentNote.content.put(Netention.Note.ContentKey.PLAN_STEPS.getKey(), steps.stream().map(s -> core.json.convertValue(s, Map.class)).collect(Collectors.toList()));
                } catch (Exception ex) { logger.error("Failed to suggest plan steps: {}", ex.getMessage()); }
            }
            saveNote(false);
            if (inspectorPanelRef != null) inspectorPanelRef.switchToPlanTab();
        }

        private JMenu getLlmMenu() {
            var llmMenu = new JMenu("💡 LLM");
            llmMenu.setToolTipText("LLM Actions for this note");
            Stream.of(LLMAction.values()).forEach(actionEnum -> llmMenu.add(
                    UIUtil.menuItem(
                            actionEnum.name().replace("_", " "),
                            actionEnum.name(),
                            this::handleLLMActionFromToolbar
                    )));
            return llmMenu;
        }

        @SuppressWarnings("unchecked")
        private void handleLLMActionFromToolbar(ActionEvent e) {
            if (currentNote == null || !core.lm.isReady() || isEffectivelyReadOnly()) {
                JOptionPane.showMessageDialog(this, "LLM not ready, no note, or read-only.", "LLM Action", JOptionPane.WARNING_MESSAGE);
                return;
            }
            updateNoteFromFields(); // Ensure current state is captured
            var actionCommand = LLMAction.valueOf(e.getActionCommand());
            var textContent = currentNote.getContentForEmbedding();
            var titleContent = currentNote.getTitle();

            CompletableFuture.runAsync(() -> {
                try {
                    switch (actionCommand) {
                        case EMBED -> core.lm.generateEmbedding(textContent).ifPresentOrElse(emb -> {
                            currentNote.setEmbeddingV1(emb);
                            core.saveNote(currentNote); // Save embedding
                            SwingUtilities.invokeLater(() -> {
                                updateEmbeddingStatus();
                                if (inspectorPanelRef != null) inspectorPanelRef.loadRelatedNotes();
                                JOptionPane.showMessageDialog(this, "Embedding generated.", "💡 LLM", JOptionPane.INFORMATION_MESSAGE);
                            });
                        }, () -> SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Failed to generate embedding.", "LLM Error", JOptionPane.ERROR_MESSAGE)));

                        case SUMMARIZE -> core.lm.summarize(textContent).ifPresent(s -> {
                            currentNote.meta.put(Netention.Note.Metadata.LLM_SUMMARY.key, s);
                            core.saveNote(currentNote);
                            SwingUtilities.invokeLater(() -> {
                                if (inspectorPanelRef != null) inspectorPanelRef.displayLLMAnalysis();
                                JOptionPane.showMessageDialog(this, "Summary saved to metadata.", "💡 LLM", JOptionPane.INFORMATION_MESSAGE);
                            });
                        });

                        case ASK -> SwingUtilities.invokeLater(() -> { // Must run on EDT for JOptionPane
                            var q = JOptionPane.showInputDialog(this, "Ask about note content:");
                            if (q != null && !q.trim().isEmpty()) {
                                CompletableFuture.supplyAsync(() -> core.lm.askAboutText(textContent, q))
                                        .thenAcceptAsync(answerOpt -> answerOpt.ifPresent(a ->
                                                        JOptionPane.showMessageDialog(this, a, "Answer", JOptionPane.INFORMATION_MESSAGE)),
                                                SwingUtilities::invokeLater);
                            }
                        });

                        case DECOMPOSE -> core.lm.decomposeTask(titleContent.isEmpty() ? textContent : titleContent).ifPresent(d -> {
                            currentNote.meta.put(Netention.Note.Metadata.LLM_DECOMPOSITION.key, d);
                            core.saveNote(currentNote);
                            SwingUtilities.invokeLater(() -> {
                                if (inspectorPanelRef != null) inspectorPanelRef.displayLLMAnalysis();
                                JOptionPane.showMessageDialog(this, "Decomposition saved to metadata.", "💡 LLM", JOptionPane.INFORMATION_MESSAGE);
                            });
                        });

                        case SUGGEST_PLAN -> {
                            var steps = (List<Netention.Planner.PlanStep>) core.executeTool(Netention.Core.Tool.SUGGEST_PLAN_STEPS, Map.of(Netention.Planner.ToolParam.GOAL_TEXT.getKey(), textContent));
                            if (steps != null && !steps.isEmpty()) {
                                currentNote.content.put(Netention.Note.ContentKey.PLAN_STEPS.getKey(), steps.stream().map(s -> core.json.convertValue(s, Map.class)).collect(Collectors.toList()));
                                if (!currentNote.tags.contains(Netention.Note.SystemTag.GOAL_WITH_PLAN.value)) {
                                    currentNote.tags.add(Netention.Note.SystemTag.GOAL_WITH_PLAN.value);
                                }
                                core.saveNote(currentNote);
                                SwingUtilities.invokeLater(() -> {
                                    if (inspectorPanelRef != null) inspectorPanelRef.switchToPlanTab();
                                    JOptionPane.showMessageDialog(this, "Suggested plan steps added.", "💡 LLM", JOptionPane.INFORMATION_MESSAGE);
                                });
                            } else {
                                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "No plan steps suggested.", "💡 LLM", JOptionPane.INFORMATION_MESSAGE));
                            }
                        }
                    }
                } catch (Exception ex) {
                    logger.error("Error during LLM action '{}': {}", actionCommand, ex.getMessage(), ex);
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage(), "LLM Error", JOptionPane.ERROR_MESSAGE));
                }
            }).thenRunAsync(() -> populateFields(this.currentNote), SwingUtilities::invokeLater); // Refresh fields after async op
        }

        private boolean isSystemReadOnly() { // Original read-only logic based on system tags
            return currentNote != null && Stream.of(Netention.Note.SystemTag.NOSTR_FEED, Netention.Note.SystemTag.CONFIG,
                            Netention.Note.SystemTag.SYSTEM_PROCESS_HANDLER, Netention.Note.SystemTag.SYSTEM_EVENT,
                            Netention.Note.SystemTag.SYSTEM_NOTE, Netention.Note.SystemTag.NOSTR_RELAY)
                    .map(tag -> tag.value).anyMatch(currentNote.tags::contains);
        }

        private boolean isEffectivelyReadOnly() { // Combines system read-only with explicit read-only mode
            return readOnlyMode || isSystemReadOnly();
        }


        private void populateFields(Netention.Note noteToDisplay) {
            this.currentNote = noteToDisplay;
            clearDocumentListeners(); // Remove old listeners before changing content

            if (currentNote == null) {
                titleF.setText("");
                contentPane.setText("Select or create a note.");
                tagsF.setText("");
                Stream.of(titleF, contentPane, tagsF).forEach(c -> c.setEnabled(false));
                embStatusL.setText("No note loaded.");
            } else {
                titleF.setText(currentNote.getTitle());
                contentPane.setEditorKit(Netention.ContentType.TEXT_HTML.equals(currentNote.getContentTypeEnum()) ? htmlEditorKit : new StyledEditorKit());
                contentPane.setText(currentNote.getText()); // Handles both HTML and plain text via editor kit
                tagsF.setText(String.join(", ", currentNote.tags));
                updateEmbeddingStatus();

                // Determine editability based on effective read-only state and MY_PROFILE tag
                var editableFields = !isEffectivelyReadOnly() && !currentNote.tags.contains(Netention.Note.SystemTag.MY_PROFILE.value);
                var editableContent = !isEffectivelyReadOnly();

                titleF.setEditable(editableFields);
                tagsF.setEditable(editableFields);
                contentPane.setEditable(editableContent);

                Stream.of(titleF, contentPane, tagsF).forEach(c -> c.setEnabled(true)); // Enable components, editability controls actual input
            }
            userModified = false; // Reset modified state
            if (onDirtyStateChange != null) onDirtyStateChange.accept(false);
            setupDocumentListeners(); // Add listeners for new content
            updateServiceDependentButtonStates(); // Update toolbar based on new state
        }

        public void updateNonTextParts(Netention.Note note) { // For external updates to the same note
            this.currentNote = note; // Update internal reference
            // Only update non-text parts that don't trigger userModified flag, like embedding status
            updateEmbeddingStatus();
            // Tags might change externally, so update tags field if not user modified
            if (!userModified) {
                tagsF.setText(String.join(", ", currentNote.tags));
            }
            updateServiceDependentButtonStates();
        }


        public boolean isUserModified() { return userModified; }
        private void updateEmbeddingStatus() {
            embStatusL.setText("Embedding: " + (currentNote != null && currentNote.getEmbeddingV1() != null ? "Generated (" + currentNote.getEmbeddingV1().length + "d)" : "N/A"));
        }

        public void updateServiceDependentButtonStates() {
            var editable = currentNote != null && !isEffectivelyReadOnly();
            var nostrReady = core.net.isEnabled() && core.net.getPrivateKeyBech32() != null && !core.net.getPrivateKeyBech32().isEmpty();

            for (var comp : toolBar.getComponents()) {
                if (comp instanceof JButton btn) {
                    var tooltip = btn.getToolTipText();
                    if (tooltip != null) {
                        if (tooltip.contains("Save Note")) btn.setEnabled(editable && currentNote != null && userModified);
                        else if (tooltip.contains("Set Goal")) btn.setEnabled(editable && currentNote != null && inspectorPanelRef != null); // Goal needs inspector
                        else if (tooltip.contains("Publish to Nostr")) btn.setEnabled(editable && nostrReady && currentNote != null && !currentNote.tags.contains(Netention.Note.SystemTag.MY_PROFILE.value));
                    }
                } else if (comp instanceof JMenu menu && "💡 LLM".equals(menu.getText())) {
                    menu.setEnabled(editable && core.lm.isReady() && currentNote != null);
                }
            }
        }

        public Netention.Note getCurrentNote() { return currentNote; }

        public void updateNoteFromFields() {
            if (currentNote == null) currentNote = new Netention.Note(); // Should not happen if UI flow is correct

            // Only update title and tags if not MY_PROFILE note (profile title/tags usually fixed)
            // and if not effectively read-only
            if (!currentNote.tags.contains(Netention.Note.SystemTag.MY_PROFILE.value) && !isEffectivelyReadOnly()) {
                currentNote.setTitle(titleF.getText());
                currentNote.tags.clear();
                Stream.of(tagsF.getText().split(","))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .forEach(currentNote.tags::add);
            }

            // Update content if not effectively read-only
            if (!isEffectivelyReadOnly()) {
                var d = contentPane.getDocument();
                if (Netention.ContentType.TEXT_HTML.getValue().equals(contentPane.getContentType())) {
                    var sw = new StringWriter();
                    try {
                        htmlEditorKit.write(sw, d, 0, d.getLength());
                        currentNote.setHtmlText(sw.toString());
                    } catch (IOException | BadLocationException e) {
                        logger.warn("Error writing HTML content, falling back to plain text: {}", e.getMessage());
                        currentNote.setText(contentPane.getText()); // Fallback
                    }
                } else { // Plain text
                    try {
                        currentNote.setText(d.getText(0, d.getLength()));
                    } catch (BadLocationException e) {
                        logger.warn("Error getting plain text content: {}", e.getMessage());
                        currentNote.setText(e.toString()); // Error text
                    }
                }
            }
        }

        public void saveNote(boolean andPublish) {
            if (currentNote != null && isEffectivelyReadOnly()) {
                JOptionPane.showMessageDialog(this, "Cannot save read-only items.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            updateNoteFromFields(); // Sync UI to currentNote object
            var saved = core.saveNote(this.currentNote);
            if (saved != null) {
                this.currentNote = saved; // Use the returned (potentially updated) note object
                userModified = false;
                if (onDirtyStateChange != null) onDirtyStateChange.accept(false);

                if (andPublish) {
                    if (!currentNote.tags.contains(Netention.Note.SystemTag.MY_PROFILE.value)) { // Don't auto-publish profile notes this way
                        if (core.net.isEnabled()) {
                            core.net.publishNote(this.currentNote);
                        } else {
                            JOptionPane.showMessageDialog(this, "Nostr not enabled. Note saved locally.", "Nostr Disabled", JOptionPane.WARNING_MESSAGE);
                        }
                    }
                }
                if (onSaveCb != null) onSaveCb.run(); // Callback after save
                populateFields(this.currentNote); // Refresh editor with (potentially modified by save) note
            } else {
                JOptionPane.showMessageDialog(this, "Failed to save note.", "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
        private enum LLMAction { EMBED, SUMMARIZE, ASK, DECOMPOSE, SUGGEST_PLAN }
    }

    public static class ChatPanel extends JPanel { // Constructor adapted
        private final Netention.Core core;
        private final Netention.Note note;
        private final String partnerNpub;
        private final JTextArea chatArea = new JTextArea(20, 50);
        private final JTextField messageInput = new JTextField(40);
        private final DateTimeFormatter chatTSFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
        private final Consumer<Netention.Core.CoreEvent> coreEventListener;
        private final Consumer<String> statusUpdater;


        public ChatPanel(Netention.Core core, Netention.Note note, String partnerNpub, Consumer<String> statusUpdater) {
            super(new BorderLayout(5, 5));
            setBorder(new EmptyBorder(10, 10, 10, 10));
            this.core = core;
            this.note = note;
            this.partnerNpub = partnerNpub;
            this.statusUpdater = statusUpdater;

            chatArea.setEditable(false);
            chatArea.setLineWrap(true);
            chatArea.setWrapStyleWord(true);
            add(new JScrollPane(chatArea), BorderLayout.CENTER);
            var inputP = new JPanel(new BorderLayout(5, 0));
            inputP.add(messageInput, BorderLayout.CENTER);
            inputP.add(UIUtil.button("➡️", "Send", e -> sendMessage()), BorderLayout.EAST);
            add(inputP, BorderLayout.SOUTH);
            messageInput.addActionListener(e -> sendMessage());
            loadMessages();
            coreEventListener = this::handleChatPanelCoreEvent;
            core.addCoreEventListener(coreEventListener);
            // Auto-remove listener when panel is no longer visible/used
            addComponentListener(new ComponentAdapter() {
                @Override public void componentHidden(ComponentEvent e) { core.removeCoreEventListener(coreEventListener); }
                // Consider adding componentRemoved if the panel can be fully detached
            });
        }
        private void handleChatPanelCoreEvent(Netention.Core.CoreEvent event) {
            if (event.type() == Netention.Core.CoreEventType.CHAT_MESSAGE_ADDED &&
                    event.data() instanceof Map data &&
                    note.id.equals(data.get("chatNoteId"))) { // Check if it's for THIS chat
                SwingUtilities.invokeLater(this::loadMessages);
            }
        }
        @SuppressWarnings("unchecked")
        private void loadMessages() {
            chatArea.setText(""); // Clear existing messages
            core.notes.get(this.note.id).ifPresent(freshNote -> {
                var messages = (List<Map<String, String>>) freshNote.content.getOrDefault(Netention.Note.ContentKey.MESSAGES.getKey(), new ArrayList<>());
                messages.forEach(this::formatAndAppendMsg);
            });
            scrollToBottom();
        }
        private void formatAndAppendMsg(Map<String, String> m) {
            var senderNpub = m.get("sender");
            var t = m.get("text");
            var tsStr = m.get("timestamp");
            if (senderNpub == null || t == null || tsStr == null) return; // Invalid message entry

            var ts = Instant.parse(tsStr);
            var dn = senderNpub.equals(core.net.getPublicKeyBech32()) ? "Me" : senderNpub.substring(0, Math.min(senderNpub.length(), 8)) + "...";
            chatArea.append(String.format("[%s] %s: %s\n", chatTSFormatter.format(ts), dn, t));
        }
        private void sendMessage() {
            var txt = messageInput.getText().trim();
            if (txt.isEmpty()) return;

            if (!core.net.isEnabled() || core.net.getPrivateKeyBech32() == null || core.net.getPrivateKeyBech32().isEmpty()) {
                JOptionPane.showMessageDialog(this, "Nostr not enabled/configured.", "Nostr Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            core.net.sendDirectMessage(partnerNpub, txt); // Send over Nostr
            // Add to local chat note immediately
            core.notes.get(this.note.id).ifPresent(currentChatNote -> {
                var entry = Map.of(
                        "sender", core.net.getPublicKeyBech32(), // Your npub
                        "timestamp", Instant.now().toString(),
                        "text", txt
                );
                @SuppressWarnings("unchecked")
                var msgs = (List<Map<String,String>>)currentChatNote.content.computeIfAbsent(Netention.Note.ContentKey.MESSAGES.getKey(), k -> new ArrayList<Map<String,String>>());
                msgs.add(entry);
                core.saveNote(currentChatNote); // Save the updated chat note
                // loadMessages(); // Reload all messages - or just append if performance is an issue
                messageInput.setText(""); // Clear input field
                statusUpdater.accept("Message sent to " + partnerNpub.substring(0,10) + "...");
            });
        }
        private void scrollToBottom() { chatArea.setCaretPosition(chatArea.getDocument().getLength()); }
        public Netention.Note getChatNote() { return note; }
    }

    // InspectorPanel, ConfigNoteEditorPanel, StatusPanel are large and mostly self-contained.
    // Assuming they are now static classes within UI.
    // Their internal logic referring to `uiRef` or similar might need slight adjustments if they were tightly coupled.
    // For example, InspectorPanel's constructor takes `UI uiRef`, this should be `App uiRef`.
    public static class InspectorPanel extends JPanel { /* ... Full existing code, ensure it's static ... */
        private final Netention.Core core;
        private final JTabbedPane tabbedPane;
        private final JPanel planPanel, actionableItemsPanel;
        private final JTextArea llmAnalysisArea;
        private final JLabel noteInfoLabel;
        private final JButton copyPubKeyButton;
        private final DefaultListModel<Netention.Link> linksListModel = new DefaultListModel<>();
        private final JList<Netention.Link> linksJList;
        private final DefaultListModel<Netention.Note> relatedNotesListModel = new DefaultListModel<>();
        private final JList<Netention.Note> relatedNotesJList;
        private final PlanStepsTableModel planStepsTableModel;
        private final DefaultTreeModel planDepsTreeModel;
        private final JTree planDepsTree;
        private final DefaultListModel<ActionableItem> actionableItemsListModel = new DefaultListModel<>();
        private final JList<ActionableItem> actionableItemsJList;
        private final App uiRef; // Changed from UI to App
        Netention.Note contextNote;

        public InspectorPanel(Netention.Core core, App uiRef, Consumer<Netention.Note> noteDisplayCallback, Function<String, List<ActionableItem>> actionableItemsProvider) {
            super(new BorderLayout(5,5));
            this.core = core;
            this.uiRef = uiRef; // App instance
            setPreferredSize(new Dimension(350, 0)); // Initial preferred width
            tabbedPane = new JTabbedPane();

            // Info Tab
            var infoPanel = new JPanel(new BorderLayout());
            noteInfoLabel = new JLabel("No note selected.");
            noteInfoLabel.setBorder(new EmptyBorder(5,5,5,5));
            copyPubKeyButton = UIUtil.button("📋", "Copy PubKey", e -> {
                if (contextNote != null && contextNote.meta.get(Netention.Note.Metadata.NOSTR_PUB_KEY.key) instanceof String npub) {
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(npub), null);
                    uiRef.statusPanel.updateStatus("📋 Copied PubKey: " + npub.substring(0, Math.min(12, npub.length())) + "...");
                }
            });
            copyPubKeyButton.setVisible(false); // Initially hidden
            var infoTopPanel = new JPanel(new BorderLayout());
            infoTopPanel.add(noteInfoLabel, BorderLayout.CENTER);
            infoTopPanel.add(copyPubKeyButton, BorderLayout.EAST);
            infoPanel.add(infoTopPanel, BorderLayout.NORTH);

            llmAnalysisArea = new JTextArea(5,20);
            llmAnalysisArea.setEditable(false);
            llmAnalysisArea.setLineWrap(true);
            llmAnalysisArea.setWrapStyleWord(true);
            llmAnalysisArea.setFont(llmAnalysisArea.getFont().deriveFont(Font.ITALIC));
            // A slightly darker background for contrast, or use system default
            llmAnalysisArea.setBackground(UIManager.getColor("TextArea.background") != null ? UIManager.getColor("TextArea.background").darker() : Color.LIGHT_GRAY);
            infoPanel.add(new JScrollPane(llmAnalysisArea), BorderLayout.CENTER);
            tabbedPane.addTab(Tab.INFO.title, infoPanel);

            // Links Tab
            var linksPanel = new JPanel(new BorderLayout());
            linksJList = new JList<>(linksListModel);
            linksJList.setCellRenderer(new LinkListCellRenderer(core));
            linksJList.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent evt) {
                    if (evt.getClickCount() == 2) { // Double-click to open linked note
                        ofNullable(linksJList.getSelectedValue())
                                .flatMap(l -> core.notes.get(l.targetNoteId))
                                .ifPresent(noteDisplayCallback);
                    }
                }
            });
            linksPanel.add(new JScrollPane(linksJList), BorderLayout.CENTER);
            var linkButtonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            linkButtonsPanel.add(UIUtil.button("➕", "Add Link", e -> addLink()));
            linkButtonsPanel.add(UIUtil.button("➖", "Remove Link", e -> removeLink()));
            linksPanel.add(linkButtonsPanel, BorderLayout.SOUTH);
            tabbedPane.addTab(Tab.LINKS.title, linksPanel);

            // Related Notes Tab
            var relatedNotesPanel = new JPanel(new BorderLayout());
            relatedNotesJList = new JList<>(relatedNotesListModel);
            relatedNotesJList.setCellRenderer(new NoteTitleListCellRenderer());
            relatedNotesJList.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent evt) {
                    if (evt.getClickCount() == 2) { // Double-click to open related note
                        ofNullable(relatedNotesJList.getSelectedValue()).ifPresent(noteDisplayCallback);
                    }
                }
            });
            relatedNotesPanel.add(new JScrollPane(relatedNotesJList), BorderLayout.CENTER);
            tabbedPane.addTab(Tab.RELATED.title, relatedNotesPanel);

            // Plan Tab
            planPanel = new JPanel(new BorderLayout());
            planStepsTableModel = new PlanStepsTableModel();
            var planStepsTable = new JTable(planStepsTableModel);
            planStepsTable.getColumnModel().getColumn(0).setPreferredWidth(150); // Description
            planStepsTable.getColumnModel().getColumn(1).setPreferredWidth(50);  // Status
            planPanel.add(new JScrollPane(planStepsTable), BorderLayout.CENTER);
            planPanel.add(UIUtil.button("▶️", "Execute/Update Plan", e -> {
                if (contextNote != null) {
                    if (!contextNote.tags.contains(Netention.Note.SystemTag.GOAL_WITH_PLAN.value)) {
                        contextNote.tags.add(Netention.Note.SystemTag.GOAL_WITH_PLAN.value); // Ensure it's a goal
                    }
                    core.saveNote(contextNote); // Save tag change if any
                    core.planner.executePlan(contextNote); // Trigger plan execution
                }
            }), BorderLayout.SOUTH);
            tabbedPane.addTab(Tab.PLAN.title, planPanel);

            // Plan Dependencies Tab
            var planDependenciesPanel = new JPanel(new BorderLayout());
            planDepsTreeModel = new DefaultTreeModel(new DefaultMutableTreeNode("Plan Dependencies"));
            planDepsTree = new JTree(planDepsTreeModel);
            planDepsTree.setRootVisible(false); // Don't show the root "Plan Dependencies" node itself
            planDepsTree.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        var node = (DefaultMutableTreeNode) planDepsTree.getLastSelectedPathComponent();
                        if (node != null && node.getUserObject() instanceof Map nodeData && nodeData.containsKey(Netention.Note.NoteProperty.ID.getKey())) {
                            core.notes.get((String)nodeData.get(Netention.Note.NoteProperty.ID.getKey())).ifPresent(noteDisplayCallback);
                        }
                    }
                }
            });
            planDependenciesPanel.add(new JScrollPane(planDepsTree), BorderLayout.CENTER);
            tabbedPane.addTab(Tab.PLAN_DEPS.title, planDependenciesPanel);


            // Actionable Items Tab (Inbox)
            actionableItemsPanel = new JPanel(new BorderLayout());
            actionableItemsJList = new JList<>(actionableItemsListModel);
            actionableItemsJList.setCellRenderer(new ActionableItemCellRenderer());
            actionableItemsJList.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) { // Double-click to execute action
                        ofNullable(actionableItemsJList.getSelectedValue())
                                .filter(item -> item.action() != null)
                                .ifPresent(item -> item.action().run());
                    }
                }
            });
            actionableItemsPanel.add(new JScrollPane(actionableItemsJList), BorderLayout.CENTER);
            tabbedPane.addTab(Tab.INBOX.title, actionableItemsPanel);

            add(tabbedPane, BorderLayout.CENTER);

            // Core event listener for InspectorPanel specific updates
            core.addCoreEventListener(event -> {
                if (!SwingUtilities.isEventDispatchThread()) SwingUtilities.invokeLater(() -> handleCoreEvent(event, actionableItemsProvider));
                else handleCoreEvent(event, actionableItemsProvider);
            });
        }

        private void handleCoreEvent(Netention.Core.CoreEvent event, Function<String, List<ActionableItem>> actionableItemsProvider) {
            if (event.type() == Netention.Core.CoreEventType.PLAN_UPDATED && event.data() instanceof Netention.Planner.PlanExecution exec) {
                if (contextNote != null && contextNote.id.equals(exec.planNoteId)) {
                    planStepsTableModel.setPlanExecution(exec);
                    loadPlanDependencies(); // Refresh dependency graph as plan changes
                }
            } else if (event.type() == Netention.Core.CoreEventType.CONFIG_CHANGED) {
                updateServiceDependentButtonStates(); // e.g. if LLM becomes available/unavailable
            } else if (event.type() == Netention.Core.CoreEventType.ACTIONABLE_ITEM_ADDED || event.type() == Netention.Core.CoreEventType.ACTIONABLE_ITEM_REMOVED) {
                // Reload actionable items, potentially filtered for the current contextNote
                loadActionableItems(actionableItemsProvider.apply(contextNote != null ? contextNote.id : null));
            }
        }

        public boolean isPlanViewActive() { return tabbedPane.getSelectedComponent() == planPanel && contextNote != null; }
        public boolean isActionItemsViewActiveAndNotEmpty() { return tabbedPane.getSelectedComponent() == actionableItemsPanel && contextNote != null && !actionableItemsListModel.isEmpty(); }

        public void switchToPlanTab() {
            if (contextNote != null) {
                tabbedPane.setSelectedComponent(planPanel);
                // Refresh plan steps from core when switching to this tab
                core.planner.getPlanExecution(contextNote.id)
                        .ifPresentOrElse(planStepsTableModel::setPlanExecution,
                                () -> planStepsTableModel.setPlanExecution(null)); // Clear if no plan
            }
        }
        public void setContextNote(Netention.Note note) {
            this.contextNote = note;
            if (note != null) {
                var title = note.getTitle();
                if (title.length() > 35) title = title.substring(0, 32) + "...";
                var tags = String.join(", ", note.tags);
                if (tags.length() > 35) tags = tags.substring(0, 32) + "...";

                var pubKeyInfo = "";
                var isNostrEntity = note.tags.contains(Netention.Note.SystemTag.NOSTR_CONTACT.value) ||
                        note.tags.contains(Netention.Note.SystemTag.NOSTR_FEED.value) ||
                        note.tags.contains(Netention.Note.SystemTag.CHAT.value);
                if (isNostrEntity && note.meta.get(Netention.Note.Metadata.NOSTR_PUB_KEY.key) instanceof String npub) {
                    pubKeyInfo = "<br>PubKey: " + npub.substring(0, Math.min(npub.length(), 12)) + "...";
                    copyPubKeyButton.setVisible(true);
                } else {
                    copyPubKeyButton.setVisible(false);
                }

                noteInfoLabel.setText(String.format("<html><b>%s</b><br>Tags: %s<br>Updated: %s%s</html>",
                        title, tags,
                        DateTimeFormatter.ISO_INSTANT.format(note.updatedAt.atZone(ZoneId.systemDefault())).substring(0, 19), // User-friendly format
                        pubKeyInfo));

                displayLLMAnalysis();
                loadLinks();
                loadRelatedNotes();
                loadPlanDependencies();
                core.planner.getPlanExecution(note.id)
                        .ifPresentOrElse(planStepsTableModel::setPlanExecution,
                                () -> planStepsTableModel.setPlanExecution(null));
                loadActionableItems(uiRef.getActionableItemsForNote(note.id)); // uiRef is App
            } else { // Clear all fields if no note is selected
                noteInfoLabel.setText("No note selected.");
                llmAnalysisArea.setText("");
                copyPubKeyButton.setVisible(false);
                linksListModel.clear();
                relatedNotesListModel.clear();
                planDepsTreeModel.setRoot(new DefaultMutableTreeNode("Plan Dependencies")); // Clear tree
                planStepsTableModel.setPlanExecution(null); // Clear table
                actionableItemsListModel.clear();
            }
        }
        private void loadLinks() {
            linksListModel.clear();
            if (contextNote != null && contextNote.links != null) {
                contextNote.links.forEach(linksListModel::addElement);
            }
        }
        private void addLink() {
            if (contextNote == null) return;
            var allNotes = core.notes.getAllNotes().stream()
                    .filter(n -> !n.id.equals(contextNote.id)) // Exclude self
                    .toList();
            if (allNotes.isEmpty()) {
                JOptionPane.showMessageDialog(this, "No other notes to link to.", "Add Link", JOptionPane.INFORMATION_MESSAGE);
                return;
            }

            var noteSelector = new JComboBox<>(allNotes.toArray(new Netention.Note[0]));
            noteSelector.setRenderer(new NoteTitleListCellRenderer()); // Show titles in combobox

            var relationTypes = new String[]{"relates_to", "supports", "elaborates", "depends_on", "plan_subgoal_of", "plan_depends_on"};
            var relationTypeSelector = new JComboBox<>(relationTypes);
            if (contextNote.tags.contains(Netention.Note.SystemTag.GOAL_WITH_PLAN.value)) {
                relationTypeSelector.setSelectedItem("plan_subgoal_of"); // Default for goals
            }

            var panel = new JPanel(new GridLayout(0, 1));
            panel.add(new JLabel("Select target note:"));
            panel.add(noteSelector);
            panel.add(new JLabel("Relation type:"));
            panel.add(relationTypeSelector);

            if (JOptionPane.showConfirmDialog(this, panel, "🔗 Add Link", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                var targetNote = (Netention.Note) noteSelector.getSelectedItem();
                var relationType = (String) relationTypeSelector.getSelectedItem();
                if (targetNote != null && relationType != null && !relationType.isEmpty()) {
                    contextNote.links.add(new Netention.Link(targetNote.id, relationType));
                    core.saveNote(contextNote); // Save the note with the new link
                    loadLinks(); // Refresh the list
                }
            }
        }
        private void removeLink() {
            if (contextNote == null || linksJList.getSelectedValue() == null) return;
            var selectedLink = linksJList.getSelectedValue();
            var targetTitle = core.notes.get(selectedLink.targetNoteId).map(Netention.Note::getTitle).orElse("Unknown");
            if (JOptionPane.showConfirmDialog(this, "Remove link to '" + targetTitle + "'?", "Confirm Remove Link", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                contextNote.links.remove(selectedLink);
                core.saveNote(contextNote);
                loadLinks();
            }
        }
        public void loadRelatedNotes() {
            relatedNotesListModel.clear();
            if (contextNote != null && contextNote.getEmbeddingV1() != null && core.lm.isReady()) {
                CompletableFuture.supplyAsync(() -> core.findRelatedNotes(contextNote, 5, 0.65)) // Top 5, min similarity 0.65
                        .thenAcceptAsync(related -> related.forEach(relatedNotesListModel::addElement), SwingUtilities::invokeLater);
            }
        }
        @SuppressWarnings("unchecked")
        private void loadPlanDependencies() {
            var root = new DefaultMutableTreeNode("Plan Dependencies"); // Root node, usually hidden
            if (contextNote != null && contextNote.tags.contains(Netention.Note.SystemTag.GOAL_WITH_PLAN.value)) {
                try {
                    var graphContext = (Map<String, Object>) core.executeTool(Netention.Core.Tool.GET_PLAN_GRAPH_CONTEXT, Map.of(Netention.Planner.ToolParam.NOTE_ID.getKey(), contextNote.id));
                    if (graphContext != null && !graphContext.isEmpty()) {
                        // Main node for the current context note
                        var mainNodeData = new HashMap<String, Object>(graphContext);
                        mainNodeData.put(Netention.Note.NoteProperty.TITLE.getKey(), "Self: " + graphContext.get(Netention.Note.NoteProperty.TITLE.getKey())); // Prefix with "Self:"
                        var mainNode = new DefaultMutableTreeNode(mainNodeData);
                        root.add(mainNode);

                        // Add children
                        ((List<Map<String, Object>>) graphContext.getOrDefault("children", Collections.emptyList()))
                                .forEach(childData -> mainNode.add(new DefaultMutableTreeNode(childData)));

                        // Add parents (as siblings of mainNode under the root for typical tree view)
                        ((List<Map<String, Object>>) graphContext.getOrDefault("parents", Collections.emptyList()))
                                .forEach(parentData -> {
                                    var parentNodeData = new HashMap<String, Object>(parentData);
                                    parentNodeData.put(Netention.Note.NoteProperty.TITLE.getKey(), "Parent: " + parentData.get(Netention.Note.NoteProperty.TITLE.getKey()));
                                    root.insert(new DefaultMutableTreeNode(parentNodeData), 0); // Add parents at the top
                                });
                    }
                } catch (Exception e) {
                    logger.error("Failed to load plan dependencies graph for note {}: {}", contextNote.id, e.getMessage(), e);
                }
            }
            planDepsTreeModel.setRoot(root);
            planDepsTreeModel.reload(); // Ensure tree UI updates
            IntStream.range(0, planDepsTree.getRowCount()).forEach(planDepsTree::expandRow); // Expand all nodes
        }
        private void loadActionableItems(List<ActionableItem> items) {
            actionableItemsListModel.clear();
            if (items != null) items.forEach(actionableItemsListModel::addElement);

            // Visually indicate if there are items in the inbox tab
            var inboxTabIndex = tabbedPane.indexOfTab(Tab.INBOX.title);
            if (inboxTabIndex != -1) {
                tabbedPane.setForegroundAt(inboxTabIndex, (items != null && !items.isEmpty()) ? Color.ORANGE.darker() : UIManager.getColor("Label.foreground"));
            }
        }
        public void updateServiceDependentButtonStates() { /* e.g. enable/disable buttons based on LLM/Nostr status */ }
        public void displayLLMAnalysis() {
            if (contextNote == null) { llmAnalysisArea.setText(""); return; }
            var sb = new StringBuilder();
            ofNullable(contextNote.meta.get(Netention.Note.Metadata.LLM_SUMMARY.key)).ifPresent(s -> sb.append("Summary:\n").append(s).append("\n\n"));
            ofNullable(contextNote.meta.get(Netention.Note.Metadata.LLM_DECOMPOSITION.key)).ifPresent(d -> {
                if (d instanceof List list) {
                    sb.append("Task Decomposition:\n");
                    list.forEach(i -> sb.append("- ").append(i).append("\n"));
                    sb.append("\n");
                }
            });
            // You can add other LLM-generated metadata here
            llmAnalysisArea.setText(sb.toString().trim());
            llmAnalysisArea.setCaretPosition(0); // Scroll to top
        }
        private enum Tab {
            INFO("ℹ️ Info"), LINKS("🔗 Links"), RELATED("🤝 Related"), PLAN("🗺️ Plan"), PLAN_DEPS("🌳 Plan Deps"), INBOX("📥 Inbox");
            final String title; Tab(String title) { this.title = title; }
        }
        static class LinkListCellRenderer extends DefaultListCellRenderer { /* ... as in original ... */
            private final Netention.Core core;
            public LinkListCellRenderer(Netention.Core core) { this.core = core; }
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Netention.Link link) {
                    var targetTitle = core.notes.get(link.targetNoteId).map(Netention.Note::getTitle).orElse("Unknown Note");
                    if (targetTitle.length() > 25) targetTitle = targetTitle.substring(0, 22) + "...";
                    setText(String.format("%s → %s", link.relationType, targetTitle));
                }
                return this;
            }
        }
        static class NoteTitleListCellRenderer extends DefaultListCellRenderer { /* ... as in original ... */
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Netention.Note note) setText(note.getTitle());
                else if (value != null) setText(value.toString()); // Fallback for other object types
                return this;
            }
        }
        static class ActionableItemCellRenderer extends DefaultListCellRenderer { /* ... as in original ... */
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof ActionableItem item) setText("<html><b>" + item.type() + ":</b> " + item.description() + "</html>");
                return this;
            }
        }
        static class PlanStepsTableModel extends AbstractTableModel { /* ... as in original ... */
            private final String[] columnNames = {"Description", "Status", "Tool", "Result"};
            private List<Netention.Planner.PlanStep> steps = new ArrayList<>();
            public void setPlanExecution(Netention.Planner.PlanExecution exec) {
                this.steps = (exec != null) ? new ArrayList<>(exec.steps) : new ArrayList<>();
                fireTableDataChanged();
            }
            @Override public int getRowCount() { return steps.size(); }
            @Override public int getColumnCount() { return columnNames.length; }
            @Override public String getColumnName(int column) { return columnNames[column]; }
            @Override public Object getValueAt(int rowIndex, int columnIndex) {
                var step = steps.get(rowIndex);
                return switch (columnIndex) {
                    case 0 -> step.description;
                    case 1 -> step.status;
                    case 2 -> step.toolName;
                    case 3 -> ofNullable(step.result).map(String::valueOf).map(s -> s.substring(0, Math.min(s.length(), 50))).orElse("");
                    default -> null;
                };
            }
        }
    }
    public static class ConfigNoteEditorPanel extends JPanel { /* ... Full existing code, ensure it's static ... */
        private final Netention.Core core;
        private final Netention.Note configNote;
        private final Runnable onSaveCb;
        private final Map<Field, JComponent> fieldToComponentMap = new HashMap<>();
        private final Consumer<Boolean> onDirtyStateChange;
        private boolean userModifiedConfig = false;

        public ConfigNoteEditorPanel(Netention.Core core, Netention.Note configNote, Runnable onSaveCb, Consumer<Boolean> onDirtyStateChangeCallback) {
            super(new BorderLayout(10,10));
            setBorder(new EmptyBorder(10,10,10,10));
            this.core = core;
            this.configNote = configNote;
            this.onSaveCb = onSaveCb;
            this.onDirtyStateChange = onDirtyStateChangeCallback;

            var targetConfigObject = getTargetConfigObject(configNote.id);

            if (targetConfigObject == null && !"config.nostr_relays".equals(configNote.id)) {
                add(new JLabel("⚠️ Unknown configuration note type: " + configNote.id), BorderLayout.CENTER);
                return;
            }

            if ("config.nostr_relays".equals(configNote.id)) {
                add(buildNostrRelaysPanel(), BorderLayout.CENTER);
            } else {
                var formPanel = new JPanel(new GridBagLayout());
                var gbc = new GridBagConstraints();
                gbc.insets = new Insets(4,4,4,4);
                gbc.fill = GridBagConstraints.HORIZONTAL;
                gbc.anchor = GridBagConstraints.WEST;

                final var pubKeyLabelHolder = new JLabel[1]; // For dynamic update of npub

                var fields = Stream.of(targetConfigObject.getClass().getDeclaredFields())
                        .filter(f -> f.isAnnotationPresent(Netention.Field.class))
                        .toList();

                for (var i = 0; i < fields.size(); i++) {
                    var field = fields.get(i);
                    var cf = field.getAnnotation(Netention.Field.class);
                    var editorComp = createEditorComponent(field, targetConfigObject, cf, pubKeyLabelHolder);
                    fieldToComponentMap.put(field, editorComp);
                    UIUtil.addLabelAndComponent(formPanel, gbc, i, cf.label() + ":", editorComp);

                    // Special handling for Nostr private key to show public key and generate button
                    if (field.getName().equals("privateKeyBech32") && targetConfigObject instanceof Netention.Config.NostrSettings ns) {
                        gbc.gridy++; gbc.gridx = 1; // Pubkey label below private key field
                        formPanel.add(pubKeyLabelHolder[0], gbc);

                        gbc.gridy++; gbc.gridx = 1; gbc.anchor = GridBagConstraints.EAST; // Button to the right
                        formPanel.add(UIUtil.button("🔑", "Generate New Keys", evt -> {
                            if (JOptionPane.showConfirmDialog(formPanel, "Generate new Nostr keys & overwrite current ones? BACKUP EXISTING KEYS FIRST!", "Confirm Key Generation", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
                                core.cfg.generateNewNostrKeysAndUpdateConfig(); // This updates core.cfg.net directly
                                refreshFieldsFromConfig(); // Refresh UI from updated core.cfg.net
                                var kda = new JTextArea("nsec: " + ns.privateKeyBech32 + "\nnpub: " + ns.publicKeyBech32, 5, 50);
                                kda.setEditable(false); kda.setWrapStyleWord(true); kda.setLineWrap(true);
                                JOptionPane.showMessageDialog(formPanel, new JScrollPane(kda), "New Keys Generated (BACKUP THESE!)", JOptionPane.INFORMATION_MESSAGE);
                            }
                        }), gbc);
                        gbc.anchor = GridBagConstraints.WEST; // Reset anchor
                    }
                }
                gbc.gridy++; gbc.weighty = 1.0; // Add spacer to push everything up
                formPanel.add(new JPanel(), gbc); // Empty panel as spacer
                add(new JScrollPane(formPanel), BorderLayout.CENTER);
            }

            var bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            bottomPanel.add(UIUtil.button("💾", "Save Settings", e -> saveChanges()));
            add(bottomPanel, BorderLayout.SOUTH);

            refreshFieldsFromConfig(); // Populate with initial values
            userModifiedConfig = false; // Initial state is clean
            if (onDirtyStateChange != null) onDirtyStateChange.accept(false);
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

            fieldToComponentMap.forEach((field, component) -> {
                try {
                    field.setAccessible(true);
                    var value = field.get(targetConfigObject);
                    var cf = field.getAnnotation(Netention.Field.class);

                    // Determine the actual editor component (might be wrapped in JScrollPane)
                    var editor = (component instanceof JScrollPane) ? (JComponent)((JScrollPane)component).getViewport().getView() : component;

                    if (editor instanceof JTextArea ta) {
                        if (cf.type() == Netention.FieldType.TEXT_AREA && value instanceof List<?> listVal) {
                            ta.setText(String.join("\n", (List<String>) listVal));
                        } else {
                            ta.setText(value != null ? value.toString() : "");
                        }
                    } else if (editor instanceof JTextComponent tc) { // JTextField, JPasswordField
                        tc.setText(value != null ? value.toString() : "");
                    } else if (editor instanceof JComboBox<?> cb) {
                        cb.setSelectedItem(value != null ? value.toString() : null);
                    } else if (editor instanceof JCheckBox chkbx) {
                        chkbx.setSelected(value instanceof Boolean b && b);
                    }

                    // Update derived public key if this is the nostr_identity panel
                    if (field.getName().equals("privateKeyBech32") && targetConfigObject instanceof Netention.Config.NostrSettings ns) {
                        var pubKeyLabel = (JLabel) UIUtil.byName(this, "publicKeyBech32Label"); // Search within this panel
                        if (pubKeyLabel != null) pubKeyLabel.setText("Public Key (npub): " + ns.publicKeyBech32);
                    }

                } catch (IllegalAccessException e) {
                    logger.error("Error refreshing config field {}: {}", field.getName(), e.getMessage(), e);
                }
            });
            userModifiedConfig = false;
            if (onDirtyStateChange != null) onDirtyStateChange.accept(false);
        }

        private void saveUIToConfigObject(Object configObject) {
            if (configObject == null) return;
            fieldToComponentMap.forEach((field, component) -> {
                try {
                    field.setAccessible(true);
                    var cf = field.getAnnotation(Netention.Field.class);
                    var editor = (component instanceof JScrollPane) ? (JComponent)((JScrollPane)component).getViewport().getView() : component;

                    switch (editor) {
                        case JTextArea ta when cf.type() == Netention.FieldType.TEXT_AREA:
                            field.set(configObject, new ArrayList<>(List.of(ta.getText().split("\\n"))));
                            break;
                        case JPasswordField pf:
                            field.set(configObject, new String(pf.getPassword()));
                            break;
                        case JTextComponent tc: // JTextField
                            field.set(configObject, tc.getText());
                            break;
                        case JComboBox<?> cb:
                            field.set(configObject, cb.getSelectedItem());
                            break;
                        case JCheckBox chkbx:
                            field.set(configObject, chkbx.isSelected());
                            break;
                        default: // Should not happen
                            logger.warn("Unhandled component type for field {}: {}", field.getName(), editor.getClass().getName());
                            break;
                    }
                } catch (IllegalAccessException e) {
                    logger.error("Error saving config field {}: {}", field.getName(), e.getMessage(), e);
                }
            });
        }

        public void saveChanges() {
            var targetConfigObject = getTargetConfigObject(configNote.id);
            if (!"config.nostr_relays".equals(configNote.id) && targetConfigObject != null) {
                saveUIToConfigObject(targetConfigObject);
            }
            // For nostr_relays, changes are saved directly when editing list items.
            // Here, we just ensure the main configNote object (if it holds any direct data) is saved.
            // Typically, config notes are just pointers, actual config is in core.cfg objects.
            // core.saveNote(configNote); // Usually not needed as config notes are just IDs.

            core.cfg.saveAllConfigs(); // This saves the actual .properties files
            userModifiedConfig = false;
            if (onDirtyStateChange != null) onDirtyStateChange.accept(false);
            JOptionPane.showMessageDialog(this, "Settings saved.", "⚙️ Settings Saved", JOptionPane.INFORMATION_MESSAGE);
            if (onSaveCb != null) onSaveCb.run();
        }

        public boolean isUserModified() { return userModifiedConfig; }
        public Netention.Note getConfigNote() { return configNote; }

        private JComponent buildNostrRelaysPanel() {
            var panel = new JPanel(new BorderLayout(5,5));
            var listModel = new DefaultListModel<Netention.Note>();
            core.notes.getAll(n -> n.tags.contains(Netention.Note.SystemTag.NOSTR_RELAY.value))
                    .forEach(listModel::addElement);

            var relayList = new JList<>(listModel);
            relayList.setCellRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> list, Object value, int i, boolean sel, boolean foc) {
                    super.getListCellRendererComponent(list, value, i, sel, foc);
                    if (value instanceof Netention.Note n) {
                        setText(n.content.getOrDefault(Netention.Note.ContentKey.RELAY_URL.getKey(), "N/A") +
                                ((Boolean)n.content.getOrDefault(Netention.Note.ContentKey.RELAY_ENABLED.getKey(), true) ? "" : " (Disabled)"));
                    }
                    return this;
                }
            });
            panel.add(new JScrollPane(relayList), BorderLayout.CENTER);

            var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttonPanel.add(UIUtil.button("➕", "Add", e -> editRelayNote(null, listModel)));
            buttonPanel.add(UIUtil.button("✏️", "Edit", e -> ofNullable(relayList.getSelectedValue()).ifPresent(val -> editRelayNote(val, listModel))));
            buttonPanel.add(UIUtil.button("➖", "Remove", e -> ofNullable(relayList.getSelectedValue()).ifPresent(sel -> {
                if (JOptionPane.showConfirmDialog(panel, "Delete relay " + sel.content.get(Netention.Note.ContentKey.RELAY_URL.getKey()) + "?", "Confirm Delete", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    core.deleteNote(sel.id);
                    listModel.removeElement(sel);
                    // Trigger a config change event so Nostr service can reload relays
                    core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, "nostr_relays_updated");
                }
            })));
            panel.add(buttonPanel, BorderLayout.SOUTH);
            return panel;
        }

        private void editRelayNote(@Nullable Netention.Note relayNote, DefaultListModel<Netention.Note> listModel) {
            var isNew = relayNote == null;
            var urlField = new JTextField(isNew ? "wss://" : (String)relayNote.content.getOrDefault(Netention.Note.ContentKey.RELAY_URL.getKey(), "wss://"), 30);
            var enabledCheck = new JCheckBox("Enabled", isNew || (Boolean)relayNote.content.getOrDefault(Netention.Note.ContentKey.RELAY_ENABLED.getKey(), true));
            var readCheck = new JCheckBox("Read (Subscribe)", isNew || (Boolean)relayNote.content.getOrDefault(Netention.Note.ContentKey.RELAY_READ.getKey(), true));
            var writeCheck = new JCheckBox("Write (Publish)", isNew || (Boolean)relayNote.content.getOrDefault(Netention.Note.ContentKey.RELAY_WRITE.getKey(), true));

            var formPanel = new JPanel(new GridLayout(0, 1, 5, 5));
            formPanel.add(new JLabel("Relay URL:")); formPanel.add(urlField);
            formPanel.add(enabledCheck); formPanel.add(readCheck); formPanel.add(writeCheck);

            if (JOptionPane.showConfirmDialog(this, formPanel, (isNew ? "Add" : "Edit") + " Relay", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                var url = urlField.getText().trim();
                if (url.isEmpty() || !url.startsWith("ws://") && !url.startsWith("wss://")) {
                    JOptionPane.showMessageDialog(this, "Invalid relay URL. Must start with ws:// or wss://", "Invalid URL", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                var noteToSave = isNew ? new Netention.Note("Relay: " + url, "") : relayNote;
                if (isNew) noteToSave.tags.add(Netention.Note.SystemTag.NOSTR_RELAY.value);

                noteToSave.content.putAll(Map.of(
                        Netention.Note.ContentKey.RELAY_URL.getKey(), url,
                        Netention.Note.ContentKey.RELAY_ENABLED.getKey(), enabledCheck.isSelected(),
                        Netention.Note.ContentKey.RELAY_READ.getKey(), readCheck.isSelected(),
                        Netention.Note.ContentKey.RELAY_WRITE.getKey(), writeCheck.isSelected()
                ));
                core.saveNote(noteToSave);
                if (isNew) listModel.addElement(noteToSave);
                else listModel.setElementAt(noteToSave, listModel.indexOf(relayNote)); // Refresh item in list

                // Trigger a config change event so Nostr service can reload relays
                core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, "nostr_relays_updated");
            }
        }

        @SuppressWarnings("unchecked")
        private JComponent createEditorComponent(Field field, Object configObj, Netention.Field cf, JLabel[] pubKeyLabelHolder) {
            JComponent comp;
            try {
                field.setAccessible(true);
                var currentValue = field.get(configObj);
                // This listener marks the panel dirty and updates the config object in memory
                // Actual saving to disk happens via the "Save Settings" button.
                DocumentListener dirtyListener = new FieldUpdateListener(e -> {
                    if (!userModifiedConfig) {
                        userModifiedConfig = true;
                        if (onDirtyStateChange != null) onDirtyStateChange.accept(true);
                    }
                    // Update the field in the configObject in memory as user types (or on focus lost if preferred)
                    // This is complex for JComboBox/JCheckBox, better to do it on saveChanges() or via specific listeners.
                    // For now, rely on saveChanges() to pull all values.
                    // If dynamic updates are needed (like nsec -> npub), specific listeners are better.
                });
                ActionListener dirtyActionListener = e -> {
                    if (!userModifiedConfig) {
                        userModifiedConfig = true;
                        if (onDirtyStateChange != null) onDirtyStateChange.accept(true);
                    }
                };


                switch (cf.type()) {
                    case TEXT_AREA:
                        var ta = new JTextArea(3, 30);
                        if (currentValue instanceof List) ta.setText(String.join("\n", (List<String>)currentValue));
                        else if (currentValue != null) ta.setText(currentValue.toString());
                        ta.getDocument().addDocumentListener(dirtyListener);
                        comp = new JScrollPane(ta); // Wrap JTextArea in JScrollPane
                        break;
                    case COMBO_BOX:
                        var cb = new JComboBox<>(cf.choices());
                        if (currentValue != null) cb.setSelectedItem(currentValue.toString());
                        cb.addActionListener(dirtyActionListener);
                        comp = cb;
                        break;
                    case CHECK_BOX:
                        var chkbx = new JCheckBox();
                        if (currentValue instanceof Boolean) chkbx.setSelected((Boolean)currentValue);
                        chkbx.addActionListener(dirtyActionListener);
                        comp = chkbx;
                        break;
                    case PASSWORD_FIELD:
                        var pf = new JPasswordField(30);
                        if (currentValue != null) pf.setText(currentValue.toString());
                        pf.getDocument().addDocumentListener(dirtyListener);
                        comp = pf;
                        break;
                    default: // TEXT_FIELD
                        var tf = new JTextField(30);
                        if (currentValue != null) tf.setText(currentValue.toString());
                        tf.getDocument().addDocumentListener(dirtyListener);
                        comp = tf;
                        break;
                }

                // Special handling for Nostr private key: display public key and update it dynamically
                if (field.getName().equals("privateKeyBech32") && configObj instanceof Netention.Config.NostrSettings ns && comp instanceof JTextField privateKeyField) {
                    pubKeyLabelHolder[0] = new JLabel("Public Key (npub): " + ns.publicKeyBech32);
                    pubKeyLabelHolder[0].setName("publicKeyBech32Label"); // For lookup

                    privateKeyField.getDocument().addDocumentListener(new FieldUpdateListener(de -> {
                        try {
                            var pkNsec = privateKeyField.getText();
                            // Update the NostrSettings object in memory
                            ns.privateKeyBech32 = pkNsec;
                            ns.publicKeyBech32 = (pkNsec != null && !pkNsec.trim().isEmpty()) ?
                                    Crypto.Bech32.nip19Encode("npub", Crypto.getPublicKeyXOnly(Crypto.Bech32.nip19Decode(pkNsec))) :
                                    "Enter nsec to derive";
                        } catch (Exception ex) {
                            ns.publicKeyBech32 = "Invalid nsec format";
                        }
                        pubKeyLabelHolder[0].setText("Public Key (npub): " + ns.publicKeyBech32);
                        // Mark as dirty
                        if (!userModifiedConfig) {
                            userModifiedConfig = true;
                            if (onDirtyStateChange != null) onDirtyStateChange.accept(true);
                        }
                    }));
                }

                if (!cf.tooltip().isEmpty()) comp.setToolTipText(cf.tooltip());
                comp.setName(field.getName()); // For easier lookup if needed
                return comp;

            } catch (IllegalAccessException e) {
                logger.error("Error creating editor for field {}: {}", field.getName(), e.getMessage(), e);
                return new JLabel("Error: " + e.getMessage());
            }
        }
    }
    public static class StatusPanel extends JPanel { /* ... Full existing code, ensure it's static ... */
        private final JLabel label, nostrStatusLabel, llmStatusLabel, systemHealthLabel;
        private final Netention.Core core;

        public StatusPanel(Netention.Core core) {
            super(new FlowLayout(FlowLayout.LEFT));
            this.core = core;
            setBorder(new EmptyBorder(2,5,2,5)); // Small padding

            label = new JLabel("⏳ Initializing...");
            nostrStatusLabel = new JLabel();
            llmStatusLabel = new JLabel();
            systemHealthLabel = new JLabel(); // For system health metrics

            Stream.of(label, new JSeparator(SwingConstants.VERTICAL),
                            nostrStatusLabel, new JSeparator(SwingConstants.VERTICAL),
                            llmStatusLabel, new JSeparator(SwingConstants.VERTICAL),
                            systemHealthLabel)
                    .forEach(this::add);

            updateStatus("🚀 Application ready."); // Initial status

            // Listen to core events for status updates
            core.addCoreEventListener(e -> {
                if (e.type() == Netention.Core.CoreEventType.CONFIG_CHANGED || e.type() == Netention.Core.CoreEventType.STATUS_MESSAGE) {
                    // If it's a direct status message, use it, otherwise keep existing message part for label
                    var msg = (e.type() == Netention.Core.CoreEventType.STATUS_MESSAGE && e.data() instanceof String s) ? s : label.getText().replaceFirst(".*?: ", "").split(" \\| ")[0];
                    updateStatus(msg);
                }
            });

            // Timer to periodically update system health (e.g., every 15 seconds)
            new Timer(15000, e -> {
                try {
                    @SuppressWarnings("unchecked")
                    var m = (Map<String,Object>)core.executeTool(Netention.Core.Tool.GET_SYSTEM_HEALTH_METRICS, Collections.emptyMap());
                    systemHealthLabel.setText(String.format("🩺 Evts:%s Plans:%s Fails:%s",
                            m.getOrDefault("pendingSystemEvents", "?"),
                            m.getOrDefault("activePlans", "?"),
                            m.getOrDefault("failedPlanStepsInActivePlans", "?")));
                } catch (Exception ex) {
                    systemHealthLabel.setText("🩺 Health: Error");
                    logger.warn("Failed to get system health metrics", ex);
                }
            }) {{ setInitialDelay(1000); start(); }}; // Start after 1 sec, then repeat
        }

        public void updateStatus(String message) {
            SwingUtilities.invokeLater(() -> {
                label.setText("ℹ️ " + message);

                // Nostr Status
                nostrStatusLabel.setText("💜 Nostr: " + (core.net.isEnabled() ? (core.net.getConnectedRelayCount() + "/" + core.net.getConfiguredRelayCount() + " Relays") : "OFF"));
                nostrStatusLabel.setForeground(core.net.isEnabled() ? (core.net.getConnectedRelayCount() > 0 ? new Color(0,153,51) : Color.ORANGE.darker()) : Color.RED);

                // LLM Status
                llmStatusLabel.setText("💡 LLM: " + (core.lm.isReady() ? "READY" : "NOT READY"));
                llmStatusLabel.setForeground(core.lm.isReady() ? new Color(0,153,51) : Color.RED);
            });
        }
    }


    private record FieldUpdateListener(Consumer<DocumentEvent> consumer) implements DocumentListener {
        @Override public void insertUpdate(DocumentEvent e) { consumer.accept(e); }
        @Override public void removeUpdate(DocumentEvent e) { consumer.accept(e); }
        @Override public void changedUpdate(DocumentEvent e) { consumer.accept(e); }
    }

    private static class UIUtil {
        static JButton button(String emoji, String tooltip, Runnable listener) { return button(emoji, "", tooltip, (e)->listener.run()); }
        static JButton button(String emoji, String tooltip, ActionListener listener) { return button(emoji, "", tooltip, listener); }
        static JButton button(String textOrEmoji, String textIfEmojiOnly, String tooltip, ActionListener listener) {
            var button = new JButton(textOrEmoji + (textIfEmojiOnly.isEmpty() ? "" : " " + textIfEmojiOnly));
            if (tooltip != null && !tooltip.isEmpty()) button.setToolTipText(tooltip);
            if (listener != null) button.addActionListener(listener);
            return button;
        }
        static JMenuItem menuItem(String text, ActionListener listener) { return menuItem(text, null, listener, null); }
        static JMenuItem menuItem(String text, @Nullable String actionCommand, ActionListener listener) { return menuItem(text, actionCommand, listener, null); }
        static JMenuItem menuItem(String text, @Nullable String actionCommand, @Nullable ActionListener listener, @Nullable KeyStroke accelerator) {
            var item = new JMenuItem(text);
            if (actionCommand != null) item.setActionCommand(actionCommand);
            if (listener != null) item.addActionListener(listener);
            if (accelerator != null) item.setAccelerator(accelerator);
            return item;
        }
        static void addLabelAndComponent(JPanel panel, GridBagConstraints gbc, int y, String labelText, Component component) {
            gbc.gridx = 0; gbc.gridy = y; gbc.weightx = 0.0;
            panel.add(new JLabel(labelText), gbc);
            gbc.gridx = 1; gbc.gridy = y; gbc.weightx = 1.0;
            panel.add(component, gbc);
        }
        static Component byName(Container container, String name) { // Recursive search for component by name
            for (var comp : container.getComponents()) {
                if (name.equals(comp.getName())) return comp;
                if (comp instanceof Container subContainer) {
                    var found = byName(subContainer, name);
                    if (found != null) return found;
                }
            }
            return null;
        }
    }
}
