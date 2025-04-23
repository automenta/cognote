// --- FlowMind Enhanced ---
// A single-file implementation of a dynamic note-taking and reasoning system.
// Vision: Transform notes into actionable plans and insights using a hybrid reasoning approach.
// Core Principles: Living Thoughts, Clear Reasoning, Unified Experience, In-Process Simplicity,
// Conversational Learning, Extensible Tools, Collaborative Flow.
// Architecture: Leverages LevelDB for persistence and LangChain.js for AI insights.

import { Level } from 'level';
import { v4 as uuidv4 } from 'uuid';
import { ChatOpenAI, OpenAIEmbeddings } from "@langchain/openai";
import { HumanMessage } from "@langchain/core/messages";
import { StringOutputParser } from "@langchain/core/output_parsers";
import { FaissStore } from "@langchain/community/vectorstores/faiss"; // Example vector store
import { Document } from "@langchain/core/documents";
import * as path from 'path';
import * as os from 'os';
import * as fs from 'fs/promises'; // Use promises for async file operations

// --- Configuration ---
// Ensure OPENAI_API_KEY is set in your environment variables
const DB_PATH = path.join(os.homedir(), '.flowmind-enhanced-db');
const VECTOR_STORE_PATH = path.join(os.homedir(), '.flowmind-vector-store');
const INSIGHT_MODEL_NAME = "gpt-4o-mini"; // Or another suitable model
const EMBEDDING_MODEL_NAME = "text-embedding-3-small";

// --- Utility Functions ---
const safeJsonParse = <T>(json: string | null | undefined, defaultValue: T): T => {
    try {
        return json ? JSON.parse(json) : defaultValue;
    } catch (error) {
        console.warn("JSON parse error:", error);
        return defaultValue;
    }
};

const generateId = (): string => uuidv4();

// --- Data Model ---
interface Metadata {
    createdAt: string;
    updatedAt: string;
    tags?: string[];
    status?: 'pending' | 'processing' | 'completed' | 'failed';
    errorInfo?: string;
    [key: string]: any; // Allow arbitrary metadata
}

interface Thought {
    id: string;
    content: string | Record<string, any>; // Flexible content (text, task details, etc.)
    priority: number; // 0.0 (low) to 1.0 (high)
    embedding?: number[]; // Optional embedding vector
    type: 'note' | 'goal' | 'task' | 'question' | 'insight' | 'event_marker' | string; // Extensible type system
    links: { relationship: string; targetId: string }[]; // e.g., { relationship: 'parent', targetId: 'uuid' }
    metadata: Metadata;
}

interface Guide {
    id: string;
    condition: string; // Simple condition DSL (e.g., "type=note & tag=urgent", "content_contains=meeting")
    action: string; // Action DSL (e.g., "set priority=0.9", "create_task=Schedule meeting", "add_tag=important")
    weight: number; // Learned reliability/importance (0.0 to 1.0)
    metadata: { createdAt: string; updatedAt: string; };
}

interface Event {
    id: string; // UUID
    type: string; // e.g., "thought_created", "thought_updated", "tool_invoked", "tool_success", "tool_failure"
    targetId: string; // ID of the Thought, Guide, etc. affected
    timestamp: string;
    data: Record<string, any>; // Event-specific payload
}

// --- Tool Definition ---
interface ToolContext {
    thoughtStore: ThoughtStore;
    taskQueue: TaskQueue;
    insightModel: InsightModel; // Provide access for tools needing LLM capabilities
    [key: string]: any; // Allow passing other context if needed
}

interface Tool {
    name: string;
    description: string;
    execute(params: Record<string, any>, context: ToolContext): Promise<any>; // All tools operate asynchronously
}

// --- Core Components ---

/**
 * ThoughtStore: Manages persistence of Thoughts, Guides, and Events using LevelDB.
 */
/**
 * ThoughtStore: Manages persistence of Thoughts, Guides, and Events using LevelDB.
 * Also manages the vector store for semantic search.
 */
class ThoughtStore {
    private db: Level<string, string>;
    private vectorStore?: FaissStore; // In-memory vector store, persisted separately
    private embeddings?: OpenAIEmbeddings; // Embedding model instance
    private vectorStorePath: string;

    constructor(dbPath: string, vectorStorePath: string) {
        this.db = new Level<string, string>(dbPath, { valueEncoding: 'json' });
        this.vectorStorePath = vectorStorePath;
        // Initialize vector store loading in constructor, but async
        this.initializeVectorStore(vectorStorePath).catch(err => {
            console.error("Failed to initialize vector store:", err)
        });
    }

    private async initializeVectorStore(vectorStorePath: string): Promise<void> {
        // Only initialize embeddings/vector store if an API key is available
        if (process.env.OPENAI_API_KEY) {
            this.embeddings = new OpenAIEmbeddings({ modelName: EMBEDDING_MODEL_NAME, openAIApiKey: process.env.OPENAI_API_KEY });
            try {
                // Try loading existing store
                await fs.access(vectorStorePath); // Check if the directory exists
                // FaissStore.load requires the *directory* path
                this.vectorStore = await FaissStore.load(vectorStorePath, this.embeddings);
                console.log(`Vector store loaded from ${vectorStorePath}`);
            } catch (error) {
                console.log(`Vector store not found at ${vectorStorePath}, creating new one.`);
                if (!this.embeddings) { // Guard against embeddings not being initialized
                    console.error("Embeddings model not available, cannot create vector store.");
                    return;
                }
                // Need dummy data to initialize if empty
                const dummyTexts = ["FlowMind init"];
                // FaissStore requires metadata to be Record<string, any>, Document fulfills this
                const dummyDocs = dummyTexts.map((text, i) => new Document({
                    pageContent: text,
                    metadata: { id: `init_${i}`, type: 'system_init' }
                }));

                try {
                    // Use fromDocuments to initialize the store
                    this.vectorStore = await FaissStore.fromDocuments(dummyDocs, this.embeddings);
                    // Immediately save the new empty store
                    await this.saveVectorStore();
                } catch (initError) {
                    console.error("Error initializing new FaissStore:", initError);
                    // Potentially delete the partially created directory if save failed
                    try { await fs.rm(this.vectorStorePath, { recursive: true, force: true }); } catch (rmErr) {}
                }
            }
        } else {
            console.warn("OPENAI_API_KEY not set. Semantic search and embedding features disabled.");
        }
    }

    async saveVectorStore(): Promise<void> {
        if (this.vectorStore) {
            // FaissStore.save requires the *directory* path
            await this.vectorStore.save(this.vectorStorePath);
            console.log(`Vector store saved to ${this.vectorStorePath}`);
        }
    }

    // --- Thought Operations ---
    async putThought(thought: Thought): Promise<void> {
        thought.metadata.updatedAt = new Date().toISOString();
        await this.db.put(`thought:${thought.id}`, JSON.stringify(thought));
        await this.logEvent('thought_updated', thought.id, { changes: Object.keys(thought).filter(k => k !== 'metadata' && k !== 'embedding') }); // Log meaningful changes

        // Update vector store if content exists and embeddings are enabled
        if (this.vectorStore && this.embeddings && typeof thought.content === 'string' && thought.content.trim()) {
            const doc = new Document({
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
            } catch (error) {
                console.error(`Error updating vector store for thought ${thought.id}:`, error);
            }
        }
    }

    async getThought(id: string): Promise<Thought | null> {
        try {
            const value = await this.db.get(`thought:${id}`);
            return safeJsonParse<Thought | null>(value, null);
        } catch (error: any) {
            // Use error.code for LevelDB specific errors
            if (error.code === 'LEVEL_NOT_FOUND') return null;
            console.error(`Error getting thought ${id}:`, error);
            throw error; // Re-throw other errors
        }
    }

    async deleteThought(id: string): Promise<void> {
        // Note: Removing from FAISS by ID is non-trivial and not implemented here.
        // The vector for the deleted thought will remain in the store until rebuilt.
        console.warn(`Deleting thought ${id} from LevelDB. Corresponding vector in FaissStore remains.`);
        await this.db.del(`thought:${id}`);
        await this.logEvent('thought_deleted', id, {});
    }

    async listThoughts(options: { type?: string; limit?: number; tag?: string } = {}): Promise<Thought[]> {
        const thoughts: Thought[] = [];
        const iteratorOptions = {
            limit: options.limit,
            gt: 'thought:', // Lexicographical start key for thoughts
            lt: 'thought;~' // Lexicographical end key for thoughts (adjust based on actual key format if needed)
        };

        for await (const [key, value] of this.db.iterator(iteratorOptions)) {
            const thought = safeJsonParse<Thought | null>(value, null);
            if (thought) {
                const typeMatch = !options.type || thought.type === options.type;
                const tagMatch = !options.tag || (thought.metadata.tags && thought.metadata.tags.includes(options.tag));
                if (typeMatch && tagMatch) {
                    thoughts.push(thought);
                }
            }
            // Check limit inside the loop for efficiency
            if (options.limit && thoughts.length >= options.limit) break;
        }
        return thoughts;
    }

    // Finds thoughts that have a link *pointing to* the targetId
    async findThoughtsLinkingTo(targetId: string, relationship?: string): Promise<Thought[]> {
        const linkedThoughts: Thought[] = [];
        for await (const [key, value] of this.db.iterator({ gt: 'thought:', lt: 'thought;~' })) {
            const thought = safeJsonParse<Thought | null>(value, null);
            if (thought && thought.links.some(link => link.targetId === targetId && (!relationship || link.relationship === relationship))) {
                linkedThoughts.push(thought);
            }
        }
        return linkedThoughts;
    }

    // Finds thoughts that are targets of links *from* the sourceId
    async findLinkedThoughtsFrom(sourceId: string, relationship?: string): Promise<Thought[]> {
        const sourceThought = await this.getThought(sourceId);
        if (!sourceThought) return [];

        const targetIds = sourceThought.links
            .filter(link => !relationship || link.relationship === relationship)
            .map(link => link.targetId);

        const linkedThoughts: Thought[] = [];
        for (const targetId of targetIds) {
            const targetThought = await this.getThought(targetId);
            if (targetThought) {
                linkedThoughts.push(targetThought);
            }
        }
        return linkedThoughts;
    }


    async semanticSearchThoughts(query: string, k: number = 5, filter?: Record<string, any>): Promise<{ thought: Thought, score: number }[]> {
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
            const thoughtsWithScores: { thought: Thought, score: number }[] = [];

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
                    } else {
                        console.warn(`Semantic search found vector for deleted/missing thought ID: ${thoughtId}`);
                    }
                } else {
                    console.warn("Semantic search result document missing valid 'id' in metadata:", doc.metadata);
                }
            }
            // Sort by score descending (higher similarity is better)
            return thoughtsWithScores.sort((a, b) => b.score - a.score);
        } catch (error) {
            console.error("Semantic search error:", error);
            return [];
        }
    }


    // --- Guide Operations ---
    async putGuide(guide: Guide): Promise<void> {
        guide.metadata.updatedAt = new Date().toISOString();
        await this.db.put(`guide:${guide.id}`, JSON.stringify(guide));
        await this.logEvent('guide_updated', guide.id, { changes: Object.keys(guide) });
    }

    async getGuide(id: string): Promise<Guide | null> {
        try {
            const value = await this.db.get(`guide:${id}`);
            return safeJsonParse<Guide | null>(value, null);
        } catch (error: any) {
            if (error.code === 'LEVEL_NOT_FOUND') return null;
            console.error(`Error getting guide ${id}:`, error);
            throw error;
        }
    }

    async deleteGuide(id: string): Promise<void> {
        await this.db.del(`guide:${id}`);
        await this.logEvent('guide_deleted', id, {});
    }

    async listGuides(): Promise<Guide[]> {
        const guides: Guide[] = [];
        for await (const [key, value] of this.db.iterator({ gt: 'guide:', lt: 'guide;~' })) {
            const guide = safeJsonParse<Guide | null>(value, null);
            if (guide) guides.push(guide);
        }
        return guides;
    }

    // --- Event Operations ---
    async logEvent(type: string, targetId: string, data: Record<string, any>): Promise<Event> {
        const event: Event = {
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

    async listEvents(options: { limit?: number, targetId?: string, since?: string } = {}): Promise<Event[]> {
        const events: Event[] = [];
        const iteratorOptions: any = { // Type flexibility for LevelDB options
            limit: options.limit,
            reverse: true, // Get latest events first
            lt: 'event;~' // Upper bound
        };
        // Add lower bound if 'since' is provided
        if(options.since) {
            iteratorOptions.gt = `event:${options.since}`;
        } else {
            iteratorOptions.gt = 'event:'; // Default lower bound
        }


        for await (const [key, value] of this.db.iterator(iteratorOptions)) {
            const event = safeJsonParse<Event | null>(value, null);
            if (event && (!options.targetId || event.targetId === options.targetId)) {
                events.push(event);
            }
            // Check limit inside the loop
            if (options.limit && events.length >= options.limit) break;
        }
        // Results are already reversed due to iterator options
        return events;
    }

    // --- DB Management ---
    async open(): Promise<void> {
        // db.status might not be reliable across all versions/platforms, use db.supports?.status check or try-catch
        try {
            if (this.db.status === 'closed') {
                await this.db.open();
                console.log(`ThoughtStore database opened at ${this.db.location}`);
            }
        } catch (err) { // Handle cases where status is not available or open fails
            console.warn("Could not determine DB status or DB already open/opening.");
            // Attempt to open anyway if needed, LevelDB might handle this internally
            try { await this.db.open(); } catch (openErr) {
                console.error("Failed to open database:", openErr);
                throw openErr; // Rethrow critical error
            }
        }
    }

    async close(): Promise<void> {
        try {
            if (this.db.status === 'open') {
                await this.db.close();
                console.log(`ThoughtStore database closed.`);
                // Persist vector store on close
                await this.saveVectorStore();
            }
        } catch(err) {
            console.warn("Could not determine DB status or DB already closed/closing.");
            // Attempt to close anyway
            try { await this.db.close(); await this.saveVectorStore(); } catch (closeErr) { /* Ignore */ }
        }
    }
}

/**
 * InsightModel: Interface for interacting with LangChain.js LLMs for suggestions and embeddings.
 */
class InsightModel {
    private llm: ChatOpenAI | null = null;
    private embeddings: OpenAIEmbeddings | null = null;

    constructor() {
        if (process.env.OPENAI_API_KEY) {
            this.llm = new ChatOpenAI({
                modelName: INSIGHT_MODEL_NAME,
                temperature: 0.7, // Balance creativity and predictability
                openAIApiKey: process.env.OPENAI_API_KEY
            });
            this.embeddings = new OpenAIEmbeddings({
                modelName: EMBEDDING_MODEL_NAME,
                openAIApiKey: process.env.OPENAI_API_KEY
            });
        } else {
            console.warn("OPENAI_API_KEY not set. InsightModel features are disabled.");
        }
    }

    // Generates suggestions based on a thought's content and context.
    async getSuggestions(thought: Thought, contextThoughts: Thought[] = []): Promise<string[]> {
        if (!this.llm) return Promise.resolve(["InsightModel disabled (no API key)."]);

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
            const result = await this.llm.pipe(new StringOutputParser()).invoke([new HumanMessage(prompt)]);
            // Basic parsing of suggestions, assuming newline separation
            return result.split('\n').map(s => s.trim().replace(/^- /, '')).filter(s => s.length > 0);
        } catch (error) {
            console.error("Error getting suggestions from InsightModel:", error);
            return ["Error generating suggestions."];
        }
    }

    // Generates an embedding vector for a given text.
    async generateEmbedding(text: string): Promise<number[] | null> {
        if (!this.embeddings) return Promise.resolve(null);
        try {
            const result = await this.embeddings.embedQuery(text);
            return result;
        } catch (error) {
            console.error("Error generating embedding:", error);
            return null;
        }
    }
}

/**
 * ToolHub: Manages and executes available tools.
 */
class ToolHub {
    private tools: Map<string, Tool> = new Map();

    registerTool(tool: Tool): void {
        if (this.tools.has(tool.name)) {
            console.warn(`Tool "${tool.name}" is already registered. Overwriting.`);
        }
        this.tools.set(tool.name, tool);
        console.log(`Tool registered: ${tool.name}`);
    }

    getTool(name: string): Tool | undefined {
        return this.tools.get(name);
    }

    listTools(): Tool[] {
        return Array.from(this.tools.values());
    }

    // Executes a tool by name with given parameters and context.
    async executeTool(name: string, params: Record<string, any>, context: ToolContext): Promise<any> {
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
        } catch (error: any) {
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
    private thoughtStore: ThoughtStore;
    private toolHub: ToolHub;
    private insightModel: InsightModel;
    private activeTasks: Set<string> = new Set(); // Track IDs of thoughts currently being processed

    constructor(thoughtStore: ThoughtStore, toolHub: ToolHub, insightModel: InsightModel) {
        this.thoughtStore = thoughtStore;
        this.toolHub = toolHub;
        this.insightModel = insightModel;
    }

    // Enqueues a task by associating it with a Thought (e.g., via metadata).
    async enqueueTask(thoughtId: string, toolName: string, params: Record<string, any>): Promise<void> {
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
    private async processTask(thoughtId: string): Promise<void> {
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
            const context: ToolContext = {
                thoughtStore: this.thoughtStore,
                taskQueue: this,
                insightModel: this.insightModel
            };
            await this.toolHub.executeTool(toolName, { ...params, thoughtId }, context); // Pass thoughtId in params
            thought.metadata.status = 'completed';
        } catch (error: any) {
            console.error(`Task "${toolName}" for thought ${thoughtId} failed:`, error);
            thought.metadata.status = 'failed';
            thought.metadata.errorInfo = error.message;
        } finally {
             // Fetch the latest version in case the tool modified it
            const finalThought = await this.thoughtStore.getThought(thoughtId);
            if(finalThought) {
                finalThought.metadata.status = thought.metadata.status; // Apply final status
                if(thought.metadata.errorInfo) finalThought.metadata.errorInfo = thought.metadata.errorInfo;
                await this.thoughtStore.putThought(finalThought);
            }
            this.activeTasks.delete(thoughtId); // Allow reprocessing if needed later
            console.log(`Finished processing task "${toolName}" for thought ${thoughtId}. Status: ${thought.metadata.status}`);
        }
    }

     // Method to periodically scan for and process any pending tasks (e.g., on startup or timer)
     async processPendingTasks(): Promise<void> {
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
    private thoughtStore: ThoughtStore;
    private taskQueue: TaskQueue;
    private toolHub: ToolHub;
    private insightModel: InsightModel;
    private guides: Guide[] = []; // In-memory cache of guides
    private processing: boolean = false; // Flag to prevent concurrent cycles

    constructor(thoughtStore: ThoughtStore, taskQueue: TaskQueue, toolHub: ToolHub, insightModel: InsightModel) {
        this.thoughtStore = thoughtStore;
        this.taskQueue = taskQueue;
        this.toolHub = toolHub;
        this.insightModel = insightModel;
    }

    async loadGuides(): Promise<void> {
        this.guides = await this.thoughtStore.listGuides();
        console.log(`Loaded ${this.guides.length} guides into Reasoner.`);
    }

    // Creates a new Thought with default values.
    async createThought(
        content: string | Record<string, any>,
        type: string = 'note',
        priority: number = 0.5,
        links: { relationship: string; targetId: string }[] = [],
        metadata: Partial<Metadata> = {}
    ): Promise<Thought> {
        const now = new Date().toISOString();
        const thought: Thought = {
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
    async updateThought(id: string, updates: Partial<Omit<Thought, 'id' | 'metadata'>> & { metadata?: Partial<Metadata> }): Promise<Thought | null> {
        const thought = await this.thoughtStore.getThought(id);
        if (!thought) return null;

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
    async processCycle(limit: number = 10): Promise<void> {
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
                        if (changed) modified = true;
                    }
                }

                // b) Get Insight Model suggestions (if applicable, e.g., for notes or goals)
                if (['note', 'goal', 'question'].includes(thought.type) && Math.random() < 0.2) { // Add stochastic element to avoid constant LLM calls
                    console.log(`Requesting InsightModel suggestions for thought ${thought.id}`);
                    // Fetch minimal context: parent/child thoughts
                    const linkedThoughtIds = thought.links.map(l => l.targetId);
                    const contextThoughts = (await Promise.all(linkedThoughtIds.map(id => this.thoughtStore.getThought(id))))
                                            .filter(t => t !== null) as Thought[];

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
        } catch (error) {
            console.error("Error during Reasoner cycle:", error);
        } finally {
            this.processing = false;
        }
    }

    // Evaluates a simple guide condition against a thought.
    private evaluateGuideCondition(condition: string, thought: Thought): boolean {
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
    private async applyGuideAction(action: string, thought: Thought): Promise<boolean> {
        // Simple DSL parser: "set field=value", "add_tag=value", "remove_tag=value", "create_task=content", "link_to=id:relationship"
        if (action.startsWith('set ')) {
            const [field, value] = action.substring(4).split('=', 2);
            switch (field) {
                case 'priority': thought.priority = Math.max(0, Math.min(1, parseFloat(value))); return true;
                case 'type': thought.type = value; return true;
                case 'status': thought.metadata.status = value as Thought['metadata']['status']; return true;
                default: console.warn(`Unsupported set action field: ${field}`); return false;
            }
        } else if (action.startsWith('add_tag=')) {
            const tag = action.substring(8);
            if (!thought.metadata.tags) thought.metadata.tags = [];
            if (!thought.metadata.tags.includes(tag)) {
                thought.metadata.tags.push(tag);
                return true;
            }
            return false;
        } else if (action.startsWith('remove_tag=')) {
            const tag = action.substring(11);
            if (thought.metadata.tags) {
                const index = thought.metadata.tags.indexOf(tag);
                if (index > -1) {
                    thought.metadata.tags.splice(index, 1);
                    return true;
                }
            }
            return false;
        } else if (action.startsWith('create_task=')) {
            const taskContent = action.substring(12);
            // Enqueue task creation via a dedicated tool or directly create thought
            await this.taskQueue.enqueueTask(thought.id, 'CreateChildTaskTool', { parentId: thought.id, content: taskContent });
            return false; // Action enqueued, thought itself not directly modified by *this* step
        } else if (action.startsWith('link_to=')) {
            const [targetId, relationship] = action.substring(8).split(':', 2);
            if (!thought.links.some(l => l.targetId === targetId && l.relationship === relationship)) {
                thought.links.push({ targetId, relationship: relationship || 'related' });
                return true;
            }
            return false;
        } else {
            console.warn(`Unsupported action: ${action}`);
            return false;
        }
    }

    // --- Public API for UI/External Interaction ---

    async addThought(content: string | Record<string, any>, type?: string, priority?: number, links?: { relationship: string; targetId: string }[], metadata?: Partial<Metadata>): Promise<Thought> {
        return this.createThought(content, type, priority, links, metadata);
    }

    async getThought(id: string): Promise<Thought | null> {
        return this.thoughtStore.getThought(id);
    }

    async findThoughts(query: string): Promise<{ thought: Thought, score: number }[]> {
        // Basic keyword search + semantic search
        const allThoughts = await this.thoughtStore.listThoughts();
        const keywordMatches = allThoughts
            .filter(t => typeof t.content === 'string' && t.content.toLowerCase().includes(query.toLowerCase()))
            .map(t => ({ thought: t, score: 0.7 })); // Assign arbitrary score for keyword match

        const semanticMatches = await this.thoughtStore.semanticSearchThoughts(query);

        // Combine and deduplicate results (simple approach: prioritize semantic matches)
        const combined = new Map<string, { thought: Thought, score: number }>();
        semanticMatches.forEach(m => combined.set(m.thought.id, m));
        keywordMatches.forEach(m => {
            if (!combined.has(m.thought.id)) {
                combined.set(m.thought.id, m);
            }
        });

        return Array.from(combined.values()).sort((a, b) => b.score - a.score);
    }

    async provideFeedback(thoughtId: string, feedback: { type: 'rating' | 'correction', value: number | string }): Promise<void> {
        // Placeholder for learning mechanism. Could adjust guide weights or refine insight model prompts.
        console.log(`Feedback received for thought ${thoughtId}:`, feedback);
        const thought = await this.getThought(thoughtId);
        if (!thought) return;

        thought.metadata.feedback = thought.metadata.feedback || [];
        thought.metadata.feedback.push({ ...feedback, timestamp: new Date().toISOString() });
        await this.updateThought(thoughtId, { metadata: thought.metadata });

        // Future: Use feedback to adjust Guide weights or fine-tune models.
    }
}


// --- Concrete Tool Implementations ---

class GenerateEmbeddingTool implements Tool {
    name = "GenerateEmbeddingTool";
    description = "Generates and stores an embedding for a thought's content.";

    async execute(params: { thoughtId: string, text?: string }, context: ToolContext): Promise<void> {
        const { thoughtId, text } = params;
        const thought = await context.thoughtStore.getThought(thoughtId);
        if (!thought) throw new Error(`Thought ${thoughtId} not found.`);

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
        } else {
            console.warn(`Failed to generate embedding for thought ${thoughtId}.`);
        }
    }
}

class CreateChildTaskTool implements Tool {
    name = "CreateChildTaskTool";
    description = "Creates a new task thought linked as a child to a parent thought.";

    async execute(params: { parentId: string, content: string, priority?: number }, context: ToolContext): Promise<{ taskId: string }> {
        const { parentId, content, priority = 0.6 } = params; // Default priority for tasks
        const parentThought = await context.thoughtStore.getThought(parentId);
        if (!parentThought) throw new Error(`Parent thought ${parentId} not found.`);

        const now = new Date().toISOString();
        const taskThought: Thought = {
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

class MemoryTool implements Tool {
    name = "MemoryTool";
    description = "Performs semantic search across thoughts using embeddings.";

    async execute(params: { query: string, k?: number }, context: ToolContext): Promise<{ results: { thought: Thought, score: number }[] }> {
        const { query, k = 5 } = params;
        if (!query) throw new Error("Query parameter is required for MemoryTool.");

        const results = await context.thoughtStore.semanticSearchThoughts(query, k);
        console.log(`MemoryTool found ${results.length} results for query "${query}"`);
        return { results };
    }
}

class ShareTool implements Tool {
    name = "ShareTool";
    description = "Exports thoughts to JSON format for sharing.";

    async execute(params: { thoughtIds: string[] }, context: ToolContext): Promise<{ exportedJson: string }> {
        const { thoughtIds } = params;
        if (!thoughtIds || thoughtIds.length === 0) throw new Error("At least one thoughtId is required for export.");

        const thoughtsToExport: Thought[] = [];
        for (const id of thoughtIds) {
            const thought = await context.thoughtStore.getThought(id);
            if (thought) {
                thoughtsToExport.push(thought);
                // Optionally include linked thoughts recursively (complex, omitted for brevity)
            } else {
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
    public thoughtStore: ThoughtStore;
    public insightModel: InsightModel;
    public toolHub: ToolHub;
    public taskQueue: TaskQueue;
    public reasoner: Reasoner;
    private processingInterval: NodeJS.Timeout | null = null;

    constructor(dbPath: string = DB_PATH, vectorStorePath: string = VECTOR_STORE_PATH) {
        this.thoughtStore = new ThoughtStore(dbPath, vectorStorePath);
        this.insightModel = new InsightModel();
        this.toolHub = new ToolHub();
        this.taskQueue = new TaskQueue(this.thoughtStore, this.toolHub, this.insightModel);
        this.reasoner = new Reasoner(this.thoughtStore, this.taskQueue, this.toolHub, this.insightModel);

        this.registerDefaultTools();
    }

    private registerDefaultTools(): void {
        this.toolHub.registerTool(new GenerateEmbeddingTool());
        this.toolHub.registerTool(new CreateChildTaskTool());
        this.toolHub.registerTool(new MemoryTool());
        this.toolHub.registerTool(new ShareTool());
        // Register other tools (ConnectTool, etc.) here
    }

    async initialize(): Promise<void> {
        await this.thoughtStore.open();
        await this.reasoner.loadGuides(); // Load guides on startup
        await this.taskQueue.processPendingTasks(); // Process any tasks pending from previous runs
        console.log("FlowMind Enhanced system initialized.");
    }

    // Starts the background processing cycle for the Reasoner.
    startAutoProcessing(intervalMs: number = 15000): void { // Process every 15 seconds
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

    stopAutoProcessing(): void {
        if (this.processingInterval) {
            clearInterval(this.processingInterval);
            this.processingInterval = null;
            console.log("Stopped auto-processing cycle.");
        }
    }

    async shutdown(): Promise<void> {
        this.stopAutoProcessing();
        await this.thoughtStore.close(); // This also saves the vector store
        console.log("FlowMind Enhanced system shut down gracefully.");
    }

    // --- High-Level API for interaction (e.g., from ThoughtView or CLI) ---
    async addThought(content: string | Record<string, any>, type?: string, priority?: number, links?: { relationship: string; targetId: string }[], metadata?: Partial<Metadata>) {
        return this.reasoner.addThought(content, type, priority, links, metadata);
    }

    async getThought(id: string) {
        return this.reasoner.getThought(id);
    }

    async updateThought(id: string, updates: Partial<Omit<Thought, 'id' | 'metadata'>> & { metadata?: Partial<Metadata> }) {
        return this.reasoner.updateThought(id, updates);
    }

    async deleteThought(id: string): Promise<void> {
         await this.thoughtStore.deleteThought(id);
    }

    async listThoughts(options?: { type?: string; limit?: number }) {
        return this.thoughtStore.listThoughts(options);
    }

    async findThoughts(query: string) {
        return this.reasoner.findThoughts(query);
    }

    async addGuide(condition: string, action: string, weight: number = 0.5): Promise<Guide> {
        const now = new Date().toISOString();
        const guide: Guide = {
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

    async invokeTool(toolName: string, params: Record<string, any>, thoughtId?: string) {
        // If thoughtId is provided, enqueue, otherwise execute directly (e.g., system tool)
        if (thoughtId) {
            await this.taskQueue.enqueueTask(thoughtId, toolName, params);
            return { message: `Task ${toolName} enqueued for thought ${thoughtId}` };
        } else {
             const context: ToolContext = {
                 thoughtStore: this.thoughtStore,
                 taskQueue: this.taskQueue,
                 insightModel: this.insightModel
             };
            return this.toolHub.executeTool(toolName, params, context);
        }
    }

    async getEvents(options?: { limit?: number, targetId?: string }) {
        return this.thoughtStore.listEvents(options);
    }
}

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
    console.log(JSON.stringify(await system.listThoughts({limit: 5}), null, 2));

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
    if(updatedThought1){
        console.log("\n--- Manually Invoking CreateChildTaskTool ---");
        await system.invokeTool('CreateChildTaskTool', {parentId: updatedThought1.id, content: "Send out calendar invite"}, updatedThought1.id);
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
    } else {
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
//}

// Export the main system class for potential external use
export { FlowMindSystem, Thought, Guide, Event, Tool, ToolContext };
