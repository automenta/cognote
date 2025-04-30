// ui/client.js

/**
 * WebSocket Client for connecting to the Cognote backend.
 * Provides methods for sending requests and subscribing to updates/events.
 */
class WebSocketClient {
    constructor(url, reconnectDelay = 3000, maxReconnectAttempts = 10) {
        this.url = url;
        this.reconnectDelay = reconnectDelay;
        this.maxReconnectAttempts = maxReconnectAttempts;
        this.reconnectAttempts = 0;
        this.ws = null;
        this.isConnected = false;
        this.messageQueue = []; // Queue for messages sent before connection is open
        this.eventListeners = new Map(); // Map<eventType, Set<listener>>
        this.responseListeners = new Map(); // Map<signalId, { resolve, reject, timeoutId }>
        this.signalIdCounter = 0;
        this.responseTimeout = 15000; // Timeout for waiting for a specific response (ms)

        // Unified signal types
        this.SIGNAL_TYPE_REQUEST = 'request';
        this.SIGNAL_TYPE_UPDATE = 'update';

        // Update types
        this.UPDATE_TYPE_RESPONSE = 'response';
        this.UPDATE_TYPE_EVENT = 'event';
        this.UPDATE_TYPE_INITIAL_STATE = 'initialState';
        this.UPDATE_TYPE_DIALOGUE_REQUEST = 'dialogueRequest';

        this._connect();
    }

    _connect() {
        if (this.ws && (this.ws.readyState === WebSocket.CONNECTING || this.ws.readyState === WebSocket.OPEN)) {
            console.log("WebSocket already connecting or open.");
            return;
        }

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
        // Initial state is now requested by the UI components upon connection
    }

    _onMessage(event) {
        try {
            const signal = JSON.parse(event.data);
            // console.debug("WS received:", signal);

            if (!signal || typeof signal !== 'object' || signal.type !== this.SIGNAL_TYPE_UPDATE) {
                console.warn("Received invalid or non-update signal format:", signal);
                return;
            }

            const updateType = signal.updateType;
            const payload = signal.payload;
            const inReplyToId = signal.inReplyToId;

            if (!updateType || !payload) {
                 console.warn("Received update signal without updateType or payload:", signal);
                 return;
            }

            switch (updateType) {
                case this.UPDATE_TYPE_RESPONSE:
                    this._handleResponse(signal.id, inReplyToId, payload);
                    break;
                case this.UPDATE_TYPE_EVENT:
                    if (payload.eventType) {
                        this._emit(payload.eventType, payload);
                        // Also emit a generic 'event' for listeners interested in all events
                        this._emit('event', payload);
                    } else {
                        console.warn("Received event update without eventType:", signal);
                    }
                    break;
                case this.UPDATE_TYPE_INITIAL_STATE:
                    this._emit('initialState', payload);
                    break;
                case this.UPDATE_TYPE_DIALOGUE_REQUEST:
                    this._emit('dialogueRequest', payload);
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

        // Reject any pending responses
        this.responseListeners.forEach(({reject, timeoutId}, signalId) => {
            clearTimeout(timeoutId);
            reject(new Error(`WebSocket closed before response received for request ${signalId}`));
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
            // console.debug("WS sending:", signal);
            this.ws.send(JSON.stringify(signal));
        } else {
            // console.debug("WS queuing:", signal);
            this.messageQueue.push(signal);
        }
    }

    _flushQueue() {
        while (this.messageQueue.length > 0 && this.ws && this.ws.readyState === WebSocket.OPEN) {
            const signal = this.messageQueue.shift();
            // console.debug("WS flushing queued:", signal);
            this.ws.send(JSON.stringify(signal));
        }
    }

    /**
     * Sends a request signal to the backend and returns a Promise for the response.
     * @param {string} command - The command name (e.g., 'addNote', 'runTool', 'query').
     * @param {object} [parameters] - Optional parameters for the command.
     * @returns {Promise<object>} A promise that resolves with the response payload on success or rejects on failure/error/timeout.
     */
    sendRequest(command, parameters = {}) {
        const signalId = this._generateSignalId();
        const signal = {
            id: signalId,
            type: this.SIGNAL_TYPE_REQUEST,
            payload: {
                command: command,
                parameters: parameters,
            },
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
        // The inReplyToId for a response update is the ID of the original request signal
        const requestId = inReplyToId;
        if (requestId && this.responseListeners.has(requestId)) {
            const {resolve, reject, timeoutId} = this.responseListeners.get(requestId);
            clearTimeout(timeoutId);
            this.responseListeners.delete(requestId);

            if (payload && payload.status === 'success') {
                // Resolve with the result or the whole payload if no specific result
                resolve(payload.result ?? payload);
            } else {
                // Reject with an error containing status and message
                const errorMsg = `Request ${requestId} failed: Status=${payload?.status}, Message=${payload?.message}`;
                reject(new Error(errorMsg));
            }
        } else {
            console.warn(`Received response update for unknown or expired request ID: ${requestId}`, {signalId, inReplyToId, payload});
            // Still emit as a generic response event if needed, though the new protocol
            // aims to handle responses via promises primarily.
            // this._emit('response', { signalId, inReplyToId, payload });
        }
    }


    /**
     * Subscribes a listener function to a specific update/event type.
     * @param {string} type - The type of update/event (e.g., 'connected', 'disconnected', 'error', 'initialState', 'dialogueRequest', 'event', 'AssertedEvent', 'NoteUpdatedEvent').
     * @param {function} listener - The function to call when the update/event occurs.
     */
    on(type, listener) {
        if (!this.eventListeners.has(type)) {
            this.eventListeners.set(type, new Set());
        }
        this.eventListeners.get(type).add(listener);
    }

    /**
     * Unsubscribes a listener function from a specific update/event type.
     * @param {string} type - The type of update/event.
     * @param {function} listener - The listener function to remove.
     */
    off(type, listener) {
        if (this.eventListeners.has(type)) {
            this.eventListeners.get(type).delete(listener);
            if (this.eventListeners.get(type).size === 0) {
                this.eventListeners.delete(type);
            }
        }
    }

    _emit(type, data) {
        if (this.eventListeners.has(type)) {
            // Use a copy of the set to avoid issues if listeners modify the set during iteration
            [...this.eventListeners.get(type)].forEach(listener => {
                try {
                    listener(data);
                } catch (error) {
                    console.error(`Error in listener for type "${type}":`, error);
                }
            });
        }
    }

    // Deprecated specific send methods, replaced by sendRequest
    /*
    sendInput(kifStrings, sourceId = null, noteId = null) { ... }
    sendInitialStateRequest() { ... }
    sendDialogueResponse(dialogueId, responseData) { ... }
    sendCommand(commandName, parameters = {}) { ... }
    sendUiAction(actionType, actionData = {}) { ... }
    sendInteractionFeedback(feedbackType, feedbackData = {}) { ... }
    */

    close() {
        if (this.ws) {
            this.ws.close();
        }
    }
}

// Export a singleton instance for simplicity in this application structure
// The port should ideally be configurable, but hardcoding for now based on typical setup
const WS_PORT = 8081; // Default port from Cog.main
const WS_URL = `ws://localhost:${WS_PORT}`;

export const websocketClient = new WebSocketClient(WS_URL);
