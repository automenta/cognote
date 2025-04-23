import React, {useState, useEffect, useCallback, useMemo} from 'react';
import styles from './ToolManager.module.css';
import {getSystemNote} from '../../lib/systemNote';
import {Note} from '../../types';
import MonacoEditor from 'react-monaco-editor';
import {systemLog} from "../../lib/systemLog";
import idService from "../../lib/idService";

interface ToolManagerProps {
}

/**
 * Component for managing tools and templates, allowing users to create, edit, and delete them.
 */
export const ToolManager: React.FC<ToolManagerProps> = () => {
    const system = getSystemNote();

    const [newTitle, setNewTitle] = useState('');
    const [newLogic, setNewLogic] = useState('');
    const [newInputSchema, setNewInputSchema] = useState('');
    const [newOutputSchema, setNewOutputSchema] = useState('');
    const [newType, setNewType] = useState<'custom' | 'langchain' | 'api'>('custom'); // Default to 'custom'
    const [newApiEndpoint, setNewApiEndpoint] = useState('');
    const [newApiMethod, setNewApiMethod] = useState<'GET' | 'POST' | 'PUT' | 'DELETE'>('POST'); // Default to 'POST'
    const [newApiHeaders, setNewApiHeaders] = useState('{}'); // Default to empty JSON object
    const [creationError, setCreationError] = useState<string | null>(null);
    const [editingId, setEditingId] = useState<string | null>(null);
    const [editingInputSchemaId, setEditingInputSchemaId] = useState<string | null>(null);
    const [editingOutputSchemaId, setEditingOutputSchemaId] = useState<string | null>(null);
    const [editingApiId, setEditingApiId] = useState<string | null>(null);
    const [items, setItems] = useState<Note[]>([]);
    const [isTool, setIsTool] = useState<boolean>(true); // Toggle between tools and templates

    /**
     * Fetches all tools or templates from the system and updates the state.
     */
    const fetchItems = useCallback(async () => {
        setItems(isTool ? system.getAllTools() : await system.getAllNotes()); // Assuming getAllNotes fetches templates
    }, [system, isTool]);

    useEffect(() => {
        fetchItems();
    }, [fetchItems, system, isTool]);

    const validateJson = (jsonString: string): boolean => {
        try {
            JSON.parse(jsonString);
            return true;
        } catch (e) {
            return false;
        }
    };

    /**
     * Handles the creation of a new tool or template.
     */
    const handleCreate = useCallback(async () => {
        if (!newTitle || !newInputSchema || !newOutputSchema) {
            setCreationError('Title, Input Schema, and Output Schema are required.');
            return;
        }

        if (newType === 'api' && (!newApiEndpoint || !newApiMethod)) {
            setCreationError('API Endpoint and Method are required for API items.');
            return;
        }

        if (!validateJson(newInputSchema) || !validateJson(newOutputSchema)) {
            setCreationError('Invalid JSON in Input Schema or Output Schema.');
            return;
        }

        try {
            if (newType === 'api') {
                JSON.parse(newApiHeaders); // Validate headers as JSON
                new URL(newApiEndpoint); // Validate API Endpoint URL
            }
        } catch (e: any) {
            setCreationError(`Invalid JSON or URL in item definition: ${e.message}`);
            return;
        }

        try {
            const newItem: Note & { type: 'custom' | 'langchain' | 'api' } = {
                id: idService.generateId(),
                type: newType, // Use the selected item type
                title: newTitle,
                content: `${isTool ? 'Tool' : 'Template'}: ${newTitle}`,
                logic: newType === 'api' ? newApiEndpoint : newLogic, // Store API endpoint in logic for API items
                inputSchema: newInputSchema,
                outputSchema: newOutputSchema,
                status: 'active',
                priority: 0,
                createdAt: new Date().toISOString(),
                updatedAt: null,
                references: [],
                config: newType === 'api' ? {
                    method: newApiMethod,
                    headers: newApiHeaders,
                } : undefined,
            };

            if (isTool) {
                system.registerToolDefinition(newItem);
            } else {
                await system.addNote(newItem); // Assuming addNote registers templates
            }

            setNewTitle('');
            setNewLogic('');
            setNewInputSchema('');
            setNewOutputSchema('');
            setNewType('custom'); // Reset to default
            setNewApiEndpoint('');
            setNewApiMethod('POST');
            setNewApiHeaders('{}');
            setCreationError(null);
            fetchItems();
        } catch (error: any) {
            systemLog.error(`Error creating item: ${error.message}`, 'ToolManager');
            setCreationError(`Error creating item: ${error.message}`);
        }
    }, [newInputSchema, newLogic, newOutputSchema, newTitle, newType, system, fetchItems, newApiEndpoint, newApiMethod, newApiHeaders, validateJson, isTool]);

    /**
     * Handles editing an existing item's logic.
     * @param {string} itemId - The ID of the item to edit.
     */
    const handleEdit = useCallback((itemId: string) => {
        try {
            setEditingId(itemId);
            const item = isTool ? system.getTool(itemId) : system.getNote(itemId);
            if (!item) {
                setCreationError(`item with id ${itemId} not found.`);
                return;
            }
            setNewLogic(item.logic || '');
        } catch (error: any) {
            systemLog.error(`Error editing item: ${error.message}`, 'ToolManager');
            setCreationError(`Error editing item: ${error.message}`);
        }
    }, [system, isTool]);

    /**
     * Handles editing an existing API item's configuration.
     * @param {string} itemId - The ID of the API item to edit.
     */
    const handleEditApi = useCallback((itemId: string) => {
        try {
            setEditingApiId(itemId);
            const item = isTool ? system.getTool(itemId) : system.getNote(itemId);
            if (!item) {
                setCreationError(`item with id ${itemId} not found.`);
                return;
            }
            setNewApiEndpoint(item.logic || '');
            setNewApiMethod((item.config?.method as 'GET' | 'POST' | 'PUT' | 'DELETE') || 'POST');
            setNewApiHeaders(item.config?.headers || '{}');
        } catch (error: any) {
            systemLog.error(`Error editing API item: ${error.message}`, 'ToolManager');
            setCreationError(`Error editing API item: ${error.message}`);
        }
    }, [system, isTool]);

    /**
     * Handles editing an existing item's input schema.
     * @param {string} itemId - The ID of the item to edit.
     */
    const handleEditInputSchema = useCallback((itemId: string) => {
        try {
            setEditingInputSchemaId(itemId);
            const item = isTool ? system.getTool(itemId) : system.getNote(itemId);
            if (!item) {
                setCreationError(`item with id ${itemId} not found.`);
                return;
            }
            setNewInputSchema(item.inputSchema || '');
        } catch (error: any) {
            systemLog.error(`Error editing item input schema: ${error.message}`, 'ToolManager');
            setCreationError(`Error editing item input schema: ${error.message}`);
        }
    }, [system, isTool]);

    /**
     * Handles editing an existing item's output schema.
     * @param {string} itemId - The ID of the item to edit.
     */
    const handleEditOutputSchema = useCallback((itemId: string) => {
        try {
            setEditingOutputSchemaId(itemId);
            const item = isTool ? system.getTool(itemId) : system.getNote(itemId);
            if (!item) {
                setCreationError(`item with id ${itemId} not found.`);
                return;
            }
            setNewOutputSchema(item.outputSchema || '');
        } catch (error: any) {
            systemLog.error(`Error editing item output schema: ${error.message}`, 'ToolManager');
            setCreationError(`Error editing item output schema: ${error.message}`);
        }
    }, [system, isTool]);

    /**
     * Handles saving an edited item's logic.
     * @param {string} itemId - The ID of the item to save.
     * @param {string} newLogic - The new logic for the item.
     */
    const handleSave = useCallback((itemId: string, newLogic: string) => {
        try {
            const item = isTool ? system.getTool(itemId) : system.getNote(itemId);
            if (!item) {
                setCreationError(`item with id ${itemId} not found.`);
                return;
            }

            item.logic = newLogic;
            system.updateNote(item);
            setEditingId(null);
            setCreationError(null);
            fetchItems();
        } catch (error: any) {
            systemLog.error(`Error saving item: ${error.message}`, 'ToolManager');
            setCreationError(`Error saving item: ${error.message}`);
        }
    }, [system, fetchItems, isTool]);

    /**
     * Handles saving an edited API item's configuration.
     * @param {string} itemId - The ID of the API item to save.
     * @param {string} newApiEndpoint - The new API endpoint URL.
     * @param {string} newApiMethod - The new API method (GET, POST, PUT, DELETE).
     * @param {string} newApiHeaders - The new API headers (JSON format).
     */
    const handleSaveApi = useCallback((itemId: string, newApiEndpoint: string, newApiMethod: string, newApiHeaders: string) => {
        try {
            const item = isTool ? system.getTool(itemId) : system.getNote(itemId);
            if (!item) {
                setCreationError(`item with id ${itemId} not found.`);
                return;
            }

            if (!validateJson(newApiHeaders)) {
                setCreationError('Invalid JSON in API Headers.');
                return;
            }

            try {
                new URL(newApiEndpoint); // Validate API Endpoint URL
            } catch (e: any) {
                setCreationError(`Invalid URL in API definition: ${e.message}`);
                return;
            }

            item.logic = newApiEndpoint;
            item.config = {
                method: newApiMethod,
                headers: newApiHeaders,
            };
            system.updateNote(item);
            setEditingApiId(null);
            setCreationError(null);
            fetchItems();
        } catch (error: any) {
            systemLog.error(`Error saving API item: ${error.message}`, 'ToolManager');
            setCreationError(`Error saving API item: ${error.message}`);
        }
    }, [system, fetchItems, validateJson, isTool]);

    /**
     * Handles saving an edited item's input schema.
     * @param {string} itemId - The ID of the item to save.
     * @param {string} newInputSchema - The new input schema for the item.
     */
    const handleSaveInputSchema = useCallback((itemId: string, newInputSchema: string) => {
        try {
            const item = isTool ? system.getTool(itemId) : system.getNote(itemId);
            if (!item) {
                setCreationError(`item with id ${itemId} not found.`);
                return;
            }

            if (!validateJson(newInputSchema)) {
                setCreationError('Invalid JSON in Input Schema.');
                return;
            }

            item.inputSchema = newInputSchema;
            system.updateNote(item);
            setEditingInputSchemaId(null);
            setCreationError(null);
            fetchItems();
        } catch (error: any) {
            systemLog.error(`Error saving item input schema: ${error.message}`, 'ToolManager');
            setCreationError(`Error saving item input schema: ${error.message}`);
        }
    }, [system, fetchItems, validateJson, isTool]);

    /**
     * Handles saving an edited item's output schema.
     * @param {string} itemId - The ID of the item to save.
     * @param {string} newOutputSchema - The new output schema for the item.
     */
    const handleSaveOutputSchema = useCallback((itemId: string, newOutputSchema: string) => {
        try {
            const item = isTool ? system.getTool(itemId) : system.getNote(itemId);
            if (!item) {
                setCreationError(`item with id ${itemId} not found.`);
                return;
            }

            if (!validateJson(newOutputSchema)) {
                setCreationError('Invalid JSON in Output Schema.');
                return;
            }

            item.outputSchema = newOutputSchema;
            system.updateNote(item);
            setEditingOutputSchemaId(null);
            setCreationError(null);
            fetchItems();
        } catch (error: any) {
            systemLog.error(`Error saving item output schema: ${error.message}`, 'ToolManager');
            setCreationError(`Error saving item output schema: ${error.message}`);
        }
    }, [system, fetchItems, validateJson, isTool]);

    /**
     * Handles canceling the editing of an item's logic.
     */
    const handleCancelEdit = useCallback(() => {
        setEditingId(null);
    }, []);

    /**
     * Handles canceling the editing of an API item's configuration.
     */
    const handleCancelEditApi = useCallback(() => {
        setEditingApiId(null);
    }, []);

    /**
     * Handles canceling the editing of an item's input schema.
     */
    const handleCancelEditInputSchema = useCallback(() => {
        setEditingInputSchemaId(null);
    }, []);

    /**
     * Handles canceling the editing of an item's output schema.
     */
    const handleCancelEditOutputSchema = useCallback(() => {
        setEditingOutputSchemaId(null);
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
        <div className={styles.toolManager}>
            <h2>{isTool ? 'Tool Manager' : 'Template Manager'}</h2>
            <button onClick={() => setIsTool(!isTool)}>
                Switch to {isTool ? 'Templates' : 'Tools'}
            </button>
            {creationError && <div className={styles.error}>{creationError}</div>}

            {/* Section for displaying existing items */}
            <div className={styles.itemList}>
                <h3>Existing {isTool ? 'Tools' : 'Templates'}</h3>
                {items.map(item => (
                    <div key={item.id} className={styles.itemItem}>
                        <h4>{item.title}</h4>
                        <p>Type: {item.type}</p>

                        {/* Display and edit logic for custom items */}
                        {item.type === 'custom' && (
                            <>
                                {editingId === item.id ? (
                                    <div className={styles.editForm}>
                                        <label htmlFor={`itemLogic-${item.id}`}>Item Logic:</label>
                                        <MonacoEditor
                                            width="600"
                                            height="300"
                                            language="json"
                                            theme="vs-dark"
                                            value={newLogic}
                                            options={editorOptions}
                                            onChange={(value) => setNewLogic(value)}
                                        />
                                        <div className={styles.editActions}>
                                            <button onClick={() => handleSave(item.id, newLogic)}>Save Logic</button>
                                            <button onClick={handleCancelEdit}>Cancel</button>
                                        </div>
                                    </div>
                                ) : (
                                    <>
                                        <p>Logic: {item.logic}</p>
                                        <button onClick={() => {
                                            handleEdit(item.id);
                                            setNewLogic(item.logic || '');
                                        }}>Edit Logic
                                        </button>
                                    </>
                                )}
                            </>
                        )}

                        {/* Display and edit API configuration for API items */}
                        {item.type === 'api' && (
                            <>
                                {editingApiId === item.id ? (
                                    <div className={styles.editForm}>
                                        <label htmlFor={`apiEndpoint-${item.id}`}>API Endpoint:</label>
                                        <input
                                            type="text"
                                            id={`apiEndpoint-${item.id}`}
                                            placeholder="API Endpoint URL"
                                            value={newApiEndpoint}
                                            onChange={(e) => setNewApiEndpoint(e.target.value)}
                                        />
                                        <label htmlFor={`apiMethod-${item.id}`}>API Method:</label>
                                        <select
                                            id={`apiMethod-${item.id}`}
                                            value={newApiMethod}
                                            onChange={(e) => setNewApiMethod(e.target.value as 'GET' | 'POST' | 'PUT' | 'DELETE')}
                                        >
                                            <option value="GET">GET</option>
                                            <option value="POST">POST</option>
                                            <option value="PUT">PUT</option>
                                            <option value="DELETE">DELETE</option>
                                        </select>
                                        <label htmlFor={`apiHeaders-${item.id}`}>API Headers (JSON):</label>
                                        <MonacoEditor
                                            width="600"
                                            height="300"
                                            language="json"
                                            theme="vs-dark"
                                            value={newApiHeaders}
                                            options={editorOptions}
                                            onChange={(value) => setNewApiHeaders(value)}
                                        />
                                        <div className={styles.editActions}>
                                            <button
                                                onClick={() => handleSaveApi(item.id, newApiEndpoint, newApiMethod, newApiHeaders)}>Update
                                                API Config
                                            </button>
                                            <button onClick={handleCancelEditApi}>Cancel</button>
                                        </div>
                                    </div>
                                ) : (
                                    <>
                                        <p>API Endpoint: {JSON.parse(item.logic || '{}').apiEndpoint}</p>
                                        <p>API Method: {item.config?.method}</p>
                                        <p>API Headers: {item.config?.headers}</p>
                                        <button onClick={() => {
                                            handleEditApi(item.id);
                                            setNewApiEndpoint(item.logic || '');
                                            setNewApiMethod((item.config?.method as 'GET' | 'POST' | 'PUT' | 'DELETE') || 'POST');
                                            setNewApiHeaders(item.config?.headers || '{}');
                                        }}>Edit API Config
                                        </button>
                                    </>
                                )}
                            </>
                        )}
                        {/* Display and edit input schema for all item types */}
                        {editingInputSchemaId === item.id ? (
                            <div className={styles.editForm}>
                                <label htmlFor={`itemInputSchema-${item.id}`}>Input Schema (JSON):</label>
                                <MonacoEditor
                                    width="600"
                                    height="300"
                                    language="json"
                                    theme="vs-dark"
                                    value={newInputSchema}
                                    options={editorOptions}
                                    onChange={(value) => setNewInputSchema(value)}
                                />
                                <div className={styles.editActions}>
                                    <button onClick={() => handleSaveInputSchema(item.id, newInputSchema)}>Save Input
                                        Schema
                                    </button>
                                    <button onClick={handleCancelEditInputSchema}>Cancel</button>
                                </div>
                            </div>
                        ) : (
                            <>
                                <p>Input Schema: {item.inputSchema}</p>
                                <button onClick={() => {
                                    handleEditInputSchema(item.id);
                                    setNewInputSchema(item.inputSchema || '');
                                }}>Edit Input Schema
                                </button>
                            </>
                        )}

                        {/* Display and edit output schema for all item types */}
                        {editingOutputSchemaId === item.id ? (
                            <div className={styles.editForm}>
                                <label htmlFor={`itemOutputSchema-${item.id}`}>Output Schema (JSON):</label>
                                <MonacoEditor
                                    width="600"
                                    height="300"
                                    language="json"
                                    theme="vs-dark"
                                    value={newOutputSchema}
                                    options={editorOptions}
                                    onChange={(value) => setNewOutputSchema(value)}
                                />
                                <div className={styles.editActions}>
                                    <button onClick={() => handleSaveOutputSchema(item.id, newOutputSchema)}>Save Output
                                        Schema
                                    </button>
                                    <button onClick={handleCancelEditOutputSchema}>Cancel</button>
                                </div>
                            </div>
                        ) : (
                            <>
                                <p>Output Schema: {item.outputSchema}</p>
                                <button onClick={() => {
                                    handleEditOutputSchema(item.id);
                                    setNewOutputSchema(item.outputSchema || '');
                                }}>Edit Output Schema
                                </button>
                            </>
                        )}
                    </div>
                ))}
            </div>

            {/* Section for creating new items */}
            <div className={styles.createItem}>
                <h3>Create New {isTool ? 'Tool' : 'Template'}</h3>
                {creationError && <div className={styles.error}>{creationError}</div>}
                <label htmlFor="newTitle">Title:</label>
                <input
                    type="text"
                    id="newTitle"
                    placeholder="Title"
                    value={newTitle}
                    onChange={(e) => setNewTitle(e.target.value)}
                />

                <label htmlFor="newType">Type:</label>
                <select
                    id="newType"
                    value={newType}
                    onChange={(e) => setNewType(e.target.value as 'custom' | 'langchain' | 'api')}
                >
                    <option value="custom">Custom</option>
                    <option value="langchain">LangChain</option>
                    <option value="api">API</option>
                </select>

                {newType === 'custom' && (
                    <>
                        <label htmlFor="newLogic">Logic:</label>
                        <MonacoEditor
                            width="600"
                            height="300"
                            language="json"
                            theme="vs-dark"
                            value={newLogic}
                            options={editorOptions}
                            onChange={(value) => setNewLogic(value)}
                        />
                    </>
                )}

                {newType === 'api' && (
                    <>
                        <label htmlFor="newApiEndpoint">API Endpoint:</label>
                        <input
                            type="text"
                            id="newApiEndpoint"
                            placeholder="API Endpoint URL"
                            value={newApiEndpoint}
                            onChange={(e) => setNewApiEndpoint(e.target.value)}
                        />
                        <label htmlFor="newApiMethod">API Method:</label>
                        <select
                            id="newApiMethod"
                            value={newApiMethod}
                            onChange={(e) => setNewApiMethod(e.target.value as 'GET' | 'POST' | 'PUT' | 'DELETE')}
                        >
                            <option value="GET">GET</option>
                            <option value="POST">POST</option>
                            <option value="PUT">PUT</option>
                            <option value="DELETE">DELETE</option>
                        </select>
                        <label htmlFor="newApiHeaders">API Headers (JSON):</label>
                        <MonacoEditor
                            width="600"
                            height="300"
                            language="json"
                            theme="vs-dark"
                            value={newApiHeaders}
                            options={editorOptions}
                            onChange={(value) => setNewApiHeaders(value)}
                        />
                    </>
                )}

                <label htmlFor="newInputSchema">Input Schema (JSON):</label>
                <MonacoEditor
                    width="600"
                    height="300"
                    language="json"
                    theme="vs-dark"
                    value={newInputSchema}
                    options={editorOptions}
                    onChange={(value) => setNewInputSchema(value)}
                />

                <label htmlFor="newOutputSchema">Output Schema (JSON):</label>
                <MonacoEditor
                    width="600"
                    height="300"
                    language="json"
                    theme="vs-dark"
                    value={newOutputSchema}
                    options={editorOptions}
                    onChange={(value) => setNewOutputSchema(value)}
                />

                <button onClick={handleCreate}>Create</button>
            </div>
        </div>
    );
}
