import React, {useEffect, useState} from 'react';
import {systemLog} from '../../lib/systemLog';
import styles from './SystemLog.module.css';

// SystemLog component - Displays system logs with functional level filtering
export const SystemLog: React.FC = () => {
    const [expanded, setExpanded] = useState(false);
    const [logs, setLogs] = useState<string[]>([]);
    const logLevels = ['info', 'warning', 'error'];
    const [filterLevel, setFilterLevel] = useState<'info' | 'warning' | 'error' | 'all'>('all');

    // useEffect hook to update logs on systemLog changes (identical)
    useEffect(() => {
        const update = () => setLogs(systemLog.getLogHistory());
        update();
        systemLog.addListener(update);
        return () => systemLog.removeListener(update);
    }, []);

    // Functional log filtering
    const filteredLogs = filterLevel === 'all'
        ? logs
        : logs.filter(log => log.includes(`[${filterLevel.toUpperCase()}]`));

    return (
        <div className={`${styles.systemLog} ${expanded ? styles.expanded : ''}`}>
            <div className={styles.statusBar} onClick={() => setExpanded(!expanded)}>
                System Status: {expanded ? '▲ Hide Log' : '▼ Show Log'} {expanded ? ' 📚' : ' 📜'}
            </div>
            {expanded && (
                <div className={styles.logContainer}>
                    <div className={styles.logFilters}>
                        <button
                            className={`${styles.filterButton} ${filterLevel === 'all' ? styles.activeFilter : ''}`}
                            onClick={() => setFilterLevel('all')}
                        >
                            All Levels
                        </button>
                        {logLevels.map(level => (
                            <button
                                key={level}
                                className={`${styles.filterButton} ${filterLevel === level ? styles.activeFilter : ''}`}
                                onClick={() => setFilterLevel(level as 'info' | 'warning' | 'error')}
                            >
                                {level.toUpperCase()}
                            </button>
                        ))}
                    </div>
                    <div className={styles.logMessages}>
                        {filteredLogs.map((log, i) => <div key={i} className={styles.logMessage}>{log}</div>)}
                    </div>
                </div>
            )}
        </div>
    );
};
