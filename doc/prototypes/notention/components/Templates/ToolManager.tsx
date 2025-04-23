import React, {useState, useEffect, useCallback, useMemo} from 'react';
import styles from './TemplatesView.module.css';
import {getSystemNote} from '../../lib/systemNote';
import {Note} from '../../types';
import MonacoEditor from 'react-monaco-editor';
import {systemLog} from "../../lib/systemLog";
import idService from "../../lib/idService";

interface ToolManagerProps {
}

/**
 * Component for managing tools, allowing users to create, edit, and delete tools.
 */
export const ToolManager: React.FC<ToolManagerProps> = () => {
    const system = getSystemNote();

    const [newToolTitle, setNewToolTitle] = useState('');
    const [newToolLogic, setNewToolLogic] = useState('');
    const [newToolInputSchema, setNewToolInputSchema] = useState('');
    const [newToolOutputSchema, setNewToolOutputSchema] = useState('');
    const [newToolType, setNewToolType] = useState<'custom' | 'langchain' | 'api'>('custom'); // Default to 'custom'
    const [newApiEndpoint, setNewApiEndpoint] = useState('');
    const [newApiMethod, setNewApiMethod] = useState<'GET' | 'POST' | 'PUT' | 'DELETE'>('POST'); // Default to 'POST'
    const [newApiHeaders, setNewApiHeaders] = useState('{}'); // Default to empty JSON object
    const [toolCreationError, setToolCreationError] = useState<string | null>(null);
    const [editingToolId, setEditingToolId] = useState<string | null>(null);
    const [editingToolInputSchemaId, setEditingToolInputSchemaId] = useState<string | null>(null);
    const [editingToolOutputSchemaId, setEditingToolOutputSchemaId] = useState<string | null>(null);
    const [editingApiToolId, setEditingApiToolId] = useState<string | null>(null);
    const [tools, setTools] = useState<Note[]>([]);

    /**
     * Fetches all tools from the system and updates the state.
     */
    const fetchTools = useCallback(async () => {
        setTools(system.getAllTools());
    }, [system]);

    useEffect(() => {
        fetchTools();
    }, [fetchTools, system]);

    /**
     * Handles the creation of a new tool.
     */
    const handleCreateTool = useCallback(async () => {
        if (!newToolTitle || !newToolInputSchema || !newToolOutputSchema) {
            setToolCreationError('Tool Title, Input Schema, and Output Schema are required.');
            return;
        }

        if (newToolType === 'api' && (!newApiEndpoint || !newApiMethod)) {
            setToolCreationError('API Endpoint and Method are required for API tools.');
            return;
        }

        try {
            JSON.parse(newToolInputSchema);
            JSON.parse(newToolOutputSchema);
            if (newToolType === 'api') {
                JSON.parse(newApiHeaders); // Validate headers as JSON
                new URL(newApiEndpoint); // Validate API Endpoint URL
            }
        } catch (e: any) {
            setToolCreationError(`Invalid JSON or URL in Tool definition: ${e.message}`);
            return;
        }

        try {
            const newTool: Note & { type: 'custom' | 'langchain' | 'api' } = {
                id: idService.generateId(),
                type: newToolType, // Use the selected tool type
                title: newToolTitle,
                content: `Tool: ${newToolTitle}`,
                logic: newToolType === 'api' ? newApiEndpoint : newToolLogic, // Store API endpoint in logic for API tools
                inputSchema: newToolInputSchema,
                outputSchema: newToolOutputSchema,
                status: 'active',
                priority: 0,
                createdAt: new Date().toISOString(),
                updatedAt: null,
                references: [],
                config: newToolType === 'api' ? {
                    method: newApiMethod,
                    headers: newApiHeaders,
                } : undefined,
            };
            system.registerToolDefinition(newTool);
            setNewToolTitle('');
            setNewToolLogic('');
            setNewToolInputSchema('');
            setNewToolOutputSchema('');
            setNewToolType('custom'); // Reset to default
            setNewApiEndpoint('');
            setNewApiMethod('POST');
            setNewApiHeaders('{}');
            setToolCreationError(null);
            fetchTools();
        } catch (error: any) {
            systemLog.error(`Error creating tool: ${error.message}`, 'ToolManager');
            setToolCreationError(`Error creating tool: ${error.message}`);
        }
    }, [newToolInputSchema, newToolLogic, newToolOutputSchema, newToolTitle, newToolType, system, fetchTools, newApiEndpoint, newApiMethod, newApiHeaders]);

    /**
     * Handles editing an existing tool's logic.
     * @param {string} toolId - The ID of the tool to edit.
     */
    const handleEditTool = useCallback((toolId: string) => {
        try {
            setEditingToolId(toolId);
            const tool = system.getTool(toolId);
            if (!tool) {
                setToolCreationError(`Tool with id ${toolId} not found.`);
                return;
            }
            setNewToolLogic(tool.logic || '');
        } catch (error: any) {
            systemLog.error(`Error editing tool: ${error.message}`, 'ToolManager');
            setToolCreationError(`Error editing tool: ${error.message}`);
        }
    }, [system]);

    /**
     * Handles editing an existing API tool's configuration.
     * @param {string} toolId - The ID of the API tool to edit.
     */
    const handleEditApiTool = useCallback((toolId: string) => {
        try {
            setEditingApiToolId(toolId);
            const tool = system.getTool(toolId);
            if (!tool) {
                setToolCreationError(`Tool with id ${toolId} not found.`);
                return;
            }
            setNewApiEndpoint(tool.logic || '');
            setNewApiMethod((tool.config?.method as 'GET' | 'POST' | 'PUT' | 'DELETE') || 'POST');
            setNewApiHeaders(tool.config?.headers || '{}');
        } catch (error: any) {
            systemLog.error(`Error editing API tool: ${error.message}`, 'ToolManager');
            setToolCreationError(`Error editing tool: ${error.message}`);
        }
    }, [system]);

    /**
     * Handles editing an existing tool's input schema.
     * @param {string} toolId - The ID of the tool to edit.
     */
    const handleEditToolInputSchema = useCallback((toolId: string) => {
        try {
            setEditingToolInputSchemaId(toolId);
            const tool = system.getTool(toolId);
            if (!tool) {
                setToolCreationError(`Tool with id ${toolId} not found.`);
                return;
            }
            setNewToolInputSchema(tool.inputSchema || '');
        } catch (error: any) {
            systemLog.error(`Error editing tool input schema: ${error.message}`, 'ToolManager');
            setToolCreationError(`Error editing tool input schema: ${error.message}`);
        }
    }, [system]);

    /**
     * Handles editing an existing tool's output schema.
     * @param {string} toolId - The ID of the tool to edit.
     */
    const handleEditToolOutputSchema = useCallback((toolId: string) => {
        try {
            setEditingToolOutputSchemaId(toolId);
            const tool = system.getTool(toolId);
            if (!tool) {
                setToolCreationError(`Tool with id ${toolId} not found.`);
                return;
            }
            setNewToolOutputSchema(tool.outputSchema || '');
        } catch (error: any) {
            systemLog.error(`Error editing tool output schema: ${error.message}`, 'ToolManager');
            setToolCreationError(`Error editing tool output schema: ${error.message}`);
        }
    }, [system]);

    /**
     * Handles saving an edited tool's logic.
     * @param {string} toolId - The ID of the tool to save.
     * @param {string} newLogic - The new logic for the tool.
     */
    const handleSaveTool = useCallback((toolId: string, newLogic: string) => {
        try {
            const tool = system.getTool(toolId);
            if (!tool) {
                setToolCreationError(`Tool with id ${toolId} not found.`);
                return;
            }

            tool.logic = newLogic;
            system.updateNote(tool);
            setEditingToolId(null);
            setToolCreationError(null);
            fetchTools();
        } catch (error: any) {
            systemLog.error(`Error saving tool: ${error.message}`, 'ToolManager');
            setToolCreationError(`Error saving tool: ${error.message}`);
        }
    }, [system, fetchTools]);

    /**
     * Handles saving an edited API tool's configuration.
     * @param {string} toolId - The ID of the API tool to save.
     * @param {string} newApiEndpoint - The new API endpoint URL.
     * @param {string} newApiMethod - The new API method (GET, POST, PUT, DELETE).
     * @param {string} newApiHeaders - The new API headers (JSON format).
     */
    const handleSaveApiTool = useCallback((toolId: string, newApiEndpoint: string, newApiMethod: string, newApiHeaders: string) => {
        try {
            const tool = system.getTool(toolId);
            if (!tool) {
                setToolCreationError(`Tool with id ${toolId} not found.`);
                return;
            }

            try {
                JSON.parse(newApiHeaders);
                new URL(newApiEndpoint); // Validate API Endpoint URL
            } catch (e: any) {
                setToolCreationError(`Invalid JSON or URL in API definition: ${e.message}`);
                return;
            }

            tool.logic = newApiEndpoint;
            tool.config = {
                method: newApiMethod,
                headers: newApiHeaders,
            };
            system.updateNote(tool);
            setEditingApiToolId(null);
            setToolCreationError(null);
            fetchTools();
        } catch (error: any) {
            systemLog.error(`Error saving API tool: ${error.message}`, 'ToolManager');
            setToolCreationError(`Error saving API tool: ${error.message}`);
        }
    }, [system, fetchTools]);

    /**
     * Handles saving an edited tool's input schema.
     * @param {string} toolId - The ID of the tool to save.
     * @param {string} newInputSchema - The new input schema for the tool.
     */
    const handleSaveToolInputSchema = useCallback((toolId: string, newInputSchema: string) => {
        try {
            const tool = system.getTool(toolId);
            if (!tool) {
                setToolCreationError(`Tool with id ${toolId} not found.`);
                return;
            }

            tool.inputSchema = newInputSchema;
            system.updateNote(tool);
            setEditingToolInputSchemaId(null);
            setToolCreationError(null);
            fetchTools();
        } catch (error: any) {
            systemLog.error(`Error saving tool input schema: ${error.message}`, 'ToolManager');
            setToolCreationError(`Error saving tool input schema: ${error.message}`);
        }
    }, [system, fetchTools]);

    /**
     * Handles saving an edited tool's output schema.
     * @param {string} toolId - The ID of the tool to save.
     * @param {string} newOutputSchema - The new output schema for the tool.
     */
    const handleSaveToolOutputSchema = useCallback((toolId: string, newOutputSchema: string) => {
        try {
            const tool = system.getTool(toolId);
            if (!tool) {
                setToolCreationError(`Tool with id ${toolId} not found.`);
                return;
            }

            tool.outputSchema = newOutputSchema;
            system.updateNote(tool);
            setEditingToolOutputSchemaId(null);
            setToolCreationError(null);
            fetchTools();
        } catch (error: any) {
            systemLog.error(`Error saving tool output schema: ${error.message}`, 'ToolManager');
            setToolCreationError(`Error saving tool output schema: ${error.message}`);
        }
    }, [system, fetchTools]);

    /**
     * Handles canceling the editing of a tool's logic.
     */
    const handleCancelEditTool = useCallback(() => {
        setEditingToolId(null);
    }, []);

    /**
     * Handles canceling the editing of an API tool's configuration.
     */
    const handleCancelEditApiTool = useCallback(() => {
        setEditingApiToolId(null);
    }, []);

    /**
     * Handles canceling the editing of a tool's input schema.
     */
    const handleCancelEditToolInputSchema = useCallback(() => {
        setEditingToolInputSchemaId(null);
    }, []);

    /**
     * Handles canceling the editing of a tool's output schema.
     */
    const handleCancelEditToolOutputSchema = useCallback(() => {
        setEditingToolOutputSchemaId(null);
    }, []);

    /**
     * Monaco Editor options for JSON editing.
     */
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
                value={newToolTitle}
                onChange={(e) => setNewToolTitle(e.target.value)}
            />

            <label htmlFor="newToolType">Tool Type:</label>
            <select
                id="newToolType"
                value={newToolType}
                onChange={(e) => setNewToolType(e.target.value as 'custom' | 'langchain' | 'api')}
            >
                <option value="custom">Custom</option>
                <option value="langchain">LangChain</option>
                <option value="api">API</option>
            </select>

            {newToolType === 'custom' && (
                <>
                    <label htmlFor="newTemplateContent">Template Content:</label>
                    <MonacoEditor
                        width="600"
                        height="300"
                        language="json"
                        theme="vs-dark"
                        value={newToolLogic}
                        options={editorOptions}
                        onChange={(value) => setNewToolLogic(value)}
                    />
                </>
            )}

            {newToolType === 'api' && (
                <>
                    <label htmlFor="newApiEndpoint">API Endpoint:</label>
                    <input
                        type="text"
                        id="newApiEndpoint"
                        placeholder="API Endpoint URL"
                        value={newApiEndpoint}
                        onChange={(e) => setNewApiEndpoint(e.target.value)}
                    />
                </>
            )}

            <button onClick={handleCreateTool}>Create Template</button>

            {/* Section for displaying existing templates */}
            <h3>Existing Templates</h3>
            <ul>
                {tools.map(tool => (
                    <li key={tool.id}>
                        {tool.title}
                        {tool.type === 'custom' && (
                            <button onClick={() => {
                                handleEditTool(tool.id);
                            }}>Edit Logic</button>
                        )}
                        {tool.type === 'api' && (
                            <button onClick={() => {
                                handleEditApiTool(tool.id);
                            }}>Edit API Config</button>
                        )}

                        {editingToolId === tool.id && (
                            <div className={styles.templateEditor}>
                                <MonacoEditor
                                    width="600"
                                    height="300"
                                    language="json"
                                    theme="vs-dark"
                                    value={newToolLogic}
                                    options={editorOptions}
                                    onChange={(value) => setNewToolLogic(value)}
                                />
                                <button onClick={() => handleSaveTool(tool.id, newToolLogic)}>Save</button>
                                <button onClick={handleCancelEditTool}>Cancel</button>
                            </div>
                        )}

                        {editingApiToolId === tool.id && (
                            <div className={styles.templateEditor}>
                                <label htmlFor={`apiEndpoint-${tool.id}`}>API Endpoint:</label>
                                <input
                                    type="text"
                                    id={`apiEndpoint-${tool.id}`}
                                    placeholder="API Endpoint URL"
                                    value={newApiEndpoint}
                                    onChange={(e) => setNewApiEndpoint(e.target.value)}
                                />
                                <button onClick={() => handleSaveApiTool(tool.id, newApiEndpoint)}>Save</button>
                                <button onClick={handleCancelEditApiTool}>Cancel</button>
                            </div>
                        )}
                    </li>
                ))}
            </ul>
        </div>
    );
};
