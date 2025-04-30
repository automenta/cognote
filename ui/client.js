// ui/client.js

/**
 * WebSocket Client for connecting to the Cognote backend.
 * Provides methods for sending signals and subscribing to events.
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

        // Dedicated handler for the initial state request promise
        this._initialStateRequestPromise = null;
        this._initialStateRequestTimeoutId = null;


        this._connect();
    }

    _connect() {
        if (this.ws && (this.ws.readyState === WebSocket.CONNECTING || this.ws.readyState === WebSocket.OPEN)) {
            console.log("WebSocket already connecting or open.");
            return;
        }

        console.log(`Attempting to connect to WebSocket: ${this.url} (Attempt ${this.reconnectAttempts + 1}/${this.maxReconnectAttempts})`);
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
        this.sendInitialStateRequest(); // Request initial state upon connection
    }

    _onMessage(event) {
        try {
            const signal = JSON.parse(event.data);
            // console.debug("WS received:", signal);

            if (!signal || typeof signal !== 'object' || !signal.type) {
                console.warn("Received invalid signal format:", signal);
                return;
            }

            switch (signal.type) {
                case 'event':
                    if (signal.payload && signal.payload.eventType) {
                        this._emit(signal.payload.eventType, signal.payload);
                        // Also emit a generic 'event' for listeners interested in all events
                        this._emit('event', signal.payload);
                    } else {
                        console.warn("Received event signal without payload or eventType:", signal);
                    }
                    break;
                case 'response':
                    this._handleResponse(signal);
                    break;
                case 'initial_state':
                    // Handle initial_state specifically to resolve the pending promise
                    if (this._initialStateRequestPromise) {
                         clearTimeout(this._initialStateRequestTimeoutId);
                         this._initialStateRequestPromise.resolve(signal.payload);
                         this._initialStateRequestPromise = null;
                         this._initialStateRequestTimeoutId = null;
                    }
                    // Still emit as a generic event for other listeners
                    this._emit('initialState', signal.payload);
                    break;
                case 'dialogue_request':
                    this._emit('dialogueRequest', signal.payload);
                    break;
                default:
                    console.warn(`Received unknown signal type: ${signal.type}`, signal);
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
            reject(new Error(`WebSocket closed before response received for signal ${signalId}`));
        });
        this.responseListeners.clear();

        // Reject pending initial state request
        if (this._initialStateRequestPromise) {
            clearTimeout(this._initialStateRequestTimeoutId);
            this._initialStateRequestPromise.reject(new Error(`WebSocket closed before initial state received`));
            this._initialStateRequestPromise = null;
            this._initialStateRequestTimeoutId = null;
        }


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
     * Sends a signal to the backend.
     * @param {string} type - The signal type (e.g., 'input', 'command').
     * @param {object} payload - The payload for the signal.
     * @param {string} [inReplyToId] - Optional ID of the signal this is a response to.
     * @returns {Promise<object|void>} A promise that resolves with the response payload if type is a request expecting a response, otherwise resolves immediately.
     */
    sendSignal(type, payload = {}, inReplyToId = null) {
        const signal = {
            id: this._generateSignalId(),
            type: type,
            payload: payload,
        };
        if (inReplyToId) {
            signal.inReplyToId = inReplyToId;
        }

        // Based on Protocol.java, 'response' signals are sent in reply to 'input', 'command', 'initial_state_request', 'dialogue_response'.
        // 'initial_state' is a special case handled directly by _onMessage.
        const expectsResponse = ['input', 'command', 'dialogue_response'].includes(type);

        if (expectsResponse) {
            return new Promise((resolve, reject) => {
                const timeoutId = setTimeout(() => {
                    this.responseListeners.delete(signal.id);
                    reject(new Error(`Response timeout for signal ${signal.id} (${type})`));
                }, this.responseTimeout);

                this.responseListeners.set(signal.id, {resolve, reject, timeoutId});
                this._send(signal);
            });
        } else if (type === 'initial_state_request') {
             // Handle initial_state_request specially
             return new Promise((resolve, reject) => {
                 // Clear any previous pending initial state request
                 if (this._initialStateRequestPromise) {
                     clearTimeout(this._initialStateRequestTimeoutId);
                     this._initialStateRequestPromise.reject(new Error("New initial state request sent before previous one completed."));
                 }

                 this._initialStateRequestPromise = { resolve, reject };
                 this._initialStateRequestTimeoutId = setTimeout(() => {
                     this._initialStateRequestPromise = null;
                     this._initialStateRequestTimeoutId = null;
                     reject(new Error(`Response timeout for signal ${signal.id} (${type})`));
                 }, this.responseTimeout);

                 this._send(signal);
             });
        }
        else {
            this._send(signal);
            return Promise.resolve(); // No specific response expected
        }
    }

    _handleResponse(responseSignal) {
        const signalId = responseSignal.inReplyToId;
        if (signalId && this.responseListeners.has(signalId)) {
            const {resolve, reject, timeoutId} = this.responseListeners.get(signalId);
            clearTimeout(timeoutId);
            this.responseListeners.delete(signalId);

            if (responseSignal.payload && responseSignal.payload.status === 'success') {
                resolve(responseSignal.payload.result ?? responseSignal.payload.message); // Resolve with result or message
            } else {
                // Reject with an error containing status and message
                const errorMsg = `Signal ${signalId} failed: Status=${responseSignal.payload?.status}, Message=${responseSignal.payload?.message}`;
                reject(new Error(errorMsg));
            }
        } else {
            console.warn(`Received response for unknown or expired signal ID: ${signalId}`, responseSignal);
            // Still emit as a generic response event
            this._emit('response', responseSignal);
        }
    }

    /**
     * Subscribes a listener function to a specific event type.
     * @param {string} eventType - The type of event (e.g., 'AssertedEvent', 'TaskUpdateEvent', 'connected', 'disconnected', 'error', 'initialState', 'dialogueRequest', 'response').
     * @param {function} listener - The function to call when the event occurs.
     */
    on(eventType, listener) {
        if (!this.eventListeners.has(eventType)) {
            this.eventListeners.set(eventType, new Set());
        }
        this.eventListeners.get(eventType).add(listener);
    }

    /**
     * Unsubscribes a listener function from a specific event type.
     * @param {string} eventType - The type of event.
     * @param {function} listener - The listener function to remove.
     */
    off(eventType, listener) {
        if (this.eventListeners.has(eventType)) {
            this.eventListeners.get(eventType).delete(listener);
            if (this.eventListeners.get(eventType).size === 0) {
                this.eventListeners.delete(eventType);
            }
        }
    }

    _emit(eventType, data) {
        if (this.eventListeners.has(eventType)) {
            // Use a copy of the set to avoid issues if listeners modify the set during iteration
            [...this.eventListeners.get(eventType)].forEach(listener => {
                try {
                    listener(data);
                } catch (error) {
                    console.error(`Error in listener for event type "${eventType}":`, error);
                }
            });
        }
    }

    /**
     * Sends an 'input' signal to assert KIF expressions.
     * @param {string[]} kifStrings - An array of KIF strings to assert.
     * @param {string} [sourceId] - Optional source identifier.
     * @param {string} [noteId] - Optional note ID context.
     * @returns {Promise<object|void>} A promise that resolves with the response payload.
     */
    sendInput(kifStrings, sourceId = null, noteId = null) {
        const payload = {
            kifStrings: Array.isArray(kifStrings) ? kifStrings : [kifStrings],
        };
        if (sourceId) payload.sourceId = sourceId;
        if (noteId) payload.noteId = noteId;
        return this.sendSignal('input', payload);
    }

    /**
     * Sends an 'initial_state_request' signal.
     * @returns {Promise<object|void>} A promise that resolves with the initial state payload.
     */
    sendInitialStateRequest() {
        // This is handled specially in sendSignal to await the 'initial_state' signal type
        return this.sendSignal('initial_state_request', {});
    }

    /**
     * Sends a 'dialogue_response' signal.
     * @param {string} dialogueId - The ID of the dialogue request being responded to.
     * @param {object} responseData - The response data payload.
     * @returns {Promise<object|void>} A promise that resolves with the response payload.
     */
    sendDialogueResponse(dialogueId, responseData) {
        const payload = {
            dialogueId: dialogueId,
            responseData: responseData,
        };
        return this.sendSignal('dialogue_response', payload, dialogueId); // Use dialogueId as inReplyToId
    }

    /**
     * Sends a generic 'command' signal.
     * @param {string} commandName - The name of the command.
     * @param {object} [parameters] - Optional command parameters.
     * @returns {Promise<object|void>} A promise that resolves with the response payload.
     */
    sendCommand(commandName, parameters = {}) {
        const payload = {
            command: commandName,
            parameters: parameters,
        };
        return this.sendSignal('command', payload);
    }

    /**
     * Sends a 'ui_action' signal.
     * @param {string} actionType - The type of UI action.
     * @param {object} [actionData] - Optional action data.
     * @returns {Promise<void>}
     */
    sendUiAction(actionType, actionData = {}) {
        const payload = {
            actionType: actionType,
            actionData: actionData,
        };
        return this.sendSignal('ui_action', payload);
    }

    /**
     * Sends an 'interaction_feedback' signal.
     * @param {string} feedbackType - The type of feedback.
     * @param {object} [feedbackData] - Optional feedback data.
     * @returns {Promise<void>}
     */
    sendInteractionFeedback(feedbackType, feedbackData = {}) {
        const payload = {
            feedbackType: feedbackType,
            feedbackData: feedbackData,
        };
        return this.sendSignal('interaction_feedback', payload);
    }

    close() {
        if (this.ws) {
            this.ws.close();
        }
    }
}

// Export a singleton instance for simplicity in this application structure
// The port should ideally be configurable, but hardcoding for now based on typical setup
const WS_PORT = 8080; // Default port from Cog.main
const WS_URL = `ws://localhost:${WS_PORT}`;

export const websocketClient = new WebSocketClient(WS_URL);
