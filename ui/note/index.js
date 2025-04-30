import {websocketClient} from '../client.js';

const Utils = {
    debounce(func, wait) {
        let t;
        return (...a) => {
            clearTimeout(t);
            t = setTimeout(() => func.apply(this, a), wait);
        };
    },
    timeAgo(ts) {
        const s = Math.floor((Date.now() - ts) / 1e3);
        if (s < 5) return "just now";
        const i = [{l: 'y', s: 31536e3}, {l: 'm', s: 2592e3}, {l: 'd', s: 86400}, {l: 'h', s: 3600}, {
            l: 'm',
            s: 60
        }, {l: 's', s: 1}];
        for (const {l, s: v} of i) {
            const c = Math.floor(s / v);
            if (c >= 1) return `${c}${l} ago`;
        }
        return "just now";
    },
    sanitizeHTML: (s) => {
        const div = document.createElement('div');
        div.textContent = s;
        return div.innerHTML;
    },
    extractText: (html) => {
        const div = document.createElement('div');
        div.innerHTML = html;
        return div.textContent || "";
    }
};

const Notifier = {
    success: (m) => console.log(`SUCCESS: ${m}`),
    info: (m) => console.log(`INFO: ${m}`),
    warning: (m) => console.warn(`WARNING: ${m}`),
    error: (m) => console.error(`ERROR: ${m}`)
};

class Component {
    constructor(app, elSelector) {
        this.app = app;
        this.el = document.querySelector(elSelector);
        if (!this.el) {
            console.error(`Component failed to find element: ${elSelector}`);
        }
    }

    render() {
    }

    bindEvents() {
    }

    destroy() {
        if (this.el) {
            this.el.innerHTML = '';
            const newEl = this.el.cloneNode(true);
            this.el.parentNode.replaceChild(newEl, this.el);
            this.el = newEl;
        }
    }
}

class NoteItem {
    constructor(app, note) {
        this.app = app;
        this.note = note;
        this.el = this.createMarkup();
        this.bindEvents();
    }

    createMarkup() {
        const preview = Utils.extractText(this.note.content).substring(0, 80) + (Utils.extractText(this.note.content).length > 80 ? '...' : '');
        const div = document.createElement('div');
        div.classList.add('note-item');
        div.dataset.id = this.note.id;
        div.style.borderLeftColor = this.note.color || '#ccc';
        div.innerHTML = `
            <h4>${Utils.sanitizeHTML(this.note.title || 'Untitled Note')}</h4>
            <div class="meta">Cr: ${new Date(this.note.created).toLocaleDateString()} | Upd: ${Utils.timeAgo(this.note.updated)} | St: ${this.note.state} | P: ${this.note.priority || 0}</div>
            <div class="preview">${Utils.sanitizeHTML(preview)}</div>
            <div class="note-priority-controls">
                <button class="priority-inc" title="Increase Priority">+</button>
                <button class="priority-dec" title="Decrease Priority">-</button>
            </div>
        `;
        return div;
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
        const preview = Utils.extractText(note.content).substring(0, 80) + (Utils.extractText(note.content).length > 80 ? '...' : '');
        this.el.querySelector('h4').textContent = Utils.sanitizeHTML(note.title || 'Untitled Note');
        this.el.querySelector('.meta').textContent = `Cr: ${new Date(note.created).toLocaleDateString()} | Upd: ${Utils.timeAgo(note.updated)} | St: ${note.state} | P: ${note.priority || 0}`;
        this.el.querySelector('.preview').textContent = Utils.sanitizeHTML(preview);
        this.el.style.borderLeftColor = note.color || '#ccc';
    }

    setActive(isActive) {
        this.el.classList.toggle('selected', isActive);
    }

    remove() {
        this.el.remove();
    }
}

class NoteList extends Component {
    constructor(app, elSelector) {
        super(app, elSelector);
        this.items = {};
    }

    render(notes, currentId) {
        this.el.innerHTML = '';
        this.items = {};
        if (notes?.length) {
            notes.forEach(n => {
                const i = new NoteItem(this.app, n);
                this.items[n.id] = i;
                this.el.appendChild(i.el);
                if (n.id === currentId) i.setActive(true);
            });
        } else {
            this.el.innerHTML = '<p style="text-align:center;color:var(--text-muted);margin-top:20px;">No notes.</p>';
        }
    }

    updateItem(note) {
        this.items[note.id]?.update(note);
    }

    removeItem(noteId) {
        if (this.items[noteId]) {
            this.items[noteId].remove();
            delete this.items[noteId];
        }
    }

    setActive(id) {
        Object.values(this.items).forEach(i => i.setActive(false));
        if (id && this.items[id]) {
            this.items[id].setActive(true);
            const itemEl = this.items[id].el;
            const container = this.el;
            const itemTop = itemEl.offsetTop;
            const itemBottom = itemTop + itemEl.offsetHeight;
            if (itemTop < container.scrollTop || itemBottom > container.scrollTop + container.clientHeight) {
                container.scrollTop = itemTop - container.clientHeight / 2 + itemEl.offsetHeight / 2;
            }
        }
    }
}

class Sidebar extends Component {
    constructor(app, elSelector) {
        super(app, elSelector);
        this.noteList = null;
        this.render();
        this.bindEvents();
    }

    render() {
        this.el.innerHTML = `
            <div class="sidebar-controls">
                <button id="add-note-btn" title="Create New Note">+</button>
                <select id="sort-notes">
                    <option value="priority">Sort: Priority</option>
                    <option value="updated">Sort: Updated</option>
                    <option value="created">Sort: Created</option>
                    <option value="title">Sort: Title</option>
                </select>
            </div>
            <input type="search" id="search-notes" placeholder="Filter/Search Notes...">
            <div class="note-list-container" id="note-list"></div>
        `;
        this.noteList = new NoteList(this.app, '#note-list');
    }

    bindEvents() {
        this.el.querySelector('#add-note-btn').addEventListener('click', () => this.app.createNewNote());
        this.el.querySelector('#sort-notes').addEventListener('change', () => this.app.sortAndFilter());
        this.el.querySelector('#search-notes').addEventListener('input', Utils.debounce(() => this.app.sortAndFilter(), 300));
    }

    renderNotes(n, c) {
        this.noteList.render(n, c);
    }

    updateNote(n) {
        this.noteList.updateItem(n);
    }

    removeNote(noteId) {
        this.noteList.removeItem(noteId);
    }

    setActive(id) {
        this.noteList.setActive(id);
    }

    getSort() {
        return this.el.querySelector('#sort-notes').value;
    }

    getSearch() {
        return this.el.querySelector('#search-notes').value;
    }
}

class Editor extends Component {
    constructor(app, elSelector) {
        super(app, elSelector);
        this.save = Utils.debounce(() => this.app.saveCurrentNote(), 1e3);
        this.render();
        this.bindEvents();
    }

    render() {
        this.el.innerHTML = `
            <div class="editor-header">
                <input type="text" class="editor-title" id="note-title" placeholder="Note Title">
                <div class="editor-meta" id="note-meta">Select or create a note</div>
                <span id="save-status" class="save-status"></span>
            </div>
            <div class="editor-toolbar">
                <button data-command="bold" class="icon" title="Bold">B</button>
                <button data-command="italic" class="icon" title="Italic">I</button>
                <button data-command="underline" class="icon" title="Underline">U</button>
                <button data-command="insertUnorderedList" class="icon" title="Bullet List">UL</button>
                <button data-command="insertOrderedList" class="icon" title="Numbered List">OL</button>
                <button data-action="insert-field" class="icon" title="Insert Field">+</button>
            </div>
            <div class="editor-content-wrapper">
                <div class="editor-content" id="note-content" contenteditable="false" placeholder="Start writing..."></div>
            </div>
        `;
        this.titleEl = this.el.querySelector('#note-title');
        this.metaEl = this.el.querySelector('#note-meta');
        this.contentEl = this.el.querySelector('#note-content');
        this.saveStatusEl = this.el.querySelector('#save-status');
    }

    bindEvents() {
        this.el.querySelectorAll('.editor-toolbar button[data-command]').forEach(button => {
            button.addEventListener('click', (e) => {
                document.execCommand(e.currentTarget.dataset.command, false, null);
                this.contentEl.dispatchEvent(new Event('input'));
                this.contentEl.focus();
            });
        });
        this.el.querySelector('.editor-toolbar button[data-action="insert-field"]').addEventListener('click', () => this.insertField());
        this.contentEl.addEventListener('input', () => this.save());
        this.titleEl.addEventListener('input', () => this.save());
        this.contentEl.addEventListener('keydown', (e) => {
            if (e.key === 'Tab' && !e.shiftKey && this.contentEl.isContentEditable) {
                e.preventDefault();
                document.execCommand('insertHTML', false, '&#009;');
            } else if (e.key === 'Tab' && e.shiftKey && this.contentEl.isContentEditable) {
                e.preventDefault();
            }
        });
    }

    load(n) {
        if (n) {
            this.titleEl.value = n.title || '';
            this.titleEl.disabled = false;
            this.contentEl.innerHTML = n.content || '';
            this.contentEl.contentEditable = true;
            this.updateMeta(n);
            this.saveStatusEl.textContent = '';
        } else this.clear();
    }

    updateMeta(n) {
        this.metaEl.textContent = `Cr: ${new Date(n.created).toLocaleDateString()} | Upd: ${Utils.timeAgo(n.updated)} | St: ${n.state} | P: ${n.priority || 0}`;
    }

    clear() {
        this.titleEl.value = '';
        this.titleEl.disabled = true;
        this.contentEl.innerHTML = '';
        this.contentEl.contentEditable = false;
        this.metaEl.textContent = 'Select or create a note';
        this.saveStatusEl.textContent = '';
    }

    getData() {
        return {title: this.titleEl.value, content: this.contentEl.innerHTML};
    }

    focusTitle() {
        this.titleEl.focus();
    }

    insertField() {
        const name = prompt("Field name (e.g., Cost):");
        if (name) {
            const value = prompt(`Value for ${name} (e.g., < $20):`);
            const html = ` <span class='field'><span class='field-label'>${Utils.sanitizeHTML(name)}:</span><span class='field-value' contenteditable='true'>${Utils.sanitizeHTML(value || '')}</span></span> `;
            document.execCommand('insertHTML', false, html);
            this.contentEl.dispatchEvent(new Event('input'));
            this.contentEl.focus();
        }
    }

    setSaveStatus(status) {
        this.saveStatusEl.textContent = status;
        if (status === 'Saved') {
            setTimeout(() => this.saveStatusEl.textContent = '', 2000);
        }
    }
}

class MenuBar extends Component {
    constructor(app, elSelector) {
        super(app, elSelector);
        this.render();
        this.bindEvents();
    }

    render() {
        this.el.innerHTML = `
            <div class="group">
                <span data-action="undo" title="Undo last action">Undo</span>
                <span data-action="redo" title="Redo last action">Redo</span>
                <span data-action="clone" title="Create a copy of the current note">Clone</span>
            </div>
            <div class="group">
                <span data-action="insert" title="Insert structured data field">Insert Field</span>
            </div>
            <div class="group">
                <span data-action="publish" title="Publish note to P2P network">Publish</span>
                <span data-action="set-private" title="Make note Private">Set Private</span>
            </div>
            <div class="group">
                <span>Tools:</span>
                <span data-action="enhance" title="Apply LLM enhancement (Stub)">Enhance</span>
                <span data-action="summary" title="Apply LLM summarization (Stub)">Summary</span>
            </div>
            <div class="group">
                <span data-action="delete" title="Delete current note">Delete</span>
                <span data-action="view-source" title="View raw note data">View source</span>
                <span data-action="settings" title="Open application settings">Settings</span>
                <span data-action="refresh-notes" title="Refresh note list from backend">Refresh Notes</span>
            </div>
        `;
    }

    bindEvents() {
        this.el.querySelectorAll('span[data-action]').forEach(span => {
            span.addEventListener('click', (e) => this.app.handleMenu(e.currentTarget.dataset.action));
        });
    }
}

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
        const div = document.createElement('div');
        div.classList.add('action-icon');
        if (this.data.urgent) div.classList.add('urgent');
        div.dataset.type = this.data.type;
        div.title = `${this.data.type}: ${this.data.details}`;
        div.innerHTML = `
            ${this.data.symbol || this.data.type[0]}
            <span class="hide-btn" title="Hide this icon">x</span>
            <span class="tooltip">${this.data.type}: ${this.data.details}</span>
        `;
        return div;
    }

    bindEvents() {
        this.el.addEventListener('click', (e) => {
            if (e.target.classList.contains('hide-btn')) {
                this.hide();
            } else {
                this.app.showDock(this.data);
            }
        });
    }

    hide() {
        this.el.style.transition = 'opacity 0.3s ease';
        this.el.style.opacity = '0';
        this.el.addEventListener('transitionend', () => this.el.remove());
    }
}

class ActionArea extends Component {
    constructor(app, elSelector) {
        super(app, elSelector);
        this.icons = [];
        this.render();
        this.bindEvents();
    }

    render() {
        this.el.innerHTML = `
            <div class="action-icons-container" id="action-icons"></div>
            <div class="action-area-description">Action Dock: Matches, Questions, Alerts etc.</div>
            <div class="action-dock" id="action-dock">
                <div id="action-dock-content"></div>
            </div>
        `;
        this.iconsEl = this.el.querySelector('#action-icons');
        this.dockEl = this.el.querySelector('#action-dock');
        this.dockContentEl = this.el.querySelector('#action-dock-content');
    }

    bindEvents() {
        document.addEventListener('click', (e) => {
            const isClickInside = this.el.contains(e.target);
            if (!isClickInside && this.dockEl.classList.contains('visible')) {
                this.hideDock();
            }
        });
    }

    renderIcons(actionDataArray) {
        this.clearIcons();
        if (!actionDataArray || actionDataArray.length === 0) return;
        actionDataArray.forEach(d => this.icons.push(new ActionIcon(this.app, this.iconsEl, d)));
    }

    clearIcons() {
        this.icons.forEach(i => i.el.remove());
        this.icons = [];
        this.hideDock();
    }

    showDock(data) {
        let html = `<h5>${Utils.sanitizeHTML(data.type)} Details</h5><ul>`;
        // Assuming data.details can be an array or string
        if (Array.isArray(data.details)) {
            data.details.forEach(detail => {
                html += `<li>${Utils.sanitizeHTML(detail)}</li>`;
            });
        } else {
            html += `<li>${Utils.sanitizeHTML(data.details)}</li>`;
        }

        // Add actions if present in data
        if (data.actions && Array.isArray(data.actions)) {
             html += `</ul><h5>Actions</h5><ul>`;
             data.actions.forEach(action => {
                 // Assuming action is an object { label: string, command: string, params: object }
                 html += `<li><button class="dock-action-button" data-command="${Utils.sanitizeHTML(action.command)}" data-params='${JSON.stringify(action.params)}'>${Utils.sanitizeHTML(action.label)}</button></li>`;
             });
        }

        html += `</ul>`;
        this.dockContentEl.innerHTML = html;
        this.dockEl.classList.add('visible');

        // Bind events for action buttons within the dock
        this.dockContentEl.querySelectorAll('.dock-action-button').forEach(button => {
            button.addEventListener('click', (e) => {
                const cmd = e.currentTarget.dataset.command;
                const params = JSON.parse(e.currentTarget.dataset.params);
                console.log(`Dock action: ${cmd} with params`, params);
                this.app.sendCommand(cmd, params); // Delegate command sending to App
                this.hideDock(); // Hide dock after action
            });
        });
    }

    hideDock() {
        this.dockEl.classList.remove('visible');
    }
}

class SettingsModal extends Component {
    constructor(app, elSelector) {
        super(app, elSelector);
        this.render();
        this.bindEvents();
    }

    render() {
        this.el.innerHTML = `
            <div class="modal-content">
                <div class="modal-header">
                    <h4>Settings</h4>
                    <span class="modal-close" id="settings-modal-close" title="Close">×</span>
                </div>
                <div class="modal-body">
                    <ul class="modal-tabs" id="settings-tabs">
                        <li class="active" data-tab="general">General</li>
                        <li data-tab="network">Network</li>
                        <li data-tab="ontology">Ontology</li>
                        <li data-tab="llm">LLM Tools</li>
                        <li data-tab="appearance">Appearance</li>
                    </ul>
                    <div class="modal-tab-content active" id="tab-general">
                        <h5>General</h5>
                        <label for="setting-default-state">Default Note State:</label>
                        <select id="setting-default-state"><option value="Private">Private</option><option value="Published">Published</option></select>
                    </div>
                    <div class="modal-tab-content" id="tab-network">
                        <h5>Network (P2P)</h5>
                        <label for="setting-nostr-relays">Nostr Relays (one per line):</label>
                        <textarea id="setting-nostr-relays" rows="4"></textarea>
                        <p style="font-size:.9em;color:var(--text-muted)">Configure relays for P2P publishing.</p>
                    </div>
                    <div class="modal-tab-content" id="tab-ontology">
                        <h5>Ontology</h5>
                        <button disabled>Import (Stub)</button>
                        <button disabled>Export (Stub)</button>
                        <p style="margin-top:15px;font-size:.9em;color:var(--text-muted)">Manage types/fields (future).</p>
                    </div>
                    <div class="modal-tab-content" id="tab-llm">
                        <h5>LLM Tools</h5>
                        <label for="setting-llm-provider">Provider:</label>
                        <select id="setting-llm-provider"><option>OpenAI (Stub)</option><option>Local (Stub)</option></select>
                        <label for="setting-llm-apikey">API Key:</label>
                        <input type="password" id="setting-llm-apikey">
                    </div>
                    <div class="modal-tab-content" id="tab-appearance">
                        <h5>Appearance</h5>
                        <label for="setting-theme">Theme:</label>
                        <select id="setting-theme"><option>Light</option><option disabled>Dark (Stub)</option></select>
                    </div>
                </div>
                <div class="modal-footer">
                    <button id="settings-cancel-btn">Cancel</button>
                    <button id="settings-save-btn">Save</button>
                </div>
            </div>
        `;
        this.modalContentEl = this.el.querySelector('.modal-content');
        this.closeButton = this.el.querySelector('#settings-modal-close');
        this.cancelButton = this.el.querySelector('#settings-cancel-btn');
        this.saveButton = this.el.querySelector('#settings-save-btn');
        this.tabsEl = this.el.querySelector('#settings-tabs');
    }

    bindEvents() {
        this.closeButton.addEventListener('click', () => this.hide());
        this.cancelButton.addEventListener('click', () => this.hide());
        this.saveButton.addEventListener('click', () => this.save());
        this.tabsEl.querySelectorAll('li').forEach(tab => {
            tab.addEventListener('click', (e) => this.switchTab(e.currentTarget));
        });
        this.el.addEventListener('click', (e) => {
            if (e.target === this.el) this.hide();
        });
    }

    switchTab(tabEl) {
        const id = tabEl.dataset.tab;
        this.tabsEl.querySelectorAll('li').forEach(li => li.classList.remove('active'));
        this.modalContentEl.querySelectorAll('.modal-tab-content').forEach(content => content.classList.remove('active'));
        tabEl.classList.add('active');
        this.modalContentEl.querySelector(`#tab-${id}`).classList.add('active');
    }

    loadSettings(settings) {
        this.el.querySelector('#setting-default-state').value = settings.defaultState || 'Private';
        this.el.querySelector('#setting-nostr-relays').value = settings.nostrRelays || '';
        this.el.querySelector('#setting-llm-provider').value = settings.llmProvider || 'OpenAI (Stub)';
        this.el.querySelector('#setting-llm-apikey').value = settings.llmApiKey || '';
        this.el.querySelector('#setting-theme').value = settings.theme || 'Light';
    }

    save() {
        const settings = {
            defaultState: this.el.querySelector('#setting-default-state').value,
            nostrRelays: this.el.querySelector('#setting-nostr-relays').value,
            llmProvider: this.el.querySelector('#setting-llm-provider').value,
            llmApiKey: this.el.querySelector('#setting-llm-apikey').value,
            theme: this.el.querySelector('#setting-theme').value
        };
        this.app.updateSettings(settings);
        Notifier.success('Settings saved.');
        this.hide();
    }

    show() {
        this.el.classList.add('visible');
    }

    hide() {
        this.el.classList.remove('visible');
    }
}

class DialogueManager extends Component {
    constructor(app, elSelector) {
        super(app, elSelector);
        this.modalEl = this.el;
        this.promptEl = this.el.querySelector('#dialogue-prompt');
        this.inputEl = this.el.querySelector('#dialogue-input');
        this.sendButton = this.el.querySelector('#dialogue-send-button');
        this.cancelButton = this.el.querySelector('#dialogue-cancel-button');
        this.closeButton = this.el.querySelector('#dialogue-modal-close');
        this.currentDialogueId = null;

        this.bindEvents();
    }

    bindEvents() {
        this.sendButton.addEventListener('click', () => this.sendResponse());
        this.cancelButton.addEventListener('click', () => this.cancelDialogue());
        this.closeButton.addEventListener('click', () => this.cancelDialogue());
        this.inputEl.addEventListener('keypress', (e) => {
            if (e.key === 'Enter') {
                e.preventDefault();
                this.sendResponse();
            }
        });
        this.modalEl.addEventListener('click', (e) => {
            if (e.target === this.modalEl) {
                this.cancelDialogue();
            }
        });
    }

    show(dialogueRequest) {
        if (this.currentDialogueId) {
            console.warn(`Received new dialogue request (${dialogueRequest.dialogueId}) while another is active (${this.currentDialogueId}). Cancelling the old one.`);
            this.cancelDialogue();
        }

        if (dialogueRequest.requestType !== 'text_input') {
            console.warn(`Received unsupported dialogue request type: ${dialogueRequest.requestType}. Cancelling.`);
            this.app.cancelDialogue(dialogueRequest.dialogueId);
            return;
        }

        this.currentDialogueId = dialogueRequest.dialogueId;
        this.promptEl.textContent = dialogueRequest.prompt;
        this.inputEl.value = '';
        this.modalEl.classList.add('visible');
        this.inputEl.focus();
    }

    hide() {
        this.modalEl.classList.remove('visible');
        this.currentDialogueId = null;
        this.promptEl.textContent = '';
        this.inputEl.value = '';
    }

    sendResponse() {
        if (!this.currentDialogueId) return;

        const responseText = this.inputEl.value.trim();

        console.log(`Sending dialogue response for ${this.currentDialogueId}: "${responseText}"`);
        websocketClient.sendDialogueResponse(this.currentDialogueId, {text: responseText})
            .then(response => {
                console.log('Dialogue response acknowledged by backend:', response);
            })
            .catch(err => {
                console.error('Failed to send dialogue response:', err);
                Notifier.error('Failed to send dialogue response.');
            });

        this.hide();
    }

    cancelDialogue() {
        if (!this.currentDialogueId) return;

        console.log(`Cancelling dialogue ${this.currentDialogueId}`);
        this.app.cancelDialogue(this.currentDialogueId);

        this.hide();
    }

    handleWsDisconnected() {
        if (this.currentDialogueId) {
            console.warn('WS disconnected, cancelling active dialogue.');
            this.hide();
            Notifier.warning('Dialogue cancelled due to disconnection.');
        }
    }
}


class App {
    constructor(elSelector) {
        this.el = document.querySelector(elSelector);
        this.notes = [];
        this.currentId = null;
        this.settings = {};
        this.initComps();
        this.bindWebSocketEvents();
        console.log("Netention Note App Ready");

        if (websocketClient.isConnected) {
            console.log('WS already connected, requesting initial state...');
            websocketClient.sendInitialStateRequest()
                .catch(err => {
                    console.error('Failed to request initial state on ready WS:', err);
                    Notifier.error('Failed to load initial state.');
                });
        } else {
            console.log('WS not connected, waiting for connection...');
        }
    }

    initComps() {
        this.el.innerHTML = `
            <aside class="sidebar" id="sidebar-container"></aside>
            <main class="main-content">
                <nav class="menu-bar" id="menu-bar-container"></nav>
                <div class="editor-area" id="editor-container"></div>
                <div class="action-area" id="action-area-container"></div>
            </main>
            <div id="settings-modal" class="modal"></div>
            <div id="dialogue-modal" class="modal"></div>
        `;
        this.sidebar = new Sidebar(this, '#sidebar-container');
        this.editor = new Editor(this, '#editor-container');
        this.menuBar = new MenuBar(this, '#menu-bar-container');
        this.actionArea = new ActionArea(this, '#action-area-container');
        this.settingsModal = new SettingsModal(this, '#settings-modal');
        this.dialogueManager = new DialogueManager(this, '#dialogue-modal');
    }

    bindWebSocketEvents() {
        websocketClient.on('connected', () => {
            console.log('WS Connected');
            Notifier.info('Connected to backend.');
            websocketClient.sendInitialStateRequest()
                .catch(err => {
                    console.error('Failed to request initial state:', err);
                    Notifier.error('Failed to load initial state.');
                });
        });

        websocketClient.on('disconnected', (e) => {
            console.warn('WS Disconnected', e);
            Notifier.error('Disconnected from backend.');
            this.notes = [];
            this.currentId = null;
            this.sortAndFilter();
            this.editor.clear();
            this.actionArea.clearIcons();
            this.editor.metaEl.textContent = 'Disconnected. Attempting to reconnect...';
            this.dialogueManager.handleWsDisconnected();
        });

        websocketClient.on('reconnectFailed', () => {
            Notifier.error('Failed to connect to backend.');
            this.editor.metaEl.textContent = 'Connection failed.';
        });

        websocketClient.on('error', (e) => {
            console.error('WS Error', e);
            Notifier.error('WebSocket error occurred.');
        });

        websocketClient.on('initialState', (state) => this.handleInitialState(state));
        websocketClient.on('NoteStatusEvent', (event) => this.handleNoteStatusEvent(event));
        websocketClient.on('NoteUpdatedEvent', (event) => this.handleNoteUpdatedEvent(event));
        websocketClient.on('NoteDeletedEvent', (event) => this.handleNoteDeletedEvent(event));
        websocketClient.on('NoteAddedEvent', (event) => this.handleNoteAddedEvent(event));
        websocketClient.on('dialogueRequest', (request) => this.dialogueManager.show(request));
        websocketClient.on('response', (response) => {
            console.log('Received generic response:', response);
        });
        websocketClient.on('event', (event) => {
            console.log('Received generic event:', event);
        });
    }

    handleInitialState(state) {
        console.log('Received initial state:', state);
        if (state?.notes) {
            this.notes = state.notes;
            this.settings = state.configuration || {};
            this.settingsModal.loadSettings(this.settings);

            this.sortAndFilter();
            const sorted = this.getSortedFiltered();
            if (sorted.length) {
                const noteToSelect = this.currentId ? this.notes.find(n => n.id === this.currentId) : sorted[0];
                this.selectNote(noteToSelect ? noteToSelect.id : sorted[0].id);
            } else {
                this.currentId = null;
                this.editor.clear();
                this.sidebar.setActive(null);
                this.actionArea.clearIcons();
            }
        } else {
            console.warn('Initial state received without notes data.');
            this.notes = [];
            this.sortAndFilter();
            this.currentId = null;
            this.editor.clear();
            this.sidebar.setActive(null);
            this.actionArea.clearIcons();
        }
    }

    handleNoteStatusEvent(event) {
        console.log('Received NoteStatusEvent:', event);
        const note = this.notes.find(n => n.id === event.noteId);
        if (note) {
            note.state = event.newStatus;
            note.updated = event.timestamp || Date.now();
            this.sidebar.updateNote(note);
            if (note.id === this.currentId) {
                this.editor.updateMeta(note);
            }
            this.sortAndFilter();
        } else {
            console.warn('NoteStatusEvent for unknown note ID:', event.noteId);
            websocketClient.sendInitialStateRequest()
                .catch(err => console.error('Failed to request initial state after unknown NoteStatusEvent:', err));
        }
    }

    handleNoteUpdatedEvent(event) {
        console.log('Received NoteUpdatedEvent:', event);
        const noteIndex = this.notes.findIndex(n => n.id === event.noteId);
        if (noteIndex !== -1) {
            const updatedNoteData = event.updatedNote;
            if (updatedNoteData) {
                Object.assign(this.notes[noteIndex], updatedNoteData);
                this.notes[noteIndex].updated = updatedNoteData.updated || Date.now();

                this.sidebar.updateNote(this.notes[noteIndex]);
                if (this.notes[noteIndex].id === this.currentId) {
                    this.editor.load(this.notes[noteIndex]);
                }
                this.sortAndFilter();
                // Assuming action data is part of the updated note payload
                this.actionArea.renderIcons(this.notes[noteIndex].actions); // Pass actions from note
            } else {
                console.warn('NoteUpdatedEvent received without updated note data.');
            }
        } else {
            console.warn('NoteUpdatedEvent for unknown note ID:', event.noteId);
            websocketClient.sendInitialStateRequest()
                .catch(err => console.error('Failed to request initial state after unknown NoteUpdatedEvent:', err));
        }
    }

    handleNoteDeletedEvent(event) {
        console.log('Received NoteDeletedEvent:', event);
        const noteId = event.noteId;
        const noteIndex = this.notes.findIndex(n => n.id === noteId);
        if (noteIndex !== -1) {
            const deletedNoteTitle = this.notes[noteIndex].title || 'Untitled';
            this.notes.splice(noteIndex, 1);
            this.sidebar.removeNote(noteId);

            if (noteId === this.currentId) {
                this.currentId = null;
                this.editor.clear();
                this.sidebar.setActive(null);
                this.actionArea.clearIcons();
            }
            this.sortAndFilter();
            Notifier.success(`Note "${deletedNoteTitle}" deleted.`);
        } else {
            console.warn('NoteDeletedEvent for unknown note ID:', noteId);
            websocketClient.sendInitialStateRequest()
                .catch(err => console.error('Failed to request initial state after unknown NoteDeletedEvent:', err));
        }
    }

    handleNoteAddedEvent(event) {
        console.log('Received NoteAddedEvent:', event);
        const newNote = event.newNote;
        if (newNote && newNote.id && !this.notes.some(n => n.id === newNote.id)) {
            console.log('Adding new note to local state:', newNote.id);
            this.notes.unshift(newNote);
            this.sortAndFilter();
            this.selectNote(newNote.id);
            this.editor.focusTitle();
            Notifier.success(`New note "${newNote.title || 'Untitled'}" created.`);
        } else if (newNote && newNote.id) {
            console.warn('NoteAddedEvent received for a note already in the list:', newNote.id);
            this.selectNote(newNote.id);
        } else {
            console.warn('NoteAddedEvent received without valid new note data.');
        }
    }


    getSortedFiltered() {
        const sort = this.sidebar.getSort(), search = this.sidebar.getSearch().toLowerCase();
        let filtered = this.notes.filter(n => (n.title || '').toLowerCase().includes(search) || Utils.extractText(n.content).toLowerCase().includes(search));
        return filtered.sort((a, b) => {
            switch (sort) {
                case 'priority':
                    return (b.priority || 0) - (a.priority || 0);
                case 'updated':
                    return b.updated - a.updated;
                case 'created':
                    return b.created - a.created;
                case 'title':
                    return (a.title || '').localeCompare(b.title || '');
                default:
                    return (b.priority || 0) - (a.priority || 0);
            }
        });
    }

    sortAndFilter() {
        this.sidebar.renderNotes(this.getSortedFiltered(), this.currentId);
    }

    selectNote(id) {
        if (id === this.currentId && id !== null) return;
        this.saveCurrentNote();

        const n = this.notes.find(n => n.id === id);
        if (n) {
            this.currentId = id;
            this.editor.load(n);
            this.sidebar.setActive(id);
            // Assuming action data is part of the note object
            this.actionArea.renderIcons(n.actions);
        } else {
            console.error(`Note ${id} not found in local state.`);
            this.currentId = null;
            this.editor.clear();
            this.sidebar.setActive(null);
            this.actionArea.clearIcons();
            Notifier.warning(`Note ${id} not found.`);
        }
    }

    saveCurrentNote() {
        if (this.currentId === null) return false;
        const n = this.notes.find(n => n.id === this.currentId);
        if (n) {
            const d = this.editor.getData();
            let changed = false;
            if (n.title !== d.title) {
                n.title = d.title;
                changed = true;
            }
            if (n.content !== d.content) {
                n.content = d.content;
                changed = true;
            }

            if (changed) {
                n.updated = Date.now();

                this.editor.setSaveStatus('Saving...');

                websocketClient.sendUiAction('updateNote', {
                    noteId: n.id,
                    title: n.title,
                    content: d.content,
                    state: n.state,
                    priority: n.priority,
                    color: n.color
                }).then(response => {
                    console.log(`Backend acknowledged update for ${n.id}:`, response);
                    this.editor.setSaveStatus('Saved');
                }).catch(err => {
                    console.error(`Failed to send update for ${n.id}:`, err);
                    this.editor.setSaveStatus('Save Failed');
                    Notifier.error(`Failed to save note "${n.title || 'Untitled'}".`);
                });

                this.sidebar.updateNote(n);
                this.editor.updateMeta(n);
                // Action icons will be re-rendered when NoteUpdatedEvent arrives
                console.log(`Attempted save for ${n.id}`);
                return true;
            } else {
                this.editor.setSaveStatus('');
            }
        }
        return false;
    }

    createNewNote() {
        this.saveCurrentNote();

        const newNoteData = {
            title: "Untitled Note",
            content: "",
            state: this.settings.defaultState || 'Private',
            color: `hsl(${Math.random() * 360}, 60%, 85%)`
        };

        this.editor.clear();
        this.currentId = null;
        this.sidebar.setActive(null);
        this.actionArea.clearIcons();
        this.editor.metaEl.textContent = 'Requesting new note... If it doesn\'t appear, try "Refresh Notes" from the menu.';

        websocketClient.sendUiAction('addNote', newNoteData)
            .then(response => {
                console.log('Backend acknowledged new note creation:', response);
                Notifier.info("New note creation requested.");
            })
            .catch(err => {
                console.error('Failed to send new note action:', err);
                Notifier.error("Failed to create new note.");
                this.editor.metaEl.textContent = 'Failed to create new note. Select or create a note.';
            });
    }

    updateNotePriority(id, delta) {
        const n = this.notes.find(n => n.id === id);
        if (n) {
            const newPriority = (n.priority || 0) + delta;
            n.priority = newPriority;
            n.updated = Date.now();

            websocketClient.sendUiAction('updateNotePriority', {
                noteId: id,
                priority: newPriority
            }).then(response => {
                console.log(`Backend acknowledged priority update for ${id}:`, response);
            }).catch(err => {
                console.error(`Failed to send priority update for ${id}:`, err);
                Notifier.error(`Failed to update priority for "${n.title || 'Untitled'}".`);
            });

            this.sortAndFilter();
            if (id === this.currentId) {
                this.editor.updateMeta(n);
            }
            Notifier.info(`Priority update requested for "${n.title || 'Untitled'}"`);
        } else {
            console.warn(`Attempted to update priority for unknown note ID: ${id}`);
            Notifier.warning(`Cannot update priority for unknown note.`);
        }
    }

    handleMenu(action) {
        this.saveCurrentNote();
        const n = this.currentId ? this.notes.find(n => n.id === this.currentId) : null;

        switch (action) {
            case 'undo':
                if (this.currentId && this.editor.contentEl.isContentEditable) {
                    document.execCommand('undo');
                } else {
                    Notifier.warning('Select a note and focus the editor to undo.');
                }
                break;
            case 'redo':
                if (this.currentId && this.editor.contentEl.isContentEditable) {
                    document.execCommand('redo');
                } else {
                    Notifier.warning('Select a note and focus the editor to redo.');
                }
                break;
            case 'clone':
                if (n) {
                    websocketClient.sendUiAction('cloneNote', {noteId: n.id})
                        .then(response => {
                            console.log('Backend acknowledged note clone:', response);
                            Notifier.info('Note cloning requested.');
                        })
                        .catch(err => {
                            console.error('Failed to send clone note action:', err);
                            Notifier.error('Failed to clone note.');
                        });
                } else Notifier.warning('Select note to clone.');
                break;
            case 'insert':
                if (this.currentId && this.editor.contentEl.isContentEditable) {
                    this.editor.insertField();
                } else {
                    Notifier.warning('Select a note and focus the editor to insert a field.');
                }
                break;
            case 'publish':
                this.updateNoteState(n, 'Published');
                break;
            case 'set-private':
                this.updateNoteState(n, 'Private');
                break;
            case 'enhance':
            case 'summary':
                if (n) {
                    this.sendCommand('runTool', {name: action, note_id: n.id});
                } else Notifier.warning(`Select note to run ${action} tool.`);
                break;
            case 'delete':
                if (n && confirm(`Delete "${n.title || 'Untitled Note'}"?`)) {
                    websocketClient.sendUiAction('deleteNote', {noteId: n.id})
                        .then(response => {
                            console.log('Backend acknowledged note deletion:', response);
                            this.notes = this.notes.filter(note => note.id !== n.id);
                            this.currentId = null;
                            this.sortAndFilter();
                            this.editor.clear();
                            this.actionArea.clearIcons();
                            Notifier.success('Note deletion requested.');
                        })
                        .catch(err => {
                            console.error('Failed to send delete note action:', err);
                            Notifier.error('Failed to delete note.');
                        });
                } else if (!n) Notifier.warning('Select note to delete.');
                break;
            case 'view-source':
                if (n) alert(`ID: ${n.id}\nTitle: ${n.title}\nState: ${n.state}\nPrio: ${n.priority}\nCreated: ${new Date(n.created).toLocaleString()}\nUpdated: ${new Date(n.updated).toLocaleString()}\n\nContent:\n${n.content}`); else Notifier.warning('Select note to view source.');
                break;
            case 'settings':
                this.settingsModal.show();
                break;
            case 'refresh-notes':
                console.log('Refreshing notes...');
                this.editor.metaEl.textContent = 'Refreshing notes...';
                websocketClient.sendInitialStateRequest()
                    .then(() => Notifier.info('Notes refreshed.'))
                    .catch(err => {
                        console.error('Failed to refresh notes:', err);
                        Notifier.error('Failed to refresh notes.');
                        this.editor.metaEl.textContent = 'Failed to refresh notes. Select or create a note.';
                    });
                break;
            default:
                console.warn(`Unknown menu action: ${action}`);
        }
    }

    updateNoteState(note, newState) {
        if (note) {
            const oldState = note.state;
            note.state = newState;
            note.updated = Date.now();

            websocketClient.sendUiAction('updateNoteState', {
                noteId: note.id,
                state: newState
            }).then(response => {
                console.log(`Backend acknowledged state update for ${note.id}:`, response);
            }).catch(err => {
                console.error(`Failed to send state update for ${note.id}:`, err);
                Notifier.error(`Failed to set note state to ${newState}.`);
                note.state = oldState;
                this.editor.updateMeta(note);
                this.sidebar.updateNote(note);
            });

            this.editor.updateMeta(note);
            this.sidebar.updateNote(note);
            Notifier.success(`Note "${note.title || 'Untitled'}" set to ${newState}.`);
        } else Notifier.warning(`Select a note to set ${newState}.`);
    }

    updateSettings(settings) {
        this.settings = settings;

        websocketClient.sendUiAction('updateSettings', {settings: settings})
            .then(response => {
                console.log('Backend acknowledged settings update:', response);
            })
            .catch(err => {
                console.error('Failed to send settings update action:', err);
                Notifier.error('Failed to save settings.');
            });
    }

    showDock(d) {
        this.actionArea.showDock(d);
    }

    sendCommand(commandName, parameters = {}) {
        websocketClient.sendCommand(commandName, parameters)
            .then(response => {
                console.log(`Backend acknowledged command '${commandName}':`, response);
                Notifier.info(`Command '${commandName}' requested.`);
            })
            .catch(err => {
                console.error(`Failed to send command '${commandName}':`, err);
                Notifier.error(`Failed to run command '${commandName}'.`);
            });
    }

    cancelDialogue(dialogueId) {
        websocketClient.sendCommand('cancelDialogue', {dialogueId: dialogueId})
            .then(response => console.log(`Backend acknowledged dialogue cancellation ${dialogueId}:`, response))
            .catch(err => console.error(`Failed to send cancelDialogue command ${dialogueId}:`, err));
    }
}

document.addEventListener('DOMContentLoaded', () => {
    new App('#app-container');
});
