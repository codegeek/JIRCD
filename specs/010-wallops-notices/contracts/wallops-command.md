# Contract: `WALLOPS` Command and `wallops` User Mode

Extends `specs/001-ircv3-server/contracts/irc-protocol-commands.md` — same two-layer split
(wire-protocol recognition in `jircd-protocol`, server behavior in `jircd-core`/extensions),
same numeric-reply conventions, same self-only `MODE` semantics already documented for
`operator`/`invisible`. This document covers only the `WALLOPS` command and the `wallops`
(`w`) user mode; every other command, mode, and general rule from `001-ircv3-server`'s
contract applies unchanged and is not repeated here.

This document does not modify `001-ircv3-server/contracts/irc-protocol-commands.md`'s Full
Command Catalog or User Mode Catalog tables — that file belongs to a closed feature and is
not edited by this one (the same convention `002-extended-irc-commands/contracts/irc-
protocol-commands-extended.md` already established). For this feature's purposes, the
`WALLOPS` row moves from "Recognized only" and the `w`/`wallops` row moves from "Reserved"
to **Implemented**, as recorded here.

Numeric replies referenced below are all pre-existing entries in
[`001-ircv3-server/contracts/irc-numeric-replies.md`](../../001-ircv3-server/contracts/irc-numeric-replies.md)'s
Full Numeric Catalog — none is newly introduced by this feature.

## `WALLOPS` command

| Command | Direction | Preconditions | Effect | Replies |
|---|---|---|---|---|
| `WALLOPS <text>` | C→S | Sender holds administrator privilege (`ClientSession.isAdministrator()`) AND the `admin` extension is currently `ENABLED` (`AdminPrivilege.isAuthorized`, same gate every other admin command uses) | Sends `<text>` as a `WALLOPS` message, prefixed with the sending administrator's own hostmask, to every currently connected session whose `userModes()` contains `wallops` (including the sender, if the sender's own `wallops` mode is set) | None on success (silent, like `NOTICE`) — see error replies below |

**Error replies**:

| Condition | Reply |
|---|---|
| Sender lacks administrator privilege, or the `admin` extension is disabled | `481 ERR_NOPRIVILEGES` — `"Permission Denied- You're not an IRC operator"` (identical wording to `KILL`/`SAMODE`/every other admin command's rejection) |
| No `<text>` parameter supplied | `461 ERR_NEEDMOREPARAMS` |
| `<text>` present but empty or whitespace-only | `412 ERR_NOTEXTTOSEND` (identical to `PRIVMSG`/`NOTICE`'s own empty-text rejection) |

**Contract notes**:

- Zero currently-connected sessions having `wallops` set is not an error — the command
  still succeeds silently (FR-011); there is simply nothing to deliver.
- The sending administrator receives their own notice under the exact same rule as any
  other recipient: only if their own `wallops` mode happens to be set. `WALLOPS` sends no
  separate confirmation reply to the sender (research.md "reuse existing numeric replies").
- A recipient that disconnects between the fan-out's recipient snapshot and its own
  `writer().enqueueRaw(...)` call is simply skipped, the same way any other multi-recipient
  send (e.g. channel `PRIVMSG`) already tolerates a mid-send disconnect.
- No server-to-server relay: this server has no multi-server linking concept in this
  release (spec.md Assumptions), so `WALLOPS` reaches only this server's own connected
  sessions.

## `wallops` (`w`) user mode

| Flag | `id` | `definedBy` | `clientSettable` | Status |
|---|---|---|---|---|
| `w` | `wallops` | `CORE` | `true` | **Implemented** (as of this feature) — self-settable via `MODE <self> +w`/`-w`, exactly like `invisible`; unlike `operator`, no privilege is required to set it |

**Contract notes**:

- Governed entirely by the existing generic self-only `MODE` handling already documented
  in `001-ircv3-server`'s User Mode contract: `MODE <nickname>` naming any nickname other
  than the sender's own current one still yields `502 ERR_USERSDONTMATCH`; an unrecognized
  flag still yields `501 ERR_UMODEUNKNOWNFLAG`. `WALLOPS`-specific behavior adds no new
  case to that handling.
- Defaults to unset on every new connection; never carried over from a previous, separate
  connection by the same user (spec.md Assumptions).
- Purely a delivery-eligibility flag for `WALLOPS` — it has no effect on any other command
  (`WHO`, `WHOIS`, `NAMES`, etc. are all unaffected by it, the same way `invisible` is
  scoped narrowly to the commands its own contract names).
