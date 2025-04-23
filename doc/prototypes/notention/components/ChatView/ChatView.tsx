import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import Message from './Message';
import {getSystemNote, onSystemNoteChange} from '../../lib/systemNote';
import {systemLog} from '../../lib/systemLog';
import {NoteImpl} from '../../lib/note';
import {NoteEditor} from '../NoteEditor/NoteEditor';
import styles from './ChatView.module.css';
import {TaskLogic, WorkflowStep} from '../../types';
import {v4 as uuidv4} from 'uuid';
import {ToolStepEditor} from './ToolStepEditor';

export const ChatView: React.FC<{ selectedTaskId: string | null }> = ({selectedTaskId}) => {
    const [messages, setMessages] = useState<any[]>([]);
    const [input, setInput] = useState('');
    const messagesEnd = useRef<HTMLDivElement>(null);
    const system = useMemo(() => getSystemNote(), []); // Memoize system to ensure stability
    const [editingNote, setEditingNote] = useState<boolean>(false);
    const [showToolSelector, setShowToolSelector] = useState(false);
    const [availableTools, setAvailableTools] = useState<any[]>([]);
    const [selectedTool, setSelectedTool] = useState<string | null>(null);
    const [toolInputValues, setToolInputValues] = useState<any>({});
    const [addingTool, setAddingTool] = useState(false);
    const [editingToolStep, setEditingToolStep] = useState<string | null>(null); // Track the tool step being edited

    useEffect(() => {
        systemLog.debug('ChatView useEffect triggered', 'ChatView');
        if (!selectedTaskId) {
            systemLog.debug('No selected task ID, clearing messages', 'ChatView');
            setMessages([]);
            return;
        }

        const task = system.getNote(selectedTaskId);
        if (!task) {
            systemLog.warn(`Task with ID ${selectedTaskId} not found`, 'ChatView');
            setMessages([]);
            return;
        }

        const initialMessages = task?.type === 'Task' ? task.content?.messages ?? [] : [];
        setMessages(initialMessages);
        systemLog.debug(`Initial messages loaded for task ${selectedTaskId}: ${initialMessages.length}`, 'ChatView');

        // Subscribe to system note changes
        const unsubscribe = onSystemNoteChange(() => {
            const updatedTask = system.getNote(selectedTaskId);
            if (!updatedTask) {
                systemLog.warn(`Task with ID ${selectedTaskId} not found during update`, 'ChatView');
                setMessages([]);
                return;
            }
            setMessages(updatedTask?.type === 'Task' ? updatedTask.content?.messages ?? [] : []);
            systemLog.debug(`Messages updated for task ${selectedTaskId}`, 'ChatView');
        });

        return () => {
            systemLog.debug('ChatView useEffect cleanup', 'ChatView');
            unsubscribe(); // Cleanup subscription
        };
    }, [selectedTaskId, system]); // Dependencies: selectedTaskId and system

    useEffect(() => {
        messagesEnd.current?.scrollIntoView({behavior: 'smooth'});
    }, [messages]);

    useEffect(() => {
        setAvailableTools(system.getAllTools());
    }, [system]);

    const handleSubmit = useCallback(async (e: React.FormEvent) => {
        e.preventDefault();
        if (!selectedTaskId || !input.trim()) {
            systemLog.debug('Submit prevented: no selected task or empty input', 'ChatView');
            return;
        }

        const task = system.getNote(selectedTaskId);
        if (!task) {
            systemLog.error(`Task with ID ${selectedTaskId} not found on submit`, 'ChatView');
            return;
        }

        const promptContent = input.trim();
        systemLog.debug(`Submitting prompt: ${promptContent} for task ${selectedTaskId}`, 'ChatView');
        const promptNote = await NoteImpl.createTaskNote(`Prompt for ${task.title}`, promptContent, task.priority);

        // Use the LLM to interpret user input and generate logic for promptNote
        try {
            const llm = system.getLLM();
            if (llm) {
                // Structure the prompt for better LLM reasoning
                const conversationHistory = messages.map(msg => `${msg.type}: ${msg.content}`).join('\n');
                const availableToolsList = system.getAllTools().map(tool => `- ${tool.title}: ${tool.content}`).join('\n');

                const llmPrompt = `
You are an AI task planner. Your goal is to convert user input into a task plan represented as a JSON array of steps.

Here's the current task: ${task.title}
Task Description: ${task.content}

Here's the conversation history:
${conversationHistory}

Here are the available tools:
${availableToolsList}

Based on the above information, convert the following user input into a task plan:
${promptContent}

Respond ONLY with a JSON array of steps. Each step should have an 'id', 'type', and other relevant properties. If a tool is needed, include a 'toolId' property.
`;
                const llmResponse = await llm.invoke(llmPrompt);
                promptNote.data.logic = llmResponse;
                systemLog.info(`LLM generated logic for promptNote: ${llmResponse}`, 'ChatView');
            } else {
                systemLog.warn('LLM not initialized, cannot generate logic for promptNote.', 'ChatView');
            }
        } catch (error: any) {
            systemLog.error(`Error generating logic from LLM: ${error.message}`, 'ChatView');
        }

        system.addNote(promptNote.data);
        task.references.push(promptNote.data.id);
        task.content.messages = [...(task.content.messages ?? []), {
            type: 'user',
            content: promptContent,
            text: promptContent,
            timestamp: new Date().toISOString()
        }];
        system.updateNote(task);
        system.enqueueNote(promptNote.data.id);

        setInput('');
        systemLog.info(`💬 User input for Task ${selectedTaskId}: ${promptContent}`, 'ChatView');
    }, [selectedTaskId, system, input, messages]);

    const handleEditInlineNote = useCallback(() => {
        systemLog.debug('Edit inline note requested', 'ChatView');
        setEditingNote(true);
    }, []);
    const handleSaveInlineNote = useCallback(() => {
        systemLog.debug('Save inline note requested', 'ChatView');
        setEditingNote(false);
    }, []);
    const handleCancelInlineNote = useCallback(() => {
        systemLog.debug('Cancel inline note requested', 'ChatView');
        setEditingNote(false);
    }, []);

    const handleAddToolStep = useCallback(() => {
        setShowToolSelector(true);
    }, []);

    const handleSelectTool = useCallback((toolId: string) => {
        setSelectedTool(toolId);
        setAddingTool(true); // Show the input form
    }, []);

    const handleInputChange = useCallback((inputName: string, value: any) => {
        setToolInputValues({
            ...toolInputValues,
            [inputName]: value,
        });
    }, [toolInputValues]);

    const handleAddSelectedTool = useCallback(() => {
        if (!selectedTaskId || !selectedTool) {
            systemLog.warn('Cannot add tool: no task or tool selected.', 'ChatView');
            return;
        }

        const task = system.getNote(selectedTaskId);
        if (!task) {
            systemLog.error(`Task with ID ${selectedTaskId} not found.`, 'ChatView');
            return;
        }

        const newStep: WorkflowStep = {
            id: uuidv4(),
            type: 'tool',
            toolId: selectedTool,
            input: toolInputValues, // Use the input values from the form
        };

        // Ensure task.logic is an object with a steps property
        if (!task.logic || typeof task.logic !== 'object' || !Array.isArray(task.logic.steps)) {
            task.logic = {steps: []};
        }

        const newLogic: TaskLogic = {
            steps: [
                ...(task.logic.steps as any[]),
                newStep
            ]
        };

        task.logic = newLogic;
        system.updateNote(task);
        setShowToolSelector(false);
        setSelectedTool(null);
        setToolInputValues({}); // Reset input values
        setAddingTool(false);
        systemLog.info(`Tool ${selectedTool} added to Task ${selectedTaskId}`, 'ChatView');
    }, [selectedTaskId, selectedTool, system, toolInputValues]);

    const handleCancelAddTool = useCallback(() => {
        setShowToolSelector(false);
        setSelectedTool(null);
        setToolInputValues({});
        setAddingTool(false);
    }, []);

    const selectedToolData = useMemo(() => {
        if (selectedTool) {
            return system.getTool(selectedTool);
        }
        return null;
    }, [selectedTool, system]);

    const handleEditToolStep = useCallback((stepId: string) => {
        setEditingToolStep(stepId);
        // Find the task and the step
        if (!selectedTaskId) return;
        const task = system.getNote(selectedTaskId);
        if (!task || !task.logic) return;

        const logic = task.logic as TaskLogic;
        const step = logic.steps.find((s: any) => s.id === stepId);

        if (step && step.input) {
            setToolInputValues(step.input); // Initialize the input values for editing
        }
    }, [system, selectedTaskId]);

    const handleSaveEditedToolStep = useCallback(() => {
        if (!selectedTaskId || !editingToolStep) return;

        const task = system.getNote(selectedTaskId);
        if (!task || !task.logic) return;

        const logic = task.logic as TaskLogic;
        const stepIndex = logic.steps.findIndex((s: any) => s.id === editingToolStep);

        if (stepIndex !== -1) {
            logic.steps[stepIndex].input = toolInputValues; // Update the input values
            task.logic = logic;
            system.updateNote(task);
            setEditingToolStep(null);
            setToolInputValues({}); // Clear the input values
            systemLog.info(`Tool step ${editingToolStep} updated in Task ${selectedTaskId}`, 'ChatView');
        }
    }, [system, selectedTaskId, editingToolStep, toolInputValues]);

    const handleCancelEditToolStep = useCallback(() => {
        setEditingToolStep(null);
        setToolInputValues({});
    }, []);

    const handleDeleteToolStep = useCallback((stepId: string) => {
        if (!selectedTaskId) return;

        const task = system.getNote(selectedTaskId);
        if (!task || !task.logic) return;

        const logic = task.logic as TaskLogic;
        const stepIndex = logic.steps.findIndex((s: any) => s.id === stepId);

        if (stepIndex !== -1) {
            logic.steps.splice(stepIndex, 1); // Remove the step from the array
            task.logic = logic;
            system.updateNote(task);
            systemLog.info(`Tool step ${stepId} deleted from Task ${selectedTaskId}`, 'ChatView');
        }
    }, [system, selectedTaskId]);

    const getToolStep = useCallback((stepId: string) => {
        if (!selectedTaskId) return null;
        const task = system.getNote(selectedTaskId);
        if (!task || !task.logic) return null;

        const logic = task.logic as TaskLogic;
        return logic.steps.find((s: any) => s.id === stepId);
    }, [system, selectedTaskId]);

    const formatInputSchema = useCallback((schemaString: string) => {
        try {
            const schema = JSON.parse(schemaString);
            return (
                <ul>
                    {Object.entries(schema.properties).map(([key, value]: [string, any]) => (
                        <li key={key}>
                            <strong>{key}:</strong> {value.description} ({value.type})
                        </li>
                    ))}
                </ul>
            );
        } catch (e) {
            return <div>Invalid JSON format</div>;
        }
    }, []);

    return (
        <div className={styles.chatView}>
            <div className={styles.chatHeader}>
                <h2>{selectedTaskId ? system.getNote(selectedTaskId)?.title : 'Select a Task to Chat'}</h2>
                {selectedTaskId && !editingNote && (
                    <div className={styles.chatActions}>
                        <button onClick={handleEditInlineNote}>Edit Note</button>
                    </div>
                )}
            </div>

            <div className={styles.messagesContainer}>
                {messages.map((msg, i) => <Message key={i} message={msg}/>)}
                <div ref={messagesEnd}/>
            </div>

            {editingNote && selectedTaskId ? (
                <div className={styles.noteEditorInline}>
                    <h3>Edit Note: {system.getNote(selectedTaskId)?.title} 📝</h3>
                    <NoteEditor
                        noteId={selectedTaskId}
                        onClose={handleCancelInlineNote}
                        onSave={async (updatedNote) => {
                            if (selectedTaskId) {
                                const task = system.getNote(selectedTaskId);
                                if (task) {
                                    system.updateNote(updatedNote);
                                    setEditingNote(false);
                                } else {
                                    systemLog.error('Note not found!', 'ChatView');
                                }
                            }
                        }}
                    />
                    <div className={styles.inlineEditorActions}>
                        <button onClick={handleCancelInlineNote}>Cancel</button>
                    </div>
                </div>
            ) : (
                <form className={styles.inputArea} onSubmit={handleSubmit}>
                    <input
                        value={input}
                        onChange={e => setInput(e.target.value)}
                        placeholder='Enter your message...'
                        disabled={!selectedTaskId}
                    />
                    <button type='submit' disabled={!selectedTaskId}>Send</button>
                </form>
            )}

            {!selectedTaskId && !editingNote &&
                <div className={styles.placeholder}>⬅️ Select a task to view messages and interact.</div>}

            {selectedTaskId && (
                <div className={styles.toolSection}>
                    <button onClick={handleAddToolStep}>Add Tool Step</button>
                    {showToolSelector && (
                        <div className={styles.toolSelector}>
                            <h3>Select a Tool</h3>
                            <ul>
                                {availableTools.map(tool => (
                                    <li
                                        key={tool.id}
                                        onClick={() => handleSelectTool(tool.id)}
                                        className={selectedTool === tool.id ? styles.selected : ''}
                                    >
                                        {tool.title}
                                    </li>
                                ))}
                            </ul>
                        </div>
                    )}

                    {(addingTool) && selectedToolData && selectedToolData.inputSchema && (
                        <ToolStepEditor
                            toolId={selectedTool}
                            inputValues={toolInputValues}
                            onChange={handleInputChange}
                            onSave={handleAddSelectedTool}
                            onCancel={handleCancelAddTool}
                        />
                    )}

                    {/* Display existing tool steps with edit option */}
                    {selectedTaskId && system.getNote(selectedTaskId)?.logic && (
                        <div>
                            <h3>Tool Steps</h3>
                            <ul>
                                {(system.getNote(selectedTaskId)!.logic as TaskLogic).steps.map((step: any) => {
                                    const tool = system.getTool(step.toolId);
                                    const isEditing = editingToolStep === step.id;
                                    return (
                                        <li key={step.id}>
                                            {isEditing ? (
                                                <ToolStepEditor
                                                    toolId={step.toolId}
                                                    inputValues={toolInputValues}
                                                    onChange={handleInputChange}
                                                    onSave={handleSaveEditedToolStep}
                                                    onCancel={handleCancelEditToolStep}
                                                />
                                            ) : (
                                                <>
                                                    {tool ? tool.title : 'Unknown Tool'}
                                                    {tool && tool.inputSchema && (
                                                        <div className={styles.toolInputDisplay}>
                                                            Input Schema: {formatInputSchema(tool.inputSchema)}
                                                        </div>
                                                    )}
                                                    <button onClick={() => handleEditToolStep(step.id)}>Edit</button>
                                                    <button onClick={() => handleDeleteToolStep(step.id)}>Delete
                                                    </button>
                                                </>
                                            )}
                                        </li>
                                    );
                                })}
                            </ul>
                        </div>
                    )}
                </div>
            )}
        </div>
    );
};
