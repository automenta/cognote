import * as fs from 'fs/promises';
import * as fsSync from 'fs';
import * as path from 'path';
import * as os from 'os';
import * as readline from 'readline';
import { v4 as uuidv4 } from 'uuid';
import chalk from 'chalk'; // Added chalk

// --- Langchain Imports ---
import { ChatOllama } from "@langchain/community/chat_models/ollama";
import { OllamaEmbeddings } from "@langchain/community/embeddings/ollama";
import { BaseChatModel } from "@langchain/core/language_models/chat_models";
import { Embeddings } from "@langchain/core/embeddings";
import { HumanMessage } from "@langchain/core/messages";
import { StringOutputParser } from "@langchain/core/output_parsers";
import { Document } from "@langchain/core/documents";
import { FaissStore } from "@langchain/community/vectorstores/faiss";

// --- Configuration ---
const DATA_DIR = path.join(os.homedir(), '.flowmind-console');
const STATE_FILE = path.join(DATA_DIR, 'flowmind_state.json');
const VECTOR_STORE_DIR = path.join(DATA_DIR, 'vector-store');
const OLLAMA_BASE_URL = process.env.OLLAMA_HOST || "http://localhost:11434";
const OLLAMA_MODEL = 'hf.co/DevQuasar/Orion-zhen.phi-4-abliterated-GGUF:Q3_K_M' // Updated default model
const OLLAMA_EMBEDDING_MODEL = OLLAMA_MODEL; // Updated default embedding model
const WORKER_INTERVAL = 2000; // ms
const UI_REFRESH_INTERVAL = 1000; // ms
const SAVE_DEBOUNCE = 5000; // ms
const MAX_RETRIES = 2;
const DEFAULT_BELIEF_POS = 1.0;
const DEFAULT_BELIEF_NEG = 1.0;
const SHORT_ID_LEN = 6;

// --- Enums ---
enum Status { PENDING = 'PENDING', ACTIVE = 'ACTIVE', WAITING = 'WAITING', DONE = 'DONE', FAILED = 'FAILED' }
enum Type { INPUT = 'INPUT', GOAL = 'GOAL', STRATEGY = 'STRATEGY', OUTCOME = 'OUTCOME', QUERY = 'QUERY', USER_PROMPT = 'USER_PROMPT', SYSTEM = 'SYSTEM' }

// --- Types and Interfaces ---
type Term = Atom | Variable | Structure | ListTerm;
interface Atom { kind: 'Atom'; name: string; }
interface Variable { kind: 'Variable'; name: string; }
interface Structure { kind: 'Structure'; name: string; args: Term[]; }
interface ListTerm { kind: 'ListTerm'; elements: Term[]; }

interface BeliefData { pos: number; neg: number; } // Shortened names

interface Metadata {
    rootId?: string;
    parentId?: string;
    ruleId?: string;
    agentId?: string;
    created?: string; // Shortened
    modified?: string; // Shortened
    priority?: number;
    error?: string; // Shortened
    uiContext?: string | { promptText?: string, promptId?: string };
    tags?: string[];
    feedback?: any[];
    embedded?: string; // Shortened
    suggestions?: string[]; // Shortened
    retries?: number;
    waitingFor?: string; // Shortened
    responseTo?: string; // Shortened
    provenance?: string; // Added for tracking origin
    [key: string]: any; // Keep for flexibility
}

interface Thought {
    id: string;
    type: Type;
    content: Term;
    belief: Belief;
    status: Status;
    metadata: Metadata;
}

interface Rule {
    id: string;
    pattern: Term;
    action: Term;
    belief: Belief;
    metadata: {
        description?: string;
        provenance?: string;
        created?: string; // Shortened
    };
}

interface MemoryEntry {
    id: string;
    embedding: number[];
    content: string;
    metadata: { created: string; type: string; sourceId: string; }; // Shortened 'timestamp'
}

interface AppState {
    thoughts: Record<string, any>;
    rules: Record<string, any>;
}

interface ToolContext {
    thoughts: ThoughtStore;
    rules: RuleStore;
    memory: MemoryStore;
    llm: LLMService;
    engine: Engine;
}

interface Tool {
    name: string;
    description: string;
    execute(actionTerm: Structure, context: ToolContext, trigger: Thought): Promise<Term | null>;
}

// --- Utility Functions ---
function generateId(): string { return uuidv4(); }
function shortId(id: string | undefined): string { return id ? id.substring(0, SHORT_ID_LEN) : '------'; }

function debounce<T extends (...args: any[]) => any>(func: T, wait: number): T & { cancel: () => void } {
    let timeout: NodeJS.Timeout | null = null;
    const debounced = (...args: Parameters<T>): void => {
        const later = () => {
            timeout = null;
            func(...args);
        };
        if (timeout) clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
    debounced.cancel = () => {
        if (timeout) clearTimeout(timeout);
        timeout = null;
    };
    return debounced as T & { cancel: () => void };
}

function safeJsonParse<T>(json: string | null | undefined, defaultValue: T): T {
    if (!json) return defaultValue;
    try { return JSON.parse(json); } catch { return defaultValue; }
}

function sleep(ms: number): Promise<void> { return new Promise(resolve => setTimeout(resolve, ms)); }

// --- Core Classes ---

class Belief implements BeliefData {
    pos: number;
    neg: number;

    constructor(pos: number = DEFAULT_BELIEF_POS, neg: number = DEFAULT_BELIEF_NEG) {
        this.pos = pos;
        this.neg = neg;
    }

    score(): number { return (this.pos + 1) / (this.pos + this.neg + 2); }
    update(success: boolean): void { success ? this.pos++ : this.neg++; }
    toJSON(): BeliefData { return { pos: this.pos, neg: this.neg }; }
    static fromJSON(data: BeliefData): Belief { return new Belief(data.pos, data.neg); }
    static DEFAULT = new Belief();
}

namespace TermLogic {
    export const Atom = (name: string): Atom => ({ kind: 'Atom', name });
    export const Variable = (name: string): Variable => ({ kind: 'Variable', name });
    export const Structure = (name: string, args: Term[]): Structure => ({ kind: 'Structure', name, args });
    export const List = (elements: Term[]): ListTerm => ({ kind: 'ListTerm', elements }); // Renamed for clarity

    export function format(term: Term | null | undefined, detail: 'short' | 'full' = 'short'): string {
        if (!term) return chalk.grey('null');
        switch (term.kind) {
            case 'Atom': return chalk.green(term.name);
            case 'Variable': return chalk.cyan(`?${term.name}`);
            case 'Structure':
                const argsStr = term.args.map(a => format(a, detail)).join(', ');
                return `${chalk.yellow(term.name)}(${argsStr})`;
            case 'ListTerm':
                const elementsStr = term.elements.map(e => format(e, detail)).join(', ');
                return `[${elementsStr}]`;
            default: return chalk.red('invalid_term');
        }
    }

    export function toString(term: Term): string { // Simple string representation for LLMs etc.
        switch (term.kind) {
            case 'Atom': return term.name;
            case 'Variable': return `?${term.name}`;
            case 'Structure': return `${term.name}: ${term.args.map(toString).join('; ')}`;
            case 'ListTerm': return term.elements.map(toString).join(', ');
            default: return '';
        }
    }

    export function fromString(input: string): Term { // Basic parsing for simple inputs
        input = input.trim();
        if (input.includes(':') && input.includes(';')) {
            const [name, argsStr] = input.split(':', 2);
            const args = argsStr.split(';').map(s => Atom(s.trim()));
            return Structure(name.trim(), args);
        }
        if (input.startsWith('[') && input.endsWith(']')) {
            const elements = input.slice(1, -1).split(',').map(s => Atom(s.trim()));
            return List(elements);
        }
        return Atom(input);
    }

    // --- Unification & Substitution (unchanged logic, minor readability) ---
    export function unify(term1: Term, term2: Term, bindings: Map<string, Term> = new Map()): Map<string, Term> | null {
        const resolve = (term: Term, currentBindings: Map<string, Term>): Term => {
            if (term.kind === 'Variable' && currentBindings.has(term.name)) {
                return resolve(currentBindings.get(term.name)!, currentBindings);
            }
            return term;
        };

        const t1 = resolve(term1, bindings);
        const t2 = resolve(term2, bindings);

        if (t1.kind === 'Variable') {
            if (t1.name === (t2 as Variable).name && t1.kind === t2.kind) return bindings; // Avoid binding var to itself
            const newBindings = new Map(bindings);
            newBindings.set(t1.name, t2);
            return newBindings;
        }
        if (t2.kind === 'Variable') {
            const newBindings = new Map(bindings);
            newBindings.set(t2.name, t1);
            return newBindings;
        }
        if (t1.kind !== t2.kind) return null;

        switch (t1.kind) {
            case 'Atom':
                return t1.name === (t2 as Atom).name ? bindings : null;
            case 'Structure': {
                const s2 = t2 as Structure;
                if (t1.name !== s2.name || t1.args.length !== s2.args.length) return null;
                let currentBindings = bindings;
                for (let i = 0; i < t1.args.length; i++) {
                    const result = unify(t1.args[i], s2.args[i], currentBindings);
                    if (!result) return null;
                    currentBindings = result;
                }
                return currentBindings;
            }
            case 'ListTerm': {
                const l2 = t2 as ListTerm;
                if (t1.elements.length !== l2.elements.length) return null;
                let currentBindings = bindings;
                for (let i = 0; i < t1.elements.length; i++) {
                    const result = unify(t1.elements[i], l2.elements[i], currentBindings);
                    if (!result) return null;
                    currentBindings = result;
                }
                return currentBindings;
            }
        }
        return null; // Should not be reached
    }

    export function substitute(term: Term, bindings: Map<string, Term>): Term {
        if (term.kind === 'Variable' && bindings.has(term.name)) {
            // Recursively substitute until a non-variable or unbound variable is found
            return substitute(bindings.get(term.name)!, bindings);
        }
        if (term.kind === 'Structure') {
            return { ...term, args: term.args.map(arg => substitute(arg, bindings)) };
        }
        if (term.kind === 'ListTerm') {
            return { ...term, elements: term.elements.map(el => substitute(el, bindings)) };
        }
        return term; // Atoms and unbound Variables remain unchanged
    }
}

abstract class BaseStore<T extends { id: string }> {
    protected items = new Map<string, T>();
    protected listeners: (() => void)[] = [];

    add(item: T): void {
        this.items.set(item.id, item);
        this.notifyChange();
    }

    get(id: string): T | undefined { return this.items.get(id); }

    update(item: T): void {
        if (this.items.has(item.id)) {
            // Attempt to merge metadata if possible, otherwise overwrite
            const existing = this.items.get(item.id);
            const updatedItem = {
                ...item,
                metadata: { ...(existing as any)?.metadata, ...(item as any).metadata, modified: new Date().toISOString() }
            };
            this.items.set(item.id, updatedItem);
            this.notifyChange();
        }
    }

    delete(id: string): boolean {
        const deleted = this.items.delete(id);
        if (deleted) this.notifyChange();
        return deleted;
    }

    getAll(): T[] { return Array.from(this.items.values()); }
    count(): number { return this.items.size; }

    addChangeListener(listener: () => void): void { this.listeners.push(listener); }
    removeChangeListener(listener: () => void): void { this.listeners = this.listeners.filter(l => l !== listener); }
    protected notifyChange(): void { this.listeners.forEach(listener => listener()); }

    abstract toJSON(): Record<string, any>;
    abstract loadJSON(data: Record<string, any>): void;
}

class ThoughtStore extends BaseStore<Thought> {
    getPending(): Thought[] {
        return this.getAll().filter(t => t.status === Status.PENDING);
    }

    getAllByRootId(rootId: string): Thought[] {
        return this.getAll()
            .filter(t => t.metadata.rootId === rootId || t.id === rootId)
            .sort((a, b) => Date.parse(a.metadata.created ?? '0') - Date.parse(b.metadata.created ?? '0'));
    }

    searchByTag(tag: string): Thought[] {
        return this.getAll().filter(t => t.metadata.tags?.includes(tag));
    }

    findThought(idPrefix: string): Thought | undefined {
        if (this.items.has(idPrefix)) return this.items.get(idPrefix);
        for (const thought of this.items.values()) {
            if (thought.id.startsWith(idPrefix)) return thought;
        }
        return undefined;
    }

    toJSON(): Record<string, any> {
        const obj: Record<string, any> = {};
        this.items.forEach((thought, id) => obj[id] = { ...thought, belief: thought.belief.toJSON() });
        return obj;
    }

    loadJSON(data: Record<string, any>): void {
        this.items.clear();
        for (const id in data) {
            const thoughtData = data[id];
            this.add({ ...thoughtData, belief: Belief.fromJSON(thoughtData.belief) });
        }
    }
}

class RuleStore extends BaseStore<Rule> {
    searchByDescription(desc: string): Rule[] {
        const lowerDesc = desc.toLowerCase();
        return this.getAll().filter(r => r.metadata.description?.toLowerCase().includes(lowerDesc));
    }

    findRule(idPrefix: string): Rule | undefined {
        if (this.items.has(idPrefix)) return this.items.get(idPrefix);
        for (const rule of this.items.values()) {
            if (rule.id.startsWith(idPrefix)) return rule;
        }
        return undefined;
    }

    toJSON(): Record<string, any> {
        const obj: Record<string, any> = {};
        this.items.forEach((rule, id) => obj[id] = { ...rule, belief: rule.belief.toJSON() });
        return obj;
    }

    loadJSON(data: Record<string, any>): void {
        this.items.clear();
        for (const id in data) {
            const ruleData = data[id];
            this.add({ ...ruleData, belief: Belief.fromJSON(ruleData.belief) });
        }
    }
}

class MemoryStore {
    private vectorStore: FaissStore | null = null;
    private embeddings: Embeddings;
    private storePath: string;
    private isReady = false;

    constructor(embeddingsService: Embeddings, storePath: string) {
        this.embeddings = embeddingsService;
        this.storePath = storePath;
    }

    async initialize(): Promise<void> {
        if (this.isReady) return;
        try {
            await fs.access(this.storePath);
            this.vectorStore = await FaissStore.load(this.storePath, this.embeddings);
            this.isReady = true;
            console.log(chalk.blue(`Vector store loaded from ${this.storePath}`));
        } catch (error: any) {
            if (error.code === 'ENOENT') {
                console.log(chalk.yellow(`Vector store not found at ${this.storePath}, initializing new store.`));
                // Initialize with a dummy document to create the store files
                const dummyDoc = new Document({
                    pageContent: "FlowMind vector store initialized.",
                    metadata: { sourceId: "init", type: "SYSTEM", created: new Date().toISOString() }
                });
                try {
                    this.vectorStore = await FaissStore.fromDocuments([dummyDoc], this.embeddings);
                    await this.vectorStore.save(this.storePath);
                    this.isReady = true;
                    console.log(chalk.green(`New vector store created at ${this.storePath}`));
                } catch (initError) {
                    console.error(chalk.red('Failed to initialize new vector store:'), initError);
                    this.isReady = false; // Ensure not marked as ready
                }
            } else {
                console.error(chalk.red('Failed to load vector store:'), error);
                this.isReady = false;
            }
        }
    }

    async add(entry: Omit<MemoryEntry, 'embedding'>): Promise<void> {
        if (!this.vectorStore || !this.isReady) {
            console.warn(chalk.yellow('MemoryStore not ready, cannot add entry.'));
            return;
        }
        const doc = new Document({
            pageContent: entry.content,
            metadata: { ...entry.metadata, id: entry.id } // Add entry ID to metadata
        });
        try {
            await this.vectorStore.addDocuments([doc]);
            // Saving frequently might be slow, consider batching or less frequent saves
            await this.save();
        } catch (error) {
            console.error(chalk.red('Failed to add document to vector store:'), error);
        }
    }

    async search(query: string, k: number = 5): Promise<MemoryEntry[]> {
        if (!this.vectorStore || !this.isReady) {
            console.warn(chalk.yellow('MemoryStore not ready, cannot search.'));
            return [];
        }
        try {
            const results = await this.vectorStore.similaritySearchWithScore(query, k);
            return results.map(([doc, score]) => ({
                id: doc.metadata.id || generateId(), // Use stored ID or generate if missing
                embedding: [], // Embeddings are not typically returned by search
                content: doc.pageContent,
                metadata: doc.metadata as MemoryEntry['metadata'],
                score: score // Include score if needed
            }));
        } catch (error) {
            console.error(chalk.red('Failed to search vector store:'), error);
            return [];
        }
    }

    async save(): Promise<void> {
        if (this.vectorStore && this.isReady) {
            try {
                await this.vectorStore.save(this.storePath);
            } catch (error) {
                console.error(chalk.red('Failed to save vector store:'), error);
            }
        }
    }
}

class LLMService {
    llm: BaseChatModel;
    embeddings: Embeddings;

    constructor() {
        this.llm = new ChatOllama({
            baseUrl: OLLAMA_BASE_URL,
            model: OLLAMA_MODEL,
            temperature: 0.7
        });
        this.embeddings = new OllamaEmbeddings({
            model: OLLAMA_EMBEDDING_MODEL,
            baseUrl: OLLAMA_BASE_URL
        });
        console.log(chalk.blue(`LLM Service configured: Model=${OLLAMA_MODEL}, Embeddings=${OLLAMA_EMBEDDING_MODEL}, URL=${OLLAMA_BASE_URL}`));
    }

    async generate(prompt: string): Promise<string> {
        try {
            const response = await this.llm.pipe(new StringOutputParser()).invoke([new HumanMessage(prompt)]);
            return response.trim();
        } catch (error: any) {
            console.error(chalk.red(`LLM generation failed: ${error.message}`));
            return `Error: LLM generation failed. ${error.message}`;
        }
    }

    async embed(text: string): Promise<number[]> {
        try {
            return await this.embeddings.embedQuery(text);
        } catch (error: any) {
            console.error(chalk.red(`Embedding failed: ${error.message}`));
            return [];
        }
    }
}

// --- Tools ---

class LLMTool implements Tool {
    name = "LLMTool";
    description = "Interacts with the LLM for generation."; // Simplified focus

    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        const promptTerm = action.args[1];

        if (operation !== 'generate' || !promptTerm) {
            return TermLogic.Atom("error:LLMTool_invalid_params");
        }

        const promptText = TermLogic.toString(promptTerm);
        const response = await context.llm.generate(promptText);

        if (response.startsWith('Error:')) {
            return TermLogic.Atom(`error:LLMTool_generation_failed:${response}`);
        }

        // Return response as Atom - caller might need to parse/structure it
        return TermLogic.Atom(response);
    }
}

class MemoryTool implements Tool {
    name = "MemoryTool";
    description = "Manages memory operations: add, search.";

    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (!operation) return TermLogic.Atom("error:MemoryTool_missing_operation");

        if (operation === 'add') {
            const contentTerm = action.args[1];
            if (!contentTerm) return TermLogic.Atom("error:MemoryTool_missing_add_content");

            const contentStr = TermLogic.toString(contentTerm);
            const sourceId = trigger.id; // Default source is the triggering thought
            const contentType = trigger.type; // Default type is the triggering thought's type

            await context.memory.add({
                id: generateId(), // Generate a unique ID for the memory entry itself
                content: contentStr,
                metadata: { created: new Date().toISOString(), type: contentType, sourceId: sourceId }
            });
            trigger.metadata.embedded = new Date().toISOString(); // Mark thought as embedded
            context.thoughts.update(trigger);
            return TermLogic.Atom("ok:memory_added");

        } else if (operation === 'search') {
            const queryTerm = action.args[1];
            const kTerm = action.args[2];
            if (!queryTerm) return TermLogic.Atom("error:MemoryTool_missing_search_query");

            const queryStr = TermLogic.toString(queryTerm);
            const k = (kTerm?.kind === 'Atom' && !isNaN(parseInt(kTerm.name))) ? parseInt(kTerm.name, 10) : 3;

            const results = await context.memory.search(queryStr, k);
            // Return results as a List of Atoms containing the content
            return TermLogic.List(results.map(r => TermLogic.Atom(r.content)));
        }

        return TermLogic.Atom(`error:MemoryTool_unsupported_operation:${operation}`);
    }
}

class UserInteractionTool implements Tool {
    name = "UserInteractionTool";
    description = "Requests input from the user via a prompt.";

    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (operation !== 'prompt') return TermLogic.Atom("error:UITool_unsupported_operation");

        const promptTextTerm = action.args[1];
        if (!promptTextTerm) return TermLogic.Atom("error:UITool_missing_prompt_text");

        const promptText = TermLogic.toString(promptTextTerm);
        const promptId = generateId();

        const promptThought: Thought = {
            id: generateId(),
            type: Type.USER_PROMPT,
            content: TermLogic.Atom(promptText), // Store the prompt text itself
            belief: Belief.DEFAULT,
            status: Status.PENDING, // This thought waits for the UI to display it
            metadata: {
                rootId: trigger.metadata.rootId ?? trigger.id,
                parentId: trigger.id,
                created: new Date().toISOString(),
                uiContext: { promptText, promptId }, // Context for UI rendering
                provenance: this.name,
            }
        };
        context.engine.addThought(promptThought); // Add the prompt thought to the store

        // Set the triggering thought to WAITING state
        trigger.status = Status.WAITING;
        trigger.metadata.waitingFor = promptId; // Link trigger to the prompt ID it's waiting for
        context.thoughts.update(trigger);

        // Return the prompt ID so the engine knows a prompt was issued
        return TermLogic.Atom(`ok:prompt_requested:${promptId}`);
    }
}

class GoalProposalTool implements Tool {
    name = "GoalProposalTool";
    description = "Suggests new goals based on context and memory.";

    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (operation !== 'suggest') return TermLogic.Atom("error:GoalTool_unsupported_operation");

        const contextTerm = action.args[1] ?? trigger.content; // Use provided context or trigger's content
        const contextStr = TermLogic.toString(contextTerm);

        // Search memory for related items
        const memoryResults = await context.memory.search(`Relevant past goals or outcomes related to: ${contextStr}`, 3);
        const memoryContext = memoryResults.length > 0
            ? `\nRelated past activities:\n - ${memoryResults.map(r => r.content).join("\n - ")}`
            : "";

        const prompt = `Based on the current context "${contextStr}"${memoryContext}\n\nSuggest ONE concise, actionable next goal or task. Output only the suggested goal text. Be specific.`;

        const suggestionText = await context.llm.generate(prompt);

        if (suggestionText && !suggestionText.startsWith('Error:')) {
            const suggestionThought: Thought = {
                id: generateId(),
                type: Type.INPUT, // Suggested goals start as INPUT
                content: TermLogic.Atom(suggestionText),
                belief: new Belief(1, 0), // Slightly positive initial belief
                status: Status.PENDING,
                metadata: {
                    rootId: trigger.metadata.rootId ?? trigger.id,
                    parentId: trigger.id,
                    created: new Date().toISOString(),
                    tags: ['suggested_goal'],
                    provenance: this.name
                }
            };
            context.engine.addThought(suggestionThought);
            return TermLogic.Atom(`ok:suggestion_created:${suggestionThought.id}`);
        } else if (suggestionText.startsWith('Error:')) {
            return TermLogic.Atom(`error:GoalTool_llm_failed:${suggestionText}`);
        }

        return TermLogic.Atom("ok:no_suggestion_generated");
    }
}

class CoreTool implements Tool {
    name = "CoreTool";
    description = "Manages internal FlowMind operations (status, thoughts).";

    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;

        switch (operation) {
            case 'set_status': {
                const targetIdTerm = action.args[1];
                const newStatusTerm = action.args[2];
                if (targetIdTerm?.kind !== 'Atom' || newStatusTerm?.kind !== 'Atom')
                    return TermLogic.Atom("error:CoreTool_invalid_set_status_params");

                const targetId = targetIdTerm.name;
                const newStatus = newStatusTerm.name.toUpperCase() as Status; // Ensure uppercase
                if (!Object.values(Status).includes(newStatus))
                    return TermLogic.Atom(`error:CoreTool_invalid_status_value:${newStatusTerm.name}`);

                const targetThought = context.thoughts.findThought(targetId); // Use findThought for prefix matching
                if (targetThought) {
                    targetThought.status = newStatus;
                    context.thoughts.update(targetThought);
                    return TermLogic.Atom(`ok:status_set:${shortId(targetId)}_to_${newStatus}`);
                }
                return TermLogic.Atom(`error:CoreTool_target_not_found:${targetId}`);
            }
            case 'add_thought': {
                const typeTerm = action.args[1];
                const contentTerm = action.args[2];
                // Optional root/parent IDs
                const rootIdTerm = action.args[3];
                const parentIdTerm = action.args[4];

                if (typeTerm?.kind !== 'Atom' || !contentTerm)
                    return TermLogic.Atom("error:CoreTool_invalid_add_thought_params");

                const type = typeTerm.name.toUpperCase() as Type;
                if (!Object.values(Type).includes(type))
                    return TermLogic.Atom(`error:CoreTool_invalid_thought_type:${typeTerm.name}`);

                const newThought: Thought = {
                    id: generateId(),
                    type,
                    content: contentTerm,
                    belief: Belief.DEFAULT,
                    status: Status.PENDING,
                    metadata: {
                        // Use provided IDs or derive from trigger
                        rootId: rootIdTerm?.kind === 'Atom' ? rootIdTerm.name : trigger.metadata.rootId ?? trigger.id,
                        parentId: parentIdTerm?.kind === 'Atom' ? parentIdTerm.name : trigger.id,
                        created: new Date().toISOString(),
                        provenance: `${this.name} (triggered by ${shortId(trigger.id)})`
                    }
                };
                context.engine.addThought(newThought);
                return TermLogic.Atom(`ok:thought_added:${shortId(newThought.id)}`);
            }
            case 'delete_thought': {
                const targetIdTerm = action.args[1];
                if (targetIdTerm?.kind !== 'Atom')
                    return TermLogic.Atom("error:CoreTool_invalid_delete_thought_params");

                const targetId = targetIdTerm.name;
                const targetThought = context.thoughts.findThought(targetId);
                if (targetThought) {
                    const deleted = context.thoughts.delete(targetThought.id);
                    return deleted ? TermLogic.Atom(`ok:thought_deleted:${shortId(targetThought.id)}`) : TermLogic.Atom(`error:CoreTool_delete_failed:${targetId}`);
                }
                return TermLogic.Atom(`error:CoreTool_thought_not_found:${targetId}`);
            }
            default:
                return TermLogic.Atom(`error:CoreTool_unsupported_operation:${operation}`);
        }
    }
}

class ToolRegistry {
    private tools = new Map<string, Tool>();

    register(tool: Tool): void {
        if (this.tools.has(tool.name)) {
            console.warn(chalk.yellow(`Tool "${tool.name}" is being redefined.`));
        }
        this.tools.set(tool.name, tool);
    }

    get(name: string): Tool | undefined {
        return this.tools.get(name);
    }

    list(): Tool[] {
        return Array.from(this.tools.values());
    }
}

class Engine {
    private activeIds = new Set<string>();
    private batchSize: number = 5;
    private maxConcurrent: number = 3;
    private context: ToolContext; // Hold context for tools

    constructor(
        private thoughts: ThoughtStore,
        private rules: RuleStore,
        private memory: MemoryStore,
        private llm: LLMService,
        private tools: ToolRegistry
    ) {
        // Create the context object needed by tools
        this.context = {
            thoughts: this.thoughts,
            rules: this.rules,
            memory: this.memory,
            llm: this.llm,
            engine: this, // Allow tools to interact back with the engine (e.g., add thoughts)
            tools: this.tools // Provide tool registry access if needed? (Maybe not)
        };
    }

    addThought(thought: Thought): void {
        this.thoughts.add(thought);
    }

    // --- Thought Selection (Weighted Random Sampling) ---
    private sampleThought(): Thought | null {
        const candidates = this.thoughts.getPending().filter(t => !this.activeIds.has(t.id));
        if (candidates.length === 0) return null;

        const weights = candidates.map(t => t.metadata.priority ?? t.belief.score());
        const totalWeight = weights.reduce((sum, w) => sum + w, 0);

        if (totalWeight <= 0) { // Handle zero or negative weights (fall back to uniform random)
            return candidates[Math.floor(Math.random() * candidates.length)];
        }

        let random = Math.random() * totalWeight;
        for (let i = 0; i < candidates.length; i++) {
            random -= weights[i];
            if (random <= 0) return candidates[i];
        }

        return candidates[candidates.length - 1]; // Fallback
    }

    // --- Rule Selection (Weighted Random Sampling among matches) ---
    private findAndSelectRule(thought: Thought): { rule: Rule; bindings: Map<string, Term> } | null {
        const matches = this.rules.getAll()
            .map(rule => ({ rule, bindings: TermLogic.unify(rule.pattern, thought.content) }))
            .filter(m => m.bindings !== null) as { rule: Rule; bindings: Map<string, Term> }[];

        if (matches.length === 0) return null;
        if (matches.length === 1) return matches[0];

        // Weighted selection based on rule belief score
        const weights = matches.map(m => m.rule.belief.score());
        const totalWeight = weights.reduce((sum, w) => sum + w, 0);

        if (totalWeight <= 0) { // Handle zero/negative weights
            return matches[Math.floor(Math.random() * matches.length)];
        }

        let random = Math.random() * totalWeight;
        for (let i = 0; i < matches.length; i++) {
            random -= weights[i];
            if (random <= 0) return matches[i];
        }

        return matches[matches.length - 1]; // Fallback
    }

    // --- Action Execution ---
    private async executeAction(thought: Thought, rule: Rule, bindings: Map<string, Term>): Promise<boolean> {
        const boundAction = TermLogic.substitute(rule.action, bindings);
        if (boundAction.kind !== 'Structure') {
            this.handleFailure(thought, rule, `Invalid action term kind: ${boundAction.kind}`);
            return false;
        }

        const tool = this.tools.get(boundAction.name);
        if (!tool) {
            this.handleFailure(thought, rule, `Tool not found: ${boundAction.name}`);
            return false;
        }

        let success = false;
        let resultTerm: Term | null = null;
        try {
            // Pass the shared context to the tool
            resultTerm = await tool.execute(boundAction, this.context, thought);

            // Check if the thought's status was changed by the tool (e.g., to WAITING)
            const currentThoughtState = this.thoughts.get(thought.id);
            const isWaiting = currentThoughtState?.status === Status.WAITING;
            const isFailed = currentThoughtState?.status === Status.FAILED;

            if (resultTerm?.kind === 'Atom' && resultTerm.name.startsWith('error:')) {
                this.handleFailure(thought, rule, `Tool execution failed: ${resultTerm.name}`);
                success = false;
            } else if (!isWaiting && !isFailed) {
                // Only mark success if not waiting or already failed
                this.handleSuccess(thought, rule);
                success = true;
            } else {
                // If waiting or failed, don't mark success, but don't override status
                success = false; // Indicate action didn't lead to DONE state directly
            }

        } catch (error: any) {
            console.error(chalk.red(`Tool execution exception for Tool ${tool.name} on Thought ${shortId(thought.id)}:`), error);
            this.handleFailure(thought, rule, `Tool execution exception: ${error.message}`);
            success = false;
        }

        // Update rule belief based on final success *unless* the thought ended up waiting
        const finalThoughtState = this.thoughts.get(thought.id);
        if (finalThoughtState?.status !== Status.WAITING) {
            rule.belief.update(success); // Update belief based on whether it completed successfully (not waiting)
            this.rules.update(rule);
        }

        return success;
    }

    // --- Fallback Handling (No Rule Match) ---
    private async handleNoRuleMatch(thought: Thought): Promise<void> {
        let prompt = "";
        let targetType: Type | null = null;
        let action: Term | null = null; // For potential direct tool calls

        // Define default behaviors based on thought type
        switch (thought.type) {
            case Type.INPUT:
                prompt = `Given the input: "${TermLogic.toString(thought.content)}". Define a specific, actionable GOAL. Output only the goal text.`;
                targetType = Type.GOAL;
                break;
            case Type.GOAL:
                prompt = `Given the goal: "${TermLogic.toString(thought.content)}". Outline 1-3 concise STRATEGY steps to achieve this. Output only the steps, one per line.`;
                targetType = Type.STRATEGY;
                break;
            case Type.STRATEGY:
                // For strategies, assume execution and ask for outcome
                prompt = `The strategy step "${TermLogic.toString(thought.content)}" was performed. Summarize a likely OUTCOME or result. Output only the outcome text.`;
                targetType = Type.OUTCOME;
                break;
            case Type.OUTCOME:
                // Outcomes often trigger memory storage and potentially new goal suggestions
                action = TermLogic.Structure("MemoryTool", [TermLogic.Atom("add"), thought.content]);
                // Could chain GoalProposalTool here too
                break;
            // Add cases for QUERY, USER_PROMPT, SYSTEM if specific default actions are needed
            default:
                // Default: Ask the user what to do
                const askUserPrompt = `No rule matched thought [${shortId(thought.id)}] (${thought.type}: ${TermLogic.toString(thought.content).substring(0, 50)}...). What should be done next?`;
                action = TermLogic.Structure("UserInteractionTool", [TermLogic.Atom("prompt"), TermLogic.Atom(askUserPrompt)]);
                break;
        }

        // Execute determined action (either LLM call or direct tool use)
        if (prompt && targetType) {
            const resultText = await this.llm.generate(prompt);
            if (resultText && !resultText.startsWith('Error:')) {
                // Split multi-line responses into separate thoughts
                resultText.split('\n')
                    .map(s => s.trim().replace(/^- /, '')) // Clean up list markers
                    .filter(s => s)
                    .forEach(resText => {
                        this.addThought({
                            id: generateId(),
                            type: targetType!,
                            content: TermLogic.Atom(resText),
                            belief: Belief.DEFAULT,
                            status: Status.PENDING,
                            metadata: {
                                rootId: thought.metadata.rootId ?? thought.id,
                                parentId: thought.id,
                                created: new Date().toISOString(),
                                provenance: 'llm_fallback_handler'
                            }
                        });
                    });
                // Mark original thought as DONE since fallback generated next steps
                thought.status = Status.DONE;
                thought.belief.update(true); // Considered successful as it led to next steps
                this.thoughts.update(thought);
            } else {
                this.handleFailure(thought, null, `LLM fallback failed: ${resultText}`);
            }
        } else if (action?.kind === 'Structure') {
            // Execute a direct tool action (like MemoryTool add or UserInteractionTool prompt)
            const tool = this.tools.get(action.name);
            if (tool) {
                try {
                    await tool.execute(action, this.context, thought);
                    // Tool execution handles status updates (e.g., UserInteractionTool sets WAITING)
                    // If the status wasn't changed to WAITING/FAILED, assume success for the fallback itself
                    const currentStatus = this.thoughts.get(thought.id)?.status;
                    if (currentStatus !== Status.WAITING && currentStatus !== Status.FAILED) {
                        thought.status = Status.DONE; // Mark as done if tool executed without waiting/failing
                        thought.belief.update(true);
                        this.thoughts.update(thought);
                    }
                } catch (error: any) {
                    this.handleFailure(thought, null, `Fallback tool execution failed (${action.name}): ${error.message}`);
                }
            } else {
                this.handleFailure(thought, null, `Fallback tool not found: ${action.name}`);
            }
        } else if (thought.status === Status.PENDING) {
            // If no action determined and still pending, mark as failed
            this.handleFailure(thought, null, "No matching rule and no applicable fallback action.");
        }
    }

    // --- Status Handling ---
    private handleFailure(thought: Thought, rule: Rule | null, errorInfo: string): void {
        const retries = (thought.metadata.retries ?? 0) + 1;
        thought.status = retries >= MAX_RETRIES ? Status.FAILED : Status.PENDING; // Revert to PENDING for retry
        thought.metadata.error = errorInfo.substring(0, 250); // Limit error length
        thought.metadata.retries = retries;
        thought.belief.update(false); // Update thought belief

        if (rule) {
            // Don't update rule belief here, it's updated after executeAction returns
            thought.metadata.ruleId = rule.id; // Record the rule that failed
        }

        this.thoughts.update(thought);
        console.warn(chalk.yellow(`Thought ${shortId(thought.id)} failed (Attempt ${retries}): ${errorInfo}`));
    }

    private handleSuccess(thought: Thought, rule: Rule | null): void {
        thought.status = Status.DONE;
        thought.belief.update(true);
        delete thought.metadata.error; // Clear error on success
        delete thought.metadata.retries; // Clear retries

        if (rule) {
            // Don't update rule belief here, it's updated after executeAction returns
            thought.metadata.ruleId = rule.id; // Record successful rule
        }

        this.thoughts.update(thought);
        // console.log(chalk.green(`Thought ${shortId(thought.id)} completed successfully.`));
    }

    // --- Processing Loop ---
    async processSingleThought(): Promise<boolean> {
        const thought = this.sampleThought();
        if (!thought) return false; // No pending thoughts

        if (this.activeIds.has(thought.id)) return false; // Already being processed

        this.activeIds.add(thought.id);
        thought.status = Status.ACTIVE;
        thought.metadata.agentId = 'worker'; // Mark who is processing
        this.thoughts.update(thought);

        let success = false;
        try {
            const match = this.findAndSelectRule(thought);
            if (match) {
                success = await this.executeAction(thought, match.rule, match.bindings);
            } else {
                await this.handleNoRuleMatch(thought);
                // Check status after fallback, success if DONE
                success = this.thoughts.get(thought.id)?.status === Status.DONE;
            }
        } catch (error: any) {
            console.error(chalk.red(`Critical error processing thought ${shortId(thought.id)}:`), error);
            this.handleFailure(thought, null, `Unhandled processing exception: ${error.message}`);
            success = false;
        } finally {
            this.activeIds.delete(thought.id);
            // Ensure thought isn't stuck in ACTIVE state if something went wrong
            const finalThoughtState = this.thoughts.get(thought.id);
            if (finalThoughtState?.status === Status.ACTIVE) {
                console.warn(chalk.yellow(`Thought ${shortId(thought.id)} ended processing while still ACTIVE. Setting to FAILED.`));
                this.handleFailure(finalThoughtState, null, "Processing ended unexpectedly while ACTIVE.");
            }
        }
        return success;
    }

    async processBatch(): Promise<number> {
        let processedCount = 0;
        const promises: Promise<boolean>[] = [];

        // Select thoughts up to concurrency limit
        while (this.activeIds.size < this.maxConcurrent) {
            const thought = this.sampleThought();
            if (!thought) break; // No more eligible thoughts

            // Add thought processing promise to the batch
            this.activeIds.add(thought.id); // Mark as active *before* async operation starts
            promises.push(
                (async () => {
                    thought.status = Status.ACTIVE;
                    thought.metadata.agentId = 'worker_batch';
                    this.thoughts.update(thought);
                    let success = false;
                    try {
                        const match = this.findAndSelectRule(thought);
                        if (match) {
                            success = await this.executeAction(thought, match.rule, match.bindings);
                        } else {
                            await this.handleNoRuleMatch(thought);
                            success = this.thoughts.get(thought.id)?.status === Status.DONE;
                        }
                    } catch (error: any) {
                        console.error(chalk.red(`Critical error processing thought ${shortId(thought.id)} in batch:`), error);
                        this.handleFailure(thought, null, `Unhandled batch processing exception: ${error.message}`);
                        success = false;
                    } finally {
                        this.activeIds.delete(thought.id); // Release the slot
                        const finalThoughtState = this.thoughts.get(thought.id);
                        if (finalThoughtState?.status === Status.ACTIVE) {
                            console.warn(chalk.yellow(`Thought ${shortId(thought.id)} ended batch processing while still ACTIVE. Setting to FAILED.`));
                            this.handleFailure(finalThoughtState, null, "Batch processing ended unexpectedly while ACTIVE.");
                        }
                    }
                    return success;
                })()
            );

            // Stop adding more if batch size is reached
            if (promises.length >= this.batchSize) break;
        }

        if (promises.length === 0) return 0; // Nothing to process

        // Wait for all promises in the batch to settle
        const results = await Promise.all(promises);
        processedCount = results.length; // Count how many were attempted

        // Return the number of thoughts that successfully reached 'DONE' state
        return results.filter(success => success).length;
    }

    // --- User Interaction Handling ---
    async handlePromptResponse(promptId: string, responseText: string): Promise<boolean> {
        // Find the original USER_PROMPT thought
        const promptThought = this.thoughts.getAll().find(t =>
            t.type === Type.USER_PROMPT && t.metadata.uiContext?.promptId === promptId
        );

        if (!promptThought) {
            console.error(chalk.red(`Prompt thought for ID ${shortId(promptId)} not found.`));
            return false;
        }

        // Find the thought that was WAITING for this prompt
        const waitingThought = this.thoughts.getAll().find(t =>
            t.metadata.waitingFor === promptId && t.status === Status.WAITING
        );

        if (!waitingThought) {
            console.warn(chalk.yellow(`No thought found waiting for prompt ${shortId(promptId)}.`));
            // Mark prompt as done anyway? Or leave pending? Let's mark done.
            promptThought.status = Status.DONE;
            this.thoughts.update(promptThought);
            return false;
        }

        // Create a new INPUT thought representing the user's response
        const responseThought: Thought = {
            id: generateId(),
            type: Type.INPUT,
            content: TermLogic.Atom(responseText), // Store response as Atom
            belief: new Belief(1, 0), // User input is initially trusted
            status: Status.PENDING, // Ready for processing
            metadata: {
                rootId: waitingThought.metadata.rootId ?? waitingThought.id,
                parentId: waitingThought.id, // Response is child of the waiting thought
                created: new Date().toISOString(),
                responseTo: promptId, // Link back to the prompt ID
                tags: ['user_response'],
                provenance: 'user_input'
            }
        };
        this.addThought(responseThought);

        // Mark the original prompt thought as DONE
        promptThought.status = Status.DONE;
        this.thoughts.update(promptThought);

        // Set the waiting thought back to PENDING so it can be processed again
        waitingThought.status = Status.PENDING;
        delete waitingThought.metadata.waitingFor; // Remove waiting flag
        waitingThought.belief.update(true); // Assume waiting was resolved successfully
        this.thoughts.update(waitingThought);

        console.log(chalk.blue(`Response for prompt ${shortId(promptId)} received. Thought ${shortId(waitingThought.id)} is now PENDING.`));
        return true;
    }
}

// --- Persistence ---
async function saveState(thoughts: ThoughtStore, rules: RuleStore, memory: MemoryStore): Promise<void> {
    try {
        await fs.mkdir(DATA_DIR, { recursive: true });
        const state: AppState = {
            thoughts: thoughts.toJSON(),
            rules: rules.toJSON()
        };
        await fs.writeFile(STATE_FILE, JSON.stringify(state, null, 2));
        await memory.save(); // Save vector store index
        console.log(chalk.gray(`System state saved.`));
    } catch (error) {
        console.error(chalk.red('Error saving state:'), error);
    }
}

const debouncedSaveState = debounce(saveState, SAVE_DEBOUNCE);

async function loadState(thoughts: ThoughtStore, rules: RuleStore, memory: MemoryStore): Promise<void> {
    try {
        // Ensure directory exists before trying to read/initialize memory
        await fs.mkdir(DATA_DIR, { recursive: true });

        // Initialize memory store first (loads or creates vector store)
        await memory.initialize();

        // Then load thoughts and rules from JSON state file
        if (fsSync.existsSync(STATE_FILE)) {
            const data = await fs.readFile(STATE_FILE, 'utf-8');
            const state: AppState = safeJsonParse(data, { thoughts: {}, rules: {} });
            thoughts.loadJSON(state.thoughts);
            rules.loadJSON(state.rules);
            console.log(chalk.blue(`Loaded ${thoughts.count()} thoughts and ${rules.count()} rules from ${STATE_FILE}`));
        } else {
            console.log(chalk.yellow(`State file ${STATE_FILE} not found. Starting fresh.`));
        }
    } catch (error) {
        console.error(chalk.red('Error loading state:'), error);
        // Attempt to initialize memory even if state loading fails
        if (!memory.isReady) {
            await memory.initialize();
        }
    }
}


// --- Console UI (Readline + Chalk) ---
class ConsoleUI {
    private rl: readline.Interface;
    private refreshIntervalId: NodeJS.Timeout | null = null;
    private lastRenderHeight = 0;
    private isPaused = false;
    private currentRootView: string | null = null; // Track focused root thought

    constructor(
        private thoughts: ThoughtStore,
        private rules: RuleStore, // Added for 'rules' command
        private systemControl: (command: string, args?: string[]) => Promise<void> // Make async
    ) {
        this.rl = readline.createInterface({
            input: process.stdin,
            output: process.stdout,
            prompt: chalk.blueBright('FlowMind> '),
            completer: this.completer.bind(this) // Add basic completer
        });

        // Listen for store changes to trigger re-render
        thoughts.addChangeListener(() => this.render());
        rules.addChangeListener(() => this.render()); // Re-render if rules change
    }

    start(): void {
        console.log(chalk.cyan('Starting FlowMind Console UI...'));
        process.stdout.write('\x1b]0;FlowMind Console\x07'); // Set terminal title

        this.rl.on('line', async (line) => {
            const trimmedLine = line.trim();
            if (trimmedLine) {
                const [command, ...args] = trimmedLine.split(' ');
                await this.systemControl(command.toLowerCase(), args); // Await command handling
            }
            // Always re-prompt after handling a line or if the line was empty
            if (!this.rl.closed) {
                this.rl.prompt();
            }
        });

        this.rl.on('SIGINT', () => {
            this.rl.question(chalk.yellow('Exit FlowMind? (y/N) '), (answer) => {
                if (answer.match(/^y(es)?$/i)) {
                    this.systemControl('quit');
                } else {
                    this.rl.prompt();
                }
            });
        });

        this.rl.on('close', () => {
            console.log(chalk.cyan('\nFlowMind Console UI stopped.'));
            if (this.refreshIntervalId) clearInterval(this.refreshIntervalId);
        });

        // Initial render and start refresh loop
        this.render();
        this.refreshIntervalId = setInterval(() => this.render(), UI_REFRESH_INTERVAL);
        this.rl.prompt();
    }

    stop(): void {
        if (this.refreshIntervalId) clearInterval(this.refreshIntervalId);
        this.refreshIntervalId = null;
        if (!this.rl.closed) {
            this.rl.close();
        }
    }

    setPaused(paused: boolean): void {
        this.isPaused = paused;
        this.render(); // Update UI immediately
    }

    // --- Rendering Logic ---
    private getStatusIndicator(status: Status): string {
        switch (status) {
            case Status.PENDING: return chalk.yellow('⏳');
            case Status.ACTIVE: return chalk.blueBright('▶️');
            case Status.WAITING: return chalk.magenta('⏸️');
            case Status.DONE: return chalk.green('✅');
            case Status.FAILED: return chalk.red('❌');
            default: return '?';
        }
    }

    private formatThoughtLine(thought: Thought, indent = 0): string {
        const prefix = ' '.repeat(indent);
        const statusIcon = this.getStatusIndicator(thought.status);
        const idStr = chalk.gray(shortId(thought.id));
        const typeStr = chalk.dim(thought.type.padEnd(8));
        const beliefScore = thought.belief.score().toFixed(2);
        const beliefStr = chalk.dim(`B:${beliefScore}`);
        const contentStr = TermLogic.format(thought.content, 'short');
        const errorStr = thought.metadata.error ? chalk.red(` ERR!`) : '';
        const waitingStr = thought.status === Status.WAITING && thought.metadata.waitingFor
            ? chalk.magenta(` W:${shortId(thought.metadata.waitingFor)}`)
            : '';
        const tagsStr = thought.metadata.tags?.length
            ? chalk.cyan(` #${thought.metadata.tags.join('#')}`)
            : '';

        return `${prefix}${statusIcon} ${idStr} ${typeStr} ${beliefStr}${waitingStr}${errorStr} ${contentStr}${tagsStr}`;
    }

    private render(): void {
        if (this.rl.closed) return;

        const termWidth = process.stdout.columns || 80;
        let output = "";

        // --- Header ---
        const title = ` FlowMind Console ${this.isPaused ? chalk.yellow('[PAUSED]') : chalk.green('[RUNNING]')}`;
        const viewMode = this.currentRootView ? chalk.cyan(` [View: ${shortId(this.currentRootView)}]`) : '';
        output += chalk.bold.inverse("=".repeat(termWidth)) + "\n";
        output += chalk.bold.inverse(title + viewMode + " ".repeat(termWidth - title.length - viewMode.length)) + "\n";
        output += chalk.bold.inverse("=".repeat(termWidth)) + "\n";

        // --- Thoughts View ---
        const allThoughts = this.thoughts.getAll();
        const thoughtsToDisplay = this.currentRootView
            ? this.thoughts.getAllByRootId(this.currentRootView)
            : allThoughts.filter(t => !t.metadata.parentId); // Show only roots if no view selected

        const rootThoughts = thoughtsToDisplay.filter(t => t.id === this.currentRootView || !t.metadata.parentId);
        const thoughtMap = new Map(allThoughts.map(t => [t.id, t]));
        const childrenMap = new Map<string, Thought[]>();
        allThoughts.forEach(t => {
            if (t.metadata.parentId) {
                const list = childrenMap.get(t.metadata.parentId) || [];
                list.push(t);
                childrenMap.set(t.metadata.parentId, list);
            }
        });

        const renderChildren = (parentId: string, indent: number) => {
            const children = (childrenMap.get(parentId) || [])
                .sort((a, b) => Date.parse(a.metadata.created ?? '0') - Date.parse(b.metadata.created ?? '0'));
            children.forEach(child => {
                // Only render children if we are in a specific view or showing all roots
                if (this.currentRootView || !child.metadata.parentId) {
                    output += this.formatThoughtLine(child, indent) + "\n";
                    if (child.metadata.error) {
                        output += ' '.repeat(indent + 2) + chalk.red(`└─ Error: ${child.metadata.error}`) + "\n";
                    }
                    renderChildren(child.id, indent + 2); // Recursive call
                }
            });
        };

        if (rootThoughts.length > 0) {
            output += chalk.bold("--- Thoughts ---\n");
            rootThoughts
                .sort((a, b) => Date.parse(a.metadata.created ?? '0') - Date.parse(b.metadata.created ?? '0'))
                .forEach(root => {
                    output += this.formatThoughtLine(root, 0) + "\n";
                    if (root.metadata.error) {
                        output += ' '.repeat(2) + chalk.red(`└─ Error: ${root.metadata.error}`) + "\n";
                    }
                    renderChildren(root.id, 2);
                    output += chalk.grey("-".repeat(termWidth / 2)) + "\n"; // Separator between roots
                });
        } else if (!this.currentRootView) {
            output += chalk.grey("No top-level thoughts. Use 'add <note>' to begin.\n");
        } else {
            output += chalk.grey(`Thought ${shortId(this.currentRootView)} not found or has no children.\n`);
        }


        // --- Pending Prompts ---
        const pendingPrompts = allThoughts.filter(t => t.type === Type.USER_PROMPT && t.status === Status.PENDING && t.metadata.uiContext?.promptId);
        if (pendingPrompts.length > 0) {
            output += chalk.bold.yellow("\n--- Pending Prompts ---\n");
            pendingPrompts.forEach(p => {
                const promptId = p.metadata.uiContext?.promptId ?? 'unknown_id';
                const triggerId = p.metadata.parentId;
                output += `${chalk.yellow('❓')} [${chalk.bold(shortId(promptId))}] ${TermLogic.format(p.content)} ${chalk.grey(`(for ${shortId(triggerId)})`)}\n`;
            });
        }

        // --- Footer / Help ---
        output += chalk.inverse("=".repeat(termWidth)) + "\n";
        output += chalk.dim("Commands: add, respond, run, pause, step, rules, tools, info, view, clear, save, quit, tag, untag, search, delete, help") + "\n";

        // --- Render to Console ---
        const outputLines = output.split('\n').length;

        readline.cursorTo(process.stdout, 0, 0); // Move cursor to top-left
        readline.clearScreenDown(process.stdout); // Clear screen from cursor down
        process.stdout.write(output); // Write the new content

        // Restore prompt
        this.rl.prompt(true); // Pass true to preserve user input

        this.lastRenderHeight = outputLines; // Store height for potential future use (less critical now)
    }

    // --- Autocompletion ---
    private completer(line: string): [string[], string] {
        const commands = ['add', 'respond', 'run', 'pause', 'step', 'rules', 'tools', 'info', 'view', 'clear', 'save', 'quit', 'tag', 'untag', 'search', 'delete', 'help'];
        const thoughts = this.thoughts.getAll().map(t => shortId(t.id));
        const rules = this.rules.getAll().map(r => shortId(r.id));
        const prompts = this.thoughts.getAll()
            .filter(t => t.type === Type.USER_PROMPT && t.status === Status.PENDING && t.metadata.uiContext?.promptId)
            .map(t => shortId(t.metadata.uiContext!.promptId!));

        const parts = line.trimStart().split(' ');
        const currentPart = parts[parts.length - 1];
        const command = parts[0].toLowerCase();

        let hits: string[] = [];

        if (parts.length <= 1) {
            // Complete command itself
            hits = commands.filter((c) => c.startsWith(currentPart));
        } else {
            // Complete arguments based on command
            switch (command) {
                case 'info':
                case 'view':
                case 'delete':
                case 'tag': // First arg is thought ID
                case 'untag': // First arg is thought ID
                    if (parts.length === 2) {
                        hits = thoughts.filter((id) => id.startsWith(currentPart));
                    }
                    // Could add tag completion for 'tag'/'untag' second arg later
                    break;
                case 'respond': // First arg is prompt ID
                    if (parts.length === 2) {
                        hits = prompts.filter((id) => id.startsWith(currentPart));
                    }
                    break;
                // Add more command-specific completions here
            }
        }

        return [hits, currentPart]; // Return completions and the part being completed
    }

    // --- Helper to switch view ---
    switchView(rootIdPrefix: string | null): void {
        if (rootIdPrefix === null || rootIdPrefix.toLowerCase() === 'all' || rootIdPrefix.toLowerCase() === 'root') {
            this.currentRootView = null;
            console.log(chalk.blue("Switched view to show all root thoughts."));
        } else {
            const targetThought = this.thoughts.findThought(rootIdPrefix);
            if (targetThought) {
                // Find the ultimate root of this thought
                let root = targetThought;
                let safety = 0;
                while (root.metadata.parentId && safety < 20) {
                    const parent = this.thoughts.get(root.metadata.parentId);
                    if (!parent) break;
                    root = parent;
                    safety++;
                }
                this.currentRootView = root.id;
                console.log(chalk.blue(`Switched view to root thought ${shortId(this.currentRootView)}.`));
            } else {
                console.log(chalk.red(`Cannot find thought starting with ID "${rootIdPrefix}".`));
            }
        }
        this.render(); // Re-render immediately
    }
}


// --- Main System Class ---
class FlowMindSystem {
    private thoughts = new ThoughtStore();
    private rules = new RuleStore();
    private llm = new LLMService();
    private memory = new MemoryStore(this.llm.embeddings, VECTOR_STORE_DIR);
    private tools = new ToolRegistry();
    private engine = new Engine(this.thoughts, this.rules, this.memory, this.llm, this.tools);
    private ui = new ConsoleUI(this.thoughts, this.rules, this.handleCommand.bind(this));
    private isRunning = false; // Start paused by default
    private workerIntervalId: NodeJS.Timeout | null = null;

    constructor() {
        this.registerCoreTools();
    }

    private registerCoreTools(): void {
        [
            new LLMTool(),
            new MemoryTool(),
            new UserInteractionTool(),
            new GoalProposalTool(),
            new CoreTool()
        ].forEach(tool => this.tools.register(tool));
    }

    private bootstrapRules(): void {
        if (this.rules.count() > 0) return; // Don't add if rules exist

        console.log(chalk.blue("Bootstrapping default rules..."));

        const defaultRules: Omit<Rule, 'belief' | 'metadata' | 'id'>[] = [
            // INPUT -> GOAL (using LLM)
            {
                pattern: TermLogic.Structure("INPUT", [TermLogic.Variable("Content")]),
                action: TermLogic.Structure("LLMTool", [
                    TermLogic.Atom("generate"),
                    TermLogic.Atom("Given the input: \"?Content\". Define a specific, actionable GOAL. Output only the goal text.")
                ])
            },
            // GOAL -> STRATEGY (using LLM)
            {
                pattern: TermLogic.Structure("GOAL", [TermLogic.Variable("Content")]),
                action: TermLogic.Structure("LLMTool", [
                    TermLogic.Atom("generate"),
                    TermLogic.Atom("Given the goal: \"?Content\". Outline 1-3 concise STRATEGY steps. Output only the steps, one per line.")
                ])
            },
            // STRATEGY -> OUTCOME (using LLM)
            {
                pattern: TermLogic.Structure("STRATEGY", [TermLogic.Variable("Content")]),
                action: TermLogic.Structure("LLMTool", [
                    TermLogic.Atom("generate"),
                    TermLogic.Atom("The strategy step \"?Content\" was performed. Summarize a likely OUTCOME. Output only the outcome text.")
                ])
            },
            // OUTCOME -> Store in Memory
            {
                pattern: TermLogic.Structure("OUTCOME", [TermLogic.Variable("Content")]),
                action: TermLogic.Structure("MemoryTool", [
                    TermLogic.Atom("add"),
                    TermLogic.Variable("Content")
                ])
            },
            // After storing outcome, maybe suggest next goal?
            {
                pattern: TermLogic.Structure("OUTCOME", [TermLogic.Variable("Content")]), // Trigger on outcome
                action: TermLogic.Structure("GoalProposalTool", [
                    TermLogic.Atom("suggest"),
                    TermLogic.Variable("Content") // Use outcome as context
                ])
            },
            // Basic rule to handle LLM output and create the corresponding thought type
            // This requires the LLMTool to return the intended type somehow, or make assumptions.
            // Let's assume the LLM output from the above rules needs to be wrapped.
            // Example: If LLMTool returns "Achieve X", this rule turns it into GOAL(Achieve X)
            // This might be overly complex; let's rely on the fallback handler for now.
        ];

        defaultRules.forEach((r, i) => {
            // Extract type from pattern if possible for description
            const type = r.pattern.kind === 'Structure' ? r.pattern.name : 'Generic';
            const description = `Default rule ${i + 1}: Trigger on ${type}`;

            this.rules.add({
                id: generateId(),
                ...r,
                belief: Belief.DEFAULT,
                metadata: {
                    description: description,
                    provenance: 'bootstrap',
                    created: new Date().toISOString()
                }
            });
        });
        console.log(chalk.green(`${this.rules.count()} default rules added.`));
    }

    async initialize(): Promise<void> {
        console.log(chalk.cyan('Initializing FlowMind System...'));
        await loadState(this.thoughts, this.rules, this.memory);
        this.bootstrapRules(); // Add default rules if none loaded
        this.ui.start();
        this.ui.setPaused(!this.isRunning); // Reflect initial state in UI
        if (this.isRunning) {
            this.startProcessingLoop();
        }
        console.log(chalk.green('FlowMind System Initialized.'));
        console.log(chalk.yellow("System is initially PAUSED. Use 'run' to start processing."));
    }

    private startProcessingLoop(): void {
        if (this.workerIntervalId) return; // Already running
        console.log(chalk.green('Starting processing loop...'));
        this.isRunning = true;
        this.ui.setPaused(false);
        this.workerIntervalId = setInterval(async () => {
            if (this.isRunning) {
                try {
                    const processedCount = await this.engine.processBatch();
                    if (processedCount > 0) {
                        // Save state after successful processing
                        debouncedSaveState(this.thoughts, this.rules, this.memory);
                    }
                } catch (error) {
                    console.error(chalk.red('Error in processing loop:'), error);
                    // Optionally pause on error:
                    // this.pauseProcessingLoop();
                }
            }
        }, WORKER_INTERVAL);
    }

    private pauseProcessingLoop(): void {
        if (!this.workerIntervalId) return; // Already paused
        console.log(chalk.yellow('Pausing processing loop...'));
        this.isRunning = false;
        this.ui.setPaused(true);
        if (this.workerIntervalId) {
            clearInterval(this.workerIntervalId);
            this.workerIntervalId = null;
        }
        // Ensure any pending save is flushed
        debouncedSaveState.cancel();
        saveState(this.thoughts, this.rules, this.memory);
    }

    // --- Command Handling ---
    private async handleCommand(command: string, args: string[] = []): Promise<void> {
        try { // Wrap command handling in try/catch
            switch (command) {
                case 'add': {
                    const contentText = args.join(' ');
                    if (!contentText) {
                        console.log(chalk.yellow("Usage: add <note text>"));
                        break;
                    }
                    const newThought: Thought = {
                        id: generateId(),
                        type: Type.INPUT, // User additions start as INPUT
                        content: TermLogic.Atom(contentText),
                        belief: new Belief(1, 0), // High initial belief for user input
                        status: Status.PENDING,
                        metadata: {
                            created: new Date().toISOString(),
                            rootId: generateId(), // New root thought
                            tags: ['user_added'],
                            provenance: 'user_command'
                        }
                    };
                    newThought.metadata.rootId = newThought.id; // Root ID is its own ID
                    this.engine.addThought(newThought);
                    console.log(chalk.green(`Added thought: ${shortId(newThought.id)}`));
                    debouncedSaveState(this.thoughts, this.rules, this.memory);
                    break;
                }
                case 'respond': {
                    const promptIdPrefix = args[0];
                    const responseText = args.slice(1).join(' ');
                    if (!promptIdPrefix || !responseText) {
                        console.log(chalk.yellow("Usage: respond <prompt_id_prefix> <response text>"));
                        break;
                    }
                    // Find the full prompt ID
                    const fullPromptId = this.thoughts.getAll()
                        .map(t => t.metadata?.uiContext?.promptId)
                        .find(pid => pid && pid.startsWith(promptIdPrefix));

                    if (!fullPromptId) {
                        console.log(chalk.red(`Pending prompt ID starting with "${promptIdPrefix}" not found.`));
                        break;
                    }
                    await this.engine.handlePromptResponse(fullPromptId, responseText);
                    debouncedSaveState(this.thoughts, this.rules, this.memory);
                    break;
                }
                case 'run':
                    if (!this.isRunning) this.startProcessingLoop();
                    else console.log(chalk.yellow("Processing is already running."));
                    break;
                case 'pause':
                    if (this.isRunning) this.pauseProcessingLoop();
                    else console.log(chalk.yellow("Processing is already paused."));
                    break;
                case 'step':
                    if (!this.isRunning) {
                        console.log(chalk.blue("Processing single thought..."));
                        const processed = await this.engine.processSingleThought();
                        console.log(processed ? chalk.green("Step completed.") : chalk.yellow("No pending thoughts to process."));
                        if (processed) debouncedSaveState(this.thoughts, this.rules, this.memory);
                    } else {
                        console.log(chalk.yellow("System must be paused to step ('pause' command)."));
                    }
                    break;
                case 'save':
                    debouncedSaveState.cancel(); // Cancel any pending debounce
                    await saveState(this.thoughts, this.rules, this.memory); // Save immediately
                    console.log(chalk.blue("System state saved explicitly."));
                    break;
                case 'quit':
                case 'exit':
                    await this.shutdown();
                    break; // shutdown exits the process
                case 'clear':
                    console.clear(); // Use Node's console.clear
                    this.ui['render'](); // Force redraw after clear
                    break;
                case 'help':
                    this.printHelp();
                    break;
                case 'status':
                    this.printStatus();
                    break;
                case 'info': {
                    const idPrefix = args[0];
                    if (!idPrefix) {
                        console.log(chalk.yellow("Usage: info <thought_or_rule_id_prefix>"));
                        break;
                    }
                    const thought = this.thoughts.findThought(idPrefix);
                    const rule = this.rules.findRule(idPrefix);
                    if (thought) this.printThoughtInfo(thought);
                    else if (rule) this.printRuleInfo(rule);
                    else console.log(chalk.red(`No thought or rule found starting with ID "${idPrefix}".`));
                    break;
                }
                case 'view': {
                    const rootIdPrefix = args[0] || null; // null or 'all'/'root' resets view
                    this.ui.switchView(rootIdPrefix);
                    break;
                }
                case 'rules':
                    this.printRules();
                    break;
                case 'tools':
                    this.printTools();
                    break;
                case 'tag': {
                    const idPrefix = args[0];
                    const tag = args[1];
                    if (!idPrefix || !tag) {
                        console.log(chalk.yellow("Usage: tag <thought_id_prefix> <tag_name>"));
                        break;
                    }
                    const thought = this.thoughts.findThought(idPrefix);
                    if (!thought) {
                        console.log(chalk.red(`Thought starting with ID "${idPrefix}" not found.`));
                        break;
                    }
                    thought.metadata.tags = thought.metadata.tags || [];
                    if (!thought.metadata.tags.includes(tag)) {
                        thought.metadata.tags.push(tag);
                        this.thoughts.update(thought);
                        console.log(chalk.green(`Tagged thought ${shortId(thought.id)} with '#${tag}'.`));
                        debouncedSaveState(this.thoughts, this.rules, this.memory);
                    } else {
                        console.log(chalk.yellow(`Thought ${shortId(thought.id)} already has tag '#${tag}'.`));
                    }
                    break;
                }
                case 'untag': {
                    const idPrefix = args[0];
                    const tag = args[1];
                    if (!idPrefix || !tag) {
                        console.log(chalk.yellow("Usage: untag <thought_id_prefix> <tag_name>"));
                        break;
                    }
                    const thought = this.thoughts.findThought(idPrefix);
                    if (!thought) {
                        console.log(chalk.red(`Thought starting with ID "${idPrefix}" not found.`));
                        break;
                    }
                    if (thought.metadata.tags?.includes(tag)) {
                        thought.metadata.tags = thought.metadata.tags.filter(t => t !== tag);
                        this.thoughts.update(thought);
                        console.log(chalk.green(`Removed tag '#${tag}' from thought ${shortId(thought.id)}.`));
                        debouncedSaveState(this.thoughts, this.rules, this.memory);
                    } else {
                        console.log(chalk.yellow(`Thought ${shortId(thought.id)} does not have tag '#${tag}'.`));
                    }
                    break;
                }
                case 'search': {
                    const query = args.join(' ');
                    if (!query) {
                        console.log(chalk.yellow("Usage: search <query text>"));
                        break;
                    }
                    console.log(chalk.blue(`Searching memory for: "${query}"...`));
                    const results = await this.memory.search(query, 5);
                    if (results.length > 0) {
                        console.log(chalk.bold(`--- Search Results (${results.length}) ---`));
                        results.forEach(r => {
                            console.log(`[${chalk.gray(shortId(r.metadata.sourceId))}|${chalk.dim(r.metadata.type)}] ${r.content.substring(0, 80)}${r.content.length > 80 ? '...' : ''}`);
                        });
                    } else {
                        console.log(chalk.yellow("No relevant memories found."));
                    }
                    break;
                }
                case 'delete': {
                    const idPrefix = args[0];
                    if (!idPrefix) {
                        console.log(chalk.yellow("Usage: delete <thought_id_prefix>"));
                        break;
                    }
                    const thought = this.thoughts.findThought(idPrefix);
                    if (!thought) {
                        console.log(chalk.red(`Thought starting with ID "${idPrefix}" not found.`));
                        break;
                    }
                    // Basic confirmation for deletion
                    this.ui['rl'].question(chalk.yellow(`Really delete thought ${shortId(thought.id)}? (y/N) `), (answer) => {
                        if (answer.match(/^y(es)?$/i)) {
                            const deleted = this.thoughts.delete(thought.id);
                            if (deleted) {
                                console.log(chalk.green(`Thought ${shortId(thought.id)} deleted.`));
                                debouncedSaveState(this.thoughts, this.rules, this.memory);
                            } else {
                                console.log(chalk.red(`Failed to delete thought ${shortId(thought.id)}.`));
                            }
                        } else {
                            console.log(chalk.yellow("Deletion cancelled."));
                        }
                        this.ui['rl'].prompt(); // Re-prompt after question
                    });
                    return; // Don't re-prompt immediately, wait for question answer
                }
                default:
                    console.log(chalk.red(`Unknown command: ${command}. Try 'help'.`));
            }
        } catch (error: any) {
            console.error(chalk.red(`Error executing command "${command}":`), error);
        }
        // Re-prompt unless the command handles it (like delete confirmation or quit)
        if (command !== 'delete' && command !== 'quit' && command !== 'exit') {
            this.ui['rl']?.prompt();
        }
    }

    // --- Helper Print Functions ---
    private printHelp(): void {
        console.log(chalk.bold("\n--- FlowMind Help ---"));
        console.log(` ${chalk.cyan('add <text>')}         : Create a new top-level INPUT thought.`);
        console.log(` ${chalk.cyan('respond <id> <text>')} : Respond to a pending user prompt.`);
        console.log(` ${chalk.cyan('run')}                : Start/resume the automatic thought processing loop.`);
        console.log(` ${chalk.cyan('pause')}              : Pause the automatic thought processing loop.`);
        console.log(` ${chalk.cyan('step')}               : Process a single thought (only when paused).`);
        console.log(` ${chalk.cyan('view [id|all]')}      : Focus view on a specific thought tree or all roots.`);
        console.log(` ${chalk.cyan('info <id>')}          : Show detailed information for a thought or rule.`);
        console.log(` ${chalk.cyan('rules')}              : List all loaded rules.`);
        console.log(` ${chalk.cyan('tools')}              : List all registered tools.`);
        console.log(` ${chalk.cyan('tag <id> <tag>')}     : Add a tag to a thought.`);
        console.log(` ${chalk.cyan('untag <id> <tag>')}   : Remove a tag from a thought.`);
        console.log(` ${chalk.cyan('search <query>')}     : Search the vector memory store.`);
        console.log(` ${chalk.cyan('delete <id>')}        : Delete a thought (use with caution!).`);
        console.log(` ${chalk.cyan('save')}               : Save the current state immediately.`);
        console.log(` ${chalk.cyan('clear')}              : Clear the console screen.`);
        console.log(` ${chalk.cyan('help')}               : Show this help message.`);
        console.log(` ${chalk.cyan('quit | exit')}        : Save state and exit FlowMind.`);
        console.log(chalk.grey(" (IDs can usually be prefixes)"));
    }

    private printStatus(): void {
        const counts = Object.values(Status).reduce((acc, status) => {
            acc[status] = 0;
            return acc;
        }, {} as Record<Status, number>);
        this.thoughts.getAll().forEach(t => counts[t.status]++);

        console.log(chalk.bold("\n--- System Status ---"));
        console.log(` Processing: ${this.isRunning ? chalk.green('RUNNING') : chalk.yellow('PAUSED')}`);
        console.log(` Thoughts  : ${chalk.cyan(this.thoughts.count())} total`);
        Object.entries(counts).forEach(([status, count]) => {
            if (count > 0) console.log(`   - ${status.padEnd(7)}: ${chalk.yellow(count)}`);
        });
        console.log(` Rules     : ${chalk.cyan(this.rules.count())}`);
        console.log(` View Mode : ${this.ui['currentRootView'] ? `Focused on ${shortId(this.ui['currentRootView'])}` : 'Root View'}`);
        // Add Memory Store status if available/relevant
    }

    private printThoughtInfo(thought: Thought): void {
        console.log(chalk.bold.underline(`\n--- Thought Info: ${shortId(thought.id)} ---`));
        console.log(` Full ID : ${chalk.gray(thought.id)}`);
        console.log(` Type    : ${chalk.cyan(thought.type)}`);
        console.log(` Status  : ${this.ui['getStatusIndicator'](thought.status)} ${thought.status}`);
        console.log(` Belief  : ${chalk.yellow(thought.belief.score().toFixed(3))} (Pos: ${thought.belief.pos}, Neg: ${thought.belief.neg})`);
        console.log(` Content : ${TermLogic.format(thought.content, 'full')}`); // Show full content
        console.log(chalk.bold(" Metadata:"));
        Object.entries(thought.metadata).forEach(([key, value]) => {
            if (value !== undefined && value !== null && (!Array.isArray(value) || value.length > 0)) {
                let displayValue = typeof value === 'object' ? JSON.stringify(value) : String(value);
                if (key === 'parentId' || key === 'rootId' || key === 'waitingFor' || key === 'responseTo' || key === 'ruleId') {
                    displayValue = shortId(displayValue); // Shorten IDs in metadata display
                }
                console.log(`  ${key.padEnd(12)}: ${chalk.dim(displayValue)}`);
            }
        });
    }

    private printRuleInfo(rule: Rule): void {
        console.log(chalk.bold.underline(`\n--- Rule Info: ${shortId(rule.id)} ---`));
        console.log(` Full ID     : ${chalk.gray(rule.id)}`);
        console.log(` Description : ${chalk.cyan(rule.metadata.description || 'N/A')}`);
        console.log(` Belief      : ${chalk.yellow(rule.belief.score().toFixed(3))} (Pos: ${rule.belief.pos}, Neg: ${rule.belief.neg})`);
        console.log(` Pattern     : ${TermLogic.format(rule.pattern, 'full')}`);
        console.log(` Action      : ${TermLogic.format(rule.action, 'full')}`);
        console.log(chalk.bold(" Metadata:"));
        Object.entries(rule.metadata).forEach(([key, value]) => {
            if (value !== undefined && value !== null && key !== 'description') { // Description shown above
                console.log(`  ${key.padEnd(12)}: ${chalk.dim(String(value))}`);
            }
        });
    }

    private printRules(): void {
        const allRules = this.rules.getAll();
        console.log(chalk.bold(`\n--- Rules (${allRules.length}) ---`));
        if (allRules.length === 0) {
            console.log(chalk.grey("No rules loaded."));
            return;
        }
        allRules
            .sort((a, b) => a.metadata.description?.localeCompare(b.metadata.description ?? '') ?? 0)
            .forEach(rule => {
                const idStr = chalk.gray(shortId(rule.id));
                const beliefStr = chalk.yellow(rule.belief.score().toFixed(2));
                const desc = rule.metadata.description || `Rule ${idStr}`;
                console.log(` ${idStr} [B:${beliefStr}] ${chalk.cyan(desc)}`);
                console.log(`   P: ${TermLogic.format(rule.pattern)}`);
                console.log(`   A: ${TermLogic.format(rule.action)}`);
            });
    }

    private printTools(): void {
        const allTools = this.tools.list();
        console.log(chalk.bold(`\n--- Tools (${allTools.length}) ---`));
        if (allTools.length === 0) {
            console.log(chalk.grey("No tools registered."));
            return;
        }
        allTools
            .sort((a, b) => a.name.localeCompare(b.name))
            .forEach(tool => {
                console.log(` ${chalk.cyan.bold(tool.name)}: ${tool.description}`);
            });
    }


    async shutdown(): Promise<void> {
        console.log(chalk.cyan('\nShutting down FlowMind System...'));
        this.pauseProcessingLoop(); // Pause loop and save state
        this.ui.stop();
        // Final save just in case
        debouncedSaveState.cancel();
        await saveState(this.thoughts, this.rules, this.memory);
        console.log(chalk.green('FlowMind shutdown complete. Goodbye!'));
        process.exit(0);
    }
}

// --- Main Execution ---
async function main() {
    const system = new FlowMindSystem();

    // Graceful shutdown handlers
    process.on('SIGINT', async () => {
        console.log(chalk.yellow('\nSIGINT received. Shutting down...'));
        await system.shutdown();
    });
    process.on('SIGTERM', async () => {
        console.log(chalk.yellow('SIGTERM received. Shutting down...'));
        await system.shutdown();
    });
    process.on('uncaughtException', async (error) => {
        console.error(chalk.red.bold('\n--- UNCAUGHT EXCEPTION ---'));
        console.error(error);
        console.error(chalk.red.bold('--------------------------'));
        // Attempt graceful shutdown, but might fail
        await system.shutdown();
        process.exit(1);
    });
    process.on('unhandledRejection', async (reason, promise) => {
        console.error(chalk.red.bold('\n--- UNHANDLED REJECTION ---'));
        console.error('Reason:', reason);
        // console.error('Promise:', promise); // Might be verbose
        console.error(chalk.red.bold('---------------------------'));
        // Attempt graceful shutdown
        await system.shutdown();
        process.exit(1);
    });


    try {
        await system.initialize();
        // Initialization starts the UI and potentially the processing loop
        // The program will now run based on intervals and user input
    } catch (error) {
        console.error(chalk.red.bold("Critical error during system initialization:"), error);
        process.exit(1);
    }
}

// Execute main function
main();