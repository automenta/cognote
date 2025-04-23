import React, {useState, useEffect, useCallback} from 'react';
import TaskListDisplay from './TaskListDisplay';
import TaskListFilters from './TaskListFilters';
import {Note, NoteSchema} from '../../types';
import styles from './TaskList.module.css';
import {useSystemNote} from '../../lib/systemNote';

const TaskList: React.FC<{ onTaskSelect: (id: string) => void, selectedId: string | null }> = ({
                                                                                                   onTaskSelect,
                                                                                                   selectedId
                                                                                               }) => {
    const [sortBy, setSortBy] = useState<'priority' | 'status' | 'createdAt'>('priority');
    const [filterByStatus, setFilterByStatus] = useState<'active' | 'pending' | 'completed' | 'failed' | 'dormant' | 'bypassed' | 'pendingRefinement' | 'all'>('pending');
    const [tasks, setTasks] = useState<Note[]>([]);
    const systemNote = useSystemNote();

    useEffect(() => {
        if (!systemNote) return;

        const fetchTasks = async () => {
            const allNotes = await systemNote.getAllNotes();
            const taskNotes = allNotes.filter(note => note.type === 'Task');
            setTasks(taskNotes);
        };

        fetchTasks();

        const unsubscribe = systemNote.onSystemNoteChange(() => {
            fetchTasks();
        });

        return () => unsubscribe();
    }, [systemNote]);

    const handleSortByChange = useCallback((newSortBy: 'priority' | 'status' | 'createdAt') => {
        setSortBy(newSortBy);
    }, []);

    const handleFilterByStatusChange = useCallback((newFilterByStatus: 'active' | 'pending' | 'completed' | 'failed' | 'dormant' | 'bypassed' | 'pendingRefinement' | 'all') => {
        setFilterByStatus(newFilterByStatus);
    }, []);

    const filteredTasks = tasks.filter(task => {
        if (filterByStatus === 'all') return true;
        return task.status === filterByStatus;
    });

    const sortedTasks = [...filteredTasks].sort((a, b) => {
        if (sortBy === 'priority') {
            return b.priority - a.priority;
        }
        if (sortBy === 'status') {
            return a.status.localeCompare(b.status);
        }
        return new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime();
    });

    const handlePriorityChange = useCallback((id: string, newPriority: number) => {
        if (!systemNote) return;
        const taskToUpdate = tasks.find(task => task.id === id);
        if (taskToUpdate) {
            const updatedTask = {...taskToUpdate, priority: newPriority};
            systemNote.updateNote(updatedTask);
        }
    }, [systemNote, tasks]);

    return (
        <div className={styles.taskList}>
            <TaskListFilters sortBy={sortBy} filterByStatus={filterByStatus}
                             onSortByChange={handleSortByChange}
                             onFilterByStatusChange={handleFilterByStatusChange}/>
            <TaskListDisplay tasks={sortedTasks} selectedId={selectedId} onTaskSelect={onTaskSelect}
                             onPriorityChange={handlePriorityChange}/>
        </div>
    );
};

export default TaskList;
