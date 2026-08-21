# Implementation Plan: Complete Core Protocol Exclusions

**Branch**: `006-complete-core-protocol` | **Date**: 2026-08-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/006-complete-core-protocol/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Seven previously-deferred RFC1459/RFC2812 completeness gaps, grouped into six user stories: two
new `VALUE`-kind channel modes (`+l` user-limit, `+k` channel-key) plus one new `BOOLEAN`-kind
mode (`+t` topic-lock) join the seven core modes `CoreChannelModes` already implements; a bare,
argument-less `NAMES` reuses the exact visibility rule and per-channel reply logic the
single-channel form already has; `LUSERS` gains two of the three previously-reserved numerics it
was missing; `INVITE` stops requiring the target channel to already exist; and `WHOWAS` gains its
optional count parameter. Every change slots into a mechanism the codebase's own comments already
anticipated: `ChannelMode.Kind.VALUE` exists in the enum today, unused by any of the seven current
core modes, and `SupportedFeatures.formatChanModes()` already reserves two empty `CHANMODES`
groups literally commented `"VALUE-kind requiring a parameter on both set and unset — none this
release"` (channel-key's shape) and `"...only when setting — none this release"` (user-limit's
shape) — this feature is what fills those two placeholders in. `WhowasHistory` already retains
more than one entry per nickname (a bounded global ring buffer, not a single-slot-per-nickname
store); FR-014 needs only a new query method, not a data-model change.

## Technical Context

**Language/Version**: Java 25 (LTS) — unchanged from `001`/`002`/`003`/`004`/`005`.

**Primary Dependencies**: None new — every change works within `jircd-core`'s existing
channel-mode, command, and session packages, using only classes already present
(`ChannelMode`, `CoreChannelModes`, `Channel`, `ChannelVisibility`, `GateAction`,
`WhowasHistory`, `SupportedFeatures`).

**Storage**: N/A — no new persisted state; `Channel` gains two new in-memory fields
(`memberLimit`, `key`), mirroring its existing `volatile String topic` field's shape; no schema
or serialization format exists to version.

**Testing**: JUnit 5 (Jupiter) + AssertJ unit/integration tests, identical approach to every
prior feature; this feature additionally re-runs the specific irctest test cases each item
corresponds to (SC-004), the same `github.com/jircd/irctest` controller `005` used.

**Target Platform**: Linux server (unchanged).

**Project Type**: Single backend network service — same multi-module Gradle build; no new
subproject.

**Performance Goals**: No new Success Criteria beyond spec.md's SC-001 through SC-005
(functional correctness) — `+l`/`+k`/`+t` checks run once per `JOIN`/`TOPIC` command, the same
per-message frequency `+i`/`+b` already run at; bare `NAMES` iterates the server's full channel
set once per invocation, the same bound `LIST` (already implemented, unbounded today) already
has — not a new class of cost.

**Constraints**: `ChannelMode.Kind.VALUE` — a single kind today — needs to become two kinds to
match `SupportedFeatures.formatChanModes()`'s already-reserved distinction between "parameter on
both set and unset" (`+k`) and "parameter only on set" (`+l`); `ModeCommandHandler.applyChanges`'s
per-flag dispatch needs one new branch per kind. `JoinCommandHandler`'s existing
`passesInviteOnlyGate` mutates state (removes the used invitation) as part of the check itself —
extending the same invite exemption to `+l`/`+k` (spec.md Clarifications) requires refactoring
that into a non-mutating peek used by all three gates, with a single consuming removal after every
applicable gate has passed, so a channel with more than one of `+i`/`+l`/`+k` active doesn't
consume the invitation on the first gate checked and then incorrectly fail the second.

**Scale/Scope**: 15 FRs across 6 user stories, 1 entity gains 2 new fields (`Channel`), ~9 touched
production files (`ChannelMode`, `CoreChannelModes`, `Channel`, `SupportedFeatures`,
`ModeCommandHandler`, `JoinCommandHandler`, `TopicCommandHandler`, `NamesCommandHandler`,
`LusersCommandHandler`, `InviteCommandHandler`, `WhowasHistory`, `WhowasCommandHandler` — 12
total), 0 new handler classes, 0 new configuration keys.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Status |
|---|---|---|
| I. Code Quality | Every change extends an existing, single-responsibility mechanism to a case it was already structured for (research.md documents the specific precedent per item: `+l`/`+k` fill `ChannelMode.Kind.VALUE`'s already-reserved `CHANMODES` groups; `+t` reuses the `BOOLEAN` shape `+n`/`+m`/`+p`/`+s` already use; bare `NAMES` reuses `ChannelVisibility`/`sendNamesReply`'s existing per-channel logic) — no new abstraction layer introduced anywhere in this batch. | PASS |
| II. Testing Standards | Every FR gets a regression test proving the specific behavior changed, plus SC-004's re-run of the exact irctest test each item corresponds to — the same verification precedent `003`/`005` established. | PASS |
| III. User Experience Consistency | Every new reply reuses an existing numeric and reply shape already defined in `NumericReply` (`471`/`475`/`252`/`255` all already exist, unused before this feature) — no new wire-format convention is invented; FR-013/FR-014's defaults are backed by RFC 2812's own error set and the already-bounded `WhowasHistory` behavior respectively (spec.md Assumptions), not invented from scratch. | PASS |
| IV. Performance Requirements | No change promotes a low/moderate-frequency command to a hot path; `+l`/`+k`/`+t` add one bounded field-read to `JOIN`/`TOPIC`, the same cost class `+i`/`+b` already have; bare `NAMES` is user-initiated and already has an unbounded-but-accepted precedent in `LIST`. | PASS |

No violations requiring justification. Complexity Tracking table below is intentionally empty.

**Post-Design Re-check** (after Phase 1 — data-model.md, quickstart.md): No new violations.
Splitting `ChannelMode.Kind.VALUE` into two kinds (data-model.md) is a strict refinement of an
already-unused enum value, not a new architectural concept — `SupportedFeatures.formatChanModes()`
already had two separate, empty slots waiting for exactly this split. Gate remains PASS.

## Project Structure

### Documentation (this feature)

```text
specs/006-complete-core-protocol/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md         # Phase 1 output (/speckit-plan command) — Channel field additions,
│                         # ChannelMode.Kind split
├── quickstart.md         # Phase 1 output (/speckit-plan command)
├── checklists/
│   └── requirements.md
└── tasks.md              # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

No `contracts/` directory of its own — this feature corrects existing contract text in
`001-ircv3-server`'s own contract file directly (the "Full Channel Mode Catalog"'s `t`/`l`/`k`
rows flip from `Reserved` to `Implemented`; the `NAMES`/`INVITE`/`MODE (channel)` Full Command
Catalog rows and the `LUSERS`/`WHOWAS` rows in `002-extended-irc-commands`'s contract get updated
notes), the same precedent `003`/`004`/`005` all already established for keeping prior features'
contract text accurate to current behavior — those edits land as `/speckit-implement` tasks, not
during planning, matching how `005`'s own contract corrections were sequenced.

### Source Code (repository root)

No new subproject — every file below is an existing file from `001-ircv3-server`'s own module
layout; no new handler classes this feature (unlike `005`'s `USERHOST`/`INFO`).

```text
jircd-core/
└── src/main/java/net/jircd/core/
    ├── session/
    │   ├── ChannelMode.java              # Story 1: split Kind.VALUE into two kinds
    │   ├── CoreChannelModes.java         # Story 1/2: new `l`/`k`/`t` ChannelMode constants
    │   ├── Channel.java                  # Story 1: memberLimit/key fields + accessors
    │   ├── WhowasHistory.java            # Story 6: new mostRecentNFor(nickname, count) method
    │   └── command/
    │       ├── ModeCommandHandler.java    # Story 1/2: new VALUE-kind branches in applyChanges
    │       ├── JoinCommandHandler.java    # Story 1: +l/+k JOIN gates; refactored, single-
    │       │                              # consumption invite-exemption check shared by +i/+l/+k
    │       ├── TopicCommandHandler.java   # Story 2: +t gates the operator-only check
    │       ├── NamesCommandHandler.java   # Story 3: bare-NAMES form
    │       ├── LusersCommandHandler.java  # Story 4: 252 RPL_LUSEROP, 255 RPL_LUSERME
    │       ├── InviteCommandHandler.java  # Story 5: not-yet-existing-channel case split out
    │       │                              # from not-a-member-of-existing-channel case
    │       └── WhowasCommandHandler.java  # Story 6: optional count parameter
    └── extension/
        └── SupportedFeatures.java        # Story 1: formatChanModes() fills its two previously-
                                           # empty VALUE-kind CHANMODES groups

jircd-integration-tests/
└── src/test/java/net/jircd/integration/
    └── (one new or extended test file per story — see tasks.md)
```

**Structure Decision**: No new module. This feature is purely additive within the existing
`jircd-core` channel-mode/command layer — no server-extension or composition-root wiring change
(unlike `005`'s `NicknameRegistry` addition to `ModeCommandHandler`, this feature's `MODE`
changes are self-contained within already-injected dependencies). Its documentation footprint
adds a `data-model.md` this time (`005` didn't need one — no entity changes; this feature adds
real per-channel state), alongside the usual `plan.md`/`research.md`/`quickstart.md` triad, plus
targeted corrections to `001`'s and `002`'s own contract files scheduled as implementation tasks.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No violations — table intentionally empty (see Constitution Check above, both gates PASS).
