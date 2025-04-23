import * as fs from 'fs';
import path from 'path';
import {systemLog} from './systemLog';

export const SAFE_DIRECTORY = path.resolve('./safe_files');
export const ALLOWED_EXTENSIONS = ['.txt', '.md', '.json', '.js'];

export const ensureSafeDirectoryExists = () => {
    if (!fs.existsSync(SAFE_DIRECTORY)) {
        fs.mkdirSync(SAFE_DIRECTORY);
        systemLog.info(`Created safe directory at ${SAFE_DIRECTORY}`, 'FileUtils');
    }
};

// TODO: Use SAFE_DIRECTORY and ALLOWED_EXTENSIONS in FileOperations.tsx to validate file paths and extensions.
