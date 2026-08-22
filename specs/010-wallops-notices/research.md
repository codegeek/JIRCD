# Phase 0 Research: Wallops Notices

No `[NEEDS CLARIFICATION]` markers remained in the spec, and the Technical Context above
has no unresolved unknowns. This document instead records the design decisions the
Technical Context and Project Structure sections rest on, since each has a real
alternative that was considered and rejected.

## Decision: `wallops` becomes a `UserMode.CORE_CATALOG` entry, not an extension-contributed mode

**Decision**: Add `UserMode.WALLOPS = new UserMode("wallops", 'w', CORE, true)` directly to
`jircd-core`'s `UserMode.CORE_CATALOG`.

**Rationale**: `UserModeCommandHandler` (self-only `MODE` query/set) already iterates
`UserMode.CORE_CATALOG` generically via `findByFlag`/`formatModes` — a `CORE_CATALOG`
entry with `clientSettable=true` gets working `+w`/`-w` self-set and `MODE <self>` query
support with zero changes to that handler, identical to how `invisible` already works.
The protocol contract (`specs/001-ircv3-server/contracts/irc-protocol-commands.md`,
User Mode Catalog table) already reserves `w`/`wallops` as a catalog entry alongside
`operator`/`invisible`, not as something extension-owned — this feature simply flips its
status from Reserved to Implemented, matching precedent (`002-extended-irc-commands`
moved several `Command` rows the same way without touching `001`'s file directly; see
`contracts/wallops-command.md`).

**Alternatives considered**:
- *Generic extension-contributed user-mode registry* (each `ServerExtension` can register
  additional `UserMode`s at startup) — rejected as premature generality (Constitution I):
  no second consumer of such a registry exists today, and `UserMode.java`'s own comment
  already flags this as a "later" concern, not a "now" one. Building it here to serve a
  single new flag would be exactly the kind of speculative abstraction the constitution
  prohibits.
- *Admin-extension-local preference tracking* (a `Set<ClientSession>` owned by the admin
  extension instead of a `UserMode`) — rejected: it would bypass the existing, already-
  wired `MODE` query/set path entirely, forcing a parallel, bespoke command just to
  read/write the preference, duplicating machinery `UserModeCommandHandler` already
  provides for free.

## Decision: `WallopsCommandHandler` lives in `jircd-server-extensions/admin`

**Decision**: Implement and register the `WALLOPS` command handler in the `admin`
extension, alongside `OperCommandHandler`, `KillCommandHandler`, etc.

**Rationale**: `WALLOPS` needs the exact same authorization gate every other privileged
admin command already uses — `AdminPrivilege.isAuthorized(session, extensionRegistry)` —
which in turn is what makes `EXTENSION DISABLE admin`'s self-lockout behavior apply
uniformly. That check, and every command that calls it, already lives in this one module
by established convention (`AdminPrivilege.java`'s own Javadoc: "the shared authorization
check every admin command but `OPER` itself uses"). Placing `WALLOPS` anywhere else would
either duplicate that check outside its established home or require exporting it, neither
of which the feature needs.

**Alternatives considered**:
- *`jircd-core`* — rejected: would relocate/duplicate the `AdminPrivilege` gate away from
  every sibling admin command, breaking the "one place for admin gating" convention for
  no functional benefit.

## Decision: fan-out reuses the direct per-session `writer().enqueueRaw(...)` primitive

**Decision**: `WallopsCommandHandler` iterates `NicknameRegistry.all()`, filters by
`session.userModes().contains(UserMode.WALLOPS)`, and calls each matching session's own
`writer().enqueueRaw(...)` directly — no new broadcast/fan-out helper class.

**Rationale**: No general-purpose "broadcast to N sessions" helper exists yet.
`AdminNotices.send(...)` is single-target and always uses the server name as prefix, so it
doesn't fit (`WALLOPS` needs the *sending administrator* as the message prefix, per RFC
2812 §4.7's `:sender WALLOPS :text` wire shape, to satisfy FR-010). `009-connection-
monitoring-log`'s `ConnectionMonitorLog` is a structured log sink, not a client-facing
send path, so it doesn't apply either. `NicknameRegistry.all()` is already the established
primitive for "every connected session" (`WHO`'s bare/mask forms use it the same way).
Introducing a new abstraction for this feature's single call site would be premature
generality (Constitution I); the direct loop is small, single-purpose, and easy to read
in place.

**Alternatives considered**:
- *New `Broadcaster`/`Fanout` helper class* — rejected: one call site today; no evidence a
  second is imminent. Constitution I disallows designing for hypothetical future reuse.

## Decision: reuse existing numeric replies; no new numeric or confirmation reply

**Decision**: Non-administrator attempts get `481 ERR_NOPRIVILEGES` (identical wording to
`KILL`/`SAMODE`/etc.); missing message text gets `461 ERR_NEEDMOREPARAMS`; empty/whitespace
text gets `412 ERR_NOTEXTTOSEND` (identical to `PRIVMSG`/`NOTICE` in
`MessageCommandHandler`). A successful send produces **no** separate confirmation reply to
the sending administrator — they see their own notice only if their own `wallops`
preference is enabled, exactly like any other recipient (FR-008), which is sufficient
positive feedback and keeps `WALLOPS`'s reply shape consistent with real-world IRC daemon
behavior.

**Rationale**: Reusing established numerics keeps client-facing error semantics
consistent across every admin command (Constitution III); none of the three cases need a
meaning `481`/`461`/`412` don't already carry exactly.

**Alternatives considered**:
- *New dedicated confirmation notice to the sender* (like `KILL`'s `AdminNotices.send`
  confirmation) — rejected: not required by any FR/SC in spec.md, and would create an
  inconsistency where administrators get delivery confirmation but never see the actual
  notice text unless also opted in, which is a more confusing behavior than simply
  treating them as an ordinary opted-in recipient.
