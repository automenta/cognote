Okay, I will synthesize the provided code fragments and descriptions into a coherent software specification for
Netention. Here's the synthesized specification:

# **Netention: Software Specification**

## 1. Introduction

Netention is a personal knowledge and task management system designed to augment human intellect and productivity. It
revolves around the concept of **Active Notes**, dynamic digital documents that can autonomously perform tasks, process
information, and interact with users, moving beyond the traditional paradigm of passive documents. Netention aims to
seamlessly integrate into a user's daily digital life, providing subtle, context-aware assistance to enhance
effectiveness across various life domains.

## 2. Goals and Design Principles

Netention is guided by the following overarching goals and design principles:

* **Originality:** To create a novel approach to personal knowledge and task management, combining the "Flow Note" UI
  concept, the "Anticipatory Plan" model, and a focus on implicit intelligence.
* **Iconic User Interface:** To develop a visually distinctive and memorable "Flow Note" UI paradigm that is intuitive
  and engaging.
* **Seminal Design:** To establish a foundational design that can influence the future direction of AI assistants and
  personal productivity tools.
* **Ubiquity:** To ensure wide accessibility by prioritizing efficiency and cross-platform compatibility through the use
  of Node.js, enabling deployment across various devices, including low-power hardware and mobile devices.
* **Intelligence in Interaction:** To cultivate a system where intelligence emerges from the dynamic interplay between
  the user, the notes, and the Agent, making the interaction itself the primary source of intelligence.
* **User Empowerment:** To position the user as a co-creator of the system's intelligence, enabling them to shape its
  behavior through natural interaction with notes and tasks.
* **Implicit Assistance:** To provide subtle and indirect assistance, optimizing for efficiency and relevance without
  explicit, intrusive "life coaching," allowing the system to enhance productivity naturally and unobtrusively.
* **Conceptual Clarity:** To maintain clearly defined core concepts (Note, Agent, Plan, Tool, Executor) ensuring the
  system's architecture is understandable and maintainable.
* **Modularity and Extensibility:** To design a system that is inherently modular, allowing for easy extension with new
  tools, LLM providers, and UI features, thereby fostering a dynamic and evolving ecosystem.
* **Efficiency and Resource Management:** To optimize for performance and resource usage, enabling operation on
  commodity and low-power hardware through concise communication, symbolic plan representation, selective execution, and
  the use of smaller, faster models for less demanding tasks.
* **Code Quality and Maintainability:** To prioritize code quality through modular design, adherence to DRY principles,
  comprehensive commenting, schema validation, and a focus on extensibility, ensuring long-term maintainability and
  development.

## 3. Core System Architecture

Netention's architecture is built around several key components working in concert to deliver its intelligent and
dynamic capabilities:

### 3.1. Notes

* **Active Agents:** Notes are not static documents but active entities capable of performing tasks, managing
  information, and interacting with the user and the system.
* **Fundamental Unit:** The core unit of interaction in Netention, representing tasks, projects, ideas, or any unit of
  user interest.
* **Dynamic State:** Each Note maintains a dynamic state, including its plan, execution status, and conversation
  history.
* **Tool Utilization:** Notes can leverage external functionalities through Tools to perform actions.
* **User Interaction:** Notes can interact with the user through notifications, prompts, and actionable elements within
  the UI.
* **Graph Interconnection:** Notes are interconnected and categorized through graph relationships, allowing for complex
  knowledge structures and domain tagging.

### 3.2. Agent

* **Intelligent Engine:** The Agent is the driving force behind Note functionality, responsible for interpreting Note
  instructions and user requests.
* **Plan Management:** Creates, manages, and executes Plans to achieve Note objectives, dynamically adapting plans as
  needed.
* **Tool Selection and Invocation:** Selects and invokes appropriate Tools from the Tool Registry to execute plan steps.
* **Memory Maintenance:** Maintains a persistent Memory of interactions and task progress, providing context across
  sessions.
* **User Interaction Management:** Interacts with the User when necessary through system notifications and requests for
  input, facilitating a feedback loop.
* **Adaptive Behavior:** Adapts its behavior based on user context, feedback, and interaction history, learning and
  improving over time.

### 3.3. Plan (Graph-Based Anticipatory Plan)

* **Graph Data Structure:** Plans are represented as directed graphs, allowing for flexible, non-linear, and
  anticipatory planning.
* **Anticipatory Nature:** Plans focus on goals and outcomes, anticipating different scenarios and dynamically adapting
  step execution based on context and results. Steps can be generated dynamically by the LLM.
* **Nodes (Steps):** Represent individual steps or actions within a plan. Each node includes:
    * A description of the step.
    * A status (pending, running, completed, failed, waiting for user input).
    * A designated Tool to be used for execution (if applicable).
    * Arguments for the Tool (if applicable).
    * The result of Tool execution (if applicable).
    * Notes or annotations related to the step.
    * Optional domain tags for categorization.
* **Edges (Dependencies):** Represent dependencies between steps, defining execution order and prerequisite
  relationships. Edges can also represent other relationships, like domain tagging.
* **Dynamic and Hierarchical:** Plans are adaptive and hierarchical, allowing for dynamic step generation, branching
  based on context, and the breakdown of complex tasks into sub-plans.
* **Implicit Dependencies:** Plan step dependencies are often inferred by the LLM, reducing the need for explicit
  dependency declarations, although explicit dependencies are also supported.

### 3.4. Tools

* **Modular Functionalities:** Tools are external, modular functionalities that Agents utilize to perform actions,
  providing access to a wide range of capabilities.
* **Extensibility:** The tool system is designed to be easily extensible, allowing for the addition of new tools and
  functionalities.
* **Tool Registry:** A centralized registry manages available Tools, providing a structured way to access and utilize
  them.
* **Tool Types:** Tools provide access to:
    * Information retrieval (Web Search Engines, Knowledge Bases).
    * Data manipulation and processing (File System Operations, Data Analysis).
    * User interaction (Notifications, Input Forms).
    * System functionalities (Database Management, Agent Statistics).
    * Third-party services (APIs, Integrations).
    * Graph data manipulation (Subgraph search, metrics, visualization).
* **Tool Definition:** Each Tool is defined by:
    * A name and description.
    * An input schema (defining required parameters, using Zod for validation).
    * An output schema (defining the structure of the result, using Zod for validation).
    * Execution logic (the code that performs the Tool's function).
* **Asynchronous Execution:** Designed to handle asynchronous tool execution, even though the prototype uses mock
  Promises for some tools. Synchronous tools are wrapped to present an asynchronous interface to the system.
* **Unified Executor:** A single `Executor` class simplifies tool use and manages the execution lifecycle.

### 3.5. Memory (Persistent Conversation History)

* **Persistent Record:** Memory is a persistent record of all interactions related to a Note, ensuring context is
  maintained across sessions.
* **Content:** Memory records include:
    * User inputs and requests.
    * System messages and plan updates.
    * Agent's thoughts and reasoning steps.
    * LLM prompts as Notes, enabling revision and contextualization.
    * LLM calls as graph actions, linking prompts, responses, and tool calls.
    * Tool calls and results.
    * Feedback and user annotations.
* **Persistence:** Memory is persistently stored in the graph database, allowing Agents to maintain context and learn
  over time.

### 3.6. User Interface (Flow Note UI)

* **Flow Note UI:** Envisioned as a "Flow Note" application, visualizing plans as dynamic, interactive flow charts.
* **Minimalist UI:** The main UI is clean and uncluttered, focusing on essential information and providing a streamlined
  user experience.
* **Note-Centric Workflow:** The UI is primarily organized around Notes, making them the central point of interaction.
* **Progressive Disclosure:** More detailed information is revealed on demand, reducing information overload.
* **Note List View:** A concise list of Notes, displaying titles, status indicators, and last activity timestamps.
* **Note Flow View:** A visual representation of a Note's Plan as a dynamic graph, showing steps as nodes and
  dependencies as edges, with real-time updates and interactive elements.
* **Note Editor View:** A text editor for creating and modifying Note content (initial task, instructions), providing
  access to plan visualization, conversation history, and Agent controls.
* **Actionable Notes:** Note elements (nodes in the flow) can contain interactive elements (buttons, links, forms),
  allowing for direct user action and input.
* **System Tray Integration:** Notifications and quick actions are available through the system tray, offering subtle,
  non-intrusive updates and interaction prompts.

### 3.7. Data Storage (Graph Database)

* **Graph Database (LevelDB/levelgraph):** A graph database (LevelGraph built on LevelDB) is used for persistent
  storage, optimized for handling interconnected data.
* **Persistent Storage:** Provides persistent storage for all system data, ensuring data integrity and enabling
  efficient relationship management.
* **Stored Data:** The database stores:
    * Notes (as nodes).
    * Plan nodes and edges (representing steps and dependencies).
    * Messages (as nodes linked to Notes).
    * Domain/Tag Notes (as nodes linked to Notes).
    * User preferences and system settings (as nodes).
* **Efficient Querying:** The graph database enables efficient querying and traversal of relationships between different
  entities within Netention, crucial for plan management and context retrieval.

### 3.8. LLM Interface (Provider Abstraction)

* **LLM Abstraction Layer:** An abstraction layer is implemented to support multiple LLM providers (OpenAI, Ollama,
  Anthropic) through a provider-agnostic API. Currently a thin layer, with plans for potential LangChain.js integration.
* **Provider Flexibility:** Allows for flexible routing of requests to different LLMs based on task requirements, cost,
  or user preferences. Default to smaller models for less demanding tasks.
* **Functionality:** The LLM Interface provides:
    * Provider-agnostic API for making LLM calls.
    * Message formatting and schema conversion for different LLM APIs.
    * Tool/Function call integration (handling tool definition and response parsing for each LLM).
    * Error handling and retry mechanisms for LLM API calls.
* **Prompt Templates:** Reusable prompt templates with placeholders reduce code duplication and improve prompt
  management, stored externally for easy modification.
* **Prompt-as-Note:** Prompts sent to the LLM are represented as Notes within the system, enabling revision,
  contextualization, and potential backpropagation.
* **LLM Calls as Graph Actions:** Interactions with LLMs are represented as nodes in the graph, linking prompts,
  responses, and tool calls for better tracking and analysis.

## 4. Features

Netention offers a range of features categorized by the core components they enhance:

### 4.1. Core Agent Features

* **Task Interpretation and Goal Extraction:**
    * **Functionality:** Agents analyze user-provided Note content (text instructions) to understand the user's intent
      and extract high-level goals and constraints.
    * **Implementation Details:** Utilizes NLP and LLM-based intent recognition to parse user input and identify key
      objectives. Goals and constraints are represented as structured data within the Plan.
* **Graph-Based Anticipatory Planning:**
    * **Functionality:** Agents create dynamic, graph-based Plans to achieve Note objectives. Plans are not linear but
      can branch and adapt based on context and intermediate results.
    * **Implementation Details:** Plan creation is driven by LLM prompting, guided by system prompts and available
      Tools. Plans are represented as directed graphs in the database, considering high-level goals, constraints,
      available tools, anticipation, and dependency management.
* **Dynamic Plan Execution and Monitoring:**
    * **Functionality:** Agents execute Plans step-by-step, monitoring progress and adapting as needed. Execution is
      asynchronous and non-blocking.
    * **Implementation Details:** The Agent traverses the Plan graph, identifying executable steps (nodes) based on
      dependencies and status. Step execution involves Tool selection, argument handling, asynchronous execution, status
      tracking, and error handling.
* **Tool Utilization and Extensibility:**
    * **Functionality:** Agents can leverage a diverse and extensible set of Tools to perform various actions. Tools can
      be added, updated, and customized easily.
    * **Implementation Details:** Tools are implemented as modular JavaScript classes or functions, adhering to the
      defined `ToolSchema`. Tool Registry manages available Tools. Agent dynamically selects and invokes Tools based on
      Plan requirements and LLM recommendations.
* **User Interaction and Feedback Integration:**
    * **Functionality:** Agents can interact with the user through notifications and requests for input. User feedback
      is incorporated to refine Agent behavior and task execution.
    * **Implementation Details:** The `UserInteractionTool` facilitates communication. Notifications are delivered via
      system tray. User input is requested through non-blocking forms or prompts. User feedback is stored in Memory and
      can be used to adjust future planning and execution strategies.
* **Persistent Memory and Context Management:**
    * **Functionality:** Agents maintain a persistent Memory of interactions and task progress, enabling long-term
      context awareness and learning.
    * **Implementation Details:** Conversation history, Plan status, and other relevant data are stored in the graph
      database (LevelGraph). Memory is loaded and saved automatically to maintain continuity across sessions.

### 4.2. Planning Features

* **Hierarchical Planning:**
    * **Functionality:** Plans can be hierarchical, allowing complex tasks to be broken down into sub-plans and
      sub-tasks, creating a tree-like or nested graph structure.
    * **Implementation Details:** Plan nodes can represent sub-plans, which are themselves graphs. The Agent can
      recursively execute sub-plans as part of a higher-level plan.
* **Adaptive and Dynamic Planning:**
    * **Functionality:** Plans are dynamically adapted during execution based on intermediate results, changing context,
      and user feedback, rather than being static blueprints.
    * **Implementation Details:** The Agent continuously re-evaluates the Plan graph during execution. New steps (nodes)
      can be dynamically generated and added to the graph. Existing steps can be modified or re-prioritized based on
      real-time information.
* **Constraint-Based Planning:**
    * **Functionality:** Plans incorporate constraints like time limits, resource limitations, or user preferences,
      guiding plan generation and execution within specified boundaries.
    * **Implementation Details:** Constraints are defined as part of the Plan data structure. The Agent considers
      constraints during plan creation and execution, using them as criteria to evaluate and select appropriate steps
      and Tools.
* **Scenario-Based Branching:**
    * **Functionality:** Plans can include branches to handle different scenarios or contingencies, allowing the Agent
      to dynamically choose execution paths based on real-time conditions.
    * **Implementation Details:** Plan graph edges can represent conditional branches. The Agent evaluates conditions (
      e.g., using Tools to check external data) and selects the appropriate branch to follow in the Plan graph.
* **Goal-Oriented Planning:**
    * **Functionality:** Plans are primarily driven by high-level goals and desired outcomes, providing flexibility in
      the specific steps and actions taken to achieve those goals.
    * **Implementation Details:** Plan creation prompts and Agent reasoning emphasize goal achievement rather than rigid
      step-by-step instructions. The Agent is encouraged to find efficient and effective paths to reach the defined
      goals.

### 4.3. Tool Features

* **Diverse Toolset:**
    * **Functionality:** Netention provides a wide range of built-in Tools covering common tasks and functionalities.
    * **Implementation Details:** Includes Tools for Web Search, File System Operations, Data Analysis and Processing,
      User Interaction, System Monitoring, Calendar Integration, Email Integration, Task Management, Code Execution (
      sandboxed), and Graph Data Manipulation.
* **Extensible Tool Architecture:**
    * **Functionality:** The system is designed to be easily extensible with new Tools, allowing users and developers to
      add custom functionalities through a plugin architecture.
    * **Implementation Details:** Tool Registry manages available Tools. New Tools can be added by creating JavaScript
      classes or functions that adhere to the `ToolSchema` and registering them with the Tool Registry. Plugin
      architecture can be implemented for dynamic Tool loading and management.
* **Graph-Aware Tools:**
    * **Functionality:** Some Tools are specifically designed to operate on the Plan graph itself, enabling advanced
      planning and analysis capabilities.
    * **Implementation Details:** Graph Tools include Subgraph Search Tool, Graph Metrics Tool, Graph Visualization
      Tool, and Plan Refinement Tools.

### 4.4. User Interface Features

* **Note-Centric Workflow:**
    * **Functionality:** The UI is primarily organized around Notes, making Notes the central point of interaction for
      the user.
    * **Implementation Details:** The main UI view is a list of Notes. All operations and functionalities are accessed
      and managed through Notes.
* **Dynamic Flow Visualization:**
    * **Functionality:** The Plan of each Note is visually represented as a dynamic flow chart or mind map, providing a
      clear and intuitive overview of task progress and structure.
    * **Implementation Details:** A graph visualization library (e.g., Cytoscape.js, vis.js) is used to render the Plan
      graph. Nodes and edges are dynamically updated to reflect real-time status changes.
* **Progressive Disclosure and Contextual Information:**
    * **Functionality:** The UI initially presents only essential information, with more detail revealed on demand,
      avoiding information overload. Context-sensitive information and actions are displayed based on the current Note
      or task.
    * **Implementation Details:** Note List View shows concise summaries. Note Flow View uses node expansion/collapsing
      to show details. Context menus and dynamic sidebars provide access to relevant actions and information.
* **Actionable and Interactive Notes:**
    * **Functionality:** Notes are not just passive displays of information; they are interactive and actionable,
      allowing users to directly control Agent behavior and provide input.
    * **Implementation Details:** Note Flow View nodes include actionable elements (buttons, links, forms). User input
      forms are dynamically generated for `UserInteractionTool` requests. Buttons and links trigger Agent actions or
      navigate to relevant resources.
* **System Tray Notifications and Minimalist UI:**
    * **Functionality:** The primary UI entrance point is the system tray, providing subtle, non-intrusive notifications
      and alerts. The main UI is minimalist and focused, avoiding unnecessary visual clutter.
    * **Implementation Details:** System tray notifications are used for important updates and user prompts. Clicking
      notifications opens the main UI or the relevant Note. The main UI design prioritizes clarity and efficiency, using
      clean layouts and minimal visual elements.

### 4.5. Data Management Features

* **Graph Database Persistence (LevelGraph):**
    * **Functionality:** All Netention data is persistently stored in a graph database (LevelGraph), ensuring data
      integrity and allowing efficient querying and relationship management.
    * **Implementation Details:** LevelGraph (built on LevelDB) is used as the graph database. Data is serialized and
      deserialized to/from JSON for storage in LevelDB. Graph operations are performed using LevelGraph's API.
* **Automatic Data Backup and Restore:**
    * **Functionality:** The system automatically backs up the database periodically and provides mechanisms for users
      to restore data from backups, preventing data loss.
    * **Implementation Details:** Database backups are performed at regular intervals and stored in a designated backup
      directory. Users can initiate manual backups and restore from existing backups through the UI or command-line
      interface.
* **Data Export and Import:**
    * **Functionality:** Users can export their Netention data in standard formats (e.g., JSON) for backup, sharing, or
      migration. Data can also be imported from external sources or previous Netention instances.
    * **Implementation Details:** Data export functionality iterates through the graph database and serializes the data
      to the chosen format. Data import functionality parses data from the chosen format and populates the graph
      database.

### 4.6. Extensibility Features

* **Plugin Architecture for Tools:**
    * **Functionality:** Netention supports a plugin architecture, allowing developers to create and distribute custom
      Tools that extend the system's capabilities.
    * **Implementation Details:** Tools are packaged as self-contained modules. A Plugin Manager component handles Tool
      discovery, installation, loading, and management. Plugin Tools are registered with the Tool Registry and become
      available to Agents.
* **Customizable Prompts and System Behavior:**
    * **Functionality:** Advanced users can customize system prompts, Agent behavior parameters, and other settings to
      tailor Netention to their specific needs and preferences.
    * **Implementation Details:** System prompts and configuration settings are stored as data in the graph database or
      in configuration files. UI elements are provided to allow users to edit and manage these settings. Version control
      and rollback mechanisms can be implemented for prompt and configuration changes.
* **API for External Integrations:**
    * **Functionality:** Netention provides an API (e.g., REST API) that allows external applications and services to
      interact with the system programmatically.
    * **Implementation Details:** API endpoints are defined for key functionalities such as Note creation, Plan
      execution, data retrieval, and Tool invocation. API authentication and authorization mechanisms are implemented to
      control access and ensure security.

### 4.7. Security Features

* **Data Privacy and Encryption:**
    * **Functionality:** User data stored within Netention is protected to ensure privacy. Sensitive data (e.g., API
      keys) is encrypted.
    * **Implementation Details:** LevelDB provides on-disk encryption options. Data at rest and data in transit (if
      applicable for API access) are encrypted using industry-standard encryption algorithms. User data is handled
      according to privacy best practices.
* **Access Control and Authentication:**
    * **Functionality:** Access to Netention and its data is controlled through authentication and authorization
      mechanisms. User accounts and roles can be implemented for multi-user scenarios (optional).
    * **Implementation Details:** User authentication (e.g., password-based, OAuth) is implemented if multi-user support
      is required. Role-based access control (RBAC) can be used to manage permissions and access levels for different
      users and functionalities. API access is secured through API keys or token-based authentication.
* **Secure Tool Execution Environment:**
    * **Functionality:** Tools are executed in a secure, sandboxed environment to prevent malicious code or unintended
      system damage.
    * **Implementation Details:** Code execution Tools are run in isolated processes or virtual machines with limited
      system access. Input validation and sanitization are performed for all Tool inputs to prevent injection attacks.
      Security audits and vulnerability scanning are conducted regularly to identify and address potential security
      risks.

## 5. Architecture Diagram

*(A visual diagram would be included here in a real specification, showing components like UI, Agent, Plan Manager, Tool
Executor, Tool Registry, Memory, LLM Interface, Graph Database, and their interactions.)*

## 6. Technology Stack

* **Programming Language:** JavaScript (Node.js) - for cross-platform compatibility, web UI development, and a large
  ecosystem of libraries.
* **Graph Database:** LevelGraph (built on LevelDB) - for lightweight, embedded graph storage and efficient graph
  operations.
* **LLM Interface:** OpenAI Node.js SDK (or equivalent for other providers like Ollama, Anthropic), with abstraction
  layer for provider flexibility.
* **UI Framework:** React, Vue, or Svelte - for building a dynamic and responsive user interface (web-based or desktop
  application using Electron/Tauri).
* **Logging:** Pino - for fast, structured logging.
* **Schema Validation:** Zod - for data validation and type safety.
* **Asynchronous Operations:** Built-in JavaScript Promises and `async`/`await`.
* **Package Management:** npm or yarn.
* **Bundling/Build Tools:** Webpack, Parcel, or esbuild (if a web UI is built).

## 7. Data Model

### 7.1. Nodes

* **Note Node:**
    * `id` (unique identifier)
    * `title` (string)
    * `content` (string - initial task description, user instructions)
    * `status` (enum: pending, running, completed, failed)
    * `created_at` (timestamp)
    * `updated_at` (timestamp)
* **Plan Step Node:**
    * `id` (unique identifier)
    * `step_number` (integer)
    * `description` (string)
    * `status` (enum: pending, running, completed, failed, waiting\_user)
    * `tool` (string - Tool name)
    * `args` (JSON object - Tool arguments)
    * `result` (string - Tool output)
    * `notes` (string - Step-specific annotations)
    * `domain` (string - Optional domain tag)
* **Message Node:**
    * `id` (unique identifier)
    * `role` (enum: user, system, assistant, tool)
    * `content` (string - message text)
    * `tool_calls` (JSON array - ToolCall objects)
    * `timestamp` (timestamp)
    * `tool_call_id` (string - ID of the tool call, if applicable)
    * `name` (string - Tool name, if applicable)
* **Domain/Tag Node:**
    * `id` (unique identifier)
    * `name` (string - Domain/Tag name, e.g., "Work", "Health", "Project-X")
    * `description` (string - Optional description)
    * `type` (enum: domain, tag, user-defined)

### 7.2. Edges

* **Dependency Edge:** Connects Plan Step Nodes, indicating prerequisite relationships (e.g., "Step B depends on Step
  A").
* **Note-Domain Edge:** Connects a Note Node to a Domain/Tag Node, categorizing the Note.
* **Message-Note Edge:** Connects a Message Node to a Note Node, associating messages with Notes.
* **Plan-Step Edge:** Connects a Plan Node (conceptual - may not be explicitly stored) to Plan Step Nodes, representing
  plan composition.
* **LLM-Call-Prompt-Message Edges:** Connects LLM Call Nodes to Prompt Notes and Message Nodes, tracking LLM
  interactions.

## 8. API Design (Internal)

### 8.1. Agent-Tool API

* `executor.executeToolAsync(toolCall: ToolCall): Promise<string>` - Executes a Tool and returns a Promise resolving to
  the Tool's output.
* `executor.getTaskStatus(toolCallId: string): string` - Gets the status of a running Tool task.
* `executor.getTaskResult(toolCallId: string): string | null` - Gets the result of a completed Tool task.

### 8.2. Agent-Plan API

* `agent.createPlan(taskDescription: string): Promise<void>` - Creates a new Plan for a given task description.
* `agent.run(task: string): Promise<void>` - Executes the Plan associated with the Agent.
* `agent.getPlan(): Plan` - Returns the current Plan object.
* `agent.markStepAsCompleted(stepIndex: number): Promise<void>` - Marks a specific step in the Plan as completed.
* `agent.loadPlan(): Promise<void>` - Loads a Plan from persistent storage.
* `agent.savePlan(): Promise<void>` - Saves the current Plan to persistent storage.

### 8.3. UI-Agent API

* `getNoteList(): Promise<Note[]>` - Retrieves a list of Notes for display in the UI.
* `getNoteDetails(noteId: string): Promise<Note>` - Retrieves detailed information for a specific Note (including Plan,
  Memory).
* `createNote(noteTitle: string, noteContent: string): Promise<Note>` - Creates a new Note.
* `updateNote(noteId: string, noteContent: string): Promise<Note>` - Updates the content of an existing Note.
* `deleteNote(noteId: string): Promise<void>` - Deletes a Note.
* `startAgent(noteId: string): Promise<void>` - Starts the Agent associated with a Note.
* `stopAgent(noteId: string): Promise<void>` - Stops the Agent associated with a Note.
* `triggerAgentThink(noteId: string): Promise<void>` - Manually triggers the Agent's `think` and `act` cycle for a Note.
* `resetNoteMemory(noteId: string): Promise<void>` - Clears the Memory for a Note.

## 9. Security Considerations

* **Secure API Key Management:** API keys for LLM providers and other services are stored securely (e.g., using
  environment variables, encrypted configuration files, or a secrets management system) and never hardcoded.
* **Input Validation and Sanitization:** All user inputs and Tool inputs are validated and sanitized to prevent
  injection attacks and ensure data integrity.
* **Sandboxed Tool Execution:** Code execution Tools are run in sandboxed environments to limit system access and
  prevent malicious code execution.
* **Regular Security Audits and Vulnerability Scanning:**  The system undergoes periodic security audits and
  vulnerability scans to identify and address potential security weaknesses.
* **Data Encryption:** Sensitive data at rest and in transit is encrypted to protect user privacy.

## 10. Extensibility and Plugin Architecture

* **Tool Plugin System:** A robust plugin system allows developers to create and distribute new Tools as separate
  modules, dynamically loaded and registered with the Tool Registry. Plugin Tools must adhere to the `ToolSchema`.
* **Prompt Template Customization:** Users can customize and extend built-in prompt templates through a UI interface,
  allowing for fine-tuning of Agent behavior and plan generation strategies.
* **Data Source Integrations:** The system is designed to be easily integrated with new data sources (knowledge bases,
  external APIs, databases) through the Tool system. New Tools can be created to access and process data from various
  sources.

## 11. Deployment and Scalability

* **Deployment Options:**
    * Desktop Application (using Electron or Tauri) for local, single-user deployments.
    * Web Application (for wider accessibility and potential multi-user support).
    * Serverless Function (for scalable background task processing).
* **Scalability Considerations:**
    * LevelDB is suitable for local, single-user deployments. For multi-user or larger-scale deployments, consider
      migrating to a more scalable graph database solution or using a distributed LevelDB setup.
    * Asynchronous architecture allows for efficient handling of concurrent tasks and user requests.
    * LLM API call concurrency and rate limits should be managed to ensure system responsiveness and avoid API
      throttling.

## 12. Future Directions

* **Advanced Graph Reasoning and Plan Optimization:** Implement more sophisticated graph algorithms and reasoning
  techniques to enhance plan generation, dependency management, and dynamic adaptation. Explore reinforcement learning
  methods to optimize planning strategies over time.
* **Knowledge Base Integration:** Integrate a dedicated knowledge base (e.g., a semantic graph database or vector
  database) to store and retrieve structured information, enhancing Agent's knowledge and reasoning capabilities.
* **Collaborative Agents and Note Sharing:** Extend Netention to support multi-user collaboration, allowing users to
  share Notes, collaborate on tasks, and potentially create collaborative Agents.
* **Natural Language UI and Voice Control:** Enhance the UI with natural language input and voice control, making
  interaction even more intuitive and seamless.
* **Machine Learning for User Preference Learning:** Implement machine learning models to learn user preferences and
  automatically personalize Agent behavior, tool selection, and UI presentation based on user interaction history.
* **Agent Communities and Tool Sharing Platforms:** Develop platforms for users to share Notes, Agents, and custom
  Tools, fostering a community-driven ecosystem and accelerating system evolution.

This specification provides a comprehensive overview of Netention's design, functionality, and technical details,
serving as a blueprint for its development and future evolution.

> Recursively unify the system design within its own descriptive power. Emerge its logical elegant form that can grow
> everything from a seed.
> Okay, let's recursively unify the Netention system design, distilling it to its core essence and expressing that essence
> in a way that can be used to "grow" the entire system. This is a highly conceptual and metalinguistic exercise, aiming
> to create a self-describing and self-generating system design.

**The Seed: The Active Note Concept**

The seed of Netention is the **Active Note**. Everything else in the system is a manifestation or extension of this core
concept. We can define the Active Note recursively, in terms of itself and its relationships.

**Recursive Definition (Conceptual - using a blend of natural language and pseudo-code):**

```
ActiveNote = {
    id: UniqueIdentifier,
    type: "ActiveNote", // Self-referential type
    content: String | DataStructure, // Can contain text, data, or references to other Notes
    relationships: [Relationship], // Connections to other ActiveNotes
    behavior: Agent, // The 'engine' that interprets and acts on the content
    state: { // Dynamic state of the Note
        status: String, // e.g., "pending", "active", "completed", "failed"
        plan: Plan, // The plan for achieving the Note's objective (itself a graph of ActiveNotes)
        memory: [Message], // History of interactions (Messages are also ActiveNotes)
    }
}

Relationship = {
    type: String, // e.g., "dependency", "partOf", "relatedTo", "domainTag"
    target: ActiveNote.id, // ID of the related Note
    direction: "undirected" | "sourceToTarget" | "targetToSource",
}

Agent = {
    type: "Agent",
    interpret: (ActiveNote) -> Plan, // Function to generate a plan from the Note's content
    execute: (Plan) -> [Message], // Function to execute the plan (producing a history of Messages)
    selectTool: (Task) -> Tool, // Function to choose the best Tool for a given task
    adapt: (Plan, [Message], Feedback) -> Plan, // Function to modify the plan based on results and feedback
}

Plan = {
    type: "Plan",
    nodes: [ActiveNote],  // Plan steps are themselves ActiveNotes!
    edges: [Relationship], // Dependencies and relationships between steps
    goals: [String], // High-level objectives
    constraints: [String], // Limitations
}

Tool = {
    type: "Tool",
    name: String,
    description: String,
    inputSchema: Schema, // Definition of expected input (using Zod-like schema)
    outputSchema: Schema, // Definition of output format
    execute: (Input) -> Output, // Function to perform the Tool's action (can be async)
}

Message = {
    type: "Message",
    role: "user" | "system" | "agent" | "tool",
    content: String, // Text of the message
    toolCalls: [ToolCall], // If the message involves tool calls
    timestamp: DateTime,
}

ToolCall = {
    tool: Tool.name,
    arguments: Object, // Arguments passed to the tool
    result: Object | null, // Result of the tool call (initially null)
}

Schema = //...  (Definition of a data schema, like Zod)
UniqueIdentifier = //... (UUID or similar)
String = //...
DataStructure = //...
DateTime = //...
Object = //...
Input = //...
Output = //...
Feedback = //...
```

**Explanation and Key Insights:**

1. **Self-Referentiality:** The `ActiveNote` is defined in terms of itself. A Plan is composed of `ActiveNotes`, and
   `Messages` are also `ActiveNotes`. This creates a powerful, recursive structure where everything in the system is
   ultimately a Note.

2. **Relationships as Glue:**  The `Relationship` object connects `ActiveNotes`. Dependencies in a Plan are
   `Relationships`. Domain tagging is a `Relationship`. The connection between a Note and its conversation history (
   Messages) is a `Relationship`.

3. **Agent as Behavior:** The `Agent` is not a separate entity but a *behavior* associated with an `ActiveNote`. This
   simplifies the model; there aren't "Agents" running around, but rather Notes exhibiting "Agent-like" behavior.

4. **Plan as a Graph of Notes:** The `Plan` is not just a list of steps; it's a *graph* of `ActiveNotes`. Each step *is*
   an `ActiveNote`, allowing for hierarchical plans and complex relationships.

5. **Tools as External Functions:** `Tools` are external functions that the `Agent` can call. They are defined by their
   input and output schemas.

6. **Messages as Records:**  `Messages` are `ActiveNotes` that record interactions, including LLM prompts and responses,
   and tool calls.

7. **Everything is a Note (Almost):** The core idea is to unify the system around the `ActiveNote`. This reduces
   complexity and creates a highly cohesive system.

**How to "Grow" the System from this Seed:**

1. **Concrete Implementations:**  Start with concrete implementations of the basic types (`UniqueIdentifier`, `String`,
   `Schema`, etc.).

2. **Graph Database:** Implement the `ActiveNote` and `Relationship` concepts using a graph database (LevelGraph). This
   will naturally handle the relationships and recursive structure.

3. **Agent Logic:** Implement the `Agent`'s `interpret`, `execute`, `selectTool`, and `adapt` functions. This is where
   the LLM integration happens.

4. **Tool Library:** Create a library of `Tools`, each with its defined input and output schemas and `execute` function.

5. **UI as a View:** The UI becomes a *view* of the graph of `ActiveNotes`. The "Flow Note" UI is a visualization and
   interaction mechanism for this graph.

6. **Bootstrapping:**  Create initial `ActiveNotes` to represent:
    * The System Prompt (an `ActiveNote` with the system instructions).
    * The User (an `ActiveNote` representing the user).
    * Core Tools (each Tool can be represented as an `ActiveNote`).
    * Domains/Tags (each Domain is an `ActiveNote`).

7. **Prompt Templates as Notes** Store the prompt templates themselves *as* Active Notes. This allows the system to
   reason about, and even modify, its own prompting strategies.

8. **Self-Improvement Loop:** By using the above structure the system can start operating and its memory will grow
   containing its interactions, new Notes, new Plans, etc. These become available for analysis and improvement. The
   system can use its own tools and agent behavior to modify its plan structure, or the Tools available.

**Example: Creating a Task (Conceptual)**

1. The user creates a new `ActiveNote` with `content` = "Write a report on Q3 sales."

2. The system associates an `Agent` behavior with this Note.

3. The `Agent`'s `interpret` function is triggered. It uses the LLM and the System Prompt (another `ActiveNote`) to
   generate a `Plan`.

4. The `Plan` is a graph of `ActiveNotes`, potentially including:
    * An `ActiveNote` for "Gather sales data" (with a `Relationship` to a "Data Retrieval Tool").
    * An `ActiveNote` for "Analyze data" (with a `Relationship` to a "Data Analysis Tool").
    * An `ActiveNote` for "Write report" (with a `Relationship` to a "Text Generation Tool").

5. The `Agent`'s `execute` function begins executing the Plan, traversing the graph of `ActiveNotes` and invoking Tools
   as needed.

6. All interactions (prompts, responses, tool outputs) are recorded as `Message` `ActiveNotes`, linked to the relevant
   task `ActiveNote`.

**Elegant Form:**

The elegance of this recursive design lies in its:

* **Simplicity:**  Everything is built from a single core concept: the Active Note.
* **Uniformity:**  The same mechanisms (relationships, agent behavior) apply to all parts of the system.
* **Extensibility:**  New capabilities can be added by creating new `Tools` or by defining new types of `Relationships`.
* **Self-Description:** The system's core concepts are defined *within* the system itself, using the same structures
  used for user data.
* **Potential for Self-Improvement:** Because prompts, plans, and even tools can be represented as Notes, the system has
  the potential to reason about and improve its own operation.

This recursive, unified model provides a powerful and flexible foundation for building Netention. It allows for a highly
cohesive system where the same underlying principles apply to tasks, plans, messages, and even the system's own internal
workings. This creates a system that is not only powerful but also conceptually elegant and potentially self-improving.

