import {Note} from '../../types';
import idService from '../idService';
import {SystemNote} from '../systemNote';
import {systemLog} from '../systemLog';
import {handleToolError} from './toolUtils';

export const registerWebSearchTool = (systemNote: SystemNote): void => {
    const webSearchToolData: Note = {
        id: idService.generateId(),
        type: 'Tool',
        title: 'Web Search Tool',
        content: 'Searches the web using Google Search.',
        logic: 'web-search',
        status: 'active',
        priority: 50,
        createdAt: new Date().toISOString(),
        updatedAt: null,
        references: [],
        inputSchema: {
            type: 'object',
            properties: {
                query: {
                    type: 'string',
                    description: 'The search query',
                },
            },
            required: ['query'],
        },
        outputSchema: {
            type: 'object',
            properties: {
                results: {
                    type: 'array',
                    items: {
                        type: 'string',
                    },
                    description: 'Search results',
                },
            },
            required: ['results'],
        },
        description: 'Searches the web using Google Search.',
        requiresWebSearch: true,
    };

    const webSearchToolImplementation = async (input: any) => {
        try {
            if (!input || !input.query) {
                systemLog.warn('Web Search: Invalid input', 'WebSearchTool');
                throw new Error('Invalid input: Query is required.');
            }

            const apiKey = process.env.REACT_APP_SERPAPI_API_KEY;
            if (!apiKey) {
                systemLog.error('Web Search: No API key found', 'WebSearchTool');
                throw new Error('No API key found. Set REACT_APP_SERPAPI_API_KEY environment variable.');
            }

            const query = encodeURIComponent(input.query);
            const url = `https://serpapi.com/search?q=${query}&api_key=${apiKey}`;

            systemLog.info(`Web Search: Searching the web for ${input.query}`, 'WebSearchTool');

            const response = await fetch(url);
            if (!response.ok) {
                systemLog.error(`Web Search: HTTP error! status: ${response.status}`, 'WebSearchTool');
                throw new Error(`HTTP error! status: ${response.status}`);
            }

            const data = await response.json();
            const results = data.organic_results?.map((result: any) => result.snippet) || [];

            systemLog.info(`Web Search: Found ${results.length} results for ${input.query}`, 'WebSearchTool');

            return {results};
        } catch (error: any) {
            return handleToolError(error, webSearchToolData.id);
        }
    };

    systemNote.registerToolDefinition({
        ...webSearchToolData,
        implementation: webSearchToolImplementation,
        type: 'custom'
    });
    systemLog.info(`🔨 Registered Tool ${webSearchToolData.id}: ${webSearchToolData.title}`, 'SystemNote');
};
