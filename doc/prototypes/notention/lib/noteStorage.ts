import {Note} from '../types';
import {systemLog} from './systemLog';
import level from 'level';
import levelgraph from 'levelgraph';

export interface NoteStorage {
    getNote(id: string): Promise<Note | undefined>;

    getAllNotes(): Promise<Note[]>;

    addNote(note: Note): Promise<void>;

    updateNote(note: Note): Promise<void>;

    deleteNote(id: string): Promise<void>;

    getReferences(noteId: string): Promise<string[]>;

    addReference(sourceId: string, targetId: string): Promise<void>;

    removeReference(sourceId: string, targetId: string): Promise<void>;
}

export class InMemoryNoteStorage implements NoteStorage {
    private notes: Map<string, Note> = new Map();

    async getNote(id: string): Promise<Note | undefined> {
        return this.notes.get(id);
    }

    async getAllNotes(): Promise<Note[]> {
        return Array.from(this.notes.values());
    }

    async addNote(note: Note): Promise<void> {
        this.notes.set(note.id, note);
    }

    async updateNote(note: Note): Promise<void> {
        this.notes.set(note.id, note);
    }

    async deleteNote(id: string): Promise<void> {
        this.notes.delete(id);
    }

    async getReferences(noteId: string): Promise<string[]> {
        const note = this.notes.get(noteId);
        return note?.references || [];
    }

    async addReference(sourceId: string, targetId: string): Promise<void> {
        const sourceNote = this.notes.get(sourceId);
        if (sourceNote) {
            sourceNote.references = [...(sourceNote.references || []), targetId];
            this.updateNote(sourceNote);
        }
    }

    async removeReference(sourceId: string, targetId: string): Promise<void> {
        const sourceNote = this.notes.get(sourceId);
        if (sourceNote) {
            sourceNote.references = sourceNote.references?.filter(ref => ref !== targetId) || [];
            this.updateNote(sourceNote);
        }
    }
}

export class GraphDBNoteStorage implements NoteStorage {
    private db: any;

    constructor(dbPath: string = 'netention.db') {
        try {
            const leveldb = level(dbPath, {createIfMissing: true, valueEncoding: 'json'});
            this.db = levelgraph(leveldb);
            systemLog.info(`GraphDBNoteStorage initialized with database at ${dbPath}`, 'GraphDBNoteStorage');
        } catch (error: any) {
            systemLog.error(`Error initializing GraphDBNoteStorage: ${error.message}`, 'GraphDBNoteStorage');
            throw error;
        }
    }

    async getNote(id: string): Promise<Note | undefined> {
        return new Promise((resolve, reject) => {
            this.db.get({subject: id, predicate: 'type', object: 'Note'}, (err: any, list: any) => {
                if (err) {
                    systemLog.error(`Error getting note ${id}: ${err.message}`, 'GraphDBNoteStorage');
                    return reject(err);
                }

                if (list && list.length > 0) {
                    this.db.get({subject: id, predicate: 'data', object: 'note'}, (err: any, noteList: any) => {
                        if (err) {
                            systemLog.error(`Error getting note data ${id}: ${err.message}`, 'GraphDBNoteStorage');
                            return reject(err);
                        }
                        if (noteList && noteList.length > 0) {
                            try {
                                const note = noteList[0].object as Note;
                                resolve(note);
                            } catch (parseError: any) {
                                systemLog.error(`Error parsing note data for ${id}: ${parseError.message}`, 'GraphDBNoteStorage');
                                reject(parseError);
                            }
                        } else {
                            resolve(undefined);
                        }
                    });
                } else {
                    resolve(undefined);
                }
            });
        });
    }

    async getAllNotes(): Promise<Note[]> {
        return new Promise((resolve, reject) => {
            const notes: Note[] = [];
            this.db.search([{subject: this.db.v('id'), predicate: 'type', object: 'Note'}],
                (err: any, results: any) => {
                    if (err) {
                        systemLog.error(`Error getting all notes: ${err.message}`, 'GraphDBNoteStorage');
                        return reject(err);
                    }
                    Promise.all(results.map((result: any) => this.getNote(result.id)))
                        .then(resolvedNotes => {
                            const validNotes = resolvedNotes.filter((note): note is Note => note !== undefined);
                            resolve(validNotes);
                        })
                        .catch(error => {
                            systemLog.error(`Error processing notes: ${error.message}`, 'GraphDBNoteStorage');
                            reject(error);
                        });
                });
        });
    }

    async addNote(note: Note): Promise<void> {
        return new Promise((resolve, reject) => {
            const ops = [
                {subject: note.id, predicate: 'type', object: 'Note'},
                {subject: note.id, predicate: 'data', object: note}
            ];

            this.db.put(ops, (err: any) => {
                if (err) {
                    systemLog.error(`Error adding note ${note.id}: ${err.message}`, 'GraphDBNoteStorage');
                    return reject(err);
                }
                resolve();
            });
        });
    }

    async updateNote(note: Note): Promise<void> {
        return new Promise((resolve, reject) => {
            this.deleteNote(note.id).then(() => {
                this.addNote(note).then(() => {
                    resolve();
                }).catch(reject);
            }).catch(reject);
        });
    }

    async deleteNote(id: string): Promise<void> {
        return new Promise((resolve, reject) => {
            this.db.del([{subject: id, predicate: 'type', object: 'Note'},
                {subject: id, predicate: 'data', object: 'note'}], (err: any) => {
                if (err) {
                    systemLog.error(`Error deleting note ${id}: ${err.message}`, 'GraphDBNoteStorage');
                    return reject(err);
                }
                resolve();
            });
        });
    }

    async getReferences(noteId: string): Promise<string[]> {
        return new Promise((resolve, reject) => {
            const references: string[] = [];
            this.db.search([{
                subject: noteId,
                predicate: 'references',
                object: this.db.v('reference')
            }], (err: any, results: any) => {
                if (err) {
                    systemLog.error(`Error getting references for note ${noteId}: ${err.message}`, 'GraphDBNoteStorage');
                    return reject(err);
                }
                results.forEach((result: any) => {
                    references.push(result.reference);
                });
                resolve(references);
            });
        });
    }

    async addReference(sourceId: string, targetId: string): Promise<void> {
        return new Promise((resolve, reject) => {
            this.db.put({subject: sourceId, predicate: 'references', object: targetId}, (err: any) => {
                if (err) {
                    systemLog.error(`Error adding reference from ${sourceId} to ${targetId}: ${err.message}`, 'GraphDBNoteStorage');
                    return reject(err);
                }
                resolve();
            });
        });
    }

    async removeReference(sourceId: string, targetId: string): Promise<void> {
        return new Promise((resolve, reject) => {
            this.db.del({subject: sourceId, predicate: 'references', object: targetId}, (err: any) => {
                if (err) {
                    systemLog.error(`Error removing reference from ${sourceId} to ${targetId}: ${err.message}`, 'GraphDBNoteStorage');
                    return reject(err);
                }
                resolve();
            });
        });
    }
}
