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
    generateId: () => `id_${Date.now()}_${Math.random().toString(36).substring(2, 11)}`,
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
const StorageService = {
    load: (key, def) => JSON.parse(localStorage.getItem(key) || JSON.stringify(def)),
    save: (key, data) => localStorage.setItem(key, JSON.stringify(data)),
    getNotes: () => StorageService.load('agenticNotesV2', [
        {
            id: Utils.generateId(),
            title: "Welcome Note",
            updated: Date.now() - 72e5,
            created: Date.now() - 864e5,
            priority: 0,
            state: 'Private',
            content: "This is an agentic note management platform.<br>Use the sidebar to manage notes, and the editor to modify content.<br>Try adding structured fields like <span class='field'><span class='field-label'>Status:</span><span class='field-value' contenteditable='true'>New</span></span>.",
            color: 'var(--accent-color-1)'
        },
        {
            id: Utils.generateId(),
            title: "Shopping List",
            updated: Date.now() - 6e4,
            created: Date.now() - 1728e5,
            priority: 2,
            state: 'Private',
            content: "<ul><li>Milk</li><li>Eggs</li><li>Cat food <span class='field'><span class='field-label'>Cost:</span><span class='field-value' contenteditable='true'>< $20</span></span> <span class='field'><span class='field-label'>Brand:</span><span class='field-value' contenteditable='true'>Any</span></span></li></ul>",
            color: 'var(--accent-color-2)'
        },
        {
            id: Utils.generateId(),
            title: "Ideas Brainstorm",
            updated: Date.now() - 2592e5,
            created: Date.now() - 3456e5,
            priority: 1,
            state: 'Private',
            content: "Feature ideas:<br>- P2P Sync (Nostr?)<br>- LLM Integration (Enhance/Summarize)<br>- Advanced Ontology Support",
            color: 'var(--accent-color-3)'
        }
    ]),
    saveNotes: (notes) => StorageService.save('agenticNotesV2', notes),
    getSettings: () => StorageService.load('agenticSettingsV2', {
        defaultState: 'Private',
        nostrRelays: "wss://relay.damus.io\nwss://relay.snort.social",
        llmProvider: 'OpenAI (Stub)',
        llmApiKey: '',
        theme: 'Light'
    }),
    saveSettings: (settings) => StorageService.save('agenticSettingsV2', settings)
};

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
        this.$el.find('.meta').text(`Upd ${Utils.timeAgo(note.updated)} | P:${note.priority || 0} | ${note.state}`);
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
        notes?.length ? notes.forEach(n => {
                const i = new NoteItem(this.app, n);
                this.items[n.id] = i;
                this.$el.append(i.$el);
                if (n.id === currentId) i.setActive(!0);
            })
            : this.$el.html('<p style="text-align:center;color:var(--text-muted);margin-top:20px;">No notes.</p>');
    }

    updateItem(note) {
        this.items[note.id]?.update(note);
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
        this.$el.html(` <div class="editor-header"> <input type="text" class="editor-title" id="note-title" placeholder="Note Title"> <div class="editor-meta" id="note-meta">Select or create a note</div> </div> <div class="editor-toolbar"> <button data-command="bold" class="icon" title="Bold">B</button> <button data-command="italic" class="icon" title="Italic">I</button> <button data-command="underline" class="icon" title="Underline">U</button> <button data-command="insertUnorderedList" class="icon" title="Bullet List">UL</button> <button data-command="insertOrderedList" class="icon" title="Numbered List">OL</button> <button data-action="insert-field" class="icon" title="Insert Field">+</button> </div> <div class="editor-content-wrapper"> <div class="editor-content" id="note-content" contenteditable="true" placeholder="Start writing..."></div> </div>`);
        this.$title = this.$el.find('#note-title');
        this.$meta = this.$el.find('#note-meta');
        this.$content = this.$el.find('#note-content');
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
            if (e.key === 'Tab') {
                e.preventDefault();
                document.execCommand(e.shiftKey ? 'outdent' : 'indent', !1, null);
            }
        });
    }

    load(n) {
        if (n) {
            this.$title.val(n.title).prop('disabled', !1);
            this.$content.html(n.content).prop('contenteditable', !0);
            this.updateMeta(n);
        } else this.clear();
    }

    updateMeta(n) {
        this.$meta.text(`Cr: ${new Date(n.created).toLocaleDateString()} | Upd: ${Utils.timeAgo(n.updated)} | St: ${n.state} | P: ${n.priority || 0}`);
    }

    clear() {
        this.$title.val('').prop('disabled', !0);
        this.$content.html('').prop('contenteditable', !1);
        this.$meta.text('Select or create a note');
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
        this.loadSettings();
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

    loadSettings() {
        const s = StorageService.getSettings();
        this.$el.find('#setting-default-state').val(s.defaultState);
        this.$el.find('#setting-nostr-relays').val(s.nostrRelays);
        this.$el.find('#setting-llm-provider').val(s.llmProvider);
        this.$el.find('#setting-llm-apikey').val(s.llmApiKey);
        this.$el.find('#setting-theme').val(s.theme);
    }

    save() {
        const s = {
            defaultState: this.$el.find('#setting-default-state').val(),
            nostrRelays: this.$el.find('#setting-nostr-relays').val(),
            llmProvider: this.$el.find('#setting-llm-provider').val(),
            llmApiKey: this.$el.find('#setting-llm-apikey').val(),
            theme: this.$el.find('#setting-theme').val()
        };
        StorageService.saveSettings(s);
        Notifier.success('Settings saved.');
        this.hide(); /* Apply settings if needed */
    }

    show() {
        this.$el.addClass('visible');
    }

    hide() {
        this.$el.removeClass('visible');
    }
}

class App {
    constructor(sel) {
        this.$cont = $(sel);
        this.notes = [];
        this.currentId = null;
        this.settings = StorageService.getSettings();
        this.initComps();
        this.loadData();
        Notifier.init();
        console.log("Netention Ready");
    }

    initComps() {
        this.$cont.html(` <aside class="sidebar" id="sidebar-container"></aside> <main class="main-content"> <nav class="menu-bar" id="menu-bar-container"></nav> <div class="editor-area" id="editor-container"></div> <div class="action-area" id="action-area-container"></div> </main> <div id="settings-modal" class="modal"></div>`);
        this.sidebar = new Sidebar(this, '#sidebar-container');
        this.editor = new Editor(this, '#editor-container');
        this.menuBar = new MenuBar(this, '#menu-bar-container');
        this.actionArea = new ActionArea(this, '#action-area-container');
        this.settingsModal = new SettingsModal(this, '#settings-modal');
    }

    loadData() {
        this.notes = StorageService.getNotes();
        if (!this.notes.length) StorageService.saveNotes(this.notes = StorageService.getNotes());
        this.sortAndFilter();
        const sorted = this.getSortedFiltered();
        if (sorted.length) this.selectNote(sorted[0].id); else {
            this.editor.clear();
            this.actionArea.clearIcons();
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
        this.saveCurrentNote();
        const n = this.notes.find(n => n.id === id);
        if (n) {
            this.currentId = id;
            this.editor.load(n);
            this.sidebar.setActive(id);
            this.actionArea.renderIcons(n);
        } else {
            console.error(`Note ${id} not found.`);
            this.currentId = null;
            this.editor.clear();
            this.sidebar.setActive(null);
            this.actionArea.clearIcons();
        }
    }

    saveCurrentNote() {
        if (this.currentId === null) return !1;
        const n = this.notes.find(n => n.id === this.currentId);
        if (n) {
            const d = this.editor.getData();
            let c = !1;
            if (n.title !== d.title) {
                n.title = d.title;
                c = !0;
            }
            if (n.content !== d.content) {
                n.content = d.content;
                c = !0;
            }
            if (c) {
                n.updated = Date.now();
                StorageService.saveNotes(this.notes);
                this.sidebar.updateNote(n);
                this.editor.updateMeta(n);
                this.actionArea.renderIcons(n); /* Logic engine hook (conceptual) */
                console.log(`Saved ${n.id}`);
                return !0;
            }
        }
        return !1;
    }

    createNewNote() {
        this.saveCurrentNote();
        const n = {
            id: Utils.generateId(),
            title: "Untitled Note",
            content: "",
            created: Date.now(),
            updated: Date.now(),
            priority: 0,
            state: this.settings.defaultState || 'Private',
            color: `hsl(${Math.random() * 360}, 60%, 85%)`
        };
        this.notes.unshift(n);
        StorageService.saveNotes(this.notes);
        this.sortAndFilter();
        this.selectNote(n.id);
        this.editor.focusTitle();
        Notifier.success("New note created");
    }

    updateNotePriority(id, delta) {
        const n = this.notes.find(n => n.id === id);
        if (n) {
            n.priority = (n.priority || 0) + delta;
            n.updated = Date.now();
            StorageService.saveNotes(this.notes);
            this.sortAndFilter();
            if (id === this.currentId) this.editor.updateMeta(n);
            Notifier.info(`Priority updated for "${n.title || 'Untitled'}"`);
        }
    }

    handleMenu(action) {
        this.saveCurrentNote();
        const n = this.currentId ? this.notes.find(n => n.id === this.currentId) : null;
        switch (action) {
            case 'undo':
                document.execCommand('undo');
                break;
            case 'redo':
                document.execCommand('redo');
                break;
            case 'clone':
                if (n) {
                    const c = {
                        ...n,
                        id: Utils.generateId(),
                        title: `${n.title || 'Untitled'} (Clone)`,
                        created: Date.now(),
                        updated: Date.now()
                    };
                    this.notes.unshift(c);
                    StorageService.saveNotes(this.notes);
                    this.sortAndFilter();
                    this.selectNote(c.id);
                    Notifier.success('Note cloned.');
                } else Notifier.warning('Select note to clone.');
                break;
            case 'insert':
                this.editor.insertField();
                break;
            case 'publish':
                this.updateNoteState(n, 'Published');
                break;
            case 'set-private':
                this.updateNoteState(n, 'Private');
                break;
            case 'enhance':
                Notifier.info('Stub: LLM Enhance');
                break;
            case 'summary':
                Notifier.info('Stub: LLM Summary');
                break;
            case 'delete':
                if (n && confirm(`Delete "${n.title || 'Untitled Note'}"?`)) {
                    this.notes = this.notes.filter(note => note.id !== n.id);
                    StorageService.saveNotes(this.notes);
                    this.currentId = null;
                    this.sortAndFilter();
                    this.editor.clear();
                    this.actionArea.clearIcons();
                    Notifier.success('Note deleted.');
                } else if (!n) Notifier.warning('Select note to delete.');
                break;
            case 'view-source':
                if (n) alert(`ID: ${n.id}\nTitle: ${n.title}\nState: ${n.state}\nPrio: ${n.priority}\n\n${n.content}`); else Notifier.warning('Select note to view source.');
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
            note.state = newState;
            note.updated = Date.now();
            StorageService.saveNotes(this.notes);
            this.editor.updateMeta(note);
            this.sidebar.updateNote(note);
            Notifier.success(`Note "${note.title || 'Untitled'}" set to ${newState}.`);
            if (newState === 'Published') console.log('Stub: Publishing note...', note);
        } else Notifier.warning(`Select a note to set ${newState}.`);
    }

    showDock(d) {
        this.actionArea.showDock(d);
    }
}

$(() => {
    const app = new App('#app-container');
    $(window).on('beforeunload', () => app.saveCurrentNote());
});