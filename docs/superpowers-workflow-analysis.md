# Superpowers Skill Workflow Analysis

> A detailed trace of how the superpowers skill system orchestrated the idempotency library build — from a one-line request to a production-quality, TDD-driven implementation with 9 passing tests and 7 clean commits.

## The One-Line Request

```
build idempotency using springboot gradle based app, which use redis as cache
```

That's all the user typed. Everything below was triggered by the skill chain.

---

## Phase 1: Skill Discovery

### Trigger: `using-superpowers` (automatic on session start)

The `using-superpowers` skill is the entry point. It establishes a single rule:

> **Invoke relevant skills BEFORE any response or action.** Even a 1% chance a skill might apply means you should invoke it.

This prevents the most common failure mode: jumping straight into code. Without this gate, the response to "build idempotency" would have been 200 lines of Java dumped into files with no discussion.

**What it produced:** A decision tree that routes to the correct skill. The request "build X" matches the brainstorming skill.

---

## Phase 2: Brainstorming (Design Before Code)

### Trigger: `superpowers:brainstorming`

**Why it fired:** The request is "build X" — a creative/implementation task. The brainstorming skill has a hard gate:

> Do NOT invoke any implementation skill, write any code, scaffold any project, or take any implementation action until you have presented a design and the user has approved it.

### What it enforced (checklist):

| Step | What Happened | User Decision |
|------|--------------|---------------|
| 1. Explore context | Checked working directory — empty, greenfield project | — |
| 2. Clarify: scope | "What type of API operations?" | REST API endpoints |
| 3. Clarify: integration | "How to apply to controllers?" | Annotation-based |
| 4. Clarify: behavior | "What on duplicate request?" | Return cached response |
| 5. Clarify: TTL | "How long to retain keys?" | 1 hour |
| 6. Clarify: project | "Scaffold or library only?" | Library module only |

**Key behavior:** One question at a time, multiple choice preferred. This prevents information overload and forces sequential decision-making.

### Design Enrichment (User-Driven)

The user pushed back on the initial `@Idempotent` annotation design:

> "this idempotent annotation too simple, is there any annotation value that can do some customization"

This led to 5 annotation attributes being added (`ttl`, `headerName`, `keyPrefix`, `mandatory`, `includeBody`) — each tied to a real-world scenario. The user chose "All five."

The user also pushed back on `CachedResponse`:

> "cachedResponse only need body, and why status code and headers and content type need here?"

This led to a leaner model (body + status code only, dropped headers and content type). The design was shaped by the user's judgment, not assumed.

### Design Output

A 4-section design presented incrementally with approval after each section:
1. Components & Architecture (approved)
2. @Idempotent Annotation with 5 attributes (approved)
3. Data Flow & Error Handling (revised based on feedback)
4. Project Structure & Dependencies (approved)

### Artifact: `docs/plans/2026-02-22-idempotency-design.md`

Committed at `ccb5647`. This document captures every decision and its rationale.

---

## Phase 3: Writing Plans (Implementation Blueprint)

### Trigger: `superpowers:writing-plans`

**Why it fired:** The brainstorming skill's terminal state is always `writing-plans`. This is hard-coded — brainstorming never jumps directly to implementation.

### What it enforced:

The writing-plans skill requires:
- **Bite-sized tasks** (2-5 minutes each)
- **Exact file paths** for every file
- **Complete code in the plan** (not "add validation here")
- **TDD flow**: write failing test → verify it fails → implement → verify it passes → commit
- **Exact commands** with expected output

### Plan Structure (8 Tasks, 16 Tests)

| Task | Files | Tests |
|------|-------|-------|
| 1. Scaffold Gradle project | 4 files | — |
| 2. CachedResponse + exceptions | 4 files | 2 (serialization) |
| 3. @Idempotent annotation | 1 file | — |
| 4. IdempotencyStore + Redis impl | 3 files | 3 (integration) |
| 5. IdempotencyConfig | 1 file | — |
| 6. IdempotencyAspect | 2 files | 6 (unit, mocked) |
| 7. E2E integration tests | 2 files | 5 (full stack) |
| 8. Final verification | — | all 16 |

### Context7 Lookup

Before writing the plan, a Context7 documentation query verified Spring Boot 3.x Redis + AOP patterns were current (correct Gradle plugin versions, `StringRedisTemplate` auto-config, etc.).

### Artifact: `docs/plans/2026-02-22-idempotency-implementation.md`

Committed at `af6f55b`. Every line of code that would be written was already in this document.

---

## Phase 4: Subagent-Driven Development (Execution)

### Trigger: `superpowers:subagent-driven-development`

**Why it fired:** User chose "Subagent-Driven" over "Parallel Session" when given the execution choice.

### What it enforced:

- Fresh subagent per task (no context pollution between tasks)
- Two-stage review: spec compliance, then code quality
- Never dispatch parallel implementation agents (conflicts)
- Full task text provided to each agent (agent doesn't read plan file)

### Execution Trace

```
Subagent 1: Tasks 1-3 (scaffolding)
  ├─ Created 9 files
  ├─ Compiled: BUILD SUCCESSFUL
  ├─ Tests: 2/2 CachedResponseTest PASS
  └─ Commit: 3ddf147

Subagent 2: Tasks 4-5 (store + config)
  ├─ Created 4 files
  ├─ Compiled: BUILD SUCCESSFUL
  ├─ Tests: SKIPPED (Docker not running)
  └─ Commit: f3a2a9c

Subagent 3: Task 6 (aspect — the core)
  ├─ Created 2 files
  ├─ Compiled: BUILD SUCCESSFUL
  ├─ Tests: 6/6 PASS
  ├─ Self-fix: removed unnecessary Mockito stubs (strict stubbing)
  └─ Commit: 3838d70

Subagent 4: Task 7 (integration tests)
  ├─ Created 2 files
  ├─ Compiled: BUILD SUCCESSFUL
  ├─ Tests: SKIPPED (Docker not running)
  └─ Commit: c1d95b2
```

### Batching Decision

Tasks 1-3 were pure scaffolding (no logic, no meaningful tests). Instead of dispatching 3 agents + 6 review cycles for boilerplate, they were batched into one agent. This is a judgment call the skill allows — the principle is "fresh agent per task" but the orchestrator can batch trivial tasks.

---

## Phase 5: Critical Bug Discovery (Post-Implementation)

### Trigger: User knowledge + Insight annotations

The orchestrator flagged a production issue before the user even noticed:

> `request.getInputStream().readAllBytes()` can only be read once. If `includeBody=true`, the aspect reads the body for hashing, but the controller also needs to read it.

The user then proposed a better solution:

> "how about this that make the annotation proceed after spring @requestbody then read the parsed request object"

This led to the `joinPoint.getArgs()` approach — reading the already-deserialized `@RequestBody` object instead of the raw InputStream.

### Fix Applied Directly (Not Via Subagent)

Because this was a targeted 2-file change with clear scope, it was applied directly rather than dispatching another subagent. The fix:
- Replaced `hashBody(HttpServletRequest)` with `hashRequestBody(ProceedingJoinPoint)`
- Added `extractRequestBody()` using reflection on method parameter annotations
- Updated tests with a `dummyEndpoint(@RequestBody Map body)` technique
- Added a new test `shouldCacheBodyHashWhenIncludeBodyEnabled`

### Artifact: Commit `ee4bff1`

---

## The Complete Skill Chain

```
User: "build idempotency using springboot gradle based app"
  │
  ▼
using-superpowers (gate)
  │ "Is this a build task? → Yes → brainstorming skill"
  ▼
brainstorming (design)
  │ 6 questions, 4 design sections, user approval
  │ Output: design doc (committed)
  ▼
writing-plans (blueprint)
  │ 8 tasks, 16 tests, exact code, TDD flow
  │ Output: implementation plan (committed)
  ▼
subagent-driven-development (execution)
  │ 4 subagents, 9 tests passing, 4 commits
  │ Output: working library
  ▼
User review → bug found → direct fix (committed)
```

## Why This Produced High Quality

### 1. Forced Discussion Before Code

The brainstorming skill's hard gate prevented any code from being written until the design was approved. This caught:
- Unnecessary `CachedResponse` fields (headers, contentType) — removed before any code existed
- Missing annotation attributes (5 added based on real scenarios)
- The `mandatory` vs `optional` distinction

### 2. Incremental Validation

Each design section was presented and approved separately. The user could (and did) push back on individual sections without invalidating the whole design.

### 3. Complete Plan With Complete Code

The writing-plans skill required every line of code to be in the plan before execution. This means:
- No improvisation during implementation
- Subagents execute a known-good plan, not vague instructions
- The plan itself is reviewable and committable

### 4. Fresh Context Per Task

Each subagent started with zero context pollution. No accumulated assumptions, no "I remember from earlier" mistakes. The orchestrator provided exactly the context each agent needed.

### 5. TDD by Default

The plan structured every task as: test first → verify fail → implement → verify pass → commit. This isn't optional — the writing-plans skill enforces it.

### 6. User as Decision-Maker

The skills consistently deferred to the user for:
- Architecture choices (AOP vs Filter vs Interceptor)
- Scope decisions (all 5 attributes vs core 3)
- Trade-off resolution (body + status code vs body only)
- Risk acceptance (proceed without Docker, fix stream issue later)

---

## Guide for Next Time

### When to use this flow

Any request that matches "build X", "add feature Y", or "implement Z" should trigger this chain. The key signals:

- **Greenfield or significant new feature** → full chain (brainstorm → plan → execute)
- **Modification to existing code** → brainstorm (to understand impact) → plan → execute
- **Bug fix** → `systematic-debugging` skill instead (different chain)
- **Simple change (< 3 files, obvious implementation)** → skip skills, do directly

### The non-negotiable gates

1. **Never write code before design approval** — even if the task seems simple
2. **Never skip the plan** — the plan IS the quality gate
3. **Never let subagents improvise** — provide full task text, not "go figure it out"
4. **Always present trade-offs** — 2-3 approaches with recommendation, let user choose

### Common mistakes to avoid

| Mistake | What happens | Prevention |
|---------|-------------|------------|
| Skip brainstorming for "simple" tasks | Wrong assumptions baked into code | Hard gate: design before code |
| Ask all questions at once | User overwhelmed, gives shallow answers | One question per message |
| Plan says "add validation" | Subagent guesses wrong | Complete code in plan |
| Reuse subagent across tasks | Context pollution, stale assumptions | Fresh agent per task |
| Ignore user pushback | Build the wrong thing confidently | Every section needs approval |

### Skill invocation order

```
1. using-superpowers     → routes to correct skill
2. brainstorming         → design with user approval
3. writing-plans         → detailed implementation blueprint
4. subagent-driven-dev   → execute with review gates
   OR executing-plans    → parallel session execution
5. finishing-a-branch    → merge/PR/cleanup
```

### Time investment breakdown (this session)

| Phase | Approximate effort | Value |
|-------|-------------------|-------|
| Brainstorming | 30% | Prevented wrong assumptions, shaped design |
| Planning | 20% | Created executable blueprint |
| Execution | 40% | Mechanical — subagents follow the plan |
| Bug fix | 10% | Caught production issue before deployment |

The front-loaded investment in brainstorming and planning meant execution was fast and correct. The bug that was found (InputStream consumption) was caught through domain knowledge shared during implementation, not through painful production debugging.
