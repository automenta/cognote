"use strict";
// flowmind-console.ts
// Single-file, console-based implementation of the FlowMind agentic note-taking system.
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
// --- Configuration ---
const DATA_DIR = path.join(os.homedir(), '.flowmind-console');
const STATE_FILE_PATH = path.join(DATA_DIR, 'flowmind_state.json');
const VECTOR_STORE_PATH = path.join(DATA_DIR, 'vector-store');
const OLLAMA_BASE_URL = process.env.OLLAMA_HOST || "http://localhost:11434";
const OLLAMA_MODEL = "llamablit"; // Or "llama3:latest", "llama3.1" etc.
const OLLAMA_EMBEDDING_MODEL = "llamablit"; // Ensure this matches Ollama install
const WORKER_INTERVAL_MS = 2000; // How often the agent cycle runs
const UI_REFRESH_INTERVAL_MS = 1000; // How often the console UI refreshes
const MAX_RETRIES = 2;
const DEFAULT_BELIEF_POS = 1.0;
const DEFAULT_BELIEF_NEG = 1.0;
// --- Utility Functions ---
const generateId = () => (0, uuid_1.v4)();
function debounce(func, wait) {
    let timeout = null;
    return (...args) => {
        const later = () => {
            timeout = null;
            func(...args);
        };
        if (timeout)
            clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
}
const safeJsonParse = (json, defaultValue) => {
    if (!json)
        return defaultValue;
    try {
        return JSON.parse(json);
    }
    catch (error) {
        console.error("JSON parse error:", error);
        return defaultValue;
    }
};
const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));
// --- Data Models ---
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
    Type["SYSTEM_EVENT"] = "SYSTEM_EVENT"; // Internal system messages
})(Type || (Type = {}));
class Belief {
    positive_evidence;
    negative_evidence;
    constructor(pos = DEFAULT_BELIEF_POS, neg = DEFAULT_BELIEF_NEG) {
        this.positive_evidence = pos;
        this.negative_evidence = neg;
    }
    score() {
        return (this.positive_evidence + 1) / (this.positive_evidence + this.negative_evidence + 2);
    }
    update(success) {
        success ? this.positive_evidence++ : this.negative_evidence++;
    }
    toJSON() {
        return { positive_evidence: this.positive_evidence, negative_evidence: this.negative_evidence };
    }
    static fromJSON(data) {
        return new Belief(data.positive_evidence, data.negative_evidence);
    }
    static DEFAULT = new Belief();
}
// --- Term Logic ---
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
        // Simplified string conversion for storage/LLM input
        switch (term.kind) {
            case 'Atom': return term.name;
            case 'Variable': return `?${term.name}`; // Usually shouldn't be stored directly
            case 'Structure': return `${term.name}: ${term.args.map(termToString).join('; ')}`;
            case 'ListTerm': return term.elements.map(termToString).join(', ');
            default: return '';
        }
    }
    TermLogic.termToString = termToString;
    function stringToTerm(input) {
        // Basic parsing attempt - assumes simple structure for now
        // TODO: Implement more robust parsing if needed
        if (input.includes(':') && input.includes(';')) { // Crude structure check
            const [name, argsStr] = input.split(':', 2);
            const args = argsStr.split(';').map(s => TermLogic.Atom(s.trim()));
            return TermLogic.Structure(name.trim(), args);
        }
        if (input.startsWith('[') && input.endsWith(']')) { // Crude list check
            const elements = input.slice(1, -1).split(',').map(s => TermLogic.Atom(s.trim()));
            return TermLogic.ListTerm(elements);
        }
        return TermLogic.Atom(input); // Default to Atom
    }
    TermLogic.stringToTerm = stringToTerm;
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
            case 'Atom':
                return t1.name === t2.name ? bindings : null;
            case 'Structure':
                const s2 = t2;
                if (t1.name !== s2.name || t1.args.length !== s2.args.length)
                    return null;
                let currentBindingsS = bindings;
                for (let i = 0; i < t1.args.length; i++) {
                    const result = unify(t1.args[i], s2.args[i], currentBindingsS);
                    if (!result)
                        return null;
                    currentBindingsS = result;
                }
                return currentBindingsS;
            case 'ListTerm':
                const l2 = t2;
                if (t1.elements.length !== l2.elements.length)
                    return null;
                let currentBindingsL = bindings;
                for (let i = 0; i < t1.elements.length; i++) {
                    const result = unify(t1.elements[i], l2.elements[i], currentBindingsL);
                    if (!result)
                        return null;
                    currentBindingsL = result;
                }
                return currentBindingsL;
        }
        return null; // Should be unreachable
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
        return term; // Atoms or unbound Variables
    }
    TermLogic.substitute = substitute;
})(TermLogic || (TermLogic = {}));
// --- Stores ---
class ThoughtStore {
    thoughts = new Map();
    add(thought) {
        this.thoughts.set(thought.id, thought);
    }
    get(id) {
        return this.thoughts.get(id);
    }
    update(thought) {
        if (!this.thoughts.has(thought.id))
            return; // Or throw?
        this.thoughts.set(thought.id, {
            ...thought,
            metadata: { ...thought.metadata, timestamp_modified: new Date().toISOString() }
        });
    }
    getPending() {
        return Array.from(this.thoughts.values()).filter(t => t.status === Status.PENDING);
    }
    getAll() {
        return Array.from(this.thoughts.values());
    }
    getAllByRootId(rootId) {
        return Array.from(this.thoughts.values())
            .filter(t => t.metadata.root_id === rootId || t.id === rootId)
            .sort((a, b) => Date.parse(a.metadata.timestamp_created ?? '0') - Date.parse(b.metadata.timestamp_created ?? '0'));
    }
    // Needed for persistence
    toJSON() {
        const obj = {};
        this.thoughts.forEach((thought, id) => {
            obj[id] = { ...thought, belief: thought.belief.toJSON() };
        });
        return obj;
    }
    // Needed for persistence
    static fromJSON(data) {
        const store = new ThoughtStore();
        for (const id in data) {
            const thoughtData = data[id];
            store.add({
                ...thoughtData,
                belief: Belief.fromJSON(thoughtData.belief)
            });
        }
        return store;
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
        if (!this.rules.has(rule.id))
            return;
        this.rules.set(rule.id, rule);
    }
    getAll() {
        return Array.from(this.rules.values());
    }
    toJSON() {
        const obj = {};
        this.rules.forEach((rule, id) => {
            obj[id] = { ...rule, belief: rule.belief.toJSON() };
        });
        return obj;
    }
    static fromJSON(data) {
        const store = new RuleStore();
        for (const id in data) {
            const ruleData = data[id];
            store.add({
                ...ruleData,
                belief: Belief.fromJSON(ruleData.belief)
            });
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
            console.log(`MemoryStore loaded from ${this.vectorStorePath}`);
            this.isReady = true;
        }
        catch (error) {
            if (error.code === 'ENOENT') {
                console.log(`MemoryStore directory not found at ${this.vectorStorePath}, creating new one.`);
                // Initialize with a dummy document
                try {
                    const dummyDoc = new documents_1.Document({ pageContent: "FlowMind init", metadata: { source_id: "init", type: "system", timestamp: new Date().toISOString() } });
                    this.vectorStore = await faiss_1.FaissStore.fromDocuments([dummyDoc], this.embeddings);
                    await this.vectorStore.save(this.vectorStorePath);
                    console.log(`New MemoryStore created and saved to ${this.vectorStorePath}`);
                    this.isReady = true;
                }
                catch (initError) {
                    console.error("Failed to create new vector store:", initError);
                    this.isReady = false;
                }
            }
            else {
                console.error("Failed to load MemoryStore:", error);
                this.isReady = false;
            }
        }
    }
    async add(entry) {
        if (!this.vectorStore || !this.isReady) {
            console.warn("MemoryStore not ready, cannot add entry.");
            return;
        }
        try {
            const embedding = await this.embeddings.embedQuery(entry.content);
            const doc = new documents_1.Document({
                pageContent: entry.content,
                metadata: { ...entry.metadata, id: entry.id } // Faiss uses metadata.id
            });
            // FAISS requires adding documents, no easy update/delete by ID
            await this.vectorStore.addDocuments([doc]);
            // Note: Saving frequently can be slow; consider debouncing or periodic saving
            await this.save();
        }
        catch (error) {
            console.error(`Error adding entry ${entry.id} to MemoryStore:`, error);
        }
    }
    async search(query, k = 5) {
        if (!this.vectorStore || !this.isReady) {
            console.warn("MemoryStore not ready for search.");
            return [];
        }
        try {
            const results = await this.vectorStore.similaritySearchWithScore(query, k);
            // Convert LangChain results back to MemoryEntry format (embedding is not returned directly)
            return results.map(([doc, _score]) => ({
                id: doc.metadata.id || generateId(), // Fallback id
                embedding: [], // Embedding not readily available post-search in FaissStore API easily
                content: doc.pageContent,
                metadata: doc.metadata
            }));
        }
        catch (error) {
            console.error("MemoryStore search error:", error);
            return [];
        }
    }
    async save() {
        if (this.vectorStore && this.isReady) {
            try {
                await this.vectorStore.save(this.vectorStorePath);
            }
            catch (error) {
                console.error("Error saving MemoryStore:", error);
            }
        }
    }
}
// --- LLM Service ---
class LLMService {
    llm;
    embeddings;
    constructor() {
        this.llm = new ollama_1.ChatOllama({
            baseUrl: OLLAMA_BASE_URL,
            model: OLLAMA_MODEL,
            temperature: 0.7,
        });
        this.embeddings = new ollama_2.OllamaEmbeddings({
            model: OLLAMA_EMBEDDING_MODEL,
            baseUrl: OLLAMA_BASE_URL,
        });
        console.log(`LLM Service configured: Model=${OLLAMA_MODEL}, Embeddings=${OLLAMA_EMBEDDING_MODEL}, URL=${OLLAMA_BASE_URL}`);
    }
    async generate(prompt) {
        try {
            const response = await this.llm.pipe(new output_parsers_1.StringOutputParser()).invoke([new messages_1.HumanMessage(prompt)]);
            return response;
        }
        catch (error) {
            console.error("LLM generation error:", error);
            throw new Error(`LLM generation failed: ${error instanceof Error ? error.message : String(error)}`);
        }
    }
    async embed(text) {
        try {
            const embedding = await this.embeddings.embedQuery(text);
            return embedding;
        }
        catch (error) {
            console.error("LLM embedding error:", error);
            throw new Error(`LLM embedding failed: ${error instanceof Error ? error.message : String(error)}`);
        }
    }
}
class LLMTool {
    name = "LLMTool";
    description = "Interacts with the LLM for generation or embedding.";
    async execute(actionTerm, context, triggerThought) {
        const operation = actionTerm.args[0]?.kind === 'Atom' ? actionTerm.args[0].name : null;
        const promptTerm = actionTerm.args[1]; // Assume second arg is prompt/content
        if (!operation || !promptTerm)
            return TermLogic.Atom("error:missing_llm_params");
        const inputText = TermLogic.termToString(promptTerm);
        try {
            if (operation === 'generate') {
                const resultText = await context.llmService.generate(inputText);
                // Return result as an Atom - calling rule/logic should structure it if needed
                return TermLogic.Atom(resultText);
            }
            else if (operation === 'embed') {
                const embedding = await context.llmService.embed(inputText);
                // Store embedding in the triggering thought's metadata? Or let MemoryTool handle it?
                // Let's assume MemoryTool is the primary way to store embeddings.
                // This tool could just return the embedding if needed directly.
                // For now, let MemoryTool handle storage. Return 'ok' or the embedding itself?
                // Returning 'ok' seems less useful. Let's return the embedding as a ListTerm of Atoms.
                // This might be large and less useful directly in rules, maybe MemoryTool is better.
                // Let's just return 'ok' and rely on MemoryTool for embedding storage.
                return TermLogic.Atom("ok:embedding_requested"); // Indicate request processed, MemoryTool handles storage
            }
            else {
                return TermLogic.Atom("error:unsupported_llm_operation");
            }
        }
        catch (error) {
            return TermLogic.Atom(`error:llm_failed:${error.message.substring(0, 50)}`);
        }
    }
}
class MemoryTool {
    name = "MemoryTool";
    description = "Adds to or searches the MemoryStore.";
    async execute(actionTerm, context, triggerThought) {
        const operation = actionTerm.args[0]?.kind === 'Atom' ? actionTerm.args[0].name : null;
        if (!operation)
            return TermLogic.Atom("error:missing_memory_operation");
        try {
            if (operation === 'add') {
                const contentTerm = actionTerm.args[1];
                if (!contentTerm)
                    return TermLogic.Atom("error:missing_memory_add_content");
                const contentStr = TermLogic.termToString(contentTerm);
                const sourceId = actionTerm.args[2]?.kind === 'Atom' ? actionTerm.args[2].name : triggerThought.id; // Allow overriding source ID
                const contentType = actionTerm.args[3]?.kind === 'Atom' ? actionTerm.args[3].name : triggerThought.type; // Allow overriding type
                await context.memoryStore.add({
                    id: generateId(), // Memory entry gets its own unique ID
                    content: contentStr,
                    metadata: {
                        timestamp: new Date().toISOString(),
                        type: contentType,
                        source_id: sourceId,
                    }
                });
                triggerThought.metadata.embedding_generated_at = new Date().toISOString(); // Mark thought as embedded
                context.thoughtStore.update(triggerThought); // Save timestamp update
                return TermLogic.Atom("ok:memory_added");
            }
            else if (operation === 'search') {
                const queryTerm = actionTerm.args[1];
                const k = actionTerm.args[2]?.kind === 'Atom' ? parseInt(actionTerm.args[2].name, 10) : 3;
                if (!queryTerm)
                    return TermLogic.Atom("error:missing_memory_search_query");
                const queryStr = TermLogic.termToString(queryTerm);
                const results = await context.memoryStore.search(queryStr, isNaN(k) ? 3 : k);
                // Return results as a ListTerm of Atoms (content only for simplicity in rules)
                return TermLogic.ListTerm(results.map(r => TermLogic.Atom(r.content)));
            }
            else {
                return TermLogic.Atom("error:unsupported_memory_operation");
            }
        }
        catch (error) {
            return TermLogic.Atom(`error:memory_failed:${error.message.substring(0, 50)}`);
        }
    }
}
class UserInteractionTool {
    name = "UserInteractionTool";
    description = "Requests input from the user via the console UI.";
    async execute(actionTerm, context, triggerThought) {
        const operation = actionTerm.args[0]?.kind === 'Atom' ? actionTerm.args[0].name : null;
        if (operation === 'prompt') {
            const promptTextTerm = actionTerm.args[1];
            if (!promptTextTerm)
                return TermLogic.Atom("error:missing_prompt_text");
            const promptText = TermLogic.termToString(promptTextTerm);
            const promptId = generateId();
            // Create a USER_PROMPT thought
            const promptThought = {
                id: generateId(),
                type: Type.USER_PROMPT,
                content: TermLogic.Atom(promptText),
                belief: new Belief(),
                status: Status.PENDING, // UI will display PENDING prompts
                metadata: {
                    root_id: triggerThought.metadata.root_id ?? triggerThought.id,
                    parent_id: triggerThought.id,
                    timestamp_created: new Date().toISOString(),
                    ui_context: { promptText, promptId } // Store prompt ID for response matching
                }
            };
            context.engine.addThought(promptThought); // Use engine method to add thought
            // Mark the triggering thought as WAITING for this prompt
            triggerThought.status = Status.WAITING;
            triggerThought.metadata.waiting_for_prompt_id = promptId;
            context.thoughtStore.update(triggerThought);
            return TermLogic.Atom(`ok:prompt_requested:${promptId}`);
        }
        else {
            return TermLogic.Atom("error:unsupported_ui_operation");
        }
    }
}
class GoalProposalTool {
    name = "GoalProposalTool";
    description = "Suggests new goals based on context or memory.";
    async execute(actionTerm, context, triggerThought) {
        const operation = actionTerm.args[0]?.kind === 'Atom' ? actionTerm.args[0].name : null;
        if (operation === 'suggest') {
            const contextTerm = actionTerm.args[1] ?? triggerThought.content; // Use trigger content if no context provided
            const contextStr = TermLogic.termToString(contextTerm);
            // 1. Search memory for related past goals/outcomes
            const memoryResults = await context.memoryStore.search(`Relevant past goals or outcomes related to: ${contextStr}`, 3);
            const memoryContext = memoryResults.map(r => r.content).join("\n - ");
            // 2. Generate suggestion using LLM
            const prompt = `Based on the current context "${contextStr}" and potentially related past activities:\n - ${memoryContext}\n\nSuggest ONE concise, actionable next goal or task. Output only the suggested goal text.`;
            try {
                const suggestionText = await context.llmService.generate(prompt);
                if (suggestionText && suggestionText.trim()) {
                    // Create a new INPUT thought for the suggestion
                    const suggestionThought = {
                        id: generateId(),
                        type: Type.INPUT, // Suggested goals come in as INPUT
                        content: TermLogic.Atom(suggestionText.trim()),
                        belief: new Belief(1, 0), // Start with positive belief
                        status: Status.PENDING,
                        metadata: {
                            root_id: triggerThought.metadata.root_id ?? triggerThought.id,
                            parent_id: triggerThought.id, // Link to the thought that triggered suggestion
                            timestamp_created: new Date().toISOString(),
                            tags: ['suggested_goal'],
                            provenance: 'GoalProposalTool'
                        }
                    };
                    context.engine.addThought(suggestionThought);
                    return TermLogic.Atom(`ok:suggestion_created:${suggestionThought.id}`);
                }
                else {
                    return TermLogic.Atom("ok:no_suggestion_generated");
                }
            }
            catch (error) {
                return TermLogic.Atom(`error:goal_suggestion_failed:${error.message.substring(0, 50)}`);
            }
        }
        else {
            return TermLogic.Atom("error:unsupported_goal_operation");
        }
    }
}
// CoreTool: Handles internal actions defined in rules (e.g., setting status, adding thoughts directly)
class CoreTool {
    name = "CoreTool";
    description = "Handles internal FlowMind operations like setting status or adding thoughts.";
    async execute(actionTerm, context, triggerThought) {
        const operation = actionTerm.args[0]?.kind === 'Atom' ? actionTerm.args[0].name : null;
        if (operation === 'set_status') {
            const targetThoughtIdTerm = actionTerm.args[1];
            const newStatusTerm = actionTerm.args[2];
            if (targetThoughtIdTerm?.kind !== 'Atom' || newStatusTerm?.kind !== 'Atom') {
                return TermLogic.Atom("error:invalid_set_status_params");
            }
            const targetId = targetThoughtIdTerm.name;
            const newStatus = newStatusTerm.name;
            if (!Object.values(Status).includes(newStatus)) {
                return TermLogic.Atom("error:invalid_status_value");
            }
            const targetThought = context.thoughtStore.get(targetId);
            if (targetThought) {
                targetThought.status = newStatus;
                context.thoughtStore.update(targetThought);
                return TermLogic.Atom(`ok:status_set:${targetId}_to_${newStatus}`);
            }
            else {
                return TermLogic.Atom(`error:target_thought_not_found:${targetId}`);
            }
        }
        else if (operation === 'add_thought') {
            // Args: type (Atom), content (Term), [rootId (Atom)], [parentId (Atom)]
            const typeTerm = actionTerm.args[1];
            const contentTerm = actionTerm.args[2];
            const rootIdTerm = actionTerm.args[3];
            const parentIdTerm = actionTerm.args[4];
            if (typeTerm?.kind !== 'Atom' || !contentTerm) {
                return TermLogic.Atom("error:invalid_add_thought_params");
            }
            const type = typeTerm.name;
            if (!Object.values(Type).includes(type)) {
                return TermLogic.Atom("error:invalid_thought_type");
            }
            const newThought = {
                id: generateId(),
                type: type,
                content: contentTerm,
                belief: new Belief(),
                status: Status.PENDING,
                metadata: {
                    root_id: rootIdTerm?.kind === 'Atom' ? rootIdTerm.name : triggerThought.metadata.root_id ?? triggerThought.id,
                    parent_id: parentIdTerm?.kind === 'Atom' ? parentIdTerm.name : triggerThought.id,
                    timestamp_created: new Date().toISOString(),
                    rule_id: triggerThought.metadata.rule_id // Propagate rule source? Or set based on current rule? Needs context.
                }
            };
            context.engine.addThought(newThought);
            return TermLogic.Atom(`ok:thought_added:${newThought.id}`);
        }
        else {
            return TermLogic.Atom("error:unsupported_core_operation");
        }
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
    listTools() {
        return Array.from(this.tools.values());
    }
}
// --- Engine (Agent Logic) ---
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
    // Public method for tools/UI to add thoughts consistently
    addThought(thought) {
        this.thoughtStore.add(thought);
        // Potentially trigger UI update here if needed immediately
    }
    sampleThought() {
        const pending = this.thoughtStore.getPending().filter(t => !this.activeThoughtIds.has(t.id));
        if (pending.length === 0)
            return null;
        // Calculate weights: priority metadata overrides belief score
        const weights = pending.map(t => t.metadata.priority ?? t.belief.score());
        const totalWeight = weights.reduce((sum, w) => sum + w, 0);
        if (totalWeight <= 0) { // Handle case where all weights are zero or negative
            return pending[Math.floor(Math.random() * pending.length)]; // Fallback to random sampling
        }
        let random = Math.random() * totalWeight;
        for (let i = 0; i < pending.length; i++) {
            random -= weights[i];
            if (random <= 0) {
                return pending[i];
            }
        }
        return pending[pending.length - 1]; // Fallback for floating point issues
    }
    findAndSelectRule(thought) {
        const matchingRules = [];
        for (const rule of this.ruleStore.getAll()) {
            const bindings = TermLogic.unify(rule.pattern, thought.content);
            if (bindings) {
                matchingRules.push({ rule, bindings });
            }
        }
        if (matchingRules.length === 0)
            return null;
        if (matchingRules.length === 1)
            return matchingRules[0];
        // Probabilistic selection based on rule belief score
        const weights = matchingRules.map(mr => mr.rule.belief.score());
        const totalWeight = weights.reduce((sum, w) => sum + w, 0);
        if (totalWeight <= 0) { // Handle case where all weights are zero or negative
            return matchingRules[Math.floor(Math.random() * matchingRules.length)]; // Fallback random
        }
        let random = Math.random() * totalWeight;
        for (let i = 0; i < matchingRules.length; i++) {
            random -= weights[i];
            if (random <= 0) {
                return matchingRules[i];
            }
        }
        return matchingRules[matchingRules.length - 1]; // Fallback
    }
    async executeAction(thought, rule, bindings) {
        const actionTermRaw = rule.action;
        const boundActionTerm = TermLogic.substitute(actionTermRaw, bindings);
        if (boundActionTerm.kind !== 'Structure') {
            this.handleFailure(thought, rule, `Invalid action term kind: ${boundActionTerm.kind}`);
            return false;
        }
        const toolName = boundActionTerm.name;
        const tool = this.toolRegistry.get(toolName);
        if (!tool) {
            this.handleFailure(thought, rule, `Tool not found: ${toolName}`);
            return false;
        }
        try {
            const toolContext = {
                thoughtStore: this.thoughtStore,
                ruleStore: this.ruleStore,
                memoryStore: this.memoryStore,
                llmService: this.llmService,
                engine: this,
            };
            // console.log(`Executing Tool: ${tool.name} for Thought ${thought.id.substring(0,8)} with Action: ${TermLogic.formatTerm(boundActionTerm)}`);
            const resultTerm = await tool.execute(boundActionTerm, toolContext, thought);
            // console.log(`Tool ${tool.name} Result: ${TermLogic.formatTerm(resultTerm)}`);
            // If tool execution didn't change the thought status (e.g. to WAITING), mark as DONE.
            // Check the thought's current status as the tool might have updated it.
            const currentThoughtState = this.thoughtStore.get(thought.id);
            if (currentThoughtState && currentThoughtState.status !== Status.WAITING && currentThoughtState.status !== Status.FAILED) {
                this.handleSuccess(thought, rule);
            }
            else if (!currentThoughtState) {
                // Thought was deleted during execution? Unlikely but possible. Log it.
                console.warn(`Thought ${thought.id} seems to have been deleted during action execution.`);
                // We can't update it, but update the rule belief.
                rule.belief.update(true); // Assume success if thought gone? Or false? Let's say true for now.
                this.ruleStore.update(rule);
            }
            // Note: If the status is WAITING, success is determined when the waiting condition is met (e.g., user response).
            // Note: If status is FAILED, handleFailure was likely called within the tool or here based on resultTerm.
            // Handle error results from tools if they return error Atoms/Structures
            if (resultTerm?.kind === 'Atom' && resultTerm.name.startsWith('error:')) {
                this.handleFailure(thought, rule, `Tool execution failed: ${resultTerm.name}`);
                return false;
            }
            // Success: update beliefs if not already handled by status checks above
            // The status check handles the main DONE case. WAITING needs subsequent handling.
            if (currentThoughtState?.status !== Status.WAITING && currentThoughtState?.status !== Status.FAILED) {
                rule.belief.update(true); // Also update rule belief on success
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
        // Default behavior: Use LLM based on thought type
        let prompt = "";
        let targetType = null;
        switch (thought.type) {
            case Type.INPUT:
                prompt = `Given the input note: "${TermLogic.termToString(thought.content)}"\n\nDefine a specific, actionable goal. Output only the goal text.`;
                targetType = Type.GOAL;
                break;
            case Type.GOAL:
                prompt = `Given the goal: "${TermLogic.termToString(thought.content)}"\n\nOutline 1-3 concise strategy steps to achieve this goal. Output only the steps, one per line.`;
                targetType = Type.STRATEGY; // Or potentially multiple STRATEGY thoughts
                break;
            case Type.STRATEGY:
                // If a strategy has no rule, maybe it needs execution summary or next step?
                // Let's try generating an outcome/summary.
                prompt = `The strategy step "${TermLogic.termToString(thought.content)}" was performed. Summarize a likely outcome or result. Output only the outcome text.`;
                targetType = Type.OUTCOME;
                break;
            default:
                // For QUERY, OUTCOME, etc. with no rules, maybe log or ask user?
                // Let's try asking the user for clarification as a fallback.
                const askUserPrompt = `The thought (${thought.type}: ${TermLogic.termToString(thought.content)}) has no applicable rules. What should be done next?`;
                const promptId = generateId();
                const promptThought = {
                    id: generateId(), type: Type.USER_PROMPT, content: TermLogic.Atom(askUserPrompt), belief: new Belief(), status: Status.PENDING,
                    metadata: { root_id: thought.metadata.root_id ?? thought.id, parent_id: thought.id, timestamp_created: new Date().toISOString(), ui_context: { promptText: askUserPrompt, promptId } }
                };
                this.addThought(promptThought);
                thought.status = Status.WAITING; // Wait for user response
                thought.metadata.waiting_for_prompt_id = promptId;
                this.thoughtStore.update(thought);
                return; // Exit after creating prompt
        }
        if (prompt && targetType) {
            try {
                const resultText = await this.llmService.generate(prompt);
                const results = resultText.split('\n').map(s => s.trim()).filter(s => s);
                results.forEach(resText => {
                    const newThought = {
                        id: generateId(),
                        type: targetType, // Checked above
                        content: TermLogic.Atom(resText),
                        belief: new Belief(),
                        status: Status.PENDING,
                        metadata: {
                            root_id: thought.metadata.root_id ?? thought.id,
                            parent_id: thought.id,
                            timestamp_created: new Date().toISOString(),
                            provenance: 'llm_default_handler'
                        }
                    };
                    this.addThought(newThought);
                });
                thought.status = Status.DONE; // Original thought is done as new ones were generated
                thought.belief.update(true); // Assume success if LLM generated something
                this.thoughtStore.update(thought);
            }
            catch (error) {
                this.handleFailure(thought, null, `Default LLM handler failed: ${error.message}`);
            }
        }
        else if (thought.status === Status.PENDING) {
            // If no action taken and still pending, mark failed to avoid infinite loops
            this.handleFailure(thought, null, "No matching rule and no default action applicable.");
        }
    }
    handleFailure(thought, rule, errorInfo) {
        const retries = (thought.metadata.retries ?? 0) + 1;
        thought.metadata.error_info = errorInfo.substring(0, 200); // Limit error length
        thought.metadata.retries = retries;
        thought.belief.update(false); // Update thought belief negatively
        if (rule) {
            rule.belief.update(false); // Update rule belief negatively
            this.ruleStore.update(rule);
            thought.metadata.rule_id = rule.id; // Ensure failed rule ID is logged
        }
        if (retries >= MAX_RETRIES) {
            thought.status = Status.FAILED;
            console.error(`Thought ${thought.id} FAILED permanently after ${retries} retries: ${errorInfo}`);
            // Optionally trigger rule synthesis here
            // this.synthesizeRuleForFailure(thought, errorInfo);
        }
        else {
            thought.status = Status.PENDING; // Re-queue for another attempt
            console.warn(`Thought ${thought.id} failed, retrying (${retries}/${MAX_RETRIES}): ${errorInfo}`);
        }
        this.thoughtStore.update(thought);
    }
    handleSuccess(thought, rule) {
        thought.status = Status.DONE;
        thought.belief.update(true);
        // Clear error info on success
        delete thought.metadata.error_info;
        delete thought.metadata.retries;
        if (rule) {
            rule.belief.update(true);
            this.ruleStore.update(rule);
            thought.metadata.rule_id = rule.id;
        }
        this.thoughtStore.update(thought);
    }
    // --- Main Processing Step ---
    async processSingleThought() {
        const thought = this.sampleThought();
        if (!thought)
            return false; // No work to do
        if (this.activeThoughtIds.has(thought.id))
            return false; // Already processing
        this.activeThoughtIds.add(thought.id);
        thought.status = Status.ACTIVE;
        thought.metadata.agent_id = 'worker'; // Simple ID for console version
        this.thoughtStore.update(thought);
        // console.log(`Processing Thought: ${thought.id.substring(0,8)} (${thought.type})`);
        let success = false;
        try {
            const match = this.findAndSelectRule(thought);
            if (match) {
                // console.log(`Matched Rule: ${match.rule.id.substring(0,8)} for Thought ${thought.id.substring(0,8)}`);
                success = await this.executeAction(thought, match.rule, match.bindings);
            }
            else {
                // console.log(`No rule match for Thought: ${thought.id.substring(0,8)}`);
                await this.handleNoRuleMatch(thought);
                // Success is determined by whether handleNoRuleMatch set status to DONE vs FAILED/WAITING
                const updatedThought = this.thoughtStore.get(thought.id);
                success = updatedThought?.status === Status.DONE;
            }
        }
        catch (error) {
            console.error(`Unhandled exception during thought processing ${thought.id}:`, error);
            this.handleFailure(thought, null, `Unhandled processing exception: ${error.message}`);
            success = false;
        }
        finally {
            this.activeThoughtIds.delete(thought.id);
            // Ensure thought isn't stuck in ACTIVE if something went wrong above handleSuccess/Failure
            const finalThoughtState = this.thoughtStore.get(thought.id);
            if (finalThoughtState?.status === Status.ACTIVE) {
                console.warn(`Thought ${thought.id} stuck in ACTIVE state, reverting to PENDING.`);
                this.handleFailure(thought, null, "Processing ended unexpectedly while ACTIVE.");
            }
        }
        return true; // Indicate that processing was attempted
    }
    // --- Rule Synthesis (Optional Enhancement) ---
    async synthesizeRuleForFailure(thought, errorInfo) {
        console.log(`Attempting to synthesize rule for failure of thought ${thought.id}`);
        const thoughtContent = TermLogic.termToString(thought.content);
        const prompt = `
         A thought processing failed.
         Thought Type: ${thought.type}
         Thought Content: ${thoughtContent}
         Failure Reason: ${errorInfo}
         Failed Rule ID (if any): ${thought.metadata.rule_id ?? 'N/A'}

         Define a FlowMind rule (pattern and action Terms) to potentially handle this situation better in the future.
         Rule Format:
         PATTERN: <TermLogic representation>
         ACTION: <TermLogic representation using CoreTool, LLMTool, MemoryTool, UserInteractionTool, or GoalProposalTool>

         Example PATTERN: Structure("process_email", [Variable("Subject"), Variable("Body")])
         Example ACTION: Structure("LLMTool", [Atom("generate"), Structure("summarize_email", [Variable("Subject"), Variable("Body")])])

         Generate the PATTERN and ACTION. Output ONLY the PATTERN: and ACTION: lines.
         `;
        try {
            const result = await this.llmService.generate(prompt);
            // Very basic parsing - needs improvement
            const patternMatch = result.match(/PATTERN:\s*(.*)/);
            const actionMatch = result.match(/ACTION:\s*(.*)/);
            if (patternMatch && actionMatch) {
                // TODO: Need a robust Term parser here instead of eval or simple Atom
                // const patternTerm = eval(`TermLogic.${patternMatch[1]}`); // DANGEROUS - DO NOT USE EVAL
                // const actionTerm = eval(`TermLogic.${actionMatch[1]}`); // DANGEROUS - DO NOT USE EVAL
                console.warn("Rule synthesis parsing not implemented due to complexity/safety. Generated text:\n", result);
                // If parsing were safe:
                // const newRule: Rule = { ... };
                // this.ruleStore.add(newRule);
                // console.log(`Synthesized and added new rule.`);
            }
            else {
                console.warn("Could not parse synthesized rule from LLM output:", result);
            }
        }
        catch (error) {
            console.error("Failed to synthesize rule:", error);
        }
    }
    // --- Prompt Response Handling ---
    async handlePromptResponse(promptId, responseText) {
        // Find the thought that was waiting for this prompt
        const waitingThought = Array.from(this.thoughtStore.getAll())
            .find(t => t.metadata.waiting_for_prompt_id === promptId && t.status === Status.WAITING);
        if (!waitingThought) {
            console.warn(`No waiting thought found for prompt ID: ${promptId}`);
            return;
        }
        // Create a new INPUT thought representing the user's response
        const responseThought = {
            id: generateId(),
            type: Type.INPUT, // User responses come in as INPUT
            content: TermLogic.Atom(responseText),
            belief: new Belief(1, 0), // High confidence in user input
            status: Status.PENDING,
            metadata: {
                root_id: waitingThought.metadata.root_id ?? waitingThought.id,
                parent_id: waitingThought.id,
                timestamp_created: new Date().toISOString(),
                response_to_prompt_id: promptId, // Link back to the prompt request
            }
        };
        this.addThought(responseThought);
        // Mark the original waiting thought as PENDING again, removing the waiting flag
        waitingThought.status = Status.PENDING;
        delete waitingThought.metadata.waiting_for_prompt_id;
        waitingThought.belief.update(true); // Successfully got the input needed
        this.thoughtStore.update(waitingThought);
        console.log(`Response received for prompt ${promptId}. Thought ${waitingThought.id} reactivated.`);
    }
}
async function saveState(thoughtStore, ruleStore, memoryStore) {
    try {
        await fs.mkdir(DATA_DIR, { recursive: true });
        const state = {
            thoughts: thoughtStore.toJSON(),
            rules: ruleStore.toJSON(),
        };
        await fs.writeFile(STATE_FILE_PATH, JSON.stringify(state, null, 2));
        await memoryStore.save(); // Save vector store separately
        // console.log(`State saved to ${STATE_FILE_PATH} and vector store saved.`);
    }
    catch (error) {
        console.error("Error saving state:", error);
    }
}
const debouncedSaveState = debounce(saveState, 5000); // Debounce saving
async function loadState(thoughtStore, ruleStore, memoryStore) {
    try {
        if (!fsSync.existsSync(STATE_FILE_PATH)) {
            console.log("No state file found, starting fresh.");
            // Initialize memory store even if state file doesn't exist
            await memoryStore.initialize();
            return;
        }
        const data = await fs.readFile(STATE_FILE_PATH, 'utf-8');
        const state = safeJsonParse(data, { thoughts: {}, rules: {} });
        const loadedThoughts = ThoughtStore.fromJSON(state.thoughts);
        loadedThoughts.getAll().forEach(t => thoughtStore.add(t)); // Populate the main store
        const loadedRules = RuleStore.fromJSON(state.rules);
        loadedRules.getAll().forEach(r => ruleStore.add(r)); // Populate the main store
        await memoryStore.initialize(); // Load vector store separately
        console.log(`State loaded from ${STATE_FILE_PATH}. Thoughts: ${thoughtStore.getAll().length}, Rules: ${ruleStore.getAll().length}`);
    }
    catch (error) {
        console.error("Error loading state:", error);
        // Ensure memory store is initialized even on load error
        if (!memoryStore.isReady)
            await memoryStore.initialize();
    }
}
// --- Console UI ---
class ConsoleUI {
    thoughtStore;
    systemControl;
    rl = null;
    refreshIntervalId = null;
    lastRenderedHeight = 0;
    constructor(thoughtStore, systemControlCallback) {
        this.thoughtStore = thoughtStore;
        this.systemControl = systemControlCallback;
    }
    start() {
        this.rl = readline.createInterface({
            input: process.stdin,
            output: process.stdout,
            prompt: 'FlowMind> '
        });
        this.rl.on('line', (line) => {
            const [command, ...args] = line.trim().split(' ');
            if (command) {
                this.systemControl(command.toLowerCase(), args);
            }
            else {
                this.rl?.prompt();
            }
        });
        this.rl.on('close', () => {
            this.systemControl('quit');
        });
        this.refreshIntervalId = setInterval(() => this.render(), UI_REFRESH_INTERVAL_MS);
        this.render(); // Initial render
        this.rl.prompt();
    }
    stop() {
        if (this.refreshIntervalId)
            clearInterval(this.refreshIntervalId);
        this.rl?.close();
        // Clear the screen one last time? Or leave the final state? Leaving it seems better.
        // console.clear();
        process.stdout.write('\nConsole UI stopped.\n');
    }
    render() {
        const thoughts = this.thoughtStore.getAll();
        const rootThoughts = thoughts.filter(t => t.type === Type.INPUT && !t.metadata.parent_id); // Simple root detection
        let output = "";
        const termWidth = process.stdout.columns || 80;
        // Clear previous output using ANSI escape codes
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
                const remainingWidth = termWidth - prefix.length - 1; // -1 for newline
                const truncatedContent = contentStr.length > remainingWidth
                    ? contentStr.substring(0, remainingWidth - 3) + "..."
                    : contentStr;
                output += prefix + truncatedContent + "\n";
                if (t.metadata.error_info) {
                    output += `    Error: ${t.metadata.error_info.substring(0, termWidth - 12)}\n`;
                }
                if (t.type === Type.USER_PROMPT && t.status === Status.PENDING && t.metadata.ui_context?.promptId) {
                    pendingPrompts.push(t);
                }
            });
        });
        if (pendingPrompts.length > 0) {
            output += "\n--- Pending Prompts ---\n";
            pendingPrompts.forEach(p => {
                const promptId = p.metadata.ui_context?.promptId ?? 'unknown_id';
                const waitingThoughtId = p.metadata.parent_id; // USER_PROMPT's parent is usually the waiter
                output += `[${promptId.substring(0, 6)}] ${TermLogic.formatTerm(p.content)} (Waiting: ${waitingThoughtId?.substring(0, 6) ?? 'N/A'})\n`;
            });
        }
        output += "\n" + "=".repeat(termWidth) + "\n";
        output += "Commands: add <note text>, respond <prompt_id> <response text>, run, pause, step, save, quit\n";
        // Calculate height and write output
        const outputLines = output.split('\n');
        this.lastRenderedHeight = outputLines.length + 1; // +1 for the prompt line
        // Use readline's clearLine and cursorTo before writing new output
        readline.cursorTo(process.stdout, 0, 0);
        readline.clearScreenDown(process.stdout);
        process.stdout.write(output);
        // Restore cursor and prompt
        this.rl?.prompt(true);
    }
}
// --- Main System Class ---
class FlowMindSystem {
    thoughtStore;
    ruleStore;
    memoryStore;
    llmService;
    toolRegistry;
    engine;
    consoleUI;
    isRunning = true; // Start running by default
    workerIntervalId = null;
    constructor() {
        this.thoughtStore = new ThoughtStore();
        this.ruleStore = new RuleStore();
        this.llmService = new LLMService();
        this.memoryStore = new MemoryStore(this.llmService.embeddings, VECTOR_STORE_PATH);
        this.toolRegistry = new ToolRegistry();
        this.engine = new Engine(this.thoughtStore, this.ruleStore, this.memoryStore, this.llmService, this.toolRegistry);
        this.consoleUI = new ConsoleUI(this.thoughtStore, this.handleCommand.bind(this));
        this.registerCoreTools();
    }
    registerCoreTools() {
        this.toolRegistry.register(new LLMTool());
        this.toolRegistry.register(new MemoryTool());
        this.toolRegistry.register(new UserInteractionTool());
        this.toolRegistry.register(new GoalProposalTool());
        this.toolRegistry.register(new CoreTool()); // Register the internal tool
    }
    bootstrapRules() {
        // Add a few basic rules if the store is empty
        if (this.ruleStore.getAll().length > 0)
            return;
        const rules = [
            // Rule 1: INPUT -> Generate GOAL using LLM
            {
                id: generateId(),
                pattern: TermLogic.Structure("thought", [TermLogic.Atom(Type.INPUT), TermLogic.Variable("Content")]),
                action: TermLogic.Structure("LLMTool", [
                    TermLogic.Atom("generate"),
                    TermLogic.Structure("prompt", [TermLogic.Atom(`Generate a specific GOAL for input: ${TermLogic.formatTerm(TermLogic.Variable("Content"))}`)]),
                    TermLogic.Atom(Type.GOAL) // Target type hint
                ])
            },
            // Rule 2: GOAL -> Generate STRATEGY using LLM
            {
                id: generateId(),
                pattern: TermLogic.Structure("thought", [TermLogic.Atom(Type.GOAL), TermLogic.Variable("Content")]),
                action: TermLogic.Structure("LLMTool", [
                    TermLogic.Atom("generate"),
                    TermLogic.Structure("prompt", [TermLogic.Atom(`Generate 1-3 STRATEGY steps for goal: ${TermLogic.formatTerm(TermLogic.Variable("Content"))}`)]),
                    TermLogic.Atom(Type.STRATEGY)
                ])
            },
            // Rule 3: STRATEGY -> Generate OUTCOME using LLM (Simple completion)
            {
                id: generateId(),
                pattern: TermLogic.Structure("thought", [TermLogic.Atom(Type.STRATEGY), TermLogic.Variable("Content")]),
                action: TermLogic.Structure("LLMTool", [
                    TermLogic.Atom("generate"),
                    TermLogic.Structure("prompt", [TermLogic.Atom(`Generate an OUTCOME/result for completing strategy: ${TermLogic.formatTerm(TermLogic.Variable("Content"))}`)]),
                    TermLogic.Atom(Type.OUTCOME)
                ])
            },
            // Rule 4: Add successful OUTCOME to memory
            {
                id: generateId(),
                pattern: TermLogic.Structure("thought", [TermLogic.Atom(Type.OUTCOME), TermLogic.Variable("Content")]),
                action: TermLogic.Structure("MemoryTool", [
                    TermLogic.Atom("add"),
                    TermLogic.Variable("Content"), // Content to add
                    TermLogic.Variable("ThoughtId"), // Use the thought ID as source ID
                    TermLogic.Atom(Type.OUTCOME) // Type hint
                ])
            },
            // Rule 5: If stuck (e.g., PENDING QUERY/OUTCOME > 1 min old?), try GoalProposal
            // Simple version: trigger GoalProposal sometimes after an OUTCOME
            {
                id: generateId(),
                // Match on an outcome, but execute probabilistically (handled by belief eventually)
                pattern: TermLogic.Structure("thought", [TermLogic.Atom(Type.OUTCOME), TermLogic.Variable("Content")]),
                action: TermLogic.Structure("GoalProposalTool", [
                    TermLogic.Atom("suggest"),
                    TermLogic.Variable("Content") // Context for suggestion
                ])
            },
        ];
        rules.forEach(r => {
            this.ruleStore.add({
                ...r,
                // Bind ThoughtId variable used in MemoryTool rule action (Rule 4)
                // This requires modifying the unification/pattern matching slightly,
                // or adding the Thought ID explicitly to the term structure during matching.
                // Let's assume for now the pattern implicitly binds ?ThoughtId.
                // A cleaner way is needed. For now, let's assume pattern is just content.
                pattern: r.pattern.args[1], // Assume pattern matches content only
                belief: new Belief(),
                metadata: { description: `Bootstrap rule ${rules.indexOf(r) + 1}`, provenance: 'bootstrap', timestamp_created: new Date().toISOString() }
            });
        });
        console.log(`Bootstrapped ${rules.length} rules.`);
    }
    async initialize() {
        console.log("Initializing FlowMind System...");
        await fs.mkdir(DATA_DIR, { recursive: true });
        await loadState(this.thoughtStore, this.ruleStore, this.memoryStore);
        this.bootstrapRules(); // Add default rules if needed
        this.consoleUI.start();
        this.startProcessingLoop();
        console.log("FlowMind System Initialized.");
    }
    startProcessingLoop() {
        if (this.workerIntervalId)
            clearInterval(this.workerIntervalId);
        this.workerIntervalId = setInterval(async () => {
            if (this.isRunning) {
                const processed = await this.engine.processSingleThought();
                if (processed) {
                    debouncedSaveState(this.thoughtStore, this.ruleStore, this.memoryStore);
                }
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
            case 'add':
                const contentText = args.join(' ');
                if (!contentText) {
                    console.log("Usage: add <note text>");
                    this.consoleUI['rl']?.prompt(); // Re-prompt using bracket notation
                    return;
                }
                const newThought = {
                    id: generateId(),
                    type: Type.INPUT,
                    content: TermLogic.Atom(contentText),
                    belief: new Belief(1, 0), // Start user input strong
                    status: Status.PENDING,
                    metadata: { timestamp_created: new Date().toISOString(), root_id: generateId() } // Give new notes a root ID
                };
                newThought.metadata.root_id = newThought.id; // Root ID is its own ID for new notes
                this.engine.addThought(newThought);
                debouncedSaveState(this.thoughtStore, this.ruleStore, this.memoryStore);
                console.log(`Added Note: ${newThought.id.substring(0, 6)}`);
                this.consoleUI['rl']?.prompt();
                break;
            case 'respond':
                const promptId = args[0];
                const responseText = args.slice(1).join(' ');
                if (!promptId || !responseText) {
                    console.log("Usage: respond <prompt_id> <response text>");
                    this.consoleUI['rl']?.prompt();
                    return;
                }
                // Find the full prompt ID based on the prefix
                const fullPromptId = Array.from(this.thoughtStore.getAll())
                    .map(t => t.metadata?.ui_context?.promptId)
                    .find(pid => pid?.startsWith(promptId));
                if (!fullPromptId) {
                    console.log(`Prompt ID starting with "${promptId}" not found.`);
                    this.consoleUI['rl']?.prompt();
                    return;
                }
                this.engine.handlePromptResponse(fullPromptId, responseText)
                    .then(() => debouncedSaveState(this.thoughtStore, this.ruleStore, this.memoryStore))
                    .catch(err => console.error("Error handling response:", err));
                this.consoleUI['rl']?.prompt();
                break;
            case 'run':
                if (!this.isRunning) {
                    this.isRunning = true;
                    console.log("System Resumed.");
                    this.startProcessingLoop();
                }
                else {
                    console.log("System is already running.");
                }
                this.consoleUI['rl']?.prompt();
                break;
            case 'pause':
                if (this.isRunning) {
                    this.isRunning = false;
                    this.stopProcessingLoop();
                    console.log("System Paused.");
                }
                else {
                    console.log("System is already paused.");
                }
                this.consoleUI['rl']?.prompt();
                break;
            case 'step':
                if (!this.isRunning) {
                    console.log("Executing single step...");
                    this.engine.processSingleThought()
                        .then((processed) => {
                        if (processed)
                            debouncedSaveState(this.thoughtStore, this.ruleStore, this.memoryStore);
                        console.log(processed ? "Step completed." : "No pending thoughts to process.");
                        this.consoleUI['rl']?.prompt();
                    })
                        .catch(err => {
                        console.error("Error during step execution:", err);
                        this.consoleUI['rl']?.prompt();
                    });
                }
                else {
                    console.log("System must be paused to step.");
                    this.consoleUI['rl']?.prompt();
                }
                break;
            case 'save':
                console.log("Manual save requested...");
                // Cancel any pending debounced save and save immediately
                debouncedSaveState.cancel(); // Assumes debounce returns an object with cancel, otherwise remove this
                saveState(this.thoughtStore, this.ruleStore, this.memoryStore)
                    .then(() => console.log("State saved."))
                    .catch(err => console.error("Manual save failed:", err))
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
        console.log("\nShutting down FlowMind System...");
        this.isRunning = false;
        this.stopProcessingLoop();
        this.consoleUI.stop();
        // Ensure final state is saved
        debouncedSaveState.cancel(); // Assumes debounce returns an object with cancel
        await saveState(this.thoughtStore, this.ruleStore, this.memoryStore);
        console.log("FlowMind System shut down gracefully.");
        process.exit(0);
    }
}
// --- Main Execution ---
async function main() {
    const system = new FlowMindSystem();
    process.on('SIGINT', async () => {
        await system.shutdown();
    });
    process.on('SIGTERM', async () => {
        await system.shutdown();
    });
    await system.initialize();
}
main().catch(error => {
    console.error("Critical error during system execution:", error);
    process.exit(1);
});
