// Okay, I've revised the TypeScript code based on the TODO list and the insightful observations provided.
//
// **Key Changes Implemented:**
//
// 1.  **Tool Schemas & Validation:**
// *   Introduced `ToolParameter`, `ToolParameterType`, and `ToolDefinition` interfaces.
// *   `ToolManager` now stores and manages `ToolDefinition` objects.
// *   Added `ToolManager.validateParams` for basic schema validation (can be extended).
// *   `ToolManager.execute` now performs validation before execution.
// *   Concrete tools (`LLMTool`, `MemoryTool`, etc.) are wrapped in `ToolDefinition` classes.
//
// 2.  **Tool Composition/Workflows:**
// *   Added `WORKFLOW_STEP` to `Type` enum.
// *   Added `WORKFLOW_ID`, `WORKFLOW_STEP`, `WORKFLOW_RESULTS` to `MetadataKeys`.
// *   `ActionExecutor` modified to handle workflow structures (`sequence`, `parallel`) within action terms.
// *   Implemented basic `executeSequenceWorkflow` and `executeParallelWorkflow` logic.
// *   Added a workflow example rule (`plan_trip`) in `bootstrapRules`.
// *   `Worker` now routes `WORKFLOW_STEP` thoughts to `ActionExecutor`.
//
// 3.  **Knowledge Graph Hints:**
// *   Added `RELATED_IDS` and `EXTRACTED_ENTITIES` to `MetadataKeys`.
// *   `MemoryStore.findSimilar` updated to accept `filterContext` for basic KG-style filtering based on these keys.
// *   `createThought` now inherits and aggregates `RELATED_IDS` from parent.
// *   `ActionExecutor` updated to link triggering thoughts/rules/results via `RELATED_IDS` and in `memorizeExecution`. (Note: `EXTRACTED_ENTITIES` population is stubbed and requires actual implementation).
//
// 4.  **Belief/RL Refinement:**
// *   Added `lastUpdated` timestamp to the `Belief` record.
// *   Added `beliefDecayRatePerMillis` to `Config` (default 0 = off).
// *   Added `decayBelief` function (currently optional, needs uncommenting in `updateBelief` / `calculateBeliefScore` to activate).
// *   Added `contextSimilarityBoostFactor` to `Config`.
// *   `sampleWeighted` function enhanced to accept context similarity scores and apply boosting.
// *   `RuleStore` now caches rule embeddings (generated via `LLMTool` in `add`).
// *   `Unifier.findAndSample` modified to use rule embeddings and context boosting.
// *   `ThoughtStore.samplePending` modified to allow context boosting based on a provided thought.
//
// 5.  **State Management & Refinements:**
// *   `updateThought` strengthened with more explicit optimistic concurrency checks based on object identity.
// *   `UserInteractionTool` refined: Request thought is set to `WAITING`, `handleResponse` marks request `DONE` and adds the `INPUT` response thought. `getPendingPromptsForUI` made more robust by checking `ThoughtStore`.
// *   `MemoryStore` constructor no longer requires `LLMTool`. `MemoryTool` now uses the `LLMTool` instance provided via the `ToolManager`.
// *   `Persistence` updated for new `Belief` structure, rule embeddings, and metadata keys. Incremented `stateVersion`. Uses async `ruleStore.add` on load.
// *   Helper `termToJsValue` added in `ActionExecutor` for better param conversion.
// *   Improved logging and error messages in several places.
// *   Added `addInput` helper to `AgentLoop`.
// *   Improved UI sorting and status indicators.
//
// **Areas Acknowledged but Not Fully Implemented (Future Directions):**
//
// *   **JSON Path / Metadata Rule Matching:** Requires significant changes to `Unifier` or a new matching mechanism.
// *   **Advanced LLM Tool Interaction:** LLM choosing tools or generating schemas. (Added passing `ToolDefinition`s to prompt as a small step).
// *   **Advanced Meta-Reasoning:** Beyond failure rule synthesis.
// *   **Rich Workflow State Passing:** Current `WORKFLOW_RESULTS` is just a placeholder concept.
// *   **Belief Propagation:** Complex logic, deferred.
// *   **Explicit Knowledge Graph:** Major architectural change.
// *   **UI as Tool / Dynamic Priorities:** Interesting concepts for future iterations.

// @ts-nocheck - Disable TypeScript checks for this file if needed for mocks/globals
// (Remove this line in a real project)

import crypto from 'crypto';

import {promises as fs} from 'fs';
import * as readline from 'readline/promises';
import {stdin as input, stdout as output} from 'process';

// --- Core Types ---

export type UUID = string;

// Added 'TOOLS', 'WORKFLOW_STEP' types
export type Type = 'INPUT' | 'GOAL' | 'STRATEGY' | 'OUTCOME' | 'QUERY' | 'RULE' | 'TOOLS' | 'WORKFLOW_STEP';
export type Status = 'PENDING' | 'ACTIVE' | 'WAITING' | 'DONE' | 'FAILED';

export type Belief = {
    readonly positive: number;
    readonly negative: number;
    readonly lastUpdated: number; // Timestamp for potential decay
};

// Initialize lastUpdated on creation
export const DEFAULT_BELIEF: Belief = Object.freeze({positive: 1.0, negative: 1.0, lastUpdated: Date.now()});

/** Calculates the belief score, smoothed probability. Optionally applies decay first. */
export function calculateBeliefScore(belief: Belief, config?: Config): number {
    let currentBelief = belief;
    // Optional: Apply decay before calculating score
    // if (config && config.beliefDecayRatePerMillis > 0) {
    //     currentBelief = decayBelief(belief, config.beliefDecayRatePerMillis, Date.now());
    // }
    const {positive, negative} = currentBelief;
    const total = positive + negative;
    // Laplace smoothing (add-1 smoothing)
    return total > 1e308 ? positive / total : (positive + 1.0) / (total + 2.0);
}

/** Updates belief counts based on success/failure, setting lastUpdated timestamp. */
export function updateBelief(belief: Belief, success: boolean, config?: Config): Belief {
    const now = Date.now();
    let currentBelief = belief;
    // Optional: Apply decay accumulated since last update before incrementing
    // if (config && config.beliefDecayRatePerMillis > 0) {
    //     currentBelief = decayBelief(belief, config.beliefDecayRatePerMillis, now);
    // }
    const {positive: basePositive, negative: baseNegative} = currentBelief;

    const newPositive = success ? Math.min(basePositive + 1.0, Number.MAX_VALUE) : basePositive;
    const newNegative = success ? baseNegative : Math.min(baseNegative + 1.0, Number.MAX_VALUE);

    // Prevent NaN/Infinity if counts somehow become invalid
    if (!isFinite(newPositive) || !isFinite(newNegative)) {
        console.warn("Belief update resulted in non-finite value, resetting to default.");
        return {...DEFAULT_BELIEF, lastUpdated: now}; // Reset with current timestamp
    }
    return {positive: newPositive, negative: newNegative, lastUpdated: now}; // Update timestamp
}


class HttpClient {
    constructor(private baseUrl: string = '') {
    }

    private async request<T>(url: string, options: FetchRequestInit = {}): Promise<T | null> { /* ... implementation ... */

        const fullUrl = url.startsWith('http') ? url : `${this.baseUrl}${url}`;
        const method = options.method || 'GET';
        try {
            const response: FetchResponse = await fetch(fullUrl, options);
            if (!response.ok) {
                let errorBody = '';
                try {
                    errorBody = await response.text();
                } catch (_) {
                }
                const errorMsg = `HTTP Error ${response.status}: ${response.statusText} on ${method} ${fullUrl}`;
                console.error(`${errorMsg}. Body: ${errorBody.substring(0, 200)}`);
                const error = new Error(errorMsg);
                (error as any).status = response.status;
                (error as any).body = errorBody;
                throw error;
            }
            if (response.status === 204) return null;
            const contentType = response.headers.get('content-type');
            if (contentType && contentType.includes('application/json')) {
                const text = await response.text();
                let y = text ? JSON.parse(text) as T : null;

                //DEBUGGING
                //if (!y.embedding) console.log(options.body, y);

                return y;
            } else if (contentType && (contentType.includes('text') || contentType.includes('application/javascript'))) {
                return await response.text() as any as T;
            } else {
                console.warn(`HttpClient: Unsupported content type '${contentType}' for ${method} ${fullUrl}. Returning raw text.`);
                return await response.text() as any as T;
            }
        } catch (error: any) {
            console.error(`HttpClient Request Failed: ${method} ${fullUrl}`, error.message || error);
            throw new Error(`HTTP request failed for ${method} ${fullUrl}: ${error.message}`);
        }
    }

    async get<T>(url: string): Promise<T | null> {
        return this.request<T>(url, {method: 'GET'});
    }

    async post<T>(url: string, body: any): Promise<T | null> {
        return this.request<T>(url, {
            method: 'POST',
            headers: {'Content-Type': 'application/json', 'Accept': 'application/json'},
            body: JSON.stringify(body)
        });
    }
}


/** Optional: Applies exponential decay to belief counts based on time elapsed. */
export function decayBelief(belief: Belief, decayRatePerMillis: number, currentTime: number): Belief {
    if (decayRatePerMillis <= 0 || !isFinite(decayRatePerMillis)) return belief; // No decay
    const elapsedMillis = Math.max(0, currentTime - belief.lastUpdated);
    if (elapsedMillis === 0) return belief;

    const decayFactor = Math.exp(-decayRatePerMillis * elapsedMillis);
    // Decay towards the initial state (1.0, 1.0) - prevents counts going below 1.
    const decayedPositive = 1.0 + (belief.positive - 1.0) * decayFactor;
    const decayedNegative = 1.0 + (belief.negative - 1.0) * decayFactor;

    // Clamp to minimum of 1 to prevent negative or zero counts from decay
    return {
        positive: Math.max(1.0, decayedPositive),
        negative: Math.max(1.0, decayedNegative),
        lastUpdated: currentTime // Update timestamp even if no significant decay occurred
    };
}


// --- Term Representation (Discriminated Union) ---

export type Term = Atom | Variable | Structure | ListTerm;

export type Atom = { readonly kind: 'atom'; readonly name: string; };
export type Variable = { readonly kind: 'variable'; readonly name: string; };
export type Structure = { readonly kind: 'structure'; readonly name: string; readonly args: ReadonlyArray<Term>; };
export type ListTerm = { readonly kind: 'list'; readonly elements: ReadonlyArray<Term>; };

/** create Atom */
export const A = (name: string): Atom => ({kind: 'atom', name});

/** create Variable */
export const V = (name: string): Variable => ({kind: 'variable', name});

/** create Structure */
export const S = (name: string, args: ReadonlyArray<Term>): Structure => ({
    kind: 'structure',
    name,
    args: Object.freeze([...args])
});

/** create List */
export const L = (elements: ReadonlyArray<Term>): ListTerm => ({kind: 'list', elements: Object.freeze([...elements])});

export const isA = (term: Term): term is Atom => term.kind === 'atom';
export const isV = (term: Term): term is Variable => term.kind === 'variable';
export const isS = (term: Term): term is Structure => term.kind === 'structure';
export const isL = (term: Term): term is ListTerm => term.kind === 'list';

export function termToString(t: Term): string {
    switch (t.kind) {
        case 'atom':
            return t.name;
        case 'variable':
            return `?${t.name}`;
        case 'structure':
            return `${t.name}(${t.args.map(termToString).join(', ')})`;
        case 'list':
            return `[${t.elements.map(termToString).join(', ')}]`;
    }
}

// --- Core Data Structures ---

// Using Record<string, unknown> for flexibility, but specific keys are defined
export type Metadata = Readonly<Record<string, unknown>>;

/** Metadata Keys */
export const Keys = Object.freeze({
    ROOT_ID: 'root_id',             // UUID of the root INPUT thought (Note ID)
    AGENT_ID: 'agent_id',           // UUID of the agent processing/owning the thought
    SHARED_WITH: 'shared_with',     // List<UUID> of agent IDs to share with
    PARENT_ID: 'parent_id',         // UUID of the thought that generated this one
    TIMESTAMP: 'timestamp',         // Milliseconds since epoch of creation/update
    ERROR: 'error',                 // String describing failure reason
    PROVENANCE: 'provenance',       // List<UUID> of Rule IDs or Thought IDs contributing
    UI_CONTEXT: 'ui_context',       // String hint for display in the UI
    PRIORITY: 'priority',           // Optional number for explicit prioritization (overrides belief score)
    EMBEDDING: 'embedding',         // ReadonlyArray<number> embedding vector (optional)
    RETRY_COUNT: 'retry_count',     // Number of times processing has been attempted
    RELATED_IDS: 'related_ids',     // List<UUID> Knowledge Graph hint: Links to related Thoughts/Rules/MemoryEntries
    EXTRACTED_ENTITIES: 'extracted_entities', // List<string> Knowledge Graph hint: Key entities mentioned
    WORKFLOW_STEP: 'workflow_step', // Number: Index for sequence workflow
    WORKFLOW_ID: 'workflow_id',     // UUID: Identifier for a workflow instance
    WORKFLOW_RESULTS: 'workflow_results', // Record<string, unknown>: Store results from previous workflow steps (use with caution)
});

export type Thought = {
    readonly id: UUID;
    readonly type: Type;
    readonly content: Term; // Can be a regular term or a workflow term structure
    readonly belief: Belief;
    readonly status: Status;
    readonly metadata: Metadata;
};

export type Rule = {
    readonly id: UUID;
    readonly pattern: Term; // Matches Thought.content
    readonly action: Term; // Can be a tool call structure or a workflow definition structure
    readonly belief: Belief;
    readonly metadata: Metadata;
};

export type MemoryEntry = {
    readonly id: UUID;
    readonly embedding: ReadonlyArray<number>;
    readonly content: string; // Often the string representation of a Thought or Rule
    readonly metadata: Metadata; // Can include type, timestamp, related_ids, extracted_entities etc.
};

export type Config = {
    readonly maxRetries: number;
    readonly pollIntervalMillis: number;
    readonly thoughtProcessingTimeoutMillis: number;
    readonly numWorkers: number;
    readonly uiRefreshMillis: number;
    readonly memorySearchLimit: number;
    readonly ollamaApiBaseUrl: string;
    readonly ollamaModel: string;
    readonly persistenceFilePath: string;
    readonly persistenceIntervalMillis: number;
    readonly beliefDecayRatePerMillis: number; // For optional belief decay (e.g., 1e-9 for slow decay)
    readonly contextSimilarityBoostFactor: number; // Multiplier for similarity boost (0 to disable, >0 to enable)
    readonly enableSchemaValidation: boolean; // Toggle for tool param validation
};

export const DEFAULT_CONFIG: Config = Object.freeze({
    ollamaModel: 'Qwen2.5.1-Coder-7B-Instruct-GGUF:Q6_K_L_wide',
    numWorkers: 1, //TODO available processors
    memorySearchLimit: 5,
    maxRetries: 3,
    pollIntervalMillis: 100,
    thoughtProcessingTimeoutMillis: 30000,
    errorSleepMS: 500,
    uiRefreshMillis: 1000,
    ollamaApiBaseUrl: 'http://localhost:11434',
    persistenceFilePath: './flowmind_state.json',
    persistenceIntervalMillis: 30000,
    beliefDecayRatePerMillis: 0, // Default: No decay
    contextSimilarityBoostFactor: 0.5, // Moderate boost based on context
    enableSchemaValidation: true, // Enable tool schema validation by default
});

// --- Utility Functions ---

export function generateUUID(): UUID {
    return crypto.randomUUID();
}

/** Merges multiple metadata objects, freezing the result. Later objects overwrite earlier ones, except for specific array keys which are merged. */
export function mergeMetadata(...metadatas: (Metadata | Partial<Metadata> | undefined | null)[]): Metadata {
    const merged: Record<string, unknown> = {}; // Use a mutable object temporarily
    const arrayKeysToMerge = [Keys.PROVENANCE, Keys.RELATED_IDS, Keys.EXTRACTED_ENTITIES]; // Keys whose array values should be combined

    for (const meta of metadatas) {
        if (meta) {
            for (const key in meta) {
                const existingValue = merged[key];
                const newValue = meta[key];

                if (arrayKeysToMerge.includes(key) && Array.isArray(existingValue) && Array.isArray(newValue)) {
                    // Combine and deduplicate arrays for specific keys
                    merged[key] = Array.from(new Set([...existingValue, ...newValue]));
                } else if (newValue !== undefined) { // Ensure we don't overwrite with undefined
                    merged[key] = newValue; // Otherwise, the later value overwrites
                }
            }
        }
    }
    return Object.freeze(merged);
}


/** Creates a standard Thought object, handling defaults and metadata inheritance. */
export function createThought(
    agentId: UUID,
    type: Type,
    content: Term,
    status: Status,
    additionalMetadata: Partial<Metadata>,
    parentThought?: Thought | null, // Optional parent for inheriting metadata & linking
    belief?: Belief // Allow specifying initial belief (optional)
): Thought {
    const id = generateUUID();
    const now = Date.now();
    const initialBelief = belief ? {...belief, lastUpdated: now} : {...DEFAULT_BELIEF, lastUpdated: now};

    /* base metadata */
    const b: Partial<Metadata> = {
        [Keys.AGENT_ID]: agentId,
        [Keys.TIMESTAMP]: now,
    };

    let relatedIds: UUID[] = [];
    let provenance: UUID[] = [];

    if (parentThought) {
        b[Keys.PARENT_ID] = parentThought.id;
        const rootId = parentThought.metadata[Keys.ROOT_ID] ?? parentThought.id;
        b[Keys.ROOT_ID] = rootId;
        relatedIds.push(parentThought.id);

        // Inherit provenance chain
        const parentProvenance = parentThought.metadata[Keys.PROVENANCE];
        if (Array.isArray(parentProvenance)) {
            provenance = [...parentProvenance];
        }
        // Add parent ID to provenance if it's not already there (e.g., if parent was the direct trigger)
        // if (!provenance.includes(parentThought.id)) {
        //     provenance.push(parentThought.id);
        // }

        // Inherit workflow context if present
        if (parentThought.metadata[Keys.WORKFLOW_ID]) {
            b[Keys.WORKFLOW_ID] = parentThought.metadata[Keys.WORKFLOW_ID];
            // Workflow step handled by ActionExecutor when creating next step thought
            // baseMetadata[MetadataKeys.WORKFLOW_STEP] = (Number(parentThought.metadata[MetadataKeys.WORKFLOW_STEP] ?? -1)) + 1;
            // Carry forward workflow results if needed (specific workflow logic TBD)
            // baseMetadata[MetadataKeys.WORKFLOW_RESULTS] = parentThought.metadata[MetadataKeys.WORKFLOW_RESULTS];
        }

        // Add parent's related IDs
        const parentRelated = parentThought.metadata[Keys.RELATED_IDS];
        if (Array.isArray(parentRelated)) {
            relatedIds = relatedIds.concat(parentRelated);
        }
    }

    // Ensure agentId is set
    b[Keys.AGENT_ID] = agentId;

    // Add root_id self-reference for new root thoughts if not inherited
    if ((type === 'INPUT') && !b[Keys.ROOT_ID]) {
        b[Keys.ROOT_ID] = id;
    }

    // Add collected provenance and related IDs to base metadata before merge
    if (provenance.length > 0) {
        b[Keys.PROVENANCE] = Array.from(new Set(provenance));
    }
    if (relatedIds.length > 0) {
        // Filter out potential self-references just in case
        b[Keys.RELATED_IDS] = Array.from(new Set(relatedIds.filter(rId => rId !== id)));
    }

    // Extract entities hint (requires implementation)
    // TODO: const entities = extractEntities(termToString(content));
    // if (entities?.length > 0) baseMetadata[MetadataKeys.EXTRACTED_ENTITIES] = entities;

    return Object.freeze({
        id,
        type,
        content,
        belief: initialBelief,
        status,
        metadata: mergeMetadata(b, additionalMetadata)
    });
}


/**
 * Atomically updates a thought in the store if the `currentThought` object reference matches the stored version.
 * Applies belief update and merges metadata. Returns true on success.
 */
export function updateThought(
    thoughtStore: ThoughtStore,
    currentThought: Thought, // The exact object reference the worker sampled
    newStatus: Status,
    additionalMetadata: Partial<Metadata>,
    config?: Config // Pass config for belief updates/decay
): boolean {
    const latestThoughtVersion = thoughtStore.get(currentThought.id);

    // *** Optimistic Concurrency Check using Object Identity ***
    // This ensures the thought hasn't been modified by another worker since we sampled it.
    if (latestThoughtVersion !== currentThought) {
        console.warn(`updateThought [${currentThought.id.substring(0, 6)}]: Stale object reference. Expected version timestamp ${currentThought.metadata[Keys.TIMESTAMP]}, found ${latestThoughtVersion?.metadata[Keys.TIMESTAMP]}. Update aborted.`);
        return false;
    }

    // Determine success based on target status (DONE means success for belief update)
    const isSuccess = newStatus === 'DONE';
    const updatedBelief = updateBelief(latestThoughtVersion.belief, isSuccess, config);

    // Optional: Apply belief decay globally here if not done elsewhere
    // const finalBelief = decayBelief(updatedBelief, config.beliefDecayRatePerMillis, Date.now());
    const finalBelief = updatedBelief;

    // Merge metadata, ensuring the latest timestamp and handling array merges
    const finalMetadata = mergeMetadata(
        latestThoughtVersion.metadata, // Base is the version we confirmed exists
        additionalMetadata, // Apply the specific updates for this change
        {[Keys.TIMESTAMP]: Date.now()} // Always update timestamp
    );

    const updatedThought: Thought = {
        ...latestThoughtVersion, // Build upon the confirmed latest version
        status: newStatus,
        belief: finalBelief,
        metadata: finalMetadata
    };

    // Attempt the atomic update in the store using the verified object reference
    const updateSucceeded = thoughtStore.update(latestThoughtVersion, updatedThought);
    if (!updateSucceeded) {
        // This *shouldn't* happen if the initial identity check passed, but log defensively.
        console.error(`updateThought [${currentThought.id.substring(0, 6)}]: Store update failed unexpectedly after passing identity check. This indicates a potential issue.`);
    }
    return updateSucceeded;
}


/** Samples an item from a list based on weights, optionally boosting by context similarity. */
export function sampleWeighted<T>(
    items: ReadonlyArray<[number, T]>,
    contextSimilarityScores?: ReadonlyMap<T, number>, // Optional map of Item -> Similarity Score (0 to 1)
    boostFactor: number = 0 // Optional boost factor (0 = no boost, >0 = apply boost)
): T | null {
    if (items.length === 0) return null;

    // Adjust weights based on similarity scores if provided
    const weightedItems = items.map(([weight, item]): [number, T] => {
        let adjustedWeight = weight;
        if (contextSimilarityScores && contextSimilarityScores.has(item) && boostFactor > 0) {
            const similarity = contextSimilarityScores.get(item) ?? 0;
            // Apply multiplicative boost: weight * (1 + similarity * factor)
            // Ensure similarity is clamped [0, 1] and weight remains non-negative
            adjustedWeight = Math.max(0, weight * (1 + Math.max(0, Math.min(1, similarity)) * boostFactor));
        }
        // Ensure weights are finite and non-negative
        return [isFinite(adjustedWeight) && adjustedWeight >= 0 ? adjustedWeight : 0, item];
    });

    // Filter out items with zero or invalid weights AFTER boost application
    const validItems = weightedItems.filter(([weight]) => weight > 0);
    if (validItems.length === 0) {
        // Fallback: If all weights are zero or invalid, pick randomly from original items
        return items.length > 0 ? items[Math.floor(Math.random() * items.length)][1] : null;
    }

    // Calculate total weight of valid items
    const totalWeight = validItems.reduce((sum, [weight]) => sum + weight, 0);
    if (totalWeight <= 0) {
        // Should not happen if validItems is not empty, but as a fallback:
        return validItems[Math.floor(Math.random() * validItems.length)][1];
    }

    // Perform weighted sampling
    let random = Math.random() * totalWeight;
    for (const [weight, item] of validItems) {
        if (random < weight) return item;
        random -= weight;
    }

    // Fallback for potential floating point inaccuracies
    return validItems[validItems.length - 1][1];
}

export type Bindings = Map<string, Term>; // Variable name -> Term

function apply(t: Term, b: Bindings): Term {
    // Implementation remains the same...
    switch (t.kind) {
        case 'variable':
            return b.get(t.name) ?? t;
        case 'structure':
            return S(t.name, t.args.map(arg => apply(arg, b)));
        case 'list':
            return L(t.elements.map(el => apply(el, b)));
        case 'atom':
            return t;
    }
}

function contains(t: Term, variableName: string): boolean {
    // Implementation remains the same...
    switch (t.kind) {
        case 'variable':
            return t.name === variableName;
        case 'structure':
            return t.args.some(arg => contains(arg, variableName));
        case 'list':
            return t.elements.some(el => contains(el, variableName));
        case 'atom':
            return false;
    }
}

export function sleep(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
}

// --- Unification ---

export class Unifier {
    // Implementation remains the same as before...
    unify(term1: Term, term2: Term): Bindings | null {
        const bindings: Bindings = new Map();
        return this.unifyRecursive(term1, term2, bindings) ? bindings : null;
    }

    private unifyRecursive(term1: Term, term2: Term, bindings: Bindings): boolean {
        const t1 = apply(term1, bindings);
        const t2 = apply(term2, bindings);

        if (isV(t1)) return this.bind(t1, t2, bindings);
        if (isV(t2)) return this.bind(t2, t1, bindings);

        if (isA(t1) && isA(t2)) return t1.name === t2.name;

        if (isS(t1) && isS(t2)) {
            return t1.name === t2.name &&
                t1.args.length === t2.args.length &&
                t1.args.every((arg, i) => this.unifyRecursive(arg, t2.args[i], bindings));
        }
        if (isL(t1) && isL(t2)) {
            return t1.elements.length === t2.elements.length &&
                t1.elements.every((el, i) => this.unifyRecursive(el, t2.elements[i], bindings));
        }
        // Consider term equality as a base case? No, unification handles structure.
        if (t1.kind === t2.kind && JSON.stringify(t1) === JSON.stringify(t2)) return true; // Basic equality check for atoms/identical structures

        return false;
    }

    private bind(variable: Variable, term: Term, bindings: Bindings): boolean {
        if (bindings.has(variable.name)) {
            return this.unifyRecursive(bindings.get(variable.name)!, term, bindings);
        }
        if (isV(term) && bindings.has(term.name)) {
            return this.unifyRecursive(variable, bindings.get(term.name)!, bindings);
        }
        // Occurs check: Ensure the variable doesn't appear within the term it's being bound to.
        if (contains(term, variable.name)) {
            // console.warn(`Unifier: Occurs check failed for variable ?${variable.name} and term ${termToString(term)}`);
            return false;
        }
        bindings.set(variable.name, term);
        return true;
    }

    /** Finds rules matching the thought and samples one based on belief score, potentially boosted by context similarity. */
    findAndSample(
        thought: Thought,
        rules: ReadonlyArray<Rule>,
        boostFactor: number, // Configured boost factor
        ruleEmbeddings: ReadonlyMap<UUID, ReadonlyArray<number>> // Precomputed rule embeddings
    ): { rule: Rule; bindings: Bindings } | null {

        const thoughtEmbedding = thought.metadata[Keys.EMBEDDING] as ReadonlyArray<number> | undefined;
        let contextSimilarities: Map<{ rule: Rule; bindings: Bindings }, number> | undefined;
        let requiresWeightedSampling = false; // Flag if boosting is applied

        // Prepare candidate matches with potentially boosted scores
        const matches: Array<[number, { rule: Rule; bindings: Bindings }]> = rules
            .map(rule => {
                const bindings = this.unify(thought.content, rule.pattern);
                if (!bindings) return null; // Unification failed

                const baseScore = calculateBeliefScore(rule.belief); // Use non-decayed belief for selection fairness? Or config based?
                const matchObject = {rule, bindings};
                let boostedScore = baseScore;

                // Apply context boost if embeddings are available and boost factor > 0
                if (thoughtEmbedding && boostFactor > 0) {
                    const ruleEmbedding = ruleEmbeddings.get(rule.id);
                    if (ruleEmbedding && ruleEmbedding.length > 0) {
                        const similarity = cosineSimilarity(thoughtEmbedding, ruleEmbedding);
                        // Apply multiplicative boost, ensuring score remains non-negative
                        boostedScore = Math.max(0, baseScore * (1 + Math.max(0, Math.min(1, similarity)) * boostFactor));
                        if (boostedScore !== baseScore) {
                            requiresWeightedSampling = true; // Boosting occurred, need proper sampling
                        }
                    }
                }

                // Ensure score is valid
                if (!isFinite(boostedScore) || boostedScore < 0) {
                    console.warn(`Invalid score (${boostedScore}) calculated for rule ${rule.id}. Using 0.`);
                    boostedScore = 0;
                }

                return [boostedScore, matchObject] as [number, { rule: Rule; bindings: Bindings }];
            })
            // Filter out nulls (unification failures) and matches with zero score
            .filter((match): match is [number, { rule: Rule; bindings: Bindings }] => match !== null && match[0] > 0);

        if (matches.length === 0) return null; // No rules matched or all had zero score

        // If boosting wasn't applied or resulted in uniform scores, potentially optimize sampling?
        // For now, always use weighted sampling for simplicity and correctness with scores.
        return sampleWeighted(matches); // sampleWeighted handles the distribution based on scores
    }
}

// --- Storage Components ---

export class ThoughtStore {
    private readonly thoughts = new Map<UUID, Thought>();

    constructor(public readonly agentId: UUID) {
    }

    get(id: UUID): Thought | undefined {
        return this.thoughts.get(id);
    }

    getEmbedding(id: UUID): ReadonlyArray<number> | undefined {
        return this.thoughts.get(id)?.metadata[Keys.EMBEDDING] as ReadonlyArray<number> | undefined;
    }

    add(thought: Thought): void {
        const targetAgentId = thought.metadata[Keys.AGENT_ID] ?? this.agentId;
        if (targetAgentId !== this.agentId) {
            console.warn(`Thought ${thought.id} ignored by agent ${this.agentId}: Agent mismatch (target: ${targetAgentId})`);
            return;
        }
        if (this.thoughts.has(thought.id)) {
            console.warn(`ThoughtStore: Thought with ID ${thought.id} already exists. Overwriting is discouraged due to optimistic concurrency. Ensure IDs are unique or updates use the 'update' method.`);
            // Overwriting can break optimistic locking if another worker holds a reference to the old object
        }
        this.thoughts.set(thought.id, thought); // Allow overwrite but warn
    }

    /** Atomically replaces oldThought with updatedThought using object identity check. */
    update(oldThoughtRef: Thought, updatedThought: Thought): boolean {
        // Strict identity check: Ensures we are updating the exact object version we read
        if (this.thoughts.get(oldThoughtRef.id) === oldThoughtRef) {
            if (oldThoughtRef.id !== updatedThought.id) {
                console.error(`ThoughtStore: Update attempt with mismatched IDs (${oldThoughtRef.id} vs ${updatedThought.id}). Aborting.`);
                return false;
            }
            this.thoughts.set(updatedThought.id, updatedThought);
            return true;
        }
        // If identity check fails, it means the thought was modified concurrently.
        return false;
    }

    /** Samples a PENDING thought, optionally boosting based on context similarity to a reference thought. */
    samplePending(
        contextThought?: Thought, // Optional: The thought providing context for boosting
        boostFactor: number = 0,
        config?: Config // Pass config for belief score calculation
    ): Thought | null {
        const pendingThoughts = Array.from(this.thoughts.values())
            .filter(t => t.status === 'PENDING');
        if (pendingThoughts.length === 0) return null;

        let contextSimilarities: Map<Thought, number> | undefined;
        const contextEmbedding = contextThought?.metadata[Keys.EMBEDDING] as ReadonlyArray<number> | undefined;

        // Calculate similarities if context is provided and boosting is enabled
        if (contextEmbedding && boostFactor > 0) {
            contextSimilarities = new Map();
            pendingThoughts.forEach(pending => {
                const pendingEmbedding = pending.metadata[Keys.EMBEDDING] as ReadonlyArray<number> | undefined;
                if (pendingEmbedding) {
                    const similarity = cosineSimilarity(contextEmbedding, pendingEmbedding);
                    contextSimilarities!.set(pending, similarity);
                } else {
                    contextSimilarities!.set(pending, 0); // No embedding, no similarity boost
                }
            });
        }

        // Prepare items for weighted sampling
        const weightedItems: Array<[number, Thought]> = pendingThoughts.map(t => {
            // Use explicit priority if set and valid, otherwise use belief score
            const priority = t.metadata[Keys.PRIORITY];
            const baseWeight = typeof priority === 'number' && isFinite(priority) && priority > 0
                ? priority
                : calculateBeliefScore(t.belief, config); // Default weight from belief

            return [baseWeight, t]; // Base weight calculation
        });

        // Sample using the utility function which handles context boosting internally
        return sampleWeighted(weightedItems, contextSimilarities, boostFactor);
    }


    findByParent(parentId: UUID): ReadonlyArray<Thought> {
        return Object.freeze(Array.from(this.thoughts.values()).filter(t => t.metadata[Keys.PARENT_ID] === parentId));
    }

    findByRoot(rootId: UUID): ReadonlyArray<Thought> {
        return Object.freeze(Array.from(this.thoughts.values()).filter(t => t.metadata[Keys.ROOT_ID] === rootId));
    }

    getAllThoughts(): ReadonlyArray<Thought> {
        return Object.freeze(Array.from(this.thoughts.values()));
    }

    clear(): void {
        this.thoughts.clear();
    } // For persistence loading
}

export class RuleStore {
    private readonly rules = new Map<UUID, Rule>();
    private embeddings = new Map<UUID, ReadonlyArray<number>>(); // Cache rule embeddings

    constructor(private readonly llmTool?: LLMTool) {
    } // Optional LLM for embedding generation

    get(id: UUID): Rule | undefined {
        return this.rules.get(id);
    }

    /** Adds a rule and generates/caches its embedding if LLMTool is available. */
    async add(rule: Rule): Promise<void> {
        if (this.rules.has(rule.id)) {
            console.warn(`RuleStore: Rule with ID ${rule.id} already exists. Overwriting.`);
        }
        this.rules.set(rule.id, rule);
        // Generate and cache embedding if LLM tool is available
        if (this.llmTool) {
            try {
                // Embed based on pattern and action string representation for semantic meaning
                const contentToEmbed = `Rule Pattern: ${termToString(rule.pattern)}\nRule Action: ${termToString(rule.action)}`;
                // Avoid embedding overly long strings if necessary
                const embedding = await this.llmTool.generateEmbedding(contentToEmbed.substring(0, 2000)); // Limit length?
                if (embedding.length > 0) {
                    this.embeddings.set(rule.id, Object.freeze(embedding));
                    // console.debug(`RuleStore: Generated embedding for rule ${rule.id}`);
                } else {
                    console.warn(`RuleStore: Embedding generation returned empty for rule ${rule.id}`);
                }
            } catch (error) {
                console.error(`RuleStore: Failed to generate embedding for rule ${rule.id}:`, error);
            }
        }
    }

    /** Updates a rule using optimistic concurrency (object identity). Re-caches embedding. */
    async update(oldRuleRef: Rule, updatedRule: Rule): Promise<boolean> {
        if (this.rules.get(oldRuleRef.id) === oldRuleRef) {
            if (oldRuleRef.id !== updatedRule.id) {
                console.error(`RuleStore: Update attempt with mismatched IDs (${oldRuleRef.id} vs ${updatedRule.id}). Aborting.`);
                return false;
            }
            this.rules.set(updatedRule.id, updatedRule);
            // Invalidate and regenerate embedding
            this.embeddings.delete(oldRuleRef.id);
            await this.add(updatedRule); // Re-add handles embedding generation
            return true;
        }
        return false; // Concurrent modification
    }

    getAll(): ReadonlyArray<Rule> {
        return Object.freeze(Array.from(this.rules.values()));
    }

    getEmbeddings(): ReadonlyMap<UUID, ReadonlyArray<number>> {
        return this.embeddings;
    }

    clear(): void {
        this.rules.clear();
        this.embeddings.clear();
    } // For persistence loading
}

export class MemoryStore {
    private readonly entries = new Map<UUID, MemoryEntry>();

    // Constructor is now simple, embedding handled by MemoryTool or externally.
    constructor() {
    }

    /** Adds a pre-formed MemoryEntry. */
    async add(entry: MemoryEntry): Promise<void> {
        if (!entry || !entry.id || !entry.content || !Array.isArray(entry.embedding)) {
            console.error("MemoryStore: Attempted to add invalid memory entry:", entry);
            return;
        }
        if (this.entries.has(entry.id)) {
            console.warn(`MemoryStore: Entry with ID ${entry.id} already exists. Overwriting.`);
        }
        // Ensure embedding and metadata are frozen
        const finalEntry = Object.freeze({
            ...entry,
            embedding: Object.freeze([...entry.embedding]),
            metadata: Object.freeze({...(entry.metadata ?? {})})
        });
        this.entries.set(entry.id, finalEntry);
    }

    /**
     * Finds similar memory entries based on embedding similarity.
     * Applies filtering based on optional context criteria.
     */
    async findSimilar(
        queryEmbedding: ReadonlyArray<number>,
        limit: number,
        filterContext?: { // Context for Knowledge Graph style filtering/boosting
            requiredEntities?: ReadonlyArray<string>;
            relatedToId?: UUID; // Find memories related to a specific Thought/Rule ID
            requiredType?: string; // Filter by memory entry type (e.g., 'execution_trace', 'fact')
        }
    ): Promise<ReadonlyArray<MemoryEntry>> {
        if (!queryEmbedding || queryEmbedding.length === 0) {
            console.warn("MemoryStore.findSimilar called with empty or invalid query embedding.");
            return [];
        }

        let candidates = Array.from(this.entries.values());

        // --- Filtering based on filterContext (Knowledge Graph hints) ---
        if (filterContext) {
            candidates = candidates.filter(entry => {
                const meta = entry.metadata;
                if (!meta) return true; // Keep if no metadata to filter on? Or discard? Let's keep.

                // Check Type
                if (filterContext.requiredType && meta['type'] !== filterContext.requiredType) {
                    return false;
                }
                // Check Relation
                if (filterContext.relatedToId) {
                    const related = meta[Keys.RELATED_IDS] as ReadonlyArray<UUID> | undefined;
                    // Check if the relatedToId is present in the entry's related_ids
                    if (!related || !related.includes(filterContext.relatedToId)) {
                        return false;
                    }
                }
                // Check Entities (requires ALL specified entities to be present)
                if (filterContext.requiredEntities && filterContext.requiredEntities.length > 0) {
                    const entities = meta[Keys.EXTRACTED_ENTITIES] as ReadonlyArray<string> | undefined;
                    // Entry must have entities, and all required entities must be included
                    if (!entities || !filterContext.requiredEntities.every(req => entities.includes(req))) {
                        return false;
                    }
                }
                return true; // Passed all filters
            });
        }

        // --- Scoring & Ranking ---
        if (candidates.length === 0) return []; // No candidates after filtering

        const scoredEntries = candidates
            .map(entry => {
                const score = cosineSimilarity(queryEmbedding, entry.embedding);
                // Optional: Add boosting based on other context relevance here if needed
                return {score, entry};
            })
            .filter(item => isFinite(item.score)); // Ensure score is valid

        // Sort by similarity score DESC
        scoredEntries.sort((a, b) => b.score - a.score);

        return Object.freeze(scoredEntries.slice(0, limit).map(item => item.entry));
    }

    get(id: UUID): MemoryEntry | undefined {
        return this.entries.get(id);
    }

    getAllEntries(): ReadonlyArray<MemoryEntry> {
        return Object.freeze(Array.from(this.entries.values()));
    }

    // Used by Persistence to load entries
    _loadEntry(entry: MemoryEntry): void {
        // Ensure data integrity on load
        if (entry.id && entry.content && Array.isArray(entry.embedding)) {
            // Use the main add method to ensure freezing and warnings
            this.add(entry);
        } else {
            console.warn(`MemoryStore: Ignoring invalid memory entry during load: ID=${entry?.id}`);
        }
    }

    clear(): void {
        this.entries.clear();
    } // For persistence loading
}

/** Calculates cosine similarity between two vectors. */
export function cosineSimilarity(vecA: ReadonlyArray<number>, vecB: ReadonlyArray<number>): number {
    if (!vecA || !vecB || vecA.length === 0 || vecA.length !== vecB.length) return 0;
    let dotProduct = 0, normA = 0, normB = 0;
    const len = vecA.length;
    for (let i = 0; i < len; i++) {
        const valA = vecA[i] ?? 0; // Handle potential undefined/null in array
        const valB = vecB[i] ?? 0;
        dotProduct += valA * valB;
        normA += valA * valA;
        normB += valB * valB;
    }
    const magnitude = Math.sqrt(normA) * Math.sqrt(normB);
    // Handle division by zero
    return magnitude === 0 ? 0 : dotProduct / magnitude;
}

// --- Tool Infrastructure ---

// Define basic parameter types for schema
export type ToolParameterType = 'string' | 'number' | 'boolean' | 'array' | 'object' | 'term';

export interface ToolParameter {
    readonly type: ToolParameterType;
    readonly required?: boolean;
    readonly description?: string;
    readonly itemType?: ToolParameterType; // For array type (e.g., 'string' if it's string[])
    // Potential: Add 'enum' for allowed values
    // Potential: Add 'schema' for nested object structure validation
}

// Structure to hold tool definition including schema
export interface ToolDefinition {
    readonly name: string;
    readonly description: string;
    readonly parameters: Readonly<Record<string, ToolParameter>>;
    readonly tool: Tool; // The actual implementation instance
}

// Tool interface remains simple
export interface Tool {
    /** Executes the tool's action. Throws error on failure. Returns resulting Thought. */
    execute(params: Readonly<Record<string, unknown>>, parentThought: Thought, agentId: UUID): Promise<Thought>;
}

export class ToolManager {
    // Store full definitions now
    private readonly tools = new Map<string, ToolDefinition>();

    constructor(
        // Pass dependencies tools might need during registration or execution
        private readonly memoryStore: MemoryStore,
        private readonly thoughtStore: ThoughtStore,
        private readonly config: Config,
        private readonly llmTool: LLMTool // Provide central LLM Tool instance
    ) {
        // Register core tools using their definitions
        this.registerToolDefinition(new LLMToolDefinition(this.config, this.llmTool)); // Pass instance
        this.registerToolDefinition(new MemoryToolDefinition(this.memoryStore, this.llmTool));
        this.registerToolDefinition(new GoalProposalToolDefinition(this.memoryStore, this)); // Pass self (ToolManager)
        // UserInteractionTool instance needs to be created externally and passed during registration
    }

    /** Registers a tool using its definition object. */
    registerToolDefinition(d: ToolDefinition): void {
        if (!d || !d.name || !d.tool) {
            console.error("ToolManager: Attempted to register invalid tool definition:", d);
            return;
        }
        if (this.tools.has(d.name)) {
            console.warn(`ToolManager: Overwriting existing tool definition "${d.name}"`);
        }
        this.tools.set(d.name, d);
    }

    /** Retrieves a tool definition. */
    getToolDefinition(name: string): ToolDefinition | undefined {
        return this.tools.get(name);
    }

    /** Retrieves all tool definitions (e.g., for LLM context prompting). */
    getAllToolDefinitions(): ReadonlyArray<ToolDefinition> {
        return Object.freeze(Array.from(this.tools.values()));
    }


    /** Executes a tool, performs schema validation, handles errors, returns result Thought. */
// Inside ToolManager.execute:
    async execute(toolName: string, params: Readonly<Record<string, unknown>>, parentThought: Thought, agentId: UUID): Promise<Thought> {
        const definition = this.tools.get(toolName); // Get definition

        if (!definition) {
            // ... (tool not found error handling - remains the same) ...
            const errorMsg = `Unknown tool: ${toolName}`;
            console.error(`ToolManager: ${errorMsg}`);
            return createThought(agentId, 'OUTCOME', A('error_tool_not_found'), 'FAILED',
                {[Keys.ERROR]: errorMsg, [Keys.UI_CONTEXT]: `Error: Tool '${toolName}' not found`}, parentThought);
        }

        // --- Parameter Validation ---
        let validationError: string | null = null;
        if (this.config.enableSchemaValidation) {
            validationError = this.validateParams(params, definition.parameters);
        }
        if (validationError) {
            // ... (invalid params error handling - remains the same) ...
            const errorMsg = `Invalid parameters for tool '${toolName}': ${validationError}`;
            console.error(`ToolManager: ${errorMsg}`);
            return createThought(agentId, 'OUTCOME', A('error_invalid_params'), 'FAILED',
                {[Keys.ERROR]: errorMsg, [Keys.UI_CONTEXT]: `Error: Invalid params for '${toolName}'`}, parentThought);
        }

        // --- Execution (Wrap the actual tool execution in try/catch) ---
        try {
            const toolResult = await definition.tool.execute(params, parentThought, agentId);

            if (!toolResult || !toolResult.id || !toolResult.type || !toolResult.content || !toolResult.status) {
                throw new Error(`Tool '${toolName}' returned an invalid Thought object.`);
            }

            // --- Prepare final metadata ---
            let embedding: ReadonlyArray<number> | undefined = undefined;
            if (!toolResult.metadata[Keys.EMBEDDING] && toolResult.status !== 'FAILED' && this.llmTool) {
                try {
                    const generatedEmbedding = await this.llmTool.generateEmbedding(termToString(toolResult.content));
                    if (generatedEmbedding.length > 0) {
                        embedding = Object.freeze(generatedEmbedding);
                    }
                } catch (embedError) {
                    console.warn(`ToolManager: Failed to generate embedding for result of ${toolName}:`, embedError);
                }
            }

            const finalMetadataAdditions: Partial<Metadata> = { /* ... as before ... */
                [Keys.AGENT_ID]: agentId,
                [Keys.PARENT_ID]: parentThought.id,
                [Keys.ROOT_ID]: parentThought.metadata[Keys.ROOT_ID] ?? parentThought.id,
                [Keys.RELATED_IDS]: [parentThought.id],
                ...(embedding && {[Keys.EMBEDDING]: embedding}),
                ...(parentThought.metadata[Keys.WORKFLOW_ID] && { /* ... workflow context ... */}),
            };
            const finalMetadata = mergeMetadata(finalMetadataAdditions, toolResult.metadata);

            return Object.freeze({...toolResult, metadata: finalMetadata});

        } catch (error: unknown) {
            // --- Error Handling for Tool Execution Failure ---
            // This catch block now correctly handles errors from definition.tool.execute()
            // It has access to 'toolName' but NOT 'definition'.
            const errorMsg = error instanceof Error ? error.message : String(error);
            console.error(`ToolManager: Error executing tool "${toolName}":`, error); // Use toolName here
            return createThought(
                agentId, 'OUTCOME', A('error_tool_execution'), 'FAILED',
                {
                    [Keys.ERROR]: `Tool '${toolName}' failed: ${errorMsg}`, // Use toolName
                    [Keys.UI_CONTEXT]: `Failed: ${toolName}` // Use toolName
                },
                parentThought
            );
        }
    }

    /** Validates parameters against the tool's schema. Returns error message string or null if valid. */
    private validateParams(params: Readonly<Record<string, unknown>>, schema: Readonly<Record<string, ToolParameter>>): string | null {
        for (const paramName in schema) {
            const rule = schema[paramName];
            const value = params[paramName];

            // --- Evaluate 'required' condition ---
            let isRequired = false;
            if (typeof rule.required === 'function') {
                try {
                    // Call the function with the params object to determine requirement
                    isRequired = rule.required(params);
                } catch (e: any) {
                    console.error(`ToolManager: Error evaluating required function for param "${paramName}": ${e.message}`);
                    return `Internal validation error for parameter "${paramName}"`; // Fail validation on error
                }
            } else {
                // Treat boolean true as required
                isRequired = !!rule.required;
            }

            // --- Check required ---
            if (isRequired && (value === undefined || value === null)) {
                return `Missing required parameter "${paramName}" (${rule.description ?? ''})`;
            }

            // --- Check type if value is present ---
            if (value !== undefined && value !== null) {
                // Determine value type (handle array/term specifically)
                const valueType = Array.isArray(value) ? 'array'
                    : (typeof value === 'object' && value && 'kind' in value && ['atom', 'variable', 'structure', 'list'].includes(value.kind)) ? 'term'
                        : typeof value;

                const type = rule.type;

                if (type === 'term') {
                    if (valueType !== 'term') {
                        return `Parameter "${paramName}" must be a valid Term object (got ${typeof value})`;
                    }
                } else if (type === 'array') {
                    if (valueType !== 'array') {
                        return `Parameter "${paramName}" must be an array (got ${valueType})`;
                    }
                    if (rule.itemType && !(value as any[]).every(item => typeof item === rule.itemType)) {
                        return `All items in array "${paramName}" must be of type ${rule.itemType}`;
                    }
                } else if (valueType !== type && type !== 'object') { // Allow any non-null object if type is 'object'
                    // Allow number to satisfy string? No, keep strict for now.
                    return `Parameter "${paramName}" must be of type ${type} (got ${valueType})`;
                } else if (type === 'object' && valueType !== 'object') {
                    // If type is 'object', value must be an object (and not an array or null)
                    if (valueType === 'array') {
                        return `Parameter "${paramName}" must be an object (got ${valueType === 'array' ? 'array' : 'null'})`;
                    }
                    // Allow any non-null, non-array object if type is 'object'.
                    // TODO: Add nested schema validation here if needed.
                }
            }
        }
        // ... (extraneous parameter check remains optional) ...
        return null; // Validation passed
    }
}


export class LLMTool implements Tool {
    private readonly httpClient: HttpClient; // Assume HttpClient is imported/defined
    private readonly generateEndpoint: string;
    private readonly embedEndpoint: string;
    private readonly model: string;

    constructor(private readonly config: Config) {
        // Ensure this uses the actual HttpClient class
        this.httpClient = new HttpClient(config.ollamaApiBaseUrl);
        this.generateEndpoint = `/api/generate`;
        this.embedEndpoint = `/api/embeddings`;
        this.model = config.ollamaModel;
    }

    // --- LLM Tool Execution ---
    async execute(params: Readonly<Record<string, unknown>>, parentThought: Thought, agentId: UUID): Promise<Thought> {
        // Parameters assumed validated by ToolManager based on LLMToolDefinition
        const action = params['action'] as 'generate' | 'embed';
        const input = params['input'] as string;

        switch (action) {
            case 'generate':
                const targetTypeStr = params['type'] as string | undefined;
                // Ensure only valid Types are used
                const validTypes: Type[] = ['INPUT', 'GOAL', 'STRATEGY', 'OUTCOME', 'QUERY', 'RULE', 'TOOLS', 'WORKFLOW_STEP'];
                const targetType: Type = targetTypeStr && validTypes.includes(targetTypeStr.toUpperCase() as Type)
                    ? targetTypeStr.toUpperCase() as Type
                    : 'OUTCOME'; // Default result type
                const format = params['format'] as 'json' | 'text' | undefined ?? 'json'; // Default to JSON
                const toolDefs = params['tool_definitions'] as ToolDefinition[] | undefined; // For prompting with schemas

                let prompt = input;
                // Optional: Enhance prompt if tool definitions are provided
                if (toolDefs && toolDefs.length > 0) {
                    // Format tool definitions for the prompt
                    const schemaDescriptions = toolDefs.map(def =>
                            `${def.name}(${Object.entries(def.parameters).map(([pName, pDef]) => `${pName}: ${pDef.type}${pDef.required ? '' : '?'}`).join(', ')})`
                        // Optional: Add description: `: ${def.description}`
                    ).join('\n');
                    prompt += `\n\nAvailable tools:\n${schemaDescriptions}\nGenerate response ${format === 'json' ? 'in JSON format conforming to the expected output structure or required tool parameters' : ''}.`;
                }


                const requestBodyGen = {model: this.model, prompt, format, stream: false};
                // console.debug(`LLM Generate Request: ${JSON.stringify(requestBodyGen).substring(0, 300)}...`); // Log request
                const responseGen = await this.httpClient.post<{
                    response: string;
                    [key: string]: unknown
                }>(this.generateEndpoint, requestBodyGen);
                const resultText = responseGen?.response;
                if (!resultText) throw new Error('LLM response missing "response" field');
                // console.debug(`LLM Generate Response: ${resultText.substring(0, 300)}...`); // Log response

                const content = this.parseLmOutput(resultText, targetType, format);
                const uiContext = `LLM Gen (${targetType}): ${termToString(content).substring(0, 50)}...`;
                const resultMetadata: Partial<Metadata> = {[Keys.UI_CONTEXT]: uiContext};

                // Attempt to generate and add embedding immediately (embedding handled by ToolManager now)
                // try {
                //     const embedding = await this.generateEmbedding(termToString(content));
                //     if (embedding.length > 0) resultMetadata[MetadataKeys.EMBEDDING] = Object.freeze(embedding);
                // } catch (e) { console.warn("LLMTool: Failed embedding generated content", e); }


                // Let createThought handle freezing and base metadata
                return createThought(agentId, targetType, content, 'PENDING', resultMetadata, parentThought);

            case 'embed':
                const embedding = await this.generateEmbedding(input);
                if (embedding.length === 0) throw new Error("Embedding generation failed or returned empty.");

                // Embedding result is usually just DONE, doesn't need further processing.
                // Content indicates what was embedded for clarity.
                return createThought(agentId, 'OUTCOME', A(`embedded(${input.substring(0, 20)}...)`), 'DONE',
                    {
                        [Keys.EMBEDDING]: Object.freeze(embedding), // Store the embedding in metadata
                        [Keys.UI_CONTEXT]: 'Content embedded'
                    },
                    parentThought
                );
        }
    }

    // --- Embedding Generation ---
    async generateEmbedding(content: string): Promise<ReadonlyArray<number>> {
        try {
            if (!content?.trim()) {
                console.warn("LLMTool: Attempted to generate embedding for empty content.");
                return [];
            }
            const requestBody = {model: this.model, prompt: content};
            // console.debug(`LLM Embed Request for: "${content.substring(0, 100)}..."`);
            const response = await this.httpClient.post<{ embedding: number[] }>(this.embedEndpoint, requestBody);
            // console.debug(`LLM Embed Response received (length: ${response?.embedding?.length})`);
            return response?.embedding ? Object.freeze(response.embedding) : [];
        } catch (error: any) {
            // Log specific error if possible
            console.error(`LLMTool: Failed to generate embedding for content starting with "${content.substring(0, 50)}...":`, error.message || error);
            return []; // Return empty array on failure
        }
    }

    // --- Output Parsing ---
    private parseLmOutput(outputString: string, expectedType: Type, format: 'json' | 'text'): Term {
        if (format === 'text') {
            return A(outputString); // Treat raw text as an atom
        }

        // Attempt JSON parsing
        try {
            const json = JSON.parse(outputString);

            // Handle different expected types based on JSON structure
            // This logic needs to be robust and align with expected LLM output formats
            switch (expectedType) {
                case 'GOAL':
                case 'STRATEGY':
                case 'QUERY':
                case 'OUTCOME':
                case 'INPUT':
                case 'WORKFLOW_STEP':
                    // Expect {"name": "...", "args": [...]} or {"value": ...}
                    if (typeof json.name === 'string') {
                        const args = Array.isArray(json.args) ? json.args.map((arg: any) => this.jsonToTerm(arg)) : [];
                        return S(json.name, args);
                    } else if (json.value !== undefined) { // Allow simple values wrapped in 'value'
                        return this.jsonToTerm(json.value);
                    }
                    // Allow bare primitives if the entire JSON is just that primitive
                    if (typeof json !== 'object') {
                        return this.jsonToTerm(json);
                    }
                    console.warn(`LLMTool: Expected JSON structure {name, args} or {value} for ${expectedType}, got:`, outputString.substring(0, 200));
                    return A(outputString); // Fallback: wrap raw JSON string

                case 'RULE':
                    // Expect {"pattern": {...}, "action": {...}} where pattern/action are Term JSON
                    if (json.pattern && json.action && typeof json.pattern === 'object' && typeof json.action === 'object') {
                        try {
                            const patternTerm = this.jsonToTerm(json.pattern);
                            const actionTerm = this.jsonToTerm(json.action);
                            // Represent the rule definition itself as a structure
                            return S("rule_definition", [patternTerm, actionTerm]);
                        } catch (termParseError) {
                            throw new Error(`Failed to parse nested terms in RULE JSON: ${termParseError}`);
                        }
                    }
                    throw new Error('Expected JSON with "pattern" and "action" objects for RULE type');

                case 'TOOLS':
                    // Expect {"tools": [...]} (list of specs) or {"tool_call": {name, params}}
                    if (Array.isArray(json.tools)) {
                        // Represents a *list* of tool definitions/specs generated by LLM
                        const toolTerms = json.tools.map((toolSpec: any) => {
                            if (typeof toolSpec === 'object' && toolSpec !== null && toolSpec.name) {
                                // Simplified representation, assumes args describe the spec
                                // tool_spec(name, type, endpoint?, description?, params_schema?)
                                const args: Term[] = [A(String(toolSpec.name))];
                                if (toolSpec.type) args.push(A(String(toolSpec.type))); else args.push(A('unknown'));
                                if (toolSpec.endpoint) args.push(A(String(toolSpec.endpoint)));
                                // Add more args if needed based on LLM output format
                                return S("tool_spec", args);
                            }
                            return A(JSON.stringify(toolSpec)); // Fallback for unknown format
                        });
                        return L(toolTerms);
                    } else if (typeof json.tool_call === 'object' && json.tool_call !== null && json.tool_call.name) {
                        // Represents a single tool call generated by LLM
                        const toolName = String(json.tool_call.name);
                        const params = typeof json.tool_call.params === 'object' && json.tool_call.params !== null ? json.tool_call.params : {};
                        // Convert params object into params( key1(value1_term), key2(value2_term) )
                        const paramTerms = Object.entries(params).map(([key, value]) =>
                            S(key, [this.jsonToTerm(value)])
                        );
                        return S(toolName, [S("params", paramTerms)]);
                    }
                    throw new Error('Expected JSON with "tools" array or "tool_call" object for TOOLS type');

                default:
                    // Fallback for unexpected types - treat as atom
                    console.warn(`LLMTool: Parsing output for unexpected type ${expectedType}. Treating as Atom.`);
                    return A(outputString);
            }
        } catch (e: any) {
            console.warn(`LLMTool: Failed to parse LLM JSON output ("${outputString.substring(0, 100)}..."). Treating as Atom. Error:`, e.message);
            return A(outputString); // Fallback if JSON parsing fails
        }
    }

    /** Converts generic JSON value to a Term representation. */
    private jsonToTerm(x: any): Term {
        // Simplified - assumes ToolManager.extractParamsFromTerm handles complex objects later
        switch (typeof x) {
            case 'string':
                return A(x);
            case 'number':
                return A(x.toString());
            case 'boolean':
                return A(x.toString());
            case 'undefined':
                return A('undefined');
            case 'object':
                if (x === null) {
                    return A('null');
                }
                if (Array.isArray(x)) {
                    return L(x.map(el => this.jsonToTerm(el)));
                }
                // Heuristic: Check if it looks like a Term structure itself (e.g., nested in rule)
                let k = x.kind;
                let n = x.name;
                if (k && ['atom', 'variable', 'structure', 'list'].includes(k)) {
                    try { // Be careful with recursive parsing of potentially malformed data
                        if (k === 'atom' && typeof n === 'string') return A(n);
                        if (k === 'variable' && typeof n === 'string') return V(n);
                        if (k === 'structure' && typeof n === 'string' && Array.isArray(x.args)) {
                            return S(n, x.args.map((arg: any) => this.jsonToTerm(arg)));
                        }
                        if (k === 'list' && Array.isArray(x.elements)) {
                            return L(x.elements.map((el: any) => this.jsonToTerm(el)));
                        }
                    } catch (parseError: any) {
                        console.warn("LLMTool: Error parsing nested Term JSON, treating as Atom:", parseError.message);
                        // Fall through to treat as generic object Atom
                    }
                }
                // Heuristic: Check for common LLM output structure { name: string, args: any[] }
                if (typeof n === 'string' && Array.isArray(x.args)) {
                    return S(n, x.args.map((arg: any) => this.jsonToTerm(arg)));
                }
                // Default for complex objects: stringify into an Atom
                // This might lose structure needed by parameter extraction later.
                // A better approach might be needed if complex nested objects are common.
                return A(JSON.stringify(x));
            default: // symbol, function, bigint -> convert to string atom
                return A(String(x));
        }
    }
}

// Define ToolDefinition for LLMTool
class LLMToolDefinition implements ToolDefinition {
    readonly name = 'llm';
    readonly description = 'Interacts with the Large Language Model for text generation or embedding.';
    readonly parameters: Readonly<Record<string, ToolParameter>> = {
        action: {type: 'string', required: true, description: 'The action to perform: "generate" or "embed".'},
        input: {type: 'string', required: true, description: 'The input text/prompt for the action.'},
        type: {
            type: 'string',
            required: false,
            description: 'For "generate": The expected semantic Type of the resulting Thought (e.g., GOAL, STRATEGY, RULE).'
        },
        format: {
            type: 'string',
            required: false,
            description: 'For "generate": The expected output format ("json" or "text"). Default: json.'
        },
        tool_definitions: {
            type: 'array',
            required: false,
            description: 'For "generate": Optional list of available tool definitions to guide generation.'
        } // Note: Actual type is ToolDefinition[], but use 'array' for schema
    };
    readonly tool: Tool; // Instance of LLMTool

    constructor(config: Config, instance: LLMTool) {
        this.tool = instance; // Use the provided instance
    }
}

// --- Memory Tool ---
class MemoryTool implements Tool {
    constructor(
        private readonly memoryStore: MemoryStore,
        private readonly llmTool: LLMTool // Needed to generate embeddings
    ) {
    }

    async execute(params: Readonly<Record<string, unknown>>, parentThought: Thought, agentId: UUID): Promise<Thought> {
        // Assumes parameters validated by ToolManager based on MemoryToolDefinition
        const action = params['action'] as 'add' | 'search';

        switch (action) {
            case 'add':
                const content = params['content'] as string; // Required by schema
                const type = params['type'] as string ?? 'generic'; // Default type
                const metadata = typeof params['metadata'] === 'object' ? params['metadata'] as Partial<Metadata> : {};

                const embedding = await this.llmTool.generateEmbedding(content);
                if (embedding.length === 0) throw new Error(`MemoryTool "add": Failed to generate embedding for content.`);

                const entryId = generateUUID();
                const finalMetadata: Metadata = mergeMetadata(
                    { // Base metadata for memory entry
                        type: type,
                        timestamp: Date.now(),
                        agent_id: agentId,
                        source_thought_id: parentThought.id, // Link to the thought that triggered the add
                        root_id: parentThought.metadata[Keys.ROOT_ID] ?? parentThought.id,
                        [Keys.RELATED_IDS]: [parentThought.id] // Link memory entry back to source thought
                    },
                    metadata, // Allow overriding with provided metadata
                    // TODO: Add extracted entities here if implemented
                    // { [MetadataKeys.EXTRACTED_ENTITIES]: extractEntities(content) }
                );

                const entry: MemoryEntry = {id: entryId, embedding, content, metadata: finalMetadata};
                await this.memoryStore.add(entry); // Add to store

                // Result indicates success and links the memory entry
                return createThought(agentId, 'OUTCOME', A(`stored_memory(${entryId.substring(0, 6)})`), 'DONE',
                    {
                        [Keys.UI_CONTEXT]: `Stored to memory (${type})`,
                        [Keys.RELATED_IDS]: [entryId] // Link outcome to the new memory entry ID
                    },
                    parentThought
                );

            case 'search':
                const query = params['query'] as string; // Required by schema
                const limit = typeof params['limit'] === 'number' ? params['limit'] : DEFAULT_CONFIG.memorySearchLimit;
                // Use filter context provided in params
                const filterContext = typeof params['filterContext'] === 'object' ? params['filterContext'] as any : undefined;

                const queryEmbedding = await this.llmTool.generateEmbedding(query);
                if (queryEmbedding.length === 0) throw new Error(`MemoryTool "search": Failed to generate embedding for query.`);

                const results = await this.memoryStore.findSimilar(queryEmbedding, limit, filterContext);

                // Represent results as a ListTerm of Structures for structured processing downstream
                // memory_result(content_atom, id_atom, type_atom?)
                const resultTerms = results.map(r => S("memory_result", [
                    A(r.content),
                    A(r.id),
                    A(r.metadata?.type as string ?? 'unknown'), // Include type
                    // Potential: createAtom(JSON.stringify(r.metadata)) // Add full metadata if needed
                ]));
                const outcomeContent = results.length > 0 ? L(resultTerms) : A('no_memory_results');

                return createThought(agentId, 'OUTCOME', outcomeContent, 'DONE',
                    {
                        [Keys.UI_CONTEXT]: `Memory search results (${results.length})`,
                        // Link outcome to the retrieved memory entry IDs
                        [Keys.RELATED_IDS]: results.map(r => r.id)
                    },
                    parentThought
                );

            default:
                // Should not happen if validation is enabled
                throw new Error(`MemoryTool: Unexpected action "${action}"`);
        }
    }
}

// Define ToolDefinition for MemoryTool
class MemoryToolDefinition implements ToolDefinition {
    readonly name = 'memory';
    readonly description = 'Stores or retrieves information from the persistent MemoryStore using embeddings.';
    readonly parameters: Readonly<Record<string, ToolParameter>> = {
        action: {type: 'string', required: true, description: 'The action: "add" or "search".'},
        // Add params - required only if action is 'add'
        content: {
            type: 'string',
            required: (params: Record<string, any>) => params.action === 'add', // Check action param
            description: 'For "add": The text content to store.'
        },
        type: {
            type: 'string',
            required: false,
            description: 'For "add": The semantic type of the content (e.g., "goal", "fact", "trace"). Default: "generic".'
        },
        metadata: {
            type: 'object',
            required: false,
            description: 'For "add": Additional metadata (JSON object) to store with the entry.'
        },
        // Search params - required only if action is 'search'
        query: {
            type: 'string',
            required: (params: Record<string, any>) => params.action === 'search', // Check action param
            description: 'For "search": The query text to search for.'
        },
        limit: {type: 'number', required: false, description: 'For "search": Maximum number of results (default 5).'},
        filterContext: {
            type: 'object',
            required: false,
            description: 'For "search": Optional filters {requiredEntities?: string[], relatedToId?: UUID, requiredType?: string}.'
        }
    };
    readonly tool: Tool;

    constructor(memoryStore: MemoryStore, llmTool: LLMTool) {
        this.tool = new MemoryTool(memoryStore, llmTool);
    }
}

// --- Goal Proposal Tool ---
class GoalProposalTool implements Tool {
    constructor(
        private readonly memoryStore: MemoryStore,
        private readonly toolManager: ToolManager // Needs ToolManager to call LLM/Memory tools
    ) {
    }

    async execute(params: Readonly<Record<string, unknown>>, parentThought: Thought, agentId: UUID): Promise<Thought> {
        // 1. Gather context from memory
        const contextQuery = "Recent goals, plans, tasks, user inputs, or completed milestones";
        let contextText = "No relevant context found in memory.";
        let contextIds: UUID[] = [];
        try {
            // Use memory tool via ToolManager to get context
            const memParams = {action: 'search', query: contextQuery, limit: 5};
            // Create a temporary parent thought for the memory call if needed, or use the original parent? Use parent.
            const memResultThought = await this.toolManager.execute('memory', memParams, parentThought, agentId);

            if (memResultThought.status !== 'FAILED' && isL(memResultThought.content)) {
                const results = memResultThought.content.elements;
                contextText = results.map(term => {
                    // Extract content from memory_result(content, id, type)
                    if (isS(term) && term.name === 'memory_result' && term.args.length > 0 && isA(term.args[0])) {
                        if (term.args.length > 1 && isA(term.args[1])) contextIds.push(term.args[1].name); // Store ID
                        return `- ${term.args[0].name}`; // Extract content atom
                    }
                    return '';
                }).filter(Boolean).join('\n');
                if (!contextText) contextText = "No relevant context found in memory.";
            } else if (memResultThought.status === 'FAILED') {
                console.warn("GoalProposalTool: Memory search failed during context gathering:", memResultThought.metadata[Keys.ERROR]);
            }
        } catch (memError: any) {
            console.error("GoalProposalTool: Error retrieving context from memory:", memError.message);
        }

        // 2. Prompt LLM for suggestion
        const prompt = `Based on the recent activity and memory context below, suggest a single, relevant, actionable next goal or task for agent ${agentId.substring(0, 8)}.\nContext:\n${contextText}\n\nDesired Format: {"name": "suggested_goal_or_task_name", "args": ["arg1", "arg2"]}\n---\nSuggest Goal/Task:`;

        const llmParams = {action: 'generate', input: prompt, type: 'GOAL'}; // Suggest as a GOAL
        const llmResultThought = await this.toolManager.execute('llm', llmParams, parentThought, agentId);

        // 3. Process result
        if (llmResultThought.status === 'FAILED') {
            // Propagate the failure, ToolManager already created a FAILED thought
            console.error("GoalProposalTool: LLM failed to generate suggestion.");
            return llmResultThought;
        }

        // LLMTool should have parsed the output into the content Term
        const suggestedGoalTerm = llmResultThought.content;

        // Return a new INPUT thought representing the suggestion, ready for processing
        return createThought(agentId, 'INPUT', suggestedGoalTerm, 'PENDING', // Treat suggestion as INPUT
            {
                [Keys.UI_CONTEXT]: `Suggested: ${termToString(suggestedGoalTerm)}`,
                [Keys.PROVENANCE]: [ // Link suggestion back to the LLM thought and memory context
                    ...(Array.isArray(parentThought.metadata[Keys.PROVENANCE]) ? parentThought.metadata[Keys.PROVENANCE] as UUID[] : []),
                    llmResultThought.id,
                    ...contextIds // Include IDs of memory entries used as context
                ],
                [Keys.RELATED_IDS]: [llmResultThought.id, ...contextIds], // Also add to related
                [Keys.PRIORITY]: 0.8 // Give suggestions a decent priority
            },
            parentThought // Link to the thought that triggered the proposal
        );
    }
}

// Define ToolDefinition for GoalProposalTool
class GoalProposalToolDefinition implements ToolDefinition {
    readonly name = 'goal_proposal';
    readonly description = 'Suggests a relevant next goal or task based on recent activity and memory context.';
    readonly parameters: Readonly<Record<string, ToolParameter>> = {
        // No parameters needed to trigger the proposal generation itself.
        // Optional context could be passed, but it gathers its own for now.
    };
    readonly tool: Tool;

    constructor(memoryStore: MemoryStore, toolManager: ToolManager) {
        // Pass dependencies needed by the GoalProposalTool instance
        this.tool = new GoalProposalTool(memoryStore, toolManager);
    }
}

// --- User Interaction Tool ---
// This tool is stateful internally to manage pending prompts.
// Needs to be a singleton instance provided to the AgentLoop.
export class UserInteractionTool implements Tool {
    // Map: interactionRequestThoughtId -> PendingPrompt details
    private pendingPrompts = new Map<UUID, PendingPrompt>();

    // Note: Constructor is empty, state is internal. Instance is created in AgentLoop.

    async execute(params: Readonly<Record<string, unknown>>, parentThought: Thought, agentId: UUID): Promise<Thought> {
        // Assumes params are validated by ToolManager
        const promptText = params['prompt'] as string;
        const options = params['options'] as string[] | undefined;

        // Create a thought representing the *request* for interaction.
        // This thought goes into WAITING state immediately.
        const interactionRequestThought = createThought(
            agentId, 'STRATEGY', // Requesting input is often part of a strategy
            S("request_user_input", [A(promptText)]),
            'WAITING', // Set to WAITING until the user responds
            {
                [Keys.UI_CONTEXT]: `Prompt: ${promptText}`,
                // Store necessary info directly in metadata for UI rendering & potential reload
                'interaction_details': {promptText, options}
            },
            parentThought // Link to the parent that needs the input
        );

        // Store the resolution logic associated with this request thought's ID
        // This promise isn't typically awaited externally, but handles internal state.
        const interactionPromise = new Promise<Thought>((resolve, reject) => {
            this.pendingPrompts.set(interactionRequestThought.id, {
                // Store minimal needed info to handle response/cancellation
                parentId: parentThought.id,
                agentId,
                thoughtId: interactionRequestThought.id, // ID of the request thought
                resolve, // Function to call when response is received (maybe unused)
                reject, // Function to call on timeout/cancellation
                parentThought: parentThought // Keep the original parent thought for context
            });
        });
        // Associate the promise with the map entry for potential timeout/cancellation logic
        this.pendingPrompts.get(interactionRequestThought.id)!.promise = interactionPromise;


        // IMPORTANT: Return the WAITING request thought immediately.
        // The AgentLoop/Worker should NOT process WAITING thoughts further.
        // The actual user input will arrive asynchronously via handleResponse.
        return interactionRequestThought;
    }

    /** Called by the UI/external system when a user responds to a prompt. */
    handleResponse(interactionRequestId: UUID, userResponse: string, thoughtStore: ThoughtStore): void {
        const pending = this.pendingPrompts.get(interactionRequestId);
        if (!pending) {
            console.warn(`UserInteractionTool: Received response for unknown or completed prompt ID: ${interactionRequestId}`);
            return;
        }
        const {agentId, resolve, reject, parentThought} = pending;
        this.pendingPrompts.delete(interactionRequestId); // Remove from pending list

        // --- Create the new INPUT thought containing the user's actual response ---
        const responseThought = createThought(
            agentId, 'INPUT', // User response is INPUT
            A(userResponse),
            'PENDING', // Ready for processing
            {
                [Keys.UI_CONTEXT]: `User Response: ${userResponse.substring(0, 50)}${userResponse.length > 50 ? '...' : ''}`,
                'answered_prompt_id': interactionRequestId, // Link back to the prompt
                [Keys.RELATED_IDS]: [interactionRequestId], // Link response to request
                [Keys.PRIORITY]: 1.0 // Give user responses decent priority
            },
            parentThought // Link this INPUT back to the original requester
        );

        // Add the new response thought to the store for processing by workers
        thoughtStore.add(responseThought);

        // --- Mark the original request thought as DONE ---
        const requestThought = thoughtStore.get(interactionRequestId);
        if (requestThought && requestThought.status === 'WAITING') {
            // Use updateThought for atomic update
            updateThought(thoughtStore, requestThought, 'DONE', {
                [Keys.UI_CONTEXT]: `Responded: ${userResponse.substring(0, 50)}...`,
                'response_thought_id': responseThought.id, // Link request to the response thought
                [Keys.RELATED_IDS]: [responseThought.id] // Also link request -> response
            });
        } else {
            console.warn(`UserInteractionTool: Could not find or update original WAITING request thought ${interactionRequestId} to DONE.`);
        }

        console.log(`UserInteractionTool: Processed response for ${interactionRequestId}, created thought ${responseThought.id}`);
        // resolve(responseThought); // Resolve the promise if needed
    }

    /** Retrieves details for prompts currently waiting for user input from ThoughtStore. */
    getPendingPromptsForUI(thoughtStore: ThoughtStore): Array<{ id: UUID; prompt: string; options?: string[] }> {
        const prompts: Array<{ id: UUID; prompt: string; options?: string[] }> = [];
        // Iterate over known pending prompts AND check the thought store for WAITING interaction requests
        const waitingThoughts = thoughtStore.getAllThoughts().filter(t =>
            t.status === 'WAITING' &&
            isS(t.content) && t.content.name === 'request_user_input'
        );

        waitingThoughts.forEach(thought => {
            // Add to internal map if missing (e.g., after loading state)
            if (!this.pendingPrompts.has(thought.id)) {
                console.warn(`UI Tool: Found WAITING prompt ${thought.id} not in internal map. Reconstructing.`);
                // Need parent thought to reconstruct fully - this is tricky.
                // For UI, just extract details from metadata.
                const parentId = thought.metadata[Keys.PARENT_ID] as UUID | undefined;
                // We can't easily get the reject/resolve functions here. Cancellation might not work for loaded prompts.
                // this.pendingPrompts.set(thought.id, { /* partial reconstruction */ });
            }

            const details = thought.metadata['interaction_details'] as {
                promptText: string;
                options?: string[]
            } | undefined;
            if (details) {
                prompts.push({id: thought.id, prompt: details.promptText, options: details.options});
            } else if (isS(thought.content) && thought.content.args.length > 0 && isA(thought.content.args[0])) {
                // Fallback: try to get prompt text from content if metadata missing
                prompts.push({id: thought.id, prompt: thought.content.args[0].name, options: undefined});
            }
        });

        // Clean up internal map if thought is no longer WAITING in store
        this.pendingPrompts.forEach((pending, id) => {
            if (!waitingThoughts.some(t => t.id === id)) {
                console.warn(`UI Tool: Cleaning up stale internal pending prompt ${id}`);
                this.pendingPrompts.delete(id);
                // pending.reject(new Error("Prompt cancelled due to state inconsistency.")); // Reject associated promise
            }
        });

        return prompts;
    }

    /** Cancels a pending prompt, marking the request thought as FAILED. */
    cancelPrompt(interactionRequestId: UUID, reason: string, thoughtStore: ThoughtStore): void {
        const pending = this.pendingPrompts.get(interactionRequestId);
        this.pendingPrompts.delete(interactionRequestId); // Remove from internal map regardless

        // Mark the original request thought as FAILED in the store
        const requestThought = thoughtStore.get(interactionRequestId);
        if (requestThought && requestThought.status === 'WAITING') {
            const updated = updateThought(thoughtStore, requestThought, 'FAILED', {
                [Keys.ERROR]: `User interaction cancelled: ${reason}`,
                [Keys.UI_CONTEXT]: `Interaction Cancelled: ${reason}`,
                'cancelled_prompt_id': interactionRequestId,
            });
            if (updated && pending?.reject) {
                // pending.reject(new Error(`Interaction cancelled: ${reason}`)); // Reject promise
            } else if (!updated) {
                console.warn(`UserInteractionTool: Failed to mark request thought ${interactionRequestId} FAILED on cancellation (concurrent modification?).`);
            }
        } else {
            console.warn(`UserInteractionTool: Could not find WAITING request thought ${interactionRequestId} to cancel.`);
        }
    }
}

// Define internal type for UserInteractionTool state (minimal version)
type PendingPrompt = {
    parentId: UUID;
    agentId: UUID;
    thoughtId: UUID; // The ID of the interaction request thought
    resolve: (value: Thought) => void;
    reject: (reason?: any) => void;
    parentThought: Thought;
    promise?: Promise<Thought>; // Optional reference to the promise itself
};

// Define ToolDefinition for UserInteractionTool
export class UserInteractionToolDefinition implements ToolDefinition {
    readonly name = 'user_interaction';
    readonly description = 'Prompts the user for input via the UI and waits for a response.';
    readonly parameters: Readonly<Record<string, ToolParameter>> = {
        prompt: {type: 'string', required: true, description: 'The text to display to the user.'},
        options: {
            type: 'array',
            itemType: 'string',
            required: false,
            description: 'Optional list of predefined choices for the user.'
        }
    };
    readonly tool: UserInteractionTool; // Must be a single shared instance

    // Constructor accepts the singleton instance created in AgentLoop
    constructor(instance: UserInteractionTool) {
        if (!(instance instanceof UserInteractionTool)) {
            throw new Error("UserInteractionToolDefinition requires an instance of UserInteractionTool");
        }
        this.tool = instance;
    }
}


// --- Web Search Tool --- (Example Dynamic/Optional Tool)
class WebSearchTool implements Tool {
    private readonly httpClient: HttpClient;
    private readonly endpoint: string;

    constructor(endpoint: string) { // Endpoint provided at registration time
        if (!endpoint) throw new Error("WebSearchTool requires a valid endpoint URL.");
        this.endpoint = endpoint;
        this.httpClient = new (global as any).HttpClient(); // Use global mock or real client
    }

    async execute(params: Readonly<Record<string, unknown>>, parentThought: Thought, agentId: UUID): Promise<Thought> {
        // Assumes validation happened in ToolManager
        const query = params['query'] as string;

        const url = new URL(this.endpoint);
        url.searchParams.set('q', query); // Example: using query parameter 'q'
        console.log(`WebSearchTool: Querying ${url.toString()}`);

        // Use try-catch within the tool for specific network/API errors
        try {
            const responseText = await this.httpClient.get<string>(url.toString());
            const resultText = responseText ?? 'No content received from web search';
            console.log(`WebSearchTool: Received response (length: ${resultText.length})`);

            // Result is INPUT, ready for analysis by other rules/LLM
            return createThought(agentId, 'INPUT', A(resultText), 'PENDING',
                {
                    [Keys.UI_CONTEXT]: `Web Search Result for: "${query}"`,
                    'search_query': query // Add query to metadata
                },
                parentThought
            );
        } catch (error: any) {
            console.error(`WebSearchTool: Failed to execute search for query "${query}":`, error);
            // Throw error to be caught by ToolManager, which creates the FAILED thought
            throw new Error(`Web search failed: ${error.message}`);
        }
    }
}

// ToolDefinition for WebSearchTool (used if registered dynamically or statically)
class WebSearchToolDefinition implements ToolDefinition {
    readonly name = 'web_search';
    readonly description = 'Performs a web search using a configured external API endpoint.';
    readonly parameters: Readonly<Record<string, ToolParameter>> = {
        query: {type: 'string', required: true, description: 'The search query text.'}
    };
    readonly tool: Tool;

    constructor(endpoint: string) {
        this.tool = new WebSearchTool(endpoint);
    }
}


/** Action Execution (Handles Rules & Workflows) */
export class ActionExecutor {
    constructor(
        private readonly thoughtStore: ThoughtStore,
        private readonly ruleStore: RuleStore,
        private readonly toolManager: ToolManager,
        private readonly memoryStore: MemoryStore, // For memorizing execution traces
        private readonly agentId: UUID,
        private readonly config: Config,
        private readonly llmTool: LLMTool // Needed for potential dynamic embedding/entity extraction
    ) {
    }

    /**
     * Executes the action defined by a matched rule OR handles a workflow step.
     * `matchedThought` is the thought that triggered this execution (must be the version from the store).
     * `ruleOrWorkflow` is the Rule or the WORKFLOW_STEP Thought containing the workflow definition.
     * `bindings` are from the initial unification (if triggered by a rule).
     */
    async executeAction(
        matchedThought: Thought, // The ACTIVE thought being processed
        ruleOrWorkflow: Rule | Thought,
        bindings: Bindings // Only relevant if ruleOrWorkflow is a Rule
    ): Promise<void> {

        let actionTerm: Term;
        let executingRule: Rule | null = null;
        let workflowContext: Partial<Metadata> = {}; // Holds ID and step for workflows

        // Determine if we're executing a rule action or a workflow step
        if ('pattern' in ruleOrWorkflow && 'action' in ruleOrWorkflow) { // It's a Rule
            executingRule = ruleOrWorkflow;
            actionTerm = apply(executingRule.action, bindings);
            // Add rule ID to provenance
            workflowContext[Keys.PROVENANCE] = [
                ...(matchedThought.metadata[Keys.PROVENANCE] as UUID[] ?? []),
                executingRule.id
            ];
        } else if (ruleOrWorkflow.type === 'WORKFLOW_STEP') { // It's a Thought representing a workflow step
            actionTerm = ruleOrWorkflow.content; // The workflow definition is the content
            workflowContext = {
                [Keys.WORKFLOW_ID]: ruleOrWorkflow.metadata[Keys.WORKFLOW_ID],
                [Keys.WORKFLOW_STEP]: ruleOrWorkflow.metadata[Keys.WORKFLOW_STEP],
                [Keys.WORKFLOW_RESULTS]: ruleOrWorkflow.metadata[Keys.WORKFLOW_RESULTS], // Carry forward results
                [Keys.PROVENANCE]: ruleOrWorkflow.metadata[Keys.PROVENANCE] // Inherit provenance
            };
            // Note: The 'matchedThought' here is the WORKFLOW_STEP thought itself.
            // The original trigger is its parent.
        } else {
            console.error(`ActionExecutor: Invalid object passed as ruleOrWorkflow. ID: ${ruleOrWorkflow.id}, Type: ${ruleOrWorkflow.type}`);
            updateThought(this.thoughtStore, matchedThought, 'FAILED', {[Keys.ERROR]: 'Invalid trigger for action execution'}, this.config);
            return;
        }

        // --- Handle different action term types ---
        if (isS(actionTerm)) {
            const actionName = actionTerm.name;
            const args = actionTerm.args;

            try {
                // Check for Workflow Control Structures
                if (actionName === 'sequence' || actionName === 'chain') {
                    await this.executeSequenceWorkflow(matchedThought, args, workflowContext, executingRule);
                } else if (actionName === 'parallel') {
                    await this.executeParallelWorkflow(matchedThought, args, workflowContext, executingRule);
                }
                // Handle standard Tool Call
                else {
                    await this.executeToolAction(matchedThought, actionTerm, workflowContext, executingRule);
                }
            } catch (executionError: any) {
                console.error(`ActionExecutor: Error during execution of action "${actionName}" for thought ${matchedThought.id}:`, executionError);
                // Fail the thought that triggered the action
                updateThought(this.thoughtStore, matchedThought, 'FAILED', {
                    [Keys.ERROR]: `Action execution failed: ${executionError.message}`,
                    [Keys.UI_CONTEXT]: `Action failed: ${actionName}`
                }, this.config);
                // Optionally memorize the failure trace
                await this.memorizeExecution(matchedThought, executingRule, null, false, executionError.message);
            }

        } else {
            // Action term is not a structure (e.g., just an Atom or ListTerm) - invalid action format
            const errorMsg = `Invalid action term type. Expected Structure, got ${actionTerm.kind}. Term: ${termToString(actionTerm)}`;
            console.error(`ActionExecutor [Thought: ${matchedThought.id}]: ${errorMsg}`);
            updateThought(this.thoughtStore, matchedThought, 'FAILED', {[Keys.ERROR]: errorMsg}, this.config);
        }
    }

    /** Executes a single tool call defined by an action term. */
    private async executeToolAction(
        triggeringThought: Thought, // The thought being processed (status: ACTIVE)
        actionTerm: Structure, // e.g., toolName(params(...))
        execContext: Partial<Metadata>, // Includes provenance, workflow context
        triggeringRule: Rule | null // The rule that led to this action, if any
    ): Promise<void> {
        const toolName = actionTerm.name;
        const params = this.extractParamsFromTerm(actionTerm.args); // Extract JS params

        // Execute the tool via ToolManager
        const resultThought = await this.toolManager.execute(toolName, params, triggeringThought, this.agentId);

        // Add the result thought to the store
        // ToolManager adds base metadata, createThought freezes.
        this.thoughtStore.add(resultThought);

        // --- Post-Tool-Execution Handling ---
        let finalStatusForTriggeringThought: Status = 'WAITING'; // Default: wait for result processing
        let finalMetadataUpdate: Partial<Metadata> = {
            // Link the triggering thought to the result
            [Keys.RELATED_IDS]: [resultThought.id], // Merge handled by updateThought
            // Update UI context based on action outcome
            [Keys.UI_CONTEXT]: `${toolName} -> ${resultThought.status} (${resultThought.id.substring(0, 6)})`,
            // Carry over provenance from context
            [Keys.PROVENANCE]: execContext[Keys.PROVENANCE] as UUID[] ?? undefined,
        };

        // 1. Handle Rule Synthesis (LLM generated a rule)
        if (resultThought.type === 'RULE' && isS(resultThought.content) && resultThought.content.name === 'rule_definition') {
            await this.handleRuleSynthesis(resultThought, triggeringThought);
            finalStatusForTriggeringThought = 'DONE'; // Rule synthesis was the goal
            finalMetadataUpdate[Keys.UI_CONTEXT] = `Synthesized rule from ${resultThought.id}`;
        }
        // 2. Handle Tool Registration (LLM generated tool specs)
        else if (resultThought.type === 'TOOLS' && isL(resultThought.content)) {
            const registeredCount = await this.handleToolRegistration(resultThought);
            finalStatusForTriggeringThought = 'DONE'; // Tool registration was the goal
            finalMetadataUpdate[Keys.UI_CONTEXT] = `Registered ${registeredCount} tools from ${resultThought.id}`;
        }
        // 3. Handle Tool Failure
        else if (resultThought.status === 'FAILED') {
            finalStatusForTriggeringThought = 'FAILED'; // Action failed
            finalMetadataUpdate[Keys.ERROR] = resultThought.metadata[Keys.ERROR] ?? `Tool '${toolName}' execution failed`;
        }
        // 4. Handle User Interaction Request (special case: original thought waits)
        else if (resultThought.status === 'WAITING' && toolName === 'user_interaction') {
            finalStatusForTriggeringThought = 'WAITING'; // Explicitly wait for user response
            finalMetadataUpdate[Keys.UI_CONTEXT] = `Waiting for user input (Prompt: ${resultThought.id.substring(0, 6)})`;
        }
        // 5. Standard Success -> Triggering thought usually WAITS for the outcome/result thought to be processed.
        else if (resultThought.status === 'DONE' || resultThought.status === 'PENDING') {
            finalStatusForTriggeringThought = 'WAITING'; // Wait for result to be processed or reach terminal state
        }
        // Handle unexpected result status? Default to WAITING.
        else {
            finalStatusForTriggeringThought = 'WAITING';
        }


        // Update the original thought that triggered the action
        // Pass config for belief update logic
        updateThought(this.thoughtStore, triggeringThought, finalStatusForTriggeringThought, finalMetadataUpdate, this.config);

        // Memorize successful execution trace
        if (finalStatusForTriggeringThought !== 'FAILED' && resultThought.status !== 'FAILED') {
            await this.memorizeExecution(triggeringThought, triggeringRule, resultThought, true);
        } else {
            // Optionally memorize failure trace here if not handled by overall execution error
            // await this.memorizeExecution(triggeringThought, triggeringRule, resultThought, false, finalMetadataUpdate[MetadataKeys.ERROR] as string);
        }
    }

    /** Executes steps in a sequence workflow defined by `steps`. */
    private async executeSequenceWorkflow(
        triggeringThought: Thought, // The thought that matched the rule defining the sequence, or the previous WORKFLOW_STEP thought
        steps: ReadonlyArray<Term>, // The list of action terms in the sequence
        execContext: Partial<Metadata>, // Contains Workflow ID, Step#, Provenance
        triggeringRule: Rule | null // Rule that initiated the workflow (if any)
    ): Promise<void> {
        if (steps.length === 0) {
            // Sequence finished successfully
            const workflowId = execContext[Keys.WORKFLOW_ID] ?? triggeringThought.id;
            console.log(`Workflow ${workflowId} sequence completed.`);
            updateThought(this.thoughtStore, triggeringThought, 'DONE', {
                [Keys.UI_CONTEXT]: 'Workflow sequence completed',
                [Keys.PROVENANCE]: execContext[Keys.PROVENANCE] as UUID[] ?? undefined, // Persist final provenance
            }, this.config);
            // TODO: Memorize workflow completion?
            return;
        }

        // --- Prepare for the next step ---
        const currentStepActionTerm = steps[0]; // The action for the current step
        const remainingSteps = steps.slice(1); // Actions for subsequent steps
        const workflowId = (execContext[Keys.WORKFLOW_ID] ?? generateUUID()) as string; // Generate ID if first step
        const currentStepIndex = Number(execContext[Keys.WORKFLOW_STEP] ?? 0);

        if (!isS(currentStepActionTerm)) {
            const errorMsg = `Invalid step type in sequence workflow ${workflowId}. Expected Structure, got ${currentStepActionTerm.kind}.`;
            console.error(`ActionExecutor: ${errorMsg}`);
            updateThought(this.thoughtStore, triggeringThought, 'FAILED', {[Keys.ERROR]: errorMsg}, this.config);
            return;
        }

        // --- Create the WORKFLOW_STEP thought for the *next* step ---
        // This thought holds the definition of the *rest* of the sequence.
        let nextStepThought: Thought | null = null;
        if (remainingSteps.length > 0) {
            const nextStepContent = S('sequence', remainingSteps); // Content defines the rest of the workflow
            const nextStepMetadata: Partial<Metadata> = {
                [Keys.WORKFLOW_ID]: workflowId,
                [Keys.WORKFLOW_STEP]: currentStepIndex + 1,
                [Keys.UI_CONTEXT]: `Workflow ${workflowId.substring(0, 6)} step ${currentStepIndex + 1}/${currentStepIndex + remainingSteps.length}`,
                [Keys.PROVENANCE]: execContext[Keys.PROVENANCE] as UUID[] ?? undefined, // Carry provenance
                // Optional: Pass result of current step to next step via metadata? Needs careful design.
                // [MetadataKeys.WORKFLOW_RESULTS]: {...execContext[MetadataKeys.WORKFLOW_RESULTS], [`step_${currentStepIndex}_result`]: resultData},
                [Keys.PRIORITY]: triggeringThought.metadata[Keys.PRIORITY] // Inherit priority
            };

            nextStepThought = createThought(
                this.agentId, 'WORKFLOW_STEP', nextStepContent, 'PENDING', // Next step is PENDING
                nextStepMetadata,
                triggeringThought // Parent is the current step's thought (or initial trigger)
            );
            this.thoughtStore.add(nextStepThought);
        } // else: This was the last step, no nextStepThought needed.

// --- Execute the *current* step's action ---
// We treat the execution of the current step like any other tool action.
// The 'triggeringThought' for this action is the thought we are currently processing.
        await this.executeToolAction(triggeringThought, currentStepActionTerm, execContext, triggeringRule);

// --- Update the triggering/current thought ---
// The status depends on whether the tool action succeeded and if there's a next step.
        const currentTriggerThoughtState = this.thoughtStore.get(triggeringThought.id);
        if (currentTriggerThoughtState?.status === 'ACTIVE') { // Check if it wasn't already failed by executeToolAction
            if (nextStepThought) {
                // If there's a next step, the current thought waits for it.
                updateThought(this.thoughtStore, currentTriggerThoughtState, 'WAITING', {
                    [Keys.UI_CONTEXT]: `Workflow step ${currentStepIndex} done, waiting for next (${nextStepThought.id.substring(0, 6)})`,
                    [Keys.RELATED_IDS]: [nextStepThought.id] // Link to the next step
                }, this.config);
            } else {
                // This was the last step, and executeToolAction didn't fail it, so mark DONE.
                updateThought(this.thoughtStore, currentTriggerThoughtState, 'DONE', {
                    [Keys.UI_CONTEXT]: `Workflow final step ${currentStepIndex} completed.`
                }, this.config);
                // Memorize completion here? Or handled by checkAndCompleteParent?
                // Let checkAndCompleteParent handle hierarchy completion.
                await this.checkAndCompleteParent(currentTriggerThoughtState); // See if this completes the parent workflow trigger
            }
        }
// If executeToolAction failed the thought, its status is already FAILED.
    }

    /** Executes workflow steps in parallel. */
    private async executeParallelWorkflow(
        triggeringThought: Thought, // The thought defining the parallel block
        steps: ReadonlyArray<Term>, // The list of action terms to run in parallel
        execContext: Partial<Metadata>, // Contains Workflow ID, Step#, Provenance
        triggeringRule: Rule | null
    ): Promise<void> {
        if (steps.length === 0) {
            console.log(`Parallel workflow for ${triggeringThought.id} has no steps. Marking DONE.`);
            updateThought(this.thoughtStore, triggeringThought, 'DONE', {[Keys.UI_CONTEXT]: 'Empty parallel workflow completed'}, this.config);
            return;
        }

        const workflowId = (execContext[Keys.WORKFLOW_ID] ?? generateUUID()) as string;
        const parentStepIndex = Number(execContext[Keys.WORKFLOW_STEP] ?? -1); // Step index of the parallel block itself
        console.log(`Starting parallel workflow block ${workflowId} (parent step ${parentStepIndex}) with ${steps.length} tasks.`);
        const childThoughtIds: UUID[] = [];

// Launch all steps concurrently by creating PENDING thoughts for each
        for (let i = 0; i < steps.length; i++) {
            const stepTerm = steps[i];
            if (!isS(stepTerm)) {
                console.error(`ActionExecutor: Invalid step type in parallel workflow ${workflowId}. Expected Structure, got ${stepTerm.kind}. Skipping.`);
                continue;
            }

            const stepMetadata: Partial<Metadata> = {
                [Keys.WORKFLOW_ID]: workflowId,
                // Identify step within the parallel block (e.g., parentStepIndex.subStepIndex)
                [Keys.WORKFLOW_STEP]: `${parentStepIndex >= 0 ? parentStepIndex : 'p'}.${i}`,
                [Keys.UI_CONTEXT]: `Parallel task [${i}]: ${stepTerm.name}`,
                [Keys.PROVENANCE]: execContext[Keys.PROVENANCE] as UUID[] ?? undefined, // Inherit provenance
                [Keys.PRIORITY]: triggeringThought.metadata[Keys.PRIORITY] // Inherit priority
            };

            // Create thought representing the *action* to be taken for this parallel step
            // This thought will be picked up by a worker and executed via executeToolAction
            const parallelStepThought = createThought(
                this.agentId, 'STRATEGY', // Parallel steps are like strategies launched by the workflow
                stepTerm, // Content is the tool call structure
                'PENDING',
                stepMetadata,
                triggeringThought // Parent is the thought that defined the parallel block
            );
            this.thoughtStore.add(parallelStepThought);
            childThoughtIds.push(parallelStepThought.id);
        }

// Mark the triggering thought (which defines the parallel block) as WAITING
// It will be marked DONE only when checkAndCompleteParent determines all its children (the parallelStepThoughts) are DONE.
        updateThought(this.thoughtStore, triggeringThought, 'WAITING', {
            [Keys.UI_CONTEXT]: `Waiting for ${steps.length} parallel tasks (Workflow ${workflowId.substring(0, 6)}, Step ${parentStepIndex})`,
            [Keys.RELATED_IDS]: childThoughtIds // Link parent to all child tasks
        }, this.config);
// Memorization happens individually as each parallel step completes via executeToolAction.
    }

// --- Helper Methods ---

    /** Handles synthesized rule from LLM result */
    private async handleRuleSynthesis(resultThought: Thought, triggeringThought: Thought): Promise<void> {
        // Ensure result thought is the current version
        const currentResult = this.thoughtStore.get(resultThought.id) ?? resultThought;
        if (currentResult.status !== 'PENDING' && currentResult.status !== 'ACTIVE') {
            console.warn(`Rule synthesis skipped for ${resultThought.id}, status is ${currentResult.status}`);
            return; // Already processed or failed
        }

        if (!isS(currentResult.content) || currentResult.content.name !== 'rule_definition' || currentResult.content.args.length !== 2) {
            console.error("ActionExecutor: Malformed RULE thought content:", currentResult.content);
            updateThought(this.thoughtStore, currentResult, 'FAILED', {[Keys.ERROR]: 'Malformed rule definition term'}, this.config);
            return;
        }
        const [patternTerm, actionTermForRule] = currentResult.content.args;
        try {
            const newRule: Rule = Object.freeze({
                id: generateUUID(),
                pattern: patternTerm,
                action: actionTermForRule,
                belief: {...DEFAULT_BELIEF, lastUpdated: Date.now()}, // Start with default belief
                metadata: mergeMetadata(
                    triggeringThought.metadata, // Inherit metadata like root_id
                    {
                        [Keys.PROVENANCE]: [currentResult.id, triggeringThought.id], // Link to thoughts
                        [Keys.TIMESTAMP]: Date.now(),
                        [Keys.AGENT_ID]: this.agentId,
                        [Keys.UI_CONTEXT]: `Synthesized Rule triggered by ${triggeringThought.id.substring(0, 6)}`,
                        // Remove fields not applicable to rules
                        [Keys.PARENT_ID]: undefined,
                        [Keys.WORKFLOW_ID]: undefined,
                        [Keys.WORKFLOW_STEP]: undefined,
                        [Keys.ERROR]: undefined,
                        [Keys.RETRY_COUNT]: undefined,
                    }
                )
            });
            await this.ruleStore.add(newRule); // Add rule (also generates embedding)
            console.log(`ActionExecutor: Synthesized and added new rule ${newRule.id}`);
            // Mark the result thought as DONE
            updateThought(this.thoughtStore, currentResult, 'DONE', {[Keys.UI_CONTEXT]: `Rule ${newRule.id} synthesized`}, this.config);
        } catch (error: any) {
            console.error("ActionExecutor: Failed to create or add synthesized rule:", error);
            updateThought(this.thoughtStore, currentResult, 'FAILED', {[Keys.ERROR]: `Failed to add synthesized rule: ${error.message}`}, this.config);
        }
    }

    /** Handles dynamic tool registration from LLM result */
    private async handleToolRegistration(resultThought: Thought): Promise<number> {
        // Ensure result thought is the current version
        const currentResult = this.thoughtStore.get(resultThought.id) ?? resultThought;
        if (currentResult.status !== 'PENDING' && currentResult.status !== 'ACTIVE') {
            if (!isL(currentResult.content)) { /* ... error handling ... */
                return 0;
            }

            let toolsRegistered = 0;
            for (const toolSpecTerm of currentResult.content.elements) {
                // Expecting: tool_spec(NameAtom, TypeAtom, EndpointAtom?, ...)
                if (isS(toolSpecTerm) && toolSpecTerm.name === 'tool_spec' && toolSpecTerm.args.length >= 2 &&
                    isA(toolSpecTerm.args[0]) && isA(toolSpecTerm.args[1])) {
                    const name = toolSpecTerm.args[0].name;
                    const type = toolSpecTerm.args[1].name; // Type suggested by LLM
                    const endpoint = (toolSpecTerm.args.length > 2 && isA(toolSpecTerm.args[2])) ? toolSpecTerm.args[2].name : '';
                    const description = (toolSpecTerm.args.length > 3 && isA(toolSpecTerm.args[3])) ? toolSpecTerm.args[3].name : `Dynamically registered: ${name}`;
                    const parameters: Record<string, ToolParameter> = {}; // TODO: Parse schema if provided

                    try {
                        let toolInstance: Tool | null = null;
                        const lowerCaseType = type.toLowerCase(); // Normalize for matching

                        // --- Updated Type Matching ---
                        // Handle various web search descriptions
                        if ((lowerCaseType.includes('search') || lowerCaseType.includes('web')) && endpoint) {
                            console.log(`ActionExecutor: Registering tool "${name}" as WebSearchTool (type: "${type}")`);
                            toolInstance = new WebSearchTool(endpoint);
                        }
                        // Add placeholders for other potential types or generic handlers
                        else if (lowerCaseType.includes('nlp') && endpoint) {
                            console.warn(`ActionExecutor: NLP tool type "${type}" for "${name}" not yet implemented. Endpoint: ${endpoint}`);
                            // toolInstance = new NlpApiTool(endpoint); // If such a tool existed
                        } else if (lowerCaseType.includes('dictionary') || lowerCaseType.includes('thesaurus') || lowerCaseType.includes('word') && endpoint) {
                            console.warn(`ActionExecutor: Dictionary/Word tool type "${type}" for "${name}" not yet implemented. Endpoint: ${endpoint}`);
                            // toolInstance = new DictionaryTool(endpoint); // If such a tool existed
                        }
                        // Add more known types or a generic API tool handler here if desired
                        else {
                            console.warn(`ActionExecutor: Unknown or unsupported dynamic tool type "${type}" for tool "${name}". Endpoint: ${endpoint || 'N/A'}`);
                        }
                        // --------------------------

                        if (toolInstance) {
                            const definition: ToolDefinition = {name, description, parameters, tool: toolInstance};
                            this.toolManager.registerToolDefinition(definition);
                            // console.log(`ActionExecutor: Dynamically registered tool "${name}" of type "${type}"`); // Already logged above
                            toolsRegistered++;
                        }
                    } catch (err: any) {
                        console.error(`ActionExecutor: Failed to instantiate or register dynamic tool "${name}":`, err.message);
                    }
                } else {
                    console.warn("ActionExecutor: Malformed tool specification term in TOOLS list:", termToString(toolSpecTerm));
                }
            }

            updateThought(this.thoughtStore, currentResult, 'DONE', {[Keys.UI_CONTEXT]: `Processed ${toolsRegistered} tool specs`}, this.config);
            return toolsRegistered;
        }

        if (!isL(currentResult.content)) {
            console.error("ActionExecutor: Malformed TOOLS thought content (expected ListTerm):", currentResult.content);
            updateThought(this.thoughtStore, currentResult, 'FAILED', {[Keys.ERROR]: 'Malformed tools definition list'}, this.config);
            return 0;
        }

        let toolsRegistered = 0;
        for (const toolSpecTerm of currentResult.content.elements) {
            // Expecting: tool_spec(NameAtom, TypeAtom, EndpointAtom?, DescriptionAtom?, ParamsSchemaTerm?)
            if (isS(toolSpecTerm) && toolSpecTerm.name === 'tool_spec' && toolSpecTerm.args.length >= 2 &&
                isA(toolSpecTerm.args[0]) && isA(toolSpecTerm.args[1])) {
                const name = toolSpecTerm.args[0].name;
                const type = toolSpecTerm.args[1].name;
                const endpoint = (toolSpecTerm.args.length > 2 && isA(toolSpecTerm.args[2])) ? toolSpecTerm.args[2].name : '';
                const description = (toolSpecTerm.args.length > 3 && isA(toolSpecTerm.args[3])) ? toolSpecTerm.args[3].name : `Dynamically registered tool: ${name}`;
                // TODO: Parse parameter schema from term (e.g., arg[4] could be a ListTerm of parameter definitions)
                const parameters: Record<string, ToolParameter> = {}; // Placeholder - requires schema definition format

                try {
                    let toolInstance: Tool | null = null;
                    // Instantiate known dynamic tool types
                    if (type === 'web_search' && endpoint) {
                        toolInstance = new WebSearchTool(endpoint);
                    } else {
                        console.warn(`ActionExecutor: Unknown or unsupported dynamic tool type "${type}" for tool "${name}"`);
                    }

                    // Register if instance created
                    if (toolInstance) {
                        const definition: ToolDefinition = {name, description, parameters, tool: toolInstance};
                        this.toolManager.registerToolDefinition(definition);
                        console.log(`ActionExecutor: Dynamically registered tool "${name}" of type "${type}"`);
                        toolsRegistered++;
                    }
                } catch (err: any) {
                    console.error(`ActionExecutor: Failed to instantiate or register dynamic tool "${name}":`, err.message);
                }
            } else {
                console.warn("ActionExecutor: Malformed tool specification term in TOOLS list:", termToString(toolSpecTerm));
            }
        }

// Mark the result thought as DONE
        updateThought(this.thoughtStore, currentResult, 'DONE', {[Keys.UI_CONTEXT]: `Registered ${toolsRegistered} tools`}, this.config);
        return toolsRegistered;
    }

    /** Stores a record of the execution trace in the memory store. */
    private async memorizeExecution(
        triggeringThought: Thought,
        rule: Rule | null, // Can be null for default actions or workflow steps
        resultThought: Thought | null, // Can be null if execution failed before result creation
        success: boolean,
        errorMessage?: string // Include error message on failure
    ): Promise<void> {
        // Avoid memorizing traces for trivial internal steps? Configurable?
        // if (triggeringThought.type === 'WORKFLOW_STEP') return;

        try {
            const triggerContent = termToString(triggeringThought.content);
            const resultContent = resultThought ? termToString(resultThought.content) : 'N/A';
            const ruleId = rule?.id ?? (triggeringThought.type === 'WORKFLOW_STEP' ? 'workflow_step' : 'default_action');
            const status = resultThought?.status ?? (success ? 'UNKNOWN' : 'FAILED');
            const errorStr = success ? '' : ` Error: ${errorMessage ?? 'Unknown'}`;

            const trace = `Trace: ${triggeringThought.type} (${triggeringThought.id.substring(0, 6)}) ` +
                `-> Rule(${ruleId}) ` +
                `-> Result(${resultThought?.id.substring(0, 6) ?? 'N/A'}, ${status})` +
                `${errorStr}`;
            const traceDetail = `Trigger Content: ${triggerContent.substring(0, 100)}...\n` +
                `Result Content: ${resultContent.substring(0, 100)}...\n` +
                `Success: ${success}`;


            const embedding = await this.llmTool.generateEmbedding(trace); // Embed the summary trace
            if (embedding.length === 0) {
                console.warn("ActionExecutor: Failed to generate embedding for execution trace.");
                // Decide whether to store without embedding or skip
                // return; // Option: Skip storing if embedding fails
            }

            const memoryId = generateUUID();
            const metadata: Metadata = {
                type: 'execution_trace',
                timestamp: Date.now(),
                agent_id: this.agentId,
                trigger_thought_id: triggeringThought.id,
                trigger_thought_type: triggeringThought.type,
                rule_id: ruleId,
                result_thought_id: resultThought?.id,
                result_thought_status: status,
                success: success,
                error: errorMessage, // Store error message if failed
                root_id: triggeringThought.metadata[Keys.ROOT_ID],
                // Link trace to all involved entities
                related_ids: [
                    triggeringThought.id,
                    ...(rule ? [rule.id] : []),
                    ...(resultThought ? [resultThought.id] : [])
                ],
                // TODO: Extract key entities from trigger/result content
                // extracted_entities: extractEntities(triggerContent + " " + resultContent),
            };

// Store the brief trace as content, details maybe in metadata or separate entry?
            await this.memoryStore.add({id: memoryId, content: trace, embedding, metadata});

        } catch (memErr: any) {
            console.warn(`ActionExecutor: Failed to store execution trace in memory (Trigger: ${triggeringThought.id}):`, memErr.message);
        }
    }

    /** Extracts parameters from action term arguments, converting Terms to JS values. */
    private extractParamsFromTerm(args: ReadonlyArray<Term>): Record<string, unknown> {
        const params: Record<string, unknown> = {};

// Handle standard structure: actionName( params( key1(val1), key2(val2), ... ) )
        if (args.length === 1 && isS(args[0]) && args[0].name === 'params') {
            const paramsList = args[0].args;
            for (const paramStruct of paramsList) {
                if (isS(paramStruct) && paramStruct.args.length > 0) {
                    // key(value_term, optional_other_args...)
                    const key = paramStruct.name;
                    params[key] = this.termToJsValue(paramStruct.args[0]); // Convert first arg to JS value
                } else if (isA(paramStruct)) {
                    // Allow boolean flags like: params(verbose) -> { verbose: true }
                    params[paramStruct.name] = true;
                }
            }
        }
// Handle alternative: actionName( key1(val1), key2(val2), ... )
        else if (args.every(arg => isS(arg) && arg.args.length > 0)) {
            args.forEach(arg => {
                if (isS(arg)) { // Type guard for compiler
                    params[arg.name] = this.termToJsValue(arg.args[0]);
                }
            });
        }
// Fallback: actionName( val1, val2, ... ) -> { arg0: jsVal1, arg1: jsVal2, ... }
        else if (args.length > 0) {
            console.warn(`ActionExecutor: Using fallback parameter extraction for action. Args: ${args.map(termToString).join(', ')}`);
            args.forEach((arg, index) => {
                params[`arg${index}`] = this.termToJsValue(arg);
            });
        }
        return params;
    }

    /** Converts a Term to a corresponding JavaScript value if possible. */
    private termToJsValue(term: Term): unknown {
        switch (term.kind) {
            case 'atom':
                // Try to parse common primitives
                const name = term.name.trim();
                if (name === 'true') return true;
                if (name === 'false') return false;
                if (name === 'null') return null;
                if (name === 'undefined') return undefined;
                // Check for number, ensure it's not empty string misinterpreted as 0
                if (name !== '' && !isNaN(Number(name))) return Number(name);
                // Return string otherwise
                return term.name; // Return original string including whitespace if not primitive
            case 'list':
                // Convert list elements recursively
                return term.elements.map(el => this.termToJsValue(el));
            case 'structure':
                // If structure is 'params', recursively extract nested params
                if (term.name === 'params') {
                    return this.extractParamsFromTerm([term]); // Wrap in array to match expected format
                }
                // Could represent as object { name: string, args: unknown[] } ?
                // Or just stringify? Stringify is safer for unknown structures.
                // return { name: term.name, args: term.args.map(a => this.termToJsValue(a)) };
                return termToString(term); // Default to string representation
            case 'variable':
                // Variables usually shouldn't appear here if actionTerm was fully bound
                console.warn(`ActionExecutor: Encountered unbound variable ${termToString(term)} during parameter extraction.`);
                return termToString(term); // Return string representation with '?'
        }
    }

    /** Checks if parent task can be completed when a child finishes. */
    private async checkAndCompleteParent(completedThought: Thought): Promise<void> {
        // Ensure the completed thought itself is marked DONE (or already is).
        const currentVersion = this.thoughtStore.get(completedThought.id);
        if (!currentVersion) return; // Thought disappeared

        if (currentVersion.status !== 'DONE' && currentVersion.status !== 'FAILED') {
            // Should have been marked DONE by the caller or previous step, but try again if needed.
            const updated = updateThought(this.thoughtStore, currentVersion, 'DONE', {[Keys.UI_CONTEXT]: `Task/Outcome complete`}, this.config);
            if (!updated) {
                console.warn(`[${this.agentId}] checkAndCompleteParent: Failed to mark child ${completedThought.id} as DONE.`);
                return; // Abort if update failed
            }
        } else if (currentVersion.status === 'FAILED') {
            // If the child itself failed, the parent likely shouldn't autocomplete.
            // Parent failure might be handled by failure propagation if implemented.
            return;
        }

// --- Check Parent ---
        const parentId = completedThought.metadata[Keys.PARENT_ID] as UUID | undefined;
        if (!parentId) return; // No parent

        const parentThought = this.thoughtStore.get(parentId);
        if (!parentThought || parentThought.status === 'DONE' || parentThought.status === 'FAILED') {
            return; // Parent already completed or doesn't exist
        }

// --- Completion Logic ---
// Parent completes if *all* its direct children are DONE.
        const siblings = this.thoughtStore.findByParent(parentId);

// Ensure the list of siblings includes the current thought (or reflects its recent DONE status)
        const allChildrenDone = siblings.every(t => t.status === 'DONE' || t.id === completedThought.id);

        if (allChildrenDone && siblings.length > 0) {
            console.log(`[${this.agentId}] All children of ${parentId} are DONE. Completing parent.`);
            const currentParentVersion = this.thoughtStore.get(parentId); // Get latest version for update
            if (currentParentVersion && currentParentVersion.status !== 'DONE' && currentParentVersion.status !== 'FAILED') {
                const parentUpdated = updateThought(this.thoughtStore, currentParentVersion, 'DONE', {
                    [Keys.UI_CONTEXT]: `Completed via children`,
                    // Aggregate related IDs from children? Maybe too much?
                }, this.config);

                if (parentUpdated) {
                    const finalParent = this.thoughtStore.get(parentId); // Get the *final* DONE version
                    if (finalParent) {
                        await this.memorizeCompletion(finalParent);
                        this.triggerCollaborationShare(finalParent);
                        // Recursively check if *this* parent's completion completes *its* parent
                        await this.checkAndCompleteParent(finalParent);
                    }
                } else {
                    console.warn(`[${this.agentId}] Failed to mark parent ${parentId} as DONE (concurrent modification?).`);
                }
            }
        }
    }

    /** Memorizes the completion of a significant thought (e.g., Goal, Root Input). */
    private async memorizeCompletion(thought: Thought): Promise<void> {
        if (thought.type !== 'GOAL' && thought.metadata[Keys.ROOT_ID] !== thought.id) {
            return; // Only memorize significant completions for now
        }
        await this.memorizeExecution(thought, null, null, true); // Use memorizeExecution for consistency
    }

    /** Fire-and-forget sharing logic (placeholder). */
    private triggerCollaborationShare(thought: Thought): void {
        const shareTargets = thought.metadata[Keys.SHARED_WITH] as string[] | undefined;
        if (shareTargets && shareTargets.length > 0) {
            console.log(`[${this.agentId}] TODO: Trigger sharing of thought ${thought.id} to agents: ${shareTargets.join(', ')}`);
            // E.g., network call to thoughtExchange.share(thought);
        }
    }
}

// --- Worker Logic ---

export class Worker {
    private isRunning = false;
    private abortController = new AbortController();
    private lastProcessedThought: Thought | null = null; // For context sampling

    constructor(
        private readonly agentId: UUID,
        private readonly thoughtStore: ThoughtStore,
        private readonly ruleStore: RuleStore,
        private readonly unifier: Unifier,
        private readonly actionExecutor: ActionExecutor,
        // ToolManager/MemoryStore often not needed directly by worker, only by ActionExecutor
        // private readonly toolManager: ToolManager,
        // private readonly memoryStore: MemoryStore,
        private readonly config: Config,
        // LLMTool might be needed for default actions if not using ToolManager
        // private readonly llmTool: LLMTool
    ) {
    }

    start(): void {
        if (this.isRunning) return;
        this.isRunning = true;
        this.abortController = new AbortController(); // Create new controller for this run
        console.log(`Worker for agent ${this.agentId.substring(0, 8)} starting...`);
        this.runLoop().catch(err => {
            console.error(`[${this.agentId.substring(0, 8)}] Worker loop crashed unexpectedly:`, err);
            this.isRunning = false; // Ensure loop stops on crash
        });
    }

    async stop(): Promise<void> {
        if (!this.isRunning) return;
        console.log(`Worker for agent ${this.agentId.substring(0, 8)} stopping...`);
        this.isRunning = false;
        this.abortController.abort(); // Signal any ongoing async operations
    }

    private async runLoop(): Promise<void> {
        while (this.isRunning) {
            let activeThought: Thought | null = null; // Track the thought claimed by this worker
            try {
                // --- Thought Sampling ---
                const pendingThought = this.thoughtStore.samplePending(
                    this.lastProcessedThought ?? undefined, // Provide context if available
                    this.config.contextSimilarityBoostFactor,
                    this.config
                );
                if (!pendingThought) {
                    await sleep(this.config.pollIntervalMillis); // No work, pause
                    continue;
                }

                // --- Mark as Active (Optimistic Update) ---
                const thoughtToMarkActive = this.thoughtStore.get(pendingThought.id); // Get reference from store
                if (!thoughtToMarkActive || thoughtToMarkActive.status !== 'PENDING') {
                    // console.warn(`[${this.agentId.substring(0,8)}] Sampled thought ${pendingThought.id} is no longer PENDING or not found. Skipping.`);
                    continue; // Try sampling again
                }

                // Attempt to update status to ACTIVE using the correct reference
                const updated = updateThought(this.thoughtStore, thoughtToMarkActive, 'ACTIVE', {[Keys.UI_CONTEXT]: 'Processing...'}, this.config);

                if (!updated) {
                    // console.warn(`[${this.agentId.substring(0,8)}] Failed to mark Thought ${thoughtToMarkActive.id} as ACTIVE (concurrent modification?), skipping.`);
                    continue; // Lost the race, another worker got it
                }

                // Successfully marked as active, get the updated version for processing
                activeThought = this.thoughtStore.get(thoughtToMarkActive.id);
                if (!activeThought || activeThought.status !== 'ACTIVE') { // Should not happen, but safety check
                    console.error(`[${this.agentId.substring(0, 8)}] Thought ${thoughtToMarkActive.id} status is not ACTIVE after successful update. Critical error. Status: ${activeThought?.status}`);
                    activeThought = null; // Avoid processing invalid state
                    continue;
                }

                // --- Processing Logic with Timeout ---
                // console.log(`[${this.agentId.substring(0,8)}] Worker processing Thought ${activeThought.id.substring(0,6)} (${activeThought.type})`);
                const startTime = Date.now();
                try {
                    await Promise.race([
                        // The actual processing task
                        (async () => {
                            // WORKFLOW_STEP thoughts contain their own execution logic (sequence/parallel)
                            if (activeThought!.type === 'WORKFLOW_STEP') {
                                await this.actionExecutor.executeAction(activeThought!, activeThought!, new Map()); // Pass thought as workflow definition
                            }
                            // Other thought types match against rules
                            else {
                                const ruleEmbeddings = this.ruleStore.getEmbeddings();
                                const match = this.unifier.findAndSample(
                                    activeThought!,
                                    this.ruleStore.getAll(),
                                    this.config.contextSimilarityBoostFactor,
                                    ruleEmbeddings
                                );

                                if (match?.rule) {
                                    // console.log(`[${this.agentId.substring(0,8)}] Applying Rule ${match.rule.id.substring(0,6)} to Thought ${activeThought!.id.substring(0,6)}`);
                                    await this.actionExecutor.executeAction(activeThought!, match.rule, match.bindings);
                                } else {
                                    // console.log(`[${this.agentId.substring(0,8)}] No rule match for Thought ${activeThought!.id.substring(0,6)}, applying default action.`);
                                    // Pass the active thought reference to default handler
                                    await this.handleDefaultAction(activeThought!);
                                }
                            }
                        })(),
                        // The timeout promise
                        sleep(this.config.thoughtProcessingTimeoutMillis).then(() => {
                            if (this.abortController.signal.aborted) return; // Check if already stopped
                            throw new Error(`Processing timeout`); // Reject race promise
                        })
                    ]);

                    const duration = Date.now() - startTime;
                    // console.log(`[${this.agentId.substring(0,8)}] Finished processing Thought ${activeThought.id.substring(0,6)} in ${duration}ms`);
                    this.lastProcessedThought = activeThought; // Update context for next sample
                    activeThought = null; // Reset tracker for next loop iteration

                } catch (processingError: unknown) {
                    const errorMsg = processingError instanceof Error ? processingError.message : String(processingError);
                    console.error(`[${this.agentId.substring(0, 8)}] Error processing Thought ${activeThought?.id.substring(0, 6)}: ${errorMsg}`);
                    if (activeThought) { // Ensure we have the thought reference
                        await this.handleFailure(activeThought, `Processing error: ${errorMsg}`);
                    }
                    activeThought = null; // Handled failure, reset tracker
                }

            } catch (loopError: unknown) {
                const errorMsg = loopError instanceof Error ? loopError.message : String(loopError);
                console.error(`[${this.agentId.substring(0, 8)}] Worker error in main loop: ${errorMsg}`, loopError);

                activeThought = null; // Reset tracker
                await sleep(this.config.pollIntervalMillis * 5); // Longer pause
            }
        } // End while(this.isRunning)
        console.log(`Worker for agent ${this.agentId.substring(0, 8)} loop terminated.`);
    }


    /** Default actions when no specific rule matches a thought. Needs the ACTIVE thought ref. */
    private async handleDefaultAction(activeThought: Thought): Promise<void> {
        if (activeThought.status !== 'ACTIVE') {
            console.warn(`[${this.agentId.substring(0, 8)}] handleDefaultAction called on non-ACTIVE thought ${activeThought.id.substring(0, 6)} (Status: ${activeThought.status}). Skipping.`);
            return;
        }
        console.log(`[${this.agentId.substring(0, 8)}] Default action for ${activeThought.type} thought ${activeThought.id.substring(0, 6)}`);
        const rootId = activeThought.metadata[Keys.ROOT_ID] ?? activeThought.id;
        let defaultHandled = false;

        try {
            // Delegate most default actions to ActionExecutor by creating a temporary action term
            let defaultActionTerm: Term | null = null;
            switch (activeThought.type) {
                case 'INPUT':
                    const goalPrompt = `Analyze this input and formulate a concise goal as a structure: {"name": "goal_name", "args": [...]}\nInput: "${termToString(activeThought.content)}"`;
                    defaultActionTerm = S("llm", [S("params", [
                        S("action", [A("generate")]),
                        S("input", [A(goalPrompt)]),
                        S("type", [A("GOAL")])
                    ])]);
                    break;
                case 'GOAL':
                    const strategyPrompt = `Break down this goal into an initial strategy or action as a structure: {"name": "strategy_name", "args": [...]}\nGoal: "${termToString(activeThought.content)}"`;
                    defaultActionTerm = S("llm", [S("params", [
                        S("action", [A("generate")]),
                        S("input", [A(strategyPrompt)]),
                        S("type", [A("STRATEGY")])
                    ])]);
                    // Fire-and-forget related actions are launched separately
                    this.triggerRelatedDefaultActions(activeThought, rootId);
                    break;
                case 'STRATEGY':
                    const outcomePrompt = `Based on this strategy, predict the likely outcome or generate the next concrete step as a structure: {"name": "outcome_or_next_step", "args": [...]}\nStrategy: "${termToString(activeThought.content)}"`;
                    defaultActionTerm = S("llm", [S("params", [
                        S("action", [A("generate")]),
                        S("input", [A(outcomePrompt)]),
                        S("type", [A("OUTCOME")])
                    ])]);
                    // Fire-and-forget tool discovery
                    this.triggerToolDiscovery(activeThought, rootId);
                    break;
                case 'OUTCOME':
                    // Default for OUTCOME is just to check completion, no further action needed from here.
                    await this.actionExecutor.checkAndCompleteParent(activeThought);
                    defaultHandled = true; // Completion check *is* the handling.
                    break;
                case 'QUERY':
                    const answerPrompt = `Answer this query. Respond with structure {"value": "Your answer"}\nQuery: "${termToString(activeThought.content)}"`;
                    defaultActionTerm = S("llm", [S("params", [
                        S("action", [A("generate")]),
                        S("input", [A(answerPrompt)]),
                        S("type", [A("OUTCOME")])
                    ])]);
                    break;
                default:
                    console.warn(`[${this.agentId.substring(0, 8)}] No default action defined for thought type: ${activeThought.type}`);
                    await this.handleFailure(activeThought, `No default action for type ${activeThought.type}`);
                    defaultHandled = true;
                    break;
            }

            // If a default action term was created, execute it via ActionExecutor
            if (defaultActionTerm && isS(defaultActionTerm)) {
                await this.actionExecutor.executeToolAction(activeThought, defaultActionTerm, {}, null); // Pass null for rule
                defaultHandled = true;
            } else if (defaultActionTerm) {
                // This shouldn't happen based on the switch cases
                console.error(`[${this.agentId.substring(0, 8)}] Invalid default action term generated for ${activeThought.type}`);
                await this.handleFailure(activeThought, `Invalid default action term for type ${activeThought.type}`);
                defaultHandled = true;
            }

        } catch (error: any) {
            console.error(`[${this.agentId.substring(0, 8)}] Error during default action for thought ${activeThought.id}:`, error.message);
            await this.handleFailure(activeThought, `Error in default action: ${error.message}`);
            defaultHandled = true;
        }

        // Safety net: Ensure thought doesn't stay ACTIVE if no path handled it
        if (!defaultHandled) {
            console.warn(`[${this.agentId.substring(0, 8)}] Default action path for ${activeThought.type} completed without explicit handling. Forcing FAILED state.`);
            await this.handleFailure(activeThought, `Unhandled default action path for type ${activeThought.type}`);
        }
    }


    // --- Default Action Triggers (Fire-and-Forget) ---
    // These use ToolManager directly, assuming it's available or passed differently.
    // For now, let's assume Worker has access to a method/instance to trigger these.
    // Simplified: Let's assume ActionExecutor handles these via specific rules or dedicated thoughts if needed.
    // Removing direct ToolManager calls from Worker to keep it focused on the main loop.

    private triggerRelatedDefaultActions(goalThought: Thought, rootId: UUID): void {
        // // Option 1: Create new PENDING thoughts that trigger these tools via rules
        // const clarifyThought = createThought(this.agentId, 'STRATEGY',
        //     createStructure("needs_clarification", [goalThought.content]), // Content matches a potential rule
        //     'PENDING', { [MetadataKeys.UI_CONTEXT]: "Auto-trigger: Clarify goal?" }, goalThought);
        // this.thoughtStore.add(clarifyThought);


        const proposeThought = createThought(this.agentId, 'STRATEGY',
            S("propose_related_goal", [goalThought.content]), // Content matches a potential rule
            'PENDING', {[Keys.UI_CONTEXT]: "Auto-trigger: Propose related goal?"}, goalThought);
        this.thoughtStore.add(proposeThought);

        // Option 2: (Less clean) Direct call if ToolManager was available
        // this.toolManager.execute('user_interaction', {...}, goalThought, this.agentId)...
        // this.toolManager.execute('goal_proposal', {}, goalThought, this.agentId)...
    }

    private triggerToolDiscovery(strategyThought: Thought, rootId: UUID): void {
        // Create a PENDING thought that triggers the LLM tool via a rule
        const discoverThought = createThought(this.agentId, 'STRATEGY',
            S("discover_tools_for", [strategyThought.content]), // Content matches potential rule
            'PENDING', {[Keys.UI_CONTEXT]: "Auto-trigger: Discover tools?"}, strategyThought);
        this.thoughtStore.add(discoverThought);

        // Option 2: (Less clean) Direct call if ToolManager was available
        // this.toolManager.execute('llm', {...}, strategyThought, this.agentId)...
    }


    /** Handles failures during thought processing. Needs the current thought reference. */
    private async handleFailure(thoughtRef: Thought, error: string): Promise<void> {
        // If the thought disappeared or was already handled, do nothing
        if (!thoughtRef || thoughtRef.status === 'FAILED' || thoughtRef.status === 'DONE') {
            // console.warn(`[${this.agentId.substring(0,8)}] Failure handling skipped for thought ${thoughtRef?.id?.substring(0,6)}: Status is ${thoughtRef?.status}.`);
            return;
        }

        console.error(`[${this.agentId.substring(0, 8)}] Failure processing Thought ${thoughtRef.id.substring(0, 6)} (Type: ${thoughtRef.type}, Status: ${thoughtRef.status}): ${error}`);

        const retries = (Number(thoughtRef.metadata[Keys.RETRY_COUNT] ?? 0)) + 1;
        const newStatus = retries <= this.config.maxRetries ? 'PENDING' : 'FAILED'; // Retry or fail permanently

        const updatedMetadata: Partial<Metadata> = {
            [Keys.ERROR]: error.substring(0, 500), // Truncate long errors
            [Keys.RETRY_COUNT]: retries,
            [Keys.UI_CONTEXT]: newStatus === 'PENDING'
                ? `Retry ${retries}/${this.config.maxRetries}: ${error.substring(0, 40)}...`
                : `FAILED: ${error.substring(0, 50)}...`,
        };

        // Attempt the atomic update using the provided thought reference
        const updated = updateThought(this.thoughtStore, thoughtRef, newStatus, updatedMetadata, this.config);

        if (updated && newStatus === 'FAILED') {
            // If update succeeded and it failed permanently, trigger rule synthesis
            const failedThought = this.thoughtStore.get(thoughtRef.id); // Get the updated FAILED thought
            if (failedThought) {
                this.triggerFailureRuleSynthesis(failedThought, error);
                // Optionally, propagate failure to parent?
                // await this.propagateFailureToParent(failedThought);
            }
        } else if (!updated) {
            console.warn(`[${this.agentId.substring(0, 8)}] Failed to update thought ${thoughtRef.id.substring(0, 6)} status after failure (concurrent modification?). Current status: ${this.thoughtStore.get(thoughtRef.id)?.status}`);
        }
    }

    /** Creates a thought to trigger failure rule synthesis (fire-and-forget). */
    private triggerFailureRuleSynthesis(failedThought: Thought, error: string): void {
        const failureContext = `Thought Content: ${termToString(failedThought.content)}\nThought Type: ${failedThought.type}\nError: ${error}`;
        const prompt = `Analyze the following failure scenario and synthesize a specific rule (pattern and action) to better handle similar situations in the future. If possible, suggest a corrective action.\nScenario:\n${failureContext.substring(0, 1500)}\n\nFormat: {"pattern": {... Term JSON ...}, "action": {... Term JSON ...}}`;

        // Create a thought that will trigger the LLM 'generate' action via a rule
        const synthesizeRuleThought = createThought(this.agentId, 'STRATEGY', // Meta-reasoning strategy
            S("synthesize_failure_rule", [
                A(failedThought.id),
                A(error.substring(0, 100)) // Pass limited error info
            ]),
            'PENDING',
            {
                [Keys.UI_CONTEXT]: `Auto-trigger: Synthesize rule for failure ${failedThought.id.substring(0, 6)}?`,
                'generation_prompt': prompt, // Pass full prompt in metadata for the rule action
                [Keys.PRIORITY]: 1.2 // High priority for learning from failure
            },
            failedThought // Link to the failed thought
        );
        this.thoughtStore.add(synthesizeRuleThought);
    }
}


// --- User Interface (Console) ---
export class UserInterface {
    private sortBy: 'timestamp' | 'type' | 'priority' | 'status' = 'timestamp';
    private currentRootId: UUID | null = null; // For potential filtering later
    private intervalId: NodeJS.Timeout | null = null;
    private rl: readline.Interface | null = null;
    private isRendering = false; // Prevent overlapping renders


    constructor(
        private readonly thoughtStore: ThoughtStore,
        private readonly userInteractionTool: UserInteractionTool, // Needs the instance
        private readonly config: Config,
        private readonly agentId: UUID
    ) {
    }

    start(): void {
        if (this.rl) return; // Already started
        this.rl = readline.createInterface({input, output, prompt: '> '});
        this.render(); // Initial render
        // Schedule periodic render, ensuring it doesn't overlap
        this.intervalId = setInterval(() => {
            if (!this.isRendering) {
                this.render();
            }
        }, this.config.uiRefreshMillis);
        this.promptLoop(); // Start loop for user commands

        this.rl.on('close', () => {
            console.log('\nReadline closed. Stopping UI updates.');
            if (this.intervalId) clearInterval(this.intervalId);
            this.intervalId = null;
            this.rl = null; // Clear reference
        });
    }

    stop(): void {
        if (this.intervalId) clearInterval(this.intervalId);
        this.intervalId = null;
        if (this.rl) {
            // Closing readline interface can cause issues if promptLoop is waiting
            // Set flag and let promptLoop exit gracefully?
            // Or just close it.
            this.rl.close();
            this.rl = null;
        }
        console.log("UI stopped.");
    }

    async render(): Promise<void> {
        if (!this.rl || this.rl.closed || this.isRendering) return;
        this.isRendering = true;

        try {
            // Store cursor position and current input line (if possible with readline)
            const currentLine = (this.rl as any).line; // May not be reliable
            const cursorPos = (this.rl as any).cursor; // May not be reliable

            //readline.cursorTo(output, 0, 0);
            //readline.clearScreenDown(output);
            console.log(`\n=== FlowMind Agent [${this.agentId.substring(0, 8)}] === (${new Date().toLocaleTimeString()}) ===`);

            const thoughts = this.thoughtStore.getAllThoughts();
            const notes = new Map<UUID, { root: Thought; activities: Thought[] }>();

            // Group thoughts by root_id
            for (const thought of thoughts) {
                // Skip DONE/FAILED root notes? Or show them? Show them for history.
                const rootId = thought.metadata[Keys.ROOT_ID] as UUID | undefined;
                if (!rootId) {
                    // Show orphaned thoughts (no root_id) that are active/pending?
                    if (thought.status !== 'DONE' && thought.status !== 'FAILED' && thought.type === 'INPUT') {
                        if (!notes.has(thought.id)) notes.set(thought.id, {root: thought, activities: []});
                    }
                    continue;
                }

                if (!notes.has(rootId)) {
                    const rootThought = this.thoughtStore.get(rootId);
                    if (rootThought) {
                        notes.set(rootId, {root: rootThought, activities: []});
                    } else {
                        // Create a placeholder for orphaned activities if root is missing
                        const placeholderRoot = createThought(this.agentId, 'INPUT', A(`Missing Root: ${rootId.substring(0, 6)}`), 'FAILED', {}, null);
                        notes.set(rootId, {root: placeholderRoot, activities: [thought]}); // Add current thought
                        continue;
                    }
                }
                // Add thought to activities if it's not the root thought itself
                if (thought.id !== rootId) {
                    notes.get(rootId)?.activities.push(thought);
                }
            }

            if (notes.size === 0) {
                console.log("No active notes or thoughts.");
            } else {
                console.log(`--- Notes (Sort: ${this.sortBy}) ---`);
            }

            // Display grouped notes and activities
            Array.from(notes.values())
                .sort((a, b) => (b.root.metadata[Keys.TIMESTAMP] as number ?? 0) - (a.root.metadata[Keys.TIMESTAMP] as number ?? 0)) // Sort notes by most recent timestamp
                .forEach(({root, activities}) => {
                    const rootContent = root.metadata[Keys.UI_CONTEXT] ?? termToString(root.content);
                    const rootStatusIndicator = this.getStatusIndicator(root.status);
                    console.log(`\n📝 ${rootStatusIndicator} Note [${root.id.substring(0, 6)}]: ${rootContent.substring(0, 100)}${rootContent.length > 100 ? '...' : ''} (Status: ${root.status})`);

                    if (activities.length > 0) {
                        const sortedActivities = [...activities].sort(this.getComparator());
                        sortedActivities.forEach(activity => {
                            const priority = activity.metadata[Keys.PRIORITY];
                            const beliefScore = calculateBeliefScore(activity.belief, this.config);
                            const prioStr = typeof priority === 'number' ? `P:${priority.toFixed(1)}` : `B:${beliefScore.toFixed(2)}`;
                            const statusIndicator = this.getStatusIndicator(activity.status);
                            const uiContext = activity.metadata[Keys.UI_CONTEXT] as string ?? termToString(activity.content);
                            const error = activity.metadata[Keys.ERROR] as string | undefined;
                            let typeStr = activity.type.padEnd(9); // Pad type for alignment
                            if (activity.type === 'WORKFLOW_STEP') {
                                typeStr = `WF Step ${activity.metadata[Keys.WORKFLOW_STEP] ?? '?'}`.padEnd(9);
                            }

                            console.log(`   - ${statusIndicator} [${activity.id.substring(0, 6)}] [${prioStr}] (${typeStr}) ${uiContext.substring(0, 80)}${uiContext.length > 80 ? '...' : ''}${error ? ` | Err: ${error.substring(0, 40)}...` : ''}`);
                        });
                    } else {
                        console.log("   (No activities yet)");
                    }
                });

            // Display pending prompts
            const prompts = this.userInteractionTool.getPendingPromptsForUI(this.thoughtStore);
            if (prompts.length > 0) {
                console.log("\n--- Pending User Prompts ---");
                prompts.forEach((p, index) => {
                    console.log(`${index + 1}. [${p.id.substring(0, 6)}] ${p.prompt}`);
                    if (p.options) {
                        p.options.forEach((opt, i) => console.log(`      ${i + 1}) ${opt}`));
                    }
                });
            }

            console.log("\n--- Commands ---");
            console.log("[sort time|type|prio|status] | [add Note Text] | [respond PromptIndex ResponseText] | [quit]");

            // Restore cursor and prompt line (best effort)
            // output.write(`> ${currentLine ?? ''}`);
            // readline.cursorTo(output, (currentLine?.length ?? 0) + 2); // Position cursor after prompt and line

            // Or just show the prompt again
            if (this.rl && !this.rl.closed) this.rl.prompt(true); // Use true to prevent duplicate prompt character

        } catch (renderError) {
            console.error("Error during UI render:", renderError);
        } finally {
            this.isRendering = false;
        }
    }

    private getStatusIndicator(status: Status): string {
        switch (status) {
            case 'PENDING':
                return ' PENDING '; // Gray?
            case 'ACTIVE':
                return ' ACTIVE  '; // Blue?
            case 'WAITING':
                return ' WAITING '; // Yellow?
            case 'DONE':
                return '  DONE   '; // Green?
            case 'FAILED':
                return ' FAILED  '; // Red?
            default:
                return '   ???   ';
        }
    }

    private async promptLoop(): Promise<void> {
        console.log("Starting command prompt loop...");
        while (true) { // Loop indefinitely until explicitly stopped
            let inputLine: string | undefined;
            try {
                if (!this.rl || this.rl.closed) {
                    console.log("Readline closed, exiting prompt loop.");
                    break; // Exit loop if readline is closed
                }
                // Use question to wait for input
                inputLine = await this.rl.question('> ');

                if (!this.rl || this.rl.closed) break; // Check again after await

                const parts = inputLine.trim().split(' ');
                const command = parts[0]?.toLowerCase();

                switch (command) {
                    case 'sort':
                        const criteria = parts[1]?.toLowerCase();
                        if (criteria === 'time' || criteria === 'timestamp') this.sortBy = 'timestamp';
                        else if (criteria === 'type') this.sortBy = 'type';
                        else if (criteria === 'prio' || criteria === 'priority') this.sortBy = 'priority';
                        else if (criteria === 'status') this.sortBy = 'status';
                        else console.log("Invalid sort criteria. Use: time, type, prio, status");
                        await this.render(); // Re-render immediately after sort change
                        break;
                    case 'add':
                        const noteText = parts.slice(1).join(' ').trim();
                        if (noteText) {
                            const newThought = createThought(
                                this.agentId, 'INPUT', A(noteText), 'PENDING',
                                {[Keys.UI_CONTEXT]: `New Note: ${noteText}`, [Keys.PRIORITY]: 1.5}, // Give new notes higher priority
                                null
                            );
                            this.thoughtStore.add(newThought);
                            console.log(`Added new note ${newThought.id.substring(0, 6)}.`);
                            await this.render(); // Re-render immediately
                        } else console.log("Usage: add <Your note text>");
                        break;
                    case 'respond':
                        const promptIndexStr = parts[1];
                        const responseText = parts.slice(2).join(' ').trim();
                        const promptIndex = parseInt(promptIndexStr, 10) - 1; // User sees 1-based index
                        const currentPrompts = this.userInteractionTool.getPendingPromptsForUI(this.thoughtStore); // Get fresh list

                        if (!isNaN(promptIndex) && promptIndex >= 0 && promptIndex < currentPrompts.length && responseText) {
                            const targetPrompt = currentPrompts[promptIndex];
                            let actualResponse = responseText;

                            // Handle selecting an option by number
                            if (targetPrompt.options) {
                                const optionIndex = parseInt(responseText, 10) - 1; // User enters 1-based option number
                                if (!isNaN(optionIndex) && optionIndex >= 0 && optionIndex < targetPrompt.options.length) {
                                    actualResponse = targetPrompt.options[optionIndex];
                                    // console.log(`Selected option: "${actualResponse}"`);
                                }
                            }

                            console.log(`\nResponding to prompt [${targetPrompt.id.substring(0, 6)}]...`); // Log before handling
                            this.userInteractionTool.handleResponse(targetPrompt.id, actualResponse, this.thoughtStore);
                            await this.render(); // Re-render immediately
                        } else {
                            if (currentPrompts.length === 0) console.log("No pending prompts to respond to.");
                            else console.log("Usage: respond <PromptIndex> <Your response text or option number>");
                            if (this.rl && !this.rl.closed) this.rl.prompt(); // Show prompt again if usage was wrong
                        }
                        break;
                    case 'quit':
                    case 'exit':
                        console.log("Quit command received. Stopping UI...");
                        await this.stop(); // Ensure cleanup happens
                        // Let the main process handle shutdown signal propagation
                        process.kill(process.pid, 'SIGINT'); // Send signal to trigger main shutdown handler
                        return; // Exit loop
                    case '': // Ignore empty input
                        if (this.rl && !this.rl.closed) this.rl.prompt();
                        break;
                    default:
                        console.log(`Unknown command: "${command}". Known commands: sort, add, respond, quit.`);
                        if (this.rl && !this.rl.closed) this.rl.prompt();
                }
            } catch (error: any) {
                // Handle readline closure during await
                if (!this.rl || this.rl.closed || error.code === 'ERR_CLOSED') {
                    console.log("Input stream closed during processing. Exiting UI loop.");
                    break; // Exit loop
                }
                console.error("\nError in UI prompt loop:", error.message);
                if (this.rl && !this.rl.closed) this.rl.prompt(); // Try to show prompt again
                await sleep(DEFAULT_CONFIG.errorSleepMS); // Pause briefly after error
            }
        }
        console.log("Command prompt loop finished.");
    }

    // Comparator function for sorting activities within a note
    private getComparator(): (a: Thought, b: Thought) => number {
        const getPriority = (t: Thought): number => {
            const explicitPrio = t.metadata[Keys.PRIORITY];
            // Give higher effective priority to explicitly set values
            const baseScore = typeof explicitPrio === 'number' && isFinite(explicitPrio)
                ? explicitPrio * 10 // Scale explicit priority
                : calculateBeliefScore(t.belief, this.config); // Use belief otherwise
            // Boost ACTIVE/PENDING slightly?
            // if (t.status === 'ACTIVE') return baseScore + 100;
            // if (t.status === 'PENDING') return baseScore + 50;
            return baseScore;
        };
        // Status order: ACTIVE > PENDING > WAITING > FAILED > DONE
        const statusOrder: Record<Status, number> = {'ACTIVE': 0, 'PENDING': 1, 'WAITING': 2, 'FAILED': 3, 'DONE': 4};
        const getTypeOrder = (t: Thought): number => {
            const typePrio: Record<Type, number> = {
                'INPUT': 0,
                'QUERY': 1,
                'GOAL': 2,
                'STRATEGY': 3,
                'WORKFLOW_STEP': 4,
                'TOOLS': 5,
                'RULE': 6,
                'OUTCOME': 7
            };
            return typePrio[t.type] ?? 99;
        }

        switch (this.sortBy) {
            case 'type':
                // Sort by type group, then timestamp within group
                return (a, b) => getTypeOrder(a) - getTypeOrder(b) || (a.metadata[Keys.TIMESTAMP] as number ?? 0) - (b.metadata[Keys.TIMESTAMP] as number ?? 0);
            case 'priority':
                // Sort descending by calculated priority, then ascending by timestamp for stability
                return (a, b) => getPriority(b) - getPriority(a) || (a.metadata[Keys.TIMESTAMP] as number ?? 0) - (b.metadata[Keys.TIMESTAMP] as number ?? 0);
            case 'status':
                // Sort by status order, then descending priority within status
                return (a, b) => statusOrder[a.status] - statusOrder[b.status] || getPriority(b) - getPriority(a);
            case 'timestamp':
            default:
                // Sort ascending by timestamp (oldest first)
                return (a, b) => (a.metadata[Keys.TIMESTAMP] as number ?? 0) - (b.metadata[Keys.TIMESTAMP] as number ?? 0);
        }
    }
}


// --- Persistence ---
// Updated for new types and structures

// Define serializable formats
type SerializableTerm =
    | { kind: 'atom'; name: string }
    | { kind: 'variable'; name: string }
    | { kind: 'structure'; name: string; args: SerializableTerm[] }
    | { kind: 'list'; elements: SerializableTerm[] };

type SerializableBelief = { positive: number; negative: number; lastUpdated: number; }; // Added lastUpdated

type SerializableThought = {
    id: UUID; type: Type; status: Status;
    content: SerializableTerm;
    belief: SerializableBelief;
    metadata: Record<string, unknown>; // Store metadata as plain object
};
type SerializableRule = {
    id: UUID;
    pattern: SerializableTerm; action: SerializableTerm;
    belief: SerializableBelief;
    metadata: Record<string, unknown>;
    embedding?: number[]; // Store embedding optionally (generated on load if missing)
};
type SerializableMemoryEntry = {
    id: UUID;
    embedding: number[]; // Keep as plain array for JSON
    content: string;
    metadata: Record<string, unknown>;
};

type SerializableState = {
    version: number;
    thoughts: SerializableThought[];
    rules: SerializableRule[];
    memories: SerializableMemoryEntry[];
};


export class Persistence {
    private readonly stateVersion = 2; // Incremented version due to belief/metadata/rule embedding changes

    // --- Serialization Helpers ---
    private termToSerializable(term: Term): SerializableTerm { /* ... same ... */
        switch (term.kind) {
            case 'atom':
                return {kind: 'atom', name: term.name};
            case 'variable':
                return {kind: 'variable', name: term.name};
            case 'structure':
                return {kind: 'structure', name: term.name, args: term.args.map(arg => this.termToSerializable(arg))};
            case 'list':
                return {kind: 'list', elements: term.elements.map(el => this.termToSerializable(el))};
        }
    }

    private serializableToTerm(sTerm: any): Term { /* ... same ... */
        // Add basic validation
        if (!sTerm || !sTerm.kind) throw new Error("Invalid serializable term object: missing kind");
        switch (sTerm.kind) {
            case 'atom':
                return A(sTerm.name);
            case 'variable':
                return V(sTerm.name);
            case 'structure':
                return S(sTerm.name, (sTerm.args ?? []).map((arg: any) => this.serializableToTerm(arg)));
            case 'list':
                return L((sTerm.elements ?? []).map((el: any) => this.serializableToTerm(el)));
            default:
                throw new Error(`Unknown serializable term kind: ${sTerm.kind}`);
        }
    }

    private beliefToSerializable(belief: Belief): SerializableBelief { /* ... same ... */
        return {positive: belief.positive, negative: belief.negative, lastUpdated: belief.lastUpdated};
    }

    private serializableToBelief(sBelief: any): Belief { /* ... same ... */
        // Provide default for lastUpdated if loading older state
        if (!sBelief || typeof sBelief.positive !== 'number' || typeof sBelief.negative !== 'number') {
            console.warn("Invalid belief object during load, using default.", sBelief);
            return {...DEFAULT_BELIEF, lastUpdated: Date.now()};
        }
        return {positive: sBelief.positive, negative: sBelief.negative, lastUpdated: sBelief.lastUpdated ?? Date.now()};
    }


    // --- Save Method ---
    async save(
        thoughtStore: ThoughtStore,
        ruleStore: RuleStore,
        memoryStore: MemoryStore,
        filePath: string
    ): Promise<void> {
        try {
            const ruleEmbeddings = ruleStore.getEmbeddings(); // Get cached embeddings

            const state: SerializableState = {
                version: this.stateVersion,
                thoughts: thoughtStore.getAllThoughts().map(t => ({
                    id: t.id, type: t.type, status: t.status,
                    content: this.termToSerializable(t.content),
                    belief: this.beliefToSerializable(t.belief),
                    // Convert metadata (which might contain ReadonlyArrays) to plain objects/arrays
                    metadata: JSON.parse(JSON.stringify(t.metadata))
                })),
                rules: ruleStore.getAll().map(r => ({
                    id: r.id,
                    pattern: this.termToSerializable(r.pattern),
                    action: this.termToSerializable(r.action),
                    belief: this.beliefToSerializable(r.belief),
                    metadata: JSON.parse(JSON.stringify(r.metadata)),
                    // Store embedding if available
                    embedding: ruleEmbeddings.has(r.id) ? [...ruleEmbeddings.get(r.id)!] : undefined
                })),
                memories: memoryStore.getAllEntries().map(m => ({
                    id: m.id,
                    embedding: [...m.embedding], // Convert ReadonlyArray back to plain array
                    content: m.content,
                    metadata: JSON.parse(JSON.stringify(m.metadata))
                }))
            };

            await fs.writeFile(filePath, JSON.stringify(state, null, 2)); // Pretty print JSON
            // console.log(`Persistence: State (v${this.stateVersion}) saved to ${filePath}`);
        } catch (error: any) {
            console.error(`Persistence: Error saving state to ${filePath}:`, error.message);
            // throw error; // Optionally re-throw
        }
    }

    // --- Load Method ---
    async load(
        thoughtStore: ThoughtStore,
        ruleStore: RuleStore, // Pass RuleStore to load rules into it
        memoryStore: MemoryStore,
        filePath: string
    ): Promise<void> {
        try {
            const data = await fs.readFile(filePath, 'utf-8');
            const state = JSON.parse(data) as SerializableState;

            if (!state || typeof state !== 'object') {
                throw new Error("Invalid state file format (not an object).");
            }

            const loadedVersion = state.version ?? 0;
            if (loadedVersion !== this.stateVersion) {
                console.warn(`Persistence: Loading state version ${loadedVersion}, current version is ${this.stateVersion}. Compatibility issues may arise.`);
                // TODO: Implement migration logic here if needed based on version differences
            }

            // Clear existing state *before* loading
            thoughtStore.clear();
            ruleStore.clear();
            memoryStore.clear();

            let thoughtsLoaded = 0;
            let rulesLoaded = 0;
            let memoriesLoaded = 0;

            // Load Thoughts
            if (Array.isArray(state.thoughts)) {
                state.thoughts.forEach(st => {
                    try {
                        // Basic validation of loaded thought data
                        if (!st.id || !st.type || !st.status || !st.content || !st.belief || !st.metadata) {
                            throw new Error("Missing required fields");
                        }
                        const thought: Thought = Object.freeze({
                            id: st.id, type: st.type, status: st.status,
                            content: this.serializableToTerm(st.content),
                            belief: this.serializableToBelief(st.belief),
                            metadata: Object.freeze(st.metadata) // Freeze metadata
                        });
                        thoughtStore.add(thought);
                        thoughtsLoaded++;
                    } catch (err: any) {
                        console.error(`Persistence: Failed to load thought ${st?.id ?? 'UNKNOWN'}:`, err.message);
                    }
                });
            }

            // Load Rules (and trigger embedding generation)
            if (Array.isArray(state.rules)) {
                for (const sr of state.rules) {
                    try {
                        if (!sr.id || !sr.pattern || !sr.action || !sr.belief || !sr.metadata) {
                            throw new Error("Missing required fields");
                        }
                        const rule: Rule = Object.freeze({
                            id: sr.id,
                            pattern: this.serializableToTerm(sr.pattern),
                            action: this.serializableToTerm(sr.action),
                            belief: this.serializableToBelief(sr.belief),
                            metadata: Object.freeze(sr.metadata)
                        });
                        // Add rule using RuleStore's method to handle embedding generation/caching
                        await ruleStore.add(rule);
                        rulesLoaded++;
                    } catch (err: any) {
                        console.error(`Persistence: Failed to load rule ${sr?.id ?? 'UNKNOWN'}:`, err.message);
                    }
                }
            }

            // Load Memories
            if (Array.isArray(state.memories)) {
                state.memories.forEach(sm => {
                    try {
                        // Basic validation
                        if (!sm.id || !sm.content || !sm.embedding || !sm.metadata) {
                            throw new Error("Missing required fields");
                        }
                        // Let MemoryStore handle validation and freezing via _loadEntry
                        memoryStore._loadEntry(sm);
                        memoriesLoaded++;
                    } catch (err: any) {
                        console.error(`Persistence: Failed to load memory entry ${sm?.id ?? 'UNKNOWN'}:`, err.message);
                    }
                });
            }

            console.log(`Persistence: State (v${loadedVersion}) loaded from ${filePath}. Thoughts: ${thoughtsLoaded}, Rules: ${rulesLoaded}, Memories: ${memoriesLoaded}`);
        } catch (error: any) {
            if (error.code === 'ENOENT') {
                console.log(`Persistence: No state file found at ${filePath}. Starting fresh.`);
            } else {
                console.error(`Persistence: Error loading state from ${filePath}:`, error);
                // Decide if loading failure should prevent startup
                // throw error; // Re-throw to potentially halt startup
            }
        }
    }
}


// --- Agent Loop / Controller ---
export class AgentLoop {
    private readonly agentId: UUID;
    private readonly config: Config;
    private readonly thoughtStore: ThoughtStore;
    private readonly ruleStore: RuleStore;
    private readonly memoryStore: MemoryStore;
    private readonly unifier: Unifier;
    private readonly llmTool: LLMTool; // Central LLMTool instance
    private readonly userInteractionTool: UserInteractionTool; // Singleton instance
    private readonly toolManager: ToolManager;
    private readonly actionExecutor: ActionExecutor;
    private readonly ui: UserInterface;
    private readonly persistence: Persistence;
    private readonly workers: Worker[] = [];
    private isRunning = false;
    private saveIntervalId: NodeJS.Timeout | null = null;

    constructor(agentId?: UUID, configOverrides: Partial<Config> = {}) {
        this.agentId = agentId ?? generateUUID();
        // Merge default config with overrides, ensuring result is readonly
        this.config = Object.freeze({...DEFAULT_CONFIG, ...configOverrides});

        console.log(`Initializing FlowMind Agent ${this.agentId.substring(0, 8)}...`);
        // console.log(`Config:`, this.config); // Optional: Log full config

        // --- Initialize Core Components ---
        this.thoughtStore = new ThoughtStore(this.agentId);
        this.llmTool = new LLMTool(this.config); // Create the central LLM tool
        this.ruleStore = new RuleStore(this.llmTool); // Pass LLM tool for rule embedding
        this.memoryStore = new MemoryStore();
        this.unifier = new Unifier();
        this.userInteractionTool = new UserInteractionTool(); // Create the UI tool instance

        // Tool Manager holds tool definitions and instances
        this.toolManager = new ToolManager(
            this.memoryStore, this.thoughtStore, this.config, this.llmTool
        );
        // Register the singleton UserInteractionTool instance
        this.toolManager.registerToolDefinition(new UserInteractionToolDefinition(this.userInteractionTool));

        // Example: Register WebSearchTool if configured via environment variable
        const webSearchEndpoint = process.env.SEARCH_ENDPOINT;
        if (webSearchEndpoint) {
            try {
                this.toolManager.registerToolDefinition(new WebSearchToolDefinition(webSearchEndpoint));
                console.log(`Registered WebSearchTool with endpoint: ${webSearchEndpoint}`);
            } catch (e: any) {
                console.error(`Failed to register WebSearchTool: ${e.message}`);
            }
        }


        this.actionExecutor = new ActionExecutor(
            this.thoughtStore, this.ruleStore, this.toolManager, this.memoryStore,
            this.agentId, this.config, this.llmTool
        );
        this.ui = new UserInterface(
            this.thoughtStore, this.userInteractionTool, this.config, this.agentId
        );
        this.persistence = new Persistence();

        // Bootstrap rules after all components are initialized
        // Run async bootstrap within constructor (or call separately)
        // this.bootstrapRules().catch(e => console.error("Error during bootstrap:", e)); // Fire-and-forget bootstrap
    }

    private async bootstrapRules(): Promise<void> {
        console.log(`AgentLoop: Bootstrapping rules...`);
        // Basic flow rules + workflow example
        const rulesData: Omit<Rule, 'id' | 'belief' | 'metadata'>[] = [
            // INPUT -> Goal
            {
                pattern: S("input", [V("ContentText")]),
                action: S("llm", [S("params", [
                    S("action", [A("generate")]),
                    S("input", [A('Analyze input "?ContentText" and formulate a concise goal as a structure: {"name": "goal_name", "args": [...]}')]),
                    S("type", [A("GOAL")])])])
            },
            // GOAL -> Strategy
            {
                pattern: S("goal", [V("GoalTerm")]),
                action: S("llm", [S("params", [
                    S("action", [A("generate")]),
                    S("input", [A('Generate initial strategy for goal "?GoalTerm" as a structure: {"name": "strategy_name", "args": [...]}.')]),
                    S("type", [A("STRATEGY")])])])
            },
            // STRATEGY -> Outcome/Next Step
            {
                pattern: S("strategy", [V("StrategyTerm")]),
                action: S("llm", [S("params", [
                    S("action", [A("generate")]),
                    S("input", [A('Execute strategy "?StrategyTerm" or determine next step as structure: {"name": "outcome_or_step", "args": [...]}.')]),
                    S("type", [A("OUTCOME")])])])
            },
            // Query -> Outcome (Answer)
            {
                pattern: S("query", [V("QueryTerm")]),
                action: S("llm", [S("params", [
                    S("action", [A("generate")]),
                    S("input", [A('Answer query "?QueryTerm". Respond with structure {"value": "Your answer"}')]),
                    S("type", [A("OUTCOME")])])])
            },
            // Explicit User Interaction Rule
            {
                pattern: S("needs_clarification", [V("Topic")]),
                action: S("user_interaction", [S("params", [
                    S("prompt", [A("Please clarify: ?Topic")]),
                    S("options", [L([A("Yes"), A("No"), A("More Info")])]) // Example options
                ])])
            },
            // Simple Memory Add Rule
            {
                pattern: S("remember", [V("Fact")]),
                action: S("memory", [S("params", [
                    S("action", [A("add")]),
                    S("content", [V("Fact")]), // Assumes Fact is atom/string
                    S("type", [A("fact")])])])
            },
            // Rule to trigger failure synthesis (matches thought created by handleFailure)
            {
                pattern: S("synthesize_failure_rule", [V("FailedThoughtID"), V("ErrorHint")]),
                action: S("llm", [S("params", [
                    S("action", [A("generate")]),
                    S("input", [V("generation_prompt")]), // Prompt passed via metadata
                    S("type", [A("RULE")])])])
            },
            // Rule to trigger tool discovery
            {
                pattern: S("discover_tools_for", [V("StrategyContent")]),
                action: S("llm", [S("params", [
                    S("action", [A("generate")]),
                    S("input", [A('List external tools (web search, APIs) useful for: "?StrategyContent". Format: {"tools": [{"name": "tool_name", "type": "...", "endpoint": "...?"}, ...]}')]),
                    S("type", [A("TOOLS")])
                ])])
            },
            // Rule to trigger Goal Proposal
            {
                pattern: S("propose_related_goal", [V("ContextTerm")]),
                action: S("goal_proposal", [S("params", [])])
            }, // No params needed for basic proposal
            // Example Workflow Rule: Plan Trip -> Sequence
            {
                pattern: S("plan_trip", [V("Destination")]),
                action: S("sequence", [ // Define sequence of steps
                    S("user_interaction", [S("params", [ // Step 1: Ask budget
                        S("prompt", [A("What is the budget for ?Destination?")])])]),
                    S("web_search", [S("params", [ // Step 2: Search flights
                        S("query", [A("Flights to ?Destination")])])]),
                    S("llm", [S("params", [ // Step 3: Summarize (needs access to prior results - handled via context or memory)
                        S("action", [A("generate")]),
                        S("input", [A('Summarize trip plan for ?Destination based on budget and flight info found.')]),
                        S("type", [A("OUTCOME")])])])
                ])
            },
            { // Rule to handle the specific clarification thought generated by default actions
                pattern: S("needs_clarification", [V("GoalContent")]), // Match the structure created by the trigger
                action: S("user_interaction", [S("params", [ // Call the UI tool
                    S("prompt", [A("Clarify goal/topic: ?GoalContent")]), // Use the bound variable
                    S("options", [L([A("Proceed As Is"), A("Refine"), A("Cancel")])])
                ])])
            },
        ];

        let count = 0;
        for (const rData of rulesData) {
            const rule: Rule = Object.freeze({
                id: generateUUID(),
                belief: {...DEFAULT_BELIEF, lastUpdated: Date.now()},
                metadata: Object.freeze({
                    [Keys.AGENT_ID]: this.agentId,
                    [Keys.TIMESTAMP]: Date.now(),
                    bootstrap: true // Mark as a bootstrap rule
                }),
                ...rData
            });
            await this.ruleStore.add(rule); // Use async add for embedding
            count++;
        }
        console.log(`AgentLoop: Bootstrapped ${count} rules.`);
    }

    async start(): Promise<void> {
        if (this.isRunning) {
            console.warn(`Agent ${this.agentId} is already running.`);
            return;
        }
        this.isRunning = true;
        console.log(`Agent ${this.agentId} starting...`);

        // Load previous state OR Bootstrap rules if load fails/no file
        try {
            await this.persistence.load(this.thoughtStore, this.ruleStore, this.memoryStore, this.config.persistenceFilePath);
            if (this.ruleStore.getAll().length === 0) { // Check if rules loaded
                console.log("No rules found after loading, bootstrapping...");
                await this.bootstrapRules();
            }
        } catch (err: any) {
            console.error(`AgentLoop: Failed to load state from ${this.config.persistenceFilePath}. Bootstrapping fresh rules. Error: ${err.message}`);
            // Ensure stores are clean if load failed partway through
            this.thoughtStore.clear();
            this.ruleStore.clear();
            this.memoryStore.clear();
            await this.bootstrapRules(); // Bootstrap rules if loading failed
        }

        // Start UI
        this.ui.start();

        // Start Workers
        console.log(`AgentLoop: Starting ${this.config.numWorkers} workers...`);
        if (this.config.numWorkers <= 0) console.warn("Agent configured with 0 workers!");
        this.workers.length = 0; // Clear any previous worker references
        for (let i = 0; i < this.config.numWorkers; i++) {
            const worker = new Worker(
                this.agentId, this.thoughtStore, this.ruleStore, this.unifier,
                this.actionExecutor, // Pass ActionExecutor
                // ToolManager, MemoryStore, LLMTool not directly needed by worker loop itself
                this.config,
            );
            this.workers.push(worker);
            worker.start();
        }

        // Start periodic saving
        if (this.config.persistenceIntervalMillis > 0) {
            this.saveIntervalId = setInterval(async () => {
                if (!this.isRunning) return; // Don't save if stopping
                try {
                    // console.log(`AgentLoop: Performing periodic save...`); // Reduce log noise?
                    await this.persistence.save(this.thoughtStore, this.ruleStore, this.memoryStore, this.config.persistenceFilePath);
                } catch (err: any) {
                    console.error("AgentLoop: Periodic save failed:", err.message);
                }
            }, this.config.persistenceIntervalMillis);
        }

        console.log(`Agent ${this.agentId} started successfully. UI is active.`);
    }

    async stop(): Promise<void> {
        if (!this.isRunning) {
            console.warn(`Agent ${this.agentId} is not running.`);
            return;
        }
        console.log(`Agent ${this.agentId} stopping...`);
        this.isRunning = false; // Signal loops to stop

        // Stop UI first (might be waiting on input)
        this.ui.stop();

        // Stop periodic saving
        if (this.saveIntervalId) {
            clearInterval(this.saveIntervalId);
            this.saveIntervalId = null;
        }

        // Stop workers gracefully
        console.log(`AgentLoop: Stopping ${this.workers.length} workers...`);
        await Promise.allSettled(this.workers.map(worker => worker.stop()));
        this.workers.length = 0; // Clear worker array
        console.log(`AgentLoop: Workers stopped.`);

        // Perform final save
        try {
            console.log("AgentLoop: Performing final save...");
            await this.persistence.save(this.thoughtStore, this.ruleStore, this.memoryStore, this.config.persistenceFilePath);
            console.log("AgentLoop: Final save complete.");
        } catch (err: any) {
            console.error("AgentLoop: Final save failed:", err.message);
        }

        console.log(`Agent ${this.agentId} stopped.`);
    }

    /** Adds a new top-level INPUT thought to the system. */
    addInput(text: string): void {
        if (!this.isRunning) {
            console.warn("Agent not running. Cannot add input.");
            return;
        }
        if (!text || typeof text !== 'string' || text.trim() === '') {
            console.warn("Cannot add empty input.");
            return;
        }
        const newThought = createThought(
            this.agentId, 'INPUT', A(text.trim()), 'PENDING',
            {[Keys.UI_CONTEXT]: `User Input: ${text.trim()}`, [Keys.PRIORITY]: 1.5}, // Give user input high priority
            null // No parent for new input
        );
        this.thoughtStore.add(newThought);
        console.log(`Added user input thought ${newThought.id.substring(0, 6)}.`);
        // UI will pick it up on next render cycle
        // this.ui.render(); // Avoid direct render call from external method
    }
}

// --- Main Execution Example ---
async function main() {
    console.log("Starting FlowMind Agent...");

    const agent = new AgentLoop("agent_main");
    let shuttingDown = false;

    const shutdown = async (signal: string) => {
        if (shuttingDown) return;
        shuttingDown = true;
        console.log(`\nReceived ${signal}. Shutting down agent...`);
        // Stop the agent gracefully (stops UI, workers, saves state)
        await agent.stop();
        console.log("Shutdown complete.");
        // Ensure process exits cleanly, especially if readline is active
        process.exit(0);
    };

    // Graceful shutdown signals
    process.on('SIGINT', () => shutdown('SIGINT')); // Ctrl+C
    process.on('SIGTERM', () => shutdown('SIGTERM')); // kill command

    // More robust error handling
    process.on('uncaughtException', async (error, origin) => {
        console.error(`\nFATAL: Uncaught Exception at: ${origin}`, error);
        if (!shuttingDown) await shutdown('uncaughtException');
        process.exit(1);
    });
    process.on('unhandledRejection', async (reason, promise) => {
        console.error('\nFATAL: Unhandled Rejection at:', promise, 'reason:', reason);
        if (!shuttingDown) await shutdown('unhandledRejection');
        process.exit(1);
    });


    try {
        await agent.start();
        console.log("Agent started successfully. Use UI commands or Ctrl+C to exit.");
        // Example: Add initial input after agent starts
        // setTimeout(() => agent.addInput("Plan a weekend trip to the mountains."), 2000);
        // setTimeout(() => agent.addInput("Remember that John's birthday is next week."), 5000);

        // Keep the process alive. The readline loop in the UI should handle this.
        // If the UI exits prematurely, the process might end.
        // The shutdown hooks ensure cleanup on signals/errors.

    } catch (error) {
        console.error("Critical error during agent startup:", error);
        if (!shuttingDown) await agent.stop(); // Attempt cleanup
        process.exit(1);
    }
}

main();


