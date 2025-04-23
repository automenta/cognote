type LogLevel = 'info' | 'warning' | 'error' | 'debug';
type Listener = () => void;

class SystemLog {
    private logBuffer: any[] = []; // Store log entries as objects
    private listeners: Listener[] = [];
    private maxHistory = 1000;
    private logLevel: LogLevel = 'info'; // Default log level

    constructor() {
        // Load log level from local storage, if available
        const storedLogLevel = localStorage.getItem('logLevel') as LogLevel | null;
        if (storedLogLevel && ['info', 'warning', 'error', 'debug'].includes(storedLogLevel)) {
            this.logLevel = storedLogLevel;
        }
    }

    setLogLevel = (level: LogLevel) => {
        this.logLevel = level;
        localStorage.setItem('logLevel', level); // Persist log level to local storage
        this.notifyListeners();
    };

    getLogLevel = (): LogLevel => this.logLevel;

    addListener = (listener: Listener) => this.listeners.push(listener);
    removeListener = (listener: Listener) => this.listeners.splice(this.listeners.indexOf(listener), 1);
    notifyListeners = () => this.listeners.forEach(listener => listener());

    log = (level: LogLevel, message: string, source?: string) => {
        if (level === 'debug' && this.logLevel !== 'debug') {
            return; // Skip debug messages if log level is not set to debug
        }

        const timestamp = new Date().toISOString();
        const logEntry = {
            timestamp,
            level,
            source,
            message
        };

        let formattedMessage = `[${timestamp}] - ${level.toUpperCase()} - `;
        if (source) formattedMessage += `[${source}] `;
        formattedMessage += message;

        let coloredMessage = formattedMessage;
        switch (level) {
            case 'error':
                coloredMessage = `[31m${formattedMessage}[0m`;
                break;
            case 'warning':
                coloredMessage = `[33m${formattedMessage}[0m`;
                break;
            case 'debug':
                coloredMessage = `[34m${formattedMessage}[0m`;
                break;
            case 'info':
                coloredMessage = `[37m${formattedMessage}[0m`;
                break;
        }
        console.log(coloredMessage);

        this.logBuffer.push(logEntry); // Store the log entry object
        if (this.logBuffer.length > this.maxHistory) this.logBuffer.shift();
        this.notifyListeners();
    };

    info = (message: string, source?: string) => this.log('info', message, source);
    warn = (message: string, source?: string) => this.log('warning', message, source);
    error = (message: string, source?: string) => this.log('error', message, source);
    debug = (message: string, source?: string) => this.log('debug', message, source);
    getLogHistory = () => [...this.logBuffer]; // Return the array of log entry objects
    clearLogHistory = () => {
        this.logBuffer = [];
        this.notifyListeners();
    };
}

export const systemLog = new SystemLog();
