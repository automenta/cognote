// --- User Interface (Blessed TUI) ---
// Import blessed library
import blessed from 'blessed';


export class UserInterface {
    private screen: blessed.Widgets.Screen | null = null;
    private notesList: blessed.Widgets.ListElement | null = null;
    private activitiesList: blessed.Widgets.ListElement | null = null;
    private detailsBox: blessed.Widgets.BoxElement | null = null;
    private statusBar: blessed.Widgets.Log | null = null; // Use Log for easier appending
    private inputBox: blessed.Widgets.TextboxElement | null = null;

    private thoughtsMap = new Map<UUID, Thought>(); // Cache for quick lookup
    private notesData: { id: UUID; text: string; timestamp: number }[] = []; // Data for notes list
    private activitiesData: { id: UUID; text: string; timestamp: number, type: Type, status: Status, priority: number }[] = []; // Data for activities list

    private selectedNoteId: UUID | null = null;
    private selectedActivityId: UUID | null = null;
    private currentSortBy: 'timestamp' | 'type' | 'priority' | 'status' = 'timestamp';
    private isRendering = false;
    private renderIntervalId: NodeJS.Timeout | null = null;
    private lastLogMessage: string = "Initializing...";
    private pendingPromptsForDisplay: Array<{ id: UUID; prompt: string; options?: string[] }> = [];

    constructor(
        private readonly thoughtStore: ThoughtStore,
        private readonly userInteractionTool: UserInteractionTool,
        private readonly config: Config,
        private readonly agentId: UUID,
        // Need agentLoop reference to add new input thoughts
        private readonly agentLoop: AgentLoop
    ) {}

    start(): void {
        if (this.screen) return; // Already started

        this.screen = blessed.screen({
            smartCSR: true,
            title: `FlowMind Agent [${this.agentId.substring(0, 8)}]`,
            fullUnicode: true, // Enable better character support
        });

        this.setupLayout();
        this.setupEventHandlers();

        // Initial data load and render
        this.updateDataCache();
        this.updateNotesList();
        this.updateStatusBar(); // Show initial status
        this.screen.render();

        // Start periodic refresh
        this.renderIntervalId = setInterval(() => {
            this.refreshDataAndView();
        }, this.config.uiRefreshMillis);

        // Give focus to input box initially
        this.inputBox?.focus();
    }

    stop(): void {
        if (this.renderIntervalId) {
            clearInterval(this.renderIntervalId);
            this.renderIntervalId = null;
        }
        if (this.screen) {
            this.screen.destroy();
            this.screen = null;
            console.log("TUI stopped.");
        }
    }

    log(message: string): void {
        this.lastLogMessage = message;
        this.updateStatusBar(); // Update status bar immediately with new log
    }

    // --- Layout Setup ---
    private setupLayout(): void {
        if (!this.screen) return;

        const screen = this.screen;

        // Notes List (Left Pane)
        this.notesList = blessed.list({
            parent: screen,
            label: ' Notes ',
            top: 0,
            left: 0,
            width: '30%',
            height: '85%',
            border: 'line',
            style: {
                fg: 'white',
                bg: 'black',
                border: { fg: 'cyan' },
                selected: { bg: 'blue' },
                item: { fg: 'yellow' }
            },
            keys: true, // Allow key navigation
            vi: true,   // Vi-style navigation
            mouse: true, // Enable mouse selection
            scrollable: true,
            scrollbar: { ch: ' ', track: { bg: 'grey' }, style: { bg: 'cyan' } }
        });

        // Activities List (Top Right Pane)
        this.activitiesList = blessed.list({
            parent: screen,
            label: ' Activities ',
            top: 0,
            left: '30%',
            width: '70%',
            height: '50%',
            border: 'line',
            style: {
                fg: 'white',
                bg: 'black',
                border: { fg: 'cyan' },
                selected: { bg: 'blue' },
                item: { fg: 'white'}
            },
            keys: true,
            vi: true,
            mouse: true,
            scrollable: true,
            scrollbar: { ch: ' ', track: { bg: 'grey' }, style: { bg: 'cyan' } }
        });

        // Details Box (Middle Right Pane)
        this.detailsBox = blessed.box({
            parent: screen,
            label: ' Details ',
            top: '50%',
            left: '30%',
            width: '70%',
            height: '35%', // Adjusted height
            border: 'line',
            style: {
                fg: 'white',
                bg: 'black',
                border: { fg: 'cyan' }
            },
            keys: true,
            vi: true,
            mouse: true,
            scrollable: true,
            alwaysScroll: true,
            scrollbar: { ch: ' ', track: { bg: 'grey' }, style: { bg: 'cyan' } },
            content: 'Select a note or activity to see details.'
        });

        // Status Bar / Log (Bottom Pane)
        this.statusBar = blessed.log({
            parent: screen,
            label: ' Status / Prompts (Press Enter to Focus Input) ',
            bottom: 1, // Leave space for input box
            left: 0,
            width: '100%',
            height: 4, // Increased height for prompts
            border: 'line',
            style: {
                fg: 'green',
                bg: 'black',
                border: { fg: 'white' }
            },
            scrollable: true,
            mouse: true,
            scrollbar: { ch: ' ', track: { bg: 'grey' }, style: { bg: 'green' } }
        });

        // Input Box (Bottom Line)
        this.inputBox = blessed.textbox({
            parent: screen,
            bottom: 0,
            left: 0,
            width: '100%',
            height: 1,
            style: {
                fg: 'white',
                bg: 'blue' // Make input stand out
            },
            inputOnFocus: true
        });
    }

    // --- Event Handling ---
    private setupEventHandlers(): void {
        if (!this.screen || !this.notesList || !this.activitiesList || !this.inputBox || !this.detailsBox) return;

        const screen = this.screen;

        // Global Quit
        screen.key(['escape', 'q', 'C-c'], () => {
            this.stop();
            // Ensure AgentLoop handles shutdown cleanly
            process.kill(process.pid, 'SIGINT');
        });

        // Focus Switching (e.g., Tab, Enter)
        screen.key(['tab'], () => screen.focusNext());
        screen.key(['S-tab'], () => screen.focusPrevious()); // Shift+Tab
        // Allow Enter in lists to focus input for commands/response
        this.notesList.key(['enter'], () => this.inputBox?.focus());
        this.activitiesList.key(['enter'], () => this.inputBox?.focus());
        this.detailsBox.key(['enter'], () => this.inputBox?.focus());
        this.statusBar?.key(['enter'], () => this.inputBox?.focus());


        // List Selection
        this.notesList.on('select item', (item, index) => {
            const selected = this.notesData[index];
            if (selected && selected.id !== this.selectedNoteId) {
                this.selectedNoteId = selected.id;
                this.selectedActivityId = null; // Reset activity selection
                this.updateActivitiesList();
                this.updateDetailsBox(); // Show note details
                this.activitiesList?.focus(); // Move focus to activities
                screen.render();
            }
        });

        this.activitiesList.on('select item', (item, index) => {
            const selected = this.activitiesData[index];
            if (selected && selected.id !== this.selectedActivityId) {
                this.selectedActivityId = selected.id;
                this.updateDetailsBox(); // Show activity details
                screen.render();
            }
        });

        // Input Box Submission
        this.inputBox.on('submit', (value) => {
            const inputText = value.trim();
            this.inputBox?.clearValue(); // Clear input after submission
            this.inputBox?.focus(); // Keep focus after submit
            if (inputText) {
                this.handleCommand(inputText);
            }
            screen.render(); // Re-render after command handling
        });

        // Ensure screen updates on resize
        screen.on('resize', () => {
            // Re-emit focus/select events if necessary, then render
            this.notesList?.emit('attach');
            this.activitiesList?.emit('attach');
            this.detailsBox?.emit('attach');
            this.statusBar?.emit('attach');
            this.inputBox?.emit('attach');
            screen.render();
        });
    }

    // --- Data Handling & Rendering ---

    private refreshDataAndView(): void {
        if (this.isRendering || !this.screen) return;
        this.isRendering = true;
        try {
            this.updateDataCache();
            this.updateNotesList(); // Update notes first
            this.updateActivitiesList(); // Then activities (might depend on selected note)
            this.updateDetailsBox(); // Then details
            this.updateStatusBar(); // Update status last
            this.screen.render();
        } catch (e) {
            console.error("Error during UI refresh:", e);
            this.log(`Error refreshing UI: ${e instanceof Error ? e.message : String(e)}`);
        } finally {
            this.isRendering = false;
        }
    }

    private updateDataCache(): void {
        this.thoughtsMap.clear();
        this.thoughtStore.getAllThoughts().forEach(t => this.thoughtsMap.set(t.id, t));

        this.notesData = [];
        this.thoughtsMap.forEach(t => {
            // Identify root thoughts (INPUT type or thoughts where id === root_id)
            if (t.type === 'INPUT' && t.metadata[Keys.ROOT_ID] === t.id) {
                this.notesData.push({
                    id: t.id,
                    text: this.formatNoteListText(t),
                    timestamp: t.metadata[Keys.TIMESTAMP] as number ?? 0
                });
            } else if (!t.metadata[Keys.ROOT_ID] && t.type === 'INPUT' && t.status !== 'DONE' && t.status !== 'FAILED') {
                // Include orphaned INPUTs that are active
                this.notesData.push({
                    id: t.id,
                    text: this.formatNoteListText(t),
                    timestamp: t.metadata[Keys.TIMESTAMP] as number ?? 0
                });
            }
        });
        // Sort notes (e.g., newest first)
        this.notesData.sort((a, b) => b.timestamp - a.timestamp);

        // Update activities cache ONLY if a note is selected
        this.updateActivitiesDataCache();

        // Update pending prompts list
        this.pendingPromptsForDisplay = this.userInteractionTool.getPendingPromptsForUI(this.thoughtStore);
    }

    private updateActivitiesDataCache(): void {
        this.activitiesData = [];
        if (!this.selectedNoteId) return;

        this.thoughtsMap.forEach(t => {
            if (t.metadata[Keys.ROOT_ID] === this.selectedNoteId && t.id !== this.selectedNoteId) {
                this.activitiesData.push({
                    id: t.id,
                    text: this.formatActivityListText(t),
                    timestamp: t.metadata[Keys.TIMESTAMP] as number ?? 0,
                    type: t.type,
                    status: t.status,
                    priority: this.getActivityPriority(t)
                });
            }
        });
        // Apply current sort order to activities
        this.activitiesData.sort(this.getActivityComparator());
    }


    private updateNotesList(): void {
        if (!this.notesList) return;
        // Preserve selection if possible
        const currentSelection = this.notesList.selected;
        const selectedIdBeforeUpdate = (currentSelection !== undefined && this.notesData[currentSelection])
            ? this.notesData[currentSelection].id
            : this.selectedNoteId;

        const listItems = this.notesData.map(n => n.text);
        this.notesList.setItems(listItems);

        // Try to restore selection
        const newIndex = this.notesData.findIndex(n => n.id === selectedIdBeforeUpdate);
        if (newIndex !== -1) {
            this.notesList.select(newIndex);
            if (this.selectedNoteId !== selectedIdBeforeUpdate) {
                this.selectedNoteId = selectedIdBeforeUpdate; // Update state if selection restored
                this.updateActivitiesDataCache(); // Reload activities for restored selection
            }
        } else {
            // Selection lost, clear dependent views
            this.selectedNoteId = null;
            this.updateActivitiesDataCache();
        }
    }

    private updateActivitiesList(): void {
        if (!this.activitiesList) return;

        // Preserve selection if possible
        const currentSelection = this.activitiesList.selected;
        const selectedIdBeforeUpdate = (currentSelection !== undefined && this.activitiesData[currentSelection])
            ? this.activitiesData[currentSelection].id
            : this.selectedActivityId;


        if (this.selectedNoteId) {
            const listItems = this.activitiesData.map(a => a.text);
            this.activitiesList.setItems(listItems);
            this.activitiesList.setLabel(` Activities (${listItems.length}) - Sort: ${this.currentSortBy} `);

            // Try to restore selection
            const newIndex = this.activitiesData.findIndex(a => a.id === selectedIdBeforeUpdate);
            if (newIndex !== -1) {
                this.activitiesList.select(newIndex);
                if (this.selectedActivityId !== selectedIdBeforeUpdate) {
                    this.selectedActivityId = selectedIdBeforeUpdate; // Update state
                }
            } else {
                this.selectedActivityId = null; // Selection lost
            }
        } else {
            this.activitiesList.setItems(['(Select a Note)']);
            this.activitiesList.setLabel(' Activities ');
            this.selectedActivityId = null;
        }
    }

    private updateDetailsBox(): void {
        if (!this.detailsBox) return;

        let thoughtIdToShow: UUID | null = this.selectedActivityId ?? this.selectedNoteId;
        let content = 'Select a note or activity to see details.';

        if (thoughtIdToShow) {
            const thought = this.thoughtsMap.get(thoughtIdToShow);
            if (thought) {
                content = this.formatThoughtDetails(thought);
            } else {
                content = `Details for ID ${thoughtIdToShow.substring(0, 6)} not found.`;
            }
        }
        this.detailsBox.setContent(content);
    }

    private updateStatusBar(): void {
        if (!this.statusBar) return;
        // Clear previous status/prompt lines? Log widget appends.
        // this.statusBar.log(`[${new Date().toLocaleTimeString()}] ${this.lastLogMessage}`);

        let statusContent = `[${new Date().toLocaleTimeString()}] ${this.lastLogMessage}\n`;
        statusContent += "--- Pending Prompts ---\n";

        if (this.pendingPromptsForDisplay.length > 0) {
            this.pendingPromptsForDisplay.forEach((p, index) => {
                statusContent += `${index + 1}. [${p.id.substring(0, 6)}] ${p.prompt}\n`;
                if (p.options) {
                    statusContent += `   Options: ${p.options.map((opt, i) => `${i+1}) ${opt}`).join(' | ')}\n`;
                }
            });
        } else {
            statusContent += "(None)\n";
        }
        this.statusBar.setContent(statusContent); // Overwrite content
        this.statusBar.setScrollPerc(100); // Scroll to bottom
    }

    // --- Command Handling ---
    private handleCommand(input: string): void {
        const parts = input.trim().split(' ');
        const command = parts[0]?.toLowerCase();
        const args = parts.slice(1);

        if (!command) return;

        this.log(`CMD: ${input}`); // Log command attempt

        if (command.startsWith('/')) { // Command prefix
            switch (command) {
                case '/add':
                case '/note':
                    const noteText = args.join(' ').trim();
                    if (noteText) {
                        this.agentLoop.addInput(noteText); // Use AgentLoop method
                        this.log(`Added new note.`);
                    } else {
                        this.log("Usage: /add <note text>");
                    }
                    break;
                case '/sort':
                    const criteria = args[0]?.toLowerCase();
                    if (criteria === 'time' || criteria === 'timestamp') this.currentSortBy = 'timestamp';
                    else if (criteria === 'type') this.currentSortBy = 'type';
                    else if (criteria === 'prio' || criteria === 'priority') this.currentSortBy = 'priority';
                    else if (criteria === 'status') this.currentSortBy = 'status';
                    else {
                        this.log("Invalid sort criteria. Use: time, type, prio, status");
                        return; // Don't redraw if invalid
                    }
                    this.log(`Sorting activities by ${this.currentSortBy}`);
                    // Data cache needs to be updated based on new sort order
                    this.updateActivitiesDataCache(); // Recalculate sorted activities
                    this.updateActivitiesList(); // Update the list widget
                    this.screen?.render(); // Re-render immediately
                    break;
                case '/respond':
                case '/r':
                    const promptIndexStr = args[0];
                    const responseText = args.slice(1).join(' ').trim();
                    const promptIndex = parseInt(promptIndexStr, 10) - 1; // User sees 1-based index

                    if (!isNaN(promptIndex) && promptIndex >= 0 && promptIndex < this.pendingPromptsForDisplay.length && responseText) {
                        const targetPrompt = this.pendingPromptsForDisplay[promptIndex];
                        let actualResponse = responseText;

                        // Handle selecting an option by number
                        if (targetPrompt.options) {
                            const optionIndex = parseInt(responseText, 10) - 1;
                            if (!isNaN(optionIndex) && optionIndex >= 0 && optionIndex < targetPrompt.options.length) {
                                actualResponse = targetPrompt.options[optionIndex];
                            }
                        }
                        this.log(`Responding to prompt ${promptIndex + 1} [${targetPrompt.id.substring(0, 6)}]`);
                        this.userInteractionTool.handleResponse(targetPrompt.id, actualResponse, this.thoughtStore);
                        // View will update on next refresh interval
                    } else {
                        if (this.pendingPromptsForDisplay.length === 0) this.log("No pending prompts to respond to.");
                        else this.log("Usage: /respond <PromptIndex> <ResponseText | OptionNumber>");
                    }
                    break;
                case '/quit':
                case '/exit':
                    this.screen?.emit('key q'); // Trigger quit handler
                    break;
                default:
                    this.log(`Unknown command: "${command}". Try /add, /sort, /respond, /quit.`);
            }
        } else {
            // Default behavior for non-command input?
            // Could be interpreted as adding a note, or responding to first prompt?
            // Let's require explicit commands for clarity.
            this.log(`Input ignored. Use commands like /add, /respond, /sort, /quit.`);
        }
    }

    // --- Formatting Helpers ---
    private formatNoteListText(thought: Thought): string {
        const status = thought.status.padEnd(7);
        const content = termToString(thought.content).substring(0, 50); // Truncate
        // Maybe add indicator if WAITING for input?
        const waiting = thought.status === 'WAITING' ? '{yellow-fg}⏳{/yellow-fg}' : ' ';
        return `${waiting} ${status} ${content}${content.length === 50 ? '...' : ''}`;
    }

    private formatActivityListText(thought: Thought): string {
        const status = this.getStatusIndicator(thought.status); // Use indicator for activities
        const prio = this.getActivityPriority(thought).toFixed(2);
        const type = thought.type.padEnd(9);
        const context = (thought.metadata[Keys.UI_CONTEXT] as string ?? termToString(thought.content)).substring(0, 40);
        return `${status} [${prio}] (${type}) ${context}${context.length === 40 ? '...' : ''}`;
    }

    private formatThoughtDetails(thought: Thought): string {
        let details = `{bold}${thought.type} - ${thought.id}{/bold}\n`;
        details += `Status: ${thought.status}\n`;
        details += `Timestamp: ${new Date(thought.metadata[Keys.TIMESTAMP] as number ?? 0).toLocaleString()}\n`;
        details += `Belief (P/N/Score): ${thought.belief.positive.toFixed(1)} / ${thought.belief.negative.toFixed(1)} / ${calculateBeliefScore(thought.belief, this.config).toFixed(3)}\n`;
        details += `Priority: ${thought.metadata[Keys.PRIORITY] ?? '(from belief)'}\n`;
        details += `Parent ID: ${thought.metadata[Keys.PARENT_ID]?.toString().substring(0, 8) ?? 'None'}\n`;
        details += `Root ID: ${thought.metadata[Keys.ROOT_ID]?.toString().substring(0, 8) ?? 'N/A'}\n`;

        if (thought.metadata[Keys.PROVENANCE]) {
            details += `Provenance: ${(thought.metadata[Keys.PROVENANCE] as string[]).map(id => id.substring(0,6)).join(', ')}\n`;
        }
        if (thought.metadata[Keys.RELATED_IDS]) {
            details += `Related: ${(thought.metadata[Keys.RELATED_IDS] as string[]).map(id => id.substring(0,6)).join(', ')}\n`;
        }
        if (thought.metadata[Keys.WORKFLOW_ID]) {
            details += `Workflow: ${thought.metadata[Keys.WORKFLOW_ID]} Step: ${thought.metadata[Keys.WORKFLOW_STEP]}\n`;
        }
        if (thought.metadata[Keys.ERROR]) {
            details += `{red-fg}Error: ${thought.metadata[Keys.ERROR]}{/red-fg}\n`;
        }

        details += `\n{underline}Content:{/underline}\n${termToString(thought.content)}\n`;

        // Optionally show full metadata
        // details += `\n{underline}Metadata:{/underline}\n${JSON.stringify(thought.metadata, null, 2)}\n`;

        return details;
    }

    private getActivityPriority(t: Thought): number {
        const explicitPrio = t.metadata[Keys.PRIORITY];
        return typeof explicitPrio === 'number' && isFinite(explicitPrio) ? explicitPrio : calculateBeliefScore(t.belief, this.config);
    };

    private getActivityComparator(): (a: { status: Status; priority: number; timestamp: number, type: Type }, b: { status: Status; priority: number; timestamp: number, type: Type }) => number {
        // Status order: ACTIVE > PENDING > WAITING > FAILED > DONE
        const statusOrder: Record<Status, number> = { 'ACTIVE': 0, 'PENDING': 1, 'WAITING': 2, 'FAILED': 3, 'DONE': 4 };
        const getTypeOrder = (t: {type: Type}): number => {
            const typePrio: Record<Type, number> = { 'INPUT': 0, 'QUERY': 1, 'GOAL': 2, 'STRATEGY': 3, 'WORKFLOW_STEP': 4, 'TOOLS': 5, 'RULE': 6, 'OUTCOME': 7 };
            return typePrio[t.type] ?? 99;
        }

        switch (this.currentSortBy) {
            case 'type':
                // Sort by type group, then timestamp within group
                return (a, b) => getTypeOrder(a) - getTypeOrder(b) || a.timestamp - b.timestamp;
            case 'priority':
                // Sort descending by calculated priority, then ascending by timestamp
                return (a, b) => b.priority - a.priority || a.timestamp - b.timestamp;
            case 'status':
                // Sort by status order, then descending priority within status
                return (a, b) => statusOrder[a.status] - statusOrder[b.status] || b.priority - a.priority;
            case 'timestamp':
            default:
                // Sort ascending by timestamp (oldest first)
                return (a, b) => a.timestamp - b.timestamp;
        }
    }

    private getStatusIndicator(status: Status): string {
        switch (status) {
            case 'PENDING': return '{grey-fg}⏳{/grey-fg}'; // Pending
            case 'ACTIVE': return '{blue-fg}⚙️{/blue-fg}'; // Active/Processing
            case 'WAITING': return '{yellow-fg}⏱️{/yellow-fg}'; // Waiting (e.g., user input)
            case 'DONE': return '{green-fg}✅{/green-fg}'; // Done
            case 'FAILED': return '{red-fg}❌{/red-fg}'; // Failed
            default: return '?';
        }
    }
}
export const DEFAULT_CONFIG: Config = Object.freeze({
    maxRetries: 3,
    pollIntervalMillis: 100,
    thoughtProcessingTimeoutMillis: 30000,
    numWorkers: 4,
    uiRefreshMillis: 1000,
    memorySearchLimit: 5,
    ollamaApiBaseUrl: 'http://localhost:11434',
    ollamaModel: 'llama3.1',
    persistenceFilePath: './flowmind_state.json',
    persistenceIntervalMillis: 30000,
    beliefDecayRatePerMillis: 0, // Default: No decay
    contextSimilarityBoostFactor: 0.5, // Moderate boost based on context
    enableSchemaValidation: true, // Enable tool schema validation by default
});


// --- AgentLoop Modifications ---
// Need to instantiate and manage the new UI

export class AgentLoop {
    private readonly agentId: UUID;
    private readonly config: Config;
    private readonly thoughtStore: ThoughtStore;
    private readonly ruleStore: RuleStore;
    private readonly memoryStore: MemoryStore;
    private readonly unifier: Unifier;
    private readonly llmTool: LLMTool; // Central LLMTool instance
    private readonly userInteractionTool: UserInteractionTool; // Singleton instance
    private readonly toolManager: ToolManager;
    private readonly actionExecutor: ActionExecutor;
    private readonly persistence: Persistence;
    private readonly workers: Worker[] = [];
    private isRunning = false;
    private saveIntervalId: NodeJS.Timeout | null = null;
    private readonly ui: UserInterface; // Type is now the blessed UI

    constructor(agentId?: UUID, configOverrides: Partial<Config> = {}) {
        this.agentId = agentId ?? generateUUID();
        this.config = Object.freeze({ ...DEFAULT_CONFIG, ...configOverrides });

        console.log(`Initializing FlowMind Agent ${this.agentId.substring(0,8)}...`);

        // --- Initialize Core Components (Order matters for dependencies) ---
        this.thoughtStore = new ThoughtStore(this.agentId);
        this.llmTool = new LLMTool(this.config);
        this.ruleStore = new RuleStore(this.llmTool);
        this.memoryStore = new MemoryStore();
        this.unifier = new Unifier();
        this.userInteractionTool = new UserInteractionTool(); // Create the UI tool instance

        // Tool Manager needs dependencies
        this.toolManager = new ToolManager(
            this.memoryStore, this.thoughtStore, this.config, this.llmTool
        );
        // Register the singleton UserInteractionTool instance
        this.toolManager.registerToolDefinition(new UserInteractionToolDefinition(this.userInteractionTool));
        // Register other tools... (e.g., WebSearchTool)

        this.actionExecutor = new ActionExecutor(
            this.thoughtStore, this.ruleStore, this.toolManager, this.memoryStore,
            this.agentId, this.config, this.llmTool
        );
        this.persistence = new Persistence();

        // --- Initialize UI (Needs stores, UI tool, and AgentLoop itself for addInput) ---
        this.ui = new UserInterface(
            this.thoughtStore, this.userInteractionTool, this.config, this.agentId, this // Pass self
        );

        // Bootstrap rules after all components are initialized
        this.bootstrapRules(); // Keep async call here, start() will await load/bootstrap
    }

    // --- bootstrapRules() remains the same ---
    private async bootstrapRules(): Promise<void> {
        // ... (bootstrap logic as before) ...
        console.log(`AgentLoop: Bootstrapping rules...`);
        // Basic flow rules + workflow example
        const rulesData: Omit<Rule, 'id' | 'belief' | 'metadata'>[] = [
            // INPUT -> Goal
            { pattern: S("input", [V("ContentText")]),
                action: S("llm", [S("params", [
                    S("action", [A("generate")]),
                    S("input", [A('Analyze input "?ContentText" and formulate a concise goal as a structure: {"name": "goal_name", "args": [...]}')]),
                    S("type", [A("GOAL")]) ])]) },
            // GOAL -> Strategy
            { pattern: S("goal", [V("GoalTerm")]),
                action: S("llm", [S("params", [
                    S("action", [A("generate")]),
                    S("input", [A('Generate initial strategy for goal "?GoalTerm" as a structure: {"name": "strategy_name", "args": [...]}.')]),
                    S("type", [A("STRATEGY")]) ])]) },
            // STRATEGY -> Outcome/Next Step
            { pattern: S("strategy", [V("StrategyTerm")]),
                action: S("llm", [S("params", [
                    S("action", [A("generate")]),
                    S("input", [A('Execute strategy "?StrategyTerm" or determine next step as structure: {"name": "outcome_or_step", "args": [...]}.')]),
                    S("type", [A("OUTCOME")]) ])]) },
            // Query -> Outcome (Answer)
            { pattern: S("query", [V("QueryTerm")]),
                action: S("llm", [S("params", [
                    S("action", [A("generate")]),
                    S("input", [A('Answer query "?QueryTerm". Respond with structure {"value": "Your answer"}')]),
                    S("type", [A("OUTCOME")]) ])]) },
            // Explicit User Interaction Rule
            { pattern: S("needs_clarification", [V("Topic")]),
                action: S("user_interaction", [S("params", [
                    S("prompt", [A("Please clarify: ?Topic")]),
                    S("options", [L([A("Yes"), A("No"), A("More Info")])]) // Example options
                ])]) },
            // Simple Memory Add Rule
            { pattern: S("remember", [V("Fact")]),
                action: S("memory", [S("params", [
                    S("action", [A("add")]),
                    S("content", [V("Fact")]), // Assumes Fact is atom/string
                    S("type", [A("fact")]) ])]) },
            // Rule to trigger failure synthesis (matches thought created by handleFailure)
            { pattern: S("synthesize_failure_rule", [V("FailedThoughtID"), V("ErrorHint")]),
                action: S("llm", [S("params", [
                    S("action", [A("generate")]),
                    S("input", [V("generation_prompt")]), // Prompt passed via metadata
                    S("type", [A("RULE")]) ])]) },
            // Rule to trigger tool discovery
            { pattern: S("discover_tools_for", [V("StrategyContent")]),
                action: S("llm", [S("params", [
                    S("action", [A("generate")]),
                    S("input", [A('List external tools (web search, APIs) useful for: "?StrategyContent". Format: {"tools": [{"name": "tool_name", "type": "...", "endpoint": "...?"}, ...]}')]),
                    S("type", [A("TOOLS")])
                ])]) },
            // Rule to trigger Goal Proposal
            { pattern: S("propose_related_goal", [V("ContextTerm")]),
                action: S("goal_proposal", [S("params", [])]) }, // No params needed for basic proposal
            // Example Workflow Rule: Plan Trip -> Sequence
            { pattern: S("plan_trip", [V("Destination")]),
                action: S("sequence", [ // Define sequence of steps
                    S("user_interaction", [S("params", [ // Step 1: Ask budget
                        S("prompt", [A("What is the budget for ?Destination?")])])]),
                    S("web_search", [S("params", [ // Step 2: Search flights
                        S("query", [A("Flights to ?Destination")])])]),
                    S("llm", [S("params", [ // Step 3: Summarize (needs access to prior results - handled via context or memory)
                        S("action", [A("generate")]),
                        S("input", [A('Summarize trip plan for ?Destination based on budget and flight info found.')]),
                        S("type", [A("OUTCOME")]) ])])
                ]) },
        ];

        let count = 0;
        for (const rData of rulesData) {
            const rule: Rule = Object.freeze({
                id: generateUUID(),
                belief: { ...DEFAULT_BELIEF, lastUpdated: Date.now() },
                metadata: Object.freeze({
                    [Keys.AGENT_ID]: this.agentId,
                    [Keys.TIMESTAMP]: Date.now(),
                    bootstrap: true // Mark as a bootstrap rule
                }),
                ...rData
            });
            await this.ruleStore.add(rule); // Use async add for embedding
            count++;
        }
        console.log(`AgentLoop: Bootstrapped ${count} rules.`);
    }

    async start(): Promise<void> {
        if (this.isRunning) {
            console.warn(`Agent ${this.agentId} is already running.`);
            return;
        }
        this.isRunning = true;
        this.ui.log(`Agent ${this.agentId} starting...`); // Log to TUI status

        // Load previous state OR Bootstrap rules
        try {
            await this.persistence.load(this.thoughtStore, this.ruleStore, this.memoryStore, this.config.persistenceFilePath);
            this.ui.log(`Loaded state from ${this.config.persistenceFilePath}`);
            if (this.ruleStore.getAll().length === 0) {
                this.ui.log("No rules found after loading, bootstrapping...");
                await this.bootstrapRules();
            }
        } catch (err: any) {
            this.ui.log(`Failed to load state. Bootstrapping fresh rules. Error: ${err.message}`);
            this.thoughtStore.clear(); this.ruleStore.clear(); this.memoryStore.clear();
            await this.bootstrapRules();
        }

        // Start the TUI
        this.ui.start(); // This now takes over the terminal

        // Start Workers in background
        this.ui.log(`Starting ${this.config.numWorkers} workers...`);
        if (this.config.numWorkers <= 0) this.ui.log("Warning: Agent configured with 0 workers!");
        this.workers.length = 0;
        for (let i = 0; i < this.config.numWorkers; i++) {
            const worker = new Worker(
                this.agentId, this.thoughtStore, this.ruleStore, this.unifier,
                this.actionExecutor, this.config,
            );
            this.workers.push(worker);
            worker.start();
        }

        // Start periodic saving
        if (this.config.persistenceIntervalMillis > 0) {
            this.saveIntervalId = setInterval(async () => {
                if (!this.isRunning) return;
                try {
                    // this.ui.log(`Periodic save...`); // Maybe too noisy
                    await this.persistence.save(this.thoughtStore, this.ruleStore, this.memoryStore, this.config.persistenceFilePath);
                } catch (err: any) {
                    this.ui.log(`Error during periodic save: ${err.message}`);
                    console.error("AgentLoop: Periodic save failed:", err.message);
                }
            }, this.config.persistenceIntervalMillis);
        }

        this.ui.log(`Agent started successfully. Use TUI controls or Ctrl+C to exit.`);
    }

    async stop(): Promise<void> {
        if (!this.isRunning) {
            // console.warn(`Agent ${this.agentId} is not running.`); // Console might not be visible
            return;
        }
        this.ui.log(`Agent ${this.agentId} stopping...`);
        this.isRunning = false; // Signal loops to stop

        // Stop periodic saving FIRST
        if (this.saveIntervalId) {
            clearInterval(this.saveIntervalId);
            this.saveIntervalId = null;
        }

        // Stop workers gracefully
        this.ui.log(`Stopping ${this.workers.length} workers...`);
        await Promise.allSettled(this.workers.map(worker => worker.stop()));
        this.workers.length = 0;
        this.ui.log(`Workers stopped.`);

        // Perform final save
        try {
            this.ui.log("Performing final save...");
            await this.persistence.save(this.thoughtStore, this.ruleStore, this.memoryStore, this.config.persistenceFilePath);
            this.ui.log("Final save complete.");
        } catch (err: any) {
            this.ui.log(`Final save failed: ${err.message}`);
            console.error("AgentLoop: Final save failed:", err.message);
        }

        // Stop UI last (releases the terminal)
        this.ui.stop(); // This should allow the process to exit if nothing else is running

        console.log(`Agent ${this.agentId} stopped.`); // Final message to console
    }

    /** Adds a new top-level INPUT thought to the system. Called by UI. */
    addInput(text: string): void {
        if (!this.isRunning) {
            this.ui.log("Error: Agent not running. Cannot add input.");
            return;
        }
        if (!text || typeof text !== 'string' || text.trim() === '') {
            this.ui.log("Error: Cannot add empty input.");
            return;
        }
        const newThought = createThought(
            this.agentId, 'INPUT', A(text.trim()), 'PENDING',
            { [Keys.UI_CONTEXT]: `Note: ${text.trim()}`, [Keys.PRIORITY]: 1.5 },
            null
        );
        this.thoughtStore.add(newThought);
        this.ui.log(`Added input thought ${newThought.id.substring(0,6)}.`);
        // UI will refresh and show the new note/thought
    }
}


// --- Main Execution ---
async function main() {
    console.log("Initializing FlowMind Agent..."); // Initial console message

    // --- Mock HttpClient Setup --- (Keep as before)
    if (typeof (global as any).HttpClient === 'undefined') {
        console.warn("Mocking HttpClient. Tool calls requiring HTTP will fail unless mocked.");
        (global as any).HttpClient = class MockHttpClient { /* ... mock implementation ... */ };
        (global as any).HttpClient = class MockHttpClient {
            async get<T>(url: string): Promise<T | null> {
                console.log(`Mock GET: ${url}`);
                if (url.includes("mocksearch.com")) return "Mock web search result for query" as any;
                throw new Error(`Mock GET request failed for ${url}`);
            }
            async post<T>(url: string, body: any): Promise<T | null> {
                console.log(`Mock POST: ${url}`, body ? JSON.stringify(body).substring(0, 200) : '');
                if (url.endsWith('/api/embeddings')) {
                    const dummyEmbedding = Array(768).fill(0).map(() => Math.random() * 2 - 1);
                    return { embedding: dummyEmbedding } as any;
                }
                if (url.endsWith('/api/generate')) {
                    const prompt = body?.prompt?.toLowerCase() ?? '';
                    let responseJson = { value: "Default mock LLM response" };
                    if (prompt.includes('formulate goal')) responseJson = { name: "mock_goal", args: ["from_input"] } as any;
                    else if (prompt.includes('generate strategy')) responseJson = { name: "mock_strategy", args: ["step1"] } as any;
                    else if (prompt.includes('execute strategy') || prompt.includes('predict') || prompt.includes('determine next step')) responseJson = { name: "mock_outcome", args: ["success"] } as any;
                    else if (prompt.includes('answer query')) responseJson = { value: "Mock answer to query" };
                    else if (prompt.includes('synthesize rule')) responseJson = { pattern: { kind: 'structure', name: 'failed_condition', args: [{ kind: 'variable', name: 'X' }] }, action: { kind: 'structure', name: 'log_error', args: [{ kind: 'variable', name: 'X' }] } } as any;
                    else if (prompt.includes('list external tools')) responseJson = { tools: [{ name: 'mock_search', type: 'web_search', endpoint: 'http://mocksearch.com' }] } as any;
                    return { response: JSON.stringify(responseJson) } as any;
                }
                throw new Error(`Mock POST request failed for ${url}`);
            }
        };
    }
    // --- End Mock HttpClient ---

    const agent = new AgentLoop("agent_tui"); // Give it a distinct ID
    let shuttingDown = false;

    // Unified shutdown handler
    const shutdown = async (signal: string) => {
        if (shuttingDown) return;
        shuttingDown = true;
        // Log to console as TUI might be destroyed
        console.log(`\nReceived ${signal}. Shutting down agent (TUI will close)...`);
        await agent.stop(); // This now stops the TUI as well
        console.log("Shutdown complete.");
        process.exit(0); // Force exit if needed
    };

    // Graceful shutdown signals
    process.on('SIGINT', () => shutdown('SIGINT'));
    process.on('SIGTERM', () => shutdown('SIGTERM'));
    process.on('uncaughtException', (error, origin) => {
        console.error(`\nFATAL: Uncaught Exception at: ${origin}`, error);
        shutdown('uncaughtException').finally(() => process.exit(1));
    });
    process.on('unhandledRejection', (reason, promise) => {
        console.error('\nFATAL: Unhandled Rejection at:', promise, 'reason:', reason);
        shutdown('unhandledRejection').finally(() => process.exit(1));
    });

    try {
        await agent.start(); // Start the agent (which includes starting the TUI)
        // The process will now be kept alive by the TUI event loop (and workers)
        // No need for an artificial keep-alive like `await new Promise(() => {})`
        console.log("Agent TUI started. Control is now with the blessed interface.");

    } catch (error) {
        console.error("Critical error during agent startup:", error);
        // Attempt cleanup even if start failed partway
        if (!shuttingDown) await agent.stop();
        process.exit(1);
    }
}

// --- HttpClient Placeholder --- (Keep as before)
import fetch, { RequestInit as FetchRequestInit, Response as FetchResponse } from 'node-fetch';
import {
    ActionExecutor,
    calculateBeliefScore,
    Config,
    cosineSimilarity, A, L, S,
    createThought, V, DEFAULT_BELIEF, generateUUID, LLMTool, MemoryStore,
    Metadata,
    Keys, Persistence, Rule, RuleStore,
    sampleWeighted,
    Status,
    Term, termToString, Thought, ThoughtStore, ToolManager,
    Type,
    Unifier,
    UserInteractionTool, UserInteractionToolDefinition, UUID, Worker
} from "./flowmind"; // Use alias

class HttpClient {
    constructor(private baseUrl: string = '') {}
    private async request<T>(url: string, options: FetchRequestInit = {}): Promise<T | null> { /* ... implementation ... */
        const fullUrl = url.startsWith('http') ? url : `${this.baseUrl}${url}`;
        const method = options.method || 'GET';
        try {
            const response: FetchResponse = await fetch(fullUrl, options);
            if (!response.ok) {
                let errorBody = ''; try { errorBody = await response.text(); } catch (_) {}
                const errorMsg = `HTTP Error ${response.status}: ${response.statusText} on ${method} ${fullUrl}`; console.error(`${errorMsg}. Body: ${errorBody.substring(0, 200)}`);
                const error = new Error(errorMsg); (error as any).status = response.status; (error as any).body = errorBody; throw error;
            }
            if (response.status === 204) return null;
            const contentType = response.headers.get('content-type');
            if (contentType && contentType.includes('application/json')) { const text = await response.text(); return text ? JSON.parse(text) as T : null; }
            else if (contentType && (contentType.includes('text') || contentType.includes('application/javascript'))) { return await response.text() as any as T; }
            else { console.warn(`HttpClient: Unsupported content type '${contentType}' for ${method} ${fullUrl}. Returning raw text.`); return await response.text() as any as T; }
        } catch (error: any) { console.error(`HttpClient Request Failed: ${method} ${fullUrl}`, error.message || error); throw new Error(`HTTP request failed for ${method} ${fullUrl}: ${error.message}`); }
    }
    async get<T>(url: string): Promise<T | null> { return this.request<T>(url, { method: 'GET' }); }
    async post<T>(url: string, body: any): Promise<T | null> { return this.request<T>(url, { method: 'POST', headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' }, body: JSON.stringify(body) }); }
}
if (typeof (global as any).HttpClient === 'undefined') { console.log("Defining global HttpClient mock/implementation."); (global as any).HttpClient = HttpClient; }
// --- End HttpClient ---


// --- Execute main ---
    main();
