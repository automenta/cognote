import {Note} from '../types';
import {getSystemNote} from './systemNote';
import {systemLog} from './systemLog';
import {updateNote} from './noteUpdate';
import {NoteImpl} from './note';

export const reflect = async (note: Note, executionResult: any) => {
    systemLog.debug(`Note ${note.id} Reflecting on result: ${JSON.stringify(executionResult)}`, note.type);

    if (note.type === 'Task' && executionResult) {
        const reflectionMessage = `Reflection: Task execution completed. Result details: ${JSON.stringify(executionResult)}`;
        addSystemMessage(note, reflectionMessage);

        // Example: Create a sub-note if the result suggests further action
        if (executionResult.output && typeof executionResult.output === 'string' && executionResult.output.includes('create sub-task')) {
            const subNote = await NoteImpl.createTaskNote(
                `Sub-task of ${note.title}`,
                'Details: ' + executionResult.output,
                note.priority - 1
            );
            getSystemNote().addNote(subNote.data);
            note.references.push(subNote.data.id);
            updateNote(note);
        }
    }
};

export const handleFailure = async (note: Note, error: any) => {
    systemLog.warn(`Handling failure for Note ${note.id}: ${error.message}`, note.type);

    if (note.type === 'Task') {
        const failureMessage = `Failure Handler: Error encountered - ${error.message}. Details: ${JSON.stringify(error)}`;
        addSystemMessage(note, failureMessage, 'warning');

        // Example: Retry logic (up to 3 attempts)
        const retryCount = note.content?.retryCount || 0;
        if (retryCount < 3) {
            note.content.retryCount = retryCount + 1;
            addSystemMessage(note, `Retrying task (attempt ${note.content.retryCount}).`);
            note.status = 'pending';
            updateNote(note);
            getSystemNote().enqueueNote(note.id); // Re-enqueue the task
        } else {
            note.status = 'failed';
            updateNote(note);
            addSystemMessage(note, 'Task failed after multiple retries.', 'error');
        }
    }
};

const addSystemMessage = (note: Note, message: string, level: 'info' | 'warning' | 'error' = 'info') => {
    const systemMessage = {
        type: 'system',
        content: message,
        timestamp: new Date().toISOString(),
    };

    note.content.messages = [...(note.content.messages ?? []), systemMessage];
    updateNote(note);
    systemLog[level](`[Note ${note.id}] ${message}`, note.type);
};
