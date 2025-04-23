import * as fs from 'fs/promises';
import * as fsSync from 'fs';
import * as path from 'path';
import * as os from 'os';
import * as readline from 'readline';
import { v4 as uuidv4 } from 'uuid';
import chalk from 'chalk';
import { WebSocket, WebSocketServer } from 'ws';
import { ChatOllama } from "@langchain/community/chat_models/ollama";
import { OllamaEmbeddings } from "@langchain/community/embeddings/ollama";
import { BaseChatModel } from "@langchain/core/language_models/chat_models";
import { Embeddings } from "@langchain/core/embeddings";
import { HumanMessage } from "@langchain/core/messages";
import { StringOutputParser } from "@langchain/core/output_parsers";
import { Document } from "@langchain/core/documents";
import { FaissStore } from "@langchain/community/vectorstores/faiss";

// --- Configuration ---
const DATA_DIR = path.join(os.homedir(), '.flowmind');
const STATE_FILE = path.join(DATA_DIR, 'flowmind_state.json');
const VECTOR_STORE_DIR = path.join(DATA_DIR, 'vector-store');
const OLLAMA_BASE_URL = process.env.OLLAMA_HOST || "http://localhost:11434";
const OLLAMA_MODEL = process.env.OLLAMA_MODEL || 'llamablit';
const OLLAMA_EMBEDDING_MODEL = process.env.OLLAMA_EMBED_MODEL || OLLAMA_MODEL;
const WORKER_INTERVAL = 2000;
const SAVE_DEBOUNCE = 5000;
const MAX_RETRIES = 2;
const DEFAULT_BELIEF_POS = 1.0;
const DEFAULT_BELIEF_NEG = 1.0;
const SHORT_ID_LEN = 6;
const WS_PORT = 8080;

// --- Types ---
enum Status { PENDING = 'PENDING', ACTIVE = 'ACTIVE', WAITING = 'WAITING', DONE = 'DONE', FAILED = 'FAILED' }
enum Type { INPUT = 'INPUT', GOAL = 'GOAL', STRATEGY = 'STRATEGY', OUTCOME = 'OUTCOME', QUERY = 'QUERY', USER_PROMPT = 'USER_PROMPT', SYSTEM = 'SYSTEM' }

type Term = Atom | Variable | Structure | ListTerm;
interface Atom { kind: 'Atom'; name: string; }
interface Variable { kind: 'Variable'; name: string; }
interface Structure { kind: 'Structure'; name: string; args: Term[]; }
interface ListTerm { kind: 'ListTerm'; elements: Term[]; }

interface BeliefData { pos: number; neg: number; }

interface Metadata {
    rootId?: string;
    parentId?: string;
    ruleId?: string;
    agentId?: string;
    created?: string;
    modified?: string;
    priority?: number;
    error?: string;
    promptId?: string; // Used for USER_PROMPT thoughts awaiting response
    tags?: string[];
    feedback?: any[];
    embedded?: string; // Timestamp when added to memory
    suggestions?: string[]; // Potential future use
    retries?: number;
    waitingFor?: string; // ID of thought this one depends on (e.g., a USER_PROMPT)
    responseTo?: string; // ID of prompt this thought is a response to
    provenance?: string; // Origin (e.g., tool name, 'user', 'llm_fallback')
    [key: string]: any; // Allow arbitrary metadata
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
        created?: string;
    };
}

interface MemoryEntry {
    id: string; // Unique ID for the memory entry itself
    embedding: number[]; // Vector embedding (not always loaded)
    content: string; // Text content
    metadata: { created: string; type: string; sourceId: string; }; // Source thought info
    score?: number; // Similarity score from search
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

// --- Utilities ---
const generateId = (): string => uuidv4();
const shortId = (id: string | undefined): string => id ? id.substring(0, SHORT_ID_LEN) : '------';

function debounce<T extends (...args: any[]) => any>(func: T, wait: number): T & { cancel: () => void; flush: () => void; } {
    let timeout: NodeJS.Timeout | null = null;
    let lastArgs: Parameters<T> | null = null;
    let lastThis: any = null;

    const debounced = (...args: Parameters<T>): void => {
        lastArgs = args;
        lastThis = this;
        if (timeout) clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };

    const later = () => {
        timeout = null;
        if (lastArgs) {
            func.apply(lastThis, lastArgs);
            lastArgs = null;
            lastThis = null;
        }
    };

    debounced.cancel = () => { if (timeout) { clearTimeout(timeout); timeout = null; lastArgs = null; lastThis = null; } };
    debounced.flush = () => { if (timeout) { later(); clearTimeout(timeout); } };

    return debounced as T & { cancel: () => void; flush: () => void; };
}

const safeJsonParse = <T>(json: string | null | undefined, defaultValue: T): T => {
    if (!json) return defaultValue;
    try { return JSON.parse(json); } catch { return defaultValue; }
};

const sleep = (ms: number): Promise<void> => new Promise(resolve => setTimeout(resolve, ms));

// --- Core Logic Classes ---

class Belief implements BeliefData {
    pos: number;
    neg: number;

    constructor(pos: number = DEFAULT_BELIEF_POS, neg: number = DEFAULT_BELIEF_NEG) {
        this.pos = pos;
        this.neg = neg;
    }

    score(): number { return (this.pos + 1) / (this.pos + this.neg + 2); } // Laplace smoothing
    update(success: boolean): void { success ? this.pos++ : this.neg++; }
    toJSON(): BeliefData { return { pos: this.pos, neg: this.neg }; }
    static fromJSON(data: BeliefData): Belief { return new Belief(data.pos, data.neg); }
    static DEFAULT = new Belief();
}

namespace TermLogic {
    export const Atom = (name: string): Atom => ({ kind: 'Atom', name });
    export const Variable = (name: string): Variable => ({ kind: 'Variable', name });
    export const Structure = (name: string, args: Term[]): Structure => ({ kind: 'Structure', name, args });
    export const List = (elements: Term[]): ListTerm => ({ kind: 'ListTerm', elements });

    export function format(term: Term | null | undefined, useColor: boolean = true): string {
        if (!term) return useColor ? chalk.grey('null') : 'null';
        const c = (color: chalk.ChalkFunction, text: string) => useColor ? color(text) : text;
        switch (term.kind) {
            case 'Atom': return c(chalk.green, term.name);
            case 'Variable': return c(chalk.cyan, `?${term.name}`);
            case 'Structure': return `${c(chalk.yellow, term.name)}(${term.args.map(t => format(t, useColor)).join(', ')})`;
            case 'ListTerm': return `[${term.elements.map(t => format(t, useColor)).join(', ')}]`;
            default: return c(chalk.red, 'invalid_term');
        }
    }

    export function toString(term: Term): string {
        switch (term.kind) {
            case 'Atom': return term.name;
            case 'Variable': return `?${term.name}`; // Keep prefix for clarity in logs/prompts
            case 'Structure': return `${term.name}(${term.args.map(toString).join(', ')})`; // Simplified representation
            case 'ListTerm': return `[${term.elements.map(toString).join(', ')}]`;
            default: return '';
        }
    }

    export function fromString(input: string): Term { // Basic heuristic parser
        input = input.trim();
        if (input.startsWith('?') && input.length > 1) return Variable(input.substring(1));
        if (input.endsWith(')') && input.includes('(')) {
            const name = input.substring(0, input.indexOf('('));
            const argsStr = input.substring(input.indexOf('(') + 1, input.length - 1);
            // This arg parsing is VERY basic, doesn't handle nested structures/lists/commas in atoms
            const args = argsStr ? argsStr.split(',').map(s => fromString(s.trim())) : [];
            return Structure(name, args);
        }
        if (input.startsWith('[') && input.endsWith(']')) {
            const elementsStr = input.substring(1, input.length - 1);
            const elements = elementsStr ? elementsStr.split(',').map(s => fromString(s.trim())) : [];
            return List(elements);
        }
        return Atom(input);
    }

    export function unify(term1: Term, term2: Term, bindings: Map<string, Term> = new Map()): Map<string, Term> | null {
        const resolve = (term: Term, currentBindings: Map<string, Term>): Term => {
            while (term.kind === 'Variable' && currentBindings.has(term.name)) {
                const bound = currentBindings.get(term.name)!;
                if (bound === term) return term; // Avoid infinite loop for self-binding (shouldn't happen)
                term = bound;
            }
            return term;
        };

        const t1 = resolve(term1, bindings);
        const t2 = resolve(term2, bindings);

        if (t1.kind === 'Variable') {
            if (t1.name === (t2 as Variable).name && t1.kind === t2.kind) return bindings; // Var matches same var
            // Occurs check (simplified): prevent binding ?X to structure containing ?X
            if (t2.kind === 'Structure' && JSON.stringify(t2).includes(`"${t1.name}"`)) return null;
            if (t2.kind === 'ListTerm' && JSON.stringify(t2).includes(`"${t1.name}"`)) return null;

            const newBindings = new Map(bindings); newBindings.set(t1.name, t2); return newBindings;
        }
        if (t2.kind === 'Variable') { // Symmetric case
            if (t1.kind === 'Structure' && JSON.stringify(t1).includes(`"${t2.name}"`)) return null;
            if (t1.kind === 'ListTerm' && JSON.stringify(t1).includes(`"${t2.name}"`)) return null;
            const newBindings = new Map(bindings); newBindings.set(t2.name, t1); return newBindings;
        }
        if (t1.kind !== t2.kind) return null;

        switch (t1.kind) {
            case 'Atom': return t1.name === (t2 as Atom).name ? bindings : null;
            case 'Structure': {
                const s2 = t2 as Structure;
                if (t1.name !== s2.name || t1.args.length !== s2.args.length) return null;
                let currentBindings = bindings;
                for (let i = 0; i < t1.args.length; i++) {
                    const result = unify(t1.args[i], s2.args[i], currentBindings);
                    if (!result) return null; currentBindings = result;
                }
                return currentBindings;
            }
            case 'ListTerm': {
                const l2 = t2 as ListTerm;
                if (t1.elements.length !== l2.elements.length) return null;
                let currentBindings = bindings;
                for (let i = 0; i < t1.elements.length; i++) {
                    const result = unify(t1.elements[i], l2.elements[i], currentBindings);
                    if (!result) return null; currentBindings = result;
                }
                return currentBindings;
            }
        }
        return null; // Should be unreachable
    }

    export function substitute(term: Term, bindings: Map<string, Term>): Term {
        if (term.kind === 'Variable' && bindings.has(term.name)) {
            // Recursively substitute in the bound term as well
            return substitute(bindings.get(term.name)!, bindings);
        }
        if (term.kind === 'Structure') {
            return { ...term, args: term.args.map(arg => substitute(arg, bindings)) };
        }
        if (term.kind === 'ListTerm') {
            return { ...term, elements: term.elements.map(el => substitute(el, bindings)) };
        }
        return term; // Atoms and unbound Variables return themselves
    }

    export function jsonToTerm(data: any): Term | null {
        if (data === null || typeof data !== 'object') return null;
        if (Array.isArray(data)) { // Assume array maps to ListTerm implicitly
            const elements = data.map(jsonToTerm).filter(t => t !== null) as Term[];
            return elements.length === data.length ? List(elements) : null;
        }
        const { kind, name, args, elements } = data;
        switch (kind) {
            case 'Atom': return typeof name === 'string' ? Atom(name) : null;
            case 'Variable': return typeof name === 'string' ? Variable(name) : null;
            case 'Structure':
                if (typeof name === 'string' && Array.isArray(args)) {
                    const convertedArgs = args.map(jsonToTerm).filter(t => t !== null) as Term[];
                    return convertedArgs.length === args.length ? Structure(name, convertedArgs) : null;
                } return null;
            case 'ListTerm':
                if (Array.isArray(elements)) {
                    const convertedElements = elements.map(jsonToTerm).filter(t => t !== null) as Term[];
                    return convertedElements.length === elements.length ? List(convertedElements) : null;
                } return null;
            default: // Attempt to convert arbitrary object { k1:v1, k2:v2 } to Structure("object", [Atom("k1:v1"), Atom("k2:v2")])
                const objArgs = Object.entries(data)
                    .map(([k, v]) => Atom(`${k}:${JSON.stringify(v)}`)); // Simple serialization
                return Structure("json_object", objArgs); // Fallback representation
        }
    }
}

abstract class BaseStore<T extends { id: string }> {
    protected items = new Map<string, T>();
    protected listeners: Set<() => void> = new Set();
    protected changedItems: Set<string> = new Set(); // Track changes for broadcasting
    protected deletedItems: Set<string> = new Set();

    add(item: T): void {
        this.items.set(item.id, item);
        this.changedItems.add(item.id);
        this.deletedItems.delete(item.id); // Ensure it's not marked deleted if re-added
        this.notifyChange();
    }
    get(id: string): T | undefined { return this.items.get(id); }

    update(item: T): void {
        if (this.items.has(item.id)) {
            const existing = this.items.get(item.id)!;
            // Preserve created time, only update modified
            const updatedItem = {
                ...item,
                metadata: { ...(existing as any).metadata, ...(item as any).metadata, modified: new Date().toISOString() }
            };
            this.items.set(item.id, updatedItem);
            this.changedItems.add(item.id);
            this.notifyChange();
        } else {
            this.add(item); // Treat update of non-existent as add
        }
    }

    delete(id: string): boolean {
        const deleted = this.items.delete(id);
        if (deleted) {
            this.changedItems.delete(id); // Remove from changed if deleted
            this.deletedItems.add(id);
            this.notifyChange();
        }
        return deleted;
    }

    getAll(): T[] { return Array.from(this.items.values()); }
    count(): number { return this.items.size; }
    findItemByPrefix(prefix: string): T | undefined {
        if (!prefix || prefix.length < 3) return undefined; // Require minimum prefix length
        if (this.items.has(prefix)) return this.items.get(prefix); // Check full ID first
        const matching = this.getAll().filter(item => item.id.startsWith(prefix));
        return matching.length === 1 ? matching[0] : undefined; // Only return if unique prefix match
    }

    addChangeListener(listener: () => void): void { this.listeners.add(listener); }
    removeChangeListener(listener: () => void): void { this.listeners.delete(listener); }

    // Get changes since last call and clear tracked changes
    getDelta(): { changed: T[], deleted: string[] } {
        const changed = Array.from(this.changedItems).map(id => this.items.get(id)).filter(item => !!item) as T[];
        const deleted = Array.from(this.deletedItems);
        this.changedItems.clear();
        this.deletedItems.clear();
        return { changed, deleted };
    }

    protected notifyChange(): void { this.listeners.forEach(listener => listener()); }

    abstract toJSON(): Record<string, any>;
    abstract loadJSON(data: Record<string, any>): void;
}

class ThoughtStore extends BaseStore<Thought> {
    getPending(): Thought[] { return this.getAll().filter(t => t.status === Status.PENDING); }
    getAllByRootId(rootId: string): Thought[] {
        const all = this.getAll();
        const root = this.get(rootId);
        if (!root) return [];
        const tree = new Set<string>([rootId]);
        let queue = [rootId];
        while (queue.length > 0) {
            const currentId = queue.shift()!;
            all.forEach(t => {
                if (t.metadata.parentId === currentId && !tree.has(t.id)) {
                    tree.add(t.id); queue.push(t.id);
                }
            });
        }
        return all.filter(t => tree.has(t.id))
            .sort((a, b) => Date.parse(a.metadata.created ?? '0') - Date.parse(b.metadata.created ?? '0'));
    }
    searchByTag(tag: string): Thought[] { return this.getAll().filter(t => t.metadata.tags?.includes(tag)); }
    findThought(idPrefix: string): Thought | undefined { return this.findItemByPrefix(idPrefix); }
    findPendingPrompt(promptId: string): Thought | undefined {
        return this.getAll().find(t => t.type === Type.USER_PROMPT && t.status === Status.PENDING && t.metadata.promptId === promptId);
    }
    findWaitingThought(promptId: string): Thought | undefined {
        return this.getAll().find(t => t.status === Status.WAITING && t.metadata.waitingFor === promptId);
    }

    toJSON(): Record<string, any> {
        const obj: Record<string, any> = {};
        this.items.forEach((thought, id) => obj[id] = { ...thought, belief: thought.belief.toJSON() });
        return obj;
    }

    loadJSON(data: Record<string, any>): void {
        this.items.clear();
        Object.entries(data).forEach(([id, thoughtData]) => {
            this.add({ ...thoughtData, belief: Belief.fromJSON(thoughtData.belief), id });
        });
        this.changedItems.clear(); // Clear changes after initial load
        this.deletedItems.clear();
    }
}

class RuleStore extends BaseStore<Rule> {
    searchByDescription(desc: string): Rule[] {
        const lowerDesc = desc.toLowerCase();
        return this.getAll().filter(r => r.metadata.description?.toLowerCase().includes(lowerDesc));
    }
    findRule(idPrefix: string): Rule | undefined { return this.findItemByPrefix(idPrefix); }

    toJSON(): Record<string, any> {
        const obj: Record<string, any> = {};
        this.items.forEach((rule, id) => obj[id] = { ...rule, belief: rule.belief.toJSON() });
        return obj;
    }

    loadJSON(data: Record<string, any>): void {
        this.items.clear();
        Object.entries(data).forEach(([id, ruleData]) => {
            this.add({ ...ruleData, belief: Belief.fromJSON(ruleData.belief), id });
        });
        this.changedItems.clear(); // Clear changes after initial load
        this.deletedItems.clear();
    }
}

class MemoryStore {
    private vectorStore: FaissStore | null = null;
    private embeddings: Embeddings;
    private storePath: string;
    public isReady = false;

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
                console.log(chalk.yellow(`Vector store not found at ${this.storePath}, initializing.`));
                try {
                    // FaissStore requires at least one document to initialize
                    const dummyDoc = new Document({ pageContent: "Initial sentinel document", metadata: { sourceId: "init", type: "SYSTEM", created: new Date().toISOString() } });
                    this.vectorStore = await FaissStore.fromDocuments([dummyDoc], this.embeddings);
                    await this.save(); // Save immediately after creation
                    this.isReady = true;
                    console.log(chalk.green(`New vector store created at ${this.storePath}`));
                } catch (initError) { console.error(chalk.red('Failed to initialize new vector store:'), initError); }
            } else { console.error(chalk.red('Failed to load vector store:'), error); }
        }
    }

    async add(entry: Omit<MemoryEntry, 'embedding' | 'score'>): Promise<void> {
        if (!this.vectorStore || !this.isReady) { console.warn(chalk.yellow('MemoryStore not ready, cannot add entry.')); return; }
        // Ensure metadata is serializable and add unique ID for vector DB doc itself
        const docMetadata = { ...entry.metadata, id: entry.id, _docId: generateId() };
        const doc = new Document({ pageContent: entry.content, metadata: docMetadata });
        try {
            await this.vectorStore.addDocuments([doc]);
            await this.save(); // Consider debouncing save if adds are frequent
        } catch (error) { console.error(chalk.red('Failed to add document to vector store:'), error); }
    }

    async search(query: string, k: number = 5): Promise<MemoryEntry[]> {
        if (!this.vectorStore || !this.isReady) { console.warn(chalk.yellow('MemoryStore not ready, cannot search.')); return []; }
        try {
            const results = await this.vectorStore.similaritySearchWithScore(query, k);
            // Filter out the sentinel document if it appears
            return results.filter(([doc]) => doc.metadata.sourceId !== 'init')
                .map(([doc, score]) => ({
                    id: doc.metadata.id || generateId(), // Use original source thought ID if available
                    embedding: [], // Embeddings are not returned by default
                    content: doc.pageContent,
                    metadata: {
                        created: doc.metadata.created,
                        type: doc.metadata.type,
                        sourceId: doc.metadata.sourceId
                    } as MemoryEntry['metadata'], // Cast to expected type
                    score: score
                }));
        } catch (error) { console.error(chalk.red('Failed to search vector store:'), error); return []; }
    }

    async save(): Promise<void> {
        if (this.vectorStore && this.isReady) {
            try { await this.vectorStore.save(this.storePath); }
            catch (error) { console.error(chalk.red('Failed to save vector store:'), error); }
        }
    }
}

class LLMService {
    llm: BaseChatModel;
    embeddings: Embeddings;

    constructor() {
        this.llm = new ChatOllama({ baseUrl: OLLAMA_BASE_URL, model: OLLAMA_MODEL, temperature: 0.7 });
        this.embeddings = new OllamaEmbeddings({ model: OLLAMA_EMBEDDING_MODEL, baseUrl: OLLAMA_BASE_URL });
        console.log(chalk.blue(`LLM Service: Model=${OLLAMA_MODEL}, Embeddings=${OLLAMA_EMBEDDING_MODEL}, URL=${OLLAMA_BASE_URL}`));
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
        try { return await this.embeddings.embedQuery(text); }
        catch (error: any) { console.error(chalk.red(`Embedding failed: ${error.message}`)); return []; }
    }
}

// --- Tools ---

class LLMTool implements Tool {
    name = "LLMTool";
    description = "Interacts with the LLM for text generation or embedding. Use generate(prompt) or embed(text).";

    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operationAtom = action.args[0];
        const inputTerm = action.args[1];
        if (operationAtom?.kind !== 'Atom' || !inputTerm) return TermLogic.Atom("error:LLMTool_invalid_params");
        const operation = operationAtom.name;
        const inputText = TermLogic.toString(inputTerm); // Get text representation of the input term

        switch (operation) {
            case 'generate': {
                const response = await context.llm.generate(inputText);
                if (response.startsWith('Error:')) return TermLogic.Atom(`error:LLMTool_generation_failed:${response}`);
                try {
                    const parsedJson = JSON.parse(response);
                    const termFromJson = TermLogic.jsonToTerm(parsedJson);
                    // If parsing and conversion to Term is successful, return the Term
                    if (termFromJson) return termFromJson;
                } catch (e) { /* Ignore JSON parsing errors, fallback to Atom */ }
                // Fallback: return raw response as an Atom
                return TermLogic.Atom(response);
            }
            case 'embed': {
                const embedding = await context.llm.embed(inputText);
                // Cannot return number[] directly as Term. Return success indicator.
                // Actual embedding might be used internally by caller or other mechanisms.
                return embedding.length > 0 ? TermLogic.Atom("ok:embedded") : TermLogic.Atom("error:LLMTool_embedding_failed");
            }
            default: return TermLogic.Atom(`error:LLMTool_unsupported_operation:${operation}`);
        }
    }
}


class MemoryTool implements Tool {
    name = "MemoryTool";
    description = "Manages vector memory: add(content_term), search(query_term, k?).";
    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operationAtom = action.args[0];
        if (operationAtom?.kind !== 'Atom') return TermLogic.Atom("error:MemoryTool_missing_operation");
        const operation = operationAtom.name;

        if (operation === 'add') {
            const contentTerm = action.args[1];
            if (!contentTerm) return TermLogic.Atom("error:MemoryTool_missing_add_content");
            await context.memory.add({
                id: generateId(), // Unique ID for this memory entry
                content: TermLogic.toString(contentTerm),
                metadata: { created: new Date().toISOString(), type: trigger.type, sourceId: trigger.id }
            });
            trigger.metadata.embedded = new Date().toISOString(); // Mark the source thought
            context.thoughts.update(trigger); // Update the thought metadata
            return TermLogic.Atom("ok:memory_added");
        } else if (operation === 'search') {
            const queryTerm = action.args[1];
            const kTerm = action.args[2];
            if (!queryTerm) return TermLogic.Atom("error:MemoryTool_missing_search_query");
            const queryStr = TermLogic.toString(queryTerm);
            const k = (kTerm?.kind === 'Atom' && /^\d+$/.test(kTerm.name)) ? parseInt(kTerm.name, 10) : 3;
            const results = await context.memory.search(queryStr, k);
            // Return results as a List of Atoms (content only)
            return TermLogic.List(results.map(r => TermLogic.Atom(r.content)));
        }
        return TermLogic.Atom(`error:MemoryTool_unsupported_operation:${operation}`);
    }
}

class UserInteractionTool implements Tool {
    name = "UserInteractionTool";
    description = "Requests input from the user: prompt(prompt_text_term).";
    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operationAtom = action.args[0];
        const promptTextTerm = action.args[1];
        if (operationAtom?.kind !== 'Atom' || operationAtom.name !== 'prompt' || !promptTextTerm) {
            return TermLogic.Atom("error:UITool_invalid_params");
        }

        const promptText = TermLogic.toString(promptTextTerm);
        const promptId = generateId(); // Unique ID for this specific prompt instance

        // Create the USER_PROMPT thought that will be displayed/sent to the UI
        const promptThought: Thought = {
            id: promptId, // Use generated ID directly for the prompt thought
            type: Type.USER_PROMPT,
            content: TermLogic.Atom(promptText),
            belief: Belief.DEFAULT,
            status: Status.PENDING, // This thought itself is pending until fulfilled
            metadata: {
                rootId: trigger.metadata.rootId ?? trigger.id,
                parentId: trigger.id,
                created: new Date().toISOString(),
                promptId: promptId, // Reference itself for UI interaction
                provenance: this.name,
            }
        };
        context.engine.addThought(promptThought);

        // Set the triggering thought to WAITING state, linking it to the promptId
        trigger.status = Status.WAITING;
        trigger.metadata.waitingFor = promptId; // Mark what it's waiting for
        context.thoughts.update(trigger);

        return TermLogic.Atom(`ok:prompt_requested:${promptId}`); // Return the ID of the prompt created
    }
}

class GoalProposalTool implements Tool {
    name = "GoalProposalTool";
    description = "Suggests new goals based on context: suggest(context_term?). Uses trigger content if no term provided.";
    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operationAtom = action.args[0];
        if (operationAtom?.kind !== 'Atom' || operationAtom.name !== 'suggest') {
            return TermLogic.Atom("error:GoalTool_invalid_params");
        }

        const contextTerm = action.args[1] ?? trigger.content; // Use provided term or trigger's content
        const contextStr = TermLogic.toString(contextTerm);
        const memoryResults = await context.memory.search(`Relevant past goals or outcomes related to: ${contextStr}`, 3);
        const memoryContext = memoryResults.length > 0
            ? `\nRelated past activities:\n - ${memoryResults.map(r => r.content).join("\n - ")}`
            : "";
        const prompt = `Based on the current context "${contextStr}"${memoryContext}\n\nSuggest ONE concise, actionable next goal or task. Output only the suggested goal text.`;

        const suggestionText = await context.llm.generate(prompt);
        if (!suggestionText || suggestionText.startsWith('Error:')) {
            return TermLogic.Atom(`error:GoalTool_llm_failed:${suggestionText}`);
        }

        const suggestionThought: Thought = {
            id: generateId(),
            type: Type.GOAL, // Suggest directly as a GOAL
            content: TermLogic.Atom(suggestionText),
            belief: new Belief(1, 0), // Start with positive belief
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
    }
}

class CoreTool implements Tool {
    name = "CoreTool";
    description = "Manages internal state: set_status(target_id_atom, status_atom), add_thought(type_atom, content_term, root_id?, parent_id?), delete_thought(target_id_atom).";
    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operationAtom = action.args[0];
        if (operationAtom?.kind !== 'Atom') return TermLogic.Atom("error:CoreTool_missing_operation");
        const operation = operationAtom.name;

        switch (operation) {
            case 'set_status': {
                const targetIdTerm = action.args[1]; const newStatusTerm = action.args[2];
                if (targetIdTerm?.kind !== 'Atom' || newStatusTerm?.kind !== 'Atom') return TermLogic.Atom("error:CoreTool_invalid_set_status_params");
                const newStatus = newStatusTerm.name.toUpperCase() as Status;
                if (!Object.values(Status).includes(newStatus)) return TermLogic.Atom(`error:CoreTool_invalid_status_value:${newStatusTerm.name}`);

                // Allow using 'self' to target the triggering thought
                const targetId = targetIdTerm.name === 'self' ? trigger.id : targetIdTerm.name;
                const targetThought = context.thoughts.findThought(targetId);

                if (targetThought) {
                    targetThought.status = newStatus; context.thoughts.update(targetThought);
                    return TermLogic.Atom(`ok:status_set:${shortId(targetThought.id)}_to_${newStatus}`);
                } return TermLogic.Atom(`error:CoreTool_target_not_found:${targetIdTerm.name}`);
            }
            case 'add_thought': {
                const typeTerm = action.args[1]; const contentTerm = action.args[2];
                const rootIdTerm = action.args[3]; const parentIdTerm = action.args[4];
                if (typeTerm?.kind !== 'Atom' || !contentTerm) return TermLogic.Atom("error:CoreTool_invalid_add_thought_params");
                const type = typeTerm.name.toUpperCase() as Type;
                if (!Object.values(Type).includes(type)) return TermLogic.Atom(`error:CoreTool_invalid_thought_type:${typeTerm.name}`);

                const resolveId = (term: Term | undefined, fallback: string): string => {
                    if (term?.kind === 'Atom') return term.name === 'self' ? trigger.id : term.name;
                    if (term?.kind === 'Variable') return fallback; // Cannot resolve variable here
                    return fallback;
                };

                const parentId = resolveId(parentIdTerm, trigger.id);
                const rootId = resolveId(rootIdTerm, context.thoughts.get(parentId)?.metadata.rootId ?? parentId);

                const newThought: Thought = {
                    id: generateId(), type, content: contentTerm, belief: Belief.DEFAULT, status: Status.PENDING,
                    metadata: {
                        rootId: rootId, parentId: parentId,
                        created: new Date().toISOString(), provenance: `${this.name} (rule on ${shortId(trigger.id)})`
                    }
                };
                context.engine.addThought(newThought);
                return TermLogic.Atom(`ok:thought_added:${shortId(newThought.id)}`);
            }
            case 'delete_thought': {
                const targetIdTerm = action.args[1];
                if (targetIdTerm?.kind !== 'Atom') return TermLogic.Atom("error:CoreTool_invalid_delete_thought_params");
                const targetId = targetIdTerm.name === 'self' ? trigger.id : targetIdTerm.name;
                const targetThought = context.thoughts.findThought(targetId);
                if (targetThought) {
                    const deleted = context.thoughts.delete(targetThought.id);
                    return deleted ? TermLogic.Atom(`ok:thought_deleted:${shortId(targetThought.id)}`) : TermLogic.Atom(`error:CoreTool_delete_failed:${targetIdTerm.name}`);
                } return TermLogic.Atom(`error:CoreTool_thought_not_found:${targetIdTerm.name}`);
            }
            default: return TermLogic.Atom(`error:CoreTool_unsupported_operation:${operation}`);
        }
    }
}

class ToolRegistry {
    private tools = new Map<string, Tool>();
    register(tool: Tool): void {
        if (this.tools.has(tool.name)) console.warn(chalk.yellow(`Tool "${tool.name}" redefined.`));
        this.tools.set(tool.name, tool);
    }
    get(name: string): Tool | undefined { return this.tools.get(name); }
    list(): Tool[] { return Array.from(this.tools.values()); }
}

// --- Engine ---

class Engine {
    private activeIds = new Set<string>();
    private batchSize: number = 5; // How many thoughts to attempt pulling in one go
    private maxConcurrent: number = 3; // Max thoughts processed in parallel
    private context: ToolContext;

    constructor(
        private thoughts: ThoughtStore, private rules: RuleStore,
        private memory: MemoryStore, private llm: LLMService,
        private tools: ToolRegistry
    ) {
        this.context = { thoughts: this.thoughts, rules: this.rules, memory: this.memory, llm: this.llm, engine: this };
    }

    addThought(thought: Thought): void { this.thoughts.add(thought); }

    private sampleThought(): Thought | null {
        const candidates = this.thoughts.getPending().filter(t => !this.activeIds.has(t.id));
        if (candidates.length === 0) return null;

        // Simple weighted random sampling based on belief score (higher score = higher chance)
        const weights = candidates.map(t => Math.max(0.01, t.metadata.priority ?? t.belief.score())); // Ensure non-zero weight
        const totalWeight = weights.reduce((sum, w) => sum + w, 0);

        if (totalWeight <= 0) return candidates[Math.floor(Math.random() * candidates.length)]; // Fallback if all weights zero

        let random = Math.random() * totalWeight;
        for (let i = 0; i < candidates.length; i++) {
            random -= weights[i];
            if (random <= 0) return candidates[i];
        }
        return candidates[candidates.length - 1]; // Fallback (shouldn't be reached if totalWeight > 0)
    }

    private findAndSelectRule(thought: Thought): { rule: Rule; bindings: Map<string, Term> } | null {
        const applicableMatches = this.rules.getAll()
            .map(rule => ({ rule, bindings: TermLogic.unify(rule.pattern, thought.content) }))
            .filter(m => m.bindings !== null) as { rule: Rule; bindings: Map<string, Term> }[];

        if (applicableMatches.length === 0) return null;
        if (applicableMatches.length === 1) return applicableMatches[0];

        // Weighted random selection among applicable rules based on belief score
        const weights = applicableMatches.map(m => Math.max(0.01, m.rule.belief.score()));
        const totalWeight = weights.reduce((sum, w) => sum + w, 0);

        if (totalWeight <= 0) return applicableMatches[Math.floor(Math.random() * applicableMatches.length)];

        let random = Math.random() * totalWeight;
        for (let i = 0; i < applicableMatches.length; i++) {
            random -= weights[i];
            if (random <= 0) return applicableMatches[i];
        }
        return applicableMatches[applicableMatches.length - 1]; // Fallback
    }

    private async executeAction(thought: Thought, rule: Rule, bindings: Map<string, Term>): Promise<boolean> {
        const boundAction = TermLogic.substitute(rule.action, bindings);
        if (boundAction.kind !== 'Structure') {
            this.handleFailure(thought, rule, `Invalid action term kind: ${boundAction.kind}`); return false;
        }
        const tool = this.tools.get(boundAction.name);
        if (!tool) {
            this.handleFailure(thought, rule, `Tool not found: ${boundAction.name}`); return false;
        }

        let success = false;
        try {
            const resultTerm = await tool.execute(boundAction, this.context, thought);
            // Re-fetch thought state as tool execution might have changed it (e.g., set to WAITING)
            const currentThoughtState = this.thoughts.get(thought.id);
            const isWaiting = currentThoughtState?.status === Status.WAITING;
            const isFailed = currentThoughtState?.status === Status.FAILED;

            if (resultTerm?.kind === 'Atom' && resultTerm.name.startsWith('error:')) {
                // Tool explicitly returned an error
                this.handleFailure(thought, rule, `Tool execution failed: ${resultTerm.name}`);
            } else if (!isWaiting && !isFailed) {
                // If the thought didn't end up waiting or failed by the tool, mark success
                this.handleSuccess(thought, rule); success = true;
            } else {
                // If it's WAITING or FAILED, the tool handled the status transition.
                // Don't mark success here, but don't count as failure either for rule belief.
                // We update rule belief only if the action *completed* successfully or failed explicitly.
                if (isWaiting) rule.belief.update(true); // Assume requesting input is rule 'success'
                else rule.belief.update(false); // Tool set to FAILED
                this.rules.update(rule); // Update rule belief immediately
                return isWaiting; // Return true if waiting (action initiated), false if failed
            }
        } catch (error: any) {
            console.error(chalk.red(`Tool exception ${tool.name} on ${shortId(thought.id)}:`), error);
            this.handleFailure(thought, rule, `Tool exception: ${error.message}`);
        }

        // Update rule belief based on success/failure ONLY if not already handled above (WAITING/FAILED cases)
        // This needs refinement. If handleSuccess/handleFailure updates belief, avoid double update.
        // Let's ensure handleSuccess/Failure DON'T update rule belief, do it here.
        const finalThoughtState = this.thoughts.get(thought.id);
        if (finalThoughtState?.status !== Status.WAITING) { // Update belief unless waiting
            rule.belief.update(success);
            this.rules.update(rule);
        }
        return success;
    }

    private async handleNoRuleMatch(thought: Thought): Promise<void> {
        let action: Term | null = null;
        let llmPrompt: string | null = null;
        let targetType: Type | null = null;

        // Define fallback behaviors based on thought type
        switch (thought.type) {
            case Type.INPUT:
                llmPrompt = `Input received: "${TermLogic.toString(thought.content)}". Define a specific, actionable GOAL based on this input. Output only the goal statement.`;
                targetType = Type.GOAL;
                break;
            case Type.GOAL:
                // Try suggesting strategies first via LLM
                llmPrompt = `Goal is: "${TermLogic.toString(thought.content)}". Outline 1 to 3 concrete STRATEGY steps to achieve this goal. Output each step on a new line.`;
                targetType = Type.STRATEGY;
                break;
            case Type.STRATEGY:
                // Assume strategy execution leads to an outcome. Ask LLM to summarize.
                llmPrompt = `The strategy step "${TermLogic.toString(thought.content)}" was attempted. Describe a likely concise OUTCOME. Output only the outcome description.`;
                targetType = Type.OUTCOME;
                break;
            case Type.OUTCOME:
                // Default action for outcome: Add to memory and suggest next goal
                action = TermLogic.Structure("MemoryTool", [TermLogic.Atom("add"), thought.content]);
                // We could chain GoalProposalTool here, but let's keep it simple: memory add completes the thought.
                // GoalProposal is better triggered by a dedicated rule on OUTCOME.
                break;
            // case Type.QUERY: // Needs specific handling - maybe search memory or ask LLM?
            // case Type.USER_PROMPT: // Should ideally not reach here - needs response
            // case Type.SYSTEM: // System messages might just complete or trigger specific rules
            default:
                // General fallback: Ask the user what to do next
                const askUserPrompt = `No rule found for thought ${shortId(thought.id)} (${thought.type}: ${TermLogic.toString(thought.content).substring(0, 50)}...). How should I proceed?`;
                action = TermLogic.Structure("UserInteractionTool", [TermLogic.Atom("prompt"), TermLogic.Atom(askUserPrompt)]);
                break;
        }

        if (llmPrompt && targetType) {
            const resultText = await this.llm.generate(llmPrompt);
            if (resultText && !resultText.startsWith('Error:')) {
                // Create new thoughts for each line of the response
                resultText.split('\n')
                    .map(s => s.trim().replace(/^- /, '')) // Clean up potential list markers
                    .filter(s => s.length > 0)
                    .forEach(resText => {
                        this.addThought({
                            id: generateId(), type: targetType!, content: TermLogic.Atom(resText),
                            belief: Belief.DEFAULT, status: Status.PENDING,
                            metadata: {
                                rootId: thought.metadata.rootId ?? thought.id, parentId: thought.id,
                                created: new Date().toISOString(), provenance: 'llm_fallback'
                            }
                        });
                    });
                // Mark original thought as DONE since fallback generated next steps
                this.handleSuccess(thought, null);
            } else { this.handleFailure(thought, null, `LLM fallback failed: ${resultText}`); }
        } else if (action?.kind === 'Structure') {
            const tool = this.tools.get(action.name);
            if (tool) {
                try {
                    await tool.execute(action, this.context, thought);
                    // Check thought status *after* execution, as tool might change it (e.g., to WAITING)
                    const currentStatus = this.thoughts.get(thought.id)?.status;
                    if (currentStatus !== Status.WAITING && currentStatus !== Status.FAILED) {
                        // If tool executed and didn't set to waiting/failed, mark original DONE
                        this.handleSuccess(thought, null);
                    } // Otherwise, the tool execution handled the status
                } catch (error: any) { this.handleFailure(thought, null, `Fallback tool execution fail (${action.name}): ${error.message}`); }
            } else { this.handleFailure(thought, null, `Fallback tool not found: ${action.name}`); }
        } else if (thought.status === Status.PENDING) {
            // If no LLM prompt, no action, and still pending -> mark as failed
            this.handleFailure(thought, null, "No rule match and no fallback action defined.");
        }
        // If the thought was already handled (e.g., set to WAITING by a fallback tool), do nothing more.
    }

    private handleFailure(thought: Thought, rule: Rule | null, errorInfo: string): void {
        const currentThought = this.thoughts.get(thought.id); // Get latest state
        if (!currentThought || currentThought.status === Status.FAILED) return; // Avoid redundant failure processing

        const retries = (currentThought.metadata.retries ?? 0) + 1;
        currentThought.status = retries >= MAX_RETRIES ? Status.FAILED : Status.PENDING; // Retry or fail
        currentThought.metadata.error = errorInfo.substring(0, 250); // Truncate long errors
        currentThought.metadata.retries = retries;
        if (rule) currentThought.metadata.ruleId = rule.id; // Record rule that led to failure (if applicable)
        currentThought.belief.update(false); // Lower belief on failure
        this.thoughts.update(currentThought);
        console.warn(chalk.yellow(`Thought ${shortId(currentThought.id)} failed (Attempt ${retries}): ${errorInfo}`));
    }

    private handleSuccess(thought: Thought, rule: Rule | null): void {
        const currentThought = this.thoughts.get(thought.id); // Get latest state
        if (!currentThought || currentThought.status === Status.DONE) return; // Avoid redundant processing

        currentThought.status = Status.DONE;
        delete currentThought.metadata.error; // Clear errors on success
        delete currentThought.metadata.retries; // Clear retries
        if (rule) currentThought.metadata.ruleId = rule.id; // Record rule that led to success
        currentThought.belief.update(true); // Increase belief on success
        this.thoughts.update(currentThought);
        // Note: Rule belief is updated in executeAction after success/failure is determined.
    }

    private async _processThought(thought: Thought): Promise<boolean> {
        try {
            thought.status = Status.ACTIVE;
            thought.metadata.agentId = 'worker'; // Mark who is processing
            this.thoughts.update(thought);
            let success = false;

            const match = this.findAndSelectRule(thought);
            if (match) {
                success = await this.executeAction(thought, match.rule, match.bindings);
            } else {
                await this.handleNoRuleMatch(thought);
                // Check status after fallback attempt
                success = this.thoughts.get(thought.id)?.status === Status.DONE;
            }
            return success; // Return whether the thought concluded successfully

        } catch (error: any) {
            console.error(chalk.red(`Critical error processing ${shortId(thought.id)}:`), error);
            // Ensure thought is marked as failed on unexpected errors
            this.handleFailure(thought, null, `Unhandled processing exception: ${error.message}`);
            return false;
        } finally {
            this.activeIds.delete(thought.id); // Release the lock regardless of outcome
            // Final safety check: If thought is somehow still ACTIVE, mark FAILED.
            const finalThoughtState = this.thoughts.get(thought.id);
            if (finalThoughtState?.status === Status.ACTIVE) {
                console.warn(chalk.yellow(`Thought ${shortId(thought.id)} ended ACTIVE. Setting FAILED.`));
                this.handleFailure(finalThoughtState, null, "Processing ended while ACTIVE.");
            }
        }
    }

    async processSingleThought(): Promise<boolean> {
        const thought = this.sampleThought();
        if (!thought || this.activeIds.has(thought.id)) return false; // No eligible thought or already processing

        this.activeIds.add(thought.id); // Mark as active
        return this._processThought(thought);
    }

    async processBatch(): Promise<number> {
        const promises: Promise<boolean>[] = [];
        let acquiredCount = 0;

        // Try to acquire up to batchSize thoughts, respecting maxConcurrent limit
        while (this.activeIds.size < this.maxConcurrent && acquiredCount < this.batchSize) {
            const thought = this.sampleThought();
            if (!thought) break; // No more eligible thoughts

            this.activeIds.add(thought.id);
            promises.push(this._processThought(thought));
            acquiredCount++;
        }

        if (promises.length === 0) return 0;

        const results = await Promise.all(promises);
        return results.filter(success => success).length; // Return count of successfully processed thoughts
    }

    async handlePromptResponse(promptId: string, responseText: string): Promise<boolean> {
        // 1. Find the original USER_PROMPT thought
        const promptThought = this.thoughts.findPendingPrompt(promptId);
        if (!promptThought) { console.error(chalk.red(`Pending prompt thought for ID ${shortId(promptId)} not found.`)); return false; }

        // 2. Find the thought that was WAITING for this prompt
        const waitingThought = this.thoughts.findWaitingThought(promptId);
        if (!waitingThought) {
            console.warn(chalk.yellow(`No thought found waiting for prompt ${shortId(promptId)}. Marking prompt as done.`));
            // Mark prompt done, but can't reactivate anything.
            promptThought.status = Status.DONE;
            promptThought.metadata.error = "No waiting thought found upon response.";
            this.thoughts.update(promptThought);
            return false;
        }

        // 3. Create a new INPUT thought representing the user's response
        const responseThought: Thought = {
            id: generateId(), type: Type.INPUT, content: TermLogic.Atom(responseText),
            belief: new Belief(1, 0), // User input starts positive
            status: Status.PENDING,
            metadata: {
                rootId: waitingThought.metadata.rootId ?? waitingThought.id,
                parentId: waitingThought.id, // Response is child of the waiting thought
                created: new Date().toISOString(),
                responseTo: promptId, // Link back to the prompt
                tags: ['user_response'],
                provenance: 'user_input'
            }
        };
        this.addThought(responseThought);

        // 4. Mark the original prompt thought as DONE
        promptThought.status = Status.DONE;
        this.thoughts.update(promptThought);

        // 5. Reactivate the waiting thought
        waitingThought.status = Status.PENDING;
        delete waitingThought.metadata.waitingFor; // Remove the waiting flag
        waitingThought.belief.update(true); // Receiving response is positive for the waiting thought's progression
        this.thoughts.update(waitingThought);

        console.log(chalk.blue(`Response for ${shortId(promptId)} received. Thought ${shortId(waitingThought.id)} reactivated.`));
        return true;
    }
}

// --- Persistence ---
async function saveState(thoughts: ThoughtStore, rules: RuleStore, memory: MemoryStore): Promise<void> {
    try {
        await fs.mkdir(DATA_DIR, { recursive: true });
        const state: AppState = { thoughts: thoughts.toJSON(), rules: rules.toJSON() };
        await fs.writeFile(STATE_FILE, JSON.stringify(state, null, 2));
        await memory.save(); // Save vector store index too
        // console.log(chalk.gray(`State saved.`)); // Too noisy for debounced saves
    } catch (error) { console.error(chalk.red('Error saving state:'), error); }
}

const debouncedSaveState = debounce(saveState, SAVE_DEBOUNCE);

async function loadState(thoughts: ThoughtStore, rules: RuleStore, memory: MemoryStore): Promise<void> {
    try {
        await fs.mkdir(DATA_DIR, { recursive: true });
        await memory.initialize(); // Initialize memory store first (loads/creates vector store)
        if (!memory.isReady) {
            console.error(chalk.red("Memory store failed to initialize. State loading might be incomplete."));
        }
        if (fsSync.existsSync(STATE_FILE)) {
            const data = await fs.readFile(STATE_FILE, 'utf-8');
            const state: AppState = safeJsonParse(data, { thoughts: {}, rules: {} });
            thoughts.loadJSON(state.thoughts);
            rules.loadJSON(state.rules);
            console.log(chalk.blue(`Loaded ${thoughts.count()} thoughts, ${rules.count()} rules.`));
        } else { console.log(chalk.yellow(`State file ${STATE_FILE} not found. Starting fresh.`)); }
    } catch (error) {
        console.error(chalk.red('Error loading state:'), error);
        // Ensure memory store is initialized even if state loading fails
        if (!memory.isReady) { await memory.initialize(); }
    }
}

// --- WebSocket API Server ---

class WsApiServer {
    private wss: WebSocketServer | null = null;
    private clients = new Map<WebSocket, { ip: string }>();
    private commandHandlers = new Map<string, (payload: any, ws: WebSocket) => Promise<any | void>>();

    constructor(
        private port: number,
        private thoughts: ThoughtStore,
        private rules: RuleStore,
        private memory: MemoryStore,
        private engine: Engine,
        private tools: ToolRegistry,
        private systemControl: {
            startProcessing: () => void,
            pauseProcessing: () => void,
            stepProcessing: () => Promise<boolean>,
            shutdown: () => Promise<void>,
            getStatus: () => { isRunning: boolean }
        }
    ) {
        this.registerCommandHandlers();
        // Listen to store changes to broadcast updates
        this.thoughts.addChangeListener(this.broadcastThoughtChanges.bind(this));
        this.rules.addChangeListener(this.broadcastRuleChanges.bind(this));
    }

    start(): void {
        this.wss = new WebSocketServer({ port: this.port });
        this.wss.on('listening', () => {
            console.log(chalk.green(`🚀 FlowMind WebSocket API listening on ws://localhost:${this.port}`));
        });
        this.wss.on('connection', this.handleConnection.bind(this));
        this.wss.on('error', (error) => {
            console.error(chalk.red('WebSocket Server Error:'), error);
        });
    }

    stop(): void {
        console.log(chalk.yellow('Stopping WebSocket API server...'));
        this.wss?.close();
        this.clients.clear();
    }

    private handleConnection(ws: WebSocket, req: any): void {
        const ip = req.socket.remoteAddress || 'unknown';
        console.log(chalk.blue(`Client connected: ${ip}`));
        this.clients.set(ws, { ip });

        ws.on('message', (message) => this.handleMessage(ws, message as Buffer));
        ws.on('close', () => this.handleDisconnection(ws));
        ws.on('error', (error) => {
            console.error(chalk.red(`WebSocket error from ${ip}:`), error);
            this.handleDisconnection(ws); // Clean up on error
        });

        // Send initial state snapshot to the new client
        this.sendSnapshot(ws);
    }

    private handleDisconnection(ws: WebSocket): void {
        const clientInfo = this.clients.get(ws);
        console.log(chalk.yellow(`Client disconnected: ${clientInfo?.ip || 'unknown'}`));
        this.clients.delete(ws);
    }

    private async handleMessage(ws: WebSocket, message: Buffer): Promise<void> {
        let request: { command: string; payload?: any; requestId?: string };
        try {
            request = JSON.parse(message.toString());
            if (typeof request.command !== 'string') throw new Error("Invalid command format");
        } catch (e) {
            this.send(ws, { type: 'error', payload: 'Invalid JSON message format.', requestId: request?.requestId });
            return;
        }

        const handler = this.commandHandlers.get(request.command);
        if (handler) {
            try {
                const result = await handler(request.payload || {}, ws);
                // Send back success response with result if handler returns data
                this.send(ws, { type: 'response', payload: result ?? null, success: true, requestId: request.requestId });
                // Trigger debounced save after potentially state-changing commands
                if (!['get_thoughts', 'get_rules', 'get_status', 'info', 'search', 'help', 'tools'].includes(request.command)) {
                    debouncedSaveState(this.thoughts, this.rules, this.memory);
                }
            } catch (error: any) {
                console.error(chalk.red(`Error executing command "${request.command}":`), error);
                this.send(ws, { type: 'response', payload: error.message || 'Command execution failed.', success: false, requestId: request.requestId });
            }
        } else {
            this.send(ws, { type: 'error', payload: `Unknown command: ${request.command}`, requestId: request.requestId });
        }
    }

    private send(ws: WebSocket, data: any): void {
        if (ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify(data));
        }
    }

    private broadcast(data: any): void {
        const message = JSON.stringify(data);
        this.clients.forEach((_, ws) => {
            if (ws.readyState === WebSocket.OPEN) {
                ws.send(message);
            }
        });
    }

    private broadcastThoughtChanges(): void {
        const delta = this.thoughts.getDelta();
        if (delta.changed.length > 0 || delta.deleted.length > 0) {
            this.broadcast({ type: 'thoughts_delta', payload: delta });
        }
    }

    private broadcastRuleChanges(): void {
        const delta = this.rules.getDelta();
        if (delta.changed.length > 0 || delta.deleted.length > 0) {
            this.broadcast({ type: 'rules_delta', payload: delta });
        }
    }

    private sendSnapshot(ws: WebSocket): void {
        this.send(ws, {
            type: 'snapshot',
            payload: {
                thoughts: this.thoughts.getAll(),
                rules: this.rules.getAll(),
                status: this.systemControl.getStatus()
            }
        });
    }

    private registerCommandHandlers(): void {
        const handlers: { [key: string]: (payload: any, ws: WebSocket) => Promise<any | void> } = {
            add: async (payload) => {
                if (!payload.text) throw new Error("Missing 'text' in payload");
                const newThought: Thought = {
                    id: generateId(), type: Type.INPUT, content: TermLogic.Atom(payload.text),
                    belief: new Belief(1, 0), status: Status.PENDING,
                    metadata: { created: new Date().toISOString(), tags: ['user_added'], provenance: 'ws_api' }
                };
                newThought.metadata.rootId = newThought.id;
                this.engine.addThought(newThought);
                return { id: newThought.id, message: `Added: ${shortId(newThought.id)}` };
            },
            respond: async (payload) => {
                if (!payload.promptId || !payload.text) throw new Error("Missing 'promptId' or 'text' in payload");
                const success = await this.engine.handlePromptResponse(payload.promptId, payload.text);
                if (!success) throw new Error(`Failed to process response for prompt ${payload.promptId}.`);
                return { message: `Response for ${shortId(payload.promptId)} processed.` };
            },
            run: async () => { this.systemControl.startProcessing(); return { message: "Processing started." }; },
            pause: async () => { this.systemControl.pauseProcessing(); return { message: "Processing paused." }; },
            step: async () => {
                const processed = await this.systemControl.stepProcessing();
                return { processed, message: processed ? "Step completed." : "Nothing to step." };
            },
            save: async () => {
                debouncedSaveState.flush(); // Force immediate save
                await saveState(this.thoughts, this.rules, this.memory);
                return { message: "State saved." };
            },
            quit: async () => {
                await this.systemControl.shutdown();
                // No response needed as server will shut down. Client should handle disconnect.
            },
            get_status: async () => this.systemControl.getStatus(),
            info: async (payload) => {
                if (!payload.idPrefix) throw new Error("Missing 'idPrefix' in payload");
                const thought = this.thoughts.findThought(payload.idPrefix);
                if (thought) return { type: 'thought', data: thought };
                const rule = this.rules.findRule(payload.idPrefix);
                if (rule) return { type: 'rule', data: rule };
                throw new Error(`ID prefix "${payload.idPrefix}" not found.`);
            },
            get_thoughts: async (payload) => {
                if (payload.rootId) return this.thoughts.getAllByRootId(payload.rootId);
                return this.thoughts.getAll();
            },
            get_rules: async () => this.rules.getAll(),
            tools: async () => this.tools.list().map(t => ({ name: t.name, description: t.description })),
            tag: async (payload) => {
                if (!payload.idPrefix || !payload.tag) throw new Error("Missing 'idPrefix' or 'tag' in payload");
                const thought = this.thoughts.findThought(payload.idPrefix);
                if (!thought) throw new Error(`Thought "${payload.idPrefix}" not found.`);
                thought.metadata.tags = [...new Set([...(thought.metadata.tags || []), payload.tag])];
                this.thoughts.update(thought);
                return { id: thought.id, tags: thought.metadata.tags };
            },
            untag: async (payload) => {
                if (!payload.idPrefix || !payload.tag) throw new Error("Missing 'idPrefix' or 'tag' in payload");
                const thought = this.thoughts.findThought(payload.idPrefix);
                if (!thought || !thought.metadata.tags) throw new Error(`Thought "${payload.idPrefix}" not found or has no tags.`);
                thought.metadata.tags = thought.metadata.tags.filter(t => t !== payload.tag);
                this.thoughts.update(thought);
                return { id: thought.id, tags: thought.metadata.tags };
            },
            search: async (payload) => {
                if (!payload.query) throw new Error("Missing 'query' in payload");
                const k = typeof payload.k === 'number' ? payload.k : 5;
                return this.memory.search(payload.query, k);
            },
            delete: async (payload) => {
                if (!payload.idPrefix) throw new Error("Missing 'idPrefix' in payload");
                const thought = this.thoughts.findThought(payload.idPrefix);
                if (!thought) throw new Error(`Thought "${payload.idPrefix}" not found.`);
                const deleted = this.thoughts.delete(thought.id);
                if (!deleted) throw new Error(`Failed to delete thought ${shortId(thought.id)}.`);
                return { id: thought.id, message: `Thought ${shortId(thought.id)} deleted.` };
            },
            help: async () => {
                return {
                    description: "Available commands and payloads.",
                    commands: Object.fromEntries(this.commandHandlers.entries())
                };
            },
            ping: async () => "pong",
        };

        for (const [command, handler] of Object.entries(handlers)) {
            this.commandHandlers.set(command, handler.bind(this)); // Bind 'this' context
        }
    }
}


// --- Main System Orchestrator ---

class FlowMindSystem {
    private thoughts = new ThoughtStore();
    private rules = new RuleStore();
    private llm = new LLMService();
    private memory = new MemoryStore(this.llm.embeddings, VECTOR_STORE_DIR);
    private tools = new ToolRegistry();
    private engine = new Engine(this.thoughts, this.rules, this.memory, this.llm, this.tools);
    private apiServer: WsApiServer;
    private isRunning = false;
    private workerIntervalId: NodeJS.Timeout | null = null;

    constructor() {
        this.registerCoreTools();
        this.apiServer = new WsApiServer(
            WS_PORT, this.thoughts, this.rules, this.memory, this.engine, this.tools,
            { // System Control Interface passed to API Server
                startProcessing: this.startProcessingLoop.bind(this),
                pauseProcessing: this.pauseProcessingLoop.bind(this),
                stepProcessing: this.engine.processSingleThought.bind(this.engine),
                shutdown: this.shutdown.bind(this),
                getStatus: () => ({ isRunning: this.isRunning })
            }
        );
    }

    private registerCoreTools(): void {
        [new LLMTool(), new MemoryTool(), new UserInteractionTool(), new GoalProposalTool(), new CoreTool()]
            .forEach(t => this.tools.register(t));
    }

    private bootstrapRules(): void {
        if (this.rules.count() > 0) return; // Don't overwrite existing rules
        console.log(chalk.blue("Bootstrapping default rules..."));
        const defaultRulesData: ({ description: string; pattern: Structure; action: Structure } | {
            description: string;
            pattern: Atom;
            action: Structure
        } | { description: string; pattern: Structure; action: Structure } | {
            description: string;
            pattern: Atom;
            action: Structure
        } | { description: string; pattern: Structure; action: Structure } | {
            description: string;
            pattern: Atom;
            action: Structure
        } | { description: string; pattern: Structure; action: Structure } | {
            description: string;
            pattern: Structure;
            action: Structure
        })[] = [
            {
                description: "Input -> Generate Goal",
                pattern: TermLogic.Structure("INPUT", [TermLogic.Variable("Content")]),
                action: TermLogic.Structure("LLMTool", [TermLogic.Atom("generate"), TermLogic.Atom("Input: \"?Content\". Define a specific, actionable GOAL. Output ONLY the goal as a single line of text.")])
            },
            { // Rule to turn LLM's generated goal text into a GOAL thought
                description: "LLM Goal Text -> Add GOAL Thought",
                pattern: TermLogic.Atom("?GoalText"), // Matches the Atom output from the previous LLM call
                action: TermLogic.Structure("CoreTool", [TermLogic.Atom("add_thought"), TermLogic.Atom("GOAL"), TermLogic.Variable("GoalText"), TermLogic.Atom("self"), TermLogic.Atom("self")])
            },
            { // Rule to take a GOAL and generate STRATEGY steps
                description: "Goal -> Generate Strategy",
                pattern: TermLogic.Structure("GOAL", [TermLogic.Variable("GoalContent")]),
                action: TermLogic.Structure("LLMTool", [TermLogic.Atom("generate"), TermLogic.Atom("Goal: \"?GoalContent\". Outline 1-3 concrete STRATEGY steps. Output each step on a new line.")])
            },
            { // Rule to turn LLM's generated strategy text (potentially multi-line) into STRATEGY thoughts
                description: "LLM Strategy Text -> Add STRATEGY Thoughts",
                pattern: TermLogic.Atom("?StrategyText"), // Matches the Atom output
                action: TermLogic.Structure("CoreTool", [TermLogic.Atom("add_thought"), TermLogic.Atom("STRATEGY"), TermLogic.Variable("StrategyText"), TermLogic.Atom("self"), TermLogic.Atom("self")])
                // Note: This rule might need adjustment if LLM output needs parsing into multiple thoughts.
                // A more robust approach might parse the text and create multiple thoughts.
                // For simplicity now, assumes one thought per line or the CoreTool handles it.
            },
            {
                description: "Strategy -> Generate Outcome",
                pattern: TermLogic.Structure("STRATEGY", [TermLogic.Variable("StrategyContent")]),
                action: TermLogic.Structure("LLMTool", [TermLogic.Atom("generate"), TermLogic.Atom("Strategy step \"?StrategyContent\" attempted. Describe concise OUTCOME.")])
            },
            { // Rule to turn LLM's outcome text into an OUTCOME thought
                description: "LLM Outcome Text -> Add OUTCOME Thought",
                pattern: TermLogic.Atom("?OutcomeText"),
                action: TermLogic.Structure("CoreTool", [TermLogic.Atom("add_thought"), TermLogic.Atom("OUTCOME"), TermLogic.Variable("OutcomeText"), TermLogic.Atom("self"), TermLogic.Atom("self")])
            },
            {
                description: "Outcome -> Add to Memory",
                pattern: TermLogic.Structure("OUTCOME", [TermLogic.Variable("OutcomeContent")]),
                action: TermLogic.Structure("MemoryTool", [TermLogic.Atom("add"), TermLogic.Variable("OutcomeContent")])
            },
            { // Example: Suggest next goal after an outcome is memorized
                description: "Outcome (after Mem Add) -> Suggest Next Goal",
                pattern: TermLogic.Structure("OUTCOME", [TermLogic.Variable("OutcomeContent")]), // Re-match outcome
                action: TermLogic.Structure("GoalProposalTool", [TermLogic.Atom("suggest"), TermLogic.Variable("OutcomeContent")])
                // This relies on the outcome thought potentially becoming PENDING again after Mem Add if rules allow,
                // or being processed twice. A cleaner way might be a rule triggering on MemoryTool success.
                // For now, keep it simple. May need priority adjustments.
            },
        ];

        defaultRulesData.forEach((rData, i) => {
            const { description, ...ruleCore } = rData;
            this.rules.add({
                id: generateId(),
                ...ruleCore,
                belief: Belief.DEFAULT,
                metadata: { description: description || `Default Rule ${i+1}`, provenance: 'bootstrap', created: new Date().toISOString() }
            });
        });
        console.log(chalk.green(`${this.rules.count()} default rules added.`));
    }

    async initialize(): Promise<void> {
        console.log(chalk.cyan('Initializing FlowMind System...'));
        await loadState(this.thoughts, this.rules, this.memory);
        this.bootstrapRules();
        this.apiServer.start();
        // Don't start processing loop automatically
        console.log(chalk.green('FlowMind Initialized.') + chalk.yellow(" Paused. Use API 'run' command to start processing."));
    }

    startProcessingLoop(): void {
        if (this.isRunning) { console.log(chalk.yellow("Processing already running.")); return; }
        console.log(chalk.green('Starting processing loop...'));
        this.isRunning = true;
        // TODO: Broadcast status change
        this.workerIntervalId = setInterval(async () => {
            if (!this.isRunning) return; // Check flag inside interval
            try {
                if (await this.engine.processBatch() > 0) {
                    // Processing occurred, trigger debounced save
                    debouncedSaveState(this.thoughts, this.rules, this.memory);
                }
            } catch (error) { console.error(chalk.red('Error in processing loop:'), error); }
        }, WORKER_INTERVAL);
    }

    pauseProcessingLoop(): void {
        if (!this.isRunning) { console.log(chalk.yellow("Processing already paused.")); return; }
        console.log(chalk.yellow('Pausing processing loop...'));
        this.isRunning = false;
        // TODO: Broadcast status change
        if (this.workerIntervalId) {
            clearInterval(this.workerIntervalId);
            this.workerIntervalId = null;
        }
        // Ensure any pending saves are flushed on pause
        debouncedSaveState.flush();
        saveState(this.thoughts, this.rules, this.memory); // Save state immediately on pause
    }

    async shutdown(): Promise<void> {
        console.log(chalk.cyan('\nShutting down FlowMind System...'));
        this.pauseProcessingLoop(); // Stop processing & save state
        this.apiServer.stop();
        // Final flush and save
        debouncedSaveState.flush();
        await saveState(this.thoughts, this.rules, this.memory);
        console.log(chalk.green('FlowMind shutdown complete. Goodbye!'));
        // Give logs a moment to flush before exiting
        await sleep(100);
        process.exit(0);
    }
}

// --- REPL Client Example ---

async function startReplClient(serverUrl: string) {
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout, prompt: chalk.blueBright('FlowMind Client> ') });
    const ws = new WebSocket(serverUrl);
    let requestCounter = 0;
    const pendingRequests = new Map<string, { resolve: (value: any) => void, reject: (reason?: any) => void }>();

    const sendCommand = (command: string, payload: any): Promise<any> => {
        return new Promise((resolve, reject) => {
            const requestId = `req-${requestCounter++}`;
            pendingRequests.set(requestId, { resolve, reject });
            ws.send(JSON.stringify({ command, payload, requestId }));
            // Set a timeout for requests
            setTimeout(() => {
                if (pendingRequests.has(requestId)) {
                    pendingRequests.delete(requestId);
                    reject(new Error(`Request ${requestId} (${command}) timed out`));
                }
            }, 10000); // 10 second timeout
        });
    };

    ws.on('open', () => {
        console.log(chalk.green(`Connected to FlowMind server at ${serverUrl}`));
        rl.prompt();
    });

    ws.on('message', (data) => {
        const message = safeJsonParse(data.toString(), null);
        if (!message) { console.log(chalk.yellow('Received non-JSON message:', data.toString())); return; }

        const { type, payload, success, requestId, ...rest } = message;

        if (type === 'response' && requestId && pendingRequests.has(requestId)) {
            const { resolve, reject } = pendingRequests.get(requestId)!;
            if (success) resolve(payload);
            else reject(new Error(payload || `Command failed for request ${requestId}`));
            pendingRequests.delete(requestId);
            rl.prompt(); // Re-prompt after command response
        } else if (type === 'error') {
            console.error(chalk.red(`Server Error: ${payload}`), requestId ? `(for request ${requestId})` : '');
            if (requestId && pendingRequests.has(requestId)) {
                pendingRequests.get(requestId)!.reject(new Error(payload || 'Server error response'));
                pendingRequests.delete(requestId);
            }
            rl.prompt();
        } else if (type === 'snapshot') {
            console.log(chalk.magenta('--- Received State Snapshot ---'));
            console.log(chalk.cyan(`Thoughts: ${payload.thoughts?.length ?? 0}, Rules: ${payload.rules?.length ?? 0}, Status: ${payload.status?.isRunning ? 'RUNNING' : 'PAUSED'}`));
        } else if (type === 'thoughts_delta') {
            if (payload.changed.length > 0) console.log(chalk.green(`Thoughts Updated: ${payload.changed.map((t: Thought) => shortId(t.id)).join(', ')}`));
            if (payload.deleted.length > 0) console.log(chalk.red(`Thoughts Deleted: ${payload.deleted.map(shortId).join(', ')}`));
        } else if (type === 'rules_delta') {
            if (payload.changed.length > 0) console.log(chalk.blue(`Rules Updated: ${payload.changed.map((r: Rule) => shortId(r.id)).join(', ')}`));
            if (payload.deleted.length > 0) console.log(chalk.yellow(`Rules Deleted: ${payload.deleted.map(shortId).join(', ')}`));
        } else {
            // Generic broadcast or unknown message type
            console.log(chalk.gray(`Server Message (${type || 'unknown'}):`), payload ?? rest);
        }
        // Don't re-prompt automatically for broadcasts, only for responses/errors
        if (type !== 'response' && type !== 'error') {
            // rl.prompt(); // Avoid excessive prompting on broadcasts
        }
    });

    ws.on('close', () => {
        console.log(chalk.red('Disconnected from server. Exiting.'));
        rl.close();
        process.exit(1);
    });

    ws.on('error', (error) => {
        console.error(chalk.red('Connection Error:'), error.message);
        rl.close();
        process.exit(1);
    });

    rl.on('line', async (line) => {
        const trimmed = line.trim();
        if (!trimmed) { rl.prompt(); return; }
        if (trimmed === 'quit' || trimmed === 'exit') { ws.close(); rl.close(); return; }

        const parts = trimmed.split(' ');
        const command = parts[0].toLowerCase();
        const args = parts.slice(1);
        let payload: any = {};

        // Simple payload mapping based on command
        try {
            switch (command) {
                case 'add': payload = { text: args.join(' ') }; break;
                case 'respond': payload = { promptId: args[0], text: args.slice(1).join(' ') }; break;
                case 'info': case 'view': case 'delete': payload = { idPrefix: args[0] }; break; // 'view' might be client-side only
                case 'tag': case 'untag': payload = { idPrefix: args[0], tag: args[1] }; break;
                case 'search': payload = { query: args.join(' '), k: 5 }; break; // Add default k
                case 'get_thoughts': payload = args[0] ? { rootId: args[0] } : {}; break;
                // Commands with no payload: run, pause, step, save, quit, get_status, get_rules, tools, help, ping
                default: payload = {};
            }
            // Validate minimal payload requirements
            if ((command === 'add' && !payload.text) ||
                (['respond', 'tag', 'untag'].includes(command) && (!payload.idPrefix || !payload.tag) && command !== 'respond') ||
                (command === 'respond' && (!payload.promptId || !payload.text)) ||
                (['info', 'delete', 'get_thoughts'].includes(command) && args.length > 0 && !payload.idPrefix && command!=='get_thoughts') ||
                (command === 'search' && !payload.query)) {
                console.log(chalk.yellow(`Usage error for command: ${command}. Use 'help' on server or check client parser.`));
                rl.prompt();
                return;
            }

            console.log(chalk.dim(`Sending: ${command}`), payload);
            const result = await sendCommand(command, payload);
            console.log(chalk.cyan(`Result (${command}):`), result ?? 'OK');
        } catch (error: any) {
            console.error(chalk.red(`Command Error (${command}):`), error.message);
        }
        // rl.prompt(); // Prompt is handled after response/error comes back
    });

    rl.on('close', () => {
        console.log(chalk.yellow('\nREPL Client exiting.'));
        if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) ws.close();
        process.exit(0);
    });
}


// --- Main Execution ---

async function main() {
    // Simple arg parsing: 'node script.js server' or 'node script.js client'
    const mode = process.argv[2] || 'server'; // Default to server mode

    if (mode === 'server') {
        const system = new FlowMindSystem();
        const handleShutdown = async (signal: string) => { console.log(chalk.yellow(`\n${signal} received. Shutting down...`)); await system.shutdown(); };
        process.on('SIGINT', () => handleShutdown('SIGINT'));
        process.on('SIGTERM', () => handleShutdown('SIGTERM'));
        process.on('uncaughtException', async (error, origin) => {
            console.error(chalk.red.bold('\n--- UNCAUGHT EXCEPTION ---\n'), error, `\nOrigin: ${origin}`, chalk.red.bold('\n--------------------------'));
            try { await system.shutdown(); } catch { process.exit(1); } // Attempt graceful shutdown
        });
        process.on('unhandledRejection', async (reason, promise) => {
            console.error(chalk.red.bold('\n--- UNHANDLED REJECTION ---\nReason:'), reason, chalk.red.bold('\nPromise:'), promise, chalk.red.bold('\n---------------------------'));
            try { await system.shutdown(); } catch { process.exit(1); } // Attempt graceful shutdown
        });

        try { await system.initialize(); }
        catch (error) { console.error(chalk.red.bold("Critical initialization error:"), error); process.exit(1); }

    } else if (mode === 'client') {
        const serverUrl = process.argv[3] || `ws://localhost:${WS_PORT}`;
        startReplClient(serverUrl);
    } else {
        console.error(chalk.red(`Invalid mode: ${mode}. Use 'server' or 'client [url]'`));
        process.exit(1);
    }
}

// Start the main function
main();