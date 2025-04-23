"use strict";
// --- FlowMind Enhanced ---
// A single-file implementation of a dynamic note-taking and reasoning system
// with a 3D graph visualization UI.
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
const messages_1 = require("@langchain/core/messages");
const output_parsers_1 = require("@langchain/core/output_parsers");
// Using FaissStore for local vector storage. Ensure `faiss-node` is installed if using locally.
// Note: `faiss-node` can have complex build requirements. An alternative like `hnswlib-node`
// with `HNSWLib` vector store might be easier for cross-platform compatibility if needed.
const faiss_1 = require("@langchain/community/vectorstores/faiss");
const documents_1 = require("@langchain/core/documents");
const path = __importStar(require("path.js"));
const os = __importStar(require("os.js"));
const fs = __importStar(require("fs/promises"));
const http = __importStar(require("http.js"));
const ws_1 = __importStar(require("ws")); // Use 'ws' library for WebSockets
// --- Configuration ---
const DB_PATH = path.join(os.homedir(), '.flowmind-enhanced-db');
const VECTOR_STORE_PATH = path.join(os.homedir(), '.flowmind-vector-store');
const INSIGHT_MODEL_NAME = "gpt-4o-mini";
const EMBEDDING_MODEL_NAME = "text-embedding-3-small";
const SERVER_PORT = 8080; // Port for the web server and WebSocket
// --- Utility Functions ---
const safeJsonParse = (json, defaultValue) => {
    try {
        return json ? JSON.parse(json) : defaultValue;
    }
    catch (error) {
        // console.warn("JSON parse error:", error); // Can be noisy
        return defaultValue;
    }
};
const generateId = () => (0, uuid_1.v4)();
// --- Core Components ---
/**
 * ThoughtStore: Manages persistence using LevelDB and embeddings via FaissStore.
 */
class ThoughtStore {
    db;
    vectorStore;
    embeddings;
    vectorStorePath;
    system = null; // Reference to main system for broadcasting changes
    constructor(dbPath, vectorStorePath) {
        this.db = new level_1.Level(dbPath, { valueEncoding: 'json' });
        this.vectorStorePath = vectorStorePath;
        // Vector store initialization is now deferred until system sets itself
    }
    // Called by FlowMindSystem after its own initialization
    async initialize(system) {
        this.system = system;
        await this.initializeVectorStore();
    }
    async initializeVectorStore() {
        if (process.env.OPENAI_API_KEY) {
            try {
                this.embeddings = new openai_1.OpenAIEmbeddings({ modelName: EMBEDDING_MODEL_NAME, openAIApiKey: process.env.OPENAI_API_KEY });
                await fs.access(this.vectorStorePath); // Check if directory exists
                this.vectorStore = await faiss_1.FaissStore.load(this.vectorStorePath, this.embeddings);
                console.log(`Vector store loaded from ${this.vectorStorePath}`);
            }
            catch (error) {
                if (error.code === 'ENOENT') { // Directory doesn't exist
                    console.log(`Vector store directory not found at ${this.vectorStorePath}, creating new one.`);
                    if (!this.embeddings) {
                        this.embeddings = new openai_1.OpenAIEmbeddings({ modelName: EMBEDDING_MODEL_NAME, openAIApiKey: process.env.OPENAI_API_KEY });
                    }
                    // FaissStore.fromDocuments needs embeddings instance
                    const dummyDocs = [new documents_1.Document({ pageContent: "FlowMind initialization placeholder", metadata: { id: "init_0" } })];
                    this.vectorStore = await faiss_1.FaissStore.fromDocuments(dummyDocs, this.embeddings);
                    await this.vectorStore.save(this.vectorStorePath);
                    console.log(`New vector store created and saved to ${this.vectorStorePath}`);
                }
                else {
                    console.error("Failed to initialize vector store:", error);
                    // Disable vector store features if loading fails critically
                    this.vectorStore = undefined;
                    this.embeddings = undefined;
                }
            }
        }
        else {
            console.warn("OPENAI_API_KEY not set. Semantic search and embedding features disabled.");
            this.vectorStore = undefined;
            this.embeddings = undefined;
        }
    }
    async saveVectorStore() {
        if (this.vectorStore) {
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
        if (this.system) {
            this.system.broadcast({ type: changeType, payload });
        }
    }
    // --- Thought Operations ---
    async putThought(thought) {
        const isNew = !(await this.getThought(thought.id)); // Check if it exists before putting
        thought.metadata.updatedAt = new Date().toISOString();
        if (isNew)
            thought.metadata.createdAt = thought.metadata.createdAt || thought.metadata.updatedAt;
        await this.db.put(`thought:${thought.id}`, JSON.stringify(thought));
        // Update vector store if content exists and embeddings are enabled
        if (this.vectorStore && this.embeddings && typeof thought.content === 'string' && thought.content.trim()) {
            const docs = [new documents_1.Document({ pageContent: thought.content, metadata: { id: thought.id, type: thought.type, createdAt: thought.metadata.createdAt } })];
            try {
                // Limitation: Add only. Updates create duplicates. Deletes are not handled here.
                await this.vectorStore.addDocuments(docs);
                // Maybe save periodically instead of every time
            }
            catch (error) {
                console.error(`Error adding document to vector store for thought ${thought.id}:`, error);
            }
        }
        await this.logEvent(isNew ? 'thought_created' : 'thought_updated', thought.id, { changes: Object.keys(thought) });
        await this.broadcastChange('thought_update', thought); // Broadcast the full thought data
    }
    async getThought(id) {
        try {
            const value = await this.db.get(`thought:${id}`);
            return safeJsonParse(value, null);
        }
        catch (error) {
            if (error.code === 'LEVEL_NOT_FOUND')
                return null;
            console.error(`Error getting thought ${id}:`, error);
            throw error; // Re-throw other errors
        }
    }
    async deleteThought(id) {
        const thought = await this.getThought(id); // Get data before deleting for logging/broadcast
        if (!thought)
            return;
        await this.db.del(`thought:${id}`);
        // Limitation: Cannot easily delete from FAISS by ID. Vector remains.
        console.warn(`Thought ${id} deleted from LevelDB, but its vector may remain in FAISS.`);
        await this.logEvent('thought_deleted', id, { type: thought.type });
        await this.broadcastChange('thought_delete', { id }); // Broadcast deletion by ID
    }
    async listThoughts(options = {}) {
        const thoughts = [];
        const iteratorOptions = { limit: options.limit, gt: 'thought:', lt: 'thought;~' };
        for await (const [key, value] of this.db.iterator(iteratorOptions)) {
            const thought = safeJsonParse(value, null);
            if (thought && (!options.type || thought.type === options.type)) {
                thoughts.push(thought);
            }
            if (options.limit && thoughts.length >= options.limit)
                break;
        }
        return thoughts;
    }
    async getAllThoughtsAndLinks() {
        const nodes = [];
        const links = [];
        const nodeIds = new Set();
        for await (const [key, value] of this.db.iterator({ gt: 'thought:', lt: 'thought;~' })) {
            const thought = safeJsonParse(value, null);
            if (thought) {
                nodes.push(thought);
                nodeIds.add(thought.id);
                thought.links.forEach(link => {
                    // Ensure target also exists (or handle dangling links if needed)
                    // For graph visualization, only add links between existing nodes
                    links.push({ source: thought.id, target: link.targetId, relationship: link.relationship });
                });
            }
        }
        // Filter links where target might not exist (though putThought should prevent this)
        const validLinks = links.filter(l => nodeIds.has(l.target));
        return { nodes, links: validLinks };
    }
    async findThoughtsByLink(targetId, relationship) {
        // (Implementation remains the same as before)
        const linkedThoughts = [];
        for await (const [key, value] of this.db.iterator({ gt: 'thought:', lt: 'thought;~' })) {
            const thought = safeJsonParse(value, null);
            if (thought && thought.links.some(link => link.targetId === targetId && (!relationship || link.relationship === relationship))) {
                linkedThoughts.push(thought);
            }
        }
        return linkedThoughts;
    }
    async semanticSearchThoughts(query, k = 5) {
        if (!this.vectorStore) {
            console.warn("Vector store not available for semantic search.");
            return [];
        }
        try {
            const results = await this.vectorStore.similaritySearchWithScore(query, k);
            const thoughtsWithScores = [];
            for (const [doc, score] of results) {
                // Check if metadata and id exist before trying to get the thought
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
            return [];
        }
    }
    // --- Guide Operations ---
    async putGuide(guide) {
        guide.metadata.updatedAt = new Date().toISOString();
        await this.db.put(`guide:${guide.id}`, JSON.stringify(guide));
        await this.logEvent('guide_updated', guide.id, { changes: Object.keys(guide) });
        // Optionally broadcast guide changes if UI needs to show them
    }
    async getGuide(id) {
        // (Implementation remains the same)
        try {
            const value = await this.db.get(`guide:${id}`);
            return safeJsonParse(value, null);
        }
        catch (error) {
            if (error.code === 'LEVEL_NOT_FOUND')
                return null;
            console.error(`Error getting guide ${id}:`, error);
            throw error;
        }
    }
    async deleteGuide(id) {
        // (Implementation remains the same)
        await this.db.del(`guide:${id}`);
        await this.logEvent('guide_deleted', id, {});
    }
    async listGuides() {
        // (Implementation remains the same)
        const guides = [];
        for await (const [key, value] of this.db.iterator({ gt: 'guide:', lt: 'guide;~' })) {
            const guide = safeJsonParse(value, null);
            if (guide)
                guides.push(guide);
        }
        return guides;
    }
    // --- Event Operations ---
    async logEvent(type, targetId, data) {
        const event = {
            id: generateId(),
            type: type,
            targetId: targetId,
            timestamp: new Date().toISOString(),
            data: data,
        };
        const eventKey = `event:${event.timestamp}:${event.id}`; // Store chronologically
        await this.db.put(eventKey, JSON.stringify(event));
        // Broadcast significant events to UI
        if (this.system && !type.startsWith('thought_')) { // Avoid duplicate broadcasts for thoughts
            this.system.broadcast({ type: 'event_log', payload: event });
        }
        if (this.system && type.startsWith('tool_')) {
            this.system.broadcast({ type: 'status_update', payload: { message: `Tool ${type}: ${data.toolName}`, targetId: targetId } });
        }
        return event;
    }
    async listEvents(options = {}) {
        // (Implementation remains the same)
        const events = [];
        const iteratorOptions = {
            limit: options.limit || 100, // Default limit
            reverse: true,
            gt: 'event:',
            lt: 'event;~'
        };
        for await (const [key, value] of this.db.iterator(iteratorOptions)) {
            const event = safeJsonParse(value, null);
            if (event && (!options.targetId || event.targetId === options.targetId)) {
                events.push(event);
            }
            // Iterator limit is handled by LevelDB
        }
        return events; // Already reversed
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
            await this.saveVectorStore(); // Save vectors before closing DB
            await this.db.close();
            console.log(`ThoughtStore database closed.`);
        }
    }
}
/**
 * InsightModel: Interface for LLM interactions.
 */
class InsightModel {
    llm = null;
    embeddings = null;
    constructor() {
        if (process.env.OPENAI_API_KEY) {
            this.llm = new openai_1.ChatOpenAI({ modelName: INSIGHT_MODEL_NAME, temperature: 0.7, openAIApiKey: process.env.OPENAI_API_KEY });
            this.embeddings = new openai_1.OpenAIEmbeddings({ modelName: EMBEDDING_MODEL_NAME, openAIApiKey: process.env.OPENAI_API_KEY });
        }
        else {
            console.warn("InsightModel disabled (OPENAI_API_KEY not set).");
        }
    }
    async getSuggestions(thought, contextThoughts = []) {
        if (!this.llm)
            return Promise.resolve(["InsightModel disabled."]);
        // (Prompt logic remains the same)
        const contextText = contextThoughts.map(t => `- ${t.content} (Type: ${t.type}, Prio: ${t.priority})`).join('\n');
        const prompt = `Given the following thought:
Type: ${thought.type}
Priority: ${thought.priority}
Content: ${JSON.stringify(thought.content)}
Metadata: ${JSON.stringify(thought.metadata)}
Links: ${JSON.stringify(thought.links)}

And the surrounding context (related thoughts):
${contextText || "None"}

Suggest 1-3 concise next actions or insights related to this thought. Examples: "Create task: Follow up", "Link to thought: Project X", "Set priority: 0.8", "Ask question: What is the deadline?". Frame suggestions as potential actions.`;
        try {
            const result = await this.llm.pipe(new output_parsers_1.StringOutputParser()).invoke([new messages_1.HumanMessage(prompt)]);
            return result.split('\n').map(s => s.trim().replace(/^- /, '')).filter(s => s.length > 0);
        }
        catch (error) {
            console.error("Error getting suggestions from InsightModel:", error);
            return ["Error generating suggestions."];
        }
    }
    async generateEmbedding(text) {
        if (!this.embeddings)
            return Promise.resolve(null);
        try {
            return await this.embeddings.embedQuery(text);
        }
        catch (error) {
            console.error("Error generating embedding:", error);
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
        // Log invocation *before* execution
        await context.thoughtStore.logEvent('tool_invoked', targetId, { toolName: name, params });
        try {
            const result = await tool.execute(params, context);
            // Log success *after* execution
            await context.thoughtStore.logEvent('tool_success', targetId, { toolName: name, result: result ? JSON.stringify(result).substring(0, 150) + '...' : null });
            return result;
        }
        catch (error) {
            console.error(`Error executing tool "${name}":`, error);
            // Log failure
            await context.thoughtStore.logEvent('tool_failure', targetId, { toolName: name, error: error.message });
            throw error; // Re-throw
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
    system; // Reference to main system
    activeTasks = new Set();
    constructor(thoughtStore, toolHub, insightModel, system) {
        this.thoughtStore = thoughtStore;
        this.toolHub = toolHub;
        this.insightModel = insightModel;
        this.system = system; // Store reference
    }
    async enqueueTask(thoughtId, toolName, params) {
        const thought = await this.thoughtStore.getThought(thoughtId);
        if (!thought) {
            console.error(`TaskQueue: Thought ${thoughtId} not found for task ${toolName}.`);
            return;
        }
        if (thought.metadata.status === 'processing' && thought.metadata.pendingTask?.toolName === toolName) {
            console.log(`Task ${toolName} already pending/processing for thought ${thoughtId}. Skipping enqueue.`);
            return;
        }
        thought.metadata.pendingTask = { toolName, params };
        thought.metadata.status = 'pending';
        await this.thoughtStore.putThought(thought); // Updates status and broadcasts change
        // No need to trigger immediately, Reasoner cycle or periodic check can pick it up
        // Or trigger processing if needed: this.processTask(thoughtId);
        this.system.broadcast({ type: 'status_update', payload: { message: `Task ${toolName} enqueued`, targetId: thoughtId } });
    }
    // Processes a single task if conditions are met
    async processTask(thoughtId) {
        if (this.activeTasks.has(thoughtId))
            return false; // Already processing
        const thought = await this.thoughtStore.getThought(thoughtId);
        // Check if task is still pending and not actively processed
        if (!thought || !thought.metadata.pendingTask || thought.metadata.status !== 'pending') {
            if (this.activeTasks.has(thoughtId))
                this.activeTasks.delete(thoughtId); // Clean up if somehow stuck
            return false; // No pending task or already handled
        }
        this.activeTasks.add(thoughtId);
        const { toolName, params } = thought.metadata.pendingTask;
        thought.metadata.status = 'processing';
        // Keep pendingTask metadata until completion for potential retries? Or remove now? Remove now for simplicity.
        delete thought.metadata.pendingTask;
        await this.thoughtStore.putThought(thought); // Update status -> broadcasts change
        console.log(`Processing task "${toolName}" for thought ${thoughtId}...`);
        this.system.broadcast({ type: 'status_update', payload: { message: `Processing ${toolName}...`, targetId: thoughtId } });
        let finalStatus = 'completed';
        let errorInfo = undefined;
        try {
            const context = {
                thoughtStore: this.thoughtStore,
                taskQueue: this,
                insightModel: this.insightModel,
                flowMindSystem: this.system
            };
            // Ensure thoughtId is available within params if tool needs it
            await this.toolHub.executeTool(toolName, { ...params, thoughtId }, context);
        }
        catch (error) {
            console.error(`Task "${toolName}" for thought ${thoughtId} failed:`, error);
            finalStatus = 'failed';
            errorInfo = error.message;
        }
        finally {
            const finalThought = await this.thoughtStore.getThought(thoughtId);
            if (finalThought) {
                finalThought.metadata.status = finalStatus;
                finalThought.metadata.errorInfo = errorInfo; // Add error info if failed
                // Clear pending task explicitly if it wasn't removed earlier
                delete finalThought.metadata.pendingTask;
                await this.thoughtStore.putThought(finalThought); // Update final status -> broadcasts change
            }
            this.activeTasks.delete(thoughtId);
            console.log(`Finished processing task "${toolName}" for thought ${thoughtId}. Status: ${finalStatus}`);
            this.system.broadcast({ type: 'status_update', payload: { message: `Task ${toolName} ${finalStatus}`, targetId: thoughtId, status: finalStatus } });
        }
        return true; // Task was processed
    }
    // Scans and processes tasks that are marked as 'pending'
    async processPendingTasks(limit = 5) {
        console.log("Scanning for pending tasks...");
        const thoughts = await this.thoughtStore.listThoughts();
        let processedCount = 0;
        for (const thought of thoughts) {
            if (processedCount >= limit)
                break; // Limit number of tasks processed per scan
            if (thought.metadata.status === 'pending' && thought.metadata.pendingTask) {
                const processed = await this.processTask(thought.id); // Process asynchronously
                if (processed)
                    processedCount++;
            }
        }
        if (processedCount > 0) {
            console.log(`Initiated processing for ${processedCount} pending tasks.`);
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
    system; // Reference to main system
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
    // Create Thought - Refactored for clarity
    async createThought(content, type = 'note', priority = 0.5, links = [], metadata = {}) {
        const now = new Date().toISOString();
        const thought = {
            id: generateId(),
            content,
            priority: Math.max(0, Math.min(1, priority)), // Clamp priority
            type,
            links,
            metadata: {
                createdAt: now,
                updatedAt: now,
                tags: [],
                status: 'pending', // Start as pending for potential initial processing/embedding
                ...metadata,
            },
        };
        // Put thought first (broadcasts creation)
        await this.thoughtStore.putThought(thought);
        console.log(`Created thought ${thought.id} of type ${type}`);
        this.system.broadcast({ type: 'status_update', payload: { message: `Thought created: ${thought.id.substring(0, 8)}...`, targetId: thought.id } });
        // Enqueue embedding task if applicable
        if (typeof content === 'string' && content.trim() && this.insightModel['embeddings']) {
            await this.taskQueue.enqueueTask(thought.id, 'GenerateEmbeddingTool', { text: content });
        }
        else {
            // If no embedding needed, mark as completed immediately
            thought.metadata.status = 'completed';
            await this.thoughtStore.putThought(thought);
        }
        return thought;
    }
    // Update Thought - More robust merging
    async updateThought(id, updates) {
        const thought = await this.thoughtStore.getThought(id);
        if (!thought)
            return null;
        // Use structuredClone for deep copy before modification
        const updatedThought = structuredClone(thought);
        // Merge top-level fields carefully
        Object.keys(updates).forEach(key => {
            if (key !== 'metadata' && key !== 'id' && key !== 'embedding' && updates[key] !== undefined) {
                updatedThought[key] = updates[key];
            }
        });
        // Merge metadata
        if (updates.metadata) {
            updatedThought.metadata = { ...thought.metadata, ...updates.metadata };
        }
        updatedThought.metadata.updatedAt = new Date().toISOString();
        // Clamp priority if updated
        if (updates.priority !== undefined) {
            updatedThought.priority = Math.max(0, Math.min(1, updates.priority));
        }
        // Check if content changed to re-trigger embedding
        const contentChanged = typeof updates.content === 'string' && updates.content !== thought.content;
        await this.thoughtStore.putThought(updatedThought); // Broadcasts update
        // Re-enqueue embedding task if content changed
        if (contentChanged && typeof updatedThought.content === 'string' && this.insightModel['embeddings']) {
            await this.taskQueue.enqueueTask(id, 'GenerateEmbeddingTool', { text: updatedThought.content });
        }
        return updatedThought;
    }
    // Reasoner Processing Cycle
    async processCycle(limit = 10) {
        if (this.processing) {
            console.log("Reasoner cycle already in progress.");
            return;
        }
        this.processing = true;
        this.system.broadcast({ type: 'status_update', payload: { message: 'Reasoner cycle started...' } });
        console.log("Starting Reasoner cycle...");
        let thoughtsProcessed = 0;
        let guidesApplied = 0;
        let insightsGenerated = 0;
        try {
            await this.loadGuides(); // Refresh guides
            // 1. Process Pending Tasks first
            const tasksProcessed = await this.taskQueue.processPendingTasks(limit);
            thoughtsProcessed += tasksProcessed; // Count tasks as processed thoughts
            // 2. Select Thoughts for Guide/Insight processing (if limit not reached)
            const remainingLimit = limit - thoughtsProcessed;
            if (remainingLimit > 0) {
                const allThoughts = await this.thoughtStore.listThoughts();
                // Prioritize thoughts that are 'completed' (not pending/processing) and higher priority
                const thoughtsToProcess = allThoughts
                    .filter(t => t.metadata.status === 'completed' || t.metadata.status === undefined) // Focus on stable thoughts
                    .sort((a, b) => b.priority - a.priority)
                    .slice(0, remainingLimit);
                if (thoughtsToProcess.length > 0) {
                    console.log(`Applying Guides/Insights to ${thoughtsToProcess.length} thoughts...`);
                    for (const thought of thoughtsToProcess) {
                        let modifiedByCycle = false;
                        // a) Apply Guides
                        for (const guide of this.guides) {
                            if (this.evaluateGuideCondition(guide.condition, thought)) {
                                console.log(`Guide "${guide.id.substring(0, 8)}" matched thought ${thought.id.substring(0, 8)}. Action: ${guide.action}`);
                                const changed = await this.applyGuideAction(guide.action, thought); // Pass original thought
                                if (changed) {
                                    modifiedByCycle = true; // Mark modification for potential re-saving if needed
                                    guidesApplied++;
                                    // Fetch the latest version as applyGuideAction might have saved it
                                    const potentiallyUpdatedThought = await this.thoughtStore.getThought(thought.id);
                                    if (potentiallyUpdatedThought)
                                        Object.assign(thought, potentiallyUpdatedThought); // Update local copy
                                }
                            }
                        }
                        // b) Get Insight Model suggestions (conditionally)
                        const shouldGetInsights = ['note', 'goal', 'question'].includes(thought.type) && Math.random() < 0.1; // Reduced frequency
                        if (shouldGetInsights && this.insightModel['llm']) {
                            console.log(`Requesting InsightModel suggestions for thought ${thought.id.substring(0, 8)}`);
                            // Simplified context fetching: just parent/child IDs if available
                            const linkedThoughtIds = thought.links.map(l => l.targetId).slice(0, 5); // Limit context size
                            const contextThoughts = (await Promise.all(linkedThoughtIds.map(id => this.thoughtStore.getThought(id))))
                                .filter((t) => t !== null); // Type guard
                            const suggestions = await this.insightModel.getSuggestions(thought, contextThoughts);
                            if (suggestions.length > 0 && !suggestions[0].startsWith("InsightModel disabled") && !suggestions[0].startsWith("Error")) {
                                // Update thought with suggestions - use updateThought method
                                await this.updateThought(thought.id, { metadata: { aiSuggestions: suggestions } });
                                insightsGenerated++;
                                modifiedByCycle = true; // Ensure we know it was modified
                                console.log(`Suggestions for ${thought.id.substring(0, 8)}: ${suggestions.join('; ')}`);
                            }
                        }
                        thoughtsProcessed++; // Count this thought as processed in this phase
                    }
                }
            }
            console.log(`Reasoner cycle finished. Processed thoughts/tasks: ${thoughtsProcessed}, Guides applied: ${guidesApplied}, Insights generated: ${insightsGenerated}.`);
            this.system.broadcast({ type: 'status_update', payload: { message: `Reasoner cycle finished. Processed: ${thoughtsProcessed}` } });
        }
        catch (error) {
            console.error("Error during Reasoner cycle:", error);
            this.system.broadcast({ type: 'error', payload: { message: 'Error during Reasoner cycle', details: error instanceof Error ? error.message : String(error) } });
        }
        finally {
            this.processing = false;
        }
    }
    // Evaluate Guide Condition (Simple DSL Parser)
    evaluateGuideCondition(condition, thought) {
        // Example DSL: "type=note & tag=urgent", "priority_gt=0.8", "content_contains=meeting"
        const conditions = condition.split('&').map(c => c.trim()).filter(c => c.length > 0);
        if (conditions.length === 0)
            return false; // Empty condition is false
        return conditions.every(cond => {
            const parts = cond.match(/^([a-zA-Z_]+)(=|!=|_gt|_lt|_contains)(.+)$/);
            if (!parts || parts.length !== 4) {
                console.warn(`Malformed condition part: ${cond}`);
                return false;
            }
            const [, key, operator, valueStr] = parts;
            const value = valueStr.trim();
            // Access thought properties, including metadata
            let thoughtValue;
            if (key === 'tag') {
                thoughtValue = thought.metadata.tags || [];
            }
            else if (key === 'content') {
                thoughtValue = typeof thought.content === 'string' ? thought.content : JSON.stringify(thought.content);
            }
            else if (key === 'priority' || key === 'weight') { // Handle numeric comparisons
                thoughtValue = thought[key];
            }
            else if (thought.hasOwnProperty(key)) {
                thoughtValue = thought[key];
            }
            else if (thought.metadata.hasOwnProperty(key)) {
                thoughtValue = thought.metadata[key];
            }
            else {
                return false; // Key not found
            }
            // Evaluate based on operator
            switch (operator) {
                case '=':
                    return String(thoughtValue) === value;
                case '!=':
                    return String(thoughtValue) !== value;
                case '_gt':
                    return typeof thoughtValue === 'number' && thoughtValue > parseFloat(value);
                case '_lt':
                    return typeof thoughtValue === 'number' && thoughtValue < parseFloat(value);
                case '_contains':
                    if (key === 'tag' && Array.isArray(thoughtValue))
                        return thoughtValue.includes(value);
                    if (typeof thoughtValue === 'string')
                        return thoughtValue.toLowerCase().includes(value.toLowerCase());
                    return false;
                default: return false;
            }
        });
    }
    // Apply Guide Action (Simple DSL Parser) - Can modify thought or enqueue task
    async applyGuideAction(action, thought) {
        // Example DSL: "set priority=0.9", "add_tag=important", "create_task=Follow up", "link_to=uuid:child"
        const parts = action.match(/^([a-zA-Z_]+)(?:=|:)(.+)$/);
        if (!parts || parts.length !== 3) {
            console.warn(`Malformed action: ${action}`);
            return false;
        }
        const [, command, valueStr] = parts;
        const value = valueStr.trim();
        try {
            switch (command) {
                case 'set':
                    const [setKey, setValue] = value.split('=', 2);
                    if (setKey && setValue !== undefined) {
                        let updatePayload = {};
                        if (setKey === 'priority' || setKey === 'weight')
                            updatePayload[setKey] = parseFloat(setValue);
                        else if (setKey === 'type')
                            updatePayload.type = setValue;
                        else if (setKey === 'status')
                            updatePayload.metadata = { status: setValue };
                        else { /* Allow setting other metadata? */
                            updatePayload.metadata = { [setKey]: setValue };
                        }
                        await this.updateThought(thought.id, updatePayload);
                        return true;
                    }
                    return false;
                case 'add_tag':
                    if (!thought.metadata.tags?.includes(value)) {
                        const newTags = [...(thought.metadata.tags || []), value];
                        await this.updateThought(thought.id, { metadata: { tags: newTags } });
                        return true;
                    }
                    return false; // Tag already exists
                case 'remove_tag':
                    if (thought.metadata.tags?.includes(value)) {
                        const newTags = thought.metadata.tags.filter(t => t !== value);
                        await this.updateThought(thought.id, { metadata: { tags: newTags } });
                        return true;
                    }
                    return false; // Tag not found
                case 'create_task':
                    // Enqueue task creation via Tool
                    await this.taskQueue.enqueueTask(thought.id, 'CreateChildTaskTool', { parentId: thought.id, content: value });
                    // Return true because an action was taken (task enqueued), even if thought wasn't directly modified *yet*
                    return true;
                case 'link_to':
                    const [targetId, relationship = 'related'] = value.split(':', 2);
                    const targetExists = await this.thoughtStore.getThought(targetId);
                    if (targetExists && !thought.links.some(l => l.targetId === targetId && l.relationship === relationship)) {
                        const newLinks = [...thought.links, { targetId, relationship }];
                        await this.updateThought(thought.id, { links: newLinks });
                        return true;
                    }
                    if (!targetExists)
                        console.warn(`Link target ${targetId} does not exist.`);
                    return false; // Link exists or target not found
                default:
                    console.warn(`Unsupported guide action command: ${command}`);
                    return false;
            }
        }
        catch (error) {
            console.error(`Error applying guide action "${action}" to thought ${thought.id}:`, error);
            return false;
        }
    }
}
// --- Concrete Tool Implementations ---
class GenerateEmbeddingTool {
    name = "GenerateEmbeddingTool";
    description = "Generates and stores an embedding for a thought's content.";
    async execute(params, context) {
        const { thoughtId } = params;
        let thought = await context.thoughtStore.getThought(thoughtId);
        if (!thought)
            throw new Error(`Thought ${thoughtId} not found for embedding.`);
        const contentToEmbed = params.text ?? (typeof thought.content === 'string' ? thought.content : JSON.stringify(thought.content));
        if (!contentToEmbed || !contentToEmbed.trim()) {
            console.log(`Skipping embedding for ${thoughtId}: empty content.`);
            // Mark as completed even if skipped
            await context.flowMindSystem.reasoner.updateThought(thoughtId, { metadata: { status: 'completed' } });
            return;
        }
        console.log(`Generating embedding for thought ${thoughtId}...`);
        const embedding = await context.insightModel.generateEmbedding(contentToEmbed);
        // Fetch thought again in case it changed during embedding generation
        thought = await context.thoughtStore.getThought(thoughtId);
        if (!thought) {
            console.warn(`Thought ${thoughtId} disappeared during embedding generation.`);
            return;
        }
        if (embedding) {
            // Instead of storing embedding directly on thought, rely on vector store's mechanism.
            // We trigger the vector store update via putThought in ThoughtStore.
            // Just update metadata here.
            await context.flowMindSystem.reasoner.updateThought(thoughtId, {
                metadata: { embeddingGeneratedAt: new Date().toISOString(), status: 'completed' }
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
    description = "Creates a new task thought linked as a child to a parent thought.";
    async execute(params, context) {
        const { parentId, content, priority = 0.6 } = params;
        const parentThought = await context.thoughtStore.getThought(parentId);
        if (!parentThought)
            throw new Error(`Parent thought ${parentId} not found.`);
        // Use Reasoner's createThought method to ensure proper initialization and broadcasting
        const taskThought = await context.flowMindSystem.reasoner.createThought(content, 'task', priority, [{ relationship: 'parent', targetId: parentId }], { tags: ['task'] } // Add task tag by default
        );
        // Add link back from parent to child (use updateThought)
        const parentLinks = [...parentThought.links, { relationship: 'child', targetId: taskThought.id }];
        await context.flowMindSystem.reasoner.updateThought(parentId, { links: parentLinks });
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
        const results = await context.thoughtStore.semanticSearchThoughts(query, k);
        console.log(`MemoryTool found ${results.length} results for query "${query}"`);
        // Return results directly, could be used by other tools or sent to UI
        return { results };
    }
}
class ShareTool {
    name = "ShareTool";
    description = "Exports thoughts to JSON.";
    async execute(params, context) {
        // (Implementation remains the same)
        const { thoughtIds } = params;
        if (!thoughtIds || thoughtIds.length === 0)
            throw new Error("thoughtIds required.");
        const thoughtsToExport = [];
        for (const id of thoughtIds) {
            const thought = await context.thoughtStore.getThought(id);
            if (thought)
                thoughtsToExport.push(thought);
            else
                console.warn(`ShareTool: Thought ${id} not found.`);
        }
        const exportedJson = JSON.stringify(thoughtsToExport, null, 2);
        console.log(`ShareTool exported ${thoughtsToExport.length} thoughts.`);
        return { exportedJson }; // Return JSON string
    }
}
// --- Main System Class ---
class FlowMindSystem {
    thoughtStore;
    insightModel;
    toolHub;
    taskQueue;
    reasoner;
    wss = null;
    clients = new Set();
    processingInterval = null;
    isPaused = true; // Start paused
    constructor(dbPath = DB_PATH, vectorStorePath = VECTOR_STORE_PATH) {
        this.thoughtStore = new ThoughtStore(dbPath, vectorStorePath);
        this.insightModel = new InsightModel();
        this.toolHub = new ToolHub();
        // Pass 'this' (FlowMindSystem) to components that need it
        this.taskQueue = new TaskQueue(this.thoughtStore, this.toolHub, this.insightModel, this);
        this.reasoner = new Reasoner(this.thoughtStore, this.taskQueue, this.toolHub, this.insightModel, this);
        this.registerDefaultTools();
    }
    registerDefaultTools() {
        this.toolHub.registerTool(new GenerateEmbeddingTool());
        this.toolHub.registerTool(new CreateChildTaskTool());
        this.toolHub.registerTool(new MemoryTool());
        this.toolHub.registerTool(new ShareTool());
    }
    async initialize(server) {
        await this.thoughtStore.open();
        // Pass system reference AFTER DB is open
        await this.thoughtStore.initialize(this);
        await this.reasoner.loadGuides();
        await this.taskQueue.processPendingTasks(10); // Process some pending tasks on startup
        this.setupWebSocketServer(server);
        console.log("FlowMind Enhanced system initialized.");
        this.broadcast({ type: 'status_update', payload: { message: 'System Initialized. Paused.', paused: this.isPaused } });
    }
    // Setup WebSocket Server
    setupWebSocketServer(server) {
        this.wss = new ws_1.WebSocketServer({ server });
        console.log(`WebSocket server started on port ${SERVER_PORT}.`);
        this.wss.on('connection', (ws) => {
            console.log('Client connected.');
            this.clients.add(ws);
            // Send initial graph state to the newly connected client
            this.sendInitialGraphState(ws);
            // Send current pause state
            ws.send(JSON.stringify({ type: 'status_update', payload: { paused: this.isPaused } }));
            ws.on('message', (message) => {
                try {
                    const parsedMessage = JSON.parse(message.toString());
                    this.handleWebSocketMessage(ws, parsedMessage);
                }
                catch (error) {
                    console.error('Failed to parse WebSocket message or handle:', error);
                    ws.send(JSON.stringify({ type: 'error', payload: { message: 'Invalid message format' } }));
                }
            });
            ws.on('close', () => {
                console.log('Client disconnected.');
                this.clients.delete(ws);
            });
            ws.on('error', (error) => {
                console.error('WebSocket error:', error);
                this.clients.delete(ws); // Remove client on error
            });
        });
    }
    // Send the complete current graph state to a client
    async sendInitialGraphState(ws) {
        try {
            const graphData = await this.thoughtStore.getAllThoughtsAndLinks();
            const message = { type: 'init', payload: graphData };
            ws.send(JSON.stringify(message));
            console.log(`Sent initial graph state to client (${graphData.nodes.length} nodes, ${graphData.links.length} links).`);
        }
        catch (error) {
            console.error('Error sending initial graph state:', error);
            ws.send(JSON.stringify({ type: 'error', payload: { message: 'Failed to retrieve graph state' } }));
        }
    }
    // Handle incoming messages from clients
    async handleWebSocketMessage(ws, message) {
        console.log('Received message:', message.type, message.payload);
        try {
            switch (message.type) {
                case 'request_graph':
                    await this.sendInitialGraphState(ws);
                    break;
                case 'add_thought':
                    const { content, type, priority, links, metadata } = message.payload;
                    const newThought = await this.reasoner.createThought(content, type, priority, links, metadata);
                    // No need to broadcast here, putThought already does
                    break;
                case 'update_thought':
                    const { id, ...updates } = message.payload;
                    // Basic validation
                    if (typeof id !== 'string' || typeof updates !== 'object')
                        throw new Error("Invalid update_thought payload");
                    // If UI sends position, store it in metadata
                    if (updates.ui && (updates.ui.x !== undefined || updates.ui.y !== undefined || updates.ui.z !== undefined)) {
                        const currentThought = await this.thoughtStore.getThought(id);
                        if (currentThought) {
                            const newMeta = { ...currentThought.metadata, ui: { ...currentThought.metadata.ui, ...updates.ui } };
                            await this.reasoner.updateThought(id, { metadata: newMeta });
                            // Avoid processing the rest of the updates if only UI changed? For now, allow other updates too.
                        }
                    }
                    // Apply other updates if present
                    const updatePayload = { ...updates };
                    delete updatePayload.ui; // Don't pass UI directly if handled above
                    if (Object.keys(updatePayload).length > 0) {
                        await this.reasoner.updateThought(id, updatePayload);
                    }
                    break;
                case 'delete_thought':
                    if (typeof message.payload.id === 'string') {
                        await this.thoughtStore.deleteThought(message.payload.id);
                        // Broadcast handled by deleteThought
                    }
                    else
                        throw new Error("Invalid delete_thought payload: id missing");
                    break;
                case 'run_tool':
                    const { toolName, params, thoughtId } = message.payload;
                    if (typeof toolName !== 'string' || typeof params !== 'object')
                        throw new Error("Invalid run_tool payload");
                    // Use invokeTool which handles enqueueing vs direct execution
                    const result = await this.invokeTool(toolName, params, thoughtId); // thoughtId can be undefined
                    // Optionally send result back to requesting client?
                    ws.send(JSON.stringify({ type: 'status_update', payload: { message: `Tool ${toolName} invoked.`, result: result } }));
                    break;
                case 'add_guide':
                    const { condition, action, weight } = message.payload;
                    if (typeof condition !== 'string' || typeof action !== 'string')
                        throw new Error("Invalid add_guide payload");
                    await this.addGuide(condition, action, weight);
                    // Optionally send confirmation back
                    ws.send(JSON.stringify({ type: 'status_update', payload: { message: `Guide added: ${condition}` } }));
                    break;
                case 'control':
                    this.handleControlCommand(message.payload);
                    break;
                default:
                    console.warn(`Unhandled WebSocket message type: ${message.type}`);
                    ws.send(JSON.stringify({ type: 'error', payload: { message: `Unhandled message type: ${message.type}` } }));
            }
        }
        catch (error) {
            console.error(`Error handling WebSocket message type ${message.type}:`, error);
            ws.send(JSON.stringify({ type: 'error', payload: { message: `Error processing ${message.type}`, details: error.message } }));
        }
    }
    // Handle control commands (run/pause/step)
    handleControlCommand(payload) {
        switch (payload.command) {
            case 'run':
                this.isPaused = false;
                this.startAutoProcessing(15000); // Use default interval or payload.interval
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
                    this.reasoner.processCycle(5).catch(err => console.error("Step execution failed:", err)); // Run a small cycle
                }
                else {
                    console.log("Cannot step while running.");
                    this.broadcast({ type: 'status_update', payload: { message: 'Cannot step while running.' } });
                }
                break;
            case 'clear_all':
                console.warn("Received 'clear_all' command. This is destructive!");
                // Implement safety checks or confirmation if needed in a real app
                this.clearAllData().then(() => {
                    this.broadcast({ type: 'init', payload: { nodes: [], links: [] } }); // Send empty graph
                    this.broadcast({ type: 'status_update', payload: { message: 'All data cleared.' } });
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
                client.send(messageString);
            }
        });
        // Log broadcast types other than frequent updates like status
        if (message.type !== 'status_update' && message.type !== 'log') {
            // console.log(`Broadcasted message type: ${message.type}`);
        }
    }
    // Start/Stop Auto Processing
    startAutoProcessing(intervalMs = 15000) {
        if (this.processingInterval || this.isPaused)
            return; // Don't start if already running or paused
        console.log(`Starting auto-processing cycle every ${intervalMs}ms.`);
        this.processingInterval = setInterval(() => {
            if (!this.isPaused) {
                this.reasoner.processCycle().catch(err => {
                    console.error("Error in scheduled Reasoner cycle:", err);
                    this.broadcast({ type: 'error', payload: { message: 'Error in Reasoner cycle', details: err.message } });
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
            this.wss.close(() => {
                console.log("WebSocket server closed.");
            });
            // Force close client connections
            this.clients.forEach(client => client.terminate());
        }
        await this.thoughtStore.close(); // Saves vectors, closes DB
        console.log("FlowMind Enhanced system shut down gracefully.");
    }
    // Destructive: Clear all data (thoughts, guides, events, vectors)
    async clearAllData() {
        console.warn("--- CLEARING ALL FLOWMIND DATA ---");
        this.stopAutoProcessing(); // Ensure nothing is running
        // Close and reopen the DB to clear it (LevelDB doesn't have a simple clear all)
        await this.thoughtStore.close();
        // Delete database directory
        try {
            await fs.rm(this.thoughtStore['db'].location, { recursive: true, force: true });
            console.log(`Deleted LevelDB database at ${this.thoughtStore['db'].location}`);
        }
        catch (err) {
            console.error(`Error deleting LevelDB directory:`, err);
        }
        // Delete vector store directory
        try {
            await fs.rm(this.vectorStorePath, { recursive: true, force: true });
            console.log(`Deleted vector store at ${this.vectorStorePath}`);
        }
        catch (err) {
            console.error(`Error deleting vector store directory:`, err);
        }
        // Re-initialize components with empty state
        this.thoughtStore = new ThoughtStore(DB_PATH, VECTOR_STORE_PATH);
        this.taskQueue = new TaskQueue(this.thoughtStore, this.toolHub, this.insightModel, this);
        this.reasoner = new Reasoner(this.thoughtStore, this.taskQueue, this.toolHub, this.insightModel, this);
        await this.thoughtStore.open();
        await this.thoughtStore.initialize(this); // Re-initialize empty vector store etc.
        await this.reasoner.loadGuides(); // Reload empty guides
        console.warn("--- ALL DATA CLEARED ---");
        // Restart processing if it was running before clear? Default to paused.
        this.isPaused = true;
        this.broadcast({ type: 'status_update', payload: { message: 'All data cleared. System Paused.', paused: true } });
    }
    // --- High-Level API Wrappers ---
    async addThought(content, type, priority, links, metadata) {
        return this.reasoner.createThought(content, type, priority, links, metadata);
    }
    async getThought(id) { return this.thoughtStore.getThought(id); }
    async updateThought(id, updates) { return this.reasoner.updateThought(id, updates); }
    async deleteThought(id) { await this.thoughtStore.deleteThought(id); }
    async listThoughts(options) { return this.thoughtStore.listThoughts(options); }
    async findThoughts(query) {
        // Combine keyword and semantic search (simplified)
        const semanticResults = await this.thoughtStore.semanticSearchThoughts(query);
        // Could add simple keyword search here if needed
        return semanticResults;
    }
    async addGuide(condition, action, weight = 0.5) {
        const now = new Date().toISOString();
        const guide = { id: generateId(), condition, action, weight, metadata: { createdAt: now, updatedAt: now } };
        await this.thoughtStore.putGuide(guide);
        await this.reasoner.loadGuides(); // Reload guides
        this.broadcast({ type: 'status_update', payload: { message: `Guide added: ${condition}` } });
        return guide;
    }
    async listGuides() { return this.thoughtStore.listGuides(); }
    async invokeTool(toolName, params, thoughtId) {
        // Enqueue if thoughtId provided, else execute directly
        if (thoughtId) {
            await this.taskQueue.enqueueTask(thoughtId, toolName, params);
            return { message: `Task ${toolName} enqueued for thought ${thoughtId}` };
        }
        else {
            const context = { thoughtStore: this.thoughtStore, taskQueue: this.taskQueue, insightModel: this.insightModel, flowMindSystem: this };
            return this.toolHub.executeTool(toolName, params, context);
        }
    }
    async getEvents(options) { return this.thoughtStore.listEvents(options); }
}
exports.FlowMindSystem = FlowMindSystem;
// --- Web Server and Client UI ---
const HTML_CONTENT = `
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>FlowMind Enhanced</title>
    <style>
        body { margin: 0; overflow: hidden; background-color: #111; color: #eee; font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; }
        #graph-container { position: absolute; top: 0; left: 0; width: 100%; height: 100%; }
        #css-container { position: absolute; top: 0; left: 0; width: 100%; height: 100%; pointer-events: none; /* Allow interaction with canvas below */ }
        canvas { display: block; }

        .hud { position: absolute; top: 10px; left: 10px; background: rgba(0,0,0,0.7); padding: 10px; border-radius: 5px; color: #ccc; font-size: 12px; max-width: 300px; }
        .hud h3 { margin: 0 0 5px 0; color: #fff; border-bottom: 1px solid #555; padding-bottom: 3px;}
        .hud button { background-color: #444; border: 1px solid #666; color: #eee; padding: 3px 8px; margin: 2px; border-radius: 3px; cursor: pointer; }
        .hud button:hover { background-color: #555; }
        .hud button:active { background-color: #333; }
        .hud .status { margin-top: 5px; }
        .hud .status span { display: inline-block; margin-right: 10px; }
        .hud .logs { margin-top: 5px; max-height: 100px; overflow-y: auto; border-top: 1px solid #444; padding-top: 5px; }
        .hud .logs div { margin-bottom: 3px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        .hud .logs .error { color: #f99; }

        .node-label {
            position: absolute; /* Needed for CSS3DRenderer */
            background: rgba(30, 30, 30, 0.8);
            color: #eee;
            padding: 5px 10px;
            border-radius: 5px;
            font-size: 12px;
            pointer-events: all; /* Allow clicks on labels */
            cursor: pointer;
            border: 1px solid #555;
            min-width: 100px;
            max-width: 250px;
            white-space: normal; /* Allow wrapping */
            box-shadow: 0 0 5px rgba(0,0,0,0.5);
        }
        .node-label h4 { margin: 0 0 3px 0; font-size: 13px; color: #fff; border-bottom: 1px solid #666; padding-bottom: 2px; }
        .node-label p { margin: 2px 0; font-size: 11px; overflow-wrap: break-word; }
        .node-label .meta { font-size: 10px; color: #aaa; }
        .node-label .actions button { font-size: 10px; padding: 1px 4px; margin: 1px;}
        .node-label input, .node-label textarea {
             background: #333; color: #eee; border: 1px solid #555; font-size: 11px; width: 95%; margin-top: 3px; padding: 2px;
        }
        .node-label textarea { min-height: 40px; }
    </style>
</head>
<body>
    <div id="graph-container"></div>
    <div id="css-container"></div>

    <div class="hud">
        <h3>FlowMind Control</h3>
        <button id="run-btn">Run</button>
        <button id="pause-btn">Pause</button>
        <button id="step-btn">Step</button>
        <button id="add-node-btn">+</button>
         <button id="clear-all-btn" title="Clear All Data (Destructive!)">Clear All</button>
        <div class="status">
            Status: <span id="system-status">Initializing...</span> | Paused: <span id="pause-status">Yes</span>
        </div>
        <div class="logs" id="log-output">
            <div>Welcome to FlowMind Enhanced!</div>
        </div>
         <div class="fov-control" style="margin-top: 5px;">
            FOV: <input type="range" id="fov-slider" min="30" max="120" value="75" style="width: 100px; vertical-align: middle;"> <span id="fov-value">75</span>°
        </div>
    </div>

    <!-- Load libraries from CDN -->
    <script src="https://unpkg.com/three@0.164.1/build/three.min.js"></script>
    <script src="https://unpkg.com/three@0.164.1/examples/jsm/controls/OrbitControls.js"></script>
    <script src="https://unpkg.com/three@0.164.1/examples/jsm/renderers/CSS3DRenderer.js"></script>
     <!-- Use ForceGraph library -->
    <script src="//unpkg.com/three-forcegraph@1.43.4/dist/three-forcegraph.min.js"></script>
     <!-- Optional: TWEEN for smooth animations -->
     <script src="https://unpkg.com/@tweenjs/tween.js@^18/dist/tween.umd.js"></script>


    <script>
        // --- Client-Side Logic ---
        const { Scene, PerspectiveCamera, WebGLRenderer, Color, Fog, AmbientLight, DirectionalLight, SphereGeometry, MeshStandardMaterial, Mesh, LineBasicMaterial, BufferGeometry, Line, Vector3, Raycaster, Vector2, Object3D } = THREE;
        const { CSS3DRenderer, CSS3DObject } = THREE.CSS3DRenderer;
        const { OrbitControls } = THREE.OrbitControls;
        const ForceGraph = ThreeForceGraph; // Alias the library

        let scene, camera, renderer, cssRenderer, controls, graph, animationId;
        let webSocket;
        let isPaused = true; // Client-side pause state mirror
        const mouse = new Vector2();
        const raycaster = new Raycaster();
        letINTERSECTED; // For mouse hover detection

        const nodeObjects = new Map(); // Map thought ID to Three.js Object3D
        const htmlElements = new Map(); // Map thought ID to HTML element container

        // HUD Elements
        const runBtn = document.getElementById('run-btn');
        const pauseBtn = document.getElementById('pause-btn');
        const stepBtn = document.getElementById('step-btn');
        const addNodeBtn = document.getElementById('add-node-btn');
        const clearAllBtn = document.getElementById('clear-all-btn');
        const systemStatus = document.getElementById('system-status');
        const pauseStatus = document.getElementById('pause-status');
        const logOutput = document.getElementById('log-output');
        const fovSlider = document.getElementById('fov-slider');
        const fovValue = document.getElementById('fov-value');


        function init() {
            // Scene
            scene = new Scene();
            scene.background = new Color(0x111111);
            scene.fog = new Fog(0x111111, 500, 2000);

            // Camera
            camera = new PerspectiveCamera(75, window.innerWidth / window.innerHeight, 0.1, 3000);
            camera.position.z = 400; // Start further back

            // Renderer
            const container = document.getElementById('graph-container');
            renderer = new WebGLRenderer({ antialias: true });
            renderer.setSize(window.innerWidth, window.innerHeight);
            renderer.setPixelRatio(window.devicePixelRatio);
            container.appendChild(renderer.domElement);

            // CSS3D Renderer (for HTML labels)
            const cssContainer = document.getElementById('css-container');
            cssRenderer = new CSS3DRenderer();
            cssRenderer.setSize(window.innerWidth, window.innerHeight);
            cssContainer.appendChild(cssRenderer.domElement);

            // Lights
            scene.add(new AmbientLight(0xcccccc, 0.8)); // Softer ambient light
            const dirLight = new DirectionalLight(0xffffff, 1.5); // Stronger directional
            dirLight.position.set(50, 200, 100);
            scene.add(dirLight);


            // Controls
            controls = new OrbitControls(camera, cssRenderer.domElement); // Control using the CSS renderer's element
            controls.enableDamping = true;
            controls.dampingFactor = 0.1;
            controls.screenSpacePanning = false; // Panning moves the target
            controls.minDistance = 5;
            controls.maxDistance = 1500;
            // controls.addEventListener('change', render); // Render on control change

             // Force Graph
            graph = new ForceGraph()
                (cssContainer) // Render within the CSS container initially
                .graphData({ nodes: [], links: [] })
                .nodeId('id')
                .linkSource('source')
                .linkTarget('target')
                .linkDirectionalParticles(1)
                .linkDirectionalParticleWidth(1.5)
                .linkWidth(0.5)
                .nodeThreeObject(node => createNodeObject(node)) // Use CSS3DObject for nodes
                .linkThreeObject(link => { // Simple line for links in WebGL space
                    const geometry = new BufferGeometry().setFromPoints([new Vector3(0,0,0), new Vector3(0,0,0)]); // Placeholder points
                    const material = new LineBasicMaterial({ color: 0x666666, transparent: true, opacity: 0.5 });
                    return new Line(geometry, material);
                })
                .linkPositionUpdate((line, { start, end }, link) => {
                    if (!line) return;
                    const geometry = line.geometry;
                    const positions = geometry.attributes.position;
                    positions.setXYZ(0, start.x, start.y, start.z);
                    positions.setXYZ(1, end.x, end.y, end.z);
                    positions.needsUpdate = true;
                    geometry.computeBoundingSphere(); // Important for visibility checks
                })
                .onNodeClick(onNodeClick)
                .onNodeRightClick(onNodeRightClick)
                 // Adjust forces
                .d3Force('charge', d3.forceManyBody().strength(-150)) // Repulsion force
                .d3Force('link', d3.forceLink().id(d => d.id).distance(60).strength(0.5)) // Link force
                .d3Force('center', d3.forceCenter(0, 0)); // Centering force

            // Add the graph's scene content to our main scene
             // graph.renderer().domElement points to the internal canvas, we dont want that directly
             // Instead, graph updates objects that we *manually* add to our scene.
             // The library handles the scene internally, we just visualize its output via nodeThreeObject/linkThreeObject
             // Let's add the graph object itself to the scene.
             scene.add(graph);


            // Event Listeners
            window.addEventListener('resize', onWindowResize);
            container.addEventListener('mousemove', onMouseMove, false); // Use container for mouse move
             // Add wheel listener for FOV (Shift+Wheel or horizontal scroll)
            container.addEventListener('wheel', onMouseWheel, { passive: false });


            // HUD Controls Setup
            runBtn.onclick = () => sendControlCommand('run');
            pauseBtn.onclick = () => sendControlCommand('pause');
            stepBtn.onclick = () => sendControlCommand('step');
            addNodeBtn.onclick = addNewThought;
            clearAllBtn.onclick = () => {
                if (confirm("Are you sure you want to delete ALL thoughts and data? This cannot be undone!")) {
                     sendControlCommand('clear_all');
                }
            };
             fovSlider.oninput = (e) => {
                const newFov = parseInt(e.target.value);
                camera.fov = newFov;
                camera.updateProjectionMatrix();
                fovValue.textContent = newFov;
                // render(); // Render immediately after FOV change
            };


            // Connect WebSocket
            connectWebSocket();

            // Start Animation Loop
            animate();
        }

        // Create Node Object (HTML Element)
        function createNodeObject(nodeData) {
            const element = document.createElement('div');
            element.className = 'node-label';
            element.id = \`node-\${nodeData.id}\`;
            updateNodeElementContent(element, nodeData); // Initial content population

            // Store element reference
            htmlElements.set(nodeData.id, element);

            // Interaction directly on the HTML element
            element.addEventListener('click', (event) => {
                event.stopPropagation(); // Prevent triggering canvas click
                console.log('Label clicked:', nodeData.id);
                // Maybe focus or highlight the node
                 graph.centerAt(nodeData.x, nodeData.y, 500); // Center view smoothly
            });

             element.addEventListener('contextmenu', (event) => {
                event.preventDefault();
                event.stopPropagation();
                onNodeRightClick(nodeData); // Trigger zoom on right-click
            });


            const cssObject = new CSS3DObject(element);
            nodeObjects.set(nodeData.id, cssObject); // Store CSS3DObject reference

            // Initial position setting (optional, force graph handles it)
             // cssObject.position.set(nodeData.x || 0, nodeData.y || 0, nodeData.z || 0);

            return cssObject;
        }

        // Update HTML content of a node
        function updateNodeElementContent(element, nodeData) {
             const contentStr = typeof nodeData.content === 'object' ? JSON.stringify(nodeData.content) : String(nodeData.content);
             const shortContent = contentStr.length > 100 ? contentStr.substring(0, 97) + '...' : contentStr;
             const tags = (nodeData.metadata?.tags || []).join(', ');
             const status = nodeData.metadata?.status || 'n/a';
             const suggestions = (nodeData.metadata?.aiSuggestions || []).map(s => \`- \${s}\`).join('<br>');

             
             const label = nodeData.metadata?.errorInfo ? \'<span class=\"error\">(Error)</span>\': \'\';
             element.innerHTML = \`
                <h4>\${nodeData.type}: \${nodeData.id.substring(0, 8)}</h4>
                <p>\${shortContent}</p>
                <div class="meta">
                    Priority: \${nodeData.priority?.toFixed(2)} | Status: \${status} \${label} <br>
                    Tags: \${tags || 'none'} <br>
                    Links: \${nodeData.links?.length || 0}
                </div>
                 \${suggestions ? \`<div class="meta suggestions" style="margin-top: 5px; padding-top: 3px; border-top: 1px dashed #555;"><b>Suggestions:</b><br>\${suggestions}</div>\` : ''}
                <div class="actions">
                     <button onclick="editNode('\${nodeData.id}')">Edit</button>
                     <button onclick="deleteNode('\${nodeData.id}')">Del</button>
                     <button onclick="runMemoryTool('\${nodeData.id}')">Find Related</button>
                </div>
                <div id="edit-\${nodeData.id}" style="display: none;">
                     <textarea placeholder="Content...">\${contentStr}</textarea>
                     <input type="number" step="0.1" min="0" max="1" value="\${nodeData.priority}" placeholder="Priority">
                     <button onclick="saveNodeEdit('\${nodeData.id}')">Save</button>
                </div>
            \`;
             // Set border color based on type or status?
            element.style.borderColor = getNodeColor(nodeData.type);
        }

         function getNodeColor(type) {
            switch(type) {
                case 'goal': return '#4CAF50'; // Green
                case 'task': return '#FFC107'; // Amber
                case 'question': return '#2196F3'; // Blue
                case 'insight': return '#9C27B0'; // Purple
                case 'note': return '#9E9E9E'; // Grey
                default: return '#607D8B'; // Blue Grey
            }
        }


        // --- Interaction Handlers ---
        function onNodeClick(node, event) {
            console.log("Node clicked (ForceGraph):", node.id);
            // Smoothly center the view on the clicked node
            // graph.centerAt uses the graph's internal coordinate system
            graph.centerAt(node.x, node.y, 500); // Center view smoothly (500ms duration)
            camera.lookAt(node.x, node.y, node.z); // Ensure camera looks directly at it
        }

         function onNodeRightClick(node, event) {
             if (event) event.preventDefault(); // Prevent browser context menu
             console.log("Node right-clicked:", node.id);
             // Auto-zoom: Move camera closer and target the node
             const nodePosition = new Vector3(node.x, node.y, node.z);
             const distance = 100; // Desired distance from node
             const offset = camera.position.clone().sub(nodePosition).normalize().multiplyScalar(distance);
             const newCamPos = nodePosition.clone().add(offset);

             // Use TWEEN for smooth transition
             new TWEEN.Tween(camera.position)
                 .to(newCamPos, 800) // Target camera position, duration 800ms
                 .easing(TWEEN.Easing.Quadratic.InOut)
                 .start();

             new TWEEN.Tween(controls.target)
                 .to(nodePosition, 800) // Target controls target
                 .easing(TWEEN.Easing.Quadratic.InOut)
                 .onUpdate(() => controls.update()) // Need to call update for target change
                 .start();
         }

        function onWindowResize() {
            camera.aspect = window.innerWidth / window.innerHeight;
            camera.updateProjectionMatrix();
            renderer.setSize(window.innerWidth, window.innerHeight);
            cssRenderer.setSize(window.innerWidth, window.innerHeight);
            // render(); // Render needed after resize
        }

         function onMouseMove(event) {
            // For potential hover effects (currently disabled for simplicity with CSS3DObjects)
             /*
            mouse.x = (event.clientX / window.innerWidth) * 2 - 1;
            mouse.y = -(event.clientY / window.innerHeight) * 2 + 1;
            checkForIntersection();
            */
        }

        function onMouseWheel(event) {
            // Default OrbitControls handles zoom (dolly) with vertical scroll

            // Horizontal scroll (or Shift+Wheel) for FOV adjustment
            let delta = 0;
            if (event.shiftKey) {
                delta = event.deltaY * -0.05; // Use vertical scroll with shift
            } else if (Math.abs(event.deltaX) > Math.abs(event.deltaY)) {
                delta = event.deltaX * -0.05; // Use horizontal scroll if dominant
            }

            if (delta !== 0) {
                 event.preventDefault(); // Prevent default horizontal scroll
                 const currentFov = camera.fov;
                 let newFov = Math.max(30, Math.min(120, currentFov + delta)); // Clamp FOV
                 camera.fov = newFov;
                 camera.updateProjectionMatrix();
                 fovSlider.value = newFov; // Update slider
                 fovValue.textContent = Math.round(newFov);
                 // render(); // Render immediately
            }
             // Let OrbitControls handle vertical scroll for zoom
        }


        // --- WebSocket Handling ---
        function connectWebSocket() {
            const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
            const wsUrl = \`\${wsProtocol}//\${window.location.host}\`;
            webSocket = new WebSocket(wsUrl);

            webSocket.onopen = () => {
                logMessage('WebSocket connected.');
                updateStatus('Connected. Requesting graph...');
                // Request initial state or wait for push
                // sendMessage({ type: 'request_graph', payload: {} });
            };

            webSocket.onmessage = (event) => {
                try {
                    const message = JSON.parse(event.data);
                    handleServerMessage(message);
                } catch (e) {
                    logMessage('Error parsing server message.', 'error');
                    console.error(e);
                }
            };

            webSocket.onerror = (error) => {
                logMessage('WebSocket error. Check console.', 'error');
                updateStatus('Connection Error');
                console.error('WebSocket Error:', error);
            };

            webSocket.onclose = () => {
                logMessage('WebSocket disconnected. Attempting to reconnect...', 'error');
                updateStatus('Disconnected');
                // Simple reconnect logic
                setTimeout(connectWebSocket, 5000);
            };
        }

        function sendMessage(message) {
            if (webSocket && webSocket.readyState === WebSocket.OPEN) {
                webSocket.send(JSON.stringify(message));
            } else {
                logMessage('WebSocket not connected. Message not sent.', 'error');
            }
        }

        function handleServerMessage(message) {
             // console.log('Server message:', message.type); // Can be verbose
             switch (message.type) {
                case 'init':
                    console.log('Received initial graph data:', message.payload);
                     logMessage(\`Received graph: \${message.payload.nodes.length} nodes, \${message.payload.links.length} links.\`);
                    graph.graphData(message.payload); // Load initial data
                     // Force simulation restart if needed after loading new data
                     graph.d3ReheatSimulation();
                    break;
                case 'thought_update':
                     const updatedNode = message.payload;
                     console.log('Thought update:', updatedNode.id);
                     logMessage(\`Thought updated: \${updatedNode.id.substring(0,8)}...\`);
                     const { nodes, links } = graph.graphData();
                     const existingNodeIndex = nodes.findIndex(n => n.id === updatedNode.id);

                     if (existingNodeIndex > -1) {
                         // Update existing node data (merge for safety)
                         Object.assign(nodes[existingNodeIndex], updatedNode);
                     } else {
                         // Add new node
                         nodes.push(updatedNode);
                     }
                      // Update links related to this node (if structure changed)
                      // For simplicity, just reload the whole graph data from current state
                      graph.graphData({ nodes, links }); // This triggers re-render and updates

                     // Update HTML label if it exists
                     const element = htmlElements.get(updatedNode.id);
                     if (element) {
                         updateNodeElementContent(element, updatedNode);
                     } else {
                         // Node might be new, graph update will create the element via nodeThreeObject
                     }
                     break;
                 case 'thought_delete':
                     const deletedNodeId = message.payload.id;
                     console.log('Thought delete:', deletedNodeId);
                      logMessage(\`Thought deleted: \${deletedNodeId.substring(0,8)}...\`);
                     let { nodes: currentNodes, links: currentLinks } = graph.graphData();
                     // Filter out the deleted node and any links connected to it
                     currentNodes = currentNodes.filter(n => n.id !== deletedNodeId);
                     currentLinks = currentLinks.filter(l => l.source.id !== deletedNodeId && l.target.id !== deletedNodeId);
                     graph.graphData({ nodes: currentNodes, links: currentLinks }); // Update graph

                     // Clean up maps
                     nodeObjects.delete(deletedNodeId);
                     htmlElements.delete(deletedNodeId);
                     break;
                case 'event_log':
                     // Maybe display important events in the log
                     logMessage(\`Event: \${message.payload.type} (\${message.payload.targetId.substring(0,8)}...)\`);
                     break;
                 case 'status_update':
                     if(message.payload.message) {
                         updateStatus(message.payload.message);
                         logMessage(\`Status: \${message.payload.message}\`);
                     }
                     if (message.payload.paused !== undefined) {
                         isPaused = message.payload.paused;
                         pauseStatus.textContent = isPaused ? 'Yes' : 'No';
                         if (isPaused) {
                             graph.pauseAnimation(); // Pause force simulation
                         } else {
                             graph.resumeAnimation(); // Resume force simulation
                         }
                     }
                     break;
                 case 'error':
                     logMessage(\`Server Error: \${message.payload.message}\`, 'error');
                     if(message.payload.details) console.error("Server error details:", message.payload.details);
                     break;
                 case 'log': // Generic log message from server
                      logMessage(\`Server Log: \${message.payload.message}\`);
                      break;

                default:
                    console.warn(\`Unhandled server message type: \${message.type}\`);
            }
        }

        // --- HUD & Logging ---
        function updateStatus(text) {
            systemStatus.textContent = text;
        }

        function logMessage(text, type = 'info') {
            const logEntry = document.createElement('div');
            logEntry.textContent = \`[\${new Date().toLocaleTimeString()}] \${text}\`;
            if (type === 'error') {
                logEntry.classList.add('error');
            }
            // Prepend to keep latest logs at the top
            logOutput.insertBefore(logEntry, logOutput.firstChild);
            // Limit log length
            while (logOutput.childElementCount > 50) {
                logOutput.removeChild(logOutput.lastChild);
            }
        }

        function sendControlCommand(command) {
            logMessage(\`Sending command: \${command}\`);
            sendMessage({ type: 'control', payload: { command } });
        }


        // --- Node Actions (Called from HTML buttons) ---
        window.editNode = (id) => {
            const editDiv = document.getElementById(\`edit-\${id}\`);
            if (editDiv) editDiv.style.display = 'block';
        };

        window.deleteNode = (id) => {
             if (confirm(\`Are you sure you want to delete thought \${id.substring(0,8)}...?\`)) {
                 logMessage(\`Requesting deletion of \${id.substring(0,8)}...\`);
                 sendMessage({ type: 'delete_thought', payload: { id } });
             }
        };

        window.saveNodeEdit = (id) => {
             const editDiv = document.getElementById(\`edit-\${id}\`);
             if (!editDiv) return;
             const content = editDiv.querySelector('textarea').value;
             const priority = parseFloat(editDiv.querySelector('input[type="number"]').value);
             logMessage(\`Saving edits for \${id.substring(0,8)}...\`);
             sendMessage({ type: 'update_thought', payload: { id, content, priority } });
             editDiv.style.display = 'none'; // Hide edit form
        };

         window.runMemoryTool = (id) => {
             const node = graph.graphData().nodes.find(n => n.id === id);
             if (!node || typeof node.content !== 'string') {
                 logMessage("Cannot run memory tool: Node not found or content not string.", "error");
                 return;
             }
             const query = node.content.substring(0, 100); // Use first 100 chars as query
             logMessage(\`Running memory tool for '\${query}'...\`);
              sendMessage({ type: 'run_tool', payload: { toolName: 'MemoryTool', params: { query: query, k: 5 }, thoughtId: id } });
              // Results will likely come back via status updates or events
         };

        function addNewThought() {
             const content = prompt("Enter content for new thought:", "New Note");
             if (content === null) return; // User cancelled
             const type = prompt("Enter type (note, task, goal, etc.):", "note");
              if (type === null) return;
             const priority = parseFloat(prompt("Enter priority (0.0 - 1.0):", "0.5"));

             logMessage("Adding new thought...");
             sendMessage({
                 type: 'add_thought',
                 payload: {
                     content,
                     type: type || 'note',
                     priority: isNaN(priority) ? 0.5 : Math.max(0, Math.min(1, priority)),
                     links: [],
                     metadata: {}
                 }
             });
        }


        // --- Animation Loop ---
        function animate() {
            animationId = requestAnimationFrame(animate);

            // Update TWEEN for smooth transitions
            TWEEN.update();

            // Update controls
            controls.update();

            // Update force graph simulation/positions
             if (!isPaused) {
                 graph.tickFrame(); // Advances simulation and updates object positions
             }

            // Render the scene
            render();
        }

        function render() {
             // Update CSS3D objects to face camera (optional, can be distracting)
             // htmlElements.forEach(obj => obj.quaternion.copy(camera.quaternion));

             // Render WebGL scene
             renderer.render(scene, camera);
             // Render CSS3D scene (HTML elements)
             cssRenderer.render(scene, camera);
        }


        // --- Start ---
        init();

    </script>
</body>
</html>
`;
// --- Server Setup ---
async function main() {
    console.log("--- Initializing FlowMind Enhanced Server ---");
    // Ensure API key is available (or features will be disabled)
    if (!process.env.OPENAI_API_KEY) {
        console.warn("**********************************************************************");
        console.warn("WARNING: OPENAI_API_KEY environment variable not set.");
        console.warn("AI-powered features (Insights, Embeddings, Semantic Search) disabled.");
        console.warn("Set your OpenAI API key to enable these features.");
        console.warn("**********************************************************************");
    }
    const system = new FlowMindSystem();
    // Create HTTP Server
    const server = http.createServer((req, res) => {
        if (req.url === '/') {
            res.writeHead(200, { 'Content-Type': 'text/html' });
            res.end(HTML_CONTENT);
        }
        else {
            res.writeHead(404, { 'Content-Type': 'text/plain' });
            res.end('Not Found');
        }
    });
    // Initialize the system (including WebSocket server using the HTTP server instance)
    await system.initialize(server);
    server.listen(SERVER_PORT, () => {
        console.log(`\nFlowMind Enhanced server running at http://localhost:${SERVER_PORT}`);
        console.log(`Graph UI available at http://localhost:${SERVER_PORT}`);
        console.log("System started in PAUSED state.");
    });
    // Graceful shutdown
    process.on('SIGINT', async () => {
        console.log('\nCaught interrupt signal, shutting down...');
        await system.shutdown();
        server.close(() => {
            console.log('HTTP server closed.');
            process.exit(0);
        });
        // Force exit if server doesn't close promptly
        setTimeout(() => process.exit(1), 5000);
    });
    process.on('SIGTERM', async () => {
        console.log('\nCaught termination signal, shutting down...');
        await system.shutdown();
        server.close(() => {
            console.log('HTTP server closed.');
            process.exit(0);
        });
        setTimeout(() => process.exit(1), 5000);
    });
}
// Execute if run directly
// if (require.main === module) {
main().catch(error => {
    console.error("\n--- Server Initialization Failed ---");
    console.error(error);
    process.exit(1);
});
