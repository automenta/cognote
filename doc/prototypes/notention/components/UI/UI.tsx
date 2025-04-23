import React from 'react';
import styles from './UI.module.css';

interface UIViewProps {
    title: string;
    children: React.ReactNode;
}

// UIView component - Generic container for UI views with title and basic styling
export const UIView: React.FC<UIViewProps> = ({title, children}) => {
    return (
        <div className={styles.uiView}>
            <h2>{title}</h2>
            <div className={styles.viewContent}>
                {children}
            </div>
        </div>
    );
};
