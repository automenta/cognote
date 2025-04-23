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
import {IncomingMessage} from 'http'; // For WebSocket connection request type

// --- Configuration ---
const DATA_DIR = path.join(os.homedir(), '.flowmind');
const STATE_FILE = path.join(DATA_DIR, 'flowmind_state.json');
const VECTOR_STORE_DIR = path.join(DATA_DIR, 'vector-store');
const LOG_FILE = path.join(DATA_DIR, 'flowmind_llm.log'); // For LLM logging
const OLLAMA_BASE_URL = process.env.OLLAMA_HOST || "http://localhost:11434";
const OLLAMA_MODEL = process.env.OLLAMA_MODEL || 'llamablit'; // Using a more common default
const OLLAMA_EMBEDDING_MODEL = process.env.OLLAMA_EMBED_MODEL || OLLAMA_MODEL;
const WORKER_INTERVAL = 1500; // Slightly faster interval
const SAVE_DEBOUNCE = 4000;
const MAX_RETRIES = 3; // Allow one more retry
const DEFAULT_BELIEF_POS = 1.0;
const DEFAULT_BELIEF_NEG = 1.0;
const SHORT_ID_LEN = 6;
const WS_PORT = 8080;
const ENGINE_BATCH_SIZE = 5; // Max thoughts to attempt processing per cycle
const ENGINE_MAX_CONCURRENCY = 3; // Max thoughts actively processed at once

// --- Types ---
enum Status { PENDING = 'PENDING', ACTIVE = 'ACTIVE', WAITING = 'WAITING', DONE = 'DONE', FAILED = 'FAILED' }

enum Type {
    INPUT = 'INPUT', GOAL = 'GOAL', STRATEGY = 'STRATEGY', OUTCOME = 'OUTCOME', QUERY = 'QUERY',
    USER_PROMPT = 'USER_PROMPT', SYSTEM = 'SYSTEM', RULE_ACTION = 'RULE_ACTION' // Added types
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
    provenance?: string; llmPrompt?: string; llmResponse?: string; // Added for logging
    [key: string]: any;
}

interface Thought {
    id: string; type: Type; content: Term; belief: Belief; status: Status; metadata: Metadata;
}

interface Rule {
    id: string; pattern: Term; action: Term; belief: Belief;
    metadata: { priority?: number; description?: string; provenance?: string; created?: string; tags?: string[]; };
}

interface MemoryEntry {
    id: string; embedding?: number[]; content: string;
    metadata: { created: string; type: string; sourceId: string; [key: string]: any; }; // Allow extra metadata
    score?: number;
}

interface AppState { thoughts: Record<string, any>; rules: Record<string, any>; }

interface ToolContext {
    thoughts: ThoughtStore; tools: ToolRegistry; rules: RuleStore; memory: MemoryStore;
    llm: LLMService; prompts: PromptRegistry; engine: Engine; // Engine access for adding thoughts etc.
}

interface Tool {
    name: string; description: string;
    execute(actionTerm: Structure, context: ToolContext, trigger: Thought): Promise<Term | null>;
}

interface SystemControl {
    startProcessing: () => void; pauseProcessing: () => void;
    stepProcessing: () => Promise<number>; shutdown: () => Promise<void>;
    getStatus: () => { isRunning: boolean; activeCount: number; pendingCount: number; };
}

type MatchResult = { rule: Rule; bindings: Map<string, Term> };
type ActionResult = { success: boolean; error?: string; finalStatus?: Status; resultTerm?: Term | null };
type FallbackResult = ActionResult;

// --- Utilities ---
const generateId = (): string => uuidv4();
const shortId = (id: string | undefined): string => id ? id.substring(0, SHORT_ID_LEN) : '------';

function debounce<T extends (...args: any[]) => any>(func: T, wait: number): T & {
    cancel: () => void; flush: () => Promise<void> | void; // Make flush potentially async
} {
    let timeout: NodeJS.Timeout | null = null;
    let lastArgs: Parameters<T> | null = null;
    let lastThis: any = null;
    let trailingCallScheduled = false;
    let lastResultPromise: Promise<void> | null = null;
    const debounced = (...args: Parameters<T>): void => {
        lastArgs = args; lastThis = this; trailingCallScheduled = true;
        if (!timeout) timeout = setTimeout(later, wait);
    };
    const later = () => {
        timeout = null;
        if (trailingCallScheduled && lastArgs) {
            lastResultPromise = Promise.resolve(func.apply(lastThis, lastArgs)).catch(err => console.error(chalk.red("Debounced function error:"), err));
            trailingCallScheduled = false; lastArgs = null; lastThis = null;
        }
    };
    debounced.cancel = () => {
        if (timeout) clearTimeout(timeout); timeout = null;
        trailingCallScheduled = false; lastArgs = null; lastThis = null;
    };
    debounced.flush = async () => {
        if (timeout) clearTimeout(timeout);
        later(); // Execute immediately
        await lastResultPromise; // Wait for the potentially async function to complete
    };
    return debounced as T & { cancel: () => void; flush: () => Promise<void> | void; };
}

const safeJsonParse = <T>(json: string | null | undefined, defaultValue: T): T => {
    if (!json) return defaultValue;
    try { return JSON.parse(json); } catch { return defaultValue; }
};

const sleep = (ms: number): Promise<void> => new Promise(resolve => setTimeout(resolve, ms));

async function logToFile(message: string): Promise<void> {
    try {
        await fs.appendFile(LOG_FILE, `${new Date().toISOString()} - ${message}\n`);
    } catch (err) {
        console.error(chalk.red(`Failed to write to log file ${LOG_FILE}:`), err);
    }
}

// --- Core Logic Classes ---

class Belief implements BeliefData {
    pos: number; neg: number;
    constructor(pos: number = DEFAULT_BELIEF_POS, neg: number = DEFAULT_BELIEF_NEG) {
        this.pos = Math.max(0, pos); this.neg = Math.max(0, neg);
    }
    score(): number { return (this.pos + 1) / (this.pos + this.neg + 2); } // Laplace smoothing
    update(success: boolean): void { success ? this.pos++ : this.neg++; }
    toJSON(): BeliefData { return {pos: this.pos, neg: this.neg}; }
    static fromJSON(data: any): Belief {
        return new Belief(data?.pos ?? DEFAULT_BELIEF_POS, data?.neg ?? DEFAULT_BELIEF_NEG);
    }
    static DEFAULT = new Belief();
}

namespace TermLogic {
    export const Atom = (name: string): Atom => ({kind: 'Atom', name});
    export const Variable = (name: string): Variable => ({kind: 'Variable', name});
    export const Structure = (name: string, args: Term[]): Structure => ({kind: 'Structure', name, args});
    export const List = (elements: Term[]): ListTerm => ({kind: 'ListTerm', elements});
    export const isTerm = (t: any): t is Term => t && typeof t === 'object' && 'kind' in t;

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
            case 'Structure': return `${term.name}(${term.args.map(toString).join(',')})`;
            case 'ListTerm': return `[${term.elements.map(toString).join(',')}]`;
            default: const exhaustiveCheck: never = term; return '';
        }
    }

    // Basic parser, assumes well-formed input for simplicity
    export function fromString(input: string): Term {
        input = input.trim();
        if (input.startsWith('?') && input.length > 1 && !input.includes('(') && !input.includes('[')) return Variable(input.substring(1));
        const structMatch = input.match(/^([a-zA-Z0-9_]+)\((.*)\)$/s); // Use 's' flag for dotall
        if (structMatch) {
            const name = structMatch[1];
            const argsStr = structMatch[2];
            const args = parseArgList(argsStr).map(fromString);
            return Structure(name, args);
        }
        const listMatch = input.match(/^\[(.*)\]$/s); // Use 's' flag for dotall
        if (listMatch) {
            const elementsStr = listMatch[1];
            const elements = parseArgList(elementsStr).map(fromString);
            return List(elements);
        }
        if (/^[a-zA-Z0-9_:]+$/.test(input) || input.includes(':')) return Atom(input); // Allow ':' in atoms
        return Atom(input); // Default to Atom, might need quotes for complex strings
    }

    // Helper to parse comma-separated args respecting nesting
    function parseArgList(argStr: string): string[] {
        const args: string[] = [];
        let currentArg = '';
        let parenDepth = 0;
        let bracketDepth = 0;
        for (let i = 0; i < argStr.length; i++) {
            const char = argStr[i];
            currentArg += char;
            if (char === '(') parenDepth++;
            else if (char === ')') parenDepth--;
            else if (char === '[') bracketDepth++;
            else if (char === ']') bracketDepth--;
            else if (char === ',' && parenDepth === 0 && bracketDepth === 0) {
                args.push(currentArg.slice(0, -1).trim());
                currentArg = '';
            }
        }
        if (currentArg.trim().length > 0) args.push(currentArg.trim());
        return args;
    }

    export function unify(term1: Term, term2: Term, bindings: Map<string, Term> = new Map()): Map<string, Term> | null {
        const resolve = (term: Term, currentBindings: Map<string, Term>): Term => {
            while (term.kind === 'Variable' && currentBindings.has(term.name)) {
                const bound = currentBindings.get(term.name)!; if (bound === term) return term; term = bound;
            } return term;
        };
        const t1 = resolve(term1, bindings); const t2 = resolve(term2, bindings);
        const occursCheck = (varName: string, termToCheck: Term): boolean => {
            if (termToCheck.kind === 'Variable') return varName === termToCheck.name;
            if (termToCheck.kind === 'Structure') return termToCheck.args.some(arg => occursCheck(varName, resolve(arg, bindings)));
            if (termToCheck.kind === 'ListTerm') return termToCheck.elements.some(el => occursCheck(varName, resolve(el, bindings)));
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
                    const result = unify(t1.args[i], s2.args[i], currentBindings); if (!result) return null; currentBindings = result;
                } return currentBindings;
            }
            case 'ListTerm': {
                const l2 = t2 as ListTerm;
                if (t1.elements.length !== l2.elements.length) return null;
                let currentBindings = bindings;
                for (let i = 0; i < t1.elements.length; i++) {
                    const result = unify(t1.elements[i], l2.elements[i], currentBindings); if (!result) return null; currentBindings = result;
                } return currentBindings;
            }
            default: const exhaustiveCheck: never = t1; return null;
        }
    }

    export function substitute(term: Term, bindings: Map<string, Term>): Term {
        if (term.kind === 'Variable' && bindings.has(term.name)) {
            const bound = bindings.get(term.name)!;
            // Avoid infinite loops for recursive bindings (e.g., ?X = ?X)
            if (bound.kind === 'Variable' && bound.name === term.name) return term;
            return substitute(bound, bindings);
        }
        if (term.kind === 'Structure') return {...term, args: term.args.map(arg => substitute(arg, bindings))};
        if (term.kind === 'ListTerm') return {...term, elements: term.elements.map(el => substitute(el, bindings))};
        return term;
    }

    export function jsonToTerm(data: any): Term | null {
        if (data === null) return Atom('null');
        if (typeof data === 'string') return Atom(data);
        if (typeof data === 'number') return Atom(data.toString());
        if (typeof data === 'boolean') return Atom(data.toString());
        if (Array.isArray(data)) {
            const elements = data.map(jsonToTerm).filter(t => t !== null) as Term[];
            return elements.length === data.length ? List(elements) : null; // Convert all array items
        }
        if (typeof data === 'object') {
            if (isTerm(data)) return data; // Already a term object
            const args = Object.entries(data)
                .map(([k, v]) => {
                    const valTerm = jsonToTerm(v);
                    return valTerm ? Structure("kv", [Atom(k), valTerm]) : null;
                })
                .filter(t => t !== null) as Structure[];
            return Structure("json_obj", args);
        }
        return null;
    }
}

abstract class BaseStore<T extends { id: string, metadata?: { created?: string, modified?: string } }> {
    protected items = new Map<string, T>();
    private listeners: Set<() => void> = new Set();
    private changedItems: Set<string> = new Set();
    private deletedItems: Set<string> = new Set();

    add(item: T): void {
        const now = new Date().toISOString();
        const newItem = {...item, metadata: {...(item.metadata ?? {}), created: item.metadata?.created ?? now, modified: now}};
        this.items.set(item.id, newItem);
        this.changedItems.add(item.id); this.deletedItems.delete(item.id);
        this.notifyChange();
    }

    get(id: string): T | undefined { return this.items.get(id); }

    update(id: string, updateFn: (item: T) => T): boolean {
        const existing = this.items.get(id);
        if (!existing) return false;
        const updatedItem = {...updateFn(existing), metadata: {...existing.metadata, ...(existing.metadata ?? {}), modified: new Date().toISOString()}};
        this.items.set(id, updatedItem);
        this.changedItems.add(id); this.deletedItems.delete(id);
        this.notifyChange();
        return true;
    }

    delete(id: string): boolean {
        if (!this.items.has(id)) return false;
        this.items.delete(id);
        this.changedItems.delete(id); this.deletedItems.add(id);
        this.notifyChange();
        return true;
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
        const changed = Array.from(this.changedItems).map(id => this.items.get(id)).filter((item): item is T => !!item);
        const deleted = Array.from(this.deletedItems);
        this.changedItems.clear(); this.deletedItems.clear();
        return {changed, deleted};
    }

    protected notifyChange(): void { this.listeners.forEach(listener => listener()); }

    abstract toJSON(): Record<string, any>;
    abstract loadJSON(data: Record<string, any>): void;
}

class ThoughtStore extends BaseStore<Thought> {
    getPending(): Thought[] { return this.getAll().filter(t => t.status === Status.PENDING); }
    getActiveCount(): number { return this.getAll().filter(t => t.status === Status.ACTIVE).length; }

    getAllByRootId(rootId: string): Thought[] {
        const all = this.getAll(); const root = this.get(rootId); if (!root) return [];
        const tree = new Set<string>([rootId]); const queue = [rootId];
        while (queue.length > 0) {
            const currentId = queue.shift()!;
            all.forEach(t => {
                if (t.metadata.parentId === currentId && !tree.has(t.id)) { tree.add(t.id); queue.push(t.id); }
            });
        }
        return all.filter(t => tree.has(t.id)).sort((a, b) => Date.parse(a.metadata.created ?? '0') - Date.parse(b.metadata.created ?? '0'));
    }

    searchByTag(tag: string): Thought[] { return this.getAll().filter(t => t.metadata.tags?.includes(tag)); }
    findThought(idPrefix: string): Thought | undefined { return this.findItemByPrefix(idPrefix); }
    findPendingPrompt(promptId: string): Thought | undefined { return this.getAll().find(t => t.type === Type.USER_PROMPT && t.status === Status.PENDING && t.metadata.promptId === promptId); }
    findWaitingThought(promptId: string): Thought | undefined { return this.getAll().find(t => t.status === Status.WAITING && t.metadata.waitingFor === promptId); }

    toJSON(): Record<string, any> {
        const obj: Record<string, any> = {};
        this.items.forEach((thought, id) => obj[id] = {...thought, belief: thought.belief.toJSON()});
        return obj;
    }

    loadJSON(data: Record<string, any>): void {
        this.items.clear();
        Object.entries(data).forEach(([id, thoughtData]) => {
            const {belief, ...rest} = thoughtData;
            const loadedThought: Thought = {...rest, belief: Belief.fromJSON(belief), id};
            // Ensure thoughts loaded as ACTIVE or WAITING are reset to PENDING on load, unless WAITING for a still-pending prompt.
            if (loadedThought.status === Status.ACTIVE || (loadedThought.status === Status.WAITING && !this.findPendingPrompt(loadedThought.metadata.waitingFor ?? ''))) {
                loadedThought.status = Status.PENDING;
                delete loadedThought.metadata.waitingFor;
                delete loadedThought.metadata.agentId;
            }
            this.add(loadedThought); // Use add to ensure metadata timestamps are consistent if missing
        });
        this.changedItems.clear(); this.deletedItems.clear();
    }
}

class RuleStore extends BaseStore<Rule> {
    searchByDescription(desc: string): Rule[] {
        const lowerDesc = desc.toLowerCase();
        return this.getAll().filter(r => r.metadata.description?.toLowerCase().includes(lowerDesc));
    }
    searchByTag(tag: string): Rule[] { return this.getAll().filter(r => r.metadata.tags?.includes(tag)); }
    findRule(idPrefix: string): Rule | undefined { return this.findItemByPrefix(idPrefix); }

    toJSON(): Record<string, any> {
        const obj: Record<string, any> = {};
        this.items.forEach((rule, id) => obj[id] = {...rule, belief: rule.belief.toJSON()});
        return obj;
    }

    loadJSON(data: Record<string, any>): void {
        this.items.clear();
        Object.entries(data).forEach(([id, ruleData]) => {
            const {belief, ...rest} = ruleData; this.add({...rest, belief: Belief.fromJSON(belief), id});
        });
        this.changedItems.clear(); this.deletedItems.clear();
    }
}

class MemoryStore {
    private vectorStore: FaissStore | null = null;
    private embeddings: Embeddings;
    private storePath: string;
    public isReady = false;

    constructor(embeddings: Embeddings, storePath: string) {
        this.embeddings = embeddings; this.storePath = storePath;
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
                    const dummyDoc = new Document({pageContent: "Initial sentinel document", metadata: { sourceId: "init", type: "SYSTEM", created: new Date().toISOString(), id: "init_doc", _docId: generateId() }});
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
        // Ensure unique _docId for FaissStore if not provided explicitly
        const docMetadata = {...entry.metadata, id: entry.id, _docId: entry.metadata._docId || generateId()};
        const doc = new Document({pageContent: entry.content, metadata: docMetadata});
        try { await this.vectorStore.addDocuments([doc]); await this.save(); } // Save immediately after add for now
        catch (error) { console.error(chalk.red('Failed to add document to vector store:'), error); }
    }

    async search(query: string, k: number = 5, filter?: (doc: Document) => boolean): Promise<MemoryEntry[]> {
        if (!this.vectorStore || !this.isReady) { console.warn(chalk.yellow('MemoryStore not ready, cannot search.')); return []; }
        try {
            const results = await this.vectorStore.similaritySearchWithScore(query, k, filter);
            return results
                .filter(([doc]) => doc.metadata.sourceId !== 'init') // Filter out dummy doc
                .map(([doc, score]): MemoryEntry => ({
                    id: doc.metadata.id ?? generateId(), // Use stored ID or generate if missing
                    content: doc.pageContent,
                    metadata: { created: doc.metadata.created ?? new Date().toISOString(), type: doc.metadata.type ?? 'UNKNOWN', sourceId: doc.metadata.sourceId ?? 'UNKNOWN', ...doc.metadata },
                    score: score, embedding: [] // Embeddings not typically returned by search
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
    llm: BaseChatModel; embeddings: Embeddings;

    constructor() {
        this.llm = new ChatOllama({baseUrl: OLLAMA_BASE_URL, model: OLLAMA_MODEL, temperature: 0.6});
        this.embeddings = new OllamaEmbeddings({model: OLLAMA_EMBEDDING_MODEL, baseUrl: OLLAMA_BASE_URL});
        console.log(chalk.blue(`LLM Service: Model=${OLLAMA_MODEL}, Embeddings=${OLLAMA_EMBEDDING_MODEL}, URL=${OLLAMA_BASE_URL}`));
    }

    async generate(prompt: string, contextThoughtId?: string): Promise<string> {
        const logCtx = contextThoughtId ? ` (Context: ${shortId(contextThoughtId)})` : '';
        await logToFile(`PROMPT${logCtx}:\n---\n${prompt}\n---`);
        try {
            const response = await this.llm.pipe(new StringOutputParser()).invoke([new HumanMessage(prompt)]);
            const result = response.trim();
            await logToFile(`RESPONSE${logCtx}:\n---\n${result}\n---`);
            return result;
        } catch (error: any) {
            const errorMsg = `Error: LLM generation failed. ${error.message}`;
            await logToFile(`ERROR${logCtx}: ${errorMsg}`);
            console.error(chalk.red(`LLM generation failed${logCtx}: ${error.message}`));
            return errorMsg;
        }
    }

    async embed(text: string): Promise<number[]> {
        try { return await this.embeddings.embedQuery(text); }
        catch (error: any) { console.error(chalk.red(`Embedding failed: ${error.message}`)); return []; }
    }
}

type PromptKey = 'GENERATE_GOAL' | 'GENERATE_STRATEGY' | 'GENERATE_OUTCOME' | 'SUGGEST_GOAL' | 'FALLBACK_ASK_USER' | 'REFINE_RULE' | 'SYSTEM_REFLECTION';
class PromptRegistry {
    private prompts: Record<PromptKey, string> = {
        GENERATE_GOAL: 'Input: "{input}". Define a specific, actionable GOAL based on this input. Respond with ONLY the goal text.',
        GENERATE_STRATEGY: 'Goal: "{goal}". Outline 1-3 concrete STRATEGY steps to achieve this goal. Respond with ONLY the strategy steps, each on a new line, starting with "- ".',
        GENERATE_OUTCOME: 'Strategy step "{strategy}" was attempted. Describe a likely concise OUTCOME. Respond with ONLY the outcome text.',
        SUGGEST_GOAL: 'Context: The last activity was "{context}"{memoryContext}\n\nSuggest ONE concise, actionable next goal or task. Respond with only the suggested goal text.',
        FALLBACK_ASK_USER: 'I encountered a situation I could not handle automatically regarding thought {thoughtId} ({thoughtType}: "{thoughtContent}"). How should I proceed?',
        REFINE_RULE: `Rule ID {ruleId} failed {failCount} times. Description: "{description}". Pattern: "{pattern}". Action: "{action}". Suggest a potential refinement to the pattern or action, or suggest deleting it. Respond with the suggestion.`,
        SYSTEM_REFLECTION: `Current System State:\n- Pending Thoughts: {pendingCount}\n- Active Thoughts: {activeCount}\n- Recent Errors: {recentErrors}\n- Memory Context: {memoryContext}\n\nReflect on the current state. Are there bottlenecks, recurring errors, or areas for improvement? Suggest a high-level SYSTEM goal to address one issue. Respond with only the suggested goal text.`
    };

    format(key: PromptKey, context: Record<string, string>): string {
        let template = this.prompts[key] ?? `Error: Prompt template "${key}" not found. Context: ${JSON.stringify(context)}`;
        Object.entries(context).forEach(([k, v]) => template = template.replace(new RegExp(`{${k}}`, 'g'), v));
        return template;
    }
}

// --- Tools ---
abstract class BaseTool implements Tool {
    abstract name: string; abstract description: string;
    abstract execute(actionTerm: Structure, context: ToolContext, trigger: Thought): Promise<Term | null>;
    protected createErrorAtom(code: string, message?: string): Atom {
        const fullMessage = `${this.name}_${code}${message ? `:${message.substring(0, 50)}` : ''}`;
        return TermLogic.Atom(`error:${fullMessage}`);
    }
    protected createOkAtom(message: string = 'ok'): Atom { return TermLogic.Atom(`ok:${message}`); }
}

class LLMTool extends BaseTool {
    name = "LLMTool"; description = "Interacts with LLM: generate(prompt_key_atom, context_term), embed(text_term). Context term: Structure(kv(key,val),...).";

    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const opAtom = action.args[0]; if (opAtom?.kind !== 'Atom') return this.createErrorAtom("invalid_op");
        const operation = opAtom.name;
        trigger.metadata.agentId = `tool:${this.name}:${operation}`; // Mark agent
        context.thoughts.update(trigger.id, t => t); // Update metadata immediately

        switch (operation) {
            case 'generate': {
                const keyAtom = action.args[1]; const ctxTerm = action.args[2];
                if (keyAtom?.kind !== 'Atom') return this.createErrorAtom("invalid_gen_params", "Missing prompt key");
                const promptKey = keyAtom.name as PromptKey;
                const promptCtx: Record<string, string> = {};
                if (ctxTerm?.kind === 'Structure') ctxTerm.args.forEach(arg => {
                    if (arg.kind === 'Structure' && arg.name === 'kv' && arg.args[0]?.kind === 'Atom' && TermLogic.isTerm(arg.args[1])) {
                        promptCtx[arg.args[0].name] = TermLogic.toString(arg.args[1]);
                    } else if (arg.kind === 'Atom' && arg.name.includes(':')) { // Legacy key:value atom support
                        const [k, ...v] = arg.name.split(':'); if (k && v.length > 0) promptCtx[k] = v.join(':');
                    }
                });
                const prompt = context.prompts.format(promptKey, promptCtx);
                const response = await context.llm.generate(prompt, trigger.id);
                context.thoughts.update(trigger.id, t => ({...t, metadata: {...t.metadata, llmPrompt: prompt.substring(0, 500), llmResponse: response.substring(0, 500)}}));
                if (response.startsWith('Error:')) return this.createErrorAtom("gen_failed", response);
                // Try parsing as JSON first, then fallback to Atom/List structure
                try { const termFromJson = TermLogic.jsonToTerm(JSON.parse(response)); if (termFromJson) return termFromJson; } catch {}
                const lines = response.split('\n').map(s => s.trim().replace(/^- /, '')).filter(s => s.length > 0);
                return lines.length === 1 ? TermLogic.Atom(lines[0]) : TermLogic.List(lines.map(TermLogic.Atom));
            }
            case 'embed': {
                const inputTerm = action.args[1]; if (!inputTerm) return this.createErrorAtom("invalid_embed_params");
                const inputText = TermLogic.toString(inputTerm);
                const embedding = await context.llm.embed(inputText);
                // Embeddings are not directly representable as simple Terms, signal success/failure.
                return embedding.length > 0 ? this.createOkAtom("embedded") : this.createErrorAtom("embed_failed");
            }
            default: return this.createErrorAtom("unsupported_op", operation);
        }
    }
}

class MemoryTool extends BaseTool {
    name = "MemoryTool"; description = "Manages vector memory: add(content_term, [metadata_term]), search(query_term, [k_atom], [filter_term?]).";

    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const opAtom = action.args[0]; if (opAtom?.kind !== 'Atom') return this.createErrorAtom("missing_op");
        const operation = opAtom.name;
        trigger.metadata.agentId = `tool:${this.name}:${operation}`; context.thoughts.update(trigger.id, t => t);

        switch (operation) {
            case 'add': {
                const contentTerm = action.args[1]; const metaTerm = action.args[2];
                if (!contentTerm) return this.createErrorAtom("missing_add_content");
                let extraMeta: Record<string, any> = {};
                if (metaTerm?.kind === 'Structure' && metaTerm.name === 'json_obj') {
                    metaTerm.args.forEach(arg => {
                        if (arg.kind === 'Structure' && arg.name === 'kv' && arg.args[0]?.kind === 'Atom' && TermLogic.isTerm(arg.args[1])) {
                            extraMeta[arg.args[0].name] = TermLogic.toString(arg.args[1]); // Simple string conversion for now
                        }
                    });
                }
                const memoryId = generateId();
                await context.memory.add({
                    id: memoryId, content: TermLogic.toString(contentTerm),
                    metadata: { created: new Date().toISOString(), type: trigger.type, sourceId: trigger.id, ...extraMeta }
                });
                context.thoughts.update(trigger.id, t => ({...t, metadata: {...t.metadata, embedded: new Date().toISOString()}}));
                return this.createOkAtom(`memory_added:${memoryId}`);
            }
            case 'search': {
                const queryTerm = action.args[1]; const kTerm = action.args[2]; const filterTerm = action.args[3];
                if (!queryTerm) return this.createErrorAtom("missing_search_query");
                const queryStr = TermLogic.toString(queryTerm);
                const k = (kTerm?.kind === 'Atom' && /^\d+$/.test(kTerm.name)) ? parseInt(kTerm.name, 10) : 5;
                // Basic filter example: filterTerm = Structure("filter", [Atom("type:GOAL")])
                const filterFn = (doc: Document): boolean => {
                    if (filterTerm?.kind !== 'Structure' || filterTerm.name !== 'filter' || filterTerm.args.length === 0) return true; // No filter
                    const filterAtom = filterTerm.args[0];
                    if (filterAtom.kind !== 'Atom' || !filterAtom.name.includes(':')) return true; // Invalid filter format
                    const [key, value] = filterAtom.name.split(':', 2);
                    return doc.metadata[key] === value;
                };
                const results = await context.memory.search(queryStr, k, filterFn);
                return TermLogic.List(results.map(r => TermLogic.Structure("memory_result", [TermLogic.Atom(r.id), TermLogic.Atom(r.content.substring(0, 100)), TermLogic.Atom(r.score?.toFixed(4) ?? 'N/A')])));
            }
            default: return this.createErrorAtom("unsupported_op", operation);
        }
    }
}

class UserInteractionTool extends BaseTool {
    name = "UserInteractionTool"; description = "Requests input: prompt(prompt_text_term | prompt_key_atom, [context_term?]).";

    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const opAtom = action.args[0]; if (opAtom?.kind !== 'Atom' || opAtom.name !== 'prompt') return this.createErrorAtom("invalid_op");
        const promptSource = action.args[1]; const promptCtxTerm = action.args[2];
        if (!promptSource) return this.createErrorAtom("missing_prompt_source");
        trigger.metadata.agentId = `tool:${this.name}:prompt`; context.thoughts.update(trigger.id, t => t);

        let promptText: string; let promptKeyUsed: PromptKey | null = null;
        if (promptSource.kind === 'Atom') {
            promptKeyUsed = promptSource.name as PromptKey;
            const promptContext: Record<string, string> = {};
            if (promptCtxTerm?.kind === 'Structure') promptCtxTerm.args.forEach(arg => {
                if (arg.kind === 'Structure' && arg.name === 'kv' && arg.args[0]?.kind === 'Atom' && TermLogic.isTerm(arg.args[1])) promptCtx[arg.args[0].name] = TermLogic.toString(arg.args[1]);
                else if (arg.kind === 'Atom' && arg.name.includes(':')) { const [k, ...v] = arg.name.split(':'); if (k && v.length > 0) promptContext[k] = v.join(':'); }
            });
            promptText = context.prompts.format(promptKeyUsed, promptContext);
        } else { promptText = TermLogic.toString(promptSource); }

        const promptId = generateId();
        const promptThought: Thought = {
            id: promptId, type: Type.USER_PROMPT, content: TermLogic.Atom(promptText), belief: Belief.DEFAULT, status: Status.PENDING,
            metadata: { rootId: trigger.metadata.rootId ?? trigger.id, parentId: trigger.id, created: new Date().toISOString(), promptId: promptId, provenance: this.name, promptKey: promptKeyUsed ?? undefined }
        };
        context.engine.addThought(promptThought); // Add via engine to ensure it's tracked

        // Update trigger thought immediately to WAITING status
        const updated = context.thoughts.update(trigger.id, t => ({...t, status: Status.WAITING, metadata: {...t.metadata, waitingFor: promptId}}));
        if (!updated) return this.createErrorAtom("trigger_not_found", `ID ${shortId(trigger.id)} disappeared`);

        return this.createOkAtom(`prompt_requested:${promptId}`);
    }
}

class GoalProposalTool extends BaseTool {
    name = "GoalProposalTool"; description = "Suggests new goals: suggest([context_term?]). Uses trigger content/memory if no term.";

    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const opAtom = action.args[0]; if (opAtom?.kind !== 'Atom' || opAtom.name !== 'suggest') return this.createErrorAtom("invalid_op");
        trigger.metadata.agentId = `tool:${this.name}:suggest`; context.thoughts.update(trigger.id, t => t);

        const contextTerm = action.args[1] ?? trigger.content;
        const contextStr = TermLogic.toString(contextTerm).substring(0, 200);
        const memoryResults = await context.memory.search(`Relevant past goals or outcomes related to: ${contextStr}`, 3);
        const memoryContext = memoryResults.length > 0 ? `\nRelated past activities:\n - ${memoryResults.map(r => r.content.substring(0, 100)).join("\n - ")}` : "";

        const prompt = context.prompts.format('SUGGEST_GOAL', {context: contextStr, memoryContext: memoryContext});
        const suggestionText = await context.llm.generate(prompt, trigger.id);
        context.thoughts.update(trigger.id, t => ({...t, metadata: {...t.metadata, llmPrompt: prompt.substring(0, 500), llmResponse: suggestionText.substring(0, 500)}}));

        if (!suggestionText || suggestionText.startsWith('Error:')) return this.createErrorAtom("llm_failed", suggestionText);

        const suggestionThought: Thought = {
            id: generateId(), type: Type.GOAL, content: TermLogic.Atom(suggestionText), belief: new Belief(1, 0), status: Status.PENDING,
            metadata: { rootId: trigger.metadata.rootId ?? trigger.id, parentId: trigger.id, created: new Date().toISOString(), tags: ['suggested_goal'], provenance: this.name }
        };
        context.engine.addThought(suggestionThought);
        return this.createOkAtom(`suggestion_created:${shortId(suggestionThought.id)}`);
    }
}

class CoreTool extends BaseTool {
    name = "CoreTool"; description = "Manages state: set_status(id, status), add_thought(type, content, [rootId?], [parentId?], [meta?]), delete_thought(id). IDs can be atom(id) or atom('self').";

    private resolveTargetId(term: Term | undefined, triggerId: string, context: ToolContext): string | null {
        if (term?.kind !== 'Atom') return null;
        if (term.name === 'self') return triggerId;
        return context.thoughts.findThought(term.name)?.id ?? context.rules.findRule(term.name)?.id ?? null; // Allow finding rules too? Maybe not needed yet.
    }

    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const opAtom = action.args[0]; if (opAtom?.kind !== 'Atom') return this.createErrorAtom("missing_op");
        const operation = opAtom.name;
        trigger.metadata.agentId = `tool:${this.name}:${operation}`; // No status update here as CoreTool modifies state directly

        switch (operation) {
            case 'set_status': {
                const targetId = this.resolveTargetId(action.args[1], trigger.id, context);
                const statusAtom = action.args[2];
                if (!targetId || statusAtom?.kind !== 'Atom') return this.createErrorAtom("invalid_params", "Need target ID atom and status atom");
                const newStatus = statusAtom.name.toUpperCase() as Status;
                if (!Object.values(Status).includes(newStatus)) return this.createErrorAtom("invalid_status", newStatus);
                const updated = context.thoughts.update(targetId, t => ({...t, status: newStatus}));
                return updated ? this.createOkAtom(`status_set:${shortId(targetId)}_to_${newStatus}`) : this.createErrorAtom("target_not_found", targetId);
            }
            case 'add_thought': {
                const typeAtom = action.args[1]; const contentTerm = action.args[2];
                const rootIdTerm = action.args[3]; const parentIdTerm = action.args[4]; const metaTerm = action.args[5];
                if (typeAtom?.kind !== 'Atom' || !contentTerm) return this.createErrorAtom("invalid_params", "Need type atom and content term");
                const type = typeAtom.name.toUpperCase() as Type;
                if (!Object.values(Type).includes(type)) return this.createErrorAtom("invalid_type", typeAtom.name);

                const parentId = this.resolveTargetId(parentIdTerm, trigger.id, context) ?? trigger.id;
                const parentThought = context.thoughts.get(parentId);
                const rootId = this.resolveTargetId(rootIdTerm, trigger.id, context) ?? parentThought?.metadata.rootId ?? parentId;

                let extraMeta: Record<string, any> = {};
                if (metaTerm?.kind === 'Structure' && metaTerm.name === 'json_obj') {
                    metaTerm.args.forEach(arg => {
                        if (arg.kind === 'Structure' && arg.name === 'kv' && arg.args[0]?.kind === 'Atom' && TermLogic.isTerm(arg.args[1])) {
                            extraMeta[arg.args[0].name] = TermLogic.toString(arg.args[1]); // Simple string conversion
                        }
                    });
                }

                const newThought: Thought = {
                    id: generateId(), type, content: contentTerm, belief: Belief.DEFAULT, status: Status.PENDING,
                    metadata: { rootId, parentId, created: new Date().toISOString(), provenance: `${this.name} (rule ${shortId(trigger.metadata.ruleId)} on ${shortId(trigger.id)})`, ...extraMeta }
                };
                context.engine.addThought(newThought);
                return this.createOkAtom(`thought_added:${shortId(newThought.id)}`);
            }
            case 'delete_thought': {
                const targetId = this.resolveTargetId(action.args[1], trigger.id, context);
                if (!targetId) return this.createErrorAtom("invalid_params", "Need target ID atom");
                const deleted = context.thoughts.delete(targetId);
                return deleted ? this.createOkAtom(`thought_deleted:${shortId(targetId)}`) : this.createErrorAtom("delete_failed", targetId);
            }
            default: return this.createErrorAtom("unsupported_op", operation);
        }
    }
}

class ToolRegistry {
    private tools = new Map<string, Tool>();
    register(tool: Tool): void { if (this.tools.has(tool.name)) console.warn(chalk.yellow(`Tool "${tool.name}" redefined.`)); this.tools.set(tool.name, tool); }
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

        // Sort by priority (higher first), then belief score (higher first)
        applicableMatches.sort((a, b) => {
            const priorityDiff = (b.rule.metadata.priority ?? 0) - (a.rule.metadata.priority ?? 0);
            if (priorityDiff !== 0) return priorityDiff;
            return b.rule.belief.score() - a.rule.belief.score();
        });
        return applicableMatches[0]; // Deterministic best match
    }
};

const ActionExecutor = {
    async execute(actionTerm: Term, context: ToolContext, trigger: Thought, rule: Rule): Promise<ActionResult> {
        if (actionTerm.kind !== 'Structure') return { success: false, error: `Invalid action term kind: ${actionTerm.kind}`, finalStatus: Status.FAILED };
        const tool = context.tools.get(actionTerm.name);
        if (!tool) return { success: false, error: `Tool not found: ${actionTerm.name}`, finalStatus: Status.FAILED };

        try {
            const resultTerm = await tool.execute(actionTerm, context, trigger);
            // Re-fetch trigger state as tool might have modified it (esp. status)
            const currentThoughtState = context.thoughts.get(trigger.id);

            if (!currentThoughtState) { // Thought deleted by tool
                console.warn(chalk.yellow(`Thought ${shortId(trigger.id)} deleted during action execution by ${tool.name}.`));
                return { success: true, resultTerm }; // Deletion is a form of success
            }

            const isWaiting = currentThoughtState.status === Status.WAITING;
            const returnedError = resultTerm?.kind === 'Atom' && resultTerm.name.startsWith('error:');

            if (returnedError) return { success: false, error: `Tool execution failed: ${resultTerm.name}`, finalStatus: Status.FAILED, resultTerm };
            if (isWaiting) return { success: true, finalStatus: Status.WAITING, resultTerm }; // Action initiated waiting state

            // If no specific failure/waiting state, assume success leading to DONE by default
            return { success: true, finalStatus: Status.DONE, resultTerm };

        } catch (error: any) {
            console.error(chalk.red(`Tool exception ${tool.name} on ${shortId(trigger.id)}:`), error);
            return { success: false, error: `Tool exception: ${error.message}`, finalStatus: Status.FAILED };
        }
    }
};

const FallbackHandler = {
    async handle(thought: Thought, context: ToolContext): Promise<FallbackResult> {
        let action: Term | null = null;
        let llmPromptKey: PromptKey | null = null;
        let llmContext: Record<string, string> = {};
        let targetType: Type | null = null;
        const contentStr = TermLogic.toString(thought.content).substring(0, 150);

        switch (thought.type) {
            case Type.INPUT: llmPromptKey = 'GENERATE_GOAL'; llmContext = {input: contentStr}; targetType = Type.GOAL; break;
            case Type.GOAL: llmPromptKey = 'GENERATE_STRATEGY'; llmContext = {goal: contentStr}; targetType = Type.STRATEGY; break;
            case Type.STRATEGY: llmPromptKey = 'GENERATE_OUTCOME'; llmContext = {strategy: contentStr}; targetType = Type.OUTCOME; break;
            case Type.OUTCOME: // Default for Outcome: Add to memory and suggest next goal
                action = TermLogic.Structure("MemoryTool", [TermLogic.Atom("add"), thought.content]);
                // We'll chain the suggestion later if memory add succeeds
                break;
            case Type.SYSTEM: // System thoughts might trigger reflection or specific actions
                // Example: If system thought is about errors, try reflection
                if (contentStr.toLowerCase().includes("error")) {
                    const recentErrors = context.thoughts.getAll()
                        .filter(t => t.status === Status.FAILED && t.metadata.error)
                        .slice(-3) // Limit to last 3 errors
                        .map(t => `${shortId(t.id)}: ${t.metadata.error?.substring(0, 50)}`)
                        .join('; ');
                    const memResults = await context.memory.search("System performance reflection", 2);
                    const memContext = memResults.length > 0 ? `\nPast Reflections:\n - ${memResults.map(r => r.content.substring(0,80)).join("\n - ")}` : "";
                    llmPromptKey = 'SYSTEM_REFLECTION';
                    llmContext = { pendingCount: context.thoughts.getPending().length.toString(), activeCount: context.thoughts.getActiveCount().toString(), recentErrors: recentErrors || 'None', memoryContext: memContext };
                    targetType = Type.GOAL; // Suggest a goal to fix the issue
                } else {
                    action = TermLogic.Structure("MemoryTool", [TermLogic.Atom("add"), thought.content]); // Default: add system message to memory
                }
                break;
            default: // Ask user for unknown/unhandled types
                const askUserContext = { thoughtId: shortId(thought.id), thoughtType: thought.type, thoughtContent: contentStr };
                action = TermLogic.Structure("UserInteractionTool", [TermLogic.Atom("prompt"), TermLogic.Atom("FALLBACK_ASK_USER"),
                    TermLogic.Structure("context", Object.entries(askUserContext).map(([k, v]) => TermLogic.Structure("kv", [TermLogic.Atom(k), TermLogic.Atom(v)])))]);
                break;
        }

        if (llmPromptKey && targetType) {
            const prompt = context.prompts.format(llmPromptKey, llmContext);
            const resultText = await context.llm.generate(prompt, thought.id);
            context.thoughts.update(thought.id, t => ({...t, metadata: {...t.metadata, llmPrompt: prompt.substring(0, 500), llmResponse: resultText.substring(0, 500)}}));

            if (resultText && !resultText.startsWith('Error:')) {
                resultText.split('\n').map(s => s.trim().replace(/^- /, '')).filter(s => s.length > 0)
                    .forEach(resText => {
                        context.engine.addThought({
                            id: generateId(), type: targetType!, content: TermLogic.Atom(resText), belief: Belief.DEFAULT, status: Status.PENDING,
                            metadata: { rootId: thought.metadata.rootId ?? thought.id, parentId: thought.id, created: new Date().toISOString(), provenance: 'llm_fallback' }
                        });
                    });
                return { success: true, finalStatus: Status.DONE }; // Original thought done, generated next steps
            } else { return { success: false, error: `LLM fallback failed: ${resultText}`, finalStatus: Status.FAILED }; }
        } else if (action) {
            const fallbackActionResult = await ActionExecutor.execute(action, context, thought, { // Pass a dummy rule for context
                id: 'fallback_rule', pattern: TermLogic.Atom('fallback'), action: action, belief: Belief.DEFAULT, metadata: { description: `Fallback for ${thought.type}` }
            });
            // If fallback was adding Outcome to memory, now suggest next goal
            if (thought.type === Type.OUTCOME && fallbackActionResult.success && action.name === 'MemoryTool' && action.args[0]?.kind === 'Atom' && action.args[0]?.name === 'add') {
                const suggestAction = TermLogic.Structure("GoalProposalTool", [TermLogic.Atom("suggest"), thought.content]);
                await ActionExecutor.execute(suggestAction, context, thought, {
                    id: 'fallback_suggest_rule', pattern: TermLogic.Atom('fallback_suggest'), action: suggestAction, belief: Belief.DEFAULT, metadata: { description: 'Fallback goal suggestion' }
                });
                // The final status is still DONE for the original outcome thought
                return { success: true, finalStatus: Status.DONE, resultTerm: fallbackActionResult.resultTerm };
            }
            return fallbackActionResult; // Return result of the executed action
        } else { return { success: false, error: "No rule match and no fallback action defined.", finalStatus: Status.FAILED }; }
    }
};

// --- Engine ---
class Engine {
    private activeIds = new Set<string>();
    private context: ToolContext;

    constructor(
        private thoughts: ThoughtStore, private rules: RuleStore, private memory: MemoryStore,
        private llm: LLMService, private tools: ToolRegistry, private prompts: PromptRegistry
    ) {
        this.context = { thoughts: this.thoughts, rules: this.rules, memory: this.memory, llm: this.llm, tools: this.tools, prompts: this.prompts, engine: this };
    }

    addThought(thought: Thought): void { this.thoughts.add(thought); debouncedSaveState(this.thoughts, this.rules, this.memory); }
    addRule(rule: Rule): void { this.rules.add(rule); debouncedSaveState(this.thoughts, this.rules, this.memory); }

    private sampleThought(): Thought | null {
        const candidates = this.thoughts.getPending().filter(t => !this.activeIds.has(t.id));
        if (candidates.length === 0) return null;
        // Weight = Priority (higher is better) * Recency factor (newer is slightly better) * Belief Score
        const now = Date.now();
        const weights = candidates.map(t => {
            const priority = t.metadata.priority ?? 1.0;
            const ageMs = now - Date.parse(t.metadata.created ?? now.toString());
            const recencyFactor = Math.max(0.1, Math.exp(-ageMs / (1000 * 60 * 60 * 24))); // Exponential decay over 1 day
            const beliefScore = t.belief.score();
            return Math.max(0.01, priority * recencyFactor * beliefScore);
        });
        const totalWeight = weights.reduce((sum, w) => sum + w, 0);
        if (totalWeight <= 0) return candidates[Math.floor(Math.random() * candidates.length)]; // Fallback if all weights zero
        let random = Math.random() * totalWeight;
        for (let i = 0; i < candidates.length; i++) { random -= weights[i]; if (random <= 0) return candidates[i]; }
        return candidates[candidates.length - 1]; // Should not happen with proper weights
    }

    private updateThoughtState(thoughtId: string, updates: Partial<Thought> & { metadata?: Partial<Metadata> }): void {
        this.thoughts.update(thoughtId, t => ({
            ...t, ...updates, metadata: {...t.metadata, ...updates.metadata}
        }));
    }

    private async _processThought(thought: Thought): Promise<boolean> {
        try {
            this.updateThoughtState(thought.id, { status: Status.ACTIVE, metadata: { agentId: 'engine_cycle' } });
            const match = RuleMatcher.match(thought, this.rules.getAll());
            let result: ActionResult | FallbackResult;
            let appliedRule: Rule | undefined = undefined;

            if (match) {
                appliedRule = match.rule;
                const boundAction = TermLogic.substitute(match.rule.action, match.bindings);
                result = await ActionExecutor.execute(boundAction, this.context, thought, match.rule);
                // Update rule belief based on action execution success
                const ruleSuccess = result.success && result.finalStatus !== Status.FAILED; // Consider WAITING as success for the rule
                match.rule.belief.update(ruleSuccess);
                this.rules.update(match.rule.id, r => ({...r, belief: match.rule.belief})); // Use store update method
                this.updateThoughtState(thought.id, { metadata: { ruleId: match.rule.id, actionResult: result.resultTerm ? TermLogic.toString(result.resultTerm).substring(0,100) : undefined } });
            } else {
                result = await FallbackHandler.handle(thought, this.context);
                this.updateThoughtState(thought.id, { metadata: { ruleId: 'fallback', actionResult: result.resultTerm ? TermLogic.toString(result.resultTerm).substring(0,100) : undefined } });
            }

            // --- Final Status Update ---
            const currentThought = this.thoughts.get(thought.id); // Re-fetch latest state
            if (!currentThought) return true; // Thought was deleted during processing

            if (result.success) {
                const finalStatus = result.finalStatus ?? Status.DONE; // Default success to DONE
                this.updateThoughtState(thought.id, { status: finalStatus, metadata: { error: undefined, retries: undefined, waitingFor: finalStatus === Status.WAITING ? currentThought.metadata.waitingFor : undefined } });
                // currentThought.belief.update(true); // Positive update on successful step? Maybe only on reaching DONE?
            } else {
                const retries = (currentThought.metadata.retries ?? 0) + 1;
                const newStatus = retries >= MAX_RETRIES ? Status.FAILED : Status.PENDING; // Retry or fail
                const errorMsg = (result.error ?? "Unknown processing error").substring(0, 250);
                this.updateThoughtState(thought.id, { status: newStatus, metadata: { error: errorMsg, retries: retries } });
                // currentThought.belief.update(false); // Negative update on failure
                console.warn(chalk.yellow(`Thought ${shortId(currentThought.id)} failed (Attempt ${retries}): ${errorMsg}`));
            }
            // Save state after processing a thought
            debouncedSaveState(this.thoughts, this.rules, this.memory);
            // Return true indicating the thought finished its current processing step (successfully or not)
            return true;

        } catch (error: any) {
            console.error(chalk.red(`Critical error processing ${shortId(thought.id)}:`), error);
            this.updateThoughtState(thought.id, { status: Status.FAILED, metadata: { error: `Unhandled processing exception: ${error.message}`, retries: MAX_RETRIES } });
            debouncedSaveState(this.thoughts, this.rules, this.memory); // Save on critical error too
            return true; // Indicate processing ended critically
        } finally {
            this.activeIds.delete(thought.id);
            // Final check: Ensure thought isn't stuck in ACTIVE
            const finalThoughtState = this.thoughts.get(thought.id);
            if (finalThoughtState?.status === Status.ACTIVE) {
                console.warn(chalk.red(`Thought ${shortId(thought.id)} ended ACTIVE. Setting FAILED.`));
                this.updateThoughtState(thought.id, { status: Status.FAILED, metadata: { error: "Processing ended while ACTIVE.", retries: MAX_RETRIES } });
            }
        }
    }

    async processBatch(): Promise<number> {
        const promises: Promise<boolean>[] = [];
        let acquiredCount = 0;
        while (this.activeIds.size < ENGINE_MAX_CONCURRENCY && acquiredCount < ENGINE_BATCH_SIZE) {
            const thought = this.sampleThought(); if (!thought) break;
            if (this.activeIds.has(thought.id)) continue; // Should be filtered by sampleThought, but double check
            this.activeIds.add(thought.id);
            promises.push(this._processThought(thought));
            acquiredCount++;
        }
        if (promises.length === 0) return 0;
        await Promise.all(promises);
        return promises.length; // Return number of thoughts attempted
    }

    async handlePromptResponse(promptId: string, responseText: string): Promise<boolean> {
        const promptThought = this.findThoughtByIdOrPrefix(promptId, Type.USER_PROMPT); // Find the original prompt
        if (!promptThought || promptThought.metadata.promptId !== promptId) {
            console.error(chalk.red(`User prompt thought for ID ${shortId(promptId)} not found or ID mismatch.`)); return false;
        }
        const waitingThought = this.thoughts.findWaitingThought(promptId);
        if (!waitingThought) {
            console.warn(chalk.yellow(`No thought found waiting for prompt ${shortId(promptId)}. Marking prompt as done.`));
            this.updateThoughtState(promptThought.id, { status: Status.DONE, metadata: { error: "No waiting thought found upon response.", responseText: responseText.substring(0,100) } });
            return false;
        }

        const responseThought: Thought = {
            id: generateId(), type: Type.INPUT, content: TermLogic.Atom(responseText), belief: new Belief(1, 0), status: Status.PENDING,
            metadata: { rootId: waitingThought.metadata.rootId ?? waitingThought.id, parentId: waitingThought.id, created: new Date().toISOString(), responseTo: promptId, tags: ['user_response'], provenance: 'user_input' }
        };
        this.addThought(responseThought);
        this.updateThoughtState(promptThought.id, { status: Status.DONE, metadata: { responseText: responseText.substring(0,100) } });
        this.updateThoughtState(waitingThought.id, { status: Status.PENDING, belief: (()=>{ const b = waitingThought.belief; b.update(true); return b; })(), metadata: { waitingFor: undefined } });

        console.log(chalk.blue(`Response for ${shortId(promptId)} received. Thought ${shortId(waitingThought.id)} reactivated.`));
        debouncedSaveState(this.thoughts, this.rules, this.memory); // Save after response handling
        return true;
    }

    // Helper to find thought more robustly
    findThoughtByIdOrPrefix(idOrPrefix: string, expectedType?: Type): Thought | undefined {
        const thought = this.thoughts.get(idOrPrefix) ?? this.thoughts.findThought(idOrPrefix);
        return (thought && (!expectedType || thought.type === expectedType)) ? thought : undefined;
    }
}

// --- Persistence ---
async function saveState(thoughts: ThoughtStore, rules: RuleStore, memory: MemoryStore): Promise<void> {
    try {
        await fs.mkdir(DATA_DIR, {recursive: true});
        const state: AppState = {thoughts: thoughts.toJSON(), rules: rules.toJSON()};
        await fs.writeFile(STATE_FILE, JSON.stringify(state, null, 2));
        if (memory.isReady) await memory.save(); // Only save memory if ready
        // console.log(chalk.gray(`State saved: ${thoughts.count()} thoughts, ${rules.count()} rules.`));
    } catch (error) { console.error(chalk.red('Error saving state:'), error); }
}

const debouncedSaveState = debounce(saveState, SAVE_DEBOUNCE);

async function loadState(thoughts: ThoughtStore, rules: RuleStore, memory: MemoryStore): Promise<void> {
    try {
        await fs.mkdir(DATA_DIR, {recursive: true});
        await memory.initialize(); // Initialize memory first
        if (!memory.isReady) console.error(chalk.red("Memory store failed to initialize. State loading might be incomplete or fail."));

        if (fsSync.existsSync(STATE_FILE)) {
            const data = await fs.readFile(STATE_FILE, 'utf-8');
            const state: AppState = safeJsonParse(data, {thoughts: {}, rules: {}});
            thoughts.loadJSON(state.thoughts); // Load thoughts AFTER memory init, before rules
            rules.loadJSON(state.rules);
            console.log(chalk.blue(`Loaded ${thoughts.count()} thoughts, ${rules.count()} rules. Vector store: ${memory.isReady ? 'Ready' : 'Failed'}.`));
        } else {
            console.log(chalk.yellow(`State file ${STATE_FILE} not found. Starting fresh.`));
        }
        // Reset change tracking after load
        thoughts.getDelta(); rules.getDelta();
    } catch (error) {
        console.error(chalk.red('Error loading state:'), error);
        // Attempt recovery if memory failed before
        if (!memory.isReady) { console.log(chalk.yellow("Attempting memory store initialization again...")); await memory.initialize(); }
    }
}

// --- WebSocket API Server ---
class WsApiServer {
    private wss: WebSocketServer | null = null;
    private clients = new Map<WebSocket, { ip: string }>();
    private commandHandlers = new Map<string, (payload: any, ws: WebSocket) => Promise<any | void>>();

    constructor(private port: number, private thoughts: ThoughtStore, private rules: RuleStore, private memory: MemoryStore, private engine: Engine, private tools: ToolRegistry, private systemControl: SystemControl) {
        this.registerCommandHandlers();
        // Subscribe to store changes AFTER initial load
        this.thoughts.addChangeListener(() => this.broadcastThoughtChanges());
        this.rules.addChangeListener(() => this.broadcastRuleChanges());
    }

    start(): void {
        if (this.wss) return;
        this.wss = new WebSocketServer({port: this.port});
        this.wss.on('listening', () => console.log(chalk.green(`🚀 FlowMind WebSocket API listening on ws://localhost:${this.port}`)));
        this.wss.on('connection', (ws, req) => this.handleConnection(ws, req));
        this.wss.on('error', (error) => console.error(chalk.red('WebSocket Server Error:'), error));
    }

    stop(): void {
        if (!this.wss) return;
        console.log(chalk.yellow('Stopping WebSocket API server...'));
        this.wss?.clients.forEach(ws => ws.close(1000, 'Server shutting down'));
        this.wss?.close(() => console.log(chalk.yellow('WebSocket server closed.')));
        this.clients.clear();
        this.wss = null;
    }

    private handleConnection(ws: WebSocket, req: IncomingMessage): void {
        const ip = req.socket.remoteAddress || req.headers['x-forwarded-for']?.toString() || 'unknown';
        console.log(chalk.blue(`Client connected: ${ip}`));
        this.clients.set(ws, {ip});
        ws.on('message', (message) => this.handleMessage(ws, message as Buffer).catch(err => console.error(chalk.red("Error handling message:"), err)));
        ws.on('close', (code, reason) => this.handleDisconnection(ws, code, reason.toString()));
        ws.on('error', (error) => { console.error(chalk.red(`WebSocket error from ${ip}:`), error); this.handleDisconnection(ws, 1011, error.message); });
        this.sendSnapshot(ws);
    }

    private handleDisconnection(ws: WebSocket, code: number, reason: string): void {
        const clientInfo = this.clients.get(ws);
        console.log(chalk.yellow(`Client disconnected: ${clientInfo?.ip || 'unknown'} (Code: ${code}, Reason: ${reason || 'N/A'})`));
        this.clients.delete(ws);
    }

    private async handleMessage(ws: WebSocket, message: Buffer): Promise<void> {
        let request: { command: string; payload?: any; requestId?: string };
        try {
            request = JSON.parse(message.toString());
            if (typeof request.command !== 'string') throw new Error("Invalid command format: missing 'command' string.");
        } catch (e: any) {
            this.send(ws, { type: 'error', payload: `Invalid JSON message: ${e.message}`, requestId: (request as any)?.requestId }); return;
        }
        const handler = this.commandHandlers.get(request.command);
        if (handler) {
            try {
                const result = await handler(request.payload ?? {}, ws);
                this.send(ws, {type: 'response', payload: result ?? { message: 'OK' }, success: true, requestId: request.requestId});
                // Trigger save explicitly for state-changing operations, debounced save handles background changes
                if (!['get_thoughts', 'get_rules', 'get_status', 'info', 'search', 'help', 'tools', 'ping'].includes(request.command)) {
                    await debouncedSaveState.flush(); // Ensure save happens soon after direct commands
                }
            } catch (error: any) {
                console.error(chalk.red(`Error executing command "${request.command}" from ${this.clients.get(ws)?.ip}: ${error.message}`));
                this.send(ws, { type: 'response', payload: error.message || 'Command execution failed.', success: false, requestId: request.requestId });
            }
        } else { this.send(ws, { type: 'error', payload: `Unknown command: ${request.command}`, requestId: request.requestId }); }
    }

    private send(ws: WebSocket, data: any): void {
        if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(data));
    }

    broadcast(data: any): void {
        if (this.clients.size === 0) return;
        const message = JSON.stringify(data);
        this.clients.forEach((_, ws) => { if (ws.readyState === WebSocket.OPEN) ws.send(message); });
    }

    private broadcastThoughtChanges(): void { const delta = this.thoughts.getDelta(); if (delta.changed.length > 0 || delta.deleted.length > 0) this.broadcast({ type: 'thoughts_delta', payload: { changed: delta.changed.map(t => ({...t, belief: t.belief.toJSON()})), deleted: delta.deleted } }); }
    private broadcastRuleChanges(): void { const delta = this.rules.getDelta(); if (delta.changed.length > 0 || delta.deleted.length > 0) this.broadcast({ type: 'rules_delta', payload: { changed: delta.changed.map(r => ({...r, belief: r.belief.toJSON()})), deleted: delta.deleted } }); }
    public broadcastStatusUpdate(): void { this.broadcast({ type: 'status_update', payload: this.systemControl.getStatus() }); }

    private sendSnapshot(ws: WebSocket): void {
        this.send(ws, {
            type: 'snapshot', payload: {
                thoughts: this.thoughts.getAll().map(t => ({...t, belief: t.belief.toJSON()})),
                rules: this.rules.getAll().map(r => ({...r, belief: r.belief.toJSON()})),
                status: this.systemControl.getStatus()
            }
        });
    }

    private registerCommandHandlers(): void {
        const handlers: { [key: string]: (payload: any, ws: WebSocket) => Promise<any | void> } = {
            add: async (p) => { if (!p.text) throw new Error("Missing 'text'"); this.engine.addThought({ id: generateId(), type: Type.INPUT, content: TermLogic.fromString(p.text), belief: new Belief(1,0), status: Status.PENDING, metadata: { created: new Date().toISOString(), tags: ['user_added'], provenance: 'ws_api', rootId: generateId()} }); },
            respond: async (p) => { if (!p.promptId || !p.text) throw new Error("Missing 'promptId' or 'text'"); if (!await this.engine.handlePromptResponse(p.promptId, p.text)) throw new Error(`Failed to process response for prompt ${shortId(p.promptId)}.`); },
            run: async () => { this.systemControl.startProcessing(); return { message: "Processing started."}; },
            pause: async () => { this.systemControl.pauseProcessing(); return { message: "Processing paused."}; },
            step: async () => { const count = await this.systemControl.stepProcessing(); return { processed: count > 0, count, message: count > 0 ? `Step processed ${count} thought(s).` : "Nothing to step." }; },
            save: async () => { await debouncedSaveState.flush(); await saveState(this.thoughts, this.rules, this.memory); return { message: "State saved."}; },
            quit: async () => { setTimeout(() => this.systemControl.shutdown(), 50); return { message: "Shutdown initiated."}; }, // Delay slightly to allow response send
            get_status: async () => this.systemControl.getStatus(),
            info: async (p) => { if (!p.idPrefix) throw new Error("Missing 'idPrefix'"); const t = this.thoughts.findThought(p.idPrefix); if (t) return { type: 'thought', data: {...t, belief: t.belief.toJSON()} }; const r = this.rules.findRule(p.idPrefix); if (r) return { type: 'rule', data: {...r, belief: r.belief.toJSON()} }; throw new Error(`ID prefix "${p.idPrefix}" not found.`); },
            get_thoughts: async () => this.thoughts.getAll().map(t => ({...t, belief: t.belief.toJSON()})),
            get_rules: async () => this.rules.getAll().map(r => ({...r, belief: r.belief.toJSON()})),
            tools: async () => this.tools.list().map(t => ({name: t.name, description: t.description})),
            tag: async (p) => { if (!p.idPrefix || !p.tag) throw new Error("Missing 'idPrefix' or 'tag'"); const t = this.thoughts.findThought(p.idPrefix); if (!t) throw new Error(`Thought prefix "${p.idPrefix}" not found.`); const updated = this.thoughts.update(t.id, item => ({...item, metadata: {...item.metadata, tags: [...new Set([...(item.metadata.tags || []), p.tag])]}})); if (!updated) throw new Error("Failed to update thought"); return {id: t.id, tags: this.thoughts.get(t.id)?.metadata.tags}; },
            untag: async (p) => { if (!p.idPrefix || !p.tag) throw new Error("Missing 'idPrefix' or 'tag'"); const t = this.thoughts.findThought(p.idPrefix); if (!t?.metadata.tags) throw new Error(`Thought prefix "${p.idPrefix}" not found or has no tags.`); const updated = this.thoughts.update(t.id, item => ({...item, metadata: {...item.metadata, tags: item.metadata.tags?.filter(tag => tag !== p.tag)}})); if (!updated) throw new Error("Failed to update thought"); return {id: t.id, tags: this.thoughts.get(t.id)?.metadata.tags}; },
            search: async (p) => { if (!p.query) throw new Error("Missing 'query'"); return this.memory.search(p.query, typeof p.k === 'number' ? p.k : 5); },
            delete: async (p) => { if (!p.idPrefix) throw new Error("Missing 'idPrefix'"); const t = this.thoughts.findThought(p.idPrefix); if (t) { if (!this.thoughts.delete(t.id)) throw new Error(`Failed to delete thought ${shortId(t.id)}.`); return {id: t.id, message: `Thought ${shortId(t.id)} deleted.`}; } const r = this.rules.findRule(p.idPrefix); if (r) { if (!this.rules.delete(r.id)) throw new Error(`Failed to delete rule ${shortId(r.id)}.`); return {id: r.id, message: `Rule ${shortId(r.id)} deleted.`}; } throw new Error(`Item with prefix "${p.idPrefix}" not found.`); },
            help: async () => ({ description: "Available commands. Use 'info <idPrefix>' for details.", commands: [...this.commandHandlers.keys()].sort() }),
            ping: async () => "pong",
        };
        for (const [command, handler] of Object.entries(handlers)) this.commandHandlers.set(command, handler.bind(this));
    }
}

// --- Main System Orchestrator ---
class FlowMindSystem {
    private thoughts = new ThoughtStore(); private rules = new RuleStore();
    private llm = new LLMService();
    private memory = new MemoryStore(this.llm.embeddings, VECTOR_STORE_DIR);
    private tools = new ToolRegistry(); private prompts = new PromptRegistry();
    private engine = new Engine(this.thoughts, this.rules, this.memory, this.llm, this.tools, this.prompts);
    private apiServer: WsApiServer;
    private isRunning = false; private workerIntervalId: NodeJS.Timeout | null = null;
    private systemControl: SystemControl;
    private isShuttingDown = false;

    constructor() {
        this.registerCoreTools();
        this.systemControl = {
            startProcessing: this.startProcessingLoop.bind(this), pauseProcessing: this.pauseProcessingLoop.bind(this),
            stepProcessing: () => this.engine.processBatch(), shutdown: this.shutdown.bind(this),
            getStatus: () => ({isRunning: this.isRunning, activeCount: this.thoughts.getActiveCount(), pendingCount: this.thoughts.getPending().length})
        };
        this.apiServer = new WsApiServer(WS_PORT, this.thoughts, this.rules, this.memory, this.engine, this.tools, this.systemControl);
    }

    private registerCoreTools(): void { [new LLMTool(), new MemoryTool(), new UserInteractionTool(), new GoalProposalTool(), new CoreTool()].forEach(t => this.tools.register(t)); }

    private bootstrapRules(): void {
        if (this.rules.count() > 0) return;
        console.log(chalk.blue("Bootstrapping default rules..."));
        const createRule = (desc: string, pattern: Term, action: Term, priority: number = 0, tags: string[] = []): Rule => ({ id: generateId(), pattern, action, belief: Belief.DEFAULT, metadata: { description: desc, provenance: 'bootstrap', created: new Date().toISOString(), priority, tags } });
        const rulesToAdd = [
            // Fallback handler now manages basic INPUT->GOAL->STRAT->OUTCOME flow.
            // Rules focus on specific triggers or refinements.
            createRule("Outcome -> Suggest Next Goal (after memory add)", Type.OUTCOME, TermLogic.Structure("GoalProposalTool", [TermLogic.Atom("suggest"), TermLogic.Variable("Content")]), 5, ['outcome_processing']), // Fallback adds memory first, this runs after if outcome stays PENDING/is re-eval'd
            createRule("Explicit 'search memory' Input", TermLogic.Structure(Type.INPUT, [TermLogic.Structure("search", [TermLogic.Variable("Query")])]), TermLogic.Structure("MemoryTool", [TermLogic.Atom("search"), TermLogic.Variable("Query")]), 15, ['command']),
            createRule("Explicit 'add memory' Input", TermLogic.Structure(Type.INPUT, [TermLogic.Structure("remember", [TermLogic.Variable("Content")])]), TermLogic.Structure("MemoryTool", [TermLogic.Atom("add"), TermLogic.Variable("Content")]), 15, ['command']),
            createRule("Mark thought 'done' if content is 'ok'", TermLogic.Atom("ok"), TermLogic.Structure("CoreTool", [TermLogic.Atom("set_status"), TermLogic.Atom("self"), TermLogic.Atom(Status.DONE)]), 1),
            createRule("Mark thought 'done' if content starts 'ok:'", TermLogic.Structure("Atom", [TermLogic.Variable("Msg", {startsWith: 'ok:'})]), TermLogic.Structure("CoreTool", [TermLogic.Atom("set_status"), TermLogic.Atom("self"), TermLogic.Atom(Status.DONE)]), 1), // Example using hypothetical extended pattern matching
            // Rule to reflect on system state periodically or when triggered
            createRule("Trigger System Reflection", TermLogic.Structure(Type.SYSTEM, [TermLogic.Atom("reflect")]), TermLogic.Structure("LLMTool", [TermLogic.Atom("generate"), TermLogic.Atom("SYSTEM_REFLECTION"), TermLogic.Structure("context", [])]), 20, ['system_maintenance']),
        ];
        rulesToAdd.forEach(rule => this.rules.add(rule));
        console.log(chalk.green(`${this.rules.count()} default rules added.`));
    }

    async initialize(): Promise<void> {
        console.log(chalk.cyan('Initializing FlowMind System...'));
        await fs.mkdir(DATA_DIR, {recursive: true}); // Ensure data dir exists early
        await loadState(this.thoughts, this.rules, this.memory);
        this.bootstrapRules();
        this.apiServer.start();
        // Setup auto-save listener AFTER load/bootstrap
        const stateChangeListener = () => debouncedSaveState(this.thoughts, this.rules, this.memory);
        this.thoughts.addChangeListener(stateChangeListener);
        this.rules.addChangeListener(stateChangeListener);
        console.log(chalk.green('FlowMind Initialized.') + chalk.yellow(" Paused. Use API 'run' command to start processing."));
    }

    startProcessingLoop(): void {
        if (this.isRunning || this.isShuttingDown) return;
        console.log(chalk.green('Starting processing loop...')); this.isRunning = true; this.apiServer.broadcastStatusUpdate();
        this.workerIntervalId = setInterval(async () => {
            if (!this.isRunning || this.isShuttingDown) return;
            try { await this.engine.processBatch(); /* Save is debounced */ }
            catch (error) { console.error(chalk.red('Error in processing loop:'), error); }
        }, WORKER_INTERVAL);
    }

    pauseProcessingLoop(isShutdown: boolean = false): void {
        if (!this.isRunning || (this.isShuttingDown && !isShutdown)) return; // Prevent pausing again if already shutting down
        console.log(chalk.yellow(isShutdown ? 'Halting processing loop for shutdown...' : 'Pausing processing loop...'));
        this.isRunning = false; if (!isShutdown) this.apiServer.broadcastStatusUpdate();
        if (this.workerIntervalId) { clearInterval(this.workerIntervalId); this.workerIntervalId = null; }
        console.log(chalk.gray("Flushing state save queue..."));
        debouncedSaveState.flush(); // Ensure pending saves are written
    }

    async shutdown(): Promise<void> {
        if (this.isShuttingDown) return;
        this.isShuttingDown = true;
        console.log(chalk.cyan('\nShutting down FlowMind System...'));
        this.pauseProcessingLoop(true); // Pause loop and flush save queue
        this.apiServer.stop();
        await sleep(200); // Allow time for server close
        console.log(chalk.cyan('Ensuring final state save...'));
        debouncedSaveState.cancel(); // Cancel any scheduled debounce
        await saveState(this.thoughts, this.rules, this.memory); // Perform final synchronous save
        console.log(chalk.green('FlowMind shutdown complete. Goodbye!'));
        await sleep(100); // Short delay before exit
        process.exit(0);
    }
}

// --- REPL Client Example (Improved) ---
async function startReplClient(serverUrl: string) {
    console.log(chalk.cyan(`Connecting to FlowMind server at ${serverUrl}...`));
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout, prompt: chalk.blueBright('FlowMind> ') });
    let ws: WebSocket | null = null;
    let requestCounter = 0;
    const pendingRequests = new Map<string, { resolve: (value: any) => void, reject: (reason?: any) => void, command: string }>();
    const reconnectDelay = 5000; let reconnectTimeout: NodeJS.Timeout | null = null; let isExiting = false;

    const printAsync = (message: string) => {
        process.stdout.clearLine(0); process.stdout.cursorTo(0); // Clear current prompt line
        console.log(message); // Print the async message
        rl.prompt(true); // Redraw the prompt with current input buffer
    };

    const connect = () => {
        if (reconnectTimeout) { clearTimeout(reconnectTimeout); reconnectTimeout = null; }
        if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) || isExiting) return;
        printAsync(chalk.yellow(`Attempting connection to ${serverUrl}...`));
        ws = new WebSocket(serverUrl);
        ws.on('open', () => { printAsync(chalk.green('Connected! Type "help" or a command.')); });
        ws.on('message', (data) => {
            const message = safeJsonParse(data.toString(), null); if (!message) { printAsync(chalk.yellow('Received non-JSON message:', data.toString().substring(0, 100))); return; }
            const {type, payload, success, requestId, ...rest} = message;
            if (requestId && pendingRequests.has(requestId)) {
                const reqInfo = pendingRequests.get(requestId)!;
                if (type === 'response') { success ? reqInfo.resolve(payload) : reqInfo.reject(new Error(payload?.message || payload || `Command '${reqInfo.command}' failed.`)); }
                else if (type === 'error') { reqInfo.reject(new Error(payload || `Server error for '${reqInfo.command}'.`)); }
                pendingRequests.delete(requestId);
                // Don't redraw prompt here, let the command handler do it after printing result
            } else if (type === 'error') printAsync(chalk.red(`\nServer Error: ${payload?.message || payload}`));
            else if (type === 'snapshot') printAsync(chalk.magenta(`\n--- Snapshot: ${payload.thoughts?.length ?? 0}T, ${payload.rules?.length ?? 0}R, ${payload.status?.isRunning ? 'RUNNING' : 'PAUSED'} ---`));
            else if (type === 'thoughts_delta' || type === 'rules_delta') {
                if (!payload || (payload.changed.length === 0 && payload.deleted.length === 0)) return;
                const typeName = type === 'thoughts_delta' ? 'Thoughts' : 'Rules';
                const changed = payload.changed.map((item: any) => `${shortId(item.id)}${item.status ? `[${item.status.substring(0,1)}]` : ''}`).join(',');
                const deleted = payload.deleted.map(shortId).join(',');
                let msg = `\n${chalk.gray(typeName)} Update:`;
                if (changed) msg += chalk.green(` Changed(${payload.changed.length}): ${changed}`);
                if (deleted) msg += chalk.red(` Deleted(${payload.deleted.length}): ${deleted}`);
                printAsync(msg);
            } else if (type === 'status_update') printAsync(chalk.magenta(`\nSystem Status: ${payload.isRunning ? 'RUNNING' : 'PAUSED'} (Active: ${payload.activeCount}, Pending: ${payload.pendingCount})`));
            else printAsync(chalk.gray(`\nServer Msg [${type || 'unkn'}]: ${JSON.stringify(payload ?? rest).substring(0,150)}`));
        });
        ws.on('close', (code) => {
            ws = null; pendingRequests.forEach(p => p.reject(new Error("Disconnected"))); pendingRequests.clear();
            if (!isExiting) { printAsync(chalk.red(`\nDisconnected (Code: ${code}). Reconnecting in ${reconnectDelay/1000}s...`)); if (!reconnectTimeout) reconnectTimeout = setTimeout(connect, reconnectDelay); }
        });
        ws.on('error', (error) => {
            printAsync(chalk.red(`\nConnection Error: ${error.message}`));
            ws?.close(); ws = null;
            if (!isExiting && !reconnectTimeout) reconnectTimeout = setTimeout(connect, reconnectDelay);
        });
    };

    const sendCommand = (command: string, payload: any): Promise<any> => {
        return new Promise((resolve, reject) => {
            if (!ws || ws.readyState !== WebSocket.OPEN) { reject(new Error("Not connected.")); rl.prompt(); return; }
            const requestId = `req-${requestCounter++}`; pendingRequests.set(requestId, {resolve, reject, command});
            ws.send(JSON.stringify({command, payload, requestId}));
            setTimeout(() => { if (pendingRequests.has(requestId)) { reject(new Error(`Request ${requestId} (${command}) timed out`)); pendingRequests.delete(requestId); rl.prompt(); } }, 15000);
        });
    };

    rl.on('line', async (line) => {
        const trimmed = line.trim(); if (!trimmed) { rl.prompt(); return; }
        if (trimmed === 'quit' || trimmed === 'exit') { rl.close(); return; }
        const parts = trimmed.match(/(?:[^\s"']+|"[^"]*"|'[^']*')+/g) ?? []; // Handle quotes
        const command = parts[0]?.toLowerCase();
        const args = parts.slice(1).map(arg => (arg.startsWith('"') && arg.endsWith('"')) || (arg.startsWith("'") && arg.endsWith("'")) ? arg.slice(1, -1) : arg);
        if (!command) { rl.prompt(); return; }
        let payload: any = {}; let valid = true;

        try { // Argument Parsing Logic (Simplified)
            switch (command) {
                case 'add': if (args.length < 1) valid = false; else payload = {text: args.join(' ')}; break;
                case 'respond': if (args.length < 2) valid = false; else payload = { promptId: args[0], text: args.slice(1).join(' ') }; break;
                case 'info': case 'delete': if (args.length !== 1) valid = false; else payload = {idPrefix: args[0]}; break;
                case 'tag': case 'untag': if (args.length !== 2) valid = false; else payload = {idPrefix: args[0], tag: args[1]}; break;
                case 'search': if (args.length < 1) valid = false; else payload = {query: args[0], k: parseInt(args[1] ?? '5', 10) || 5}; break; // Basic k parsing
                case 'get_thoughts': case 'get_rules': case 'run': case 'pause': case 'step': case 'save': case 'get_status': case 'tools': case 'help': case 'ping': case 'quit': if (args.length > 0) valid = false; else payload = {}; break;
                default: console.log(chalk.yellow(`Unknown command: ${command}. Type 'help'.`)); valid = false; break;
            }
            if (!valid) { if (command !== 'help') console.log(chalk.yellow(`Invalid arguments for command: ${command}. Type 'help'.`)); }
            else {
                const result = await sendCommand(command, payload);
                if (command === 'help') {
                    console.log(chalk.cyan(result.description)); console.log(chalk.blueBright("Commands:"), result.commands.join(', '));
                } else if (result !== null && typeof result === 'object' && result.message === 'OK' && Object.keys(result).length === 1) {
                    console.log(chalk.green('OK')); // Simplified OK response
                } else if (result !== null && result !== undefined) {
                    console.log(chalk.cyan(`Result:`), JSON.stringify(result, null, 2));
                } else { console.log(chalk.green('OK')); } // Default OK if result is null/undefined
            }
        } catch (error: any) { console.error(chalk.red(`Error: ${error.message}`)); }
        finally { rl.prompt(); }
    });

    rl.on('close', () => {
        isExiting = true; printAsync(chalk.yellow('\nREPL Client exiting...'));
        if (reconnectTimeout) clearTimeout(reconnectTimeout);
        ws?.close(1000, 'Client exited'); ws = null;
        setTimeout(() => process.exit(0), 200); // Allow time for close message
    });
    connect(); // Initial connection attempt
}

// --- Main Execution ---
async function main() {
    const mode = process.argv[2] || 'server';
    if (mode === 'server') {
        let system: FlowMindSystem | null = null;
        const handleShutdownSignal = async (signal: string) => { console.log(chalk.yellow(`\n${signal} received.`)); if (system) await system.shutdown(); else process.exit(0); };
        const handleCriticalError = async (context: string, error: any, origin?: any) => {
            console.error(chalk.red.bold(`\n--- ${context.toUpperCase()} ---`)); console.error(error); if (origin) console.error(`Origin: ${origin}`); console.error(chalk.red.bold('--------------------------'));
            if (system) { try { await system.shutdown(); } catch { process.exit(1); } } else process.exit(1);
        };
        process.on('SIGINT', () => handleShutdownSignal('SIGINT')); process.on('SIGTERM', () => handleShutdownSignal('SIGTERM'));
        process.on('uncaughtException', (error, origin) => handleCriticalError('Uncaught Exception', error, origin));
        process.on('unhandledRejection', (reason, promise) => handleCriticalError('Unhandled Rejection', reason, promise));
        try { system = new FlowMindSystem(); await system.initialize(); }
        catch (error) { console.error(chalk.red.bold("Critical initialization error:"), error); process.exit(1); }
    } else if (mode === 'client') {
        const serverUrl = process.argv[3] || `ws://localhost:${WS_PORT}`; await startReplClient(serverUrl);
    } else { console.error(chalk.red(`Invalid mode: ${mode}. Use 'server' or 'client [url]'`)); process.exit(1); }
}

main().catch(error => { console.error(chalk.red.bold("Unhandled error in main execution:"), error); process.exit(1); });