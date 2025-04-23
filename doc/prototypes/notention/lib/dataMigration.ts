import {GraphDBNoteStorage, InMemoryNoteStorage} from './noteStorage';
import {systemLog} from './systemLog';

// Function to migrate data from InMemoryNoteStorage to GraphDBNoteStorage
export const migrateDataToGraphDB = async () => {
    systemLog.info('Starting data migration to GraphDBNoteStorage...', 'SystemNote');

    try {
        const inMemoryStorage = new InMemoryNoteStorage();
        const graphDBStorage = new GraphDBNoteStorage();

        // Get all notes from InMemoryNoteStorage
        const notes = await inMemoryStorage.getAllNotes();

        // Add each note to GraphDBNoteStorage
        for (const note of notes) {
            await graphDBStorage.addNote(note);
        }

        systemLog.info(`Successfully migrated ${notes.length} notes to GraphDBNoteStorage.`, 'SystemNote');
    } catch (error: any) {
        systemLog.error(`Error migrating data to GraphDBNoteStorage: ${error.message}`, 'SystemNote');
    }
};
