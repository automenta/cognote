class WebSocketClient {
    constructor(url, reconnectDelay = 3000, maxReconnectAttempts = 10) {
        this.url = url;
        this.reconnectDelay = reconnectDelay;
        this.maxReconnectAttempts = maxReconnectAttempts;
        this.reconnectAttempts = 0;
        this.ws = null;
        this.isConnected = false;
        this.messageQueue = [];
        this.eventListeners = new Map();
        this.responseListeners = new Map();
        this.signalIdCounter = 0;
        this.responseTimeout = 15000;

        this._connect();
    }

    _connect() {
        if (this.ws && (this.ws.readyState === WebSocket.CONNECTING || this.ws.readyState === WebSocket.OPEN)) return;

        console.log(`Attempting to connect to WebSocket: ${this.reconnectAttempts + 1}/${this.maxReconnectAttempts}`);
        this.ws = new WebSocket(this.url);

        this.ws.onopen = this._onOpen.bind(this);
        this.ws.onmessage = this._onMessage.bind(this);
        this.ws.onerror = this._onError.bind(this);
        this.ws.onclose = this._onClose.bind(this);
    }

    _onOpen() {
        console.log("WebSocket connected.");
        this.isConnected = true;
        this.reconnectAttempts = 0;
        this._flushQueue();
        this._emit('connected');
    }

    _onMessage(event) {
        try {
            const signal = JSON.parse(event.data);

            if (!signal || typeof signal !== 'object' || signal.type !== 'update') {
                console.warn("Received invalid or non-update signal format:", signal);
                return;
            }

            const {updateType, payload, inReplyToId} = signal;

            if (!updateType || !payload) {
                 console.warn("Received update signal without updateType or payload:", signal);
                 return;
            }

            switch (updateType) {
                case Protocol.UPDATE_TYPE_RESPONSE:
                    this._handleResponse(signal.id, inReplyToId, payload);
                    break;
                case Protocol.UPDATE_TYPE_EVENT:
                    if (payload.eventType) {
                        this._emit(payload.eventType, payload);
                        this._emit('event', payload); // Also emit a generic 'event'
                    } else {
                        console.warn("Received event update without eventType:", signal);
                    }
                    break;
                case Protocol.UPDATE_TYPE_INITIAL_STATE:
                    this._emit(Protocol.UPDATE_TYPE_INITIAL_STATE, payload);
                    break;
                case Protocol.UPDATE_TYPE_DIALOGUE_REQUEST:
                    this._emit(Protocol.UPDATE_TYPE_DIALOGUE_REQUEST, payload);
                    break;
                default:
                    console.warn(`Received unknown update type: ${updateType}`, signal);
            }
        } catch (error) {
            console.error("Error processing WebSocket message:", error, event.data);
        }
    }

    _onError(event) {
        console.error("WebSocket error:", event);
        this._emit('error', event);
    }

    _onClose(event) {
        this.isConnected = false;
        console.log(`WebSocket closed: Code=${event.code}, Reason=${event.reason}, Clean=${event.wasClean}`);
        this._emit('disconnected', event);

        this.responseListeners.forEach(({reject, timeoutId}) => {
            clearTimeout(timeoutId);
            reject(new Error(`WebSocket closed before response received`));
        });
        this.responseListeners.clear();

        if (this.reconnectAttempts < this.maxReconnectAttempts) {
            this.reconnectAttempts++;
            console.log(`Attempting to reconnect in ${this.reconnectDelay / 1000} seconds...`);
            setTimeout(() => this._connect(), this.reconnectDelay);
        } else {
            console.error("Maximum reconnect attempts reached. WebSocket connection failed.");
            this._emit('reconnectFailed');
        }
    }

    _generateSignalId() {
        return `client-${Date.now()}-${this.signalIdCounter++}`;
    }

    _send(signal) {
        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
            this.ws.send(JSON.stringify(signal));
        } else {
            this.messageQueue.push(signal);
        }
    }

    _flushQueue() {
        while (this.messageQueue.length > 0 && this.ws && this.ws.readyState === WebSocket.OPEN) {
            this.ws.send(JSON.stringify(this.messageQueue.shift()));
        }
    }

    sendRequest(command, parameters = {}) {
        const signalId = this._generateSignalId();
        const signal = {
            id: signalId,
            type: 'request', // This type seems consistent with backend
            payload: { command, parameters },
        };

        return new Promise((resolve, reject) => {
            const timeoutId = setTimeout(() => {
                this.responseListeners.delete(signalId);
                reject(new Error(`Response timeout for request ${signalId} (command: ${command})`));
            }, this.responseTimeout);

            this.responseListeners.set(signalId, {resolve, reject, timeoutId});
            this._send(signal);
        });
    }

    _handleResponse(signalId, inReplyToId, payload) {
        const requestId = inReplyToId;
        if (requestId && this.responseListeners.has(requestId)) {
            const {resolve, reject, timeoutId} = this.responseListeners.get(requestId);
            clearTimeout(timeoutId);
            this.responseListeners.delete(requestId);

            // Backend sends status in payload
            payload?.status === Protocol.RESPONSE_STATUS_SUCCESS ?
                resolve(payload.result ?? payload) : // Resolve with result or the whole payload if no result
                reject(new Error(`Request ${requestId} failed: Status=${payload?.status}, Message=${payload?.message}`));
        } else {
            console.warn(`Received response update for unknown or expired request ID: ${requestId}`, {signalId, inReplyToId, payload});
        }
    }

    on(type, listener) {
        this.eventListeners.has(type) || this.eventListeners.set(type, new Set());
        this.eventListeners.get(type).add(listener);
    }

    off(type, listener) {
        if (this.eventListeners.has(type)) {
            this.eventListeners.get(type).delete(listener);
            this.eventListeners.get(type).size === 0 && this.eventListeners.delete(type);
        }
    }

    _emit(type, data) {
        this.eventListeners.has(type) && [...this.eventListeners.get(type)].forEach(listener => {
            try { listener(data); } catch (error) { console.error(`Error in listener for type "${type}":`, error); }
        });
    }

    close() {
        this.ws?.close();
    }
}

// Update WS_PORT to match the new default WebSocket port in the backend
const WS_PORT = 8081;
const WS_URL = `ws://localhost:${WS_PORT}`;

// Define Protocol constants mirroring the backend Protocol.java
export const Protocol = {
    // Command Names (client sends these)
    COMMAND_ASSERT_KIF: 'assertKif',
    COMMAND_RUN_TOOL: 'runTool',
    COMMAND_RUN_QUERY: 'query', // Note: Backend Protocol.java has 'query'
    COMMAND_WAIT: 'wait',
    COMMAND_RETRACT: 'retract',
    COMMAND_CANCEL_DIALOGUE: 'cancelDialogue',
    COMMAND_GET_INITIAL_STATE: 'initialStateRequest', // Corrected based on Protocol.java
    COMMAND_ADD_NOTE: 'addNote',
    COMMAND_UPDATE_NOTE: 'updateNote',
    COMMAND_DELETE_NOTE: 'deleteNote',
    COMMAND_CLONE_NOTE: 'cloneNote',
    COMMAND_CLEAR_ALL: 'clearAll',
    COMMAND_UPDATE_SETTINGS: 'updateSettings',
    COMMAND_DIALOGUE_RESPONSE: 'dialogueResponse',
    COMMAND_SAVE_STATE: 'saveState', // Added based on ui/note/index.js usage (not in backend Protocol.java)
    COMMAND_LOAD_STATE: 'loadState', // Added based on ui/note/index.js usage (not in backend Protocol.java)


    // Update Types (client receives these)
    UPDATE_TYPE_RESPONSE: 'response',
    UPDATE_TYPE_EVENT: 'event',
    UPDATE_TYPE_INITIAL_STATE: 'initialState',
    UPDATE_TYPE_DIALOGUE_REQUEST: 'dialogueRequest',

    // Response Statuses
    RESPONSE_STATUS_SUCCESS: 'success',
    RESPONSE_STATUS_FAILURE: 'failure',
    RESPONSE_STATUS_ERROR: 'error',

    // Event Types (client receives these within UPDATE_TYPE_EVENT)
    // These are examples based on ui/note/index.js usage, not exhaustive, and not defined in backend Protocol.java
    EVENT_TYPE_NOTE_ADDED: 'NoteAddedEvent',
    EVENT_TYPE_NOTE_UPDATED: 'NoteUpdatedEvent',
    EVENT_TYPE_NOTE_DELETED: 'NoteDeletedEvent',
    EVENT_TYPE_SYSTEM_STATUS: 'SystemStatusEvent',
    EVENT_TYPE_LOG_MESSAGE: 'LogMessageEvent',
    // ... potentially others like AssertionAddedEvent, RuleAddedEvent, etc.
};


export const websocketClient = new WebSocketClient(WS_URL);
