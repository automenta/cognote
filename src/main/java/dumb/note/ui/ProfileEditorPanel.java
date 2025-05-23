package dumb.note.ui;

import dumb.note.Netention;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.function.Consumer;

public class ProfileEditorPanel extends JPanel implements UI.DirtyableSavableComponent {
    private final Netention.Core core;
    private Netention.Note profileNote;
    private final JTextField nameField;
    private final JTextArea aboutArea;
    private final JTextField pictureUrlField;
    private boolean dirty = false;
    private Consumer<Boolean> dirtyStateListener;

    public ProfileEditorPanel(Netention.Core core, Netention.Note profileNote, Consumer<Boolean> dirtyStateListener) {
        this.core = core;
        this.profileNote = profileNote;
        this.dirtyStateListener = dirtyStateListener;

        setLayout(new GridBagLayout());
        setBorder(new EmptyBorder(10, 10, 10, 10));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Name Field
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        add(new JLabel("Name:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        nameField = new JTextField(profileNote.content.getOrDefault(Netention.ContentKey.PROFILE_NAME.getKey(), "").toString());
        add(nameField, gbc);

        // About Area
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        add(new JLabel("About:"), gbc);

        gbc.gridx = 1;
        gbc.weighty = 1.0; // Allow about area to expand vertically
        aboutArea = new JTextArea(profileNote.content.getOrDefault(Netention.ContentKey.PROFILE_ABOUT.getKey(), "").toString());
        aboutArea.setLineWrap(true);
        aboutArea.setWrapStyleWord(true);
        JScrollPane aboutScrollPane = new JScrollPane(aboutArea);
        aboutScrollPane.setPreferredSize(new Dimension(300, 100)); // Set a preferred size
        add(aboutScrollPane, gbc);

        // Picture URL Field
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weighty = 0; // Reset weighty
        gbc.anchor = GridBagConstraints.WEST;
        add(new JLabel("Picture URL:"), gbc);

        gbc.gridx = 1;
        pictureUrlField = new JTextField(profileNote.content.getOrDefault(Netention.ContentKey.PROFILE_PICTURE_URL.getKey(), "").toString());
        add(pictureUrlField, gbc);

        // Add document listeners to track dirty state
        DocumentListener docListener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { setDirty(true); }
            @Override
            public void removeUpdate(DocumentEvent e) { setDirty(true); }
            @Override
            public void changedUpdate(DocumentEvent e) { setDirty(true); }
        };

        nameField.getDocument().addDocumentListener(docListener);
        aboutArea.getDocument().addDocumentListener(docListener);
        pictureUrlField.getDocument().addDocumentListener(docListener);

        // Initial dirty state check
        setDirty(false);
    }

    private void setDirty(boolean dirty) {
        if (this.dirty != dirty) {
            this.dirty = dirty;
            if (dirtyStateListener != null) {
                dirtyStateListener.accept(dirty);
            }
        }
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public void saveChanges() {
        profileNote.content.put(Netention.ContentKey.PROFILE_NAME.getKey(), nameField.getText());
        profileNote.content.put(Netention.ContentKey.PROFILE_ABOUT.getKey(), aboutArea.getText());
        profileNote.content.put(Netention.ContentKey.PROFILE_PICTURE_URL.getKey(), pictureUrlField.getText());
        profileNote.meta.put(Netention.Metadata.PROFILE_LAST_UPDATED_AT.key, System.currentTimeMillis()); // Update timestamp
        core.saveNote(profileNote);
        setDirty(false);
        core.fireCoreEvent(Netention.Core.CoreEventType.NOTE_UPDATED, profileNote);
    }

    public Netention.Note getNote() {
        return profileNote;
    }

    public void setReadOnlyMode(boolean readOnly) {
        nameField.setEditable(!readOnly);
        aboutArea.setEditable(!readOnly);
        pictureUrlField.setEditable(!readOnly);
        
        if (readOnly) {
            // Remove document listeners to prevent accidental dirty state changes in read-only mode
            DocumentListener[] nameListeners = nameField.getDocument().getDocumentListeners();
            for (DocumentListener l : nameListeners) {
                nameField.getDocument().removeDocumentListener(l);
            }
            DocumentListener[] aboutListeners = aboutArea.getDocument().getDocumentListeners();
            for (DocumentListener l : aboutListeners) {
                aboutArea.getDocument().removeDocumentListener(l);
            }
            DocumentListener[] pictureListeners = pictureUrlField.getDocument().getDocumentListeners();
            for (DocumentListener l : pictureListeners) {
                pictureUrlField.getDocument().removeDocumentListener(l);
            }
            setDirty(false); // Ensure it's not dirty in read-only mode
        } else {
            // If switching back to editable mode, re-add listeners if needed.
            // For this application's current use case, ProfileEditorPanel is either
            // created for editing (my profile) or for read-only viewing (contact profile).
            // Re-adding listeners here is not strictly necessary for current functionality.
        }
    }
}
