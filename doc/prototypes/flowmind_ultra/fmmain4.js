"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || function (mod) {
    if (mod && mod.__esModule) return mod;
    var result = {};
    if (mod != null) for (var k in mod) if (k !== "default" && Object.prototype.hasOwnProperty.call(mod, k)) __createBinding(result, mod, k);
    __setModuleDefault(result, mod);
    return result;
};
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const fs = __importStar(require("fs/promises"));
const fsSync = __importStar(require("fs"));
const path = __importStar(require("path"));
const os = __importStar(require("os"));
const uuid_1 = require("uuid");
const chalk_1 = __importDefault(require("chalk")); // Added for rich text
const ollama_1 = require("@langchain/community/chat_models/ollama");
const ollama_2 = require("@langchain/community/embeddings/ollama");
const messages_1 = require("@langchain/core/messages");
const output_parsers_1 = require("@langchain/core/output_parsers");
const documents_1 = require("@langchain/core/documents");
const faiss_1 = require("@langchain/community/vectorstores/faiss");
// --- Constants ---
const DATA_DIR = path.join(os.homedir(), '.flowmind-console');
const STATE_FILE_PATH = path.join(DATA_DIR, 'flowmind_state.json');
const VECTOR_STORE_PATH = path.join(DATA_DIR, 'vector-store');
const OLLAMA_BASE_URL = process.env.OLLAMA_HOST || "http://localhost:11434";
const OLLAMA_MODEL = "hf.co/DevQuasar/Orion-zhen.phi-4-abliterated-GGUF:Q3_K_M"; // Updated model
const OLLAMA_EMBEDDING_MODEL = OLLAMA_MODEL; // Updated embedding model
const WORKER_INTERVAL_MS = 1500; // Slightly faster processing
const UI_REFRESH_INTERVAL_MS = 500; // Faster UI updates
const MAX_RETRIES = 3; // Allow one more retry
const DEFAULT_BELIEF_POS = 1.0;
const DEFAULT_BELIEF_NEG = 1.0;
const SAVE_DEBOUNCE_MS = 3000;
const MAX_LOG_LINES = 10; // For UI status messages
// --- Enums and Types ---
var Status;
(function (Status) {
    Status["PENDING"] = "PENDING";
    Status["ACTIVE"] = "ACTIVE";
    Status["WAITING"] = "WAITING";
    Status["DONE"] = "DONE";
    Status["FAILED"] = "FAILED";
    Status["STALE"] = "STALE"; // Indicates content might be outdated due to parent edit
})(Status || (Status = {}));
var Type;
(function (Type) {
    Type["INPUT"] = "INPUT";
    Type["GOAL"] = "GOAL";
    Type["STRATEGY"] = "STRATEGY";
    Type["ACTION"] = "ACTION";
    Type["OUTCOME"] = "OUTCOME";
    Type["QUERY"] = "QUERY";
    Type["USER_PROMPT"] = "USER_PROMPT";
    Type["SYSTEM_EVENT"] = "SYSTEM_EVENT"; // Log internal happenings
})(Type || (Type = {}));
var TermLogic;
(function (TermLogic) {
    TermLogic.Atom = (name) => ({ kind: 'Atom', name });
    TermLogic.Variable = (name) => ({ kind: 'Variable', name });
    TermLogic.Structure = (name, args) => ({ kind: 'Structure', name, args });
    TermLogic.ListTerm = (elements) => ({ kind: 'ListTerm', elements });
    function formatTerm(term, maxLength = 80) {
        if (!term)
            return chalk_1.default.gray('null');
        let str;
        switch (term.kind) {
            case 'Atom':
                str = term.name;
                break;
            case 'Variable':
                str = chalk_1.default.cyan(`?${term.name}`);
                break;
            case 'Structure':
                str = `${chalk_1.default.yellow(term.name)}(${term.args.map(t => formatTerm(t, Math.max(10, Math.floor(maxLength / (term.args.length || 1))))).join(', ')})`;
                break;
            case 'ListTerm':
                str = `[${term.elements.map(t => formatTerm(t, Math.max(10, Math.floor(maxLength / (term.elements.length || 1))))).join(', ')}]`;
                break;
            default: str = chalk_1.default.red('invalid_term');
        }
        return str.length > maxLength ? str.substring(0, maxLength - 3) + '...' : str;
    }
    TermLogic.formatTerm = formatTerm;
    // Used for generating simpler string representations for LLMs or memory
    function termToString(term) {
        switch (term.kind) {
            case 'Atom': return term.name;
            case 'Variable': return `?${term.name}`; // Usually shouldn't happen in final content
            case 'Structure': return `${term.name}: ${term.args.map(termToString).join('; ')}`;
            case 'ListTerm': return term.elements.map(termToString).join(', ');
            default: return '';
        }
    }
    TermLogic.termToString = termToString;
    // Basic heuristic to parse simple strings back to terms, primarily for user input
    function stringToTerm(input) {
        input = input.trim();
        // Very basic structure detection (improve if needed)
        if (input.includes(':') && input.includes('(') && input.endsWith(')')) {
            const match = input.match(/^([a-zA-Z0-9_]+)\s*\((.*)\)$/);
            if (match) {
                const name = match[1];
                const argsStr = match[2];
                // Simple comma split, doesn't handle nested structures well
                const args = argsStr.split(',').map(s => TermLogic.Atom(s.trim()));
                return TermLogic.Structure(name, args);
            }
        }
        // Basic list detection
        if (input.startsWith('[') && input.endsWith(']')) {
            const elements = input.slice(1, -1).split(',').map(s => TermLogic.Atom(s.trim()));
            return TermLogic.ListTerm(elements);
        }
        // Default to Atom
        return TermLogic.Atom(input);
    }
    TermLogic.stringToTerm = stringToTerm;
    function unify(term1, term2, bindings = new Map()) {
        const resolve = (term, currentBindings) => {
            if (term.kind === 'Variable' && currentBindings.has(term.name))
                return resolve(currentBindings.get(term.name), currentBindings);
            return term;
        };
        const t1 = resolve(term1, bindings);
        const t2 = resolve(term2, bindings);
        if (t1.kind === 'Variable') {
            if (t1.name === t2.name && t1.kind === t2.kind)
                return bindings; // ?X unifies with ?X
            // Occurs check (basic): prevent unifying ?X with f(?X)
            if (termContainsVariable(t2, t1.name))
                return null;
            const newBindings = new Map(bindings);
            newBindings.set(t1.name, t2);
            return newBindings;
        }
        if (t2.kind === 'Variable') {
            // Occurs check
            if (termContainsVariable(t1, t2.name))
                return null;
            const newBindings = new Map(bindings);
            newBindings.set(t2.name, t1);
            return newBindings;
        }
        if (t1.kind !== t2.kind)
            return null;
        switch (t1.kind) {
            case 'Atom': return t1.name === t2.name ? bindings : null;
            case 'Structure': {
                const s2 = t2;
                if (t1.name !== s2.name || t1.args.length !== s2.args.length)
                    return null;
                let currentBindings = bindings;
                for (let i = 0; i < t1.args.length; i++) {
                    const result = unify(t1.args[i], s2.args[i], currentBindings);
                    if (!result)
                        return null;
                    currentBindings = result;
                }
                return currentBindings;
            }
            case 'ListTerm': {
                const l2 = t2;
                if (t1.elements.length !== l2.elements.length)
                    return null;
                let currentBindings = bindings;
                for (let i = 0; i < t1.elements.length; i++) {
                    const result = unify(t1.elements[i], l2.elements[i], currentBindings);
                    if (!result)
                        return null;
                    currentBindings = result;
                }
                return currentBindings;
            }
        }
        return null; // Should not be reached
    }
    TermLogic.unify = unify;
    // Helper for occurs check in unify
    function termContainsVariable(term, varName) {
        switch (term.kind) {
            case 'Variable': return term.name === varName;
            case 'Structure': return term.args.some(arg => termContainsVariable(arg, varName));
            case 'ListTerm': return term.elements.some(el => termContainsVariable(el, varName));
            case 'Atom': return false;
            default: return false;
        }
    }
    function substitute(term, bindings) {
        const resolveAndSubstitute = (t) => {
            if (t.kind === 'Variable') {
                const boundValue = bindings.get(t.name);
                // If bound, substitute recursively, otherwise keep the variable
                return boundValue ? resolveAndSubstitute(boundValue) : t;
            }
            if (t.kind === 'Structure') {
                return { ...t, args: t.args.map(resolveAndSubstitute) };
            }
            if (t.kind === 'ListTerm') {
                return { ...t, elements: t.elements.map(resolveAndSubstitute) };
            }
            return t; // Atoms are returned as is
        };
        return resolveAndSubstitute(term);
    }
    TermLogic.substitute = substitute;
})(TermLogic || (TermLogic = {}));
// --- Belief Class ---
class Belief {
    positive_evidence;
    negative_evidence;
    constructor(pos = DEFAULT_BELIEF_POS, neg = DEFAULT_BELIEF_NEG) {
        this.positive_evidence = Math.max(0, pos); // Ensure non-negative
        this.negative_evidence = Math.max(0, neg);
    }
    // Laplace smoothing included in the formula
    score() {
        return (this.positive_evidence + 1) / (this.positive_evidence + this.negative_evidence + 2);
    }
    update(success) {
        if (success) {
            this.positive_evidence++;
        }
        else {
            this.negative_evidence++;
        }
    }
    toJSON() {
        return { positive_evidence: this.positive_evidence, negative_evidence: this.negative_evidence };
    }
    static fromJSON(data) {
        if (!data)
            return new Belief();
        return new Belief(data.positive_evidence, data.negative_evidence);
    }
    static DEFAULT = new Belief();
}
// --- Utility Functions ---
function generateId() { return (0, uuid_1.v4)(); }
function debounce(func, wait) {
    let timeout = null;
    const debounced = (...args) => {
        const later = () => {
            timeout = null;
            func(...args);
        };
        if (timeout)
            clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
    debounced.cancel = () => {
        if (timeout)
            clearTimeout(timeout);
        timeout = null;
    };
    return debounced;
}
function safeJsonParse(json, defaultValue) {
    if (!json)
        return defaultValue;
    try {
        return JSON.parse(json);
    }
    catch {
        // Log parsing error?
        return defaultValue;
    }
}
function sleep(ms) { return new Promise(resolve => setTimeout(resolve, ms)); }
function formatTimestamp(isoString) {
    if (!isoString)
        return '-------- --:--:--';
    try {
        const date = new Date(isoString);
        return date.toLocaleString();
    }
    catch {
        return 'Invalid Date';
    }
}
// --- Storage Classes ---
class ThoughtStore {
    thoughts = new Map();
    listeners = [];
    add(thought) {
        // Ensure necessary metadata
        thought.metadata = {
            timestamp_created: new Date().toISOString(),
            ...thought.metadata,
        };
        this.thoughts.set(thought.id, thought);
        this.notifyChange();
    }
    get(id) { return this.thoughts.get(id); }
    update(thought) {
        if (this.thoughts.has(thought.id)) {
            const existing = this.thoughts.get(thought.id);
            this.thoughts.set(thought.id, {
                ...existing, // Preserve original creation timestamp etc.
                ...thought,
                metadata: {
                    ...existing.metadata,
                    ...thought.metadata,
                    timestamp_modified: new Date().toISOString()
                }
            });
            this.notifyChange();
        }
    }
    delete(id) {
        const deleted = this.thoughts.delete(id);
        if (deleted)
            this.notifyChange();
        return deleted;
    }
    getPending() {
        return this.getAll().filter(t => t.status === Status.PENDING);
    }
    getAll() {
        return Array.from(this.thoughts.values()).sort((a, b) => Date.parse(a.metadata.timestamp_created ?? '0') - Date.parse(b.metadata.timestamp_created ?? '0'));
    }
    // Get all thoughts belonging to the same logical thread/root
    getAllByRootId(rootId) {
        return this.getAll()
            .filter(t => t.metadata.root_id === rootId || t.id === rootId)
            .sort((a, b) => Date.parse(a.metadata.timestamp_created ?? '0') - Date.parse(b.metadata.timestamp_created ?? '0'));
    }
    // Find thoughts waiting for a specific prompt ID
    findWaitingForPrompt(promptId) {
        return this.getAll().find(t => t.metadata.waiting_for_prompt_id === promptId && t.status === Status.WAITING);
    }
    // Find the original USER_PROMPT thought by its promptId
    findPromptThought(promptId) {
        return this.getAll().find(t => t.type === Type.USER_PROMPT && t.metadata.ui_context?.promptId === promptId);
    }
    searchByTag(tag) {
        return this.getAll().filter(t => t.metadata.tags?.includes(tag));
    }
    // Find direct children of a thought
    getChildren(parentId) {
        return this.getAll().filter(t => t.metadata.parent_id === parentId);
    }
    // Find the parent of a thought
    getParent(childId) {
        const child = this.get(childId);
        return child?.metadata.parent_id ? this.get(child.metadata.parent_id) : undefined;
    }
    toJSON() {
        const obj = {};
        this.thoughts.forEach((thought, id) => {
            obj[id] = {
                ...thought,
                belief: thought.belief.toJSON() // Serialize belief
            };
        });
        return obj;
    }
    static fromJSON(data) {
        const store = new ThoughtStore();
        for (const id in data) {
            const thoughtData = data[id];
            // Ensure belief is deserialized correctly
            const belief = Belief.fromJSON(thoughtData.belief);
            store.thoughts.set(id, { ...thoughtData, belief }); // Use thoughts.set directly to avoid extra notify
        }
        return store;
    }
    addChangeListener(listener) { this.listeners.push(listener); }
    removeChangeListener(listener) { this.listeners = this.listeners.filter(l => l !== listener); }
    notifyChange() { this.listeners.forEach(listener => listener()); }
}
class RuleStore {
    rules = new Map();
    add(rule) {
        rule.metadata = {
            timestamp_created: new Date().toISOString(),
            enabled: true,
            ...rule.metadata,
        };
        this.rules.set(rule.id, rule);
    }
    get(id) { return this.rules.get(id); }
    update(rule) {
        if (this.rules.has(rule.id)) {
            this.rules.set(rule.id, rule);
        }
    }
    delete(id) { return this.rules.delete(id); }
    getAll(enabledOnly = true) {
        const allRules = Array.from(this.rules.values());
        return enabledOnly ? allRules.filter(r => r.metadata.enabled !== false) : allRules;
    }
    searchByDescription(description) {
        const lowerDesc = description.toLowerCase();
        return this.getAll().filter(r => r.metadata.description?.toLowerCase().includes(lowerDesc));
    }
    toJSON() {
        const obj = {};
        this.rules.forEach((rule, id) => {
            obj[id] = { ...rule, belief: rule.belief.toJSON() };
        });
        return obj;
    }
    static fromJSON(data) {
        const store = new RuleStore();
        for (const id in data) {
            const ruleData = data[id];
            const belief = Belief.fromJSON(ruleData.belief);
            store.rules.set(id, { ...ruleData, belief }); // Use rules.set directly
        }
        return store;
    }
}
class MemoryStore {
    vectorStore = null;
    embeddings;
    vectorStorePath;
    isReady = false;
    initPromise = null;
    addQueue = [];
    isAdding = false;
    saveRequested = false;
    constructor(embeddingsService, storePath) {
        this.embeddings = embeddingsService;
        this.vectorStorePath = storePath;
        this.initPromise = this.initialize();
    }
    async initialize() {
        try {
            // Check if directory exists, FaissStore.load handles file existence
            await fs.access(path.dirname(this.vectorStorePath));
            if (fsSync.existsSync(this.vectorStorePath)) {
                this.vectorStore = await faiss_1.FaissStore.load(this.vectorStorePath, this.embeddings);
                console.log(chalk_1.default.blue(`Loaded vector store from ${this.vectorStorePath}`));
            }
            else {
                await this.createEmptyStore();
                console.log(chalk_1.default.blue(`Created new vector store at ${this.vectorStorePath}`));
            }
            this.isReady = true;
        }
        catch (error) {
            console.error(chalk_1.default.red(`Failed to initialize vector store at ${this.vectorStorePath}:`), error);
            // Attempt to create a new one if loading failed drastically
            if (error.code !== 'ENOENT') { // ENOENT is handled by checking fsSync.existsSync
                console.warn(chalk_1.default.yellow("Attempting to create a new empty vector store due to load error."));
                await this.createEmptyStore();
                this.isReady = true;
            }
            else if (!fsSync.existsSync(this.vectorStorePath)) {
                // If path doesn't exist but load failed for other reasons, still try to create
                await this.createEmptyStore();
                this.isReady = true;
            }
        }
    }
    async createEmptyStore() {
        // FaissStore requires at least one document to initialize
        const dummyDoc = new documents_1.Document({
            pageContent: "FlowMind system initialization marker.",
            metadata: { source_id: "system_init", type: "SYSTEM_EVENT", timestamp: new Date().toISOString(), root_id: "system", tags: ["system"] }
        });
        try {
            await fs.mkdir(path.dirname(this.vectorStorePath), { recursive: true });
            this.vectorStore = await faiss_1.FaissStore.fromDocuments([dummyDoc], this.embeddings);
            await this.vectorStore.save(this.vectorStorePath);
        }
        catch (saveError) {
            console.error(chalk_1.default.red("Failed to create or save initial empty vector store:"), saveError);
            this.isReady = false; // Mark as not ready if creation fails
        }
    }
    async ensureReady() {
        if (!this.initPromise) {
            // Should not happen if constructor logic is sound, but as a safeguard
            this.initPromise = this.initialize();
        }
        await this.initPromise;
        return this.isReady;
    }
    // Add thought data to be embedded and stored
    async addThought(thought) {
        if (!await this.ensureReady() || !this.vectorStore)
            return;
        const contentStr = TermLogic.termToString(thought.content);
        if (!contentStr)
            return; // Don't add empty content
        const doc = new documents_1.Document({
            pageContent: contentStr,
            metadata: {
                source_id: thought.id,
                type: thought.type,
                timestamp: thought.metadata.timestamp_created ?? new Date().toISOString(),
                root_id: thought.metadata.root_id,
                tags: thought.metadata.tags ?? []
            }
        });
        this.addQueue.push(doc);
        this.processAddQueue(); // Trigger processing (debounced internally)
    }
    // Delete and re-add thought (for updates)
    async updateThought(thought) {
        if (!await this.ensureReady() || !this.vectorStore)
            return;
        // Note: FaissStore doesn't directly support deletion/update by ID easily.
        // The standard approach is to rebuild or use metadata filtering if supported.
        // For simplicity here, we'll just add the new version. Search might return multiple versions.
        // A more robust solution would involve tracking document IDs and filtering during search,
        // or periodically rebuilding the index.
        console.warn(chalk_1.default.yellow(`MemoryStore update for ${thought.id} currently adds new version, old one remains.`));
        await this.addThought(thought);
    }
    async processAddQueue() {
        if (this.isAdding || this.addQueue.length === 0 || !this.isReady || !this.vectorStore)
            return;
        this.isAdding = true;
        const batch = this.addQueue.splice(0, this.addQueue.length); // Process all pending
        try {
            await this.vectorStore.addDocuments(batch);
            this.requestSave(); // Request save after successful add
        }
        catch (error) {
            console.error(chalk_1.default.red("Error adding documents to vector store:"), error);
            // Re-queue failed batch? Be careful about infinite loops.
            // this.addQueue.unshift(...batch);
        }
        finally {
            this.isAdding = false;
            // If more items were added while processing, run again
            if (this.addQueue.length > 0) {
                setTimeout(() => this.processAddQueue(), 100); // Small delay before next batch
            }
        }
    }
    async search(query, k = 5, filter) {
        if (!await this.ensureReady() || !this.vectorStore)
            return [];
        try {
            // Basic metadata filtering (example: filter by type)
            // Note: FaissStore's base similaritySearch doesn't have built-in metadata filtering.
            // You might need FaissStore.similaritySearch(query, k, filter) if using a version/variant
            // that supports it, or filter results post-search. Let's do post-search filtering.
            const results = await this.vectorStore.similaritySearchWithScore(query, k * 2); // Fetch more to filter
            const filteredResults = results.filter(([doc]) => {
                if (!filter)
                    return true;
                for (const key in filter) {
                    if (doc.metadata[key] !== filter[key])
                        return false;
                }
                return true;
            }).slice(0, k); // Limit to k after filtering
            return filteredResults.map(([doc, score]) => ({
                id: doc.metadata.source_id || generateId(), // Use source_id as the primary ID
                embedding: [], // Embeddings aren't typically returned by search
                content: doc.pageContent,
                metadata: {
                    timestamp: doc.metadata.timestamp,
                    type: doc.metadata.type,
                    source_id: doc.metadata.source_id,
                    root_id: doc.metadata.root_id,
                    tags: doc.metadata.tags,
                    score: score // Include similarity score
                }
            }));
        }
        catch (error) {
            console.error(chalk_1.default.red("Error searching vector store:"), error);
            return [];
        }
    }
    requestSave() {
        if (!this.saveRequested) {
            this.saveRequested = true;
            setTimeout(async () => {
                await this.save();
                this.saveRequested = false;
            }, SAVE_DEBOUNCE_MS); // Debounce saving
        }
    }
    async save() {
        if (this.vectorStore && this.isReady) {
            try {
                await this.vectorStore.save(this.vectorStorePath);
                // console.log(chalk.dim(`Vector store saved to ${this.vectorStorePath}`));
            }
            catch (error) {
                console.error(chalk_1.default.red("Error saving vector store:"), error);
            }
        }
        else if (!this.isReady) {
            console.warn(chalk_1.default.yellow("Vector store not ready, cannot save."));
        }
    }
}
// --- LLM Service ---
class LLMService {
    llm;
    embeddings;
    constructor() {
        this.llm = new ollama_1.ChatOllama({
            baseUrl: OLLAMA_BASE_URL,
            model: OLLAMA_MODEL,
            temperature: 0.6, // Slightly lower temp for more focused output
        });
        this.embeddings = new ollama_2.OllamaEmbeddings({
            model: OLLAMA_EMBEDDING_MODEL,
            baseUrl: OLLAMA_BASE_URL
        });
    }
    async generate(prompt, systemMessage) {
        const messages = [];
        if (systemMessage) {
            // Ollama specific way to add system message if needed, check langchain docs
            // messages.push(new SystemMessage(systemMessage));
        }
        messages.push(new messages_1.HumanMessage(prompt));
        try {
            // Add retry logic here if needed
            const result = await this.llm.pipe(new output_parsers_1.StringOutputParser()).invoke(messages);
            return result.trim();
        }
        catch (error) {
            console.error(chalk_1.default.red("LLM generation error:"), error.message);
            throw error; // Re-throw to be handled by caller
        }
    }
    async embed(text) {
        try {
            return await this.embeddings.embedQuery(text);
        }
        catch (error) {
            console.error(chalk_1.default.red("Embedding error:"), error.message);
            throw error; // Re-throw
        }
    }
}
// --- Tool Implementations ---
class LLMTool {
    name = "LLMTool";
    description = "Interacts with the LLM for generation.";
    async execute(actionTerm, context, triggerThought) {
        if (actionTerm.args.length < 2 || actionTerm.args[0].kind !== 'Atom' || actionTerm.args[1].kind !== 'Structure' || actionTerm.args[1].name !== 'prompt') {
            return TermLogic.Atom("error:invalid_llm_params(operation, prompt(text))");
        }
        const operation = actionTerm.args[0].name;
        const promptStructure = actionTerm.args[1];
        const promptTextTerm = promptStructure.args[0]; // Assuming prompt structure is prompt(Atom("text"))
        const targetTypeTerm = actionTerm.args.length > 2 && actionTerm.args[2].kind === 'Atom' ? actionTerm.args[2] : null; // Optional: Type hint for result
        if (!promptTextTerm)
            return TermLogic.Atom("error:missing_llm_prompt_text");
        const inputText = TermLogic.termToString(promptTextTerm);
        if (operation === 'generate') {
            context.ui?.log(chalk_1.default.blue(`LLM generating for [${triggerThought.id.substring(0, 6)}]...`));
            try {
                const response = await context.llmService.generate(inputText);
                context.ui?.log(chalk_1.default.blue(`LLM response received for [${triggerThought.id.substring(0, 6)}]`));
                // Attempt to create a thought of the target type
                const targetType = targetTypeTerm ? targetTypeTerm.name : Type.OUTCOME; // Default to outcome if not specified
                if (Object.values(Type).includes(targetType)) {
                    const newThought = {
                        id: generateId(),
                        type: targetType,
                        content: TermLogic.stringToTerm(response), // Parse response back to term
                        belief: new Belief(),
                        status: Status.PENDING,
                        metadata: {
                            root_id: triggerThought.metadata.root_id ?? triggerThought.id,
                            parent_id: triggerThought.id,
                            timestamp_created: new Date().toISOString(),
                            provenance: `llm_tool:${targetType}`
                        }
                    };
                    context.engine.addThought(newThought);
                    return TermLogic.Atom(`ok:generated_${targetType.toLowerCase()}:${newThought.id}`);
                }
                else {
                    // Fallback: return raw response if type is invalid/not provided
                    return TermLogic.Atom(response);
                }
            }
            catch (error) {
                context.ui?.log(chalk_1.default.red(`LLM generation failed for [${triggerThought.id.substring(0, 6)}]: ${error.message}`));
                return TermLogic.Atom(`error:llm_generation_failed:${error.message}`);
            }
        }
        // Removed 'embed' operation - embedding is handled by MemoryTool/MemoryStore now
        return TermLogic.Atom("error:unsupported_llm_operation");
    }
}
class MemoryTool {
    name = "MemoryTool";
    description = "Manages memory operations: add, search.";
    async execute(actionTerm, context, triggerThought) {
        if (actionTerm.args.length < 1 || actionTerm.args[0].kind !== 'Atom') {
            return TermLogic.Atom("error:missing_memory_operation");
        }
        const operation = actionTerm.args[0].name;
        if (operation === 'add') {
            // Add the triggering thought itself to memory
            try {
                await context.memoryStore.addThought(triggerThought);
                // Update metadata after successful queuing (actual embedding happens async)
                triggerThought.metadata.embedding_generated_at = new Date().toISOString();
                context.thoughtStore.update(triggerThought); // Update thought in store
                context.ui?.log(chalk_1.default.magenta(`Memory add queued for [${triggerThought.id.substring(0, 6)}]`));
                return TermLogic.Atom("ok:memory_add_queued");
            }
            catch (error) {
                context.ui?.log(chalk_1.default.red(`Memory add failed for [${triggerThought.id.substring(0, 6)}]: ${error.message}`));
                return TermLogic.Atom(`error:memory_add_failed:${error.message}`);
            }
        }
        else if (operation === 'search') {
            const queryTerm = actionTerm.args[1];
            const k = actionTerm.args[2]?.kind === 'Atom' ? parseInt(actionTerm.args[2].name, 10) : 3;
            const filterTerm = actionTerm.args[3]; // Optional filter Structure(key, value)
            if (!queryTerm)
                return TermLogic.Atom("error:missing_memory_search_query");
            const queryStr = TermLogic.termToString(queryTerm);
            let filter = undefined;
            if (filterTerm?.kind === 'Structure' && filterTerm.args.length === 2 && filterTerm.args[0].kind === 'Atom' && filterTerm.args[1].kind === 'Atom') {
                filter = { [filterTerm.args[0].name]: filterTerm.args[1].name };
            }
            context.ui?.log(chalk_1.default.magenta(`Memory search: "${queryStr.substring(0, 30)}..." (k=${k})`));
            try {
                const results = await context.memoryStore.search(queryStr, isNaN(k) ? 3 : k, filter);
                context.ui?.log(chalk_1.default.magenta(`Memory search found ${results.length} results.`));
                // Create an outcome thought containing the search results
                const resultListTerm = TermLogic.ListTerm(results.map(r => TermLogic.Structure("memory_result", [
                    TermLogic.Atom(r.id),
                    TermLogic.Atom(r.content),
                    TermLogic.Atom(r.metadata.type),
                    TermLogic.Atom(String(r.metadata.score?.toFixed(3) ?? 'N/A'))
                ])));
                const searchResultThought = {
                    id: generateId(),
                    type: Type.OUTCOME, // Represent search results as an outcome
                    content: resultListTerm,
                    belief: new Belief(),
                    status: Status.DONE, // Search result is immediately done
                    metadata: {
                        root_id: triggerThought.metadata.root_id ?? triggerThought.id,
                        parent_id: triggerThought.id,
                        timestamp_created: new Date().toISOString(),
                        provenance: 'memory_tool:search',
                        query: queryStr, // Store the original query
                        k: k,
                        filter: filter
                    }
                };
                context.engine.addThought(searchResultThought);
                return TermLogic.Atom(`ok:memory_search_completed:${searchResultThought.id}`);
            }
            catch (error) {
                context.ui?.log(chalk_1.default.red(`Memory search failed: ${error.message}`));
                return TermLogic.Atom(`error:memory_search_failed:${error.message}`);
            }
        }
        return TermLogic.Atom("error:unsupported_memory_operation");
    }
}
class UserInteractionTool {
    name = "UserInteractionTool";
    description = "Requests input from the user via the UI.";
    async execute(actionTerm, context, triggerThought) {
        if (actionTerm.args.length < 1 || actionTerm.args[0].kind !== 'Atom') {
            return TermLogic.Atom("error:missing_ui_operation");
        }
        const operation = actionTerm.args[0].name;
        if (operation === 'prompt') {
            const promptTextTerm = actionTerm.args[1];
            if (!promptTextTerm)
                return TermLogic.Atom("error:missing_prompt_text");
            const promptText = TermLogic.termToString(promptTextTerm);
            const promptId = generateId(); // Unique ID for this specific prompt instance
            // Create the thought representing the request for user input
            const promptThought = {
                id: generateId(), // ID of the thought *representing* the prompt
                type: Type.USER_PROMPT,
                content: TermLogic.Atom(promptText), // Store the prompt question itself
                belief: new Belief(), // Neutral belief initially
                status: Status.PENDING, // It's pending until the user responds
                metadata: {
                    root_id: triggerThought.metadata.root_id ?? triggerThought.id,
                    parent_id: triggerThought.id, // Link to the thought that needs the input
                    timestamp_created: new Date().toISOString(),
                    // Store the unique promptId in ui_context for the UI to track
                    ui_context: { promptText, promptId },
                    provenance: 'user_interaction_tool:prompt'
                }
            };
            context.engine.addThought(promptThought); // Add the prompt thought to the store
            // Mark the triggering thought as WAITING for this specific prompt
            triggerThought.status = Status.WAITING;
            triggerThought.metadata.waiting_for_prompt_id = promptId;
            context.thoughtStore.update(triggerThought); // Update the waiting thought
            context.ui?.log(chalk_1.default.green(`User prompt requested by [${triggerThought.id.substring(0, 6)}] (Prompt ID: ${promptId.substring(0, 6)})`));
            // The UI will detect the new USER_PROMPT thought and display it.
            // The engine doesn't wait here; it relies on the UI/user to provide input later.
            return TermLogic.Atom(`ok:prompt_requested:${promptId}`);
        }
        return TermLogic.Atom("error:unsupported_ui_operation");
    }
}
// Tool to suggest follow-up goals (less aggressive than before)
class GoalProposalTool {
    name = "GoalProposalTool";
    description = "Suggests a potential next goal based on context.";
    async execute(actionTerm, context, triggerThought) {
        if (actionTerm.args.length < 1 || actionTerm.args[0].kind !== 'Atom' || actionTerm.args[0].name !== 'suggest') {
            return TermLogic.Atom("error:invalid_goal_proposal_params(suggest, [context_term])");
        }
        const contextTerm = actionTerm.args.length > 1 ? actionTerm.args[1] : triggerThought.content;
        const contextStr = TermLogic.termToString(contextTerm);
        // Avoid proposing goals too often for the same root thread
        const recentSuggestions = context.thoughtStore.getAllByRootId(triggerThought.metadata.root_id ?? triggerThought.id)
            .filter(t => t.metadata.provenance === 'goal_proposal_tool:suggest' && t.metadata.parent_id === triggerThought.id);
        if (recentSuggestions.length > 0) {
            return TermLogic.Atom("ok:suggestion_skipped_recently_proposed");
        }
        context.ui?.log(chalk_1.default.blue(`Suggesting goal based on [${triggerThought.id.substring(0, 6)}]...`));
        const memoryResults = await context.memoryStore.search(`Relevant past goals or outcomes related to: ${contextStr}`, 2);
        const memoryContext = memoryResults.map(r => `- ${r.content}`).join("\n");
        const prompt = `Based on the current thought "${contextStr}" and potentially related past activities:\n${memoryContext}\n\nSuggest ONE concise, actionable *next* goal or task relevant to this context. Be specific. Output only the suggested goal text. If no clear next goal comes to mind, output "None".`;
        try {
            const suggestionText = await context.llmService.generate(prompt);
            if (suggestionText && suggestionText.trim() && suggestionText.toLowerCase() !== "none") {
                const suggestionThought = {
                    id: generateId(),
                    type: Type.INPUT, // Suggestion comes in as INPUT, user can promote it to GOAL
                    content: TermLogic.Atom(suggestionText.trim()),
                    belief: new Belief(1, 0), // Initial positive belief for suggestion
                    status: Status.PENDING, // User needs to decide what to do with it
                    metadata: {
                        root_id: triggerThought.metadata.root_id ?? triggerThought.id,
                        parent_id: triggerThought.id,
                        timestamp_created: new Date().toISOString(),
                        tags: ['suggested_goal', 'suggestion'],
                        provenance: 'goal_proposal_tool:suggest'
                    }
                };
                context.engine.addThought(suggestionThought);
                context.ui?.log(chalk_1.default.green(`Goal suggestion added: [${suggestionThought.id.substring(0, 6)}]`));
                return TermLogic.Atom(`ok:suggestion_created:${suggestionThought.id}`);
            }
            else {
                context.ui?.log(chalk_1.default.blue(`No goal suggestion generated for [${triggerThought.id.substring(0, 6)}]`));
                return TermLogic.Atom("ok:no_suggestion_generated");
            }
        }
        catch (error) {
            context.ui?.log(chalk_1.default.red(`Goal suggestion failed for [${triggerThought.id.substring(0, 6)}]: ${error.message}`));
            return TermLogic.Atom(`error:suggestion_generation_failed:${error.message}`);
        }
    }
}
// Tool for internal system operations
class CoreTool {
    name = "CoreTool";
    description = "Manages internal FlowMind operations like status updates, thought management.";
    async execute(actionTerm, context, triggerThought) {
        if (actionTerm.args.length < 1 || actionTerm.args[0].kind !== 'Atom') {
            return TermLogic.Atom("error:missing_core_operation");
        }
        const operation = actionTerm.args[0].name;
        switch (operation) {
            case 'set_status': {
                if (actionTerm.args.length < 3 || actionTerm.args[1].kind !== 'Atom' || actionTerm.args[2].kind !== 'Atom')
                    return TermLogic.Atom("error:invalid_set_status_params(set_status, target_id, status)");
                const targetId = actionTerm.args[1].name;
                const newStatusStr = actionTerm.args[2].name;
                const targetThought = context.thoughtStore.get(targetId);
                if (!targetThought)
                    return TermLogic.Atom(`error:target_thought_not_found:${targetId}`);
                // Validate Status Enum
                const newStatus = Object.values(Status).find(s => s === newStatusStr);
                if (!newStatus)
                    return TermLogic.Atom(`error:invalid_status_value:${newStatusStr}`);
                targetThought.status = newStatus;
                // Optionally clear error/waiting state when status changes
                if (newStatus !== Status.FAILED)
                    delete targetThought.metadata.error_info;
                if (newStatus !== Status.WAITING)
                    delete targetThought.metadata.waiting_for_prompt_id;
                context.thoughtStore.update(targetThought);
                context.ui?.log(chalk_1.default.cyan(`Status of [${targetId.substring(0, 6)}] set to ${newStatus}`));
                return TermLogic.Atom(`ok:status_set:${targetId}_to_${newStatus}`);
            }
            case 'add_thought': {
                if (actionTerm.args.length < 3 || actionTerm.args[1].kind !== 'Atom' || !actionTerm.args[2])
                    return TermLogic.Atom("error:invalid_add_thought_params(add_thought, type, content, [root_id], [parent_id])");
                const typeStr = actionTerm.args[1].name;
                const contentTerm = actionTerm.args[2];
                const rootIdTerm = actionTerm.args.length > 3 ? actionTerm.args[3] : null;
                const parentIdTerm = actionTerm.args.length > 4 ? actionTerm.args[4] : null;
                const type = Object.values(Type).find(t => t === typeStr);
                if (!type)
                    return TermLogic.Atom(`error:invalid_thought_type:${typeStr}`);
                const newThought = {
                    id: generateId(),
                    type,
                    content: contentTerm,
                    belief: new Belief(),
                    status: Status.PENDING,
                    metadata: {
                        root_id: rootIdTerm?.kind === 'Atom' ? rootIdTerm.name : triggerThought.metadata.root_id ?? triggerThought.id,
                        parent_id: parentIdTerm?.kind === 'Atom' ? parentIdTerm.name : triggerThought.id,
                        timestamp_created: new Date().toISOString(),
                        provenance: `core_tool:add_thought (triggered by ${triggerThought.id.substring(0, 6)})`
                    }
                };
                context.engine.addThought(newThought);
                context.ui?.log(chalk_1.default.cyan(`CoreTool added new thought [${newThought.id.substring(0, 6)}] type ${type}`));
                return TermLogic.Atom(`ok:thought_added:${newThought.id}`);
            }
            case 'update_thought_content': {
                if (actionTerm.args.length < 3 || actionTerm.args[1].kind !== 'Atom' || !actionTerm.args[2])
                    return TermLogic.Atom("error:invalid_update_content_params(update_thought_content, target_id, new_content)");
                const targetId = actionTerm.args[1].name;
                const newContentTerm = actionTerm.args[2];
                const targetThought = context.thoughtStore.get(targetId);
                if (!targetThought)
                    return TermLogic.Atom(`error:target_thought_not_found:${targetId}`);
                targetThought.content = newContentTerm;
                targetThought.status = Status.PENDING; // Mark for reprocessing
                targetThought.metadata.timestamp_modified = new Date().toISOString();
                delete targetThought.metadata.error_info; // Clear previous errors
                // Mark children as potentially stale
                const children = context.thoughtStore.getChildren(targetId);
                children.forEach(child => {
                    if (child.status !== Status.STALE) {
                        child.status = Status.STALE;
                        context.thoughtStore.update(child);
                    }
                });
                context.thoughtStore.update(targetThought);
                context.memoryStore.updateThought(targetThought); // Trigger memory update
                context.ui?.log(chalk_1.default.cyan(`Content of [${targetId.substring(0, 6)}] updated, marked PENDING.`));
                return TermLogic.Atom(`ok:thought_content_updated:${targetId}`);
            }
            case 'delete_thought': {
                if (actionTerm.args.length < 2 || actionTerm.args[1].kind !== 'Atom')
                    return TermLogic.Atom("error:invalid_delete_thought_params(delete_thought, target_id)");
                const targetId = actionTerm.args[1].name;
                const deleted = context.thoughtStore.delete(targetId);
                if (deleted) {
                    // Optionally delete children recursively? For now, just delete the target.
                    context.ui?.log(chalk_1.default.cyan(`Thought [${targetId.substring(0, 6)}] deleted.`));
                    return TermLogic.Atom(`ok:thought_deleted:${targetId}`);
                }
                else {
                    return TermLogic.Atom(`error:thought_not_found:${targetId}`);
                }
            }
            default:
                return TermLogic.Atom(`error:unsupported_core_operation:${operation}`);
        }
    }
}
// --- Tool Registry ---
class ToolRegistry {
    tools = new Map();
    register(tool) {
        if (this.tools.has(tool.name)) {
            console.warn(chalk_1.default.yellow(`Tool already registered: ${tool.name}. Overwriting.`));
        }
        this.tools.set(tool.name, tool);
    }
    get(name) {
        return this.tools.get(name);
    }
    listTools() {
        return Array.from(this.tools.values());
    }
    unregister(name) {
        return this.tools.delete(name);
    }
}
// --- Core Engine ---
class Engine {
    thoughtStore;
    ruleStore;
    memoryStore;
    llmService;
    toolRegistry;
    ui;
    activeThoughtIds = new Set(); // Track thoughts currently being processed
    batchSize = 3; // Process up to 3 thoughts concurrently
    maxConcurrent = 3; // Max parallel processing slots
    constructor(thoughtStore, ruleStore, memoryStore, llmService, toolRegistry, ui // Allow engine to log to UI
    ) {
        this.thoughtStore = thoughtStore;
        this.ruleStore = ruleStore;
        this.memoryStore = memoryStore;
        this.llmService = llmService;
        this.toolRegistry = toolRegistry;
        this.ui = ui;
    }
    setUI(ui) {
        this.ui = ui;
    }
    addThought(thought) {
        this.thoughtStore.add(thought);
        // Immediately queue for embedding if it's a type we want to remember
        if ([Type.INPUT, Type.GOAL, Type.STRATEGY, Type.OUTCOME].includes(thought.type)) {
            this.memoryStore.addThought(thought);
        }
    }
    // Selects the next thought to process based on status, priority, and belief
    sampleThought() {
        const pending = this.thoughtStore.getPending()
            .filter(t => !this.activeThoughtIds.has(t.id)); // Exclude already active thoughts
        if (pending.length === 0)
            return null;
        // Simple priority: Explicit priority > Higher belief score > Older thought
        pending.sort((a, b) => {
            const priorityA = a.metadata.priority ?? 0;
            const priorityB = b.metadata.priority ?? 0;
            if (priorityA !== priorityB)
                return priorityB - priorityA; // Higher priority first
            const scoreA = a.belief.score();
            const scoreB = b.belief.score();
            if (scoreA !== scoreB)
                return scoreB - scoreA; // Higher score first
            return Date.parse(a.metadata.timestamp_created ?? '0') - Date.parse(b.metadata.timestamp_created ?? '0'); // Older first
        });
        return pending[0]; // Return the highest priority thought
    }
    // Finds the best matching enabled rule for a thought
    findAndSelectRule(thought) {
        const potentialMatches = [];
        for (const rule of this.ruleStore.getAll(true)) { // Only consider enabled rules
            const bindings = TermLogic.unify(rule.pattern, thought.content);
            if (bindings !== null) {
                potentialMatches.push({ rule, bindings });
            }
        }
        if (potentialMatches.length === 0)
            return null;
        if (potentialMatches.length === 1)
            return potentialMatches[0];
        // Selection strategy: Prioritize rules with higher belief scores
        potentialMatches.sort((a, b) => b.rule.belief.score() - a.rule.belief.score());
        // Add randomness for ties? For now, just pick the best.
        return potentialMatches[0];
    }
    // Executes the action specified by a rule
    async executeAction(thought, rule, bindings) {
        // Add thought ID to bindings so tools can access it easily if needed
        bindings.set("ThoughtId", TermLogic.Atom(thought.id));
        bindings.set("ThoughtType", TermLogic.Atom(thought.type));
        bindings.set("ThoughtRootId", TermLogic.Atom(thought.metadata.root_id ?? thought.id));
        const boundActionTerm = TermLogic.substitute(rule.action, bindings);
        if (boundActionTerm.kind !== 'Structure') {
            this.handleFailure(thought, rule, `Invalid action term kind after substitution: ${boundActionTerm.kind}`);
            return false;
        }
        const tool = this.toolRegistry.get(boundActionTerm.name);
        if (!tool) {
            this.handleFailure(thought, rule, `Tool not found: ${boundActionTerm.name}`);
            return false;
        }
        this.ui?.log(chalk_1.default.yellow(`Executing Rule [${rule.id.substring(0, 6)}] -> Tool [${tool.name}] for Thought [${thought.id.substring(0, 6)}]`));
        try {
            const resultTerm = await tool.execute(boundActionTerm, {
                thoughtStore: this.thoughtStore,
                ruleStore: this.ruleStore,
                memoryStore: this.memoryStore,
                llmService: this.llmService,
                engine: this,
                ui: this.ui // Pass UI context
            }, thought);
            // Check the thought's status *after* execution, as the tool might have changed it (e.g., to WAITING)
            const currentThoughtState = this.thoughtStore.get(thought.id);
            if (!currentThoughtState)
                return false; // Thought was deleted during execution?
            const isErrorResult = resultTerm?.kind === 'Atom' && resultTerm.name.startsWith('error:');
            if (isErrorResult) {
                this.handleFailure(thought, rule, `Tool execution failed: ${resultTerm.name}`);
                return false; // Explicit failure
            }
            else if (currentThoughtState.status === Status.ACTIVE) {
                // If status is still ACTIVE (and not WAITING/FAILED/DONE), assume success for rule update
                this.handleSuccess(thought, rule);
                return true; // Assume success if no error and status didn't change to failure/waiting
            }
            else if (currentThoughtState.status === Status.DONE || currentThoughtState.status === Status.WAITING) {
                // If tool set it to DONE or WAITING, the action itself succeeded
                rule.belief.update(true); // Rule led to a valid state transition
                this.ruleStore.update(rule);
                return true;
            }
            else {
                // If status is PENDING, FAILED, STALE etc., treat as non-success for the rule
                this.handleFailure(thought, rule, `Action completed but thought status is ${currentThoughtState.status}`);
                return false;
            }
        }
        catch (error) {
            console.error(chalk_1.default.red(`Unhandled Tool execution exception: ${error.message}`), error.stack);
            this.handleFailure(thought, rule, `Tool execution exception: ${error.message}`);
            return false;
        }
    }
    // Handles thoughts that don't match any rules
    async handleNoRuleMatch(thought) {
        this.ui?.log(chalk_1.default.gray(`No rule match for [${thought.id.substring(0, 6)}] (${thought.type})`));
        let specificPrompt = "";
        let targetType = null;
        let askUser = false;
        // Default behavior: Try to generate the next logical step using LLM
        switch (thought.type) {
            case Type.INPUT:
                specificPrompt = `Given the input note: "${TermLogic.termToString(thought.content)}"\n\nDefine a specific, actionable goal based on this input. Output only the goal text.`;
                targetType = Type.GOAL;
                break;
            case Type.GOAL:
                specificPrompt = `Given the goal: "${TermLogic.termToString(thought.content)}"\n\nOutline 1-3 concise strategy steps to achieve this goal. Output only the steps, one per line.`;
                targetType = Type.STRATEGY;
                break;
            case Type.STRATEGY:
                // If a strategy has no rule, maybe ask user what happened or generate a generic outcome?
                // Let's ask the user for the outcome.
                specificPrompt = `What was the outcome or result of performing the strategy step: "${TermLogic.termToString(thought.content)}"?`;
                askUser = true;
                // targetType = Type.OUTCOME; // The user's response will be INPUT, which might trigger outcome generation later
                break;
            case Type.OUTCOME:
                // An outcome with no rule might trigger goal proposal or just be marked DONE.
                // Let's try suggesting a next goal.
                const proposalTool = this.toolRegistry.get("GoalProposalTool");
                if (proposalTool) {
                    await proposalTool.execute(TermLogic.Structure("suggest", []), {
                        thoughtStore: this.thoughtStore, ruleStore: this.ruleStore, memoryStore: this.memoryStore,
                        llmService: this.llmService, engine: this, ui: this.ui
                    }, thought);
                    // Mark the outcome DONE after attempting suggestion
                    thought.status = Status.DONE;
                    thought.belief.update(true); // Outcome reached an end state
                    this.thoughtStore.update(thought);
                    return; // Handled by suggestion tool
                }
                else {
                    // Default: Mark DONE if no suggestion tool
                    thought.status = Status.DONE;
                    thought.belief.update(true);
                    this.thoughtStore.update(thought);
                    return;
                }
                break; // Should not be reached if suggestion tool runs
            default:
                // For other types (QUERY, USER_PROMPT, etc.) with no rules, ask user.
                specificPrompt = `The thought (${thought.type}: ${TermLogic.formatTerm(thought.content)}) has no applicable rules. What should be done next?`;
                askUser = true;
                break;
        }
        try {
            if (askUser && specificPrompt) {
                const promptId = generateId();
                const promptThought = {
                    id: generateId(),
                    type: Type.USER_PROMPT,
                    content: TermLogic.Atom(specificPrompt),
                    belief: new Belief(),
                    status: Status.PENDING,
                    metadata: {
                        root_id: thought.metadata.root_id ?? thought.id,
                        parent_id: thought.id,
                        timestamp_created: new Date().toISOString(),
                        ui_context: { promptText: specificPrompt, promptId },
                        provenance: 'engine:no_rule_match_prompt'
                    }
                };
                this.addThought(promptThought);
                thought.status = Status.WAITING;
                thought.metadata.waiting_for_prompt_id = promptId;
                this.thoughtStore.update(thought);
                this.ui?.log(chalk_1.default.green(`No rule match for [${thought.id.substring(0, 6)}], asking user (Prompt ID: ${promptId.substring(0, 6)})`));
            }
            else if (specificPrompt && targetType) {
                // Use LLM to generate the next step
                this.ui?.log(chalk_1.default.blue(`No rule match for [${thought.id.substring(0, 6)}], using LLM to generate ${targetType}...`));
                const resultText = await this.llmService.generate(specificPrompt);
                const results = resultText.split('\n').map(s => s.trim()).filter(s => s && s.length > 1); // Filter empty/short lines
                if (results.length > 0) {
                    results.forEach(resText => {
                        this.addThought({
                            id: generateId(),
                            type: targetType,
                            content: TermLogic.stringToTerm(resText),
                            belief: new Belief(),
                            status: Status.PENDING,
                            metadata: {
                                root_id: thought.metadata.root_id ?? thought.id,
                                parent_id: thought.id,
                                timestamp_created: new Date().toISOString(),
                                provenance: 'llm_default_handler'
                            }
                        });
                    });
                    thought.status = Status.DONE; // Original thought is done as next step generated
                    thought.belief.update(true); // Successfully handled by generating next step
                    this.thoughtStore.update(thought);
                    this.ui?.log(chalk_1.default.blue(`LLM generated ${results.length} ${targetType}(s) for [${thought.id.substring(0, 6)}]`));
                }
                else {
                    // LLM didn't provide a useful response
                    this.handleFailure(thought, null, "No rule match and LLM default handler failed to generate content.");
                }
            }
            else {
                // No specific action defined, mark as failed? Or leave PENDING?
                // Let's mark as FAILED if it's not an INPUT type (INPUTs might just sit there).
                if (thought.type !== Type.INPUT) {
                    this.handleFailure(thought, null, "No rule match and no default action applicable.");
                }
                else {
                    // Leave INPUT as PENDING if no rules match initially
                    thought.status = Status.PENDING;
                    this.thoughtStore.update(thought);
                }
            }
        }
        catch (error) {
            this.ui?.log(chalk_1.default.red(`Error in default handler for [${thought.id.substring(0, 6)}]: ${error.message}`));
            this.handleFailure(thought, null, `No rule match handler failed: ${error.message}`);
        }
    }
    // Handles successful processing of a thought by a rule
    handleSuccess(thought, rule) {
        // Check current state in case it was modified by the tool
        const currentThought = this.thoughtStore.get(thought.id);
        if (!currentThought || currentThought.status !== Status.ACTIVE) {
            // Already handled (e.g., set to WAITING, DONE, or FAILED by the tool)
            // Still update rule belief if it wasn't a failure
            if (currentThought?.status !== Status.FAILED) {
                rule.belief.update(true);
                this.ruleStore.update(rule);
            }
            return;
        }
        // --- Goal Persistence Logic ---
        // Goals are only marked DONE if they have successful child outcomes,
        // or if explicitly marked done by user/specific rule.
        // For now, a successful rule execution on a GOAL might lead to STRATEGY,
        // so we keep the GOAL as ACTIVE or PENDING until children complete/fail.
        // Let's tentatively mark it DONE here, but this needs refinement.
        // A better approach might be a separate "check_goal_completion" rule/process.
        currentThought.status = Status.DONE;
        currentThought.belief.update(true);
        delete currentThought.metadata.error_info;
        delete currentThought.metadata.retries;
        currentThought.metadata.rule_id = rule.id; // Record the successful rule
        rule.belief.update(true);
        this.ruleStore.update(rule);
        this.thoughtStore.update(currentThought);
        this.ui?.log(chalk_1.default.green(`Thought [${currentThought.id.substring(0, 6)}] processed successfully by Rule [${rule.id.substring(0, 6)}] -> ${currentThought.status}`));
        // Trigger memory update on success for relevant types
        if ([Type.INPUT, Type.GOAL, Type.STRATEGY, Type.OUTCOME].includes(currentThought.type)) {
            this.memoryStore.addThought(currentThought); // Re-add to update embedding/metadata if needed
        }
        // --- Check Parent Goal Status on Child Success ---
        this.checkUpdateParentGoalStatus(currentThought);
    }
    // Handles failed processing or tool execution error
    handleFailure(thought, rule, errorInfo) {
        // Check current state first
        const currentThought = this.thoughtStore.get(thought.id);
        if (!currentThought || currentThought.status === Status.FAILED)
            return; // Already failed
        const retries = (currentThought.metadata.retries ?? 0) + 1;
        currentThought.metadata.error_info = errorInfo.substring(0, 250); // Limit error length
        currentThought.metadata.retries = retries;
        currentThought.belief.update(false); // Lower thought belief on failure
        if (rule) {
            rule.belief.update(false); // Lower rule belief on failure
            this.ruleStore.update(rule);
            currentThought.metadata.rule_id = rule.id; // Record the failing rule
        }
        // --- Goal Persistence: Don't fail goals easily ---
        if (currentThought.type === Type.GOAL && retries < MAX_RETRIES) {
            // Revert goal to PENDING to try again or different strategies
            currentThought.status = Status.PENDING;
            this.ui?.log(chalk_1.default.yellow(`Goal [${currentThought.id.substring(0, 6)}] failed processing, retrying (Attempt ${retries}/${MAX_RETRIES}). Error: ${errorInfo}`));
        }
        else if (retries >= MAX_RETRIES) {
            currentThought.status = Status.FAILED;
            this.ui?.log(chalk_1.default.red(`Thought [${currentThought.id.substring(0, 6)}] FAILED after ${retries} retries. Error: ${errorInfo}`));
            // --- Check Parent Goal Status on Child Failure ---
            this.checkUpdateParentGoalStatus(currentThought);
        }
        else {
            // For non-goals, retry by setting back to PENDING
            currentThought.status = Status.PENDING;
            this.ui?.log(chalk_1.default.yellow(`Thought [${currentThought.id.substring(0, 6)}] failed processing, retrying (Attempt ${retries}/${MAX_RETRIES}). Error: ${errorInfo}`));
        }
        this.thoughtStore.update(currentThought);
    }
    // Check parent goal status when a child finishes (DONE or FAILED)
    checkUpdateParentGoalStatus(childThought) {
        if (!childThought.metadata.parent_id)
            return;
        const parent = this.thoughtStore.getParent(childThought.id);
        if (!parent || parent.type !== Type.GOAL || parent.status === Status.DONE || parent.status === Status.FAILED) {
            return; // Only update active/pending goals
        }
        const siblings = this.thoughtStore.getChildren(parent.id);
        const activeSiblings = siblings.filter(s => s.status === Status.PENDING || s.status === Status.ACTIVE || s.status === Status.WAITING);
        const failedSiblings = siblings.filter(s => s.status === Status.FAILED);
        if (failedSiblings.length > 0) {
            // If any child FAILED, the Goal might need review. Mark it PENDING.
            if (parent.status !== Status.PENDING) {
                parent.status = Status.PENDING;
                parent.metadata.error_info = `Child task [${failedSiblings[0].id.substring(0, 6)}] failed. Review goal.`;
                parent.belief.update(false); // Negative evidence for goal
                this.thoughtStore.update(parent);
                this.ui?.log(chalk_1.default.yellow(`Goal [${parent.id.substring(0, 6)}] marked PENDING due to failed child [${failedSiblings[0].id.substring(0, 6)}]`));
            }
        }
        else if (activeSiblings.length === 0) {
            // All children are DONE (or STALE), potentially mark Goal as DONE.
            // Requires explicit confirmation or a specific rule for now.
            // Let's leave it ACTIVE/PENDING - requires user action or specific rule to mark DONE.
            // parent.status = Status.DONE; // Tentative - maybe too aggressive
            // parent.belief.update(true);
            // this.thoughtStore.update(parent);
            // this.ui?.log(chalk.green(`All children of Goal [${parent.id.substring(0, 6)}] finished. Goal remains ${parent.status}.`));
        }
    }
    // Main processing loop step (called periodically)
    async processBatch() {
        let processedCount = 0;
        const promises = [];
        for (let i = 0; i < this.batchSize && this.activeThoughtIds.size < this.maxConcurrent; ++i) {
            const thought = this.sampleThought();
            if (!thought)
                break; // No more pending thoughts
            // Double check it's not already active (should be handled by sampleThought, but safety first)
            if (this.activeThoughtIds.has(thought.id))
                continue;
            this.activeThoughtIds.add(thought.id);
            processedCount++;
            // Mark as active *before* async operation
            thought.status = Status.ACTIVE;
            thought.metadata.agent_id = 'engine_worker';
            this.thoughtStore.update(thought); // Update status in store
            promises.push((async () => {
                let success = false;
                try {
                    const match = this.findAndSelectRule(thought);
                    if (match) {
                        success = await this.executeAction(thought, match.rule, match.bindings);
                    }
                    else {
                        // Handle thoughts with no matching rules (might generate new thoughts, ask user, or fail)
                        await this.handleNoRuleMatch(thought);
                        // Check status after handling no match
                        const finalState = this.thoughtStore.get(thought.id);
                        // Success if it's DONE, WAITING (implies user interaction initiated), or STALE. FAILED is not success.
                        success = finalState ? [Status.DONE, Status.WAITING, Status.STALE].includes(finalState.status) : false;
                    }
                }
                catch (error) {
                    console.error(chalk_1.default.red(`Unhandled error during thought processing [${thought.id}]: ${error.message}`), error.stack);
                    this.handleFailure(thought, null, `Unhandled processing exception: ${error.message}`);
                    success = false;
                }
                finally {
                    this.activeThoughtIds.delete(thought.id); // Remove from active set *after* completion/failure
                    // Ensure thought doesn't get stuck in ACTIVE state if something went wrong
                    const finalThoughtState = this.thoughtStore.get(thought.id);
                    if (finalThoughtState?.status === Status.ACTIVE) {
                        this.handleFailure(finalThoughtState, null, "Processing ended unexpectedly while ACTIVE.");
                        success = false;
                    }
                }
                return success;
            })());
        }
        // Wait for all concurrent processes in this batch to finish
        const results = await Promise.all(promises);
        return results.filter(r => r).length; // Return count of successful operations
    }
    // Handles user response to a prompt
    async handlePromptResponse(promptId, responseText) {
        const waitingThought = this.thoughtStore.findWaitingForPrompt(promptId);
        const promptThought = this.thoughtStore.findPromptThought(promptId);
        if (promptThought && promptThought.status === Status.PENDING) {
            // Mark the prompt thought itself as DONE since it's been answered
            promptThought.status = Status.DONE;
            promptThought.belief.update(true);
            this.thoughtStore.update(promptThought);
        }
        else {
            console.warn(chalk_1.default.yellow(`Prompt thought [${promptId.substring(0, 6)}] not found or not pending.`));
            // Continue anyway, maybe the waiting thought still exists
        }
        if (!waitingThought) {
            this.ui?.log(chalk_1.default.red(`No thought found waiting for prompt ID [${promptId.substring(0, 6)}]`));
            return false;
        }
        this.ui?.log(chalk_1.default.green(`Received response for prompt [${promptId.substring(0, 6)}], updating thought [${waitingThought.id.substring(0, 6)}]`));
        // Create a new thought representing the user's response
        const responseThought = {
            id: generateId(),
            type: Type.INPUT, // User responses are treated as new input
            content: TermLogic.stringToTerm(responseText), // Convert text to term
            belief: new Belief(1, 0), // High initial belief in user input
            status: Status.PENDING, // Process this new input
            metadata: {
                root_id: waitingThought.metadata.root_id ?? waitingThought.id,
                parent_id: waitingThought.id, // Link response back to the thought that was waiting
                timestamp_created: new Date().toISOString(),
                response_to_prompt_id: promptId, // Link to the specific prompt ID
                tags: ['user_response'],
                provenance: 'user_prompt_response'
            }
        };
        this.addThought(responseThought); // Add the response thought
        // Update the original waiting thought
        waitingThought.status = Status.PENDING; // It's no longer waiting, ready for processing again
        delete waitingThought.metadata.waiting_for_prompt_id;
        waitingThought.belief.update(true); // Positive update for receiving the needed input
        // Optionally add response ID to metadata?
        // waitingThought.metadata.last_response_id = responseThought.id;
        this.thoughtStore.update(waitingThought);
        return true;
    }
    // Edit the content of an existing thought
    async editThoughtContent(thoughtId, newContentText) {
        const thought = this.thoughtStore.get(thoughtId);
        if (!thought) {
            this.ui?.log(chalk_1.default.red(`Cannot edit: Thought [${thoughtId.substring(0, 6)}] not found.`));
            return false;
        }
        const newContentTerm = TermLogic.stringToTerm(newContentText);
        // Use CoreTool for consistency? Or handle directly? Direct for now.
        thought.content = newContentTerm;
        thought.status = Status.PENDING; // Mark for reprocessing
        thought.metadata.timestamp_modified = new Date().toISOString();
        delete thought.metadata.error_info; // Clear previous errors
        delete thought.metadata.retries;
        thought.belief = new Belief(); // Reset belief? Or keep? Let's reset.
        // Mark direct children as STALE
        const children = this.thoughtStore.getChildren(thoughtId);
        children.forEach(child => {
            if (child.status !== Status.STALE) {
                child.status = Status.STALE;
                child.metadata.error_info = `Parent [${thoughtId.substring(0, 6)}] edited.`;
                this.thoughtStore.update(child);
                this.ui?.log(chalk_1.default.yellow(`Marked child [${child.id.substring(0, 6)}] as STALE due to parent edit.`));
            }
        });
        this.thoughtStore.update(thought);
        this.memoryStore.updateThought(thought); // Trigger memory vector update
        this.ui?.log(chalk_1.default.green(`Thought [${thoughtId.substring(0, 6)}] content updated and marked PENDING.`));
        return true;
    }
}
// --- Persistence ---
async function saveState(thoughtStore, ruleStore, memoryStore) {
    try {
        await fs.mkdir(DATA_DIR, { recursive: true });
        const state = {
            thoughts: thoughtStore.toJSON(),
            rules: ruleStore.toJSON()
        };
        await fs.writeFile(STATE_FILE_PATH, JSON.stringify(state, null, 2));
        await memoryStore.save(); // Ensure vector store is saved too
        // console.log(chalk.dim('State saved.'));
    }
    catch (error) {
        console.error(chalk_1.default.red("Error saving state:"), error);
    }
}
// Debounced save function
const debouncedSaveState = debounce(saveState, SAVE_DEBOUNCE_MS);
async function loadState(thoughtStore, ruleStore, memoryStore) {
    try {
        if (!fsSync.existsSync(STATE_FILE_PATH)) {
            console.log(chalk_1.default.yellow('State file not found. Starting fresh.'));
            await memoryStore.ensureReady(); // Ensure vector store is initialized even if state file is missing
            return;
        }
        const data = await fs.readFile(STATE_FILE_PATH, 'utf-8');
        const state = safeJsonParse(data, { thoughts: {}, rules: {} });
        // Load thoughts
        const loadedThoughts = ThoughtStore.fromJSON(state.thoughts);
        loadedThoughts.getAll().forEach(t => thoughtStore.add(t)); // Add thoughts to the main store
        // Load rules
        const loadedRules = RuleStore.fromJSON(state.rules);
        loadedRules.getAll(false).forEach(r => ruleStore.add(r)); // Add rules to the main store
        // Initialize memory store (loads Faiss index)
        await memoryStore.ensureReady();
        console.log(chalk_1.default.green(`Loaded ${thoughtStore.getAll().length} thoughts and ${ruleStore.getAll(false).length} rules.`));
    }
    catch (error) {
        console.error(chalk_1.default.red("Error loading state:"), error);
        // Ensure memory store is initialized even if state loading fails
        await memoryStore.ensureReady();
    }
}
// --- Full Screen TUI ---
// Helper to truncate string and pad
function truncatePad(str, length) {
    const truncated = str.length > length ? str.substring(0, length - 1) + '…' : str;
    return truncated.padEnd(length);
}
// Helper to style status
function styleStatus(status) {
    switch (status) {
        case Status.PENDING: return chalk_1.default.yellow(status);
        case Status.ACTIVE: return chalk_1.default.blueBright(status);
        case Status.WAITING: return chalk_1.default.cyan(status);
        case Status.DONE: return chalk_1.default.green(status);
        case Status.FAILED: return chalk_1.default.redBright.bold(status);
        case Status.STALE: return chalk_1.default.gray.italic(status);
        default: return status;
    }
}
// Helper to style type
function styleType(type) {
    switch (type) {
        case Type.INPUT: return chalk_1.default.white(type);
        case Type.GOAL: return chalk_1.default.magentaBright.bold(type);
        case Type.STRATEGY: return chalk_1.default.blue(type);
        case Type.ACTION: return chalk_1.default.cyan(type);
        case Type.OUTCOME: return chalk_1.default.greenBright(type);
        case Type.USER_PROMPT: return chalk_1.default.yellowBright.bold(type);
        case Type.QUERY: return chalk_1.default.gray(type);
        case Type.SYSTEM_EVENT: return chalk_1.default.dim(type);
        default: return type;
    }
}
class FullScreenTUI {
    thoughtStore;
    systemControl;
    engineIsRunning;
    refreshIntervalId = null;
    logs = [];
    inputBuffer = "";
    mode = 'main';
    selectedThoughtIndex = 0;
    selectedPromptIndex = 0;
    currentThoughtList = []; // Filtered/sorted list currently displayed
    currentPromptList = []; // Pending prompts
    viewMode = 'all'; // 'all' shows roots, 'root' shows thread
    focusedRootId = null;
    editingThoughtId = null; // ID of thought being edited
    respondingToPromptId = null; // ID of prompt being responded to
    screenWidth = process.stdout.columns || 80;
    screenHeight = process.stdout.rows || 24;
    mainAreaHeight = 0; // Calculated height for thought list
    promptAreaHeight = 0; // Calculated height for prompts
    lastRenderTime = 0;
    constructor(thoughtStore, systemControl, engineIsRunning) {
        this.thoughtStore = thoughtStore;
        this.systemControl = systemControl;
        this.engineIsRunning = engineIsRunning;
        // Re-render when thoughts change
        this.thoughtStore.addChangeListener(() => this.render(true));
    }
    log(message) {
        const timestamp = new Date().toLocaleTimeString();
        this.logs.push(chalk_1.default.dim(`[${timestamp}] `) + message);
        if (this.logs.length > MAX_LOG_LINES) {
            this.logs.shift();
        }
        this.render(); // Re-render to show new log
    }
    start() {
        process.stdout.write('\x1B[?1049h'); // Enter alternative screen buffer
        process.stdout.write('\x1B[?25l'); // Hide cursor
        process.stdin.setRawMode(true);
        process.stdin.resume();
        process.stdin.setEncoding('utf8');
        this.updateScreenSize();
        process.stdin.on('data', this.handleKeyPress.bind(this));
        process.stdout.on('resize', this.handleResize.bind(this));
        this.refreshIntervalId = setInterval(() => this.render(), UI_REFRESH_INTERVAL_MS);
        this.render(true); // Initial render
        this.log(chalk_1.default.blue("FlowMind TUI started. Press '?' for help."));
    }
    stop() {
        if (this.refreshIntervalId)
            clearInterval(this.refreshIntervalId);
        process.stdin.setRawMode(false);
        process.stdin.pause();
        process.stdout.write('\x1B[?25h'); // Show cursor
        process.stdout.write('\x1B[?1049l'); // Exit alternative screen buffer
        console.log(chalk_1.default.blue("\nFlowMind TUI stopped."));
    }
    updateScreenSize() {
        this.screenWidth = process.stdout.columns || 80;
        this.screenHeight = process.stdout.rows || 24;
        // Layout: Header(1), Main(variable), Prompt Header(1), Prompt(variable), Logs(MAX_LOG_LINES), Input(1), Footer(1)
        const fixedHeight = 1 + 1 + MAX_LOG_LINES + 1 + 1;
        const availableHeight = this.screenHeight - fixedHeight;
        // Split available height, giving more to main area
        this.promptAreaHeight = Math.max(0, Math.min(5, Math.floor(availableHeight * 0.3))); // Max 5 lines for prompts
        this.mainAreaHeight = Math.max(0, availableHeight - this.promptAreaHeight);
    }
    handleResize() {
        this.updateScreenSize();
        this.render(true); // Force redraw on resize
    }
    async handleKeyPress(key) {
        if (key === '\u0003') { // Ctrl+C
            await this.systemControl('quit');
            return;
        }
        if (this.mode === 'input' || this.mode === 'editing' || this.mode === 'prompt_response') {
            this.handleInputModeKeys(key);
        }
        else { // Main mode navigation / commands
            this.handleMainModeKeys(key);
        }
        this.render(); // Update UI based on interaction
    }
    handleInputModeKeys(key) {
        switch (key) {
            case '\r': // Enter
                const command = this.inputBuffer.trim();
                this.inputBuffer = "";
                if (command) {
                    if (this.mode === 'editing' && this.editingThoughtId) {
                        this.systemControl('edit', [this.editingThoughtId, command]);
                        this.editingThoughtId = null;
                    }
                    else if (this.mode === 'prompt_response' && this.respondingToPromptId) {
                        this.systemControl('respond', [this.respondingToPromptId, command]);
                        this.respondingToPromptId = null;
                    }
                    else { // Normal command input
                        const [cmd, ...args] = command.split(' ');
                        this.systemControl(cmd.toLowerCase(), args);
                    }
                }
                this.mode = 'main'; // Exit input mode
                break;
            case '\u001b': // Escape
                this.inputBuffer = "";
                this.mode = 'main';
                this.editingThoughtId = null;
                this.respondingToPromptId = null;
                break;
            case '\u007f': // Backspace
            case '\b':
                this.inputBuffer = this.inputBuffer.slice(0, -1);
                break;
            default:
                // Allow printable characters (basic check)
                if (key >= ' ' && key <= '~') {
                    this.inputBuffer += key;
                }
                break;
        }
    }
    handleMainModeKeys(key) {
        switch (key.toLowerCase()) {
            // Navigation
            case '\u001b[A': // Up Arrow
                if (this.currentPromptList.length > 0 && this.selectedPromptIndex > -1) {
                    this.selectedPromptIndex = Math.max(0, this.selectedPromptIndex - 1);
                }
                else if (this.currentThoughtList.length > 0) {
                    this.selectedThoughtIndex = Math.max(0, this.selectedThoughtIndex - 1);
                    this.selectedPromptIndex = -1; // De-select prompt
                }
                break;
            case '\u001b[B': // Down Arrow
                if (this.currentThoughtList.length > 0 && this.selectedPromptIndex === -1) {
                    this.selectedThoughtIndex = Math.min(this.currentThoughtList.length - 1, this.selectedThoughtIndex + 1);
                }
                else if (this.currentPromptList.length > 0) {
                    if (this.selectedPromptIndex === -1)
                        this.selectedPromptIndex = 0; // Select first prompt
                    else
                        this.selectedPromptIndex = Math.min(this.currentPromptList.length - 1, this.selectedPromptIndex + 1);
                }
                break;
            case '\u001b[D': // Left Arrow (Go back up in hierarchy/view)
                if (this.viewMode === 'root') {
                    this.viewMode = 'all';
                    this.focusedRootId = null;
                    this.selectedThoughtIndex = 0; // Reset selection
                    this.render(true); // Force list refresh
                }
                break;
            case '\u001b[C': // Right Arrow / Enter (Focus/Select/Action)
            case '\r': // Enter
                this.handleSelectAction();
                break;
            // Commands / Mode Changes
            case 'a': // Add new thought
            case '+':
                this.mode = 'input';
                this.inputBuffer = "add ";
                break;
            case 'e': // Edit selected thought
                this.handleEditSelected();
                break;
            case 'd': // Delete selected thought
            case '-':
                this.handleDeleteSelected();
                break;
            case 'r': // Respond to selected prompt
                this.handleRespondSelected();
                break;
            case ' ': // Toggle Run/Pause
                this.systemControl(this.engineIsRunning() ? 'pause' : 'run');
                break;
            case 's': // Step (if paused)
                if (!this.engineIsRunning())
                    this.systemControl('step');
                else
                    this.log(chalk_1.default.yellow("System must be paused to step. Press [Space] to pause."));
                break;
            case 'v': // Change View
                this.viewMode = this.viewMode === 'all' ? 'root' : 'all';
                if (this.viewMode === 'all')
                    this.focusedRootId = null;
                else
                    this.handleSelectAction(); // Enter root view for selected item
                this.render(true);
                break;
            case 't': // Tag selected thought
                this.handleTagSelected();
                break;
            case 'q': // Quit
                this.systemControl('quit');
                break;
            case '?': // Help
                this.showHelp();
                break;
            // Add more keybindings here (e.g., for saving, searching)
        }
    }
    // Action when Enter/Right Arrow is pressed in main mode
    handleSelectAction() {
        if (this.selectedPromptIndex > -1 && this.currentPromptList.length > this.selectedPromptIndex) {
            // If a prompt is selected, initiate response
            this.handleRespondSelected();
        }
        else if (this.selectedThoughtIndex > -1 && this.currentThoughtList.length > this.selectedThoughtIndex) {
            const selectedThought = this.currentThoughtList[this.selectedThoughtIndex];
            if (this.viewMode === 'all') {
                // Enter root view mode focused on this thought's root
                this.viewMode = 'root';
                this.focusedRootId = selectedThought.metadata.root_id ?? selectedThought.id;
                this.selectedThoughtIndex = 0; // Reset selection within the new view
                this.render(true); // Force list refresh
            }
            else {
                // Already in root view, maybe edit or show details? For now, let's try editing.
                this.handleEditSelected();
            }
        }
    }
    handleEditSelected() {
        if (this.viewMode === 'root' && this.selectedThoughtIndex > -1 && this.currentThoughtList.length > this.selectedThoughtIndex) {
            const thought = this.currentThoughtList[this.selectedThoughtIndex];
            this.editingThoughtId = thought.id;
            this.mode = 'editing';
            // Pre-fill with simple string version of content
            this.inputBuffer = TermLogic.termToString(thought.content);
            this.log(chalk_1.default.yellow(`Editing Thought [${thought.id.substring(0, 6)}]. Press Enter to save, Esc to cancel.`));
        }
        else {
            this.log(chalk_1.default.yellow("Select a thought in 'root' view (Enter/Right Arrow) to edit."));
        }
    }
    handleDeleteSelected() {
        if (this.selectedThoughtIndex > -1 && this.currentThoughtList.length > this.selectedThoughtIndex) {
            const thought = this.currentThoughtList[this.selectedThoughtIndex];
            // Maybe add confirmation later?
            this.log(chalk_1.default.red(`Attempting to delete Thought [${thought.id.substring(0, 6)}]...`));
            this.systemControl('delete', [thought.id]);
            this.selectedThoughtIndex = Math.max(0, this.selectedThoughtIndex - 1); // Adjust selection
        }
        else {
            this.log(chalk_1.default.yellow("No thought selected to delete."));
        }
    }
    handleRespondSelected() {
        if (this.selectedPromptIndex > -1 && this.currentPromptList.length > this.selectedPromptIndex) {
            const promptThought = this.currentPromptList[this.selectedPromptIndex];
            const promptId = promptThought.metadata.ui_context?.promptId;
            if (promptId) {
                this.respondingToPromptId = promptId;
                this.mode = 'prompt_response';
                this.inputBuffer = ""; // Clear buffer for response
                this.log(chalk_1.default.yellow(`Responding to Prompt [${promptId.substring(0, 6)}]. Enter response, Esc to cancel.`));
            }
            else {
                this.log(chalk_1.default.red("Selected prompt has no valid ID to respond to."));
            }
        }
        else {
            this.log(chalk_1.default.yellow("No prompt selected to respond to. Use Up/Down arrows."));
        }
    }
    handleTagSelected() {
        if (this.selectedThoughtIndex > -1 && this.currentThoughtList.length > this.selectedThoughtIndex) {
            const thought = this.currentThoughtList[this.selectedThoughtIndex];
            this.mode = 'input';
            this.inputBuffer = `tag ${thought.id.substring(0, 8)} `; // Use slightly longer prefix for safety
            this.log(chalk_1.default.yellow(`Enter tag for Thought [${thought.id.substring(0, 6)}]:`));
        }
        else {
            this.log(chalk_1.default.yellow("No thought selected to tag."));
        }
    }
    showHelp() {
        // Simple help log messages
        this.log(chalk_1.default.bold.cyan("--- Help ---"));
        this.log(chalk_1.default.cyan("Arrows: Navigate | Enter/Right: Select/Focus/Edit | Left: Go Back"));
        this.log(chalk_1.default.cyan("A/+: Add Note | E: Edit Selected | D/-: Delete Selected | R: Respond Prompt"));
        this.log(chalk_1.default.cyan("Space: Toggle Run/Pause | S: Step (when paused) | V: Change View"));
        this.log(chalk_1.default.cyan("T: Tag Selected | Q: Quit | ?: This Help"));
        this.log(chalk_1.default.cyan("In Input/Edit/Response mode: Enter to submit, Esc to cancel"));
        this.log(chalk_1.default.bold.cyan("------------"));
    }
    render(forceListRefresh = false) {
        // Throttle rendering
        const now = Date.now();
        if (now - this.lastRenderTime < 100 && !forceListRefresh)
            return; // Render max ~10fps unless forced
        this.lastRenderTime = now;
        const { screenWidth, screenHeight } = this;
        let output = "";
        // 1. Clear Screen & Position Cursor
        output += '\x1B[2J\x1B[0;0H';
        // 2. Header
        const title = " FlowMind TUI ";
        const status = this.engineIsRunning() ? chalk_1.default.greenBright.bold("[RUNNING]") : chalk_1.default.yellow.bold("[PAUSED]");
        const viewLabel = `View: ${this.viewMode === 'root' ? `Thread (${this.focusedRootId?.substring(0, 6) ?? 'N/A'})` : 'All Roots'}`;
        const headerRight = `${status} | ${viewLabel} `;
        const headerLeft = chalk_1.default.bold.blue(title);
        const padding = screenWidth - headerLeft.length - headerRight.length;
        output += headerLeft + " ".repeat(Math.max(0, padding)) + headerRight + "\n";
        // output += chalk.dim("-".repeat(screenWidth)) + "\n"; // Separator line
        // 3. Main Content Area (Thoughts)
        if (forceListRefresh || this.viewMode === 'all') {
            this.updateThoughtList(); // Refresh the list if needed
        }
        // Adjust selection if out of bounds
        this.selectedThoughtIndex = Math.min(this.currentThoughtList.length - 1, this.selectedThoughtIndex);
        if (this.currentThoughtList.length === 0)
            this.selectedThoughtIndex = -1;
        const mainStartRow = 2; // Header is row 1 (0-indexed)
        const listStart = Math.max(0, this.selectedThoughtIndex - Math.floor(this.mainAreaHeight / 2));
        const listEnd = Math.min(this.currentThoughtList.length, listStart + this.mainAreaHeight);
        for (let i = 0; i < this.mainAreaHeight; i++) {
            const currentLine = mainStartRow + i;
            output += `\x1B[${currentLine};0H`; // Move cursor to start of line
            const thoughtIndex = listStart + i;
            if (thoughtIndex < listEnd) {
                const thought = this.currentThoughtList[thoughtIndex];
                const isSelected = (thoughtIndex === this.selectedThoughtIndex && this.selectedPromptIndex === -1);
                const prefix = isSelected ? chalk_1.default.inverse("> ") : "  ";
                const indent = this.viewMode === 'root' ? " ".repeat((thought.metadata.depth ?? 0) * 2) : ""; // Indent in thread view
                // Format thought line
                const idStr = `[${thought.id.substring(0, 6)}]`;
                const typeStr = styleType(thought.type);
                const statusStr = styleStatus(thought.status);
                const beliefStr = thought.belief.score().toFixed(2);
                const contentStr = TermLogic.formatTerm(thought.content, screenWidth - 25); // Adjusted width
                const tagsStr = thought.metadata.tags ? chalk_1.default.dim(` #${thought.metadata.tags.join('#')}`) : '';
                const errorStr = thought.status === Status.FAILED ? chalk_1.default.red(` Err!`) : '';
                const waitingStr = thought.status === Status.WAITING ? chalk_1.default.cyan(` Wait:${thought.metadata.waiting_for_prompt_id?.substring(0, 4)}`) : '';
                let line = `${prefix}${indent}${idStr} ${typeStr.padEnd(9)} ${statusStr.padEnd(18)} B:${beliefStr} ${contentStr}${tagsStr}${errorStr}${waitingStr}`;
                output += truncatePad(line, screenWidth);
            }
            else {
                output += chalk_1.default.dim("~".padEnd(screenWidth)); // Show empty lines marker
            }
            output += "\n";
        }
        // 4. Pending Prompts Area
        this.updatePromptList(); // Refresh prompt list
        this.selectedPromptIndex = Math.min(this.currentPromptList.length - 1, this.selectedPromptIndex);
        if (this.currentPromptList.length === 0)
            this.selectedPromptIndex = -1;
        const promptHeaderRow = mainStartRow + this.mainAreaHeight;
        output += `\x1B[${promptHeaderRow};0H`;
        output += chalk_1.default.bold.yellow("-".repeat(screenWidth / 2) + " Pending Prompts " + "-".repeat(screenWidth / 2)).substring(0, screenWidth) + "\n";
        const promptListStartRow = promptHeaderRow + 1;
        for (let i = 0; i < this.promptAreaHeight; i++) {
            const currentLine = promptListStartRow + i;
            output += `\x1B[${currentLine};0H`;
            if (i < this.currentPromptList.length) {
                const promptThought = this.currentPromptList[i];
                const isSelected = (i === this.selectedPromptIndex);
                const prefix = isSelected ? chalk_1.default.inverse("> ") : "  ";
                const promptId = promptThought.metadata.ui_context?.promptId ?? '????';
                const waitingId = promptThought.metadata.parent_id?.substring(0, 6) ?? 'N/A';
                const promptText = TermLogic.formatTerm(promptThought.content, screenWidth - 25); // Adjust width
                let line = `${prefix}[${promptId.substring(0, 6)}] (for ${waitingId}) ${promptText}`;
                output += truncatePad(line, screenWidth);
            }
            else {
                output += " ".repeat(screenWidth); // Clear empty lines
            }
            output += "\n";
        }
        // 5. Log Area
        const logStartRow = promptListStartRow + this.promptAreaHeight;
        for (let i = 0; i < MAX_LOG_LINES; i++) {
            const currentLine = logStartRow + i;
            output += `\x1B[${currentLine};0H`;
            const logIndex = this.logs.length - MAX_LOG_LINES + i;
            if (logIndex >= 0) {
                output += truncatePad(this.logs[logIndex], screenWidth);
            }
            else {
                output += " ".repeat(screenWidth); // Clear line
            }
            output += "\n";
        }
        // 6. Input Area
        const inputRow = logStartRow + MAX_LOG_LINES;
        output += `\x1B[${inputRow};0H`;
        let inputPrefix = chalk_1.default.bold.greenBright("> ");
        if (this.mode === 'editing')
            inputPrefix = chalk_1.default.bold.yellow("EDIT: ");
        if (this.mode === 'prompt_response')
            inputPrefix = chalk_1.default.bold.cyan("RESP: ");
        if (this.mode !== 'main') {
            output += inputPrefix + this.inputBuffer + chalk_1.default.inverse(" "); // Show cursor block
            output += " ".repeat(Math.max(0, screenWidth - (inputPrefix.length + this.inputBuffer.length + 1)));
        }
        else {
            // Show key hints in main mode
            const hints = "[A]dd [E]dit [D]elete [R]espond [Spc]Run/Pause [S]tep [V]iew [T]ag [Q]uit [?]Help";
            output += chalk_1.default.bgGray.white(truncatePad(hints, screenWidth));
        }
        output += "\n";
        // 7. Footer (Optional - maybe stats?)
        const footerRow = inputRow + 1;
        output += `\x1B[${footerRow};0H`;
        const counts = `Thoughts: ${this.thoughtStore.getAll().length} | Pending: ${this.thoughtStore.getPending().length} | Prompts: ${this.currentPromptList.length}`;
        output += chalk_1.default.dim(truncatePad(counts, screenWidth));
        // 8. Write to screen & position cursor for input
        process.stdout.write(output);
        if (this.mode !== 'main') {
            const cursorCol = inputPrefix.length + this.inputBuffer.length;
            process.stdout.write(`\x1B[${inputRow};${cursorCol + 1}H`); // Position cursor after input
            process.stdout.write('\x1B[?25h'); // Show cursor in input mode
        }
        else {
            process.stdout.write('\x1B[?25l'); // Hide cursor in main mode
        }
    }
    // Update the list of thoughts to be displayed based on viewMode
    updateThoughtList() {
        const allThoughts = this.thoughtStore.getAll();
        if (this.viewMode === 'all') {
            // Show only root thoughts (INPUTs without parents, or orphaned goals/etc.)
            this.currentThoughtList = allThoughts.filter(t => !t.metadata.parent_id || !this.thoughtStore.get(t.metadata.parent_id)).sort((a, b) => Date.parse(b.metadata.timestamp_created ?? '0') - Date.parse(a.metadata.timestamp_created ?? '0')); // Show newest roots first
        }
        else { // 'root' view
            if (!this.focusedRootId) {
                this.currentThoughtList = []; // Should not happen, but safeguard
            }
            else {
                // Show the full thread for the focused root, sorted chronologically, with depth
                const thread = this.thoughtStore.getAllByRootId(this.focusedRootId);
                // Calculate depth for indentation
                const depthMap = new Map();
                const calculateDepth = (id, currentDepth) => {
                    if (depthMap.has(id))
                        return depthMap.get(id);
                    const thought = this.thoughtStore.get(id);
                    if (!thought || !thought.metadata.parent_id || thought.id === this.focusedRootId) {
                        depthMap.set(id, currentDepth);
                        return currentDepth;
                    }
                    const parentDepth = calculateDepth(thought.metadata.parent_id, 0); // Start depth calculation from parent
                    const depth = parentDepth + 1;
                    depthMap.set(id, depth);
                    return depth;
                };
                thread.forEach(t => t.metadata.depth = calculateDepth(t.id, 0));
                this.currentThoughtList = thread; // Already sorted by timestamp in getAllByRootId
            }
        }
    }
    // Update the list of pending user prompts
    updatePromptList() {
        this.currentPromptList = this.thoughtStore.getAll().filter(t => t.type === Type.USER_PROMPT && t.status === Status.PENDING && t.metadata.ui_context?.promptId);
    }
}
// --- Main System Class ---
class FlowMindSystem {
    thoughtStore = new ThoughtStore();
    ruleStore = new RuleStore();
    llmService = new LLMService();
    memoryStore = new MemoryStore(this.llmService.embeddings, VECTOR_STORE_PATH);
    toolRegistry = new ToolRegistry();
    engine = new Engine(this.thoughtStore, this.ruleStore, this.memoryStore, this.llmService, this.toolRegistry);
    ui = new FullScreenTUI(this.thoughtStore, this.handleCommand.bind(this), () => this.isRunning // Provide UI with a way to check engine status
    );
    isRunning = false; // Start paused by default
    workerIntervalId = null;
    constructor() {
        this.registerCoreTools();
        this.engine.setUI(this.ui); // Give engine access to UI for logging
    }
    registerCoreTools() {
        [
            new LLMTool(),
            new MemoryTool(),
            new UserInteractionTool(),
            new GoalProposalTool(),
            new CoreTool()
        ].forEach(tool => this.toolRegistry.register(tool));
    }
    // Define basic rules if none exist
    bootstrapRules() {
        if (this.ruleStore.getAll(false).length > 0)
            return; // Don't bootstrap if rules exist
        this.ui.log(chalk_1.default.blue("Bootstrapping default rules..."));
        const rules = [
            // Rule 1: INPUT -> Generate GOAL
            {
                id: 'bootstrap-input-to-goal',
                pattern: TermLogic.Variable("Content"), // Matches any term
                // Condition: Only apply if the thought type is INPUT
                // Note: Basic unification doesn't support type conditions directly.
                // We rely on the engine potentially prioritizing rules or adding type checks later.
                // For now, this rule might fire too broadly. Let's refine the action instead.
                action: TermLogic.Structure("LLMTool", [
                    TermLogic.Atom("generate"), // Operation
                    TermLogic.Structure("prompt", [
                        TermLogic.Atom("From the input note: \"?Content\"\nDefine one specific, actionable GOAL. Output only goal text.")
                    ]),
                    TermLogic.Atom(Type.GOAL) // Target type hint
                ]),
                // We need a way to restrict this rule to only fire for Type.INPUT thoughts.
                // This could be done by:
                // 1. Adding type to the pattern: Structure("input", [Variable("Content")]) - Requires changing thought content structure.
                // 2. Adding a condition check within a CoreTool action that then calls LLMTool.
                // 3. Engine logic specifically checks thought type before applying rules of certain kinds.
                // Let's assume for now the engine or future rule enhancements handle this. This rule is illustrative.
            },
            // Rule 2: GOAL -> Generate STRATEGY
            {
                id: 'bootstrap-goal-to-strategy',
                pattern: TermLogic.Variable("GoalContent"), // Matches any goal content
                // Condition: Thought type is GOAL
                action: TermLogic.Structure("LLMTool", [
                    TermLogic.Atom("generate"),
                    TermLogic.Structure("prompt", [
                        TermLogic.Atom("For the goal: \"?GoalContent\"\nOutline 1-3 concise STRATEGY steps. Output only steps, one per line.")
                    ]),
                    TermLogic.Atom(Type.STRATEGY)
                ])
            },
            // Rule 3: STRATEGY -> Request OUTCOME from user
            {
                id: 'bootstrap-strategy-to-outcome-prompt',
                pattern: TermLogic.Variable("StrategyContent"), // Matches any strategy content
                // Condition: Thought type is STRATEGY
                action: TermLogic.Structure("UserInteractionTool", [
                    TermLogic.Atom("prompt"),
                    TermLogic.Structure("prompt_text", [
                        TermLogic.Atom("What was the OUTCOME of executing the strategy: \"?StrategyContent\"?")
                    ])
                ])
            },
            // Rule 4: OUTCOME -> Add to Memory & Suggest Next Goal
            {
                id: 'bootstrap-outcome-memory-suggest',
                pattern: TermLogic.Variable("OutcomeContent"), // Matches any outcome content
                // Condition: Thought type is OUTCOME
                // Action: Sequence of actions (requires tool/engine support for sequences or a dedicated sequence tool)
                // Simpler: Just add to memory for now. Suggestion can be a separate rule/process.
                action: TermLogic.Structure("MemoryTool", [
                    TermLogic.Atom("add") // Adds the triggering thought (the outcome)
                ])
                // Follow-up action (could be another rule triggered by MemoryTool's success):
                // TermLogic.Structure("GoalProposalTool", [TermLogic.Atom("suggest")])
            },
            // Rule 5: Generic Content -> Add to Memory (Fallback)
            // This is very broad, maybe too broad. Disable by default?
            // {
            //     id: 'bootstrap-generic-to-memory',
            //     pattern: TermLogic.Variable("AnyContent"),
            //     action: TermLogic.Structure("MemoryTool", [ TermLogic.Atom("add") ])
            // }
        ];
        rules.forEach((rData, i) => {
            // Create a more specific pattern if possible (e.g., based on expected content structure)
            // For now, these use generic variables.
            let pattern = rData.pattern;
            let description = "";
            // Add pseudo-conditions via descriptions for now
            if (rData.id === 'bootstrap-input-to-goal') {
                description = "INPUT: Generate Goal via LLM";
                // pattern = TermLogic.Structure("input", [TermLogic.Variable("Content")]); // If content was structured
            }
            else if (rData.id === 'bootstrap-goal-to-strategy') {
                description = "GOAL: Generate Strategy via LLM";
            }
            else if (rData.id === 'bootstrap-strategy-to-outcome-prompt') {
                description = "STRATEGY: Prompt User for Outcome";
            }
            else if (rData.id === 'bootstrap-outcome-memory-suggest') {
                description = "OUTCOME: Add to Memory";
                // Add suggestion rule separately if needed
            }
            this.ruleStore.add({
                id: rData.id,
                pattern: pattern, // Use potentially refined pattern
                action: rData.action,
                belief: new Belief(), // Start with default belief
                metadata: {
                    description: description || `Bootstrap rule ${i + 1}`,
                    provenance: 'bootstrap',
                    enabled: true // Enable bootstrap rules by default
                }
            });
        });
        this.ui.log(chalk_1.default.blue(`Added ${this.ruleStore.getAll().length} bootstrap rules.`));
    }
    async initialize() {
        await fs.mkdir(DATA_DIR, { recursive: true });
        // Load state first, then bootstrap if needed
        await loadState(this.thoughtStore, this.ruleStore, this.memoryStore);
        this.bootstrapRules(); // Add default rules only if none were loaded
        this.ui.start();
        this.ui.log(chalk_1.default.yellow("System paused. Press [Space] to start processing."));
        // Don't start processing loop automatically
        // this.startProcessingLoop();
    }
    startProcessingLoop() {
        if (this.workerIntervalId)
            clearInterval(this.workerIntervalId);
        this.isRunning = true;
        this.ui.log(chalk_1.default.greenBright("Processing loop started."));
        this.workerIntervalId = setInterval(async () => {
            if (this.isRunning) {
                try {
                    const processedCount = await this.engine.processBatch();
                    if (processedCount > 0) {
                        debouncedSaveState(this.thoughtStore, this.ruleStore, this.memoryStore);
                        // No need to log every batch, UI updates show activity
                        // this.ui.log(chalk.dim(`Processed batch (${processedCount} successful).`));
                    }
                }
                catch (error) {
                    console.error("Error in processing loop:", error);
                    this.ui.log(chalk_1.default.red(`Critical Error in processing loop: ${error}`));
                    this.stopProcessingLoop(); // Stop on critical error
                }
            }
        }, WORKER_INTERVAL_MS);
        this.ui.render(true); // Update UI to show running status
    }
    stopProcessingLoop() {
        if (this.workerIntervalId)
            clearInterval(this.workerIntervalId);
        this.workerIntervalId = null;
        this.isRunning = false;
        this.ui.log(chalk_1.default.yellow("Processing loop paused."));
        this.ui.render(true); // Update UI to show paused status
    }
    // --- Command Handling ---
    async handleCommand(command, args = []) {
        this.ui.log(chalk_1.default.dim(`CMD: ${command} ${args.join(' ')}`)); // Log command execution
        const thoughtIdPrefix = args[0];
        let thought;
        if (thoughtIdPrefix) {
            thought = this.thoughtStore.getAll().find(t => t.id.startsWith(thoughtIdPrefix));
        }
        switch (command) {
            case 'add': {
                const contentText = args.join(' ');
                if (!contentText) {
                    this.ui.log(chalk_1.default.red("Usage: add <note text>"));
                    return;
                }
                const newThought = {
                    id: generateId(),
                    type: Type.INPUT,
                    content: TermLogic.stringToTerm(contentText), // Parse input
                    belief: new Belief(1, 0), // User input starts positive
                    status: Status.PENDING,
                    metadata: {
                        timestamp_created: new Date().toISOString(),
                        tags: ['user'],
                        provenance: 'user:add_command'
                    }
                };
                newThought.metadata.root_id = newThought.id; // It's its own root
                this.engine.addThought(newThought);
                this.ui.log(chalk_1.default.green(`Added Input [${newThought.id.substring(0, 6)}]`));
                debouncedSaveState(this.thoughtStore, this.ruleStore, this.memoryStore);
                break;
            }
            case 'respond': {
                const promptIdPrefix = args[0];
                const responseText = args.slice(1).join(' ');
                if (!promptIdPrefix || !responseText) {
                    this.ui.log(chalk_1.default.red("Usage: respond <prompt_id_prefix> <response text>"));
                    return;
                }
                // Find the full prompt ID from the prefix
                const promptThought = this.thoughtStore.getAll().find(t => t.type === Type.USER_PROMPT &&
                    t.metadata.ui_context?.promptId?.startsWith(promptIdPrefix));
                const fullPromptId = promptThought?.metadata.ui_context?.promptId;
                if (!fullPromptId) {
                    this.ui.log(chalk_1.default.red(`Prompt ID starting with "${promptIdPrefix}" not found or invalid.`));
                    return;
                }
                const success = await this.engine.handlePromptResponse(fullPromptId, responseText);
                if (success) {
                    debouncedSaveState(this.thoughtStore, this.ruleStore, this.memoryStore);
                }
                break;
            }
            case 'edit': {
                const contentText = args.slice(1).join(' ');
                if (!thought || !contentText) {
                    this.ui.log(chalk_1.default.red("Usage: edit <thought_id_prefix> <new content text>"));
                    return;
                }
                const success = await this.engine.editThoughtContent(thought.id, contentText);
                if (success) {
                    debouncedSaveState(this.thoughtStore, this.ruleStore, this.memoryStore);
                }
                break;
            }
            case 'run':
                if (!this.isRunning)
                    this.startProcessingLoop();
                break;
            case 'pause':
                if (this.isRunning)
                    this.stopProcessingLoop();
                break;
            case 'step':
                if (!this.isRunning) {
                    this.ui.log(chalk_1.default.blue("Processing single step..."));
                    const processed = await this.engine.processBatch(); // Process one batch (might do >1 thought)
                    this.ui.log(chalk_1.default.blue(`Step completed. ${processed} successful operations.`));
                    if (processed > 0)
                        debouncedSaveState(this.thoughtStore, this.ruleStore, this.memoryStore);
                }
                else {
                    this.ui.log(chalk_1.default.yellow("System must be paused to step. Press [Space] to pause."));
                }
                break;
            case 'save':
                debouncedSaveState.cancel(); // Cancel any pending debounce
                await saveState(this.thoughtStore, this.ruleStore, this.memoryStore);
                this.ui.log(chalk_1.default.blue("State saved manually."));
                break;
            case 'quit':
            case 'exit':
                await this.shutdown();
                break;
            case 'list': // Simple list to log for debugging
                this.ui.log(chalk_1.default.bold.cyan("--- All Thoughts ---"));
                this.thoughtStore.getAll().forEach(t => {
                    this.ui.log(`[${t.id.substring(0, 6)}] ${t.type} (${t.status}) B:${t.belief.score().toFixed(2)} - ${TermLogic.formatTerm(t.content, 50)}`);
                });
                this.ui.log(chalk_1.default.bold.cyan("--------------------"));
                break;
            case 'tag': {
                const tag = args[1];
                if (!thought || !tag) {
                    this.ui.log(chalk_1.default.red("Usage: tag <thought_id_prefix> <tag_name>"));
                    return;
                }
                thought.metadata.tags = thought.metadata.tags || [];
                if (!thought.metadata.tags.includes(tag)) {
                    thought.metadata.tags.push(tag);
                    this.thoughtStore.update(thought);
                    this.ui.log(chalk_1.default.green(`Tagged [${thought.id.substring(0, 6)}] with #${tag}`));
                    debouncedSaveState(this.thoughtStore, this.ruleStore, this.memoryStore);
                }
                else {
                    this.ui.log(chalk_1.default.yellow(`Thought [${thought.id.substring(0, 6)}] already has tag #${tag}`));
                }
                break;
            }
            case 'search': { // Search memory
                const query = args.join(' ');
                if (!query) {
                    this.ui.log(chalk_1.default.red("Usage: search <query text>"));
                    return;
                }
                this.ui.log(chalk_1.default.blue(`Searching memory for: "${query}"...`));
                const results = await this.memoryStore.search(query, 5);
                this.ui.log(chalk_1.default.bold.magenta(`--- Memory Search Results (${results.length}) ---`));
                results.forEach(r => {
                    this.ui.log(chalk_1.default.magenta(`[${r.id.substring(0, 6)}] (Score: ${r.metadata.score?.toFixed(3)}) ${r.content.substring(0, 60)}...`));
                });
                this.ui.log(chalk_1.default.bold.magenta("------------------------------------"));
                break;
            }
            case 'delete': {
                if (!thought) {
                    this.ui.log(chalk_1.default.red(`Usage: delete <thought_id_prefix>. Thought not found.`));
                    return;
                }
                // Add confirmation? For now, delete directly.
                const deleted = this.thoughtStore.delete(thought.id);
                if (deleted) {
                    this.ui.log(chalk_1.default.green(`Deleted Thought [${thought.id.substring(0, 6)}]`));
                    // Consider deleting children? Or let them become stale/orphaned?
                    debouncedSaveState(this.thoughtStore, this.ruleStore, this.memoryStore);
                }
                else {
                    this.ui.log(chalk_1.default.red(`Failed to delete thought [${thought.id.substring(0, 6)}]`)); // Should not happen if found
                }
                break;
            }
            // Add more commands: rule management, status changes, etc.
            default:
                this.ui.log(chalk_1.default.red(`Unknown command: ${command}. Press '?' for help.`));
        }
    }
    async shutdown() {
        this.ui.log(chalk_1.default.blue("Shutting down..."));
        this.stopProcessingLoop();
        debouncedSaveState.cancel(); // Cancel pending saves
        await saveState(this.thoughtStore, this.ruleStore, this.memoryStore); // Final save
        this.ui.stop();
        process.exit(0);
    }
}
// --- Main Execution ---
async function main() {
    const system = new FlowMindSystem();
    // Graceful shutdown listeners
    process.on('SIGINT', () => system.shutdown());
    process.on('SIGTERM', () => system.shutdown());
    // Handle unhandled rejections and exceptions
    process.on('unhandledRejection', (reason, promise) => {
        console.error(chalk_1.default.red.bold('\nUnhandled Rejection at:'), promise, chalk_1.default.red.bold('reason:'), reason);
        // Optionally shutdown or log more details
        // system.shutdown();
    });
    process.on('uncaughtException', (error) => {
        console.error(chalk_1.default.red.bold('\nUncaught Exception:'), error);
        // Optionally shutdown
        // system.shutdown();
    });
    try {
        await system.initialize();
        // The system now waits for user interaction or manual start via UI
    }
    catch (error) {
        console.error(chalk_1.default.red.bold("Critical error during system initialization:"), error);
        process.exit(1);
    }
}
// Execute main function
main();
