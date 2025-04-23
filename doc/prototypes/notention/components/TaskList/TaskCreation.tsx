import React, {useCallback, useState, useEffect} from 'react';
import {useSystemNote} from '../../lib/systemNote';
import {Note, TaskLogic} from '../../types';
import styles from './TaskList.module.css';
import {NoteImpl} from "../../lib/note";
import idService from '../../lib/idService'; // Import the IdService

interface TaskCreationProps {
    onTaskAdd: () => void;
}

export const TaskCreation: React.FC<TaskCreationProps> = ({onTaskAdd}) => {
    const [showToolSelector, setShowToolSelector] = useState(false);
    const [availableTools, setAvailableTools] = useState<Note[]>([]);
    const [showTemplateSelector, setShowTemplateSelector] = useState(false);
    const [availableTemplates, setAvailableTemplates] = useState<Note[]>([]);
    const [selectedTemplateId, setSelectedTemplateId] = useState<string | null>(null);
    const [selectedToolId, setSelectedToolId] = useState<string | null>(null); // Track selected tool
    const [taskDescription, setTaskDescription] = useState<string>(''); // New state for task description
    const system = useSystemNote();
    const [taskTitle, setTaskTitle] = useState<string>('');

    useEffect(() => {
        if (!system) return;
        const fetchToolsAndTemplates = async () => {
            const tools = await system.getAllTools();
            setAvailableTools(tools);
            const allNotes = await system.getAllNotes();
            setAvailableTemplates(allNotes.filter(n => n.type === 'Template'));
        };

        fetchToolsAndTemplates();
    }, [system]);

    const handleAddTask = useCallback(() => {
        if (!system) return;

        let noteImplPromise: Promise<NoteImpl>;
        if (selectedToolId) {
            // Create a task with the selected tool
            const selectedTool = system.getTool(selectedToolId);
            const taskTitle = selectedTool ? `Task with ${selectedTool.title}` : 'New Task with Tool';

            const newLogic: TaskLogic = {
                steps: [
                    {
                        id: idService.generateId(), // Generate UUID for tool step
                        type: 'tool',
                        toolId: selectedToolId,
                        input: { /* Define input parameters here */}
                    }
                ]
            };

            noteImplPromise = NoteImpl.createTaskNote(taskTitle, 'Describe your task here...', 50, taskDescription);
            noteImplPromise.then(noteImpl => {
                noteImpl.data.logic = newLogic;
                system.addNote(noteImpl.data);
            });
            setShowToolSelector(false);
            setSelectedToolId(null);
        } else {
            // Create a basic task
            noteImplPromise = NoteImpl.createTaskNote(taskTitle, 'Describe your task here...', 50, taskDescription);
            noteImplPromise.then(noteImpl => system.addNote(noteImpl.data));
        }
        noteImplPromise.then(() => onTaskAdd());
        setTaskDescription(''); // Reset task description after adding
        setTaskTitle('');
    }, [system, selectedToolId, onTaskAdd, taskDescription, taskTitle]);

    const handleCreateFromTemplate = useCallback(() => {
        setShowTemplateSelector(true);
    }, []);

    const handleSelectTemplate = useCallback(async (templateId: string) => {
        setShowTemplateSelector(false);
        setSelectedTemplateId(templateId);
        if (!system) return;
        const template = await system.getNote(templateId);
        if (template) {
            NoteImpl.createTaskNote(template.title, template.content, template.priority).then(noteImpl => {
                noteImpl.data.logic = template.logic;
                system.addNote(noteImpl.data);
            });
        }
        onTaskAdd();
    }, [system, onTaskAdd]);

    const handleSelectTool = useCallback((toolId: string) => {
        console.log('Selected tool:', toolId);
        setSelectedToolId(toolId); // Set selected tool
    }, []);

    const handleShowToolSelector = useCallback(() => {
        setShowToolSelector(true); // Show tool selector
    }, []);

    return (
        <div>
            <div className={styles.taskListActions}>
                <button onClick={handleAddTask}>+ Add Task</button>
                <button onClick={handleCreateFromTemplate}>Create from Template</button>
            </div>

            {showTemplateSelector && (
                <div className={styles.templateSelector}>
                    <h3>Select a Template</h3>
                    <ul>
                        {availableTemplates.map(template => (
                            <li key={template.id} onClick={() => handleSelectTemplate(template.id)}>
                                {template.title}
                            </li>
                        ))}
                    </ul>
                </div>
            )}

            <input
                type="text"
                placeholder="Task Title"
                value={taskTitle}
                onChange={(e) => setTaskTitle(e.target.value)}
                className={styles.taskTitleInput}
            />

            <input
                type="text"
                placeholder="Task description"
                value={taskDescription}
                onChange={(e) => setTaskDescription(e.target.value)}
                className={styles.taskDescriptionInput}
            />

            {!showToolSelector ? (
                <button onClick={handleShowToolSelector}>+ Add Task with Tool</button>
            ) : (
                <div className={styles.toolSelector}>
                    <h3>Select a Tool</h3>
                    <ul>
                        {availableTools.map(tool => (
                            <li key={tool.id}
                                onClick={() => handleSelectTool(tool.id)}
                                className={selectedToolId === tool.id ? styles.selected : ''}>
                                {tool.title}
                            </li>
                        ))}
                    </ul>
                    <button onClick={handleAddTask}>Create Task with Selected Tool</button>
                </div>
            )}
        </div>
    );
};
