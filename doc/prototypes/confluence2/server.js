// server.js - Confluence App Backend

import { WebSocketServer } from 'ws';
import {
    generateSecretKey, getPublicKey, SimplePool, nip19, nip04, nip10, finalizeEvent
} from 'nostr-tools';
import fs from 'fs/promises';
import path from 'path';
import fetch from 'node-fetch'; // Use node-fetch v3+ which supports ESM

import { generateUUID, cosineSimilarity } from "./util.js";

// --- Configuration ---
const PORT = process.env.PORT || 8080;
const OLLAMA_URL = process.env.OLLAMA_URL || 'http://localhost:11434';
const OLLAMA_EMBED_MODEL = process.env.OLLAMA_EMBED_MODEL || 'hf.co/mradermacher/phi-4-GGUF:Q4_K_S';
const OLLAMA_CHAT_MODEL = process.env.OLLAMA_CHAT_MODEL || 'hf.co/mradermacher/phi-4-GGUF:Q4_K_S';
const NOSTR_RELAYS = process.env.NOSTR_RELAYS?.split(',') || [
    'wss://relay.damus.io',
    'wss://relay.primal.net',
    'wss://nos.lol',
];
const KEYS_FILE = 'nostr_keys.json';
const SIMILARITY_THRESHOLD = 0.75; // Cosine similarity threshold for matching

// --- State ---
let notes = new Map(); // In-memory store: local_id -> Note object
let wsClients = new Set();
let nostrSK, nostrPK;
let nostrPool;
let nostrSubscriptions = new Map(); // local_id -> nostr_subscription_object

// --- Helper Functions ---
const broadcast = (message) => {
    const data = JSON.stringify(message);
    wsClients.forEach(client => {
        if (client.readyState === 1) client.send(data); // 1 = WebSocket.OPEN
    });
};

const send = (ws, message) => {
    if (ws.readyState === 1) ws.send(JSON.stringify(message));
};

const loadNotes = async () => {
    try {
        const data = await fs.readFile('notes_storage.json', 'utf-8');
        const parsedNotes = JSON.parse(data);
        notes = new Map(parsedNotes.map(note => [note.local_id, note]));
        console.log(`Loaded ${notes.size} notes from storage.`);
    } catch (err) {
        if (err.code === 'ENOENT') {
            console.log('No existing notes storage found. Starting fresh.');
            notes = new Map();
            // Create initial welcome note
            const welcomeId = generateUUID();
            notes.set(welcomeId, {
                local_id: welcomeId,
                content: "<h1>Welcome to Confluence!</h1><p>Create notes, explore semantic connections, and optionally share on Nostr.</p>",
                tags: { k: 'welcome', type: 'info' },
                embedding: null,
                status: 'active',
                config: { isPublished: false, findMatches: false },
                created_at: Date.now(),
                updated_at: Date.now(),
                network_id: null, owner_id: null, network_ts: null
            });
            await saveNotes();
        } else {
            console.error('Error loading notes:', err);
        }
    }
};

const saveNotes = async () => {
    try {
        // Convert Map entries to an array for JSON serialization
        const notesArray = Array.from(notes.values());
        await fs.writeFile('notes_storage.json', JSON.stringify(notesArray, null, 2));
    } catch (err) {
        console.error('Error saving notes:', err);
    }
};

const getOllamaEmbedding = async (text) => {
    try {
        const response = await fetch(`${OLLAMA_URL}/api/embeddings`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ model: OLLAMA_EMBED_MODEL, prompt: text }),
        });
        if (!response.ok) throw new Error(`Ollama embedding error: ${response.statusText}`);
        const data = await response.json();
        return data.embedding;
    } catch (error) {
        console.error("Error getting Ollama embedding:", error.message);
        return null;
    }
};

const getOllamaAnalysis = async (text) => {
    try {
        const prompt = `Analyze the following note content. Extract potential keywords/tags (use 'k:' prefix), suggest an action hint if applicable (use 'a:' prefix, e.g., a:offer, a:request, a:question), and identify the primary subject or type (use 'type:' prefix). Also, suggest 1-2 brief questions to clarify the content or purpose for better matching. Respond ONLY in JSON format like this: {"tags": {"k:tag1":true, "k:tag2":true, "a:action":true, "type:subject":true}, "prompts": ["Clarification question 1?", "Clarification question 2?"]}. Content:\n\n${text}`;

        const response = await fetch(`${OLLAMA_URL}/api/generate`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                model: OLLAMA_CHAT_MODEL,
                prompt: prompt,
                stream: false, // Get full response at once
                format: "json" // Request JSON output
            }),
        });
        if (!response.ok) throw new Error(`Ollama analysis error: ${response.statusText}`);
        const data = await response.json();
        // Attempt to parse the JSON string within the 'response' field
        try {
            const analysisResult = JSON.parse(data.response);
            // Basic validation
            if (typeof analysisResult === 'object' && analysisResult !== null && Array.isArray(analysisResult.prompts) && typeof analysisResult.tags === 'object') {
                return analysisResult;
            } else {
                console.error("Ollama analysis response is not in the expected JSON structure:", data.response);
                return { tags: {}, prompts: ["Could you be more specific?"] }; // Fallback
            }
        } catch(parseError) {
            console.error("Error parsing Ollama analysis JSON:", parseError, "Raw response:", data.response);
            return { tags: {}, prompts: ["Analysis failed. Try rephrasing?"] }; // Fallback
        }

    } catch (error) {
        console.error("Error getting Ollama analysis:", error.message);
        return { tags: {}, prompts: ["Analysis service unavailable."] };
    }
};


const updateNoteSemanticData = async (note) => {
    const textContent = note.content.replace(/<[^>]*>/g, ' '); // Basic HTML strip for analysis
    if (!textContent.trim()) {
        note.embedding = null;
        // Optionally clear suggested tags or keep existing ones
        return { analysis: { tags: {}, prompts: [] }, embedding: null };
    }

    const [embedding, analysis] = await Promise.all([
        getOllamaEmbedding(textContent),
        getOllamaAnalysis(textContent)
    ]);

    note.embedding = embedding;
    // Merge suggested tags with existing ones (user tags take precedence if keys conflict)
    note.tags = { ...analysis.tags, ...note.tags };
    note.updated_at = Date.now();

    return { analysis, embedding };
};

// --- Nostr Integration ---
const initNostr = async () => {
    try {
        let keys;
        try {
            const data = await fs.readFile(KEYS_FILE, 'utf-8');
            keys = JSON.parse(data);
            nostrSK = keys.sk;
            nostrPK = keys.pk;
            console.log('Loaded Nostr keys from', KEYS_FILE);
        } catch (err) {
            console.log('Generating new Nostr keys...');
            nostrSK = generateSecretKey();
            nostrPK = getPublicKey(nostrSK);
            await fs.writeFile(KEYS_FILE, JSON.stringify({ sk: nostrSK, pk: nostrPK }));
            console.log('Saved new Nostr keys to', KEYS_FILE);
        }

        nostrPool = new SimplePool();
        console.log('Connecting to Nostr relays:', NOSTR_RELAYS);
        // Initial connection attempt (doesn't block)
        Promise.all(NOSTR_RELAYS.map(url => nostrPool.ensureRelay(url)))
            .then(() => console.log('Nostr relay connections established/attempted.'))
            .catch(err => console.error('Error connecting to some Nostr relays:', err));

        return true;
    } catch (error) {
        console.error('Failed to initialize Nostr:', error);
        nostrPool = null; // Ensure pool is null if init fails
        return false;
    }
};

const publishNoteToNostr = async (note) => {
    if (!nostrPool || !nostrSK || !note) return null;

    const nostrTags = Object.entries(note.tags)
        .map(([key, value]) => {
            if (typeof value === 'boolean' && value) return [key.split(':')[0], key.split(':')[1]]; // e.g., ["k", "keyword"]
            if (typeof value === 'string') return [key.split(':')[0], value]; // Allow string values too
            return null;
        })
        .filter(tag => tag !== null);

    const event = {
        kind: 1, // Standard text note
        pubkey: nostrPK,
        created_at: Math.floor(Date.now() / 1000),
        tags: nostrTags,
        content: note.content, // Publish the rich text content
    };

    const signedEvent = finalizeEvent(event, nostrSK);

    try {
        const pubs = await nostrPool.publish(NOSTR_RELAYS, signedEvent);
        console.log(`Published note ${note.local_id} to ${pubs.length} relays. Event ID: ${signedEvent.id}`);
        broadcast({ type: 'networkActivity', data: `Published: ${signedEvent.content.substring(0, 50)}... (ID: ${signedEvent.id.substring(0, 8)})` });
        return { network_id: signedEvent.id, network_ts: signedEvent.created_at * 1000 };
    } catch (error) {
        console.error(`Failed to publish note ${note.local_id}:`, error);
        broadcast({ type: 'networkActivity', data: `Error publishing: ${error.message}` });
        return null;
    }
};

const subscribeToNostrMatches = (localNote) => {
    if (!nostrPool || !localNote || !localNote.config.findMatches || !localNote.config.isPublished) {
        return;
    }
    if (nostrSubscriptions.has(localNote.local_id)) {
        console.log(`Already subscribed for note ${localNote.local_id}`);
        return; // Avoid duplicate subscriptions
    }

    // Create filters based on note tags (only 'k:' type for now)
    const keywordTags = Object.keys(localNote.tags)
        .filter(key => key.startsWith('k:'))
        .map(key => key.substring(2)); // Get the keyword itself

    if (keywordTags.length === 0) {
        console.log(`Note ${localNote.local_id} has no keywords for matching.`);
        // Optionally provide feedback to the client
        return;
    }

    // Filter for kind 1 notes containing any of the keywords as tags
    const filter = {
        kinds: [1],
        '#k': keywordTags, // Match events with any of these #k tags
        since: Math.floor(Date.now() / 1000) - 86400, // Look back 1 day (adjust as needed)
        limit: 50 // Limit initial results
    };

    console.log(`Subscribing to Nostr for matches related to note ${localNote.local_id} with filter:`, filter);
    broadcast({ type: 'networkActivity', data: `Subscribing for matches: ${keywordTags.join(', ')}` });

    const sub = nostrPool.sub(NOSTR_RELAYS, [filter]);
    nostrSubscriptions.set(localNote.local_id, sub);

    sub.on('event', async (event) => {
        if (event.pubkey === nostrPK) return; // Ignore own notes

        broadcast({ type: 'networkActivity', data: `Potential match received: ${event.content.substring(0, 50)}... (From: ${nip19.npubEncode(event.pubkey).substring(0, 12)}...)` });

        // --- Semantic Matching ---
        if (!localNote.embedding) {
            console.log(`Local note ${localNote.local_id} missing embedding, skipping similarity check.`);
            return;
        }

        // Need embedding for the candidate note
        const candidateTextContent = event.content.replace(/<[^>]*>/g, ' '); // Basic strip
        const candidateEmbedding = await getOllamaEmbedding(candidateTextContent);

        if (!candidateEmbedding) {
            console.log(`Could not get embedding for candidate note ${event.id}, skipping.`);
            return;
        }

        const similarity = cosineSimilarity(localNote.embedding, candidateEmbedding);
        console.log(`Similarity between ${localNote.local_id} and ${event.id}: ${similarity.toFixed(4)}`);

        if (similarity > SIMILARITY_THRESHOLD) {
            console.log(`Found match! Local: ${localNote.local_id}, Candidate: ${event.id}, Score: ${similarity}`);

            // Create a Match Result Note (dogfooding)
            const matchId = generateUUID();
            const matchNote = {
                local_id: matchId,
                content: `Match found for your note: ${localNote.content.substring(0, 50)}...\n---\nCandidate Note (ID: ${event.id.substring(0,8)}...):\n${event.content.substring(0, 150)}...\n---\nSimilarity: ${similarity.toFixed(3)}`,
                tags: {
                    type: 'match_result',
                    local_note_id: localNote.local_id,
                    candidate_network_id: event.id,
                    candidate_owner_id: event.pubkey,
                    similarity_score: similarity.toFixed(4),
                },
                embedding: null, // Match results don't need embeddings themselves typically
                status: 'active',
                config: { isPublished: false, findMatches: false }, // Matches are local-only by default
                created_at: Date.now(),
                updated_at: Date.now(),
                network_id: null, owner_id: null, network_ts: null,
            };
            notes.set(matchId, matchNote);
            await saveNotes(); // Persist the match result

            // Notify clients about the new match and update the list
            broadcast({ type: 'newMatch', data: matchNote });
            broadcast({ type: 'notesListUpdate', data: Array.from(notes.values()) });
        }
    });

    sub.on('eose', () => {
        console.log(`Subscription EOSE for note ${localNote.local_id}`);
        broadcast({ type: 'networkActivity', data: `Initial match results loaded for: ${keywordTags.join(', ')}` });
    });
};

const unsubscribeFromNostrMatches = (localNoteId) => {
    const sub = nostrSubscriptions.get(localNoteId);
    if (sub) {
        console.log(`Unsubscribing from Nostr matches for note ${localNoteId}`);
        sub.unsub();
        nostrSubscriptions.delete(localNoteId);
        broadcast({ type: 'networkActivity', data: `Stopped watching for matches for note ${localNoteId.substring(0, 8)}...` });
    }
};

// --- WebSocket Server Logic ---
const wss = new WebSocketServer({ port: PORT });

wss.on('connection', (ws) => {
    console.log('Client connected');
    wsClients.add(ws);

    // Send initial data
    send(ws, { type: 'notesListUpdate', data: Array.from(notes.values()) });
    send(ws, { type: 'identityUpdate', data: nostrPK ? nip19.npubEncode(nostrPK) : null });

    ws.on('message', async (message) => {
        let request;
        try {
            request = JSON.parse(message);
            // console.log('Received:', request); // Debug logging

            switch (request.type) {
                case 'getNotes':
                    send(ws, { type: 'notesListUpdate', data: Array.from(notes.values()) });
                    break;

                case 'saveNote': {
                    const noteData = request.data;
                    let note = notes.get(noteData.local_id);
                    let isNew = false;
                    const requiresAnalysis = !note || note.content !== noteData.content; // Analyze if new or content changed

                    if (note) { // Update existing
                        note = { ...note, ...noteData, updated_at: Date.now() };
                    } else { // Create new
                        isNew = true;
                        note = {
                            local_id: noteData.local_id || generateUUID(),
                            content: noteData.content || '',
                            tags: noteData.tags || {},
                            embedding: null,
                            status: 'active',
                            config: { isPublished: false, findMatches: false, ...(noteData.config || {}) },
                            created_at: Date.now(),
                            updated_at: Date.now(),
                            network_id: null, owner_id: null, network_ts: null
                        };
                    }
                    notes.set(note.local_id, note);

                    let analysisResult = { analysis: { tags: {}, prompts: [] }, embedding: null };
                    if (requiresAnalysis && note.content.trim()) {
                        try {
                            analysisResult = await updateNoteSemanticData(note);
                            send(ws, { type: 'llmFeedback', data: { local_id: note.local_id, ...analysisResult.analysis } });
                        } catch (err) {
                            console.error("Error during semantic update:", err);
                            send(ws, { type: 'llmFeedback', data: { local_id: note.local_id, tags: {}, prompts: ["Error during analysis."] } });
                        }
                    } else if (!note.content.trim()) {
                        note.embedding = null; // Clear embedding if content is empty
                    }


                    await saveNotes(); // Persist changes

                    // Send update confirmation *with potentially updated semantic data*
                    send(ws, { type: 'noteUpdateConfirm', data: note });
                    // Broadcast list update only if it's a new note or status changed significantly (publish/match)
                    // Content changes are handled by noteUpdateConfirm for the active editor
                    if (isNew || noteData.config?.isPublished !== notes.get(note.local_id)?.config?.isPublished || noteData.config?.findMatches !== notes.get(note.local_id)?.config?.findMatches) {
                        broadcast({ type: 'notesListUpdate', data: Array.from(notes.values()) });
                    }

                    // Handle publishing state change
                    if (note.config.isPublished && !note.network_id) {
                        const publishResult = await publishNoteToNostr(note);
                        if (publishResult) {
                            note.network_id = publishResult.network_id;
                            note.network_ts = publishResult.network_ts;
                            note.owner_id = nostrPK; // Set owner on successful publish
                            await saveNotes();
                            // Send updated note data back to the specific client and broadcast list update
                            send(ws, { type: 'noteUpdateConfirm', data: note });
                            broadcast({ type: 'notesListUpdate', data: Array.from(notes.values()) });
                        } else {
                            // Revert publish state if failed
                            note.config.isPublished = false;
                            await saveNotes();
                            send(ws, { type: 'noteUpdateConfirm', data: note }); // Send reverted state
                            broadcast({ type: 'notesListUpdate', data: Array.from(notes.values()) });
                        }
                    }

                    // Handle matching state change
                    if (note.config.findMatches && note.config.isPublished) {
                        subscribeToNostrMatches(note);
                    } else {
                        unsubscribeFromNostrMatches(note.local_id);
                    }

                    break;
                }

                case 'analyzeNote': { // Explicit request for analysis
                    const { local_id } = request.data;
                    const note = notes.get(local_id);
                    if (note) {
                        try {
                            const { analysis } = await updateNoteSemanticData(note);
                            await saveNotes(); // Save updated embedding/tags
                            send(ws, { type: 'llmFeedback', data: { local_id: note.local_id, ...analysis } });
                            // Also send the updated note data itself
                            send(ws, { type: 'noteUpdateConfirm', data: note });
                            // No broadcast needed here, analysis is per-client request usually
                        } catch (err) {
                            console.error("Error during explicit analysis:", err);
                            send(ws, { type: 'llmFeedback', data: { local_id: note.local_id, tags:{}, prompts: ["Error during analysis."] } });
                        }
                    }
                    break;
                }


                case 'deleteNote': {
                    const { local_id } = request.data;
                    if (notes.has(local_id)) {
                        const noteToDelete = notes.get(local_id);
                        // Unsubscribe if it was matching
                        if(noteToDelete.config?.findMatches) {
                            unsubscribeFromNostrMatches(local_id);
                        }
                        // TODO: Optionally publish a deletion event to Nostr (Kind 5) if published? Needs careful consideration.

                        notes.delete(local_id);
                        await saveNotes();
                        broadcast({ type: 'notesListUpdate', data: Array.from(notes.values()) });
                        // Send confirmation or indicate which note was deleted if needed
                        send(ws, { type: 'noteDeleteConfirm', data: { local_id } });
                    }
                    break;
                }

                case 'sendMessage': { // Basic NIP-04 Direct Message
                    const { recipientNpub, encryptedMessage } = request.data;
                    if (!nostrPool || !nostrSK || !recipientNpub || !encryptedMessage) {
                        send(ws, { type: 'error', data: 'Missing data or Nostr not initialized for sending DM.' });
                        break;
                    }
                    try {
                        const recipientPubkey = nip19.decode(recipientNpub).data;
                        const event = {
                            kind: 4, // Encrypted Direct Message
                            pubkey: nostrPK,
                            created_at: Math.floor(Date.now() / 1000),
                            tags: [['p', recipientPubkey]],
                            content: encryptedMessage,
                        };
                        const signedEvent = finalizeEvent(event, nostrSK);
                        const pubs = await nostrPool.publish(NOSTR_RELAYS, signedEvent);
                        broadcast({ type: 'networkActivity', data: `Sent DM to ${recipientNpub.substring(0,12)}... (${pubs.length} relays)` });
                        send(ws, { type: 'messageSent', data: { recipientNpub } }); // Confirmation
                    } catch (error) {
                        console.error(`Failed to send DM to ${recipientNpub}:`, error);
                        broadcast({ type: 'networkActivity', data: `Error sending DM: ${error.message}` });
                        send(ws, { type: 'error', data: `Failed to send DM: ${error.message}` });
                    }
                    break;
                }

                default:
                    console.log('Unknown message type:', request.type);
            }
        } catch (error) {
            console.error('Failed to process message:', message, 'Error:', error);
            // Optionally send an error back to the client
            send(ws, { type: 'error', data: 'Failed to process message on server.' });
        }
    });

    ws.on('close', () => {
        console.log('Client disconnected');
        wsClients.delete(ws);
    });

    ws.on('error', (error) => {
        console.error('WebSocket error:', error);
        wsClients.delete(ws); // Clean up on error
    });
});

// --- Initialization ---
const startServer = async () => {
    await loadNotes();
    const nostrReady = await initNostr();
    if (!nostrReady) {
        console.warn("Nostr initialization failed. Network features will be unavailable.");
    } else {
        // Re-establish subscriptions for notes marked for matching on startup
        notes.forEach(note => {
            if (note.config?.isPublished && note.config?.findMatches) {
                subscribeToNostrMatches(note);
            }
        });
    }

    console.log(`WebSocket server started on ws://localhost:${PORT}`);
    console.log(`Ollama API endpoint: ${OLLAMA_URL}`);
    console.log(`Nostr Public Key: ${nostrPK ? nip19.npubEncode(nostrPK) : 'Not Initialized'}`);
};

startServer();

// Graceful shutdown
process.on('SIGINT', async () => {
    console.log('Shutting down server...');
    wss.close();
    if (nostrPool) nostrPool.close(NOSTR_RELAYS);
    await saveNotes(); // Ensure notes are saved on exit
    process.exit(0);
});