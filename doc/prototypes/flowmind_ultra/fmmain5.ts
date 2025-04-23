import {promises as fs} from "fs";
import fsSync from "fs";
import path from "path";
import os from "os";
import readline from "readline";
import {v4 as uuidv4} from "uuid";

import chalk from "chalk";
import {ChatOllama} from "@langchain/community/chat_models/ollama";

import {OllamaEmbeddings} from "@langchain/community/embeddings/ollama";

import {HumanMessage} from "@langchain/core/messages";

import {StringOutputParser} from "@langchain/core/output_parsers";

import {Document} from "@langchain/core/documents";

import {FaissStore} from "@langchain/community/vectorstores/faiss";


const DATA_DIR = path.join(os.homedir(), '.flowmind');
const STATE_FILE = path.join(DATA_DIR, 'state.json');
const VECTOR_STORE = path.join(DATA_DIR, 'vector-store');
const OLLAMA_URL = process.env.OLLAMA_HOST || "http://localhost:11434";
const MODEL = "llamablit";
const WORKER_INTERVAL = 1000;
const UI_REFRESH = 1000;
const MAX_RETRIES = 2;
const DEFAULT_BELIEF = { pos: 1.0, neg: 1.0 };

const Status = {
    PENDING: 'PENDING',
    ACTIVE: 'ACTIVE',
    WAITING: 'WAITING',
    DONE: 'DONE',
    FAILED: 'FAILED'
};

const Type = {
    INPUT: 'INPUT',
    GOAL: 'GOAL',
    STRATEGY: 'STRATEGY',
    OUTCOME: 'OUTCOME',
    QUERY: 'QUERY',
    PROMPT: 'PROMPT',
    EVENT: 'EVENT'
};

class Belief {
    constructor(pos = DEFAULT_BELIEF.pos, neg = DEFAULT_BELIEF.neg) {
        this.pos = pos;
        this.neg = neg;
    }

    score() {
        return (this.pos + 1) / (this.pos + this.neg + 2);
    }

    update(success) {
        success ? this.pos++ : this.neg++;
    }

    toJSON() {
        return { pos: this.pos, neg: this.neg };
    }

    static fromJSON({ pos, neg }) {
        return new Belief(pos, neg);
    }
}

const Term = {
    Atom: (name) => ({ kind: 'Atom', name }),
    Var: (name) => ({ kind: 'Var', name }),
    Struct: (name, args) => ({ kind: 'Struct', name, args }),
    List: (elems) => ({ kind: 'List', elems }),

    format(term) {
        if (!term) return 'null';
        switch (term.kind) {
            case 'Atom': return term.name;
            case 'Var': return `?${term.name}`;
            case 'Struct': return `${term.name}(${term.args.map(Term.format).join(', ')})`;
            case 'List': return `[${term.elems.map(Term.format).join(', ')}]`;
            default: return 'invalid';
        }
    },

    toString(term) {
        switch (term.kind) {
            case 'Atom': return term.name;
            case 'Var': return `?${term.name}`;
            case 'Struct': return `${term.name}: ${term.args.map(Term.toString).join('; ')}`;
            case 'List': return term.elems.map(Term.toString).join(', ');
            default: return '';
        }
    },

    fromString(input) {
        if (input.includes(':') && input.includes(';')) {
            const [name, argsStr] = input.split(':', 2);
            const args = argsStr.split(';').map(s => Term.Atom(s.trim()));
            return Term.Struct(name.trim(), args);
        }
        if (input.startsWith('[') && input.endsWith(']')) {
            const elems = input.slice(1, -1).split(',').map(s => Term.Atom(s.trim()));
            return Term.List(elems);
        }
        return Term.Atom(input);
    },

    unify(t1, t2, bindings = new Map()) {
        const resolve = (term, binds) => {
            if (term.kind === 'Var' && binds.has(term.name)) return resolve(binds.get(term.name), binds);
            return term;
        };

        t1 = resolve(t1, bindings);
        t2 = resolve(t2, bindings);

        if (t1.kind === 'Var') {
            if (t1.name === t2.name && t1.kind === t2.kind) return bindings;
            const newBinds = new Map(bindings);
            newBinds.set(t1.name, t2);
            return newBinds;
        }
        if (t2.kind === 'Var') {
            const newBinds = new Map(bindings);
            newBinds.set(t2.name, t1);
            return newBinds;
        }
        if (t1.kind !== t2.kind) return null;

        switch (t1.kind) {
            case 'Atom': return t1.name === t2.name ? bindings : null;
            case 'Struct': {
                if (t1.name !== t2.name || t1.args.length !== t2.args.length) return null;
                let currBinds = bindings;
                for (let i = 0; i < t1.args.length; i++) {
                    const result = Term.unify(t1.args[i], t2.args[i], currBinds);
                    if (!result) return null;
                    currBinds = result;
                }
                return currBinds;
            }
            case 'List': {
                if (t1.elems.length !== t2.elems.length) return null;
                let currBinds = bindings;
                for (let i = 0; i < t1.elems.length; i++) {
                    const result = Term.unify(t1.elems[i], t2.elems[i], currBinds);
                    if (!result) return null;
                    currBinds = result;
                }
                return currBinds;
            }
        }
        return null;
    },

    subst(term, bindings) {
        if (term.kind === 'Var' && bindings.has(term.name)) return Term.subst(bindings.get(term.name), bindings);
        if (term.kind === 'Struct') return { ...term, args: term.args.map(arg => Term.subst(arg, bindings)) };
        if (term.kind === 'List') return { ...term, elems: term.elems.map(el => Term.subst(el, bindings)) };
        return term;
    }
};

function generateId() {
    return uuidv4();
}

function debounce(func, wait) {
    let timeout;
    return (...args) => {
        const later = () => {
            timeout = null;
            func(...args);
        };
        if (timeout) clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
}

function parseJSON(json, defaultVal) {
    if (!json) return defaultVal;
    try {
        return JSON.parse(json);
    } catch {
        return defaultVal;
    }
}

class ThoughtStore {
    thoughts = new Map();
    listeners = [];

    add(thought) {
        this.thoughts.set(thought.id, thought);
        this.notify();
    }

    get(id) {
        return this.thoughts.get(id);
    }

    update(thought) {
        if (this.thoughts.has(thought.id)) {
            this.thoughts.set(thought.id, {
                ...thought,
                meta: { ...thought.meta, modified: new Date().toISOString() }
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

    getPending() {
        return Array.from(this.thoughts.values()).filter(t => t.status === Status.PENDING);
    }

    getAll() {
        return Array.from(this.thoughts.values());
    }

    getByRoot(rootId) {
        return this.getAll()
            .filter(t => t.meta.root === rootId || t.id === rootId)
            .sort((a, b) => Date.parse(a.meta.created ?? '0') - Date.parse(b.meta.created ?? '0'));
    }

    searchByTag(tag) {
        return this.getAll().filter(t => t.meta.tags?.includes(tag));
    }

    toJSON() {
        const obj = {};
        this.thoughts.forEach((t, id) => {
            obj[id] = { ...t, belief: t.belief.toJSON() };
        });
        return obj;
    }

    static fromJSON(data) {
        const store = new ThoughtStore();
        for (const id in data) {
            store.add({ ...data[id], belief: Belief.fromJSON(data[id].belief) });
        }
        return store;
    }

    onChange(listener) {
        this.listeners.push(listener);
    }

    offChange(listener) {
        this.listeners = this.listeners.filter(l => l !== listener);
    }

    notify() {
        this.listeners.forEach(l => l());
    }
}

class RuleStore {
    rules = new Map();

    add(rule) {
        this.rules.set(rule.id, rule);
    }

    get(id) {
        return this.rules.get(id);
    }

    update(rule) {
        if (this.rules.has(rule.id)) this.rules.set(rule.id, rule);
    }

    delete(id) {
        this.rules.delete(id);
    }

    getAll() {
        return Array.from(this.rules.values());
    }

    searchByDesc(desc) {
        return this.getAll().filter(r => r.meta.desc?.toLowerCase().includes(desc.toLowerCase()));
    }

    toJSON() {
        const obj = {};
        this.rules.forEach((r, id) => {
            obj[id] = { ...r, belief: r.belief.toJSON() };
        });
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
    vector = null;
    ready = false;

    constructor(embeddings, path) {
        this.embeddings = embeddings;
        this.path = path;
    }

    async init() {
        try {
            await fs.access(this.path);
            this.vector = await FaissStore.load(this.path, this.embeddings);
            this.ready = true;
        } catch (e) {
            if (e.code === 'ENOENT') {
                const doc = new Document({
                    pageContent: "FlowMind init",
                    metadata: { source: "init", type: "system", time: new Date().toISOString() }
                });
                this.vector = await FaissStore.fromDocuments([doc], this.embeddings);
                await this.vector.save(this.path);
                this.ready = true;
            }
        }
    }

    async add(entry) {
        if (!this.vector || !this.ready) return;
        const doc = new Document({ pageContent: entry.content, metadata: { ...entry.meta, id: entry.id } });
        await this.vector.addDocuments([doc]);
        await this.save();
    }

    async search(query, k = 5) {
        if (!this.vector || !this.ready) return [];
        const results = await this.vector.similaritySearchWithScore(query, k);
        return results.map(([doc]) => ({
            id: doc.metadata.id || generateId(),
            embedding: [],
            content: doc.pageContent,
            meta: doc.metadata
        }));
    }

    async save() {
        if (this.vector && this.ready) await this.vector.save(this.path);
    }
}

class LLMService {
    constructor() {
        this.llm = new ChatOllama({ baseUrl: OLLAMA_URL, model: MODEL, temperature: 0.7 });
        this.embeddings = new OllamaEmbeddings({ model: MODEL, baseUrl: OLLAMA_URL });
    }

    async generate(prompt) {
        return this.llm.pipe(new StringOutputParser()).invoke([new HumanMessage(prompt)]);
    }

    async embed(text) {
        return this.embeddings.embedQuery(text);
    }
}

class LLMTool {
    name = "LLMTool";
    desc = "Interacts with LLM for generation or embedding.";

    async exec(action, ctx, thought) {
        const op = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        const prompt = action.args[1];
        if (!op || !prompt) return Term.Atom("error:no_params");
        const input = Term.toString(prompt);
        if (op === 'generate') {
            const resp = await ctx.llm.generate(input);
            return Term.Atom(resp);
        } else if (op === 'embed') {
            return Term.Atom("ok:embed_req");
        }
        return Term.Atom("error:bad_op");
    }
}

class MemoryTool {
    name = "MemoryTool";
    desc = "Manages memory operations.";

    async exec(action, ctx, thought) {
        const op = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (!op) return Term.Atom("error:no_op");

        if (op === 'add') {
            const content = action.args[1];
            if (!content) return Term.Atom("error:no_content");
            const contentStr = Term.toString(content);
            const source = action.args[2]?.kind === 'Atom' ? action.args[2].name : thought.id;
            const type = action.args[3]?.kind === 'Atom' ? action.args[3].name : thought.type;
            await ctx.memory.add({
                id: generateId(),
                content: contentStr,
                meta: { time: new Date().toISOString(), type, source }
            });
            thought.meta.embed_at = new Date().toISOString();
            ctx.thoughts.update(thought);
            return Term.Atom("ok:added");
        } else if (op === 'search') {
            const query = action.args[1];
            const k = action.args[2]?.kind === 'Atom' ? parseInt(action.args[2].name, 10) : 3;
            if (!query) return Term.Atom("error:no_query");
            const queryStr = Term.toString(query);
            const results = await ctx.memory.search(queryStr, isNaN(k) ? 3 : k);
            return Term.List(results.map(r => Term.Atom(r.content)));
        }
        return Term.Atom("error:bad_op");
    }
}

class UITool {
    name = "UITool";
    desc = "Handles user interactions.";

    async exec(action, ctx, thought) {
        const op = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (op !== 'prompt') return Term.Atom("error:bad_op");

        const text = action.args[1];
        if (!text) return Term.Atom("error:no_text");

        const promptText = Term.toString(text);
        const promptId = generateId();
        const prompt = {
            id: generateId(),
            type: Type.PROMPT,
            content: Term.Atom(promptText),
            belief: new Belief(),
            status: Status.PENDING,
            meta: {
                root: thought.meta.root ?? thought.id,
                parent: thought.id,
                created: new Date().toISOString(),
                ui: { text: promptText, id: promptId }
            }
        };

        ctx.engine.addThought(prompt);
        thought.status = Status.WAITING;
        thought.meta.waiting = promptId;
        ctx.thoughts.update(thought);
        return Term.Atom(`ok:prompt:${promptId}`);
    }
}

class GoalTool {
    name = "GoalTool";
    desc = "Suggests goals based on context.";

    async exec(action, ctx, thought) {
        const op = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (op !== 'suggest') return Term.Atom("error:bad_op");

        const ctxTerm = action.args[1] ?? thought.content;
        const ctxStr = Term.toString(ctxTerm);
        const mem = await ctx.memory.search(`Goals related to: ${ctxStr}`, 3);
        const memCtx = mem.map(r => r.content).join("\n - ");

        const prompt = `Context: "${ctxStr}"\nPast: ${memCtx}\n\nSuggest ONE goal.`;
        const sugg = await ctx.llm.generate(prompt);

        if (sugg?.trim()) {
            const suggThought = {
                id: generateId(),
                type: Type.INPUT,
                content: Term.Atom(sugg.trim()),
                belief: new Belief(1, 0),
                status: Status.PENDING,
                meta: {
                    root: thought.meta.root ?? thought.id,
                    parent: thought.id,
                    created: new Date().toISOString(),
                    tags: ['suggested'],
                    source: 'GoalTool'
                }
            };
            ctx.engine.addThought(suggThought);
            return Term.Atom(`ok:sugg:${suggThought.id}`);
        }
        return Term.Atom("ok:no_sugg");
    }
}

class CoreTool {
    name = "CoreTool";
    desc = "Manages internal operations.";

    async exec(action, ctx, thought) {
        const op = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;

        if (op === 'set_status') {
            const id = action.args[1];
            const status = action.args[2];
            if (id?.kind !== 'Atom' || status?.kind !== 'Atom') return Term.Atom("error:bad_params");

            const targetId = id.name;
            const newStatus = status.name;
            if (!Object.values(Status).includes(newStatus)) return Term.Atom("error:bad_status");

            const target = ctx.thoughts.get(targetId);
            if (target) {
                target.status = newStatus;
                ctx.thoughts.update(target);
                return Term.Atom(`ok:status:${targetId}_${newStatus}`);
            }
            return Term.Atom(`error:no_thought:${targetId}`);
        } else if (op === 'add_thought') {
            const type = action.args[1];
            const content = action.args[2];
            const root = action.args[3];
            const parent = action.args[4];
            if (type?.kind !== 'Atom' || !content) return Term.Atom("error:bad_params");

            const tType = type.name;
            if (!Object.values(Type).includes(tType)) return Term.Atom("error:bad_type");

            const newThought = {
                id: generateId(),
                type: tType,
                content,
                belief: new Belief(),
                status: Status.PENDING,
                meta: {
                    root: root?.kind === 'Atom' ? root.name : thought.meta.root ?? thought.id,
                    parent: parent?.kind === 'Atom' ? parent.name : thought.id,
                    created: new Date().toISOString()
                }
            };
            ctx.engine.addThought(newThought);
            return Term.Atom(`ok:thought:${newThought.id}`);
        }
        return Term.Atom("error:bad_op");
    }
}

class ToolRegistry {
    tools = new Map();

    register(tool) {
        this.tools.set(tool.name, tool);
    }

    get(name) {
        return this.tools.get(name);
    }
}

class Engine {
    active = new Set();
    batchSize = 5;
    maxConcurrent = 3;

    constructor(thoughts, rules, memory, llm, tools) {
        this.thoughts = thoughts;
        this.rules = rules;
        this.memory = memory;
        this.llm = llm;
        this.tools = tools;
    }

    addThought(thought) {
        this.thoughts.add(thought);
    }

    sampleThought() {
        const pending = this.thoughts.getPending().filter(t => !this.active.has(t.id));
        if (!pending.length) return null;

        const weights = pending.map(t => t.meta.priority ?? t.belief.score());
        const total = weights.reduce((sum, w) => sum + w, 0);
        if (total <= 0) return pending[Math.floor(Math.random() * pending.length)];

        let rand = Math.random() * total;
        for (let i = 0; i < pending.length; i++) {
            rand -= weights[i];
            if (rand <= 0) return pending[i];
        }
        return pending[pending.length - 1];
    }

    findRule(thought) {
        const matches = this.rules.getAll()
            .map(r => ({ rule: r, binds: Term.unify(r.pattern, thought.content) }))
            .filter(m => m.binds !== null);

        if (!matches.length) return null;
        if (matches.length === 1) return matches[0];

        const weights = matches.map(m => m.rule.belief.score());
        const total = weights.reduce((sum, w) => sum + w, 0);
        if (total <= 0) return matches[Math.floor(Math.random() * matches.length)];

        let rand = Math.random() * total;
        for (let i = 0; i < matches.length; i++) {
            rand -= weights[i];
            if (rand <= 0) return matches[i];
        }
        return matches[matches.length - 1];
    }

    async execAction(thought, rule, binds) {
        const action = Term.subst(rule.action, binds);
        if (action.kind !== 'Struct') {
            this.fail(thought, rule, `Bad action: ${action.kind}`);
            return false;
        }

        const tool = this.tools.get(action.name);
        if (!tool) {
            this.fail(thought, rule, `No tool: ${action.name}`);
            return false;
        }

        try {
            const result = await tool.exec(action, {
                thoughts: this.thoughts,
                rules: this.rules,
                memory: this.memory,
                llm: this.llm,
                engine: this
            }, thought);

            const curr = this.thoughts.get(thought.id);
            if (curr && curr.status !== Status.WAITING && curr.status !== Status.FAILED) {
                this.succeed(thought, rule);
            }

            if (result?.kind === 'Atom' && result.name.startsWith('error:')) {
                this.fail(thought, rule, `Tool failed: ${result.name}`);
                return false;
            }

            if (curr?.status !== Status.WAITING && curr?.status !== Status.FAILED) {
                rule.belief.update(true);
                this.rules.update(rule);
            }
            return true;
        } catch (e) {
            this.fail(thought, rule, `Tool error: ${e.message}`);
            return false;
        }
    }

    async handleNoRule(thought) {
        let prompt = "";
        let type = null;

        switch (thought.type) {
            case Type.INPUT:
                prompt = `Input: "${Term.toString(thought.content)}"\n\nDefine a goal.`;
                type = Type.GOAL;
                break;
            case Type.GOAL:
                prompt = `Goal: "${Term.toString(thought.content)}"\n\nList 1-3 strategies.`;
                type = Type.STRATEGY;
                break;
            case Type.STRATEGY:
                prompt = `Strategy: "${Term.toString(thought.content)}"\n\nSummarize outcome.`;
                type = Type.OUTCOME;
                break;
            default: {
                const ask = `Thought (${thought.type}: ${Term.toString(thought.content)}) has no rules. Next?`;
                const promptId = generateId();
                const promptThought = {
                    id: generateId(),
                    type: Type.PROMPT,
                    content: Term.Atom(ask),
                    belief: new Belief(),
                    status: Status.PENDING,
                    meta: {
                        root: thought.meta.root ?? thought.id,
                        parent: thought.id,
                        created: new Date().toISOString(),
                        ui: { text: ask, id: promptId }
                    }
                };
                this.addThought(promptThought);
                thought.status = Status.WAITING;
                thought.meta.waiting = promptId;
                this.thoughts.update(thought);
                return;
            }
        }

        if (prompt && type) {
            const result = await this.llm.generate(prompt);
            result.split('\n').map(s => s.trim()).filter(s => s).forEach(text => {
                this.addThought({
                    id: generateId(),
                    type,
                    content: Term.Atom(text),
                    belief: new Belief(),
                    status: Status.PENDING,
                    meta: {
                        root: thought.meta.root ?? thought.id,
                        parent: thought.id,
                        created: new Date().toISOString(),
                        source: 'llm'
                    }
                });
            });
            thought.status = Status.DONE;
            thought.belief.update(true);
            this.thoughts.update(thought);
        } else if (thought.status === Status.PENDING) {
            this.fail(thought, null, "No rule or action.");
        }
    }

    fail(thought, rule, error) {
        const retries = (thought.meta.retries ?? 0) + 1;
        thought.meta.error = error.substring(0, 200);
        thought.meta.retries = retries;
        thought.belief.update(false);

        if (rule) {
            rule.belief.update(false);
            this.rules.update(rule);
            thought.meta.rule = rule.id;
        }

        thought.status = retries >= MAX_RETRIES ? Status.FAILED : Status.PENDING;
        this.thoughts.update(thought);
    }

    succeed(thought, rule) {
        thought.status = Status.DONE;
        thought.belief.update(true);
        delete thought.meta.error;
        delete thought.meta.retries;

        if (rule) {
            rule.belief.update(true);
            this.rules.update(rule);
            thought.meta.rule = rule.id;
        }

        this.thoughts.update(thought);
    }

    async processOne() {
        const thought = this.sampleThought();
        if (!thought || this.active.has(thought.id)) return false;

        this.active.add(thought.id);
        thought.status = Status.ACTIVE;
        thought.meta.agent = 'worker';
        this.thoughts.update(thought);

        let success = false;
        try {
            const match = this.findRule(thought);
            if (match) {
                success = await this.execAction(thought, match.rule, match.binds);
            } else {
                await this.handleNoRule(thought);
                success = this.thoughts.get(thought.id)?.status === Status.DONE;
            }
        } catch (e) {
            this.fail(thought, null, `Error: ${e.message}`);
        } finally {
            this.active.delete(thought.id);
            const final = this.thoughts.get(thought.id);
            if (final?.status === Status.ACTIVE) {
                this.fail(thought, null, "Ended unexpectedly.");
            }
        }
        return success;
    }

    async processBatch() {
        let count = 0;
        const toProcess = [];

        while (count < this.batchSize && this.active.size < this.maxConcurrent) {
            const thought = this.sampleThought();
            if (!thought) break;
            toProcess.push(thought);
            this.active.add(thought.id);
            count++;
        }

        const results = await Promise.all(toProcess.map(async thought => {
            thought.status = Status.ACTIVE;
            thought.meta.agent = 'worker';
            this.thoughts.update(thought);

            try {
                const match = this.findRule(thought);
                if (match) {
                    return await this.execAction(thought, match.rule, match.binds);
                } else {
                    await this.handleNoRule(thought);
                    return this.thoughts.get(thought.id)?.status === Status.DONE;
                }
            } catch (e) {
                this.fail(thought, null, `Error: ${e.message}`);
                return false;
            } finally {
                this.active.delete(thought.id);
                const final = this.thoughts.get(thought.id);
                if (final?.status === Status.ACTIVE) {
                    this.fail(thought, null, "Ended unexpectedly.");
                }
            }
        }));

        return results.filter(r => r).length;
    }

    async handleResponse(promptId, text) {
        const waiting = this.thoughts.getAll().find(t =>
            t.meta.waiting === promptId && t.status === Status.WAITING
        );

        if (!waiting) return;

        const respThought = {
            id: generateId(),
            type: Type.INPUT,
            content: Term.Atom(text),
            belief: new Belief(1, 0),
            status: Status.PENDING,
            meta: {
                root: waiting.meta.root ?? waiting.id,
                parent: waiting.id,
                created: new Date().toISOString(),
                resp_to: promptId,
                tags: ['user_resp']
            }
        };

        this.addThought(respThought);
        waiting.status = Status.PENDING;
        delete waiting.meta.waiting;
        waiting.belief.update(true);
        this.thoughts.update(waiting);
    }
}

async function saveState(thoughts, rules, memory) {
    await fs.mkdir(DATA_DIR, { recursive: true });
    const state = {
        thoughts: thoughts.toJSON(),
        rules: rules.toJSON()
    };
    await fs.writeFile(STATE_FILE, JSON.stringify(state, null, 2));
    await memory.save();
}

const saveDebounced = debounce(saveState, 5000);

async function loadState(thoughts, rules, memory) {
    if (!fsSync.existsSync(STATE_FILE)) {
        await memory.init();
        return;
    }

    const data = await fs.readFile(STATE_FILE, 'utf-8');
    const state = parseJSON(data, { thoughts: {}, rules: {} });
    ThoughtStore.fromJSON(state.thoughts).getAll().forEach(t => thoughts.add(t));
    RuleStore.fromJSON(state.rules).getAll().forEach(r => rules.add(r));
    await memory.init();
}

class ConsoleUI {
    constructor(thoughts, control) {
        this.thoughts = thoughts;
        this.control = control;
        this.paused = false;
        this.height = 0;
        thoughts.onChange(() => this.render());
    }

    start() {
        this.rl = readline.createInterface({
            input: process.stdin,
            output: process.stdout,
            prompt: chalk.cyan('FlowMind> ')
        });

        this.rl.on('line', line => {
            const [cmd, ...args] = line.trim().split(' ');
            if (cmd) this.control(cmd.toLowerCase(), args);
            else this.rl.prompt();
        });

        this.rl.on('close', () => this.control('quit'));
        this.interval = setInterval(() => this.render(), UI_REFRESH);
        this.render();
        this.rl.prompt();
    }

    stop() {
        if (this.interval) clearInterval(this.interval);
        this.rl?.close();
        process.stdout.write('\nUI stopped.\n');
    }

    setPaused(paused) {
        this.paused = paused;
        this.render();
    }

    render() {
        const thoughts = this.thoughts.getAll();
        const roots = thoughts.filter(t => t.type === Type.INPUT && !t.meta.parent);
        let output = "";
        const width = process.stdout.columns || 80;

        if (this.height > 0) {
            process.stdout.write(`\x1b[${this.height}A\x1b[J`);
        }

        output += chalk.gray('='.repeat(width)) + "\n";
        output += chalk.bold(` FlowMind ${this.paused ? chalk.red('[PAUSED]') : chalk.green('[RUNNING]')}\n`);
        output += chalk.gray('='.repeat(width)) + "\n";

        const prompts = [];
        roots.forEach(root => {
            output += `\n${chalk.blue('Note')}: ${Term.format(root.content).substring(0, width - 15)} [${chalk.gray(root.id.substring(0, 6))}] (${root.status})\n`;
            output += chalk.gray('-'.repeat(width / 2)) + "\n";

            const children = this.thoughts.getByRoot(root.id).filter(t => t.id !== root.id);
            children.forEach(t => {
                const prefix = `  [${chalk.gray(t.id.substring(0, 6))}|${t.type.padEnd(8)}|${t.status.padEnd(7)}|P:${t.belief.score().toFixed(2)}] `;
                const content = Term.format(t.content);
                const maxLen = width - prefix.length - 1;
                const trunc = content.length > maxLen ? content.substring(0, maxLen - 3) + "..." : content;
                output += prefix + chalk.white(trunc) + "\n";

                if (t.meta.error) {
                    output += chalk.red(`    Error: ${t.meta.error.substring(0, width - 12)}\n`);
                }

                if (t.meta.tags?.length) {
                    output += chalk.gray(`    Tags: ${t.meta.tags.join(', ')}\n`);
                }

                if (t.type === Type.PROMPT && t.status === Status.PENDING && t.meta.ui?.id) {
                    prompts.push(t);
                }
            });
        });

        if (prompts.length > 0) {
            output += "\n" + chalk.yellow('--- Prompts ---') + "\n";
            prompts.forEach(p => {
                const id = p.meta.ui?.id ?? 'unknown';
                const parent = p.meta.parent;
                output += chalk.yellow(`[${id.substring(0, 6)}] ${Term.format(p.content)} (Waiting: ${parent?.substring(0, 6) ?? 'N/A'})\n`);
            });
        }

        output += "\n" + chalk.gray('='.repeat(width)) + "\n";
        output += chalk.gray("Commands: add <note>, respond <prompt_id> <text>, run, pause, step, save, quit, list, tag <id> <tag>, search <query>, delete <id>\n");

        const lines = output.split('\n');
        this.height = lines.length + 1;
        readline.cursorTo(process.stdout, 0, 0);
        readline.clearScreenDown(process.stdout);
        process.stdout.write(output);
        this.rl?.prompt(true);
    }
}

class FlowMind {
    constructor() {
        this.thoughts = new ThoughtStore();
        this.rules = new RuleStore();
        this.llm = new LLMService();
        this.memory = new MemoryStore(this.llm.embeddings, VECTOR_STORE);
        this.tools = new ToolRegistry();
        this.engine = new Engine(this.thoughts, this.rules, this.memory, this.llm, this.tools);
        this.ui = new ConsoleUI(this.thoughts, this.handleCmd.bind(this));
        this.running = true;
        this.registerTools();
    }

    registerTools() {
        [new LLMTool(), new MemoryTool(), new UITool(), new GoalTool(), new CoreTool()].forEach(t => this.tools.register(t));
    }

    bootstrapRules() {
        if (this.rules.getAll().length > 0) return;

        const rules = [
            {
                id: generateId(),
                pattern: Term.Var("Content"),
                action: Term.Struct("LLMTool", [
                    Term.Atom("generate"),
                    Term.Struct("prompt", [Term.Atom(`Generate GOAL for: ${Term.format(Term.Var("Content"))}`)]),
                    Term.Atom(Type.GOAL)
                ]),
                desc: "Generate goal from input"
            },
            {
                id: generateId(),
                pattern: Term.Var("Content"),
                action: Term.Struct("LLMTool", [
                    Term.Atom("generate"),
                    Term.Struct("prompt", [Term.Atom(`Generate 1-3 STRATEGIES for: ${Term.format(Term.Var("Content"))}`)]),
                    Term.Atom(Type.STRATEGY)
                ]),
                desc: "Generate strategies from goal"
            },
            {
                id: generateId(),
                pattern: Term.Var("Content"),
                action: Term.Struct("LLMTool", [
                    Term.Atom("generate"),
                    Term.Struct("prompt", [Term.Atom(`Generate OUTCOME for: ${Term.format(Term.Var("Content"))}`)]),
                    Term.Atom(Type.OUTCOME)
                ]),
                desc: "Generate outcome from strategy"
            },
            {
                id: generateId(),
                pattern: Term.Var("Content"),
                action: Term.Struct("MemoryTool", [
                    Term.Atom("add"),
                    Term.Var("Content"),
                    Term.Var("ThoughtId"),
                    Term.Atom(Type.OUTCOME)
                ]),
                desc: "Store outcome"
            },
            {
                id: generateId(),
                pattern: Term.Var("Content"),
                action: Term.Struct("GoalTool", [
                    Term.Atom("suggest"),
                    Term.Var("Content")
                ]),
                desc: "Suggest goal"
            }
        ];

        rules.forEach((r, i) => this.rules.add({
            ...r,
            belief: new Belief(),
            meta: {
                desc: r.desc,
                source: 'bootstrap',
                created: new Date().toISOString()
            }
        }));
    }

    async init() {
        await fs.mkdir(DATA_DIR, { recursive: true });
        await loadState(this.thoughts, this.rules, this.memory);
        this.bootstrapRules();
        this.ui.start();
        this.startLoop();
    }

    startLoop() {
        if (this.worker) clearInterval(this.worker);
        this.worker = setInterval(async () => {
            if (this.running) {
                const processed = await this.engine.processBatch();
                if (processed > 0) saveDebounced(this.thoughts, this.rules, this.memory);
            }
        }, WORKER_INTERVAL);
    }

    stopLoop() {
        if (this.worker) clearInterval(this.worker);
        this.worker = null;
        this.ui.setPaused(true);
    }

    async handleCmd(cmd, args = []) {
        switch (cmd) {
            case 'add': {
                const text = args.join(' ');
                if (!text) {
                    console.log(chalk.red("Usage: add <note>"));
                    return this.ui.rl?.prompt();
                }

                const thought = {
                    id: generateId(),
                    type: Type.INPUT,
                    content: Term.Atom(text),
                    belief: new Belief(1, 0),
                    status: Status.PENDING,
                    meta: {
                        created: new Date().toISOString(),
                        root: generateId(),
                        tags: ['user']
                    }
                };
                thought.meta.root = thought.id;
                this.engine.addThought(thought);
                saveDebounced(this.thoughts, this.rules, this.memory);
                this.ui.rl?.prompt();
                break;
            }
            case 'respond': {
                const id = args[0];
                const text = args.slice(1).join(' ');
                if (!id || !text) {
                    console.log(chalk.red("Usage: respond <prompt_id> <text>"));
                    return this.ui.rl?.prompt();
                }

                const fullId = this.thoughts.getAll()
                    .map(t => t.meta?.ui?.id)
                    .find(pid => pid?.startsWith(id));

                if (!fullId) {
                    console.log(chalk.red(`Prompt "${id}" not found.`));
                    return this.ui.rl?.prompt();
                }

                await this.engine.handleResponse(fullId, text);
                saveDebounced(this.thoughts, this.rules, this.memory);
                this.ui.rl?.prompt();
                break;
            }
            case 'run': {
                if (!this.running) {
                    this.running = true;
                    this.startLoop();
                    this.ui.setPaused(false);
                }
                this.ui.rl?.prompt();
                break;
            }
            case 'pause': {
                if (this.running) {
                    this.running = false;
                    this.stopLoop();
                }
                this.ui.rl?.prompt();
                break;
            }
            case 'step': {
                if (!this.running) {
                    const processed = await this.engine.processOne();
                    if (processed) saveDebounced(this.thoughts, this.rules, this.memory);
                    this.ui.rl?.prompt();
                } else {
                    console.log(chalk.red("Pause system to step."));
                    this.ui.rl?.prompt();
                }
                break;
            }
            case 'save': {
                saveDebounced.cancel();
                await saveState(this.thoughts, this.rules, this.memory);
                this.ui.rl?.prompt();
                break;
            }
            case 'quit':
            case 'exit': {
                await this.shutdown();
                break;
            }
            case 'list': {
                const thoughts = this.thoughts.getAll();
                console.log(chalk.blue(`\nThoughts: ${thoughts.length}`));
                thoughts.forEach(t => {
                    console.log(`[${chalk.gray(t.id.substring(0, 6))}] ${t.type}: ${Term.format(t.content)} (${t.status})`);
                });
                this.ui.rl?.prompt();
                break;
            }
            case 'tag': {
                const id = args[0];
                const tag = args[1];
                if (!id || !tag) {
                    console.log(chalk.red("Usage: tag <id> <tag>"));
                    return this.ui.rl?.prompt();
                }

                const thought = this.thoughts.getAll().find(t => t.id.startsWith(id));
                if (!thought) {
                    console.log(chalk.red(`Thought "${id}" not found.`));
                    return this.ui.rl?.prompt();
                }

                thought.meta.tags = thought.meta.tags || [];
                if (!thought.meta.tags.includes(tag)) {
                    thought.meta.tags.push(tag);
                    this.thoughts.update(thought);
                    saveDebounced(this.thoughts, this.rules, this.memory);
                }
                this.ui.rl?.prompt();
                break;
            }
            case 'search': {
                const query = args.join(' ');
                if (!query) {
                    console.log(chalk.red("Usage: search <query>"));
                    return this.ui.rl?.prompt();
                }

                const results = await this.memory.search(query, 5);
                console.log(chalk.blue(`\nResults for "${query}":`));
                results.forEach(r => {
                    console.log(`[${chalk.gray(r.id.substring(0, 6))}] ${r.content.substring(0, 50)}${r.content.length > 50 ? '...' : ''}`);
                });
                this.ui.rl?.prompt();
                break;
            }
            case 'delete': {
                const id = args[0];
                if (!id) {
                    console.log(chalk.red("Usage: delete <id>"));
                    return this.ui.rl?.prompt();
                }

                const thought = this.thoughts.getAll().find(t => t.id.startsWith(id));
                if (!thought) {
                    console.log(chalk.red(`Thought "${id}" not found.`));
                    return this.ui.rl?.prompt();
                }

                this.thoughts.delete(thought.id);
                saveDebounced(this.thoughts, this.rules, this.memory);
                console.log(chalk.green(`Thought ${id} deleted.`));
                this.ui.rl?.prompt();
                break;
            }
            default: {
                console.log(chalk.red(`Unknown: ${cmd}`));
                this.ui.rl?.prompt();
            }
        }
    }

    async shutdown() {
        this.running = false;
        this.stopLoop();
        this.ui.stop();
        saveDebounced.cancel();
        await saveState(this.thoughts, this.rules, this.memory);
        process.exit(0);
    }
}

async function main() {
    const system = new FlowMind();
    process.on('SIGINT', () => system.shutdown());
    process.on('SIGTERM', () => system.shutdown());
    await system.init();
}

main().catch(e => {
    console.error("Error:", e);
    process.exit(1);
});