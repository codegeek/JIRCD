# Implementation Plan: Extended IRC Commands

**Branch**: `002-extended-irc-commands` | **Date**: 2026-08-19 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-extended-irc-commands/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Add handlers for seven IRC commands `001-ircv3-server` deliberately left "Recognized only"
(parsed but rejected with `421 ERR_UNKNOWNCOMMAND`): server-information queries (`VERSION`
— now paired with a fresh `ISUPPORT` burst per this feature's Clarifications,
`TIME`, `LUSERS`), presence (`AWAY`, read by `PRIVMSG`/`NOTICE`/`WHOIS`/`WHO`), an
administrator-forced disconnect (`KILL`), a bounded last-known-identity lookup (`WHOWAS`),
and tag-only messages (`TAGMSG`). Technical approach: no new module, no new dependency, no
change to the existing concurrency/networking model — every command is a new
`CommandHandler` implementation registered in `JircdServerApplication` alongside the
existing ones (`registerStory1Handlers`/`registerModerationHandlers`), reusing existing
infrastructure wherever the requirement lets it (`SupportedFeatures` for `VERSION`'s
`ISUPPORT` burst, `DisconnectCleanup` for `KILL`, `MessageCommandHandler`'s target
resolution for `TAGMSG`) rather than introducing parallel logic. The one new piece of state
is a small in-memory bounded ring buffer (`WhowasHistory`) for `WHOWAS` — see research.md.

## Technical Context

**Language/Version**: Java 25 (LTS) — unchanged from `001-ircv3-server`.

**Primary Dependencies**: None new. Every command reuses existing `jircd-core` machinery
(`SupportedFeatures`, `DisconnectCleanup`, `NicknameRegistry`, `ChannelRegistry`,
`MessageCommandHandler`'s target-resolution logic) or JDK standard library only
(`java.util.ArrayDeque` for `WhowasHistory`, research.md "WHOWAS bounded history store").

**Storage**: N/A — in-memory only, matching `001-ircv3-server`'s scope. `WhowasHistory` is a
bounded, capacity-limited in-memory structure (default 100 entries,
`ServerConfiguration.whowasHistorySize`), not persistent storage; a server restart clears it,
the same as every other piece of this server's live state.

**Testing**: JUnit 5 (Jupiter) + AssertJ for unit tests; protocol-level integration tests
over real TCP sockets against a running server instance, identical approach to
`001-ircv3-server` (`specs/001-ircv3-server/research.md` "Deterministic testing under
concurrency") — no new testing approach introduced.

**Target Platform**: Linux server (unchanged).

**Project Type**: Single backend network service — same multi-module Gradle build as
`001-ircv3-server`; no new subproject. New classes land in existing modules (`jircd-core`
for `AWAY`/`WHOWAS`/`TAGMSG`/`VERSION`/`TIME`/`LUSERS` handlers and `WhowasHistory`,
`jircd-server-extensions/admin` for `KILL`, `jircd-integration-tests` for protocol-level
tests) — see "Project Structure" below.

**Performance Goals**: No new Success Criteria beyond what spec.md's SC-001 through SC-005
already state (functional correctness, not throughput) — none of these seven commands is a
per-message hot path at the scale `001-ircv3-server`'s SC-002/SC-003 already budget for
(`WHOWAS`/`KILL`/server-info queries are rare, administrator- or user-initiated actions;
`AWAY` changes state once per user action, not per message; `TAGMSG` reuses `PRIVMSG`'s
already-budgeted fan-out path exactly).

**Constraints**: `TAGMSG` MUST reuse `PRIVMSG`/`NOTICE`'s existing targeting/gating logic,
not reimplement it (FR-022) — the one constraint from spec.md with direct implementation
impact, addressed by extracting a shared target-resolution step (research.md "TAGMSG
delivery reuse"). `KILL` MUST route through the existing `DisconnectCleanup`/socket-close
path, not a new one, to avoid reintroducing the cross-thread-close bug fixed in this
session's earlier work on `ConnectionHandler` (research.md "KILL disconnect path reuse").

**Scale/Scope**: 7 commands, 23 functional requirements (FR-001 through FR-023), 5 user
stories, 1 new entity (`WhowasHistory`/`WhowasEntry`), 1 new field on an existing entity
(`ClientSession.awayReason`), 1 new configuration key (`whowasHistorySize`).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Status |
|---|---|---|
| I. Code Quality | Every new command is its own single-responsibility `CommandHandler`, matching the existing one-handler-per-command convention exactly (no handler grows an "and" in its description); reused logic (`SupportedFeatures` rendering, `DisconnectCleanup`, `MessageCommandHandler` target resolution) is extracted into shared helpers rather than duplicated, per research.md's explicit rationale for each; static analysis (Spotless/SpotBugs/PMD) runs unchanged, gating merge the same as every other change to this codebase. | PASS |
| II. Testing Standards | Every FR gets unit and/or protocol-level integration coverage (quickstart.md's five story walkthroughs map directly to acceptance-scenario tests); no new timing-sensitive assertions are introduced (`WhowasHistory`'s eviction is deterministic given an insertion count, not wall-clock-based). | PASS |
| III. User Experience Consistency | New numeric replies (`351`/`391`/`251`/`254`/`305`/`306`/`301`/`314`/`369`/`406`) all come from RFC 2812's own defined set, already reserved (unused) in `001-ircv3-server`'s numeric catalog — no invented numerics; error messages follow the identical `481`/`401`/`421`-class conventions every existing command already uses; quickstart.md's five walkthroughs double as the required manual usage-scenario check. | PASS |
| IV. Performance Requirements | No new per-message hot path — `TAGMSG` reuses `PRIVMSG`'s already-budgeted fan-out, and every other new command is a low-frequency, user- or administrator-initiated action with no throughput target of its own (Technical Context "Performance Goals" above); `WhowasHistory`'s bounded-size, whole-structure-lock design is justified in research.md against the actual (low) write volume it will see. | PASS |

No violations requiring justification. Complexity Tracking table below is intentionally
empty.

**Post-Design Re-check** (after Phase 1 — data-model.md, contracts/, quickstart.md): No new
violations introduced. The one addition worth calling out explicitly: `WhowasHistory`
introduces genuinely new server-scoped state (`001-ircv3-server`'s data model has nothing
like it), but it's a bounded, in-memory-only structure following the same
"administrator-configurable numeric limit with a validated default" pattern every other
bounded resource on this server already uses (channel ban lists, mode-per-command limits) —
not a new category of complexity, just one more instance of an established one. Gate
remains PASS.

## Project Structure

### Documentation (this feature)

```text
specs/002-extended-irc-commands/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
│   ├── irc-protocol-commands-extended.md
│   └── server-configuration-extensions.md
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

No new subproject — every file below lands in an existing module from
`001-ircv3-server`'s "Project Structure" (`jircd-protocol`, `jircd-core`,
`jircd-server-extensions/admin`, `jircd-server`, `jircd-integration-tests`).

```text
jircd-protocol/
└── src/main/java/net/jircd/protocol/
    └── Command.java                          # NO CHANGE — VERSION/TIME/LUSERS/AWAY/KILL/
                                               # WHOWAS/TAGMSG already exist as enum constants
                                               # (001's "wire-protocol recognition MUST
                                               # represent the full RFC set" already covers
                                               # them); only jircd-core's handler wiring is new

jircd-core/
├── src/main/java/net/jircd/core/session/
│   ├── ClientSession.java                    # add `awayReason` field (data-model.md)
│   ├── WhowasEntry.java                      # new — Value Object (data-model.md)
│   ├── WhowasHistory.java                    # new — bounded ring buffer (research.md)
│   └── command/
│       ├── VersionCommandHandler.java        # new
│       ├── TimeCommandHandler.java           # new
│       ├── LusersCommandHandler.java         # new
│       ├── AwayCommandHandler.java           # new
│       ├── WhowasCommandHandler.java         # new
│       ├── TagmsgCommandHandler.java         # new
│       ├── MessageCommandHandler.java        # extract shared target-resolution step
│       │                                     # (research.md "TAGMSG delivery reuse"),
│       │                                     # add away-notice send (FR-007)
│       ├── RegistrationCompletion.java       # extract shared ISUPPORT-rendering helper
│       │                                     # (research.md "VERSION + ISUPPORT reuse")
│       ├── WhoisCommandHandler.java          # add away-line output (FR-008)
│       └── WhoCommandHandler.java            # switch status letter H/G on awayReason (FR-009)
├── src/main/java/net/jircd/core/config/
│   └── ServerConfiguration.java              # add `whowasHistorySize` (contracts/
│                                             # server-configuration-extensions.md)
└── src/test/java/net/jircd/core/...          # unit tests for WhowasHistory eviction/lookup,
                                               # each new handler's own logic

jircd-server-extensions/admin/
└── src/main/java/net/jircd/serverextensions/admin/
    ├── KillCommandHandler.java               # new, alongside Oper/Rehash/Sajoin/Samode/
    │                                         # WhohostCommandHandler
    └── AdminExtension.java                   # register KILL

jircd-server/
└── src/main/java/net/jircd/server/
    └── JircdServerApplication.java           # register the six jircd-core handlers
                                               # (KILL registers via AdminExtension, matching
                                               # the existing admin-command pattern)

jircd-integration-tests/
└── src/test/java/net/jircd/integration/
    ├── ServerInfoQueriesTest.java             # new — VERSION/TIME/LUSERS (US1)
    ├── AwayStatusTest.java                    # new (US2)
    ├── KillCommandTest.java                   # new (US3)
    ├── WhowasCommandTest.java                 # new (US4)
    └── TagmsgCommandTest.java                 # new (US5)
    # named by command, not "StoryN", to avoid colliding with 001-ircv3-server's own
    # StoryN*Test.java naming (its "Story 1" through "Story 7" are different stories)
```

**Structure Decision**: No new module. This feature is purely additive within
`001-ircv3-server`'s existing four-module (plus application/test) layout — every new class
is a `CommandHandler` implementation placed in the same package its sibling handlers already
live in (`jircd-core/session/command` for core protocol commands,
`jircd-server-extensions/admin` for the one administration command), following the exact
registration pattern `JircdServerApplication.registerStory1Handlers`/
`registerModerationHandlers`/`AdminExtension` already establish (see this session's earlier
discussion of that pattern — explicit, compile-time-checked registration was kept rather
than moving to reflection-based discovery).

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No violations — table intentionally empty (see Constitution Check above, both gates PASS).
