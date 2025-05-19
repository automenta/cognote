# Strategic Development Plan for dumb.cogthought

This document outlines the strategic plan for implementing the `dumb.cogthought` package based on the specification provided in `README.md`. The goal is to build a minimal, elegant, self-unifying system where behavior is defined primarily by data in the Knowledge Base (KB).

**Overall Approach:**

The core strategy is to build a minimal, generic Java kernel first (Phase 1). This kernel provides the fundamental mechanisms for data storage, logic interpretation, primitive action execution, and interaction translation. **Crucially, this kernel will be built by refactoring and adapting the existing `dumb.cog` codebase.** Once the kernel is stable, the system's entire reality (ontology, tools, rules, settings) will be defined as data within the KB (Phase 2). Subsequently, all complex behaviors and application-specific logic will be implemented by writing more data (rules, tool definitions) that the generic kernel processes (Phase 3). Finally, external components (UI, P2P, Simulator) will be built to interact with the system's data-driven core via the defined API (Phase 4), followed by refinement and self-optimization (Phase 5).

**Revised Approach: Refactoring dumb.cog**

Instead of building the `cogthought` kernel from scratch, we will leverage the existing `dumb.cog` codebase as the starting point. The development process will involve systematically refactoring the core `cognote` components to align with the minimal, generic, data-driven architecture of `cogthought`. This means:

*   Adapting existing classes (`Cog`, `Cognition`, `Reason`, `Tools`, `Persist`, `LM`, `Protocol`, `Term`, `Rule`, `Assertion`, `Note`, `Logic`, `Json`, `KifParser`, `Log`, `Events`, `Tool`, `Plugin`) rather than replacing them entirely.
*   Identifying and extracting specific behavior logic currently hardcoded in Java, moving it into the data layer (Rules, Tool Definitions, Ontology).
*   Refining existing data structures (`Term`, `Rule`, `Assertion`, `Note`, `Logic`) to fully support the `cogthought` model, including concepts like `metadata`, `graph`, `associatedTerms`, and `Relationship`.
*   Adapting utilities (`Json`, `KifParser`, `Log`, `Events`) for integration with the new KB-centric architecture, particularly making `Log` and `Events` assert into the KB.
*   Renaming and restructuring components to match the `cogthought` conceptual model while preserving adapted implementation details.

**Target Architecture Specification (The Minimal, Generic Kernel)**

This section describes the components of the target `dumb.cogthought` kernel that will be implemented by refactoring `dumb.cog`.

1.  **Core Architectural Principles (Restated for Clarity):**
    *   **Knowledge Base as Universal Reality:** The persistent KB is the single source of truth and definition for all system elements.
    *   **Minimal Fixed Java Kernel:** A small, unchanging Java core implementing only fundamental, generic mechanisms.
    *   **Recursive Self-Unification Loop:** System behavior driven by a continuous loop processing KB data via the generic engine and rules.
    *   **Behavior as Data:** All complex behaviors defined as Rules and Tool definitions stored in the KB.
    *   **Primitive Tools:** A fixed, minimal set of atomic Java tools for operations not expressible otherwise.
    *   **System Ontology:** A data-defined vocabulary in the KB for unified representation.
    *   **API as KB Interface:** A thin layer translating external messages to/from KB data.

2.  **Knowledge Base Specification (KB Content - Data Defines Reality):**
    *   **Notes:** Universal entity container (`id`, `type`, `title`, `text`, `status`, `priority`, `color`, `updated`, `metadata`, `graph`, `associatedTerms`). Types defined in Ontology.
    *   **Relationships:** Directed links between Notes (`targetId`, `type`, `metadata`). Types defined in Ontology.
    *   **Terms:** Universal knowledge representation units (Atom, Var, Lst). Used for assertions, rule forms, queries, parameters. Vocabulary defined in Ontology.
    *   **Rules:** Define conditional behavior. Stored as `RuleDefinition` Notes (`id`, `form`, `antecedent`, `consequent`, `pri`, `antecedents`, `sourceNoteId`). Query/manipulate KB data.
    *   **Assertions:** Represent factual knowledge (`id`, `kif`, `pri`, `timestamp`, `sourceNoteId`, `justificationIds`, `type`, `flags`, `quantifiedVars`, `derivationDepth`, `isActive`, `kb`).
    *   **System Ontology:** Defined as data in the KB (`OntologyConcept` Notes, Relationships, Assertions defining predicates, functions, types, schemas).

3.  **Minimal Generic Java Kernel Components (Java Code - The Interpreter):**
    *   `Cog.java`: Minimal entry point. Initializes core components, manages executors, bootstraps from KB, starts `SystemControl` loop. **(Refactored from `dumb.cog.Cog`)**
    *   `KnowledgeBase.java`: Interface for KB interaction (CRUD, query for Notes, Terms, Rules, Assertions, Relationships). Manages persistence. Generic, schema-agnostic. Must handle multiple KB contexts (e.g., global vs. note-specific) in a generic way. **(Refactored from `dumb.cog.Cognition` and `dumb.cog.Persist`)**
    *   `TermLogicEngine.java`: Minimal, generic interpreter. Algorithms for unification, rule matching/action generation. Loads Rules from KB. Interacts *only* with `KnowledgeBase` and `ToolRegistry`. **Must be refactored from `dumb.cog.Reason` by removing all hardcoded logic specific to reasoning types (forward/backward/rewrite) and operator handling, replacing it with a single, generic rule interpretation loop.** **(Refactored from `dumb.cog.Reason`)**
    *   `ToolRegistry.java`: Minimal manager. Loads `ToolDefinition` Notes from KB. Maps tool names (from action Terms produced by rules) to Java `Tool` instances. **(Refactored from `dumb.cog.Tools`)**
    *   `Tool.java`: Minimal interface (`name`, `description`, `execute(parameters: Term, context: ToolContext)`). `ToolContext` provides access to `KnowledgeBase`, `LLMService`, `ApiGateway` (for sending messages), `Events` (for emitting Java events that become KB Terms). **(Adapting existing `dumb.cog.Tool`)**
    *   **Primitive Tools (Java Classes):** Fixed, minimal set implementing `Tool.java`. Atomic operations on KB or environment. Examples: `_AssertTool`, `_RetractTool`, `_UpdateNoteTool`, `_QueryKBTool`, `_CallLLMTool`, `_SendApiMessageTool`, `_AskUserTool`. **The exact set must be rigorously defined during detailed design.** **(Extracted/created from existing `cognote` logic or new)**
    *   `ApiGateway.java`: Minimal translator. Converts external messages to/from KB data (ApiRequest/ApiResponse Terms, Note/Relationship updates). **Must be refactored from `dumb.cog.Protocol` and related `Cog` logic by removing hardcoded command handling and making it purely data translation.** **(Refactored from `dumb.cog.Protocol` and related `Cog` logic)**
    *   `Persistence.java`: Minimal storage implementation (low-level key-value/vector store). Provides simple read/write methods used *only* by `KnowledgeBase`. **(Refactored from `dumb.cog.Persist`)**
    *   `LLMService.java`: Minimal wrapper for LLM provider. Manages connection/calls. Used *only* by `_CallLLMTool`. **(Refactored from `dumb.cog.LM`)**
    *   `Events.java`: Minimal event bus. Allows Java components to emit events. Automatically converts events to Event Terms and asserts into KB via `KnowledgeBase`. **(Adapted `dumb.cog.util.Events`)**
    *   `Log.java`: Minimal logging utility. Logs messages. Automatically converts log messages to Error or LogMessage Terms and asserts into KB via `KnowledgeBase`. **(Adapted `dumb.cog.util.Log`)**
    *   `SystemControl.java`: Minimal loop manager. Runs thread(s) that continuously query the KB for new/changed Terms (especially ApiRequest, Event, Task Terms), feeds them to `TermLogicEngine`, and ensures action Terms are executed by the `ToolRegistry`. **The mechanism for monitoring KB changes and prioritizing processing must be designed.** **(Refactored from `dumb.cog.Cog`'s main loop logic)**

4.  **System Flows (Data-Driven Execution):**
    *   **Recursive Processing Loop:** `SystemControl` drives: Monitor KB -> Feed Terms to Engine -> Engine fires Rules (from KB) -> Rules produce data/action Terms -> SystemControl/Engine executes actions via `ToolRegistry` -> Primitive Tools modify KB/environment -> New data/events asserted into KB -> Loop continues.
    *   **API Request Flow:** `ApiGateway` translates message to `ApiRequest` Term -> Asserted into KB -> Rules matching `ApiRequest` fire -> Rules trigger Primitive Tools -> Results/Responses asserted into KB -> `ApiGateway` monitors KB for `ApiResponse` Terms -> Translates to message -> Sends to client.
    *   **Event Handling Flow:** Internal Java event -> `Events.java` translates to `Event` Term -> Asserted into KB -> Rules matching `Event` fire -> Rules trigger Primitive Tools.

**Key Implementation Challenges (Phase 1):**

Refactoring the existing `dumb.cog` codebase into the minimal, generic `cogthought` kernel presents several significant challenges that require careful design and execution:

*   **Refactoring `Reason`:** Transforming the current `Reason` class, which manages distinct reasoner plugins (forward, backward, rewrite, instantiation) and operator execution, into a single, generic `TermLogicEngine` that *only* interprets rules loaded from the KB is complex. All specific reasoning strategies must be removed from the Java code and defined as data (Rules).
*   **Refactoring `Cognition` and `Persist`:** Combining and refactoring `Cognition`'s KB management logic (including handling multiple note-specific KBs, rules, assertions, and the TMS) and `Persist`'s serialization into a clean, generic `KnowledgeBase` interface is challenging. The interface must be schema-agnostic and handle different data types (Notes, Terms, Rules, Assertions, Relationships) and potentially multiple KB contexts without hardcoded logic.
*   **Defining Primitive Tool Granularity:** Identifying the absolute minimal set of atomic operations that *must* remain as Java Primitive Tools, and ensuring all other complex behaviors can be built *only* from these primitives via Rules, requires careful analysis and design.
*   **Designing the System Ontology:** Defining the comprehensive vocabulary and structure of the KB (Note types, Relationship types, Term predicates/functions, schemas) as data is fundamental and requires significant upfront design to ensure consistency and expressiveness.
*   **Designing the Recursive Loop and Prioritization:** The `SystemControl` loop needs a robust mechanism for monitoring KB changes and prioritizing which data/tasks the `TermLogicEngine` should process next. This logic should ideally also be influenced by data in the KB (e.g., task priority Notes).

**Development Phases:**

**Phase 1: Refactor dumb.cog into Minimal Generic Kernel (Java)**

*   **Focus:** Implement the minimal, generic `cogthought` kernel components by systematically refactoring the existing `dumb.cog` codebase according to the "Target Architecture Specification". **This phase requires a detailed design document outlining the interfaces, component responsibilities, refactoring strategy for each `cognote` class, the definition of Primitive Tools, the structure of initial KB data types (Ontology, Tool/Rule/Setting Notes), and the design of the processing loop.**
*   **Key Steps:**
    1.  Establish the `dumb.cogthought` package structure (or refactor `cognote` in place and rename).
    2.  Refine existing data structures (`Term`, `Rule`, `Assertion`, `Note`, `Logic`) to match the specification, adding support for `Note` fields like `metadata`, `graph`, `associatedTerms`, and defining `Relationship`.
    3.  Adapt utilities (`Json`, `KifParser`, `Log`, `Events`) for the new architecture, implementing KB assertion for logs/events via the future `KnowledgeBase`.
    4.  Refactor `Persist` and parts of `Cognition` to implement the `KnowledgeBase` interface and `Persistence` layer, handling generic data access and persistence across KB contexts.
    5.  Refactor `Reason` to implement the minimal, generic `TermLogicEngine`, removing hardcoded reasoning logic and making it purely rule-interpreting based on rules from the `KnowledgeBase`.
    6.  Refactor `Tools` to implement the `ToolRegistry`, loading `ToolDefinition` Notes from the `KnowledgeBase`.
    7.  Refactor `LM` to implement the minimal `LLMService`.
    8.  Refactor `Protocol` and `Cog`'s API handling to implement the minimal `ApiGateway`, translating messages to/from KB data.
    9.  Refactor `Cog`'s main loop logic into `SystemControl`, implementing the designed KB monitoring and processing loop.
    10. Implement the minimal set of Java Primitive Tools as defined in the detailed design, extracting atomic logic from existing `cognote` code where applicable.
    11. Refactor `Cog` to initialize and connect the new kernel components.
    12. Systematically remove all redundant or hardcoded behavior logic from the refactored Java classes. Remove any remaining redundant code from `doc/prototypes`.

**Phase 2: Bootstrap Universal Reality (Data - KB Content)**

*   **Focus:** Define the system's initial state and fundamental behavior by creating the necessary data (Notes, Terms, Relationships) in the KB, using the structures defined in the "Target Architecture Specification" and the detailed design from Phase 1. This phase involves writing data files loaded by the refactored kernel.
*   **Key Steps:**
    1.  Define the complete System Ontology as data files (e.g., KIF or JSON), including Note types, Relationship types, Term predicates/functions, and potentially schema definitions.
    2.  Define `ToolDefinition` Notes for all Primitive Tools as data files.
    3.  Define `Setting` Notes for default configuration as data files.
    4.  Define `RuleDefinition` Notes for the minimal bootstrap rules (API handling, basic event processing, loop triggering) as data files.
    5.  Implement the bootstrap loading logic in the refactored `Cog`/`SystemControl` to load these data files into the `KnowledgeBase` on startup.

**Phase 3: Implement Data-Driven Behaviors (Data - Rules & ToolDefs)**

*   **Focus:** Implement all complex system behaviors by writing more data (Rules, Tool Definitions, Ontology extensions) that the generic kernel processes. This phase is primarily data entry and rule writing.
*   **Key Steps:**
    1.  Write `RuleDefinition` Notes (and define new non-primitive Java Tools if absolutely necessary, adding their ToolDefinition Notes) to implement full reasoning strategies, planning, prioritization, resource management, and application-specific logic, leveraging the generic engine, primitive tools, System Ontology, and both Term/Graph data.
    2.  Define `ToolDefinition` Notes for higher-level tools implemented as composite behaviors via Rules.
    3.  Extend the System Ontology data as needed.
    4.  *Minimize* implementing new Java Primitive Tools; only do so if an atomic operation is truly impossible to build from existing primitives.

**Phase 4: Implement External Components (UI, P2P, Simulator)**

*   **Focus:** Build external components that interact *only* with the core KB via the defined API and by asserting data into the KB.
*   **Key Steps:**
    1.  Implement the Frontend UI to interact with the `ApiGateway`, visualizing the KB data and sending API commands.
    2.  Implement the P2P Network component to interact with the KB via asserting P2P state/events as Terms/Relationships and calling primitive tools via API.
    3.  Implement the Community Simulator component to interact with the KB via asserting simulation state/events/outcomes as Notes/Terms/Relationships and calling primitive tools via API.

**Phase 5: Refinement, Optimization, Testing, Documentation, and Self-Optimization**

*   **Focus:** Refine the system, ensure robustness, document everything, and implement advanced self-management and self-optimization using the data-driven architecture.
*   **Key Steps:**
    1.  Optimize the refactored generic Java kernel components.
    2.  Optimize the data (Ontology, Rules, Tool Definitions).
    3.  Implement comprehensive testing (including data-driven tests defined as Rules).
    4.  Enhance error handling (via Rules acting on Error Terms).
    5.  Implement security and privacy (potentially via Rules and Ontology).
    6.  Refine UI/UX.
    7.  Write complete documentation (Java kernel, API, and *crucially* the System Ontology, all defined Rules, all defined Tools).
    8.  Implement advanced self-optimization mechanisms via Rules acting on system state/metrics in the KB.
    9.  Prepare for deployment.

**Code Reduction and Functionality Expansion:**

*   **Code Reduction:** Achieved by making the Java codebase a minimal, fixed, generic kernel. All specific behaviors are defined as data (Rules, Tool Definitions, Ontology) in the KB. The Java code implements the mechanism (interpret, execute, store, translate), not the behavior. This is achieved by systematically refactoring existing `dumb.cog` code to remove hardcoded behavior.
*   **Functionality Expansion:** Achieved by simply adding new RuleDefinition and ToolDefinition Notes (and potentially new Note/Relationship types for the Ontology) to the KB. The generic engine automatically picks up and executes the new behavior defined in the data. This allows for potentially massive and rapid expansion of capabilities by leveraging the combined power of Term logic and Graph structures, driven by the recursive self-unification loop.

