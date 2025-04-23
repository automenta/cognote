import * as fs from 'fs/promises';
import * as fsSync from 'fs';
import * as path from 'path';
import * as os from 'os';
import * as readline from 'readline';
import { v4 as uuidv4 } from 'uuid';
import { ChatOllama } from "@langchain/community/chat_models/ollama";
import { OllamaEmbeddings } from "@langchain/community/embeddings/ollama";
import { BaseChatModel } from "@langchain/core/language_models/chat_models";
import { Embeddings } from "@langchain/core/embeddings";
import { HumanMessage } from "@langchain/core/messages";
import { StringOutputParser } from "@langchain/core/output_parsers";
import { Document } from "@langchain/core/documents";
import { FaissStore } from "@langchain/community/vectorstores/faiss";

const DATA_DIR = path.join(os.homedir(), '.flowmind-console');
const STATE_FILE_PATH = path.join(DATA_DIR, 'flowmind_state.json');
const VECTOR_STORE_PATH = path.join(DATA_DIR, 'vector-store');
const OLLAMA_BASE_URL = process.env.OLLAMA_HOST || "http://localhost:11434";
const OLLAMA_MODEL = "llamablit";
const OLLAMA_EMBEDDING_MODEL = "llamablit";
const WORKER_INTERVAL_MS = 2000;
const UI_REFRESH_INTERVAL_MS = 1000;
const MAX_RETRIES = 2;
const DEFAULT_BELIEF_POS = 1.0;
const DEFAULT_BELIEF_NEG = 1.0;

enum Status { PENDING = 'PENDING', ACTIVE = 'ACTIVE', WAITING = 'WAITING', DONE = 'DONE', FAILED = 'FAILED' }
enum Type { INPUT = 'INPUT', GOAL = 'GOAL', STRATEGY = 'STRATEGY', OUTCOME = 'OUTCOME', QUERY = 'QUERY', USER_PROMPT = 'USER_PROMPT', SYSTEM_EVENT = 'SYSTEM_EVENT' }

type Term = Atom | Variable | Structure | ListTerm;
interface Atom { kind: 'Atom'; name: string; }
interface Variable { kind: 'Variable'; name: string; }
interface Structure { kind: 'Structure'; name: string; args: Term[]; }
interface ListTerm { kind: 'ListTerm'; elements: Term[]; }
interface BeliefData { positive_evidence: number; negative_evidence: number; }
interface Metadata { root_id?: string; parent_id?: string; rule_id?: string; agent_id?: string; timestamp_created?: string; timestamp_modified?: string; priority?: number; error_info?: string; ui_context?: string | { promptText?: string, promptId?: string }; tags?: string[]; feedback?: any[]; embedding_generated_at?: string; ai_suggestions?: string[]; retries?: number; waiting_for_prompt_id?: string; response_to_prompt_id?: string; [key: string]: any; }
interface Thought { id: string; type: Type; content: Term; belief: Belief; status: Status; metadata: Metadata; }
interface Rule { id: string; pattern: Term; action: Term; belief: Belief; metadata: { description?: string; provenance?: string; timestamp_created?: string; }; }
interface MemoryEntry { id: string; embedding: number[]; content: string; metadata: { timestamp: string; type: string; source_id: string; }; }
interface ToolContext { thoughtStore: ThoughtStore; ruleStore: RuleStore; memoryStore: MemoryStore; llmService: LLMService; engine: Engine; }
interface Tool { name: string; description: string; execute(actionTerm: Structure, context: ToolContext, triggerThought: Thought): Promise<Term | null>; }
interface AppState { thoughts: Record<string, any>; rules: Record<string, any>; }

class Belief implements BeliefData {
    positive_evidence: number;
    negative_evidence: number;

    constructor(pos: number = DEFAULT_BELIEF_POS, neg: number = DEFAULT_BELIEF_NEG) {
        this.positive_evidence = pos;
        this.negative_evidence = neg;
    }

    score(): number { return (this.positive_evidence + 1) / (this.positive_evidence + this.negative_evidence + 2); }
    update(success: boolean): void { success ? this.positive_evidence++ : this.negative_evidence++; }
    toJSON(): BeliefData { return { positive_evidence: this.positive_evidence, negative_evidence: this.negative_evidence }; }
    static fromJSON(data: BeliefData): Belief { return new Belief(data.positive_evidence, data.negative_evidence); }
    static DEFAULT = new Belief();
}

namespace TermLogic {
    export const Atom = (name: string): Atom => ({ kind: 'Atom', name });
    export const Variable = (name: string): Variable => ({ kind: 'Variable', name });
    export const Structure = (name: string, args: Term[]): Structure => ({ kind: 'Structure', name, args });
    export const ListTerm = (elements: Term[]): ListTerm => ({ kind: 'ListTerm', elements });

    export function formatTerm(term: Term | null | undefined): string {
        if (!term) return 'null';
        switch (term.kind) {
            case 'Atom': return term.name;
            case 'Variable': return `?${term.name}`;
            case 'Structure': return `${term.name}(${term.args.map(formatTerm).join(', ')})`;
            case 'ListTerm': return `[${term.elements.map(formatTerm).join(', ')}]`;
            default: return 'invalid_term';
        }
    }

    export function termToString(term: Term): string {
        switch (term.kind) {
            case 'Atom': return term.name;
            case 'Variable': return `?${term.name}`;
            case 'Structure': return `${term.name}: ${term.args.map(termToString).join('; ')}`;
            case 'ListTerm': return term.elements.map(termToString).join(', ');
            default: return '';
        }
    }

    export function stringToTerm(input: string): Term {
        if (input.includes(':') && input.includes(';')) {
            const [name, argsStr] = input.split(':', 2);
            const args = argsStr.split(';').map(s => Atom(s.trim()));
            return Structure(name.trim(), args);
        }
        if (input.startsWith('[') && input.endsWith(']')) {
            const elements = input.slice(1, -1).split(',').map(s => Atom(s.trim()));
            return ListTerm(elements);
        }
        return Atom(input);
    }

    export function unify(term1: Term, term2: Term, bindings: Map<string, Term> = new Map()): Map<string, Term> | null {
        const resolve = (term: Term, currentBindings: Map<string, Term>): Term => {
            if (term.kind === 'Variable' && currentBindings.has(term.name)) return resolve(currentBindings.get(term.name)!, currentBindings);
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
        if (term.kind === 'Variable' && bindings.has(term.name)) return substitute(bindings.get(term.name)!, bindings);
        if (term.kind === 'Structure') return { ...term, args: term.args.map(arg => substitute(arg, bindings)) };
        if (term.kind === 'ListTerm') return { ...term, elements: term.elements.map(el => substitute(el, bindings)) };
        return term;
    }
}

function generateId(): string { return uuidv4(); }

function debounce<T extends (...args: any[]) => any>(func: T, wait: number): (...args: Parameters<T>) => void {
    let timeout: NodeJS.Timeout | null = null;
    return (...args: Parameters<T>): void => {
        const later = () => { timeout = null; func(...args); };
        if (timeout) clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
}

function safeJsonParse<T>(json: string | null | undefined, defaultValue: T): T {
    if (!json) return defaultValue;
    try { return JSON.parse(json); } catch { return defaultValue; }
}

function sleep(ms: number): Promise<void> { return new Promise(resolve => setTimeout(resolve, ms)); }

class ThoughtStore {
    private thoughts = new Map<string, Thought>();

    add(thought: Thought): void { this.thoughts.set(thought.id, thought); }
    get(id: string): Thought | undefined { return this.thoughts.get(id); }
    update(thought: Thought): void {
        if (this.thoughts.has(thought.id)) {
            this.thoughts.set(thought.id, { ...thought, metadata: { ...thought.metadata, timestamp_modified: new Date().toISOString() } });
        }
    }
    getPending(): Thought[] { return Array.from(this.thoughts.values()).filter(t => t.status === Status.PENDING); }
    getAll(): Thought[] { return Array.from(this.thoughts.values()); }
    getAllByRootId(rootId: string): Thought[] {
        return this.getAll()
            .filter(t => t.metadata.root_id === rootId || t.id === rootId)
            .sort((a, b) => Date.parse(a.metadata.timestamp_created ?? '0') - Date.parse(b.metadata.timestamp_created ?? '0'));
    }
    toJSON(): Record<string, any> {
        const obj: Record<string, any> = {};
        this.thoughts.forEach((thought, id) => obj[id] = { ...thought, belief: thought.belief.toJSON() });
        return obj;
    }
    static fromJSON(data: Record<string, any>): ThoughtStore {
        const store = new ThoughtStore();
        for (const id in data) {
            store.add({ ...data[id], belief: Belief.fromJSON(data[id].belief) });
        }
        return store;
    }
}

class RuleStore {
    private rules = new Map<string, Rule>();

    add(rule: Rule): void { this.rules.set(rule.id, rule); }
    get(id: string): Rule | undefined { return this.rules.get(id); }
    update(rule: Rule): void { if (this.rules.has(rule.id)) this.rules.set(rule.id, rule); }
    getAll(): Rule[] { return Array.from(this.rules.values()); }
    toJSON(): Record<string, any> {
        const obj: Record<string, any> = {};
        this.rules.forEach((rule, id) => obj[id] = { ...rule, belief: rule.belief.toJSON() });
        return obj;
    }
    static fromJSON(data: Record<string, any>): RuleStore {
        const store = new RuleStore();
        for (const id in data) {
            store.add({ ...data[id], belief: Belief.fromJSON(data[id].belief) });
        }
        return store;
    }
}

class MemoryStore {
    private vectorStore: FaissStore | null = null;
    private embeddings: Embeddings;
    private vectorStorePath: string;
    private isReady = false;

    constructor(embeddingsService: Embeddings, storePath: string) {
        this.embeddings = embeddingsService;
        this.vectorStorePath = storePath;
    }

    async initialize(): Promise<void> {
        try {
            await fs.access(this.vectorStorePath);
            this.vectorStore = await FaissStore.load(this.vectorStorePath, this.embeddings);
            this.isReady = true;
        } catch (error: any) {
            if (error.code === 'ENOENT') {
                const dummyDoc = new Document({ pageContent: "FlowMind init", metadata: { source_id: "init", type: "system", timestamp: new Date().toISOString() } });
                this.vectorStore = await FaissStore.fromDocuments([dummyDoc], this.embeddings);
                await this.vectorStore.save(this.vectorStorePath);
                this.isReady = true;
            }
        }
    }

    async add(entry: Omit<MemoryEntry, 'embedding'>): Promise<void> {
        if (!this.vectorStore || !this.isReady) return;
        const doc = new Document({ pageContent: entry.content, metadata: { ...entry.metadata, id: entry.id } });
        await this.vectorStore.addDocuments([doc]);
        await this.save();
    }

    async search(query: string, k: number = 5): Promise<MemoryEntry[]> {
        if (!this.vectorStore || !this.isReady) return [];
        const results = await this.vectorStore.similaritySearchWithScore(query, k);
        return results.map(([doc]) => ({
            id: doc.metadata.id || generateId(),
            embedding: [],
            content: doc.pageContent,
            metadata: doc.metadata as MemoryEntry['metadata']
        }));
    }

    async save(): Promise<void> {
        if (this.vectorStore && this.isReady) await this.vectorStore.save(this.vectorStorePath);
    }
}

class LLMService {
    llm: BaseChatModel;
    embeddings: Embeddings;

    constructor() {
        this.llm = new ChatOllama({ baseUrl: OLLAMA_BASE_URL, model: 'llamablit', temperature: 0.7 });
        this.embeddings = new OllamaEmbeddings({ model: OLLAMA_EMBEDDING_MODEL, baseUrl: OLLAMA_BASE_URL });
    }

    async generate(prompt: string): Promise<string> {
        return this.llm.pipe(new StringOutputParser()).invoke([new HumanMessage(prompt)]);
    }

    async embed(text: string): Promise<number[]> {
        return this.embeddings.embedQuery(text);
    }
}

class LLMTool implements Tool {
    name = "LLMTool";
    description = "Interacts with the LLM for generation or embedding.";

    async execute(actionTerm: Structure, context: ToolContext, triggerThought: Thought): Promise<Term | null> {
        const operation = actionTerm.args[0]?.kind === 'Atom' ? actionTerm.args[0].name : null;
        const promptTerm = actionTerm.args[1];
        if (!operation || !promptTerm) return TermLogic.Atom("error:missing_llm_params");
        const inputText = TermLogic.termToString(promptTerm);
        if (operation === 'generate') {
            return TermLogic.Atom(await context.llmService.generate(inputText));
        } else if (operation === 'embed') {
            return TermLogic.Atom("ok:embedding_requested");
        }
        return TermLogic.Atom("error:unsupported_llm_operation");
    }
}

class MemoryTool implements Tool {
    name = "MemoryTool";
    description = "Adds to or searches the MemoryStore.";

    async execute(actionTerm: Structure, context: ToolContext, triggerThought: Thought): Promise<Term | null> {
        const operation = actionTerm.args[0]?.kind === 'Atom' ? actionTerm.args[0].name : null;
        if (!operation) return TermLogic.Atom("error:missing_memory_operation");
        if (operation === 'add') {
            const contentTerm = actionTerm.args[1];
            if (!contentTerm) return TermLogic.Atom("error:missing_memory_add_content");
            const contentStr = TermLogic.termToString(contentTerm);
            const sourceId = actionTerm.args[2]?.kind === 'Atom' ? actionTerm.args[2].name : triggerThought.id;
            const contentType = actionTerm.args[3]?.kind === 'Atom' ? actionTerm.args[3].name : triggerThought.type;
            await context.memoryStore.add({
                id: generateId(),
                content: contentStr,
                metadata: { timestamp: new Date().toISOString(), type: contentType, source_id: sourceId }
            });
            triggerThought.metadata.embedding_generated_at = new Date().toISOString();
            context.thoughtStore.update(triggerThought);
            return TermLogic.Atom("ok:memory_added");
        } else if (operation === 'search') {
            const queryTerm = actionTerm.args[1];
            const k = actionTerm.args[2]?.kind === 'Atom' ? parseInt(actionTerm.args[2].name, 10) : 3;
            if (!queryTerm) return TermLogic.Atom("error:missing_memory_search_query");
            const queryStr = TermLogic.termToString(queryTerm);
            const results = await context.memoryStore.search(queryStr, isNaN(k) ? 3 : k);
            return TermLogic.ListTerm(results.map(r => TermLogic.Atom(r.content)));
        }
        return TermLogic.Atom("error:unsupported_memory_operation");
    }
}

class UserInteractionTool implements Tool {
    name = "UserInteractionTool";
    description = "Requests input from the user via the console UI.";

    async execute(actionTerm: Structure, context: ToolContext, triggerThought: Thought): Promise<Term | null> {
        const operation = actionTerm.args[0]?.kind === 'Atom' ? actionTerm.args[0].name : null;
        if (operation !== 'prompt') return TermLogic.Atom("error:unsupported_ui_operation");
        const promptTextTerm = actionTerm.args[1];
        if (!promptTextTerm) return TermLogic.Atom("error:missing_prompt_text");
        const promptText = TermLogic.termToString(promptTextTerm);
        const promptId = generateId();
        const promptThought: Thought = {
            id: generateId(),
            type: Type.USER_PROMPT,
            content: TermLogic.Atom(promptText),
            belief: new Belief(),
            status: Status.PENDING,
            metadata: {
                root_id: triggerThought.metadata.root_id ?? triggerThought.id,
                parent_id: triggerThought.id,
                timestamp_created: new Date().toISOString(),
                ui_context: { promptText, promptId }
            }
        };
        context.engine.addThought(promptThought);
        triggerThought.status = Status.WAITING;
        triggerThought.metadata.waiting_for_prompt_id = promptId;
        context.thoughtStore.update(triggerThought);
        return TermLogic.Atom(`ok:prompt_requested:${promptId}`);
    }
}

class GoalProposalTool implements Tool {
    name = "GoalProposalTool";
    description = "Suggests new goals based on context or memory.";

    async execute(actionTerm: Structure, context: ToolContext, triggerThought: Thought): Promise<Term | null> {
        const operation = actionTerm.args[0]?.kind === 'Atom' ? actionTerm.args[0].name : null;
        if (operation !== 'suggest') return TermLogic.Atom("error:unsupported_goal_operation");
        const contextTerm = actionTerm.args[1] ?? triggerThought.content;
        const contextStr = TermLogic.termToString(contextTerm);
        const memoryResults = await context.memoryStore.search(`Relevant past goals or outcomes related to: ${contextStr}`, 3);
        const memoryContext = memoryResults.map(r => r.content).join("\n - ");
        const prompt = `Based on the current context "${contextStr}" and potentially related past activities:\n - ${memoryContext}\n\nSuggest ONE concise, actionable next goal or task. Output only the suggested goal text.`;
        const suggestionText = await context.llmService.generate(prompt);
        if (suggestionText && suggestionText.trim()) {
            const suggestionThought: Thought = {
                id: generateId(),
                type: Type.INPUT,
                content: TermLogic.Atom(suggestionText.trim()),
                belief: new Belief(1, 0),
                status: Status.PENDING,
                metadata: {
                    root_id: triggerThought.metadata.root_id ?? triggerThought.id,
                    parent_id: triggerThought.id,
                    timestamp_created: new Date().toISOString(),
                    tags: ['suggested_goal'],
                    provenance: 'GoalProposalTool'
                }
            };
            context.engine.addThought(suggestionThought);
            return TermLogic.Atom(`ok:suggestion_created:${suggestionThought.id}`);
        }
        return TermLogic.Atom("ok:no_suggestion_generated");
    }
}

class CoreTool implements Tool {
    name = "CoreTool";
    description = "Handles internal FlowMind operations like setting status or adding thoughts.";

    async execute(actionTerm: Structure, context: ToolContext, triggerThought: Thought): Promise<Term | null> {
        const operation = actionTerm.args[0]?.kind === 'Atom' ? actionTerm.args[0].name : null;
        if (operation === 'set_status') {
            const targetThoughtIdTerm = actionTerm.args[1];
            const newStatusTerm = actionTerm.args[2];
            if (targetThoughtIdTerm?.kind !== 'Atom' || newStatusTerm?.kind !== 'Atom') return TermLogic.Atom("error:invalid_set_status_params");
            const targetId = targetThoughtIdTerm.name;
            const newStatus = newStatusTerm.name as Status;
            if (!Object.values(Status).includes(newStatus)) return TermLogic.Atom("error:invalid_status_value");
            const targetThought = context.thoughtStore.get(targetId);
            if (targetThought) {
                targetThought.status = newStatus;
                context.thoughtStore.update(targetThought);
                return TermLogic.Atom(`ok:status_set:${targetId}_to_${newStatus}`);
            }
            return TermLogic.Atom(`error:target_thought_not_found:${targetId}`);
        } else if (operation === 'add_thought') {
            const typeTerm = actionTerm.args[1];
            const contentTerm = actionTerm.args[2];
            const rootIdTerm = actionTerm.args[3];
            const parentIdTerm = actionTerm.args[4];
            if (typeTerm?.kind !== 'Atom' || !contentTerm) return TermLogic.Atom("error:invalid_add_thought_params");
            const type = typeTerm.name as Type;
            if (!Object.values(Type).includes(type)) return TermLogic.Atom("error:invalid_thought_type");
            const newThought: Thought = {
                id: generateId(),
                type,
                content: contentTerm,
                belief: new Belief(),
                status: Status.PENDING,
                metadata: {
                    root_id: rootIdTerm?.kind === 'Atom' ? rootIdTerm.name : triggerThought.metadata.root_id ?? triggerThought.id,
                    parent_id: parentIdTerm?.kind === 'Atom' ? parentIdTerm.name : triggerThought.id,
                    timestamp_created: new Date().toISOString()
                }
            };
            context.engine.addThought(newThought);
            return TermLogic.Atom(`ok:thought_added:${newThought.id}`);
        }
        return TermLogic.Atom("error:unsupported_core_operation");
    }
}

class ToolRegistry {
    private tools = new Map<string, Tool>();
    register(tool: Tool): void { this.tools.set(tool.name, tool); }
    get(name: string): Tool | undefined { return this.tools.get(name); }
    listTools(): Tool[] { return Array.from(this.tools.values()); }
}

class Engine {
    private activeThoughtIds = new Set<string>();

    constructor(
        private thoughtStore: ThoughtStore,
        private ruleStore: RuleStore,
        private memoryStore: MemoryStore,
        private llmService: LLMService,
        private toolRegistry: ToolRegistry
    ) {}

    addThought(thought: Thought): void { this.thoughtStore.add(thought); }

    private sampleThought(): Thought | null {
        const pending = this.thoughtStore.getPending().filter(t => !this.activeThoughtIds.has(t.id));
        if (pending.length === 0) return null;
        const weights = pending.map(t => t.metadata.priority ?? t.belief.score());
        const totalWeight = weights.reduce((sum, w) => sum + w, 0);
        if (totalWeight <= 0) return pending[Math.floor(Math.random() * pending.length)];
        let random = Math.random() * totalWeight;
        for (let i = 0; i < pending.length; i++) {
            random -= weights[i];
            if (random <= 0) return pending[i];
        }
        return pending[pending.length - 1];
    }

    private findAndSelectRule(thought: Thought): { rule: Rule; bindings: Map<string, Term> } | null {
        const matches = this.ruleStore.getAll()
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
        const boundActionTerm = TermLogic.substitute(rule.action, bindings);
        if (boundActionTerm.kind !== 'Structure') {
            this.handleFailure(thought, rule, `Invalid action term kind: ${boundActionTerm.kind}`);
            return false;
        }
        const tool = this.toolRegistry.get(boundActionTerm.name);
        if (!tool) {
            this.handleFailure(thought, rule, `Tool not found: ${boundActionTerm.name}`);
            return false;
        }
        try {
            const resultTerm = await tool.execute(boundActionTerm, { thoughtStore: this.thoughtStore, ruleStore: this.ruleStore, memoryStore: this.memoryStore, llmService: this.llmService, engine: this }, thought);
            const currentThoughtState = this.thoughtStore.get(thought.id);
            if (currentThoughtState && currentThoughtState.status !== Status.WAITING && currentThoughtState.status !== Status.FAILED) {
                this.handleSuccess(thought, rule);
            }
            if (resultTerm?.kind === 'Atom' && resultTerm.name.startsWith('error:')) {
                this.handleFailure(thought, rule, `Tool execution failed: ${resultTerm.name}`);
                return false;
            }
            if (currentThoughtState?.status !== Status.WAITING && currentThoughtState?.status !== Status.FAILED) {
                rule.belief.update(true);
                this.ruleStore.update(rule);
            }
            return true;
        } catch (error: any) {
            this.handleFailure(thought, rule, `Tool execution exception: ${error.message}`);
            return false;
        }
    }

    private async handleNoRuleMatch(thought: Thought): Promise<void> {
        let prompt = "";
        let targetType: Type | null = null;
        switch (thought.type) {
            case Type.INPUT:
                prompt = `Given the input note: "${TermLogic.termToString(thought.content)}"\n\nDefine a specific, actionable goal. Output only the goal text.`;
                targetType = Type.GOAL;
                break;
            case Type.GOAL:
                prompt = `Given the goal: "${TermLogic.termToString(thought.content)}"\n\nOutline 1-3 concise strategy steps to achieve this goal. Output only the steps, one per line.`;
                targetType = Type.STRATEGY;
                break;
            case Type.STRATEGY:
                prompt = `The strategy step "${TermLogic.termToString(thought.content)}" was performed. Summarize a likely outcome or result. Output only the outcome text.`;
                targetType = Type.OUTCOME;
                break;
            default: {
                const askUserPrompt = `The thought (${thought.type}: ${TermLogic.termToString(thought.content)}) has no applicable rules. What should be done next?`;
                const promptId = generateId();
                const promptThought: Thought = {
                    id: generateId(),
                    type: Type.USER_PROMPT,
                    content: TermLogic.Atom(askUserPrompt),
                    belief: new Belief(),
                    status: Status.PENDING,
                    metadata: {
                        root_id: thought.metadata.root_id ?? thought.id,
                        parent_id: thought.id,
                        timestamp_created: new Date().toISOString(),
                        ui_context: { promptText: askUserPrompt, promptId }
                    }
                };
                this.addThought(promptThought);
                thought.status = Status.WAITING;
                thought.metadata.waiting_for_prompt_id = promptId;
                this.thoughtStore.update(thought);
                return;
            }
        }
        if (prompt && targetType) {
            const resultText = await this.llmService.generate(prompt);
            resultText.split('\n').map(s => s.trim()).filter(s => s).forEach(resText => {
                this.addThought({
                    id: generateId(),
                    type: targetType!,
                    content: TermLogic.Atom(resText),
                    belief: new Belief(),
                    status: Status.PENDING,
                    metadata: {
                        root_id: thought.metadata.root_id ?? thought.id,
                        parent_id: thought.id,
                        timestamp_created: new Date().toISOString(),
                        provenance: 'llm_default_handler'
                    }
                });
            });
            thought.status = Status.DONE;
            thought.belief.update(true);
            this.thoughtStore.update(thought);
        } else if (thought.status === Status.PENDING) {
            this.handleFailure(thought, null, "No matching rule and no default action applicable.");
        }
    }

    private handleFailure(thought: Thought, rule: Rule | null, errorInfo: string): void {
        const retries = (thought.metadata.retries ?? 0) + 1;
        thought.metadata.error_info = errorInfo.substring(0, 200);
        thought.metadata.retries = retries;
        thought.belief.update(false);
        if (rule) {
            rule.belief.update(false);
            this.ruleStore.update(rule);
            thought.metadata.rule_id = rule.id;
        }
        thought.status = retries >= MAX_RETRIES ? Status.FAILED : Status.PENDING;
        this.thoughtStore.update(thought);
    }

    private handleSuccess(thought: Thought, rule: Rule | null): void {
        thought.status = Status.DONE;
        thought.belief.update(true);
        delete thought.metadata.error_info;
        delete thought.metadata.retries;
        if (rule) {
            rule.belief.update(true);
            this.ruleStore.update(rule);
            thought.metadata.rule_id = rule.id;
        }
        this.thoughtStore.update(thought);
    }

    async processSingleThought(): Promise<boolean> {
        const thought = this.sampleThought();
        if (!thought) return false;
        if (this.activeThoughtIds.has(thought.id)) return false;
        this.activeThoughtIds.add(thought.id);
        thought.status = Status.ACTIVE;
        thought.metadata.agent_id = 'worker';
        this.thoughtStore.update(thought);
        let success = false;
        try {
            const match = this.findAndSelectRule(thought);
            if (match) {
                success = await this.executeAction(thought, match.rule, match.bindings);
            } else {
                await this.handleNoRuleMatch(thought);
                success = this.thoughtStore.get(thought.id)?.status === Status.DONE;
            }
        } catch (error: any) {
            this.handleFailure(thought, null, `Unhandled processing exception: ${error.message}`);
        } finally {
            this.activeThoughtIds.delete(thought.id);
            const finalThoughtState = this.thoughtStore.get(thought.id);
            if (finalThoughtState?.status === Status.ACTIVE) {
                this.handleFailure(thought, null, "Processing ended unexpectedly while ACTIVE.");
            }
        }
        return true;
    }

    async handlePromptResponse(promptId: string, responseText: string): Promise<void> {
        const waitingThought = this.thoughtStore.getAll().find(t => t.metadata.waiting_for_prompt_id === promptId && t.status === Status.WAITING);
        if (!waitingThought) return;
        const responseThought: Thought = {
            id: generateId(),
            type: Type.INPUT,
            content: TermLogic.Atom(responseText),
            belief: new Belief(1, 0),
            status: Status.PENDING,
            metadata: {
                root_id: waitingThought.metadata.root_id ?? waitingThought.id,
                parent_id: waitingThought.id,
                timestamp_created: new Date().toISOString(),
                response_to_prompt_id: promptId
            }
        };
        this.addThought(responseThought);
        waitingThought.status = Status.PENDING;
        delete waitingThought.metadata.waiting_for_prompt_id;
        waitingThought.belief.update(true);
        this.thoughtStore.update(waitingThought);
    }
}

async function saveState(thoughtStore: ThoughtStore, ruleStore: RuleStore, memoryStore: MemoryStore): Promise<void> {
    await fs.mkdir(DATA_DIR, { recursive: true });
    const state: AppState = { thoughts: thoughtStore.toJSON(), rules: ruleStore.toJSON() };
    await fs.writeFile(STATE_FILE_PATH, JSON.stringify(state, null, 2));
    await memoryStore.save();
}

const debouncedSaveState = debounce(saveState, 5000);

async function loadState(thoughtStore: ThoughtStore, ruleStore: RuleStore, memoryStore: MemoryStore): Promise<void> {
    if (!fsSync.existsSync(STATE_FILE_PATH)) {
        await memoryStore.initialize();
        return;
    }
    const data = await fs.readFile(STATE_FILE_PATH, 'utf-8');
    const state: AppState = safeJsonParse(data, { thoughts: {}, rules: {} });
    ThoughtStore.fromJSON(state.thoughts).getAll().forEach(t => thoughtStore.add(t));
    RuleStore.fromJSON(state.rules).getAll().forEach(r => ruleStore.add(r));
    await memoryStore.initialize();
}

class ConsoleUI {
    private rl: readline.Interface | null = null;
    private refreshIntervalId: NodeJS.Timeout | null = null;
    private lastRenderedHeight = 0;

    constructor(
        private thoughtStore: ThoughtStore,
        private systemControl: (command: string, args?: string[]) => void
    ) {}

    start(): void {
        this.rl = readline.createInterface({ input: process.stdin, output: process.stdout, prompt: 'FlowMind> ' });
        this.rl.on('line', line => {
            const [command, ...args] = line.trim().split(' ');
            if (command) this.systemControl(command.toLowerCase(), args);
            else this.rl?.prompt();
        });
        this.rl.on('close', () => this.systemControl('quit'));
        this.refreshIntervalId = setInterval(() => this.render(), UI_REFRESH_INTERVAL_MS);
        this.render();
        this.rl.prompt();
    }

    stop(): void {
        if (this.refreshIntervalId) clearInterval(this.refreshIntervalId);
        this.rl?.close();
        process.stdout.write('\nConsole UI stopped.\n');
    }

    private render(): void {
        const thoughts = this.thoughtStore.getAll();
        const rootThoughts = thoughts.filter(t => t.type === Type.INPUT && !t.metadata.parent_id);
        let output = "";
        const termWidth = process.stdout.columns || 80;
        if (this.lastRenderedHeight > 0) {
            process.stdout.write(`\x1b[${this.lastRenderedHeight}A\x1b[J`);
        }
        output += "=".repeat(termWidth) + "\n";
        output += " FlowMind Notes\n";
        output += "=".repeat(termWidth) + "\n";
        const pendingPrompts: Thought[] = [];
        rootThoughts.forEach(root => {
            output += `\nNote: ${TermLogic.formatTerm(root.content).substring(0, termWidth - 15)} [${root.id.substring(0, 6)}] (${root.status})\n`;
            output += "-".repeat(termWidth / 2) + "\n";
            const children = this.thoughtStore.getAllByRootId(root.id).filter(t => t.id !== root.id);
            children.forEach(t => {
                const prefix = `  [${t.id.substring(0, 6)}|${t.type.padEnd(8)}|${t.status.padEnd(7)}|P:${t.belief.score().toFixed(2)}] `;
                const contentStr = TermLogic.formatTerm(t.content);
                const remainingWidth = termWidth - prefix.length - 1;
                const truncatedContent = contentStr.length > remainingWidth ? contentStr.substring(0, remainingWidth - 3) + "..." : contentStr;
                output += prefix + truncatedContent + "\n";
                if (t.metadata.error_info) output += `    Error: ${t.metadata.error_info.substring(0, termWidth - 12)}\n`;
                if (t.type === Type.USER_PROMPT && t.status === Status.PENDING && t.metadata.ui_context?.promptId) pendingPrompts.push(t);
            });
        });
        if (pendingPrompts.length > 0) {
            output += "\n--- Pending Prompts ---\n";
            pendingPrompts.forEach(p => {
                const promptId = p.metadata.ui_context?.promptId ?? 'unknown_id';
                const waitingThoughtId = p.metadata.parent_id;
                output += `[${promptId.substring(0, 6)}] ${TermLogic.formatTerm(p.content)} (Waiting: ${waitingThoughtId?.substring(0, 6) ?? 'N/A'})\n`;
            });
        }
        output += "\n" + "=".repeat(termWidth) + "\n";
        output += "Commands: add <note text>, respond <prompt_id> <response text>, run, pause, step, save, quit\n";
        const outputLines = output.split('\n');
        this.lastRenderedHeight = outputLines.length + 1;
        readline.cursorTo(process.stdout, 0, 0);
        readline.clearScreenDown(process.stdout);
        process.stdout.write(output);
        this.rl?.prompt(true);
    }
}

class FlowMindSystem {
    private thoughtStore = new ThoughtStore();
    private ruleStore = new RuleStore();
    private llmService = new LLMService();
    private memoryStore = new MemoryStore(this.llmService.embeddings, VECTOR_STORE_PATH);
    private toolRegistry = new ToolRegistry();
    private engine = new Engine(this.thoughtStore, this.ruleStore, this.memoryStore, this.llmService, this.toolRegistry);
    private consoleUI = new ConsoleUI(this.thoughtStore, this.handleCommand.bind(this));
    private isRunning = true;
    private workerIntervalId: NodeJS.Timeout | null = null;

    constructor() {
        this.registerCoreTools();
    }

    private registerCoreTools(): void {
        [new LLMTool(), new MemoryTool(), new UserInteractionTool(), new GoalProposalTool(), new CoreTool()]
            .forEach(tool => this.toolRegistry.register(tool));
    }

    private bootstrapRules(): void {
        if (this.ruleStore.getAll().length > 0) return;
        const rules: Omit<Rule, 'belief' | 'metadata'>[] = [
            {
                id: generateId(),
                pattern: TermLogic.Variable("Content"),
                action: TermLogic.Structure("LLMTool", [
                    TermLogic.Atom("generate"),
                    TermLogic.Structure("prompt", [TermLogic.Atom(`Generate a specific GOAL for input: ${TermLogic.formatTerm(TermLogic.Variable("Content"))}`)]),
                    TermLogic.Atom(Type.GOAL)
                ])
            },
            {
                id: generateId(),
                pattern: TermLogic.Variable("Content"),
                action: TermLogic.Structure("LLMTool", [
                    TermLogic.Atom("generate"),
                    TermLogic.Structure("prompt", [TermLogic.Atom(`Generate 1-3 STRATEGY steps for goal: ${TermLogic.formatTerm(TermLogic.Variable("Content"))}`)]),
                    TermLogic.Atom(Type.STRATEGY)
                ])
            },
            {
                id: generateId(),
                pattern: TermLogic.Variable("Content"),
                action: TermLogic.Structure("LLMTool", [
                    TermLogic.Atom("generate"),
                    TermLogic.Structure("prompt", [TermLogic.Atom(`Generate an OUTCOME/result for completing strategy: ${TermLogic.formatTerm(TermLogic.Variable("Content"))}`)]),
                    TermLogic.Atom(Type.OUTCOME)
                ])
            },
            {
                id: generateId(),
                pattern: TermLogic.Variable("Content"),
                action: TermLogic.Structure("MemoryTool", [
                    TermLogic.Atom("add"),
                    TermLogic.Variable("Content"),
                    TermLogic.Variable("ThoughtId"),
                    TermLogic.Atom(Type.OUTCOME)
                ])
            },
            {
                id: generateId(),
                pattern: TermLogic.Variable("Content"),
                action: TermLogic.Structure("GoalProposalTool", [
                    TermLogic.Atom("suggest"),
                    TermLogic.Variable("Content")
                ])
            }
        ];
        rules.forEach((r, i) => this.ruleStore.add({
            ...r,
            belief: new Belief(),
            metadata: { description: `Bootstrap rule ${i + 1}`, provenance: 'bootstrap', timestamp_created: new Date().toISOString() }
        }));
    }

    async initialize(): Promise<void> {
        await fs.mkdir(DATA_DIR, { recursive: true });
        await loadState(this.thoughtStore, this.ruleStore, this.memoryStore);
        this.bootstrapRules();
        this.consoleUI.start();
        this.startProcessingLoop();
    }

    private startProcessingLoop(): void {
        if (this.workerIntervalId) clearInterval(this.workerIntervalId);
        this.workerIntervalId = setInterval(async () => {
            if (this.isRunning) {
                if (await this.engine.processSingleThought()) debouncedSaveState(this.thoughtStore, this.ruleStore, this.memoryStore);
            }
        }, WORKER_INTERVAL_MS);
    }

    private stopProcessingLoop(): void {
        if (this.workerIntervalId) clearInterval(this.workerIntervalId);
        this.workerIntervalId = null;
    }

    private handleCommand(command: string, args: string[] = []): void {
        switch (command) {
            case 'add': {
                const contentText = args.join(' ');
                if (!contentText) {
                    console.log("Usage: add <note text>");
                    return this.consoleUI['rl']?.prompt();
                }
                const newThought: Thought = {
                    id: generateId(),
                    type: Type.INPUT,
                    content: TermLogic.Atom(contentText),
                    belief: new Belief(1, 0),
                    status: Status.PENDING,
                    metadata: { timestamp_created: new Date().toISOString(), root_id: generateId() }
                };
                newThought.metadata.root_id = newThought.id;
                this.engine.addThought(newThought);
                debouncedSaveState(this.thoughtStore, this.ruleStore, this.memoryStore);
                this.consoleUI['rl']?.prompt();
                break;
            }
            case 'respond': {
                const promptId = args[0];
                const responseText = args.slice(1).join(' ');
                if (!promptId || !responseText) {
                    console.log("Usage: respond <prompt_id> <response text>");
                    return this.consoleUI['rl']?.prompt();
                }
                const fullPromptId = this.thoughtStore.getAll()
                    .map(t => t.metadata?.ui_context?.promptId)
                    .find(pid => pid?.startsWith(promptId));
                if (!fullPromptId) {
                    console.log(`Prompt ID starting with "${promptId}" not found.`);
                    return this.consoleUI['rl']?.prompt();
                }
                this.engine.handlePromptResponse(fullPromptId, responseText)
                    .then(() => debouncedSaveState(this.thoughtStore, this.ruleStore, this.memoryStore));
                this.consoleUI['rl']?.prompt();
                break;
            }
            case 'run':
                if (!this.isRunning) {
                    this.isRunning = true;
                    this.startProcessingLoop();
                }
                this.consoleUI['rl']?.prompt();
                break;
            case 'pause':
                if (this.isRunning) {
                    this.isRunning = false;
                    this.stopProcessingLoop();
                }
                this.consoleUI['rl']?.prompt();
                break;
            case 'step':
                if (!this.isRunning) {
                    this.engine.processSingleThought()
                        .then(processed => {
                            if (processed) debouncedSaveState(this.thoughtStore, this.ruleStore, this.memoryStore);
                            this.consoleUI['rl']?.prompt();
                        });
                } else {
                    console.log("System must be paused to step.");
                    this.consoleUI['rl']?.prompt();
                }
                break;
            case 'save':
                debouncedSaveState.cancel();
                saveState(this.thoughtStore, this.ruleStore, this.memoryStore)
                    .finally(() => this.consoleUI['rl']?.prompt());
                break;
            case 'quit':
            case 'exit':
                this.shutdown();
                break;
            default:
                console.log(`Unknown command: ${command}`);
                this.consoleUI['rl']?.prompt();
        }
    }

    async shutdown(): Promise<void> {
        this.isRunning = false;
        this.stopProcessingLoop();
        this.consoleUI.stop();
        debouncedSaveState.cancel();
        await saveState(this.thoughtStore, this.ruleStore, this.memoryStore);
        process.exit(0);
    }
}

async function main() {
    const system = new FlowMindSystem();
    process.on('SIGINT', () => system.shutdown());
    process.on('SIGTERM', () => system.shutdown());
    await system.initialize();
}

main().catch(error => {
    console.error("Critical error during system execution:", error);
    process.exit(1);
});