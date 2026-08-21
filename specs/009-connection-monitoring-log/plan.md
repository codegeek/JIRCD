# Implementation Plan: Connection Monitoring Log

**Branch**: `009-connection-monitoring-log` | **Date**: 2026-08-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/009-connection-monitoring-log/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Connection identifiers are currently sequential (`ConnectionHandler`'s `AtomicLong` + `"c" +
n`), leaking connection order and count. This feature switches them to opaque
`UUID.randomUUID()`-based tokens, adds a new `ConnectionMonitorLog` facility (sibling to the
existing `SecurityEventLog`) that records a connect entry and a disconnect entry — with
duration — for every connection, and makes the server-initiated keep-alive's idle interval
an administrator-configurable `ServerConfiguration` setting (`keepAliveFrequencySeconds`,
default 120 seconds, previously a hardcoded, deliberately-non-configurable 30-second
constant). The consistency requirement (the same token appears in the monitoring log and in
every server-sent `PING`) requires no new plumbing — `LivenessMonitor.sendPing` already
sends `session.connectionId()` as `PING`'s payload, so it stays consistent automatically
once the token's generation changes at its one source.

## Technical Context

**Language/Version**: Java 25 (LTS) — unchanged from `001`–`008`.

**Primary Dependencies**: None new — `java.util.UUID` (JDK built-in) for token generation;
reuses the project's existing `slf4j`/`logback` logging stack for the new
`ConnectionMonitorLog` facility, matching `SecurityEventLog`'s own precedent exactly.

**Storage**: N/A — `ClientSession` gains one new immutable-at-construction field
(`connectedAt`); `ServerConfiguration` gains one new scalar field
(`keepAliveFrequencySeconds`). No schema or serialization format exists to version for
either.

**Testing**: JUnit 5 (Jupiter) + AssertJ, identical approach to every prior feature. A new
`ConnectionMonitorLogTest` (unit-level, via a Logback `ListAppender` attached to that
logger) verifies the two log methods' exact output — the only test in the project needing
this technique, but it is a standard, lightweight one requiring no new dependency (`logback-
classic` is already on the classpath). Integration tests verify token opacity/uniqueness
across several connections, PING-token consistency, and the new configuration setting's
default/override/rejection behavior. `KeepAliveLoadTest` is updated to configure its own
short `keepAliveFrequencySeconds` rather than relying on the new 120-second default
(research.md "Test impact of changing the default idle interval").

**Target Platform**: Linux server (unchanged).

**Project Type**: Single backend network service — same multi-module Gradle build; no new
subproject. Touches `jircd-core` only (`ConnectionHandler`, `ClientSession`,
`DisconnectCleanup`, `ServerConfiguration`, `ConfigurationLoader`, one new
`ConnectionMonitorLog` class) and `jircd-server`'s composition root
(`JircdServerApplication`, to wire the new configured supplier through — the same pattern
`rateLimit` already uses there).

**Performance Goals**: No new user-facing operation — connection accept and disconnect
already exist; this only changes what identifies them and adds one log line to each path.
`UUID.randomUUID()` is a fast, allocation-light JDK call, well within the cost the previous
`AtomicLong.incrementAndGet()` already had for this low-frequency (per-connection, not
per-message) path.

**Constraints**: `ConnectionHandler.accept` currently names its per-connection virtual
thread using the *pre-increment* counter value (`"connection-" +
connectionIdCounter.get()`, `ConnectionHandler.java:118`) — an existing, harmless
off-by-one quirk in a debug-only thread name. Since the counter disappears with this
feature, the token must be generated in `accept()` (before the thread is spawned) rather
than inside `handleConnection`, so it can both name the thread accurately and be handed
into `handleConnection` — incidentally fixing that quirk as a side effect of the refactor,
not a goal in itself. `LivenessMonitor` itself needs zero changes — confirmed via source
read that it already carries `session.connectionId()` as `PING`'s payload and does not
validate `PONG`'s returned content against it.

**Scale/Scope**: 11 FRs across 3 user stories, 1 entity gains 1 new field
(`ClientSession.connectedAt`), 1 config record gains 1 new field
(`ServerConfiguration.keepAliveFrequencySeconds`), 1 new class (`ConnectionMonitorLog`), 6
touched production files (`ConnectionHandler`, `ClientSession`, `DisconnectCleanup`,
`ServerConfiguration`, `ConfigurationLoader`, `JircdServerApplication`), 0 new handler
classes, 1 new configuration key, 1 prior-feature contract correction
(`001-ircv3-server/spec.md`'s now-false "keep-alive timing is not administrator-configurable"
assumption and its FR-063 cross-reference).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Status |
|---|---|---|
| I. Code Quality | `ConnectionMonitorLog` reuses `SecurityEventLog`'s exact established shape rather than inventing a new logging pattern; the keep-alive frequency setting reuses `rateLimit`'s exact existing supplier-injection and per-connection-resolution pattern rather than inventing a new configuration-wiring approach. Both are directly-reused precedent, not new abstractions. | PASS |
| II. Testing Standards | New behavior (token opacity, monitoring log entries, PING consistency, configurable frequency with validation) gets dedicated new test coverage; the one at-risk existing test (`KeepAliveLoadTest`, timing-dependent on the changing default) is identified and updated rather than left to silently slow down or flake. | PASS |
| III. User Experience Consistency | The monitoring log's structured `key=value` line format matches `SecurityEventLog`'s existing convention exactly — no new logging style introduced. An invalid `keepAliveFrequencySeconds` is rejected at startup with a specific, actionable error, the same fail-fast posture every other `ServerConfiguration` value already has (FR-012/SC-008, `001-ircv3-server`). | PASS |
| IV. Performance Requirements | No new user-facing operation; connection accept/disconnect already exist. `UUID.randomUUID()` and one additional `LOG.info` call per connection lifecycle event are negligible relative to existing per-connection setup cost (socket I/O, `ClientSession`/`SessionWriter` construction). | PASS |

No violations requiring justification. Complexity Tracking table below is intentionally empty.

**Post-Design Re-check** (after Phase 1 — data-model.md, quickstart.md): No new violations.
`ClientSession.connectedAt` and `ServerConfiguration.keepAliveFrequencySeconds` are both
strict, additive extensions of already-established patterns (`Channel.createdAt` for the
former, `operFailureThreshold`/`whowasHistorySize` for the latter) — not new architectural
concepts. Gate remains PASS.

## Project Structure

### Documentation (this feature)

```text
specs/009-connection-monitoring-log/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md         # Phase 1 output (/speckit-plan command)
├── quickstart.md         # Phase 1 output (/speckit-plan command)
├── checklists/
│   └── requirements.md
└── tasks.md              # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

No `contracts/` directory of its own — this feature adds one new key to
`001-ircv3-server/contracts/server-configuration.md`'s existing schema documentation and
corrects that same spec's now-false keep-alive-is-not-configurable assumption, the same
precedent `003`–`008` established for extending/correcting a prior feature's own contract
text — scheduled as an implementation task, not during planning.

### Source Code (repository root)

No new subproject, no new handler class.

```text
jircd-core/
└── src/main/java/net/jircd/core/session/
    ├── ConnectionHandler.java     # Remove connectionIdCounter/KEEP_ALIVE_IDLE_INTERVAL;
    │                              # generate a UUID token in accept() (fixes the thread-name
    │                              # off-by-one as a side effect); resolve the configured
    │                              # keepAliveFrequencySeconds once per connection, same as
    │                              # rateLimit already is; call ConnectionMonitorLog.connected(...)
    ├── ClientSession.java         # Add `connectedAt` field (Instant.now() initializer,
    │                              # mirrors Channel.createdAt) + accessor
    ├── DisconnectCleanup.java     # Inside the existing idempotency guard: compute duration
    │                              # from connectedAt, call ConnectionMonitorLog.disconnected(...)
    └── ConnectionMonitorLog.java  # New — sibling to SecurityEventLog.java, same shape

jircd-core/
└── src/main/java/net/jircd/core/config/
    ├── ServerConfiguration.java  # Add keepAliveFrequencySeconds field +
    │                             # DEFAULT_KEEP_ALIVE_FREQUENCY_SECONDS (120) +
    │                             # KEEP_ALIVE_FREQUENCY_CEILING_SECONDS (3600)
    └── ConfigurationLoader.java  # One more positiveIntWithinCeiling call site

jircd-server/
└── src/main/java/net/jircd/server/
    └── JircdServerApplication.java  # Wire () -> reloader.current().keepAliveFrequencySeconds()
                                     # into ConnectionHandler's constructor, mirroring rateLimit

jircd-integration-tests/
└── src/test/java/net/jircd/integration/
    ├── KeepAliveLoadTest.java    # Configure a short keepAliveFrequencySeconds explicitly
    │                             # instead of relying on the new 120s default
    └── (new test file(s) — see tasks.md)

jircd-core/
└── src/test/java/net/jircd/core/session/
    └── ConnectionMonitorLogTest.java  # New — ListAppender-based, verifies exact log output

specs/001-ircv3-server/contracts/server-configuration.md
    # document the new keepAliveFrequencySeconds key

specs/001-ircv3-server/spec.md
    # correct the Assumptions section's now-false "keep-alive timing ... not
    # administrator-configurable" statement and FR-063's cross-reference to it
```

**Structure Decision**: No new module. Purely within `jircd-core`'s existing
`session`/`config` packages (already responsible for connection handling and Server
Configuration respectively) plus the composition root's existing dependency-wiring point —
no server-extension registration change, no new constructor dependency shape beyond one
more `Supplier<Integer>` parameter mirroring `rateLimit`'s own.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No violations — table intentionally empty (see Constitution Check above, both gates PASS).
