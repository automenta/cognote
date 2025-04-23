import {Note} from '../types';
import {systemLog} from './systemLog';

export class ToolRegistry {
    private tools: Map<string, Note>;
    private toolImplementations: Map<string, Function>;

    constructor() {
        this.tools = new Map<string, Note>();
        this.toolImplementations = new Map<string, Function>();
    }

    registerTool(toolDefinition: Note & { type: 'custom' | 'langchain' | 'api', implementation?: Function | any }) {
        const toolNote = toolDefinition as Note;
        this.tools.set(toolNote.id, toolNote);
        if (toolDefinition.type === 'custom' && toolDefinition.implementation) {
            this.toolImplementations.set(toolDefinition.id, toolDefinition.implementation);
        }
        systemLog.info(`🔨 Registered Tool ${toolNote.id}: ${toolNote.title}`, 'SystemNote');
    }

    getTool(id: string): Note | undefined {
        return this.tools.get(id);
    }

    getToolImplementation(id: string): Function | undefined {
        return this.toolImplementations.get(id);
    }

    getAllTools(): Note[] {
        return Array.from(this.tools.values());
    }
}
