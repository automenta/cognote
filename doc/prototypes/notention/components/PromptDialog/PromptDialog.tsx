import React, {useState, useEffect} from 'react';
import styles from './PromptDialog.module.css';

interface PromptDialogProps {
    prompt: string;
    onClose: (userInput: string) => void;
}

const PromptDialog: React.FC<PromptDialogProps> = ({prompt, onClose}) => {
    const [userInput, setUserInput] = useState('');

    useEffect(() => {
        // Focus on the input when the dialog is opened
        const inputElement = document.getElementById('promptInput') as HTMLInputElement;
        if (inputElement) {
            inputElement.focus();
        }
    }, []);

    const handleInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
        setUserInput(e.target.value);
    };

    const handleClose = () => {
        onClose(userInput);
    };

    // Handle pressing Enter key to close the dialog
    const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
        if (e.key === 'Enter') {
            handleClose();
        }
    };

    return (
        <div className={styles.promptDialogOverlay}>
            <div className={styles.promptDialog}>
                <p>{prompt}</p>
                <input
                    type="text"
                    id="promptInput"
                    value={userInput}
                    onChange={handleInputChange}
                    onKeyDown={handleKeyDown}
                    className={styles.promptInput}
                />
                <div className={styles.buttonContainer}>
                    <button onClick={handleClose} className={styles.promptButton}>
                        OK
                    </button>
                </div>
            </div>
        </div>
    );
};

export default PromptDialog;
