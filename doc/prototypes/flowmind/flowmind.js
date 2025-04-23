"use strict";
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
exports.AgentLoop = exports.Persistence = exports.UserInterface = exports.Worker = exports.ActionExecutor = exports.UserInteractionToolDefinition = exports.UserInteractionTool = exports.LLMTool = exports.ToolManager = exports.MemoryStore = exports.RuleStore = exports.ThoughtStore = exports.Unifier = exports.DEFAULT_CONFIG = exports.Keys = exports.isL = exports.isS = exports.isV = exports.isA = exports.L = exports.S = exports.V = exports.A = exports.DEFAULT_BELIEF = void 0;
exports.calculateBeliefScore = calculateBeliefScore;
exports.updateBelief = updateBelief;
exports.decayBelief = decayBelief;
exports.termToString = termToString;
exports.generateUUID = generateUUID;
exports.mergeMetadata = mergeMetadata;
exports.createThought = createThought;
exports.updateThought = updateThought;
exports.sampleWeighted = sampleWeighted;
exports.sleep = sleep;
exports.cosineSimilarity = cosineSimilarity;
// @ts-nocheck - Disable TypeScript checks for this file if needed for mocks/globals
// (Remove this line in a real project)
const crypto_1 = __importDefault(require("crypto.js"));
const fs_1 = require("fs.js");
const readline = __importStar(require("readline/promises"));
const process_1 = require("process");
// Initialize lastUpdated on creation
exports.DEFAULT_BELIEF = Object.freeze({ positive: 1.0, negative: 1.0, lastUpdated: Date.now() });
/** Calculates the belief score, smoothed probability. Optionally applies decay first. */
function calculateBeliefScore(belief, config) {
    let currentBelief = belief;
    // Optional: Apply decay before calculating score
    // if (config && config.beliefDecayRatePerMillis > 0) {
    //     currentBelief = decayBelief(belief, config.beliefDecayRatePerMillis, Date.now());
    // }
    const { positive, negative } = currentBelief;
    const total = positive + negative;
    // Laplace smoothing (add-1 smoothing)
    return total > 1e308 ? positive / total : (positive + 1.0) / (total + 2.0);
}
/** Updates belief counts based on success/failure, setting lastUpdated timestamp. */
function updateBelief(belief, success, config) {
    const now = Date.now();
    let currentBelief = belief;
    // Optional: Apply decay accumulated since last update before incrementing
    // if (config && config.beliefDecayRatePerMillis > 0) {
    //     currentBelief = decayBelief(belief, config.beliefDecayRatePerMillis, now);
    // }
    const { positive: basePositive, negative: baseNegative } = currentBelief;
    const newPositive = success ? Math.min(basePositive + 1.0, Number.MAX_VALUE) : basePositive;
    const newNegative = success ? baseNegative : Math.min(baseNegative + 1.0, Number.MAX_VALUE);
    // Prevent NaN/Infinity if counts somehow become invalid
    if (!isFinite(newPositive) || !isFinite(newNegative)) {
        console.warn("Belief update resulted in non-finite value, resetting to default.");
        return { ...exports.DEFAULT_BELIEF, lastUpdated: now }; // Reset with current timestamp
    }
    return { positive: newPositive, negative: newNegative, lastUpdated: now }; // Update timestamp
}
class HttpClient {
    baseUrl;
    constructor(baseUrl = '') {
        this.baseUrl = baseUrl;
    }
    async request(url, options = {}) {
        const fullUrl = url.startsWith('http') ? url : `${this.baseUrl}${url}`;
        const method = options.method || 'GET';
        try {
            const response = await fetch(fullUrl, options);
            if (!response.ok) {
                let errorBody = '';
                try {
                    errorBody = await response.text();
                }
                catch (_) {
                }
                const errorMsg = `HTTP Error ${response.status}: ${response.statusText} on ${method} ${fullUrl}`;
                console.error(`${errorMsg}. Body: ${errorBody.substring(0, 200)}`);
                const error = new Error(errorMsg);
                error.status = response.status;
                error.body = errorBody;
                throw error;
            }
            if (response.status === 204)
                return null;
            const contentType = response.headers.get('content-type');
            if (contentType && contentType.includes('application/json')) {
                const text = await response.text();
                let y = text ? JSON.parse(text) : null;
                //DEBUGGING
                //if (!y.embedding) console.log(options.body, y);
                return y;
            }
            else if (contentType && (contentType.includes('text') || contentType.includes('application/javascript'))) {
                return await response.text();
            }
            else {
                console.warn(`HttpClient: Unsupported content type '${contentType}' for ${method} ${fullUrl}. Returning raw text.`);
                return await response.text();
            }
        }
        catch (error) {
            console.error(`HttpClient Request Failed: ${method} ${fullUrl}`, error.message || error);
            throw new Error(`HTTP request failed for ${method} ${fullUrl}: ${error.message}`);
        }
    }
    async get(url) {
        return this.request(url, { method: 'GET' });
    }
    async post(url, body) {
        return this.request(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
            body: JSON.stringify(body)
        });
    }
}
/** Optional: Applies exponential decay to belief counts based on time elapsed. */
function decayBelief(belief, decayRatePerMillis, currentTime) {
    if (decayRatePerMillis <= 0 || !isFinite(decayRatePerMillis))
        return belief; // No decay
    const elapsedMillis = Math.max(0, currentTime - belief.lastUpdated);
    if (elapsedMillis === 0)
        return belief;
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
/** create Atom */
const A = (name) => ({ kind: 'atom', name });
exports.A = A;
/** create Variable */
const V = (name) => ({ kind: 'variable', name });
exports.V = V;
/** create Structure */
const S = (name, args) => ({
    kind: 'structure',
    name,
    args: Object.freeze([...args])
});
exports.S = S;
/** create List */
const L = (elements) => ({ kind: 'list', elements: Object.freeze([...elements]) });
exports.L = L;
const isA = (term) => term.kind === 'atom';
exports.isA = isA;
const isV = (term) => term.kind === 'variable';
exports.isV = isV;
const isS = (term) => term.kind === 'structure';
exports.isS = isS;
const isL = (term) => term.kind === 'list';
exports.isL = isL;
function termToString(t) {
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
/** Metadata Keys */
exports.Keys = Object.freeze({
    ROOT_ID: 'root_id', // UUID of the root INPUT thought (Note ID)
    AGENT_ID: 'agent_id', // UUID of the agent processing/owning the thought
    SHARED_WITH: 'shared_with', // List<UUID> of agent IDs to share with
    PARENT_ID: 'parent_id', // UUID of the thought that generated this one
    TIMESTAMP: 'timestamp', // Milliseconds since epoch of creation/update
    ERROR: 'error', // String describing failure reason
    PROVENANCE: 'provenance', // List<UUID> of Rule IDs or Thought IDs contributing
    UI_CONTEXT: 'ui_context', // String hint for display in the UI
    PRIORITY: 'priority', // Optional number for explicit prioritization (overrides belief score)
    EMBEDDING: 'embedding', // ReadonlyArray<number> embedding vector (optional)
    RETRY_COUNT: 'retry_count', // Number of times processing has been attempted
    RELATED_IDS: 'related_ids', // List<UUID> Knowledge Graph hint: Links to related Thoughts/Rules/MemoryEntries
    EXTRACTED_ENTITIES: 'extracted_entities', // List<string> Knowledge Graph hint: Key entities mentioned
    WORKFLOW_STEP: 'workflow_step', // Number: Index for sequence workflow
    WORKFLOW_ID: 'workflow_id', // UUID: Identifier for a workflow instance
    WORKFLOW_RESULTS: 'workflow_results', // Record<string, unknown>: Store results from previous workflow steps (use with caution)
});
exports.DEFAULT_CONFIG = Object.freeze({
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
function generateUUID() {
    return crypto_1.default.randomUUID();
}
/** Merges multiple metadata objects, freezing the result. Later objects overwrite earlier ones, except for specific array keys which are merged. */
function mergeMetadata(...metadatas) {
    const merged = {}; // Use a mutable object temporarily
    const arrayKeysToMerge = [exports.Keys.PROVENANCE, exports.Keys.RELATED_IDS, exports.Keys.EXTRACTED_ENTITIES]; // Keys whose array values should be combined
    for (const meta of metadatas) {
        if (meta) {
            for (const key in meta) {
                const existingValue = merged[key];
                const newValue = meta[key];
                if (arrayKeysToMerge.includes(key) && Array.isArray(existingValue) && Array.isArray(newValue)) {
                    // Combine and deduplicate arrays for specific keys
                    merged[key] = Array.from(new Set([...existingValue, ...newValue]));
                }
                else if (newValue !== undefined) { // Ensure we don't overwrite with undefined
                    merged[key] = newValue; // Otherwise, the later value overwrites
                }
            }
        }
    }
    return Object.freeze(merged);
}
/** Creates a standard Thought object, handling defaults and metadata inheritance. */
function createThought(agentId, type, content, status, additionalMetadata, parentThought, // Optional parent for inheriting metadata & linking
belief // Allow specifying initial belief (optional)
) {
    const id = generateUUID();
    const now = Date.now();
    const initialBelief = belief ? { ...belief, lastUpdated: now } : { ...exports.DEFAULT_BELIEF, lastUpdated: now };
    /* base metadata */
    const b = {
        [exports.Keys.AGENT_ID]: agentId,
        [exports.Keys.TIMESTAMP]: now,
    };
    let relatedIds = [];
    let provenance = [];
    if (parentThought) {
        b[exports.Keys.PARENT_ID] = parentThought.id;
        const rootId = parentThought.metadata[exports.Keys.ROOT_ID] ?? parentThought.id;
        b[exports.Keys.ROOT_ID] = rootId;
        relatedIds.push(parentThought.id);
        // Inherit provenance chain
        const parentProvenance = parentThought.metadata[exports.Keys.PROVENANCE];
        if (Array.isArray(parentProvenance)) {
            provenance = [...parentProvenance];
        }
        // Add parent ID to provenance if it's not already there (e.g., if parent was the direct trigger)
        // if (!provenance.includes(parentThought.id)) {
        //     provenance.push(parentThought.id);
        // }
        // Inherit workflow context if present
        if (parentThought.metadata[exports.Keys.WORKFLOW_ID]) {
            b[exports.Keys.WORKFLOW_ID] = parentThought.metadata[exports.Keys.WORKFLOW_ID];
            // Workflow step handled by ActionExecutor when creating next step thought
            // baseMetadata[MetadataKeys.WORKFLOW_STEP] = (Number(parentThought.metadata[MetadataKeys.WORKFLOW_STEP] ?? -1)) + 1;
            // Carry forward workflow results if needed (specific workflow logic TBD)
            // baseMetadata[MetadataKeys.WORKFLOW_RESULTS] = parentThought.metadata[MetadataKeys.WORKFLOW_RESULTS];
        }
        // Add parent's related IDs
        const parentRelated = parentThought.metadata[exports.Keys.RELATED_IDS];
        if (Array.isArray(parentRelated)) {
            relatedIds = relatedIds.concat(parentRelated);
        }
    }
    // Ensure agentId is set
    b[exports.Keys.AGENT_ID] = agentId;
    // Add root_id self-reference for new root thoughts if not inherited
    if ((type === 'INPUT') && !b[exports.Keys.ROOT_ID]) {
        b[exports.Keys.ROOT_ID] = id;
    }
    // Add collected provenance and related IDs to base metadata before merge
    if (provenance.length > 0) {
        b[exports.Keys.PROVENANCE] = Array.from(new Set(provenance));
    }
    if (relatedIds.length > 0) {
        // Filter out potential self-references just in case
        b[exports.Keys.RELATED_IDS] = Array.from(new Set(relatedIds.filter(rId => rId !== id)));
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
function updateThought(thoughtStore, currentThought, // The exact object reference the worker sampled
newStatus, additionalMetadata, config // Pass config for belief updates/decay
) {
    const latestThoughtVersion = thoughtStore.get(currentThought.id);
    // *** Optimistic Concurrency Check using Object Identity ***
    // This ensures the thought hasn't been modified by another worker since we sampled it.
    if (latestThoughtVersion !== currentThought) {
        console.warn(`updateThought [${currentThought.id.substring(0, 6)}]: Stale object reference. Expected version timestamp ${currentThought.metadata[exports.Keys.TIMESTAMP]}, found ${latestThoughtVersion?.metadata[exports.Keys.TIMESTAMP]}. Update aborted.`);
        return false;
    }
    // Determine success based on target status (DONE means success for belief update)
    const isSuccess = newStatus === 'DONE';
    const updatedBelief = updateBelief(latestThoughtVersion.belief, isSuccess, config);
    // Optional: Apply belief decay globally here if not done elsewhere
    // const finalBelief = decayBelief(updatedBelief, config.beliefDecayRatePerMillis, Date.now());
    const finalBelief = updatedBelief;
    // Merge metadata, ensuring the latest timestamp and handling array merges
    const finalMetadata = mergeMetadata(latestThoughtVersion.metadata, // Base is the version we confirmed exists
    additionalMetadata, // Apply the specific updates for this change
    { [exports.Keys.TIMESTAMP]: Date.now() } // Always update timestamp
    );
    const updatedThought = {
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
function sampleWeighted(items, contextSimilarityScores, // Optional map of Item -> Similarity Score (0 to 1)
boostFactor = 0 // Optional boost factor (0 = no boost, >0 = apply boost)
) {
    if (items.length === 0)
        return null;
    // Adjust weights based on similarity scores if provided
    const weightedItems = items.map(([weight, item]) => {
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
        if (random < weight)
            return item;
        random -= weight;
    }
    // Fallback for potential floating point inaccuracies
    return validItems[validItems.length - 1][1];
}
function apply(t, b) {
    // Implementation remains the same...
    switch (t.kind) {
        case 'variable':
            return b.get(t.name) ?? t;
        case 'structure':
            return (0, exports.S)(t.name, t.args.map(arg => apply(arg, b)));
        case 'list':
            return (0, exports.L)(t.elements.map(el => apply(el, b)));
        case 'atom':
            return t;
    }
}
function contains(t, variableName) {
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
function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}
// --- Unification ---
class Unifier {
    // Implementation remains the same as before...
    unify(term1, term2) {
        const bindings = new Map();
        return this.unifyRecursive(term1, term2, bindings) ? bindings : null;
    }
    unifyRecursive(term1, term2, bindings) {
        const t1 = apply(term1, bindings);
        const t2 = apply(term2, bindings);
        if ((0, exports.isV)(t1))
            return this.bind(t1, t2, bindings);
        if ((0, exports.isV)(t2))
            return this.bind(t2, t1, bindings);
        if ((0, exports.isA)(t1) && (0, exports.isA)(t2))
            return t1.name === t2.name;
        if ((0, exports.isS)(t1) && (0, exports.isS)(t2)) {
            return t1.name === t2.name &&
                t1.args.length === t2.args.length &&
                t1.args.every((arg, i) => this.unifyRecursive(arg, t2.args[i], bindings));
        }
        if ((0, exports.isL)(t1) && (0, exports.isL)(t2)) {
            return t1.elements.length === t2.elements.length &&
                t1.elements.every((el, i) => this.unifyRecursive(el, t2.elements[i], bindings));
        }
        // Consider term equality as a base case? No, unification handles structure.
        if (t1.kind === t2.kind && JSON.stringify(t1) === JSON.stringify(t2))
            return true; // Basic equality check for atoms/identical structures
        return false;
    }
    bind(variable, term, bindings) {
        if (bindings.has(variable.name)) {
            return this.unifyRecursive(bindings.get(variable.name), term, bindings);
        }
        if ((0, exports.isV)(term) && bindings.has(term.name)) {
            return this.unifyRecursive(variable, bindings.get(term.name), bindings);
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
    findAndSample(thought, rules, boostFactor, // Configured boost factor
    ruleEmbeddings // Precomputed rule embeddings
    ) {
        const thoughtEmbedding = thought.metadata[exports.Keys.EMBEDDING];
        let contextSimilarities;
        let requiresWeightedSampling = false; // Flag if boosting is applied
        // Prepare candidate matches with potentially boosted scores
        const matches = rules
            .map(rule => {
            const bindings = this.unify(thought.content, rule.pattern);
            if (!bindings)
                return null; // Unification failed
            const baseScore = calculateBeliefScore(rule.belief); // Use non-decayed belief for selection fairness? Or config based?
            const matchObject = { rule, bindings };
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
            return [boostedScore, matchObject];
        })
            // Filter out nulls (unification failures) and matches with zero score
            .filter((match) => match !== null && match[0] > 0);
        if (matches.length === 0)
            return null; // No rules matched or all had zero score
        // If boosting wasn't applied or resulted in uniform scores, potentially optimize sampling?
        // For now, always use weighted sampling for simplicity and correctness with scores.
        return sampleWeighted(matches); // sampleWeighted handles the distribution based on scores
    }
}
exports.Unifier = Unifier;
// --- Storage Components ---
class ThoughtStore {
    agentId;
    thoughts = new Map();
    constructor(agentId) {
        this.agentId = agentId;
    }
    get(id) {
        return this.thoughts.get(id);
    }
    getEmbedding(id) {
        return this.thoughts.get(id)?.metadata[exports.Keys.EMBEDDING];
    }
    add(thought) {
        const targetAgentId = thought.metadata[exports.Keys.AGENT_ID] ?? this.agentId;
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
    update(oldThoughtRef, updatedThought) {
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
    samplePending(contextThought, // Optional: The thought providing context for boosting
    boostFactor = 0, config // Pass config for belief score calculation
    ) {
        const pendingThoughts = Array.from(this.thoughts.values())
            .filter(t => t.status === 'PENDING');
        if (pendingThoughts.length === 0)
            return null;
        let contextSimilarities;
        const contextEmbedding = contextThought?.metadata[exports.Keys.EMBEDDING];
        // Calculate similarities if context is provided and boosting is enabled
        if (contextEmbedding && boostFactor > 0) {
            contextSimilarities = new Map();
            pendingThoughts.forEach(pending => {
                const pendingEmbedding = pending.metadata[exports.Keys.EMBEDDING];
                if (pendingEmbedding) {
                    const similarity = cosineSimilarity(contextEmbedding, pendingEmbedding);
                    contextSimilarities.set(pending, similarity);
                }
                else {
                    contextSimilarities.set(pending, 0); // No embedding, no similarity boost
                }
            });
        }
        // Prepare items for weighted sampling
        const weightedItems = pendingThoughts.map(t => {
            // Use explicit priority if set and valid, otherwise use belief score
            const priority = t.metadata[exports.Keys.PRIORITY];
            const baseWeight = typeof priority === 'number' && isFinite(priority) && priority > 0
                ? priority
                : calculateBeliefScore(t.belief, config); // Default weight from belief
            return [baseWeight, t]; // Base weight calculation
        });
        // Sample using the utility function which handles context boosting internally
        return sampleWeighted(weightedItems, contextSimilarities, boostFactor);
    }
    findByParent(parentId) {
        return Object.freeze(Array.from(this.thoughts.values()).filter(t => t.metadata[exports.Keys.PARENT_ID] === parentId));
    }
    findByRoot(rootId) {
        return Object.freeze(Array.from(this.thoughts.values()).filter(t => t.metadata[exports.Keys.ROOT_ID] === rootId));
    }
    getAllThoughts() {
        return Object.freeze(Array.from(this.thoughts.values()));
    }
    clear() {
        this.thoughts.clear();
    } // For persistence loading
}
exports.ThoughtStore = ThoughtStore;
class RuleStore {
    llmTool;
    rules = new Map();
    embeddings = new Map(); // Cache rule embeddings
    constructor(llmTool) {
        this.llmTool = llmTool;
    } // Optional LLM for embedding generation
    get(id) {
        return this.rules.get(id);
    }
    /** Adds a rule and generates/caches its embedding if LLMTool is available. */
    async add(rule) {
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
                }
                else {
                    console.warn(`RuleStore: Embedding generation returned empty for rule ${rule.id}`);
                }
            }
            catch (error) {
                console.error(`RuleStore: Failed to generate embedding for rule ${rule.id}:`, error);
            }
        }
    }
    /** Updates a rule using optimistic concurrency (object identity). Re-caches embedding. */
    async update(oldRuleRef, updatedRule) {
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
    getAll() {
        return Object.freeze(Array.from(this.rules.values()));
    }
    getEmbeddings() {
        return this.embeddings;
    }
    clear() {
        this.rules.clear();
        this.embeddings.clear();
    } // For persistence loading
}
exports.RuleStore = RuleStore;
class MemoryStore {
    entries = new Map();
    // Constructor is now simple, embedding handled by MemoryTool or externally.
    constructor() {
    }
    /** Adds a pre-formed MemoryEntry. */
    async add(entry) {
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
            metadata: Object.freeze({ ...(entry.metadata ?? {}) })
        });
        this.entries.set(entry.id, finalEntry);
    }
    /**
     * Finds similar memory entries based on embedding similarity.
     * Applies filtering based on optional context criteria.
     */
    async findSimilar(queryEmbedding, limit, filterContext) {
        if (!queryEmbedding || queryEmbedding.length === 0) {
            console.warn("MemoryStore.findSimilar called with empty or invalid query embedding.");
            return [];
        }
        let candidates = Array.from(this.entries.values());
        // --- Filtering based on filterContext (Knowledge Graph hints) ---
        if (filterContext) {
            candidates = candidates.filter(entry => {
                const meta = entry.metadata;
                if (!meta)
                    return true; // Keep if no metadata to filter on? Or discard? Let's keep.
                // Check Type
                if (filterContext.requiredType && meta['type'] !== filterContext.requiredType) {
                    return false;
                }
                // Check Relation
                if (filterContext.relatedToId) {
                    const related = meta[exports.Keys.RELATED_IDS];
                    // Check if the relatedToId is present in the entry's related_ids
                    if (!related || !related.includes(filterContext.relatedToId)) {
                        return false;
                    }
                }
                // Check Entities (requires ALL specified entities to be present)
                if (filterContext.requiredEntities && filterContext.requiredEntities.length > 0) {
                    const entities = meta[exports.Keys.EXTRACTED_ENTITIES];
                    // Entry must have entities, and all required entities must be included
                    if (!entities || !filterContext.requiredEntities.every(req => entities.includes(req))) {
                        return false;
                    }
                }
                return true; // Passed all filters
            });
        }
        // --- Scoring & Ranking ---
        if (candidates.length === 0)
            return []; // No candidates after filtering
        const scoredEntries = candidates
            .map(entry => {
            const score = cosineSimilarity(queryEmbedding, entry.embedding);
            // Optional: Add boosting based on other context relevance here if needed
            return { score, entry };
        })
            .filter(item => isFinite(item.score)); // Ensure score is valid
        // Sort by similarity score DESC
        scoredEntries.sort((a, b) => b.score - a.score);
        return Object.freeze(scoredEntries.slice(0, limit).map(item => item.entry));
    }
    get(id) {
        return this.entries.get(id);
    }
    getAllEntries() {
        return Object.freeze(Array.from(this.entries.values()));
    }
    // Used by Persistence to load entries
    _loadEntry(entry) {
        // Ensure data integrity on load
        if (entry.id && entry.content && Array.isArray(entry.embedding)) {
            // Use the main add method to ensure freezing and warnings
            this.add(entry);
        }
        else {
            console.warn(`MemoryStore: Ignoring invalid memory entry during load: ID=${entry?.id}`);
        }
    }
    clear() {
        this.entries.clear();
    } // For persistence loading
}
exports.MemoryStore = MemoryStore;
/** Calculates cosine similarity between two vectors. */
function cosineSimilarity(vecA, vecB) {
    if (!vecA || !vecB || vecA.length === 0 || vecA.length !== vecB.length)
        return 0;
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
class ToolManager {
    memoryStore;
    thoughtStore;
    config;
    llmTool;
    // Store full definitions now
    tools = new Map();
    constructor(
    // Pass dependencies tools might need during registration or execution
    memoryStore, thoughtStore, config, llmTool // Provide central LLM Tool instance
    ) {
        this.memoryStore = memoryStore;
        this.thoughtStore = thoughtStore;
        this.config = config;
        this.llmTool = llmTool;
        // Register core tools using their definitions
        this.registerToolDefinition(new LLMToolDefinition(this.config, this.llmTool)); // Pass instance
        this.registerToolDefinition(new MemoryToolDefinition(this.memoryStore, this.llmTool));
        this.registerToolDefinition(new GoalProposalToolDefinition(this.memoryStore, this)); // Pass self (ToolManager)
        // UserInteractionTool instance needs to be created externally and passed during registration
    }
    /** Registers a tool using its definition object. */
    registerToolDefinition(d) {
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
    getToolDefinition(name) {
        return this.tools.get(name);
    }
    /** Retrieves all tool definitions (e.g., for LLM context prompting). */
    getAllToolDefinitions() {
        return Object.freeze(Array.from(this.tools.values()));
    }
    /** Executes a tool, performs schema validation, handles errors, returns result Thought. */
    // Inside ToolManager.execute:
    async execute(toolName, params, parentThought, agentId) {
        const definition = this.tools.get(toolName); // Get definition
        if (!definition) {
            // ... (tool not found error handling - remains the same) ...
            const errorMsg = `Unknown tool: ${toolName}`;
            console.error(`ToolManager: ${errorMsg}`);
            return createThought(agentId, 'OUTCOME', (0, exports.A)('error_tool_not_found'), 'FAILED', { [exports.Keys.ERROR]: errorMsg, [exports.Keys.UI_CONTEXT]: `Error: Tool '${toolName}' not found` }, parentThought);
        }
        // --- Parameter Validation ---
        let validationError = null;
        if (this.config.enableSchemaValidation) {
            validationError = this.validateParams(params, definition.parameters);
        }
        if (validationError) {
            // ... (invalid params error handling - remains the same) ...
            const errorMsg = `Invalid parameters for tool '${toolName}': ${validationError}`;
            console.error(`ToolManager: ${errorMsg}`);
            return createThought(agentId, 'OUTCOME', (0, exports.A)('error_invalid_params'), 'FAILED', { [exports.Keys.ERROR]: errorMsg, [exports.Keys.UI_CONTEXT]: `Error: Invalid params for '${toolName}'` }, parentThought);
        }
        // --- Execution (Wrap the actual tool execution in try/catch) ---
        try {
            const toolResult = await definition.tool.execute(params, parentThought, agentId);
            if (!toolResult || !toolResult.id || !toolResult.type || !toolResult.content || !toolResult.status) {
                throw new Error(`Tool '${toolName}' returned an invalid Thought object.`);
            }
            // --- Prepare final metadata ---
            let embedding = undefined;
            if (!toolResult.metadata[exports.Keys.EMBEDDING] && toolResult.status !== 'FAILED' && this.llmTool) {
                try {
                    const generatedEmbedding = await this.llmTool.generateEmbedding(termToString(toolResult.content));
                    if (generatedEmbedding.length > 0) {
                        embedding = Object.freeze(generatedEmbedding);
                    }
                }
                catch (embedError) {
                    console.warn(`ToolManager: Failed to generate embedding for result of ${toolName}:`, embedError);
                }
            }
            const finalMetadataAdditions = {
                [exports.Keys.AGENT_ID]: agentId,
                [exports.Keys.PARENT_ID]: parentThought.id,
                [exports.Keys.ROOT_ID]: parentThought.metadata[exports.Keys.ROOT_ID] ?? parentThought.id,
                [exports.Keys.RELATED_IDS]: [parentThought.id],
                ...(embedding && { [exports.Keys.EMBEDDING]: embedding }),
                ...(parentThought.metadata[exports.Keys.WORKFLOW_ID] && { /* ... workflow context ... */}),
            };
            const finalMetadata = mergeMetadata(finalMetadataAdditions, toolResult.metadata);
            return Object.freeze({ ...toolResult, metadata: finalMetadata });
        }
        catch (error) {
            // --- Error Handling for Tool Execution Failure ---
            // This catch block now correctly handles errors from definition.tool.execute()
            // It has access to 'toolName' but NOT 'definition'.
            const errorMsg = error instanceof Error ? error.message : String(error);
            console.error(`ToolManager: Error executing tool "${toolName}":`, error); // Use toolName here
            return createThought(agentId, 'OUTCOME', (0, exports.A)('error_tool_execution'), 'FAILED', {
                [exports.Keys.ERROR]: `Tool '${toolName}' failed: ${errorMsg}`, // Use toolName
                [exports.Keys.UI_CONTEXT]: `Failed: ${toolName}` // Use toolName
            }, parentThought);
        }
    }
    /** Validates parameters against the tool's schema. Returns error message string or null if valid. */
    validateParams(params, schema) {
        for (const paramName in schema) {
            const rule = schema[paramName];
            const value = params[paramName];
            // --- Evaluate 'required' condition ---
            let isRequired = false;
            if (typeof rule.required === 'function') {
                try {
                    // Call the function with the params object to determine requirement
                    isRequired = rule.required(params);
                }
                catch (e) {
                    console.error(`ToolManager: Error evaluating required function for param "${paramName}": ${e.message}`);
                    return `Internal validation error for parameter "${paramName}"`; // Fail validation on error
                }
            }
            else {
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
                }
                else if (type === 'array') {
                    if (valueType !== 'array') {
                        return `Parameter "${paramName}" must be an array (got ${valueType})`;
                    }
                    if (rule.itemType && !value.every(item => typeof item === rule.itemType)) {
                        return `All items in array "${paramName}" must be of type ${rule.itemType}`;
                    }
                }
                else if (valueType !== type && type !== 'object') { // Allow any non-null object if type is 'object'
                    // Allow number to satisfy string? No, keep strict for now.
                    return `Parameter "${paramName}" must be of type ${type} (got ${valueType})`;
                }
                else if (type === 'object' && valueType !== 'object') {
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
exports.ToolManager = ToolManager;
class LLMTool {
    config;
    httpClient; // Assume HttpClient is imported/defined
    generateEndpoint;
    embedEndpoint;
    model;
    constructor(config) {
        this.config = config;
        // Ensure this uses the actual HttpClient class
        this.httpClient = new HttpClient(config.ollamaApiBaseUrl);
        this.generateEndpoint = `/api/generate`;
        this.embedEndpoint = `/api/embeddings`;
        this.model = config.ollamaModel;
    }
    // --- LLM Tool Execution ---
    async execute(params, parentThought, agentId) {
        // Parameters assumed validated by ToolManager based on LLMToolDefinition
        const action = params['action'];
        const input = params['input'];
        switch (action) {
            case 'generate':
                const targetTypeStr = params['type'];
                // Ensure only valid Types are used
                const validTypes = ['INPUT', 'GOAL', 'STRATEGY', 'OUTCOME', 'QUERY', 'RULE', 'TOOLS', 'WORKFLOW_STEP'];
                const targetType = targetTypeStr && validTypes.includes(targetTypeStr.toUpperCase())
                    ? targetTypeStr.toUpperCase()
                    : 'OUTCOME'; // Default result type
                const format = params['format'] ?? 'json'; // Default to JSON
                const toolDefs = params['tool_definitions']; // For prompting with schemas
                let prompt = input;
                // Optional: Enhance prompt if tool definitions are provided
                if (toolDefs && toolDefs.length > 0) {
                    // Format tool definitions for the prompt
                    const schemaDescriptions = toolDefs.map(def => `${def.name}(${Object.entries(def.parameters).map(([pName, pDef]) => `${pName}: ${pDef.type}${pDef.required ? '' : '?'}`).join(', ')})`
                    // Optional: Add description: `: ${def.description}`
                    ).join('\n');
                    prompt += `\n\nAvailable tools:\n${schemaDescriptions}\nGenerate response ${format === 'json' ? 'in JSON format conforming to the expected output structure or required tool parameters' : ''}.`;
                }
                const requestBodyGen = { model: this.model, prompt, format, stream: false };
                // console.debug(`LLM Generate Request: ${JSON.stringify(requestBodyGen).substring(0, 300)}...`); // Log request
                const responseGen = await this.httpClient.post(this.generateEndpoint, requestBodyGen);
                const resultText = responseGen?.response;
                if (!resultText)
                    throw new Error('LLM response missing "response" field');
                // console.debug(`LLM Generate Response: ${resultText.substring(0, 300)}...`); // Log response
                const content = this.parseLmOutput(resultText, targetType, format);
                const uiContext = `LLM Gen (${targetType}): ${termToString(content).substring(0, 50)}...`;
                const resultMetadata = { [exports.Keys.UI_CONTEXT]: uiContext };
                // Attempt to generate and add embedding immediately (embedding handled by ToolManager now)
                // try {
                //     const embedding = await this.generateEmbedding(termToString(content));
                //     if (embedding.length > 0) resultMetadata[MetadataKeys.EMBEDDING] = Object.freeze(embedding);
                // } catch (e) { console.warn("LLMTool: Failed embedding generated content", e); }
                // Let createThought handle freezing and base metadata
                return createThought(agentId, targetType, content, 'PENDING', resultMetadata, parentThought);
            case 'embed':
                const embedding = await this.generateEmbedding(input);
                if (embedding.length === 0)
                    throw new Error("Embedding generation failed or returned empty.");
                // Embedding result is usually just DONE, doesn't need further processing.
                // Content indicates what was embedded for clarity.
                return createThought(agentId, 'OUTCOME', (0, exports.A)(`embedded(${input.substring(0, 20)}...)`), 'DONE', {
                    [exports.Keys.EMBEDDING]: Object.freeze(embedding), // Store the embedding in metadata
                    [exports.Keys.UI_CONTEXT]: 'Content embedded'
                }, parentThought);
        }
    }
    // --- Embedding Generation ---
    async generateEmbedding(content) {
        try {
            if (!content?.trim()) {
                console.warn("LLMTool: Attempted to generate embedding for empty content.");
                return [];
            }
            const requestBody = { model: this.model, prompt: content };
            // console.debug(`LLM Embed Request for: "${content.substring(0, 100)}..."`);
            const response = await this.httpClient.post(this.embedEndpoint, requestBody);
            // console.debug(`LLM Embed Response received (length: ${response?.embedding?.length})`);
            return response?.embedding ? Object.freeze(response.embedding) : [];
        }
        catch (error) {
            // Log specific error if possible
            console.error(`LLMTool: Failed to generate embedding for content starting with "${content.substring(0, 50)}...":`, error.message || error);
            return []; // Return empty array on failure
        }
    }
    // --- Output Parsing ---
    parseLmOutput(outputString, expectedType, format) {
        if (format === 'text') {
            return (0, exports.A)(outputString); // Treat raw text as an atom
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
                        const args = Array.isArray(json.args) ? json.args.map((arg) => this.jsonToTerm(arg)) : [];
                        return (0, exports.S)(json.name, args);
                    }
                    else if (json.value !== undefined) { // Allow simple values wrapped in 'value'
                        return this.jsonToTerm(json.value);
                    }
                    // Allow bare primitives if the entire JSON is just that primitive
                    if (typeof json !== 'object') {
                        return this.jsonToTerm(json);
                    }
                    console.warn(`LLMTool: Expected JSON structure {name, args} or {value} for ${expectedType}, got:`, outputString.substring(0, 200));
                    return (0, exports.A)(outputString); // Fallback: wrap raw JSON string
                case 'RULE':
                    // Expect {"pattern": {...}, "action": {...}} where pattern/action are Term JSON
                    if (json.pattern && json.action && typeof json.pattern === 'object' && typeof json.action === 'object') {
                        try {
                            const patternTerm = this.jsonToTerm(json.pattern);
                            const actionTerm = this.jsonToTerm(json.action);
                            // Represent the rule definition itself as a structure
                            return (0, exports.S)("rule_definition", [patternTerm, actionTerm]);
                        }
                        catch (termParseError) {
                            throw new Error(`Failed to parse nested terms in RULE JSON: ${termParseError}`);
                        }
                    }
                    throw new Error('Expected JSON with "pattern" and "action" objects for RULE type');
                case 'TOOLS':
                    // Expect {"tools": [...]} (list of specs) or {"tool_call": {name, params}}
                    if (Array.isArray(json.tools)) {
                        // Represents a *list* of tool definitions/specs generated by LLM
                        const toolTerms = json.tools.map((toolSpec) => {
                            if (typeof toolSpec === 'object' && toolSpec !== null && toolSpec.name) {
                                // Simplified representation, assumes args describe the spec
                                // tool_spec(name, type, endpoint?, description?, params_schema?)
                                const args = [(0, exports.A)(String(toolSpec.name))];
                                if (toolSpec.type)
                                    args.push((0, exports.A)(String(toolSpec.type)));
                                else
                                    args.push((0, exports.A)('unknown'));
                                if (toolSpec.endpoint)
                                    args.push((0, exports.A)(String(toolSpec.endpoint)));
                                // Add more args if needed based on LLM output format
                                return (0, exports.S)("tool_spec", args);
                            }
                            return (0, exports.A)(JSON.stringify(toolSpec)); // Fallback for unknown format
                        });
                        return (0, exports.L)(toolTerms);
                    }
                    else if (typeof json.tool_call === 'object' && json.tool_call !== null && json.tool_call.name) {
                        // Represents a single tool call generated by LLM
                        const toolName = String(json.tool_call.name);
                        const params = typeof json.tool_call.params === 'object' && json.tool_call.params !== null ? json.tool_call.params : {};
                        // Convert params object into params( key1(value1_term), key2(value2_term) )
                        const paramTerms = Object.entries(params).map(([key, value]) => (0, exports.S)(key, [this.jsonToTerm(value)]));
                        return (0, exports.S)(toolName, [(0, exports.S)("params", paramTerms)]);
                    }
                    throw new Error('Expected JSON with "tools" array or "tool_call" object for TOOLS type');
                default:
                    // Fallback for unexpected types - treat as atom
                    console.warn(`LLMTool: Parsing output for unexpected type ${expectedType}. Treating as Atom.`);
                    return (0, exports.A)(outputString);
            }
        }
        catch (e) {
            console.warn(`LLMTool: Failed to parse LLM JSON output ("${outputString.substring(0, 100)}..."). Treating as Atom. Error:`, e.message);
            return (0, exports.A)(outputString); // Fallback if JSON parsing fails
        }
    }
    /** Converts generic JSON value to a Term representation. */
    jsonToTerm(x) {
        // Simplified - assumes ToolManager.extractParamsFromTerm handles complex objects later
        switch (typeof x) {
            case 'string':
                return (0, exports.A)(x);
            case 'number':
                return (0, exports.A)(x.toString());
            case 'boolean':
                return (0, exports.A)(x.toString());
            case 'undefined':
                return (0, exports.A)('undefined');
            case 'object':
                if (x === null) {
                    return (0, exports.A)('null');
                }
                if (Array.isArray(x)) {
                    return (0, exports.L)(x.map(el => this.jsonToTerm(el)));
                }
                // Heuristic: Check if it looks like a Term structure itself (e.g., nested in rule)
                let k = x.kind;
                let n = x.name;
                if (k && ['atom', 'variable', 'structure', 'list'].includes(k)) {
                    try { // Be careful with recursive parsing of potentially malformed data
                        if (k === 'atom' && typeof n === 'string')
                            return (0, exports.A)(n);
                        if (k === 'variable' && typeof n === 'string')
                            return (0, exports.V)(n);
                        if (k === 'structure' && typeof n === 'string' && Array.isArray(x.args)) {
                            return (0, exports.S)(n, x.args.map((arg) => this.jsonToTerm(arg)));
                        }
                        if (k === 'list' && Array.isArray(x.elements)) {
                            return (0, exports.L)(x.elements.map((el) => this.jsonToTerm(el)));
                        }
                    }
                    catch (parseError) {
                        console.warn("LLMTool: Error parsing nested Term JSON, treating as Atom:", parseError.message);
                        // Fall through to treat as generic object Atom
                    }
                }
                // Heuristic: Check for common LLM output structure { name: string, args: any[] }
                if (typeof n === 'string' && Array.isArray(x.args)) {
                    return (0, exports.S)(n, x.args.map((arg) => this.jsonToTerm(arg)));
                }
                // Default for complex objects: stringify into an Atom
                // This might lose structure needed by parameter extraction later.
                // A better approach might be needed if complex nested objects are common.
                return (0, exports.A)(JSON.stringify(x));
            default: // symbol, function, bigint -> convert to string atom
                return (0, exports.A)(String(x));
        }
    }
}
exports.LLMTool = LLMTool;
// Define ToolDefinition for LLMTool
class LLMToolDefinition {
    name = 'llm';
    description = 'Interacts with the Large Language Model for text generation or embedding.';
    parameters = {
        action: { type: 'string', required: true, description: 'The action to perform: "generate" or "embed".' },
        input: { type: 'string', required: true, description: 'The input text/prompt for the action.' },
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
    tool; // Instance of LLMTool
    constructor(config, instance) {
        this.tool = instance; // Use the provided instance
    }
}
// --- Memory Tool ---
class MemoryTool {
    memoryStore;
    llmTool;
    constructor(memoryStore, llmTool // Needed to generate embeddings
    ) {
        this.memoryStore = memoryStore;
        this.llmTool = llmTool;
    }
    async execute(params, parentThought, agentId) {
        // Assumes parameters validated by ToolManager based on MemoryToolDefinition
        const action = params['action'];
        switch (action) {
            case 'add':
                const content = params['content']; // Required by schema
                const type = params['type'] ?? 'generic'; // Default type
                const metadata = typeof params['metadata'] === 'object' ? params['metadata'] : {};
                const embedding = await this.llmTool.generateEmbedding(content);
                if (embedding.length === 0)
                    throw new Error(`MemoryTool "add": Failed to generate embedding for content.`);
                const entryId = generateUUID();
                const finalMetadata = mergeMetadata({
                    type: type,
                    timestamp: Date.now(),
                    agent_id: agentId,
                    source_thought_id: parentThought.id, // Link to the thought that triggered the add
                    root_id: parentThought.metadata[exports.Keys.ROOT_ID] ?? parentThought.id,
                    [exports.Keys.RELATED_IDS]: [parentThought.id] // Link memory entry back to source thought
                }, metadata);
                const entry = { id: entryId, embedding, content, metadata: finalMetadata };
                await this.memoryStore.add(entry); // Add to store
                // Result indicates success and links the memory entry
                return createThought(agentId, 'OUTCOME', (0, exports.A)(`stored_memory(${entryId.substring(0, 6)})`), 'DONE', {
                    [exports.Keys.UI_CONTEXT]: `Stored to memory (${type})`,
                    [exports.Keys.RELATED_IDS]: [entryId] // Link outcome to the new memory entry ID
                }, parentThought);
            case 'search':
                const query = params['query']; // Required by schema
                const limit = typeof params['limit'] === 'number' ? params['limit'] : exports.DEFAULT_CONFIG.memorySearchLimit;
                // Use filter context provided in params
                const filterContext = typeof params['filterContext'] === 'object' ? params['filterContext'] : undefined;
                const queryEmbedding = await this.llmTool.generateEmbedding(query);
                if (queryEmbedding.length === 0)
                    throw new Error(`MemoryTool "search": Failed to generate embedding for query.`);
                const results = await this.memoryStore.findSimilar(queryEmbedding, limit, filterContext);
                // Represent results as a ListTerm of Structures for structured processing downstream
                // memory_result(content_atom, id_atom, type_atom?)
                const resultTerms = results.map(r => (0, exports.S)("memory_result", [
                    (0, exports.A)(r.content),
                    (0, exports.A)(r.id),
                    (0, exports.A)(r.metadata?.type ?? 'unknown'), // Include type
                    // Potential: createAtom(JSON.stringify(r.metadata)) // Add full metadata if needed
                ]));
                const outcomeContent = results.length > 0 ? (0, exports.L)(resultTerms) : (0, exports.A)('no_memory_results');
                return createThought(agentId, 'OUTCOME', outcomeContent, 'DONE', {
                    [exports.Keys.UI_CONTEXT]: `Memory search results (${results.length})`,
                    // Link outcome to the retrieved memory entry IDs
                    [exports.Keys.RELATED_IDS]: results.map(r => r.id)
                }, parentThought);
            default:
                // Should not happen if validation is enabled
                throw new Error(`MemoryTool: Unexpected action "${action}"`);
        }
    }
}
// Define ToolDefinition for MemoryTool
class MemoryToolDefinition {
    name = 'memory';
    description = 'Stores or retrieves information from the persistent MemoryStore using embeddings.';
    parameters = {
        action: { type: 'string', required: true, description: 'The action: "add" or "search".' },
        // Add params - required only if action is 'add'
        content: {
            type: 'string',
            required: (params) => params.action === 'add', // Check action param
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
            required: (params) => params.action === 'search', // Check action param
            description: 'For "search": The query text to search for.'
        },
        limit: { type: 'number', required: false, description: 'For "search": Maximum number of results (default 5).' },
        filterContext: {
            type: 'object',
            required: false,
            description: 'For "search": Optional filters {requiredEntities?: string[], relatedToId?: UUID, requiredType?: string}.'
        }
    };
    tool;
    constructor(memoryStore, llmTool) {
        this.tool = new MemoryTool(memoryStore, llmTool);
    }
}
// --- Goal Proposal Tool ---
class GoalProposalTool {
    memoryStore;
    toolManager;
    constructor(memoryStore, toolManager // Needs ToolManager to call LLM/Memory tools
    ) {
        this.memoryStore = memoryStore;
        this.toolManager = toolManager;
    }
    async execute(params, parentThought, agentId) {
        // 1. Gather context from memory
        const contextQuery = "Recent goals, plans, tasks, user inputs, or completed milestones";
        let contextText = "No relevant context found in memory.";
        let contextIds = [];
        try {
            // Use memory tool via ToolManager to get context
            const memParams = { action: 'search', query: contextQuery, limit: 5 };
            // Create a temporary parent thought for the memory call if needed, or use the original parent? Use parent.
            const memResultThought = await this.toolManager.execute('memory', memParams, parentThought, agentId);
            if (memResultThought.status !== 'FAILED' && (0, exports.isL)(memResultThought.content)) {
                const results = memResultThought.content.elements;
                contextText = results.map(term => {
                    // Extract content from memory_result(content, id, type)
                    if ((0, exports.isS)(term) && term.name === 'memory_result' && term.args.length > 0 && (0, exports.isA)(term.args[0])) {
                        if (term.args.length > 1 && (0, exports.isA)(term.args[1]))
                            contextIds.push(term.args[1].name); // Store ID
                        return `- ${term.args[0].name}`; // Extract content atom
                    }
                    return '';
                }).filter(Boolean).join('\n');
                if (!contextText)
                    contextText = "No relevant context found in memory.";
            }
            else if (memResultThought.status === 'FAILED') {
                console.warn("GoalProposalTool: Memory search failed during context gathering:", memResultThought.metadata[exports.Keys.ERROR]);
            }
        }
        catch (memError) {
            console.error("GoalProposalTool: Error retrieving context from memory:", memError.message);
        }
        // 2. Prompt LLM for suggestion
        const prompt = `Based on the recent activity and memory context below, suggest a single, relevant, actionable next goal or task for agent ${agentId.substring(0, 8)}.\nContext:\n${contextText}\n\nDesired Format: {"name": "suggested_goal_or_task_name", "args": ["arg1", "arg2"]}\n---\nSuggest Goal/Task:`;
        const llmParams = { action: 'generate', input: prompt, type: 'GOAL' }; // Suggest as a GOAL
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
            [exports.Keys.UI_CONTEXT]: `Suggested: ${termToString(suggestedGoalTerm)}`,
            [exports.Keys.PROVENANCE]: [
                ...(Array.isArray(parentThought.metadata[exports.Keys.PROVENANCE]) ? parentThought.metadata[exports.Keys.PROVENANCE] : []),
                llmResultThought.id,
                ...contextIds // Include IDs of memory entries used as context
            ],
            [exports.Keys.RELATED_IDS]: [llmResultThought.id, ...contextIds], // Also add to related
            [exports.Keys.PRIORITY]: 0.8 // Give suggestions a decent priority
        }, parentThought // Link to the thought that triggered the proposal
        );
    }
}
// Define ToolDefinition for GoalProposalTool
class GoalProposalToolDefinition {
    name = 'goal_proposal';
    description = 'Suggests a relevant next goal or task based on recent activity and memory context.';
    parameters = {
    // No parameters needed to trigger the proposal generation itself.
    // Optional context could be passed, but it gathers its own for now.
    };
    tool;
    constructor(memoryStore, toolManager) {
        // Pass dependencies needed by the GoalProposalTool instance
        this.tool = new GoalProposalTool(memoryStore, toolManager);
    }
}
// --- User Interaction Tool ---
// This tool is stateful internally to manage pending prompts.
// Needs to be a singleton instance provided to the AgentLoop.
class UserInteractionTool {
    // Map: interactionRequestThoughtId -> PendingPrompt details
    pendingPrompts = new Map();
    // Note: Constructor is empty, state is internal. Instance is created in AgentLoop.
    async execute(params, parentThought, agentId) {
        // Assumes params are validated by ToolManager
        const promptText = params['prompt'];
        const options = params['options'];
        // Create a thought representing the *request* for interaction.
        // This thought goes into WAITING state immediately.
        const interactionRequestThought = createThought(agentId, 'STRATEGY', // Requesting input is often part of a strategy
        (0, exports.S)("request_user_input", [(0, exports.A)(promptText)]), 'WAITING', // Set to WAITING until the user responds
        {
            [exports.Keys.UI_CONTEXT]: `Prompt: ${promptText}`,
            // Store necessary info directly in metadata for UI rendering & potential reload
            'interaction_details': { promptText, options }
        }, parentThought // Link to the parent that needs the input
        );
        // Store the resolution logic associated with this request thought's ID
        // This promise isn't typically awaited externally, but handles internal state.
        const interactionPromise = new Promise((resolve, reject) => {
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
        this.pendingPrompts.get(interactionRequestThought.id).promise = interactionPromise;
        // IMPORTANT: Return the WAITING request thought immediately.
        // The AgentLoop/Worker should NOT process WAITING thoughts further.
        // The actual user input will arrive asynchronously via handleResponse.
        return interactionRequestThought;
    }
    /** Called by the UI/external system when a user responds to a prompt. */
    handleResponse(interactionRequestId, userResponse, thoughtStore) {
        const pending = this.pendingPrompts.get(interactionRequestId);
        if (!pending) {
            console.warn(`UserInteractionTool: Received response for unknown or completed prompt ID: ${interactionRequestId}`);
            return;
        }
        const { agentId, resolve, reject, parentThought } = pending;
        this.pendingPrompts.delete(interactionRequestId); // Remove from pending list
        // --- Create the new INPUT thought containing the user's actual response ---
        const responseThought = createThought(agentId, 'INPUT', // User response is INPUT
        (0, exports.A)(userResponse), 'PENDING', // Ready for processing
        {
            [exports.Keys.UI_CONTEXT]: `User Response: ${userResponse.substring(0, 50)}${userResponse.length > 50 ? '...' : ''}`,
            'answered_prompt_id': interactionRequestId, // Link back to the prompt
            [exports.Keys.RELATED_IDS]: [interactionRequestId], // Link response to request
            [exports.Keys.PRIORITY]: 1.0 // Give user responses decent priority
        }, parentThought // Link this INPUT back to the original requester
        );
        // Add the new response thought to the store for processing by workers
        thoughtStore.add(responseThought);
        // --- Mark the original request thought as DONE ---
        const requestThought = thoughtStore.get(interactionRequestId);
        if (requestThought && requestThought.status === 'WAITING') {
            // Use updateThought for atomic update
            updateThought(thoughtStore, requestThought, 'DONE', {
                [exports.Keys.UI_CONTEXT]: `Responded: ${userResponse.substring(0, 50)}...`,
                'response_thought_id': responseThought.id, // Link request to the response thought
                [exports.Keys.RELATED_IDS]: [responseThought.id] // Also link request -> response
            });
        }
        else {
            console.warn(`UserInteractionTool: Could not find or update original WAITING request thought ${interactionRequestId} to DONE.`);
        }
        console.log(`UserInteractionTool: Processed response for ${interactionRequestId}, created thought ${responseThought.id}`);
        // resolve(responseThought); // Resolve the promise if needed
    }
    /** Retrieves details for prompts currently waiting for user input from ThoughtStore. */
    getPendingPromptsForUI(thoughtStore) {
        const prompts = [];
        // Iterate over known pending prompts AND check the thought store for WAITING interaction requests
        const waitingThoughts = thoughtStore.getAllThoughts().filter(t => t.status === 'WAITING' &&
            (0, exports.isS)(t.content) && t.content.name === 'request_user_input');
        waitingThoughts.forEach(thought => {
            // Add to internal map if missing (e.g., after loading state)
            if (!this.pendingPrompts.has(thought.id)) {
                console.warn(`UI Tool: Found WAITING prompt ${thought.id} not in internal map. Reconstructing.`);
                // Need parent thought to reconstruct fully - this is tricky.
                // For UI, just extract details from metadata.
                const parentId = thought.metadata[exports.Keys.PARENT_ID];
                // We can't easily get the reject/resolve functions here. Cancellation might not work for loaded prompts.
                // this.pendingPrompts.set(thought.id, { /* partial reconstruction */ });
            }
            const details = thought.metadata['interaction_details'];
            if (details) {
                prompts.push({ id: thought.id, prompt: details.promptText, options: details.options });
            }
            else if ((0, exports.isS)(thought.content) && thought.content.args.length > 0 && (0, exports.isA)(thought.content.args[0])) {
                // Fallback: try to get prompt text from content if metadata missing
                prompts.push({ id: thought.id, prompt: thought.content.args[0].name, options: undefined });
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
    cancelPrompt(interactionRequestId, reason, thoughtStore) {
        const pending = this.pendingPrompts.get(interactionRequestId);
        this.pendingPrompts.delete(interactionRequestId); // Remove from internal map regardless
        // Mark the original request thought as FAILED in the store
        const requestThought = thoughtStore.get(interactionRequestId);
        if (requestThought && requestThought.status === 'WAITING') {
            const updated = updateThought(thoughtStore, requestThought, 'FAILED', {
                [exports.Keys.ERROR]: `User interaction cancelled: ${reason}`,
                [exports.Keys.UI_CONTEXT]: `Interaction Cancelled: ${reason}`,
                'cancelled_prompt_id': interactionRequestId,
            });
            if (updated && pending?.reject) {
                // pending.reject(new Error(`Interaction cancelled: ${reason}`)); // Reject promise
            }
            else if (!updated) {
                console.warn(`UserInteractionTool: Failed to mark request thought ${interactionRequestId} FAILED on cancellation (concurrent modification?).`);
            }
        }
        else {
            console.warn(`UserInteractionTool: Could not find WAITING request thought ${interactionRequestId} to cancel.`);
        }
    }
}
exports.UserInteractionTool = UserInteractionTool;
// Define ToolDefinition for UserInteractionTool
class UserInteractionToolDefinition {
    name = 'user_interaction';
    description = 'Prompts the user for input via the UI and waits for a response.';
    parameters = {
        prompt: { type: 'string', required: true, description: 'The text to display to the user.' },
        options: {
            type: 'array',
            itemType: 'string',
            required: false,
            description: 'Optional list of predefined choices for the user.'
        }
    };
    tool; // Must be a single shared instance
    // Constructor accepts the singleton instance created in AgentLoop
    constructor(instance) {
        if (!(instance instanceof UserInteractionTool)) {
            throw new Error("UserInteractionToolDefinition requires an instance of UserInteractionTool");
        }
        this.tool = instance;
    }
}
exports.UserInteractionToolDefinition = UserInteractionToolDefinition;
// --- Web Search Tool --- (Example Dynamic/Optional Tool)
class WebSearchTool {
    httpClient;
    endpoint;
    constructor(endpoint) {
        if (!endpoint)
            throw new Error("WebSearchTool requires a valid endpoint URL.");
        this.endpoint = endpoint;
        this.httpClient = new global.HttpClient(); // Use global mock or real client
    }
    async execute(params, parentThought, agentId) {
        // Assumes validation happened in ToolManager
        const query = params['query'];
        const url = new URL(this.endpoint);
        url.searchParams.set('q', query); // Example: using query parameter 'q'
        console.log(`WebSearchTool: Querying ${url.toString()}`);
        // Use try-catch within the tool for specific network/API errors
        try {
            const responseText = await this.httpClient.get(url.toString());
            const resultText = responseText ?? 'No content received from web search';
            console.log(`WebSearchTool: Received response (length: ${resultText.length})`);
            // Result is INPUT, ready for analysis by other rules/LLM
            return createThought(agentId, 'INPUT', (0, exports.A)(resultText), 'PENDING', {
                [exports.Keys.UI_CONTEXT]: `Web Search Result for: "${query}"`,
                'search_query': query // Add query to metadata
            }, parentThought);
        }
        catch (error) {
            console.error(`WebSearchTool: Failed to execute search for query "${query}":`, error);
            // Throw error to be caught by ToolManager, which creates the FAILED thought
            throw new Error(`Web search failed: ${error.message}`);
        }
    }
}
// ToolDefinition for WebSearchTool (used if registered dynamically or statically)
class WebSearchToolDefinition {
    name = 'web_search';
    description = 'Performs a web search using a configured external API endpoint.';
    parameters = {
        query: { type: 'string', required: true, description: 'The search query text.' }
    };
    tool;
    constructor(endpoint) {
        this.tool = new WebSearchTool(endpoint);
    }
}
/** Action Execution (Handles Rules & Workflows) */
class ActionExecutor {
    thoughtStore;
    ruleStore;
    toolManager;
    memoryStore;
    agentId;
    config;
    llmTool;
    constructor(thoughtStore, ruleStore, toolManager, memoryStore, // For memorizing execution traces
    agentId, config, llmTool // Needed for potential dynamic embedding/entity extraction
    ) {
        this.thoughtStore = thoughtStore;
        this.ruleStore = ruleStore;
        this.toolManager = toolManager;
        this.memoryStore = memoryStore;
        this.agentId = agentId;
        this.config = config;
        this.llmTool = llmTool;
    }
    /**
     * Executes the action defined by a matched rule OR handles a workflow step.
     * `matchedThought` is the thought that triggered this execution (must be the version from the store).
     * `ruleOrWorkflow` is the Rule or the WORKFLOW_STEP Thought containing the workflow definition.
     * `bindings` are from the initial unification (if triggered by a rule).
     */
    async executeAction(matchedThought, // The ACTIVE thought being processed
    ruleOrWorkflow, bindings // Only relevant if ruleOrWorkflow is a Rule
    ) {
        let actionTerm;
        let executingRule = null;
        let workflowContext = {}; // Holds ID and step for workflows
        // Determine if we're executing a rule action or a workflow step
        if ('pattern' in ruleOrWorkflow && 'action' in ruleOrWorkflow) { // It's a Rule
            executingRule = ruleOrWorkflow;
            actionTerm = apply(executingRule.action, bindings);
            // Add rule ID to provenance
            workflowContext[exports.Keys.PROVENANCE] = [
                ...(matchedThought.metadata[exports.Keys.PROVENANCE] ?? []),
                executingRule.id
            ];
        }
        else if (ruleOrWorkflow.type === 'WORKFLOW_STEP') { // It's a Thought representing a workflow step
            actionTerm = ruleOrWorkflow.content; // The workflow definition is the content
            workflowContext = {
                [exports.Keys.WORKFLOW_ID]: ruleOrWorkflow.metadata[exports.Keys.WORKFLOW_ID],
                [exports.Keys.WORKFLOW_STEP]: ruleOrWorkflow.metadata[exports.Keys.WORKFLOW_STEP],
                [exports.Keys.WORKFLOW_RESULTS]: ruleOrWorkflow.metadata[exports.Keys.WORKFLOW_RESULTS], // Carry forward results
                [exports.Keys.PROVENANCE]: ruleOrWorkflow.metadata[exports.Keys.PROVENANCE] // Inherit provenance
            };
            // Note: The 'matchedThought' here is the WORKFLOW_STEP thought itself.
            // The original trigger is its parent.
        }
        else {
            console.error(`ActionExecutor: Invalid object passed as ruleOrWorkflow. ID: ${ruleOrWorkflow.id}, Type: ${ruleOrWorkflow.type}`);
            updateThought(this.thoughtStore, matchedThought, 'FAILED', { [exports.Keys.ERROR]: 'Invalid trigger for action execution' }, this.config);
            return;
        }
        // --- Handle different action term types ---
        if ((0, exports.isS)(actionTerm)) {
            const actionName = actionTerm.name;
            const args = actionTerm.args;
            try {
                // Check for Workflow Control Structures
                if (actionName === 'sequence' || actionName === 'chain') {
                    await this.executeSequenceWorkflow(matchedThought, args, workflowContext, executingRule);
                }
                else if (actionName === 'parallel') {
                    await this.executeParallelWorkflow(matchedThought, args, workflowContext, executingRule);
                }
                // Handle standard Tool Call
                else {
                    await this.executeToolAction(matchedThought, actionTerm, workflowContext, executingRule);
                }
            }
            catch (executionError) {
                console.error(`ActionExecutor: Error during execution of action "${actionName}" for thought ${matchedThought.id}:`, executionError);
                // Fail the thought that triggered the action
                updateThought(this.thoughtStore, matchedThought, 'FAILED', {
                    [exports.Keys.ERROR]: `Action execution failed: ${executionError.message}`,
                    [exports.Keys.UI_CONTEXT]: `Action failed: ${actionName}`
                }, this.config);
                // Optionally memorize the failure trace
                await this.memorizeExecution(matchedThought, executingRule, null, false, executionError.message);
            }
        }
        else {
            // Action term is not a structure (e.g., just an Atom or ListTerm) - invalid action format
            const errorMsg = `Invalid action term type. Expected Structure, got ${actionTerm.kind}. Term: ${termToString(actionTerm)}`;
            console.error(`ActionExecutor [Thought: ${matchedThought.id}]: ${errorMsg}`);
            updateThought(this.thoughtStore, matchedThought, 'FAILED', { [exports.Keys.ERROR]: errorMsg }, this.config);
        }
    }
    /** Executes a single tool call defined by an action term. */
    async executeToolAction(triggeringThought, // The thought being processed (status: ACTIVE)
    actionTerm, // e.g., toolName(params(...))
    execContext, // Includes provenance, workflow context
    triggeringRule // The rule that led to this action, if any
    ) {
        const toolName = actionTerm.name;
        const params = this.extractParamsFromTerm(actionTerm.args); // Extract JS params
        // Execute the tool via ToolManager
        const resultThought = await this.toolManager.execute(toolName, params, triggeringThought, this.agentId);
        // Add the result thought to the store
        // ToolManager adds base metadata, createThought freezes.
        this.thoughtStore.add(resultThought);
        // --- Post-Tool-Execution Handling ---
        let finalStatusForTriggeringThought = 'WAITING'; // Default: wait for result processing
        let finalMetadataUpdate = {
            // Link the triggering thought to the result
            [exports.Keys.RELATED_IDS]: [resultThought.id], // Merge handled by updateThought
            // Update UI context based on action outcome
            [exports.Keys.UI_CONTEXT]: `${toolName} -> ${resultThought.status} (${resultThought.id.substring(0, 6)})`,
            // Carry over provenance from context
            [exports.Keys.PROVENANCE]: execContext[exports.Keys.PROVENANCE] ?? undefined,
        };
        // 1. Handle Rule Synthesis (LLM generated a rule)
        if (resultThought.type === 'RULE' && (0, exports.isS)(resultThought.content) && resultThought.content.name === 'rule_definition') {
            await this.handleRuleSynthesis(resultThought, triggeringThought);
            finalStatusForTriggeringThought = 'DONE'; // Rule synthesis was the goal
            finalMetadataUpdate[exports.Keys.UI_CONTEXT] = `Synthesized rule from ${resultThought.id}`;
        }
        // 2. Handle Tool Registration (LLM generated tool specs)
        else if (resultThought.type === 'TOOLS' && (0, exports.isL)(resultThought.content)) {
            const registeredCount = await this.handleToolRegistration(resultThought);
            finalStatusForTriggeringThought = 'DONE'; // Tool registration was the goal
            finalMetadataUpdate[exports.Keys.UI_CONTEXT] = `Registered ${registeredCount} tools from ${resultThought.id}`;
        }
        // 3. Handle Tool Failure
        else if (resultThought.status === 'FAILED') {
            finalStatusForTriggeringThought = 'FAILED'; // Action failed
            finalMetadataUpdate[exports.Keys.ERROR] = resultThought.metadata[exports.Keys.ERROR] ?? `Tool '${toolName}' execution failed`;
        }
        // 4. Handle User Interaction Request (special case: original thought waits)
        else if (resultThought.status === 'WAITING' && toolName === 'user_interaction') {
            finalStatusForTriggeringThought = 'WAITING'; // Explicitly wait for user response
            finalMetadataUpdate[exports.Keys.UI_CONTEXT] = `Waiting for user input (Prompt: ${resultThought.id.substring(0, 6)})`;
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
        }
        else {
            // Optionally memorize failure trace here if not handled by overall execution error
            // await this.memorizeExecution(triggeringThought, triggeringRule, resultThought, false, finalMetadataUpdate[MetadataKeys.ERROR] as string);
        }
    }
    /** Executes steps in a sequence workflow defined by `steps`. */
    async executeSequenceWorkflow(triggeringThought, // The thought that matched the rule defining the sequence, or the previous WORKFLOW_STEP thought
    steps, // The list of action terms in the sequence
    execContext, // Contains Workflow ID, Step#, Provenance
    triggeringRule // Rule that initiated the workflow (if any)
    ) {
        if (steps.length === 0) {
            // Sequence finished successfully
            const workflowId = execContext[exports.Keys.WORKFLOW_ID] ?? triggeringThought.id;
            console.log(`Workflow ${workflowId} sequence completed.`);
            updateThought(this.thoughtStore, triggeringThought, 'DONE', {
                [exports.Keys.UI_CONTEXT]: 'Workflow sequence completed',
                [exports.Keys.PROVENANCE]: execContext[exports.Keys.PROVENANCE] ?? undefined, // Persist final provenance
            }, this.config);
            // TODO: Memorize workflow completion?
            return;
        }
        // --- Prepare for the next step ---
        const currentStepActionTerm = steps[0]; // The action for the current step
        const remainingSteps = steps.slice(1); // Actions for subsequent steps
        const workflowId = (execContext[exports.Keys.WORKFLOW_ID] ?? generateUUID()); // Generate ID if first step
        const currentStepIndex = Number(execContext[exports.Keys.WORKFLOW_STEP] ?? 0);
        if (!(0, exports.isS)(currentStepActionTerm)) {
            const errorMsg = `Invalid step type in sequence workflow ${workflowId}. Expected Structure, got ${currentStepActionTerm.kind}.`;
            console.error(`ActionExecutor: ${errorMsg}`);
            updateThought(this.thoughtStore, triggeringThought, 'FAILED', { [exports.Keys.ERROR]: errorMsg }, this.config);
            return;
        }
        // --- Create the WORKFLOW_STEP thought for the *next* step ---
        // This thought holds the definition of the *rest* of the sequence.
        let nextStepThought = null;
        if (remainingSteps.length > 0) {
            const nextStepContent = (0, exports.S)('sequence', remainingSteps); // Content defines the rest of the workflow
            const nextStepMetadata = {
                [exports.Keys.WORKFLOW_ID]: workflowId,
                [exports.Keys.WORKFLOW_STEP]: currentStepIndex + 1,
                [exports.Keys.UI_CONTEXT]: `Workflow ${workflowId.substring(0, 6)} step ${currentStepIndex + 1}/${currentStepIndex + remainingSteps.length}`,
                [exports.Keys.PROVENANCE]: execContext[exports.Keys.PROVENANCE] ?? undefined, // Carry provenance
                // Optional: Pass result of current step to next step via metadata? Needs careful design.
                // [MetadataKeys.WORKFLOW_RESULTS]: {...execContext[MetadataKeys.WORKFLOW_RESULTS], [`step_${currentStepIndex}_result`]: resultData},
                [exports.Keys.PRIORITY]: triggeringThought.metadata[exports.Keys.PRIORITY] // Inherit priority
            };
            nextStepThought = createThought(this.agentId, 'WORKFLOW_STEP', nextStepContent, 'PENDING', // Next step is PENDING
            nextStepMetadata, triggeringThought // Parent is the current step's thought (or initial trigger)
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
                    [exports.Keys.UI_CONTEXT]: `Workflow step ${currentStepIndex} done, waiting for next (${nextStepThought.id.substring(0, 6)})`,
                    [exports.Keys.RELATED_IDS]: [nextStepThought.id] // Link to the next step
                }, this.config);
            }
            else {
                // This was the last step, and executeToolAction didn't fail it, so mark DONE.
                updateThought(this.thoughtStore, currentTriggerThoughtState, 'DONE', {
                    [exports.Keys.UI_CONTEXT]: `Workflow final step ${currentStepIndex} completed.`
                }, this.config);
                // Memorize completion here? Or handled by checkAndCompleteParent?
                // Let checkAndCompleteParent handle hierarchy completion.
                await this.checkAndCompleteParent(currentTriggerThoughtState); // See if this completes the parent workflow trigger
            }
        }
        // If executeToolAction failed the thought, its status is already FAILED.
    }
    /** Executes workflow steps in parallel. */
    async executeParallelWorkflow(triggeringThought, // The thought defining the parallel block
    steps, // The list of action terms to run in parallel
    execContext, // Contains Workflow ID, Step#, Provenance
    triggeringRule) {
        if (steps.length === 0) {
            console.log(`Parallel workflow for ${triggeringThought.id} has no steps. Marking DONE.`);
            updateThought(this.thoughtStore, triggeringThought, 'DONE', { [exports.Keys.UI_CONTEXT]: 'Empty parallel workflow completed' }, this.config);
            return;
        }
        const workflowId = (execContext[exports.Keys.WORKFLOW_ID] ?? generateUUID());
        const parentStepIndex = Number(execContext[exports.Keys.WORKFLOW_STEP] ?? -1); // Step index of the parallel block itself
        console.log(`Starting parallel workflow block ${workflowId} (parent step ${parentStepIndex}) with ${steps.length} tasks.`);
        const childThoughtIds = [];
        // Launch all steps concurrently by creating PENDING thoughts for each
        for (let i = 0; i < steps.length; i++) {
            const stepTerm = steps[i];
            if (!(0, exports.isS)(stepTerm)) {
                console.error(`ActionExecutor: Invalid step type in parallel workflow ${workflowId}. Expected Structure, got ${stepTerm.kind}. Skipping.`);
                continue;
            }
            const stepMetadata = {
                [exports.Keys.WORKFLOW_ID]: workflowId,
                // Identify step within the parallel block (e.g., parentStepIndex.subStepIndex)
                [exports.Keys.WORKFLOW_STEP]: `${parentStepIndex >= 0 ? parentStepIndex : 'p'}.${i}`,
                [exports.Keys.UI_CONTEXT]: `Parallel task [${i}]: ${stepTerm.name}`,
                [exports.Keys.PROVENANCE]: execContext[exports.Keys.PROVENANCE] ?? undefined, // Inherit provenance
                [exports.Keys.PRIORITY]: triggeringThought.metadata[exports.Keys.PRIORITY] // Inherit priority
            };
            // Create thought representing the *action* to be taken for this parallel step
            // This thought will be picked up by a worker and executed via executeToolAction
            const parallelStepThought = createThought(this.agentId, 'STRATEGY', // Parallel steps are like strategies launched by the workflow
            stepTerm, // Content is the tool call structure
            'PENDING', stepMetadata, triggeringThought // Parent is the thought that defined the parallel block
            );
            this.thoughtStore.add(parallelStepThought);
            childThoughtIds.push(parallelStepThought.id);
        }
        // Mark the triggering thought (which defines the parallel block) as WAITING
        // It will be marked DONE only when checkAndCompleteParent determines all its children (the parallelStepThoughts) are DONE.
        updateThought(this.thoughtStore, triggeringThought, 'WAITING', {
            [exports.Keys.UI_CONTEXT]: `Waiting for ${steps.length} parallel tasks (Workflow ${workflowId.substring(0, 6)}, Step ${parentStepIndex})`,
            [exports.Keys.RELATED_IDS]: childThoughtIds // Link parent to all child tasks
        }, this.config);
        // Memorization happens individually as each parallel step completes via executeToolAction.
    }
    // --- Helper Methods ---
    /** Handles synthesized rule from LLM result */
    async handleRuleSynthesis(resultThought, triggeringThought) {
        // Ensure result thought is the current version
        const currentResult = this.thoughtStore.get(resultThought.id) ?? resultThought;
        if (currentResult.status !== 'PENDING' && currentResult.status !== 'ACTIVE') {
            console.warn(`Rule synthesis skipped for ${resultThought.id}, status is ${currentResult.status}`);
            return; // Already processed or failed
        }
        if (!(0, exports.isS)(currentResult.content) || currentResult.content.name !== 'rule_definition' || currentResult.content.args.length !== 2) {
            console.error("ActionExecutor: Malformed RULE thought content:", currentResult.content);
            updateThought(this.thoughtStore, currentResult, 'FAILED', { [exports.Keys.ERROR]: 'Malformed rule definition term' }, this.config);
            return;
        }
        const [patternTerm, actionTermForRule] = currentResult.content.args;
        try {
            const newRule = Object.freeze({
                id: generateUUID(),
                pattern: patternTerm,
                action: actionTermForRule,
                belief: { ...exports.DEFAULT_BELIEF, lastUpdated: Date.now() }, // Start with default belief
                metadata: mergeMetadata(triggeringThought.metadata, // Inherit metadata like root_id
                {
                    [exports.Keys.PROVENANCE]: [currentResult.id, triggeringThought.id], // Link to thoughts
                    [exports.Keys.TIMESTAMP]: Date.now(),
                    [exports.Keys.AGENT_ID]: this.agentId,
                    [exports.Keys.UI_CONTEXT]: `Synthesized Rule triggered by ${triggeringThought.id.substring(0, 6)}`,
                    // Remove fields not applicable to rules
                    [exports.Keys.PARENT_ID]: undefined,
                    [exports.Keys.WORKFLOW_ID]: undefined,
                    [exports.Keys.WORKFLOW_STEP]: undefined,
                    [exports.Keys.ERROR]: undefined,
                    [exports.Keys.RETRY_COUNT]: undefined,
                })
            });
            await this.ruleStore.add(newRule); // Add rule (also generates embedding)
            console.log(`ActionExecutor: Synthesized and added new rule ${newRule.id}`);
            // Mark the result thought as DONE
            updateThought(this.thoughtStore, currentResult, 'DONE', { [exports.Keys.UI_CONTEXT]: `Rule ${newRule.id} synthesized` }, this.config);
        }
        catch (error) {
            console.error("ActionExecutor: Failed to create or add synthesized rule:", error);
            updateThought(this.thoughtStore, currentResult, 'FAILED', { [exports.Keys.ERROR]: `Failed to add synthesized rule: ${error.message}` }, this.config);
        }
    }
    /** Handles dynamic tool registration from LLM result */
    async handleToolRegistration(resultThought) {
        // Ensure result thought is the current version
        const currentResult = this.thoughtStore.get(resultThought.id) ?? resultThought;
        if (currentResult.status !== 'PENDING' && currentResult.status !== 'ACTIVE') {
            if (!(0, exports.isL)(currentResult.content)) { /* ... error handling ... */
                return 0;
            }
            let toolsRegistered = 0;
            for (const toolSpecTerm of currentResult.content.elements) {
                // Expecting: tool_spec(NameAtom, TypeAtom, EndpointAtom?, ...)
                if ((0, exports.isS)(toolSpecTerm) && toolSpecTerm.name === 'tool_spec' && toolSpecTerm.args.length >= 2 &&
                    (0, exports.isA)(toolSpecTerm.args[0]) && (0, exports.isA)(toolSpecTerm.args[1])) {
                    const name = toolSpecTerm.args[0].name;
                    const type = toolSpecTerm.args[1].name; // Type suggested by LLM
                    const endpoint = (toolSpecTerm.args.length > 2 && (0, exports.isA)(toolSpecTerm.args[2])) ? toolSpecTerm.args[2].name : '';
                    const description = (toolSpecTerm.args.length > 3 && (0, exports.isA)(toolSpecTerm.args[3])) ? toolSpecTerm.args[3].name : `Dynamically registered: ${name}`;
                    const parameters = {}; // TODO: Parse schema if provided
                    try {
                        let toolInstance = null;
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
                        }
                        else if (lowerCaseType.includes('dictionary') || lowerCaseType.includes('thesaurus') || lowerCaseType.includes('word') && endpoint) {
                            console.warn(`ActionExecutor: Dictionary/Word tool type "${type}" for "${name}" not yet implemented. Endpoint: ${endpoint}`);
                            // toolInstance = new DictionaryTool(endpoint); // If such a tool existed
                        }
                        // Add more known types or a generic API tool handler here if desired
                        else {
                            console.warn(`ActionExecutor: Unknown or unsupported dynamic tool type "${type}" for tool "${name}". Endpoint: ${endpoint || 'N/A'}`);
                        }
                        // --------------------------
                        if (toolInstance) {
                            const definition = { name, description, parameters, tool: toolInstance };
                            this.toolManager.registerToolDefinition(definition);
                            // console.log(`ActionExecutor: Dynamically registered tool "${name}" of type "${type}"`); // Already logged above
                            toolsRegistered++;
                        }
                    }
                    catch (err) {
                        console.error(`ActionExecutor: Failed to instantiate or register dynamic tool "${name}":`, err.message);
                    }
                }
                else {
                    console.warn("ActionExecutor: Malformed tool specification term in TOOLS list:", termToString(toolSpecTerm));
                }
            }
            updateThought(this.thoughtStore, currentResult, 'DONE', { [exports.Keys.UI_CONTEXT]: `Processed ${toolsRegistered} tool specs` }, this.config);
            return toolsRegistered;
        }
        if (!(0, exports.isL)(currentResult.content)) {
            console.error("ActionExecutor: Malformed TOOLS thought content (expected ListTerm):", currentResult.content);
            updateThought(this.thoughtStore, currentResult, 'FAILED', { [exports.Keys.ERROR]: 'Malformed tools definition list' }, this.config);
            return 0;
        }
        let toolsRegistered = 0;
        for (const toolSpecTerm of currentResult.content.elements) {
            // Expecting: tool_spec(NameAtom, TypeAtom, EndpointAtom?, DescriptionAtom?, ParamsSchemaTerm?)
            if ((0, exports.isS)(toolSpecTerm) && toolSpecTerm.name === 'tool_spec' && toolSpecTerm.args.length >= 2 &&
                (0, exports.isA)(toolSpecTerm.args[0]) && (0, exports.isA)(toolSpecTerm.args[1])) {
                const name = toolSpecTerm.args[0].name;
                const type = toolSpecTerm.args[1].name;
                const endpoint = (toolSpecTerm.args.length > 2 && (0, exports.isA)(toolSpecTerm.args[2])) ? toolSpecTerm.args[2].name : '';
                const description = (toolSpecTerm.args.length > 3 && (0, exports.isA)(toolSpecTerm.args[3])) ? toolSpecTerm.args[3].name : `Dynamically registered tool: ${name}`;
                // TODO: Parse parameter schema from term (e.g., arg[4] could be a ListTerm of parameter definitions)
                const parameters = {}; // Placeholder - requires schema definition format
                try {
                    let toolInstance = null;
                    // Instantiate known dynamic tool types
                    if (type === 'web_search' && endpoint) {
                        toolInstance = new WebSearchTool(endpoint);
                    }
                    else {
                        console.warn(`ActionExecutor: Unknown or unsupported dynamic tool type "${type}" for tool "${name}"`);
                    }
                    // Register if instance created
                    if (toolInstance) {
                        const definition = { name, description, parameters, tool: toolInstance };
                        this.toolManager.registerToolDefinition(definition);
                        console.log(`ActionExecutor: Dynamically registered tool "${name}" of type "${type}"`);
                        toolsRegistered++;
                    }
                }
                catch (err) {
                    console.error(`ActionExecutor: Failed to instantiate or register dynamic tool "${name}":`, err.message);
                }
            }
            else {
                console.warn("ActionExecutor: Malformed tool specification term in TOOLS list:", termToString(toolSpecTerm));
            }
        }
        // Mark the result thought as DONE
        updateThought(this.thoughtStore, currentResult, 'DONE', { [exports.Keys.UI_CONTEXT]: `Registered ${toolsRegistered} tools` }, this.config);
        return toolsRegistered;
    }
    /** Stores a record of the execution trace in the memory store. */
    async memorizeExecution(triggeringThought, rule, // Can be null for default actions or workflow steps
    resultThought, // Can be null if execution failed before result creation
    success, errorMessage // Include error message on failure
    ) {
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
            const metadata = {
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
                root_id: triggeringThought.metadata[exports.Keys.ROOT_ID],
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
            await this.memoryStore.add({ id: memoryId, content: trace, embedding, metadata });
        }
        catch (memErr) {
            console.warn(`ActionExecutor: Failed to store execution trace in memory (Trigger: ${triggeringThought.id}):`, memErr.message);
        }
    }
    /** Extracts parameters from action term arguments, converting Terms to JS values. */
    extractParamsFromTerm(args) {
        const params = {};
        // Handle standard structure: actionName( params( key1(val1), key2(val2), ... ) )
        if (args.length === 1 && (0, exports.isS)(args[0]) && args[0].name === 'params') {
            const paramsList = args[0].args;
            for (const paramStruct of paramsList) {
                if ((0, exports.isS)(paramStruct) && paramStruct.args.length > 0) {
                    // key(value_term, optional_other_args...)
                    const key = paramStruct.name;
                    params[key] = this.termToJsValue(paramStruct.args[0]); // Convert first arg to JS value
                }
                else if ((0, exports.isA)(paramStruct)) {
                    // Allow boolean flags like: params(verbose) -> { verbose: true }
                    params[paramStruct.name] = true;
                }
            }
        }
        // Handle alternative: actionName( key1(val1), key2(val2), ... )
        else if (args.every(arg => (0, exports.isS)(arg) && arg.args.length > 0)) {
            args.forEach(arg => {
                if ((0, exports.isS)(arg)) { // Type guard for compiler
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
    termToJsValue(term) {
        switch (term.kind) {
            case 'atom':
                // Try to parse common primitives
                const name = term.name.trim();
                if (name === 'true')
                    return true;
                if (name === 'false')
                    return false;
                if (name === 'null')
                    return null;
                if (name === 'undefined')
                    return undefined;
                // Check for number, ensure it's not empty string misinterpreted as 0
                if (name !== '' && !isNaN(Number(name)))
                    return Number(name);
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
    async checkAndCompleteParent(completedThought) {
        // Ensure the completed thought itself is marked DONE (or already is).
        const currentVersion = this.thoughtStore.get(completedThought.id);
        if (!currentVersion)
            return; // Thought disappeared
        if (currentVersion.status !== 'DONE' && currentVersion.status !== 'FAILED') {
            // Should have been marked DONE by the caller or previous step, but try again if needed.
            const updated = updateThought(this.thoughtStore, currentVersion, 'DONE', { [exports.Keys.UI_CONTEXT]: `Task/Outcome complete` }, this.config);
            if (!updated) {
                console.warn(`[${this.agentId}] checkAndCompleteParent: Failed to mark child ${completedThought.id} as DONE.`);
                return; // Abort if update failed
            }
        }
        else if (currentVersion.status === 'FAILED') {
            // If the child itself failed, the parent likely shouldn't autocomplete.
            // Parent failure might be handled by failure propagation if implemented.
            return;
        }
        // --- Check Parent ---
        const parentId = completedThought.metadata[exports.Keys.PARENT_ID];
        if (!parentId)
            return; // No parent
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
                    [exports.Keys.UI_CONTEXT]: `Completed via children`,
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
                }
                else {
                    console.warn(`[${this.agentId}] Failed to mark parent ${parentId} as DONE (concurrent modification?).`);
                }
            }
        }
    }
    /** Memorizes the completion of a significant thought (e.g., Goal, Root Input). */
    async memorizeCompletion(thought) {
        if (thought.type !== 'GOAL' && thought.metadata[exports.Keys.ROOT_ID] !== thought.id) {
            return; // Only memorize significant completions for now
        }
        await this.memorizeExecution(thought, null, null, true); // Use memorizeExecution for consistency
    }
    /** Fire-and-forget sharing logic (placeholder). */
    triggerCollaborationShare(thought) {
        const shareTargets = thought.metadata[exports.Keys.SHARED_WITH];
        if (shareTargets && shareTargets.length > 0) {
            console.log(`[${this.agentId}] TODO: Trigger sharing of thought ${thought.id} to agents: ${shareTargets.join(', ')}`);
            // E.g., network call to thoughtExchange.share(thought);
        }
    }
}
exports.ActionExecutor = ActionExecutor;
// --- Worker Logic ---
class Worker {
    agentId;
    thoughtStore;
    ruleStore;
    unifier;
    actionExecutor;
    config;
    isRunning = false;
    abortController = new AbortController();
    lastProcessedThought = null; // For context sampling
    constructor(agentId, thoughtStore, ruleStore, unifier, actionExecutor, 
    // ToolManager/MemoryStore often not needed directly by worker, only by ActionExecutor
    // private readonly toolManager: ToolManager,
    // private readonly memoryStore: MemoryStore,
    config) {
        this.agentId = agentId;
        this.thoughtStore = thoughtStore;
        this.ruleStore = ruleStore;
        this.unifier = unifier;
        this.actionExecutor = actionExecutor;
        this.config = config;
    }
    start() {
        if (this.isRunning)
            return;
        this.isRunning = true;
        this.abortController = new AbortController(); // Create new controller for this run
        console.log(`Worker for agent ${this.agentId.substring(0, 8)} starting...`);
        this.runLoop().catch(err => {
            console.error(`[${this.agentId.substring(0, 8)}] Worker loop crashed unexpectedly:`, err);
            this.isRunning = false; // Ensure loop stops on crash
        });
    }
    async stop() {
        if (!this.isRunning)
            return;
        console.log(`Worker for agent ${this.agentId.substring(0, 8)} stopping...`);
        this.isRunning = false;
        this.abortController.abort(); // Signal any ongoing async operations
    }
    async runLoop() {
        while (this.isRunning) {
            let activeThought = null; // Track the thought claimed by this worker
            try {
                // --- Thought Sampling ---
                const pendingThought = this.thoughtStore.samplePending(this.lastProcessedThought ?? undefined, // Provide context if available
                this.config.contextSimilarityBoostFactor, this.config);
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
                const updated = updateThought(this.thoughtStore, thoughtToMarkActive, 'ACTIVE', { [exports.Keys.UI_CONTEXT]: 'Processing...' }, this.config);
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
                            if (activeThought.type === 'WORKFLOW_STEP') {
                                await this.actionExecutor.executeAction(activeThought, activeThought, new Map()); // Pass thought as workflow definition
                            }
                            // Other thought types match against rules
                            else {
                                const ruleEmbeddings = this.ruleStore.getEmbeddings();
                                const match = this.unifier.findAndSample(activeThought, this.ruleStore.getAll(), this.config.contextSimilarityBoostFactor, ruleEmbeddings);
                                if (match?.rule) {
                                    // console.log(`[${this.agentId.substring(0,8)}] Applying Rule ${match.rule.id.substring(0,6)} to Thought ${activeThought!.id.substring(0,6)}`);
                                    await this.actionExecutor.executeAction(activeThought, match.rule, match.bindings);
                                }
                                else {
                                    // console.log(`[${this.agentId.substring(0,8)}] No rule match for Thought ${activeThought!.id.substring(0,6)}, applying default action.`);
                                    // Pass the active thought reference to default handler
                                    await this.handleDefaultAction(activeThought);
                                }
                            }
                        })(),
                        // The timeout promise
                        sleep(this.config.thoughtProcessingTimeoutMillis).then(() => {
                            if (this.abortController.signal.aborted)
                                return; // Check if already stopped
                            throw new Error(`Processing timeout`); // Reject race promise
                        })
                    ]);
                    const duration = Date.now() - startTime;
                    // console.log(`[${this.agentId.substring(0,8)}] Finished processing Thought ${activeThought.id.substring(0,6)} in ${duration}ms`);
                    this.lastProcessedThought = activeThought; // Update context for next sample
                    activeThought = null; // Reset tracker for next loop iteration
                }
                catch (processingError) {
                    const errorMsg = processingError instanceof Error ? processingError.message : String(processingError);
                    console.error(`[${this.agentId.substring(0, 8)}] Error processing Thought ${activeThought?.id.substring(0, 6)}: ${errorMsg}`);
                    if (activeThought) { // Ensure we have the thought reference
                        await this.handleFailure(activeThought, `Processing error: ${errorMsg}`);
                    }
                    activeThought = null; // Handled failure, reset tracker
                }
            }
            catch (loopError) {
                const errorMsg = loopError instanceof Error ? loopError.message : String(loopError);
                console.error(`[${this.agentId.substring(0, 8)}] Worker error in main loop: ${errorMsg}`, loopError);
                activeThought = null; // Reset tracker
                await sleep(this.config.pollIntervalMillis * 5); // Longer pause
            }
        } // End while(this.isRunning)
        console.log(`Worker for agent ${this.agentId.substring(0, 8)} loop terminated.`);
    }
    /** Default actions when no specific rule matches a thought. Needs the ACTIVE thought ref. */
    async handleDefaultAction(activeThought) {
        if (activeThought.status !== 'ACTIVE') {
            console.warn(`[${this.agentId.substring(0, 8)}] handleDefaultAction called on non-ACTIVE thought ${activeThought.id.substring(0, 6)} (Status: ${activeThought.status}). Skipping.`);
            return;
        }
        console.log(`[${this.agentId.substring(0, 8)}] Default action for ${activeThought.type} thought ${activeThought.id.substring(0, 6)}`);
        const rootId = activeThought.metadata[exports.Keys.ROOT_ID] ?? activeThought.id;
        let defaultHandled = false;
        try {
            // Delegate most default actions to ActionExecutor by creating a temporary action term
            let defaultActionTerm = null;
            switch (activeThought.type) {
                case 'INPUT':
                    const goalPrompt = `Analyze this input and formulate a concise goal as a structure: {"name": "goal_name", "args": [...]}\nInput: "${termToString(activeThought.content)}"`;
                    defaultActionTerm = (0, exports.S)("llm", [(0, exports.S)("params", [
                            (0, exports.S)("action", [(0, exports.A)("generate")]),
                            (0, exports.S)("input", [(0, exports.A)(goalPrompt)]),
                            (0, exports.S)("type", [(0, exports.A)("GOAL")])
                        ])]);
                    break;
                case 'GOAL':
                    const strategyPrompt = `Break down this goal into an initial strategy or action as a structure: {"name": "strategy_name", "args": [...]}\nGoal: "${termToString(activeThought.content)}"`;
                    defaultActionTerm = (0, exports.S)("llm", [(0, exports.S)("params", [
                            (0, exports.S)("action", [(0, exports.A)("generate")]),
                            (0, exports.S)("input", [(0, exports.A)(strategyPrompt)]),
                            (0, exports.S)("type", [(0, exports.A)("STRATEGY")])
                        ])]);
                    // Fire-and-forget related actions are launched separately
                    this.triggerRelatedDefaultActions(activeThought, rootId);
                    break;
                case 'STRATEGY':
                    const outcomePrompt = `Based on this strategy, predict the likely outcome or generate the next concrete step as a structure: {"name": "outcome_or_next_step", "args": [...]}\nStrategy: "${termToString(activeThought.content)}"`;
                    defaultActionTerm = (0, exports.S)("llm", [(0, exports.S)("params", [
                            (0, exports.S)("action", [(0, exports.A)("generate")]),
                            (0, exports.S)("input", [(0, exports.A)(outcomePrompt)]),
                            (0, exports.S)("type", [(0, exports.A)("OUTCOME")])
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
                    defaultActionTerm = (0, exports.S)("llm", [(0, exports.S)("params", [
                            (0, exports.S)("action", [(0, exports.A)("generate")]),
                            (0, exports.S)("input", [(0, exports.A)(answerPrompt)]),
                            (0, exports.S)("type", [(0, exports.A)("OUTCOME")])
                        ])]);
                    break;
                default:
                    console.warn(`[${this.agentId.substring(0, 8)}] No default action defined for thought type: ${activeThought.type}`);
                    await this.handleFailure(activeThought, `No default action for type ${activeThought.type}`);
                    defaultHandled = true;
                    break;
            }
            // If a default action term was created, execute it via ActionExecutor
            if (defaultActionTerm && (0, exports.isS)(defaultActionTerm)) {
                await this.actionExecutor.executeToolAction(activeThought, defaultActionTerm, {}, null); // Pass null for rule
                defaultHandled = true;
            }
            else if (defaultActionTerm) {
                // This shouldn't happen based on the switch cases
                console.error(`[${this.agentId.substring(0, 8)}] Invalid default action term generated for ${activeThought.type}`);
                await this.handleFailure(activeThought, `Invalid default action term for type ${activeThought.type}`);
                defaultHandled = true;
            }
        }
        catch (error) {
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
    triggerRelatedDefaultActions(goalThought, rootId) {
        // // Option 1: Create new PENDING thoughts that trigger these tools via rules
        // const clarifyThought = createThought(this.agentId, 'STRATEGY',
        //     createStructure("needs_clarification", [goalThought.content]), // Content matches a potential rule
        //     'PENDING', { [MetadataKeys.UI_CONTEXT]: "Auto-trigger: Clarify goal?" }, goalThought);
        // this.thoughtStore.add(clarifyThought);
        const proposeThought = createThought(this.agentId, 'STRATEGY', (0, exports.S)("propose_related_goal", [goalThought.content]), // Content matches a potential rule
        'PENDING', { [exports.Keys.UI_CONTEXT]: "Auto-trigger: Propose related goal?" }, goalThought);
        this.thoughtStore.add(proposeThought);
        // Option 2: (Less clean) Direct call if ToolManager was available
        // this.toolManager.execute('user_interaction', {...}, goalThought, this.agentId)...
        // this.toolManager.execute('goal_proposal', {}, goalThought, this.agentId)...
    }
    triggerToolDiscovery(strategyThought, rootId) {
        // Create a PENDING thought that triggers the LLM tool via a rule
        const discoverThought = createThought(this.agentId, 'STRATEGY', (0, exports.S)("discover_tools_for", [strategyThought.content]), // Content matches potential rule
        'PENDING', { [exports.Keys.UI_CONTEXT]: "Auto-trigger: Discover tools?" }, strategyThought);
        this.thoughtStore.add(discoverThought);
        // Option 2: (Less clean) Direct call if ToolManager was available
        // this.toolManager.execute('llm', {...}, strategyThought, this.agentId)...
    }
    /** Handles failures during thought processing. Needs the current thought reference. */
    async handleFailure(thoughtRef, error) {
        // If the thought disappeared or was already handled, do nothing
        if (!thoughtRef || thoughtRef.status === 'FAILED' || thoughtRef.status === 'DONE') {
            // console.warn(`[${this.agentId.substring(0,8)}] Failure handling skipped for thought ${thoughtRef?.id?.substring(0,6)}: Status is ${thoughtRef?.status}.`);
            return;
        }
        console.error(`[${this.agentId.substring(0, 8)}] Failure processing Thought ${thoughtRef.id.substring(0, 6)} (Type: ${thoughtRef.type}, Status: ${thoughtRef.status}): ${error}`);
        const retries = (Number(thoughtRef.metadata[exports.Keys.RETRY_COUNT] ?? 0)) + 1;
        const newStatus = retries <= this.config.maxRetries ? 'PENDING' : 'FAILED'; // Retry or fail permanently
        const updatedMetadata = {
            [exports.Keys.ERROR]: error.substring(0, 500), // Truncate long errors
            [exports.Keys.RETRY_COUNT]: retries,
            [exports.Keys.UI_CONTEXT]: newStatus === 'PENDING'
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
        }
        else if (!updated) {
            console.warn(`[${this.agentId.substring(0, 8)}] Failed to update thought ${thoughtRef.id.substring(0, 6)} status after failure (concurrent modification?). Current status: ${this.thoughtStore.get(thoughtRef.id)?.status}`);
        }
    }
    /** Creates a thought to trigger failure rule synthesis (fire-and-forget). */
    triggerFailureRuleSynthesis(failedThought, error) {
        const failureContext = `Thought Content: ${termToString(failedThought.content)}\nThought Type: ${failedThought.type}\nError: ${error}`;
        const prompt = `Analyze the following failure scenario and synthesize a specific rule (pattern and action) to better handle similar situations in the future. If possible, suggest a corrective action.\nScenario:\n${failureContext.substring(0, 1500)}\n\nFormat: {"pattern": {... Term JSON ...}, "action": {... Term JSON ...}}`;
        // Create a thought that will trigger the LLM 'generate' action via a rule
        const synthesizeRuleThought = createThought(this.agentId, 'STRATEGY', // Meta-reasoning strategy
        (0, exports.S)("synthesize_failure_rule", [
            (0, exports.A)(failedThought.id),
            (0, exports.A)(error.substring(0, 100)) // Pass limited error info
        ]), 'PENDING', {
            [exports.Keys.UI_CONTEXT]: `Auto-trigger: Synthesize rule for failure ${failedThought.id.substring(0, 6)}?`,
            'generation_prompt': prompt, // Pass full prompt in metadata for the rule action
            [exports.Keys.PRIORITY]: 1.2 // High priority for learning from failure
        }, failedThought // Link to the failed thought
        );
        this.thoughtStore.add(synthesizeRuleThought);
    }
}
exports.Worker = Worker;
// --- User Interface (Console) ---
class UserInterface {
    thoughtStore;
    userInteractionTool;
    config;
    agentId;
    sortBy = 'timestamp';
    currentRootId = null; // For potential filtering later
    intervalId = null;
    rl = null;
    isRendering = false; // Prevent overlapping renders
    constructor(thoughtStore, userInteractionTool, // Needs the instance
    config, agentId) {
        this.thoughtStore = thoughtStore;
        this.userInteractionTool = userInteractionTool;
        this.config = config;
        this.agentId = agentId;
    }
    start() {
        if (this.rl)
            return; // Already started
        this.rl = readline.createInterface({ input: process_1.stdin, output: process_1.stdout, prompt: '> ' });
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
            if (this.intervalId)
                clearInterval(this.intervalId);
            this.intervalId = null;
            this.rl = null; // Clear reference
        });
    }
    stop() {
        if (this.intervalId)
            clearInterval(this.intervalId);
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
    async render() {
        if (!this.rl || this.rl.closed || this.isRendering)
            return;
        this.isRendering = true;
        try {
            // Store cursor position and current input line (if possible with readline)
            const currentLine = this.rl.line; // May not be reliable
            const cursorPos = this.rl.cursor; // May not be reliable
            //readline.cursorTo(output, 0, 0);
            //readline.clearScreenDown(output);
            console.log(`\n=== FlowMind Agent [${this.agentId.substring(0, 8)}] === (${new Date().toLocaleTimeString()}) ===`);
            const thoughts = this.thoughtStore.getAllThoughts();
            const notes = new Map();
            // Group thoughts by root_id
            for (const thought of thoughts) {
                // Skip DONE/FAILED root notes? Or show them? Show them for history.
                const rootId = thought.metadata[exports.Keys.ROOT_ID];
                if (!rootId) {
                    // Show orphaned thoughts (no root_id) that are active/pending?
                    if (thought.status !== 'DONE' && thought.status !== 'FAILED' && thought.type === 'INPUT') {
                        if (!notes.has(thought.id))
                            notes.set(thought.id, { root: thought, activities: [] });
                    }
                    continue;
                }
                if (!notes.has(rootId)) {
                    const rootThought = this.thoughtStore.get(rootId);
                    if (rootThought) {
                        notes.set(rootId, { root: rootThought, activities: [] });
                    }
                    else {
                        // Create a placeholder for orphaned activities if root is missing
                        const placeholderRoot = createThought(this.agentId, 'INPUT', (0, exports.A)(`Missing Root: ${rootId.substring(0, 6)}`), 'FAILED', {}, null);
                        notes.set(rootId, { root: placeholderRoot, activities: [thought] }); // Add current thought
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
            }
            else {
                console.log(`--- Notes (Sort: ${this.sortBy}) ---`);
            }
            // Display grouped notes and activities
            Array.from(notes.values())
                .sort((a, b) => (b.root.metadata[exports.Keys.TIMESTAMP] ?? 0) - (a.root.metadata[exports.Keys.TIMESTAMP] ?? 0)) // Sort notes by most recent timestamp
                .forEach(({ root, activities }) => {
                const rootContent = root.metadata[exports.Keys.UI_CONTEXT] ?? termToString(root.content);
                const rootStatusIndicator = this.getStatusIndicator(root.status);
                console.log(`\n📝 ${rootStatusIndicator} Note [${root.id.substring(0, 6)}]: ${rootContent.substring(0, 100)}${rootContent.length > 100 ? '...' : ''} (Status: ${root.status})`);
                if (activities.length > 0) {
                    const sortedActivities = [...activities].sort(this.getComparator());
                    sortedActivities.forEach(activity => {
                        const priority = activity.metadata[exports.Keys.PRIORITY];
                        const beliefScore = calculateBeliefScore(activity.belief, this.config);
                        const prioStr = typeof priority === 'number' ? `P:${priority.toFixed(1)}` : `B:${beliefScore.toFixed(2)}`;
                        const statusIndicator = this.getStatusIndicator(activity.status);
                        const uiContext = activity.metadata[exports.Keys.UI_CONTEXT] ?? termToString(activity.content);
                        const error = activity.metadata[exports.Keys.ERROR];
                        let typeStr = activity.type.padEnd(9); // Pad type for alignment
                        if (activity.type === 'WORKFLOW_STEP') {
                            typeStr = `WF Step ${activity.metadata[exports.Keys.WORKFLOW_STEP] ?? '?'}`.padEnd(9);
                        }
                        console.log(`   - ${statusIndicator} [${activity.id.substring(0, 6)}] [${prioStr}] (${typeStr}) ${uiContext.substring(0, 80)}${uiContext.length > 80 ? '...' : ''}${error ? ` | Err: ${error.substring(0, 40)}...` : ''}`);
                    });
                }
                else {
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
            if (this.rl && !this.rl.closed)
                this.rl.prompt(true); // Use true to prevent duplicate prompt character
        }
        catch (renderError) {
            console.error("Error during UI render:", renderError);
        }
        finally {
            this.isRendering = false;
        }
    }
    getStatusIndicator(status) {
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
    async promptLoop() {
        console.log("Starting command prompt loop...");
        while (true) { // Loop indefinitely until explicitly stopped
            let inputLine;
            try {
                if (!this.rl || this.rl.closed) {
                    console.log("Readline closed, exiting prompt loop.");
                    break; // Exit loop if readline is closed
                }
                // Use question to wait for input
                inputLine = await this.rl.question('> ');
                if (!this.rl || this.rl.closed)
                    break; // Check again after await
                const parts = inputLine.trim().split(' ');
                const command = parts[0]?.toLowerCase();
                switch (command) {
                    case 'sort':
                        const criteria = parts[1]?.toLowerCase();
                        if (criteria === 'time' || criteria === 'timestamp')
                            this.sortBy = 'timestamp';
                        else if (criteria === 'type')
                            this.sortBy = 'type';
                        else if (criteria === 'prio' || criteria === 'priority')
                            this.sortBy = 'priority';
                        else if (criteria === 'status')
                            this.sortBy = 'status';
                        else
                            console.log("Invalid sort criteria. Use: time, type, prio, status");
                        await this.render(); // Re-render immediately after sort change
                        break;
                    case 'add':
                        const noteText = parts.slice(1).join(' ').trim();
                        if (noteText) {
                            const newThought = createThought(this.agentId, 'INPUT', (0, exports.A)(noteText), 'PENDING', { [exports.Keys.UI_CONTEXT]: `New Note: ${noteText}`, [exports.Keys.PRIORITY]: 1.5 }, // Give new notes higher priority
                            null);
                            this.thoughtStore.add(newThought);
                            console.log(`Added new note ${newThought.id.substring(0, 6)}.`);
                            await this.render(); // Re-render immediately
                        }
                        else
                            console.log("Usage: add <Your note text>");
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
                        }
                        else {
                            if (currentPrompts.length === 0)
                                console.log("No pending prompts to respond to.");
                            else
                                console.log("Usage: respond <PromptIndex> <Your response text or option number>");
                            if (this.rl && !this.rl.closed)
                                this.rl.prompt(); // Show prompt again if usage was wrong
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
                        if (this.rl && !this.rl.closed)
                            this.rl.prompt();
                        break;
                    default:
                        console.log(`Unknown command: "${command}". Known commands: sort, add, respond, quit.`);
                        if (this.rl && !this.rl.closed)
                            this.rl.prompt();
                }
            }
            catch (error) {
                // Handle readline closure during await
                if (!this.rl || this.rl.closed || error.code === 'ERR_CLOSED') {
                    console.log("Input stream closed during processing. Exiting UI loop.");
                    break; // Exit loop
                }
                console.error("\nError in UI prompt loop:", error.message);
                if (this.rl && !this.rl.closed)
                    this.rl.prompt(); // Try to show prompt again
                await sleep(exports.DEFAULT_CONFIG.errorSleepMS); // Pause briefly after error
            }
        }
        console.log("Command prompt loop finished.");
    }
    // Comparator function for sorting activities within a note
    getComparator() {
        const getPriority = (t) => {
            const explicitPrio = t.metadata[exports.Keys.PRIORITY];
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
        const statusOrder = { 'ACTIVE': 0, 'PENDING': 1, 'WAITING': 2, 'FAILED': 3, 'DONE': 4 };
        const getTypeOrder = (t) => {
            const typePrio = {
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
        };
        switch (this.sortBy) {
            case 'type':
                // Sort by type group, then timestamp within group
                return (a, b) => getTypeOrder(a) - getTypeOrder(b) || (a.metadata[exports.Keys.TIMESTAMP] ?? 0) - (b.metadata[exports.Keys.TIMESTAMP] ?? 0);
            case 'priority':
                // Sort descending by calculated priority, then ascending by timestamp for stability
                return (a, b) => getPriority(b) - getPriority(a) || (a.metadata[exports.Keys.TIMESTAMP] ?? 0) - (b.metadata[exports.Keys.TIMESTAMP] ?? 0);
            case 'status':
                // Sort by status order, then descending priority within status
                return (a, b) => statusOrder[a.status] - statusOrder[b.status] || getPriority(b) - getPriority(a);
            case 'timestamp':
            default:
                // Sort ascending by timestamp (oldest first)
                return (a, b) => (a.metadata[exports.Keys.TIMESTAMP] ?? 0) - (b.metadata[exports.Keys.TIMESTAMP] ?? 0);
        }
    }
}
exports.UserInterface = UserInterface;
class Persistence {
    stateVersion = 2; // Incremented version due to belief/metadata/rule embedding changes
    // --- Serialization Helpers ---
    termToSerializable(term) {
        switch (term.kind) {
            case 'atom':
                return { kind: 'atom', name: term.name };
            case 'variable':
                return { kind: 'variable', name: term.name };
            case 'structure':
                return { kind: 'structure', name: term.name, args: term.args.map(arg => this.termToSerializable(arg)) };
            case 'list':
                return { kind: 'list', elements: term.elements.map(el => this.termToSerializable(el)) };
        }
    }
    serializableToTerm(sTerm) {
        // Add basic validation
        if (!sTerm || !sTerm.kind)
            throw new Error("Invalid serializable term object: missing kind");
        switch (sTerm.kind) {
            case 'atom':
                return (0, exports.A)(sTerm.name);
            case 'variable':
                return (0, exports.V)(sTerm.name);
            case 'structure':
                return (0, exports.S)(sTerm.name, (sTerm.args ?? []).map((arg) => this.serializableToTerm(arg)));
            case 'list':
                return (0, exports.L)((sTerm.elements ?? []).map((el) => this.serializableToTerm(el)));
            default:
                throw new Error(`Unknown serializable term kind: ${sTerm.kind}`);
        }
    }
    beliefToSerializable(belief) {
        return { positive: belief.positive, negative: belief.negative, lastUpdated: belief.lastUpdated };
    }
    serializableToBelief(sBelief) {
        // Provide default for lastUpdated if loading older state
        if (!sBelief || typeof sBelief.positive !== 'number' || typeof sBelief.negative !== 'number') {
            console.warn("Invalid belief object during load, using default.", sBelief);
            return { ...exports.DEFAULT_BELIEF, lastUpdated: Date.now() };
        }
        return { positive: sBelief.positive, negative: sBelief.negative, lastUpdated: sBelief.lastUpdated ?? Date.now() };
    }
    // --- Save Method ---
    async save(thoughtStore, ruleStore, memoryStore, filePath) {
        try {
            const ruleEmbeddings = ruleStore.getEmbeddings(); // Get cached embeddings
            const state = {
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
                    embedding: ruleEmbeddings.has(r.id) ? [...ruleEmbeddings.get(r.id)] : undefined
                })),
                memories: memoryStore.getAllEntries().map(m => ({
                    id: m.id,
                    embedding: [...m.embedding], // Convert ReadonlyArray back to plain array
                    content: m.content,
                    metadata: JSON.parse(JSON.stringify(m.metadata))
                }))
            };
            await fs_1.promises.writeFile(filePath, JSON.stringify(state, null, 2)); // Pretty print JSON
            // console.log(`Persistence: State (v${this.stateVersion}) saved to ${filePath}`);
        }
        catch (error) {
            console.error(`Persistence: Error saving state to ${filePath}:`, error.message);
            // throw error; // Optionally re-throw
        }
    }
    // --- Load Method ---
    async load(thoughtStore, ruleStore, // Pass RuleStore to load rules into it
    memoryStore, filePath) {
        try {
            const data = await fs_1.promises.readFile(filePath, 'utf-8');
            const state = JSON.parse(data);
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
                        const thought = Object.freeze({
                            id: st.id, type: st.type, status: st.status,
                            content: this.serializableToTerm(st.content),
                            belief: this.serializableToBelief(st.belief),
                            metadata: Object.freeze(st.metadata) // Freeze metadata
                        });
                        thoughtStore.add(thought);
                        thoughtsLoaded++;
                    }
                    catch (err) {
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
                        const rule = Object.freeze({
                            id: sr.id,
                            pattern: this.serializableToTerm(sr.pattern),
                            action: this.serializableToTerm(sr.action),
                            belief: this.serializableToBelief(sr.belief),
                            metadata: Object.freeze(sr.metadata)
                        });
                        // Add rule using RuleStore's method to handle embedding generation/caching
                        await ruleStore.add(rule);
                        rulesLoaded++;
                    }
                    catch (err) {
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
                    }
                    catch (err) {
                        console.error(`Persistence: Failed to load memory entry ${sm?.id ?? 'UNKNOWN'}:`, err.message);
                    }
                });
            }
            console.log(`Persistence: State (v${loadedVersion}) loaded from ${filePath}. Thoughts: ${thoughtsLoaded}, Rules: ${rulesLoaded}, Memories: ${memoriesLoaded}`);
        }
        catch (error) {
            if (error.code === 'ENOENT') {
                console.log(`Persistence: No state file found at ${filePath}. Starting fresh.`);
            }
            else {
                console.error(`Persistence: Error loading state from ${filePath}:`, error);
                // Decide if loading failure should prevent startup
                // throw error; // Re-throw to potentially halt startup
            }
        }
    }
}
exports.Persistence = Persistence;
// --- Agent Loop / Controller ---
class AgentLoop {
    agentId;
    config;
    thoughtStore;
    ruleStore;
    memoryStore;
    unifier;
    llmTool; // Central LLMTool instance
    userInteractionTool; // Singleton instance
    toolManager;
    actionExecutor;
    ui;
    persistence;
    workers = [];
    isRunning = false;
    saveIntervalId = null;
    constructor(agentId, configOverrides = {}) {
        this.agentId = agentId ?? generateUUID();
        // Merge default config with overrides, ensuring result is readonly
        this.config = Object.freeze({ ...exports.DEFAULT_CONFIG, ...configOverrides });
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
        this.toolManager = new ToolManager(this.memoryStore, this.thoughtStore, this.config, this.llmTool);
        // Register the singleton UserInteractionTool instance
        this.toolManager.registerToolDefinition(new UserInteractionToolDefinition(this.userInteractionTool));
        // Example: Register WebSearchTool if configured via environment variable
        const webSearchEndpoint = process.env.SEARCH_ENDPOINT;
        if (webSearchEndpoint) {
            try {
                this.toolManager.registerToolDefinition(new WebSearchToolDefinition(webSearchEndpoint));
                console.log(`Registered WebSearchTool with endpoint: ${webSearchEndpoint}`);
            }
            catch (e) {
                console.error(`Failed to register WebSearchTool: ${e.message}`);
            }
        }
        this.actionExecutor = new ActionExecutor(this.thoughtStore, this.ruleStore, this.toolManager, this.memoryStore, this.agentId, this.config, this.llmTool);
        this.ui = new UserInterface(this.thoughtStore, this.userInteractionTool, this.config, this.agentId);
        this.persistence = new Persistence();
        // Bootstrap rules after all components are initialized
        // Run async bootstrap within constructor (or call separately)
        // this.bootstrapRules().catch(e => console.error("Error during bootstrap:", e)); // Fire-and-forget bootstrap
    }
    async bootstrapRules() {
        console.log(`AgentLoop: Bootstrapping rules...`);
        // Basic flow rules + workflow example
        const rulesData = [
            // INPUT -> Goal
            {
                pattern: (0, exports.S)("input", [(0, exports.V)("ContentText")]),
                action: (0, exports.S)("llm", [(0, exports.S)("params", [
                        (0, exports.S)("action", [(0, exports.A)("generate")]),
                        (0, exports.S)("input", [(0, exports.A)('Analyze input "?ContentText" and formulate a concise goal as a structure: {"name": "goal_name", "args": [...]}')]),
                        (0, exports.S)("type", [(0, exports.A)("GOAL")])
                    ])])
            },
            // GOAL -> Strategy
            {
                pattern: (0, exports.S)("goal", [(0, exports.V)("GoalTerm")]),
                action: (0, exports.S)("llm", [(0, exports.S)("params", [
                        (0, exports.S)("action", [(0, exports.A)("generate")]),
                        (0, exports.S)("input", [(0, exports.A)('Generate initial strategy for goal "?GoalTerm" as a structure: {"name": "strategy_name", "args": [...]}.')]),
                        (0, exports.S)("type", [(0, exports.A)("STRATEGY")])
                    ])])
            },
            // STRATEGY -> Outcome/Next Step
            {
                pattern: (0, exports.S)("strategy", [(0, exports.V)("StrategyTerm")]),
                action: (0, exports.S)("llm", [(0, exports.S)("params", [
                        (0, exports.S)("action", [(0, exports.A)("generate")]),
                        (0, exports.S)("input", [(0, exports.A)('Execute strategy "?StrategyTerm" or determine next step as structure: {"name": "outcome_or_step", "args": [...]}.')]),
                        (0, exports.S)("type", [(0, exports.A)("OUTCOME")])
                    ])])
            },
            // Query -> Outcome (Answer)
            {
                pattern: (0, exports.S)("query", [(0, exports.V)("QueryTerm")]),
                action: (0, exports.S)("llm", [(0, exports.S)("params", [
                        (0, exports.S)("action", [(0, exports.A)("generate")]),
                        (0, exports.S)("input", [(0, exports.A)('Answer query "?QueryTerm". Respond with structure {"value": "Your answer"}')]),
                        (0, exports.S)("type", [(0, exports.A)("OUTCOME")])
                    ])])
            },
            // Explicit User Interaction Rule
            {
                pattern: (0, exports.S)("needs_clarification", [(0, exports.V)("Topic")]),
                action: (0, exports.S)("user_interaction", [(0, exports.S)("params", [
                        (0, exports.S)("prompt", [(0, exports.A)("Please clarify: ?Topic")]),
                        (0, exports.S)("options", [(0, exports.L)([(0, exports.A)("Yes"), (0, exports.A)("No"), (0, exports.A)("More Info")])]) // Example options
                    ])])
            },
            // Simple Memory Add Rule
            {
                pattern: (0, exports.S)("remember", [(0, exports.V)("Fact")]),
                action: (0, exports.S)("memory", [(0, exports.S)("params", [
                        (0, exports.S)("action", [(0, exports.A)("add")]),
                        (0, exports.S)("content", [(0, exports.V)("Fact")]), // Assumes Fact is atom/string
                        (0, exports.S)("type", [(0, exports.A)("fact")])
                    ])])
            },
            // Rule to trigger failure synthesis (matches thought created by handleFailure)
            {
                pattern: (0, exports.S)("synthesize_failure_rule", [(0, exports.V)("FailedThoughtID"), (0, exports.V)("ErrorHint")]),
                action: (0, exports.S)("llm", [(0, exports.S)("params", [
                        (0, exports.S)("action", [(0, exports.A)("generate")]),
                        (0, exports.S)("input", [(0, exports.V)("generation_prompt")]), // Prompt passed via metadata
                        (0, exports.S)("type", [(0, exports.A)("RULE")])
                    ])])
            },
            // Rule to trigger tool discovery
            {
                pattern: (0, exports.S)("discover_tools_for", [(0, exports.V)("StrategyContent")]),
                action: (0, exports.S)("llm", [(0, exports.S)("params", [
                        (0, exports.S)("action", [(0, exports.A)("generate")]),
                        (0, exports.S)("input", [(0, exports.A)('List external tools (web search, APIs) useful for: "?StrategyContent". Format: {"tools": [{"name": "tool_name", "type": "...", "endpoint": "...?"}, ...]}')]),
                        (0, exports.S)("type", [(0, exports.A)("TOOLS")])
                    ])])
            },
            // Rule to trigger Goal Proposal
            {
                pattern: (0, exports.S)("propose_related_goal", [(0, exports.V)("ContextTerm")]),
                action: (0, exports.S)("goal_proposal", [(0, exports.S)("params", [])])
            }, // No params needed for basic proposal
            // Example Workflow Rule: Plan Trip -> Sequence
            {
                pattern: (0, exports.S)("plan_trip", [(0, exports.V)("Destination")]),
                action: (0, exports.S)("sequence", [
                    (0, exports.S)("user_interaction", [(0, exports.S)("params", [
                            (0, exports.S)("prompt", [(0, exports.A)("What is the budget for ?Destination?")])
                        ])]),
                    (0, exports.S)("web_search", [(0, exports.S)("params", [
                            (0, exports.S)("query", [(0, exports.A)("Flights to ?Destination")])
                        ])]),
                    (0, exports.S)("llm", [(0, exports.S)("params", [
                            (0, exports.S)("action", [(0, exports.A)("generate")]),
                            (0, exports.S)("input", [(0, exports.A)('Summarize trip plan for ?Destination based on budget and flight info found.')]),
                            (0, exports.S)("type", [(0, exports.A)("OUTCOME")])
                        ])])
                ])
            },
            {
                pattern: (0, exports.S)("needs_clarification", [(0, exports.V)("GoalContent")]), // Match the structure created by the trigger
                action: (0, exports.S)("user_interaction", [(0, exports.S)("params", [
                        (0, exports.S)("prompt", [(0, exports.A)("Clarify goal/topic: ?GoalContent")]), // Use the bound variable
                        (0, exports.S)("options", [(0, exports.L)([(0, exports.A)("Proceed As Is"), (0, exports.A)("Refine"), (0, exports.A)("Cancel")])])
                    ])])
            },
        ];
        let count = 0;
        for (const rData of rulesData) {
            const rule = Object.freeze({
                id: generateUUID(),
                belief: { ...exports.DEFAULT_BELIEF, lastUpdated: Date.now() },
                metadata: Object.freeze({
                    [exports.Keys.AGENT_ID]: this.agentId,
                    [exports.Keys.TIMESTAMP]: Date.now(),
                    bootstrap: true // Mark as a bootstrap rule
                }),
                ...rData
            });
            await this.ruleStore.add(rule); // Use async add for embedding
            count++;
        }
        console.log(`AgentLoop: Bootstrapped ${count} rules.`);
    }
    async start() {
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
        }
        catch (err) {
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
        if (this.config.numWorkers <= 0)
            console.warn("Agent configured with 0 workers!");
        this.workers.length = 0; // Clear any previous worker references
        for (let i = 0; i < this.config.numWorkers; i++) {
            const worker = new Worker(this.agentId, this.thoughtStore, this.ruleStore, this.unifier, this.actionExecutor, // Pass ActionExecutor
            // ToolManager, MemoryStore, LLMTool not directly needed by worker loop itself
            this.config);
            this.workers.push(worker);
            worker.start();
        }
        // Start periodic saving
        if (this.config.persistenceIntervalMillis > 0) {
            this.saveIntervalId = setInterval(async () => {
                if (!this.isRunning)
                    return; // Don't save if stopping
                try {
                    // console.log(`AgentLoop: Performing periodic save...`); // Reduce log noise?
                    await this.persistence.save(this.thoughtStore, this.ruleStore, this.memoryStore, this.config.persistenceFilePath);
                }
                catch (err) {
                    console.error("AgentLoop: Periodic save failed:", err.message);
                }
            }, this.config.persistenceIntervalMillis);
        }
        console.log(`Agent ${this.agentId} started successfully. UI is active.`);
    }
    async stop() {
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
        }
        catch (err) {
            console.error("AgentLoop: Final save failed:", err.message);
        }
        console.log(`Agent ${this.agentId} stopped.`);
    }
    /** Adds a new top-level INPUT thought to the system. */
    addInput(text) {
        if (!this.isRunning) {
            console.warn("Agent not running. Cannot add input.");
            return;
        }
        if (!text || typeof text !== 'string' || text.trim() === '') {
            console.warn("Cannot add empty input.");
            return;
        }
        const newThought = createThought(this.agentId, 'INPUT', (0, exports.A)(text.trim()), 'PENDING', { [exports.Keys.UI_CONTEXT]: `User Input: ${text.trim()}`, [exports.Keys.PRIORITY]: 1.5 }, // Give user input high priority
        null // No parent for new input
        );
        this.thoughtStore.add(newThought);
        console.log(`Added user input thought ${newThought.id.substring(0, 6)}.`);
        // UI will pick it up on next render cycle
        // this.ui.render(); // Avoid direct render call from external method
    }
}
exports.AgentLoop = AgentLoop;
// --- Main Execution Example ---
async function main() {
    console.log("Starting FlowMind Agent...");
    const agent = new AgentLoop("agent_main");
    let shuttingDown = false;
    const shutdown = async (signal) => {
        if (shuttingDown)
            return;
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
        if (!shuttingDown)
            await shutdown('uncaughtException');
        process.exit(1);
    });
    process.on('unhandledRejection', async (reason, promise) => {
        console.error('\nFATAL: Unhandled Rejection at:', promise, 'reason:', reason);
        if (!shuttingDown)
            await shutdown('unhandledRejection');
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
    }
    catch (error) {
        console.error("Critical error during agent startup:", error);
        if (!shuttingDown)
            await agent.stop(); // Attempt cleanup
        process.exit(1);
    }
}
main();
