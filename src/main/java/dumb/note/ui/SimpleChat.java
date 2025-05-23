package dumb.note.ui;

import dumb.note.Netention;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedInputStream;
import java.util.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class SimpleChat extends UI.BaseAppFrame {
    private final BuddyListPanel buddyPanel;
    private final List<UI.ActionableItem> actionableItems = new CopyOnWriteArrayList<>();
    private final ReentrantLock actionableItemsLock = new ReentrantLock();
    private JMenuItem inboxMenuItem;
    private Clip notificationSoundClip;

    public SimpleChat(Netention.Core core) {
        super(core, "SimpleChat ✨", 900, 700);
        var contentPanelContainer = super.defaultEditorHostPanel;

        try (var is = getClass().getResourceAsStream("/notification.wav");
             var bis = new BufferedInputStream(is)) {
            notificationSoundClip = AudioSystem.getClip();
            notificationSoundClip.open(AudioSystem.getAudioInputStream(bis));
        } catch (Exception e) {
            Netention.Core.logger.error("Error loading notification sound: {}", e.getMessage());
            notificationSoundClip = null;
        }

        buddyPanel = new BuddyListPanel(this, this::displayContentForIdentifier, this::showMyProfileEditor);

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

        buddyPanel.buddies.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showBuddyListContextMenu(e);
                }
            }

            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showBuddyListContextMenu(e);
                }
            }
        });
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
            var wantsEnable = false;
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
            var selected = buddyPanel.buddies.getSelectedValue();
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
                if (event.data() instanceof Map<?, ?> data && data.get("chatNoteId") instanceof String chatNoteId) {
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
            case NOTE_ADDED, NOTE_UPDATED, NOTE_DELETED -> SwingUtilities.invokeLater(buddyPanel::refreshList);
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
            default -> {
            }
        }
        updateActionStates();
    }

    private void updateStatusBasedOnNostrState() {
        updateStatus(core.net.isEnabled() ? "Nostr Connected" : "Nostr Disconnected");
    }

    private void displayContentForIdentifier(String identifier) {
        if (!canSwitchOrCloseContent(false, null)) return;

        // 1. Try to load as a Chat Note
        var chatNoteOpt = core.notes.get(identifier)
                .filter(n -> n.tags.contains(Netention.SystemTag.CHAT.value));

        if (chatNoteOpt.isPresent()) {
            var chatNote = chatNoteOpt.get();
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
            var contactNote = contactNoteOpt.get();
            // Display contact profile in the main panel
            var profileEditor = new ProfileEditorPanel(core, contactNote, isDirty -> {
            }); // No dirty listener needed for read-only view
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
            var choice = JOptionPane.showConfirmDialog(this, "Your Nostr profile note ID is not configured. Would you like to generate new keys and create a profile now?", "👤 Profile Setup", JOptionPane.YES_NO_CANCEL_OPTION);
            if (choice == JOptionPane.YES_OPTION) {
                var keyInfo = core.cfg.generateNewNostrKeysAndUpdateConfig();
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
                var choice = JOptionPane.showConfirmDialog(this, "Your configured profile note (ID: " + myProfileNoteId + ") was not found. Would you like to create a new one?", "👤 Profile Error", JOptionPane.YES_NO_OPTION);
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
        var profileEditor = new ProfileEditorPanel(core, profileNote, isDirty -> SwingUtilities.invokeLater(() -> {
            var publishButton = (JButton) ((JPanel) dialogContentPanel.getComponent(1)).getComponent(0);
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
                    _ -> {
                    }
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
                                        ((Map) (item.rawData())).get("senderNpub"),
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
                var count = actionableItems.size();
                inboxMenuItem.setText("Inbox" + (count > 0 ? " (" + count + ")" : ""));
                inboxMenuItem.setForeground(count > 0 ? Color.ORANGE.darker() : UIManager.getColor("MenuItem.foreground"));
            });
        }
    }

    private void showBuddyListContextMenu(MouseEvent e) {
        var index = buddyPanel.buddies.locationToIndex(e.getPoint());
        if (index < 0) return;

        buddyPanel.buddies.setSelectedIndex(index);
        var selected = buddyPanel.buddies.getSelectedValue();

        if (selected instanceof Netention.Note selectedNote) {
            var popup = new JPopupMenu();

            // View Profile
            if (selectedNote.tags.contains(Netention.SystemTag.CONTACT.value)) {
                var viewProfileItem = new JMenuItem("View Profile");
                viewProfileItem.addActionListener(_ -> displayContentForIdentifier(selectedNote.id));
                popup.add(viewProfileItem);
            }

            // Start Chat
            if (selectedNote.tags.contains(Netention.SystemTag.CONTACT.value) || selectedNote.tags.contains(Netention.SystemTag.CHAT.value)) {
                var startChatItem = new JMenuItem("Start Chat");
                startChatItem.addActionListener(_ -> {
                    var chatPartnerPubKeyHex = (String) selectedNote.meta.get(Netention.Metadata.NOSTR_PUB_KEY_HEX.key);
                    if (chatPartnerPubKeyHex != null) {
                        // Try to find an existing chat note for this partner
                        var chatNoteOpt = core.notes.getAll(n ->
                                n.tags.contains(Netention.SystemTag.CHAT.value) &&
                                        chatPartnerPubKeyHex.equals(n.meta.get(Netention.Metadata.NOSTR_PUB_KEY_HEX.key))
                        ).stream().findFirst();

                        Netention.Note chatNoteToDisplay;
                        if (chatNoteOpt.isPresent()) {
                            chatNoteToDisplay = chatNoteOpt.get();
                        } else {
                            // If no chat note exists, create a new one
                            chatNoteToDisplay = new Netention.Note("Chat with " + selectedNote.getTitle(), "");
                            chatNoteToDisplay.tags.add(Netention.SystemTag.CHAT.value);
                            chatNoteToDisplay.meta.put(Netention.Metadata.NOSTR_PUB_KEY_HEX.key, chatPartnerPubKeyHex);
                            chatNoteToDisplay.meta.put(Netention.Metadata.NOSTR_PUB_KEY.key, selectedNote.meta.get(Netention.Metadata.NOSTR_PUB_KEY.key));
                            chatNoteToDisplay.content.put(Netention.ContentKey.MESSAGES.getKey(), new ArrayList<Map<String, String>>());
                            core.saveNote(chatNoteToDisplay); // Save it so it appears in the buddy list
                            Netention.Core.logger.info("Created new chat note for {}.", selectedNote.getTitle());
                        }
                        displayContentForIdentifier(chatNoteToDisplay.id);
                    } else {
                        JOptionPane.showMessageDialog(this, "Cannot start chat: Public key not found for this contact.", "Error", JOptionPane.ERROR_MESSAGE);
                    }
                });
                popup.add(startChatItem);
            }

            // Remove Contact
            if (selectedNote.tags.contains(Netention.SystemTag.CONTACT.value) && !selectedNote.tags.contains(Netention.SystemTag.MY_PROFILE.value)) {
                var removeContactItem = new JMenuItem("Remove Contact");
                removeContactItem.addActionListener(_ -> {
                    var confirm = JOptionPane.showConfirmDialog(this, "Are you sure you want to remove " + selectedNote.getTitle() + "? This will also delete the chat history.", "Confirm Removal", JOptionPane.YES_NO_OPTION);
                    if (confirm == JOptionPane.YES_OPTION) {
                        var nostrPubKeyHex = (String) selectedNote.meta.get(Netention.Metadata.NOSTR_PUB_KEY_HEX.key);
                        if (nostrPubKeyHex != null) {
                            try {
                                core.executeTool(Netention.Core.Tool.REMOVE_CONTACT, Map.of(Netention.ToolParam.NOSTR_PUB_KEY_HEX.getKey(), nostrPubKeyHex));
                                buddyPanel.refreshList();
                                setEditorComponent(new JLabel("Select a chat or contact.", SwingConstants.CENTER)); // Clear current view
                                updateStatus("Contact " + selectedNote.getTitle() + " removed.");
                            } catch (Exception ex) {
                                JOptionPane.showMessageDialog(this, "Failed to remove contact: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                                Netention.Core.logger.error("Failed to remove contact {}: {}", selectedNote.id, ex.getMessage(), ex);
                            }
                        }
                    }
                });
                popup.add(removeContactItem);
            }

            if (popup.getComponentCount() > 0) {
                popup.show(e.getComponent(), e.getX(), e.getY());
            }
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

    static class BuddyListPanel extends JPanel {
        private final Netention.Core core;
        private final DefaultListModel<Object> list = new DefaultListModel<>();
        final JList<Object> buddies = new JList<>(list);
        private final JTextField searchField; // NEW

        public BuddyListPanel(UI.BaseAppFrame ui, Consumer<String> onIdentifierSelected, Runnable onShowMyProfile) {
            this.core = ui.core;
            setLayout(new BorderLayout(5, 5));
            setBorder(new EmptyBorder(5, 5, 5, 5));

            searchField = new JTextField(); // NEW
            searchField.putClientProperty("JTextField.placeholderText", "Search contacts/chats..."); // NEW
            searchField.getDocument().addDocumentListener(new UI.FieldUpdateListener(_ -> refreshList())); // NEW

            buddies.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            buddies.setCellRenderer(new BuddyListRenderer(core));
            buddies.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting() && buddies.getSelectedValue() != null) {
                    var selected = buddies.getSelectedValue();
                    if (selected instanceof Netention.Note note) onIdentifierSelected.accept(note.id);
                    else if (selected instanceof String npub) onIdentifierSelected.accept(npub);
                }
            });
            buddies.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        if (buddies.getSelectedValue() instanceof Netention.Note note)
                            onIdentifierSelected.accept(note.id);
                    }
                }

                @Override // NEW: Right-click for context menu
                public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e)) {
                        var index = buddies.locationToIndex(e.getPoint());
                        if (index != -1 && buddies.getCellBounds(index, index).contains(e.getPoint())) {
                            buddies.setSelectedIndex(index);
                            var selected = buddies.getSelectedValue();
                            if (selected instanceof Netention.Note note && note.tags.contains(Netention.SystemTag.CONTACT.value)) {
                                var menu = new JPopupMenu();
                                menu.add(new JMenuItem(ui.actionRegistry.get(UI.ActionID.VIEW_CONTACT_PROFILE))); // Use action from registry
                                menu.show(e.getComponent(), e.getX(), e.getY());
                            }
                        }
                    }
                }
            });

            add(new JScrollPane(buddies), BorderLayout.CENTER);

            var buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
            buttonPanel.add(UIUtil.button("👤", "My Profile", onShowMyProfile));
            buttonPanel.add(UIUtil.button("➕🫂", "Add Contact", () ->
                    UIUtil.addNostrContactDialog(this, core, onIdentifierSelected) // Pass onIdentifierSelected
            ));
            var topPanel = new JPanel(new BorderLayout(5, 5)); // NEW
            topPanel.add(searchField, BorderLayout.NORTH); // NEW
            topPanel.add(buttonPanel, BorderLayout.SOUTH); // NEW
            add(topPanel, BorderLayout.NORTH); // NEW
            refreshList();
        }

        public void refreshList() {
            var selectedValue = buddies.getSelectedValue();
            list.clear();

            var searchTerm = searchField.getText().toLowerCase().trim(); // NEW

            // Get all chats and contacts
            var allChats = core.notes.getAll(n -> n.tags.contains(Netention.SystemTag.CHAT.value));
            var allContacts = core.notes.getAll(n -> n.tags.containsAll(List.of(Netention.SystemTag.CONTACT.value, Netention.SystemTag.NOSTR_CONTACT.value)));

            // Filter based on search term
            Predicate<Netention.Note> searchFilter = n -> {
                if (searchTerm.isEmpty()) return true;
                var title = n.getTitle().toLowerCase();
                var npub = (String) n.meta.get(Netention.Metadata.NOSTR_PUB_KEY.key);
                return title.contains(searchTerm) || (npub != null && npub.contains(searchTerm));
            };

            // Add chats first, sorted by unread count then last message time
            allChats.stream()
                    .filter(searchFilter) // NEW
                    .sorted(Comparator
                            .comparing((Netention.Note n) -> (Integer) n.meta.getOrDefault(Netention.Metadata.UNREAD_MESSAGES_COUNT.key, 0), Comparator.reverseOrder()) // Unread first
                            .thenComparing(n -> n.updatedAt, Comparator.reverseOrder())) // Then by last updated
                    .forEach(list::addElement);

            var npubsWithChats = IntStream.range(0, list.getSize())
                    .mapToObj(list::getElementAt)
                    .filter(Netention.Note.class::isInstance)
                    .map(Netention.Note.class::cast)
                    .filter(n -> n.tags.contains(Netention.SystemTag.CHAT.value))
                    .map(n -> (String) n.meta.get(Netention.Metadata.NOSTR_PUB_KEY.key))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            // Add contacts that don't have an associated chat yet, filtered and sorted
            allContacts.stream()
                    .filter(searchFilter) // NEW
                    .filter(contactNote -> {
                        var contactNpub = (String) contactNote.meta.get(Netention.Metadata.NOSTR_PUB_KEY.key);
                        return contactNpub == null || !npubsWithChats.contains(contactNpub);
                    })
                    .sorted(Comparator.comparing(Netention.Note::getTitle, String.CASE_INSENSITIVE_ORDER))
                    .forEach(list::addElement);

            if (selectedValue != null && list.contains(selectedValue)) {
                buddies.setSelectedValue(selectedValue, true);
            } else if (!list.isEmpty()) {
                buddies.setSelectedIndex(0);
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
                String text;
                if (value instanceof Netention.Note note) {
                    if (note.tags.contains(Netention.SystemTag.CHAT.value)) {
                        var partnerNpub = (String) note.meta.get(Netention.Metadata.NOSTR_PUB_KEY.key);
                        var baseTitle = note.getTitle();
                        String displayName;
                        if (partnerNpub != null) {
                            displayName = core.notes.getAll(c -> c.tags.contains(Netention.SystemTag.CONTACT.value) && partnerNpub.equals(c.meta.get(Netention.Metadata.NOSTR_PUB_KEY.key)))
                                    .stream().findFirst().map(Netention.Note::getTitle)
                                    .orElseGet(() -> partnerNpub.substring(0, Math.min(10, partnerNpub.length())) + "...");
                        } else {
                            displayName = (baseTitle != null && !baseTitle.isEmpty() ? baseTitle : "Unknown Chat");
                        }
                        int unreadCount = (Integer) note.meta.getOrDefault(Netention.Metadata.UNREAD_MESSAGES_COUNT.key, 0);
                        text = "💬 " + displayName + (unreadCount > 0 ? " (" + unreadCount + ")" : "");
                        if (unreadCount > 0) {
                            setFont(getFont().deriveFont(Font.BOLD));
                            setForeground(Color.BLUE.darker());
                        } else {
                            setFont(getFont().deriveFont(Font.PLAIN));
                            setForeground(UIManager.getColor("List.foreground"));
                        }
                    } else if (note.tags.contains(Netention.SystemTag.CONTACT.value)) {
                        text = "👤 " + note.getTitle();
                        setFont(getFont().deriveFont(Font.PLAIN));
                        setForeground(UIManager.getColor("List.foreground"));
                    } else {
                        text = note.getTitle();
                        setFont(getFont().deriveFont(Font.PLAIN));
                        setForeground(UIManager.getColor("List.foreground"));
                    }
                } else if (value instanceof String strValue) {
                    text = "❔ " + strValue;
                    setFont(getFont().deriveFont(Font.PLAIN));
                    setForeground(UIManager.getColor("List.foreground"));
                } else {
                    text = value != null ? value.toString() : "";
                    setFont(getFont().deriveFont(Font.PLAIN));
                    setForeground(UIManager.getColor("List.foreground"));
                }
                setText(text);
                return this;
            }
        }
    }
}
