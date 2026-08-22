# Phase 1 Data Model: Wallops Notices

This feature adds no persisted storage and no new field to any existing entity. It adds
one new catalog value to an existing entity, and one transient, non-stored message shape.

## `UserMode.WALLOPS` (extends existing `UserMode` catalog — `jircd-core`)

`UserMode` (`jircd-core/.../session/UserMode.java`) is an existing record:
`(id, flag, definedBy, clientSettable)`. This feature adds one constant to its
`CORE_CATALOG`:

| Field | Value |
|---|---|
| `id` | `"wallops"` |
| `flag` | `'w'` |
| `definedBy` | `UserMode.CORE` |
| `clientSettable` | `true` |

**Meaning**: whether the owning `ClientSession` currently wants to receive `WALLOPS`
notices. No new field is added to `ClientSession` — membership in its existing
`Set<UserMode> userModes()` (already `ConcurrentHashMap.newKeySet()`-backed) *is* the
preference, exactly the same representation `invisible` already uses.

**Lifecycle**: absent by default on a new connection (opt-out by default per
spec.md Assumptions); added by the session's own `MODE <self> +w` (self-only,
`clientSettable=true` — no privilege required, unlike `operator`); removed by
`MODE <self> -w`; removed implicitly when the session disconnects (the whole
`ClientSession`, and its `userModes()` set, is discarded — nothing to persist or clean up
separately). Never carried over to a future, separate connection.

**Validation rules**: none beyond what `UserModeCommandHandler` already enforces
generically for any `clientSettable` mode — self-only target (`ERR_USERSDONTMATCH`
otherwise), known flag (`ERR_UMODEUNKNOWNFLAG` otherwise). No `WALLOPS`-specific
validation is needed.

## `WALLOPS` notice (transient message — not an entity, not persisted)

Constructed fresh per send inside `WallopsCommandHandler`; exists only as an in-flight
`net.jircd.protocol.Message` handed to each recipient's `writer().enqueueRaw(...)`. Never
stored, queried, replayed, or referenced after delivery.

| Field | Source | Notes |
|---|---|---|
| prefix | sending session's own `nickname()` (+ user/host per this server's existing prefix-rendering convention) | Identifies the sending administrator to each recipient (FR-010) — not the server name, unlike `AdminNotices.send`. |
| command | `Command.WALLOPS` | |
| params | `[messageText]` | The raw text argument the administrator supplied. |

**Recipients**: computed at send time as
`NicknameRegistry.all().stream().filter(s -> s.userModes().contains(UserMode.WALLOPS))` —
never cached, always the live set of currently-opted-in, currently-connected sessions
(FR-008/FR-009). A session that disconnects before delivery is simply absent from
`NicknameRegistry.all()` already (existing disconnect-cleanup behavior) — no special
handling needed.

**State transitions**: none — a `WALLOPS` notice has no lifecycle beyond
construct-then-immediately-deliver-then-discard.
