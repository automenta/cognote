// ### Additional Bootstrap Rules
//
// 1.  **Self-Correction on Failure:**
// *   **Description:** When a thought fails (`FAILED` status, triggering the `failure` log thought), attempt a recovery action instead of just asking the user immediately.
// *   **Pattern:** `Log(failure(thoughtId(?FailedId), content(?FailedContent), error(?ErrorMsg)))`
// *   **Action:** `LLMTool(generate, 'GENERATE_RECOVERY_STRATEGY', context(failedId:?FailedId, failedContent:?FailedContent, error:?ErrorMsg))` *OR* `CoreTool(set_status, ?FailedId, 'PENDING')` (for a simple retry rule) *OR* try finding an alternative existing Strategy if the failed thought was an Outcome.
// *   **Priority:** Medium (e.g., 40) - lower than the "Ask How to Handle Failure" rule, allowing automatic attempts first.
//
// 2.  **Stuck Thought Detection:**
// *   **Description:** Periodically check for thoughts that have been `PENDING` or `WAITING` for an unusually long time and prompt for action. (Requires a TimeTool).
// *   **Pattern:** `System(tick)` (Assuming a `System(tick)` thought is generated periodically by a TimeTool) *AND* a way to query thoughts by status and age (potentially via a CoreTool enhancement or a dedicated QueryTool).
// *   **Action:** `UserInteractionTool(prompt, 'STUCK_THOUGHT_PROMPT', context(thoughtId:?StuckId, status:?StuckStatus, age:?StuckAge))`
// *   **Priority:** Very Low (e.g., -50)
//
// 3.  **Input Clarification (More Specific):**
// *   **Description:** If input is very short or seems ambiguous (e.g., contains only common words), trigger clarification *before* attempting goal generation.
// *   **Pattern:** `Input(?Content)` where `?Content` is determined to be potentially ambiguous (e.g., `Atom` with short length, needs a helper function or predicate in the pattern matcher, which isn't directly supported here - might need a preliminary rule/tool to tag thoughts as 'ambiguous').
// *   **Action:** `UserInteractionTool(prompt, 'CLARIFY_INPUT', context(input:?Content))`
// *   **Priority:** Higher than default `Input` -> `GENERATE_GOAL` fallback (e.g., 5).
//
// 4.  **Task Completion Cleanup/Review:**
// *   **Description:** When a root Goal thought becomes `DONE`, trigger a final review or summary action.
// *   **Pattern:** `Goal(?GoalContent)` where the thought's status becomes `DONE` and `metadata.rootId == id`. (Requires status change trigger mechanism or periodic check).
// *   **Action:** `LLMTool(generate, 'SUMMARIZE_TASK_COMPLETION', context(goalId:?SelfId, goalContent:?GoalContent))` *OR* `CoreTool(add_thought, 'LOG', 'Task ?SelfId completed.')`
// *   **Priority:** High (e.g., 100) - run immediately upon completion.
//
// ### Additional Tools
//
// 1.  **TimeTool:**
// *   **Name:** `TimeTool`
// *   **Description:** Provides time-related functions: `get_time()`, `wait(duration)`, `schedule(action, time)`, `periodic_tick(interval)`.
// *   **Execute:** Returns current time, pauses execution (carefully, maybe creates a `WAITING` thought scheduled for later), creates future scheduled thoughts, or generates periodic `System(tick)` thoughts. This enables scheduling and checking for stalled thoughts.
//
// 2.  **FileSystemTool:**
// *   **Name:** `FileSystemTool`
// *   **Description:** Interacts with the local filesystem: `read_file(path)`, `write_file(path, content)`, `list_dir(path)`.
// *   **Execute:** Reads/writes files, allowing the system to ingest external documents or save structured output beyond the main state file. Use with caution regarding security.
//
// 3.  **WebSearchTool:**
// *   **Name:** `WebSearchTool`
// *   **Description:** Performs web searches: `search(query, num_results)`.
// *   **Execute:** Uses an external search API (e.g., SerpApi, Google Search API) to fetch search results, allowing the system to incorporate external, up-to-date information. Requires API keys and potentially LangChain integration wrappers.
//
// 4.  **ContextSummarizationTool:**
// *   **Name:** `ContextSummarizer`
// *   **Description:** Summarizes the history or relevant parts of a task: `summarize(rootId, max_tokens)`.
// *   **Execute:** Retrieves descendant thoughts for a `rootId`, potentially filters them (e.g., Facts, Outcomes), and uses the LLM to generate a concise summary. Useful for providing context to LLM prompts without exceeding limits.
//
// 5.  **RuleManagementTool:**
// *   **Name:** `RuleManager`
// *   **Description:** Allows programmatic manipulation of rules: `add_rule(pattern, action, priority?, description?)`, `delete_rule(ruleId)`, `modify_priority(ruleId, delta)`.
// *   **Execute:** Adds, deletes, or modifies rules in the `RuleStore`. Enables self-modification based on performance or user feedback (use with extreme caution).
//
// ### Additional Prompts (PromptRegistry)
//
// 1.  **`GENERATE_RECOVERY_STRATEGY`**:
// *   `"Thought {failedId} ('{failedContent}') failed with error: '{error}'. Suggest a concise alternative strategy or action to achieve the original goal (if discernible). Output ONLY the suggested action/strategy text."`
// 2.  **`STUCK_THOUGHT_PROMPT`**:
// *   `"Thought {thoughtId} has been in status '{status}' for {age}. What should be done? (Options: retry / mark_failed / investigate / ignore)"`
// 3.  **`SUMMARIZE_TASK_COMPLETION`**:
// *   `"Task goal '{goalContent}' (ID: {goalId}) is complete. Based on its associated thoughts (Outcomes, Facts, Logs), provide a brief summary of what was accomplished and any key findings."` (Requires ContextSummarizer tool or passing context).
// 4.  **`ASK_FOR_RULE_SUGGESTION`**:
// *   `"Processing pattern '{pattern}' led to failure '{error}' multiple times. Can you suggest a better rule (pattern -> action) to handle this situation more effectively?"` (Could be triggered by RuleManagementTool or analysis).
//
// These additions would enhance the system's ability to handle failures autonomously, manage tasks over time, interact with external information, and potentially learn or adapt its own rules, significantly boosting its overall capability. Remember to implement new tools carefully, especially those interacting with external systems or modifying core components like rules.

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

// --- Configuration ---
const DATA_DIR = path.join(os.homedir(), '.flowmind');
const STATE_FILE = path.join(DATA_DIR, 'flowmind_state.json');
const VECTOR_STORE_DIR = path.join(DATA_DIR, 'vector-store');
const OLLAMA_BASE_URL = process.env.OLLAMA_HOST || "http://localhost:11434";
const OLLAMA_MODEL = process.env.OLLAMA_MODEL || 'llamablit';
const OLLAMA_EMBEDDING_MODEL = process.env.OLLAMA_EMBED_MODEL || OLLAMA_MODEL;
const WORKER_INTERVAL = 2000;
const SAVE_DEBOUNCE = 5000;
const MAX_RETRIES = 2;
const DEFAULT_BELIEF_POS = 1.0;
const DEFAULT_BELIEF_NEG = 1.0;
const SHORT_ID_LEN = 6;
const WS_PORT = 8080;
const LOG_LEVEL = process.env.LOG_LEVEL || 'info';

// --- Types ---
enum Status { PENDING = 'PENDING', ACTIVE = 'ACTIVE', WAITING = 'WAITING', DONE = 'DONE', FAILED = 'FAILED' }

enum Type {
    INPUT = 'INPUT', GOAL = 'GOAL', STRATEGY = 'STRATEGY', OUTCOME = 'OUTCOME',
    QUERY = 'QUERY', USER_PROMPT = 'USER_PROMPT', SYSTEM = 'SYSTEM',
    FACT = 'FACT', LOG = 'LOG'
}

type TaskStatus = 'RUNNING' | 'PAUSED';
type Term = Atom | Variable | Struct | List;

interface Atom {
    kind: 'Atom';
    name: string;
}

interface Variable {
    kind: 'Variable';
    name: string;
}

interface Struct {
    kind: 'Struct';
    name: string;
    args: Term[];
}

interface List {
    kind: 'List';
    elements: Term[];
}

interface BeliefData {
    pos: number;
    neg: number;
}

interface Metadata {
    rootId?: string;
    parentId?: string;
    ruleId?: string;
    agentId?: string;
    created?: string;
    modified?: string;
    priority?: number;
    error?: string;
    promptId?: string;
    tags?: string[];
    feedback?: any[];
    embedded?: string;
    suggestions?: string[];
    retries?: number;
    waitingFor?: string;
    responseTo?: string;
    provenance?: string;
    taskStatus?: TaskStatus;

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
    metadata: { priority?: number; description?: string; provenance?: string; created?: string; };
}

interface MemoryEntry {
    id: string;
    embedding?: number[];
    content: string;
    metadata: { created: string; type: string; sourceId: string; };
    score?: number;
}

interface AppState {
    thoughts: Record<string, any>;
    rules: Record<string, any>;
}

interface ToolContext {
    thoughts: Thoughts;
    tools: Tools;
    rules: Rules;
    memory: Memory;
    llm: LLM;
    prompts: Prompts;
    engine: Engine;
}

interface Tool {
    name: string;
    description: string;

    execute(actionTerm: Struct, context: ToolContext, trigger: Thought): Promise<Term | null>;
}

interface SystemControl {
    startProcessing: () => void;
    pauseProcessing: () => void;
    stepProcessing: () => Promise<number>;
    shutdown: () => Promise<void>;
    getStatus: () => { isRunning: boolean };
}

type MatchResult = { rule: Rule; bindings: Map<string, Term> };
type ActionResult = { success: boolean; error?: string; finalStatus?: Status };
type FallbackResult = ActionResult;
type LogLevel = 'debug' | 'info' | 'warn' | 'error';

// --- Utilities ---
const generateId = (): string => uuidv4();
const shortId = (id: string | undefined): string => id ? id.substring(0, SHORT_ID_LEN) : '------';
const sleep = (ms: number): Promise<void> => new Promise(resolve => setTimeout(resolve, ms));
const LOG_LEVELS: Record<LogLevel, number> = {debug: 0, info: 1, warn: 2, error: 3};
const CURRENT_LOG_LEVEL = LOG_LEVELS[LOG_LEVEL as LogLevel] ?? LOG_LEVELS.info;

const log = (level: LogLevel, message: string, ...args: any[]) => {
    if (LOG_LEVELS[level] >= CURRENT_LOG_LEVEL) {
        const colorMap: Record<LogLevel, chalk.ChalkFunction> =
            {debug: chalk.gray, info: chalk.blue, warn: chalk.yellow, error: chalk.red};
        console.log(colorMap[level](`[${level.toUpperCase()}] ${message}`), ...args);
    }
};

function debounce<T extends (...args: any[]) => any>(func: T, wait: number): T & {
    cancel: () => void; flush: () => ReturnType<T> | undefined;
} {
    let timeout: NodeJS.Timeout | null = null;
    let lastArgs: Parameters<T> | null = null;
    let lastThis: any = null;
    let result: ReturnType<T> | undefined;
    const debounced = (...args: Parameters<T>): void => {
        lastArgs = args;
        lastThis = this;
        if (timeout) clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
    const later = () => {
        timeout = null;
        if (lastArgs) result = func.apply(lastThis, lastArgs);
        lastArgs = null;
        lastThis = null;
    };
    debounced.cancel = () => {
        if (timeout) clearTimeout(timeout);
        timeout = null;
        lastArgs = null;
        lastThis = null;
    };
    debounced.flush = () => {
        if (timeout) clearTimeout(timeout);
        later();
        return result;
    };
    return debounced as T & { cancel: () => void; flush: () => ReturnType<T> | undefined; };
}

const safeJsonParse = <T>(json: string | null | undefined, defaultValue: T): T => {
    if (!json) return defaultValue;
    try {
        return JSON.parse(json);
    } catch {
        return defaultValue;
    }
};

// --- Core Logic Classes ---

class Belief implements BeliefData {
    constructor(public pos: number = DEFAULT_BELIEF_POS, public neg: number = DEFAULT_BELIEF_NEG) {
    }

    score(): number {
        return (this.pos + 1) / (this.pos + this.neg + 2);
    }

    update(success: boolean): void {
        success ? this.pos++ : this.neg++;
    }

    toJSON(): BeliefData {
        return {pos: this.pos, neg: this.neg};
    }

    static fromJSON(data: BeliefData | Belief | undefined): Belief {
        if (!data) return new Belief();
        return data instanceof Belief ? data : new Belief(data.pos ?? DEFAULT_BELIEF_POS, data.neg ?? DEFAULT_BELIEF_NEG);
    }

    static DEFAULT = new Belief();
}

const Atom = (name: string): Atom => ({kind: 'Atom', name});
const Variable = (name: string): Variable => ({kind: 'Variable', name});
const Struct = (name: string, args: Term[]): Struct => ({kind: 'Struct', name, args});
const List = (elements: Term[]): List => ({kind: 'List', elements});

namespace Terms {
    export function format(term: Term | null | undefined, useColor: boolean = true): string {
        if (!term) return useColor ? chalk.grey('null') : 'null';
        const c = (color: chalk.ChalkFunction, text: string) => useColor ? color(text) : text;
        switch (term.kind) {
            case 'Atom':
                return c(chalk.green, term.name);
            case 'Variable':
                return c(chalk.cyan, `?${term.name}`);
            case 'Struct':
                return `${c(chalk.yellow, term.name)}(${term.args.map(t => format(t, useColor)).join(', ')})`;
            case 'List':
                return `[${term.elements.map(t => format(t, useColor)).join(', ')}]`;
            default:
                const exhaustiveCheck: never = term;
                return c(chalk.red, 'invalid_term');
        }
    }

    export function toString(term: Term): string {
        switch (term.kind) {
            case 'Atom':
                return term.name;
            case 'Variable':
                return `?${term.name}`;
            case 'Struct':
                return `${term.name}(${term.args.map(toString).join(',')})`;
            case 'List':
                return `[${term.elements.map(toString).join(',')}]`;
            default:
                const exhaustiveCheck: never = term;
                return '';
        }
    }

    export function fromString(input: string): Term {
        input = input.trim();
        if (input.startsWith('?') && input.length > 1 && !input.includes('(') && !input.includes('[') && !input.includes(',')) return Variable(input.substring(1));

        const parseNested = (str: string, open: string, close: string): Term[] => {
            const items: Term[] = [];
            let currentItem = '';
            let level = 0;
            for (let i = 0; i < str.length; i++) {
                const char = str[i];
                if (char === open) level++; else if (char === close) level--;
                else if (char === '(') level++; else if (char === ')') level--;
                else if (char === '[') level++; else if (char === ']') level--;

                if (char === ',' && level === 0) {
                    if (currentItem.trim()) items.push(fromString(currentItem.trim()));
                    currentItem = '';
                } else {
                    currentItem += char;
                }
            }
            if (currentItem.trim()) items.push(fromString(currentItem.trim()));
            return items;
        };

        const structMatch = input.match(/^([a-zA-Z0-9_]+)\((.*)\)$/);
        if (structMatch) return Struct(structMatch[1], parseNested(structMatch[2], '(', ')'));

        const listMatch = input.match(/^\[(.*)]$/);
        if (listMatch) return List(parseNested(listMatch[1], '[', ']'));

        if (/^[a-zA-Z0-9_:-]+$/.test(input) || input === '') return Atom(input);

        log('warn', `Interpreting potentially complex string as Atom: "${input}"`);
        return Atom(input);
    }

    export function unify(term1: Term, term2: Term, bindings: Map<string, Term> = new Map()): Map<string, Term> | null {
        const resolve = (term: Term, currentBindings: Map<string, Term>): Term => {
            while (term.kind === 'Variable' && currentBindings.has(term.name)) {
                const bound = currentBindings.get(term.name)!;
                if (bound === term) return term;
                term = bound;
            }
            return term;
        };

        const occursCheck = (varName: string, termToCheck: Term, currentBindings: Map<string, Term>): boolean => {
            const resolved = resolve(termToCheck, currentBindings);
            if (resolved.kind === 'Variable') return varName === resolved.name;
            if (resolved.kind === 'Struct') return resolved.args.some(arg => occursCheck(varName, arg, currentBindings));
            if (resolved.kind === 'List') return resolved.elements.some(el => occursCheck(varName, el, currentBindings));
            return false;
        };

        const stack: [Term, Term][] = [[term1, term2]];
        let currentBindings = new Map(bindings);

        while (stack.length > 0) {
            const [t1, t2] = stack.pop()!;
            const rt1 = resolve(t1, currentBindings);
            const rt2 = resolve(t2, currentBindings);

            if (rt1 === rt2) continue;

            if (rt1.kind === 'Variable') {
                if (occursCheck(rt1.name, rt2, currentBindings)) return null;
                currentBindings.set(rt1.name, rt2);
                continue;
            }
            if (rt2.kind === 'Variable') {
                if (occursCheck(rt2.name, rt1, currentBindings)) return null;
                currentBindings.set(rt2.name, rt1);
                continue;
            }
            if (rt1.kind !== rt2.kind) return null;

            switch (rt1.kind) {
                case 'Atom':
                    if (rt1.name !== (rt2 as Atom).name) return null;
                    break;
                case 'Struct': {
                    const s2 = rt2 as Struct;
                    if (rt1.name !== s2.name || rt1.args.length !== s2.args.length) return null;
                    for (let i = 0; i < rt1.args.length; i++) stack.push([rt1.args[i], s2.args[i]]);
                    break;
                }
                case 'List': {
                    const l2 = rt2 as List;
                    if (rt1.elements.length !== l2.elements.length) return null;
                    for (let i = 0; i < rt1.elements.length; i++) stack.push([rt1.elements[i], l2.elements[i]]);
                    break;
                }
            }
        }
        return currentBindings;
    }

    export function substitute(term: Term, bindings: Map<string, Term>): Term {
        const resolve = (t: Term): Term => {
            if (t.kind === 'Variable' && bindings.has(t.name)) {
                const bound = bindings.get(t.name)!;
                return resolve(bound);
            }
            return t;
        };
        const resolved = resolve(term);
        if (resolved.kind === 'Struct') return {...resolved, args: resolved.args.map(arg => substitute(arg, bindings))};
        if (resolved.kind === 'List') return {
            ...resolved,
            elements: resolved.elements.map(el => substitute(el, bindings))
        };
        return resolved;
    }

    export function jsonToTerm(data: any): Term | null {
        if (data === null || typeof data !== 'object') return Atom(String(data));
        if (Array.isArray(data)) {
            const elements = data.map(jsonToTerm).filter((t): t is Term => t !== null);
            return List(elements);
        }
        const {kind, name, args, elements} = data;
        switch (kind) {
            case 'Atom':
                return typeof name === 'string' ? Atom(name) : null;
            case 'Variable':
                return typeof name === 'string' ? Variable(name) : null;
            case 'Struct':
                if (typeof name === 'string' && Array.isArray(args)) {
                    const convertedArgs = args.map(jsonToTerm).filter((t): t is Term => t !== null);
                    return convertedArgs.length === args.length ? Struct(name, convertedArgs) : null;
                }
                return null;
            case 'List':
                if (Array.isArray(elements)) {
                    const convertedElements = elements.map(jsonToTerm).filter((t): t is Term => t !== null);
                    return convertedElements.length === elements.length ? List(convertedElements) : null;
                }
                return null;
            default:
                const objArgs = Object.entries(data)
                    .map(([k, v]) => {
                        const termValue = jsonToTerm(v);
                        return termValue ? Struct(k, [termValue]) : Atom(`${k}:${JSON.stringify(v)}`);
                    })
                    .filter((t): t is Term => t !== null);
                return Struct("json_object", objArgs);
        }
    }
}

abstract class BaseStore<T extends { id: string, metadata?: { created?: string, modified?: string } }> {
    protected items = new Map<string, T>();
    protected listeners: Set<() => void> = new Set();
    protected changedItems: Set<string> = new Set();
    protected deletedItems: Set<string> = new Set();

    add(item: T): void {
        this.update(item, true);
    }

    get(id: string): T | undefined {
        return this.items.get(id);
    }

    getAll(): T[] {
        return Array.from(this.items.values());
    }

    count(): number {
        return this.items.size;
    }

    findItemByPrefix(prefix: string): T | undefined {
        if (!prefix || prefix.length < 3) return undefined;
        if (this.items.has(prefix)) return this.items.get(prefix);
        const matching = this.getAll().filter(item => item.id.startsWith(prefix));
        return matching.length === 1 ? matching[0] : undefined;
    }

    addChangeListener(listener: () => void): void {
        this.listeners.add(listener);
    }

    removeChangeListener(listener: () => void): void {
        this.listeners.delete(listener);
    }

    update(item: T, isNew: boolean = false): void {
        const existing = this.items.get(item.id);
        const now = new Date().toISOString();
        const newItem = {
            ...item,
            metadata: {
                ...(existing?.metadata ?? {}),
                ...(item.metadata ?? {}),
                created: existing?.metadata?.created ?? now,
                modified: now
            }
        };
        this.items.set(item.id, newItem);
        this.changedItems.add(item.id);
        this.deletedItems.delete(item.id);
        this.notifyChange();
    }

    delete(id: string): boolean {
        if (!this.items.has(id)) return false;
        this.items.delete(id);
        this.changedItems.delete(id);
        this.deletedItems.add(id);
        this.notifyChange();
        return true;
    }

    getDelta(): { changed: T[], deleted: string[] } {
        const changed = Array.from(this.changedItems).map(id => this.items.get(id)).filter((item): item is T => !!item);
        const deleted = Array.from(this.deletedItems);
        this.changedItems.clear();
        this.deletedItems.clear();
        return {changed, deleted};
    }

    protected notifyChange(): void {
        this.listeners.forEach(listener => listener());
    }

    abstract toJSON(): Record<string, any>;

    abstract loadJSON(data: Record<string, any>): void;

    protected baseLoadJSON(data: Record<string, any>, itemFactory: (id: string, itemData: any) => T | null): void {
        this.items.clear();
        Object.entries(data).forEach(([id, itemData]) => {
            try {
                const item = itemFactory(id, itemData);
                if (item) this.items.set(id, item);
                else log('warn', `Skipped loading invalid item ${id}`);
            } catch (e: any) {
                log('error', `Failed to load item ${id}: ${e.message}`);
            }
        });
        this.changedItems.clear();
        this.deletedItems.clear();
    }
}

class Thoughts extends BaseStore<Thought> {
    getPending(): Thought[] {
        return this.getAll().filter(t => t.status === Status.PENDING);
    }

    findThought(idPrefix: string): Thought | undefined {
        return this.findItemByPrefix(idPrefix);
    }

    findPendingPrompt(promptId: string): Thought | undefined {
        return this.getAll().find(t => t.type === Type.USER_PROMPT && t.status === Status.PENDING && t.metadata.promptId === promptId);
    }

    findWaitingThought(promptId: string): Thought | undefined {
        return this.getAll().find(t => t.status === Status.WAITING && t.metadata.waitingFor === promptId);
    }

    getRootThoughts(): Thought[] {
        return this.getAll().filter(t => !t.metadata.rootId || t.metadata.rootId === t.id);
    }

    getDescendants(rootId: string): Thought[] {
        return this.getAll().filter(t => t.metadata.rootId === rootId && t.id !== rootId);
    }

    toJSON(): Record<string, any> {
        const obj: Record<string, any> = {};
        this.items.forEach((thought, id) => obj[id] = {...thought, belief: thought.belief.toJSON()});
        return obj;
    }

    loadJSON(data: Record<string, any>): void {
        this.baseLoadJSON(data, (id, thoughtData) => {
            const {belief, content, metadata, ...rest} = thoughtData;
            const status = Object.values(Status).includes(rest.status) ? rest.status : Status.PENDING;
            const finalStatus = status === Status.ACTIVE ? Status.PENDING : status; // Reset ACTIVE on load
            const finalMetadata = metadata || {};
            const contentTerm = Terms.jsonToTerm(content);
            if (!contentTerm) {
                log('error', `Failed to parse content for thought ${id}, skipping.`);
                return null;
            }
            if (!finalMetadata.rootId || finalMetadata.rootId === id) {
                finalMetadata.taskStatus = ['RUNNING', 'PAUSED'].includes(finalMetadata.taskStatus) ? finalMetadata.taskStatus : 'RUNNING';
            }
            return {
                ...rest,
                id,
                belief: Belief.fromJSON(belief),
                content: contentTerm,
                status: finalStatus,
                metadata: finalMetadata
            };
        });
        log('info', `Loaded ${this.count()} thoughts. Reset ACTIVE thoughts to PENDING.`);
    }
}

class Rules extends BaseStore<Rule> {
    findRule(idPrefix: string): Rule | undefined {
        return this.findItemByPrefix(idPrefix);
    }

    toJSON(): Record<string, any> {
        const obj: Record<string, any> = {};
        this.items.forEach((rule, id) => obj[id] = {...rule, belief: rule.belief.toJSON()});
        return obj;
    }

    loadJSON(data: Record<string, any>): void {
        this.baseLoadJSON(data, (id, ruleData) => {
            const {belief, pattern, action, ...rest} = ruleData;
            const patternTerm = Terms.jsonToTerm(pattern);
            const actionTerm = Terms.jsonToTerm(action);
            if (!patternTerm || !actionTerm) {
                log('error', `Failed to parse pattern/action for rule ${id}, skipping.`);
                return null;
            }
            return {
                ...rest,
                id,
                belief: Belief.fromJSON(belief),
                pattern: patternTerm,
                action: actionTerm
            };
        });
        log('info', `Loaded ${this.count()} rules.`);
    }
}

class Memory {
    private vectorStore: FaissStore | null = null;
    public isReady = false;

    constructor(private embeddings: Embeddings, private storePath: string) {
    }

    async initialize(): Promise<void> {
        if (this.isReady) return;
        try {
            await fs.access(this.storePath);
            this.vectorStore = await FaissStore.load(this.storePath, this.embeddings);
            this.isReady = true;
            log('info', `Vector store loaded from ${this.storePath}`);
        } catch (error: any) {
            if (error.code === 'ENOENT') {
                log('warn', `Vector store not found at ${this.storePath}, initializing.`);
                try {
                    await fs.mkdir(path.dirname(this.storePath), {recursive: true});
                    const dummyDoc = new Document({
                        pageContent: "Initial sentinel document",
                        metadata: {
                            sourceId: "init",
                            type: "SYSTEM",
                            created: new Date().toISOString(),
                            id: "init_doc",
                            _docId: generateId()
                        }
                    });
                    this.vectorStore = await FaissStore.fromDocuments([dummyDoc], this.embeddings);
                    await this.save();
                    this.isReady = true;
                    log('info', `New vector store created at ${this.storePath}`);
                } catch (initError: any) {
                    log('error', 'Failed to initialize new vector store:', initError.message);
                }
            } else {
                log('error', 'Failed to load vector store:', error.message);
            }
        }
    }

    async add(entry: Omit<MemoryEntry, 'embedding' | 'score'>): Promise<void> {
        if (!this.vectorStore || !this.isReady) {
            log('warn', 'MemoryStore not ready, cannot add entry.');
            return;
        }
        const docMetadata = {...entry.metadata, id: entry.id, _docId: generateId()}; // Ensure unique internal ID
        const doc = new Document({pageContent: entry.content, metadata: docMetadata});
        try {
            await this.vectorStore.addDocuments([doc]);
            await this.save();
            log('debug', `Added memory entry from source ${entry.metadata.sourceId}`);
        } catch (error: any) {
            log('error', 'Failed to add document to vector store:', error.message);
        }
    }

    async search(query: string, k: number = 5): Promise<MemoryEntry[]> {
        if (!this.vectorStore || !this.isReady) {
            log('warn', 'MemoryStore not ready, cannot search.');
            return [];
        }
        try {
            const results = await this.vectorStore.similaritySearchWithScore(query, k);
            log('debug', `Memory search for "${query.substring(0, 30)}..." returned ${results.length} results.`);
            return results
                .filter(([doc]) => doc.metadata.sourceId !== 'init')
                .map(([doc, score]): MemoryEntry => ({
                    id: doc.metadata.id ?? generateId(),
                    content: doc.pageContent,
                    metadata: {
                        created: doc.metadata.created ?? new Date().toISOString(),
                        type: doc.metadata.type ?? 'UNKNOWN',
                        sourceId: doc.metadata.sourceId ?? 'UNKNOWN'
                    },
                    score: score,
                    embedding: []
                }));
        } catch (error: any) {
            log('error', 'Failed to search vector store:', error.message);
            return [];
        }
    }

    async save(): Promise<void> {
        if (this.vectorStore && this.isReady) {
            try {
                await this.vectorStore.save(this.storePath);
            } catch (error: any) {
                log('error', 'Failed to save vector store:', error.message);
            }
        }
    }
}

class LLM {
    llm: BaseChatModel;
    embeddings: Embeddings;

    constructor() {
        this.llm = new ChatOllama({baseUrl: OLLAMA_BASE_URL, model: OLLAMA_MODEL, temperature: 0.7});
        this.embeddings = new OllamaEmbeddings({model: OLLAMA_EMBEDDING_MODEL, baseUrl: OLLAMA_BASE_URL});
        log('info', `LLM Service: Model=${OLLAMA_MODEL}, Embeddings=${OLLAMA_EMBEDDING_MODEL}, URL=${OLLAMA_BASE_URL}`);
    }

    async generate(prompt: string): Promise<string> {
        log('debug', `LLM Prompt: "${prompt.substring(0, 100)}..."`);
        try {
            const response = await this.llm.pipe(new StringOutputParser()).invoke([new HumanMessage(prompt)]);
            const trimmedResponse = response.trim();
            log('debug', `LLM Response: "${trimmedResponse.substring(0, 100)}..."`);
            return trimmedResponse;
        } catch (error: any) {
            log('error', `LLM generation failed: ${error.message}`);
            return `Error: LLM generation failed. ${error.message}`;
        }
    }

    async embed(text: string): Promise<number[]> {
        log('debug', `Embedding text: "${text.substring(0, 50)}..."`);
        try {
            return await this.embeddings.embedQuery(text);
        } catch (error: any) {
            log('error', `Embedding failed: ${error.message}`);
            return [];
        }
    }
}

type PromptKey =
    'GENERATE_GOAL'
    | 'GENERATE_STRATEGY'
    | 'GENERATE_OUTCOME'
    | 'SUGGEST_GOAL'
    | 'FALLBACK_ASK_USER'
    | 'CONFIRM_GOAL'
    | 'CLARIFY_INPUT'
    | 'CONFIRM_STRATEGY'
    | 'NEXT_TASK_PROMPT'
    | 'HANDLE_FAILURE';

class Prompts {
    private prompts: Record<PromptKey, string> = {
        GENERATE_GOAL: 'Input: "{input}". Define a specific, actionable GOAL based on this input. Output ONLY the goal text.',
        GENERATE_STRATEGY: 'Goal: "{goal}". Outline 1-3 concrete STRATEGY steps to achieve this goal. Output each step on a new line, starting with "- ".',
        GENERATE_OUTCOME: 'Strategy step "{strategy}" was attempted. Describe a likely concise OUTCOME. Output ONLY the outcome text.',
        SUGGEST_GOAL: 'Based on the current context "{context}"{memoryContext}\n\nSuggest ONE concise, actionable next goal or task. Output only the suggested goal text.',
        FALLBACK_ASK_USER: 'No rule found for thought {thoughtId} ({thoughtType}: "{thoughtContent}"). How should I proceed with this?',
        CONFIRM_GOAL: 'Goal generated: "{goal}". Is this correct, or should it be refined? Please respond.',
        CLARIFY_INPUT: 'The input "{input}" seems brief. Could you please provide more details or context for a better goal?',
        CONFIRM_STRATEGY: 'Proposed strategy step: "{strategy}". Does this seem like a good step towards the goal? (yes/no/suggest alternative)',
        NEXT_TASK_PROMPT: 'Task "{taskContent}" ({taskId}) is complete. What would you like to work on next?',
        HANDLE_FAILURE: 'Thought {failedThoughtId} ("{content}") failed with error: "{error}". How should I proceed? (Options: retry / abandon / edit_goal / provide_new_strategy / manual_fix)',
    };

    format(key: PromptKey, context: Record<string, string>): string {
        let template = this.prompts[key] ?? `Error: Prompt template "${key}" not found. Context: ${JSON.stringify(context)}`;
        Object.entries(context).forEach(([k, v]) => {
            template = template.replace(`{${k}}`, v);
        });
        template = template.replace(/\{[^}]+\}/g, ''); // Remove unused placeholders
        return template;
    }
}

// --- Tools ---
abstract class BaseTool implements Tool {
    abstract name: string;
    abstract description: string;

    abstract execute(actionTerm: Struct, context: ToolContext, trigger: Thought): Promise<Term | null>;

    protected createResultAtom(success: boolean, code: string, message?: string): Atom {
        const prefix = success ? 'ok' : 'error';
        const fullMessage = `${prefix}:${this.name}_${code}${message ? `:${message.substring(0, 50)}` : ''}`;
        return Atom(fullMessage);
    }
}

class LLMTool extends BaseTool {
    name = "LLMTool";
    description = "Interacts with the LLM: generate(prompt_key_atom, context_term), embed(text_term).";

    async execute(action: Struct, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (!operation) return this.createResultAtom(false, "invalid_params", "Missing operation atom");
        switch (operation) {
            case 'generate':
                return this.generate(action, context, trigger);
            case 'embed':
                return this.embed(action, context);
            default:
                return this.createResultAtom(false, "unsupported_operation", operation);
        }
    }

    private async embed(action: Struct, context: ToolContext) {
        const inputTerm = action.args[1];
        if (!inputTerm) return this.createResultAtom(false, "invalid_params", "Missing text term for embed");
        const inputText = Terms.toString(inputTerm);
        const embedding = await context.llm.embed(inputText);
        return this.createResultAtom(embedding.length > 0, embedding.length > 0 ? "embedded" : "embedding_failed");
    }

    private async generate(action: Struct, context: ToolContext, trigger: Thought) {
        const promptKeyAtom = action.args[1];
        const contextTerm = action.args[2];
        if (promptKeyAtom?.kind !== 'Atom') return this.createResultAtom(false, "invalid_params", "Missing prompt key atom");
        if (contextTerm && contextTerm.kind !== 'Struct') return this.createResultAtom(false, "invalid_params", "Context must be a Struct");

        const promptKey = promptKeyAtom.name as PromptKey;
        const promptContext: Record<string, string> = {};
        if (contextTerm?.kind === 'Struct') {
            contextTerm.args.forEach(arg => {
                if (arg.kind === 'Atom' && arg.name.includes(':')) {
                    const [k, ...vParts] = arg.name.split(':');
                    promptContext[k] = vParts.join(':'); // Handle ':' in value
                } else if (arg.kind === 'Struct' && arg.args.length === 1 && arg.args[0].kind === 'Variable') {
                    // Resolve variable like goal:?GoalContent
                    const variableName = arg.args[0].name;
                    // Need to find the variable in the trigger context or parent? This is tricky.
                    // For now, let's try resolving against the trigger's content if it matches.
                    const bindings = Terms.unify(Variable(variableName), trigger.content);
                    if (bindings && bindings.has(variableName)) {
                        promptContext[arg.name] = Terms.toString(bindings.get(variableName)!);
                    } else {
                        promptContext[arg.name] = `?${variableName}`; // Keep unresolved
                    }
                }
            });
        }

        const prompt = context.prompts.format(promptKey, promptContext);
        const response = await context.llm.generate(prompt);
        if (response.startsWith('Error:')) return this.createResultAtom(false, "generation_failed", response);

        // Try to parse as JSON term first (for structured output)
        try {
            const termFromJson = Terms.jsonToTerm(JSON.parse(response));
            if (termFromJson) return termFromJson;
        } catch { /* Ignore JSON parse error */
        }

        // Otherwise, return as Atom
        return Atom(response);
    }
}

class MemoryTool extends BaseTool {
    name = "MemoryTool";
    description = "Manages vector memory: add(content_term), search(query_term, k?).";

    async execute(action: Struct, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (!operation) return this.createResultAtom(false, "missing_operation");
        switch (operation) {
            case 'add': {
                const contentTerm = action.args[1];
                if (!contentTerm) return this.createResultAtom(false, "missing_add_content");
                await context.memory.add({
                    id: generateId(),
                    content: Terms.toString(contentTerm),
                    metadata: {created: new Date().toISOString(), type: trigger.type, sourceId: trigger.id}
                });
                trigger.metadata.embedded = new Date().toISOString();
                context.thoughts.update(trigger);
                return this.createResultAtom(true, "memory_added", shortId(trigger.id));
            }
            case 'search': {
                const queryTerm = action.args[1];
                const kTerm = action.args[2];
                if (!queryTerm) return this.createResultAtom(false, "missing_search_query");
                const queryStr = Terms.toString(queryTerm);
                const k = (kTerm?.kind === 'Atom' && /^\d+$/.test(kTerm.name)) ? parseInt(kTerm.name, 10) : 3;
                const results = await context.memory.search(queryStr, k);
                return List(results.map(r => Atom(r.content)));
            }
            default:
                return this.createResultAtom(false, "unsupported_operation", operation);
        }
    }
}

class UserInteractionTool extends BaseTool {
    name = "UserInteractionTool";
    description = "Requests input from the user: prompt(prompt_text_term | prompt_key_atom, context_term?).";

    async execute(action: Struct, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (operation !== 'prompt') return this.createResultAtom(false, "invalid_params", "Expected prompt operation");
        const promptSource = action.args[1];
        const promptContextTerm = action.args[2];
        if (!promptSource) return this.createResultAtom(false, "invalid_params", "Missing prompt source");

        let promptText: string;
        if (promptSource.kind === 'Atom') {
            const promptKey = promptSource.name as PromptKey;
            const promptContext: Record<string, string> = {};
            if (promptContextTerm?.kind === 'Struct') {
                promptContextTerm.args.forEach(arg => {
                    if (arg.kind === 'Atom' && arg.name.includes(':')) {
                        const [k, ...vParts] = arg.name.split(':');
                        const value = vParts.join(':');
                        // Attempt to resolve variables in the value part
                        if (value.startsWith('?')) {
                            const varName = value.substring(1);
                            const termToMatch = Variable(varName);
                            const ruleBindings = trigger.metadata.ruleId ? context.rules.get(trigger.metadata.ruleId)?.pattern : undefined;
                            // Try unifying with trigger content directly first
                            let bindings = Terms.unify(termToMatch, trigger.content);
                            if (bindings && bindings.has(varName)) {
                                promptContext[k] = Terms.toString(bindings.get(varName)!);
                            } else {
                                // If rule available, try unifying with rule pattern against trigger content
                                if (ruleBindings && trigger.metadata.ruleId) {
                                    const ruleMatch = RuleMatcher.match(trigger, [context.rules.get(trigger.metadata.ruleId)!]);
                                    if (ruleMatch && ruleMatch.bindings.has(varName)) {
                                        promptContext[k] = Terms.toString(ruleMatch.bindings.get(varName)!);
                                    } else {
                                        promptContext[k] = value; // Keep as variable if unresolved
                                    }
                                } else {
                                    promptContext[k] = value; // Keep as variable if unresolved
                                }
                            }
                        } else {
                            promptContext[k] = value;
                        }
                    }
                });
            }
            promptText = context.prompts.format(promptKey, promptContext);
        } else {
            promptText = Terms.toString(promptSource);
        }

        const promptId = generateId();
        context.engine.addThought({
            id: promptId,
            type: Type.USER_PROMPT,
            content: Atom(promptText),
            belief: Belief.DEFAULT,
            status: Status.PENDING,
            metadata: {
                rootId: trigger.metadata.rootId ?? trigger.id,
                parentId: trigger.id,
                created: new Date().toISOString(),
                promptId: promptId,
                provenance: this.name
            }
        });
        trigger.status = Status.WAITING;
        trigger.metadata.waitingFor = promptId;
        context.thoughts.update(trigger);
        log('info', `User prompt requested (${shortId(promptId)}) for thought ${shortId(trigger.id)}.`);
        return this.createResultAtom(true, "prompt_requested", promptId);
    }
}

class GoalProposalTool extends BaseTool {
    name = "GoalProposalTool";
    description = "Suggests new goals based on context: suggest(context_term?).";

    async execute(action: Struct, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (operation !== 'suggest') return this.createResultAtom(false, "invalid_params");
        const contextTerm = action.args[1] ?? trigger.content;
        const contextStr = Terms.toString(contextTerm);
        const memoryResults = await context.memory.search(`Relevant past goals or outcomes related to: ${contextStr}`, 3);
        const memoryContext = memoryResults.length > 0 ? `\nRelated past activities:\n - ${memoryResults.map(r => r.content).join("\n - ")}` : "";
        const prompt = context.prompts.format('SUGGEST_GOAL', {context: contextStr, memoryContext: memoryContext});
        const suggestionText = await context.llm.generate(prompt);
        if (!suggestionText || suggestionText.startsWith('Error:')) return this.createResultAtom(false, "llm_failed", suggestionText);
        const suggestionThought: Thought = {
            id: generateId(),
            type: Type.GOAL,
            content: Atom(suggestionText),
            belief: new Belief(1, 0),
            status: Status.PENDING,
            metadata: {
                rootId: trigger.metadata.rootId ?? trigger.id,
                parentId: trigger.id,
                created: new Date().toISOString(),
                tags: ['suggested_goal'],
                provenance: this.name
            }
        };
        context.engine.addThought(suggestionThought);
        log('info', `Suggested goal ${shortId(suggestionThought.id)} based on ${shortId(trigger.id)}.`);
        return this.createResultAtom(true, "suggestion_created", shortId(suggestionThought.id));
    }
}

class CoreTool extends BaseTool {
    name = "CoreTool";
    description = "Manages internal state: set_status(target_id, status), add_thought(type, content, root?, parent?), delete_thought(target_id), set_content(target_id, content).";

    private isValidUuid(id: string): boolean {
        return /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(id);
    }

    private resolveTargetId(t: Term | undefined, fallbackId: string, context: ToolContext): string | null {
        if (t?.kind !== 'Atom') return null;
        if (t.name === 'self') return fallbackId;
        const target = context.thoughts.findThought(t.name) ?? context.rules.findRule(t.name);
        return target?.id ?? (this.isValidUuid(t.name) ? t.name : null);
    }

    async execute(action: Struct, context: ToolContext, trigger: Thought): Promise<Term | null> {
        const operation = action.args[0]?.kind === 'Atom' ? action.args[0].name : null;
        if (!operation) return this.createResultAtom(false, "missing_operation");

        const triggerId = trigger.id;
        switch (operation) {
            case 'set_status':
                return this.setStatus(action, triggerId, context);
            case 'add_thought':
                return this.addThought(action, triggerId, context, trigger);
            case 'delete_thought':
                return this.delThought(action, triggerId, context);
            case 'set_content':
                return this.setContent(action, triggerId, context);
            default:
                return this.createResultAtom(false, "unsupported_operation", operation);
        }
    }

    private setContent(action: Struct, triggerId: string, context: ToolContext) {
        const targetId = this.resolveTargetId(action.args[1], triggerId, context);
        const newContentTerm = action.args[2];
        if (!targetId || !newContentTerm) return this.createResultAtom(false, "invalid_set_content_params");
        const targetThought = context.thoughts.get(targetId);
        if (!targetThought) return this.createResultAtom(false, "target_not_found", shortId(targetId));
        targetThought.content = newContentTerm;
        context.thoughts.update(targetThought);
        log('debug', `Set content of ${shortId(targetThought.id)}`);
        return this.createResultAtom(true, "content_set", shortId(targetThought.id));
    }

    private delThought(action: Struct, triggerId: string, context: ToolContext) {
        const targetId = this.resolveTargetId(action.args[1], triggerId, context);
        if (!targetId) return this.createResultAtom(false, "invalid_delete_thought_params");
        const deleted = context.thoughts.delete(targetId);
        log('debug', `Attempted delete thought ${shortId(targetId)}: ${deleted}`);
        return this.createResultAtom(deleted, deleted ? "thought_deleted" : "delete_failed", shortId(targetId));
    }

    private addThought(action: Struct, triggerId: string, context: ToolContext, trigger: Thought) {
        const typeTerm = action.args[1];
        const contentTerm = action.args[2];
        const rootIdTerm = action.args[3];
        const parentIdTerm = action.args[4];
        if (typeTerm?.kind !== 'Atom' || !contentTerm) return this.createResultAtom(false, "invalid_add_thought_params");
        const type = typeTerm.name.toUpperCase() as Type;
        if (!Object.values(Type).includes(type)) return this.createResultAtom(false, "invalid_thought_type", typeTerm.name);

        const parentId = this.resolveTargetId(parentIdTerm, triggerId, context) ?? triggerId;
        const parentThought = context.thoughts.get(parentId);
        const defaultRootId = parentThought?.metadata.rootId ?? parentId;
        const rootId = this.resolveTargetId(rootIdTerm, defaultRootId, context) ?? defaultRootId;

        const newThought: Thought = {
            id: generateId(), type, content: contentTerm, belief: Belief.DEFAULT, status: Status.PENDING,
            metadata: {
                rootId, parentId,
                created: new Date().toISOString(),
                provenance: `${this.name} (rule ${shortId(trigger.metadata.ruleId)} on ${shortId(triggerId)})`
            }
        };
        if (newThought.id === newThought.metadata.rootId) {
            newThought.metadata.taskStatus = 'RUNNING'; // New root thoughts default to RUNNING
        }

        context.engine.addThought(newThought);
        log('debug', `Added thought ${shortId(newThought.id)} of type ${type}`);
        return this.createResultAtom(true, "thought_added", shortId(newThought.id));
    }

    private setStatus(action: Struct, triggerId: string, context: ToolContext) {
        const targetId = this.resolveTargetId(action.args[1], triggerId, context);
        const newStatusTerm = action.args[2];
        if (!targetId || newStatusTerm?.kind !== 'Atom') return this.createResultAtom(false, "invalid_set_status_params");
        const newStatus = newStatusTerm.name.toUpperCase() as Status;
        if (!Object.values(Status).includes(newStatus)) return this.createResultAtom(false, "invalid_status_value", newStatusTerm.name);
        const targetThought = context.thoughts.get(targetId);
        if (!targetThought) return this.createResultAtom(false, "target_not_found", shortId(targetId));
        targetThought.status = newStatus;
        context.thoughts.update(targetThought);
        log('debug', `Set status of ${shortId(targetThought.id)} to ${newStatus}`);
        return this.createResultAtom(true, "status_set", `${shortId(targetThought.id)}_to_${newStatus}`);
    }
}

class Tools {
    private tools = new Map<string, Tool>();

    register(tool: Tool): void {
        if (this.tools.has(tool.name)) log('warn', `Tool "${tool.name}" redefined.`);
        this.tools.set(tool.name, tool);
    }

    get(name: string): Tool | undefined {
        return this.tools.get(name);
    }

    list(): Tool[] {
        return Array.from(this.tools.values());
    }
}

// --- Engine Components ---
const RuleMatcher = {
    match(thought: Thought, rules: Rule[]): MatchResult | null {
        const applicableMatches = rules
            .map(rule => ({rule, bindings: Terms.unify(rule.pattern, thought.content)}))
            .filter((m): m is MatchResult => m.bindings !== null);

        const count = applicableMatches.length;
        if (count === 0) return null;
        if (count === 1) return applicableMatches[0];

        applicableMatches.sort((a, b) =>
            (b.rule.metadata.priority ?? 0) - (a.rule.metadata.priority ?? 0) ||
            b.rule.belief.score() - a.rule.belief.score()
        );
        log('debug', `Multiple rule matches for ${shortId(thought.id)} (${thought.type}). Best: ${shortId(applicableMatches[0].rule.id)}`);
        return applicableMatches[0];
    }
};

const ActionExecutor = {
    async execute(actionTerm: Term, context: ToolContext, trigger: Thought, rule?: Rule): Promise<ActionResult> {
        if (actionTerm.kind !== 'Struct') return {
            success: false,
            error: `Invalid action term kind: ${actionTerm.kind}`,
            finalStatus: Status.FAILED
        };
        const tool = context.tools.get(actionTerm.name);
        if (!tool) return {success: false, error: `Tool not found: ${actionTerm.name}`, finalStatus: Status.FAILED};
        log('debug', `Executing action ${Terms.format(actionTerm, false)} via tool ${tool.name} for thought ${shortId(trigger.id)}`);
        try {
            const resultTerm = await tool.execute(actionTerm, context, trigger);
            const currentThoughtState = context.thoughts.get(trigger.id);
            if (!currentThoughtState) {
                log('warn', `Thought ${shortId(trigger.id)} deleted during action by ${tool.name}.`);
                return {success: true};
            }
            const isWaiting = currentThoughtState.status === Status.WAITING;
            const isFailed = currentThoughtState.status === Status.FAILED;
            const returnedError = resultTerm?.kind === 'Atom' && resultTerm.name.startsWith('error:');

            if (returnedError) return {
                success: false,
                error: `Tool failed: ${resultTerm.name}`,
                finalStatus: Status.FAILED
            };
            if (isWaiting) return {success: true, finalStatus: Status.WAITING};
            if (isFailed) return {success: false, error: `Tool set status to FAILED`, finalStatus: Status.FAILED};

            return {success: true, finalStatus: Status.DONE};
        } catch (error: any) {
            log('error', `Tool exception ${tool.name} on ${shortId(trigger.id)}: ${error.message}`, error.stack);
            return {success: false, error: `Tool exception: ${error.message}`, finalStatus: Status.FAILED};
        }
    }
};

const FallbackHandler = {
    async handle(thought: Thought, context: ToolContext): Promise<FallbackResult> {
        log('debug', `Applying fallback handler for thought ${shortId(thought.id)} (${thought.type})`);
        let action: Term | null = null;
        let llmPromptKey: PromptKey | null = null;
        let llmContext: Record<string, string> = {};
        let targetType: Type | null = null;
        const contentStr = Terms.toString(thought.content).substring(0, 100);

        switch (thought.type) {
            case Type.INPUT:
                llmPromptKey = 'GENERATE_GOAL';
                llmContext = {input: contentStr};
                targetType = Type.GOAL;
                break;
            case Type.GOAL:
                llmPromptKey = 'GENERATE_STRATEGY';
                llmContext = {goal: contentStr};
                targetType = Type.STRATEGY;
                break;
            case Type.STRATEGY:
                llmPromptKey = 'GENERATE_OUTCOME';
                llmContext = {strategy: contentStr};
                targetType = Type.OUTCOME;
                break;
            case Type.OUTCOME:
            case Type.FACT:
                action = Struct("MemoryTool", [Atom("add"), thought.content]);
                break;
            default:
                const askUserContext = {
                    thoughtId: shortId(thought.id),
                    thoughtType: thought.type,
                    thoughtContent: contentStr
                };
                action = Struct("UserInteractionTool", [
                    Atom("prompt"), Atom("FALLBACK_ASK_USER"),
                    Struct("context", Object.entries(askUserContext).map(([k, v]) => Atom(`${k}:${v}`)))
                ]);
                break;
        }

        if (llmPromptKey && targetType) {
            const prompt = context.prompts.format(llmPromptKey, llmContext);
            const resultText = await context.llm.generate(prompt);
            if (resultText && !resultText.startsWith('Error:')) {
                resultText.split('\n').map(s => s.trim().replace(/^- /, '')).filter(s => s.length > 0).forEach(resText => {
                    context.engine.addThought({
                        id: generateId(),
                        type: targetType!,
                        content: Atom(resText),
                        belief: Belief.DEFAULT,
                        status: Status.PENDING,
                        metadata: {
                            rootId: thought.metadata.rootId ?? thought.id,
                            parentId: thought.id,
                            created: new Date().toISOString(),
                            provenance: 'llm_fallback'
                        }
                    });
                });
                return {success: true, finalStatus: Status.DONE};
            } else {
                return {success: false, error: `LLM fallback failed: ${resultText}`, finalStatus: Status.FAILED};
            }
        } else if (action) {
            return ActionExecutor.execute(action, context, thought);
        } else {
            return {success: false, error: "No rule match and no fallback action defined.", finalStatus: Status.FAILED};
        }
    }
};

// --- Engine ---
class Engine {
    private activeIds = new Set<string>();
    private context: ToolContext;

    constructor(
        private thoughts: Thoughts, private rules: Rules, private memory: Memory,
        private llm: LLM, private tools: Tools, private prompts: Prompts,
        private batchSize: number = 5, private maxConcurrent: number = 3
    ) {
        this.context = {
            thoughts: this.thoughts,
            rules: this.rules,
            memory: this.memory,
            llm: this.llm,
            tools: this.tools,
            prompts: this.prompts,
            engine: this
        };
    }

    addThought(thought: Thought): void {
        this.thoughts.add(thought);
    }

    private sampleThought(): Thought | null {
        const candidates = this.thoughts.getPending().filter(t => {
            if (this.activeIds.has(t.id)) return false;
            const rootId = t.metadata.rootId ?? t.id;
            const rootThought = this.thoughts.get(rootId);
            return !rootThought || rootThought.metadata.taskStatus !== 'PAUSED';
        });
        if (candidates.length === 0) return null;

        const weights = candidates.map(t => Math.max(0.01, t.metadata.priority ?? t.belief.score()));
        const totalWeight = weights.reduce((sum, w) => sum + w, 0);

        if (totalWeight <= 0) return candidates[Math.floor(Math.random() * candidates.length)];

        let random = Math.random() * totalWeight;
        for (let i = 0; i < candidates.length; i++) {
            random -= weights[i];
            if (random <= 0) return candidates[i];
        }
        return candidates[candidates.length - 1]; // Fallback
    }

    private updateThoughtStatus(thought: Thought, result: ActionResult | FallbackResult, rule?: Rule): void {
        const currentThought = this.thoughts.get(thought.id);
        if (!currentThought) return;

        currentThought.metadata.ruleId = rule?.id;
        currentThought.belief.update(result.success);

        if (result.success) {
            currentThought.status = result.finalStatus ?? Status.DONE;
            delete currentThought.metadata.error;
            delete currentThought.metadata.retries;
            if (currentThought.status !== Status.WAITING) delete currentThought.metadata.waitingFor;
        } else {
            const retries = (currentThought.metadata.retries ?? 0) + 1;
            currentThought.status = retries >= MAX_RETRIES ? Status.FAILED : Status.PENDING;
            currentThought.metadata.error = (result.error ?? "Unknown processing error").substring(0, 250);
            currentThought.metadata.retries = retries;
            log('warn', `Thought ${shortId(currentThought.id)} failed (Attempt ${retries}): ${currentThought.metadata.error}`);
            if (currentThought.status === Status.FAILED && currentThought.type !== Type.LOG) {
                // Log failure event for potential handling by rules
                this.addThought({
                    id: generateId(), type: Type.LOG,
                    content: Struct("failure", [
                        Struct("thoughtId", [Atom(currentThought.id)]),
                        Struct("content", [currentThought.content]),
                        Struct("error", [Atom(currentThought.metadata.error ?? "Unknown")])
                    ]),
                    belief: Belief.DEFAULT, status: Status.PENDING,
                    metadata: {
                        rootId: currentThought.metadata.rootId ?? currentThought.id, parentId: currentThought.id,
                        created: new Date().toISOString(), provenance: 'engine_failure_log'
                    }
                });
            }
        }
        this.thoughts.update(currentThought);
    }

    private async _applyRule(match: MatchResult, thought: Thought): Promise<ActionResult> {
        const boundAction = Terms.substitute(match.rule.action, match.bindings);
        const result = await ActionExecutor.execute(boundAction, this.context, thought, match.rule);
        match.rule.belief.update(result.success);
        this.rules.update(match.rule);
        return result;
    }

    private async _applyFallback(thought: Thought): Promise<FallbackResult> {
        return await FallbackHandler.handle(thought, this.context);
    }

    private async _processThought(thought: Thought): Promise<boolean> {
        try {
            log('info', `Processing ${thought.type} ${shortId(thought.id)}: ${Terms.format(thought.content, false).substring(0, 80)}`);
            thought.status = Status.ACTIVE;
            thought.metadata.agentId = 'worker';
            this.thoughts.update(thought);

            const match = RuleMatcher.match(thought, this.rules.getAll());
            let result: ActionResult | FallbackResult;
            let appliedRule: Rule | undefined = undefined;

            if (match) {
                appliedRule = match.rule;
                result = await this._applyRule(match, thought);
            } else {
                result = await this._applyFallback(thought);
            }

            this.updateThoughtStatus(thought, result, appliedRule);
            const finalStatus = this.thoughts.get(thought.id)?.status;
            return finalStatus !== Status.PENDING && finalStatus !== Status.ACTIVE; // Returns true if processing completed (not pending/active)

        } catch (error: any) {
            log('error', `Critical error processing ${shortId(thought.id)}: ${error.message}`, error.stack);
            this.updateThoughtStatus(thought, {
                success: false,
                error: `Unhandled exception: ${error.message}`,
                finalStatus: Status.FAILED
            });
            return true; // Completed (with error)
        } finally {
            this.activeIds.delete(thought.id);
            const finalThoughtState = this.thoughts.get(thought.id);
            if (finalThoughtState?.status === Status.ACTIVE) {
                log('warn', `Thought ${shortId(thought.id)} ended ACTIVE. Setting FAILED.`);
                this.updateThoughtStatus(finalThoughtState, {
                    success: false,
                    error: "Processing ended while ACTIVE.",
                    finalStatus: Status.FAILED
                });
            }
        }
    }

    async processBatch(): Promise<number> {
        const promises: Promise<boolean>[] = [];
        let acquiredCount = 0;
        while (this.activeIds.size < this.maxConcurrent && acquiredCount < this.batchSize) {
            const thought = this.sampleThought();
            if (!thought) break;
            this.activeIds.add(thought.id);
            promises.push(this._processThought(thought));
            acquiredCount++;
        }
        if (promises.length === 0) return 0;
        await Promise.all(promises);
        log('debug', `Processed batch of ${promises.length} thoughts.`);
        return promises.length;
    }

    async handlePromptResponse(promptId: string, responseText: string): Promise<boolean> {
        const promptThought = this.thoughts.findPendingPrompt(promptId);
        if (!promptThought) {
            log('error', `Pending prompt thought for ID ${shortId(promptId)} not found.`);
            return false;
        }

        const waitingThought = this.thoughts.findWaitingThought(promptId);
        if (!waitingThought) {
            log('warn', `No thought found waiting for prompt ${shortId(promptId)}. Marking prompt as done.`);
            promptThought.status = Status.DONE;
            promptThought.metadata.error = "No waiting thought found upon response.";
            this.thoughts.update(promptThought);
            return false;
        }

        this.addThought({
            id: generateId(),
            type: Type.INPUT,
            content: Atom(responseText),
            belief: new Belief(1, 0),
            status: Status.PENDING,
            metadata: {
                rootId: waitingThought.metadata.rootId ?? waitingThought.id,
                parentId: waitingThought.id,
                created: new Date().toISOString(),
                responseTo: promptId,
                tags: ['user_response'],
                provenance: 'user_input'
            }
        });

        promptThought.status = Status.DONE;
        this.thoughts.update(promptThought);

        waitingThought.status = Status.PENDING;
        delete waitingThought.metadata.waitingFor;
        waitingThought.belief.update(true); // User responding generally means the waiting was 'successful'
        this.thoughts.update(waitingThought);

        log('info', `Response for ${shortId(promptId)} received. Thought ${shortId(waitingThought.id)} reactivated.`);
        return true;
    }
}

// --- Persistence ---
const saveState = async (thoughts: Thoughts, rules: Rules, memory: Memory): Promise<void> => {
    try {
        await fs.mkdir(DATA_DIR, {recursive: true});
        const state: AppState = {thoughts: thoughts.toJSON(), rules: rules.toJSON()};
        await fs.writeFile(STATE_FILE, JSON.stringify(state, null, 2));
        await memory.save();
        log('debug', 'State saved successfully.');
    } catch (error: any) {
        log('error', 'Error saving state:', error.message);
    }
};
const debouncedSaveState = debounce(saveState, SAVE_DEBOUNCE);

const loadState = async (thoughts: Thoughts, rules: Rules, memory: Memory): Promise<void> => {
    try {
        await fs.mkdir(DATA_DIR, {recursive: true});
        await memory.initialize();
        if (!memory.isReady) log('error', "Memory store failed to initialize. State loading might be incomplete.");

        if (fsSync.existsSync(STATE_FILE)) {
            const data = await fs.readFile(STATE_FILE, 'utf-8');
            const state: AppState = safeJsonParse(data, {thoughts: {}, rules: {}});
            thoughts.loadJSON(state.thoughts);
            rules.loadJSON(state.rules);
        } else {
            log('warn', `State file ${STATE_FILE} not found. Starting fresh.`);
        }
    } catch (error: any) {
        log('error', 'Error loading state:', error.message);
        if (!memory.isReady) await memory.initialize(); // Attempt memory init even if file load fails
    }
};

// --- WebSocket API Server ---
class ApiServer {
    private wss: WebSocketServer | null = null;
    private clients = new Map<WebSocket, { ip: string }>();
    private commandHandlers = new Map<string, (payload: any, ws: WebSocket) => Promise<any | void>>();

    constructor(
        private port: number, private thoughts: Thoughts, private rules: Rules,
        private memory: Memory, private engine: Engine, private tools: Tools,
        private systemControl: SystemControl
    ) {
        this.registerCommandHandlers();
        this.thoughts.addChangeListener(() => this.broadcastChanges(this.thoughts, 'thoughts'));
        this.rules.addChangeListener(() => this.broadcastChanges(this.rules, 'rules'));
    }

    start(): void {
        this.wss = new WebSocketServer({port: this.port});
        this.wss.on('listening', () => log('info', `🚀 FlowMind API listening on ws://localhost:${this.port}`));
        this.wss.on('connection', (ws, req) => this.handleConnection(ws, req));
        this.wss.on('error', (error) => log('error', 'WebSocket Server Error:', error.message));
    }

    stop(): void {
        log('warn', 'Stopping API server...');
        this.wss?.clients.forEach(ws => ws.close());
        this.wss?.close();
        this.clients.clear();
    }

    private handleConnection(ws: WebSocket, req: any): void {
        const ip = req.socket.remoteAddress || 'unknown';
        log('info', `Client connected: ${ip}`);
        this.clients.set(ws, {ip});
        ws.on('message', (message) => this.handleMessage(ws, message as Buffer));
        ws.on('close', () => this.handleDisconnection(ws));
        ws.on('error', (error) => {
            log('error', `WebSocket error from ${ip}: ${error.message}`);
            this.handleDisconnection(ws); // Ensure cleanup on error
        });
        this.sendSnapshot(ws);
    }

    private handleDisconnection(ws: WebSocket): void {
        const clientInfo = this.clients.get(ws);
        log('info', `Client disconnected: ${clientInfo?.ip || 'unknown'}`);
        this.clients.delete(ws);
    }

    private async handleMessage(ws: WebSocket, message: Buffer): Promise<void> {
        let request: { command: string; payload?: any; requestId?: string } | null = null;
        try {
            request = JSON.parse(message.toString());
            if (typeof request?.command !== 'string') throw new Error("Invalid command format");
        } catch (e: any) {
            this.send(ws, {type: 'error', payload: `Invalid JSON: ${e.message}`, requestId: request?.requestId});
            return;
        }

        const handler = this.commandHandlers.get(request.command);
        if (!handler) {
            this.send(ws, {
                type: 'error',
                payload: `Unknown command: ${request.command}`,
                requestId: request.requestId
            });
            return;
        }

        try {
            log('debug', `Executing command "${request.command}" from client`);
            const result = await handler(request.payload ?? {}, ws);
            this.send(ws, {type: 'response', payload: result ?? null, success: true, requestId: request.requestId});

            // Trigger save for commands that likely modify state
            if (!['get_thoughts', 'get_rules', 'get_tasks', 'get_task_details', 'get_status', 'info', 'search', 'help', 'tools', 'ping'].includes(request.command)) {
                debouncedSaveState(this.thoughts, this.rules, this.memory);
            }
        } catch (error: any) {
            log('error', `Error executing command "${request.command}": ${error.message}`);
            this.send(ws, {
                type: 'response',
                payload: error.message || 'Command failed.',
                success: false,
                requestId: request.requestId
            });
        }
    }

    private send(ws: WebSocket, data: any): void {
        if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(data));
    }

    broadcast(data: any): void {
        const message = JSON.stringify(data);
        this.clients.forEach((_, ws) => {
            if (ws.readyState === WebSocket.OPEN) ws.send(message);
        });
    }

    private broadcastChanges(store: BaseStore<any>, type: string): void {
        const delta = store.getDelta();
        if (delta.changed.length > 0 || delta.deleted.length > 0) {
            this.broadcast({type: `${type}_delta`, payload: delta});
            log('debug', `Broadcasting ${type} delta: ${delta.changed.length} changed, ${delta.deleted.length} deleted`);
        }
    }

    private sendSnapshot(ws: WebSocket): void {
        this.send(ws, {
            type: 'snapshot',
            payload: {
                thoughts: this.thoughts.getAll().map(t => ({...t, belief: t.belief.toJSON()})),
                rules: this.rules.getAll().map(r => ({...r, belief: r.belief.toJSON()})),
                status: this.systemControl.getStatus()
            }
        });
        log('debug', `Sent snapshot to new client`);
    }

    private findThoughtOrThrow(idPrefix: string, requireRoot: boolean = false): Thought {
        const thought = this.thoughts.findThought(idPrefix);
        if (!thought) throw new Error(`Thought prefix "${idPrefix}" not found or ambiguous.`);
        if (requireRoot && (thought.metadata.rootId && thought.metadata.rootId !== thought.id)) {
            throw new Error(`Thought ${shortId(thought.id)} is not a root task.`);
        }
        return thought;
    }

    private findRuleOrThrow(idPrefix: string): Rule {
        const rule = this.rules.findRule(idPrefix);
        if (!rule) throw new Error(`Rule prefix "${idPrefix}" not found or ambiguous.`);
        return rule;
    }

    private registerCommandHandlers(): void {
        const handlers: { [key: string]: (payload: any, ws: WebSocket) => Promise<any | void> } = {
            add: async ({text}) => {
                if (typeof text !== 'string' || !text.trim()) throw new Error("Missing or empty 'text'");
                const newThought: Thought = {
                    id: generateId(),
                    type: Type.INPUT,
                    content: Terms.fromString(text),
                    belief: new Belief(1, 0),
                    status: Status.PENDING,
                    metadata: {
                        created: new Date().toISOString(),
                        tags: ['user_added'],
                        provenance: 'api',
                        taskStatus: 'RUNNING'
                    }
                };
                newThought.metadata.rootId = newThought.id;
                this.engine.addThought(newThought);
                return {id: newThought.id, message: `Added Task ${newThought.type}: ${shortId(newThought.id)}`};
            },
            respond: async ({promptId, text}) => {
                if (typeof promptId !== 'string' || !promptId.trim()) throw new Error("Missing 'promptId'");
                if (typeof text !== 'string' || !text.trim()) throw new Error("Missing or empty 'text'");
                const success = await this.engine.handlePromptResponse(promptId, text);
                if (!success) throw new Error(`Failed to process response for prompt ${shortId(promptId)}.`);
                return {message: `Response for ${shortId(promptId)} processed.`};
            },
            run: async () => {
                this.systemControl.startProcessing();
                return {message: "Processing started."};
            },
            pause: async () => {
                this.systemControl.pauseProcessing();
                return {message: "Processing paused."};
            },
            step: async () => {
                const count = await this.systemControl.stepProcessing();
                return {
                    processed: count > 0,
                    count,
                    message: count > 0 ? `Step processed ${count} thought(s).` : "Nothing to step."
                };
            },
            save: async () => {
                await debouncedSaveState.flush();
                await saveState(this.thoughts, this.rules, this.memory);
                return {message: "State saved."};
            },
            quit: async () => {
                await this.systemControl.shutdown();
            }, // No return needed, server will shut down
            get_status: async () => this.systemControl.getStatus(),
            info: async ({idPrefix}) => {
                if (typeof idPrefix !== 'string' || !idPrefix.trim()) throw new Error("Missing 'idPrefix'");
                const thought = this.thoughts.findThought(idPrefix);
                if (thought) return {type: 'thought', data: {...thought, belief: thought.belief.toJSON()}};
                const rule = this.rules.findRule(idPrefix);
                if (rule) return {type: 'rule', data: {...rule, belief: rule.belief.toJSON()}};
                throw new Error(`ID prefix "${idPrefix}" not found or ambiguous.`);
            },
            get_thoughts: async () => this.thoughts.getAll().map(t => ({...t, belief: t.belief.toJSON()})),
            get_rules: async () => this.rules.getAll().map(r => ({...r, belief: r.belief.toJSON()})),
            tools: async () => this.tools.list().map(t => ({name: t.name, description: t.description})),
            tag: async ({idPrefix, tag}) => {
                if (typeof idPrefix !== 'string' || !idPrefix.trim()) throw new Error("Missing 'idPrefix'");
                if (typeof tag !== 'string' || !tag.trim()) throw new Error("Missing or empty 'tag'");
                const thought = this.findThoughtOrThrow(idPrefix);
                thought.metadata.tags = [...new Set([...(thought.metadata.tags || []), tag.trim()])];
                this.thoughts.update(thought);
                return {id: thought.id, tags: thought.metadata.tags};
            },
            untag: async ({idPrefix, tag}) => {
                if (typeof idPrefix !== 'string' || !idPrefix.trim()) throw new Error("Missing 'idPrefix'");
                if (typeof tag !== 'string' || !tag.trim()) throw new Error("Missing or empty 'tag'");
                const thought = this.findThoughtOrThrow(idPrefix);
                if (!thought.metadata.tags) return {id: thought.id, tags: []}; // Nothing to untag
                thought.metadata.tags = thought.metadata.tags.filter(t => t !== tag.trim());
                this.thoughts.update(thought);
                return {id: thought.id, tags: thought.metadata.tags};
            },
            search: async ({query, k}) => {
                if (typeof query !== 'string' || !query.trim()) throw new Error("Missing or empty 'query'");
                return this.memory.search(query, typeof k === 'number' ? k : 5);
            },
            delete: async ({idPrefix}) => {
                if (typeof idPrefix !== 'string' || !idPrefix.trim()) throw new Error("Missing 'idPrefix'");
                const thought = this.thoughts.findThought(idPrefix);
                if (thought) {
                    if (this.thoughts.delete(thought.id)) return {
                        id: thought.id,
                        message: `Thought ${shortId(thought.id)} deleted.`
                    };
                    else throw new Error(`Failed to delete thought ${shortId(thought.id)}.`); // Should not happen if found
                }
                const rule = this.rules.findRule(idPrefix);
                if (rule) {
                    if (this.rules.delete(rule.id)) return {id: rule.id, message: `Rule ${shortId(rule.id)} deleted.`};
                    else throw new Error(`Failed to delete rule ${shortId(rule.id)}.`); // Should not happen if found
                }
                throw new Error(`Item with prefix "${idPrefix}" not found or ambiguous.`);
            },
            get_tasks: async () => {
                return this.thoughts.getRootThoughts().map(task => {
                    const descendants = this.thoughts.getDescendants(task.id);
                    const pendingPrompts = descendants.filter(t => t.type === Type.USER_PROMPT && t.status === Status.PENDING);
                    return {
                        id: task.id, type: task.type, content: Terms.toString(task.content),
                        status: task.status, taskStatus: task.metadata.taskStatus ?? 'RUNNING',
                        pendingPrompts: pendingPrompts.map(p => ({id: p.id, content: Terms.toString(p.content)}))
                    };
                });
            },
            get_task_details: async ({idPrefix}) => {
                if (typeof idPrefix !== 'string' || !idPrefix.trim()) throw new Error("Missing 'idPrefix'");
                const task = this.findThoughtOrThrow(idPrefix, true);
                const descendants = this.thoughts.getDescendants(task.id);
                const format = (thoughts: Thought[]) => thoughts.map(t => ({
                    id: t.id, type: t.type, content: Terms.toString(t.content),
                    status: t.status, created: t.metadata.created
                }));
                return {
                    task: {...task, belief: task.belief.toJSON()},
                    plan: format(descendants.filter(t => t.type === Type.STRATEGY)),
                    prompts: format(descendants.filter(t => t.type === Type.USER_PROMPT)),
                    facts: format(descendants.filter(t => t.type === Type.FACT)),
                    logs: format(descendants.filter(t => t.type === Type.LOG)),
                    other: format(descendants.filter(t => ![Type.STRATEGY, Type.USER_PROMPT, Type.FACT, Type.LOG].includes(t.type))),
                };
            },
            pause_task: async ({idPrefix}) => {
                if (typeof idPrefix !== 'string' || !idPrefix.trim()) throw new Error("Missing 'idPrefix'");
                const task = this.findThoughtOrThrow(idPrefix, true);
                task.metadata.taskStatus = 'PAUSED';
                this.thoughts.update(task);
                return {id: task.id, taskStatus: task.metadata.taskStatus, message: `Task ${shortId(task.id)} paused.`};
            },
            run_task: async ({idPrefix}) => {
                if (typeof idPrefix !== 'string' || !idPrefix.trim()) throw new Error("Missing 'idPrefix'");
                const task = this.findThoughtOrThrow(idPrefix, true);
                task.metadata.taskStatus = 'RUNNING';
                this.thoughts.update(task);
                return {
                    id: task.id,
                    taskStatus: task.metadata.taskStatus,
                    message: `Task ${shortId(task.id)} set to running.`
                };
            },
            help: async () => ({
                description: "Available commands. Use 'info <idPrefix>' for details.", commands: [
                    {cmd: "add <text>", desc: "Add new root INPUT thought (a new Task)."},
                    {cmd: "respond <promptId> <text>", desc: "Respond to a pending user prompt."},
                    {cmd: "run", desc: "Start global processing loop."}, {
                        cmd: "pause",
                        desc: "Pause global processing loop."
                    },
                    {cmd: "step", desc: "Process one batch of thoughts (respects task status)."},
                    {cmd: "save", desc: "Force immediate state save."}, {
                        cmd: "quit / exit",
                        desc: "Shutdown the server."
                    },
                    {cmd: "get_status", desc: "Get global processing loop status."},
                    {cmd: "info <idPrefix>", desc: "Get details of a thought/rule by ID prefix."},
                    {cmd: "get_thoughts", desc: "Get all thoughts."}, {cmd: "get_rules", desc: "Get all rules."},
                    {cmd: "get_tasks", desc: "List all root tasks and their status/pending prompts."},
                    {
                        cmd: "get_task_details <taskIdPrefix>",
                        desc: "Get detailed view of a task (plan, facts, logs, etc.)."
                    },
                    {cmd: "run_task <taskIdPrefix>", desc: "Set a specific task to RUNNING state."},
                    {cmd: "pause_task <taskIdPrefix>", desc: "Set a specific task to PAUSED state."},
                    {cmd: "tools", desc: "List available tools."}, {
                        cmd: "tag <idPrefix> <tag>",
                        desc: "Add tag to thought."
                    },
                    {cmd: "untag <idPrefix> <tag>", desc: "Remove tag from thought."},
                    {cmd: "search <query> [k=5]", desc: "Search vector memory."},
                    {cmd: "delete <idPrefix>", desc: "Delete a thought or rule by ID prefix."},
                    {cmd: "ping", desc: "Check server connection."}, {cmd: "help", desc: "Show this help message."},
                ]
            }),
            ping: async () => "pong",
        };
        for (const [command, handler] of Object.entries(handlers)) this.commandHandlers.set(command, handler.bind(this));
    }
}

// --- Main System Orchestrator ---
class System {
    private thoughts = new Thoughts();
    private rules = new Rules();
    private llm = new LLM();
    private memory = new Memory(this.llm.embeddings, VECTOR_STORE_DIR);
    private tools = new Tools();
    private prompts = new Prompts();
    private engine: Engine;
    private apiServer: ApiServer;
    private isRunning = false;
    private workerIntervalId: NodeJS.Timeout | null = null;
    private systemControl: SystemControl;

    constructor() {
        this.engine = new Engine(this.thoughts, this.rules, this.memory, this.llm, this.tools, this.prompts);
        this.addTools();
        this.systemControl = {
            startProcessing: this.startProcessingLoop.bind(this),
            pauseProcessing: this.pauseProcessingLoop.bind(this),
            stepProcessing: this.engine.processBatch.bind(this.engine),
            shutdown: this.shutdown.bind(this),
            getStatus: () => ({isRunning: this.isRunning})
        };
        this.apiServer = new ApiServer(WS_PORT, this.thoughts, this.rules, this.memory, this.engine, this.tools, this.systemControl);
    }

    private addTools(): void {
        [new LLMTool(), new MemoryTool(), new UserInteractionTool(), new GoalProposalTool(), new CoreTool()]
            .forEach(t => this.tools.register(t));
    }

    private addRules(): void {
        if (this.rules.count() > 0) {
            log('debug', "Skipping bootstrap rules: Rules already exist.");
            return;
        }
        log('info', "Bootstrapping default rules...");
        const rule = (desc: string, pattern: Term, action: Term, priority: number = 0): Rule => ({
            id: generateId(), pattern, action, belief: Belief.DEFAULT,
            metadata: {description: desc, provenance: 'bootstrap', created: new Date().toISOString(), priority}
        });
        const rulesToAdd = [
            rule("Outcome -> Add to Memory", Struct(Type.OUTCOME, [Variable("C")]), Struct("MemoryTool", [Atom("add"), Variable("C")]), 10),
            rule("Fact -> Add to Memory", Struct(Type.FACT, [Variable("C")]), Struct("MemoryTool", [Atom("add"), Variable("C")]), 10),
            rule("Outcome -> Log Outcome", Struct(Type.OUTCOME, [Variable("OutcomeContent")]), Struct("CoreTool", [Atom("add_thought"), Atom(Type.LOG), Struct("logged_outcome", [Variable("OutcomeContent")]), Atom("self"), Atom("self")]), -5),
            rule("Outcome -> Suggest Next Goal", Struct(Type.OUTCOME, [Variable("C")]), Struct("GoalProposalTool", [Atom("suggest"), Variable("C")]), -10),
            rule("Unclear Input -> Ask User", Struct(Type.INPUT, [Atom("unclear")]), Struct("UserInteractionTool", [Atom("prompt"), Atom("The input 'unclear' was received. Please clarify.")]), 20),
            rule("Input Command: Search Memory -> Use MemoryTool", Struct(Type.INPUT, [Struct("search", [Variable("Q")])]), Struct("MemoryTool", [Atom("search"), Variable("Q")]), 15),
            rule("Thought 'ok' -> Set Done", Atom("ok"), Struct("CoreTool", [Atom("set_status"), Atom("self"), Atom(Status.DONE)]), 1),
            rule("Confirm Generated Goal", Struct(Type.GOAL, [Variable("GoalContent")]),
                Struct("UserInteractionTool", [Atom("prompt"), Atom("CONFIRM_GOAL"), Struct("context", [Atom("goal:?GoalContent")])]), 5),
            rule("Clarify Brief Input", Struct(Type.INPUT, [Variable("InputAtom")]),
                Struct("UserInteractionTool", [Atom("prompt"), Atom("CLARIFY_INPUT"), Struct("context", [Atom("input:?InputAtom")])]), -5),
            rule("Confirm Strategy Step", Struct(Type.STRATEGY, [Variable("StrategyContent")]),
                Struct("UserInteractionTool", [Atom("prompt"), Atom("CONFIRM_STRATEGY"), Struct("context", [Atom("strategy:?StrategyContent")])]), 0),
            rule("Ask How to Handle Failure", Struct(Type.LOG, [Struct("failure", [Struct("thoughtId", [Variable("FailedId")]), Struct("content", [Variable("FailedContent")]), Struct("error", [Variable("ErrorMsg")])])]),
                Struct("UserInteractionTool", [Atom("prompt"), Atom("HANDLE_FAILURE"), Struct("context", [Atom("failedThoughtId:?FailedId"), Atom("content:?FailedContent"), Atom("error:?ErrorMsg")])]), 50),
            // Example rule to trigger next task prompt (needs a thought like TASK_DONE to be created by another rule/tool when a task finishes)
            // rule("Prompt After Task Completion", Struct("TASK_DONE", [Variable("TaskId"), Variable("TaskContent")]),
            //      Struct("UserInteractionTool", [Atom("prompt"), Atom("NEXT_TASK_PROMPT"), Struct("context", [Atom("taskId:?TaskId"), Atom("taskContent:?TaskContent")])]), 100),
        ];
        rulesToAdd.forEach(r => this.rules.add(r));
        log('info', `${this.rules.count()} default rules added.`);
    }

    async initialize(): Promise<void> {
        log('info', 'Initializing FlowMind System...');
        await loadState(this.thoughts, this.rules, this.memory);
        this.addRules(); // Add default rules if none were loaded
        this.apiServer.start();
        log('info', chalk.green('FlowMind Initialized.') + chalk.yellow(" Paused. Use API 'run' command or REPL 'run' to start processing."));
    }

    startProcessingLoop(): void {
        if (this.isRunning) {
            log('warn', "Processing already running.");
            return;
        }
        log('info', 'Starting processing loop...');
        this.isRunning = true;
        this.apiServer.broadcast({type: 'status_update', payload: this.systemControl.getStatus()});
        this.workerIntervalId = setInterval(async () => {
            if (!this.isRunning) return; // Check again inside interval
            try {
                if (await this.engine.processBatch() > 0) {
                    debouncedSaveState(this.thoughts, this.rules, this.memory);
                }
            } catch (error: any) {
                log('error', 'Error in processing loop:', error.message);
                this.pauseProcessingLoop(); // Pause on critical error
            }
        }, WORKER_INTERVAL);
    }

    pauseProcessingLoop(): void {
        if (!this.isRunning) {
            log('warn', "Processing already paused.");
            return;
        }
        log('warn', 'Pausing processing loop...');
        this.isRunning = false;
        this.apiServer.broadcast({type: 'status_update', payload: this.systemControl.getStatus()});
        if (this.workerIntervalId) {
            clearInterval(this.workerIntervalId);
            this.workerIntervalId = null;
        }
        debouncedSaveState.flush(); // Ensure last changes are saved
        saveState(this.thoughts, this.rules, this.memory); // Force save immediately on pause
    }

    async shutdown(): Promise<void> {
        log('info', '\nShutting down FlowMind System...');
        this.pauseProcessingLoop(); // Pause and save state
        this.apiServer.stop();
        debouncedSaveState.cancel(); // Cancel any pending debounced save
        await saveState(this.thoughts, this.rules, this.memory); // Final synchronous save
        log('info', chalk.green('FlowMind shutdown complete. Goodbye!'));
        await sleep(100); // Allow logs to flush
        process.exit(0);
    }
}

// --- REPL Client Utilities ---
const contentStr = (t: any): string => {
    if (!t?.content) return '';
    return typeof t.content === 'string' ? t.content : Terms.format(t.content, false);
};

const formatThought = (t: Thought | any, detail = false): string => {
    const thoughtContent = contentStr(t);
    const detailLimit = detail ? 150 : 50;
    return `${chalk.magenta(shortId(t.id))}|${chalk.yellow(t.type)}|${chalk.cyan(t.status ?? 'N/A')}|${thoughtContent.substring(0, detailLimit)}${thoughtContent.length > detailLimit ? '...' : ''}`;
};

const formatRule = (r: Rule | any): string =>
    `${chalk.blue(shortId(r.id))}|P${r.metadata?.priority ?? 0}|${Terms.format(r.pattern, false)} -> ${Terms.format(r.action, false)} (${(r.metadata?.description ?? '').substring(0, 40)}...)`;

const formatTaskSummary = (t: any): string => {
    const taskContent = contentStr(t);
    const prompts = t.pendingPrompts || [];
    const statusColor = t.taskStatus === 'RUNNING' ? chalk.green : chalk.yellow;
    return `${chalk.magenta(shortId(t.id))}|${chalk.yellow(t.type)}|${statusColor(t.taskStatus)}|${taskContent.substring(0, 60)}${taskContent.length > 60 ? '...' : ''}${prompts.length > 0 ? chalk.red(` [${prompts.length} Prompt(s)]`) : ''}`;
};

const formatTaskDetailItem = (t: any): string => {
    const taskContent = contentStr(t);
    return `  - ${chalk.magenta(shortId(t.id))}|${chalk.yellow(t.type)}|${chalk.cyan(t.status)}|${taskContent.substring(0, 100)}${taskContent.length > 100 ? '...' : ''}`;
};


// --- REPL Client ---
async function startReplClient(serverUrl: string) {
    console.log(chalk.cyan(`Connecting to FlowMind server at ${serverUrl}...`));
    const rl = readline.createInterface({
        input: process.stdin,
        output: process.stdout,
        prompt: chalk.blueBright('FM Client> ')
    });
    let ws: WebSocket | null = null;
    let requestCounter = 0;
    const pendingRequests = new Map<string, {
        resolve: (value: any) => void,
        reject: (reason?: any) => void,
        command: string
    }>();
    const reconnectDelay = 5000;
    let reconnectTimeout: NodeJS.Timeout | null = null;
    let shuttingDown = false;

    const connect = () => {
        if (shuttingDown || (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING))) return;
        if (reconnectTimeout) clearTimeout(reconnectTimeout);
        reconnectTimeout = null;

        console.log(chalk.yellow(`Attempting connection to ${serverUrl}...`));
        ws = new WebSocket(serverUrl);

        ws.on('open', () => {
            console.log(chalk.green(`Connected! Type 'help' for commands.`));
            rl.prompt();
        });

        ws.on('message', (data) => {
            const message = safeJsonParse(data.toString(), null);
            if (!message) {
                console.log(chalk.yellow('Received non-JSON message:', data.toString().substring(0, 100)));
                return;
            }
            const {type, payload, success, requestId, ...rest} = message;
            process.stdout.write('\n'); // Clear potential prompt overlap

            if (requestId && pendingRequests.has(requestId)) {
                const reqInfo = pendingRequests.get(requestId)!;
                pendingRequests.delete(requestId); // Remove *before* resolving/rejecting
                switch (type) {
                    case 'response':
                        success ? reqInfo.resolve(payload) : reqInfo.reject(new Error(payload || `Command '${reqInfo.command}' failed.`));
                        break;
                    case 'error':
                        reqInfo.reject(new Error(payload || `Server error for '${reqInfo.command}'.`));
                        break;
                    default:
                        reqInfo.reject(new Error(`Unexpected message type '${type}' for request '${reqInfo.command}'.`));
                        break;
                }
            } else { // Handle broadcast messages
                switch (type) {
                    case 'error':
                        console.error(chalk.red(`Server Error: ${payload}`));
                        break;
                    case 'snapshot':
                        console.log(chalk.magenta('--- Received State Snapshot ---'));
                        console.log(chalk.cyan(`Thoughts: ${payload.thoughts?.length ?? 0}, Rules: ${payload.rules?.length ?? 0}, Status: ${payload.status?.isRunning ? chalk.green('RUNNING') : chalk.yellow('PAUSED')}`));
                        break;
                    case 'thoughts_delta':
                        if (payload.changed.length > 0) console.log(chalk.green(`Thoughts Updated: ${payload.changed.map((t: Thought) => formatThought(t)).join(', ')}`));
                        if (payload.deleted.length > 0) console.log(chalk.red(`Thoughts Deleted: ${payload.deleted.map(shortId).join(', ')}`));
                        break;
                    case 'rules_delta':
                        if (payload.changed.length > 0) console.log(chalk.blue(`Rules Updated: ${payload.changed.map((r: Rule) => shortId(r.id)).join(', ')}`));
                        if (payload.deleted.length > 0) console.log(chalk.yellow(`Rules Deleted: ${payload.deleted.map(shortId).join(', ')}`));
                        break;
                    case 'status_update':
                        console.log(chalk.magenta(`System Status: ${payload.isRunning ? chalk.green('RUNNING') : chalk.yellow('PAUSED')}`));
                        break;
                    default:
                        console.log(chalk.gray(`Server Message [${type || 'unknown'}]:`), payload ?? rest);
                        break;
                }
                rl.prompt();
            }
        });

        ws.on('close', () => {
            if (shuttingDown) return;
            console.log(chalk.red('\nDisconnected. Attempting reconnect...'));
            ws = null;
            pendingRequests.forEach(p => p.reject(new Error("Disconnected")));
            pendingRequests.clear();
            if (!reconnectTimeout) reconnectTimeout = setTimeout(connect, reconnectDelay);
        });

        ws.on('error', (error) => {
            if (shuttingDown) return;
            console.error(chalk.red(`\nConnection Error: ${error.message}. Retrying...`));
            ws?.close();
            ws = null; // Ensure ws is nullified
            if (!reconnectTimeout) reconnectTimeout = setTimeout(connect, reconnectDelay);
        });
    };

    const sendCommand = (command: string, payload: any): Promise<any> => {
        return new Promise((resolve, reject) => {
            if (!ws || ws.readyState !== WebSocket.OPEN) {
                reject(new Error("Not connected."));
                return;
            }
            const requestId = `req-${requestCounter++}`;
            pendingRequests.set(requestId, {resolve, reject, command});
            ws.send(JSON.stringify({command, payload, requestId}));
            // Add timeout
            setTimeout(() => {
                if (pendingRequests.has(requestId)) {
                    pendingRequests.delete(requestId);
                    reject(new Error(`Request timeout for command '${command}'.`));
                    rl.prompt();
                }
            }, 15000); // 15 second timeout
        });
    };

    rl.on('line', async (line) => {
        const trimmed = line.trim();
        if (!trimmed) {
            rl.prompt();
            return;
        }
        if (trimmed === 'quit' || trimmed === 'exit') {
            rl.close();
            return;
        }

        const parts = trimmed.match(/(?:[^\s"]+|"[^"]*")+/g) ?? [];
        const command = parts[0]?.toLowerCase();
        const args = parts.slice(1).map(arg => arg.startsWith('"') && arg.endsWith('"') ? arg.slice(1, -1) : arg);

        if (!command) {
            rl.prompt();
            return;
        }

        let payload: any = {};
        let valid = true;
        try {
            switch (command) {
                case 'add':
                    payload = {text: args.join(' ')};
                    valid = args.length >= 1;
                    break;
                case 'respond':
                    payload = {promptId: args[0], text: args.slice(1).join(' ')};
                    valid = args.length >= 2;
                    break;
                case 'info':
                case 'delete':
                case 'get_task_details':
                case 'pause_task':
                case 'run_task':
                    payload = {idPrefix: args[0]};
                    valid = args.length === 1;
                    break;
                case 'tag':
                case 'untag':
                    payload = {idPrefix: args[0], tag: args[1]};
                    valid = args.length === 2;
                    break;
                case 'search':
                    payload = {query: args.join(' '), k: 5};
                    valid = args.length >= 1;
                    break; // Simple parsing for k
                case 'run':
                case 'pause':
                case 'step':
                case 'save':
                case 'get_status':
                case 'get_thoughts':
                case 'get_rules':
                case 'get_tasks':
                case 'tools':
                case 'help':
                case 'ping':
                    payload = {};
                    valid = args.length === 0;
                    break;
                default:
                    console.log(chalk.yellow(`Unknown command: ${command}. Type 'help'.`));
                    valid = false;
                    break;
            }
            if (!valid) {
                console.log(chalk.yellow(`Invalid arguments for '${command}'. Type 'help'.`));
                rl.prompt();
                return;
            }

            const result = await sendCommand(command, payload);

            // Custom formatting for specific commands
            if (command === 'help' && result?.commands) {
                console.log(chalk.cyan(result.description ?? 'Help:'));
                result.commands.forEach((c: {
                    cmd: string,
                    desc: string
                }) => console.log(`  ${chalk.blueBright(c.cmd)}: ${c.desc}`));
            } else if (command === 'info' && result?.type === 'thought') {
                console.log(chalk.cyan('--- Thought Info ---'));
                console.log(formatThought(result.data, true));
                console.log(chalk.gray(JSON.stringify(result.data.metadata, null, 2)));
            } else if (command === 'info' && result?.type === 'rule') {
                console.log(chalk.cyan('--- Rule Info ---'));
                console.log(formatRule(result.data));
                console.log(chalk.gray(JSON.stringify(result.data.metadata, null, 2)));
            } else if (command === 'get_thoughts' && Array.isArray(result)) {
                console.log(chalk.cyan(`--- All Thoughts (${result.length}) ---`));
                result.forEach((t: Thought) => console.log(formatThought(t)));
            } else if (command === 'get_rules' && Array.isArray(result)) {
                console.log(chalk.cyan(`--- All Rules (${result.length}) ---`));
                result.forEach((r: Rule) => console.log(formatRule(r)));
            } else if (command === 'get_tasks' && Array.isArray(result)) {
                console.log(chalk.cyan(`--- Tasks (${result.length}) ---`));
                result.forEach((t: any) => console.log(formatTaskSummary(t)));
            } else if (command === 'get_task_details' && result?.task) {
                console.log(chalk.cyan(`--- Task Details: ${formatTaskSummary(result.task)} ---`));
                const printSection = (title: string, items: any[] | undefined) => {
                    if (items?.length) {
                        console.log(chalk.blueBright(title + ':'));
                        items.forEach((i: any) => console.log(formatTaskDetailItem(i)));
                    }
                };
                printSection('Plan (Strategies)', result.plan);
                printSection('Facts', result.facts);
                printSection('Prompts', result.prompts);
                printSection('Logs', result.logs);
                printSection('Other Thoughts', result.other);
            } else if (command === 'tools' && Array.isArray(result)) {
                console.log(chalk.cyan('--- Available Tools ---'));
                result.forEach((t: {
                    name: string,
                    description: string
                }) => console.log(`  ${chalk.blueBright(t.name)}: ${t.description}`));
            } else if (result !== null && result !== undefined) {
                console.log(chalk.cyan(`Result:`), typeof result === 'object' ? JSON.stringify(result, null, 2) : result);
            } else {
                console.log(chalk.green('OK'));
            } // For commands with no explicit return value

        } catch (error: any) {
            console.error(chalk.red(`Error:`), error.message);
        } finally {
            rl.prompt();
        }
    });

    rl.on('close', () => {
        shuttingDown = true;
        console.log(chalk.yellow('\nREPL Client exiting.'));
        if (reconnectTimeout) clearTimeout(reconnectTimeout);
        if (ws && ws.readyState === WebSocket.OPEN) ws.close();
        setTimeout(() => process.exit(0), 100); // Allow time for cleanup
    });

    connect(); // Initial connection attempt
}

// --- Main Execution ---
async function main() {
    const mode = process.argv[2] || 'server';

    if (mode === 'server') {
        let system: System | null = null;
        const handleShutdown = async (signal: string) => {
            log('warn', `\n${signal} received. Shutting down...`);
            if (system) await system.shutdown(); // System handles process.exit
            else process.exit(0);
        };
        process.on('SIGINT', () => handleShutdown('SIGINT'));
        process.on('SIGTERM', () => handleShutdown('SIGTERM'));
        try {
            system = new System();
            await system.initialize();
            // Server runs until shutdown signal
        } catch (error: any) {
            log('error', "Critical initialization error:", error.message);
            console.error(error.stack); // Log stack for critical errors
            process.exit(1);
        }
    } else if (mode === 'client') {
        const serverUrl = process.argv[3] || `ws://localhost:${WS_PORT}`;
        await startReplClient(serverUrl);
        // REPL client handles its own lifecycle and exit
    } else {
        console.error(chalk.red(`Invalid mode: ${mode}. Use 'server' or 'client [url]'`));
        process.exit(1);
    }
}

main().catch(error => {
    log('error', 'Unhandled error in main execution:', error.message);
    console.error(error.stack);
    process.exit(1);
});
