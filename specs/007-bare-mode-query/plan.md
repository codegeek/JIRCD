# Implementation Plan: Bare Channel Mode Query

**Branch**: `007-bare-mode-query` | **Date**: 2026-08-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/007-bare-mode-query/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

A bare `MODE #channel` (no flag argument) currently falls into `ModeCommandHandler`'s
unrecognized-flag branch, replying `472 ERR_UNKNOWNMODE` — the last loose end from the
`+l`/`+k`/`+t` scope-exclusion cluster `006-complete-core-protocol` closed. This feature replaces
that branch with the real query: `324 RPL_CHANNELMODEIS` (every active `BOOLEAN`/`VALUE_SET_ONLY`/
`VALUE_ALWAYS`-kind flag as one mode string, with each value-carrying flag's current value
appended — `LIST`/`MEMBER`-kind state stays out, already covered by `MODE +b`/`NAMES`) followed by
`329 RPL_CHANNELCREATED` (the channel's creation time — a new field, `Channel` tracks none today).
No operator privilege is required (mirrors `TOPIC`'s own view-vs-set split); a private/secret
channel is hidden from a non-member the same way `TOPIC`/`NAMES` already hide theirs.

## Technical Context

**Language/Version**: Java 25 (LTS) — unchanged from `001`–`006`.

**Primary Dependencies**: None new — reuses `ChannelVisibility`, `ExtensionRegistry.
recognizedChannelModes`, `CoreChannelModes.ALL`, `Replies.send`, all already used elsewhere in
`ModeCommandHandler`/`NamesCommandHandler`/`TopicCommandHandler`.

**Storage**: N/A — `Channel` gains one new immutable-at-construction field (`createdAt`); no
schema or serialization format exists to version.

**Testing**: JUnit 5 (Jupiter) + AssertJ integration test, identical approach to every prior
feature; re-runs `chmodes/modeis.py::testChannelModeIs` against `github.com/jircd/irctest`'s
`irctest.controllers.jircd` controller (SC-003), the same verification precedent `003`/`005`/`006`
established.

**Target Platform**: Linux server (unchanged).

**Project Type**: Single backend network service — same multi-module Gradle build; no new
subproject.

**Performance Goals**: No new Success Criteria beyond spec.md's SC-001 through SC-003 (functional
correctness) — the query runs once per `MODE #channel` invocation, an already-existing,
low-frequency command path; building the mode string iterates the same small, fixed
`CoreChannelModes.ALL` collection `SupportedFeatures.formatChanModes()` already iterates once per
registration, not a new class of cost.

**Constraints**: `ModeCommandHandler`'s bare-query branch runs before its whole-command
operator-privilege check today (the same structural position `MODE +b`'s bare ban-list query
already occupies) — FR-005 (no operator privilege required) falls out of that existing position
for free, no restructuring needed. The existing channel-lookup 403 check (`found.isEmpty()`) does
not currently consult `ChannelVisibility.isHiddenFrom` for *any* `MODE` path — FR-006 adds that
check narrowly inside the new bare-query branch only, per spec.md's own explicit scope boundary
("`MODE #channel <flag>` ... is entirely unaffected") — not a general visibility check added to
every `MODE` use.

**Scale/Scope**: 8 FRs across 3 user stories, 1 entity gains 1 new field (`Channel.createdAt`), 1
new numeric (`RPL_CHANNELCREATED`, 329 — 326-330 were all unreserved, confirmed via source read),
3 touched production files (`NumericReply`, `Channel`, `ModeCommandHandler`), 0 new handler
classes, 0 new configuration keys.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Status |
|---|---|---|
| I. Code Quality | The new query reuses four already-established mechanisms verbatim (`ChannelVisibility.isHiddenFrom`, `ExtensionRegistry.recognizedChannelModes`, `CoreChannelModes.ALL`'s iteration order, `Replies.send`) — no new abstraction introduced; the `VALUE_SET_ONLY`/`VALUE_ALWAYS` special-casing mirrors the identical special-casing `ModeCommandHandler.applyChanges` already does for `+l`/`+k` (006), not a new pattern. | PASS |
| II. Testing Standards | A new regression test proves the specific behavior changed, plus SC-003's re-run of the exact irctest test that surfaced this finding — the same verification precedent `003`/`005`/`006` established. | PASS |
| III. User Experience Consistency | The reply reuses two numerics already reserved in `NumericReply` (`324`, and `329` fills a previously-unreserved slot with no conflict) — no new wire-format convention; the privilege/visibility model is FR-005/FR-006's direct reuse of `TOPIC`'s own existing view-vs-set split, not invented from scratch. | PASS |
| IV. Performance Requirements | No new user-facing operation — `MODE #channel` already exists; this only changes what its flag-less form returns. Iterates a small, fixed mode collection already iterated once per registration elsewhere (`SupportedFeatures`), well within existing cost bounds for this low-frequency command. | PASS |

No violations requiring justification. Complexity Tracking table below is intentionally empty.

**Post-Design Re-check** (after Phase 1 — data-model.md, quickstart.md): No new violations. The
`Channel.createdAt` field is a strict, additive extension of the same "new per-channel state
field" pattern `006` already established for `memberLimit`/`key` — not a new architectural
concept. Gate remains PASS.

## Project Structure

### Documentation (this feature)

```text
specs/007-bare-mode-query/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md         # Phase 1 output (/speckit-plan command) — Channel.createdAt field
├── quickstart.md         # Phase 1 output (/speckit-plan command)
├── checklists/
│   └── requirements.md
└── tasks.md              # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

No `contracts/` directory of its own — this feature corrects existing contract text in
`001-ircv3-server`'s own contract file directly (the `MODE` (channel) Full Command Catalog row,
and the bare-mode-query note already documenting this as excluded), the same precedent
`003`/`004`/`005`/`006` all already established — scheduled as an implementation task, not during
planning.

### Source Code (repository root)

No new subproject, no new handler class — every file below is an existing file `006` already
touched (or, for `NumericReply`, that `001` already established).

```text
jircd-protocol/
└── src/main/java/net/jircd/protocol/
    └── NumericReply.java              # Add RPL_CHANNELCREATED(329), between the existing
                                        # RPL_UNIQOPIS(325) and RPL_NOTOPIC(331)

jircd-core/
└── src/main/java/net/jircd/core/session/
    ├── Channel.java                   # Add a final, construction-time `createdAt` field
    │                                  # (Instant.now() field initializer — resets naturally on
    │                                  # recreation, the same way memberLimit/key/topic already do,
    │                                  # no explicit reset logic needed)
    └── command/
        └── ModeCommandHandler.java    # Replace the bare-query 472 branch with the real
                                        # 324/329 reply; adds the ChannelVisibility check
                                        # narrowly inside that branch only

jircd-integration-tests/
└── src/test/java/net/jircd/integration/
    └── (one new test file — see tasks.md)

specs/001-ircv3-server/contracts/irc-protocol-commands.md
    # update the `MODE` (channel) Full Command Catalog row and the existing bare-mode-query note
```

**Structure Decision**: No new module. This feature is purely additive within the existing
`jircd-core`/`jircd-protocol` layer already touched by `006` — no server-extension or
composition-root wiring change (the touched constructors/dependencies are unchanged; `Channel`'s
new field needs no new constructor parameter, since it's a computed-at-construction default, not
an injected dependency). Its documentation footprint adds a small `data-model.md` (one new field,
following `006`'s own precedent of documenting real entity changes), alongside the usual
`plan.md`/`research.md`/`quickstart.md` triad, plus a targeted correction to `001`'s own contract
file scheduled as an implementation task.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No violations — table intentionally empty (see Constitution Check above, both gates PASS).
