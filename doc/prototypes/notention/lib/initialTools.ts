import {getSystemNote} from './systemNote';
import {systemLog} from './systemLog';
import * as fs from 'fs';
import path from 'path';
import {registerWebSearchTool} from './tools/webSearchTool';
import {registerFileOperationsTool} from './tools/fileOperationsTool';
import {registerGenerateTaskLogicTool} from './tools/generateTaskLogicTool';
import {registerApiTool} from './tools/apiTool';
import {registerSummarizationTool} from './tools/summarizationTool';
import {registerUserInteractionTool} from './tools/userInteractionTool';
import {registerEchoTool} from './tools/echoTool';

// Define the safe directory
const SAFE_DIRECTORY = path.resolve('./safe_files');

// Allowed file extensions
const ALLOWED_EXTENSIONS = ['.txt', '.md', '.json', '.js'];

// Ensure the safe directory exists
if (!fs.existsSync(SAFE_DIRECTORY)) {
    fs.mkdirSync(SAFE_DIRECTORY);
}

// Function to sanitize filenames to prevent path traversal attacks
const sanitizeFilename = (filename: string): string => {
    const sanitized = path.basename(filename); // Get the base filename
    return sanitized.replace(/[^a-zA-Z0-9._-]/g, ''); // Remove any characters that are not alphanumeric, '.', '_', or '-'
};

export const initializeInitialTools = () => {
    const systemNote = getSystemNote();

    registerEchoTool(systemNote);
    registerWebSearchTool(systemNote);
    registerFileOperationsTool(systemNote);
    registerGenerateTaskLogicTool(systemNote);
    registerApiTool(systemNote);
    registerSummarizationTool(systemNote);
    registerUserInteractionTool(systemNote);

    systemLog.info('Initial tools registered.', 'SystemNote');
};

export {SAFE_DIRECTORY, ALLOWED_EXTENSIONS, sanitizeFilename};
