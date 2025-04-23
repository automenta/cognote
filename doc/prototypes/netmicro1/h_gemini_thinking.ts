# Hybrid Synthesis of Two Versions - Revised and Completed

====

import express from "express";
import { EventEmitter } from "node:events";
import { WebSocketServer, WebSocket } from "ws";
import pLimit from "p-limit";
import { ChatGoogleGenerativeAI } from "@langchain/google-genai";
import * as THREE from "three";
import { CSS3DRenderer, CSS3DObject } from "three/addons/renderers/CSS3DRenderer.js";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";
import * as z from "zod";
import { Logger } from "./src/logger.js";
import { defineTool, Tools, Tool } from "./src/tools.js";
import { Graph } from "./src/graph.js";
import { ErrorHandler } from "./src/error_handler.js";
import { ExecutionQueue } from "./src/execution_queue_manager.js";
import TWEEN from "https://cdnjs.cloudflare.com/ajax/libs/tween.js/25.0.0/tween.esm.js";
import fs from 'node:fs/promises';
import path from 'path';

// --- Types ---
type Vector3 = { x: number; y: number; z: number };
interface Note extends Omit<Node, 'actions'> {
    content: any;
    graph: { target: string; rel: string }[];
    state: { status: string; priority: number; entropy: number };
    memory: any[];
    tools: Record<string, string>;
    context: string[];
    ts: string;
    resources: { tokens: number; cycles: number };
    logic: Logic;
}
type Logic = { type: "sequential" | "conditional" | "loop" | "fork" | "eval" | "modify" | "graph" | "plan" | "reason" | "ui" | "llm"; steps?: LogicStep[]; condition?: string; body?: Logic; count?: number; target?: string; code?: string; operation?: string; query?: string; goal?: string; widget?: string; prompt?: string };
type LogicStep = { tool: string; input: any };

interface Node {
    id: string;
    value: any;
    links: [string, number][];
    history: [string, any][];
    priority: number;
    resources: [number, number];
    timestamp: string;
    status: "pending" | "running" | "completed" | "failed";
    memory?: { type: string; content: string; timestamp: string }[];
}

interface Widget {
    name: string;
    render: (note: Note, scene: THREE.Scene) => THREE.Object3D;
}

interface Memory {
    store(note: Note): Promise<void>;
    fetch(id: string): Promise<Note>;
    list(): Promise<Note[]>;
}

const logger = new Logger(true);
const errorHandler = new ErrorHandler({ logger });

class InMemory implements Memory {
    private store = new Map<string, Note>();
    async store(node: Note) { this.store.set(node.id, node); }
    async fetch(id: string) { const n = this.store.get(id); if (!n) throw new Error(`Note ${id} not found`); return n; }
    async list() { return [...this.store.values()]; }
}

const tools = new Tools();
tools.addTool(defineTool({
    name: "add", description: "Add a new node", schema: z.object({ type: z.string().optional(), data: z.any().optional() }),
    apply: async (node, system, input) => {
        const id = `${input.type || "note"}-${Date.now()}`;
        const newNode: Note = { id, content: input.data || {}, graph: [[node.id, "child"]], memory: [{ type: "system", content: `Created by ${node.id}`, timestamp: new Date().toISOString() }],
            state: { status: "pending", priority: 0.5, entropy: 0 }, tools: {}, context: [node.id], ts: new Date().toISOString(), resources: { tokens: 1000, cycles: 1000 }, logic: { type: "sequential", steps: [] } };
        await system.memory.store(newNode); system.graph.addNote(newNode); return node;
    }
}));
tools.addTool(defineTool({
    name: "llm", description: "Invoke LLM for reasoning", schema: z.object({ prompt: z.string() }),
    apply: async (node, system, input) => {
        const llm = new ChatGoogleGenerativeAI({ model: "gemini-2.0-flash", apiKey: process.env.GOOGLE_API_KEY });
        const response = await llm.invoke(input.prompt);
        return { ...node, history: [...node.history, ["llm", response]], resources: [node.resources[0] - 10, node.resources[1]] };
    }
}));
tools.addTool(defineTool({
    name: "generateTool", description: "Generate a new tool at runtime", schema: z.object({ name: z.string(), desc: z.string(), code: z.string() }),
    apply: async (node, system, input) => {
        const toolDef = defineTool({ name: input.name, description: input.desc, schema: z.object({}), apply: async (n, s, i) => new Function("node", "system", "input", input.code)(n, s, i) });
        system.tools.addTool(toolDef); return { ...node, history: [...node.history, ["generateTool", input.name]] };
    }
}));
tools.addTool(defineTool({
    name: "reflect", description: "Reflect on a node and optimize", schema: z.object({ targetId: z.string() }),
    apply: async (node, system, input) => {
        const target = await system.memory.fetch(input.targetId);
        const llm = new ChatGoogleGenerativeAI({ model: "gemini-2.0-flash", apiKey: process.env.GOOGLE_API_KEY });
        const summary = await llm.invoke([`Optimize this note: ${JSON.stringify(target)}`]);
        const updates = await system.memory.fetch(input.targetId); updates.content.optimized = summary.text; await system.memory.store(updates);
        return { ...node, history: [...node.history, ["reflect", input.targetId]] };
    }
}));

const widgets: Record<string, Widget> = {
    sphere: { name: "sphere", render: (node, scene) => { const mesh = new THREE.Mesh(new THREE.SphereGeometry(node.content.size || 5), new THREE.MeshBasicMaterial({ color: node.state.status === "completed" ? 0x00ff00 : 0xff0000 })); mesh.position.set(Math.random() * 100 - 50, Math.random() * 100 - 50, Math.random() * 100 - 50); const overlay = new CSS3DObject(Object.assign(document.createElement("div"), { className: "node-overlay", innerHTML: `<h3>${node.content.desc || node.id}</h3><p>Status: ${node.state.status}</p>` })); overlay.position.copy(mesh.position); scene.add(mesh, overlay); return mesh; } }
};

export class System {
    memory: Memory; tools: Tools; widgets: Map<string, Widget>; events: EventEmitter; server: WebSocketServer; graph: Graph; queue: ExecutionQueue; limit = pLimit(5); state = { paused: false, tick: 0 }; ui: UI;

    constructor(memory: Memory = new InMemory(), port = 8000) {
        this.memory = memory; this.tools = tools; this.widgets = new Map(Object.entries(widgets)); this.events = new EventEmitter(); this.server = new WebSocketServer({ port }); this.graph = new Graph(); this.queue = new ExecutionQueue({ logger, graph: this.graph }); this.ui = new UI();
    }

    async start() {
        this.server.on("connection", (client) => this.broadcast(client)); this.events.on("update", (id: string) => this.limit(() => this.process(id).catch(e => errorHandler.handleNoteError({ id }, e)))); this.run(); await this.seed(); await this.loadTools('./src/tools');
    }

    async loadTools(toolsDir: string) {
        logger.info(`Loading tools from ${toolsDir}...`, { component: "System" });
        try {
            const files = await fs.readdir(toolsDir);
            for (const file of files) {
                if (file.endsWith('.js')) {
                    const toolPath = path.join(toolsDir, file);
                    try {
                        const toolModule = await import(toolPath);
                        if (toolModule.default && typeof toolModule.default.name === 'string' && typeof toolModule.default.invoke === 'function') {
                            this.tools.addTool(toolModule.default);
                            logger.debug(`Loaded tool: ${toolModule.default.name} from ${file}`, { component: "System/ToolLoader" });
                        } else {
                            logger.warn(`Invalid tool module format in ${file}: missing default export or name/invoke properties.`, { component: "System/ToolLoader", file: file });
                        }
                    } catch (importError: any) {
                        logger.error(`Error importing tool from ${file}: ${importError.message}`, { component: "System/ToolLoader", file: file, error: importError });
                    }
                }
            }
            logger.info(`Loaded ${this.tools.getTools().length} tools.`, { component: "System", count: this.tools.getTools().length });
        } catch (readDirError: any) {
            logger.error(`Error reading tools directory ${toolsDir}: ${readDirError.message}`, { component: "System/ToolLoader", directory: toolsDir, error: readDirError });
        }
    }

    async process(id: string): Promise<Note> {
        if (this.state.paused) return this.memory.fetch(id); const note = await this.memory.fetch(id).catch(() => this.create(id)); if (note.resources.tokens <= 0 || note.resources.cycles <= 0) return note;
        let updatedNote: Note = { ...note, state: { ...note.state, status: "running" } }; await this.memory.store(updatedNote);
        try {
            logger.debug(`Executing logic for ${id}`, { component: "System/process" }); await this.executeLogic(updatedNote); updatedNote = await this.memory.fetch(id); updatedNote.state.priority = this.computePriority(updatedNote); updatedNote.ts = new Date().toISOString(); updatedNote.resources.cycles--; await this.memory.store(updatedNote); this.queue.queueExecution(updatedNote); this.events.emit("update", updatedNote.id);
        } catch (error) {
            updatedNote.state.status = "failed"; updatedNote.memory.push({ type: "error", content: error.message, timestamp: new Date().toISOString() }); await this.memory.store(updatedNote); if (errorHandler.shouldRetry(error)) { updatedNote.state.status = "pending"; this.queue.queueExecution(updatedNote); logger.info(`Retrying ${id} due to ${error.message}`, { component: "System/process" }); } throw error;
        }
        updatedNote.state.status = "completed"; await this.memory.store(updatedNote); this.state.tick++; return updatedNote;
    }

    async executeLogic(note: Note): Promise<void> {
        const logic = note.logic; if (!logic) return;
        const ctx = { note, memory: this.memory, ui: this.ui, tools: this.tools, system: this, logger: logger };
        try {
            switch (logic.type) {
                case "sequential": if (logic.steps) for (const step of logic.steps) { const toolInstance = this.tools.getTool(step.tool); if (!toolInstance) { logger.error(`Tool ${step.tool} not found`, { component: "System/executeLogic", toolName: step.tool, noteId: note.id }); note.state.status = "failed"; await this.memory.store(note); return; } try { logger.debug(`Executing tool ${step.tool} for note ${note.id}`, { component: "System/executeLogic", toolName: step.tool, stepInput: step.input, noteId: note.id }); const result = await toolInstance.apply(note, this, step.input ?? {}); note.state.status = result?.state?.status ?? "done"; note.memory.push({ type: "tool_call", content: { tool: step.tool, input: step.input, result: result }, timestamp: new Date().toISOString() }); await this.memory.store(note); if (note.state.status === "failed") return; } catch (toolError: any) { logger.error(`Error executing tool ${step.tool} for note ${note.id}: ${toolError.message}`, { component: "System/executeLogic", toolName: step.tool, stepInput: step.input, noteId: note.id, error: toolError }); note.state.status = "failed"; note.memory.push({ type: "tool_error", content: { tool: step.tool, input: step.input, error: toolError.message }, timestamp: new Date().toISOString() }); await this.memory.store(note); return; } } break;
                case "conditional": if (logic.condition) try { const conditionResult = new Function("note", "memory", `return ${logic.condition}`)(note, this.memory); if (conditionResult && logic.body) await this.executeLogic(logic.body); } catch (conditionError: any) { logger.error(`Condition error for note ${note.id}: ${conditionError.message}`, { component: "System/executeLogic", condition: logic.condition, noteId: note.id, error: conditionError }); note.state.status = "failed"; note.memory.push({ type: "logic_error", content: `Condition eval error: ${conditionError.message}`, timestamp: new Date().toISOString() }); await this.memory.store(note); } break;
                case "loop": if (logic.body) for (let i = 0; i < (logic.count ?? 0); i++) await this.executeLogic(logic.body); break;
                case "fork": const forkTool = this.tools.getTool("fork"); if (forkTool) await forkTool.apply(note, this, { target: logic.target ?? note.id }); else logger.error(`Tool 'fork' not found`, { component: "System/executeLogic", logicType: 'fork', noteId: note.id }); break;
                case "eval": const evalTool = this.tools.getTool("eval"); if (evalTool) await evalTool.apply(note, this, { code: logic.code ?? '' }); else logger.error(`Tool 'eval' not found`, { component: "System/executeLogic", logicType: 'eval', noteId: note.id }); break;
                case "modify": const modifyTool = this.tools.getTool("modify"); if (modifyTool && logic.target && logic.code) await modifyTool.apply(note, this, { target: logic.target, key: 'content', value: logic.code }); else logger.error(`Tool 'modify' not found or missing params`, { component: "System/executeLogic", logicType: 'modify', noteId: note.id }); break;
                case "graph": const graphTool = this.tools.getTool("graph"); if (graphTool && logic.operation) await graphTool.apply(note, this, { operation: logic.operation, query: logic.query, target: logic.target }); else logger.error(`Tool 'graph' not found or missing operation`, { component: "System/executeLogic", logicType: 'graph', noteId: note.id }); break;
                case "plan": const planTool = this.tools.getTool("plan"); if (planTool && logic.goal) await planTool.apply(note, this, { goal: logic.goal }); else logger.error(`Tool 'plan' not found or missing goal`, { component: "System/executeLogic", logicType: 'plan', noteId: note.id }); break;
                case "reason": const reasonTool = this.tools.getTool("reason"); if (reasonTool && logic.premise && logic.conclusion) await reasonTool.apply(note, this, { premise: logic.premise, conclusion: logic.conclusion }); else logger.error(`Tool 'reason' not found or missing params`, { component: "System/executeLogic", logicType: 'reason', noteId: note.id }); break;
                case "ui": if (logic.widget) this.ui.updateWidget(note.id, { widget: logic.widget, color: logic.code ? new Function("note", `return ${logic.code}`)(note) : undefined }); break;
                case "llm": if (logic.prompt) { const llmTool = this.tools.getTool("llm"); if (llmTool) await llmTool.apply(note, this, { prompt: logic.prompt }); } break;
                default: logger.warn(`Unknown logic type: ${logic.type}`, { component: "System/executeLogic", logicType: logic.type, noteId: note.id });
            }
        } catch (topLevelError: any) {
            logger.error(`Error in executeLogic for note ${note.id}: ${topLevelError.message}`, { component: "System/executeLogic", noteId: note.id, error: topLevelError, logicType: logic.type });
            note.state.status = "failed"; note.memory.push({ type: "logic_runtime_error", content: `Runtime error in logic type ${logic.type}: ${topLevelError.message}`, timestamp: new Date().toISOString() }); await this.memory.store(note);
        }
    }


    run() { setInterval(() => { this.queue.optimizeSchedule(); const notes = this.queue.executionQueue.values(); for (const id of notes) { this.process(id).catch(e => logger.error(`Run error: ${e.message}`, { id })); this.queue.executionQueue.delete(id); } }, 50); }
    computePriority(note: Note): number { const deadlineFactor = note.content.deadline ? (new Date(note.content.deadline).getTime() - Date.now()) / (1000 * 60) : 0; const usage = note.memory?.length || 0; return (note.state.priority || 0.5) - (deadlineFactor < 0 ? 100 : deadlineFactor) + usage / 100; }
    broadcast(client: WebSocket) { this.memory.list().then(nodes => client.send(JSON.stringify({ type: "graph", nodes, tick: this.state.tick }))); }
    create(id: string): Note { const node: Note = { id, content: { desc: "New Node" }, graph: [], memory: [{ type: "system", content: "Node created", timestamp: new Date().toISOString() }], state: { status: "pending", priority: 0.5, entropy: 0 }, tools: {}, context: [], ts: new Date().toISOString(), resources: { tokens: 1000, cycles: 1000 }, logic: { type: "sequential", steps: [] } }; this.graph.addNote(node); return node; }
    async seed() { const root: Note = { id: "root", content: { desc: "Netention Ultra Root", widget: "sphere", size: 10 }, graph: [], state: { status: "pending", priority: 1, entropy: 0 }, tools: {}, context: [], ts: new Date().toISOString(), resources: { tokens: 5000, cycles: 5000 }, logic: { type: "sequential", steps: [ { tool: "generateTool", input: { name: "testTool", desc: "Test tool", code: "return {...note, content: {...note.content, test: 'done'}}" } }, { tool: "reflect", input: { targetId: "root" } } ] }, memory: [{ type: "system", content: "Root initialized", timestamp: new Date().toISOString() }] }; await this.memory.store(root); this.graph.addNote(root); this.events.emit("update", root.id); }
}

class Dashboard {
    scene = new THREE.Scene(); camera = new THREE.PerspectiveCamera(75, window.innerWidth / window.innerHeight, 0.1, 1000); renderer = new THREE.WebGLRenderer({ canvas: document.createElement("canvas"), antialias: true }); cssRenderer = new CSS3DRenderer(); controls: OrbitControls; widgets = new Map(Object.entries(widgets)); nodes = new Map<string, THREE.Object3D>(); ws: WebSocket; hud: HTMLDivElement;

    constructor(port = 8000) { this.setup(); this.ws = new WebSocket(`ws://localhost:${port}`); this.ws.onmessage = (e) => this.update(JSON.parse(e.data)); }
    setup() { document.body.style.cssText = "margin: 0; overflow: hidden; background: #1e1e1e;"; const container = Object.assign(document.createElement("div"), { id: "container", style: "position: relative;" }); document.body.appendChild(container); this.renderer.domElement.id = "canvas"; this.renderer.setSize(window.innerWidth, window.innerHeight); container.appendChild(this.renderer.domElement); this.cssRenderer.domElement.style.cssText = "position: absolute; top: 0;"; this.cssRenderer.setSize(window.innerWidth, window.innerHeight); container.appendChild(this.cssRenderer.domElement); this.camera.position.set(0, 50, 100); this.controls = new OrbitControls(this.camera, this.cssRenderer.domElement); this.controls.enableDamping = true; this.hud = Object.assign(document.createElement("div"), { style: "position: absolute; top: 10px; left: 10px; color: #d4d4d4; font-family: monospace;", innerHTML: `<div id="status">Nodes: 0 | Tick: 0</div>` }); container.appendChild(this.hud); const input = Object.assign(document.createElement("input"), { id: "prompt", style: "background: #2d2d2d; color: #d4d4d4; border: 1px solid #444; padding: 5px; margin-top: 10px;", placeholder: "Enter command...", onkeypress: (e: KeyboardEvent) => e.key === "Enter" && this.handleInput((e.target as HTMLInputElement).value) }); this.hud.appendChild(input); document.head.appendChild(Object.assign(document.createElement("style"), { textContent: `.node-overlay { background: rgba(0, 0, 0, 0.7); padding: 10px; border-radius: 5px; color: #d4d4d4; font-family: monospace; }` })); window.addEventListener("resize", () => { this.camera.aspect = window.innerWidth / window.innerHeight; this.camera.updateProjectionMatrix(); this.renderer.setSize(window.innerWidth, window.innerHeight); this.cssRenderer.setSize(window.innerWidth, window.innerHeight); }); this.animate(); }
    update(data: { type: string; nodes: Note[]; tick: number }) { if (data.type !== "graph") return; data.nodes.forEach((n) => { if (!this.nodes.has(n.id) && n.content.widget) { const widget = this.widgets.get(n.content.widget); if (widget) this.nodes.set(n.id, widget.render(n, this.scene)); } }); this.hud.querySelector("#status")!.textContent = `Nodes: ${this.nodes.size} | Tick: ${data.tick}`; }
    handleInput(cmd: string) { const [action, ...args] = cmd.trim().split(" "); const system = globalThis.system; if (action === "add") { const desc = args.join(" "); system.memory.store({ id: `cmd-${Date.now()}`, content: { tool: "add", input: { data: { desc, widget: "sphere", size: 5 } } }, graph: [["root", "command"]], memory: [{ type: "user", content: `Added via command: ${desc}`, timestamp: new Date().toISOString() }], state: { status: "pending", priority: 0.5, entropy: 0 }, tools: {}, context: ["root"], ts: new Date().toISOString(), resources: { tokens: 100, cycles: 100 }, logic: { type: "sequential", steps: [] } }).then(() => system.events.emit("update", "root")); } (this.hud.querySelector("#prompt") as HTMLInputElement).value = ""; }
    animate() { requestAnimationFrame(() => this.animate()); this.controls.update(); this.renderer.render(this.scene, this.camera); this.cssRenderer.render(this.scene, this.camera); }
}

class UI {
    scene = new THREE.Scene(); camera = new THREE.PerspectiveCamera(75, window.innerWidth / window.innerHeight, 0.1, 1000); renderer = new THREE.WebGLRenderer({ antialias: true }); cssRenderer = new CSS3DRenderer(); controls = new OrbitControls(this.camera, this.cssRenderer.domElement); widgets = new Map<string, { mesh: THREE.Mesh; overlay: CSS3DObject }>(); edges = new Map<string, THREE.Line>(); history: { nodes: any[]; edges: any[]; time: number }[] = []; timeIndex = -1; ws: WebSocket | null = null;

    constructor() { this.renderer.setSize(window.innerWidth, window.innerHeight); this.cssRenderer.setSize(window.innerWidth, window.innerHeight); this.cssRenderer.domElement.style.position = "absolute"; this.cssRenderer.domElement.style.top = "0"; document.getElementById("container")!.append(this.renderer.domElement, this.cssRenderer.domElement); this.camera.position.set(0, 50, 100); this.controls.enableDamping = true; this.controls.dampingFactor = 0.1; this.controls.minDistance = 10; this.controls.maxDistance = 500; window.onresize = () => this.onResize(); this.addTimeline(); this.animate(); }
    connectWS(port = 8000) { this.ws = new WebSocket(`ws://localhost:${port}`); this.ws.onmessage = (e) => this.render(JSON.parse(e.data).data); }
    animate() { requestAnimationFrame(() => this.animate()); this.controls.update(); const repulsion = 100, attraction = 0.05, damping = 0.9, nodes = [...this.widgets.entries()]; for (let i = 0; i < nodes.length; i++) { const [id1, w1] = nodes[i], p1 = w1.mesh.position; for (let j = i + 1; j < nodes.length; j++) { const [, w2] = nodes[j], p2 = w2.mesh.position, dir = p1.clone().sub(p2), dist = Math.max(dir.length(), 0.1), force = repulsion / (dist * dist); dir.normalize().multiplyScalar(force); p1.add(dir); p2.sub(dir); } } for (const [, e] of this.edges) { const s = this.widgets.get(e.userData.source)?.mesh.position, t = this.widgets.get(e.userData.target)?.mesh.position; if (s && t) { const dir = t.clone().sub(s), dist = dir.length(), force = attraction * dist; dir.normalize().multiplyScalar(force); s.add(dir); t.sub(dir); e.geometry.setFromPoints([s, t]); } } for (const [, w] of this.widgets) w.mesh.position.multiplyScalar(damping); this.renderer.render(this.scene, this.camera); this.cssRenderer.render(this.scene, this.camera); TWEEN.update(); }
    render({ nodes, edges }: { nodes: any[]; edges: any[] }) { const nodeMap = new Map(nodes.map(n => [n.id, n])), edgeKeys = new Set(edges.map(e => `${e.source}-${e.target}`)); for (const [id] of this.widgets) if (!nodeMap.has(id)) { this.scene.remove(this.widgets.get(id)!.mesh, this.widgets.get(id)!.overlay); this.widgets.delete(id); } for (const [key] of this.edges) if (!edgeKeys.has(key)) { this.scene.remove(this.edges.get(key)!); this.edges.delete(key); } nodes.forEach(n => { if (!this.widgets.has(n.id)) { const mesh = new THREE.Mesh(new THREE.SphereGeometry(Math.max(5, n.priority / 10)), new THREE.MeshBasicMaterial({ color: n.color ?? 0x00ff00 })); const div = document.createElement("div"); div.className = "overlay"; div.innerHTML = `<h3>${n.label}</h3><p>${n.status} | ${n.priority} | ${n.value ?? 0}</p>`; const overlay = new CSS3DObject(div); mesh.position.set(Math.random() * 100 - 50, Math.random() * 100 - 50, Math.random() * 100 - 50); overlay.position.copy(mesh.position); this.scene.add(mesh, overlay); this.widgets.set(n.id, { mesh, overlay }); } else this.updateWidget(n.id, n); }); edges.forEach(e => { const key = `${e.source}-${e.target}`; if (!this.edges.has(key)) { const s = this.widgets.get(e.source)!.mesh.position, t = this.widgets.get(e.target)!.mesh.position; const line = new THREE.Line(new THREE.BufferGeometry().setFromPoints([s, t]), new THREE.LineBasicMaterial({ color: 0xffffff })); line.userData = { source: e.source, target: e.target }; this.scene.add(line); this.edges.set(key, line); } }); this.history.push({ nodes, edges, time: Date.now() }); this.timeIndex = this.history.length - 1; this.addTimeline(); document.getElementById("status")!.textContent = `Nodes: ${this.widgets.size} | Edges: ${this.edges.size}`; }
    updateWidget(id: string, { color, value, widget }: { color?: number; value?: number; widget?: string }) { let w = this.widgets.get(id); if (!w && widget === 'sphere') { const mesh = new THREE.Mesh(new THREE.SphereGeometry(Math.max(5, 50 / 10)), new THREE.MeshBasicMaterial({ color: color ?? 0x00ff00 })); const div = document.createElement("div"); div.className = "overlay"; div.innerHTML = `<h3>${id}</h3><p>Status: pending | 50 | 0</p>`; const overlay = new CSS3DObject(div); mesh.position.set(Math.random() * 100 - 50, Math.random() * 100 - 50, Math.random() * 100 - 50); overlay.position.copy(mesh.position); this.scene.add(mesh, overlay); w = {mesh, overlay}; this.widgets.set(id, w); } if (!w) return; if (color) (w.mesh.material as THREE.MeshBasicMaterial).color.set(color); if (value !== undefined) w.mesh.scale.setScalar(1 + value / 10); w.overlay.element.innerHTML = `<h3>${w.mesh.userData.label ?? id}</h3><p>${w.mesh.userData.status ?? 'pending'} | ${w.mesh.userData.priority ?? 50} | ${value ?? w.mesh.userData.value ?? 0}</p>`; }
    updateOverlay(id: string, { text }: { text?: string }) { const w = this.widgets.get(id); if (!w) return; if (text) w.overlay.element.innerHTML = `<h3>${text}</h3><p>${w.mesh.userData.status} | ${w.mesh.userData.priority} | ${w.mesh.userData.value ?? 0}</p>`; }
    addTimeline() { let timeline = document.getElementById("timeline") as HTMLInputElement; if (!timeline) { timeline = Object.assign(document.createElement("input"), { type: "range", min: "0", max: "0", value: "0", style: "width: 200px; margin-top: 10px", id: "timeline" }); timeline.oninput = () => this.render(this.history[+timeline.value]); document.getElementById("hud")!.append(timeline); } this.history.length && (timeline.max = String(this.history.length - 1), timeline.value = String(this.timeIndex)); }
    onResize() { this.camera.aspect = window.innerWidth / window.innerHeight; this.camera.updateProjectionMatrix(); this.renderer.setSize(window.innerWidth, window.innerHeight); this.cssRenderer.setSize(window.innerWidth, window.innerHeight); }
}

const system = new System(); globalThis.system = system; globalThis.logger = logger;

async function main() { logger.info("Starting Netention System...", { component: "main" }); await system.start(); const dashboard = new Dashboard(); dashboard.connectWS(); globalThis.dashboard = dashboard; logger.info("Netention System started and Dashboard initialized.", { component: "main" }); }

main().catch(e => logger.error(`Main error: ${e.message}`, { component: "main", error: e }));