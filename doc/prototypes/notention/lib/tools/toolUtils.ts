import {Note} from '../../types';
import {getSystemNote} from '../systemNote';
import {systemLog} from '../systemLog';

export const registerTool = (toolDefinition: Note & {
    type: 'custom' | 'langchain' | 'api',
    implementation?: Function | any
}) => {
    const systemNote = getSystemNote();
    systemNote.registerToolDefinition(toolDefinition);
    systemLog.info(`🔨 Registered Tool ${toolDefinition.id}: ${toolDefinition.title}`, 'SystemNote');
};

export const handleToolError = (error: any, toolId: string) => {
    systemLog.error(`Error executing tool ${toolId}: ${error.message}`, 'SystemNote');
    return {result: `Error: ${error.message}`};
};
