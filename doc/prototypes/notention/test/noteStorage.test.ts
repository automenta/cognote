import {GraphDBNoteStorage, InMemoryNoteStorage, NoteStorage} from '../lib/noteStorage';
import {Note} from '../types';
import idService from '../lib/idService';
import * as fs from 'fs';

const TEST_DB_PATH = 'test.db';

describe('NoteStorage Implementations', () => {
    let inMemoryStorage: InMemoryNoteStorage;
    let graphDBStorage: GraphDBNoteStorage;
    let note1: Note;
    let note2: Note;

    beforeEach(async () => {
        // Initialize InMemoryNoteStorage
        inMemoryStorage = new InMemoryNoteStorage();

        // Initialize GraphDBNoteStorage with a test database path
        graphDBStorage = new GraphDBNoteStorage(TEST_DB_PATH);

        note1 = {
            id: idService.generateId(),
            type: 'Task',
            title: 'Test Note 1',
            content: 'This is a test note.',
            status: 'pending',
            priority: 50,
            createdAt: new Date().toISOString(),
            updatedAt: null,
            references: [],
            description: 'Test Description',
            requiresWebSearch: false,
            inputSchema: '',
            outputSchema: '',
            config: {},
            logic: ''
        };
        note2 = {
            id: idService.generateId(),
            type: 'Task',
            title: 'Test Note 2',
            content: 'This is another test note.',
            status: 'active',
            priority: 75,
            createdAt: new Date().toISOString(),
            updatedAt: null,
            references: [],
            description: 'Test Description 2',
            requiresWebSearch: false,
            inputSchema: '',
            outputSchema: '',
            config: {},
            logic: ''
        };
    });

    afterEach(async () => {
        // Clear the test database after each test
        if (fs.existsSync(TEST_DB_PATH)) {
            fs.rmSync(TEST_DB_PATH, {recursive: true, force: true});
        }
    });

    // Helper function to run the same tests for both storage implementations
    const testStorage = (storageName: string, storage: NoteStorage) => {
        describe(`${storageName}`, () => {
            it('should add a note', async () => {
                await storage.addNote(note1);
                const retrievedNote = await storage.getNote(note1.id);
                expect(retrievedNote).toEqual(note1);
            });

            it('should get a note by ID', async () => {
                await storage.addNote(note1);
                const retrievedNote = await storage.getNote(note1.id);
                expect(retrievedNote).toEqual(note1);
            });

            it('should return undefined when getting a non-existent note', async () => {
                const retrievedNote = await storage.getNote('non-existent-id');
                expect(retrievedNote).toBeUndefined();
            });

            it('should get all notes', async () => {
                await storage.addNote(note1);
                await storage.addNote(note2);
                const allNotes = await storage.getAllNotes();
                expect(allNotes).toEqual(expect.arrayContaining([note1, note2]));
            });

            it('should update a note', async () => {
                await storage.addNote(note1);
                const updatedNote = {...note1, title: 'Updated Test Note'};
                await storage.updateNote(updatedNote);
                const retrievedNote = await storage.getNote(note1.id);
                expect(retrievedNote).toEqual(updatedNote);
            });

            it('should delete a note', async () => {
                await storage.addNote(note1);
                await storage.deleteNote(note1.id);
                const retrievedNote = await storage.getNote(note1.id);
                expect(retrievedNote).toBeUndefined();
            });

            it('should get references for a note', async () => {
                note1.references = [note2.id];
                await storage.addNote(note1);
                await storage.addNote(note2);
                const references = await storage.getReferences(note1.id);
                expect(references).toEqual(expect.arrayContaining([note2.id]));
            });

            it('should add a reference to a note', async () => {
                await storage.addNote(note1);
                await storage.addNote(note2);
                await storage.addReference(note1.id, note2.id);
                const retrievedNote = await storage.getNote(note1.id);
                expect(retrievedNote?.references).toContain(note2.id);
            });

            it('should remove a reference from a note', async () => {
                note1.references = [note2.id];
                await storage.addNote(note1);
                await storage.addNote(note2);
                await storage.removeReference(note1.id, note2.id);
                const retrievedNote = await storage.getNote(note1.id);
                expect(retrievedNote?.references).not.toContain(note2.id);
            });
        });
    };

    // Run the tests for both storage implementations
    testStorage('InMemoryNoteStorage', inMemoryStorage);
    testStorage('GraphDBNoteStorage', graphDBStorage);
});
