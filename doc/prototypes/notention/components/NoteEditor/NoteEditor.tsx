import React, {useState, useEffect, useCallback} from 'react';
import styles from './NoteEditor.module.css';
import {Note, NoteSchema} from '../../types';
import {useSystemNote} from '../../lib/systemNote';
import {NoteImpl} from '../../lib/note';
import idService from '../../lib/idService';

interface NoteEditorProps {
    noteId: string | null;
    onClose: () => void;
    onSave: (note: Note) => void;
}

const NoteEditor: React.FC<NoteEditorProps> = ({noteId, onClose, onSave}) => {
    const [note, setNote] = useState<Note | null>(null);
    const [title, setTitle] = useState('');
    const [content, setContent] = useState<any>('');
    const [error, setError] = useState<string | null>(null);
    const [toolResult, setToolResult] = useState<any>(null); // New state for tool result
    const systemNote = useSystemNote();

    useEffect(() => {
        const fetchNote = async () => {
            if (noteId) {
                const fetchedNote = await systemNote.getNote(noteId);
                if (fetchedNote) {
                    setNote(fetchedNote);
                    setTitle(fetchedNote.title);
                    setContent(fetchedNote.content);
                }
            } else {
                // Initialize for new note creation
                setNote(null);
                setTitle('');
                setContent('Write a summary of...');
            }
        };

        fetchNote();
    }, [noteId, systemNote]);

    const handleTitleChange = useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
        setTitle(event.target.value);
    }, [title]);

    const handleContentChange = useCallback((event: React.ChangeEvent<HTMLTextAreaElement>) => {
        setContent(event.target.value);
    }, [content]);

    const handleSave = async () => {
        if (!title.trim()) {
            setError('Title cannot be empty.');
            return;
        }

        setError(null);

        if (note) {
            // Update existing note
            const updatedNote: Note = {
                ...note,
                title: title,
                content: content,
            };

            await systemNote.updateNote(updatedNote);
            onSave(updatedNote); // Notify parent component that save is complete
        } else {
            // Create a new note
            const newNoteImpl = await NoteImpl.createTaskNote(title, content);
            await systemNote.addNote(newNoteImpl.data);
            onSave(newNoteImpl.data); // Notify parent component that save is complete
        }
        onClose();
    };

    const handleRun = async () => {
        if (note) {
            const noteImpl = new NoteImpl(note);
            try {
                await noteImpl.run();
                // Fetch the updated note after running
                const updatedNote = await systemNote.getNote(note.id);
                if (updatedNote) {
                    setNote(updatedNote);
                    // Extract the last system message as the tool result
                    if (updatedNote.content?.messages && updatedNote.content.messages.length > 0) {
                        const lastMessage = updatedNote.content.messages[updatedNote.content.messages.length - 1];
                        if (lastMessage.type === 'error') {
                            setError(lastMessage.content);
                            setToolResult(null);
                        } else {
                            setToolResult(lastMessage.content);
                            setError(null);
                        }
                    } else {
                        setToolResult('No result.');
                        setError(null);
                    }
                }
            } catch (error: any) {
                setToolResult(null);
                setError(`Error: ${error.message}`);
            }
        }
    };

    return (
        <div className={styles.noteEditor}>
            <h2>Note Editor</h2>
            {error && <div className={styles.error}>{error}</div>}
            <label htmlFor="title">Title:</label>
            <input
                type="text"
                id="title"
                value={title}
                onChange={handleTitleChange}
            />
            <label htmlFor="content">Content:</label>
            <textarea
                id="content"
                value={content}
                onChange={handleContentChange}
            />
            <div className={styles.buttons}>
                <button onClick={handleSave}>Save</button>
                <button onClick={handleRun}>Run</button>
                <button onClick={onClose}>Close</button>
            </div>
            {toolResult && (
                <div className={styles.toolResult}>
                    <h3>Tool Result:</h3>
                    <textarea
                        value={toolResult}
                        readOnly
                        rows={4}
                        cols={50}
                    />
                </div>
            )}
        </div>
    );
};

export default NoteEditor;
