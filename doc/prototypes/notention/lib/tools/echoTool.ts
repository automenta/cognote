import {Note} from '../../types';
import idService from '../idService';
import {SystemNote} from '../systemNote';
import {systemLog} from '../systemLog';
import {handleToolError, registerTool} from './toolUtils';

export const registerEchoTool = (systemNote: SystemNote) => {
    const echoToolNoteData: Note = {
        id: idService.generateId(),
        type: 'Tool',
        title: 'Echo Tool',
        content: 'A simple tool that echoes back the input.',
        logic: {
            "steps": [
                {
                    "id": "echo",
                    "type": "passthrough", // Use RunnablePassthrough for simple echo
                    "runnable": {
                        "constructor": "RunnablePassthrough",
                        "kwargs": {}
                    },
                    "input": "{input}" // Pass input through
                }
            ],
        },
        status: 'active',
        priority: 50,
        createdAt: new Date().toISOString(),
        updatedAt: null,
        references: [],
        inputSchema: { //Basic input schema for the tool
            type: 'object',
            properties: {
                input: {
                    type: 'string',
                    description: 'Text to echo',
                    inputType: 'textarea' // Specify inputType as textarea
                }
            },
            required: ['input']
        },
        outputSchema: {  //Basic output schema for the tool
            type: 'object',
            properties: {
                output: {type: 'string', description: 'Echoed text'}
            },
            required: ['output']
        },
        description: 'Echoes the input text.',
    };
    const echoToolImplementation = async (input: any) => {
        try {
            return {output: input.input};
        } catch (error: any) {
            return handleToolError(error, echoToolNoteData.id);
        }
    };
    registerTool({...echoToolNoteData, implementation: echoToolImplementation, type: 'custom'}); // Register Echo Tool
    systemLog.info(`🔨 Registered Tool ${echoToolNoteData.id}: ${echoToolNoteData.title}`, 'SystemNote');
};
