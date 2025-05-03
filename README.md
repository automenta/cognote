# CogThought

This package contains a new implementation of the Netention vision, focusing on total elegance, conceptual integrity, and extreme minimalism by leveraging metaprogrammatic capabilities through recursive self-unification. The system's entire state, structure, behavior, and evolution are defined and managed as data within its own knowledge base (KB), processed by a minimal, generic Java kernel.

This README serves as the specification for this implementation.

**1. Core Architectural Principles**

*   **Knowledge Base as Universal Reality:** The persistent KB is the *only* source of truth and definition for *everything* in the system: user data, system state, configuration, the System Ontology itself, tool definitions, rule definitions, active tasks, historical events, performance metrics, and even aspects of the system's own structure and capabilities.
*   **Minimal Fixed Java Kernel:** The Java codebase is reduced to the absolute minimum necessary to:
    *   Manage persistent storage (`KnowledgeBase`).
    *   Interpret Term logic and execute rules (`TermLogicEngine`).
    *   Execute a fixed, minimal set of atomic primitive actions (`ToolRegistry` + Primitive `Tool`s).
    *   Translate external interactions (API, Events, Logs) into KB data (`ApiGateway`, `Events`, `Log`).
    *   Run the recursive processing loop (`SystemControl`).
*   **Recursive Self-Unification Loop:** The system's dynamic behavior is driven by a single, continuous loop managed by `SystemControl`. This loop:
    1.  Monitors the KB for new or changed data (Terms, Notes, Relationships).
    2.  Feeds relevant changes (represented as System Ontology Terms) to the `TermLogicEngine`.
    3.  The Engine finds matching Rules (defined as data in the KB).
    4.  Fired Rules produce new data (derived assertions, action Terms, new Notes, new Relationships, updated Notes/Relationships) or trigger primitive Tools.
    5.  Action Terms are executed by the `ToolRegistry`, which invokes Primitive Tools.
    6.  Primitive Tools modify the KB or interact with the environment.
    7.  KB modifications or external interactions generate new data/events, which are asserted back into the KB, restarting the cycle.
    The system processes and modifies itself *solely* based on the data in its KB, using its own logic engine.
*   **Behavior as Data:** All system behaviors, from handling a user command to optimizing resource allocation or learning a new rule, are defined *exclusively* as Rules and Tool definitions stored *as data* in the KB. No specific system behavior logic is hardcoded in the Java kernel beyond the generic interpretation/execution algorithms.
*   **Primitive Tools:** A fixed, minimal set of Java Tools exists to perform the *absolute atomic operations* that *cannot* be expressed as rules acting on other tools or as pure data transformations within the KB. These are the only points where Java code performs specific side effects or interacts directly with underlying systems (persistence, OS, external APIs, external services like LLMs).
*   **System Ontology:** A formal, comprehensive vocabulary within the Term language, combined with standard `Note` types and `Relationship` types, to represent *every* concept in the system (user data, system components, state, behavior definitions, history) in a unified, elegant way across both Term and Graph representations. This ontology is also defined *as data* in the KB.
*   **API as KB Interface:** The WebSocket API is a thin layer that translates external messages into System Ontology Terms asserted into the KB, and translates relevant KB data (Terms, Notes, Relationships) into external messages. It is a window into the system's internal state and processing.

**2. Knowledge Base Specification (KB Content - Data Defines Reality)**

*   **`Note`s:** The universal entity container. Any distinct "thing" in the system is a Note.
    *   Types (`Note.type`): Defined *as data* in the System Ontology (e.g., "UserData", "RuleDefinition", "ToolDefinition", "Setting", "Task", "SystemMetric", "P2PPeer", "SimulatorAgent", "EventLog", "ErrorLog", "OntologyConcept", "ApiEndpoint").
    *   Fields: `id`, `type`, `title`, `text`, `status`, `priority`, `color`, `updated`, `metadata` (`Map<String, Object>`), `graph` (`List<Relationship>`), `associatedTerms`. These fields provide a standard structure for *any* entity Note.
*   **`Relationship`s:** Directed links between Notes, stored within `Note.graph` or managed by `KnowledgeBase`.
    *   Types (`Relationship.type`): Defined *as data* in the System Ontology (e.g., "parent", "dependsOn", "processedBy", "generated", "monitors", "configures", "defines", "implements", "input", "output").
    *   Fields: `targetId`, `type`, `metadata`.
*   **`Term`s:** Universal knowledge representation units (`Atom`, `Var`, `Lst`). Used for assertions, rule forms, queries, and representing structured data within Notes/Relationships/Tool parameters. The vocabulary of Atoms (predicates, functions, constants) is defined *as data* in the System Ontology.
*   **`Rule`s:** Define conditional behavior. Stored as `RuleDefinition` Notes.
    *   Fields: `id`, `form` (Term.Lst), `antecedent` (Term), `consequent` (Term), `pri`, `sourceNoteId`.
    *   Rules can query and manipulate *any* data in the KB (Notes, Terms, Relationships) using predicates defined in the System Ontology.
*   **`Assertion`s:** Represent factual knowledge derived or asserted. Stored in the KB. Fields: `id`, `kif` (Term.Lst), `pri`, `timestamp`, `sourceNoteId`, `justificationIds`, `type`, flags, `quantifiedVars`, `derivationDepth`, `isActive`, `kb`.
*   **System Ontology:** Defined *as data* in the KB. This includes:
    *   Notes of type "OntologyConcept" representing key concepts.
    *   Relationships between OntologyConcept Notes (e.g., "isA", "partOf").
    *   Assertions defining the predicates, functions, standard Note types, and standard Relationship types used in the system's vocabulary.
    *   Assertions defining the structure/schema of different Note types or Term structures.

**3. Minimal Generic Java Kernel Specification (Java Code - The Interpreter)**

*   **`Cog.java`:** Minimal entry point. Initializes the fixed set of core components (`KnowledgeBase`, `TermLogicEngine`, `ToolRegistry`, `ApiGateway`, `SystemControl`). Manages core executors. Its primary role is bootstrapping the system from the persistent KB and starting the `SystemControl` loop.
*   **`KnowledgeBase.java`:** The *only* interface for Java code to interact with the persistent KB. Provides generic, schema-agnostic CRUD and query methods for `Note`, `Term`, `Rule`, `Assertion`, `Relationship`. Manages underlying persistence details (key-value, vector store). *Does not contain logic specific to Note types, Term predicates, or Relationship types.*
*   **`TermLogicEngine.java`:** Minimal, generic interpreter. Contains algorithms for unification, rule matching (forward/backward/rewrite/UI), and action generation. Takes Terms as input, finds matching Rules (loaded from KB via `KnowledgeBase`), performs unification, generates action Terms. *Does not contain hardcoded behavior logic.* Its predicates for querying the KB are generic (e.g., `(assertion ?term)`, `(note ?id ?field ?value)`, `(relationship ?source ?type ?target)`).
*   **`ToolRegistry.java`:** Minimal manager. Loads `ToolDefinition` Notes from KB. Maps Tool names (from action Terms) to Java `Tool` instances. Provides `getTool(name)`.
*   **`Tool.java`:** Minimal interface. `execute(parameters: Term, context: ToolContext): CompletableFuture<Term>`. `ToolContext` provides access to `KnowledgeBase`, `LLMService`, `ApiGateway` (for sending messages), `Events` (for emitting Java events that become KB Terms).
*   **Primitive Tools (Java Classes):** A fixed, minimal set of Java classes implementing `Tool.java`. These are the *only* tools with hardcoded side effects or external interactions. They perform atomic operations on the KB or environment.
    *   `_AssertTool(Term)`: Adds Term to KB via `KnowledgeBase`.
    *   `_RetractTool(Term)`: Removes Term from KB via `KnowledgeBase`.
    *   `_UpdateNoteTool(NoteId, UpdateTerm)`: Updates Note fields/metadata via `KnowledgeBase`.
    *   `_AddRelationshipTool(SourceId, TargetId, Type, MetadataTerm)`: Adds Relationship via `KnowledgeBase`.
    *   `_DeleteRelationshipTool(SourceId, TargetId, Type)`: Deletes Relationship via `KnowledgeBase`.
    *   `_QueryKBTool(QueryTerm)`: Queries KB (Terms, Notes, Relationships) via `KnowledgeBase`, returns results as a Term (e.g., a list).
    *   `_CallLLMTool(PromptTerm)`: Calls `LLMService`, returns result Term.
    *   `_SendApiMessageTool(MessageTypeTerm, PayloadTerm)`: Sends message via `ApiGateway`.
    *   `_ExecuteOSCommandTool(CommandTerm)`: Executes OS command, returns result Term. (Optional, potentially risky).
    *   `_AskUserTool(PromptTerm)`: Takes prompt/options Term, asserts `DialogueRequest` Term into KB, waits for `DialogueResponse` Term, returns response Term.
    *   `_GetSystemTimeTool()`: Returns current system time as a Term.
    *   *Minimal Set:* Identify the absolute minimum set required to build all other functionality via Rules. Aim for ~10-15 primitives.
*   **`ApiGateway.java`:** Minimal translator. Receives WebSocket messages, translates to `ApiRequest` Terms, asserts into KB. Monitors KB for `ApiResponse`, `Event`, `NoteUpdate`, `RelationshipUpdate` Terms/Notes/Relationships, translates to API messages, sends via WebSocket.
*   **`Persistence.java`:** Minimal storage implementation. Manages low-level key-value and vector store libraries. Provides simple read/write methods used *only* by `KnowledgeBase`.
*   **`LLMService.java`:** Minimal external service wrapper. Manages connection/calls to LLM provider. Provides methods used *only* by `_CallLLMTool`.
*   **`Events.java`:** Minimal event bus. Allows Java components to emit events. Automatically converts events to `Event` Terms and asserts into KB via `KnowledgeBase`.
*   **`Log.java`:** Minimal logging utility. Logs messages. Automatically converts log messages to `Error` or `LogMessage` Terms and asserts into KB via `KnowledgeBase`.
*   **`SystemControl.java`:** Minimal loop manager. Runs thread(s) that continuously query the KB for new/changed Terms (especially `ApiRequest`, `Event`, `Task` Terms), feeds them to `TermLogicEngine`, and ensures action Terms are executed by the `ToolRegistry`.

**4. System Flows (Data-Driven Execution)**

*   **Recursive Processing Loop:** `SystemControl` drives the loop: Monitor KB -> Feed Terms to Engine -> Engine fires Rules (defined in KB) -> Rules produce data/action Terms -> `SystemControl`/Engine executes actions via `ToolRegistry` -> Primitive Tools modify KB/environment -> New data/events asserted into KB -> Loop continues.
*   **API Request Flow:** `ApiGateway` translates API message to `ApiRequest` Term -> Asserted into KB -> Rules matching `ApiRequest` fire -> Rules trigger Primitive Tools (e.g., `_AssertTool`, `_QueryKBTool`, `_SendApiMessageTool`) -> Results/Responses asserted into KB -> `ApiGateway` monitors KB for `ApiResponse` Terms -> Translates to API message -> Sends to client.
*   **Event Handling Flow:** Internal Java event -> `Events.java` translates to `Event` Term -> Asserted into KB -> Rules matching `Event` fire -> Rules trigger Primitive Tools (e.g., `_LogMessageTool`, `_UpdateNoteTool`, `_SendApiMessageTool`).

**5. Development Phases**

*   **Phase 1: Minimal Generic Kernel Implementation (Java)**
    *   Implement the canonical `Note`, `Relationship`, `Term`, `Rule`, `Assertion` structures.
    *   Implement `KifParser` and `Json`.
    *   Implement the `Persistence` layer and `KnowledgeBase` interface.
    *   Implement the minimal, generic `TermLogicEngine` shell.
    *   Implement the `ToolRegistry` shell.
    *   Implement the `Events` system (including conversion to `Event` Terms).
    *   Implement the `ApiGateway` shell (translation to/from `ApiRequest`/`ApiResponse` Terms).
    *   Implement `LLMService` shell, `SystemControl` shell.
    *   Implement core utilities (`Log`, ID generation).
    *   Implement the minimal set of Java Primitive Tools.
    *   Implement `Cog` to bootstrap these components and start `SystemControl`.
    *   Remove prototype backend code.
*   **Phase 2: Bootstrap Universal Reality (Data - KB Content)**
    *   Bootstrap the persistent KB with the complete System Ontology definitions (Note types, Relationship types, Term predicates/structures). This data defines the system's vocabulary.
    *   Bootstrap the KB with initial `ToolDefinition` Notes for all Primitive Tools.
    *   Bootstrap the KB with initial `Setting` Notes (default configuration).
    *   Bootstrap the KB with the minimal set of `RuleDefinition` Notes required for the system to start, process basic API requests, handle events, and run the recursive loop.
*   **Phase 3: Implement Data-Driven Behaviors (Data - Rules & ToolDefs)**
    *   Write `RuleDefinition` Notes (and define new non-primitive Java Tools if absolutely necessary, adding their `ToolDefinition` Notes) to implement *all* complex system behaviors: full reasoning strategies, planning, prioritization, resource management, application-specific logic, self-monitoring, self-management. This phase is primarily data entry and rule writing, leveraging the generic engine, primitive tools, System Ontology, and both Term/Graph data.
*   **Phase 4: Implement External Components (UI, P2P, Simulator)**
    *   Implement the Frontend UI to interact with the KB *only* via the API, visualizing the Note graph and System Ontology.
    *   Implement the P2P Network component to interact with the KB *only* via asserting P2P Terms/Relationships and calling primitive tools.
    *   Implement the Community Simulator component to interact with the KB *only* via asserting simulation Terms/Notes/Relationships and calling primitive tools.
*   **Phase 5: Refinement, Optimization, Testing, Documentation, and Self-Optimization**
    *   Optimize the generic `TermLogicEngine` and the processing loop.
    *   Implement comprehensive testing (including data-driven tests defined as Rules).
    *   Enhance error handling (via Rules acting on `Error` Terms).
    *   Implement security and privacy (potentially via Rules and Ontology).
    *   Refine UI/UX.
    *   Write complete documentation (crucially documenting the System Ontology, all defined Rules, all defined Tools).
    *   Implement advanced self-optimization mechanisms via Rules acting on system state/metrics/performance data in the KB.
    *   Prepare for deployment.

**6. Code Reduction and Functionality Expansion**

*   **Code Reduction:** Achieved by making the Java codebase a minimal, fixed, generic kernel. All specific behaviors are defined as data (Rules, Tool Definitions, Ontology) in the KB. The Java code implements the *mechanism* (interpret, execute, store, translate), not the *behavior*.
*   **Functionality Expansion:** Achieved by simply adding new `RuleDefinition` and `ToolDefinition` Notes (and potentially new Note/Relationship types for the Ontology) to the KB. The generic engine automatically picks up and executes the new behavior defined in the data. This allows for potentially massive and rapid expansion of capabilities by leveraging the combined power of Term logic and Graph structures, driven by the recursive self-unification loop.

This specification outlines a system designed for total elegance, conceptual integrity, and extreme minimalism in the Java kernel by making the KB the single source of truth for both state and behavior, processed by a minimal, generic interpreter. This maximizes code reduction in the Java layer and enables exponential functional expansion through data manipulation and the system's inherent self-awareness.
