// server.js - Confluence Backend

import { WebSocketServer } from 'ws';
import http from 'http';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { SimplePool, generateSecretKey, getPublicKey, finalizeEvent, verifyEvent } from 'nostr-tools';

// Use dynamic import for fetch if needed (Node < 18) or ensure Node >= 18
// import fetch from 'node-fetch'; // Uncomment if needed

// Import shared utilities
import {
    generateUUID,
    evaluatePredicate,
    callOllamaExtractProperties,
    callOllamaEmbed,
    cosineSimilarity
} from './util.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const PORT = process.env.PORT || 3000;
const OLLAMA_MODEL_EMBED = process.env.OLLAMA_MODEL_EMBED || "hf.co/mradermacher/phi-4-GGUF:Q4_K_S"; // Or mxbai-embed-large
const OLLAMA_MODEL_EXTRACT = process.env.OLLAMA_MODEL_EXTRACT || "hf.co/mradermacher/phi-4-GGUF:Q4_K_S"; // Or another capable model

// --- Data Stores (In-Memory - Replace with DB in production) ---
let localNotes = []; // Array of Note objects
let incomingNostrNotes = new Map(); // Map<network_id, Note> - Temporary store for analyzed Nostr events

// --- Nostr Setup ---
const nostrPool = new SimplePool();
let nostrRelays = ['wss://relay.damus.io', 'wss://relay.primal.net']; // Default relays
let nostrSubscriptions = new Map(); // Map<subId, NostrSubscriptionObject>
let serverNostrKeys = { // Store keys server-side for publishing etc.
    privkey: process.env.NOSTR_PRIVKEY || null, // Load from env or generate/manage securely
    pubkey: null
};
if (serverNostrKeys.privkey) {
    serverNostrKeys.pubkey = getPublicKey(serverNostrKeys.privkey);
    console.log(`Loaded Nostr key. Public Key: ${serverNostrKeys.pubkey}`);
} else {
    console.warn("NOSTR_PRIVKEY environment variable not set. Publishing and certain interactions will be disabled.");
}

// --- HTTP Server ---
const server = http.createServer((req, res) => {
    if (req.url === '/' || req.url === '/index.html') {
        fs.readFile(path.join(__dirname, 'index.html'), (err, data) => {
            if (err) {
                res.writeHead(500);
                res.end('Error loading index.html');
                return;
            }
            res.writeHead(200, { 'Content-Type': 'text/html' });
            res.end(data);
        });
    } else if (req.url === '/util.js') {
        fs.readFile(path.join(__dirname, 'util.js'), (err, data) => {
            if (err) {
                res.writeHead(500);
                res.end('Error loading util.js');
                return;
            }
            res.writeHead(200, { 'Content-Type': 'application/javascript' });
            res.end(data);
        });
    } else {
        res.writeHead(404);
        res.end('Not Found');
    }
});

// --- WebSocket Server ---
const wss = new WebSocketServer({ server });

wss.on('connection', (ws) => {
    console.log('Client connected');

    // Send initial data
    send(ws, { type: 'notesList', payload: localNotes });
    send(ws, { type: 'nostrStatus', payload: { connected: false, relays: nostrRelays, pubkey: serverNostrKeys.pubkey }}); // Initial status

    ws.on('message', async (message) => {
        let data;
        try {
            data = JSON.parse(message);
            console.log('Received:', data.type); // Avoid logging full data potentially containing secrets

            switch (data.type) {
                case 'getNotes':
                    send(ws, { type: 'notesList', payload: localNotes });
                    break;

                case 'saveNote':
                    await handleSaveNote(ws, data.payload);
                    break;

                case 'deleteNote':
                    handleDeleteNote(ws, data.payload.local_id);
                    break;

                case 'analyzeNote':
                    await handleAnalyzeNote(ws, data.payload.local_id);
                    break;

                case 'getEmbeddings': // Simple request to get all current embeddings
                    const embeddingsMap = localNotes.reduce((acc, note) => {
                        if (note.embedding) acc[note.local_id] = note.embedding.slice(0, 10); // Send truncated embedding for viz
                        return acc;
                    }, {});
                    send(ws, { type: 'embeddingsData', payload: embeddingsMap });
                    break;

                case 'publishNote':
                    await handlePublishNote(ws, data.payload.local_id);
                    break;

                case 'setNostrKeys': // Allow client to set keys (less secure, demo only)
                    if (data.payload.privkey) {
                        try {
                            serverNostrKeys.privkey = data.payload.privkey;
                            serverNostrKeys.pubkey = getPublicKey(serverNostrKeys.privkey);
                            console.log(`Nostr keys updated by client. Pubkey: ${serverNostrKeys.pubkey}`);
                            broadcastNostrStatus();
                        } catch (e) {
                            console.error("Invalid private key provided:", e);
                            send(ws, { type: 'error', payload: 'Invalid Nostr private key.' });
                        }
                    }
                    break;

                case 'setNostrRelays':
                    if (Array.isArray(data.payload.relays) && data.payload.relays.length > 0) {
                        nostrRelays = data.payload.relays;
                        console.log("Nostr relays updated:", nostrRelays);
                        // Optionally reconnect or resubscribe
                        // For simplicity, new subscriptions will use the updated relays.
                        broadcastNostrStatus();
                    }
                    break;

                case 'subscribeNostr': // Example: Subscribe to kind 1 from specific pubkeys
                    await handleSubscribeNostr(ws, data.payload); // e.g., { authors: ['pubkey1', ...], kinds: [1] }
                    break;

                case 'unsubscribeNostr':
                    handleUnsubscribeNostr(ws, data.payload.subId);
                    break;

                case 'getMatches':
                    await handleGetMatches(ws, data.payload); // e.g., { noteId: 'local_uuid', threshold: 0.75 }
                    break;

                default:
                    console.log('Unknown message type:', data.type);
            }
        } catch (error) {
            console.error('Failed to process message or invalid JSON:', message.toString(), error);
            send(ws, { type: 'error', payload: 'Invalid message format or server error.' });
        }
    });

    ws.on('close', () => {
        console.log('Client disconnected');
        // Clean up user-specific subscriptions if necessary
        nostrSubscriptions.forEach((sub, subId) => {
            // Simple cleanup: If we knew which ws owned which sub, we could be more targeted
            // For now, subs persist until explicitly unsubscribed or server restart
        });
    });

    ws.on('error', (error) => {
        console.error('WebSocket error:', error);
    });
});

// --- WebSocket Helper ---
function send(ws, data) {
    if (ws.readyState === ws.OPEN) {
        ws.send(JSON.stringify(data));
    }
}

function broadcast(data) {
    wss.clients.forEach((client) => {
        send(client, data);
    });
}

function broadcastNostrStatus() {
    // Simplified connection status - check pool connections if needed
    const connected = nostrPool.connectedRelays.length > 0; // Basic check
    broadcast({ type: 'nostrStatus', payload: { connected, relays: nostrRelays, pubkey: serverNostrKeys.pubkey } });
}


// --- Note Handlers ---

async function handleSaveNote(ws, noteData) {
    const isNew = !noteData.local_id;
    const now = Date.now();
    let note;

    if (isNew) {
        note = {
            local_id: generateUUID(),
            network_id: null,
            owner_id: null, // Set if published
            content: noteData.content || "",
            semantic_properties: noteData.semantic_properties || [], // Expect properties from client
            embedding: null, // Regenerate on save if content changed significantly? Or on analyze?
            status: noteData.status || "active",
            config: noteData.config || { isPublished: false, enableMatching: true, isTemplate: false },
            created_at: now,
            updated_at: now,
            network_ts: null,
        };
        // Sanitize incoming semantic properties
        note.semantic_properties = note.semantic_properties.map(p => ({
            property_id: p.property_id || generateUUID(),
            name: p.name || "unnamed_property",
            type: p.type || "string",
            unit: p.unit || undefined,
            enum_values: p.enum_values || undefined,
            value_real: p.value_real !== undefined ? p.value_real : null, // Allow explicit null/false/0
            value_ideal: p.value_ideal || null, // Ensure structure if present
            source: p.source || "user_defined",
            confidence: p.confidence || undefined,
        }));
        localNotes.push(note);
        console.log(`Created Note ${note.local_id}`);
    } else {
        const noteIndex = localNotes.findIndex(n => n.local_id === noteData.local_id);
        if (noteIndex === -1) {
            send(ws, { type: 'error', payload: `Note not found: ${noteData.local_id}` });
            return;
        }
        note = localNotes[noteIndex];
        const contentChanged = note.content !== noteData.content;
        const propertiesChanged = JSON.stringify(note.semantic_properties) !== JSON.stringify(noteData.semantic_properties);

        note.content = noteData.content || note.content;
        note.semantic_properties = (noteData.semantic_properties || note.semantic_properties).map(p => ({ // Update carefully
            property_id: p.property_id || generateUUID(), // Assign ID if missing on update too
            name: p.name || "unnamed_property",
            type: p.type || "string",
            unit: p.unit || undefined,
            enum_values: p.enum_values || undefined,
            value_real: p.value_real !== undefined ? p.value_real : null,
            value_ideal: p.value_ideal || null,
            source: p.source || "user_defined",
            confidence: p.confidence || undefined,
        }));
        note.status = noteData.status || note.status;
        note.config = { ...note.config, ...(noteData.config || {}) };
        note.updated_at = now;

        // Invalidate embedding if content or properties changed significantly
        // For simplicity, we'll regenerate embedding only via "Analyze" or explicitly
        // if (contentChanged || propertiesChanged) {
        //    note.embedding = null; // Mark for regeneration
        // }
        console.log(`Updated Note ${note.local_id}`);
    }

    // Optionally regenerate embedding immediately on save if needed
    // if (!note.embedding && note.content) {
    //     await generateNoteEmbedding(note); // This might slow down saving
    // }

    send(ws, { type: 'noteUpdated', payload: note }); // Send back the full updated note
    broadcast({ type: 'notesList', payload: localNotes }); // Update lists for all clients
}

function handleDeleteNote(ws, local_id) {
    const initialLength = localNotes.length;
    localNotes = localNotes.filter(n => n.local_id !== local_id);
    if (localNotes.length < initialLength) {
        console.log(`Deleted Note ${local_id}`);
        broadcast({ type: 'noteDeleted', payload: { local_id } }); // Inform clients
        broadcast({ type: 'notesList', payload: localNotes }); // Update lists
    } else {
        send(ws, { type: 'error', payload: `Note not found for deletion: ${local_id}` });
    }
}

async function handleAnalyzeNote(ws, local_id) {
    const noteIndex = localNotes.findIndex(n => n.local_id === local_id);
    if (noteIndex === -1) {
        send(ws, { type: 'error', payload: `Note not found for analysis: ${local_id}` });
        return;
    }
    const note = localNotes[noteIndex];
    send(ws, { type: 'analysisStatus', payload: { noteId: local_id, status: 'started' } });

    try {
        // 1. Extract Semantic Properties
        console.log(`Analyzing content for Note ${local_id}...`);
        const extractedProperties = await callOllamaExtractProperties(note.content, OLLAMA_MODEL_EXTRACT);
        console.log(`Extracted ${extractedProperties.length} properties for Note ${local_id}`);

        // Merge extracted properties with existing user-defined ones (simple strategy: add new ones)
        const existingPropNames = new Set(note.semantic_properties.map(p => p.name.toLowerCase()));
        let updated = false;
        extractedProperties.forEach(extracted => {
            if (extracted.name && !existingPropNames.has(extracted.name.toLowerCase())) {
                note.semantic_properties.push({
                    property_id: generateUUID(),
                    name: extracted.name,
                    type: extracted.type || 'string',
                    unit: extracted.unit || undefined,
                    enum_values: extracted.enum_values || undefined,
                    value_real: extracted.value_real !== undefined ? extracted.value_real : null,
                    value_ideal: extracted.value_ideal || null, // Ensure structure
                    source: 'llm_extracted',
                    confidence: extracted.confidence || undefined
                });
                updated = true;
            } else {
                // Optionally: Update confidence or merge values if property already exists? More complex logic.
                console.log(`Skipping extracted property '${extracted.name}' as it (or similar) likely exists.`);
            }
        });

        // 2. Generate Embedding (based on content + maybe key properties)
        await generateNoteEmbedding(note); // Pass the note object itself
        updated = true; // Embedding was generated/updated

        note.updated_at = Date.now();
        localNotes[noteIndex] = note; // Update the note in the main array

        send(ws, { type: 'analysisStatus', payload: { noteId: local_id, status: 'completed' } });
        if (updated) {
            send(ws, { type: 'noteUpdated', payload: note }); // Send updated note back
            broadcast({ type: 'notesList', payload: localNotes }); // Update lists if properties were added
        }

    } catch (error) {
        console.error(`Error analyzing Note ${local_id}:`, error);
        send(ws, { type: 'analysisStatus', payload: { noteId: local_id, status: 'failed', error: error.message } });
        send(ws, { type: 'error', payload: `Analysis failed for Note ${local_id}. Check server logs.` });
    }
}

async function generateNoteEmbedding(note) {
    if (!note || !note.content) return; // Need content

    try {
        // Option 1: Embed only content
        // const textToEmbed = note.content;

        // Option 2: Embed content + stringified meaningful properties (experimental)
        let textToEmbed = note.content;
        const propsToEmbed = note.semantic_properties
            .filter(p => p.value_real !== null || p.value_ideal !== null) // Only embed properties with values
            .map(p => {
                let propStr = `${p.name} (${p.type})`;
                if (p.value_real !== null) propStr += `: ${JSON.stringify(p.value_real)}`;
                if (p.value_ideal !== null) propStr += ` (Ideal: ${p.value_ideal.predicate} ${JSON.stringify(p.value_ideal.value)})`;
                return propStr;
            })
            .join('; ');

        if (propsToEmbed) {
            textToEmbed += "\n\n--- Properties ---\n" + propsToEmbed;
        }

        console.log(`Generating embedding for Note ${note.local_id} using model ${OLLAMA_MODEL_EMBED}...`);
        note.embedding = await callOllamaEmbed(textToEmbed, OLLAMA_MODEL_EMBED);
        console.log(`Generated embedding for Note ${note.local_id} (length: ${note.embedding?.length})`);
    } catch (error) {
        console.error(`Failed to generate embedding for Note ${note.local_id}:`, error);
        note.embedding = null; // Ensure embedding is null on failure
    }
}


// --- Nostr Handlers ---

async function handlePublishNote(ws, local_id) {
    if (!serverNostrKeys.privkey || !serverNostrKeys.pubkey) {
        send(ws, { type: 'error', payload: 'Nostr private key not configured on server.' });
        return;
    }

    const noteIndex = localNotes.findIndex(n => n.local_id === local_id);
    if (noteIndex === -1) {
        send(ws, { type: 'error', payload: `Note not found for publishing: ${local_id}` });
        return;
    }
    const note = localNotes[noteIndex];

    // Prepare Nostr event (Kind 1: Short Text Note)
    const tags = [
        // Add standard tags if desired (e.g., #hashtags extracted from content)
        // ["t", "hashtag"]
    ];

    // Embed semantic properties using a custom tag: ["semantic", <JSON_STRING>]
    const semanticPropsString = JSON.stringify(note.semantic_properties || []);
    tags.push(["semantic", semanticPropsString]);

    const eventTemplate = {
        kind: 1,
        created_at: Math.floor(Date.now() / 1000),
        tags: tags,
        content: note.content, // Publish the main content
        pubkey: serverNostrKeys.pubkey,
    };

    try {
        const signedEvent = finalizeEvent(eventTemplate, serverNostrKeys.privkey);
        const ok = verifyEvent(signedEvent);
        if (!ok) {
            throw new Error("Failed to sign Nostr event.");
        }

        console.log(`Publishing Note ${local_id} to Nostr relays:`, nostrRelays);
        const pubs = await nostrPool.publish(nostrRelays, signedEvent);

        // Wait for confirmations (optional, can be slow)
        // await Promise.all(pubs); // pubs is an array of promises resolving when the relay confirms receipt (or times out)

        console.log(`Published event ${signedEvent.id} to ${pubs.length} relays.`);

        // Update local note state
        note.network_id = signedEvent.id;
        note.owner_id = signedEvent.pubkey;
        note.network_ts = signedEvent.created_at;
        note.config.isPublished = true;
        note.updated_at = Date.now();
        localNotes[noteIndex] = note;

        send(ws, { type: 'notePublished', payload: note }); // Send updated note back
        broadcast({ type: 'notesList', payload: localNotes }); // Update lists


    } catch (error) {
        console.error(`Failed to publish Note ${local_id} to Nostr:`, error);
        send(ws, { type: 'error', payload: `Failed to publish to Nostr: ${error.message}` });
    }
}

async function handleSubscribeNostr(ws, filter) {
    // filter example: { kinds: [1], authors: ['pubkey1', ...], limit: 50 }
    if (!filter || (Object.keys(filter).length === 0)) {
        send(ws, { type: 'error', payload: 'Invalid Nostr filter provided for subscription.' });
        return;
    }

    const subId = `sub-${generateUUID()}`; // Generate a unique ID for this subscription
    console.log(`Subscribing to Nostr with filter [${subId}]:`, filter);

    try {
        const sub = nostrPool.sub(nostrRelays, [filter], { id: subId }); // Pass subId option
        nostrSubscriptions.set(subId, sub); // Store the subscription object

        sub.on('event', async (event) => {
            console.log(`Received Nostr event [${subId}]:`, event.id, event.kind, `by ${event.pubkey.substring(0,8)}...`);
            // Optionally filter out events from our own pubkey if desired
            // if (event.pubkey === serverNostrKeys.pubkey) return;

            // Process the incoming event
            await processIncomingNostrEvent(event, ws); // Pass ws to potentially send updates back immediately
        });

        sub.on('eose', () => {
            console.log(`Nostr subscription EOSE received [${subId}]`);
            // You might want to inform the client EOSE has been reached
            send(ws, { type: 'nostrSubscriptionStatus', payload: { subId, status: 'eose' }});
        });

        // Inform client subscription was successful
        send(ws, { type: 'nostrSubscriptionStatus', payload: { subId, status: 'active', filter }});
        broadcastNostrStatus(); // Update general status

    } catch (error) {
        console.error(`Failed to subscribe to Nostr [${subId}]:`, error);
        send(ws, { type: 'error', payload: `Nostr subscription failed: ${error.message}`});
        if (nostrSubscriptions.has(subId)) {
            nostrSubscriptions.delete(subId);
        }
    }
}

function handleUnsubscribeNostr(ws, subId) {
    if (nostrSubscriptions.has(subId)) {
        const sub = nostrSubscriptions.get(subId);
        sub.unsub();
        nostrSubscriptions.delete(subId);
        console.log(`Unsubscribed from Nostr [${subId}]`);
        send(ws, { type: 'nostrSubscriptionStatus', payload: { subId, status: 'inactive' }});
        broadcastNostrStatus(); // Update general status
    } else {
        console.warn(`Attempted to unsubscribe from non-existent Nostr subscription [${subId}]`);
    }
}

async function processIncomingNostrEvent(event, ws) {
    // Avoid processing duplicates
    if (incomingNostrNotes.has(event.id)) {
        // console.log(`Skipping duplicate Nostr event: ${event.id}`);
        return;
    }

    // Basic validation
    if (!event.id || !event.pubkey || event.created_at === undefined || event.content === undefined) {
        console.warn("Skipping malformed Nostr event:", event);
        return;
    }

    // Try to parse semantic properties from the custom tag
    let semantic_properties = [];
    let content = event.content; // Default to full content

    const semanticTag = event.tags?.find(t => t[0] === 'semantic');
    if (semanticTag && semanticTag[1]) {
        try {
            semantic_properties = JSON.parse(semanticTag[1]);
            // Basic validation of parsed properties
            if (!Array.isArray(semantic_properties)) {
                semantic_properties = [];
            } else {
                semantic_properties = semantic_properties.map(p => ({ // Sanitize/default structure
                    property_id: p.property_id || generateUUID(),
                    name: p.name || "unknown",
                    type: p.type || "string",
                    unit: p.unit || undefined,
                    enum_values: p.enum_values || undefined,
                    value_real: p.value_real !== undefined ? p.value_real : null,
                    value_ideal: p.value_ideal || null,
                    source: "nostr_event", // Mark source
                    confidence: p.confidence || undefined,
                })).filter(p => p.name !== "unknown"); // Filter out invalid basics
            }
            console.log(`Parsed ${semantic_properties.length} properties from Nostr tag for event ${event.id}`);
        } catch (e) {
            console.warn(`Failed to parse 'semantic' tag JSON for event ${event.id}:`, e);
            semantic_properties = []; // Reset if parsing fails
        }
    }

    // If no properties parsed from tag OR if we always want LLM analysis on incoming:
    if (semantic_properties.length === 0 && content.trim().length > 0) { // Only analyze if there's content and no props found
        console.log(`Analyzing content of incoming Nostr event ${event.id}...`);
        try {
            const extractedProperties = await callOllamaExtractProperties(content, OLLAMA_MODEL_EXTRACT);
            // Only take 'value_real' from LLM analysis of external notes by default
            semantic_properties = extractedProperties.map(p => ({
                property_id: generateUUID(),
                name: p.name,
                type: p.type || 'string',
                unit: p.unit || undefined,
                // enum_values: p.enum_values || undefined, // Less likely useful here
                value_real: p.value_real !== undefined ? p.value_real : null, // Primarily capture facts
                value_ideal: null, // Ignore ideal values extracted from external notes initially? Or allow? Let's allow for now.
                // value_ideal: p.value_ideal || null,
                source: 'llm_extracted', // Mark source as LLM analysis of Nostr event
                confidence: p.confidence || undefined
            })).filter(p => p.name && p.value_real !== null); // Only keep props with name and a real value
            console.log(`LLM extracted ${semantic_properties.length} properties (real values) for event ${event.id}`);

        } catch (error) {
            console.error(`Failed to analyze incoming Nostr event ${event.id} content:`, error);
            // Continue without LLM-extracted properties if analysis fails
        }
    }


    // Represent the Nostr event internally using the Note structure
    const nostrNote = {
        local_id: `nostr-${event.id}`, // Use a prefix to distinguish
        network_id: event.id,
        owner_id: event.pubkey,
        content: content, // Store original content
        semantic_properties: semantic_properties, // Store parsed/extracted properties
        embedding: null, // Generate embedding for matching
        status: "external", // Custom status
        config: { isPublished: true, enableMatching: true, isTemplate: false }, // Default config for matching
        created_at: event.created_at * 1000, // Convert Nostr ts to ms
        updated_at: event.created_at * 1000,
        network_ts: event.created_at,
    };

    // Generate embedding for the incoming note
    await generateNoteEmbedding(nostrNote); // Pass the note object

    // Store temporarily (could expire old entries)
    incomingNostrNotes.set(nostrNote.network_id, nostrNote);

    // Notify client about the new *processed* Nostr note
    send(ws, { type: 'nostrNoteProcessed', payload: nostrNote });

    // Optionally trigger matching automatically here, or wait for client request
    // await handleGetMatches(ws, { noteId: null, threshold: 0.7 }); // Example: Find matches for all local notes
}

// --- Matching Logic ---

async function handleGetMatches(ws, options) {
    const { noteId, threshold = 0.70 } = options; // Allow specifying a note or match all vs all
    const embeddingSimilarityThreshold = threshold;

    console.log(`Finding matches (Embed threshold: ${embeddingSimilarityThreshold}). Target note: ${noteId || 'all'}`);

    const potentialMatches = [];
    const notesToMatch = noteId ? [localNotes.find(n => n.local_id === noteId)] : localNotes;
    const comparisonSet = [...localNotes, ...Array.from(incomingNostrNotes.values())];

    for (const noteA of notesToMatch) {
        if (!noteA || !noteA.embedding || !noteA.config.enableMatching) continue; // Skip notes without embedding or matching disabled

        for (const noteB of comparisonSet) {
            if (!noteB || !noteB.embedding || !noteB.config.enableMatching || noteA.local_id === noteB.local_id) continue; // Skip self, notes without embedding, or matching disabled

            // 1. Check Embedding Similarity
            const similarity = cosineSimilarity(noteA.embedding, noteB.embedding);

            if (similarity >= embeddingSimilarityThreshold) {
                // 2. Check Semantic Property Alignment (A.ideal vs B.real AND B.ideal vs A.real)
                const alignmentAB = checkPropertyAlignment(noteA, noteB); // Does B satisfy A's ideals?
                const alignmentBA = checkPropertyAlignment(noteB, noteA); // Does A satisfy B's ideals?

                // Match requires embedding similarity AND at least one direction of property alignment
                if (alignmentAB.satisfied || alignmentBA.satisfied) {
                    potentialMatches.push({
                        noteA_id: noteA.local_id,
                        noteB_id: noteB.local_id, // Could be local UUID or nostr-eventID
                        noteB_owner: noteB.owner_id, // Include owner pubkey for Nostr notes
                        similarity: similarity,
                        alignment_A_ideal_vs_B_real: alignmentAB, // { satisfied: bool, details: [...] }
                        alignment_B_ideal_vs_A_real: alignmentBA, // { satisfied: bool, details: [...] }
                    });
                    console.log(`Potential Match Found: ${noteA.local_id.substring(0,5)}... <=> ${noteB.local_id.substring(0,8)}... (Sim: ${similarity.toFixed(3)})`);
                }
            }
        }
    }

    // Sort matches by similarity?
    potentialMatches.sort((a, b) => b.similarity - a.similarity);

    send(ws, { type: 'matchesFound', payload: potentialMatches });
}

// Helper function to check if noteB's real values satisfy noteA's ideal specifications
function checkPropertyAlignment(noteA, noteB) {
    const alignmentDetails = [];
    let overallSatisfied = true; // Start assuming satisfied, prove otherwise
    let idealPropsChecked = 0;

    // Iterate through Note A's properties that have an 'ideal' specification
    const idealPropertiesA = noteA.semantic_properties.filter(p => p.value_ideal !== null && p.value_ideal !== undefined);

    if (idealPropertiesA.length === 0) {
        return { satisfied: false, details: [], reason: "Note A has no ideal properties defined." }; // Cannot satisfy if no ideals are set
    }

    for (const propA of idealPropertiesA) {
        idealPropsChecked++;
        const propNameA = propA.name.toLowerCase();
        const propTypeA = propA.type;
        const idealSpecA = propA.value_ideal;

        // Find the corresponding property in Note B (match by name, case-insensitive)
        const propB = noteB.semantic_properties.find(p => p.name.toLowerCase() === propNameA);

        let satisfied = false;
        let detail = {
            property_name: propA.name,
            ideal_spec: idealSpecA,
            real_value_B: propB ? propB.value_real : null,
            satisfied: false
        };

        if (propB && propB.value_real !== null && propB.value_real !== undefined) {
            // Evaluate the predicate using the utility function
            satisfied = evaluatePredicate(propB.value_real, idealSpecA, propTypeA);
            detail.satisfied = satisfied;
        } else {
            // Property doesn't exist in B, or has no real value
            detail.reason = propB ? "Missing real value in Note B" : "Property not found in Note B";
            // Treat missing property as not satisfying the ideal? This is debatable.
            // Let's say it fails the check if the property is specified as ideal.
            satisfied = false;
        }

        alignmentDetails.push(detail);
        if (!satisfied) {
            overallSatisfied = false; // If any ideal is not met, the overall alignment fails
            // break; // Optimization: can stop early if one fails (optional)
        }
    }

    return { satisfied: overallSatisfied, details: alignmentDetails, reason: overallSatisfied ? "All ideals met" : "One or more ideals not met" };
}


// --- Server Startup ---
server.listen(PORT, () => {
    console.log(`Confluence server listening on port ${PORT}`);
    console.log(`Using Ollama models: Embed='${OLLAMA_MODEL_EMBED}', Extract='${OLLAMA_MODEL_EXTRACT}'`);
    console.log(`Access UI at http://localhost:${PORT}`);
    // Connect to default Nostr relays on startup? Optional.
    // nostrPool.ensureRelays(nostrRelays).then(() => {
    //     console.log(`Connected to initial Nostr relays: ${nostrRelays.join(', ')}`);
    //     broadcastNostrStatus();
    // }).catch(err => console.error("Failed to connect to initial Nostr relays:", err));
});

// Graceful shutdown
process.on('SIGINT', () => {
    console.log('Shutting down...');
    wss.close();
    server.close();
    nostrPool.close(nostrRelays); // Close connections to relays
    process.exit(0);
});