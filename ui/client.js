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
                case 'response':
                    this._handleResponse(signal.id, inReplyToId, payload);
                    break;
                case 'event':
                    if (payload.eventType) {
                        this._emit(payload.eventType, payload);
                        this._emit('event', payload);
                    } else {
                        console.warn("Received event update without eventType:", signal);
                    }
                    break;
                case 'initialState':
                    this._emit('initialState', payload);
                    break;
                case 'dialogueRequest':
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
            type: 'request',
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

            payload?.status === 'success' ? resolve(payload.result ?? payload) :
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

const WS_PORT = 8081;
const WS_URL = `ws://localhost:${WS_PORT}`;

export const websocketClient = new WebSocketClient(WS_URL);
