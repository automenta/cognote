// seed.ts
export const seed: Note = {
    id: "root",
    content: {
        type: "system",
        desc: "Netention",
        config: { maxMemory: 50, tickRate: 10, tokenBudget: 5000, defaultPriority: 50 }
    },
    graph: [],
    state: { status: "running", priority: 100, entropy: 0 },
    memory: [],
    tools: {},
    context: [],
    ts: new Date().toISOString(),
    resources: { tokens: 5000, cycles: 10000 },
    logic: {
        type: "sequential",
        steps: [
            { tool: "know", input: { content: { type: "tool", name: "know", desc: "Create Note", execute: "kv.put" } } },
            { tool: "know", input: { content: { type: "UI", id: "ui-tree", desc: "Activity Tree", state: { status: "running", priority: 80 }, logic: { type: "sequential", steps: [{ tool: "ui_render", input: { target: "tree", desc: "Render tree" } }] } } } },
            { tool: "know", input: { content: { type: "UI", id: "ui-log", desc: "Log Display", state: { status: "running", priority: 70 }, logic: { type: "sequential", steps: [{ tool: "ui_render", input: { target: "log", desc: "Render log" } }] } } } },
            { tool: "know", input: { content: { type: "UI", id: "ui-prompt", desc: "Input Prompt", state: { status: "running", priority: 60 }, logic: { type: "sequential", steps: [{ tool: "ui_input", input: {} }] } } } },
            { tool: "know", input: { content: { type: "UI", id: "ui-status", desc: "Execution Status", content: { paused: false }, state: { status: "running", priority: 90 }, logic: { type: "sequential", steps: [{ tool: "ui_control", input: { desc: "Update status" } }] } } } }
        ]
    }
};

export type Note = {
    id: string;
    content: any;
    graph: { target: string; rel: string }[];
    state: { status: string; priority: number; entropy: number };
    memory: string[];
    tools: Record<string, string>;
    context: string[];
    ts: string;
    resources: { tokens: number; cycles: number };
    logic: any;
};