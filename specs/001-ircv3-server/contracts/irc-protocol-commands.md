# Contract: Client-to-Server Commands (In Scope)

This is the wire-protocol contract the server exposes to IRC clients — the
external interface a client-facing network service like this exposes
instead of a REST/RPC API. Line-based text protocol per RFC 1459/2812
semantics with IRCv3 CAP negotiation layered on top, restricted to what is
in scope for this plan (see spec.md's Clarifications for what's deferred).

Numeric reply references point to [irc-numeric-replies.md](./irc-numeric-replies.md).

Every command below that includes a sender prefix on its outgoing message
(`JOIN`, `PART`, `PRIVMSG`, `NOTICE`, `QUIT`, `KICK`, `MODE`) presents that
sender in the standard `nickname!ident@hostname` form (FR-030), where
`hostname` is subject to the cloak `ServerExtension` described in
"Administration" below.

## Connection Registration

| Command | Direction | Preconditions | Effect | Replies (see numeric-replies contract) |
|---|---|---|---|---|
| `CAP LS [version]` | C→S | Any time before registration completes | Server returns its currently-available capability list | `CAP * LS :<capabilities>` |
| `CAP REQ :<caps>` | C→S | After `CAP LS` | Server enables requested capabilities it supports, declines the rest | `CAP * ACK`/`CAP * NAK` |
| `CAP END` | C→S | After negotiation | Ends negotiation; registration may complete | (none; unblocks registration) |
| `NICK <nickname>` | C→S | Session not yet holding a nickname, or changing an existing one | Atomically claims the nickname (FR-002) | `433 ERR_NICKNAMEINUSE` on conflict; silent success otherwise (reflected via subsequent replies) |
| `USER <user> <mode> <unused> :<realname>` | C→S | Nickname claimed | Completes registration (FR-001) | `001 RPL_WELCOME` and standard post-registration burst |

**Contract notes**:
- `CAP` negotiation MUST be usable before `NICK`/`USER` completes
  registration (FR-006), and registration MUST still succeed for clients
  that skip `CAP` entirely (FR-008).
- Only `message-tags`, `server-time`, and `echo-message` may appear in the
  `CAP LS` response for this release (FR-025); the SASL capability
  referenced in the spec is deferred with Story 3 and MUST NOT appear.

## Channel Operations

| Command | Direction | Preconditions | Effect | Replies |
|---|---|---|---|---|
| `JOIN <channel>` | C→S | `REGISTERED` session | Creates the channel if absent (first joiner becomes operator, FR-013) or joins existing (FR-003) | `JOIN` echoed to all members; `353 RPL_NAMREPLY` + `366 RPL_ENDOFNAMES` to joiner |
| `PART <channel> [:reason]` | C→S | Session is a member | Removes membership | `PART` echoed to all (former) members |
| `PRIVMSG <target> :<text>` | C→S | Session is a member of channel target, or target is any registered nickname for a direct message | Delivers to all other channel members (FR-004) or the direct-message recipient (FR-005) | `PRIVMSG` delivered to recipients; `echo-message`-negotiated senders also receive their own message back |
| `NOTICE <target> :<text>` | C→S | Same as `PRIVMSG` | Same delivery semantics as `PRIVMSG`, but MUST NOT trigger automated replies | Delivered like `PRIVMSG` |
| `QUIT [:reason]` | C→S | Any time | Disconnects; removes all channel memberships (FR-017) | `QUIT` echoed to all affected channels |

## Moderation (Story 5)

| Command | Direction | Preconditions | Effect | Replies |
|---|---|---|---|---|
| `KICK <channel> <nickname> [:reason]` | C→S | Sender is a channel operator (FR-013) | Removes the target from the channel | `KICK` echoed to all (former) members; `482 ERR_CHANOPRIVSNEEDED` if sender lacks privilege (FR-014) |
| `MODE <channel> <+/-flag>` | C→S | Sender is a channel operator | Applies a restriction (e.g., moderated-mode, only-members-may-speak per FR-013) | `MODE` echoed to all members; `482 ERR_CHANOPRIVSNEEDED` if unauthorized |

## Administration (Story 6)

| Command | Direction | Preconditions | Effect | Replies |
|---|---|---|---|---|
| `OPER <username> <password>` | C→S | `REGISTERED` session | Verifies credentials against `ServerConfiguration.administratorCredentials` (FR-034); on success, grants administrator privilege to this session | `381 RPL_YOUREOPER` on success; `464 ERR_PASSWDMISMATCH` on failure (also logged, FR-019) |
| `EXTENSION <ENABLE\|DISABLE> <extension-id>` | C→S | Sender holds administrator privilege | Toggles the named `CapabilityExtension` or `ServerExtension`'s state, in effect immediately for all clients (FR-011, FR-032) | Confirmation notice on success; `481 ERR_NOPRIVILEGES` if sender lacks administrator privilege (FR-033); `421 ERR_UNKNOWNCOMMAND`-style error naming the extension id if it doesn't exist |
| `WHOHOST <nickname>` | C→S | Sender holds administrator privilege | Returns the target's real, unobfuscated hostname/IP regardless of any active cloaking (FR-031, FR-032) | Notice containing the real hostname on success; `481 ERR_NOPRIVILEGES` if unauthorized; standard "no such nickname" error if the target isn't connected |

**Contract notes**:
- `OPER` privilege is server-wide and independent of any channel-operator
  status (FR-033) — holding it does not grant channel-operator privileges
  in channels the administrator hasn't joined, and vice versa.
- These three commands are the FR-032 minimum; additional administrative
  commands MAY be added by future extensions without changing this
  contract.
- Command names (`EXTENSION`, `WHOHOST`) are illustrative for this plan;
  the tasks phase may finalize different verbs as long as the
  preconditions/effects/replies contract above holds.
- **Self-lockout**: `EXTENSION DISABLE admin`, issued by a privileged
  session through the `admin` extension itself, MUST succeed (an
  administrator is allowed to disable in-band administration) and takes
  effect immediately — the issuing session's own subsequent admin commands
  are then rejected with `481 ERR_NOPRIVILEGES` like any other
  non-privileged session, same as `contracts/server-configuration.md`'s
  "path equivalence" note. This is not an error state: the
  configuration-file path (Story 4) remains available to re-enable
  `admin` from outside the protocol.

## Explicitly Out of Scope for This Plan

- `AUTHENTICATE` / SASL exchange (Story 3, FR-009/FR-010) — deferred.
- Any `NickServ`/`ChanServ`-style registration command (FR-023/FR-024/
  FR-026/FR-027) — deferred.
- Server-to-server commands (federation, FR-021/FR-022/FR-028/FR-029) —
  deferred; this server has no server-to-server interface at all in this
  release.
