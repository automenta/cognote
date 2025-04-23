"use strict";
// Okay, let's refactor and enhance FlowMind Enhanced significantly.
//
// We'll address the core requirements: separating the client-side code, fixing module loading, implementing flexible LLM configuration (defaulting to Ollama), cleaning up the backend, and adding UI enhancements.
//
// **1. Backend (`flowmind_enhanced.ts`)**
//
// This file will contain the core Node.js server, backend logic (ThoughtStore, Reasoner, etc.), WebSocket handling, and static file serving for the UI.
//
//     ```typescript
// --- FlowMind Enhanced ---
// A single-file implementation (backend) of a dynamic note-taking and reasoning system
// with a 3D graph visualization UI served statically.
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
Object.defineProperty(exports, "__esModule", { value: true });
exports.FlowMindSystem = void 0;
// --- Core Imports ---
const level_1 = require("level");
const uuid_1 = require("uuid");
const openai_1 = require("@langchain/openai");
const ollama_1 = require("@langchain/community/chat_models/ollama");
const ollama_2 = require("@langchain/community/embeddings/ollama");
const messages_1 = require("@langchain/core/messages");
const output_parsers_1 = require("@langchain/core/output_parsers");
const faiss_1 = require("@langchain/community/vectorstores/faiss");
const documents_1 = require("@langchain/core/documents");
const path = __importStar(require("path"));
const os = __importStar(require("os"));
const fsSync = __importStar(require("fs"));
const fs = __importStar(require("fs/promises"));
const http = __importStar(require("http"));
const ws_1 = __importStar(require("ws"));
const url_1 = require("url"); // To get __dirname in ES modules
// --- Configuration ---
const __filename = (0, url_1.fileURLToPath)(import.meta.url);
const __dirname = path.dirname(__filename);
const DATA_DIR = path.join(os.homedir(), '.flowmind-enhanced');
const DB_PATH = path.join(DATA_DIR, 'db');
const VECTOR_STORE_PATH = path.join(DATA_DIR, 'vector-store');
const SETTINGS_PATH = path.join(DATA_DIR, 'settings.json');
const SERVER_PORT = 8080;
const DEFAULT_OLLAMA_URL = "http://localhost:11434"; // Default Ollama endpoint
// Ensure data directory exists
fs.mkdir(DATA_DIR, { recursive: true }).catch(console.error);
// --- Utility Functions ---
const safeJsonParse = (json, defaultValue) => {
    if (!json)
        return defaultValue;
    try {
        return JSON.parse(json);
    }
    catch (error) {
        // console.warn("JSON parse error:", error);
        return defaultValue;
    }
};
const generateId = () => (0, uuid_1.v4)();
// Debounce function
function debounce(func, wait) {
    let timeout = null;
    return (...args) => {
        const later = () => {
            timeout = null;
            func(...args);
        };
        if (timeout)
            clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
}
// --- Settings Manager ---
class SettingsManager {
    settings;
    system = null;
    constructor() {
        this.settings = this.loadSettings();
    }
    // Set system reference after initialization
    setSystem(system) {
        this.system = system;
    }
    getDefaultSettings() {
        return {
            llm: {
                provider: 'ollama',
                model: 'llamablit', // Common default Ollama model
                embeddingModel: 'llamablit', // Common default Ollama embedding
                endpoint: DEFAULT_OLLAMA_URL,
                apiKey: '',
            },
            autoProcessInterval: 15000,
        };
    }
    loadSettings() {
        try {
            const loaded = JSON.parse(fsSync.readFileSync(SETTINGS_PATH, 'utf-8'));
            // Merge with defaults to ensure all keys exist
            return { ...this.getDefaultSettings(), ...loaded, llm: { ...this.getDefaultSettings().llm, ...loaded.llm } };
        }
        catch (error) {
            if (error.code === 'ENOENT') {
                console.log("Settings file not found, using defaults and saving.");
                const defaults = this.getDefaultSettings();
                this.saveSettings(defaults); // Save defaults if file doesn't exist
                return defaults;
            }
            console.warn("Error loading settings, using defaults:", error);
            return this.getDefaultSettings();
        }
    }
    saveSettings(newSettings) {
        try {
            this.settings = newSettings;
            fs.writeFile(SETTINGS_PATH, JSON.stringify(this.settings, null, 2));
            console.log("Settings saved.");
            if (this.system) {
                this.system.onSettingsUpdated(); // Notify system components
            }
        }
        catch (error) {
            console.error("Error saving settings:", error);
        }
    }
    getSettings() {
        return structuredClone(this.settings); // Return a copy
    }
    updateSettings(updates) {
        const current = this.getSettings();
        // Deep merge for nested LLM settings
        const newSettings = {
            ...current,
            ...updates,
            llm: {
                ...current.llm,
                ...(updates.llm || {})
            }
        };
        this.saveSettings(newSettings);
        return newSettings;
    }
}
// --- Core Components ---
/**
 * ThoughtStore: Manages persistence using LevelDB and embeddings via FaissStore.
 */
class ThoughtStore {
    db;
    vectorStore;
    embeddings; // Use generic Embeddings type
    vectorStorePath;
    system = null;
    isVectorStoreReady = false;
    constructor(dbPath, vectorStorePath) {
        this.db = new level_1.Level(dbPath, { valueEncoding: 'json' });
        this.vectorStorePath = vectorStorePath;
    }
    async initialize(system) {
        this.system = system;
        await this.initializeVectorStore();
    }
    // Re-initialize vector store when settings change
    async initializeVectorStore() {
        if (!this.system)
            return;
        this.isVectorStoreReady = false; // Mark as not ready during init
        const settings = this.system.settingsManager.getSettings().llm;
        console.log(`Initializing vector store with provider: ${settings.provider}, embedding model: ${settings.embeddingModel}`);
        try {
            // Instantiate appropriate embedding model based on settings
            if (settings.provider === 'openai' && settings.apiKey) {
                this.embeddings = new openai_1.OpenAIEmbeddings({
                    modelName: settings.embeddingModel, // Fallback default
                    openAIApiKey: settings.apiKey
                });
            }
            else if (settings.provider === 'ollama') {
                this.embeddings = new ollama_2.OllamaEmbeddings({
                    model: settings.embeddingModel,
                    baseUrl: settings.endpoint || DEFAULT_OLLAMA_URL,
                });
            }
            else {
                console.warn(`Unsupported LLM provider "${settings.provider}" or missing API key for embeddings. Disabling vector store.`);
                this.embeddings = undefined;
                this.vectorStore = undefined;
                return;
            }
            // Attempt to load or create the FaissStore
            await fs.access(this.vectorStorePath); // Check if directory exists
            this.vectorStore = await faiss_1.FaissStore.load(this.vectorStorePath, this.embeddings);
            console.log(`Vector store loaded from ${this.vectorStorePath}`);
            this.isVectorStoreReady = true;
        }
        catch (error) {
            if (error.code === 'ENOENT' && this.embeddings) { // Directory doesn't exist, create new
                console.log(`Vector store directory not found at ${this.vectorStorePath}, creating new one.`);
                try {
                    // FaissStore requires at least one embedding to initialize.
                    const dummyEmbedding = await this.embeddings.embedQuery("FlowMind init");
                    // The structure expected by fromEmbeddings is [vector, metadata]
                    // FAISS library expects metadata to be an object, but LangChain types might vary. Let's use Document metadata format.
                    const dummyDoc = new documents_1.Document({
                        pageContent: "FlowMind init",
                        metadata: { id: `init_${generateId()}` }
                    });
                    // Correct way: use fromDocuments
                    this.vectorStore = await faiss_1.FaissStore.fromDocuments([dummyDoc], this.embeddings);
                    await this.vectorStore.save(this.vectorStorePath);
                    console.log(`New vector store created and saved to ${this.vectorStorePath}`);
                    this.isVectorStoreReady = true;
                }
                catch (createError) {
                    console.error("Failed to create new vector store:", createError);
                    this.vectorStore = undefined; // Ensure it's undefined on failure
                }
            }
            else {
                console.error("Failed to initialize vector store:", error);
                this.vectorStore = undefined; // Ensure it's undefined on failure
            }
        }
        if (this.isVectorStoreReady)
            console.log("Vector store ready.");
        else
            console.warn("Vector store initialization failed or skipped.");
    }
    // Debounced save function
    debouncedSaveVectorStore = debounce(() => {
        this.saveVectorStore().catch(err => console.error("Debounced save failed:", err));
    }, 5000); // Save max every 5 seconds
    async saveVectorStore() {
        if (this.vectorStore && this.isVectorStoreReady) {
            try {
                await this.vectorStore.save(this.vectorStorePath);
                console.log(`Vector store saved to ${this.vectorStorePath}`);
            }
            catch (saveError) {
                console.error("Error saving vector store:", saveError);
            }
        }
    }
    async broadcastChange(changeType, payload) {
        this.system?.broadcast({ type: changeType, payload });
    }
    // --- Thought Operations ---
    async putThought(thought) {
        const isNew = !(await this.getThought(thought.id));
        thought.metadata.updatedAt = new Date().toISOString();
        if (isNew)
            thought.metadata.createdAt = thought.metadata.createdAt || thought.metadata.updatedAt;
        await this.db.put(`thought:${thought.id}`, JSON.stringify(thought));
        // Update vector store if conditions met
        if (this.vectorStore && this.isVectorStoreReady && this.embeddings && typeof thought.content === 'string' && thought.content.trim()) {
            const doc = new documents_1.Document({
                pageContent: thought.content,
                metadata: { id: thought.id, type: thought.type, createdAt: thought.metadata.createdAt }
            });
            try {
                // FAISS doesn't easily support updates/deletes by ID.
                // Strategy: Add the new/updated document. Search might return duplicates if content changes significantly.
                // A more robust solution might involve rebuilding the index periodically or using a different vector store.
                await this.vectorStore.addDocuments([doc]);
                this.debouncedSaveVectorStore(); // Schedule a save
            }
            catch (error) {
                console.error(`Error adding/updating document in vector store for thought ${thought.id}:`, error);
                // If adding fails (e.g., dimension mismatch), maybe disable vector store?
                if (error.message?.includes("different dimension")) {
                    console.error("Vector dimension mismatch detected! Re-initializing vector store might be needed.");
                    this.isVectorStoreReady = false; // Mark as not ready
                }
            }
        }
        await this.logEvent(isNew ? 'thought_created' : 'thought_updated', thought.id, { type: thought.type });
        await this.broadcastChange('thought_update', thought);
    }
    async getThought(id) {
        try {
            const value = await this.db.get(`thought:${id}`);
            return safeJsonParse(value, null);
        }
        catch (error) {
            if (error.code === 'LEVEL_NOT_FOUND')
                return null;
            // Avoid logging not found errors, they are expected
            if (error.code !== 'LEVEL_NOT_FOUND')
                console.error(`Error getting thought ${id}:`, error);
            return null; // Return null for not found or other errors during get
        }
    }
    async deleteThought(id) {
        const thought = await this.getThought(id);
        if (!thought)
            return;
        await this.db.del(`thought:${id}`);
        // Note: Cannot delete from FAISS by ID easily. Vector remains.
        console.warn(`Thought ${id} deleted from LevelDB, vector may remain in FAISS.`);
        await this.logEvent('thought_deleted', id, { type: thought.type });
        await this.broadcastChange('thought_delete', { id });
    }
    async listThoughts(options = {}) {
        const thoughts = [];
        const iteratorOptions = { limit: options.limit, gt: 'thought:', lt: 'thought;~' };
        for await (const [, value] of this.db.iterator(iteratorOptions)) {
            const thought = safeJsonParse(value, null);
            if (thought && (!options.type || thought.type === options.type)) {
                thoughts.push(thought);
            }
            // LevelDB handles the limit internally
        }
        return thoughts;
    }
    async getAllThoughtsAndLinks() {
        const nodes = [];
        const links = [];
        const nodeIds = new Set();
        for await (const [, value] of this.db.iterator({ gt: 'thought:', lt: 'thought;~' })) {
            const thought = safeJsonParse(value, null);
            if (thought) {
                nodes.push(thought);
                nodeIds.add(thought.id);
                thought.links.forEach(link => {
                    links.push({ source: thought.id, target: link.targetId, relationship: link.relationship });
                });
            }
        }
        // Filter links to ensure both source and target nodes exist in the current list
        const validLinks = links.filter(l => nodeIds.has(l.source) && nodeIds.has(l.target));
        return { nodes, links: validLinks };
    }
    async findThoughtsByLink(targetId, relationship) {
        const linkedThoughts = [];
        for await (const [, value] of this.db.iterator({ gt: 'thought:', lt: 'thought;~' })) {
            const thought = safeJsonParse(value, null);
            if (thought?.links.some(link => link.targetId === targetId && (!relationship || link.relationship === relationship))) {
                linkedThoughts.push(thought);
            }
        }
        return linkedThoughts;
    }
    async semanticSearchThoughts(query, k = 5) {
        if (!this.vectorStore || !this.isVectorStoreReady) {
            console.warn("Vector store not available or ready for semantic search.");
            return [];
        }
        try {
            const results = await this.vectorStore.similaritySearchWithScore(query, k);
            const thoughtsWithScores = [];
            for (const [doc, score] of results) {
                if (doc.metadata && doc.metadata.id) {
                    const thought = await this.getThought(doc.metadata.id);
                    if (thought) {
                        thoughtsWithScores.push({ thought, score });
                    }
                }
                else {
                    console.warn("Semantic search result document missing metadata.id:", doc);
                }
            }
            return thoughtsWithScores;
        }
        catch (error) {
            console.error("Semantic search error:", error);
            // If search fails, maybe vector store is broken?
            this.isVectorStoreReady = false; // Mark as not ready
            return [];
        }
    }
    // --- Guide Operations ---
    async putGuide(guide) {
        guide.metadata.updatedAt = new Date().toISOString();
        await this.db.put(`guide:${guide.id}`, JSON.stringify(guide));
        await this.logEvent('guide_updated', guide.id, { condition: guide.condition });
        this.system?.reasoner.loadGuides(); // Reload guides in reasoner
    }
    async getGuide(id) {
        try {
            const value = await this.db.get(`guide:${id}`);
            return safeJsonParse(value, null);
        }
        catch (error) {
            if (error.code === 'LEVEL_NOT_FOUND')
                return null;
            console.error(`Error getting guide ${id}:`, error);
            return null;
        }
    }
    async deleteGuide(id) {
        await this.db.del(`guide:${id}`);
        await this.logEvent('guide_deleted', id, {});
        this.system?.reasoner.loadGuides(); // Reload guides
    }
    async listGuides() {
        const guides = [];
        for await (const [, value] of this.db.iterator({ gt: 'guide:', lt: 'guide;~' })) {
            const guide = safeJsonParse(value, null);
            if (guide)
                guides.push(guide);
        }
        return guides;
    }
    // --- Event Operations ---
    async logEvent(type, targetId, data) {
        const event = {
            id: generateId(), type, targetId,
            timestamp: new Date().toISOString(), data,
        };
        const eventKey = `event:${event.timestamp}:${event.id}`;
        await this.db.put(eventKey, JSON.stringify(event));
        // Broadcast significant events
        if (this.system && !type.startsWith('thought_')) { // Avoid duplicate broadcasts for thoughts
            this.system.broadcast({ type: 'event_log', payload: event });
        }
        if (this.system && (type.startsWith('tool_') || type.startsWith('guide_'))) {
            this.system.broadcast({ type: 'status_update', payload: { message: `Event: ${type}`, targetId: targetId } });
        }
        return event;
    }
    async listEvents(options = {}) {
        const events = [];
        const iteratorOptions = {
            limit: options.limit || 100, reverse: true,
            gt: 'event:', lt: 'event;~'
        };
        for await (const [, value] of this.db.iterator(iteratorOptions)) {
            const event = safeJsonParse(value, null);
            if (event && (!options.targetId || event.targetId === options.targetId)) {
                events.push(event);
            }
        }
        return events;
    }
    // --- DB Management ---
    async open() {
        if (this.db.status === 'closed') {
            await this.db.open();
            console.log(`ThoughtStore database opened at ${this.db.location}`);
        }
    }
    async close() {
        if (this.db.status === 'open') {
            // Ensure vector store is saved before closing DB
            if (this.vectorStore && this.isVectorStoreReady) {
                await this.saveVectorStore();
            }
            await this.db.close();
            console.log(`ThoughtStore database closed.`);
        }
    }
    // Clear all data in the database (USE WITH CAUTION)
    async clearAll() {
        console.warn("Clearing all data in ThoughtStore...");
        await this.db.clear();
        // Also clear the vector store by removing its directory
        if (this.vectorStorePath) {
            try {
                await fs.rm(this.vectorStorePath, { recursive: true, force: true });
                console.log(`Cleared vector store directory: ${this.vectorStorePath}`);
                this.vectorStore = undefined; // Reset in-memory reference
                this.isVectorStoreReady = false;
            }
            catch (err) {
                if (err.code !== 'ENOENT') { // Ignore if dir doesn't exist
                    console.error(`Error clearing vector store directory: ${this.vectorStorePath}`, err);
                }
            }
        }
        console.warn("ThoughtStore data cleared.");
        // Re-initialize vector store to create dummy files etc.
        await this.initializeVectorStore();
    }
}
/**
 * InsightModel: Interface for LLM interactions, configurable provider.
 */
class InsightModel {
    system;
    llm = null;
    embeddings = null;
    constructor(system) {
        this.system = system;
        this.configure(); // Initial configuration
    }
    // Update LLM and Embeddings instances based on current settings
    configure() {
        const settings = this.system.settingsManager.getSettings().llm;
        this.llm = null;
        this.embeddings = null; // Reset first
        console.log(`Configuring InsightModel: Provider=${settings.provider}, Model=${settings.model}, Embedding=${settings.embeddingModel}`);
        try {
            // Configure LLM
            if (settings.provider === 'openai' && settings.apiKey) {
                this.llm = new openai_1.ChatOpenAI({
                    modelName: settings.model,
                    openAIApiKey: settings.apiKey,
                    temperature: 0.7,
                });
            }
            else if (settings.provider === 'ollama') {
                this.llm = new ollama_1.ChatOllama({
                    baseUrl: settings.endpoint || DEFAULT_OLLAMA_URL,
                    model: settings.model,
                    temperature: 0.7,
                });
            }
            // Configure Embeddings (shares provider logic)
            if (settings.provider === 'openai' && settings.apiKey) {
                this.embeddings = new openai_1.OpenAIEmbeddings({
                    modelName: settings.embeddingModel,
                    openAIApiKey: settings.apiKey
                });
            }
            else if (settings.provider === 'ollama') {
                this.embeddings = new ollama_2.OllamaEmbeddings({
                    model: settings.embeddingModel,
                    baseUrl: settings.endpoint || DEFAULT_OLLAMA_URL,
                });
            }
        }
        catch (error) {
            console.error(`Error configuring InsightModel for provider ${settings.provider}:`, error);
            this.llm = null;
            this.embeddings = null;
        }
        if (!this.llm)
            console.warn("LLM client could not be configured.");
        if (!this.embeddings)
            console.warn("Embeddings client could not be configured.");
    }
    isLLMAvailable() {
        return !!this.llm;
    }
    isEmbeddingsAvailable() {
        return !!this.embeddings;
    }
    async getSuggestions(thought, contextThoughts = []) {
        if (!this.llm)
            return Promise.resolve(["LLM not configured or available."]);
        const contextText = contextThoughts.map(t => `- ${t.content} (Type: ${t.type}, Prio: ${t.priority})`).join('\n');
        // Simplified prompt for broader compatibility
        const prompt = `Thought:\nType: ${thought.type}, Priority: ${thought.priority}\nContent: ${JSON.stringify(thought.content)}\n\nContext:\n${contextText || "None"}\n\nSuggest 1-3 concise next actions or insights (e.g., "Create task: Follow up", "Link to: Project X", "Set priority: 0.8", "Ask: Deadline?").`;
        try {
            this.system.broadcast({
                type: 'status_update',
                payload: { message: `Querying LLM (${this.llm.lc_id.at(-1)})...`, targetId: thought.id }
            });
            const result = await this.llm.pipe(new output_parsers_1.StringOutputParser()).invoke([new messages_1.HumanMessage(prompt)]);
            this.system.broadcast({
                type: 'status_update',
                payload: { message: `LLM response received.`, targetId: thought.id }
            });
            return result.split('\n').map(s => s.trim().replace(/^- /, '')).filter(s => s.length > 0 && s.length < 150); // Filter and limit length
        }
        catch (error) {
            console.error("Error getting suggestions from InsightModel:", error);
            this.system.broadcast({
                type: 'error',
                payload: { message: 'LLM suggestion error', details: error.message, targetId: thought.id }
            });
            return ["Error generating suggestions."];
        }
    }
    async generateEmbedding(text) {
        if (!this.embeddings)
            return Promise.resolve(null);
        try {
            this.system.broadcast({ type: 'status_update', payload: { message: `Generating embedding...` } });
            const result = await this.embeddings.embedQuery(text);
            this.system.broadcast({ type: 'status_update', payload: { message: `Embedding generated.` } });
            return result;
        }
        catch (error) {
            console.error("Error generating embedding:", error);
            this.system.broadcast({
                type: 'error',
                payload: { message: 'Embedding generation error', details: error.message }
            });
            return null;
        }
    }
}
/**
 * ToolHub: Manages and executes tools.
 */
class ToolHub {
    tools = new Map();
    registerTool(tool) {
        if (this.tools.has(tool.name)) {
            console.warn(`Tool "${tool.name}" already registered. Overwriting.`);
        }
        this.tools.set(tool.name, tool);
        console.log(`Tool registered: ${tool.name}`);
    }
    getTool(name) {
        return this.tools.get(name);
    }
    listTools() {
        return Array.from(this.tools.values());
    }
    async executeTool(name, params, context) {
        const tool = this.getTool(name);
        if (!tool)
            throw new Error(`Tool "${name}" not found.`);
        const targetId = params.thoughtId || 'system';
        await context.thoughtStore.logEvent('tool_invoked', targetId, {
            toolName: name,
            params: params ? JSON.stringify(params).substring(0, 100) + '...' : '{}'
        });
        context.flowMindSystem.broadcast({
            type: 'status_update',
            payload: { message: `Running tool: ${name}...`, targetId }
        });
        try {
            const result = await tool.execute(params, context);
            await context.thoughtStore.logEvent('tool_success', targetId, {
                toolName: name,
                result: result ? JSON.stringify(result).substring(0, 150) + '...' : null
            });
            context.flowMindSystem.broadcast({
                type: 'status_update',
                payload: { message: `Tool ${name} succeeded.`, targetId }
            });
            return result;
        }
        catch (error) {
            console.error(`Error executing tool "${name}":`, error);
            await context.thoughtStore.logEvent('tool_failure', targetId, { toolName: name, error: error.message });
            context.flowMindSystem.broadcast({
                type: 'error',
                payload: { message: `Tool ${name} failed`, details: error.message, targetId }
            });
            throw error;
        }
    }
}
/**
 * TaskQueue: Handles asynchronous tasks, updating Thought metadata for state.
 */
class TaskQueue {
    thoughtStore;
    toolHub;
    insightModel;
    system;
    activeTasks = new Set(); // Track IDs of thoughts currently being processed
    constructor(thoughtStore, toolHub, insightModel, system) {
        this.thoughtStore = thoughtStore;
        this.toolHub = toolHub;
        this.insightModel = insightModel;
        this.system = system;
    }
    async enqueueTask(thoughtId, toolName, params) {
        const thought = await this.thoughtStore.getThought(thoughtId);
        if (!thought) {
            console.error(`TaskQueue: Thought ${thoughtId} not found for task ${toolName}.`);
            return;
        }
        // Avoid enqueueing the exact same task if already pending/processing
        if ((thought.metadata.status === 'processing' || thought.metadata.status === 'pending') &&
            thought.metadata.pendingTask?.toolName === toolName &&
            JSON.stringify(thought.metadata.pendingTask?.params) === JSON.stringify(params)) {
            console.log(`Task ${toolName} already pending/processing for thought ${thoughtId}. Skipping enqueue.`);
            return;
        }
        // Use reasoner's update method to handle metadata changes and broadcast
        await this.system.reasoner.updateThought(thoughtId, {
            metadata: { pendingTask: { toolName, params }, status: 'pending' }
        });
        this.system.broadcast({
            type: 'status_update',
            payload: { message: `Task ${toolName} enqueued`, targetId: thoughtId }
        });
        // Optionally trigger processing immediately if needed, otherwise rely on cycle/scan
        // this.processTask(thoughtId);
    }
    // Processes a single task if conditions are met
    async processTask(thoughtId) {
        if (this.activeTasks.has(thoughtId))
            return false;
        let thought = await this.thoughtStore.getThought(thoughtId);
        if (!thought || !thought.metadata.pendingTask || thought.metadata.status !== 'pending') {
            return false; // No pending task or not in correct state
        }
        this.activeTasks.add(thoughtId);
        const { toolName, params } = thought.metadata.pendingTask;
        // Update status to processing via reasoner method
        await this.system.reasoner.updateThought(thoughtId, {
            metadata: { status: 'processing', pendingTask: undefined } // Remove pending task marker
        });
        console.log(`Processing task "${toolName}" for thought ${thoughtId}...`);
        this.system.broadcast({
            type: 'status_update',
            payload: { message: `Processing ${toolName}...`, targetId: thoughtId }
        });
        let finalStatus = 'completed';
        let errorInfo = undefined;
        try {
            const context = {
                thoughtStore: this.thoughtStore,
                taskQueue: this,
                insightModel: this.insightModel,
                flowMindSystem: this.system,
                settings: this.system.settingsManager.getSettings(),
            };
            await this.toolHub.executeTool(toolName, { ...params, thoughtId }, context);
        }
        catch (error) {
            finalStatus = 'failed';
            errorInfo = error.message;
        }
        finally {
            // Update final status via reasoner method
            await this.system.reasoner.updateThought(thoughtId, {
                metadata: { status: finalStatus, errorInfo: errorInfo }
            });
            this.activeTasks.delete(thoughtId);
            console.log(`Finished processing task "${toolName}" for thought ${thoughtId}. Status: ${finalStatus}`);
            this.system.broadcast({
                type: 'status_update',
                payload: { message: `Task ${toolName} ${finalStatus}`, targetId: thoughtId, status: finalStatus }
            });
        }
        return true;
    }
    // Scans and processes tasks that are marked as 'pending'
    async processPendingTasks(limit = 5) {
        // console.log("Scanning for pending tasks..."); // Can be noisy
        const thoughts = await this.thoughtStore.listThoughts();
        let processedCount = 0;
        // Create promises for potential tasks to run in parallel (up to limit)
        const taskPromises = [];
        for (const thought of thoughts) {
            if (taskPromises.length >= limit)
                break; // Don't exceed limit
            if (thought.metadata.status === 'pending' && thought.metadata.pendingTask && !this.activeTasks.has(thought.id)) {
                taskPromises.push(this.processTask(thought.id));
            }
        }
        if (taskPromises.length > 0) {
            const results = await Promise.all(taskPromises);
            processedCount = results.filter(Boolean).length; // Count how many actually processed
            if (processedCount > 0) {
                console.log(`Initiated processing for ${processedCount} pending tasks.`);
                this.system.broadcast({
                    type: 'status_update',
                    payload: { message: `Processed ${processedCount} pending tasks.` }
                });
            }
        }
        return processedCount;
    }
}
/**
 * Reasoner: Core agentic component driving Thought evolution.
 */
class Reasoner {
    thoughtStore;
    taskQueue;
    toolHub;
    insightModel;
    system;
    guides = [];
    processing = false;
    constructor(thoughtStore, taskQueue, toolHub, insightModel, system) {
        this.thoughtStore = thoughtStore;
        this.taskQueue = taskQueue;
        this.toolHub = toolHub;
        this.insightModel = insightModel;
        this.system = system;
    }
    async loadGuides() {
        this.guides = await this.thoughtStore.listGuides();
        console.log(`Loaded ${this.guides.length} guides into Reasoner.`);
    }
    // Create Thought - Centralized logic
    async createThought(content, type = 'note', priority = 0.5, links = [], metadata = {}) {
        const now = new Date().toISOString();
        const thought = {
            id: generateId(),
            content,
            priority: Math.max(0, Math.min(1, priority)),
            type, links,
            metadata: {
                createdAt: now, updatedAt: now, tags: [],
                status: 'pending', // Start as pending for embedding/initial check
                ...metadata,
            },
        };
        await this.thoughtStore.putThought(thought); // Saves and broadcasts creation
        console.log(`Created thought ${thought.id} of type ${type}`);
        this.system.broadcast({
            type: 'status_update',
            payload: { message: `Thought created: ${thought.id.substring(0, 8)}...`, targetId: thought.id }
        });
        // Enqueue embedding task if applicable and embeddings are configured
        if (this.insightModel.isEmbeddingsAvailable() && typeof content === 'string' && content.trim()) {
            await this.taskQueue.enqueueTask(thought.id, 'GenerateEmbeddingTool', { text: content });
        }
        else {
            // If no embedding needed, mark as completed
            await this.updateThought(thought.id, { metadata: { status: 'completed' } });
        }
        return thought;
    }
    // Update Thought - Centralized logic with metadata merge
    async updateThought(id, updates) {
        const thought = await this.thoughtStore.getThought(id);
        if (!thought)
            return null;
        const updatedThought = structuredClone(thought);
        // Merge top-level fields (excluding metadata, id)
        Object.keys(updates).forEach(key => {
            if (key !== 'metadata' && key !== 'id' && updates[key] !== undefined) {
                updatedThought[key] = updates[key];
            }
        });
        // Merge metadata correctly
        if (updates.metadata) {
            // Special handling for UI updates: merge ui sub-object
            if (updates.metadata.ui) {
                updatedThought.metadata.ui = { ...thought.metadata.ui, ...updates.metadata.ui };
                // If position is fixed by UI, update metadata
                if (updates.metadata.ui.fx !== undefined || updates.metadata.ui.fy !== undefined || updates.metadata.ui.fz !== undefined) {
                    updatedThought.metadata.ui.fx = updates.metadata.ui.fx ?? thought.metadata.ui?.fx;
                    updatedThought.metadata.ui.fy = updates.metadata.ui.fy ?? thought.metadata.ui?.fy;
                    updatedThought.metadata.ui.fz = updates.metadata.ui.fz ?? thought.metadata.ui?.fz;
                }
                // Remove UI from the general metadata update to avoid overwriting merge
                delete updates.metadata.ui;
            }
            // Merge the rest of the metadata
            updatedThought.metadata = { ...updatedThought.metadata, ...updates.metadata };
        }
        updatedThought.metadata.updatedAt = new Date().toISOString();
        // Clamp priority if updated
        if (updates.priority !== undefined) {
            updatedThought.priority = Math.max(0, Math.min(1, updates.priority));
        }
        const contentChanged = updates.content !== undefined && JSON.stringify(updates.content) !== JSON.stringify(thought.content);
        // Use putThought for saving and broadcasting
        await this.thoughtStore.putThought(updatedThought);
        // Re-enqueue embedding task if content changed and embeddings available
        if (contentChanged && this.insightModel.isEmbeddingsAvailable() && typeof updatedThought.content === 'string' && updatedThought.content.trim()) {
            await this.taskQueue.enqueueTask(id, 'GenerateEmbeddingTool', { text: updatedThought.content });
        }
        return updatedThought;
    }
    // Reasoner Processing Cycle
    async processCycle(limit = 10) {
        if (this.processing)
            return;
        this.processing = true;
        this.system.broadcast({ type: 'status_update', payload: { message: 'Reasoner cycle started...' } });
        // console.log("Starting Reasoner cycle..."); // Can be noisy
        let thoughtsProcessed = 0;
        let guidesApplied = 0;
        let insightsGenerated = 0;
        let tasksProcessed = 0;
        try {
            await this.loadGuides(); // Refresh guides
            // 1. Process Pending Tasks first
            tasksProcessed = await this.taskQueue.processPendingTasks(limit);
            thoughtsProcessed += tasksProcessed;
            // 2. Select Thoughts for Guide/Insight processing
            const remainingLimit = limit - thoughtsProcessed;
            if (remainingLimit > 0) {
                const allThoughts = await this.thoughtStore.listThoughts();
                const thoughtsToProcess = allThoughts
                    .filter(t => t.metadata.status === 'completed' || t.metadata.status === undefined) // Focus on stable thoughts
                    .sort((a, b) => (b.priority ?? 0.5) - (a.priority ?? 0.5)) // Sort by priority
                    .slice(0, remainingLimit);
                if (thoughtsToProcess.length > 0) {
                    // Process guides and insights in parallel for selected thoughts
                    const processingPromises = thoughtsToProcess.map(async (thought) => {
                        let guideAppliedCount = 0;
                        let insightGeneratedCount = 0;
                        // a) Apply Guides
                        for (const guide of this.guides) {
                            if (this.evaluateGuideCondition(guide.condition, thought)) {
                                try {
                                    const changed = await this.applyGuideAction(guide.action, thought);
                                    if (changed)
                                        guideAppliedCount++;
                                }
                                catch (e) {
                                    console.error(`Error applying guide ${guide.id} to ${thought.id}:`, e);
                                }
                            }
                        }
                        // b) Get Insight Model suggestions (conditionally)
                        const shouldGetInsights = ['note', 'goal', 'question'].includes(thought.type) && Math.random() < 0.15; // Slightly higher chance
                        if (shouldGetInsights && this.insightModel.isLLMAvailable()) {
                            try {
                                // Minimal context: just the thought itself for simplicity now
                                const suggestions = await this.insightModel.getSuggestions(thought);
                                if (suggestions.length > 0 && !suggestions[0].startsWith("LLM not configured") && !suggestions[0].startsWith("Error")) {
                                    await this.updateThought(thought.id, { metadata: { aiSuggestions: suggestions } });
                                    insightGeneratedCount++;
                                }
                            }
                            catch (e) {
                                console.error(`Error getting insights for ${thought.id}:`, e);
                            }
                        }
                        return { guideAppliedCount, insightGeneratedCount };
                    });
                    const results = await Promise.all(processingPromises);
                    results.forEach(r => {
                        guidesApplied += r.guideAppliedCount;
                        insightsGenerated += r.insightGeneratedCount;
                    });
                    thoughtsProcessed += thoughtsToProcess.length; // Count thoughts attempted
                }
            }
            const summary = `Cycle finished. Tasks: ${tasksProcessed}, Guides: ${guidesApplied}, Insights: ${insightsGenerated}. Total considered: ${thoughtsProcessed}.`;
            console.log(summary);
            this.system.broadcast({ type: 'status_update', payload: { message: summary } });
        }
        catch (error) {
            console.error("Error during Reasoner cycle:", error);
            this.system.broadcast({
                type: 'error',
                payload: {
                    message: 'Error during Reasoner cycle',
                    details: error instanceof Error ? error.message : String(error)
                }
            });
        }
        finally {
            this.processing = false;
        }
    }
    // Evaluate Guide Condition (Simple DSL Parser - enhanced)
    evaluateGuideCondition(condition, thought) {
        const conditions = condition.split('&').map(c => c.trim()).filter(c => c.length > 0);
        if (conditions.length === 0)
            return false;
        return conditions.every(cond => {
            // Match key, operator, value (value can be quoted)
            const parts = cond.match(/^([a-zA-Z_.]+)(\s*=|!=|>|<|>=|<=|=~|!~)\s*(.*)$/);
            if (!parts || parts.length !== 4) {
                // Simple tag check: tag=urgent (no operator)
                if (cond.startsWith('tag='))
                    return (thought.metadata.tags || []).includes(cond.substring(4));
                if (cond.startsWith('type='))
                    return thought.type === cond.substring(5);
                console.warn(`Malformed condition part: ${cond}`);
                return false;
            }
            const [, key, operator, rawValue] = parts;
            // Trim quotes from value if present
            const value = rawValue.startsWith('"') && rawValue.endsWith('"') ? rawValue.slice(1, -1) : rawValue;
            let v; // v = thought's value: (check top-level then metadata)
            if (key.startsWith('metadata.')) {
                v = thought.metadata[key.substring(9)];
            }
            else {
                v = thought[key];
            }
            // Handle specific keys
            if (key === 'tags')
                v = thought.metadata.tags || [];
            if (key === 'content' && typeof thought.content !== 'string')
                v = JSON.stringify(thought.content);
            // Evaluate based on operator
            const vNum = parseFloat(value);
            switch (operator.trim()) {
                case '=':
                    return String(v) === value;
                case '!=':
                    return String(v) !== value;
                case '>':
                    return typeof v === 'number' && v > vNum;
                case '<':
                    return typeof v === 'number' && v < vNum;
                case '>=':
                    return typeof v === 'number' && v >= vNum;
                case '<=':
                    return typeof v === 'number' && v <= vNum;
                case '=~': // Contains / Includes
                    if (key === 'tags' && Array.isArray(v))
                        return v.includes(value);
                    if (typeof v === 'string')
                        return v.toLowerCase().includes(value.toLowerCase());
                    return false;
                case '!~': // Does not contain / include
                    if (key === 'tags' && Array.isArray(v))
                        return !v.includes(value);
                    if (typeof v === 'string')
                        return !v.toLowerCase().includes(value.toLowerCase());
                    return false;
                default:
                    return false;
            }
        });
    }
    // Apply Guide Action (Simple DSL Parser - enhanced)
    async applyGuideAction(action, thought) {
        // Examples: set priority=0.9, add_tag=important, create_task:"Follow up required", link_to=uuid:child
        const parts = action.match(/^([a-zA-Z_]+)(?:=|:)(.+)$/);
        if (!parts || parts.length !== 3) {
            console.warn(`Malformed action: ${action}`);
            return false;
        }
        const [, command, rawValue] = parts;
        // Trim quotes from value if present
        let rawTrimmed = rawValue.trim();
        const value = rawTrimmed.startsWith('"') && rawTrimmed.endsWith('"')
            ? rawTrimmed.slice(1, -1)
            : rawTrimmed;
        let updatePayload = {};
        let taskEnqueued = false;
        try {
            switch (command) {
                case 'set':
                    const [setKey, setValueStr] = value.split('=', 2);
                    if (!setKey || setValueStr === undefined)
                        return false;
                    const setValue = setValueStr.trim();
                    if (setKey.startsWith('metadata.')) {
                        const metaKey = setKey.substring(9);
                        updatePayload.metadata = { ...updatePayload.metadata, [metaKey]: setValue };
                        // Handle specific types if needed (e.g., status)
                        if (metaKey === 'status')
                            updatePayload.metadata.status = setValue;
                    }
                    else if (setKey === 'priority') {
                        updatePayload.priority = parseFloat(setValue);
                    }
                    else if (setKey === 'type') {
                        updatePayload.type = setValue;
                    }
                    else {
                        updatePayload[setKey] = setValue; // Allow setting other top-level? Risky.
                    }
                    break;
                case 'add_tag':
                    const currentTags = thought.metadata.tags || [];
                    if (!currentTags.includes(value)) {
                        updatePayload.metadata = { ...updatePayload.metadata, tags: [...currentTags, value] };
                    }
                    else
                        return false; // Tag already exists
                    break;
                case 'remove_tag':
                    const tagsToRemove = thought.metadata.tags || [];
                    if (tagsToRemove.includes(value)) {
                        updatePayload.metadata = {
                            ...updatePayload.metadata,
                            tags: tagsToRemove.filter(t => t !== value)
                        };
                    }
                    else
                        return false; // Tag not found
                    break;
                case 'create_task':
                    await this.taskQueue.enqueueTask(thought.id, 'CreateChildTaskTool', {
                        parentId: thought.id,
                        content: value
                    });
                    taskEnqueued = true;
                    break;
                case 'link_to':
                    const [targetId, relationship = 'related'] = value.split(':', 2);
                    const targetExists = await this.thoughtStore.getThought(targetId);
                    if (targetExists && !thought.links.some(l => l.targetId === targetId && l.relationship === relationship)) {
                        updatePayload.links = [...thought.links, { targetId, relationship }];
                    }
                    else {
                        if (!targetExists)
                            console.warn(`Guide Action: Link target ${targetId} does not exist.`);
                        return false; // Link exists or target not found
                    }
                    break;
                case 'run_tool': // Allow guides to run tools
                    const [toolName, toolParamsJson] = value.split(':', 2);
                    const toolParams = toolParamsJson ? JSON.parse(toolParamsJson) : {};
                    await this.taskQueue.enqueueTask(thought.id, toolName, toolParams);
                    taskEnqueued = true;
                    break;
                default:
                    console.warn(`Unsupported guide action command: ${command}`);
                    return false;
            }
            // Apply updates if any were generated
            if (Object.keys(updatePayload).length > 0) {
                await this.updateThought(thought.id, updatePayload);
                return true; // Thought was directly modified
            }
            return taskEnqueued; // Return true if a task was enqueued
        }
        catch (error) {
            console.error(`Error applying guide action "${action}" to thought ${thought.id}:`, error);
            return false;
        }
    }
    // Basic feedback handling (placeholder)
    async provideFeedback(id, feedback) {
        console.log(`Feedback received for thought ${id}:`, feedback);
        const t = await this.thoughtStore.getThought(id);
        if (!t)
            return;
        const currentFeedback = t.metadata.feedback || [];
        currentFeedback.push({ ...feedback, timestamp: new Date().toISOString() });
        // Example: Adjust priority based on rating
        let priorityUpdate = undefined;
        if (feedback.type === 'rating' && typeof feedback.value === 'number') {
            // Simple adjustment: boost priority slightly for positive rating, decrease for negative
            const adjustment = (feedback.value - 0.5) * 0.1; // Scale rating (-0.5 to 0.5) to adjustment (-0.05 to 0.05)
            const newPriority = Math.max(0, Math.min(1, t.priority + adjustment));
            priorityUpdate = { priority: newPriority };
            console.log(`Adjusting priority for ${id} from ${t.priority.toFixed(2)} to ${newPriority.toFixed(2)} based on feedback.`);
        }
        await this.updateThought(id, {
            ...priorityUpdate,
            metadata: { feedback: currentFeedback }
        });
        this.system.broadcast({
            type: 'status_update',
            payload: { message: `Feedback processed for ${id.substring(0, 8)}...`, targetId: id }
        });
    }
}
// --- Concrete Tool Implementations ---
class FeedbackTool {
    name = "FeedbackTool";
    description = "Processes user feedback about a thought.";
    async execute(params, context) {
        const { thoughtId, feedback } = params;
        if (!thoughtId || !feedback) {
            throw new Error("thoughtId and feedback parameters are required.");
        }
        // Use the reasoner's method to handle feedback logic
        await context.flowMindSystem.reasoner.provideFeedback(thoughtId, feedback);
        return { status: "Feedback processed" };
    }
}
class GenerateEmbeddingTool {
    name = "GenerateEmbeddingTool";
    description = "Generates and stores an embedding for a thought's content via the vector store.";
    async execute(params, context) {
        const { thoughtId } = params;
        const thought = await context.thoughtStore.getThought(thoughtId);
        if (!thought)
            throw new Error(`Thought ${thoughtId} not found for embedding.`);
        // Use text override if provided, otherwise thought content
        const contentToEmbed = params.text ?? (typeof thought.content === 'string' ? thought.content : JSON.stringify(thought.content));
        if (!context.insightModel.isEmbeddingsAvailable()) {
            console.warn(`Embeddings not available, skipping embedding for ${thoughtId}.`);
            // Mark as completed anyway, as no embedding can be done
            await context.flowMindSystem.reasoner.updateThought(thoughtId, {
                metadata: {
                    status: 'completed',
                    errorInfo: 'Embeddings unavailable'
                }
            });
            return;
        }
        if (!contentToEmbed || !contentToEmbed.trim()) {
            console.log(`Skipping embedding for ${thoughtId}: empty content.`);
            await context.flowMindSystem.reasoner.updateThought(thoughtId, { metadata: { status: 'completed' } });
            return;
        }
        console.log(`Generating embedding for thought ${thoughtId}...`);
        const embedding = await context.insightModel.generateEmbedding(contentToEmbed);
        if (embedding) {
            // Embedding is successful, vector store update is handled by putThought.
            // We just need to mark the thought as completed for this task.
            await context.flowMindSystem.reasoner.updateThought(thoughtId, {
                metadata: { embeddingGeneratedAt: new Date().toISOString(), status: 'completed', errorInfo: undefined }
            });
            console.log(`Embedding generated for thought ${thoughtId}. Vector store update triggered.`);
        }
        else {
            console.warn(`Failed to generate embedding for thought ${thoughtId}. Marking as failed.`);
            await context.flowMindSystem.reasoner.updateThought(thoughtId, {
                metadata: { status: 'failed', errorInfo: 'Embedding generation failed' }
            });
        }
    }
}
class CreateChildTaskTool {
    name = "CreateChildTaskTool";
    description = "Creates a new task thought linked to a parent.";
    async execute(params, context) {
        const { parentId, content, priority } = params; // Use default priority from createThought if not provided
        const parentThought = await context.thoughtStore.getThought(parentId);
        if (!parentThought)
            throw new Error(`Parent thought ${parentId} not found.`);
        const taskThought = await context.flowMindSystem.reasoner.createThought(content, 'task', priority, [{ relationship: 'parent', targetId: parentId }], { tags: ['task'] });
        // Add link back from parent to child
        await context.flowMindSystem.reasoner.updateThought(parentId, {
            links: [...parentThought.links, { relationship: 'child', targetId: taskThought.id }]
        });
        console.log(`Created child task ${taskThought.id} for parent ${parentId}`);
        return { taskId: taskThought.id };
    }
}
class MemoryTool {
    name = "MemoryTool";
    description = "Performs semantic search across thoughts.";
    async execute(params, context) {
        const { query, k = 5 } = params;
        if (!query)
            throw new Error("Query parameter is required.");
        if (!context.insightModel.isEmbeddingsAvailable()) {
            throw new Error("Embeddings service not available for memory search.");
        }
        const results = await context.thoughtStore.semanticSearchThoughts(query, k);
        console.log(`MemoryTool found ${results.length} results for query "${query.substring(0, 30)}..."`);
        return { results }; // Return results for potential use by caller/UI
    }
}
class ShareTool {
    name = "ShareTool";
    description = "Exports/Imports thoughts as JSON.";
    async execute(params, context) {
        if (params.action === 'export') {
            const { thoughtIds } = params;
            if (!thoughtIds || thoughtIds.length === 0)
                throw new Error("thoughtIds required for export.");
            const thoughtsToExport = [];
            for (const id of thoughtIds) {
                const thought = await context.thoughtStore.getThought(id);
                if (thought)
                    thoughtsToExport.push(thought);
            }
            const exportedJson = JSON.stringify(thoughtsToExport, null, 2);
            console.log(`ShareTool exported ${thoughtsToExport.length} thoughts.`);
            return { exportedJson };
        }
        else if (params.action === 'import') {
            const { json } = params;
            if (!json)
                throw new Error("JSON string required for import.");
            const thoughtsToImport = safeJsonParse(json, []);
            if (!Array.isArray(thoughtsToImport))
                throw new Error("Invalid JSON format for import.");
            let importedCount = 0;
            let skippedCount = 0;
            for (const thought of thoughtsToImport) {
                // Basic validation
                if (thought.id && thought.content !== undefined && thought.type) {
                    // Check if ID already exists? Overwrite or skip? Let's overwrite.
                    await context.thoughtStore.putThought(thought); // putThought handles creation/update/broadcast
                    importedCount++;
                }
                else {
                    console.warn("Skipping invalid thought during import:", thought);
                    skippedCount++;
                }
            }
            console.log(`ShareTool imported ${importedCount} thoughts, skipped ${skippedCount}.`);
            return { importedCount, skippedCount };
        }
        else {
            throw new Error("Invalid action for ShareTool. Use 'export' or 'import'.");
        }
    }
}
// --- Main System Class ---
class FlowMindSystem {
    settingsManager;
    thoughtStore;
    insightModel;
    toolHub;
    taskQueue;
    reasoner;
    wss = null;
    clients = new Set();
    processingInterval = null;
    isPaused = true; // Start paused
    constructor() {
        this.settingsManager = new SettingsManager();
        this.settingsManager.setSystem(this); // Provide reference back
        this.thoughtStore = new ThoughtStore(DB_PATH, VECTOR_STORE_PATH);
        this.insightModel = new InsightModel(this); // Pass system reference
        this.toolHub = new ToolHub();
        this.taskQueue = new TaskQueue(this.thoughtStore, this.toolHub, this.insightModel, this);
        this.reasoner = new Reasoner(this.thoughtStore, this.taskQueue, this.toolHub, this.insightModel, this);
        this.registerDefaultTools();
    }
    // Callback for when settings are updated
    onSettingsUpdated() {
        console.log("System notified of settings update.");
        const newSettings = this.settingsManager.getSettings();
        // Reconfigure components that depend on settings
        this.insightModel.configure();
        this.thoughtStore.initializeVectorStore(); // Re-init vector store with potentially new embeddings
        // Restart auto-processing with new interval if changed and not paused
        this.stopAutoProcessing();
        if (!this.isPaused) {
            this.startAutoProcessing(newSettings.autoProcessInterval);
        }
        // Broadcast new settings to clients
        this.broadcast({ type: 'settings', payload: newSettings });
    }
    registerDefaultTools() {
        this.toolHub.registerTool(new GenerateEmbeddingTool());
        this.toolHub.registerTool(new CreateChildTaskTool());
        this.toolHub.registerTool(new MemoryTool());
        this.toolHub.registerTool(new ShareTool());
        this.toolHub.registerTool(new FeedbackTool());
    }
    async initialize(server) {
        await this.thoughtStore.open();
        await this.thoughtStore.initialize(this); // Pass system ref after DB open
        await this.reasoner.loadGuides();
        await this.taskQueue.processPendingTasks(10); // Process some pending tasks on startup
        this.setupWebSocketServer(server);
        console.log("FlowMind Enhanced system initialized.");
        this.broadcast({
            type: 'status_update',
            payload: { message: 'System Initialized. Paused.', paused: this.isPaused }
        });
    }
    // Setup WebSocket Server
    setupWebSocketServer(server) {
        this.wss = new ws_1.WebSocketServer({ server });
        console.log(`WebSocket server started.`);
        this.wss.on('connection', (ws) => {
            console.log('Client connected.');
            this.clients.add(ws);
            // Send initial state
            this.sendCurrentSettings(ws);
            this.sendInitialGraphState(ws);
            ws.send(JSON.stringify({ type: 'status_update', payload: { paused: this.isPaused } }));
            ws.on('message', (message) => this.handleWebSocketMessage(ws, message));
            ws.on('close', () => {
                console.log('Client disconnected.');
                this.clients.delete(ws);
            });
            ws.on('error', (error) => {
                console.error('WebSocket error:', error);
                this.clients.delete(ws);
            });
        });
    }
    // Send current settings to a client
    sendCurrentSettings(ws) {
        const settings = this.settingsManager.getSettings();
        ws.send(JSON.stringify({ type: 'settings', payload: settings }));
    }
    // Send the complete current graph state to a client
    async sendInitialGraphState(ws) {
        try {
            const graphData = await this.thoughtStore.getAllThoughtsAndLinks();
            const message = { type: 'init', payload: graphData };
            ws.send(JSON.stringify(message));
            console.log(`Sent initial graph state (${graphData.nodes.length} nodes, ${graphData.links.length} links).`);
        }
        catch (error) {
            console.error('Error sending initial graph state:', error);
            ws.send(JSON.stringify({ type: 'error', payload: { message: 'Failed to retrieve graph state' } }));
        }
    }
    // Handle incoming messages from clients
    async handleWebSocketMessage(ws, message) {
        let parsedMessage;
        try {
            parsedMessage = JSON.parse(message.toString());
        }
        catch (error) {
            console.error('Failed to parse WebSocket message:', message.toString());
            ws.send(JSON.stringify({ type: 'error', payload: { message: 'Invalid message format' } }));
            return;
        }
        // console.log('Received message:', parsedMessage.type); // Verbose
        try {
            switch (parsedMessage.type) {
                case 'request_graph':
                    await this.sendInitialGraphState(ws);
                    break;
                case 'request_settings':
                    this.sendCurrentSettings(ws);
                    break;
                case 'update_settings':
                    const newSettings = this.settingsManager.updateSettings(parsedMessage.payload);
                    // SettingsManager handles saving and notifying system components
                    // Confirmation is sent via onSettingsUpdated -> broadcast
                    break;
                case 'add_thought':
                    await this.reasoner.createThought(parsedMessage.payload.content, parsedMessage.payload.type, parsedMessage.payload.priority, parsedMessage.payload.links, parsedMessage.payload.metadata);
                    break;
                case 'update_thought':
                    const { id, ...updates } = parsedMessage.payload;
                    if (typeof id === 'string')
                        await this.reasoner.updateThought(id, updates);
                    else
                        throw new Error("Invalid update_thought payload: id missing");
                    break;
                case 'delete_thought':
                    if (typeof parsedMessage.payload.id === 'string')
                        await this.thoughtStore.deleteThought(parsedMessage.payload.id);
                    else
                        throw new Error("Invalid delete_thought payload: id missing");
                    break;
                case 'run_tool':
                    const { toolName, params, thoughtId } = parsedMessage.payload;
                    if (typeof toolName !== 'string' || typeof params !== 'object')
                        throw new Error("Invalid run_tool payload");
                    const result = await this.invokeTool(toolName, params, thoughtId);
                    // Send result back to the specific client that requested it
                    ws.send(JSON.stringify({
                        type: 'status_update',
                        payload: { message: `Tool ${toolName} completed.`, result }
                    }));
                    break;
                case 'add_guide':
                    const { condition, action, weight } = parsedMessage.payload;
                    if (typeof condition !== 'string' || typeof action !== 'string')
                        throw new Error("Invalid add_guide payload");
                    await this.addGuide(condition, action, weight);
                    ws.send(JSON.stringify({ type: 'status_update', payload: { message: `Guide added: ${condition}` } }));
                    break;
                case 'control':
                    this.handleControlCommand(parsedMessage.payload);
                    break;
                default:
                    console.warn(`Unhandled WebSocket message type: ${parsedMessage.type}`);
                    ws.send(JSON.stringify({
                        type: 'error',
                        payload: { message: `Unhandled message type: ${parsedMessage.type}` }
                    }));
            }
        }
        catch (error) {
            console.error(`Error handling WebSocket message type ${parsedMessage.type}:`, error);
            ws.send(JSON.stringify({
                type: 'error',
                payload: { message: `Error processing ${parsedMessage.type}`, details: error.message }
            }));
        }
    }
    // Handle control commands (run/pause/step/clear)
    handleControlCommand(payload) {
        switch (payload.command) {
            case 'run':
                this.isPaused = false;
                this.startAutoProcessing(this.settingsManager.getSettings().autoProcessInterval);
                console.log("System Resumed.");
                this.broadcast({ type: 'status_update', payload: { message: 'System Resumed.', paused: false } });
                break;
            case 'pause':
                this.isPaused = true;
                this.stopAutoProcessing();
                console.log("System Paused.");
                this.broadcast({ type: 'status_update', payload: { message: 'System Paused.', paused: true } });
                break;
            case 'step':
                if (this.isPaused) {
                    console.log("Executing single Reasoner step...");
                    this.broadcast({ type: 'status_update', payload: { message: 'Executing step...' } });
                    // Run a smaller cycle for responsiveness
                    this.reasoner.processCycle(5).catch(err => console.error("Step execution failed:", err));
                }
                else {
                    console.log("Cannot step while running.");
                    this.broadcast({ type: 'status_update', payload: { message: 'Cannot step while running.' } });
                }
                break;
            case 'clear_all':
                console.warn("Received 'clear_all' command.");
                // Add confirmation? For now, proceed directly.
                this.clearAllData().then(() => {
                    this.broadcast({ type: 'init', payload: { nodes: [], links: [] } }); // Send empty graph
                    this.broadcast({
                        type: 'status_update',
                        payload: { message: 'All data cleared. System Paused.', paused: true }
                    });
                }).catch(err => {
                    console.error("Failed to clear all data:", err);
                    this.broadcast({ type: 'error', payload: { message: 'Failed to clear data' } });
                });
                break;
            default:
                console.warn(`Unknown control command: ${payload.command}`);
        }
    }
    // Broadcast message to all connected clients
    broadcast(message) {
        if (!this.wss)
            return;
        const messageString = JSON.stringify(message);
        this.clients.forEach((client) => {
            if (client.readyState === ws_1.default.OPEN) {
                client.send(messageString, (err) => {
                    if (err)
                        console.error("Error sending message to client:", err);
                });
            }
        });
    }
    // Start/Stop Auto Processing
    startAutoProcessing(intervalMs) {
        if (this.processingInterval || this.isPaused)
            return;
        console.log(`Starting auto-processing cycle every ${intervalMs}ms.`);
        this.processingInterval = setInterval(() => {
            if (!this.isPaused) {
                this.reasoner.processCycle().catch(err => {
                    console.error("Error in scheduled Reasoner cycle:", err);
                    this.broadcast({
                        type: 'error',
                        payload: { message: 'Error in Reasoner cycle', details: err.message }
                    });
                });
            }
        }, intervalMs);
    }
    stopAutoProcessing() {
        if (this.processingInterval) {
            clearInterval(this.processingInterval);
            this.processingInterval = null;
            console.log("Stopped auto-processing cycle.");
        }
    }
    // Shutdown
    async shutdown() {
        console.log("Shutting down FlowMind system...");
        this.stopAutoProcessing();
        if (this.wss) {
            await new Promise(resolve => this.wss.close(() => {
                console.log("WebSocket server closed.");
                resolve();
            }));
            this.clients.forEach(client => client.terminate());
        }
        await this.thoughtStore.close(); // Saves vectors, closes DB
        console.log("FlowMind Enhanced system shut down gracefully.");
    }
    // Destructive: Clear all data
    async clearAllData() {
        console.warn("--- CLEARING ALL FLOWMIND DATA ---");
        this.stopAutoProcessing();
        this.isPaused = true; // Ensure paused state
        await this.thoughtStore.clearAll(); // Clears LevelDB and vector store dir
        await this.reasoner.loadGuides(); // Reload empty guides
        // Clear settings? No, keep settings persisted.
        console.warn("--- ALL DATA CLEARED ---");
    }
    // --- High-Level API Wrappers (for potential programmatic use) ---
    async addThought(content, type, priority, links, metadata) {
        return this.reasoner.createThought(content, type, priority, links, metadata);
    }
    async getThought(id) {
        return this.thoughtStore.getThought(id);
    }
    async updateThought(id, updates) {
        return this.reasoner.updateThought(id, updates);
    }
    async deleteThought(id) {
        await this.thoughtStore.deleteThought(id);
    }
    async listThoughts(options) {
        return this.thoughtStore.listThoughts(options);
    }
    async findThoughts(query) {
        return this.thoughtStore.semanticSearchThoughts(query);
    } // Primarily semantic
    async addGuide(condition, action, weight = 0.5) {
        const guide = {
            id: generateId(),
            condition,
            action,
            weight,
            metadata: { createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() }
        };
        await this.thoughtStore.putGuide(guide); // This triggers reload via putGuide
        return guide;
    }
    async listGuides() {
        return this.thoughtStore.listGuides();
    }
    async invokeTool(toolName, params, thoughtId) {
        if (thoughtId) {
            await this.taskQueue.enqueueTask(thoughtId, toolName, params);
            return { status: 'enqueued', message: `Task ${toolName} enqueued for thought ${thoughtId}` };
        }
        else {
            const context = {
                thoughtStore: this.thoughtStore,
                taskQueue: this.taskQueue,
                insightModel: this.insightModel,
                flowMindSystem: this,
                settings: this.settingsManager.getSettings()
            };
            const result = await this.toolHub.executeTool(toolName, params, context);
            return { status: 'executed', result };
        }
    }
    async getEvents(options) {
        return this.thoughtStore.listEvents(options);
    }
}
exports.FlowMindSystem = FlowMindSystem;
// --- Server Setup ---
async function main() {
    console.log("--- Initializing FlowMind Enhanced Server ---");
    const system = new FlowMindSystem();
    // Create HTTP Server for static files and WebSocket upgrade
    const server = http.createServer((req, res) => {
        // Simple static file server for the UI
        const url = req.url === '/' ? 'index.html' : req.url ?? 'index.html';
        const filePath = path.join(__dirname, url); // Assume UI files are in ../ui/
        const extname = String(path.extname(filePath)).toLowerCase();
        const mimeTypes = {
            '.html': 'text/html',
            '.js': 'text/javascript',
            '.css': 'text/css',
            '.json': 'application/json',
            '.png': 'image/png',
            '.jpg': 'image/jpg',
            '.gif': 'image/gif',
            '.svg': 'image/svg+xml',
        };
        const contentType = mimeTypes[extname] || 'application/octet-stream';
        fs.readFile(filePath)
            .then(content => {
            res.writeHead(200, { 'Content-Type': contentType, 'Access-Control-Allow-Origin': '*' });
            res.end(content, 'utf-8');
        })
            .catch(error => {
            if (error.code === 'ENOENT') {
                res.writeHead(404, { 'Content-Type': 'text/plain' });
                res.end('Not Found');
            }
            else {
                res.writeHead(500, { 'Content-Type': 'text/plain' });
                res.end(`Server Error: ${error.code}`);
            }
        });
    });
    // Initialize the system (attaches WebSocket server)
    await system.initialize(server);
    server.listen(SERVER_PORT, () => {
        console.log(`\nFlowMind Enhanced server running.`);
        console.log(`>>> UI available at http://localhost:${SERVER_PORT} <<<`);
        console.log(`System started in ${system['isPaused'] ? 'PAUSED' : 'RUNNING'} state.`);
    });
    // Graceful shutdown
    const shutdown = async (signal) => {
        console.log(`\nCaught ${signal}, shutting down...`);
        try {
            await system.shutdown();
            server.close(() => {
                console.log('HTTP server closed.');
                process.exit(0);
            });
            // Force exit if necessary
            setTimeout(() => process.exit(1), 5000);
        }
        catch (err) {
            console.error("Error during shutdown:", err);
            process.exit(1);
        }
    };
    process.on('SIGINT', () => shutdown('SIGINT'));
    process.on('SIGTERM', () => shutdown('SIGTERM'));
}
// --- Run ---
//if (import.meta.url === `file://${process.argv[1]}`) { // Check if running directly
main().catch(error => {
    console.error("\n--- Server Initialization Failed ---");
    console.error(error);
    process.exit(1);
});
//
// **3. Add a `FeedbackTool` (Backend `flowmind_enhanced.ts`)**
//
// Add this simple tool class within the `// --- Concrete Tool Implementations ---` section:
//
//     ```typescript
// ```
//
// **2. Client-Side UI (`ui/index.html`)**
//
// Create a directory named `ui` next to your `flowmind_enhanced.ts` file and place this `index.html` inside it.
//
// ```html
//
//     And register it in the `FlowMindSystem`'s `registerDefaultTools` method:
//
//         ```typescript
// private registerDefaultTools(): void {
//     // ... other tools
//     this.toolHub.registerTool(new FeedbackTool()); // Add this line
// }
// ```
//
//         **Explanation of Changes:**
//
//         1.  **Separated UI:** The HTML, CSS, and client-side JavaScript are now in `ui/index.html`. The Node.js server (`flowmind_enhanced.ts`) simply serves this file statically.
//         2.  **ES Modules (Client):** The client-side JS now uses `<script type="module">` and an `importmap` to load Three.js and its addons correctly from CDN module endpoints. This fixes the `import` errors.
//         3.  **LLM Configuration:**
//     *   A `SettingsManager` class handles loading, saving, and providing system settings, including LLM config. Settings are saved to `~/.flowmind-enhanced/settings.json`.
//         *   `InsightModel` now dynamically configures LangChain's `ChatOpenAI`/`OpenAIEmbeddings` or `ChatOllama`/`OllamaEmbeddings` based on the saved settings.
//         *   The UI includes a "Settings" panel (toggled via a button) to view and modify the LLM provider, endpoint (for Ollama), models, API key, and processing interval.
//         *   The system defaults to Ollama running at `http://localhost:11434` with common model names, requiring no API key by default.
//         4.  **Backend Refactoring:**
//     *   Code is slightly more organized (e.g., `SettingsManager`).
//         *   Error handling is improved in places (e.g., vector store init/search).
//     *   Broadcasting uses the central `system.broadcast` method more consistently.
//         *   `updateThought` in `Reasoner` now handles metadata merging more carefully, especially for UI properties (`fx`, `fy`, `fz` for fixed node positions).
//     *   `putThought` in `ThoughtStore` now triggers debounced vector store saves to avoid excessive writes.
//         *   Reasoner cycle processes tasks first, then applies guides/insights in parallel for better performance.
//         *   Guide condition/action parsing is slightly enhanced.
//         5.  **Frontend Refactoring & Enhancements:**
//     *   Client JS is structured within the module script using functions.
//     *   Node labels (`.node-label`) have improved styling, including a colored border indicating the node type.
//         *   Node dragging now updates fixed position (`fx`, `fy`, `fz`) metadata, allowing users to pin nodes.
//         *   The HUD shows node/link counts and has better status indicators.
//         *   A simple feedback mechanism (👍/👎 buttons) is added to nodes with AI suggestions, triggering the new `FeedbackTool`.
//         *   The node editing form is integrated within the label itself.
//         *   A "Clear All" button with confirmation is added.
//         *   Error messages from the server are displayed more clearly in the UI log.
//         *   Smooth camera transitions (`TWEEN.js`) are used for focusing/zooming on nodes.
//         6.  **Ubiquity/Utility:** Defaulting to local Ollama makes it usable without cloud dependencies or API keys. The settings UI allows adapting to various LLM setups. JSON import/export provides basic sharing.
//         7.  **Missing Functionality:**
//     *   **Learning:** Basic priority adjustment based on feedback is added. Complex learning is still out of scope.
//         *   **Collaboration:** Remains JSON import/export via `ShareTool`.
//
//         **To Run:**
//
//         1.  **Save Files:** Save the backend code as `flowmind_enhanced.ts` and the frontend code as `ui/index.html` (create the `ui` directory).
//         2.  **Install Dependencies:**
//         ```bash
//     npm install level uuid @langchain/openai @langchain/community @langchain/core ws @types/node @types/ws @types/uuid
//     # Optional but recommended for FaissStore:
//     # npm install @langchain/community # (If not already installed for Ollama)
//     # npm install faiss-node # (May require build tools like cmake, python, C++ compiler)
//     # If faiss-node fails, consider alternatives like HNSWLib:
//     # npm install hnswlib-node @langchain/hnswlib
//     ```
//         *(Note: `faiss-node` installation can be tricky. If you have issues, you might need to install build tools or switch to another vector store like `HNSWLib`)*
//             3.  **Set OpenAI Key (Optional):** If you want to use OpenAI, set the environment variable:
//                 ```bash
//     export OPENAI_API_KEY="your-openai-api-key"
//     ```
//             4.  **Run Ollama (Optional):** If you want to use the default Ollama setup, ensure Ollama is running locally (usually at `http://localhost:11434`) and you have pulled the default models:
//             ```bash
//     ollama pull llama3:latest
//     ollama pull nomic-embed-text:latest
//     ```
//             5.  **Compile and Run Backend:**
//             ```bash
//     # Compile TypeScript (if needed, or use ts-node)
//     # npx tsc flowmind_enhanced.ts --esModuleInterop --module NodeNext --outDir dist
//     # node dist/flowmind_enhanced.js
//
//     # Or run directly with ts-node (easier for development)
//     npx ts-node --esm flowmind_enhanced.ts
//     ```
//             6.  **Access UI:** Open your browser to `http://localhost:8080`.
//
//                 You should now have a more robust, configurable, and user-friendly version of FlowMind Enhanced. Remember to configure the LLM settings in the UI if you deviate from the Ollama defaults or want to use OpenAI.
