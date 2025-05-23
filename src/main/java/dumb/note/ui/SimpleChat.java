package dumb.note.ui;

import dumb.note.Netention;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.swing.*;
import java.awt.*;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

public class SimpleChat extends UI.BaseAppFrame {
    private final UI.BuddyListPanel buddyPanel;
    private final List<UI.ActionableItem> actionableItems = new CopyOnWriteArrayList<>();
    private final ReentrantLock actionableItemsLock = new ReentrantLock();
    private JMenuItem inboxMenuItem;
    private Clip notificationSoundClip;

    public SimpleChat(Netention.Core core) {
        super(core, "SimpleChat ✨", 900, 700);
        var contentPanelContainer = super.defaultEditorHostPanel;

        try (InputStream is = getClass().getResourceAsStream("/notification.wav");
             BufferedInputStream bis = new BufferedInputStream(is)) {
            notificationSoundClip = AudioSystem.getClip();
            notificationSoundClip.open(AudioSystem.getAudioInputStream(bis));
        } catch (Exception e) {
            Netention.Core.logger.error("Error loading notification sound: {}", e.getMessage());
            notificationSoundClip = null;
        }

        buddyPanel = new UI.BuddyListPanel(this, this::displayContentForIdentifier, this::showMyProfileEditor);

        var splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buddyPanel, contentPanelContainer);
        splitPane.setDividerLocation(250);

        remove(super.defaultEditorHostPanel);
        add(splitPane, BorderLayout.CENTER);

        populateActions();
        setJMenuBar(createSimpleChatMenuBar());
        core.addCoreEventListener(this::handleSimpleChatCoreEvent);

        setEditorComponent(new JLabel("Select a chat or contact.", SwingConstants.CENTER));

        if (core.cfg.net.privateKeyBech32 != null && !core.cfg.net.privateKeyBech32.isEmpty()) {
            core.net.setEnabled(true);
        }
        updateActionStates();
    }

    public static void main(String[] ignoredArgs) {
        SwingUtilities.invokeLater(() -> new SimpleChat(new Netention.Core()).setVisible(true));
    }

    @Override
    protected Optional<UI.DirtyableSavableComponent> getActiveDirtyableSavableComponent() {
        return Optional.empty();
    }

    private void populateActions() {
        actionRegistry.register(UI.ActionID.EXIT, new UI.AppAction("Exit", e -> handleWindowClose()));
        actionRegistry.register(UI.ActionID.TOGGLE_NOSTR, new UI.AppAction("Enable Nostr Connection", e -> {
            boolean wantsEnable = false;
            if (e.getSource() instanceof JCheckBoxMenuItem cbmi) wantsEnable = cbmi.isSelected();
            else wantsEnable = !core.net.isEnabled();

            if (wantsEnable && (core.cfg.net.privateKeyBech32 == null || core.cfg.net.privateKeyBech32.isEmpty())) {
                JOptionPane.showMessageDialog(this, "Nostr private key (nsec) not configured. Please configure it via Nostr -> Configure Identity.", "Nostr Configuration Needed", JOptionPane.WARNING_MESSAGE);
            } else {
                core.net.setEnabled(wantsEnable);
            }
            updateStatusBasedOnNostrState();
            core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, "nostr_status_changed_simple_chat");
            updateActionStates();
        }).setSelectedCalculator(core.net::isEnabled));

        actionRegistry.register(UI.ActionID.SHOW_MY_NOSTR_PROFILE_EDITOR, new UI.AppAction("My Profile...", e -> showMyProfileEditor()));
        actionRegistry.register(UI.ActionID.ADD_NOSTR_FRIEND, new UI.AppAction("Add Nostr Contact...", e -> UIUtil.addNostrContactDialog(this, core, this::displayContentForIdentifier)));
        actionRegistry.register(UI.ActionID.MANAGE_NOSTR_RELAYS_POPUP, new UI.AppAction("Manage Relays...", e -> manageNostrRelays()));
        actionRegistry.register(UI.ActionID.CONFIGURE_NOSTR_IDENTITY_POPUP, new UI.AppAction("Configure Identity...", e -> configureNostrIdentity()));
        actionRegistry.register(UI.ActionID.ABOUT, new UI.AppAction("About SimpleChat", e ->
                JOptionPane.showMessageDialog(this, "SimpleChat ✨\nA basic Nostr IM client.", "About SimpleChat", JOptionPane.INFORMATION_MESSAGE)
        ));
        actionRegistry.register(UI.ActionID.SHOW_INBOX, new UI.AppAction("Inbox", e -> showInboxDialog()));
        actionRegistry.register(UI.ActionID.VIEW_CONTACT_PROFILE, new UI.AppAction("View Profile", e -> {
            Object selected = buddyPanel.buddies.getSelectedValue();
            if (selected instanceof Netention.Note note && note.tags.contains(Netention.SystemTag.CONTACT.value)) {
                displayContentForIdentifier(note.id); // Show profile in main panel
            }
        }));
    }

    private void handleSimpleChatCoreEvent(Netention.Core.CoreEvent event) {
        super.handleCoreEventBase(event);
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> handleSimpleChatCoreEvent(event));
            return;
        }
        switch (event.type()) {
            case CHAT_MESSAGE_ADDED -> {
                if (event.data() instanceof Map<?,?> data && data.get("chatNoteId") instanceof String chatNoteId) {
                    core.notes.get(chatNoteId).ifPresent(chatNote -> {
                        // Only increment unread count if the chat is not currently open
                        if (!(getEditorHostPanel().getComponentCount() > 0 && getEditorHostPanel().getComponent(0) instanceof UI.ChatPanel currentChatPanel &&
                                currentChatPanel.getChatNote().id.equals(chatNoteId))) {
                            int unreadCount = (Integer) chatNote.meta.getOrDefault(Netention.Metadata.UNREAD_MESSAGES_COUNT.key, 0);
                            chatNote.meta.put(Netention.Metadata.UNREAD_MESSAGES_COUNT.key, unreadCount + 1);
                            core.saveNote(chatNote);

                            if (!isActive() && notificationSoundClip != null) {
                                if (notificationSoundClip.isRunning()) notificationSoundClip.stop();
                                notificationSoundClip.setFramePosition(0);
                                notificationSoundClip.start();
                                toFront();
                                requestFocus();
                            }
                        }
                    });
                }
                SwingUtilities.invokeLater(buddyPanel::refreshList);
            }
            case NOTE_ADDED, NOTE_UPDATED, NOTE_DELETED ->
                    SwingUtilities.invokeLater(buddyPanel::refreshList);
            case CONFIG_CHANGED -> {
                updateStatusBasedOnNostrState();
                buddyPanel.refreshList();
            }
            case ACTIONABLE_ITEM_ADDED -> {
                if (event.data() instanceof UI.ActionableItem item) {
                    actionableItemsLock.lock();
                    try {
                        actionableItems.add(item);
                    } finally {
                        actionableItemsLock.unlock();
                    }
                    updateInboxMenuItemText();
                }
            }
            case ACTIONABLE_ITEM_REMOVED -> {
                if (event.data() instanceof String itemId) {
                    actionableItemsLock.lock();
                    try {
                        actionableItems.removeIf(item -> item.id().equals(itemId));
                    } finally {
                        actionableItemsLock.unlock();
                    }
                    updateInboxMenuItemText();
                }
            }
            default -> {}
        }
        updateActionStates();
    }

    private void updateStatusBasedOnNostrState() {
        updateStatus(core.net.isEnabled() ? "Nostr Connected" : "Nostr Disconnected");
    }

    private void displayContentForIdentifier(String identifier) {
        if (!canSwitchOrCloseContent(false, null)) return;

        // 1. Try to load as a Chat Note
        Optional<Netention.Note> chatNoteOpt = core.notes.get(identifier)
                .filter(n -> n.tags.contains(Netention.SystemTag.CHAT.value));

        if (chatNoteOpt.isPresent()) {
            Netention.Note chatNote = chatNoteOpt.get();
            if (chatNote.meta.get(Netention.Metadata.NOSTR_PUB_KEY.key) instanceof String partnerNpub) {
                setEditorComponent(new UI.ChatPanel(core, chatNote, partnerNpub, this::updateStatus));
                if ((Integer) chatNote.meta.getOrDefault(Netention.Metadata.UNREAD_MESSAGES_COUNT.key, 0) > 0) {
                    chatNote.meta.put(Netention.Metadata.UNREAD_MESSAGES_COUNT.key, 0);
                    core.saveNote(chatNote);
                    buddyPanel.refreshList(); // Refresh to clear unread count
                }
                updateStatus("Viewing chat with " + chatNote.getTitle());
            } else {
                setEditorComponent(new JLabel("Error: Chat partner not found in note metadata.", SwingConstants.CENTER));
                updateStatus("Error: Chat partner not found for " + chatNote.getTitle());
            }
            return;
        }

        // 2. Try to load as a Contact Note (or resolve npub to contact)
        Optional<Netention.Note> contactNoteOpt;
        if (identifier.startsWith("npub1")) {
            // If identifier is an npub, find the corresponding contact note
            contactNoteOpt = core.notes.getAll(n -> n.tags.contains(Netention.SystemTag.CONTACT.value) &&
                    identifier.equals(n.meta.get(Netention.Metadata.NOSTR_PUB_KEY.key)))
                    .stream().findFirst();
        } else {
            // If identifier is a note ID, check if it's a contact note
            contactNoteOpt = core.notes.get(identifier)
                    .filter(n -> n.tags.contains(Netention.SystemTag.CONTACT.value));
        }

        if (contactNoteOpt.isPresent()) {
            Netention.Note contactNote = contactNoteOpt.get();
            // Display contact profile in the main panel
            var profileEditor = new ProfileEditorPanel(core, contactNote, isDirty -> {}); // No dirty listener needed for read-only view
            profileEditor.setReadOnlyMode(true);
            setEditorComponent(profileEditor);
            updateStatus("Viewing profile: " + contactNote.getTitle());
            return;
        }

        // 3. If nothing found, show a generic message
        setEditorComponent(new JLabel("Content not found for: " + identifier, SwingConstants.CENTER));
        updateStatus("Content not found for " + identifier);
    }

    private void showMyProfileEditor() {
        if (!canSwitchOrCloseContent(false, null)) return;
        var myProfileNoteId = core.cfg.net.myProfileNoteId;
        Netention.Note profileNote;

        if (myProfileNoteId == null || myProfileNoteId.isEmpty()) {
            int choice = JOptionPane.showConfirmDialog(this, "Your Nostr profile note ID is not configured. Would you like to generate new keys and create a profile now?", "👤 Profile Setup", JOptionPane.YES_NO_CANCEL_OPTION);
            if (choice == JOptionPane.YES_OPTION) {
                String keyInfo = core.cfg.generateNewNostrKeysAndUpdateConfig();
                myProfileNoteId = core.cfg.net.myProfileNoteId; // Get the newly set ID
                profileNote = core.notes.get(myProfileNoteId).orElse(null);
                if (profileNote == null) {
                    JOptionPane.showMessageDialog(this, "Failed to create profile note after key generation.", "👤 Profile Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                updateStatus("Generated new Nostr keys and created profile note.");
            } else {
                JOptionPane.showMessageDialog(this, "Nostr profile setup cancelled.", "👤 Profile Setup", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
        } else {
            profileNote = core.notes.get(myProfileNoteId).orElse(null);
            if (profileNote == null) {
                int choice = JOptionPane.showConfirmDialog(this, "Your configured profile note (ID: " + myProfileNoteId + ") was not found. Would you like to create a new one?", "👤 Profile Error", JOptionPane.YES_NO_OPTION);
                if (choice == JOptionPane.YES_OPTION) {
                    profileNote = new Netention.Note("My Nostr Profile", "Edit your profile details here.");
                    profileNote.tags.add(Netention.SystemTag.MY_PROFILE.value);
                    profileNote.tags.add(Netention.SystemTag.CONTACT.value);
                    profileNote.tags.add(Netention.SystemTag.NOSTR_CONTACT.value);
                    profileNote.meta.put(Netention.Metadata.NOSTR_PUB_KEY.key, core.net.getPublicKeyBech32());
                    profileNote.meta.put(Netention.Metadata.NOSTR_PUB_KEY_HEX.key, core.net.getPublicKeyXOnlyHex());
                    core.saveNote(profileNote);
                    core.cfg.net.myProfileNoteId = profileNote.id;
                    core.cfg.saveAllConfigs();
                    updateStatus("Created new profile note.");
                } else {
                    JOptionPane.showMessageDialog(this, "My Profile note not found and not recreated.", "👤 Profile Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }
        }

        if (profileNote == null) {
            JOptionPane.showMessageDialog(this, "Could not load or create profile note.", "👤 Profile Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        var dialogContentPanel = new JPanel(new BorderLayout());
        ProfileEditorPanel profileEditor = new ProfileEditorPanel(core, profileNote, isDirty -> SwingUtilities.invokeLater(() -> {
            JButton publishButton = (JButton) ((JPanel) dialogContentPanel.getComponent(1)).getComponent(0);
            publishButton.setEnabled(isDirty && core.net.isEnabled());
        }));
        dialogContentPanel.add(profileEditor, BorderLayout.CENTER);

        var publishButton = UIUtil.button("🚀 Publish Profile", null, _ -> {
            profileEditor.saveChanges(); // Save changes to the note first
            core.net.publishProfile(profileEditor.getNote()); // Publish the updated note
            updateStatus("Profile publish request sent.");
            JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(dialogContentPanel), "Profile publish request sent.", "🚀 Nostr Profile", JOptionPane.INFORMATION_MESSAGE);
        });
        publishButton.setEnabled(profileEditor.isDirty() && core.net.isEnabled());

        var bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottomPanel.add(publishButton);
        dialogContentPanel.add(bottomPanel, BorderLayout.SOUTH);

        UIUtil.showEditablePanelInDialog(this, "Edit My Nostr Profile", dialogContentPanel, new Dimension(500, 600), true, panel -> profileEditor.isDirty());
        updateStatus("Viewing/Editing My Profile.");
    }

    private void manageNostrRelays() {
        showConfigEditorDialog("config.nostr_relays", "Manage Nostr Relays");
    }

    private void configureNostrIdentity() {
        showConfigEditorDialog("config.nostr_identity", "Configure Nostr Identity");
    }

    private void showConfigEditorDialog(String configNoteId, String dialogTitle) {
        if (!canSwitchOrCloseContent(false, null)) return;

        core.notes.get(configNoteId).ifPresentOrElse(configNote -> {
            var configEditor = new UI.ConfigNoteEditorPanel(core, configNote,
                    () -> {
                        updateStatus(dialogTitle + " saved.");
                        core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, configNoteId + "_updated");
                        if ("config.nostr_identity".equals(configNoteId)) {
                            buddyPanel.refreshList();
                        }
                    },
                    _ -> {}
            );
            UIUtil.showEditablePanelInDialog(this, dialogTitle, configEditor, new Dimension(600, 400), true,
                    panel -> configEditor.userModifiedConfig);
        }, () -> JOptionPane.showMessageDialog(this, "Configuration note '" + configNoteId + "' not found.", "Config Error", JOptionPane.ERROR_MESSAGE));
    }

    private void showInboxDialog() {
        var inboxPanel = new UI.ActionableItemsPanel(core, this::executeActionableItem);
        actionableItemsLock.lock();
        try {
            inboxPanel.refreshList(new ArrayList<>(actionableItems));
        } finally {
            actionableItemsLock.unlock();
        }
        UIUtil.showPanelInDialog(this, "Inbox", inboxPanel, new Dimension(500, 400), false);
        updateStatus("Viewing Inbox.");
    }

    private void executeActionableItem(UI.ActionableItem item) {
        if (item.type().equals("FRIEND_REQUEST")) {
            UIUtil.showConfirmationDialog(this, "Friend Request", "Accept friend request from " + item.description() + "?",
                    () -> {
                        core.fireCoreEvent(Netention.Core.CoreEventType.SYSTEM_EVENT_REQUESTED, Map.of(
                                Netention.ToolParam.EVENT_TYPE.getKey(), Netention.Core.SystemEventType.ACCEPT_FRIEND_REQUEST.name(),
                                Netention.ToolParam.PAYLOAD.getKey(), Map.of(
                                        Netention.ToolParam.FRIEND_REQUEST_SENDER_NPUB.getKey(),
                                        ((Map)(item.rawData())).get("senderNpub"),
                                        Netention.ToolParam.ACTIONABLE_ITEM_ID.getKey(), item.id()
                                )
                        ));
                        updateStatus("Friend request accepted.");
                    },
                    () -> {
                        core.fireCoreEvent(Netention.Core.CoreEventType.SYSTEM_EVENT_REQUESTED, Map.of(
                                Netention.ToolParam.EVENT_TYPE.getKey(), Netention.Core.SystemEventType.REJECT_FRIEND_REQUEST.name(),
                                Netention.ToolParam.PAYLOAD.getKey(), Map.of(
                                        Netention.ToolParam.ACTIONABLE_ITEM_ID.getKey(), item.id()
                                )
                        ));
                        updateStatus("Friend request rejected.");
                    });
        } else {
            item.action().run();
        }
    }

    private void updateInboxMenuItemText() {
        if (inboxMenuItem != null) {
            SwingUtilities.invokeLater(() -> {
                int count = actionableItems.size();
                inboxMenuItem.setText("Inbox" + (count > 0 ? " (" + count + ")" : ""));
                inboxMenuItem.setForeground(count > 0 ? Color.ORANGE.darker() : UIManager.getColor("MenuItem.foreground"));
            });
        }
    }

    private JMenuBar createSimpleChatMenuBar() {
        var mb = new JMenuBar();
        var fileMenu = new JMenu("File");
        fileMenu.add(createMenuItem(UI.ActionID.EXIT));
        mb.add(fileMenu);

        var nostrMenu = new JMenu("Nostr 💜");
        nostrMenu.add(createCheckBoxMenuItem(UI.ActionID.TOGGLE_NOSTR));
        nostrMenu.addSeparator();
        nostrMenu.add(createMenuItem(UI.ActionID.SHOW_MY_NOSTR_PROFILE_EDITOR));
        nostrMenu.add(createMenuItem(UI.ActionID.ADD_NOSTR_FRIEND));
        nostrMenu.addSeparator();
        nostrMenu.add(createMenuItem(UI.ActionID.MANAGE_NOSTR_RELAYS_POPUP));
        nostrMenu.add(createMenuItem(UI.ActionID.CONFIGURE_NOSTR_IDENTITY_POPUP));
        mb.add(nostrMenu);

        var helpMenu = new JMenu("Help");
        helpMenu.add(createMenuItem(UI.ActionID.ABOUT));
        mb.add(helpMenu);

        var actionsMenu = new JMenu("Actions");
        inboxMenuItem = createMenuItem(UI.ActionID.SHOW_INBOX);
        actionsMenu.add(inboxMenuItem);
        mb.add(actionsMenu);

        updateInboxMenuItemText();
        return mb;
    }
}
