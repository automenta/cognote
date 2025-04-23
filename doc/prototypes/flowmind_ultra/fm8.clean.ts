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
const OLLAMA_EMBEDDING_MODEL = process.env.OLLAMA_EMBED_MODEL || OLLAMA_MODEL; // Use OLLAMA_EMBED_MODEL if set, else fallback to OLLAMA_MODEL
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
    promptId?: string;
    tags?: string[];
    feedback?: any[];
    embedded?: string;
    suggestions?: string[];
    retries?: number;
    waitingFor?: string;
    responseTo?: string;
    provenance?: string;
    [key: string]: any;
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
    id: string;
    embedding?: number[];
    content: string;
    metadata: { created: string; type: string; sourceId: string; };
    score?: number;
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

interface SystemControl {
    startProcessing: () => void;
    pauseProcessing: () => void;
    stepProcessing: () => Promise<boolean>;
    shutdown: () => Promise<void>;
    getStatus: () => { isRunning: boolean };
}

// --- Utilities ---
const generateId = (): string => uuidv4();
const shortId = (id: string | undefined): string => id ? id.substring(0, SHORT_ID_LEN) : '------';

function debounce<T extends (...args: any[]) => any>(func: T, wait: number): T & { cancel: () => void; flush: () => void; } {
    let timeout: NodeJS.Timeout | null = null;
    let lastArgs: Parameters<T> | null = null;
    let lastThis: any = null;
    let trailingCallScheduled = false;

    const debounced = (...args: Parameters<T>): void => {
        lastArgs = args;
        lastThis = this;
        trailingCallScheduled = true;
        if (!timeout) {
            timeout = setTimeout(later, wait);
        }
    };

    const later = () => {
        timeout = null;
        if (trailingCallScheduled && lastArgs) {
            func.apply(lastThis, lastArgs);
            trailingCallScheduled = false;
            lastArgs = null;
            lastThis = null;
        }
    };

    debounced.cancel = () => {
        if (timeout) clearTimeout(timeout);
        timeout = null;
        trailingCallScheduled = false;
        lastArgs = null;
        lastThis = null;
    };

    debounced.flush = () => {
        if (timeout) clearTimeout(timeout);
        later(); // Execute immediately
    };

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

    score(): number { return (this.pos + 1) / (this.pos + this.neg + 2); }
    update(success: boolean): void { success ? this.pos++ : this.neg++; }
    toJSON(): BeliefData { return { pos: this.pos, neg: this.neg }; }
    static fromJSON(data: BeliefData): Belief { return new Belief(data?.pos ?? DEFAULT_BELIEF_POS, data?.neg ?? DEFAULT_BELIEF_NEG); }
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
            default: const exhaustiveCheck: never = term; return c(chalk.red, 'invalid_term');
        }
    }

    export function toString(term: Term): string {
        switch (term.kind) {
            case 'Atom': return term.name;
            case 'Variable': return `?${term.name}`;
            case 'Structure': return `${term.name}(${term.args.map(toString).join(',')})`; // Simplified for non-display contexts
            case 'ListTerm': return `[${term.elements.map(toString).join(',')}]`;
            default: const exhaustiveCheck: never = term; return '';
        }
    }

    export function fromString(input: string): Term {
        input = input.trim();
        if (input.startsWith('?') && input.length > 1 && !input.includes(' ')) return Variable(input.substring(1));
        const structMatch = input.match(/^([a-zA-Z0-9_]+)\((.*)\)$/);
        if (structMatch) {
            const name = structMatch[1];
            const argsStr = structMatch[2];
            // Basic split, doesn't handle nested structures/lists/commas in atoms correctly
            const args = argsStr ? argsStr.split(',').map(s => fromString(s.trim())) : [];
            return Structure(name, args);
        }
        const listMatch = input.match(/^\[(.*)\]$/);
        if (listMatch) {
            const elementsStr = listMatch[1];
            const elements = elementsStr ? elementsStr.split(',').map(s => fromString(s.trim())) : [];
            return List(elements);
        }
        return Atom(input);
    }

    export function unify(term1: Term, term2: Term, bindings: Map<string, Term> = new Map()): Map<string, Term> | null {
        const resolve = (term: Term, currentBindings: Map<string, Term>): Term => {
            while (term.kind === 'Variable' && currentBindings.has(term.name)) {
                const bound = currentBindings.get(term.name)!;
                if (bound === term) return term; term = bound;
            }
            return term;
        };

        const t1 = resolve(term1, bindings);
        const t2 = resolve(term2, bindings);

        const occursCheck = (varName: string, termToCheck: Term): boolean => {
            if (termToCheck.kind === 'Variable') return varName === termToCheck.name;
            if (termToCheck.kind === 'Structure') return termToCheck.args.some(arg => occursCheck(varName, arg));
            if (termToCheck.kind === 'ListTerm') return termToCheck.elements.some(el => occursCheck(varName, el));
            return false;
        };

        if (t1.kind === 'Variable') {
            if (t1.name === (t2 as Variable).name && t1.kind === t2.kind) return bindings;
            if (occursCheck(t1.name, t2)) return null;
            const newBindings = new Map(bindings); newBindings.set(t1.name, t2); return newBindings;
        }
        if (t2.kind === 'Variable') { // Symmetric case handled by t1 check after potential var swap
            if (occursCheck(t2.name, t1)) return null;
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
            default: const exhaustiveCheck: never = t1; return null;
        }
    }

    export function substitute(term: Term, bindings: Map<string, Term>): Term {
        if (term.kind === 'Variable' && bindings.has(term.name)) {
            return substitute(bindings.get(term.name)!, bindings);
        }
        if (term.kind === 'Structure') {
            return { ...term, args: term.args.map(arg => substitute(arg, bindings)) };
        }
        if (term.kind === 'ListTerm') {
            return { ...term, elements: term.elements.map(el => substitute(el, bindings)) };
        }
        return term;
    }

    export function jsonToTerm(data: any): Term | null {
        if (data === null || typeof data !== 'object') return null;
        if (Array.isArray(data)) {
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
            default:
                const objArgs = Object.entries(data)
                    .map(([k, v]) => Atom(`${k}:${JSON.stringify(v)}`));
                return Structure("json_object", objArgs);
        }
    }
}

abstract class BaseStore<T extends { id: string, metadata?: { created?: string, modified?: string } }> {
    protected items = new Map<string, T>();
    protected listeners: Set<() => void> = new Set();
    protected changedItems: Set<string> = new Set();
    protected deletedItems: Set<string> = new Set();

    add(item: T): void {
        const newItem = {
            ...item,
            metadata: { ...(item.metadata ?? {}), created: item.metadata?.created ?? new Date().toISOString() }
        };
        this.items.set(item.id, newItem);
        this.changedItems.add(item.id);
        this.deletedItems.delete(item.id);
        this.notifyChange();
    }

    get(id: string): T | undefined { return this.items.get(id); }

    update(item: T): void {
        const existing = this.items.get(item.id);
        if (existing) {
            const updatedItem = {
                ...item,
                metadata: {
                    ...(existing.metadata ?? {}),
                    ...(item.metadata ?? {}),
                    modified: new Date().toISOString()
                }
            };
            this.items.set(item.id, updatedItem);
            this.changedItems.add(item.id);
            this.deletedItems.delete(item.id); // Ensure not marked deleted if updated
            this.notifyChange();
        } else {
            this.add(item); // Add if not existing
        }
    }

    delete(id: string): boolean {
        if (this.items.has(id)) {
            this.items.delete(id);
            this.changedItems.delete(id);
            this.deletedItems.add(id);
            this.notifyChange();
            return true;
        }
        return false;
    }

    getAll(): T[] { return Array.from(this.items.values()); }
    count(): number { return this.items.size; }

    findItemByPrefix(prefix: string): T | undefined {
        if (!prefix || prefix.length < 3) return undefined;
        if (this.items.has(prefix)) return this.items.get(prefix);
        const matching = this.getAll().filter(item => item.id.startsWith(prefix));
        return matching.length === 1 ? matching[0] : undefined;
    }

    addChangeListener(listener: () => void): void { this.listeners.add(listener); }
    removeChangeListener(listener: () => void): void { this.listeners.delete(listener); }

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
        const queue = [rootId];
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
            const { belief, ...rest } = thoughtData;
            this.add({ ...rest, belief: Belief.fromJSON(belief), id });
        });
        this.changedItems.clear();
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
            const { belief, ...rest } = ruleData;
            this.add({ ...rest, belief: Belief.fromJSON(belief), id });
        });
        this.changedItems.clear();
        this.deletedItems.clear();
    }
}

class MemoryStore {
    private vectorStore: FaissStore | null = null;
    private embeddings: Embeddings;
    private storePath: string;
    public isReady = false;

    constructor(embeddings: Embeddings, storePath: string) {
        this.embeddings = embeddings;
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
                    const dummyDoc = new Document({ pageContent: "Initial sentinel document", metadata: { sourceId: "init", type: "SYSTEM", created: new Date().toISOString(), id: "init_doc" } });
                    this.vectorStore = await FaissStore.fromDocuments([dummyDoc], this.embeddings);
                    await this.save();
                    this.isReady = true;
                    console.log(chalk.green(`New vector store created at ${this.storePath}`));
                } catch (initError) { console.error(chalk.red('Failed to initialize new vector store:'), initError); }
            } else { console.error(chalk.red('Failed to load vector store:'), error); }
        }
    }

    async add(entry: Omit<MemoryEntry, 'embedding' | 'score'>): Promise<void> {
        if (!this.vectorStore || !this.isReady) { console.warn(chalk.yellow('MemoryStore not ready, cannot add entry.')); return; }
        const docMetadata = { ...entry.metadata, id: entry.id, _docId: generateId() };
        const doc = new Document({ pageContent: entry.content, metadata: docMetadata });
        try {
            await this.vectorStore.addDocuments([doc]);
            await this.save(); // Save immediately after adding
        } catch (error) { console.error(chalk.red('Failed to add document to vector store:'), error); }
    }

    async search(query: string, k: number = 5): Promise<MemoryEntry[]> {
        if (!this.vectorStore || !this.isReady) { console.warn(chalk.yellow('MemoryStore not ready, cannot search.')); return []; }
        try {
            const results = await this.vectorStore.similaritySearchWithScore(query, k);
            return results
                .filter(([doc]) => doc.metadata.sourceId !== 'init')
                .map(([doc, score]): MemoryEntry => ({
                    id: doc.metadata.id ?? generateId(),
                    content: doc.pageContent,
                    metadata: {
                        created: doc.metadata.created ?? new Date().toISOString(),
                        type: doc.metadata.type ?? 'UNKNOWN',
                        sourceId: doc.metadata.sourceId ?? 'UNKNOWN'
                    },
                    score: score,
                    embedding: [] // Embeddings are not stored/returned by default
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

abstract class BaseTool implements Tool {
    abstract name: string;
    abstract description: string;
    abstract execute(actionTerm: Structure, context: ToolContext, trigger: Thought): Promise<Term | null>;

    protected createErrorAtom(code: string, message?: string): Atom {
        const fullMessage = `${this.name}_${code}${message ? `:${message.substring(0, 50)}` : ''}`;
        return TermLogic.Atom(`error:${fullMessage}`);
    }

    protected createOkAtom(message: string): Atom {
        return TermLogic.Atom(`ok:${message}`);
    }
}

class LLMTool extends BaseTool {
    name = "LLMTool";
    description = "Interacts with the LLM for text generation or embedding. Use generate(prompt) or embed(text).";

    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operationAtom = action.args[0];
        const inputTerm = action.args[1];
        if (operationAtom?.kind !== 'Atom' || !inputTerm) return this.createErrorAtom("invalid_params");
        const operation = operationAtom.name;
        const inputText = TermLogic.toString(inputTerm);

        switch (operation) {
            case 'generate': {
                const response = await context.llm.generate(inputText);
                if (response.startsWith('Error:')) return this.createErrorAtom("generation_failed", response);
                try {
                    const termFromJson = TermLogic.jsonToTerm(JSON.parse(response));
                    if (termFromJson) return termFromJson;
                } catch { /* Ignore JSON parse error, fall back to Atom */ }
                return TermLogic.Atom(response); // Return raw response as Atom if not valid JSON Term
            }
            case 'embed': {
                const embedding = await context.llm.embed(inputText);
                return embedding.length > 0 ? this.createOkAtom("embedded") : this.createErrorAtom("embedding_failed");
            }
            default: return this.createErrorAtom("unsupported_operation", operation);
        }
    }
}

class MemoryTool extends BaseTool {
    name = "MemoryTool";
    description = "Manages vector memory: add(content_term), search(query_term, k?).";
    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operationAtom = action.args[0];
        if (operationAtom?.kind !== 'Atom') return this.createErrorAtom("missing_operation");
        const operation = operationAtom.name;

        switch (operation) {
            case 'add': {
                const contentTerm = action.args[1];
                if (!contentTerm) return this.createErrorAtom("missing_add_content");
                await context.memory.add({
                    id: generateId(),
                    content: TermLogic.toString(contentTerm),
                    metadata: { created: new Date().toISOString(), type: trigger.type, sourceId: trigger.id }
                });
                trigger.metadata.embedded = new Date().toISOString();
                context.thoughts.update(trigger);
                return this.createOkAtom(`memory_added:${shortId(trigger.id)}`);
            }
            case 'search': {
                const queryTerm = action.args[1];
                const kTerm = action.args[2];
                if (!queryTerm) return this.createErrorAtom("missing_search_query");
                const queryStr = TermLogic.toString(queryTerm);
                const k = (kTerm?.kind === 'Atom' && /^\d+$/.test(kTerm.name)) ? parseInt(kTerm.name, 10) : 3;
                const results = await context.memory.search(queryStr, k);
                return TermLogic.List(results.map(r => TermLogic.Atom(r.content)));
            }
            default: return this.createErrorAtom("unsupported_operation", operation);
        }
    }
}

class UserInteractionTool extends BaseTool {
    name = "UserInteractionTool";
    description = "Requests input from the user: prompt(prompt_text_term).";
    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operationAtom = action.args[0];
        const promptTextTerm = action.args[1];
        if (operationAtom?.kind !== 'Atom' || operationAtom.name !== 'prompt' || !promptTextTerm) {
            return this.createErrorAtom("invalid_params");
        }

        const promptText = TermLogic.toString(promptTextTerm);
        const promptId = generateId();

        const promptThought: Thought = {
            id: promptId,
            type: Type.USER_PROMPT,
            content: TermLogic.Atom(promptText),
            belief: Belief.DEFAULT,
            status: Status.PENDING,
            metadata: {
                rootId: trigger.metadata.rootId ?? trigger.id,
                parentId: trigger.id,
                created: new Date().toISOString(),
                promptId: promptId,
                provenance: this.name,
            }
        };
        context.engine.addThought(promptThought);

        trigger.status = Status.WAITING;
        trigger.metadata.waitingFor = promptId;
        context.thoughts.update(trigger);

        return this.createOkAtom(`prompt_requested:${promptId}`);
    }
}

class GoalProposalTool extends BaseTool {
    name = "GoalProposalTool";
    description = "Suggests new goals based on context: suggest(context_term?). Uses trigger content if no term provided.";
    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operationAtom = action.args[0];
        if (operationAtom?.kind !== 'Atom' || operationAtom.name !== 'suggest') {
            return this.createErrorAtom("invalid_params");
        }

        const contextTerm = action.args[1] ?? trigger.content;
        const contextStr = TermLogic.toString(contextTerm);
        const memoryResults = await context.memory.search(`Relevant past goals or outcomes related to: ${contextStr}`, 3);
        const memoryContext = memoryResults.length > 0
            ? `\nRelated past activities:\n - ${memoryResults.map(r => r.content).join("\n - ")}`
            : "";
        const prompt = `Based on the current context "${contextStr}"${memoryContext}\n\nSuggest ONE concise, actionable next goal or task. Output only the suggested goal text.`;

        const suggestionText = await context.llm.generate(prompt);
        if (!suggestionText || suggestionText.startsWith('Error:')) {
            return this.createErrorAtom("llm_failed", suggestionText);
        }

        const suggestionThought: Thought = {
            id: generateId(),
            type: Type.GOAL,
            content: TermLogic.Atom(suggestionText),
            belief: new Belief(1, 0),
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
        return this.createOkAtom(`suggestion_created:${shortId(suggestionThought.id)}`);
    }
}

class CoreTool extends BaseTool {
    name = "CoreTool";
    description = "Manages internal state: set_status(target_id_atom, status_atom), add_thought(type_atom, content_term, root_id?, parent_id?), delete_thought(target_id_atom).";
    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operationAtom = action.args[0];
        if (operationAtom?.kind !== 'Atom') return this.createErrorAtom("missing_operation");
        const operation = operationAtom.name;

        const resolveTargetId = (term: Term | undefined, fallbackId: string): string | null => {
            if (term?.kind !== 'Atom') return null;
            return term.name === 'self' ? fallbackId : term.name;
        };

        switch (operation) {
            case 'set_status': {
                const targetIdStr = resolveTargetId(action.args[1], trigger.id);
                const newStatusTerm = action.args[2];
                if (!targetIdStr || newStatusTerm?.kind !== 'Atom') return this.createErrorAtom("invalid_set_status_params");
                const newStatus = newStatusTerm.name.toUpperCase() as Status;
                if (!Object.values(Status).includes(newStatus)) return this.createErrorAtom("invalid_status_value", newStatusTerm.name);

                const targetThought = context.thoughts.findThought(targetIdStr);
                if (!targetThought) return this.createErrorAtom("target_not_found", targetIdStr);

                targetThought.status = newStatus;
                context.thoughts.update(targetThought);
                return this.createOkAtom(`status_set:${shortId(targetThought.id)}_to_${newStatus}`);
            }
            case 'add_thought': {
                const typeTerm = action.args[1]; const contentTerm = action.args[2];
                const rootIdTerm = action.args[3]; const parentIdTerm = action.args[4];
                if (typeTerm?.kind !== 'Atom' || !contentTerm) return this.createErrorAtom("invalid_add_thought_params");
                const type = typeTerm.name.toUpperCase() as Type;
                if (!Object.values(Type).includes(type)) return this.createErrorAtom("invalid_thought_type", typeTerm.name);

                const parentId = resolveTargetId(parentIdTerm, trigger.id) ?? trigger.id;
                const parentThought = context.thoughts.get(parentId);
                const rootId = resolveTargetId(rootIdTerm, parentThought?.metadata.rootId ?? parentId) ?? parentThought?.metadata.rootId ?? parentId;

                const newThought: Thought = {
                    id: generateId(), type, content: contentTerm, belief: Belief.DEFAULT, status: Status.PENDING,
                    metadata: {
                        rootId: rootId, parentId: parentId,
                        created: new Date().toISOString(), provenance: `${this.name} (rule ${shortId(trigger.metadata.ruleId)} on ${shortId(trigger.id)})`
                    }
                };
                context.engine.addThought(newThought);
                return this.createOkAtom(`thought_added:${shortId(newThought.id)}`);
            }
            case 'delete_thought': {
                const targetIdStr = resolveTargetId(action.args[1], trigger.id);
                if (!targetIdStr) return this.createErrorAtom("invalid_delete_thought_params");
                const targetThought = context.thoughts.findThought(targetIdStr);
                if (!targetThought) return this.createErrorAtom("target_not_found", targetIdStr);

                const deleted = context.thoughts.delete(targetThought.id);
                return deleted ? this.createOkAtom(`thought_deleted:${shortId(targetThought.id)}`) : this.createErrorAtom("delete_failed", targetIdStr);
            }
            default: return this.createErrorAtom("unsupported_operation", operation);
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
    private batchSize: number = 5;
    private maxConcurrent: number = 3;
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

        const weights = candidates.map(t => Math.max(0.01, t.metadata.priority ?? t.belief.score()));
        const totalWeight = weights.reduce((sum, w) => sum + w, 0);

        if (totalWeight <= 0) return candidates[Math.floor(Math.random() * candidates.length)];

        let random = Math.random() * totalWeight;
        for (let i = 0; i < candidates.length; i++) {
            random -= weights[i];
            if (random <= 0) return candidates[i];
        }
        return candidates[candidates.length - 1];
    }

    private findAndSelectRule(thought: Thought): { rule: Rule; bindings: Map<string, Term> } | null {
        const applicableMatches = this.rules.getAll()
            .map(rule => ({ rule, bindings: TermLogic.unify(rule.pattern, thought.content) }))
            .filter(m => m.bindings !== null) as { rule: Rule; bindings: Map<string, Term> }[];

        if (applicableMatches.length === 0) return null;
        if (applicableMatches.length === 1) return applicableMatches[0];

        const weights = applicableMatches.map(m => Math.max(0.01, m.rule.belief.score()));
        const totalWeight = weights.reduce((sum, w) => sum + w, 0);

        if (totalWeight <= 0) return applicableMatches[Math.floor(Math.random() * applicableMatches.length)];

        let random = Math.random() * totalWeight;
        for (let i = 0; i < applicableMatches.length; i++) {
            random -= weights[i];
            if (random <= 0) return applicableMatches[i];
        }
        return applicableMatches[applicableMatches.length - 1];
    }

    private async executeAction(thought: Thought, rule: Rule, bindings: Map<string, Term>): Promise<boolean> {
        const boundAction = TermLogic.substitute(rule.action, bindings);
        if (boundAction.kind !== 'Structure') {
            this.handleFailure(thought, rule, `Invalid action term kind: ${boundAction.kind}`);
            rule.belief.update(false); this.rules.update(rule); // Rule failed
            return false;
        }
        const tool = this.tools.get(boundAction.name);
        if (!tool) {
            this.handleFailure(thought, rule, `Tool not found: ${boundAction.name}`);
            rule.belief.update(false); this.rules.update(rule); // Rule failed
            return false;
        }

        let actionCompletedSuccessfully = false;
        try {
            const resultTerm = await tool.execute(boundAction, this.context, thought);
            const currentThoughtState = this.thoughts.get(thought.id); // Re-fetch state

            if (!currentThoughtState) { // Thought might have been deleted by the tool
                console.warn(chalk.yellow(`Thought ${shortId(thought.id)} deleted during action execution.`));
                rule.belief.update(true); // Assume deletion implies rule success? Or specific result needed? Assume success for now.
                this.rules.update(rule);
                return true; // Consider deletion as a form of completion
            }

            const isWaiting = currentThoughtState.status === Status.WAITING;
            const isFailed = currentThoughtState.status === Status.FAILED;
            const returnedError = resultTerm?.kind === 'Atom' && resultTerm.name.startsWith('error:');

            if (returnedError) {
                this.handleFailure(thought, rule, `Tool execution failed: ${resultTerm.name}`);
                actionCompletedSuccessfully = false;
            } else if (isWaiting) {
                // Action initiated waiting state, consider rule successful in initiating.
                actionCompletedSuccessfully = true;
            } else if (isFailed) {
                // Tool explicitly set thought to FAILED.
                actionCompletedSuccessfully = false;
            } else {
                // No error, not waiting, not failed -> assume successful completion by tool.
                this.handleSuccess(thought, rule);
                actionCompletedSuccessfully = true;
            }

        } catch (error: any) {
            console.error(chalk.red(`Tool exception ${tool.name} on ${shortId(thought.id)}:`), error);
            this.handleFailure(thought, rule, `Tool exception: ${error.message}`);
            actionCompletedSuccessfully = false; // Exception means failure
        }

        // Update rule belief based on whether the action *completed* successfully or failed
        // Don't penalize rule if it successfully put thought into WAITING state.
        rule.belief.update(actionCompletedSuccessfully);
        this.rules.update(rule);

        // Return true only if the thought reached a final DONE state during this execution
        return this.thoughts.get(thought.id)?.status === Status.DONE;
    }

    private async handleNoRuleMatch(thought: Thought): Promise<void> {
        let action: Term | null = null;
        let llmPrompt: string | null = null;
        let targetType: Type | null = null;

        switch (thought.type) {
            case Type.INPUT:
                llmPrompt = `Input: "${TermLogic.toString(thought.content)}". Define a specific, actionable GOAL. Output ONLY the goal text.`;
                targetType = Type.GOAL;
                break;
            case Type.GOAL:
                llmPrompt = `Goal: "${TermLogic.toString(thought.content)}". Outline 1-3 concrete STRATEGY steps. Output each step on a new line.`;
                targetType = Type.STRATEGY;
                break;
            case Type.STRATEGY:
                llmPrompt = `Strategy: "${TermLogic.toString(thought.content)}". Describe a likely concise OUTCOME. Output ONLY the outcome text.`;
                targetType = Type.OUTCOME;
                break;
            case Type.OUTCOME:
                action = TermLogic.Structure("MemoryTool", [TermLogic.Atom("add"), thought.content]);
                break;
            default:
                const askUserPrompt = `No rule found for ${shortId(thought.id)} (${thought.type}: ${TermLogic.toString(thought.content).substring(0, 50)}...). How to proceed?`;
                action = TermLogic.Structure("UserInteractionTool", [TermLogic.Atom("prompt"), TermLogic.Atom(askUserPrompt)]);
                break;
        }

        let fallbackSucceeded = false;
        if (llmPrompt && targetType) {
            const resultText = await this.llm.generate(llmPrompt);
            if (resultText && !resultText.startsWith('Error:')) {
                resultText.split('\n').map(s => s.trim().replace(/^- /, '')).filter(s => s.length > 0)
                    .forEach(resText => {
                        this.addThought({
                            id: generateId(), type: targetType!, content: TermLogic.Atom(resText),
                            belief: Belief.DEFAULT, status: Status.PENDING,
                            metadata: { rootId: thought.metadata.rootId ?? thought.id, parentId: thought.id, created: new Date().toISOString(), provenance: 'llm_fallback' }
                        });
                    });
                fallbackSucceeded = true;
            } else { this.handleFailure(thought, null, `LLM fallback failed: ${resultText}`); }
        } else if (action?.kind === 'Structure') {
            const tool = this.tools.get(action.name);
            if (tool) {
                try {
                    await tool.execute(action, this.context, thought);
                    const currentStatus = this.thoughts.get(thought.id)?.status;
                    // Fallback success if thought is now DONE or WAITING (implies tool initiated something)
                    fallbackSucceeded = currentStatus === Status.DONE || currentStatus === Status.WAITING;
                } catch (error: any) { this.handleFailure(thought, null, `Fallback tool fail (${action.name}): ${error.message}`); }
            } else { this.handleFailure(thought, null, `Fallback tool not found: ${action.name}`); }
        }

        // Mark original thought based on fallback outcome, only if it's still PENDING/ACTIVE
        const currentThought = this.thoughts.get(thought.id);
        if (currentThought && (currentThought.status === Status.PENDING || currentThought.status === Status.ACTIVE)) {
            if (fallbackSucceeded) {
                this.handleSuccess(thought, null); // Mark original done if fallback generated next steps/action
            } else if (!action && !llmPrompt) {
                this.handleFailure(thought, null, "No rule match and no fallback action defined.");
            }
            // If fallback failed explicitly, handleFailure was already called.
        }
    }

    private handleFailure(thought: Thought, rule: Rule | null, errorInfo: string): void {
        const currentThought = this.thoughts.get(thought.id);
        if (!currentThought || currentThought.status === Status.FAILED) return;

        const retries = (currentThought.metadata.retries ?? 0) + 1;
        currentThought.status = retries >= MAX_RETRIES ? Status.FAILED : Status.PENDING;
        currentThought.metadata.error = errorInfo.substring(0, 250);
        currentThought.metadata.retries = retries;
        if (rule) currentThought.metadata.ruleId = rule.id;
        // Do not update thought belief here, let the calling context decide based on action outcome vs. thought failure.
        // Thought belief reflects internal state/history, rule belief reflects rule effectiveness.
        this.thoughts.update(currentThought);
        console.warn(chalk.yellow(`Thought ${shortId(currentThought.id)} failed (Attempt ${retries}): ${errorInfo}`));
    }

    private handleSuccess(thought: Thought, rule: Rule | null): void {
        const currentThought = this.thoughts.get(thought.id);
        if (!currentThought || currentThought.status === Status.DONE) return;

        currentThought.status = Status.DONE;
        delete currentThought.metadata.error;
        delete currentThought.metadata.retries;
        delete currentThought.metadata.waitingFor; // Clear waiting status if somehow succeeded while waiting
        if (rule) currentThought.metadata.ruleId = rule.id;
        // Do not update thought belief here. Belief updates based on its own history/feedback, not just rule success.
        this.thoughts.update(currentThought);
    }

    private async _processThought(thought: Thought): Promise<boolean> {
        try {
            thought.status = Status.ACTIVE;
            thought.metadata.agentId = 'worker';
            this.thoughts.update(thought);

            const match = this.findAndSelectRule(thought);
            let processedSuccessfully = false;
            if (match) {
                processedSuccessfully = await this.executeAction(thought, match.rule, match.bindings);
            } else {
                await this.handleNoRuleMatch(thought);
                // Check status after fallback: Success if DONE or WAITING
                const finalStatus = this.thoughts.get(thought.id)?.status;
                processedSuccessfully = finalStatus === Status.DONE || finalStatus === Status.WAITING;
            }
            return processedSuccessfully;

        } catch (error: any) {
            console.error(chalk.red(`Critical error processing ${shortId(thought.id)}:`), error);
            this.handleFailure(thought, null, `Unhandled processing exception: ${error.message}`);
            return false;
        } finally {
            this.activeIds.delete(thought.id);
            const finalThoughtState = this.thoughts.get(thought.id);
            if (finalThoughtState?.status === Status.ACTIVE) {
                console.warn(chalk.yellow(`Thought ${shortId(thought.id)} ended ACTIVE. Setting FAILED.`));
                this.handleFailure(finalThoughtState, null, "Processing ended while ACTIVE.");
            }
        }
    }

    async processSingleThought(): Promise<boolean> {
        const thought = this.sampleThought();
        if (!thought || this.activeIds.has(thought.id)) return false;

        this.activeIds.add(thought.id);
        return this._processThought(thought);
    }

    async processBatch(): Promise<number> {
        const promises: Promise<boolean>[] = [];
        let acquiredCount = 0;

        while (this.activeIds.size < this.maxConcurrent && acquiredCount < this.batchSize) {
            const thought = this.sampleThought();
            if (!thought) break;
            this.activeIds.add(thought.id);
            promises.push(this._processThought(thought));
            acquiredCount++;
        }

        if (promises.length === 0) return 0;

        const results = await Promise.all(promises);
        // Count how many thoughts *ended* processing in this batch (not necessarily succeeded)
        return promises.length;
    }

    async handlePromptResponse(promptId: string, responseText: string): Promise<boolean> {
        const promptThought = this.thoughts.findPendingPrompt(promptId);
        if (!promptThought) { console.error(chalk.red(`Pending prompt thought for ID ${shortId(promptId)} not found.`)); return false; }

        const waitingThought = this.thoughts.findWaitingThought(promptId);
        if (!waitingThought) {
            console.warn(chalk.yellow(`No thought found waiting for prompt ${shortId(promptId)}. Marking prompt as done.`));
            promptThought.status = Status.DONE;
            promptThought.metadata.error = "No waiting thought found upon response.";
            this.thoughts.update(promptThought);
            return false;
        }

        const responseThought: Thought = {
            id: generateId(), type: Type.INPUT, content: TermLogic.Atom(responseText),
            belief: new Belief(1, 0), status: Status.PENDING,
            metadata: {
                rootId: waitingThought.metadata.rootId ?? waitingThought.id,
                parentId: waitingThought.id,
                created: new Date().toISOString(),
                responseTo: promptId,
                tags: ['user_response'],
                provenance: 'user_input'
            }
        };
        this.addThought(responseThought);

        promptThought.status = Status.DONE;
        this.thoughts.update(promptThought);

        waitingThought.status = Status.PENDING; // Reactivate
        delete waitingThought.metadata.waitingFor;
        waitingThought.belief.update(true); // Receiving response is positive progress
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
        await memory.save();
    } catch (error) { console.error(chalk.red('Error saving state:'), error); }
}

const debouncedSaveState = debounce(saveState, SAVE_DEBOUNCE);

async function loadState(thoughts: ThoughtStore, rules: RuleStore, memory: MemoryStore): Promise<void> {
    try {
        await fs.mkdir(DATA_DIR, { recursive: true });
        await memory.initialize();
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
        if (!memory.isReady) { await memory.initialize(); } // Retry init if load failed
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
        private systemControl: SystemControl
    ) {
        this.registerCommandHandlers();
        this.thoughts.addChangeListener(this.broadcastThoughtChanges.bind(this));
        this.rules.addChangeListener(this.broadcastRuleChanges.bind(this));
    }

    start(): void {
        this.wss = new WebSocketServer({ port: this.port });
        this.wss.on('listening', () => console.log(chalk.green(`🚀 FlowMind WebSocket API listening on ws://localhost:${this.port}`)));
        this.wss.on('connection', (ws, req) => this.handleConnection(ws, req));
        this.wss.on('error', (error) => console.error(chalk.red('WebSocket Server Error:'), error));
    }

    stop(): void {
        console.log(chalk.yellow('Stopping WebSocket API server...'));
        this.wss?.clients.forEach(ws => ws.close());
        this.wss?.close();
        this.clients.clear();
    }

    private handleConnection(ws: WebSocket, req: any): void {
        const ip = req.socket.remoteAddress || 'unknown';
        console.log(chalk.blue(`Client connected: ${ip}`));
        this.clients.set(ws, { ip });

        ws.on('message', (message) => this.handleMessage(ws, message as Buffer));
        ws.on('close', () => this.handleDisconnection(ws));
        ws.on('error', (error) => { console.error(chalk.red(`WebSocket error from ${ip}:`), error); this.handleDisconnection(ws); });

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
            if (typeof request.command !== 'string') throw new Error("Invalid command format: missing 'command' string.");
        } catch (e: any) {
            this.send(ws, { type: 'error', payload: `Invalid JSON message format: ${e.message}`, requestId: request?.requestId });
            return;
        }

        const handler = this.commandHandlers.get(request.command);
        if (handler) {
            try {
                const result = await handler(request.payload ?? {}, ws);
                this.send(ws, { type: 'response', payload: result ?? null, success: true, requestId: request.requestId });
                if (!['get_thoughts', 'get_rules', 'get_status', 'info', 'search', 'help', 'tools', 'ping'].includes(request.command)) {
                    debouncedSaveState(this.thoughts, this.rules, this.memory);
                }
            } catch (error: any) {
                console.error(chalk.red(`Error executing command "${request.command}"`), error.message);
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

    broadcast(data: any): void {
        const message = JSON.stringify(data);
        this.clients.forEach((_, ws) => {
            if (ws.readyState === WebSocket.OPEN) { ws.send(message); }
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
                thoughts: this.thoughts.getAll().map(t => ({...t, belief: t.belief.toJSON()})), // Serialize belief for snapshot
                rules: this.rules.getAll().map(r => ({...r, belief: r.belief.toJSON()})),
                status: this.systemControl.getStatus()
            }
        });
    }

    private registerCommandHandlers(): void {
        const handlers: { [key: string]: (payload: any, ws: WebSocket) => Promise<any | void> } = {
            add: async (payload) => {
                if (!payload.text) throw new Error("Missing 'text' in payload");
                const content = TermLogic.fromString(payload.text); // Attempt to parse input as Term
                const newThought: Thought = {
                    id: generateId(), type: Type.INPUT, content: content,
                    belief: new Belief(1, 0), status: Status.PENDING,
                    metadata: { created: new Date().toISOString(), tags: ['user_added'], provenance: 'ws_api' }
                };
                newThought.metadata.rootId = newThought.id;
                this.engine.addThought(newThought);
                return { id: newThought.id, message: `Added ${newThought.type}: ${shortId(newThought.id)}` };
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
                const processedCount = await this.engine.processBatch(); // Use batch processing for step
                return { processed: processedCount > 0, count: processedCount, message: processedCount > 0 ? `Step processed ${processedCount} thought(s).` : "Nothing to step." };
            },
            save: async () => {
                debouncedSaveState.flush();
                await saveState(this.thoughts, this.rules, this.memory); // Ensure final save
                return { message: "State saved." };
            },
            quit: async () => { await this.systemControl.shutdown(); },
            get_status: async () => this.systemControl.getStatus(),
            info: async (payload) => {
                if (!payload.idPrefix) throw new Error("Missing 'idPrefix' in payload");
                const thought = this.thoughts.findThought(payload.idPrefix);
                if (thought) return { type: 'thought', data: {...thought, belief: thought.belief.toJSON()} }; // Serialize belief
                const rule = this.rules.findRule(payload.idPrefix);
                if (rule) return { type: 'rule', data: {...rule, belief: rule.belief.toJSON()} }; // Serialize belief
                throw new Error(`ID prefix "${payload.idPrefix}" not found.`);
            },
            get_thoughts: async (payload) => this.thoughts.getAll().map(t => ({...t, belief: t.belief.toJSON()})), // Serialize belief
            get_rules: async () => this.rules.getAll().map(r => ({...r, belief: r.belief.toJSON()})), // Serialize belief
            tools: async () => this.tools.list().map(t => ({ name: t.name, description: t.description })),
            tag: async (payload) => {
                if (!payload.idPrefix || !payload.tag) throw new Error("Missing 'idPrefix' or 'tag' in payload");
                const thought = this.thoughts.findThought(payload.idPrefix);
                if (!thought) throw new Error(`Thought prefix "${payload.idPrefix}" not found.`);
                thought.metadata.tags = [...new Set([...(thought.metadata.tags || []), payload.tag])];
                this.thoughts.update(thought);
                return { id: thought.id, tags: thought.metadata.tags };
            },
            untag: async (payload) => {
                if (!payload.idPrefix || !payload.tag) throw new Error("Missing 'idPrefix' or 'tag' in payload");
                const thought = this.thoughts.findThought(payload.idPrefix);
                if (!thought?.metadata.tags) throw new Error(`Thought prefix "${payload.idPrefix}" not found or has no tags.`);
                thought.metadata.tags = thought.metadata.tags.filter(t => t !== payload.tag);
                this.thoughts.update(thought);
                return { id: thought.id, tags: thought.metadata.tags };
            },
            search: async (payload) => {
                if (!payload.query) throw new Error("Missing 'query' in payload");
                return this.memory.search(payload.query, typeof payload.k === 'number' ? payload.k : 5);
            },
            delete: async (payload) => {
                if (!payload.idPrefix) throw new Error("Missing 'idPrefix' in payload");
                const thought = this.thoughts.findThought(payload.idPrefix);
                if (thought) {
                    if (!this.thoughts.delete(thought.id)) throw new Error(`Failed to delete thought ${shortId(thought.id)}.`);
                    return { id: thought.id, message: `Thought ${shortId(thought.id)} deleted.` };
                }
                const rule = this.rules.findRule(payload.idPrefix);
                if (rule) {
                    if (!this.rules.delete(rule.id)) throw new Error(`Failed to delete rule ${shortId(rule.id)}.`);
                    return { id: rule.id, message: `Rule ${shortId(rule.id)} deleted.` };
                }
                throw new Error(`Item with prefix "${payload.idPrefix}" not found.`);
            },
            help: async () => ({
                description: "Available commands. Use 'info <idPrefix>' for details on thoughts/rules.",
                commands: [
                    { cmd: "add <text>", desc: "Add new INPUT thought." },
                    { cmd: "respond <promptId> <text>", desc: "Respond to a pending user prompt." },
                    { cmd: "run", desc: "Start processing loop." },
                    { cmd: "pause", desc: "Pause processing loop." },
                    { cmd: "step", desc: "Process one batch of thoughts." },
                    { cmd: "save", desc: "Force immediate state save." },
                    { cmd: "quit", desc: "Shutdown the server." },
                    { cmd: "get_status", desc: "Get processing loop status." },
                    { cmd: "info <idPrefix>", desc: "Get details of a thought or rule by ID prefix." },
                    { cmd: "get_thoughts", desc: "Get all thoughts." },
                    { cmd: "get_rules", desc: "Get all rules." },
                    { cmd: "tools", desc: "List available tools." },
                    { cmd: "tag <idPrefix> <tag>", desc: "Add a tag to a thought." },
                    { cmd: "untag <idPrefix> <tag>", desc: "Remove a tag from a thought." },
                    { cmd: "search <query> [k=5]", desc: "Search vector memory." },
                    { cmd: "delete <idPrefix>", desc: "Delete a thought or rule by ID prefix." },
                    { cmd: "ping", desc: "Check server connection." },
                    { cmd: "help", desc: "Show this help message." },
                ]
            }),
            ping: async () => "pong",
        };

        for (const [command, handler] of Object.entries(handlers)) {
            this.commandHandlers.set(command, handler.bind(this));
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
    private systemControl: SystemControl;

    constructor() {
        this.registerCoreTools();
        this.systemControl = {
            startProcessing: this.startProcessingLoop.bind(this),
            pauseProcessing: this.pauseProcessingLoop.bind(this),
            stepProcessing: this.engine.processBatch.bind(this.engine), // Use batch for step control
            shutdown: this.shutdown.bind(this),
            getStatus: () => ({ isRunning: this.isRunning })
        };
        this.apiServer = new WsApiServer( WS_PORT, this.thoughts, this.rules, this.memory, this.engine, this.tools, this.systemControl );
    }

    private registerCoreTools(): void {
        [new LLMTool(), new MemoryTool(), new UserInteractionTool(), new GoalProposalTool(), new CoreTool()]
            .forEach(t => this.tools.register(t));
    }

    private bootstrapRules(): void {
        if (this.rules.count() > 0) return;
        console.log(chalk.blue("Bootstrapping default rules..."));

        const createRule = (desc: string, pattern: Term, action: Term): Rule => ({
            id: generateId(), pattern, action, belief: Belief.DEFAULT,
            metadata: { description: desc, provenance: 'bootstrap', created: new Date().toISOString() }
        });

        const rulesToAdd = [
            // --- LLM-based Flow ---
            // Tries to parse LLM result as Term first, then adds thought using CoreTool
            createRule( "Input -> Generate Goal Text (LLM)",
                TermLogic.Variable("InputContent"), // Match any Atom/Structure/List content of INPUT type
                TermLogic.Structure("LLMTool", [TermLogic.Atom("generate"), TermLogic.Atom("Input: \"?InputContent\". Define a specific, actionable GOAL. Output ONLY the goal text.")])
            ),
            // This rule now specifically looks for the GOAL type thought to trigger strategy generation
            createRule( "Goal -> Generate Strategy Text (LLM)",
                TermLogic.Variable("GoalContent"),
                TermLogic.Structure("LLMTool", [TermLogic.Atom("generate"), TermLogic.Atom("Goal: \"?GoalContent\". Outline 1-3 concrete STRATEGY steps. Output each step on a new line.")])
            ),
            // This rule looks for STRATEGY type thought
            createRule( "Strategy -> Generate Outcome Text (LLM)",
                TermLogic.Variable("StrategyContent"),
                TermLogic.Structure("LLMTool", [TermLogic.Atom("generate"), TermLogic.Atom("Strategy step \"?StrategyContent\" attempted. Describe concise OUTCOME.")])
            ),

            // --- CoreTool for Thought Creation from LLM Output ---
            // These rules trigger on the *result* of the LLMTool (which is often an Atom containing text)
            // It assumes the preceding LLM call was successful and the calling thought is now DONE.
            // We need a way to link the new thought back correctly. Let's assume CoreTool handles parent/root.
            // Problem: How does CoreTool know the *type* of thought to create? The pattern only matches the text atom.
            // Solution: Modify LLM prompts to output a structure like `result(goal, "text")` or use metadata?
            // Alternative: Simpler rules that just add generic thoughts based on LLM output.
            // Let's try a simpler, less specific rule for now:
            createRule( "Any Text Atom -> Add 'SYSTEM' Thought (Fallback?)",
                TermLogic.Variable("ResultText"), // Matches any Atom (potential LLM output)
                TermLogic.Structure("CoreTool", [
                    TermLogic.Atom("add_thought"), TermLogic.Atom("SYSTEM"), // Default to SYSTEM type
                    TermLogic.Variable("ResultText"), TermLogic.Atom("self"), TermLogic.Atom("self") // Link to the atom thought itself
                ])
            ),
            // This generic rule is problematic. Let's revert to type-specific triggers, but maybe use different tools.
            // Revisit bootstrap rules - the original assumed LLMTool output would trigger specific CoreTool rules.

            // --- Direct Tool Usage ---
            createRule( "Outcome -> Add to Memory",
                TermLogic.Variable("OutcomeContent"),
                TermLogic.Structure("MemoryTool", [TermLogic.Atom("add"), TermLogic.Variable("OutcomeContent")])
            ),
            // Triggers *after* MemoryTool successfully adds (returns ok:memory_added:...)
            // Need a rule that matches the *result* of the MemoryTool add operation.
            // Let's assume MemoryTool sets the Outcome thought to DONE. We need a rule triggered by Outcome itself.
            createRule( "Outcome -> Suggest Next Goal",
                TermLogic.Variable("OutcomeContent"), // Re-match outcome (assumes it might become PENDING again or rule priority handles it)
                TermLogic.Structure("GoalProposalTool", [TermLogic.Atom("suggest"), TermLogic.Variable("OutcomeContent")])
            ),
        ];

        // Filter rules that directly map Type -> LLM call, as LLMTool handles this implicitly via prompts now.
        // Focus on rules that process results or trigger standard actions.
        const finalRules = [
            createRule( "Outcome -> Add to Memory",
                TermLogic.Structure(Type.OUTCOME, [TermLogic.Variable("OutcomeContent")]),
                TermLogic.Structure("MemoryTool", [TermLogic.Atom("add"), TermLogic.Variable("OutcomeContent")])
            ),
            createRule( "Outcome -> Suggest Next Goal (after memory add)",
                TermLogic.Structure(Type.OUTCOME, [TermLogic.Variable("OutcomeContent")]), // This needs careful thought triggering/priority
                TermLogic.Structure("GoalProposalTool", [TermLogic.Atom("suggest"), TermLogic.Variable("OutcomeContent")])
            ),
            // Rule to explicitly ask user if an INPUT is unclear
            createRule( "Unclear Input -> Ask User",
                TermLogic.Structure(Type.INPUT, [TermLogic.Atom("unclear")]), // Example pattern for unclear input
                TermLogic.Structure("UserInteractionTool", [TermLogic.Atom("prompt"), TermLogic.Atom("The input 'unclear' was received. Please clarify.")])
            ),
        ];


        finalRules.forEach(rule => this.rules.add(rule));
        console.log(chalk.green(`${this.rules.count()} default rules added.`));
        // Note: The default rules might need significant refinement based on actual LLM outputs and desired flow.
        // The original bootstrap rules had a potential flaw in chaining LLM output text to CoreTool add_thought calls without clear type specification.
        // The engine's fallback mechanism (`handleNoRuleMatch`) now handles the basic Type -> LLM generation flow.
        // These rules focus on post-outcome actions.
    }

    async initialize(): Promise<void> {
        console.log(chalk.cyan('Initializing FlowMind System...'));
        await loadState(this.thoughts, this.rules, this.memory);
        this.bootstrapRules();
        this.apiServer.start();
        console.log(chalk.green('FlowMind Initialized.') + chalk.yellow(" Paused. Use API 'run' command to start processing."));
    }

    startProcessingLoop(): void {
        if (this.isRunning) { console.log(chalk.yellow("Processing already running.")); return; }
        console.log(chalk.green('Starting processing loop...'));
        this.isRunning = true;
        this.apiServer.broadcast({ type: 'status_update', payload: this.systemControl.getStatus() });
        this.workerIntervalId = setInterval(async () => {
            if (!this.isRunning) return;
            try {
                if (await this.engine.processBatch() > 0) {
                    debouncedSaveState(this.thoughts, this.rules, this.memory);
                }
            } catch (error) { console.error(chalk.red('Error in processing loop:'), error); }
        }, WORKER_INTERVAL);
    }

    pauseProcessingLoop(): void {
        if (!this.isRunning) { console.log(chalk.yellow("Processing already paused.")); return; }
        console.log(chalk.yellow('Pausing processing loop...'));
        this.isRunning = false;
        this.apiServer.broadcast({ type: 'status_update', payload: this.systemControl.getStatus() });
        if (this.workerIntervalId) {
            clearInterval(this.workerIntervalId);
            this.workerIntervalId = null;
        }
        debouncedSaveState.flush();
        saveState(this.thoughts, this.rules, this.memory); // Save state immediately on pause
    }

    async shutdown(): Promise<void> {
        console.log(chalk.cyan('\nShutting down FlowMind System...'));
        this.pauseProcessingLoop();
        this.apiServer.stop();
        debouncedSaveState.cancel(); // Cancel any pending debounced save
        await saveState(this.thoughts, this.rules, this.memory); // Final immediate save
        console.log(chalk.green('FlowMind shutdown complete. Goodbye!'));
        await sleep(100); // Allow logs to flush
        process.exit(0);
    }
}

// --- REPL Client Example ---

async function startReplClient(serverUrl: string) {
    console.log(chalk.cyan(`Connecting to FlowMind server at ${serverUrl}...`));
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout, prompt: chalk.blueBright('FlowMind Client> ') });
    const ws = new WebSocket(serverUrl);
    let requestCounter = 0;
    const pendingRequests = new Map<string, { resolve: (value: any) => void, reject: (reason?: any) => void, command: string }>();

    const sendCommand = (command: string, payload: any): Promise<any> => {
        return new Promise((resolve, reject) => {
            const requestId = `req-${requestCounter++}`;
            pendingRequests.set(requestId, { resolve, reject, command });
            if (ws.readyState !== WebSocket.OPEN) {
                reject(new Error("WebSocket is not open."));
                pendingRequests.delete(requestId);
                return;
            }
            ws.send(JSON.stringify({ command, payload, requestId }));
            setTimeout(() => {
                if (pendingRequests.has(requestId)) {
                    reject(new Error(`Request ${requestId} (${command}) timed out`));
                    pendingRequests.delete(requestId);
                    rl.prompt(); // Re-prompt on timeout
                }
            }, 15000); // 15 second timeout
        });
    };

    ws.on('open', () => {
        console.log(chalk.green(`Connected! Type 'help' for commands.`));
        rl.prompt();
    });

    ws.on('message', (data) => {
        const message = safeJsonParse(data.toString(), null);
        if (!message) { console.log(chalk.yellow('Received non-JSON message:', data.toString().substring(0, 100))); return; }

        const { type, payload, success, requestId, ...rest } = message;

        if (requestId && pendingRequests.has(requestId)) {
            const reqInfo = pendingRequests.get(requestId)!;
            if (type === 'response') {
                if (success) reqInfo.resolve(payload);
                else reqInfo.reject(new Error(payload || `Command '${reqInfo.command}' failed.`));
            } else if (type === 'error') {
                reqInfo.reject(new Error(payload || `Server error for '${reqInfo.command}'.`));
            }
            pendingRequests.delete(requestId);
            rl.prompt(); // Re-prompt only after a request completes
        } else if (type === 'error') {
            console.error(chalk.red(`\nServer Error: ${payload}`));
            rl.prompt(); // Prompt if unsolicited error
        } else if (type === 'snapshot') {
            console.log(chalk.magenta('\n--- Received State Snapshot ---'));
            console.log(chalk.cyan(`Thoughts: ${payload.thoughts?.length ?? 0}, Rules: ${payload.rules?.length ?? 0}, Status: ${payload.status?.isRunning ? 'RUNNING' : 'PAUSED'}`));
        } else if (type === 'thoughts_delta') {
            if (payload.changed.length === 0 && payload.deleted.length === 0) return;
            process.stdout.write('\n'); // Newline before delta output
            if (payload.changed.length > 0) console.log(chalk.green(`Thoughts Updated: ${payload.changed.map((t: Thought) => `${shortId(t.id)}[${t.status}]`).join(', ')}`));
            if (payload.deleted.length > 0) console.log(chalk.red(`Thoughts Deleted: ${payload.deleted.map(shortId).join(', ')}`));
            rl.prompt(); // Prompt after showing delta
        } else if (type === 'rules_delta') {
            if (payload.changed.length === 0 && payload.deleted.length === 0) return;
            process.stdout.write('\n');
            if (payload.changed.length > 0) console.log(chalk.blue(`Rules Updated: ${payload.changed.map((r: Rule) => shortId(r.id)).join(', ')}`));
            if (payload.deleted.length > 0) console.log(chalk.yellow(`Rules Deleted: ${payload.deleted.map(shortId).join(', ')}`));
            rl.prompt();
        } else if (type === 'status_update') {
            process.stdout.write('\n');
            console.log(chalk.magenta(`System Status: ${payload.isRunning ? 'RUNNING' : 'PAUSED'}`));
            rl.prompt();
        } else {
            process.stdout.write('\n');
            console.log(chalk.gray(`Server Message [${type || 'unknown'}]:`), payload ?? rest);
            rl.prompt();
        }
    });

    ws.on('close', () => {
        console.log(chalk.red('\nDisconnected from server. Exiting.'));
        rl.close();
    });

    ws.on('error', (error) => {
        console.error(chalk.red(`\nConnection Error: ${error.message}. Exiting.`));
        rl.close();
    });

    rl.on('line', async (line) => {
        const trimmed = line.trim();
        if (!trimmed) { rl.prompt(); return; }
        if (trimmed === 'quit' || trimmed === 'exit') { rl.close(); return; } // Let rl.on('close') handle ws closure

        const parts = trimmed.match(/(?:[^\s"]+|"[^"]*")+/g) ?? []; // Split by space, respecting quotes
        const command = parts[0]?.toLowerCase();
        const args = parts.slice(1).map(arg => arg.startsWith('"') && arg.endsWith('"') ? arg.slice(1, -1) : arg); // Remove quotes

        if (!command) { rl.prompt(); return; }

        let payload: any = {};
        let valid = true;

        try {
            switch (command) {
                case 'add': if (args.length < 1) valid = false; else payload = { text: args.join(' ') }; break;
                case 'respond': if (args.length < 2) valid = false; else payload = { promptId: args[0], text: args.slice(1).join(' ') }; break;
                case 'info': case 'delete': if (args.length !== 1) valid = false; else payload = { idPrefix: args[0] }; break;
                case 'tag': case 'untag': if (args.length !== 2) valid = false; else payload = { idPrefix: args[0], tag: args[1] }; break;
                case 'search': if (args.length < 1) valid = false; else payload = { query: args.join(' '), k: 5 }; break; // Default k
                case 'get_thoughts': case 'get_rules': case 'run': case 'pause': case 'step': case 'save': case 'get_status': case 'tools': case 'help': case 'ping': if (args.length > 0) valid = false; else payload = {}; break;
                case 'quit': break; // Handled outside switch
                default: console.log(chalk.yellow(`Unknown command: ${command}. Type 'help'.`)); valid = false; break;
            }

            if (!valid) {
                console.log(chalk.yellow(`Invalid arguments for command: ${command}. Type 'help'.`));
                rl.prompt();
                return;
            }

            if (command === 'quit') { rl.close(); return; } // Special case handled by rl 'close'

            // console.log(chalk.dim(`Sending: ${command}`), payload); // Less verbose
            const result = await sendCommand(command, payload);

            if (command === 'help') {
                console.log(chalk.cyan(payload.description));
                payload.commands.forEach((c: { cmd: string, desc: string }) => console.log(`  ${chalk.blueBright(c.cmd)}: ${c.desc}`));
            } else if (result !== null && result !== undefined) {
                console.log(chalk.cyan(`Result:`), result);
            } else {
                console.log(chalk.green('OK')); // Indicate success for commands returning null/void
            }

        } catch (error: any) {
            console.error(chalk.red(`Error:`), error.message);
            // rl.prompt() will be called by the response handler's error path
        }
        // Prompt is handled by response/error handler now
    });

    rl.on('close', () => {
        console.log(chalk.yellow('\nREPL Client exiting.'));
        if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
            ws.close();
        }
        // Ensure process exits cleanly if server connection was already lost
        setTimeout(() => process.exit(0), 100);
    });
}


// --- Main Execution ---

async function main() {
    const mode = process.argv[2] || 'server';

    if (mode === 'server') {
        let system: FlowMindSystem | null = null;
        const handleShutdown = async (signal: string) => {
            console.log(chalk.yellow(`\n${signal} received. Shutting down...`));
            if (system) await system.shutdown(); else process.exit(0);
        };
        const handleException = async (context: string, error: any, origin?: any) => {
            console.error(chalk.red.bold(`\n--- ${context.toUpperCase()} ---`));
            console.error(error);
            if (origin) console.error(`Origin: ${origin}`);
            console.error(chalk.red.bold('--------------------------'));
            if (system) {
                try { await system.shutdown(); } catch { process.exit(1); }
            } else { process.exit(1); }
        };

        process.on('SIGINT', () => handleShutdown('SIGINT'));
        process.on('SIGTERM', () => handleShutdown('SIGTERM'));
        process.on('uncaughtException', (error, origin) => handleException('Uncaught Exception', error, origin));
        process.on('unhandledRejection', (reason, promise) => handleException('Unhandled Rejection', reason, promise));

        try {
            system = new FlowMindSystem();
            await system.initialize();
        } catch (error) {
            console.error(chalk.red.bold("Critical initialization error:"), error);
            process.exit(1);
        }

    } else if (mode === 'client') {
        const serverUrl = process.argv[3] || `ws://localhost:${WS_PORT}`;
        startReplClient(serverUrl);
    } else {
        console.error(chalk.red(`Invalid mode: ${mode}. Use 'server' or 'client [url]'`));
        process.exit(1);
    }
}

main();