import * as fs from 'fs/promises';
import * as fsSync from 'fs';
import * as path from 'path';
import * as os from 'os';
import * as readline from 'readline';
import { v4 as uuidv4 } from 'uuid';
import chalk from 'chalk';
import { ChatOllama } from "@langchain/community/chat_models/ollama";
import { OllamaEmbeddings } from "@langchain/community/embeddings/ollama";
import { BaseChatModel } from "@langchain/core/language_models/chat_models";
import { Embeddings } from "@langchain/core/embeddings";
import { HumanMessage } from "@langchain/core/messages";
import { StringOutputParser } from "@langchain/core/output_parsers";
import { Document } from "@langchain/core/documents";
import { FaissStore } from "@langchain/community/vectorstores/faiss";

const DATA_DIR = path.join(os.homedir(), '.flowmind-console');
const STATE_FILE = path.join(DATA_DIR, 'flowmind_state.json');
const VECTOR_STORE_DIR = path.join(DATA_DIR, 'vector-store');
const OLLAMA_BASE_URL = process.env.OLLAMA_HOST || "http://localhost:11434";
const OLLAMA_MODEL = 'hf.co/DevQuasar/Orion-zhen.phi-4-abliterated-GGUF:Q3_K_M';
const OLLAMA_EMBEDDING_MODEL = OLLAMA_MODEL;
const WORKER_INTERVAL = 2000;
const UI_REFRESH_INTERVAL = 1000;
const SAVE_DEBOUNCE = 5000;
const MAX_RETRIES = 2;
const DEFAULT_BELIEF_POS = 1.0;
const DEFAULT_BELIEF_NEG = 1.0;
const SHORT_ID_LEN = 6;

enum Status { PENDING = 'PENyDING', ACTIVE = 'ACTIVE', WAITING = 'WAITING', DONE = 'DONE', FAILED = 'FAILED' }
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
    uiContext?: string | { promptText?: string, promptId?: string };
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
    embedding: number[];
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

function generateId(): string { return uuidv4(); }
function shortId(id: string | undefined): string { return id ? id.substring(0, SHORT_ID_LEN) : '------'; }

function debounce<T extends (...args: any[]) => any>(func: T, wait: number): T & { cancel: () => void } {
    let timeout: NodeJS.Timeout | null = null;
    const debounced = (...args: Parameters<T>): void => {
        const later = () => { timeout = null; func(...args); };
        if (timeout) clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
    debounced.cancel = () => { if (timeout) { clearTimeout(timeout); timeout = null; } };
    return debounced as T & { cancel: () => void };
}

function safeJsonParse<T>(json: string | null | undefined, defaultValue: T): T {
    if (!json) return defaultValue;
    try { return JSON.parse(json); } catch { return defaultValue; }
}

function sleep(ms: number): Promise<void> { return new Promise(resolve => setTimeout(resolve, ms)); }

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
    export const List = (elements: Term[]): ListTerm => ({ kind: 'ListTerm', elements });

    export function format(term: Term | null | undefined): string {
        if (!term) return chalk.grey('null');
        switch (term.kind) {
            case 'Atom': return chalk.green(term.name);
            case 'Variable': return chalk.cyan(`?${term.name}`);
            case 'Structure': return `${chalk.yellow(term.name)}(${term.args.map(format).join(', ')})`;
            case 'ListTerm': return `[${term.elements.map(format).join(', ')}]`;
            default: return chalk.red('invalid_term');
        }
    }

    export function toString(term: Term): string {
        switch (term.kind) {
            case 'Atom': return term.name;
            case 'Variable': return `?${term.name}`;
            case 'Structure': return `${term.name}: ${term.args.map(toString).join('; ')}`;
            case 'ListTerm': return term.elements.map(toString).join(', ');
            default: return '';
        }
    }

    export function fromString(input: string): Term {
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
            if (t1.name === (t2 as Variable).name && t1.kind === t2.kind) return bindings;
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
            case 'Atom': return t1.name === (t2 as Atom).name ? bindings : null;
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
        return null;
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
}

abstract class BaseStore<T extends { id: string }> {
    protected items = new Map<string, T>();
    protected listeners: (() => void)[] = [];

    add(item: T): void { this.items.set(item.id, item); this.notifyChange(); }
    get(id: string): T | undefined { return this.items.get(id); }

    update(item: T): void {
        if (this.items.has(item.id)) {
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
    findItemByPrefix(prefix: string): T | undefined {
        if (this.items.has(prefix)) return this.items.get(prefix);
        for (const item of this.items.values()) {
            if (item.id.startsWith(prefix)) return item;
        }
        return undefined;
    }

    addChangeListener(listener: () => void): void { this.listeners.push(listener); }
    removeChangeListener(listener: () => void): void { this.listeners = this.listeners.filter(l => l !== listener); }
    protected notifyChange(): void { this.listeners.forEach(listener => listener()); }

    abstract toJSON(): Record<string, any>;
    abstract loadJSON(data: Record<string, any>): void;
}

class ThoughtStore extends BaseStore<Thought> {
    getPending(): Thought[] { return this.getAll().filter(t => t.status === Status.PENDING); }
    getAllByRootId(rootId: string): Thought[] {
        return this.getAll()
            .filter(t => t.metadata.rootId === rootId || t.id === rootId)
            .sort((a, b) => Date.parse(a.metadata.created ?? '0') - Date.parse(b.metadata.created ?? '0'));
    }
    searchByTag(tag: string): Thought[] { return this.getAll().filter(t => t.metadata.tags?.includes(tag)); }
    findThought(idPrefix: string): Thought | undefined { return this.findItemByPrefix(idPrefix); }

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
                    const dummyDoc = new Document({ pageContent: "Init", metadata: { sourceId: "init", type: "SYSTEM", created: new Date().toISOString() } });
                    this.vectorStore = await FaissStore.fromDocuments([dummyDoc], this.embeddings);
                    await this.vectorStore.save(this.storePath);
                    this.isReady = true;
                    console.log(chalk.green(`New vector store created at ${this.storePath}`));
                } catch (initError) {
                    console.error(chalk.red('Failed to initialize new vector store:'), initError);
                }
            } else {
                console.error(chalk.red('Failed to load vector store:'), error);
            }
        }
    }

    async add(entry: Omit<MemoryEntry, 'embedding' | 'score'>): Promise<void> {
        if (!this.vectorStore || !this.isReady) {
            console.warn(chalk.yellow('MemoryStore not ready, cannot add entry.')); return;
        }
        const doc = new Document({ pageContent: entry.content, metadata: { ...entry.metadata, id: entry.id } });
        try {
            await this.vectorStore.addDocuments([doc]);
            await this.save();
        } catch (error) { console.error(chalk.red('Failed to add document to vector store:'), error); }
    }

    async search(query: string, k: number = 5): Promise<MemoryEntry[]> {
        if (!this.vectorStore || !this.isReady) {
            console.warn(chalk.yellow('MemoryStore not ready, cannot search.')); return [];
        }
        try {
            const results = await this.vectorStore.similaritySearchWithScore(query, k);
            return results.map(([doc, score]) => ({
                id: doc.metadata.id || generateId(),
                embedding: [],
                content: doc.pageContent,
                metadata: doc.metadata as MemoryEntry['metadata'],
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

class LLMTool implements Tool {
    name = "LLMTool";
    description = "Interacts with the LLM for generation.";
    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        const promptTerm = action.args[1];
        if (operation !== 'generate' || !promptTerm) return TermLogic.Atom("error:LLMTool_invalid_params");

        const response = await context.llm.generate(TermLogic.toString(promptTerm));
        return response.startsWith('Error:')
            ? TermLogic.Atom(`error:LLMTool_generation_failed:${response}`)
            : TermLogic.Atom(response);
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
            await context.memory.add({
                id: generateId(),
                content: TermLogic.toString(contentTerm),
                metadata: { created: new Date().toISOString(), type: trigger.type, sourceId: trigger.id }
            });
            trigger.metadata.embedded = new Date().toISOString();
            context.thoughts.update(trigger);
            return TermLogic.Atom("ok:memory_added");
        } else if (operation === 'search') {
            const queryTerm = action.args[1];
            const kTerm = action.args[2];
            if (!queryTerm) return TermLogic.Atom("error:MemoryTool_missing_search_query");
            const queryStr = TermLogic.toString(queryTerm);
            const k = (kTerm?.kind === 'Atom' && !isNaN(parseInt(kTerm.name))) ? parseInt(kTerm.name, 10) : 3;
            const results = await context.memory.search(queryStr, k);
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
        context.engine.addThought({
            id: generateId(), type: Type.USER_PROMPT, content: TermLogic.Atom(promptText),
            belief: Belief.DEFAULT, status: Status.PENDING,
            metadata: {
                rootId: trigger.metadata.rootId ?? trigger.id, parentId: trigger.id,
                created: new Date().toISOString(), uiContext: { promptText, promptId },
                provenance: this.name,
            }
        });
        trigger.status = Status.WAITING;
        trigger.metadata.waitingFor = promptId;
        context.thoughts.update(trigger);
        return TermLogic.Atom(`ok:prompt_requested:${promptId}`);
    }
}

class GoalProposalTool implements Tool {
    name = "GoalProposalTool";
    description = "Suggests new goals based on context and memory.";
    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (operation !== 'suggest') return TermLogic.Atom("error:GoalTool_unsupported_operation");

        const contextTerm = action.args[1] ?? trigger.content;
        const contextStr = TermLogic.toString(contextTerm);
        const memoryResults = await context.memory.search(`Relevant past goals or outcomes related to: ${contextStr}`, 3);
        const memoryContext = memoryResults.length > 0 ? `\nRelated past activities:\n - ${memoryResults.map(r => r.content).join("\n - ")}` : "";
        const prompt = `Based on the current context "${contextStr}"${memoryContext}\n\nSuggest ONE concise, actionable next goal or task. Output only the suggested goal text.`;
        const suggestionText = await context.llm.generate(prompt);

        if (suggestionText && !suggestionText.startsWith('Error:')) {
            const suggestionThought: Thought = {
                id: generateId(), type: Type.INPUT, content: TermLogic.Atom(suggestionText),
                belief: new Belief(1, 0), status: Status.PENDING,
                metadata: {
                    rootId: trigger.metadata.rootId ?? trigger.id, parentId: trigger.id,
                    created: new Date().toISOString(), tags: ['suggested_goal'], provenance: this.name
                }
            };
            context.engine.addThought(suggestionThought);
            return TermLogic.Atom(`ok:suggestion_created:${suggestionThought.id}`);
        }
        return suggestionText.startsWith('Error:')
            ? TermLogic.Atom(`error:GoalTool_llm_failed:${suggestionText}`)
            : TermLogic.Atom("ok:no_suggestion_generated");
    }
}

class CoreTool implements Tool {
    name = "CoreTool";
    description = "Manages internal FlowMind operations (status, thoughts).";
    async execute(action: Structure, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        switch (operation) {
            case 'set_status': {
                const targetIdTerm = action.args[1]; const newStatusTerm = action.args[2];
                if (targetIdTerm?.kind !== 'Atom' || newStatusTerm?.kind !== 'Atom') return TermLogic.Atom("error:CoreTool_invalid_set_status_params");
                const newStatus = newStatusTerm.name.toUpperCase() as Status;
                if (!Object.values(Status).includes(newStatus)) return TermLogic.Atom(`error:CoreTool_invalid_status_value:${newStatusTerm.name}`);
                const targetThought = context.thoughts.findThought(targetIdTerm.name);
                if (targetThought) {
                    targetThought.status = newStatus; context.thoughts.update(targetThought);
                    return TermLogic.Atom(`ok:status_set:${shortId(targetIdTerm.name)}_to_${newStatus}`);
                } return TermLogic.Atom(`error:CoreTool_target_not_found:${targetIdTerm.name}`);
            }
            case 'add_thought': {
                const typeTerm = action.args[1]; const contentTerm = action.args[2];
                const rootIdTerm = action.args[3]; const parentIdTerm = action.args[4];
                if (typeTerm?.kind !== 'Atom' || !contentTerm) return TermLogic.Atom("error:CoreTool_invalid_add_thought_params");
                const type = typeTerm.name.toUpperCase() as Type;
                if (!Object.values(Type).includes(type)) return TermLogic.Atom(`error:CoreTool_invalid_thought_type:${typeTerm.name}`);
                const newThought: Thought = {
                    id: generateId(), type, content: contentTerm, belief: Belief.DEFAULT, status: Status.PENDING,
                    metadata: {
                        rootId: rootIdTerm?.kind === 'Atom' ? rootIdTerm.name : trigger.metadata.rootId ?? trigger.id,
                        parentId: parentIdTerm?.kind === 'Atom' ? parentIdTerm.name : trigger.id,
                        created: new Date().toISOString(), provenance: `${this.name} (triggered by ${shortId(trigger.id)})`
                    }
                };
                context.engine.addThought(newThought);
                return TermLogic.Atom(`ok:thought_added:${shortId(newThought.id)}`);
            }
            case 'delete_thought': {
                const targetIdTerm = action.args[1];
                if (targetIdTerm?.kind !== 'Atom') return TermLogic.Atom("error:CoreTool_invalid_delete_thought_params");
                const targetThought = context.thoughts.findThought(targetIdTerm.name);
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
        const weights = candidates.map(t => t.metadata.priority ?? t.belief.score());
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
        const matches = this.rules.getAll()
            .map(rule => ({ rule, bindings: TermLogic.unify(rule.pattern, thought.content) }))
            .filter(m => m.bindings !== null) as { rule: Rule; bindings: Map<string, Term> }[];
        if (matches.length === 0) return null;
        if (matches.length === 1) return matches[0];
        const weights = matches.map(m => m.rule.belief.score());
        const totalWeight = weights.reduce((sum, w) => sum + w, 0);
        if (totalWeight <= 0) return matches[Math.floor(Math.random() * matches.length)];
        let random = Math.random() * totalWeight;
        for (let i = 0; i < matches.length; i++) {
            random -= weights[i];
            if (random <= 0) return matches[i];
        }
        return matches[matches.length - 1];
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
            const currentThoughtState = this.thoughts.get(thought.id);
            const isWaiting = currentThoughtState?.status === Status.WAITING;
            const isFailed = currentThoughtState?.status === Status.FAILED;

            if (resultTerm?.kind === 'Atom' && resultTerm.name.startsWith('error:')) {
                this.handleFailure(thought, rule, `Tool execution failed: ${resultTerm.name}`);
            } else if (!isWaiting && !isFailed) {
                this.handleSuccess(thought, rule); success = true;
            }
        } catch (error: any) {
            console.error(chalk.red(`Tool exception ${tool.name} on ${shortId(thought.id)}:`), error);
            this.handleFailure(thought, rule, `Tool exception: ${error.message}`);
        }

        const finalThoughtState = this.thoughts.get(thought.id);
        if (finalThoughtState?.status !== Status.WAITING) {
            rule.belief.update(success); this.rules.update(rule);
        }
        return success;
    }

    private async handleNoRuleMatch(thought: Thought): Promise<void> {
        let prompt = ""; let targetType: Type | null = null; let action: Term | null = null;
        switch (thought.type) {
            case Type.INPUT: prompt = `Input: "${TermLogic.toString(thought.content)}". Define GOAL. Output only goal.`; targetType = Type.GOAL; break;
            case Type.GOAL: prompt = `Goal: "${TermLogic.toString(thought.content)}". Outline 1-3 STRATEGY steps. Output steps, one per line.`; targetType = Type.STRATEGY; break;
            case Type.STRATEGY: prompt = `Strategy step "${TermLogic.toString(thought.content)}" performed. Summarize likely OUTCOME. Output outcome.`; targetType = Type.OUTCOME; break;
            case Type.OUTCOME: action = TermLogic.Structure("MemoryTool", [TermLogic.Atom("add"), thought.content]); break;
            default: const askUserPrompt = `No rule for ${shortId(thought.id)} (${thought.type}: ${TermLogic.toString(thought.content).substring(0, 50)}...). Task?`;
                action = TermLogic.Structure("UserInteractionTool", [TermLogic.Atom("prompt"), TermLogic.Atom(askUserPrompt)]); break;
        }

        if (prompt && targetType) {
            const resultText = await this.llm.generate(prompt);
            if (resultText && !resultText.startsWith('Error:')) {
                resultText.split('\n').map(s => s.trim().replace(/^- /, '')).filter(s => s).forEach(resText => {
                    this.addThought({
                        id: generateId(), type: targetType!, content: TermLogic.Atom(resText),
                        belief: Belief.DEFAULT, status: Status.PENDING,
                        metadata: { rootId: thought.metadata.rootId ?? thought.id, parentId: thought.id, created: new Date().toISOString(), provenance: 'llm_fallback' }
                    });
                });
                thought.status = Status.DONE; thought.belief.update(true); this.thoughts.update(thought);
            } else { this.handleFailure(thought, null, `LLM fallback failed: ${resultText}`); }
        } else if (action?.kind === 'Structure') {
            const tool = this.tools.get(action.name);
            if (tool) {
                try {
                    await tool.execute(action, this.context, thought);
                    const currentStatus = this.thoughts.get(thought.id)?.status;
                    if (currentStatus !== Status.WAITING && currentStatus !== Status.FAILED) {
                        thought.status = Status.DONE; thought.belief.update(true); this.thoughts.update(thought);
                    }
                } catch (error: any) { this.handleFailure(thought, null, `Fallback tool fail (${action.name}): ${error.message}`); }
            } else { this.handleFailure(thought, null, `Fallback tool not found: ${action.name}`); }
        } else if (thought.status === Status.PENDING) {
            this.handleFailure(thought, null, "No rule match and no fallback action.");
        }
    }

    private handleFailure(thought: Thought, rule: Rule | null, errorInfo: string): void {
        const retries = (thought.metadata.retries ?? 0) + 1;
        thought.status = retries >= MAX_RETRIES ? Status.FAILED : Status.PENDING;
        thought.metadata.error = errorInfo.substring(0, 250);
        thought.metadata.retries = retries;
        thought.belief.update(false);
        if (rule) thought.metadata.ruleId = rule.id;
        this.thoughts.update(thought);
        console.warn(chalk.yellow(`Thought ${shortId(thought.id)} failed (Attempt ${retries}): ${errorInfo}`));
    }

    private handleSuccess(thought: Thought, rule: Rule | null): void {
        thought.status = Status.DONE;
        thought.belief.update(true);
        delete thought.metadata.error; delete thought.metadata.retries;
        if (rule) thought.metadata.ruleId = rule.id;
        this.thoughts.update(thought);
    }

    private async _processThought(thought: Thought): Promise<boolean> {
        thought.status = Status.ACTIVE;
        thought.metadata.agentId = 'worker';
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
            console.error(chalk.red(`Critical error processing ${shortId(thought.id)}:`), error);
            this.handleFailure(thought, null, `Unhandled processing exception: ${error.message}`);
        } finally {
            this.activeIds.delete(thought.id);
            const finalThoughtState = this.thoughts.get(thought.id);
            if (finalThoughtState?.status === Status.ACTIVE) {
                console.warn(chalk.yellow(`Thought ${shortId(thought.id)} ended ACTIVE. Setting FAILED.`));
                this.handleFailure(finalThoughtState, null, "Processing ended while ACTIVE.");
            }
        }
        return success;
    }

    async processSingleThought(): Promise<boolean> {
        const thought = this.sampleThought();
        if (!thought || this.activeIds.has(thought.id)) return false;
        this.activeIds.add(thought.id);
        return this._processThought(thought);
    }

    async processBatch(): Promise<number> {
        const promises: Promise<boolean>[] = [];
        while (this.activeIds.size < this.maxConcurrent) {
            const thought = this.sampleThought();
            if (!thought) break;
            this.activeIds.add(thought.id);
            promises.push(this._processThought(thought));
            if (promises.length >= this.batchSize) break;
        }
        if (promises.length === 0) return 0;
        const results = await Promise.all(promises);
        return results.filter(success => success).length;
    }

    async handlePromptResponse(promptId: string, responseText: string): Promise<boolean> {
        const promptThought = this.thoughts.getAll().find(t => t.type === Type.USER_PROMPT && t.metadata.uiContext?.promptId === promptId);
        if (!promptThought) { console.error(chalk.red(`Prompt thought for ID ${shortId(promptId)} not found.`)); return false; }

        const waitingThought = this.thoughts.getAll().find(t => t.metadata.waitingFor === promptId && t.status === Status.WAITING);
        if (!waitingThought) {
            console.warn(chalk.yellow(`No thought found waiting for prompt ${shortId(promptId)}.`));
            promptThought.status = Status.DONE; this.thoughts.update(promptThought); return false;
        }

        this.addThought({
            id: generateId(), type: Type.INPUT, content: TermLogic.Atom(responseText),
            belief: new Belief(1, 0), status: Status.PENDING,
            metadata: {
                rootId: waitingThought.metadata.rootId ?? waitingThought.id, parentId: waitingThought.id,
                created: new Date().toISOString(), responseTo: promptId, tags: ['user_response'], provenance: 'user_input'
            }
        });
        promptThought.status = Status.DONE; this.thoughts.update(promptThought);
        waitingThought.status = Status.PENDING; delete waitingThought.metadata.waitingFor;
        waitingThought.belief.update(true); this.thoughts.update(waitingThought);
        console.log(chalk.blue(`Response for ${shortId(promptId)} received. ${shortId(waitingThought.id)} now PENDING.`));
        return true;
    }
}

async function saveState(thoughts: ThoughtStore, rules: RuleStore, memory: MemoryStore): Promise<void> {
    try {
        await fs.mkdir(DATA_DIR, { recursive: true });
        const state: AppState = { thoughts: thoughts.toJSON(), rules: rules.toJSON() };
        await fs.writeFile(STATE_FILE, JSON.stringify(state, null, 2));
        await memory.save();
        console.log(chalk.gray(`State saved.`));
    } catch (error) { console.error(chalk.red('Error saving state:'), error); }
}

const debouncedSaveState = debounce(saveState, SAVE_DEBOUNCE);

async function loadState(thoughts: ThoughtStore, rules: RuleStore, memory: MemoryStore): Promise<void> {
    try {
        await fs.mkdir(DATA_DIR, { recursive: true });
        await memory.initialize();
        if (fsSync.existsSync(STATE_FILE)) {
            const data = await fs.readFile(STATE_FILE, 'utf-8');
            const state: AppState = safeJsonParse(data, { thoughts: {}, rules: {} });
            thoughts.loadJSON(state.thoughts);
            rules.loadJSON(state.rules);
            console.log(chalk.blue(`Loaded ${thoughts.count()} thoughts, ${rules.count()} rules.`));
        } else { console.log(chalk.yellow(`State file ${STATE_FILE} not found.`)); }
    } catch (error) {
        console.error(chalk.red('Error loading state:'), error);
        if (!memory.isReady) await memory.initialize();
    }
}

class ConsoleUI {
    private rl: readline.Interface;
    private refreshIntervalId: NodeJS.Timeout | null = null;
    private isPaused = false;
    private currentRootView: string | null = null;

    constructor(
        private thoughts: ThoughtStore, private rules: RuleStore,
        private systemControl: (command: string, args?: string[]) => Promise<void>
    ) {
        this.rl = readline.createInterface({ input: process.stdin, output: process.stdout, prompt: chalk.blueBright('FlowMind> '), completer: this.completer.bind(this) });
        thoughts.addChangeListener(() => this.render());
        rules.addChangeListener(() => this.render());
    }

    start(): void {
        console.log(chalk.cyan('Starting FlowMind Console UI...'));
        process.stdout.write('\x1b]0;FlowMind Console\x07');
        this.rl.on('line', async (line) => {
            const trimmedLine = line.trim();
            if (trimmedLine) { const [command, ...args] = trimmedLine.split(' '); await this.systemControl(command.toLowerCase(), args); }
            if (!this.rl.closed) this.rl.prompt();
        });
        this.rl.on('SIGINT', () => {
            this.rl.question(chalk.yellow('Exit FlowMind? (y/N) '), (answer) => {
                if (answer.match(/^y(es)?$/i)) this.systemControl('quit'); else this.rl.prompt();
            });
        });
        this.rl.on('close', () => {
            console.log(chalk.cyan('\nFlowMind Console UI stopped.'));
            if (this.refreshIntervalId) clearInterval(this.refreshIntervalId);
        });
        this.render();
        this.refreshIntervalId = setInterval(() => this.render(), UI_REFRESH_INTERVAL);
        this.rl.prompt();
    }

    stop(): void {
        if (this.refreshIntervalId) { clearInterval(this.refreshIntervalId); this.refreshIntervalId = null; }
        if (!this.rl.closed) this.rl.close();
    }

    setPaused(paused: boolean): void { this.isPaused = paused; this.render(); }

    private getStatusIndicator(status: Status): string {
        const map = { [Status.PENDING]: '⏳', [Status.ACTIVE]: '▶️', [Status.WAITING]: '⏸️', [Status.DONE]: '✅', [Status.FAILED]: '❌' };
        return chalk[status === Status.PENDING ? 'yellow' : status === Status.ACTIVE ? 'blueBright' : status === Status.WAITING ? 'magenta' : status === Status.DONE ? 'green' : 'red'](map[status] || '?');
    }

    private formatThoughtLine(thought: Thought, indent = 0): string {
        const prefix = ' '.repeat(indent);
        const statusIcon = this.getStatusIndicator(thought.status);
        const idStr = chalk.gray(shortId(thought.id));
        const typeStr = chalk.dim(thought.type.padEnd(8));
        const beliefStr = chalk.dim(`B:${thought.belief.score().toFixed(2)}`);
        const contentStr = TermLogic.format(thought.content);
        const errorStr = thought.metadata.error ? chalk.red(` ERR!`) : '';
        const waitingStr = thought.status === Status.WAITING && thought.metadata.waitingFor ? chalk.magenta(` W:${shortId(thought.metadata.waitingFor)}`) : '';
        const tagsStr = thought.metadata.tags?.length ? chalk.cyan(` #${thought.metadata.tags.join('#')}`) : '';
        return `${prefix}${statusIcon} ${idStr} ${typeStr} ${beliefStr}${waitingStr}${errorStr} ${contentStr}${tagsStr}`;
    }

    private renderHeader(termWidth: number): string {
        const title = ` FlowMind Console ${this.isPaused ? chalk.yellow('[PAUSED]') : chalk.green('[RUNNING]')}`;
        const viewMode = this.currentRootView ? chalk.cyan(` [View: ${shortId(this.currentRootView)}]`) : '';
        let header = chalk.bold.inverse("=".repeat(termWidth)) + "\n";
        header += chalk.bold.inverse(title + viewMode + " ".repeat(termWidth - title.length - viewMode.length)) + "\n";
        header += chalk.bold.inverse("=".repeat(termWidth)) + "\n";
        return header;
    }

    private renderThoughts(allThoughts: Thought[], termWidth: number): string {
        let output = "";
        const thoughtsToDisplay = this.currentRootView ? this.thoughts.getAllByRootId(this.currentRootView) : allThoughts.filter(t => !t.metadata.parentId);
        const rootThoughts = thoughtsToDisplay.filter(t => t.id === this.currentRootView || !t.metadata.parentId);
        const thoughtMap = new Map(allThoughts.map(t => [t.id, t]));
        const childrenMap = new Map<string, Thought[]>();
        allThoughts.forEach(t => {
            if (t.metadata.parentId) {
                const list = childrenMap.get(t.metadata.parentId) || []; list.push(t); childrenMap.set(t.metadata.parentId, list);
            }
        });

        const renderChildren = (parentId: string, indent: number) => {
            (childrenMap.get(parentId) || []).sort((a, b) => Date.parse(a.metadata.created ?? '0') - Date.parse(b.metadata.created ?? '0')).forEach(child => {
                if (this.currentRootView || !child.metadata.parentId) {
                    output += this.formatThoughtLine(child, indent) + "\n";
                    if (child.metadata.error) output += ' '.repeat(indent + 2) + chalk.red(`└─ Error: ${child.metadata.error}`) + "\n";
                    renderChildren(child.id, indent + 2);
                }
            });
        };

        if (rootThoughts.length > 0) {
            output += chalk.bold("--- Thoughts ---\n");
            rootThoughts.sort((a, b) => Date.parse(a.metadata.created ?? '0') - Date.parse(b.metadata.created ?? '0')).forEach(root => {
                output += this.formatThoughtLine(root, 0) + "\n";
                if (root.metadata.error) output += ' '.repeat(2) + chalk.red(`└─ Error: ${root.metadata.error}`) + "\n";
                renderChildren(root.id, 2);
                output += chalk.grey("-".repeat(termWidth / 2)) + "\n";
            });
        } else {
            output += chalk.grey(this.currentRootView ? `Thought ${shortId(this.currentRootView)} not found/no children.` : "No top-level thoughts. Use 'add <note>'.\n");
        }
        return output;
    }

    private renderPrompts(allThoughts: Thought[]): string {
        let output = "";
        const pendingPrompts = allThoughts.filter(t => t.type === Type.USER_PROMPT && t.status === Status.PENDING && t.metadata.uiContext?.promptId);
        if (pendingPrompts.length > 0) {
            output += chalk.bold.yellow("\n--- Pending Prompts ---\n");
            pendingPrompts.forEach(p => {
                const promptId = p.metadata.uiContext?.promptId ?? '?id?';
                output += `${chalk.yellow('❓')} [${chalk.bold(shortId(promptId))}] ${TermLogic.format(p.content)} ${chalk.grey(`(for ${shortId(p.metadata.parentId)})`)}\n`;
            });
        }
        return output;
    }

    private renderFooter(termWidth: number): string {
        let output = chalk.inverse("=".repeat(termWidth)) + "\n";
        output += chalk.dim("Cmds: add, respond, run, pause, step, rules, tools, info, view, clear, save, quit, tag, untag, search, delete, help") + "\n";
        return output;
    }

    private render(): void {
        if (this.rl.closed) return;
        const termWidth = process.stdout.columns || 80;
        const allThoughts = this.thoughts.getAll();
        let output = "";
        output += this.renderHeader(termWidth);
        output += this.renderThoughts(allThoughts, termWidth);
        output += this.renderPrompts(allThoughts);
        output += this.renderFooter(termWidth);

        readline.cursorTo(process.stdout, 0, 0);
        readline.clearScreenDown(process.stdout);
        process.stdout.write(output);
        this.rl.prompt(true);
    }

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

        if (parts.length <= 1) hits = commands.filter((c) => c.startsWith(currentPart));
        else {
            switch (command) {
                case 'info': case 'view': case 'delete': case 'tag': case 'untag':
                    if (parts.length === 2) hits = thoughts.filter((id) => id.startsWith(currentPart)); break;
                case 'respond': if (parts.length === 2) hits = prompts.filter((id) => id.startsWith(currentPart)); break;
            }
        }
        return [hits, currentPart];
    }

    switchView(rootIdPrefix: string | null): void {
        if (!rootIdPrefix || ['all', 'root'].includes(rootIdPrefix.toLowerCase())) {
            this.currentRootView = null; console.log(chalk.blue("View: All root thoughts."));
        } else {
            const targetThought = this.thoughts.findThought(rootIdPrefix);
            if (targetThought) {
                let root = targetThought; let safety = 0;
                while (root.metadata.parentId && safety++ < 20) {
                    const parent = this.thoughts.get(root.metadata.parentId); if (!parent) break; root = parent;
                }
                this.currentRootView = root.id; console.log(chalk.blue(`View: Root ${shortId(this.currentRootView)}.`));
            } else { console.log(chalk.red(`Cannot find thought prefix "${rootIdPrefix}".`)); }
        }
        this.render();
    }
}

class FlowMindSystem {
    private thoughts = new ThoughtStore();
    private rules = new RuleStore();
    private llm = new LLMService();
    private memory = new MemoryStore(this.llm.embeddings, VECTOR_STORE_DIR);
    private tools = new ToolRegistry();
    private engine = new Engine(this.thoughts, this.rules, this.memory, this.llm, this.tools);
    private ui = new ConsoleUI(this.thoughts, this.rules, this.handleCommand.bind(this));
    private isRunning = false;
    private workerIntervalId: NodeJS.Timeout | null = null;

    constructor() { this.registerCoreTools(); }
    private registerCoreTools(): void { [new LLMTool(), new MemoryTool(), new UserInteractionTool(), new GoalProposalTool(), new CoreTool()].forEach(t => this.tools.register(t)); }

    private bootstrapRules(): void {
        if (this.rules.count() > 0) return;
        console.log(chalk.blue("Bootstrapping default rules..."));
        const defaultRules: Omit<Rule, 'belief' | 'metadata' | 'id'>[] = [
            { pattern: TermLogic.Structure("INPUT", [TermLogic.Variable("C")]), action: TermLogic.Structure("LLMTool", [TermLogic.Atom("generate"), TermLogic.Atom("Input: \"?C\". Define GOAL. Output only goal.")]) },
            { pattern: TermLogic.Structure("GOAL", [TermLogic.Variable("C")]), action: TermLogic.Structure("LLMTool", [TermLogic.Atom("generate"), TermLogic.Atom("Goal: \"?C\". Outline 1-3 STRATEGY steps. Output steps, one per line.")]) },
            { pattern: TermLogic.Structure("STRATEGY", [TermLogic.Variable("C")]), action: TermLogic.Structure("LLMTool", [TermLogic.Atom("generate"), TermLogic.Atom("Strategy \"?C\" performed. Summarize likely OUTCOME. Output outcome.")]) },
            { pattern: TermLogic.Structure("OUTCOME", [TermLogic.Variable("C")]), action: TermLogic.Structure("MemoryTool", [TermLogic.Atom("add"), TermLogic.Variable("C")]) },
            { pattern: TermLogic.Structure("OUTCOME", [TermLogic.Variable("C")]), action: TermLogic.Structure("GoalProposalTool", [TermLogic.Atom("suggest"), TermLogic.Variable("C")]) },
        ];
        defaultRules.forEach((r, i) => {
            const type = r.pattern.kind === 'Structure' ? r.pattern.name : 'Generic';
            this.rules.add({ id: generateId(), ...r, belief: Belief.DEFAULT, metadata: { description: `Default ${i+1}: On ${type}`, provenance: 'bootstrap', created: new Date().toISOString() } });
        });
        console.log(chalk.green(`${this.rules.count()} default rules added.`));
    }

    async initialize(): Promise<void> {
        console.log(chalk.cyan('Initializing FlowMind System...'));
        await loadState(this.thoughts, this.rules, this.memory);
        this.bootstrapRules();
        this.ui.start();
        this.ui.setPaused(!this.isRunning);
        if (this.isRunning) this.startProcessingLoop();
        console.log(chalk.green('FlowMind Initialized.') + chalk.yellow(" Paused. Use 'run' to start."));
    }

    private startProcessingLoop(): void {
        if (this.workerIntervalId) return;
        console.log(chalk.green('Starting processing loop...'));
        this.isRunning = true; this.ui.setPaused(false);
        this.workerIntervalId = setInterval(async () => {
            if (!this.isRunning) return;
            try {
                if (await this.engine.processBatch() > 0) {
                    debouncedSaveState(this.thoughts, this.rules, this.memory);
                }
            } catch (error) { console.error(chalk.red('Error in processing loop:'), error); }
        }, WORKER_INTERVAL);
    }

    private pauseProcessingLoop(): void {
        if (!this.workerIntervalId) return;
        console.log(chalk.yellow('Pausing processing loop...'));
        this.isRunning = false; this.ui.setPaused(true);
        clearInterval(this.workerIntervalId); this.workerIntervalId = null;
        debouncedSaveState.cancel(); saveState(this.thoughts, this.rules, this.memory);
    }

    private async handleCommand(command: string, args: string[] = []): Promise<void> {
        try {
            switch (command) {
                case 'add': {
                    const contentText = args.join(' '); if (!contentText) { console.log(chalk.yellow("Usage: add <note text>")); break; }
                    const newThought: Thought = {
                        id: generateId(), type: Type.INPUT, content: TermLogic.Atom(contentText), belief: new Belief(1, 0), status: Status.PENDING,
                        metadata: { created: new Date().toISOString(), tags: ['user_added'], provenance: 'user_command' }
                    }; newThought.metadata.rootId = newThought.id; this.engine.addThought(newThought);
                    console.log(chalk.green(`Added: ${shortId(newThought.id)}`)); debouncedSaveState(this.thoughts, this.rules, this.memory); break;
                }
                case 'respond': {
                    const promptIdPrefix = args[0]; const responseText = args.slice(1).join(' ');
                    if (!promptIdPrefix || !responseText) { console.log(chalk.yellow("Usage: respond <prompt_id_prefix> <response text>")); break; }
                    const fullPromptId = this.thoughts.getAll().map(t => t.metadata?.uiContext?.promptId).find(pid => pid && pid.startsWith(promptIdPrefix));
                    if (!fullPromptId) { console.log(chalk.red(`Prompt prefix "${promptIdPrefix}" not found.`)); break; }
                    await this.engine.handlePromptResponse(fullPromptId, responseText); debouncedSaveState(this.thoughts, this.rules, this.memory); break;
                }
                case 'run': if (!this.isRunning) this.startProcessingLoop(); else console.log(chalk.yellow("Already running.")); break;
                case 'pause': if (this.isRunning) this.pauseProcessingLoop(); else console.log(chalk.yellow("Already paused.")); break;
                case 'step':
                    if (!this.isRunning) {
                        console.log(chalk.blue("Stepping...")); const processed = await this.engine.processSingleThought();
                        console.log(processed ? chalk.green("Step done.") : chalk.yellow("Nothing to step."));
                        if (processed) debouncedSaveState(this.thoughts, this.rules, this.memory);
                    } else console.log(chalk.yellow("Pause system first ('pause').")); break;
                case 'save': debouncedSaveState.cancel(); await saveState(this.thoughts, this.rules, this.memory); console.log(chalk.blue("State saved.")); break;
                case 'quit': case 'exit': await this.shutdown(); break;
                case 'clear': console.clear(); this.ui['render'](); break;
                case 'help': this.printHelp(); break;
                case 'status': this.printStatus(); break;
                case 'info': {
                    const idPrefix = args[0]; if (!idPrefix) { console.log(chalk.yellow("Usage: info <id_prefix>")); break; }
                    const thought = this.thoughts.findThought(idPrefix); const rule = this.rules.findRule(idPrefix);
                    if (thought) this.printThoughtInfo(thought); else if (rule) this.printRuleInfo(rule); else console.log(chalk.red(`ID prefix "${idPrefix}" not found.`)); break;
                }
                case 'view': this.ui.switchView(args[0] || null); break;
                case 'rules': this.printRules(); break;
                case 'tools': this.printTools(); break;
                case 'tag': case 'untag': {
                    const idPrefix = args[0]; const tag = args[1]; const isTagging = command === 'tag';
                    if (!idPrefix || !tag) { console.log(chalk.yellow(`Usage: ${command} <thought_id_prefix> <tag_name>`)); break; }
                    const thought = this.thoughts.findThought(idPrefix); if (!thought) { console.log(chalk.red(`Thought "${idPrefix}" not found.`)); break; }
                    thought.metadata.tags = thought.metadata.tags || []; const hasTag = thought.metadata.tags.includes(tag);
                    if (isTagging && !hasTag) { thought.metadata.tags.push(tag); console.log(chalk.green(`Tagged ${shortId(thought.id)} with #${tag}.`)); }
                    else if (!isTagging && hasTag) { thought.metadata.tags = thought.metadata.tags.filter(t => t !== tag); console.log(chalk.green(`Untagged ${shortId(thought.id)} from #${tag}.`)); }
                    else { console.log(chalk.yellow(`Thought ${shortId(thought.id)} ${isTagging ? 'already has' : 'does not have'} tag #${tag}.`)); break; }
                    this.thoughts.update(thought); debouncedSaveState(this.thoughts, this.rules, this.memory); break;
                }
                case 'search': {
                    const query = args.join(' '); if (!query) { console.log(chalk.yellow("Usage: search <query>")); break; }
                    console.log(chalk.blue(`Searching memory: "${query}"...`)); const results = await this.memory.search(query, 5);
                    if (results.length > 0) {
                        console.log(chalk.bold(`--- Search Results (${results.length}) ---`));
                        results.forEach(r => console.log(`[${chalk.gray(shortId(r.metadata.sourceId))}|${chalk.dim(r.metadata.type)}] ${r.content.substring(0, 80)}${r.content.length > 80 ? '...' : ''}`));
                    } else console.log(chalk.yellow("No relevant memories found.")); break;
                }
                case 'delete': {
                    const idPrefix = args[0]; if (!idPrefix) { console.log(chalk.yellow("Usage: delete <thought_id_prefix>")); break; }
                    const thought = this.thoughts.findThought(idPrefix); if (!thought) { console.log(chalk.red(`Thought "${idPrefix}" not found.`)); break; }
                    this.ui['rl'].question(chalk.yellow(`Delete thought ${shortId(thought.id)}? (y/N) `), (answer) => {
                        if (answer.match(/^y(es)?$/i)) {
                            if (this.thoughts.delete(thought.id)) { console.log(chalk.green(`Thought ${shortId(thought.id)} deleted.`)); debouncedSaveState(this.thoughts, this.rules, this.memory); }
                            else console.log(chalk.red(`Failed delete ${shortId(thought.id)}.`));
                        } else console.log(chalk.yellow("Deletion cancelled."));
                        this.ui['rl'].prompt();
                    }); return;
                }
                default: console.log(chalk.red(`Unknown command: ${command}. Try 'help'.`));
            }
        } catch (error: any) { console.error(chalk.red(`Error executing command "${command}":`), error); }
        if (!['delete', 'quit', 'exit'].includes(command)) this.ui['rl']?.prompt();
    }

    private printHelp(): void {
        console.log(chalk.bold("\n--- FlowMind Help ---"));
        const cmds = [
            ['add <text>', 'Create new INPUT thought'], ['respond <id> <text>', 'Respond to user prompt'],
            ['run', 'Start/resume processing'], ['pause', 'Pause processing'], ['step', 'Process one thought (when paused)'],
            ['view [id|all]', 'Focus view on thought tree/all'], ['info <id>', 'Details for thought/rule'],
            ['rules', 'List all rules'], ['tools', 'List all tools'], ['tag <id> <tag>', 'Add tag to thought'],
            ['untag <id> <tag>', 'Remove tag from thought'], ['search <query>', 'Search vector memory'],
            ['delete <id>', 'Delete thought (confirm!)'], ['save', 'Save state now'], ['clear', 'Clear screen'],
            ['help', 'Show this help'], ['quit | exit', 'Save state and exit'],
        ];
        cmds.forEach(([cmd, desc]) => console.log(` ${chalk.cyan(cmd.padEnd(20))} : ${desc}`));
        console.log(chalk.grey(" (IDs can usually be prefixes)"));
    }

    private printStatus(): void {
        const counts = Object.values(Status).reduce((acc, s) => ({ ...acc, [s]: 0 }), {} as Record<Status, number>);
        this.thoughts.getAll().forEach(t => counts[t.status]++);
        console.log(chalk.bold("\n--- System Status ---"));
        console.log(` Processing: ${this.isRunning ? chalk.green('RUNNING') : chalk.yellow('PAUSED')}`);
        console.log(` Thoughts  : ${chalk.cyan(this.thoughts.count())} total`);
        Object.entries(counts).filter(([,c])=>c>0).forEach(([s, c]) => console.log(`   - ${s.padEnd(7)}: ${chalk.yellow(c)}`));
        console.log(` Rules     : ${chalk.cyan(this.rules.count())}`);
        console.log(` View Mode : ${this.ui['currentRootView'] ? `Focused on ${shortId(this.ui['currentRootView'])}` : 'Root View'}`);
    }

    private printThoughtInfo(thought: Thought): void {
        console.log(chalk.bold.underline(`\n--- Thought Info: ${shortId(thought.id)} ---`));
        console.log(` Full ID : ${chalk.gray(thought.id)}`);
        console.log(` Type    : ${chalk.cyan(thought.type)}`);
        console.log(` Status  : ${this.ui['getStatusIndicator'](thought.status)} ${thought.status}`);
        console.log(` Belief  : ${chalk.yellow(thought.belief.score().toFixed(3))} (P:${thought.belief.pos}, N:${thought.belief.neg})`);
        console.log(` Content : ${TermLogic.format(thought.content)}`); // Short format for term
        console.log(chalk.bold(" Metadata:"));
        Object.entries(thought.metadata).forEach(([key, value]) => {
            if (value !== undefined && value !== null && (!Array.isArray(value) || value.length > 0)) {
                let displayValue = typeof value === 'object' ? JSON.stringify(value) : String(value);
                if (['parentId', 'rootId', 'waitingFor', 'responseTo', 'ruleId'].includes(key)) displayValue = shortId(displayValue);
                console.log(`  ${key.padEnd(12)}: ${chalk.dim(displayValue)}`);
            }
        });
    }

    private printRuleInfo(rule: Rule): void {
        console.log(chalk.bold.underline(`\n--- Rule Info: ${shortId(rule.id)} ---`));
        console.log(` Full ID     : ${chalk.gray(rule.id)}`);
        console.log(` Description : ${chalk.cyan(rule.metadata.description || 'N/A')}`);
        console.log(` Belief      : ${chalk.yellow(rule.belief.score().toFixed(3))} (P:${rule.belief.pos}, N:${rule.belief.neg})`);
        console.log(` Pattern     : ${TermLogic.format(rule.pattern)}`);
        console.log(` Action      : ${TermLogic.format(rule.action)}`);
        if (Object.keys(rule.metadata).filter(k=>k!=='description').length > 0) console.log(chalk.bold(" Metadata:"));
        Object.entries(rule.metadata).forEach(([key, value]) => { if (key !== 'description') console.log(`  ${key.padEnd(12)}: ${chalk.dim(String(value))}`); });
    }

    private printRules(): void {
        const allRules = this.rules.getAll();
        console.log(chalk.bold(`\n--- Rules (${allRules.length}) ---`));
        if (allRules.length === 0) { console.log(chalk.grey("No rules loaded.")); return; }
        allRules.sort((a, b) => a.metadata.description?.localeCompare(b.metadata.description ?? '') ?? 0).forEach(rule => {
            const idStr = chalk.gray(shortId(rule.id)); const beliefStr = chalk.yellow(rule.belief.score().toFixed(2));
            console.log(` ${idStr} [B:${beliefStr}] ${chalk.cyan(rule.metadata.description || `Rule ${idStr}`)}`);
            console.log(`   P: ${TermLogic.format(rule.pattern)}`); console.log(`   A: ${TermLogic.format(rule.action)}`);
        });
    }

    private printTools(): void {
        const allTools = this.tools.list();
        console.log(chalk.bold(`\n--- Tools (${allTools.length}) ---`));
        if (allTools.length === 0) { console.log(chalk.grey("No tools registered.")); return; }
        allTools.sort((a, b) => a.name.localeCompare(b.name)).forEach(tool => console.log(` ${chalk.cyan.bold(tool.name)}: ${tool.description}`));
    }

    async shutdown(): Promise<void> {
        console.log(chalk.cyan('\nShutting down FlowMind System...'));
        this.pauseProcessingLoop(); this.ui.stop();
        debouncedSaveState.cancel(); await saveState(this.thoughts, this.rules, this.memory);
        console.log(chalk.green('FlowMind shutdown complete. Goodbye!'));
        process.exit(0);
    }
}

async function main() {
    const system = new FlowMindSystem();
    const handleShutdown = async (signal: string) => { console.log(chalk.yellow(`\n${signal} received. Shutting down...`)); await system.shutdown(); };
    process.on('SIGINT', () => handleShutdown('SIGINT'));
    process.on('SIGTERM', () => handleShutdown('SIGTERM'));
    process.on('uncaughtException', async (error) => {
        console.error(chalk.red.bold('\n--- UNCAUGHT EXCEPTION ---\n'), error, chalk.red.bold('\n--------------------------'));
        try { await system.shutdown(); } catch { process.exit(1); }
    });
    process.on('unhandledRejection', async (reason) => {
        console.error(chalk.red.bold('\n--- UNHANDLED REJECTION ---\nReason:'), reason, chalk.red.bold('\n---------------------------'));
        try { await system.shutdown(); } catch { process.exit(1); }
    });

    try { await system.initialize(); }
    catch (error) { console.error(chalk.red.bold("Critical init error:"), error); process.exit(1); }
}

main();