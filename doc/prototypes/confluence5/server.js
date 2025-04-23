// server.js
import http from 'http';
import fs from 'fs';
import path from 'path';
import { WebSocketServer } from 'ws';
import fetch from 'node-fetch'; // Use node-fetch for Ollama API calls
import {
    createNote, createSemanticProperty, cosineSimilarity, checkPropertyMatch,
    OLLAMA_API_URL, OLLAMA_MODEL, NOSTR_RELAYS, NOSTR_SECRET_KEY, NOSTR_PUBLIC_KEY,

} from './util.js';
import { SimplePool, generateSecretKey, getPublicKey, finalizeEvent, verifyEvent } from 'nostr-tools';

const PORT = process.env.PORT || 3000;

// --- Local Storage ---
// Simple in-memory storage. Replace with file/DB for persistence.
const notes = new Map(); // Map<local_id, Note>
const nostrNoteIndex = new Map(); // Map<network_id, Note> - Index for processed Nostr events
const emergentSchema = new Map(); // Map<propertyName, { types: Set<string>, units: Set<string> }>

function updateSchema(properties) {
    properties.forEach(prop => {
        if (!emergentSchema.has(prop.name)) {
            emergentSchema.set(prop.name, { types: new Set(), units: new Set() });
        }
        const entry = emergentSchema.get(prop.name);
        entry.types.add(prop.type);
        if (prop.unit) entry.units.add(prop.unit);
    });
}

function getSerializableSchema() {
    const serializable = {};
    for (const [name, data] of emergentSchema.entries()) {
        serializable[name] = {
            types: Array.from(data.types),
            units: Array.from(data.units)
        };
    }
    return serializable;
}


// --- Ollama Interaction ---
async function callOllamaGenerate(prompt) {
    try {
        console.log("Sending prompt to Ollama:", prompt);
        const response = await fetch(`${OLLAMA_API_URL}/api/generate`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                model: OLLAMA_MODEL,
                prompt: prompt,
                stream: false, // Get full response at once
                format: "json" // Request JSON output if model supports it
            }),
        });
        if (!response.ok) {
            throw new Error(`Ollama API error: ${response.status} ${response.statusText}`);
        }
        const data = await response.json();
        console.log("Ollama raw response:", data.response);
        // Attempt to parse the JSON string within the response
        try {
            // Sometimes the response might be double-encoded or have markdown backticks
            const cleanedResponse = data.response.replace(/^```json\n?/, '').replace(/\n?```$/, '').trim();
            return JSON.parse(cleanedResponse);
        } catch (parseError) {
            console.error("Failed to parse Ollama JSON response:", parseError);
            console.error("Raw response string was:", data.response);
            // Attempt a more lenient parse (find first '{' or '[') - less reliable
            const jsonMatch = data.response.match(/(\[.*\]|\{.*\})/s);
            if (jsonMatch) {
                try {
                    return JSON.parse(jsonMatch[0]);
                } catch ( lenientParseError) {
                    console.error("Lenient JSON parsing also failed:", lenientParseError);
                }
            }
            return null; // Indicate failure
        }
    } catch (error) {
        console.error('Error calling Ollama /api/generate:', error);
        return null; // Indicate failure
    }
}

async function callOllamaEmbeddings(text) {
    try {
        const response = await fetch(`${OLLAMA_API_URL}/api/embeddings`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                model: OLLAMA_MODEL,
                prompt: text,
            }),
        });
        if (!response.ok) {
            throw new Error(`Ollama API error: ${response.status} ${response.statusText}`);
        }
        const data = await response.json();
        return data.embedding; // Array[Float]
    } catch (error) {
        console.error('Error calling Ollama /api/embeddings:', error);
        return null;
    }
}

async function extractSemanticProperties(text) {
    const prompt = `
Analyze the following text and extract semantic properties as a valid JSON array.
Each object in the array MUST strictly follow this TypeScript interface:
\`\`\`typescript
interface SemanticProperty {
  name: string;      // Property name (e.g., "budget", "location", "deadline", "color_preference", "item_name", "quantity")
  type: string;      // Data type ("number", "string", "geolocation", "date", "duration", "color", "enum", "boolean")
  unit?: string;     // Optional unit for number/duration (e.g., "USD", "km", "minutes", "kg")
  enum_values?: string[]; // Optional possible values if type is "enum"
  value_real?: any;  // The actual, factual value (typed according to \`type\`). Use null if not specified.
  value_ideal?: {    // The desired, intended, or conditional value. Use null if not specified.
    predicate: string; // e.g., "=", ">", "<", ">=", "<=", "between", "contains", "is_one_of", "starts_with", "near"
    value: any;        // The value(s) for comparison (typed according to \`type\`).
  };
  source?: "llm_extracted"; // Set source to "llm_extracted"
  confidence?: number; // Optional confidence score (0.0 to 1.0) - estimate if possible.
}
\`\`\`

Rules:
- Output ONLY the JSON array, nothing else. No explanations, no markdown formatting.
- Infer types and units logically. Use standard units where applicable (e.g., ISO 8601 for dates like YYYY-MM-DDTHH:MM:SSZ, numbers for prices).
- Distinguish between factual statements (\`value_real\`) and desires/conditions (\`value_ideal\`). A single property might have one or both.
- For \`value_ideal\`, choose the most appropriate \`predicate\` based on the text (e.g., "under $15k" -> \`{ predicate: "<", value: 15000 }\`, "by next month" -> estimate date and use \`{ predicate: "<=", value: "YYYY-MM-DDTHH:MM:SSZ" }\`, "blue or green" -> \`{ predicate: "is_one_of", value: ["blue", "green"] }\`).
- If a value is mentioned without clear fact/ideal distinction, lean towards \`value_real\`.
- For locations, use \`type: "geolocation"\` and represent the value as a string name for now (e.g., "New York", "office"). If coordinates or proximity are mentioned, try to capture that (e.g., \`value_ideal: { predicate: "near", value: ["New York", 10] }\` if radius in km is given).
- If a property is mentioned multiple times with different values/conditions, create separate property objects or choose the most specific one.
- Ensure the final output is a single, valid JSON array.

Text to Analyze:
"""
${text}
"""

JSON Output:
`;
    const extractedData = await callOllamaGenerate(prompt);

    if (Array.isArray(extractedData)) {
        // Basic validation and adding source/id
        return extractedData.map(prop => ({
            property_id: uuidv4(),
            name: prop.name || 'unknown',
            type: prop.type || 'string',
            unit: prop.unit,
            enum_values: prop.enum_values,
            value_real: prop.value_real !== undefined ? prop.value_real : null,
            value_ideal: prop.value_ideal !== undefined ? prop.value_ideal : null,
            source: 'llm_extracted',
            confidence: prop.confidence,
        })).filter(prop => prop.name !== 'unknown'); // Filter out invalid entries
    } else {
        console.warn("LLM did not return a valid JSON array for properties.");
        return [];
    }
}

async function generateEmbeddingForNote(note) {
    // Combine content and key properties for richer embedding
    let embeddingText = note.content;
    note.semantic_properties.forEach(p => {
        embeddingText += `\n[${p.name}: ${p.value_real ?? ''} ${p.value_ideal ? `(ideal: ${p.value_ideal.predicate} ${JSON.stringify(p.value_ideal.value)})` : ''}]`;
    });
    return await callOllamaEmbeddings(embeddingText);
}

// --- Nostr Integration ---
const nostrPool = new SimplePool();
let nostrSubscriptions = []; // Store active subscriptions

async function publishNoteToNostr(note) {
    if (!note || !note.content) return null;

    // Prepare structured data representation (simple JSON in content for now)
    const propertiesForNostr = note.semantic_properties.map(p => ({
        name: p.name,
        type: p.type,
        unit: p.unit,
        value_real: p.value_real,
        // Maybe omit ideal values for public posts unless intended? Or use specific tags?
        // value_ideal: p.value_ideal
    }));

    // Embed properties JSON within the content or use tags
    // Option 1: JSON in content (simpler)
    const contentWithProps = `${note.content}\n\n--- Semantic Data ---\n${JSON.stringify(propertiesForNostr)}`;

    // Option 2: Using tags (more structured, but less standard)
    // const tags = propertiesForNostr.map(p => (
    //     [`p:${p.name}`, JSON.stringify({ t: p.type, u: p.unit, vr: p.value_real })]
    // ));
    // tags.push(['client', 'ConfluenceApp']); // Identify the app

    const event = {
        kind: 1, // Text note
        pubkey: NOSTR_PUBLIC_KEY,
        created_at: Math.floor(Date.now() / 1000),
        tags: [
            ['client', 'ConfluenceApp'],
            // Add other relevant tags, e.g., #hashtags based on properties?
            // ...tags // Uncomment if using Option 2
        ],
        // content: note.content, // Use this if using Option 2 tags
        content: contentWithProps, // Use this if using Option 1 JSON in content
    };

    try {
        const signedEvent = finalizeEvent(event, NOSTR_SECRET_KEY);
        const pubs = await nostrPool.publish(NOSTR_RELAYS, signedEvent);
        console.log(`Published event ${signedEvent.id} to ${pubs.length} relays.`);
        return signedEvent;
    } catch (error) {
        console.error("Failed to publish note to Nostr:", error);
        return null;
    }
}

async function processIncomingNostrEvent(event, ws) {
    if (event.kind !== 1 || nostrNoteIndex.has(event.id)) { // Only process Kind 1 text notes, avoid duplicates
        return;
    }
    console.log(`Processing Nostr event ${event.id} from ${event.pubkey}`);

    // Basic check if it might contain our structured data
    let contentToAnalyze = event.content;
    let extractedProps = [];
    const dataMarker = '--- Semantic Data ---';
    const dataIndex = event.content.indexOf(dataMarker);

    if (dataIndex !== -1) {
        const jsonData = event.content.substring(dataIndex + dataMarker.length).trim();
        try {
            const parsedProps = JSON.parse(jsonData);
            if (Array.isArray(parsedProps)) {
                extractedProps = parsedProps.map(p => createSemanticProperty(
                    p.name, p.type, p.value_real, undefined /* no ideal parsing here */, 'nostr_event', p.unit
                ));
                contentToAnalyze = event.content.substring(0, dataIndex).trim(); // Analyze text before marker
                console.log(`Found ${extractedProps.length} properties in Nostr event ${event.id}`);
            }
        } catch (e) {
            console.warn(`Failed to parse semantic data JSON in Nostr event ${event.id}, analyzing full content.`);
            contentToAnalyze = event.content; // Fallback to analyzing everything
        }
    } else {
        // If no marker, use LLM to extract from the whole content
        console.log(`No semantic data marker found in ${event.id}, using LLM extraction.`);
        extractedProps = await extractSemanticProperties(event.content);
    }

    // Create a temporary Note representation
    const tempNote = createNote(contentToAnalyze); // Use original content for context
    tempNote.network_id = event.id;
    tempNote.owner_id = event.pubkey;
    tempNote.network_ts = event.created_at * 1000; // Convert sec to ms
    tempNote.semantic_properties = extractedProps; // Use parsed/extracted properties
    tempNote.status = 'active'; // Treat as active for matching
    tempNote.config.isPublished = true; // It came from the network

    // Generate embedding (optional, could be heavy)
    tempNote.embedding = await generateEmbeddingForNote(tempNote); // Use original content + props

    // Store it temporarily for matching
    nostrNoteIndex.set(tempNote.network_id, tempNote);
    updateSchema(tempNote.semantic_properties); // Update schema based on incoming data

    console.log(`Stored temporary note for Nostr event ${event.id}`);

    // Send to client for display/potential matching
    ws.send(JSON.stringify({ type: 'nostrEventProcessed', payload: tempNote }));

    // Trigger matching against local notes
    triggerMatching(ws, tempNote); // Match this new Nostr note against local ones
}


function subscribeToNostrFeed(ws, filters = [{ kinds: [1], limit: 50 }]) { // Default: recent 50 text notes
    // Unsubscribe from previous feeds if any for this client connection? Or allow multiple?
    // For simplicity, let's assume one main feed subscription per client for now.
    if (nostrSubscriptions.length > 0) {
        nostrSubscriptions.forEach(sub => sub.unsub());
        nostrSubscriptions = [];
        console.log("Unsubscribed from previous Nostr feeds.");
    }

    //console.log("Subscribing to Nostr feed with filters:", filters);
    // const sub = nostrPool.sub(NOSTR_RELAYS, filters);
    // sub.on('event', event => {
    //     // Process the event asynchronously
    //     processIncomingNostrEvent(event, ws).catch(err => {
    //         console.error(`Error processing incoming Nostr event ${event?.id}:`, err);
    //     });
    // });
    // sub.on('eose', () => {
    //     console.log(`Nostr feed subscription EOSE received.`);
    //     // Maybe notify client?
    // });
    // nostrSubscriptions.push(sub); // Store the subscription object
}


// --- Matching Logic ---
const SIMILARITY_THRESHOLD = 0.75; // Example threshold for embedding match

async function findMatches(targetNote) {
    const potentialMatches = [];
    const allNotes = [...notes.values(), ...nostrNoteIndex.values()];

    if (!targetNote.embedding) {
        console.warn(`Target note ${targetNote.local_id || targetNote.network_id} has no embedding, skipping matching.`);
        return [];
    }

    for (const candidateNote of allNotes) {
        // Don't match a note against itself
        if ((targetNote.local_id && targetNote.local_id === candidateNote.local_id) ||
            (targetNote.network_id && targetNote.network_id === candidateNote.network_id)) {
            continue;
        }

        // Skip if candidate has no embedding
        if (!candidateNote.embedding) {
            continue;
        }

        // 1. Check Embedding Similarity
        const similarity = cosineSimilarity(targetNote.embedding, candidateNote.embedding);

        if (similarity >= SIMILARITY_THRESHOLD) {
            // 2. Check Semantic Property Alignment (Real vs. Ideal)
            const propertyCheck = checkPropertyMatch(targetNote, candidateNote);

            // Define "Match": High similarity AND at least one property condition met? Or all ideals met?
            // Let's use the stricter definition from checkPropertyMatch: all ideals must be met if specified.
            // Or a more flexible approach: high similarity OR significant property match?
            // Let's try: high similarity AND the property check indicates *some* compatibility (isMatch = true)
            if (propertyCheck.isMatch) {
                potentialMatches.push({
                    note: candidateNote, // The matching note
                    similarity: similarity,
                    propertyMatchDetails: propertyCheck,
                });
            }
            // Optional: Log near misses (high similarity but failed property check)
            // else {
            //     console.log(`Near miss: High similarity (${similarity.toFixed(3)}) but property mismatch between ${targetNote.local_id || targetNote.network_id} and ${candidateNote.local_id || candidateNote.network_id}. Mismatches:`, propertyCheck.mismatches);
            // }
        }
    }

    // Sort matches by similarity? Or property match quality?
    potentialMatches.sort((a, b) => b.similarity - a.similarity);

    console.log(`Found ${potentialMatches.length} matches for note ${targetNote.local_id || targetNote.network_id}`);
    return potentialMatches;
}

// Trigger matching for a specific note or all notes
async function triggerMatching(ws, specificNote = null) {
    console.log(`Triggering matching. Specific note provided: ${!!specificNote}`);
    const notesToMatch = specificNote ? [specificNote] : Array.from(notes.values());
    let allMatches = [];

    for (const note of notesToMatch) {
        if (note.config.enableMatching !== false && note.status === 'active') {
            const matches = await findMatches(note);
            if (matches.length > 0) {
                allMatches.push({ sourceNoteId: note.local_id || note.network_id, matches });
            }
        }
    }

    if (specificNote && nostrNoteIndex.has(specificNote.network_id)) {
        // If the trigger was an incoming Nostr note, also match local notes against it
        for (const localNote of notes.values()) {
            if (localNote.config.enableMatching !== false && localNote.status === 'active') {
                const matches = await findMatches(localNote); // This will compare localNote against the new specificNote
                // Filter matches to only include the specific Nostr note if found
                const relevantMatches = matches.filter(m => m.note.network_id === specificNote.network_id);
                if (relevantMatches.length > 0) {
                    // Check if this match pair was already added
                    if (!allMatches.some(m => m.sourceNoteId === localNote.local_id && m.matches.some(rm => rm.note.network_id === specificNote.network_id))) {
                        allMatches.push({ sourceNoteId: localNote.local_id, matches: relevantMatches });
                    }
                }
            }
        }
    }


    if (allMatches.length > 0) {
        ws.send(JSON.stringify({ type: 'matchesFound', payload: allMatches }));
    } else {
        // Optionally notify that no matches were found
        // ws.send(JSON.stringify({ type: 'noMatchesFound' }));
        console.log("No new matches found in this cycle.");
    }
}


// --- WebSocket Server ---
const wss = new WebSocketServer({ noServer: true });

wss.on('connection', (ws) => {
    console.log('Client connected');

    // Send initial data
    ws.send(JSON.stringify({ type: 'notesList', payload: Array.from(notes.values()) }));
    ws.send(JSON.stringify({ type: 'schemaUpdate', payload: getSerializableSchema() }));
    ws.send(JSON.stringify({ type: 'identity', payload: { pubkey: NOSTR_PUBLIC_KEY } }));


    // Start default Nostr subscription for this client
    // Subscribe to notes mentioning our pubkey or specific kinds/tags?
    // For now, let's subscribe to a general feed (Kind 1)
    subscribeToNostrFeed(ws, [{ kinds: [1], limit: 20 }]); // Limit initial load


    ws.on('message', async (message) => {
        try {
            const parsedMessage = JSON.parse(message);
            console.log('Received message:', parsedMessage.type);

            switch (parsedMessage.type) {
                case 'createNote': {
                    const newNote = createNote(parsedMessage.payload?.content || '');
                    notes.set(newNote.local_id, newNote);
                    ws.send(JSON.stringify({ type: 'noteCreated', payload: newNote }));
                    // Maybe trigger analysis immediately? Or wait for explicit request?
                    break;
                }

                case 'updateNote': {
                    const updatedNoteData = parsedMessage.payload;
                    if (notes.has(updatedNoteData.local_id)) {
                        const existingNote = notes.get(updatedNoteData.local_id);
                        const updatedNote = { ...existingNote, ...updatedNoteData, updated_at: Date.now() };

                        // Ensure semantic properties have unique IDs if added/modified
                        updatedNote.semantic_properties = updatedNote.semantic_properties.map(p => ({
                            ...p,
                            property_id: p.property_id || uuidv4() // Assign ID if missing
                        }));

                        notes.set(updatedNote.local_id, updatedNote);
                        updateSchema(updatedNote.semantic_properties); // Update schema if props changed

                        // Regenerate embedding if content or significant properties changed
                        // For simplicity, regenerate on every update for now
                        updatedNote.embedding = await generateEmbeddingForNote(updatedNote);
                        notes.set(updatedNote.local_id, updatedNote); // Save embedding

                        ws.send(JSON.stringify({ type: 'noteUpdated', payload: updatedNote }));
                        ws.send(JSON.stringify({ type: 'schemaUpdate', payload: getSerializableSchema() })); // Send updated schema

                        // Trigger matching for the updated note
                        await triggerMatching(ws, updatedNote);

                    } else {
                        console.warn(`Attempted to update non-existent note: ${updatedNoteData.local_id}`);
                    }
                    break;
                }

                case 'deleteNote': {
                    const noteId = parsedMessage.payload;
                    if (notes.delete(noteId)) {
                        ws.send(JSON.stringify({ type: 'noteDeleted', payload: noteId }));
                        // Optionally trigger re-matching for other notes?
                    }
                    break;
                }

                case 'analyzeText': {
                    const { noteId, text } = parsedMessage.payload;
                    const note = notes.get(noteId);
                    if (note) {
                        const properties = await extractSemanticProperties(text);
                        if (properties !== null) {
                            // Send back suggestions - client decides whether to merge them
                            ws.send(JSON.stringify({ type: 'analysisComplete', payload: { noteId, properties } }));
                            updateSchema(properties); // Update schema with suggestions
                            ws.send(JSON.stringify({ type: 'schemaUpdate', payload: getSerializableSchema() }));
                        } else {
                            ws.send(JSON.stringify({ type: 'analysisFailed', payload: { noteId } }));
                        }
                    }
                    break;
                }

                case 'generateEmbedding': { // Allow explicit embedding generation
                    const noteId = parsedMessage.payload;
                    const note = notes.get(noteId);
                    if (note && !note.embedding) { // Only generate if missing
                        note.embedding = await generateEmbeddingForNote(note);
                        notes.set(noteId, note); // Save embedding
                        ws.send(JSON.stringify({ type: 'noteUpdated', payload: note })); // Send updated note with embedding
                        console.log(`Generated embedding for note ${noteId}`);
                        await triggerMatching(ws, note); // Trigger matching now that embedding exists
                    }
                    break;
                }

                case 'publishNote': {
                    const noteId = parsedMessage.payload;
                    const note = notes.get(noteId);
                    if (note) {
                        const nostrEvent = await publishNoteToNostr(note);
                        if (nostrEvent) {
                            note.network_id = nostrEvent.id;
                            note.owner_id = nostrEvent.pubkey;
                            note.network_ts = nostrEvent.created_at * 1000;
                            note.config.isPublished = true;
                            note.updated_at = Date.now();
                            notes.set(noteId, note);
                            ws.send(JSON.stringify({ type: 'notePublished', payload: note }));
                        } else {
                            // Notify client of publish failure?
                            console.error(`Failed to publish note ${noteId} to Nostr.`);
                        }
                    }
                    break;
                }

                case 'updateNostrSubscription': {
                    const filters = parsedMessage.payload.filters;
                    if (Array.isArray(filters)) {
                        subscribeToNostrFeed(ws, filters);
                    } else {
                        console.warn("Invalid Nostr filters received:", filters);
                    }
                    break;
                }

                case 'triggerFullMatch': {
                    console.log("Client requested full re-matching.");
                    await triggerMatching(ws); // Match all local notes against all known notes
                    break;
                }


                default:
                    console.log('Unknown message type:', parsedMessage.type);
            }
        } catch (error) {
            console.error('Failed to process message:', message, error);
        }
    });

    ws.on('close', () => {
        console.log('Client disconnected');
        // Clean up Nostr subscriptions associated with this client?
        // If subscriptions are shared, maybe not. If per-client, then yes.
        // Assuming shared for now based on `nostrSubscriptions` array.
    });

    ws.on('error', (error) => {
        console.error('WebSocket error:', error);
    });
});

// --- HTTP Server ---
const server = http.createServer((req, res) => {
    if (req.url === '/' || req.url === '/index.html') {
        fs.readFile(path.join(process.cwd(), 'index.html'), (err, data) => {
            if (err) {
                res.writeHead(500);
                res.end('Error loading index.html');
                return;
            }
            res.writeHead(200, { 'Content-Type': 'text/html' });
            res.end(data);
        });
    } else if (req.url === '/util.js') {
        fs.readFile(path.join(process.cwd(), 'util.js'), (err, data) => {
            if (err) {
                res.writeHead(500);
                res.end('Error loading util.js');
                return;
            }
            res.writeHead(200, { 'Content-Type': 'application/javascript' });
            res.end(data);
        });
    }
    else {
        res.writeHead(404);
        res.end('Not Found');
    }
});

// --- WebSocket Upgrade Handling ---
server.on('upgrade', (request, socket, head) => {
    wss.handleUpgrade(request, socket, head, (ws) => {
        wss.emit('connection', ws, request);
    });
});

// --- Start Server ---
server.listen(PORT, () => {
    console.log(`Server listening on port ${PORT}`);
    console.log(`Nostr PubKey: ${NOSTR_PUBLIC_KEY}`);
    // Connect to Nostr relays on startup
    // nostrPool.ensureRelays(NOSTR_RELAYS).then(() => {
    //     console.log(`Connected to Nostr relays: ${NOSTR_RELAYS.join(', ')}`);
    // }).catch(err => {
    //     console.error("Failed to connect to Nostr relays:", err);
    // });

    // Load initial data / run migrations if persistence was implemented
});

// Basic persistence (example - save/load on exit/start) - VERY rudimentary
function saveNotes() {
    try {
        const data = JSON.stringify(Array.from(notes.entries()));
        fs.writeFileSync('notes_data.json', data);
        console.log('Notes saved.');
    } catch (err) {
        console.error('Error saving notes:', err);
    }
}

function loadNotes() {
    try {
        if (fs.existsSync('notes_data.json')) {
            const data = fs.readFileSync('notes_data.json');
            const parsed = JSON.parse(data);
            parsed.forEach(([key, value]) => notes.set(key, value));
            console.log(`Loaded ${notes.size} notes.`);
            // Regenerate embeddings and update schema on load if needed
            let embeddingsGenerated = 0;
            const loadPromises = Array.from(notes.values()).map(async note => {
                updateSchema(note.semantic_properties);
                if (!note.embedding) {
                    note.embedding = await generateEmbeddingForNote(note);
                    notes.set(note.local_id, note); // Update map with embedding
                    embeddingsGenerated++;
                }
            });
            Promise.all(loadPromises).then(() => {
                if(embeddingsGenerated > 0) console.log(`Generated ${embeddingsGenerated} missing embeddings on load.`);
                console.log("Schema updated from loaded notes.");
            });
        }
    } catch (err) {
        console.error('Error loading notes:', err);
    }
}

// loadNotes(); // Load notes on start
// process.on('SIGINT', () => { saveNotes(); process.exit(); }); // Save notes on exit
// process.on('SIGTERM', () => { saveNotes(); process.exit(); });