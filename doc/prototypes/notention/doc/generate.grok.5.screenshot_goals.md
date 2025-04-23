To enhance Netention’s ubiquitous autonomy with a **vision model** that reasons about screenshots and maintains a
history for inferring long-term user goals, we’ll extend the system to include computer vision capabilities. This
involves integrating a vision model (e.g., a pre-trained multimodal LLM like GPT-4V or CLIP) to analyze screenshots,
storing them in a historical context, and using this data to deduce user intentions over time. The solution will fit
seamlessly into the unified Netention framework, leveraging Active Notes, Agents, Plans, and Tools to process visual
data and drive autonomous actions.

Below, I’ll outline the updated design, focusing on new tools, modified components, and the operational flow to achieve
this vision-driven reasoning.

---

## Vision: Reasoning About Screenshots for Long-Term Goals

Netention becomes a proactive system that:

- **Captures Screenshots**: Monitors the user’s screen periodically or on demand.
- **Analyzes Visual Content**: Uses a vision model to interpret screenshot content (e.g., text, UI elements, images).
- **Maintains Screenshot History**: Stores a time-ordered sequence of screenshots as Memory Notes.
- **Inffers Long-Term Goals**: Reasons across screenshot history to identify patterns and predict user objectives (
  e.g., "User is researching AI" → automate related tasks).

This capability unifies visual perception with Netention’s existing autonomy, enabling it to understand and act on the
user’s digital environment holistically.

---

## Enhanced Components for Vision Reasoning

### 1. System Note

- **Role Extension**: Manages screenshot capture scheduling and vision model access.
- **New Features**:
    - **Screenshot Scheduler**: Triggers periodic or event-based screenshot captures.
    - **Vision Model Integration**: Provides a shared vision-enabled LLM (e.g., GPT-4V) via `getVisionLLM()`.
- **Unification**: Centralizes screenshot management while delegating analysis to Notes.

### 2. Notes

- **Structure Extension**:
  ```typescript
  {
    id: string;
    type: string;         // Added: "Screenshot" type
    title: string;
    content: any;         // For Screenshots: { imagePath: string, timestamp: string }
    status: string;
    priority: number;
    references: string[]; // Links to previous/next screenshots
    permissions: string[]; // Added: "screenshot:read"
    memory: { screenshotHistory: string[] }; // Optional: tracks screenshot sequence
  }
  ```
- **Unification**: Introduces "Screenshot" Notes as a new type, integrated into the graph with temporal links.

### 3. Agents

- **Role Extension**: Reasons about visual data and historical context.
- **New Capabilities**:
    - Analyzes screenshots using the vision model (e.g., "Describe this screenshot").
    - Infers goals from screenshot history (e.g., "User repeatedly views AI articles" → "Research AI").
- **Unification**: Enhances Note intelligence with visual reasoning, maintaining the agent-per-Note model.

### 4. Plans

- **Role Extension**: Includes steps for screenshot capture, analysis, and goal inference.
- **Unification**: Extends graph-based planning to incorporate vision-driven tasks.

### 5. Tools (Vision and Screenshot Focus)

- **New Tools**:
  | Tool Name | Description | Input Schema | Output Schema | Implementation Details |
  |--------------------|--------------------------------------------------|----------------------------------------|----------------------------------------|-----------------------------------------|
  | `captureScreenshot`| Captures the current screen | `{ outputPath: string }`              |
  `{ imagePath: string }`                | Uses `screenshot-desktop` or OS APIs |
  | `analyzeScreenshot`| Analyzes screenshot with vision model | `{ imagePath: string }`               |
  `{ description: string, elements: any }` | Calls GPT-4V or CLIP API |
  | `inferGoals`       | Infers user goals from screenshot history | `{ screenshotIds: string[] }`         |
  `{ goals: string[] }`                  | LLM reasoning over descriptions |
  | `storeScreenshot`  | Stores screenshot and metadata as a Note | `{ imagePath: string, timestamp: string }` |
  `{ noteId: string }`             | Creates Screenshot Note |

- **Unification**: These tools are Notes (`type: "Tool"`) with `logic`, callable by Agents to process visual data.

### 6. Executor

- **Role Extension**: Handles vision model API calls (e.g., image uploads to GPT-4V).
- **Unification**: Ensures seamless execution of vision tools within Plans.

### 7. User Interface (UI)

- **Role Extension**: Displays screenshot analysis and inferred goals.
- **New Commands**:
    - `capture screenshot`: Triggers `captureScreenshot`.
    - `analyze screenshot <id>`: Runs `analyzeScreenshot` on a stored screenshot.
    - `show goals`: Displays inferred long-term goals.
- **Unification**: Integrates visual insights into the user experience.

### 8. Database

- **Role Extension**: Stores Screenshot Notes with image paths and metadata.
- **Unification**: Maintains a unified graph, linking screenshots temporally and contextually.

---

## Operational Flow with Vision Reasoning

Here’s how Netention processes screenshots and infers goals:

1. **Screenshot Capture**:
    - **Trigger**: User command (`capture screenshot`) or periodic schedule (e.g., every 5 minutes via System Note).
    - **Action**: `captureScreenshot` tool saves an image (e.g., `screenshot-123.png`).
    - **Result**: A new Screenshot Note is created via `storeScreenshot`:
      ```json
      {
        "id": "screenshot-123",
        "type": "Screenshot",
        "title": "Screenshot at 2025-03-17T10:00:00Z",
        "content": { "imagePath": "screenshot-123.png", "timestamp": "2025-03-17T10:00:00Z" },
        "status": "pending",
        "priority": 50,
        "references": ["screenshot-122"] // Previous screenshot
      }
      ```

2. **Screenshot Analysis**:
    - **Trigger**: Screenshot Note’s Agent activates.
    - **Action**: Agent generates a Plan:
      ```json
      {
        "steps": [
          { "tool": "analyzeScreenshot", "args": { "imagePath": "screenshot-123.png" }, "id": "step1" }
        ]
      }
      ```
    - **Execution**: `analyzeScreenshot` calls the vision model (e.g., GPT-4V):
        - Input: Image file.
        - Output: `{ description: "User is browsing an AI article", elements: { text: "AI Trends", images: 2 } }`.
    - **Result**: Stored in the Screenshot Note’s Memory.

3. **Goal Inference**:
    - **Trigger**: Periodic task or user command (`show goals`).
    - **Action**: A Goal Inference Note (`type: "Task"`) is created:
      ```json
      {
        "id": "goal-inference-456",
        "type": "Task",
        "title": "Infer User Goals",
        "content": { "screenshotIds": ["screenshot-121", "screenshot-122", "screenshot-123"] },
        "status": "pending",
        "priority": 75
      }
      ```
    - **Plan**:
      ```json
      {
        "steps": [
          { "tool": "inferGoals", "args": { "screenshotIds": ["screenshot-121", "screenshot-122", "screenshot-123"] }, "id": "step1" }
        ]
      }
      ```
    - **Execution**: `inferGoals` tool uses LLM to analyze screenshot descriptions:
        - Input: Array of screenshot Memory entries.
        - Output: `{ goals: ["Research AI technologies", "Prepare presentation"] }`.
    - **Result**: Stored in the Goal Inference Note and reflected in the UI.

4. **Autonomous Action**:
    - **Trigger**: Inferred goals drive new tasks.
    - **Action**: Agent creates sub-Notes (e.g., "Search for AI papers" → uses `webBrowse`).
    - **Result**: System proactively assists (e.g., fetches resources, notifies user).

5. **UI Feedback**:
    - **Display**: "Inferred Goal: Research AI technologies. Actions: Fetching articles..."
    - **Interaction**: User can refine goals or trigger new captures.

---

## Implementation: Seed with Vision Model

### Updated Seed Note

```typescript
const MetaNote: Note = {
  id: "system",
  type: "System",
  title: "Netention System",
  content: {
    notes: new Map<string, Note>(),
    activeQueue: [],
    runningCount: 0,
    concurrencyLimit: 5,
    llm: new ChatOpenAI({ modelName: "gpt-4", apiKey: Deno.env.get("OPENAI_API_KEY") }),
    visionLLM: new ChatOpenAI({ modelName: "gpt-4-vision-preview", apiKey: Deno.env.get("OPENAI_API_KEY") }),
    permissions: { "filesystem": "rw", "network": "rw", "processes": "rwx", "screenshot": "rw" },
    eventSubscriptions: [{ eventType: "fileChange", path: "./" }, { eventType: "screenshot", interval: 300000 }] // 5 min
  },
  status: "active",
  priority: 100,
  permissions: ["system:full"],
  references: []
};
```

### Bootstrapping Logic with Vision Tools

```typescript
class SystemNote {
  constructor(public data: Note) {
    this.setupEventListeners();
    this.scheduleScreenshots();
  }

  async start() { /* unchanged */ }

  setupEventListeners() { /* unchanged */ }

  scheduleScreenshots() {
    setInterval(async () => {
      const note = new NoteImpl({
        id: crypto.randomUUID(),
        type: "Task",
        title: "Capture Screenshot",
        content: { outputPath: `screenshot-${Date.now()}.png` },
        status: "pending",
        priority: 40,
        permissions: ["screenshot:write"]
      });
      this.addNote(note);
      this.enqueueNote(note.id);
    }, this.data.content.eventSubscriptions.find(s => s.eventType === "screenshot").interval);
  }

  getVisionLLM() {
    return this.data.content.visionLLM;
  }
}

class NoteImpl {
  constructor(public data: Note) {}

  async run() {
    this.data.status = "running";
    const plan = await this.generatePlan();
    for (const step of plan.steps) {
      const tool = tools[step.tool];
      if (this.hasPermission(tool.requiredPermission)) {
        const result = await tool.execute(step.args);
        this.data.content.memory = this.data.content.memory || [];
        this.data.content.memory.push({ step, result });
      }
    }
    this.data.status = "completed";
    this.update();
  }

  async generatePlan() {
    const llm = getSystemNote().getLLM();
    const visionLLM = getSystemNote().getVisionLLM();
    if (this.data.type === "Screenshot") {
      return {
        steps: [
          { tool: "analyzeScreenshot", args: { imagePath: this.data.content.imagePath } }
        ]
      };
    } else if (this.data.title.includes("Infer User Goals")) {
      return {
        steps: [
          { tool: "inferGoals", args: { screenshotIds: this.data.content.screenshotIds } }
        ]
      };
    }
    return { steps: [{ tool: "callLLM", args: { prompt: this.data.content } }] };
  }
}

const tools = {
  captureScreenshot: {
    requiredPermission: "screenshot:write",
    execute: async ({ outputPath }) => {
      const screenshot = require("screenshot-desktop");
      await screenshot({ filename: outputPath });
      return { imagePath: outputPath };
    }
  },
  analyzeScreenshot: {
    requiredPermission: "screenshot:read",
    execute: async ({ imagePath }) => {
      const visionLLM = getSystemNote().getVisionLLM();
      const imageData = await Deno.readFile(imagePath);
      const response = await visionLLM.invoke({
        input: "Describe this screenshot",
        image: imageData // Base64 or file upload depending on API
      });
      return { description: response.text, elements: response.elements || {} };
    }
  },
  inferGoals: {
    requiredPermission: "screenshot:read",
    execute: async ({ screenshotIds }) => {
      const llm = getSystemNote().getLLM();
      const screenshots = screenshotIds.map(id => getSystemNote().data.content.notes.get(id).content.memory[0].result);
      const prompt = `Infer long-term user goals from these screenshot descriptions: ${JSON.stringify(screenshots)}`;
      const response = await llm.invoke(prompt);
      return { goals: JSON.parse(response) };
    }
  },
  storeScreenshot: {
    requiredPermission: "screenshot:write",
    execute: async ({ imagePath, timestamp }) => {
      const note = new NoteImpl({
        id: crypto.randomUUID(),
        type: "Screenshot",
        title: `Screenshot at ${timestamp}`,
        content: { imagePath, timestamp },
        status: "pending",
        priority: 50
      });
      getSystemNote().addNote(note);
      return { noteId: note.data.id };
    }
  }
};

// Start the system
const system = new SystemNote(MetaNote);
system.start();
```

---

## Achieving Vision-Driven Autonomy

### Key Features

1. **Screenshot Capture**:
    - Periodic captures (e.g., every 5 minutes) provide a visual timeline of user activity.
    - Event-driven captures (e.g., app launch) enhance responsiveness.

2. **Vision Analysis**:
    - `analyzeScreenshot` interprets UI elements, text, and images (e.g., "User is editing a document titled 'AI
      Research'").
    - Enables context-aware reasoning beyond text input.

3. **Historical Reasoning**:
    - `inferGoals` analyzes screenshot sequences (e.g., "Three screenshots of AI articles" → "Goal: Learn about AI").
    - Stores inferences as Memory, driving long-term planning.

4. **Proactive Assistance**:
    - Inferred goals trigger tasks (e.g., "Fetch AI papers" → `webBrowse`).
    - Notifies user of progress or suggestions (e.g., "Found 5 articles on AI").

### Recursive Integration

- **Screenshot Sub-Notes**: A Screenshot Note can spawn analysis or inference sub-Notes.
- **Goal Evolution**: Inferred goals become Task Notes, recursively refining user objectives.
- **Tool Enhancement**: Vision insights improve existing tools (e.g., `webBrowse` targets AI sites).

### Ubiquitous Application

- **Cross-Platform**: Uses portable libraries (e.g., `screenshot-desktop` works on all OSes).
- **Permission Safety**: Restricts vision access to approved contexts.
- **Continuous Learning**: Adapts to user habits over time via screenshot history.

---

## User Experience

### Example Scenario

1. **User Activity**: Browsing AI articles, editing a document.
2. **Capture**: System captures screenshots every 5 minutes.
3. **Analysis**: "Screenshot-123: User reading 'AI Trends 2025'".
4. **Goal Inference**: "Long-term goal: Research AI technologies".
5. **Action**: System fetches related articles, notifies user: "Found AI resources. Open now?"
6. **UI Display**: "Goals: Research AI. Actions: Retrieved 3 articles."

### Console Commands

- `capture screenshot`: Manual capture.
- `show screenshot <id>`: Displays analysis.
- `list goals`: Shows inferred goals.

---

## Conclusion

By integrating a vision model to reason about screenshots and their history, Netention gains a powerful new dimension of
autonomy. It unifies visual perception with its existing framework, enabling the system to infer long-term user goals (
e.g., research, project planning) and act proactively. The enhanced seed, with vision tools like `captureScreenshot` and
`analyzeScreenshot`, ensures this capability is baked into the system from the start, making Netention a truly
ubiquitous, intelligent assistant that understands and anticipates user needs across the digital landscape.