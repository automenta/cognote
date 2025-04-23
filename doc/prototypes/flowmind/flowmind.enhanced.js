"use strict";
// --- FlowMind Enhanced ---
// A single-file implementation of a dynamic note-taking and reasoning system.
// Vision: Transform notes into actionable plans and insights using a hybrid reasoning approach.
// Core Principles: Living Thoughts, Clear Reasoning, Unified Experience, In-Process Simplicity,
// Conversational Learning, Extensible Tools, Collaborative Flow.
// Architecture: Leverages LevelDB for persistence and LangChain.js for AI insights.
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
const level_1 = require("level");
const uuid_1 = require("uuid");
const openai_1 = require("@langchain/openai");
const messages_1 = require("@langchain/core/messages");
const output_parsers_1 = require("@langchain/core/output_parsers");
const faiss_1 = require("@langchain/community/vectorstores/faiss"); // Example vector store
const documents_1 = require("@langchain/core/documents");
const path = __importStar(require("path.js"));
const os = __importStar(require("os.js"));
const fs = __importStar(require("fs/promises")); // Use promises for async file operations
// --- Configuration ---
// Ensure OPENAI_API_KEY is set in your environment variables
const DB_PATH = path.join(os.homedir(), '.flowmind-enhanced-db');
const VECTOR_STORE_PATH = path.join(os.homedir(), '.flowmind-vector-store');
const INSIGHT_MODEL_NAME = "gpt-4o-mini"; // Or another suitable model
const EMBEDDING_MODEL_NAME = "text-embedding-3-small";
// --- Utility Functions ---
const safeJsonParse = (json, defaultValue) => {
    try {
        return json ? JSON.parse(json) : defaultValue;
    }
    catch (error) {
        console.warn("JSON parse error:", error);
        return defaultValue;
    }
};
const generateId = () => (0, uuid_1.v4)();
// --- Core Components ---
/**
 * ThoughtStore: Manages persistence of Thoughts, Guides, and Events using LevelDB.
 */
/**
 * ThoughtStore: Manages persistence of Thoughts, Guides, and Events using LevelDB.
 * Also manages the vector store for semantic search.
 */
class ThoughtStore {
    db;
    vectorStore; // In-memory vector store, persisted separately
    embeddings; // Embedding model instance
    vectorStorePath;
    constructor(dbPath, vectorStorePath) {
        this.db = new level_1.Level(dbPath, { valueEncoding: 'json' });
        this.vectorStorePath = vectorStorePath;
        // Initialize vector store loading in constructor, but async
        this.initializeVectorStore(vectorStorePath).catch(err => {
            console.error("Failed to initialize vector store:", err);
        });
    }
    async initializeVectorStore(vectorStorePath) {
        // Only initialize embeddings/vector store if an API key is available
        if (process.env.OPENAI_API_KEY) {
            this.embeddings = new openai_1.OpenAIEmbeddings({ modelName: EMBEDDING_MODEL_NAME, openAIApiKey: process.env.OPENAI_API_KEY });
            try {
                // Try loading existing store
                await fs.access(vectorStorePath); // Check if the directory exists
                // FaissStore.load requires the *directory* path
                this.vectorStore = await faiss_1.FaissStore.load(vectorStorePath, this.embeddings);
                console.log(`Vector store loaded from ${vectorStorePath}`);
            }
            catch (error) {
                console.log(`Vector store not found at ${vectorStorePath}, creating new one.`);
                if (!this.embeddings) { // Guard against embeddings not being initialized
                    console.error("Embeddings model not available, cannot create vector store.");
                    return;
                }
                // Need dummy data to initialize if empty
                const dummyTexts = ["FlowMind init"];
                // FaissStore requires metadata to be Record<string, any>, Document fulfills this
                const dummyDocs = dummyTexts.map((text, i) => new documents_1.Document({
                    pageContent: text,
                    metadata: { id: `init_${i}`, type: 'system_init' }
                }));
                try {
                    // Use fromDocuments to initialize the store
                    this.vectorStore = await faiss_1.FaissStore.fromDocuments(dummyDocs, this.embeddings);
                    // Immediately save the new empty store
                    await this.saveVectorStore();
                }
                catch (initError) {
                    console.error("Error initializing new FaissStore:", initError);
                    // Potentially delete the partially created directory if save failed
                    try {
                        await fs.rm(this.vectorStorePath, { recursive: true, force: true });
                    }
                    catch (rmErr) { }
                }
            }
        }
        else {
            console.warn("OPENAI_API_KEY not set. Semantic search and embedding features disabled.");
        }
    }
    async saveVectorStore() {
        if (this.vectorStore) {
            // FaissStore.save requires the *directory* path
            await this.vectorStore.save(this.vectorStorePath);
            console.log(`Vector store saved to ${this.vectorStorePath}`);
        }
    }
    // --- Thought Operations ---
    async putThought(thought) {
        thought.metadata.updatedAt = new Date().toISOString();
        await this.db.put(`thought:${thought.id}`, JSON.stringify(thought));
        await this.logEvent('thought_updated', thought.id, { changes: Object.keys(thought).filter(k => k !== 'metadata' && k !== 'embedding') }); // Log meaningful changes
        // Update vector store if content exists and embeddings are enabled
        if (this.vectorStore && this.embeddings && typeof thought.content === 'string' && thought.content.trim()) {
            const doc = new documents_1.Document({
                pageContent: thought.content,
                // Include relevant metadata for filtering/context in search results
                metadata: { id: thought.id, type: thought.type, priority: thought.priority, createdAt: thought.metadata.createdAt }
            });
            try {
                // FAISS doesn't support updates or deletes by ID easily.
                // Strategy: Add new version. Search might return multiple versions. Client logic or post-processing needed to select latest.
                // A more robust solution involves rebuilding the index periodically or using a vector store with better update/delete support.
                await this.vectorStore.addDocuments([doc]);
                // Consider saving periodically or on shutdown instead of every put for performance
                // await this.saveVectorStore();
            }
            catch (error) {
                console.error(`Error updating vector store for thought ${thought.id}:`, error);
            }
        }
    }
    async getThought(id) {
        try {
            const value = await this.db.get(`thought:${id}`);
            return safeJsonParse(value, null);
        }
        catch (error) {
            // Use error.code for LevelDB specific errors
            if (error.code === 'LEVEL_NOT_FOUND')
                return null;
            console.error(`Error getting thought ${id}:`, error);
            throw error; // Re-throw other errors
        }
    }
    async deleteThought(id) {
        // Note: Removing from FAISS by ID is non-trivial and not implemented here.
        // The vector for the deleted thought will remain in the store until rebuilt.
        console.warn(`Deleting thought ${id} from LevelDB. Corresponding vector in FaissStore remains.`);
        await this.db.del(`thought:${id}`);
        await this.logEvent('thought_deleted', id, {});
    }
    async listThoughts(options = {}) {
        const thoughts = [];
        const iteratorOptions = {
            limit: options.limit,
            gt: 'thought:', // Lexicographical start key for thoughts
            lt: 'thought;~' // Lexicographical end key for thoughts (adjust based on actual key format if needed)
        };
        for await (const [key, value] of this.db.iterator(iteratorOptions)) {
            const thought = safeJsonParse(value, null);
            if (thought) {
                const typeMatch = !options.type || thought.type === options.type;
                const tagMatch = !options.tag || (thought.metadata.tags && thought.metadata.tags.includes(options.tag));
                if (typeMatch && tagMatch) {
                    thoughts.push(thought);
                }
            }
            // Check limit inside the loop for efficiency
            if (options.limit && thoughts.length >= options.limit)
                break;
        }
        return thoughts;
    }
    // Finds thoughts that have a link *pointing to* the targetId
    async findThoughtsLinkingTo(targetId, relationship) {
        const linkedThoughts = [];
        for await (const [key, value] of this.db.iterator({ gt: 'thought:', lt: 'thought;~' })) {
            const thought = safeJsonParse(value, null);
            if (thought && thought.links.some(link => link.targetId === targetId && (!relationship || link.relationship === relationship))) {
                linkedThoughts.push(thought);
            }
        }
        return linkedThoughts;
    }
    // Finds thoughts that are targets of links *from* the sourceId
    async findLinkedThoughtsFrom(sourceId, relationship) {
        const sourceThought = await this.getThought(sourceId);
        if (!sourceThought)
            return [];
        const targetIds = sourceThought.links
            .filter(link => !relationship || link.relationship === relationship)
            .map(link => link.targetId);
        const linkedThoughts = [];
        for (const targetId of targetIds) {
            const targetThought = await this.getThought(targetId);
            if (targetThought) {
                linkedThoughts.push(targetThought);
            }
        }
        return linkedThoughts;
    }
    async semanticSearchThoughts(query, k = 5, filter) {
        if (!this.vectorStore) {
            console.warn("Vector store not available for semantic search.");
            return [];
        }
        try {
            // FaissStore similaritySearchWithScore doesn't directly support metadata filtering in the same way as some other stores.
            // We retrieve more results (e.g., k * 2) and filter afterwards, or implement filtering if the underlying library supports it.
            // For simplicity, we'll retrieve k results and note the limitation.
            if (filter) {
                console.warn("FaissStore basic implementation doesn't support metadata filtering during search. Filter ignored.");
            }
            const results = await this.vectorStore.similaritySearchWithScore(query, k);
            const thoughtsWithScores = [];
            for (const [doc, score] of results) {
                // doc.metadata.id should contain the thought ID if stored correctly during putThought
                const thoughtId = doc.metadata?.id;
                if (typeof thoughtId === 'string') {
                    const thought = await this.getThought(thoughtId);
                    if (thought) {
                        // Score from Faiss is distance (lower is better). Convert to similarity (0-1, higher is better) if needed.
                        // This depends on the distance metric used. Assuming L2 distance for now.
                        // Simple inversion (not mathematically rigorous similarity):
                        const similarity = 1 / (1 + score);
                        thoughtsWithScores.push({ thought, score: similarity });
                    }
                    else {
                        console.warn(`Semantic search found vector for deleted/missing thought ID: ${thoughtId}`);
                    }
                }
                else {
                    console.warn("Semantic search result document missing valid 'id' in metadata:", doc.metadata);
                }
            }
            // Sort by score descending (higher similarity is better)
            return thoughtsWithScores.sort((a, b) => b.score - a.score);
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
            throw error;
        }
    }
    async deleteGuide(id) {
        await this.db.del(`guide:${id}`);
        await this.logEvent('guide_deleted', id, {});
    }
    async listGuides() {
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
        // Store chronologically using timestamp + UUID for uniqueness
        await this.db.put(`event:${event.timestamp}:${event.id}`, JSON.stringify(event));
        return event;
    }
    async listEvents(options = {}) {
        const events = [];
        const iteratorOptions = {
            limit: options.limit,
            reverse: true, // Get latest events first
            lt: 'event;~' // Upper bound
        };
        // Add lower bound if 'since' is provided
        if (options.since) {
            iteratorOptions.gt = `event:${options.since}`;
        }
        else {
            iteratorOptions.gt = 'event:'; // Default lower bound
        }
        for await (const [key, value] of this.db.iterator(iteratorOptions)) {
            const event = safeJsonParse(value, null);
            if (event && (!options.targetId || event.targetId === options.targetId)) {
                events.push(event);
            }
            // Check limit inside the loop
            if (options.limit && events.length >= options.limit)
                break;
        }
        // Results are already reversed due to iterator options
        return events;
    }
    // --- DB Management ---
    async open() {
        // db.status might not be reliable across all versions/platforms, use db.supports?.status check or try-catch
        try {
            if (this.db.status === 'closed') {
                await this.db.open();
                console.log(`ThoughtStore database opened at ${this.db.location}`);
            }
        }
        catch (err) { // Handle cases where status is not available or open fails
            console.warn("Could not determine DB status or DB already open/opening.");
            // Attempt to open anyway if needed, LevelDB might handle this internally
            try {
                await this.db.open();
            }
            catch (openErr) {
                console.error("Failed to open database:", openErr);
                throw openErr; // Rethrow critical error
            }
        }
    }
    async close() {
        try {
            if (this.db.status === 'open') {
                await this.db.close();
                console.log(`ThoughtStore database closed.`);
                // Persist vector store on close
                await this.saveVectorStore();
            }
        }
        catch (err) {
            console.warn("Could not determine DB status or DB already closed/closing.");
            // Attempt to close anyway
            try {
                await this.db.close();
                await this.saveVectorStore();
            }
            catch (closeErr) { /* Ignore */ }
        }
    }
}
/**
 * InsightModel: Interface for interacting with LangChain.js LLMs for suggestions and embeddings.
 */
class InsightModel {
    llm = null;
    embeddings = null;
    constructor() {
        if (process.env.OPENAI_API_KEY) {
            this.llm = new openai_1.ChatOpenAI({
                modelName: INSIGHT_MODEL_NAME,
                temperature: 0.7, // Balance creativity and predictability
                openAIApiKey: process.env.OPENAI_API_KEY
            });
            this.embeddings = new openai_1.OpenAIEmbeddings({
                modelName: EMBEDDING_MODEL_NAME,
                openAIApiKey: process.env.OPENAI_API_KEY
            });
        }
        else {
            console.warn("OPENAI_API_KEY not set. InsightModel features are disabled.");
        }
    }
    // Generates suggestions based on a thought's content and context.
    async getSuggestions(thought, contextThoughts = []) {
        if (!this.llm)
            return Promise.resolve(["InsightModel disabled (no API key)."]);
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
            // Basic parsing of suggestions, assuming newline separation
            return result.split('\n').map(s => s.trim().replace(/^- /, '')).filter(s => s.length > 0);
        }
        catch (error) {
            console.error("Error getting suggestions from InsightModel:", error);
            return ["Error generating suggestions."];
        }
    }
    // Generates an embedding vector for a given text.
    async generateEmbedding(text) {
        if (!this.embeddings)
            return Promise.resolve(null);
        try {
            const result = await this.embeddings.embedQuery(text);
            return result;
        }
        catch (error) {
            console.error("Error generating embedding:", error);
            return null;
        }
    }
}
/**
 * ToolHub: Manages and executes available tools.
 */
class ToolHub {
    tools = new Map();
    registerTool(tool) {
        if (this.tools.has(tool.name)) {
            console.warn(`Tool "${tool.name}" is already registered. Overwriting.`);
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
    // Executes a tool by name with given parameters and context.
    async executeTool(name, params, context) {
        const tool = this.getTool(name);
        if (!tool) {
            throw new Error(`Tool "${name}" not found.`);
        }
        const targetId = params.thoughtId || 'system'; // Assume params might contain target thought ID
        await context.thoughtStore.logEvent('tool_invoked', targetId, { toolName: name, params });
        try {
            const result = await tool.execute(params, context);
            await context.thoughtStore.logEvent('tool_success', targetId, { toolName: name, result: result ? JSON.stringify(result).substring(0, 100) + '...' : null }); // Log snippet of result
            return result;
        }
        catch (error) {
            console.error(`Error executing tool "${name}":`, error);
            await context.thoughtStore.logEvent('tool_failure', targetId, { toolName: name, error: error.message });
            throw error; // Re-throw error after logging
        }
    }
}
/**
 * TaskQueue: Handles asynchronous processing of tasks, primarily tool executions.
 * Persists state implicitly by updating Thought metadata.
 */
class TaskQueue {
    thoughtStore;
    toolHub;
    insightModel;
    activeTasks = new Set(); // Track IDs of thoughts currently being processed
    constructor(thoughtStore, toolHub, insightModel) {
        this.thoughtStore = thoughtStore;
        this.toolHub = toolHub;
        this.insightModel = insightModel;
    }
    // Enqueues a task by associating it with a Thought (e.g., via metadata).
    async enqueueTask(thoughtId, toolName, params) {
        const thought = await this.thoughtStore.getThought(thoughtId);
        if (!thought) {
            console.error(`TaskQueue: Thought ${thoughtId} not found for task.`);
            return;
        }
        // Mark the thought as having a pending task
        thought.metadata.pendingTask = { toolName, params };
        thought.metadata.status = 'pending';
        await this.thoughtStore.putThought(thought);
        // Trigger processing immediately (could be debounced or batched)
        this.processTask(thoughtId);
    }
    // Processes a task associated with a specific thought.
    async processTask(thoughtId) {
        // Prevent concurrent processing of the same thought's task
        if (this.activeTasks.has(thoughtId)) {
            console.log(`Task for thought ${thoughtId} is already processing.`);
            return;
        }
        this.activeTasks.add(thoughtId);
        const thought = await this.thoughtStore.getThought(thoughtId);
        if (!thought || !thought.metadata.pendingTask) {
            this.activeTasks.delete(thoughtId);
            return; // Thought deleted or task already processed/cleared
        }
        const { toolName, params } = thought.metadata.pendingTask;
        thought.metadata.status = 'processing';
        delete thought.metadata.pendingTask; // Remove pending task marker
        await this.thoughtStore.putThought(thought); // Update status
        console.log(`Processing task "${toolName}" for thought ${thoughtId}...`);
        try {
            const context = {
                thoughtStore: this.thoughtStore,
                taskQueue: this,
                insightModel: this.insightModel
            };
            await this.toolHub.executeTool(toolName, { ...params, thoughtId }, context); // Pass thoughtId in params
            thought.metadata.status = 'completed';
        }
        catch (error) {
            console.error(`Task "${toolName}" for thought ${thoughtId} failed:`, error);
            thought.metadata.status = 'failed';
            thought.metadata.errorInfo = error.message;
        }
        finally {
            // Fetch the latest version in case the tool modified it
            const finalThought = await this.thoughtStore.getThought(thoughtId);
            if (finalThought) {
                finalThought.metadata.status = thought.metadata.status; // Apply final status
                if (thought.metadata.errorInfo)
                    finalThought.metadata.errorInfo = thought.metadata.errorInfo;
                await this.thoughtStore.putThought(finalThought);
            }
            this.activeTasks.delete(thoughtId); // Allow reprocessing if needed later
            console.log(`Finished processing task "${toolName}" for thought ${thoughtId}. Status: ${thought.metadata.status}`);
        }
    }
    // Method to periodically scan for and process any pending tasks (e.g., on startup or timer)
    async processPendingTasks() {
        console.log("Scanning for pending tasks...");
        const thoughts = await this.thoughtStore.listThoughts();
        let processedCount = 0;
        for (const thought of thoughts) {
            if (thought.metadata.pendingTask && !this.activeTasks.has(thought.id)) {
                console.log(`Found pending task for thought ${thought.id}. Enqueuing processing.`);
                this.processTask(thought.id); // Process asynchronously
                processedCount++;
            }
        }
        console.log(`Finished scanning. Found and initiated ${processedCount} pending tasks.`);
    }
}
/**
 * Reasoner: The core agentic component driving Thought evolution.
 */
class Reasoner {
    thoughtStore;
    taskQueue;
    toolHub;
    insightModel;
    guides = []; // In-memory cache of guides
    processing = false; // Flag to prevent concurrent cycles
    constructor(thoughtStore, taskQueue, toolHub, insightModel) {
        this.thoughtStore = thoughtStore;
        this.taskQueue = taskQueue;
        this.toolHub = toolHub;
        this.insightModel = insightModel;
    }
    async loadGuides() {
        this.guides = await this.thoughtStore.listGuides();
        console.log(`Loaded ${this.guides.length} guides into Reasoner.`);
    }
    // Creates a new Thought with default values.
    async createThought(content, type = 'note', priority = 0.5, links = [], metadata = {}) {
        const now = new Date().toISOString();
        const thought = {
            id: generateId(),
            content,
            priority,
            type,
            links,
            metadata: {
                createdAt: now,
                updatedAt: now,
                tags: [],
                ...metadata,
            },
        };
        await this.thoughtStore.putThought(thought);
        await this.thoughtStore.logEvent('thought_created', thought.id, { type, priority });
        // Optionally trigger embedding generation asynchronously
        if (typeof content === 'string' && content.trim()) {
            this.taskQueue.enqueueTask(thought.id, 'GenerateEmbeddingTool', { text: content });
        }
        console.log(`Created thought ${thought.id} of type ${type}`);
        return thought;
    }
    // Updates specific fields of an existing thought.
    async updateThought(id, updates) {
        const thought = await this.thoughtStore.getThought(id);
        if (!thought)
            return null;
        // Merge updates
        const updatedThought = { ...thought };
        Object.assign(updatedThought, updates);
        if (updates.metadata) {
            updatedThought.metadata = { ...thought.metadata, ...updates.metadata };
        }
        updatedThought.metadata.updatedAt = new Date().toISOString(); // Ensure updatedAt is always current
        await this.thoughtStore.putThought(updatedThought);
        return updatedThought;
    }
    // The main processing loop for evolving thoughts.
    async processCycle(limit = 10) {
        if (this.processing) {
            console.log("Reasoner cycle already in progress. Skipping.");
            return;
        }
        this.processing = true;
        console.log("Starting Reasoner cycle...");
        try {
            await this.loadGuides(); // Refresh guides each cycle
            // 1. Select Thoughts to process (e.g., highest priority, recently updated)
            // Simple strategy: Get thoughts sorted by priority (desc)
            const allThoughts = await this.thoughtStore.listThoughts();
            const thoughtsToProcess = allThoughts
                .filter(t => t.metadata.status !== 'processing') // Avoid processing thoughts with active tasks
                .sort((a, b) => b.priority - a.priority) // Higher priority first
                .slice(0, limit);
            if (thoughtsToProcess.length === 0) {
                console.log("No thoughts require processing in this cycle.");
                this.processing = false;
                return;
            }
            console.log(`Processing ${thoughtsToProcess.length} thoughts...`);
            // 2. Process each selected Thought
            for (const thought of thoughtsToProcess) {
                let modified = false;
                // a) Apply Guides
                for (const guide of this.guides) {
                    if (this.evaluateGuideCondition(guide.condition, thought)) {
                        console.log(`Guide "${guide.id}" matched thought ${thought.id}. Applying action: ${guide.action}`);
                        const changed = await this.applyGuideAction(guide.action, thought);
                        if (changed)
                            modified = true;
                    }
                }
                // b) Get Insight Model suggestions (if applicable, e.g., for notes or goals)
                if (['note', 'goal', 'question'].includes(thought.type) && Math.random() < 0.2) { // Add stochastic element to avoid constant LLM calls
                    console.log(`Requesting InsightModel suggestions for thought ${thought.id}`);
                    // Fetch minimal context: parent/child thoughts
                    const linkedThoughtIds = thought.links.map(l => l.targetId);
                    const contextThoughts = (await Promise.all(linkedThoughtIds.map(id => this.thoughtStore.getThought(id))))
                        .filter(t => t !== null);
                    const suggestions = await this.insightModel.getSuggestions(thought, contextThoughts);
                    if (suggestions.length > 0 && suggestions[0] !== "InsightModel disabled (no API key)." && suggestions[0] !== "Error generating suggestions.") {
                        // Store suggestions in metadata for potential user review or future automated action
                        thought.metadata.aiSuggestions = suggestions;
                        console.log(`Suggestions for ${thought.id}: ${suggestions.join(', ')}`);
                        modified = true;
                    }
                }
                // c) Persist changes if any occurred during Guide application or Insight generation
                if (modified) {
                    await this.updateThought(thought.id, thought); // Persist changes made directly to thought object
                }
            }
            console.log("Reasoner cycle finished.");
        }
        catch (error) {
            console.error("Error during Reasoner cycle:", error);
        }
        finally {
            this.processing = false;
        }
    }
    // Evaluates a simple guide condition against a thought.
    evaluateGuideCondition(condition, thought) {
        // Simple DSL parser: "field=value", "field_contains=value", "tag=value" combined with &
        const conditions = condition.split('&').map(c => c.trim());
        return conditions.every(cond => {
            if (cond.includes('=')) {
                const [key, value] = cond.split('=', 2);
                switch (key) {
                    case 'type': return thought.type === value;
                    case 'priority_gt': return thought.priority > parseFloat(value);
                    case 'priority_lt': return thought.priority < parseFloat(value);
                    case 'status': return thought.metadata.status === value;
                    case 'tag': return thought.metadata.tags?.includes(value) ?? false;
                    case 'content_contains': return typeof thought.content === 'string' && thought.content.includes(value);
                    default: return false; // Unknown condition key
                }
            }
            return false; // Malformed condition part
        });
    }
    // Applies a simple guide action to a thought or enqueues a task.
    async applyGuideAction(action, thought) {
        // Simple DSL parser: "set field=value", "add_tag=value", "remove_tag=value", "create_task=content", "link_to=id:relationship"
        if (action.startsWith('set ')) {
            const [field, value] = action.substring(4).split('=', 2);
            switch (field) {
                case 'priority':
                    thought.priority = Math.max(0, Math.min(1, parseFloat(value)));
                    return true;
                case 'type':
                    thought.type = value;
                    return true;
                case 'status':
                    thought.metadata.status = value;
                    return true;
                default:
                    console.warn(`Unsupported set action field: ${field}`);
                    return false;
            }
        }
        else if (action.startsWith('add_tag=')) {
            const tag = action.substring(8);
            if (!thought.metadata.tags)
                thought.metadata.tags = [];
            if (!thought.metadata.tags.includes(tag)) {
                thought.metadata.tags.push(tag);
                return true;
            }
            return false;
        }
        else if (action.startsWith('remove_tag=')) {
            const tag = action.substring(11);
            if (thought.metadata.tags) {
                const index = thought.metadata.tags.indexOf(tag);
                if (index > -1) {
                    thought.metadata.tags.splice(index, 1);
                    return true;
                }
            }
            return false;
        }
        else if (action.startsWith('create_task=')) {
            const taskContent = action.substring(12);
            // Enqueue task creation via a dedicated tool or directly create thought
            await this.taskQueue.enqueueTask(thought.id, 'CreateChildTaskTool', { parentId: thought.id, content: taskContent });
            return false; // Action enqueued, thought itself not directly modified by *this* step
        }
        else if (action.startsWith('link_to=')) {
            const [targetId, relationship] = action.substring(8).split(':', 2);
            if (!thought.links.some(l => l.targetId === targetId && l.relationship === relationship)) {
                thought.links.push({ targetId, relationship: relationship || 'related' });
                return true;
            }
            return false;
        }
        else {
            console.warn(`Unsupported action: ${action}`);
            return false;
        }
    }
    // --- Public API for UI/External Interaction ---
    async addThought(content, type, priority, links, metadata) {
        return this.createThought(content, type, priority, links, metadata);
    }
    async getThought(id) {
        return this.thoughtStore.getThought(id);
    }
    async findThoughts(query) {
        // Basic keyword search + semantic search
        const allThoughts = await this.thoughtStore.listThoughts();
        const keywordMatches = allThoughts
            .filter(t => typeof t.content === 'string' && t.content.toLowerCase().includes(query.toLowerCase()))
            .map(t => ({ thought: t, score: 0.7 })); // Assign arbitrary score for keyword match
        const semanticMatches = await this.thoughtStore.semanticSearchThoughts(query);
        // Combine and deduplicate results (simple approach: prioritize semantic matches)
        const combined = new Map();
        semanticMatches.forEach(m => combined.set(m.thought.id, m));
        keywordMatches.forEach(m => {
            if (!combined.has(m.thought.id)) {
                combined.set(m.thought.id, m);
            }
        });
        return Array.from(combined.values()).sort((a, b) => b.score - a.score);
    }
    async provideFeedback(thoughtId, feedback) {
        // Placeholder for learning mechanism. Could adjust guide weights or refine insight model prompts.
        console.log(`Feedback received for thought ${thoughtId}:`, feedback);
        const thought = await this.getThought(thoughtId);
        if (!thought)
            return;
        thought.metadata.feedback = thought.metadata.feedback || [];
        thought.metadata.feedback.push({ ...feedback, timestamp: new Date().toISOString() });
        await this.updateThought(thoughtId, { metadata: thought.metadata });
        // Future: Use feedback to adjust Guide weights or fine-tune models.
    }
}
// --- Concrete Tool Implementations ---
class GenerateEmbeddingTool {
    name = "GenerateEmbeddingTool";
    description = "Generates and stores an embedding for a thought's content.";
    async execute(params, context) {
        const { thoughtId, text } = params;
        const thought = await context.thoughtStore.getThought(thoughtId);
        if (!thought)
            throw new Error(`Thought ${thoughtId} not found.`);
        const contentToEmbed = text ?? (typeof thought.content === 'string' ? thought.content : JSON.stringify(thought.content));
        if (!contentToEmbed || !contentToEmbed.trim()) {
            console.log(`Skipping embedding for ${thoughtId}: empty content.`);
            return;
        }
        const embedding = await context.insightModel.generateEmbedding(contentToEmbed);
        if (embedding) {
            thought.embedding = embedding; // Store embedding *directly* on thought for now
            thought.metadata.embeddingGeneratedAt = new Date().toISOString();
            await context.thoughtStore.putThought(thought); // This also updates the vector store via putThought hook
            console.log(`Embedding generated and stored for thought ${thoughtId}.`);
        }
        else {
            console.warn(`Failed to generate embedding for thought ${thoughtId}.`);
        }
    }
}
class CreateChildTaskTool {
    name = "CreateChildTaskTool";
    description = "Creates a new task thought linked as a child to a parent thought.";
    async execute(params, context) {
        const { parentId, content, priority = 0.6 } = params; // Default priority for tasks
        const parentThought = await context.thoughtStore.getThought(parentId);
        if (!parentThought)
            throw new Error(`Parent thought ${parentId} not found.`);
        const now = new Date().toISOString();
        const taskThought = {
            id: generateId(),
            content: content,
            priority: priority,
            type: 'task',
            links: [{ relationship: 'parent', targetId: parentId }],
            metadata: {
                createdAt: now,
                updatedAt: now,
                status: 'pending', // Initial status for a new task
                tags: ['task'],
            },
        };
        await context.thoughtStore.putThought(taskThought);
        await context.thoughtStore.logEvent('task_created', taskThought.id, { parentId });
        // Also add link back from parent to child if desired (optional)
        parentThought.links.push({ relationship: 'child', targetId: taskThought.id });
        await context.thoughtStore.putThought(parentThought);
        console.log(`Created child task ${taskThought.id} for parent ${parentId}`);
        return { taskId: taskThought.id };
    }
}
class MemoryTool {
    name = "MemoryTool";
    description = "Performs semantic search across thoughts using embeddings.";
    async execute(params, context) {
        const { query, k = 5 } = params;
        if (!query)
            throw new Error("Query parameter is required for MemoryTool.");
        const results = await context.thoughtStore.semanticSearchThoughts(query, k);
        console.log(`MemoryTool found ${results.length} results for query "${query}"`);
        return { results };
    }
}
class ShareTool {
    name = "ShareTool";
    description = "Exports thoughts to JSON format for sharing.";
    async execute(params, context) {
        const { thoughtIds } = params;
        if (!thoughtIds || thoughtIds.length === 0)
            throw new Error("At least one thoughtId is required for export.");
        const thoughtsToExport = [];
        for (const id of thoughtIds) {
            const thought = await context.thoughtStore.getThought(id);
            if (thought) {
                thoughtsToExport.push(thought);
                // Optionally include linked thoughts recursively (complex, omitted for brevity)
            }
            else {
                console.warn(`ShareTool: Thought ${id} not found, skipping export.`);
            }
        }
        const exportedJson = JSON.stringify(thoughtsToExport, null, 2); // Pretty print JSON
        console.log(`ShareTool exported ${thoughtsToExport.length} thoughts.`);
        // In a real app, this might return a file handle or URL, here just the JSON string
        return { exportedJson };
    }
}
// --- Main System Class ---
class FlowMindSystem {
    thoughtStore;
    insightModel;
    toolHub;
    taskQueue;
    reasoner;
    processingInterval = null;
    constructor(dbPath = DB_PATH, vectorStorePath = VECTOR_STORE_PATH) {
        this.thoughtStore = new ThoughtStore(dbPath, vectorStorePath);
        this.insightModel = new InsightModel();
        this.toolHub = new ToolHub();
        this.taskQueue = new TaskQueue(this.thoughtStore, this.toolHub, this.insightModel);
        this.reasoner = new Reasoner(this.thoughtStore, this.taskQueue, this.toolHub, this.insightModel);
        this.registerDefaultTools();
    }
    registerDefaultTools() {
        this.toolHub.registerTool(new GenerateEmbeddingTool());
        this.toolHub.registerTool(new CreateChildTaskTool());
        this.toolHub.registerTool(new MemoryTool());
        this.toolHub.registerTool(new ShareTool());
        // Register other tools (ConnectTool, etc.) here
    }
    async initialize() {
        await this.thoughtStore.open();
        await this.reasoner.loadGuides(); // Load guides on startup
        await this.taskQueue.processPendingTasks(); // Process any tasks pending from previous runs
        console.log("FlowMind Enhanced system initialized.");
    }
    // Starts the background processing cycle for the Reasoner.
    startAutoProcessing(intervalMs = 15000) {
        if (this.processingInterval) {
            console.log("Auto-processing is already running.");
            return;
        }
        console.log(`Starting auto-processing cycle every ${intervalMs}ms.`);
        this.processingInterval = setInterval(() => {
            this.reasoner.processCycle().catch(err => {
                console.error("Error in scheduled Reasoner cycle:", err);
            });
        }, intervalMs);
    }
    stopAutoProcessing() {
        if (this.processingInterval) {
            clearInterval(this.processingInterval);
            this.processingInterval = null;
            console.log("Stopped auto-processing cycle.");
        }
    }
    async shutdown() {
        this.stopAutoProcessing();
        await this.thoughtStore.close(); // This also saves the vector store
        console.log("FlowMind Enhanced system shut down gracefully.");
    }
    // --- High-Level API for interaction (e.g., from ThoughtView or CLI) ---
    async addThought(content, type, priority, links, metadata) {
        return this.reasoner.addThought(content, type, priority, links, metadata);
    }
    async getThought(id) {
        return this.reasoner.getThought(id);
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
        return this.reasoner.findThoughts(query);
    }
    async addGuide(condition, action, weight = 0.5) {
        const now = new Date().toISOString();
        const guide = {
            id: generateId(),
            condition,
            action,
            weight,
            metadata: { createdAt: now, updatedAt: now },
        };
        await this.thoughtStore.putGuide(guide);
        await this.reasoner.loadGuides(); // Reload guides in reasoner
        return guide;
    }
    async listGuides() {
        return this.thoughtStore.listGuides();
    }
    async invokeTool(toolName, params, thoughtId) {
        // If thoughtId is provided, enqueue, otherwise execute directly (e.g., system tool)
        if (thoughtId) {
            await this.taskQueue.enqueueTask(thoughtId, toolName, params);
            return { message: `Task ${toolName} enqueued for thought ${thoughtId}` };
        }
        else {
            const context = {
                thoughtStore: this.thoughtStore,
                taskQueue: this.taskQueue,
                insightModel: this.insightModel
            };
            return this.toolHub.executeTool(toolName, params, context);
        }
    }
    async getEvents(options) {
        return this.thoughtStore.listEvents(options);
    }
}
exports.FlowMindSystem = FlowMindSystem;
// --- Example Usage ---
async function runExample() {
    console.log("--- FlowMind Enhanced Example ---");
    // Ensure API key is available (or features will be disabled)
    if (!process.env.OPENAI_API_KEY) {
        console.warn("**********************************************************************");
        console.warn("WARNING: OPENAI_API_KEY environment variable not set.");
        console.warn("AI-powered features (Insights, Embeddings, Semantic Search) disabled.");
        console.warn("Set your OpenAI API key to enable these features.");
        console.warn("**********************************************************************");
    }
    const system = new FlowMindSystem();
    await system.initialize();
    // Add a simple guide
    const guides = await system.listGuides();
    if (guides.length === 0) {
        console.log("Adding initial example guide...");
        await system.addGuide("type=note & content_contains=meeting", "add_tag=meeting_prep & set priority=0.8", 0.7);
        await system.addGuide("tag=urgent", "set priority=0.95");
    }
    // Add some thoughts
    let thought1 = await system.addThought("Organize team meeting for Project Phoenix", "note", 0.7);
    let thought2 = await system.addThought("Need to finalize the Q3 budget report #urgent", "task", 0.8);
    let thought3 = await system.addThought("What are the key risks for Project Phoenix?", "question");
    console.log("\n--- Initial Thoughts ---");
    console.log(JSON.stringify(await system.listThoughts({ limit: 5 }), null, 2));
    // Run a reasoning cycle manually
    console.log("\n--- Running Manual Reasoner Cycle ---");
    await system.reasoner.processCycle();
    console.log("\n--- Thoughts After Cycle ---");
    const updatedThought1 = await system.getThought(thought1.id);
    const updatedThought2 = await system.getThought(thought2.id);
    console.log("Meeting Note:", JSON.stringify(updatedThought1, null, 2)); // Should have 'meeting_prep' tag and higher priority
    console.log("Budget Task:", JSON.stringify(updatedThought2, null, 2)); // Should have very high priority due to #urgent tag
    // Example: Trigger task creation via guide action (indirectly via reasoner cycle)
    // Or trigger directly via tool invocation:
    if (updatedThought1) {
        console.log("\n--- Manually Invoking CreateChildTaskTool ---");
        await system.invokeTool('CreateChildTaskTool', { parentId: updatedThought1.id, content: "Send out calendar invite" }, updatedThought1.id);
        // Allow a moment for async task processing (in a real app, UI would poll or use websockets)
        await new Promise(resolve => setTimeout(resolve, 1000));
        const childTasks = await system.thoughtStore.findLinkedThoughtsFrom(updatedThought1.id, 'parent');
        console.log("Child tasks for meeting note:", JSON.stringify(childTasks, null, 2));
    }
    // Example: Semantic Search (if API key is set)
    if (process.env.OPENAI_API_KEY) {
        console.log("\n--- Performing Semantic Search for 'budget' ---");
        const searchResults = await system.findThoughts("budget");
        console.log("Search Results:", JSON.stringify(searchResults.map(r => ({ id: r.thought.id, content: r.thought.content, score: r.score })), null, 2));
    }
    else {
        console.log("\n--- Semantic Search Skipped (No API Key) ---");
    }
    // Start auto-processing in the background (optional)
    // system.startAutoProcessing(10000); // Check every 10 seconds
    // console.log("\n--- Auto-processing started. Press Ctrl+C to exit. ---");
    // Clean up
    // Add a slight delay before shutdown to allow any final async ops
    await new Promise(resolve => setTimeout(resolve, 1500));
    await system.shutdown();
}
// Execute example if run directly
//if (require.main === module) {
runExample().catch(error => {
    console.error("\n--- Example Run Failed ---");
    console.error(error);
    process.exit(1);
});
