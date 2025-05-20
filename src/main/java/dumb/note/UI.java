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

import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

public class UI extends JFrame {
    private static final Logger logger = LoggerFactory.getLogger(UI.class);
    private final Netention.Core core;
    private final JSplitPane contentInspectorSplit;
    private final NavPanel navPanel;
    private final JPanel contentPanelHost;
    private final InspectorPanel inspectorPanel;
    private final StatusPanel statusPanel;
    private final Map<String, ActionableItem> actionableItems = new ConcurrentHashMap<>();
    private TrayIcon trayIcon;
    private SystemTray tray;

    public UI(Netention.Core core) {
        this.core = core;
        setTitle("Netention ✨");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);
        contentPanelHost = new JPanel(new BorderLayout());

        if (core.cfg.net.privateKeyBech32 == null || core.cfg.net.privateKeyBech32.isEmpty()) {
            logger.info("Nostr identity missing. Generating new identity...");
            var keysInfo = core.cfg.generateNewNostrKeysAndUpdateConfig();
            core.net.setEnabled(true);
            var keyArea = new JTextArea(keysInfo);
            keyArea.setEditable(false);
            var scrollPane = new JScrollPane(keyArea);
            scrollPane.setPreferredSize(new Dimension(400, 150));
            JOptionPane.showMessageDialog(this, scrollPane, "🔑 New Nostr Identity Created - BACKUP YOUR nsec KEY!", JOptionPane.INFORMATION_MESSAGE);
        } else if (!core.net.isEnabled()) {
            core.net.setEnabled(true);
        }

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                handleWindowClose();
            }

            @Override
            public void windowIconified(WindowEvent e) {
                if (core.cfg.ui.minimizeToTray && tray != null) {
                    setVisible(false);
                }
            }
        });
        core.addCoreEventListener(this::handleCoreEvent);
        inspectorPanel = new InspectorPanel(core, this, this::display, this::getActionableItemsForNote);
        setJMenuBar(createMenuBar());
        navPanel = new NavPanel(core, this::display, this::displayChatInEditor, this::displaySettingsInEditor, this::createNewNote, this::createNewNoteFromTemplate);
        contentInspectorSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, contentPanelHost, inspectorPanel);
        contentInspectorSplit.setResizeWeight(0.65);
        contentInspectorSplit.setOneTouchExpandable(true);
        var mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, navPanel, contentInspectorSplit);
        mainSplitPane.setDividerLocation(280);
        mainSplitPane.setOneTouchExpandable(true);
        add(mainSplitPane, BorderLayout.CENTER);
        statusPanel = new StatusPanel(core);
        add(statusPanel, BorderLayout.SOUTH);
        initSystemTray();
        updateTheme(core.cfg.ui.theme);
        setVisible(true);
        displayNoteInEditor(null);
        inspectorPanel.setVisible(false);
        contentInspectorSplit.setDividerLocation(1.0);
        logger.info("NetentionUI initialized.");
    }

    public java.util.List<ActionableItem> getActionableItemsForNote(String noteId) {
        return actionableItems.values().stream()
                .filter(item -> (item.planNoteId() != null && item.planNoteId().equals(noteId)) ||
                        ("DISTRIBUTED_LM_RESULT".equals(item.type()) && item.rawData() instanceof Map && noteId.equals(((Map<?, ?>) item.rawData()).get("sourceNoteId"))))
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private void handleCoreEvent(Netention.Core.CoreEvent event) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> handleCoreEvent(event));
            return;
        }
        switch (event.type()) {
            case USER_INTERACTION_REQUESTED -> {
                if (event.data() instanceof Map data) {
                    var prompt = (String) data.getOrDefault(Netention.Planner.ToolParam.PROMPT.getKey(), "Input required:");
                    var callbackKey = (String) data.get(Netention.Planner.ToolParam.CALLBACK_KEY.getKey());
                    var planNoteId = (String) data.get(Netention.Planner.ToolParam.PLAN_NOTE_ID.getKey());
                    if (callbackKey != null) {
                        var itemId = "user_input_" + callbackKey;
                        var item = new ActionableItem(itemId, planNoteId, "Plan requires input: " + prompt, "USER_INTERACTION", data, () -> {
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
                    var item = new ActionableItem(itemId, null, String.format("LM Result from %s for %s", processedByNpub.substring(0, 10) + "...", tool), "DISTRIBUTED_LM_RESULT", data, () -> {
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
            case STATUS_MESSAGE -> {
                if (event.data() instanceof String msg) statusPanel.updateStatus(msg);
            }
            case CHAT_MESSAGE_ADDED -> {
                if (navPanel != null) navPanel.refreshNotes();
            }
            case CONFIG_CHANGED -> {
                if ("ui_theme_updated".equals(event.data())) updateTheme(core.cfg.ui.theme);
                navPanel.updateServiceDependentButtonStates();
                var currentEditor = contentPanelHost.getComponentCount() > 0 && contentPanelHost.getComponent(0) instanceof NoteEditorPanel nep ? nep : null;
                if (currentEditor != null) currentEditor.updateServiceDependentButtonStates();
                inspectorPanel.updateServiceDependentButtonStates();
                statusPanel.updateStatus("⚙️ Configuration reloaded/changed.");
                if (getJMenuBar() != null && getJMenuBar().getMenuCount() > 0) {
                    Arrays.stream(getJMenuBar().getComponents())
                            .filter(c -> c instanceof JMenu && "Nostr 💜".equals(((JMenu) c).getText())).findFirst()
                            .flatMap(m -> Arrays.stream(((JMenu) m).getMenuComponents()).filter(mc -> mc instanceof JCheckBoxMenuItem && UIAction.TOGGLE_NOSTR.name().equals(((JCheckBoxMenuItem) mc).getActionCommand())).findFirst())
                            .ifPresent(cbm -> ((JCheckBoxMenuItem) cbm).setSelected(core.net.isEnabled()));
                }
            }
            default -> {
            }
        }
    }

    private void handleWindowClose() {
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
        Stream.of(
                new MenuItem("✨ Open Netention") {{
                    addActionListener(e -> restoreWindow());
                }},
                new MenuItem("➕ Quick Add Note") {{
                    addActionListener(e -> quickAddNoteFromTray());
                }},
                null,
                new MenuItem("🚪 Exit") {{
                    addActionListener(e -> {
                        tray.remove(trayIcon);
                        System.exit(0);
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

    private void setContentPanel(JComponent panel, Netention.Note contextNote) {
        contentPanelHost.removeAll();
        if (panel != null) contentPanelHost.add(panel, BorderLayout.CENTER);
        contentPanelHost.revalidate();
        contentPanelHost.repaint();
        inspectorPanel.setContextNote(contextNote);
        var showInspector = contextNote != null || inspectorPanel.isPlanViewActive() || inspectorPanel.isActionItemsViewActiveAndNotEmpty();
        if (inspectorPanel.isVisible() != showInspector) {
            inspectorPanel.setVisible(showInspector);
            contentInspectorSplit.setDividerLocation(showInspector ? contentInspectorSplit.getResizeWeight() : 1.0);
        }
    }

    public void display(@Nullable Netention.Note note) {
        if (note == null) displayNoteInEditor(null);
        else {
            if (note.tags.contains(Netention.Note.SystemTag.CHAT.value)) displayChatInEditor(note);
            else displayNoteInEditor(note);
        }
    }

    private void displayNoteInEditor(@Nullable Netention.Note note) {
        var currentEditor = (contentPanelHost.getComponentCount() > 0 && contentPanelHost.getComponent(0) instanceof NoteEditorPanel nep) ? nep : null;
        if (currentEditor != null && currentEditor.getCurrentNote() != null && note != null && currentEditor.getCurrentNote().id.equals(note.id)) {
            if (currentEditor.isUserModified()) {
                currentEditor.updateNonTextParts(note);
                inspectorPanel.setContextNote(note);
                return;
            }
        }
        setContentPanel(new NoteEditorPanel(core, note, () -> {
            var editorPanel = (NoteEditorPanel) contentPanelHost.getComponent(0);
            var currentNoteInEditor = editorPanel.getCurrentNote();
            statusPanel.updateStatus(currentNoteInEditor == null || currentNoteInEditor.id == null ? "📝 Note created" : "💾 Note saved: " + currentNoteInEditor.getTitle());
            inspectorPanel.setContextNote(currentNoteInEditor);
        }, inspectorPanel), note);
    }

    public void createNewNote() {
        displayNoteInEditor(new Netention.Note("Untitled", ""));
    }

    public void createNewNoteFromTemplate(Netention.Note templateNote) {
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
        setContentPanel(new ChatPanel(core, chatNote, partnerNpub), chatNote);
    }

    public void displaySettingsInEditor() {
        setContentPanel(new SettingsPanel(core, this::updateThemeAndRestartMessage, () -> {
            statusPanel.updateStatus("⚙️ LLM status potentially updated from Settings.");
            navPanel.updateServiceDependentButtonStates();
            if (contentPanelHost.getComponentCount() > 0 && contentPanelHost.getComponent(0) instanceof NoteEditorPanel nep)
                nep.updateServiceDependentButtonStates();
            inspectorPanel.updateServiceDependentButtonStates();
            core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, "llm_settings_changed");
        }), null);
    }

    private void updateThemeAndRestartMessage(String themeName) {
        updateTheme(themeName);
        JOptionPane.showMessageDialog(this, "🎨 Theme changed to " + themeName + ". Some L&F changes may require restart.", "Theme Changed", JOptionPane.INFORMATION_MESSAGE);
    }

    private void updateTheme(String themeName) {
        try {
            UIManager.setLookAndFeel("Nimbus (Dark)".equalsIgnoreCase(themeName) ? "javax.swing.plaf.nimbus.NimbusLookAndFeel" : UIManager.getSystemLookAndFeelClassName());
            SwingUtilities.updateComponentTreeUI(this);
        } catch (Exception e) {
            logger.warn("Failed to set theme '{}': {}", themeName, e.getMessage());
        }
    }

    private JMenuBar createMenuBar() {
        var m = new JMenuBar();
        m.add(createMenu("File 📁", Map.of(
                "➕ New Note", UIAction.NEW_NOTE,
                "➕📄 New Note from Template...", UIAction.NEW_FROM_TEMPLATE,
                "⚙️ Settings", UIAction.SETTINGS,
                "🚪 Exit", UIAction.EXIT
        )));
        m.add(createViewMenu());
        m.add(createMenu("Nostr 💜", Map.of(
                "👤 My Nostr Profile", UIAction.MY_PROFILE,
                "🚀 Publish My Profile", UIAction.PUBLISH_PROFILE,
                "➕ Add Nostr Friend", UIAction.ADD_NOSTR_FRIEND
        ), createNostrToggleMenuItem()));
        m.add(createMenu("LLM 💡", Map.of(
                "🚀 Initialize LLM Service", UIAction.INIT_LLM
        )));
        return m;
    }

    private JMenu createMenu(String title, Map<String, UIAction> items, Component... additionalItems) {
        var menu = new JMenu(title);
        items.forEach((text, action) -> {
            if (text == null) menu.addSeparator();
            else menu.add(createMenuItem(text, "", action, null));
        });
        Stream.of(additionalItems).forEach(menu::add);
        return menu;
    }

    private JMenuItem createMenuItem(String text, String emoji, UIAction action, KeyStroke accelerator) {
        var item = new JMenuItem(text + (emoji.isEmpty() ? "" : " " + emoji));
        if (action != null) item.setActionCommand(action.name());
        item.addActionListener(this::handleMenuAction);
        if (accelerator != null) item.setAccelerator(accelerator);
        return item;
    }

    private JMenu createViewMenu() {
        var viewM = new JMenu("View 👁️");
        var toggleInspectorItem = new JCheckBoxMenuItem("Toggle Inspector Panel");
        toggleInspectorItem.setActionCommand(UIAction.TOGGLE_INSPECTOR.name());
        toggleInspectorItem.setSelected(inspectorPanel.isVisible());
        toggleInspectorItem.addActionListener(this::handleMenuAction);
        viewM.add(toggleInspectorItem);
        return viewM;
    }

    private JCheckBoxMenuItem createNostrToggleMenuItem() {
        var toggleNostr = new JCheckBoxMenuItem("Enable Nostr");
        toggleNostr.setActionCommand(UIAction.TOGGLE_NOSTR.name());
        toggleNostr.setSelected(core.net.isEnabled());
        toggleNostr.addActionListener(this::handleMenuAction);
        return toggleNostr;
    }

    private void handleMenuAction(ActionEvent e) {
        var action = UIAction.valueOf(e.getActionCommand());
        switch (action) {
            case NEW_NOTE -> createNewNote();
            case NEW_FROM_TEMPLATE -> {
                var templates = core.notes.getAll(n -> n.tags.contains(Netention.Note.SystemTag.TEMPLATE.value));
                if (templates.isEmpty()) {
                    JOptionPane.showMessageDialog(UI.this, "No templates found. Create a note and tag it with '" + Netention.Note.SystemTag.TEMPLATE.value + "'.", "🤷 No Templates", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                var selectedTemplate = (Netention.Note) JOptionPane.showInputDialog(UI.this, "Select a template:", "New from Template", JOptionPane.PLAIN_MESSAGE, null, templates.toArray(), templates.getFirst());
                if (selectedTemplate != null) createNewNoteFromTemplate(selectedTemplate);
            }
            case SETTINGS -> displaySettingsInEditor();
            case EXIT -> handleWindowClose();
            case TOGGLE_INSPECTOR -> {
                var show = ((JCheckBoxMenuItem) e.getSource()).isSelected();
                inspectorPanel.setVisible(show);
                contentInspectorSplit.setDividerLocation(show ? contentInspectorSplit.getResizeWeight() : 1.0);
                contentInspectorSplit.revalidate();
            }
            case MY_PROFILE -> {
                if (core.cfg.net.myProfileNoteId != null && !core.cfg.net.myProfileNoteId.isEmpty()) {
                    core.notes.get(core.cfg.net.myProfileNoteId).ifPresentOrElse(this::displayNoteInEditor, () -> JOptionPane.showMessageDialog(this, "My Profile note not found.", "Error", JOptionPane.ERROR_MESSAGE));
                } else
                    JOptionPane.showMessageDialog(this, "My Profile note ID not configured.", "Error", JOptionPane.ERROR_MESSAGE);
            }
            case PUBLISH_PROFILE -> {
                if (core.cfg.net.myProfileNoteId != null && !core.cfg.net.myProfileNoteId.isEmpty()) {
                    core.notes.get(core.cfg.net.myProfileNoteId).ifPresentOrElse(profileNote -> {
                                core.net.publishProfile(profileNote);
                                JOptionPane.showMessageDialog(this, "Profile publish request sent.", "Nostr Profile", JOptionPane.INFORMATION_MESSAGE);
                            },
                            () -> JOptionPane.showMessageDialog(this, "My Profile note not found.", "Error", JOptionPane.ERROR_MESSAGE));
                } else
                    JOptionPane.showMessageDialog(this, "My Profile note ID not configured.", "Error", JOptionPane.ERROR_MESSAGE);
            }
            case TOGGLE_NOSTR -> {
                var wantsEnable = ((JCheckBoxMenuItem) e.getSource()).isSelected();
                String statusMsg;
                if (wantsEnable) {
                    if (core.cfg.net.privateKeyBech32 == null || core.cfg.net.privateKeyBech32.isEmpty()) {
                        JOptionPane.showMessageDialog(UI.this, "Nostr private key (nsec) not configured.\nPlease go to File > Settings > Nostr: Identity.", "Nostr Configuration Needed", JOptionPane.WARNING_MESSAGE);
                        statusMsg = "Nostr setup required.";
                        ((JCheckBoxMenuItem) e.getSource()).setSelected(false);
                    } else {
                        core.net.setEnabled(true);
                        statusMsg = core.net.isEnabled() ? "Nostr enabled." : "Nostr enabling failed.";
                        JOptionPane.showMessageDialog(UI.this, statusMsg, "Nostr Status", core.net.isEnabled() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE);
                        ((JCheckBoxMenuItem) e.getSource()).setSelected(core.net.isEnabled());
                    }
                } else {
                    core.net.setEnabled(false);
                    statusMsg = "Nostr disabled by user.";
                    JOptionPane.showMessageDialog(UI.this, statusMsg, "Nostr Status", JOptionPane.INFORMATION_MESSAGE);
                }
                statusPanel.updateStatus(statusMsg);
                core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, "nostr_status_changed");
            }
            case ADD_NOSTR_FRIEND -> {
                var pkNpub = JOptionPane.showInputDialog(UI.this, "Friend's Nostr public key (npub):");
                if (pkNpub != null && !pkNpub.trim().isEmpty()) {
                    try {
                        var hexPubKey = Crypto.bytesToHex(Crypto.Bech32.nip19Decode(pkNpub.trim()));
                        core.net.sendFriendRequest(pkNpub.trim());
                        core.notes.get("contact_" + hexPubKey).orElseGet(() -> {
                            var contactN = new Netention.Note("Contact: " + pkNpub.trim().substring(0, 12) + "...", "");
                            contactN.id = "contact_" + hexPubKey;
                            contactN.tags.addAll(Arrays.asList(Netention.Note.SystemTag.CONTACT.value, Netention.Note.SystemTag.NOSTR_CONTACT.value));
                            contactN.meta.putAll(Map.of(Netention.Note.Metadata.NOSTR_PUB_KEY.key, pkNpub.trim(), Netention.Note.Metadata.NOSTR_PUB_KEY_HEX.key, hexPubKey));
                            return core.saveNote(contactN);
                        });
                        var cId = "chat_" + pkNpub.trim();
                        if (core.notes.get(cId).isEmpty()) {
                            var fn = new Netention.Note("Chat with " + pkNpub.trim().substring(0, 10) + "...", "");
                            fn.id = cId;
                            fn.tags.addAll(java.util.List.of(Netention.Note.SystemTag.CHAT.value, "nostr"));
                            fn.meta.put(Netention.Note.Metadata.NOSTR_PUB_KEY.key, pkNpub.trim());
                            fn.content.put(Netention.Note.ContentKey.MESSAGES.getKey(), new ArrayList<Map<String, String>>());
                            core.saveNote(fn);
                            JOptionPane.showMessageDialog(UI.this, "Friend " + pkNpub.trim().substring(0, 10) + "... added & intro DM sent.", "🤝 Friend Added", JOptionPane.INFORMATION_MESSAGE);
                        } else
                            JOptionPane.showMessageDialog(UI.this, "Friend " + pkNpub.trim().substring(0, 10) + "... already exists.", "ℹ️ Friend Exists", JOptionPane.INFORMATION_MESSAGE);
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(UI.this, "Invalid Nostr public key (npub) or error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }
            case INIT_LLM -> {
                core.lm.init();
                var msg = "LLM Service " + (core.lm.isReady() ? "initialized successfully." : "failed to initialize. Check settings/logs.");
                JOptionPane.showMessageDialog(UI.this, msg, "LLM Status", core.lm.isReady() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE);
                statusPanel.updateStatus("LLM status updated.");
                core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, "llm_status_changed");
            }
        }
    }

    private enum UIAction {NEW_NOTE, NEW_FROM_TEMPLATE, SETTINGS, EXIT, TOGGLE_INSPECTOR, TOGGLE_NOSTR, ADD_NOSTR_FRIEND, INIT_LLM, MY_PROFILE, PUBLISH_PROFILE}

    public record ActionableItem(String id, String planNoteId, String description, String type, Object rawData,
                                 Runnable action) {
    }

    public static class NavPanel extends JPanel {
        private final Netention.Core core;
        private final DefaultListModel<Netention.Note> listModel = new DefaultListModel<>();
        private final JList<Netention.Note> noteJList = new JList<>(listModel);
        private final JTextField searchField = new JTextField(15);
        private final JButton semanticSearchButton;
        private final JComboBox<NavPanel.View> viewSelector;
        private final JPanel tagFilterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        private final Set<String> activeTagFilters = new HashSet<>();

        public NavPanel(Netention.Core core, Consumer<Netention.Note> onShowNote, Consumer<Netention.Note> onShowChat, Runnable onSettings, Runnable onNewNote, Consumer<Netention.Note> onNewNoteFromTemplate) {
            this.core = core;
            setLayout(new BorderLayout(5, 5));
            setBorder(new EmptyBorder(5, 5, 5, 5));
            core.addCoreEventListener(event -> {
                if (Set.of(Netention.Core.CoreEventType.NOTE_ADDED, Netention.Core.CoreEventType.NOTE_UPDATED, Netention.Core.CoreEventType.NOTE_DELETED, Netention.Core.CoreEventType.CONFIG_CHANGED, Netention.Core.CoreEventType.CHAT_MESSAGE_ADDED).contains(event.type())) {
                    SwingUtilities.invokeLater(this::refreshNotes);
                }
            });
            noteJList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            noteJList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting())
                    ofNullable(noteJList.getSelectedValue()).ifPresent(sel -> (sel.tags.contains(Netention.Note.SystemTag.CHAT.value) ? onShowChat : onShowNote).accept(sel));
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
            topControls.add(UIUtil.button("➕Note", "", "Create New Note", e -> onNewNote.run()));
            topControls.add(UIUtil.button("➕📄", "", "New Note from Template", e -> {
                var templates = core.notes.getAll(n -> n.tags.contains(Netention.Note.SystemTag.TEMPLATE.value));
                if (templates.isEmpty()) {
                    JOptionPane.showMessageDialog(this, "No templates found.", "🤷 No Templates", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                var selectedTemplate = (Netention.Note) JOptionPane.showInputDialog(this, "Select a template:", "New from Template", JOptionPane.PLAIN_MESSAGE, null, templates.toArray(), templates.getFirst());
                if (selectedTemplate != null) onNewNoteFromTemplate.accept(selectedTemplate);
            }));
            topControls.add(UIUtil.button("⚙️Prefs", "", "Open Settings", e -> onSettings.run()));

            viewSelector = new JComboBox<>(NavPanel.View.values());
            viewSelector.addActionListener(e -> {
                refreshNotes();
                updateServiceDependentButtonStates();
            });

            var searchPanel = new JPanel(new BorderLayout(5, 0));
            searchPanel.add(new JLabel("🔍 Search:"), BorderLayout.WEST);
            searchPanel.add(searchField, BorderLayout.CENTER);
            searchField.getDocument().addDocumentListener(new FieldUpdateListener(e -> refreshNotes()));
            semanticSearchButton = UIUtil.button("🧠AI", "", "Semantic Search (AI)", e -> performSemanticSearch());
            var combinedSearchPanel = new JPanel(new BorderLayout());
            combinedSearchPanel.add(searchPanel, BorderLayout.CENTER);
            combinedSearchPanel.add(semanticSearchButton, BorderLayout.EAST);

            var tagScrollPane = new JScrollPane(tagFilterPanel, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            tagScrollPane.setPreferredSize(new Dimension(200, 60));

            var topBox = Box.createVerticalBox();
            Stream.of(topControls, viewSelector, combinedSearchPanel, tagScrollPane).forEach(comp -> {
                comp.setAlignmentX(Component.LEFT_ALIGNMENT);
                if (comp == viewSelector || comp == combinedSearchPanel)
                    comp.setMaximumSize(new Dimension(Integer.MAX_VALUE, comp.getPreferredSize().height));
                topBox.add(comp);
            });
            add(topBox, BorderLayout.NORTH);
            refreshNotes();
            updateServiceDependentButtonStates();
        }

        @SuppressWarnings("unchecked")
        private void showNoteContextMenu(Netention.Note note, MouseEvent e) {
            var contextMenu = new JPopupMenu();
            if (note.tags.contains(Netention.Note.SystemTag.NOSTR_FEED.value) && core.lm.isReady() && core.net.isEnabled()) {
                var processMenu = new JMenu("💡 Process with My LM");
                processMenu.setEnabled(core.lm.isReady());
                Stream.of("Summarize", "Decompose Task", "Identify Concepts").forEach(tool -> processMenu.add(UIUtil.menuItem(tool, "", null, ae -> processSharedNoteWithLM(note, tool))));
                contextMenu.add(processMenu);
            }
            if (note.tags.contains(Netention.Note.SystemTag.SYSTEM_EVENT.value)) {
                var status = (String) note.content.getOrDefault(Netention.Note.ContentKey.STATUS.getKey(), "");
                if (Set.of("PROCESSED", "FAILED_PROCESSING", Netention.Planner.PlanState.FAILED.name()).contains(status)) {
                    contextMenu.add(UIUtil.menuItem("🗑️ Delete Processed/Failed Event", "", null, ae -> {
                        if (JOptionPane.showConfirmDialog(this, "Delete system event note '" + note.getTitle() + "'?", "Confirm Delete", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
                            core.deleteNote(note.id);
                    }));
                }
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
                JOptionPane.showMessageDialog(this, "Original publisher not found.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            CompletableFuture.supplyAsync(() -> {
                var contentToProcess = sharedNote.getContentForEmbedding();
                return switch (toolName) {
                    case "Summarize" -> core.lm.summarize(contentToProcess);
                    case "Decompose Task" ->
                            core.lm.decomposeTask(contentToProcess).map(list -> String.join("\n- ", list));
                    case "Identify Concepts" -> core.lm.chat("Identify key concepts in:\n\n" + contentToProcess);
                    default -> empty();
                };
            }).thenAcceptAsync(resultOpt -> resultOpt.ifPresent(result -> {
                try {
                    var payload = Map.of("type", "netention_lm_result", "sourceNoteId", sharedNote.id, "tool", toolName, "result", result, "processedByNpub", core.net.getPublicKeyBech32());
                    core.net.sendDirectMessage(originalPublisherNpub, core.json.writeValueAsString(payload));
                    JOptionPane.showMessageDialog(this, toolName + " result sent to " + originalPublisherNpub.substring(0, 10) + "...", "💡 LM Result Sent", JOptionPane.INFORMATION_MESSAGE);
                } catch (JsonProcessingException jpe) {
                    JOptionPane.showMessageDialog(this, "Error packaging LM result: " + jpe.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }), SwingUtilities::invokeLater).exceptionally(ex -> {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Error processing with LM: " + ex.getCause().getMessage(), "Error", JOptionPane.ERROR_MESSAGE));
                return null;
            });
        }

        public void updateServiceDependentButtonStates() {
            semanticSearchButton.setEnabled(core.lm.isReady() && Set.of(NavPanel.View.MY_NOTES, NavPanel.View.ALL_NOTES, NavPanel.View.GOALS, NavPanel.View.PUBLIC_FEED).contains(viewSelector.getSelectedItem()));
        }

        public void refreshNotes() {
            refreshNotes(null);
        }

        public void refreshNotes(java.util.List<Netention.Note> notesToDisplay) {
            var selectedBefore = noteJList.getSelectedValue();
            listModel.clear();
            var finalFilter = getPredicate();

            var filteredNotes = (notesToDisplay != null ? notesToDisplay.stream().filter(finalFilter) : core.notes.getAll(finalFilter).stream())
                    .sorted(Comparator.comparing((Netention.Note n) -> n.updatedAt).reversed()).collect(Collectors.toList());
            filteredNotes.forEach(listModel::addElement);
            updateTagFilterPanel(filteredNotes);
            if (selectedBefore != null && listModel.contains(selectedBefore))
                noteJList.setSelectedValue(selectedBefore, true);
            else if (!listModel.isEmpty() && noteJList.getSelectedIndex() == -1) noteJList.setSelectedIndex(0);
        }

        private @NotNull Predicate<Netention.Note> getPredicate() {
            var term = searchField.getText().toLowerCase();
            Predicate<Netention.Note> textFilter = n -> term.isEmpty() || n.getTitle().toLowerCase().contains(term) || n.getText().toLowerCase().contains(term) || n.tags.stream().anyMatch(t -> t.toLowerCase().contains(term));
            Predicate<Netention.Note> tagFilterPred = n -> activeTagFilters.isEmpty() || n.tags.containsAll(activeTagFilters);
            return ((NavPanel.View) Objects.requireNonNullElse(viewSelector.getSelectedItem(), NavPanel.View.MY_NOTES)).getFilter().and(textFilter).and(tagFilterPred);
        }

        private void updateTagFilterPanel(java.util.List<Netention.Note> currentNotesInList) {
            tagFilterPanel.removeAll();
            var alwaysExclude = Stream.of(Netention.Note.SystemTag.values()).map(tag -> tag.value).collect(Collectors.toSet());
            var counts = currentNotesInList.stream().flatMap(n -> n.tags.stream()).filter(t -> !alwaysExclude.contains(t) && !t.startsWith("#")).collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
            counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).limit(15).forEach(entry -> {
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

        private void performSemanticSearch() {
            if (!core.lm.isReady()) {
                JOptionPane.showMessageDialog(this, "LLM Service not ready.", "LLM Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            var query = JOptionPane.showInputDialog(this, "Semantic search query:");
            if (query == null || query.trim().isEmpty()) return;
            CompletableFuture.supplyAsync(() -> core.lm.generateEmbedding(query))
                    .thenAcceptAsync(queryEmbOpt -> queryEmbOpt.ifPresentOrElse(qEmb -> {
                        var notesWithEmb = core.notes.getAllNotes().stream().filter(n -> !n.tags.contains(Netention.Note.SystemTag.CONFIG.value) && n.getEmbeddingV1() != null && n.getEmbeddingV1().length == qEmb.length).toList();
                        if (notesWithEmb.isEmpty()) {
                            JOptionPane.showMessageDialog(this, "No notes with embeddings found.", "🧠 Semantic Search", JOptionPane.INFORMATION_MESSAGE);
                            return;
                        }
                        var scored = notesWithEmb.stream().map(n -> Map.entry(n, LM.cosineSimilarity(qEmb, n.getEmbeddingV1()))).filter(entry -> entry.getValue() > 0.1)
                                .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue())).map(Map.Entry::getKey).collect(Collectors.toList());
                        if (scored.isEmpty())
                            JOptionPane.showMessageDialog(this, "No relevant notes found.", "🧠 Semantic Search", JOptionPane.INFORMATION_MESSAGE);
                        else refreshNotes(scored);
                    }, () -> JOptionPane.showMessageDialog(this, "Failed to generate embedding for query.", "LLM Error", JOptionPane.ERROR_MESSAGE)), SwingUtilities::invokeLater)
                    .exceptionally(ex -> {
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Error during semantic search: " + ex.getCause().getMessage(), "Search Error", JOptionPane.ERROR_MESSAGE));
                        return null;
                    });
        }

        private enum View {
            MY_NOTES("📝 My Notes", n -> Stream.of(Netention.Note.SystemTag.values()).map(tag -> tag.value).noneMatch(n.tags::contains)),
            PUBLIC_FEED("🌐 Public Feed", n -> n.tags.contains(Netention.Note.SystemTag.NOSTR_FEED.value)),
            CONTACTS("👥 Contacts", n -> n.tags.contains(Netention.Note.SystemTag.CONTACT.value)),
            TEMPLATES("📄 Templates", n -> n.tags.contains(Netention.Note.SystemTag.TEMPLATE.value)),
            GOALS("🎯 Goals", n -> n.tags.contains(Netention.Note.SystemTag.GOAL_WITH_PLAN.value)),
            SYSTEM_EVENTS("⚙️ System Events", n -> n.tags.contains(Netention.Note.SystemTag.SYSTEM_EVENT.value)),
            SYSTEM_HANDLERS("🛠️ System Handlers", n -> n.tags.contains(Netention.Note.SystemTag.SYSTEM_PROCESS_HANDLER.value)),
            ALL_NOTES("📚 All Notes", n -> !n.tags.contains(Netention.Note.SystemTag.CONFIG.value));
            private final String displayName;
            private final Predicate<Netention.Note> filter;

            View(String displayName, Predicate<Netention.Note> filter) {
                this.displayName = displayName;
                this.filter = filter;
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
        private final Netention.Core core;
        private final Runnable onSaveCb;
        private final InspectorPanel inspectorPanelRef;
        private final JTextField titleF = new JTextField(40);
        private final JTextPane contentPane = new JTextPane();
        private final HTMLEditorKit htmlEditorKit = new HTMLEditorKit();
        private final JTextField tagsF = new JTextField(40);
        private final JLabel embStatusL = new JLabel("Embedding: Unknown");
        private final JToolBar toolBar = new JToolBar();
        private final java.util.List<DocumentListener> activeDocumentListeners = new ArrayList<>();
        private Netention.Note currentNote;
        private boolean userModified = false;

        public NoteEditorPanel(Netention.Core core, Netention.Note note, Runnable onSaveCb, InspectorPanel inspectorPanelRef) {
            super(new BorderLayout(5, 5));
            setBorder(new EmptyBorder(5, 5, 5, 5));
            this.core = core;
            this.currentNote = note;
            this.onSaveCb = onSaveCb;
            this.inspectorPanelRef = inspectorPanelRef;
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
            var listener = new FieldUpdateListener(e -> this.userModified = true);
            titleF.getDocument().addDocumentListener(listener);
            tagsF.getDocument().addDocumentListener(listener);
            contentPane.getDocument().addDocumentListener(listener);
            activeDocumentListeners.add(listener);
        }

        private void setupToolbar() {
            toolBar.setFloatable(false);
            toolBar.setRollover(true);
            toolBar.setMargin(new Insets(2, 2, 2, 2));
            Stream.of(new StyledEditorKit.BoldAction(), new StyledEditorKit.ItalicAction(), new StyledEditorKit.UnderlineAction()).forEach(action -> {
                var btn = new JButton(action);
                var name = action.getClass().getSimpleName().replace("StyledEditorKit$", "").replace("Action", "");
                btn.setText(name.substring(0, 1));
                btn.setToolTipText(name);
                toolBar.add(btn);
            });
            toolBar.addSeparator();
            toolBar.add(UIUtil.button("💾", "Save", "Save Note", e -> saveNote(false)));
            toolBar.add(UIUtil.button("🚀", "Publish", "Save & Publish to Nostr", e -> saveNote(true)));
            toolBar.addSeparator();
            toolBar.add(UIUtil.button("🎯", "Set Goal", "Make this note a Goal/Plan", e -> {
                if (currentNote == null || isReadOnly()) return;
                updateNoteFromFields();
                if (!currentNote.tags.contains(Netention.Note.SystemTag.GOAL_WITH_PLAN.value)) {
                    currentNote.tags.add(Netention.Note.SystemTag.GOAL_WITH_PLAN.value);
                }
                if (core.lm.isReady() && currentNote.content.get(Netention.Note.ContentKey.PLAN_STEPS.getKey()) == null) {
                    try {
                        @SuppressWarnings("unchecked") var steps = (java.util.List<Netention.Planner.PlanStep>) core.executeTool(Netention.Core.Tool.SUGGEST_PLAN_STEPS, Map.of(Netention.Planner.ToolParam.GOAL_TEXT.getKey(), currentNote.getContentForEmbedding()));
                        if (steps != null && !steps.isEmpty())
                            currentNote.content.put(Netention.Note.ContentKey.PLAN_STEPS.getKey(), steps.stream().map(s -> core.json.convertValue(s, Map.class)).collect(Collectors.toList()));
                    } catch (Exception ex) {
                        logger.error("Failed to suggest plan steps: {}", ex.getMessage());
                    }
                }
                saveNote(false);
                if (inspectorPanelRef != null) inspectorPanelRef.switchToPlanTab();
            }));
            toolBar.addSeparator();
            var llmMenu = new JMenu("💡 LLM");
            llmMenu.setToolTipText("LLM Actions for this note");
            Stream.of(NoteEditorPanel.LLMAction.values()).forEach(actionEnum -> llmMenu.add(UIUtil.menuItem(actionEnum.name().replace("_", " "), "", actionEnum.name(), this::handleLLMActionFromToolbar)));
            toolBar.add(llmMenu);
        }

        @SuppressWarnings("unchecked")
        private void handleLLMActionFromToolbar(ActionEvent e) {
            if (currentNote == null || !core.lm.isReady() || isReadOnly()) {
                JOptionPane.showMessageDialog(this, "LLM not ready, no note, or read-only.", "LLM Action", JOptionPane.WARNING_MESSAGE);
                return;
            }
            updateNoteFromFields();
            var actionCommand = NoteEditorPanel.LLMAction.valueOf(e.getActionCommand());
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
                                inspectorPanelRef.loadRelatedNotes();
                                JOptionPane.showMessageDialog(this, "Embedding generated.", "💡 LLM", JOptionPane.INFORMATION_MESSAGE);
                            });
                        }, () -> SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Failed to generate embedding.", "LLM Error", JOptionPane.ERROR_MESSAGE)));
                        case SUMMARIZE -> core.lm.summarize(textContent).ifPresent(s -> {
                            currentNote.meta.put(Netention.Note.Metadata.LLM_SUMMARY.key, s);
                            core.saveNote(currentNote);
                            SwingUtilities.invokeLater(() -> {
                                inspectorPanelRef.displayLLMAnalysis();
                                JOptionPane.showMessageDialog(this, "Summary saved to metadata.", "💡 LLM", JOptionPane.INFORMATION_MESSAGE);
                            });
                        });
                        case ASK -> SwingUtilities.invokeLater(() -> {
                            var q = JOptionPane.showInputDialog(this, "Ask about note content:");
                            if (q != null && !q.trim().isEmpty())
                                CompletableFuture.supplyAsync(() -> core.lm.askAboutText(textContent, q)).thenAcceptAsync(answerOpt -> answerOpt.ifPresent(a -> JOptionPane.showMessageDialog(this, a, "Answer", JOptionPane.INFORMATION_MESSAGE)), SwingUtilities::invokeLater);
                        });
                        case DECOMPOSE ->
                                core.lm.decomposeTask(titleContent.isEmpty() ? textContent : titleContent).ifPresent(d -> {
                                    currentNote.meta.put(Netention.Note.Metadata.LLM_DECOMPOSITION.key, d);
                                    core.saveNote(currentNote);
                                    SwingUtilities.invokeLater(() -> {
                                        inspectorPanelRef.displayLLMAnalysis();
                                        JOptionPane.showMessageDialog(this, "Decomposition saved to metadata.", "💡 LLM", JOptionPane.INFORMATION_MESSAGE);
                                    });
                                });
                        case SUGGEST_PLAN -> {
                            var steps = (java.util.List<Netention.Planner.PlanStep>) core.executeTool(Netention.Core.Tool.SUGGEST_PLAN_STEPS, Map.of(Netention.Planner.ToolParam.GOAL_TEXT.getKey(), textContent));
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
                            } else
                                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "No plan steps suggested.", "💡 LLM", JOptionPane.INFORMATION_MESSAGE));
                        }
                    }
                } catch (Exception ex) {
                    logger.error("Error during LLM action '{}': {}", actionCommand, ex.getMessage(), ex);
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage(), "LLM Error", JOptionPane.ERROR_MESSAGE));
                }
            }).thenRunAsync(() -> populateFields(this.currentNote), SwingUtilities::invokeLater);
        }

        private boolean isReadOnly() {
            return currentNote != null && Stream.of(Netention.Note.SystemTag.NOSTR_FEED, Netention.Note.SystemTag.CONFIG, Netention.Note.SystemTag.SYSTEM_PROCESS_HANDLER, Netention.Note.SystemTag.SYSTEM_EVENT, Netention.Note.SystemTag.SYSTEM_NOTE, Netention.Note.SystemTag.NOSTR_RELAY).map(tag -> tag.value).anyMatch(currentNote.tags::contains);
        }

        private void populateFields(Netention.Note noteToDisplay) {
            this.currentNote = noteToDisplay;
            clearDocumentListeners();
            if (currentNote == null) {
                titleF.setText("");
                contentPane.setText("Select or create a note.");
                tagsF.setText("");
                Stream.of(titleF, contentPane, tagsF).forEach(c -> c.setEnabled(false));
                embStatusL.setText("No note loaded.");
            } else {
                titleF.setText(currentNote.getTitle());
                contentPane.setEditorKit(Netention.ContentType.TEXT_HTML.equals(currentNote.getContentTypeEnum()) ? htmlEditorKit : new StyledEditorKit());
                contentPane.setText(currentNote.getText());
                tagsF.setText(String.join(", ", currentNote.tags));
                updateEmbeddingStatus();
                var editable = !isReadOnly() && !currentNote.tags.contains(Netention.Note.SystemTag.MY_PROFILE.value);
                titleF.setEditable(editable);
                tagsF.setEditable(editable);
                contentPane.setEditable(!isReadOnly());
                Stream.of(titleF, contentPane, tagsF).forEach(c -> c.setEnabled(true));
            }
            userModified = false;
            setupDocumentListeners();
            updateServiceDependentButtonStates();
        }

        public void updateNonTextParts(Netention.Note note) {
            this.currentNote = note;
            updateEmbeddingStatus();
            updateServiceDependentButtonStates();
        }

        public boolean isUserModified() {
            return userModified;
        }

        private void updateEmbeddingStatus() {
            embStatusL.setText("Embedding: " + (currentNote != null && currentNote.getEmbeddingV1() != null ? "Generated (" + currentNote.getEmbeddingV1().length + "d)" : "N/A"));
        }

        public void updateServiceDependentButtonStates() {
            var editable = currentNote != null && !isReadOnly();
            var nostrReady = core.net.isEnabled() && core.net.getPrivateKeyBech32() != null && !core.net.getPrivateKeyBech32().isEmpty();
            for (var comp : toolBar.getComponents()) {
                if (comp instanceof JButton btn) {
                    var text = btn.getText();
                    var tooltip = btn.getToolTipText();
                    if ("💾Save".equals(text) || (tooltip != null && tooltip.contains("Save Note")) || "🎯Set Goal".equals(text) || (tooltip != null && tooltip.contains("Set Goal")))
                        btn.setEnabled(editable && currentNote != null);
                    else if (tooltip != null && tooltip.contains("Publish to Nostr"))
                        btn.setEnabled(editable && nostrReady && currentNote != null && !currentNote.tags.contains(Netention.Note.SystemTag.MY_PROFILE.value));
                } else if (comp instanceof JMenu menu && "💡 LLM".equals(menu.getText()))
                    menu.setEnabled(editable && core.lm.isReady() && currentNote != null);
            }
        }

        public Netention.Note getCurrentNote() {
            return currentNote;
        }

        public void updateNoteFromFields() {
            if (currentNote == null) currentNote = new Netention.Note();
            if (!currentNote.tags.contains(Netention.Note.SystemTag.MY_PROFILE.value)) {
                currentNote.setTitle(titleF.getText());
                currentNote.tags.clear();
                Stream.of(tagsF.getText().split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(currentNote.tags::add);
            }
            var d = contentPane.getDocument();
            if (Netention.ContentType.TEXT_HTML.getValue().equals(contentPane.getContentType())) {
                var sw = new StringWriter();
                try {
                    htmlEditorKit.write(sw, d, 0, d.getLength());
                    currentNote.setHtmlText(sw.toString());
                } catch (IOException | BadLocationException e) {
                    currentNote.setText(contentPane.getText());
                }
            } else try {
                currentNote.setText(d.getText(0, d.getLength()));
            } catch (BadLocationException e) {
                currentNote.setText(e.toString());
            }
        }

        private void saveNote(boolean andPublish) {
            if (currentNote != null && isReadOnly()) {
                JOptionPane.showMessageDialog(this, "Cannot save read-only items.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            updateNoteFromFields();
            var saved = core.saveNote(this.currentNote);
            if (saved != null) {
                this.currentNote = saved;
                userModified = false;
                if (andPublish) {
                    if (!currentNote.tags.contains(Netention.Note.SystemTag.MY_PROFILE.value)) {
                        if (core.net.isEnabled()) core.net.publishNote(this.currentNote);
                        else
                            JOptionPane.showMessageDialog(this, "Nostr not enabled. Note saved locally.", "Nostr Disabled", JOptionPane.WARNING_MESSAGE);
                    }
                }
                if (onSaveCb != null) onSaveCb.run();
                populateFields(this.currentNote);
            } else JOptionPane.showMessageDialog(this, "Failed to save note.", "Error", JOptionPane.ERROR_MESSAGE);
        }

        private enum LLMAction {EMBED, SUMMARIZE, ASK, DECOMPOSE, SUGGEST_PLAN}
    }

    public static class InspectorPanel extends JPanel {
        private final Netention.Core core;
        private final JTabbedPane tabbedPane;
        private final JPanel planPanel, actionableItemsPanel;
        private final JTextArea llmAnalysisArea;
        private final JLabel noteInfoLabel;
        private final JButton copyPubKeyButton;
        private final DefaultListModel<Netention.Link> linksListModel = new DefaultListModel<>();
        private final JList<Netention.Link> linksJList = new JList<>(linksListModel);
        private final DefaultListModel<Netention.Note> relatedNotesListModel = new DefaultListModel<>();
        private final JList<Netention.Note> relatedNotesJList = new JList<>(relatedNotesListModel);
        private final InspectorPanel.PlanStepsTableModel planStepsTableModel;
        private final DefaultTreeModel planDepsTreeModel;
        private final JTree planDepsTree;
        private final DefaultListModel<ActionableItem> actionableItemsListModel = new DefaultListModel<>();
        private final JList<ActionableItem> actionableItemsJList = new JList<>(actionableItemsListModel);
        private final UI uiRef;
        Netention.Note contextNote;

        public InspectorPanel(Netention.Core core, UI ui, Consumer<Netention.Note> noteDisplayCallback, Function<String, java.util.List<ActionableItem>> actionableItemsProvider) {
            super(new BorderLayout(5, 5));
            this.core = core;
            this.uiRef = ui;
            setPreferredSize(new Dimension(350, 0));
            tabbedPane = new JTabbedPane();

            var infoPanel = new JPanel(new BorderLayout());
            noteInfoLabel = new JLabel("No note selected.");
            noteInfoLabel.setBorder(new EmptyBorder(5, 5, 5, 5));
            copyPubKeyButton = UIUtil.button("📋", "Copy PubKey", "Copy Nostr Public Key (npub)", e -> {
                if (contextNote != null) {
                    if (contextNote.meta.containsKey(Netention.Note.Metadata.NOSTR_PUB_KEY.key)) {
                        var npub = (String) contextNote.meta.get(Netention.Note.Metadata.NOSTR_PUB_KEY.key);
                        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(npub), null);
                        uiRef.statusPanel.updateStatus("📋 Copied PubKey: " + npub.substring(0, 12) + "...");
                    }
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
            llmAnalysisArea.setBackground(getBackground().darker());
            infoPanel.add(new JScrollPane(llmAnalysisArea), BorderLayout.CENTER);
            tabbedPane.addTab(InspectorPanel.Tab.INFO.title, infoPanel);

            var linksPanel = new JPanel(new BorderLayout());
            linksJList.setCellRenderer(new InspectorPanel.LinkListCellRenderer(core));
            linksJList.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent evt) {
                    if (evt.getClickCount() == 2)
                        ofNullable(linksJList.getSelectedValue()).flatMap(l -> core.notes.get(l.targetNoteId)).ifPresent(noteDisplayCallback);
                }
            });
            linksPanel.add(new JScrollPane(linksJList), BorderLayout.CENTER);
            var linkButtonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            linkButtonsPanel.add(UIUtil.button("➕", "Add Link", "Add Link to another Note", e -> addLink()));
            linkButtonsPanel.add(UIUtil.button("➖", "Remove Link", "Remove Selected Link", e -> removeLink()));
            linksPanel.add(linkButtonsPanel, BorderLayout.SOUTH);
            tabbedPane.addTab(InspectorPanel.Tab.LINKS.title, linksPanel);

            var relatedNotesPanel = new JPanel(new BorderLayout());
            relatedNotesJList.setCellRenderer(new InspectorPanel.NoteTitleListCellRenderer());
            relatedNotesJList.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent evt) {
                    if (evt.getClickCount() == 2)
                        ofNullable(relatedNotesJList.getSelectedValue()).ifPresent(noteDisplayCallback);
                }
            });
            relatedNotesPanel.add(new JScrollPane(relatedNotesJList), BorderLayout.CENTER);
            tabbedPane.addTab(InspectorPanel.Tab.RELATED.title, relatedNotesPanel);

            planPanel = new JPanel(new BorderLayout());
            planStepsTableModel = new InspectorPanel.PlanStepsTableModel();
            var planStepsTable = new JTable(planStepsTableModel);
            planStepsTable.getColumnModel().getColumn(0).setPreferredWidth(150);
            planStepsTable.getColumnModel().getColumn(1).setPreferredWidth(50);
            planPanel.add(new JScrollPane(planStepsTable), BorderLayout.CENTER);
            planPanel.add(UIUtil.button("▶️", "Execute/Update Plan", "Execute or Update this Plan", e -> {
                if (contextNote != null) {
                    if (!contextNote.tags.contains(Netention.Note.SystemTag.GOAL_WITH_PLAN.value)) {
                        contextNote.tags.add(Netention.Note.SystemTag.GOAL_WITH_PLAN.value);
                    }
                    core.saveNote(contextNote);
                    core.planner.executePlan(contextNote);
                }
            }), BorderLayout.SOUTH);
            tabbedPane.addTab(InspectorPanel.Tab.PLAN.title, planPanel);

            var planDependenciesPanel = new JPanel(new BorderLayout());
            planDepsTreeModel = new DefaultTreeModel(new DefaultMutableTreeNode("Plan Dependencies"));
            planDepsTree = new JTree(planDepsTreeModel);
            planDepsTree.setRootVisible(false);
            planDepsTree.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        var node = (DefaultMutableTreeNode) planDepsTree.getLastSelectedPathComponent();
                        if (node != null && node.getUserObject() instanceof Map nodeData && nodeData.containsKey(Netention.Note.NoteProperty.ID.getKey()))
                            core.notes.get((String) nodeData.get(Netention.Note.NoteProperty.ID.getKey())).ifPresent(noteDisplayCallback);
                    }
                }
            });
            planDependenciesPanel.add(new JScrollPane(planDepsTree), BorderLayout.CENTER);
            tabbedPane.addTab(InspectorPanel.Tab.PLAN_DEPS.title, planDependenciesPanel);

            actionableItemsPanel = new JPanel(new BorderLayout());
            actionableItemsJList.setCellRenderer(new InspectorPanel.ActionableItemCellRenderer());
            actionableItemsJList.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2)
                        ofNullable(actionableItemsJList.getSelectedValue()).filter(item -> item.action() != null).ifPresent(item -> item.action().run());
                }
            });
            actionableItemsPanel.add(new JScrollPane(actionableItemsJList), BorderLayout.CENTER);
            tabbedPane.addTab(InspectorPanel.Tab.INBOX.title, actionableItemsPanel);
            add(tabbedPane, BorderLayout.CENTER);
            core.addCoreEventListener(event -> {
                if (!SwingUtilities.isEventDispatchThread()) {
                    SwingUtilities.invokeLater(() -> handleCoreEvent(event, actionableItemsProvider));
                    return;
                }
                handleCoreEvent(event, actionableItemsProvider);
            });
        }

        private void handleCoreEvent(Netention.Core.CoreEvent event, Function<String, java.util.List<ActionableItem>> actionableItemsProvider) {
            if (event.type() == Netention.Core.CoreEventType.PLAN_UPDATED && event.data() instanceof Netention.Planner.PlanExecution exec) {
                if (contextNote != null && contextNote.id.equals(exec.planNoteId)) {
                    planStepsTableModel.setPlanExecution(exec);
                    loadPlanDependencies();
                }
            } else if (event.type() == Netention.Core.CoreEventType.CONFIG_CHANGED) {
                updateServiceDependentButtonStates();
            } else if (event.type() == Netention.Core.CoreEventType.ACTIONABLE_ITEM_ADDED || event.type() == Netention.Core.CoreEventType.ACTIONABLE_ITEM_REMOVED) {
                loadActionableItems(actionableItemsProvider.apply(contextNote != null ? contextNote.id : null));
            }
        }

        public boolean isPlanViewActive() {
            return tabbedPane.getSelectedComponent() == planPanel && contextNote != null;
        }

        public boolean isActionItemsViewActiveAndNotEmpty() {
            return tabbedPane.getSelectedComponent() == actionableItemsPanel && contextNote != null && !actionableItemsListModel.isEmpty();
        }

        public void switchToPlanTab() {
            if (contextNote != null) {
                tabbedPane.setSelectedComponent(planPanel);
                core.planner.getPlanExecution(contextNote.id).ifPresentOrElse(planStepsTableModel::setPlanExecution, () -> planStepsTableModel.setPlanExecution(null));
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
                var isNostrContact = note.tags.contains(Netention.Note.SystemTag.NOSTR_CONTACT.value) || note.tags.contains(Netention.Note.SystemTag.NOSTR_FEED.value);
                if (isNostrContact && note.meta.containsKey(Netention.Note.Metadata.NOSTR_PUB_KEY.key)) {
                    var npub = (String) note.meta.get(Netention.Note.Metadata.NOSTR_PUB_KEY.key);
                    pubKeyInfo = "<br>PubKey: " + npub.substring(0, Math.min(npub.length(), 12)) + "...";
                    copyPubKeyButton.setVisible(true);
                } else copyPubKeyButton.setVisible(false);
                noteInfoLabel.setText(String.format("<html><b>%s</b><br>Tags: %s<br>Updated: %s%s</html>", title, tags, DateTimeFormatter.ISO_INSTANT.format(note.updatedAt.atZone(ZoneId.systemDefault())).substring(0, 19), pubKeyInfo));
                displayLLMAnalysis();
                loadLinks();
                loadRelatedNotes();
                loadPlanDependencies();
                core.planner.getPlanExecution(note.id).ifPresentOrElse(planStepsTableModel::setPlanExecution, () -> planStepsTableModel.setPlanExecution(null));
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
            if (contextNote != null && contextNote.links != null)
                contextNote.links.forEach(linksListModel::addElement);
        }

        private void addLink() {
            if (contextNote == null) return;
            var allNotes = core.notes.getAllNotes().stream().filter(n -> !n.id.equals(contextNote.id)).toList();
            if (allNotes.isEmpty()) {
                JOptionPane.showMessageDialog(this, "No other notes to link to.", "Add Link", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            var noteSelector = new JComboBox<>(allNotes.toArray(new Netention.Note[0]));
            var relationTypes = new String[]{"relates_to", "supports", "elaborates", "depends_on", "plan_subgoal_of", "plan_depends_on"};
            var relationTypeSelector = new JComboBox<>(relationTypes);
            if (contextNote.tags.contains(Netention.Note.SystemTag.GOAL_WITH_PLAN.value))
                relationTypeSelector.setSelectedItem("plan_subgoal_of");
            var panel = new JPanel(new GridLayout(0, 1));
            panel.add(new JLabel("Select target note:"));
            panel.add(noteSelector);
            panel.add(new JLabel("Relation type:"));
            panel.add(relationTypeSelector);
            if (JOptionPane.showConfirmDialog(this, panel, "Add Link", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
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
            if (JOptionPane.showConfirmDialog(this, "Remove link to '" + core.notes.get(selectedLink.targetNoteId).map(Netention.Note::getTitle).orElse("Unknown") + "'?", "Confirm Remove Link", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
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
            if (contextNote != null) {
                if (contextNote.tags.contains(Netention.Note.SystemTag.GOAL_WITH_PLAN.value)) {
                    try {
                        var graphContext = (Map<String, Object>) core.executeTool(Netention.Core.Tool.GET_PLAN_GRAPH_CONTEXT, Map.of(Netention.Planner.ToolParam.NOTE_ID.getKey(), contextNote.id));
                        if (graphContext != null && !graphContext.isEmpty()) {
                            var mainNode = new DefaultMutableTreeNode(Map.of(Netention.Note.NoteProperty.ID.getKey(), graphContext.get(Netention.Note.NoteProperty.ID.getKey()), Netention.Note.NoteProperty.TITLE.getKey(), "Self: " + graphContext.get(Netention.Note.NoteProperty.TITLE.getKey()), Netention.Note.ContentKey.STATUS.getKey(), graphContext.get(Netention.Note.ContentKey.STATUS.getKey())));
                            root.add(mainNode);
                            ((java.util.List<Map<String, Object>>) graphContext.getOrDefault("children", Collections.emptyList())).forEach(childData -> mainNode.add(new DefaultMutableTreeNode(childData)));
                            ((java.util.List<Map<String, Object>>) graphContext.getOrDefault("parents", Collections.emptyList())).forEach(parentData -> root.insert(new DefaultMutableTreeNode(parentData), 0));
                        }
                    } catch (Exception e) {
                        Netention.Core.logger.error("Failed to load plan dependencies graph: {}", e.getMessage());
                    }
                }
            }
            planDepsTreeModel.setRoot(root);
            IntStream.range(0, planDepsTree.getRowCount()).forEach(planDepsTree::expandRow);
        }

        private void loadActionableItems(java.util.List<ActionableItem> items) {
            actionableItemsListModel.clear();
            if (items != null) items.forEach(actionableItemsListModel::addElement);
            var inboxTabIndex = tabbedPane.indexOfTab(InspectorPanel.Tab.INBOX.title);
            if (inboxTabIndex != -1)
                tabbedPane.setForegroundAt(inboxTabIndex, (items != null && !items.isEmpty()) ? Color.ORANGE.darker() : UIManager.getColor("Label.foreground"));
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
                if (d instanceof java.util.List) {
                    sb.append("Task Decomposition:\n");
                    ((java.util.List<?>) d).forEach(i -> sb.append("- ").append(i).append("\n"));
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
                return this;
            }
        }

        static class ActionableItemCellRenderer extends DefaultListCellRenderer {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof ActionableItem item)
                    setText("<html><b>" + item.type() + ":</b> " + item.description() + "</html>");
                return this;
            }
        }

        static class PlanStepsTableModel extends AbstractTableModel {
            private final String[] columnNames = {"Description", "Status", "Tool", "Result"};
            private java.util.List<Netention.Planner.PlanStep> steps = new ArrayList<>();

            public void setPlanExecution(Netention.Planner.PlanExecution exec) {
                this.steps = (exec != null) ? new ArrayList<>(exec.steps) : new ArrayList<>();
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
                            step.result != null ? String.valueOf(step.result).substring(0, Math.min(String.valueOf(step.result).length(), 50)) : "";
                    default -> null;
                };
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

        public ChatPanel(Netention.Core core, Netention.Note note, String partnerNpub) {
            super(new BorderLayout(5, 5));
            setBorder(new EmptyBorder(10, 10, 10, 10));
            this.core = core;
            this.note = note;
            this.partnerNpub = partnerNpub;
            chatArea.setEditable(false);
            chatArea.setLineWrap(true);
            chatArea.setWrapStyleWord(true);
            add(new JScrollPane(chatArea), BorderLayout.CENTER);
            var inputP = new JPanel(new BorderLayout(5, 0));
            inputP.add(messageInput, BorderLayout.CENTER);
            inputP.add(UIUtil.button("➡️", "Send", "Send Message", e -> sendMessage()), BorderLayout.EAST);
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
            });
        }

        private void handleChatPanelCoreEvent(Netention.Core.CoreEvent event) {
            if (event.type() == Netention.Core.CoreEventType.CHAT_MESSAGE_ADDED && event.data() instanceof Map data && note.id.equals(data.get("chatNoteId")))
                SwingUtilities.invokeLater(this::loadMessages);
        }

        @SuppressWarnings("unchecked")
        private void loadMessages() {
            chatArea.setText("");
            core.notes.get(this.note.id).ifPresent(freshNote -> ((java.util.List<Map<String, String>>) freshNote.content.getOrDefault(Netention.Note.ContentKey.MESSAGES.getKey(), new ArrayList<>())).forEach(this::formatAndAppendMsg));
            scrollToBottom();
        }

        private void formatAndAppendMsg(Map<String, String> m) {
            var senderNpub = m.get("sender");
            var t = m.get("text");
            var ts = Instant.parse(m.get("timestamp"));
            var dn = senderNpub.equals(core.net.getPublicKeyBech32()) ? "Me" : senderNpub.substring(0, Math.min(senderNpub.length(), 8));
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
                var entry = Map.of("sender", core.net.getPublicKeyBech32(), "timestamp", Instant.now().toString(), "text", txt);
                @SuppressWarnings("unchecked") var msgs = (java.util.List<Map<String, String>>) currentChatNote.content.computeIfAbsent(Netention.Note.ContentKey.MESSAGES.getKey(), k -> new ArrayList<>());
                msgs.add(entry);
                core.saveNote(currentChatNote);
                messageInput.setText("");
            });
        }

        private void scrollToBottom() {
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
        }
    }

    public static class SettingsPanel extends JPanel {
        private final Netention.Core core;

        public SettingsPanel(Netention.Core core, Consumer<String> themeUpdater, Runnable llmInitCb) {
            super(new BorderLayout(10, 10));
            setBorder(new EmptyBorder(10, 10, 10, 10));
            this.core = core;
            var tabbedPane = new JTabbedPane();
            groupFieldsByAnnotation(core.cfg.net).forEach((group, fields) -> tabbedPane.addTab("Nostr 💜: " + group, buildConfigSubPanelFor(core.cfg.net, fields)));
            tabbedPane.addTab("Nostr 💜: Relays", buildNostrRelaysPanel());
            groupFieldsByAnnotation(core.cfg.ui).forEach((group, fields) -> tabbedPane.addTab("UI 🎨: " + group, buildConfigSubPanelFor(core.cfg.ui, fields)));
            groupFieldsByAnnotation(core.cfg.lm).forEach((group, fields) -> tabbedPane.addTab("LLM 💡: " + group, buildConfigSubPanelFor(core.cfg.lm, fields)));
            add(tabbedPane, BorderLayout.CENTER);
            var bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            bottomPanel.add(UIUtil.button("💾", "Save All Settings", "Save All Settings", e -> {
                core.cfg.saveAllConfigs();
                JOptionPane.showMessageDialog(this, "Settings save requests fired.", "Settings Saved", JOptionPane.INFORMATION_MESSAGE);
                if (themeUpdater != null) themeUpdater.accept(core.cfg.ui.theme);
                if (llmInitCb != null) llmInitCb.run();
            }));
            add(bottomPanel, BorderLayout.SOUTH);
        }

        private Map<String, java.util.List<Field>> groupFieldsByAnnotation(Object configObject) {
            return Stream.of(configObject.getClass().getDeclaredFields()).filter(f -> f.isAnnotationPresent(Netention.Field.class)).collect(Collectors.groupingBy(f -> f.getAnnotation(Netention.Field.class).group()));
        }

        private JComponent buildConfigSubPanelFor(Object configObject, java.util.List<Field> fields) {
            var panel = new JPanel(new GridBagLayout());
            var gbc = new GridBagConstraints();
            gbc.insets = new Insets(4, 4, 4, 4);
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.anchor = GridBagConstraints.WEST;
            final var pubKeyLabelHolder = new JLabel[1];
            for (var i = 0; i < fields.size(); i++) {
                var field = fields.get(i);
                var cf = field.getAnnotation(Netention.Field.class);
                UIUtil.addLabelAndComponent(panel, gbc, i, cf.label() + ":", createEditorComponent(field, configObject, cf, pubKeyLabelHolder));
                if (field.getName().equals("privateKeyBech32") && configObject instanceof Netention.Config.NostrSettings nostrSettings) {
                    gbc.gridx = 1;
                    gbc.gridy = gbc.gridy + 1;
                    panel.add(pubKeyLabelHolder[0], gbc);
                    gbc.gridy = gbc.gridy + 1;
                    gbc.anchor = GridBagConstraints.EAST;
                    panel.add(UIUtil.button("🔑", "Generate New Keys", "Generate New Nostr Keys", evt -> {
                        if (JOptionPane.showConfirmDialog(panel, "Generate new Nostr keys & overwrite? Backup existing!", "Confirm", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
                            core.cfg.generateNewNostrKeysAndUpdateConfig();
                            ((JTextComponent) UIUtil.byName(panel, field.getName())).setText(nostrSettings.privateKeyBech32);
                            pubKeyLabelHolder[0].setText("Public Key (npub): " + nostrSettings.publicKeyBech32);
                            var kda = new JTextArea("nsec: " + nostrSettings.privateKeyBech32 + "\nnpub: " + nostrSettings.publicKeyBech32, 5, 50);
                            kda.setEditable(false);
                            JOptionPane.showMessageDialog(panel, new JScrollPane(kda), "New Keys (Backup!)", JOptionPane.INFORMATION_MESSAGE);
                        }
                    }), gbc);
                    gbc.anchor = GridBagConstraints.WEST;
                }
            }
            gbc.gridy = gbc.gridy + 1;
            gbc.weighty = 1.0;
            panel.add(new JPanel(), gbc);
            return new JScrollPane(panel);
        }

        private JComponent buildNostrRelaysPanel() {
            var panel = new JPanel(new BorderLayout(5, 5));
            var listModel = new DefaultListModel<Netention.Note>();
            core.notes.getAll(n -> n.tags.contains(Netention.Note.SystemTag.NOSTR_RELAY.value)).forEach(listModel::addElement);
            var relayList = getList(listModel);
            panel.add(new JScrollPane(relayList), BorderLayout.CENTER);
            var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttonPanel.add(UIUtil.button("➕", "Add", "Add New Relay", e -> editRelayNote(null, listModel)));
            buttonPanel.add(UIUtil.button("✏️", "Edit", "Edit Selected Relay", e -> ofNullable(relayList.getSelectedValue()).ifPresent(val -> editRelayNote(val, listModel))));
            buttonPanel.add(UIUtil.button("➖", "Remove", "Remove Selected Relay", e -> ofNullable(relayList.getSelectedValue()).ifPresent(sel -> {
                if (JOptionPane.showConfirmDialog(panel, "Delete relay " + sel.content.get(Netention.Note.ContentKey.RELAY_URL.getKey()) + "?", "Confirm Delete", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    core.deleteNote(sel.id);
                    listModel.removeElement(sel);
                }
            })));
            panel.add(buttonPanel, BorderLayout.SOUTH);
            return panel;
        }

        private @NotNull JList<Netention.Note> getList(DefaultListModel<Netention.Note> listModel) {
            var relayList = new JList<>(listModel);
            relayList.setCellRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int i, boolean sel, boolean foc) {
                    super.getListCellRendererComponent(list, value, i, sel, foc);
                    if (value instanceof Netention.Note n)
                        setText(n.content.getOrDefault(Netention.Note.ContentKey.RELAY_URL.getKey(), "N/A") + ((Boolean) n.content.getOrDefault(Netention.Note.ContentKey.RELAY_ENABLED.getKey(), true) ? "" : " (Disabled)"));
                    return this;
                }
            });
            return relayList;
        }

        private void editRelayNote(Netention.Note relayNote, DefaultListModel<Netention.Note> listModel) {
            var isNew = relayNote == null;
            var urlField = new JTextField(isNew ? "wss://" : (String) relayNote.content.getOrDefault(Netention.Note.ContentKey.RELAY_URL.getKey(), "wss://"), 30);
            var enabledCheck = new JCheckBox("Enabled", isNew || (Boolean) relayNote.content.getOrDefault(Netention.Note.ContentKey.RELAY_ENABLED.getKey(), true));
            var readCheck = new JCheckBox("Read (Subscribe)", isNew || (Boolean) relayNote.content.getOrDefault(Netention.Note.ContentKey.RELAY_READ.getKey(), true));
            var writeCheck = new JCheckBox("Write (Publish)", isNew || (Boolean) relayNote.content.getOrDefault(Netention.Note.ContentKey.RELAY_WRITE.getKey(), true));
            var formPanel = new JPanel(new GridLayout(0, 1, 5, 5));
            formPanel.add(new JLabel("Relay URL:"));
            formPanel.add(urlField);
            formPanel.add(enabledCheck);
            formPanel.add(readCheck);
            formPanel.add(writeCheck);
            if (JOptionPane.showConfirmDialog(this, formPanel, (isNew ? "Add" : "Edit") + " Relay", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                var url = urlField.getText().trim();
                if (url.isEmpty()) return;
                var noteToSave = isNew ? new Netention.Note("Relay: " + url, "") : relayNote;
                if (isNew) {
                    noteToSave.tags.add(Netention.Note.SystemTag.NOSTR_RELAY.value);
                }
                noteToSave.content.putAll(Map.of(Netention.Note.ContentKey.RELAY_URL.getKey(), url, Netention.Note.ContentKey.RELAY_ENABLED.getKey(), enabledCheck.isSelected(), Netention.Note.ContentKey.RELAY_READ.getKey(), readCheck.isSelected(), Netention.Note.ContentKey.RELAY_WRITE.getKey(), writeCheck.isSelected()));
                core.saveNote(noteToSave);
                if (isNew) listModel.addElement(noteToSave);
                else listModel.setElementAt(noteToSave, listModel.indexOf(relayNote));
            }
        }

        @SuppressWarnings("unchecked")
        private JComponent createEditorComponent(Field field, Object configObj, Netention.Field cf, JLabel[] pubKeyLabelHolder) {
            try {
                field.setAccessible(true);
                var val = field.get(configObj);
                JComponent comp;
                switch (cf.type()) {
                    case TEXT_AREA -> {
                        var ta = new JTextArea(3, 30);
                        if (val instanceof java.util.List) ta.setText(String.join("\n", (java.util.List<String>) val));
                        else if (val != null) ta.setText(val.toString());
                        ta.getDocument().addDocumentListener(new FieldUpdateListener(e -> {
                            try {
                                field.set(configObj, new ArrayList<>(List.of(ta.getText().split("\\n"))));
                            } catch (IllegalAccessException ex) {
                            }
                        }));
                        comp = new JScrollPane(ta);
                    }
                    case COMBO_BOX -> {
                        var cb = new JComboBox<>(cf.choices());
                        if (val != null) cb.setSelectedItem(val.toString());
                        cb.addActionListener(e -> {
                            try {
                                field.set(configObj, cb.getSelectedItem());
                            } catch (IllegalAccessException ex) {
                            }
                        });
                        comp = cb;
                    }
                    case CHECK_BOX -> {
                        var chkbx = new JCheckBox();
                        if (val instanceof Boolean) chkbx.setSelected((Boolean) val);
                        chkbx.addActionListener(e -> {
                            try {
                                field.set(configObj, chkbx.isSelected());
                            } catch (IllegalAccessException ex) {
                            }
                        });
                        comp = chkbx;
                    }
                    case PASSWORD_FIELD -> {
                        var pf = new JPasswordField(30);
                        if (val != null) pf.setText(val.toString());
                        pf.getDocument().addDocumentListener(new FieldUpdateListener(e -> {
                            try {
                                field.set(configObj, new String(pf.getPassword()));
                            } catch (IllegalAccessException ex) {
                            }
                        }));
                        comp = pf;
                    }
                    default -> {
                        var tf = new JTextField(30);
                        if (val != null) tf.setText(val.toString());
                        tf.getDocument().addDocumentListener(new FieldUpdateListener(e -> {
                            try {
                                field.set(configObj, tf.getText());
                            } catch (IllegalAccessException ex) {
                            }
                        }));
                        comp = tf;
                    }
                }
                if (field.getName().equals("privateKeyBech32") && configObj instanceof Netention.Config.NostrSettings ns && comp instanceof JTextComponent tc) {
                    pubKeyLabelHolder[0] = new JLabel("Public Key (npub): " + ns.publicKeyBech32);
                    tc.getDocument().addDocumentListener(new FieldUpdateListener(de -> {
                        try {
                            var pkNsec = tc.getText();
                            if (pkNsec != null && !pkNsec.trim().isEmpty()) {
                                ns.publicKeyBech32 = Crypto.Bech32.nip19Encode("npub", Crypto.getPublicKeyXOnly(Crypto.Bech32.nip19Decode(pkNsec)));
                            } else ns.publicKeyBech32 = "Enter nsec to derive";
                        } catch (Exception ex) {
                            ns.publicKeyBech32 = "Invalid nsec";
                        }
                        pubKeyLabelHolder[0].setText("Public Key (npub): " + ns.publicKeyBech32);
                    }));
                }
                if (!cf.tooltip().isEmpty()) comp.setToolTipText(cf.tooltip());
                comp.setName(field.getName());
                return comp;
            } catch (IllegalAccessException e) {
                return new JLabel("Error: " + e.getMessage());
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
            Stream.of(label, new JSeparator(SwingConstants.VERTICAL), nostrStatusLabel, new JSeparator(SwingConstants.VERTICAL), llmStatusLabel, new JSeparator(SwingConstants.VERTICAL), systemHealthLabel).forEach(this::add);
            updateStatus("🚀 Application ready.");
            core.addCoreEventListener(e -> {
                if (e.type() == Netention.Core.CoreEventType.CONFIG_CHANGED || e.type() == Netention.Core.CoreEventType.STATUS_MESSAGE)
                    updateStatus(e.type() == Netention.Core.CoreEventType.STATUS_MESSAGE && e.data() instanceof String ? (String) e.data() : label.getText().replaceFirst(".*?: ", "").split(" \\| ")[0]);
            });
            new Timer(15000, e -> {
                try {
                    @SuppressWarnings("unchecked") var m = (Map<String, Object>) core.executeTool(Netention.Core.Tool.GET_SYSTEM_HEALTH_METRICS, Collections.emptyMap());
                    systemHealthLabel.setText(String.format("🩺 Evts:%s Plans:%s Fails:%s", m.getOrDefault("pendingSystemEvents", "?"), m.getOrDefault("activePlans", "?"), m.getOrDefault("failedPlanStepsInActivePlans", "?")));
                } catch (Exception ex) {
                    systemHealthLabel.setText("🩺 Health: Error");
                }
            }) {{
                setInitialDelay(1000);
                start();
            }};
        }

        public void updateStatus(String message) {
            SwingUtilities.invokeLater(() -> {
                label.setText("ℹ️ " + message);
                nostrStatusLabel.setText("💜 Nostr: " + (core.net.isEnabled() ? "ON" : "OFF"));
                nostrStatusLabel.setForeground(core.net.isEnabled() ? Color.GREEN.darker() : Color.RED);
                llmStatusLabel.setText("💡 LLM: " + (core.lm.isReady() ? "READY" : "NOT READY"));
                llmStatusLabel.setForeground(core.lm.isReady() ? Color.GREEN.darker() : Color.RED);
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
        static JButton button(String text, String emoji, String tooltip, ActionListener listener) {
            var button = new JButton(text + (emoji.isEmpty() ? "" : " " + emoji));
            if (tooltip != null && !tooltip.isEmpty()) button.setToolTipText(tooltip);
            if (listener != null) button.addActionListener(listener);
            return button;
        }

        static JMenuItem menuItem(String text, String emoji, String actionCommand, ActionListener listener) {
            var item = new JMenuItem(text + (emoji.isEmpty() ? "" : " " + emoji));
            if (actionCommand != null) item.setActionCommand(actionCommand);
            if (listener != null) item.addActionListener(listener);
            return item;
        }

        static void addLabelAndComponent(JPanel panel, GridBagConstraints gbc, int y, String labelText, Component component) {
            gbc.gridx = 0;
            gbc.gridy = y;
            gbc.weightx = 0.0;
            panel.add(new JLabel(labelText), gbc);
            gbc.gridx = 1;
            gbc.gridy = y;
            gbc.weightx = 1.0;
            panel.add(component, gbc);
        }

        static Component byName(Container container, String name) {
            for (var comp : container.getComponents()) {
                if (name.equals(comp.getName()))
                    return comp;
                if (comp instanceof Container subContainer) {
                    var found = byName(subContainer, name);
                    if (found != null)
                        return found;
                }
            }
            return null;
        }
    }
}
