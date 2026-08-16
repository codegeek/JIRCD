# Contract: Server-to-Client Numeric Replies (In Scope)

Numeric replies this server MUST produce for the commands in
[irc-protocol-commands.md](./irc-protocol-commands.md), per RFC 1459/2812
where a standard numeric exists. Error replies MUST be worded specifically
enough to satisfy FR-002/FR-012/FR-014/FR-015's "clear error" requirements
(constitution Principle III: error messages state what went wrong and what
the client/administrator can do about it).

| Numeric | Name | Triggered by | FR |
|---|---|---|---|
| `001` | `RPL_WELCOME` | Successful registration | FR-001 |
| `353` | `RPL_NAMREPLY` | `JOIN` | FR-003 |
| `366` | `RPL_ENDOFNAMES` | `JOIN` | FR-003 |
| `421` | `ERR_UNKNOWNCOMMAND` | Malformed/unrecognized command | FR-015 |
| `431` | `ERR_NONICKNAMEGIVEN` | `NICK` with no argument | FR-001 (input validation) |
| `432` | `ERR_ERRONEUSNICKNAME` | `NICK` violating format rules (invalid chars/length) | Edge case: nickname format |
| `433` | `ERR_NICKNAMEINUSE` | `NICK` naming an already-claimed nickname | FR-002 |
| `442` | `ERR_NOTONCHANNEL` | `PART`/`PRIVMSG`/`KICK`/`MODE` on a channel the sender hasn't joined (where membership is required) | FR-003, FR-014 |
| `461` | `ERR_NEEDMOREPARAMS` | Command missing required parameters | FR-015 |
| `472` | `ERR_UNKNOWNMODE` | `MODE` given a flag the core moderation command set doesn't define | FR-015, FR-036 (core input validation — `MODE` is never extension-gated) |
| `482` | `ERR_CHANOPRIVSNEEDED` | `KICK`/`MODE` attempted by a non-operator | FR-014 |
| `381` | `RPL_YOUREOPER` | Successful `OPER` | FR-034 |
| `464` | `ERR_PASSWDMISMATCH` | Failed `OPER` (also logged as a security event, FR-019) | FR-034 |
| `481` | `ERR_NOPRIVILEGES` | `MODULE`/`WHOHOST` (or any admin command) attempted without administrator privilege | FR-033 |
| `CAP * LS` | (IRCv3, not a numeric) | `CAP LS` | FR-006 |
| `CAP * ACK` / `CAP * NAK` | (IRCv3) | `CAP REQ` | FR-007 |

**Contract notes**:
- Every error reply's trailing `:<text>` parameter MUST name the specific
  problem (e.g., "Nickname is already in use" for `433`, not a generic
  "Error") — this is the testable form of FR-002/FR-012/FR-014/FR-015's
  "clear error" requirement and of the constitution's UX Consistency
  principle.
- Reply wording for a given numeric MUST be identical regardless of which
  subsystem produced it. `482` is now always sourced from core channel
  moderation (FR-036) rather than an extension, so this mainly applies to
  `481`: it MUST read the same whether it was triggered by the core admin
  command set or by a future `jircd-server-extensions/*` extension that
  also gates an action on administrator privilege — satisfying
  FR-011/FR-020's extension-consistency intent at the protocol level.
