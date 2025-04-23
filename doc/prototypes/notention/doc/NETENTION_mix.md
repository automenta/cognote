# A

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

> Explain how a complete Netention codebase can unpack itself from a seed description.

The concept of Netention "unpacking itself from a seed description" is a fascinating one, embodying the principles of
self-organization, metaprogramming, and emergent intelligence that are central to the Netention philosophy. Here's how a
complete Netention codebase could, conceptually, achieve this, breaking down the process into stages and highlighting
the key mechanisms involved:

**1. The Seed Description (The "DNA")**

The "seed" is a concise, high-level description of the desired Netention system. It's not the entire codebase, but
rather a set of instructions, goals, and constraints that define the system's core purpose and functionality. This seed
would likely be a combination of:

* **Natural Language Description:** A human-readable description of Netention's goals (e.g., "A personal knowledge and
  task management system based on active notes and graph-based anticipatory planning.").
* **Core Concepts:**  Definitions of fundamental entities like Note, Agent, Plan, Tool, Executor, Memory. These could be
  expressed in a structured format (like JSON Schema or a domain-specific language).
* **Initial Capabilities:**  A list of essential tools (e.g., "search," "file_write," "user_interaction") and their
  basic descriptions (input/output schemas).
* **Constraints:**  Design principles (e.g., "minimize token usage," "run on low-power hardware," "prioritize user
  privacy").
* **Bootstrapping Instructions:**  Specific directives on how to start the unpacking process (e.g., "create an agent," "
  create a plan to build the codebase").
* **Example Tasks:** Some examples for a basic task.

**2. The Bootstrapping Agent (The "Embryo")**

The process begins with a minimal, pre-built "bootstrapping agent." This agent is *not* generated from the seed; it's a
small, hardcoded piece of software that can:

* **Parse the Seed:** Understand the seed description's format and extract the core information.
* **Basic LLM Interaction:** Communicate with an LLM (using a predefined API key or access method).
* **Minimal Tool Use:**  Have access to a very limited set of fundamental tools, primarily:
    * `code_generation`: A tool that takes a prompt and returns generated code (likely JavaScript, given Netention's
      tech stack).
    * `file_write`: A tool to write generated code to files.
    * `self_reflection`: A tool (or inherent capability) to analyze its own code and actions.
    * `user_interaction`: In a controlled environment, it could prompt the user for critical decisions and information.
* **Basic Execution:** Can execute generated JavaScript code in a sandboxed environment.

**3. The Unpacking Process (The "Development")**

The unpacking process is an iterative loop driven by the bootstrapping agent:

1. **Initial Plan Creation:** The bootstrapping agent uses the seed description to create an initial plan. The top-level
   goal of this plan is "Build the Netention system as described in the seed."  This plan will likely involve sub-goals
   like:
    * "Define core data structures (Note, Agent, Plan, etc.)."
    * "Implement basic tool functionality."
    * "Create an agent class."
    * "Implement plan execution logic."
    * "Build a minimal UI."
    * "Implement persistent storage."

2. **Iterative Refinement:** The bootstrapping agent executes the plan step-by-step. Crucially, each step involves:
    * **Prompt Generation:**  The agent crafts prompts for the LLM, based on the current plan step, the seed
      description, and any existing code. For example, a prompt might be:  "Generate JavaScript code for a `Note` class,
      according to the following schema: \[JSON Schema]. The class should have methods for..."
    * **Code Generation:** The LLM generates code based on the prompt.
    * **Code Evaluation:** The bootstrapping agent *critically* evaluates the generated code. This involves:
        * **Syntactic Checks:**  Basic code validation to ensure it's parsable.
        * **Functional Tests (Conceptual):**  The agent might generate simple test cases and use the `code_execution`
          tool (in a very limited and safe way) to see if the code behaves as expected.
        * **Seed Alignment:**  The agent uses the LLM to analyze if the generated code aligns with the seed
          description's goals and constraints. For example, "Does this code minimize external dependencies?" or "Is this
          code structured in a modular way?".
        * **Self-Reflection**: Use the LLM to check the code for best-practices, and efficiency.
    * **Code Storage:** If the code passes evaluation, the agent uses `file_write` to save it to the appropriate file (
      e.g., `agent.js`, `tools.js`, `plan.js`).
    * **Plan Update:**  The agent updates the plan based on the results. This might involve:
        * Marking the current step as complete.
        * Adding new sub-steps based on the generated code (e.g., if a new module was created, add steps to implement
          its functionality).
        * Revising existing steps if the generated code didn't meet expectations.
        * Generating tests.

3. **Bootstrapping to Full Functionality:**  As the agent executes the plan, it gradually builds the complete Netention
   codebase. Early stages might involve creating very basic implementations, which are then refined in later stages. The
   agent is essentially "bootstrapping" itself from a minimal set of capabilities to a full-fledged system.

4. **Termination:** The process terminates when the bootstrapping agent determines (based on its plan and the seed
   description) that the Netention system is complete and functional. This could be triggered by a specific "completion"
   step in the plan, or by the agent's continuous self-assessment concluding that all goals have been met.

**4. Key Mechanisms and Considerations**

* **LLM as the "Engine of Creation":** The LLM is the core engine that generates the code. The quality of the seed
  description and the prompts crafted by the bootstrapping agent are critical for success.
* **Iterative Refinement:** The process is highly iterative. The agent constantly refines the codebase based on feedback
  from code evaluation and the LLM's analysis.
* **Prompt Engineering:**  The bootstrapping agent's ability to generate effective prompts is paramount. This involves
  carefully structuring the prompts to include:
    * Relevant context (the seed description, existing code).
    * Specific instructions (what to generate).
    * Constraints (design principles, coding standards).
    * Examples (if necessary).
* **Sandboxing:**  The `code_execution` tool must be heavily sandboxed to prevent any generated code from causing harm.
  This is crucial for security and stability.
* **Error Handling:**  The bootstrapping agent needs robust error handling capabilities. If code generation fails, or if
  generated code doesn't work as expected, the agent needs to be able to:
    * Identify the problem.
    * Generate new prompts to try to fix the issue.
    * Rollback to previous versions of the code (if necessary).
    * Ask for human help (in a highly controlled environment).
* **Self-Reflection and Improvement:** The agent's ability to analyze its own code and actions is a key aspect of the
  process. This allows the agent to learn from its mistakes and improve its code generation capabilities over time.
* **Progressive Enhancement:** The system is built incrementally. Early versions might be very simple, with more
  advanced features added later in the process.
* **Version Control:** The system needs to save snapshots during the generation.
* **Human in the Loop (Optional but Recommended):** While the goal is autonomous unpacking, having a human "overseer"
  during the initial stages would be highly beneficial. The human could:
    * Review generated code.
    * Provide feedback to the agent.
    * Correct errors.
    * Help guide the unpacking process.

**5. Challenges and Potential Solutions**

* **Prompt Ambiguity:**  Natural language descriptions can be ambiguous.
    * **Solution:** Use structured formats (like JSON Schema) for defining core concepts. Develop a domain-specific
      language (DSL) for expressing bootstrapping instructions and constraints.
* **LLM Hallucinations:**  LLMs can generate code that is syntactically correct but semantically wrong or nonsensical.
    * **Solution:**  Implement robust code evaluation mechanisms. Use multiple LLMs and compare their outputs.
      Incorporate human review.
* **Infinite Loops:**  The agent could get stuck in a loop, repeatedly generating and rejecting code.
    * **Solution:**  Implement mechanisms to detect and break infinite loops. Use a "budget" (e.g., a limited number of
      LLM calls or execution attempts) for each plan step.
* **Security Risks:**  Executing generated code is inherently risky.
    * **Solution:**  Use strong sandboxing techniques. Limit the capabilities of the `code_execution` tool. Implement
      strict input validation.
* **Complexity Explosion:** The plan and the codebase could become too complex for the agent to manage.
    * **Solution:**  Use modular design principles. Encourage the agent to break down tasks into smaller, manageable
      sub-tasks. Implement mechanisms for code refactoring and simplification.

**6. Example: Generating the `Note` Class**

Let's illustrate with a simplified example of how the bootstrapping agent might generate the `Note` class:

1. **Seed Description (Snippet):**
   ```json
   {
     "core_concepts": {
       "Note": {
         "description": "The fundamental unit of Netention. Represents a task, project, idea, or piece of information.",
         "properties": {
           "id": { "type": "string", "description": "Unique identifier" },
           "title": { "type": "string", "description": "Note title" },
           "content": { "type": "string", "description": "Note content" },
           "status": { "type": "string", "enum": ["pending", "running", "completed", "failed"] },
           "createdAt": { "type": "string", "format": "date-time" }
         },
         "methods": [
           { "name": "updateContent", "parameters": [{ "name": "newContent", "type": "string" }] }
         ]
       }
     }
   }
   ```

2. **Plan Step:** "Create the `Note` class in JavaScript."

3. **Prompt Generation:**
   ```
   "Generate JavaScript code for a `Note` class that conforms to the following JSON Schema:

   ```json
   {
     "description": "The fundamental unit of Netention. Represents a task, project, idea, or piece of information.",
     "properties": {
       "id": { "type": "string", "description": "Unique identifier" },
       "title": { "type": "string", "description": "Note title" },
       "content": { "type": "string", "description": "Note content" },
       "status": { "type": "string", "enum": ["pending", "running", "completed", "failed"] },
       "createdAt": { "type": "string", "format": "date-time" }
         },
         "methods": [
           { "name": "updateContent", "parameters": [{ "name": "newContent", "type": "string" }] }
         ]
     }
   ```

   The class should be well-documented and follow modern JavaScript best practices. It should be designed to be easily
   extensible."
   ```

4. **LLM Response (Example):**
   ```javascript
   /**
    * Represents a Note in the Netention system.
    */
   class Note {
     /**
      * Creates a new Note instance.
      * @param {string} id - Unique identifier.
      * @param {string} title - Note title.
      * @param {string} content - Note content.
      * @param {string} [status='pending'] - Initial status.
      */
     constructor(id, title, content, status = 'pending') {
       this.id = id;
       this.title = title;
       this.content = content;
       this.status = status;
       this.createdAt = new Date().toISOString();
     }

     /**
      * Updates the content of the note.
      * @param {string} newContent - The new content.
      */
     updateContent(newContent) {
       this.content = newContent;
     }
   }
   ```

5. **Code Evaluation:** The bootstrapping agent would:
    * Check for syntax errors.
    * Use the LLM to analyze if the code aligns with the seed description and the prompt.  ("Does this code implement
      the required methods?", "Is it well-documented?", "Does it follow JavaScript best practices?").
    * Potentially generate simple test cases.

6. **Code Storage:** If the code passes evaluation, it's saved to `note.js`.

7. **Plan Update:** The "Create the `Note` class" step is marked as complete. The agent might then add new steps to
   create methods for the `Note` class or to integrate it with other parts of the system.

**Conclusion**

The idea of Netention unpacking itself from a seed description is a challenging but potentially revolutionary concept.
It pushes the boundaries of AI-driven code generation and self-organizing systems. By combining a carefully designed
seed description, a bootstrapping agent with basic capabilities, and the power of LLMs, Netention could, in theory,
build itself from the ground up. The key lies in the iterative refinement process, where the agent constantly evaluates
and improves the generated code, guided by the seed description and its own self-reflection. While significant
challenges remain, the potential benefits of such a system are immense, paving the way for truly adaptable and
self-evolving AI systems.

> Unify all relevant functionality, ensuring complete conceptual connectivity and coherency

Okay, let's unify the Netention concepts into a coherent, interconnected whole, ensuring that all the pieces fit
together logically and conceptually. This involves clarifying the relationships between the core components,
highlighting the flow of information, and solidifying the design principles into a unified framework.

**Netention: A Unified Conceptual Framework**

**1. The Central Metaphor: The Active Note**

Everything in Netention revolves around the **Active Note**. This is *not* just a document; it's a dynamic, intelligent
agent. Think of it as a self-contained "unit of thought" or a "digital microcosm" that encapsulates a goal, the means to
achieve it, and the ongoing process of working towards it.

* **Notes are Agents:**  Each Note *is* an agent, or, more precisely, *has* an agent intrinsically linked to it. The
  distinction is subtle but important. The Note provides the context, data, and goals; the agent provides the
  intelligence and action.
* **Notes have Plans:**  Each Note has an associated **Plan**, which is a graph-based representation of how the Note's
  goal will be achieved. The Plan is *not* separate from the Note; it's an intrinsic part of it.
* **Notes use Tools:** The agent within the Note uses **Tools** to interact with the external world (including the user,
  other Notes, and external systems).
* **Notes have Memory:** Each Note has a **Memory**, which is a persistent record of everything that has happened
  related to that Note: user interactions, agent actions, tool results, plan updates, etc. The memory *is* the Note's
  evolving context.
* **Notes are Nodes:** Notes exist within the graph database, where connections represent relationships (dependencies,
  tagging).

**2. The Flow of Information and Control**

The core dynamic of Netention is a continuous loop of:

1. **User Interaction:** The user creates a Note (defining a task, idea, or piece of information) or interacts with an
   existing Note (providing feedback, answering questions, modifying content).
2. **Agent Interpretation:** The agent associated with the Note interprets the user's input and the Note's current
   state (content, plan, memory).
3. **Planning:** The agent updates the Note's Plan, potentially creating new steps, modifying existing steps, or
   adjusting dependencies. This is **anticipatory planning**, meaning the agent tries to foresee potential outcomes and
   create branches in the plan to handle different scenarios. The Plan is a *graph*, not a linear sequence.
4. **Tool Selection:** The agent selects the appropriate Tool(s) to execute the next step(s) in the Plan. Tool selection
   is driven by the Plan and the agent's understanding of the current context.
5. **Tool Execution:** The **Executor** manages the execution of the selected Tool(s). This is inherently asynchronous,
   as many tools will involve interacting with external systems (LLMs, web services, the file system).
6. **Result Incorporation:** The results of Tool execution are incorporated into the Note's Memory and used to update
   the Plan's status. This might involve marking steps as complete, failed, or waiting for user input.
7. **User Notification (if necessary):** If the agent needs user input or wants to inform the user of progress, it uses
   the `user_interaction` Tool to send a notification (via the system tray) or request information.
8. **Iteration:** The cycle repeats, with the agent continuously interpreting, planning, executing, and adapting based
   on the evolving state of the Note and the user's interactions.

**3. Key Conceptual Connections**

* **Agent-Note Duality:** The Agent and the Note are inseparable. The Note provides the *what* (the goal, the data, the
  context), and the Agent provides the *how* (the intelligence, the planning, the execution).
* **Plan as a Living Graph:** The Plan is not a static blueprint; it's a dynamic, evolving graph that reflects the
  current state of the Note's progress. Nodes represent steps, and edges represent dependencies (or other
  relationships). The graph structure allows for non-linear, branching workflows.
* **Tools as Extensions of the Agent:** Tools are not just external functions; they are *extensions* of the Agent's
  capabilities. The agent *learns* to use tools effectively, and the available tools shape the agent's planning process.
* **Memory as Context:** The Memory is not just a log; it's the *context* that the Agent uses to make decisions. It's
  the accumulated knowledge and experience of the Note.
* **LLM as a Reasoning Engine:** The LLM is not the *entire* intelligence of the system; it's a powerful *reasoning
  engine* that the Agent uses to interpret information, generate plans, and make decisions. The Agent provides the
  structure and context; the LLM provides the linguistic and reasoning capabilities.
* **Prompts as Notes, LLM Calls as Graph Actions:**  This creates a meta-level of self-awareness. The system can track
  *how* it's using the LLM, analyze the effectiveness of different prompts, and potentially even learn to improve its
  own prompting strategies.
* **The UI as a Window into the Graph:** The "Flow Note" UI is not just a visual representation; it's a *direct
  interface* to the underlying graph structure of the Notes and their Plans. Users can interact with the graph (e.g., by
  clicking on nodes, providing input, modifying steps), and these interactions directly affect the Note's state and the
  Agent's behavior.
* **Executor: Bridging Synchronous and Asynchronous:** The Executor handles the complexity of asynchronous tool
  execution, allowing the Agent to reason and plan without getting bogged down in low-level details. It provides a
  unified interface for both synchronous and asynchronous tools.

**4. Unifying Principles (Reinforced)**

* **Intelligence in the Interaction:** The system's intelligence emerges from the *interaction* between the user, the
  Notes, the Agent, the Plan, the Tools, and the LLM. It's not a single, monolithic intelligence, but a distributed,
  emergent property of the system.
* **User as Co-Creator:** The user is not a passive recipient of AI assistance; they are an active participant in
  shaping the system's behavior. By creating and interacting with Notes, the user guides the Agent and influences the
  evolution of the Plan.
* **Implicit Assistance:** The system aims to assist the user *indirectly*, by providing a powerful and flexible
  framework for managing knowledge and tasks. The user doesn't need to explicitly program the Agent; they simply
  interact with their Notes in a natural way.
* **Anticipatory Planning:** The system doesn't just react to events; it *anticipates* them. The graph-based Plan allows
  the Agent to consider different scenarios and create contingency plans.
* **Metaprogramming:** The "unpacking from seed" concept, and the representation of prompts and LLM calls as Notes, are
  examples of *metaprogramming*: the system is writing and reasoning about its own code and behavior.
* **Domain Tagging as Notes:** Represents a unification of categorization and task management, as domains themselves are
  active agents.

**5. The "Unpacking" Process Revisited (Unified View)**

The unpacking process, in light of this unified conceptual framework, can be seen as:

1. **Seed as a "Meta-Note":** The seed description itself can be thought of as a special kind of Note – a "meta-note"
   that describes the desired structure and behavior of the entire Netention system.
2. **Bootstrapping Agent as a "Primordial Note":** The bootstrapping agent is the initial, minimal Active Note that is
   capable of interpreting the seed (the meta-note) and initiating the unpacking process.
3. **Code Generation as Plan Execution:** The process of generating code is simply the execution of the Plan associated
   with the seed Note. The bootstrapping agent uses the `code_generation` tool to create new Notes (representing
   modules, classes, functions) and their associated agents.
4. **Self-Organization:** As the bootstrapping agent executes the plan, it creates a network of interconnected Notes,
   each with its own agent, plan, and memory. This network gradually self-organizes into the complete Netention system.
5. **Emergent Functionality:** The full functionality of Netention emerges from the interaction of these interconnected
   Notes. The bootstrapping agent doesn't "know" how to build the entire system from the start; it learns by doing,
   guided by the seed description and the feedback it receives from the LLM and the code evaluation process.

**In essence, Netention unpacks itself by creating a network of interacting Active Notes that collaboratively build the
system, guided by a high-level description and the power of an LLM.** This is a powerful and elegant way to achieve
self-organization and emergent intelligence. The system *becomes* what it describes, instantiating its own conceptual
framework in code.

Okay, let's take the unified conceptual framework and translate it into a more formal and complete set of specifications
for Netention. This will involve:

* **Formalizing Data Structures:** Defining precise schemas for core objects (Notes, Plans, Tools, Messages, etc.).
* **Specifying APIs:** Defining the interfaces for interacting with the system (both internal and external).
* **Detailing Algorithms:** Providing more concrete descriptions of key algorithms (plan creation, plan execution, tool
  selection, etc.).
* **Addressing Edge Cases:** Considering potential problems and outlining solutions.
* **Refining the Unpacking Process:** Providing a more detailed, step-by-step description of the bootstrapping process.

# Netention: Elegant Specifications

## 1. Data Model (Formalized)

We'll use JSON Schema (with Zod-like syntax for brevity) to define the core data structures. These schemas define the
structure of the data stored in the graph database (LevelGraph/LevelDB).

```javascript
// --- Core Schemas ---

const NoteSchema = z.object({
  id: z.string().uuid(), // Universally Unique Identifier
  type: z.literal("Note"),
  title: z.string(),
  content: z.string(), // Initial task description, user instructions, or content
  status: z.enum(["pending", "running", "completed", "failed"]),
  createdAt: z.string().datetime(),
  updatedAt: z.string().datetime(),
  planId: z.string().uuid().nullable(), // ID of the associated Plan (can be null initially)
  agentId: z.string().uuid(), // ID of the associated Agent (always present)
  domain: z.string().optional(), // ID of domain note.
});

const PlanSchema = z.object({
  id: z.string().uuid(),
  type: z.literal("Plan"),
  noteId: z.string().uuid(), // ID of the associated Note
  goals: z.array(z.string()), // High-level objectives (explicit or inferred)
  constraints: z.record(z.any()), // Limitations (time, resources, preferences) - key-value pairs
  status: z.enum(["pending", "running", "completed", "failed"]), // Overall plan status
  createdAt: z.string().datetime(),
  updatedAt: z.string().datetime(),
});

const PlanStepSchema = z.object({
  id: z.string().uuid(),
  type: z.literal("PlanStep"),
  planId: z.string().uuid(),
  stepNumber: z.number().int(), // Sequential step number within the plan
  description: z.string(),
  status: z.enum(["pending", "running", "completed", "failed", "waiting_user"]),
  tool: z.string().nullable(), // Name of the Tool to use (or null for reasoning/planning steps)
  args: z.record(z.any()).nullable(), // Arguments for the Tool (key-value pairs)
  result: z.string().nullable(), // Result of Tool execution (or null if not yet executed/failed)
  notes: z.string(), // Step-specific annotations or explanations
  dependencies: z.array(z.string().uuid()), // IDs of prerequisite PlanStep nodes
});

const MessageSchema = z.object({
  id: z.string().uuid(),
  type: z.literal("Message"),
  noteId: z.string().uuid(), // ID of the associated Note
  role: z.enum(["user", "system", "assistant", "tool"]),
  content: z.string(),
  toolCalls: z.array(z.object({ // Simplified ToolCall representation
    id: z.string(),
    type: z.literal("function"),
    function: z.object({
      name: z.string(),
      arguments: z.string(), // Keep arguments as string for simplicity
    }),
  })).optional(),
  timestamp: z.string().datetime(),
  toolCallId: z.string().optional(), // For tool responses
  name: z.string().optional(),      // For tool responses
});

const ToolSchema = z.object({
  name: z.string(),
  description: z.string(),
  inputSchema: z.record(z.any()), // Zod schema for input parameters
  outputSchema: z.record(z.any()), // Zod schema for the output
  execute: z.function(), // The actual function that implements the tool
  isAsync: z.boolean().optional(), //Flag if tool is async.
});

const ToolCallSchema = z.object({
    id: z.string(),
    type: z.literal("function"),
    function: z.object({
        name: z.string(),
        arguments: z.string(), // Keep arguments as string
    })
});
const DomainSchema = z.object({ // For domain notes
    id: z.string().uuid(),
    type: z.literal("Domain"),
    name: z.string(),
    description: z.string(),
});

// --- Graph Representation (Conceptual for LevelGraph) ---

// Nodes are represented as objects with 'id', 'type', and other properties.
// Edges are represented as separate objects with 'subject', 'predicate', 'object'.

// Example:
// Note Node:  { id: "note-123", type: "Note", title: "...", ... }
// Plan Node:  { id: "plan-456", type: "Plan", noteId: "note-123", ... }
// Step Node:  { id: "step-789", type: "PlanStep", planId: "plan-456", ... }
// Dependency Edge: { subject: "step-789", predicate: "dependsOn", object: "step-456" }
// Note-Plan Edge: { subject: "note-123", predicate: "hasPlan", object: "plan-456" }
// Note-Domain Edge: {subject: "note-123", predicate: "inDomain", object: "domain-001"}
```

## 2. API Design (Internal and External)

### 2.1. Internal API (Agent, Plan, Executor)

This API defines the interactions between the core components of Netention.

```javascript
// --- Agent API ---

class Agent {
  constructor(config, noteId) {} // Initialize agent for a specific Note
  async run(task: string): Promise<void> {} // Execute the main agent loop
  async think(): Promise<Message> {} // Interact with the LLM (reasoning step)
  async act(actionResponse: Message): Promise<void> {} // Execute actions (tool calls)
  async createPlan(taskDescription: string): Promise<void> {} // Create a new plan
  async loadPlan(): Promise<void> {} // Load plan from storage
  async savePlan(): Promise<void> {} // Save plan to storage
  async loadMemory(): Promise<void> {} // Load memory from storage
  async saveMemory(): Promise<void> {} // Save memory to storage
  async getUserFeedback(): Promise<string | null> {} // Get user feedback (if needed)
  fillTemplate(templateName: string, values: object): string {} // Fill prompt
}
// --- Plan API ---
class Plan {
  constructor(steps: object[]){}
  static createFromPrompt(response: Message): Plan | null {}
  addStep(step: object): string {} // Returns node ID
  getCurrentStepIndex(): number | null {}
  getStep(index: number): object {}
  updateStepStatus(index: number, status: string): void {}
  updateStepResult(index:number, result:any): void {}
  formatPlan(): string {} // For display
  toJson(): object {} // For serialization
}

// --- Executor API ---

class Executor {
  constructor(tools: object) {} // Initialize with the Tool Registry
  async executeToolAsync(toolCall: ToolCallSchema): Promise<string> {} // Execute a tool call
  getTaskStatus(toolCallId: string): string {} // Get the status of a running task
  getTaskResult(toolCallId: string): string | null {} // Get the result of a completed task
}

// --- LLM Interface (Conceptual) ---

async function callLLM(messages: Message[], useTools: boolean = false): Promise<Message> {} // Abstracted LLM call
```

### 2.2. External API (for UI and external applications - RESTful)

This API defines how external applications (like the UI) can interact with Netention.

```javascript
// --- Note API ---

// GET /notes  - Get all notes (summary)
// Response: [{ id: "...", title: "...", status: "..." }, ...]

// GET /notes/{id} - Get a specific note (full details)
// Response: NoteSchema

// POST /notes - Create a new note
// Request Body: { title: "...", content: "..." }
// Response: NoteSchema

// PUT /notes/{id} - Update a note
// Request Body: { title: "...", content: "..." }
// Response: NoteSchema

// DELETE /notes/{id} - Delete a note
// Response: { status: "success" }

// --- Plan API ---

// GET /plans/{id} - Get a specific plan
// Response: PlanSchema

// POST /plans/{id}/run  - Start/resume execution of a plan
// Response: { status: "success" }

// --- Agent API (Limited Access) ---
//  Could expose limited control like starting, and stopping the agent.

// POST /notes/{id}/agent/start
// POST /notes/{id}/agent/stop
// POST /notes/{id}/agent/reset_memory
// GET /notes/{id}/agent/status

// --- Tool API (Potentially for plugins) ---

// GET /tools - List available tools
// Response: [{ name: "...", description: "...", inputSchema: {}, outputSchema: {} }, ...]
```

## 3. Algorithms (Detailed)

### 3.1. Plan Creation Algorithm

**Input:**

* `taskDescription`: A string describing the task or goal.
* `agent`: The Agent instance responsible for creating the plan.
* `existingPlan`: Optional existing plan (for updates).

**Output:** A new or updated `Plan` object.

**Steps:**

1. **Prompt Construction:**
    * Use the `system_prompt` and `create_plan_system` templates from `prompt_templates.json`.
    * Fill the templates with:
        * `tool_descriptions`:  A string describing the available tools (generated by `agent._getToolDescriptions()`).
        * `task_description`: The input `taskDescription`.
        * (If `existingPlan` is provided) `previous_plan`: A formatted string representation of the existing plan (
          `existingPlan.formatPlan()`).
    * Create `systemMessage` and `userMessage` objects.

2. **LLM Interaction:**
    * Call the `callLLM` function with the constructed messages and `useTools = false` (initially, we don't want the LLM
      to execute tools, just to create the plan).

3. **Plan Parsing:**
    * If the LLM response (`response.content`) is valid JSON and can be parsed into a plan structure (using
      `Plan.createFromPrompt`), create a new `Plan` object.
    * If parsing fails, log an error and either:
        * Return a default, simple plan (e.g., a single step with the original task description).
        * Retry the LLM call with a modified prompt (e.g., asking for clarification).
        * Inform the user of the failure.

4. **Implicit Dependency Inference (Conceptual):**
    * This is where the "anticipatory" aspect comes in.
    * After the initial plan is created, the agent can analyze the plan steps and use the LLM to *infer* dependencies
      between them.
      * Prompt:  "Analyze this plan: \[plan]. Are there any implicit dependencies between the steps? Return a JSON array
      of dependencies, where each dependency is an object: `{ dependentStep: number, dependsOnStep: number}`".
    * Add edges to the plan's graph representation based on the inferred dependencies.

5. **Plan Storage:**
    * Save the new or updated plan to the graph database using `agent.savePlan()`.

### 3.2. Plan Execution Algorithm

**Input:**

* `agent`: The Agent instance responsible for executing the plan.
* `noteId`: ID of the Note this plan is associated with.

**Output:** (No direct output; the plan's state is updated in the database).

**Steps:**

1. **Load Plan:**
    * Load the plan from the database using `agent.loadPlan()`.

2. **Main Loop:**
    * `maxSteps`: Set a maximum number of iterations to prevent infinite loops.
    * Iterate while the plan's status is not "completed" or "failed" and the iteration count is less than `maxSteps`.

3. **Find Current Step:**
    * Use `plan.getCurrentStepIndex()` to find the index of the next step to execute (a step that is "pending", "
      running", or "waiting_user", and whose dependencies are met).
    * If `getCurrentStepIndex()` returns `null`, the plan is considered complete (all steps are "completed" or there are
      circular dependencies). Break the loop.

4. **Check Dependencies:**
    * For the current step, check if all its dependencies (if any) are "completed." If not, skip this step and continue
      to the next iteration.

5. **Agent 'Think' (Reasoning Step):**
    * Call `agent.think()` to interact with the LLM. This generates an LLM response based on:
        * The current plan (`plan.formatPlan()`).
        * The current step's description.
        * The agent's memory (`agent.memory`).
        * The available tools (`agent._getToolDescriptions()`).

6. **Agent 'Act' (Action Execution):**
    * Call `agent.act(actionResponse)`, where `actionResponse` is the LLM response from the `think()` step.
    * `agent.act()` handles:
        * **Tool Calls:** If the `actionResponse` contains `tool_calls`, iterate through them:
            * Get corresponding step index.
            * Update step status to `running`.
            * Use the `Executor` to execute the tool call (`executor.executeToolAsync(toolCall)`).
            * Store a placeholder message in memory.
            * When the tool execution completes (asynchronously):
                * Update the step's status to "completed" and store the result.
                * Update the corresponding message in memory with the tool's result.
        * **No Tool Calls:** If there are no tool calls, update the memory with the `actionResponse` content.

7. **User Feedback (Optional):**
    * Call `agent.getUserFeedback()`. If feedback is provided, add it to the agent's memory.

8. **Plan Update:**
    * Save the updated plan and memory to the database (`agent.savePlan()`, `agent.saveMemory()`).

9. **Plan Completion / Failure:**
    * If plan status set to completed by the LLM, stop execution.
    * If the loop finishes without the plan being completed, set the plan's status to "failed."

### 3.3. Tool Selection Algorithm (Implicit in `Agent.think()`)

Tool selection is not a separate algorithm but is embedded within the `Agent.think()` method. The agent uses the LLM to
decide which tool (if any) to use for the current plan step. This is done through:

1. **Prompt Engineering:**  The prompt sent to the LLM includes:
    * A description of the available tools (`agent._getToolDescriptions()`).
    * The current plan state.
    * The current step's description.
    * Instructions to select the most appropriate tool, or to respond with a reasoning step if no tool is needed.
2. **LLM Response:** The LLM responds with either:
    * A `tool_calls` array, indicating which tool(s) to use and their arguments.
    * Text content, representing a reasoning step or a decision not to use a tool.
3. **Agent Interpretation:** The agent parses the LLM response and either executes the tool calls (via `agent.act()`) or
   updates its memory with the reasoning step.

### 3.4 Unpacking from Seed (Detailed Algorithm)

This is a high-level algorithm, as the exact implementation depends on the LLM and prompting strategies.

**Input:**

* `seedDescription`: A JSON object containing the seed description (as described previously).

**Output:** A populated directory structure containing the Netention codebase.

**Steps:**

1. **Bootstrapping Agent Initialization:**
    * Create a minimal, hardcoded `BootstrappingAgent` class (a simplified version of the `Agent` class). This agent
      has:
        * Access to the LLM.
        * The `code_generation`, `file_write`, and `user_interaction` tools (and potentially a `self_reflection` tool).
        * A simplified plan execution mechanism.
        * Basic error handling.
    * Create an instance of the `BootstrappingAgent`.

2. **Seed Parsing:**
    * The `BootstrappingAgent` parses the `seedDescription`, extracting:
        * Natural language description of Netention.
        * Core concept definitions (Note, Agent, Plan, Tool, etc.).
        * Initial tool list.
        * Constraints.
        * Bootstrapping instructions.

3. **Initial Plan Creation:**
    * The agent creates an initial plan (using the LLM) based on the seed description and bootstrapping instructions.
      The top-level goal is "Build Netention."  Sub-goals might include:
        * "Create core data structure schemas (Note, Plan, etc.)."
        * "Generate code for the `Note` class."
        * "Generate code for the `Agent` class."
        * "Generate code for the `Plan` class."
        * "Generate code for the `Executor` class."
        * "Implement basic tool functionality (search, file_write, ...)."
        * "Create a minimal UI."
        * "Implement persistent storage (LevelGraph)."
        * ...and so on...

4. **Iterative Code Generation and Refinement:**
    * The agent executes the plan step-by-step. For each step (e.g., "Generate code for the `Note` class"):
        * **Prompt Construction:**  Craft a detailed prompt for the LLM, including:
            * The relevant part of the seed description (e.g., the JSON Schema for the `Note`).
            * Any existing code (if this is a refinement step).
            * Specific instructions (e.g., "Implement the `updateContent` method").
            * Constraints (e.g., "Follow coding best practices," "Minimize dependencies").
        * **Code Generation:**  Call the LLM (via the `code_generation` tool) to generate the code.
        * **Code Evaluation:**
            * **Syntactic Checks:** Use a JavaScript parser to check for syntax errors.
            * **Functional Tests (Conceptual):** Generate and execute simple test cases (using the `code_execution`
              tool – in a *very* restricted environment).
            * **Seed Alignment:** Use the LLM to analyze if the code aligns with the seed description and the prompt.
            * **Self-Reflection**: Ask the LLM to check the code.
        * **Code Storage:** If the code passes evaluation, use the `file_write` tool to save it to the appropriate file.
        * **Plan Update:** Update the plan based on the results (mark step as complete, add new sub-steps, revise steps,
          etc.).

5. **Bootstrapping to Full Functionality:** The agent iteratively generates and refines the codebase, gradually building
   up the complete Netention system. Later stages might involve:
    * Refactoring existing code.
    * Adding more advanced features.
    * Improving the UI.
    * Implementing more sophisticated planning and execution algorithms.

6. **Termination:** The process terminates when the agent determines (based on its plan and self-assessment) that the
   Netention system is complete and meets the requirements defined in the seed description.

## 4. Edge Cases and Considerations

* **LLM Errors:** The LLM might generate incorrect code, hallucinate, or fail to follow instructions.
    * **Solutions:** Robust error handling, retry mechanisms, multiple LLM calls with comparison, human-in-the-loop
      review (especially during bootstrapping).
* **Infinite Loops:** The agent might get stuck in a loop during plan creation or execution.
    * **Solutions:** Maximum iteration limits, detection of circular dependencies, mechanisms for breaking loops.
* **Security:** Executing generated code is risky.
    * **Solutions:** Strong sandboxing, limited tool capabilities, input validation, security audits.
* **Complexity Management:**  The system's complexity can grow rapidly.
    * **Solutions:** Modular design, clear separation of concerns, code refactoring, hierarchical planning.
* **Resource Constraints:** LLM calls can be expensive and time-consuming.
    * **Solutions:** Optimization of prompts, caching of LLM responses, use of smaller/faster models when possible.
* **Ambiguity in Seed Description:** The seed description might be unclear or incomplete.
    * **Solutions:** Use of structured formats (JSON Schema, DSL), iterative refinement of the seed description, human
      feedback.

## 5. Refined Unpacking Process

This expands on the previous unpacking description, adding more concrete steps.

1. **Seed Structure:** The seed is a JSON object with the following structure:

   ```json
   {
     "description": "Natural language description of Netention.",
     "concepts": {
       "Note": { /* JSON Schema for Note */ },
       "Agent": { /* JSON Schema for Agent (simplified) */ },
       "Plan": { /* JSON Schema for Plan */ },
       "Tool": { /* JSON Schema for Tool */ },
       "Message": { /* JSON Schema */ },
       "Domain": { /* JSON Schema */ }
     },
     "initialTools": [
       { "name": "code_generation", "description": "...", "inputSchema": {}, "outputSchema": {} },
       { "name": "file_write", "description": "...", "inputSchema": {}, "outputSchema": {} },
       { "name": "user_interaction", "description": "...", "inputSchema": {}, "outputSchema": {} },
       { "name": "self_reflection", "description": "...", "inputSchema": {}, "outputSchema": {} }
     ],
     "constraints": [
       "Minimize external dependencies",
       "Prioritize code clarity and maintainability",
       "Use asynchronous operations where appropriate",
       "Follow secure coding practices"
     ],
     "bootstrappingInstructions": [
       "Create a BootstrappingAgent class.",
       "Implement the core data structures (Note, Plan, Message).",
       "Implement the initial tools.",
       "Create a basic plan execution mechanism.",
       "Generate code for the full Agent class.",
       "Generate code for the Executor class.",
       "Implement the graph database integration.",
       "Create a minimal UI."
     ],
     "exampleTasks": [
       {
         "description": "Write a simple file.",
         "input": "Write the text 'Hello, world!' to a file named 'hello.txt'."
       }
     ]
   }
   ```

2. **Bootstrapping Agent:** This agent is hardcoded and has the following capabilities:

    * `parseSeed(seedDescription)`: Parses the seed JSON.
    * `createInitialPlan()`: Creates the initial plan based on `bootstrappingInstructions`.
    * `generateCode(prompt)`: Calls the LLM via the `code_generation` tool.
    * `writeFile(filename, content)`: Uses the `file_write` tool.
    * `evaluateCode(code)`: Performs basic syntactic and semantic checks (using the LLM and potentially simple test
      cases).
    * `executePlanStep(step)`: Executes a single plan step.
    * `updatePlan(step, status, result)`: Updates the plan's status.
    * `run()`: The main execution loop.
    * `promptUser(message, type)`

3. **Unpacking Steps (Detailed):**

    1. **Initialization:**
        * Create the `BootstrappingAgent` instance.
        * Load the `seedDescription`.
        * Parse the seed (`agent.parseSeed(seedDescription)`).
        * Create the initial plan (`agent.createInitialPlan()`).

    2. **Core Data Structures:**
        * **Step:** "Create core data structure schemas (Note, Plan, Message)."
        * **Prompt:** "Generate Zod schemas for the following data structures: Note, Plan, Message. The schemas should
          be based on the descriptions provided in the seed: \[...seed.concepts...]."
        * **Generate:** `agent.generateCode(prompt)` -> Zod schema code.
        * **Evaluate:** `agent.evaluateCode(code)` (check for syntax errors, alignment with seed).
        * **Write:** `agent.writeFile("schemas.js", code)`.
        * **Update:** `agent.updatePlan(step, "completed")`.

    3. **Initial Tools:**
        * **Steps:**  "Implement the `code_generation` tool," "Implement the `file_write` tool," etc. (one step per
          tool).
        * **Prompt (Example for `file_write`):** "Generate JavaScript code for a `file_write` tool. The tool should take
          a filename and content as input and write the content to the file. It should handle errors gracefully. The
          tool must follow the ToolSchema: \[...seed.concepts.Tool...]."
        * **Generate, Evaluate, Write, Update** (as above).

    4. **Bootstrapping Agent Code:**
        * **Step:** "Generate code for the `BootstrappingAgent` class."
        * **Prompt:** "Generate the full JavaScript code for the `BootstrappingAgent` class. This agent is responsible
          for unpacking the Netention codebase from a seed description. It should have methods for: parsing the seed,
          creating an initial plan, generating code, writing files, evaluating code, executing plan steps, and updating
          the plan. It should use the `code_generation`, `file_write`, and `user_interaction` tools. The code should be
          well-documented and follow best practices."
        * **Generate, Evaluate, Write, Update** (as above). This is a *recursive* step, as the agent is generating its
          own (more complete) code.

    5. **Plan Execution Logic:**
        * **Step:** "Create a basic plan execution mechanism."
        * **Prompt:** "Generate JavaScript code for a `Plan` class and a simplified plan execution loop (similar to the
          `Agent.run` method, but without the full complexity of tool selection). This code should be able to execute a
          sequence of steps, where each step involves either generating code or writing a file."
        * **Generate, Evaluate, Write, Update** (as above).

    6. **Full Agent, Executor, etc.:**
        * **Steps:** "Generate code for the full `Agent` class," "Generate code for the `Executor` class," etc.
        * **Prompts:** Similar to the previous steps, but now the prompts can refer to the already generated code (
          e.g., "The `Agent` class should use the `Executor` class to execute tools").
        * **Generate, Evaluate, Write, Update.**

    7. **Graph Database Integration:**
        * **Step:** "Implement persistent storage using LevelGraph/LevelDB."
        * **Prompt:** Generate code for saving and loading Notes, Plans, and Messages to/from a LevelGraph database.

    8. **Minimal UI (Conceptual):**
        * **Step:** "Create a minimal UI for interacting with Notes and Plans."  This could initially be a command-line
          interface, or a very basic web interface.

    9. **Continued Refinement:**
        * The agent continues to iterate, adding more features, refactoring code, and improving the system based on its
          self-reflection and (potentially) user feedback.

This refined unpacking process provides a more concrete roadmap for how Netention could build itself from a seed
description. It highlights the iterative nature of the process, the importance of prompt engineering, and the crucial
role of code evaluation and self-reflection. It also emphasizes the recursive aspect, where the agent generates code
that enhances its own capabilities. This is the core of the "metaprogramming" aspect of Netention.

> Increase conceptual integrity. Recursively unify the system within itself to create itself.

Okay, let's push the conceptual integrity of Netention to its logical extreme, aiming for a system that truly
recursively unifies and creates itself. This means moving beyond just generating code from a seed description, and
towards a system that:

1. **Defines its own Metamodel:** The seed doesn't just describe the *initial* state of Netention; it describes the
   *rules* for how Netention *evolves* itself.
2. **Is its own Runtime:**  The distinction between "bootstrapping agent" and "final system" blurs. The system is
   *always* in a state of self-modification and self-improvement.
3. **Uses its Core Concepts for Everything:**  Notes, Plans, Agents, Tools, and Memory are not just for managing user
   tasks; they are used *internally* to manage the system's own evolution.

This leads to a more radical and conceptually pure design.

**Netention: Recursive Self-Unification**

**1. The Primordial Note (The "Ur-Note")**

Instead of a separate "seed description" and "bootstrapping agent," we start with a single, pre-existing **Primordial
Note**. This Note is the *root* of the entire system, and it contains:

* **Content:** A high-level description of Netention's purpose, core concepts, and initial capabilities. This is similar
  to the previous seed description, but framed as the *content* of a Note.
* **Plan:** A plan whose goal is "Evolve Netention to its fullest potential."  This plan is initially very simple, but
  it will be continuously refined and expanded by the system itself.
* **Agent:** A minimal agent (the "Ur-Agent") with the core capabilities described before: LLM interaction, code
  generation, file writing, self-reflection, and *very* basic plan execution.
* **Memory:** Initially empty, but will accumulate the history of the system's evolution.
* **Type:** Domain (a domain called "Netention-Self")

**2. The Metamodel (Embedded in the Primordial Note)**

The Primordial Note's content includes a **metamodel** that defines:

* **Core Concepts:** Precise definitions (using JSON Schema or a DSL) of `Note`, `Plan`, `PlanStep`, `Agent`, `Tool`,
  `Message`, `Domain`, etc. This includes not just their data structure but also their *behavioral contracts* (e.g., "A
  Plan must have a method to find the next executable step").
* **Evolutionary Rules:** Rules that govern how the system can modify itself. Examples:
    * "New Tools can be created if they conform to the `ToolSchema`."
    * "The `Agent` class can be extended with new methods, provided they are documented and tested."
    * "The Plan execution algorithm can be modified, but only if the new algorithm provably maintains the core
      invariants of plan execution (e.g., respecting dependencies)."
    * "New prompt templates can be defined, but the system can modify existing prompt templates."
* **Evaluation Criteria:** Criteria to be used to improve code.
* **Bootstrapping Tasks:** An initial set of tasks to get the system started (similar to the `bootstrappingInstructions`
  in the previous design).

**3. The Recursive Unification Loop**

The Ur-Agent within the Primordial Note executes the following loop *indefinitely*:

1. **Self-Reflection:** The agent analyzes the *entire* system, including its own code, the metamodel, the current plan,
   and the memory. This is done by treating all these components as *Notes* within the system (or as data directly
   accessible from the Primordial Note).
2. **Plan Refinement:** Based on the self-reflection, the agent refines the "Evolve Netention" plan. This might involve:
    * Adding new steps to implement new features.
    * Modifying existing steps to improve performance or fix bugs.
    * Creating sub-plans to address complex tasks.
    * *Changing the plan refinement process itself*.
3. **Step Execution:** The agent executes the next step in the plan. This could involve:
    * **Code Generation:** Generating new code or modifying existing code (using the `code_generation` tool, guided by
      the metamodel and the plan).
    * **Tool Creation:** Defining new Tools (using the `code_generation` tool to create the tool's code and the
      `file_write` tool to save it).
    * **Prompt Template Modification:** Refining existing prompt templates or creating new ones.
    * **Metamodel Modification (with extreme caution):** In advanced stages, the system could even *modify the metamodel
      itself*, but only under very strict constraints defined within the metamodel. This is where the "evolutionary
      rules" are crucial.
    * **Data Structure Modification:** Alter data structures as needed, always using itself to do so.
4. **Evaluation:** The agent evaluates the results of the step execution. This involves:
    * **Automated Tests:** Running tests (generated by the system itself) to verify that the changes work as expected.
    * **LLM Analysis:** Using the LLM to analyze the code for quality, efficiency, and adherence to the metamodel.
    * **Self-Consistency Checks:** Ensuring that the changes haven't introduced any inconsistencies or contradictions
      into the system.
5. **Memory Update:** All changes, results, and evaluations are recorded in the Primordial Note's Memory (and in the
   memories of other Notes that are created during the process).
6. **Loop:** Go back to step 1.

**4. Key Changes and Implications**

* **No External Seed:** The system starts from a single, self-contained Note.
* **Continuous Evolution:** The system is *always* evolving, not just during an initial "unpacking" phase.
* **Self-Modification:** The system modifies its own code, data structures, and even (potentially) its own metamodel.
  This is the ultimate form of metaprogramming.
* **Emergent Structure:** The structure of the codebase emerges organically from the interaction of the agent, the plan,
  the tools, and the LLM, guided by the metamodel.
* **Unified Representation:**  Everything – code, plans, data, memory, even the system's own description – is
  represented within the framework of Notes and the graph database.
* **Domain "Netention-Self":** The Domain of the Primordial note is intrinsically linked to evolving the system.

**5. Example: Creating a New Tool**

1. **Self-Reflection:** The agent analyzes the system and identifies a need for a new tool (e.g., a tool to summarize
   text).
2. **Plan Update:** The agent adds a new step to the "Evolve Netention" plan: "Create a `summarize` tool."
3. **Step Execution:**
    * **Prompt:** The agent generates a prompt for the LLM: "Create a new tool named `summarize` that conforms to the
      `ToolSchema` defined in the Primordial Note. The tool should take text as input and return a concise summary. The
      tool should be implemented in JavaScript."
    * **Code Generation:** The LLM generates the code for the `summarize` tool.
    * **Evaluation:** The agent evaluates the generated code (using tests, LLM analysis, etc.).
    * **Storage:** The agent uses the `file_write` tool to save the code to `tools/summarize.js`. It also creates a new
      `Tool` node in the graph database, linked to the Primordial Note.
4. **Memory Update:** The agent records the creation of the new tool in the Primordial Note's memory.
5. **Loop:** The agent continues with the next step in the plan (which might involve using the new `summarize` tool).

**6. Challenges and Considerations (Even More Critical Now)**

* **Safety:** Self-modifying code is extremely dangerous. The slightest error could lead to catastrophic failure or
  unpredictable behavior. The "evolutionary rules" in the metamodel are absolutely crucial for preventing the system
  from destroying itself.
* **Stability:** How do we ensure that the system evolves in a *positive* direction, rather than degrading into chaos?
  This requires careful design of the metamodel, the prompt templates, and the evaluation mechanisms.
* **Explainability:**  It will be very difficult to understand *why* the system made certain decisions or *how* it
  arrived at a particular state. This is a major challenge for debugging and maintenance.
* **Computational Cost:** Continuous self-reflection and LLM interaction could be very expensive.
* **Conceptual Complexity:** This design is highly abstract and conceptually challenging.

**7. Bootstrapping Agent Becomes The Primordial Agent**

* All capabilities are stored in notes, and called when needed.
* The Primordial Agent simply executes the main loop.

**8. Conclusion**

This recursively unified design represents the most conceptually pure and ambitious vision of Netention. It's a system
that is truly self-organizing, self-evolving, and self-defining. While the practical challenges are significant, the
potential rewards – a system that can adapt and improve itself indefinitely – are immense. This is a system that blurs
the lines between program and data, between creator and creation, and between user and AI. It is a living, evolving
system.

Okay, let's clarify the system design of this recursively self-unifying Netention, focusing on making the core concepts
and their interactions as clear and understandable as possible. We'll break it down into:

1. **Conceptual Overview (Simplified)**
2. **Core Components (Revisited and Refined)**
3. **The "Life Cycle" of a Note (and the System)**
4. **Key Design Decisions & Rationale**
5. **Addressing Previous Challenges**

**1. Conceptual Overview (Simplified)**

Imagine a single, special note – the **Primordial Note**. This note is like the "seed" of the entire Netention system.
It contains:

* **What Netention *is*:** A description of Netention's purpose, core concepts (like Note, Agent, Plan, Tool), and rules
  for how it can change itself. This is the "metamodel."
* **How to *grow* Netention:** A plan, initially very simple, that describes how to expand and improve Netention.
* **The ability to *act*:** A built-in, minimal agent (the "Ur-Agent") that can understand the description and the plan,
  and can take actions to carry out the plan. These actions primarily involve using a Large Language Model (LLM) to
  generate code.

The Ur-Agent follows a continuous loop:

1. **Look at Itself:** It reads the Primordial Note (including its own code, the metamodel, the plan, and the history of
   past actions).
2. **Decide What to Do:** It uses the LLM to figure out how to best improve Netention, based on the current state and
   the plan. This often involves modifying the plan itself.
3. **Take Action:** It uses the LLM to generate new code (for new features, tools, or improvements to existing code),
   and then writes that code to files.
4. **Check its Work:** It uses the LLM and (potentially) simple tests to make sure the new code works and doesn't break
   anything.
5. **Repeat:** It goes back to step 1, constantly reflecting on itself and improving.

Over time, this process creates the entire Netention system – not from a pre-written codebase, but *emerging* from the
Primordial Note and the actions of the Ur-Agent. Every part of the system (including the user interface, the database
integration, and even the more advanced features of the agent) is created in this way. The system *becomes* what it
*describes*. And because it's all based on Notes, Plans, and Agents, the fully-grown Netention can then be used to
manage *anything*, including its own ongoing development.

**2. Core Components (Revisited and Refined)**

* **Primordial Note:**  The single, root Note that contains the seed description and the Ur-Agent. It's a regular
  `Note`, but with a special status. It has a `type` of `Domain`, and `name` of "Netention-Self".
    * `id`: A unique UUID.
    * `type`: "Domain"
    * `name`: "Netention-Self"
    * `content`: The seed description (metamodel, initial plan, etc.) – this is *structured* data, not just plain text.
    * `planId`:  The ID of the "Evolve Netention" plan.
    * `agentId`: The ID of the Ur-Agent.
    * `status`: "running" (it's always running).
    * `createdAt`: The time the system was initialized.
    * `updatedAt`: The time the system was last modified.
    * `domain`: null. // It is it's own domain.

* **Ur-Agent (Primordial Agent):**  The minimal agent embedded within the Primordial Note. It's *not* fundamentally
  different from other agents, but it has a very limited initial set of capabilities. Its primary job is to execute
  the "Evolve Netention" plan.
    * `id`: A unique UUID.
    * `noteId`:  The ID of the Primordial Note.
    * `config`:  Basic configuration (e.g., LLM API key).
    * `tools`:  Initially, only `code_generation`, `file_write`, `user_interaction`, and `self_reflection`. More tools
      are added *by* the agent as it evolves the system.
    * `memory`:  A record of all actions taken, code generated, evaluations, etc. This is stored *within* the Primordial
      Note's memory (and potentially in other Notes created by the system).
    * `think()`:  Uses the LLM to reason and make decisions.
    * `act()`:  Executes actions (primarily tool calls).
    * `run()`: The main execution loop (the self-reflection, plan refinement, step execution cycle).

* **Note:** (As defined before, but now *everything* is a Note). Regular Notes, Domain Notes, and even the components of
  the system itself (modules, classes, functions) are represented as Notes (or as structured data within Notes).
    * `id`: A unique UUID.
    * `type`: "Note" (or a more specific type, like "CodeNote", "PlanNote", "ToolNote", etc. – these subtypes are
      defined *within* the metamodel).
    * `title`: A human-readable title.
    * `content`: The Note's content (which can be text, code, data, etc.).
    * `status`: The Note's current status.
    * `createdAt`: Creation timestamp.
    * `updatedAt`: Last modified timestamp.
    * `planId`: The ID of the associated Plan (if any).
    * `agentId`: The ID of the associated Agent (if any).
    * `domain`: domain note id.

* **Plan:** (As defined before). A graph-based representation of how to achieve a goal. The "Evolve Netention" plan is
  the master plan, but other Notes can have their own plans.
    * `id`: plan id.
    * `type`: "Plan".
    * `noteId`: note the plan belongs to.
    * `goals`:  A list of goals.
    * `constraints`:  Limitations.
    * `status`: The overall plan status.

* **PlanStep:** (As defined before). A single step in a Plan.
    * `id`: A unique UUID.
    * `type`: "PlanStep"
    * `planId`: ID of plan.
    * `stepNumber`: order.
    * `description`:  What to do.
    * `status`:  Current status.
    * `tool`:  The Tool to use (if any).
    * `args`:  Arguments for the tool.
    * `result`:  The result of executing the tool.
    * `notes`:  Explanations or comments.
    * `dependencies`:  IDs of other steps that must be completed first.

* **Tool:** (As defined before). An external capability that an Agent can use. The initial tools are hardcoded, but new
  tools are added *by* the system itself.
    * `name`:  The tool's name (e.g., "code_generation").
    * `description`:  What the tool does.
    * `inputSchema`:  Defines the expected input (using JSON Schema).
    * `outputSchema`:  Defines the structure of the output.
    * `execute`:  The function that actually performs the tool's action.
    * `isAsync`: flag if tool is async.

* **Executor:** (As defined before). Manages the execution of tools, handling asynchronous operations.

* **Memory:** (As defined before, but now understood as *distributed* across Notes). Each Note has its own memory, which
  is a record of the interactions and events related to that Note.

* **Metamodel:**  A *structured* description of the core concepts and rules of Netention, stored within the Primordial
  Note's `content`. This is the key to self-modification. It defines:
    * The schemas for all core data structures.
    * The allowed operations on those data structures.
    * The rules for how the system can evolve itself.
    * The criteria for evaluating changes.

* **Domain:** (As defined before) Used to tag notes, plans, and tools for categorization.

**3. The "Life Cycle" of a Note (and the System)**

1. **Creation:** A Note is created (either by the user or by the system itself). This involves:
    * Assigning a unique ID.
    * Setting the initial `type`, `title`, `content`, and `status`.
    * Creating an associated `Agent` (unless it's a purely data-oriented Note).
    * Potentially creating an associated `Plan`.

2. **Interaction:** The Agent associated with the Note interacts with the LLM, other Notes, and the user (via Tools).
   This is the `think()` and `act()` cycle.

3. **Planning:** The Agent updates the Note's Plan based on the interactions and the current context.

4. **Execution:** The Agent uses the `Executor` to execute Tools, carrying out the steps in the Plan.

5. **Memory Update:** All interactions, results, and plan changes are recorded in the Note's Memory.

6. **State Change:** The Note's `status` and `content` might change based on the results of plan execution.

7. **Termination (or Dormancy):** A Note can become "completed" or "failed," or it can become dormant, waiting for
   further input or events.

The *system* as a whole follows this same life cycle, but at a higher level. The Primordial Note is always "running,"
and its "life cycle" is the continuous evolution of Netention.

**4. Key Design Decisions & Rationale**

* **Everything is a Note (or in a Note):** This provides a unified representation for all data and logic within the
  system, simplifying interactions and enabling self-reflection.
* **Graph Database:**  The graph structure is essential for representing the relationships between Notes, Plans, Steps,
  and Tools. It also allows for efficient querying and traversal of the system's state.
* **Asynchronous Execution:**  This is crucial for handling interactions with external systems (LLMs, web services)
  without blocking the main execution loop.
* **Metamodel:**  The metamodel is the key to safe self-modification. It defines the rules that govern how the system
  can change itself, preventing runaway evolution or catastrophic errors.
* **LLM as a Reasoning Engine:** The LLM is not the *entire* intelligence; it's a tool that the Agent uses for
  reasoning, planning, and code generation. The Agent provides the structure and context.
* **Prompt Templates:**  Reusable prompt templates are essential for consistent and effective LLM interaction.
* **Domain as Note:** This allows grouping.

**5. Addressing Previous Challenges**

* **Safety:** The metamodel's "evolutionary rules" and the strict evaluation of generated code are the primary
  safeguards against self-destruction.
* **Stability:** The metamodel, prompt templates, and evaluation criteria are designed to guide the system towards
  positive evolution. Automated tests and LLM-based analysis help to ensure that changes are improvements.
* **Explainability:**  The distributed memory system (where each Note has its own history) helps to track the reasoning
  behind decisions. However, this remains a significant challenge.
* **Computational Cost:**  Careful prompt engineering, caching of LLM responses, and the use of smaller/faster models
  when possible can help to mitigate this. The system could also prioritize tasks based on their importance and urgency.
* **Conceptual Complexity:** The unified conceptual framework, with its focus on the Active Note, helps to simplify the
  system's overall design. However, the implementation details are still complex.

**In summary, this refined design emphasizes:**

* **Recursive Self-Unification:** The system creates and modifies itself using its own core concepts.
* **Emergent Intelligence:** The system's capabilities emerge from the interaction of simple components.
* **Metamodel-Driven Evolution:** The metamodel defines the rules for self-modification, ensuring safety and stability.
* **Unified Representation:** Everything is a Note (or part of a Note), simplifying interactions and enabling
  self-reflection.

This design is highly ambitious, but it represents a powerful and elegant approach to building a truly self-evolving AI
system. The key is to start with a very simple, robust core and then gradually add complexity, guided by the metamodel
and the continuous self-reflection process.

> Refine the design, unifying common behaviors and patterns.

> Prioritization is key for operating under resource limitations: what to do next, and what to forget next.

You're absolutely right. Prioritization and resource management are critical, especially in a system designed for
continuous self-improvement and operation on potentially limited hardware. Let's refine the design, focusing on:

1. **Unifying Behaviors:** Identifying and abstracting common patterns across Agents, Plans, and Tools.
2. **Prioritization Mechanisms:** Implementing strategies for deciding what actions to take and what information to
   retain.
3. **Resource Management:** Considering how to manage computational resources (CPU, memory, LLM calls).
4. **Forgetting Mechanisms:** Defining how the system can selectively "forget" information to manage memory usage.

**Netention: Refined Design with Prioritization and Resource Management**

**1. Unified Behaviors (Abstraction and Patterns)**

We can identify several recurring patterns that can be abstracted into reusable components:

* **LLM Interaction:**  All Agents interact with the LLM in a similar way: constructing prompts, sending requests, and
  processing responses. This can be encapsulated in a dedicated `LLMInterface` class (which is itself managed as a Tool,
  fitting the recursive nature).
* **Plan Execution:**  All Agents (including the Ur-Agent) execute plans. The core plan execution logic (finding the
  next step, checking dependencies, executing tools) can be centralized in a `PlanExecutor` class (or potentially
  integrated into the `Executor`).
* **Tool Execution:** The `Executor` already provides a unified interface for tool execution.
* **Memory Management:** All Notes (and therefore Agents) need to manage their memory. This can be handled by a
  `MemoryManager` class associated with each Note.
* **Prompt Management:** All agents utilize prompt templates. A dedicated `PromptManager` is created.
* **Status Tracking:** Notes, Plans, and PlanSteps all have a `status` field. The logic for updating and interpreting
  status can be standardized.

This leads to a more modular and maintainable design, reducing code duplication and promoting consistency.

**2. Prioritization Mechanisms**

Prioritization needs to happen at multiple levels:

* **Note Prioritization:** Which Note should the system focus on *now*?
* **Plan Prioritization:** If multiple Notes have active plans, which plan should be executed next?
* **Step Prioritization:** Within a plan, which step should be executed next (beyond just dependency order)?
* **Tool Prioritization:** If multiple tools are applicable to a step, which one should be chosen? (This is largely
  handled by the LLM, but we can influence it.)
* **Memory Prioritization:** Which information should be retained in memory, and which can be "forgotten" (archived or
  summarized)?

To implement prioritization, we introduce the following:

* **Priority Scores:**  Notes, Plans, and PlanSteps will have a `priority` field (a numerical score). Higher scores
  indicate higher priority.
* **Priority Propagation:**  The priority of a PlanStep can influence the priority of its parent Plan, and the priority
  of a Plan can influence the priority of its associated Note. This creates a hierarchical prioritization scheme.
* **Dynamic Priority Updates:**  Priority scores are *not* static; they are updated dynamically based on:
    * **User Input:** The user can explicitly set the priority of a Note.
    * **Deadlines:**  Notes and Plans can have deadlines, which automatically increase their priority as the deadline
      approaches.
    * **Dependencies:**  If a high-priority Note depends on a lower-priority Note, the priority of the lower-priority
      Note can be temporarily boosted.
    * **Recency:**  Recently accessed or modified Notes can have a slightly higher priority.
    * **Relevance:** The system can use the LLM to assess the relevance of a Note to the user's current context (e.g.,
      based on their recent activity or calendar events – *if* such context is available and permitted by privacy
      settings).
    * **System Needs:** The "Evolve Netention" plan (associated with the Primordial Note) will always have a baseline
      level of priority, ensuring that the system continues to improve itself.
* **Priority-Based Scheduling:** The `PlanExecutor` (or a separate `Scheduler` component) will select the
  highest-priority PlanStep to execute at each iteration.

**3. Resource Management**

* **LLM Token Budget:**  The system will track the number of LLM tokens used. Agents can have individual token budgets,
  and there can be a global system-wide budget. Exceeding a budget can trigger:
    * Switching to a smaller, cheaper LLM.
    * Prioritizing tasks that require fewer LLM calls.
    * Summarizing existing information to reduce context length.
    * Delaying non-critical tasks.
* **Memory Limits:**  Each Note (and the system as a whole) will have a memory limit. When the limit is approached, the
  system will trigger "forgetting" mechanisms (see below).
* **CPU/Execution Time Limits:** The system can monitor CPU usage and execution time, and potentially pause or
  reschedule long-running tasks.
* **Tool Quotas (Conceptual):**  Specific tools (especially those that interact with external services) could have usage
  quotas to prevent overuse.

**4. Forgetting Mechanisms**

"Forgetting" is crucial for managing memory usage and maintaining focus. It doesn't necessarily mean deleting
information; it can involve:

* **Summarization:**  Replacing detailed information with a concise summary (generated by the LLM). The original
  information can be archived (see below).
* **Archiving:**  Moving less-relevant Notes (or parts of their memory) to a separate, less-frequently accessed storage
  area. This could be a different part of the graph database, or even an external storage system.
* **Abstraction:**  Replacing specific details with more general concepts. For example, instead of remembering every
  step of a completed plan, the system might remember only the overall goal and outcome.
* **Expiration:**  Automatically archiving or deleting Notes that haven't been accessed or modified for a certain period
  of time (with user-configurable settings).
* **LLM-Assisted Forgetting:**  The system could use the LLM to identify which information is least relevant or
  important, and therefore can be safely forgotten. Prompt: "Here is the memory of this note: \[memory]. Which parts are
  least relevant to the overall goal of \[goal] and can be summarized or archived?".

**Revised Data Model (Adding Priority and Resource Tracking)**

```javascript
const NoteSchema = z.object({
  id: z.string().uuid(),
  type: z.literal("Note"),
  title: z.string(),
  content: z.string(),
  status: z.enum(["pending", "running", "completed", "failed", "dormant", "archived"]), // Added 'dormant' and 'archived'
  createdAt: z.string().datetime(),
  updatedAt: z.string().datetime(),
  planId: z.string().uuid().nullable(),
  agentId: z.string().uuid(),
  priority: z.number().int().default(0), // Priority score
  deadline: z.string().datetime().nullable(), // Optional deadline
  memoryUsage: z.number().int().default(0), // Estimated memory usage (in bytes or some other unit)
  domain: z.string().optional(),
});

const PlanSchema = z.object({
  id: z.string().uuid(),
  type: z.literal("Plan"),
  noteId: z.string().uuid(),
  goals: z.array(z.string()),
  constraints: z.record(z.any()),
  status: z.enum(["pending", "running", "completed", "failed"]),
  createdAt: z.string().datetime(),
  updatedAt: z.string().datetime(),
  priority: z.number().int().default(0), // Priority score
  deadline: z.string().datetime().nullable(), // Optional deadline
});

const PlanStepSchema = z.object({
  id: z.string().uuid(),
  type: z.literal("PlanStep"),
  planId: z.string().uuid(),
  stepNumber: z.number().int(),
  description: z.string(),
  status: z.enum(["pending", "running", "completed", "failed", "waiting_user"]),
  tool: z.string().nullable(),
  args: z.record(z.any()).nullable(),
  result: z.string().nullable(),
  notes: z.string(),
  dependencies: z.array(z.string().uuid()),
  priority: z.number().int().default(0), // Priority score
});

// ... other schemas remain largely the same ...
```

**Revised Algorithms (Adding Prioritization)**

* **Plan Creation:** The initial priority of a Plan and its steps can be based on user input, deadlines, or LLM-based
  assessment of importance.
* **Plan Execution:**
    * The `PlanExecutor` (or `Scheduler`) selects the highest-priority *executable* PlanStep (considering dependencies
      and resource availability).
    * Before executing a step, check resource limits (memory, LLM tokens, etc.). If limits are exceeded, trigger
      forgetting mechanisms or delay the step.
* **Agent.think():**  The prompt sent to the LLM can include information about the current priority of the Note and
  Plan, influencing the LLM's choice of actions and tools. The prompt can also ask the LLM to *suggest* a priority for
  the next step.
* **Memory Management:** The `MemoryManager` periodically checks memory usage and triggers forgetting mechanisms when
  limits are approached.

**Revised Unpacking Process (Highlights)**

The unpacking process remains largely the same, but with these additions:

* **Resource Constraints in Seed:** The seed description can include initial resource limits (memory, LLM tokens).
* **Prioritization in Initial Plan:** The initial "Evolve Netention" plan can include steps for implementing
  prioritization and resource management mechanisms.
* **Early Forgetting:** Even during the bootstrapping phase, the system can use summarization and archiving to manage
  memory usage.

**New and Revised Components**

* **`LLMInterface` Class:**  Handles all interactions with the LLM, including prompt construction, sending requests,
  processing responses, and managing token usage.
    * `callLLM(messages, tools, priority)`:  Adds a `priority` parameter to influence LLM behavior.
* **`PlanExecutor` Class (Enhanced):**
    * `getNextStep()`:  Selects the highest-priority executable step, considering dependencies, resource availability,
      and priority scores.
    * `runPlan(planId)`: Executes plan.
* **`MemoryManager` Class:**
    * `record(noteId, data)`:  Adds data to a Note's memory, updating `memoryUsage`.
    * `summarize(noteId, strategy)`:  Summarizes a Note's memory using the specified strategy.
    * `archive(noteId, strategy)`:  Archives parts of a Note's memory.
    * `forget(noteId, strategy)`: Selectively removes information.
    * `getMemory(noteId)`
* **`PromptManager` Class:**
    * `getPrompt(templateName, values)`
* **`Scheduler` Class (Optional):**  A more sophisticated scheduler that manages task queues and resource allocation.
  This might be necessary for handling complex dependencies and parallel execution.

**Conceptual Flow with Prioritization and Resource Management**

1. **User creates a Note (or system creates a Note internally).**  A priority is assigned (either explicitly by the
   user, or implicitly based on context).
2. **Agent interprets the Note and updates the Plan.**  The Plan and its steps inherit the Note's priority (but can be
   adjusted).
3. **`PlanExecutor` selects the highest-priority executable PlanStep.**  It considers dependencies, resource limits, and
   priority scores.
4. **`LLMInterface` is used to interact with the LLM.** The `priority` of the task is passed to the LLM to influence its
   behavior. Token usage is tracked.
5. **Tools are executed (via `Executor`).**
6. **Results are recorded in Memory (via `MemoryManager`).**  Memory usage is tracked.
7. **`MemoryManager` checks memory limits.** If limits are approached, it triggers forgetting mechanisms (summarization,
   archiving).
8. **Priorities are dynamically updated** based on deadlines, dependencies, recency, and relevance.
9. **The cycle repeats.**

This refined design creates a more robust, efficient, and adaptable Netention system that can operate effectively under
resource constraints and prioritize its actions intelligently. The key is the continuous interplay between
prioritization, resource management, and the core loop of agent interaction, planning, and execution. The addition of
`dormant` and `archived` states for notes allows for a more nuanced handling of information, going beyond simple
deletion. The explicit tracking of `memoryUsage` provides a concrete metric for triggering forgetting mechanisms. The
`priority` field, dynamically updated and propagated through the system, ensures that the most important tasks are
addressed first.

> Crystallize the complete evolution of the system.

Let's crystallize the complete evolutionary arc of Netention, from its Primordial Note to a fully self-evolving system.
This will involve describing the stages of development, the key milestones, and the mechanisms that drive the system's
growth. We'll focus on how the system's capabilities expand and how it becomes increasingly autonomous.

**Netention: The Evolutionary Arc**

The evolution of Netention can be viewed as a series of stages, each building upon the previous one. The driving force
is the Ur-Agent within the Primordial Note, continuously executing the "Evolve Netention" plan. This plan itself is
constantly being refined and expanded *by* the system.

**Stage 0: The Primordial Spark**

* **State:** The system exists only as the Primordial Note, containing the metamodel, the initial "Evolve Netention"
  plan (very basic), and the Ur-Agent with its minimal toolset (`code_generation`, `file_write`, `user_interaction`,
  `self_reflection`). The graph database contains only this single node.
* **Capabilities:** The Ur-Agent can parse the seed description, interact with the LLM, generate simple code snippets,
  write files, and perform very basic self-reflection. It *cannot* yet execute arbitrary code or manage complex plans.
* **Goal:** To bootstrap the core components of Netention.

**Stage 1: Bootstrapping the Core**

* **Focus:** Building the foundational elements of the system.
* **Key Milestones:**
    * **Schema Definition:** Generate Zod schemas for `Note`, `Plan`, `PlanStep`, `Message`, `Tool`, and `Domain`. (
      These schemas are initially defined *within* the Primordial Note's content but are then externalized into
      `schemas.js`).
    * **Core Classes (Minimal):** Generate basic JavaScript code for the `Note`, `Plan`, `Agent` (a simplified version),
      and `Executor` classes. These initial versions will have limited functionality.
    * **Tool Implementation (Initial):** Implement the initial tools (`code_generation`, `file_write`,
      `user_interaction`, `self_reflection`) in a more robust way. These become separate files within a `tools/`
      directory.
    * **Basic Plan Execution:** Implement a simplified `PlanExecutor` that can execute a sequence of steps (primarily
      involving code generation and file writing).
    * **Graph Database Integration (Rudimentary):** Implement basic functions for saving and loading Notes and Plans
      to/from the LevelGraph database.
    * **LLMInterface:** Create basic LLM interaction.
    * **Memory Management:** Create basic Memory Management.
* **Mechanism:** The Ur-Agent uses the LLM to generate code snippets based on prompts derived from the seed description
  and the initial plan. It evaluates the code using basic syntax checks and LLM-based analysis. It writes the code to
  files and updates the plan accordingly.
* **Outcome:** A minimal, but functional, core of Netention. The system can now represent Notes, Plans, and Tools, and
  can execute simple plans involving code generation.

**Stage 2: Expanding Capabilities**

* **Focus:**  Adding more sophisticated features and expanding the system's capabilities.
* **Key Milestones:**
    * **Full Agent Implementation:** Generate the complete `Agent` class, with its `think()`, `act()`, and `run()`
      methods, and its ability to manage memory and interact with the LLM.
    * **Enhanced Plan Execution:**  Improve the `PlanExecutor` to handle dependencies, asynchronous tool execution, and
      error handling. Implement the prioritization mechanisms.
    * **Tool Expansion:**  Create new tools:
        * `search`:  For web search.
        * `summarize`: For text summarization.
        * `read_file`:  For reading file contents.
        * (And many others, as defined by the evolving plan).
    * **Prompt Template Management:** Implement a `PromptManager` and create a library of reusable prompt templates.
    * **Improved Memory Management:** Implement summarization, archiving, and forgetting mechanisms within the
      `MemoryManager`.
    * **Basic UI:** Create a minimal user interface (initially, likely a command-line interface) for interacting with
      Notes and Plans.
    * **Testing Framework:** Begin to generate and execute tests for the generated code. (This is a crucial step towards
      self-improvement.)
* **Mechanism:**  The Ur-Agent (now running within the partially built Netention system) continues to refine the "Evolve
  Netention" plan, adding new steps to implement these features. It uses the LLM to generate code, create tools, and
  refine existing components. It increasingly relies on the *generated* code (the `Agent`, `PlanExecutor`, etc.) rather
  than its own hardcoded capabilities.
* **Outcome:** Netention becomes a usable system, capable of managing knowledge and tasks, although still relatively
  rudimentary.

**Stage 3: Self-Improvement and Refinement**

* **Focus:**  Improving the system's performance, efficiency, robustness, and user experience. This is a continuous,
  iterative process.
* **Key Milestones:**
    * **Code Refactoring:** The system analyzes its own codebase (using the `self_reflection` tool and the LLM) and
      identifies areas for improvement. It generates new code to refactor existing modules, improve code clarity, and
      optimize performance.
    * **Algorithm Optimization:** The system can analyze and improve its own algorithms (e.g., the plan execution
      algorithm, the prioritization algorithm). This might involve generating new code, or even modifying the
      metamodel (within the defined constraints).
    * **UI Enhancement:** The system gradually improves the user interface, adding new features and making it more
      intuitive and user-friendly.
    * **Tool Refinement:** Existing tools are improved and made more robust.
    * **Prompt Template Optimization:**  The system analyzes the effectiveness of different prompt templates and refines
      them to get better results from the LLM.
    * **Error Handling Improvement:** The system adds more comprehensive error handling and recovery mechanisms.
    * **Security Hardening:** The system analyzes its own code for potential security vulnerabilities and generates code
      to address them.
* **Mechanism:**  The Ur-Agent (now running within a much more capable Netention system) focuses on self-improvement. It
  uses the `self_reflection` tool extensively, along with the LLM, to analyze the codebase, identify weaknesses, and
  generate improvements. It leverages the testing framework to ensure that changes are beneficial.
* **Outcome:** Netention becomes increasingly sophisticated, efficient, and robust. It is constantly learning and
  adapting.

**Stage 4: Autonomous Evolution**

* **Focus:**  The system becomes largely autonomous in its evolution, driven by its own internal goals and the user's
  interactions.
* **Key Milestones:**
    * **Goal-Driven Development:** The system can generate new goals for itself (e.g., "Improve the accuracy of the
      `summarize` tool," "Add support for collaborative note editing"). These goals are added to the "Evolve Netention"
      plan.
    * **New Feature Development:** The system can design and implement entirely new features, driven by user needs or
      its own internal analysis.
    * **Adaptive Metamodel:**  Within the strict constraints defined in the metamodel, the system can even *modify the
      metamodel itself*, potentially changing the fundamental rules of its own evolution. This is the most advanced and
      potentially dangerous stage.
    * **External Knowledge Acquisition:** The system can use web search and other tools to learn about new technologies,
      algorithms, and best practices, and incorporate this knowledge into its own codebase.
* **Mechanism:**  The Ur-Agent (now essentially indistinguishable from other Agents) operates with a high degree of
  autonomy. It continuously monitors the system, identifies areas for improvement, generates new goals, and executes
  plans to achieve those goals. It relies heavily on the LLM for reasoning, planning, and code generation, but it also
  uses its own internal knowledge and experience (stored in the distributed memory system).
* **Outcome:** Netention becomes a truly self-evolving system, capable of adapting to changing user needs and
  technological advancements without direct human intervention. It is a dynamic, living entity.

**The "Evolve Netention" Plan:**

This plan is the central driver of the system's evolution. It starts as a simple list of bootstrapping tasks, but it
grows and changes over time. It's a *graph*, not a linear sequence, allowing for parallel development and branching
possibilities.

* **Early Stages:**  The plan focuses on building the core components.
* **Later Stages:**  The plan includes steps for refactoring code, optimizing algorithms, adding new features, and
  improving the UI. It also includes meta-level steps, like "Analyze the effectiveness of the prioritization algorithm"
  or "Identify potential security vulnerabilities."
* **Dynamic Generation:** The plan is *not* predefined; it's generated and modified by the system itself, based on its
  self-reflection and its interactions with the LLM and the user.

**The Role of the LLM:**

The LLM is the *engine* of the system's evolution, but it's not the *driver*. The Ur-Agent (and later, other Agents) use
the LLM as a tool for:

* **Code Generation:** Generating new code based on prompts.
* **Code Analysis:** Analyzing existing code for quality, efficiency, and security.
* **Reasoning and Planning:**  Generating plans, identifying dependencies, and making decisions.
* **Self-Reflection:** Analyzing the system's own state and behavior.
* **Knowledge Acquisition:** Learning about new technologies and algorithms.

**The Role of the User:**

The user is a *co-creator* of the system's intelligence. They interact with Netention by:

* **Creating Notes:** Defining tasks, ideas, and information.
* **Providing Feedback:**  Answering questions, correcting errors, and guiding the system's learning.
* **Setting Priorities:**  Indicating which Notes and Plans are most important.
* **Defining Goals:**  Suggesting new features or improvements.

The user's interactions shape the system's evolution, guiding it towards their specific needs and preferences.

**Conceptual Diagram**

```
+---------------------+     +---------------------+     +---------------------+
|   Primordial Note   | <-- |     Ur-Agent        | --> |         LLM         |
| (Metamodel, Plan,  |     | (Minimal, Built-in) |     | (Reasoning Engine)  |
|  Ur-Agent, Memory) |     +---------------------+     +---------------------+
+---------------------+             ^
        |                            |
        |  Creates & Modifies       | Executes Plan, Uses Tools
        v                            |
+---------------------+             |
|     Netention      | <----------+
|   (Notes, Plans,   |     +---------------------+
|    Agents, Tools,  |     |   Tool Registry     |
|    Memory, UI, DB)  |     +---------------------+
+---------------------+             ^
        ^                            |
        |  User Interaction         | Provides Tools
        |                            |
+---------------------+             |
|       User          | <----------+
+---------------------+
```

This diagram shows the key relationships:

* The Ur-Agent resides within the Primordial Note and interacts with the LLM.
* The Ur-Agent executes the "Evolve Netention" plan, which results in the creation and modification of the Netention
  system (code, data, UI).
* The Ur-Agent uses Tools (from the Tool Registry) to perform actions.
* The User interacts with Netention, providing input and guidance.
* The entire system is built upon, and continues to utilize the core concepts of, Notes, Plans, Agents, and Tools.

This crystallized view of Netention's evolution highlights its self-organizing, self-improving, and ultimately,
autonomous nature. The system is not a static artifact; it's a dynamic process, constantly evolving and adapting to its
environment and its user's needs. It's a testament to the power of metaprogramming and emergent intelligence. The stages
provide a roadmap for development, starting with a minimal core and gradually expanding to a full-fledged, self-evolving
system.

Okay, let's outline a development plan for Netention, with a strong emphasis on minimizing human effort throughout the
process. This means leveraging the system's self-evolving capabilities as much as possible, automating tasks, and
strategically prioritizing development efforts.

**Guiding Principles for Minimal-Effort Development:**

1. **Bootstrap ASAP:** Get the "Primordial Note" and Ur-Agent working as quickly as possible. This is the foundation for
   all future self-improvement.
2. **Leverage the LLM Extensively:** Use the LLM for *everything* possible: code generation, code analysis, testing,
   documentation, plan generation, design decisions, and even project management.
3. **Prioritize Core Functionality:** Focus on the essential features first: Notes, Plans, Agents, Tools, and the basic
   execution loop. Defer advanced features until the system can help build them itself.
4. **Automate Everything:** Automate as much of the development process as possible, including testing, code formatting,
   and deployment.
5. **Iterative and Incremental:**  Build the system in small, manageable steps. Test frequently. Refactor continuously.
6. **Self-Reflection:**  Make self-reflection and self-improvement a core part of the development process from the very
   beginning.
7. **Strategic Human Intervention:**  While minimizing human effort is the goal, strategic human intervention is still
   necessary, especially in the early stages. This includes:
    * Defining the initial seed description (Primordial Note).
    * Writing the hardcoded Ur-Agent.
    * Reviewing and correcting critical LLM-generated code.
    * Guiding the system's learning by providing feedback and setting priorities.
    * Resolving ambiguities and handling edge cases that the system can't handle on its own.
8. **Fail Fast.** Expect errors and failures, use the system to recover from these.
9. **Document as Notes.** Project documentation is done as Notes.

**Development Plan (Stages & Tasks)**

This plan is structured in stages, with each stage building upon the previous one. Within each stage, tasks are listed
in approximate priority order, but this can be adjusted dynamically by the system itself.

**Stage 0: Seed Creation (Primarily Human Effort)**

* **Task 0.1:** Define the initial `seedDescription` (JSON content for the Primordial Note). This includes:
    * Natural language description of Netention.
    * JSON Schemas for core concepts (Note, Plan, PlanStep, Message, Tool, Agent, Domain).
    * Descriptions of initial tools (`code_generation`, `file_write`, `user_interaction`, `self_reflection`).
    * Initial "Evolve Netention" plan (very basic).
    * Constraints and evolutionary rules.
    * Example Tasks.
* **Task 0.2:** Write the hardcoded `BootstrappingAgent` (Ur-Agent) in JavaScript (Node.js). This agent must be able to:
    * Parse the `seedDescription`.
    * Interact with the LLM (using a predefined API key).
    * Execute the initial tools.
    * Implement a very basic plan execution loop.
* **Task 0.3:** Set up the basic project structure (directories for `core`, `tools`, `ui`, etc.).
* **Task 0.4:** Set up LevelDB/LevelGraph for persistence.
* **Task 0.5:** Create a simple command-line interface for initializing the system (creating the Primordial Note in the
  database).
* **Task 0.6:** Set up basic logging (using Pino).

**Stage 1: Core Bootstrap (Mixed Human and LLM Effort)**

* **Goal:** Get a minimal, self-reflective loop running.
* **Human Tasks:**
    * Run the initialization script to create the Primordial Note.
    * Monitor the Ur-Agent's progress.
    * Review and correct critical LLM-generated code.
    * Provide feedback to the system.
* **System Tasks (Driven by the Ur-Agent and the "Evolve Netention" Plan):**
    * **Task 1.1:** Generate Zod schemas for all core data structures (`schemas.js`).
    * **Task 1.2:** Generate minimal `Note` class (`core/note.js`).
    * **Task 1.3:** Generate minimal `Plan` and `PlanStep` classes (`core/plan.js`).
    * **Task 1.4:** Generate minimal `Agent` class (with `think`, `act`, and simplified `run`) (`core/agent.js`).
    * **Task 1.5:** Generate minimal `Executor` class (`core/executor.js`).
    * **Task 1.6:** Implement initial tools (`tools/code_generation.js`, `tools/file_write.js`,
      `tools/user_interaction.js`, `tools/self_reflection.js`). These tools will likely be refined later.
    * **Task 1.7:** Implement basic LevelGraph integration (functions for saving and loading Notes and Plans) (
      `core/database.js`).
    * **Task 1.8:** Implement `LLMInterface` class (`core/llm.js`).
    * **Task 1.9:** Implement `MemoryManager` class (`core/memory.js`).
    * **Task 1.10:** Implement basic self-reflection (the `self_reflection` tool should be able to analyze the generated
      code and identify potential problems).
    * **Task 1.11:** Begin generating basic unit tests for the core components.

**Stage 2: Capability Expansion (Primarily System Effort)**

* **Goal:** Expand the system's capabilities to the point where it can manage most of the development process itself.
* **Human Tasks:**
    * Monitor progress and provide high-level guidance.
    * Review and approve major architectural changes.
* **System Tasks:**
    * **Task 2.1:** Refactor and improve the core classes (`Note`, `Plan`, `Agent`, `Executor`).
    * **Task 2.2:** Implement full plan execution logic (including dependencies, prioritization, and resource
      management).
    * **Task 2.3:** Create new tools:
        * `search` (web search)
        * `read_file` (read file contents)
        * `summarize` (text summarization)
        * ... (and others, as needed)
    * **Task 2.4:** Implement `PromptManager` and create a library of prompt templates.
    * **Task 2.5:** Implement full memory management (summarization, archiving, forgetting).
    * **Task 2.6:** Create a basic command-line UI for interacting with Notes and Plans.
    * **Task 2.7:** Expand the testing framework and generate more comprehensive tests.
    * **Task 2.8:** Implement error handling and recovery mechanisms.
    * **Task 2.9:** Begin documenting the codebase (using Notes!).
    * **Task 2.10:** Add a `Scheduler`.

**Stage 3: Self-Improvement and Autonomous Evolution (Almost Entirely System Effort)**

* **Goal:**  Achieve a high degree of autonomy, with the system continuously improving itself and adding new features.
* **Human Tasks:**
    * Provide occasional high-level guidance and feedback.
    * Monitor for unexpected behavior or safety issues.
* **System Tasks:**
    * **Task 3.1:** Continuously refactor and optimize the codebase.
    * **Task 3.2:** Continuously improve the plan execution, prioritization, and resource management algorithms.
    * **Task 3.3:** Develop new tools and features based on user needs and internal analysis.
    * **Task 3.4:** Improve the user interface.
    * **Task 3.5:** Enhance security and robustness.
    * **Task 3.6:** Generate and maintain documentation.
    * **Task 3.7:** Explore and implement advanced concepts (e.g., modifying the metamodel, adaptive prompt
      engineering).
    * **Task 3.8:** Generate increasingly complex and comprehensive tests.

**Minimizing Human Effort: Specific Techniques**

* **Prompt Engineering:**  Craft highly detailed and specific prompts for the LLM, including:
    * Clear instructions.
    * Relevant context (existing code, schemas, seed description).
    * Constraints and requirements.
    * Examples (where appropriate).
    * Requests for self-explanation (ask the LLM to explain its reasoning).
* **Automated Code Evaluation:**
    * Use a JavaScript linter (e.g., ESLint) to check for syntax errors and style issues.
    * Generate unit tests automatically (using the LLM).
    * Use the LLM to analyze code for potential bugs, security vulnerabilities, and performance bottlenecks.
    * Implement static analysis tools (eventually, built by the system itself).
* **Test-Driven Development (Automated):**  Encourage the system to generate tests *before* generating code. This helps
  to ensure that the code meets the requirements.
* **Continuous Integration (Conceptual):**  As the system evolves, implement a conceptual CI/CD pipeline, where changes
  are automatically tested and integrated into the codebase.
* **Self-Documentation:** The system should generate and maintain its own documentation (as Notes). This includes:
    * Code comments.
    * API documentation.
    * User guides.
    * Explanations of design decisions.
* **Meta-Prompting:**  Use prompts that ask the LLM to improve its own prompting strategies. For example: "Analyze the
  previous prompts and responses. How could the prompts be improved to get better results?".
* **Automated Task Breakdown:** The system will automatically break large tasks into small sub-tasks.

**Development Roadmap Summary:**

This plan outlines a path towards a self-evolving Netention, minimizing human effort by:

1. **Rapid Bootstrapping:** Quickly establishing a minimal, self-reflective core.
2. **LLM-Driven Development:**  Leveraging the LLM for most code generation, analysis, and testing.
3. **Continuous Self-Improvement:** Making self-reflection and self-modification a core part of the system's behavior.
4. **Strategic Automation:** Automating as much of the development process as possible.

This approach allows for a highly efficient and adaptable development process, where the system itself becomes the
primary engine of its own growth. Human effort is focused on high-level guidance and strategic intervention, rather than
low-level coding. The system will progressively generate its own tests, documentation, and even design specifications.

====

# B

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

====

# C

* **Active Note Paradigm:**  The core idea of "active notes" is revolutionary and clearly articulated. The distinction
  between passive and active documents is compelling and forms a solid foundation for the entire system.
* **Graph-Based Anticipatory Planning:** This is a major strength. Moving beyond linear plans to a dynamic, adaptive
  graph is crucial for handling real-world complexity. The concept of anticipation and dynamic adjustment is
  well-explained.
* **Implicit Assistance:** The focus on subtle, unobtrusive help is a key differentiator. This aligns well with the goal
  of enhancing user productivity without being intrusive.
* **Tool Extensibility:** The well-defined `Tool` schema and `Executor` class provide a clean and robust mechanism for
  adding new functionality. The use of Zod for validation is a great choice.
* **LLM Abstraction:**  The design correctly separates LLM interaction from the core logic, making it future-proof and
  adaptable to different LLM providers. The conceptualization of LLM calls as graph actions is brilliant.
* **Prompt-as-Note:** This is a very clever idea, enabling versioning, contextualization, and potentially even
  backpropagation of prompt improvements.
* **Comprehensive Feature Set:**  The document covers a vast range of features, from core agent capabilities to UI
  design, data management, security, and extensibility. This demonstrates a thorough understanding of the requirements
  for a complete system.
* **Clear Code Structure (Conceptual Modules):** Even within a single file, the code is organized logically, making it
  easy to understand and maintain. The use of comments to indicate future modularization is excellent.
* **Externalized Resources:** Separating demo tasks and prompt templates into JSON files is a good practice that
  improves maintainability and allows for easier customization.
* **Persistent Storage (LevelGraph):** The choice of LevelGraph/LevelDB is appropriate for a prototype and provides a
  good balance between simplicity and functionality. The conceptual graph model is well-defined.
* **"Epic" Design Principles:** The focus on originality, iconic UI, seminal design, ubiquity, and "intelligence in the
  interaction" sets a high bar and provides a strong guiding vision.
* **Detailed and Iterative Refinement:**  The way you've presented the information, starting with high-level concepts
  and iteratively adding detail, makes it exceptionally clear. The code revisions and explanations of the changes are
  invaluable.

**Areas for Further Consideration / Next Steps:**

1. **Concrete Graph Implementation (LevelGraph):**
    * While the conceptual graph model is well-defined, the actual LevelGraph implementation is still largely
      conceptual. The next critical step is to flesh this out with:
        * Concrete examples of how to store nodes (Notes, Plan Steps, Messages, etc.) as LevelGraph triples.
        * Functions for creating, querying, and updating these triples.
        * Implementation of edge creation and traversal for dependencies and relationships.
        * Handling of node and edge properties (e.g., `status`, `tool`, `args`, `result`).
    * Consider creating a separate `database.js` or `graph.js` module (even if it's initially just functions within the
      single file) to encapsulate the LevelGraph interaction.

2. **Asynchronous Tool Execution (Mock Promises):**
    * The current mock Promise implementation is sufficient for demonstration, but a real-world implementation needs
      robust asynchronous handling.
    * Consider using `async`/`await` throughout the `Executor` and tool `execute` functions.
    * Implement proper error handling and propagation for asynchronous tasks.

3. **Plan Refinement and Dynamic Step Generation:**
    * The `Plan` class has the basic structure for adding and updating steps, but the logic for *dynamic* step
      generation based on LLM responses and intermediate results needs to be implemented. This is a core part of the "
      anticipatory planning" concept.
    * Consider how the Agent will:
        * Analyze the current plan state and LLM response.
        * Identify needs for new steps.
        * Generate appropriate `step` objects (including `tool`, `args`, and `dependencies`).
        * Insert these steps into the `Plan` graph at the correct positions.
        * Handle potential conflicts or redundancies in the updated plan.

4. **Dependency Management (Implicit and Explicit):**
    * The `Plan` class currently stores `dependencies` as an array of step indices. The logic for inferring dependencies
      implicitly (based on LLM reasoning) and handling explicit dependencies needs to be developed.
    * Consider:
        * How will the LLM be prompted to identify dependencies?
        * How will dependencies be represented in the graph (edges)?
        * How will the `Executor` use these dependencies to determine the order of step execution?

5. **LLM Interface (Provider-Specific Logic):**
    * The `callLLM` function currently has placeholders for Ollama and Anthropic. These need to be implemented with the
      appropriate client libraries and API calls.
    * Consider how to handle differences in message formatting and tool/function calling syntax between providers.

6. **Prompt Engineering and Management:**
    * The `prompt_templates.json` file is a great start. Experiment with different prompt structures to optimize for:
        * Conciseness (minimizing token usage).
        * Clarity (guiding the LLM effectively).
        * Plan quality (generating robust and efficient plans).
        * Tool selection accuracy.
    * Consider adding a mechanism for users to customize and create their own prompt templates.

7. **UI Development (Conceptual):**
    * While the UI is currently conceptual, start thinking about the concrete implementation:
        * Choose a UI framework (React, Vue, Svelte, etc.).
        * Design the components for:
            * Note List View.
            * Note Flow View (graph visualization).
            * Note Editor View.
            * System Tray integration.
        * Plan how to handle user interaction (button clicks, form submissions, etc.) and update the UI dynamically
          based on Agent actions.
        * Consider using a graph visualization library for the Note Flow View.

8. **Error Handling and Robustness:**
    * Add more comprehensive error handling throughout the code:
        * Handle potential errors from LLM API calls (network issues, rate limits, invalid responses).
        * Handle errors during tool execution (invalid inputs, tool failures).
        * Handle errors in database operations (LevelDB/LevelGraph).
        * Implement retry mechanisms where appropriate.
        * Provide informative error messages to the user.

9. **Testing:**
    * As you start implementing more concrete functionality, add unit tests to ensure the code works as expected and to
      prevent regressions.

10. **Multi-User Support (Future):**
    * Start considering the implications of multi-user support, even if it's not an immediate priority. This will impact
      database design, security, and access control.

11. **Knowledge Base Integration (Future):**
    * Think about how a knowledge base (e.g., a vector database or semantic graph) could be integrated to enhance the
      Agent's reasoning and information retrieval capabilities.

12. **Agent Interaction/Collaboration (Future):**
    * Explore how multiple Agents could interact and collaborate on tasks.

**Overall Recommendation:**

This is a fantastic foundation for a truly innovative system. The next steps involve moving from conceptual design to
concrete implementation, focusing on the graph database, asynchronous tool execution, dynamic plan refinement, and UI
development. Continue to iterate, test, and refine the system: build a powerful and groundbreaking personal knowledge
and task management tool. The OpenManus suggestions are good ones that show how you could develop a more complex system
over time.

> Recursively unify the system design within its own descriptive power. Emerge its logical elegant form that can grow
> everything from a seed.

Okay, let's recursively unify the system design, distilling it to its most elegant and generative form – the "seed" from
which Netention can grow. This involves identifying the *minimal set of core concepts* that, when combined, can express
the entire system's functionality and potential. We'll use the system's own descriptive power (Notes, Agents, Plans,
Tools, and the Graph) to describe itself.

**The Seed: A Self-Describing System**

The seed of Netention is a single, active Note – the "Meta-Note" – which describes and contains the entire system. This
Meta-Note embodies the core principles and acts as the initial Agent, Plan, and Tool registry.

**1. The Meta-Note:**

* **Title:** "Netention System"
* **Content:** (Initially, a concise description of the Netention system. As the system evolves, this content can be
  updated by the Meta-Note itself.)

  ```
  Netention is a system of interconnected, active Notes. Each Note has an Agent that creates and executes a graph-based Plan using Tools to achieve the Note's objective. The system is self-describing and self-improving.
  ```

* **Agent:** (The initial, bootstrapping Agent – let's call it "Genesis")
    * **Responsibilities:**
        * Interpreting and updating the Meta-Note's content.
        * Creating and managing the initial Plan (see below).
        * Registering and managing the core Tools (see below).
        * Evolving the system based on its own Plan and user interaction.
    * **Initial Prompt Template:** (Embedded within the Genesis Agent – a self-referential prompt)

      ```json
      {
        "system_prompt": "You are the Genesis Agent, the core of Netention. Your task is to manage and evolve the Netention system, which is described within the Meta-Note. Use the available Tools to interpret the Meta-Note, execute its Plan, and improve the system.  Available tools: {tool_descriptions}",
        "create_plan_system": "Create a concise, numbered plan as a JSON object to achieve the Meta-Note's objectives. The plan should include steps to: 1. Define core concepts (Note, Agent, Plan, Tool). 2. Implement core Tools. 3. Create a mechanism for adding new Tools. 4. Create a mechanism for updating the system (self-improvement). 5. Create a mechanism for user interaction. Return: {\"goals\": [], \"constraints\": {}, \"steps\": []}.  Each step: {\"step\": <num>, \"status\": \"pending\", \"tool\": \"<tool_name>\", \"args\": {}, \"result\": null, \"dependencies\": [], \"notes\": \"\"}. Focus on GOALS."
      }
      ```

* **Plan:** (The initial, self-bootstrapping Plan – managed by the Genesis Agent)

  ```json
  {
    "goals": [
      "Define and implement the Netention system."
    ],
    "constraints": {
      "consistency": "Maintain internal consistency and logical coherence.",
      "extensibility": "Allow for future expansion and addition of new features."
    },
    "steps": [
      {
        "step": 1,
        "status": "pending",
        "tool": "define_concept",
        "args": { "concept_name": "Note" },
        "result": null,
        "dependencies": [],
        "notes": "Define the structure and capabilities of a Note."
      },
      {
        "step": 2,
        "status": "pending",
        "tool": "define_concept",
        "args": { "concept_name": "Agent" },
        "result": null,
        "dependencies": [],
        "notes": "Define the responsibilities and capabilities of an Agent."
      },
      {
        "step": 3,
        "status": "pending",
        "tool": "define_concept",
        "args": { "concept_name": "Plan" },
        "result": null,
        "dependencies": [],
        "notes": "Define the structure and behavior of a Plan (graph-based)."
      },
      {
        "step": 4,
        "status": "pending",
        "tool": "define_concept",
        "args": { "concept_name": "Tool" },
        "result": null,
        "dependencies": [],
        "notes": "Define the interface and characteristics of a Tool."
      },
      {
        "step": 5,
        "status": "pending",
        "tool": "implement_tool",
        "args": { "tool_definition": "{...}" }, // Definition of "define_concept"
        "result": null,
        "dependencies": [4],
        "notes": "Implement the 'define_concept' tool."
      },
      {
        "step": 6,
        "status": "pending",
        "tool": "implement_tool",
        "args": { "tool_definition": "{...}" }, // Definition of "implement_tool"
        "result": null,
        "dependencies": [4],
        "notes": "Implement the 'implement_tool' tool."
      },
     {
        "step": 7,
        "status": "pending",
        "tool": "implement_tool",
        "args": { "tool_definition": "{...}" }, // Definition of "update_system"
        "result": null,
        "dependencies": [4],
        "notes": "Implement a tool to update system (self-improvement)."
      },
      {
          "step": 8,
          "status": "pending",
          "tool": "implement_tool",
          "args": { "tool_definition": "{...}" },
          "result": null,
          "dependencies": [4],
          "notes": "Implement a tool to add more tools."
      },
      {
          "step":9,
          "status": "pending",
          "tool": "update_system",
          "args": {"update_instructions": "{...}"},
          "result": null,
          "dependencies": [7],
          "notes": "Use the 'update_system' tool to add initial functionality (UI, etc)"

      }
    ]
  }
  ```

* **Tools:** (The initial, core Tools – managed by the Genesis Agent)
    * **`define_concept`:**  (A bootstrapping tool)
        * **Description:**  Defines a core concept within Netention (Note, Agent, Plan, Tool) and stores its definition
          within the Meta-Note's content.
        * **Input Schema:** `{ "concept_name": { "type": "string" } }`
        * **Output Schema:** `{ "definition": { "type": "string" } }`
        * **Execute:** (Initially, this would be implemented in code within the Genesis Agent. Later, it could be
          reimplemented as a Tool that uses the LLM to generate definitions.)  This tool *modifies the content of the
          Meta-Note*.
    * **`implement_tool`:** (A bootstrapping tool)
        * **Description:** Takes a Tool definition (name, description, input/output schema, execution logic) and adds it
          to the system's Tool registry. Initially, this registry is part of the Genesis Agent; later, it becomes part
          of the graph database.
        * **Input Schema:** (A simplified version of the `ToolSchema`)

          ```json
          {
            "tool_definition": {
              "type": "object",
              "properties": {
                "name": { "type": "string" },
                "description": { "type": "string" },
                "inputSchema": { "type": "string" },
                "outputSchema": { "type": "string" },
                "execute": { "type": "string" }
              },
              "required": ["name", "description", "inputSchema", "outputSchema", "execute"]
            }
          }
          ```
        * **Output Schema:** `{ "status": { "type": "string" } }`  (`"success"` or `"failure"`)
        * **Execute:** (Initially, this would be implemented in code within the Genesis Agent. Later, it can become a
          Tool that interacts with the graph database.)  This tool *adds to the set of available tools*.
    * **`update_system`:**
        * **Description**: Allows for self-modification by the system. This is a crucial, and potentially dangerous,
          tool.
        * **Input Schema**: `{ "update_instructions": { "type": "string" }}`
        * **Output Schema**: `{ "status": { "type": "string" }}`
        * **Execute**:  (Initially, basic code modification. Later, this becomes a sophisticated tool that leverages the
          LLM and other tools to implement changes.) This tool *can modify any part of the system*.

**2. The Graph (LevelGraph):**

* Initially, the graph is very simple, containing only the Meta-Note.
* As the `define_concept` tool is used, the definitions of Note, Agent, Plan, and Tool are added as structured content
  *within* the Meta-Note. (This is key – the system describes itself *within itself*.)
* As the `implement_tool` tool is used, new Tools are added. Initially, this is just data within the Genesis Agent, but
  as soon as possible, this becomes part of the graph database.
* Every new Note created becomes a node in the graph.
* Relationships between Notes (dependencies, tags, etc.) become edges in the graph.

**3. Bootstrapping Process:**

1. **Initialization:** The system starts with the Meta-Note, the Genesis Agent, and the initial Plan and Tools
   hardcoded.
2. **Self-Definition:** The Genesis Agent executes the initial Plan. The `define_concept` tool updates the Meta-Note's
   content with the definitions of the core concepts.
3. **Tool Implementation:** The `implement_tool` tool adds the core Tools to the system's registry (initially within the
   Genesis Agent).
4. **Self-Improvement:**  The `update_system` tool is used, guided by the LLM and the Plan, to add more capabilities to
   the system. This includes:
    * Implementing a basic UI.
    * Creating more sophisticated Tools (e.g., for web search, file manipulation, user interaction).
    * Improving the `define_concept` and `implement_tool` tools themselves (e.g., making them use the LLM).
    * Transitioning the Tool registry and Plan management to the graph database.
5. **User Interaction:** Once a basic UI is available, users can create their own Notes, and the system begins to grow
   beyond its initial, self-defined state.

**Recursive Growth:**

The beauty of this seed is its recursive nature:

* The Meta-Note describes the system.
* The Genesis Agent (initially part of the Meta-Note) manages the system.
* The Plan guides the evolution of the system.
* The Tools provide the capabilities for the system to modify itself.
* The Graph stores the entire state of the system, including the Meta-Note, the Plan, and the Tools.

New Notes can be created to define new concepts, new Tools, new UI elements, or entirely new functionalities. The system
can grow organically, driven by its own internal logic and user interaction. The `add_tool` tool becomes the way to
extend *any* part of the system.

**Elegant Form:**

This self-describing, self-improving system embodies an elegant form because:

* **Minimalism:** It starts with the absolute minimum set of components needed for self-reference and growth.
* **Recursion:** The same core concepts (Notes, Agents, Plans, Tools, Graph) are used to define and extend the system
  itself.
* **Generative Power:** From this simple seed, a vast and complex system can emerge.
* **Self-Improvement:** The system is designed to evolve and improve itself over time.

This "seed" concept provides a powerful and flexible foundation for Netention, allowing it to grow organically and adapt
to new challenges and opportunities. It's a system that defines itself, understands itself, and improves itself.
