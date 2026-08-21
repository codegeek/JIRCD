---

description: "Task list for the Connection Monitoring Log feature"
---

# Tasks: Connection Monitoring Log

**Input**: Design documents from `/specs/009-connection-monitoring-log/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, quickstart.md (all present)

**Tests**: Included. The project constitution's Testing Standards principle mandates
automated coverage for every feature's primary behavior and documented edge cases.

**Organization**: Tasks are grouped by user story (spec.md Stories 1-3, priority order
P1→P3). US1 delivers the token switch and the monitoring log itself — the shared
groundwork US2 (configurable PING frequency) and US3 (token opacity) each build on top of,
per research.md's "no single blocking prerequisite spans all three stories, but US2/US3
each have a real dependency on US1's own token-generation change landing first" — see
Dependencies below.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1-US3)
- File paths are relative to the repository root.

## Path Conventions

No new module — every path is an existing file from `001-ircv3-server`'s own module layout
(`jircd-core/session`, `jircd-core/config`, `jircd-server`), plus `jircd-integration-tests`
(protocol-level tests).

---

## Phase 1: Setup

Not applicable — no new module, no new build dependency (`java.util.UUID` is a JDK
built-in), no new tooling.

## Phase 2: Foundational

Not applicable — no single blocking prerequisite spans all three stories; US1's token/log
work and US2's configuration work touch mostly-disjoint files and only meet at
`ConnectionHandler.java`, tracked explicitly in Dependencies below rather than as a
separate phase.

---

## Phase 3: User Story 1 - Administrator reviews connection activity in a monitoring log (Priority: P1) 🎯 MVP

**Goal**: Every accepted connection gets an opaque, unique token; a connect-event and a
disconnect-event (with duration) are logged for it through a new facility distinct from
`SecurityEventLog`.

**Independent Test**: Connect a client, exchange some traffic, and disconnect. Confirm a
connect-event log entry and a disconnect-event log entry both appear, both referencing the
same connection token.

- [ ] T001 [US1] Add a `private final Instant connectedAt = Instant.now();` field (with a
  `connectedAt()` accessor) to
  `jircd-core/src/main/java/net/jircd/core/session/ClientSession.java` — a field
  initializer, not a constructor parameter, mirroring `Channel.createdAt`'s identical
  precedent (data-model.md)
- [ ] T002 [P] [US1] Create
  `jircd-core/src/main/java/net/jircd/core/session/ConnectionMonitorLog.java` — a
  static-only utility sibling to `SecurityEventLog.java` (private constructor, one `slf4j`
  `Logger`): `connected(String connectionId, String remoteAddress)` logs
  `connection-event=connected connection={} remoteAddress={}`; `disconnected(String
  connectionId, Duration duration, String reason)` logs `connection-event=disconnected
  connection={} durationMs={} reason={}` (data-model.md)
- [ ] T003 [US1] In `jircd-core/src/main/java/net/jircd/core/session/ConnectionHandler.java`:
  remove `connectionIdCounter` (the `AtomicLong` field) and its `"c" +
  counter.incrementAndGet()` usage; generate `UUID.randomUUID().toString()` once inside
  `accept()`, before spawning the per-connection virtual thread, and use it both for the
  thread's name (`"connection-" + token`, incidentally fixing the existing pre-increment
  off-by-one in the current thread-name value — research.md "Constraints") and pass it into
  `handleConnection`; call `ConnectionMonitorLog.connected(connectionId, remoteAddress)`
  right after `ClientSession` is constructed (depends on T001, T002)
- [ ] T004 [US1] In
  `jircd-core/src/main/java/net/jircd/core/session/DisconnectCleanup.java`, inside the
  existing idempotency guard (`if (!session.lifecycle().closeIfNotAlreadyClosing())
  return;`), compute `Duration.between(session.connectedAt(), Instant.now())` and call
  `ConnectionMonitorLog.disconnected(session.connectionId(), duration, reason)`, reusing the
  `reason` parameter the method already receives (depends on T001, T002)
- [ ] T005 [US1] New integration test file,
  `jircd-integration-tests/src/test/java/net/jircd/integration/ConnectionMonitorLogTest.java`:
  add a small reusable log-capture helper (a Logback `ListAppender` attached to
  `net.jircd.core.session.ConnectionMonitorLog`'s logger — `TestServer` runs the server
  in-process, so this captures real output) and use it to prove connecting then
  disconnecting a client produces both a `connection-event=connected` and a
  `connection-event=disconnected` line sharing the identical token, and that the token is
  not of the old `c1`/`c2` form (design it as a reusable static helper other test files in
  this feature can call, not a one-off — see T012/T013) (depends on T003, T004)

**Checkpoint**: The monitoring log is fully functional and independently testable — tokens
are opaque, connect/disconnect are both recorded, and the facility is separate from
`SecurityEventLog`.

---

## Phase 4: User Story 2 - Administrator correlates a live keep-alive check with a logged connection (Priority: P2)

**Goal**: The server-sent `PING` already carries the same token as the monitoring log
(verify, don't reimplement); the idle interval that triggers it becomes an
administrator-configurable setting, defaulting to 120 seconds.

**Independent Test**: Connect a client, capture the token from its monitoring log entry,
wait for (or trigger) a server-sent keep-alive check, and confirm the keep-alive message
carries the identical token; separately, confirm the check's frequency follows a configured
value instead of the old hardcoded 30-second constant.

- [ ] T006 [P] [US2] In
  `jircd-core/src/main/java/net/jircd/core/config/ServerConfiguration.java`, add `int
  keepAliveFrequencySeconds` to the record, plus `DEFAULT_KEEP_ALIVE_FREQUENCY_SECONDS =
  120` and `KEEP_ALIVE_FREQUENCY_CEILING_SECONDS = 3600` constants (data-model.md) — no
  dependency on US1's tasks, different file
- [ ] T007 [US2] In
  `jircd-core/src/main/java/net/jircd/core/config/ConfigurationLoader.java`, add one more
  `positiveIntWithinCeiling(root, "keepAliveFrequencySeconds",
  ServerConfiguration.DEFAULT_KEEP_ALIVE_FREQUENCY_SECONDS,
  ServerConfiguration.KEEP_ALIVE_FREQUENCY_CEILING_SECONDS)` call site, the same pattern
  `operFailureThreshold`/`whowasHistorySize` already use (FR-011) (depends on T006)
- [ ] T008 [US2] In `ConnectionHandler.java`, remove the `KEEP_ALIVE_IDLE_INTERVAL` constant
  and its javadoc claiming keep-alive timing is "deliberately not exposed as an
  administrator-configurable setting" (now false); add a `Supplier<Integer>
  keepAliveFrequencySeconds` constructor parameter; in `handleConnection`, resolve it once
  per accepted connection (mirroring `rateLimit.get()`'s own existing precedent at the same
  call site) into `Duration.ofSeconds(...)` and pass that to `LivenessMonitor`'s existing
  constructor, unchanged otherwise (depends on T003 [same file/constructor as US1's own
  edit — land after it], T007)
- [ ] T009 [US2] In
  `jircd-server/src/main/java/net/jircd/server/JircdServerApplication.java`, add `() ->
  reloader.current().keepAliveFrequencySeconds()` as the new argument to `ConnectionHandler`'s
  constructor call, mirroring `rateLimit`'s own existing wiring immediately above it
  (depends on T008)
- [ ] T010 [P] [US2] In
  `jircd-core/src/test/java/net/jircd/core/config/ConfigurationLoaderTest.java`, add tests
  proving a valid `keepAliveFrequencySeconds` value parses cleanly and that zero, a
  negative value, and a value above the ceiling are each rejected with a
  `ConfigurationException` (depends on T007)
- [ ] T011 [US2] Update
  `jircd-integration-tests/src/test/java/net/jircd/integration/KeepAliveLoadTest.java` to
  configure a short `keepAliveFrequencySeconds` (e.g. `2`) via its own server configuration
  YAML instead of relying on the new 120-second default, and update its comments that
  currently reference the old hardcoded "30s idle interval" (research.md "Test impact of
  changing the default idle interval") (depends on T008, T009)
- [ ] T012 [US2] New integration test file,
  `jircd-integration-tests/src/test/java/net/jircd/integration/PingTokenConsistencyTest.java`:
  using T005's log-capture helper, connect a client, capture its monitoring-log token, wait
  for a server-sent `PING` (configuring a short `keepAliveFrequencySeconds` for the test),
  and assert the `PING` payload equals that exact token; add a second test proving a
  configured frequency other than the default actually changes how soon the `PING` arrives
  (depends on T005, T009)

**Checkpoint**: Live keep-alive correlation and configurable frequency are both fully
functional and independently testable.

---

## Phase 5: User Story 3 - Connection tokens reveal nothing about server activity (Priority: P3)

**Goal**: Prove the opacity property US1's token switch already delivers, rather than
implement anything new.

**Independent Test**: Connect several clients in sequence and compare their tokens; confirm
no arithmetic or lexical relationship reveals connection order or count.

- [ ] T013 [US3] New integration test file,
  `jircd-integration-tests/src/test/java/net/jircd/integration/ConnectionTokenOpacityTest.java`:
  connect three clients in sequence, capture their three tokens (via T005's log-capture
  helper), and assert each matches the standard UUID format (`^[0-9a-f]{8}-[0-9a-f]{4}-
  [0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`), that all three are distinct (SC-003), and that
  none equals a literal `c1`/`c2`/`c3`-style value — a UUID matching that format is
  structurally proof against encoding order/count by construction, which is the strongest
  testable form of FR-002/SC-004 (depends on T003)

**Checkpoint**: All three user stories are independently functional and demonstrable.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T014 [P] Update
  `specs/001-ircv3-server/contracts/server-configuration.md`'s Server Configuration schema
  documentation to add the new `keepAliveFrequencySeconds` key alongside the existing
  numeric settings (e.g. `operFailureThreshold`)
- [ ] T015 [P] Correct `specs/001-ircv3-server/spec.md`'s Assumptions section (the sentence
  stating keep-alive timing is "not exposed as an administrator-configurable Server
  Configuration setting in this release") and FR-063's cross-reference to that same claim,
  both now false (research.md "Prior-feature contract correction required")
- [ ] T016 [P] Code cleanup pass: confirm `./gradlew build` runs Spotless/SpotBugs/PMD
  clean across every touched module, and that the full existing test suite (not just this
  feature's own tests) passes with zero regressions
- [ ] T017 Run the full `specs/009-connection-monitoring-log/quickstart.md` validation pass
  manually against a running `./gradlew :jircd-server:run` instance (constitution UX
  Consistency principle's required manual usage-scenario check)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)** / **Foundational (Phase 2)**: Both empty.
- **User Stories (Phase 3-5)**: US1 (T001-T005) has no dependency on the other stories.
  US2's `ConnectionHandler.java` edit (T008) depends on US1's own edit to that same file
  (T003) having already landed — a genuine shared-file ordering dependency, not just a
  merge-conflict risk. US3 (T013) depends only on US1's token-generation change (T003).
- **Polish (Phase 6)**: T014/T015/T016 can start once Phase 3 (and, for T016, all of
  Phases 3-5) land; T017 depends on everything.

### Shared-file coordination (read before parallelizing)

- **`ConnectionHandler.java`** is edited by **US1** (`T003`) and **US2** (`T008`) — land
  strictly in that order: T003 removes the old counter and introduces the new
  token-generation call site; T008 adds the configurable-frequency constructor parameter
  to the same method. Not a conceptual dependency so much as a real one — T008's diff
  assumes T003's rewrite of `accept()`/`handleConnection` already exists.
- **`ConnectionMonitorLogTest.java`**'s log-capture helper (T005) is reused by T012 and
  T013 — land T005 before either.

### Within Each User Story

- Implementation before that story's own integration test additions.
- `T006` (US2) is `[P]` — different file, no dependency on US1's tasks — and can start
  immediately, in parallel with all of US1.

### Parallel Opportunities

- `T002` (US1) and `T006` (US2) can both start immediately, in parallel with each other and
  with `T001`.
- `T010` (US2, `ConfigurationLoaderTest.java`) has no dependency on `T008`/`T009`/`T011`/
  `T012` beyond `T007`, and can run in parallel with them.
- `T014`/`T015`/`T016` (Polish) are `[P]` — different files, no dependency on each other.

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 3 (US1) — the monitoring log alone already delivers the feature's core
   value (opaque tokens, connect/disconnect logging).
2. **STOP and VALIDATE**: confirm T005's test passes — matching tokens, correct log
   facility.
3. Layer US2 (configurable frequency + PING correlation proof), then US3 (opacity proof)
   incrementally — each is additive, not a rework of US1.

### Incremental Delivery

1. US1 → the monitoring log exists and works (MVP).
2. US2 → the same mechanism is now tunable, and its consistency with `PING` is proven, not
   just assumed.
3. US3 → the opacity property is proven, not just asserted by choice of UUID.
4. Polish → prior-spec corrections, full-build verification, and manual validation close
   out the feature.
