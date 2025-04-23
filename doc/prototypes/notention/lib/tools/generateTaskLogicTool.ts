import {Note} from '../../types';
import idService from '../idService';
import {SystemNote} from '../systemNote';
import {systemLog} from '../systemLog';
import {handleToolError} from './toolUtils';

export const registerGenerateTaskLogicTool = (systemNote: SystemNote): void => {
    const generateTaskLogicToolData: Note = {
        id: idService.generateId(),
        type: 'Tool',
        title: 'Generate Task Logic Tool',
        content: 'Generates task logic based on a description.',
        logic: 'generate-task-logic',
        status: 'active',
        priority: 50,
        createdAt: new Date().toISOString(),
        updatedAt: null,
        references: [],
        inputSchema: {
            type: 'object',
            properties: {
                description: {
                    type: 'string',
                    description: 'The task description',
                },
            },
            required: ['description'],
        },
        outputSchema: {
            type: 'object',
            properties: {
                logic: {
                    type: 'string',
                    description: 'The generated task logic',
                },
            },
            required: ['logic'],
        },
        description: 'Generates task logic based on a description.',
    };

    const generateTaskLogicToolImplementation = async (input: any) => {
        try {
            if (!input || !input.description) {
                systemLog.warn('Generate Task Logic: Invalid input', 'GenerateTaskLogicTool');
                throw new Error('Invalid input: Description is required.');
            }

            const llm = systemNote.getLLM();
            if (!llm) {
                systemLog.error('Generate Task Logic: No LLM found', 'GenerateTaskLogicTool');
                throw new Error('No LLM found. Please configure the LLM.');
            }

            systemLog.info(`Generate Task Logic: Generating task logic for ${input.description}`, 'GenerateTaskLogicTool');

            const prompt = `Generate a LangChain Runnable steps array (JSON format) for the following task: ${input.description}. Include a step to use the "web-search-tool" if appropriate.`;
            const logic = await llm.invoke(prompt);

            systemLog.info('Generate Task Logic: Task logic generated', 'GenerateTaskLogicTool');

            return {logic};
        } catch (error: any) {
            return handleToolError(error, generateTaskLogicToolData.id);
        }
    };

    systemNote.registerToolDefinition({
        ...generateTaskLogicToolData,
        implementation: generateTaskLogicToolImplementation,
        type: 'custom'
    });
    systemLog.info(`🔨 Registered Tool ${generateTaskLogicToolData.id}: ${generateTaskLogicToolData.title}`, 'SystemNote');
};
