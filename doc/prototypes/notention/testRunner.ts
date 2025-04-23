import jest from 'jest';
import {systemLog} from './lib/systemLog';

export const runTests = async () => {
    try {
        systemLog.info('Starting Jest test run...', 'TestRunner');
        const result = await jest.run();
        systemLog.debug(`Jest run completed. Raw result: ${JSON.stringify(result, null, 2)}`, 'TestRunner');

        if (!result || !result.results || !Array.isArray(result.results)) {
            systemLog.error('Invalid Jest result format. Tests may not have run correctly.', 'TestRunner');
            return {
                success: false,
                testResults: [{name: 'Test Run', status: 'error', message: 'Invalid Jest result format.'}]
            };
        }

        const testResults = result.results.flatMap(testResult => {
            if (!testResult.testResults || !Array.isArray(testResult.testResults)) {
                systemLog.warn(`Invalid test result format for file: ${testResult.testFilePath}. Skipping.`, 'TestRunner');
                return [];
            }

            return testResult.testResults.map(individualTestResult => ({
                name: `${testResult.name} - ${individualTestResult.fullName}`,
                status: individualTestResult.status,
                message: individualTestResult.message || '',
            }));
        });

        const success = result.success;
        systemLog.info(`Jest test run completed. Success: ${success}. ${testResults.length} tests found.`, 'TestRunner');

        return {success, testResults};
    } catch (error: any) {
        systemLog.error(`Error running tests: ${error.message}`, 'TestRunner');
        return {success: false, testResults: [{name: 'Test Run', status: 'error', message: error.message}]};
    }
};
