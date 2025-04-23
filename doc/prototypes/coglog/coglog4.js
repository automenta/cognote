/**
 * Coglog 2.6.1 - Probabilistic, Reflective, Self-Executing Logic Engine (Ollama Version)
 *
 * Single-file Node.js implementation using LangChain.js for LLM interaction.
 * Adheres to principles: Complete, Correct, Compact, Consolidated, Deduplicated, Modular, Self-documenting.
 *
 * Usage:
 * 1. Install: `npm install uuid @langchain/community @langchain/core`
 * 2. Run Ollama: `ollama serve` (ensure model pulled, e.g., `ollama pull llama3`)
 * 3. Execute: `node coglog.js` (or set env vars like OLLAMA_MODEL, OLLAMA_BASE_URL, COGLOG_LOG_LEVEL)
 */

// === Dependencies ===
import { randomUUID } from 'crypto';
import { ChatOllama } from "@langchain/community/chat_models/ollama";
import { HumanMessage } from "@langchain/core/messages";
import fs from 'fs/promises';
import path from 'path';

// === Configuration ===
const config = Object.freeze({
    persistenceFile: process.env.COGLOG_STATE_FILE || 'coglog_state.json',
    maxRetries: parseInt(process.env.COGLOG_MAX_RETRIES || '3', 10),
    pollIntervalMillis: parseInt(process.env.COGLOG_POLL_INTERVAL_MS || '100', 10),
    maxActiveDurationMillis: parseInt(process.env.COGLOG_MAX_ACTIVE_MS || '60000', 10), // Increased timeout for local LLMs
    gcIntervalMillis: parseInt(process.env.COGLOG_GC_INTERVAL_MS || `${60 * 60 * 1000}`, 10), // 1 hour
    gcThresholdMillis: parseInt(process.env.COGLOG_GC_THRESHOLD_MS || `${24 * 60 * 60 * 1000}`, 10), // 24 hours
    ollamaBaseUrl: process.env.OLLAMA_BASE_URL || "http://localhost:11434",
    llmModelName: process.env.OLLAMA_MODEL || "llama3", // Ensure this model is pulled in Ollama
    logLevel: process.env.COGLOG_LOG_LEVEL || 'info', // 'debug', 'info', 'warn', 'error'
    maxBeliefValue: Number.MAX_SAFE_INTEGER,
    defaultBeliefPositive: [1.0, 0.0],
    defaultBeliefUncertain: [1.0, 1.0],
    defaultBeliefLowConfidence: [0.1, 0.9],
    defaultBeliefNegative: [0.0, 1.0],
});

// === Logging ===
const logLevels = { debug: 1, info: 2, warn: 3, error: 4 };
const currentLogLevel = logLevels[config.logLevel] || logLevels.info;
const logger = {
    debug: (...args) => currentLogLevel <= logLevels.debug && console.debug('[DEBUG]', new Date().toISOString(), ...args),
    info: (...args) => currentLogLevel <= logLevels.info && console.info('[INFO]', new Date().toISOString(), ...args),
    warn: (...args) => currentLogLevel <= logLevels.warn && console.warn('[WARN]', new Date().toISOString(), ...args),
    error: (...args) => currentLogLevel <= logLevels.error && console.error('[ERROR]', new Date().toISOString(), ...args),
};

// === Constants ===
const Role = Object.freeze({ NOTE: 'NOTE', GOAL: 'GOAL', STRATEGY: 'STRATEGY', OUTCOME: 'OUTCOME', META_THOUGHT: 'META_THOUGHT' });
const Status = Object.freeze({ PENDING: 'PENDING', ACTIVE: 'ACTIVE', WAITING_CHILDREN: 'WAITING_CHILDREN', DONE: 'DONE', FAILED: 'FAILED' });
const ValidStatusTransitions = Object.freeze({ // Allowed direct state changes via set_status
    [Status.PENDING]: [],
    [Status.ACTIVE]: [Status.PENDING, Status.WAITING_CHILDREN, Status.DONE, Status.FAILED],
    [Status.WAITING_CHILDREN]: [Status.DONE, Status.FAILED], // Typically done via check_parent_completion
    [Status.DONE]: [],
    [Status.FAILED]: [],
});
const BeliefType = Object.freeze({ POSITIVE: 'POSITIVE', NEGATIVE: 'NEGATIVE', UNCERTAIN: 'UNCERTAIN', LOW_CONFIDENCE: 'LOW_CONFIDENCE' });

// === Data Structures ===

// Base class for immutable logical terms with common methods.
class TermBase {
    constructor() { if (this.constructor === TermBase) throw new Error("Abstract class");
        //Object.freeze(this);
    }
    applySubstitution(subMap) { return this; } // Default: Atoms, Numbers don't change
    toJSON() { return { type: this.constructor.name, ...this }; } // Simple serialization structure
    toString() { return this._toString(); } // Public method, calls internal
    _toString() { throw new Error("Subclasses must implement _toString"); } // Force implementation
    equals(other) { return this === other || (other?.constructor === this.constructor && this.toString() === other.toString()); } // Basic equality check
}

// Represents an atomic constant (e.g., 'go', 'true').
class Atom extends TermBase {
    constructor(name) {
        super();
        if (typeof name !== 'string' || !name) throw new Error("Atom name must be a non-empty string");
        this.name = name;
        Object.freeze(this);
    }
    _toString() { return this.name; }
}

// Represents a logical variable (e.g., '_X', 'VarName').
class Variable extends TermBase {
    constructor(name) {
        super();
        if (typeof name !== 'string' || !(name.startsWith('_') || /^[A-Z]/.test(name))) throw new Error(`Invalid Variable name: "${name}" (must start with _ or uppercase)`);
        this.name = name;
        Object.freeze(this);
    }
    _toString() { return this.name; }
    // Recursively resolves variable binding in the substitution map. Handles chained bindings.
    applySubstitution(subMap) {
        let current = subMap.get(this.name);
        let visited = new Set(); // Detect cycles
        while (current instanceof Variable && subMap.has(current.name) && !visited.has(current.name)) {
            visited.add(current.name);
            const next = subMap.get(current.name);
            if (next === current || (next instanceof Variable && next.name === current.name)) break; // Self-reference or identical variable
            current = next;
        }
        // If resolved to a different term, apply substitution to that term; otherwise, return self.
        return (current && current !== this && current instanceof TermBase) ? current.applySubstitution(subMap) : this;
    }
}

// Represents a numeric literal.
class NumberTerm extends TermBase {
    constructor(value) {
        super();
        if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error("NumberTerm value must be a finite number");
        this.value = value;
        Object.freeze(this);
    }
    _toString() { return this.value.toString(); }
}

// Represents a structured term (functor and arguments).
class Structure extends TermBase {
    constructor(name, args) {
        super();
        if (typeof name !== 'string' || !name) throw new Error(`Structure name must be a non-empty string (got: ${name})`);
        if (!Array.isArray(args) || !args.every(arg => arg instanceof TermBase)) {
            const invalidArgs = Array.isArray(args) ? args.filter(a => !(a instanceof TermBase)).map(a => typeof a) : 'not an array';
            throw new Error(`Structure args must be an array of Terms. Invalid args: ${invalidArgs} in Structure(${name}, ...)`);
        }
        this.name = name;
        this.args = Object.freeze([...args]); // Ensure args array is immutable
        Object.freeze(this);
    }
    _toString() { return `${this.name}(${this.args.map(a => a.toString()).join(', ')})`; }
    applySubstitution(subMap) {
        const newArgs = this.args.map(arg => arg.applySubstitution(subMap));
        // Return a new instance only if any argument actually changed.
        return newArgs.every((newArg, i) => newArg === this.args[i]) ? this : new Structure(this.name, newArgs);
    }
}

// Represents a list of terms.
class ListTerm extends TermBase {
    constructor(elements) {
        super();
        if (!Array.isArray(elements) || !elements.every(el => el instanceof TermBase)) {
            const invalidElems = Array.isArray(elements) ? elements.filter(el => !(el instanceof TermBase)).map(el => typeof el) : 'not an array';
            throw new Error(`ListTerm elements must be an array of Terms. Invalid elements: ${invalidElems}`);
        }
        this.elements = Object.freeze([...elements]); // Ensure elements array is immutable
        Object.freeze(this);
    }
    _toString() { return `[${this.elements.map(e => e.toString()).join(', ')}]`; }
    applySubstitution(subMap) {
        const newElements = this.elements.map(el => el.applySubstitution(subMap));
        // Return a new instance only if any element actually changed.
        return newElements.every((newEl, i) => newEl === this.elements[i]) ? this : new ListTerm(newElements);
    }
}

// Represents the probabilistic belief in a thought (positive/negative evidence).
class Belief {
    constructor(positive, negative) {
        if (!Number.isFinite(positive) || !Number.isFinite(negative) || positive < 0 || negative < 0) {
            throw new Error(`Invalid Belief counts: p=${positive}, n=${negative}. Must be finite non-negative numbers.`);
        }
        // Cap counts to prevent extreme values, maintaining ratio.
        this.positive = Math.min(positive, config.maxBeliefValue);
        this.negative = Math.min(negative, config.maxBeliefValue);
        Object.freeze(this);
    }
    // Calculates belief score using Laplace smoothing ((p+1)/(p+n+2)). Handles large numbers.
    score() {
        const total = this.positive + this.negative;
        if (total === 0) return 0.5; // Default for no evidence
        // Avoid potential overflow if total is extremely large by using ratio directly
        return total > Number.MAX_SAFE_INTEGER / 2 ? this.positive / total : (this.positive + 1.0) / (total + 2.0);
    }
    // Returns a new Belief instance with updated counts based on signal.
    update(positiveSignal) {
        return positiveSignal
            ? new Belief(this.positive + 1.0, this.negative)
            : new Belief(this.positive, this.negative + 1.0);
    }
    toJSON() { return { positive: this.positive, negative: this.negative }; }
    // Safely reconstructs Belief from JSON, returning uncertain default on failure.
    static fromJSON(json) {
        if (json && typeof json.positive === 'number' && typeof json.negative === 'number') {
            try { return new Belief(json.positive, json.negative); }
            catch (e) { logger.warn("Invalid Belief JSON during reconstruction:", json, e.message); }
        }
        logger.warn("Invalid Belief JSON format, using DEFAULT_UNCERTAIN:", json);
        return Belief.DEFAULT_UNCERTAIN;
    }
    // Predefined common belief states.
    static DEFAULT_POSITIVE = Object.freeze(new Belief(...config.defaultBeliefPositive));
    static DEFAULT_UNCERTAIN = Object.freeze(new Belief(...config.defaultBeliefUncertain));
    static DEFAULT_LOW_CONFIDENCE = Object.freeze(new Belief(...config.defaultBeliefLowConfidence));
    static DEFAULT_NEGATIVE = Object.freeze(new Belief(...config.defaultBeliefNegative));
}

// The core unit of processing, representing a piece of information or task. Immutable.
class Thought {
    constructor(id, role, content, belief, status, metadata) {
        if (typeof id !== 'string' || id.length !== 36) throw new Error(`Invalid Thought ID: ${id}`);
        if (!Object.values(Role).includes(role)) throw new Error(`Invalid Thought Role: ${role}`);
        if (!(content instanceof TermBase)) throw new Error(`Invalid Thought Content: Must be TermBase instance, got ${content?.constructor?.name}`);
        if (!(belief instanceof Belief)) throw new Error(`Invalid Thought Belief: Must be Belief instance, got ${belief?.constructor?.name}`);
        if (!Object.values(Status).includes(status)) throw new Error(`Invalid Thought Status: ${status}`);
        if (typeof metadata !== 'object' || metadata === null) throw new Error("Thought Metadata must be a non-null object");

        this.id = id;
        this.role = role;
        this.content = content; // Assumed immutable TermBase
        this.belief = belief;   // Assumed immutable Belief
        this.status = status;
        this.metadata = Object.freeze({ ...metadata }); // Ensure metadata is immutable
        Object.freeze(this);
    }
    // Provides a mutable builder for creating modified copies.
    toBuilder() { return new ThoughtBuilder(this); }
    toJSON() {
        return {
            id: this.id, role: this.role, content: this.content.toJSON(),
            belief: this.belief.toJSON(), status: this.status, metadata: { ...this.metadata }
        };
    }
    // Safely reconstructs Thought from JSON, throwing on failure.
    static fromJSON(json) {
        try {
            const content = termFromJSON(json.content);
            const belief = Belief.fromJSON(json.belief);
            // Ensure basic required fields exist before construction
            if (!json.id || !json.role || !json.status) throw new Error("Missing required fields (id, role, status)");
            return new Thought(json.id, json.role, content, belief, json.status, json.metadata || {});
        } catch (e) {
            logger.error(`Error reconstructing Thought from JSON (ID: ${json?.id}): ${e.message}`);
            throw new Error(`Failed to reconstruct Thought: ${e.message}`, { cause: e });
        }
    }
}

// Mutable builder pattern for creating modified Thought instances.
class ThoughtBuilder {
    constructor(thought) {
        this.id = thought.id;
        this.role = thought.role;
        this.content = thought.content;
        this.belief = thought.belief;
        this.status = thought.status;
        this.metadata = { ...thought.metadata }; // Create a mutable copy
    }
    _updateMeta(key, value) {
        this.metadata[key] = value;
        this.metadata['last_updated_timestamp'] = Date.now(); // Always update timestamp on meta change
        return this;
    }
    setRole(role) { this.role = role; return this; }
    setContent(content) { this.content = content; return this; }
    setBelief(belief) { this.belief = belief; return this; }
    setStatus(status) { this.status = status; this._updateMeta('last_updated_timestamp', Date.now()); return this; } // Explicitly update timestamp
    setMetadata(key, value) { return this._updateMeta(key, value); }
    replaceMetadata(metadata) { this.metadata = { ...metadata, last_updated_timestamp: Date.now() }; return this; }
    build() { return new Thought(this.id, this.role, this.content, this.belief, this.status, this.metadata); }
}

// Factory function to reconstruct Term instances from JSON.
function termFromJSON(json) {
    if (!json || typeof json !== 'object' || !json.type) {
        throw new Error(`Invalid JSON for Term reconstruction: ${JSON.stringify(json)}`);
    }
    try {
        switch (json.type) {
            case 'Atom': return new Atom(json.name);
            case 'Variable': return new Variable(json.name);
            case 'NumberTerm': return new NumberTerm(json.value);
            case 'Structure': return new Structure(json.name, json.args.map(termFromJSON)); // Recursive call for args
            case 'ListTerm': return new ListTerm(json.elements.map(termFromJSON)); // Recursive call for elements
            default: throw new Error(`Unknown Term type in JSON: ${json.type}`);
        }
    } catch (e) {
        // Wrap nested errors for better debugging context
        throw new Error(`Error reconstructing Term type ${json.type} (nested: ${e.message})`, { cause: e });
    }
}

// === Helper Functions ===

// Generates a standard UUID v4.
const generateUUID = () => randomUUID();

// Attempts to unify two terms, returning a substitution map (Map<string, TermBase>) or null if impossible.
function unify(term1, term2, initialSubstitution = new Map()) {
    const substitution = new Map(initialSubstitution); // Work on a copy
    const stack = [[term1, term2]]; // Stack for iterative unification

    while (stack.length > 0) {
        let [t1, t2] = stack.pop();

        // Resolve variables using the current substitution map before comparing
        t1 = resolveVariable(t1, substitution);
        t2 = resolveVariable(t2, substitution);

        if (t1 === t2 || t1.equals(t2)) continue; // Terms are identical or equal structurally (Atoms, Numbers)

        if (t1 instanceof Variable) {
            if (occursCheck(t1, t2, substitution)) return null; // Occurs check fails
            substitution.set(t1.name, t2); // Bind variable t1 to term t2
            continue;
        }

        if (t2 instanceof Variable) {
            if (occursCheck(t2, t1, substitution)) return null; // Occurs check fails (order reversed)
            substitution.set(t2.name, t1); // Bind variable t2 to term t1
            continue;
        }

        // Structure unification: check name, arity, and unify arguments recursively
        if (t1 instanceof Structure && t2 instanceof Structure && t1.name === t2.name && t1.args.length === t2.args.length) {
            for (let i = 0; i < t1.args.length; i++) {
                stack.push([t1.args[i], t2.args[i]]);
            }
            continue;
        }

        // List unification: check length and unify elements recursively
        if (t1 instanceof ListTerm && t2 instanceof ListTerm && t1.elements.length === t2.elements.length) {
            for (let i = 0; i < t1.elements.length; i++) {
                stack.push([t1.elements[i], t2.elements[i]]);
            }
            continue;
        }

        // If none of the above conditions met, unification fails
        return null;
    }

    return substitution; // Unification successful
}

// Fully resolves a variable's binding in a substitution map, handling chains.
function resolveVariable(term, substitutionMap) {
    let current = term;
    let visited = null; // Lazy init for cycle detection
    while (current instanceof Variable && substitutionMap.has(current.name)) {
        const boundValue = substitutionMap.get(current.name);
        // Stop if bound to itself or an identical variable, or if a cycle is detected
        if (boundValue === current || (boundValue instanceof Variable && boundValue.name === current.name)) return current;
        if (visited?.has(current.name)) return current; // Cycle detected

        if (!visited) visited = new Set();
        visited.add(current.name);
        current = boundValue; // Follow the chain
    }
    return current; // Return the final resolved term (or the original if not a bound variable)
}

// Checks if a variable occurs within a term (crucial for preventing infinite loops in unification).
function occursCheck(variable, term, substitutionMap) {
    const resolvedTerm = resolveVariable(term, substitutionMap); // Resolve term first

    if (variable === resolvedTerm || (variable instanceof Variable && resolvedTerm instanceof Variable && variable.name === resolvedTerm.name)) {
        return true; // Variable is identical to the resolved term
    }

    // Only need to check inside Structures and Lists
    if (!(resolvedTerm instanceof Structure || resolvedTerm instanceof ListTerm)) {
        return false;
    }

    const stack = [resolvedTerm]; // Stack for iterative traversal
    const visited = new Set(); // Avoid redundant checks

    while (stack.length > 0) {
        const current = resolveVariable(stack.pop(), substitutionMap); // Resolve each subterm

        if (variable === current || (variable instanceof Variable && current instanceof Variable && variable.name === current.name)) {
            return true; // Found the variable
        }

        if (!current || visited.has(current)) continue; // Already checked or not a term to check further

        // Add sub-terms to the stack if Structure or List
        if (current instanceof Structure) {
            visited.add(current);
            current.args.forEach(arg => stack.push(arg));
        } else if (current instanceof ListTerm) {
            visited.add(current);
            current.elements.forEach(element => stack.push(element));
        }
        // Atoms, Numbers, and unbound Variables don't contain other terms
    }

    return false; // Variable not found
}

// Applies a substitution map to a term, returning a new term instance.
function applySubstitution(term, substitutionMap) {
    if (!(term instanceof TermBase)) throw new Error(`Cannot apply substitution to non-Term: ${term}`);
    if (!(substitutionMap instanceof Map)) throw new Error(`Substitution map must be a Map instance`);
    return term.applySubstitution(substitutionMap); // Delegate to the term's own method
}

// Selects an item from a list probabilistically based on associated weights (scores).
function sampleWeighted(itemsWithScores) {
    if (!Array.isArray(itemsWithScores) || itemsWithScores.length === 0) return null;

    // Filter out invalid entries (non-numeric/non-positive scores, undefined items)
    const validItems = itemsWithScores.filter(([score, item]) =>
        typeof score === 'number' && Number.isFinite(score) && score > 0 && item !== undefined && item !== null
    );

    if (validItems.length === 0) return null;

    const totalWeight = validItems.reduce((sum, [score]) => sum + score, 0);
    if (totalWeight <= 0) return null; // Should not happen with score > 0 filter, but safety check

    let randomValue = Math.random() * totalWeight;

    for (const [score, item] of validItems) {
        randomValue -= score;
        if (randomValue <= 0) {
            return item; // Found the item
        }
    }

    // Fallback due to potential floating point inaccuracies, return the last valid item
    return validItems[validItems.length - 1][1];
}

// === External Service Wrappers ===

// Handles interaction with the Ollama LLM service.
class LlmService {
    constructor() {
        logger.info(`Initializing Ollama service: Model=${config.llmModelName}, BaseURL=${config.ollamaBaseUrl}`);
        try {
            this.client = new ChatOllama({ baseUrl: config.ollamaBaseUrl, model: config.llmModelName });
        } catch (e) {
            logger.error(`Failed to initialize ChatOllama client: ${e.message}`);
            throw new Error(`Ollama client initialization failed. Is Ollama running and the model available? ${e.message}`, { cause: e });
        }
    }
    // Invokes the LLM with a given prompt.
    async invoke(prompt) {
        if (!this.client) throw new Error("LLM Service client not initialized.");
        logger.debug(`Ollama Call prompt (first 100 chars): "${prompt.substring(0, 100)}..."`);
        try {
            const response = await this.client.invoke([new HumanMessage(prompt)]);
            const content = response?.content;
            if (typeof content !== 'string') { // Check if content is a string, handle non-string cases if necessary
                throw new Error(`Ollama response content is not a string: ${JSON.stringify(content)}`);
            }
            logger.debug(`Ollama Response received (first 100 chars): "${content.substring(0, 100)}..."`);
            return content;
        } catch (e) {
            const errorMessage = e?.response?.data?.error?.message || e?.message || 'Unknown Ollama API error';
            logger.error(`Ollama invocation failed: ${errorMessage}`);
            // More specific error for API call failures vs client issues
            throw new Error(`Ollama API call failed: ${errorMessage}`, { cause: e });
        }
    }
}

// Manages saving and loading the engine's state (Thoughts) to/from a file.
class PersistenceService {
    constructor(filePath) {
        this.filePath = path.resolve(filePath); // Ensure absolute path
        logger.info(`Persistence path set to: ${this.filePath}`);
    }
    // Saves the current state (map of Thoughts) to the file atomically.
    async save(thoughtMap) {
        logger.debug(`Persisting ${thoughtMap.size} thoughts to ${this.filePath}...`);
        const tempFilePath = this.filePath + '.tmp'; // Use temporary file for atomic write
        try {
            // Convert Map values (Thoughts) to JSON array
            const dataToSave = Array.from(thoughtMap.values()).map(thought => thought.toJSON());
            await fs.writeFile(tempFilePath, JSON.stringify(dataToSave, null, 2)); // Pretty print JSON
            await fs.rename(tempFilePath, this.filePath); // Atomic rename replaces old file
            logger.debug(`Persistence successful.`);
        } catch (e) {
            logger.error(`Failed to save state to ${this.filePath}:`, e);
            try { await fs.unlink(tempFilePath); } // Clean up temp file on error
            catch (_) { /* Ignore unlink error */ }
            throw new Error(`Failed to save state: ${e.message}`, { cause: e }); // Re-throw for upstream handling
        }
    }
    // Loads the engine state from the file. Returns empty map if file not found or invalid.
    async load() {
        logger.debug(`Loading state from ${this.filePath}...`);
        try {
            const fileContent = await fs.readFile(this.filePath, 'utf-8');
            const jsonArray = JSON.parse(fileContent);
            if (!Array.isArray(jsonArray)) throw new Error("Loaded state file is not a JSON array.");

            const thoughtMap = new Map();
            let invalidCount = 0;
            jsonArray.forEach((jsonItem, index) => {
                try {
                    const thought = Thought.fromJSON(jsonItem);
                    if (thoughtMap.has(thought.id)) {
                        logger.warn(`Duplicate Thought ID "${thought.id}" found during load at index ${index}. Skipping duplicate.`);
                        invalidCount++;
                    } else {
                        thoughtMap.set(thought.id, thought);
                    }
                } catch (e) {
                    logger.warn(`Skipping invalid thought data at index ${index} during load: ${e.message}`);
                    invalidCount++;
                }
            });
            logger.info(`Loaded ${thoughtMap.size} thoughts successfully. Skipped ${invalidCount} invalid entries.`);
            return thoughtMap;
        } catch (e) {
            if (e.code === 'ENOENT') { // File does not exist
                logger.info(`Persistence file ${this.filePath} not found. Starting with an empty state.`);
                return new Map();
            }
            // Other errors (read error, JSON parse error, etc.)
            logger.error(`Failed to load state from ${this.filePath}:`, e);
            logger.warn("Proceeding with an empty state due to load failure.");
            return new Map(); // Return empty map on error to allow startup
        }
    }
}

// Simple notifier for store events (can be extended for UI updates, etc.).
class StoreNotifier {
    // Logs the type of event and basic thought info.
    notify(eventType, thought) {
        logger.debug(`Store Event: ${eventType.toUpperCase()} - ID: ${thought.id}, Status: ${thought.status}, Role: ${thought.role}`);
        // Placeholder for more complex notification logic (e.g., web sockets, event bus)
    }
}

// Parses raw text input into an initial Thought.
class TextParserService {
    // Creates a basic NOTE Thought from a string. Returns null for empty input.
    parse(textInput) {
        if (typeof textInput !== 'string' || !textInput.trim()) return null;
        logger.debug(`Parsing text input: "${textInput}"`);
        // Use helpers for structure creation
        const content = S("user_input", A(textInput.trim()));
        const now = Date.now();
        return new Thought(
            generateUUID(),
            Role.NOTE,
            content,
            Belief.DEFAULT_POSITIVE, // Assume initial user input is positively intended
            Status.PENDING,
            { creation_timestamp: now, last_updated_timestamp: now }
        );
    }
}

// === Core Components ===

// Global context (e.g., for provenance tracking). Minimal use encouraged.
const executionContext = {
    currentMetaThoughtId: null // Tracks the ID of the META_THOUGHT currently being executed
};

// In-memory store for Thoughts with persistence and notification hooks.
class ThoughtStore {
    constructor(persistenceService, storeNotifier) {
        this.thoughts = new Map(); // id -> Thought map
        this.persistenceService = persistenceService;
        this.storeNotifier = storeNotifier;
        this.savingPromise = null; // Tracks ongoing save operation
        this.saveQueued = false; // Flags if a save is needed after the current one finishes
    }

    // Loads initial state from persistence.
    async loadState() {
        this.thoughts = await this.persistenceService.load();
    }

    // Asynchronously persists the current state, queuing if already saving.
    async persistState() {
        if (this.savingPromise) {
            this.saveQueued = true; // Queue the save request
            logger.debug("Persistence already in progress, queuing request.");
            return;
        }
        // Start the save operation
        this.savingPromise = this.persistenceService.save(this.thoughts)
            .catch(e => logger.error("Background persistence failed:", e)) // Log errors from background save
            .finally(async () => {
                this.savingPromise = null; // Clear the promise lock
                if (this.saveQueued) { // If a save was queued during the operation
                    this.saveQueued = false;
                    logger.debug("Starting queued persistence operation.");
                    await this.persistState(); // Trigger the queued save immediately
                }
            });
        // Optionally await the current save if immediate confirmation is needed,
        // but generally run it in the background.
        // await this.savingPromise;
    }

    // Retrieves a Thought by its ID.
    getThought(id) { return this.thoughts.get(id); }

    // Adds a new Thought to the store.
    addThought(thought) {
        if (!(thought instanceof Thought)) throw new Error("Invalid argument: Must provide a Thought instance to addThought.");
        if (this.thoughts.has(thought.id)) {
            logger.warn(`Attempted to add Thought with duplicate ID: ${thought.id}. Skipping.`);
            return false; // Indicate failure due to duplicate ID
        }
        this.thoughts.set(thought.id, thought);
        logger.debug(`Added Thought: ${thought.id} (Role: ${thought.role}, Status: ${thought.status})`);
        this.storeNotifier.notify('add', thought);
        this.persistState(); // Trigger background persistence
        return true;
    }

    // Atomically updates a Thought if the oldThought matches the current state.
    updateThought(oldThought, newThought) {
        if (!(oldThought instanceof Thought) || !(newThought instanceof Thought)) throw new Error("Invalid arguments: Must provide Thought instances to updateThought.");
        if (oldThought.id !== newThought.id) throw new Error("Cannot change Thought ID during update.");

        const currentThought = this.thoughts.get(oldThought.id);
        if (!currentThought) {
            logger.warn(`Attempted to update non-existent Thought: ${oldThought.id}`);
            return false; // Indicate failure: thought not found
        }
        // Optimistic locking: only update if the provided oldThought matches the one currently in store
        if (currentThought !== oldThought) {
            logger.warn(`Update conflict for Thought ${oldThought.id}. Provided oldThought does not match current state. Update rejected.`);
            // Debugging: Log differences if helpful
            // logger.debug("Current state:", currentThought);
            // logger.debug("Provided old state:", oldThought);
            return false; // Indicate failure due to conflict
        }

        // Add provenance tracking: add the current meta-thought ID if available
        const metaId = executionContext.currentMetaThoughtId;
        let provenance = Array.isArray(newThought.metadata.provenance) ? [...newThought.metadata.provenance] : [];
        if (metaId && !provenance.includes(metaId)) {
            provenance.push(metaId);
        }
        // Ensure metadata is immutable and timestamp updated
        const finalMetadata = Object.freeze({
            ...newThought.metadata,
            provenance: Object.freeze(provenance),
            last_updated_timestamp: newThought.metadata.last_updated_timestamp || Date.now() // Ensure timestamp
        });
        const thoughtToStore = new Thought(newThought.id, newThought.role, newThought.content, newThought.belief, newThought.status, finalMetadata);

        this.thoughts.set(thoughtToStore.id, thoughtToStore);
        logger.debug(`Updated Thought: ${thoughtToStore.id} -> Status: ${thoughtToStore.status}, Belief Score: ${thoughtToStore.belief.score().toFixed(3)}`);
        this.storeNotifier.notify('update', thoughtToStore);
        this.persistState(); // Trigger background persistence
        return true; // Indicate success
    }

    // Removes a Thought from the store by ID.
    removeThought(id) {
        const thought = this.thoughts.get(id);
        if (thought && this.thoughts.delete(id)) {
            logger.debug(`Removed Thought: ${id}`);
            this.storeNotifier.notify('remove', thought);
            this.persistState(); // Trigger background persistence
            return true; // Indicate success
        }
        return false; // Indicate failure: thought not found
    }

    // Retrieves all META_THOUGHTs.
    getMetaThoughts() {
        return Array.from(this.thoughts.values()).filter(t => t.role === Role.META_THOUGHT);
    }

    // Finds all Thoughts that have a specific parent ID.
    findThoughtsByParentId(parentId) {
        return Array.from(this.thoughts.values()).filter(t => t.metadata?.parent_id === parentId);
    }

    // Probabilistically samples a PENDING Thought based on belief score.
    samplePendingThought() {
        const pendingItems = [];
        for (const thought of this.thoughts.values()) {
            if (thought.status === Status.PENDING) {
                const score = thought.belief.score();
                // Only consider thoughts with a positive belief score for sampling
                if (score > 0) {
                    pendingItems.push([score, thought]); // [score, item] format for sampleWeighted
                }
            }
        }

        if (pendingItems.length === 0) return null; // No suitable pending thoughts

        const selectedThought = sampleWeighted(pendingItems);

        if (selectedThought) {
            logger.debug(`Sampled PENDING Thought: ${selectedThought.id} (Score: ${selectedThought.belief.score().toFixed(3)}) from ${pendingItems.length} candidates.`);
        } else if (pendingItems.length > 0) {
            // This case should ideally not happen if sampleWeighted is correct and scores are > 0
            logger.warn(`Weighted sampling returned null despite ${pendingItems.length} PENDING candidates.`);
            // As a fallback, maybe return the highest score one? Or just null. Stick with null for now.
        }
        return selectedThought;
    }

    // Returns a copy of all Thoughts currently in the store.
    getAllThoughts() { return Array.from(this.thoughts.values()); }

    // Counts Thoughts by their status.
    getStatusCounts() {
        const counts = { PENDING: 0, ACTIVE: 0, WAITING_CHILDREN: 0, DONE: 0, FAILED: 0 };
        for (const thought of this.thoughts.values()) {
            if (counts[thought.status] !== undefined) {
                counts[thought.status]++;
            }
        }
        return counts;
    }

    // Waits for any pending persistence operation to complete.
    async waitForPersistence() {
        if (this.savingPromise) {
            await this.savingPromise;
        }
        // If a save was queued, it should have been started by the finally() block,
        // so we might need to wait again if strict sequential consistency is needed.
        // However, for shutdown, waiting for the current one is usually enough.
        if (this.savingPromise) { // Check again in case a queued save started
            await this.savingPromise;
        }
    }
}

// Responsible for matching an active Thought against available META_THOUGHTs.
class Unifier {
    // Finds META_THOUGHTs whose target unifies with the active Thought's content and samples one.
    findAndSampleMatchingMeta(activeThought, allMetaThoughts) {
        logger.debug(`Unifying Thought ${activeThought.id} (Content: ${activeThought.content.toString()}) against ${allMetaThoughts.length} META_THOUGHTs...`);
        const matches = [];
        for (const metaThought of allMetaThoughts) {
            // Basic sanity check for meta-thought structure
            if (!(metaThought.content instanceof Structure) || metaThought.content.name !== 'meta_def' || metaThought.content.args.length !== 2) {
                logger.warn(`Skipping invalid META_THOUGHT ${metaThought.id}: Incorrect structure - ${metaThought.content.toString()}`);
                continue;
            }

            const targetPattern = metaThought.content.args[0];
            const substitutionMap = unify(targetPattern, activeThought.content); // Attempt unification

            if (substitutionMap !== null) { // Unification successful
                const score = metaThought.belief.score();
                logger.debug(`Potential match found: ${activeThought.id} vs META ${metaThought.id} (Target: ${targetPattern.toString()}, Score: ${score.toFixed(3)})`);
                if (score > 0) {
                    matches.push({ metaThought, substitutionMap, score });
                } else {
                    logger.debug(`Skipping match with META ${metaThought.id} due to zero score.`);
                }
            }
        }

        if (matches.length === 0) {
            logger.debug(`No matching META_THOUGHT found for ${activeThought.id}`);
            return { hasMatch: false };
        }

        // Sample one match based on the META_THOUGHT's belief score
        const selectedMatch = sampleWeighted(matches.map(m => [m.score, m]));

        if (selectedMatch) {
            logger.info(`Selected META ${selectedMatch.metaThought.id} (Score: ${selectedMatch.score.toFixed(3)}) for Thought ${activeThought.id}`);
            return { hasMatch: true, ...selectedMatch }; // Return the full match object
        } else {
            // Should be rare if scores > 0, but handle it
            logger.warn(`Weighted sampling failed to select a META match for ${activeThought.id} despite ${matches.length} potential matches.`);
            return { hasMatch: false };
        }
    }
}

// Executes the action defined by a matched META_THOUGHT.
class ActionExecutor {
    constructor(thoughtStore, thoughtGenerator, llmService) {
        this.thoughtStore = thoughtStore;
        this.thoughtGenerator = thoughtGenerator;
        this.llmService = llmService;
        // Map of primitive action names to their implementation methods
        this.primitiveActions = {
            'add_thought': this._addThought.bind(this),
            'set_status': this._setStatus.bind(this),
            'set_belief': this._setBelief.bind(this),
            'check_parent_completion': this._checkParentCompletion.bind(this),
            'generate_thoughts': this._generateThoughts.bind(this),
            'sequence': this._sequence.bind(this),
            'call_llm': this._callLlm.bind(this),
            'log_message': this._logMessage.bind(this),
            'no_op': this._noOp.bind(this),
            // 'reflect': this._reflect.bind(this), // Potential future action
        };
    }

    // Executes the resolved action for the active thought, guided by the meta-thought.
    async execute(activeThought, metaThought, substitutionMap) {
        // Set context for provenance tracking
        executionContext.currentMetaThoughtId = metaThought.id;
        let resolvedActionTerm = null; // For logging/error reporting
        try {
            logger.debug(`Executing action from META ${metaThought.id} for ACTIVE ${activeThought.id}`);
            const actionTemplate = metaThought.content.args[1]; // The action part of meta_def
            resolvedActionTerm = applySubstitution(actionTemplate, substitutionMap); // Apply substitutions

            logger.debug(`Resolved Action for ${activeThought.id}: ${resolvedActionTerm.toString()}`);
            if (!(resolvedActionTerm instanceof Structure)) {
                throw new Error(`Resolved action is not a Structure: ${resolvedActionTerm.toString()}`);
            }

            const actionName = resolvedActionTerm.name;
            const actionArgs = resolvedActionTerm.args;
            const actionMethod = this.primitiveActions[actionName];

            if (!actionMethod) {
                throw new Error(`Unknown primitive action name: "${actionName}"`);
            }

            // Execute the primitive action method
            // Pass the *current state* of the active thought in case it was modified by a previous step in a sequence
            const currentActiveThoughtState = this.thoughtStore.getThought(activeThought.id);
            if (!currentActiveThoughtState) {
                logger.warn(`Active thought ${activeThought.id} disappeared before executing primitive "${actionName}". Aborting action.`);
                return false; // Action cannot proceed
            }
            // Ensure the thought is still active before executing the *next* primitive (relevant mainly within sequence)
            if (currentActiveThoughtState.status !== Status.ACTIVE) {
                logger.info(`Active thought ${activeThought.id} status changed to ${currentActiveThoughtState.status} before executing primitive "${actionName}". Stopping action execution.`);
                return true; // Not a failure, just stopped due to state change
            }


            const success = await actionMethod(currentActiveThoughtState, actionArgs, substitutionMap);

            if (success) {
                logger.debug(`Primitive action "${actionName}" executed successfully for ${activeThought.id}.`);
            } else {
                // Specific action method should log details of why it failed
                logger.warn(`Primitive action "${actionName}" reported failure for ${activeThought.id}.`);
            }
            return success; // Return the success status of the primitive

        } catch (e) {
            const actionNameStr = resolvedActionTerm?.name || 'unknown action';
            logger.error(`Error executing action "${actionNameStr}" for Thought ${activeThought.id} (from META ${metaThought.id}):`, e);
            // Re-throw the error to be handled by the ExecuteLoop's failure mechanism
            throw new Error(`Action execution failed: ${e.message}`, { cause: e });
        } finally {
            // Clear context after execution finishes or fails
            executionContext.currentMetaThoughtId = null;
        }
    }

    // --- Primitive Action Implementations ---

    // Primitive: Creates a new thought.
    async _addThought(activeThought, args, substitutionMap) {
        // add_thought(RoleAtom, ContentTerm, OptionalBeliefTypeAtom)
        if (args.length < 2 || args.length > 3 || !(args[0] instanceof Atom)) throw new Error("Invalid args for add_thought: Expects RoleAtom, ContentTerm, [BeliefTypeAtom]");
        const roleName = args[0].name.toUpperCase();
        const role = Role[roleName];
        if (!role) throw new Error(`Invalid Role specified in add_thought: ${roleName}`);
        if (!(args[1] instanceof TermBase)) throw new Error("Invalid ContentTerm for add_thought");
        const content = args[1]; // Content is already substituted by execute() caller

        let belief = Belief.DEFAULT_UNCERTAIN; // Default if not specified
        if (args.length === 3) {
            if (!(args[2] instanceof Atom)) throw new Error("BeliefType argument for add_thought must be an Atom");
            const beliefTypeName = args[2].name.toUpperCase();
            const beliefMap = {
                [BeliefType.POSITIVE]: Belief.DEFAULT_POSITIVE,
                [BeliefType.UNCERTAIN]: Belief.DEFAULT_UNCERTAIN,
                [BeliefType.LOW_CONFIDENCE]: Belief.DEFAULT_LOW_CONFIDENCE,
                [BeliefType.NEGATIVE]: Belief.DEFAULT_NEGATIVE,
            };
            belief = beliefMap[beliefTypeName];
            if (!belief) {
                logger.warn(`Unknown BeliefType "${args[2].name}" in add_thought. Using DEFAULT_UNCERTAIN.`);
                belief = Belief.DEFAULT_UNCERTAIN;
            }
        }

        const now = Date.now();
        const newThought = new Thought(
            generateUUID(),
            role,
            content,
            belief,
            Status.PENDING, // New thoughts always start as PENDING
            {
                parent_id: activeThought.id, // Link to the thought that triggered its creation
                creation_timestamp: now,
                last_updated_timestamp: now,
                // Add provenance from the parent's execution context
                provenance: executionContext.currentMetaThoughtId ? Object.freeze([executionContext.currentMetaThoughtId]) : Object.freeze([]),
            }
        );
        this.thoughtStore.addThought(newThought);
        return true; // addThought always succeeds if args are valid
    }

    // Primitive: Sets the status of the active thought.
    async _setStatus(activeThought, args, substitutionMap) {
        // set_status(StatusAtom)
        if (args.length !== 1 || !(args[0] instanceof Atom)) throw new Error("Invalid args for set_status: Expects single StatusAtom");
        const statusName = args[0].name.toUpperCase();
        const newStatus = Status[statusName];
        if (!newStatus) throw new Error(`Invalid Status specified in set_status: ${statusName}`);

        // Prevent setting status back to ACTIVE or PENDING via this primitive
        // (ACTIVE is handled by the loop, PENDING is for retries/initial state)
        if (newStatus === Status.ACTIVE) {
            throw new Error("Cannot directly set status to ACTIVE using set_status primitive.");
        }
        if (newStatus === Status.PENDING) {
            throw new Error("Cannot directly set status to PENDING using set_status primitive (use failure handling for retries).");
        }

        // Get the current state again, as it might have changed (e.g., in a sequence)
        const currentThought = this.thoughtStore.getThought(activeThought.id);
        if (!currentThought) {
            logger.warn(`Thought ${activeThought.id} not found during set_status. Cannot update.`);
            return false; // Failed because thought disappeared
        }
        if (currentThought.status === newStatus) return true; // No change needed

        // Check if transition is valid (optional strictness)
        // const allowedTransitions = ValidStatusTransitions[currentThought.status] || [];
        // if (!allowedTransitions.includes(newStatus)) {
        //     throw new Error(`Invalid status transition from ${currentThought.status} to ${newStatus} for Thought ${activeThought.id}`);
        // }

        const updatedThought = currentThought.toBuilder().setStatus(newStatus).build();
        const success = this.thoughtStore.updateThought(currentThought, updatedThought); // Use atomic update
        if (!success) {
            logger.warn(`Failed to update Thought ${activeThought.id} status to ${newStatus} (likely conflict). Action considered failed.`);
        }
        return success;
    }

    // Primitive: Updates the belief of the active thought.
    async _setBelief(activeThought, args, substitutionMap) {
        // set_belief(SignalAtom) - SignalAtom is 'POSITIVE' or 'NEGATIVE'
        if (args.length !== 1 || !(args[0] instanceof Atom)) throw new Error("Invalid args for set_belief: Expects single SignalAtom ('POSITIVE' or 'NEGATIVE')");
        const signalTypeName = args[0].name.toUpperCase();

        let positiveSignal;
        if (signalTypeName === BeliefType.POSITIVE) positiveSignal = true;
        else if (signalTypeName === BeliefType.NEGATIVE) positiveSignal = false;
        else throw new Error(`Invalid belief signal type in set_belief: ${signalTypeName}. Use 'POSITIVE' or 'NEGATIVE'.`);

        const currentThought = this.thoughtStore.getThought(activeThought.id);
        if (!currentThought) {
            logger.warn(`Thought ${activeThought.id} not found during set_belief. Cannot update.`);
            return false;
        }

        const updatedBelief = currentThought.belief.update(positiveSignal);
        // Check if belief actually changed to avoid unnecessary updates
        if (updatedBelief.positive === currentThought.belief.positive && updatedBelief.negative === currentThought.belief.negative) {
            return true; // No change needed
        }

        // Update belief and also the timestamp as metadata changed implicitly
        const updatedThought = currentThought.toBuilder()
            .setBelief(updatedBelief)
            .setMetadata('last_updated_timestamp', Date.now()) // Explicit timestamp update
            .build();

        const success = this.thoughtStore.updateThought(currentThought, updatedThought);
        if (!success) {
            logger.warn(`Failed to update belief for Thought ${activeThought.id} (likely conflict). Action considered failed.`);
        }
        return success;
    }

    // Primitive: Checks if all children of the active thought's parent are terminal (DONE/FAILED) and updates parent.
    async _checkParentCompletion(activeThought, args, substitutionMap) {
        // check_parent_completion(StatusIfDoneAtom, StatusIfFailedAtom)
        if (args.length !== 2 || !(args[0] instanceof Atom) || !(args[1] instanceof Atom)) throw new Error("Invalid args for check_parent_completion: Expects StatusIfDoneAtom, StatusIfFailedAtom");

        const statusIfDoneName = args[0].name.toUpperCase();
        const statusIfFailedName = args[1].name.toUpperCase();
        const statusIfDone = Status[statusIfDoneName];
        const statusIfFailed = Status[statusIfFailedName];

        if (!statusIfDone || statusIfDone === Status.ACTIVE || statusIfDone === Status.PENDING) throw new Error(`Invalid StatusIfDone in check_parent_completion: ${statusIfDoneName}`);
        if (!statusIfFailed || statusIfFailed === Status.ACTIVE || statusIfFailed === Status.PENDING) throw new Error(`Invalid StatusIfFailed in check_parent_completion: ${statusIfFailedName}`);

        const parentId = activeThought.metadata?.parent_id;
        if (!parentId) {
            logger.debug(`Thought ${activeThought.id} has no parent_id. check_parent_completion does nothing.`);
            return true; // No parent to check, considered success
        }

        const parentThought = this.thoughtStore.getThought(parentId);
        if (!parentThought) {
            logger.warn(`Parent Thought ${parentId} (of ${activeThought.id}) not found during check_parent_completion.`);
            return true; // Cannot find parent, maybe deleted? Consider success.
        }

        // Only proceed if the parent is actually waiting
        if (parentThought.status !== Status.WAITING_CHILDREN) {
            logger.debug(`Parent ${parentId} is not in WAITING_CHILDREN status (${parentThought.status}). check_parent_completion does nothing.`);
            return true;
        }

        const children = this.thoughtStore.findThoughtsByParentId(parentId);
        if (children.length === 0) {
            // This is an odd state - parent WAITING but no children found. Mark parent FAILED.
            logger.warn(`Parent ${parentId} is WAITING_CHILDREN but no children were found. Setting parent to FAILED (${statusIfFailedName}).`);
            const failedParent = parentThought.toBuilder()
                .setStatus(statusIfFailed)
                .setBelief(parentThought.belief.update(false)) // Update belief negatively
                .setMetadata('error_info', 'Parent was WAITING_CHILDREN, but no child thoughts found.')
                .build();
            return this.thoughtStore.updateThought(parentThought, failedParent); // Return success of update attempt
        }

        // Check if ANY child is still non-terminal (PENDING, ACTIVE, WAITING_CHILDREN)
        const anyNonTerminalChild = children.some(child =>
            child.status === Status.PENDING || child.status === Status.ACTIVE || child.status === Status.WAITING_CHILDREN
        );

        if (anyNonTerminalChild) {
            logger.debug(`Parent ${parentId} still has non-terminal children. check_parent_completion waits.`);
            return true; // Parent continues waiting, action is "successful" in not failing
        }

        // If all children are terminal (DONE or FAILED)
        const anyChildFailed = children.some(child => child.status === Status.FAILED);
        const finalParentStatus = anyChildFailed ? statusIfFailed : statusIfDone;
        const beliefSignal = !anyChildFailed; // Positive signal if no children failed

        logger.info(`All children of Parent ${parentId} are terminal. ${anyChildFailed ? 'Some children FAILED.' : 'All children DONE.'} Updating parent status to ${finalParentStatus}.`);

        const updatedParent = parentThought.toBuilder()
            .setStatus(finalParentStatus)
            .setBelief(parentThought.belief.update(beliefSignal)) // Update belief based on outcome
            .setMetadata('error_info', null) // Clear previous errors if successful completion
            .build();

        const success = this.thoughtStore.updateThought(parentThought, updatedParent);
        if (!success) {
            logger.warn(`Failed to update parent ${parentId} status during check_parent_completion (likely conflict).`);
        }
        return success;
    }

    // Primitive: Generates new thoughts using the ThoughtGenerator service.
    async _generateThoughts(activeThought, args, substitutionMap) {
        // generate_thoughts(PromptTerm)
        if (args.length !== 1 || !(args[0] instanceof TermBase)) throw new Error("Invalid args for generate_thoughts: Expects single PromptTerm");
        const promptTerm = args[0];
        const promptInput = promptTerm.toString(); // Convert substituted term to string prompt

        logger.debug(`Calling ThoughtGenerator for parent ${activeThought.id}. Prompt (first 150 chars): ${promptInput.substring(0, 150)}...`);
        try {
            // Delegate generation to the ThoughtGenerator service
            const newThoughts = await this.thoughtGenerator.generate(promptInput, activeThought.id);

            if (!Array.isArray(newThoughts)) {
                logger.error("ThoughtGenerator did not return an array.");
                return false; // Generation failed
            }

            if (newThoughts.length > 0) {
                logger.info(`ThoughtGenerator created ${newThoughts.length} new thoughts for parent ${activeThought.id}.`);
                let addedCount = 0;
                newThoughts.forEach(thought => {
                    if (thought instanceof Thought) {
                        if(this.thoughtStore.addThought(thought)) addedCount++;
                    } else {
                        logger.warn("ThoughtGenerator returned a non-Thought item:", thought);
                    }
                });
                logger.info(`Successfully added ${addedCount} of ${newThoughts.length} generated thoughts.`);
            } else {
                logger.info(`ThoughtGenerator produced no thoughts for prompt: "${promptInput.substring(0, 100)}..."`);
            }
            return true; // Generation process succeeded (even if no thoughts were made)
        } catch (e) {
            logger.error(`Error during generate_thoughts primitive for ${activeThought.id}: ${e.message}`);
            return false; // Generation failed due to error
        }
    }

    // Primitive: Executes a sequence of actions.
    async _sequence(activeThought, args, substitutionMap) {
        // sequence(ListOfActionStructs)
        if (args.length !== 1 || !(args[0] instanceof ListTerm)) throw new Error("Invalid args for sequence: Expects single ListTerm containing action Structures");
        const actionList = args[0].elements;

        logger.debug(`Executing sequence of ${actionList.length} actions for ${activeThought.id}`);
        for (let i = 0; i < actionList.length; i++) {
            const actionTerm = actionList[i];
            if (!(actionTerm instanceof Structure)) {
                throw new Error(`Item ${i} in sequence is not an action Structure: ${actionTerm.toString()}`);
            }

            const actionName = actionTerm.name;
            const actionArgs = actionTerm.args; // Args are already substituted from the main execute call
            const actionMethod = this.primitiveActions[actionName];

            if (!actionMethod) {
                throw new Error(`Unknown action "${actionName}" found in sequence (item ${i + 1}/${actionList.length})`);
            }

            // CRITICAL: Check the *current* state of the active thought *before* executing each step in the sequence.
            const currentActiveState = this.thoughtStore.getThought(activeThought.id);
            if (!currentActiveState) {
                logger.warn(`Active thought ${activeThought.id} disappeared during sequence execution (before step ${i + 1}: ${actionName}). Aborting sequence.`);
                return false; // Sequence fails because thought is gone
            }
            // If the thought's status changed (e.g., set to DONE/FAILED by a previous step), stop the sequence.
            if (currentActiveState.status !== Status.ACTIVE) {
                logger.info(`Active thought ${activeThought.id} status became ${currentActiveState.status} during sequence (before step ${i + 1}: ${actionName}). Stopping sequence execution.`);
                // This is not necessarily a failure of the sequence itself, but a natural stop.
                return true;
            }

            logger.debug(`Sequence step ${i + 1}/${actionList.length}: Executing "${actionName}" for ${activeThought.id}`);
            try {
                // Execute the action step, passing the *current* state
                const success = await actionMethod(currentActiveState, actionArgs, substitutionMap);
                if (!success) {
                    // If a step reports failure, the whole sequence fails.
                    logger.warn(`Action "${actionName}" (step ${i + 1}) failed within sequence for ${activeThought.id}. Aborting sequence.`);
                    return false; // Sequence fails
                }
            } catch (e) {
                // If a step throws an error, the whole sequence fails and propagates the error.
                logger.error(`Error executing action "${actionName}" (step ${i + 1}) within sequence for ${activeThought.id}: ${e.message}. Aborting sequence.`);
                throw new Error(`Sequence failed at step ${i+1} (${actionName}): ${e.message}`, {cause: e}); // Propagate
            }
        }

        logger.debug(`Sequence executed successfully for ${activeThought.id}`);
        return true; // All steps succeeded
    }

    // Primitive: Calls the LLM service and adds the response as a new thought.
    async _callLlm(activeThought, args, substitutionMap) {
        // call_llm(PromptTerm, ResultRoleAtom)
        if (args.length !== 2 || !(args[0] instanceof TermBase) || !(args[1] instanceof Atom)) throw new Error("Invalid args for call_llm: Expects PromptTerm, ResultRoleAtom");

        const promptTerm = args[0];
        const resultRoleName = args[1].name.toUpperCase();
        const resultRole = Role[resultRoleName];
        if (!resultRole) throw new Error(`Invalid result Role specified in call_llm: ${resultRoleName}`);

        // Usually expect OUTCOME, but allow others, maybe with a warning.
        if (resultRole !== Role.OUTCOME) {
            logger.warn(`call_llm invoked with unusual result Role: ${resultRoleName}. Expected OUTCOME.`);
        }

        const promptString = promptTerm.toString(); // Convert substituted term to string prompt
        logger.debug(`Calling LLM for ${activeThought.id} (result Role: ${resultRoleName}). Prompt (first 100 chars): "${promptString.substring(0, 100)}..."`);

        try {
            const llmResponse = await this.llmService.invoke(promptString);
            if (!llmResponse?.trim()) {
                logger.warn(`LLM call for ${activeThought.id} returned empty or whitespace-only response. No result thought created.`);
                // Consider if this should be true (LLM call itself succeeded) or false (no useful result)
                // Let's say true, as the call didn't error, just returned nothing useful.
                return true;
            }

            // Create the result thought
            const resultContent = S("llm_response", A(llmResponse.trim())); // Structure for the response
            const now = Date.now();
            const resultThought = new Thought(
                generateUUID(),
                resultRole,
                resultContent,
                Belief.DEFAULT_POSITIVE, // Assume LLM output is initially positive belief
                Status.PENDING,
                {
                    parent_id: activeThought.id,
                    creation_timestamp: now,
                    last_updated_timestamp: now,
                    prompt: promptString.substring(0, 500), // Store truncated prompt for context
                    provenance: executionContext.currentMetaThoughtId ? Object.freeze([executionContext.currentMetaThoughtId]) : Object.freeze([]),
                }
            );
            this.thoughtStore.addThought(resultThought);
            logger.info(`LLM call successful for ${activeThought.id}. Created result Thought ${resultThought.id} with Role ${resultRoleName}.`);
            return true; // LLM call and thought creation successful

        } catch (e) {
            // LLM service invoke likely threw an error
            logger.error(`call_llm primitive failed for ${activeThought.id}: ${e.message}`);
            return false; // Action failed
        }
    }

    // Primitive: Logs a message using the engine's logger.
    async _logMessage(activeThought, args, substitutionMap) {
        // log_message(LevelAtom, MessageTerm) - LevelAtom is 'debug', 'info', 'warn', 'error'
        if (args.length !== 2 || !(args[0] instanceof Atom) || !(args[1] instanceof TermBase)) throw new Error("Invalid args for log_message: Expects LevelAtom, MessageTerm");

        const level = args[0].name.toLowerCase();
        const messageTerm = args[1];
        const message = messageTerm.toString(); // Convert substituted term to string

        const logFunction = logger[level];
        if (typeof logFunction === 'function') {
            logFunction(`[Action Log | T:${activeThought.id}]: ${message}`);
        } else {
            logger.warn(`Invalid log level "${level}" specified in log_message. Defaulting to INFO.`);
            logger.info(`[Action Log | T:${activeThought.id}]: ${message}`);
        }
        return true; // Logging always succeeds
    }

    // Primitive: Does nothing. Useful as a placeholder or final step.
    async _noOp(activeThought, args, substitutionMap) {
        if (args.length !== 0) throw new Error("no_op primitive expects exactly 0 arguments.");
        logger.debug(`Executing no_op for Thought ${activeThought.id}.`);
        return true; // No-op always succeeds
    }
}

// Generates Thoughts, often by breaking down tasks using an LLM.
class ThoughtGenerator {
    constructor(llmService) {
        this.llmService = llmService;
    }

    // Generates sub-tasks/strategies based on a prompt, returning an array of new Thoughts.
    async generate(promptInput, parentId) {
        // Prompt designed to elicit a JSON list of strings representing sub-tasks.
        const fullPrompt = `Given the high-level goal or request: "${promptInput}"

Break this down into a concise list of specific, actionable sub-tasks or strategies required to achieve it.

Output ONLY a valid JSON list of strings, where each string is a clear and distinct sub-task description.
Example Format: ["Sub-task 1 description", "Sub-task 2 description", "Sub-task 3 description"]

If the input is already a simple, atomic task or cannot be meaningfully broken down further, return an empty JSON list: [].
Do not include any other text, explanation, apologies, or formatting outside the JSON list itself.`;

        let llmResponseText;
        try {
            llmResponseText = await this.llmService.invoke(fullPrompt);
        } catch (e) {
            // Log the specific error from the LLM service
            logger.error(`LLM call failed during Thought generation for parent ${parentId}: ${e.message}`);
            throw new Error(`LLM service failed during thought generation: ${e.message}`, { cause: e });
        }

        let taskStrings = [];
        try {
            // Attempt to parse the LLM response as JSON
            // First, try to extract JSON block if markdown formatting is present
            const jsonMatch = llmResponseText.match(/```(?:json)?\s*([\s\S]*?)\s*```/);
            const textToParse = jsonMatch ? jsonMatch[1] : llmResponseText;

            taskStrings = JSON.parse(textToParse.trim());

            if (!Array.isArray(taskStrings)) {
                throw new Error("LLM response parsed but is not a JSON array.");
            }
            // Validate that all elements are strings
            if (!taskStrings.every(task => typeof task === 'string')) {
                throw new Error("LLM response array contains non-string elements.");
            }
            // Filter out empty strings after trimming
            taskStrings = taskStrings.map(t => t.trim()).filter(t => t.length > 0);

        } catch (e) {
            logger.warn(`Failed to parse LLM response as JSON list for parent ${parentId}: ${e.message}. Response (raw): "${llmResponseText}"`);
            // Depending on strictness, either return [] or throw error. Throwing is safer.
            throw new Error(`LLM response parsing failed: ${e.message}. Check LLM output format.`, { cause: e });
            // return []; // Option: return empty list if parsing fails
        }

        if (taskStrings.length === 0) {
            logger.info(`LLM generation resulted in no sub-tasks for parent ${parentId} (Prompt: "${promptInput.substring(0,100)}...")`);
            return []; // Return empty array if no tasks generated or parsed
        }

        // Convert valid task strings into new STRATEGY Thoughts
        const now = Date.now();
        return taskStrings.map(taskStr => {
            // Content structure: task(Atom("description"))
            const taskContent = S("task", A(taskStr));
            return new Thought(
                generateUUID(),
                Role.STRATEGY, // Generated thoughts are typically strategies
                taskContent,
                Belief.DEFAULT_POSITIVE, // Start with positive belief
                Status.PENDING,
                {
                    parent_id: parentId,
                    creation_timestamp: now,
                    last_updated_timestamp: now,
                    source_prompt: promptInput.substring(0, 500), // Store source for context
                    provenance: executionContext.currentMetaThoughtId ? Object.freeze([executionContext.currentMetaThoughtId]) : Object.freeze([]),
                }
            );
        });
    }
}


// === Term Helper Functions === (for concise definition)
const S = (name, ...args) => new Structure(name, args);
const A = (name) => new Atom(name);
const V = (name) => new Variable(name);
const N = (value) => new NumberTerm(value);
const L = (...elements) => new ListTerm(elements);


// === Bootstrap META_THOUGHTs ===
// Define the initial set of meta-thoughts that drive the engine's basic behavior.
const bootstrapMetaThoughtTemplates = [
    { // MT-NOTE-TO-GOAL: Converts initial user input (NOTE) into a GOAL.
        content: S("meta_def",
            // Target: Matches a NOTE with user_input structure
            S("user_input", V("Content")), // Use Variable V("Content") to capture input
            // Action: Sequence of logging, adding a GOAL, and marking NOTE as DONE.
            S("sequence", L(
                S("log_message", A("info"), S("concat", L(A("Converting NOTE to GOAL: "), V("Content")))), // Log with captured content
                S("add_thought", A(Role.GOAL), S("goal_content", V("Content")), A(BeliefType.POSITIVE)), // Create GOAL using captured content
                S("set_status", A(Status.DONE)) // Mark the original NOTE as DONE
            ))
        ),
        belief: Belief.DEFAULT_POSITIVE, // High confidence in this rule
        metadata: { description: "Convert user_input NOTE to GOAL", is_bootstrap: true, applicable_role: Role.NOTE }
    },
    { // MT-GOAL-DECOMPOSE: Breaks down a GOAL into sub-tasks (STRATEGIES).
        content: S("meta_def",
            // Target: Matches a GOAL with goal_content structure
            S("goal_content", V("GoalDesc")), // Capture the goal description
            // Action: Sequence of logging, generating thoughts, setting status to WAITING.
            S("sequence", L(
                S("log_message", A("info"), S("concat", L(A("Decomposing GOAL: "), V("GoalDesc")))),
                S("generate_thoughts", V("GoalDesc")), // Use captured description as prompt for generator
                S("set_status", A(Status.WAITING_CHILDREN)) // Set GOAL to wait for generated STRATEGIES
            ))
        ),
        // Slightly lower confidence than NOTE->GOAL, decomposition might not always be needed/possible.
        belief: new Belief(...config.defaultBeliefUncertain), // Let's start uncertain and let it adapt
        metadata: { description: "Decompose GOAL into STRATEGIES via ThoughtGenerator", is_bootstrap: true, applicable_role: Role.GOAL }
    },
    { // MT-STRATEGY-EXECUTE: Executes a STRATEGY (task) by calling the LLM.
        content: S("meta_def",
            // Target: Matches a STRATEGY with task structure
            S("task", V("TaskDesc")), // Capture the task description
            // Action: Sequence of logging, calling LLM, setting status to DONE.
            S("sequence", L(
                S("log_message", A("info"), S("concat", L(A("Executing STRATEGY: "), V("TaskDesc")))),
                // Call LLM with a prompt constructed from the task description. Result is an OUTCOME.
                S("call_llm", S("execute_task_prompt", V("TaskDesc")), A(Role.OUTCOME)),
                S("set_status", A(Status.DONE)) // Mark STRATEGY as DONE after initiating LLM call
            ))
        ),
        belief: Belief.DEFAULT_POSITIVE, // High confidence in executing defined tasks
        metadata: { description: "Execute task STRATEGY via LLM call, creating an OUTCOME", is_bootstrap: true, applicable_role: Role.STRATEGY }
    },
    { // MT-OUTCOME-PROCESS: Processes the result (OUTCOME) from an LLM call.
        content: S("meta_def",
            // Target: Matches an OUTCOME with llm_response structure
            S("llm_response", V("LLMResult")), // Capture the LLM result string
            // Action: Sequence of logging, updating belief, setting status DONE, checking parent.
            S("sequence", L(
                // Log processing (debug level)
                S("log_message", A("debug"), S("concat", L(A("Processing OUTCOME (first 50): "), S("substring", L(V("LLMResult"), N(0), N(50)))))),
                S("set_belief", A(BeliefType.POSITIVE)), // Confirm positive belief in the outcome itself
                S("set_status", A(Status.DONE)), // Mark the OUTCOME as DONE
                // Check if the parent (likely STRATEGY or GOAL) can be marked DONE/FAILED
                S("check_parent_completion", A(Status.DONE), A(Status.FAILED))
            ))
        ),
        belief: Belief.DEFAULT_POSITIVE, // High confidence in processing outcomes
        metadata: { description: "Process llm_response OUTCOME, mark DONE, check parent completion", is_bootstrap: true, applicable_role: Role.OUTCOME }
    },
    // Add a meta-thought for META_THOUGHTs themselves to prevent them failing immediately
    { // MT-META-NOOP: Handles processing of META_THOUGHTs themselves (e.g., after bootstrap)
        content: S("meta_def",
            S("meta_def", V("Target"), V("Action")), // Target: Matches any META_THOUGHT definition
            S("sequence", L(
                S("log_message", A("debug"), A("Processing META_THOUGHT definition. Setting DONE.")),
                S("set_status", A(Status.DONE)) // Mark the META_THOUGHT as DONE (it's loaded/defined)
            ))
        ),
        belief: new Belief(0.1, 0.0), // Very low positive belief, just enough to be selected if nothing else matches
        metadata: { description: "Handle processing of META_THOUGHTs themselves by marking them DONE.", is_bootstrap: true, applicable_role: Role.META_THOUGHT }
    }
].filter(t => t.content instanceof Structure && t.content.name === 'meta_def'); // Basic validation

// === Garbage Collection ===

// Removes old, terminal (DONE/FAILED) thoughts from the store.
function garbageCollect(thoughtStore) {
    logger.info("Running Garbage Collection cycle...");
    const startTime = Date.now();
    const expirationTimestamp = startTime - config.gcThresholdMillis; // Threshold for removal
    let removedCount = 0;
    let examinedCount = 0;

    const allThoughts = thoughtStore.getAllThoughts(); // Get a snapshot
    const thoughtsToRemove = [];

    for (const thought of allThoughts) {
        examinedCount++;
        const isTerminal = thought.status === Status.DONE || thought.status === Status.FAILED;
        // Use last updated timestamp primarily, fallback to creation
        const thoughtTimestamp = thought.metadata?.last_updated_timestamp ?? thought.metadata?.creation_timestamp ?? 0;
        const isOld = thoughtTimestamp < expirationTimestamp;

        if (isTerminal && isOld) {
            // Optional: Add check for active children if needed (more complex)
            // const children = thoughtStore.findThoughtsByParentId(thought.id);
            // const hasActiveChildren = children.some(c => c.status === Status.ACTIVE || c.status === Status.PENDING || c.status === Status.WAITING_CHILDREN);
            // if (hasActiveChildren) {
            //     logger.debug(`GC: Skipping old terminal thought ${thought.id} because it has active children.`);
            //     continue;
            // }
            thoughtsToRemove.push(thought.id); // Mark for removal
        }
    }

    // Perform removal outside the loop
    for (const idToRemove of thoughtsToRemove) {
        if (thoughtStore.removeThought(idToRemove)) {
            removedCount++;
        }
    }

    const duration = Date.now() - startTime;
    logger.info(`Garbage Collection complete. Examined: ${examinedCount}, Removed: ${removedCount} old terminal thoughts in ${duration}ms.`);
}

// === ExecuteLoop ===

// Main control loop orchestrating the thought processing cycle.
class ExecuteLoop {
    constructor(thoughtStore, unifier, actionExecutor) {
        this.thoughtStore = thoughtStore;
        this.unifier = unifier;
        this.actionExecutor = actionExecutor;
        this.isRunning = false;
        this.timeoutId = null; // For scheduling next cycle
        this.activeThoughtProcessingPromise = null; // Tracks the promise of the currently running cycle
        this.shutdownSignalReceived = false; // Flag for graceful shutdown
    }

    // Starts the execution loop.
    start() {
        if (this.isRunning) return;
        this.isRunning = true;
        this.shutdownSignalReceived = false;
        logger.info("Starting Coglog ExecuteLoop...");
        this.scheduleNextCycle(0); // Start immediately
    }

    // Signals the loop to stop gracefully.
    async stop() {
        if (!this.isRunning || this.shutdownSignalReceived) return; // Already stopping or stopped
        this.shutdownSignalReceived = true; // Signal that shutdown is in progress
        logger.info("ExecuteLoop stopping process initiated...");

        if (this.timeoutId) {
            clearTimeout(this.timeoutId); // Cancel any scheduled next cycle
            this.timeoutId = null;
        }

        // Wait for the currently active cycle (if any) to complete
        if (this.activeThoughtProcessingPromise) {
            logger.info("Waiting for the current processing cycle to complete...");
            try {
                await this.activeThoughtProcessingPromise;
                logger.info("Current processing cycle finished.");
            } catch (e) {
                // Log error from the final cycle but continue shutdown
                logger.warn("Error occurred during the final processing cycle:", e?.message);
            }
        }

        this.isRunning = false; // Mark as fully stopped
        this.activeThoughtProcessingPromise = null;
        logger.info("ExecuteLoop stopped successfully.");
    }

    // Schedules the next runCycle execution.
    scheduleNextCycle(delay = config.pollIntervalMillis) {
        if (!this.isRunning || this.shutdownSignalReceived) return; // Don't schedule if stopping/stopped

        if (this.timeoutId) clearTimeout(this.timeoutId); // Clear previous timer just in case

        this.timeoutId = setTimeout(() => {
            // Use setImmediate or process.nextTick for better event loop handling than direct async call in setTimeout
            setImmediate(async () => {
                if (!this.isRunning || this.shutdownSignalReceived) return; // Check again before running

                // Run the cycle and store its promise
                this.activeThoughtProcessingPromise = this.runCycle();
                try {
                    await this.activeThoughtProcessingPromise;
                } catch (e) {
                    // Should not happen if runCycle catches its errors, but log just in case
                    logger.error("!!! Unhandled error escaped from runCycle:", e);
                } finally {
                    this.activeThoughtProcessingPromise = null; // Clear the promise
                    if (this.isRunning && !this.shutdownSignalReceived) {
                        // Decide next poll interval: immediate if pending thoughts exist, otherwise standard poll interval
                        const counts = this.thoughtStore.getStatusCounts();
                        const nextDelay = counts.PENDING > 0 ? 0 : config.pollIntervalMillis;
                        this.scheduleNextCycle(nextDelay); // Schedule the *next* cycle
                    }
                }
            });
        }, delay);
    }

    // Executes a single processing cycle: sample, activate, unify, execute.
    async runCycle() {
        let activeThoughtInitialState = null; // Keep track of the thought being processed in this cycle
        try {
            // 1. Sample a PENDING thought
            const pendingThought = this.thoughtStore.samplePendingThought();
            if (!pendingThought) {
                logger.debug("No PENDING thoughts available for processing.");
                return; // Nothing to do this cycle
            }

            // 2. Attempt to activate the thought (atomic update)
            const activatedThoughtBuilder = pendingThought.toBuilder()
                .setStatus(Status.ACTIVE)
                .setMetadata('last_activated_timestamp', Date.now()); // Track activation time maybe?
            const activatedThought = activatedThoughtBuilder.build();

            // Use atomic update: ensure the thought is still the same PENDING one we sampled
            if (!this.thoughtStore.updateThought(pendingThought, activatedThought)) {
                logger.warn(`Failed to activate Thought ${pendingThought.id} (Status: ${pendingThought.status}). Likely changed state or conflict. Rescheduling cycle.`);
                // Don't reschedule here, the main loop scheduling handles it.
                return; // Skip this cycle, will retry sampling next time
            }
            activeThoughtInitialState = activatedThought; // Successfully activated
            const processingThought = activatedThought; // Use this reference for the rest of the cycle

            logger.info(`Processing Activated Thought: ${processingThought.id} (Role: ${processingThought.role}, Belief: ${processingThought.belief.score().toFixed(3)}, Content: ${processingThought.content.toString().substring(0, 80)}...)`);

            // 3. Execute with Timeout
            let timeoutHandle = null;
            const timeoutPromise = new Promise((_, reject) => {
                timeoutHandle = setTimeout(() => {
                    const timeoutError = new Error(`Execution timeout exceeded (> ${config.maxActiveDurationMillis}ms) for Thought ${processingThought.id}`);
                    timeoutError.name = 'TimeoutError';
                    reject(timeoutError);
                }, config.maxActiveDurationMillis);
            });

            try {
                // Race execution logic against the timeout
                await Promise.race([
                    (async () => {
                        // 4. Find matching META_THOUGHT
                        const metaThoughts = this.thoughtStore.getMetaThoughts();
                        const unificationResult = this.unifier.findAndSampleMatchingMeta(processingThought, metaThoughts);

                        if (unificationResult.hasMatch) {
                            const { matchedMetaThought, substitutionMap } = unificationResult;
                            // 5. Execute action defined by the META_THOUGHT
                            const actionSuccess = await this.actionExecutor.execute(processingThought, matchedMetaThought, substitutionMap);

                            // 6. Post-execution check (only if action didn't throw)
                            const finalState = this.thoughtStore.getThought(processingThought.id);
                            if (!finalState) {
                                logger.warn(`Thought ${processingThought.id} disappeared after action execution.`);
                                // Nothing more to do if it's gone
                            } else if (actionSuccess && finalState.status === Status.ACTIVE) {
                                // Action reported success but didn't change status from ACTIVE. This is usually an error in the META_THOUGHT logic.
                                logger.warn(`Action for Thought ${processingThought.id} succeeded but left status ACTIVE. Marking as FAILED.`);
                                this.handleFailure(finalState, "Action completed successfully but did not set a terminal status.", 0); // No retries for logic error
                            } else if (!actionSuccess && finalState.status === Status.ACTIVE) {
                                // Action reported failure, and status is still ACTIVE. Mark FAILED with retries.
                                logger.warn(`Action for Thought ${processingThought.id} reported failure. Marking as FAILED/PENDING based on retries.`);
                                this.handleFailure(finalState, "Action execution reported failure.", this.calculateRemainingRetries(finalState));
                            }
                            // If action succeeded and status is no longer ACTIVE, or action failed and status is no longer ACTIVE,
                            // the action itself handled the status change correctly.
                        } else {
                            // No matching META_THOUGHT found
                            logger.warn(`No matching META_THOUGHT found for ${processingThought.id}. Marking as FAILED.`);
                            this.handleFailure(processingThought, "No matching META_THOUGHT found.", 0); // No retries if no rule matches
                        }
                    })(), // Immediately invoke the async execution logic
                    timeoutPromise // Race against the timeout
                ]);
            } catch (error) {
                // Catch errors from execution logic OR the timeout
                const stateAfterError = this.thoughtStore.getThought(processingThought.id);
                if (!stateAfterError) {
                    logger.warn(`Thought ${processingThought.id} disappeared after encountering error: ${error.message}`);
                    return; // Nothing to handle if thought is gone
                }

                // Only handle failure if the thought is still ACTIVE
                if (stateAfterError.status === Status.ACTIVE) {
                    if (error.name === 'TimeoutError') {
                        logger.warn(`Timeout processing Thought ${processingThought.id}.`);
                        this.handleFailure(stateAfterError, "Timeout during action execution.", this.calculateRemainingRetries(stateAfterError));
                    } else {
                        // Handle errors from unification or action execution
                        logger.error(`Error processing Thought ${processingThought.id}: ${error.message}`, error.cause || error);
                        this.handleFailure(stateAfterError, `Execution Error: ${error.message}`, this.calculateRemainingRetries(stateAfterError));
                    }
                } else {
                    // Error occurred, but status already changed (e.g., by a partially successful sequence before error)
                    logger.warn(`Error occurred for ${processingThought.id} but status was already ${stateAfterError.status}. Error: ${error.message}`);
                    // Do not call handleFailure again if status is already terminal
                }
            } finally {
                // Always clear the timeout handle
                if (timeoutHandle) clearTimeout(timeoutHandle);
            }

        } catch (cycleError) {
            // Catch unexpected critical errors in the cycle logic itself
            logger.error("!!! Critical error in ExecuteLoop runCycle logic:", cycleError);
            // If we know which thought was being processed, try to mark it FAILED
            if (activeThoughtInitialState) {
                const currentState = this.thoughtStore.getThought(activeThoughtInitialState.id);
                // Mark FAILED only if it's still ACTIVE or somehow reverted to PENDING
                if (currentState && (currentState.status === Status.ACTIVE || currentState.status === Status.PENDING)) {
                    logger.error(`Attempting to mark Thought ${currentState.id} as FAILED due to unhandled cycle error.`);
                    this.handleFailure(currentState, `Unhandled cycle error: ${cycleError.message}`, 0); // No retries for critical failure
                }
            }
            // Rethrow critical errors to potentially halt the application if needed? Or just log and continue?
            // For robustness, maybe just log and let the next cycle attempt run.
            // throw cycleError; // Option: rethrow to halt
        }
    }

    // Handles failures during thought processing, updating status and belief.
    handleFailure(failedThought, errorMessage, retriesLeft) {
        // Get the absolute latest state of the thought before updating
        const currentThought = this.thoughtStore.getThought(failedThought.id);
        if (!currentThought) {
            logger.warn(`Thought ${failedThought.id} not found during failure handling for error: "${errorMessage}".`);
            return;
        }

        // Only apply failure handling if the thought is currently ACTIVE.
        // If it's already DONE/FAILED/WAITING, something else handled it.
        if (currentThought.status !== Status.ACTIVE) {
            logger.debug(`Thought ${currentThought.id} status is already ${currentThought.status}. Skipping failure handling for error: "${errorMessage}"`);
            return;
        }

        const retryCount = (currentThought.metadata?.retry_count || 0) + 1;
        const newStatus = retriesLeft > 0 ? Status.PENDING : Status.FAILED; // Set PENDING for retry, FAILED if out of retries
        const updatedBelief = currentThought.belief.update(false); // Apply negative belief signal

        logger.warn(`Handling failure for Thought ${currentThought.id}: "${errorMessage.substring(0, 200)}...". Retries left: ${retriesLeft}. Setting status to ${newStatus}.`);

        const updatedThought = currentThought.toBuilder()
            .setStatus(newStatus)
            .setBelief(updatedBelief)
            .setMetadata('error_info', errorMessage.substring(0, 1000)) // Store error message (truncated)
            .setMetadata('retry_count', retryCount)
            .build();

        // Use atomic update
        if (!this.thoughtStore.updateThought(currentThought, updatedThought)) {
            // This is serious - failed to even mark the thought as failed/pending!
            logger.error(`CRITICAL: Failed to update Thought ${currentThought.id} status to ${newStatus} during failure handling (conflict?). State may be inconsistent.`);
            // Potential fallback: attempt to remove the thought? Or just log heavily.
        }
    }

    // Calculates remaining retries based on current count and max config.
    calculateRemainingRetries(thought) {
        const currentRetries = thought.metadata?.retry_count || 0;
        // Ensure retries don't somehow exceed maxRetries due to external modification or race conditions
        const effectiveRetries = Math.min(currentRetries, config.maxRetries);
        return Math.max(0, config.maxRetries - effectiveRetries);
    }
}

// === Initialization and Main Execution ===

// Main asynchronous function to set up and run the engine.
async function main() {
    logger.info(`--- Coglog ${"2.6.1"} Initializing ---`);
    logger.info(`Config: ${JSON.stringify({logLevel: config.logLevel, model: config.llmModelName, maxRetries: config.maxRetries, pollInterval: config.pollIntervalMillis, timeout: config.maxActiveDurationMillis})}`);

    // 1. Initialize Services
    const persistenceService = new PersistenceService(config.persistenceFile);
    const storeNotifier = new StoreNotifier(); // Simple console notifier
    const llmService = new LlmService(); // Handles Ollama connection
    const textParser = new TextParserService(); // Parses initial input

    // 2. Initialize Core Components
    const thoughtStore = new ThoughtStore(persistenceService, storeNotifier);
    const thoughtGenerator = new ThoughtGenerator(llmService); // Uses LLM for generation
    const unifier = new Unifier();
    const actionExecutor = new ActionExecutor(thoughtStore, thoughtGenerator, llmService);
    const executeLoop = new ExecuteLoop(thoughtStore, unifier, actionExecutor);

    // 3. Load State or Bootstrap
    await thoughtStore.loadState();
    let initialThoughtCount = thoughtStore.thoughts.size;

    if (initialThoughtCount === 0) {
        logger.info("No existing state found. Loading bootstrap META_THOUGHTs...");
        let bootstrapAdded = 0;
        bootstrapMetaThoughtTemplates.forEach(template => {
            // Create META_THOUGHT from template
            const metaThought = new Thought(
                generateUUID(),
                Role.META_THOUGHT,
                template.content, // Assumes content is valid TermBase from helpers
                template.belief, // Assumes belief is valid Belief instance
                Status.PENDING, // Start as PENDING to be processed/marked DONE
                { ...(template.metadata || {}), creation_timestamp: Date.now(), last_updated_timestamp: Date.now() }
            );
            if (thoughtStore.addThought(metaThought)) bootstrapAdded++;
        });
        logger.info(`Loaded ${bootstrapAdded} bootstrap META_THOUGHTs.`);

        // Add a sample initial NOTE to kick things off
        const initialNote = textParser.parse("Create a plan to learn about probabilistic logic engines.");
        if (initialNote) {
            if(thoughtStore.addThought(initialNote)){
                logger.info("Added sample initial NOTE to start processing.");
            }
        }
        initialThoughtCount = thoughtStore.thoughts.size; // Update count
    } else {
        logger.info(`Loaded ${initialThoughtCount} thoughts from ${config.persistenceFile}.`);
        // Reset any ACTIVE thoughts to PENDING (as they were interrupted)
        let resetCount = 0;
        const thoughtsToReset = [];
        for (const thought of thoughtStore.getAllThoughts()) {
            if (thought.status === Status.ACTIVE) {
                thoughtsToReset.push(thought);
            }
        }
        thoughtsToReset.forEach(thought => {
            const current = thoughtStore.getThought(thought.id); // Get latest state
            if (current && current.status === Status.ACTIVE) {
                const resetThought = current.toBuilder()
                    .setStatus(Status.PENDING)
                    .setMetadata('error_info', 'Reset to PENDING on restart')
                    .setMetadata('retry_count', 0) // Reset retries too
                    .build();
                if (thoughtStore.updateThought(current, resetThought)) {
                    resetCount++;
                } else {
                    logger.warn(`Failed to reset Thought ${thought.id} from ACTIVE to PENDING (likely conflict).`);
                }
            }
        });
        if (resetCount > 0) logger.info(`Reset ${resetCount} previously ACTIVE thoughts to PENDING.`);
    }

    // 4. Start Periodic Tasks (GC)
    logger.info("Scheduling initial Garbage Collection...");
    // Run GC shortly after start and then periodically
    setTimeout(() => garbageCollect(thoughtStore), 5000); // Run GC 5s after start
    const gcIntervalId = setInterval(() => garbageCollect(thoughtStore), config.gcIntervalMillis);
    logger.info(`Garbage Collection scheduled to run every ${config.gcIntervalMillis / 1000 / 60} minutes.`);

    // 5. Start the Main Execution Loop
    executeLoop.start();
    logger.info(`Engine started. Initial thought count: ${initialThoughtCount}. Status counts: ${JSON.stringify(thoughtStore.getStatusCounts())}`);

    // 6. Graceful Shutdown Handling
    const shutdown = async (signal) => {
        logger.info(`Received ${signal}. Initiating graceful shutdown...`);
        // 1. Stop the execution loop (prevents new cycles, waits for current)
        await executeLoop.stop();

        // 2. Stop periodic tasks
        clearInterval(gcIntervalId);
        logger.info("Stopped periodic Garbage Collection.");

        // 3. Ensure final state is persisted
        logger.info("Waiting for any pending persistence operations to complete...");
        await thoughtStore.waitForPersistence(); // Wait for ongoing save
        // Trigger one final save if not already queued/running
        if (!thoughtStore.savingPromise && !thoughtStore.saveQueued) {
            logger.info("Triggering final state persistence.");
            try {
                // Use the service directly for the final save
                await persistenceService.save(thoughtStore.thoughts);
                logger.info("Final state saved successfully.");
            } catch (e) {
                logger.error("Error during final state persistence:", e);
            }
        } else {
            logger.info("Final persistence was already queued or running, waited for completion.");
        }


        logger.info("Coglog shutdown complete.");
        process.exit(0); // Exit cleanly
    };

    process.on('SIGINT', () => shutdown('SIGINT')); // Ctrl+C
    process.on('SIGTERM', () => shutdown('SIGTERM')); // Termination signal
    process.on('uncaughtException', (error, origin) => {
        logger.error(`!!! Uncaught Exception at: ${origin}, error: ${error.message}`, error.stack);
        // Optionally try to shutdown gracefully, but it might fail
        shutdown('uncaughtException').catch(() => process.exit(1)); // Force exit if shutdown fails
    });
    process.on('unhandledRejection', (reason, promise) => {
        logger.error(`!!! Unhandled Promise Rejection at: ${promise}, reason: ${reason instanceof Error ? reason.message : reason}`, reason instanceof Error ? reason.stack : '');
        // Optionally shutdown
        // shutdown('unhandledRejection').catch(() => process.exit(1));
    });

    logger.info("--- Coglog Initialization Complete ---");
    logger.info("Engine running. Press Ctrl+C to exit gracefully.");
}

// Execute the main function and catch top-level errors.
main().catch(error => {
    console.error("!!! Unhandled exception during Coglog startup or shutdown:", error);
    process.exit(1); // Exit with error code
});