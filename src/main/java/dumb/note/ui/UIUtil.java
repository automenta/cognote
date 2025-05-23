package dumb.note.ui;

import dumb.note.Crypto;
import dumb.note.Netention;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.Optional.ofNullable;

class UIUtil {
    static JButton button(String emoji, String tooltip, Runnable listener) {
        return button(emoji, "", tooltip, _ -> listener.run());
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
        var dialog = new JDialog(owner, baseTitle, modal);
        dialog.setContentPane(panelContent);
        if (preferredSize != null) dialog.setSize(preferredSize);
        else dialog.pack();
        dialog.setLocationRelativeTo(owner);

        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                boolean allowClose = true;
                if (dirtyStateProvider != null && dirtyStateProvider.apply(panelContent)) {
                    int result = JOptionPane.showConfirmDialog(dialog,
                            "There are unsaved changes. Close anyway?",
                            "Unsaved Changes", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (result == JOptionPane.NO_OPTION) {
                        allowClose = false;
                    }
                }
                if (allowClose) dialog.dispose();
            }
        });
        dialog.setVisible(true);
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
                    contactN.tags.addAll(java.util.List.of(Netention.SystemTag.CONTACT.value, Netention.SystemTag.NOSTR_CONTACT.value));
                    contactN.meta.putAll(Map.of(Netention.Metadata.NOSTR_PUB_KEY.key, cleanNpub, Netention.Metadata.NOSTR_PUB_KEY_HEX.key, hexPubKey));
                    return core.saveNote(contactN);
                });

                var chatId = "chat_" + cleanNpub;
                if (core.notes.get(chatId).isEmpty()) {
                    var chatNote = new Netention.Note("Chat with " + cleanNpub.substring(0, Math.min(10, cleanNpub.length())) + "...", "");
                    chatNote.id = chatId;
                    chatNote.tags.addAll(List.of(Netention.SystemTag.CHAT.value, "nostr"));
                    chatNote.meta.put(Netention.Metadata.NOSTR_PUB_KEY.key, cleanNpub);
                    chatNote.content.put(Netention.ContentKey.MESSAGES.getKey(), new ArrayList<Map<String, String>>());
                    core.saveNote(chatNote);
                    JOptionPane.showMessageDialog(parentComponent, "Friend " + cleanNpub.substring(0, 10) + "... added & intro DM sent (if Nostr enabled).", "🤝 Friend Added", JOptionPane.INFORMATION_MESSAGE);
                } else {
                    JOptionPane.showMessageDialog(parentComponent, "Friend " + cleanNpub.substring(0, 10) + "... already exists or has an active chat.", "ℹ️ Friend Exists", JOptionPane.INFORMATION_MESSAGE);
                }
                if (onAddedCallback != null) onAddedCallback.accept(cleanNpub);

            } catch (Exception ex) {
                JOptionPane.showMessageDialog(parentComponent, "Invalid Nostr public key (npub) or error: " + ex.getMessage(), "Error Adding Friend", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    public static void showConfirmationDialog(Component parent, String title, String message, Runnable onConfirm, Runnable onCancel) {
        int result = JOptionPane.showConfirmDialog(parent, message, title, JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (result == JOptionPane.YES_OPTION) {
            if (onConfirm != null) onConfirm.run();
        } else {
            if (onCancel != null) onCancel.run();
        }
    }

}
