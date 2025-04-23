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
import { IncomingMessage } from 'http';

// --- Configuration ---
const DATA_DIR = path.join(os.homedir(), '.flowmind');
const STATE_FILE = path.join(DATA_DIR, 'flowmind_state.json');
const VECTOR_STORE_DIR = path.join(DATA_DIR, 'vector-store');
const OLLAMA_BASE_URL = process.env.OLLAMA_HOST || "http://localhost:11434";
const OLLAMA_MODEL = process.env.OLLAMA_MODEL || 'hf.co/mradermacher/phi-4-GGUF:Q4_K_S';
const OLLAMA_EMBEDDING_MODEL = process.env.OLLAMA_EMBED_MODEL || OLLAMA_MODEL;
const WORKER_INTERVAL = 2000;
const SAVE_DEBOUNCE = 5000;
const MAX_RETRIES = 2;
const DEFAULT_BELIEF_POS = 1.0;
const DEFAULT_BELIEF_NEG = 1.0;
const SHORT_ID_LEN = 6;
const WS_PORT = 8080;
const LOG_LEVEL = process.env.LOG_LEVEL || 'info';
const STUCK_THRESHOLD_MS = 5 * 60 * 1000; // 5 minutes
const TICK_INTERVAL_MS = 60 * 1000; // 1 minute

// --- Types ---
enum Status { PENDING = 'PENDING', ACTIVE = 'ACTIVE', WAITING = 'WAITING', DONE = 'DONE', FAILED = 'FAILED' }

enum Type {
    INPUT = 'INPUT', GOAL = 'GOAL', STRATEGY = 'STRATEGY', OUTCOME = 'OUTCOME',
    QUERY = 'QUERY', USER_PROMPT = 'USER_PROMPT', SYSTEM = 'SYSTEM',
    FACT = 'FACT', LOG = 'LOG'
}

type TaskStatus = 'RUNNING' | 'PAUSED';
type Term = Atom | Variable | Struct | List;

interface Atom { kind: 'Atom'; name: string; }
interface Variable { kind: 'Variable'; name: string; }
interface Struct { kind: 'Struct'; name: string; args: Term[]; }
interface List { kind: 'List'; elements: Term[]; }

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
    waitingFor?: string | { type: 'time', timestamp: number } | { type: 'event', pattern: string };
    responseTo?: string;
    provenance?: string;
    taskStatus?: TaskStatus;
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
    metadata: { priority?: number; description?: string; provenance?: string; created?: string; modified?: string; };
}

interface MemoryEntry {
    id: string;
    embedding?: number[];
    content: string;
    metadata: { created: string; type: string; sourceId: string; };
    score?: number;
}

interface AppState { thoughts: Record<string, any>; rules: Record<string, any>; }

interface ToolContext {
    thoughts: Thoughts;
    tools: Tools;
    rules: Rules;
    memory: Memory;
    llm: LLM;
    prompts: Prompts;
    engine: Engine;
}

interface Tool {
    name: string;
    description: string;
    execute(actionTerm: Struct, context: ToolContext, trigger: Thought): Promise<Term | null>;
}

interface SystemControl {
    startProcessing: () => void;
    pauseProcessing: () => void;
    stepProcessing: () => Promise<number>;
    shutdown: () => Promise<void>;
    getStatus: () => { isRunning: boolean };
}

type MatchResult = { rule: Rule; bindings: Map<string, Term> };
type ActionResult = { success: boolean; error?: string; finalStatus?: Status };
type FallbackResult = ActionResult;
type LogLevel = 'debug' | 'info' | 'warn' | 'error';

// --- Utilities ---
const generateId = (): string => uuidv4();
const shortId = (id: string | undefined): string => id ? id.substring(0, SHORT_ID_LEN) : '------';
const sleep = (ms: number): Promise<void> => new Promise(resolve => setTimeout(resolve, ms));
const LOG_LEVELS: Record<LogLevel, number> = { debug: 0, info: 1, warn: 2, error: 3 };
const CURRENT_LOG_LEVEL = LOG_LEVELS[LOG_LEVEL as LogLevel] ?? LOG_LEVELS.info;

const log = (level: LogLevel, message: string, ...args: any[]) => {
    if (LOG_LEVELS[level] >= CURRENT_LOG_LEVEL) {
        const colorMap: Record<LogLevel, chalk.ChalkFunction> =
            { debug: chalk.gray, info: chalk.blue, warn: chalk.yellow, error: chalk.red };
        console.log(colorMap[level](`[${level.toUpperCase()}] ${message}`), ...args);
    }
};

function debounce<T extends (...args: any[]) => any>(func: T, wait: number): T & {
    cancel: () => void; flush: () => ReturnType<T> | undefined;
} {
    let timeout: NodeJS.Timeout | null = null;
    let lastArgs: Parameters<T> | null = null;
    let lastThis: any = null;
    let result: ReturnType<T> | undefined;
    const debounced = (...args: Parameters<T>): void => {
        lastArgs = args;
        lastThis = this;
        if (timeout) clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
    const later = () => {
        timeout = null;
        if (lastArgs) result = func.apply(lastThis, lastArgs);
        lastArgs = null;
        lastThis = null;
    };
    debounced.cancel = () => {
        if (timeout) clearTimeout(timeout);
        timeout = null;
        lastArgs = null;
        lastThis = null;
    };
    debounced.flush = () => {
        if (timeout) clearTimeout(timeout);
        later();
        return result;
    };
    return debounced as T & { cancel: () => void; flush: () => ReturnType<T> | undefined; };
}

const safeJsonParse = <T>(json: string | null | undefined, defaultValue: T): T => {
    if (!json) return defaultValue;
    try { return JSON.parse(json); }
    catch { return defaultValue; }
};

const isValidUuid = (id: string): boolean =>
    /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(id);

// --- Core Logic Classes ---

class Belief implements BeliefData {
    constructor(public pos: number = DEFAULT_BELIEF_POS, public neg: number = DEFAULT_BELIEF_NEG) { }
    score(): number { return (this.pos + 1) / (this.pos + this.neg + 2); }
    update(success: boolean): void { success ? this.pos++ : this.neg++; }
    toJSON(): BeliefData { return { pos: this.pos, neg: this.neg }; }
    static fromJSON(data: BeliefData | Belief | undefined): Belief {
        if (!data) return new Belief();
        return data instanceof Belief ? data : new Belief(data.pos ?? DEFAULT_BELIEF_POS, data.neg ?? DEFAULT_BELIEF_NEG);
    }
    static DEFAULT = new Belief();
}

const Atom = (name: string): Atom => ({ kind: 'Atom', name });
const Variable = (name: string): Variable => ({ kind: 'Variable', name });
const Struct = (name: string, args: Term[]): Struct => ({ kind: 'Struct', name, args });
const List = (elements: Term[]): List => ({ kind: 'List', elements });

namespace Terms {
    export function format(term: Term | null | undefined, useColor: boolean = true): string {
        if (!term) return useColor ? chalk.grey('null') : 'null';
        const c = (color: chalk.ChalkFunction, text: string) => useColor ? color(text) : text;
        switch (term.kind) {
            case 'Atom': return c(chalk.green, term.name);
            case 'Variable': return c(chalk.cyan, `?${term.name}`);
            case 'Struct': return `${c(chalk.yellow, term.name)}(${term.args.map(t => format(t, useColor)).join(', ')})`;
            case 'List': return `[${term.elements.map(t => format(t, useColor)).join(', ')}]`;
            default: const exhaustiveCheck: never = term; return c(chalk.red, 'invalid_term');
        }
    }

    export function toString(term: Term): string {
        switch (term.kind) {
            case 'Atom': return term.name;
            case 'Variable': return `?${term.name}`;
            case 'Struct': return `${term.name}(${term.args.map(toString).join(',')})`;
            case 'List': return `[${term.elements.map(toString).join(',')}]`;
            default: const exhaustiveCheck: never = term; return '';
        }
    }

    export function fromString(input: string): Term {
        input = input.trim();
        if (input.startsWith('?') && input.length > 1 && !input.includes('(') && !input.includes('[') && !input.includes(',')) return Variable(input.substring(1));

        const parseNested = (str: string, open: string, close: string): Term[] => {
            const items: Term[] = [];
            let currentItem = '';
            let level = 0;
            for (let i = 0; i < str.length; i++) {
                const char = str[i];
                if (char === open) level++; else if (char === close) level--;
                else if (char === '(') level++; else if (char === ')') level--;
                else if (char === '[') level++; else if (char === ']') level--;

                if (char === ',' && level === 0) {
                    if (currentItem.trim()) items.push(fromString(currentItem.trim()));
                    currentItem = '';
                } else { currentItem += char; }
            }
            if (currentItem.trim()) items.push(fromString(currentItem.trim()));
            return items;
        };

        const structMatch = input.match(/^([a-zA-Z0-9_]+)\((.*)\)$/);
        if (structMatch) return Struct(structMatch[1], parseNested(structMatch[2], '(', ')'));

        const listMatch = input.match(/^\[(.*)]$/);
        if (listMatch) return List(parseNested(listMatch[1], '[', ']'));

        if (/^[a-zA-Z0-9_:\-\/. ]+$/.test(input) || input === '') return Atom(input); // Allow more chars in Atoms

        log('warn', `Interpreting potentially complex string as Atom: "${input}"`);
        return Atom(input);
    }

    export function unify(term1: Term, term2: Term, bindings: Map<string, Term> = new Map()): Map<string, Term> | null {
        const resolve = (term: Term, currentBindings: Map<string, Term>): Term => {
            while (term.kind === 'Variable' && currentBindings.has(term.name)) {
                const bound = currentBindings.get(term.name)!;
                if (bound === term) return term; // Self-reference detected
                term = bound;
            }
            return term;
        };

        const occursCheck = (varName: string, termToCheck: Term, currentBindings: Map<string, Term>): boolean => {
            const resolved = resolve(termToCheck, currentBindings);
            if (resolved.kind === 'Variable') return varName === resolved.name;
            if (resolved.kind === 'Struct') return resolved.args.some(arg => occursCheck(varName, arg, currentBindings));
            if (resolved.kind === 'List') return resolved.elements.some(el => occursCheck(varName, el, currentBindings));
            return false;
        };

        const stack: [Term, Term][] = [[term1, term2]];
        let currentBindings = new Map(bindings);

        while (stack.length > 0) {
            const [t1, t2] = stack.pop()!;
            const rt1 = resolve(t1, currentBindings);
            const rt2 = resolve(t2, currentBindings);

            if (rt1 === rt2) continue;

            if (rt1.kind === 'Variable') {
                if (occursCheck(rt1.name, rt2, currentBindings)) return null;
                currentBindings.set(rt1.name, rt2);
                continue;
            }
            if (rt2.kind === 'Variable') {
                if (occursCheck(rt2.name, rt1, currentBindings)) return null;
                currentBindings.set(rt2.name, rt1);
                continue;
            }
            if (rt1.kind !== rt2.kind) return null;

            switch (rt1.kind) {
                case 'Atom': if (rt1.name !== (rt2 as Atom).name) return null; break;
                case 'Struct': {
                    const s2 = rt2 as Struct;
                    if (rt1.name !== s2.name || rt1.args.length !== s2.args.length) return null;
                    for (let i = 0; i < rt1.args.length; i++) stack.push([rt1.args[i], s2.args[i]]);
                    break;
                }
                case 'List': {
                    const l2 = rt2 as List;
                    if (rt1.elements.length !== l2.elements.length) return null;
                    for (let i = 0; i < rt1.elements.length; i++) stack.push([rt1.elements[i], l2.elements[i]]);
                    break;
                }
            }
        }
        return currentBindings;
    }

    export function substitute(term: Term, bindings: Map<string, Term>): Term {
        const resolve = (t: Term): Term => {
            if (t.kind === 'Variable' && bindings.has(t.name)) {
                const bound = bindings.get(t.name)!;
                return resolve(bound); // Recursively resolve bound variables
            }
            return t;
        };
        const resolved = resolve(term);
        if (resolved.kind === 'Struct') return { ...resolved, args: resolved.args.map(arg => substitute(arg, bindings)) };
        if (resolved.kind === 'List') return { ...resolved, elements: resolved.elements.map(el => substitute(el, bindings)) };
        return resolved;
    }

    export function jsonToTerm(data: any): Term | null {
        if (data === null || typeof data !== 'object') return Atom(String(data));
        if (Array.isArray(data)) {
            const elements = data.map(jsonToTerm).filter((t): t is Term => t !== null);
            return List(elements);
        }
        const { kind, name, args, elements } = data;
        switch (kind) {
            case 'Atom': return typeof name === 'string' ? Atom(name) : null;
            case 'Variable': return typeof name === 'string' ? Variable(name) : null;
            case 'Struct':
                if (typeof name === 'string' && Array.isArray(args)) {
                    const convertedArgs = args.map(jsonToTerm).filter((t): t is Term => t !== null);
                    return convertedArgs.length === args.length ? Struct(name, convertedArgs) : null;
                } return null;
            case 'List':
                if (Array.isArray(elements)) {
                    const convertedElements = elements.map(jsonToTerm).filter((t): t is Term => t !== null);
                    return convertedElements.length === elements.length ? List(convertedElements) : null;
                } return null;
            default:
                const objArgs = Object.entries(data)
                    .map(([k, v]) => {
                        const termValue = jsonToTerm(v);
                        return termValue ? Struct(k, [termValue]) : Atom(`${k}:${JSON.stringify(v)}`);
                    })
                    .filter((t): t is Term => t !== null);
                return Struct("json_object", objArgs);
        }
    }

    export function extractBindings(bindings: Map<string, Term>): Record<string, string> {
        const context: Record<string, string> = {};
        bindings.forEach((value, key) => { context[key] = toString(value); });
        return context;
    }
}

abstract class BaseStore<T extends { id: string, metadata?: { created?: string, modified?: string } }> {
    protected items = new Map<string, T>();
    protected listeners: Set<() => void> = new Set();
    protected changedItems: Set<string> = new Set();
    protected deletedItems: Set<string> = new Set();

    add(item: T): void { this.update(item, true); }
    get(id: string): T | undefined { return this.items.get(id); }
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

    update(item: T, isNew: boolean = false): void {
        const existing = this.items.get(item.id);
        const now = new Date().toISOString();
        const newItem = {
            ...item,
            metadata: { ...(existing?.metadata ?? {}), ...(item.metadata ?? {}), created: existing?.metadata?.created ?? now, modified: now }
        };
        this.items.set(item.id, newItem);
        this.changedItems.add(item.id);
        this.deletedItems.delete(item.id);
        this.notifyChange();
    }

    delete(id: string): boolean {
        if (!this.items.has(id)) return false;
        this.items.delete(id);
        this.changedItems.delete(id);
        this.deletedItems.add(id);
        this.notifyChange();
        return true;
    }

    getDelta(): { changed: T[], deleted: string[] } {
        const changed = Array.from(this.changedItems).map(id => this.items.get(id)).filter((item): item is T => !!item);
        const deleted = Array.from(this.deletedItems);
        this.changedItems.clear();
        this.deletedItems.clear();
        return { changed, deleted };
    }

    protected notifyChange(): void { this.listeners.forEach(listener => listener()); }

    abstract toJSON(): Record<string, any>;
    abstract loadJSON(data: Record<string, any>): void;

    protected baseLoadJSON(data: Record<string, any>, itemFactory: (id: string, itemData: any) => T | null): void {
        this.items.clear();
        Object.entries(data).forEach(([id, itemData]) => {
            try {
                const item = itemFactory(id, itemData);
                if (item) this.items.set(id, item);
                else log('warn', `Skipped loading invalid item ${id}`);
            } catch (e: any) { log('error', `Failed to load item ${id}: ${e.message}`); }
        });
        this.changedItems.clear();
        this.deletedItems.clear();
    }
}

class Thoughts extends BaseStore<Thought> {
    getPending(): Thought[] { return this.getAll().filter(t => t.status === Status.PENDING); }
    findThought(idPrefix: string): Thought | undefined { return this.findItemByPrefix(idPrefix); }
    findPendingPrompt(promptId: string): Thought | undefined {
        return this.getAll().find(t => t.type === Type.USER_PROMPT && t.status === Status.PENDING && t.metadata.promptId === promptId);
    }
    findWaitingThought(promptId: string): Thought | undefined {
        return this.getAll().find(t => t.status === Status.WAITING && t.metadata.waitingFor === promptId);
    }
    findStuckThoughts(thresholdMs: number): Thought[] {
        const now = Date.now();
        return this.getAll().filter(t =>
            (t.status === Status.PENDING || t.status === Status.WAITING) &&
            t.metadata.modified &&
            (now - new Date(t.metadata.modified).getTime()) > thresholdMs
        );
    }
    getRootThoughts(): Thought[] { return this.getAll().filter(t => !t.metadata.rootId || t.metadata.rootId === t.id); }
    getDescendants(rootId: string): Thought[] { return this.getAll().filter(t => t.metadata.rootId === rootId && t.id !== rootId); }

    toJSON(): Record<string, any> {
        const obj: Record<string, any> = {};
        this.items.forEach((thought, id) => obj[id] = { ...thought, belief: thought.belief.toJSON() });
        return obj;
    }

    loadJSON(data: Record<string, any>): void {
        this.baseLoadJSON(data, (id, thoughtData) => {
            const { belief, content, metadata, ...rest } = thoughtData;
            const status = Object.values(Status).includes(rest.status) ? rest.status : Status.PENDING;
            const finalStatus = status === Status.ACTIVE ? Status.PENDING : status; // Reset ACTIVE on load
            const finalMetadata = metadata || {};
            const contentTerm = Terms.jsonToTerm(content);
            if (!contentTerm) { log('error', `Failed to parse content for thought ${id}, skipping.`); return null; }
            if (!finalMetadata.rootId || finalMetadata.rootId === id) {
                finalMetadata.taskStatus = ['RUNNING', 'PAUSED'].includes(finalMetadata.taskStatus) ? finalMetadata.taskStatus : 'RUNNING';
            }
            return { ...rest, id, belief: Belief.fromJSON(belief), content: contentTerm, status: finalStatus, metadata: finalMetadata };
        });
        log('info', `Loaded ${this.count()} thoughts. Reset ACTIVE thoughts to PENDING.`);
    }
}

class Rules extends BaseStore<Rule> {
    findRule(idPrefix: string): Rule | undefined { return this.findItemByPrefix(idPrefix); }
    toJSON(): Record<string, any> {
        const obj: Record<string, any> = {};
        this.items.forEach((rule, id) => obj[id] = { ...rule, belief: rule.belief.toJSON() });
        return obj;
    }

    loadJSON(data: Record<string, any>): void {
        this.baseLoadJSON(data, (id, ruleData) => {
            const { belief, pattern, action, metadata, ...rest } = ruleData;
            const patternTerm = Terms.jsonToTerm(pattern);
            const actionTerm = Terms.jsonToTerm(action);
            if (!patternTerm || !actionTerm) { log('error', `Failed to parse pattern/action for rule ${id}, skipping.`); return null; }
            return { ...rest, id, belief: Belief.fromJSON(belief), pattern: patternTerm, action: actionTerm, metadata: metadata ?? {} };
        });
        log('info', `Loaded ${this.count()} rules.`);
    }
}

class Memory {
    private vectorStore: FaissStore | null = null;
    public isReady = false;
    constructor(private embeddings: Embeddings, private storePath: string) { }

    async initialize(): Promise<void> {
        if (this.isReady) return;
        try {
            await fs.access(this.storePath);
            this.vectorStore = await FaissStore.load(this.storePath, this.embeddings);
            this.isReady = true;
            log('info', `Vector store loaded from ${this.storePath}`);
        } catch (error: any) {
            if (error.code === 'ENOENT') {
                log('warn', `Vector store not found at ${this.storePath}, initializing.`);
                try {
                    await fs.mkdir(path.dirname(this.storePath), { recursive: true });
                    const dummyDoc = new Document({
                        pageContent: "Initial sentinel document",
                        metadata: { sourceId: "init", type: "SYSTEM", created: new Date().toISOString(), id: "init_doc", _docId: generateId() }
                    });
                    this.vectorStore = await FaissStore.fromDocuments([dummyDoc], this.embeddings);
                    await this.save();
                    this.isReady = true;
                    log('info', `New vector store created at ${this.storePath}`);
                } catch (initError: any) { log('error', 'Failed to initialize new vector store:', initError.message); }
            } else { log('error', 'Failed to load vector store:', error.message); }
        }
    }

    async add(entry: Omit<MemoryEntry, 'embedding' | 'score'>): Promise<void> {
        if (!this.vectorStore || !this.isReady) { log('warn', 'MemoryStore not ready, cannot add entry.'); return; }
        const docMetadata = { ...entry.metadata, id: entry.id, _docId: generateId() };
        const doc = new Document({ pageContent: entry.content, metadata: docMetadata });
        try {
            await this.vectorStore.addDocuments([doc]);
            await this.save();
            log('debug', `Added memory entry from source ${entry.metadata.sourceId}`);
        } catch (error: any) { log('error', 'Failed to add document to vector store:', error.message); }
    }

    async search(query: string, k: number = 5): Promise<MemoryEntry[]> {
        if (!this.vectorStore || !this.isReady) { log('warn', 'MemoryStore not ready, cannot search.'); return []; }
        try {
            const results = await this.vectorStore.similaritySearchWithScore(query, k);
            log('debug', `Memory search for "${query.substring(0, 30)}..." returned ${results.length} results.`);
            return results
                .filter(([doc]) => doc.metadata.sourceId !== 'init')
                .map(([doc, score]): MemoryEntry => ({
                    id: doc.metadata.id ?? generateId(), content: doc.pageContent,
                    metadata: { created: doc.metadata.created ?? new Date().toISOString(), type: doc.metadata.type ?? 'UNKNOWN', sourceId: doc.metadata.sourceId ?? 'UNKNOWN' },
                    score: score, embedding: []
                }));
        } catch (error: any) { log('error', 'Failed to search vector store:', error.message); return []; }
    }

    async save(): Promise<void> {
        if (this.vectorStore && this.isReady) {
            try { await this.vectorStore.save(this.storePath); }
            catch (error: any) { log('error', 'Failed to save vector store:', error.message); }
        }
    }
}

class LLM {
    llm: BaseChatModel;
    embeddings: Embeddings;
    constructor() {
        this.llm = new ChatOllama({ baseUrl: OLLAMA_BASE_URL, model: OLLAMA_MODEL, temperature: 0.7 });
        this.embeddings = new OllamaEmbeddings({ model: OLLAMA_EMBEDDING_MODEL, baseUrl: OLLAMA_BASE_URL });
        log('info', `LLM Service: Model=${OLLAMA_MODEL}, Embeddings=${OLLAMA_EMBEDDING_MODEL}, URL=${OLLAMA_BASE_URL}`);
    }

    async generate(prompt: string): Promise<string> {
        log('debug', `LLM Prompt: "${prompt.substring(0, 100)}..."`);
        try {
            const response = await this.llm.pipe(new StringOutputParser()).invoke([new HumanMessage(prompt)]);
            const trimmedResponse = response.trim();
            log('debug', `LLM Response: "${trimmedResponse.substring(0, 100)}..."`);
            return trimmedResponse;
        } catch (error: any) {
            log('error', `LLM generation failed: ${error.message}`);
            return `Error: LLM generation failed. ${error.message}`;
        }
    }

    async embed(text: string): Promise<number[]> {
        log('debug', `Embedding text: "${text.substring(0, 50)}..."`);
        try { return await this.embeddings.embedQuery(text); }
        catch (error: any) { log('error', `Embedding failed: ${error.message}`); return []; }
    }
}

type PromptKey =
    'GENERATE_GOAL' | 'GENERATE_STRATEGY' | 'GENERATE_OUTCOME' | 'SUGGEST_GOAL' |
    'FALLBACK_ASK_USER' | 'CONFIRM_GOAL' | 'CLARIFY_INPUT' | 'CONFIRM_STRATEGY' |
    'NEXT_TASK_PROMPT' | 'HANDLE_FAILURE' | 'GENERATE_RECOVERY_STRATEGY' |
    'STUCK_THOUGHT_PROMPT' | 'SUMMARIZE_TASK_COMPLETION' | 'ASK_FOR_RULE_SUGGESTION';

class Prompts {
    private prompts: Record<PromptKey, string> = {
        GENERATE_GOAL: 'Input: "{input}". Define a specific, actionable GOAL based on this input. Output ONLY the goal text.',
        GENERATE_STRATEGY: 'Goal: "{goal}". Outline 1-3 concrete STRATEGY steps to achieve this goal. Output each step on a new line, starting with "- ".',
        GENERATE_OUTCOME: 'Strategy step "{strategy}" was attempted. Describe a likely concise OUTCOME. Output ONLY the outcome text.',
        SUGGEST_GOAL: 'Based on the current context "{context}"{memoryContext}\n\nSuggest ONE concise, actionable next goal or task. Output only the suggested goal text.',
        FALLBACK_ASK_USER: 'No rule found for thought {thoughtId} ({thoughtType}: "{thoughtContent}"). How should I proceed with this?',
        CONFIRM_GOAL: 'Goal generated: "{goal}". Is this correct, or should it be refined? Please respond.',
        CLARIFY_INPUT: 'The input "{input}" seems brief or ambiguous. Could you please provide more details or context for a better goal?',
        CONFIRM_STRATEGY: 'Proposed strategy step: "{strategy}". Does this seem like a good step towards the goal? (yes/no/suggest alternative)',
        NEXT_TASK_PROMPT: 'Task "{taskContent}" ({taskId}) is complete. What would you like to work on next?',
        HANDLE_FAILURE: 'Thought {failedThoughtId} ("{content}") failed with error: "{error}". How should I proceed? (Options: retry / abandon / edit_goal / provide_new_strategy / manual_fix)',
        GENERATE_RECOVERY_STRATEGY: "Thought {failedId} ('{failedContent}') failed with error: '{error}'. Suggest a concise alternative strategy or action to achieve the original goal (if discernible). Output ONLY the suggested action/strategy text.",
        STUCK_THOUGHT_PROMPT: "Thought {thoughtId} has been in status '{status}' for {age}. What should be done? (Options: retry / mark_failed / investigate / ignore)",
        SUMMARIZE_TASK_COMPLETION: "Task goal '{goalContent}' (ID: {goalId}) is complete. Based on its associated thoughts (Outcomes, Facts, Logs), provide a brief summary of what was accomplished and any key findings.",
        ASK_FOR_RULE_SUGGESTION: "Processing pattern '{pattern}' led to failure '{error}' multiple times. Can you suggest a better rule (pattern -> action) to handle this situation more effectively?",
    };

    format(key: PromptKey, context: Record<string, string>): string {
        let template = this.prompts[key] ?? `Error: Prompt template "${key}" not found. Context: ${JSON.stringify(context)}`;
        Object.entries(context).forEach(([k, v]) => { template = template.replace(new RegExp(`\\{${k}\\}`, 'g'), v); });
        template = template.replace(/\{[^}]+\}/g, ''); // Remove unused placeholders
        return template;
    }
}

// --- Tools ---
abstract class BaseTool implements Tool {
    abstract name: string;
    abstract description: string;
    abstract execute(actionTerm: Struct, context: ToolContext, trigger: Thought): Promise<Term | null>;

    protected createResultAtom(success: boolean, code: string, message?: string): Atom {
        const prefix = success ? 'ok' : 'error';
        const fullMessage = `${prefix}:${this.name}_${code}${message ? `:${message.substring(0, 50)}` : ''}`;
        return Atom(fullMessage.replace(/\s+/g, '_')); // Ensure atom compatibility
    }

    protected extractBindingsFromContext(contextTerm: Term | undefined, trigger: Thought, ruleContext: ToolContext): Record<string, string> {
        const promptContext: Record<string, string> = {};
        if (contextTerm?.kind !== 'Struct') return promptContext;

        contextTerm.args.forEach(arg => {
            if (arg.kind === 'Atom' && arg.name.includes(':')) {
                const [k, ...vParts] = arg.name.split(':');
                let value = vParts.join(':');
                if (value.startsWith('?')) { // Resolve variable
                    const varName = value.substring(1);
                    const rule = trigger.metadata.ruleId ? ruleContext.rules.get(trigger.metadata.ruleId) : undefined;
                    const ruleMatch = rule ? RuleMatcher.match(trigger, [rule]) : null;
                    if (ruleMatch?.bindings.has(varName)) {
                        value = Terms.toString(ruleMatch.bindings.get(varName)!);
                    }
                }
                promptContext[k] = value;
            } else if (arg.kind === 'Struct' && arg.args.length === 1 && arg.args[0].kind === 'Variable') {
                // Resolve variable like context(goal:?GoalContent)
                const k = arg.name;
                const varName = arg.args[0].name;
                const rule = trigger.metadata.ruleId ? ruleContext.rules.get(trigger.metadata.ruleId) : undefined;
                const ruleMatch = rule ? RuleMatcher.match(trigger, [rule]) : null;
                promptContext[k] = ruleMatch?.bindings.has(varName)
                    ? Terms.toString(ruleMatch.bindings.get(varName)!)
                    : `?${varName}`;
            }
        });
        return promptContext;
    }
}

class LLMTool extends BaseTool {
    name = "LLMTool";
    description = "Interacts with the LLM: generate(prompt_key_atom, context_term), embed(text_term).";

    async execute(action: Struct, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (!operation) return this.createResultAtom(false, "invalid_params", "Missing operation atom");
        switch (operation) {
            case 'generate': return this.generate(action, context, trigger);
            case 'embed': return this.embed(action, context);
            default: return this.createResultAtom(false, "unsupported_operation", operation);
        }
    }

    private async embed(action: Struct, context: ToolContext): Promise<Term> {
        const inputTerm = action.args[1];
        if (!inputTerm) return this.createResultAtom(false, "invalid_params", "Missing text term for embed");
        const inputText = Terms.toString(inputTerm);
        const embedding = await context.llm.embed(inputText);
        return this.createResultAtom(embedding.length > 0, embedding.length > 0 ? "embedded" : "embedding_failed");
    }

    private async generate(action: Struct, context: ToolContext, trigger: Thought): Promise<Term> {
        const promptKeyAtom = action.args[1];
        const contextTerm = action.args[2];
        if (promptKeyAtom?.kind !== 'Atom') return this.createResultAtom(false, "invalid_params", "Missing prompt key atom");
        if (contextTerm && contextTerm.kind !== 'Struct') return this.createResultAtom(false, "invalid_params", "Context must be a Struct");

        const promptKey = promptKeyAtom.name as PromptKey;
        const promptContext = this.extractBindingsFromContext(contextTerm, trigger, context);
        const prompt = context.prompts.format(promptKey, promptContext);
        const response = await context.llm.generate(prompt);

        if (response.startsWith('Error:')) return this.createResultAtom(false, "generation_failed", response);

        // Try parsing response as Term structure (e.g., LLM outputs 'Struct(name, [Atom(arg1)])')
        try {
            const parsedTerm = Terms.fromString(response);
            if (parsedTerm) return parsedTerm;
        } catch { /* Ignore parsing error, treat as Atom */ }

        // Fallback: return as Atom
        return Atom(response);
    }
}

class MemoryTool extends BaseTool {
    name = "MemoryTool";
    description = "Manages vector memory: add(content_term), search(query_term, k?).";

    async execute(action: Struct, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (!operation) return this.createResultAtom(false, "missing_operation");
        switch (operation) {
            case 'add': {
                const contentTerm = action.args[1];
                if (!contentTerm) return this.createResultAtom(false, "missing_add_content");
                await context.memory.add({
                    id: generateId(), content: Terms.toString(contentTerm),
                    metadata: { created: new Date().toISOString(), type: trigger.type, sourceId: trigger.id }
                });
                trigger.metadata.embedded = new Date().toISOString();
                context.thoughts.update(trigger);
                return this.createResultAtom(true, "memory_added", shortId(trigger.id));
            }
            case 'search': {
                const queryTerm = action.args[1];
                const kTerm = action.args[2];
                if (!queryTerm) return this.createResultAtom(false, "missing_search_query");
                const queryStr = Terms.toString(queryTerm);
                const k = (kTerm?.kind === 'Atom' && /^\d+$/.test(kTerm.name)) ? parseInt(kTerm.name, 10) : 3;
                const results = await context.memory.search(queryStr, k);
                return List(results.map(r => Atom(r.content)));
            }
            default: return this.createResultAtom(false, "unsupported_operation", operation);
        }
    }
}

class UserInteractionTool extends BaseTool {
    name = "UserInteractionTool";
    description = "Requests input from the user: prompt(prompt_text_term | prompt_key_atom, context_term?).";

    async execute(action: Struct, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (operation !== 'prompt') return this.createResultAtom(false, "invalid_params", "Expected prompt operation");
        const promptSource = action.args[1];
        const promptContextTerm = action.args[2];
        if (!promptSource) return this.createResultAtom(false, "invalid_params", "Missing prompt source");

        let promptText: string;
        if (promptSource.kind === 'Atom') {
            const promptKey = promptSource.name as PromptKey;
            const promptContext = this.extractBindingsFromContext(promptContextTerm, trigger, context);
            promptText = context.prompts.format(promptKey, promptContext);
        } else { promptText = Terms.toString(promptSource); }

        const promptId = generateId();
        context.engine.addThought({
            id: promptId, type: Type.USER_PROMPT, content: Atom(promptText), belief: Belief.DEFAULT, status: Status.PENDING,
            metadata: {
                rootId: trigger.metadata.rootId ?? trigger.id, parentId: trigger.id, created: new Date().toISOString(),
                promptId: promptId, provenance: this.name
            }
        });
        trigger.status = Status.WAITING;
        trigger.metadata.waitingFor = promptId;
        context.thoughts.update(trigger);
        log('info', `User prompt requested (${shortId(promptId)}) for thought ${shortId(trigger.id)}.`);
        return this.createResultAtom(true, "prompt_requested", promptId);
    }
}

class GoalProposalTool extends BaseTool {
    name = "GoalProposalTool";
    description = "Suggests new goals based on context: suggest(context_term?).";

    async execute(action: Struct, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (operation !== 'suggest') return this.createResultAtom(false, "invalid_params");
        const contextTerm = action.args[1] ?? trigger.content;
        const contextStr = Terms.toString(contextTerm);
        const memoryResults = await context.memory.search(`Relevant past goals or outcomes related to: ${contextStr}`, 3);
        const memoryContext = memoryResults.length > 0 ? `\nRelated past activities:\n - ${memoryResults.map(r => r.content).join("\n - ")}` : "";
        const prompt = context.prompts.format('SUGGEST_GOAL', { context: contextStr, memoryContext: memoryContext });
        const suggestionText = await context.llm.generate(prompt);
        if (!suggestionText || suggestionText.startsWith('Error:')) return this.createResultAtom(false, "llm_failed", suggestionText);

        const suggestionThought: Thought = {
            id: generateId(), type: Type.GOAL, content: Atom(suggestionText), belief: new Belief(1, 0), status: Status.PENDING,
            metadata: {
                rootId: trigger.metadata.rootId ?? trigger.id, parentId: trigger.id, created: new Date().toISOString(),
                tags: ['suggested_goal'], provenance: this.name
            }
        };
        context.engine.addThought(suggestionThought);
        log('info', `Suggested goal ${shortId(suggestionThought.id)} based on ${shortId(trigger.id)}.`);
        return this.createResultAtom(true, "suggestion_created", shortId(suggestionThought.id));
    }
}

class CoreTool extends BaseTool {
    name = "CoreTool";
    description = "Manages internal state: set_status(target_id, status), add_thought(type, content, root?, parent?), delete_thought(target_id), set_content(target_id, content), log(level, message_term).";

    private resolveTargetId(t: Term | undefined, fallbackId: string, context: ToolContext): string | null {
        if (t?.kind !== 'Atom') return null;
        if (t.name === 'self') return fallbackId;
        const target = context.thoughts.findThought(t.name) ?? context.rules.findRule(t.name);
        return target?.id ?? (isValidUuid(t.name) ? t.name : null);
    }

    async execute(action: Struct, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (!operation) return this.createResultAtom(false, "missing_operation");

        const triggerId = trigger.id;
        switch (operation) {
            case 'set_status': return this.setStatus(action, triggerId, context, trigger);
            case 'add_thought': return this.addThought(action, triggerId, context, trigger);
            case 'delete_thought': return this.delThought(action, triggerId, context);
            case 'set_content': return this.setContent(action, triggerId, context);
            case 'log': return this.logMessage(action, context);
            default: return this.createResultAtom(false, "unsupported_operation", operation);
        }
    }

    private setContent(action: Struct, triggerId: string, context: ToolContext): Term {
        const targetId = this.resolveTargetId(action.args[1], triggerId, context);
        const newContentTerm = action.args[2];
        if (!targetId || !newContentTerm) return this.createResultAtom(false, "invalid_set_content_params");
        const targetThought = context.thoughts.get(targetId);
        if (!targetThought) return this.createResultAtom(false, "target_not_found", shortId(targetId));
        targetThought.content = newContentTerm;
        context.thoughts.update(targetThought);
        log('debug', `Set content of ${shortId(targetThought.id)}`);
        return this.createResultAtom(true, "content_set", shortId(targetThought.id));
    }

    private delThought(action: Struct, triggerId: string, context: ToolContext): Term {
        const targetId = this.resolveTargetId(action.args[1], triggerId, context);
        if (!targetId) return this.createResultAtom(false, "invalid_delete_thought_params");
        const deleted = context.thoughts.delete(targetId);
        log('debug', `Attempted delete thought ${shortId(targetId)}: ${deleted}`);
        return this.createResultAtom(deleted, deleted ? "thought_deleted" : "delete_failed", shortId(targetId));
    }

    private addThought(action: Struct, triggerId: string, context: ToolContext, trigger: Thought): Term {
        const typeTerm = action.args[1];
        const contentTerm = action.args[2];
        const rootIdTerm = action.args[3];
        const parentIdTerm = action.args[4];
        if (typeTerm?.kind !== 'Atom' || !contentTerm) return this.createResultAtom(false, "invalid_add_thought_params");
        const type = typeTerm.name.toUpperCase() as Type;
        if (!Object.values(Type).includes(type)) return this.createResultAtom(false, "invalid_thought_type", typeTerm.name);

        const parentId = this.resolveTargetId(parentIdTerm, triggerId, context) ?? triggerId;
        const parentThought = context.thoughts.get(parentId);
        const defaultRootId = parentThought?.metadata.rootId ?? parentId;
        const rootId = this.resolveTargetId(rootIdTerm, defaultRootId, context) ?? defaultRootId;

        const newThought: Thought = {
            id: generateId(), type, content: contentTerm, belief: Belief.DEFAULT, status: Status.PENDING,
            metadata: {
                rootId, parentId, created: new Date().toISOString(),
                provenance: `${this.name} (rule ${shortId(trigger.metadata.ruleId)} on ${shortId(triggerId)})`
            }
        };
        if (newThought.id === newThought.metadata.rootId) newThought.metadata.taskStatus = 'RUNNING';

        context.engine.addThought(newThought);
        log('debug', `Added thought ${shortId(newThought.id)} of type ${type}`);
        return this.createResultAtom(true, "thought_added", shortId(newThought.id));
    }

    private setStatus(action: Struct, triggerId: string, context: ToolContext, trigger: Thought): Term {
        const targetId = this.resolveTargetId(action.args[1], triggerId, context);
        const newStatusTerm = action.args[2];
        if (!targetId || newStatusTerm?.kind !== 'Atom') return this.createResultAtom(false, "invalid_set_status_params");
        const newStatus = newStatusTerm.name.toUpperCase() as Status;
        if (!Object.values(Status).includes(newStatus)) return this.createResultAtom(false, "invalid_status_value", newStatusTerm.name);
        const targetThought = context.thoughts.get(targetId);
        if (!targetThought) return this.createResultAtom(false, "target_not_found", shortId(targetId));

        const oldStatus = targetThought.status;
        targetThought.status = newStatus;
        context.thoughts.update(targetThought);
        log('debug', `Set status of ${shortId(targetThought.id)} to ${newStatus}`);

        // Check for task completion
        if (oldStatus !== Status.DONE && newStatus === Status.DONE && targetThought.type === Type.GOAL && targetThought.metadata.rootId === targetThought.id) {
            context.engine.addThought({
                id: generateId(), type: Type.LOG, content: Struct("task_completed", [Atom(targetThought.id)]),
                belief: Belief.DEFAULT, status: Status.PENDING,
                metadata: { rootId: targetThought.id, parentId: targetThought.id, created: new Date().toISOString(), provenance: `${this.name}_status_change` }
            });
        }

        return this.createResultAtom(true, "status_set", `${shortId(targetThought.id)}_to_${newStatus}`);
    }

    private logMessage(action: Struct, context: ToolContext): Term {
        const levelTerm = action.args[1];
        const messageTerm = action.args[2];
        if (levelTerm?.kind !== 'Atom' || !messageTerm) return this.createResultAtom(false, "invalid_log_params");
        const level = levelTerm.name.toLowerCase() as LogLevel;
        if (!LOG_LEVELS.hasOwnProperty(level)) return this.createResultAtom(false, "invalid_log_level", level);
        const message = Terms.toString(messageTerm);
        log(level, `[CoreTool Log] ${message}`);
        return this.createResultAtom(true, "logged", level);
    }
}

class TimeTool extends BaseTool {
    name = "TimeTool";
    description = "Provides time functions: get_time(), wait(duration_ms), schedule(action_term, time_ms), periodic_tick(interval_ms).";
    private static tickIntervalId: NodeJS.Timeout | null = null;

    async execute(action: Struct, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (!operation) return this.createResultAtom(false, "missing_operation");

        switch (operation) {
            case 'get_time': return Atom(Date.now().toString());
            case 'wait': {
                const durationTerm = action.args[1];
                if (durationTerm?.kind !== 'Atom' || !/^\d+$/.test(durationTerm.name)) return this.createResultAtom(false, "invalid_wait_duration");
                const durationMs = parseInt(durationTerm.name, 10);
                const wakeUpTime = Date.now() + durationMs;
                trigger.status = Status.WAITING;
                trigger.metadata.waitingFor = { type: 'time', timestamp: wakeUpTime };
                context.thoughts.update(trigger);
                log('debug', `Thought ${shortId(trigger.id)} waiting for ${durationMs}ms until ${new Date(wakeUpTime).toISOString()}`);
                return this.createResultAtom(true, "wait_set", durationTerm.name);
            }
            case 'schedule': { // TODO: Implement scheduling mechanism (e.g., add a scheduled thought)
                return this.createResultAtom(false, "unsupported_operation", "schedule");
            }
            case 'periodic_tick': {
                const intervalTerm = action.args[1];
                if (intervalTerm?.kind !== 'Atom' || !/^\d+$/.test(intervalTerm.name)) return this.createResultAtom(false, "invalid_tick_interval");
                const intervalMs = parseInt(intervalTerm.name, 10);
                if (TimeTool.tickIntervalId) clearInterval(TimeTool.tickIntervalId);
                TimeTool.tickIntervalId = setInterval(() => {
                    context.engine.addThought({
                        id: generateId(), type: Type.SYSTEM, content: Struct("tick", [Atom(Date.now().toString())]),
                        belief: Belief.DEFAULT, status: Status.PENDING,
                        metadata: { created: new Date().toISOString(), provenance: this.name, priority: -100 } // Low priority
                    });
                }, intervalMs);
                log('info', `Periodic tick set every ${intervalMs}ms`);
                return this.createResultAtom(true, "tick_started", intervalTerm.name);
            }
            default: return this.createResultAtom(false, "unsupported_operation", operation);
        }
    }
}

class FileSystemTool extends BaseTool {
    name = "FileSystemTool";
    description = "Interacts with local filesystem (Use with caution!): read_file(path_atom), write_file(path_atom, content_term), list_dir(path_atom).";

    async execute(action: Struct, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        const pathTerm = action.args[1];
        if (!operation || pathTerm?.kind !== 'Atom') return this.createResultAtom(false, "invalid_params");
        const filePath = path.resolve(DATA_DIR, pathTerm.name); // Restrict to DATA_DIR for safety

        if (!filePath.startsWith(path.resolve(DATA_DIR))) {
            return this.createResultAtom(false, "security_violation", "Path outside data directory");
        }

        try {
            switch (operation) {
                case 'read_file':
                    const content = await fs.readFile(filePath, 'utf-8');
                    return Atom(content);
                case 'write_file':
                    const contentTerm = action.args[2];
                    if (!contentTerm) return this.createResultAtom(false, "missing_write_content");
                    await fs.writeFile(filePath, Terms.toString(contentTerm), 'utf-8');
                    return this.createResultAtom(true, "file_written", pathTerm.name);
                case 'list_dir':
                    const files = await fs.readdir(filePath);
                    return List(files.map(f => Atom(f)));
                default: return this.createResultAtom(false, "unsupported_operation", operation);
            }
        } catch (error: any) {
            log('error', `FileSystemTool error (${operation} ${pathTerm.name}): ${error.message}`);
            return this.createResultAtom(false, "fs_error", error.code ?? error.message);
        }
    }
}

class WebSearchTool extends BaseTool {
    name = "WebSearchTool";
    description = "Performs web searches: search(query_term, num_results?). (Requires external setup/API key)";

    async execute(action: Struct, context: ToolContext, trigger: Thought): Promise<Term | null> {
        log('warn', "WebSearchTool is not fully implemented. Requires external API setup.");
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (operation !== 'search') return this.createResultAtom(false, "unsupported_operation", operation);
        const queryTerm = action.args[1];
        // const kTerm = action.args[2]; // Optional num results
        if (!queryTerm) return this.createResultAtom(false, "missing_search_query");
        const queryStr = Terms.toString(queryTerm);
        // Placeholder: In a real implementation, call a search API (e.g., SerpApi via LangChain)
        // const results = await someSearchApi(queryStr, k);
        // return List(results.map(r => Atom(r.snippet)));
        return this.createResultAtom(false, "not_implemented", `Search for '${queryStr.substring(0, 30)}...'`);
    }
}

class ContextSummarizer extends BaseTool {
    name = "ContextSummarizer";
    description = "Summarizes task context: summarize(rootId_atom, max_tokens?).";

    async execute(action: Struct, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (operation !== 'summarize') return this.createResultAtom(false, "unsupported_operation", operation);
        const rootIdTerm = action.args[1];
        // const maxTokensTerm = action.args[2]; // Optional token limit

        if (rootIdTerm?.kind !== 'Atom') return this.createResultAtom(false, "missing_root_id");
        const rootId = this.resolveTargetId(rootIdTerm, trigger.metadata.rootId ?? trigger.id, context);
        if (!rootId) return this.createResultAtom(false, "root_id_not_found", rootIdTerm.name);

        const rootThought = context.thoughts.get(rootId);
        if (!rootThought) return this.createResultAtom(false, "root_thought_not_found", shortId(rootId));

        const descendants = context.thoughts.getDescendants(rootId);
        const relevantThoughts = [rootThought, ...descendants].filter(t => [Type.GOAL, Type.STRATEGY, Type.OUTCOME, Type.FACT, Type.LOG].includes(t.type));
        if (relevantThoughts.length === 0) return Atom("No relevant context found for summarization.");

        const contextText = relevantThoughts
            .sort((a, b) => (a.metadata.created ?? '').localeCompare(b.metadata.created ?? ''))
            .map(t => `${t.type}: ${Terms.toString(t.content)}`)
            .join('\n');

        // Simplified prompt for now, could be more elaborate
        const prompt = `Summarize the following task context concisely:\n---\n${contextText.substring(0, 2000)}\n---\nSummary:`;
        const summary = await context.llm.generate(prompt);

        return summary.startsWith('Error:') ? this.createResultAtom(false, "llm_failed", summary) : Atom(summary);
    }

    private resolveTargetId(t: Term | undefined, fallbackId: string, context: ToolContext): string | null {
        if (t?.kind !== 'Atom') return null;
        if (t.name === 'self') return fallbackId; // May not make sense here
        const target = context.thoughts.findThought(t.name);
        return target?.id ?? (isValidUuid(t.name) ? t.name : null);
    }
}

class RuleManager extends BaseTool {
    name = "RuleManager";
    description = "Manages rules (Use with extreme caution!): add_rule(pattern_term, action_term, priority?, desc?), delete_rule(ruleId_atom), modify_priority(ruleId_atom, delta_num).";

    async execute(action: Struct, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (!operation) return this.createResultAtom(false, "missing_operation");

        try {
            switch (operation) {
                case 'add_rule': return this.addRule(action, context, trigger);
                case 'delete_rule': return this.deleteRule(action, context);
                case 'modify_priority': return this.modifyPriority(action, context);
                default: return this.createResultAtom(false, "unsupported_operation", operation);
            }
        } catch (error: any) {
            log('error', `RuleManager error (${operation}): ${error.message}`);
            return this.createResultAtom(false, "rule_mgmt_error", error.message);
        }
    }

    private addRule(action: Struct, context: ToolContext, trigger: Thought): Term {
        const patternTerm = action.args[1];
        const actionTerm = action.args[2];
        const priorityTerm = action.args[3];
        const descTerm = action.args[4];

        if (!patternTerm || !actionTerm) return this.createResultAtom(false, "invalid_add_rule_params");
        const priority = (priorityTerm?.kind === 'Atom' && /^-?\d+$/.test(priorityTerm.name)) ? parseInt(priorityTerm.name, 10) : 0;
        const description = descTerm ? Terms.toString(descTerm) : `Added by ${shortId(trigger.id)}`;

        const newRule: Rule = {
            id: generateId(), pattern: patternTerm, action: actionTerm, belief: Belief.DEFAULT,
            metadata: { priority, description, provenance: `RuleManager (${shortId(trigger.id)})`, created: new Date().toISOString() }
        };
        context.rules.add(newRule);
        log('warn', `Rule ${shortId(newRule.id)} added programmatically by ${shortId(trigger.id)}`);
        return this.createResultAtom(true, "rule_added", shortId(newRule.id));
    }

    private deleteRule(action: Struct, context: ToolContext): Term {
        const ruleIdTerm = action.args[1];
        if (ruleIdTerm?.kind !== 'Atom') return this.createResultAtom(false, "invalid_delete_rule_params");
        const rule = context.rules.findRule(ruleIdTerm.name);
        if (!rule) return this.createResultAtom(false, "rule_not_found", ruleIdTerm.name);
        context.rules.delete(rule.id);
        log('warn', `Rule ${shortId(rule.id)} deleted programmatically.`);
        return this.createResultAtom(true, "rule_deleted", shortId(rule.id));
    }

    private modifyPriority(action: Struct, context: ToolContext): Term {
        const ruleIdTerm = action.args[1];
        const deltaTerm = action.args[2];
        if (ruleIdTerm?.kind !== 'Atom' || deltaTerm?.kind !== 'Atom' || !/^-?\d+$/.test(deltaTerm.name)) {
            return this.createResultAtom(false, "invalid_modify_priority_params");
        }
        const rule = context.rules.findRule(ruleIdTerm.name);
        if (!rule) return this.createResultAtom(false, "rule_not_found", ruleIdTerm.name);
        const delta = parseInt(deltaTerm.name, 10);
        const oldPriority = rule.metadata.priority ?? 0;
        rule.metadata.priority = oldPriority + delta;
        context.rules.update(rule);
        log('warn', `Rule ${shortId(rule.id)} priority changed from ${oldPriority} to ${rule.metadata.priority}`);
        return this.createResultAtom(true, "priority_modified", `${shortId(rule.id)} to ${rule.metadata.priority}`);
    }
}

class Tools {
    private tools = new Map<string, Tool>();
    register(tool: Tool): void {
        if (this.tools.has(tool.name)) log('warn', `Tool "${tool.name}" redefined.`);
        this.tools.set(tool.name, tool);
    }
    get(name: string): Tool | undefined { return this.tools.get(name); }
    list(): Tool[] { return Array.from(this.tools.values()); }
}

// --- Engine Components ---
const RuleMatcher = {
    match(thought: Thought, rules: Rule[]): MatchResult | null {
        const applicableMatches = rules
            .map(rule => {
                // Handle potential self-references in bindings during unification
                let bindings: Map<string, Term> | null = null;
                try { bindings = Terms.unify(rule.pattern, thought.content); }
                catch (e: any) { log('warn', `Unification error for rule ${shortId(rule.id)} and thought ${shortId(thought.id)}: ${e.message}`); }
                return { rule, bindings };
            })
            .filter((m): m is MatchResult => m.bindings !== null);

        const count = applicableMatches.length;
        if (count === 0) return null;
        if (count === 1) return applicableMatches[0];

        applicableMatches.sort((a, b) =>
            (b.rule.metadata.priority ?? 0) - (a.rule.metadata.priority ?? 0) ||
            b.rule.belief.score() - a.rule.belief.score() ||
            (b.rule.metadata.created ?? '').localeCompare(a.rule.metadata.created ?? '') // Tie-break by creation time
        );
        log('debug', `Multiple rule matches for ${shortId(thought.id)} (${thought.type}). Best: ${shortId(applicableMatches[0].rule.id)}`);
        return applicableMatches[0];
    }
};

const ActionExecutor = {
    async execute(actionTerm: Term, context: ToolContext, trigger: Thought, rule?: Rule): Promise<ActionResult> {
        if (actionTerm.kind !== 'Struct') return { success: false, error: `Invalid action term kind: ${actionTerm.kind}`, finalStatus: Status.FAILED };
        const tool = context.tools.get(actionTerm.name);
        if (!tool) return { success: false, error: `Tool not found: ${actionTerm.name}`, finalStatus: Status.FAILED };
        log('debug', `Executing action ${Terms.format(actionTerm, false)} via tool ${tool.name} for thought ${shortId(trigger.id)}`);
        try {
            const resultTerm = await tool.execute(actionTerm, context, trigger);
            const currentThoughtState = context.thoughts.get(trigger.id); // Re-fetch state
            if (!currentThoughtState) { log('warn', `Thought ${shortId(trigger.id)} deleted during action by ${tool.name}.`); return { success: true }; }

            const isWaiting = currentThoughtState.status === Status.WAITING;
            const isFailed = currentThoughtState.status === Status.FAILED;
            const returnedError = resultTerm?.kind === 'Atom' && resultTerm.name.startsWith('error:');

            if (returnedError) return { success: false, error: `Tool failed: ${resultTerm.name}`, finalStatus: Status.FAILED };
            if (isWaiting) return { success: true, finalStatus: Status.WAITING };
            if (isFailed) return { success: false, error: `Tool set status to FAILED`, finalStatus: Status.FAILED };

            return { success: true, finalStatus: Status.DONE };
        } catch (error: any) {
            log('error', `Tool exception ${tool.name} on ${shortId(trigger.id)}: ${error.message}`, error.stack);
            return { success: false, error: `Tool exception: ${error.message}`, finalStatus: Status.FAILED };
        }
    }
};

const FallbackHandler = {
    async handle(thought: Thought, context: ToolContext): Promise<FallbackResult> {
        log('debug', `Applying fallback handler for thought ${shortId(thought.id)} (${thought.type})`);
        let action: Term | null = null;
        let llmPromptKey: PromptKey | null = null;
        let llmContext: Record<string, string> = {};
        let targetType: Type | null = null;
        const contentStr = Terms.toString(thought.content).substring(0, 100);

        switch (thought.type) {
            case Type.INPUT: llmPromptKey = 'GENERATE_GOAL'; llmContext = { input: contentStr }; targetType = Type.GOAL; break;
            case Type.GOAL: llmPromptKey = 'GENERATE_STRATEGY'; llmContext = { goal: contentStr }; targetType = Type.STRATEGY; break;
            case Type.STRATEGY: llmPromptKey = 'GENERATE_OUTCOME'; llmContext = { strategy: contentStr }; targetType = Type.OUTCOME; break;
            case Type.OUTCOME: case Type.FACT: action = Struct("MemoryTool", [Atom("add"), thought.content]); break;
            case Type.LOG: case Type.SYSTEM: return { success: true, finalStatus: Status.DONE }; // Logs/System ticks often don't need fallback
            default:
                const askUserContext = { thoughtId: shortId(thought.id), thoughtType: thought.type, thoughtContent: contentStr };
                action = Struct("UserInteractionTool", [Atom("prompt"), Atom("FALLBACK_ASK_USER"), Struct("context", Object.entries(askUserContext).map(([k, v]) => Atom(`${k}:${v}`)))]);
                break;
        }

        if (llmPromptKey && targetType) {
            const prompt = context.prompts.format(llmPromptKey, llmContext);
            const resultText = await context.llm.generate(prompt);
            if (resultText && !resultText.startsWith('Error:')) {
                resultText.split('\n').map(s => s.trim().replace(/^- /, '')).filter(s => s.length > 0).forEach(resText => {
                    context.engine.addThought({
                        id: generateId(), type: targetType!, content: Atom(resText), belief: Belief.DEFAULT, status: Status.PENDING,
                        metadata: { rootId: thought.metadata.rootId ?? thought.id, parentId: thought.id, created: new Date().toISOString(), provenance: 'llm_fallback' }
                    });
                });
                return { success: true, finalStatus: Status.DONE };
            } else { return { success: false, error: `LLM fallback failed: ${resultText}`, finalStatus: Status.FAILED }; }
        } else if (action) { return ActionExecutor.execute(action, context, thought); }
        else { return { success: false, error: "No rule match and no fallback action defined.", finalStatus: Status.FAILED }; }
    }
};

// --- Engine ---
class Engine {
    private activeIds = new Set<string>();
    private context: ToolContext;

    constructor(
        private thoughts: Thoughts, private rules: Rules, private memory: Memory,
        private llm: LLM, private tools: Tools, private prompts: Prompts,
        private batchSize: number = 5, private maxConcurrent: number = 3
    ) {
        this.context = { thoughts: this.thoughts, rules: this.rules, memory: this.memory, llm: this.llm, tools: this.tools, prompts: this.prompts, engine: this };
    }

    addThought(thought: Thought): void { this.thoughts.add(thought); }

    private sampleThought(): Thought | null {
        const candidates = this.thoughts.getPending().filter(t => {
            if (this.activeIds.has(t.id)) return false;
            const rootId = t.metadata.rootId ?? t.id;
            const rootThought = this.thoughts.get(rootId);
            return !rootThought || rootThought.metadata.taskStatus !== 'PAUSED';
        });
        if (candidates.length === 0) return null;

        const weights = candidates.map(t => Math.max(0.01, t.metadata.priority ?? t.belief.score()));
        const totalWeight = weights.reduce((sum, w) => sum + w, 0);

        if (totalWeight <= 0) return candidates[Math.floor(Math.random() * candidates.length)];

        let random = Math.random() * totalWeight;
        for (let i = 0; i < candidates.length; i++) {
            random -= weights[i];
            if (random <= 0) return candidates[i];
        }
        return candidates[candidates.length - 1]; // Fallback
    }

    private updateThoughtStatus(thought: Thought, result: ActionResult | FallbackResult, rule?: Rule): void {
        const currentThought = this.thoughts.get(thought.id);
        if (!currentThought) return;

        currentThought.metadata.ruleId = rule?.id;
        currentThought.belief.update(result.success);

        if (result.success) {
            currentThought.status = result.finalStatus ?? Status.DONE;
            delete currentThought.metadata.error;
            delete currentThought.metadata.retries;
            if (currentThought.status !== Status.WAITING) delete currentThought.metadata.waitingFor;
        } else {
            const retries = (currentThought.metadata.retries ?? 0) + 1;
            currentThought.status = retries >= MAX_RETRIES ? Status.FAILED : Status.PENDING;
            currentThought.metadata.error = (result.error ?? "Unknown processing error").substring(0, 250);
            currentThought.metadata.retries = retries;
            log('warn', `Thought ${shortId(currentThought.id)} failed (Attempt ${retries}): ${currentThought.metadata.error}`);
            if (currentThought.status === Status.FAILED && currentThought.type !== Type.LOG) {
                this.addThought({
                    id: generateId(), type: Type.LOG,
                    content: Struct("failure", [
                        Struct("thoughtId", [Atom(currentThought.id)]),
                        Struct("content", [currentThought.content]),
                        Struct("error", [Atom(currentThought.metadata.error ?? "Unknown")])
                    ]),
                    belief: Belief.DEFAULT, status: Status.PENDING,
                    metadata: { rootId: currentThought.metadata.rootId ?? currentThought.id, parentId: currentThought.id, created: new Date().toISOString(), provenance: 'engine_failure_log' }
                });
            }
        }
        this.thoughts.update(currentThought);
    }

    private async _applyRule(match: MatchResult, thought: Thought): Promise<ActionResult> {
        const boundAction = Terms.substitute(match.rule.action, match.bindings);
        const result = await ActionExecutor.execute(boundAction, this.context, thought, match.rule);
        match.rule.belief.update(result.success);
        this.rules.update(match.rule);
        return result;
    }

    private async _applyFallback(thought: Thought): Promise<FallbackResult> {
        return await FallbackHandler.handle(thought, this.context);
    }

    private async _processThought(thought: Thought): Promise<boolean> {
        try {
            log('info', `Processing ${thought.type} ${shortId(thought.id)}: ${Terms.format(thought.content, false).substring(0, 80)}`);
            thought.status = Status.ACTIVE;
            thought.metadata.agentId = 'worker';
            this.thoughts.update(thought);

            const match = RuleMatcher.match(thought, this.rules.getAll());
            let result: ActionResult | FallbackResult;
            let appliedRule: Rule | undefined = undefined;

            if (match) { appliedRule = match.rule; result = await this._applyRule(match, thought); }
            else { result = await this._applyFallback(thought); }

            this.updateThoughtStatus(thought, result, appliedRule);
            const finalStatus = this.thoughts.get(thought.id)?.status;
            return finalStatus !== Status.PENDING && finalStatus !== Status.ACTIVE;

        } catch (error: any) {
            log('error', `Critical error processing ${shortId(thought.id)}: ${error.message}`, error.stack);
            this.updateThoughtStatus(thought, { success: false, error: `Unhandled exception: ${error.message}`, finalStatus: Status.FAILED });
            return true; // Completed (with error)
        } finally {
            this.activeIds.delete(thought.id);
            const finalThoughtState = this.thoughts.get(thought.id);
            if (finalThoughtState?.status === Status.ACTIVE) {
                log('warn', `Thought ${shortId(thought.id)} ended ACTIVE. Setting FAILED.`);
                this.updateThoughtStatus(finalThoughtState, { success: false, error: "Processing ended while ACTIVE.", finalStatus: Status.FAILED });
            }
        }
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
        await Promise.all(promises);
        log('debug', `Processed batch of ${promises.length} thoughts.`);
        return promises.length;
    }

    async handlePromptResponse(promptId: string, responseText: string): Promise<boolean> {
        const promptThought = this.thoughts.findPendingPrompt(promptId);
        if (!promptThought) { log('error', `Pending prompt thought for ID ${shortId(promptId)} not found.`); return false; }

        const waitingThought = this.thoughts.findWaitingThought(promptId);
        if (!waitingThought) {
            log('warn', `No thought found waiting for prompt ${shortId(promptId)}. Marking prompt as done.`);
            promptThought.status = Status.DONE;
            promptThought.metadata.error = "No waiting thought found upon response.";
            this.thoughts.update(promptThought);
            return false;
        }

        this.addThought({
            id: generateId(), type: Type.INPUT, content: Atom(responseText), belief: new Belief(1, 0), status: Status.PENDING,
            metadata: {
                rootId: waitingThought.metadata.rootId ?? waitingThought.id, parentId: waitingThought.id, created: new Date().toISOString(),
                responseTo: promptId, tags: ['user_response'], provenance: 'user_input'
            }
        });

        promptThought.status = Status.DONE; this.thoughts.update(promptThought);
        waitingThought.status = Status.PENDING; delete waitingThought.metadata.waitingFor;
        waitingThought.belief.update(true); // User responding generally means the waiting was 'successful'
        this.thoughts.update(waitingThought);

        log('info', `Response for ${shortId(promptId)} received. Thought ${shortId(waitingThought.id)} reactivated.`);
        return true;
    }
}

// --- Persistence ---
const saveState = async (thoughts: Thoughts, rules: Rules, memory: Memory): Promise<void> => {
    try {
        await fs.mkdir(DATA_DIR, { recursive: true });
        const state: AppState = { thoughts: thoughts.toJSON(), rules: rules.toJSON() };
        await fs.writeFile(STATE_FILE, JSON.stringify(state, null, 2));
        await memory.save();
        log('debug', 'State saved successfully.');
    } catch (error: any) { log('error', 'Error saving state:', error.message); }
};
const debouncedSaveState = debounce(saveState, SAVE_DEBOUNCE);

const loadState = async (thoughts: Thoughts, rules: Rules, memory: Memory): Promise<void> => {
    try {
        await fs.mkdir(DATA_DIR, { recursive: true });
        await memory.initialize();
        if (!memory.isReady) log('error', "Memory store failed to initialize. State loading might be incomplete.");

        if (fsSync.existsSync(STATE_FILE)) {
            const data = await fs.readFile(STATE_FILE, 'utf-8');
            const state: AppState = safeJsonParse(data, { thoughts: {}, rules: {} });
            thoughts.loadJSON(state.thoughts);
            rules.loadJSON(state.rules);
        } else { log('warn', `State file ${STATE_FILE} not found. Starting fresh.`); }
    } catch (error: any) {
        log('error', 'Error loading state:', error.message);
        if (!memory.isReady) await memory.initialize(); // Attempt memory init even if file load fails
    }
};

// --- WebSocket API Server ---
class ApiServer {
    private wss: WebSocketServer | null = null;
    private clients = new Map<WebSocket, { ip: string }>();
    private commandHandlers = new Map<string, (payload: any, ws: WebSocket) => Promise<any | void>>();

    constructor(
        private port: number, private thoughts: Thoughts, private rules: Rules,
        private memory: Memory, private engine: Engine, private tools: Tools,
        private systemControl: SystemControl
    ) {
        this.registerCommandHandlers();
        this.thoughts.addChangeListener(() => this.broadcastChanges(this.thoughts, 'thoughts'));
        this.rules.addChangeListener(() => this.broadcastChanges(this.rules, 'rules'));
    }

    start(): void {
        this.wss = new WebSocketServer({ port: this.port });
        this.wss.on('listening', () => log('info', `🚀 FlowMind API listening on ws://localhost:${this.port}`));
    this.wss.on('connection', (ws, req) => this.handleConnection(ws, req as IncomingMessage));
this.wss.on('error', (error) => log('error', 'WebSocket Server Error:', error.message));
}

stop(): void {
    log('warn', 'Stopping API server...');
this.wss?.clients.forEach(ws => ws.close());
this.wss?.close();
this.clients.clear();
}

private handleConnection(ws: WebSocket, req: IncomingMessage): void {
    const ip = req.socket.remoteAddress || 'unknown';
    log('info', `Client connected: ${ip}`);
this.clients.set(ws, { ip });
ws.on('message', (message) => this.handleMessage(ws, message as Buffer));
ws.on('close', () => this.handleDisconnection(ws));
ws.on('error', (error) => { log('error', `WebSocket error from ${ip}: ${error.message}`); this.handleDisconnection(ws); });
this.sendSnapshot(ws);
}

private handleDisconnection(ws: WebSocket): void {
    const clientInfo = this.clients.get(ws);
    log('info', `Client disconnected: ${clientInfo?.ip || 'unknown'}`);
this.clients.delete(ws);
}

private async handleMessage(ws: WebSocket, message: Buffer): Promise<void> {
    let request: { command: string; payload?: any; requestId?: string } | null = null;
try {
    request = JSON.parse(message.toString());
    if (typeof request?.command !== 'string') throw new Error("Invalid command format");
} catch (e: any) { this.send(ws, { type: 'error', payload: `Invalid JSON: ${e.message}`, requestId: request?.requestId }); return; }

const handler = this.commandHandlers.get(request.command);
if (!handler) { this.send(ws, { type: 'error', payload: `Unknown command: ${request.command}`, requestId: request.requestId }); return; }

try {
    log('debug', `Executing command "${request.command}" from client`);
    const result = await handler(request.payload ?? {}, ws);
    this.send(ws, { type: 'response', payload: result ?? null, success: true, requestId: request.requestId });

    if (!['thoughts', 'rules', 'tasks', 'task', 'status', 'info', 'search', 'help', 'tools', 'ping'].includes(request.command)) {
        debouncedSaveState(this.thoughts, this.rules, this.memory);
    }
} catch (error: any) {
    log('error', `Error executing command "${request.command}": ${error.message}`);
    this.send(ws, { type: 'response', payload: error.message || 'Command failed.', success: false, requestId: request.requestId });
}
}

private send(ws: WebSocket, data: any): void { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(data)); }

broadcast(data: any): void {
    const message = JSON.stringify(data);
    this.clients.forEach((_, ws) => { if (ws.readyState === WebSocket.OPEN) ws.send(message); });
}

private broadcastChanges(store: BaseStore<any>, type: string): void {
    const delta = store.getDelta();
    if (delta.changed.length > 0 || delta.deleted.length > 0) {
    this.broadcast({ type: `${type}_delta`, payload: delta });
    log('debug', `Broadcasting ${type} delta: ${delta.changed.length} changed, ${delta.deleted.length} deleted`);
}
}

private sendSnapshot(ws: WebSocket): void {
    this.send(ws, {
        type: 'snapshot', payload: {
            thoughts: this.thoughts.getAll().map(t => ({ ...t, belief: t.belief.toJSON() })),
            rules: this.rules.getAll().map(r => ({ ...r, belief: r.belief.toJSON() })),
            status: this.systemControl.getStatus()
        }
    });
    log('debug', `Sent snapshot to new client`);
}

private _findThoughtOrThrow(idPrefix: string, requireRoot: boolean = false): Thought {
    const thought = this.thoughts.findThought(idPrefix);
    if (!thought) throw new Error(`Thought prefix "${idPrefix}" not found or ambiguous.`);
    if (requireRoot && (thought.metadata.rootId && thought.metadata.rootId !== thought.id)) {
        throw new Error(`Thought ${shortId(thought.id)} is not a root task.`);
    }
    return thought;
}

private _findRuleOrThrow(idPrefix: string): Rule {
    const rule = this.rules.findRule(idPrefix);
    if (!rule) throw new Error(`Rule prefix "${idPrefix}" not found or ambiguous.`);
    return rule;
}

private registerCommandHandlers(): void {
    const handlers: { [key: string]: (payload: any, ws: WebSocket) => Promise<any | void> } = {
    add: async ({ text }) => {
        if (typeof text !== 'string' || !text.trim()) throw new Error("Missing or empty 'text'");
        const newThought: Thought = {
            id: generateId(), type: Type.INPUT, content: Terms.fromString(text), belief: new Belief(1, 0), status: Status.PENDING,
            metadata: { created: new Date().toISOString(), tags: ['user_added'], provenance: 'api', taskStatus: 'RUNNING' }
        };
        newThought.metadata.rootId = newThought.id;
        this.engine.addThought(newThought);
        return { id: newThought.id, message: `Added Task ${newThought.type}: ${shortId(newThought.id)}` };
    },
    respond: async ({ promptId, text }) => {
        if (typeof promptId !== 'string' || !promptId.trim()) throw new Error("Missing 'promptId'");
        if (typeof text !== 'string' || !text.trim()) throw new Error("Missing or empty 'text'");
        const success = await this.engine.handlePromptResponse(promptId, text);
        if (!success) throw new Error(`Failed to process response for prompt ${shortId(promptId)}.`);
        return { message: `Response for ${shortId(promptId)} processed.` };
    },
    run: async () => { this.systemControl.startProcessing(); return { message: "Processing started." }; },
    pause: async () => { this.systemControl.pauseProcessing(); return { message: "Processing paused." }; },
    step: async () => {
        const count = await this.systemControl.stepProcessing();
        return { processed: count > 0, count, message: count > 0 ? `Step processed ${count} thought(s).` : "Nothing to step." };
    },
    save: async () => { await debouncedSaveState.flush(); await saveState(this.thoughts, this.rules, this.memory); return { message: "State saved." }; },
    quit: async () => { await this.systemControl.shutdown(); },
    status: async () => this.systemControl.getStatus(),
    info: async ({ idPrefix }) => {
        if (typeof idPrefix !== 'string' || !idPrefix.trim()) throw new Error("Missing 'idPrefix'");
        const thought = this.thoughts.findThought(idPrefix);
        if (thought) return { type: 'thought', data: { ...thought, belief: thought.belief.toJSON() } };
        const rule = this.rules.findRule(idPrefix);
        if (rule) return { type: 'rule', data: { ...rule, belief: rule.belief.toJSON() } };
        throw new Error(`ID prefix "${idPrefix}" not found or ambiguous.`);
    },
    thoughts: async () => this.thoughts.getAll().map(t => ({ ...t, belief: t.belief.toJSON() })),
    rules: async () => this.rules.getAll().map(r => ({ ...r, belief: r.belief.toJSON() })),
    tools: async () => this.tools.list().map(t => ({ name: t.name, description: t.description })),
    tag: async ({ idPrefix, tag }) => {
        if (typeof idPrefix !== 'string' || !idPrefix.trim()) throw new Error("Missing 'idPrefix'");
        if (typeof tag !== 'string' || !tag.trim()) throw new Error("Missing or empty 'tag'");
        const thought = this._findThoughtOrThrow(idPrefix);
        thought.metadata.tags = [...new Set([...(thought.metadata.tags || []), tag.trim()])];
        this.thoughts.update(thought);
        return { id: thought.id, tags: thought.metadata.tags };
    },
    untag: async ({ idPrefix, tag }) => {
        if (typeof idPrefix !== 'string' || !idPrefix.trim()) throw new Error("Missing 'idPrefix'");
        if (typeof tag !== 'string' || !tag.trim()) throw new Error("Missing or empty 'tag'");
        const thought = this._findThoughtOrThrow(idPrefix);
        if (!thought.metadata.tags) return { id: thought.id, tags: [] };
        thought.metadata.tags = thought.metadata.tags.filter(t => t !== tag.trim());
        this.thoughts.update(thought);
        return { id: thought.id, tags: thought.metadata.tags };
    },
    search: async ({ query, k }) => {
        if (typeof query !== 'string' || !query.trim()) throw new Error("Missing or empty 'query'");
        return this.memory.search(query, typeof k === 'number' ? k : 5);
    },
    delete: async ({ idPrefix }) => {
        if (typeof idPrefix !== 'string' || !idPrefix.trim()) throw new Error("Missing 'idPrefix'");
        const thought = this.thoughts.findThought(idPrefix);
        if (thought) {
            if (this.thoughts.delete(thought.id)) return { id: thought.id, message: `Thought ${shortId(thought.id)} deleted.` };
            else throw new Error(`Failed to delete thought ${shortId(thought.id)}.`);
        }
        const rule = this.rules.findRule(idPrefix);
        if (rule) {
            if (this.rules.delete(rule.id)) return { id: rule.id, message: `Rule ${shortId(rule.id)} deleted.` };
            else throw new Error(`Failed to delete rule ${shortId(rule.id)}.`);
        }
        throw new Error(`Item with prefix "${idPrefix}" not found or ambiguous.`);
    },
    tasks: async () => {
        return this.thoughts.getRootThoughts().map(task => {
            const descendants = this.thoughts.getDescendants(task.id);
            const pendingPrompts = descendants.filter(t => t.type === Type.USER_PROMPT && t.status === Status.PENDING);
            return {
                id: task.id, type: task.type, content: Terms.toString(task.content),
                status: task.status, taskStatus: task.metadata.taskStatus ?? 'RUNNING',
                pendingPrompts: pendingPrompts.map(p => ({ id: p.id, content: Terms.toString(p.content) }))
            };
        });
    },
    task: async ({ idPrefix }) => { // Renamed from get_task_details
        if (typeof idPrefix !== 'string' || !idPrefix.trim()) throw new Error("Missing 'idPrefix'");
        const task = this._findThoughtOrThrow(idPrefix, true);
        const descendants = this.thoughts.getDescendants(task.id);
        const format = (thoughts: Thought[]) => thoughts.map(t => ({
            id: t.id, type: t.type, content: Terms.toString(t.content),
            status: t.status, created: t.metadata.created
        }));
        return {
            task: { ...task, belief: task.belief.toJSON() },
            plan: format(descendants.filter(t => t.type === Type.STRATEGY)),
            prompts: format(descendants.filter(t => t.type === Type.USER_PROMPT)),
            facts: format(descendants.filter(t => t.type === Type.FACT)),
            logs: format(descendants.filter(t => t.type === Type.LOG)),
            other: format(descendants.filter(t => ![Type.STRATEGY, Type.USER_PROMPT, Type.FACT, Type.LOG].includes(t.type))),
        };
    },
    pause_task: async ({ idPrefix }) => {
        if (typeof idPrefix !== 'string' || !idPrefix.trim()) throw new Error("Missing 'idPrefix'");
        const task = this._findThoughtOrThrow(idPrefix, true);
        task.metadata.taskStatus = 'PAUSED'; this.thoughts.update(task);
        return { id: task.id, taskStatus: task.metadata.taskStatus, message: `Task ${shortId(task.id)} paused.` };
    },
    run_task: async ({ idPrefix }) => {
        if (typeof idPrefix !== 'string' || !idPrefix.trim()) throw new Error("Missing 'idPrefix'");
        const task = this._findThoughtOrThrow(idPrefix, true);
        task.metadata.taskStatus = 'RUNNING'; this.thoughts.update(task);
        return { id: task.id, taskStatus: task.metadata.taskStatus, message: `Task ${shortId(task.id)} set to running.` };
    },
    help: async () => ({
        description: "Available commands. Use 'info <idPrefix>' for details.", commands: [
            { cmd: "add <text>", desc: "Add new root INPUT thought (a new Task)." },
            { cmd: "respond <promptId> <text>", desc: "Respond to a pending user prompt." },
            { cmd: "run", desc: "Start global processing loop." }, { cmd: "pause", desc: "Pause global processing loop." },
            { cmd: "step", desc: "Process one batch of thoughts (respects task status)." },
            { cmd: "save", desc: "Force immediate state save." }, { cmd: "quit / exit", desc: "Shutdown the server." },
            { cmd: "status", desc: "Get global processing loop status." },
            { cmd: "info <idPrefix>", desc: "Get details of a thought/rule by ID prefix." },
            { cmd: "thoughts", desc: "Get all thoughts." }, { cmd: "rules", desc: "Get all rules." },
            { cmd: "tasks", desc: "List all root tasks and their status/pending prompts." },
            { cmd: "task <taskIdPrefix>", desc: "Get detailed view of a task (plan, facts, logs, etc.)." },
            { cmd: "run_task <taskIdPrefix>", desc: "Set a specific task to RUNNING state." },
            { cmd: "pause_task <taskIdPrefix>", desc: "Set a specific task to PAUSED state." },
            { cmd: "tools", desc: "List available tools." }, { cmd: "tag <idPrefix> <tag>", desc: "Add tag to thought." },
            { cmd: "untag <idPrefix> <tag>", desc: "Remove tag from thought." },
            { cmd: "search <query> [k=5]", desc: "Search vector memory." },
            { cmd: "delete <idPrefix>", desc: "Delete a thought or rule by ID prefix." },
            { cmd: "ping", desc: "Check server connection." }, { cmd: "help", desc: "Show this help message." },
        ]
    }),
    ping: async () => "pong",
};
for (const [command, handler] of Object.entries(handlers)) this.commandHandlers.set(command, handler.bind(this));
}
}

// --- Main System Orchestrator ---
class System {
    private thoughts = new Thoughts();
    private rules = new Rules();
    private llm = new LLM();
    private memory = new Memory(this.llm.embeddings, VECTOR_STORE_DIR);
    private tools = new Tools();
    private prompts = new Prompts();
    private engine: Engine;
    private apiServer: ApiServer;
    private isRunning = false;
    private workerIntervalId: NodeJS.Timeout | null = null;
    private systemControl: SystemControl;

    constructor() {
        this.engine = new Engine(this.thoughts, this.rules, this.memory, this.llm, this.tools, this.prompts);
        this.addTools();
        this.systemControl = {
            startProcessing: this.startProcessingLoop.bind(this), pauseProcessing: this.pauseProcessingLoop.bind(this),
            stepProcessing: this.engine.processBatch.bind(this.engine), shutdown: this.shutdown.bind(this),
            getStatus: () => ({ isRunning: this.isRunning })
        };
        this.apiServer = new ApiServer(WS_PORT, this.thoughts, this.rules, this.memory, this.engine, this.tools, this.systemControl);
    }

    private addTools(): void {
        [
            new LLMTool(), new MemoryTool(), new UserInteractionTool(), new GoalProposalTool(), new CoreTool(),
            new TimeTool(), new FileSystemTool(), new WebSearchTool(), new ContextSummarizer(), new RuleManager()
        ].forEach(t => this.tools.register(t));
    }

    private addRules(): void {
        if (this.rules.count() > 0) { log('debug', "Skipping bootstrap rules: Rules already exist."); return; }
        log('info', "Bootstrapping default rules...");
        const rule = (desc: string, pattern: Term, action: Term, priority: number = 0): Rule => ({
            id: generateId(), pattern, action, belief: Belief.DEFAULT,
            metadata: { description: desc, provenance: 'bootstrap', created: new Date().toISOString(), priority }
        });
        const rulesToAdd = [
            // Basic Flow & Memory
            rule("Outcome -> Add to Memory", Struct(Type.OUTCOME, [Variable("C")]), Struct("MemoryTool", [Atom("add"), Variable("C")]), 10),
            rule("Fact -> Add to Memory", Struct(Type.FACT, [Variable("C")]), Struct("MemoryTool", [Atom("add"), Variable("C")]), 10),
            rule("Outcome -> Log Outcome", Struct(Type.OUTCOME, [Variable("OutcomeContent")]), Struct("CoreTool", [Atom("add_thought"), Atom(Type.LOG), Struct("logged_outcome", [Variable("OutcomeContent")]), Atom("self"), Atom("self")]), -5),
            rule("Outcome -> Suggest Next Goal", Struct(Type.OUTCOME, [Variable("C")]), Struct("GoalProposalTool", [Atom("suggest"), Variable("C")]), -10),
            // Input Handling & Interaction
            rule("Input Command: Search Memory -> Use MemoryTool", Struct(Type.INPUT, [Struct("search", [Variable("Q")])]), Struct("MemoryTool", [Atom("search"), Variable("Q")]), 15),
            rule("Input Command: Read File -> Use FileSystemTool", Struct(Type.INPUT, [Struct("read", [Variable("Path")])]), Struct("FileSystemTool", [Atom("read_file"), Variable("Path")]), 15),
            rule("Input Command: Write File -> Use FileSystemTool", Struct(Type.INPUT, [Struct("write", [Variable("Path"), Variable("Content")])]), Struct("FileSystemTool", [Atom("write_file"), Variable("Path"), Variable("Content")]), 15),
            rule("Confirm Generated Goal", Struct(Type.GOAL, [Variable("GoalContent")]), Struct("UserInteractionTool", [Atom("prompt"), Atom("CONFIRM_GOAL"), Struct("context", [Atom("goal:?GoalContent")])]), 5),
            rule("Clarify Brief Input (Atom)", Struct(Type.INPUT, [Atom("?InputAtom")]), Struct("UserInteractionTool", [Atom("prompt"), Atom("CLARIFY_INPUT"), Struct("context", [Atom("input:?InputAtom")])]), -5), // Lower priority, specific to short atoms
            rule("Confirm Strategy Step", Struct(Type.STRATEGY, [Variable("StrategyContent")]), Struct("UserInteractionTool", [Atom("prompt"), Atom("CONFIRM_STRATEGY"), Struct("context", [Atom("strategy:?StrategyContent")])]), 0),
            // Status & Control
            rule("Thought 'ok' -> Set Done", Atom("ok"), Struct("CoreTool", [Atom("set_status"), Atom("self"), Atom(Status.DONE)]), 1),
            // Failure Handling
            rule("Self-Correction: Simple Retry", Struct(Type.LOG, [Struct("failure", [Struct("thoughtId", [Variable("FailedId")]), Struct("content", [Variable("FailedContent")]), Struct("error", [Variable("ErrorMsg")])])]), Struct("CoreTool", [Atom("set_status"), Variable("FailedId"), Atom(Status.PENDING)]), 40),
            // rule("Self-Correction: LLM Strategy", Struct(Type.LOG, [Struct("failure", [Struct("thoughtId", [Variable("FailedId")]), Struct("content", [Variable("FailedContent")]), Struct("error", [Variable("ErrorMsg")])])]), Struct("LLMTool", [Atom("generate"), Atom("GENERATE_RECOVERY_STRATEGY"), Struct("context", [Atom("failedId:?FailedId"), Atom("failedContent:?FailedContent"), Atom("error:?ErrorMsg")])]), 39), // Lower priority than retry
            rule("Ask User How to Handle Failure", Struct(Type.LOG, [Struct("failure", [Struct("thoughtId", [Variable("FailedId")]), Struct("content", [Variable("FailedContent")]), Struct("error", [Variable("ErrorMsg")])])]), Struct("UserInteractionTool", [Atom("prompt"), Atom("HANDLE_FAILURE"), Struct("context", [Atom("failedThoughtId:?FailedId"), Atom("content:?FailedContent"), Atom("error:?ErrorMsg")])]), 30), // Lower priority than automatic attempts
            // Time & Maintenance
            rule("Initialize Periodic Tick", Struct(Type.SYSTEM, [Atom("startup")]), Struct("TimeTool", [Atom("periodic_tick"), Atom(TICK_INTERVAL_MS.toString())]), 200), // High priority on startup
            rule("Wake Up Timed Waits", Struct(Type.SYSTEM, [Struct("tick", [Atom("?CurrentTime")])]), Struct("CoreTool", [Atom("log"), Atom("debug"), Atom("Checking timed waits")]), -50), // Placeholder action, needs logic
            rule("Detect Stuck Thoughts", Struct(Type.SYSTEM, [Struct("tick", [Atom("?CurrentTime")])]), Struct("CoreTool", [Atom("log"), Atom("debug"), Atom("Checking stuck thoughts")]), -60), // Placeholder action, needs logic
            // Task Completion
            rule("Task Completion: Log Summary", Struct(Type.LOG, [Struct("task_completed", [Variable("GoalId")])]), Struct("ContextSummarizer", [Atom("summarize"), Variable("GoalId")]), 90),
            rule("Task Completion: Add Log", Struct(Type.CONTEXT_SUMMARY, [Variable("Summary")]), Struct("CoreTool", [Atom("add_thought"), Atom(Type.LOG), Struct("task_summary", [Variable("Summary")])]), 80),
            // rule("Task Completion: User Prompt", Struct(Type.LOG, [Struct("task_completed", [Variable("GoalId")])]), Struct("UserInteractionTool", [Atom("prompt"), Atom("NEXT_TASK_PROMPT"), Struct("context", [Atom("taskId:?GoalId"), Atom("taskContent:?")])]), 100), // Needs GoalContent binding
        ];
        rulesToAdd.forEach(r => this.rules.add(r));
        // Add initial system thought to trigger startup rules
        this.engine.addThought({
            id: generateId(), type: Type.SYSTEM, content: Atom("startup"), belief: Belief.DEFAULT, status: Status.PENDING,
            metadata: { created: new Date().toISOString(), provenance: 'system_init', priority: 300 }
        });
        log('info', `${this.rules.count()} default rules added.`);
    }

    async initialize(): Promise<void> {
        log('info', 'Initializing FlowMind System...');
        await loadState(this.thoughts, this.rules, this.memory);
        this.addRules();
        this.apiServer.start();
        log('info', chalk.green('FlowMind Initialized.') + chalk.yellow(" Paused. Use API 'run' command or REPL 'run' to start processing."));
    }

    startProcessingLoop(): void {
        if (this.isRunning) { log('warn', "Processing already running."); return; }
        log('info', 'Starting processing loop...');
        this.isRunning = true;
        this.apiServer.broadcast({ type: 'status_update', payload: this.systemControl.getStatus() });
        this.workerIntervalId = setInterval(async () => {
            if (!this.isRunning) return;
            try {
                // Check for timed waits to wake up
                const now = Date.now();
                this.thoughts.getAll().forEach(t => {
                    if (t.status === Status.WAITING && typeof t.metadata.waitingFor === 'object' && t.metadata.waitingFor.type === 'time' && t.metadata.waitingFor.timestamp <= now) {
                        log('debug', `Waking up thought ${shortId(t.id)} from timed wait.`);
                        t.status = Status.PENDING;
                        delete t.metadata.waitingFor;
                        this.thoughts.update(t);
                    }
                });
                // Process batch
                if (await this.engine.processBatch() > 0) {
                    debouncedSaveState(this.thoughts, this.rules, this.memory);
                }
            } catch (error: any) {
                log('error', 'Error in processing loop:', error.message);
                this.pauseProcessingLoop();
            }
        }, WORKER_INTERVAL);
    }

    pauseProcessingLoop(): void {
        if (!this.isRunning) { log('warn', "Processing already paused."); return; }
        log('warn', 'Pausing processing loop...');
        this.isRunning = false;
        this.apiServer.broadcast({ type: 'status_update', payload: this.systemControl.getStatus() });
        if (this.workerIntervalId) { clearInterval(this.workerIntervalId); this.workerIntervalId = null; }
        debouncedSaveState.flush();
        saveState(this.thoughts, this.rules, this.memory);
    }

    async shutdown(): Promise<void> {
        log('info', '\nShutting down FlowMind System...');
        this.pauseProcessingLoop();
        this.apiServer.stop();
        debouncedSaveState.cancel();
        await saveState(this.thoughts, this.rules, this.memory);
        log('info', chalk.green('FlowMind shutdown complete. Goodbye!'));
        await sleep(100);
        process.exit(0);
    }
}

// --- REPL Client Utilities ---
const contentStr = (t: any): string => t?.content ? (typeof t.content === 'string' ? t.content : Terms.format(t.content, false)) : '';
const formatThought = (t: Thought | any, detail = false): string => {
    const thoughtContent = contentStr(t);
    const detailLimit = detail ? 150 : 50;
    const statusColor = t.status === Status.FAILED ? chalk.red : (t.status === Status.DONE ? chalk.gray : (t.status === Status.WAITING ? chalk.yellow : chalk.cyan));
    return `${chalk.magenta(shortId(t.id))}|${chalk.yellow(t.type)}|${statusColor(t.status ?? 'N/A')}|${thoughtContent.substring(0, detailLimit)}${thoughtContent.length > detailLimit ? '...' : ''}`;
};
const formatRule = (r: Rule | any): string => `${chalk.blue(shortId(r.id))}|P${r.metadata?.priority ?? 0}|${Terms.format(r.pattern, false)} -> ${Terms.format(r.action, false)} (${(r.metadata?.description ?? '').substring(0, 40)}...)`;
const formatTaskSummary = (t: any): string => {
    const taskContent = contentStr(t);
    const prompts = t.pendingPrompts || [];
    const statusColor = t.taskStatus === 'RUNNING' ? chalk.green : chalk.yellow;
    return `${chalk.magenta(shortId(t.id))}|${chalk.yellow(t.type)}|${statusColor(t.taskStatus)}|${taskContent.substring(0, 60)}${taskContent.length > 60 ? '...' : ''}${prompts.length > 0 ? chalk.red(` [${prompts.length} Prompt(s)]`) : ''}`;
};
const formatTaskDetailItem = (t: any): string => {
    const taskContent = contentStr(t);
    const statusColor = t.status === Status.FAILED ? chalk.red : (t.status === Status.DONE ? chalk.gray : (t.status === Status.WAITING ? chalk.yellow : chalk.cyan));
    return `  - ${chalk.magenta(shortId(t.id))}|${chalk.yellow(t.type)}|${statusColor(t.status)}|${taskContent.substring(0, 100)}${taskContent.length > 100 ? '...' : ''}`;
};

// --- REPL Client ---
async function startReplClient(serverUrl: string) {
    console.log(chalk.cyan(`Connecting to FlowMind server at ${serverUrl}...`));
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout, prompt: chalk.blueBright('FM Client> ') });
    let ws: WebSocket | null = null;
    let requestCounter = 0;
    const pendingRequests = new Map<string, { resolve: (value: any) => void, reject: (reason?: any) => void, command: string }>();
    const reconnectDelay = 5000;
    let reconnectTimeout: NodeJS.Timeout | null = null;
    let shuttingDown = false;

    const connect = () => {
        if (shuttingDown || (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING))) return;
        if (reconnectTimeout) clearTimeout(reconnectTimeout); reconnectTimeout = null;
        console.log(chalk.yellow(`Attempting connection to ${serverUrl}...`));
        ws = new WebSocket(serverUrl);

        ws.on('open', () => { console.log(chalk.green(`Connected! Type 'help' for commands.`)); rl.prompt(); });
        ws.on('message', (data) => {
            const message = safeJsonParse(data.toString(), null);
            if (!message) { console.log(chalk.yellow('Received non-JSON message:', data.toString().substring(0, 100))); return; }
            const { type, payload, success, requestId, ...rest } = message;
            process.stdout.write('\n');

            if (requestId && pendingRequests.has(requestId)) {
                const reqInfo = pendingRequests.get(requestId)!; pendingRequests.delete(requestId);
                switch (type) {
                    case 'response': success ? reqInfo.resolve(payload) : reqInfo.reject(new Error(payload || `Command '${reqInfo.command}' failed.`)); break;
                    case 'error': reqInfo.reject(new Error(payload || `Server error for '${reqInfo.command}'.`)); break;
                    default: reqInfo.reject(new Error(`Unexpected message type '${type}' for request '${reqInfo.command}'.`)); break;
                }
            } else { // Broadcast messages
                switch (type) {
                    case 'error': console.error(chalk.red(`Server Error: ${payload}`)); break;
                    case 'snapshot':
                        console.log(chalk.magenta('--- Received State Snapshot ---'));
                        console.log(chalk.cyan(`Thoughts: ${payload.thoughts?.length ?? 0}, Rules: ${payload.rules?.length ?? 0}, Status: ${payload.status?.isRunning ? chalk.green('RUNNING') : chalk.yellow('PAUSED')}`));
                        break;
                    case 'thoughts_delta':
                        if (payload.changed.length > 0) console.log(chalk.green(`Thoughts Updated: ${payload.changed.map((t: Thought) => shortId(t.id)).join(', ')}`));
                        if (payload.deleted.length > 0) console.log(chalk.red(`Thoughts Deleted: ${payload.deleted.map(shortId).join(', ')}`));
                        break;
                    case 'rules_delta':
                        if (payload.changed.length > 0) console.log(chalk.blue(`Rules Updated: ${payload.changed.map((r: Rule) => shortId(r.id)).join(', ')}`));
                        if (payload.deleted.length > 0) console.log(chalk.yellow(`Rules Deleted: ${payload.deleted.map(shortId).join(', ')}`));
                        break;
                    case 'status_update': console.log(chalk.magenta(`System Status: ${payload.isRunning ? chalk.green('RUNNING') : chalk.yellow('PAUSED')}`)); break;
                    default: console.log(chalk.gray(`Server Message [${type || 'unknown'}]:`), payload ?? rest); break;
                }
                rl.prompt();
            }
        });
        ws.on('close', () => {
            if (shuttingDown) return;
            console.log(chalk.red('\nDisconnected. Attempting reconnect...')); ws = null;
            pendingRequests.forEach(p => p.reject(new Error("Disconnected"))); pendingRequests.clear();
            if (!reconnectTimeout) reconnectTimeout = setTimeout(connect, reconnectDelay);
        });
        ws.on('error', (error) => {
            if (shuttingDown) return;
            console.error(chalk.red(`\nConnection Error: ${error.message}. Retrying...`)); ws?.close(); ws = null;
            if (!reconnectTimeout) reconnectTimeout = setTimeout(connect, reconnectDelay);
        });
    };

    const sendCommand = (command: string, payload: any): Promise<any> => {
        return new Promise((resolve, reject) => {
            if (!ws || ws.readyState !== WebSocket.OPEN) { reject(new Error("Not connected.")); return; }
            const requestId = `req-${requestCounter++}`;
            pendingRequests.set(requestId, { resolve, reject, command });
            ws.send(JSON.stringify({ command, payload, requestId }));
            setTimeout(() => {
                if (pendingRequests.has(requestId)) {
                    pendingRequests.delete(requestId); reject(new Error(`Request timeout for command '${command}'.`)); rl.prompt();
                }
            }, 15000);
        });
    };

    rl.on('line', async (line) => {
        const trimmed = line.trim();
        if (!trimmed) { rl.prompt(); return; }
        if (trimmed === 'quit' || trimmed === 'exit') { rl.close(); return; }

        const parts = trimmed.match(/(?:[^\s"]+|"[^"]*")+/g) ?? [];
        const command = parts[0]?.toLowerCase();
        const args = parts.slice(1).map(arg => arg.startsWith('"') && arg.endsWith('"') ? arg.slice(1, -1) : arg);
        if (!command) { rl.prompt(); return; }

        let payload: any = {}; let valid = true;
        try {
            switch (command) {
                case 'add': payload = { text: args.join(' ') }; valid = args.length >= 1; break;
                case 'respond': payload = { promptId: args[0], text: args.slice(1).join(' ') }; valid = args.length >= 2; break;
                case 'info': case 'delete': case 'task': case 'pause_task': case 'run_task': payload = { idPrefix: args[0] }; valid = args.length === 1; break;
                case 'tag': case 'untag': payload = { idPrefix: args[0], tag: args[1] }; valid = args.length === 2; break;
                case 'search': payload = { query: args.join(' '), k: 5 }; valid = args.length >= 1; break;
                case 'run': case 'pause': case 'step': case 'save': case 'status': case 'thoughts': case 'rules': case 'tasks': case 'tools': case 'help': case 'ping': payload = {}; valid = args.length === 0; break;
                default: console.log(chalk.yellow(`Unknown command: ${command}. Type 'help'.`)); valid = false; break;
            }
            if (!valid) { console.log(chalk.yellow(`Invalid arguments for '${command}'. Type 'help'.`)); rl.prompt(); return; }

            const result = await sendCommand(command, payload);

            if (command === 'help' && result?.commands) {
                console.log(chalk.cyan(result.description ?? 'Help:'));
                result.commands.forEach((c: { cmd: string, desc: string }) => console.log(`  ${chalk.blueBright(c.cmd)}: ${c.desc}`));
            } else if (command === 'info' && result?.type === 'thought') {
                console.log(chalk.cyan('--- Thought Info ---')); console.log(formatThought(result.data, true)); console.log(chalk.gray(JSON.stringify(result.data.metadata, null, 2)));
            } else if (command === 'info' && result?.type === 'rule') {
                console.log(chalk.cyan('--- Rule Info ---')); console.log(formatRule(result.data)); console.log(chalk.gray(JSON.stringify(result.data.metadata, null, 2)));
            } else if (command === 'thoughts' && Array.isArray(result)) {
                console.log(chalk.cyan(`--- All Thoughts (${result.length}) ---`)); result.forEach((t: Thought) => console.log(formatThought(t)));
            } else if (command === 'rules' && Array.isArray(result)) {
                console.log(chalk.cyan(`--- All Rules (${result.length}) ---`)); result.forEach((r: Rule) => console.log(formatRule(r)));
            } else if (command === 'tasks' && Array.isArray(result)) {
                console.log(chalk.cyan(`--- Tasks (${result.length}) ---`)); result.forEach((t: any) => console.log(formatTaskSummary(t)));
            } else if (command === 'task' && result?.task) { // Renamed from get_task_details
                console.log(chalk.cyan(`--- Task Details: ${formatTaskSummary(result.task)} ---`));
                const printSection = (title: string, items: any[] | undefined) => { if (items?.length) { console.log(chalk.blueBright(title + ':')); items.forEach((i: any) => console.log(formatTaskDetailItem(i))); } };
                printSection('Plan (Strategies)', result.plan); printSection('Facts', result.facts); printSection('Prompts', result.prompts); printSection('Logs', result.logs); printSection('Other Thoughts', result.other);
            } else if (command === 'tools' && Array.isArray(result)) {
                console.log(chalk.cyan('--- Available Tools ---')); result.forEach((t: { name: string, description: string }) => console.log(`  ${chalk.blueBright(t.name)}: ${t.description}`));
            } else if (result !== null && result !== undefined) { console.log(chalk.cyan(`Result:`), typeof result === 'object' ? JSON.stringify(result, null, 2) : result); }
            else { console.log(chalk.green('OK')); }

        } catch (error: any) { console.error(chalk.red(`Error:`), error.message); }
        finally { rl.prompt(); }
    });

    rl.on('close', () => {
        shuttingDown = true; console.log(chalk.yellow('\nREPL Client exiting.'));
        if (reconnectTimeout) clearTimeout(reconnectTimeout);
        if (ws && ws.readyState === WebSocket.OPEN) ws.close();
        setTimeout(() => process.exit(0), 100);
    });

    connect();
}

// --- Main Execution ---
async function main() {
    const mode = process.argv[2] || 'server';

    if (mode === 'server') {
        let system: System | null = null;
        const handleShutdown = async (signal: string) => {
            log('warn', `\n${signal} received. Shutting down...`);
            if (system) await system.shutdown(); else process.exit(0);
        };
        process.on('SIGINT', () => handleShutdown('SIGINT'));
        process.on('SIGTERM', () => handleShutdown('SIGTERM'));
        try { system = new System(); await system.initialize(); }
        catch (error: any) { log('error', "Critical initialization error:", error.message); console.error(error.stack); process.exit(1); }
    } else if (mode === 'client') {
        const serverUrl = process.argv[3] || `ws://localhost:${WS_PORT}`;
        await startReplClient(serverUrl);
    } else { console.error(chalk.red(`Invalid mode: ${mode}. Use 'server' or 'client [url]'`)); process.exit(1); }
}

main().catch(error => {
    log('error', 'Unhandled error in main execution:', error.message);
    console.error(error.stack);
    process.exit(1);
});
