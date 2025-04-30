import { websocketClient } from '../client.js';

const outputDiv = document.getElementById('output');
const inputArea = document.getElementById('input');
const sendButton = document.getElementById('send-button');

function appendOutput(message, className = '') {
    const messageElement = document.createElement('div');
    messageElement.classList.add(className);
    messageElement.textContent = message;
    outputDiv.appendChild(messageElement);
    // Auto-scroll to the bottom
    outputDiv.scrollTop = outputDiv.scrollHeight;
}

function formatTimestamp() {
    const now = new Date();
    return now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

// Handle WebSocket connection status
websocketClient.on('connected', () => {
    appendOutput(`${formatTimestamp()} [STATUS] Connected to WebSocket.`, 'status');
    sendButton.disabled = false;
});

websocketClient.on('disconnected', (event) => {
    appendOutput(`${formatTimestamp()} [STATUS] Disconnected from WebSocket. Code: ${event.code}`, 'status');
    sendButton.disabled = true;
});

websocketClient.on('reconnectFailed', () => {
    appendOutput(`${formatTimestamp()} [STATUS] Reconnect attempts failed.`, 'status');
    sendButton.disabled = true;
});

websocketClient.on('error', (event) => {
    appendOutput(`${formatTimestamp()} [STATUS] WebSocket Error: ${event.message || event}`, 'response-error');
});

// Handle incoming events
websocketClient.on('event', (eventPayload) => {
    // Filter out LogMessageEvent here to handle them separately with specific styling
    if (eventPayload.eventType === 'LogMessageEvent') {
        const level = eventPayload.level ? eventPayload.level.toLowerCase() : 'info';
        appendOutput(`${formatTimestamp()} [LOG:${level.toUpperCase()}] ${eventPayload.message}`, `log-${level}`);
    } else {
        appendOutput(`${formatTimestamp()} [EVENT:${eventPayload.eventType}] ${JSON.stringify(eventPayload, null, 2)}`, 'event');
    }
});

// Handle incoming responses
websocketClient.on('response', (responsePayload) => {
    const status = responsePayload.payload?.status || 'unknown';
    const className = status === 'success' ? 'response-success' : (status === 'failure' ? 'response-failure' : 'response-error');
    appendOutput(`${formatTimestamp()} [RESPONSE:${status.toUpperCase()}] In Reply To: ${responsePayload.inReplyToId}\nPayload: ${JSON.stringify(responsePayload.payload, null, 2)}`, className);
});

// Handle dialogue requests (basic logging for now)
websocketClient.on('dialogueRequest', (requestPayload) => {
    appendOutput(`${formatTimestamp()} [DIALOGUE REQUEST] ID: ${requestPayload.dialogueId}, Type: ${requestPayload.requestType}\nPrompt: ${requestPayload.prompt}\nContext: ${JSON.stringify(requestPayload.context, null, 2)}`, 'event');
    // TODO: Implement a simple modal or input for dialogue responses if needed
    // For now, just log and maybe send a canned response or cancel after a delay
    console.warn("Dialogue request received, no UI handler implemented. Cancelling after 5s.");
    setTimeout(() => {
         // Example: Send a canned response for text input
         if (requestPayload.requestType === 'text_input') {
             websocketClient.sendDialogueResponse(requestPayload.dialogueId, { text: "Canned response from REPL UI" })
                 .then(() => appendOutput(`${formatTimestamp()} [SENT] Canned dialogue response for ${requestPayload.dialogueId}`, 'sent'))
                 .catch(err => appendOutput(`${formatTimestamp()} [ERROR] Failed to send canned dialogue response: ${err.message}`, 'response-error'));
         } else {
             websocketClient.sendCommand('cancelDialogue', { dialogueId: requestPayload.dialogueId })
                 .then(() => appendOutput(`${formatTimestamp()} [SENT] Cancelled dialogue ${requestPayload.dialogueId}`, 'sent'))
                 .catch(err => appendOutput(`${formatTimestamp()} [ERROR] Failed to send cancelDialogue command: ${err.message}`, 'response-error'));
         }
    }, 5000); // Cancel after 5 seconds
});


// Send input on button click or Enter key
sendButton.addEventListener('click', sendInput);
inputArea.addEventListener('keypress', (event) => {
    // Check for Enter key without Shift (Shift+Enter for newline)
    if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault(); // Prevent newline in textarea
        sendInput();
    }
});

function sendInput() {
    const input = inputArea.value.trim();
    if (!input) return;

    // For this simple REPL, assume input is KIF and send as an 'input' signal
    // In a more complex REPL, you might parse commands like /commandName {json_params}
    const kifStrings = input.split('\n').filter(line => line.trim() !== '' && !line.trim().startsWith(';')); // Basic split by line, ignore comments

    if (kifStrings.length === 0) {
        appendOutput(`${formatTimestamp()} [SENT] No valid KIF lines to send.`, 'sent');
        inputArea.value = '';
        return;
    }

    appendOutput(`${formatTimestamp()} [SENT] Sending input signal with KIF:\n${kifStrings.join('\n')}`, 'sent');

    // Send the input signal and handle the response promise
    websocketClient.sendInput(kifStrings, 'repl-ui')
        .then(response => {
            // Response is handled by the generic 'response' listener
            // appendOutput(`${formatTimestamp()} [RESPONSE:SUCCESS] ${JSON.stringify(response, null, 2)}`, 'response-success');
        })
        .catch(error => {
             // Error response is handled by the generic 'response' listener
             // appendOutput(`${formatTimestamp()} [RESPONSE:ERROR] ${error.message}`, 'response-error');
        });

    inputArea.value = ''; // Clear input after sending
}

// Initial state of the button
sendButton.disabled = !websocketClient.isConnected;

// Request initial state when the client is ready (might already be connected)
if (websocketClient.isConnected) {
     websocketClient.sendInitialStateRequest()
        .then(state => {
            appendOutput(`${formatTimestamp()} [INITIAL STATE] Received system snapshot.`, 'status');
            // Optionally display parts of the state, e.g., note titles
            // appendOutput(JSON.stringify(state, null, 2), 'event'); // Too verbose usually
        })
        .catch(err => {
            appendOutput(`${formatTimestamp()} [ERROR] Failed to request initial state: ${err.message}`, 'response-error');
        });
}
