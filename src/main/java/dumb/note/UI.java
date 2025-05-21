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
        SwingUtilities.invokeLater(() -> {
            if (core.cfg.net.privateKeyBech32 == null || core.cfg.net.privateKeyBech32.isEmpty()) {
                logger.info("Nostr identity missing. Generating new identity...");
                var keysInfo = core.cfg.generateNewNostrKeysAndUpdateConfig();
                core.net.setEnabled(true);
                var keyArea = new JTextArea(keysInfo);
                keyArea.setEditable(false);
                keyArea.setWrapStyleWord(true);
                keyArea.setLineWrap(true);
                var scrollPane = new JScrollPane(keyArea);
                scrollPane.setPreferredSize(new Dimension(450, 150));
                JOptionPane.showMessageDialog(null, scrollPane, "🔑 New Nostr Identity Created - BACKUP YOUR nsec KEY!", JOptionPane.INFORMATION_MESSAGE);
            } else if (!core.net.isEnabled() && Arrays.asList(args).contains("App")) {
                core.net.setEnabled(true);
            }

            BaseAppFrame appInstance = new App(core);
            appInstance.setVisible(true);
        });
    }

    enum UIAction {
        NEW_NOTE, NEW_FROM_TEMPLATE, EXIT, SEPARATOR, CUT, COPY, PASTE,
        TOGGLE_INSPECTOR, TOGGLE_NAV_PANEL, SAVE_NOTE, PUBLISH_NOTE, SET_GOAL,
        LINK_NOTE, LLM_ACTIONS_MENU, DELETE_NOTE, TOGGLE_NOSTR, MY_PROFILE,
        PUBLISH_PROFILE, ADD_NOSTR_FRIEND, MANAGE_RELAYS, LLM_SETTINGS, SYNC_ALL, ABOUT,
        SHOW_MY_NOSTR_PROFILE_EDITOR, MANAGE_NOSTR_RELAYS_POPUP, CONFIGURE_NOSTR_IDENTITY_POPUP
    }

    public record ActionableItem(String id, String planNoteId, String description, String type, Object rawData,
                                 Runnable action) {
    }

    abstract static class BaseAppFrame extends JFrame {
        protected final Netention.Core core;
        protected final String baseTitle;
        protected final JLabel statusBarLabel;
        protected final JPanel defaultEditorHostPanel;
        protected final Map<UIAction, Consumer<ActionEvent>> actionHandlers = new EnumMap<>(UIAction.class);

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
            JPanel targetHost = getEditorHostPanel();
            targetHost.removeAll();
            if (panel != null) targetHost.add(panel, BorderLayout.CENTER);
            targetHost.revalidate();
            targetHost.repaint();
            updateFrameTitleWithDirtyState(false);
        }

        protected Optional<Netention.Note> getCurrentEditedNote() {
            JPanel editorHost = getEditorHostPanel();
            if (editorHost.getComponentCount() > 0) {
                Component comp = editorHost.getComponent(0);
                if (comp instanceof NoteEditorPanel nep) return Optional.ofNullable(nep.getCurrentNote());
                if (comp instanceof ConfigNoteEditorPanel cnep) return Optional.ofNullable(cnep.getConfigNote());
                if (comp instanceof ChatPanel cp) return Optional.ofNullable(cp.getChatNote());
            }
            return Optional.empty();
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
            if (core.net.isEnabled()) core.net.setEnabled(false);
            System.exit(0);
        }

        protected boolean canSwitchEditorContent(boolean switchingToNewUnsavedNote) {
            var editorHost = getEditorHostPanel();
            var currentEditorComp = editorHost.getComponentCount() > 0 ? editorHost.getComponent(0) : null;

            if (currentEditorComp instanceof NoteEditorPanel nep && nep.isUserModified()) {
                var currentDirtyNote = nep.getCurrentNote();
                var title = currentDirtyNote != null && currentDirtyNote.getTitle() != null && !currentDirtyNote.getTitle().isEmpty() ? currentDirtyNote.getTitle() : "Untitled";
                var result = JOptionPane.showConfirmDialog(this, "Note '" + title + "' has unsaved changes. Save them?", "❓ Unsaved Changes", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
                if (result == JOptionPane.YES_OPTION) {
                    nep.saveNote(false);
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

            var editorHost = getEditorHostPanel();
            if (editorHost.getComponentCount() > 0 && editorHost.getComponent(0) instanceof NoteEditorPanel nep) {
                return Optional.of(nep.contentPane);
            }
            return Optional.empty();
        }

        protected JMenuItem createMenuItem(String text, @Nullable UIAction actionEnum, @Nullable KeyStroke accelerator, @Nullable Consumer<ActionEvent> specificHandler) {
            var item = new JMenuItem(text);
            ofNullable(actionEnum).ifPresent(ae -> item.setActionCommand(ae.name()));

            var handler = ofNullable(specificHandler)
                    .orElseGet(() -> ofNullable(actionEnum).map(actionHandlers::get).orElse(null));

            if (handler != null) {
                item.addActionListener(handler::accept);
            } else if (actionEnum != null) {
                item.addActionListener(e -> logger.warn("Unhandled menu action: {} (no specific handler or map entry)", e.getActionCommand()));
            }

            ofNullable(accelerator).ifPresent(item::setAccelerator);
            return item;
        }

        protected void displayNoteInEditor(@Nullable Netention.Note note) {
            var nep = new NoteEditorPanel(core, note,
                    () -> {
                        JPanel host = getEditorHostPanel();
                        if (host.getComponentCount() > 0 && host.getComponent(0) instanceof NoteEditorPanel currentNep) {
                            Netention.Note savedNote = currentNep.getCurrentNote();
                            updateStatus(savedNote == null || savedNote.id == null ? "📝 Note created" : "💾 Note saved: " + savedNote.getTitle());
                        }
                    },
                    null,
                    this::updateFrameTitleWithDirtyState
            );
            setEditorComponent(nep);
        }
    }

    public static class App extends BaseAppFrame {
        final NavPanel navPanel;
        final JSplitPane mainSplitPane;
        final JSplitPane contentInspectorSplit;
        final InspectorPanel inspectorPanel;
        final StatusPanel statusPanel;
        private final JPanel editorPlaceholder;
        private final Map<String, ActionableItem> actionableItems = new ConcurrentHashMap<>();
        private TrayIcon trayIcon;
        private SystemTray tray;
        private Netention.Note currentlyDisplayedNoteForSwitchLogic = null;


        public App(Netention.Core core) {
            super(core, "Netention ✨", 1280, 800);

            remove(super.statusBarLabel);
            statusPanel = new StatusPanel(core);
            add(statusPanel, BorderLayout.SOUTH);

            editorPlaceholder = new JPanel(new BorderLayout());
            inspectorPanel = new InspectorPanel(core, this, this::display, this::getActionableItemsForNote);
            navPanel = new NavPanel(core, this, this::display, this::displayChatInEditor, this::displayConfigNoteInEditor, this::createNewNote, this::createNewNoteFromTemplate);

            contentInspectorSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, editorPlaceholder, inspectorPanel);
            contentInspectorSplit.setResizeWeight(0.70);
            contentInspectorSplit.setOneTouchExpandable(true);
            mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, navPanel, contentInspectorSplit);
            mainSplitPane.setDividerLocation(280);
            mainSplitPane.setOneTouchExpandable(true);

            remove(super.defaultEditorHostPanel);
            add(mainSplitPane, BorderLayout.CENTER);

            populateActionHandlers();
            setJMenuBar(createMenuBarFull());

            core.addCoreEventListener(this::handleCoreEvent);
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowIconified(WindowEvent e) {
                    if (core.cfg.ui.minimizeToTray && tray != null) setVisible(false);
                }
            });

            initSystemTray();
            displayNoteInEditor((Netention.Note) null);
            inspectorPanel.setVisible(false); // Start with inspector hidden
            contentInspectorSplit.setDividerLocation(1.0); // Ensure divider is all the way to hide inspector
        }

        @Override
        protected JPanel getEditorHostPanel() {
            return editorPlaceholder;
        }

        @Override
        protected void updateStatus(String message) {
            if (statusPanel != null) statusPanel.updateStatus(message);
            else super.updateStatus(message);
        }

        private void populateActionHandlers() {
            actionHandlers.put(UIAction.NEW_NOTE, e -> createNewNote());
            actionHandlers.put(UIAction.NEW_FROM_TEMPLATE, e -> {
                if (!canSwitchEditorContent(true)) return;
                var templates = core.notes.getAll(n -> n.tags.contains(Netention.Note.SystemTag.TEMPLATE.value));
                if (templates.isEmpty()) {
                    JOptionPane.showMessageDialog(this, "No templates found...", "🤷 No Templates", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                var selectedTemplate = (Netention.Note) JOptionPane.showInputDialog(this, "Select a template:", "📄 New from Template", JOptionPane.PLAIN_MESSAGE, null, templates.toArray(), templates.isEmpty() ? null : templates.getFirst());
                if (selectedTemplate != null) createNewNoteFromTemplate(selectedTemplate);
            });
            actionHandlers.put(UIAction.EXIT, e -> handleWindowClose());
            actionHandlers.put(UIAction.TOGGLE_INSPECTOR, e -> {
                var show = ((JCheckBoxMenuItem) e.getSource()).isSelected();
                inspectorPanel.setVisible(show);
                contentInspectorSplit.setDividerLocation(show ? contentInspectorSplit.getResizeWeight() : contentInspectorSplit.getWidth() - contentInspectorSplit.getDividerSize());
                contentInspectorSplit.revalidate();
            });
            actionHandlers.put(UIAction.TOGGLE_NAV_PANEL, e -> {
                var show = ((JCheckBoxMenuItem) e.getSource()).isSelected();
                navPanel.setVisible(show);
                mainSplitPane.setDividerLocation(show ? navPanel.getPreferredSize().width : 0);
                mainSplitPane.revalidate();
            });
            actionHandlers.put(UIAction.SAVE_NOTE, e -> {
                if (getEditorHostPanel().getComponent(0) instanceof NoteEditorPanel nep) nep.saveNote(false);
            });
            actionHandlers.put(UIAction.PUBLISH_NOTE, e -> {
                if (getEditorHostPanel().getComponent(0) instanceof NoteEditorPanel nep) nep.saveNote(true);
            });
            actionHandlers.put(UIAction.SET_GOAL, e -> {
                if (getEditorHostPanel().getComponent(0) instanceof NoteEditorPanel nep && nep.getCurrentNote() != null)
                    Stream.of(nep.toolBar.getComponents()).filter(c -> c instanceof JButton && "🎯".equals(((JButton) c).getText())).findFirst().map(JButton.class::cast).ifPresent(JButton::doClick);
            });
            actionHandlers.put(UIAction.DELETE_NOTE, e -> {
                if (getEditorHostPanel().getComponent(0) instanceof NoteEditorPanel nep && nep.getCurrentNote() != null) {
                    var noteToDelete = nep.getCurrentNote();
                    if (JOptionPane.showConfirmDialog(this, "🗑️ Delete note '" + noteToDelete.getTitle() + "'?", "Confirm Delete", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                        var isCurrentNote = noteToDelete.id != null && noteToDelete.id.equals(nep.getCurrentNote().id);
                        if (isCurrentNote && !canSwitchEditorContent(false)) return;
                        core.deleteNote(noteToDelete.id);
                        if (isCurrentNote) displayNoteInEditor((Netention.Note) null);
                    }
                }
            });
            actionHandlers.put(UIAction.MY_PROFILE, e -> {
                if (!canSwitchEditorContent(false)) return;
                ofNullable(core.cfg.net.myProfileNoteId).filter(id -> !id.isEmpty()).flatMap(core.notes::get)
                        .ifPresentOrElse(this::displayNoteInEditor,
                                () -> JOptionPane.showMessageDialog(this, "My Profile note ID not configured or note not found.", "👤 Profile Error", JOptionPane.ERROR_MESSAGE));
            });
            actionHandlers.put(UIAction.PUBLISH_PROFILE, e ->
                    ofNullable(core.cfg.net.myProfileNoteId).filter(id -> !id.isEmpty()).flatMap(core.notes::get).ifPresentOrElse(profileNote -> {
                        core.net.publishProfile(profileNote);
                        JOptionPane.showMessageDialog(this, "Profile publish request sent.", "🚀 Nostr Profile", JOptionPane.INFORMATION_MESSAGE);
                    }, () -> JOptionPane.showMessageDialog(this, "My Profile note ID not configured or note not found.", "👤 Profile Error", JOptionPane.ERROR_MESSAGE)));
            actionHandlers.put(UIAction.TOGGLE_NOSTR, e -> {
                var wantsEnable = ((JCheckBoxMenuItem) e.getSource()).isSelected();
                String statusMsg;
                if (wantsEnable) {
                    if (core.cfg.net.privateKeyBech32 == null || core.cfg.net.privateKeyBech32.isEmpty()) {
                        JOptionPane.showMessageDialog(this, "Nostr private key (nsec) not configured...", "Nostr Configuration Needed", JOptionPane.WARNING_MESSAGE);
                        statusMsg = "Nostr setup required.";
                        ((JCheckBoxMenuItem) e.getSource()).setSelected(false);
                    } else {
                        core.net.setEnabled(true);
                        statusMsg = core.net.isEnabled() ? "Nostr enabled." : "Nostr enabling failed.";
                        JOptionPane.showMessageDialog(this, statusMsg, "💜 Nostr Status", core.net.isEnabled() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE);
                        ((JCheckBoxMenuItem) e.getSource()).setSelected(core.net.isEnabled());
                    }
                } else {
                    core.net.setEnabled(false);
                    statusMsg = "Nostr disabled by user.";
                    JOptionPane.showMessageDialog(this, statusMsg, "💜 Nostr Status", JOptionPane.INFORMATION_MESSAGE);
                }
                statusPanel.updateStatus(statusMsg);
                core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, "nostr_status_changed");
            });
            actionHandlers.put(UIAction.ADD_NOSTR_FRIEND, e -> UIUtil.addNostrContactDialog(this, core, npub -> statusPanel.updateStatus("Friend " + npub.substring(0, 10) + "... added.")));
            actionHandlers.put(UIAction.MANAGE_RELAYS, e -> {
                if (!canSwitchEditorContent(false)) return;
                navPanel.selectViewAndNote(NavPanel.View.SETTINGS, "config.nostr_relays");
            });
            actionHandlers.put(UIAction.LLM_SETTINGS, e -> {
                if (!canSwitchEditorContent(false)) return;
                navPanel.selectViewAndNote(NavPanel.View.SETTINGS, "config.llm");
            });
            actionHandlers.put(UIAction.SYNC_ALL, e -> {
                core.fireCoreEvent(Netention.Core.CoreEventType.STATUS_MESSAGE, "🔄 Syncing all...");
                if (core.net.isEnabled()) core.net.requestSync();
                navPanel.refreshNotes();
                JOptionPane.showMessageDialog(this, "Synchronization requested.", "🔄 Sync All", JOptionPane.INFORMATION_MESSAGE);
            });
            actionHandlers.put(UIAction.ABOUT, e -> JOptionPane.showMessageDialog(this, "Netention ✨ (Full App)\nVersion: (dev)\nYour awesome note-taking and Nostr app!", "ℹ️ About Netention", JOptionPane.INFORMATION_MESSAGE));
        }

        public List<ActionableItem> getActionableItemsForNote(String noteId) {
            return actionableItems.values().stream()
                    .filter(item -> item.planNoteId() != null && item.planNoteId().equals(noteId) ||
                            "DISTRIBUTED_LM_RESULT".equals(item.type()) && item.rawData() instanceof Map && noteId.equals(((Map<?, ?>) item.rawData()).get("sourceNoteId")))
                    .toList();
        }

        @SuppressWarnings("unchecked")
        private void handleCoreEvent(Netention.Core.CoreEvent event) {
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater(() -> handleCoreEvent(event));
                return;
            }
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
                                if (choice == 0) {
                                    sourceNote.meta.put(Netention.Note.Metadata.LLM_SUMMARY.key.replace("summary", tool.toLowerCase()) + "_by_" + processedByNpub, result);
                                    core.saveNote(sourceNote);
                                    if (inspectorPanel.contextNote != null && inspectorPanel.contextNote.id.equals(sourceNoteId))
                                        inspectorPanel.setContextNote(sourceNote);
                                    statusPanel.updateStatus("Applied LM result from " + processedByNpub.substring(0, 10));
                                } else if (choice == 1) {
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
                case NOTE_UPDATED -> {
                    if (event.data() instanceof Netention.Note newNoteData) {
                        getCurrentEditedNote().ifPresent(noteBeingEdited -> {
                            if (noteBeingEdited.id != null && noteBeingEdited.id.equals(newNoteData.id)) {
                                var currentEditorComp = getEditorHostPanel().getComponent(0);
                                if (currentEditorComp instanceof NoteEditorPanel nep) {
                                    if (nep.isUserModified()) {
                                        logger.info("Note {} being edited was updated externally. User has local changes. Marking for potential refresh.", newNoteData.id);
                                        nep.setExternallyUpdated(true);
                                    } else {
                                        nep.populateFields(newNoteData);
                                        if (inspectorPanel.contextNote != null && inspectorPanel.contextNote.id.equals(newNoteData.id)) {
                                            inspectorPanel.setContextNote(newNoteData);
                                        }
                                    }
                                } else if (currentEditorComp instanceof ConfigNoteEditorPanel cnep) {
                                    if (cnep.isUserModified()) {
                                        logger.info("ConfigNote {} being edited was updated externally. User has local changes.", newNoteData.id);
                                    } else {
                                        cnep.refreshFieldsFromConfig();
                                        if (inspectorPanel.contextNote != null && inspectorPanel.contextNote.id.equals(newNoteData.id)) {
                                            inspectorPanel.setContextNote(newNoteData);
                                        }
                                    }
                                }
                            }
                        });
                    }
                    if (navPanel != null) navPanel.refreshNotes();
                }
                case CHAT_MESSAGE_ADDED, NOTE_ADDED, NOTE_DELETED -> {
                    if (navPanel != null) navPanel.refreshNotes();
                }
                case CONFIG_CHANGED -> {
                    navPanel.updateServiceDependentButtonStates();
                    var currentEditor = getEditorHostPanel().getComponentCount() > 0 ? getEditorHostPanel().getComponent(0) : null;
                    if (currentEditor instanceof NoteEditorPanel nep) nep.updateServiceDependentButtonStates();
                    else if (currentEditor instanceof ConfigNoteEditorPanel cnep) cnep.refreshFieldsFromConfig();
                    inspectorPanel.updateServiceDependentButtonStates();
                    statusPanel.updateStatus("⚙️ Configuration reloaded/changed.");
                    ofNullable(getJMenuBar()).map(JMenuBar::getComponents)
                            .flatMap(menus -> Arrays.stream(menus).filter(c -> c instanceof JMenu && "Nostr 💜".equals(((JMenu) c).getText())).findFirst()
                                    .flatMap(m -> Arrays.stream(((JMenu) m).getMenuComponents()).filter(mc -> mc instanceof JCheckBoxMenuItem && UIAction.TOGGLE_NOSTR.name().equals(((JCheckBoxMenuItem) mc).getActionCommand())).findFirst()))
                            .ifPresent(cbm -> ((JCheckBoxMenuItem) cbm).setSelected(core.net.isEnabled()));
                }
                default -> { /* No specific action for other events in this handler */ }
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
            Stream.of(new MenuItem("✨ Open Netention") {{
                          addActionListener(e -> restoreWindow());
                      }},
                    new MenuItem("➕ Quick Add Note") {{
                        addActionListener(e -> quickAddNoteFromTray());
                    }},
                    null,
                    new MenuItem("🚪 Exit") {{
                        addActionListener(e -> {
                            if (canSwitchEditorContent(false)) {
                                tray.remove(trayIcon);
                                if (core.net.isEnabled()) core.net.setEnabled(false);
                                System.exit(0);
                            }
                        });
                    }}
            ).forEach(item -> {
                if (item == null) trayMenu.addSeparator();
                else trayMenu.add(item);
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

        public void display(@Nullable Netention.Note noteToDisplay) {
            Optional<Netention.Note> currentEditorNoteOpt = getCurrentEditedNote();

            if (noteToDisplay != null && currentEditorNoteOpt.isPresent() && Objects.equals(noteToDisplay.id, currentEditorNoteOpt.get().id)) {
                var currentEditorComp = getEditorHostPanel().getComponent(0);
                if (currentEditorComp instanceof NoteEditorPanel || currentEditorComp instanceof ConfigNoteEditorPanel || currentEditorComp instanceof ChatPanel) {
                    inspectorPanel.setContextNote(noteToDisplay);
                    if (currentEditorNoteOpt.get() != noteToDisplay && currentEditorComp instanceof NoteEditorPanel nep && !nep.isUserModified()) {
                        nep.populateFields(noteToDisplay); // Refresh if instance changed and not dirty
                    } else if (currentEditorNoteOpt.get() != noteToDisplay && currentEditorComp instanceof ConfigNoteEditorPanel cnep && !cnep.isUserModified()) {
                        cnep.refreshFieldsFromConfig(); // Config notes might need different refresh
                    }
                    return;
                }
            }

            if (!canSwitchEditorContent(noteToDisplay == null || noteToDisplay.id == null)) return;

            if (noteToDisplay == null) {
                displayNoteInEditor((Netention.Note) null);
            } else if (noteToDisplay.tags.contains(Netention.Note.SystemTag.CHAT.value)) {
                displayChatInEditor(noteToDisplay);
            } else if (noteToDisplay.tags.contains(Netention.Note.SystemTag.CONFIG.value)) {
                displayConfigNoteInEditor(noteToDisplay);
            } else {
                displayNoteInEditor(noteToDisplay);
            }
            this.currentlyDisplayedNoteForSwitchLogic = noteToDisplay;
        }


        private void _setAndShowEditorContent(JComponent editorComponent, @Nullable Netention.Note contextNote) {
            setEditorComponent(editorComponent);
            inspectorPanel.setContextNote(contextNote);
            // Inspector visibility is now primarily user-controlled via menu
            // updateInspectorVisibility(contextNote);
        }

        public void displayNoteInEditor(@Nullable Netention.Note note) {
            var nep = new NoteEditorPanel(core, note, () -> {
                var editorPanel = (NoteEditorPanel) getEditorHostPanel().getComponent(0);
                var currentNoteInEditor = editorPanel.getCurrentNote();
                statusPanel.updateStatus(currentNoteInEditor == null || currentNoteInEditor.id == null ? "📝 Note created" : "💾 Note saved: " + currentNoteInEditor.getTitle());
                inspectorPanel.setContextNote(currentNoteInEditor);
            }, inspectorPanel, this::updateFrameTitleWithDirtyState);
            _setAndShowEditorContent(nep, note);
            this.currentlyDisplayedNoteForSwitchLogic = note;
        }

        public void createNewNote() {
            if (!canSwitchEditorContent(true)) return;
            displayNoteInEditor(new Netention.Note("Untitled", ""));
        }

        public void createNewNoteFromTemplate(Netention.Note templateNote) {
            if (!canSwitchEditorContent(true)) return;
            var newNote = new Netention.Note(templateNote.getTitle().replaceFirst("\\[Template\\]", "[New]").replaceFirst(Netention.Note.SystemTag.TEMPLATE.value, "").trim(), templateNote.getText());
            newNote.tags.addAll(templateNote.tags.stream().filter(t -> !t.equals(Netention.Note.SystemTag.TEMPLATE.value)).toList());
            if (Netention.ContentType.TEXT_HTML.equals(templateNote.getContentTypeEnum()))
                newNote.setHtmlText(templateNote.getText());
            core.saveNote(newNote);
            displayNoteInEditor(newNote);
        }

        public void displayChatInEditor(Netention.Note chatNote) {
            if (!chatNote.tags.contains(Netention.Note.SystemTag.CHAT.value)) {
                display(chatNote);
                return;
            }
            var partnerNpub = (String) chatNote.meta.get(Netention.Note.Metadata.NOSTR_PUB_KEY.key);
            if (partnerNpub == null) {
                JOptionPane.showMessageDialog(this, "Chat partner PK (npub) not found.", "💬 Chat Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            var chatPanel = new ChatPanel(core, chatNote, partnerNpub, statusPanel::updateStatus);
            _setAndShowEditorContent(chatPanel, chatNote);
            this.currentlyDisplayedNoteForSwitchLogic = chatNote;
        }

        public void displayConfigNoteInEditor(Netention.Note configNote) {
            if (!configNote.tags.contains(Netention.Note.SystemTag.CONFIG.value)) {
                display(configNote);
                return;
            }
            var cnep = new ConfigNoteEditorPanel(core, configNote, () -> {
                statusPanel.updateStatus("⚙️ Configuration potentially updated.");
                navPanel.updateServiceDependentButtonStates();
                core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, "config_note_saved");
            }, this::updateFrameTitleWithDirtyState);
            _setAndShowEditorContent(cnep, configNote);
            this.currentlyDisplayedNoteForSwitchLogic = configNote;
        }

        private void updateThemeAndRestartMessage(String themeName) {
            updateTheme(themeName);
            JOptionPane.showMessageDialog(this, "🎨 Theme changed to " + themeName + ". Some L&F changes may require restart.", "Theme Changed", JOptionPane.INFORMATION_MESSAGE);
        }

        private JMenuBar createMenuBarFull() {
            var mb = new JMenuBar();
            var fileMenu = new JMenu("File 📁");
            fileMenu.add(createMenuItem("➕ New Note", UIAction.NEW_NOTE, KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK), null));
            fileMenu.add(createMenuItem("📄 New Note from Template...", UIAction.NEW_FROM_TEMPLATE, KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), null));
            fileMenu.addSeparator();
            fileMenu.add(createMenuItem("🚪 Exit", UIAction.EXIT, null, null));
            mb.add(fileMenu);

            var editMenu = new JMenu("Edit ✏️");
            editMenu.add(UIUtil.menuItem("✂️ Cut", UIAction.CUT.name(), e -> getActiveTextComponent().ifPresent(JTextComponent::cut), KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK)));
            editMenu.add(UIUtil.menuItem("📋 Copy", UIAction.COPY.name(), e -> getActiveTextComponent().ifPresent(JTextComponent::copy), KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK)));
            editMenu.add(UIUtil.menuItem("📝 Paste", UIAction.PASTE.name(), e -> getActiveTextComponent().ifPresent(JTextComponent::paste), KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK)));
            mb.add(editMenu);

            mb.add(createViewMenu());

            var noteMenu = new JMenu("Note 📝");
            noteMenu.add(createMenuItem("💾 Save Note", UIAction.SAVE_NOTE, KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), null));
            noteMenu.add(createMenuItem("🚀 Publish Note (Nostr)", UIAction.PUBLISH_NOTE, KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK), null));
            noteMenu.add(createMenuItem("🎯 Convert to/Edit Goal", UIAction.SET_GOAL, null, null));
            noteMenu.add(createMenuItem("🔗 Link to Another Note...", UIAction.LINK_NOTE, null, null));
            noteMenu.add(createMenuItem("💡 LLM Actions...", UIAction.LLM_ACTIONS_MENU, null, null));
            noteMenu.addSeparator();
            noteMenu.add(createMenuItem("🗑️ Delete Note", UIAction.DELETE_NOTE, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), null));
            mb.add(noteMenu);

            var nostrMenu = new JMenu("Nostr 💜");
            nostrMenu.add(createNostrToggleMenuItem());
            nostrMenu.add(createMenuItem("👤 My Nostr Profile", UIAction.MY_PROFILE, null, null));
            nostrMenu.add(createMenuItem("🚀 Publish My Profile", UIAction.PUBLISH_PROFILE, null, null));
            nostrMenu.add(createMenuItem("➕ Add Nostr Contact...", UIAction.ADD_NOSTR_FRIEND, null, null));
            nostrMenu.add(createMenuItem("📡 Manage Relays...", UIAction.MANAGE_RELAYS, null, null));
            mb.add(nostrMenu);

            var toolsMenu = new JMenu("Tools 🛠️");
            toolsMenu.add(createMenuItem("💡 LLM Service Status/Settings", UIAction.LLM_SETTINGS, null, null));
            toolsMenu.add(createMenuItem("🔄 Synchronize/Refresh All", UIAction.SYNC_ALL, null, null));
            mb.add(toolsMenu);

            var helpMenu = new JMenu("Help ❓");
            helpMenu.add(createMenuItem("ℹ️ About Netention", UIAction.ABOUT, null, null));
            mb.add(helpMenu);

            return mb;
        }

        private JMenu createViewMenu() {
            var viewMenu = new JMenu("View 👁️");
            var toggleInspectorItem = new JCheckBoxMenuItem("Toggle Inspector Panel");
            toggleInspectorItem.setActionCommand(UIAction.TOGGLE_INSPECTOR.name());
            toggleInspectorItem.setSelected(inspectorPanel.isVisible());
            toggleInspectorItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
            ofNullable(actionHandlers.get(UIAction.TOGGLE_INSPECTOR)).ifPresent(e -> toggleInspectorItem.addActionListener(e::accept));
            viewMenu.add(toggleInspectorItem);

            var toggleNavPanelItem = new JCheckBoxMenuItem("Toggle Navigation Panel");
            toggleNavPanelItem.setActionCommand(UIAction.TOGGLE_NAV_PANEL.name());
            toggleNavPanelItem.setSelected(navPanel.isVisible());
            toggleNavPanelItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
            ofNullable(actionHandlers.get(UIAction.TOGGLE_NAV_PANEL)).ifPresent(e -> toggleNavPanelItem.addActionListener(e::accept));
            viewMenu.add(toggleNavPanelItem);
            viewMenu.addSeparator();

            var themesMenu = new JMenu("🎨 Themes");
            var themeGroup = new ButtonGroup();
            Stream.of("System Default", "Nimbus (Dark)").forEach(themeName -> {
                var themeItem = new JRadioButtonMenuItem(themeName, themeName.equals(core.cfg.ui.theme));
                themeItem.addActionListener(e -> {
                    core.cfg.ui.theme = themeName;
                    updateThemeAndRestartMessage(themeName);
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
            ofNullable(actionHandlers.get(UIAction.TOGGLE_NOSTR)).ifPresent(e -> toggleNostr.addActionListener(e::accept));
            return toggleNostr;
        }
    }

    public static class SimpleNote extends BaseAppFrame {
        private final SimpleNoteListPanel noteListPanel;
        private final JPanel editorPanelContainer;

        public SimpleNote(Netention.Core core) {
            super(core, "SimpleNote ✨", 800, 600);
            this.editorPanelContainer = new JPanel(new BorderLayout());

            noteListPanel = new SimpleNoteListPanel(core, this::displayNote);
            JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, noteListPanel, editorPanelContainer);
            splitPane.setDividerLocation(200);

            remove(super.defaultEditorHostPanel);
            add(splitPane, BorderLayout.CENTER);

            populateActionHandlers();
            setJMenuBar(createSimpleNoteMenuBar());
            core.addCoreEventListener(this::handleSimpleNoteCoreEvent);
            displayNote(null);
        }

        public static void main(String[] args) {
            SwingUtilities.invokeLater(() -> new SimpleNote(new Netention.Core()).setVisible(true));
        }

        @Override
        protected JPanel getEditorHostPanel() {
            return editorPanelContainer;
        }

        private void populateActionHandlers() {
            actionHandlers.put(UIAction.NEW_NOTE, e -> createNewNote());
            actionHandlers.put(UIAction.EXIT, e -> handleWindowClose());
            actionHandlers.put(UIAction.SAVE_NOTE, e -> {
                if (getEditorHostPanel().getComponent(0) instanceof NoteEditorPanel nep) nep.saveNote(false);
            });
            actionHandlers.put(UIAction.ABOUT, e ->
                    JOptionPane.showMessageDialog(this, "SimpleNote ✨\nA basic notes editor.", "About SimpleNote", JOptionPane.INFORMATION_MESSAGE)
            );
        }

        private void handleSimpleNoteCoreEvent(Netention.Core.CoreEvent event) {
            super.handleCoreEventBase(event);
            if (!SwingUtilities.isEventDispatchThread()) { // Ensure EDT for UI updates
                SwingUtilities.invokeLater(() -> handleSimpleNoteCoreEvent(event));
                return;
            }

            if (Set.of(Netention.Core.CoreEventType.NOTE_ADDED, Netention.Core.CoreEventType.NOTE_DELETED).contains(event.type())) {
                noteListPanel.refreshNotes();
            } else if (event.type() == Netention.Core.CoreEventType.NOTE_UPDATED && event.data() instanceof Netention.Note updatedNote) {
                getCurrentEditedNote().ifPresent(noteBeingEdited -> {
                    if (noteBeingEdited.id != null && noteBeingEdited.id.equals(updatedNote.id)) {
                        var editor = (NoteEditorPanel) getEditorHostPanel().getComponent(0);
                        if (editor.isUserModified()) {
                            editor.setExternallyUpdated(true);
                        } else {
                            editor.populateFields(updatedNote);
                        }
                    }
                });
                noteListPanel.refreshNotes();
            }
        }


        private void displayNote(@Nullable Netention.Note note) {
            if (note != null && (note.tags.contains(Netention.Note.SystemTag.CHAT.value) || note.tags.contains(Netention.Note.SystemTag.CONFIG.value))) {
                super.displayNoteInEditor(null);
                updateStatus("Selected note type not supported in SimpleNote.");
                return;
            }
            if (!canSwitchEditorContent(note == null || note.id == null)) return;
            super.displayNoteInEditor(note);
        }

        private void createNewNote() {
            if (!canSwitchEditorContent(true)) return;
            displayNote(new Netention.Note("Untitled", ""));
        }

        private JMenuBar createSimpleNoteMenuBar() {
            var mb = new JMenuBar();
            var fileMenu = new JMenu("File");
            fileMenu.add(createMenuItem("New Note", UIAction.NEW_NOTE, KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK), null));
            fileMenu.addSeparator();
            fileMenu.add(createMenuItem("Exit", UIAction.EXIT, null, null));
            mb.add(fileMenu);

            var editMenu = new JMenu("Edit");
            editMenu.add(UIUtil.menuItem("Cut", UIAction.CUT.name(), e -> getActiveTextComponent().ifPresent(JTextComponent::cut), KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK)));
            editMenu.add(UIUtil.menuItem("Copy", UIAction.COPY.name(), e -> getActiveTextComponent().ifPresent(JTextComponent::copy), KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK)));
            editMenu.add(UIUtil.menuItem("Paste", UIAction.PASTE.name(), e -> getActiveTextComponent().ifPresent(JTextComponent::paste), KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK)));
            mb.add(editMenu);

            var noteMenu = new JMenu("Note");
            noteMenu.add(createMenuItem("Save Note", UIAction.SAVE_NOTE, KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), null));
            mb.add(noteMenu);

            var helpMenu = new JMenu("Help");
            helpMenu.add(createMenuItem("About SimpleNote", UIAction.ABOUT, null, null));
            mb.add(helpMenu);
            return mb;
        }
    }

    public static class SimpleChat extends BaseAppFrame {
        private final BuddyListPanel buddyPanel;
        private final JPanel contentPanelContainer;
        private JCheckBoxMenuItem nostrToggleMenuItem;

        public SimpleChat(Netention.Core core) {
            super(core, "SimpleChat ✨", 900, 700);
            this.contentPanelContainer = new JPanel(new BorderLayout());

            buddyPanel = new BuddyListPanel(core, this::displayContentForIdentifier, this::showMyProfileEditor);

            JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buddyPanel, contentPanelContainer);
            splitPane.setDividerLocation(250);

            remove(super.defaultEditorHostPanel);
            add(splitPane, BorderLayout.CENTER);

            populateActionHandlers();
            setJMenuBar(createSimpleChatMenuBar());
            core.addCoreEventListener(this::handleSimpleChatCoreEvent);

            setEditorComponent(new JLabel("Select a chat or contact.", SwingConstants.CENTER));

            if (core.cfg.net.privateKeyBech32 != null && !core.cfg.net.privateKeyBech32.isEmpty()) {
                core.net.setEnabled(true);
            }
            updateNostrToggleState();
        }

        public static void main(String[] args) {
            SwingUtilities.invokeLater(() -> new SimpleChat(new Netention.Core()).setVisible(true));
        }

        @Override
        protected JPanel getEditorHostPanel() {
            return contentPanelContainer;
        }

        private void populateActionHandlers() {
            actionHandlers.put(UIAction.EXIT, e -> handleWindowClose());
            actionHandlers.put(UIAction.TOGGLE_NOSTR, e -> {
                var wantsEnable = nostrToggleMenuItem.isSelected();
                if (wantsEnable && (core.cfg.net.privateKeyBech32 == null || core.cfg.net.privateKeyBech32.isEmpty())) {
                    JOptionPane.showMessageDialog(this, "Nostr private key (nsec) not configured. Please configure it via Nostr -> Configure Identity.", "Nostr Configuration Needed", JOptionPane.WARNING_MESSAGE);
                    nostrToggleMenuItem.setSelected(false);
                } else {
                    core.net.setEnabled(wantsEnable);
                }
                updateNostrToggleState();
                core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, "nostr_status_changed_simple_chat");
            });
            actionHandlers.put(UIAction.SHOW_MY_NOSTR_PROFILE_EDITOR, e -> showMyProfileEditor());
            actionHandlers.put(UIAction.ADD_NOSTR_FRIEND, e -> UIUtil.addNostrContactDialog(this, core, npub -> buddyPanel.refreshList()));
            actionHandlers.put(UIAction.MANAGE_NOSTR_RELAYS_POPUP, e -> manageNostrRelays());
            actionHandlers.put(UIAction.CONFIGURE_NOSTR_IDENTITY_POPUP, e -> configureNostrIdentity());
            actionHandlers.put(UIAction.ABOUT, e ->
                    JOptionPane.showMessageDialog(this, "SimpleChat ✨\nA basic Nostr IM client.", "About SimpleChat", JOptionPane.INFORMATION_MESSAGE)
            );
        }

        private void handleSimpleChatCoreEvent(Netention.Core.CoreEvent event) {
            super.handleCoreEventBase(event);
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater(() -> handleSimpleChatCoreEvent(event));
                return;
            }
            switch (event.type()) {
                case CHAT_MESSAGE_ADDED, NOTE_ADDED, NOTE_UPDATED, NOTE_DELETED ->
                        SwingUtilities.invokeLater(buddyPanel::refreshList);
                case CONFIG_CHANGED -> {
                    updateNostrToggleState();
                    buddyPanel.refreshList();
                }
                default -> { /* No specific action */ }
            }
        }

        private void updateNostrToggleState() {
            if (nostrToggleMenuItem != null) {
                nostrToggleMenuItem.setSelected(core.net.isEnabled());
            }
            updateStatus(core.net.isEnabled() ? "Nostr Connected" : "Nostr Disconnected");
        }

        private void displayContentForIdentifier(String identifier) {
            if (!canSwitchEditorContent(false)) return;

            core.notes.get(identifier)
                    .filter(n -> n.tags.contains(Netention.Note.SystemTag.CHAT.value))
                    .ifPresentOrElse(chatNote -> {
                        var partnerNpub = (String) chatNote.meta.get(Netention.Note.Metadata.NOSTR_PUB_KEY.key);
                        if (partnerNpub != null) {
                            setEditorComponent(new ChatPanel(core, chatNote, partnerNpub, this::updateStatus));
                        } else {
                            setEditorComponent(new JLabel("Error: Chat partner not found.", SwingConstants.CENTER));
                            updateStatus("Error: Chat partner not found for " + chatNote.getTitle());
                        }
                    }, () -> {
                        var contactNpub = identifier;
                        if (!identifier.startsWith("npub1")) { // Assumes identifier is note ID for contact
                            core.notes.get(identifier)
                                    .filter(n -> n.tags.contains(Netention.Note.SystemTag.CONTACT.value))
                                    .ifPresent(this::showProfileForContact);
                            return;
                        }
                        // Identifier is an npub, find contact note by npub
                        core.notes.getAll(n -> n.tags.contains(Netention.Note.SystemTag.CONTACT.value) &&
                                        contactNpub.equals(n.meta.get(Netention.Note.Metadata.NOSTR_PUB_KEY.key)))
                                .stream().findFirst()
                                .ifPresentOrElse(
                                        this::showProfileForContact,
                                        () -> { // If no contact note, try to find chat note by this npub to show chat
                                            core.notes.getAll(cn -> cn.tags.contains(Netention.Note.SystemTag.CHAT.value) &&
                                                            contactNpub.equals(cn.meta.get(Netention.Note.Metadata.NOSTR_PUB_KEY.key)))
                                                    .stream().findFirst()
                                                    .ifPresentOrElse(chatNote -> displayContentForIdentifier(chatNote.id), // Recurse with chat note ID
                                                            () -> { // Truly not found
                                                                setEditorComponent(new JLabel("Contact profile/chat not found for: " + contactNpub.substring(0, 12) + "...", SwingConstants.CENTER));
                                                                updateStatus("Contact profile/chat not found for " + contactNpub.substring(0, 12) + "...");
                                                            });
                                        }
                                );
                    });
        }

        private void showProfileForContact(Netention.Note contactNote) {
            var profileEditor = new NoteEditorPanel(core, contactNote, () -> {
            }, null, dirty -> {
            });
            profileEditor.setReadOnlyMode(true);
            UIUtil.showPanelInDialog(this, "Profile: " + contactNote.getTitle(), profileEditor, new Dimension(400, 500), false);
            updateStatus("Viewing profile: " + contactNote.getTitle());
        }

        private void showMyProfileEditor() {
            if (!canSwitchEditorContent(false)) return;
            var myProfileNoteId = core.cfg.net.myProfileNoteId;
            if (myProfileNoteId == null || myProfileNoteId.isEmpty()) {
                if (JOptionPane.showConfirmDialog(this, "My Profile note ID not configured. Create one now?", "👤 Profile Setup", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    var newProfileNote = new Netention.Note("My Nostr Profile", "Bio: ...");
                    newProfileNote.tags.add(Netention.Note.SystemTag.MY_PROFILE.value);
                    core.saveNote(newProfileNote);
                    core.cfg.net.myProfileNoteId = newProfileNote.id;
                    core.cfg.saveAllConfigs();
                    myProfileNoteId = newProfileNote.id;
                    updateStatus("Created and set new profile note.");
                } else {
                    JOptionPane.showMessageDialog(this, "My Profile note ID not configured.", "👤 Profile Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }

            final String finalMyProfileNoteId = myProfileNoteId;
            core.notes.get(finalMyProfileNoteId).ifPresentOrElse(profileNote -> {
                var dialogContentPanel = new JPanel(new BorderLayout());
                NoteEditorPanel profileEditor = new NoteEditorPanel(core, profileNote,
                        null,
                        null,
                        isDirty -> {
                        }
                );
                profileEditor.onSaveCb = () -> {
                    updateStatus("Profile note saved.");
                    core.fireCoreEvent(Netention.Core.CoreEventType.NOTE_UPDATED, profileEditor.getCurrentNote());
                };
                dialogContentPanel.add(profileEditor, BorderLayout.CENTER);

                var publishButton = UIUtil.button("🚀 Publish Profile", null, e -> {
                    profileEditor.saveNote(false);
                    core.net.publishProfile(profileEditor.currentNote);
                    updateStatus("Profile publish request sent.");
                    JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(dialogContentPanel), "Profile publish request sent.", "🚀 Nostr Profile", JOptionPane.INFORMATION_MESSAGE);
                });
                publishButton.setEnabled(core.net.isEnabled());

                var bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
                bottomPanel.add(publishButton);
                dialogContentPanel.add(bottomPanel, BorderLayout.SOUTH);

                UIUtil.showEditablePanelInDialog(this, "Edit My Nostr Profile", dialogContentPanel, new Dimension(500, 600), true, x -> profileEditor.isUserModified());

            }, () -> JOptionPane.showMessageDialog(this, "My Profile note not found (ID: " + core.cfg.net.myProfileNoteId + ").", "👤 Profile Error", JOptionPane.ERROR_MESSAGE));
        }


        private void manageNostrRelays() {
            showConfigEditorDialog("config.nostr_relays", "Manage Nostr Relays");
        }

        private void configureNostrIdentity() {
            showConfigEditorDialog("config.nostr_identity", "Configure Nostr Identity");
        }

        private void showConfigEditorDialog(String configNoteId, String dialogTitle) {
            if (!canSwitchEditorContent(false)) return;

            core.notes.get(configNoteId).ifPresentOrElse(configNote -> {
                var configEditor = new ConfigNoteEditorPanel(core, configNote,
                        () -> {
                            updateStatus(dialogTitle + " saved.");
                            core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, configNoteId + "_updated");
                            if ("config.nostr_identity".equals(configNoteId)) buddyPanel.refreshList();
                        },
                        isDirty -> {
                        }
                );
                UIUtil.showEditablePanelInDialog(this, dialogTitle, configEditor, new Dimension(600, 400), true, x -> configEditor.isUserModified());
            }, () -> JOptionPane.showMessageDialog(this, "Configuration note '" + configNoteId + "' not found.", "Config Error", JOptionPane.ERROR_MESSAGE));
        }

        private JMenuBar createSimpleChatMenuBar() {
            var mb = new JMenuBar();
            var fileMenu = new JMenu("File");
            fileMenu.add(createMenuItem("Exit", UIAction.EXIT, null, null));
            mb.add(fileMenu);

            var nostrMenu = new JMenu("Nostr 💜");
            nostrToggleMenuItem = new JCheckBoxMenuItem("Enable Nostr Connection");
            nostrToggleMenuItem.setActionCommand(UIAction.TOGGLE_NOSTR.name());
            ofNullable(actionHandlers.get(UIAction.TOGGLE_NOSTR)).ifPresent(e -> nostrToggleMenuItem.addActionListener(e::accept));
            nostrMenu.add(nostrToggleMenuItem);
            nostrMenu.addSeparator();
            nostrMenu.add(createMenuItem("My Profile...", UIAction.SHOW_MY_NOSTR_PROFILE_EDITOR, null, null));
            nostrMenu.add(createMenuItem("Add Nostr Contact...", UIAction.ADD_NOSTR_FRIEND, null, null));
            nostrMenu.addSeparator();
            nostrMenu.add(createMenuItem("Manage Relays...", UIAction.MANAGE_NOSTR_RELAYS_POPUP, null, null));
            nostrMenu.add(createMenuItem("Configure Identity...", UIAction.CONFIGURE_NOSTR_IDENTITY_POPUP, null, null));
            mb.add(nostrMenu);

            var helpMenu = new JMenu("Help");
            helpMenu.add(createMenuItem("About SimpleChat", UIAction.ABOUT, null, null));
            mb.add(helpMenu);
            return mb;
        }
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
            topPanel.add(UIUtil.button("➕", "New Note", e -> onNoteSelected.accept(new Netention.Note("Untitled", ""))), BorderLayout.WEST);

            var searchBox = new JPanel(new BorderLayout(2, 0));
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
            }
        }
    }

    static class BuddyListPanel extends JPanel {
        private final Netention.Core core;
        private final DefaultListModel<Object> listModel = new DefaultListModel<>();
        private final JList<Object> buddyJList = new JList<>(listModel);

        public BuddyListPanel(Netention.Core core, Consumer<String> onIdentifierSelected, Runnable onShowMyProfile) {
            this.core = core;

            setLayout(new BorderLayout(5, 5));
            setBorder(new EmptyBorder(5, 5, 5, 5));

            buddyJList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            buddyJList.setCellRenderer(new BuddyListRenderer(core));
            buddyJList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting() && buddyJList.getSelectedValue() != null) {
                    var selected = buddyJList.getSelectedValue();
                    if (selected instanceof Netention.Note note) {
                        onIdentifierSelected.accept(note.id);
                    } else if (selected instanceof String npub) { // This case might be for npubs directly if added to list
                        onIdentifierSelected.accept(npub);
                    }
                }
            });
            buddyJList.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        var selected = buddyJList.getSelectedValue();
                        if (selected instanceof Netention.Note note) onIdentifierSelected.accept(note.id);
                        // else if (selected instanceof String npub) onIdentifierSelected.accept(npub);
                    }
                }
            });

            add(new JScrollPane(buddyJList), BorderLayout.CENTER);

            var buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
            buttonPanel.add(UIUtil.button("👤", "My Profile", onShowMyProfile));
            buttonPanel.add(UIUtil.button("➕🫂", "Add Contact", () ->
                    UIUtil.addNostrContactDialog(this, core, npub -> refreshList())
            ));
            add(buttonPanel, BorderLayout.NORTH);
            refreshList();
        }

        public void refreshList() {
            var selected = buddyJList.getSelectedValue();
            listModel.clear();

            core.notes.getAll(n -> n.tags.contains(Netention.Note.SystemTag.CHAT.value))
                    .stream()
                    .sorted(Comparator.comparing((Netention.Note n) -> n.updatedAt).reversed())
                    .forEach(listModel::addElement);

            core.notes.getAll(n -> n.tags.contains(Netention.Note.SystemTag.CONTACT.value) && n.tags.contains(Netention.Note.SystemTag.NOSTR_CONTACT.value))
                    .stream()
                    .filter(contactNote -> {
                        var contactNpub = (String) contactNote.meta.get(Netention.Note.Metadata.NOSTR_PUB_KEY.key);
                        if (contactNpub == null) return true; // Show contacts without npub if any
                        return !IntStream.range(0, listModel.getSize())
                                .mapToObj(listModel::getElementAt)
                                .filter(Netention.Note.class::isInstance)
                                .map(Netention.Note.class::cast)
                                .anyMatch(chatNote -> chatNote.tags.contains(Netention.Note.SystemTag.CHAT.value) &&
                                        contactNpub.equals(chatNote.meta.get(Netention.Note.Metadata.NOSTR_PUB_KEY.key)));
                    })
                    .sorted(Comparator.comparing(Netention.Note::getTitle))
                    .forEach(listModel::addElement);

            if (selected != null && listModel.contains(selected)) {
                buddyJList.setSelectedValue(selected, true);
            }
        }

        static class BuddyListRenderer extends DefaultListCellRenderer {
            private final Netention.Core core;

            public BuddyListRenderer(Netention.Core core) {
                this.core = core;
            }

            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Netention.Note note) {
                    if (note.tags.contains(Netention.Note.SystemTag.CHAT.value)) {
                        var partnerNpub = (String) note.meta.get(Netention.Note.Metadata.NOSTR_PUB_KEY.key);
                        var displayName = note.getTitle();
                        if (partnerNpub != null && (displayName.startsWith("Chat with") || displayName.isEmpty())) {
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
                    setText("❔ " + str);
                }
                return this;
            }
        }
    }

    public static class NavPanel extends JPanel {
        private final Netention.Core core;
        private final App uiRef;
        private final DefaultListModel<Netention.Note> listModel = new DefaultListModel<>();
        private final JList<Netention.Note> noteJList = new JList<>(listModel);
        private final JTextField searchField = new JTextField(15);
        private final JButton semanticSearchButton;
        private final JComboBox<View> viewSelector;
        private final JPanel tagFilterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        private final Set<String> activeTagFilters = new HashSet<>();

        public NavPanel(Netention.Core core, App uiRef, Consumer<Netention.Note> onShowNote, Consumer<Netention.Note> onShowChat, Consumer<Netention.Note> onShowConfigNote, Runnable onNewNote, Consumer<Netention.Note> onNewNoteFromTemplate) {
            this.core = core;
            this.uiRef = uiRef;
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

                    Optional<Netention.Note> currentEditorNoteOpt = uiRef.getCurrentEditedNote();
                    String currentEditorNoteId = currentEditorNoteOpt.map(n -> n.id).orElse(null);

                    if (Objects.equals(currentEditorNoteId, newlySelectedNoteInList.id)) {
                        if (currentEditorNoteOpt.isPresent() && currentEditorNoteOpt.get() != newlySelectedNoteInList) {
                            // Same ID, but different instance, possibly newer. Let display handle potential refresh.
                        } else {
                            return; // Same note ID, likely same instance, do nothing to avoid flicker.
                        }
                    }
                    uiRef.display(newlySelectedNoteInList);
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
            topControls.add(UIUtil.button("➕", "New Note", onNewNote::run));
            topControls.add(UIUtil.button("📄", "New from Template", e -> {
                var templates = core.notes.getAll(n -> n.tags.contains(Netention.Note.SystemTag.TEMPLATE.value));
                if (templates.isEmpty()) {
                    JOptionPane.showMessageDialog(this, "No templates found.", "🤷 No Templates", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                var selectedTemplate = (Netention.Note) JOptionPane.showInputDialog(this, "Select a template:", "📄 New from Template", JOptionPane.PLAIN_MESSAGE, null, templates.toArray(), templates.isEmpty() ? null : templates.getFirst());
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
            add(searchField, BorderLayout.CENTER);
            searchField.getDocument().addDocumentListener(new FieldUpdateListener(e -> refreshNotes()));
            semanticSearchButton = UIUtil.button("🧠", "AI Search", e -> performSemanticSearch());
            var combinedSearchPanel = new JPanel(new BorderLayout());
            combinedSearchPanel.add(searchPanel, BorderLayout.CENTER);
            combinedSearchPanel.add(semanticSearchButton, BorderLayout.EAST);
            var tagScrollPane = new JScrollPane(tagFilterPanel, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            tagScrollPane.setPreferredSize(new Dimension(200, 60));
            var topBox = Box.createVerticalBox();
            Stream.of(topControls, viewSelector, combinedSearchPanel, tagScrollPane).forEach(comp -> {
                comp.setAlignmentX(Component.LEFT_ALIGNMENT);
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
                        var notesWithEmb = core.notes.getAllNotes().stream().filter(n ->
                                !n.tags.contains(Netention.Note.SystemTag.CONFIG.value) &&
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
                    }, () -> JOptionPane.showMessageDialog(this, "Failed to generate embedding for query.", "LLM Error", JOptionPane.ERROR_MESSAGE)), SwingUtilities::invokeLater)
                    .exceptionally(ex -> {
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Error during semantic search: " + ex.getCause().getMessage(), "Search Error", JOptionPane.ERROR_MESSAGE));
                        return null;
                    });
        }

        public void selectViewAndNote(View view, String noteId) {
            viewSelector.setSelectedItem(view);
            SwingUtilities.invokeLater(() -> { // Ensure refreshNotes from viewSelector finishes
                core.notes.get(noteId).ifPresent(noteToSelect -> {
                    var index = listModel.indexOf(noteToSelect);
                    if (index >= 0) {
                        noteJList.setSelectedIndex(index);
                        noteJList.ensureIndexIsVisible(index);
                        // Explicitly call display if programmatic selection should force editor update
                        uiRef.display(noteToSelect);
                    } else {
                        logger.warn("Note {} not found in view {} after refresh", noteId, view);
                    }
                });
            });
        }

        @SuppressWarnings("unchecked")
        private void showNoteContextMenu(Netention.Note note, MouseEvent e) {
            var contextMenu = new JPopupMenu();
            if (note.tags.contains(Netention.Note.SystemTag.NOSTR_FEED.value) && core.lm.isReady() && core.net.isEnabled()) {
                var processMenu = new JMenu("💡 Process with My LM");
                processMenu.setEnabled(core.lm.isReady());
                Stream.of("Summarize", "Decompose Task", "Identify Concepts").forEach(tool ->
                        processMenu.add(UIUtil.menuItem(tool, ae -> processSharedNoteWithLM(note, tool)))
                );
                contextMenu.add(processMenu);
            }
            if (note.tags.contains(Netention.Note.SystemTag.SYSTEM_EVENT.value)) {
                var status = (String) note.content.getOrDefault(Netention.Note.ContentKey.STATUS.getKey(), "");
                if (Set.of("PROCESSED", "FAILED_PROCESSING", Netention.Planner.PlanState.FAILED.name()).contains(status)) {
                    contextMenu.add(UIUtil.menuItem("🗑️ Delete Processed/Failed Event", ae -> {
                        if (JOptionPane.showConfirmDialog(this, "Delete system event note '" + note.getTitle() + "'?", "Confirm Delete", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                            core.deleteNote(note.id);
                        }
                    }));
                }
            }
            if (!note.tags.contains(Netention.Note.SystemTag.CONFIG.value)) {
                contextMenu.add(UIUtil.menuItem("🗑️ Delete Note", ae -> {
                    if (JOptionPane.showConfirmDialog(this, "Delete note '" + note.getTitle() + "'?", "Confirm Delete", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                        Optional<Netention.Note> currentEditorNoteOpt = uiRef.getCurrentEditedNote();
                        boolean isCurrentNoteInEditor = currentEditorNoteOpt.map(editorNote -> editorNote.id != null && editorNote.id.equals(note.id)).orElse(false);

                        if (isCurrentNoteInEditor && !uiRef.canSwitchEditorContent(false)) return;

                        core.deleteNote(note.id);
                        if (isCurrentNoteInEditor) {
                            uiRef.display(null);
                        }
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
            var originalPublisherNpub = (String) sharedNote.meta.get(Netention.Note.Metadata.NOSTR_PUB_KEY.key);
            if (originalPublisherNpub == null) {
                JOptionPane.showMessageDialog(this, "Original publisher (npub) not found in note metadata.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            CompletableFuture.supplyAsync(() -> {
                        var contentToProcess = sharedNote.getContentForEmbedding();
                        return switch (toolName) {
                            case "Summarize" -> core.lm.summarize(contentToProcess);
                            case "Decompose Task" ->
                                    core.lm.decomposeTask(contentToProcess).map(list -> String.join("\n- ", list));
                            case "Identify Concepts" -> core.lm.chat("Identify key concepts in:\n\n" + contentToProcess);
                            default -> Optional.empty();
                        };
                    }).thenAcceptAsync(resultOpt -> resultOpt.ifPresent(result -> {
                        try {
                            var payload = Map.of(
                                    "type", "netention_lm_result",
                                    "sourceNoteId", sharedNote.id,
                                    "tool", toolName,
                                    "result", result,
                                    "processedByNpub", core.net.getPublicKeyBech32()
                            );
                            core.net.sendDirectMessage(originalPublisherNpub, core.json.writeValueAsString(payload));
                            JOptionPane.showMessageDialog(this, toolName + " result sent to " + originalPublisherNpub.substring(0, 10) + "...", "💡 LM Result Sent", JOptionPane.INFORMATION_MESSAGE);
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

        public void refreshNotes() {
            refreshNotes(null);
        }

        public void refreshNotes(List<Netention.Note> notesToDisplay) {
            var listSelectedNoteBeforeRefresh = noteJList.getSelectedValue();
            var listSelectedNoteIdBeforeRefresh = listSelectedNoteBeforeRefresh != null ? listSelectedNoteBeforeRefresh.id : null;

            listModel.clear();
            var finalFilter = getPredicate();

            var filteredNotes = (notesToDisplay != null ? notesToDisplay.stream().filter(finalFilter)
                    : core.notes.getAll(finalFilter).stream())
                    .sorted(Comparator.comparing((Netention.Note n) -> n.updatedAt).reversed())
                    .toList();

            filteredNotes.forEach(listModel::addElement);
            updateTagFilterPanel(filteredNotes);

            Netention.Note noteToReselect = null;
            Optional<Netention.Note> currentEditorNoteOpt = uiRef.getCurrentEditedNote();

            if (currentEditorNoteOpt.isPresent() && currentEditorNoteOpt.get().id != null) {
                final var editorNoteId = currentEditorNoteOpt.get().id;
                noteToReselect = filteredNotes.stream().filter(n -> editorNoteId.equals(n.id)).findFirst().orElse(null);
            }

            if (noteToReselect == null && listSelectedNoteIdBeforeRefresh != null) {
                final var finalSelectedIdBefore = listSelectedNoteIdBeforeRefresh;
                noteToReselect = filteredNotes.stream().filter(n -> finalSelectedIdBefore.equals(n.id)).findFirst().orElse(null);
            }

            if (noteToReselect != null) {
                noteJList.setSelectedValue(noteToReselect, true);
            } else if (!filteredNotes.isEmpty() && currentEditorNoteOpt.isEmpty()) {
                // If no note was selected or editor was empty, and list is not empty, select first
                // noteJList.setSelectedIndex(0); // This might be too aggressive
            } else if (currentEditorNoteOpt.isEmpty() && listSelectedNoteBeforeRefresh == null) {
                // No selection before, editor empty, list empty or no reselection candidate: do nothing
            }
        }

        private @NotNull Predicate<Netention.Note> getPredicate() {
            var viewFilter = ((View) Objects.requireNonNullElse(viewSelector.getSelectedItem(), View.NOTES)).getFilter();
            Predicate<Netention.Note> tagFilter = n -> activeTagFilters.isEmpty() || n.tags.containsAll(activeTagFilters);
            Predicate<Netention.Note> searchFilter = n -> {
                var term = searchField.getText().toLowerCase();
                if (term.isEmpty()) return true;
                return n.getTitle().toLowerCase().contains(term) ||
                        n.getText().toLowerCase().contains(term) ||
                        n.tags.stream().anyMatch(t -> t.toLowerCase().contains(term));
            };
            return viewFilter.and(tagFilter).and(searchFilter);
        }

        private void updateTagFilterPanel(List<Netention.Note> currentNotesInList) {
            tagFilterPanel.removeAll();
            var alwaysExclude = Stream.of(Netention.Note.SystemTag.values()).map(tag -> tag.value).collect(Collectors.toSet());

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
                        tagButton.addActionListener(e -> {
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
            INBOX("📥 Inbox", n -> n.tags.contains(Netention.Note.SystemTag.NOSTR_FEED.value) || n.tags.contains(Netention.Note.SystemTag.CHAT.value)),
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
                return Stream.of(Netention.Note.SystemTag.NOSTR_FEED, Netention.Note.SystemTag.CONFIG,
                                Netention.Note.SystemTag.SYSTEM_EVENT, Netention.Note.SystemTag.SYSTEM_PROCESS_HANDLER,
                                Netention.Note.SystemTag.NOSTR_RELAY, Netention.Note.SystemTag.SYSTEM_NOTE,
                                Netention.Note.SystemTag.CHAT
                        )
                        .map(tag -> tag.value)
                        .noneMatch(n.tags::contains);
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

    public static class NoteEditorPanel extends JPanel {
        final JTextField titleF = new JTextField(40);
        final JTextPane contentPane = new JTextPane();
        final JTextField tagsF = new JTextField(40);
        final JToolBar toolBar = new JToolBar();
        private final Netention.Core core;
        @Nullable
        private final InspectorPanel inspectorPanelRef;
        private final HTMLEditorKit htmlEditorKit = new HTMLEditorKit();
        private final JLabel embStatusL = new JLabel("Embedding: Unknown");
        private final List<DocumentListener> activeDocumentListeners = new ArrayList<>();
        private final Consumer<Boolean> onDirtyStateChange;
        public Runnable onSaveCb;
        public Netention.Note currentNote;
        private boolean userModified = false;
        private boolean readOnlyMode = false;
        private boolean externallyUpdated = false;


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
            gbc.gridx = 0;
            gbc.gridy = 2;
            gbc.gridwidth = 2;
            gbc.weighty = 1.0;
            gbc.fill = GridBagConstraints.BOTH;
            formP.add(new JScrollPane(contentPane), gbc);
            gbc.gridy = 3;
            gbc.weighty = 0.0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            formP.add(embStatusL, gbc);
            add(formP, BorderLayout.CENTER);
            populateFields(note);
        }

        public void setReadOnlyMode(boolean readOnly) {
            this.readOnlyMode = readOnly;
            if (currentNote != null) {
                var editable = !isEffectivelyReadOnly() && !currentNote.tags.contains(Netention.Note.SystemTag.MY_PROFILE.value);
                titleF.setEditable(editable);
                tagsF.setEditable(editable);
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
        }

        private void setupDocumentListeners() {
            clearDocumentListeners();
            var listener = new FieldUpdateListener(e -> {
                if (!userModified) {
                    userModified = true;
                    if (onDirtyStateChange != null) onDirtyStateChange.accept(true);
                    updateServiceDependentButtonStates();
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
            toolBar.add(UIUtil.button("🔄", "Refresh", "Refresh from Source (if changed externally)", e -> refreshFromSource()));
            toolBar.addSeparator();
            toolBar.add(UIUtil.button("🎯", "Set Goal", "Make this note a Goal/Plan", e -> setGoal()));
            toolBar.addSeparator();
            toolBar.add(getLlmMenu());
        }

        private @NotNull JButton styleButton(StyledEditorKit.StyledTextAction action) {
            var btn = new JButton(action);
            var name = action.toString();
            if (action instanceof StyledEditorKit.BoldAction) {
                name = "Bold";
                btn.setText("B");
            } else if (action instanceof StyledEditorKit.ItalicAction) {
                name = "Italic";
                btn.setText("I");
            } else if (action instanceof StyledEditorKit.UnderlineAction) {
                name = "Underline";
                btn.setText("U");
            } else {
                btn.setText(name.substring(0, 1));
            }
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
                } catch (Exception ex) {
                    logger.error("Failed to suggest plan steps: {}", ex.getMessage());
                }
            }
            saveNote(false);
            if (inspectorPanelRef != null) inspectorPanelRef.switchToPlanTab();
        }

        private JMenu getLlmMenu() {
            var llmMenu = new JMenu("💡 LLM");
            llmMenu.setToolTipText("LLM Actions for this note");
            Stream.of(LLMAction.values()).forEach(actionEnum -> llmMenu.add(
                    UIUtil.menuItem(
                            actionEnum.label,
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
            updateNoteFromFields();
            var actionCommand = LLMAction.valueOf(e.getActionCommand());
            var textContent = currentNote.getContentForEmbedding();
            var titleContent = currentNote.getTitle();

            CompletableFuture.runAsync(() -> {
                try {
                    switch (actionCommand) {
                        case EMBED -> core.lm.generateEmbedding(textContent).ifPresentOrElse(emb -> {
                            currentNote.setEmbeddingV1(emb);
                            core.saveNote(currentNote);
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

                        case ASK -> SwingUtilities.invokeLater(() -> {
                            var q = JOptionPane.showInputDialog(this, "Ask about note content:");
                            if (q != null && !q.trim().isEmpty()) {
                                CompletableFuture.supplyAsync(() -> core.lm.askAboutText(textContent, q))
                                        .thenAcceptAsync(answerOpt -> answerOpt.ifPresent(a ->
                                                        JOptionPane.showMessageDialog(this, a, "Answer", JOptionPane.INFORMATION_MESSAGE)),
                                                SwingUtilities::invokeLater);
                            }
                        });

                        case DECOMPOSE ->
                                core.lm.decomposeTask(titleContent.isEmpty() ? textContent : titleContent).ifPresent(d -> {
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
            }).thenRunAsync(() -> populateFields(this.currentNote), SwingUtilities::invokeLater);
        }

        private boolean isSystemReadOnly() {
            return currentNote != null && Stream.of(Netention.Note.SystemTag.NOSTR_FEED, Netention.Note.SystemTag.CONFIG,
                            Netention.Note.SystemTag.SYSTEM_PROCESS_HANDLER, Netention.Note.SystemTag.SYSTEM_EVENT,
                            Netention.Note.SystemTag.SYSTEM_NOTE, Netention.Note.SystemTag.NOSTR_RELAY)
                    .map(tag -> tag.value).anyMatch(currentNote.tags::contains);
        }

        private boolean isEffectivelyReadOnly() {
            return readOnlyMode || isSystemReadOnly();
        }

        public void populateFields(Netention.Note noteToDisplay) {
            this.currentNote = noteToDisplay;
            clearDocumentListeners(); // Important: do this before setting text to avoid self-triggering

            if (currentNote == null) {
                titleF.setText("");
                contentPane.setText("Select or create a note.");
                tagsF.setText("");
                Stream.of(titleF, contentPane, tagsF).forEach(c -> c.setEnabled(false));
                embStatusL.setText("No note loaded.");
            } else {
                titleF.setText(currentNote.getTitle());
                contentPane.setEditorKit(Netention.ContentType.TEXT_HTML.equals(currentNote.getContentTypeEnum()) ? htmlEditorKit : new StyledEditorKit());
                contentPane.setText(currentNote.getText()); // This might trigger listeners if not careful, but clearDocumentListeners() helps
                contentPane.setCaretPosition(0); // Reset caret after setting text
                tagsF.setText(String.join(", ", currentNote.tags));
                updateEmbeddingStatus();

                var editableFields = !isEffectivelyReadOnly() && !currentNote.tags.contains(Netention.Note.SystemTag.MY_PROFILE.value);
                var editableContent = !isEffectivelyReadOnly();

                titleF.setEditable(editableFields);
                tagsF.setEditable(editableFields);
                contentPane.setEditable(editableContent);

                Stream.of(titleF, contentPane, tagsF).forEach(c -> c.setEnabled(true));
            }
            userModified = false;
            setExternallyUpdated(false); // Reset this flag whenever fields are repopulated
            if (onDirtyStateChange != null) onDirtyStateChange.accept(false);
            setupDocumentListeners(); // Re-attach listeners after fields are set
            updateServiceDependentButtonStates();
        }


        public void updateNonTextParts(Netention.Note note) {
            this.currentNote = note; // Update reference
            updateEmbeddingStatus();
            if (!userModified) { // Only update tags if user hasn't modified them
                clearDocumentListeners(); // Temporarily remove to avoid triggering dirty state
                tagsF.setText(String.join(", ", currentNote.tags));
                setupDocumentListeners();
            }
            updateServiceDependentButtonStates();
        }


        public boolean isUserModified() {
            return userModified;
        }

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
                        if (tooltip.contains("Save Note"))
                            btn.setEnabled(editable && currentNote != null && (userModified || externallyUpdated)); // Enable save if dirty or if external changes can be "saved" by overwriting
                        else if (tooltip.contains("Set Goal"))
                            btn.setEnabled(editable && currentNote != null && inspectorPanelRef != null);
                        else if (tooltip.contains("Publish to Nostr"))
                            btn.setEnabled(editable && nostrReady && currentNote != null && !currentNote.tags.contains(Netention.Note.SystemTag.MY_PROFILE.value));
                        else if (tooltip.contains("Refresh from Source"))
                            btn.setEnabled(externallyUpdated && currentNote != null);
                    }
                } else if (comp instanceof JMenu menu && "💡 LLM".equals(menu.getText())) {
                    menu.setEnabled(editable && core.lm.isReady() && currentNote != null);
                }
            }
        }

        public Netention.Note getCurrentNote() {
            return currentNote;
        }

        public void updateNoteFromFields() {
            if (currentNote == null) currentNote = new Netention.Note();

            if (!currentNote.tags.contains(Netention.Note.SystemTag.MY_PROFILE.value) && !isEffectivelyReadOnly()) {
                currentNote.setTitle(titleF.getText());
                currentNote.tags.clear();
                Stream.of(tagsF.getText().split(","))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .forEach(currentNote.tags::add);
            }

            if (!isEffectivelyReadOnly()) {
                var d = contentPane.getDocument();
                if (Netention.ContentType.TEXT_HTML.getValue().equals(contentPane.getContentType())) {
                    var sw = new StringWriter();
                    try {
                        htmlEditorKit.write(sw, d, 0, d.getLength());
                        currentNote.setHtmlText(sw.toString());
                    } catch (IOException | BadLocationException e) {
                        logger.warn("Error writing HTML content, falling back to plain text: {}", e.getMessage());
                        currentNote.setText(contentPane.getText());
                    }
                } else {
                    try {
                        currentNote.setText(d.getText(0, d.getLength()));
                    } catch (BadLocationException e) {
                        logger.warn("Error getting plain text content: {}", e.getMessage());
                        currentNote.setText(e.toString());
                    }
                }
            }
        }

        public void saveNote(boolean andPublish) {
            if (currentNote != null && isEffectivelyReadOnly()) {
                JOptionPane.showMessageDialog(this, "Cannot save read-only items.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            updateNoteFromFields();
            var saved = core.saveNote(this.currentNote);
            if (saved != null) {
                this.currentNote = saved; // Use the saved instance which might have new ID or updated timestamps
                userModified = false;
                setExternallyUpdated(false); // Saved local changes, so external changes are now "merged" or overwritten
                if (onDirtyStateChange != null) onDirtyStateChange.accept(false);

                if (andPublish) {
                    if (!currentNote.tags.contains(Netention.Note.SystemTag.MY_PROFILE.value)) {
                        if (core.net.isEnabled()) {
                            core.net.publishNote(this.currentNote);
                        } else {
                            JOptionPane.showMessageDialog(this, "Nostr not enabled. Note saved locally.", "Nostr Disabled", JOptionPane.WARNING_MESSAGE);
                        }
                    }
                }
                if (onSaveCb != null) onSaveCb.run();
                populateFields(this.currentNote); // Repopulate to reflect the truly saved state
            } else {
                JOptionPane.showMessageDialog(this, "Failed to save note.", "Error", JOptionPane.ERROR_MESSAGE);
            }
        }

        public void setExternallyUpdated(boolean updated) {
            this.externallyUpdated = updated;
            updateServiceDependentButtonStates();
        }

        private void refreshFromSource() {
            if (currentNote == null || !externallyUpdated) return;

            Optional<Netention.Note> latestNoteOpt = core.notes.get(currentNote.id);
            if (latestNoteOpt.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Note no longer exists in store.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            Netention.Note latestNote = latestNoteOpt.get();

            if (userModified) {
                int choice = JOptionPane.showConfirmDialog(this,
                        "You have unsaved local changes. Discard your changes and refresh from the latest version?",
                        "Confirm Refresh", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (choice == JOptionPane.NO_OPTION) {
                    return;
                }
            }
            populateFields(latestNote);
            // populateFields already sets userModified = false and externallyUpdated = false
            if (inspectorPanelRef != null) inspectorPanelRef.setContextNote(latestNote);
        }


        private enum LLMAction {
            EMBED("Embed"),
            SUMMARIZE("Summarize"),
            ASK("Ask Question"),
            DECOMPOSE("Decompose Task"),
            SUGGEST_PLAN("Suggest Plan");

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
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentHidden(ComponentEvent e) {
                    core.removeCoreEventListener(coreEventListener);
                }

                @Override
                public void componentShown(ComponentEvent e) {
                    core.removeCoreEventListener(coreEventListener); // remove just in case it's duplicated
                    core.addCoreEventListener(coreEventListener);
                    loadMessages(); // Refresh when shown
                }
            });
        }

        private void handleChatPanelCoreEvent(Netention.Core.CoreEvent event) {
            if (event.type() == Netention.Core.CoreEventType.CHAT_MESSAGE_ADDED &&
                    event.data() instanceof Map data &&
                    note.id.equals(data.get("chatNoteId"))) {
                SwingUtilities.invokeLater(this::loadMessages);
            }
        }

        @SuppressWarnings("unchecked")
        private void loadMessages() {
            chatArea.setText("");
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
            if (senderNpub == null || t == null || tsStr == null) return;

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
            core.net.sendDirectMessage(partnerNpub, txt);
            core.notes.get(this.note.id).ifPresent(currentChatNote -> {
                var entry = Map.of(
                        "sender", core.net.getPublicKeyBech32(),
                        "timestamp", Instant.now().toString(),
                        "text", txt
                );
                @SuppressWarnings("unchecked")
                var msgs = (List<Map<String, String>>) currentChatNote.content.computeIfAbsent(Netention.Note.ContentKey.MESSAGES.getKey(), k -> new ArrayList<Map<String, String>>());
                msgs.add(entry);
                core.saveNote(currentChatNote); // This will trigger CHAT_MESSAGE_ADDED -> loadMessages
                messageInput.setText("");
                statusUpdater.accept("Message sent to " + partnerNpub.substring(0, 10) + "...");
            });
        }

        private void scrollToBottom() {
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
        }

        public Netention.Note getChatNote() {
            return note;
        }
    }

    public static class InspectorPanel extends JPanel {
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
        private final App uiRef;
        Netention.Note contextNote;

        public InspectorPanel(Netention.Core core, App uiRef, Consumer<Netention.Note> noteDisplayCallback, Function<String, List<ActionableItem>> actionableItemsProvider) {
            super(new BorderLayout(5, 5));
            this.core = core;
            this.uiRef = uiRef;
            setPreferredSize(new Dimension(350, 0));
            tabbedPane = new JTabbedPane();

            var infoPanel = new JPanel(new BorderLayout());
            noteInfoLabel = new JLabel("No note selected.");
            noteInfoLabel.setBorder(new EmptyBorder(5, 5, 5, 5));
            copyPubKeyButton = UIUtil.button("📋", "Copy PubKey", e -> {
                if (contextNote != null && contextNote.meta.get(Netention.Note.Metadata.NOSTR_PUB_KEY.key) instanceof String npub) {
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(npub), null);
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
            llmAnalysisArea.setBackground(UIManager.getColor("TextArea.background") != null ? UIManager.getColor("TextArea.background").darker() : Color.LIGHT_GRAY);
            infoPanel.add(new JScrollPane(llmAnalysisArea), BorderLayout.CENTER);
            tabbedPane.addTab(Tab.INFO.title, infoPanel);

            var linksPanel = new JPanel(new BorderLayout());
            linksJList = new JList<>(linksListModel);
            linksJList.setCellRenderer(new LinkListCellRenderer(core));
            linksJList.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent evt) {
                    if (evt.getClickCount() == 2) {
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

            var relatedNotesPanel = new JPanel(new BorderLayout());
            relatedNotesJList = new JList<>(relatedNotesListModel);
            relatedNotesJList.setCellRenderer(new NoteTitleListCellRenderer());
            relatedNotesJList.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent evt) {
                    if (evt.getClickCount() == 2) {
                        ofNullable(relatedNotesJList.getSelectedValue()).ifPresent(noteDisplayCallback);
                    }
                }
            });
            relatedNotesPanel.add(new JScrollPane(relatedNotesJList), BorderLayout.CENTER);
            tabbedPane.addTab(Tab.RELATED.title, relatedNotesPanel);

            planPanel = new JPanel(new BorderLayout());
            planStepsTableModel = new PlanStepsTableModel();
            var planStepsTable = new JTable(planStepsTableModel);
            planStepsTable.getColumnModel().getColumn(0).setPreferredWidth(150);
            planStepsTable.getColumnModel().getColumn(1).setPreferredWidth(50);
            planPanel.add(new JScrollPane(planStepsTable), BorderLayout.CENTER);
            planPanel.add(UIUtil.button("▶️", "Execute/Update Plan", e -> {
                if (contextNote != null) {
                    if (!contextNote.tags.contains(Netention.Note.SystemTag.GOAL_WITH_PLAN.value)) {
                        contextNote.tags.add(Netention.Note.SystemTag.GOAL_WITH_PLAN.value);
                    }
                    core.saveNote(contextNote);
                    core.planner.executePlan(contextNote);
                }
            }), BorderLayout.SOUTH);
            tabbedPane.addTab(Tab.PLAN.title, planPanel);

            var planDependenciesPanel = new JPanel(new BorderLayout());
            planDepsTreeModel = new DefaultTreeModel(new DefaultMutableTreeNode("Plan Dependencies"));
            planDepsTree = new JTree(planDepsTreeModel);
            planDepsTree.setRootVisible(false);
            planDepsTree.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        var node = (DefaultMutableTreeNode) planDepsTree.getLastSelectedPathComponent();
                        if (node != null && node.getUserObject() instanceof Map nodeData && nodeData.containsKey(Netention.Note.NoteProperty.ID.getKey())) {
                            core.notes.get((String) nodeData.get(Netention.Note.NoteProperty.ID.getKey())).ifPresent(noteDisplayCallback);
                        }
                    }
                }
            });
            planDependenciesPanel.add(new JScrollPane(planDepsTree), BorderLayout.CENTER);
            tabbedPane.addTab(Tab.PLAN_DEPS.title, planDependenciesPanel);

            actionableItemsPanel = new JPanel(new BorderLayout());
            actionableItemsJList = new JList<>(actionableItemsListModel);
            actionableItemsJList.setCellRenderer(new ActionableItemCellRenderer());
            actionableItemsJList.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        ofNullable(actionableItemsJList.getSelectedValue())
                                .filter(item -> item.action() != null)
                                .ifPresent(item -> item.action().run());
                    }
                }
            });
            actionableItemsPanel.add(new JScrollPane(actionableItemsJList), BorderLayout.CENTER);
            tabbedPane.addTab(Tab.INBOX.title, actionableItemsPanel);

            add(tabbedPane, BorderLayout.CENTER);

            core.addCoreEventListener(event -> {
                if (!SwingUtilities.isEventDispatchThread())
                    SwingUtilities.invokeLater(() -> handleCoreEvent(event, actionableItemsProvider));
                else handleCoreEvent(event, actionableItemsProvider);
            });
        }

        private void handleCoreEvent(Netention.Core.CoreEvent event, Function<String, List<ActionableItem>> actionableItemsProvider) {
            switch (event.type()) {
                case PLAN_UPDATED:
                    if (event.data() instanceof Netention.Planner.PlanExecution exec && contextNote != null && contextNote.id.equals(exec.planNoteId)) {
                        planStepsTableModel.setPlanExecution(exec);
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
                core.planner.getPlanExecution(contextNote.id)
                        .ifPresentOrElse(planStepsTableModel::setPlanExecution,
                                () -> planStepsTableModel.setPlanExecution(null));
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
                        DateTimeFormatter.ISO_INSTANT.format(note.updatedAt.atZone(ZoneId.systemDefault())).substring(0, 19),
                        pubKeyInfo));

                displayLLMAnalysis();
                loadLinks();
                loadRelatedNotes();
                loadPlanDependencies();
                core.planner.getPlanExecution(note.id)
                        .ifPresentOrElse(planStepsTableModel::setPlanExecution,
                                () -> planStepsTableModel.setPlanExecution(null));
                loadActionableItems(uiRef.getActionableItemsForNote(note.id));
            } else {
                noteInfoLabel.setText("No note selected.");
                llmAnalysisArea.setText("");
                copyPubKeyButton.setVisible(false);
                linksListModel.clear();
                relatedNotesListModel.clear();
                planDepsTreeModel.setRoot(new DefaultMutableTreeNode("Plan Dependencies"));
                planStepsTableModel.setPlanExecution(null);
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
                    .filter(n -> !n.id.equals(contextNote.id))
                    .toList();
            if (allNotes.isEmpty()) {
                JOptionPane.showMessageDialog(this, "No other notes to link to.", "Add Link", JOptionPane.INFORMATION_MESSAGE);
                return;
            }

            var noteSelector = new JComboBox<>(allNotes.toArray(new Netention.Note[0]));
            noteSelector.setRenderer(new NoteTitleListCellRenderer());

            var relationTypes = new String[]{"relates_to", "supports", "elaborates", "depends_on", "plan_subgoal_of", "plan_depends_on"};
            var relationTypeSelector = new JComboBox<>(relationTypes);
            if (contextNote.tags.contains(Netention.Note.SystemTag.GOAL_WITH_PLAN.value)) {
                relationTypeSelector.setSelectedItem("plan_subgoal_of");
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
                    core.saveNote(contextNote);
                    loadLinks();
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
                CompletableFuture.supplyAsync(() -> core.findRelatedNotes(contextNote, 5, 0.65))
                        .thenAcceptAsync(related -> related.forEach(relatedNotesListModel::addElement), SwingUtilities::invokeLater);
            }
        }

        @SuppressWarnings("unchecked")
        private void loadPlanDependencies() {
            var root = new DefaultMutableTreeNode("Plan Dependencies");
            if (contextNote != null && contextNote.tags.contains(Netention.Note.SystemTag.GOAL_WITH_PLAN.value)) {
                try {
                    var graphContext = (Map<String, Object>) core.executeTool(Netention.Core.Tool.GET_PLAN_GRAPH_CONTEXT, Map.of(Netention.Planner.ToolParam.NOTE_ID.getKey(), contextNote.id));
                    if (graphContext != null && !graphContext.isEmpty()) {
                        var mainNodeData = new HashMap<>(graphContext);
                        mainNodeData.put(Netention.Note.NoteProperty.TITLE.getKey(), "Self: " + graphContext.get(Netention.Note.NoteProperty.TITLE.getKey()));
                        var mainNode = new DefaultMutableTreeNode(mainNodeData);
                        root.add(mainNode);

                        ((List<Map<String, Object>>) graphContext.getOrDefault("children", Collections.emptyList()))
                                .forEach(childData -> mainNode.add(new DefaultMutableTreeNode(childData)));

                        ((List<Map<String, Object>>) graphContext.getOrDefault("parents", Collections.emptyList()))
                                .forEach(parentData -> {
                                    var parentNodeData = new HashMap<>(parentData);
                                    parentNodeData.put(Netention.Note.NoteProperty.TITLE.getKey(), "Parent: " + parentData.get(Netention.Note.NoteProperty.TITLE.getKey()));
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

            var inboxTabIndex = tabbedPane.indexOfTab(Tab.INBOX.title);
            if (inboxTabIndex != -1) {
                var hasItems = items != null && !items.isEmpty();
                tabbedPane.setForegroundAt(inboxTabIndex, hasItems ? Color.ORANGE.darker() : UIManager.getColor("Label.foreground"));
                if (hasItems && tabbedPane.getSelectedIndex() != inboxTabIndex) {
                    tabbedPane.setTitleAt(inboxTabIndex, Tab.INBOX.title + " (" + items.size() + ")");
                } else {
                    tabbedPane.setTitleAt(inboxTabIndex, Tab.INBOX.title);
                }
            }
        }

        public void updateServiceDependentButtonStates() {
        }

        public void displayLLMAnalysis() {
            if (contextNote == null) {
                llmAnalysisArea.setText("");
                return;
            }
            var sb = new StringBuilder();
            ofNullable(contextNote.meta.get(Netention.Note.Metadata.LLM_SUMMARY.key)).ifPresent(s -> sb.append("Summary:\n").append(s).append("\n\n"));
            ofNullable(contextNote.meta.get(Netention.Note.Metadata.LLM_DECOMPOSITION.key)).ifPresent(d -> {
                if (d instanceof List list) {
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

        static class ActionableItemCellRenderer extends DefaultListCellRenderer {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof ActionableItem item) {
                    setText("<html><b>" + item.type().replace("_", " ") + ":</b> " + item.description() + "</html>");
                }
                return this;
            }
        }

        static class PlanStepsTableModel extends AbstractTableModel {
            private final String[] columnNames = {"Description", "Status", "Tool", "Result"};
            private List<Netention.Planner.PlanStep> steps = new ArrayList<>();

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
                    case 3 ->
                            ofNullable(step.result).map(String::valueOf).map(s -> s.substring(0, Math.min(s.length(), 50)) + (s.length() > 50 ? "..." : "")).orElse("");
                    default -> null;
                };
            }
        }
    }

    public static class ConfigNoteEditorPanel extends JPanel {
        private final Netention.Core core;
        private final Netention.Note configNote;
        private final Runnable onSaveCb;
        private final Map<Field, JComponent> fieldToComponentMap = new HashMap<>();
        private final Consumer<Boolean> onDirtyStateChange;
        private boolean userModifiedConfig = false;

        public ConfigNoteEditorPanel(Netention.Core core, Netention.Note configNote, Runnable onSaveCb, Consumer<Boolean> onDirtyStateChangeCallback) {
            super(new BorderLayout(10, 10));
            setBorder(new EmptyBorder(10, 10, 10, 10));
            this.core = core;
            this.configNote = configNote;
            this.onSaveCb = onSaveCb;
            this.onDirtyStateChange = onDirtyStateChangeCallback;

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
                        .filter(f -> f.isAnnotationPresent(Netention.Field.class))
                        .toList();

                for (var i = 0; i < fields.size(); i++) {
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
            if (isKnownEditableType) {
                bottomPanel.add(UIUtil.button("💾", "Save Settings", e -> saveChanges()));
            }
            add(bottomPanel, BorderLayout.SOUTH);

            if (targetConfigObject != null && !"config.nostr_relays".equals(configNote.id)) {
                refreshFieldsFromConfig();
            }
            userModifiedConfig = false;
            if (onDirtyStateChange != null) onDirtyStateChange.accept(false);
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
                if (onDirtyStateChange != null) onDirtyStateChange.accept(true);
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
            if (onDirtyStateChange != null) onDirtyStateChange.accept(false);
        }

        public void saveChanges() {
            var targetConfigObject = getTargetConfigObject(configNote.id);
            if (!"config.nostr_relays".equals(configNote.id) && targetConfigObject != null) {
                var factory = new ConfigFieldEditorFactory(core, this::markDirty);
                fieldToComponentMap.forEach((field, component) -> factory.saveComponentValueToField(field, component, targetConfigObject));
            }
            core.cfg.saveAllConfigs();
            userModifiedConfig = false;
            if (onDirtyStateChange != null) onDirtyStateChange.accept(false);
            JOptionPane.showMessageDialog(this, "Settings saved.", "⚙️ Settings Saved", JOptionPane.INFORMATION_MESSAGE);
            if (onSaveCb != null) onSaveCb.run();
            refreshFieldsFromConfig();
        }

        public boolean isUserModified() {
            return userModifiedConfig;
        }

        public Netention.Note getConfigNote() {
            return configNote;
        }

        private JComponent buildNostrRelaysPanel() {
            var panel = new JPanel(new BorderLayout(5, 5));
            var listModel = new DefaultListModel<Netention.Note>();
            core.notes.getAll(n -> n.tags.contains(Netention.Note.SystemTag.NOSTR_RELAY.value))
                    .forEach(listModel::addElement);

            var relayList = new JList<>(listModel);
            relayList.setCellRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int i, boolean sel, boolean foc) {
                    super.getListCellRendererComponent(list, value, i, sel, foc);
                    if (value instanceof Netention.Note n) {
                        setText(n.content.getOrDefault(Netention.Note.ContentKey.RELAY_URL.getKey(), "N/A") +
                                ((Boolean) n.content.getOrDefault(Netention.Note.ContentKey.RELAY_ENABLED.getKey(), true) ? "" : " (Disabled)"));
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
                    core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, "nostr_relays_updated");
                }
            })));
            panel.add(buttonPanel, BorderLayout.SOUTH);
            return panel;
        }

        private void editRelayNote(@Nullable Netention.Note relayNote, DefaultListModel<Netention.Note> listModel) {
            var editorDialog = new RelayEditDialog((Frame) SwingUtilities.getWindowAncestor(this), core, relayNote);
            editorDialog.setVisible(true);

            if (editorDialog.isSaved()) {
                Netention.Note savedNote = editorDialog.getRelayNote();
                if (relayNote == null) {
                    listModel.addElement(savedNote);
                } else {
                    listModel.setElementAt(savedNote, listModel.indexOf(relayNote));
                }
                core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, "nostr_relays_updated");
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

                urlField = new JTextField(isNew ? "wss://" : (String) this.relayNote.content.getOrDefault(Netention.Note.ContentKey.RELAY_URL.getKey(), "wss://"), 30);
                enabledCheck = new JCheckBox("Enabled", isNew || (Boolean) this.relayNote.content.getOrDefault(Netention.Note.ContentKey.RELAY_ENABLED.getKey(), true));
                readCheck = new JCheckBox("Read (Subscribe)", isNew || (Boolean) this.relayNote.content.getOrDefault(Netention.Note.ContentKey.RELAY_READ.getKey(), true));
                writeCheck = new JCheckBox("Write (Publish)", isNew || (Boolean) this.relayNote.content.getOrDefault(Netention.Note.ContentKey.RELAY_WRITE.getKey(), true));

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
                buttonPanel.add(UIUtil.button("💾 Save", null, e -> saveRelay()));
                buttonPanel.add(UIUtil.button("❌ Cancel", null, e -> dispose()));

                setLayout(new BorderLayout(10, 10));
                add(formPanel, BorderLayout.CENTER);
                add(buttonPanel, BorderLayout.SOUTH);
                pack();
                setLocationRelativeTo(owner);
            }

            private void saveRelay() {
                var url = urlField.getText().trim();
                if (url.isEmpty() || !url.startsWith("ws://") && !url.startsWith("wss://")) {
                    JOptionPane.showMessageDialog(this, "Invalid relay URL. Must start with ws:// or wss://", "Invalid URL", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                var noteToSave = isNew ? new Netention.Note("Relay: " + url, "") : this.relayNote;
                if (isNew) noteToSave.tags.add(Netention.Note.SystemTag.NOSTR_RELAY.value);

                noteToSave.content.putAll(Map.of(
                        Netention.Note.ContentKey.RELAY_URL.getKey(), url,
                        Netention.Note.ContentKey.RELAY_ENABLED.getKey(), enabledCheck.isSelected(),
                        Netention.Note.ContentKey.RELAY_READ.getKey(), readCheck.isSelected(),
                        Netention.Note.ContentKey.RELAY_WRITE.getKey(), writeCheck.isSelected()
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


        static class ConfigFieldEditorFactory {
            private final Netention.Core core;
            private final Runnable dirtyMarker;

            public ConfigFieldEditorFactory(Netention.Core core, Runnable dirtyMarker) {
                this.core = core;
                this.dirtyMarker = dirtyMarker;
            }

            public JComponent createEditor(Field field, Object configObjInstance) {
                Netention.Field cf = field.getAnnotation(Netention.Field.class);
                JComponent comp;
                try {
                    field.setAccessible(true);
                    Object currentValue = field.get(configObjInstance);
                    DocumentListener dl = new FieldUpdateListener(e -> dirtyMarker.run());
                    ActionListener al = e -> dirtyMarker.run();

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

                var publicKeyLabel = new JLabel("Public Key (npub): " + ns.publicKeyBech32);

                privateKeyField.getDocument().addDocumentListener(new FieldUpdateListener(de -> {
                    try {
                        String pkNsec = new String(privateKeyField.getPassword());
                        ns.publicKeyBech32 = pkNsec != null && !pkNsec.trim().isEmpty() ?
                                Crypto.Bech32.nip19Encode("npub", Crypto.getPublicKeyXOnly(Crypto.Bech32.nip19Decode(pkNsec))) :
                                "Enter nsec to derive";
                    } catch (Exception ex) {
                        ns.publicKeyBech32 = "Invalid nsec format";
                    }
                    publicKeyLabel.setText("Public Key (npub): " + ns.publicKeyBech32);
                    privateKeyDocListener.insertUpdate(de);
                }));

                var generateButton = UIUtil.button("🔑 Generate New", "Generate New Nostr Keys", evt -> {
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
                if (currentValue instanceof List<?> listVal) ta.setText(String.join("\n", (List<String>) listVal));
                else if (currentValue != null) ta.setText(currentValue.toString());
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
                        var pkField = (JPasswordField) Arrays.stream(nostrIdentityPanel.getComponents()).filter(c -> "privateKeyBech32_field".equals(c.getName())).findFirst().orElseThrow();
                        var pubKeyLabel = (JLabel) Arrays.stream(nostrIdentityPanel.getComponents()).filter(JLabel.class::isInstance).findFirst().orElseThrow();
                        pkField.setText(value != null ? value.toString() : "");
                        if (configObjInstance instanceof Netention.Config.NostrSettings ns) {
                            pubKeyLabel.setText("Public Key (npub): " + ns.publicKeyBech32);
                        }
                        return;
                    }

                    var editor = component instanceof JScrollPane scp ? (JComponent) scp.getViewport().getView() : component;

                    if (editor instanceof JTextArea ta) {
                        if (cf.type() == Netention.FieldType.TEXT_AREA && value instanceof List<?> listVal) {
                            ta.setText(String.join("\n", (List<String>) listVal));
                        } else {
                            ta.setText(value != null ? value.toString() : "");
                        }
                    } else if (editor instanceof JTextComponent tc) {
                        tc.setText(value != null ? value.toString() : "");
                    } else if (editor instanceof JComboBox<?> cb) {
                        cb.setSelectedItem(value != null ? value.toString() : null);
                    } else if (editor instanceof JCheckBox chkbx) {
                        chkbx.setSelected(value instanceof Boolean b && b);
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
                        var pkField = (JPasswordField) Arrays.stream(nostrIdentityPanel.getComponents()).filter(c -> "privateKeyBech32_field".equals(c.getName())).findFirst().orElseThrow();
                        field.set(configObjInstance, new String(pkField.getPassword()));
                        return;
                    }

                    var editor = component instanceof JScrollPane scp ? (JComponent) scp.getViewport().getView() : component;

                    switch (editor) {
                        case JTextArea ta when cf.type() == Netention.FieldType.TEXT_AREA ->
                                field.set(configObjInstance, new ArrayList<>(List.of(ta.getText().split("\\n"))));
                        case JPasswordField pf -> field.set(configObjInstance, new String(pf.getPassword()));
                        case JTextComponent tc -> field.set(configObjInstance, tc.getText());
                        case JComboBox<?> cb -> field.set(configObjInstance, cb.getSelectedItem());
                        case JCheckBox chkbx -> field.set(configObjInstance, chkbx.isSelected());
                        default ->
                                logger.warn("Unhandled component type for field {}: {}", field.getName(), editor.getClass().getName());
                    }
                } catch (IllegalAccessException e) {
                    logger.error("Error saving config field {}: {}", field.getName(), e.getMessage(), e);
                }
            }
        }
    }

    public static class StatusPanel extends JPanel {
        private final JLabel label, nostrStatusLabel, llmStatusLabel, systemHealthLabel;
        private final Netention.Core core;
        private final Timer healthMetricsTimer;

        public StatusPanel(Netention.Core core) {
            super(new FlowLayout(FlowLayout.LEFT));
            this.core = core;
            setBorder(new EmptyBorder(2, 5, 2, 5));

            label = new JLabel("⏳ Initializing...");
            nostrStatusLabel = new JLabel();
            llmStatusLabel = new JLabel();
            systemHealthLabel = new JLabel();

            Stream.of(label, createSeparator(),
                            nostrStatusLabel, createSeparator(),
                            llmStatusLabel, createSeparator(),
                            systemHealthLabel)
                    .forEach(this::add);

            updateStatus("🚀 Application ready.");

            core.addCoreEventListener(e -> {
                if (e.type() == Netention.Core.CoreEventType.CONFIG_CHANGED || e.type() == Netention.Core.CoreEventType.STATUS_MESSAGE) {
                    var msg = e.type() == Netention.Core.CoreEventType.STATUS_MESSAGE && e.data() instanceof String s ? s : label.getText().replaceFirst(".*?: ", "").split(" \\| ")[0];
                    updateStatus(msg);
                }
            });

            healthMetricsTimer = new Timer(15000, e -> updateSystemHealthMetrics());
            healthMetricsTimer.setInitialDelay(1000);
            healthMetricsTimer.start();
        }

        private JSeparator createSeparator() {
            var sep = new JSeparator(SwingConstants.VERTICAL);
            sep.setPreferredSize(new Dimension(sep.getPreferredSize().width, label.getPreferredSize().height));
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

                nostrStatusLabel.setText("💜 Nostr: " + (nostrEnabled ? connectedRelays + "/" + configuredRelays + " Relays" : "OFF"));
                nostrStatusLabel.setForeground(nostrEnabled ? connectedRelays > 0 ? new Color(0, 153, 51) : Color.ORANGE.darker() : Color.RED);

                llmStatusLabel.setText("💡 LLM: " + (core.lm.isReady() ? "READY" : "NOT READY"));
                llmStatusLabel.setForeground(core.lm.isReady() ? new Color(0, 153, 51) : Color.RED);
            });
        }
    }

    private record FieldUpdateListener(Consumer<DocumentEvent> consumer) implements DocumentListener {
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

    private static class UIUtil {
        static JButton button(String emoji, String tooltip, Runnable listener) {
            return button(emoji, "", tooltip, e -> listener.run());
        }

        static JButton button(String emoji, String tooltip, ActionListener listener) {
            return button(emoji, "", tooltip, listener);
        }

        static JButton button(String textOrEmoji, String textIfEmojiOnly, String tooltip, ActionListener listener) {
            var button = new JButton(textOrEmoji + (textIfEmojiOnly.isEmpty() ? "" : " " + textIfEmojiOnly));
            if (tooltip != null && !tooltip.isEmpty()) button.setToolTipText(tooltip);
            if (listener != null) button.addActionListener(listener);
            button.setMargin(new Insets(2, 5, 2, 5));
            return button;
        }

        static JMenuItem menuItem(String text, ActionListener listener) {
            return menuItem(text, null, listener, null);
        }

        static JMenuItem menuItem(String text, @Nullable String actionCommand, ActionListener listener) {
            return menuItem(text, actionCommand, listener, null);
        }

        static JMenuItem menuItem(String text, @Nullable String actionCommand, @Nullable ActionListener listener, @Nullable KeyStroke accelerator) {
            var item = new JMenuItem(text);
            ofNullable(actionCommand).ifPresent(item::setActionCommand);
            ofNullable(listener).ifPresent(item::addActionListener);
            ofNullable(accelerator).ifPresent(item::setAccelerator);
            return item;
        }

        static void addLabelAndComponent(JPanel panel, GridBagConstraints gbc, int y, String labelText, Component component) {
            gbc.gridx = 0;
            gbc.gridy = y;
            gbc.weightx = 0.0;
            gbc.gridwidth = 1;
            panel.add(new JLabel(labelText), gbc);
            gbc.gridx = 1;
            gbc.gridy = y;
            gbc.weightx = 1.0;
            panel.add(component, gbc);
        }

        static JDialog showPanelInDialog(Frame owner, String title, JComponent panelContent, Dimension preferredSize, boolean modal) {
            return showEditablePanelInDialog(owner, title, panelContent, preferredSize, modal, null);
        }

        static JDialog showEditablePanelInDialog(Frame owner, String baseTitle, JComponent panelContent, Dimension preferredSize, boolean modal, @Nullable Function<JComponent, Boolean> dirtyStateProvider) {
            JDialog dialog = new JDialog(owner, baseTitle, modal);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            dialog.setContentPane(panelContent);

            if (preferredSize != null) {
                dialog.setPreferredSize(preferredSize);
                dialog.setSize(preferredSize);
            } else {
                dialog.pack();
            }
            dialog.setLocationRelativeTo(owner);

            if (dirtyStateProvider != null && panelContent instanceof Container) {
                // The original code relied on the panel itself having an onDirtyStateChange callback
                // or the caller managing the title. For this utility, direct title update is hard.
                // The caller (e.g. SimpleChat for profile editor) handles this by passing a callback
                // to the NoteEditorPanel that updates the dialog title.
                // This utility will just show the dialog.
            }
            dialog.setVisible(true); // Make sure this is called after pack/setSize
            return dialog;
        }

        static void addNostrContactDialog(Component parentComponent, Netention.Core core, Consumer<String> onAddedCallback) {
            var pkNpub = JOptionPane.showInputDialog(parentComponent, "Friend's Nostr public key (npub):");
            if (pkNpub != null && !pkNpub.trim().isEmpty()) {
                try {
                    var cleanNpub = pkNpub.trim();
                    var hexPubKey = Crypto.bytesToHex(Crypto.Bech32.nip19Decode(cleanNpub));

                    if (core.net.isEnabled()) core.net.sendFriendRequest(cleanNpub);

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

                    var chatId = "chat_" + cleanNpub;
                    if (core.notes.get(chatId).isEmpty()) {
                        var chatNote = new Netention.Note("Chat with " + cleanNpub.substring(0, Math.min(10, cleanNpub.length())) + "...", "");
                        chatNote.id = chatId;
                        chatNote.tags.addAll(List.of(Netention.Note.SystemTag.CHAT.value, "nostr"));
                        chatNote.meta.put(Netention.Note.Metadata.NOSTR_PUB_KEY.key, cleanNpub);
                        chatNote.content.put(Netention.Note.ContentKey.MESSAGES.getKey(), new ArrayList<Map<String, String>>());
                        core.saveNote(chatNote);
                        JOptionPane.showMessageDialog(parentComponent, "Friend " + cleanNpub.substring(0, 10) + "... added & intro DM sent (if Nostr enabled).", "🤝 Friend Added", JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(parentComponent, "Friend " + cleanNpub.substring(0, 10) + "... already exists.", "ℹ️ Friend Exists", JOptionPane.INFORMATION_MESSAGE);
                    }
                    if (onAddedCallback != null) onAddedCallback.accept(cleanNpub);

                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(parentComponent, "Invalid Nostr public key (npub) or error: " + ex.getMessage(), "Error Adding Friend", JOptionPane.ERROR_MESSAGE);
                }
            }
        }
    }
}
