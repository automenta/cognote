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
// Okay, let's refactor FlowMind into a client-server architecture using WebSockets and vanilla JavaScript for the frontend, eliminating React and the need for a build system like Webpack.
//
// **Architecture:**
//
// 1.  **Server (Node.js):**
// *   Hosts the Datascript database, LangChain tools, inference engine, action handler, and scheduler.
// *   Manages the core application state.
// *   Runs a WebSocket server (`ws`) to communicate with clients.
// *   Runs a basic HTTP server (`http`) to serve the static `index.html` and `client.js`.
// *   Handles persistence (saving/loading DB to a local file).
// 2.  **Client (Browser - `index.html` + `client.js`):**
// *   A static HTML file providing the basic structure.
// *   Vanilla JavaScript (`client.js`) connects to the WebSocket server.
// *   Receives state updates (thoughts, prompts, status) from the server.
// *   Sends user actions (add note, respond to prompt, control commands) to the server.
// *   Dynamically builds and updates the UI using standard DOM manipulation.
//
// ---
//
// **1. Server (`server.mjs`)**
//
// ```javascript
// server.mjs
const http_1 = __importDefault(require("http"));
const promises_1 = __importDefault(require("fs/promises"));
const fs_1 = __importDefault(require("fs"));
const ws_1 = require("ws");
const ds = __importStar(require("datascript"));
const uuid_1 = require("uuid");
const ollama_1 = require("@langchain/community/chat_models/ollama");
// --- Port and File Paths ---
const HTTP_PORT = 3000;
const WS_PORT = 3001;
const DB_FILE_PATH = './flowmind_db.json';
const CLIENT_HTML_PATH = './index.html';
const CLIENT_JS_PATH = './client.js';
// ======== Types (Copied and adapted) ========
// Keep necessary types here or in a separate types.mjs file
var Types;
(function (Types) {
    let Status;
    (function (Status) {
        Status["PENDING"] = ":flowmind.status/pending";
        Status["ACTIVE"] = ":flowmind.status/active";
        Status["WAITING"] = ":flowmind.status/waiting";
        Status["DONE"] = ":flowmind.status/done";
        Status["FAILED"] = ":flowmind.status/failed";
    })(Status = Types.Status || (Types.Status = {}));
})(Types || (Types = {}));
// ======== Global Constants (Copied) ========
const META_KEYS = {
    UI_PROMPT_TEXT: ':flowmind.meta.key/ui_prompt_text',
    WAITING_FOR_USER_INPUT: ':flowmind.meta.key/waiting_for_user_input',
    PARENT_UUID: ':flowmind.meta.key/parent_uuid',
    SOURCE_RULE_UUID: ':flowmind.meta.key/source_rule_uuid',
    TOOL_RESULT_FOR: ':flowmind.meta.key/tool_result_for',
    RESPONSE_TO_PROMPT: ':flowmind.meta.key/responded_to_prompt',
    EMBEDDING_VECTOR: ':flowmind.meta.key/embedding_vector'
};
// ======== Configuration (Server-side) ========
let config = {
    llmEndpoint: 'http://localhost:11434',
    ollamaModel: 'llama3:latest', // Adjust if needed
    vectorStore: 'memory',
    autoSaveInterval: 30000,
};
var Status = Types.Status;
var TermUtils;
(function (TermUtils) {
    /** Converts a Datascript entity map into a Term object. Requires pulling referenced terms. */
    TermUtils.entityToTerm = (db, entityId) => {
        if (!entityId)
            return null;
        const entity = db.pull(entityId, '[*]');
        if (!entity)
            return null;
        const kind = entity[':term/kind']?.replace(':flowmind.term/', '');
        const name = entity[':term/name'];
        switch (kind) {
            case 'struct':
                const argIds = entity[':term/args'] || [];
                return {
                    kind: 'struct',
                    name: name,
                    args: argIds.map((ref) => TermUtils.entityToTerm(db, ref[':db/id'])).filter(Boolean),
                };
            case 'list':
                const elementIds = entity[':term/elements'] || [];
                return {
                    kind: 'list',
                    elements: elementIds.map((ref) => TermUtils.entityToTerm(db, ref[':db/id'])).filter(Boolean),
                };
            case 'atom':
            case 'var':
                return { kind, name };
            default:
                console.warn("Unknown term kind:", kind, entity);
                return null; // Or throw error
        }
    };
    /** Converts a Term object into Datascript transaction data (list of entity maps). Returns the transaction data and the db/id of the root term entity. */
    TermUtils.termToTransactionData = (term) => {
        const rootId = ds.tempid('term');
        let tx = [];
        const base = { ':db/id': rootId, ':term/kind': `:flowmind.term/${term.kind}` };
        switch (term.kind) {
            case 'struct':
                const argsData = (term.args ?? []).map(arg => TermUtils.termToTransactionData(arg));
                tx = argsData.flatMap(d => d.tx);
                tx.push({ ...base, ':term/name': term.name, ':term/args': argsData.map(d => d.rootId) });
                break;
            case 'list':
                const elementsData = (term.elements ?? []).map(el => TermUtils.termToTransactionData(el));
                tx = elementsData.flatMap(d => d.tx);
                tx.push({ ...base, ':term/elements': elementsData.map(d => d.rootId) });
                break;
            case 'atom':
            case 'var':
                tx.push({ ...base, ':term/name': term.name });
                break;
        }
        return { tx, rootId };
    };
    /** Pretty prints a term. */
    TermUtils.formatTerm = (term) => {
        if (!term)
            return 'null';
        switch (term.kind) {
            case 'atom':
                return term.name ?? 'nil';
            case 'var':
                return `?${term.name ?? 'unnamed'}`;
            case 'struct':
                const argsStr = (term.args ?? []).map(TermUtils.formatTerm).join(', ');
                return `${term.name ?? 'anon_struct'}(${argsStr})`;
            case 'list':
                const elsStr = (term.elements ?? []).map(TermUtils.formatTerm).join(', ');
                return `[${elsStr}]`;
            default:
                return 'invalid_term';
        }
    };
    /**
     * Attempts to unify two terms, returning the resulting bindings if successful.
     * Variables are represented as { kind: 'var', name: 'VarName' }.
     */
    TermUtils.unify = (t1, t2, bindings = new Map()) => {
        const resolve = (term, currentBindings) => {
            if (term.kind === 'var' && term.name && currentBindings.has(term.name)) {
                return resolve(currentBindings.get(term.name), currentBindings);
            }
            return term;
        };
        const term1 = resolve(t1, bindings);
        const term2 = resolve(t2, bindings);
        if (term1.kind === 'var' && term1.name) {
            if (term1.name === term2.name && term1.kind === term2.kind)
                return { success: true, bindings }; // Var equals itself
            const newBindings = new Map(bindings);
            newBindings.set(term1.name, term2);
            return { success: true, bindings: newBindings };
        }
        if (term2.kind === 'var' && term2.name) {
            // Already handled t1 being a var, so t1 is not a var here
            const newBindings = new Map(bindings);
            newBindings.set(term2.name, term1);
            return { success: true, bindings: newBindings };
        }
        if (term1.kind !== term2.kind)
            return null; // Different kinds
        if (term1.kind === 'atom') {
            return term1.name === term2.name ? { success: true, bindings } : null;
        }
        if (term1.kind === 'struct') {
            if (term1.name !== term2.name || term1.args?.length !== term2.args?.length)
                return null;
            let currentBindings = bindings;
            for (let i = 0; i < (term1.args?.length ?? 0); i++) {
                const result = TermUtils.unify(term1.args[i], term2.args[i], currentBindings);
                if (!result)
                    return null;
                currentBindings = result.bindings;
            }
            return { success: true, bindings: currentBindings };
        }
        if (term1.kind === 'list') {
            if (term1.elements?.length !== term2.elements?.length)
                return null;
            let currentBindings = bindings;
            for (let i = 0; i < (term1.elements?.length ?? 0); i++) {
                const result = TermUtils.unify(term1.elements[i], term2.elements[i], currentBindings);
                if (!result)
                    return null;
                currentBindings = result.bindings;
            }
            return { success: true, bindings: currentBindings };
        }
        console.error("Unhandled unification case:", term1, term2);
        return null; // Should not happen
    };
    /** Substitutes variables in a term based on the bindings map. */
    TermUtils.substitute = (term, bindings) => {
        if (term.kind === 'var' && term.name && bindings.has(term.name)) {
            // Recursively substitute in case the binding is another variable or complex term
            return TermUtils.substitute(bindings.get(term.name), bindings);
        }
        if (term.kind === 'struct' && term.args) {
            return { ...term, args: term.args.map(arg => TermUtils.substitute(arg, bindings)) };
        }
        if (term.kind === 'list' && term.elements) {
            return { ...term, elements: term.elements.map(el => TermUtils.substitute(el, bindings)) };
        }
        return term; // Atoms or unbound vars
    };
})(TermUtils || (TermUtils = {}));
var Database;
(function (Database) {
    const dbSchema = { /* ... (copy schema from previous version) ... */};
    class DBInterface {
        db;
        history = [];
        // Removed listeners - communication handled by explicit WebSocket pushes
        constructor() {
            this.db = ds.default.empty_db(dbSchema);
        }
        transact(tx) {
            if (!tx || tx.length === 0)
                return null;
            const now = new Date();
            // Add timestamps (same logic as before)
            const txWithTimestamps = tx.map(t => {
            });
            try {
                const report = ds.transact(this.db, txWithTimestamps);
                if (report) {
                    this.db = report.db_after;
                    this.history.push(tx);
                    if (this.history.length > 100)
                        this.history.shift();
                    // --- Trigger WebSocket Update ---
                    broadcastStateUpdate(); // Notify clients AFTER transaction
                    return report;
                }
                return null;
            }
            catch (e) { /* ... error logging ... */
                return null;
            }
        }
        undo() {
            if (!this.history.length)
                return;
            this.history.pop();
            let tempDb = ds.empty_db(dbSchema);
            this.history.forEach(tx => {
            });
            this.db = tempDb;
            broadcastStateUpdate(); // Notify clients after undo
        }
        query(q, ...args) {
        }
        pull(id, pattern) {
        }
        pullMany(ids, pattern) {
        }
        findEntityIdByAttribute(attribute, value) {
        }
        // Keep static helpers: buildMetaTx, parseMeta
        static buildMetaTx(meta) {
        }
        static parseMeta(metaEntities) {
        }
        // --- Persistence ---
        async save(filePath) {
            try {
                const json = JSON.stringify(ds.db_to_json(this.db));
                await promises_1.default.writeFile(filePath, json, 'utf-8');
                console.log(`Database saved to ${filePath}`);
            }
            catch (e) {
                console.error(`Error saving database to ${filePath}:`, e.message);
            }
        }
        async load(filePath) {
            try {
                if (!fs_1.default.existsSync(filePath)) {
                    console.log(`Database file ${filePath} not found. Starting fresh.`);
                    return false; // Indicate no file was loaded
                }
                const json = await promises_1.default.readFile(filePath, 'utf-8');
                this.db = ds.json_to_db(JSON.parse(json), dbSchema);
                this.history = []; // Reset history after load
                console.log(`Database loaded from ${filePath}`);
                // No broadcast here, typically done after initial load sequence
                return true;
            }
            catch (e) {
                console.error(`Error loading database from ${filePath}:`, e.message);
                this.db = ds.default.empty_db(dbSchema); // Reset on error
                return false;
            }
        }
    }
    Database.DBInterface = DBInterface;
})(Database || (Database = {}));
var Tools;
(function (Tools) {
    // Keep LLMTool, MemoryTool, GoalProposalTool mostly as before
    class LLMTool {
        config;
        constructor(config) {
            this.config = config;
        }
        async execute({ kind, name, args = [] }) {
            if (kind !== 'struct' || !name)
                return { kind: 'atom', name: 'error' };
            const llm = new ollama_1.ChatOllama({ baseUrl: this.config.llmEndpoint });
            const [content] = args;
            try {
                if (name === 'generate') {
                    const resp = await llm.invoke(`Generate a goal for: ${content.name || 'unknown'}`);
                    return { kind: 'struct', name: 'goal', args: [{ kind: 'atom', name: resp.text.trim() }] };
                }
                if (name === 'embed') {
                    const embedding = await llm.embedQuery(content.name || '');
                    return { kind: 'list', elements: embedding.map((n) => ({ kind: 'atom', name: n.toString() })) };
                }
                if (name === 'synthesize_rule') {
                    const resp = await llm.invoke(`Synthesize a rule for thought: ${content.name || 'unknown'}`);
                    return { kind: 'struct', name: 'rule', args: [{ kind: 'atom', name: resp.text.trim() }] };
                }
            }
            catch (e) {
                console.error('LLM error:', e);
                return { kind: 'atom', name: 'error' };
            }
            return { kind: 'atom', name: 'unsupported' };
        }
    }
    Tools.LLMTool = LLMTool;
    class MemoryTool {
        store;
        constructor() {
            this.store = new VectorStore({ provider: 'memory' });
        }
        async execute({ kind, name, args = [] }) {
            if (kind !== 'struct' || !name)
                return { kind: 'atom', name: 'error' };
            try {
                if (name === 'add') {
                    const [, embedding, content] = args;
                    await this.store.add({
                        id: args[0].name,
                        embedding: embedding.elements?.map(e => parseFloat(e.name || '0')),
                        metadata: { content: content.name }
                    });
                    return { kind: 'atom', name: 'ok' };
                }
                if (name === 'search') {
                    const [embedding] = args;
                    const results = await this.store.query(embedding.elements?.map(e => parseFloat(e.name || '0')), 3);
                    return {
                        kind: 'list',
                        elements: results.map((r) => ({ kind: 'atom', name: r.metadata.content }))
                    };
                }
            }
            catch (e) {
                console.error('Memory error:', e);
                return { kind: 'atom', name: 'error' };
            }
            return { kind: 'atom', name: 'unsupported' };
        }
    }
    Tools.MemoryTool = MemoryTool;
    class GoalProposalTool {
        config;
        constructor(config) {
            this.config = config;
        }
        async execute({ kind, name, args = [] }) {
            if (kind !== 'struct' || name !== 'suggest')
                return { kind: 'atom', name: 'error' };
            const llm = new ollama_1.ChatOllama({ baseUrl: this.config.llmEndpoint });
            try {
                const context = args[0]?.name || 'general';
                const resp = await llm.invoke(`Suggest a goal based on: ${context}`);
                return {
                    kind: 'list',
                    elements: [{ kind: 'struct', name: 'goal', args: [{ kind: 'atom', name: resp.text.trim() }] }]
                };
            }
            catch (e) {
                console.error('Goal proposal error:', e);
                return { kind: 'atom', name: 'error' };
            }
        }
    }
    Tools.GoalProposalTool = GoalProposalTool;
    class CollaborationTool {
        socket;
        constructor() {
            this.socket = io('http://localhost:3000');
        } // Placeholder
        async execute({ kind, name, args = [] }) {
            if (kind !== 'struct' || name !== 'send' || !args[0].name || !args[1].name)
                return {
                    kind: 'atom',
                    name: 'error'
                };
            try {
                this.socket.emit('thought', { target: args[0].name, thought: args[1].name });
                return { kind: 'atom', name: 'ok' };
            }
            catch (e) {
                console.error('Collaboration error:', e);
                return { kind: 'atom', name: 'error' };
            }
        }
    }
    Tools.CollaborationTool = CollaborationTool;
    // --- Redesigned UserInteractionTool ---
    class UserInteractionTool {
        actionHandler;
        constructor(actionHandler) {
            this.actionHandler = actionHandler;
        } // Inject ActionHandler
        async execute(params) {
            if (params.kind !== 'struct' || params.name !== 'prompt' || !params.args || params.args.length === 0 || params.args[0].kind !== 'atom' || !params.args[0].name) {
                return { kind: 'atom', name: 'error:invalid_prompt_params' };
            }
            const promptText = params.args[0].name;
            const promptThoughtUUID = (0, uuid_1.v4)(); // This ID represents the *request* for input
            console.log(`UserInteractionTool: Requesting input via WebSocket: "${promptText}"`);
            // Instead of creating a thought, enqueue a WebSocket action
            // The ActionHandler will manage sending the request and waiting for the response.
            // We need context: which thought triggered this and is now waiting?
            // This context MUST be provided by the Engine when calling the tool op.
            // Let's assume the context includes the waitingThoughtUUID
            // The tool itself doesn't know which thought is waiting, the caller (engine/action handler) must manage this state.
            // This tool's *direct* execution result is just an indicator that the prompt process has started.
            // The actual user response will come back asynchronously via WebSocket message handling.
            // The primary role here is signalling the *need* for input. The ActionHandler or Engine must
            // manage the waiting state and response correlation.
            // Let's return the prompt request UUID.
            return { kind: 'atom', name: promptThoughtUUID };
        }
    }
    Tools.UserInteractionTool = UserInteractionTool;
    // Tool Registry (Adapted)
    class ToolRegistry {
        tools;
        constructor(cfg, db, actionHandler) {
            this.tools = new Map([
                ['llm', new LLMTool(cfg)],
                ['memory', new MemoryTool(cfg)], // Pass config
                ['user_interaction', new UserInteractionTool(actionHandler)], // Inject ActionHandler
                ['goal_proposal', new GoalProposalTool(cfg)], // Pass config
                // No CollaborationTool for now
            ]);
            console.log("ToolRegistry initialized with:", Array.from(this.tools.keys()));
        }
        get(name) {
            return this.tools.get(name);
        }
    }
    Tools.ToolRegistry = ToolRegistry;
})(Tools || (Tools = {}));
// ======== Engine (Adapted for Server) ========
var Engine;
(function (Engine) {
    var Status = Types.Status;
    class InferenceEngine {
        db;
        constructor(db) {
            this.db = db;
        }
        infer(triggerThoughtUUID) {
            // ... (Keep the main infer logic from the previous refactored version) ...
            // It should query the DB, find rules, select one, and call executeOps.
            // The key is that executeOps now might generate 'websocket' actions.
            const thoughtEid = this.db.findEntityIdByAttribute('uuid', triggerThoughtUUID);
            // ... rest of the checks and rule finding logic ...
            const { tx: opTx, actions: opActions } = this.executeOps(bodyOpTerms, bindings, triggerThoughtUUID, ruleUUID);
            // ... rest of the logic to update thought status, log events, update rule truth ...
            return { transactionData: finalTx, actions: opActions };
        }
        selectRule(matches) {
        }
        // --- Modified executeOps to potentially handle WebSocket actions ---
        executeOps(ops, bindings, triggerUUID, ruleUUID) {
            const tx = [];
            const actions = [];
            let currentBindings = new Map(bindings);
            for (const opTerm of ops) {
                const op = TermUtils.substitute(opTerm, currentBindings);
                if (op.kind !== 'struct' || !op.name || !op.args)
                    continue;
                // --- op:tool ---
                if (op.name === 'op:tool' && op.args.length >= 2 /*...*/) {
                    const toolName = op.args[0].name;
                    const toolParams = op.args[1];
                    const resultVar = op.args[2]?.kind === 'var' ? op.args[2].name : undefined;
                    // --- Special Handling for User Interaction ---
                    if (toolName === 'user_interaction' && toolParams.name === 'prompt' && toolParams.args?.[0]?.kind === 'atom') {
                        const promptText = toolParams.args[0].name;
                        const promptId = (0, uuid_1.v4)(); // ID for this specific prompt request
                        console.log(`Engine: Enqueuing user input request (id: ${promptId.substring(0, 8)})`);
                        // Action to send request via WebSocket
                        actions.push({
                            type: 'websocket', // New action type
                            wsMessageType: 'request_user_input',
                            wsPayload: { promptId, text: promptText, waitingThoughtUUID: triggerUUID },
                            context: { triggerUUID, ruleUUID }
                        });
                        // Mark the triggering thought as waiting for the response
                        tx.push({
                            ':db/id': this.db.findEntityIdByAttribute('uuid', triggerUUID),
                            ':thought/status': Status.WAITING
                        });
                    }
                    else {
                        // Normal tool action (LLM, Memory, etc.)
                        actions.push({
                            type: 'tool',
                            name: toolName,
                            params: toolParams,
                            context: {
                                triggerUUID,
                                ruleUUID,
                                resultBindingVar: resultVar,
                                waitingThoughtUUID: resultVar ? triggerUUID : undefined
                            }
                        });
                        if (resultVar) {
                            tx.push({
                                ':db/id': this.db.findEntityIdByAttribute('uuid', triggerUUID),
                                ':thought/status': Status.WAITING
                            });
                        }
                    }
                }
                // --- op:add_thought ---
                else if (op.name === 'op:add_thought' /*...*/) { /* ... implementation ... */
                }
                // --- op:set ---
                else if (op.name === 'op:set' /*...*/) { /* ... implementation ... */
                }
                // --- op:log ---
                else if (op.name === 'op:log' /*...*/) { /* ... implementation ... */
                }
                else { /* ... unknown operation logging ... */
                }
            }
            return { tx, actions };
        }
        createEventTx(targetUUID, type, data) {
        }
    }
    Engine.InferenceEngine = InferenceEngine;
})(Engine || (Engine = {}));
var ActionHandler;
(function (ActionHandler) {
    var Status = Types.Status;
    // Store pending prompts waiting for user response via WebSocket
    const pendingPrompts = new Map();
    class AH {
        db;
        tools;
        queue = [];
        processing = false;
        constructor(db, tools) {
            this.db = db;
            this.tools = tools;
        }
        enqueue(actions) {
            if (!actions || actions.length === 0)
                return;
            this.queue.push(...actions);
            this.processQueue();
        }
        // --- Called by WebSocket server when user response arrives ---
        async handleUserInputResponse(promptId, responseText) {
            console.log(`ActionHandler: Received response for prompt ${promptId.substring(0, 8)}`);
            if (!pendingPrompts.has(promptId)) {
                console.warn(`ActionHandler: Received response for unknown/expired promptId: ${promptId}`);
                return;
            }
            const { waitingThoughtUUID, ruleUUID } = pendingPrompts.get(promptId);
            pendingPrompts.delete(promptId); // Consume the prompt
            const waitingThoughtEid = this.db.findEntityIdByAttribute('uuid', waitingThoughtUUID);
            if (!waitingThoughtEid) {
                console.warn(`ActionHandler: Waiting thought ${waitingThoughtUUID} not found for prompt response.`);
                return;
            }
            // Create a new thought for the user's response
            const responseThoughtUUID = (0, uuid_1.v4)();
            const responseTermData = TermUtils.termToTransactionData({ kind: 'atom', name: responseText });
            const responseMeta = new Map([
                [META_KEYS.RESPONSE_TO_PROMPT, promptId], // Link to the prompt *request* ID
                [META_KEYS.PARENT_UUID, waitingThoughtUUID] // Link to the thought that was waiting
            ]);
            const metaTxData = Database.DBInterface.buildMetaTx(responseMeta);
            const newThoughtTx = [
                ...responseTermData.tx,
                ...metaTxData.tx,
                {
                    ':db/id': ds.tempid('goal'),
                    ':goal/value': 0.8,
                    ':goal/source': 'user_response',
                    ':goal/time': new Date()
                },
                { ':db/id': ds.tempid('truth'), ':truth/pos': 1, ':truth/neg': 0 },
                {
                    ':db/id': ds.tempid('thought'),
                    uuid: responseThoughtUUID,
                    kind: ':flowmind.kind/thought',
                    ':thought/content': responseTermData.rootId,
                    ':thought/status': Status.PENDING, // Ready for engine
                    ':thought/truth': -1, ':thought/goal': -2, ':thought/meta': metaTxData.refs,
                    createdAt: new Date(), modifiedAt: new Date(),
                },
                // Reactivate the waiting thought
                { ':db/id': waitingThoughtEid, ':thought/status': Status.PENDING, modifiedAt: new Date() }
            ];
            this.db.transact(newThoughtTx); // This will trigger broadcast update
        }
        async processQueue() {
            if (this.processing)
                return;
            this.processing = true;
            while (this.queue.length > 0) {
                const action = this.queue.shift();
                try {
                    switch (action.type) {
                        case 'tool':
                            await this.handleToolAction(action);
                            break;
                        case 'websocket': // Handle sending messages to client
                            if (action.wsMessageType === 'request_user_input' && action.wsPayload) {
                                // Store the prompt request details for correlation later
                                pendingPrompts.set(action.wsPayload.promptId, {
                                    waitingThoughtUUID: action.wsPayload.waitingThoughtUUID,
                                    ruleUUID: action.context.ruleUUID
                                });
                                // Broadcast to *all* connected clients (simplest approach)
                                // A specific client ID could be added to action.context if needed
                                broadcastMessage({ type: action.wsMessageType, payload: action.wsPayload });
                            }
                            else {
                                console.warn("Unhandled websocket action:", action);
                            }
                            break;
                        case 'log': /* ... logging ... */
                            break;
                        default: /* ... unknown action ... */
                            break;
                    }
                }
                catch (error) {
                    console.error(`ActionHandler: Error processing action: ${error.message}`, action);
                    this.logEvent(action.context.triggerUUID, ':flowmind.event.type/action_handler_error', {
                        actionType: action.type,
                        error: error.message
                    });
                    // Handle failure (e.g., mark waiting thought as FAILED)
                    if ((action.type === 'tool' || action.type === 'websocket') && action.context.waitingThoughtUUID) {
                        const waitingEid = this.db.findEntityIdByAttribute('uuid', action.context.waitingThoughtUUID);
                        if (waitingEid)
                            this.db.transact([{ ':db/id': waitingEid, ':thought/status': Status.FAILED }]);
                    }
                }
            }
            this.processing = false;
        }
        async handleToolAction(action) {
            // ... (Keep the logic for executing LLMTool, MemoryTool, etc.) ...
            // ... (It should create result thoughts and reactivate waiting thoughts as before) ...
            if (!action.name || !action.params || !action.context)
                return;
            const tool = this.tools.get(action.name);
            // ... tool not found check ...
            const resultTerm = await tool.execute(action.params);
            // ... log success event ...
            // Handle result binding (create result thought, reactivate waiter)
            if (action.context.resultBindingVar && action.context.waitingThoughtUUID) {
                // ... logic to create result thought and reactivate waiting thought ...
                // Transaction here will trigger broadcast update
            }
            else if (resultTerm.name?.startsWith('error:') && action.context.waitingThoughtUUID) {
                // ... logic to mark waiting thought as FAILED ...
                // Transaction here will trigger broadcast update
            }
        }
        logEvent(targetUUID, type, data) {
        }
    }
    ActionHandler.AH = AH;
})(ActionHandler || (ActionHandler = {}));
// ======== Scheduler (Server-Side) ========
var Scheduler;
(function (Scheduler) {
    class ExecutionScheduler {
        db;
        engine;
        handler;
        tickInterval;
        runState = 'running';
        timeoutId = null;
        isProcessing = false;
        constructor(db, engine, handler, tickInterval = 100 // Adjusted interval
        ) {
            this.db = db;
            this.engine = engine;
            this.handler = handler;
            this.tickInterval = tickInterval;
            this.scheduleNextTick();
        }
        scheduleNextTick() {
            if (this.runState === 'paused')
                return;
            if (this.timeoutId)
                clearTimeout(this.timeoutId);
            this.timeoutId = setTimeout(async () => {
                if (this.runState === 'running' || this.runState === 'stepping') {
                    await this.tick();
                    if (this.runState === 'stepping')
                        this.setRunState('paused'); // Auto-pause after step
                }
                if (this.runState !== 'paused')
                    this.scheduleNextTick();
            }, this.tickInterval);
        }
        async tick() {
            if (this.isProcessing)
                return;
            this.isProcessing = true;
            try {
                // ... (Keep the logic to find highest priority PENDING thought) ...
                const pendingThoughts = this.db.query( /* ... */);
                if (pendingThoughts.length === 0) {
                    this.isProcessing = false;
                    return;
                }
                // ... (Select best thought logic) ...
                const triggerUUID = bestThoughtUUID;
                // ... (Lock thought to ACTIVE status) ...
                const lockTxReport = this.db.transact( /* ... */); // Triggers broadcast
                if (!lockTxReport) {
                    this.isProcessing = false;
                    return;
                }
                const inferenceResult = this.engine.infer(triggerUUID);
                if (inferenceResult) {
                    // Transact results (will trigger another broadcast)
                    if (inferenceResult.transactionData.length > 0)
                        this.db.transact(inferenceResult.transactionData);
                    // Enqueue actions
                    if (inferenceResult.actions.length > 0)
                        this.handler.enqueue(inferenceResult.actions);
                }
                else {
                    // Revert status if inference failed
                    this.db.transact([{ ':db/id': thoughtEid, ':thought/status': Status.PENDING }]); // Triggers broadcast
                }
            }
            catch (error) { /* ... error logging ... */
            }
            finally {
                this.isProcessing = false;
            }
        }
        setRunState(state) {
            if (state === this.runState)
                return;
            const previousState = this.runState;
            this.runState = state;
            console.log(`Scheduler state changed to: ${state}`);
            broadcastMessage({ type: 'status_update', payload: { runState: this.runState } }); // Notify clients
            if (state === 'running' && previousState !== 'running')
                this.scheduleNextTick();
            if (state === 'paused' && this.timeoutId)
                clearTimeout(this.timeoutId);
        }
        getRunState() {
            return this.runState;
        }
        step() {
            if (this.runState === 'paused') {
                console.log("Scheduler stepping...");
                this.runState = 'stepping'; // Tick will run once then set to paused
                this.scheduleNextTick();
            }
        }
        stop() {
        }
    }
    Scheduler.ExecutionScheduler = ExecutionScheduler;
})(Scheduler || (Scheduler = {}));
// ======== Bootstrap Rules (Server-Side) ========
var Bootstrap;
(function (Bootstrap) {
    // Keep getBootstrapRules() and bootstrapDB() mostly as before
    // Ensure rules use the correct op:tool format for user_interaction
    Bootstrap.getBootstrapRules = () => {
        // ... (Copy rules from previous refactor, ensuring user interaction uses the correct op)
        // Example fix for the old Rule 3 (if re-enabled):
        /*
        {
            uuid: uuid(),
            head: { ... }, // Match active thought
            body: [
                { kind: 'struct', name: 'op:log', args: [...] },
                // Correct way to trigger prompt via websocket action
                { kind: 'struct', name: 'op:tool', args: [
                    { kind: 'atom', name: 'user_interaction' }, // The tool name
                    { kind: 'struct', name: 'prompt', args: [{ kind: 'atom', name: 'Need clarification for: ' }, { kind: 'var', name: 'Content' }] } // Parameters for the tool
                    // NO result var needed, handled by websocket flow
                ]},
                // Status set to WAITING by the op:tool handler implicitly via websocket action
            ],
            truth: { pos: 0, neg: 5 },
            meta: new Map([['description', 'Fallback rule: Ask user for clarification']])
        },
        */
        // ... other rules ...
    };
    Bootstrap.bootstrapDB = (db) => {
    };
})(Bootstrap || (Bootstrap = {}));
// ======== Server Setup ========
// --- Initialize Core Components ---
const db = new Database.DBInterface();
// Inject dependencies correctly
const actionHandler = new ActionHandler.AH(db, null); // Placeholder for tools
const tools = new Tools.ToolRegistry(config, db, actionHandler);
actionHandler.tools = tools; // Inject tools back into handler after registry creation
const engine = new Engine.InferenceEngine(db);
const scheduler = new Scheduler.ExecutionScheduler(db, engine, actionHandler);
// --- Persistence ---
let autoSaveIntervalId = null;
async function initializePersistence() {
    const loaded = await db.load(DB_FILE_PATH);
    if (!loaded) {
        Bootstrap.bootstrapDB(db); // Bootstrap only if load failed or file absent
        await db.save(DB_FILE_PATH); // Initial save if bootstrapped
    }
    // Setup auto-save
    if (autoSaveIntervalId)
        clearInterval(autoSaveIntervalId);
    autoSaveIntervalId = setInterval(() => db.save(DB_FILE_PATH), config.autoSaveInterval);
}
// --- WebSocket Server ---
const wss = new ws_1.WebSocketServer({ port: WS_PORT });
const clients = new Set(); // Store connected WebSocket clients
wss.on('connection', (ws) => {
    console.log('Client connected');
    clients.add(ws);
    ws.on('message', (message) => {
        try {
            const parsedMessage = JSON.parse(message.toString());
            // console.log('Received:', parsedMessage); // Debugging
            handleWebSocketMessage(ws, parsedMessage);
        }
        catch (e) {
            console.error('Failed to parse message or handle:', message.toString(), e);
            ws.send(JSON.stringify({ type: 'error', payload: { message: 'Invalid message format' } }));
        }
    });
    ws.on('close', () => {
        console.log('Client disconnected');
        clients.delete(ws);
    });
    ws.on('error', (error) => {
        console.error('WebSocket error:', error);
        clients.delete(ws); // Remove potentially broken client
    });
    // Send initial state shortly after connection established
    setTimeout(() => sendInitialState(ws), 100);
});
function broadcastMessage(message) {
    const messageString = JSON.stringify(message);
    // console.log('Broadcasting:', message.type); // Debugging
    clients.forEach((client) => {
        if (client.readyState === WebSocket.OPEN) {
            client.send(messageString);
        }
    });
}
function sendMessage(client, message) {
    if (client.readyState === WebSocket.OPEN) {
        client.send(JSON.stringify(message));
    }
}
// --- Message Handling Logic ---
function handleWebSocketMessage(ws, message) {
    switch (message.type) {
        case 'get_initial_state':
            sendInitialState(ws);
            break;
        case 'add_thought':
            if (typeof message.payload?.content === 'string' && message.payload.content.trim()) {
                const content = message.payload.content.trim();
                const newThoughtUUID = (0, uuid_1.v4)();
                const contentTermData = TermUtils.termToTransactionData({ kind: 'atom', name: content });
                // Simplified transaction for adding user thought
                db.transact([
                    ...contentTermData.tx,
                    { ':db/id': -1, ':goal/value': 0.7, ':goal/source': 'user_input', ':goal/time': new Date() },
                    { ':db/id': -2, ':truth/pos': 1, ':truth/neg': 0 },
                    {
                        ':db/id': -3, uuid: newThoughtUUID, kind: ':flowmind.kind/thought',
                        ':thought/content': contentTermData.rootId, ':thought/status': Types.Status.PENDING,
                        ':thought/truth': -2, ':thought/goal': -1, ':thought/meta': [],
                        createdAt: new Date(), modifiedAt: new Date()
                    }
                ]); // Transaction triggers broadcast
            }
            break;
        case 'user_input_response':
            if (message.payload?.promptId && typeof message.payload?.response === 'string') {
                actionHandler.handleUserInputResponse(message.payload.promptId, message.payload.response);
                // Response handling triggers DB transaction -> broadcast
            }
            break;
        case 'update_goal':
            if (message.payload?.thoughtUUID && typeof message.payload?.delta === 'number') {
                const { thoughtUUID, delta } = message.payload;
                const thoughtEid = db.findEntityIdByAttribute('uuid', thoughtUUID);
                if (thoughtEid) {
                    const goalRef = db.pull(thoughtEid, [{ ':thought/goal': [':db/id', ':goal/value'] }])?.[':thought/goal'];
                    if (goalRef) {
                        const currentValue = goalRef[':goal/value'] ?? 0.5;
                        const newValue = Math.max(0, Math.min(1, currentValue + delta));
                        db.transact([{
                                ':db/id': goalRef[':db/id'],
                                ':goal/value': newValue,
                                ':goal/source': 'user_adjust',
                                ':goal/time': new Date()
                            }]); // Triggers broadcast
                    }
                }
            }
            break;
        case 'control_scheduler':
            if (['run', 'pause', 'step'].includes(message.payload?.command)) {
                scheduler.setRunState(message.payload.command);
                if (message.payload.command === 'step')
                    scheduler.step();
            }
            break;
        case 'request_undo':
            db.undo(); // Triggers broadcast
            break;
        case 'request_save':
            db.save(DB_FILE_PATH);
            sendMessage(ws, { type: 'status_update', payload: { message: 'Database saved.' } });
            break;
        case 'request_load':
            initializePersistence().then(() => {
                broadcastStateUpdate(); // Force update after load
                sendMessage(ws, { type: 'status_update', payload: { message: 'Database loaded.' } });
            });
            break;
        case 'get_config':
            sendMessage(ws, { type: 'config_update', payload: config });
            break;
        case 'update_config': // Basic config update - requires validation/restart logic in real app
            if (message.payload?.config) {
                console.log("Received config update request:", message.payload.config);
                // Naive update - in reality, need validation and possibly restart parts of the app
                config = { ...config, ...message.payload.config };
                // Re-inject config where needed (e.g., tools might need re-initialization)
                // This is complex without a restart. For now, just update the object.
                broadcastMessage({ type: 'config_update', payload: config }); // Inform all clients
                sendMessage(ws, { type: 'status_update', payload: { message: 'Config updated (restart may be needed).' } });
            }
            break;
        // Add handlers for other client messages (get debug data, etc.)
        default:
            console.warn('Unknown message type received:', message.type);
            sendMessage(ws, { type: 'error', payload: { message: `Unknown command: ${message.type}` } });
    }
}
// --- State Broadcasting Logic ---
function getAllThoughtsForClient() {
    const thoughtEids = db.query('[:find ?t :where [?t :kind :flowmind.kind/thought]]').map(r => r[0]);
    const thoughtData = db.pullMany(thoughtEids, [
        'uuid', ':thought/status',
        { ':thought/content': ['*'] },
        { ':thought/goal': [':goal/value', ':goal/source'] },
        ':created_at',
        { ':thought/meta': [':meta_entry/key', ':meta_entry/value_string'] } // Example meta pull
    ]);
    return thoughtData.map((tData) => {
        if (!tData)
            return null;
        const contentTerm = TermUtils.entityToTerm(db, tData[':thought/content']?.[':db/id']);
        const meta = Database.DBInterface.parseMeta(tData[':thought/meta'] ?? {});
        return {
            uuid: tData.uuid,
            contentStr: TermUtils.simplifyTermForClient(contentTerm), // Send simplified string
            status: tData[':thought/status']?.split('/')[1] || 'unknown',
            goalValue: tData[':thought/goal']?.[':goal/value'] ?? 0,
            goalSource: tData[':thought/goal']?.[':goal/source'] ?? 'unknown',
            createdAt: (tData[':created_at'] ?? new Date(0)).toISOString(),
            meta: Object.fromEntries(meta.entries()), // Convert map for JSON
        };
    }).filter(Boolean);
}
function getActivePromptsForClient() {
    return Array.from(pendingPrompts.entries()).map(([promptId, data]) => {
        // We need the prompt text again. The original action payload had it,
        // but we didn't store it in pendingPrompts. This is a flaw.
        // WORKAROUND: Pull the waiting thought's content as a proxy?
        // This requires the original design where the *rule* specified the text.
        // Or the 'request_user_input' wsPayload should be stored completely.
        // Let's assume we stored it:
        // const promptData = storedPromptData.get(promptId); // Hypothetical stored data
        // const text = promptData?.text || `Missing prompt text for ${promptId}`;
        // For now, use a placeholder:
        const text = `Input needed for ${data.waitingThoughtUUID.substring(0, 8)} (ID: ${promptId.substring(0, 8)})`;
        return {
            promptId,
            text: text, // FIXME: Need to store/retrieve original prompt text
            waitingThoughtUUID: data.waitingThoughtUUID,
        };
    });
}
function broadcastStateUpdate() {
    const thoughts = getAllThoughtsForClient();
    const prompts = getActivePromptsForClient();
    broadcastMessage({
        type: 'state_update',
        payload: {
            thoughts: thoughts.sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt)), // Sort client-side? Server sort is fine.
            prompts: prompts
        }
    });
}
function sendInitialState(ws) {
    const thoughts = getAllThoughtsForClient();
    const prompts = getActivePromptsForClient();
    sendMessage(ws, {
        type: 'initial_state',
        payload: {
            thoughts: thoughts.sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt)),
            prompts: prompts,
            config: config,
            runState: scheduler.getRunState()
        }
    });
}
// --- HTTP Server for Static Files ---
const server = http_1.default.createServer(async (req, res) => {
    console.log(`HTTP Request: ${req.method} ${req.url}`);
    let filePath = '';
    if (req.url === '/' || req.url === '/index.html') {
        filePath = CLIENT_HTML_PATH;
    }
    else if (req.url === '/client.js') {
        filePath = CLIENT_JS_PATH;
    }
    else {
        res.writeHead(404);
        res.end('Not Found');
        return;
    }
    try {
        const content = await promises_1.default.readFile(filePath, 'utf-8');
        let contentType = 'text/html';
        if (filePath.endsWith('.js'))
            contentType = 'text/javascript';
        else if (filePath.endsWith('.css'))
            contentType = 'text/css';
        res.writeHead(200, { 'Content-Type': contentType });
        res.end(content);
    }
    catch (err) {
        console.error("Error reading file:", err);
        res.writeHead(500);
        res.end('Server Error');
    }
});
// --- Start Servers ---
server.listen(HTTP_PORT, async () => {
    await initializePersistence(); // Load DB before starting scheduler/accepting connections
    console.log(`HTTP server listening on http://localhost:${HTTP_PORT}`);
    console.log(`WebSocket server listening on ws://localhost:${WS_PORT}`);
    scheduler.setRunState('running'); // Start the engine after setup
});
