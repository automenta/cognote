// util.js
// Shared utilities for client and server (though primarily used by server here)
import { v4 as uuidv4 } from 'uuid';
import { SimplePool, generateSecretKey, getPublicKey, finalizeEvent, verifyEvent } from 'nostr-tools';
import { WebSocket } from 'ws'; // Only needed on server-side if used here, otherwise just for type hints if using TS

// Interfaces (TypeScript style for clarity, but this is JS)
/*
interface SemanticProperty {
  property_id: string; // UUID
  name: string;
  type: string; // "number", "string", "geolocation", "date", "duration", "color", "enum", "boolean"
  unit?: string;
  enum_values?: string[];
  value_real?: any;
  value_ideal?: IdealSpecification;
  source: "llm_extracted" | "user_defined" | "nostr_event";
  confidence?: number;
}

interface IdealSpecification {
  predicate: string; // "=", ">", "<", ">=", "<=", "between", "contains", "is_one_of", "starts_with", "near" (for geo)
  value: any;
}

interface Note {
  local_id: string; // UUID
  network_id?: string; // Nostr event ID
  owner_id?: string; // Nostr pubkey
  content: string; // Rich text / source text
  semantic_properties: SemanticProperty[];
  embedding?: number[]; // Float array
  status: "active" | "archived" | "template";
  config: {
    isPublished?: boolean;
    enableMatching?: boolean;
    isTemplate?: boolean;
    // Add other note-specific settings
  };
  created_at: number; // Timestamp ms
  updated_at: number; // Timestamp ms
  network_ts?: number; // Nostr event timestamp (seconds -> ms)
}
*/

export const createNote = (content = '', initialProps = []) => {
    const now = Date.now();
    return {
        local_id: uuidv4(),
        network_id: null,
        owner_id: null,
        content: content,
        semantic_properties: initialProps.map(prop => ({ ...prop, property_id: uuidv4() })),
        embedding: null,
        status: "active",
        config: {
            isPublished: false,
            enableMatching: true,
            isTemplate: false,
        },
        created_at: now,
        updated_at: now,
        network_ts: null,
    };
};

export const createSemanticProperty = (name, type, value_real = null, value_ideal = null, source = "user_defined", unit = undefined, enum_values = undefined, confidence = undefined) => ({
    property_id: uuidv4(),
    name,
    type,
    unit,
    enum_values,
    value_real,
    value_ideal,
    source,
    confidence,
});

// Basic cosine similarity
export function cosineSimilarity(vecA, vecB) {
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
}

// --- Property Matching Logic ---

function compareValues(type, predicate, idealValue, realValue) {
    if (realValue === null || realValue === undefined || idealValue === null || idealValue === undefined) {
        return false; // Cannot satisfy a condition with missing data
    }

    // Type coercion helpers
    const num = (v) => parseFloat(v);
    const str = (v) => String(v).toLowerCase();
    const date = (v) => new Date(v).getTime(); // Compare timestamps

    try {
        switch (type) {
            case 'number':
            case 'duration': // Treat duration as number for comparison
                const rNum = num(realValue);
                const iNum = num(idealValue);
                const iNumArr = Array.isArray(idealValue) ? idealValue.map(num) : null;
                switch (predicate) {
                    case '=': return rNum === iNum;
                    case '>': return rNum > iNum;
                    case '<': return rNum < iNum;
                    case '>=': return rNum >= iNum;
                    case '<=': return rNum <= iNum;
                    case 'between': return iNumArr && rNum >= iNumArr[0] && rNum <= iNumArr[1];
                    default: return false;
                }
            case 'string':
            case 'color': // Treat color as string
            case 'geolocation': // Simplified: treat as string match for now
            case 'enum':
                const rStr = str(realValue);
                const iStr = str(idealValue);
                const iStrArr = Array.isArray(idealValue) ? idealValue.map(str) : null;
                switch (predicate) {
                    case '=': return rStr === iStr;
                    case 'contains': return rStr.includes(iStr);
                    case 'starts_with': return rStr.startsWith(iStr);
                    case 'is_one_of': return iStrArr && iStrArr.includes(rStr);
                    // Add other relevant string predicates if needed
                    default: return false;
                }
            case 'date':
                const rDate = date(realValue);
                const iDate = date(idealValue);
                const iDateArr = Array.isArray(idealValue) ? idealValue.map(date) : null;
                if (isNaN(rDate) || isNaN(iDate) || (iDateArr && iDateArr.some(isNaN))) return false;
                switch (predicate) {
                    case '=': return rDate === iDate; // Exact same millisecond
                    case '>': return rDate > iDate;   // After
                    case '<': return rDate < iDate;   // Before
                    case '>=': return rDate >= iDate; // On or after
                    case '<=': return rDate <= iDate; // On or before
                    case 'between': return iDateArr && rDate >= iDateArr[0] && rDate <= iDateArr[1];
                    default: return false;
                }
            case 'boolean':
                const rBool = !!realValue; // Coerce to boolean
                const iBool = !!idealValue;
                switch (predicate) {
                    case '=': return rBool === iBool;
                    default: return false; // Only equality makes sense for boolean
                }
            // case 'geolocation': // Proper geo requires libraries or complex math
            //     if (predicate === 'near' && Array.isArray(realValue) && Array.isArray(idealValue) && idealValue.length >= 3) {
            //         // idealValue = [lat, lon, radius_km]
            //         // realValue = [lat, lon]
            //         // Implement Haversine distance calculation here
            //         return calculateDistance(realValue[0], realValue[1], idealValue[0], idealValue[1]) <= idealValue[2];
            //     }
            //     return false;
            default:
                console.warn(`Unsupported type for comparison: ${type}`);
                return false;
        }
    } catch (e) {
        console.error(`Error comparing values: type=${type}, predicate=${predicate}, ideal=${idealValue}, real=${realValue}`, e);
        return false;
    }
}


// Checks if note B's real values satisfy note A's ideal conditions
export function checkPropertyMatch(noteA, noteB) {
    const matches = [];
    const mismatches = [];

    // Iterate through Note A's ideal properties
    for (const propA of noteA.semantic_properties) {
        if (propA.value_ideal) {
            let foundMatch = false;
            // Find a corresponding property in Note B (match by name, maybe type later?)
            for (const propB of noteB.semantic_properties) {
                if (propA.name === propB.name && propB.value_real !== null && propB.value_real !== undefined) {
                    // Check if B's real value satisfies A's ideal condition
                    if (compareValues(propA.type, propA.value_ideal.predicate, propA.value_ideal.value, propB.value_real)) {
                        matches.push({
                            property_name: propA.name,
                            ideal: propA.value_ideal,
                            real: propB.value_real,
                        });
                        foundMatch = true;
                        break; // Found a satisfying property in B, move to next ideal in A
                    }
                }
            }
            if (!foundMatch) {
                mismatches.push({
                    property_name: propA.name,
                    ideal: propA.value_ideal,
                    reason: `No matching property '${propA.name}' with a suitable real value found in the other note.`,
                });
            }
        }
    }

    // Now check if note A's real values satisfy note B's ideal conditions (reciprocal check)
    for (const propB of noteB.semantic_properties) {
        if (propB.value_ideal) {
            let foundMatch = false;
            // Check if this ideal property was already satisfied in the previous loop (avoid duplicate matches)
            if (matches.some(m => m.property_name === propB.name && JSON.stringify(m.ideal) === JSON.stringify(propB.value_ideal))) {
                continue;
            }

            for (const propA of noteA.semantic_properties) {
                if (propB.name === propA.name && propA.value_real !== null && propA.value_real !== undefined) {
                    if (compareValues(propB.type, propB.value_ideal.predicate, propB.value_ideal.value, propA.value_real)) {
                        matches.push({
                            property_name: propB.name,
                            ideal: propB.value_ideal, // B's ideal
                            real: propA.value_real,    // A's real
                        });
                        foundMatch = true;
                        break;
                    }
                }
            }
            if (!foundMatch) {
                // Check if this mismatch was already recorded (avoid duplicate mismatches if names overlap)
                if (!mismatches.some(m => m.property_name === propB.name && JSON.stringify(m.ideal) === JSON.stringify(propB.value_ideal))) {
                    mismatches.push({
                        property_name: propB.name,
                        ideal: propB.value_ideal,
                        reason: `No matching property '${propB.name}' with a suitable real value found in the other note.`,
                    });
                }
            }
        }
    }


    // A match requires at least one successful ideal/real comparison and no unsatisfied ideals.
    // OR: Define match criteria differently? Maybe threshold based?
    // For now, let's say a match requires *all* specified ideals to be met by a real value in the other note.
    const isMatch = mismatches.length === 0 && matches.length > 0;
    // Alternative: A match requires at least one property match.
    // const isMatch = matches.length > 0;

    return {
        isMatch: isMatch, // Or use a more nuanced logic based on match/mismatch counts
        matches: matches, // List of satisfied conditions
        mismatches: mismatches // List of unsatisfied conditions (ideals in A not met by B, or vice-versa)
    };
}

export const OLLAMA_API_URL = process.env.OLLAMA_URL || 'http://localhost:11434';
export const OLLAMA_MODEL = process.env.OLLAMA_MODEL || 'hf.co/mradermacher/phi-4-GGUF:Q4_K_S';

export const NOSTR_RELAYS = process.env.NOSTR_RELAYS ? process.env.NOSTR_RELAYS.split(',') : ['wss://relay.damus.io', 'wss://relay.primal.net'];
export const NOSTR_SECRET_KEY = process.env.NOSTR_SECRET_KEY || generateSecretKey(); // Generate if not provided
export const NOSTR_PUBLIC_KEY = getPublicKey(NOSTR_SECRET_KEY);

console.log(`Using Ollama: ${OLLAMA_API_URL} with model ${OLLAMA_MODEL}`);
console.log(`Using Nostr PubKey: ${NOSTR_PUBLIC_KEY}`);
console.log(`Using Nostr Relays: ${NOSTR_RELAYS.join(', ')}`);

export { uuidv4, SimplePool, generateSecretKey, getPublicKey, finalizeEvent, verifyEvent };