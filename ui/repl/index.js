import { websocketClient } from '../client.js';

const outputDiv = document.getElementById('output');
const inputArea = document.getElementById('input');
const sendButton = document.getElementById('send-button');
const clearButton = document.getElementById('clear-button');

const dialogueModal = document.getElementById('dialogue-modal');
const dialoguePrompt = document.getElementById('dialogue-prompt');
const dialogueInput = document.getElementById('dialogue-input');
const dialogueSendButton = document.getElementById('dialogue-send-button');
const dialogueCancelButton = document.getElementById('dialogue-cancel-button');
const dialogueModalClose = document.getElementById('dialogue-modal-close');

let currentDialogueId = null;

function appendOutput(message, className = '') {
    const messageElement = document.createElement('div');
    messageElement.classList.add(className);
    messageElement.textContent = message;
    outputDiv.appendChild(messageElement);
    outputDiv.scrollTop = outputDiv.scrollHeight;
}

function formatTimestamp() {
    const now = new Date();
    return now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

websocketClient.on('connected', () => {
    appendOutput(`${formatTimestamp()} [STATUS] Connected to WebSocket.`, 'status');
    sendButton.disabled = false;
    clearButton.disabled = false;
    websocketClient.sendRequest('initialStateRequest', {})
        .catch(err => appendOutput(`${formatTimestamp()} [ERROR] Failed to request initial state: ${err.message}`, 'response-error'));
});

websocketClient.on('disconnected', (event) => {
    appendOutput(`${formatTimestamp()} [STATUS] Disconnected from WebSocket. Code: ${event.code}`, 'status');
    sendButton.disabled = true;
    clearButton.disabled = true;
    hideDialogueModal();
});

websocketClient.on('reconnectFailed', () => {
    appendOutput(`${formatTimestamp()} [STATUS] Reconnect attempts failed.`, 'status');
    sendButton.disabled = true;
    clearButton.disabled = true;
    hideDialogueModal();
});

websocketClient.on('error', (event) => {
    appendOutput(`${formatTimestamp()} [STATUS] WebSocket Error: ${event.message || event}`, 'response-error');
});

websocketClient.on('event', (eventPayload) => {
    eventPayload.eventType === 'LogMessageEvent' ?
        appendOutput(`${formatTimestamp()} [LOG:${(eventPayload.level || 'info').toUpperCase()}] ${eventPayload.message}`, `log-${(eventPayload.level || 'info').toLowerCase()}`) :
        appendOutput(`${formatTimestamp()} [EVENT:${eventPayload.eventType}] ${JSON.stringify(eventPayload, null, 2)}`, 'event');
});

websocketClient.on('response', (responsePayload) => {
    const status = responsePayload?.status || 'unknown';
    const className = status === 'success' ? 'response-success' : (status === 'failure' ? 'response-failure' : 'response-error');
    let responseText = `${formatTimestamp()} [RESPONSE:${status.toUpperCase()}]\n`;

    responsePayload?.message && (responseText += `Message: ${responsePayload.message}\n`);
    responsePayload?.result !== undefined ?
         responseText += `Result: ${JSON.stringify(responsePayload.result, null, 2)}\n` :
         responsePayload && (responseText += `Payload: ${JSON.stringify(responsePayload, null, 2)}\n`);

    appendOutput(responseText, className);
});

websocketClient.on('initialState', (statePayload) => {
    appendOutput(`${formatTimestamp()} [INITIAL STATE] Received system snapshot.`, 'status');
    if (statePayload) {
        let summary = "System Snapshot Summary:\n";
        statePayload.configuration && (summary += `- Config: LLM=${statePayload.configuration.llmModel}, KB Capacity=${statePayload.configuration.globalKbCapacity}, Reasoning Depth=${statePayload.configuration.reasoningDepthLimit}\n`);
        statePayload.notes && (summary += `- Notes: ${statePayload.notes.length} total\n`, statePayload.notes.length === 0 && (appendOutput(`${formatTimestamp()} [STATUS] No notes found. Creating example notes...`, 'status'), createExampleNotes()));
        statePayload.rules && (summary += `- Rules: ${statePayload.rules.length} total\n`);
        statePayload.assertions && (summary += `- Assertions: ${statePayload.assertions.length} total\n`);
        appendOutput(summary, 'event');
    }
});

websocketClient.on('dialogueRequest', (requestPayload) => {
    appendOutput(`${formatTimestamp()} [DIALOGUE REQUEST] ID: ${requestPayload.dialogueId}, Type: ${requestPayload.requestType}\nPrompt: ${requestPayload.prompt}\nContext: ${JSON.stringify(requestPayload.context, null, 2)}`, 'event');

    requestPayload.requestType === 'text_input' ?
        (currentDialogueId = requestPayload.dialogueId, dialoguePrompt.textContent = requestPayload.prompt, dialogueInput.value = '', showDialogueModal()) :
        (console.warn(`Received unsupported dialogue request type: ${requestPayload.requestType}. Cancelling.`),
         websocketClient.sendRequest('cancelDialogue', { dialogueId: requestPayload.dialogueId })
             .then(() => appendOutput(`${formatTimestamp()} [SENT] Cancelled dialogue ${requestPayload.dialogueId} (unsupported type)`, 'sent'))
             .catch(err => appendOutput(`${formatTimestamp()} [ERROR] Failed to send cancelDialogue command: ${err.message}`, 'response-error')));
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
    if (!currentDialogueId || !dialogueInput) return;

    const responseText = dialogueInput.value.trim();
    const responseData = { text: responseText };

    appendOutput(`${formatTimestamp()} [SENT] Dialogue response for ${currentDialogueId}: "${responseText}"`, 'sent');

    websocketClient.sendRequest('dialogueResponse', {
        dialogueId: currentDialogueId,
        responseData: responseData
    }).catch(err => console.error('Failed to send dialogue response:', err));

    hideDialogueModal();
}

function cancelDialogue() {
    if (!currentDialogueId) return;

    appendOutput(`${formatTimestamp()} [SENT] Cancelling dialogue ${currentDialogueId}`, 'sent');

    websocketClient.sendRequest('cancelDialogue', { dialogueId: currentDialogueId })
        .catch(err => appendOutput(`${formatTimestamp()} [ERROR] Failed to send cancelDialogue command: ${err.message}`, 'response-error'));

    hideDialogueModal();
}

sendButton.addEventListener('click', sendInput);
clearButton.addEventListener('click', () => (outputDiv.innerHTML = '', appendOutput(`${formatTimestamp()} [STATUS] Output cleared.`, 'status')));
inputArea.addEventListener('keypress', (event) => event.key === 'Enter' && !event.shiftKey && (event.preventDefault(), sendInput()));

dialogueSendButton.addEventListener('click', sendDialogueResponse);
dialogueCancelButton.addEventListener('click', cancelDialogue);
dialogueModalClose.addEventListener('click', cancelDialogue);
dialogueInput.addEventListener('keypress', (event) => event.key === 'Enter' && (event.preventDefault(), sendDialogueResponse()));
dialogueModal.addEventListener('click', (event) => event.target === dialogueModal && cancelDialogue());

function sendInput() {
    const input = inputArea.value.trim();
    if (!input) return;

    inputArea.value = '';

    input.startsWith('/') ? handleCommand(input) : handleKifInput(input);
}

function handleKifInput(input) {
    const kifStrings = input.split('\n').filter(line => line.trim() !== '' && !line.trim().startsWith(';'));

    if (kifStrings.length === 0) {
        appendOutput(`${formatTimestamp()} [SENT] No valid KIF lines to send.`, 'sent');
        return;
    }

    appendOutput(`${formatTimestamp()} [SENT] Sending assertKif command with KIF:\n${kifStrings.join('\n')}`, 'sent');
    websocketClient.sendRequest('assertKif', { kifStrings: kifStrings }).catch(error => console.error('assertKif failed:', error));
}

function handleCommand(commandString) {
    const parts = commandString.substring(1).split(/\s+/);
    const command = parts[0].toLowerCase();
    const args = parts.slice(1).join(' ').trim();

    appendOutput(`${formatTimestamp()} [COMMAND] Executing: /${command} ${args}`, 'sent');

    try {
        let backendCommand = null;
        let parameters = {};
        let rawArgs = args;

        const kifMatch = rawArgs.match(/^\s*(\(.*\))\s*(\{.*\})?\s*$/);
        if (kifMatch) {
            rawArgs = kifMatch[1];
            try { kifMatch[2] && (parameters = JSON.parse(kifMatch[2])); }
            catch (e) { throw new Error(`Invalid JSON parameters: ${e.message}`); }
        }

        switch (command) {
            case 'assert':
            case 'rule':
                if (!args) throw new Error(`Usage: /${command} <kif_string>`);
                backendCommand = 'assertKif';
                parameters.kifStrings = [args];
                break;
            case 'query':
                if (!rawArgs) throw new Error(`Usage: /query <pattern> [{json_params}]`);
                backendCommand = 'query';
                parameters.pattern = rawArgs;
                break;
            case 'tool':
                const toolNameMatch = args.match(/^([a-zA-Z0-9_-]+)\s*(\{.*\})?$/);
                 if (!toolNameMatch) throw new Error(`Usage: /tool <tool_name> [{json_params}]`);
                backendCommand = 'runTool';
                parameters.name = toolNameMatch[1];
                try { toolNameMatch[2] && (parameters.parameters = JSON.parse(toolNameMatch[2])); }
                catch (e) { throw new Error(`Invalid JSON parameters for /tool: ${e.message}`); }
                break;
            case 'wait':
                if (!rawArgs) throw new Error(`Usage: /wait <condition> [{json_params}]`);
                backendCommand = 'wait';
                parameters.condition = rawArgs;
                break;
            case 'retract':
                 const retractMatch = args.match(/^(BY_KIF|BY_ID)\s+(\(.*\)|[a-zA-Z0-9_-]+)$/);
                 if (!retractMatch) throw new Error(`Usage: /retract <BY_KIF|BY_ID> <target_kif_or_id>`);
                 backendCommand = 'retract';
                 parameters.type = retractMatch[1];
                 parameters.target = retractMatch[2];
                 break;
            case 'clear': backendCommand = 'clearAll'; break;
            case 'state': backendCommand = 'initialStateRequest'; break;
            default: appendOutput(`${formatTimestamp()} [ERROR] Unknown command: /${command}`, 'response-error'); return;
        }

        websocketClient.sendRequest(backendCommand, parameters).catch(error => console.error(`${backendCommand} failed:`, error));

    } catch (e) {
        appendOutput(`${formatTimestamp()} [ERROR] Command failed: ${e.message}`, 'response-error');
        console.error(`Command /${command} failed:`, e);
    }
}

function createExampleNotes() {
    const exampleNotes = [
        { title: 'Getting Started: Facts & Queries', content: `This note contains basic facts about animals.\n(instance MyCat Cat)\n(instance YourCat Cat)\n(instance MyDog Dog)\n(instance MyCat Mammal)\n\nTry querying the system in the REPL input below:\n/query (instance ?X Cat)\n/query (instance ?Y Mammal)\n/query (instance MyCat ?Type)\n`, state: 'ACTIVE' },
        { title: 'Reasoning: Rules & Derivations', content: `This note defines a simple rule and a fact that should trigger it.\n(=> (instance ?X Dog) (attribute ?X Canine))\n\nThe fact below should cause the rule to derive (attribute MyDog Canine):\n(instance MyDog Dog)\n\nYou can query for the derived fact:\n/query (attribute MyDog Canine)\n`, state: 'ACTIVE' },
        { title: 'LLM Tools: Summarize & Enhance', content: `This note has some text that might be useful for LLM tools.\n\nHere is a long paragraph about the benefits of agentic systems. Agentic systems, often powered by large language models and symbolic reasoning engines, promise a new era of intelligent automation. They can process information, make decisions, take actions, and even learn and adapt over time. Unlike traditional software, which follows explicit instructions, agentic systems can operate autonomously towards a goal, handling unforeseen circumstances and integrating information from diverse sources. This capability is particularly valuable in complex, dynamic environments where pre-programmed solutions are insufficient. Potential applications range from advanced personal assistants and automated research agents to sophisticated control systems and creative collaborators. However, developing robust and reliable agentic systems presents significant challenges, including ensuring safety, interpretability, and preventing unintended consequences.\n\nCan you summarize this?\nWhat are the key concepts mentioned?\nSuggest some related questions.\n\nTry running tools from the REPL:\n/tool summarize { "note_id": "example-note-3" }\n/tool identifyConcepts { "note_id": "example-note-3" }\n/tool generateQuestions { "note_id": "example-note-3" }\n`, state: 'ACTIVE' },
        { title: 'Dialogue & Interaction', content: `This note contains a KIF term that uses the (ask-user ...) operator.\n(ask-user "What is your favorite color?")\n\nRunning this query in the REPL should trigger a dialogue modal asking for your input.\n/query (ask-user "What is your favorite color?")\n\nThe response you provide will be returned as the result of the query.\n`, state: 'ACTIVE' }
    ];

    exampleNotes.forEach(noteData => {
        websocketClient.sendRequest('addNote', {
            title: noteData.title,
            content: noteData.content,
            state: noteData.state,
        })
            .then(() => appendOutput(`${formatTimestamp()} [STATUS] Created example note: "${noteData.title}"`, 'status'))
            .catch(err => appendOutput(`${formatTimestamp()} [ERROR] Failed to create example note "${noteData.title}": ${err.message}`, 'response-error'));
    });
}

sendButton.disabled = !websocketClient.isConnected;
clearButton.disabled = !websocketClient.isConnected;
