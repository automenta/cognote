/**
 * Coglog 2.6.1 - Probabilistic, Reflective, Self-Executing Logic Engine
 *
 * Implemented in a single Node.js file using LangChain.js for LLM interaction.
 * Follows the unambiguous specification provided, focusing on completeness,
 * correctness, compactness, consolidation, deduplication, modularity, and
 * self-documentation.
 *
 * Version incorporates fixes for identified bugs (e.g., ThoughtBuilder status method)
 * and general improvements based on review.
 *
 * Core Principles:
 * - Probabilistic execution via weighted sampling based on Belief scores.
 * - Meta-circular definition: Engine logic defined by internal META_THOUGHTs.
 * - Planning cycle: Goal -> Strategy/Sub-goal -> Execution -> Outcome -> Status Update.
 * - Reflection: Generates new META_THOUGHTs to adapt behavior.
 * - Robustness: Error handling, retries, persistence, garbage collection.
 * - Immutability: Data structures are treated as immutable; updates create new instances.
 *
 * Usage:
 * 1. Install dependencies: `npm install uuid @langchain/openai`
 * 2. Set environment variable: `OPENAI_API_KEY=<your_key>`
 * 3. Run: `node coglog.js`
 *
 * The engine will load state from 'coglog_state.json' if it exists,
 * otherwise it initializes with bootstrap META_THOUGHTs and starts the execution loop.
 */

// === Dependencies ===
import {randomUUID} from 'crypto'; // Built-in UUID v4
//import {ChatOpenAI} from "@langchain/openai"; // For LLM interaction
import { ChatOllama } from "@langchain/community/chat_models/ollama";
import {HumanMessage} from "@langchain/core/messages"; // LangChain message type
import fs from 'fs/promises'; // For persistence
import path from 'path'; // For path manipulation

// === Configuration ===
// Central configuration object for engine parameters.
const config = Object.freeze({
    persistenceFile: 'coglog_state.json',
    maxRetries: 3,                  // Max retries for a failing thought
    pollIntervalMillis: 100,        // Pause duration when no PENDING thoughts
    maxActiveDurationMillis: 30000, // Max time a thought can be ACTIVE before timeout
    gcIntervalMillis: 60 * 60 * 1000, // Run garbage collection hourly
    gcThresholdMillis: 24 * 60 * 60 * 1000, // GC thoughts older than 1 day (DONE/FAILED only)
    llmModelName: "gpt-4o-mini",     // Default LLM model
    logLevel: process.env.COGLOG_LOG_LEVEL || 'info', // 'debug', 'info', 'warn', 'error'
    maxBeliefValue: Number.MAX_SAFE_INTEGER, // Cap belief counts to avoid precision issues
});

// === Logging ===
// Simple logger respecting configured level.
const logger = {
    debug: (...args) => config.logLevel === 'debug' && console.debug('[DEBUG]', new Date().toISOString(), ...args),
    info: (...args) => ['debug', 'info'].includes(config.logLevel) && console.info('[INFO]', new Date().toISOString(), ...args),
    warn: (...args) => ['debug', 'info', 'warn'].includes(config.logLevel) && console.warn('[WARN]', new Date().toISOString(), ...args),
    error: (...args) => console.error('[ERROR]', new Date().toISOString(), ...args),
};

// === Constants ===
// Define standard roles and statuses using strings for simplicity and serialization.
const Role = Object.freeze({
    NOTE: 'NOTE',
    GOAL: 'GOAL',
    STRATEGY: 'STRATEGY',
    OUTCOME: 'OUTCOME',
    META_THOUGHT: 'META_THOUGHT',
});

const Status = Object.freeze({
    PENDING: 'PENDING',
    ACTIVE: 'ACTIVE',         // Transient state during processing
    WAITING_CHILDREN: 'WAITING_CHILDREN',
    DONE: 'DONE',
    FAILED: 'FAILED',
});

// === Data Structures ===
// Implementing Term subtypes using classes with a 'type' discriminator.

// Base class for Terms
class TermBase {
    // Method to facilitate deep cloning and substitution
    applySubstitution(substitutionMap) {
        return this;
    } // Default: no change
    // Utility for simple serialization
    toJSON() {
        return {type: this.type, ...this};
    }

    // For cleaner logging and comparisons
    toString() {
        return this._toString();
    }

    _toString() {
        throw new Error("Subclasses must implement _toString");
    }

    equals(other) {
        return this === other || (other instanceof TermBase && this.toString() === other.toString());
    }
}

class Atom extends TermBase {
    type = 'Atom';

    constructor(name) {
        super();
        if (typeof name !== 'string' || !name) throw new Error("Atom name must be a non-empty string");
        this.name = name;
        Object.freeze(this);
    }

    _toString() {
        return this.name;
    }
}

class Variable extends TermBase {
    type = 'Variable';

    constructor(name) {
        super();
        if (typeof name !== 'string' || !(name.startsWith('_') || /^[A-Z]/.test(name))) {
            throw new Error(`Invalid Variable name: "${name}". Must be a string starting with '_' or uppercase letter.`);
        }
        this.name = name;
        Object.freeze(this);
    }

    _toString() {
        return this.name;
    }

    // Variables are replaced during substitution
    applySubstitution(substitutionMap) {
        let current = substitutionMap.get(this.name);
        while (current instanceof Variable && substitutionMap.has(current.name)) {
            const next = substitutionMap.get(current.name);
            if (next === current || next.name === current.name) break; // Avoid infinite loop
            current = next;
        }
        // Recursively apply substitution to the resolved value if it's another term
        return current ? current.applySubstitution(substitutionMap) : this;
    }
}

class NumberTerm extends TermBase {
    type = 'NumberTerm';

    constructor(value) {
        super();
        if (typeof value !== 'number' || !Number.isFinite(value)) {
            throw new Error("NumberTerm value must be a finite number");
        }
        this.value = value;
        Object.freeze(this);
    }

    _toString() {
        return this.value.toString();
    }
}

class Structure extends TermBase {
    type = 'Structure';

    constructor(name, args) {
        super();
        if (typeof name !== 'string' || !name) throw new Error("Structure name must be a non-empty string");
        if (!Array.isArray(args)) {// || !args.every(arg => arg instanceof TermBase)) {
            throw new Error("Structure args must be an array of Terms");
        }
        this.name = name;
        this.args = Object.freeze([...args]); // Ensure args array is immutable
        Object.freeze(this);
    }

    _toString() {
        return `${this.name}(${this.args.map(a => a.toString()).join(', ')})`;
    }

    // Apply substitution recursively to arguments
    applySubstitution(substitutionMap) {
        const newArgs = this.args.map(arg => arg.applySubstitution(substitutionMap));
        // Optimization: return same instance if no args changed
        return newArgs.every((newArg, i) => newArg === this.args[i])
            ? this
            : new Structure(this.name, newArgs);
    }
}

class ListTerm extends TermBase {
    type = 'ListTerm';

    constructor(elements) {
        super();
        if (!Array.isArray(elements) || !elements.every(el => el instanceof TermBase)) {
            throw new Error("ListTerm elements must be an array of Terms");
        }
        this.elements = Object.freeze([...elements]); // Ensure elements array is immutable
        Object.freeze(this);
    }

    _toString() {
        return `[${this.elements.map(e => e.toString()).join(', ')}]`;
    }

    // Apply substitution recursively to elements
    applySubstitution(substitutionMap) {
        const newElements = this.elements.map(el => el.applySubstitution(substitutionMap));
        // Optimization: return same instance if no elements changed
        return newElements.every((newEl, i) => newEl === this.elements[i])
            ? this
            : new ListTerm(newElements);
    }
}

// Belief class implementing scoring and updates with Laplace smoothing.
class Belief {
    // Common belief presets.
    static DEFAULT_POSITIVE = Object.freeze(new Belief(1.0, 0.0));    // score ≈ 0.67
    static DEFAULT_UNCERTAIN = Object.freeze(new Belief(0.0, 0.0));  // score = 0.5 (corrected)
    static DEFAULT_LOW_CONFIDENCE = Object.freeze(new Belief(0.1, 0.9)); // score ≈ 0.18

    constructor(positive, negative) {
        if (!Number.isFinite(positive) || !Number.isFinite(negative) || positive < 0 || negative < 0) {
            throw new Error(`Invalid Belief counts: positive=${positive}, negative=${negative}. Must be finite and non-negative.`);
        }
        // Cap values to prevent potential overflow/precision issues
        this.positive = Math.min(positive, config.maxBeliefValue);
        this.negative = Math.min(negative, config.maxBeliefValue);
        Object.freeze(this);
    }

    static fromJSON(json) {
        if (json && typeof json.positive === 'number' && typeof json.negative === 'number') {
            return new Belief(json.positive, json.negative);
        }
        logger.warn("Invalid Belief JSON detected during deserialization, using DEFAULT_UNCERTAIN:", json);
        return Belief.DEFAULT_UNCERTAIN; // Fallback
    }

    // Calculate belief score using Laplace smoothing (add-1).
    score() {
        const total = this.positive + this.negative;
        // Avoid division by zero and handle potential overflow
        return total === 0 ? 0.5 // Initial state (0,0) -> score 0.5
            : total > Number.MAX_SAFE_INTEGER / 2 ? this.positive / total // Approximate for large numbers
                : (this.positive + 1.0) / (total + 2.0);
    }

    // Return a *new* Belief instance with updated counts.
    update(positiveSignal) {
        return positiveSignal
            ? new Belief(this.positive + 1.0, this.negative)
            : new Belief(this.positive, this.negative + 1.0);
    }

    // For serialization/deserialization
    toJSON() {
        return {positive: this.positive, negative: this.negative};
    }
}


// Thought class representing the primary unit of processing.
class Thought {
    constructor(id, role, content, belief, status, metadata) {
        // Input validation
        if (typeof id !== 'string' || id.length !== 36) throw new Error(`Invalid Thought ID: ${id}`);
        if (!Object.values(Role).includes(role)) throw new Error(`Invalid Role: ${role}`);
        if (!(content instanceof TermBase)) throw new Error(`Invalid Content: Must be a Term instance. Received: ${content?.constructor?.name}`);
        if (!(belief instanceof Belief)) throw new Error(`Invalid Belief: Must be a Belief instance. Received: ${belief?.constructor?.name}`);
        if (!Object.values(Status).includes(status)) throw new Error(`Invalid Status: ${status}`);
        if (typeof metadata !== 'object' || metadata === null) throw new Error("Metadata must be an object");

        this.id = id;
        this.role = role;
        this.content = content;
        this.belief = belief;
        this.status = status;
        // Ensure metadata is treated as immutable (shallow freeze is sufficient here)
        this.metadata = Object.freeze({...metadata});

        // Freeze the thought instance itself
        Object.freeze(this);
    }

    // Reconstructs Thought from plain JSON object
    static fromJSON(json) {
        try {
            return new Thought(
                json.id,
                json.role,
                termFromJSON(json.content), // Requires Term reconstruction logic
                Belief.fromJSON(json.belief), // Requires Belief reconstruction
                json.status,
                json.metadata || {} // Ensure metadata is at least an empty object
            );
        } catch (error) {
            logger.error(`Error reconstructing Thought from JSON (ID: ${json?.id}): ${error.message}`);
            throw error; // Re-throw to prevent adding corrupted data
        }
    }

    // Builder pattern for creating modified copies, ensuring immutability.
    toBuilder() {
        return new ThoughtBuilder(this);
    }

    // For serialization/deserialization
    toJSON() {
        return {
            id: this.id,
            role: this.role,
            content: this.content.toJSON(), // Delegate Term serialization
            belief: this.belief.toJSON(), // Delegate Belief serialization
            status: this.status,
            metadata: {...this.metadata} // Clone metadata (already frozen, but good practice)
        };
    }
}

// Helper class for the Thought builder pattern.
class ThoughtBuilder {
    constructor(thought) {
        // Copy properties from the source thought
        this.id = thought.id;
        this.role = thought.role;
        this.content = thought.content;
        this.belief = thought.belief;
        this.status = thought.status; // This property will hold the value
        this.metadata = {...thought.metadata}; // Work with a mutable copy of metadata
    }

    // Internal helper to update metadata and timestamp consistently
    _updateMetadata(key, value) {
        this.metadata[key] = value;
        this.metadata['last_updated_timestamp'] = Date.now();
        return this; // Return 'this' for chaining
    }

    // Builder methods for setting properties
    setRole(newRole) {
        this.role = newRole;
        return this;
    }

    setContent(newContent) {
        this.content = newContent;
        return this;
    }

    setBelief(newBelief) {
        this.belief = newBelief;
        return this;
    }

    // *** CORRECTED METHOD NAME ***
    // Renamed from 'status' to 'setStatus' to avoid conflict with the 'status' property.
    setStatus(newStatus) {
        this.status = newStatus; // Update the property
        this._updateMetadata('last_updated_timestamp', Date.now()); // Update timestamp via helper
        return this; // Return 'this' for chaining
    }

    setMetadata(key, value) {
        return this._updateMetadata(key, value);
    }

    // Method to replace the entire metadata object
    replaceMetadata(newMetadata) {
        this.metadata = {...newMetadata, last_updated_timestamp: Date.now()};
        return this;
    }


    // Build the new immutable Thought instance
    build() {
        return new Thought(
            this.id,
            this.role,
            this.content,
            this.belief,
            this.status,
            this.metadata // Pass the modified metadata (will be frozen by Thought constructor)
        );
    }
}

// Helper to reconstruct Term hierarchy from JSON. Added error checking.
function termFromJSON(json) {
    if (!json || typeof json !== 'object' || !json.type) {
        throw new Error(`Invalid JSON for Term reconstruction: ${JSON.stringify(json)}`);
    }
    try {
        switch (json.type) {
            case 'Atom':
                return new Atom(json.name);
            case 'Variable':
                return new Variable(json.name);
            case 'NumberTerm':
                return new NumberTerm(json.value);
            case 'Structure':
                return new Structure(json.name, json.args.map(termFromJSON));
            case 'ListTerm':
                return new ListTerm(json.elements.map(termFromJSON));
            default:
                throw new Error(`Unknown Term type in JSON: ${json.type}`);
        }
    } catch (error) {
        // Catch errors during nested reconstruction (e.g., invalid args)
        throw new Error(`Error reconstructing Term type ${json.type}: ${error.message}`);
    }
}


// === Helper Functions ===

// Generate a UUID v4 string.
const generateUUID = () => randomUUID();

// UnificationException class (optional, unify returns null on failure)
// class UnificationException extends Error { /* ... */ }
// UnboundVariableException class (optional, applySubstitution returns var if unbound)
// class UnboundVariableException extends Error { /* ... */ }


/**
 * Performs unification between two terms.
 * @param {TermBase} term1 - The first term.
 * @param {TermBase} term2 - The second term.
 * @param {Map<string, TermBase>} initialSubstitution - Existing substitutions.
 * @returns {Map<string, TermBase> | null} A new map with substitutions if successful, null otherwise.
 */
function unify(term1, term2, initialSubstitution = new Map()) {
    const substitution = new Map(initialSubstitution); // Work on a copy
    const stack = [[term1, term2]]; // Stack for iterative unification

    while (stack.length > 0) {
        let [t1, t2] = stack.pop();

        // Follow substitution chains for variables
        t1 = resolveVariable(t1, substitution);
        t2 = resolveVariable(t2, substitution);

        // If terms are identical (including same variable instance), succeed
        if (t1 === t2 || (t1 instanceof Atom && t2 instanceof Atom && t1.name === t2.name) || (t1 instanceof NumberTerm && t2 instanceof NumberTerm && t1.value === t2.value)) {
            continue;
        }

        // If t1 is a variable, try binding it to t2
        if (t1 instanceof Variable) {
            if (occursCheck(t1, t2, substitution)) {
                logger.debug(`Unification failed: Occurs check failed for ${t1.name} in ${t2.toString()}`);
                return null; // Occurs check failed
            }
            substitution.set(t1.name, t2);
            continue;
        }

        // If t2 is a variable, try binding it to t1
        if (t2 instanceof Variable) {
            if (occursCheck(t2, t1, substitution)) {
                logger.debug(`Unification failed: Occurs check failed for ${t2.name} in ${t1.toString()}`);
                return null; // Occurs check failed
            }
            substitution.set(t2.name, t1);
            continue;
        }

        // If both are Structures, unify name and args
        if (t1 instanceof Structure && t2 instanceof Structure) {
            if (t1.name !== t2.name || t1.args.length !== t2.args.length) {
                logger.debug(`Unification failed: Structure mismatch ${t1.toString()} vs ${t2.toString()}`);
                return null; // Name or arity mismatch
            }
            // Add argument pairs to the stack for unification (order doesn't strictly matter)
            for (let i = 0; i < t1.args.length; i++) {
                stack.push([t1.args[i], t2.args[i]]);
            }
            continue;
        }

        // If both are ListTerms, unify elements
        if (t1 instanceof ListTerm && t2 instanceof ListTerm) {
            if (t1.elements.length !== t2.elements.length) {
                logger.debug(`Unification failed: List length mismatch ${t1.toString()} vs ${t2.toString()}`);
                return null; // Length mismatch
            }
            // Add element pairs to the stack
            for (let i = 0; i < t1.elements.length; i++) {
                stack.push([t1.elements[i], t2.elements[i]]);
            }
            continue;
        }

        // Any other combination means failure
        logger.debug(`Unification failed: Type mismatch or incompatible terms ${t1.toString()} (${t1?.constructor?.name}) vs ${t2.toString()} (${t2?.constructor?.name})`);
        return null;
    }

    return substitution; // Unification successful
}

// Helper to resolve a variable through the substitution chain.
function resolveVariable(term, substitution) {
    while (term instanceof Variable && substitution.has(term.name)) {
        const boundValue = substitution.get(term.name);
        // Check for direct self-reference which should be caught by occurs check, but prevents infinite loop here.
        if (boundValue === term || (boundValue instanceof Variable && boundValue.name === term.name)) return term;
        term = boundValue;
    }
    return term;
}

// Helper for occurs check to prevent infinite recursion (e.g., X = f(X)).
function occursCheck(variable, term, substitution) {
    term = resolveVariable(term, substitution); // Resolve term first

    if (variable === term || (variable instanceof Variable && term instanceof Variable && variable.name === term.name)) {
        return true; // Variable occurs directly
    } else if (term instanceof Variable && substitution.has(term.name)) {
        // If term is another variable, check what it's bound to (avoids infinite loop via stack)
        return occursCheck(variable, substitution.get(term.name), substitution);
    } else if (term instanceof Structure) {
        // Check if variable occurs in any argument
        return term.args.some(arg => occursCheck(variable, arg, substitution));
    } else if (term instanceof ListTerm) {
        // Check if variable occurs in any element
        return term.elements.some(el => occursCheck(variable, el, substitution));
    }

    return false; // Variable does not occur in the term
}

/**
 * Applies substitutions to a term recursively.
 * This function serves as the entry point, delegating to the TermBase subclasses.
 * Note: As implemented, it does not throw UnboundVariableException, allowing
 * terms to contain unbound variables after substitution if they were not bound
 * by the provided map. This differs slightly from a strict interpretation of the
 * spec but offers flexibility.
 * @param {TermBase} term - The term to apply substitutions to.
 * @param {Map<string, TermBase>} substitutionMap - The map of variable names to terms.
 * @returns {TermBase} A new Term instance with substitutions applied.
 */
function applySubstitution(term, substitutionMap) {
    if (!(term instanceof TermBase)) {
        throw new Error(`Cannot apply substitution to non-Term object: ${term}`);
    }
    if (!(substitutionMap instanceof Map)) {
        throw new Error(`Substitution map must be a Map instance.`);
    }
    return term.applySubstitution(substitutionMap);
}


/**
 * Samples an item from a list based on weighted scores.
 * @param {Array<[number, T]>} itemsWithScores - Array of [score, item] pairs.
 * @returns {T | null} The selected item, or null if the list is empty or total score is zero/invalid.
 */
function sampleWeighted(itemsWithScores) {
    if (!Array.isArray(itemsWithScores) || itemsWithScores.length === 0) return null;

    const validItems = itemsWithScores.filter(([score, item]) =>
        typeof score === 'number' && Number.isFinite(score) && score > 0 && item !== undefined && item !== null
    );
    if (validItems.length === 0) {
        logger.debug("Weighted sampling found no items with positive finite scores.");
        return null;
    }

    const totalScore = validItems.reduce((sum, [score]) => sum + score, 0);
    if (totalScore <= 0) { // Should not happen with filter, but safety check
        logger.warn("Weighted sampling total score is not positive despite valid items.");
        return null;
    }


    let randomValue = Math.random() * totalScore;

    for (const [score, item] of validItems) {
        randomValue -= score;
        if (randomValue <= 0) {
            return item;
        }
    }

    // Fallback (due to potential floating point inaccuracies at the very end)
    logger.debug("Weighted sampling falling back to last valid item.");
    return validItems[validItems.length - 1][1];
}


// === External Service Wrappers ===

// Basic LLM Service using LangChain.js
class LlmService {
    constructor(apiKey) {
        // if (!apiKey) {
        //     logger.warn("OPENAI_API_KEY not set. LLM calls will likely fail.");
        //     // Allow creation but calls will fail later
        //     this.client = null;
        // } else {
        this.client = new ChatOllama({
            apiKey: '',
            baseUrl: "http://localhost:11434", // default Ollama API port
            model: "llamablit", // or whatever model you started, e.g. "mistral"
        });
        // this.client = new ChatOpenAI({
        //     apiKey: '', // Placeholder, not used by Ollama but required by the wrapper
        //     baseUrl: 'http://localhost:11434', // Ollama's local API endpoint
        //     modelName: 'llamablit', // e.g., 'llama3.2', 'mistral', etc.
        //     temperature: 0.7,
        // });
        // }
    }

    // Invoke the LLM with a given prompt string.
    async invoke(prompt) {
        logger.debug(`Calling LLM (model: ${config.llmModelName}) with prompt: "${prompt.substring(0, 100)}..."`);
        try {
            const response = await this.client.invoke([new HumanMessage(prompt)]);
            const content = response?.content;
            if (typeof content !== 'string') {
                throw new Error(`LLM response content is not a string: ${content}`);
            }
            logger.debug(`LLM response received: "${content.substring(0, 100)}..."`);
            return content;
        } catch (error) {
            logger.error(`LLM invocation failed: ${error?.message || error}`);
            // Make error message more informative if possible
            const errMsg = error?.response?.data?.error?.message || error?.message || 'Unknown LLM API error';
            throw new Error(`LLM API call failed: ${errMsg}`);
        }
    }
}

// Persistence Service for saving/loading state to/from JSON file.
class PersistenceService {
    constructor(filePath) {
        this.filePath = path.resolve(filePath); // Ensure absolute path
        logger.info(`Persistence file path configured: ${this.filePath}`);
    }

    // Save the current state of all thoughts.
    async save(thoughtsMap) {
        logger.debug(`Persisting ${thoughtsMap.size} thoughts to ${this.filePath}...`);
        try {
            const thoughtsArray = Array.from(thoughtsMap.values());
            // Use Thought's toJSON for proper serialization including Terms/Beliefs
            const serializableData = thoughtsArray.map(t => t.toJSON());
            // Write atomically (create temp file, write, then rename)
            const tempFilePath = this.filePath + '.tmp';
            await fs.writeFile(tempFilePath, JSON.stringify(serializableData, null, 2)); // Pretty print JSON
            await fs.rename(tempFilePath, this.filePath); // Atomic rename
            logger.debug(`Persistence successful.`);
        } catch (error) {
            logger.error(`Failed to save state to ${this.filePath}:`, error);
            // Optionally clean up temp file if rename failed
            try {
                await fs.unlink(this.filePath + '.tmp');
            } catch (_) {
            }
            throw error; // Re-throw so background persistence failures are noticeable
        }
    }

    // Load thoughts from the persistence file.
    async load() {
        logger.debug(`Attempting to load state from ${this.filePath}...`);
        try {
            const data = await fs.readFile(this.filePath, 'utf-8');
            const thoughtsJSONArray = JSON.parse(data);
            if (!Array.isArray(thoughtsJSONArray)) {
                throw new Error("Persistence file does not contain a JSON array.");
            }
            const thoughtsMap = new Map();
            let invalidCount = 0;
            // Reconstruct Thought objects, skipping invalid ones
            thoughtsJSONArray.forEach((json, index) => {
                try {
                    const thought = Thought.fromJSON(json); // Validation happens in fromJSON
                    if (thoughtsMap.has(thought.id)) {
                        logger.warn(`Duplicate thought ID "${thought.id}" found during load. Skipping entry ${index}.`);
                        invalidCount++;
                    } else {
                        thoughtsMap.set(thought.id, thought);
                    }
                } catch (parseError) {
                    logger.warn(`Skipping invalid thought data at index ${index} during load: ${parseError.message}`, json);
                    invalidCount++;
                }
            });
            logger.info(`Successfully loaded ${thoughtsMap.size} thoughts from ${this.filePath}. Skipped ${invalidCount} invalid entries.`);
            return thoughtsMap;
        } catch (error) {
            if (error.code === 'ENOENT') {
                logger.info(`Persistence file ${this.filePath} not found. Starting with empty state.`);
                return new Map(); // File not found, return empty map is expected
            }
            logger.error(`Failed to load state from ${this.filePath}:`, error);
            logger.warn("Proceeding with empty state due to load error.");
            return new Map(); // Return empty map on other load errors for resilience
        }
    }
}

// Placeholder for notifying external systems (e.g., UI) about changes.
class StoreNotifier {
    notify(changeType, thought) {
        // Example: Log the notification
        logger.debug(`Notifier: ${changeType.toUpperCase()} - Thought ID: ${thought.id}, Status: ${thought.status}`);
        // In a real application, this could emit an event, send a WebSocket message, etc.
    }
}

// Simplified Text Parser Service (Example)
class TextParserService {
    parse(textInput) {
        if (typeof textInput !== 'string' || textInput.trim().length === 0) {
            logger.warn("TextParserService received empty or invalid input.");
            return null; // Return null for invalid input
        }
        logger.debug(`Parsing text input: "${textInput}"`);
        // Basic: Treat input as content of a NOTE.
        return new Thought(
            generateUUID(),
            Role.NOTE,
            new Structure("user_input", [new Atom(textInput)]), // Represent input as structure
            Belief.DEFAULT_POSITIVE, // Assume user input is initially believed
            Status.PENDING,
            {creation_timestamp: Date.now(), last_updated_timestamp: Date.now()}
        );
    }
}


// === Core Components ===

// Execution Context: Provides access to the currently executing META_THOUGHT ID for provenance.
const executionContext = {
    currentMetaThoughtId: null,
};

/**
 * ThoughtStore: In-memory storage for Thoughts with persistence integration.
 */
class ThoughtStore {
    constructor(persistenceService, storeNotifier) {
        this.thoughts = new Map(); // Map<String, Thought>
        this.persistenceService = persistenceService;
        this.storeNotifier = storeNotifier;
        this._savingPromise = null; // Track ongoing save operation
        this._saveQueued = false; // Flag if a save is needed after the current one
    }

    // Load initial state from persistence.
    async loadState() {
        this.thoughts = await this.persistenceService.load();
    }

    // Persist the current state asynchronously, preventing concurrent saves.
    async persistState() {
        if (this._savingPromise) {
            // Save already in progress, queue the next one
            this._saveQueued = true;
            logger.debug("Persistence already in progress, queuing next save.");
            return;
        }

        // Start the save operation
        this._savingPromise = this.persistenceService.save(this.thoughts)
            .catch(err => logger.error("Background persistence failed:", err)) // Log errors
            .finally(async () => {
                this._savingPromise = null; // Clear the current promise
                // If another save was requested while this one ran, start it now
                if (this._saveQueued) {
                    this._saveQueued = false;
                    logger.debug("Starting queued persistence operation.");
                    await this.persistState(); // Trigger the queued save
                }
            });

        // Do not await here in the main flow, let it run in the background
    }

    // Get a thought by its ID. Returns undefined if not found.
    getThought(id) {
        return this.thoughts.get(id);
    }

    // Add a new thought to the store.
    addThought(thought) {
        if (!(thought instanceof Thought)) throw new Error("Can only add Thought instances");
        if (this.thoughts.has(thought.id)) {
            logger.warn(`Attempted to add duplicate thought ID: ${thought.id}. Skipping add.`);
            return; // Avoid overwriting existing thought
        }
        this.thoughts.set(thought.id, thought);
        logger.debug(`Added Thought ${thought.id} (${thought.role}, ${thought.status})`);
        this.storeNotifier.notify('add', thought);
        this.persistState(); // Trigger background save
    }

    /**
     * Update an existing thought atomically (within the single thread).
     * @param {Thought} oldThought - The reference thought instance expected to be in the store.
     * @param {Thought} newThought - The new thought instance to store.
     * @returns {boolean} True if update was successful, false otherwise (e.g., conflict).
     */
    updateThought(oldThought, newThought) {
        if (!(newThought instanceof Thought) || !(oldThought instanceof Thought)) throw new Error("Must provide Thought instances for update");
        if (oldThought.id !== newThought.id) throw new Error("Cannot change thought ID during update");

        const currentThoughtInStore = this.thoughts.get(oldThought.id);
        if (!currentThoughtInStore) {
            logger.warn(`Attempted to update non-existent thought ID: ${oldThought.id}`);
            return false; // Thought not found
        }
        // Optimistic lock check: Ensure the thought hasn't changed since 'oldThought' was fetched.
        if (currentThoughtInStore !== oldThought) {
            logger.warn(`Update conflict for thought ID: ${oldThought.id}. Thought changed concurrently. Update rejected.`);
            return false; // Conflict detected
        }

        // Add provenance: Record the META_THOUGHT that caused this change, if available in context
        const activeMetaId = executionContext.currentMetaThoughtId;
        let provenance = Array.isArray(newThought.metadata.provenance) ? [...newThought.metadata.provenance] : [];
        if (activeMetaId && !provenance.includes(activeMetaId)) {
            provenance.push(activeMetaId);
        }
        // Ensure metadata object includes potentially updated provenance and timestamp
        const finalMetadata = {
            ...newThought.metadata,
            provenance: Object.freeze(provenance), // Ensure provenance array is immutable
            last_updated_timestamp: newThought.metadata.last_updated_timestamp || Date.now() // Ensure timestamp is set
        };

        // Create the final instance to store, ensuring updated metadata is used.
        // This relies on the builder having correctly set other fields.
        const thoughtToStore = new Thought(
            newThought.id, newThought.role, newThought.content, newThought.belief,
            newThought.status, finalMetadata
        );


        this.thoughts.set(thoughtToStore.id, thoughtToStore);
        logger.debug(`Updated Thought ${thoughtToStore.id} -> Status: ${thoughtToStore.status}, Belief: ${thoughtToStore.belief.score().toFixed(3)}`);
        this.storeNotifier.notify('update', thoughtToStore);
        this.persistState(); // Trigger background save
        return true;
    }

    // Remove a thought by ID (used by GC).
    removeThought(id) {
        const thought = this.thoughts.get(id);
        if (thought) {
            if (this.thoughts.delete(id)) {
                logger.debug(`Removed Thought ${id}`);
                this.storeNotifier.notify('remove', thought); // Notify with the removed thought
                this.persistState(); // Trigger background save
                return true;
            }
        }
        logger.warn(`Attempted to remove non-existent thought ID: ${id}`);
        return false;
    }

    // Get all thoughts with role META_THOUGHT.
    getMetaThoughts() {
        // Filter directly from values iterator for efficiency
        return Array.from(this.thoughts.values()).filter(t => t.role === Role.META_THOUGHT);
    }

    // Find thoughts by parent ID.
    findThoughtsByParentId(parentId) {
        return Array.from(this.thoughts.values()).filter(t => t.metadata?.parent_id === parentId);
    }

    // Sample a PENDING thought based on belief scores using weighted sampling.
    samplePendingThought() {
        // More efficient: iterate values and filter/map in one pass
        const pendingItemsWithScores = [];
        for (const thought of this.thoughts.values()) {
            if (thought.status === Status.PENDING) {
                const score = thought.belief.score();
                // Only consider thoughts with positive score for sampling
                if (score > 0) {
                    pendingItemsWithScores.push([score, thought]);
                }
            }
        }

        if (pendingItemsWithScores.length === 0) return null;

        const selectedThought = sampleWeighted(pendingItemsWithScores);

        if (selectedThought) {
            logger.debug(`Sampled PENDING Thought ${selectedThought.id} (Score: ${selectedThought.belief.score().toFixed(3)}) from ${pendingItemsWithScores.length} candidates.`);
        } else if (pendingItemsWithScores.length > 0) {
            // This case should be rare if sampleWeighted is correct, indicates scores might be problematic
            logger.warn(`Weighted sampling returned null despite ${pendingItemsWithScores.length} PENDING candidates with positive scores.`);
        }
        return selectedThought;
    }

    // Get all thoughts (e.g., for GC).
    getAllThoughts() {
        return Array.from(this.thoughts.values());
    }

    // Get count of thoughts by status
    getStatusCounts() {
        const counts = {PENDING: 0, ACTIVE: 0, WAITING_CHILDREN: 0, DONE: 0, FAILED: 0};
        for (const thought of this.thoughts.values()) {
            if (counts[thought.status] !== undefined) {
                counts[thought.status]++;
            }
        }
        return counts;
    }
}


/**
 * Unifier: Finds and samples matching META_THOUGHTs for a given thought.
 */
class Unifier {
    // Find potential META_THOUGHT matches and sample one based on belief score.
    findAndSampleMatchingMeta(activeThought, allMetaThoughts) {
        logger.debug(`Unifying Thought ${activeThought.id} (${activeThought.content.toString()}) against ${allMetaThoughts.length} META_THOUGHTs...`);
        const matches = [];

        for (const meta of allMetaThoughts) {
            // Validate META_THOUGHT format: Structure("meta_def", [TargetTerm, ActionTerm])
            if (!(meta.content instanceof Structure) || meta.content.name !== 'meta_def' || meta.content.args.length !== 2) {
                logger.warn(`Skipping META_THOUGHT ${meta.id} due to invalid format: ${meta.content.toString()}`);
                continue;
            }
            const targetTerm = meta.content.args[0];

            // Attempt unification between the META_THOUGHT's target and the active thought's content
            const substitutionMap = unify(targetTerm, activeThought.content);

            if (substitutionMap !== null) {
                const score = meta.belief.score();
                logger.debug(`Found potential match: ${activeThought.id} matches META ${meta.id} (Target: ${targetTerm.toString()}, Score: ${score.toFixed(3)})`);
                // Only consider matches with positive score for sampling
                if (score > 0) {
                    matches.push({metaThought: meta, substitutionMap: substitutionMap, score: score});
                } else {
                    logger.debug(`Skipping match with META ${meta.id} due to non-positive score (${score}).`);
                }
            }
        }

        if (matches.length === 0) {
            logger.debug(`No matching META_THOUGHT found (or none with positive score) for Thought ${activeThought.id}`);
            return {hasMatch: false};
        }

        // Sample one match based on the META_THOUGHT's belief score
        const selectedMatch = sampleWeighted(matches.map(m => [m.score, m]));

        if (selectedMatch) {
            logger.info(`Selected META_THOUGHT ${selectedMatch.metaThought.id} (Score: ${selectedMatch.score.toFixed(3)}) for Thought ${activeThought.id}`);
            return {
                hasMatch: true,
                matchedMetaThought: selectedMatch.metaThought,
                substitutionMap: selectedMatch.substitutionMap,
            };
        } else {
            logger.warn(`Weighted sampling failed to select a META match for Thought ${activeThought.id} despite ${matches.length} candidates with positive scores.`);
            return {hasMatch: false};
        }
    }
}


/**
 * ActionExecutor: Executes primitive actions defined by META_THOUGHTs.
 */
class ActionExecutor {
    constructor(thoughtStore, thoughtGenerator, llmService) {
        this.thoughtStore = thoughtStore;
        this.thoughtGenerator = thoughtGenerator;
        this.llmService = llmService;

        // Map action names to their implementation methods. Bound for correct 'this'.
        this.primitiveActions = {
            'add_thought': this._addThought.bind(this),
            'set_status': this._setStatus.bind(this),
            'set_belief': this._setBelief.bind(this),
            'check_parent_completion': this._checkParentCompletion.bind(this),
            'generate_thoughts': this._generateThoughts.bind(this),
            'sequence': this._sequence.bind(this),
            'call_llm': this._callLlm.bind(this),
            'log_message': this._logMessage.bind(this), // Added simple logging action
            'no_op': this._noOp.bind(this),           // Added no-op action
        };
    }

    /**
     * Executes the action defined in a matched META_THOUGHT.
     * @param {Thought} activeThought - The thought being processed (as it was when activated).
     * @param {Thought} metaThought - The matched META_THOUGHT.
     * @param {Map<string, TermBase>} substitutionMap - Substitutions from unification.
     * @returns {Promise<boolean>} True if execution was successful (action completed without error), false otherwise.
     */
    async execute(activeThought, metaThought, substitutionMap) {
        executionContext.currentMetaThoughtId = metaThought.id; // Set context for provenance
        let resolvedActionTerm; // For logging in finally block

        try {
            logger.debug(`Executing action from META ${metaThought.id} for Thought ${activeThought.id}`);
            // Extract action term: Structure("meta_def", [Target, Action]) -> Action
            const actionTermTemplate = metaThought.content.args[1];
            // Apply substitutions from unification to the action term template
            resolvedActionTerm = applySubstitution(actionTermTemplate, substitutionMap);
            logger.debug(`Resolved Action Term: ${resolvedActionTerm.toString()}`);

            // Action term must be a Structure: Structure(ActionName, Arguments)
            if (!(resolvedActionTerm instanceof Structure)) {
                throw new Error(`Resolved action term is not a Structure: ${resolvedActionTerm.toString()}`);
            }

            const actionName = resolvedActionTerm.name;
            const actionArgs = resolvedActionTerm.args;

            // Find and execute the corresponding primitive action
            const actionMethod = this.primitiveActions[actionName];
            if (!actionMethod) {
                throw new Error(`Unknown primitive action: ${actionName}`);
            }

            // Execute the action. Action methods return true on success, false/throw on failure.
            // Pass the *original* activeThought reference for context, but actions should fetch
            // the *current* state from the store if they need to modify it based on prior steps.
            const success = await actionMethod(activeThought, actionArgs, substitutionMap);

            if (!success) {
                // Action explicitly reported failure (e.g., LLM call failed, condition not met)
                logger.warn(`Primitive action "${actionName}" reported failure for Thought ${activeThought.id}.`);
            } else {
                logger.debug(`Primitive action "${actionName}" executed successfully for Thought ${activeThought.id}.`);
            }
            return success; // Indicate whether the action logic completed without throwing

        } catch (error) {
            // Catch errors during action resolution or execution
            logger.error(`Error executing action "${resolvedActionTerm?.name || 'unknown'}" for Thought ${activeThought.id} from META ${metaThought.id}:`, error);
            // Propagate the error to be handled by ExecuteLoop's main try/catch, which will call handleFailure.
            throw error;
        } finally {
            executionContext.currentMetaThoughtId = null; // Clear context
        }
    }

    // --- Primitive Action Implementations ---
    // Each returns Promise<boolean> or throws Error.

    // `add_thought(Role role, Term content, [Atom beliefType])`
    async _addThought(activeThought, args) {
        if (args.length < 2 || args.length > 3 || !(args[0] instanceof Atom) || !(args[1] instanceof TermBase)) {
            throw new Error("Invalid arguments for add_thought: Expected (Atom Role, Term Content, [Atom BeliefType])");
        }
        const roleName = args[0].name.toUpperCase();
        const role = Role[roleName];
        const content = args[1]; // Content is already substituted
        let belief = Belief.DEFAULT_UNCERTAIN; // Default if not provided

        if (!role) throw new Error(`Invalid role specified in add_thought: ${roleName}`);

        if (args.length === 3) {
            if (!(args[2] instanceof Atom)) throw new Error("Belief argument must be an Atom (e.g., 'DEFAULT_POSITIVE')");
            const beliefTypeName = args[2].name.toUpperCase();
            // Allow lookup by static property name or simple terms
            const beliefMap = {
                'DEFAULT_POSITIVE': Belief.DEFAULT_POSITIVE,
                'POSITIVE': Belief.DEFAULT_POSITIVE,
                'DEFAULT_UNCERTAIN': Belief.DEFAULT_UNCERTAIN,
                'UNCERTAIN': Belief.DEFAULT_UNCERTAIN,
                'DEFAULT_LOW_CONFIDENCE': Belief.DEFAULT_LOW_CONFIDENCE,
                'LOW_CONFIDENCE': Belief.DEFAULT_LOW_CONFIDENCE,
                'NEGATIVE': new Belief(0.0, 1.0) // If explicitly negative needed
            };
            if (beliefMap[beliefTypeName]) {
                belief = beliefMap[beliefTypeName];
            } else {
                logger.warn(`Unknown belief type specified: ${args[2].name}. Using DEFAULT_UNCERTAIN.`);
            }
        }

        const newThought = new Thought(
            generateUUID(), role, content, belief, Status.PENDING,
            {
                parent_id: activeThought.id, // Link to the active thought
                creation_timestamp: Date.now(),
                last_updated_timestamp: Date.now(),
            }
        );
        this.thoughtStore.addThought(newThought);
        return true; // Adding is generally successful if args are valid
    }

    // `set_status(Atom status)`
    async _setStatus(activeThought, args) {
        if (args.length !== 1 || !(args[0] instanceof Atom)) {
            throw new Error("Invalid arguments for set_status: Expected (Atom Status)");
        }
        const statusName = args[0].name.toUpperCase();
        const newStatus = Status[statusName];

        if (!newStatus) throw new Error(`Invalid status specified: ${statusName}`);
        if (newStatus === Status.ACTIVE) throw new Error("Cannot explicitly set status to ACTIVE via primitive action");

        // Need the *current* version of the thought from the store to update
        const currentThought = this.thoughtStore.getThought(activeThought.id);
        if (!currentThought) {
            logger.warn(`Thought ${activeThought.id} not found during set_status primitive.`);
            return false; // Thought disappeared
        }
        // Avoid redundant updates if status is already the target
        if (currentThought.status === newStatus) {
            logger.debug(`Thought ${activeThought.id} status is already ${newStatus}. Skipping set_status.`);
            return true;
        }

        const updatedThought = currentThought.toBuilder()
            .setStatus(newStatus) // *** USE CORRECTED METHOD ***
            .build();

        const success = this.thoughtStore.updateThought(currentThought, updatedThought);
        if (!success) logger.warn(`Failed to update thought ${activeThought.id} status to ${newStatus} (conflict?).`);
        return success;
    }

    // `set_belief(Atom type)` -> "POSITIVE" or "NEGATIVE" signal
    async _setBelief(activeThought, args) {
        if (args.length !== 1 || !(args[0] instanceof Atom)) {
            throw new Error("Invalid arguments for set_belief: Expected (Atom Type - 'POSITIVE' or 'NEGATIVE')");
        }
        const signalType = args[0].name.toUpperCase();
        let positiveSignal;
        if (signalType === 'POSITIVE') positiveSignal = true;
        else if (signalType === 'NEGATIVE') positiveSignal = false;
        else throw new Error(`Invalid belief signal type: ${signalType}. Expected POSITIVE or NEGATIVE.`);

        const currentThought = this.thoughtStore.getThought(activeThought.id);
        if (!currentThought) {
            logger.warn(`Thought ${activeThought.id} not found during set_belief primitive.`);
            return false;
        }

        const updatedBelief = currentThought.belief.update(positiveSignal);
        // Avoid update if belief hasn't changed (e.g., reached max value)
        if (updatedBelief.positive === currentThought.belief.positive && updatedBelief.negative === currentThought.belief.negative) {
            logger.debug(`Belief for thought ${activeThought.id} did not change. Skipping update.`);
            return true;
        }

        const updatedThought = currentThought.toBuilder()
            .setBelief(updatedBelief)
            .setMetadata('last_updated_timestamp', Date.now()) // Ensure timestamp updates on belief change
            .build();

        const success = this.thoughtStore.updateThought(currentThought, updatedThought);
        if (!success) logger.warn(`Failed to update belief for thought ${activeThought.id} (conflict?).`);
        return success;
    }


    // `check_parent_completion(Atom statusIfDone, Atom statusIfFailed)`
    // Checks if all children of the parent of activeThought are terminal (DONE/FAILED).
    // If so, updates the parent's status based on whether any child FAILED.
    async _checkParentCompletion(activeThought, args) {
        if (args.length !== 2 || !(args[0] instanceof Atom) || !(args[1] instanceof Atom)) {
            throw new Error("Invalid arguments for check_parent_completion: Expected (Atom StatusIfAllDone, Atom StatusIfAnyFailed)");
        }
        const statusIfDoneName = args[0].name.toUpperCase();
        const statusIfFailedName = args[1].name.toUpperCase();

        const statusIfDone = Status[statusIfDoneName];
        const statusIfFailed = Status[statusIfFailedName];

        // Validate target statuses
        if (!statusIfDone || statusIfDone === Status.ACTIVE || statusIfDone === Status.PENDING) {
            throw new Error(`Invalid target status for 'StatusIfAllDone': ${statusIfDoneName}`);
        }
        if (!statusIfFailed || statusIfFailed === Status.ACTIVE || statusIfFailed === Status.PENDING) {
            throw new Error(`Invalid target status for 'StatusIfAnyFailed': ${statusIfFailedName}`);
        }

        const parentId = activeThought.metadata?.parent_id;
        if (!parentId) {
            logger.debug(`Thought ${activeThought.id} has no parent_id, skipping parent completion check.`);
            return true; // No parent to check, action is trivially successful
        }

        const parent = this.thoughtStore.getThought(parentId);
        if (!parent) {
            logger.warn(`Parent thought ${parentId} not found for child ${activeThought.id}. Cannot check completion.`);
            // Return true because the action itself didn't fail, just couldn't find parent.
            // If finding the parent is critical, could return false or throw.
            return true;
        }

        // Only proceed if parent is WAITING_CHILDREN
        if (parent.status !== Status.WAITING_CHILDREN) {
            logger.debug(`Parent ${parentId} is not WAITING_CHILDREN (Status: ${parent.status}). Skipping check.`);
            return true;
        }

        const children = this.thoughtStore.findThoughtsByParentId(parentId);
        if (children.length === 0) {
            logger.warn(`Parent ${parentId} is WAITING_CHILDREN but no children found. Setting status to FAILED.`);
            const failedParent = parent.toBuilder()
                .setStatus(statusIfFailed) // Use the specified failed status
                .setBelief(parent.belief.update(false)) // Update belief negatively
                .setMetadata('error_info', 'Waiting for children, but none found.')
                .build();
            return this.thoughtStore.updateThought(parent, failedParent);
        }

        const anyNonTerminal = children.some(c => c.status !== Status.DONE && c.status !== Status.FAILED);

        if (anyNonTerminal) {
            logger.debug(`Not all children of ${parentId} are terminal yet. Parent remains ${parent.status}.`);
            return true; // Condition not met, action successful
        }

        // All children are terminal (DONE or FAILED)
        const anyFailed = children.some(c => c.status === Status.FAILED);
        const finalStatus = anyFailed ? statusIfFailed : statusIfDone;
        const beliefUpdateSignal = !anyFailed; // Positive signal only if ALL children are DONE

        logger.info(`All children of ${parentId} are terminal. ${anyFailed ? 'At least one FAILED.' : 'All DONE.'} Setting parent status to ${finalStatus}.`);

        const updatedParent = parent.toBuilder()
            .setStatus(finalStatus) // *** USE CORRECTED METHOD ***
            .setBelief(parent.belief.update(beliefUpdateSignal))
            // Optionally clear error_info if completing successfully
            // .setMetadata('error_info', anyFailed ? (parent.metadata.error_info || 'Child failed') : null) // Example
            .build();

        const success = this.thoughtStore.updateThought(parent, updatedParent);
        if (!success) logger.warn(`Failed to update parent ${parentId} after children completion check (conflict?).`);
        return success;
    }


    // `generate_thoughts(Term prompt)` -> Uses ThoughtGenerator (LLM)
    async _generateThoughts(activeThought, args) {
        if (args.length !== 1 || !(args[0] instanceof TermBase)) {
            throw new Error("Invalid arguments for generate_thoughts: Expected (Term Prompt)");
        }
        const promptTerm = args[0]; // Already substituted by execute()
        const promptInput = promptTerm.toString(); // Convert Term to string for LLM
        logger.debug(`Calling ThoughtGenerator for parent ${activeThought.id} with prompt: ${promptInput.substring(0, 150)}...`);

        try {
            // Delegate generation to ThoughtGenerator instance
            const newThoughts = await this.thoughtGenerator.generate(promptInput, activeThought.id);

            if (!Array.isArray(newThoughts)) {
                logger.error("ThoughtGenerator did not return an array.");
                return false;
            }

            if (newThoughts.length > 0) {
                logger.info(`ThoughtGenerator created ${newThoughts.length} new thoughts for parent ${activeThought.id}.`);
                newThoughts.forEach(t => {
                    if (t instanceof Thought) this.thoughtStore.addThought(t);
                    else logger.warn("ThoughtGenerator returned non-Thought item:", t);
                });
                return true; // Success if generation process completed and returned thoughts
            } else {
                logger.info(`ThoughtGenerator produced no thoughts for prompt: ${promptInput}`);
                return true; // Still successful execution, just no output
            }
        } catch (error) {
            logger.error(`Error during generate_thoughts primitive for ${activeThought.id}: ${error.message}`);
            return false; // Indicate failure of the primitive itself
        }
    }

    // `sequence(ListTerm actions)`
    async _sequence(activeThought, args, substitutionMap) {
        if (args.length !== 1 || !(args[0] instanceof ListTerm)) {
            throw new Error("Invalid arguments for sequence: Expected (ListTerm Actions)");
        }
        const actionList = args[0].elements;
        logger.debug(`Executing sequence of ${actionList.length} actions for Thought ${activeThought.id}`);

        for (let i = 0; i < actionList.length; i++) {
            const actionTerm = actionList[i];
            // Each element in the list must be a Structure representing an action
            if (!(actionTerm instanceof Structure)) {
                throw new Error(`Item ${i} in sequence is not an action Structure: ${actionTerm.toString()}`);
            }

            const actionName = actionTerm.name;
            const actionArgs = actionTerm.args;
            const actionMethod = this.primitiveActions[actionName];

            if (!actionMethod) {
                throw new Error(`Unknown primitive action "${actionName}" in sequence (item ${i})`);
            }

            // Fetch the *current* state of activeThought before each sub-action in the sequence.
            // This ensures actions operate on the state potentially modified by previous actions in the sequence.
            const currentActiveThoughtState = this.thoughtStore.getThought(activeThought.id);
            if (!currentActiveThoughtState) {
                logger.warn(`Active thought ${activeThought.id} disappeared during sequence execution (before action ${i}: ${actionName}). Aborting sequence.`);
                return false; // Abort sequence if thought is gone
            }
            // If thought status changed away from ACTIVE (e.g., a previous 'set_status' in the sequence), stop.
            if (currentActiveThoughtState.status !== Status.ACTIVE) {
                logger.info(`Active thought ${activeThought.id} status changed to ${currentActiveThoughtState.status} during sequence (before action ${i}: ${actionName}). Stopping sequence.`);
                // Sequence stopped due to status change, considered successful completion of the sequence up to this point.
                return true;
            }

            logger.debug(`Executing sequence action ${i + 1}/${actionList.length}: ${actionName}`);
            // Execute the sub-action. If it fails (returns false or throws), the whole sequence fails.
            try {
                const success = await actionMethod(currentActiveThoughtState, actionArgs, substitutionMap); // Pass original substitutions
                if (!success) {
                    logger.warn(`Action "${actionName}" (item ${i}) failed within sequence for Thought ${activeThought.id}. Aborting sequence.`);
                    return false; // Sequence fails if any action fails
                }
            } catch (error) {
                logger.error(`Error executing action "${actionName}" (item ${i}) within sequence for Thought ${activeThought.id}: ${error.message}. Aborting sequence.`);
                throw error; // Re-throw error to be handled by the main execute loop
            }
        }

        logger.debug(`Sequence executed successfully for Thought ${activeThought.id}`);
        return true; // All actions in sequence succeeded
    }


    // `call_llm(Term prompt, Atom resultRole)` -> Uses LLMService, creates a new thought
    async _callLlm(activeThought, args) {
        if (args.length !== 2 || !(args[0] instanceof TermBase) || !(args[1] instanceof Atom)) {
            throw new Error("Invalid arguments for call_llm: Expected (Term Prompt, Atom ResultRole)");
        }
        const promptTerm = args[0]; // Already substituted
        const resultRoleName = args[1].name.toUpperCase();
        const resultRole = Role[resultRoleName];

        if (!resultRole) {
            throw new Error(`Invalid result role specified in call_llm: ${resultRoleName}`);
        }

        const promptString = promptTerm.toString(); // Simple string conversion
        logger.debug(`Calling LLM for Thought ${activeThought.id} (result role ${resultRoleName}). Prompt: "${promptString.substring(0, 100)}..."`);

        try {
            const llmResponse = await this.llmService.invoke(promptString);

            if (llmResponse === null || llmResponse === undefined || llmResponse.trim() === '') {
                logger.warn(`LLM call for ${activeThought.id} returned empty response. No result thought created.`);
                return true; // Action succeeded, but no result to store
            }

            // Create a new thought for the result. Content = Atom(response).
            const resultContent = new Atom(llmResponse);
            const newThought = new Thought(
                generateUUID(),
                resultRole,
                resultContent,
                Belief.DEFAULT_POSITIVE, // Assume LLM output is initially useful
                Status.PENDING,
                {
                    parent_id: activeThought.id,
                    creation_timestamp: Date.now(),
                    last_updated_timestamp: Date.now(),
                    prompt: promptString.substring(0, 500), // Store truncated prompt used
                }
            );
            this.thoughtStore.addThought(newThought);
            logger.info(`LLM call successful for ${activeThought.id}. Created result Thought ${newThought.id} with role ${resultRoleName}.`);
            return true; // Success
        } catch (error) {
            // Error already logged by LlmService.invoke
            logger.error(`call_llm primitive failed for Thought ${activeThought.id}: ${error.message}`);
            return false; // Indicate failure of the primitive
        }
    }

    // `log_message(Atom level, Term message)`
    async _logMessage(activeThought, args) {
        if (args.length !== 2 || !(args[0] instanceof Atom) || !(args[1] instanceof TermBase)) {
            throw new Error("Invalid arguments for log_message: Expected (Atom Level, Term Message)");
        }
        const level = args[0].name.toLowerCase();
        const messageTerm = args[1]; // Already substituted
        const message = messageTerm.toString();

        const logFn = logger[level];
        if (typeof logFn === 'function') {
            logFn(`[Action Log - Thought ${activeThought.id}]: ${message}`);
        } else {
            logger.warn(`Invalid log level specified in log_message: ${level}. Defaulting to info.`);
            logger.info(`[Action Log - Thought ${activeThought.id}]: ${message}`);
        }
        return true; // Logging always succeeds
    }

    // `no_op()`
    async _noOp(activeThought, args) {
        if (args.length !== 0) {
            throw new Error("Invalid arguments for no_op: Expected zero arguments.");
        }
        logger.debug(`Executing no_op for Thought ${activeThought.id}.`);
        return true; // No-op always succeeds
    }
}


/**
 * ThoughtGenerator: Creates new thoughts, potentially using an LLM.
 */
class ThoughtGenerator {
    constructor(llmService) {
        this.llmService = llmService;
    }

    /**
     * Generates new thoughts based on a prompt.
     * @param {string} promptInput - The prompt string.
     * @param {string} parentId - The ID of the thought requesting generation.
     * @returns {Promise<Thought[]>} An array of new Thought instances.
     * @throws {Error} If LLM call fails or response parsing fails fundamentally.
     */
    async generate(promptInput, parentId) {
        // Example: Use LLM to decompose a goal. Ask for JSON output.
        const fullPrompt = `Given the high-level goal or request "${promptInput}", break it down into a list of specific, actionable sub-tasks or strategies needed to achieve it. Output ONLY a valid JSON list of strings, where each string is a clear sub-task. Example: ["Sub-task 1 description", "Sub-task 2 description"]. If the input is already a simple task or cannot be broken down, return an empty list []. Do not include any other text, explanation, or formatting.`;

        let llmResponse;
        try {
            llmResponse = await this.llmService.invoke(fullPrompt);
        } catch (error) {
            // Error already logged by llmService
            // Re-throw to indicate generation failure
            throw new Error(`LLM call failed during thought generation: ${error.message}`);
        }

        let tasks = [];
        try {
            // Attempt to parse the response strictly as a JSON list of strings
            tasks = JSON.parse(llmResponse);
            if (!Array.isArray(tasks)) {
                throw new Error("LLM response is not a JSON array.");
            }
            if (!tasks.every(t => typeof t === 'string')) {
                // Attempt to filter/convert, or throw error. Let's be strict.
                throw new Error("LLM response array contains non-string elements.");
            }
            // Filter out empty strings
            tasks = tasks.map(t => t.trim()).filter(t => t.length > 0);

        } catch (parseError) {
            logger.warn(`Failed to parse LLM response for thought generation as JSON list of strings: ${parseError.message}. Response: "${llmResponse}"`);
            // Consider this a failure of the generation process if strict output is required.
            // Alternative: Could try a fallback strategy (e.g., treat whole response as one task).
            // Let's throw to signal the generation failed to meet format requirements.
            throw new Error(`LLM response parsing failed: ${parseError.message}`);
        }

        if (tasks.length === 0) {
            logger.info(`LLM generated no valid sub-tasks for parent ${parentId} prompt: "${promptInput}"`);
            return []; // Return empty list, generation succeeded but yielded no tasks
        }

        // Create STRATEGY thoughts for each valid task string
        const newThoughts = tasks.map(taskString => {
            return new Thought(
                generateUUID(),
                Role.STRATEGY, // Generated thoughts are typically strategies
                new Structure("task", [new Atom(taskString)]), // Represent task as structure
                Belief.DEFAULT_POSITIVE, // Assume generated strategies are initially plausible
                Status.PENDING,
                {
                    parent_id: parentId,
                    creation_timestamp: Date.now(),
                    last_updated_timestamp: Date.now(),
                    source_prompt: promptInput.substring(0, 500), // Track origin
                }
            );
        });

        return newThoughts;
    }
}

// === Bootstrap META_THOUGHTs ===
// Define the initial set of rules that govern the engine's behavior.

// Helper to create Term structures concisely
const S = (name, ...args) => new Structure(name, args);
const A = (name) => new Atom(name);
const V = (name) => new Variable(name);
const L = (...elements) => new ListTerm(elements);

// Ensure bootstrap thoughts have unique IDs generated *at bootstrap time*
// Store templates here, generate actual thoughts in main() if needed.
const bootstrapMetaThoughtTemplates = [
    // MT-NOTE-TO-GOAL: Convert user input NOTE to a GOAL.
    {
        content: S("meta_def",
            S("user_input", V("Content")), // Target: NOTE from TextParserService
            S("sequence", L(
                // 1. Log the conversion
                S("log_message", A("info"), A("Converting user NOTE to GOAL")),
                // 2. Create a GOAL with the same content
                S("add_thought", A(Role.GOAL), V("Content"), A("DEFAULT_POSITIVE")),
                // 3. Mark the original NOTE as DONE
                S("set_status", A(Status.DONE))
            ))
        ),
        belief: Belief.DEFAULT_POSITIVE, // High confidence in this rule
        metadata: {is_bootstrap: true, description: "Convert user_input NOTE to GOAL"}
    },

    // MT-GOAL-DECOMPOSE: Use LLM to decompose a GOAL into STRATEGIES.
    {
        content: S("meta_def",
            V("GoalContent"), // Target: Matches any GOAL content
            S("sequence", L(
                // 1. Log action
                S("log_message", A("info"), S("concat", L(A("Decomposing GOAL: "), V("GoalContent")))), // Example using structure for logging
                // 2. Ask LLM to generate sub-thoughts (STRATEGIES)
                S("generate_thoughts", V("GoalContent")),
                // 3. Set GOAL to wait for children (if generation likely succeeded)
                // Note: generate_thoughts returns true even if 0 thoughts generated.
                // A better approach might be a conditional action or checking thought count.
                // Simplified: Assume generation is requested, set parent to wait.
                S("set_status", A(Status.WAITING_CHILDREN))
            ))
        ),
        belief: new Belief(1.0, 0.25), // High confidence, but acknowledge LLM might fail/yield nothing
        metadata: {
            is_bootstrap: true,
            description: "Decompose GOAL using LLM into STRATEGIES",
            applicable_role: Role.GOAL
        } // Hint for applicability
    },

    // MT-STRATEGY-EXECUTE: Execute a simple 'task' STRATEGY via LLM, creating an OUTCOME.
    {
        content: S("meta_def",
            S("task", V("TaskDesc")), // Target: Matches STRATEGY content from decomposition
            S("sequence", L(
                S("log_message", A("info"), S("concat", L(A("Executing STRATEGY: "), V("TaskDesc")))),
                // 1. Call LLM with task, expect OUTCOME result
                S("call_llm", S("execute_task_prompt", [V("TaskDesc")]), A(Role.OUTCOME)), // Example: wrap TaskDesc in another structure if needed for prompt engineering
                // 2. Mark STRATEGY as DONE (assuming LLM call was successfully initiated)
                // Note: If LLM fails, the OUTCOME thought might not be created. The parent completion check handles this.
                S("set_status", A(Status.DONE))
            ))
        ),
        belief: Belief.DEFAULT_POSITIVE,
        metadata: {
            is_bootstrap: true,
            description: "Execute task STRATEGY via LLM, create OUTCOME",
            applicable_role: Role.STRATEGY
        }
    },


    // MT-OUTCOME-PROCESS: Process a generated OUTCOME. Mark DONE and check parent.
    {
        content: S("meta_def",
            V("OutcomeContent"), // Target: Matches any OUTCOME content
            S("sequence", L(
                // 1. Log the outcome
                S("log_message", A("debug"), S("concat", L(A("Processing OUTCOME: "), V("OutcomeContent")))),
                // 2. Update belief positively (outcome received)
                S("set_belief", A("POSITIVE")),
                // 3. Mark OUTCOME as DONE
                S("set_status", A(Status.DONE)),
                // 4. Check if parent (STRATEGY or GOAL) is now complete
                //    If all children done -> Parent DONE. If any child failed -> Parent FAILED.
                S("check_parent_completion", A(Status.DONE), A(Status.FAILED))
            ))
        ),
        belief: Belief.DEFAULT_POSITIVE,
        metadata: {
            is_bootstrap: true,
            description: "Process OUTCOME, mark DONE, check parent completion",
            applicable_role: Role.OUTCOME
        }
    },

    // MT-IDENTITY-META: A simple meta-thought that just marks itself DONE.
    // Useful for testing the meta-circularity or having self-terminating meta rules.
    {
        content: S("meta_def",
            S("self_terminate", []), // Target: A specific content structure
            S("sequence", L(
                S("log_message", A("info"), A("Executing self-terminating META_THOUGHT.")),
                S("set_status", A(Status.DONE))
            ))
        ),
        belief: Belief.DEFAULT_POSITIVE,
        metadata: {is_bootstrap: true, description: "Meta-thought that marks itself DONE"}
    },

].filter(t => t.content instanceof Structure && t.content.name === 'meta_def'); // Basic validation


// === Garbage Collection ===

/**
 * Removes old DONE or FAILED thoughts from the store.
 * @param {ThoughtStore} thoughtStore - The store to clean.
 */
function garbageCollect(thoughtStore) {
    logger.info("Running garbage collection...");
    const startTime = Date.now();
    const thresholdTimestamp = startTime - config.gcThresholdMillis;
    let removedCount = 0;
    let examinedCount = 0;

    const allThoughts = thoughtStore.getAllThoughts(); // Get a snapshot
    const thoughtIndex = new Map(allThoughts.map(t => [t.id, t]));
    const childrenMap = new Map(); // Map<parentId, childId[]>

    // Build parent-child relationship map efficiently
    for (const thought of allThoughts) {
        examinedCount++;
        const parentId = thought.metadata?.parent_id;
        if (parentId) {
            const children = childrenMap.get(parentId) || [];
            children.push(thought.id);
            childrenMap.set(parentId, children);
        }
    }

    // Identify and remove candidates
    // Iterate backwards for potential efficiency if removing many items (though Map makes it less critical)
    for (let i = allThoughts.length - 1; i >= 0; i--) {
        const thought = allThoughts[i];

        // Candidate criteria: DONE or FAILED and old enough
        const isTerminal = thought.status === Status.DONE || thought.status === Status.FAILED;
        const timestamp = thought.metadata?.last_updated_timestamp ?? thought.metadata?.creation_timestamp ?? 0;
        const isOldEnough = timestamp < thresholdTimestamp;

        if (isTerminal && isOldEnough) {
            // Safety check: Does it have non-terminal children? (Should be rare if logic is correct, but safe)
            const childrenIds = childrenMap.get(thought.id) || [];
            const hasActiveChildren = childrenIds.some(childId => {
                const child = thoughtIndex.get(childId);
                // Keep parent if child exists and is *not* DONE or FAILED
                return child && child.status !== Status.DONE && child.status !== Status.FAILED;
            });

            if (hasActiveChildren) {
                logger.debug(`GC: Skipping ${thought.id} (${thought.status}) due to active children.`);
                continue; // Don't remove parent of active work
            }

            // Remove the thought
            if (thoughtStore.removeThought(thought.id)) {
                removedCount++;
            }
        }
    }

    const duration = Date.now() - startTime;
    logger.info(`Garbage collection complete. Examined ${examinedCount}, removed ${removedCount} thoughts in ${duration}ms.`);
    // Persist state after GC finishes (implicitly handled by removeThought calling persistState)
}


// === ExecuteLoop ===
// The main engine cycle controller.

class ExecuteLoop {
    constructor(thoughtStore, unifier, actionExecutor) {
        this.thoughtStore = thoughtStore;
        this.unifier = unifier;
        this.actionExecutor = actionExecutor;
        this.isRunning = false;
        this.timeoutId = null; // For scheduling the next cycle
        this.activeThoughtProcessing = null; // Promise for the currently processed thought
    }

    // Start the execution loop.
    start() {
        if (this.isRunning) {
            logger.warn("ExecuteLoop already running.");
            return;
        }
        this.isRunning = true;
        logger.info("Starting Coglog ExecuteLoop...");
        this.scheduleNextCycle(0); // Start immediately
    }

    // Stop the execution loop.
    async stop() {
        if (!this.isRunning) {
            logger.warn("ExecuteLoop already stopped.");
            return;
        }
        this.isRunning = false;
        if (this.timeoutId) clearTimeout(this.timeoutId);
        this.timeoutId = null;
        logger.info("ExecuteLoop stopping...");

        // Wait for any currently processing thought to finish
        if (this.activeThoughtProcessing) {
            logger.info("Waiting for current cycle to complete...");
            try {
                await this.activeThoughtProcessing;
                logger.info("Current cycle finished.");
            } catch (error) {
                logger.warn("Error occurred during final cycle completion:", error?.message);
            }
        }
        this.activeThoughtProcessing = null;
        logger.info("ExecuteLoop stopped.");
    }

    // Schedule the next execution cycle.
    scheduleNextCycle(delay = config.pollIntervalMillis) {
        if (!this.isRunning) return;
        // Clear previous timer if any (e.g., if called with delay 0 multiple times)
        if (this.timeoutId) clearTimeout(this.timeoutId);

        this.timeoutId = setTimeout(() => {
            // Use setImmediate to yield between cycles, preventing event loop starvation
            setImmediate(async () => {
                if (!this.isRunning) return; // Check again in case stopped while timer was pending
                this.activeThoughtProcessing = this.runCycle();
                try {
                    await this.activeThoughtProcessing;
                } catch (err) {
                    // Catch errors bubbled up from runCycle - should be handled within runCycle itself
                    logger.error("!!! Unhandled error escaped execution cycle:", err);
                } finally {
                    this.activeThoughtProcessing = null;
                    if (this.isRunning) {
                        // Schedule the *next* cycle only after the current one fully completes or errors
                        // Use delay 0 if work was done, pollInterval if nothing was found
                        const statusCounts = this.thoughtStore.getStatusCounts();
                        const nextDelay = statusCounts.PENDING > 0 ? 0 : config.pollIntervalMillis;
                        this.scheduleNextCycle(nextDelay);
                    }
                }
            });
        }, delay);
    }


    // Perform a single cycle of the execution loop.
    async runCycle() {
        let activeThoughtAtStart = null; // Track the thought selected for this cycle

        try {
            // 1. Sample a PENDING thought
            const pendingThought = this.thoughtStore.samplePendingThought();

            if (!pendingThought) {
                // No pending thoughts found, loop will pause via scheduleNextCycle delay
                logger.debug("No PENDING thoughts found.");
                // Return early, scheduleNextCycle will handle polling delay
                return;
            }

            // 2. Set thought to ACTIVE
            // Re-fetch to ensure it's still PENDING and get the latest reference for update
            const currentThoughtState = this.thoughtStore.getThought(pendingThought.id);
            if (!currentThoughtState) {
                logger.warn(`Sampled PENDING thought ${pendingThought.id} disappeared before activation. Resampling.`);
                // No need to schedule explicitly, the outer loop handles it.
                return;
            }
            if (currentThoughtState.status !== Status.PENDING) {
                logger.debug(`Thought ${currentThoughtState.id} status changed to ${currentThoughtState.status} before activation. Resampling.`);
                // No need to schedule explicitly, the outer loop handles it.
                return;
            }

            // Assign to cycle's scope for error handling
            activeThoughtAtStart = currentThoughtState;

            const activatedThoughtBuilder = activeThoughtAtStart.toBuilder()
                .setStatus(Status.ACTIVE) // *** USE CORRECTED METHOD ***
                .setMetadata('last_updated_timestamp', Date.now()); // Ensure timestamp is fresh
            const activatedThought = activatedThoughtBuilder.build();

            // Attempt atomic update
            if (!this.thoughtStore.updateThought(activeThoughtAtStart, activatedThought)) {
                logger.warn(`Failed to set thought ${activeThoughtAtStart.id} to ACTIVE (update conflict?). Rescheduling cycle.`);
                // No need to schedule explicitly, the outer loop handles it.
                return;
            }

            // Use the successfully activated thought instance moving forward in this cycle
            const processingThought = activatedThought;
            logger.info(`Processing Thought ${processingThought.id} (Role: ${processingThought.role}, Content: ${processingThought.content.toString().substring(0, 80)}...)`);

            // --- Setup Timeout ---
            let timeoutId = null;
            const timeoutPromise = new Promise((_, reject) => {
                timeoutId = setTimeout(() => {
                    const timeoutError = new Error(`Execution timeout (> ${config.maxActiveDurationMillis}ms)`);
                    timeoutError.name = 'TimeoutError';
                    reject(timeoutError);
                }, config.maxActiveDurationMillis);
            });


            // 3. Find matching META_THOUGHT & 4. Execute Action
            try {
                await Promise.race([
                    (async () => { // Wrap core logic in async IIFE for Promise.race
                        const metaThoughts = this.thoughtStore.getMetaThoughts();
                        const unificationResult = this.unifier.findAndSampleMatchingMeta(processingThought, metaThoughts);

                        if (unificationResult.hasMatch) {
                            const {matchedMetaThought, substitutionMap} = unificationResult;
                            // Execute the action
                            // ActionExecutor.execute will throw on internal errors
                            const actionSuccess = await this.actionExecutor.execute(processingThought, matchedMetaThought, substitutionMap);

                            // Check state *after* action execution completes (or fails explicitly)
                            const finalState = this.thoughtStore.getThought(processingThought.id);

                            if (!finalState) {
                                logger.warn(`Thought ${processingThought.id} disappeared during action execution.`);
                                // Nothing more to do for this thought
                            } else if (actionSuccess && finalState.status === Status.ACTIVE) {
                                // Action succeeded but didn't change status from ACTIVE (logic error in META_THOUGHT action)
                                logger.warn(`Action for Thought ${processingThought.id} completed but did not set a terminal status. Marking FAILED.`);
                                this.handleFailure(finalState, "Action did not set terminal status", 0); // No retries
                            } else if (!actionSuccess && finalState.status === Status.ACTIVE) {
                                // Action explicitly returned false (failure), but status still ACTIVE
                                logger.warn(`Action for Thought ${processingThought.id} failed, marking FAILED.`);
                                this.handleFailure(finalState, "Action execution reported failure", this.calculateRemainingRetries(finalState));
                            }
                            // If actionSuccess is true and status is no longer ACTIVE, action completed correctly.
                            // If actionSuccess is false and status is no longer ACTIVE, failure was handled (e.g., by handleFailure or sequence abort)
                        } else {
                            // No matching META_THOUGHT found
                            logger.warn(`No matching META_THOUGHT found for ${processingThought.id}. Marking FAILED.`);
                            this.handleFailure(processingThought, "No matching META_THOUGHT", 0); // No retries
                        }
                    })(), // End async IIFE
                    timeoutPromise
                ]);
            } catch (error) {
                // Handle errors from action execution OR timeout
                const currentThoughtStateAfterError = this.thoughtStore.getThought(processingThought.id);
                if (!currentThoughtStateAfterError) {
                    logger.warn(`Thought ${processingThought.id} disappeared after error during processing: ${error.message}`);
                } else if (currentThoughtStateAfterError.status === Status.ACTIVE) {
                    // Only handle failure if the thought is still ACTIVE (not already handled by action)
                    if (error.name === 'TimeoutError') {
                        logger.warn(`Timeout executing action for Thought ${processingThought.id}.`);
                        this.handleFailure(currentThoughtStateAfterError, "Timeout during action execution", this.calculateRemainingRetries(currentThoughtStateAfterError));
                    } else {
                        logger.error(`Error processing Thought ${processingThought.id}:`, error);
                        this.handleFailure(currentThoughtStateAfterError, `Execution Error: ${error.message}`, this.calculateRemainingRetries(currentThoughtStateAfterError));
                    }
                } else {
                    // Error occurred, but thought status is already terminal - likely handled within action, just log.
                    logger.warn(`Error occurred for Thought ${processingThought.id} but status is already ${currentThoughtStateAfterError.status}. Error: ${error.message}`);
                }
            } finally {
                // Clear the timeout timer regardless of outcome
                if (timeoutId) clearTimeout(timeoutId);
            }

        } catch (cycleError) {
            // Catch unexpected errors in the cycle logic *outside* the action execution race
            logger.error("!!! Critical error in execution cycle logic:", cycleError);
            if (activeThoughtAtStart) {
                // Attempt to mark the thought as FAILED if an error occurred *before or during* its activation/processing setup
                const currentThoughtState = this.thoughtStore.getThought(activeThoughtAtStart.id);
                // Only fail it if it's still ACTIVE (meaning the error happened after activation but before/during processing setup)
                // or if it's still PENDING (error happened before activation completed)
                if (currentThoughtState && (currentThoughtState.status === Status.ACTIVE || currentThoughtState.status === Status.PENDING)) {
                    logger.error(`Marking ${activeThoughtAtStart.id} as FAILED due to unhandled cycle error.`);
                    // Use currentThoughtState for the update if available and ACTIVE, otherwise use activeThoughtAtStart (if PENDING)
                    const thoughtToFail = currentThoughtState.status === Status.ACTIVE ? currentThoughtState : activeThoughtAtStart;
                    this.handleFailure(thoughtToFail, `Unhandled cycle error: ${cycleError.message}`, 0); // No retries for system errors
                }
            }
            // Allow the error to propagate up so the main loop logs it via `await this.activeThoughtProcessing` catch block
            throw cycleError;
        }
        // Outer loop handles scheduling the next cycle.
    }


    // Handle failure of a thought during processing. Needs the thought state *before* the update.
    handleFailure(thoughtAtFailure, errorMsg, retriesLeft) {
        // Fetch the absolute latest state from the store to use as the base for the update.
        // This prevents race conditions if the thought was modified between the failure occurring
        // and this handler being called (though unlikely in single thread, it's safer).
        const currentThoughtInStore = this.thoughtStore.getThought(thoughtAtFailure.id);
        if (!currentThoughtInStore) {
            logger.warn(`Thought ${thoughtAtFailure.id} not found during failure handling. Cannot update.`);
            return; // Thought disappeared
        }

        // Only proceed if the thought is currently ACTIVE. If it's already PENDING/FAILED/DONE,
        // another process (or a previous step in this cycle) likely already handled it.
        if (currentThoughtInStore.status !== Status.ACTIVE) {
            logger.debug(`Thought ${currentThoughtInStore.id} status is ${currentThoughtInStore.status}, not ACTIVE. Skipping failure handling for error: ${errorMsg}`);
            return;
        }

        const retryCount = (currentThoughtInStore.metadata?.retry_count || 0) + 1;
        const newStatus = retriesLeft > 0 ? Status.PENDING : Status.FAILED;
        const updatedBelief = currentThoughtInStore.belief.update(false); // Penalize belief on failure/retry

        logger.warn(`Handling failure for Thought ${currentThoughtInStore.id}: "${errorMsg}". Retries left: ${retriesLeft}. New status: ${newStatus}.`);

        const updatedThought = currentThoughtInStore.toBuilder()
            .setStatus(newStatus) // *** USE CORRECTED METHOD ***
            .setBelief(updatedBelief)
            .setMetadata('error_info', errorMsg.substring(0, 1000)) // Store truncated error
            .setMetadata('retry_count', retryCount)
            // last_updated_timestamp is handled by setStatus/setMetadata via _updateMetadata
            .build();

        // Perform the update using the fetched currentThoughtInStore as the 'old' reference
        if (!this.thoughtStore.updateThought(currentThoughtInStore, updatedThought)) {
            logger.error(`Failed to update thought ${currentThoughtInStore.id} to ${newStatus} during failure handling (conflict?)!`);
        }
    }

    // Calculate remaining retries for a thought (based on its current state).
    calculateRemainingRetries(thought) {
        const retries = thought.metadata?.retry_count || 0;
        // Ensure retries don't exceed maxRetries even if metadata is somehow inconsistent
        const effectiveRetries = Math.min(retries, config.maxRetries);
        return Math.max(0, config.maxRetries - effectiveRetries);
    }
}


// === Initialization and Main Execution ===

async function main() {
    logger.info("--- Coglog 2.6.1 Initializing ---");

    // --- Initialize Services ---
    const persistenceService = new PersistenceService(config.persistenceFile);
    const storeNotifier = new StoreNotifier();
    const llmService = new LlmService(process.env.OPENAI_API_KEY);
    const textParserService = new TextParserService();

    // --- Initialize Core Components ---
    const thoughtStore = new ThoughtStore(persistenceService, storeNotifier);
    const thoughtGenerator = new ThoughtGenerator(llmService);
    const unifier = new Unifier();
    const actionExecutor = new ActionExecutor(thoughtStore, thoughtGenerator, llmService);
    const executeLoop = new ExecuteLoop(thoughtStore, unifier, actionExecutor);

    // --- Load State or Bootstrap ---
    await thoughtStore.loadState();
    let initialThoughtCount = thoughtStore.thoughts.size;

    if (initialThoughtCount === 0) {
        logger.info("No previous state found. Loading bootstrap META_THOUGHTs...");
        bootstrapMetaThoughtTemplates.forEach((template, index) => {
            // Create actual Thought instances from templates with fresh IDs and timestamps
            const metaThought = new Thought(
                generateUUID(), // Generate fresh ID
                Role.META_THOUGHT,
                template.content,
                template.belief,
                Status.PENDING, // Start PENDING
                {
                    ...(template.metadata || {}), // Include template metadata
                    is_bootstrap: true, // Ensure bootstrap flag is set
                    creation_timestamp: Date.now(),
                    last_updated_timestamp: Date.now()
                }
            );
            thoughtStore.addThought(metaThought);
        });
        logger.info(`Loaded ${thoughtStore.thoughts.size} bootstrap META_THOUGHTs.`);

        // Add a sample initial NOTE only if store was completely empty
        const initialNote = textParserService.parse("Create a plan for today's top 3 priorities.");
        if (initialNote) {
            thoughtStore.addThought(initialNote);
            logger.info("Added sample initial NOTE to kick things off.");
        }
        initialThoughtCount = thoughtStore.thoughts.size; // Update count after bootstrap

    } else {
        logger.info(`Loaded ${initialThoughtCount} thoughts from state.`);
        // Reset ACTIVE thoughts from previous run to PENDING to avoid stuck states
        let resetCount = 0;
        const thoughtsToReset = [];
        // Collect thoughts to reset first to avoid modifying map during iteration issues
        for (const thought of thoughtStore.getAllThoughts()) {
            if (thought.status === Status.ACTIVE) {
                thoughtsToReset.push(thought);
            }
        }

        thoughtsToReset.forEach(t => {
            const current = thoughtStore.getThought(t.id); // Get latest ref
            if (current && current.status === Status.ACTIVE) { // Double check status
                const resetThought = current.toBuilder()
                    .setStatus(Status.PENDING) // *** USE CORRECTED METHOD ***
                    .setMetadata('error_info', 'Reset to PENDING on restart') // Add note
                    .build();
                if (thoughtStore.updateThought(current, resetThought)) {
                    resetCount++;
                } else {
                    logger.warn(`Failed to reset thought ${t.id} from ACTIVE to PENDING on load (conflict?).`);
                }
            }
        });
        if (resetCount > 0) logger.info(`Reset ${resetCount} ACTIVE thoughts to PENDING.`);
    }


    // --- Start Garbage Collector ---
    // Run GC once shortly after startup, then periodically
    logger.info("Scheduling initial garbage collection...");
    setTimeout(() => garbageCollect(thoughtStore), 5000); // Run after 5 seconds
    const gcIntervalId = setInterval(() => garbageCollect(thoughtStore), config.gcIntervalMillis);
    logger.info(`Garbage collection scheduled every ${config.gcIntervalMillis / 1000 / 60} minutes.`);

    // --- Start the Engine ---
    executeLoop.start();
    logger.info(`Engine started. Initial thought count: ${initialThoughtCount}. Status counts: ${JSON.stringify(thoughtStore.getStatusCounts())}`);


    // --- Handle graceful shutdown ---
    const shutdown = async (signal) => {
        logger.info(`${signal} received. Shutting down gracefully...`);
        // 1. Stop the execution loop (prevents new cycles, waits for current if any)
        await executeLoop.stop();

        // 2. Stop periodic tasks like GC
        clearInterval(gcIntervalId);
        logger.info("Stopped periodic garbage collection.");

        // 3. Ensure final persistence completes
        // Check if a save is queued or running
        if (thoughtStore._savingPromise || thoughtStore._saveQueued) {
            logger.info("Waiting for final persistence operation to complete...");
            // If a save is queued but not running, trigger it
            if (!thoughtStore._savingPromise && thoughtStore._saveQueued) {
                await thoughtStore.persistState();
            }
            // Wait for any potentially running save
            while (thoughtStore._savingPromise) {
                await thoughtStore._savingPromise;
                // Check again in case a queued save started
            }
            logger.info("Final persistence complete.");
        } else {
            // If no save was pending, maybe trigger one last save? Optional.
            logger.info("No pending persistence operation found. Triggering final save.");
            try {
                await thoughtStore.persistenceService.save(thoughtStore.thoughts); // Use direct save
                logger.info("Final state saved successfully.");
            } catch (err) {
                logger.error("Error during final state save on shutdown:", err);
            }
        }

        logger.info("Coglog shutdown complete.");
        process.exit(signal === 'SIGINT' ? 0 : 1); // Exit code 0 for SIGINT, 1 for SIGTERM? Or always 0? Let's use 0.
    };

    process.on('SIGINT', () => shutdown('SIGINT')); // Ctrl+C
    process.on('SIGTERM', () => shutdown('SIGTERM')); // Termination signal

    logger.info("--- Coglog Initialization Complete ---");
    logger.info("Engine is running. Press Ctrl+C to exit gracefully.");
}

// --- Run Main Function ---
main().catch(error => {
    console.error("!!! Unhandled exception during Coglog startup or shutdown:", error);
    process.exit(1); // Exit with error code on catastrophic failure
});
