import * as fs from 'fs/promises';
import * as fsSync from 'fs';
import * as path from 'path';
import * as os from 'os';
import * as readline from 'readline';
import { v4 as uuidv4 } from 'uuid';
import chalk from 'chalk';
import { ChatOllama } from "@langchain/community/chat_models/ollama";
import { OllamaEmbeddings } from "@langchain/community/embeddings/ollama";
import { HumanMessage } from "@langchain/core/messages";
import { StringOutputParser } from "@langchain/core/output_parsers";
import { Document } from "@langchain/core/documents";
import { FaissStore } from "@langchain/community/vectorstores/faiss";

const DATA_DIR = path.join(os.homedir(), '.flowmind');
const STATE_FILE = path.join(DATA_DIR, 'state.json');
const VECTOR_STORE = path.join(DATA_DIR, 'vector-store');
const OLLAMA_URL = process.env.OLLAMA_HOST || "http://localhost:11434";
const MODEL = "llamablit";
const WORKER_INTERVAL = 2000;
const UI_REFRESH = 1000;
const MAX_RETRIES = 2;
const DEF_BELIEF_POS = 1.0;
const DEF_BELIEF_NEG = 1.0;

enum Status { PENDING = 'PENDING', ACTIVE = 'ACTIVE', WAITING = 'WAITING', DONE = 'DONE', FAILED = 'FAILED' }
enum Type { INPUT = 'INPUT', GOAL = 'GOAL', STRATEGY = 'STRATEGY', OUTCOME = 'OUTCOME', QUERY = 'QUERY', PROMPT = 'PROMPT', EVENT = 'EVENT' }

type Term = Atom | Var | Struct | List;
interface Atom { kind: 'Atom'; name: string; }
interface Var { kind: 'Var'; name: string; }
interface Struct { kind: 'Struct'; name: string; args: Term[]; }
interface List { kind: 'List'; items: Term[]; }
interface BeliefData { pos: number; neg: number; }
interface Meta {
    root_id?: string;
    parent_id?: string;
    rule_id?: string;
    agent_id?: string;
    created_at?: string;
    updated_at?: string;
    priority?: number;
    error?: string;
    ui?: string | { text?: string, prompt_id?: string };
    tags?: string[];
    feedback?: any[];
    embedded_at?: string;
    suggestions?: string[];
    retries?: number;
    waiting_prompt?: string;
    response_to?: string;
    [key: string]: any;
}
interface Thought {
    id: string;
    type: Type;
    content: Term;
    belief: Belief;
    status: Status;
    meta: Meta;
}
interface Rule {
    id: string;
    pattern: Term;
    action: Term;
    belief: Belief;
    meta: { desc?: string; source?: string; created_at?: string; };
}
interface MemoryEntry {
    id: string;
    embedding: number[];
    content: string;
    meta: { ts: string; type: string; source_id: string; };
}
interface ToolCtx {
    thoughts: ThoughtStore;
    rules: RuleStore;
    memory: MemoryStore;
    llm: LLMService;
    engine: Engine;
}
interface Tool {
    name: string;
    desc: string;
    exec(action: Struct, ctx: ToolCtx, thought: Thought): Promise<Term | null>;
}
interface State {
    thoughts: Record<string, any>;
    rules: Record<string, any>;
}

class Belief implements BeliefData {
    pos: number;
    neg: number;

    constructor(pos: number = DEF_BELIEF_POS, neg: number = DEF_BELIEF_NEG) {
        this.pos = pos;
        this.neg = neg;
    }

    score(): number { return (this.pos + 1) / (this.pos + this.neg + 2); }
    update(success: boolean): void { success ? this.pos++ : this.neg++; }
    toJSON(): BeliefData { return { pos: this.pos, neg: this.neg }; }
    static fromJSON(data: BeliefData): Belief { return new Belief(data.pos, data.neg); }
    static DEF = new Belief();
}

namespace Terms {
    export const Atom = (name: string): Atom => ({ kind: 'Atom', name });
    export const Var = (name: string): Var => ({ kind: 'Var', name });
    export const Struct = (name: string, args: Term[]): Struct => ({ kind: 'Struct', name, args });
    export const List = (items: Term[]): List => ({ kind: 'List', items });

    export function format(term: Term | null | undefined): string {
        if (!term) return 'null';
        switch (term.kind) {
            case 'Atom': return term.name;
            case 'Var': return `?${term.name}`;
            case 'Struct': return `${term.name}(${term.args.map(format).join(', ')})`;
            case 'List': return `[${term.items.map(format).join(', ')}]`;
            default: return 'invalid';
        }
    }

    export function toStr(term: Term): string {
        switch (term.kind) {
            case 'Atom': return term.name;
            case 'Var': return `?${term.name}`;
            case 'Struct': return `${term.name}: ${term.args.map(toStr).join('; ')}`;
            case 'List': return term.items.map(toStr).join(', ');
            default: return '';
        }
    }

    export function fromStr(input: string): Term {
        if (input.includes(':') && input.includes(';')) {
            const [name, argsStr] = input.split(':', 2);
            const args = argsStr.split(';').map(s => Atom(s.trim()));
            return Struct(name.trim(), args);
        }
        if (input.startsWith('[') && input.endsWith(']')) {
            const items = input.slice(1, -1).split(',').map(s => Atom(s.trim()));
            return List(items);
        }
        return Atom(input);
    }

    export function unify(t1: Term, t2: Term, binds: Map<string, Term> = new Map()): Map<string, Term> | null {
        const resolve = (term: Term, current: Map<string, Term>): Term => {
            if (term.kind === 'Var' && current.has(term.name)) return resolve(current.get(term.name)!, current);
            return term;
        };

        const r1 = resolve(t1, binds);
        const r2 = resolve(t2, binds);

        if (r1.kind === 'Var') {
            if (r1.name === (r2 as Var).name && r1.kind === r2.kind) return binds;
            const newBinds = new Map(binds);
            newBinds.set(r1.name, r2);
            return newBinds;
        }
        if (r2.kind === 'Var') {
            const newBinds = new Map(binds);
            newBinds.set(r2.name, r1);
            return newBinds;
        }
        if (r1.kind !== r2.kind) return null;

        switch (r1.kind) {
            case 'Atom': return r1.name === (r2 as Atom).name ? binds : null;
            case 'Struct': {
                const s2 = r2 as Struct;
                if (r1.name !== s2.name || r1.args.length !== s2.args.length) return null;
                let current = binds;
                for (let i = 0; i < r1.args.length; i++) {
                    const res = unify(r1.args[i], s2.args[i], current);
                    if (!res) return null;
                    current = res;
                }
                return current;
            }
            case 'List': {
                const l2 = r2 as List;
                if (r1.items.length !== l2.items.length) return null;
                let current = binds;
                for (let i = 0; i < r1.items.length; i++) {
                    const res = unify(r1.items[i], l2.items[i], current);
                    if (!res) return null;
                    current = res;
                }
                return current;
            }
        }
        return null;
    }

    export function subst(term: Term, binds: Map<string, Term>): Term {
        if (term.kind === 'Var' && binds.has(term.name)) return subst(binds.get(term.name)!, binds);
        if (term.kind === 'Struct') return { ...term, args: term.args.map(a => subst(a, binds)) };
        if (term.kind === 'List') return { ...term, items: term.items.map(e => subst(e, binds)) };
        return term;
    }
}

function genId(): string { return uuidv4(); }

function debounce<T extends (...args: any[]) => any>(fn: T, wait: number): (...args: Parameters<T>) => void {
    let timeout: NodeJS.Timeout | null = null;
    return (...args: Parameters<T>): void => {
        const later = () => { timeout = null; fn(...args); };
        if (timeout) clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
}

function parseJSON<T>(json: string | null | undefined, def: T): T {
    if (!json) return def;
    try { return JSON.parse(json); } catch { return def; }
}

function sleep(ms: number): Promise<void> { return new Promise(resolve => setTimeout(resolve, ms)); }

class ThoughtStore {
    private thoughts = new Map<string, Thought>();
    private listeners: (() => void)[] = [];

    add(t: Thought): void {
        this.thoughts.set(t.id, t);
        this.notify();
    }

    get(id: string): Thought | undefined { return this.thoughts.get(id); }

    update(t: Thought): void {
        if (this.thoughts.has(t.id)) {
            this.thoughts.set(t.id, {
                ...t,
                meta: { ...t.meta, updated_at: new Date().toISOString() }
            });
            this.notify();
        }
    }

    delete(id: string): void {
        if (this.thoughts.has(id)) {
            this.thoughts.delete(id);
            this.notify();
        }
    }

    pending(): Thought[] {
        return Array.from(this.thoughts.values()).filter(t => t.status === Status.PENDING);
    }

    all(): Thought[] {
        return Array.from(this.thoughts.values());
    }

    byRoot(rootId: string): Thought[] {
        return this.all()
            .filter(t => t.meta.root_id === rootId || t.id === rootId)
            .sort((a, b) => Date.parse(a.meta.created_at ?? '0') - Date.parse(b.meta.created_at ?? '0'));
    }

    byTag(tag: string): Thought[] {
        return this.all().filter(t => t.meta.tags?.includes(tag));
    }

    toJSON(): Record<string, any> {
        const obj: Record<string, any> = {};
        this.thoughts.forEach((t, id) => obj[id] = { ...t, belief: t.belief.toJSON() });
        return obj;
    }

    static fromJSON(data: Record<string, any>): ThoughtStore {
        const store = new ThoughtStore();
        for (const id in data) {
            store.add({ ...data[id], belief: Belief.fromJSON(data[id].belief) });
        }
        return store;
    }

    onChange(l: () => void): void { this.listeners.push(l); }
    offChange(l: () => void): void { this.listeners = this.listeners.filter(x => x !== l); }
    private notify(): void { this.listeners.forEach(l => l()); }
}

class RuleStore {
    private rules = new Map<string, Rule>();

    add(r: Rule): void { this.rules.set(r.id, r); }
    get(id: string): Rule | undefined { return this.rules.get(id); }
    update(r: Rule): void { if (this.rules.has(r.id)) this.rules.set(r.id, r); }
    delete(id: string): void { this.rules.delete(id); }
    all(): Rule[] { return Array.from(this.rules.values()); }
    byDesc(desc: string): Rule[] {
        return this.all().filter(r => r.meta.desc?.toLowerCase().includes(desc.toLowerCase()));
    }

    toJSON(): Record<string, any> {
        const obj: Record<string, any> = {};
        this.rules.forEach((r, id) => obj[id] = { ...r, belief: r.belief.toJSON() });
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
    private store: FaissStore | null = null;
    private ready = false;

    constructor(private emb: OllamaEmbeddings, private path: string) {}

    async init(): Promise<void> {
        try {
            await fs.access(this.path);
            this.store = await FaissStore.load(this.path, this.emb);
            this.ready = true;
        } catch (e: any) {
            if (e.code === 'ENOENT') {
                const doc = new Document({
                    pageContent: "FlowMind init",
                    metadata: { source_id: "init", type: "system", ts: new Date().toISOString() }
                });
                this.store = await FaissStore.fromDocuments([doc], this.emb);
                await this.store.save(this.path);
                this.ready = true;
            }
        }
    }

    async add(e: Omit<MemoryEntry, 'embedding'>): Promise<void> {
        if (!this.store || !this.ready) return;
        const doc = new Document({ pageContent: e.content, metadata: { ...e.meta, id: e.id } });
        await this.store.addDocuments([doc]);
        await this.save();
    }

    async search(q: string, k: number = 5): Promise<MemoryEntry[]> {
        if (!this.store || !this.ready) return [];
        const res = await this.store.similaritySearchWithScore(q, k);
        return res.map(([doc]) => ({
            id: doc.metadata.id || genId(),
            embedding: [],
            content: doc.pageContent,
            meta: doc.metadata as MemoryEntry['meta']
        }));
    }

    async save(): Promise<void> {
        if (this.store && this.ready) await this.store.save(this.path);
    }
}

class LLMService {
    private llm: ChatOllama;

    constructor() {
        this.llm = new ChatOllama({ baseUrl: OLLAMA_URL, model: MODEL, temperature: 0.7 });
    }

    async gen(p: string): Promise<string> {
        return this.llm.pipe(new StringOutputParser()).invoke([new HumanMessage(p)]);
    }

    get emb(): OllamaEmbeddings {
        return new OllamaEmbeddings({ model: MODEL, baseUrl: OLLAMA_URL });
    }
}

class LLMTool implements Tool {
    name = "LLMTool";
    desc = "Handles LLM generation tasks.";

    async exec(action: Struct, ctx: ToolCtx, t: Thought): Promise<Term | null> {
        const op = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        const p = action.args[1];
        if (!op || !p) return Terms.Atom("err:no_params");
        const txt = Terms.toStr(p);
        if (op === 'gen') {
            const res = await ctx.llm.gen(txt);
            return Terms.Atom(res);
        }
        return Terms.Atom("err:bad_op");
    }
}

class MemoryTool implements Tool {
    name = "MemoryTool";
    desc = "Manages memory operations.";

    async exec(action: Struct, ctx: ToolCtx, t: Thought): Promise<Term | null> {
        const op = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (!op) return Terms.Atom("err:no_op");

        if (op === 'add') {
            const c = action.args[1];
            if (!c) return Terms.Atom("err:no_content");
            const txt = Terms.toStr(c);
            const src = action.args[2]?.kind === 'Atom' ? action.args[2].name : t.id;
            const type = action.args[3]?.kind === 'Atom' ? action.args[3].name : t.type;
            await ctx.memory.add({
                id: genId(),
                content: txt,
                meta: { ts: new Date().toISOString(), type, source_id: src }
            });
            t.meta.embedded_at = new Date().toISOString();
            ctx.thoughts.update(t);
            return Terms.Atom("ok:added");
        } else if (op === 'search') {
            const q = action.args[1];
            const k = action.args[2]?.kind === 'Atom' ? parseInt(action.args[2].name, 10) : 3;
            if (!q) return Terms.Atom("err:no_query");
            const txt = Terms.toStr(q);
            const res = await ctx.memory.search(txt, isNaN(k) ? 3 : k);
            return Terms.List(res.map(r => Terms.Atom(r.content)));
        }
        return Terms.Atom("err:bad_op");
    }
}

class PromptTool implements Tool {
    name = "PromptTool";
    desc = "Manages user prompts.";

    async exec(action: Struct, ctx: ToolCtx, t: Thought): Promise<Term | null> {
        const op = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (op !== 'prompt') return Terms.Atom("err:bad_op");

        const txt = action.args[1];
        if (!txt) return Terms.Atom("err:no_text");

        const pTxt = Terms.toStr(txt);
        const pId = genId();
        const pThought: Thought = {
            id: genId(),
            type: Type.PROMPT,
            content: Terms.Atom(pTxt),
            belief: new Belief(),
            status: Status.PENDING,
            meta: {
                root_id: t.meta.root_id ?? t.id,
                parent_id: t.id,
                created_at: new Date().toISOString(),
                ui: { text: pTxt, prompt_id: pId }
            }
        };

        ctx.engine.addThought(pThought);
        t.status = Status.WAITING;
        t.meta.waiting_prompt = pId;
        ctx.thoughts.update(t);
        return Terms.Atom(`ok:prompt:${pId}`);
    }
}

class GoalTool implements Tool {
    name = "GoalTool";
    desc = "Suggests new goals.";

    async exec(action: Struct, ctx: ToolCtx, t: Thought): Promise<Term | null> {
        const op = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (op !== 'suggest') return Terms.Atom("err:bad_op");

        const c = action.args[1] ?? t.content;
        const txt = Terms.toStr(c);
        const mem = await ctx.memory.search(`Goals related to: ${txt}`, 3);
        const memTxt = mem.map(r => r.content).join("\n - ");

        const p = `Context: "${txt}"\nPast: ${memTxt}\n\nSuggest ONE actionable goal.`;
        const sug = await ctx.llm.gen(p);

        if (sug && sug.trim()) {
            const sThought: Thought = {
                id: genId(),
                type: Type.INPUT,
                content: Terms.Atom(sug.trim()),
                belief: new Belief(1, 0),
                status: Status.PENDING,
                meta: {
                    root_id: t.meta.root_id ?? t.id,
                    parent_id: t.id,
                    created_at: new Date().toISOString(),
                    tags: ['suggested'],
                    source: 'GoalTool'
                }
            };
            ctx.engine.addThought(sThought);
            return Terms.Atom(`ok:suggested:${sThought.id}`);
        }
        return Terms.Atom("ok:no_suggestion");
    }
}

class CoreTool implements Tool {
    name = "CoreTool";
    desc = "Handles system operations.";

    async exec(action: Struct, ctx: ToolCtx, t: Thought): Promise<Term | null> {
        const op = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;

        if (op === 'set_status') {
            const tId = action.args[1];
            const s = action.args[2];
            if (tId?.kind !== 'Atom' || s?.kind !== 'Atom') return Terms.Atom("err:bad_params");
            const id = tId.name;
            const stat = s.name as Status;
            if (!Object.values(Status).includes(stat)) return Terms.Atom("err:bad_status");
            const target = ctx.thoughts.get(id);
            if (target) {
                target.status = stat;
                ctx.thoughts.update(target);
                return Terms.Atom(`ok:status:${id}_${stat}`);
            }
            return Terms.Atom(`err:no_thought:${id}`);
        } else if (op === 'add_thought') {
            const type = action.args[1];
            const c = action.args[2];
            const rId = action.args[3];
            const pId = action.args[4];
            if (type?.kind !== 'Atom' || !c) return Terms.Atom("err:bad_params");
            const tType = type.name as Type;
            if (!Object.values(Type).includes(tType)) return Terms.Atom("err:bad_type");
            const nThought: Thought = {
                id: genId(),
                type: tType,
                content: c,
                belief: new Belief(),
                status: Status.PENDING,
                meta: {
                    root_id: rId?.kind === 'Atom' ? rId.name : t.meta.root_id ?? t.id,
                    parent_id: pId?.kind === 'Atom' ? pId.name : t.id,
                    created_at: new Date().toISOString()
                }
            };
            ctx.engine.addThought(nThought);
            return Terms.Atom(`ok:added:${nThought.id}`);
        } else if (op === 'delete_thought') {
            const tId = action.args[1];
            if (tId?.kind !== 'Atom') return Terms.Atom("err:bad_params");
            const id = tId.name;
            const target = ctx.thoughts.get(id);
            if (target) {
                ctx.thoughts.delete(id);
                return Terms.Atom(`ok:deleted:${id}`);
            }
            return Terms.Atom(`err:no_thought:${id}`);
        }
        return Terms.Atom("err:bad_op");
    }
}

class Tools {
    private tools = new Map<string, Tool>();

    add(t: Tool): void { this.tools.set(t.name, t); }
    get(n: string): Tool | undefined { return this.tools.get(n); }
    list(): Tool[] { return Array.from(this.tools.values()); }
    remove(n: string): void { this.tools.delete(n); }
}

class Engine {
    private active = new Set<string>();
    private batchSize = 5;
    private maxConc = 3;

    constructor(
        private thoughts: ThoughtStore,
        private rules: RuleStore,
        private memory: MemoryStore,
        private llm: LLMService,
        private tools: Tools
    ) {}

    addThought(t: Thought): void { this.thoughts.add(t); }

    private pick(): Thought | null {
        const p = this.thoughts.pending().filter(t => !this.active.has(t.id));
        if (p.length === 0) return null;
        const w = p.map(t => t.meta.priority ?? t.belief.score());
        const total = w.reduce((s, w) => s + w, 0);
        if (total <= 0) return p[Math.floor(Math.random() * p.length)];
        let r = Math.random() * total;
        for (let i = 0; i < p.length; i++) {
            r -= w[i];
            if (r <= 0) return p[i];
        }
        return p[p.length - 1];
    }

    private match(t: Thought): { rule: Rule; binds: Map<string, Term> } | null {
        const m = this.rules.all()
            .map(r => ({ rule: r, binds: Terms.unify(r.pattern, t.content) }))
            .filter(m => m.binds !== null) as { rule: Rule; binds: Map<string, Term> }[];
        if (m.length === 0) return null;
        if (m.length === 1) return m[0];
        const w = m.map(m => m.rule.belief.score());
        const total = w.reduce((s, w) => s + w, 0);
        if (total <= 0) return m[Math.floor(Math.random() * m.length)];
        let r = Math.random() * total;
        for (let i = 0; i < m.length; i++) {
            r -= w[i];
            if (r <= 0) return m[i];
        }
        return m[m.length - 1];
    }

    private async run(t: Thought, r: Rule, b: Map<string, Term>): Promise<boolean> {
        const a = Terms.subst(r.action, b);
        if (a.kind !== 'Struct') {
            this.fail(t, r, `Bad action: ${a.kind}`);
            return false;
        }
        const tool = this.tools.get(a.name);
        if (!tool) {
            this.fail(t, r, `No tool: ${a.name}`);
            return false;
        }
        try {
            const res = await tool.exec(a, {
                thoughts: this.thoughts,
                rules: this.rules,
                memory: this.memory,
                llm: this.llm,
                engine: this
            }, t);
            const curr = this.thoughts.get(t.id);
            if (curr && curr.status !== Status.WAITING && curr.status !== Status.FAILED) {
                this.succeed(t, r);
            }
            if (res?.kind === 'Atom' && res.name.startsWith('err:')) {
                this.fail(t, r, `Tool failed: ${res.name}`);
                return false;
            }
            if (curr?.status !== Status.WAITING && curr?.status !== Status.FAILED) {
                r.belief.update(true);
                this.rules.update(r);
            }
            return true;
        } catch (e: any) {
            this.fail(t, r, `Tool error: ${e.message}`);
            return false;
        }
    }

    private async noMatch(t: Thought): Promise<void> {
        let p = "";
        let type: Type | null = null;

        switch (t.type) {
            case Type.INPUT:
                p = `Input: "${Terms.toStr(t.content)}"\n\nDefine a goal.`;
                type = Type.GOAL;
                break;
            case Type.GOAL:
                p = `Goal: "${Terms.toStr(t.content)}"\n\nList 1-3 strategies.`;
                type = Type.STRATEGY;
                break;
            case Type.STRATEGY:
                p = `Strategy: "${Terms.toStr(t.content)}"\n\nPredict outcome.`;
                type = Type.OUTCOME;
                break;
            default: {
                const txt = `Thought (${t.type}: ${Terms.toStr(t.content)}) has no rules. Next?`;
                const pId = genId();
                const pThought: Thought = {
                    id: genId(),
                    type: Type.PROMPT,
                    content: Terms.Atom(txt),
                    belief: new Belief(),
                    status: Status.PENDING,
                    meta: {
                        root_id: t.meta.root_id ?? t.id,
                        parent_id: t.id,
                        created_at: new Date().toISOString(),
                        ui: { text: txt, prompt_id: pId }
                    }
                };
                this.addThought(pThought);
                t.status = Status.WAITING;
                t.meta.waiting_prompt = pId;
                this.thoughts.update(t);
                return;
            }
        }

        if (p && type) {
            const res = await this.llm.gen(p);
            res.split('\n').map(s => s.trim()).filter(s => s).forEach(txt => {
                this.addThought({
                    id: genId(),
                    type,
                    content: Terms.Atom(txt),
                    belief: new Belief(),
                    status: Status.PENDING,
                    meta: {
                        root_id: t.meta.root_id ?? t.id,
                        parent_id: t.id,
                        created_at: new Date().toISOString(),
                        source: 'llm'
                    }
                });
            });
            t.status = Status.DONE;
            t.belief.update(true);
            this.thoughts.update(t);
        } else if (t.status === Status.PENDING) {
            this.fail(t, null, "No rule or action.");
        }
    }

    private fail(t: Thought, r: Rule | null, e: string): void {
        const retries = (t.meta.retries ?? 0) + 1;
        t.meta.error = e.substring(0, 200);
        t.meta.retries = retries;
        t.belief.update(false);
        if (r) {
            r.belief.update(false);
            this.rules.update(r);
            t.meta.rule_id = r.id;
        }
        t.status = retries >= MAX_RETRIES ? Status.FAILED : Status.PENDING;
        this.thoughts.update(t);
    }

    private succeed(t: Thought, r: Rule | null): void {
        t.status = Status.DONE;
        t.belief.update(true);
        delete t.meta.error;
        delete t.meta.retries;
        if (r) {
            r.belief.update(true);
            this.rules.update(r);
            t.meta.rule_id = r.id;
        }
        this.thoughts.update(t);
    }

    async processOne(): Promise<boolean> {
        const t = this.pick();
        if (!t) return false;
        if (this.active.has(t.id)) return false;

        this.active.add(t.id);
        t.status = Status.ACTIVE;
        t.meta.agent_id = 'worker';
        this.thoughts.update(t);

        let ok = false;
        try {
            const m = this.match(t);
            if (m) {
                ok = await this.run(t, m.rule, m.binds);
            } else {
                await this.noMatch(t);
                ok = this.thoughts.get(t.id)?.status === Status.DONE;
            }
        } catch (e: any) {
            this.fail(t, null, `Error: ${e.message}`);
        } finally {
            this.active.delete(t.id);
            const curr = this.thoughts.get(t.id);
            if (curr?.status === Status.ACTIVE) {
                this.fail(t, null, "Unexpected end.");
            }
        }
        return ok;
    }

    async processBatch(): Promise<number> {
        let count = 0;
        const toProc: Thought[] = [];

        while (count < this.batchSize && this.active.size < this.maxConc) {
            const t = this.pick();
            if (!t) break;
            toProc.push(t);
            this.active.add(t.id);
            count++;
        }

        const res = await Promise.all(toProc.map(async t => {
            t.status = Status.ACTIVE;
            t.meta.agent_id = 'worker';
            this.thoughts.update(t);
            try {
                const m = this.match(t);
                if (m) {
                    return await this.run(t, m.rule, m.binds);
                } else {
                    await this.noMatch(t);
                    return this.thoughts.get(t.id)?.status === Status.DONE;
                }
            } catch (e: any) {
                this.fail(t, null, `Error: ${e.message}`);
                return false;
            } finally {
                this.active.delete(t.id);
                const curr = this.thoughts.get(t.id);
                if (curr?.status === Status.ACTIVE) {
                    this.fail(t, null, "Unexpected end.");
                }
            }
        }));

        return res.filter(r => r).length;
    }

    async handlePrompt(pId: string, txt: string): Promise<void> {
        const t = this.thoughts.all().find(t => t.meta.waiting_prompt === pId && t.status === Status.WAITING);
        if (!t) return;

        const rThought: Thought = {
            id: genId(),
            type: Type.INPUT,
            content: Terms.Atom(txt),
            belief: new Belief(1, 0),
            status: Status.PENDING,
            meta: {
                root_id: t.meta.root_id ?? t.id,
                parent_id: t.id,
                created_at: new Date().toISOString(),
                response_to: pId,
                tags: ['user']
            }
        };

        this.addThought(rThought);
        t.status = Status.PENDING;
        delete t.meta.waiting_prompt;
        t.belief.update(true);
        this.thoughts.update(t);
    }
}

async function save(thoughts: ThoughtStore, rules: RuleStore, mem: MemoryStore): Promise<void> {
    await fs.mkdir(DATA_DIR, { recursive: true });
    const s: State = {
        thoughts: thoughts.toJSON(),
        rules: rules.toJSON()
    };
    await fs.writeFile(STATE_FILE, JSON.stringify(s, null, 2));
    await mem.save();
}

const saveDeb = debounce(save, 5000);

async function load(thoughts: ThoughtStore, rules: RuleStore, mem: MemoryStore): Promise<void> {
    if (!fsSync.existsSync(STATE_FILE)) {
        await mem.init();
        return;
    }
    const d = await fs.readFile(STATE_FILE, 'utf-8');
    const s: State = parseJSON(d, { thoughts: {}, rules: {} });
    ThoughtStore.fromJSON(s.thoughts).all().forEach(t => thoughts.add(t));
    RuleStore.fromJSON(s.rules).all().forEach(r => rules.add(r));
    await mem.init();
}

class UI {
    private rl: readline.Interface | null = null;
    private timer: NodeJS.Timeout | null = null;
    private height = 0;
    private paused = false;

    constructor(
        private thoughts: ThoughtStore,
        private cmd: (c: string, a?: string[]) => void
    ) {
        thoughts.onChange(() => this.draw());
    }

    start(): void {
        this.rl = readline.createInterface({
            input: process.stdin,
            output: process.stdout,
            prompt: chalk.cyan.bold('FlowMind 🌟> ')
        });

        this.rl.on('line', l => {
            const [c, ...a] = l.trim().split(' ');
            if (c) this.cmd(c.toLowerCase(), a);
            else this.rl?.prompt();
        });

        this.rl.on('close', () => this.cmd('quit'));
        this.timer = setInterval(() => this.draw(), UI_REFRESH);
        this.draw();
        this.rl.prompt();
    }

    stop(): void {
        if (this.timer) clearInterval(this.timer);
        this.rl?.close();
        process.stdout.write(chalk.gray('\nFlowMind stopped 🚪\n'));
    }

    private draw(): void {
        const t = this.thoughts.all();
        const roots = t.filter(x => x.type === Type.INPUT && !x.meta.parent_id);
        let out = "";
        const w = process.stdout.columns || 80;

        if (this.height > 0) {
            process.stdout.write(`\x1b[${this.height}A\x1b[J`);
        }

        out += chalk.magenta('═'.repeat(w)) + "\n";
        out += chalk.bold(` 🌈 FlowMind ${this.paused ? chalk.red('[PAUSED 😴]') : chalk.green('[RUNNING 🚀]')}\n`);
        out += chalk.magenta('═'.repeat(w)) + "\n";

        const prompts: Thought[] = [];
        roots.forEach(r => {
            out += `\n${chalk.yellow('📝 Note:')} ${Terms.format(r.content).substring(0, w - 20)} ${chalk.gray(`[${r.id.substring(0, 6)}]`)} ${this.status(r.status)}\n`;
            out += chalk.gray('-'.repeat(w / 2)) + "\n";

            const kids = this.thoughts.byRoot(r.id).filter(x => x.id !== r.id);
            kids.forEach(k => {
                const p = `  ${chalk.gray(`[${k.id.substring(0, 6)}|${k.type.padEnd(8)}|${k.status.padEnd(7)}|P:${k.belief.score().toFixed(2)}]`)} `;
                const c = Terms.format(k.content);
                const maxW = w - p.length - 1;
                const txt = c.length > maxW ? c.substring(0, maxW - 3) + "..." : c;
                out += p + chalk.white(txt) + "\n";

                if (k.meta.error)
                    out += `    ${chalk.red('⚠ Error:')} ${k.meta.error.substring(0, w - 15)}\n`;

                if (k.meta.tags?.length)
                    out += `    ${chalk.cyan('🏷 Tags:')} ${k.meta.tags.join(', ')}\n`;

                if (k.type === Type.PROMPT && k.status === Status.PENDING && k.meta.ui?.prompt_id)
                    prompts.push(k);
            });
        });

        if (prompts.length > 0) {
            out += "\n" + chalk.yellow('📬 Pending Prompts') + "\n";
            prompts.forEach(p => {
                const id = p.meta.ui?.prompt_id ?? 'unknown';
                const parent = p.meta.parent_id;
                out += `${chalk.gray(`[${id.substring(0, 6)}]`)} ${Terms.format(p.content)} ${chalk.gray(`(Waiting: ${parent?.substring(0, 6) ?? 'N/A'})`)}\n`;
            });
        }

        out += "\n" + chalk.magenta('═'.repeat(w)) + "\n";
        out += chalk.blue('🔧 Commands:') + " add <note>, respond <prompt_id> <text>, run, pause, step, save, quit, list, tag <id> <tag>, search <query>, delete <id>, clear, help\n";

        const lines = out.split('\n');
        this.height = lines.length + 1;
        readline.cursorTo(process.stdout, 0, 0);
        readline.clearScreenDown(process.stdout);
        process.stdout.write(out);
        this.rl?.prompt(true);
    }

    private status(s: Status): string {
        switch (s) {
            case Status.PENDING: return chalk.yellow('⏳ Pending');
            case Status.ACTIVE: return chalk.blue('⚙ Active');
            case Status.WAITING: return chalk.cyan('⌛ Waiting');
            case Status.DONE: return chalk.green('✅ Done');
            case Status.FAILED: return chalk.red('❌ Failed');
        }
    }

    setPaused(p: boolean): void {
        this.paused = p;
        this.draw();
    }
}

class FlowMind {
    private thoughts = new ThoughtStore();
    private rules = new RuleStore();
    private llm = new LLMService();
    private mem = new MemoryStore(this.llm.emb, VECTOR_STORE);
    private tools = new Tools();
    private engine = new Engine(this.thoughts, this.rules, this.mem, this.llm, this.tools);
    private ui = new UI(this.thoughts, this.handleCmd.bind(this));
    private running = true;
    private worker: NodeJS.Timeout | null = null;

    constructor() {
        this.initTools();
    }

    private initTools(): void {
        [new LLMTool(), new MemoryTool(), new PromptTool(), new GoalTool(), new CoreTool()]
            .forEach(t => this.tools.add(t));
    }

    private initRules(): void {
        if (this.rules.all().length > 0) return;
        const rules: Omit<Rule, 'belief' | 'meta'>[] = [
            {
                id: genId(),
                pattern: Terms.Var("C"),
                action: Terms.Struct("LLMTool", [
                    Terms.Atom("gen"),
                    Terms.Struct("prompt", [Terms.Atom(`Goal for: ${Terms.format(Terms.Var("C"))}`)]),
                    Terms.Atom(Type.GOAL)
                ])
            },
            {
                id: genId(),
                pattern: Terms.Var("C"),
                action: Terms.Struct("LLMTool", [
                    Terms.Atom("gen"),
                    Terms.Struct("prompt", [Terms.Atom(`Strategies for: ${Terms.format(Terms.Var("C"))}`)]),
                    Terms.Atom(Type.STRATEGY)
                ])
            },
            {
                id: genId(),
                pattern: Terms.Var("C"),
                action: Terms.Struct("LLMTool", [
                    Terms.Atom("gen"),
                    Terms.Struct("prompt", [Terms.Atom(`Outcome for: ${Terms.format(Terms.Var("C"))}`)]),
                    Terms.Atom(Type.OUTCOME)
                ])
            },
            {
                id: genId(),
                pattern: Terms.Var("C"),
                action: Terms.Struct("MemoryTool", [
                    Terms.Atom("add"),
                    Terms.Var("C"),
                    Terms.Var("TId"),
                    Terms.Atom(Type.OUTCOME)
                ])
            },
            {
                id: genId(),
                pattern: Terms.Var("C"),
                action: Terms.Struct("GoalTool", [
                    Terms.Atom("suggest"),
                    Terms.Var("C")
                ])
            }
        ];

        rules.forEach((r, i) => this.rules.add({
            ...r,
            belief: new Belief(),
            meta: {
                desc: `Rule ${i + 1}`,
                source: 'init',
                created_at: new Date().toISOString()
            }
        }));
    }

    async init(): Promise<void> {
        await fs.mkdir(DATA_DIR, { recursive: true });
        await load(this.thoughts, this.rules, this.mem);
        this.initRules();
        this.ui.start();
        this.runLoop();
    }

    private runLoop(): void {
        if (this.worker) clearInterval(this.worker);
        this.worker = setInterval(async () => {
            if (this.running) {
                const n = await this.engine.processBatch();
                if (n > 0) saveDeb(this.thoughts, this.rules, this.mem);
            }
        }, WORKER_INTERVAL);
    }

    private stopLoop(): void {
        if (this.worker) clearInterval(this.worker);
        this.worker = null;
        this.ui.setPaused(true);
    }

    private async handleCmd(c: string, a: string[] = []): Promise<void> {
        switch (c) {
            case 'add': {
                const txt = a.join(' ');
                if (!txt) {
                    console.log(chalk.red('⚠ Usage: add <note>'));
                    return this.ui['rl']?.prompt();
                }
                const t: Thought = {
                    id: genId(),
                    type: Type.INPUT,
                    content: Terms.Atom(txt),
                    belief: new Belief(1, 0),
                    status: Status.PENDING,
                    meta: {
                        created_at: new Date().toISOString(),
                        root_id: genId(),
                        tags: ['user']
                    }
                };
                t.meta.root_id = t.id;
                this.engine.addThought(t);
                saveDeb(this.thoughts, this.rules, this.mem);
                console.log(chalk.green('✅ Note added'));
                this.ui['rl']?.prompt();
                break;
            }
            case 'respond': {
                const pId = a[0];
                const txt = a.slice(1).join(' ');
                if (!pId || !txt) {
                    console.log(chalk.red('⚠ Usage: respond <prompt_id> <text>'));
                    return this.ui['rl']?.prompt();
                }
                const fullId = this.thoughts.all()
                    .map(t => t.meta.ui?.prompt_id)
                    .find(id => id?.startsWith(pId));
                if (!fullId) {
                    console.log(chalk.red(`⚠ Prompt "${pId}" not found`));
                    return this.ui['rl']?.prompt();
                }
                await this.engine.handlePrompt(fullId, txt);
                saveDeb(this.thoughts, this.rules, this.mem);
                console.log(chalk.green('✅ Response sent'));
                this.ui['rl']?.prompt();
                break;
            }
            case 'run': {
                if (!this.running) {
                    this.running = true;
                    this.runLoop();
                    this.ui.setPaused(false);
                    console.log(chalk.green('🚀 System running'));
                }
                this.ui['rl']?.prompt();
                break;
            }
            case 'pause': {
                if (this.running) {
                    this.running = false;
                    this.stopLoop();
                    console.log(chalk.yellow('😴 System paused'));
                }
                this.ui['rl']?.prompt();
                break;
            }
            case 'step': {
                if (!this.running) {
                    const ok = await this.engine.processOne();
                    if (ok) saveDeb(this.thoughts, this.rules, this.mem);
                    console.log(chalk.blue(`⚙ Stepped: ${ok ? 'Processed' : 'Idle'}`));
                } else {
                    console.log(chalk.red('⚠ Pause system to step'));
                }
                this.ui['rl']?.prompt();
                break;
            }
            case 'save': {
                saveDeb.cancel();
                await save(this.thoughts, this.rules, this.mem);
                console.log(chalk.green('💾 Saved'));
                this.ui['rl']?.prompt();
                break;
            }
            case 'quit':
            case 'exit': {
                await this.shutdown();
                break;
            }
            case 'list': {
                const t = this.thoughts.all();
                console.log(chalk.blue(`\n📋 Thoughts: ${t.length}`));
                t.forEach(x => {
                    console.log(`${chalk.gray(`[${x.id.substring(0, 6)}]`)} ${x.type}: ${Terms.format(x.content)} ${this.ui['status'](x.status)}`);
                });
                this.ui['rl']?.prompt();
                break;
            }
            case 'tag': {
                const id = a[0];
                const tag = a[1];
                if (!id || !tag) {
                    console.log(chalk.red('⚠ Usage: tag <id> <tag>'));
                    return this.ui['rl']?.prompt();
                }
                const t = this.thoughts.all().find(x => x.id.startsWith(id));
                if (!t) {
                    console.log(chalk.red(`⚠ Thought "${id}" not found`));
                    return this.ui['rl']?.prompt();
                }
                t.meta.tags = t.meta.tags || [];
                if (!t.meta.tags.includes(tag)) {
                    t.meta.tags.push(tag);
                    this.thoughts.update(t);
                    saveDeb(this.thoughts, this.rules, this.mem);
                    console.log(chalk.green('🏷 Tag added'));
                }
                this.ui['rl']?.prompt();
                break;
            }
            case 'search': {
                const q = a.join(' ');
                if (!q) {
                    console.log(chalk.red('⚠ Usage: search <query>'));
                    return this.ui['rl']?.prompt();
                }
                const r = await this.mem.search(q, 5);
                console.log(chalk.blue(`\n🔍 Results for "${q}":`));
                r.forEach(x => {
                    console.log(`${chalk.gray(`[${x.id.substring(0, 6)}]`)} ${x.content.substring(0, 50)}${x.content.length > 50 ? '...' : ''}`);
                });
                this.ui['rl']?.prompt();
                break;
            }
            case 'delete': {
                const id = a[0];
                if (!id) {
                    console.log(chalk.red('⚠ Usage: delete <id>'));
                    return this.ui['rl']?.prompt();
                }
                const t = this.thoughts.all().find(x => x.id.startsWith(id));
                if (!t) {
                    console.log(chalk.red(`⚠ Thought "${id}" not found`));
                    return this.ui['rl']?.prompt();
                }
                this.thoughts.delete(t.id);
                saveDeb(this.thoughts, this.rules, this.mem);
                console.log(chalk.green(`🗑 Thought ${id} deleted`));
                this.ui['rl']?.prompt();
                break;
            }
            case 'clear': {
                readline.cursorTo(process.stdout, 0, 0);
                readline.clearScreenDown(process.stdout);
                this.height = 0;
                this.draw();
                this.ui['rl']?.prompt();
                break;
            }
            case 'help': {
                console.log(chalk.blue('\n📖 Commands:'));
                console.log('  add <note>        - Add a new note');
                console.log('  respond <id> <txt> - Reply to a prompt');
                console.log('  run               - Start processing');
                console.log('  pause             - Stop processing');
                console.log('  step              - Process one thought');
                console.log('  save              - Save state');
                console.log('  quit/exit         - Exit system');
                console.log('  list              - List thoughts');
                console.log('  tag <id> <tag>    - Tag a thought');
                console.log('  search <query>    - Search memory');
                console.log('  delete <id>       - Delete a thought');
                console.log('  clear             - Clear screen');
                console.log('  help              - Show this help');
                this.ui['rl']?.prompt();
                break;
            }
            default: {
                console.log(chalk.red(`⚠ Unknown command: ${c}`));
                this.ui['rl']?.prompt();
            }
        }
    }

    async shutdown(): Promise<void> {
        this.running = false;
        this.stopLoop();
        this.ui.stop();
        saveDeb.cancel();
        await save(this.thoughts, this.rules, this.mem);
        process.exit(0);
    }
}

async function main() {
    const sys = new FlowMind();
    process.on('SIGINT', () => sys.shutdown());
    process.on('SIGTERM', () => sys.shutdown());
    await sys.init();
}

main().catch(e => {
    console.error(chalk.red("💥 Error:"), e);
    process.exit(1);
});