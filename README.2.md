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

----

# spacegraph.js: Zooming User Interface Library

## 1. Overview
The Zooming User Interface Library is a general-purpose, extensible JavaScript library built with HTML, JavaScript, and three.js. It provides a fractal-like zooming interface that supports both 2D and 3D visualizations, enabling developers to create a wide variety of applications. The library is designed to be highly abstract, customizable, and easy to use, with a focus on a comprehensive scene graph, flexible event handling, and an interactive heads-up display (HUD).

**Purpose**: To serve as a ubiquitous JavaScript library that developers can include to build complex, interactive, and visually rich applications with minimal setup.

**Scope**: The library supports fractal scaling, multiple view modes, interactive widgets, a robust event handling system, and a HUD with system controls and a REPL for dynamic interaction.

---

## 2. Functional Requirements

### 2.1 Core Features

#### 2.1.1 Fractal Zooming Interface
- **Description**: The interface supports infinite zooming, where elements can be composed at different scales, resembling a fractal structure.
- **Behavior**:
    - Zooming allows seamless navigation between scales, with elements maintaining their functionality and appearance.
    - Auto-zoom feature highlights zoomable elements with a visible frame on hover.
    - Repeated auto-zoom on the same element reverts to the previous view, implementing an undo stack for zoom navigation.

#### 2.1.2 Scene Graph
- **Description**: A comprehensive scene graph manages all elements in the interface, including shapes, text, and widgets.
- **Features**:
    - Supports a hierarchical structure for organizing elements.
    - Includes primitives for shapes (e.g., rectangles, circles), text, and configuration options.
    - Allows easy manipulation and traversal for developers.
    - Supports HTML fragments as nodes, rendered using CSS 3D transforms for integration with the visualizer.

#### 2.1.3 View Modes
- **Description**: The library supports multiple visualization modes to cater to different use cases.
- **Modes**:
    - **2D Locked View**: A top-down projection, restricting navigation to 2D.
    - **3D Free Look View**: Allows free navigation in 3D space, with an auto-snap-back to a default forward view to maintain a primarily 2D focus.
    - **Spherical View**: Arranges elements on a sphere around the user for an immersive layout.
- **Implementation**:
    - All view modes are unified in a single model, allowing seamless switching.
    - Uses three.js for 3D rendering, with a focus on maintaining a mostly 2D experience.

#### 2.1.4 Event Handling System
- **Description**: An abstract, reconfigurable event handling system for user interactions.
- **Features**:
    - Maps inputs (keyboard, mouse, etc.) to outcomes (e.g., dragging, resizing, zooming).
    - Provides reasonable default mappings, hard-coded but editable.
    - Supports runtime reconfiguration, similar to video game control customization.
    - Handles interactions like moving elements with the mouse, resizing widgets, and organizing elements like a mind map.
    - Defines a comprehensive API for generic reactions to events.

#### 2.1.5 Widgets
- **Description**: Reusable components built from primitives and event handlers.
- **Examples**:
    - Buttons, sliders, and display components.
    - Sliders can be moved in 3D space, mimicking physical controls.
    - Components for wiring data flow systems, enabling complex workflows.
- **Behavior**:
    - Widgets are fully interactive and customizable.
    - Support drag-and-drop, resizing, and other mind-map-like organization.

#### 2.1.6 Heads-Up Display (HUD)
- **Description**: An overlay layer for system interaction and feedback.
- **Features**:
    - Displays logs, alerts, and other system messages in a scrolling HTML overlay.
    - Semi-translucent to blend with the main interface.
    - Includes interactive widgets for user responses.
    - Provides controls for:
        - Inspecting and manipulating the scene graph.
        - Accessing system-level settings and options.
    - Integrates a REPL (Read-Eval-Print Loop) for:
        - Executing commands (e.g., search, collect, probe system).
        - Supporting toy use and experimentation.

---

## 3. Technical Requirements

### 3.1 Platform and Technologies
- **Frontend**: HTML, JavaScript.
- **3D Rendering**: three.js for 3D visualization and CSS 3D transforms for HTML nodes.
- **Environment**: Browser-based, compatible with modern browsers (Chrome, Firefox, Safari, Edge).
- **Library Structure**: Modular, with a public API for easy integration into projects.

### 3.2 Performance
- **Target Capacity**: Must handle at least a few dozen to a hundred widgets without optimization concerns, based on prior prototypes.
- **Scalability**: Designed to support fractal scaling without predefined performance limits.

### 3.3 General-Purpose Design
- **Abstraction**: All components (event handling, scene graph, widgets) are abstract and generalized to support diverse applications.
- **Ease of Use**: Minimal setup required; developers can include the library and start building immediately.
- **Extensibility**: API and scene graph allow custom shapes, widgets, and interactions.

---

## 4. Non-Functional Requirements

### 4.1 Usability
- The library must be intuitive, with a clear API and documentation.
- Default event mappings should be familiar to users (e.g., click to select, drag to move).
- The HUD and REPL should provide immediate feedback for user actions.

### 4.2 Customizability
- Event mappings, widget behaviors, and view modes can be reconfigured at runtime.
- Developers can extend the library with custom primitives and workflows.

### 4.3 Maintainability
- Codebase should be modular, with clear separation of concerns (e.g., scene graph, event handling, rendering).
- Comprehensive API documentation for developers.

---

## 5. Implementation Notes
- **Scene Graph**: Use a tree-based data structure to manage elements, with nodes for shapes, text, and HTML fragments.
- **Event Handling**: Implement a pub-sub or observer pattern for abstract input-to-action mappings, with a configuration layer for defaults and runtime changes.
- **Zooming**: Leverage three.js camera controls for smooth zooming, with a stack to track zoom history.
- **View Modes**: Use three.js to toggle between 2D, 3D, and spherical projections, ensuring a unified model.
- **HUD**: Build as a separate HTML overlay with CSS for translucency, integrating WebGL for 3D interactions if needed.
- **REPL**: Implement a command parser with a set of predefined actions (search, collect, probe), extensible for custom commands.

---

## 6. Future Considerations
- **Data Flow Paradigms**: Support for complex workflows and data flow systems can be added as an extension.
- **Performance Optimization**: If needed, optimize for larger widget counts or complex scenes in later iterations.
- **Additional Widgets**: Expand the widget library based on developer feedback.

---

## 7. Summary
This specification outlines a powerful, general-purpose JavaScript library for a zooming user interface. Key features include fractal scaling, a robust scene graph, abstract event handling, multiple view modes, interactive widgets, and a HUD with a REPL. Built with three.js, HTML, and JavaScript, it aims to be a ubiquitous tool for developers to create diverse, visually engaging applications.

----

## Code Guidelines / Conventions

- Complete (fully functional)
- Professional-grade, not explanatory/educational
- Correct (bug-free and logically sound)
- Compact (minimal codebase size)
    - Using space-saving syntax constructs, like ternary/switch/etc..., to minimize lines and tokens
    - Using the latest language version's syntax options to best express code
- Consolidated (avoids unnecessary separation)
- Deduplicated (no redundant logic)
    - Introduce helpful abstractions functions, parameters, and classes to share common code
    - Apply "don't repeat yourself" principles
- Modular (logically organized, supporting abstraction)
- Remove all comments, relying only on self-documenting code
    - Clear naming and structure
- Use the latest version of the language, APIs, and dependencies