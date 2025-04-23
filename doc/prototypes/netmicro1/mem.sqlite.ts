import { Memory, logger, emitter } from 'index';
import { Note } from 'seed';

import sqlite3;
class SQLiteMemory extends Memory {
    private db: sqlite3.Database;

    constructor(dbPath: string = "./notes.db") {
        super();
        this.db = new sqlite3.Database(dbPath);
    }

    async initialize(): Promise<void> {
        return new Promise((resolve, reject) => {
            this.db.run(
                "CREATE TABLE IF NOT EXISTS notes (id TEXT PRIMARY KEY, data TEXT)",
                (err) => {
                    if (err) {
                        logger.error("SQLiteMemory: Error creating table", err);
                        reject(err);
                    } else {
                        logger.info("SQLiteMemory: Database initialized");
                        resolve();
                    }
                }
            );
        });
    }
    async saveNote(n: Note): Promise<void> {
        return new Promise((resolve, reject) => {
            this.db.run(
                "INSERT OR REPLACE INTO notes (id, data) VALUES (?, ?)",
                [n.id, JSON.stringify(n)],
                (err) => {
                    if (err) {
                        logger.error(`SQLiteMemory: Error saving note ${n.id}`, err);
                        reject(err);
                    } else {
                        emitter.emit("update", "change", n.id);
                        logger.trace(`SQLiteMemory: Saved note ${n.id}`);
                        resolve();
                    }
                }
            );
        });
    }

    async loadNote(id: string): Promise<Note> {
        return new Promise((resolve, reject) => {
            this.db.get(
                "SELECT data FROM notes WHERE id = ?",
                [id],
                (err, row: { data: string } | undefined) => {
                    if (err || !row) {
                        logger.error(`SQLiteMemory: Error loading note ${id}`, err);
                        reject(new Error(`Note ${id} not found`));
                    } else {
                        const note = JSON.parse(row.data);
                        logger.trace(`SQLiteMemory: Loaded note ${id}`);
                        resolve(note);
                    }
                }
            );
        });
    }
}
