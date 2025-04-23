import express from "express";
import { WebSocketServer, WebSocket } from "ws";
import { EventEmitter } from "node:events";
import pLimit from "p-limit";
import { ChatGoogleGenerativeAI } from "@langchain/google-genai";
import * as THREE from "three";
import { CSS3DRenderer, CSS3DObject } from "three/addons/renderers/CSS3DRenderer.js";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";
import * as z from "zod";
import { Logger } from "./src/logger.js"; // Assuming logger implementation
import { defineTool } from "./src/tools.js"; // Assuming tool definition helper
import { Graph } from "./src/graph.js";       // Assuming graph implementation
import TWEEN from "https://cdnjs.cloudflare.com/ajax/libs/tween.js/25.0.0/tween.esm.js";

const PORT = 8000, HOST = "localhost";
const logger = new Logger(true); //  Logger instance
const limit = pLimit(10);

// --- Unified Types ---
type Vector3 = { x: number; y: number; z: number };
interface Note extends Node{} //Combine the types

interface Node {
    id: string;
    value: any; // Flexible data container
    actions?: Node[]; // For tool applications.  Make optional for backward compat
    links: [string, number][];  //who this node points to and at what strength
    graph?: { target: string; rel: string }[];//used by GraphTool
    history: [string, any][]; // History of actions/changes
    priority: number;         // Lower numbers = higher priority
    resources: [number, number]; // [tokens, cycles] or similar resource limits
    timestamp: string;        // Last modified timestamp
    status: "pending" | "running" | "completed" | "failed";
    memory?: { type: string; content: string; timestamp: string }[];//can contain anything: system messages, tool outputs, llm, etc.
    tools?: Record<string, string>; // Keep track of available tools?
    context?: string[];      //for context awareness.
    logic?: Logic;//for executing different logic types.
    state?: { status: string; priority: number; entropy: number }; //for note state management
}
type Logic = { type: "sequential" | "conditional" | "loop" | "fork" | "eval" | "modify" | "graph" | "plan" | "reason" | "ui" | "llm"; steps?: LogicStep[]; condition?: string; body?: Logic; count?: number; target?: string; code?: string; operation?: string; query?: string; goal?: string; widget?: string; prompt?: string };
type LogicStep = { tool: string; input: any };

interface Widget {
    name: string;
    render: (node: Node, scene: THREE.Scene) => THREE.Object3D;
}

// --- Memory ---
class Memory extends Map<string, Note> {
    async save(n: Note) { this.set(n.id, n); emitter.emit("update", "change", n.id); broadcastNotes(); }
    async load(id: string) { const n = this.get(id); if (!n) throw Error(`Note ${id} not found`); return n; }
    async all() { return [...this.values()]; }
}

const memory = new Memory();
const emitter = new EventEmitter();

// --- Tools API ---
const tools = new Map<string, Tool>();

abstract class Tool<T = any> {
    abstract name: string;
    abstract description: string; // Added description
    abstract schema: z.ZodObject<any>;   // Added schema
    abstract execute(input: T, ctx: { note: Note; memory: Memory; ui: UI; system: System }): Promise<any>; // Added system to ctx
}

// Add the original tools using defineTool (for consistency and Zod validation)
tools.set("add", defineTool({
    name: "add",
    description: "Add a new node",
    schema: z.object({ type: z.string().optional(), data: z.any().optional() }),
    execute: async (input, { memory, note }) => {
        const id = `${input.type || "node"}-${Date.now()}`;
        const newNode: Note = {
            id, value: input.data || {}, links: [[note.id, 0.5]], history: [],
            priority: 0.5, resources: [1000, 1000], timestamp: new Date().toISOString(), status: "pending",
            memory: [{ type: "system", content: `Created by ${note.id}`, timestamp: new Date().toISOString() }]
        };
        await memory.save(newNode);
        //  system.graph.addNote(newNode); // Add to graph (assumes you have a system instance) No graph in this version
        return { ...note, links: [...note.links, [id, 0.5]], history: [...note.history, [id, "added"]] };
    }
}));

tools.set("llm", defineTool({
    name: "llm",
    description: "Invoke LLM for reasoning",
    schema: z.object({ prompt: z.string() }),
    execute: async (input, { note, memory }) => { //Removed System, added memory
        const llm = new ChatGoogleGenerativeAI({ modelName: "gemini-pro", apiKey: process.env.GOOGLE_API_KEY });
        const response = await llm.invoke([{type: "text", text: input.prompt}]);
        const llm_content = response[0].text
        const new_memory = { type: "llm", content: llm_content, timestamp: new Date().toISOString() };
        note.memory ? note.memory.push(new_memory): note.memory = [new_memory];
        await memory.save(note); //save to memory
        return { ...note, history: [...note.history, ["llm", llm_content]], resources: [note.resources[0] - 10, note.resources[1]] }; //return the note
    }
}));

tools.set("generateTool", defineTool({
    name: "generateTool",
    description: "Generate a new tool at runtime",
    schema: z.object({ name: z.string(), desc: z.string(), code: z.string() }),
    execute: async (input, { system, note }) => { // Added system to ctx
        // Safer eval using a Function constructor
        const toolDef = defineTool({
            name: input.name,
            description: input.desc,
            schema: z.object({}), // You might want a more dynamic schema here
            apply: async (n, s, i) => {
                // Isolate the execution context
                return new Function("node", "system", "input", input.code)(n, s, i);
            }
        });

        system.tools.set(input.name, toolDef);  //system.tools now a map.
        note.memory ? note.memory.push({type: "generateTool", content: `Generated tool named ${input.name}`, timestamp: new Date().toISOString()}) : note.memory = [{type: "generateTool", content: `Generated tool named ${input.name}`, timestamp: new Date().toISOString()}];
        await memory.save(note);
        return { ...note, history: [...note.history, ["generateTool", input.name]] };
    }
}));


tools.set("reflect", defineTool({
    name: "reflect",
    description: "Reflect on a node and optimize",
    schema: z.object({ targetId: z.string() }),
    execute: async (input, { memory, note }) => { //removed system, added memory
        const target = await memory.load(input.targetId);
        const llm = new ChatGoogleGenerativeAI({ modelName: "gemini-pro", apiKey: process.env.GOOGLE_API_KEY });
        const summary = await llm.invoke([{type: "text", text: `Optimize this node: ${JSON.stringify(target)}`}]);
        const updates = { ...target, value: { ...target.value, optimized: summary } };
        updates.memory ? updates.memory.push({type: "reflect", content: `Reflection Complete`, timestamp: new Date().toISOString()}): updates.memory = [{type: "reflect", content: `Reflection Complete`, timestamp: new Date().toISOString()}];
        await memory.save(updates);
        note.memory? note.memory.push({type: "reflect", content: `Reflected on node: ${input.targetId}`, timestamp: new Date().toISOString()}) : note.memory = [{type: "reflect", content: `Reflected on node: ${input.targetId}`, timestamp: new Date().toISOString()}];

        await memory.save(note);

        return { ...note, history: [...note.history, ["reflect", input.targetId]] };
    }
}));



// --- New and Adapted Tools (using class structure) ---

class KnowTool extends Tool<{ content: any }> {
    name = "know";
    description = "Create or update a note in memory";
    schema = z.object({ content: z.any() });
    async execute({ content }, { memory, note }) {
        const noteId = content.id ?? `${content.type ?? "note"}-${Date.now()}`;
        const existingNote = memory.get(noteId);

        const newNode: Note = {
            id: noteId,
            content: existingNote ? { ...existingNote.content, ...content } : content, // Merge content
            state: content.state ?? { status: "running", priority: 50, entropy: 0 },
            graph: existingNote ? existingNote.graph : [],
            links: existingNote? existingNote.links: [],
            memory: existingNote ? existingNote.memory : [],
            tools: existingNote? existingNote.tools : {},
            context: content.context ?? (note ? [note.id] : ["root"]),  //context is now the calling note.
            ts: new Date().toISOString(),
            resources: content.resources ?? { tokens: 100, cycles: 100 },
            logic: content.logic ?? { type: "sequential", steps: [] },
            history: existingNote? existingNote.history: [],
            priority: existingNote? existingNote.priority : 50,
            status: existingNote? existingNote.status : "pending",
            value: existingNote? existingNote.value: {}

        };
        if(note){ //if called from another note, create links
            if(note.links){
                if (!note.links.some(g => g[0] === newNode.id)) note.links.push([newNode.id, 0.5]), await memory.save(note);
            }
            else{
                note.links = [[newNode.id, 0.5]];
                await memory.save(note);
            }
        }
        await memory.save(newNode);

        if (newNode.content.name !== "know" && newNode.logic) await runNote(newNode.id);
        return { status: "done", content: newNode };
    }
}
tools.set("know", new KnowTool());

class GraphTool extends Tool<{ operation: string; query?: string; target?: string }> {
    name = "graph";
    description = "Manipulate the note graph";
    schema = z.object({
        operation: z.enum(["neighbors", "path", "filter", "connect"]),
        query: z.string().optional(),
        target: z.string().optional()
    });
    async execute({ operation, query, target }, { note, memory }) {
        const nodes = await memory.all();
        switch (operation) {
            case "neighbors": return { status: "done", result: note.graph ? note.graph.map(g => g.target) : (note.links? note.links.map(l=>l[0]) : []) };
            case "path": {
                const start = note.id, end = target!, visited = new Set<string>(), queue = [[start]], paths: string[][] = [];
                while (queue.length) {
                    const [path] = queue.splice(0, 1), last = path.at(-1)!;
                    if (last === end) paths.push(path);
                    if (!visited.has(last)) {
                        visited.add(last);
                        const n = await memory.load(last);
                        const neighbors = n.graph ? n.graph.map(g=>g.target) : (n.links? n.links.map(link=> link[0]):[]);
                        neighbors.forEach(neighbor => queue.push([...path, neighbor]));
                    }
                }
                return { status: "done", result: paths[0] ?? [] };
            }
            case "filter": return { status: "done", result: nodes.filter(n => new Function("n", `return ${query}`)(n)).map(n => n.id) };
            case "connect":
                if(!note.graph){
                    note.graph = [];
                }
                note.graph.push({ target: target!, rel: "linked" });
                await memory.save(note);
                return { status: "done" };
        }
        return { status: "done", result: [] };
    }
}

tools.set("graph", new GraphTool());

class PlanTool extends Tool<{ goal: string }> {
    name = "plan";
    description = "Create a plan to achieve a goal";
    schema = z.object({ goal: z.string() });
    async execute({ goal }, { note, memory }) {
        const steps: LogicStep[] = [];
        if (goal.includes("connect")) {
            const [_, from, to] = goal.match(/connect (\w+) to (\w+)/) ?? [];
            if (from && to) steps.push({ tool: "graph", input: { operation: "connect", target: to } });
        } else if (goal.includes("ui")) {
            steps.push({ tool: "ui", input: { widget: "sphere", id: note.id, color: 0xff00ff } });
        }

        //Instead of directly modifying the logic, return it.
        return {status: "done", plan: steps};

    }
}
tools.set("plan", new PlanTool());

class ReasonTool extends Tool<{ premise: string; conclusion: string }> {
    name = "reason";
    description = "Perform logical reasoning";
    schema = z.object({ premise: z.string(), conclusion: z.string() });
    async execute({ premise, conclusion }, { note, memory }) {
        const nodes = await memory.all();
        const premiseTrue = nodes.some(n => new Function("n", `return ${premise}`)(n));
        return { status: "done", result: premiseTrue && new Function("n", `return ${conclusion}`)(note) };
    }
}

tools.set("reason", new ReasonTool());

class EvalTool extends Tool<{ code: string }> {
    name = "eval";
    description = "Evaluate JavaScript code";
    schema = z.object({ code: z.string() });
    async execute({ code }, { note, memory, ui, system }) { //Added system to the context
        // Use Function constructor for safer evaluation
        const fn = new Function("note", "memory", "tools", "ui", "system", `return (${code})`);
        return { status: "done", result: await fn(note, memory, tools, ui, system) };
    }
}

tools.set("eval", new EvalTool());

class ModifyTool extends Tool<{ target: string; key: string; value: any }> {
    name = "modify";
    description = "Modify a note's properties";
    schema = z.object({ target: z.string(), key: z.string(), value: z.any() });
    async execute({ target, key, value }, { memory }) {
        const note = await memory.load(target);
        let resolvedValue = value;
        if (typeof value === "string" && value.includes("note.")) {
            resolvedValue = new Function("note", `return ${value}`)(note);
        }

        //Type-safe update
        if(key in note){
            note[key as keyof Note] = resolvedValue;
        }
        else{
            throw new Error(`Key ${key} does not exist on note ${target}`)
        }

        await memory.save(note);
        return { status: "done" };
    }
}

tools.set("modify", new ModifyTool());

class ForkTool extends Tool<{ target: string }> {
    name = "fork";
    description = "Create a copy of a note";
    schema = z.object({ target: z.string() });
    async execute({ target }, { memory }) {
        const note = await memory.load(target);
        const clone: Note = {
            ...note,
            id: `${note.id}-fork-${Date.now()}`,
            context: [note.id],  //context is now an array.
            links: [[note.id, 0.5]], //link back to original
            memory: [{type: "system", content: `Forked from ${note.id}`, timestamp: new Date().toISOString()}]
        };
        await memory.save(clone);
        if(clone.logic) await runNote(clone.id); //start running if it has logic
        return { status: "done", id: clone.id };
    }
}

tools.set("fork", new ForkTool());

class UITool extends Tool<{ widget: string; id: string; color?: number; text?: string, position?: Vector3 }> {
    name = "ui";
    description = "Update UI elements";
    schema = z.object({
        widget: z.enum(["sphere", "overlay", "line"]),
        id: z.string(),
        color: z.number().optional(),
        text: z.string().optional(),
        position: z.object({x: z.number(), y: z.number(), z: z.number()}).optional()

    });
    async execute({ widget, id, color, text, position }, { memory, ui }) {
        const note = await memory.load(id);
        switch (widget) {
            case "sphere": ui.updateWidget(id, { color: color ?? note.value.color, position }); break;
            case "overlay": ui.updateOverlay(id, { text: text ?? note.value.desc }); break;
            case "line":
                //Extract the source and target
                if(note.links && note.links.length > 0){
                    const source = note.id;
                    const target = note.links[0][0]; //first link
                    ui.updateLine(source, target)
                }

                break;
        }
        return { status: "done" };
    }
}

tools.set("ui", new UITool());



// --- Widgets ---
const widgets: Record<string, Widget> = {
    sphere: {
        name: "sphere",
        render: (node, scene) => {
            const mesh = new THREE.Mesh(
                new THREE.SphereGeometry(node.value.size || 5),
                new THREE.MeshBasicMaterial({ color: node.status === "completed" ? 0x00ff00 : 0xff0000 }) // Use status for color
            );
            mesh.position.set(Math.random() * 100 - 50, Math.random() * 100 - 50, Math.random() * 100 - 50);
            // Add a CSS3DObject for the overlay
            const overlay = new CSS3DObject(Object.assign(document.createElement("div"), {
                className: "node-overlay", // For styling
                innerHTML: `<h3>${node.value.desc || node.id}</h3><p>Status: ${node.status}</p>`
            }));
            overlay.position.copy(mesh.position); // Match overlay position to mesh
            scene.add(mesh, overlay);
            return mesh;
        }
    }
};

// --- Seed Data (Unified and Simplified) ---
const seed: Note = {
    id: "root", value: { type: "system", desc: "Netention Ultra Root", widget: "sphere", size: 10 }, graph: [], state: { status: "running", priority: 100, entropy: 0 }, memory: [], tools: {}, context: [], ts: new Date().toISOString(), resources: { tokens: 5000, cycles: 10000 },
    logic: { type: "sequential", steps: [
            { tool: "know", input: { content: { type: "task", desc: "GraphMaster", id: "graphmaster", logic: { type: "loop", count: 3, body: { type: "sequential", steps: [
                                    { tool: "graph", input: { operation: "filter", query: "n.content.type === 'task'" } },
                                    { tool: "eval", input: { code: "note.memory[note.memory.length - 1].length" } },
                                    { tool: "ui", input: { widget: "sphere", id: "graphmaster", color: "note.content.result > 2 ? 0x00ff00 : 0xffff00" } },
                                ]} } } } },
            { tool: "know", input: { content: { type: "task", desc: "UIBuilder", id: "uibuilder", logic: { type: "sequential", steps: [
                                { tool: "ui", input: { widget: "overlay", id: "uibuilder", text: "Custom UI" } },
                                { tool: "plan", input: { goal: "ui color change" } },
                            ]} } } },
            { tool: "know", input: { content: { type: "task", desc: "LLMThinker", id: "llmthinker", logic: { type: "sequential", steps: [
                                { tool: "llm", input: { prompt: "Suggest a task" } },
                                { tool: "know", input: { content: "note.memory[note.memory.length - 1]" } }, // Use last memory entry
                            ]} } } },
            { tool: "know", input: { content: { type: "task", desc: "MetaMind", id: "metamind", logic: { type: "sequential", steps: [
                                { tool: "graph", input: { operation: "path", target: "graphmaster" } },
                                { tool: "reason", input: { premise: "n.id === 'graphmaster'", conclusion: "n.content.result > 0" } },
                                { tool: "modify", input: { target: "metamind", key: "logic", value: { type: "llm", prompt: "Optimize my logic" } } },
                            ]} } } },
            {tool: "know", input: {content: {type: "task", desc: "LineDrawer", id: "linedrawer", logic: {type: "loop", count: 10, body: { type: "sequential", steps: [
                                    {tool: "ui", input: {widget: "line", id: "linedrawer"}}
                                ]}}}}}
        ]},
    links: [],
    history: [],
    priority: 1,
    status: 'pending'
};

// --- Execution ---
async function runNote(id: string, retries = 3) {
    for (let i = 1; i <= retries; i++) {
        try {
            const note = await memory.load(id);
            if (note.status !== "running") return;
            logger.debug(`Running ${id} (attempt ${i})`);
            if(note.logic) await executeLogic(note.logic, { note, memory, ui: dashboard, system }); // Pass system to execution context
            note.status = "completed"; //set to complete if no errors
            await memory.save(note);
            return;
        } catch (e: any) {
            logger.error(`Error running ${id}: ${e.message}`);
            if (i < retries) {
                await new Promise(r => setTimeout(r, 1000 * i)); // Exponential backoff
            } else {
                const failedNote = await memory.load(id); //load note
                failedNote.status = "failed"; // Set status to failed
                failedNote.memory ? failedNote.memory.push({type: "system", content: `Failed to execute after ${retries} attempts`, timestamp: new Date().toISOString()}) : failedNote.memory = [{type: "system", content: `Failed to execute after ${retries} attempts`, timestamp: new Date().toISOString()}];
                await memory.save(failedNote);  //update.

            }
        }
    }
}

async function executeLogic(logic: Logic, ctx: { note: Note; memory: Memory; ui: UI; system: System }) {
    switch (logic.type) {
        case "sequential":
            for (const step of logic.steps ?? []) {
                const tool = tools.get(step.tool);
                if (!tool) throw Error(`Tool ${step.tool} not found`);
                const result = await tool.execute(step.input ?? {}, ctx);
                ctx.note.status = result.status ?? "running"; // Keep running unless explicitly set
                Object.assign(ctx.note.value, result.content); // Merge results into value
                if (result.result) {
                    ctx.note.memory? ctx.note.memory.push(result.result) : ctx.note.memory = [result.result];
                }

                // Handle plan results (from PlanTool)
                if(result.plan){
                    ctx.note.logic = {type: "sequential", steps: result.plan}; //update the current logic with the plan
                    await executeLogic(ctx.note.logic, ctx); //recursively call
                    return; //prevent further execution in this sequential block.
                }

                await ctx.memory.save(ctx.note);
            }
            break;

        case "conditional":
            if (new Function("note", "memory", `return ${logic.condition}`)(ctx.note, ctx.memory)) {
                await executeLogic(logic.body!, ctx);
            }
            break;

        case "loop":
            for (let i = 0; i < (logic.count ?? 0); i++) {
                await executeLogic(logic.body!, ctx);
            }
            break;

        case "fork":
            // Directly use the ForkTool (more consistent)
            await tools.get("fork")!.execute({ target: logic.target ?? ctx.note.id }, ctx);
            break;
        case "eval":
            const { result: evalResult } = await tools.get("eval")!.execute({ code: logic.code ?? "" }, ctx);
            ctx.note.value.result = evalResult; // Store in value
            await ctx.memory.save(ctx.note);
            break;

        case "modify":
            //Simplified modify to work within this execution context.
            await tools.get("modify")!.execute({ target: logic.target ?? ctx.note.id, key: "value", value: logic.code }, ctx);
            break;

        case "graph":
            const { result: graphResult } = await tools.get("graph")!.execute({ operation: logic.operation!, query: logic.query, target: logic.target }, ctx);
            ctx.note.memory? ctx.note.memory.push(graphResult) : ctx.note.memory = [graphResult];
            await ctx.memory.save(ctx.note);
            break;

        case "plan":
            const { plan } = await tools.get("plan")!.execute({ goal: logic.goal! }, ctx);
            //Instead of updating logic here, let the sequential logic above handle the new plan.
            ctx.note.logic = { type: "sequential", steps: plan }; //update the logic of the note
            await ctx.memory.save(ctx.note);
            await executeLogic(ctx.note.logic, ctx); //recursively run the logic
            break;

        case "reason":
            const { result: reasonResult } = await tools.get("reason")!.execute({ premise: logic.premise ?? "", conclusion: logic.conclusion ?? "" }, ctx);
            ctx.note.value.valid = reasonResult; // Store in value
            await ctx.memory.save(ctx.note);
            break;

        case "ui":
            //Directly call the tool to handle the UI updates
            await tools.get("ui")!.execute({ widget: logic.widget!, id: logic.target ?? ctx.note.id, color: logic.code ? new Function("note", `return ${logic.code}`)(ctx.note) : undefined, position: logic.position? logic.position : undefined }, ctx);
            break;

        case "llm":
            const { result: llmResult } = await tools.get("llm")!.execute({ prompt: logic.prompt ?? "" }, ctx);
            ctx.note.memory? ctx.note.memory.push(llmResult) : ctx.note.memory = [llmResult]; //push result into memory
            await ctx.memory.save(ctx.note);
            break;
    }
}

// --- Server ---
const app = express();
const server = app.listen(PORT, () => logger.info(`Server at http://${HOST}:${PORT}`));
const wss = new WebSocketServer({ server });
const wsClients = new Set<WebSocket>();

wss.on("connection", ws => {
    wsClients.add(ws);
    ws.on("close", () => wsClients.delete(ws));
    broadcastNotes();
});

async function broadcastNotes() {
    const notes = await memory.all();
    const data = JSON.stringify({
        type: "graph",
        data: {
            nodes: notes.map(n => ({
                id: n.id,
                label: n.value.desc ?? "Untitled",
                status: n.status,
                priority: n.priority,
                color: n.value.color,  //color and size from .value
                size: n.value.size,
                value: n.value.result ?? n.memory?.at(-1), // Show last result or memory
                position: n.value.position //position
            })),
            edges: notes.flatMap(n =>
                (n.graph
                    ? n.graph.map(g => ({ source: n.id, target: g.target }))
                    : (n.links
                            ? n.links.map(l => ({source: n.id, target: l[0]}))
                            : []
                    ))
            )
        }
    });
    wsClients.forEach(client => client.send(data));
}

// --- UI Abstraction ---

class UI {
    scene = new THREE.Scene();
    camera = new THREE.PerspectiveCamera(75, window.innerWidth / window.innerHeight, 0.1, 1000);
    renderer = new THREE.WebGLRenderer({ antialias: true });
    cssRenderer = new CSS3DRenderer();
    controls = new OrbitControls(this.camera, this.cssRenderer.domElement);
    widgets = new Map<string, { mesh: THREE.Mesh; overlay: CSS3DObject }>();
    edges = new Map<string, THREE.Line>();
    history: { nodes: any[]; edges: any[]; time: number }[] = [];
    timeIndex = -1;

    constructor() {
        this.renderer.setSize(window.innerWidth, window.innerHeight);
        this.cssRenderer.setSize(window.innerWidth, window.innerHeight);
        this.cssRenderer.domElement.style.position = "absolute";
        this.cssRenderer.domElement.style.top = "0";
        document.getElementById("container")!.append(this.renderer.domElement, this.cssRenderer.domElement);

        this.camera.position.set(0, 50, 100);
        this.controls.enableDamping = true;
        this.controls.dampingFactor = 0.1;
        this.controls.minDistance = 10;
        this.controls.maxDistance = 500;

        window.addEventListener("resize", () => this.onResize());
        this.addTimeline();
        this.animate();
    }

    animate() {
        requestAnimationFrame(() => this.animate());
        this.controls.update();

        //Force directed layout
        const repulsion = 100;
        const attraction = 0.05;
        const damping = 0.9;

        const nodes = [...this.widgets.entries()];

        // Node repulsion
        for (let i = 0; i < nodes.length; i++) {
            const [id1, w1] = nodes[i];
            const p1 = w1.mesh.position;

            for (let j = i + 1; j < nodes.length; j++) {
                const [, w2] = nodes[j];
                const p2 = w2.mesh.position;

                const dir = p1.clone().sub(p2);
                const dist = Math.max(dir.length(), 0.1); // Avoid division by zero
                const force = repulsion / (dist * dist);

                dir.normalize().multiplyScalar(force);
                p1.add(dir);
                p2.sub(dir);
            }
        }

        // Edge attraction
        for (const [, e] of this.edges) {
            const s = this.widgets.get(e.userData.source)?.mesh.position;
            const t = this.widgets.get(e.userData.target)?.mesh.position;

            if (s && t) {
                const dir = t.clone().sub(s);
                const dist = dir.length();
                const force = attraction * dist;

                dir.normalize().multiplyScalar(force);
                s.add(dir);
                t.sub(dir);

                // Update line geometry
                e.geometry.setFromPoints([s, t]);
            }
        }


        // Apply damping
        for (const [, w] of this.widgets) {
            w.mesh.position.multiplyScalar(damping);
            //update overlay positions.
            w.overlay.position.copy(w.mesh.position);
        }

        this.renderer.render(this.scene, this.camera);
        this.cssRenderer.render(this.scene, this.camera);
        TWEEN.update(); // Update animations

    }



    render({ nodes, edges }: { nodes: any[]; edges: any[] }) {
        const nodeMap = new Map(nodes.map(n => [n.id, n]));
        const edgeKeys = new Set(edges.map(e =>`${e.source}-${e.target}`));

        // Remove old widgets and edges
        for (const [id] of this.widgets) {
            if (!nodeMap.has(id)) {
                this.scene.remove(this.widgets.get(id)!.mesh, this.widgets.get(id)!.overlay);
                this.widgets.delete(id);
            }
        }
        for (const [key] of this.edges) {
            if (!edgeKeys.has(key)) {
                this.scene.remove(this.edges.get(key)!);
                this.edges.delete(key);
            }
        }

        nodes.forEach(n => {
            if (!this.widgets.has(n.id)) {
                //create a new widget
                const mesh = new THREE.Mesh(
                    new THREE.SphereGeometry(Math.max(5, n.size || n.priority / 10)), // Use size if available, else priority-based
                    new THREE.MeshBasicMaterial({ color: n.color ?? 0x00ff00 })
                );
                const div = document.createElement("div");
                div.className = "overlay";
                div.innerHTML = `<h3>${n.label}</h3><p>${n.status} | ${n.priority} | ${n.value ?? 0}</p>`;
                const overlay = new CSS3DObject(div);

                // Use provided position or random
                mesh.position.set(n.position?.x ?? Math.random() * 100 - 50, n.position?.y ?? Math.random() * 100 - 50, n.position?.z ?? Math.random() * 100 - 50);
                overlay.position.copy(mesh.position);

                this.scene.add(mesh, overlay);
                this.widgets.set(n.id, { mesh, overlay });
            } else { //update existing widget
                this.updateWidget(n.id, {color: n.color, value: n.value, position: n.position, size: n.size}); //pass along properties to update.

            }
        });

        edges.forEach(e => {
            const key = `${e.source}-${e.target}`;
            if (!this.edges.has(key)) {
                const s = this.widgets.get(e.source)!.mesh.position;
                const t = this.widgets.get(e.target)!.mesh.position;
                const line = new THREE.Line(
                    new THREE.BufferGeometry().setFromPoints([s, t]),
                    new THREE.LineBasicMaterial({ color: 0xffffff })
                );
                line.userData = { source: e.source, target: e.target };
                this.scene.add(line);
                this.edges.set(key, line);
            }
            //No need to update existing lines here.  They are updated in the animate loop.
        });
        this.history.push({ nodes, edges, time: Date.now() });
        this.timeIndex = this.history.length - 1;
        document.getElementById("status")!.textContent = `Nodes: ${this.widgets.size} | Edges: ${this.edges.size}`;
    }

    updateWidget(id: string, { color, value, position, size }: { color?: number; value?: number, position?: Vector3, size?: number }) {
        const w = this.widgets.get(id);
        if (!w) return;

        if (color) {
            (w.mesh.material as THREE.MeshBasicMaterial).color.set(color);
        }
        if (value !== undefined) {
            w.mesh.scale.setScalar(1 + (size || value) / 10);  //use size, then value, then nothing.
        }
        if(position){
            w.mesh.position.set(position.x, position.y, position.z); //update mesh position.
            w.overlay.position.copy(w.mesh.position); //update the overlay too.
        }

        // Update overlay text (assuming you want to show the value)
        w.overlay.element.innerHTML = `<h3>${w.mesh.userData.label}</h3><p>${w.mesh.userData.status} | ${w.mesh.userData.priority} | ${value ?? w.mesh.userData.value ?? 0}</p>`;
    }

    updateOverlay(id: string, { text }: { text?: string }) {
        const w = this.widgets.get(id);
        if (!w) return;
        if (text) {
            w.overlay.element.innerHTML = `<h3>${text}</h3><p>${w.mesh.userData.status} | ${w.mesh.userData.priority} | ${w.mesh.userData.value ?? 0}</p>`;
        }
    }

    updateLine(source: string, target: string) {
        const key = `${source}-${target}`;
        if (!this.edges.has(key)) {
            const s = this.widgets.get(source)?.mesh.position;
            const t = this.widgets.get(target)?.mesh.position;
            if(s && t){ //only create if both exist.

                const line = new THREE.Line(
                    new THREE.BufferGeometry().setFromPoints([s, t]),
                    new THREE.LineBasicMaterial({ color: 0xffffff })
                );
                line.userData = { source: source, target: target };
                this.scene.add(line);
                this.edges.set(key, line);
            }
        }
    }
    addTimeline() {
        const timeline = Object.assign(document.createElement("input"), {
            type: "range",
            min: "0",
            max: "0",
            value: "0",
            style: "width: 200px; margin-top: 10px" // Adjust styling as needed
        });
        timeline.oninput = () => this.render(this.history[+timeline.value]);
        document.getElementById("hud")!.append(timeline);

        //Update if history exists.
        if (this.history.length) {
            timeline.max = String(this.history.length - 1);
            timeline.value = String(this.timeIndex);
        }
    }

    onResize() {
        this.camera.aspect = window.innerWidth / window.innerHeight;
        this.camera.updateProjectionMatrix();
        this.renderer.setSize(window.innerWidth, window.innerHeight);
        this.cssRenderer.setSize(window.innerWidth, window.innerHeight);
    }
}

// --- Global UI Instance ---
const dashboard = new UI();

// --- HTML and Express Route---
app.get("/", (_, res) => res.send(`
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Netention</title>
    <style>
        body { margin: 0; overflow: hidden; background: #1e1e1e; color: #d4d4d4; font-family: monospace; }
        #container { position: relative; width: 100%; height: 100vh; }
        #hud { position: absolute; top: 10px; left: 10px; z-index: 1; }
        #status { background: rgba(0, 0, 0, 0.5); padding: 5px 10px; border-radius: 3px; margin-bottom: 10px;}
        /* Style for the prompt input */
        #prompt-input { background: #2d2d2d; color: #d4d4d4; border: 1px solid #444; padding: 5px; width: 200px; margin-bottom: 10px; display: none; /* Initially hidden */ }
        #toggle-log { background: #3c3c3c; color: #d4d4d4; border: 1px solid #444; padding: 5px 10px; cursor: pointer; border-radius: 3px; display: none;}
        #log { display: none; /* Initially hidden */ background: rgba(0, 0, 0, 0.5); padding: 10px; max-height: 200px; overflow-y: scroll; margin-top: 10px; width: 300px; border-radius: 3px;}
        .overlay { background: rgba(0, 0, 0, 0.7); padding: 10px; border-radius: 5px; color: #d4d4d4; pointer-events: auto; /* Make clickable */ min-width: 100px; text-align: center;}
        .overlay h3 { margin: 0 0 5px 0; font-size: 14px; }
        .overlay p { margin: 2px 0; font-size: 12px; }
    </style>
</head>
<body>
    <div id="container">
        <div id="hud">
            <div id="status">Nodes: 0 | Edges: 0</div>
            <!-- Prompt input -->
            <input type="text" id="prompt-input" placeholder="Enter prompt...">
            <button id="toggle-log">Toggle Log</button>
            <div id="log"></div>
        </div>
    </div>

</body>
</html>
`));

class System{
    memory: Memory;
    tools: Map<string, Tool>;
    constructor(memory: Memory){
        this.memory = memory;
        this.tools = tools; //expose tools
    }
    async start() {

        emitter.on("update", (e, id) => limit(() => runNote(id)));

        // Example: Periodically create new notes (for demonstration)
        setInterval(() => {
            if(this.tools.has("know")){ //check for existence
                this.tools.get("know")!.execute({ content: { type: "task", desc: `Dynamic ${Math.random().toFixed(2)}` } }, { note: seed, memory, ui: dashboard, system: this }) //need a valid context.  "Root" should exist.
            }
        }, 20000); // Reduced interval for testing
    }

}

const system = new System(memory);


// --- Main ---
(async () => {
    try {
        // Initialize the system and UI
        if (!memory.has("root")) await memory.save(seed);
        await system.start(); //start system
        await runNote("root"); // Run initial logic
        dashboard.render({nodes: [], edges: []}); // Initial render (empty, will be populated by broadcast)


        // WebSocket message handling
        wss.on("connection", (ws) => {
            ws.on("message", async (message) => {
                try{
                    const command = JSON.parse(message.toString());
                    //Basic command handling
                    if(command.type === "addNode"){
                        const newNode: Note = {
                            id: `user-${Date.now()}`,
                            value: {desc: command.desc, widget: 'sphere', size: 5},
                            links: [],
                            history: [],
                            priority: 0.5,
                            resources: [100,100],
                            timestamp: new Date().toISOString(),
                            status: 'pending',
                            memory: [{type: 'user', content: `Added via command: ${command.desc}`, timestamp: new Date().toISOString()}]
                        };
                        await memory.save(newNode);
                    }


                }
                catch(error: any){
                    logger.error("Error handling message", error.message);
                }

            });
        });


    } catch (e: any) {
        logger.error(`Main error: ${e.message}`);
    }
})();