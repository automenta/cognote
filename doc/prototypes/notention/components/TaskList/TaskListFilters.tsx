import React, {useCallback} from 'react';
import styles from './TaskList.module.css';

interface TaskListFiltersProps {
    sortBy: 'priority' | 'status' | 'createdAt';
    filterByStatus: 'active' | 'pending' | 'completed' | 'failed' | 'dormant' | 'bypassed' | 'pendingRefinement' | 'all';
    onSortByChange: (sortBy: 'priority' | 'status' | 'createdAt') => void;
    onFilterByStatusChange: (filterByStatus: 'active' | 'pending' | 'completed' | 'failed' | 'dormant' | 'bypassed' | 'pendingRefinement' | 'all') => void;
}

export const TaskListFilters: React.FC<TaskListFiltersProps> = ({
                                                                    sortBy,
                                                                    filterByStatus,
                                                                    onSortByChange,
                                                                    onFilterByStatusChange,
                                                                }) => {
    const handleSortByChange = useCallback((e: React.ChangeEvent<HTMLSelectElement>) => {
        onSortByChange(e.target.value as 'priority' | 'status' | 'createdAt');
    }, [onSortByChange]);

    const handleFilterByStatusChange = useCallback((e: React.ChangeEvent<HTMLSelectElement>) => {
        onFilterByStatusChange(e.target.value as 'active' | 'pending' | 'completed' | 'failed' | 'dormant' | 'bypassed' | 'pendingRefinement' | 'all');
    }, [onFilterByStatusChange]);

    return (
        <div className={styles.taskListFilters}>
            <select value={sortBy} onChange={handleSortByChange}>
                <option value="priority">Sort by Priority</option>
                <option value="createdAt">Sort by Date</option>
                <option value="status">Sort by Status</option>
            </select>
            <select value={filterByStatus} onChange={handleFilterByStatusChange}>
                <option value="all">Show All</option>
                <option value="active">Active</option>
                <option value="pending">Pending</option>
                <option value="completed">Completed</option>
                <option value="failed">Failed</option>
                <option value="dormant">Dormant</option>
                <option value="bypassed">Bypassed</option>
                <option value="pendingRefinement">Pending Refinement</option>
            </select>
        </div>
    );
};
