package dumb.note.ui;

import dumb.note.Netention;

import javax.swing.*;
import java.awt.*;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

public class SimpleChat extends UI.BaseAppFrame {
    private final UI.BuddyListPanel buddyPanel;
    private final List<UI.ActionableItem> actionableItems = new CopyOnWriteArrayList<>();
    private final ReentrantLock actionableItemsLock = new ReentrantLock();
    private JMenuItem inboxMenuItem; // To update the menu item text dynamically

    public SimpleChat(Netention.Core core) {
        super(core, "SimpleChat ✨", 900, 700);
        var contentPanelContainer = super.defaultEditorHostPanel;

        buddyPanel = new UI.BuddyListPanel(core, this::displayContentForIdentifier, this::showMyProfileEditor);

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
        updateActionStates(); // Will update nostrToggleMenuItem state
    }

    public static void main(String[] ignoredArgs) {
        SwingUtilities.invokeLater(() -> new SimpleChat(new Netention.Core()).setVisible(true));
    }

    @Override
    protected Optional<UI.DirtyableSavableComponent> getActiveDirtyableSavableComponent() {
        // SimpleChat doesn't have a main DirtyableSavableComponent in the editor area
        // ChatPanel is not dirtyable/savable in the same way.
        // Profile editor is in a dialog.
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
        }).setSelectedCalculator(() -> core.net.isEnabled()));

        actionRegistry.register(UI.ActionID.SHOW_MY_NOSTR_PROFILE_EDITOR, new UI.AppAction("My Profile...", e -> showMyProfileEditor()));
        actionRegistry.register(UI.ActionID.ADD_NOSTR_FRIEND, new UI.AppAction("Add Nostr Contact...", e -> UIUtil.addNostrContactDialog(this, core, _ -> buddyPanel.refreshList())));
        actionRegistry.register(UI.ActionID.MANAGE_NOSTR_RELAYS_POPUP, new UI.AppAction("Manage Relays...", e -> manageNostrRelays()));
        actionRegistry.register(UI.ActionID.CONFIGURE_NOSTR_IDENTITY_POPUP, new UI.AppAction("Configure Identity...", e -> configureNostrIdentity()));
        actionRegistry.register(UI.ActionID.ABOUT, new UI.AppAction("About SimpleChat", e ->
                JOptionPane.showMessageDialog(this, "SimpleChat ✨\nA basic Nostr IM client.", "About SimpleChat", JOptionPane.INFORMATION_MESSAGE)
        ));
        actionRegistry.register(UI.ActionID.SHOW_INBOX, new UI.AppAction("Inbox", e -> showInboxDialog()));
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
                updateStatusBasedOnNostrState(); // Also called by super.handleCoreEventBase via updateActionStates
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
            default -> { /* No specific action */ }
        }
        updateActionStates();
    }

    private void updateStatusBasedOnNostrState() {
        updateStatus(core.net.isEnabled() ? "Nostr Connected" : "Nostr Disconnected");
    }

    private void displayContentForIdentifier(String identifier) {
        if (!canSwitchOrCloseContent(false, null)) return;

        core.notes.get(identifier)
                .filter(n -> n.tags.contains(Netention.SystemTag.CHAT.value))
                .ifPresentOrElse(chatNote -> {
                    if (chatNote.meta.get(Netention.Metadata.NOSTR_PUB_KEY.key) instanceof String partnerNpub) {
                        setEditorComponent(new UI.ChatPanel(core, chatNote, partnerNpub, this::updateStatus));
                    } else {
                        setEditorComponent(new JLabel("Error: Chat partner not found in note metadata.", SwingConstants.CENTER));
                        updateStatus("Error: Chat partner not found for " + chatNote.getTitle());
                    }
                }, () -> {
                    if (!identifier.startsWith("npub1")) {
                        core.notes.get(identifier)
                                .filter(n -> n.tags.contains(Netention.SystemTag.CONTACT.value))
                                .ifPresentOrElse(this::showProfileForContact,
                                        () -> setEditorComponent(new JLabel("Content not found for: " + identifier, SwingConstants.CENTER)));
                        return;
                    }
                    core.notes.getAll(n -> n.tags.contains(Netention.SystemTag.CONTACT.value) &&
                                    identifier.equals(n.meta.get(Netention.Metadata.NOSTR_PUB_KEY.key)))
                            .stream().findFirst()
                            .ifPresentOrElse(
                                    this::showProfileForContact,
                                    () -> core.notes.getAll(cn -> cn.tags.contains(Netention.SystemTag.CHAT.value) &&
                                                    identifier.equals(cn.meta.get(Netention.Metadata.NOSTR_PUB_KEY.key)))
                                            .stream().findFirst()
                                            .ifPresentOrElse(chatNote -> displayContentForIdentifier(chatNote.id),
                                                    () -> {
                                                        setEditorComponent(new JLabel("Contact/chat not found for: " + identifier.substring(0, 12) + "...", SwingConstants.CENTER));
                                                        updateStatus("Contact/chat not found for " + identifier.substring(0, 12) + "...");
                                                    })
                            );
                });
    }


    private void showProfileForContact(Netention.Note contactNote) {
        var profileEditor = new UI.NoteEditorPanel(core, contactNote, this, () -> {
        }, null, _ -> {
        });
        profileEditor.setReadOnlyMode(true);
        UIUtil.showPanelInDialog(this, "Profile: " + contactNote.getTitle(), profileEditor, new Dimension(400, 500), false);
        updateStatus("Viewing profile: " + contactNote.getTitle());
    }

    private void showMyProfileEditor() {
        if (!canSwitchOrCloseContent(false, null)) return;
        var myProfileNoteId = core.cfg.net.myProfileNoteId;
        if (myProfileNoteId == null || myProfileNoteId.isEmpty()) {
            if (JOptionPane.showConfirmDialog(this, "My Profile note ID not configured. Create one now?", "👤 Profile Setup", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                var newProfileNote = new Netention.Note("My Nostr Profile", "Bio: ...");
                newProfileNote.tags.add(Netention.SystemTag.MY_PROFILE.value);
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

        final var finalMyProfileNoteId = myProfileNoteId;
        core.notes.get(finalMyProfileNoteId).ifPresentOrElse(profileNote -> {
            var dialogContentPanel = new JPanel(new BorderLayout());
            var profileEditor = new UI.NoteEditorPanel(core, profileNote, this, null, null, _ -> {
            });
            profileEditor.onSaveCb = () -> {
                updateStatus("Profile note saved.");
                core.fireCoreEvent(Netention.Core.CoreEventType.NOTE_UPDATED, profileEditor.getCurrentNote());
            };
            dialogContentPanel.add(profileEditor, BorderLayout.CENTER);

            var publishButton = UIUtil.button("🚀 Publish Profile", null, _ -> {
                profileEditor.saveNote(false);
                core.net.publishProfile(profileEditor.currentNote);
                updateStatus("Profile publish request sent.");
                JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(dialogContentPanel), "Profile publish request sent.", "🚀 Nostr Profile", JOptionPane.INFORMATION_MESSAGE);
            });
            publishButton.setEnabled(core.net.isEnabled());

            var bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            bottomPanel.add(publishButton);
            dialogContentPanel.add(bottomPanel, BorderLayout.SOUTH);

            UIUtil.showEditablePanelInDialog(this, "Edit My Nostr Profile", dialogContentPanel, new Dimension(500, 600), true, panel -> profileEditor.isUserModified());

        }, () -> JOptionPane.showMessageDialog(this, "My Profile note not found (ID: " + core.cfg.net.myProfileNoteId + ").", "👤 Profile Error", JOptionPane.ERROR_MESSAGE));
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
            inboxPanel.refreshList(new ArrayList<>(actionableItems)); // Pass a copy
        } finally {
            actionableItemsLock.unlock();
        }
        UIUtil.showPanelInDialog(this, "Inbox", inboxPanel, new Dimension(500, 400), false);
        updateStatus("Viewing Inbox.");
    }

    private void executeActionableItem(UI.ActionableItem item) {
        item.action().run();
        // The action itself should trigger a CoreEventType.ACTIONABLE_ITEM_REMOVED event
        // if the item is no longer relevant, which will update the list.
        // No explicit removal here to avoid race conditions with Core events.
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

        // NEW: Actions Menu
        var actionsMenu = new JMenu("Actions");
        inboxMenuItem = createMenuItem(UI.ActionID.SHOW_INBOX); // Assign to class field
        actionsMenu.add(inboxMenuItem);
        mb.add(actionsMenu);

        updateInboxMenuItemText(); // Initial update
        return mb;
    }
}
