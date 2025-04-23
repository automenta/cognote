import React, {useState, useEffect, useCallback} from 'react';
import './App.css';
import GraphView from './components/GraphView/GraphView';
import TaskList from './components/TaskList/TaskList';
import NoteEditor from './components/NoteEditor/NoteEditor';
import {initializeSystemNote, getSystemNote, onSystemNoteChange, useSystemNote} from './lib/systemNote';
import {ChatOpenAI} from '@langchain/openai';
import SystemLog from './components/SystemLog/SystemLog';
import SettingsView from './components/Settings/SettingsView';
import TemplatesView from './components/Templates/TemplatesView';
import ToolManager from './components/Templates/ToolManager';
import TaskCreation from './components/TaskList/TaskCreation';
import LLMInterface from './components/LLMInterface/LLMInterface';
import {SettingsService} from './lib/settingsService';

function App() {
    const [selectedNoteId, setSelectedNoteId] = useState<string | null>(null);
    const [isEditing, setIsEditing] = useState(false);
    const [showSettings, setShowSettings] = useState(false);
    const [showTemplates, setShowTemplates] = useState(false);
    const [showToolManager, setShowToolManager] = useState(false);
    const systemNote = useSystemNote();

    const handleTaskSelect = (id: string) => {
        setSelectedNoteId(id);
        setIsEditing(false);
    };

    const handleEdit = () => {
        setIsEditing(true);
    };

    const handleCloseEditor = () => {
        setIsEditing(false);
        setSelectedNoteId(null);
    };

    const handleSettingsToggle = () => {
        setShowSettings(!showSettings);
    };

    const handleTemplatesToggle = () => {
        setShowTemplates(!showTemplates);
    };

    const handleToolManagerToggle = () => {
        setShowToolManager(!showToolManager);
    };

    const handleTaskAdd = () => {
        setSelectedNoteId(null);
        setIsEditing(false);
    };

    return (
        <div className="App">
            <header className="App-header">
                <h1>Netention</h1>
                <div className="header-buttons">
                    <button onClick={handleTaskAdd}>Add Task</button>
                    <button onClick={handleSettingsToggle}>Settings</button>
                    <button onClick={handleTemplatesToggle}>Templates</button>
                    <button onClick={handleToolManagerToggle}>Tool Manager</button>
                </div>
            </header>
            <div className="App-body">
                <div className="sidebar">
                    <TaskList onTaskSelect={handleTaskSelect} selectedId={selectedNoteId}/>
                    <TaskCreation onTaskAdd={handleTaskAdd}/>
                </div>
                <div className="main-content">
                    {selectedNoteId && !isEditing && (
                        <div className="task-actions">
                            <button onClick={handleEdit}>Edit</button>
                        </div>
                    )}
                    {selectedNoteId && isEditing ? (
                        <NoteEditor noteId={selectedNoteId} onClose={handleCloseEditor}
                                    onSave={() => setIsEditing(false)}/>
                    ) : (
                        <GraphView selectedNoteId={selectedNoteId}/>
                    )}
                </div>
            </div>
            <LLMInterface/>
            {showSettings && <SettingsView onClose={handleSettingsToggle}/>}
            {showTemplates && <TemplatesView onClose={handleTemplatesToggle}/>}
            {showToolManager && <ToolManager onClose={handleToolManagerToggle}/>}
            <SystemLog/>
        </div>
    );
}

export default App;
