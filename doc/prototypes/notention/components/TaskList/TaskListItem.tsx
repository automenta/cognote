import React, {useCallback} from 'react';
import {Note} from '../../types';
import styles from './TaskListItem.module.css';

interface TaskListItemProps {
    task: Note;
    onPriorityChange: (id: string, priority: number) => void;
    onClick: (id: string) => void;
    isSelected: boolean;
}

const TaskListItem: React.FC<TaskListItemProps> = ({task, onPriorityChange, onClick, isSelected}) => {
    const handlePriorityChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
        const newPriority = parseInt(e.target.value, 10);
        onPriorityChange(task.id, newPriority);
    }, [task.id, onPriorityChange]);

    const getStatusColor = (status: string) => {
        switch (status) {
            case 'active':
                return styles.active;
            case 'pending':
                return styles.pending;
            case 'completed':
                return styles.completed;
            case 'failed':
                return styles.failed;
            case 'dormant':
                return styles.dormant;
            case 'bypassed':
                return styles.bypassed;
            case 'pendingRefinement':
                return styles.pendingRefinement;
            case 'running':
                return styles.running;
            default:
                return styles.unknown;
        }
    };

    return (
        <div className={`${styles.taskListItem} ${isSelected ? styles.selected : ''}`} onClick={() => onClick(task.id)}>
            <div className={`${styles.statusIndicator} ${getStatusColor(task.status)}`}/>
            <div className={styles.taskContent}>
                <h3>{task.title}</h3>
                <p>{task.content}</p>
            </div>
            <div className={styles.taskPriority}>
                <label htmlFor={`priority-${task.id}`}>Priority:</label>
                <input
                    type="number"
                    id={`priority-${task.id}`}
                    value={task.priority}
                    onChange={handlePriorityChange}
                />
            </div>
        </div>
    );
};

export default TaskListItem;
