---

description: "Task list template for feature implementation"
---

# Tasks: Wallops Notices

**Input**: Design documents from `/specs/010-wallops-notices/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/wallops-command.md, quickstart.md

**Tests**: Included — this project's constitution (Testing Standards) requires every new
feature to ship with automated tests covering its primary behavior and documented edge
cases, and every existing admin-extension command (`KILL`, `OPER`) already follows this
via an end-to-end test in `jircd-integration-tests`. This feature follows the same
convention rather than introducing a new one.

**Organization**: Tasks are grouped by user story (spec.md P1/P2/P3) to enable independent
implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

Existing multi-module Gradle project (plan.md "Project Structure") — no new modules:

- `jircd-core/src/main/java/net/jircd/core/session/` — user-mode catalog
- `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/` — the
  privileged command handler and its registration
- `jircd-integration-tests/src/test/java/net/jircd/integration/` — end-to-end tests

---

## Phase 1: Setup

**Purpose**: Confirm a clean baseline before touching any source.

- [ ] T001 Run `./gradlew build` from the repo root and confirm it succeeds with no
      source changes yet, establishing the pre-feature baseline.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The one piece of core infrastructure every user story needs.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [ ] T002 Add the `WALLOPS` user-mode constant to `UserMode.CORE_CATALOG` in
      `jircd-core/src/main/java/net/jircd/core/session/UserMode.java`:
      `public static final UserMode WALLOPS = new UserMode("wallops", 'w', CORE, true);`
      added to the `CORE_CATALOG` set alongside `OPERATOR`/`INVISIBLE` (data-model.md
      "`UserMode.WALLOPS`"). No other file changes — `UserModeCommandHandler`'s
      `findByFlag`/`formatModes` already iterate `CORE_CATALOG` generically, so `+w`/`-w`
      self-set and `MODE <self>` query support for the new flag require no code changes of
      their own (research.md, decision 1).

**Checkpoint**: `UserMode.WALLOPS` exists and is already self-settable/queryable via the
generic `MODE` path — verify with a quick manual `MODE <self> +w` against a locally run
server before proceeding, per quickstart.md Scenario 4 steps 1-2.

---

## Phase 3: User Story 1 - Administrator broadcasts an operational notice (Priority: P1) 🎯 MVP

**Goal**: An administrator can send a `WALLOPS <text>` and have it delivered, with their
own hostmask as sender, to every currently connected session that has opted in
(`UserMode.WALLOPS` set) — including themselves, if they are opted in.

**Independent Test**: `OPER` one connection, set `+w` on a second connection, send
`WALLOPS` from the first, confirm only the second (and, if also opted in, the first)
receives it — per quickstart.md Scenarios 1-3 and 6.

### Tests for User Story 1

- [ ] T003 [US1] Write integration tests in
      `jircd-integration-tests/src/test/java/net/jircd/integration/WallopsCommandTest.java`
      covering: (a) an `OPER`'d sender's `WALLOPS` is delivered, prefixed with the sender's
      own hostmask, to a connected session with `+w` set (quickstart.md Scenario 1); (b) a
      `WALLOPS` send with zero currently opted-in sessions completes with no error reply and
      no delivery to anyone (Scenario 2); (c) an `OPER`'d sender who has also set `+w`
      receives a copy of their own notice (Scenario 3); (d) `WALLOPS` with no parameter
      yields `461 ERR_NEEDMOREPARAMS`, and `WALLOPS :` (empty text) yields
      `412 ERR_NOTEXTTOSEND`, in both cases with no delivery to anyone (Scenario 6). These
      tests are expected to fail until T004/T005 are complete.

### Implementation for User Story 1

- [ ] T004 [US1] Create
      `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/WallopsCommandHandler.java`
      implementing `CommandHandler`, following `KillCommandHandler`'s established shape:
      constructor takes `NicknameRegistry`, `ExtensionRegistry`, and `Supplier<String> serverName`;
      `handle(session, message)` (1) rejects with `481 ERR_NOPRIVILEGES` (via `Replies.send`,
      identical wording to `KillCommandHandler`) unless
      `AdminPrivilege.isAuthorized(session, extensionRegistry)`; (2) rejects with
      `461 ERR_NEEDMOREPARAMS` if `message.params()` is empty, else `412 ERR_NOTEXTTOSEND`
      if the text is blank; (3) otherwise builds one `Message` with
      `Hostmask.format(session.nickname(), session.ident(), session.realHostname())` as
      prefix, `Command.WALLOPS` as command, and the text as its sole param, then delivers it
      via `writer().enqueueRaw(...)` to every session in `nicknameRegistry.all()` whose
      `userModes()` contains `UserMode.WALLOPS` (contracts/wallops-command.md "`WALLOPS`
      command"; data-model.md "`WALLOPS` notice"). No confirmation reply is sent to the
      sender on success (research.md, decision 4).
- [ ] T005 [US1] Register the new handler in
      `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/AdminExtension.java`'s
      `start(ServerContext context)`: add
      `registrar.register(Command.WALLOPS, new WallopsCommandHandler(context.nicknameRegistry(), extensionRegistry, serverName));`
      alongside the six existing registrations, and update the class Javadoc's "Registers
      the six ... handlers" to reflect the seventh. (depends on T004)

**Checkpoint**: User Story 1 is fully functional and independently testable — run T003's
tests against T004/T005; all should pass.

---

## Phase 4: User Story 2 - User opts in or out of receiving operational notices (Priority: P2)

**Goal**: Any connected user can self-enable or self-disable receipt of `WALLOPS` notices,
and never change another user's preference.

**Independent Test**: `MODE <self> +w`, confirm `MODE <self>` query reflects it;
`MODE <self> -w`, confirm it's gone; attempt `MODE <other> +w`, confirm rejection — per
quickstart.md Scenario 4. No `WALLOPS` send is needed to verify this story.

### Tests for User Story 2

- [ ] T006 [US2] Add integration tests to the same
      `jircd-integration-tests/src/test/java/net/jircd/integration/WallopsCommandTest.java`
      covering: self `MODE <self> +w` is accepted and reflected in a subsequent
      `MODE <self>` query; self `MODE <self> -w` is accepted and reflected the same way;
      and `MODE <other-nickname> +w` is rejected with `502 ERR_USERSDONTMATCH` and leaves
      the other user's preference unchanged (quickstart.md Scenario 4). No new production
      code is needed for this story — `UserMode.WALLOPS` (T002) already plugs into the
      existing generic self-only `MODE` query/set path
      (`UserModeCommandHandler`) unchanged (research.md, decision 1); this phase is
      verification of that claim, not new implementation.

**Checkpoint**: User Story 2 is independently verified — T006 passes against T002 alone,
with no dependency on T004/T005.

---

## Phase 5: User Story 3 - Non-administrator is prevented from broadcasting notices (Priority: P3)

**Goal**: A connected user without administrator privilege cannot deliver a `WALLOPS`
notice to anyone, and is told why.

**Independent Test**: From a non-`OPER`'d connection, send `WALLOPS`, confirm
`481 ERR_NOPRIVILEGES` and zero delivery to any connected session, including ones with
`+w` set — per quickstart.md Scenarios 5 and 7.

### Tests for User Story 3

- [ ] T007 [US3] Add integration tests to the same
      `jircd-integration-tests/src/test/java/net/jircd/integration/WallopsCommandTest.java`
      covering: a non-administrator's `WALLOPS` attempt yields `481 ERR_NOPRIVILEGES` and
      no connected session (including one with `+w` set) receives anything (quickstart.md
      Scenario 5); and a session that had administrator privilege revoked mid-session
      (`MODE <self> -o`, per `Story6OperUserModeTest.java`'s existing pattern) is rejected
      identically on a subsequent `WALLOPS` attempt (Scenario 7). No new production code is
      needed for this story — both cases exercise the `AdminPrivilege.isAuthorized` gate
      T004 already implemented as part of User Story 1; this phase is dedicated
      verification of that gate's exact contract (numeric code, zero delivery), not new
      implementation.

**Checkpoint**: All three user stories are independently functional and verified.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final checks spanning all three stories.

- [ ] T008 [P] Re-read `specs/010-wallops-notices/contracts/wallops-command.md` against the
      finished `WallopsCommandHandler.java` and `UserMode.java` changes and correct any
      wording that has drifted from the actual implementation (no source-code change
      expected — this is a documentation-accuracy pass).
- [ ] T009 Walk through every scenario in `specs/010-wallops-notices/quickstart.md`
      end-to-end (manually or by confirming the corresponding T003/T006/T007 test methods
      cover it) and check off any scenario not already exercised by an automated test.
- [ ] T010 [P] Run `./gradlew check` to execute the full test suite plus Spotless/SpotBugs/PMD
      across `jircd-core`, `jircd-server-extensions:admin`, and `jircd-integration-tests`,
      satisfying the Constitution's Quality Gates (automated tests green, static analysis
      clean) before this feature is considered mergeable.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately.
- **Foundational (Phase 2)**: Depends on Setup. BLOCKS all user stories (T004 references
  `UserMode.WALLOPS`; T006's `MODE +w` test has nothing to set without it).
- **User Story 1 (Phase 3)**: Depends on Foundational only.
- **User Story 2 (Phase 4)**: Depends on Foundational only — does NOT depend on User
  Story 1 (its test needs no `WALLOPS` send, only `MODE`).
- **User Story 3 (Phase 5)**: Depends on Foundational AND on User Story 1's T004 (the
  authorization gate it verifies is written there) — it adds no code of its own, only
  tests against T004's existing gate.
- **Polish (Phase 6)**: Depends on all three user stories being complete.

### Within Each Story

- T003 (US1 tests) before T004/T005 (US1 implementation) — write tests first per this
  project's Testing Standards; T005 depends on T004 (registration needs the class to exist).
- T006 (US2) and T007 (US3) each depend only on Foundational + (for T007) T004; they may
  be done in either order relative to each other, and T006 may even be done in parallel
  with T004/T005 by a second contributor, since it touches only `MODE`, never `WALLOPS`.

### Parallel Opportunities

- T008 and T010 in Polish are independent of each other and can run in parallel.
- T006 (User Story 2) has no dependency on T004/T005 (User Story 1) and can be worked on
  in parallel with them once T002 (Foundational) is done, by a different contributor —
  though both eventually append to the same `WallopsCommandTest.java` file, so the actual
  edits should be sequenced or merged carefully even if the underlying work is independent.

---

## Parallel Example: After Foundational (T002)

```bash
# Two independent tracks can start immediately once T002 is done:
Track A (User Story 1): T003 -> T004 -> T005
Track B (User Story 2): T006  # no dependency on Track A
# User Story 3 (T007) must wait for Track A's T004 before it has a gate to verify.
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001).
2. Complete Phase 2: Foundational (T002) — CRITICAL, blocks everything else.
3. Complete Phase 3: User Story 1 (T003-T005).
4. **STOP and VALIDATE**: run quickstart.md Scenarios 1-3 and 6 against the running server.
5. This alone delivers the core value: administrators can broadcast to whoever has opted
   in, even before User Story 2 gives users an ergonomic way to discover the toggle (they
   can still self-set `+w` today via the generic `MODE` command Foundational already
   activates) and before User Story 3 adds dedicated rejection-path test coverage (the
   rejection behavior itself is already present in T004).

### Incremental Delivery

1. Setup + Foundational → foundation ready (T001-T002).
2. Add User Story 1 → validate independently → this is the MVP (T003-T005).
3. Add User Story 2 → validate independently (T006) — confirms the self-opt-in path with
   dedicated coverage.
4. Add User Story 3 → validate independently (T007) — confirms the security boundary with
   dedicated coverage.
5. Polish (T008-T010) once all three are in place.
