import React, {useState, useCallback} from 'react';
import {useSystemNote} from '../../lib/systemNote';
import {ChatOpenAI} from '@langchain/openai';
import styles from './LLMInterface.module.css';

interface LLMInterfaceProps {
}

const LLMInterface: React.FC<LLMInterfaceProps> = () => {
    const [llmPrompt, setLlmPrompt] = useState('');
    const [llmResponse, setLlmResponse] = useState('');
    const systemNote = useSystemNote();

    const handleLlmPromptChange = useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
        setLlmPrompt(event.target.value);
    }, []);

    const handleLlmSubmit = useCallback(async () => {
        if (!systemNote) return;

        try {
            const llm = systemNote.getLLM();
            if (!llm) {
                setLlmResponse('LLM is not initialized. Check your settings.');
                return;
            }
            const response = await llm.call([llmPrompt]);
            setLlmResponse(response);
        } catch (error: any) {
            setLlmResponse(`Error: ${error.message}`);
        }
    }, [llmPrompt, systemNote]);

    return (
        <div className={styles.llmInterface}>
            <input
                type="text"
                placeholder="Enter LLM prompt"
                value={llmPrompt}
                onChange={handleLlmPromptChange}
                className={styles.llmInput}
            />
            <button onClick={handleLlmSubmit} className={styles.llmButton}>Submit to LLM</button>
            <div>
                <strong>LLM Response:</strong>
                <p>{llmResponse}</p>
            </div>
        </div>
    );
};

export default LLMInterface;
