import {Note} from '../../types';
import idService from '../idService';
import {SystemNote} from '../systemNote';
import {systemLog} from '../systemLog';
import {handleToolError} from './toolUtils';

export const registerApiTool = (systemNote: SystemNote): void => {
    const apiToolData: Note = {
        id: idService.generateId(),
        type: 'Tool',
        title: 'API Tool',
        content: 'Calls an external API.',
        logic: 'api-tool',
        status: 'active',
        priority: 50,
        createdAt: new Date().toISOString(),
        updatedAt: null,
        references: [],
        inputSchema: {
            type: 'object',
            properties: {
                input: {
                    type: 'string',
                    description: 'The input for the API',
                },
            },
            required: ['input'],
        },
        outputSchema: {
            type: 'object',
            properties: {
                result: {
                    type: 'string',
                    description: 'The result from the API',
                },
            },
            required: ['result'],
        },
        description: 'Calls an external API.',
    };

    const apiToolImplementation = async (input: any) => {
        try {
            if (!apiToolData.logic) {
                systemLog.warn('API Tool: No API endpoint found', 'APITool');
                throw new Error('No API endpoint found. Please configure the API endpoint.');
            }

            const method = apiToolData.config?.method || 'POST';
            const headers = JSON.parse(apiToolData.config?.headers || '{}');
            const body = JSON.stringify(input);

            systemLog.info(`API Tool: Calling API ${apiToolData.logic} with method ${method} and body ${body}`, 'APITool');

            const response = await fetch(apiToolData.logic, {
                method,
                headers,
                body,
            });

            if (!response.ok) {
                systemLog.error(`API Tool: HTTP error! status: ${response.status}`, 'APITool');
                throw new Error(`HTTP error! status: ${response.status}`);
            }

            const data = await response.json();

            systemLog.info('API Tool: API call successful', 'APITool');

            return data;
        } catch (error: any) {
            return handleToolError(error, apiToolData.id);
        }
    };

    systemNote.registerToolDefinition({...apiToolData, implementation: apiToolImplementation, type: 'custom'});
    systemLog.info(`🔨 Registered Tool ${apiToolData.id}: ${apiToolData.title}`, 'SystemNote');
};
