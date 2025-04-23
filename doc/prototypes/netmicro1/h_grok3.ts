import { EventEmitter } from "node:events";
import { WebSocketServer, WebSocket } from "ws";
import pLimit from "p-limit";
import { ChatGoogleGenerativeAI } from "@langchain/google-genai";
import * as THREE from "three";
import { CSS3DRenderer, CSS3DObject } from "three/addons/renderers/CSS3DRenderer.js";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";
import * as z from "zod";
import { Logger } from "./src/logger.js";
import { defineTool, Tools } from "./src/tools.js"; // Enhanced Tools class
import { Graph } from "./src/graph.js"; // Graph management
import { ErrorHandler } from "./src/error_handler.js"; // Error handling
import { ExecutionQueue } from "./src/execution_queue_manager.js"; // Queue management

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
    memory?: { type: string; content: string; timestamp: string }[]; // From src/note_runner.js
}

interface Tool {
    name: string;
    description: string;
    schema: z.ZodObject<any>;
    apply: (node: Node, system: System, input: any) => Promise<Node>;
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

// Logger and Error Handler Setup
const logger = new Logger(true);
const errorHandler = new ErrorHandler({ logger }); // Simplified serverState for now

// Memory Implementation
class InMemory implements Memory {
    private store = new Map<string, Node>();
    async store(node: Node) { this.store.set(node.id, node); }
    async fetch(id: string) { const n = this.store.get(id); if (!n) throw new Error(`Node ${id} not found`); return n; }
    async list() { return [...this.store.values()]; }
}

// Tools Definition
const tools = new Tools();
tools.addTool(defineTool({
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
        return { ...node, links: [...node.links, [id, 0.5]], history: [...node.history, [id, "added"]] };
    }
}));
tools.addTool(defineTool({
    name: "llm",
    description: "Invoke LLM for reasoning",
    schema: z.object({ prompt: z.string() }),
    apply: async (node, system, input) => {
        const llm = new ChatGoogleGenerativeAI({ model: "gemini-2.0-flash", apiKey: process.env.GOOGLE_API_KEY });
        const response = await llm.invoke(input.prompt);
        return { ...node, history: [...node.history, ["llm", response]], resources: [node.resources[0] - 10, node.resources[1]] };
    }
}));
tools.addTool(defineTool({
    name: "generateTool",
    description: "Generate a new tool at runtime",
    schema: z.object({ name: z.string(), desc: z.string(), code: z.string() }),
    apply: async (node, system, input) => {
        const toolDef = defineTool({
            name: input.name, description: input.desc, schema: z.object({}),
            apply: async (n, s, i) => new Function("node", "system", "input", input.code)(n, s, i)
        });
        system.tools.addTool(toolDef);
        return { ...node, history: [...node.history, ["generateTool", input.name]] };
    }
}));
tools.addTool(defineTool({
    name: "reflect",
    description: "Reflect on a node and optimize",
    schema: z.object({ targetId: z.string() }),
    apply: async (node, system, input) => {
        const target = await system.memory.fetch(input.targetId);
        const llm = new ChatGoogleGenerativeAI({ model: "gemini-2.0-flash", apiKey: process.env.GOOGLE_API_KEY });
        const summary = await llm.invoke([`Optimize this node: ${JSON.stringify(target)}`]);
        const updates = { ...target, value: { ...target.value, optimized: summary.text } };
        await system.memory.store(updates);
        return { ...node, history: [...node.history, ["reflect", input.targetId]] };
    }
}));

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

    constructor(memory: Memory = new InMemory(), port = 8000) {
        this.memory = memory;
        this.tools = tools;
        this.widgets = new Map(Object.entries(widgets));
        this.events = new EventEmitter();
        this.server = new WebSocketServer({ port });
        this.graph = new Graph(); // From src/graph.js
        this.queue = new ExecutionQueue({ logger, graph: this.graph }); // From src/execution_queue_manager.js
    }

    async start() {
        this.server.on("connection", (client) => this.broadcast(client));
        this.events.on("update", (id: string) => this.limit(() => this.process(id).catch(e => errorHandler.handleNoteError({ id }, e))));
        this.run();
        await this.seed();
    }

    async process(id: string): Promise<Node> {
        if (this.state.paused) return this.memory.fetch(id);
        const node = await this.memory.fetch(id).catch(() => this.create(id));
        if (node.resources[0] <= 0 || node.resources[1] <= 0) return node;
        let updated = { ...node, status: "running" };
        await this.memory.store(updated);

        for (const action of updated.actions) {
            const tool = this.tools.getTool(action.value.tool) || { name: "nop", description: "No-op", schema: z.object({}), apply: async (n) => n };
            try {
                logger.debug(`Executing ${tool.name} on ${id}`, { component: "System/process" });
                updated = await tool.apply(updated, this, action.value.input || {});
                updated.priority = this.computePriority(updated);
                updated.timestamp = new Date().toISOString();
                updated.resources[1]--;
                updated.memory = updated.memory || [];
                updated.memory.push({ type: "tool", content: `${tool.name} applied`, timestamp: new Date().toISOString() });
                await this.memory.store(updated);
                this.queue.queueExecution(updated);
                this.events.emit("update", updated.id);
            } catch (error) {
                updated.status = "failed";
                updated.memory!.push({ type: "error", content: error.message, timestamp: new Date().toISOString() });
                await this.memory.store(updated);
                if (errorHandler.shouldRetry(error)) {
                    updated.status = "pending";
                    this.queue.queueExecution(updated);
                    logger.info(`Retrying ${id} due to ${error.message}`, { component: "System/process" });
                }
                throw error;
            }
        }
        updated.status = "completed";
        await this.memory.store(updated);
        this.state.tick++;
        return updated;
    }

    run() {
        setInterval(() => {
            this.queue.optimizeSchedule();
            const notes = this.queue.executionQueue.values();
            for (const id of notes) {
                this.process(id).catch(e => logger.error(`Run error: ${e.message}`, { id }));
                this.queue.executionQueue.delete(id); // Remove after processing
            }
        }, 50);
    }

    computePriority(node: Node): number {
        const deadlineFactor = node.value.deadline ? (new Date(node.value.deadline).getTime() - Date.now()) / (1000 * 60) : 0;
        const usage = node.memory?.length || 0;
        return (node.priority || 0.5) - (deadlineFactor < 0 ? 100 : deadlineFactor) + usage / 100;
    }

    broadcast(client: WebSocket) {
        this.memory.list().then(nodes => client.send(JSON.stringify({ type: "graph", nodes, tick: this.state.tick })));
    }

    create(id: string): Node {
        const node = {
            id, value: { desc: "New Node" }, actions: [], links: [], history: [], priority: 0.5,
            resources: [1000, 1000], timestamp: new Date().toISOString(), status: "pending",
            memory: [{ type: "system", content: "Node created", timestamp: new Date().toISOString() }]
        };
        this.graph.addNote(node);
        return node;
    }

    async seed() {
        const root: Node23 = {
            id: "root",
            value: { desc: "Netention Ultra Root", widget: "sphere", size: 10 },
            actions: [
                { id: "toolGen", value: { tool: "generateTool", input: { name: "testTool", desc: "Test tool", code: "return {...node, value: {...node.value, test: 'done'}}" } }, actions: [], links: [], history: [], priority: 0.5, resources: [100, 100], timestamp: "", status: "pending" },
                { id: "reflect", value: { tool: "reflect", input: { targetId: "root" } }, actions: [], links: [], history: [], priority: 0.5, resources: [100, 100], timestamp: "", status: "pending" }
            ],
            links: [], history: [], priority: 1, resources: [5000, 5000], timestamp: new Date().toISOString(), status: "pending",
            memory: [{ type: "system", content: "Root initialized", timestamp: new Date().toISOString() }]
        };
        await this.memory.store(root);
        this.graph.addNote(root);
        this.events.emit("update", root.id);
    }
}

// Dashboard Class
class Dashboard {
    scene = new THREE.Scene();
    camera = new THREE.PerspectiveCamera(75, window.innerWidth / window.innerHeight, 0.1, 1000);
    renderer = new THREE.WebGLRenderer({ canvas: document.createElement("canvas"), antialias: true });
    cssRenderer = new CSS3DRenderer();
    controls: OrbitControls;
    widgets = new Map(Object.entries(widgets));
    nodes = new Map<string, THREE.Object3D>();
    ws: WebSocket;
    hud: HTMLDivElement;

    constructor(port = 8000) {
        this.setup();
        this.ws = new WebSocket(`ws://localhost:${port}`);
        this.ws.onmessage = (e) => this.update(JSON.parse(e.data));
    }

    setup() {
        document.body.style.cssText = "margin: 0; overflow: hidden; background: #1e1e1e;";
        const container = Object.assign(document.createElement("div"), { id: "container", style: "position: relative;" });
        document.body.appendChild(container);

        this.renderer.domElement.id = "canvas";
        this.renderer.setSize(window.innerWidth, window.innerHeight);
        container.appendChild(this.renderer.domElement);

        this.cssRenderer.domElement.style.cssText = "position: absolute; top: 0;";
        this.cssRenderer.setSize(window.innerWidth, window.innerHeight);
        container.appendChild(this.cssRenderer.domElement);

        this.camera.position.set(0, 50, 100);
        this.controls = new OrbitControls(this.camera, this.cssRenderer.domElement);
        this.controls.enableDamping = true;

        this.hud = Object.assign(document.createElement("div"), {
            style: "position: absolute; top: 10px; left: 10px; color: #d4d4d4; font-family: monospace;",
            innerHTML: `<div id="status">Nodes: 0 | Tick: 0</div>`
        });
        container.appendChild(this.hud);

        const input = Object.assign(document.createElement("input"), {
            id: "prompt", style: "background: #2d2d2d; color: #d4d4d4; border: 1px solid #444; padding: 5px; margin-top: 10px;",
            placeholder: "Enter command...", onkeypress: (e: KeyboardEvent) => e.key === "Enter" && this.handleInput((e.target as HTMLInputElement).value)
        });
        this.hud.appendChild(input);

        document.head.appendChild(Object.assign(document.createElement("style"), {
            textContent: `.node-overlay { background: rgba(0, 0, 0, 0.7); padding: 10px; border-radius: 5px; color: #d4d4d4; font-family: monospace; }`
        }));

        window.addEventListener("resize", () => {
            this.camera.aspect = window.innerWidth / window.innerHeight;
            this.camera.updateProjectionMatrix();
            this.renderer.setSize(window.innerWidth, window.innerHeight);
            this.cssRenderer.setSize(window.innerWidth, window.innerHeight);
        });

        this.animate();
    }

    update(data: { type: string; nodes: Node[]; tick: number }) {
        if (data.type !== "graph") return;
        data.nodes.forEach((n) => {
            if (!this.nodes.has(n.id) && n.value.widget) {
                const widget = this.widgets.get(n.value.widget);
                if (widget) this.nodes.set(n.id, widget.render(n, this.scene));
            }
        });
        this.hud.querySelector("#status")!.textContent = `Nodes: ${this.nodes.size} | Tick: ${data.tick}`;
    }

    handleInput(cmd: string) {
        const [action, ...args] = cmd.trim().split(" ");
        const system = globalThis.system;
        if (action === "add") {
            const desc = args.join(" ");
            system.memory.store({
                id: `cmd-${Date.now()}`, value: { tool: "add", input: { data: { desc, widget: "sphere", size: 5 } } },
                actions: [], links: [["root", 0.5]], history: [], priority: 0.5, resources: [100, 100], timestamp: new Date().toISOString(), status: "pending",
                memory: [{ type: "user", content: `Added via command: ${desc}`, timestamp: new Date().toISOString() }]
            }).then(() => system.events.emit("update", "root"));
        }
        (this.hud.querySelector("#prompt") as HTMLInputElement).value = "";
    }

    animate() {
        requestAnimationFrame(() => this.animate());
        this.controls.update();
        this.renderer.render(this.scene, this.camera);
        this.cssRenderer.render(this.scene, this.camera);
    }
}

const system = new System();
globalThis.system = system;

async function main() {
    await system.start();
    new Dashboard();
}

main().catch(e => logger.error(`Main error: ${e.message}`));