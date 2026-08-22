# Implementation Plan: Wallops Notices

**Branch**: `010-wallops-notices` | **Date**: 2026-08-22 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/010-wallops-notices/spec.md`

## Summary

Administrators need to broadcast time-sensitive operational text to whichever connected
users have opted in, without messaging each one individually. This adds the `WALLOPS`
command (administrator-only, already wire-recognized but unimplemented — `Command.java`)
and activates the already-reserved `w` (`wallops`) user mode as a self-settable, per-connection
opt-in flag. `WALLOPS` fans out to every currently connected session whose own `userModes()`
contains `wallops`, including the sender if they opted in themselves; everyone else, and
every non-administrator attempt, is unaffected.

## Technical Context

**Language/Version**: Java 25 (Gradle toolchain, `build.gradle.kts`)

**Primary Dependencies**: None new. Reuses `jircd-protocol` (`Command`, `Message`,
`NumericReply`) and `jircd-core` (`ClientSession`, `UserMode`, `NicknameRegistry`,
`ExtensionRegistry`) already depended on by `jircd-server-extensions/admin`.

**Storage**: N/A — the opt-in preference is in-memory, per-connection state
(`ClientSession.userModes()`), same as every other user mode; notices themselves are
transient and never persisted.

**Testing**: JUnit 5 (`org.junit.jupiter`, already the project-wide test framework),
end-to-end socket tests in `jircd-integration-tests` — the established pattern for
admin-extension commands (`KillCommandTest.java`, `Story6OperTest.java`); no dedicated
unit-test source set exists in `jircd-server-extensions/admin` today, and this feature
does not need to add one, since its only new logic is a thin command handler exercised
more realistically over a real connection.

**Target Platform**: JVM server process (existing `jircd-server` runtime) — no new
platform surface.

**Project Type**: Single multi-module Gradle project (existing structure; no new module).

**Performance Goals**: Matches SC-001 — delivery latency for a `WALLOPS` fan-out is the
same order as the existing per-recipient `NOTICE`/`PRIVMSG` write path (`writer().enqueueRaw(...)`),
since it reuses that exact primitive per recipient. No new budget beyond the existing
message-delivery path's.

**Constraints**: Administrator-privilege gating must use the exact same authorization
check every other admin command already uses (`AdminPrivilege.isAuthorized`), so that
`EXTENSION DISABLE admin`'s existing self-lockout behavior (contracts/irc-protocol-commands.md
"Self-lockout") applies to `WALLOPS` identically, with no special-casing.

**Scale/Scope**: Bounded by the server's existing connected-session count — the fan-out
iterates `NicknameRegistry.all()` once per `WALLOPS` send, the same collection `WHO`'s
bare/mask forms already iterate at comparable or greater frequency.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Code Quality**: New command handler has a single responsibility (authorize, validate
  text, fan out); no dead code or speculative abstraction introduced (research.md documents
  why a generic mode-registry or broadcast-helper abstraction is rejected as premature).
  PASS.
- **II. Testing Standards**: New behavior (a new command, a new enforced user mode) gets
  end-to-end coverage in `jircd-integration-tests` before being marked done, following this
  module's existing test convention. PASS (planned; enforced at `/speckit-tasks` +
  `/speckit-implement`).
- **III. User Experience Consistency**: Reuses existing numeric replies
  (`ERR_NOPRIVILEGES`, `ERR_NEEDMOREPARAMS`, `ERR_NOTEXTTOSEND`) and the existing
  self-only `MODE` semantics (`ERR_USERSDONTMATCH`) rather than inventing new
  client-facing error shapes. PASS.
- **IV. Performance Requirements**: Budget stated above; no hot-path change beyond one
  additional iteration over already-iterated-elsewhere connected-session data. PASS.

No violations to record in Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/010-wallops-notices/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
│   └── wallops-command.md
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
jircd-core/
└── src/main/java/net/jircd/core/session/
    └── UserMode.java                       # add WALLOPS('w', CORE, clientSettable=true)
                                             # to CORE_CATALOG — no other core change needed;
                                             # MODE query/set already iterates CORE_CATALOG
                                             # generically (UserModeCommandHandler)

jircd-server-extensions/admin/
└── src/main/java/net/jircd/serverextensions/admin/
    ├── WallopsCommandHandler.java          # NEW — authorize via AdminPrivilege, validate
                                             # text, fan out over NicknameRegistry.all()
                                             # filtered by UserMode.WALLOPS membership
    └── AdminExtension.java                 # register Command.WALLOPS -> WallopsCommandHandler

jircd-integration-tests/
└── src/test/java/net/jircd/integration/
    └── WallopsCommandTest.java             # NEW — end-to-end: admin+w-recipient delivery,
                                             # non-admin rejection, no-recipients no-op,
                                             # self-only +w/-w, empty-text rejection
```

**Structure Decision**: No new modules. This feature is entirely additive within the two
existing modules that already own this behavior's two halves — `jircd-core` owns the
generic user-mode catalog and query/set machinery (the mode already fits it unchanged),
and `jircd-server-extensions/admin` owns every other administrator-gated command and its
shared `AdminPrivilege` check. End-to-end coverage lands in `jircd-integration-tests`,
matching how every other admin-extension command (`KILL`, `OPER`) is already tested.

## Complexity Tracking

*No violations — table intentionally omitted.*
