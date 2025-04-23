import {Note} from '../../types';
import idService from '../idService';
import {SystemNote} from '../systemNote';
import {systemLog} from '../systemLog';
import {handleToolError} from './toolUtils';

export const registerUserInteractionTool = (systemNote: SystemNote): void => {
    const userInteractionToolData: Note = {
        id: idService.generateId(),
        type: 'Tool',
        title: 'User Interaction Tool',
        content: 'Prompts the user for input.',
        logic: 'user-interaction-tool',
        status: 'active',
        priority: 50,
        createdAt: new Date().toISOString(),
        updatedAt: null,
        references: [],
        inputSchema: {
            type: 'object',
            properties: {
                prompt: {
                    type: 'string',
                    description: 'The prompt to display to the user',
                },
            },
            required: ['prompt'],
        },
        outputSchema: {
            type: 'object',
            properties: {
                response: {
                    type: 'string',
                    description: 'The user\'s response',
                },
            },
            required: ['response'],
        },
        description: 'Prompts the user for input.',
    };

    const userInteractionToolImplementation = async (input: any) => {
        try {
            if (!input || !input.prompt) {
                systemLog.warn('User Interaction Tool: Invalid input', 'UserInteractionTool');
                throw new Error('Invalid input: Prompt is required.');
            }

            systemLog.info(`User Interaction Tool: Prompting user for input`, 'UserInteractionTool');

            // This is a placeholder for the actual user interaction logic
            const response = prompt(input.prompt);

            systemLog.info('User Interaction Tool: User input received', 'UserInteractionTool');

            return {response};
        } catch (error: any) {
            return handleToolError(error, userInteractionToolData.id);
        }
    };

    systemNote.registerToolDefinition({
        ...userInteractionToolData,
        implementation: userInteractionToolImplementation,
        type: 'custom'
    });
    systemLog.info(`🔨 Registered Tool ${userInteractionToolData.id}: ${userInteractionToolData.title}`, 'SystemNote');
};
