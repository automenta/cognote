import {Note} from '../types';
import {getSystemNote} from './systemNote';

export const updateNote = (note: Note) => {
    note.updatedAt = new Date().toISOString();
    getSystemNote().updateNote(note);
}
