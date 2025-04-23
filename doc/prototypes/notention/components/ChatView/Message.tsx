import React from 'react';
import styles from './ChatView.module.css';

interface MessageProps {
    message: {
        type: 'user' | 'system' | 'tool' | 'error';
        content: string;
        timestamp?: string;
    };
}

// Message component - Displays individual chat messages with styling
const Message: React.FC<MessageProps> = ({message}) => {
    const messageClass = () => {
        switch (message.type) {
            case 'user':
                return styles.userMessage;
            case 'system':
                return styles.systemMessage;
            case 'tool':
                return styles.toolMessage;
            case 'error':
                return styles.errorMessage;
            default:
                return styles.message;
        }
    };

    return (
        <div className={`${styles.message} ${messageClass()}`}>
            <div className={styles.messageContent}>
                {message.type === 'error' ? (
                    <pre>{message.content}</pre>
                ) : (
                    message.content
                )}
            </div>
            <div className={styles.messageTimestamp}>
                {message.timestamp && new Date(message.timestamp).toLocaleTimeString()}
            </div>
        </div>
    );
};

export default Message;
