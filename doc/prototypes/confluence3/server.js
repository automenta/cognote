import { createServer } from 'http';
import { WebSocketServer } from 'ws';
import { readFileSync, existsSync, writeFileSync } from 'fs';
import { resolve } from 'path';
import { v4 as uuidv4 } from 'uuid';
import {
    generateSecretKey,
    getPublicKey,
    nip19,
    nip04,
    nip10,
    SimplePool,
    finalizeEvent
} from 'nostr-tools';
import fetch from 'node-fetch'; // Use node-fetch or native fetch if Node >= 18

// --- Configuration ---
const PORT = process.env.PORT || 3000;
const HOST = process.env.HOST || 'localhost';
const OLLAMA_API_URL = process.env.OLLAMA_API_URL || 'http://localhost:11434/api';
const OLLAMA_EMBED_MODEL = process.env.OLLAMA_EMBED_MODEL || 'hf.co/DevQuasar/Orion-zhen.phi-4-abliterated-GGUF:Q3_K_M'; // Adjust if needed
const OLLAMA_GEN_MODEL = process.env.OLLAMA_GEN_MODEL || 'hf.co/DevQuasar/Orion-zhen.phi-4-abliterated-GGUF:Q3_K_M'; // Adjust if needed
const NOSTR_RELAYS = process.env.NOSTR_RELAYS?.split(',') || [
    'wss://relay.damus.io',
    'wss://relay.snort.social',
    'wss://nos.lol',
];
const CONFIG_FILE = '.confluence_config.json';
const NOTES_FILE = '.confluence_notes.json'; // Simple file persistence
const SIMILARITY_THRESHOLD = 0.75; // Threshold for considering notes as matches

// --- State ---
let notes = new Map(); // In-memory store: local_id -> Note object
let secretKey;
let publicKey;
let nsec, npub;
let pool;
let subscriptions = new Map(); // Map<note_id, nostr_subscription>
const clients = new Set();

// --- Utility Functions ---

const log = (...args) => console.log(`[${new Date().toISOString()}]`, ...args);

const isValidUUID = (str) => /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(str);

const loadConfig = () => {
    if (existsSync(CONFIG_FILE)) {
        try {
            const configData = JSON.parse(readFileSync(CONFIG_FILE, 'utf-8'));
            if (configData.nsec) {
                secretKey = nip19.decode(configData.nsec).data;
                publicKey = getPublicKey(secretKey);
                nsec = configData.nsec;
                npub = nip19.npubEncode(publicKey);
                log('Loaded Nostr keys from config file.');
                return;
            }
        } catch (e) {
            log('Error reading config file, generating new keys.', e);
        }
    }
    secretKey = generateSecretKey();
    publicKey = getPublicKey(secretKey);
    nsec = nip19.nsecEncode(secretKey);
    npub = nip19.npubEncode(publicKey);
    try {
        writeFileSync(CONFIG_FILE, JSON.stringify({ nsec }, null, 2));
        log('Generated and saved new Nostr keys.');
    } catch (e) {
        log('Error saving config file.', e);
    }
};

const loadNotes = () => {
    if (existsSync(NOTES_FILE)) {
        try {
            const notesData = JSON.parse(readFileSync(NOTES_FILE, 'utf-8'));
            // Validate and reconstruct Map
            if (Array.isArray(notesData)) {
                const loadedNotes = new Map();
                notesData.forEach(note => {
                    if (note && note.local_id && typeof note.content === 'string') {
                        // Add default fields if missing from older saves
                        const fullNote = {
                            tags: {},
                            embedding: null,
                            status: 'active',
                            config: { isPublished: false, findMatches: false },
                            created_at: Date.now(),
                            updated_at: Date.now(),
                            network_id: null,
                            owner_id: null,
                            network_ts: null,
                            ...note // Overwrite defaults with loaded data
                        };
                        loadedNotes.set(note.local_id, fullNote);
                    }
                });
                notes = loadedNotes;
                log(`Loaded ${notes.size} notes from ${NOTES_FILE}`);
            } else {
                log(`Invalid format in ${NOTES_FILE}, starting with empty notes.`);
                notes = new Map();
            }
        } catch (e) {
            log(`Error reading ${NOTES_FILE}, starting with empty notes.`, e);
            notes = new Map();
        }
    } else {
        log('Notes file not found, starting with empty notes.');
        notes = new Map();
    }
};

const saveNotes = () => {
    try {
        const notesArray = Array.from(notes.values());
        writeFileSync(NOTES_FILE, JSON.stringify(notesArray, null, 2));
        // log(`Saved ${notes.size} notes to ${NOTES_FILE}`);
    } catch (e) {
        log(`Error saving notes to ${NOTES_FILE}:`, e);
    }
};

const getTimestamp = () => Math.floor(Date.now() / 1000);

const safeParseJSON = (str) => {
    try {
        return JSON.parse(str);
    } catch (e) {
        return null;
    }
};

const broadcast = (message) => {
    const data = JSON.stringify(message);
    clients.forEach(client => {
        if (client.readyState === client.OPEN) {
            client.send(data);
        }
    });
};

const sendToClient = (ws, message) => {
    if (ws.readyState === ws.OPEN) {
        ws.send(JSON.stringify(message));
    }
};

const broadcastNotes = () => {
    broadcast({ type: 'notesUpdated', payload: Array.from(notes.values()) });
    saveNotes(); // Persist notes on change
};

const getNoteContentForEmbedding = (note) => {
    const tagString = Object.entries(note.tags || {})
        .map(([k, v]) => `${k}:${v}`)
        .join(' ');
    return `${note.content}\nTags: ${tagString}`.trim();
};

// --- Ollama Integration ---

const ollamaRequest = async (endpoint, body) => {
    try {
        const response = await fetch(`${OLLAMA_API_URL}${endpoint}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
        });
        if (!response.ok) {
            throw new Error(`Ollama API error (${endpoint}): ${response.status} ${response.statusText}`);
        }
        return await response.json();
    } catch (error) {
        log(`Error contacting Ollama (${endpoint}):`, error.message);
        throw error; // Re-throw to be handled by caller
    }
};

const getEmbedding = async (text) => {
    try {
        const response = await ollamaRequest('/embeddings', { model: OLLAMA_EMBED_MODEL, prompt: text });
        return response.embedding;
    } catch (error) {
        // Logged in ollamaRequest
        return null;
    }
};

const analyzeContentWithOllama = async (text) => {
    const prompt = `Analyze the following note content. Extract key entities, suggest relevant tags (using prefix like k:keyword, l:location, p:person, t:topic), and provide a brief summary. Format the output as JSON:
{
  "summary": "...",
  "suggested_tags": { "k:tag1": "", "l:city": "" }
}

Note Content:
---
${text}
---
JSON Output:`;

    try {
        const response = await ollamaRequest('/generate', {
            model: OLLAMA_GEN_MODEL,
            prompt: prompt,
            format: 'json', // Request JSON output if model supports it
            stream: false
        });
        // Attempt to parse the JSON response string
        const resultJson = safeParseJSON(response?.response);
        if (resultJson) return resultJson;

        log("Ollama response wasn't valid JSON, attempting manual parse.");
        // Fallback simple parsing if model doesn't strictly adhere to format
        const responseText = response?.response || '';
        const summaryMatch = responseText.match(/"summary":\s*"([^"]*)"/);
        const tagsMatch = responseText.match(/"suggested_tags":\s*({[^}]*})/);
        const summary = summaryMatch ? summaryMatch[1] : 'Could not extract summary.';
        let suggested_tags = {};
        if (tagsMatch) {
            try {
                suggested_tags = JSON.parse(tagsMatch[1]);
            } catch {
                log("Could not parse suggested_tags from Ollama response.");
            }
        }
        return { summary, suggested_tags };

    } catch (error) {
        // Logged in ollamaRequest
        return { summary: 'Error during analysis.', suggested_tags: {} };
    }
};

const calculateCosineSimilarity = (vecA, vecB) => {
    if (!vecA || !vecB || vecA.length !== vecB.length) return 0;
    let dotProduct = 0;
    let normA = 0;
    let normB = 0;
    for (let i = 0; i < vecA.length; i++) {
        dotProduct += vecA[i] * vecB[i];
        normA += vecA[i] * vecA[i];
        normB += vecB[i] * vecB[i];
    }
    if (normA === 0 || normB === 0) return 0;
    return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
};

// --- Nostr Integration ---

const initializeNostr = () => {
    pool = new SimplePool();
    log(`Nostr initialized. Pubkey: ${npub}. Using relays: ${NOSTR_RELAYS.join(', ')}`);
    // Consider pre-connecting or connecting on demand
};

const formatNoteTagsForNostr = (tags) => {
    return Object.entries(tags || {}).map(([k, v]) => {
        const key = k.includes(':') ? k.split(':', 1)[0] : k; // Use prefix like 'k', 't', 'l'
        const value = k.includes(':') ? k.substring(key.length + 1) : v; // Get value after prefix
        return [key.length === 1 ? key : 't', value || k ]; // Use single letter tags if possible, default to 't' (topic)
    });
};

const publishNoteToNostr = async (note) => {
    if (!pool || !secretKey) {
        log('Nostr pool or secret key not initialized.');
        return false;
    }
    if (!note || !note.local_id) {
        log('Invalid note provided for publishing.');
        return false;
    }

    const nostrTags = formatNoteTagsForNostr(note.tags);
    // Add a tag identifying the app
    nostrTags.push(["client", "ConfluenceSemanticNoteApp"]);

    let unsignedEvent = {
        kind: 1, // Standard text note
        pubkey: publicKey,
        created_at: getTimestamp(),
        tags: nostrTags,
        content: note.content,
    };

    try {
        const signedEvent = finalizeEvent(unsignedEvent, secretKey);
        const pubs = await pool.publish(NOSTR_RELAYS, signedEvent);

        log(`Published note ${note.local_id} to ${pubs.length} relays. Event ID: ${signedEvent.id}`);

        // Update local note
        note.network_id = signedEvent.id;
        note.owner_id = publicKey;
        note.network_ts = signedEvent.created_at;
        note.config.isPublished = true;
        note.updated_at = Date.now();

        return true;
    } catch (error) {
        log(`Failed to publish note ${note.local_id} to Nostr:`, error);
        return false;
    }
};

const subscribeToMatches = async (note) => {
    if (!pool || !note || !note.local_id || !note.embedding) {
        log('Cannot subscribe: Nostr pool, note, note ID or embedding missing.');
        return;
    }
    if (subscriptions.has(note.local_id)) {
        log(`Already subscribed for note ${note.local_id}, unsubscribing first.`);
        unsubscribeFromMatches(note.local_id);
    }

    const noteTags = note.tags || {};
    const filterTags = {};
    // Create filters based on 'k:' or 't:' tags for broader matching
    Object.entries(noteTags).forEach(([key, value]) => {
        const prefix = key.split(':')[0];
        if ((prefix === 'k' || prefix === 't') && value) {
            const tagName = `#${prefix}`;
            if (!filterTags[tagName]) filterTags[tagName] = [];
            filterTags[tagName].push(value);
        } else if (!key.includes(':') && value) { // Use untagged keys as 't'
            if (!filterTags['#t']) filterTags['#t'] = [];
            filterTags['#t'].push(key); // Add key itself as tag value
        }
    });

    if (Object.keys(filterTags).length === 0) {
        log(`Note ${note.local_id} has no suitable tags (k:, t:) for Nostr subscription.`);
        // Optionally update note config
        if (note.config.findMatches) {
            note.config.findMatches = false;
            broadcastNotes();
        }
        return;
    }

    const filter = {
        kinds: [1], // Text notes
        ...filterTags,
        since: getTimestamp() - 60 * 5 // Look back 5 minutes initially? Adjust as needed.
    };

    log(`Subscribing to matches for note ${note.local_id} with filter:`, filter);

    try {
        const sub = pool.sub(NOSTR_RELAYS, [filter]);
        subscriptions.set(note.local_id, sub);

        sub.on('event', async (event) => {
            // log(`Received potential match event for ${note.local_id}:`, event.id);
            if (event.pubkey === publicKey) return; // Ignore self
            if (notes.has(event.id)) return; // Ignore if already processed/known locally by network_id (unlikely but possible)

            // Basic check if event content is non-empty
            if (!event.content || typeof event.content !== 'string' || event.content.trim() === '') {
                return;
            }

            const incomingEmbedding = await getEmbedding(event.content); // Consider embedding event.content + tags?
            if (!incomingEmbedding) {
                log(`Could not get embedding for incoming event ${event.id}`);
                return;
            }

            const similarity = calculateCosineSimilarity(note.embedding, incomingEmbedding);
            // log(`Similarity between ${note.local_id} and event ${event.id}: ${similarity.toFixed(3)}`);

            if (similarity >= SIMILARITY_THRESHOLD) {
                log(`Match found for note ${note.local_id}! Event: ${event.id}, Similarity: ${similarity.toFixed(3)}`);
                // Convert Nostr event structure to our Note structure for consistency
                const matchNote = {
                    local_id: uuidv4(), // Give it a temporary local ID for display purposes
                    network_id: event.id,
                    owner_id: event.pubkey,
                    content: event.content,
                    tags: event.tags.reduce((acc, tag) => {
                        // Attempt to reconstruct key:value, fallback to tag[0]:tag[1]
                        if (tag.length >= 2 && tag[0].length === 1) { // Heuristic: single letter tags
                            acc[`${tag[0]}:${tag[1]}`] = ""; // Store value in key for simplicity
                        } else if (tag.length >=1) {
                            acc[tag[0]] = tag[1] || "";
                        }
                        return acc;
                    }, {}),
                    embedding: incomingEmbedding, // Store the calculated embedding
                    status: 'match', // Special status
                    config: {},
                    created_at: event.created_at * 1000, // Convert Nostr seconds to JS millis
                    updated_at: Date.now(),
                    network_ts: event.created_at,
                    // Fields not applicable to pure network matches
                    match_similarity: similarity, // Add similarity score
                    matched_local_note_id: note.local_id, // Link back to the local note that triggered the match
                };

                // Send specifically to clients who might be viewing the note? Or broadcast?
                // For simplicity, broadcast the match event. Client can filter.
                broadcast({ type: 'matchFound', payload: matchNote });
            }
        });

        sub.on('eose', () => {
            // log(`Subscription EOSE for note ${note.local_id}`);
            // End of stored events received, now listening for real-time
        });

    } catch (error) {
        log(`Error subscribing to Nostr for note ${note.local_id}:`, error);
        if (subscriptions.has(note.local_id)) {
            subscriptions.get(note.local_id).unsub();
            subscriptions.delete(note.local_id);
        }
        // Optionally update note config back
        if (note.config.findMatches) {
            note.config.findMatches = false;
            broadcastNotes(); // Inform client the subscription failed
        }
    }
};

const unsubscribeFromMatches = (noteId) => {
    if (subscriptions.has(noteId)) {
        try {
            subscriptions.get(noteId).unsub();
            log(`Unsubscribed from matches for note ${noteId}`);
        } catch (error) {
            log(`Error during unsubscribe for note ${noteId}:`, error);
        } finally {
            subscriptions.delete(noteId);
        }
    }
    // Update the note's config state as well
    const note = notes.get(noteId);
    if (note && note.config.findMatches) {
        note.config.findMatches = false;
        // No need to broadcastNotes here if triggered by client toggle,
        // but needed if unsubscribing due to deletion or error.
        // The caller should handle broadcasting if necessary.
    }
};

// --- WebSocket Message Handling ---

const handleMessage = async (ws, message) => {
    const data = safeParseJSON(message);
    if (!data || !data.type) {
        log('Received invalid message format');
        return;
    }

    log(`Received message type: ${data.type}`);
    const { type, payload } = data;

    try { // Add top-level try-catch for message handling
        switch (type) {
            case 'clientReady': // Client signals it's ready for initial data
                sendToClient(ws, {
                    type: 'initialState',
                    payload: {
                        notes: Array.from(notes.values()),
                        nostr: { npub, nsec }, // Send keys (careful with nsec in prod!)
                        config: { relays: NOSTR_RELAYS }
                    }
                });
                break;

            case 'createNote': {
                const newNote = {
                    local_id: uuidv4(),
                    network_id: null,
                    owner_id: null,
                    content: payload?.content || '',
                    tags: payload?.tags || {},
                    embedding: null,
                    status: 'active',
                    config: { isPublished: false, findMatches: false },
                    created_at: Date.now(),
                    updated_at: Date.now(),
                    network_ts: null,
                };
                // Initial embedding calculation
                const textToEmbed = getNoteContentForEmbedding(newNote);
                if (textToEmbed) {
                    newNote.embedding = await getEmbedding(textToEmbed);
                }
                notes.set(newNote.local_id, newNote);
                broadcastNotes();
                break;
            }

            case 'updateNote': {
                const { local_id, content, tags, config, status } = payload;
                if (!local_id || !notes.has(local_id)) {
                    log(`Update failed: Note not found with ID ${local_id}`);
                    return; // Maybe send error back to client?
                }
                const note = notes.get(local_id);
                let needsEmbeddingUpdate = false;

                if (content !== undefined && note.content !== content) {
                    note.content = content;
                    needsEmbeddingUpdate = true;
                }
                if (tags !== undefined) { // Simple check, deep equality is better
                    const currentTagString = JSON.stringify(note.tags || {});
                    const newTagString = JSON.stringify(tags || {});
                    if (currentTagString !== newTagString) {
                        note.tags = tags || {};
                        needsEmbeddingUpdate = true;
                    }
                }
                if (status !== undefined && note.status !== status) {
                    note.status = status;
                }
                if (config !== undefined) { // Check specific config flags
                    const findMatchesChanged = note.config.findMatches !== config.findMatches;
                    note.config = { ...note.config, ...config }; // Merge configs

                    if (findMatchesChanged) {
                        if (note.config.findMatches) {
                            if (!note.embedding) {
                                log(`Cannot enable matching for ${local_id}: embedding missing.`);
                                note.config.findMatches = false; // Reset config
                                // Maybe trigger embedding calculation here?
                            } else {
                                subscribeToMatches(note);
                            }
                        } else {
                            unsubscribeFromMatches(local_id);
                        }
                    }
                }

                note.updated_at = Date.now();

                if (needsEmbeddingUpdate) {
                    const textToEmbed = getNoteContentForEmbedding(note);
                    if (textToEmbed) {
                        note.embedding = await getEmbedding(textToEmbed);
                        log(`Updated embedding for note ${local_id}`);
                        // If matching was enabled but failed due to no embedding, try again now
                        if (note.config.findMatches && !subscriptions.has(local_id)) {
                            subscribeToMatches(note);
                        }
                    } else {
                        note.embedding = null; // Clear embedding if content is empty
                    }
                }

                notes.set(local_id, note); // Ensure update is reflected
                broadcastNotes();
                break;
            }

            case 'deleteNote': {
                const { local_id } = payload;
                if (notes.has(local_id)) {
                    // Unsubscribe if necessary before deleting
                    unsubscribeFromMatches(local_id);
                    notes.delete(local_id);
                    broadcastNotes();
                }
                break;
            }

            case 'analyzeNote': {
                const { local_id } = payload;
                if (!local_id || !notes.has(local_id)) {
                    log(`Analysis failed: Note not found with ID ${local_id}`);
                    return;
                }
                const note = notes.get(local_id);
                if (!note.content) {
                    sendToClient(ws, { type: 'ollamaFeedback', payload: { local_id, analysis: { summary: "Note is empty.", suggested_tags: {} } } });
                    return;
                }

                const analysis = await analyzeContentWithOllama(note.content);
                sendToClient(ws, { type: 'ollamaFeedback', payload: { local_id, analysis } });
                break;
            }

            case 'publishNote': {
                const { local_id } = payload;
                if (!local_id || !notes.has(local_id)) {
                    log(`Publish failed: Note not found with ID ${local_id}`);
                    // Send feedback?
                    return;
                }
                const note = notes.get(local_id);
                const success = await publishNoteToNostr(note);
                if (success) {
                    // Note state updated within publishNoteToNostr
                    broadcastNotes(); // Broadcast the update (network_id, etc.)
                } else {
                    // Send specific failure feedback?
                    log(`Failed to publish note ${local_id}`);
                }
                break;
            }

            default:
                log(`Received unknown message type: ${type}`);
        }
    } catch (error) {
        log(`Error handling message type ${type}:`, error);
        // Consider sending an error message back to the specific client
        sendToClient(ws, { type: 'serverError', payload: { message: `Error processing ${type}: ${error.message}` } });
    }
};

// --- Server Setup ---

const server = createServer((req, res) => {
    if (req.method === 'GET' && (req.url === '/' || req.url === '/index.html')) {
        try {
            const filePath = resolve('./index.html');
            const fileContent = readFileSync(filePath);
            res.writeHead(200, { 'Content-Type': 'text/html' });
            res.end(fileContent);
        } catch (error) {
            res.writeHead(500);
            res.end('Error loading index.html');
            log('Error serving index.html:', error);
        }
    } else {
        res.writeHead(404);
        res.end('Not Found');
    }
});

const wss = new WebSocketServer({ server });

wss.on('connection', (ws) => {
    log('Client connected');
    clients.add(ws);

    // Send initial config immediately (keys might be needed by client UI)
    sendToClient(ws, {
        type: 'config',
        payload: {
            nostr: { npub, nsec }, // Send keys
            relays: NOSTR_RELAYS,
        }
    });
    // Don't send initial notes immediately, wait for clientReady

    ws.on('message', (message) => {
        handleMessage(ws, message.toString()); // Ensure message is string
    });

    ws.on('close', () => {
        log('Client disconnected');
        clients.delete(ws);
        // Clean up subscriptions associated with this client? No, subs are note-based.
    });

    ws.on('error', (error) => {
        log('WebSocket error:', error);
        clients.delete(ws); // Remove potentially broken client
    });
});

// --- Initialization ---
loadConfig();
loadNotes();
initializeNostr();

// --- Start Server ---
server.listen(PORT, HOST, () => {
    log(`Server running at http://${HOST}:${PORT}`);
    log('WebSocket server is listening on the same port.');
});

// --- Graceful Shutdown ---
process.on('SIGINT', () => {
    log('Shutting down server...');
    saveNotes(); // Save notes before exiting
    wss.close(() => {
        log('WebSocket server closed.');
    });
    server.close(() => {
        log('HTTP server closed.');
        pool?.close(NOSTR_RELAYS); // Close Nostr connections
        log('Nostr pool closed.');
        process.exit(0);
    });
    // Force exit if shutdown takes too long
    setTimeout(() => {
        log('Forcing exit...');
        process.exit(1);
    }, 5000);
});