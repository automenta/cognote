import { websocketClient } from '../client.js'; // Import the WebSocket client

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
    sanitizeHTML: (s) => Object.assign(document.createElement('div'), {textContent: s}).innerHTML,
    // generateId is no longer needed client-side for new notes, backend assigns ID
    extractText: (html) => Object.assign(document.createElement('div'), {innerHTML: html}).textContent || ""
};

const Notifier = {
    init() {
        toastr.options = {
            closeButton: !0,
            progressBar: !0,
            positionClass: "toast-bottom-right",
            preventDuplicates: !0,
            timeOut: "3500",
            extendedTimeOut: "1500",
            showEasing: "swing",
            hideEasing: "linear",
            showMethod: "fadeIn",
            hideMethod: "fadeOut"
        };
    },
    success: (m) => toastr.success(m), info: (m) => toastr.info(m),
    warning: (m) => toastr.warning(m), error: (m) => toastr.error(m)
};

// StorageService is removed, data is managed via WebSocket

class Component {
    constructor(app, el) {
        this.app = app;
        this.$el = $(el);
    }

    render() {
    }

    bindEvents() {
    }

    destroy() {
        this.$el?.empty().off();
    }
}

class NoteItem extends Component {
    constructor(app, note) {
        super(app);
        this.note = note;
        this.$el = this.createMarkup();
        this.bindEvents();
    }

    createMarkup() {
        const preview = Utils.extractText(this.note.content).substring(0, 80) + (Utils.extractText(this.note.content).length > 80 ? '...' : '');
        return $(`
              <div class="note-item" data-id="${this.note.id}" style="border-left-color: ${this.note.color || '#ccc'};">
                  <h4>${Utils.sanitizeHTML(this.note.title || 'Untitled Note')}</h4>
                  <div class="meta">Upd ${Utils.timeAgo(this.note.updated)} | P:${this.note.priority || 0} | ${this.note.state}</div>
                  <div class="preview">${Utils.sanitizeHTML(preview)}</div>
                  <div class="note-priority-controls">
                      <button class="priority-inc" title="Increase Priority">+</button>
                      <button class="priority-dec" title="Decrease Priority">-</button>
                  </div>
              </div>`);
    }

    bindEvents() {
        this.$el.on('click', () => this.app.selectNote(this.note.id));
        this.$el.find('.priority-inc').on('click', (e) => {
            e.stopPropagation();
            this.app.updateNotePriority(this.note.id, 1);
        });
        this.$el.find('.priority-dec').on('click', (e) => {
            e.stopPropagation();
            this.app.updateNotePriority(this.note.id, -1);
        });
    }

    update(note) {
        this.note = note;
        const preview = Utils.extractText(note.content).substring(0, 80) + (Utils.extractText(note.content).length > 80 ? '...' : '');
        this.$el.find('h4').text(Utils.sanitizeHTML(note.title || 'Untitled Note'));
        this.$el.find('.meta').text(`Cr: ${new Date(note.created).toLocaleDateString()} | Upd: ${Utils.timeAgo(note.updated)} | St: ${note.state} | P: ${note.priority || 0}`);
        this.$el.find('.preview').text(Utils.sanitizeHTML(preview));
        this.$el.css('border-left-color', note.color || '#ccc');
    }

    setActive(isActive) {
        this.$el.toggleClass('selected', isActive);
    }
}

class NoteList extends Component {
    constructor(app, el) {
        super(app, el);
        this.items = {};
    }

    render(notes, currentId) {
        this.$el.empty();
        this.items = {};
        if (notes?.length) {
            notes.forEach(n => {
                const i = new NoteItem(this.app, n);
                this.items[n.id] = i;
                this.$el.append(i.$el);
                if (n.id === currentId) i.setActive(!0);
            });
        } else {
            this.$el.html('<p style="text-align:center;color:var(--text-muted);margin-top:20px;">No notes.</p>');
        }
    }

    updateItem(note) {
        this.items[note.id]?.update(note);
    }

    removeItem(noteId) {
        if (this.items[noteId]) {
            this.items[noteId].$el.remove();
            delete this.items[noteId];
        }
    }

    setActive(id) {
        Object.values(this.items).forEach(i => i.setActive(!1));
        if (id && this.items[id]) {
            this.items[id].setActive(!0);
            const $i = this.items[id].$el, c = this.$el[0], iT = $i.position().top + c.scrollTop,
                iB = iT + $i.outerHeight();
            if (iT < c.scrollTop || iB > c.scrollTop + c.clientHeight) c.scrollTop = iT - c.clientHeight / 2 + $i.outerHeight() / 2;
        }
    }
}

class Sidebar extends Component {
    constructor(app, el) {
        super(app, el);
        this.noteList = null;
        this.render();
        this.bindEvents();
    }

    render() {
        this.$el.html(` <div class="sidebar-controls"> <button id="add-note-btn" title="Create New Note">+</button> <select id="sort-notes"><option value="priority">Sort: Priority</option><option value="updated">Sort: Updated</option><option value="created">Sort: Created</option><option value="title">Sort: Title</option></select> </div> <input type="search" id="search-notes" placeholder="Filter/Search Notes..."> <div class="note-list-container" id="note-list"></div>`);
        this.noteList = new NoteList(this.app, '#note-list');
    }

    bindEvents() {
        this.$el.on('click', '#add-note-btn', () => this.app.createNewNote());
        this.$el.on('change', '#sort-notes', () => this.app.sortAndFilter());
        this.$el.on('input', '#search-notes', Utils.debounce(() => this.app.sortAndFilter(), 300));
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
        return this.$el.find('#sort-notes').val();
    }

    getSearch() {
        return this.$el.find('#search-notes').val();
    }
}

class Editor extends Component {
    constructor(app, el) {
        super(app, el);
        this.save = Utils.debounce(() => this.app.saveCurrentNote(), 1e3);
        this.render();
        this.bindEvents();
    }

    render() {
        this.$el.html(` <div class="editor-header"> <input type="text" class="editor-title" id="note-title" placeholder="Note Title"> <div class="editor-meta" id="note-meta">Select or create a note</div> <span id="save-status" class="save-status"></span> </div> <div class="editor-toolbar"> <button data-command="bold" class="icon" title="Bold">B</button> <button data-command="italic" class="icon" title="Italic">I</button> <button data-command="underline" class="icon" title="Underline">U</button> <button data-command="insertUnorderedList" class="icon" title="Bullet List">UL</button> <button data-command="insertOrderedList" class="icon" title="Numbered List">OL</button> <button data-action="insert-field" class="icon" title="Insert Field">+</button> </div> <div class="editor-content-wrapper"> <div class="editor-content" id="note-content" contenteditable="false" placeholder="Start writing..."></div> </div>`);
        this.$title = this.$el.find('#note-title');
        this.$meta = this.$el.find('#note-meta');
        this.$content = this.$el.find('#note-content');
        this.$saveStatus = this.$el.find('#save-status');
    }

    bindEvents() {
        this.$el.find('.editor-toolbar button[data-command]').on('click', (e) => {
            document.execCommand($(e.currentTarget).data('command'), !1, null);
            this.$content.trigger('input').focus();
        });
        this.$el.find('.editor-toolbar button[data-action="insert-field"]').on('click', () => this.insertField());
        this.$content.on('input', () => this.save());
        this.$title.on('input', () => this.save());
        this.$content.on('keydown', (e) => {
            if (e.key === 'Tab' && !e.shiftKey && this.$content.is(':focus')) {
                // Basic indent for contenteditable
                e.preventDefault();
                document.execCommand('insertHTML', false, '&#009;'); // Insert tab character
            } else if (e.key === 'Tab' && e.shiftKey && this.$content.is(':focus')) {
                 // Basic outdent - execCommand('outdent') only works for list items
                 // More complex logic needed for general text outdent
                 // For now, just prevent default tab behavior
                 e.preventDefault();
            }
        });
    }

    load(n) {
        if (n) {
            this.$title.val(n.title).prop('disabled', !1);
            this.$content.html(n.content).prop('contenteditable', !0); // Set contenteditable to true when loading a note
            this.updateMeta(n);
            this.$saveStatus.text(''); // Clear status on load
        } else this.clear();
    }

    updateMeta(n) {
        this.$meta.text(`Cr: ${new Date(n.created).toLocaleDateString()} | Upd: ${Utils.timeAgo(n.updated)} | St: ${n.state} | P: ${n.priority || 0}`);
    }

    clear() {
        this.$title.val('').prop('disabled', !0);
        this.$content.html('').prop('contenteditable', !1); // Set contenteditable to false when clearing
        this.$meta.text('Select or create a note');
        this.$saveStatus.text(''); // Clear status
    }

    getData() {
        return {title: this.$title.val(), content: this.$content.html()};
    }

    focusTitle() {
        this.$title.focus();
    }

    insertField() {
        const n = prompt("Field name (e.g., Cost):");
        if (n) {
            const v = prompt(`Value for ${n} (e.g., < $20):`),
                h = ` <span class='field'><span class='field-label'>${Utils.sanitizeHTML(n)}:</span><span class='field-value' contenteditable='true'>${Utils.sanitizeHTML(v || '')}</span></span> `;
            document.execCommand('insertHTML', !1, h);
            this.$content.trigger('input').focus();
        }
    }

    setSaveStatus(status) {
        this.$saveStatus.text(status);
        if (status === 'Saved') {
            setTimeout(() => this.$saveStatus.text(''), 2000); // Clear 'Saved' message after 2s
        }
    }
}

class MenuBar extends Component {
    constructor(app, el) {
        super(app, el);
        this.render();
        this.bindEvents();
    }

    render() {
        this.$el.html(` <div class="group"> <span data-action="undo" title="Undo last action">Undo</span> <span data-action="redo" title="Redo last action">Redo</span> <span data-action="clone" title="Create a copy of the current note">Clone</span> </div> <div class="group"> <span data-action="insert" title="Insert structured data field">Insert Field</span> </div> <div class="group"> <span data-action="publish" title="Publish note to P2P network">Publish</span> <span data-action="set-private" title="Make note Private">Set Private</span> </div> <div class="group"> <span>Tools:</span> <span data-action="enhance" title="Apply LLM enhancement (Stub)">Enhance</span> <span data-action="summary" title="Apply LLM summarization (Stub)">Summary</span> </div> <div class="group"> <span data-action="delete" title="Delete current note">Delete</span> <span data-action="view-source" title="View raw note data">View source</span> <span data-action="settings" title="Open application settings">Settings</span> </div>`);
    }

    bindEvents() {
        this.$el.on('click', 'span[data-action]', (e) => this.app.handleMenu($(e.currentTarget).data('action')));
    }
}

class ActionIcon extends Component {
    constructor(app, parent, data) {
        super(app);
        this.parent = parent;
        this.data = data;
        this.$el = this.createMarkup();
        this.bindEvents();
        this.parent.append(this.$el);
    }

    createMarkup() {
        return $(`<div class="action-icon ${this.data.urgent ? 'urgent' : ''}" data-type="${this.data.type}" title="${this.data.type}: ${this.data.details}"> ${this.data.symbol || this.data.type[0]} <span class="hide-btn" title="Hide this icon">x</span> <span class="tooltip">${this.data.type}: ${this.data.details}</span> </div>`);
    }

    bindEvents() {
        this.$el.on('click', (e) => {
            $(e.target).hasClass('hide-btn') ? this.hide() : this.app.showDock(this.data);
        });
    }

    hide() {
        this.$el.fadeOut(300, () => this.$el.remove()); /* Notify app for persistence if needed */
    }
}

class ActionArea extends Component {
    constructor(app, el) {
        super(app, el);
        this.icons = [];
        this.render();
        this.bindEvents();
    }

    render() {
        this.$el.html(` <div class="action-icons-container" id="action-icons"></div> <div class="action-area-description"> Action Dock: Matches, Questions, Alerts etc.</div> <div class="action-dock" id="action-dock"> <div id="action-dock-content"></div> </div>`);
        this.$icons = this.$el.find('#action-icons');
        this.$dock = this.$el.find('#action-dock');
        this.$dockContent = this.$el.find('#action-dock-content');
    }

    bindEvents() {
        $(document).on('click', (e) => {
            if (!$(e.target).closest('.action-icon, .action-dock').length && this.$dock.hasClass('visible')) this.hideDock();
        });
    }

    renderIcons(note) {
        this.clearIcons();
        if (!note) return;
        // Client-side text analysis for action icons (stub)
        const txt = Utils.extractText(note.content).toLowerCase(), toAdd = [];
        if (txt.includes('cost') || txt.includes('$') || txt.includes('budget')) toAdd.push({
            type: 'Match',
            details: 'Potential budget match found.',
            symbol: 'M'
        });
        if (txt.includes('?')) toAdd.push({type: 'Question', details: 'Question detected in note.', symbol: '?'});
        if (txt.includes('idea') || txt.includes('feature') || txt.includes('suggest')) toAdd.push({
            type: 'Suggestion',
            details: 'Idea or suggestion found.',
            symbol: 'S'
        });
        if (txt.includes('error') || txt.includes('fix') || txt.includes('issue')) toAdd.push({
            type: 'Alert',
            details: 'Potential issue mentioned.',
            symbol: '!',
            urgent: !0
        });
        if (txt.includes('link') || txt.includes('related')) toAdd.push({
            type: 'Related',
            details: 'Related item or link detected.',
            symbol: 'L'
        });
        toAdd.forEach(d => this.icons.push(new ActionIcon(this.app, this.$icons, d)));
    }

    clearIcons() {
        this.icons.forEach(i => i.$el.remove());
        this.icons = [];
        this.hideDock();
    }

    showDock(data) {
        // Stub data for action dock content
        let h = `<h5>${data.type} Details</h5><ul><li>${Utils.sanitizeHTML(data.details)}</li>`;
        switch (data.type) {
            case 'Match':
                h += `<li>Related: Product XYZ ($18)</li><li>Action: Check competitor prices</li>`;
                break;
            case 'Question':
                h += `<li>Context: Needs clarification.</li><li>Action: Ask team member.</li>`;
                break;
            case 'Suggestion':
                h += `<li>Related: Similar concept article</li><li>Action: Add to backlog</li>`;
                break;
            case 'Alert':
                h += `<li>Context: Urgent review required.</li><li>Action: Review constraints.</li>`;
                break;
            case 'Related':
                h += `<li>Link: example.com</li><li>Action: Follow up</li>`;
                break;
        }
        h += `</ul>`;
        this.$dockContent.html(h);
        this.$dock.addClass('visible');
    }

    hideDock() {
        this.$dock.removeClass('visible');
    }
}

class SettingsModal extends Component {
    constructor(app, el) {
        super(app, el);
        this.render();
        this.bindEvents();
        // Settings are loaded via App.handleInitialState
    }

    render() {
        this.$el.html(` <div class="modal-content"> <div class="modal-header"> <h4>Settings</h4> <span class="modal-close" id="settings-modal-close" title="Close">×</span> </div> <div class="modal-body"> <ul class="modal-tabs" id="settings-tabs"> <li class="active" data-tab="general">General</li> <li data-tab="network">Network</li> <li data-tab="ontology">Ontology</li> <li data-tab="llm">LLM Tools</li> <li data-tab="appearance">Appearance</li> </ul> <div class="modal-tab-content active" id="tab-general"> <h5>General</h5> <label for="setting-default-state">Default Note State:</label> <select id="setting-default-state"><option>Private</option><option>Published</option></select> </div> <div class="modal-tab-content" id="tab-network"> <h5>Network (P2P)</h5> <label for="setting-nostr-relays">Nostr Relays (one per line):</label> <textarea id="setting-nostr-relays" rows="4"></textarea> <p style="font-size:.9em;color:var(--text-muted)">Configure relays for P2P publishing.</p> </div> <div class="modal-tab-content" id="tab-ontology"> <h5>Ontology</h5> <button disabled>Import (Stub)</button> <button disabled>Export (Stub)</button> <p style="margin-top:15px;font-size:.9em;color:var(--text-muted)">Manage types/fields (future).</p> </div> <div class="modal-tab-content" id="tab-llm"> <h5>LLM Tools</h5> <label for="setting-llm-provider">Provider:</label> <select id="setting-llm-provider"><option>OpenAI (Stub)</option><option>Local (Stub)</option></select> <label for="setting-llm-apikey">API Key:</label> <input type="password" id="setting-llm-apikey"> </div> <div class="modal-tab-content" id="tab-appearance"> <h5>Appearance</h5> <label for="setting-theme">Theme:</label> <select id="setting-theme"><option>Light</option><option disabled>Dark (Stub)</option></select> </div> </div> <div class="modal-footer"> <button id="settings-cancel-btn">Cancel</button> <button id="settings-save-btn">Save</button> </div> </div>`);
    }

    bindEvents() {
        this.$el.on('click', '#settings-modal-close, #settings-cancel-btn', () => this.hide());
        this.$el.on('click', '#settings-save-btn', () => this.save());
        this.$el.on('click', '.modal-tabs li', (e) => this.switchTab(e));
        this.$el.on('click', (e) => {
            if (e.target === this.$el[0]) this.hide();
        });
    }

    switchTab(e) {
        const $t = $(e.currentTarget), id = $t.data('tab');
        this.$el.find('.modal-tabs li, .modal-tab-content').removeClass('active');
        $t.addClass('active');
        this.$el.find(`#tab-${id}`).addClass('active');
    }

    loadSettings(settings) {
        // Load settings from the object provided by the App (from initial state)
        this.$el.find('#setting-default-state').val(settings.defaultState || 'Private');
        this.$el.find('#setting-nostr-relays').val(settings.nostrRelays || '');
        this.$el.find('#setting-llm-provider').val(settings.llmProvider || 'OpenAI (Stub)');
        this.$el.find('#setting-llm-apikey').val(settings.llmApiKey || '');
        this.$el.find('#setting-theme').val(settings.theme || 'Light');
    }

    save() {
        const s = {
            defaultState: this.$el.find('#setting-default-state').val(),
            nostrRelays: this.$el.find('#setting-nostr-relays').val(),
            llmProvider: this.$el.find('#setting-llm-provider').val(),
            llmApiKey: this.$el.find('#setting-llm-apikey').val(),
            theme: this.$el.find('#setting-theme').val()
        };
        this.app.updateSettings(s); // Delegate saving to the App
        Notifier.success('Settings saved.');
        this.hide();
    }

    show() {
        this.$el.addClass('visible');
    }

    hide() {
        this.$el.removeClass('visible');
    }
}

class DialogueManager extends Component {
    constructor(app, el) {
        super(app, el);
        this.$modal = this.$el;
        this.$prompt = this.$el.find('#dialogue-prompt');
        this.$input = this.$el.find('#dialogue-input');
        this.$sendButton = this.$el.find('#dialogue-send-button');
        this.$cancelButton = this.$el.find('#dialogue-cancel-button');
        this.$closeButton = this.$el.find('#dialogue-modal-close');
        this.currentDialogueId = null;

        this.bindEvents();
    }

    bindEvents() {
        this.$sendButton.on('click', () => this.sendResponse());
        this.$cancelButton.on('click', () => this.cancelDialogue());
        this.$closeButton.on('click', () => this.cancelDialogue());
        this.$input.on('keypress', (e) => {
            if (e.key === 'Enter') {
                e.preventDefault();
                this.sendResponse();
            }
        });
        this.$modal.on('click', (e) => {
            if (e.target === this.$modal[0]) {
                this.cancelDialogue();
            }
        });
    }

    show(dialogueRequest) {
        if (this.currentDialogueId) {
            console.warn(`Received new dialogue request (${dialogueRequest.dialogueId}) while another is active (${this.currentDialogueId}). Cancelling the old one.`);
            this.cancelDialogue(); // Cancel previous one
        }

        // For now, only handle text_input requests
        if (dialogueRequest.requestType !== 'text_input') {
            console.warn(`Received unsupported dialogue request type: ${dialogueRequest.requestType}. Cancelling.`);
            this.app.cancelDialogue(dialogueRequest.dialogueId); // Notify backend we can't handle it
            return;
        }

        this.currentDialogueId = dialogueRequest.dialogueId;
        this.$prompt.text(dialogueRequest.prompt);
        this.$input.val(''); // Clear previous input
        this.$modal.addClass('visible');
        this.$input.focus();
    }

    hide() {
        this.$modal.removeClass('visible');
        this.currentDialogueId = null;
        this.$prompt.text('');
        this.$input.val('');
    }

    sendResponse() {
        if (!this.currentDialogueId) return;

        const responseText = this.$input.val().trim();
        // Allow empty response for now, backend can validate if needed

        console.log(`Sending dialogue response for ${this.currentDialogueId}: "${responseText}"`);
        websocketClient.sendDialogueResponse(this.currentDialogueId, { text: responseText }) // Assuming text_input type
            .then(response => {
                console.log('Dialogue response acknowledged by backend:', response);
                // Backend response is handled by generic 'response' listener in client.js
                // No need for specific UI feedback here unless the response indicates an error
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
        this.app.cancelDialogue(this.currentDialogueId); // Delegate cancellation command to App

        this.hide();
    }

    // Called by App when WS disconnects
    handleWsDisconnected() {
        if (this.currentDialogueId) {
            console.warn('WS disconnected, cancelling active dialogue.');
            this.hide(); // Just hide, don't send cancel command as WS is down
            Notifier.warning('Dialogue cancelled due to disconnection.');
        }
    }
}


class App {
    constructor(sel) {
        this.$cont = $(sel);
        this.notes = [];
        this.currentId = null;
        this.settings = {}; // Will be populated from WebSocket initial state
        this.initComps();
        this.bindWebSocketEvents(); // Bind events before connecting
        Notifier.init();
        console.log("Netention Note App Ready");

        // Connect and request initial state
        if (websocketClient.isConnected) {
             console.log('WS already connected, requesting initial state...');
             websocketClient.sendInitialStateRequest()
                .catch(err => {
                    console.error('Failed to request initial state on ready WS:', err);
                    Notifier.error('Failed to load initial state.');
                });
        } else {
             console.log('WS not connected, waiting for connection...');
             // The 'connected' event listener will trigger the initial state request
        }
    }

    initComps() {
        this.$cont.html(` <aside class="sidebar" id="sidebar-container"></aside> <main class="main-content"> <nav class="menu-bar" id="menu-bar-container"></nav> <div class="editor-area" id="editor-container"></div> <div class="action-area" id="action-area-container"></div> </main> <div id="settings-modal" class="modal"></div>`);
        this.sidebar = new Sidebar(this, '#sidebar-container');
        this.editor = new Editor(this, '#editor-container');
        this.menuBar = new MenuBar(this, '#menu-bar-container');
        this.actionArea = new ActionArea(this, '#action-area-container');
        this.settingsModal = new SettingsModal(this, '#settings-modal');
        this.dialogueManager = new DialogueManager(this, '#dialogue-modal'); // Initialize DialogueManager
    }

    bindWebSocketEvents() {
        websocketClient.on('connected', () => {
            console.log('WS Connected');
            Notifier.info('Connected to backend.');
            // Request initial state upon connection
            websocketClient.sendInitialStateRequest()
                .catch(err => {
                    console.error('Failed to request initial state:', err);
                    Notifier.error('Failed to load initial state.');
                });
        });

        websocketClient.on('disconnected', (e) => {
            console.warn('WS Disconnected', e);
            Notifier.error('Disconnected from backend.');
            // Clear UI or show a disconnected state
            this.notes = [];
            this.currentId = null;
            this.sortAndFilter();
            this.editor.clear();
            this.actionArea.clearIcons();
            this.editor.$meta.text('Disconnected. Attempting to reconnect...'); // Update meta text
            this.dialogueManager.handleWsDisconnected(); // Notify dialogue manager
        });

        websocketClient.on('reconnectFailed', () => {
            Notifier.error('Failed to connect to backend.');
            this.editor.$meta.text('Connection failed.'); // Final state after failed attempts
        });

        websocketClient.on('error', (e) => {
            console.error('WS Error', e);
            // Generic error handler, specific errors might be in response payloads
            Notifier.error('WebSocket error occurred.');
        });

        websocketClient.on('initialState', (state) => this.handleInitialState(state));
        websocketClient.on('NoteStatusEvent', (event) => this.handleNoteStatusEvent(event));
        websocketClient.on('NoteUpdatedEvent', (event) => this.handleNoteUpdatedEvent(event));
        websocketClient.on('NoteDeletedEvent', (event) => this.handleNoteDeletedEvent(event));
        websocketClient.on('NoteAddedEvent', (event) => this.handleNoteAddedEvent(event));
        websocketClient.on('dialogueRequest', (request) => this.dialogueManager.show(request)); // Handle dialogue requests
        websocketClient.on('response', (response) => {
             // Generic response handler - useful for debugging or unexpected responses
             console.log('Received generic response:', response);
             // Specific response handling (like save status) is done in the promise chain
        });
        websocketClient.on('event', (event) => {
             // Generic event handler - useful for debugging or unexpected events
             console.log('Received generic event:', event);
        });
    }

    handleInitialState(state) {
        console.log('Received initial state:', state);
        if (state?.notes) {
            // Assuming state.notes is an array of note objects
            this.notes = state.notes;
            // Assuming state.configuration contains settings
            this.settings = state.configuration || {}; // Use configuration from snapshot
            this.settingsModal.loadSettings(this.settings); // Load settings into modal

            this.sortAndFilter();
            const sorted = this.getSortedFiltered();
            if (sorted.length) {
                // Select the first note or the previously selected one if it exists
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
            // Backend should ideally provide updated timestamp, using client time for responsiveness
            note.updated = event.timestamp || Date.now();
            this.sidebar.updateNote(note);
            if (note.id === this.currentId) {
                this.editor.updateMeta(note);
            }
            // Re-sort/filter in case state affects sorting (it doesn't currently)
            this.sortAndFilter();
        } else {
            console.warn('NoteStatusEvent for unknown note ID:', event.noteId);
            // If a note status event arrives for a note not in our list,
            // we might need a full refresh or a specific NoteAddedEvent.
            // For now, log warning and request full state.
            websocketClient.sendInitialStateRequest()
                 .catch(err => console.error('Failed to request initial state after unknown NoteStatusEvent:', err));
        }
    }

    handleNoteUpdatedEvent(event) {
         console.log('Received NoteUpdatedEvent:', event);
         const noteIndex = this.notes.findIndex(n => n.id === event.noteId);
         if (noteIndex !== -1) {
             // Update the note object in the local array with data from the event
             // Assuming the event payload contains the full updated note object or relevant fields
             const updatedNoteData = event.updatedNote; // Assuming event.updatedNote contains the new data
             if (updatedNoteData) {
                 // Merge updated fields, preserving fields not in the event if necessary
                 Object.assign(this.notes[noteIndex], updatedNoteData);
                 // Use timestamp from event if available, otherwise client time for responsiveness
                 this.notes[noteIndex].updated = updatedNoteData.updated || Date.now();

                 this.sidebar.updateNote(this.notes[noteIndex]);
                 if (this.notes[noteIndex].id === this.currentId) {
                     // If the updated note is the currently selected one, reload the editor
                     this.editor.load(this.notes[noteIndex]); // Reloads content, title, meta
                 }
                 this.sortAndFilter(); // Re-sort/filter in case title/priority changed
                 this.actionArea.renderIcons(this.notes[noteIndex]); // Re-render action icons
                 // Notifier.info(`Note "${this.notes[noteIndex].title || 'Untitled'}" updated.`); // Avoid excessive notifications
             } else {
                 console.warn('NoteUpdatedEvent received without updated note data.');
             }
         } else {
             console.warn('NoteUpdatedEvent for unknown note ID:', event.noteId);
             // If an update arrives for a note not in our list, request a full state refresh
             // This might happen if the note was added/updated by another client/plugin
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
             this.notes.splice(noteIndex, 1); // Remove from local array
             this.sidebar.removeNote(noteId); // Remove from sidebar UI

             if (noteId === this.currentId) {
                 // If the deleted note was the current one, clear the editor
                 this.currentId = null;
                 this.editor.clear();
                 this.sidebar.setActive(null);
                 this.actionArea.clearIcons();
             }
             this.sortAndFilter(); // Update sidebar list display
             Notifier.success(`Note "${deletedNoteTitle}" deleted.`);
         } else {
             console.warn('NoteDeletedEvent for unknown note ID:', noteId);
             // If a delete event arrives for a note not in our list, request a full state refresh
             websocketClient.sendInitialStateRequest()
                 .catch(err => console.error('Failed to request initial state after unknown NoteDeletedEvent:', err));
         }
    }

    handleNoteAddedEvent(event) {
         console.log('Received NoteAddedEvent:', event);
         const newNote = event.newNote; // Assuming event.newNote contains the full new note object
         if (newNote && !this.notes.some(n => n.id === newNote.id)) {
             this.notes.unshift(newNote); // Add to the beginning of the local array
             this.sortAndFilter(); // Re-render sidebar list with the new note
             this.selectNote(newNote.id); // Select the newly added note
             this.editor.focusTitle(); // Focus the title field
             Notifier.success(`New note "${newNote.title || 'Untitled'}" created.`);
         } else if (newNote) {
             console.warn('NoteAddedEvent received for a note already in the list:', newNote.id);
             // Note already exists, maybe it was a clone we were waiting for?
             // Re-select it to ensure UI is consistent.
             this.selectNote(newNote.id);
         } else {
             console.warn('NoteAddedEvent received without new note data.');
         }
    }


    getSortedFiltered() {
        const sort = this.sidebar.getSort(), search = this.sidebar.getSearch().toLowerCase();
        let filt = this.notes.filter(n => (n.title || '').toLowerCase().includes(search) || Utils.extractText(n.content).toLowerCase().includes(search));
        return filt.sort((a, b) => {
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
        this.saveCurrentNote(); // Save current note before switching

        const n = this.notes.find(n => n.id === id);
        if (n) {
            this.currentId = id;
            this.editor.load(n);
            this.sidebar.setActive(id);
            this.actionArea.renderIcons(n);
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
        if (this.currentId === null) return !1;
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
                // Update local state immediately for responsiveness
                // Backend will send NoteUpdatedEvent with correct timestamp later
                n.updated = Date.now(); // Optimistic update

                this.editor.setSaveStatus('Saving...');

                // Send update action to backend
                websocketClient.sendUiAction('updateNote', {
                    noteId: n.id,
                    title: n.title,
                    content: n.content,
                    // Include other properties that might be edited via UI later
                    state: n.state,
                    priority: n.priority,
                    color: n.color
                }).then(response => {
                    console.log(`Backend acknowledged update for ${n.id}:`, response);
                    this.editor.setSaveStatus('Saved');
                    // Backend should send an event confirming the update (NoteUpdatedEvent)
                    // which will trigger editor.load() and sidebar.updateNote()
                }).catch(err => {
                    console.error(`Failed to send update for ${n.id}:`, err);
                    this.editor.setSaveStatus('Save Failed');
                    Notifier.error(`Failed to save note "${n.title || 'Untitled'}".`);
                    // Consider reverting local changes or showing error state
                });

                // Update UI based on local changes immediately (optimistic)
                this.sidebar.updateNote(n);
                this.editor.updateMeta(n);
                this.actionArea.renderIcons(n);
                console.log(`Attempted save for ${n.id}`);
                return true;
            } else {
                 this.editor.setSaveStatus(''); // Clear status if no changes
            }
        }
        return false;
    }

    createNewNote() {
        this.saveCurrentNote(); // Save current note before creating a new one

        const newNoteData = {
            // Backend will assign ID, created/updated timestamps, and potentially priority
            title: "Untitled Note",
            content: "",
            state: this.settings.defaultState || 'Private', // Use default state from settings
            color: `hsl(${Math.random() * 360}, 60%, 85%)` // Client-side color for now
        };

        // Send add action to backend
        websocketClient.sendUiAction('addNote', newNoteData)
            .then(response => {
                console.log('Backend acknowledged new note creation:', response);
                // Backend should send an event (like NoteAddedEvent)
                // which will trigger the UI update and selection of the new note.
                Notifier.info("New note creation requested.");
            })
            .catch(err => {
                console.error('Failed to send new note action:', err);
                Notifier.error("Failed to create new note.");
            });

        // Do NOT immediately add to local notes array or select.
        // Wait for backend confirmation via event/NoteAddedEvent.
    }

    updateNotePriority(id, delta) {
        const n = this.notes.find(n => n.id === id);
        if (n) {
            const newPriority = (n.priority || 0) + delta;
            // Update local state immediately
            n.priority = newPriority;
            n.updated = Date.now(); // Optimistic update

            // Send update action to backend
            websocketClient.sendUiAction('updateNotePriority', {
                noteId: id,
                priority: newPriority
            }).then(response => {
                console.log(`Backend acknowledged priority update for ${id}:`, response);
                // Backend should ideally send an event confirming the update (NoteUpdatedEvent)
            }).catch(err => {
                console.error(`Failed to send priority update for ${id}:`, err);
                Notifier.error(`Failed to update priority for "${n.title || 'Untitled'}".`);
                // Consider reverting local changes or showing error state
            });

            // Update UI based on local changes immediately
            this.sortAndFilter(); // Re-sort list
            if (id === this.currentId) {
                this.editor.updateMeta(n); // Update meta in editor
            }
            Notifier.info(`Priority update requested for "${n.title || 'Untitled'}"`);
        } else {
             console.warn(`Attempted to update priority for unknown note ID: ${id}`);
             Notifier.warning(`Cannot update priority for unknown note.`);
        }
    }

    handleMenu(action) {
        this.saveCurrentNote(); // Save current note before performing action
        const n = this.currentId ? this.notes.find(n => n.id === this.currentId) : null;

        switch (action) {
            case 'undo':
                if (this.currentId && this.editor.$content.is(':focus')) {
                    document.execCommand('undo'); // Client-side editor action
                } else {
                    Notifier.warning('Select a note and focus the editor to undo.');
                }
                break;
            case 'redo':
                 if (this.currentId && this.editor.$content.is(':focus')) {
                    document.execCommand('redo'); // Client-side editor action
                } else {
                    Notifier.warning('Select a note and focus the editor to redo.');
                }
                break;
            case 'clone':
                if (n) {
                    // Send clone action to backend
                    websocketClient.sendUiAction('cloneNote', { noteId: n.id })
                        .then(response => {
                            console.log('Backend acknowledged note clone:', response);
                            // Backend should send event (NoteAddedEvent) with the new cloned note
                            Notifier.info('Note cloning requested.');
                        })
                        .catch(err => {
                            console.error('Failed to send clone note action:', err);
                            Notifier.error('Failed to clone note.');
                        });
                    // Do NOT immediately add/select locally
                } else Notifier.warning('Select note to clone.');
                break;
            case 'insert':
                if (this.currentId && this.editor.$content.is(':focus')) {
                    this.editor.insertField(); // Client-side editor action
                } else {
                    Notifier.warning('Select a note and focus the editor to insert a field.');
                }
                break;
            case 'publish':
                this.updateNoteState(n, 'Published'); // Calls updateNoteState which sends UI action
                break;
            case 'set-private':
                this.updateNoteState(n, 'Private'); // Calls updateNoteState which sends UI action
                break;
            case 'enhance':
            case 'summary':
                if (n) {
                    // Send command to backend
                    websocketClient.sendCommand('runTool', { name: action, note_id: n.id })
                        .then(response => {
                            console.log(`Backend acknowledged tool '${action}':`, response);
                            Notifier.info(`Tool '${action}' requested.`);
                            // Response/result will come via generic response listener or specific event
                        })
                        .catch(err => {
                            console.error(`Failed to send tool '${action}' command:`, err);
                            Notifier.error(`Failed to run tool '${action}'.`);
                        });
                } else Notifier.warning(`Select note to run ${action} tool.`);
                break;
            case 'delete':
                if (n && confirm(`Delete "${n.title || 'Untitled Note'}"?`)) {
                    // Send delete action to backend
                    websocketClient.sendUiAction('deleteNote', { noteId: n.id })
                        .then(response => {
                            console.log('Backend acknowledged note deletion:', response);
                            // Backend should send event (NoteDeletedEvent) confirming deletion
                            // For responsiveness, remove locally immediately
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
            default:
                console.warn(`Unknown menu action: ${action}`);
        }
    }

    updateNoteState(note, newState) {
        if (note) {
            // Update local state immediately
            const oldState = note.state;
            note.state = newState;
            note.updated = Date.now(); // Optimistic update

            // Send update action to backend
            websocketClient.sendUiAction('updateNoteState', {
                noteId: note.id,
                state: newState
            }).then(response => {
                console.log(`Backend acknowledged state update for ${note.id}:`, response);
                // Backend should send NoteStatusEvent confirming the update
            }).catch(err => {
                console.error(`Failed to send state update for ${note.id}:`, err);
                Notifier.error(`Failed to set note state to ${newState}.`);
                // Revert local state on failure
                note.state = oldState;
                this.editor.updateMeta(note);
                this.sidebar.updateNote(note);
            });

            // Update UI based on local changes immediately (optimistic)
            this.editor.updateMeta(note);
            this.sidebar.updateNote(note);
            Notifier.success(`Note "${note.title || 'Untitled'}" set to ${newState}.`);
            if (newState === 'Published') console.log('Stub: Publishing note action sent...');
        } else Notifier.warning(`Select a note to set ${newState}.`);
    }

    updateSettings(settings) {
        // Update local settings
        this.settings = settings;

        // Send update action to backend
        websocketClient.sendUiAction('updateSettings', { settings: settings })
            .then(response => {
                console.log('Backend acknowledged settings update:', response);
                // Backend should ideally send an event confirming settings update
            })
            .catch(err => {
                console.error('Failed to send settings update action:', err);
                Notifier.error('Failed to save settings.');
            });
    }

    showDock(d) {
        this.actionArea.showDock(d);
    }

    // Method to cancel a dialogue request (called by DialogueManager)
    cancelDialogue(dialogueId) {
         websocketClient.sendCommand('cancelDialogue', { dialogueId: dialogueId })
             .then(response => console.log(`Backend acknowledged dialogue cancellation ${dialogueId}:`, response))
             .catch(err => console.error(`Failed to send cancelDialogue command ${dialogueId}:`, err));
    }
}

// Initialize the app when the DOM is ready
$(() => {
    const app = new App('#app-container');
    // No need to save on beforeunload anymore, saving is triggered by input debounce
    // $(window).on('beforeunload', () => app.saveCurrentNote());
});
