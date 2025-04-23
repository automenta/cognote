import { EventEmitter } from "node:events";
import { WebSocketServer, WebSocket } from "ws";
import pLimit from "p-limit";
import { ChatGoogleGenerativeAI } from "@langchain/google-genai";
import * as THREE from "three";
import { CSS3DRenderer, CSS3DObject } from "three/addons/renderers/CSS3DRenderer.js";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";
import * as z from "zod";

interface Node {
    id: string;
    value: any;
    actions: Node[];
    links: [string, number][];
    history: [string, any][];
    priority: number;
    resources: [number, number];
    timestamp: string;
}

interface Tool {
    name: string;
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

class InMemory implements Memory {
    private map = new Map<string, Node>();
    store(node: Node) { this.map.set(node.id, node); return Promise.resolve(); }
    fetch(id: string) { const n = this.map.get(id); return n ? Promise.resolve(n) : Promise.reject(`Node ${id} not found`); }
    list() { return Promise.resolve([...this.map.values()]); }
}

const tools = {
    add: {
        name: "add",
        apply: async (node, system) => {
            const id = `${node.value.type || "node"}-${Date.now()}`;
            const newNode: Node = {
                id,
                value: node.value.data || {},
                actions: [],
                links: [[node.id, 0.5]],
                history: [],
                priority: 0.5,
                resources: [1000, 1000],
                timestamp: new Date().toISOString()
            };
            await system.memory.store(newNode);
            return { ...node, links: [...node.links, [id, 0.5]], history: [...node.history, [id, "added"]] };
        }
    },
    mod: {
        name: "mod",
        apply: async (node, system) => {
            const targetId = node.value.target || "self";
            const target = targetId === "self" ? node : await system.memory.fetch(targetId);
            const updated = { ...target, ...node.value.updates, priority: system.computePriority(target) };
            await system.memory.store(updated);
            return updated;
        }
    },
    llm: {
        name: "llm",
        apply: async (node) => {
            const llm = new ChatGoogleGenerativeAI({ model: "gemini-2.0-flash", apiKey: process.env.GOOGLE_API_KEY });
            const response = await llm.invoke(node.value.prompt || "Suggest an action");
            return { ...node, history: [...node.history, ["llm", response]], resources: [node.resources[0] - 10, node.resources[1]] };
        }
    },
    guide: {
        name: "guide",
        apply: async (node, system) => {
            const nodes = await system.memory.list();
            const prompt = `Suggest an enhancement for a system with ${nodes.length} nodes: ${node.value.query || "grow"}`;
            const response = await tools.llm.apply({ ...node, value: { prompt } }, system);
            return { ...node, actions: [...node.actions, ...response.history.map(([_, v]) => ({ id: `guide-${Date.now()}`, value: { tool: "add", data: v }, actions: [], links: [], history: [], priority: 0.5, resources: [100, 100], timestamp: "" }))] };
        }
    },
    fork: {
        name: "fork",
        apply: async (node, system) => {
            const branches = node.value.branches || [];
            const results = await Promise.all(branches.map((b: Node) => system.process(b.id)));
            return { ...node, history: [...node.history, ["fork", results.map(r => r.id)]] };
        }
    },
    graph: {
        name: "graph",
        apply: async (node, system) => {
            const nodes = await system.memory.list();
            const neighbors = nodes.filter(n => n.links.some(([id]) => id === node.id)).map(n => n.id);
            return { ...node, history: [...node.history, ["graph", { neighbors }]] };
        }
    }
};

const widgets = {
    sphere: {
        name: "sphere",
        render: (node, scene) => {
            const size = node.value.size || 5;
            const color = node.priority > 0.5 ? 0x00ff00 : node.priority > 0.2 ? 0xffff00 : 0xff0000;
            const mesh = new THREE.Mesh(new THREE.SphereGeometry(size), new THREE.MeshBasicMaterial({ color }));
            mesh.position.set(Math.random() * 100 - 50, Math.random() * 100 - 50, Math.random() * 100 - 50);
            const overlay = new CSS3DObject(Object.assign(document.createElement("div"), {
                className: "node-overlay",
                innerHTML: `<h3>${node.value.desc || node.id}</h3><p>Pri: ${node.priority.toFixed(2)}</p><p>Res: ${node.resources.join("/")}</p>`
            }));
            overlay.position.copy(mesh.position);
            scene.add(mesh, overlay);
            return mesh;
        }
    },
    cube: {
        name: "cube",
        render: (node, scene) => {
            const size = node.value.size || 5;
            const color = node.priority > 0.5 ? 0x0000ff : 0xff00ff;
            const mesh = new THREE.Mesh(new THREE.BoxGeometry(size, size, size), new THREE.MeshBasicMaterial({ color }));
            mesh.position.set(Math.random() * 100 - 50, Math.random() * 100 - 50, Math.random() * 100 - 50);
            const overlay = new CSS3DObject(Object.assign(document.createElement("div"), {
                className: "node-overlay",
                innerHTML: `<h3>${node.value.desc || node.id}</h3>`
            }));
            overlay.position.copy(mesh.position);
            scene.add(mesh, overlay);
            return mesh;
        }
    }
};

export class System {
    memory: Memory;
    tools: Map<string, Tool>;
    widgets: Map<string, Widget>;
    events: EventEmitter;
    server: WebSocketServer;
    queue: [string, number][] = [];
    limit = pLimit(10);
    state = { paused: false, tick: 0 };

    constructor(memory: Memory = new InMemory(), port = 8000) {
        this.memory = memory;
        this.tools = new Map(Object.entries(tools));
        this.widgets = new Map(Object.entries(widgets));
        this.events = new EventEmitter();
        this.server = new WebSocketServer({ port });
    }

    async start() {
        this.server.on("connection", (client) => this.broadcast(client));
        this.events.on("update", (id: string) => this.limit(() => this.process(id)));
        this.run();
        await this.seed();
    }

    async process(id: string) {
        if (this.state.paused) return;
        const node = await this.memory.fetch(id).catch(() => this.create(id));
        if (node.resources[0] <= 0 || node.resources[1] <= 0) return node;
        let updated = { ...node, actions: [...node.actions] };
        while (updated.actions.length) {
            const action = updated.actions.shift()!;
            const tool = this.tools.get(action.value.tool || "nop") || { name: "nop", apply: async (n) => n };
            updated = await tool.apply(action, this, action.value.input || {});
            updated.priority = this.computePriority(updated);
            updated.timestamp = new Date().toISOString();
            updated.resources[1]--;
            await this.memory.store(updated);
            this.queue.push([updated.id, updated.priority]);
            this.events.emit("update", updated.id);
        }
        this.state.tick++;
        return updated;
    }

    run() {
        setInterval(() => {
            this.queue.sort((a, b) => b[1] - a[1]);
            const [id] = this.queue.shift() || [""];
            if (id) this.process(id);
        }, 50);
    }

    computePriority(node: Node): number {
        const novelty = 1 - Math.min(1, node.history.length / 100);
        const resources = (node.resources[0] + node.resources[1]) / 2000;
        const influence = node.links.length ? node.links.reduce((s, [, p]) => s + p, 0) / node.links.length : 0.5;
        return Math.min(1, Math.max(0, (novelty + resources + influence) / 3));
    }

    broadcast(client: WebSocket) {
        this.memory.list().then(nodes => client.send(JSON.stringify({ type: "graph", nodes, tick: this.state.tick })));
    }

    create(id: string): Node {
        return {
            id,
            value: { desc: "New Node" },
            actions: [],
            links: [],
            history: [],
            priority: 0.5,
            resources: [1000, 1000],
            timestamp: new Date().toISOString()
        };
    }

    async seed() {
        const root: Node = {
            id: "root",
            value: { desc: "Netention Root", widget: "sphere", size: 10 },
            actions: [
                { id: "explorer", value: { tool: "add", input: { data: { desc: "Explorer", widget: "sphere", size: 5, actions: [
                                    { id: "explore", value: { tool: "graph" }, actions: [], links: [], history: [], priority: 0.5, resources: [100, 100], timestamp: "" }
                                ] } } }, actions: [], links: [], history: [], priority: 0.5, resources: [100, 100], timestamp: "" },
                { id: "planner", value: { tool: "add", input: { data: { desc: "Planner", widget: "cube", size: 8, actions: [
                                    { id: "plan", value: { tool: "fork", input: { branches: [
                                                    { id: "b1", value: { tool: "add", input: { data: { desc: "Task1", widget: "sphere", size: 3 } } }, actions: [], links: [], history: [], priority: 0.5, resources: [50, 50], timestamp: "" }
                                                ] } }, actions: [], links: [], history: [], priority: 0.5, resources: [100, 100], timestamp: "" }
                                ] } } }, actions: [], links: [], history: [], priority: 0.5, resources: [100, 100], timestamp: "" },
                { id: "guide", value: { tool: "guide", input: { query: "expand" } }, actions: [], links: [], history: [], priority: 0.5, resources: [100, 100], timestamp: "" }
            ],
            links: [],
            history: [],
            priority: 1,
            resources: [5000, 5000],
            timestamp: new Date().toISOString()
        };
        await this.memory.store(root);
        this.events.emit("update", root.id);
    }

    pause() { this.state.paused = true; }
    resume() { this.state.paused = false; }
}

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
        this.ws.onmessage = (e) => this.update(e.data);
    }

    setup() {
        document.body.style.margin = "0";
        document.body.style.overflow = "hidden";
        document.body.style.background = "#1e1e1e";
        const container = document.createElement("div");
        container.id = "container";
        container.style.position = "relative";
        document.body.appendChild(container);

        this.renderer.domElement.id = "canvas";
        this.renderer.setSize(window.innerWidth, window.innerHeight);
        container.appendChild(this.renderer.domElement);

        this.cssRenderer.domElement.style.position = "absolute";
        this.cssRenderer.domElement.style.top = "0";
        this.cssRenderer.setSize(window.innerWidth, window.innerHeight);
        container.appendChild(this.cssRenderer.domElement);

        this.camera.position.set(0, 50, 100);
        this.controls = new OrbitControls(this.camera, this.cssRenderer.domElement);
        this.controls.enableDamping = true;

        this.hud = document.createElement("div");
        this.hud.style.position = "absolute";
        this.hud.style.top = "10px";
        this.hud.style.left = "10px";
        this.hud.style.color = "#d4d4d4";
        this.hud.style.fontFamily = "monospace";
        this.hud.innerHTML = `<div id="status">Nodes: 0 | Tick: 0</div>`;
        container.appendChild(this.hud);

        const input = document.createElement("input");
        input.id = "prompt";
        input.style.background = "#2d2d2d";
        input.style.color = "#d4d4d4";
        input.style.border = "1px solid #444";
        input.style.padding = "5px";
        input.style.marginTop = "10px";
        input.placeholder = "Enter command...";
        input.onkeypress = (e) => e.key === "Enter" && this.handleInput(input.value);
        this.hud.appendChild(input);

        const style = document.createElement("style");
        style.textContent = `
            .node-overlay { background: rgba(0, 0, 0, 0.7); padding: 10px; border-radius: 5px; color: #d4d4d4; font-family: monospace; }
            .node-overlay h3 { margin: 0 0 5px 0; font-size: 14px; }
            .node-overlay p { margin: 2px 0; font-size: 12px; }
        `;
        document.head.appendChild(style);

        window.addEventListener("resize", () => {
            this.camera.aspect = window.innerWidth / window.innerHeight;
            this.camera.updateProjectionMatrix();
            this.renderer.setSize(window.innerWidth, window.innerHeight);
            this.cssRenderer.setSize(window.innerWidth, window.innerHeight);
        });

        this.animate();
    }

    update(data: string) {
        const { type, nodes, tick } = JSON.parse(data);
        if (type !== "graph") return;
        nodes.forEach((n: Node) => {
            if (!this.nodes.has(n.id) && n.value.widget) {
                const widget = this.widgets.get(n.value.widget);
                if (widget) this.nodes.set(n.id, widget.render(n, this.scene));
            }
        });
        this.hud.querySelector("#status")!.textContent = `Nodes: ${this.nodes.size} | Tick: ${tick}`;
    }

    handleInput(cmd: string) {
        const [action, ...args] = cmd.trim().split(" ");
        if (action === "pause") system.pause();
        if (action === "resume") system.resume();
        if (action === "add") {
            const desc = args.join(" ");
            system.memory.store({
                id: `cmd-${Date.now()}`,
                value: { tool: "add", input: { data: { desc, widget: "sphere", size: 5 } } },
                actions: [],
                links: [["root", 0.5]],
                history: [],
                priority: 0.5,
                resources: [100, 100],
                timestamp: new Date().toISOString()
            }).then(() => system.events.emit("update", "root"));
        }
        this.hud.querySelector("#prompt")!.value = "";
    }

    animate() {
        requestAnimationFrame(() => this.animate());
        this.controls.update();
        this.renderer.render(this.scene, this.camera);
        this.cssRenderer.render(this.scene, this.camera);
    }
}

const system = new System();

async function main() {
    await system.start();
    new Dashboard();
}

main().catch(console.error);