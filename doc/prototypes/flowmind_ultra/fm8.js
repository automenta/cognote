"use strict";
var __extends = (this && this.__extends) || (function () {
    var extendStatics = function (d, b) {
        extendStatics = Object.setPrototypeOf ||
            ({ __proto__: [] } instanceof Array && function (d, b) { d.__proto__ = b; }) ||
            function (d, b) { for (var p in b) if (Object.prototype.hasOwnProperty.call(b, p)) d[p] = b[p]; };
        return extendStatics(d, b);
    };
    return function (d, b) {
        if (typeof b !== "function" && b !== null)
            throw new TypeError("Class extends value " + String(b) + " is not a constructor or null");
        extendStatics(d, b);
        function __() { this.constructor = d; }
        d.prototype = b === null ? Object.create(b) : (__.prototype = b.prototype, new __());
    };
})();
var __assign = (this && this.__assign) || function () {
    __assign = Object.assign || function(t) {
        for (var s, i = 1, n = arguments.length; i < n; i++) {
            s = arguments[i];
            for (var p in s) if (Object.prototype.hasOwnProperty.call(s, p))
                t[p] = s[p];
        }
        return t;
    };
    return __assign.apply(this, arguments);
};
var __awaiter = (this && this.__awaiter) || function (thisArg, _arguments, P, generator) {
    function adopt(value) { return value instanceof P ? value : new P(function (resolve) { resolve(value); }); }
    return new (P || (P = Promise))(function (resolve, reject) {
        function fulfilled(value) { try { step(generator.next(value)); } catch (e) { reject(e); } }
        function rejected(value) { try { step(generator["throw"](value)); } catch (e) { reject(e); } }
        function step(result) { result.done ? resolve(result.value) : adopt(result.value).then(fulfilled, rejected); }
        step((generator = generator.apply(thisArg, _arguments || [])).next());
    });
};
var __generator = (this && this.__generator) || function (thisArg, body) {
    var _ = { label: 0, sent: function() { if (t[0] & 1) throw t[1]; return t[1]; }, trys: [], ops: [] }, f, y, t, g = Object.create((typeof Iterator === "function" ? Iterator : Object).prototype);
    return g.next = verb(0), g["throw"] = verb(1), g["return"] = verb(2), typeof Symbol === "function" && (g[Symbol.iterator] = function() { return this; }), g;
    function verb(n) { return function (v) { return step([n, v]); }; }
    function step(op) {
        if (f) throw new TypeError("Generator is already executing.");
        while (g && (g = 0, op[0] && (_ = 0)), _) try {
            if (f = 1, y && (t = op[0] & 2 ? y["return"] : op[0] ? y["throw"] || ((t = y["return"]) && t.call(y), 0) : y.next) && !(t = t.call(y, op[1])).done) return t;
            if (y = 0, t) op = [op[0] & 2, t.value];
            switch (op[0]) {
                case 0: case 1: t = op; break;
                case 4: _.label++; return { value: op[1], done: false };
                case 5: _.label++; y = op[1]; op = [0]; continue;
                case 7: op = _.ops.pop(); _.trys.pop(); continue;
                default:
                    if (!(t = _.trys, t = t.length > 0 && t[t.length - 1]) && (op[0] === 6 || op[0] === 2)) { _ = 0; continue; }
                    if (op[0] === 3 && (!t || (op[1] > t[0] && op[1] < t[3]))) { _.label = op[1]; break; }
                    if (op[0] === 6 && _.label < t[1]) { _.label = t[1]; t = op; break; }
                    if (t && _.label < t[2]) { _.label = t[2]; _.ops.push(op); break; }
                    if (t[2]) _.ops.pop();
                    _.trys.pop(); continue;
            }
            op = body.call(thisArg, _);
        } catch (e) { op = [6, e]; y = 0; } finally { f = t = 0; }
        if (op[0] & 5) throw op[1]; return { value: op[0] ? op[1] : void 0, done: true };
    }
};
Object.defineProperty(exports, "__esModule", { value: true });
var fs = require("fs/promises");
var fsSync = require("fs");
var path = require("path");
var os = require("os");
var readline = require("readline");
var ws_1 = require("ws");
var uuid_1 = require("uuid");
var chalk_1 = require("chalk");
var ollama_1 = require("@langchain/community/chat_models/ollama");
var ollama_2 = require("@langchain/community/embeddings/ollama");
var messages_1 = require("@langchain/core/messages");
var output_parsers_1 = require("@langchain/core/output_parsers");
var documents_1 = require("@langchain/core/documents");
var faiss_1 = require("@langchain/community/vectorstores/faiss");
// --- Configuration ---
var DATA_DIR = path.join(os.homedir(), '.flowmind-ws');
var STATE_FILE = path.join(DATA_DIR, 'flowmind_state.json');
var VECTOR_STORE_DIR = path.join(DATA_DIR, 'vector-store');
var OLLAMA_BASE_URL = process.env.OLLAMA_HOST || "http://localhost:11434";
var OLLAMA_MODEL = 'hf.co/DevQuasar/Orion-zhen.phi-4-abliterated-GGUF:Q3_K_M';
var OLLAMA_EMBEDDING_MODEL = OLLAMA_MODEL;
var WORKER_INTERVAL = 2000;
var SAVE_DEBOUNCE = 5000;
var MAX_RETRIES = 2;
var DEFAULT_BELIEF_POS = 1.0;
var DEFAULT_BELIEF_NEG = 1.0;
var DEFAULT_PRIORITY = 1.0;
var SHORT_ID_LEN = 6;
var ACTION_TIMEOUT_MS = 5000;
var WS_PORT = 8080;
var WORKER_ID = "worker-".concat((0, uuid_1.v4)().substring(0, 8));
// --- Enums ---
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
// --- Utility Functions ---
var generateId = function () { return (0, uuid_1.v4)(); };
var shortId = function (id) { return id ? id.substring(0, SHORT_ID_LEN) : '------'; };
function debounce(func, wait) {
    var timeout = null;
    var debounced = function () {
        var args = [];
        for (var _i = 0; _i < arguments.length; _i++) {
            args[_i] = arguments[_i];
        }
        var later = function () { timeout = null; func.apply(void 0, args); };
        if (timeout)
            clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
    debounced.cancel = function () { if (timeout) {
        clearTimeout(timeout);
        timeout = null;
    } };
    return debounced;
}
var safeJsonParse = function (json, defaultValue) {
    if (!json)
        return defaultValue;
    try {
        return JSON.parse(json);
    }
    catch (_a) {
        return defaultValue;
    }
};
var sleep = function (ms) { return new Promise(function (resolve) { return setTimeout(resolve, ms); }); };
// --- Core Classes ---
var Belief = /** @class */ (function () {
    function Belief(pos, neg) {
        if (pos === void 0) { pos = DEFAULT_BELIEF_POS; }
        if (neg === void 0) { neg = DEFAULT_BELIEF_NEG; }
        this.pos = Math.max(0, pos);
        this.neg = Math.max(0, neg);
    }
    Belief.prototype.score = function () { return (this.pos + 1) / (this.pos + this.neg + 2); };
    Belief.prototype.update = function (success) { success ? this.pos++ : this.neg++; };
    Belief.prototype.toJSON = function () { return { pos: this.pos, neg: this.neg }; };
    Belief.fromJSON = function (data) { return new Belief(data === null || data === void 0 ? void 0 : data.pos, data === null || data === void 0 ? void 0 : data.neg); };
    Belief.DEFAULT = new Belief();
    return Belief;
}());
var TermLogic;
(function (TermLogic) {
    TermLogic.Atom = function (name) { return ({ kind: 'Atom', name: name }); };
    TermLogic.Variable = function (name) { return ({ kind: 'Variable', name: name }); };
    TermLogic.Structure = function (name, args) { return ({ kind: 'Structure', name: name, args: args }); };
    TermLogic.List = function (elements) { return ({ kind: 'ListTerm', elements: elements }); };
    function format(term) {
        if (!term)
            return chalk_1.default.grey('null');
        switch (term.kind) {
            case 'Atom': return chalk_1.default.green(term.name);
            case 'Variable': return chalk_1.default.cyan("?".concat(term.name));
            case 'Structure': return "".concat(chalk_1.default.yellow(term.name), "(").concat(term.args.map(format).join(', '), ")");
            case 'ListTerm': return "[".concat(term.elements.map(format).join(', '), "]");
            default:
                var invalid = term;
                return chalk_1.default.red('invalid_term');
        }
    }
    TermLogic.format = format;
    function toString(term) {
        switch (term.kind) {
            case 'Atom': return term.name;
            case 'Variable': return "?".concat(term.name);
            case 'Structure': return "".concat(term.name, "(").concat(term.args.map(toString).join(', '), ")");
            case 'ListTerm': return "[".concat(term.elements.map(toString).join(', '), "]");
            default:
                var invalid = term;
                return '';
        }
    }
    TermLogic.toString = toString;
    function fromString(input) {
        input = input.trim();
        if (input.startsWith('[') && input.endsWith(']')) {
            var elements = input.slice(1, -1).split(',').map(function (s) { return TermLogic.Atom(s.trim()); });
            return TermLogic.List(elements);
        }
        var structureMatch = input.match(/^([a-zA-Z0-9_]+)\((.*)\)$/);
        if (structureMatch) {
            var name_1 = structureMatch[1];
            var argsStr = structureMatch[2];
            // Basic parsing, assumes args are simple Atoms or nested structures
            // This is naive and might fail on complex nested terms or terms with commas in names
            var args = argsStr ? argsStr.split(/,\s*(?![^()]*\))/) /* Avoid splitting inside parentheses */.map(fromString) : [];
            return TermLogic.Structure(name_1, args);
        }
        if (input.startsWith('?'))
            return TermLogic.Variable(input.substring(1));
        return TermLogic.Atom(input);
    }
    TermLogic.fromString = fromString;
    function fromJson(json) {
        if (!json || typeof json !== 'object' || !json.kind)
            return null;
        try {
            switch (json.kind) {
                case 'Atom': return TermLogic.Atom(json.name);
                case 'Variable': return TermLogic.Variable(json.name);
                case 'Structure': return TermLogic.Structure(json.name, json.args.map(fromJson).filter(Boolean));
                case 'ListTerm': return TermLogic.List(json.elements.map(fromJson).filter(Boolean));
                default: return null;
            }
        }
        catch (_a) {
            return null;
        }
    }
    TermLogic.fromJson = fromJson;
    function toJson(term) { return term; } // Terms are already JSON-serializable
    TermLogic.toJson = toJson;
    function unify(term1, term2, bindings) {
        if (bindings === void 0) { bindings = new Map(); }
        var resolve = function (term, currentBindings) {
            return (term.kind === 'Variable' && currentBindings.has(term.name)) ? resolve(currentBindings.get(term.name), currentBindings) : term;
        };
        var t1 = resolve(term1, bindings);
        var t2 = resolve(term2, bindings);
        if (t1.kind === 'Variable')
            return t1.name === t2.name && t1.kind === t2.kind ? bindings : new Map(bindings).set(t1.name, t2);
        if (t2.kind === 'Variable')
            return new Map(bindings).set(t2.name, t1);
        if (t1.kind !== t2.kind)
            return null;
        switch (t1.kind) {
            case 'Atom': return t1.name === t2.name ? bindings : null;
            case 'Structure': {
                var s2 = t2;
                if (t1.name !== s2.name || t1.args.length !== s2.args.length)
                    return null;
                var currentBindings = bindings;
                for (var i = 0; i < t1.args.length; i++) {
                    var result = unify(t1.args[i], s2.args[i], currentBindings);
                    if (!result)
                        return null;
                    currentBindings = result;
                }
                return currentBindings;
            }
            case 'ListTerm': {
                var l2 = t2;
                if (t1.elements.length !== l2.elements.length)
                    return null;
                var currentBindings = bindings;
                for (var i = 0; i < t1.elements.length; i++) {
                    var result = unify(t1.elements[i], l2.elements[i], currentBindings);
                    if (!result)
                        return null;
                    currentBindings = result;
                }
                return currentBindings;
            }
            default:
                var invalid = t1;
                return null; // Should be unreachable
        }
    }
    TermLogic.unify = unify;
    function substitute(term, bindings) {
        if (term.kind === 'Variable' && bindings.has(term.name))
            return substitute(bindings.get(term.name), bindings);
        if (term.kind === 'Structure')
            return __assign(__assign({}, term), { args: term.args.map(function (arg) { return substitute(arg, bindings); }) });
        if (term.kind === 'ListTerm')
            return __assign(__assign({}, term), { elements: term.elements.map(function (el) { return substitute(el, bindings); }) });
        return term;
    }
    TermLogic.substitute = substitute;
})(TermLogic || (TermLogic = {}));
var BaseStore = /** @class */ (function () {
    function BaseStore() {
        this.items = new Map();
        this.listeners = [];
    }
    BaseStore.prototype.add = function (item) { this.items.set(item.id, item); this.notifyChange(); };
    BaseStore.prototype.get = function (id) { return this.items.get(id); };
    BaseStore.prototype.update = function (item) {
        if (this.items.has(item.id)) {
            var existing = this.items.get(item.id);
            item.metadata = __assign(__assign(__assign({}, existing === null || existing === void 0 ? void 0 : existing.metadata), item.metadata), { modified: new Date().toISOString() });
            this.items.set(item.id, item);
            this.notifyChange();
        }
    };
    BaseStore.prototype.delete = function (id) { var deleted = this.items.delete(id); if (deleted)
        this.notifyChange(); return deleted; };
    BaseStore.prototype.getAll = function () { return Array.from(this.items.values()); };
    BaseStore.prototype.count = function () { return this.items.size; };
    BaseStore.prototype.findItemByPrefix = function (prefix) {
        if (this.items.has(prefix))
            return this.items.get(prefix);
        for (var _i = 0, _a = this.items.values(); _i < _a.length; _i++) {
            var item = _a[_i];
            if (item.id.startsWith(prefix))
                return item;
        }
        return undefined;
    };
    BaseStore.prototype.addChangeListener = function (listener) { this.listeners.push(listener); };
    BaseStore.prototype.removeChangeListener = function (listener) { this.listeners = this.listeners.filter(function (l) { return l !== listener; }); };
    BaseStore.prototype.notifyChange = function () { this.listeners.forEach(function (listener) { return listener(); }); };
    return BaseStore;
}());
var ThoughtStore = /** @class */ (function (_super) {
    __extends(ThoughtStore, _super);
    function ThoughtStore() {
        return _super !== null && _super.apply(this, arguments) || this;
    }
    ThoughtStore.prototype.getPending = function () { return this.getAll().filter(function (t) { return t.status === Status.PENDING; }); };
    ThoughtStore.prototype.getAllByRootId = function (rootId) { return this.getAll().filter(function (t) { return t.metadata.rootId === rootId || t.id === rootId; }).sort(function (a, b) { return Date.parse(a.metadata.created) - Date.parse(b.metadata.created); }); };
    ThoughtStore.prototype.searchByTag = function (tag) { return this.getAll().filter(function (t) { var _a; return (_a = t.metadata.tags) === null || _a === void 0 ? void 0 : _a.includes(tag); }); };
    ThoughtStore.prototype.findThought = function (idPrefix) { return this.findItemByPrefix(idPrefix); };
    ThoughtStore.prototype.toJSON = function () {
        var obj = {};
        this.items.forEach(function (thought, id) { return obj[id] = __assign(__assign({}, thought), { belief: thought.belief.toJSON(), content: TermLogic.toJson(thought.content) }); });
        return obj;
    };
    ThoughtStore.prototype.loadJSON = function (data) {
        var _this = this;
        this.items.clear();
        Object.entries(data).forEach(function (_a) {
            var id = _a[0], thoughtData = _a[1];
            var contentTerm = TermLogic.fromJson(thoughtData.content);
            if (contentTerm)
                _this.add(__assign(__assign({}, thoughtData), { belief: Belief.fromJSON(thoughtData.belief), content: contentTerm, id: id }));
            else
                console.warn(chalk_1.default.yellow("Failed to load thought ".concat(id, ": Invalid content term.")));
        });
    };
    return ThoughtStore;
}(BaseStore));
var RuleStore = /** @class */ (function (_super) {
    __extends(RuleStore, _super);
    function RuleStore() {
        return _super !== null && _super.apply(this, arguments) || this;
    }
    RuleStore.prototype.searchByDescription = function (desc) { var lowerDesc = desc.toLowerCase(); return this.getAll().filter(function (r) { var _a; return (_a = r.metadata.description) === null || _a === void 0 ? void 0 : _a.toLowerCase().includes(lowerDesc); }); };
    RuleStore.prototype.findRule = function (idPrefix) { return this.findItemByPrefix(idPrefix); };
    RuleStore.prototype.toJSON = function () {
        var obj = {};
        this.items.forEach(function (rule, id) { return obj[id] = __assign(__assign({}, rule), { belief: rule.belief.toJSON(), pattern: TermLogic.toJson(rule.pattern), action: TermLogic.toJson(rule.action) }); });
        return obj;
    };
    RuleStore.prototype.loadJSON = function (data) {
        var _this = this;
        this.items.clear();
        Object.entries(data).forEach(function (_a) {
            var id = _a[0], ruleData = _a[1];
            var patternTerm = TermLogic.fromJson(ruleData.pattern);
            var actionTerm = TermLogic.fromJson(ruleData.action);
            if (patternTerm && actionTerm)
                _this.add(__assign(__assign({}, ruleData), { belief: Belief.fromJSON(ruleData.belief), pattern: patternTerm, action: actionTerm, id: id }));
            else
                console.warn(chalk_1.default.yellow("Failed to load rule ".concat(id, ": Invalid pattern or action term.")));
        });
    };
    return RuleStore;
}(BaseStore));
var MemoryStore = /** @class */ (function () {
    function MemoryStore(embeddingsService, storePath) {
        this.vectorStore = null;
        this.isReady = false;
        this.embeddings = embeddingsService;
        this.storePath = storePath;
    }
    MemoryStore.prototype.initialize = function () {
        return __awaiter(this, void 0, void 0, function () {
            var _a, error_1, dummyDoc, _b, initError_1;
            return __generator(this, function (_c) {
                switch (_c.label) {
                    case 0:
                        if (this.isReady)
                            return [2 /*return*/];
                        _c.label = 1;
                    case 1:
                        _c.trys.push([1, 4, , 12]);
                        return [4 /*yield*/, fs.access(this.storePath)];
                    case 2:
                        _c.sent();
                        _a = this;
                        return [4 /*yield*/, faiss_1.FaissStore.load(this.storePath, this.embeddings)];
                    case 3:
                        _a.vectorStore = _c.sent();
                        this.isReady = true;
                        console.log(chalk_1.default.blue("Vector store loaded from ".concat(this.storePath)));
                        return [3 /*break*/, 12];
                    case 4:
                        error_1 = _c.sent();
                        if (!(error_1.code === 'ENOENT')) return [3 /*break*/, 10];
                        console.log(chalk_1.default.yellow("Vector store not found at ".concat(this.storePath, ", initializing.")));
                        _c.label = 5;
                    case 5:
                        _c.trys.push([5, 8, , 9]);
                        dummyDoc = new documents_1.Document({ pageContent: "Init", metadata: { sourceId: "init", type: "SYSTEM", created: new Date().toISOString(), id: generateId() } });
                        _b = this;
                        return [4 /*yield*/, faiss_1.FaissStore.fromDocuments([dummyDoc], this.embeddings)];
                    case 6:
                        _b.vectorStore = _c.sent();
                        return [4 /*yield*/, this.vectorStore.save(this.storePath)];
                    case 7:
                        _c.sent();
                        this.isReady = true;
                        console.log(chalk_1.default.green("New vector store created at ".concat(this.storePath)));
                        return [3 /*break*/, 9];
                    case 8:
                        initError_1 = _c.sent();
                        console.error(chalk_1.default.red('Failed to initialize new vector store:'), initError_1);
                        return [3 /*break*/, 9];
                    case 9: return [3 /*break*/, 11];
                    case 10:
                        console.error(chalk_1.default.red('Failed to load vector store:'), error_1);
                        _c.label = 11;
                    case 11: return [3 /*break*/, 12];
                    case 12: return [2 /*return*/];
                }
            });
        });
    };
    MemoryStore.prototype.add = function (entry) {
        return __awaiter(this, void 0, void 0, function () {
            var doc, error_2;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        if (!this.vectorStore || !this.isReady) {
                            console.warn(chalk_1.default.yellow('MemoryStore not ready, cannot add entry.'));
                            return [2 /*return*/];
                        }
                        doc = new documents_1.Document({ pageContent: entry.content, metadata: __assign(__assign({}, entry.metadata), { id: entry.id }) });
                        _a.label = 1;
                    case 1:
                        _a.trys.push([1, 4, , 5]);
                        return [4 /*yield*/, this.vectorStore.addDocuments([doc], { ids: [entry.id] })];
                    case 2:
                        _a.sent();
                        return [4 /*yield*/, this.save()];
                    case 3:
                        _a.sent();
                        return [3 /*break*/, 5];
                    case 4:
                        error_2 = _a.sent();
                        console.error(chalk_1.default.red('Failed to add document to vector store:'), error_2);
                        return [3 /*break*/, 5];
                    case 5: return [2 /*return*/];
                }
            });
        });
    };
    MemoryStore.prototype.search = function (query_1) {
        return __awaiter(this, arguments, void 0, function (query, k) {
            var results, vectors_1, error_3;
            if (k === void 0) { k = 5; }
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        if (!this.vectorStore || !this.isReady) {
                            console.warn(chalk_1.default.yellow('MemoryStore not ready, cannot search.'));
                            return [2 /*return*/, []];
                        }
                        _a.label = 1;
                    case 1:
                        _a.trys.push([1, 4, , 5]);
                        return [4 /*yield*/, this.vectorStore.similaritySearchWithScore(query, k)];
                    case 2:
                        results = _a.sent();
                        return [4 /*yield*/, this.embeddings.embedDocuments(results.map(function (_a) {
                                var doc = _a[0];
                                return doc.pageContent;
                            }))];
                    case 3:
                        vectors_1 = _a.sent();
                        return [2 /*return*/, results.map(function (_a, index) {
                                var doc = _a[0], score = _a[1];
                                return ({
                                    id: doc.metadata.id || generateId(), // Ensure ID exists
                                    embedding: vectors_1[index] || [], // Explicitly add embedding
                                    content: doc.pageContent,
                                    metadata: doc.metadata,
                                    score: score
                                });
                            })];
                    case 4:
                        error_3 = _a.sent();
                        console.error(chalk_1.default.red('Failed to search vector store:'), error_3);
                        return [2 /*return*/, []];
                    case 5: return [2 /*return*/];
                }
            });
        });
    };
    MemoryStore.prototype.save = function () {
        return __awaiter(this, void 0, void 0, function () { var error_4; return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    if (!(this.vectorStore && this.isReady)) return [3 /*break*/, 4];
                    _a.label = 1;
                case 1:
                    _a.trys.push([1, 3, , 4]);
                    return [4 /*yield*/, this.vectorStore.save(this.storePath)];
                case 2:
                    _a.sent();
                    return [3 /*break*/, 4];
                case 3:
                    error_4 = _a.sent();
                    console.error(chalk_1.default.red('Failed to save vector store:'), error_4);
                    return [3 /*break*/, 4];
                case 4: return [2 /*return*/];
            }
        }); });
    };
    return MemoryStore;
}());
var LLMService = /** @class */ (function () {
    function LLMService() {
        this.llm = new ollama_1.ChatOllama({ baseUrl: OLLAMA_BASE_URL, model: OLLAMA_MODEL, temperature: 0.7 });
        this.embeddings = new ollama_2.OllamaEmbeddings({ model: OLLAMA_EMBEDDING_MODEL, baseUrl: OLLAMA_BASE_URL });
        console.log(chalk_1.default.blue("LLM Service: Model=".concat(OLLAMA_MODEL, ", Embeddings=").concat(OLLAMA_EMBEDDING_MODEL, ", URL=").concat(OLLAMA_BASE_URL)));
    }
    LLMService.prototype.generate = function (prompt) {
        return __awaiter(this, void 0, void 0, function () { var response, error_5; return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    _a.trys.push([0, 2, , 3]);
                    return [4 /*yield*/, this.llm.pipe(new output_parsers_1.StringOutputParser()).invoke([new messages_1.HumanMessage(prompt)])];
                case 1:
                    response = _a.sent();
                    return [2 /*return*/, response.trim()];
                case 2:
                    error_5 = _a.sent();
                    console.error(chalk_1.default.red("LLM generation failed: ".concat(error_5.message)));
                    return [2 /*return*/, "Error: LLM generation failed. ".concat(error_5.message)];
                case 3: return [2 /*return*/];
            }
        }); });
    };
    LLMService.prototype.embed = function (text) {
        return __awaiter(this, void 0, void 0, function () { var error_6; return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    _a.trys.push([0, 2, , 3]);
                    return [4 /*yield*/, this.embeddings.embedQuery(text)];
                case 1: return [2 /*return*/, _a.sent()];
                case 2:
                    error_6 = _a.sent();
                    console.error(chalk_1.default.red("Embedding failed: ".concat(error_6.message)));
                    return [2 /*return*/, []];
                case 3: return [2 /*return*/];
            }
        }); });
    };
    return LLMService;
}());
// --- Tools ---
var ToolManager = /** @class */ (function () {
    function ToolManager() {
        this.tools = new Map();
    }
    ToolManager.prototype.register = function (tool) { if (this.tools.has(tool.name))
        console.warn(chalk_1.default.yellow("Tool \"".concat(tool.name, "\" redefined."))); this.tools.set(tool.name, tool); };
    ToolManager.prototype.get = function (name) { return this.tools.get(name); };
    ToolManager.prototype.list = function () { return Array.from(this.tools.values()); };
    ToolManager.prototype.getAll = function () { return this.tools; };
    return ToolManager;
}());
var LLMTool = /** @class */ (function () {
    function LLMTool() {
        this.name = "LLMTool";
        this.description = "Interacts with the LLM for generation and embedding.";
    }
    LLMTool.prototype.execute = function (action, context, trigger) {
        return __awaiter(this, void 0, void 0, function () {
            var operation, contentTerm, prompt_1, response, parsedTerm, text, embedding;
            var _a;
            return __generator(this, function (_b) {
                switch (_b.label) {
                    case 0:
                        operation = ((_a = action.args[0]) === null || _a === void 0 ? void 0 : _a.kind) === 'Atom' ? action.args[0].name : null;
                        contentTerm = action.args[1];
                        if (!operation || !contentTerm)
                            return [2 /*return*/, TermLogic.Atom("error:LLMTool_invalid_params")];
                        if (!(operation === 'generate')) return [3 /*break*/, 2];
                        prompt_1 = TermLogic.toString(contentTerm);
                        return [4 /*yield*/, context.llm.generate(prompt_1)];
                    case 1:
                        response = _b.sent();
                        if (response.startsWith('Error:'))
                            return [2 /*return*/, TermLogic.Atom("error:LLMTool_generation_failed:".concat(response))];
                        // Attempt to parse response as JSON Term
                        try {
                            parsedTerm = TermLogic.fromJson(JSON.parse(response));
                            if (parsedTerm)
                                return [2 /*return*/, parsedTerm];
                        }
                        catch ( /* Ignore parsing error, treat as atom */_c) { /* Ignore parsing error, treat as atom */ }
                        return [2 /*return*/, TermLogic.Atom(response)]; // Fallback to Atom
                    case 2:
                        if (!(operation === 'embed')) return [3 /*break*/, 4];
                        text = TermLogic.toString(contentTerm);
                        return [4 /*yield*/, context.llm.embed(text)];
                    case 3:
                        embedding = _b.sent();
                        return [2 /*return*/, embedding.length > 0 ? TermLogic.Atom("ok:embedded:".concat(embedding.length, "_dims")) : TermLogic.Atom("error:LLMTool_embedding_failed")];
                    case 4: return [2 /*return*/, TermLogic.Atom("error:LLMTool_unsupported_operation:".concat(operation))];
                }
            });
        });
    };
    // Reintroduced embed method (delegated to LLMService via execute)
    LLMTool.prototype.embed = function (text, context) {
        return __awaiter(this, void 0, void 0, function () { return __generator(this, function (_a) {
            return [2 /*return*/, context.llm.embed(text)];
        }); });
    };
    return LLMTool;
}());
var MemoryTool = /** @class */ (function () {
    function MemoryTool() {
        this.name = "MemoryTool";
        this.description = "Manages memory operations: add, search.";
    }
    MemoryTool.prototype.execute = function (action, context, trigger) {
        return __awaiter(this, void 0, void 0, function () {
            var operation, contentTerm, contentStr, embedding, queryTerm, kTerm, queryStr, k, results;
            var _a;
            return __generator(this, function (_b) {
                switch (_b.label) {
                    case 0:
                        operation = ((_a = action.args[0]) === null || _a === void 0 ? void 0 : _a.kind) === 'Atom' ? action.args[0].name : null;
                        if (!operation)
                            return [2 /*return*/, TermLogic.Atom("error:MemoryTool_missing_operation")];
                        if (!(operation === 'add')) return [3 /*break*/, 3];
                        contentTerm = action.args[1];
                        if (!contentTerm)
                            return [2 /*return*/, TermLogic.Atom("error:MemoryTool_missing_add_content")];
                        contentStr = TermLogic.toString(contentTerm);
                        return [4 /*yield*/, context.llm.embed(contentStr)];
                    case 1:
                        embedding = _b.sent();
                        return [4 /*yield*/, context.memory.add({ id: generateId(), content: contentStr, embedding: embedding, metadata: { created: new Date().toISOString(), type: trigger.type, sourceId: trigger.id } })];
                    case 2:
                        _b.sent();
                        trigger.metadata.embedded = new Date().toISOString();
                        context.thoughts.update(trigger);
                        return [2 /*return*/, TermLogic.Atom("ok:memory_added")];
                    case 3:
                        if (!(operation === 'search')) return [3 /*break*/, 5];
                        queryTerm = action.args[1];
                        kTerm = action.args[2];
                        if (!queryTerm)
                            return [2 /*return*/, TermLogic.Atom("error:MemoryTool_missing_search_query")];
                        queryStr = TermLogic.toString(queryTerm);
                        k = ((kTerm === null || kTerm === void 0 ? void 0 : kTerm.kind) === 'Atom' && !isNaN(parseInt(kTerm.name))) ? parseInt(kTerm.name, 10) : 3;
                        return [4 /*yield*/, context.memory.search(queryStr, k)];
                    case 4:
                        results = _b.sent();
                        return [2 /*return*/, TermLogic.List(results.map(function (r) { return TermLogic.Atom(r.content); }))];
                    case 5: return [2 /*return*/, TermLogic.Atom("error:MemoryTool_unsupported_operation:".concat(operation))];
                }
            });
        });
    };
    return MemoryTool;
}());
var UserInteractionTool = /** @class */ (function () {
    function UserInteractionTool() {
        this.name = "UserInteractionTool";
        this.description = "Requests input from the user via a prompt.";
    }
    UserInteractionTool.prototype.execute = function (action, context, trigger) {
        return __awaiter(this, void 0, void 0, function () {
            var operation, promptTextTerm, promptText, promptId;
            var _a, _b;
            return __generator(this, function (_c) {
                operation = ((_a = action.args[0]) === null || _a === void 0 ? void 0 : _a.kind) === 'Atom' ? action.args[0].name : null;
                if (operation !== 'prompt')
                    return [2 /*return*/, TermLogic.Atom("error:UITool_unsupported_operation")];
                promptTextTerm = action.args[1];
                if (!promptTextTerm)
                    return [2 /*return*/, TermLogic.Atom("error:UITool_missing_prompt_text")];
                promptText = TermLogic.toString(promptTextTerm);
                promptId = generateId();
                context.engine.addThought({
                    id: generateId(), type: Type.USER_PROMPT, content: TermLogic.Atom(promptText), belief: Belief.DEFAULT, status: Status.PENDING,
                    metadata: {
                        agentId: WORKER_ID, created: new Date().toISOString(), priority: DEFAULT_PRIORITY * 1.1, // Slightly higher priority
                        rootId: (_b = trigger.metadata.rootId) !== null && _b !== void 0 ? _b : trigger.id, parentId: trigger.id,
                        uiContext: { promptText: promptText, promptId: promptId }, provenance: this.name,
                    }
                });
                trigger.status = Status.WAITING;
                trigger.metadata.waitingFor = promptId;
                context.thoughts.update(trigger);
                return [2 /*return*/, TermLogic.Atom("ok:prompt_requested:".concat(promptId))];
            });
        });
    };
    return UserInteractionTool;
}());
var GoalProposalTool = /** @class */ (function () {
    function GoalProposalTool() {
        this.name = "GoalProposalTool";
        this.description = "Suggests new goals based on context and memory.";
    }
    GoalProposalTool.prototype.execute = function (action, context, trigger) {
        return __awaiter(this, void 0, void 0, function () {
            var operation, contextTerm, contextStr, memoryResults, memoryContext, prompt, suggestionText, suggestionThought;
            var _a, _b, _c;
            return __generator(this, function (_d) {
                switch (_d.label) {
                    case 0:
                        operation = ((_a = action.args[0]) === null || _a === void 0 ? void 0 : _a.kind) === 'Atom' ? action.args[0].name : null;
                        if (operation !== 'suggest')
                            return [2 /*return*/, TermLogic.Atom("error:GoalTool_unsupported_operation")];
                        contextTerm = (_b = action.args[1]) !== null && _b !== void 0 ? _b : trigger.content;
                        contextStr = TermLogic.toString(contextTerm);
                        return [4 /*yield*/, context.memory.search("Relevant past goals or outcomes related to: ".concat(contextStr), 3)];
                    case 1:
                        memoryResults = _d.sent();
                        memoryContext = memoryResults.length > 0 ? "\nRelated past activities:\n - ".concat(memoryResults.map(function (r) { return r.content; }).join("\n - ")) : "";
                        prompt = "Based on the current context \"".concat(contextStr, "\"").concat(memoryContext, "\n\nSuggest ONE concise, actionable next goal or task. Output only the suggested goal text.");
                        return [4 /*yield*/, context.llm.generate(prompt)];
                    case 2:
                        suggestionText = _d.sent();
                        if (suggestionText && !suggestionText.startsWith('Error:')) {
                            suggestionThought = {
                                id: generateId(), type: Type.INPUT, content: TermLogic.Atom(suggestionText), belief: new Belief(1, 0), status: Status.PENDING,
                                metadata: {
                                    agentId: WORKER_ID, created: new Date().toISOString(), priority: DEFAULT_PRIORITY,
                                    rootId: (_c = trigger.metadata.rootId) !== null && _c !== void 0 ? _c : trigger.id, parentId: trigger.id,
                                    tags: ['suggested_goal'], provenance: this.name
                                }
                            };
                            context.engine.addThought(suggestionThought);
                            return [2 /*return*/, TermLogic.Atom("ok:suggestion_created:".concat(suggestionThought.id))];
                        }
                        return [2 /*return*/, suggestionText.startsWith('Error:') ? TermLogic.Atom("error:GoalTool_llm_failed:".concat(suggestionText)) : TermLogic.Atom("ok:no_suggestion_generated")];
                }
            });
        });
    };
    return GoalProposalTool;
}());
var CoreTool = /** @class */ (function () {
    function CoreTool() {
        this.name = "CoreTool";
        this.description = "Manages internal FlowMind operations (status, thoughts).";
    }
    CoreTool.prototype.execute = function (action, context, trigger) {
        return __awaiter(this, void 0, void 0, function () {
            var operation, targetIdTerm, newStatusTerm, newStatus, targetThought, typeTerm, contentTerm, rootIdTerm, parentIdTerm, type, newThought, targetIdTerm, targetThought, deleted;
            var _a, _b;
            return __generator(this, function (_c) {
                operation = ((_a = action.args[0]) === null || _a === void 0 ? void 0 : _a.kind) === 'Atom' ? action.args[0].name : null;
                switch (operation) {
                    case 'set_status': {
                        targetIdTerm = action.args[1];
                        newStatusTerm = action.args[2];
                        if ((targetIdTerm === null || targetIdTerm === void 0 ? void 0 : targetIdTerm.kind) !== 'Atom' || (newStatusTerm === null || newStatusTerm === void 0 ? void 0 : newStatusTerm.kind) !== 'Atom')
                            return [2 /*return*/, TermLogic.Atom("error:CoreTool_invalid_set_status_params")];
                        newStatus = newStatusTerm.name.toUpperCase();
                        if (!Object.values(Status).includes(newStatus))
                            return [2 /*return*/, TermLogic.Atom("error:CoreTool_invalid_status_value:".concat(newStatusTerm.name))];
                        targetThought = context.thoughts.findThought(targetIdTerm.name);
                        if (targetThought) {
                            targetThought.status = newStatus;
                            context.thoughts.update(targetThought);
                            return [2 /*return*/, TermLogic.Atom("ok:status_set:".concat(shortId(targetIdTerm.name), "_to_").concat(newStatus))];
                        }
                        return [2 /*return*/, TermLogic.Atom("error:CoreTool_target_not_found:".concat(targetIdTerm.name))];
                    }
                    case 'add_thought': {
                        typeTerm = action.args[1];
                        contentTerm = action.args[2];
                        rootIdTerm = action.args[3];
                        parentIdTerm = action.args[4];
                        if ((typeTerm === null || typeTerm === void 0 ? void 0 : typeTerm.kind) !== 'Atom' || !contentTerm)
                            return [2 /*return*/, TermLogic.Atom("error:CoreTool_invalid_add_thought_params")];
                        type = typeTerm.name.toUpperCase();
                        if (!Object.values(Type).includes(type))
                            return [2 /*return*/, TermLogic.Atom("error:CoreTool_invalid_thought_type:".concat(typeTerm.name))];
                        newThought = {
                            id: generateId(),
                            type: type,
                            content: contentTerm, belief: Belief.DEFAULT, status: Status.PENDING,
                            metadata: {
                                agentId: WORKER_ID, created: new Date().toISOString(), priority: DEFAULT_PRIORITY,
                                rootId: (rootIdTerm === null || rootIdTerm === void 0 ? void 0 : rootIdTerm.kind) === 'Atom' ? rootIdTerm.name : (_b = trigger.metadata.rootId) !== null && _b !== void 0 ? _b : trigger.id,
                                parentId: (parentIdTerm === null || parentIdTerm === void 0 ? void 0 : parentIdTerm.kind) === 'Atom' ? parentIdTerm.name : trigger.id,
                                provenance: "".concat(this.name, " (triggered by ").concat(shortId(trigger.id), ")")
                            }
                        };
                        context.engine.addThought(newThought);
                        return [2 /*return*/, TermLogic.Atom("ok:thought_added:".concat(shortId(newThought.id)))];
                    }
                    case 'delete_thought': {
                        targetIdTerm = action.args[1];
                        if ((targetIdTerm === null || targetIdTerm === void 0 ? void 0 : targetIdTerm.kind) !== 'Atom')
                            return [2 /*return*/, TermLogic.Atom("error:CoreTool_invalid_delete_thought_params")];
                        targetThought = context.thoughts.findThought(targetIdTerm.name);
                        if (targetThought) {
                            deleted = context.thoughts.delete(targetThought.id);
                            return [2 /*return*/, deleted ? TermLogic.Atom("ok:thought_deleted:".concat(shortId(targetThought.id))) : TermLogic.Atom("error:CoreTool_delete_failed:".concat(targetIdTerm.name))];
                        }
                        return [2 /*return*/, TermLogic.Atom("error:CoreTool_thought_not_found:".concat(targetIdTerm.name))];
                    }
                    default: return [2 /*return*/, TermLogic.Atom("error:CoreTool_unsupported_operation:".concat(operation))];
                }
                return [2 /*return*/];
            });
        });
    };
    return CoreTool;
}());
var RuleSynthesisTool = /** @class */ (function () {
    function RuleSynthesisTool() {
        this.name = "RuleSynthesisTool";
        this.description = "Generates a new rule using LLM based on a failed or unmatched thought.";
    }
    RuleSynthesisTool.prototype.execute = function (action, context, trigger) {
        return __awaiter(this, void 0, void 0, function () {
            var operation, targetThoughtIdTerm, targetThought, thoughtStr, failureReason, recentMemories, memoryContext, prompt, response, ruleJson, pattern, action_1, newRule;
            var _a;
            return __generator(this, function (_b) {
                switch (_b.label) {
                    case 0:
                        operation = ((_a = action.args[0]) === null || _a === void 0 ? void 0 : _a.kind) === 'Atom' ? action.args[0].name : null;
                        targetThoughtIdTerm = action.args[1];
                        if (operation !== 'synthesize' || (targetThoughtIdTerm === null || targetThoughtIdTerm === void 0 ? void 0 : targetThoughtIdTerm.kind) !== 'Atom')
                            return [2 /*return*/, TermLogic.Atom("error:RuleSynthesisTool_invalid_params")];
                        targetThought = context.thoughts.get(targetThoughtIdTerm.name);
                        if (!targetThought)
                            return [2 /*return*/, TermLogic.Atom("error:RuleSynthesisTool_thought_not_found:".concat(targetThoughtIdTerm.name))];
                        thoughtStr = TermLogic.toString(targetThought.content);
                        failureReason = targetThought.metadata.error || "No matching rule found";
                        return [4 /*yield*/, context.memory.search("Context related to: ".concat(thoughtStr), 2)];
                    case 1:
                        recentMemories = _b.sent();
                        memoryContext = recentMemories.length > 0 ? "\nRelevant memories:\n".concat(recentMemories.map(function (m) { return "- ".concat(m.content); }).join('\n')) : "";
                        prompt = "\nContext: A thought of type ".concat(targetThought.type, " with content \"").concat(thoughtStr, "\" failed processing or had no matching rule. Reason: \"").concat(failureReason, "\". ").concat(memoryContext, "\n\nTask: Generate a new FlowMind rule (pattern and action as JSON Terms) to handle similar thoughts effectively in the future.\n- The pattern should be specific enough to match similar thoughts but general enough using Variables (e.g., ?VarName) where appropriate.\n- The action should be a Structure Term representing a Tool call (e.g., LLMTool, MemoryTool, CoreTool, UserInteractionTool) with appropriate arguments.\n- Consider the thought type (").concat(targetThought.type, ") and failure reason. Aim for a robust or corrective action.\n- If generating text via LLMTool, make the prompt clear. If using CoreTool, specify thought type/status. If MemoryTool, specify operation.\n\nOutput ONLY the JSON object containing the 'pattern' and 'action' Term objects, like this:\n{\n  \"pattern\": { \"kind\": \"Structure\", \"name\": \"...\", \"args\": [...] },\n  \"action\": { \"kind\": \"Structure\", \"name\": \"ToolName\", \"args\": [...] }\n}\n");
                        return [4 /*yield*/, context.llm.generate(prompt)];
                    case 2:
                        response = _b.sent();
                        try {
                            ruleJson = JSON.parse(response);
                            pattern = TermLogic.fromJson(ruleJson.pattern);
                            action_1 = TermLogic.fromJson(ruleJson.action);
                            if (pattern && action_1) {
                                newRule = {
                                    id: generateId(),
                                    pattern: pattern,
                                    action: action_1,
                                    belief: new Belief(1, 1), // Start neutral
                                    metadata: {
                                        description: "Synthesized for ".concat(targetThought.type, ": ").concat(thoughtStr.substring(0, 30), "..."),
                                        provenance: "synthesized_from_".concat(targetThought.id, "_failure"),
                                        created: new Date().toISOString()
                                    }
                                };
                                context.rules.add(newRule);
                                console.log(chalk_1.default.magenta("Synthesized new rule ".concat(shortId(newRule.id), " for pattern: ").concat(TermLogic.format(pattern))));
                                return [2 /*return*/, TermLogic.Atom("ok:rule_synthesized:".concat(shortId(newRule.id)))];
                            }
                            else
                                throw new Error("Invalid pattern or action in LLM response.");
                        }
                        catch (error) {
                            console.error(chalk_1.default.red("Rule synthesis failed: ".concat(error.message, ". LLM Response:\n").concat(response)));
                            return [2 /*return*/, TermLogic.Atom("error:RuleSynthesisTool_llm_or_parse_failed:".concat(error.message))];
                        }
                        return [2 /*return*/];
                }
            });
        });
    };
    return RuleSynthesisTool;
}());
var Engine = /** @class */ (function () {
    function Engine(thoughts, rules, memory, llm, tools) {
        this.thoughts = thoughts;
        this.rules = rules;
        this.memory = memory;
        this.llm = llm;
        this.tools = tools;
        this.activeIds = new Set();
        this.batchSize = 5;
        this.maxConcurrent = 3;
        this.context = { thoughts: this.thoughts, rules: this.rules, memory: this.memory, llm: this.llm, engine: this, tools: this.tools };
    }
    Engine.prototype.addThought = function (thought) {
        var _a, _b;
        // Ensure metadata defaults are set
        var now = new Date().toISOString();
        thought.metadata = __assign(__assign({ agentId: WORKER_ID, priority: DEFAULT_PRIORITY }, thought.metadata), { created: (_b = (_a = thought.metadata) === null || _a === void 0 ? void 0 : _a.created) !== null && _b !== void 0 ? _b : now });
        // Ensure rootId if not present and no parentId
        if (!thought.metadata.rootId && !thought.metadata.parentId) {
            thought.metadata.rootId = thought.id;
        }
        this.thoughts.add(thought);
    };
    Engine.prototype.sampleThought = function () {
        var _this = this;
        var candidates = this.thoughts.getPending().filter(function (t) { return !_this.activeIds.has(t.id); });
        if (candidates.length === 0)
            return null;
        // Use priority first, then belief score as fallback
        var weights = candidates.map(function (t) { var _a; return ((_a = t.metadata.priority) !== null && _a !== void 0 ? _a : DEFAULT_PRIORITY) + t.belief.score(); }); // Combine priority & belief
        var totalWeight = weights.reduce(function (sum, w) { return sum + w; }, 0);
        if (totalWeight <= 0)
            return candidates[Math.floor(Math.random() * candidates.length)]; // Fallback if all weights zero/negative
        var random = Math.random() * totalWeight;
        for (var i = 0; i < candidates.length; i++) {
            random -= weights[i];
            if (random <= 0)
                return candidates[i];
        }
        return candidates[candidates.length - 1]; // Fallback for floating point issues
    };
    Engine.prototype.findAndSelectRule = function (thought) {
        var matches = this.rules.getAll()
            .map(function (rule) { return ({ rule: rule, bindings: TermLogic.unify(rule.pattern, thought.content) }); })
            .filter(function (m) { return m.bindings !== null; });
        if (matches.length === 0)
            return null;
        if (matches.length === 1)
            return matches[0];
        var weights = matches.map(function (m) { return m.rule.belief.score(); });
        var totalWeight = weights.reduce(function (sum, w) { return sum + w; }, 0);
        if (totalWeight <= 0)
            return matches[Math.floor(Math.random() * matches.length)];
        var random = Math.random() * totalWeight;
        for (var i = 0; i < matches.length; i++) {
            random -= weights[i];
            if (random <= 0)
                return matches[i];
        }
        return matches[matches.length - 1];
    };
    Engine.prototype.executeAction = function (thought, rule, bindings) {
        return __awaiter(this, void 0, void 0, function () {
            var boundAction, tool, success, timeoutPromise, resultTerm, currentThoughtState, isWaiting, isFailed, error_7, finalThoughtState;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        boundAction = TermLogic.substitute(rule.action, bindings);
                        if (boundAction.kind !== 'Structure') {
                            this.handleFailure(thought, rule, "Invalid action term kind: ".concat(boundAction.kind));
                            return [2 /*return*/, false];
                        }
                        tool = this.tools.get(boundAction.name);
                        if (!!tool) return [3 /*break*/, 2];
                        // Dynamic Tool Discovery Attempt
                        console.log(chalk_1.default.yellow("Tool \"".concat(boundAction.name, "\" not found for thought ").concat(shortId(thought.id), ". Attempting dynamic discovery...")));
                        return [4 /*yield*/, this.fallback(thought, "Tool not found: ".concat(boundAction.name))];
                    case 1:
                        _a.sent(); // Trigger fallback which might discover tool
                        return [2 /*return*/, false]; // Let the next cycle retry if tool is discovered or fail permanently
                    case 2:
                        success = false;
                        _a.label = 3;
                    case 3:
                        _a.trys.push([3, 5, , 6]);
                        timeoutPromise = new Promise(function (_, reject) { return setTimeout(function () { return reject(new Error("Timeout executing ".concat(tool.name, " after ").concat(ACTION_TIMEOUT_MS, "ms"))); }, ACTION_TIMEOUT_MS); });
                        return [4 /*yield*/, Promise.race([tool.execute(boundAction, this.context, thought), timeoutPromise])];
                    case 4:
                        resultTerm = _a.sent();
                        currentThoughtState = this.thoughts.get(thought.id);
                        isWaiting = (currentThoughtState === null || currentThoughtState === void 0 ? void 0 : currentThoughtState.status) === Status.WAITING;
                        isFailed = (currentThoughtState === null || currentThoughtState === void 0 ? void 0 : currentThoughtState.status) === Status.FAILED;
                        if ((resultTerm === null || resultTerm === void 0 ? void 0 : resultTerm.kind) === 'Atom' && resultTerm.name.startsWith('error:')) {
                            this.handleFailure(thought, rule, "Tool execution failed: ".concat(resultTerm.name));
                        }
                        else if (!isWaiting && !isFailed) {
                            this.handleSuccess(thought, rule);
                            success = true;
                        }
                        return [3 /*break*/, 6];
                    case 5:
                        error_7 = _a.sent();
                        console.error(chalk_1.default.red("Tool exception ".concat(tool.name, " on ").concat(shortId(thought.id), ":")), error_7);
                        this.handleFailure(thought, rule, "Tool exception: ".concat(error_7.message));
                        return [3 /*break*/, 6];
                    case 6:
                        finalThoughtState = this.thoughts.get(thought.id);
                        if (finalThoughtState && finalThoughtState.status !== Status.WAITING) {
                            rule.belief.update(success);
                            this.rules.update(rule);
                        }
                        return [2 /*return*/, success];
                }
            });
        });
    };
    // Renamed from handleNoRuleMatch, handles both no-rule and specific failures like missing tools
    Engine.prototype.fallback = function (thought, reason) {
        return __awaiter(this, void 0, void 0, function () {
            var synthAction, synthTool, toolName, discoveryAction, discoveryTool, prompt, targetType, action, askUserPrompt, resultText, tool, currentStatus, error_8;
            var _this = this;
            var _a, _b;
            return __generator(this, function (_c) {
                switch (_c.label) {
                    case 0:
                        // Prioritize Rule Synthesis for repeated failures or explicit synthesis requests
                        if (reason.includes("RuleSynthesisTool")) { // If synthesis itself failed
                            this.handleFailure(thought, null, "Rule synthesis failed: ".concat(reason), true); // Final failure
                            return [2 /*return*/];
                        }
                        if (!reason.includes("synthesize_rule")) return [3 /*break*/, 4];
                        synthAction = TermLogic.Structure("RuleSynthesisTool", [TermLogic.Atom("synthesize"), TermLogic.Atom(thought.id)]);
                        synthTool = this.tools.get("RuleSynthesisTool");
                        if (!synthTool) return [3 /*break*/, 2];
                        return [4 /*yield*/, synthTool.execute(synthAction, this.context, thought)];
                    case 1:
                        _c.sent();
                        return [3 /*break*/, 3];
                    case 2:
                        this.handleFailure(thought, null, "RuleSynthesisTool not found", true);
                        _c.label = 3;
                    case 3:
                        // After synthesis attempt, the thought might match the new rule on next cycle, or fail permanently if synthesis fails.
                        // Keep it PENDING for retry unless synthesis explicitly failed.
                        if (((_a = this.thoughts.get(thought.id)) === null || _a === void 0 ? void 0 : _a.status) !== Status.FAILED) {
                            thought.status = Status.PENDING; // Allow retry with potentially new rule
                            this.thoughts.update(thought);
                        }
                        return [2 /*return*/];
                    case 4:
                        if (!reason.startsWith("Tool not found:")) return [3 /*break*/, 8];
                        toolName = reason.substring("Tool not found: ".length).trim();
                        discoveryAction = TermLogic.Structure("ToolDiscoveryTool", [TermLogic.Atom("discover"), TermLogic.Atom(toolName)]);
                        discoveryTool = this.tools.get("ToolDiscoveryTool");
                        if (!discoveryTool) return [3 /*break*/, 6];
                        return [4 /*yield*/, discoveryTool.execute(discoveryAction, this.context, thought)];
                    case 5:
                        _c.sent();
                        // Keep thought PENDING to retry action after discovery attempt
                        thought.status = Status.PENDING;
                        this.thoughts.update(thought);
                        return [3 /*break*/, 7];
                    case 6:
                        this.handleFailure(thought, null, "ToolDiscoveryTool not found to discover ".concat(toolName), true); // Final failure
                        _c.label = 7;
                    case 7: return [2 /*return*/];
                    case 8:
                        prompt = "";
                        targetType = null;
                        action = null;
                        switch (thought.type) {
                            case Type.INPUT:
                                prompt = "Input: \"".concat(TermLogic.toString(thought.content), "\". Define GOAL. Output only goal.");
                                targetType = Type.GOAL;
                                break;
                            case Type.GOAL:
                                prompt = "Goal: \"".concat(TermLogic.toString(thought.content), "\". Outline 1-3 STRATEGY steps. Output steps, one per line.");
                                targetType = Type.STRATEGY;
                                break;
                            case Type.STRATEGY:
                                prompt = "Strategy step \"".concat(TermLogic.toString(thought.content), "\" performed. Summarize likely OUTCOME. Output outcome.");
                                targetType = Type.OUTCOME;
                                break;
                            case Type.OUTCOME:
                                action = TermLogic.Structure("MemoryTool", [TermLogic.Atom("add"), thought.content]);
                                break;
                            default:
                                askUserPrompt = "No rule/fallback for ".concat(shortId(thought.id), " (").concat(thought.type, ": ").concat(TermLogic.toString(thought.content).substring(0, 50), "...). Task?");
                                action = TermLogic.Structure("UserInteractionTool", [TermLogic.Atom("prompt"), TermLogic.Atom(askUserPrompt)]);
                                break;
                        }
                        if (!(prompt && targetType)) return [3 /*break*/, 10];
                        return [4 /*yield*/, this.llm.generate(prompt)];
                    case 9:
                        resultText = _c.sent();
                        if (resultText && !resultText.startsWith('Error:')) {
                            resultText.split('\n').map(function (s) { return s.trim().replace(/^- /, ''); }).filter(function (s) { return s; }).forEach(function (resText) {
                                var _a;
                                _this.addThought({ id: generateId(), type: targetType, content: TermLogic.Atom(resText), belief: Belief.DEFAULT, status: Status.PENDING, metadata: { rootId: (_a = thought.metadata.rootId) !== null && _a !== void 0 ? _a : thought.id, parentId: thought.id, created: new Date().toISOString(), priority: DEFAULT_PRIORITY, provenance: 'llm_fallback' } });
                            });
                            this.handleSuccess(thought, null);
                        }
                        else {
                            this.handleFailure(thought, null, "LLM fallback failed: ".concat(resultText));
                        }
                        return [3 /*break*/, 18];
                    case 10:
                        if (!((action === null || action === void 0 ? void 0 : action.kind) === 'Structure')) return [3 /*break*/, 17];
                        tool = this.tools.get(action.name);
                        if (!tool) return [3 /*break*/, 15];
                        _c.label = 11;
                    case 11:
                        _c.trys.push([11, 13, , 14]);
                        return [4 /*yield*/, tool.execute(action, this.context, thought)];
                    case 12:
                        _c.sent();
                        currentStatus = (_b = this.thoughts.get(thought.id)) === null || _b === void 0 ? void 0 : _b.status;
                        if (currentStatus !== Status.WAITING && currentStatus !== Status.FAILED)
                            this.handleSuccess(thought, null);
                        return [3 /*break*/, 14];
                    case 13:
                        error_8 = _c.sent();
                        this.handleFailure(thought, null, "Fallback tool fail (".concat(action.name, "): ").concat(error_8.message));
                        return [3 /*break*/, 14];
                    case 14: return [3 /*break*/, 16];
                    case 15:
                        this.handleFailure(thought, null, "Fallback tool not found: ".concat(action.name));
                        _c.label = 16;
                    case 16: return [3 /*break*/, 18];
                    case 17:
                        if (thought.status === Status.PENDING) { // Ensure it wasn't already handled (e.g., set to WAITING)
                            this.handleFailure(thought, null, "No rule match and no fallback action for ".concat(thought.type, ". Reason: ").concat(reason));
                        }
                        _c.label = 18;
                    case 18: return [2 /*return*/];
                }
            });
        });
    };
    Engine.prototype.handleFailure = function (thought, rule, errorInfo, forceFail) {
        var _a;
        if (forceFail === void 0) { forceFail = false; }
        var retries = ((_a = thought.metadata.retries) !== null && _a !== void 0 ? _a : 0) + 1;
        var shouldFailPermanently = forceFail || retries >= MAX_RETRIES;
        thought.status = shouldFailPermanently ? Status.FAILED : Status.PENDING; // Keep PENDING for retry unless max retries/forced
        thought.metadata.error = errorInfo.substring(0, 250);
        thought.metadata.retries = retries;
        thought.belief.update(false);
        if (rule)
            thought.metadata.ruleId = rule.id;
        this.thoughts.update(thought);
        console.warn(chalk_1.default.yellow("Thought ".concat(shortId(thought.id), " failed (Attempt ").concat(retries, "): ").concat(errorInfo)));
        // Trigger Rule Synthesis on final failure or if no rule matched initially
        if (shouldFailPermanently || errorInfo.includes("No matching rule found")) {
            // Check if a synthesis rule already triggered this failure to avoid loops
            if (!errorInfo.includes("RuleSynthesisTool failed")) {
                console.log(chalk_1.default.magenta("Triggering rule synthesis for failed thought ".concat(shortId(thought.id), "...")));
                var synthAction = TermLogic.Structure("RuleSynthesisTool", [TermLogic.Atom("synthesize"), TermLogic.Atom(thought.id)]);
                var synthTool = this.tools.get("RuleSynthesisTool");
                if (synthTool) {
                    // Execute synthesis asynchronously, don't wait for it here
                    synthTool.execute(synthAction, this.context, thought).catch(function (err) {
                        console.error(chalk_1.default.red("Error during background rule synthesis trigger: ".concat(err.message)));
                    });
                }
                else {
                    console.error(chalk_1.default.red("RuleSynthesisTool not found. Cannot synthesize rule."));
                }
            }
        }
    };
    Engine.prototype.handleSuccess = function (thought, rule) {
        thought.status = Status.DONE;
        thought.belief.update(true);
        delete thought.metadata.error;
        delete thought.metadata.retries;
        if (rule)
            thought.metadata.ruleId = rule.id;
        this.thoughts.update(thought);
    };
    Engine.prototype._processThought = function (thought) {
        return __awaiter(this, void 0, void 0, function () {
            var success, match, error_9, finalThoughtState;
            var _a;
            return __generator(this, function (_b) {
                switch (_b.label) {
                    case 0:
                        thought.status = Status.ACTIVE;
                        thought.metadata.agentId = WORKER_ID;
                        this.thoughts.update(thought);
                        success = false;
                        _b.label = 1;
                    case 1:
                        _b.trys.push([1, 6, 7, 8]);
                        match = this.findAndSelectRule(thought);
                        if (!match) return [3 /*break*/, 3];
                        return [4 /*yield*/, this.executeAction(thought, match.rule, match.bindings)];
                    case 2:
                        success = _b.sent();
                        return [3 /*break*/, 5];
                    case 3: return [4 /*yield*/, this.fallback(thought, "No matching rule found")];
                    case 4:
                        _b.sent();
                        success = ((_a = this.thoughts.get(thought.id)) === null || _a === void 0 ? void 0 : _a.status) === Status.DONE;
                        _b.label = 5;
                    case 5: return [3 /*break*/, 8];
                    case 6:
                        error_9 = _b.sent();
                        console.error(chalk_1.default.red("Critical error processing ".concat(shortId(thought.id), ":")), error_9);
                        this.handleFailure(thought, null, "Unhandled processing exception: ".concat(error_9.message), true);
                        return [3 /*break*/, 8];
                    case 7:
                        this.activeIds.delete(thought.id);
                        finalThoughtState = this.thoughts.get(thought.id);
                        if ((finalThoughtState === null || finalThoughtState === void 0 ? void 0 : finalThoughtState.status) === Status.ACTIVE) {
                            console.warn(chalk_1.default.yellow("Thought ".concat(shortId(thought.id), " ended ACTIVE. Setting FAILED.")));
                            this.handleFailure(finalThoughtState, null, "Processing ended while ACTIVE.", true);
                        }
                        return [7 /*endfinally*/];
                    case 8: return [2 /*return*/, success];
                }
            });
        });
    };
    Engine.prototype.processSingleThought = function () {
        return __awaiter(this, void 0, void 0, function () {
            var thought;
            return __generator(this, function (_a) {
                thought = this.sampleThought();
                if (!thought || this.activeIds.has(thought.id))
                    return [2 /*return*/, false];
                this.activeIds.add(thought.id);
                return [2 /*return*/, this._processThought(thought)];
            });
        });
    };
    Engine.prototype.processBatch = function () {
        return __awaiter(this, void 0, void 0, function () {
            var promises, thought, results;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        promises = [];
                        while (this.activeIds.size < this.maxConcurrent) {
                            thought = this.sampleThought();
                            if (!thought)
                                break;
                            this.activeIds.add(thought.id);
                            promises.push(this._processThought(thought));
                            if (promises.length >= this.batchSize)
                                break;
                        }
                        if (promises.length === 0)
                            return [2 /*return*/, 0];
                        return [4 /*yield*/, Promise.all(promises)];
                    case 1:
                        results = _a.sent();
                        return [2 /*return*/, results.filter(Boolean).length];
                }
            });
        });
    };
    Engine.prototype.handlePromptResponse = function (promptId, responseText) {
        return __awaiter(this, void 0, void 0, function () {
            var promptThought, waitingThought;
            var _a;
            return __generator(this, function (_b) {
                promptThought = this.thoughts.getAll().find(function (t) { var _a; return t.type === Type.USER_PROMPT && ((_a = t.metadata.uiContext) === null || _a === void 0 ? void 0 : _a.promptId) === promptId; });
                if (!promptThought) {
                    console.error(chalk_1.default.red("Prompt thought for ID ".concat(shortId(promptId), " not found.")));
                    return [2 /*return*/, false];
                }
                waitingThought = this.thoughts.getAll().find(function (t) { return t.metadata.waitingFor === promptId && t.status === Status.WAITING; });
                if (!waitingThought) {
                    console.warn(chalk_1.default.yellow("No thought found waiting for prompt ".concat(shortId(promptId), ".")));
                    promptThought.status = Status.DONE;
                    this.thoughts.update(promptThought);
                    return [2 /*return*/, false];
                }
                this.addThought({
                    id: generateId(), type: Type.INPUT, content: TermLogic.Atom(responseText), belief: new Belief(1, 0), status: Status.PENDING,
                    metadata: {
                        rootId: (_a = waitingThought.metadata.rootId) !== null && _a !== void 0 ? _a : waitingThought.id, parentId: waitingThought.id,
                        created: new Date().toISOString(), priority: DEFAULT_PRIORITY, responseTo: promptId,
                        tags: ['user_response'], provenance: 'user_input'
                    }
                });
                promptThought.status = Status.DONE;
                this.thoughts.update(promptThought);
                waitingThought.status = Status.PENDING;
                delete waitingThought.metadata.waitingFor;
                waitingThought.belief.update(true);
                this.thoughts.update(waitingThought);
                console.log(chalk_1.default.blue("Response for ".concat(shortId(promptId), " received. ").concat(shortId(waitingThought.id), " now PENDING.")));
                return [2 /*return*/, true];
            });
        });
    };
    return Engine;
}());
// --- Persistence ---
function saveState(thoughts, rules, memory) {
    return __awaiter(this, void 0, void 0, function () {
        var state, error_10;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    _a.trys.push([0, 4, , 5]);
                    return [4 /*yield*/, fs.mkdir(DATA_DIR, { recursive: true })];
                case 1:
                    _a.sent();
                    state = { thoughts: thoughts.toJSON(), rules: rules.toJSON() };
                    return [4 /*yield*/, fs.writeFile(STATE_FILE, JSON.stringify(state, null, 2))];
                case 2:
                    _a.sent();
                    return [4 /*yield*/, memory.save()];
                case 3:
                    _a.sent(); // Ensure memory (vectors) are saved too
                    console.log(chalk_1.default.gray("State saved."));
                    return [3 /*break*/, 5];
                case 4:
                    error_10 = _a.sent();
                    console.error(chalk_1.default.red('Error saving state:'), error_10);
                    return [3 /*break*/, 5];
                case 5: return [2 /*return*/];
            }
        });
    });
}
var debouncedSaveState = debounce(saveState, SAVE_DEBOUNCE);
function loadState(thoughts, rules, memory) {
    return __awaiter(this, void 0, void 0, function () {
        var data, state, error_11;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    _a.trys.push([0, 6, , 9]);
                    return [4 /*yield*/, fs.mkdir(DATA_DIR, { recursive: true })];
                case 1:
                    _a.sent();
                    return [4 /*yield*/, memory.initialize()];
                case 2:
                    _a.sent(); // Initialize memory store first
                    if (!fsSync.existsSync(STATE_FILE)) return [3 /*break*/, 4];
                    return [4 /*yield*/, fs.readFile(STATE_FILE, 'utf-8')];
                case 3:
                    data = _a.sent();
                    state = safeJsonParse(data, { thoughts: {}, rules: {} });
                    thoughts.loadJSON(state.thoughts);
                    rules.loadJSON(state.rules);
                    console.log(chalk_1.default.blue("Loaded ".concat(thoughts.count(), " thoughts, ").concat(rules.count(), " rules.")));
                    return [3 /*break*/, 5];
                case 4:
                    console.log(chalk_1.default.yellow("State file ".concat(STATE_FILE, " not found.")));
                    _a.label = 5;
                case 5: return [3 /*break*/, 9];
                case 6:
                    error_11 = _a.sent();
                    console.error(chalk_1.default.red('Error loading state:'), error_11);
                    if (!!memory.isReady) return [3 /*break*/, 8];
                    return [4 /*yield*/, memory.initialize()];
                case 7:
                    _a.sent();
                    _a.label = 8;
                case 8: return [3 /*break*/, 9];
                case 9: return [2 /*return*/];
            }
        });
    });
}
var WebSocketApiServer = /** @class */ (function () {
    function WebSocketApiServer(port, system) {
        var _this = this;
        this.system = system;
        this.commandHandlers = new Map();
        this.clients = new Set();
        this.wss = new ws_1.WebSocketServer({ port: port });
        console.log(chalk_1.default.cyan("WebSocket API server listening on ws://localhost:".concat(port)));
        this.wss.on('connection', function (ws) {
            console.log(chalk_1.default.blue('Client connected'));
            _this.clients.add(ws);
            ws.on('message', function (message) { return _this.handleMessage(ws, message); });
            ws.on('close', function () { console.log(chalk_1.default.yellow('Client disconnected')); _this.clients.delete(ws); });
            ws.on('error', function (error) { console.error(chalk_1.default.red('WebSocket error:'), error); _this.clients.delete(ws); });
            _this.send(ws, { type: 'hello', message: 'Welcome to FlowMind API', workerId: WORKER_ID });
            _this.sendFullState(ws); // Send initial state
        });
        this.system.thoughts.addChangeListener(function () { return _this.broadcast({ type: 'notification', event: 'thoughtsChanged', payload: _this.system.getThoughtsSummary() }); });
        this.system.rules.addChangeListener(function () { return _this.broadcast({ type: 'notification', event: 'rulesChanged', payload: _this.system.getRulesSummary() }); });
        this.system.addSystemEventListener(function (event, payload) { return _this.broadcast({ type: 'notification', event: event, payload: payload }); });
    }
    WebSocketApiServer.prototype.registerCommand = function (name, handler) { this.commandHandlers.set(name, handler); };
    WebSocketApiServer.prototype.send = function (ws, message) { if (ws.readyState === ws_1.WebSocket.OPEN)
        ws.send(JSON.stringify(message)); };
    WebSocketApiServer.prototype.broadcast = function (message) { var msgStr = JSON.stringify(message); this.clients.forEach(function (client) { if (client.readyState === ws_1.WebSocket.OPEN)
        client.send(msgStr); }); };
    WebSocketApiServer.prototype.handleMessage = function (ws, message) {
        return __awaiter(this, void 0, void 0, function () {
            var msg, handler, result, error_12;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        _a.trys.push([0, 4, , 5]);
                        msg = JSON.parse(message.toString());
                        if (!(msg.type === 'command' && msg.command && this.commandHandlers.has(msg.command))) return [3 /*break*/, 2];
                        console.log(chalk_1.default.gray("Received command: ".concat(msg.command, " ").concat(JSON.stringify(msg.args || []))));
                        handler = this.commandHandlers.get(msg.command);
                        return [4 /*yield*/, handler(msg.args || [], msg.requestId, ws)];
                    case 1:
                        result = _a.sent();
                        this.send(ws, { type: 'response', requestId: msg.requestId, payload: result });
                        return [3 /*break*/, 3];
                    case 2: throw new Error("Invalid message format or unknown command: ".concat(msg.type, " / ").concat(msg.command));
                    case 3: return [3 /*break*/, 5];
                    case 4:
                        error_12 = _a.sent();
                        console.error(chalk_1.default.red("Failed to handle message: ".concat(error_12.message)));
                        this.send(ws, { type: 'response', error: error_12.message, requestId: safeJsonParse(message.toString(), {}).requestId });
                        return [3 /*break*/, 5];
                    case 5: return [2 /*return*/];
                }
            });
        });
    };
    WebSocketApiServer.prototype.sendFullState = function (ws) {
        this.send(ws, { type: 'fullState', payload: { thoughts: this.system.thoughts.getAll(), rules: this.system.rules.getAll() } });
    };
    WebSocketApiServer.prototype.shutdown = function () { this.wss.close(); console.log(chalk_1.default.cyan('WebSocket server shut down.')); };
    return WebSocketApiServer;
}());
// --- FlowMind System ---
var FlowMindSystem = /** @class */ (function () {
    function FlowMindSystem() {
        this.thoughts = new ThoughtStore();
        this.rules = new RuleStore();
        this.llm = new LLMService();
        this.memory = new MemoryStore(this.llm.embeddings, VECTOR_STORE_DIR);
        this.tools = new ToolManager();
        this.engine = new Engine(this.thoughts, this.rules, this.memory, this.llm, this.tools);
        this.wsServer = null;
        this.isRunning = false;
        this.workerIntervalId = null;
        this.systemEventListeners = [];
        this.registerCoreTools();
    }
    FlowMindSystem.prototype.registerCoreTools = function () {
        var _this = this;
        [new LLMTool(), new MemoryTool(), new UserInteractionTool(), new GoalProposalTool(), new CoreTool(), new RuleSynthesisTool()].forEach(function (t) { return _this.tools.register(t); });
        // TODO: Add ToolDiscoveryTool when implemented
    };
    FlowMindSystem.prototype.bootstrapRules = function () {
        var _this = this;
        if (this.rules.count() > 0)
            return;
        console.log(chalk_1.default.blue("Bootstrapping default rules..."));
        var now = new Date().toISOString();
        var defaultRules = [
            // Basic progression
            { pattern: TermLogic.Structure("INPUT", [TermLogic.Variable("C")]), action: TermLogic.Structure("LLMTool", [TermLogic.Atom("generate"), TermLogic.Atom("Input: \"?C\". Define GOAL. Output only goal as Atom.")]), metadata: { description: "INPUT -> GOAL (LLM)", created: now, provenance: 'bootstrap' } },
            { pattern: TermLogic.Structure("GOAL", [TermLogic.Variable("C")]), action: TermLogic.Structure("LLMTool", [TermLogic.Atom("generate"), TermLogic.Atom("Goal: \"?C\". Outline 1-3 STRATEGY steps as Atoms, one per line.")]), metadata: { description: "GOAL -> STRATEGY (LLM)", created: now, provenance: 'bootstrap' } },
            { pattern: TermLogic.Structure("STRATEGY", [TermLogic.Variable("C")]), action: TermLogic.Structure("LLMTool", [TermLogic.Atom("generate"), TermLogic.Atom("Strategy \"?C\" performed. Summarize likely OUTCOME as Atom.")]), metadata: { description: "STRATEGY -> OUTCOME (LLM)", created: now, provenance: 'bootstrap' } },
            { pattern: TermLogic.Structure("OUTCOME", [TermLogic.Variable("C")]), action: TermLogic.Structure("MemoryTool", [TermLogic.Atom("add"), TermLogic.Variable("C")]), metadata: { description: "OUTCOME -> Memory Add", created: now, provenance: 'bootstrap' } },
            { pattern: TermLogic.Structure("OUTCOME", [TermLogic.Variable("C")]), action: TermLogic.Structure("GoalProposalTool", [TermLogic.Atom("suggest"), TermLogic.Variable("C")]), metadata: { description: "OUTCOME -> Suggest Goal", created: now, provenance: 'bootstrap' } },
            // Failure handling / Rule Synthesis Bootstrap
            { pattern: TermLogic.Variable("ThoughtContent"), action: TermLogic.Structure("CoreTool", [TermLogic.Atom("synthesize_rule"), TermLogic.Variable("ThoughtId")]), metadata: { description: "Trigger Rule Synthesis on repeated failure (fallback)", created: now, provenance: 'bootstrap_failure_handler', /* Needs context/check in engine? No, handled by Engine failure logic */ } },
            // TODO: Add Tool Discovery Bootstrap Rule when ToolDiscoveryTool exists
            // { pattern: TermLogic.Structure("Action", [TermLogic.Variable("Args")]), action: TermLogic.Structure("ToolDiscoveryTool", [TermLogic.Atom("discover"), TermLogic.Variable("ActionName")]) , metadata: { description: "Trigger Tool Discovery on unknown action (fallback)", created: now, provenance: 'bootstrap_tool_discovery' }},
        ];
        defaultRules.forEach(function (r) { return _this.rules.add(__assign(__assign({ id: generateId() }, r), { belief: Belief.DEFAULT })); });
        console.log(chalk_1.default.green("".concat(this.rules.count(), " default rules added.")));
    };
    FlowMindSystem.prototype.addSystemEventListener = function (listener) { this.systemEventListeners.push(listener); };
    FlowMindSystem.prototype.notifySystemEvent = function (event, payload) { this.systemEventListeners.forEach(function (l) { return l(event, payload); }); };
    FlowMindSystem.prototype.initialize = function () {
        return __awaiter(this, void 0, void 0, function () {
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        console.log(chalk_1.default.cyan('Initializing FlowMind System...'));
                        return [4 /*yield*/, loadState(this.thoughts, this.rules, this.memory)];
                    case 1:
                        _a.sent();
                        this.bootstrapRules();
                        this.wsServer = new WebSocketApiServer(WS_PORT, this);
                        this.registerApiCommands();
                        this.startProcessingLoop(); // Start processing by default now
                        console.log(chalk_1.default.green('FlowMind Initialized and Running.'));
                        return [2 /*return*/];
                }
            });
        });
    };
    FlowMindSystem.prototype.registerApiCommands = function () {
        var _this = this;
        if (!this.wsServer)
            return;
        this.wsServer.registerCommand('add', function (args) { return __awaiter(_this, void 0, void 0, function () {
            var contentText, newThought;
            return __generator(this, function (_a) {
                contentText = args[0];
                if (typeof contentText !== 'string' || !contentText)
                    throw new Error("Usage: add <note text>");
                newThought = {
                    id: generateId(), type: Type.INPUT, content: TermLogic.Atom(contentText), belief: new Belief(1, 0), status: Status.PENDING,
                    metadata: { agentId: 'user_api', created: new Date().toISOString(), priority: DEFAULT_PRIORITY, tags: ['user_added'], provenance: 'ws_api' }
                };
                newThought.metadata.rootId = newThought.id;
                this.engine.addThought(newThought);
                debouncedSaveState(this.thoughts, this.rules, this.memory);
                return [2 /*return*/, { status: 'ok', thoughtId: newThought.id }];
            });
        }); });
        this.wsServer.registerCommand('respond', function (args) { return __awaiter(_this, void 0, void 0, function () {
            var promptIdPrefix, responseText, fullPromptId, success;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        promptIdPrefix = args[0];
                        responseText = args[1];
                        if (typeof promptIdPrefix !== 'string' || !promptIdPrefix || typeof responseText !== 'string' || !responseText)
                            throw new Error("Usage: respond <prompt_id_prefix> <response text>");
                        fullPromptId = this.thoughts.getAll().map(function (t) { var _a, _b; return (_b = (_a = t.metadata) === null || _a === void 0 ? void 0 : _a.uiContext) === null || _b === void 0 ? void 0 : _b.promptId; }).find(function (pid) { return pid && pid.startsWith(promptIdPrefix); });
                        if (!fullPromptId)
                            throw new Error("Prompt prefix \"".concat(promptIdPrefix, "\" not found."));
                        return [4 /*yield*/, this.engine.handlePromptResponse(fullPromptId, responseText)];
                    case 1:
                        success = _a.sent();
                        if (success)
                            debouncedSaveState(this.thoughts, this.rules, this.memory);
                        return [2 /*return*/, { status: success ? 'ok' : 'error', message: success ? 'Response processed' : 'Failed to find waiting thought' }];
                }
            });
        }); });
        this.wsServer.registerCommand('run', function () { return __awaiter(_this, void 0, void 0, function () { return __generator(this, function (_a) {
            if (!this.isRunning)
                this.startProcessingLoop();
            return [2 /*return*/, { status: 'ok', running: this.isRunning }];
        }); }); });
        this.wsServer.registerCommand('pause', function () { return __awaiter(_this, void 0, void 0, function () { return __generator(this, function (_a) {
            if (this.isRunning)
                this.pauseProcessingLoop();
            return [2 /*return*/, { status: 'ok', running: this.isRunning }];
        }); }); });
        this.wsServer.registerCommand('step', function () { return __awaiter(_this, void 0, void 0, function () {
            var processed;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        if (!!this.isRunning) return [3 /*break*/, 2];
                        return [4 /*yield*/, this.engine.processSingleThought()];
                    case 1:
                        processed = _a.sent();
                        if (processed)
                            debouncedSaveState(this.thoughts, this.rules, this.memory);
                        return [2 /*return*/, { status: 'ok', processed: processed }];
                    case 2: throw new Error("System must be paused to step.");
                }
            });
        }); });
        this.wsServer.registerCommand('save', function () { return __awaiter(_this, void 0, void 0, function () { return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    debouncedSaveState.cancel();
                    return [4 /*yield*/, saveState(this.thoughts, this.rules, this.memory)];
                case 1:
                    _a.sent();
                    return [2 /*return*/, { status: 'ok', message: 'State saved.' }];
            }
        }); }); });
        this.wsServer.registerCommand('info', function (args) { return __awaiter(_this, void 0, void 0, function () {
            var idPrefix, thought, rule;
            return __generator(this, function (_a) {
                idPrefix = args[0];
                if (typeof idPrefix !== 'string' || !idPrefix)
                    throw new Error("Usage: info <id_prefix>");
                thought = this.thoughts.findThought(idPrefix);
                rule = this.rules.findRule(idPrefix);
                if (thought)
                    return [2 /*return*/, { type: 'thought', data: thought }];
                if (rule)
                    return [2 /*return*/, { type: 'rule', data: rule }];
                throw new Error("ID prefix \"".concat(idPrefix, "\" not found."));
            });
        }); });
        this.wsServer.registerCommand('getThoughts', function () { return __awaiter(_this, void 0, void 0, function () { return __generator(this, function (_a) {
            return [2 /*return*/, this.thoughts.getAll()];
        }); }); });
        this.wsServer.registerCommand('getRules', function () { return __awaiter(_this, void 0, void 0, function () { return __generator(this, function (_a) {
            return [2 /*return*/, this.rules.getAll()];
        }); }); });
        this.wsServer.registerCommand('getTools', function () { return __awaiter(_this, void 0, void 0, function () { return __generator(this, function (_a) {
            return [2 /*return*/, this.tools.list()];
        }); }); });
        this.wsServer.registerCommand('getMemory', function (args) { return __awaiter(_this, void 0, void 0, function () {
            var query, k;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        query = args[0];
                        k = parseInt(args[1] || '5', 10);
                        if (typeof query !== 'string' || !query)
                            throw new Error("Usage: getMemory <query> [k]");
                        return [4 /*yield*/, this.memory.search(query, k)];
                    case 1: return [2 /*return*/, _a.sent()];
                }
            });
        }); });
        this.wsServer.registerCommand('getStatus', function () { return __awaiter(_this, void 0, void 0, function () { return __generator(this, function (_a) {
            return [2 /*return*/, this.getStatusSummary()];
        }); }); });
        this.wsServer.registerCommand('tag', function (args) { return __awaiter(_this, void 0, void 0, function () { return __generator(this, function (_a) {
            return [2 /*return*/, this.tagThought(args[0], args[1], true)];
        }); }); });
        this.wsServer.registerCommand('untag', function (args) { return __awaiter(_this, void 0, void 0, function () { return __generator(this, function (_a) {
            return [2 /*return*/, this.tagThought(args[0], args[1], false)];
        }); }); });
        this.wsServer.registerCommand('delete', function (args) { return __awaiter(_this, void 0, void 0, function () {
            var idPrefix, thought;
            return __generator(this, function (_a) {
                idPrefix = args[0];
                if (typeof idPrefix !== 'string' || !idPrefix)
                    throw new Error("Usage: delete <thought_id_prefix>");
                thought = this.thoughts.findThought(idPrefix);
                if (!thought)
                    throw new Error("Thought \"".concat(idPrefix, "\" not found."));
                if (this.thoughts.delete(thought.id)) {
                    debouncedSaveState(this.thoughts, this.rules, this.memory);
                    return [2 /*return*/, { status: 'ok', message: "Thought ".concat(shortId(thought.id), " deleted.") }];
                }
                else
                    throw new Error("Failed delete ".concat(shortId(thought.id), "."));
                return [2 /*return*/];
            });
        }); });
    };
    FlowMindSystem.prototype.tagThought = function (idPrefix, tag, isTagging) {
        if (typeof idPrefix !== 'string' || !idPrefix || typeof tag !== 'string' || !tag)
            throw new Error("Usage: ".concat(isTagging ? 'tag' : 'untag', " <thought_id_prefix> <tag_name>"));
        var thought = this.thoughts.findThought(idPrefix);
        if (!thought)
            throw new Error("Thought \"".concat(idPrefix, "\" not found."));
        thought.metadata.tags = thought.metadata.tags || [];
        var hasTag = thought.metadata.tags.includes(tag);
        if (isTagging && !hasTag)
            thought.metadata.tags.push(tag);
        else if (!isTagging && hasTag)
            thought.metadata.tags = thought.metadata.tags.filter(function (t) { return t !== tag; });
        else
            throw new Error("Thought ".concat(shortId(thought.id), " ").concat(isTagging ? 'already has' : 'does not have', " tag #").concat(tag, "."));
        this.thoughts.update(thought);
        debouncedSaveState(this.thoughts, this.rules, this.memory);
        return { status: 'ok', message: "".concat(isTagging ? 'Tagged' : 'Untagged', " ").concat(shortId(thought.id), " ").concat(isTagging ? 'with' : 'from', " #").concat(tag, ".") };
    };
    FlowMindSystem.prototype.getThoughtsSummary = function () { return { count: this.thoughts.count(), statuses: this.getStatusCounts() }; };
    FlowMindSystem.prototype.getRulesSummary = function () { return { count: this.rules.count() }; };
    FlowMindSystem.prototype.getStatusCounts = function () { var counts = Object.values(Status).reduce(function (acc, s) {
        var _a;
        return (__assign(__assign({}, acc), (_a = {}, _a[s] = 0, _a)));
    }, {}); this.thoughts.getAll().forEach(function (t) { return counts[t.status]++; }); return counts; };
    FlowMindSystem.prototype.getStatusSummary = function () { return { running: this.isRunning, thoughts: this.getThoughtsSummary(), rules: this.getRulesSummary() }; };
    FlowMindSystem.prototype.startProcessingLoop = function () {
        var _this = this;
        if (this.workerIntervalId)
            return;
        console.log(chalk_1.default.green('Starting processing loop...'));
        this.isRunning = true;
        this.notifySystemEvent('statusChanged', { running: this.isRunning });
        this.workerIntervalId = setInterval(function () { return __awaiter(_this, void 0, void 0, function () {
            var error_13;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        if (!this.isRunning)
                            return [2 /*return*/];
                        _a.label = 1;
                    case 1:
                        _a.trys.push([1, 3, , 4]);
                        return [4 /*yield*/, this.engine.processBatch()];
                    case 2:
                        if ((_a.sent()) > 0)
                            debouncedSaveState(this.thoughts, this.rules, this.memory);
                        return [3 /*break*/, 4];
                    case 3:
                        error_13 = _a.sent();
                        console.error(chalk_1.default.red('Error in processing loop:'), error_13);
                        return [3 /*break*/, 4];
                    case 4: return [2 /*return*/];
                }
            });
        }); }, WORKER_INTERVAL);
    };
    FlowMindSystem.prototype.pauseProcessingLoop = function () {
        if (!this.workerIntervalId)
            return;
        console.log(chalk_1.default.yellow('Pausing processing loop...'));
        this.isRunning = false;
        this.notifySystemEvent('statusChanged', { running: this.isRunning });
        clearInterval(this.workerIntervalId);
        this.workerIntervalId = null;
        debouncedSaveState.cancel();
        saveState(this.thoughts, this.rules, this.memory); // Save immediately on pause
    };
    FlowMindSystem.prototype.shutdown = function () {
        return __awaiter(this, void 0, void 0, function () {
            var _a;
            return __generator(this, function (_b) {
                switch (_b.label) {
                    case 0:
                        console.log(chalk_1.default.cyan('\nShutting down FlowMind System...'));
                        this.pauseProcessingLoop();
                        (_a = this.wsServer) === null || _a === void 0 ? void 0 : _a.shutdown();
                        debouncedSaveState.cancel();
                        return [4 /*yield*/, saveState(this.thoughts, this.rules, this.memory)];
                    case 1:
                        _b.sent();
                        console.log(chalk_1.default.green('FlowMind shutdown complete. Goodbye!'));
                        process.exit(0);
                        return [2 /*return*/];
                }
            });
        });
    };
    return FlowMindSystem;
}());
// --- REPL Client (Example) ---
function startReplClient(url) {
    return __awaiter(this, void 0, void 0, function () {
        var ws, rl, nextRequestId, pendingRequests, sendCommand;
        var _this = this;
        return __generator(this, function (_a) {
            ws = new ws_1.WebSocket(url);
            rl = readline.createInterface({ input: process.stdin, output: process.stdout, prompt: chalk_1.default.blueBright('FlowMind Client> ') });
            nextRequestId = 1;
            pendingRequests = new Map();
            sendCommand = function (command, args) {
                if (args === void 0) { args = []; }
                var requestId = (nextRequestId++).toString();
                var message = JSON.stringify({ type: 'command', command: command, args: args, requestId: requestId });
                return new Promise(function (resolve) {
                    pendingRequests.set(requestId, resolve);
                    ws.send(message);
                    setTimeout(function () {
                        if (pendingRequests.has(requestId)) {
                            pendingRequests.delete(requestId);
                            console.error(chalk_1.default.red("Request ".concat(requestId, " (").concat(command, ") timed out.")));
                            rl.prompt();
                        }
                    }, 10000);
                });
            };
            ws.on('open', function () { console.log(chalk_1.default.green("Connected to FlowMind server at ".concat(url))); rl.prompt(); });
            ws.on('message', function (data) {
                var message = JSON.parse(data.toString());
                if (message.type === 'response' && message.requestId && pendingRequests.has(message.requestId)) {
                    var handler = pendingRequests.get(message.requestId);
                    pendingRequests.delete(message.requestId);
                    if (message.error)
                        console.error(chalk_1.default.red("Error response [".concat(message.requestId, "]: ").concat(message.error)));
                    else
                        console.log(chalk_1.default.green("Response [".concat(message.requestId, "]:")), message.payload !== undefined ? message.payload : '(No payload)');
                }
                else if (message.type === 'notification') {
                    console.log(chalk_1.default.magenta("\nNotification [".concat(message.event, "]:")), message.payload);
                }
                else if (message.type === 'hello') {
                    console.log(chalk_1.default.blue("Server says: ".concat(message.message, " (Worker: ").concat(message.workerId, ")")));
                }
                else if (message.type === 'fullState') {
                    console.log(chalk_1.default.blue("Received initial state: ".concat(message.payload.thoughts.length, " thoughts, ").concat(message.payload.rules.length, " rules.")));
                }
                else {
                    console.log(chalk_1.default.yellow('Received unknown message:'), message);
                }
                rl.prompt(true); // Refresh prompt without clearing input line
            });
            ws.on('close', function () { console.log(chalk_1.default.red('Disconnected from server. Exiting.')); process.exit(0); });
            ws.on('error', function (error) { console.error(chalk_1.default.red('Connection error:'), error.message); process.exit(1); });
            rl.on('line', function (line) { return __awaiter(_this, void 0, void 0, function () {
                var trimmedLine, _a, command, args, error_14;
                return __generator(this, function (_b) {
                    switch (_b.label) {
                        case 0:
                            trimmedLine = line.trim();
                            if (!trimmedLine) return [3 /*break*/, 4];
                            _a = trimmedLine.split(' '), command = _a[0], args = _a.slice(1);
                            _b.label = 1;
                        case 1:
                            _b.trys.push([1, 3, , 4]);
                            return [4 /*yield*/, sendCommand(command.toLowerCase(), args)];
                        case 2:
                            _b.sent();
                            return [3 /*break*/, 4];
                        case 3:
                            error_14 = _b.sent();
                            console.error(chalk_1.default.red("Client error: ".concat(error_14.message)));
                            return [3 /*break*/, 4];
                        case 4:
                            rl.prompt();
                            return [2 /*return*/];
                    }
                });
            }); });
            rl.on('close', function () { ws.close(); console.log(chalk_1.default.yellow('Exiting client.')); });
            return [2 /*return*/];
        });
    });
}
// --- Main Execution ---
function main() {
    return __awaiter(this, void 0, void 0, function () {
        var args, url, system_1, handleShutdown_1, error_15;
        var _this = this;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    args = process.argv.slice(2);
                    if (!args.includes('--client')) return [3 /*break*/, 2];
                    url = args[args.indexOf('--client') + 1] || "ws://localhost:".concat(WS_PORT);
                    return [4 /*yield*/, startReplClient(url)];
                case 1:
                    _a.sent();
                    return [3 /*break*/, 6];
                case 2:
                    system_1 = new FlowMindSystem();
                    handleShutdown_1 = function (signal) { return __awaiter(_this, void 0, void 0, function () { return __generator(this, function (_a) {
                        switch (_a.label) {
                            case 0:
                                console.log(chalk_1.default.yellow("\n".concat(signal, " received. Shutting down...")));
                                return [4 /*yield*/, system_1.shutdown()];
                            case 1:
                                _a.sent();
                                return [2 /*return*/];
                        }
                    }); }); };
                    process.on('SIGINT', function () { return handleShutdown_1('SIGINT'); });
                    process.on('SIGTERM', function () { return handleShutdown_1('SIGTERM'); });
                    process.on('uncaughtException', function (error) { return __awaiter(_this, void 0, void 0, function () { var _a; return __generator(this, function (_b) {
                        switch (_b.label) {
                            case 0:
                                console.error(chalk_1.default.red.bold('\n--- UNCAUGHT EXCEPTION ---\n'), error);
                                _b.label = 1;
                            case 1:
                                _b.trys.push([1, 3, , 4]);
                                return [4 /*yield*/, system_1.shutdown()];
                            case 2:
                                _b.sent();
                                return [3 /*break*/, 4];
                            case 3:
                                _a = _b.sent();
                                process.exit(1);
                                return [3 /*break*/, 4];
                            case 4: return [2 /*return*/];
                        }
                    }); }); });
                    process.on('unhandledRejection', function (reason) { return __awaiter(_this, void 0, void 0, function () { var _a; return __generator(this, function (_b) {
                        switch (_b.label) {
                            case 0:
                                console.error(chalk_1.default.red.bold('\n--- UNHANDLED REJECTION ---\nReason:'), reason);
                                _b.label = 1;
                            case 1:
                                _b.trys.push([1, 3, , 4]);
                                return [4 /*yield*/, system_1.shutdown()];
                            case 2:
                                _b.sent();
                                return [3 /*break*/, 4];
                            case 3:
                                _a = _b.sent();
                                process.exit(1);
                                return [3 /*break*/, 4];
                            case 4: return [2 /*return*/];
                        }
                    }); }); });
                    _a.label = 3;
                case 3:
                    _a.trys.push([3, 5, , 6]);
                    return [4 /*yield*/, system_1.initialize()];
                case 4:
                    _a.sent();
                    return [3 /*break*/, 6];
                case 5:
                    error_15 = _a.sent();
                    console.error(chalk_1.default.red.bold("Critical init error:"), error_15);
                    process.exit(1);
                    return [3 /*break*/, 6];
                case 6: return [2 /*return*/];
            }
        });
    });
}
main();
