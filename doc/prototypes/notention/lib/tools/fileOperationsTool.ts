import {Note} from '../../types';
import idService from '../idService';
import {SystemNote} from '../systemNote';
import {systemLog} from '../systemLog';
import * as fs from 'fs';
import path from 'path';
import {ALLOWED_EXTENSIONS, SAFE_DIRECTORY, sanitizeFilename} from '../fileUtils';
import {handleToolError} from './toolUtils';

const ALLOWED_ACTIONS: { [filename: string]: string[] } = {
    'test.txt': ['read', 'write', 'deleteFile'],
    'test_directory': ['createDirectory'],
};

export const registerFileOperationsTool = (systemNote: SystemNote): void => {
    const fileOperationsToolData: Note = {
        id: idService.generateId(),
        type: 'Tool',
        title: 'File Operations Tool',
        content: `A tool to read and write local files within the safe directory: ${SAFE_DIRECTORY} (SECURITY WARNING).`,
        logic: {
            "steps": [
                {
                    "id": "read-file",
                    "type": "passthrough",
                    "input": "{filename}"
                },
                {
                    "id": "write-file",
                    "type": "passthrough",
                    "input": "{filename, content}"
                }
            ],
        },
        status: 'active',
        priority: 50,
        createdAt: new Date().toISOString(),
        updatedAt: null,
        references: [],
        inputSchema: {
            type: 'object',
            properties: {
                action: {
                    type: 'string',
                    enum: ['read', 'write', 'createDirectory', 'deleteFile'],
                    description: 'Action to perform',
                    inputType: 'select',
                },
                filename: {type: 'string', description: 'Filename'},
                content: {type: 'string', description: 'Content to write', inputType: 'textarea'}
            },
            required: ['action', 'filename', 'content']
        },
        outputSchema: {
            type: 'object',
            properties: {
                result: {type: 'string', description: 'Result of the operation'}
            }
        },
        description: 'Reads and writes local files within a safe directory (SECURITY WARNING).',
    };

    const fileOperationsToolImplementation = async (input: any) => {
        try {
            if (!input || !input.filename || !input.action) {
                systemLog.warn('File operation: Invalid input', 'FileOperationsTool');
                throw new Error('Invalid input: Action and filename are required.');
            }

            const action: string = input.action;
            let filename: string = sanitizeFilename(input.filename);
            filename = path.resolve(SAFE_DIRECTORY, filename);

            if (!filename.startsWith(SAFE_DIRECTORY + path.sep)) {
                systemLog.warn(`File operation: Access denied - outside safe directory`, 'FileOperationsTool');
                throw new Error('Access denied: Filename is outside the safe directory.');
            }

            if (action === 'read' || action === 'deleteFile') {
                if (!fs.existsSync(filename)) {
                    systemLog.warn(`File operation: File not found`, 'FileOperationsTool');
                    throw new Error('File not found.');
                }
            }

            const ext: string = path.extname(filename).toLowerCase();
            if (action !== 'createDirectory' && !ALLOWED_EXTENSIONS.includes(ext)) {
                systemLog.warn(`File operation: Invalid file extension`, 'FileOperationsTool');
                throw new Error(`Access denied: Invalid file extension.`);
            }

            // Action Whitelisting
            const baseFilename = path.basename(filename);
            if (ALLOWED_ACTIONS[baseFilename] && !ALLOWED_ACTIONS[baseFilename].includes(action)) {
                systemLog.warn(`File operation: Action not allowed for file`, 'FileOperationsTool');
                throw new Error(`Access denied: Action "${action}" is not allowed for file "${filename}".`);
            }

            if (action === 'read') {
                systemLog.info(`File operation: Reading file`, 'FileOperationsTool');
                const content: string = fs.readFileSync(filename, 'utf-8');
                return {result: content};
            } else if (action === 'write') {
                if (input.content === undefined || input.content === null) {
                    systemLog.warn(`File operation: Write action - content missing`, 'FileOperationsTool');
                    throw new Error('Invalid input: Content is required for write action.');
                }

                const contentToWrite: string = String(input.content);
                if (contentToWrite.includes('<script>') || contentToWrite.includes('<iframe>')) {
                    systemLog.warn(`File operation: Potential XSS attack`, 'FileOperationsTool');
                    throw new Error('Access denied: Content contains potentially harmful code.');
                }

                systemLog.info(`File operation: Writing to file`, 'FileOperationsTool');
                fs.writeFileSync(filename, contentToWrite, 'utf-8');
                return {result: 'File written successfully'};
            } else if (action === 'createDirectory') {
                if (fs.existsSync(filename)) {
                    systemLog.warn(`File operation: Directory already exists`, 'FileOperationsTool');
                    throw new Error('Directory already exists.');
                }
                systemLog.info(`File operation: Creating directory`, 'FileOperationsTool');
                fs.mkdirSync(filename, {recursive: true});
                return {result: 'Directory created successfully'};
            } else if (action === 'deleteFile') {
                systemLog.info(`File operation: Deleting file`, 'FileOperationsTool');
                fs.unlinkSync(filename);
                return {result: 'File deleted successfully'};
            } else {
                systemLog.error(`File operation: Invalid action`, 'FileOperationsTool');
                throw new Error('Invalid action');
            }
        } catch (error: any) {
            return handleToolError(error, fileOperationsToolData.id);
        }
    };
    systemNote.registerToolDefinition({
        ...fileOperationsToolData,
        implementation: fileOperationsToolImplementation,
        type: 'custom'
    });
    systemLog.info(`🔨 Registered Tool ${fileOperationsToolData.id}: ${fileOperationsToolData.title}`, 'SystemNote');
};
