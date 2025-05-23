package dumb.note.ui;

import dumb.note.Netention;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.InternalFrameAdapter;
import javax.swing.event.InternalFrameEvent;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

public class App extends UI.BaseAppFrame {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

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

            UI.BaseAppFrame appInstance = new App(core);
            appInstance.setVisible(true);
        });
    }


    final UI.NavPanel navPanel;
    final JSplitPane mainSplitPane;
    final JSplitPane contentInspectorSplit;
    final UI.InspectorPanel inspectorPanel;
    final UI.StatusPanel statusPanel;
    final JDesktopPane desktopPane;
    private final Map<String, BaseInternalFrame> openFramesByNoteId = new ConcurrentHashMap<>();
    private final List<BaseInternalFrame> openUnsavedFrames = Collections.synchronizedList(new ArrayList<>());

    private TrayIcon trayIcon;
    private SystemTray tray;

    public App(Netention.Core core) {
        super(core, "Netention ✨ (MDI)", 1380, 860);

        remove(super.statusBarLabel);
        remove(super.defaultEditorHostPanel);

        statusPanel = new UI.StatusPanel(core);
        add(statusPanel, BorderLayout.SOUTH);

        desktopPane = new JDesktopPane();
        desktopPane.setBackground(UIManager.getColor("control") != null ? UIManager.getColor("control").darker() : Color.DARK_GRAY);

        inspectorPanel = new UI.InspectorPanel(core, this, this::display, this::getActionableItemsForNote);
        // This part of NEW_FROM_TEMPLATE needs to be in the action itself or callable
        navPanel = new UI.NavPanel(core, this, this::display, this::display, this::display,
                () -> actionRegistry.get(UI.ActionID.NEW_NOTE).actionPerformed(null),
                this::createNewNoteFromTemplate);


        contentInspectorSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, desktopPane, inspectorPanel);
        contentInspectorSplit.setResizeWeight(0.75);
        contentInspectorSplit.setOneTouchExpandable(true);

        mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, navPanel, contentInspectorSplit);
        mainSplitPane.setDividerLocation(280);
        mainSplitPane.setOneTouchExpandable(true);

        add(mainSplitPane, BorderLayout.CENTER);

        populateActions();
        setJMenuBar(createMenuBarFull());

        core.addCoreEventListener(this::handleCoreEvent);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowIconified(WindowEvent e) {
                if (core.cfg.ui.minimizeToTray && tray != null) setVisible(false);
            }
        });

        desktopPane.addPropertyChangeListener("selectedFrame", evt -> {
            BaseInternalFrame activeFrame = null;
            if (evt.getNewValue() instanceof BaseInternalFrame bif) {
                activeFrame = bif;
                inspectorPanel.setContextNote(bif.getAssociatedNote());
            } else {
                inspectorPanel.setContextNote(null);
            }
            updateAppTitleWithActiveFrame(activeFrame);
            updateActionStates();
        });

        initSystemTray();
        inspectorPanel.setVisible(false); // Initial state
        contentInspectorSplit.setDividerLocation(1.0); // Initial state
        updateActionStates(); // Initial action states
    }

    @Override
    protected Optional<UI.DirtyableSavableComponent> getActiveDirtyableSavableComponent() {
        return getActiveInternalFrame().map(BaseInternalFrame::getMainPanel)
                .filter(UI.DirtyableSavableComponent.class::isInstance)
                .map(UI.DirtyableSavableComponent.class::cast);
    }

    @Override
    protected void deleteActiveNote() {
        getActiveInternalFrame().ifPresent(frame -> {
            var noteToDelete = frame.getAssociatedNote();
            if (noteToDelete != null && JOptionPane.showConfirmDialog(this, "🗑️ Delete note '" + noteToDelete.getTitle() + "'?", "Confirm Delete", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                core.deleteNote(noteToDelete.id);
                try {
                    frame.setClosed(true);
                } catch (java.beans.PropertyVetoException ex) {
                    logger.warn("Vetoed closing deleted note frame", ex);
                }
            }
        });
    }


    private void updateAppTitleWithActiveFrame(@Nullable App.BaseInternalFrame activeFrame) {
        if (activeFrame != null && activeFrame.getAssociatedNote() != null) {
            var noteTitle = activeFrame.getAssociatedNote().getTitle();
            if (noteTitle == null || noteTitle.isEmpty()) noteTitle = "Untitled";
            setTitle(baseTitle + " - [" + noteTitle + (activeFrame.isPanelDirty() ? " *" : "") + "]");
        } else {
            setTitle(baseTitle);
        }
    }

    @Override
    protected Optional<Netention.Note> getCurrentEditedNote() {
        return getActiveInternalFrame().map(BaseInternalFrame::getAssociatedNote);
    }

    @Override
    protected Optional<JTextComponent> getActiveTextComponent() {
        return getActiveInternalFrame().flatMap(bif -> {
            if (bif.getMainPanel() instanceof UI.NoteEditorPanel nep) return Optional.of(nep.contentPane);
            return Optional.empty();
        });
    }

    @Override
    protected void updateStatus(String message) {
        if (statusPanel != null) statusPanel.updateStatus(message);
        else super.updateStatus(message);
    }

    public Optional<BaseInternalFrame> getActiveInternalFrame() {
        return ofNullable(desktopPane.getSelectedFrame()).filter(BaseInternalFrame.class::isInstance).map(BaseInternalFrame.class::cast);
    }

    private void populateActions() {
        actionRegistry.register(UI.ActionID.NEW_NOTE, new UI.AppAction("New Note", KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK), e -> createNewNote()));
        actionRegistry.register(UI.ActionID.NEW_FROM_TEMPLATE, new UI.AppAction("New Note from Template...", KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), e -> {
            var templates = core.notes.getAll(n -> n.tags.contains(Netention.SystemTag.TEMPLATE.value));
            if (templates.isEmpty()) {
                JOptionPane.showMessageDialog(this, "No templates found...", "🤷 No Templates", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            var selectedTemplate = (Netention.Note) JOptionPane.showInputDialog(this, "Select a template:", "📄 New from Template", JOptionPane.PLAIN_MESSAGE, null, templates.toArray(), templates.stream().findFirst().orElse(null));
            if (selectedTemplate != null) createNewNoteFromTemplate(selectedTemplate);
        }));
        actionRegistry.register(UI.ActionID.EXIT, new UI.AppAction("Exit", e -> handleWindowClose()));

        actionRegistry.register(UI.ActionID.CUT, new UI.AppAction("Cut", KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK), e -> getActiveTextComponent().ifPresent(JTextComponent::cut))
                .setEnabledCalculator(() -> getActiveTextComponent().isPresent()));
        actionRegistry.register(UI.ActionID.COPY, new UI.AppAction("Copy", KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), e -> getActiveTextComponent().ifPresent(JTextComponent::copy))
                .setEnabledCalculator(() -> getActiveTextComponent().isPresent()));
        actionRegistry.register(UI.ActionID.PASTE, new UI.AppAction("Paste", KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), e -> getActiveTextComponent().ifPresent(JTextComponent::paste))
                .setEnabledCalculator(() -> getActiveTextComponent().isPresent()));

        actionRegistry.register(UI.ActionID.TOGGLE_INSPECTOR, new UI.AppAction("Toggle Inspector Panel", KeyStroke.getKeyStroke(KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), e -> {
            boolean show = !(e.getSource() instanceof JCheckBoxMenuItem) || ((JCheckBoxMenuItem) e.getSource()).isSelected(); // If not from checkbox, assume toggle
            if (e.getSource() instanceof JCheckBoxMenuItem cbmi) show = cbmi.isSelected();

            inspectorPanel.setVisible(show);
            contentInspectorSplit.setDividerLocation(show ? contentInspectorSplit.getResizeWeight() : contentInspectorSplit.getWidth() - contentInspectorSplit.getDividerSize());
            contentInspectorSplit.revalidate();
        }).setSelectedCalculator(inspectorPanel::isVisible));

        actionRegistry.register(UI.ActionID.TOGGLE_NAV_PANEL, new UI.AppAction("Toggle Navigation Panel", KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), e -> {
            boolean show = !(e.getSource() instanceof JCheckBoxMenuItem) || ((JCheckBoxMenuItem) e.getSource()).isSelected();
            if (e.getSource() instanceof JCheckBoxMenuItem cbmi) show = cbmi.isSelected();
            navPanel.setVisible(show);
            mainSplitPane.setDividerLocation(show ? navPanel.getPreferredSize().width : 0);
            mainSplitPane.revalidate();
        }).setSelectedCalculator(navPanel::isVisible));

        actionRegistry.register(UI.ActionID.SAVE_NOTE, new UI.AppAction("Save Note", KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), e -> saveActiveDirtyableSavableComponent(false))
                .setEnabledCalculator(() -> getActiveDirtyableSavableComponent().map(UI.DirtyableSavableComponent::isDirty).orElse(false)));

        actionRegistry.register(UI.ActionID.PUBLISH_NOTE, new UI.AppAction("Publish Note (Nostr)", KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK), e -> saveActiveDirtyableSavableComponent(true))
                .setEnabledCalculator(() -> core.net.isEnabled() && getActiveDirtyableSavableComponent().map(dsc -> dsc.isDirty() || dsc.getAssociatedNote() != null).orElse(false)));

        actionRegistry.register(UI.ActionID.SET_GOAL, new UI.AppAction("Convert to/Edit Goal", e ->
                getActiveInternalFrame().ifPresent(f -> {
                    if (f.getMainPanel() instanceof UI.NoteEditorPanel nep && nep.getCurrentNote() != null)
                        Stream.of(nep.toolBar.getComponents()).filter(c -> c instanceof JButton && "🎯".equals(((JButton) c).getText())).findFirst().map(JButton.class::cast).ifPresent(JButton::doClick);
                })
        ).setEnabledCalculator(() -> getActiveInternalFrame().map(f -> f.getMainPanel() instanceof UI.NoteEditorPanel).orElse(false)));

        actionRegistry.register(UI.ActionID.DELETE_NOTE, new UI.AppAction("Delete Note", KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), e -> deleteActiveNote())
                .setEnabledCalculator(() -> getActiveInternalFrame().flatMap(f -> ofNullable(f.getAssociatedNote())).isPresent()));

        actionRegistry.register(UI.ActionID.MY_PROFILE, new UI.AppAction("My Nostr Profile", e ->
                ofNullable(core.cfg.net.myProfileNoteId).filter(id -> !id.isEmpty()).flatMap(core.notes::get)
                        .ifPresentOrElse(this::display,
                                () -> JOptionPane.showMessageDialog(this, "My Profile note ID not configured or note not found.", "👤 Profile Error", JOptionPane.ERROR_MESSAGE)))
                .setEnabledCalculator(() -> core.cfg.net.myProfileNoteId != null && !core.cfg.net.myProfileNoteId.isEmpty()));

        actionRegistry.register(UI.ActionID.PUBLISH_PROFILE, new UI.AppAction("Publish My Profile", e ->
                ofNullable(core.cfg.net.myProfileNoteId).filter(id -> !id.isEmpty()).flatMap(core.notes::get).ifPresentOrElse(profileNote -> {
                    core.net.publishProfile(profileNote);
                    JOptionPane.showMessageDialog(this, "Profile publish request sent.", "🚀 Nostr Profile", JOptionPane.INFORMATION_MESSAGE);
                }, () -> JOptionPane.showMessageDialog(this, "My Profile note ID not configured or note not found.", "👤 Profile Error", JOptionPane.ERROR_MESSAGE)))
                .setEnabledCalculator(() -> core.net.isEnabled() && core.cfg.net.myProfileNoteId != null && !core.cfg.net.myProfileNoteId.isEmpty()));

        actionRegistry.register(UI.ActionID.TOGGLE_NOSTR, new UI.AppAction("Enable Nostr Connection", e -> {
            boolean wantsEnable = false; // Determine based on source if it's a JCheckBoxMenuItem
            if (e.getSource() instanceof JCheckBoxMenuItem cbmi) wantsEnable = cbmi.isSelected();
            else wantsEnable = !core.net.isEnabled(); // Toggle if not from checkbox

            String statusMsg;
            if (wantsEnable) {
                if (core.cfg.net.privateKeyBech32 == null || core.cfg.net.privateKeyBech32.isEmpty()) {
                    JOptionPane.showMessageDialog(this, "Nostr private key (nsec) not configured...", "Nostr Configuration Needed", JOptionPane.WARNING_MESSAGE);
                    statusMsg = "Nostr setup required.";
                } else {
                    core.net.setEnabled(true);
                    statusMsg = core.net.isEnabled() ? "Nostr enabled." : "Nostr enabling failed.";
                    JOptionPane.showMessageDialog(this, statusMsg, "💜 Nostr Status", core.net.isEnabled() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE);
                }
            } else {
                core.net.setEnabled(false);
                statusMsg = "Nostr disabled by user.";
                JOptionPane.showMessageDialog(this, statusMsg, "💜 Nostr Status", JOptionPane.INFORMATION_MESSAGE);
            }
            statusPanel.updateStatus(statusMsg);
            core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, "nostr_status_changed");
            updateActionStates(); // This will update the JCheckBoxMenuItem's selected state via setSelectedCalculator
        }).setSelectedCalculator(core.net::isEnabled));

        actionRegistry.register(UI.ActionID.ADD_NOSTR_FRIEND, new UI.AppAction("Add Nostr Contact...", e -> UIUtil.addNostrContactDialog(this, core, npub -> statusPanel.updateStatus("Friend " + npub.substring(0, 10) + "... added."))));
        actionRegistry.register(UI.ActionID.MANAGE_RELAYS, new UI.AppAction("Manage Relays...", e -> navPanel.selectViewAndNote(UI.NavPanel.View.SETTINGS, "config.nostr_relays")));
        actionRegistry.register(UI.ActionID.LLM_SETTINGS, new UI.AppAction("LLM Service Status/Settings", e -> navPanel.selectViewAndNote(UI.NavPanel.View.SETTINGS, "config.llm")));
        actionRegistry.register(UI.ActionID.SYNC_ALL, new UI.AppAction("Synchronize/Refresh All", e -> {
            core.fireCoreEvent(Netention.Core.CoreEventType.STATUS_MESSAGE, "🔄 Syncing all...");
            if (core.net.isEnabled()) core.net.requestSync();
            navPanel.refreshNotes();
            JOptionPane.showMessageDialog(this, "Synchronization requested.", "🔄 Sync All", JOptionPane.INFORMATION_MESSAGE);
        }));
        actionRegistry.register(UI.ActionID.ABOUT, new UI.AppAction("About Netention", e -> JOptionPane.showMessageDialog(this, "Netention ✨ (MDI App)\nVersion: (dev)\nYour awesome note-taking and Nostr app!", "ℹ️ About Netention", JOptionPane.INFORMATION_MESSAGE)));

        actionRegistry.register(UI.ActionID.CASCADE_WINDOWS, new UI.AppAction("Cascade", e -> cascadeFrames()).setEnabledCalculator(() -> desktopPane.getAllFrames().length > 0));
        actionRegistry.register(UI.ActionID.TILE_WINDOWS_HORIZONTALLY, new UI.AppAction("Tile Horizontally", e -> tileFramesHorizontally()).setEnabledCalculator(() -> desktopPane.getAllFrames().length > 0));
        actionRegistry.register(UI.ActionID.TILE_WINDOWS_VERTICALLY, new UI.AppAction("Tile Vertically", e -> tileFramesVertically()).setEnabledCalculator(() -> desktopPane.getAllFrames().length > 0));
        actionRegistry.register(UI.ActionID.CLOSE_ACTIVE_WINDOW, new UI.AppAction("Close Active Window", KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK), e -> getActiveInternalFrame().ifPresent(f -> {
            try {
                f.setClosed(true);
            } catch (Exception ex) {
                logger.debug("Close active window vetoed/failed", ex);
            }
        })).setEnabledCalculator(() -> getActiveInternalFrame().isPresent()));
        actionRegistry.register(UI.ActionID.CLOSE_ALL_WINDOWS, new UI.AppAction("Close All Windows", KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), e -> Stream.of(desktopPane.getAllFrames()).forEach(f -> {
            try {
                f.setClosed(true);
            } catch (Exception ex) {
                logger.debug("Close all windows - one frame vetoed/failed", ex);
            }
        })).setEnabledCalculator(() -> desktopPane.getAllFrames().length > 0));
    }

    public List<UI.ActionableItem> getActionableItemsForNote(String noteId) {
        return Collections.emptyList();
    }

    private void handleCoreEvent(Netention.Core.CoreEvent event) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> handleCoreEvent(event));
            return;
        }
        super.handleCoreEventBase(event); // Handles base status, theme, and calls updateActionStates for CONFIG_CHANGED

        switch (event.type()) {
            case NOTE_UPDATED -> {
                if (event.data() instanceof Netention.Note newNoteData) {
                    ofNullable(openFramesByNoteId.get(newNoteData.id))
                            .ifPresent(frame -> frame.handleExternalUpdate(newNoteData));
                    if (inspectorPanel.contextNote != null && newNoteData.id.equals(inspectorPanel.contextNote.id)) {
                        inspectorPanel.setContextNote(newNoteData);
                    }
                }
                if (navPanel != null) navPanel.refreshNotes();
            }
            case NOTE_DELETED -> {
                if (event.data() instanceof String deletedNoteId) {
                    ofNullable(openFramesByNoteId.get(deletedNoteId)).ifPresent(frame -> {
                        try {
                            frame.setClosed(true);
                        } catch (java.beans.PropertyVetoException e) {
                            logger.warn("Vetoed closing deleted note frame", e);
                        }
                    });
                }
                if (navPanel != null) navPanel.refreshNotes();
            }
            case CHAT_MESSAGE_ADDED, NOTE_ADDED -> {
                if (navPanel != null) navPanel.refreshNotes();
                if (event.type() == Netention.Core.CoreEventType.CHAT_MESSAGE_ADDED && event.data() instanceof Map<?, ?> msgDataMap) {
                    if (msgDataMap.get("chatNoteId") instanceof String chatNoteId) {
                        ofNullable(openFramesByNoteId.get(chatNoteId))
                                .filter(ChatInternalFrame.class::isInstance).map(ChatInternalFrame.class::cast)
                                .ifPresent(ChatInternalFrame::refreshMessages);
                    }
                }
            }
            case CONFIG_CHANGED -> { // Already handled by super.handleCoreEventBase for updateActionStates
                navPanel.updateServiceDependentButtonStates();
                Stream.of(desktopPane.getAllFrames())
                        .filter(BaseInternalFrame.class::isInstance).map(BaseInternalFrame.class::cast)
                        .forEach(BaseInternalFrame::updateServiceDependentButtonStatesInPanel);
                inspectorPanel.updateServiceDependentButtonStates();
                statusPanel.updateStatus("⚙️ Configuration reloaded/changed.");
            }
            default -> { /* No specific action */ }
        }
        updateActionStates(); // Ensure actions are up-to-date after any event
    }

    @Override
    protected void handleWindowClose() {
        List<JInternalFrame> frames = new ArrayList<>(List.of(desktopPane.getAllFrames()));
        for (var frame : frames) {
            if (frame instanceof BaseInternalFrame bif) {
                if (!canSwitchOrCloseContent(false, bif)) {
                    return;
                }
                try {
                    bif.setClosed(true);
                } catch (java.beans.PropertyVetoException e) {
                    return;
                }
            }
        }
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

        trayIcon = new TrayIcon(image, "Netention ✨", createTrayPopupMenu());
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(_ -> restoreWindow());
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
        var openItem = new MenuItem("✨ Open Netention");
        openItem.addActionListener(_ -> restoreWindow());
        trayMenu.add(openItem);

        var quickAddItem = new MenuItem("➕ Quick Add Note");
        quickAddItem.addActionListener(_ -> {
            restoreWindow();
            actionRegistry.get(UI.ActionID.NEW_NOTE).actionPerformed(null);
        });
        trayMenu.add(quickAddItem);
        trayMenu.addSeparator();

        var exitItem = new MenuItem("🚪 Exit");
        exitItem.addActionListener(_ -> {
            restoreWindow();
            actionRegistry.get(UI.ActionID.EXIT).actionPerformed(null);
        });
        trayMenu.add(exitItem);
        return trayMenu;
    }

    private void restoreWindow() {
        setVisible(true);
        setState(JFrame.NORMAL);
        toFront();
        requestFocus();
    }

    public void display(@Nullable Netention.Note noteToDisplay) {
        if (noteToDisplay == null) {
            if (desktopPane.getSelectedFrame() == null) inspectorPanel.setContextNote(null);
            updateActionStates();
            return;
        }

        var noteId = noteToDisplay.id;
        if (noteId != null && openFramesByNoteId.containsKey(noteId)) {
            var frame = openFramesByNoteId.get(noteId);
            try {
                if (frame.isIcon()) frame.setIcon(false);
                frame.setSelected(true);
                if (noteToDisplay != frame.getAssociatedNote() && !frame.isPanelDirty()) {
                    frame.populatePanel(noteToDisplay);
                }
            } catch (java.beans.PropertyVetoException ex) {
                logger.warn("Could not select frame for note {}: {}", noteId, ex.getMessage());
            }
            updateActionStates();
            return;
        }

        if (noteId == null) {
            for (var frame : openUnsavedFrames) {
                if (frame.getAssociatedNote() == noteToDisplay) {
                    try {
                        if (frame.isIcon()) frame.setIcon(false);
                        frame.setSelected(true);
                        updateActionStates();
                        return;
                    } catch (java.beans.PropertyVetoException ex) { /* continue */ }
                }
            }
        }

        Class<? extends BaseInternalFrame> frameClass;
        String titlePrefix;
        if (noteToDisplay.tags.contains(Netention.SystemTag.CHAT.value)) {
            frameClass = ChatInternalFrame.class;
            titlePrefix = "💬 ";
        } else if (noteToDisplay.tags.contains(Netention.SystemTag.CONFIG.value)) {
            frameClass = ConfigInternalFrame.class;
            titlePrefix = "⚙️ ";
        } else {
            frameClass = NoteInternalFrame.class;
            titlePrefix = "📝 ";
        }
        openWindowForNote(noteToDisplay, frameClass, titlePrefix);
        updateActionStates();
    }

    private <T extends BaseInternalFrame> void openWindowForNote(Netention.Note note, Class<T> frameClass, String titlePrefix) {
        try {
            Constructor<T> constructor = frameClass.getDeclaredConstructor(App.class, Netention.Core.class, Netention.Note.class, String.class);
            BaseInternalFrame frame = constructor.newInstance(this, core, note, titlePrefix);
            desktopPane.add(frame);
            frame.setVisible(true);

            if (note.id == null) openUnsavedFrames.add(frame);
            else openFramesByNoteId.put(note.id, frame);

            frame.setSelected(true); // This will trigger property change listener, which calls updateActionStates
        } catch (Exception ex) {
            logger.error("Error creating MDI window for note '{}': {}", note.getTitle(), ex.getMessage(), ex);
            JOptionPane.showMessageDialog(this, "Error opening note: " + ex.getMessage(), "MDI Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    void handleInternalFrameDisposal(BaseInternalFrame frame) {
        if (frame.getAssociatedNoteId() != null) openFramesByNoteId.remove(frame.getAssociatedNoteId());
        else openUnsavedFrames.remove(frame);

        if (desktopPane.getSelectedFrame() == null) {
            inspectorPanel.setContextNote(null);
            updateAppTitleWithActiveFrame(null);
        }
        updateActionStates();
    }

    void handleNewNoteIdAssigned(BaseInternalFrame frame, String newNoteId) {
        openUnsavedFrames.remove(frame);
        openFramesByNoteId.put(newNoteId, frame);
    }

    public void createNewNote() {
        display(new Netention.Note("Untitled", ""));
    }

    public void createNewNoteFromTemplate(Netention.Note templateNote) {
        var newNote = new Netention.Note(
                templateNote.getTitle().replaceFirst("\\[Template\\]", "[New]").replaceFirst(Netention.SystemTag.TEMPLATE.value, "").trim(),
                templateNote.getText()
        );
        newNote.tags.addAll(templateNote.tags.stream().filter(t -> !Netention.SystemTag.TEMPLATE.value.equals(t)).toList());
        if (Netention.ContentType.TEXT_HTML.equals(templateNote.getContentTypeEnum())) {
            newNote.setHtmlText(templateNote.getText());
        }
        display(newNote);
    }

    private void updateThemeAndRestartMessage(String themeName) {
        updateTheme(themeName);
        JOptionPane.showMessageDialog(this, "🎨 Theme changed to " + themeName + ". Some L&F changes may require restart.", "Theme Changed", JOptionPane.INFORMATION_MESSAGE);
    }

    private JMenuBar createMenuBarFull() {
        var mb = new JMenuBar();
        var fileMenu = new JMenu("File 📁");
        fileMenu.add(createMenuItem(UI.ActionID.NEW_NOTE));
        fileMenu.add(createMenuItem(UI.ActionID.NEW_FROM_TEMPLATE));
        fileMenu.addSeparator();
        fileMenu.add(createMenuItem(UI.ActionID.EXIT));
        mb.add(fileMenu);

        var editMenu = new JMenu("Edit ✏️");
        editMenu.add(createMenuItem("✂️ Cut", UI.ActionID.CUT));
        editMenu.add(createMenuItem("📋 Copy", UI.ActionID.COPY));
        editMenu.add(createMenuItem("📝 Paste", UI.ActionID.PASTE));
        mb.add(editMenu);

        mb.add(createViewMenu());

        var noteMenu = new JMenu("Note 📝");
        noteMenu.add(createMenuItem("💾 Save Note", UI.ActionID.SAVE_NOTE));
        noteMenu.add(createMenuItem("🚀 Publish Note (Nostr)", UI.ActionID.PUBLISH_NOTE));
        noteMenu.add(createMenuItem("🎯 Convert to/Edit Goal", UI.ActionID.SET_GOAL));
        noteMenu.addSeparator();
        noteMenu.add(createMenuItem("🗑️ Delete Note", UI.ActionID.DELETE_NOTE));
        mb.add(noteMenu);

        var windowMenu = new JMenu("Window 🖼️");
        windowMenu.add(createMenuItem("🖼️ Cascade", UI.ActionID.CASCADE_WINDOWS));
        windowMenu.add(createMenuItem("↔️ Tile Horizontally", UI.ActionID.TILE_WINDOWS_HORIZONTALLY));
        windowMenu.add(createMenuItem("↕️ Tile Vertically", UI.ActionID.TILE_WINDOWS_VERTICALLY));
        windowMenu.addSeparator();
        windowMenu.add(createMenuItem("✖️ Close Active Window", UI.ActionID.CLOSE_ACTIVE_WINDOW));
        windowMenu.add(createMenuItem("❌ Close All Windows", UI.ActionID.CLOSE_ALL_WINDOWS));
        mb.add(windowMenu);

        var nostrMenu = new JMenu("Nostr 💜");
        nostrMenu.add(createCheckBoxMenuItem(UI.ActionID.TOGGLE_NOSTR));
        nostrMenu.add(createMenuItem("👤 My Nostr Profile", UI.ActionID.MY_PROFILE));
        nostrMenu.add(createMenuItem("🚀 Publish My Profile", UI.ActionID.PUBLISH_PROFILE));
        nostrMenu.add(createMenuItem("➕ Add Nostr Contact...", UI.ActionID.ADD_NOSTR_FRIEND));
        nostrMenu.add(createMenuItem("📡 Manage Relays...", UI.ActionID.MANAGE_RELAYS));
        mb.add(nostrMenu);

        var toolsMenu = new JMenu("Tools 🛠️");
        toolsMenu.add(createMenuItem("💡 LLM Service Status/Settings", UI.ActionID.LLM_SETTINGS));
        toolsMenu.add(createMenuItem("🔄 Synchronize/Refresh All", UI.ActionID.SYNC_ALL));
        mb.add(toolsMenu);

        var helpMenu = new JMenu("Help ❓");
        helpMenu.add(createMenuItem("ℹ️ About Netention", UI.ActionID.ABOUT));
        mb.add(helpMenu);

        return mb;
    }

    private JMenu createViewMenu() {
        var viewMenu = new JMenu("View 👁️");
        viewMenu.add(createCheckBoxMenuItem(UI.ActionID.TOGGLE_INSPECTOR));
        viewMenu.add(createCheckBoxMenuItem(UI.ActionID.TOGGLE_NAV_PANEL));
        viewMenu.addSeparator();

        var themesMenu = new JMenu("🎨 Themes");
        var themeGroup = new ButtonGroup();
        Stream.of("System Default", "Nimbus (Dark)").forEach(themeName ->
                themesMenu.add(createRadioButtonMenuItem(themeName, themeName.equals(core.cfg.ui.theme), themeGroup, _ -> {
                    core.cfg.ui.theme = themeName;
                    updateThemeAndRestartMessage(themeName);
                    core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, "ui_theme_updated");
                }))
        );
        viewMenu.add(themesMenu);
        return viewMenu;
    }

    private void cascadeFrames() {
        var frames = desktopPane.getAllFrames();
        if (frames.length == 0) return;
        int x = 0, y = 0;
        var frameOffset = Math.min(30, Math.min(desktopPane.getWidth(), desktopPane.getHeight()) / Math.max(1, frames.length * 2));

        Arrays.sort(frames, Comparator.comparingInt(f -> -desktopPane.getComponentZOrder(f)));

        for (var frame : frames) {
            if (!frame.isIcon()) {
                try {
                    frame.setMaximum(false);
                    var prefSize = frame.getPreferredSize();
                    var frameWidth = Math.min(prefSize.width, desktopPane.getWidth() - x);
                    var frameHeight = Math.min(prefSize.height, desktopPane.getHeight() - y);
                    if (frameWidth <= 0) frameWidth = desktopPane.getWidth() / 2;
                    if (frameHeight <= 0) frameHeight = desktopPane.getHeight() / 2;

                    frame.setBounds(x, y, frameWidth, frameHeight);
                    x += frameOffset;
                    y += frameOffset;
                    if (x + frameOffset > desktopPane.getWidth() || y + frameOffset > desktopPane.getHeight()) {
                        x = 0;
                        y = 0;
                    }
                } catch (java.beans.PropertyVetoException e) { /* ignore */ }
            }
        }
    }

    private void tileFrames(boolean horizontal) {
        var frames = Arrays.stream(desktopPane.getAllFrames())
                .filter(f -> !f.isIcon()).toArray(JInternalFrame[]::new);
        if (frames.length == 0) return;

        var desktopSize = desktopPane.getSize();
        var frameWidth = horizontal ? desktopSize.width / frames.length : desktopSize.width;
        var frameHeight = horizontal ? desktopSize.height : desktopSize.height / frames.length;
        int currentX = 0, currentY = 0;

        for (var frame : frames) {
            try {
                frame.setMaximum(false);
                frame.setBounds(currentX, currentY, frameWidth, frameHeight);
                if (horizontal) currentX += frameWidth;
                else currentY += frameHeight;
            } catch (java.beans.PropertyVetoException e) { /* ignore */ }
        }
    }

    private void tileFramesHorizontally() {
        tileFrames(true);
    }

    private void tileFramesVertically() {
        tileFrames(false);
    }

    abstract static class BaseInternalFrame extends JInternalFrame {
        protected final App mdiApp;
        protected final Netention.Core core;
        protected final String titlePrefix;
        protected final JComponent mainPanel;
        protected Netention.Note currentNote;
        private String originalNoteIdForNewNote;

        public BaseInternalFrame(App mdiApp, Netention.Core core, Netention.Note note, String titlePrefix, String frameTitle) {
            super(frameTitle, true, true, true, true);
            this.mdiApp = mdiApp;
            this.core = core;
            this.currentNote = note;
            this.titlePrefix = titlePrefix;
            this.originalNoteIdForNewNote = note.id;

            this.mainPanel = createMainPanel();
            setContentPane(this.mainPanel);
            pack();
            setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            addInternalFrameListener(new InternalFrameAdapter() {
                @Override
                public void internalFrameClosing(InternalFrameEvent e) {
                    if (mdiApp.canSwitchOrCloseContent(false, BaseInternalFrame.this)) {
                        dispose();
                    }
                }

                @Override
                public void internalFrameClosed(InternalFrameEvent e) {
                    mdiApp.handleInternalFrameDisposal(BaseInternalFrame.this);
                }

                @Override
                public void internalFrameActivated(InternalFrameEvent e) {
                    mdiApp.updateAppTitleWithActiveFrame(BaseInternalFrame.this);
                    mdiApp.updateActionStates();
                }

                @Override
                public void internalFrameDeactivated(InternalFrameEvent e) {
                    mdiApp.updateActionStates();
                }
            });
            updateFrameTitle();
            setLocation(getNextFrameLocation());
        }

        private Point getNextFrameLocation() {
            var frames = mdiApp.desktopPane.getAllFrames();
            if (frames.length <= 1) return new Point(5, 5);

            Point lastLocation = Arrays.stream(frames)
                    .filter(f -> f != this)
                    .max(Comparator.comparingInt(mdiApp.desktopPane::getComponentZOrder))
                    .map(Component::getLocation)
                    .orElse(new Point(5, 5));

            var offset = 25;
            var x = (lastLocation.x + offset);
            var y = (lastLocation.y + offset);

            var desktopSize = mdiApp.desktopPane.getSize();
            if (x + getWidth() > desktopSize.width || x < 0) x = 5;
            if (y + getHeight() > desktopSize.height || y < 0) y = 5;
            return new Point(x, y);
        }

        protected abstract JComponent createMainPanel();

        public abstract boolean isPanelDirty();

        public abstract void savePanel(boolean andPublish);

        public abstract void populatePanel(Netention.Note note);

        public JComponent getMainPanel() {
            return mainPanel;
        }

        public Netention.Note getAssociatedNote() {
            return currentNote;
        }

        public String getAssociatedNoteId() {
            return ofNullable(currentNote).map(n -> n.id).orElse(null);
        }

        public void updateFrameTitle() {
            var title = (currentNote != null && currentNote.getTitle() != null && !currentNote.getTitle().isEmpty()) ? currentNote.getTitle() : "Untitled";
            setTitle(titlePrefix + title + (isPanelDirty() ? " *" : ""));
            if (isSelected()) mdiApp.updateAppTitleWithActiveFrame(this);
        }

        protected void onPanelDirtyStateChanged(boolean isDirty) {
            updateFrameTitle();
            mdiApp.updateActionStates();
        }

        public void updateServiceDependentButtonStatesInPanel() {
            if (mainPanel instanceof UI.NoteEditorPanel nep) nep.updateServiceDependentButtonStates();
        }

        public void handleExternalUpdate(Netention.Note newNoteData) {
            if (isPanelDirty()) {
                if (mainPanel instanceof UI.NoteEditorPanel nep) nep.setExternallyUpdated(true);
            } else {
                this.currentNote = newNoteData;
                populatePanel(newNoteData);
                updateFrameTitle();
            }
        }

        protected void notifyNoteIdAssigned(String newNoteId) {
            if (originalNoteIdForNewNote == null && newNoteId != null) {
                this.originalNoteIdForNewNote = newNoteId;
                mdiApp.handleNewNoteIdAssigned(this, newNoteId);
            }
        }
    }

    static class NoteInternalFrame extends BaseInternalFrame {
        private UI.NoteEditorPanel noteEditorPanel;

        public NoteInternalFrame(App mdiApp, Netention.Core core, Netention.Note note, String titlePrefix) {
            super(mdiApp, core, note, titlePrefix, titlePrefix + (note.getTitle() != null ? note.getTitle() : "Untitled"));
        }

        @Override
        protected JComponent createMainPanel() {
            noteEditorPanel = new UI.NoteEditorPanel(core, currentNote, mdiApp,
                    () -> {
                        this.currentNote = noteEditorPanel.getCurrentNote();
                        if (currentNote.id != null && getAssociatedNoteId() == null) {
                            notifyNoteIdAssigned(currentNote.id);
                        }
                        updateFrameTitle();
                        mdiApp.statusPanel.updateStatus(currentNote == null || currentNote.id == null ? "📝 Note created" : "💾 Note saved: " + currentNote.getTitle());
                        mdiApp.inspectorPanel.setContextNote(currentNote);
                    },
                    mdiApp.inspectorPanel,
                    this::onPanelDirtyStateChanged
            );
            return noteEditorPanel;
        }

        @Override
        public boolean isPanelDirty() {
            return noteEditorPanel != null && noteEditorPanel.isDirty();
        }

        @Override
        public void savePanel(boolean andPublish) {
            if (noteEditorPanel != null) noteEditorPanel.saveNote(andPublish);
        }

        @Override
        public void populatePanel(Netention.Note note) {
            if (noteEditorPanel != null) noteEditorPanel.populateFields(note);
        }
    }

    static class ChatInternalFrame extends BaseInternalFrame {
        private UI.ChatPanel chatPanel;

        public ChatInternalFrame(App mdiApp, Netention.Core core, Netention.Note note, String titlePrefix) {
            super(mdiApp, core, note, titlePrefix, titlePrefix + (note.getTitle() != null ? note.getTitle() : "Untitled"));
            if (note.id != null && getAssociatedNoteId() == null) {
                notifyNoteIdAssigned(note.id);
            }
        }

        @Override
        protected JComponent createMainPanel() {
            if (!(currentNote.meta.get(Netention.Metadata.NOSTR_PUB_KEY.key) instanceof String partnerNpub)) {
                var errorLabel = new JLabel("Chat partner PK (npub) not found in note metadata.");
                errorLabel.setHorizontalAlignment(SwingConstants.CENTER);
                return errorLabel;
            }
            chatPanel = new UI.ChatPanel(core, currentNote, partnerNpub, mdiApp.statusPanel::updateStatus);
            return chatPanel;
        }

        @Override
        public boolean isPanelDirty() {
            return false;
        }

        @Override
        public void savePanel(boolean andPublish) { /* No explicit save action from frame */ }

        @Override
        public void populatePanel(Netention.Note note) {
            if (chatPanel != null) chatPanel.loadMessages();
        }

        public void refreshMessages() {
            if (chatPanel != null) chatPanel.loadMessages();
        }
    }

    static class ConfigInternalFrame extends BaseInternalFrame {
        private UI.ConfigNoteEditorPanel configEditorPanel;

        public ConfigInternalFrame(App mdiApp, Netention.Core core, Netention.Note note, String titlePrefix) {
            super(mdiApp, core, note, titlePrefix, titlePrefix + (note.getTitle() != null ? note.getTitle() : "Untitled"));
            if (note.id != null && getAssociatedNoteId() == null) {
                notifyNoteIdAssigned(note.id);
            }
        }

        @Override
        protected JComponent createMainPanel() {
            configEditorPanel = new UI.ConfigNoteEditorPanel(core, currentNote,
                    () -> {
                        mdiApp.statusPanel.updateStatus("⚙️ Configuration potentially updated: " + currentNote.getTitle());
                        mdiApp.navPanel.updateServiceDependentButtonStates();
                        core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, "config_note_saved_mdi:" + currentNote.id);
                        updateFrameTitle();
                    },
                    this::onPanelDirtyStateChanged
            );
            return configEditorPanel;
        }

        @Override
        public boolean isPanelDirty() {
            return configEditorPanel != null && configEditorPanel.isDirty();
        }

        @Override
        public void savePanel(boolean andPublish) {
            if (configEditorPanel != null) configEditorPanel.saveChanges();
        }

        @Override
        public void populatePanel(Netention.Note note) {
            if (configEditorPanel != null) configEditorPanel.refreshFieldsFromConfig();
        }
    }
}
