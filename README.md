# Netention

Netention is a system for organizing, prioritizing, and evolving thoughts ("Notes") into actionable results. It emphasizes real-time communication, semantic matching, and agentic capabilities, allowing notes to evolve and interact.

## Core Concepts

- **Notes:** The central data structure. Notes represent thoughts, ideas, or descriptions. They can consist of factual ("as they are") and conditional ("how you would like them to be") semantic descriptions. Notes are designed to be shareable, private by default, support persistent queries, and enable fast and precise semantic matching.

- Thought Evolution: `Note`s describe thoughts and ideas, allowing them to evolve agentically.
- Shared Notes: Create, prioritize, and manage data collaboratively as `Note`s.
- Privacy by Default: All `Note`s are private unless explicitly shared.
- Tolerates vagueness and incomplete information 
- Agents: `Note`s represent the control surface for swarms of **agents** that use the reasoning engine and tools to pursue goals, react to, or anticipate changes.
- Persistent Queries: `Note`s represent ongoing search interests.
- Semantic Matching: `Note`s capture meaning and intent.
- Notifications: The app receives matches to shared `Note`s as replies.
- Real vs. Imaginary: Matches factual descriptions of real things to hypothetical (acceptable) descriptions of imaginary
  things - effecting the realization of imagination.

- **Tools:** External capabilities that the system can invoke based on its reasoning:
```
  Language Model interaction (LangChain/LangChainJS/LangChain4J/etc...)
      - supporting, at least: Ollama (localhost:11434) and Gemini
  GetNoteText
  ModifyNoteText
  Graph search, traversal, etc..
  Web Search
  Code writing
  Code execution
  FindAssertions
  LogMessage
  IdentifyConcepts
  GenerateQuestions
  DecomposeGoal
  Summarize
  Enhance
  AssertKIF
  Query
  Retract
  Api
  Echo        
  FileOperations
  UserInteraction
  GenerateTaskLogic
  Inspect 
  EvalExpr
  IfElse
  Generate
  Reflect
  Schedule
  Plan
  Reason
  DefineConcept
  Exec
```

- **Real-time Communication:** A WebSocket server enables clients (UIs, other systems) to interact with the system in real-time, sending commands and receiving updates.
- **Semantic Matching:** Notes and queries can be matched based on their meaning using various strategies:
    - comparisons of semantic metadata (facts satisfying conditions)
    - vector embeddings
    
- **Graph Structure:** Notes and their relationships form a graph, which can be visualized and reasoned about.

## Components

**Core:** The backend application.
    *   Tool execution framework.
    *   Resource management (e.g., LLM calls, processing cycles).
    *   Real-time communication server (WebSocket).
    *   Planning and task management.
    *   Notifications and event system.
    - Coordinates system proceses
    - Manages prioritizable resources
    - Manages notifications and events, used in implementing app features and interacting with users
    - Develops and executes plans
    - System activity guided by user's Note editing

**UI:** Application user interface
    
    Fuses a TODO list with a Mind map
    
    **TODO List (ui/note):** editing and managing Notes
        *   WYSIWYG Rich-text editing
        *   Semantic ontology fields/forms/templates
        *   Sorting, searching, filtering.
        *   Status and priority management.
        *   Viewing and controlling agent execution
    
    **Mind Map (ui/mindmap):** A free-form mindmap editing and organizing Notes as a node/edge graph.
        *   Visualizing Note relationships.
        *   Interactive graph editing.
        *   Layout algorithms.
        - Node/Edge Graph editing
        - SpaceGraph.js (see below)

4.  **P2P Network:** A decentralized network layer for sharing Notes and knowledge between Netention instances.
    *   Use Nostr for Bootstrap and Publishing
    *   Handles identity, encryption, and synchronization.
    *   Privacy by default, Publish with confirmation
    
5.  **Community Simulator:** A ui for simulating interactions between Notes and agents.
    *   Testing and optimizing the system's ontology and reasoning heuristics.
    *   Generating synthetic data.
    *   Narrating the simulation process.
    *   Resembles 'The Sims'
    - Narrates real-world simulated community interactions
    - Semi-supervised tool for developing and optimizing the ontology (expressivitiy, descriptive ergonomics, metalinguistic abstraction, etc...), matching heuristics, and performance testing
    - Sample randomly, with optional domain constraints: 
        - situations
        - intentions
        - description methods
        - etc...
    - Identifies missing bridging ontology or rules
    - Identifies user or multi-user patterns or workflows that can be managed or reinforced