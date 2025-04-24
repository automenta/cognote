# netention

Organize, prioritize, and grow thoughts into actionable results with real-time communication, matching, and analysis.

## Features

### Objects

- **Shared Objects:** Create, prioritize, and manage data collaboratively as `Note`s.
- **Thought Evolution:** `Note`s describe thoughts and ideas.
- **Privacy by Default:** All `Note`s are private unless explicitly shared.

### Search and Match

- **Persistent Queries:** `Note`s can act as ongoing search interests.
- **Semantic Matching:** `Note`s capture meaning and intent.
- **Notifications:** The app receives matches to shared `Note`s as replies.

### P2P Network

- **Decentralized**
- **Secure**
    - End-to-end encryption protects private data.
    - Crypto-signing ensures `Note` integrity and provenance.

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