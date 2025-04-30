import { websocketClient } from '../client.js';

const outputDiv = document.getElementById('output');
const inputArea = document.getElementById('input');
const sendButton = document.getElementById('send-button');
const clearButton = document.getElementById('clear-button');

// Dialogue Modal Elements
const dialogueModal = document.getElementById('dialogue-modal');
const dialoguePrompt = document.getElementById('dialogue-prompt');
const dialogueInput = document.getElementById('dialogue-input');
const dialogueSendButton = document.getElementById('dialogue-send-button');
const dialogueCancelButton = document.getElementById('dialogue-cancel-button');
const dialogueModalClose = document.getElementById('dialogue-modal-close');

let currentDialogueId = null; // To keep track of the active dialogue request

function appendOutput(message, className = '') {
    const messageElement = document.createElement('div');
    messageElement.classList.add(className);
    // Use textContent for safety, unless specific HTML formatting is needed (not for raw REPL output)
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
    clearButton.disabled = false;
});

websocketClient.on('disconnected', (event) => {
    appendOutput(`${formatTimestamp()} [STATUS] Disconnected from WebSocket. Code: ${event.code}`, 'status');
    sendButton.disabled = true;
    clearButton.disabled = true;
    hideDialogueModal(); // Hide modal if connection drops
});

websocketClient.on('reconnectFailed', () => {
    appendOutput(`${formatTimestamp()} [STATUS] Reconnect attempts failed.`, 'status');
    sendButton.disabled = true;
    clearButton.disabled = true;
    hideDialogueModal(); // Hide modal if connection fails
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
        // Display other events
        appendOutput(`${formatTimestamp()} [EVENT:${eventPayload.eventType}] ${JSON.stringify(eventPayload, null, 2)}`, 'event');
    }
});

// Handle incoming responses
websocketClient.on('response', (responsePayload) => {
    const status = responsePayload.payload?.status || 'unknown';
    const className = status === 'success' ? 'response-success' : (status === 'failure' ? 'response-failure' : 'response-error');
    let responseText = `${formatTimestamp()} [RESPONSE:${status.toUpperCase()}] In Reply To: ${responsePayload.inReplyToId}\n`;

    if (responsePayload.payload?.message) {
        responseText += `Message: ${responsePayload.payload.message}\n`;
    }
    if (responsePayload.payload?.result !== undefined) {
         responseText += `Result: ${JSON.stringify(responsePayload.payload.result, null, 2)}\n`;
    } else if (responsePayload.payload) {
         // If no specific result/message, show the whole payload
         responseText += `Payload: ${JSON.stringify(responsePayload.payload, null, 2)}\n`;
    }


    appendOutput(responseText, className);
});

// Handle incoming initial state snapshot
websocketClient.on('initialState', (statePayload) => {
    appendOutput(`${formatTimestamp()} [INITIAL STATE] Received system snapshot.`, 'status');
    if (statePayload) {
        let summary = "System Snapshot Summary:\n";
        if (statePayload.configuration) {
            summary += `- Config: LLM=${statePayload.configuration.llmModel}, KB Capacity=${statePayload.configuration.globalKbCapacity}, Reasoning Depth=${statePayload.configuration.reasoningDepthLimit}\n`;
        }
        if (statePayload.notes) {
            summary += `- Notes: ${statePayload.notes.length} total\n`;
            // Check if notes are empty and create examples
            if (statePayload.notes.length === 0) {
                appendOutput(`${formatTimestamp()} [STATUS] No notes found. Creating example notes...`, 'status');
                createExampleNotes(); // Call a new function to handle creation
            }
        }
         if (statePayload.rules) {
            summary += `- Rules: ${statePayload.rules.length} total\n`;
        }
         if (statePayload.assertions) {
            summary += `- Assertions: ${statePayload.assertions.length} total\n`;
        }
        appendOutput(summary, 'event');
    }
});


// Handle dialogue requests
websocketClient.on('dialogueRequest', (requestPayload) => {
    appendOutput(`${formatTimestamp()} [DIALOGUE REQUEST] ID: ${requestPayload.dialogueId}, Type: ${requestPayload.requestType}\nPrompt: ${requestPayload.prompt}\nContext: ${JSON.stringify(requestPayload.context, null, 2)}`, 'event');

    // For now, only handle text_input requests with a simple modal
    if (requestPayload.requestType === 'text_input') {
        currentDialogueId = requestPayload.dialogueId;
        dialoguePrompt.textContent = requestPayload.prompt;
        dialogueInput.value = ''; // Clear previous input
        showDialogueModal();
    } else {
        console.warn(`Received unsupported dialogue request type: ${requestPayload.requestType}. Cancelling.`);
        // Automatically cancel unsupported types
         websocketClient.sendCommand('cancelDialogue', { dialogueId: requestPayload.dialogueId })
             .then(() => appendOutput(`${formatTimestamp()} [SENT] Cancelled dialogue ${requestPayload.dialogueId} (unsupported type)`, 'sent'))
             .catch(err => appendOutput(`${formatTimestamp()} [ERROR] Failed to send cancelDialogue command: ${err.message}`, 'response-error'));
    }
});

function showDialogueModal() {
    dialogueModal.classList.add('visible');
    dialogueInput.focus();
}

function hideDialogueModal() {
    dialogueModal.classList.remove('visible');
    currentDialogueId = null;
    dialoguePrompt.textContent = '';
    dialogueInput.value = '';
}

function sendDialogueResponse() {
    if (!currentDialogueId) return;

    const responseText = dialogueInput.value.trim();
    if (!responseText) {
        // Optionally require input or handle empty response differently
        // For now, allow empty response
    }

    const responseData = { text: responseText }; // Assuming text_input type

    appendOutput(`${formatTimestamp()} [SENT] Dialogue response for ${currentDialogueId}: "${responseText}"`, 'sent');

    websocketClient.sendDialogueResponse(currentDialogueId, responseData)
        .then(() => {
            // Response handled by generic 'response' listener
        })
        .catch(err => {
            // Error handled by generic 'response' listener
        });

    hideDialogueModal();
}

function cancelDialogue() {
    if (!currentDialogueId) return;

    appendOutput(`${formatTimestamp()} [SENT] Cancelling dialogue ${currentDialogueId}`, 'sent');

    websocketClient.sendCommand('cancelDialogue', { dialogueId: currentDialogueId })
        .then(() => {
            // Response handled by generic 'response' listener
        })
        .catch(err => {
            // Error handled by generic 'response' listener
        });

    hideDialogueModal();
}


// Send input on button click or Enter key
sendButton.addEventListener('click', sendInput);
clearButton.addEventListener('click', () => {
    outputDiv.innerHTML = ''; // Clear the output div
    appendOutput(`${formatTimestamp()} [STATUS] Output cleared.`, 'status');
});

inputArea.addEventListener('keypress', (event) => {
    // Check for Enter key without Shift (Shift+Enter for newline)
    if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault(); // Prevent newline in textarea
        sendInput();
    }
});

// Dialogue modal event listeners
dialogueSendButton.addEventListener('click', sendDialogueResponse);
dialogueCancelButton.addEventListener('click', cancelDialogue);
dialogueModalClose.addEventListener('click', cancelDialogue); // Close button also cancels
dialogueInput.addEventListener('keypress', (event) => {
    if (event.key === 'Enter') {
        event.preventDefault();
        sendDialogueResponse();
    }
});
// Close modal if clicking outside the content
dialogueModal.addEventListener('click', (event) => {
    if (event.target === dialogueModal) {
        cancelDialogue();
    }
});


function sendInput() {
    const input = inputArea.value.trim();
    if (!input) return;

    inputArea.value = ''; // Clear input immediately

    if (input.startsWith('/')) {
        handleCommand(input);
    } else {
        // Treat as raw KIF input
        const kifStrings = input.split('\n').filter(line => line.trim() !== '' && !line.trim().startsWith(';')); // Basic split by line, ignore comments

        if (kifStrings.length === 0) {
            appendOutput(`${formatTimestamp()} [SENT] No valid KIF lines to send.`, 'sent');
            return;
        }

        appendOutput(`${formatTimestamp()} [SENT] Sending input signal with KIF:\n${kifStrings.join('\n')}`, 'sent');

        websocketClient.sendInput(kifStrings, 'repl-ui')
            .then(response => { /* Handled by generic response listener */ })
            .catch(error => { /* Handled by generic response listener */ });
    }
}

function handleCommand(commandString) {
    const parts = commandString.substring(1).split(/\s+/); // Split by one or more spaces
    const command = parts[0].toLowerCase();
    const args = parts.slice(1).join(' ').trim(); // Rest of the string is arguments

    appendOutput(`${formatTimestamp()} [COMMAND] Executing: /${command} ${args}`, 'sent');

    try {
        switch (command) {
            case 'assert':
            case 'rule':
                // Send as input signal (backend handles parsing KIF into assertions/rules)
                if (!args) throw new Error(`Usage: /${command} <kif_string>`);
                websocketClient.sendInput([args], 'repl-ui')
                    .then(response => { /* Handled by generic response listener */ })
                    .catch(error => { /* Handled by generic response listener */ });
                break;

            case 'query':
            case 'tool':
            case 'wait':
            case 'retract': // Add retract command
                 // These map to backend commands
                if (!args) throw new Error(`Usage: /${command} <arguments>`);

                let commandName, parameters = {};
                let argKif = args; // Default: treat args as KIF string

                // Attempt to parse arguments as KIF list followed by optional JSON params
                const kifMatch = args.match(/^\s*(\(.*\))\s*(\{.*\})?\s*$/);
                if (kifMatch) {
                    argKif = kifMatch[1];
                    try {
                         if (kifMatch[2]) parameters = JSON.parse(kifMatch[2]);
                    } catch (e) {
                         throw new Error(`Invalid JSON parameters: ${e.message}`);
                    }
                } else {
                    // If not a KIF list, maybe it's just a tool name or ID?
                    // Simple case: /tool tool_name {params}
                    // Complex case: /query (pattern) {params}
                    // Let's assume the backend command handlers expect specific parameter structures.
                    // For simplicity here, we'll just pass the raw args string for now,
                    // or try to parse simple cases like tool name + JSON.
                    // A more robust REPL would need a KIF parser client-side.

                    // Simple parsing for /tool <name> {params}
                    if (command === 'tool') {
                         const toolNameMatch = args.match(/^([a-zA-Z0-9_-]+)\s*(\{.*\})?$/);
                         if (toolNameMatch) {
                             commandName = 'runTool'; // Backend command name
                             parameters.name = toolNameMatch[1];
                             try {
                                 if (toolNameMatch[2]) parameters = { ...parameters, ...JSON.parse(toolNameMatch[2]) };
                             } catch (e) {
                                 throw new Error(`Invalid JSON parameters for /tool: ${e.message}`);
                             }
                         } else {
                             throw new Error(`Usage: /tool <tool_name> [{json_params}]`);
                         }
                    } else if (command === 'retract') {
                         // Simple parsing for /retract <BY_KIF|BY_ID> <target>
                         const retractMatch = args.match(/^(BY_KIF|BY_ID)\s+(\(.*\)|[a-zA-Z0-9_-]+)$/);
                         if (retractMatch) {
                             commandName = 'retract'; // Backend command name
                             parameters.type = retractMatch[1];
                             parameters.target = retractMatch[2]; // KIF string or ID string
                         } else {
                             throw new Error(`Usage: /retract <BY_KIF|BY_ID> <target_kif_or_id>`);
                         }
                    }
                    // For /query and /wait, we'll stick to the KIF pattern + optional JSON for now
                    // A client-side KIF parser would be needed for more flexible command args.
                }


                if (!commandName) {
                    // Map REPL command to backend command name if not handled above
                    commandName = command; // Assume command name matches backend command type
                    // For query/wait, the pattern is the main argument
                    if (command === 'query' || command === 'wait') {
                         parameters.pattern = argKif; // Pass the KIF string as 'pattern' parameter
                    }
                }


                // NOTE: Sending these as 'command' signals. If the backend WebSocketPlugin
                // does not handle 'command' signals, this will fail.
                // The backend WebSocketPlugin needs to be updated to receive SIGNAL_TYPE_COMMAND
                // and route it to the RequestProcessorPlugin or appropriate handler.
                // The REPL's /query, /tool, /wait, /retract commands are currently
                // handled by the RequestProcessorPlugin which listens for assertions
                // in kb://client-input. This handler is for future direct commands.
                websocketClient.sendCommand(commandName, parameters)
                    .then(response => { /* Handled by generic response listener */ })
                    .catch(error => { /* Handled by generic response listener */ });

                break;

            case 'clear':
                outputDiv.innerHTML = '';
                appendOutput(`${formatTimestamp()} [STATUS] Output cleared by command.`, 'status');
                break;

            case 'state':
                websocketClient.sendInitialStateRequest()
                    .then(state => { /* Handled by initialState listener */ })
                    .catch(err => { /* Handled by generic response listener */ });
                break;

            default:
                appendOutput(`${formatTimestamp()} [ERROR] Unknown command: /${command}`, 'response-error');
        }
    } catch (e) {
        appendOutput(`${formatTimestamp()} [ERROR] Command failed: ${e.message}`, 'response-error');
        console.error(`Command /${command} failed:`, e);
    }
}


// Function to create example notes
function createExampleNotes() {
    const exampleNotes = [
        {
            id: 'example-note-1',
            title: 'Getting Started: Facts & Queries',
            text: `This note contains basic facts about animals.
(instance MyCat Cat)
(instance YourCat Cat)
(instance MyDog Dog)
(instance MyCat Mammal)

Try querying the system in the REPL input below:
/query (instance ?X Cat)
/query (instance ?Y Mammal)
/query (instance MyCat ?Type)
`,
            status: 'ACTIVE'
        },
        {
            id: 'example-note-2',
            title: 'Reasoning: Rules & Derivations',
            text: `This note defines a simple rule and a fact that should trigger it.
(=> (instance ?X Dog) (attribute ?X Canine))

The fact below should cause the rule to derive (attribute MyDog Canine):
(instance MyDog Dog)

You can query for the derived fact:
/query (attribute MyDog Canine)
`,
            status: 'ACTIVE'
        },
        {
            id: 'example-note-3',
            title: 'LLM Tools: Summarize & Enhance',
            text: `This note has some text that might be useful for LLM tools.

Here is a long paragraph about the benefits of agentic systems. Agentic systems, often powered by large language models and symbolic reasoning engines, promise a new era of intelligent automation. They can process information, make decisions, take actions, and even learn and adapt over time. Unlike traditional software, which follows explicit instructions, agentic systems can operate autonomously towards a goal, handling unforeseen circumstances and integrating information from diverse sources. This capability is particularly valuable in complex, dynamic environments where pre-programmed solutions are insufficient. Potential applications range from advanced personal assistants and automated research agents to sophisticated control systems and creative collaborators. However, developing robust and reliable agentic systems presents significant challenges, including ensuring safety, interpretability, and preventing unintended consequences.

Can you summarize this?
What are the key concepts mentioned?
Suggest some related questions.

Try running tools from the REPL:
/tool summarize { "note_id": "example-note-3" }
/tool identifyConcepts { "note_id": "example-note-3" }
/tool generateQuestions { "note_id": "example-note-3" }
`,
            status: 'ACTIVE'
        },
        {
            id: 'example-note-4',
            title: 'Dialogue & Interaction',
            text: `This note contains a KIF term that uses the (ask-user ...) operator.
(ask-user "What is your favorite color?")

Running this query in the REPL should trigger a dialogue modal asking for your input.
/query (ask-user "What is your favorite color?")

The response you provide will be returned as the result of the query.
`,
            status: 'ACTIVE'
        }
    ];

    // Use sendUiAction for note management operations
    // NOTE: The backend RequestProcessorPlugin (or similar) needs to be updated
    // to listen for uiAction assertions in kb://ui-actions and call the
    // appropriate Cog methods (addNote, updateNoteText, updateNoteStatus).
    // The Note UI relies on NoteAddedEvent, NoteUpdatedEvent, NoteStatusEvent etc.
    // being emitted by the backend after these actions are processed.
    exampleNotes.forEach(noteData => {
        // Send actions sequentially for each note
        websocketClient.sendUiAction('addNote', { noteId: noteData.id, title: noteData.title })
            .then(() => websocketClient.sendUiAction('updateNoteText', { noteId: noteData.id, newText: noteData.text }))
            .then(() => websocketClient.sendUiAction('updateNoteStatus', { noteId: noteData.id, newStatus: noteData.status }))
            .then(() => appendOutput(`${formatTimestamp()} [STATUS] Created example note: "${noteData.title}"`, 'status'))
            .catch(err => appendOutput(`${formatTimestamp()} [ERROR] Failed to create example note "${noteData.title}": ${err.message}`, 'response-error'));
    });
}


// Initial state of the buttons
sendButton.disabled = !websocketClient.isConnected;
clearButton.disabled = !websocketClient.isConnected;

// Request initial state when the client is ready (might already be connected)
// This is now handled automatically by the client on 'connected' event
// if (websocketClient.isConnected) {
//      websocketClient.sendInitialStateRequest()
//         .then(state => {
//             appendOutput(`${formatTimestamp()} [INITIAL STATE] Received system snapshot.`, 'status');
//             // Optionally display parts of the state, e.g., note titles
//             // appendOutput(JSON.stringify(state, null, 2), 'event'); // Too verbose usually
//         })
//         .catch(err => {
//             appendOutput(`${formatTimestamp()} [ERROR] Failed to request initial state: ${err.message}`, 'response-error');
//         });
// }
