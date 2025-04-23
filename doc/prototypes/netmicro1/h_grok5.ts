import { EventEmitter } from "node:events";
import express from "express";
import { WebSocketServer, WebSocket } from "ws";
import pLimit from "p-limit";
import { ChatGoogleGenerativeAI } from "@langchain/google-genai";
import * as THREE from "three";
import { CSS3DRenderer, CSS3DObject } from "three/addons/renderers/CSS3DRenderer.js";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";
import * as z from "zod";
import TWEEN from "https://cdnjs.cloudflare.com/ajax/libs/tween.js/25.0.0/tween.esm.js";

// Utility Classes
class Logger {
    constructor(public verbose: boolean) {}
    info(msg: string, meta?: any) { console.log(msg, meta || ""); }
    debug(msg: string, meta?: any) { if (this.verbose) console.log(msg, meta || ""); }
    error(msg: string, meta?: any) { console.error(msg, meta || ""); }
}

class ErrorHandler {
    constructor(private logger: Logger) {}
    handleNoteError({ id }: { id: string }, error: any) { this.logger.error(`Error in ${id}: ${error.message}`); }
    shouldRetry(error: any): boolean { return error.message.includes("transient") || error instanceof TypeError; }
}

class Graph {
    addNote(node: Node) { /* Simplified graph management */ }
}

class ExecutionQueue {
    executionQueue = new Set<string>();
    constructor(private opts: { logger: Logger; graph: Graph }) {}
    queueExecution(node: Node) { this.executionQueue.add(node.id); }
    optimizeSchedule() { /* Simplified: Prioritize based on node.priority */ }
}

// Core Interfaces
interface Node {
    id: string;
    value: any;
    actions: Node[];
    links: [string, number][];
    history: [string, any][];
    priority: number;
    resources: [number, number];
    timestamp: string;
    status: "pending" | "running" | "completed" | "failed";
    memory: { type: string; content: string; timestamp: string }[];
    logic?: Logic;
}

interface Logic {
    type: "sequential" | "conditional" | "loop" | "fork" | "eval" | "modify" | "graph" | "plan" | "reason" | "ui" | "llm";
    steps?: LogicStep[];
    condition?: string;
    body?: Logic;
    count?: number;
    target?: string;
    code?: string;
    operation?: string;
    query?: string;
    goal?: string;
    widget?: string;
    prompt?: string;
}

interface LogicStep {
    tool: string;
    input: any;
}

interface Tool {
    name: string;
    description: string;
    schema: z.ZodObject<any>;
    apply: (node: Node, system: System, input: any) => Promise<Node | any>;
}

interface Widget {
    name: string;
    render: (node: Node, scene: THREE.Scene) => THREE.Object3D;
}

interface Memory {
    store(node: Node): Promise<void>;
    fetch(id: string): Promise<Node>;
    list(): Promise<Node[]>;
}

// Memory Implementation
class InMemory implements Memory {
    private store = new Map<string, Node>();
    async store(node: Node) { this.store.set(node.id, node); }
    async fetch(id: string) { const n = this.store.get(id); if (!n) throw new Error(`Node ${id} not found`); return n; }
    async list() { return [...this.store.values()]; }
}

// Tools Definition
class Tools {
    private tools = new Map<string, Tool>();
    addTool(tool: Tool) { this.tools.set(tool.name, tool); }
    getTool(name: string) { return this.tools.get(name); }
}

const logger = new Logger(true);
const errorHandler = new ErrorHandler(logger);
const tools = new Tools();

tools.addTool({
    name: "add",
    description: "Add a new node",
    schema: z.object({ type: z.string().optional(), data: z.any().optional() }),
    apply: async (node, system, input) => {
        const id = `${input.type || "node"}-${Date.now()}`;
        const newNode: Node = {
            id, value: input.data || {}, actions: [], links: [[node.id, 0.5]], history: [],
            priority: 0.5, resources: [1000, 1000], timestamp: new Date().toISOString(), status: "pending",
            memory: [{ type: "system", content: `Created by ${node.id}`, timestamp: new Date().toISOString() }]
        };
        await system.memory.store(newNode);
        system.graph.addNote(newNode);
        system.queue.queueExecution(newNode);
        return { ...node, links: [...node.links, [id, 0.5]], history: [...node.history, [id, "added"]] };
    }
});

tools.addTool({
    name: "llm",
    description: "Invoke LLM for reasoning",
    schema: z.object({ prompt: z.string() }),
    apply: async (node, system, input) => {
        const llm = new ChatGoogleGenerativeAI({ model: "gemini-2.0-flash", apiKey: process.env.GOOGLE_API_KEY });
        const response = await llm.invoke(input.prompt);
        node.memory.push({ type: "llm", content: response, timestamp: new Date().toISOString() });
        return { ...node, history: [...node.history, ["llm", response]], resources: [node.resources[0] - 10, node.resources[1]] };
    }
});

tools.addTool({
    name: "graph",
    description: "Manipulate graph structure",
    schema: z.object({ operation: z.string(), query: z.string().optional(), target: z.string().optional() }),
    apply: async (node, system, input) => {
        const nodes = await system.memory.list();
        switch (input.operation) {
            case "connect": node.links.push([input.target!, 0.5]); break;
            case "filter": return nodes.filter(n => new Function("n", `return ${input.query}`)(n)).map(n => n.id);
        }
        return node;
    }
});

tools.addTool({
    name: "ui",
    description: "Update UI widget",
    schema: z.object({ widget: z.string(), id: z.string(), color: z.number().optional() }),
    apply: async (node, system, input) => {
        system.dashboard.updateWidget(input.id, { color: input.color });
        return node;
    }
});

// Widgets Definition
const widgets: Record<string, Widget> = {
    sphere: {
        name: "sphere",
        render: (node, scene) => {
            const mesh = new THREE.Mesh(
                new THREE.SphereGeometry(node.value.size || 5),
                new THREE.MeshBasicMaterial({ color: node.status === "completed" ? 0x00ff00 : 0xff0000 })
            );
            mesh.position.set(Math.random() * 100 - 50, Math.random() * 100 - 50, Math.random() * 100 - 50);
            const overlay = new CSS3DObject(Object.assign(document.createElement("div"), {
                className: "node-overlay",
                innerHTML: `<h3>${node.value.desc || node.id}</h3><p>Status: ${node.status}</p>`
            }));
            overlay.position.copy(mesh.position);
            scene.add(mesh, overlay);
            return mesh;
        }
    }
};

// Core System Class
export class System {
    memory: Memory;
    tools: Tools;
    widgets: Map<string, Widget>;
    events: EventEmitter;
    server: WebSocketServer;
    graph: Graph;
    queue: ExecutionQueue;
    limit = pLimit(5);
    state = { paused: false, tick: 0 };
    dashboard: Dashboard;

    constructor(memory: Memory = new InMemory(), port = 8000) {
        this.memory = memory;
        this.tools = tools;
        this.widgets = new Map(Object.entries(widgets));
        this.events = new EventEmitter();
        const app = express();
        const server = app.listen(port, () => logger.info(`Server at http://localhost:${port}`));
        this.server = new WebSocketServer({ server });
        this.graph = new Graph();
        this.queue = new ExecutionQueue({ logger, graph: this.graph });
        this.dashboard = new Dashboard(this, port);
        app.get("/", (_, res) => res.send(this.dashboard.getHTML()));
    }

    async start() {
        this.server.on("connection", (client) => this.broadcast(client));
        this.events.on("update", (id: string) => this.limit(() => this.process(id).catch(e => errorHandler.handleNoteError({ id }, e))));
        await this.seed();
        this.run();
    }

    async process(id: string, retries = 3): Promise<Node> {
        if (this.state.paused) return this.memory.fetch(id);
        let node = await this.memory.fetch(id).catch(() => this.create(id));
        if (node.resources[0] <= 0 || node.resources[1] <= 0) return node;
        node = { ...node, status: "running" };
        await this.memory.store(node);

        for (let attempt = 1; attempt <= retries; attempt++) {
            try {
                logger.debug(`Processing ${id} (attempt ${attempt})`);
                if (node.logic) await this.executeLogic(node.logic, node);
                for (const action of node.actions) {
                    const tool = this.tools.getTool(action.value.tool) || { name: "nop", description: "No-op", schema: z.object({}), apply: async (n) => n };
                    node = await tool.apply(node, this, action.value.input || {});
                    node.priority = this.computePriority(node);
                    node.timestamp = new Date().toISOString();
                    node.resources[1]--;
                    node.memory.push({ type: "tool", content: `${tool.name} applied`, timestamp: new Date().toISOString() });
                    await this.memory.store(node);
                    this.queue.queueExecution(node);
                }
                node.status = "completed";
                await this.memory.store(node);
                this.state.tick++;
                this.broadcastAll();
                return node;
            } catch (error) {
                node.status = "failed";
                node.memory.push({ type: "error", content: error.message, timestamp: new Date().toISOString() });
                await this.memory.store(node);
                if (errorHandler.shouldRetry(error) && attempt < retries) {
                    node.status = "pending";
                    await this.memory.store(node);
                    this.queue.queueExecution(node);
                    logger.info(`Retrying ${id} due to ${error.message}`);
                    await new Promise(r => setTimeout(r, 1000 * attempt));
                    continue;
                }
                throw error;
            }
        }
        return node;
    }

    async executeLogic(logic: Logic, node: Node) {
        switch (logic.type) {
            case "sequential":
                for (const step of logic.steps || []) {
                    const tool = this.tools.getTool(step.tool);
                    if (tool) node = await tool.apply(node, this, step.input || {});
                }
                break;
            case "loop":
                for (let i = 0; i < (logic.count || 0); i++) await this.executeLogic(logic.body!, node);
                break;
            case "ui":
                await this.tools.getTool("ui")!.apply(node, this, { widget: logic.widget, id: node.id, color: logic.code ? new Function("node", `return ${logic.code}`)(node) : undefined });
                break;
        }
        await this.memory.store(node);
    }

    run() {
        setInterval(() => {
            this.queue.optimizeSchedule();
            const nodes = this.queue.executionQueue.values();
            for (const id of nodes) {
                this.process(id).catch(e => logger.error(`Run error: ${e.message}`, { id }));
                this.queue.executionQueue.delete(id);
            }
        }, 50);
    }

    computePriority(node: Node): number {
        const usage = node.memory.length || 0;
        return (node.priority || 0.5) + usage / 100;
    }

    create(id: string): Node {
        const node: Node = {
            id, value: { desc: "New Node" }, actions: [], links: [], history: [], priority: 0.5,
            resources: [1000, 1000], timestamp: new Date().toISOString(), status: "pending",
            memory: [{ type: "system", content: "Node created", timestamp: new Date().toISOString() }]
        };
        this.graph.addNote(node);
        return node;
    }

    async seed() {
        const root: Node = {
            id: "root",
            value: { desc: "Hybrid System Root", widget: "sphere", size: 10 },
            actions: [
                { id: "add", value: { tool: "add", input: { data: { desc: "Child Node", widget: "sphere", size: 5 } } }, actions: [], links: [], history: [], priority: 0.5, resources: [100, 100], timestamp: new Date().toISOString(), status: "pending", memory: [] }
            ],
            links: [],
            history: [],
            priority: 1,
            resources: [5000, 5000],
            timestamp: new Date().toISOString(),
            status: "pending",
            memory: [{ type: "system", content: "Root initialized", timestamp: new Date().toISOString() }],
            logic: {
                type: "sequential",
                steps: [
                    { tool: "llm", input: { prompt: "Suggest a task" } },
                    { tool: "ui", input: { widget: "sphere", id: "root", color: 0x00ff00 } }
                ]
            }
        };
        await this.memory.store(root);
        this.graph.addNote(root);
        this.queue.queueExecution(root);
        this.events.emit("update", root.id);
    }

    broadcast(client: WebSocket) {
        this.memory.list().then(nodes => client.send(JSON.stringify({ type: "graph", nodes, tick: this.state.tick })));
    }

    broadcastAll() {
        this.memory.list().then(nodes => {
            const data = JSON.stringify({ type: "graph", nodes, tick: this.state.tick });
            this.server.clients.forEach(client => client.send(data));
        });
    }
}

// Dashboard Class
class Dashboard {
    scene = new THREE.Scene();
    camera = new THREE.PerspectiveCamera(75, window.innerWidth / window.innerHeight, 0.1, 1000);
    renderer = new THREE.WebGLRenderer({ antialias: true });
    cssRenderer = new CSS3DRenderer();
    controls: OrbitControls;
    widgets = new Map<string, { mesh: THREE.Mesh; overlay: CSS3DObject }>();
    ws: WebSocket;

    constructor(system: System, port: number) {
        this.setup();
        this.ws = new WebSocket(`ws://localhost:${port}`);
        this.ws.onmessage = (e) => this.update(JSON.parse(e.data));
        this.controls = new OrbitControls(this.camera, this.cssRenderer.domElement);
        this.camera.position.set(0, 50, 100);
        this.animate();
    }

    setup() {
        this.renderer.setSize(window.innerWidth, window.innerHeight);
        this.cssRenderer.setSize(window.innerWidth, window.innerHeight);
        this.cssRenderer.domElement.style.position = "absolute";
        this.cssRenderer.domElement.style.top = "0";
    }

    getHTML() {
        return `
      <!DOCTYPE html>
      <html lang="en">
      <head><meta charset="UTF-8"><title>Hybrid System</title><style>
        body { margin: 0; overflow: hidden; background: #1e1e1e; color: #d4d4d4; font-family: monospace; }
        #container { position: relative; width: 100%; height: 100vh; }
        .node-overlay { background: rgba(0, 0, 0, 0.7); padding: 10px; border-radius: 5px; color: #d4d4d4; }
      </style></head>
      <body><div id="container"></div></body>
      </html>
    `;
    }

    update(data: { type: string; nodes: Node[]; tick: number }) {
        if (data.type !== "graph") return;
        data.nodes.forEach(n => {
            if (!this.widgets.has(n.id) && n.value.widget) {
                const widget = widgets[n.value.widget];
                if (widget) {
                    const mesh = widget.render(n, this.scene);
                    this.widgets.set(n.id, { mesh, overlay: this.scene.children.find(c => c instanceof CSS3DObject) as CSS3DObject });
                }
            }
        });
    }

    updateWidget(id: string, { color }: { color?: number }) {
        const w = this.widgets.get(id);
        if (w && color) (w.mesh.material as THREE.MeshBasicMaterial).color.set(color);
    }

    animate() {
        requestAnimationFrame(() => this.animate());
        this.controls.update();
        this.renderer.render(this.scene, this.camera);
        this.cssRenderer.render(this.scene, this.camera);
        TWEEN.update();
    }
}

const system = new System();
globalThis.system = system;

async function main() {
    await system.start();
}

main().catch(e => logger.error(`Main error: ${e.message}`));