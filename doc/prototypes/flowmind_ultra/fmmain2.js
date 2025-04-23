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
Object.defineProperty(exports, "__esModule", { value: true });
const fs = __importStar(require("fs/promises"));
const fsSync = __importStar(require("fs"));
const path = __importStar(require("path"));
const os = __importStar(require("os"));
const readline = __importStar(require("readline"));
const uuid_1 = require("uuid");
const ollama_1 = require("@langchain/community/chat_models/ollama");
const ollama_2 = require("@langchain/community/embeddings/ollama");
const messages_1 = require("@langchain/core/messages");
const output_parsers_1 = require("@langchain/core/output_parsers");
const documents_1 = require("@langchain/core/documents");
const faiss_1 = require("@langchain/community/vectorstores/faiss");
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
    Type["SYSTEM_EVENT"] = "SYSTEM_EVENT";
})(Type || (Type = {}));
class Belief {
    positive_evidence;
    negative_evidence;
    constructor(pos = DEFAULT_BELIEF_POS, neg = DEFAULT_BELIEF_NEG) {
        this.positive_evidence = pos;
        this.negative_evidence = neg;
    }
    score() { return (this.positive_evidence + 1) / (this.positive_evidence + this.negative_evidence + 2); }
    update(success) { success ? this.positive_evidence++ : this.negative_evidence++; }
    toJSON() { return { positive_evidence: this.positive_evidence, negative_evidence: this.negative_evidence }; }
    static fromJSON(data) { return new Belief(data.positive_evidence, data.negative_evidence); }
    static DEFAULT = new Belief();
}
var TermLogic;
(function (TermLogic) {
    TermLogic.Atom = (name) => ({ kind: 'Atom', name });
    TermLogic.Variable = (name) => ({ kind: 'Variable', name });
    TermLogic.Structure = (name, args) => ({ kind: 'Structure', name, args });
    TermLogic.ListTerm = (elements) => ({ kind: 'ListTerm', elements });
    function formatTerm(term) {
        if (!term)
            return 'null';
        switch (term.kind) {
            case 'Atom': return term.name;
            case 'Variable': return `?${term.name}`;
            case 'Structure': return `${term.name}(${term.args.map(formatTerm).join(', ')})`;
            case 'ListTerm': return `[${term.elements.map(formatTerm).join(', ')}]`;
            default: return 'invalid_term';
        }
    }
    TermLogic.formatTerm = formatTerm;
    function termToString(term) {
        switch (term.kind) {
            case 'Atom': return term.name;
            case 'Variable': return `?${term.name}`;
            case 'Structure': return `${term.name}: ${term.args.map(termToString).join('; ')}`;
            case 'ListTerm': return term.elements.map(termToString).join(', ');
            default: return '';
        }
    }
    TermLogic.termToString = termToString;
    function stringToTerm(input) {
        if (input.includes(':') && input.includes(';')) {
            const [name, argsStr] = input.split(':', 2);
            const args = argsStr.split(';').map(s => TermLogic.Atom(s.trim()));
            return TermLogic.Structure(name.trim(), args);
        }
        if (input.startsWith('[') && input.endsWith(']')) {
            const elements = input.slice(1, -1).split(',').map(s => TermLogic.Atom(s.trim()));
            return TermLogic.ListTerm(elements);
        }
        return TermLogic.Atom(input);
    }
    TermLogic.stringToTerm = stringToTerm;
    function unify(term1, term2, bindings = new Map()) {
        const resolve = (term, currentBindings) => {
            if (term.kind === 'Variable' && currentBindings.has(term.name))
                return resolve(currentBindings.get(term.name), currentBindings);
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
        if (term.kind === 'Variable' && bindings.has(term.name))
            return substitute(bindings.get(term.name), bindings);
        if (term.kind === 'Structure')
            return { ...term, args: term.args.map(arg => substitute(arg, bindings)) };
        if (term.kind === 'ListTerm')
            return { ...term, elements: term.elements.map(el => substitute(el, bindings)) };
        return term;
    }
    TermLogic.substitute = substitute;
})(TermLogic || (TermLogic = {}));
function generateId() { return (0, uuid_1.v4)(); }
function debounce(func, wait) {
    let timeout = null;
    return (...args) => {
        const later = () => { timeout = null; func(...args); };
        if (timeout)
            clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
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
class ThoughtStore {
    thoughts = new Map();
    add(thought) { this.thoughts.set(thought.id, thought); }
    get(id) { return this.thoughts.get(id); }
    update(thought) {
        if (this.thoughts.has(thought.id)) {
            this.thoughts.set(thought.id, { ...thought, metadata: { ...thought.metadata, timestamp_modified: new Date().toISOString() } });
        }
    }
    getPending() { return Array.from(this.thoughts.values()).filter(t => t.status === Status.PENDING); }
    getAll() { return Array.from(this.thoughts.values()); }
    getAllByRootId(rootId) {
        return this.getAll()
            .filter(t => t.metadata.root_id === rootId || t.id === rootId)
            .sort((a, b) => Date.parse(a.metadata.timestamp_created ?? '0') - Date.parse(b.metadata.timestamp_created ?? '0'));
    }
    toJSON() {
        const obj = {};
        this.thoughts.forEach((thought, id) => obj[id] = { ...thought, belief: thought.belief.toJSON() });
        return obj;
    }
    static fromJSON(data) {
        const store = new ThoughtStore();
        for (const id in data) {
            store.add({ ...data[id], belief: Belief.fromJSON(data[id].belief) });
        }
        return store;
    }
}
class RuleStore {
    rules = new Map();
    add(rule) { this.rules.set(rule.id, rule); }
    get(id) { return this.rules.get(id); }
    update(rule) { if (this.rules.has(rule.id))
        this.rules.set(rule.id, rule); }
    getAll() { return Array.from(this.rules.values()); }
    toJSON() {
        const obj = {};
        this.rules.forEach((rule, id) => obj[id] = { ...rule, belief: rule.belief.toJSON() });
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
    vectorStore = null;
    embeddings;
    vectorStorePath;
    isReady = false;
    constructor(embeddingsService, storePath) {
        this.embeddings = embeddingsService;
        this.vectorStorePath = storePath;
    }
    async initialize() {
        try {
            await fs.access(this.vectorStorePath);
            this.vectorStore = await faiss_1.FaissStore.load(this.vectorStorePath, this.embeddings);
            this.isReady = true;
        }
        catch (error) {
            if (error.code === 'ENOENT') {
                const dummyDoc = new documents_1.Document({ pageContent: "FlowMind init", metadata: { source_id: "init", type: "system", timestamp: new Date().toISOString() } });
                this.vectorStore = await faiss_1.FaissStore.fromDocuments([dummyDoc], this.embeddings);
                await this.vectorStore.save(this.vectorStorePath);
                this.isReady = true;
            }
        }
    }
    async add(entry) {
        if (!this.vectorStore || !this.isReady)
            return;
        const doc = new documents_1.Document({ pageContent: entry.content, metadata: { ...entry.metadata, id: entry.id } });
        await this.vectorStore.addDocuments([doc]);
        await this.save();
    }
    async search(query, k = 5) {
        if (!this.vectorStore || !this.isReady)
            return [];
        const results = await this.vectorStore.similaritySearchWithScore(query, k);
        return results.map(([doc]) => ({
            id: doc.metadata.id || generateId(),
            embedding: [],
            content: doc.pageContent,
            metadata: doc.metadata
        }));
    }
    async save() {
        if (this.vectorStore && this.isReady)
            await this.vectorStore.save(this.vectorStorePath);
    }
}
class LLMService {
    llm;
    embeddings;
    constructor() {
        this.llm = new ollama_1.ChatOllama({ baseUrl: OLLAMA_BASE_URL, model: 'llamablit', temperature: 0.7 });
        this.embeddings = new ollama_2.OllamaEmbeddings({ model: OLLAMA_EMBEDDING_MODEL, baseUrl: OLLAMA_BASE_URL });
    }
    async generate(prompt) {
        return this.llm.pipe(new output_parsers_1.StringOutputParser()).invoke([new messages_1.HumanMessage(prompt)]);
    }
    async embed(text) {
        return this.embeddings.embedQuery(text);
    }
}
class LLMTool {
    name = "LLMTool";
    description = "Interacts with the LLM for generation or embedding.";
    async execute(actionTerm, context, triggerThought) {
        const operation = actionTerm.args[0]?.kind === 'Atom' ? actionTerm.args[0].name : null;
        const promptTerm = actionTerm.args[1];
        if (!operation || !promptTerm)
            return TermLogic.Atom("error:missing_llm_params");
        const inputText = TermLogic.termToString(promptTerm);
        if (operation === 'generate') {
            return TermLogic.Atom(await context.llmService.generate(inputText));
        }
        else if (operation === 'embed') {
            return TermLogic.Atom("ok:embedding_requested");
        }
        return TermLogic.Atom("error:unsupported_llm_operation");
    }
}
class MemoryTool {
    name = "MemoryTool";
    description = "Adds to or searches the MemoryStore.";
    async execute(actionTerm, context, triggerThought) {
        const operation = actionTerm.args[0]?.kind === 'Atom' ? actionTerm.args[0].name : null;
        if (!operation)
            return TermLogic.Atom("error:missing_memory_operation");
        if (operation === 'add') {
            const contentTerm = actionTerm.args[1];
            if (!contentTerm)
                return TermLogic.Atom("error:missing_memory_add_content");
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
        }
        else if (operation === 'search') {
            const queryTerm = actionTerm.args[1];
            const k = actionTerm.args[2]?.kind === 'Atom' ? parseInt(actionTerm.args[2].name, 10) : 3;
            if (!queryTerm)
                return TermLogic.Atom("error:missing_memory_search_query");
            const queryStr = TermLogic.termToString(queryTerm);
            const results = await context.memoryStore.search(queryStr, isNaN(k) ? 3 : k);
            return TermLogic.ListTerm(results.map(r => TermLogic.Atom(r.content)));
        }
        return TermLogic.Atom("error:unsupported_memory_operation");
    }
}
class UserInteractionTool {
    name = "UserInteractionTool";
    description = "Requests input from the user via the console UI.";
    async execute(actionTerm, context, triggerThought) {
        const operation = actionTerm.args[0]?.kind === 'Atom' ? actionTerm.args[0].name : null;
        if (operation !== 'prompt')
            return TermLogic.Atom("error:unsupported_ui_operation");
        const promptTextTerm = actionTerm.args[1];
        if (!promptTextTerm)
            return TermLogic.Atom("error:missing_prompt_text");
        const promptText = TermLogic.termToString(promptTextTerm);
        const promptId = generateId();
        const promptThought = {
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
class GoalProposalTool {
    name = "GoalProposalTool";
    description = "Suggests new goals based on context or memory.";
    async execute(actionTerm, context, triggerThought) {
        const operation = actionTerm.args[0]?.kind === 'Atom' ? actionTerm.args[0].name : null;
        if (operation !== 'suggest')
            return TermLogic.Atom("error:unsupported_goal_operation");
        const contextTerm = actionTerm.args[1] ?? triggerThought.content;
        const contextStr = TermLogic.termToString(contextTerm);
        const memoryResults = await context.memoryStore.search(`Relevant past goals or outcomes related to: ${contextStr}`, 3);
        const memoryContext = memoryResults.map(r => r.content).join("\n - ");
        const prompt = `Based on the current context "${contextStr}" and potentially related past activities:\n - ${memoryContext}\n\nSuggest ONE concise, actionable next goal or task. Output only the suggested goal text.`;
        const suggestionText = await context.llmService.generate(prompt);
        if (suggestionText && suggestionText.trim()) {
            const suggestionThought = {
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
class CoreTool {
    name = "CoreTool";
    description = "Handles internal FlowMind operations like setting status or adding thoughts.";
    async execute(actionTerm, context, triggerThought) {
        const operation = actionTerm.args[0]?.kind === 'Atom' ? actionTerm.args[0].name : null;
        if (operation === 'set_status') {
            const targetThoughtIdTerm = actionTerm.args[1];
            const newStatusTerm = actionTerm.args[2];
            if (targetThoughtIdTerm?.kind !== 'Atom' || newStatusTerm?.kind !== 'Atom')
                return TermLogic.Atom("error:invalid_set_status_params");
            const targetId = targetThoughtIdTerm.name;
            const newStatus = newStatusTerm.name;
            if (!Object.values(Status).includes(newStatus))
                return TermLogic.Atom("error:invalid_status_value");
            const targetThought = context.thoughtStore.get(targetId);
            if (targetThought) {
                targetThought.status = newStatus;
                context.thoughtStore.update(targetThought);
                return TermLogic.Atom(`ok:status_set:${targetId}_to_${newStatus}`);
            }
            return TermLogic.Atom(`error:target_thought_not_found:${targetId}`);
        }
        else if (operation === 'add_thought') {
            const typeTerm = actionTerm.args[1];
            const contentTerm = actionTerm.args[2];
            const rootIdTerm = actionTerm.args[3];
            const parentIdTerm = actionTerm.args[4];
            if (typeTerm?.kind !== 'Atom' || !contentTerm)
                return TermLogic.Atom("error:invalid_add_thought_params");
            const type = typeTerm.name;
            if (!Object.values(Type).includes(type))
                return TermLogic.Atom("error:invalid_thought_type");
            const newThought = {
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
    tools = new Map();
    register(tool) { this.tools.set(tool.name, tool); }
    get(name) { return this.tools.get(name); }
    listTools() { return Array.from(this.tools.values()); }
}
class Engine {
    thoughtStore;
    ruleStore;
    memoryStore;
    llmService;
    toolRegistry;
    activeThoughtIds = new Set();
    constructor(thoughtStore, ruleStore, memoryStore, llmService, toolRegistry) {
        this.thoughtStore = thoughtStore;
        this.ruleStore = ruleStore;
        this.memoryStore = memoryStore;
        this.llmService = llmService;
        this.toolRegistry = toolRegistry;
    }
    addThought(thought) { this.thoughtStore.add(thought); }
    sampleThought() {
        const pending = this.thoughtStore.getPending().filter(t => !this.activeThoughtIds.has(t.id));
        if (pending.length === 0)
            return null;
        const weights = pending.map(t => t.metadata.priority ?? t.belief.score());
        const totalWeight = weights.reduce((sum, w) => sum + w, 0);
        if (totalWeight <= 0)
            return pending[Math.floor(Math.random() * pending.length)];
        let random = Math.random() * totalWeight;
        for (let i = 0; i < pending.length; i++) {
            random -= weights[i];
            if (random <= 0)
                return pending[i];
        }
        return pending[pending.length - 1];
    }
    findAndSelectRule(thought) {
        const matches = this.ruleStore.getAll()
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
        }
        catch (error) {
            this.handleFailure(thought, rule, `Tool execution exception: ${error.message}`);
            return false;
        }
    }
    async handleNoRuleMatch(thought) {
        let prompt = "";
        let targetType = null;
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
                const promptThought = {
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
                    type: targetType,
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
        }
        else if (thought.status === Status.PENDING) {
            this.handleFailure(thought, null, "No matching rule and no default action applicable.");
        }
    }
    handleFailure(thought, rule, errorInfo) {
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
    handleSuccess(thought, rule) {
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
    async processSingleThought() {
        const thought = this.sampleThought();
        if (!thought)
            return false;
        if (this.activeThoughtIds.has(thought.id))
            return false;
        this.activeThoughtIds.add(thought.id);
        thought.status = Status.ACTIVE;
        thought.metadata.agent_id = 'worker';
        this.thoughtStore.update(thought);
        let success = false;
        try {
            const match = this.findAndSelectRule(thought);
            if (match) {
                success = await this.executeAction(thought, match.rule, match.bindings);
            }
            else {
                await this.handleNoRuleMatch(thought);
                success = this.thoughtStore.get(thought.id)?.status === Status.DONE;
            }
        }
        catch (error) {
            this.handleFailure(thought, null, `Unhandled processing exception: ${error.message}`);
        }
        finally {
            this.activeThoughtIds.delete(thought.id);
            const finalThoughtState = this.thoughtStore.get(thought.id);
            if (finalThoughtState?.status === Status.ACTIVE) {
                this.handleFailure(thought, null, "Processing ended unexpectedly while ACTIVE.");
            }
        }
        return true;
    }
    async handlePromptResponse(promptId, responseText) {
        const waitingThought = this.thoughtStore.getAll().find(t => t.metadata.waiting_for_prompt_id === promptId && t.status === Status.WAITING);
        if (!waitingThought)
            return;
        const responseThought = {
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
async function saveState(thoughtStore, ruleStore, memoryStore) {
    await fs.mkdir(DATA_DIR, { recursive: true });
    const state = { thoughts: thoughtStore.toJSON(), rules: ruleStore.toJSON() };
    await fs.writeFile(STATE_FILE_PATH, JSON.stringify(state, null, 2));
    await memoryStore.save();
}
const debouncedSaveState = debounce(saveState, 5000);
async function loadState(thoughtStore, ruleStore, memoryStore) {
    if (!fsSync.existsSync(STATE_FILE_PATH)) {
        await memoryStore.initialize();
        return;
    }
    const data = await fs.readFile(STATE_FILE_PATH, 'utf-8');
    const state = safeJsonParse(data, { thoughts: {}, rules: {} });
    ThoughtStore.fromJSON(state.thoughts).getAll().forEach(t => thoughtStore.add(t));
    RuleStore.fromJSON(state.rules).getAll().forEach(r => ruleStore.add(r));
    await memoryStore.initialize();
}
class ConsoleUI {
    thoughtStore;
    systemControl;
    rl = null;
    refreshIntervalId = null;
    lastRenderedHeight = 0;
    constructor(thoughtStore, systemControl) {
        this.thoughtStore = thoughtStore;
        this.systemControl = systemControl;
    }
    start() {
        this.rl = readline.createInterface({ input: process.stdin, output: process.stdout, prompt: 'FlowMind> ' });
        this.rl.on('line', line => {
            const [command, ...args] = line.trim().split(' ');
            if (command)
                this.systemControl(command.toLowerCase(), args);
            else
                this.rl?.prompt();
        });
        this.rl.on('close', () => this.systemControl('quit'));
        this.refreshIntervalId = setInterval(() => this.render(), UI_REFRESH_INTERVAL_MS);
        this.render();
        this.rl.prompt();
    }
    stop() {
        if (this.refreshIntervalId)
            clearInterval(this.refreshIntervalId);
        this.rl?.close();
        process.stdout.write('\nConsole UI stopped.\n');
    }
    render() {
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
        const pendingPrompts = [];
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
                if (t.metadata.error_info)
                    output += `    Error: ${t.metadata.error_info.substring(0, termWidth - 12)}\n`;
                if (t.type === Type.USER_PROMPT && t.status === Status.PENDING && t.metadata.ui_context?.promptId)
                    pendingPrompts.push(t);
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
    thoughtStore = new ThoughtStore();
    ruleStore = new RuleStore();
    llmService = new LLMService();
    memoryStore = new MemoryStore(this.llmService.embeddings, VECTOR_STORE_PATH);
    toolRegistry = new ToolRegistry();
    engine = new Engine(this.thoughtStore, this.ruleStore, this.memoryStore, this.llmService, this.toolRegistry);
    consoleUI = new ConsoleUI(this.thoughtStore, this.handleCommand.bind(this));
    isRunning = true;
    workerIntervalId = null;
    constructor() {
        this.registerCoreTools();
    }
    registerCoreTools() {
        [new LLMTool(), new MemoryTool(), new UserInteractionTool(), new GoalProposalTool(), new CoreTool()]
            .forEach(tool => this.toolRegistry.register(tool));
    }
    bootstrapRules() {
        if (this.ruleStore.getAll().length > 0)
            return;
        const rules = [
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
    async initialize() {
        await fs.mkdir(DATA_DIR, { recursive: true });
        await loadState(this.thoughtStore, this.ruleStore, this.memoryStore);
        this.bootstrapRules();
        this.consoleUI.start();
        this.startProcessingLoop();
    }
    startProcessingLoop() {
        if (this.workerIntervalId)
            clearInterval(this.workerIntervalId);
        this.workerIntervalId = setInterval(async () => {
            if (this.isRunning) {
                if (await this.engine.processSingleThought())
                    debouncedSaveState(this.thoughtStore, this.ruleStore, this.memoryStore);
            }
        }, WORKER_INTERVAL_MS);
    }
    stopProcessingLoop() {
        if (this.workerIntervalId)
            clearInterval(this.workerIntervalId);
        this.workerIntervalId = null;
    }
    handleCommand(command, args = []) {
        switch (command) {
            case 'add': {
                const contentText = args.join(' ');
                if (!contentText) {
                    console.log("Usage: add <note text>");
                    return this.consoleUI['rl']?.prompt();
                }
                const newThought = {
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
                        if (processed)
                            debouncedSaveState(this.thoughtStore, this.ruleStore, this.memoryStore);
                        this.consoleUI['rl']?.prompt();
                    });
                }
                else {
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
    async shutdown() {
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
