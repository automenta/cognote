// index.ts
import pc from "ansi-colors";
const { red, blue, yellow, green, bold } = pc;
import { EventEmitter } from "node:events";
import pLimit from "p-limit";
import { seed, Note } from "./seed.js";
import * as readline from "node:readline";
import { ChatGoogleGenerativeAI } from "@langchain/google-genai";
import { StructuredTool } from "@langchain/core/tools";
import { z } from "zod";
import * as winston from "winston";
import express from "express";
import { WebSocketServer } from "ws";
import { join } from "path";

// --- Constants ---
const MAX_RETRIES = 3;
const PORT = process.env.PORT || 8000;
const HOST = "localhost";

// --- Logger Setup ---
export const logger = winston.createLogger({
    level: "debug",
    format: winston.format.combine(
        winston.format.timestamp(),
        winston.format.printf(({ timestamp, level, message }) => `${timestamp} ${level}: ${message}`)
    ),
    transports: [new winston.transports.Console()],
});

export abstract class Memory {
    abstract saveNote(n: Note): Promise<void>;
    abstract loadNote(id: string): Promise<Note>;
    abstract initialize(): Promise<void>; // Add initialization method
}

// --- Memory Abstraction ---
class KeyValueMemory extends Memory {
    private kv = new Map<string, Note>();

    async initialize(): Promise<void> {
        // No-op
    }

    async saveNote(n: Note): Promise<void> {
        this.kv.set(n.id, n);
        emitter.emit("update", "change", n.id);
        logger.debug(`Saved note ${n.id}`);
        broadcastNotes();
    }

    async loadNote(id: string): Promise<Note> {
        const note = this.kv.get(id);
        if (!note) throw new Error(`Note ${id} not found`);
        logger.debug(`Loaded note ${id}`);
        return note;
    }

    async getAllNotes(): Promise<Note[]> {
        return Array.from(this.kv.values());
    }
}


const memory = new KeyValueMemory();

// --- Setup ---
export const emitter = new EventEmitter();
const limit = pLimit(10);
const llm = new ChatGoogleGenerativeAI({ model: "gemini-2.0-flash", temperature: 1, maxRetries: 2 });

// --- Tools ---
class KnowTool extends StructuredTool {
    name = "know";
    description = "Creates a new Note";
    schema = z.object({ content: z.any() });

    async _call({ content }: { content: any }) {
        const newNote: Note = {
            id: content.id || `${content.type || "note"}-${Date.now()}`,
            content,
            state: content.state || { status: "running", priority: 50, entropy: 0 },
            graph: [],
            memory: [],
            tools: {},
            context: content.context || ["root"],
            ts: new Date().toISOString(),
            resources: content.resources || { tokens: 100, cycles: 100 },
            logic: content.logic || {}
        };
        await memory.saveNote(newNote);

        // Update parent's graph
        const parentId = newNote.context[0] || "root";
        const parentNote = await memory.loadNote(parentId);
        parentNote.graph = parentNote.graph || [];
        if (!parentNote.graph.some(g => g.target === newNote.id)) {
            parentNote.graph.push({ target: newNote.id, rel: "contains" });
            await memory.saveNote(parentNote);
            logger.debug(`Updated ${parentId}.graph with ${newNote.id}`);
        }

        if (newNote.content.name !== "know") await runNote(newNote.id);
        logger.info(`${green("🟢")} Created ${newNote.id}`);
        broadcastNotes();
        return { status: "done", content: newNote };
    }
}

class UIRenderTool extends StructuredTool {
    name = "ui_render";
    description = "Renders console UI";
    schema = z.object({ target: z.enum(["tree", "log"]).optional(), desc: z.string().optional() }).optional();

    async _call(input: { target?: "tree" | "log"; desc?: string } = {}) {
        const target = input.target || "tree";
        const desc = input.desc || "UI render";
        const statusNote = await memory.loadNote("ui-status").catch(() => ({ content: { paused: false } }));
        const status = statusNote.content.paused ? yellow("PAUSED") : green("RUNNING");

        if (target === "tree") {
            const root = await memory.loadNote("root");
            const allNotes = root.graph && root.graph.length > 0
                ? await Promise.all(root.graph.map(g => memory.loadNote(g.target).catch(() => null))).then(notes => notes.filter(Boolean))
                : [];
            const notes = allNotes.filter(n => n.state.status === "running" || n.state.status === "pending").sort((a, b) => b.state.priority - a.state.priority);
            let output = `${bold("Netention")} - ${status}\n${blue("Active Notes:")}\n`;
            for (const n of notes) {
                const s = n.state.status === "running" ? green("●") : yellow("○");
                output += `${s} ${n.id} (${n.content.desc || "Untitled"}) [${n.state.priority}]\n`;
            }
            //console.clear();
            console.log(output);
        } else if (target === "log") {
            const logOutput = logger.transports[0].silent ? "" : logger.transports[0].lastLog || "";
            console.log(`${blue("Log:")}\n${logOutput}`);
        }
        process.stdout.write("> ");
        broadcastNotes();
        return { status: "running", memory: `${desc}` };
    }
}

class UIInputTool extends StructuredTool {
    name = "ui_input";
    description = "Handles user input";
    schema = z.object({}).optional();

    async _call(_: any = {}) {
        const rl = readline.createInterface({ input: process.stdin, output: process.stdout, prompt: "> " });
        rl.prompt();
        rl.on("line", async (input) => {
            input = input.trim();
            if (input === "pause" || input === "resume") {
                const task: Note = {
                    id: `control-${Date.now()}`,
                    content: { type: "task", desc: input, logic: { type: "sequential", steps: [{ tool: "ui_control", input: { command: input, desc: "Toggle execution" } }] } },
                    state: { status: "running", priority: 90, entropy: 0 },
                    graph: [],
                    memory: [],
                    tools: {},
                    context: ["ui-prompt"],
                    ts: new Date().toISOString(),
                    resources: { tokens: 100, cycles: 100 },
                    logic: {}
                };
                await memory.saveNote(task);
            } else if (input.startsWith("know")) {
                const [_, type, desc] = input.match(/know\s+(\w+)\s+"([^"]+)"/) || [];
                if (type && desc) {
                    const newNote: Note = {
                        id: `${type}-${Date.now()}`,
                        content: { type, desc },
                        state: { status: "pending", priority: 50, entropy: 0 },
                        graph: [],
                        memory: [],
                        tools: {},
                        context: ["root"],
                        ts: new Date().toISOString(),
                        resources: { tokens: 100, cycles: 100 },
                        logic: {}
                    };
                    await memory.saveNote(newNote);
                }
            }
            rl.prompt();
        });
        return { status: "running" };
    }
}

class UIControlTool extends StructuredTool {
    name = "ui_control";
    description = "Controls execution state";
    schema = z.object({ command: z.string().optional(), desc: z.string().optional() }).optional();

    async _call({ command, desc }: { command?: string; desc?: string } = {}) {
        let statusNote = await memory.loadNote("ui-status").catch(() => null);
        if (!statusNote) {
            statusNote = {
                id: "ui-status",
                content: { paused: false },
                state: { status: "running", priority: 90, entropy: 0 },
                graph: [],
                memory: [],
                tools: {},
                context: ["root"],
                ts: new Date().toISOString(),
                resources: { tokens: 100, cycles: 100 },
                logic: {}
            };
            await memory.saveNote(statusNote);
        }

        if (command === "pause") statusNote.content.paused = true;
        else if (command === "resume") statusNote.content.paused = false;

        await memory.saveNote(statusNote);
        logger.info(`${statusNote.content.paused ? yellow("⏸️") : green("▶️")} ${desc || "Status update"}`);
        broadcastNotes();
        return { status: "running", content: statusNote.content };
    }
}

const app = express();
const server = app.listen(PORT, () => {
    logger.info(`${green("🌐")} Web server running at http://${HOST}:${PORT}`);
});
const wss = new WebSocketServer({ server });
const wsClients: Set<any> = new Set();

wss.on("connection", (ws) => {
    wsClients.add(ws);
    ws.on("close", () => wsClients.delete(ws));
    broadcastNotes();
});

app.use(express.static("ui"));

async function broadcastNotes() {
    const notes = await memory.getAllNotes();
    const data = JSON.stringify({ type: "graph", data: { nodes: notes.map(n => ({
                id: n.id,
                label: n.content.desc || "Untitled",
                status: n.state.status,
                priority: n.state.priority,
                type: n.content.type || "N/A",
                context: n.context,
                ts: n.ts
            })), edges: notes.flatMap(n => n.graph.map(g => ({ source: n.id, target: g.target }))) } });
    wsClients.forEach(client => client.send(data));
}

// --- Execution Logic ---
async function runNote(id: string, retries = MAX_RETRIES): Promise<void> {
    for (let attempt = 1; attempt <= retries; attempt++) {
        try {
            const note = await memory.loadNote(id);
            const statusNote = await memory.loadNote("ui-status").catch(() => ({ content: { paused: false } }));
            if (statusNote.content.paused || note.state.status !== "running") return;

            logger.info(`${blue("▶️")} Running ${id} (Attempt ${attempt})`);
            const tools = [new KnowTool(), new UIRenderTool(), new UIInputTool(), new UIControlTool()];

            if (note.logic && note.logic.type === "sequential" && note.logic.steps) {
                for (const step of note.logic.steps) {
                    const tool = tools.find(t => t.name === step.tool);
                    if (!tool) throw new Error(`Tool ${step.tool} not found`);
                    const result = await tool.call(step.input || {});
                    note.state.status = result.status || "done";
                    Object.assign(note.content, result.content);
                    if (result.memory) note.memory.push(await saveMemory(note.id, result.memory));
                }
            } else if (note.content.name) {
                const tool = tools.find(t => t.name === note.content.name);
                if (!tool) throw new Error(`Tool ${note.content.name} not found`);
                const result = await tool.call(note.content);
                note.state.status = result.status || "done";
                Object.assign(note.content, result.content);
                if (result.memory) note.memory.push(await saveMemory(note.id, result.memory));
            }

            await memory.saveNote(note);
            return;
        } catch (err: any) {
            logger.error(`${red("❌")} Error running ${id}: ${err.message}`);
            if (attempt === retries) logger.error(`${red("🚨")} Max retries reached for ${id}`);
            else await new Promise(resolve => setTimeout(resolve, 1000 * attempt));
        }
    }
}

async function saveMemory(parentId: string, entry: string): Promise<string> {
    const id = `${parentId}-${Date.now()}`;
    const memoryNote: Note = {
        id,
        content: entry,
        state: { status: "done", priority: 0, entropy: 0 },
        graph: [],
        memory: [],
        tools: {},
        context: [parentId],
        ts: new Date().toISOString(),
        resources: { tokens: 0, cycles: 0 },
        logic: {}
    };
    await memory.saveNote(memoryNote);
    return id;
}

async function handleEvent(event: string, id: string) {
    if (event === "change") await limit(() => runNote(id));
}

async function main() {
    logger.info(`${green("🚀")} Starting Netention`);
    await memory.initialize();

    try {
        await memory.loadNote("root");
    } catch {
        logger.info("Seeding initial data...");
        await memory.saveNote(seed);
    }
    await runNote("root");

    emitter.on("update", handleEvent);

    // UI Update Loop
    setInterval(async () => {
        await new UIRenderTool().call({ target: "tree", desc: "Activity Tree" });
        await new UIRenderTool().call({ target: "log", desc: "Log Display" });
    }, 500);
}

main().catch(err => logger.error(`${red("❌")} Main error: ${err.message}`));