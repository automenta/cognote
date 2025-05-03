# Netention

Netention is a system for organizing, prioritizing, and evolving thoughts ("Notes") into actionable results. It emphasizes real-time communication, semantic matching, and agentic capabilities, allowing notes to evolve and interact.

## Core Concepts

- **Notes:** The central data structure. Notes represent thoughts, ideas, or descriptions. They can be factual ("as they are") or conditional ("how you would like them to be"). Notes are designed to be shareable, private by default, support persistent queries, and enable semantic matching.
- **Prolog-like Logic and Reasoning:** A core component for processing and evolving Notes based on rules and facts. Supports unification, forward/backward chaining, and rewrite rules.
- **Tools:** External capabilities (like interacting with LLMs, searching the web, running code) that the system can invoke based on its reasoning.
- **Agents:** Notes can become "active" and act as agents, using the reasoning engine and tools to pursue goals or react to changes.
- **Real-time Communication:** A WebSocket server allows clients (UIs, other systems) to interact with the system in real-time, sending commands and receiving updates.
- **Semantic Matching:** Notes and queries can be matched based on their meaning, likely using vector embeddings.
- **Graph Structure:** Notes and their relationships form a graph, which can be visualized and reasoned about.

## Components

1.  **Core:** The backend Java application.
    *   Prolog-like logic and reasoning engine.
    *   Knowledge base management (Notes, facts, rules, embeddings).
    *   Tool execution framework.
    *   Resource management (e.g., LLM calls, processing cycles).
    *   Real-time communication server (WebSocket).
    *   Planning and task management.
    *   Notifications and event system.
2.  **TODO List (ui/note):** A conventional web UI for editing and managing Notes as a list.
    *   WYSIWYG editing.
    *   Semantic ontology insertion/forms.
    *   Sorting, searching, filtering.
    *   Status and priority management.
3.  **Mind Map (ui/mindmap):** A free-form web UI for editing and organizing Notes as a node/edge graph.
    *   Visualizing Note relationships.
    *   Interactive graph editing.
    *   Layout algorithms.
4.  **P2P Network:** A decentralized network layer for sharing Notes and knowledge between Netention instances.
    *   Based on protocols like Nostr or LibP2P.
    *   Handles identity, encryption, and synchronization.
    *   Privacy by default.
5.  **Community Simulator:** A tool for simulating interactions between Notes and agents.
    *   Testing and optimizing the system's ontology and reasoning heuristics.
    *   Generating synthetic data.
    *   Narrating the simulation process.

## Development Status

The project is currently in a prototyping phase, exploring different architectural approaches and technologies in the `doc/prototypes` directory. The `src/main/java` directory contains the beginnings of a core Java backend.

## Getting Started

(Instructions for setting up the development environment and running the current code will go here)

## Contributing

(Guidelines for contributing will go here)

## Code Guidelines

See `CODE.md`
