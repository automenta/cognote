// src/lib/planningRules.ts
import {Note} from '../types';
import {SystemNote} from './systemNote';
import idService from './idService';
import {systemLog} from './systemLog';

/**
 * Represents a planning rule that can be applied to a note.
 */
export interface PlanningRule {
    /**
     * The name of the planning rule.
     */
    name: string;
    /**
     * Specifies when the rule should be applied ('before' or 'after' note execution).
     */
    order: 'before' | 'after';
    /**
     * An optional condition that must be met for the rule to be applied.
     * Can be a synchronous or asynchronous function.
     */
    condition?: (task: Note, system: SystemNote) => boolean | Promise<boolean>;
    /**
     * An optional action to be performed when the rule is applied.
     */
    action?: (task: Note, system: SystemNote) => Promise<void>;
}

/**
 * Determines if a web search step should be added to a task.
 *
 * This function prioritizes user-defined settings (`task.requiresWebSearch`) and
 * falls back to analyzing the task description for keywords if an LLM is not available
 * or if the LLM call fails.
 *
 * @param {Note} task - The task to analyze.
 * @param {SystemNote} system - The system note instance.
 * @returns {Promise<boolean>} - True if a web search step should be added, false otherwise.
 */
const shouldAddWebSearch = async (task: Note, system: SystemNote): Promise<boolean> => {
    // Prioritize user-defined setting
    if (task.requiresWebSearch !== undefined && task.requiresWebSearch !== null) {
        systemLog.debug(`Using user-defined requiresWebSearch: ${task.requiresWebSearch} for task ${task.title}`, 'PlanningRules');
        return task.requiresWebSearch;
    }

    // Check if task description is available
    if (!task.description) {
        systemLog.debug(`Task description is missing for task ${task.title}, skipping web search.`, 'PlanningRules');
        return false;
    }

    // Fallback to keyword check
    const keywords = ["search", "find", "research", "investigate", "what is", "what are", "how to"];
    const shouldSearch = keywords.some(keyword => task.description.toLowerCase().includes(keyword));
    systemLog.info(`Using keyword-based check, web search ${shouldSearch ? 'recommended' : 'not recommended'} for task ${task.title}.`, 'PlanningRules');
    return shouldSearch;
};

/**
 * An array of planning rules that are applied to notes.
 */
const planningRules: PlanningRule[] = [
    {
        name: "Decompose Complex Task (Before)",
        order: 'before',
        condition: (task: Note, system: SystemNote) => {
            return task.priority > 75 && task.content.messages?.length === 0; // High priority, vague description
        },
        action: async (task: Note, system: SystemNote) => {
            systemLog.info(`[BEFORE] Decomposing complex task: ${task.title}`, 'PlanningRules');
            // In a real implementation, this would use the LLM to generate sub-tasks
            const subTask1: Note = {
                id: idService.generateId(),
                type: 'Task',
                title: `Sub-task 1 of ${task.title}`,
                content: {messages: []},
                status: 'pending',
                priority: task.priority - 10,
                createdAt: new Date().toISOString(),
                updatedAt: null,
                references: [task.id],
                description: `A subtask of ${task.title}`,
            };
            system.addNote(subTask1);
            task.references = [...(task.references ?? []), subTask1.id];
            system.updateNote(task);
            systemLog.info(`Created subtask: ${subTask1.title}`, 'PlanningRules');
        }
    },
    {
        name: "Add Web Search Step (Before)",
        order: 'before',
        condition: async (task: Note, system: SystemNote) => {
            return await shouldAddWebSearch(task, system);
        },
        action: async (task: Note, system: SystemNote) => {
            systemLog.info(`[BEFORE] Adding web search step to task: ${task.title}`, 'PlanningRules');
            // In a real implementation, this would modify the task's logic to include a web search tool step
            task.logic = JSON.stringify({
                steps: [
                    {
                        id: idService.generateId(),
                        type: "tool",
                        toolId: system.getAllTools().find(tool => tool.title === "Web Search Tool")?.id,
                        input: "{query: task.description}"
                    }
                ]
            });
            system.updateNote(task);
            systemLog.info(`Added web search step to task: ${task.title}`, 'PlanningRules');
        }
    },
    {
        name: "Reflect and Create Subtasks (After)",
        order: 'after',
        condition: (task: Note, system: SystemNote) => {
            return task.status === 'completed' && task.references.length === 0; // Task completed, no subtasks
        },
        action: async (task: Note, system: SystemNote) => {
            systemLog.info(`[AFTER] Reflecting and creating subtasks for: ${task.title}`, 'PlanningRules');
            // In a real implementation, this would use the LLM to analyze the task's output and generate relevant subtasks
            const subTask1: Note = {
                id: idService.generateId(),
                type: 'Task',
                title: `Follow-up task for ${task.title}`,
                content: {messages: []},
                status: 'pending',
                priority: task.priority - 5,
                createdAt: new Date().toISOString(),
                updatedAt: null,
                references: [task.id],
                description: `A follow-up task for ${task.title}`,
            };
            system.addNote(subTask1);
            task.references = [...(task.references ?? []), subTask1.id];
            system.updateNote(task);
            systemLog.info(`Created follow-up task: ${subTask1.title}`, 'PlanningRules');
        }
    },
    // Add more planning rules here
];

export default planningRules;
