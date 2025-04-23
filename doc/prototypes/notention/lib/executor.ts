import {Note} from '../types';
import {systemLog} from './systemLog';
import {ChatOpenAI} from '@langchain/openai';

// Custom error types
class ToolExecutionError extends Error {
    constructor(message: string, public toolId: string, public stepId?: string) {
        super(message);
        this.name = 'ToolExecutionError';
    }
}

class InvalidToolLogicError extends Error {
    constructor(message: string, public toolId: string) {
        super(message);
        this.name = 'InvalidToolLogicError';
    }
}

class ToolNotFoundError extends Error {
    constructor(message: string, public toolId: string) {
        super(message);
        this.name = 'ToolNotFoundError';
    }
}

export async function executeTool(tool: Note, input: any, llm: ChatOpenAI, toolImplementation?: Function): Promise<any> {
    systemLog.info(`Executing tool ${tool.id}: ${tool.title}`, 'Executor');

    if (toolImplementation) {
        try {
            systemLog.debug(`Executing tool ${tool.id} with custom implementation.`, 'Executor');
            return await toolImplementation(input);
        } catch (error: any) {
            systemLog.error(`Error executing custom implementation for tool ${tool.id}: ${error.message}`, 'Executor');
            throw new ToolExecutionError(`Error executing custom implementation: ${error.message}`, tool.id);
        }
    } else {
        try {
            systemLog.debug(`Executing tool ${tool.id} with default logic.`, 'Executor');
            const steps = JSON.parse(tool.logic || '{}')?.steps;
            if (!Array.isArray(steps)) {
                systemLog.warn(`Tool ${tool.id} has invalid or missing logic steps.`, 'Executor');
                throw new InvalidToolLogicError(`Tool has invalid or missing logic steps.`, tool.id);
            }

            let context: any = {...input}; // Shared context for passing data between steps
            let output: any = null;

            for (const step of steps) {
                systemLog.debug(`Running step ${step.id} of tool ${tool.id}.`, 'Executor');
                try {
                    switch (step.type) {
                        case 'passthrough':
                            // For passthrough steps, simply assign the input to the output
                            output = context[step.input.replace(/[{}]/g, '')];
                            break;
                        case 'llm':
                            // For llm steps, invoke the LLM with the specified prompt
                            const prompt = step.prompt.replace(/[{}]/g, '');
                            output = await llm.call([
                                `Human: ${prompt}`
                            ]);
                            break;
                        case 'code':
                            // For code steps, execute the JavaScript code and update the context
                            const code = step.code;
                            try {
                                // Create a function with the code and context as arguments
                                const fn = new Function('context', 'systemLog', code);
                                // Execute the function and update the context with the result
                                output = fn(context, systemLog);
                            } catch (codeError: any) {
                                systemLog.error(`Error executing code step ${step.id} in tool ${tool.id}: ${codeError.message}`, 'Executor');
                                throw new ToolExecutionError(`Error executing code step: ${codeError.message}`, tool.id, step.id);
                            }
                            break;
                        default:
                            systemLog.warn(`Unknown step type: ${step.type} in tool ${tool.id}. Skipping step.`, 'Executor');
                            throw new ToolExecutionError(`Unknown step type: ${step.type}`, tool.id, step.id);
                    }
                    context[step.id] = output; // Store the output in the context for the next step
                } catch (stepError: any) {
                    //Catch errors from inside the step execution and add step ID
                    if (stepError instanceof ToolExecutionError) {
                        throw stepError;
                    }
                    throw new ToolExecutionError(stepError.message, tool.id, step.id);
                }
            }
            return {output};
        } catch (error: any) {
            systemLog.error(`Error executing default logic for tool ${tool.id}: ${error.message}`, 'Executor');
            if (error instanceof InvalidToolLogicError || error instanceof ToolExecutionError) {
                throw error;
            }
            throw new ToolExecutionError(`Error executing default logic: ${error.message}`, tool.id);
        }
    }
}
