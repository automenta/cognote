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
        this.render(); // Call render to add HTML content
        // Get element references *after* rendering
        this.promptEl = this.el.querySelector('#dialogue-prompt');
        this.inputEl = this.el.querySelector('#dialogue-input');
        this.sendButton = this.el.querySelector('#dialogue-send-button');
        this.cancelButton = this.el.querySelector('#dialogue-cancel-button');
        this.closeButton = this.el.querySelector('#dialogue-modal-close');
        this.currentDialogueId = null;

        this.bindEvents();
    }

    render() {
        // Add the HTML structure for the dialogue modal content
        this.el.innerHTML = `
            <div class="modal-content">
                <div class="modal-header">
                    <h4>Dialogue Request</h4>
                    <span class="modal-close" id="dialogue-modal-close" title="Close">×</span>
                </div>
                <div class="modal-body">
                    <p id="dialogue-prompt"></p>
                    <input type="text" id="dialogue-input" placeholder="Enter your response...">
                </div>
                <div class="modal-footer">
                    <button id="dialogue-cancel-button">Cancel</button>
                    <button id="dialogue-send-button">Send Response</button>
                </div>
            </div>
        `;
    }

    bindEvents() {
        // Ensure elements exist before adding listeners
        if (this.sendButton) this.sendButton.addEventListener('click', () => this.sendResponse());
        if (this.cancelButton) this.cancelButton.addEventListener('click', () => this.cancelDialogue());
        if (this.closeButton) this.closeButton.addEventListener('click', () => this.cancelDialogue());
        if (this.inputEl) {
            this.inputEl.addEventListener('keypress', (e) => {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    this.sendResponse();
                }
            });
        }
        if (this.modalEl) {
            this.modalEl.addEventListener('click', (e) => {
                if (e.target === this.modalEl) {
                    this.cancelDialogue();
                }
            });
        }
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
        if (this.promptEl) this.promptEl.textContent = dialogueRequest.prompt;
        if (this.inputEl) {
            this.inputEl.value = '';
            this.inputEl.focus();
        }
        if (this.modalEl) this.modalEl.classList.add('visible');
    }

    hide() {
        if (this.modalEl) this.modalEl.classList.remove('visible');
        this.currentDialogueId = null;
        if (this.promptEl) this.promptEl.textContent = '';
        if (this.inputEl) this.inputEl.value = '';
    }

    sendResponse() {
        if (!this.currentDialogueId || !this.inputEl) return;

        const responseText = this.inputEl.value.trim();

        console.log(`Sending dialogue response for ${this.currentDialogueId}: "${responseText}"`);
        // Use sendRequest for dialogueResponse command
        websocketClient.sendRequest('dialogueResponse', {
            dialogueId: this.currentDialogueId,
            responseData: { text: responseText } // Assuming text_input type
        })
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
        // Use sendRequest for cancelDialogue command
        websocketClient.sendRequest('cancelDialogue', { dialogueId: this.currentDialogueId })
            .then(response => {
                 console.log('Dialogue cancellation acknowledged by backend:', response);
            })
            .catch(err => {
                 console.error('Failed to send cancelDialogue command:', err);
                 Notifier.error('Failed to cancel dialogue.');
            });

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

        // Request initial state immediately if connected, otherwise wait for 'connected' event
        if (websocketClient.isConnected) {
            console.log('WS already connected, requesting initial state...');
            this.requestInitialState()
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
            // Request initial state upon connection
            this.requestInitialState()
                .catch(err => {
                    console.error('Failed to request initial state:', err);
                    Notifier.error('Failed to load initial state.');
                });
        });

        websocketClient.on('disconnected', (e) => {
            console.warn('WS Disconnected', e);
            Notifier.error('Disconnected from backend.');
            // Clear UI state on disconnection
            this.notes = [];
            this.currentId = null;
            this.sortAndFilter(); // Render empty list
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

        // Listen for specific update types emitted by the client
        websocketClient.on('initialState', (state) => this.handleInitialState(state));
        websocketClient.on('NoteStatusEvent', (event) => this.handleNoteStatusEvent(event));
        websocketClient.on('NoteUpdatedEvent', (event) => this.handleNoteUpdatedEvent(event));
        websocketClient.on('NoteDeletedEvent', (event) => this.handleNoteDeletedEvent(event));
        websocketClient.on('AddedEvent', (event) => this.handleNoteAddedEvent(event)); // AddedEvent is still used for new notes
        websocketClient.on('dialogueRequest', (request) => this.dialogueManager.show(request));
        // Generic 'event' listener can be used for debugging or unhandled events
        // websocketClient.on('event', (event) => { console.log('Received generic event:', event); });
        // Generic 'response' listener is less useful now that responses are handled by Promises
        // websocketClient.on('response', (response) => { console.log('Received generic response:', response); });
    }

    requestInitialState() {
        // Use sendRequest for initialStateRequest command
        return websocketClient.sendRequest('initialStateRequest', {});
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
                // Try to re-select the previously selected note if it exists, otherwise select the first one
                const noteToSelect = this.currentId ? this.notes.find(n => n.id === this.currentId) : sorted[0];
                this.selectNote(noteToSelect ? noteToSelect.id : sorted[0].id);
            } else {
                // No notes received, clear UI
                this.currentId = null;
                this.editor.clear();
                this.sidebar.setActive(null);
                this.actionArea.clearIcons();
            }
        } else {
            console.warn('Initial state received without notes data or notes array is empty.');
            // If notes array is explicitly empty or missing, treat as no notes
            this.notes = [];
            this.sortAndFilter(); // Render empty list
            this.currentId = null;
            this.editor.clear();
            this.sidebar.setActive(null);
            this.actionArea.clearIcons();
        }
    }

    handleNoteStatusEvent(event) {
        console.log('Received NoteStatusEvent:', event);
        // NoteStatusEvent payload contains the updated note object
        const updatedNote = event.note;
        if (!updatedNote || !updatedNote.id) {
             console.warn('NoteStatusEvent received without valid note data.');
             return;
        }

        const noteIndex = this.notes.findIndex(n => n.id === updatedNote.id);
        if (noteIndex !== -1) {
            // Update existing note object with new data from the event
            Object.assign(this.notes[noteIndex], updatedNote);
            this.sidebar.updateItem(this.notes[noteIndex]);
            if (this.notes[noteIndex].id === this.currentId) {
                this.editor.updateMeta(this.notes[noteIndex]);
            }
            this.sortAndFilter(); // Re-sort/filter as status might affect sorting
        } else {
            console.warn('NoteStatusEvent for unknown note ID:', updatedNote.id);
            // As a fallback, request full state if an event for an unknown note arrives
            this.requestInitialState()
                .catch(err => console.error('Failed to request initial state after unknown NoteStatusEvent:', err));
        }
    }

    handleNoteUpdatedEvent(event) {
        console.log('Received NoteUpdatedEvent:', event);
        // NoteUpdatedEvent payload contains the updated note object
        const updatedNote = event.updatedNote;
         if (!updatedNote || !updatedNote.id) {
             console.warn('NoteUpdatedEvent received without valid note data.');
             return;
         }

        const noteIndex = this.notes.findIndex(n => n.id === updatedNote.id);
        if (noteIndex !== -1) {
            // Update existing note object with new data from the event
            Object.assign(this.notes[noteIndex], updatedNote);

            this.sidebar.updateItem(this.notes[noteIndex]);
            if (this.notes[noteIndex].id === this.currentId) {
                this.editor.load(this.notes[noteIndex]); // Reload editor if it's the current note
            }
            this.sortAndFilter(); // Re-sort/filter as title, priority, updated might have changed
            // Assuming action data is part of the updated note payload
            this.actionArea.renderIcons(this.notes[noteIndex].actions); // Update action icons
        } else {
            console.warn('NoteUpdatedEvent for unknown note ID:', updatedNote.id);
            // As a fallback, request full state if an event for an unknown note arrives
            this.requestInitialState()
                .catch(err => console.error('Failed to request initial state after unknown NoteUpdatedEvent:', err));
        }
    }

    handleNoteDeletedEvent(event) {
        console.log('Received NoteDeletedEvent:', event);
        // NoteDeletedEvent payload contains the noteId
        const noteId = event.noteId;
        if (!noteId) {
             console.warn('NoteDeletedEvent received without noteId.');
             return;
        }

        const noteIndex = this.notes.findIndex(n => n.id === noteId);
        if (noteIndex !== -1) {
            const deletedNoteTitle = this.notes[noteIndex].title || 'Untitled';
            this.notes.splice(noteIndex, 1); // Remove from local array
            this.sidebar.removeItem(noteId); // Remove from sidebar UI

            if (noteId === this.currentId) {
                // If the deleted note was the current one, clear the editor
                this.currentId = null;
                this.editor.clear();
                this.sidebar.setActive(null);
                this.actionArea.clearIcons();
            }
            this.sortAndFilter(); // Update list view
            Notifier.success(`Note "${deletedNoteTitle}" deleted.`);
        } else {
            console.warn('NoteDeletedEvent for unknown note ID:', noteId);
            // As a fallback, request full state if an event for an unknown note arrives
            this.requestInitialState()
                .catch(err => console.error('Failed to request initial state after unknown NoteDeletedEvent:', err));
        }
    }

    handleNoteAddedEvent(event) {
        console.log('Received AddedEvent:', event);
        // AddedEvent payload contains the new note object
        const newNote = event.note;
        if (!newNote || !newNote.id) {
             console.warn('AddedEvent received without valid note data.');
             return;
        }

        // Check if the note already exists in our local state (might happen on reconnect/initial state)
        if (!this.notes.some(n => n.id === newNote.id)) {
            console.log('Adding new note to local state:', newNote.id);
            this.notes.unshift(newNote); // Add to the beginning of the array
            this.sortAndFilter(); // Update list view
            this.selectNote(newNote.id); // Select the newly added note
            this.editor.focusTitle(); // Focus the title field
            Notifier.success(`New note "${newNote.title || 'Untitled'}" created.`);
        } else {
            console.warn('AddedEvent received for a note already in the list:', newNote.id);
            // If it already exists, maybe just select it if it's not already?
            // Or update it if the event contains updated data? The current backend event
            // seems to only contain the new note, not updates.
            // For now, if it exists, just select it.
             if (this.currentId !== newNote.id) {
                 this.selectNote(newNote.id);
             }
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
        // Save current note before switching
        if (this.currentId !== null && this.currentId !== id) {
             this.saveCurrentNote();
        }

        if (id === this.currentId && id !== null) return; // Already selected

        const n = this.notes.find(n => n.id === id);
        if (n) {
            this.currentId = id;
            this.editor.load(n); // Load note data into editor
            this.sidebar.setActive(id); // Highlight in sidebar
            // Assuming action data is part of the note object
            this.actionArea.renderIcons(n.actions); // Render action icons for this note
        } else {
            console.error(`Note ${id} not found in local state.`);
            // If selected note is somehow missing from local state, clear UI
            this.currentId = null;
            this.editor.clear();
            this.sidebar.setActive(null);
            this.actionArea.clearIcons();
            Notifier.warning(`Note ${id} not found.`);
            // Optionally request a full state refresh here as a last resort
            this.requestInitialState().catch(err => console.error('Failed to request initial state after selecting unknown note:', err));
        }
    }

    saveCurrentNote() {
        if (this.currentId === null) return false; // No note selected
        const n = this.notes.find(n => n.id === this.currentId);
        if (n) {
            const d = this.editor.getData();
            let changed = false;
            // Check if title or content has actually changed
            if (n.title !== d.title) {
                // n.title = d.title; // Update local state immediately for responsiveness - now done by event
                changed = true;
            }
            if (n.content !== d.content) {
                // n.content = d.content; // Update local state immediately - now done by event
                changed = true;
            }

            if (changed) {
                // Update local timestamp immediately for responsiveness - now done by event
                // n.updated = Date.now();

                this.editor.setSaveStatus('Saving...');
                // this.sidebar.updateNote(n); // Update sidebar preview/meta immediately - now done by event
                // this.editor.updateMeta(n); // Update editor meta immediately - now done by event

                // Send update command to backend
                websocketClient.sendRequest('updateNote', {
                    noteId: n.id,
                    title: d.title, // Send the potentially changed title
                    content: d.content, // Send the potentially changed content
                    state: n.state, // Include state and priority in update payload
                    priority: n.priority,
                    color: n.color // Include color
                }).then(response => {
                    console.log(`Backend acknowledged update for ${n.id}:`, response);
                    this.editor.setSaveStatus('Saved');
                    // NoteUpdatedEvent from backend will provide the definitive state update
                }).catch(err => {
                    console.error(`Failed to send update for ${n.id}:`, err);
                    this.editor.setSaveStatus('Save Failed');
                    Notifier.error(`Failed to save note "${n.title || 'Untitled'}".`);
                    // Error handling: Could revert local state or mark as unsaved
                    // Reverting local state is tricky with async updates, might need a more robust sync mechanism (CRDTs!)
                });

                // this.sortAndFilter(); // Re-sort/filter as title/updated might have changed - now done by event
                console.log(`Attempted save for ${n.id}`);
                return true; // Indicates a save action was initiated
            } else {
                this.editor.setSaveStatus(''); // Clear status if no changes
            }
        }
        return false; // No note or no changes
    }

    createNewNote() {
        this.saveCurrentNote(); // Save the currently selected note first

        const newNoteData = {
            title: "Untitled Note",
            content: "",
            state: this.settings.defaultState || 'Private', // Use default state from settings
            color: `hsl(${Math.random() * 360}, 60%, 85%)` // Assign a random color
        };

        // Clear UI immediately for perceived responsiveness
        this.editor.clear();
        this.currentId = null;
        this.sidebar.setActive(null);
        this.actionArea.clearIcons();
        this.editor.metaEl.textContent = 'Requesting new note...'; // Indicate pending action

        // Send addNote command to backend
        websocketClient.sendRequest('addNote', newNoteData)
            .then(response => {
                console.log('Backend acknowledged new note creation:', response);
                Notifier.info("New note creation requested.");
                // AddedEvent from backend will add the note to local state and select it
            })
            .catch(err => {
                console.error('Failed to send new note command:', err);
                Notifier.error("Failed to create new note.");
                this.editor.metaEl.textContent = 'Failed to create new note. Select or create a note.'; // Restore default message
                // If creation fails, the UI remains cleared, waiting for user action
            });
    }

    updateNotePriority(id, delta) {
        const n = this.notes.find(n => n.id === id);
        if (n) {
            const oldPriority = n.priority || 0;
            const newPriority = oldPriority + delta;
            // n.priority = newPriority; // Update local state immediately - now done by event
            // n.updated = Date.now(); // Update local timestamp - now done by event

            // this.sidebar.updateNote(n); // Update sidebar preview/meta immediately - now done by event
            // if (id === this.currentId) {
            //     this.editor.updateMeta(n); // Update editor meta immediately - now done by event
            // }
            // this.sortAndFilter(); // Re-sort/filter as priority/updated changed - now done by event

            // Send updateNote command with only priority changed
            websocketClient.sendRequest('updateNote', {
                noteId: id,
                priority: newPriority
            }).then(response => {
                console.log(`Backend acknowledged priority update for ${id}:`, response);
                // NoteUpdatedEvent from backend will provide the definitive state
            }).catch(err => {
                console.error(`Failed to send priority update for ${id}:`, err);
                Notifier.error(`Failed to update priority for "${n.title || 'Untitled'}".`);
                // Error handling: Could revert local state
                // Reverting local state is tricky with async updates
            });

            Notifier.info(`Priority update requested for "${n.title || 'Untitled'}"`);
        } else {
            console.warn(`Attempted to update priority for unknown note ID: ${id}`);
            Notifier.warning(`Cannot update priority for unknown note.`);
        }
    }

    handleMenu(action) {
        this.saveCurrentNote(); // Save current note before performing menu action
        const n = this.currentId ? this.notes.find(n => n.id === this.currentId) : null;

        switch (action) {
            case 'undo':
                if (this.currentId && this.editor.contentEl.isContentEditable) {
                    document.execCommand('undo');
                    // Note: Undo/Redo on contenteditable doesn't trigger 'input',
                    // so manual save might be needed or rely on next auto-save.
                    // For simplicity, we'll rely on the next auto-save or manual save.
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
                    // Use sendRequest for cloneNote command
                    websocketClient.sendRequest('cloneNote', {noteId: n.id})
                        .then(response => {
                            console.log('Backend acknowledged note clone:', response);
                            Notifier.info('Note cloning requested.');
                            // AddedEvent from backend will handle adding the cloned note
                        })
                        .catch(err => {
                            console.error('Failed to send clone note command:', err);
                            Notifier.error('Failed to clone note.');
                        });
                } else Notifier.warning('Select note to clone.');
                break;
            case 'insert':
                if (this.currentId && this.editor.contentEl.isContentEditable) {
                    this.editor.insertField();
                    // Inserting field modifies content, which triggers 'input' and auto-save
                } else {
                    Notifier.warning('Select a note and focus the editor to insert a field.');
                }
                break;
            case 'publish':
                this.updateNoteState(n, 'ACTIVE'); // Assuming 'Published' maps to 'ACTIVE' status
                break;
            case 'set-private':
                this.updateNoteState(n, 'IDLE'); // Assuming 'Private' maps to 'IDLE' status
                break;
            case 'enhance':
            case 'summary':
                if (n) {
                    // Send runTool command to backend
                    websocketClient.sendRequest('runTool', {name: action, parameters: { note_id: n.id }});
                } else Notifier.warning(`Select note to run ${action} tool.`);
                break;
            case 'delete':
                if (n && confirm(`Delete "${n.title || 'Untitled Note'}"?`)) {
                    // Send deleteNote command to backend
                    websocketClient.sendRequest('deleteNote', {noteId: n.id})
                        .then(response => {
                            console.log('Backend acknowledged note deletion:', response);
                            Notifier.success('Note deletion requested.');
                            // NoteDeletedEvent from backend will handle removing the note from UI
                        })
                        .catch(err => {
                            console.error('Failed to send delete note command:', err);
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
            default:
                console.warn(`Unknown menu action: ${action}`);
        }
    }

    updateNoteState(note, newState) {
        if (note) {
            const oldState = note.state;
            // note.state = newState; // Update local state immediately - now done by event
            // note.updated = Date.now(); // Update local timestamp - now done by event

            // this.editor.updateMeta(note); // Update editor meta immediately - now done by event
            // this.sidebar.updateNote(note); // Update sidebar preview/meta immediately - now done by event
            // this.sortAndFilter(); // Re-sort/filter as state/updated changed - now done by event

            // Send updateNote command with only state changed
            websocketClient.sendRequest('updateNote', {
                noteId: note.id,
                state: newState
            }).then(response => {
                console.log(`Backend acknowledged state update for ${note.id}:`, response);
                // NoteStatusEvent from backend will provide the definitive state
            }).catch(err => {
                console.error(`Failed to send state update for ${note.id}:`, err);
                Notifier.error(`Failed to set note state to ${newState}.`);
                // Error handling: Revert local state on failure
                // Reverting local state is tricky with async updates
            });

            Notifier.info(`Note "${note.title || 'Untitled'}" set to ${newState}.`);
        } else Notifier.warning(`Select a note to set ${newState}.`);
    }

    updateSettings(settings) {
        this.settings = settings;

        // Send updateSettings command to backend
        websocketClient.sendRequest('updateSettings', {settings: settings})
            .then(response => {
                console.log('Backend acknowledged settings update:', response);
                // NoteUpdatedEvent for config note will update UI if needed
            })
            .catch(err => {
                console.error('Failed to send settings update command:', err);
                Notifier.error('Failed to save settings.');
            });
    }

    showDock(d) {
        this.actionArea.showDock(d);
    }

    sendCommand(commandName, parameters = {}) {
        // This method is used by ActionArea dock buttons
        // These are generic commands, map them to sendRequest
        websocketClient.sendRequest(commandName, parameters)
            .then(response => {
                console.log(`Backend acknowledged command '${commandName}':`, response);
                Notifier.info(`Command '${commandName}' requested.`);
                // Backend events/responses related to the command will update UI
            })
            .catch(err => {
                console.error(`Failed to run command '${commandName}'.`);
                Notifier.error(`Failed to run command '${commandName}'.`);
            });
    }

    cancelDialogue(dialogueId) {
        // This method is used by DialogueManager
        // Map to sendRequest with cancelDialogue command
        websocketClient.sendRequest('cancelDialogue', {dialogueId: dialogueId})
            .then(response => console.log(`Backend acknowledged dialogue cancellation ${dialogueId}:`, response))
            .catch(err => console.error(`Failed to send cancelDialogue command ${dialogueId}:`, err));
    }
}

document.addEventListener('DOMContentLoaded', () => {
    new App('#app-container');
});
