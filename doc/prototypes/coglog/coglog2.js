/**
 * Coglog 2.6.1 - Probabilistic, Reflective, Self-Executing Logic Engine
 *
 * Implemented in a single Node.js file using LangChain.js for LLM interaction.
 * Follows the unambiguous specification provided, focusing on completeness,
 * correctness, compactness, consolidation, deduplication, modularity, and
 * self-documentation.
 *
 * Version incorporates fixes for identified bugs (e.g., ThoughtBuilder status method,
 * bootstrap term construction) and general improvements based on review.
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
 */

// === Dependencies ===
import { randomUUID } from 'crypto'; // Built-in UUID v4
//import { ChatOpenAI } from "@langchain/openai"; // For LLM interaction
import { HumanMessage } from "@langchain/core/messages"; // LangChain message type
import fs from 'fs/promises'; // For persistence
import path from 'path';
import {ChatOllama} from "@langchain/community/chat_models/ollama"; // For path manipulation

// === Configuration ===
const config = Object.freeze({
    persistenceFile: 'coglog_state.json',
    maxRetries: 3,
    pollIntervalMillis: 100,
    maxActiveDurationMillis: 30000,
    gcIntervalMillis: 60 * 60 * 1000,
    gcThresholdMillis: 24 * 60 * 60 * 1000,
    llmModelName: "gpt-4o-mini", // Updated default model
    logLevel: process.env.COGLOG_LOG_LEVEL || 'info',
    maxBeliefValue: Number.MAX_SAFE_INTEGER,
});

// === Logging ===
const logger = {
    debug: (...args) => config.logLevel === 'debug' && console.debug('[DEBUG]', new Date().toISOString(), ...args),
    info: (...args) => ['debug', 'info'].includes(config.logLevel) && console.info('[INFO]', new Date().toISOString(), ...args),
    warn: (...args) => ['debug', 'info', 'warn'].includes(config.logLevel) && console.warn('[WARN]', new Date().toISOString(), ...args),
    error: (...args) => console.error('[ERROR]', new Date().toISOString(), ...args),
};

// === Constants ===
const Role = Object.freeze({ NOTE: 'NOTE', GOAL: 'GOAL', STRATEGY: 'STRATEGY', OUTCOME: 'OUTCOME', META_THOUGHT: 'META_THOUGHT' });
const Status = Object.freeze({ PENDING: 'PENDING', ACTIVE: 'ACTIVE', WAITING_CHILDREN: 'WAITING_CHILDREN', DONE: 'DONE', FAILED: 'FAILED' });

// === Data Structures ===
class TermBase {
    applySubstitution(substitutionMap) { return this; }
    toJSON() { return { type: this.type, ...this }; }
    toString() { return this._toString(); }
    _toString() { throw new Error("Subclasses must implement _toString"); }
    equals(other) { return this === other || (other instanceof TermBase && this.toString() === other.toString()); }
}

class Atom extends TermBase {
    type = 'Atom';
    constructor(name) { super(); if (typeof name !== 'string' || !name) throw new Error("Atom name must be a non-empty string"); this.name = name; Object.freeze(this); }
    _toString() { return this.name; }
}

class Variable extends TermBase {
    type = 'Variable';
    constructor(name) { super(); if (typeof name !== 'string' || !(name.startsWith('_') || /^[A-Z]/.test(name))) throw new Error(`Invalid Variable name: "${name}". Must start with '_' or uppercase letter.`); this.name = name; Object.freeze(this); }
    _toString() { return this.name; }
    applySubstitution(substitutionMap) {
        let current = substitutionMap.get(this.name);
        let visited = new Set(); // Prevent infinite loops in substitution chain
        while (current instanceof Variable && substitutionMap.has(current.name) && !visited.has(current.name)) {
            visited.add(current.name);
            const next = substitutionMap.get(current.name);
            if (next === current || (next instanceof Variable && next.name === current.name)) break;
            current = next;
        }
        // If current resolved to a non-Variable Term, recursively apply substitution to it.
        // Otherwise, return the original variable (if unbound) or the final variable in the chain.
        return (current && current !== this && current instanceof TermBase) ? current.applySubstitution(substitutionMap) : this;
    }
}

class NumberTerm extends TermBase {
    type = 'NumberTerm';
    constructor(value) { super(); if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error("NumberTerm value must be a finite number"); this.value = value; Object.freeze(this); }
    _toString() { return this.value.toString(); }
}

class Structure extends TermBase {
    type = 'Structure';
    constructor(name, args) {
        super();
        if (typeof name !== 'string' || !name) throw new Error(`Structure name must be a non-empty string (got: ${name})`);
        if (!Array.isArray(args) || !args.every(arg => arg instanceof TermBase)) {
            const invalidArgs = Array.isArray(args) ? args.filter(arg => !(arg instanceof TermBase)).map(arg => typeof arg) : 'not an array';
            throw new Error(`Structure args must be an array of Terms. Invalid args types: ${invalidArgs} in Structure(${name}, ...)`);
        }
        this.name = name; this.args = Object.freeze([...args]); Object.freeze(this);
    }
    _toString() { return `${this.name}(${this.args.map(a => a.toString()).join(', ')})`; }
    applySubstitution(substitutionMap) {
        const newArgs = this.args.map(arg => {
            if (!(arg instanceof TermBase)) { // Safety check, though constructor should prevent this
                logger.error(`Non-Term object found in Structure args during substitution: ${arg}`);
                return arg; // Or throw? Let's return it to see where it leads, but log error.
            }
            return arg.applySubstitution(substitutionMap);
        });
        return newArgs.every((newArg, i) => newArg === this.args[i]) ? this : new Structure(this.name, newArgs);
    }
}

class ListTerm extends TermBase {
    type = 'ListTerm';
    constructor(elements) {
        super();
        if (!Array.isArray(elements) || !elements.every(el => el instanceof TermBase)) {
            const invalidElems = Array.isArray(elements) ? elements.filter(el => !(el instanceof TermBase)).map(el => typeof el) : 'not an array';
            throw new Error(`ListTerm elements must be an array of Terms. Invalid element types: ${invalidElems}`);
        }
        this.elements = Object.freeze([...elements]); Object.freeze(this);
    }
    _toString() { return `[${this.elements.map(e => e.toString()).join(', ')}]`; }
    applySubstitution(substitutionMap) {
        const newElements = this.elements.map(el => {
            if (!(el instanceof TermBase)) { // Safety check
                logger.error(`Non-Term object found in ListTerm elements during substitution: ${el}`);
                return el;
            }
            return el.applySubstitution(substitutionMap);
        });
        return newElements.every((newEl, i) => newEl === this.elements[i]) ? this : new ListTerm(newElements);
    }
}

class Belief {
    constructor(positive, negative) {
        if (!Number.isFinite(positive) || !Number.isFinite(negative) || positive < 0 || negative < 0) throw new Error(`Invalid Belief counts: positive=${positive}, negative=${negative}.`);
        this.positive = Math.min(positive, config.maxBeliefValue); this.negative = Math.min(negative, config.maxBeliefValue); Object.freeze(this);
    }
    score() { const total = this.positive + this.negative; return total === 0 ? 0.5 : total > Number.MAX_SAFE_INTEGER / 2 ? this.positive / total : (this.positive + 1.0) / (total + 2.0); }
    update(positiveSignal) { return positiveSignal ? new Belief(this.positive + 1.0, this.negative) : new Belief(this.positive, this.negative + 1.0); }
    static DEFAULT_POSITIVE = Object.freeze(new Belief(1.0, 0.0));
    static DEFAULT_UNCERTAIN = Object.freeze(new Belief(0.0, 0.0));
    static DEFAULT_LOW_CONFIDENCE = Object.freeze(new Belief(0.1, 0.9));
    toJSON() { return { positive: this.positive, negative: this.negative }; }
    static fromJSON(json) { return (json && typeof json.positive === 'number' && typeof json.negative === 'number') ? new Belief(json.positive, json.negative) : (logger.warn("Invalid Belief JSON, using DEFAULT_UNCERTAIN:", json), Belief.DEFAULT_UNCERTAIN); }
}

class Thought {
    constructor(id, role, content, belief, status, metadata) {
        if (typeof id !== 'string' || id.length !== 36) throw new Error(`Invalid Thought ID: ${id}`);
        if (!Object.values(Role).includes(role)) throw new Error(`Invalid Role: ${role}`);
        if (!(content instanceof TermBase)) throw new Error(`Invalid Content: Must be a Term instance. Received: ${content?.constructor?.name}`);
        if (!(belief instanceof Belief)) throw new Error(`Invalid Belief: Must be a Belief instance. Received: ${belief?.constructor?.name}`);
        if (!Object.values(Status).includes(status)) throw new Error(`Invalid Status: ${status}`);
        if (typeof metadata !== 'object' || metadata === null) throw new Error("Metadata must be an object");
        this.id = id; this.role = role; this.content = content; this.belief = belief; this.status = status; this.metadata = Object.freeze({ ...metadata }); Object.freeze(this);
    }
    toBuilder() { return new ThoughtBuilder(this); }
    toJSON() { return { id: this.id, role: this.role, content: this.content.toJSON(), belief: this.belief.toJSON(), status: this.status, metadata: { ...this.metadata } }; }
    static fromJSON(json) {
        try { return new Thought(json.id, json.role, termFromJSON(json.content), Belief.fromJSON(json.belief), json.status, json.metadata || {}); }
        catch (error) { logger.error(`Error reconstructing Thought from JSON (ID: ${json?.id}): ${error.message}`); throw error; }
    }
}

class ThoughtBuilder {
    constructor(thought) { this.id = thought.id; this.role = thought.role; this.content = thought.content; this.belief = thought.belief; this.status = thought.status; this.metadata = { ...thought.metadata }; }
    _updateMetadata(key, value) { this.metadata[key] = value; this.metadata['last_updated_timestamp'] = Date.now(); return this; }
    setRole(newRole) { this.role = newRole; return this; }
    setContent(newContent) { this.content = newContent; return this; }
    setBelief(newBelief) { this.belief = newBelief; return this; }
    setStatus(newStatus) { this.status = newStatus; this._updateMetadata('last_updated_timestamp', Date.now()); return this; }
    setMetadata(key, value) { return this._updateMetadata(key, value); }
    replaceMetadata(newMetadata) { this.metadata = { ...newMetadata, last_updated_timestamp: Date.now() }; return this; }
    build() { return new Thought(this.id, this.role, this.content, this.belief, this.status, this.metadata); }
}

function termFromJSON(json) {
    if (!json || typeof json !== 'object' || !json.type) throw new Error(`Invalid JSON for Term reconstruction: ${JSON.stringify(json)}`);
    try {
        switch (json.type) {
            case 'Atom': return new Atom(json.name);
            case 'Variable': return new Variable(json.name);
            case 'NumberTerm': return new NumberTerm(json.value);
            case 'Structure': return new Structure(json.name, json.args.map(termFromJSON)); // Recursive call
            case 'ListTerm': return new ListTerm(json.elements.map(termFromJSON)); // Recursive call
            default: throw new Error(`Unknown Term type in JSON: ${json.type}`);
        }
    } catch (error) { throw new Error(`Error reconstructing Term type ${json.type} (nested: ${error.message})`); }
}

// === Helper Functions ===
const generateUUID = () => randomUUID();

function unify(term1, term2, initialSubstitution = new Map()) {
    const substitution = new Map(initialSubstitution); const stack = [[term1, term2]];
    while (stack.length > 0) {
        let [t1, t2] = stack.pop();
        t1 = resolveVariable(t1, substitution); t2 = resolveVariable(t2, substitution);
        if (t1 === t2 || (t1 instanceof Atom && t2 instanceof Atom && t1.name === t2.name) || (t1 instanceof NumberTerm && t2 instanceof NumberTerm && t1.value === t2.value)) continue;
        if (t1 instanceof Variable) { if (occursCheck(t1, t2, substitution)) return null; substitution.set(t1.name, t2); continue; }
        if (t2 instanceof Variable) { if (occursCheck(t2, t1, substitution)) return null; substitution.set(t2.name, t1); continue; }
        if (t1 instanceof Structure && t2 instanceof Structure && t1.name === t2.name && t1.args.length === t2.args.length) { for (let i = 0; i < t1.args.length; i++) stack.push([t1.args[i], t2.args[i]]); continue; }
        if (t1 instanceof ListTerm && t2 instanceof ListTerm && t1.elements.length === t2.elements.length) { for (let i = 0; i < t1.elements.length; i++) stack.push([t1.elements[i], t2.elements[i]]); continue; }
        return null; // Mismatch
    } return substitution;
}

function resolveVariable(term, substitution) {
    let visited = null; // Lazy init Set for loop detection
    while (term instanceof Variable && substitution.has(term.name)) {
        const boundValue = substitution.get(term.name);
        if (boundValue === term || (boundValue instanceof Variable && boundValue.name === term.name)) return term; // Direct self-reference
        if (visited?.has(term.name)) return term; // Loop detected
        if (!visited) visited = new Set();
        visited.add(term.name);
        term = boundValue;
    } return term;
}

function occursCheck(variable, term, substitution) {
    term = resolveVariable(term, substitution);
    if (variable === term || (variable instanceof Variable && term instanceof Variable && variable.name === term.name)) return true;
    // Optimization: If term is not a variable, structure, or list, variable cannot occur in it.
    if (!(term instanceof Variable || term instanceof Structure || term instanceof ListTerm)) return false;
    // Use a stack to avoid deep recursion errors on large terms
    const stack = [term];
    const visited = new Set(); // Track visited compound terms to avoid redundant checks/loops
    while (stack.length > 0) {
        const current = resolveVariable(stack.pop(), substitution); // Resolve intermediate variables
        if (variable === current || (variable instanceof Variable && current instanceof Variable && variable.name === current.name)) return true;
        if (!current || visited.has(current)) continue; // Skip null/undefined/already visited

        if (current instanceof Structure) {
            visited.add(current);
            current.args.forEach(arg => stack.push(arg));
        } else if (current instanceof ListTerm) {
            visited.add(current);
            current.elements.forEach(el => stack.push(el));
        }
        // Ignore Atoms, Numbers, unbound Variables after resolution
    }
    return false;
}

function applySubstitution(term, substitutionMap) {
    if (!(term instanceof TermBase)) throw new Error(`Cannot apply substitution to non-Term object: ${term}`);
    if (!(substitutionMap instanceof Map)) throw new Error(`Substitution map must be a Map instance.`);
    return term.applySubstitution(substitutionMap);
}

function sampleWeighted(itemsWithScores) {
    if (!Array.isArray(itemsWithScores) || itemsWithScores.length === 0) return null;
    const validItems = itemsWithScores.filter(([score, item]) => typeof score === 'number' && Number.isFinite(score) && score > 0 && item !== undefined && item !== null);
    if (validItems.length === 0) return null;
    const totalScore = validItems.reduce((sum, [score]) => sum + score, 0);
    if (totalScore <= 0) return null;
    let randomValue = Math.random() * totalScore;
    for (const [score, item] of validItems) { randomValue -= score; if (randomValue <= 0) return item; }
    return validItems[validItems.length - 1][1]; // Fallback
}

// === External Service Wrappers ===
class LlmService {
    constructor(apiKey) {
        //this.client = apiKey ? new ChatOpenAI({ apiKey: apiKey, modelName: config.llmModelName, temperature: 0.7 }) : (logger.warn("OPENAI_API_KEY not set. LLM calls will fail."), null);
        this.client = new ChatOllama({
            apiKey: '',
            baseUrl: "http://localhost:11434", // default Ollama API port
            model: "llamablit", // or whatever model you started, e.g. "mistral"
        });
    }
    async invoke(prompt) {
        if (!this.client) throw new Error("LLM Service not configured (missing API key).");
        logger.debug(`Calling LLM (model: ${config.llmModelName}) prompt: "${prompt.substring(0, 100)}..."`);
        try {
            const response = await this.client.invoke([new HumanMessage(prompt)]);
            const content = response?.content; if (typeof content !== 'string') throw new Error(`LLM response content is not a string: ${content}`);
            logger.debug(`LLM response received: "${content.substring(0, 100)}..."`); return content;
        } catch (error) { const errMsg = error?.response?.data?.error?.message || error?.message || 'Unknown LLM API error'; logger.error(`LLM invocation failed: ${errMsg}`); throw new Error(`LLM API call failed: ${errMsg}`); }
    }
}

class PersistenceService {
    constructor(filePath) { this.filePath = path.resolve(filePath); logger.info(`Persistence file path: ${this.filePath}`); }
    async save(thoughtsMap) {
        logger.debug(`Persisting ${thoughtsMap.size} thoughts to ${this.filePath}...`);
        const tempFilePath = this.filePath + '.tmp';
        try {
            const serializableData = Array.from(thoughtsMap.values()).map(t => t.toJSON());
            await fs.writeFile(tempFilePath, JSON.stringify(serializableData, null, 2)); await fs.rename(tempFilePath, this.filePath); logger.debug(`Persistence successful.`);
        } catch (error) { logger.error(`Failed to save state to ${this.filePath}:`, error); try { await fs.unlink(tempFilePath); } catch (_) {} throw error; }
    }
    async load() {
        logger.debug(`Attempting to load state from ${this.filePath}...`);
        try {
            const data = await fs.readFile(this.filePath, 'utf-8'); const thoughtsJSONArray = JSON.parse(data); if (!Array.isArray(thoughtsJSONArray)) throw new Error("Persistence file not a JSON array.");
            const thoughtsMap = new Map(); let invalidCount = 0;
            thoughtsJSONArray.forEach((json, index) => {
                try { const thought = Thought.fromJSON(json); if (thoughtsMap.has(thought.id)) { logger.warn(`Duplicate thought ID "${thought.id}" during load. Skipping index ${index}.`); invalidCount++; } else { thoughtsMap.set(thought.id, thought); } }
                catch(parseError) { logger.warn(`Skipping invalid thought data index ${index} during load: ${parseError.message}`); invalidCount++; }
            });
            logger.info(`Loaded ${thoughtsMap.size} thoughts from ${this.filePath}. Skipped ${invalidCount} invalid entries.`); return thoughtsMap;
        } catch (error) { if (error.code === 'ENOENT') { logger.info(`Persistence file ${this.filePath} not found. Starting fresh.`); return new Map(); } logger.error(`Failed to load state from ${this.filePath}:`, error); logger.warn("Proceeding with empty state due to load error."); return new Map(); }
    }
}

class StoreNotifier { notify(changeType, thought) { logger.debug(`Notifier: ${changeType.toUpperCase()} - Thought ID: ${thought.id}, Status: ${thought.status}`); } }

class TextParserService {
    parse(textInput) {
        if (typeof textInput !== 'string' || !textInput.trim()) return null; logger.debug(`Parsing text input: "${textInput}"`);
        return new Thought(generateUUID(), Role.NOTE, S("user_input", A(textInput)), Belief.DEFAULT_POSITIVE, Status.PENDING, { creation_timestamp: Date.now(), last_updated_timestamp: Date.now() });
    }
}

// === Core Components ===
const executionContext = { currentMetaThoughtId: null };

class ThoughtStore {
    constructor(persistenceService, storeNotifier) { this.thoughts = new Map(); this.persistenceService = persistenceService; this.storeNotifier = storeNotifier; this._savingPromise = null; this._saveQueued = false; }
    async loadState() { this.thoughts = await this.persistenceService.load(); }
    async persistState() {
        if (this._savingPromise) { this._saveQueued = true; logger.debug("Persistence queued."); return; }
        this._savingPromise = this.persistenceService.save(this.thoughts).catch(err => logger.error("Background persistence failed:", err)).finally(async () => { this._savingPromise = null; if (this._saveQueued) { this._saveQueued = false; logger.debug("Starting queued persistence."); await this.persistState(); } });
    }
    getThought(id) { return this.thoughts.get(id); }
    addThought(thought) { if (!(thought instanceof Thought)) throw new Error("Can only add Thought instances"); if (this.thoughts.has(thought.id)) { logger.warn(`Attempted add duplicate thought ID: ${thought.id}. Skipping.`); return; } this.thoughts.set(thought.id, thought); logger.debug(`Added Thought ${thought.id} (${thought.role}, ${thought.status})`); this.storeNotifier.notify('add', thought); this.persistState(); }
    updateThought(oldThought, newThought) {
        if (!(newThought instanceof Thought) || !(oldThought instanceof Thought)) throw new Error("Must provide Thought instances for update"); if (oldThought.id !== newThought.id) throw new Error("Cannot change thought ID during update");
        const currentThoughtInStore = this.thoughts.get(oldThought.id); if (!currentThoughtInStore) { logger.warn(`Attempted update non-existent thought ID: ${oldThought.id}`); return false; } if (currentThoughtInStore !== oldThought) { logger.warn(`Update conflict for thought ID: ${oldThought.id}. Update rejected.`); return false; }
        const activeMetaId = executionContext.currentMetaThoughtId; let provenance = Array.isArray(newThought.metadata.provenance) ? [...newThought.metadata.provenance] : []; if (activeMetaId && !provenance.includes(activeMetaId)) provenance.push(activeMetaId);
        const finalMetadata = { ...newThought.metadata, provenance: Object.freeze(provenance), last_updated_timestamp: newThought.metadata.last_updated_timestamp || Date.now() };
        const thoughtToStore = new Thought(newThought.id, newThought.role, newThought.content, newThought.belief, newThought.status, finalMetadata);
        this.thoughts.set(thoughtToStore.id, thoughtToStore); logger.debug(`Updated Thought ${thoughtToStore.id} -> Status: ${thoughtToStore.status}, Belief: ${thoughtToStore.belief.score().toFixed(3)}`); this.storeNotifier.notify('update', thoughtToStore); this.persistState(); return true;
    }
    removeThought(id) { const thought = this.thoughts.get(id); if (thought && this.thoughts.delete(id)) { logger.debug(`Removed Thought ${id}`); this.storeNotifier.notify('remove', thought); this.persistState(); return true; } return false; }
    getMetaThoughts() { return Array.from(this.thoughts.values()).filter(t => t.role === Role.META_THOUGHT); }
    findThoughtsByParentId(parentId) { return Array.from(this.thoughts.values()).filter(t => t.metadata?.parent_id === parentId); }
    samplePendingThought() {
        const pendingItemsWithScores = []; for (const thought of this.thoughts.values()) { if (thought.status === Status.PENDING) { const score = thought.belief.score(); if (score > 0) pendingItemsWithScores.push([score, thought]); } }
        if (pendingItemsWithScores.length === 0) return null; const selectedThought = sampleWeighted(pendingItemsWithScores);
        if (selectedThought) logger.debug(`Sampled PENDING Thought ${selectedThought.id} (Score: ${selectedThought.belief.score().toFixed(3)}) from ${pendingItemsWithScores.length} candidates.`);
        else if (pendingItemsWithScores.length > 0) logger.warn(`Weighted sampling returned null despite ${pendingItemsWithScores.length} PENDING candidates.`);
        return selectedThought;
    }
    getAllThoughts() { return Array.from(this.thoughts.values()); }
    getStatusCounts() { const counts = { PENDING: 0, ACTIVE: 0, WAITING_CHILDREN: 0, DONE: 0, FAILED: 0 }; for (const thought of this.thoughts.values()) { if (counts[thought.status] !== undefined) counts[thought.status]++; } return counts; }
}

class Unifier {
    findAndSampleMatchingMeta(activeThought, allMetaThoughts) {
        logger.debug(`Unifying Thought ${activeThought.id} (${activeThought.content.toString()}) against ${allMetaThoughts.length} META_THOUGHTs...`);
        const matches = [];
        for (const meta of allMetaThoughts) {
            if (!(meta.content instanceof Structure) || meta.content.name !== 'meta_def' || meta.content.args.length !== 2) { logger.warn(`Skipping META_THOUGHT ${meta.id} due to invalid format: ${meta.content.toString()}`); continue; }
            const targetTerm = meta.content.args[0]; const substitutionMap = unify(targetTerm, activeThought.content);
            if (substitutionMap !== null) { const score = meta.belief.score(); logger.debug(`Potential match: ${activeThought.id} vs META ${meta.id} (Target: ${targetTerm.toString()}, Score: ${score.toFixed(3)})`); if (score > 0) matches.push({ metaThought: meta, substitutionMap: substitutionMap, score: score }); else logger.debug(`Skipping match with META ${meta.id} due to non-positive score (${score}).`); }
        }
        if (matches.length === 0) { logger.debug(`No matching META_THOUGHT found for Thought ${activeThought.id}`); return { hasMatch: false }; }
        const selectedMatch = sampleWeighted(matches.map(m => [m.score, m]));
        if (selectedMatch) { logger.info(`Selected META_THOUGHT ${selectedMatch.metaThought.id} (Score: ${selectedMatch.score.toFixed(3)}) for Thought ${activeThought.id}`); return { hasMatch: true, matchedMetaThought: selectedMatch.metaThought, substitutionMap: selectedMatch.substitutionMap }; }
        else { logger.warn(`Weighted sampling failed for META match for Thought ${activeThought.id} despite ${matches.length} candidates.`); return { hasMatch: false }; }
    }
}

class ActionExecutor {
    constructor(thoughtStore, thoughtGenerator, llmService) { this.thoughtStore = thoughtStore; this.thoughtGenerator = thoughtGenerator; this.llmService = llmService; this.primitiveActions = { 'add_thought': this._addThought.bind(this), 'set_status': this._setStatus.bind(this), 'set_belief': this._setBelief.bind(this), 'check_parent_completion': this._checkParentCompletion.bind(this), 'generate_thoughts': this._generateThoughts.bind(this), 'sequence': this._sequence.bind(this), 'call_llm': this._callLlm.bind(this), 'log_message': this._logMessage.bind(this), 'no_op': this._noOp.bind(this) }; }
    async execute(activeThought, metaThought, substitutionMap) {
        executionContext.currentMetaThoughtId = metaThought.id; let resolvedActionTerm; try {
            logger.debug(`Executing action from META ${metaThought.id} for Thought ${activeThought.id}`); const actionTermTemplate = metaThought.content.args[1];
            resolvedActionTerm = applySubstitution(actionTermTemplate, substitutionMap); logger.debug(`Resolved Action Term: ${resolvedActionTerm.toString()}`);
            if (!(resolvedActionTerm instanceof Structure)) throw new Error(`Resolved action term is not a Structure: ${resolvedActionTerm.toString()}`);
            const actionName = resolvedActionTerm.name; const actionArgs = resolvedActionTerm.args; const actionMethod = this.primitiveActions[actionName]; if (!actionMethod) throw new Error(`Unknown primitive action: ${actionName}`);
            const success = await actionMethod(activeThought, actionArgs, substitutionMap); if (!success) logger.warn(`Primitive action "${actionName}" reported failure for Thought ${activeThought.id}.`); else logger.debug(`Primitive action "${actionName}" executed successfully.`); return success;
        } catch (error) { logger.error(`Error executing action "${resolvedActionTerm?.name || 'unknown'}" for ${activeThought.id} from META ${metaThought.id}:`, error); throw error; }
        finally { executionContext.currentMetaThoughtId = null; }
    }
    async _addThought(activeThought, args) {
        if (args.length < 2 || args.length > 3 || !(args[0] instanceof Atom) || !(args[1] instanceof TermBase)) throw new Error("Invalid args for add_thought: Expected (Atom Role, Term Content, [Atom BeliefType])");
        const roleName = args[0].name.toUpperCase(); const role = Role[roleName]; if (!role) throw new Error(`Invalid role in add_thought: ${roleName}`); const content = args[1]; let belief = Belief.DEFAULT_UNCERTAIN;
        if (args.length === 3) { if (!(args[2] instanceof Atom)) throw new Error("Belief arg must be Atom"); const beliefTypeName = args[2].name.toUpperCase(); const beliefMap = {'POSITIVE': Belief.DEFAULT_POSITIVE, 'UNCERTAIN': Belief.DEFAULT_UNCERTAIN, 'LOW_CONFIDENCE': Belief.DEFAULT_LOW_CONFIDENCE, 'NEGATIVE': new Belief(0.0, 1.0)}; belief = beliefMap[beliefTypeName] || (logger.warn(`Unknown belief type ${args[2].name}. Using UNCERTAIN.`), Belief.DEFAULT_UNCERTAIN); }
        const newThought = new Thought(generateUUID(), role, content, belief, Status.PENDING, { parent_id: activeThought.id, creation_timestamp: Date.now(), last_updated_timestamp: Date.now() }); this.thoughtStore.addThought(newThought); return true;
    }
    async _setStatus(activeThought, args) {
        if (args.length !== 1 || !(args[0] instanceof Atom)) throw new Error("Invalid args for set_status: Expected (Atom Status)");
        const statusName = args[0].name.toUpperCase(); const newStatus = Status[statusName]; if (!newStatus) throw new Error(`Invalid status: ${statusName}`); if (newStatus === Status.ACTIVE) throw new Error("Cannot explicitly set status to ACTIVE");
        const currentThought = this.thoughtStore.getThought(activeThought.id); if (!currentThought) { logger.warn(`Thought ${activeThought.id} not found during set_status.`); return false; } if (currentThought.status === newStatus) return true; // Already set
        const updatedThought = currentThought.toBuilder().setStatus(newStatus).build(); const success = this.thoughtStore.updateThought(currentThought, updatedThought); if (!success) logger.warn(`Failed to update thought ${activeThought.id} status to ${newStatus} (conflict?).`); return success;
    }
    async _setBelief(activeThought, args) {
        if (args.length !== 1 || !(args[0] instanceof Atom)) throw new Error("Invalid args for set_belief: Expected (Atom 'POSITIVE'/'NEGATIVE')");
        const signalType = args[0].name.toUpperCase(); let positiveSignal; if (signalType === 'POSITIVE') positiveSignal = true; else if (signalType === 'NEGATIVE') positiveSignal = false; else throw new Error(`Invalid belief signal type: ${signalType}`);
        const currentThought = this.thoughtStore.getThought(activeThought.id); if (!currentThought) { logger.warn(`Thought ${activeThought.id} not found during set_belief.`); return false; }
        const updatedBelief = currentThought.belief.update(positiveSignal); if (updatedBelief.positive === currentThought.belief.positive && updatedBelief.negative === currentThought.belief.negative) return true; // No change
        const updatedThought = currentThought.toBuilder().setBelief(updatedBelief).setMetadata('last_updated_timestamp', Date.now()).build(); const success = this.thoughtStore.updateThought(currentThought, updatedThought); if (!success) logger.warn(`Failed to update belief for thought ${activeThought.id} (conflict?).`); return success;
    }
    async _checkParentCompletion(activeThought, args) {
        if (args.length !== 2 || !(args[0] instanceof Atom) || !(args[1] instanceof Atom)) throw new Error("Invalid args for check_parent_completion: Expected (Atom StatusIfAllDone, Atom StatusIfAnyFailed)");
        const statusIfDoneName = args[0].name.toUpperCase(); const statusIfFailedName = args[1].name.toUpperCase(); const statusIfDone = Status[statusIfDoneName]; const statusIfFailed = Status[statusIfFailedName];
        if (!statusIfDone || statusIfDone === Status.ACTIVE || statusIfDone === Status.PENDING) throw new Error(`Invalid StatusIfAllDone: ${statusIfDoneName}`); if (!statusIfFailed || statusIfFailed === Status.ACTIVE || statusIfFailed === Status.PENDING) throw new Error(`Invalid StatusIfAnyFailed: ${statusIfFailedName}`);
        const parentId = activeThought.metadata?.parent_id; if (!parentId) return true; // No parent
        const parent = this.thoughtStore.getThought(parentId); if (!parent) { logger.warn(`Parent ${parentId} not found for child ${activeThought.id}.`); return true; } if (parent.status !== Status.WAITING_CHILDREN) return true; // Parent not waiting
        const children = this.thoughtStore.findThoughtsByParentId(parentId); if (children.length === 0) { logger.warn(`Parent ${parentId} WAITING_CHILDREN but no children found. Setting parent FAILED.`); const fp = parent.toBuilder().setStatus(statusIfFailed).setBelief(parent.belief.update(false)).setMetadata('error_info', 'Waiting for children, but none found.').build(); return this.thoughtStore.updateThought(parent, fp); }
        const anyNonTerminal = children.some(c => c.status !== Status.DONE && c.status !== Status.FAILED); if (anyNonTerminal) return true; // Not all finished
        const anyFailed = children.some(c => c.status === Status.FAILED); const finalStatus = anyFailed ? statusIfFailed : statusIfDone; const beliefUpdateSignal = !anyFailed;
        logger.info(`All children of ${parentId} terminal. ${anyFailed ? 'Some FAILED.' : 'All DONE.'} Setting parent status to ${finalStatus}.`);
        const updatedParent = parent.toBuilder().setStatus(finalStatus).setBelief(parent.belief.update(beliefUpdateSignal)).build(); const success = this.thoughtStore.updateThought(parent, updatedParent); if (!success) logger.warn(`Failed to update parent ${parentId} after children completion check (conflict?).`); return success;
    }
    async _generateThoughts(activeThought, args) {
        if (args.length !== 1 || !(args[0] instanceof TermBase)) throw new Error("Invalid args for generate_thoughts: Expected (Term Prompt)");
        const promptTerm = args[0]; const promptInput = promptTerm.toString(); logger.debug(`Calling ThoughtGenerator for parent ${activeThought.id} prompt: ${promptInput.substring(0,150)}...`);
        try { const newThoughts = await this.thoughtGenerator.generate(promptInput, activeThought.id); if (!Array.isArray(newThoughts)) { logger.error("ThoughtGenerator didn't return array."); return false; } if (newThoughts.length > 0) { logger.info(`ThoughtGenerator created ${newThoughts.length} thoughts for ${activeThought.id}.`); newThoughts.forEach(t => { if (t instanceof Thought) this.thoughtStore.addThought(t); else logger.warn("ThoughtGenerator returned non-Thought item:", t); }); } else { logger.info(`ThoughtGenerator produced no thoughts for: ${promptInput}`); } return true; }
        catch (error) { logger.error(`Error during generate_thoughts primitive for ${activeThought.id}: ${error.message}`); return false; }
    }
    async _sequence(activeThought, args, substitutionMap) {
        if (args.length !== 1 || !(args[0] instanceof ListTerm)) throw new Error("Invalid args for sequence: Expected (ListTerm Actions)"); const actionList = args[0].elements; logger.debug(`Executing sequence of ${actionList.length} actions for ${activeThought.id}`);
        for (let i = 0; i < actionList.length; i++) { const actionTerm = actionList[i]; if (!(actionTerm instanceof Structure)) throw new Error(`Item ${i} in sequence not action Structure: ${actionTerm.toString()}`); const actionName = actionTerm.name; const actionArgs = actionTerm.args; const actionMethod = this.primitiveActions[actionName]; if (!actionMethod) throw new Error(`Unknown action "${actionName}" in sequence (item ${i})`);
            const currentActiveThoughtState = this.thoughtStore.getThought(activeThought.id); if (!currentActiveThoughtState) { logger.warn(`Active thought ${activeThought.id} disappeared during sequence (before ${i}: ${actionName}). Aborting.`); return false; } if (currentActiveThoughtState.status !== Status.ACTIVE) { logger.info(`Active thought ${activeThought.id} status became ${currentActiveThoughtState.status} during sequence (before ${i}: ${actionName}). Stopping sequence.`); return true; }
            logger.debug(`Seq action ${i+1}/${actionList.length}: ${actionName}`); try { const success = await actionMethod(currentActiveThoughtState, actionArgs, substitutionMap); if (!success) { logger.warn(`Action "${actionName}" (item ${i}) failed in sequence for ${activeThought.id}. Aborting.`); return false; } } catch (error) { logger.error(`Error in action "${actionName}" (item ${i}) within sequence for ${activeThought.id}: ${error.message}. Aborting.`); throw error; } }
        logger.debug(`Sequence executed successfully for ${activeThought.id}`); return true;
    }
    // Within class ActionExecutor:

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
        // Validate that the intended result role is suitable (e.g., typically OUTCOME)
        if (resultRole !== Role.OUTCOME) {
            logger.warn(`call_llm used with unusual result role: ${resultRoleName}. Expected OUTCOME.`);
            // Allow it, but log warning. Could throw error if strictness desired.
        }


        const promptString = promptTerm.toString(); // Simple string conversion
        logger.debug(`Calling LLM for Thought ${activeThought.id} (result role ${resultRoleName}). Prompt: "${promptString.substring(0,100)}..."`);

        try {
            const llmResponse = await this.llmService.invoke(promptString);

            if (!llmResponse?.trim()) {
                logger.warn(`LLM call for ${activeThought.id} returned empty response. No result thought created.`);
                return true; // Action succeeded, but no result to store
            }

            // *** FIXED LINE: Wrap result in a specific Structure ***
            const resultContent = S("llm_response", A(llmResponse)); // Was: A(llmResponse)

            const newThought = new Thought(
                generateUUID(),
                resultRole, // Use the specified role (e.g., OUTCOME)
                resultContent, // Store the structured result
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
            logger.info(`LLM call successful for ${activeThought.id}. Created result Thought ${newThought.id} role ${resultRoleName}.`);
            return true; // Success
        } catch (error) {
            logger.error(`call_llm primitive failed for ${activeThought.id}: ${error.message}`);
            return false; // Indicate failure of the primitive
        }
    }
    async _logMessage(activeThought, args) { if (args.length !== 2 || !(args[0] instanceof Atom) || !(args[1] instanceof TermBase)) throw new Error("Invalid args for log_message: Expected (Atom Level, Term Message)"); const level = args[0].name.toLowerCase(); const messageTerm = args[1]; const message = messageTerm.toString(); const logFn = logger[level]; if (typeof logFn === 'function') logFn(`[Action Log - Thought ${activeThought.id}]: ${message}`); else { logger.warn(`Invalid log level: ${level}. Defaulting to info.`); logger.info(`[Action Log - Thought ${activeThought.id}]: ${message}`); } return true; }
    async _noOp(activeThought, args) { if (args.length !== 0) throw new Error("no_op expects zero arguments."); logger.debug(`Executing no_op for ${activeThought.id}.`); return true; }
}

class ThoughtGenerator {
    constructor(llmService) { this.llmService = llmService; }
    async generate(promptInput, parentId) {
        const fullPrompt = `Given the high-level goal or request "${promptInput}", break it down into a list of specific, actionable sub-tasks or strategies needed to achieve it. Output ONLY a valid JSON list of strings, where each string is a clear sub-task. Example: ["Sub-task 1 description", "Sub-task 2 description"]. If the input is already a simple task or cannot be broken down, return an empty list []. Do not include any other text, explanation, or formatting.`;
        let llmResponse; try { llmResponse = await this.llmService.invoke(fullPrompt); } catch (error) { throw new Error(`LLM call failed during thought generation: ${error.message}`); }
        let tasks = []; try { tasks = JSON.parse(llmResponse); if (!Array.isArray(tasks)) throw new Error("LLM response not JSON array."); if (!tasks.every(t => typeof t === 'string')) throw new Error("LLM response array contains non-strings."); tasks = tasks.map(t => t.trim()).filter(t => t.length > 0); } catch (parseError) { logger.warn(`Failed to parse LLM gen response as JSON list: ${parseError.message}. Response: "${llmResponse}"`); throw new Error(`LLM response parsing failed: ${parseError.message}`); }
        if (tasks.length === 0) { logger.info(`LLM generated no sub-tasks for parent ${parentId} prompt: "${promptInput}"`); return []; }
        return tasks.map(taskString => new Thought(generateUUID(), Role.STRATEGY, S("task", [A(taskString)]), Belief.DEFAULT_POSITIVE, Status.PENDING, { parent_id: parentId, creation_timestamp: Date.now(), last_updated_timestamp: Date.now(), source_prompt: promptInput.substring(0, 500) }));
    }
}

// === Bootstrap META_THOUGHTs ===
const S = (name, ...args) => new Structure(name, args); const A = (name) => new Atom(name); const V = (name) => new Variable(name); const L = (...elements) => new ListTerm(elements);
const bootstrapMetaThoughtTemplates = [
    { content: S("meta_def", S("user_input", V("Content")), S("sequence", L( S("log_message", A("info"), A("Converting user NOTE to GOAL")), S("add_thought", A(Role.GOAL), S("goal_content", V("Content")), A("POSITIVE")), S("set_status", A(Status.DONE)) ))), belief: Belief.DEFAULT_POSITIVE, metadata: { description: "Convert user_input NOTE to GOAL" } }, // Wrap goal content
    { content: S("meta_def", S("goal_content", V("GoalContent")), S("sequence", L( S("log_message", A("info"), S("concat", L(A("Decomposing GOAL: "), V("GoalContent")))), S("generate_thoughts", V("GoalContent")), S("set_status", A(Status.WAITING_CHILDREN)) ))), belief: new Belief(1.0, 0.25), metadata: { description: "Decompose GOAL into STRATEGIES", applicable_role: Role.GOAL } }, // Match wrapped goal content
    { content: S("meta_def", S("task", V("TaskDesc")), S("sequence", L( S("log_message", A("info"), S("concat", L(A("Executing STRATEGY: "), V("TaskDesc")))), S("call_llm", S("execute_task_prompt", V("TaskDesc")), A(Role.OUTCOME)), S("set_status", A(Status.DONE)) ))), belief: Belief.DEFAULT_POSITIVE, metadata: { description: "Execute task STRATEGY via LLM", applicable_role: Role.STRATEGY } }, // *** Corrected L() use was implicitly done by using S() helper ***
    //{ content: S("meta_def", V("OutcomeContent"), S("sequence", L( S("log_message", A("debug"), S("concat", L(A("Processing OUTCOME: "), V("OutcomeContent")))), S("set_belief", A("POSITIVE")), S("set_status", A(Status.DONE)), S("check_parent_completion", A(Status.DONE), A(Status.FAILED)) ))), belief: Belief.DEFAULT_POSITIVE, metadata: { description: "Process OUTCOME, check parent completion", applicable_role: Role.OUTCOME } },
    // MT-OUTCOME-PROCESS: Process a generated OUTCOME. Mark DONE and check parent.
    {
        content: S("meta_def",
            // *** FIXED TARGET PATTERN ***
            S("llm_response", V("Result")), // Was: V("OutcomeContent")
            // Action sequence
            S("sequence", L(
                // 1. Log the outcome using the new variable name 'Result'
                S("log_message", A("debug"), S("concat", L(A("Processing OUTCOME: "), V("Result")))),
                // 2. Update belief positively (outcome received)
                S("set_belief", A("POSITIVE")),
                // 3. Mark OUTCOME as DONE
                S("set_status", A(Status.DONE)),
                // 4. Check if parent (STRATEGY or GOAL) is now complete
                S("check_parent_completion", A(Status.DONE), A(Status.FAILED))
            ))
        ),
        belief: Belief.DEFAULT_POSITIVE,
        // Explicitly state this applies to OUTCOME role thoughts for clarity
        metadata: { description: "Process llm_response OUTCOME, mark DONE, check parent", applicable_role: Role.OUTCOME }
    }, // Removed is_bootstrap flag as it's added globally later
].filter(t => t.content instanceof Structure && t.content.name === 'meta_def');

// === Garbage Collection ===
function garbageCollect(thoughtStore) {
    logger.info("Running garbage collection..."); const startTime = Date.now(); const thresholdTimestamp = startTime - config.gcThresholdMillis; let removedCount = 0; let examinedCount = 0;
    const allThoughts = thoughtStore.getAllThoughts(); const thoughtIndex = new Map(allThoughts.map(t => [t.id, t])); const childrenMap = new Map();
    for (const thought of allThoughts) { examinedCount++; const parentId = thought.metadata?.parent_id; if (parentId) { const children = childrenMap.get(parentId) || []; children.push(thought.id); childrenMap.set(parentId, children); } }
    for (let i = allThoughts.length - 1; i >= 0; i--) { const thought = allThoughts[i]; const isTerminal = thought.status === Status.DONE || thought.status === Status.FAILED; const timestamp = thought.metadata?.last_updated_timestamp ?? thought.metadata?.creation_timestamp ?? 0; const isOldEnough = timestamp < thresholdTimestamp;
        if (isTerminal && isOldEnough) { const childrenIds = childrenMap.get(thought.id) || []; const hasActiveChildren = childrenIds.some(childId => { const child = thoughtIndex.get(childId); return child && child.status !== Status.DONE && child.status !== Status.FAILED; }); if (hasActiveChildren) { logger.debug(`GC: Skipping ${thought.id} (${thought.status}) due to active children.`); continue; } if (thoughtStore.removeThought(thought.id)) removedCount++; } }
    const duration = Date.now() - startTime; logger.info(`Garbage collection complete. Examined ${examinedCount}, removed ${removedCount} thoughts in ${duration}ms.`);
}

// === ExecuteLoop ===
class ExecuteLoop {
    constructor(thoughtStore, unifier, actionExecutor) { this.thoughtStore = thoughtStore; this.unifier = unifier; this.actionExecutor = actionExecutor; this.isRunning = false; this.timeoutId = null; this.activeThoughtProcessing = null; }
    start() { if (this.isRunning) return; this.isRunning = true; logger.info("Starting Coglog ExecuteLoop..."); this.scheduleNextCycle(0); }
    async stop() { if (!this.isRunning) return; this.isRunning = false; if (this.timeoutId) clearTimeout(this.timeoutId); this.timeoutId = null; logger.info("ExecuteLoop stopping..."); if (this.activeThoughtProcessing) { logger.info("Waiting for current cycle..."); try { await this.activeThoughtProcessing; logger.info("Current cycle finished."); } catch (error) { logger.warn("Error during final cycle:", error?.message); } } this.activeThoughtProcessing = null; logger.info("ExecuteLoop stopped."); }
    scheduleNextCycle(delay = config.pollIntervalMillis) {
        if (!this.isRunning) return; if (this.timeoutId) clearTimeout(this.timeoutId);
        this.timeoutId = setTimeout(() => { setImmediate(async () => { if (!this.isRunning) return; this.activeThoughtProcessing = this.runCycle(); try { await this.activeThoughtProcessing; } catch (err) { logger.error("!!! Unhandled error escaped execution cycle:", err); } finally { this.activeThoughtProcessing = null; if (this.isRunning) { const statusCounts = this.thoughtStore.getStatusCounts(); const nextDelay = statusCounts.PENDING > 0 ? 0 : config.pollIntervalMillis; this.scheduleNextCycle(nextDelay); } } }); }, delay);
    }
    async runCycle() {
        let activeThoughtAtStart = null; try {
            const pendingThought = this.thoughtStore.samplePendingThought(); if (!pendingThought) { logger.debug("No PENDING thoughts found."); return; }
            const currentThoughtState = this.thoughtStore.getThought(pendingThought.id); if (!currentThoughtState) { logger.warn(`Sampled PENDING thought ${pendingThought.id} disappeared before activation.`); return; } if (currentThoughtState.status !== Status.PENDING) { logger.debug(`Thought ${currentThoughtState.id} status ${currentThoughtState.status}, not PENDING. Resampling.`); return; }
            activeThoughtAtStart = currentThoughtState; const activatedThought = activeThoughtAtStart.toBuilder().setStatus(Status.ACTIVE).setMetadata('last_updated_timestamp', Date.now()).build();
            if (!this.thoughtStore.updateThought(activeThoughtAtStart, activatedThought)) { logger.warn(`Failed to set thought ${activeThoughtAtStart.id} to ACTIVE (conflict?). Rescheduling.`); return; }
            const processingThought = activatedThought; logger.info(`Processing Thought ${processingThought.id} (Role: ${processingThought.role}, Content: ${processingThought.content.toString().substring(0,80)}...)`);
            let timeoutId = null; const timeoutPromise = new Promise((_, reject) => { timeoutId = setTimeout(() => { const e = new Error(`Execution timeout (> ${config.maxActiveDurationMillis}ms)`); e.name = 'TimeoutError'; reject(e); }, config.maxActiveDurationMillis); });
            try { await Promise.race([ (async () => { const metaThoughts = this.thoughtStore.getMetaThoughts(); const unificationResult = this.unifier.findAndSampleMatchingMeta(processingThought, metaThoughts); if (unificationResult.hasMatch) { const { matchedMetaThought, substitutionMap } = unificationResult; const actionSuccess = await this.actionExecutor.execute(processingThought, matchedMetaThought, substitutionMap); const finalState = this.thoughtStore.getThought(processingThought.id); if (!finalState) { logger.warn(`Thought ${processingThought.id} disappeared during action execution.`); } else if (actionSuccess && finalState.status === Status.ACTIVE) { logger.warn(`Action for ${processingThought.id} completed but didn't set terminal status. Marking FAILED.`); this.handleFailure(finalState, "Action did not set terminal status", 0); } else if (!actionSuccess && finalState.status === Status.ACTIVE) { logger.warn(`Action for ${processingThought.id} failed, marking FAILED.`); this.handleFailure(finalState, "Action execution reported failure", this.calculateRemainingRetries(finalState)); } } else { logger.warn(`No matching META_THOUGHT for ${processingThought.id}. Marking FAILED.`); this.handleFailure(processingThought, "No matching META_THOUGHT", 0); } })(), timeoutPromise ]); }
            catch (error) { const stateAfterError = this.thoughtStore.getThought(processingThought.id); if (!stateAfterError) { logger.warn(`Thought ${processingThought.id} disappeared after error: ${error.message}`); } else if (stateAfterError.status === Status.ACTIVE) { if (error.name === 'TimeoutError') { logger.warn(`Timeout processing ${processingThought.id}.`); this.handleFailure(stateAfterError, "Timeout during action execution", this.calculateRemainingRetries(stateAfterError)); } else { logger.error(`Error processing ${processingThought.id}:`, error); this.handleFailure(stateAfterError, `Execution Error: ${error.message}`, this.calculateRemainingRetries(stateAfterError)); } } else { logger.warn(`Error occurred for ${processingThought.id} but status already ${stateAfterError.status}. Error: ${error.message}`); } }
            finally { if (timeoutId) clearTimeout(timeoutId); }
        } catch (cycleError) { logger.error("!!! Critical error in cycle logic:", cycleError); if (activeThoughtAtStart) { const state = this.thoughtStore.getThought(activeThoughtAtStart.id); if (state && (state.status === Status.ACTIVE || state.status === Status.PENDING)) { const thoughtToFail = state.status === Status.ACTIVE ? state : activeThoughtAtStart; logger.error(`Marking ${thoughtToFail.id} FAILED due to unhandled cycle error.`); this.handleFailure(thoughtToFail, `Unhandled cycle error: ${cycleError.message}`, 0); } } throw cycleError; }
    }
    handleFailure(thoughtAtFailure, errorMsg, retriesLeft) {
        const currentThoughtInStore = this.thoughtStore.getThought(thoughtAtFailure.id); if (!currentThoughtInStore) { logger.warn(`Thought ${thoughtAtFailure.id} not found during failure handling.`); return; } if (currentThoughtInStore.status !== Status.ACTIVE) { logger.debug(`Thought ${currentThoughtInStore.id} status ${currentThoughtInStore.status}, not ACTIVE. Skipping failure handling for: ${errorMsg}`); return; }
        const retryCount = (currentThoughtInStore.metadata?.retry_count || 0) + 1; const newStatus = retriesLeft > 0 ? Status.PENDING : Status.FAILED; const updatedBelief = currentThoughtInStore.belief.update(false);
        logger.warn(`Handling failure for ${currentThoughtInStore.id}: "${errorMsg}". Retries left: ${retriesLeft}. New status: ${newStatus}.`);
        const updatedThought = currentThoughtInStore.toBuilder().setStatus(newStatus).setBelief(updatedBelief).setMetadata('error_info', errorMsg.substring(0, 1000)).setMetadata('retry_count', retryCount).build();
        if (!this.thoughtStore.updateThought(currentThoughtInStore, updatedThought)) { logger.error(`Failed to update thought ${currentThoughtInStore.id} to ${newStatus} during failure handling (conflict?)!`); }
    }
    calculateRemainingRetries(thought) { const retries = thought.metadata?.retry_count || 0; const effectiveRetries = Math.min(retries, config.maxRetries); return Math.max(0, config.maxRetries - effectiveRetries); }
}

// === Initialization and Main Execution ===
async function main() {
    logger.info("--- Coglog 2.6.1 Initializing ---");
    const persistenceService = new PersistenceService(config.persistenceFile); const storeNotifier = new StoreNotifier(); const llmService = new LlmService(process.env.OPENAI_API_KEY); const textParserService = new TextParserService();
    const thoughtStore = new ThoughtStore(persistenceService, storeNotifier); const thoughtGenerator = new ThoughtGenerator(llmService); const unifier = new Unifier(); const actionExecutor = new ActionExecutor(thoughtStore, thoughtGenerator, llmService); const executeLoop = new ExecuteLoop(thoughtStore, unifier, actionExecutor);
    await thoughtStore.loadState(); let initialThoughtCount = thoughtStore.thoughts.size;
    if (initialThoughtCount === 0) {
        logger.info("Loading bootstrap META_THOUGHTs..."); bootstrapMetaThoughtTemplates.forEach(template => { const metaThought = new Thought(generateUUID(), Role.META_THOUGHT, template.content, template.belief, Status.PENDING, { ...(template.metadata || {}), is_bootstrap: true, creation_timestamp: Date.now(), last_updated_timestamp: Date.now() }); thoughtStore.addThought(metaThought); }); logger.info(`Loaded ${thoughtStore.thoughts.size} bootstrap META_THOUGHTs.`);
        const initialNote = textParserService.parse("Create a plan for today's top 3 priorities."); if (initialNote) thoughtStore.addThought(initialNote); logger.info("Added sample initial NOTE."); initialThoughtCount = thoughtStore.thoughts.size;
    } else { logger.info(`Loaded ${initialThoughtCount} thoughts from state.`); let resetCount = 0; const thoughtsToReset = []; for (const thought of thoughtStore.getAllThoughts()) { if (thought.status === Status.ACTIVE) thoughtsToReset.push(thought); }
        thoughtsToReset.forEach(t => { const current = thoughtStore.getThought(t.id); if(current && current.status === Status.ACTIVE) { const resetThought = current.toBuilder().setStatus(Status.PENDING).setMetadata('error_info', 'Reset to PENDING on restart').build(); if(thoughtStore.updateThought(current, resetThought)) resetCount++; else logger.warn(`Failed reset ${t.id} from ACTIVE to PENDING (conflict?).`); } }); if (resetCount > 0) logger.info(`Reset ${resetCount} ACTIVE thoughts to PENDING.`);
    }
    logger.info("Scheduling initial garbage collection..."); setTimeout(() => garbageCollect(thoughtStore), 5000); const gcIntervalId = setInterval(() => garbageCollect(thoughtStore), config.gcIntervalMillis); logger.info(`GC scheduled every ${config.gcIntervalMillis / 1000 / 60} minutes.`);
    executeLoop.start(); logger.info(`Engine started. Initial thoughts: ${initialThoughtCount}. Status: ${JSON.stringify(thoughtStore.getStatusCounts())}`);
    const shutdown = async (signal) => { logger.info(`${signal} received. Shutting down...`); await executeLoop.stop(); clearInterval(gcIntervalId); logger.info("Stopped periodic GC."); if (thoughtStore._savingPromise || thoughtStore._saveQueued) { logger.info("Waiting for final persistence..."); if (!thoughtStore._savingPromise && thoughtStore._saveQueued) await thoughtStore.persistState(); while (thoughtStore._savingPromise) await thoughtStore._savingPromise; logger.info("Final persistence complete."); } else { logger.info("Triggering final save."); try { await thoughtStore.persistenceService.save(thoughtStore.thoughts); logger.info("Final state saved."); } catch (err) { logger.error("Error during final save:", err); } } logger.info("Coglog shutdown complete."); process.exit(0); };
    process.on('SIGINT', () => shutdown('SIGINT')); process.on('SIGTERM', () => shutdown('SIGTERM')); logger.info("--- Coglog Initialization Complete ---"); logger.info("Press Ctrl+C to exit.");
}

main().catch(error => { console.error("!!! Unhandled exception during startup/shutdown:", error); process.exit(1); });