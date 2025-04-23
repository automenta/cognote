"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || function (mod) {
    if (mod && mod.__esModule) return mod;
    var result = {};
    if (mod != null) for (var k in mod) if (k !== "default" && Object.prototype.hasOwnProperty.call(mod, k)) __createBinding(result, mod, k);
    __setModuleDefault(result, mod);
    return result;
};
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const fs = __importStar(require("fs/promises"));
const fsSync = __importStar(require("fs"));
const path = __importStar(require("path"));
const os = __importStar(require("os"));
const readline = __importStar(require("readline"));
const uuid_1 = require("uuid");
const chalk_1 = __importDefault(require("chalk"));
const ollama_1 = require("@langchain/community/chat_models/ollama");
const ollama_2 = require("@langchain/community/embeddings/ollama");
const messages_1 = require("@langchain/core/messages");
const output_parsers_1 = require("@langchain/core/output_parsers");
const documents_1 = require("@langchain/core/documents");
const faiss_1 = require("@langchain/community/vectorstores/faiss");
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
var Status;
(function (Status) {
    Status["PENDING"] = "PENDING";
    Status["ACTIVE"] = "ACTIVE";
    Status["WAITING"] = "WAITING";
    Status["DONE"] = "DONE";
    Status["FAILED"] = "FAILED";
})(Status || (Status = {}));
var Type;
(function (Type) {
    Type["INPUT"] = "INPUT";
    Type["GOAL"] = "GOAL";
    Type["STRATEGY"] = "STRATEGY";
    Type["OUTCOME"] = "OUTCOME";
    Type["QUERY"] = "QUERY";
    Type["PROMPT"] = "PROMPT";
    Type["EVENT"] = "EVENT";
})(Type || (Type = {}));
class Belief {
    pos;
    neg;
    constructor(pos = DEF_BELIEF_POS, neg = DEF_BELIEF_NEG) {
        this.pos = pos;
        this.neg = neg;
    }
    score() { return (this.pos + 1) / (this.pos + this.neg + 2); }
    update(success) { success ? this.pos++ : this.neg++; }
    toJSON() { return { pos: this.pos, neg: this.neg }; }
    static fromJSON(data) { return new Belief(data.pos, data.neg); }
    static DEF = new Belief();
}
var Terms;
(function (Terms) {
    Terms.Atom = (name) => ({ kind: 'Atom', name });
    Terms.Var = (name) => ({ kind: 'Var', name });
    Terms.Struct = (name, args) => ({ kind: 'Struct', name, args });
    Terms.List = (items) => ({ kind: 'List', items });
    function format(term) {
        if (!term)
            return 'null';
        switch (term.kind) {
            case 'Atom': return term.name;
            case 'Var': return `?${term.name}`;
            case 'Struct': return `${term.name}(${term.args.map(format).join(', ')})`;
            case 'List': return `[${term.items.map(format).join(', ')}]`;
            default: return 'invalid';
        }
    }
    Terms.format = format;
    function toStr(term) {
        switch (term.kind) {
            case 'Atom': return term.name;
            case 'Var': return `?${term.name}`;
            case 'Struct': return `${term.name}: ${term.args.map(toStr).join('; ')}`;
            case 'List': return term.items.map(toStr).join(', ');
            default: return '';
        }
    }
    Terms.toStr = toStr;
    function fromStr(input) {
        if (input.includes(':') && input.includes(';')) {
            const [name, argsStr] = input.split(':', 2);
            const args = argsStr.split(';').map(s => Terms.Atom(s.trim()));
            return Terms.Struct(name.trim(), args);
        }
        if (input.startsWith('[') && input.endsWith(']')) {
            const items = input.slice(1, -1).split(',').map(s => Terms.Atom(s.trim()));
            return Terms.List(items);
        }
        return Terms.Atom(input);
    }
    Terms.fromStr = fromStr;
    function unify(t1, t2, binds = new Map()) {
        const resolve = (term, current) => {
            if (term.kind === 'Var' && current.has(term.name))
                return resolve(current.get(term.name), current);
            return term;
        };
        const r1 = resolve(t1, binds);
        const r2 = resolve(t2, binds);
        if (r1.kind === 'Var') {
            if (r1.name === r2.name && r1.kind === r2.kind)
                return binds;
            const newBinds = new Map(binds);
            newBinds.set(r1.name, r2);
            return newBinds;
        }
        if (r2.kind === 'Var') {
            const newBinds = new Map(binds);
            newBinds.set(r2.name, r1);
            return newBinds;
        }
        if (r1.kind !== r2.kind)
            return null;
        switch (r1.kind) {
            case 'Atom': return r1.name === r2.name ? binds : null;
            case 'Struct': {
                const s2 = r2;
                if (r1.name !== s2.name || r1.args.length !== s2.args.length)
                    return null;
                let current = binds;
                for (let i = 0; i < r1.args.length; i++) {
                    const res = unify(r1.args[i], s2.args[i], current);
                    if (!res)
                        return null;
                    current = res;
                }
                return current;
            }
            case 'List': {
                const l2 = r2;
                if (r1.items.length !== l2.items.length)
                    return null;
                let current = binds;
                for (let i = 0; i < r1.items.length; i++) {
                    const res = unify(r1.items[i], l2.items[i], current);
                    if (!res)
                        return null;
                    current = res;
                }
                return current;
            }
        }
        return null;
    }
    Terms.unify = unify;
    function subst(term, binds) {
        if (term.kind === 'Var' && binds.has(term.name))
            return subst(binds.get(term.name), binds);
        if (term.kind === 'Struct')
            return { ...term, args: term.args.map(a => subst(a, binds)) };
        if (term.kind === 'List')
            return { ...term, items: term.items.map(e => subst(e, binds)) };
        return term;
    }
    Terms.subst = subst;
})(Terms || (Terms = {}));
function genId() { return (0, uuid_1.v4)(); }
function debounce(fn, wait) {
    let timeout = null;
    return (...args) => {
        const later = () => { timeout = null; fn(...args); };
        if (timeout)
            clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
}
function parseJSON(json, def) {
    if (!json)
        return def;
    try {
        return JSON.parse(json);
    }
    catch {
        return def;
    }
}
function sleep(ms) { return new Promise(resolve => setTimeout(resolve, ms)); }
class ThoughtStore {
    thoughts = new Map();
    listeners = [];
    add(t) {
        this.thoughts.set(t.id, t);
        this.notify();
    }
    get(id) { return this.thoughts.get(id); }
    update(t) {
        if (this.thoughts.has(t.id)) {
            this.thoughts.set(t.id, {
                ...t,
                meta: { ...t.meta, updated_at: new Date().toISOString() }
            });
            this.notify();
        }
    }
    delete(id) {
        if (this.thoughts.has(id)) {
            this.thoughts.delete(id);
            this.notify();
        }
    }
    pending() {
        return Array.from(this.thoughts.values()).filter(t => t.status === Status.PENDING);
    }
    all() {
        return Array.from(this.thoughts.values());
    }
    byRoot(rootId) {
        return this.all()
            .filter(t => t.meta.root_id === rootId || t.id === rootId)
            .sort((a, b) => Date.parse(a.meta.created_at ?? '0') - Date.parse(b.meta.created_at ?? '0'));
    }
    byTag(tag) {
        return this.all().filter(t => t.meta.tags?.includes(tag));
    }
    toJSON() {
        const obj = {};
        this.thoughts.forEach((t, id) => obj[id] = { ...t, belief: t.belief.toJSON() });
        return obj;
    }
    static fromJSON(data) {
        const store = new ThoughtStore();
        for (const id in data) {
            store.add({ ...data[id], belief: Belief.fromJSON(data[id].belief) });
        }
        return store;
    }
    onChange(l) { this.listeners.push(l); }
    offChange(l) { this.listeners = this.listeners.filter(x => x !== l); }
    notify() { this.listeners.forEach(l => l()); }
}
class RuleStore {
    rules = new Map();
    add(r) { this.rules.set(r.id, r); }
    get(id) { return this.rules.get(id); }
    update(r) { if (this.rules.has(r.id))
        this.rules.set(r.id, r); }
    delete(id) { this.rules.delete(id); }
    all() { return Array.from(this.rules.values()); }
    byDesc(desc) {
        return this.all().filter(r => r.meta.desc?.toLowerCase().includes(desc.toLowerCase()));
    }
    toJSON() {
        const obj = {};
        this.rules.forEach((r, id) => obj[id] = { ...r, belief: r.belief.toJSON() });
        return obj;
    }
    static fromJSON(data) {
        const store = new RuleStore();
        for (const id in data) {
            store.add({ ...data[id], belief: Belief.fromJSON(data[id].belief) });
        }
        return store;
    }
}
class MemoryStore {
    emb;
    path;
    store = null;
    ready = false;
    constructor(emb, path) {
        this.emb = emb;
        this.path = path;
    }
    async init() {
        try {
            await fs.access(this.path);
            this.store = await faiss_1.FaissStore.load(this.path, this.emb);
            this.ready = true;
        }
        catch (e) {
            if (e.code === 'ENOENT') {
                const doc = new documents_1.Document({
                    pageContent: "FlowMind init",
                    metadata: { source_id: "init", type: "system", ts: new Date().toISOString() }
                });
                this.store = await faiss_1.FaissStore.fromDocuments([doc], this.emb);
                await this.store.save(this.path);
                this.ready = true;
            }
        }
    }
    async add(e) {
        if (!this.store || !this.ready)
            return;
        const doc = new documents_1.Document({ pageContent: e.content, metadata: { ...e.meta, id: e.id } });
        await this.store.addDocuments([doc]);
        await this.save();
    }
    async search(q, k = 5) {
        if (!this.store || !this.ready)
            return [];
        const res = await this.store.similaritySearchWithScore(q, k);
        return res.map(([doc]) => ({
            id: doc.metadata.id || genId(),
            embedding: [],
            content: doc.pageContent,
            meta: doc.metadata
        }));
    }
    async save() {
        if (this.store && this.ready)
            await this.store.save(this.path);
    }
}
class LLMService {
    llm;
    constructor() {
        this.llm = new ollama_1.ChatOllama({ baseUrl: OLLAMA_URL, model: MODEL, temperature: 0.7 });
    }
    async gen(p) {
        return this.llm.pipe(new output_parsers_1.StringOutputParser()).invoke([new messages_1.HumanMessage(p)]);
    }
    get emb() {
        return new ollama_2.OllamaEmbeddings({ model: MODEL, baseUrl: OLLAMA_URL });
    }
}
class LLMTool {
    name = "LLMTool";
    desc = "Handles LLM generation tasks.";
    async exec(action, ctx, t) {
        const op = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        const p = action.args[1];
        if (!op || !p)
            return Terms.Atom("err:no_params");
        const txt = Terms.toStr(p);
        if (op === 'gen') {
            const res = await ctx.llm.gen(txt);
            return Terms.Atom(res);
        }
        return Terms.Atom("err:bad_op");
    }
}
class MemoryTool {
    name = "MemoryTool";
    desc = "Manages memory operations.";
    async exec(action, ctx, t) {
        const op = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (!op)
            return Terms.Atom("err:no_op");
        if (op === 'add') {
            const c = action.args[1];
            if (!c)
                return Terms.Atom("err:no_content");
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
        }
        else if (op === 'search') {
            const q = action.args[1];
            const k = action.args[2]?.kind === 'Atom' ? parseInt(action.args[2].name, 10) : 3;
            if (!q)
                return Terms.Atom("err:no_query");
            const txt = Terms.toStr(q);
            const res = await ctx.memory.search(txt, isNaN(k) ? 3 : k);
            return Terms.List(res.map(r => Terms.Atom(r.content)));
        }
        return Terms.Atom("err:bad_op");
    }
}
class PromptTool {
    name = "PromptTool";
    desc = "Manages user prompts.";
    async exec(action, ctx, t) {
        const op = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (op !== 'prompt')
            return Terms.Atom("err:bad_op");
        const txt = action.args[1];
        if (!txt)
            return Terms.Atom("err:no_text");
        const pTxt = Terms.toStr(txt);
        const pId = genId();
        const pThought = {
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
class GoalTool {
    name = "GoalTool";
    desc = "Suggests new goals.";
    async exec(action, ctx, t) {
        const op = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (op !== 'suggest')
            return Terms.Atom("err:bad_op");
        const c = action.args[1] ?? t.content;
        const txt = Terms.toStr(c);
        const mem = await ctx.memory.search(`Goals related to: ${txt}`, 3);
        const memTxt = mem.map(r => r.content).join("\n - ");
        const p = `Context: "${txt}"\nPast: ${memTxt}\n\nSuggest ONE actionable goal.`;
        const sug = await ctx.llm.gen(p);
        if (sug && sug.trim()) {
            const sThought = {
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
class CoreTool {
    name = "CoreTool";
    desc = "Handles system operations.";
    async exec(action, ctx, t) {
        const op = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (op === 'set_status') {
            const tId = action.args[1];
            const s = action.args[2];
            if (tId?.kind !== 'Atom' || s?.kind !== 'Atom')
                return Terms.Atom("err:bad_params");
            const id = tId.name;
            const stat = s.name;
            if (!Object.values(Status).includes(stat))
                return Terms.Atom("err:bad_status");
            const target = ctx.thoughts.get(id);
            if (target) {
                target.status = stat;
                ctx.thoughts.update(target);
                return Terms.Atom(`ok:status:${id}_${stat}`);
            }
            return Terms.Atom(`err:no_thought:${id}`);
        }
        else if (op === 'add_thought') {
            const type = action.args[1];
            const c = action.args[2];
            const rId = action.args[3];
            const pId = action.args[4];
            if (type?.kind !== 'Atom' || !c)
                return Terms.Atom("err:bad_params");
            const tType = type.name;
            if (!Object.values(Type).includes(tType))
                return Terms.Atom("err:bad_type");
            const nThought = {
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
        }
        else if (op === 'delete_thought') {
            const tId = action.args[1];
            if (tId?.kind !== 'Atom')
                return Terms.Atom("err:bad_params");
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
    tools = new Map();
    add(t) { this.tools.set(t.name, t); }
    get(n) { return this.tools.get(n); }
    list() { return Array.from(this.tools.values()); }
    remove(n) { this.tools.delete(n); }
}
class Engine {
    thoughts;
    rules;
    memory;
    llm;
    tools;
    active = new Set();
    batchSize = 5;
    maxConc = 3;
    constructor(thoughts, rules, memory, llm, tools) {
        this.thoughts = thoughts;
        this.rules = rules;
        this.memory = memory;
        this.llm = llm;
        this.tools = tools;
    }
    addThought(t) { this.thoughts.add(t); }
    pick() {
        const p = this.thoughts.pending().filter(t => !this.active.has(t.id));
        if (p.length === 0)
            return null;
        const w = p.map(t => t.meta.priority ?? t.belief.score());
        const total = w.reduce((s, w) => s + w, 0);
        if (total <= 0)
            return p[Math.floor(Math.random() * p.length)];
        let r = Math.random() * total;
        for (let i = 0; i < p.length; i++) {
            r -= w[i];
            if (r <= 0)
                return p[i];
        }
        return p[p.length - 1];
    }
    match(t) {
        const m = this.rules.all()
            .map(r => ({ rule: r, binds: Terms.unify(r.pattern, t.content) }))
            .filter(m => m.binds !== null);
        if (m.length === 0)
            return null;
        if (m.length === 1)
            return m[0];
        const w = m.map(m => m.rule.belief.score());
        const total = w.reduce((s, w) => s + w, 0);
        if (total <= 0)
            return m[Math.floor(Math.random() * m.length)];
        let r = Math.random() * total;
        for (let i = 0; i < m.length; i++) {
            r -= w[i];
            if (r <= 0)
                return m[i];
        }
        return m[m.length - 1];
    }
    async run(t, r, b) {
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
        }
        catch (e) {
            this.fail(t, r, `Tool error: ${e.message}`);
            return false;
        }
    }
    async noMatch(t) {
        let p = "";
        let type = null;
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
                const pThought = {
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
        }
        else if (t.status === Status.PENDING) {
            this.fail(t, null, "No rule or action.");
        }
    }
    fail(t, r, e) {
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
    succeed(t, r) {
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
    async processOne() {
        const t = this.pick();
        if (!t)
            return false;
        if (this.active.has(t.id))
            return false;
        this.active.add(t.id);
        t.status = Status.ACTIVE;
        t.meta.agent_id = 'worker';
        this.thoughts.update(t);
        let ok = false;
        try {
            const m = this.match(t);
            if (m) {
                ok = await this.run(t, m.rule, m.binds);
            }
            else {
                await this.noMatch(t);
                ok = this.thoughts.get(t.id)?.status === Status.DONE;
            }
        }
        catch (e) {
            this.fail(t, null, `Error: ${e.message}`);
        }
        finally {
            this.active.delete(t.id);
            const curr = this.thoughts.get(t.id);
            if (curr?.status === Status.ACTIVE) {
                this.fail(t, null, "Unexpected end.");
            }
        }
        return ok;
    }
    async processBatch() {
        let count = 0;
        const toProc = [];
        while (count < this.batchSize && this.active.size < this.maxConc) {
            const t = this.pick();
            if (!t)
                break;
            toProc.push(t);
            this.active.add(t.id);
            count++;
        }
        const res = await Promise.all(toProc.map(async (t) => {
            t.status = Status.ACTIVE;
            t.meta.agent_id = 'worker';
            this.thoughts.update(t);
            try {
                const m = this.match(t);
                if (m) {
                    return await this.run(t, m.rule, m.binds);
                }
                else {
                    await this.noMatch(t);
                    return this.thoughts.get(t.id)?.status === Status.DONE;
                }
            }
            catch (e) {
                this.fail(t, null, `Error: ${e.message}`);
                return false;
            }
            finally {
                this.active.delete(t.id);
                const curr = this.thoughts.get(t.id);
                if (curr?.status === Status.ACTIVE) {
                    this.fail(t, null, "Unexpected end.");
                }
            }
        }));
        return res.filter(r => r).length;
    }
    async handlePrompt(pId, txt) {
        const t = this.thoughts.all().find(t => t.meta.waiting_prompt === pId && t.status === Status.WAITING);
        if (!t)
            return;
        const rThought = {
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
async function save(thoughts, rules, mem) {
    await fs.mkdir(DATA_DIR, { recursive: true });
    const s = {
        thoughts: thoughts.toJSON(),
        rules: rules.toJSON()
    };
    await fs.writeFile(STATE_FILE, JSON.stringify(s, null, 2));
    await mem.save();
}
const saveDeb = debounce(save, 5000);
async function load(thoughts, rules, mem) {
    if (!fsSync.existsSync(STATE_FILE)) {
        await mem.init();
        return;
    }
    const d = await fs.readFile(STATE_FILE, 'utf-8');
    const s = parseJSON(d, { thoughts: {}, rules: {} });
    ThoughtStore.fromJSON(s.thoughts).all().forEach(t => thoughts.add(t));
    RuleStore.fromJSON(s.rules).all().forEach(r => rules.add(r));
    await mem.init();
}
class UI {
    thoughts;
    cmd;
    rl = null;
    timer = null;
    height = 0;
    paused = false;
    constructor(thoughts, cmd) {
        this.thoughts = thoughts;
        this.cmd = cmd;
        thoughts.onChange(() => this.draw());
    }
    start() {
        this.rl = readline.createInterface({
            input: process.stdin,
            output: process.stdout,
            prompt: chalk_1.default.cyan.bold('FlowMind 🌟> ')
        });
        this.rl.on('line', l => {
            const [c, ...a] = l.trim().split(' ');
            if (c)
                this.cmd(c.toLowerCase(), a);
            else
                this.rl?.prompt();
        });
        this.rl.on('close', () => this.cmd('quit'));
        this.timer = setInterval(() => this.draw(), UI_REFRESH);
        this.draw();
        this.rl.prompt();
    }
    stop() {
        if (this.timer)
            clearInterval(this.timer);
        this.rl?.close();
        process.stdout.write(chalk_1.default.gray('\nFlowMind stopped 🚪\n'));
    }
    draw() {
        const t = this.thoughts.all();
        const roots = t.filter(x => x.type === Type.INPUT && !x.meta.parent_id);
        let out = "";
        const w = process.stdout.columns || 80;
        if (this.height > 0) {
            process.stdout.write(`\x1b[${this.height}A\x1b[J`);
        }
        out += chalk_1.default.magenta('═'.repeat(w)) + "\n";
        out += chalk_1.default.bold(` 🌈 FlowMind ${this.paused ? chalk_1.default.red('[PAUSED 😴]') : chalk_1.default.green('[RUNNING 🚀]')}\n`);
        out += chalk_1.default.magenta('═'.repeat(w)) + "\n";
        const prompts = [];
        roots.forEach(r => {
            out += `\n${chalk_1.default.yellow('📝 Note:')} ${Terms.format(r.content).substring(0, w - 20)} ${chalk_1.default.gray(`[${r.id.substring(0, 6)}]`)} ${this.status(r.status)}\n`;
            out += chalk_1.default.gray('-'.repeat(w / 2)) + "\n";
            const kids = this.thoughts.byRoot(r.id).filter(x => x.id !== r.id);
            kids.forEach(k => {
                const p = `  ${chalk_1.default.gray(`[${k.id.substring(0, 6)}|${k.type.padEnd(8)}|${k.status.padEnd(7)}|P:${k.belief.score().toFixed(2)}]`)} `;
                const c = Terms.format(k.content);
                const maxW = w - p.length - 1;
                const txt = c.length > maxW ? c.substring(0, maxW - 3) + "..." : c;
                out += p + chalk_1.default.white(txt) + "\n";
                if (k.meta.error)
                    out += `    ${chalk_1.default.red('⚠ Error:')} ${k.meta.error.substring(0, w - 15)}\n`;
                if (k.meta.tags?.length)
                    out += `    ${chalk_1.default.cyan('🏷 Tags:')} ${k.meta.tags.join(', ')}\n`;
                if (k.type === Type.PROMPT && k.status === Status.PENDING && k.meta.ui?.prompt_id)
                    prompts.push(k);
            });
        });
        if (prompts.length > 0) {
            out += "\n" + chalk_1.default.yellow('📬 Pending Prompts') + "\n";
            prompts.forEach(p => {
                const id = p.meta.ui?.prompt_id ?? 'unknown';
                const parent = p.meta.parent_id;
                out += `${chalk_1.default.gray(`[${id.substring(0, 6)}]`)} ${Terms.format(p.content)} ${chalk_1.default.gray(`(Waiting: ${parent?.substring(0, 6) ?? 'N/A'})`)}\n`;
            });
        }
        out += "\n" + chalk_1.default.magenta('═'.repeat(w)) + "\n";
        out += chalk_1.default.blue('🔧 Commands:') + " add <note>, respond <prompt_id> <text>, run, pause, step, save, quit, list, tag <id> <tag>, search <query>, delete <id>, clear, help\n";
        const lines = out.split('\n');
        this.height = lines.length + 1;
        readline.cursorTo(process.stdout, 0, 0);
        readline.clearScreenDown(process.stdout);
        process.stdout.write(out);
        this.rl?.prompt(true);
    }
    status(s) {
        switch (s) {
            case Status.PENDING: return chalk_1.default.yellow('⏳ Pending');
            case Status.ACTIVE: return chalk_1.default.blue('⚙ Active');
            case Status.WAITING: return chalk_1.default.cyan('⌛ Waiting');
            case Status.DONE: return chalk_1.default.green('✅ Done');
            case Status.FAILED: return chalk_1.default.red('❌ Failed');
        }
    }
    setPaused(p) {
        this.paused = p;
        this.draw();
    }
}
class FlowMind {
    thoughts = new ThoughtStore();
    rules = new RuleStore();
    llm = new LLMService();
    mem = new MemoryStore(this.llm.emb, VECTOR_STORE);
    tools = new Tools();
    engine = new Engine(this.thoughts, this.rules, this.mem, this.llm, this.tools);
    ui = new UI(this.thoughts, this.handleCmd.bind(this));
    running = true;
    worker = null;
    constructor() {
        this.initTools();
    }
    initTools() {
        [new LLMTool(), new MemoryTool(), new PromptTool(), new GoalTool(), new CoreTool()]
            .forEach(t => this.tools.add(t));
    }
    initRules() {
        if (this.rules.all().length > 0)
            return;
        const rules = [
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
    async init() {
        await fs.mkdir(DATA_DIR, { recursive: true });
        await load(this.thoughts, this.rules, this.mem);
        this.initRules();
        this.ui.start();
        this.runLoop();
    }
    runLoop() {
        if (this.worker)
            clearInterval(this.worker);
        this.worker = setInterval(async () => {
            if (this.running) {
                const n = await this.engine.processBatch();
                if (n > 0)
                    saveDeb(this.thoughts, this.rules, this.mem);
            }
        }, WORKER_INTERVAL);
    }
    stopLoop() {
        if (this.worker)
            clearInterval(this.worker);
        this.worker = null;
        this.ui.setPaused(true);
    }
    async handleCmd(c, a = []) {
        switch (c) {
            case 'add': {
                const txt = a.join(' ');
                if (!txt) {
                    console.log(chalk_1.default.red('⚠ Usage: add <note>'));
                    return this.ui['rl']?.prompt();
                }
                const t = {
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
                console.log(chalk_1.default.green('✅ Note added'));
                this.ui['rl']?.prompt();
                break;
            }
            case 'respond': {
                const pId = a[0];
                const txt = a.slice(1).join(' ');
                if (!pId || !txt) {
                    console.log(chalk_1.default.red('⚠ Usage: respond <prompt_id> <text>'));
                    return this.ui['rl']?.prompt();
                }
                const fullId = this.thoughts.all()
                    .map(t => t.meta.ui?.prompt_id)
                    .find(id => id?.startsWith(pId));
                if (!fullId) {
                    console.log(chalk_1.default.red(`⚠ Prompt "${pId}" not found`));
                    return this.ui['rl']?.prompt();
                }
                await this.engine.handlePrompt(fullId, txt);
                saveDeb(this.thoughts, this.rules, this.mem);
                console.log(chalk_1.default.green('✅ Response sent'));
                this.ui['rl']?.prompt();
                break;
            }
            case 'run': {
                if (!this.running) {
                    this.running = true;
                    this.runLoop();
                    this.ui.setPaused(false);
                    console.log(chalk_1.default.green('🚀 System running'));
                }
                this.ui['rl']?.prompt();
                break;
            }
            case 'pause': {
                if (this.running) {
                    this.running = false;
                    this.stopLoop();
                    console.log(chalk_1.default.yellow('😴 System paused'));
                }
                this.ui['rl']?.prompt();
                break;
            }
            case 'step': {
                if (!this.running) {
                    const ok = await this.engine.processOne();
                    if (ok)
                        saveDeb(this.thoughts, this.rules, this.mem);
                    console.log(chalk_1.default.blue(`⚙ Stepped: ${ok ? 'Processed' : 'Idle'}`));
                }
                else {
                    console.log(chalk_1.default.red('⚠ Pause system to step'));
                }
                this.ui['rl']?.prompt();
                break;
            }
            case 'save': {
                saveDeb.cancel();
                await save(this.thoughts, this.rules, this.mem);
                console.log(chalk_1.default.green('💾 Saved'));
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
                console.log(chalk_1.default.blue(`\n📋 Thoughts: ${t.length}`));
                t.forEach(x => {
                    console.log(`${chalk_1.default.gray(`[${x.id.substring(0, 6)}]`)} ${x.type}: ${Terms.format(x.content)} ${this.ui['status'](x.status)}`);
                });
                this.ui['rl']?.prompt();
                break;
            }
            case 'tag': {
                const id = a[0];
                const tag = a[1];
                if (!id || !tag) {
                    console.log(chalk_1.default.red('⚠ Usage: tag <id> <tag>'));
                    return this.ui['rl']?.prompt();
                }
                const t = this.thoughts.all().find(x => x.id.startsWith(id));
                if (!t) {
                    console.log(chalk_1.default.red(`⚠ Thought "${id}" not found`));
                    return this.ui['rl']?.prompt();
                }
                t.meta.tags = t.meta.tags || [];
                if (!t.meta.tags.includes(tag)) {
                    t.meta.tags.push(tag);
                    this.thoughts.update(t);
                    saveDeb(this.thoughts, this.rules, this.mem);
                    console.log(chalk_1.default.green('🏷 Tag added'));
                }
                this.ui['rl']?.prompt();
                break;
            }
            case 'search': {
                const q = a.join(' ');
                if (!q) {
                    console.log(chalk_1.default.red('⚠ Usage: search <query>'));
                    return this.ui['rl']?.prompt();
                }
                const r = await this.mem.search(q, 5);
                console.log(chalk_1.default.blue(`\n🔍 Results for "${q}":`));
                r.forEach(x => {
                    console.log(`${chalk_1.default.gray(`[${x.id.substring(0, 6)}]`)} ${x.content.substring(0, 50)}${x.content.length > 50 ? '...' : ''}`);
                });
                this.ui['rl']?.prompt();
                break;
            }
            case 'delete': {
                const id = a[0];
                if (!id) {
                    console.log(chalk_1.default.red('⚠ Usage: delete <id>'));
                    return this.ui['rl']?.prompt();
                }
                const t = this.thoughts.all().find(x => x.id.startsWith(id));
                if (!t) {
                    console.log(chalk_1.default.red(`⚠ Thought "${id}" not found`));
                    return this.ui['rl']?.prompt();
                }
                this.thoughts.delete(t.id);
                saveDeb(this.thoughts, this.rules, this.mem);
                console.log(chalk_1.default.green(`🗑 Thought ${id} deleted`));
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
                console.log(chalk_1.default.blue('\n📖 Commands:'));
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
                console.log(chalk_1.default.red(`⚠ Unknown command: ${c}`));
                this.ui['rl']?.prompt();
            }
        }
    }
    async shutdown() {
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
    console.error(chalk_1.default.red("💥 Error:"), e);
    process.exit(1);
});
