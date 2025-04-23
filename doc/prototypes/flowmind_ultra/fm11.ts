import * as fs from 'fs/promises';
import * as fsSync from 'fs';
import * as path from 'path';
import * as os from 'os';
import * as readline from 'readline';
import {v4 as uuidv4} from 'uuid';
import chalk from 'chalk';
import {WebSocket, WebSocketServer} from 'ws';
import {ChatOllama} from "@langchain/community/chat_models/ollama";
import {OllamaEmbeddings} from "@langchain/community/embeddings/ollama";
import {BaseChatModel} from "@langchain/core/language_models/chat_models";
import {Embeddings} from "@langchain/core/embeddings";
import {HumanMessage} from "@langchain/core/messages";
import {StringOutputParser} from "@langchain/core/output_parsers";
import {Document} from "@langchain/core/documents";
import {FaissStore} from "@langchain/community/vectorstores/faiss";

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
const LOG_LEVEL = process.env.LOG_LEVEL || 'info'; // 'debug', 'info', 'warn', 'error'

// --- Types ---
enum Status { PENDING = 'PENDING', ACTIVE = 'ACTIVE', WAITING = 'WAITING', DONE = 'DONE', FAILED = 'FAILED' }
enum Type {
    INPUT = 'INPUT', GOAL = 'GOAL', STRATEGY = 'STRATEGY', OUTCOME = 'OUTCOME',
    QUERY = 'QUERY', USER_PROMPT = 'USER_PROMPT', SYSTEM = 'SYSTEM'
}
type Term = Atom | Variable | Structure | ListTerm;
interface Atom { kind: 'Atom'; name: string; }
interface Variable { kind: 'Variable'; name: string; }
interface Structure { kind: 'Structure'; name: string; args: Term[]; }
interface ListTerm { kind: 'ListTerm'; elements: Term[]; }
interface BeliefData { pos: number; neg: number; }
interface Metadata {
    rootId?: string; parentId?: string; ruleId?: string; agentId?: string;
    created?: string; modified?: string; priority?: number; error?: string;
    promptId?: string; tags?: string[]; feedback?: any[]; embedded?: string;
    suggestions?: string[]; retries?: number; waitingFor?: string; responseTo?: string;
    provenance?: string; [key: string]: any;
}
interface Thought {
    id: string; type: Type; content: Term; belief: Belief; status: Status; metadata: Metadata;
}
interface Rule {
    id: string; pattern: Term; action: Term; belief: Belief;
    metadata: { priority?: number; description?: string; provenance?: string; created?: string; };
}
interface MemoryEntry {
    id: string; embedding?: number[]; content: string;
    metadata: { created: string; type: string; sourceId: string; }; score?: number;
}
interface AppState { thoughts: Record<string, any>; rules: Record<string, any>; }
interface ToolContext {
    thoughts: ThoughtStore; tools: ToolRegistry; rules: RuleStore; memory: MemoryStore;
    llm: LLMService; prompts: PromptRegistry; engine: Engine;
}
interface Tool {
    name: string; description: string;
    execute(actionTerm: Structure, context: ToolContext, trigger: Thought): Promise<Term | null>;
}
interface SystemControl {
    startProcessing: () => void; pauseProcessing: () => void; stepProcessing: () => Promise<number>;
    shutdown: () => Promise<void>; getStatus: () => { isRunning: boolean };
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
        lastArgs = args; lastThis = this;
        if (timeout) clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
    const later = () => {
        timeout = null;
        if (lastArgs) result = func.apply(lastThis, lastArgs);
        lastArgs = null; lastThis = null;
    };
    debounced.cancel = () => { if (timeout) clearTimeout(timeout); timeout = null; lastArgs = null; lastThis = null; };
    debounced.flush = () => { if (timeout) clearTimeout(timeout); later(); return result; };
    return debounced as T & { cancel: () => void; flush: () => ReturnType<T> | undefined; };
}

const safeJsonParse = <T>(json: string | null | undefined, defaultValue: T): T => {
    if (!json) return defaultValue; try { return JSON.parse(json); } catch { return defaultValue; }
};

// --- Core Logic Classes ---

class Belief implements BeliefData {
    constructor(public pos: number = DEFAULT_BELIEF_POS, public neg: number = DEFAULT_BELIEF_NEG) {}
    score(): number { return (this.pos + 1) / (this.pos + this.neg + 2); }
    update(success: boolean): void { success ? this.pos++ : this.neg++; }
    toJSON(): BeliefData { return {pos: this.pos, neg: this.neg}; }
    static fromJSON(data: BeliefData | Belief): Belief {
        return data instanceof Belief ? data : new Belief(data?.pos ?? DEFAULT_BELIEF_POS, data?.neg ?? DEFAULT_BELIEF_NEG);
    }
    static DEFAULT = new Belief();
}

namespace TermLogic {
    export const Atom = (name: string): Atom => ({kind: 'Atom', name});
    export const Variable = (name: string): Variable => ({kind: 'Variable', name});
    export const Structure = (name: string, args: Term[]): Structure => ({kind: 'Structure', name, args});
    export const List = (elements: Term[]): ListTerm => ({kind: 'ListTerm', elements});

    export function format(term: Term | null | undefined, useColor: boolean = true): string {
        if (!term) return useColor ? chalk.grey('null') : 'null';
        const c = (color: chalk.ChalkFunction, text: string) => useColor ? color(text) : text;
        switch (term.kind) {
            case 'Atom': return c(chalk.green, term.name);
            case 'Variable': return c(chalk.cyan, `?${term.name}`);
            case 'Structure': return `${c(chalk.yellow, term.name)}(${term.args.map(t => format(t, useColor)).join(', ')})`;
            case 'ListTerm': return `[${term.elements.map(t => format(t, useColor)).join(', ')}]`;
            default: const _: never = term; return c(chalk.red, 'invalid_term');
        }
    }

    export function toString(term: Term): string {
        switch (term.kind) {
            case 'Atom': return term.name;
            case 'Variable': return `?${term.name}`;
            case 'Structure': return `${term.name}(${term.args.map(toString).join(',')})`;
            case 'ListTerm': return `[${term.elements.map(toString).join(',')}]`;
            default: const _: never = term; return '';
        }
    }

    // Basic parser, assumes well-formed input without complex nesting/quoting
    export function fromString(input: string): Term {
        input = input.trim();
        if (input.startsWith('?') && input.length > 1 && !input.includes('(') && !input.includes('[') && !input.includes(',')) return Variable(input.substring(1));
        const structMatch = input.match(/^([a-zA-Z0-9_]+)\((.*)\)$/);
        if (structMatch) {
            const name = structMatch[1];
            const argsStr = structMatch[2];
            // Basic parsing: split by comma, respecting simple parentheses/brackets
            const args: Term[] = [];
            let currentArg = ''; let parenLevel = 0; let bracketLevel = 0;
            for (let i = 0; i < argsStr.length; i++) {
                const char = argsStr[i];
                if (char === '(') parenLevel++; else if (char === ')') parenLevel--;
                else if (char === '[') bracketLevel++; else if (char === ']') bracketLevel--;
                if (char === ',' && parenLevel === 0 && bracketLevel === 0) {
                    if (currentArg.trim()) args.push(fromString(currentArg.trim())); currentArg = '';
                } else currentArg += char;
            }
            if (currentArg.trim()) args.push(fromString(currentArg.trim()));
            return Structure(name, args);
        }
        const listMatch = input.match(/^\[(.*)\]$/);
        if (listMatch) {
            const elementsStr = listMatch[1];
            // Similar basic parsing for list elements
            const elements: Term[] = [];
            let currentEl = ''; let parenLevel = 0; let bracketLevel = 0;
            for (let i = 0; i < elementsStr.length; i++) {
                const char = elementsStr[i];
                if (char === '(') parenLevel++; else if (char === ')') parenLevel--;
                else if (char === '[') bracketLevel++; else if (char === ']') bracketLevel--;
                if (char === ',' && parenLevel === 0 && bracketLevel === 0) {
                    if (currentEl.trim()) elements.push(fromString(currentEl.trim())); currentEl = '';
                } else currentEl += char;
            }
            if (currentEl.trim()) elements.push(fromString(currentEl.trim()));
            return List(elements);
        }
        // Ensure atom names don't contain special characters interpreted by other types
        if (/^[a-zA-Z0-9_:-]+$/.test(input) || input === '') return Atom(input);
        log('warn', `Could not parse term, interpreting as Atom: "${input}"`);
        return Atom(input); // Fallback to Atom if parsing fails
    }

    export function unify(term1: Term, term2: Term, bindings: Map<string, Term> = new Map()): Map<string, Term> | null {
        const resolve = (term: Term, currentBindings: Map<string, Term>): Term => {
            while (term.kind === 'Variable' && currentBindings.has(term.name)) {
                const bound = currentBindings.get(term.name)!; if (bound === term) return term; term = bound;
            } return term;
        };
        const t1 = resolve(term1, bindings); const t2 = resolve(term2, bindings);
        const occursCheck = (varName: string, termToCheck: Term): boolean => {
            const resolved = resolve(termToCheck, bindings);
            if (resolved.kind === 'Variable') return varName === resolved.name;
            if (resolved.kind === 'Structure') return resolved.args.some(arg => occursCheck(varName, arg));
            if (resolved.kind === 'ListTerm') return resolved.elements.some(el => occursCheck(varName, el));
            return false;
        };
        if (t1.kind === 'Variable') {
            if (t1.name === (t2 as Variable).name && t1.kind === t2.kind) return bindings;
            if (occursCheck(t1.name, t2)) return null;
            const newBindings = new Map(bindings); newBindings.set(t1.name, t2); return newBindings;
        }
        if (t2.kind === 'Variable') {
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
                } return currentBindings;
            }
            case 'ListTerm': {
                const l2 = t2 as ListTerm;
                if (t1.elements.length !== l2.elements.length) return null;
                let currentBindings = bindings;
                for (let i = 0; i < t1.elements.length; i++) {
                    const result = unify(t1.elements[i], l2.elements[i], currentBindings);
                    if (!result) return null; currentBindings = result;
                } return currentBindings;
            } default: const _: never = t1; return null;
        }
    }

    export function substitute(term: Term, bindings: Map<string, Term>): Term {
        const resolve = (t: Term): Term => {
            if (t.kind === 'Variable' && bindings.has(t.name)) return resolve(bindings.get(t.name)!);
            return t;
        };
        const resolved = resolve(term);
        if (resolved.kind === 'Structure') return {...resolved, args: resolved.args.map(arg => substitute(arg, bindings))};
        if (resolved.kind === 'ListTerm') return {...resolved, elements: resolved.elements.map(el => substitute(el, bindings))};
        return resolved;
    }

    export function jsonToTerm(data: any): Term | null {
        if (data === null || typeof data !== 'object') return null;
        if (Array.isArray(data)) {
            const elements = data.map(jsonToTerm).filter((t): t is Term => t !== null);
            return elements.length === data.length ? List(elements) : null;
        }
        const {kind, name, args, elements} = data;
        switch (kind) {
            case 'Atom': return typeof name === 'string' ? Atom(name) : null;
            case 'Variable': return typeof name === 'string' ? Variable(name) : null;
            case 'Structure':
                if (typeof name === 'string' && Array.isArray(args)) {
                    const convertedArgs = args.map(jsonToTerm).filter((t): t is Term => t !== null);
                    return convertedArgs.length === args.length ? Structure(name, convertedArgs) : null;
                } return null;
            case 'ListTerm':
                if (Array.isArray(elements)) {
                    const convertedElements = elements.map(jsonToTerm).filter((t): t is Term => t !== null);
                    return convertedElements.length === elements.length ? List(convertedElements) : null;
                } return null;
            default: // Attempt to convert arbitrary object to Structure
                const objArgs = Object.entries(data)
                    .map(([k, v]) => jsonToTerm(v) ? Structure(k, [jsonToTerm(v)!]) : Atom(`${k}:${JSON.stringify(v)}`));
                return Structure("json_object", objArgs);
        }
    }
}

abstract class BaseStore<T extends { id: string, metadata?: { created?: string, modified?: string } }> {
    protected items = new Map<string, T>();
    protected listeners: Set<() => void> = new Set();
    protected changedItems: Set<string> = new Set();
    protected deletedItems: Set<string> = new Set();

    add(item: T): void { this.update(item, true); } // Add is just an update that ensures creation meta
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
            metadata: {
                ...(existing?.metadata ?? {}), // Keep existing meta
                ...(item.metadata ?? {}),      // Apply incoming meta
                created: existing?.metadata?.created ?? now, // Preserve original creation time
                modified: now                      // Always update modified time
            }
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
        this.changedItems.clear(); this.deletedItems.clear();
        return {changed, deleted};
    }

    protected notifyChange(): void { this.listeners.forEach(listener => listener()); }
    abstract toJSON(): Record<string, any>;
    abstract loadJSON(data: Record<string, any>): void;
    protected baseLoadJSON(data: Record<string, any>, itemFactory: (id: string, itemData: any) => T): void {
        this.items.clear();
        Object.entries(data).forEach(([id, itemData]) => {
            try { this.items.set(id, itemFactory(id, itemData)); }
            catch (e: any) { log('error', `Failed to load item ${id}: ${e.message}`); }
        });
        this.changedItems.clear(); this.deletedItems.clear();
    }
}

class ThoughtStore extends BaseStore<Thought> {
    getPending(): Thought[] { return this.getAll().filter(t => t.status === Status.PENDING); }
    findThought(idPrefix: string): Thought | undefined { return this.findItemByPrefix(idPrefix); }
    findPendingPrompt(promptId: string): Thought | undefined {
        return this.getAll().find(t => t.type === Type.USER_PROMPT && t.status === Status.PENDING && t.metadata.promptId === promptId);
    }
    findWaitingThought(promptId: string): Thought | undefined {
        return this.getAll().find(t => t.status === Status.WAITING && t.metadata.waitingFor === promptId);
    }
    toJSON(): Record<string, any> {
        const obj: Record<string, any> = {};
        this.items.forEach((thought, id) => obj[id] = {...thought, belief: thought.belief.toJSON()});
        return obj;
    }
    loadJSON(data: Record<string, any>): void {
        this.baseLoadJSON(data, (id, thoughtData) => {
            const {belief, ...rest} = thoughtData;
            // Ensure status is valid, default to PENDING if loaded state is invalid/transient
            const status = Object.values(Status).includes(rest.status) ? rest.status : Status.PENDING;
            // Reset ACTIVE to PENDING on load to ensure they get picked up
            const finalStatus = status === Status.ACTIVE ? Status.PENDING : status;
            return {...rest, belief: Belief.fromJSON(belief), id, status: finalStatus};
        });
        log('info', `Loaded ${this.count()} thoughts. Reset ACTIVE thoughts to PENDING.`);
    }
}

class RuleStore extends BaseStore<Rule> {
    findRule(idPrefix: string): Rule | undefined { return this.findItemByPrefix(idPrefix); }
    toJSON(): Record<string, any> {
        const obj: Record<string, any> = {};
        this.items.forEach((rule, id) => obj[id] = {...rule, belief: rule.belief.toJSON()});
        return obj;
    }
    loadJSON(data: Record<string, any>): void {
        this.baseLoadJSON(data, (id, ruleData) => {
            const {belief, ...rest} = ruleData;
            return {...rest, belief: Belief.fromJSON(belief), id};
        });
        log('info', `Loaded ${this.count()} rules.`);
    }
}

class MemoryStore {
    private vectorStore: FaissStore | null = null;
    public isReady = false;

    constructor(private embeddings: Embeddings, private storePath: string) {}

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
                    const dummyDoc = new Document({ pageContent: "Initial sentinel document", metadata: { sourceId: "init", type: "SYSTEM", created: new Date().toISOString(), id: "init_doc" } });
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
        const docMetadata = {...entry.metadata, id: entry.id, _docId: generateId()};
        const doc = new Document({pageContent: entry.content, metadata: docMetadata});
        try {
            await this.vectorStore.addDocuments([doc]);
            await this.save(); // Consider debouncing save if adds are frequent
            log('debug', `Added memory entry from source ${entry.metadata.sourceId}`);
        } catch (error: any) { log('error', 'Failed to add document to vector store:', error.message); }
    }

    async search(query: string, k: number = 5): Promise<MemoryEntry[]> {
        if (!this.vectorStore || !this.isReady) { log('warn', 'MemoryStore not ready, cannot search.'); return []; }
        try {
            const results = await this.vectorStore.similaritySearchWithScore(query, k);
            log('debug', `Memory search for "${query.substring(0, 30)}..." returned ${results.length} results.`);
            return results.filter(([doc]) => doc.metadata.sourceId !== 'init').map(([doc, score]): MemoryEntry => ({
                id: doc.metadata.id ?? generateId(), content: doc.pageContent,
                metadata: { created: doc.metadata.created ?? new Date().toISOString(), type: doc.metadata.type ?? 'UNKNOWN', sourceId: doc.metadata.sourceId ?? 'UNKNOWN' },
                score: score, embedding: [] // Embedding not typically returned by search
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

class LLMService {
    llm: BaseChatModel;
    embeddings: Embeddings;

    constructor() {
        this.llm = new ChatOllama({baseUrl: OLLAMA_BASE_URL, model: OLLAMA_MODEL, temperature: 0.7});
        this.embeddings = new OllamaEmbeddings({model: OLLAMA_EMBEDDING_MODEL, baseUrl: OLLAMA_BASE_URL});
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

// --- Prompt Management ---
type PromptKey = 'GENERATE_GOAL' | 'GENERATE_STRATEGY' | 'GENERATE_OUTCOME' | 'SUGGEST_GOAL' | 'FALLBACK_ASK_USER';

class PromptRegistry {
    private prompts: Record<PromptKey, string> = {
        GENERATE_GOAL: 'Input: "{input}". Define a specific, actionable GOAL based on this input. Output ONLY the goal text.',
        GENERATE_STRATEGY: 'Goal: "{goal}". Outline 1-3 concrete STRATEGY steps to achieve this goal. Output each step on a new line, starting with "- ".',
        GENERATE_OUTCOME: 'Strategy step "{strategy}" was attempted. Describe a likely concise OUTCOME. Output ONLY the outcome text.',
        SUGGEST_GOAL: 'Based on the current context "{context}"{memoryContext}\n\nSuggest ONE concise, actionable next goal or task. Output only the suggested goal text.',
        FALLBACK_ASK_USER: 'No rule found for thought {thoughtId} ({thoughtType}: "{thoughtContent}"). How should I proceed with this?',
    };

    format(key: PromptKey, context: Record<string, string>): string {
        let template = this.prompts[key] ?? `Error: Prompt template "${key}" not found. Context: ${JSON.stringify(context)}`;
        Object.entries(context).forEach(([k, v]) => { template = template.replace(`{${k}}`, v); });
        return template;
    }
}

// --- Tools ---

abstract class BaseTool implements Tool {
    abstract name: string; abstract description: string;
    abstract execute(actionTerm: Structure, context: ToolContext, trigger: Thought): Promise<Term | null>;
    protected createResultAtom(success: boolean, code: string, message?: string): Atom {
        const prefix = success ? 'ok' : 'error';
        const fullMessage = `${prefix}:${this.name}_${code}${message ? `:${message.substring(0, 50)}` : ''}`;
        return TermLogic.Atom(fullMessage);
    }
}

class LLMTool extends BaseTool {
    name = "LLMTool"; description = "Interacts with the LLM: generate(prompt_key_atom, context_term), embed(text_term).";
    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (!operation) return this.createResultAtom(false, "invalid_params", "Missing operation atom");
        switch (operation) {
            case 'generate': {
                const promptKeyAtom = action.args[1]; const contextTerm = action.args[2];
                if (promptKeyAtom?.kind !== 'Atom') return this.createResultAtom(false, "invalid_params", "Missing prompt key atom");
                if (contextTerm && contextTerm.kind !== 'Structure') return this.createResultAtom(false, "invalid_params", "Context must be a Structure");
                const promptKey = promptKeyAtom.name as PromptKey;
                const promptContext: Record<string, string> = {};
                if (contextTerm?.kind === 'Structure') contextTerm.args.forEach(arg => {
                    if (arg.kind === 'Atom' && arg.name.includes(':')) { const [k, v] = arg.name.split(':', 2); promptContext[k] = v; }
                });
                const prompt = context.prompts.format(promptKey, promptContext);
                const response = await context.llm.generate(prompt);
                if (response.startsWith('Error:')) return this.createResultAtom(false, "generation_failed", response);
                // Attempt to parse as JSON -> Term first, fallback to Atom
                try { const termFromJson = TermLogic.jsonToTerm(JSON.parse(response)); if (termFromJson) return termFromJson; } catch { /* Ignore JSON parse error */ }
                return TermLogic.Atom(response);
            }
            case 'embed': {
                const inputTerm = action.args[1];
                if (!inputTerm) return this.createResultAtom(false, "invalid_params", "Missing text term for embed");
                const inputText = TermLogic.toString(inputTerm);
                const embedding = await context.llm.embed(inputText);
                return this.createResultAtom(embedding.length > 0, embedding.length > 0 ? "embedded" : "embedding_failed");
            }
            default: return this.createResultAtom(false, "unsupported_operation", operation);
        }
    }
}

class MemoryTool extends BaseTool {
    name = "MemoryTool"; description = "Manages vector memory: add(content_term), search(query_term, k?).";
    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (!operation) return this.createResultAtom(false, "missing_operation");
        switch (operation) {
            case 'add': {
                const contentTerm = action.args[1];
                if (!contentTerm) return this.createResultAtom(false, "missing_add_content");
                await context.memory.add({
                    id: generateId(), content: TermLogic.toString(contentTerm),
                    metadata: {created: new Date().toISOString(), type: trigger.type, sourceId: trigger.id}
                });
                trigger.metadata.embedded = new Date().toISOString();
                context.thoughts.update(trigger);
                return this.createResultAtom(true, "memory_added", shortId(trigger.id));
            }
            case 'search': {
                const queryTerm = action.args[1]; const kTerm = action.args[2];
                if (!queryTerm) return this.createResultAtom(false, "missing_search_query");
                const queryStr = TermLogic.toString(queryTerm);
                const k = (kTerm?.kind === 'Atom' && /^\d+$/.test(kTerm.name)) ? parseInt(kTerm.name, 10) : 3;
                const results = await context.memory.search(queryStr, k);
                return TermLogic.List(results.map(r => TermLogic.Atom(r.content)));
            }
            default: return this.createResultAtom(false, "unsupported_operation", operation);
        }
    }
}

class UserInteractionTool extends BaseTool {
    name = "UserInteractionTool"; description = "Requests input from the user: prompt(prompt_text_term | prompt_key_atom, context_term?).";
    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (operation !== 'prompt') return this.createResultAtom(false, "invalid_params", "Expected prompt operation");
        const promptSource = action.args[1]; const promptContextTerm = action.args[2];
        if (!promptSource) return this.createResultAtom(false, "invalid_params", "Missing prompt source");

        let promptText: string;
        if (promptSource.kind === 'Atom') {
            const promptKey = promptSource.name as PromptKey;
            const promptContext: Record<string, string> = {};
            if (promptContextTerm?.kind === 'Structure') promptContextTerm.args.forEach(arg => {
                if (arg.kind === 'Atom' && arg.name.includes(':')) { const [k, v] = arg.name.split(':', 2); promptContext[k] = v; }
            });
            promptText = context.prompts.format(promptKey, promptContext);
        } else { promptText = TermLogic.toString(promptSource); }

        const promptId = generateId();
        context.engine.addThought({
            id: promptId, type: Type.USER_PROMPT, content: TermLogic.Atom(promptText), belief: Belief.DEFAULT, status: Status.PENDING,
            metadata: { rootId: trigger.metadata.rootId ?? trigger.id, parentId: trigger.id, created: new Date().toISOString(), promptId: promptId, provenance: this.name }
        });
        trigger.status = Status.WAITING; trigger.metadata.waitingFor = promptId;
        context.thoughts.update(trigger);
        log('info', `User prompt requested (${shortId(promptId)}) for thought ${shortId(trigger.id)}.`);
        return this.createResultAtom(true, "prompt_requested", promptId);
    }
}

class GoalProposalTool extends BaseTool {
    name = "GoalProposalTool"; description = "Suggests new goals based on context: suggest(context_term?).";
    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (operation !== 'suggest') return this.createResultAtom(false, "invalid_params");
        const contextTerm = action.args[1] ?? trigger.content;
        const contextStr = TermLogic.toString(contextTerm);
        const memoryResults = await context.memory.search(`Relevant past goals or outcomes related to: ${contextStr}`, 3);
        const memoryContext = memoryResults.length > 0 ? `\nRelated past activities:\n - ${memoryResults.map(r => r.content).join("\n - ")}` : "";
        const prompt = context.prompts.format('SUGGEST_GOAL', {context: contextStr, memoryContext: memoryContext});
        const suggestionText = await context.llm.generate(prompt);
        if (!suggestionText || suggestionText.startsWith('Error:')) return this.createResultAtom(false, "llm_failed", suggestionText);
        const suggestionThought: Thought = {
            id: generateId(), type: Type.GOAL, content: TermLogic.Atom(suggestionText), belief: new Belief(1, 0), status: Status.PENDING,
            metadata: { rootId: trigger.metadata.rootId ?? trigger.id, parentId: trigger.id, created: new Date().toISOString(), tags: ['suggested_goal'], provenance: this.name }
        };
        context.engine.addThought(suggestionThought);
        log('info', `Suggested goal ${shortId(suggestionThought.id)} based on ${shortId(trigger.id)}.`);
        return this.createResultAtom(true, "suggestion_created", shortId(suggestionThought.id));
    }
}

class CoreTool extends BaseTool {
    name = "CoreTool"; description = "Manages internal state: set_status(target_id, status), add_thought(type, content, root?, parent?), delete_thought(target_id).";
    private resolveTargetId(term: Term | undefined, fallbackId: string, context: ToolContext): string | null {
        if (term?.kind !== 'Atom') return null;
        if (term.name === 'self') return fallbackId;
        // Allow resolving prefixes
        const target = context.thoughts.findThought(term.name) ?? context.rules.findRule(term.name);
        return target?.id ?? (this.isValidId(term.name) ? term.name : null); // Return full ID if prefix resolved or looks like full ID
    }
    private isValidId(id: string): boolean { return /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(id); }
    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (!operation) return this.createResultAtom(false, "missing_operation");
        switch (operation) {
            case 'set_status': {
                const targetId = this.resolveTargetId(action.args[1], trigger.id, context);
                const newStatusTerm = action.args[2];
                if (!targetId || newStatusTerm?.kind !== 'Atom') return this.createResultAtom(false, "invalid_set_status_params");
                const newStatus = newStatusTerm.name.toUpperCase() as Status;
                if (!Object.values(Status).includes(newStatus)) return this.createResultAtom(false, "invalid_status_value", newStatusTerm.name);
                const targetThought = context.thoughts.get(targetId);
                if (!targetThought) return this.createResultAtom(false, "target_not_found", targetId);
                targetThought.status = newStatus;
                context.thoughts.update(targetThought);
                log('debug', `Set status of ${shortId(targetThought.id)} to ${newStatus}`);
                return this.createResultAtom(true, "status_set", `${shortId(targetThought.id)}_to_${newStatus}`);
            }
            case 'add_thought': {
                const typeTerm = action.args[1]; const contentTerm = action.args[2];
                const rootIdTerm = action.args[3]; const parentIdTerm = action.args[4];
                if (typeTerm?.kind !== 'Atom' || !contentTerm) return this.createResultAtom(false, "invalid_add_thought_params");
                const type = typeTerm.name.toUpperCase() as Type;
                if (!Object.values(Type).includes(type)) return this.createResultAtom(false, "invalid_thought_type", typeTerm.name);
                const parentId = this.resolveTargetId(parentIdTerm, trigger.id, context) ?? trigger.id;
                const parentThought = context.thoughts.get(parentId);
                const rootId = this.resolveTargetId(rootIdTerm, parentThought?.metadata.rootId ?? parentId, context) ?? parentThought?.metadata.rootId ?? parentId;
                const newThought: Thought = {
                    id: generateId(), type, content: contentTerm, belief: Belief.DEFAULT, status: Status.PENDING,
                    metadata: { rootId, parentId, created: new Date().toISOString(), provenance: `${this.name} (rule ${shortId(trigger.metadata.ruleId)} on ${shortId(trigger.id)})` }
                };
                context.engine.addThought(newThought);
                log('debug', `Added thought ${shortId(newThought.id)} of type ${type}`);
                return this.createResultAtom(true, "thought_added", shortId(newThought.id));
            }
            case 'delete_thought': {
                const targetId = this.resolveTargetId(action.args[1], trigger.id, context);
                if (!targetId) return this.createResultAtom(false, "invalid_delete_thought_params");
                const deleted = context.thoughts.delete(targetId);
                log('debug', `Attempted delete thought ${shortId(targetId)}: ${deleted}`);
                return this.createResultAtom(deleted, deleted ? "thought_deleted" : "delete_failed", shortId(targetId));
            }
            default: return this.createResultAtom(false, "unsupported_operation", operation);
        }
    }
}

class ToolRegistry {
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
            .map(rule => ({rule, bindings: TermLogic.unify(rule.pattern, thought.content)}))
            .filter((m): m is MatchResult => m.bindings !== null);
        if (applicableMatches.length === 0) return null;
        if (applicableMatches.length === 1) return applicableMatches[0];
        applicableMatches.sort((a, b) => (b.rule.metadata.priority ?? 0) - (a.rule.metadata.priority ?? 0) || b.rule.belief.score() - a.rule.belief.score());
        log('debug', `Multiple rule matches for ${shortId(thought.id)} (${thought.type}). Best: ${shortId(applicableMatches[0].rule.id)}`);
        return applicableMatches[0]; // Return deterministic best match
    }
};

const ActionExecutor = {
    async execute(actionTerm: Term, context: ToolContext, trigger: Thought, rule?: Rule): Promise<ActionResult> {
        if (actionTerm.kind !== 'Structure') return {success: false, error: `Invalid action term kind: ${actionTerm.kind}`, finalStatus: Status.FAILED};
        const tool = context.tools.get(actionTerm.name);
        if (!tool) return {success: false, error: `Tool not found: ${actionTerm.name}`, finalStatus: Status.FAILED};

        log('debug', `Executing action ${TermLogic.format(actionTerm, false)} via tool ${tool.name} for thought ${shortId(trigger.id)}`);
        try {
            const resultTerm = await tool.execute(actionTerm, context, trigger);
            const currentThoughtState = context.thoughts.get(trigger.id); // Re-fetch state
            if (!currentThoughtState) { log('warn', `Thought ${shortId(trigger.id)} deleted during action by ${tool.name}.`); return {success: true}; }

            const isWaiting = currentThoughtState.status === Status.WAITING;
            const isFailed = currentThoughtState.status === Status.FAILED;
            const returnedError = resultTerm?.kind === 'Atom' && resultTerm.name.startsWith('error:');

            if (returnedError) return {success: false, error: `Tool failed: ${resultTerm.name}`, finalStatus: Status.FAILED};
            if (isWaiting) return {success: true, finalStatus: Status.WAITING};
            if (isFailed) return {success: false, error: `Tool set status to FAILED`, finalStatus: Status.FAILED};
            return {success: true, finalStatus: Status.DONE}; // Default success -> DONE
        } catch (error: any) {
            log('error', `Tool exception ${tool.name} on ${shortId(trigger.id)}: ${error.message}`, error.stack);
            return {success: false, error: `Tool exception: ${error.message}`, finalStatus: Status.FAILED};
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
        const contentStr = TermLogic.toString(thought.content).substring(0, 100);

        switch (thought.type) {
            case Type.INPUT: llmPromptKey = 'GENERATE_GOAL'; llmContext = {input: contentStr}; targetType = Type.GOAL; break;
            case Type.GOAL: llmPromptKey = 'GENERATE_STRATEGY'; llmContext = {goal: contentStr}; targetType = Type.STRATEGY; break;
            case Type.STRATEGY: llmPromptKey = 'GENERATE_OUTCOME'; llmContext = {strategy: contentStr}; targetType = Type.OUTCOME; break;
            case Type.OUTCOME: action = TermLogic.Structure("MemoryTool", [TermLogic.Atom("add"), thought.content]); break;
            default:
                const askUserContext = { thoughtId: shortId(thought.id), thoughtType: thought.type, thoughtContent: contentStr };
                action = TermLogic.Structure("UserInteractionTool", [TermLogic.Atom("prompt"), TermLogic.Atom("FALLBACK_ASK_USER"), TermLogic.Structure("context", Object.entries(askUserContext).map(([k, v]) => TermLogic.Atom(`${k}:${v}`)))]);
                break;
        }

        if (llmPromptKey && targetType) {
            const prompt = context.prompts.format(llmPromptKey, llmContext);
            const resultText = await context.llm.generate(prompt);
            if (resultText && !resultText.startsWith('Error:')) {
                resultText.split('\n').map(s => s.trim().replace(/^- /, '')).filter(s => s.length > 0)
                    .forEach(resText => {
                        context.engine.addThought({
                            id: generateId(), type: targetType!, content: TermLogic.Atom(resText), belief: Belief.DEFAULT, status: Status.PENDING,
                            metadata: { rootId: thought.metadata.rootId ?? thought.id, parentId: thought.id, created: new Date().toISOString(), provenance: 'llm_fallback' }
                        });
                    });
                return {success: true, finalStatus: Status.DONE}; // Original thought done, fallback generated next steps
            } else { return {success: false, error: `LLM fallback failed: ${resultText}`, finalStatus: Status.FAILED}; }
        } else if (action) { return ActionExecutor.execute(action, context, thought); }
        else { return {success: false, error: "No rule match and no fallback action defined.", finalStatus: Status.FAILED}; }
    }
};

// --- Engine ---

class Engine {
    private activeIds = new Set<string>();
    private context: ToolContext;

    constructor(
        private thoughts: ThoughtStore, private rules: RuleStore, private memory: MemoryStore,
        private llm: LLMService, private tools: ToolRegistry, private prompts: PromptRegistry,
        private batchSize: number = 5, private maxConcurrent: number = 3
    ) {
        this.context = { thoughts: this.thoughts, rules: this.rules, memory: this.memory, llm: this.llm, tools: this.tools, prompts: this.prompts, engine: this };
    }

    addThought(thought: Thought): void { this.thoughts.add(thought); }

    private sampleThought(): Thought | null {
        const candidates = this.thoughts.getPending().filter(t => !this.activeIds.has(t.id));
        if (candidates.length === 0) return null;
        const weights = candidates.map(t => Math.max(0.01, t.metadata.priority ?? t.belief.score()));
        const totalWeight = weights.reduce((sum, w) => sum + w, 0);
        if (totalWeight <= 0) return candidates[Math.floor(Math.random() * candidates.length)];
        let random = Math.random() * totalWeight;
        for (let i = 0; i < candidates.length; i++) { random -= weights[i]; if (random <= 0) return candidates[i]; }
        return candidates[candidates.length - 1];
    }

    private updateThoughtStatus(thought: Thought, result: ActionResult | FallbackResult, rule?: Rule): void {
        const currentThought = this.thoughts.get(thought.id);
        if (!currentThought) return; // Thought was deleted

        currentThought.metadata.ruleId = rule?.id;
        if (result.success) {
            currentThought.status = result.finalStatus ?? Status.DONE;
            delete currentThought.metadata.error; delete currentThought.metadata.retries;
            if (currentThought.status !== Status.WAITING) delete currentThought.metadata.waitingFor;
            // Update belief on success? Maybe only if DONE? Let's update on any success for now.
            // currentThought.belief.update(true); // Belief update handled by rule/fallback logic
        } else {
            const retries = (currentThought.metadata.retries ?? 0) + 1;
            currentThought.status = retries >= MAX_RETRIES ? Status.FAILED : Status.PENDING; // Retry or fail
            currentThought.metadata.error = (result.error ?? "Unknown processing error").substring(0, 250);
            currentThought.metadata.retries = retries;
            log('warn', `Thought ${shortId(currentThought.id)} failed (Attempt ${retries}): ${currentThought.metadata.error}`);
        }
        this.thoughts.update(currentThought);
    }

    private async _applyRule(match: MatchResult, thought: Thought): Promise<ActionResult> {
        const boundAction = TermLogic.substitute(match.rule.action, match.bindings);
        const result = await ActionExecutor.execute(boundAction, this.context, thought, match.rule);
        // Update rule belief based on action execution success
        match.rule.belief.update(result.success);
        this.rules.update(match.rule);
        return result;
    }

    private async _applyFallback(thought: Thought): Promise<FallbackResult> {
        const result = await FallbackHandler.handle(thought, this.context);
        // No specific rule belief to update for fallback
        // Thought belief might be updated based on fallback success/failure? Let's handle in updateThoughtStatus
        return result;
    }

    private async _processThought(thought: Thought): Promise<boolean> {
        try {
            log('info', `Processing ${thought.type} thought ${shortId(thought.id)}: ${TermLogic.format(thought.content, false).substring(0, 80)}`);
            thought.status = Status.ACTIVE;
            thought.metadata.agentId = 'worker';
            this.thoughts.update(thought);

            const match = RuleMatcher.match(thought, this.rules.getAll());
            let result: ActionResult | FallbackResult;
            let appliedRule: Rule | undefined = undefined;

            if (match) {
                appliedRule = match.rule;
                result = await this._applyRule(match, thought);
            } else {
                result = await this._applyFallback(thought);
            }

            this.updateThoughtStatus(thought, result, appliedRule);
            const finalStatus = this.thoughts.get(thought.id)?.status;
            return finalStatus !== Status.PENDING && finalStatus !== Status.ACTIVE; // True if processing concluded (Done, Failed, Waiting)

        } catch (error: any) {
            log('error', `Critical error processing ${shortId(thought.id)}: ${error.message}`, error.stack);
            this.updateThoughtStatus(thought, { success: false, error: `Unhandled exception: ${error.message}`, finalStatus: Status.FAILED });
            return true; // Indicate processing ended critically
        } finally {
            this.activeIds.delete(thought.id);
            const finalThoughtState = this.thoughts.get(thought.id);
            if (finalThoughtState?.status === Status.ACTIVE) { // Should not happen if updateThoughtStatus works
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
            if (this.activeIds.has(thought.id)) continue; // Should not happen with sampleThought filter, but safety check
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
            promptThought.status = Status.DONE; promptThought.metadata.error = "No waiting thought found upon response.";
            this.thoughts.update(promptThought); return false;
        }

        this.addThought({
            id: generateId(), type: Type.INPUT, content: TermLogic.Atom(responseText), belief: new Belief(1, 0), status: Status.PENDING,
            metadata: { rootId: waitingThought.metadata.rootId ?? waitingThought.id, parentId: waitingThought.id, created: new Date().toISOString(), responseTo: promptId, tags: ['user_response'], provenance: 'user_input' }
        });
        promptThought.status = Status.DONE; this.thoughts.update(promptThought);
        waitingThought.status = Status.PENDING; delete waitingThought.metadata.waitingFor;
        waitingThought.belief.update(true); // Assume responding successfully moves the waiting thought forward
        this.thoughts.update(waitingThought);
        log('info', `Response for ${shortId(promptId)} received. Thought ${shortId(waitingThought.id)} reactivated.`);
        return true;
    }
}

// --- Persistence ---
const saveState = async (thoughts: ThoughtStore, rules: RuleStore, memory: MemoryStore): Promise<void> => {
    try {
        await fs.mkdir(DATA_DIR, {recursive: true});
        const state: AppState = {thoughts: thoughts.toJSON(), rules: rules.toJSON()};
        await fs.writeFile(STATE_FILE, JSON.stringify(state, null, 2));
        await memory.save();
        log('debug', 'State saved successfully.');
    } catch (error: any) { log('error', 'Error saving state:', error.message); }
};

const debouncedSaveState = debounce(saveState, SAVE_DEBOUNCE);

const loadState = async (thoughts: ThoughtStore, rules: RuleStore, memory: MemoryStore): Promise<void> => {
    try {
        await fs.mkdir(DATA_DIR, {recursive: true});
        await memory.initialize();
        if (!memory.isReady) log('error', "Memory store failed to initialize. State loading might be incomplete.");
        if (fsSync.existsSync(STATE_FILE)) {
            const data = await fs.readFile(STATE_FILE, 'utf-8');
            const state: AppState = safeJsonParse(data, {thoughts: {}, rules: {}});
            thoughts.loadJSON(state.thoughts); // loadJSON now handles resetting ACTIVE -> PENDING
            rules.loadJSON(state.rules);
        } else { log('warn', `State file ${STATE_FILE} not found. Starting fresh.`); }
    } catch (error: any) {
        log('error', 'Error loading state:', error.message);
        if (!memory.isReady) await memory.initialize(); // Attempt memory init again if load failed
    }
};

// --- WebSocket API Server ---
class WsApiServer {
    private wss: WebSocketServer | null = null;
    private clients = new Map<WebSocket, { ip: string }>();
    private commandHandlers = new Map<string, (payload: any, ws: WebSocket) => Promise<any | void>>();

    constructor(
        private port: number, private thoughts: ThoughtStore, private rules: RuleStore,
        private memory: MemoryStore, private engine: Engine, private tools: ToolRegistry,
        private systemControl: SystemControl
    ) {
        this.registerCommandHandlers();
        this.thoughts.addChangeListener(this.broadcastThoughtChanges.bind(this));
        this.rules.addChangeListener(this.broadcastRuleChanges.bind(this));
    }

    start(): void {
        this.wss = new WebSocketServer({port: this.port});
        this.wss.on('listening', () => log('info', `🚀 FlowMind WebSocket API listening on ws://localhost:${this.port}`));
        this.wss.on('connection', (ws, req) => this.handleConnection(ws, req));
        this.wss.on('error', (error) => log('error', 'WebSocket Server Error:', error.message));
    }

    stop(): void {
        log('warn', 'Stopping WebSocket API server...');
        this.wss?.clients.forEach(ws => ws.close());
        this.wss?.close(); this.clients.clear();
    }

    private handleConnection(ws: WebSocket, req: any): void {
        const ip = req.socket.remoteAddress || 'unknown';
        log('info', `Client connected: ${ip}`); this.clients.set(ws, {ip});
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
        let request: { command: string; payload?: any; requestId?: string };
        try {
            request = JSON.parse(message.toString());
            if (typeof request.command !== 'string') throw new Error("Invalid command format");
        } catch (e: any) { this.send(ws, { type: 'error', payload: `Invalid JSON: ${e.message}`, requestId: request?.requestId }); return; }

        const handler = this.commandHandlers.get(request.command);
        if (!handler) { this.send(ws, { type: 'error', payload: `Unknown command: ${request.command}`, requestId: request.requestId }); return; }

        try {
            log('debug', `Executing command "${request.command}" from client`);
            const result = await handler(request.payload ?? {}, ws);
            this.send(ws, {type: 'response', payload: result ?? null, success: true, requestId: request.requestId});
            // Trigger save only for commands that modify state
            if (!['get_thoughts', 'get_rules', 'get_status', 'info', 'search', 'help', 'tools', 'ping'].includes(request.command)) {
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
    private broadcastThoughtChanges(): void { this.broadcastChanges(this.thoughts, 'thoughts'); }
    private broadcastRuleChanges(): void { this.broadcastChanges(this.rules, 'rules'); }
    private sendSnapshot(ws: WebSocket): void {
        this.send(ws, {
            type: 'snapshot', payload: {
                thoughts: this.thoughts.getAll().map(t => ({...t, belief: t.belief.toJSON()})),
                rules: this.rules.getAll().map(r => ({...r, belief: r.belief.toJSON()})),
                status: this.systemControl.getStatus()
            }
        });
        log('debug', `Sent snapshot to new client`);
    }

    // --- Command Handlers ---
    private findItemOrThrow(prefix: string, type: 'thought' | 'rule'): Thought | Rule {
        const item = type === 'thought' ? this.thoughts.findThought(prefix) : this.rules.findRule(prefix);
        if (!item) throw new Error(`${type === 'thought' ? 'Thought' : 'Rule'} prefix "${prefix}" not found.`);
        return item;
    }
    private registerCommandHandlers(): void {
        const handlers: { [key: string]: (payload: any, ws: WebSocket) => Promise<any | void> } = {
            add: async ({text}) => {
                if (!text) throw new Error("Missing 'text' in payload");
                const newThought: Thought = {
                    id: generateId(), type: Type.INPUT, content: TermLogic.fromString(text), belief: new Belief(1, 0), status: Status.PENDING,
                    metadata: {created: new Date().toISOString(), tags: ['user_added'], provenance: 'ws_api'}
                };
                newThought.metadata.rootId = newThought.id; // Input is its own root
                this.engine.addThought(newThought);
                return {id: newThought.id, message: `Added ${newThought.type}: ${shortId(newThought.id)}`};
            },
            respond: async ({promptId, text}) => {
                if (!promptId || !text) throw new Error("Missing 'promptId' or 'text'");
                const success = await this.engine.handlePromptResponse(promptId, text);
                if (!success) throw new Error(`Failed to process response for prompt ${shortId(promptId)}.`);
                return {message: `Response for ${shortId(promptId)} processed.`};
            },
            run: async () => { this.systemControl.startProcessing(); return {message: "Processing started."}; },
            pause: async () => { this.systemControl.pauseProcessing(); return {message: "Processing paused."}; },
            step: async () => {
                const count = await this.systemControl.stepProcessing();
                return { processed: count > 0, count, message: count > 0 ? `Step processed ${count} thought(s).` : "Nothing to step." };
            },
            save: async () => { await debouncedSaveState.flush(); await saveState(this.thoughts, this.rules, this.memory); return {message: "State saved."}; },
            quit: async () => { await this.systemControl.shutdown(); }, // No response needed, server will shut down
            get_status: async () => this.systemControl.getStatus(),
            info: async ({idPrefix}) => {
                if (!idPrefix) throw new Error("Missing 'idPrefix'");
                const thought = this.thoughts.findThought(idPrefix); if (thought) return {type: 'thought', data: {...thought, belief: thought.belief.toJSON()}};
                const rule = this.rules.findRule(idPrefix); if (rule) return {type: 'rule', data: {...rule, belief: rule.belief.toJSON()}};
                throw new Error(`ID prefix "${idPrefix}" not found.`);
            },
            get_thoughts: async () => this.thoughts.getAll().map(t => ({...t, belief: t.belief.toJSON()})),
            get_rules: async () => this.rules.getAll().map(r => ({...r, belief: r.belief.toJSON()})),
            tools: async () => this.tools.list().map(t => ({name: t.name, description: t.description})),
            tag: async ({idPrefix, tag}) => {
                if (!idPrefix || !tag) throw new Error("Missing 'idPrefix' or 'tag'");
                const thought = this.findItemOrThrow(idPrefix, 'thought') as Thought;
                thought.metadata.tags = [...new Set([...(thought.metadata.tags || []), tag])];
                this.thoughts.update(thought); return {id: thought.id, tags: thought.metadata.tags};
            },
            untag: async ({idPrefix, tag}) => {
                if (!idPrefix || !tag) throw new Error("Missing 'idPrefix' or 'tag'");
                const thought = this.findItemOrThrow(idPrefix, 'thought') as Thought;
                if (!thought.metadata.tags) throw new Error(`Thought ${shortId(thought.id)} has no tags.`);
                thought.metadata.tags = thought.metadata.tags.filter(t => t !== tag);
                this.thoughts.update(thought); return {id: thought.id, tags: thought.metadata.tags};
            },
            search: async ({query, k}) => {
                if (!query) throw new Error("Missing 'query'");
                return this.memory.search(query, typeof k === 'number' ? k : 5);
            },
            delete: async ({idPrefix}) => {
                if (!idPrefix) throw new Error("Missing 'idPrefix'");
                const thought = this.thoughts.findThought(idPrefix);
                if (thought) { if (!this.thoughts.delete(thought.id)) throw new Error(`Failed to delete thought ${shortId(thought.id)}.`); return {id: thought.id, message: `Thought ${shortId(thought.id)} deleted.`}; }
                const rule = this.rules.findRule(idPrefix);
                if (rule) { if (!this.rules.delete(rule.id)) throw new Error(`Failed to delete rule ${shortId(rule.id)}.`); return {id: rule.id, message: `Rule ${shortId(rule.id)} deleted.`}; }
                throw new Error(`Item with prefix "${idPrefix}" not found.`);
            },
            help: async () => ({
                description: "Available commands. Use 'info <idPrefix>' for details.",
                commands: [
                    {cmd: "add <text>", desc: "Add new INPUT thought."},
                    {cmd: "respond <promptId> <text>", desc: "Respond to a pending user prompt."},
                    {cmd: "run", desc: "Start processing loop."}, {cmd: "pause", desc: "Pause processing loop."},
                    {cmd: "step", desc: "Process one batch of thoughts."}, {cmd: "save", desc: "Force immediate state save."},
                    {cmd: "quit", desc: "Shutdown the server."}, {cmd: "get_status", desc: "Get processing loop status."},
                    {cmd: "info <idPrefix>", desc: "Get details of a thought/rule by ID prefix."},
                    {cmd: "get_thoughts", desc: "Get all thoughts."}, {cmd: "get_rules", desc: "Get all rules."},
                    {cmd: "tools", desc: "List available tools."}, {cmd: "tag <idPrefix> <tag>", desc: "Add tag to thought."},
                    {cmd: "untag <idPrefix> <tag>", desc: "Remove tag from thought."},
                    {cmd: "search <query> [k=5]", desc: "Search vector memory."},
                    {cmd: "delete <idPrefix>", desc: "Delete a thought or rule by ID prefix."},
                    {cmd: "ping", desc: "Check server connection."}, {cmd: "help", desc: "Show this help message."},
                ]
            }),
            ping: async () => "pong",
        };
        for (const [command, handler] of Object.entries(handlers)) this.commandHandlers.set(command, handler.bind(this));
    }
}

// --- Main System Orchestrator ---
class FlowMindSystem {
    private thoughts = new ThoughtStore();
    private rules = new RuleStore();
    private llm = new LLMService();
    private memory = new MemoryStore(this.llm.embeddings, VECTOR_STORE_DIR);
    private tools = new ToolRegistry();
    private prompts = new PromptRegistry();
    private engine = new Engine(this.thoughts, this.rules, this.memory, this.llm, this.tools, this.prompts);
    private apiServer: WsApiServer;
    private isRunning = false;
    private workerIntervalId: NodeJS.Timeout | null = null;
    private systemControl: SystemControl;

    constructor() {
        this.registerCoreTools();
        this.systemControl = {
            startProcessing: this.startProcessingLoop.bind(this), pauseProcessing: this.pauseProcessingLoop.bind(this),
            stepProcessing: this.engine.processBatch.bind(this.engine), shutdown: this.shutdown.bind(this),
            getStatus: () => ({isRunning: this.isRunning})
        };
        this.apiServer = new WsApiServer(WS_PORT, this.thoughts, this.rules, this.memory, this.engine, this.tools, this.systemControl);
    }

    private registerCoreTools(): void { [new LLMTool(), new MemoryTool(), new UserInteractionTool(), new GoalProposalTool(), new CoreTool()].forEach(t => this.tools.register(t)); }
    private bootstrapRules(): void {
        if (this.rules.count() > 0) return;
        log('info', "Bootstrapping default rules...");
        const createRule = (desc: string, pattern: Term, action: Term, priority: number = 0): Rule => ({
            id: generateId(), pattern, action, belief: Belief.DEFAULT,
            metadata: {description: desc, provenance: 'bootstrap', created: new Date().toISOString(), priority}
        });
        const rulesToAdd = [
            createRule("Outcome -> Add to Memory", TermLogic.Structure(Type.OUTCOME, [TermLogic.Variable("C")]), TermLogic.Structure("MemoryTool", [TermLogic.Atom("add"), TermLogic.Variable("C")]), 10),
            createRule("Outcome -> Suggest Next Goal", TermLogic.Structure(Type.OUTCOME, [TermLogic.Variable("C")]), TermLogic.Structure("GoalProposalTool", [TermLogic.Atom("suggest"), TermLogic.Variable("C")]), 5),
            createRule("Unclear Input -> Ask User", TermLogic.Structure(Type.INPUT, [TermLogic.Atom("unclear")]), TermLogic.Structure("UserInteractionTool", [TermLogic.Atom("prompt"), TermLogic.Atom("The input 'unclear' was received. Please clarify.")]), 20),
            createRule("Input Command: Search Memory -> Use MemoryTool", TermLogic.Structure(Type.INPUT, [TermLogic.Structure("search", [TermLogic.Variable("Q")])]), TermLogic.Structure("MemoryTool", [TermLogic.Atom("search"), TermLogic.Variable("Q")]), 15),
            createRule("Thought 'ok' -> Set Done", TermLogic.Atom("ok"), TermLogic.Structure("CoreTool", [TermLogic.Atom("set_status"), TermLogic.Atom("self"), TermLogic.Atom(Status.DONE)]), 1),
        ];
        rulesToAdd.forEach(rule => this.rules.add(rule));
        log('info', `${this.rules.count()} default rules added.`);
    }

    async initialize(): Promise<void> {
        log('info', 'Initializing FlowMind System...');
        await loadState(this.thoughts, this.rules, this.memory);
        this.bootstrapRules();
        this.apiServer.start();
        log('info', chalk.green('FlowMind Initialized.') + chalk.yellow(" Paused. Use API 'run' command to start processing."));
    }

    startProcessingLoop(): void {
        if (this.isRunning) { log('warn', "Processing already running."); return; }
        log('info', 'Starting processing loop...'); this.isRunning = true;
        this.apiServer.broadcast({type: 'status_update', payload: this.systemControl.getStatus()});
        this.workerIntervalId = setInterval(async () => {
            if (!this.isRunning) return;
            try { if (await this.engine.processBatch() > 0) debouncedSaveState(this.thoughts, this.rules, this.memory); }
            catch (error: any) { log('error', 'Error in processing loop:', error.message); }
        }, WORKER_INTERVAL);
    }

    pauseProcessingLoop(): void {
        if (!this.isRunning) { log('warn', "Processing already paused."); return; }
        log('warn', 'Pausing processing loop...'); this.isRunning = false;
        this.apiServer.broadcast({type: 'status_update', payload: this.systemControl.getStatus()});
        if (this.workerIntervalId) { clearInterval(this.workerIntervalId); this.workerIntervalId = null; }
        debouncedSaveState.flush(); // Ensure pending saves are done
        saveState(this.thoughts, this.rules, this.memory); // Save immediately on pause
    }

    async shutdown(): Promise<void> {
        log('info', '\nShutting down FlowMind System...');
        this.pauseProcessingLoop(); // Pause first, which also saves state
        this.apiServer.stop();
        debouncedSaveState.cancel(); // Cancel any pending debounced saves
        await saveState(this.thoughts, this.rules, this.memory); // Final save
        log('info', chalk.green('FlowMind shutdown complete. Goodbye!'));
        await sleep(100); process.exit(0);
    }
}

// --- REPL Client Example ---
async function startReplClient(serverUrl: string) {
    console.log(chalk.cyan(`Connecting to FlowMind server at ${serverUrl}...`));
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout, prompt: chalk.blueBright('FM Client> ') });
    let ws: WebSocket | null = null; let requestCounter = 0;
    const pendingRequests = new Map<string, { resolve: (value: any) => void, reject: (reason?: any) => void, command: string }>();
    const reconnectDelay = 5000; let reconnectTimeout: NodeJS.Timeout | null = null;

    const formatThought = (t: Thought, detail = false): string =>
        `${chalk.magenta(shortId(t.id))}|${chalk.yellow(t.type)}|${chalk.cyan(t.status)}|${TermLogic.format(t.content, false).substring(0, detail ? 150 : 50)}${TermLogic.format(t.content, false).length > (detail ? 150 : 50) ? '...' : ''}`;
    const formatRule = (r: Rule): string =>
        `${chalk.blue(shortId(r.id))}|P${r.metadata.priority ?? 0}|${chalk.green(TermLogic.format(r.pattern, false))} -> ${chalk.yellow(TermLogic.format(r.action, false))} (${r.metadata.description?.substring(0, 40) ?? ''}...)`;

    const connect = () => {
        if (reconnectTimeout) clearTimeout(reconnectTimeout); reconnectTimeout = null;
        if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
        console.log(chalk.yellow(`Attempting connection to ${serverUrl}...`));
        ws = new WebSocket(serverUrl);
        ws.on('open', () => { console.log(chalk.green(`Connected! Type 'help' for commands.`)); rl.prompt(); });
        ws.on('message', (data) => {
            const message = safeJsonParse(data.toString(), null);
            if (!message) { console.log(chalk.yellow('Received non-JSON message:', data.toString().substring(0, 100))); return; }
            const {type, payload, success, requestId, ...rest} = message;
            process.stdout.write('\n'); // Clear line for incoming message

            if (requestId && pendingRequests.has(requestId)) {
                const reqInfo = pendingRequests.get(requestId)!;
                if (type === 'response') success ? reqInfo.resolve(payload) : reqInfo.reject(new Error(payload || `Command '${reqInfo.command}' failed.`));
                else if (type === 'error') reqInfo.reject(new Error(payload || `Server error for '${reqInfo.command}'.`));
                pendingRequests.delete(requestId);
            } else if (type === 'error') console.error(chalk.red(`Server Error: ${payload}`));
            else if (type === 'snapshot') {
                console.log(chalk.magenta('--- Received State Snapshot ---'));
                console.log(chalk.cyan(`Thoughts: ${payload.thoughts?.length ?? 0}, Rules: ${payload.rules?.length ?? 0}, Status: ${payload.status?.isRunning ? chalk.green('RUNNING') : chalk.yellow('PAUSED')}`));
            } else if (type === 'thoughts_delta') {
                if (payload.changed.length > 0) console.log(chalk.green(`Thoughts Updated: ${payload.changed.map((t: Thought) => formatThought(t)).join(', ')}`));
                if (payload.deleted.length > 0) console.log(chalk.red(`Thoughts Deleted: ${payload.deleted.map(shortId).join(', ')}`));
            } else if (type === 'rules_delta') {
                if (payload.changed.length > 0) console.log(chalk.blue(`Rules Updated: ${payload.changed.map((r: Rule) => shortId(r.id)).join(', ')}`));
                if (payload.deleted.length > 0) console.log(chalk.yellow(`Rules Deleted: ${payload.deleted.map(shortId).join(', ')}`));
            } else if (type === 'status_update') console.log(chalk.magenta(`System Status: ${payload.isRunning ? chalk.green('RUNNING') : chalk.yellow('PAUSED')}`));
            else console.log(chalk.gray(`Server Message [${type || 'unknown'}]:`), payload ?? rest);

            rl.prompt(); // Re-display prompt after handling message
        });
        ws.on('close', () => {
            console.log(chalk.red('\nDisconnected. Attempting reconnect...')); ws = null;
            pendingRequests.forEach(p => p.reject(new Error("Disconnected"))); pendingRequests.clear();
            if (!reconnectTimeout) reconnectTimeout = setTimeout(connect, reconnectDelay);
        });
        ws.on('error', (error) => {
            console.error(chalk.red(`\nConnection Error: ${error.message}. Retrying...`)); ws?.close(); ws = null;
            if (!reconnectTimeout) reconnectTimeout = setTimeout(connect, reconnectDelay);
        });
    };

    const sendCommand = (command: string, payload: any): Promise<any> => {
        return new Promise((resolve, reject) => {
            if (!ws || ws.readyState !== WebSocket.OPEN) { reject(new Error("Not connected.")); return; }
            const requestId = `req-${requestCounter++}`;
            pendingRequests.set(requestId, {resolve, reject, command});
            ws.send(JSON.stringify({command, payload, requestId}));
            setTimeout(() => {
                if (pendingRequests.has(requestId)) { reject(new Error(`Request timeout for ${command}`)); pendingRequests.delete(requestId); rl.prompt(); }
            }, 15000); // 15s timeout
        });
    };

    rl.on('line', async (line) => {
        const trimmed = line.trim(); if (!trimmed) { rl.prompt(); return; }
        if (trimmed === 'quit' || trimmed === 'exit') { rl.close(); return; }
        const parts = trimmed.match(/(?:[^\s"]+|"[^"]*")+/g) ?? [];
        const command = parts[0]?.toLowerCase(); const args = parts.slice(1).map(arg => arg.startsWith('"') && arg.endsWith('"') ? arg.slice(1, -1) : arg);
        if (!command) { rl.prompt(); return; }

        let payload: any = {}; let valid = true;
        try { // Simple argument parsing based on command
            switch (command) {
                case 'add': payload = {text: args.join(' ')}; valid = args.length >= 1; break;
                case 'respond': payload = {promptId: args[0], text: args.slice(1).join(' ')}; valid = args.length >= 2; break;
                case 'info': case 'delete': payload = {idPrefix: args[0]}; valid = args.length === 1; break;
                case 'tag': case 'untag': payload = {idPrefix: args[0], tag: args[1]}; valid = args.length === 2; break;
                case 'search': payload = {query: args.join(' '), k: 5}; valid = args.length >= 1; break; // Default k=5
                case 'run': case 'pause': case 'step': case 'save': case 'quit': case 'get_status':
                case 'get_thoughts': case 'get_rules': case 'tools': case 'help': case 'ping':
                    payload = {}; valid = args.length === 0; break;
                default: console.log(chalk.yellow(`Unknown command: ${command}. Type 'help'.`)); valid = false; break;
            }
            if (!valid) { console.log(chalk.yellow(`Invalid arguments for '${command}'. Type 'help'.`)); rl.prompt(); return; }

            const result = await sendCommand(command, payload);
            // Custom formatting for specific commands
            if (command === 'help') {
                console.log(chalk.cyan(result.description));
                result.commands.forEach((c: {cmd: string, desc: string}) => console.log(`  ${chalk.blueBright(c.cmd)}: ${c.desc}`));
            } else if (command === 'info' && result?.type === 'thought') {
                console.log(chalk.cyan('--- Thought Info ---')); console.log(formatThought(result.data, true)); console.log(chalk.gray(JSON.stringify(result.data.metadata, null, 2)));
            } else if (command === 'info' && result?.type === 'rule') {
                console.log(chalk.cyan('--- Rule Info ---')); console.log(formatRule(result.data)); console.log(chalk.gray(JSON.stringify(result.data.metadata, null, 2)));
            } else if (command === 'get_thoughts' && Array.isArray(result)) {
                console.log(chalk.cyan(`--- All Thoughts (${result.length}) ---`)); result.forEach((t: Thought) => console.log(formatThought(t)));
            } else if (command === 'get_rules' && Array.isArray(result)) {
                console.log(chalk.cyan(`--- All Rules (${result.length}) ---`)); result.forEach((r: Rule) => console.log(formatRule(r)));
            } else if (command === 'tools' && Array.isArray(result)) {
                console.log(chalk.cyan('--- Available Tools ---')); result.forEach((t: {name: string, description: string}) => console.log(`  ${chalk.blueBright(t.name)}: ${t.description}`));
            } else if (result !== null && result !== undefined) console.log(chalk.cyan(`Result:`), result);
            else console.log(chalk.green('OK'));

        } catch (error: any) { console.error(chalk.red(`Error:`), error.message); }
        finally { rl.prompt(); }
    });
    rl.on('close', () => {
        console.log(chalk.yellow('\nREPL Client exiting.'));
        if (reconnectTimeout) clearTimeout(reconnectTimeout);
        if (ws && ws.readyState === WebSocket.OPEN) ws.close();
        setTimeout(() => process.exit(0), 100); // Allow time for close frame
    });
    connect(); // Initial connection attempt
}

// --- Main Execution ---
async function main() {
    const mode = process.argv[2] || 'server';
    if (mode === 'server') {
        let system: FlowMindSystem | null = null;
        const handleShutdown = async (signal: string) => {
            log('warn', `\n${signal} received. Shutting down...`);
            if (system) await system.shutdown(); else process.exit(0);
        };
        const handleException = async (context: string, error: any, origin?: any) => {
            log('error', `\n--- ${context.toUpperCase()} ---`);
            console.error(error); if (origin) console.error(`Origin: ${origin}`);
            log('error', '--------------------------');
            if (system) { try { await system.shutdown(); } catch { process.exit(1); } }
            else { process.exit(1); }
        };
        process.on('SIGINT', () => handleShutdown('SIGINT')); process.on('SIGTERM', () => handleShutdown('SIGTERM'));
        process.on('uncaughtException', (error, origin) => handleException('Uncaught Exception', error, origin));
        process.on('unhandledRejection', (reason, promise) => handleException('Unhandled Rejection', reason, promise));
        try { system = new FlowMindSystem(); await system.initialize(); }
        catch (error: any) { log('error', "Critical initialization error:", error.message); process.exit(1); }
    } else if (mode === 'client') {
        const serverUrl = process.argv[3] || `ws://localhost:${WS_PORT}`;
        await startReplClient(serverUrl);
    } else { console.error(chalk.red(`Invalid mode: ${mode}. Use 'server' or 'client [url]'`)); process.exit(1); }
}

main();