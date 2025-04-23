import React, {useState, useCallback, useMemo} from 'react';
import styles from './TemplatesView.module.css';
import {getSystemNote} from '../../lib/systemNote';
import {Note} from '../../types';
import {NoteImpl} from '../../lib/note';
import MonacoEditor from 'react-monaco-editor';
import {systemLog} from "../../lib/systemLog";

interface TemplateManagerProps {
}

export const TemplateManager: React.FC<TemplateManagerProps> = () => {
    const system = getSystemNote();

    const [templates, setTemplates] = useState<Note[]>([]);
    const [newTemplateTitle, setNewTemplateTitle] = useState('');
    const [newTemplateContent, setNewTemplateContent] = useState('');
    const [newTemplateLogic, setNewTemplateLogic] = useState('');
    const [editingTemplateId, setEditingTemplateId] = useState<string | null>(null);
    const [editingContentTemplateId, setEditingContentTemplateId] = useState<string | null>(null);
    const [templateError, setTemplateError] = useState<string | null>(null);

    const fetchTemplates = useCallback(async () => {
        try {
            const allTemplates = system.getAllNotes().filter(note => note.type === 'Template');
            setTemplates(allTemplates);
            setTemplateError(null);
        } catch (error: any) {
            systemLog.error(`Error fetching templates: ${error.message}`, 'TemplateManager');
            setTemplateError(`Error fetching templates: ${error.message}`);
        }
    }, [system]);

    // Handle the creation of a new template
    const handleCreateTemplate = useCallback(async () => {
        if (!newTemplateTitle || !newTemplateContent) {
            setTemplateError('Template title and content are required.');
            return;
        }

        try {
            const newTemplate: Note = {
                id: `template-${Date.now()}`,
                type: 'Template',
                title: newTemplateTitle,
                content: newTemplateContent,
                logic: newTemplateLogic,
                status: 'active',
                priority: 0,
                createdAt: new Date().toISOString(),
                updatedAt: null,
                references: [],
            };
            system.addNote(newTemplate);
            setNewTemplateTitle('');
            setNewTemplateContent('');
            setNewTemplateLogic('');
            setTemplateError(null);
            fetchTemplates();
        } catch (error: any) {
            systemLog.error(`Error creating template: ${error.message}`, 'TemplateManager');
            setTemplateError(`Error creating template: ${error.message}`);
        }
    }, [newTemplateContent, newTemplateLogic, newTemplateTitle, system, fetchTemplates]);

    // Handle the creation of a new task from a template
    const handleCreateTaskFromTemplate = useCallback(async (templateId: string) => {
        try {
            const template = system.getNote(templateId);
            if (!template) {
                setTemplateError(`Template with id ${templateId} not found.`);
                return;
            }

            const task = await NoteImpl.createTaskNote(template.title, template.content, template.priority);
            task.data.logic = template.logic;
            task.data.content = template.content;
            system.addNote(task.data);
            setTemplateError(null);
        } catch (error: any) {
            systemLog.error(`Error creating task from template: ${error.message}`, 'TemplateManager');
            setTemplateError(`Error creating task from template: ${error.message}`);
        }
    }, [system]);

    // Handle editing an existing template
    const handleEditTemplate = useCallback((templateId: string) => {
        try {
            setEditingTemplateId(templateId);
            const template = system.getNote(templateId);
            if (!template) {
                setTemplateError(`Template with id ${templateId} not found.`);
                return;
            }
            setNewTemplateLogic(template.logic || '');
        } catch (error: any) {
            systemLog.error(`Error editing template: ${error.message}`, 'TemplateManager');
            setTemplateError(`Error editing template: ${error.message}`);
        }
    }, [system]);

    const handleEditContentTemplate = useCallback((templateId: string) => {
        try {
            setEditingContentTemplateId(templateId);
            const template = system.getNote(templateId);
            if (!template) {
                setTemplateError(`Template with id ${templateId} not found.`);
                return;
            }
            setNewTemplateContent(template.content || ''); // Set the content for editing
        } catch (error: any) {
            systemLog.error(`Error editing template: ${error.message}`, 'TemplateManager');
            setTemplateError(`Error editing template: ${error.message}`);
        }
    }, [system]);

    // Handle saving an edited template
    const handleSaveTemplate = useCallback((templateId: string, newLogic: string) => {
        try {
            const template = system.getNote(templateId);
            if (!template) {
                setTemplateError(`Template with id ${templateId} not found.`);
                return;
            }

            template.logic = newLogic;
            system.updateNote(template);
            setEditingTemplateId(null);
            setTemplateError(null);
            fetchTemplates();
        } catch (error: any) {
            systemLog.error(`Error saving template: ${error.message}`, 'TemplateManager');
            setTemplateError(`Error saving template: ${error.message}`);
        }
    }, [system, fetchTemplates]);

    const handleSaveContentTemplate = useCallback((templateId: string, newContent: string) => {
        try {
            const template = system.getNote(templateId);
            if (!template) {
                setTemplateError(`Template with id ${templateId} not found.`);
                return;
            }

            template.content = newContent;
            system.updateNote(template);
            setEditingContentTemplateId(null);
            setTemplateError(null);
            fetchTemplates();
        } catch (error: any) {
            systemLog.error(`Error saving template: ${error.message}`, 'TemplateManager');
            setTemplateError(`Error saving template: ${error.message}`);
        }
    }, [system, fetchTemplates]);

    // Handle canceling the editing of a template
    const handleCancelEdit = useCallback(() => {
        setEditingTemplateId(null);
    }, []);

    const handleCancelContentEdit = useCallback(() => {
        setEditingContentTemplateId(null);
    }, []);

    // Monaco Editor options
    const editorOptions = useMemo(() => ({
        selectOnLineNumbers: true,
        roundedSelection: false,
        readOnly: false,
        cursorStyle: 'line',
        automaticLayout: true,
    }), []);

    return (
        <div className={styles.templatesContainer}>
            {/* Section for creating new templates */}
            <h3>Create New Template</h3>
            <label htmlFor="newTemplateTitle">Template Title:</label>
            <input
                type="text"
                id="newTemplateTitle"
                placeholder="Template Title"
                value={newTemplateTitle}
                onChange={(e) => setNewTemplateTitle(e.target.value)}
            />
            <label htmlFor="newTemplateContent">Template Content:</label>
            <textarea
                id="newTemplateContent"
                placeholder="Template Content"
                value={newTemplateContent}
                onChange={(e) => setNewTemplateContent(e.target.value)}
            />
            <label htmlFor="newTemplateLogic">Template Logic (JSON):</label>
            <textarea
                id="newTemplateLogic"
                placeholder="Template Logic (JSON)"
                value={newTemplateLogic}
                onChange={(e) => setNewTemplateLogic(e.target.value)}
            />
            <button onClick={handleCreateTemplate}>Create Template</button>
            {templateError && <div className={styles.errorMessage}>Error: {templateError}</div>}

            {/* Section for displaying existing templates */}
            <h3>Existing Templates</h3>
            <ul>
                {templates.map(template => (
                    <li key={template.id}>
                        {template.title} - {template.content}
                        <button onClick={() => handleCreateTaskFromTemplate(template.id)}>Create Task</button>
                        <button onClick={() => {
                            handleEditTemplate(template.id);
                            setNewTemplateLogic(template.logic || '');
                        }}>Edit Logic
                        </button>
                        <button onClick={() => {
                            handleEditContentTemplate(template.id);
                            setNewTemplateContent(template.content || '');
                        }}>Edit Content
                        </button>
                        {editingTemplateId === template.id && (
                            <div className={styles.templateEditor}>
                                <MonacoEditor
                                    width="600"
                                    height="300"
                                    language="json"
                                    theme="vs-dark"
                                    value={newTemplateLogic}
                                    options={editorOptions}
                                    onChange={(value) => setNewTemplateLogic(value)}
                                />
                                <button onClick={() => handleSaveTemplate(template.id, newTemplateLogic)}>Save</button>
                                <button onClick={handleCancelEdit}>Cancel</button>
                            </div>
                        )}
                        {editingContentTemplateId === template.id && (
                            <div className={styles.templateEditor}>
                                <MonacoEditor
                                    width="600"
                                    height="300"
                                    language="markdown"
                                    theme="vs-dark"
                                    value={newTemplateContent}
                                    options={editorOptions}
                                    onChange={(value) => setNewTemplateContent(value)}
                                />
                                <button
                                    onClick={() => handleSaveContentTemplate(template.id, newTemplateContent)}>Save
                                </button>
                                <button onClick={handleCancelContentEdit}>Cancel</button>
                            </div>
                        )}
                    </li>
                ))}
            </ul>
        </div>
    );
};
