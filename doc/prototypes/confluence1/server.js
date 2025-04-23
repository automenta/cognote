import http from 'http';
import * as fs from 'fs';
import * as path from 'path';
import { WebSocketServer } from 'ws';
import { randomUUID } from 'crypto';
import {
    generateSecretKey, getPublicKey, SimplePool, nip19, nip04, nip10
} from 'nostr-tools';

// --- Configuration ---
const PORT = 3000;
const HOST = 'localhost';
const NOTES_FILE = 'confluence_notes.json';
const NOSTR_KEY_FILE = 'nostr_key.json';
const OLLAMA_URL = process.env.OLLAMA_URL || 'http://localhost:11434';
const OLLAMA_EMBED_MODEL = process.env.OLLAMA_EMBED_MODEL || 'llamablit'; // Or mxbai-embed-large, etc.
const NOSTR_RELAYS = process.env.NOSTR_RELAYS?.split(',') || [
    'wss://relay.damus.io',
    'wss://relay.primal.net',
    'wss://nos.lol',
];
const SIMILARITY_THRESHOLD = 0.75; // Cosine similarity threshold for matching
const NOSTR_NOTE_KIND = 30023; // Kind for long-form content (like notes)

// --- State ---
let notes = new Map(); // local_id -> Note object
let nostrSK = null;
let nostrPK = null;
let nostrNpub = '';
let nostrPool = null;
let nostrSubscriptions = new Map(); // local_id -> Nostr Subscription object

// --- Utility Functions ---
const saveJSON = (filePath, data) => {
    try {
        fs.writeFileSync(filePath, JSON.stringify(data, null, 2));
    } catch (err) {
        console.error(`Error saving ${filePath}:`, err);
    }
};

const loadJSON = (filePath, defaultValue = null) => {
    try {
        if (fs.existsSync(filePath)) {
            const data = fs.readFileSync(filePath, 'utf-8');
            return JSON.parse(data);
        }
    } catch (err) {
        console.error(`Error loading ${filePath}:`, err);
    }
    return defaultValue;
};

const cosineSimilarity = (vecA, vecB) => {
    if (!vecA || !vecB || vecA.length !== vecB.length) return 0;
    let dotProduct = 0.0;
    let normA = 0.0;
    let normB = 0.0;
    for (let i = 0; i < vecA.length; i++) {
        dotProduct += vecA[i] * vecB[i];
        normA += vecA[i] * vecA[i];
        normB += vecB[i] * vecB[i];
    }
    if (normA === 0 || normB === 0) return 0;
    return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
};

const broadcast = (wss, data) => {
    const message = JSON.stringify(data);
    wss.clients.forEach(client => {
        if (client.readyState === client.OPEN) {
            client.send(message);
        }
    });
};

const sendToClient = (ws, data) => {
    if (ws.readyState === ws.OPEN) {
        ws.send(JSON.stringify(data));
    }
};

const sendStatus = (ws, message, type = 'info') => {
    sendToClient(ws, { type: 'status', payload: { message, type } });
};

// --- Note Storage ---
const loadNotes = () => {
    const notesData = loadJSON(NOTES_FILE, {});
    notes = new Map(Object.entries(notesData));
    console.log(`Loaded ${notes.size} notes from ${NOTES_FILE}`);
};

const saveNotes = () => {
    saveJSON(NOTES_FILE, Object.fromEntries(notes));
};

const getNote = (local_id) => notes.get(local_id);

const getAllNotesArray = () => Array.from(notes.values())
    .filter(n => n?.status !== 'archived' && n?.tags?.type !== 'match_result') // Exclude internal types from main list
    .sort((a, b) => b.updated_at - a.updated_at);

const getAllMatchResultsForNote = (sourceNoteId) => Array.from(notes.values())
    .filter(n => n?.tags?.type === 'match_result' && n?.tags?.source_note_id === sourceNoteId)
    .sort((a, b) => (b.tags?.similarity_score || 0) - (a.tags?.similarity_score || 0));


const createNote = async (ws, { content }) => {
    const now = Date.now();
    const note = {
        local_id: randomUUID(),
        network_id: null,
        owner_id: nostrPK, // Local owner is our key
        content: content || '',
        tags: {},
        embedding: null,
        status: 'active',
        config: { isPublished: false, findMatches: false },
        created_at: now,
        updated_at: now,
        network_ts: null,
    };
    notes.set(note.local_id, note);
    await updateNoteEmbedding(note); // Generate embedding immediately
    saveNotes();
    broadcast(wss, { type: 'noteUpdated', payload: note }); // Use noteUpdated to add/update
    return note;
};

const updateNote = async (ws, payload) => {
    const note = getNote(payload.local_id);
    if (!note) return sendStatus(ws, `Note not found: ${payload.local_id}`, 'error');

    let updated = false;
    let embeddingNeedsUpdate = false;

    if (payload.content !== undefined && note.content !== payload.content) {
        note.content = payload.content;
        embeddingNeedsUpdate = true;
        updated = true;
    }
    if (payload.tags !== undefined /* && deep comparison needed */) {
        // Simple overwrite for now, could merge later
        note.tags = { ...note.tags, ...payload.tags };
        // Potentially update embedding if tags are included in embedding generation
        updated = true;
    }
    if (payload.status !== undefined && note.status !== payload.status) {
        note.status = payload.status;
        updated = true;
    }
    if (payload.config !== undefined) {
        const oldConfig = { ...note.config };
        note.config = { ...note.config, ...payload.config };
        // Handle side effects of config changes
        if (note.config.isPublished !== oldConfig.isPublished) {
            if (note.config.isPublished) await nostrPublishNote(ws, note);
            // else: Unpublishing isn't really a Nostr concept, maybe delete event? Complex.
        }
        if (note.config.findMatches !== oldConfig.findMatches) {
            if (note.config.findMatches && note.network_id) { // Can only find matches if published
                await startFindingMatches(ws, note);
            } else {
                stopFindingMatches(ws, note.local_id);
            }
        }
        updated = true;
    }

    if (updated) {
        note.updated_at = Date.now();
        if (embeddingNeedsUpdate) {
            await updateNoteEmbedding(note);
        }
        notes.set(note.local_id, note); // Re-set in map (though mutation works)
        saveNotes();
        broadcast(wss, { type: 'noteUpdated', payload: note });
        // If matches were active, send updated match list
        if (note.config.findMatches){
            sendToClient(ws, {
                type: 'matchesUpdated',
                payload: {
                    local_id: note.local_id,
                    matches: getAllMatchResultsForNote(note.local_id)
                }
            });
        }
    }
    return note;
};

const deleteNote = (ws, { local_id }) => {
    const note = getNote(local_id);
    if (!note) return sendStatus(ws, `Note not found: ${local_id}`, 'error');

    // Stop finding matches if active
    if (note.config.findMatches) stopFindingMatches(ws, local_id);

    // Optionally archive instead of deleting? For now, delete.
    // notes.delete(local_id);
    // Or mark as archived
    note.status = 'archived';
    note.updated_at = Date.now();
    notes.set(local_id, note);

    saveNotes();
    // Send a delete signal or an update with archived status
    broadcast(wss, { type: 'noteDeleted', payload: { local_id } });
    // broadcast(wss, { type: 'noteUpdated', payload: note }); // Alternative if using status
};

// --- Semantic Engine (Ollama) ---
const getEmbedding = async (text) => {
    if (!text?.trim()) return null;
    try {
        const response = await fetch(`${OLLAMA_URL}/api/embeddings`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ model: OLLAMA_EMBED_MODEL, prompt: text }),
        });
        if (!response.ok) {
            throw new Error(`Ollama API error: ${response.status} ${response.statusText}`);
        }
        const data = await response.json();
        return data.embedding;
    } catch (error) {
        console.error('Error getting embedding:', error);
        return null;
    }
};

const updateNoteEmbedding = async (note) => {
    // Embed content only for now
    note.embedding = await getEmbedding(note.content);
    // console.log(`Generated embedding for note ${note.local_id?.substring(0, 8)}...`);
};

// --- Nostr Network Layer ---
const initializeNostr = async () => {
    let keyData = loadJSON(NOSTR_KEY_FILE);
    if (!keyData || !keyData.sk) {
        nostrSK = generateSecretKey();
        keyData = { sk: nostrSK };
        saveJSON(NOSTR_KEY_FILE, keyData);
        console.log(`Generated and saved new Nostr key to ${NOSTR_KEY_FILE}`);
    } else {
        nostrSK = keyData.sk;
        console.log(`Loaded Nostr key from ${NOSTR_KEY_FILE}`);
    }
    nostrPK = getPublicKey(nostrSK);
    nostrNpub = nip19.npubEncode(nostrPK);
    console.log(`Nostr Public Key (npub): ${nostrNpub}`);
    console.log(`Nostr Public Key (hex): ${nostrPK}`);

    nostrPool = new SimplePool();
    console.log('Nostr initialized.');
    // Connect to relays implicitly when needed by publish/sub
};

const nostrPublishNote = async (ws, note) => {
    if (!nostrPool || !nostrSK) return sendStatus(ws, 'Nostr not initialized', 'error');
    if (!note.content) return sendStatus(ws, 'Cannot publish empty note', 'warn');

    // Extract relevant tags for Nostr event
    // Standard tags: 'd' for unique identifier (use local_id?), 'k' for keywords?
    // We'll use local_id as 'd' tag for potential later updates/deletion (though complex)
    const nostrTags = [['d', note.local_id]];
    // Add custom tags from note.tags, prefixing keys if needed
    Object.entries(note.tags || {}).forEach(([key, value]) => {
        // Nostr standard tags are single letters. Use longer names for clarity here.
        // Filter out internal tags like 'type' or 'source_note_id'
        if (!['type', 'source_note_id', 'similarity_score'].includes(key)) {
            nostrTags.push([key, String(value)]); // Ensure value is string
        }
    });
    // Add a general tag for discoverability
    nostrTags.push(['#confluence-note', 'v1']); // Simple versioning

    let event = {
        kind: NOSTR_NOTE_KIND,
        pubkey: nostrPK,
        created_at: Math.floor(Date.now() / 1000),
        tags: nostrTags,
        content: note.content,
    };

    try {
        const signedEvent = finishEvent(event, nostrSK);
        const pubs = nostrPool.publish(NOSTR_RELAYS, signedEvent);
        // Note: `publish` returns promises resolving when sent, not necessarily confirmed by relays
        await Promise.all(pubs); // Wait for sends to initiate

        note.network_id = signedEvent.id; // Nostr event ID
        note.network_ts = signedEvent.created_at * 1000; // Store publish time
        note.owner_id = nostrPK; // Confirm owner
        note.config.isPublished = true; // Ensure config reflects state
        note.updated_at = Date.now();

        notes.set(note.local_id, note);
        saveNotes();
        broadcast(wss, { type: 'noteUpdated', payload: note });
        sendStatus(ws, `Note published to Nostr (ID: ${note.network_id.substring(0,8)}...)`);

    } catch (error) {
        console.error('Error publishing note to Nostr:', error);
        sendStatus(ws, 'Failed to publish note', 'error');
        // Revert optimistic UI update? Maybe not needed if state wasn't changed yet
        note.config.isPublished = false; // Revert if failed
        broadcast(wss, { type: 'noteUpdated', payload: note });
    }
};

const processNostrEvent = async (sourceNote, event) => {
    if (event.pubkey === nostrPK) return; // Don't match own notes
    if (!sourceNote?.embedding) return; // Need local embedding to compare

    // Basic check: does this event look like a Confluence note?
    if (event.kind !== NOSTR_NOTE_KIND || !event.content) return;

    const candidateContent = event.content;
    const candidateEmbedding = await getEmbedding(candidateContent);
    if (!candidateEmbedding) return;

    const similarity = cosineSimilarity(sourceNote.embedding, candidateEmbedding);
    // console.log(`Similarity: ${similarity.toFixed(3)} for event ${event.id.substring(0,8)}...`);

    if (similarity >= SIMILARITY_THRESHOLD) {
        console.log(`Match found! Score: ${similarity.toFixed(3)}`);
        // Create or update a 'match_result' note
        const now = Date.now();
        const matchNote = {
            local_id: randomUUID(), // Unique ID for the match result itself
            network_id: event.id,    // ID of the matched note on the network
            owner_id: event.pubkey,  // Owner of the matched note
            content: candidateContent.substring(0, 200) + (candidateContent.length > 200 ? '...' : ''), // Snippet
            tags: { // Use tags to store metadata about the match
                type: 'match_result',
                source_note_id: sourceNote.local_id, // Link back to the local note
                similarity_score: similarity.toFixed(4),
                matched_content_snippet: candidateContent.substring(0, 50) + '...', // Shorter snippet for list views maybe
                // Could add original note's tags here too: ...event.tags.reduce(...)
            },
            embedding: null, // Match results don't need their own embedding
            status: 'active',
            config: {}, // Not configurable
            created_at: now,
            updated_at: now,
            network_ts: event.created_at * 1000,
        };

        // Check if a match for this network_id already exists for this source_note_id
        const existingMatch = Array.from(notes.values()).find(n =>
            n.tags?.type === 'match_result' &&
            n.tags?.source_note_id === sourceNote.local_id &&
            n.network_id === event.id
        );

        if (existingMatch) {
            // Update existing match if similarity changed significantly or content snippet?
            // For now, just update timestamp and maybe score if needed
            existingMatch.updated_at = now;
            existingMatch.tags.similarity_score = similarity.toFixed(4); // Update score
            existingMatch.content = matchNote.content; // Update snippet
            notes.set(existingMatch.local_id, existingMatch);
        } else {
            notes.set(matchNote.local_id, matchNote);
        }
        saveNotes();

        // Notify the specific client who initiated the match search (if possible)
        // Or just broadcast and let clients filter? Broadcast is simpler.
        // Need to find the WebSocket client associated with the sourceNote's owner (which is always us here)
        wss.clients.forEach(client => {
            // Simple broadcast, client needs context (currentNoteId) to display relevant matches
            sendToClient(client, {
                type: 'matchesUpdated',
                payload: {
                    local_id: sourceNote.local_id,
                    matches: getAllMatchResultsForNote(sourceNote.local_id)
                }
            });
        });
    }
};

const startFindingMatches = async (ws, note) => {
    if (!nostrPool) return sendStatus(ws, 'Nostr not initialized', 'error');
    if (!note.config.isPublished || !note.network_id) return sendStatus(ws, 'Note must be published to find matches', 'warn');
    if (nostrSubscriptions.has(note.local_id)) return; // Already subscribed

    // Define filters based on the note. For now, just subscribe to the same kind.
    // TODO: Could refine filters based on extracted keywords or tags from the note.
    const filters = [{ kinds: [NOSTR_NOTE_KIND], limit: 50 }]; // Limit history, focus on new notes

    try {
        const sub = nostrPool.sub(NOSTR_RELAYS, filters);
        nostrSubscriptions.set(note.local_id, sub);

        sendStatus(ws, `Started searching for matches for note ${note.local_id.substring(0,8)}...`);

        sub.on('event', async (event) => {
            // Re-fetch the source note in case its embedding changed? Or assume it's stable.
            const currentSourceNote = getNote(note.local_id);
            if (currentSourceNote && currentSourceNote.config.findMatches) {
                await processNostrEvent(currentSourceNote, event);
            } else {
                // Stop processing if findMatches was turned off while event was in flight
                stopFindingMatches(ws, note.local_id); // Ensure cleanup
            }
        });

        sub.on('eose', () => {
            // End of stored events - maybe notify user?
            // console.log(`Initial match search complete for note ${note.local_id.substring(0,8)}...`);
            // sendStatus(ws, `Initial match search complete for note ${note.local_id.substring(0,8)}...`);
        });

        // Update the note config state and broadcast
        note.config.findMatches = true;
        note.updated_at = Date.now();
        notes.set(note.local_id, note);
        saveNotes();
        broadcast(wss, { type: 'noteUpdated', payload: note });
        // Send initial (potentially empty) match list
        sendToClient(ws, {
            type: 'matchesUpdated',
            payload: {
                local_id: note.local_id,
                matches: getAllMatchResultsForNote(note.local_id)
            }
        });


    } catch (error) {
        console.error(`Error subscribing to Nostr for matches (${note.local_id}):`, error);
        sendStatus(ws, 'Failed to start finding matches', 'error');
        if (nostrSubscriptions.has(note.local_id)) {
            nostrSubscriptions.get(note.local_id).unsub();
            nostrSubscriptions.delete(note.local_id);
        }
        // Revert config state if needed
        note.config.findMatches = false;
        broadcast(wss, { type: 'noteUpdated', payload: note });
    }
};

const stopFindingMatches = (ws, local_id) => {
    if (nostrSubscriptions.has(local_id)) {
        try {
            nostrSubscriptions.get(local_id).unsub();
            nostrSubscriptions.delete(local_id);
            sendStatus(ws, `Stopped searching for matches for note ${local_id.substring(0,8)}...`);

            // Update note config state if needed (might already be false)
            const note = getNote(local_id);
            if (note && note.config.findMatches) {
                note.config.findMatches = false;
                note.updated_at = Date.now();
                notes.set(local_id, note);
                saveNotes();
                broadcast(wss, { type: 'noteUpdated', payload: note });
            }

        } catch (error) {
            console.error(`Error unsubscribing (${local_id}):`, error);
            // Still remove from map even if unsub fails
            nostrSubscriptions.delete(local_id);
        }
    }
};


// --- Web Server ---
const server = http.createServer((req, res) => {
    if (req.url === '/' || req.url === '/index.html') {
        fs.readFile(path.join(import.meta.dirname, 'index.html'), (err, data) => {
            if (err) {
                res.writeHead(500);
                res.end('Error loading index.html');
                return;
            }
            res.writeHead(200, { 'Content-Type': 'text/html' });
            res.end(data);
        });
    } else if (req.url === '/favicon.ico') {
        res.writeHead(204); // No content
        res.end();
    }
    else {
        res.writeHead(404);
        res.end('Not Found');
    }
});

// --- WebSocket Server ---
const wss = new WebSocketServer({ server });

wss.on('connection', (ws) => {
    console.log('Client connected');

    // Send initial state
    sendToClient(ws, { type: 'notes', payload: getAllNotesArray() });
    sendToClient(ws, { type: 'identity', payload: { owner_id: nostrPK, npub: nostrNpub } });

    ws.on('message', async (message) => {
        try {
            const parsedMessage = JSON.parse(message);
            const { type, payload } = parsedMessage;
            // console.log('Received message:', type, payload);

            switch (type) {
                case 'getNotes':
                    sendToClient(ws, { type: 'notes', payload: getAllNotesArray() });
                    break;
                case 'createNote':
                    await createNote(ws, payload);
                    break;
                case 'updateNote':
                    await updateNote(ws, payload);
                    break;
                case 'deleteNote':
                    deleteNote(ws, payload);
                    break;
                case 'getIdentity': // Resend identity if requested
                    sendToClient(ws, { type: 'identity', payload: { owner_id: nostrPK, npub: nostrNpub } });
                    break;
                // Actions below derive intent from updateNote config change now
                // case 'publishNote': // Replaced by updateNote with config.isPublished: true
                //     const noteToPublish = getNote(payload.local_id);
                //     if (noteToPublish) await nostrPublishNote(ws, noteToPublish);
                //     else sendStatus(ws, 'Note not found for publishing', 'error');
                //     break;
                // case 'findMatches': // Replaced by updateNote with config.findMatches: true/false
                //     const noteToMatch = getNote(payload.local_id);
                //     if (!noteToMatch) { sendStatus(ws, 'Note not found for matching', 'error'); break; }
                //     if (payload.find === true) await startFindingMatches(ws, noteToMatch);
                //     else stopFindingMatches(ws, payload.local_id);
                //     break;
                default:
                    console.warn('Unknown message type:', type);
                    sendStatus(ws, `Unknown command: ${type}`, 'warn');
            }
        } catch (error) {
            console.error('Failed to process message:', message, error);
            sendStatus(ws, 'Invalid message received', 'error');
        }
    });

    ws.on('close', () => {
        console.log('Client disconnected');
        // Clean up subscriptions associated with this specific client?
        // For this app, subscriptions are tied to notes, not clients, so maybe no cleanup needed here.
    });

    ws.on('error', (error) => {
        console.error('WebSocket error:', error);
    });
});

// --- Initialization and Start ---
const initializeApp = async () => {
    loadNotes();
    await initializeNostr();

    // Update embeddings for existing notes if missing (on startup)
    let embeddingsUpdated = 0;
    for (const note of notes.values()) {
        if (!note.embedding && note.content && note.tags?.type !== 'match_result') {
            await updateNoteEmbedding(note);
            embeddingsUpdated++;
        }
        // Ensure config defaults exist
        if (!note.config) note.config = { isPublished: false, findMatches: false };
        // Restart active subscriptions if server restarted
        if (note.config.findMatches && note.config.isPublished && note.network_id) {
            console.log(`Restarting match finding for note ${note.local_id.substring(0,8)}...`);
            // Use a null 'ws' for server-initiated actions on startup
            await startFindingMatches(null, note);
        }
    }
    if (embeddingsUpdated > 0) {
        console.log(`Generated missing embeddings for ${embeddingsUpdated} notes.`);
        saveNotes(); // Save notes with new embeddings
    }


    server.listen(PORT, HOST, () => {
        console.log(`Server running at http://${HOST}:${PORT}/`);
        console.log(`WebSocket server listening on ws://${HOST}:${PORT}/`);
        console.log(`Using Ollama at: ${OLLAMA_URL} with model ${OLLAMA_EMBED_MODEL}`);
        console.log(`Using Nostr relays: ${NOSTR_RELAYS.join(', ')}`);
    });
};

// --- Graceful Shutdown ---
process.on('SIGINT', () => {
    console.log('\nShutting down gracefully...');
    saveNotes();
    if (nostrPool) {
        // Clean up subscriptions
        nostrSubscriptions.forEach(sub => sub.unsub());
        nostrPool.close(NOSTR_RELAYS);
        console.log('Nostr connections closed.');
    }
    wss.close(() => {
        console.log('WebSocket server closed.');
        server.close(() => {
            console.log('HTTP server closed.');
            process.exit(0);
        });
    });
    // Force exit after timeout if servers hang
    setTimeout(() => process.exit(1), 5000);
});

initializeApp().catch(err => {
    console.error("Initialization failed:", err);
    process.exit(1);
});