import {InMemoryNoteStorage} from '../lib/noteStorage';
import {Note} from '../types';
import idService from '../lib/idService';
import {getSystemNote, initializeSystemNote, SystemNote} from '../lib/systemNote';
import {ChatOpenAI} from '@langchain/openai';
import * as fs from 'fs';
import path from 'path';
import {SAFE_DIRECTORY} from '../lib/initialTools';

jest.mock('@langchain/openai', () => ({
    ChatOpenAI: jest.fn().mockImplementation(() => ({
        call: jest.fn().mockResolvedValue('Mock LLM Response'),
        invoke: jest.fn().mockResolvedValue('Mock LLM Response'),
    })),
}));

describe('SystemNote Integration with InMemoryNoteStorage', () => {
    let systemNote: SystemNote;
    let inMemoryStorage: InMemoryNoteStorage;
    let note1: Note;

    beforeEach(() => {
        initializeSystemNote({} as ChatOpenAI);
        systemNote = getSystemNote();
        inMemoryStorage = new InMemoryNoteStorage();

        note1 = {
            id: idService.generateId(),
            type: 'Task',
            title: 'Test Note 1',
            content: 'This is a test note',
            status: 'pending',
            priority: 50,
            createdAt: new Date().toISOString(),
            updatedAt: null,
            references: [],
            description: '',
            requiresWebSearch: false,
            inputSchema: '',
            outputSchema: '',
            config: {},
            logic: ''
        };
    });

    it('should add a note to the storage through SystemNote', async () => {
        await systemNote.addNote(note1);
        const retrievedNote = await inMemoryStorage.getNote(note1.id);
        expect(retrievedNote).toEqual(note1);
    });

    it('should get a note from the storage through SystemNote', async () => {
        await systemNote.addNote(note1);
        const retrievedNote = await systemNote.getNote(note1.id);
        expect(retrievedNote).toEqual(note1);
    });

    it('should update a note in the storage through SystemNote', async () => {
        await systemNote.addNote(note1);
        const updatedNote = {...note1, title: 'Updated Test Note'};
        await systemNote.updateNote(updatedNote);
        const retrievedNote = await inMemoryStorage.getNote(note1.id);
        expect(retrievedNote).toEqual(updatedNote);
    });

    it('should delete a note from the storage through SystemNote', async () => {
        await systemNote.addNote(note1);
        await systemNote.deleteNote(note1.id);
        const retrievedNote = await inMemoryStorage.getNote(note1.id);
        expect(retrievedNote).toBeUndefined();
    });

    describe('Tool Execution', () => {
        it('should execute a custom tool', async () => {
            const customTool: Note = {
                id: idService.generateId(),
                type: 'Tool',
                title: 'Custom Tool',
                content: 'This is a custom tool',
                status: 'active',
                priority: 50,
                createdAt: new Date().toISOString(),
                updatedAt: null,
                references: [],
                description: '',
                requiresWebSearch: false,
                inputSchema: '',
                outputSchema: '',
                config: {},
                logic: ''
            };

            systemNote.registerToolDefinition({
                ...customTool,
                type: 'custom',
                implementation: async (input: any) => ({result: `Custom tool executed with input: ${JSON.stringify(input)}`})
            });

            const result = await systemNote.executeTool(customTool.id, {input: 'test'});

            expect(result).toEqual({result: 'Custom tool executed with input: {"input":"test"}'});
        });

        it('should execute a langchain tool', async () => {
            const langchainTool: Note = {
                id: idService.generateId(),
                type: 'Tool',
                title: 'Langchain Tool',
                content: 'This is a langchain tool',
                status: 'active',
                priority: 50,
                createdAt: new Date().toISOString(),
                updatedAt: null,
                references: [],
                description: '',
                requiresWebSearch: false,
                inputSchema: '',
                outputSchema: '',
                config: {},
                logic: ''
            };

            const mockLangchainTool = {
                call: jest.fn().mockResolvedValue('Mock Langchain Tool Response'),
            };

            systemNote.registerToolDefinition({
                ...langchainTool,
                type: 'langchain',
                implementation: mockLangchainTool,
            });

            const result = await systemNote.executeTool(langchainTool.id, {input: 'test'});

            expect(result).toEqual('Mock Langchain Tool Response');
            expect(mockLangchainTool.call).toHaveBeenCalledWith({input: 'test'});
        });

        it('should execute an api tool', async () => {
            const apiTool: Note = {
                id: idService.generateId(),
                type: 'Tool',
                title: 'API Tool',
                content: 'This is an API tool',
                status: 'active',
                priority: 50,
                createdAt: new Date().toISOString(),
                updatedAt: null,
                references: [],
                description: '',
                requiresWebSearch: false,
                inputSchema: '',
                outputSchema: '',
                config: {
                    method: 'POST',
                    headers: JSON.stringify({'Content-Type': 'application/json'}),
                },
                logic: 'https://example.com/api',
            };

            global.fetch = jest.fn().mockResolvedValue({
                ok: true,
                json: async () => ({result: 'Mock API Response'}),
            }) as jest.Mock;

            systemNote.registerToolDefinition(apiTool);

            const result = await systemNote.executeTool(apiTool.id, {input: 'test'});

            expect(result).toEqual({result: 'Mock API Response'});
            expect(fetch).toHaveBeenCalledWith('https://example.com/api', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({input: 'test'}),
            });
        });

        describe('File Operations Tool', () => {
            const testFilePath = path.join(SAFE_DIRECTORY, 'test.txt');
            const username = 'admin';
            const password = 'password';

            afterEach(() => {
                if (fs.existsSync(testFilePath)) {
                    fs.unlinkSync(testFilePath);
                }
            });

            it('should read a file within the safe directory', async () => {
                fs.writeFileSync(testFilePath, 'test content', 'utf-8');

                const result = await systemNote.executeTool('file-operations-tool', {
                    action: 'read',
                    filename: 'test.txt',
                });

                expect(result).toEqual({result: 'test content'});
            });

            it('should write to a file within the safe directory', async () => {
                const result = await systemNote.executeTool('file-operations-tool', {
                    action: 'write',
                    filename: 'test.txt',
                    content: 'test content',
                });

                expect(result).toEqual({result: 'File written successfully'});

                const fileContent = fs.readFileSync(testFilePath, 'utf-8');
                expect(fileContent).toBe('test content');
            });

            it('should not read a file outside the safe directory', async () => {
                await expect(
                    systemNote.executeTool('file-operations-tool', {
                        action: 'read',
                        filename: '../outside.txt',
                    })
                ).rejects.toThrow('Filename is outside the safe directory.');
            });

            it('should not write to a file outside the safe directory', async () => {
                await expect(
                    systemNote.executeTool('file-operations-tool', {
                        action: 'write',
                        filename: '../outside.txt',
                        content: 'test content',
                    })
                ).rejects.toThrow('Filename is outside the safe directory.');
            });

            it('should not allow reading files with invalid extensions', async () => {
                fs.writeFileSync(path.join(SAFE_DIRECTORY, 'test.exe'), 'test content', 'utf-8');

                await expect(
                    systemNote.executeTool('file-operations-tool', {
                        action: 'read',
                        filename: 'test.exe',
                    })
                ).rejects.toThrow('Invalid file extension.');
            });

            it('should create a directory within the safe directory', async () => {
                const testDirectoryPath = path.join(SAFE_DIRECTORY, 'test_directory');

                const result = await systemNote.executeTool('file-operations-tool', {
                    action: 'createDirectory',
                    filename: 'test_directory',
                });

                expect(result).toEqual({result: 'Directory created successfully'});

                expect(fs.existsSync(testDirectoryPath)).toBe(true);

                fs.rmdirSync(testDirectoryPath);
            });

            it('should delete a file within the safe directory', async () => {
                fs.writeFileSync(testFilePath, 'test content', 'utf-8');

                const result = await systemNote.executeTool('file-operations-tool', {
                    action: 'deleteFile',
                    filename: 'test.txt',
                });

                expect(result).toEqual({result: 'File deleted successfully'});

                expect(fs.existsSync(testFilePath)).toBe(false);
            });

            it('should not allow writing content with script tags', async () => {
                await expect(
                    systemNote.executeTool('file-operations-tool', {
                        action: 'write',
                        filename: 'test.txt',
                        content: '<script>alert("XSS")</script>',
                    })
                ).rejects.toThrow('Content contains potentially harmful code.');
            });

            it('should not allow writing content with iframe tags', async () => {
                await expect(
                    systemNote.executeTool('file-operations-tool', {
                        action: 'write',
                        filename: 'test.txt',
                        content: '<iframe src="https://example.com"></iframe>',
                    })
                ).rejects.toThrow('Content contains potentially harmful code.');
            });

            it('should not allow actions that are not whitelisted for test.txt', async () => {
                await expect(
                    systemNote.executeTool('file-operations-tool', {
                        action: 'createDirectory',
                        filename: 'test.txt',
                    })
                ).rejects.toThrow('Action "createDirectory" is not allowed for file "test.txt".');
            });

            it('should not allow actions that are not whitelisted for test_directory', async () => {
                await expect(
                    systemNote.executeTool('file-operations-tool', {
                        action: 'read',
                        filename: 'test_directory',
                    })
                ).rejects.toThrow('Action "read" is not allowed for file "test_directory".');
            });
        });
    });
});
