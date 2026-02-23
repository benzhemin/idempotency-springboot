# Superpowers Interaction Sequence Diagram

## Full Session Flow

```mermaid
sequenceDiagram
    actor User
    participant SP as using-superpowers<br/>(Gate)
    participant BS as brainstorming<br/>(Design)
    participant WP as writing-plans<br/>(Blueprint)
    participant C7 as Context7<br/>(Docs Lookup)
    participant SD as subagent-driven-dev<br/>(Execution)
    participant SA as Subagent<br/>(Worker)
    participant Git as Git

    Note over User,Git: Phase 1: Skill Discovery
    User->>SP: "build idempotency using springboot gradle based app"
    SP->>SP: Classify request: "build X" = creative task
    SP->>BS: Route to brainstorming skill

    Note over User,Git: Phase 2: Brainstorming (Design Before Code)
    BS->>BS: HARD GATE: No code until design approved
    BS->>BS: Explore project context (empty directory)

    loop One question at a time (6 rounds)
        BS->>User: Multiple-choice question
        User->>BS: Selection
    end

    Note right of User: Q1: REST / Messaging / Both → REST<br/>Q2: Annotation / Filter / AOP → Annotation<br/>Q3: Cache response / Reject / Lock → Cache<br/>Q4: TTL 24h / 1h / Configurable → 1h<br/>Q5: Scaffold / Library only → Library

    BS->>User: Present Design Section 1: Architecture
    User->>BS: Approved

    BS->>User: Present Design Section 2: @Idempotent Annotation
    User->>BS: "too simple, what customization?"
    Note right of User: USER PUSHBACK #1<br/>Annotation enriched with<br/>5 attributes from real scenarios
    BS->>User: Propose 5 attributes with use cases
    User->>BS: "All five"

    BS->>User: Present Design Section 3: Data Flow & Error Handling
    User->>BS: "CachedResponse only need body, why statusCode/headers?"
    Note right of User: USER PUSHBACK #2<br/>CachedResponse trimmed to<br/>body + statusCode only
    BS->>User: Revised: body + statusCode
    User->>BS: Approved

    BS->>User: Present Design Section 4: Project Structure
    User->>BS: Approved

    BS->>Git: Commit design doc
    Git-->>BS: ccb5647

    BS->>WP: Terminal state → invoke writing-plans

    Note over User,Git: Phase 3: Writing Plans (Blueprint)
    WP->>C7: Query Spring Boot 3.x Redis + AOP patterns
    C7-->>WP: Verified: Gradle plugin versions, RedisTemplate config

    WP->>WP: Generate 8 tasks, 16 tests, complete code, TDD flow
    WP->>Git: Commit implementation plan
    Git-->>WP: af6f55b

    WP->>User: "Plan complete. Subagent-Driven or Parallel Session?"
    User->>WP: "which one is better?"
    WP->>User: Comparison table with trade-offs
    User->>WP: "Subagent-Driven"

    WP->>SD: Invoke subagent-driven-development

    Note over User,Git: Phase 4: Subagent-Driven Execution
    SD->>SD: Extract all 8 tasks from plan
    SD->>SD: Create task tracking (TodoWrite)

    rect rgb(230, 245, 230)
        Note over SD,Git: Subagent 1: Tasks 1-3 (Scaffolding — batched)
        SD->>SA: Dispatch with full task text (9 files)
        SA->>SA: Create files, compile, run tests
        SA-->>SD: 2/2 tests PASS, compiled OK
        SA->>Git: Commit
        Git-->>SA: 3ddf147
    end

    rect rgb(230, 240, 250)
        Note over SD,Git: Subagent 2: Tasks 4-5 (Store + Config)
        SD->>SA: Dispatch with full task text (4 files)
        SA->>SA: Create files, compile
        SA-->>SD: Compiled OK, tests SKIPPED (no Docker)
        SA->>Git: Commit
        Git-->>SA: f3a2a9c
    end

    rect rgb(255, 245, 230)
        Note over SD,Git: Subagent 3: Task 6 (Aspect — the core)
        SD->>SA: Dispatch with full task text (2 files)
        SA->>SA: Create files, compile, run unit tests
        SA->>SA: Self-fix: remove unnecessary Mockito stubs
        SA-->>SD: 6/6 tests PASS
        SA->>Git: Commit
        Git-->>SA: 3838d70
    end

    rect rgb(240, 230, 250)
        Note over SD,Git: Subagent 4: Task 7 (Integration Tests)
        SD->>User: "Docker not running. Create files only, or start Colima?"
        User->>SD: "Create files, skip running"
        SD->>SA: Dispatch with full task text (2 files)
        SA->>SA: Create files, compile
        SA-->>SD: Compiled OK
        SA->>Git: Commit
        Git-->>SA: c1d95b2
    end

    SD->>SD: Run all unit tests: 9/9 PASS

    Note over User,Git: Phase 5: Bug Discovery & Fix
    SD->>User: Flag InputStream issue with includeBody=true
    Note right of User: PROACTIVE BUG REPORT<br/>getInputStream() consumed twice<br/>will fail in production
    User->>SD: "clarify later, continue"

    SD->>User: Detailed explanation of InputStream problem
    User->>SD: "how about read parsed request object from joinPoint args?"
    Note right of User: USER PROPOSES FIX<br/>Use joinPoint.getArgs() to read<br/>already-deserialized @RequestBody

    SD->>User: Assessment: why this works (lifecycle diagram)
    User->>SD: "yes, implement it"

    SD->>SD: Edit IdempotencyAspect (replace hashBody)
    SD->>SD: Edit IdempotencyAspectTest (add MethodSignature mock)
    SD->>SD: Run tests: 9/9 PASS (7 aspect + 2 model)
    SD->>Git: Commit fix
    Git-->>SD: ee4bff1
```

## Interaction Pattern Summary

```mermaid
flowchart TD
    A["User Request<br/><i>'build X with Y'</i>"] --> B{"using-superpowers<br/>Gate"}

    B -->|"build/add/implement"| C["brainstorming"]
    B -->|"fix/debug"| D["systematic-debugging"]
    B -->|"simple < 3 files"| E["Direct Implementation"]

    C --> F{"User Approves<br/>Design?"}
    F -->|"no / pushback"| C
    F -->|"yes"| G["writing-plans"]

    G --> H{"Execution<br/>Choice"}
    H -->|"same session"| I["subagent-driven-dev"]
    H -->|"new session"| J["executing-plans"]

    I --> K["Per-Task Loop"]
    J --> K

    K --> L["Dispatch Subagent"]
    L --> M{"Tests Pass?"}
    M -->|"yes"| N{"More Tasks?"}
    M -->|"no"| O["Fix & Re-test"]
    O --> M
    N -->|"yes"| K
    N -->|"no"| P["finishing-a-branch"]

    style A fill:#e8f5e9
    style C fill:#fff3e0
    style G fill:#e3f2fd
    style I fill:#f3e5f5
    style P fill:#e8f5e9
```

## Where User Interaction Happens

```mermaid
flowchart LR
    subgraph "USER DRIVES"
        U1["Scope Decisions<br/><i>REST vs Messaging</i>"]
        U2["Architecture Choices<br/><i>AOP vs Filter vs Interceptor</i>"]
        U3["Design Pushback<br/><i>'too simple' / 'why this field?'</i>"]
        U4["Trade-off Resolution<br/><i>body+status vs body only</i>"]
        U5["Execution Strategy<br/><i>Subagent vs Parallel</i>"]
        U6["Risk Acceptance<br/><i>'skip Docker, fix later'</i>"]
        U7["Bug Fix Direction<br/><i>'use joinPoint.getArgs()'</i>"]
    end

    subgraph "SYSTEM DRIVES"
        S1["Skill Routing<br/><i>using-superpowers gate</i>"]
        S2["Question Ordering<br/><i>one at a time, multiple choice</i>"]
        S3["Hard Gates<br/><i>no code before design</i>"]
        S4["TDD Enforcement<br/><i>test first in every task</i>"]
        S5["Subagent Isolation<br/><i>fresh context per task</i>"]
        S6["Proactive Bug Flagging<br/><i>InputStream issue</i>"]
    end

    style U1 fill:#e8f5e9
    style U2 fill:#e8f5e9
    style U3 fill:#e8f5e9
    style U4 fill:#e8f5e9
    style U5 fill:#e8f5e9
    style U6 fill:#e8f5e9
    style U7 fill:#e8f5e9
    style S1 fill:#e3f2fd
    style S2 fill:#e3f2fd
    style S3 fill:#e3f2fd
    style S4 fill:#e3f2fd
    style S5 fill:#e3f2fd
    style S6 fill:#e3f2fd
```

## Quick Reference: How to Trigger This Flow

| What You Say | What Fires | What Happens |
|---|---|---|
| "build X" / "create Y" / "implement Z" | brainstorming → writing-plans → subagent-driven | Full design-first flow |
| "fix this bug" / "why is X broken" | systematic-debugging | Debug-first flow |
| "add a field to X" (trivial) | Nothing — direct edit | Too small for skills |
| "refactor the auth system" | brainstorming → writing-plans → subagent-driven | Full flow (multi-file) |

## Key Triggers That Shaped Quality

```mermaid
timeline
    title Moments That Shaped The Output
    section Brainstorming
        User selects REST only : Scoped the problem, avoided over-engineering messaging support
        User says "too simple" : Enriched annotation from 0 to 5 attributes with real use cases
        User challenges CachedResponse : Trimmed 4 fields to 2, leaner model
    section Planning
        Context7 lookup : Verified Spring Boot 3.x patterns are current
        User picks Subagent-Driven : Stayed in session, interactive, caught issues faster
    section Execution
        Subagent self-fixes Mockito stubs : Strict stubbing caught dead test code
        User skips Docker tests : Pragmatic — compiled but deferred runtime verification
    section Post-Implementation
        System flags InputStream bug : Proactive — caught before user noticed
        User proposes joinPoint.getArgs() : Domain expertise shaped the fix, not just AI
```
