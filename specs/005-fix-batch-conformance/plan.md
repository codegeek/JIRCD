# Implementation Plan: Fix Batch of Conformance Bugs

**Branch**: `005-fix-batch-conformance` | **Date**: 2026-08-20 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/005-fix-batch-conformance/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

19 individually root-caused conformance bugs, grouped into 6 user stories, each a small
correction to an already-implemented command's reply or notification behavior — two new
commands (`USERHOST`, `INFO`) complete already-declared-but-unhandled wire-protocol entries
rather than adding new ones. The most severe finding, `NICK` never broadcasting a change to
anyone (including the client itself), anchors Story 1. Every fix reuses an existing pattern
already established elsewhere in this codebase (research.md documents the specific precedent
for each) — no new reply convention or architectural mechanism is introduced.

## Technical Context

**Language/Version**: Java 25 (LTS) — unchanged from `001`/`002`/`003`/`004`.

**Primary Dependencies**: None new — every fix works within `jircd-core`'s existing command,
capability, and connection-handling packages, using only classes already present
(`NicknameRegistry`, `ChannelRegistry`, `ExtensionRegistry`, `OutboundMessage`,
`CapabilityNegotiator`).

**Storage**: N/A — no new persisted or session state; every fix either sends an
already-computable reply/notification or fixes an existing computation.

**Testing**: JUnit 5 (Jupiter) + AssertJ unit/integration tests, identical approach to every
prior feature; this feature additionally re-runs the exact irctest test cases that surfaced
each finding (SC-003), using the controller in `github.com/jircd/irctest`.

**Target Platform**: Linux server (unchanged).

**Project Type**: Single backend network service — same multi-module Gradle build; no new
subproject.

**Performance Goals**: No new Success Criteria beyond spec.md's SC-001 through SC-005
(functional correctness) — every touched command is already a per-message or per-connection
low-to-moderate-frequency path (registration, `NICK`, `JOIN`, `MODE`, `WHO`/`WHOIS`, `CAP`),
none newly promoted to a hot path by these fixes.

**Constraints**: `ModeCommandHandler` needs a new dependency, `NicknameRegistry`, to
distinguish "nickname doesn't exist anywhere" (FR-017) from "exists, not in this channel" —
the one constructor-signature change with composition-root (`JircdServerApplication`) impact;
every other fix is self-contained within its existing handler's current dependencies.

**Scale/Scope**: 23 FRs across 6 user stories, 0 new entities, ~13 touched production files
(`NickCommandHandler`, `MessageCommandHandler`, `OutboundMessage`, `CapabilityTagRenderer`,
`PingPongCommandHandler`, `CapCommandHandler`, `CapabilityNegotiator`, `ConnectionHandler`,
`AwayCommandHandler`, `ModeCommandHandler`, `JoinCommandHandler`, `KickCommandHandler`,
`WhoCommandHandler`, `WhoisCommandHandler`, `OperCommandHandler`), 2 new handler classes
(`UserhostCommandHandler`, `InfoCommandHandler`), 0 new configuration keys.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Status |
|---|---|---|
| I. Code Quality | Every fix is a small, targeted correction to a handler that already has a single clear responsibility; the two new handlers (`USERHOST`/`INFO`) follow the exact structural pattern every other small query handler in this codebase already uses — no new abstraction introduced anywhere in this batch. | PASS |
| II. Testing Standards | Every FR gets a regression test proving the specific behavior changed, plus SC-003's re-run of the exact irctest test that originally surfaced each finding — the same two-channel verification precedent `003-irctest-conformance-fixes` established. | PASS |
| III. User Experience Consistency | Every changed reply/notification reuses an existing pattern already established elsewhere in this codebase (research.md documents the specific precedent per fix: `NICK`'s fan-out mirrors `MessageCommandHandler`'s channel fan-out; `+o`/`+v`'s corrected numeric reuses the nickname-registry-existence check `WHOIS`/`KILL` already do) — no new convention is invented. | PASS |
| IV. Performance Requirements | No fix promotes a low/moderate-frequency command to a hot path; `NICK`'s new fan-out is bounded by the changing session's own channel-membership count, the same bound `PART`/`QUIT` broadcasts already have. | PASS |

No violations requiring justification. Complexity Tracking table below is intentionally empty.

**Post-Design Re-check** (after Phase 1 — quickstart.md, contract updates): No new violations.
`ModeCommandHandler`'s new `NicknameRegistry` dependency (Technical Context) is the one
constructor-signature change in this batch — it's a strict widening of an existing handler's
already-established dependency-injection pattern (every other handler needing registry access
already takes it the same way), not a new architectural layer. Gate remains PASS.

## Project Structure

### Documentation (this feature)

```text
specs/005-fix-batch-conformance/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

No `data-model.md` — spec.md's Key Entities section states this feature introduces no new
entities. No `contracts/` directory of its own — this feature corrects existing contract text
in `001-ircv3-server`/`002-extended-irc-commands`'s own contract files directly (see below),
the same precedent `003-irctest-conformance-fixes` and `004-fix-tls-certificate` both already
established for keeping prior features' contract text accurate to current behavior.

### Source Code (repository root)

No new subproject — every file below is an existing file from `001-ircv3-server`'s own module
layout, except the two brand-new handler classes (Story 5).

```text
jircd-core/
└── src/main/java/net/jircd/core/
    ├── session/command/
    │   ├── NickCommandHandler.java       # Story 1: broadcast NICK to self + channel co-members
    │   ├── MessageCommandHandler.java    # Story 2: echo-message for DMs; empty-body rejection;
    │   │                                 # pass client tags into OutboundMessage.now(...) (Story 3)
    │   ├── PingPongCommandHandler.java   # Story 3: PONG server-name param; bare-PING error
    │   ├── CapCommandHandler.java        # Story 3: real CAP LIST; 410 for invalid subcommand
    │   ├── AwayCommandHandler.java       # Story 5: empty-argument AWAY clears, doesn't set blank
    │   ├── ModeCommandHandler.java       # Story 4: +b list-query recognizes "+b"; +o/+v checks
    │   │                                 # NicknameRegistry existence before channel membership
    │   ├── JoinCommandHandler.java       # Story 4: comma-separated multi-channel JOIN; send
    │   │                                 # existing topic to a joiner
    │   ├── KickCommandHandler.java       # Story 4: default KICK comment to kicker's nickname
    │   ├── WhoCommandHandler.java        # Story 5: exact-nickname form bypasses invisibility
    │   ├── WhoisCommandHandler.java      # Story 5: send 318 on the not-found path too
    │   ├── UserhostCommandHandler.java   # Story 5: new — USERHOST
    │   └── InfoCommandHandler.java       # Story 5: new — INFO
    ├── capability/
    │   └── CapabilityNegotiator.java     # Story 3: NegotiationResult tracks List, not Set —
    │                                     # preserves order and duplicates for CAP NAK's echo
    ├── session/
    │   ├── OutboundMessage.java          # Story 3: no signature change — MessageCommandHandler
    │   │                                 # starts using the existing clientTags-accepting factory
    │   └── CapabilityTagRenderer.java    # Story 3: merge message.clientTags() into the
    │                                     # per-recipient rendered tag map
    └── session/
        └── ConnectionHandler.java        # Story 3: invalid UTF-8 pre-registration closes the
                                           # connection instead of leaving it open with no path
                                           # forward; independent tag-section/command-section
                                           # length checks

jircd-server-extensions/admin/
└── src/main/java/net/jircd/serverextensions/admin/
    └── OperCommandHandler.java           # Story 6: unsolicited MODE +o self-notification

jircd-server/
└── src/main/java/net/jircd/server/
    └── JircdServerApplication.java       # wire the two new handlers; pass NicknameRegistry to
                                           # ModeCommandHandler's constructor

jircd-integration-tests/
└── src/test/java/net/jircd/integration/
    └── (one new or extended test file per story — see tasks.md)

specs/001-ircv3-server/contracts/irc-protocol-commands.md
    # update NICK, PRIVMSG/NOTICE, PING/PONG, CAP, JOIN, KICK, MODE, WHO, WHOIS rows/notes;
    # add USERHOST, INFO rows (both currently listed "Recognized only")

specs/002-extended-irc-commands/contracts/irc-protocol-commands-extended.md
    # update AWAY's row/notes
```

**Structure Decision**: No new module. This feature is purely corrective within the existing
`jircd-core` command-handler/capability layer, plus one small addition in the `admin` server
extension (`OperCommandHandler`) and one composition-root wiring change. Its documentation
footprint mirrors `003`/`004`: a `plan.md`/`research.md`/`quickstart.md` triad for itself, plus
direct, targeted corrections to `001`'s and `002`'s own contract files — including two rows
moving from "Recognized only" to "Implemented" for `USERHOST`/`INFO`, the same kind of
completion-of-an-already-declared-command `003` never needed but this feature does.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No violations — table intentionally empty (see Constitution Check above, both gates PASS).
