import * as z from 'zod';

export const NoteSchema = z.object({
    id: z.string().uuid().describe("Unique identifier for the note (UUID format)."), // Enforce UUID format
    type: z.enum([
        "Root", "Task", "Plan", "Step", "Tool", "Memory",
        "System", "Data", "Prompt", "Config", "custom", "langchain", "api", "Template"
    ]).default("Task").describe("Type of the note, defining its purpose and behavior."), // Use enum for predefined types
    title: z.string().min(1).max(255).default("Untitled Note").describe("Title of the note (1-255 characters)."), // Enforce length constraints
    content: z.record(z.any()).optional().describe("Content of the note (can be any data type)."), // Standardize content as a record
    logic: z.any().optional().describe("Logic associated with the note (can be any data type)."),
    status: z.enum(["pending", "active", "running", "completed", "failed", "dormant", "bypassed", "pendingRefinement"]).default("pending").describe("Status of the note, indicating its current state."), // Use enum for predefined statuses
    priority: z.number().int().min(0).max(100).default(0).describe("Priority of the note (0-100, integer)."), // Enforce number range and integer type
    createdAt: z.string().datetime().default(() => new Date().toISOString()).describe("Date and time when the note was created (ISO 8601 format)."), // Enforce datetime format
    updatedAt: z.string().datetime().nullable().describe("Date and time when the note was last updated (ISO 8601 format, nullable)."), // Enforce datetime format and nullability
    inputSchema: z.record(z.any()).optional().describe("Input schema for the note (JSON format)."),
    outputSchema: z.record(z.any()).optional().describe("Output schema for the note (JSON format)."),
    references: z.array(z.string().uuid()).default([]).describe("List of IDs of notes that this note references (UUID format)."), // Enforce UUID format for references
    config: z.record(z.any()).optional().describe("Configuration settings for the note (key-value pairs)."),
    description: z.string().max(1000).optional().describe("Description of the note (max 1000 characters)."), // Enforce length constraint
    requiresWebSearch: z.boolean().optional().describe("Indicates whether the note requires a web search to be completed."), // Add requiresWebSearch property
});

export type Note = z.infer<typeof NoteSchema>;

export interface TaskLogic {
    steps: WorkflowStep[];
}

export interface WorkflowStep {
    id: string;
    type: 'tool';
    toolId: string;
    input: any;
}

export interface SystemMessage {
    type: 'system';
    content: string;
    timestamp: string;
    level?: 'info' | 'warning' | 'error';
}
