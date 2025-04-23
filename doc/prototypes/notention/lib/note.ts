import {Note} from '../types';
import {systemLog} from './systemLog';
import {getSystemNote} from './systemNote';
import {Runnable} from "langchain/runnables";
import idService from './idService'; // Import the IdService
import {handleFailure, reflect} from './noteLifecycle';
import {updateNote} from './noteUpdate';

//const NoteSchema = z.object({
//    id: z.string(),
//    type: z.enum(['Task', 'System', 'Template', 'Tool']),
//    title: z.string(),
//    content: z.any(),
//    logic: z.any().optional(), // Change to any for now
//    status: z.enum(['active', 'pending', 'completed', 'failed', 'dormant', 'bypassed', 'pendingRefinement', 'running']),
//    priority: z.number(),
//    createdAt: z.string(),
//    updatedAt: z.string().nullable(),
//    references: z.array(z.string()),
//    inputSchema: z.string().optional(),
//    outputSchema: z.string().optional(),
//});

export class NoteImpl {
    private _runnable: Runnable | null = null; // Cache runnable

    constructor(public data: Note) {
    }

    // Static factory method for creating Root Note
    static createRootNote = async (llm: any): Promise<NoteImpl> => {
        systemLog.debug('Creating root note', 'NoteImpl');
        return new NoteImpl({
            id: 'root',
            type: 'System',
            title: 'Netention System',
            content: {
                notes: new Map<string, Note>(),
                activeQueue: [],
                runningCount: 0,
                concurrencyLimit: 5,
                llm,
                tools: new Map<string, Note>(),
                toolImplementations: new Map<string, Function>(),
            },
            status: 'active',
            priority: 100,
            createdAt: new Date().toISOString(),
            updatedAt: null,
            references: [],
            description: 'The root note for the system.',
            requiresWebSearch: false,
            inputSchema: '',
            outputSchema: '',
            config: {},
            logic: ''
        });
    };

    // Static factory method for creating Task Note
    static createTaskNote = async (title: string, content: string, priority: number = 50, description: string = ''): Promise<NoteImpl> => {
        systemLog.debug(`Creating task note: ${title}`, 'NoteImpl');
        return new NoteImpl({
            id: idService.generateId(),
            type: 'Task',
            title: title,
            content: {
                messages: [{
                    type: 'system',
                    content: `You are a helpful assistant.  Respond to the user.`,
                    timestamp: new Date().toISOString()
                }],
            },
            description: description,
            status: 'pending',
            priority: priority,
            createdAt: new Date().toISOString(),
            updatedAt: null,
            references: [],
            requiresWebSearch: false,
            inputSchema: '',
            outputSchema: '',
            config: {},
            logic: ''
        });
    };

    async run() {
        if (this.data.status !== 'active' && this.data.status !== 'pending') {
            systemLog.debug(`Note ${this.data.id} is not active or pending, skipping run. Status: ${this.data.status}`, this.data.type);
            return;
        }

        this.data.status = 'running';
        updateNote(this.data);
        systemLog.info(`🚀 Running Note ${this.data.id}: ${this.data.title}`, this.data.type);
        getSystemNote().incrementRunning();

        try {
            await this.executeLogic();
            this.data.status = 'completed';
            updateNote(this.data);
            getSystemNote().decrementRunning();
            systemLog.info(`✅ Note ${this.data.id} completed successfully.`, this.data.type);
            await reflect(this.data, {});
        } catch (error: any) {
            this.data.status = 'failed';
            updateNote(this.data);
            getSystemNote().decrementRunning();
            systemLog.error(`🔥 Note ${this.data.id} failed: ${error.message}`, this.data.type);
            handleFailure(this.data, error);
        }
    }

    addSystemMessage(message: string, level: 'info' | 'warning' | 'error' = 'info') {
        const systemMessage = {
            type: 'system',
            content: message,
            timestamp: new Date().toISOString(),
        };

        this.data.content.messages = [...(this.data.content.messages ?? []), systemMessage];
        updateNote(this.data);
        systemLog[level](`[Note ${this.data.id}] ${message}`, this.data.type);
    }

    getRunnable(): Runnable | null {
        if (this._runnable) {
            systemLog.debug(`Note ${this.data.id} using cached runnable.`, this.data.type);
            return this._runnable;
        }

        if (!this.data.logic) {
            systemLog.debug(`Note ${this.data.id} has no logic defined, using simulation.`, this.data.type);
            return null;
        }

        try {
            // this._runnable = Runnable.fromJSON(JSON.parse(this.data.logic));
            systemLog.debug(`Note ${this.data.id} runnable created from logic.`, this.data.type);
            return null;
        } catch (e) {
            systemLog.error(`Error creating runnable from logic: ${e}`, this.data.type);
            return null;
        }
    }

    private async executeLogic() {
        if (!this.data.logic) {
            systemLog.debug(`Note ${this.data.id} has no logic defined, using simulation.`, this.data.type);
            return;
        }

        const logic: any = this.data.logic;
        if (!logic || !logic.steps || !Array.isArray(logic.steps)) {
            systemLog.warn(`Note ${this.data.id} has invalid or missing logic steps.`, this.data.type);
            return;
        }

        let stepResult: any = null; // Store the result of each step

        for (const step of logic.steps) {
            try {
                stepResult = await this.executeStep(step, stepResult); // Pass the previous result to the next step
            } catch (error: any) {
                systemLog.error(`Error executing step ${step.id} in Note ${this.data.id}: ${error.message}`, this.data.type);
                this.addSystemMessage(`Error executing step ${step.id}: ${error.message}`, 'error');
                throw error; // Re-throw to be caught by the main run function
            }
        }
    }

    private async executeStep(step: any, previousStepResult: any): Promise<any> {
        systemLog.debug(`Running step ${step.id} of type ${step.type}`, this.data.type);

        if (step.type === 'tool') {
            return await this.executeToolStep(step, previousStepResult);
        } else {
            systemLog.warn(`Unknown step type: ${step.type}. Skipping step.`, this.data.type);
            return null;
        }
    }

    private async executeToolStep(step: any, previousStepResult: any): Promise<any> {
        const toolId = step.toolId;
        let input = step.input;

        if (!toolId) {
            systemLog.warn(`Tool ID not provided in step ${step.id}`, this.data.type);
            return null;
        }

        // Basic mechanism for passing data between steps
        if (typeof input === 'string' && input.startsWith('{') && input.endsWith('}')) {
            try {
                input = JSON.parse(input);
            } catch (e) {
                // If parsing fails, assume it's a simple string
            }
        }

        if (previousStepResult && typeof input === 'object' && input !== null) {
            // Merge previous step result into the input
            input = {...input, ...previousStepResult};
        }

        systemLog.info(`⚙️ Executing tool ${toolId} for Note ${this.data.id} with input ${JSON.stringify(input)}`, this.data.type);
        try {
            const result = await getSystemNote().executeTool(toolId, input);
            systemLog.info(`✅ Tool ${toolId} executed successfully for Note ${this.data.id}. Result: ${JSON.stringify(result)}`, this.data.type);
            this.addSystemMessage(`Tool ${toolId} executed successfully. Result: ${JSON.stringify(result)}`);
            return result; // Return the result for the next step
        } catch (toolError: any) {
            systemLog.error(`❌ Error executing tool ${toolId} for Note ${this.data.id}: ${toolError.message}`, this.data.type);
            this.addSystemMessage(`Error executing tool ${toolId}: ${toolError.message}`, 'error');
            throw toolError; // Re-throw to be caught by the executeLogic function
        }
    }
}
