// util.js - Shared utility functions for Confluence

// Basic UUID generation (replace with a robust library like 'uuid' in production)
export function generateUUID() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
        var r = Math.random() * 16 | 0, v = c == 'x' ? r : (r & 0x3 | 0x8);
        return v.toString(16);
    });
}

// --- Predicate Evaluation Logic ---
// Evaluates if a 'realValue' satisfies an 'idealSpec' based on property type
export function evaluatePredicate(realValue, idealSpec, propertyType) {
    if (idealSpec === null || idealSpec === undefined || realValue === null || realValue === undefined) {
        // console.log(`Predicate Eval: Missing real (${realValue}) or ideal (${idealSpec})`);
        return false; // Cannot compare if one is missing
    }
    const { predicate, value: idealValue } = idealSpec;
    if (idealValue === null || idealValue === undefined) {
        // console.log(`Predicate Eval: Missing ideal value`);
        return false;
    }

    // console.log(`Evaluating: real=${realValue} (${typeof realValue}), ideal=${idealValue} (${typeof idealValue}), pred=${predicate}, type=${propertyType}`);

    try {
        switch (propertyType) {
            case 'number': {
                const realNum = parseFloat(realValue);
                const idealNum = parseFloat(idealValue); // For single value predicates
                const idealRange = Array.isArray(idealValue) ? idealValue.map(parseFloat) : null; // For range predicates

                if (isNaN(realNum)) return false;

                switch (predicate) {
                    case '=': return !isNaN(idealNum) && realNum === idealNum;
                    case '>': return !isNaN(idealNum) && realNum > idealNum;
                    case '<': return !isNaN(idealNum) && realNum < idealNum;
                    case '>=': return !isNaN(idealNum) && realNum >= idealNum;
                    case '<=': return !isNaN(idealNum) && realNum <= idealNum;
                    case 'between': return idealRange?.length === 2 && !isNaN(idealRange[0]) && !isNaN(idealRange[1]) && realNum >= Math.min(idealRange[0], idealRange[1]) && realNum <= Math.max(idealRange[0], idealRange[1]);
                    case 'is_one_of': return Array.isArray(idealValue) && idealValue.map(parseFloat).includes(realNum);
                    default: console.warn(`Unsupported number predicate: ${predicate}`); return false;
                }
            }
            case 'string': {
                const realStr = String(realValue).toLowerCase();
                const idealStr = String(idealValue).toLowerCase(); // For single value predicates
                const idealList = Array.isArray(idealValue) ? idealValue.map(v => String(v).toLowerCase()) : null; // For list predicates

                switch (predicate) {
                    case '=': return realStr === idealStr;
                    case 'contains': return realStr.includes(idealStr);
                    case 'starts_with': return realStr.startsWith(idealStr);
                    case 'ends_with': return realStr.endsWith(idealStr);
                    case 'is_one_of': return idealList && idealList.includes(realStr);
                    default: console.warn(`Unsupported string predicate: ${predicate}`); return false;
                }
            }
            case 'boolean': {
                const realBool = typeof realValue === 'string' ? realValue.toLowerCase() === 'true' : !!realValue;
                const idealBool = typeof idealValue === 'string' ? idealValue.toLowerCase() === 'true' : !!idealValue;
                switch (predicate) {
                    case '=': return realBool === idealBool;
                    default: console.warn(`Unsupported boolean predicate: ${predicate}`); return false;
                }
            }
            case 'date': {
                const realDate = new Date(realValue);
                const idealDate = new Date(idealValue); // For single value predicates
                const idealRange = Array.isArray(idealValue) ? idealValue.map(d => new Date(d)) : null; // For range predicates

                if (isNaN(realDate.getTime())) return false;

                switch (predicate) {
                    case '=': return !isNaN(idealDate.getTime()) && realDate.getTime() === idealDate.getTime();
                    case '>': return !isNaN(idealDate.getTime()) && realDate.getTime() > idealDate.getTime();
                    case '<': return !isNaN(idealDate.getTime()) && realDate.getTime() < idealDate.getTime();
                    case '>=': return !isNaN(idealDate.getTime()) && realDate.getTime() >= idealDate.getTime();
                    case '<=': return !isNaN(idealDate.getTime()) && realDate.getTime() <= idealDate.getTime();
                    case 'between':
                        return idealRange?.length === 2 && !isNaN(idealRange[0].getTime()) && !isNaN(idealRange[1].getTime()) &&
                            realDate.getTime() >= Math.min(idealRange[0].getTime(), idealRange[1].getTime()) &&
                            realDate.getTime() <= Math.max(idealRange[0].getTime(), idealRange[1].getTime());
                    default: console.warn(`Unsupported date predicate: ${predicate}`); return false;
                }
            }
            case 'geolocation': // Example: { latitude: 40.7128, longitude: -74.0060 }
                // Predicate 'near': requires idealValue = { center: { latitude, longitude }, radius_km: number }
                if (predicate === 'near' && typeof realValue === 'object' && realValue.latitude && realValue.longitude &&
                    typeof idealValue === 'object' && idealValue.center && idealValue.radius_km) {
                    const dist = calculateDistance(realValue.latitude, realValue.longitude, idealValue.center.latitude, idealValue.center.longitude);
                    return dist <= idealValue.radius_km;
                }
                // Add more geo predicates ('=', 'within_bounds', etc.)
                console.warn(`Unsupported geolocation predicate or format: ${predicate}`);
                return false;
            case 'enum':
                const realEnumStr = String(realValue);
                const idealEnumList = Array.isArray(idealValue) ? idealValue.map(String) : [String(idealValue)];
                switch (predicate) {
                    case '=': return realEnumStr === String(idealValue); // Compare against single ideal value
                    case 'is_one_of': return idealEnumList.includes(realEnumStr);
                    default: console.warn(`Unsupported enum predicate: ${predicate}`); return false;
                }
            // Add other types: duration, color, etc.
            default:
                console.warn(`Unsupported property type for predicate evaluation: ${propertyType}`);
                return false;
        }
    } catch (error) {
        console.error("Error during predicate evaluation:", error, { realValue, idealSpec, propertyType });
        return false;
    }
}

// --- Geolocation Helper (Haversine formula) ---
export function calculateDistance(lat1, lon1, lat2, lon2) {
    const R = 6371; // Radius of the Earth in km
    const dLat = (lat2 - lat1) * Math.PI / 180;
    const dLon = (lon2 - lon1) * Math.PI / 180;
    const a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
        Math.sin(dLon / 2) * Math.sin(dLon / 2);
    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return R * c; // Distance in km
}


// --- Ollama Interaction Helpers (Server-side implementations needed) ---
// These are placeholders for where the actual HTTP calls would go in server.js
// Using fetch requires Node >= 18 or a polyfill like node-fetch

export async function callOllama(endpoint, payload) {
    const OLLAMA_API_URL = process.env.OLLAMA_URL || 'http://localhost:11434/api'; // Make configurable
    const url = `${OLLAMA_API_URL}/${endpoint}`;
    console.log(`Calling Ollama: ${url} with payload:`, JSON.stringify(payload).substring(0, 200) + "..."); // Log truncated payload

    try {
        const response = await fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload),
        });

        if (!response.ok) {
            const errorBody = await response.text();
            console.error(`Ollama API Error (${response.status}): ${errorBody}`);
            throw new Error(`Ollama API request failed with status ${response.status}`);
        }

        const data = await response.json();
        // console.log("Ollama Raw Response:", data); // Debugging
        return data;

    } catch (error) {
        console.error(`Error calling Ollama endpoint ${endpoint}:`, error);
        throw error; // Re-throw to be handled by caller
    }
}

export async function callOllamaExtractProperties(text, model = "llama3") {
    const prompt = `
You are an AI assistant specialized in extracting structured semantic information from text notes.
Analyze the following text and extract key factual data points and user intentions/requirements as a JSON array of objects.
Each object in the array should represent a single semantic property and strictly follow this structure:
{
  "name": "property_name", // Concise, descriptive name (e.g., "destination_city", "meeting_date", "item_price", "desired_color")
  "type": "data_type", // Infer ONE type from: "string", "number", "date", "time", "datetime", "duration", "boolean", "location", "geolocation", "enum", "url"
  "unit": "unit_symbol", // Optional: Standard unit if type is number/duration (e.g., "USD", "EUR", "kg", "km", "minutes", "hours")
  "enum_values": ["value1", "value2"], // Optional: Possible values if type is "enum"
  "value_real": ActualValue, // The factual value extracted directly from the text (string, number, boolean, or structured object for date/geo). Null if not explicitly stated as fact.
  "value_ideal": { // The desired/intended/conditional value. Null if no intention/condition expressed for this property.
    "predicate": "comparison_operator", // Suggest ONE based on context: "=", ">", "<", ">=", "<=", "between", "contains", "starts_with", "ends_with", "is_one_of", "near" (for geo)
    "value": DesiredValueOrRange // The value(s) for comparison (string, number, boolean, date string, array for 'between'/'is_one_of', object for 'near').
  },
  "confidence": 0.85 // Optional: Your confidence score (0.0 to 1.0) for this extraction.
}

Guidelines:
- Focus on specific, actionable pieces of information.
- Infer types and units logically. Use ISO 8601 for dates/datetimes if possible (YYYY-MM-DDTHH:mm:ss).
- Distinguish between facts stated ("value_real") and desires/conditions expressed ("value_ideal"). If something is both stated and desired (e.g., "I need exactly 5 apples"), put it in value_ideal with predicate "=".
- For locations, use "location" for place names (string) and "geolocation" for coordinates (object: { latitude: number, longitude: number }).
- If a list of options is given for a choice, use type "enum" and list them in "enum_values" if defining the property, or use predicate "is_one_of" in "value_ideal".
- Be concise with property names.
- If the text is vague, make a reasonable guess or omit the property. Provide a confidence score if possible.
- Output ONLY the JSON array, nothing else. Ensure the JSON is valid.

Example Input Text:
"Meeting scheduled for tomorrow, June 10th 2024 at 3 PM PST. Need to book a flight from SFO to London Heathrow (LHR) arriving before noon local time on Aug 15th. My budget is ideally less than £1000, max £1200. Looking for hotels near Buckingham Palace, maybe The Ritz or The Savoy? Must have free WiFi."

Example Output JSON:
[
  {"name": "meeting_datetime", "type": "datetime", "value_real": "2024-06-10T15:00:00-07:00", "value_ideal": null, "confidence": 0.95},
  {"name": "departure_airport", "type": "string", "value_real": "SFO", "value_ideal": null, "confidence": 0.9},
  {"name": "arrival_airport", "type": "string", "value_real": "LHR", "value_ideal": null, "confidence": 0.9},
  {"name": "arrival_deadline", "type": "datetime", "value_real": null, "value_ideal": {"predicate": "<", "value": "2024-08-15T12:00:00"}, "confidence": 0.9},
  {"name": "flight_budget", "type": "number", "unit": "GBP", "value_real": null, "value_ideal": {"predicate": "<=", "value": 1200}, "confidence": 0.8},
  {"name": "ideal_flight_budget", "type": "number", "unit": "GBP", "value_real": null, "value_ideal": {"predicate": "<", "value": 1000}, "confidence": 0.75},
  {"name": "hotel_location_preference", "type": "location", "value_real": null, "value_ideal": {"predicate": "near", "value": "Buckingham Palace"}, "confidence": 0.85},
  {"name": "preferred_hotels", "type": "enum", "value_real": null, "value_ideal": {"predicate": "is_one_of", "value": ["The Ritz", "The Savoy"]}, "confidence": 0.7},
  {"name": "hotel_wifi", "type": "boolean", "value_real": null, "value_ideal": {"predicate": "=", "value": true}, "confidence": 0.9}
]

---
Now analyze this text:
\`\`\`
${text}
\`\`\`
Output JSON:
    `;
    const payload = {
        model: model,
        prompt: prompt,
        format: "json", // Request JSON output if supported by the Ollama version/model
        stream: false,
        options: {
            temperature: 0.3, // Lower temperature for more deterministic extraction
            // top_p: 0.9
        }
    };
    const result = await callOllama('generate', payload);
    try {
        // Ollama might return the JSON string within a 'response' field or similar
        const jsonString = result.response || JSON.stringify(result); // Adjust based on actual Ollama response structure
        // console.log("Raw JSON string from Ollama:", jsonString);
        // Clean potential markdown fences or leading/trailing text
        const cleanedJsonString = jsonString.replace(/^```json\s*|```$/g, '').trim();
        return JSON.parse(cleanedJsonString);
    } catch (error) {
        console.error("Failed to parse JSON from Ollama extraction:", error);
        console.error("Ollama raw response was:", result); // Log the raw response for debugging
        return []; // Return empty array on parse failure
    }
}


export async function callOllamaEmbed(text, model = "nomic-embed-text") { // Use an appropriate embedding model
    const payload = {
        model: model,
        prompt: text, // For embeddings, the 'prompt' is usually the text itself
        stream: false
    };
    const result = await callOllama('embeddings', payload);
    return result.embedding; // Adjust based on Ollama's embedding endpoint response structure
}

export async function callOllamaSimilarity(embedding1, embedding2, model = "nomic-embed-text") {
    // Note: Ollama itself doesn't typically have a direct similarity endpoint.
    // Similarity is usually calculated client-side (or server-side) using cosine similarity.
    if (!embedding1 || !embedding2 || embedding1.length !== embedding2.length) {
        console.warn("Cannot calculate similarity: Invalid or mismatched embeddings.");
        return 0;
    }
    return cosineSimilarity(embedding1, embedding2);
}

// --- Cosine Similarity Calculation ---
export function cosineSimilarity(vecA, vecB) {
    if (!vecA || !vecB || vecA.length !== vecB.length) {
        return 0;
    }
    let dotProduct = 0.0;
    let normA = 0.0;
    let normB = 0.0;
    for (let i = 0; i < vecA.length; i++) {
        dotProduct += vecA[i] * vecB[i];
        normA += vecA[i] * vecA[i];
        normB += vecB[i] * vecB[i];
    }
    if (normA === 0 || normB === 0) {
        return 0; // Avoid division by zero
    }
    return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
}


// Export functions for Node.js (if used in server.js) or browser
if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
        generateUUID,
        evaluatePredicate,
        callOllamaExtractProperties,
        callOllamaEmbed,
        callOllamaSimilarity, // Exposing the calculation helper
        cosineSimilarity // Also expose the direct function
    };
}