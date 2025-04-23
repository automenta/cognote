// client.js
(function() {
    const WS_PORT = 3001; //TODO share port with the static HTTP server
    const WS_URL = `ws://localhost:${WS_PORT}`; // Make sure WS_PORT matches server
    let ws;
    let currentConfig = {};
    let currentRunState = 'N/A';
    let reconnectAttempts = 0;
    const MAX_RECONNECT_ATTEMPTS = 10;
    const RECONNECT_DELAY = 3000; // ms

    // --- DOM Elements ---
    const app = document.getElementById('app');
    const statusBar = document.getElementById('status-bar');
    const errorBox = document.getElementById('error-box');
    const thoughtInput = document.getElementById('thought-input');
    const addThoughtBtn = document.getElementById('add-thought-btn');
    const thoughtList = document.getElementById('thought-list');
    const promptPanel = document.getElementById('prompt-panel');
    const promptList = document.getElementById('prompt-list');
    const settingsPanel = document.getElementById('settings-panel');
    const settingsToggleBtn = document.getElementById('settings-toggle-btn');
    const settingsSaveBtn = document.getElementById('settings-save-btn');
    const settingsCancelBtn = document.getElementById('settings-cancel-btn');
    const settingLlmEndpoint = document.getElementById('setting-llm-endpoint');
    const settingOllamaModel = document.getElementById('setting-ollama-model');
    const settingAutosave = document.getElementById('setting-autosave');
    const debugPanel = document.getElementById('debug-panel');
    const debugToggleBtn = document.getElementById('debug-toggle-btn');
    const schedulerStatus = document.getElementById('scheduler-status');
    const pauseBtn = document.getElementById('pause-btn');
    const stepBtn = document.getElementById('step-btn');
    const runBtn = document.getElementById('run-btn');
    const undoBtn = document.getElementById('undo-btn');
    const saveBtn = document.getElementById('save-btn');
    const loadBtn = document.getElementById('load-btn');


    // --- Utility Functions ---
    function updateStatus(message, isError = false) {
        statusBar.textContent = message;
        statusBar.style.color = isError ? 'var(--danger-color)' : 'var(--text-muted)';
    }

    function showError(message) {
        errorBox.textContent = message;
        errorBox.style.display = 'block';
        // Auto-hide error after some time
        setTimeout(() => { errorBox.style.display = 'none'; }, 5000);
    }

    function sendWsMessage(type, payload = {}) {
        if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({ type, payload }));
        } else {
            showError("WebSocket not connected. Cannot send message.");
            console.error("Attempted to send message while WebSocket not open:", type, payload);
        }
    }

    // --- UI Rendering Functions ---
    function renderThoughts(thoughts = []) {
        thoughtList.innerHTML = ''; // Clear existing
        if (!Array.isArray(thoughts)) {
            console.error("Invalid thoughts data received:", thoughts);
            thoughts = [];
        }
        thoughts.forEach(thought => {
            const li = document.createElement('li');
            li.className = 'thought-item';
            li.id = `thought-${thought.uuid}`; // Add ID for potential targeted updates

            // Sanitize status for class name
            const statusClass = `status-${(thought.status || 'unknown').toLowerCase().replace(/[^a-z0-9]/g, '')}`;

            li.innerHTML = `
                <div class="thought-content">
                    <span class="thought-status ${statusClass}">${thought.status || 'unknown'}</span>
                    <strong>${escapeHtml(thought.contentStr || '')}</strong>
                    <div class="thought-meta">
                        Priority: ${thought.goalValue?.toFixed(2) || 'N/A'} (${escapeHtml(thought.goalSource || '?')})
                        - Created: ${new Date(thought.createdAt || 0).toLocaleString()}
                         ${thought.meta && Object.keys(thought.meta).length > 0 ? `- Meta: ${escapeHtml(JSON.stringify(thought.meta))}` : ''}
                    </div>
                </div>
                <div class="thought-actions">
                    <button class="btn-success btn-small" data-uuid="${thought.uuid}" data-delta="0.1" title="Increase Priority">+</button>
                    <button class="btn-danger btn-small" data-uuid="${thought.uuid}" data-delta="-0.1" title="Decrease Priority">-</button>
                </div>
            `;
            thoughtList.appendChild(li);
        });
        attachGoalButtonListeners();
    }

    function attachGoalButtonListeners() {
        thoughtList.querySelectorAll('.thought-actions button').forEach(button => {
            // Remove old listener before adding new one to prevent duplicates
            button.removeEventListener('click', handleGoalButtonClick);
            button.addEventListener('click', handleGoalButtonClick);
        });
    }

    function handleGoalButtonClick(event) {
        const button = event.currentTarget;
        const uuid = button.dataset.uuid;
        const delta = parseFloat(button.dataset.delta);
        if (uuid && !isNaN(delta)) {
            sendWsMessage('update_goal', { thoughtUUID: uuid, delta: delta });
        }
    }


    function renderPrompts(prompts = []) {
        promptList.innerHTML = ''; // Clear existing
        if (!Array.isArray(prompts) || prompts.length === 0) {
            promptPanel.style.display = 'none';
            return;
        }

        prompts.forEach(prompt => {
            const div = document.createElement('div');
            div.className = 'prompt-item';
            div.id = `prompt-${prompt.promptId}`;
            div.innerHTML = `
                 <p>${escapeHtml(prompt.text || 'N/A')}</p>
                 <input type="text" data-prompt-id="${prompt.promptId}" placeholder="Your response..." />
                 <button class="btn-primary btn-small" data-prompt-id="${prompt.promptId}">Submit</button>
             `;
            // Add event listener for Enter key on input
            const input = div.querySelector('input');
            input?.addEventListener('keydown', (e) => {
                if (e.key === 'Enter') {
                    submitPromptResponse(prompt.promptId, input.value);
                    input.value = ''; // Clear after sending
                }
            });
            // Add event listener for submit button
            const button = div.querySelector('button');
            button?.addEventListener('click', () => {
                if(input) {
                    submitPromptResponse(prompt.promptId, input.value);
                    input.value = ''; // Clear after sending
                }
            });

            promptList.appendChild(div);
        });
        promptPanel.style.display = 'block';
    }

    function submitPromptResponse(promptId, response) {
        const trimmedResponse = response.trim();
        if (!trimmedResponse) {
            showError("Response cannot be empty.");
            return;
        }
        sendWsMessage('user_input_response', { promptId: promptId, response: trimmedResponse });
        // UI prompt will be removed automatically when next state update arrives
    }

    function updateSchedulerUI(state) {
        currentRunState = state || 'N/A';
        schedulerStatus.textContent = currentRunState;
        pauseBtn.disabled = currentRunState !== 'running';
        stepBtn.disabled = currentRunState !== 'paused';
        runBtn.disabled = currentRunState === 'running';
    }

    function renderSettings() {
        settingLlmEndpoint.value = currentConfig.llmEndpoint || '';
        settingOllamaModel.value = currentConfig.ollamaModel || '';
        settingAutosave.value = currentConfig.autoSaveInterval || 30000;
    }

    function escapeHtml(unsafe) {
        if (unsafe === null || typeof unsafe === 'undefined') return '';
        return String(unsafe)
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&#039;");
    }


    // --- WebSocket Event Handlers ---
    function connectWebSocket() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            updateStatus(`WebSocket connection failed after ${MAX_RECONNECT_ATTEMPTS} attempts. Please refresh.`, true);
            return;
        }
        if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
            console.log("WebSocket already open or connecting.");
            return;
        }

        reconnectAttempts++;
        updateStatus(`Connecting to WebSocket... (Attempt ${reconnectAttempts})`);
        ws = new WebSocket(WS_URL);

        ws.onopen = () => {
            updateStatus("WebSocket Connected");
            reconnectAttempts = 0; // Reset attempts on successful connection
            // Request initial state from server
            sendWsMessage('get_initial_state');
        };

        ws.onmessage = (event) => {
            try {
                const message = JSON.parse(event.data);
                // console.log('WS Received:', message.type); // Debugging

                switch (message.type) {
                    case 'initial_state':
                        renderThoughts(message.payload.thoughts);
                        renderPrompts(message.payload.prompts);
                        currentConfig = message.payload.config || {};
                        updateSchedulerUI(message.payload.runState);
                        renderSettings(); // Populate settings initially
                        break;
                    case 'state_update':
                        renderThoughts(message.payload.thoughts);
                        renderPrompts(message.payload.prompts);
                        break;
                    case 'request_user_input':
                        // Server manages prompts list, we just render it via state_update
                        // Could add a notification here if desired
                        console.log("User input requested:", message.payload);
                        // Ensure the prompt panel is visible if it wasn't
                        // renderPrompts will handle this based on the next state_update
                        break;
                    case 'config_update':
                        currentConfig = message.payload || {};
                        renderSettings();
                        break;
                    case 'status_update':
                        if (message.payload.runState) {
                            updateSchedulerUI(message.payload.runState);
                        }
                        if (message.payload.message) {
                            updateStatus(message.payload.message);
                        }
                        break;
                    case 'error':
                        showError(`Server Error: ${message.payload?.message || 'Unknown error'}`);
                        break;
                    default:
                        console.warn('Unknown message type from server:', message.type);
                }
            } catch (e) {
                console.error('Failed to parse server message:', event.data, e);
                showError("Received invalid message from server.");
            }
        };

        ws.onclose = () => {
            updateStatus("WebSocket Closed. Attempting to reconnect...", true);
            // Attempt to reconnect after a delay
            setTimeout(connectWebSocket, RECONNECT_DELAY);
        };

        ws.onerror = (error) => {
            console.error('WebSocket Error:', error);
            updateStatus("WebSocket Connection Error.", true);
            // ws.close(); // Ensure it's closed before reconnecting
            // Connection will likely close anyway, onclose handler will trigger reconnect
        };
    }

    // --- UI Event Listeners ---
    addThoughtBtn.addEventListener('click', () => {
        const content = thoughtInput.value.trim();
        if (content) {
            sendWsMessage('add_thought', { content });
            thoughtInput.value = '';
        } else {
            showError("Thought content cannot be empty.");
        }
    });

    thoughtInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            addThoughtBtn.click();
        }
    });

    settingsToggleBtn.addEventListener('click', () => {
        const isVisible = settingsPanel.style.display === 'block';
        settingsPanel.style.display = isVisible ? 'none' : 'block';
        if (isVisible) {
            // Reset fields if canceling
            renderSettings();
        }
    });

    settingsSaveBtn.addEventListener('click', () => {
        const updatedConfig = {
            llmEndpoint: settingLlmEndpoint.value,
            ollamaModel: settingOllamaModel.value,
            autoSaveInterval: parseInt(settingAutosave.value) || 30000
        };
        sendWsMessage('update_config', { config: updatedConfig });
        settingsPanel.style.display = 'none'; // Hide after save attempt
    });

    settingsCancelBtn.addEventListener('click', () => {
        settingsPanel.style.display = 'none';
        renderSettings(); // Reset fields
    });


    debugToggleBtn.addEventListener('click', () => {
        debugPanel.style.display = debugPanel.style.display === 'block' ? 'none' : 'block';
        // Optionally request debug data if needed when opening
        // if (debugPanel.style.display === 'block') sendWsMessage('get_debug_data');
    });

    pauseBtn.addEventListener('click', () => sendWsMessage('control_scheduler', { command: 'pause' }));
    stepBtn.addEventListener('click', () => sendWsMessage('control_scheduler', { command: 'step' }));
    runBtn.addEventListener('click', () => sendWsMessage('control_scheduler', { command: 'run' }));
    undoBtn.addEventListener('click', () => sendWsMessage('request_undo'));
    saveBtn.addEventListener('click', () => sendWsMessage('request_save'));
    loadBtn.addEventListener('click', () => sendWsMessage('request_load'));


    // --- Initial Connection ---
    connectWebSocket();

}());
