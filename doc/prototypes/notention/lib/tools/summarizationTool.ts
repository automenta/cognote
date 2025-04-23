import {Note} from '../../types';
import idService from '../idService';
import {SystemNote} from '../systemNote';
import {systemLog} from '../systemLog';
import {handleToolError} from './toolUtils';

export const registerSummarizationTool = (systemNote: SystemNote): void => {
    const summarizationToolData: Note = {
        id: idService.generateId(),
        type: 'Tool',
        title: 'Summarization Tool',
        content: 'Summarizes text using an LLM.',
        logic: 'summarization-tool',
        status: 'active',
        priority: 50,
        createdAt: new Date().toISOString(),
        updatedAt: null,
        references: [],
        inputSchema: {
            type: 'object',
            properties: {
                text: {
                    type: 'string',
                    description: 'The text to summarize',
                },
            },
            required: ['text'],
        },
        outputSchema: {
            type: 'object',
            properties: {
                summary: {
                    type: 'string',
                    description: 'The summary of the text',
                },
            },
            required: ['summary'],
        },
        description: 'Summarizes text using an LLM.',
    };

    const summarizationToolImplementation = async (input: any) => {
        try {
            if (!input || !input.text) {
                systemLog.warn('Summarization Tool: Invalid input', 'SummarizationTool');
                throw new Error('Invalid input: Text is required.');
            }

            const llm = systemNote.getLLM();
            if (!llm) {
                systemLog.error('Summarization Tool: No LLM found', 'SummarizationTool');
                throw new Error('No LLM found. Please configure the LLM.');
            }

            systemLog.info(`Summarization Tool: Summarizing text`, 'SummarizationTool');

            const prompt = `Please summarize the following text: ${input.text}`;
            const summary = await llm.invoke(prompt);

            systemLog.info('Summarization Tool: Text summarized', 'SummarizationTool');

            return {summary};
        } catch (error: any) {
            return handleToolError(error, summarizationToolData.id);
        }
    };

    systemNote.registerToolDefinition({
        ...summarizationToolData,
        implementation: summarizationToolImplementation,
        type: 'custom'
    });
    systemLog.info(`🔨 Registered Tool ${summarizationToolData.id}: ${summarizationToolData.title}`, 'SystemNote');
};
