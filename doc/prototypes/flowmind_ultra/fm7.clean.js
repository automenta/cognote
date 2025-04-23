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
    Type["USER_PROMPT"] = "USER_PROMPT";
    Type["SYSTEM"] = "SYSTEM";
})(Type || (Type = {}));
function generateId() { return (0, uuid_1.v4)(); }
function shortId(id) { return id ? id.substring(0, SHORT_ID_LEN) : '------'; }
function debounce(func, wait) {
    let timeout = null;
    const debounced = (...args) => {
        const later = () => { timeout = null; func(...args); };
        if (timeout)
            clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
    debounced.cancel = () => { if (timeout) {
        clearTimeout(timeout);
        timeout = null;
    } };
    return debounced;
}
function safeJsonParse(json, defaultValue) {
    if (!json)
        return defaultValue;
    try {
        return JSON.parse(json);
    }
    catch {
        return defaultValue;
    }
}
function sleep(ms) { return new Promise(resolve => setTimeout(resolve, ms)); }
class Belief {
    pos;
    neg;
    constructor(pos = DEFAULT_BELIEF_POS, neg = DEFAULT_BELIEF_NEG) {
        this.pos = pos;
        this.neg = neg;
    }
    score() { return (this.pos + 1) / (this.pos + this.neg + 2); }
    update(success) { success ? this.pos++ : this.neg++; }
    toJSON() { return { pos: this.pos, neg: this.neg }; }
    static fromJSON(data) { return new Belief(data.pos, data.neg); }
    static DEFAULT = new Belief();
}
var TermLogic;
(function (TermLogic) {
    TermLogic.Atom = (name) => ({ kind: 'Atom', name });
    TermLogic.Variable = (name) => ({ kind: 'Variable', name });
    TermLogic.Structure = (name, args) => ({ kind: 'Structure', name, args });
    TermLogic.List = (elements) => ({ kind: 'ListTerm', elements });
    function format(term) {
        if (!term)
            return chalk_1.default.grey('null');
        switch (term.kind) {
            case 'Atom': return chalk_1.default.green(term.name);
            case 'Variable': return chalk_1.default.cyan(`?${term.name}`);
            case 'Structure': return `${chalk_1.default.yellow(term.name)}(${term.args.map(format).join(', ')})`;
            case 'ListTerm': return `[${term.elements.map(format).join(', ')}]`;
            default: return chalk_1.default.red('invalid_term');
        }
    }
    TermLogic.format = format;
    function toString(term) {
        switch (term.kind) {
            case 'Atom': return term.name;
            case 'Variable': return `?${term.name}`;
            case 'Structure': return `${term.name}: ${term.args.map(toString).join('; ')}`;
            case 'ListTerm': return term.elements.map(toString).join(', ');
            default: return '';
        }
    }
    TermLogic.toString = toString;
    function fromString(input) {
        input = input.trim();
        if (input.includes(':') && input.includes(';')) {
            const [name, argsStr] = input.split(':', 2);
            const args = argsStr.split(';').map(s => TermLogic.Atom(s.trim()));
            return TermLogic.Structure(name.trim(), args);
        }
        if (input.startsWith('[') && input.endsWith(']')) {
            const elements = input.slice(1, -1).split(',').map(s => TermLogic.Atom(s.trim()));
            return TermLogic.List(elements);
        }
        return TermLogic.Atom(input);
    }
    TermLogic.fromString = fromString;
    function unify(term1, term2, bindings = new Map()) {
        const resolve = (term, currentBindings) => {
            if (term.kind === 'Variable' && currentBindings.has(term.name)) {
                return resolve(currentBindings.get(term.name), currentBindings);
            }
            return term;
        };
        const t1 = resolve(term1, bindings);
        const t2 = resolve(term2, bindings);
        if (t1.kind === 'Variable') {
            if (t1.name === t2.name && t1.kind === t2.kind)
                return bindings;
            const newBindings = new Map(bindings);
            newBindings.set(t1.name, t2);
            return newBindings;
        }
        if (t2.kind === 'Variable') {
            const newBindings = new Map(bindings);
            newBindings.set(t2.name, t1);
            return newBindings;
        }
        if (t1.kind !== t2.kind)
            return null;
        switch (t1.kind) {
            case 'Atom': return t1.name === t2.name ? bindings : null;
            case 'Structure': {
                const s2 = t2;
                if (t1.name !== s2.name || t1.args.length !== s2.args.length)
                    return null;
                let currentBindings = bindings;
                for (let i = 0; i < t1.args.length; i++) {
                    const result = unify(t1.args[i], s2.args[i], currentBindings);
                    if (!result)
                        return null;
                    currentBindings = result;
                }
                return currentBindings;
            }
            case 'ListTerm': {
                const l2 = t2;
                if (t1.elements.length !== l2.elements.length)
                    return null;
                let currentBindings = bindings;
                for (let i = 0; i < t1.elements.length; i++) {
                    const result = unify(t1.elements[i], l2.elements[i], currentBindings);
                    if (!result)
                        return null;
                    currentBindings = result;
                }
                return currentBindings;
            }
        }
        return null;
    }
    TermLogic.unify = unify;
    function substitute(term, bindings) {
        if (term.kind === 'Variable' && bindings.has(term.name)) {
            return substitute(bindings.get(term.name), bindings);
        }
        if (term.kind === 'Structure') {
            return { ...term, args: term.args.map(arg => substitute(arg, bindings)) };
        }
        if (term.kind === 'ListTerm') {
            return { ...term, elements: term.elements.map(el => substitute(el, bindings)) };
        }
        return term;
    }
    TermLogic.substitute = substitute;
})(TermLogic || (TermLogic = {}));
class BaseStore {
    items = new Map();
    listeners = [];
    add(item) { this.items.set(item.id, item); this.notifyChange(); }
    get(id) { return this.items.get(id); }
    update(item) {
        if (this.items.has(item.id)) {
            const existing = this.items.get(item.id);
            const updatedItem = {
                ...item,
                metadata: { ...existing?.metadata, ...item.metadata, modified: new Date().toISOString() }
            };
            this.items.set(item.id, updatedItem);
            this.notifyChange();
        }
    }
    delete(id) {
        const deleted = this.items.delete(id);
        if (deleted)
            this.notifyChange();
        return deleted;
    }
    getAll() { return Array.from(this.items.values()); }
    count() { return this.items.size; }
    findItemByPrefix(prefix) {
        if (this.items.has(prefix))
            return this.items.get(prefix);
        for (const item of this.items.values()) {
            if (item.id.startsWith(prefix))
                return item;
        }
        return undefined;
    }
    addChangeListener(listener) { this.listeners.push(listener); }
    removeChangeListener(listener) { this.listeners = this.listeners.filter(l => l !== listener); }
    notifyChange() { this.listeners.forEach(listener => listener()); }
}
class ThoughtStore extends BaseStore {
    getPending() { return this.getAll().filter(t => t.status === Status.PENDING); }
    getAllByRootId(rootId) {
        return this.getAll()
            .filter(t => t.metadata.rootId === rootId || t.id === rootId)
            .sort((a, b) => Date.parse(a.metadata.created ?? '0') - Date.parse(b.metadata.created ?? '0'));
    }
    searchByTag(tag) { return this.getAll().filter(t => t.metadata.tags?.includes(tag)); }
    findThought(idPrefix) { return this.findItemByPrefix(idPrefix); }
    toJSON() {
        const obj = {};
        this.items.forEach((thought, id) => obj[id] = { ...thought, belief: thought.belief.toJSON() });
        return obj;
    }
    loadJSON(data) {
        this.items.clear();
        Object.entries(data).forEach(([id, thoughtData]) => {
            this.add({ ...thoughtData, belief: Belief.fromJSON(thoughtData.belief), id });
        });
    }
}
class RuleStore extends BaseStore {
    searchByDescription(desc) {
        const lowerDesc = desc.toLowerCase();
        return this.getAll().filter(r => r.metadata.description?.toLowerCase().includes(lowerDesc));
    }
    findRule(idPrefix) { return this.findItemByPrefix(idPrefix); }
    toJSON() {
        const obj = {};
        this.items.forEach((rule, id) => obj[id] = { ...rule, belief: rule.belief.toJSON() });
        return obj;
    }
    loadJSON(data) {
        this.items.clear();
        Object.entries(data).forEach(([id, ruleData]) => {
            this.add({ ...ruleData, belief: Belief.fromJSON(ruleData.belief), id });
        });
    }
}
class MemoryStore {
    vectorStore = null;
    embeddings;
    storePath;
    isReady = false;
    constructor(embeddingsService, storePath) {
        this.embeddings = embeddingsService;
        this.storePath = storePath;
    }
    async initialize() {
        if (this.isReady)
            return;
        try {
            await fs.access(this.storePath);
            this.vectorStore = await faiss_1.FaissStore.load(this.storePath, this.embeddings);
            this.isReady = true;
            console.log(chalk_1.default.blue(`Vector store loaded from ${this.storePath}`));
        }
        catch (error) {
            if (error.code === 'ENOENT') {
                console.log(chalk_1.default.yellow(`Vector store not found at ${this.storePath}, initializing.`));
                try {
                    const dummyDoc = new documents_1.Document({ pageContent: "Init", metadata: { sourceId: "init", type: "SYSTEM", created: new Date().toISOString() } });
                    this.vectorStore = await faiss_1.FaissStore.fromDocuments([dummyDoc], this.embeddings);
                    await this.vectorStore.save(this.storePath);
                    this.isReady = true;
                    console.log(chalk_1.default.green(`New vector store created at ${this.storePath}`));
                }
                catch (initError) {
                    console.error(chalk_1.default.red('Failed to initialize new vector store:'), initError);
                }
            }
            else {
                console.error(chalk_1.default.red('Failed to load vector store:'), error);
            }
        }
    }
    async add(entry) {
        if (!this.vectorStore || !this.isReady) {
            console.warn(chalk_1.default.yellow('MemoryStore not ready, cannot add entry.'));
            return;
        }
        const doc = new documents_1.Document({ pageContent: entry.content, metadata: { ...entry.metadata, id: entry.id } });
        try {
            await this.vectorStore.addDocuments([doc]);
            await this.save();
        }
        catch (error) {
            console.error(chalk_1.default.red('Failed to add document to vector store:'), error);
        }
    }
    async search(query, k = 5) {
        if (!this.vectorStore || !this.isReady) {
            console.warn(chalk_1.default.yellow('MemoryStore not ready, cannot search.'));
            return [];
        }
        try {
            const results = await this.vectorStore.similaritySearchWithScore(query, k);
            return results.map(([doc, score]) => ({
                id: doc.metadata.id || generateId(),
                embedding: [],
                content: doc.pageContent,
                metadata: doc.metadata,
                score: score
            }));
        }
        catch (error) {
            console.error(chalk_1.default.red('Failed to search vector store:'), error);
            return [];
        }
    }
    async save() {
        if (this.vectorStore && this.isReady) {
            try {
                await this.vectorStore.save(this.storePath);
            }
            catch (error) {
                console.error(chalk_1.default.red('Failed to save vector store:'), error);
            }
        }
    }
}
class LLMService {
    llm;
    embeddings;
    constructor() {
        this.llm = new ollama_1.ChatOllama({ baseUrl: OLLAMA_BASE_URL, model: OLLAMA_MODEL, temperature: 0.7 });
        this.embeddings = new ollama_2.OllamaEmbeddings({ model: OLLAMA_EMBEDDING_MODEL, baseUrl: OLLAMA_BASE_URL });
        console.log(chalk_1.default.blue(`LLM Service: Model=${OLLAMA_MODEL}, Embeddings=${OLLAMA_EMBEDDING_MODEL}, URL=${OLLAMA_BASE_URL}`));
    }
    async generate(prompt) {
        try {
            const response = await this.llm.pipe(new output_parsers_1.StringOutputParser()).invoke([new messages_1.HumanMessage(prompt)]);
            return response.trim();
        }
        catch (error) {
            console.error(chalk_1.default.red(`LLM generation failed: ${error.message}`));
            return `Error: LLM generation failed. ${error.message}`;
        }
    }
    async embed(text) {
        try {
            return await this.embeddings.embedQuery(text);
        }
        catch (error) {
            console.error(chalk_1.default.red(`Embedding failed: ${error.message}`));
            return [];
        }
    }
}
class LLMTool {
    name = "LLMTool";
    description = "Interacts with the LLM for generation.";
    async execute(action, context, trigger) {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        const promptTerm = action.args[1];
        if (operation !== 'generate' || !promptTerm)
            return TermLogic.Atom("error:LLMTool_invalid_params");
        const response = await context.llm.generate(TermLogic.toString(promptTerm));
        return response.startsWith('Error:')
            ? TermLogic.Atom(`error:LLMTool_generation_failed:${response}`)
            : TermLogic.Atom(response);
    }
}
class MemoryTool {
    name = "MemoryTool";
    description = "Manages memory operations: add, search.";
    async execute(action, context, trigger) {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (!operation)
            return TermLogic.Atom("error:MemoryTool_missing_operation");
        if (operation === 'add') {
            const contentTerm = action.args[1];
            if (!contentTerm)
                return TermLogic.Atom("error:MemoryTool_missing_add_content");
            await context.memory.add({
                id: generateId(),
                content: TermLogic.toString(contentTerm),
                metadata: { created: new Date().toISOString(), type: trigger.type, sourceId: trigger.id }
            });
            trigger.metadata.embedded = new Date().toISOString();
            context.thoughts.update(trigger);
            return TermLogic.Atom("ok:memory_added");
        }
        else if (operation === 'search') {
            const queryTerm = action.args[1];
            const kTerm = action.args[2];
            if (!queryTerm)
                return TermLogic.Atom("error:MemoryTool_missing_search_query");
            const queryStr = TermLogic.toString(queryTerm);
            const k = (kTerm?.kind === 'Atom' && !isNaN(parseInt(kTerm.name))) ? parseInt(kTerm.name, 10) : 3;
            const results = await context.memory.search(queryStr, k);
            return TermLogic.List(results.map(r => TermLogic.Atom(r.content)));
        }
        return TermLogic.Atom(`error:MemoryTool_unsupported_operation:${operation}`);
    }
}
class UserInteractionTool {
    name = "UserInteractionTool";
    description = "Requests input from the user via a prompt.";
    async execute(action, context, trigger) {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (operation !== 'prompt')
            return TermLogic.Atom("error:UITool_unsupported_operation");
        const promptTextTerm = action.args[1];
        if (!promptTextTerm)
            return TermLogic.Atom("error:UITool_missing_prompt_text");
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
class GoalProposalTool {
    name = "GoalProposalTool";
    description = "Suggests new goals based on context and memory.";
    async execute(action, context, trigger) {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (operation !== 'suggest')
            return TermLogic.Atom("error:GoalTool_unsupported_operation");
        const contextTerm = action.args[1] ?? trigger.content;
        const contextStr = TermLogic.toString(contextTerm);
        const memoryResults = await context.memory.search(`Relevant past goals or outcomes related to: ${contextStr}`, 3);
        const memoryContext = memoryResults.length > 0 ? `\nRelated past activities:\n - ${memoryResults.map(r => r.content).join("\n - ")}` : "";
        const prompt = `Based on the current context "${contextStr}"${memoryContext}\n\nSuggest ONE concise, actionable next goal or task. Output only the suggested goal text.`;
        const suggestionText = await context.llm.generate(prompt);
        if (suggestionText && !suggestionText.startsWith('Error:')) {
            const suggestionThought = {
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
class CoreTool {
    name = "CoreTool";
    description = "Manages internal FlowMind operations (status, thoughts).";
    async execute(action, context, trigger) {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        switch (operation) {
            case 'set_status': {
                const targetIdTerm = action.args[1];
                const newStatusTerm = action.args[2];
                if (targetIdTerm?.kind !== 'Atom' || newStatusTerm?.kind !== 'Atom')
                    return TermLogic.Atom("error:CoreTool_invalid_set_status_params");
                const newStatus = newStatusTerm.name.toUpperCase();
                if (!Object.values(Status).includes(newStatus))
                    return TermLogic.Atom(`error:CoreTool_invalid_status_value:${newStatusTerm.name}`);
                const targetThought = context.thoughts.findThought(targetIdTerm.name);
                if (targetThought) {
                    targetThought.status = newStatus;
                    context.thoughts.update(targetThought);
                    return TermLogic.Atom(`ok:status_set:${shortId(targetIdTerm.name)}_to_${newStatus}`);
                }
                return TermLogic.Atom(`error:CoreTool_target_not_found:${targetIdTerm.name}`);
            }
            case 'add_thought': {
                const typeTerm = action.args[1];
                const contentTerm = action.args[2];
                const rootIdTerm = action.args[3];
                const parentIdTerm = action.args[4];
                if (typeTerm?.kind !== 'Atom' || !contentTerm)
                    return TermLogic.Atom("error:CoreTool_invalid_add_thought_params");
                const type = typeTerm.name.toUpperCase();
                if (!Object.values(Type).includes(type))
                    return TermLogic.Atom(`error:CoreTool_invalid_thought_type:${typeTerm.name}`);
                const newThought = {
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
                if (targetIdTerm?.kind !== 'Atom')
                    return TermLogic.Atom("error:CoreTool_invalid_delete_thought_params");
                const targetThought = context.thoughts.findThought(targetIdTerm.name);
                if (targetThought) {
                    const deleted = context.thoughts.delete(targetThought.id);
                    return deleted ? TermLogic.Atom(`ok:thought_deleted:${shortId(targetThought.id)}`) : TermLogic.Atom(`error:CoreTool_delete_failed:${targetIdTerm.name}`);
                }
                return TermLogic.Atom(`error:CoreTool_thought_not_found:${targetIdTerm.name}`);
            }
            default: return TermLogic.Atom(`error:CoreTool_unsupported_operation:${operation}`);
        }
    }
}
class ToolRegistry {
    tools = new Map();
    register(tool) {
        if (this.tools.has(tool.name))
            console.warn(chalk_1.default.yellow(`Tool "${tool.name}" redefined.`));
        this.tools.set(tool.name, tool);
    }
    get(name) { return this.tools.get(name); }
    list() { return Array.from(this.tools.values()); }
}
class Engine {
    thoughts;
    rules;
    memory;
    llm;
    tools;
    activeIds = new Set();
    batchSize = 5;
    maxConcurrent = 3;
    context;
    constructor(thoughts, rules, memory, llm, tools) {
        this.thoughts = thoughts;
        this.rules = rules;
        this.memory = memory;
        this.llm = llm;
        this.tools = tools;
        this.context = { thoughts: this.thoughts, rules: this.rules, memory: this.memory, llm: this.llm, engine: this };
    }
    addThought(thought) { this.thoughts.add(thought); }
    sampleThought() {
        const candidates = this.thoughts.getPending().filter(t => !this.activeIds.has(t.id));
        if (candidates.length === 0)
            return null;
        const weights = candidates.map(t => t.metadata.priority ?? t.belief.score());
        const totalWeight = weights.reduce((sum, w) => sum + w, 0);
        if (totalWeight <= 0)
            return candidates[Math.floor(Math.random() * candidates.length)];
        let random = Math.random() * totalWeight;
        for (let i = 0; i < candidates.length; i++) {
            random -= weights[i];
            if (random <= 0)
                return candidates[i];
        }
        return candidates[candidates.length - 1];
    }
    findAndSelectRule(thought) {
        const matches = this.rules.getAll()
            .map(rule => ({ rule, bindings: TermLogic.unify(rule.pattern, thought.content) }))
            .filter(m => m.bindings !== null);
        if (matches.length === 0)
            return null;
        if (matches.length === 1)
            return matches[0];
        const weights = matches.map(m => m.rule.belief.score());
        const totalWeight = weights.reduce((sum, w) => sum + w, 0);
        if (totalWeight <= 0)
            return matches[Math.floor(Math.random() * matches.length)];
        let random = Math.random() * totalWeight;
        for (let i = 0; i < matches.length; i++) {
            random -= weights[i];
            if (random <= 0)
                return matches[i];
        }
        return matches[matches.length - 1];
    }
    async executeAction(thought, rule, bindings) {
        const boundAction = TermLogic.substitute(rule.action, bindings);
        if (boundAction.kind !== 'Structure') {
            this.handleFailure(thought, rule, `Invalid action term kind: ${boundAction.kind}`);
            return false;
        }
        const tool = this.tools.get(boundAction.name);
        if (!tool) {
            this.handleFailure(thought, rule, `Tool not found: ${boundAction.name}`);
            return false;
        }
        let success = false;
        try {
            const resultTerm = await tool.execute(boundAction, this.context, thought);
            const currentThoughtState = this.thoughts.get(thought.id);
            const isWaiting = currentThoughtState?.status === Status.WAITING;
            const isFailed = currentThoughtState?.status === Status.FAILED;
            if (resultTerm?.kind === 'Atom' && resultTerm.name.startsWith('error:')) {
                this.handleFailure(thought, rule, `Tool execution failed: ${resultTerm.name}`);
            }
            else if (!isWaiting && !isFailed) {
                this.handleSuccess(thought, rule);
                success = true;
            }
        }
        catch (error) {
            console.error(chalk_1.default.red(`Tool exception ${tool.name} on ${shortId(thought.id)}:`), error);
            this.handleFailure(thought, rule, `Tool exception: ${error.message}`);
        }
        const finalThoughtState = this.thoughts.get(thought.id);
        if (finalThoughtState?.status !== Status.WAITING) {
            rule.belief.update(success);
            this.rules.update(rule);
        }
        return success;
    }
    async handleNoRuleMatch(thought) {
        let prompt = "";
        let targetType = null;
        let action = null;
        switch (thought.type) {
            case Type.INPUT:
                prompt = `Input: "${TermLogic.toString(thought.content)}". Define GOAL. Output only goal.`;
                targetType = Type.GOAL;
                break;
            case Type.GOAL:
                prompt = `Goal: "${TermLogic.toString(thought.content)}". Outline 1-3 STRATEGY steps. Output steps, one per line.`;
                targetType = Type.STRATEGY;
                break;
            case Type.STRATEGY:
                prompt = `Strategy step "${TermLogic.toString(thought.content)}" performed. Summarize likely OUTCOME. Output outcome.`;
                targetType = Type.OUTCOME;
                break;
            case Type.OUTCOME:
                action = TermLogic.Structure("MemoryTool", [TermLogic.Atom("add"), thought.content]);
                break;
            default:
                const askUserPrompt = `No rule for ${shortId(thought.id)} (${thought.type}: ${TermLogic.toString(thought.content).substring(0, 50)}...). Task?`;
                action = TermLogic.Structure("UserInteractionTool", [TermLogic.Atom("prompt"), TermLogic.Atom(askUserPrompt)]);
                break;
        }
        if (prompt && targetType) {
            const resultText = await this.llm.generate(prompt);
            if (resultText && !resultText.startsWith('Error:')) {
                resultText.split('\n').map(s => s.trim().replace(/^- /, '')).filter(s => s).forEach(resText => {
                    this.addThought({
                        id: generateId(), type: targetType, content: TermLogic.Atom(resText),
                        belief: Belief.DEFAULT, status: Status.PENDING,
                        metadata: { rootId: thought.metadata.rootId ?? thought.id, parentId: thought.id, created: new Date().toISOString(), provenance: 'llm_fallback' }
                    });
                });
                thought.status = Status.DONE;
                thought.belief.update(true);
                this.thoughts.update(thought);
            }
            else {
                this.handleFailure(thought, null, `LLM fallback failed: ${resultText}`);
            }
        }
        else if (action?.kind === 'Structure') {
            const tool = this.tools.get(action.name);
            if (tool) {
                try {
                    await tool.execute(action, this.context, thought);
                    const currentStatus = this.thoughts.get(thought.id)?.status;
                    if (currentStatus !== Status.WAITING && currentStatus !== Status.FAILED) {
                        thought.status = Status.DONE;
                        thought.belief.update(true);
                        this.thoughts.update(thought);
                    }
                }
                catch (error) {
                    this.handleFailure(thought, null, `Fallback tool fail (${action.name}): ${error.message}`);
                }
            }
            else {
                this.handleFailure(thought, null, `Fallback tool not found: ${action.name}`);
            }
        }
        else if (thought.status === Status.PENDING) {
            this.handleFailure(thought, null, "No rule match and no fallback action.");
        }
    }
    handleFailure(thought, rule, errorInfo) {
        const retries = (thought.metadata.retries ?? 0) + 1;
        thought.status = retries >= MAX_RETRIES ? Status.FAILED : Status.PENDING;
        thought.metadata.error = errorInfo.substring(0, 250);
        thought.metadata.retries = retries;
        thought.belief.update(false);
        if (rule)
            thought.metadata.ruleId = rule.id;
        this.thoughts.update(thought);
        console.warn(chalk_1.default.yellow(`Thought ${shortId(thought.id)} failed (Attempt ${retries}): ${errorInfo}`));
    }
    handleSuccess(thought, rule) {
        thought.status = Status.DONE;
        thought.belief.update(true);
        delete thought.metadata.error;
        delete thought.metadata.retries;
        if (rule)
            thought.metadata.ruleId = rule.id;
        this.thoughts.update(thought);
    }
    async _processThought(thought) {
        thought.status = Status.ACTIVE;
        thought.metadata.agentId = 'worker';
        this.thoughts.update(thought);
        let success = false;
        try {
            const match = this.findAndSelectRule(thought);
            if (match) {
                success = await this.executeAction(thought, match.rule, match.bindings);
            }
            else {
                await this.handleNoRuleMatch(thought);
                success = this.thoughts.get(thought.id)?.status === Status.DONE;
            }
        }
        catch (error) {
            console.error(chalk_1.default.red(`Critical error processing ${shortId(thought.id)}:`), error);
            this.handleFailure(thought, null, `Unhandled processing exception: ${error.message}`);
        }
        finally {
            this.activeIds.delete(thought.id);
            const finalThoughtState = this.thoughts.get(thought.id);
            if (finalThoughtState?.status === Status.ACTIVE) {
                console.warn(chalk_1.default.yellow(`Thought ${shortId(thought.id)} ended ACTIVE. Setting FAILED.`));
                this.handleFailure(finalThoughtState, null, "Processing ended while ACTIVE.");
            }
        }
        return success;
    }
    async processSingleThought() {
        const thought = this.sampleThought();
        if (!thought || this.activeIds.has(thought.id))
            return false;
        this.activeIds.add(thought.id);
        return this._processThought(thought);
    }
    async processBatch() {
        const promises = [];
        while (this.activeIds.size < this.maxConcurrent) {
            const thought = this.sampleThought();
            if (!thought)
                break;
            this.activeIds.add(thought.id);
            promises.push(this._processThought(thought));
            if (promises.length >= this.batchSize)
                break;
        }
        if (promises.length === 0)
            return 0;
        const results = await Promise.all(promises);
        return results.filter(success => success).length;
    }
    async handlePromptResponse(promptId, responseText) {
        const promptThought = this.thoughts.getAll().find(t => t.type === Type.USER_PROMPT && t.metadata.uiContext?.promptId === promptId);
        if (!promptThought) {
            console.error(chalk_1.default.red(`Prompt thought for ID ${shortId(promptId)} not found.`));
            return false;
        }
        const waitingThought = this.thoughts.getAll().find(t => t.metadata.waitingFor === promptId && t.status === Status.WAITING);
        if (!waitingThought) {
            console.warn(chalk_1.default.yellow(`No thought found waiting for prompt ${shortId(promptId)}.`));
            promptThought.status = Status.DONE;
            this.thoughts.update(promptThought);
            return false;
        }
        this.addThought({
            id: generateId(), type: Type.INPUT, content: TermLogic.Atom(responseText),
            belief: new Belief(1, 0), status: Status.PENDING,
            metadata: {
                rootId: waitingThought.metadata.rootId ?? waitingThought.id, parentId: waitingThought.id,
                created: new Date().toISOString(), responseTo: promptId, tags: ['user_response'], provenance: 'user_input'
            }
        });
        promptThought.status = Status.DONE;
        this.thoughts.update(promptThought);
        waitingThought.status = Status.PENDING;
        delete waitingThought.metadata.waitingFor;
        waitingThought.belief.update(true);
        this.thoughts.update(waitingThought);
        console.log(chalk_1.default.blue(`Response for ${shortId(promptId)} received. ${shortId(waitingThought.id)} now PENDING.`));
        return true;
    }
}
async function saveState(thoughts, rules, memory) {
    try {
        await fs.mkdir(DATA_DIR, { recursive: true });
        const state = { thoughts: thoughts.toJSON(), rules: rules.toJSON() };
        await fs.writeFile(STATE_FILE, JSON.stringify(state, null, 2));
        await memory.save();
        console.log(chalk_1.default.gray(`State saved.`));
    }
    catch (error) {
        console.error(chalk_1.default.red('Error saving state:'), error);
    }
}
const debouncedSaveState = debounce(saveState, SAVE_DEBOUNCE);
async function loadState(thoughts, rules, memory) {
    try {
        await fs.mkdir(DATA_DIR, { recursive: true });
        await memory.initialize();
        if (fsSync.existsSync(STATE_FILE)) {
            const data = await fs.readFile(STATE_FILE, 'utf-8');
            const state = safeJsonParse(data, { thoughts: {}, rules: {} });
            thoughts.loadJSON(state.thoughts);
            rules.loadJSON(state.rules);
            console.log(chalk_1.default.blue(`Loaded ${thoughts.count()} thoughts, ${rules.count()} rules.`));
        }
        else {
            console.log(chalk_1.default.yellow(`State file ${STATE_FILE} not found.`));
        }
    }
    catch (error) {
        console.error(chalk_1.default.red('Error loading state:'), error);
        if (!memory.isReady)
            await memory.initialize();
    }
}
class ConsoleUI {
    thoughts;
    rules;
    systemControl;
    rl;
    refreshIntervalId = null;
    isPaused = false;
    currentRootView = null;
    constructor(thoughts, rules, systemControl) {
        this.thoughts = thoughts;
        this.rules = rules;
        this.systemControl = systemControl;
        this.rl = readline.createInterface({ input: process.stdin, output: process.stdout, prompt: chalk_1.default.blueBright('FlowMind> '), completer: this.completer.bind(this) });
        thoughts.addChangeListener(() => this.render());
        rules.addChangeListener(() => this.render());
    }
    start() {
        console.log(chalk_1.default.cyan('Starting FlowMind Console UI...'));
        process.stdout.write('\x1b]0;FlowMind Console\x07');
        this.rl.on('line', async (line) => {
            const trimmedLine = line.trim();
            if (trimmedLine) {
                const [command, ...args] = trimmedLine.split(' ');
                await this.systemControl(command.toLowerCase(), args);
            }
            if (!this.rl.closed)
                this.rl.prompt();
        });
        this.rl.on('SIGINT', () => {
            this.rl.question(chalk_1.default.yellow('Exit FlowMind? (y/N) '), (answer) => {
                if (answer.match(/^y(es)?$/i))
                    this.systemControl('quit');
                else
                    this.rl.prompt();
            });
        });
        this.rl.on('close', () => {
            console.log(chalk_1.default.cyan('\nFlowMind Console UI stopped.'));
            if (this.refreshIntervalId)
                clearInterval(this.refreshIntervalId);
        });
        this.render();
        this.refreshIntervalId = setInterval(() => this.render(), UI_REFRESH_INTERVAL);
        this.rl.prompt();
    }
    stop() {
        if (this.refreshIntervalId) {
            clearInterval(this.refreshIntervalId);
            this.refreshIntervalId = null;
        }
        if (!this.rl.closed)
            this.rl.close();
    }
    setPaused(paused) { this.isPaused = paused; this.render(); }
    getStatusIndicator(status) {
        const map = { [Status.PENDING]: '⏳', [Status.ACTIVE]: '▶️', [Status.WAITING]: '⏸️', [Status.DONE]: '✅', [Status.FAILED]: '❌' };
        return chalk_1.default[status === Status.PENDING ? 'yellow' : status === Status.ACTIVE ? 'blueBright' : status === Status.WAITING ? 'magenta' : status === Status.DONE ? 'green' : 'red'](map[status] || '?');
    }
    formatThoughtLine(thought, indent = 0) {
        const prefix = ' '.repeat(indent);
        const statusIcon = this.getStatusIndicator(thought.status);
        const idStr = chalk_1.default.gray(shortId(thought.id));
        const typeStr = chalk_1.default.dim(thought.type.padEnd(8));
        const beliefStr = chalk_1.default.dim(`B:${thought.belief.score().toFixed(2)}`);
        const contentStr = TermLogic.format(thought.content);
        const errorStr = thought.metadata.error ? chalk_1.default.red(` ERR!`) : '';
        const waitingStr = thought.status === Status.WAITING && thought.metadata.waitingFor ? chalk_1.default.magenta(` W:${shortId(thought.metadata.waitingFor)}`) : '';
        const tagsStr = thought.metadata.tags?.length ? chalk_1.default.cyan(` #${thought.metadata.tags.join('#')}`) : '';
        return `${prefix}${statusIcon} ${idStr} ${typeStr} ${beliefStr}${waitingStr}${errorStr} ${contentStr}${tagsStr}`;
    }
    renderHeader(termWidth) {
        const title = ` FlowMind Console ${this.isPaused ? chalk_1.default.yellow('[PAUSED]') : chalk_1.default.green('[RUNNING]')}`;
        const viewMode = this.currentRootView ? chalk_1.default.cyan(` [View: ${shortId(this.currentRootView)}]`) : '';
        let header = chalk_1.default.bold.inverse("=".repeat(termWidth)) + "\n";
        header += chalk_1.default.bold.inverse(title + viewMode + " ".repeat(termWidth - title.length - viewMode.length)) + "\n";
        header += chalk_1.default.bold.inverse("=".repeat(termWidth)) + "\n";
        return header;
    }
    renderThoughts(allThoughts, termWidth) {
        let output = "";
        const thoughtsToDisplay = this.currentRootView ? this.thoughts.getAllByRootId(this.currentRootView) : allThoughts.filter(t => !t.metadata.parentId);
        const rootThoughts = thoughtsToDisplay.filter(t => t.id === this.currentRootView || !t.metadata.parentId);
        const thoughtMap = new Map(allThoughts.map(t => [t.id, t]));
        const childrenMap = new Map();
        allThoughts.forEach(t => {
            if (t.metadata.parentId) {
                const list = childrenMap.get(t.metadata.parentId) || [];
                list.push(t);
                childrenMap.set(t.metadata.parentId, list);
            }
        });
        const renderChildren = (parentId, indent) => {
            (childrenMap.get(parentId) || []).sort((a, b) => Date.parse(a.metadata.created ?? '0') - Date.parse(b.metadata.created ?? '0')).forEach(child => {
                if (this.currentRootView || !child.metadata.parentId) {
                    output += this.formatThoughtLine(child, indent) + "\n";
                    if (child.metadata.error)
                        output += ' '.repeat(indent + 2) + chalk_1.default.red(`└─ Error: ${child.metadata.error}`) + "\n";
                    renderChildren(child.id, indent + 2);
                }
            });
        };
        if (rootThoughts.length > 0) {
            output += chalk_1.default.bold("--- Thoughts ---\n");
            rootThoughts.sort((a, b) => Date.parse(a.metadata.created ?? '0') - Date.parse(b.metadata.created ?? '0')).forEach(root => {
                output += this.formatThoughtLine(root, 0) + "\n";
                if (root.metadata.error)
                    output += ' '.repeat(2) + chalk_1.default.red(`└─ Error: ${root.metadata.error}`) + "\n";
                renderChildren(root.id, 2);
                output += chalk_1.default.grey("-".repeat(termWidth / 2)) + "\n";
            });
        }
        else {
            output += chalk_1.default.grey(this.currentRootView ? `Thought ${shortId(this.currentRootView)} not found/no children.` : "No top-level thoughts. Use 'add <note>'.\n");
        }
        return output;
    }
    renderPrompts(allThoughts) {
        let output = "";
        const pendingPrompts = allThoughts.filter(t => t.type === Type.USER_PROMPT && t.status === Status.PENDING && t.metadata.uiContext?.promptId);
        if (pendingPrompts.length > 0) {
            output += chalk_1.default.bold.yellow("\n--- Pending Prompts ---\n");
            pendingPrompts.forEach(p => {
                const promptId = p.metadata.uiContext?.promptId ?? '?id?';
                output += `${chalk_1.default.yellow('❓')} [${chalk_1.default.bold(shortId(promptId))}] ${TermLogic.format(p.content)} ${chalk_1.default.grey(`(for ${shortId(p.metadata.parentId)})`)}\n`;
            });
        }
        return output;
    }
    renderFooter(termWidth) {
        let output = chalk_1.default.inverse("=".repeat(termWidth)) + "\n";
        output += chalk_1.default.dim("Cmds: add, respond, run, pause, step, rules, tools, info, view, clear, save, quit, tag, untag, search, delete, help") + "\n";
        return output;
    }
    render() {
        if (this.rl.closed)
            return;
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
    completer(line) {
        const commands = ['add', 'respond', 'run', 'pause', 'step', 'rules', 'tools', 'info', 'view', 'clear', 'save', 'quit', 'tag', 'untag', 'search', 'delete', 'help'];
        const thoughts = this.thoughts.getAll().map(t => shortId(t.id));
        const rules = this.rules.getAll().map(r => shortId(r.id));
        const prompts = this.thoughts.getAll()
            .filter(t => t.type === Type.USER_PROMPT && t.status === Status.PENDING && t.metadata.uiContext?.promptId)
            .map(t => shortId(t.metadata.uiContext.promptId));
        const parts = line.trimStart().split(' ');
        const currentPart = parts[parts.length - 1];
        const command = parts[0].toLowerCase();
        let hits = [];
        if (parts.length <= 1)
            hits = commands.filter((c) => c.startsWith(currentPart));
        else {
            switch (command) {
                case 'info':
                case 'view':
                case 'delete':
                case 'tag':
                case 'untag':
                    if (parts.length === 2)
                        hits = thoughts.filter((id) => id.startsWith(currentPart));
                    break;
                case 'respond':
                    if (parts.length === 2)
                        hits = prompts.filter((id) => id.startsWith(currentPart));
                    break;
            }
        }
        return [hits, currentPart];
    }
    switchView(rootIdPrefix) {
        if (!rootIdPrefix || ['all', 'root'].includes(rootIdPrefix.toLowerCase())) {
            this.currentRootView = null;
            console.log(chalk_1.default.blue("View: All root thoughts."));
        }
        else {
            const targetThought = this.thoughts.findThought(rootIdPrefix);
            if (targetThought) {
                let root = targetThought;
                let safety = 0;
                while (root.metadata.parentId && safety++ < 20) {
                    const parent = this.thoughts.get(root.metadata.parentId);
                    if (!parent)
                        break;
                    root = parent;
                }
                this.currentRootView = root.id;
                console.log(chalk_1.default.blue(`View: Root ${shortId(this.currentRootView)}.`));
            }
            else {
                console.log(chalk_1.default.red(`Cannot find thought prefix "${rootIdPrefix}".`));
            }
        }
        this.render();
    }
}
class FlowMindSystem {
    thoughts = new ThoughtStore();
    rules = new RuleStore();
    llm = new LLMService();
    memory = new MemoryStore(this.llm.embeddings, VECTOR_STORE_DIR);
    tools = new ToolRegistry();
    engine = new Engine(this.thoughts, this.rules, this.memory, this.llm, this.tools);
    ui = new ConsoleUI(this.thoughts, this.rules, this.handleCommand.bind(this));
    isRunning = false;
    workerIntervalId = null;
    constructor() { this.registerCoreTools(); }
    registerCoreTools() { [new LLMTool(), new MemoryTool(), new UserInteractionTool(), new GoalProposalTool(), new CoreTool()].forEach(t => this.tools.register(t)); }
    bootstrapRules() {
        if (this.rules.count() > 0)
            return;
        console.log(chalk_1.default.blue("Bootstrapping default rules..."));
        const defaultRules = [
            { pattern: TermLogic.Structure("INPUT", [TermLogic.Variable("C")]), action: TermLogic.Structure("LLMTool", [TermLogic.Atom("generate"), TermLogic.Atom("Input: \"?C\". Define GOAL. Output only goal.")]) },
            { pattern: TermLogic.Structure("GOAL", [TermLogic.Variable("C")]), action: TermLogic.Structure("LLMTool", [TermLogic.Atom("generate"), TermLogic.Atom("Goal: \"?C\". Outline 1-3 STRATEGY steps. Output steps, one per line.")]) },
            { pattern: TermLogic.Structure("STRATEGY", [TermLogic.Variable("C")]), action: TermLogic.Structure("LLMTool", [TermLogic.Atom("generate"), TermLogic.Atom("Strategy \"?C\" performed. Summarize likely OUTCOME. Output outcome.")]) },
            { pattern: TermLogic.Structure("OUTCOME", [TermLogic.Variable("C")]), action: TermLogic.Structure("MemoryTool", [TermLogic.Atom("add"), TermLogic.Variable("C")]) },
            { pattern: TermLogic.Structure("OUTCOME", [TermLogic.Variable("C")]), action: TermLogic.Structure("GoalProposalTool", [TermLogic.Atom("suggest"), TermLogic.Variable("C")]) },
        ];
        defaultRules.forEach((r, i) => {
            const type = r.pattern.kind === 'Structure' ? r.pattern.name : 'Generic';
            this.rules.add({ id: generateId(), ...r, belief: Belief.DEFAULT, metadata: { description: `Default ${i + 1}: On ${type}`, provenance: 'bootstrap', created: new Date().toISOString() } });
        });
        console.log(chalk_1.default.green(`${this.rules.count()} default rules added.`));
    }
    async initialize() {
        console.log(chalk_1.default.cyan('Initializing FlowMind System...'));
        await loadState(this.thoughts, this.rules, this.memory);
        this.bootstrapRules();
        this.ui.start();
        this.ui.setPaused(!this.isRunning);
        if (this.isRunning)
            this.startProcessingLoop();
        console.log(chalk_1.default.green('FlowMind Initialized.') + chalk_1.default.yellow(" Paused. Use 'run' to start."));
    }
    startProcessingLoop() {
        if (this.workerIntervalId)
            return;
        console.log(chalk_1.default.green('Starting processing loop...'));
        this.isRunning = true;
        this.ui.setPaused(false);
        this.workerIntervalId = setInterval(async () => {
            if (!this.isRunning)
                return;
            try {
                if (await this.engine.processBatch() > 0) {
                    debouncedSaveState(this.thoughts, this.rules, this.memory);
                }
            }
            catch (error) {
                console.error(chalk_1.default.red('Error in processing loop:'), error);
            }
        }, WORKER_INTERVAL);
    }
    pauseProcessingLoop() {
        if (!this.workerIntervalId)
            return;
        console.log(chalk_1.default.yellow('Pausing processing loop...'));
        this.isRunning = false;
        this.ui.setPaused(true);
        clearInterval(this.workerIntervalId);
        this.workerIntervalId = null;
        debouncedSaveState.cancel();
        saveState(this.thoughts, this.rules, this.memory);
    }
    async handleCommand(command, args = []) {
        try {
            switch (command) {
                case 'add': {
                    const contentText = args.join(' ');
                    if (!contentText) {
                        console.log(chalk_1.default.yellow("Usage: add <note text>"));
                        break;
                    }
                    const newThought = {
                        id: generateId(), type: Type.INPUT, content: TermLogic.Atom(contentText), belief: new Belief(1, 0), status: Status.PENDING,
                        metadata: { created: new Date().toISOString(), tags: ['user_added'], provenance: 'user_command' }
                    };
                    newThought.metadata.rootId = newThought.id;
                    this.engine.addThought(newThought);
                    console.log(chalk_1.default.green(`Added: ${shortId(newThought.id)}`));
                    debouncedSaveState(this.thoughts, this.rules, this.memory);
                    break;
                }
                case 'respond': {
                    const promptIdPrefix = args[0];
                    const responseText = args.slice(1).join(' ');
                    if (!promptIdPrefix || !responseText) {
                        console.log(chalk_1.default.yellow("Usage: respond <prompt_id_prefix> <response text>"));
                        break;
                    }
                    const fullPromptId = this.thoughts.getAll().map(t => t.metadata?.uiContext?.promptId).find(pid => pid && pid.startsWith(promptIdPrefix));
                    if (!fullPromptId) {
                        console.log(chalk_1.default.red(`Prompt prefix "${promptIdPrefix}" not found.`));
                        break;
                    }
                    await this.engine.handlePromptResponse(fullPromptId, responseText);
                    debouncedSaveState(this.thoughts, this.rules, this.memory);
                    break;
                }
                case 'run':
                    if (!this.isRunning)
                        this.startProcessingLoop();
                    else
                        console.log(chalk_1.default.yellow("Already running."));
                    break;
                case 'pause':
                    if (this.isRunning)
                        this.pauseProcessingLoop();
                    else
                        console.log(chalk_1.default.yellow("Already paused."));
                    break;
                case 'step':
                    if (!this.isRunning) {
                        console.log(chalk_1.default.blue("Stepping..."));
                        const processed = await this.engine.processSingleThought();
                        console.log(processed ? chalk_1.default.green("Step done.") : chalk_1.default.yellow("Nothing to step."));
                        if (processed)
                            debouncedSaveState(this.thoughts, this.rules, this.memory);
                    }
                    else
                        console.log(chalk_1.default.yellow("Pause system first ('pause')."));
                    break;
                case 'save':
                    debouncedSaveState.cancel();
                    await saveState(this.thoughts, this.rules, this.memory);
                    console.log(chalk_1.default.blue("State saved."));
                    break;
                case 'quit':
                case 'exit':
                    await this.shutdown();
                    break;
                case 'clear':
                    console.clear();
                    this.ui['render']();
                    break;
                case 'help':
                    this.printHelp();
                    break;
                case 'status':
                    this.printStatus();
                    break;
                case 'info': {
                    const idPrefix = args[0];
                    if (!idPrefix) {
                        console.log(chalk_1.default.yellow("Usage: info <id_prefix>"));
                        break;
                    }
                    const thought = this.thoughts.findThought(idPrefix);
                    const rule = this.rules.findRule(idPrefix);
                    if (thought)
                        this.printThoughtInfo(thought);
                    else if (rule)
                        this.printRuleInfo(rule);
                    else
                        console.log(chalk_1.default.red(`ID prefix "${idPrefix}" not found.`));
                    break;
                }
                case 'view':
                    this.ui.switchView(args[0] || null);
                    break;
                case 'rules':
                    this.printRules();
                    break;
                case 'tools':
                    this.printTools();
                    break;
                case 'tag':
                case 'untag': {
                    const idPrefix = args[0];
                    const tag = args[1];
                    const isTagging = command === 'tag';
                    if (!idPrefix || !tag) {
                        console.log(chalk_1.default.yellow(`Usage: ${command} <thought_id_prefix> <tag_name>`));
                        break;
                    }
                    const thought = this.thoughts.findThought(idPrefix);
                    if (!thought) {
                        console.log(chalk_1.default.red(`Thought "${idPrefix}" not found.`));
                        break;
                    }
                    thought.metadata.tags = thought.metadata.tags || [];
                    const hasTag = thought.metadata.tags.includes(tag);
                    if (isTagging && !hasTag) {
                        thought.metadata.tags.push(tag);
                        console.log(chalk_1.default.green(`Tagged ${shortId(thought.id)} with #${tag}.`));
                    }
                    else if (!isTagging && hasTag) {
                        thought.metadata.tags = thought.metadata.tags.filter(t => t !== tag);
                        console.log(chalk_1.default.green(`Untagged ${shortId(thought.id)} from #${tag}.`));
                    }
                    else {
                        console.log(chalk_1.default.yellow(`Thought ${shortId(thought.id)} ${isTagging ? 'already has' : 'does not have'} tag #${tag}.`));
                        break;
                    }
                    this.thoughts.update(thought);
                    debouncedSaveState(this.thoughts, this.rules, this.memory);
                    break;
                }
                case 'search': {
                    const query = args.join(' ');
                    if (!query) {
                        console.log(chalk_1.default.yellow("Usage: search <query>"));
                        break;
                    }
                    console.log(chalk_1.default.blue(`Searching memory: "${query}"...`));
                    const results = await this.memory.search(query, 5);
                    if (results.length > 0) {
                        console.log(chalk_1.default.bold(`--- Search Results (${results.length}) ---`));
                        results.forEach(r => console.log(`[${chalk_1.default.gray(shortId(r.metadata.sourceId))}|${chalk_1.default.dim(r.metadata.type)}] ${r.content.substring(0, 80)}${r.content.length > 80 ? '...' : ''}`));
                    }
                    else
                        console.log(chalk_1.default.yellow("No relevant memories found."));
                    break;
                }
                case 'delete': {
                    const idPrefix = args[0];
                    if (!idPrefix) {
                        console.log(chalk_1.default.yellow("Usage: delete <thought_id_prefix>"));
                        break;
                    }
                    const thought = this.thoughts.findThought(idPrefix);
                    if (!thought) {
                        console.log(chalk_1.default.red(`Thought "${idPrefix}" not found.`));
                        break;
                    }
                    this.ui['rl'].question(chalk_1.default.yellow(`Delete thought ${shortId(thought.id)}? (y/N) `), (answer) => {
                        if (answer.match(/^y(es)?$/i)) {
                            if (this.thoughts.delete(thought.id)) {
                                console.log(chalk_1.default.green(`Thought ${shortId(thought.id)} deleted.`));
                                debouncedSaveState(this.thoughts, this.rules, this.memory);
                            }
                            else
                                console.log(chalk_1.default.red(`Failed delete ${shortId(thought.id)}.`));
                        }
                        else
                            console.log(chalk_1.default.yellow("Deletion cancelled."));
                        this.ui['rl'].prompt();
                    });
                    return;
                }
                default: console.log(chalk_1.default.red(`Unknown command: ${command}. Try 'help'.`));
            }
        }
        catch (error) {
            console.error(chalk_1.default.red(`Error executing command "${command}":`), error);
        }
        if (!['delete', 'quit', 'exit'].includes(command))
            this.ui['rl']?.prompt();
    }
    printHelp() {
        console.log(chalk_1.default.bold("\n--- FlowMind Help ---"));
        const cmds = [
            ['add <text>', 'Create new INPUT thought'], ['respond <id> <text>', 'Respond to user prompt'],
            ['run', 'Start/resume processing'], ['pause', 'Pause processing'], ['step', 'Process one thought (when paused)'],
            ['view [id|all]', 'Focus view on thought tree/all'], ['info <id>', 'Details for thought/rule'],
            ['rules', 'List all rules'], ['tools', 'List all tools'], ['tag <id> <tag>', 'Add tag to thought'],
            ['untag <id> <tag>', 'Remove tag from thought'], ['search <query>', 'Search vector memory'],
            ['delete <id>', 'Delete thought (confirm!)'], ['save', 'Save state now'], ['clear', 'Clear screen'],
            ['help', 'Show this help'], ['quit | exit', 'Save state and exit'],
        ];
        cmds.forEach(([cmd, desc]) => console.log(` ${chalk_1.default.cyan(cmd.padEnd(20))} : ${desc}`));
        console.log(chalk_1.default.grey(" (IDs can usually be prefixes)"));
    }
    printStatus() {
        const counts = Object.values(Status).reduce((acc, s) => ({ ...acc, [s]: 0 }), {});
        this.thoughts.getAll().forEach(t => counts[t.status]++);
        console.log(chalk_1.default.bold("\n--- System Status ---"));
        console.log(` Processing: ${this.isRunning ? chalk_1.default.green('RUNNING') : chalk_1.default.yellow('PAUSED')}`);
        console.log(` Thoughts  : ${chalk_1.default.cyan(this.thoughts.count())} total`);
        Object.entries(counts).filter(([, c]) => c > 0).forEach(([s, c]) => console.log(`   - ${s.padEnd(7)}: ${chalk_1.default.yellow(c)}`));
        console.log(` Rules     : ${chalk_1.default.cyan(this.rules.count())}`);
        console.log(` View Mode : ${this.ui['currentRootView'] ? `Focused on ${shortId(this.ui['currentRootView'])}` : 'Root View'}`);
    }
    printThoughtInfo(thought) {
        console.log(chalk_1.default.bold.underline(`\n--- Thought Info: ${shortId(thought.id)} ---`));
        console.log(` Full ID : ${chalk_1.default.gray(thought.id)}`);
        console.log(` Type    : ${chalk_1.default.cyan(thought.type)}`);
        console.log(` Status  : ${this.ui['getStatusIndicator'](thought.status)} ${thought.status}`);
        console.log(` Belief  : ${chalk_1.default.yellow(thought.belief.score().toFixed(3))} (P:${thought.belief.pos}, N:${thought.belief.neg})`);
        console.log(` Content : ${TermLogic.format(thought.content)}`); // Short format for term
        console.log(chalk_1.default.bold(" Metadata:"));
        Object.entries(thought.metadata).forEach(([key, value]) => {
            if (value !== undefined && value !== null && (!Array.isArray(value) || value.length > 0)) {
                let displayValue = typeof value === 'object' ? JSON.stringify(value) : String(value);
                if (['parentId', 'rootId', 'waitingFor', 'responseTo', 'ruleId'].includes(key))
                    displayValue = shortId(displayValue);
                console.log(`  ${key.padEnd(12)}: ${chalk_1.default.dim(displayValue)}`);
            }
        });
    }
    printRuleInfo(rule) {
        console.log(chalk_1.default.bold.underline(`\n--- Rule Info: ${shortId(rule.id)} ---`));
        console.log(` Full ID     : ${chalk_1.default.gray(rule.id)}`);
        console.log(` Description : ${chalk_1.default.cyan(rule.metadata.description || 'N/A')}`);
        console.log(` Belief      : ${chalk_1.default.yellow(rule.belief.score().toFixed(3))} (P:${rule.belief.pos}, N:${rule.belief.neg})`);
        console.log(` Pattern     : ${TermLogic.format(rule.pattern)}`);
        console.log(` Action      : ${TermLogic.format(rule.action)}`);
        if (Object.keys(rule.metadata).filter(k => k !== 'description').length > 0)
            console.log(chalk_1.default.bold(" Metadata:"));
        Object.entries(rule.metadata).forEach(([key, value]) => { if (key !== 'description')
            console.log(`  ${key.padEnd(12)}: ${chalk_1.default.dim(String(value))}`); });
    }
    printRules() {
        const allRules = this.rules.getAll();
        console.log(chalk_1.default.bold(`\n--- Rules (${allRules.length}) ---`));
        if (allRules.length === 0) {
            console.log(chalk_1.default.grey("No rules loaded."));
            return;
        }
        allRules.sort((a, b) => a.metadata.description?.localeCompare(b.metadata.description ?? '') ?? 0).forEach(rule => {
            const idStr = chalk_1.default.gray(shortId(rule.id));
            const beliefStr = chalk_1.default.yellow(rule.belief.score().toFixed(2));
            console.log(` ${idStr} [B:${beliefStr}] ${chalk_1.default.cyan(rule.metadata.description || `Rule ${idStr}`)}`);
            console.log(`   P: ${TermLogic.format(rule.pattern)}`);
            console.log(`   A: ${TermLogic.format(rule.action)}`);
        });
    }
    printTools() {
        const allTools = this.tools.list();
        console.log(chalk_1.default.bold(`\n--- Tools (${allTools.length}) ---`));
        if (allTools.length === 0) {
            console.log(chalk_1.default.grey("No tools registered."));
            return;
        }
        allTools.sort((a, b) => a.name.localeCompare(b.name)).forEach(tool => console.log(` ${chalk_1.default.cyan.bold(tool.name)}: ${tool.description}`));
    }
    async shutdown() {
        console.log(chalk_1.default.cyan('\nShutting down FlowMind System...'));
        this.pauseProcessingLoop();
        this.ui.stop();
        debouncedSaveState.cancel();
        await saveState(this.thoughts, this.rules, this.memory);
        console.log(chalk_1.default.green('FlowMind shutdown complete. Goodbye!'));
        process.exit(0);
    }
}
async function main() {
    const system = new FlowMindSystem();
    const handleShutdown = async (signal) => { console.log(chalk_1.default.yellow(`\n${signal} received. Shutting down...`)); await system.shutdown(); };
    process.on('SIGINT', () => handleShutdown('SIGINT'));
    process.on('SIGTERM', () => handleShutdown('SIGTERM'));
    process.on('uncaughtException', async (error) => {
        console.error(chalk_1.default.red.bold('\n--- UNCAUGHT EXCEPTION ---\n'), error, chalk_1.default.red.bold('\n--------------------------'));
        try {
            await system.shutdown();
        }
        catch {
            process.exit(1);
        }
    });
    process.on('unhandledRejection', async (reason) => {
        console.error(chalk_1.default.red.bold('\n--- UNHANDLED REJECTION ---\nReason:'), reason, chalk_1.default.red.bold('\n---------------------------'));
        try {
            await system.shutdown();
        }
        catch {
            process.exit(1);
        }
    });
    try {
        await system.initialize();
    }
    catch (error) {
        console.error(chalk_1.default.red.bold("Critical init error:"), error);
        process.exit(1);
    }
}
main();
