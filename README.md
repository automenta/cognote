# Netention

Organize, prioritize, and grow thoughts into actionable results with real-time communication, matching, and analysis.

## Notes

- **Thought Evolution:** `Note`s describe thoughts and ideas, allowing them to evolve agentically.
- **Shared Notes:** Create, prioritize, and manage data collaboratively as `Note`s.
- **Privacy by Default:** All `Note`s are private unless explicitly shared.

## Search and Match

- **Persistent Queries:** `Note`s can act as ongoing search interests.
- **Semantic Matching:** `Note`s capture meaning and intent.
- **Notifications:** The app receives matches to shared `Note`s as replies.
- **Real vs. Imaginary:** Matches factual descriptions of real things to hypothetical (acceptable) descriptions of
  imaginary things - effecting the realization of imagination.

## Components

### Logic engine

- Prolog-like logic and reasoning
- Coordinates system proceses
- Manages prioritizable resources
- Manages notifications and events, used in implementing app features and interacting with users
- Develops and executes plans
- System activity guided by user's Note editing

### TODO List (App)

- Conventional "TODO List" editor
- Semantic Ontology: fields/forms/templates inserted by menu, or suggested
- WYSIWYG editor
- List: Sort, Search, Filter

### Mind Map (App)

- Free-form Note editor and organization tool
- Node/Edge Graph editing

### Semantic Simulator (Plugin)

- Narrates real-world simulated community interactions
- Semi-supervised tool for developing ontology, matching heuristics, and performance testing

### P2P Network (Plugin)

- **Decentralized**
- **Secure**
    - End-to-end encryption protects private data.
    - Crypto-signing ensures `Note` integrity and provenance.
- Networks:
    1. Nostr
    2. LibP2P
    3. etc...

----

## Code Guidelines

- Complete (fully functional)
    - Ensure all functionality remains present, in some way.
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
- Assume any referenced dependencies exist, so do not create stubs/mocks
