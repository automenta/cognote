import { websocketClient } from '../client.js'; // Import the websocketClient

// Utility functions (assuming these are defined elsewhere or inline)
const Utils = {
    sanitizeHTML: (s) => {
        const div = document.createElement('div');
        div.textContent = s;
        return div.innerHTML;
    },
    extractText: (html) => {
        const div = document.createElement('div');
        div.innerHTML = html;
        return div.textContent || div.innerText || '';
    },
    debounce: (func, delay) => {
        let timeoutId;
        return (...args) => {
            clearTimeout(timeoutId);
            timeoutId = setTimeout(() => {
                func.apply(this, args);
            }, delay);
        };
    }
};

// Notifier (assuming this is defined elsewhere or inline)
const Notifier = {
    info: (msg) => console.log(`[INFO] ${msg}`),
    error: (msg) => console.error(`[ERROR] ${msg}`),
    warning: (msg) => console.warn(`[WARN] ${msg}`)
};

// Base Component class
class Component {
    constructor(app, elSelector) {
        this.app = app;
        this.el = document.querySelector(elSelector);
        this.el || console.error(`Component failed to find element: ${elSelector}`);
    }

    render() {}
    bindEvents() {}

    show() {
        this.el?.classList.add('visible');
    }

    hide() {
        this.el?.classList.remove('visible');
    }
}

// NoteItem component for the list
class NoteItem {
    constructor(app, note) {
        this.app = app;
        this.note = note;
        this.el = this.createMarkup();
        this.bindEvents();
    }

    createMarkup() {
        const preview = Utils.extractText(this.note.content).substring(0, 80) + (Utils.extractText(this.note.content).length > 80 ? '...' : '');
        const el = document.createElement('div');
        el.classList.add('note-item');
        el.dataset.noteId = this.note.id;
        el.style.borderColor = this.note.color || ''; // Apply color
        el.innerHTML = `
            <div class="note-title">${Utils.sanitizeHTML(this.note.title)}</div>
            <div class="note-preview">${Utils.sanitizeHTML(preview)}</div>
            <div class="note-meta">
                <span class="note-status status-${this.note.state.status.toLowerCase()}">${this.note.state.status}</span>
                <span class="note-priority">Pri: ${this.note.pri}</span>
                <button class="priority-inc">+</button>
                <button class="priority-dec">-</button>
            </div>
        `;
        return el;
    }

    bindEvents() {
        this.el.addEventListener('click', () => this.app.selectNote(this.note.id));
        this.el.querySelector('.priority-inc').addEventListener('click', (e) => {
            e.stopPropagation();
            this.app.updateNotePriority(this.note.id, 1);
        });
        this.el.querySelector('.priority-dec').addEventListener('click', (e) => {
            e.stopPropagation();
            this.app.updateNotePriority(this.note.id, -1);
        });
    }

    update(note) {
        this.note = note;
        const preview = Utils.extractText(this.note.content).substring(0, 80) + (Utils.extractText(this.note.content).length > 80 ? '...' : '');
        this.el.querySelector('.note-title').innerHTML = Utils.sanitizeHTML(this.note.title);
        this.el.querySelector('.note-preview').innerHTML = Utils.sanitizeHTML(preview);
        this.el.querySelector('.note-status').textContent = this.note.state.status;
        this.el.querySelector('.note-status').className = `note-status status-${this.note.state.status.toLowerCase()}`;
        this.el.querySelector('.note-priority').textContent = `Pri: ${this.note.pri}`;
        this.el.style.borderColor = this.note.color || ''; // Update color
    }

    setActive(isActive) {
        this.el.classList.toggle('selected', isActive);
    }
}

// NoteList component for the sidebar
class NoteList extends Component {
    constructor(app, elSelector) {
        super(app, elSelector);
        this.items = {};
    }

    render(notes, currentId) {
        this.el.innerHTML = '';
        this.items = {};
        notes?.length ?
            notes.forEach(note => {
                const item = new NoteItem(this.app, note);
                this.items[note.id] = item;
                this.el.appendChild(item.el);
                if (note.id === currentId) {
                    item.setActive(true);
                }
            }) :
            this.el.innerHTML = '<div class="no-notes">No notes found.</div>';

        if (currentId && this.items[currentId]) {
             this.scrollToActive(currentId);
        }
    }

    updateNote(note) {
        if (this.items[note.id]) {
            this.items[note.id].update(note);
        } else {
            // If note is new or wasn't in the filtered list, re-render the whole list
            this.app.sortAndFilter();
        }
    }

    removeNote(noteId) {
        if (this.items[noteId]) {
            this.items[noteId].el.remove();
            delete this.items[noteId];
        }
    }

    setActive(id) {
        Object.values(this.items).forEach(i => i.setActive(false));
        if (id && this.items[id]) {
            this.items[id].setActive(true);
            this.scrollToActive(id);
        }
    }

    scrollToActive(id) {
        const itemEl = this.items[id]?.el;
        const container = this.el;
        if (itemEl && container) {
            const itemTop = itemEl.offsetTop;
            const itemBottom = itemTop + itemEl.offsetHeight;
            // Scroll only if the item is not fully visible
            if (itemTop < container.scrollTop || itemBottom > container.scrollTop + container.clientHeight) {
                container.scrollTop = itemTop - container.clientHeight / 2 + itemEl.offsetHeight / 2;
            }
        }
    }
}

// Sidebar component
class Sidebar extends Component {
    constructor(app, elSelector) {
        super(app, elSelector);
        this.noteList = null;
        this.render();
        this.bindEvents();
        this.noteList = new NoteList(app, '#note-list');
    }

    render() {
        this.el.innerHTML = `
            <div class="sidebar-header">
                <h2>Notes</h2>
                <button id="add-note-btn">+</button>
            </div>
            <div class="sidebar-controls">
                <input type="text" id="search-notes" placeholder="Search...">
                <select id="sort-notes">
                    <option value="updated-desc">Last Updated</option>
                    <option value="priority-desc">Priority (High to Low)</option>
                    <option value="priority-asc">Priority (Low to High)</option>
                    <option value="title-asc">Title (A-Z)</option>
                    <option value="title-desc">Title (Z-A)</option>
                </select>
            </div>
            <div id="note-list" class="note-list">
                <!-- Note items will be rendered here by NoteList component -->
            </div>
        `;
    }

    bindEvents() {
        this.el.querySelector('#add-note-btn').addEventListener('click', () => this.app.createNewNote());
        this.el.querySelector('#sort-notes').addEventListener('change', () => this.app.sortAndFilter());
        this.el.querySelector('#search-notes').addEventListener('input', Utils.debounce(() => this.app.sortAndFilter(), 300));
    }

    renderNotes(notes, currentId) {
        this.noteList.render(notes, currentId);
    }

    updateNote(note) {
        this.noteList.updateNote(note);
    }

    removeNote(noteId) {
        this.noteList.removeNote(noteId);
    }

    setActive(id) {
        this.noteList.setActive(id);
    }

    getSortBy() {
        return this.el.querySelector('#sort-notes').value;
    }

    getSearchTerm() {
        return this.el.querySelector('#search-notes').value.toLowerCase();
    }
}

// Editor component
class Editor extends Component {
    constructor(app, elSelector) {
        super(app, elSelector);
        this.currentNoteId = null;
        this.save = Utils.debounce(() => this.app.saveCurrentNote(), 1000);
        this.render();
        this.contentEl = this.el.querySelector('.editor-content');
        this.titleEl = this.el.querySelector('.editor-title');
        this.bindEvents();
    }

    render() {
        this.el.innerHTML = `
            <div class="editor-toolbar">
                <button data-command="bold">B</button>
                <button data-command="italic">I</button>
                <button data-command="underline">U</button>
                <button data-command="insertUnorderedList">UL</button>
                <button data-command="insertOrderedList">OL</button>
                <button data-command="createLink">Link</button>
                <button data-action="insert-field">Insert Field</button>
                <button data-action="delete-note" class="delete-button">Delete Note</button>
                <button data-action="clone-note" class="clone-button">Clone Note</button>
            </div>
            <input type="text" class="editor-title" placeholder="Note Title">
            <div class="editor-content" contenteditable="true" placeholder="Write your note here..."></div>
        `;
    }

    bindEvents() {
        this.el.querySelectorAll('.editor-toolbar button[data-command]').forEach(button => {
            button.addEventListener('click', (e) => {
                document.execCommand(e.currentTarget.dataset.command, false, null);
                this.contentEl.dispatchEvent(new Event('input')); // Trigger input event after command
                this.contentEl.focus(); // Keep focus on content
            });
        });

        this.el.querySelector('.editor-toolbar button[data-action="insert-field"]').addEventListener('click', () => this.insertField());
        this.el.querySelector('.editor-toolbar button[data-action="delete-note"]').addEventListener('click', () => this.app.deleteNote(this.currentNoteId));
        this.el.querySelector('.editor-toolbar button[data-action="clone-note"]').addEventListener('click', () => this.app.cloneNote(this.currentNoteId));

        this.titleEl.addEventListener('input', () => this.save());
        this.contentEl.addEventListener('input', () => this.save());
    }

    loadNote(note) {
        this.currentNoteId = note.id;
        this.titleEl.value = note.title;
        this.contentEl.innerHTML = note.text; // Use innerHTML for rich text
        this.el.classList.add('visible');
    }

    clear() {
        this.currentNoteId = null;
        this.titleEl.value = '';
        this.contentEl.innerHTML = '';
        this.el.classList.remove('visible');
    }

    getNoteContent() {
        return {
            id: this.currentNoteId,
            title: this.titleEl.value,
            text: this.contentEl.innerHTML // Get rich text content
        };
    }

    insertField() {
        const fieldName = prompt("Enter field name:");
        if (fieldName) {
            const fieldMarkup = `{{${fieldName}}}`;
            document.execCommand('insertText', false, fieldMarkup);
            this.contentEl.dispatchEvent(new Event('input')); // Trigger save
        }
    }
}

// MenuBar component
class MenuBar extends Component {
    constructor(app, elSelector) {
        super(app, elSelector);
        this.render();
        this.bindEvents();
    }

    render() {
        this.el.innerHTML = `
            <div class="group">
                <span data-action="show-settings">Settings</span>
                <span data-action="clear-all">Clear All</span>
                <span data-action="save-state">Save State</span>
                <span data-action="load-state">Load State</span>
            </div>
            <div class="group">
                 <span id="system-status">Status: Unknown</span>
                 <span id="kb-status">KB: 0/0</span>
                 <span id="llm-status">LLM: 0</span>
                 <span id="rules-status">Rules: 0</span>
            </div>
        `;
    }

    bindEvents() {
        this.el.querySelectorAll('span[data-action]').forEach(span => {
            span.addEventListener('click', (e) => this.app.handleMenu(e.currentTarget.dataset.action));
        });
    }

    updateStatus(status) {
        this.el.querySelector('#system-status').textContent = `Status: ${status.status}`;
        this.el.querySelector('#kb-status').textContent = `KB: ${status.kbCount}/${status.kbTotalCapacity}`;
        this.el.querySelector('#llm-status').textContent = `LLM: ${status.activeLlmTasks}`;
        this.el.querySelector('#rules-status').textContent = `Rules: ${status.ruleCount}`;
    }
}

// ActionIcon component (for the floating action area)
class ActionIcon {
    constructor(app, parentEl, data) {
        this.app = app;
        this.parentEl = parentEl;
        this.data = data;
        this.el = this.createMarkup();
        this.bindEvents();
        this.parentEl.appendChild(this.el);
    }

    createMarkup() {
        const el = document.createElement('div');
        el.classList.add('action-icon');
        el.innerHTML = `
            <span class="icon">${this.data.icon}</span>
            <span class="label">${this.data.label}</span>
            <button class="hide-btn">x</button>
        `;
        return el;
    }

    bindEvents() {
        this.el.addEventListener('click', (e) => {
            // Check if the click was on the hide button
            if (e.target.classList.contains('hide-btn')) {
                this.hide();
            } else {
                this.app.showDock(this.data);
            }
        });
    }

    hide() {
        this.el.style.display = 'none'; // Or remove from DOM
    }
}

// ActionArea component (the floating area containing icons)
class ActionArea extends Component {
    constructor(app, elSelector) {
        super(app, elSelector);
        this.icons = [];
        this.dockEl = null; // Placeholder for the dock element
        this.render();
        this.bindEvents();
    }

    render() {
        this.el.innerHTML = `
            <div id="action-icons" class="action-icons">
                <!-- Icons will be added here -->
            </div>
            <div id="action-dock" class="action-dock">
                <div class="dock-header">
                    <span class="dock-title"></span>
                    <button class="close-dock">x</button>
                </div>
                <div class="dock-content">
                    <!-- Content loaded dynamically -->
                </div>
            </div>
        `;
        this.iconsEl = this.el.querySelector('#action-icons');
        this.dockEl = this.el.querySelector('#action-dock');
        this.dockTitleEl = this.dockEl.querySelector('.dock-title');
        this.dockContentEl = this.dockEl.querySelector('.dock-content');
        this.closeDockButton = this.dockEl.querySelector('.close-dock');
    }

    bindEvents() {
        this.closeDockButton.addEventListener('click', () => this.hideDock());
        // Close dock when clicking outside
        document.addEventListener('click', (e) => {
            // Check if the click is outside the action area and the dock is visible
            if (!this.el.contains(e.target) && this.dockEl.classList.contains('visible')) {
                 this.hideDock();
            }
        });
    }

    addIcon(data) {
        const icon = new ActionIcon(this.app, this.iconsEl, data);
        this.icons.push(icon);
    }

    showDock(data) {
        this.dockTitleEl.textContent = data.label;
        this.dockContentEl.innerHTML = data.content; // Load content
        this.dockEl.classList.add('visible');
    }

    hideDock() {
        this.dockEl.classList.remove('visible');
        this.dockContentEl.innerHTML = ''; // Clear content
    }
}

// Settings Modal component
class SettingsModal extends Component {
    constructor(app, elSelector) {
        super(app, elSelector);
        this.render();
        this.closeButton = this.el.querySelector('.close-button');
        this.cancelButton = this.el.querySelector('.cancel-button');
        this.saveButton = this.el.querySelector('.save-button');
        this.tabsEl = this.el.querySelector('.settings-tabs');
        this.tabContentEl = this.el.querySelector('.settings-tab-content');
        this.bindEvents();
    }

    render() {
        this.el.innerHTML = `
            <div class="modal-content">
                <div class="modal-header">
                    <h2>Settings</h2>
                    <span class="close-button">&times;</span>
                </div>
                <div class="modal-body">
                    <ul class="settings-tabs">
                        <li data-tab="general" class="active">General</li>
                        <li data-tab="llm">LLM</li>
                        <li data-tab="advanced">Advanced</li>
                    </ul>
                    <div class="settings-tab-content">
                        <div id="settings-tab-general" class="tab-pane active">
                            <h3>General Settings</h3>
                            <label for="setting-usePersistence">Use Persistence:</label>
                            <input type="checkbox" id="setting-usePersistence"><br>
                            <label for="setting-serpApiKey">SerpAPI Key:</label>
                            <input type="text" id="setting-serpApiKey"><br>
                        </div>
                        <div id="settings-tab-llm" class="tab-pane">
                            <h3>LLM Settings</h3>
                            <label for="setting-apiKey">API Key:</label>
                            <input type="text" id="setting-apiKey"><br>
                            <label for="setting-modelName">Model Name:</label>
                            <input type="text" id="setting-modelName"><br>
                            <label for="setting-temperature">Temperature:</label>
                            <input type="number" id="setting-temperature" step="0.1" min="0" max="2"><br>
                            <label for="setting-concurrency">Concurrency:</label>
                            <input type="number" id="setting-concurrency" min="1"><br>
                        </div>
                         <div id="settings-tab-advanced" class="tab-pane">
                            <h3>Advanced Settings</h3>
                            <label for="setting-llmApiUrl">LLM API URL:</label>
                            <input type="text" id="setting-llmApiUrl"><br>
                            <label for="setting-globalKbCapacity">Global KB Capacity:</label>
                            <input type="number" id="setting-globalKbCapacity" min="1024"><br>
                            <label for="setting-reasoningDepthLimit">Reasoning Depth Limit:</label>
                            <input type="number" id="setting-reasoningDepthLimit" min="1"><br>
                            <label for="setting-broadcastInputAssertions">Broadcast Input Assertions:</label>
                            <input type="checkbox" id="setting-broadcastInputAssertions"><br>
                        </div>
                    </div>
                </div>
                <div class="modal-footer">
                    <button class="cancel-button">Cancel</button>
                    <button class="save-button">Save</button>
                </div>
            </div>
        `;
    }

    bindEvents() {
        this.closeButton.addEventListener('click', () => this.hide());
        this.cancelButton.addEventListener('click', () => this.hide());
        this.saveButton.addEventListener('click', () => this.save());
        this.tabsEl.querySelectorAll('li').forEach(tab => {
            tab.addEventListener('click', (e) => this.switchTab(e.currentTarget));
        });
        // Close modal when clicking outside the content
        this.el.addEventListener('click', (e) => {
            if (e.target === this.el) {
                this.hide();
            }
        });
    }

    loadSettings(settings) {
        // Assuming settings object matches input IDs
        for (const key in settings) {
            const input = this.el.querySelector(`#setting-${key}`);
            if (input) {
                if (input.type === 'checkbox') {
                    input.checked = settings[key];
                } else {
                    input.value = settings[key];
                }
            }
        }
    }

    getSettings() {
        const settings = {};
        this.el.querySelectorAll('.tab-pane input').forEach(input => {
            const key = input.id.replace('setting-', '');
            if (input.type === 'checkbox') {
                settings[key] = input.checked;
            } else if (input.type === 'number') {
                 settings[key] = parseFloat(input.value);
                 if (isNaN(settings[key])) settings[key] = 0; // Handle potential NaN
            }
            else {
                settings[key] = input.value;
            }
        });
        return settings;
    }

    save() {
        const settings = this.getSettings();
        // Basic validation (can be expanded)
        if (settings.apiKey === '') {
             Notifier.warning("API Key is empty. LLM features may not work.");
        }
         if (settings.serpApiKey === '') {
             Notifier.warning("SerpAPI Key is empty. Search features may not work.");
        }

        this.app.saveSettings(settings);
        this.hide();
    }

    switchTab(clickedTab) {
        this.tabsEl.querySelectorAll('li').forEach(tab => tab.classList.remove('active'));
        clickedTab.classList.add('active');

        this.tabContentEl.querySelectorAll('.tab-pane').forEach(pane => pane.classList.remove('active'));
        const targetTabId = clickedTab.dataset.tab;
        this.tabContentEl.querySelector(`#settings-tab-${targetTabId}`).classList.add('active');
    }
}

// Dialogue Modal component
class DialogueManager extends Component {
    constructor(app, elSelector) {
        super(app, elSelector);
        this.modalEl = this.el; // The modal overlay element
        this.currentDialogueId = null;
        this.resolvePromise = null;
        this.rejectPromise = null;
        this.render();
        this.promptEl = this.el.querySelector('#dialogue-prompt');
        this.inputEl = this.el.querySelector('#dialogue-input');
        this.sendButton = this.el.querySelector('#dialogue-send-button');
        this.cancelButton = this.el.querySelector('#dialogue-cancel-button');
        this.closeButton = this.el.querySelector('#dialogue-modal-close');
        this.bindEvents();
    }

    render() {
        this.el.innerHTML = `
            <div class="dialogue-modal-content">
                <div class="dialogue-modal-header">
                    <h3 id="dialogue-title">Dialogue Required</h3>
                    <span id="dialogue-modal-close" class="close-button">&times;</span>
                </div>
                <div class="dialogue-modal-body">
                    <p id="dialogue-prompt"></p>
                    <input type="text" id="dialogue-input" placeholder="Enter your response...">
                </div>
                <div class="dialogue-modal-footer">
                    <button id="dialogue-cancel-button" class="cancel-button">Cancel</button>
                    <button id="dialogue-send-button" class="send-button">Send</button>
                </div>
            </div>
        `;
    }

    bindEvents() {
        this.sendButton?.addEventListener('click', () => this.sendResponse());
        this.cancelButton?.addEventListener('click', () => this.cancelDialogue());
        this.closeButton?.addEventListener('click', () => this.cancelDialogue());
        this.inputEl?.addEventListener('keypress', (e) => {
            // Check if Enter key was pressed and it's not a shift+enter for newline
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault(); // Prevent default form submission or newline
                this.sendResponse();
            }
        });
        // Close modal when clicking outside the content
        this.modalEl?.addEventListener('click', (e) => {
            if (e.target === this.modalEl) {
                this.cancelDialogue();
            }
        });
    }

    showDialogue(dialogueId, prompt, title = 'Dialogue Required') {
        this.currentDialogueId = dialogueId;
        this.el.querySelector('#dialogue-title').textContent = title;
        this.promptEl.textContent = prompt;
        this.inputEl.value = ''; // Clear previous input
        this.show();
        this.inputEl.focus(); // Focus the input field
    }

    sendResponse() {
        if (!this.currentDialogueId) return;

        const response = this.inputEl.value.trim();
        if (response) {
            websocketClient.sendRequest('dialogueResponse', {
                dialogueId: this.currentDialogueId,
                responseData: { text: response } // Wrap response in an object
            })
            .then(() => {
                Notifier.info(`Response sent for dialogue ${this.currentDialogueId}`);
                this.hide();
            })
            .catch(err => {
                console.error('Failed to send dialogueResponse command:', err);
                Notifier.error('Failed to send response.');
                // Decide whether to hide or keep modal open on error
                this.hide(); // Hide on error for now
            });
        } else {
            Notifier.warning("Please enter a response.");
        }
    }

    cancelDialogue() {
        if (!this.currentDialogueId) return;

        websocketClient.sendRequest('cancelDialogue', { dialogueId: this.currentDialogueId })
            .catch(err => (console.error('Failed to send cancelDialogue command:', err), Notifier.error('Failed to cancel dialogue.')));

        this.hide();
    }

    hide() {
        super.hide();
        this.currentDialogueId = null;
        this.promptEl.textContent = '';
        this.inputEl.value = '';
    }
}


// Main Application class
class App {
    constructor(elSelector) {
        this.el = document.querySelector(elSelector);
        this.notes = [];
        this.currentId = null;
        this.settings = {}; // Client-side settings (like UI preferences, API keys)
        this.systemConfig = {}; // Backend system configuration
        this.initComps();
        this.bindWebSocketEvents();
        console.log("Netention Note App Ready");
    }

    initComps() {
        this.sidebar = new Sidebar(this, '#sidebar');
        this.editor = new Editor(this, '#editor');
        this.menuBar = new MenuBar(this, '#menu-bar');
        this.actionArea = new ActionArea(this, '#action-area');
        this.settingsModal = new SettingsModal(this, '#settings-modal');
        this.dialogueManager = new DialogueManager(this, '#dialogue-modal');

        // Example action icons (can be dynamic based on backend capabilities)
        this.actionArea.addIcon({ label: 'REPL', icon: '⌨️', content: '<div id="repl-container"></div>' }); // Placeholder for REPL
        // this.actionArea.addIcon({ label: 'Graph', icon: '🕸️', content: 'Graph visualization coming soon...' });
    }

    bindWebSocketEvents() {
        websocketClient.on('connected', () => {
            Notifier.info('Connected to backend.');
            this.requestInitialState().catch(err => (console.error('Failed to request initial state:', err), Notifier.error('Failed to load initial state.')));
        });

        websocketClient.on('disconnected', (e) => {
            console.warn('WS Disconnected', e);
            Notifier.error('Disconnected from backend.');
            this.notes = []; // Clear notes on disconnect
            this.currentId = null;
            this.sidebar.renderNotes([], null);
            this.editor.clear();
            this.menuBar.updateStatus({ status: 'Disconnected', kbCount: 0, kbTotalCapacity: 0, activeLlmTasks: 0, ruleCount: 0 });
        });

        websocketClient.on('error', (e) => {
            console.error('WS Error', e);
            Notifier.error('WebSocket error.');
        });

        websocketClient.on('initialState', (payload) => {
            console.log('Received initial state:', payload);
            this.notes = payload.notes || [];
            this.systemConfig = payload.configuration || {};
            // Load client-side settings from local storage or defaults
            this.loadClientSettings();
            this.sortAndFilter();
            this.menuBar.updateStatus(payload.systemStatus);
            // Note: Assertions and Rules from initial state are not currently used in the UI
        });

        websocketClient.on('event', (payload) => {
            // console.log('Received event:', payload);
            switch (payload.eventType) {
                case 'NoteAddedEvent':
                    this.handleNoteAdded(payload.note);
                    break;
                case 'NoteUpdatedEvent':
                    this.handleNoteUpdated(payload.note);
                    break;
                case 'NoteDeletedEvent':
                    this.handleNoteDeleted(payload.noteId);
                    break;
                case 'SystemStatusEvent':
                    this.menuBar.updateStatus(payload);
                    break;
                case 'LogMessageEvent':
                    // Handle log messages if needed, e.g., show in a console/log area
                    console.log(`[BACKEND] [${payload.level.toUpperCase()}] ${payload.message}`);
                    break;
                // Add handlers for other event types (AssertionAddedEvent, RuleAddedEvent, etc.)
            }
        });

        websocketClient.on('dialogueRequest', (payload) => {
             console.log('Received dialogue request:', payload);
             this.dialogueManager.showDialogue(payload.dialogueId, payload.prompt, payload.title);
        });
    }

    requestInitialState() {
        return websocketClient.sendRequest('getInitialState');
    }

    handleNoteAdded(note) {
        // Add note if it doesn't exist, or update if it does (shouldn't happen for 'Added')
        if (!this.notes.find(n => n.id === note.id)) {
            this.notes.push(note);
            this.sortAndFilter(); // Re-sort and filter the list
            Notifier.info(`Note added: ${note.title}`);
        }
    }

    handleNoteUpdated(note) {
        const index = this.notes.findIndex(n => n.id === note.id);
        if (index !== -1) {
            this.notes[index] = note;
            this.sidebar.updateNote(note); // Update the specific item in the list
            if (this.currentId === note.id) {
                // If the updated note is the one currently in the editor, update the editor
                // Note: This might overwrite user's unsaved changes if not careful.
                // A better approach might be to only update if the editor is not dirty,
                // or prompt the user. For now, we'll just update the sidebar item.
                // If the update came from the editor itself (via saveCurrentNote),
                // this event handler might be redundant or need logic to prevent loops.
                // Let's assume for now the editor handles its own state and this updates the list view.
            }
             // If status or priority changed, re-sort might be needed
            this.sortAndFilter();
            // Notifier.info(`Note updated: ${note.title}`); // Can be noisy
        } else {
             // This might happen if a note outside the current filter/sort is updated
             // We could fetch the note or just ignore if it's not in the current view
             // For now, we'll just log a warning.
             console.warn(`Received update for unknown or filtered note ID: ${note.id}`);
        }
    }

    handleNoteDeleted(noteId) {
        const index = this.notes.findIndex(n => n.id === noteId);
        if (index !== -1) {
            const deletedNote = this.notes.splice(index, 1)[0];
            this.sidebar.removeNote(noteId);
            if (this.currentId === noteId) {
                this.editor.clear();
                this.currentId = null;
            }
            Notifier.info(`Note deleted: ${deletedNote.title}`);
        }
    }

    createNewNote() {
        const newNote = {
            id: `client-${Date.now()}`, // Temporary client-side ID
            title: 'New Note',
            text: '',
            state: { status: 'IDLE' },
            pri: 0,
            color: null,
            created: Date.now(),
            updated: Date.now()
        };
        // Add to client list immediately for responsiveness
        this.notes.unshift(newNote); // Add to the beginning
        this.sortAndFilter();
        this.selectNote(newNote.id);

        // Send request to backend to create the note
        websocketClient.sendRequest('addNote', {
            title: newNote.title,
            content: newNote.text,
            state: newNote.state.status,
            priority: newNote.pri,
            color: newNote.color
        })
        .then(response => {
            // Backend will send a NoteAddedEvent with the final backend ID
            // The event handler will update the note in the list
            console.log('Backend addNote response:', response);
            // We might need to map the temporary client ID to the backend ID here
            // if the backend doesn't send the temporary ID back in the event.
            // A better approach might be to wait for the backend event before adding to the list.
            // For now, we rely on the event handler to update the list correctly.
        })
        .catch(err => {
            console.error('Failed to add note to backend:', err);
            Notifier.error('Failed to create note on backend.');
            // Remove the temporary note if backend creation failed
            this.handleNoteDeleted(newNote.id);
            if (this.currentId === newNote.id) {
                 this.editor.clear();
                 this.currentId = null;
            }
        });
    }

    selectNote(id) {
        if (this.currentId === id) return; // Already selected

        // Save current note before switching
        if (this.currentId) {
            this.saveCurrentNote();
        }

        this.currentId = id;
        const note = this.notes.find(n => n.id === id);
        if (note) {
            this.editor.loadNote(note);
            this.sidebar.setActive(id);
        } else {
            console.error("Attempted to select unknown note ID:", id);
            this.editor.clear();
            this.sidebar.setActive(null);
            this.currentId = null;
        }
    }

    saveCurrentNote() {
        if (!this.currentId) return;

        const editorContent = this.editor.getNoteContent();
        const note = this.notes.find(n => n.id === this.currentId);

        if (note) {
            // Check if content or title actually changed
            if (note.title !== editorContent.title || note.text !== editorContent.text) {
                 // Update client-side note immediately for responsiveness
                note.title = editorContent.title;
                note.text = editorContent.text;
                note.updated = Date.now(); // Update timestamp client-side
                this.sidebar.updateNote(note); // Update sidebar item

                // Send update to backend
                websocketClient.sendRequest('updateNote', {
                    noteId: note.id,
                    title: note.title,
                    content: note.text
                })
                .then(() => {
                    // Backend will send a NoteUpdatedEvent which will re-update the note in the list
                    // This ensures consistency with the backend's state, including the updated timestamp.
                    // Notifier.info(`Note saved: ${note.title}`); // Can be noisy
                })
                .catch(err => {
                    console.error('Failed to save note to backend:', err);
                    Notifier.error('Failed to save note.');
                    // TODO: Handle save failure - maybe mark note as unsaved?
                });
            }
        } else {
            console.error("Attempted to save unknown note ID:", this.currentId);
        }
    }

    deleteNote(noteId) {
        if (!noteId) return;

        if (confirm(`Are you sure you want to delete this note?`)) {
            // Clear editor immediately if it's the current note
            if (this.currentId === noteId) {
                this.editor.clear();
                this.currentId = null;
            }
            // Remove from client list immediately for responsiveness
            this.handleNoteDeleted(noteId); // This also updates the sidebar

            // Send delete request to backend
            websocketClient.sendRequest('deleteNote', { noteId: noteId })
                .then(() => {
                    // Backend will send a NoteDeletedEvent which confirms the deletion
                    console.log(`Backend deleteNote response for ${noteId}: success`);
                })
                .catch(err => {
                    console.error(`Failed to delete note ${noteId} on backend:`, err);
                    Notifier.error('Failed to delete note.');
                    // TODO: Handle delete failure - maybe re-add the note to the list?
                });
        }
    }

    cloneNote(noteId) {
         if (!noteId) return;

         websocketClient.sendRequest('cloneNote', { noteId: noteId })
            .then(response => {
                // Backend will send a NoteAddedEvent for the new cloned note
                console.log(`Backend cloneNote response for ${noteId}:`, response);
                Notifier.info('Note cloning requested.');
            })
            .catch(err => {
                console.error(`Failed to clone note ${noteId} on backend:`, err);
                Notifier.error('Failed to clone note.');
            });
    }

    updateNotePriority(noteId, delta) {
        const note = this.notes.find(n => n.id === noteId);
        if (note) {
            const newPriority = note.pri + delta;
            // Update client-side immediately
            note.pri = newPriority;
            note.updated = Date.now(); // Update timestamp
            this.sidebar.updateNote(note); // Update sidebar item
            this.sortAndFilter(); // Re-sort the list

            // Send update to backend
            websocketClient.sendRequest('updateNote', {
                noteId: note.id,
                priority: newPriority
            })
            .then(() => {
                 // Backend will send a NoteUpdatedEvent
                 console.log(`Backend updateNote priority response for ${noteId}: success`);
            })
            .catch(err => {
                console.error(`Failed to update priority for note ${noteId} on backend:`, err);
                Notifier.error('Failed to update note priority.');
                // TODO: Handle failure - revert client-side change?
            });
        }
    }

    sortAndFilter() {
        const sortBy = this.sidebar.getSortBy();
        const searchTerm = this.sidebar.getSearchTerm();

        let filteredNotes = this.notes;

        // Apply filter
        if (searchTerm) {
            filteredNotes = this.notes.filter(note =>
                note.title.toLowerCase().includes(searchTerm) ||
                Utils.extractText(note.text).toLowerCase().includes(searchTerm)
            );
        }

        // Apply sort
        filteredNotes.sort((a, b) => {
            switch (sortBy) {
                case 'updated-desc': return b.updated - a.updated;
                case 'priority-desc': return b.pri - a.pri;
                case 'priority-asc': return a.pri - b.pri;
                case 'title-asc': return a.title.localeCompare(b.title);
                case 'title-desc': return b.title.localeCompare(a.title);
                default: return 0;
            }
        });

        this.sidebar.renderNotes(filteredNotes, this.currentId);
    }

    handleMenu(action) {
        console.log("Menu action:", action);
        switch (action) {
            case 'show-settings':
                this.showSettings();
                break;
            case 'clear-all':
                this.clearAll();
                break;
            case 'save-state':
                this.saveState();
                break;
            case 'load-state':
                this.loadState();
                break;
            default:
                console.warn("Unknown menu action:", action);
        }
    }

    showSettings() {
        // Load current system config into the modal
        this.settingsModal.loadSettings({
            llmApiUrl: this.systemConfig.llmApiUrl,
            llmModel: this.systemConfig.llmModel,
            globalKbCapacity: this.systemConfig.globalKbCapacity,
            reasoningDepthLimit: this.systemConfig.reasoningDepthLimit,
            broadcastInputAssertions: this.systemConfig.broadcastInputAssertions,
            // Load client-side settings (if any)
            usePersistence: this.settings.usePersistence ?? true, // Default client setting
            serpApiKey: this.settings.serpApiKey ?? '' // Default client setting
        });
        this.settingsModal.show();
    }

    saveSettings(settings) {
        // Separate backend config from client settings
        const backendConfig = {
            llmApiUrl: settings.llmApiUrl,
            llmModel: settings.llmModel,
            globalKbCapacity: settings.globalKbCapacity,
            reasoningDepthLimit: settings.reasoningDepthLimit,
            broadcastInputAssertions: settings.broadcastInputAssertions
        };

        // Save client-side settings locally
        this.settings = {
            usePersistence: settings.usePersistence,
            serpApiKey: settings.serpApiKey
        };
        localStorage.setItem('clientSettings', JSON.stringify(this.settings));
        Notifier.info('Client settings saved.');

        // Send backend config to backend
        websocketClient.sendRequest('updateSettings', { settings: backendConfig })
            .then(() => {
                Notifier.info('Backend settings updated.');
                // Backend will send SystemStatusEvent with updated config
            })
            .catch(err => {
                console.error('Failed to update backend settings:', err);
                Notifier.error('Failed to update backend settings.');
            });
    }

    loadClientSettings() {
        try {
            const savedSettings = localStorage.getItem('clientSettings');
            if (savedSettings) {
                this.settings = JSON.parse(savedSettings);
                console.log('Loaded client settings:', this.settings);
            } else {
                this.settings = { usePersistence: true, serpApiKey: '' }; // Default settings
                console.log('No client settings found, using defaults:', this.settings);
            }
        } catch (e) {
            console.error('Failed to load client settings from local storage:', e);
            this.settings = { usePersistence: true, serpApiKey: '' }; // Fallback to defaults
        }
    }


    clearAll() {
        if (confirm("Are you sure you want to clear ALL notes and knowledge? This cannot be undone.")) {
            websocketClient.sendRequest('clearAll')
                .then(() => {
                    Notifier.info('System clear initiated.');
                    // Backend will send events for deleted notes and updated status
                })
                .catch(err => {
                    console.error('Failed to send clearAll command:', err);
                    Notifier.error('Failed to clear system.');
                });
        }
    }

    saveState() {
         websocketClient.sendRequest('saveState') // Assuming a backend command for this
            .then(() => {
                Notifier.info('System state save requested.');
            })
            .catch(err => {
                console.error('Failed to send saveState command:', err);
                Notifier.error('Failed to request state save.');
            });
    }

    loadState() {
         if (confirm("Are you sure you want to load the last saved state? This will overwrite the current state.")) {
             websocketClient.sendRequest('loadState') // Assuming a backend command for this
                .then(() => {
                    Notifier.info('System state load requested.');
                    // Backend should send initialState event after loading
                })
                .catch(err => {
                    console.error('Failed to send loadState command:', err);
                    Notifier.error('Failed to request state load.');
                });
         }
    }

    // Dialogue handling methods called by DialogueManager
    // These are now handled directly by DialogueManager using websocketClient
    // but kept here for clarity if App needed to orchestrate them.
    // cancelDialogue(dialogueId) {
    //     websocketClient.sendRequest('cancelDialogue', {dialogueId: dialogueId})
    //         .catch(err => console.error(`Failed to send cancelDialogue command ${dialogueId}:`, err));
    // }
}

// Initialize the app when the DOM is ready
document.addEventListener('DOMContentLoaded', () => {
    const app = new App('#app');

    // Initialize REPL if the container exists
    const replContainer = document.getElementById('repl-container');
    if (replContainer) {
        // Assuming repl.js exports an init function
        import('./repl/index.js').then(repl => {
            repl.init(replContainer, websocketClient);
        }).catch(err => {
            console.error('Failed to load REPL:', err);
            replContainer.innerHTML = '<p style="color: red;">Failed to load REPL.</p>';
        });
    }
});
