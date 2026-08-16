# Contract: Client-to-Server Commands

This contract has two layers, matching the `jircd-protocol`/`jircd-core`
split in plan.md's "Domain Model & Bounded Contexts":

1. **Wire-protocol recognition** (`jircd-protocol`) — the complete
   RFC 1459/2812 command set, plus the IRCv3 framework commands this
   project uses (`CAP`, `AUTHENTICATE`, `TAGMSG`). `jircd-protocol` is
   explicitly designed to be reusable by a future IRC *client* library
   (research.md "Protocol/server boundary"), so its command model and
   parser MUST recognize the full standard set — not just the subset this
   particular server implements — the same way a client library would
   need to parse a `WHOIS` reply from *any* server, not only one that
   happens to also run this codebase. See "Full Command Catalog" below.
2. **Server behavior** (`jircd-core` and extensions) — only the commands
   below, under "Implemented in This Release", have an actual handler in
   this server. A wire-protocol-recognized command with no handler is
   rejected the same way an unrecognized one would be: `421
   ERR_UNKNOWNCOMMAND` (contracts/irc-numeric-replies.md). Recognizing a
   command's grammar and having a handler for it are independent — this
   is why the two are documented separately.

Line-based text protocol per RFC 1459/2812 semantics with IRCv3 `CAP`
negotiation layered on top.

Numeric reply references point to [irc-numeric-replies.md](./irc-numeric-replies.md).

Every command below that includes a sender prefix on its outgoing message
(`JOIN`, `PART`, `PRIVMSG`, `NOTICE`, `QUIT`, `KICK`, `MODE`) presents that
sender in the standard `nickname!ident@hostname` form (FR-030), where
`hostname` is subject to the cloak `ServerExtension` described in
"Administration" below.

## Implemented in This Release

### Connection Registration

| Command | Direction | Preconditions | Effect | Replies (see numeric-replies contract) |
|---|---|---|---|---|
| `CAP LS [version]` | C→S | Any time before registration completes | Server returns its currently-available capability list | `CAP * LS :<capabilities>` |
| `CAP REQ :<caps>` | C→S | After `CAP LS` | Server enables requested capabilities it supports, declines the rest | `CAP * ACK`/`CAP * NAK` |
| `CAP END` | C→S | After negotiation | Ends negotiation; registration may complete | (none; unblocks registration) |
| `NICK <nickname>` | C→S | Session not yet holding a nickname, or changing an existing one | Atomically claims the nickname (FR-002) | `433 ERR_NICKNAMEINUSE` on conflict; silent success otherwise (reflected via subsequent replies) |
| `USER <user> <mode> <unused> :<realname>` | C→S | Nickname claimed | Completes registration (FR-001) | `001 RPL_WELCOME` and standard post-registration burst |

#### Connection Registration Grammar

CAP negotiation got its own dedicated grammar component
(`CapabilityNegotiationGrammar`, T018) because `CAP`'s subcommands
(`LS`/`REQ`/`ACK`/`NAK`/`END`) branch into genuinely different follow-on
syntax — that's a real sub-language within a line. `NICK`/`USER` don't
have that: `NICK` takes exactly one parameter and `USER` exactly four
fixed positional parameters (the last one trailing), so their line shape
is fully covered by the generic `COMMAND [params] [:trailing]` framing
`MessageParser` already handles, plus arity metadata from the `Command`
catalog (T014) for `461 ERR_NEEDMOREPARAMS`. No dedicated grammar class is
needed for that part — this is a deliberate asymmetry with `CAP`, not an
oversight.

What *was* missing, and does need its own definition, is the **content**
grammar for the two identifiers registration produces — what characters
and length are actually legal, independent of parameter count:

- **Nickname** (RFC 2812 §2.3.1): `( letter / special ) *8( letter /
  digit / special / "-" )` — one leading letter or `special`
  (`[`, `]`, `\`, `` ` ``, `_`, `^`, `{`, `|`, `}`), followed by up to 8
  more letters/digits/`special`/`-` (9 characters total). A `NICK`
  violating this is `432 ERR_ERRONEUSNICKNAME` (contracts/irc-numeric-replies.md);
  this is the concrete definition the "Edge case: nickname format" note
  there previously pointed at without ever stating.
- **Username** (the `<user>` parameter of `USER`, RFC 2812 §2.3.1's
  `user` production): any octet except NUL, CR, LF, space, and `@` — no
  length limit specified by the RFC itself, but this server MUST apply
  the same 9-character limit as `Hostmask`'s `ident` field for display
  consistency (contracts/irc-protocol-commands.md intro; FR-030) — an
  overlong `<user>` is truncated to 9 characters for the `ident` shown in
  the hostmask, not rejected outright (unlike an invalid nickname, which
  *is* rejected — usernames don't get their own `ERR_*` numeric in
  RFC 2812).

**Registration completion sequencing** (a `jircd-core`/`ConnectionLifecycle`
state-machine concern, not a wire-protocol grammar concern, since it's
about the order of already-parsed commands, not how to parse one line):
a session reaches `REGISTERED` once it holds a claimed nickname (`NICK`
succeeded) *and* has sent `USER`, *and* — if it sent `CAP LS` at all — has
also sent `CAP END`. `NICK` and `USER` MAY arrive in either order; `CAP`
negotiation, if used, MAY be interleaved with either. `001 RPL_WELCOME`
fires the moment all applicable conditions are satisfied, regardless of
which arrived last.

**Contract notes**:
- `CAP` negotiation MUST be usable before `NICK`/`USER` completes
  registration (FR-006), and registration MUST still succeed for clients
  that skip `CAP` entirely (FR-008).
- Only `message-tags`, `server-time`, and `echo-message` may appear in the
  `CAP LS` response for this release (FR-025); the SASL capability
  referenced in the spec is deferred with Story 3 and MUST NOT appear.

### Channel Operations

| Command | Direction | Preconditions | Effect | Replies |
|---|---|---|---|---|
| `JOIN <channel>` | C→S | `REGISTERED` session | Creates the channel if absent (first joiner becomes operator, FR-013) or joins existing (FR-003) | `JOIN` echoed to all members; `353 RPL_NAMREPLY` + `366 RPL_ENDOFNAMES` to joiner |
| `PART <channel> [:reason]` | C→S | Session is a member | Removes membership | `PART` echoed to all (former) members |
| `PRIVMSG <target> :<text>` | C→S | Session is a member of channel target, or target is any registered nickname for a direct message | Delivers to all other channel members (FR-004) or the direct-message recipient (FR-005) | `PRIVMSG` delivered to recipients; `echo-message`-negotiated senders also receive their own message back |
| `NOTICE <target> :<text>` | C→S | Same as `PRIVMSG` | Same delivery semantics as `PRIVMSG`, but MUST NOT trigger automated replies | Delivered like `PRIVMSG` |
| `QUIT [:reason]` | C→S | Any time | Disconnects; removes all channel memberships (FR-017) | `QUIT` echoed to all affected channels |

### Moderation (Story 5)

| Command | Direction | Preconditions | Effect | Replies |
|---|---|---|---|---|
| `KICK <channel> <nickname> [:reason]` | C→S | Sender is a channel operator (FR-013) | Removes the target from the channel | `KICK` echoed to all (former) members; `482 ERR_CHANOPRIVSNEEDED` if sender lacks privilege (FR-014) |
| `MODE <channel> <+/-flag>` | C→S | Sender is a channel operator | Applies a restriction (e.g., moderated-mode, only-members-may-speak per FR-013) | `MODE` echoed to all members; `482 ERR_CHANOPRIVSNEEDED` if unauthorized |

### Administration (Story 6)

| Command | Direction | Preconditions | Effect | Replies |
|---|---|---|---|---|
| `OPER <username> <password>` | C→S | `REGISTERED` session | Verifies credentials against `ServerConfiguration.administratorCredentials` (FR-034); on success, grants administrator privilege to this session | `381 RPL_YOUREOPER` on success; `464 ERR_PASSWDMISMATCH` on failure (also logged, FR-019) |
| `EXTENSION <ENABLE\|DISABLE> <extension-id>` | C→S | Sender holds administrator privilege | Toggles the named `CapabilityExtension` or `ServerExtension`'s state, in effect immediately for all clients (FR-011, FR-032) | Confirmation notice on success; `481 ERR_NOPRIVILEGES` if sender lacks administrator privilege (FR-033); `421 ERR_UNKNOWNCOMMAND`-style error naming the extension id if it doesn't exist |
| `WHOHOST <nickname>` | C→S | Sender holds administrator privilege | Returns the target's real, unobfuscated hostname/IP regardless of any active cloaking (FR-031, FR-032) | Notice containing the real hostname on success; `481 ERR_NOPRIVILEGES` if unauthorized; standard "no such nickname" error if the target isn't connected |
| `REHASH` | C→S | Sender holds administrator privilege | Manually re-reads and re-validates the Server Configuration file and reconciles it against live state — the in-band equivalent of a `SIGHUP` (research.md "Configuration reload mechanism", contracts/server-configuration.md "Live reload") | `382 RPL_REHASHING` on success; on validation failure, the same specific, actionable error startup validation would report (FR-012, SC-008), and the previously-active configuration remains untouched; `481 ERR_NOPRIVILEGES` if unauthorized |

**Contract notes**:
- `OPER` privilege is server-wide and independent of any channel-operator
  status (FR-033) — holding it does not grant channel-operator privileges
  in channels the administrator hasn't joined, and vice versa.
- These four commands are the FR-032 minimum; additional administrative
  commands MAY be added by future extensions without changing this
  contract.
- Command names (`EXTENSION`, `WHOHOST`, `REHASH`) are illustrative for
  this plan; the tasks phase may finalize different verbs as long as the
  preconditions/effects/replies contract above holds.
- `REHASH` and `EXTENSION` are not interchangeable: `REHASH` reloads the
  whole configuration file (extensions, rate limit, listeners,
  administrator credentials) and requires the file to be valid;
  `EXTENSION` changes a single extension's state directly in memory and
  never touches the file (contracts/server-configuration.md "Path
  equivalence").
- **Self-lockout**: `EXTENSION DISABLE admin`, issued by a privileged
  session through the `admin` extension itself, MUST succeed (an
  administrator is allowed to disable in-band administration) and takes
  effect immediately — the issuing session's own subsequent admin commands
  are then rejected with `481 ERR_NOPRIVILEGES` like any other
  non-privileged session, same as `contracts/server-configuration.md`'s
  "path equivalence" note. This is not an error state: the
  configuration-file path (Story 4) remains available to re-enable
  `admin` from outside the protocol.

## Full Command Catalog (`jircd-protocol` — Wire-Protocol Recognition)

Every command RFC 1459/2812 defines, plus the IRCv3 framework commands
this project uses. `jircd-protocol`'s parser and `Command` model
(data-model.md-adjacent, defined in `jircd-protocol`) MUST represent all
of these — parsing a line into a command name + parameters is generic
regardless of which commands `jircd-core` has handlers for, and a future
client library needs to recognize server output using the same commands
regardless of *this* server's feature scope. "Status" says whether
`jircd-core` has an actual handler *in this release*; "Recognized only"
commands still parse correctly and, if a client sends one, get `421
ERR_UNKNOWNCOMMAND` — the same reply an actually-unrecognized word would
get, since the wire protocol having a name for something doesn't obligate
any given server to implement it.

| Command | RFC 2812 § | Status in this release |
|---|---|---|
| `PASS` | 3.1.1 | Recognized only — this server has no server-wide connection password concept |
| `NICK` | 3.1.2 | **Implemented** — see "Connection Registration" above |
| `USER` | 3.1.3 | **Implemented** — see "Connection Registration" above |
| `OPER` | 3.1.4 | **Implemented**, but with this project's own privilege model, not RFC 2812's O-line concept — see "Administration" above |
| `MODE` (user) | 3.1.5 | Recognized only — no user modes defined in this release (only channel `MODE`, see below) |
| `SERVICE` | 3.1.6 | Recognized only — this server has no services-framework concept |
| `QUIT` | 3.1.7 | **Implemented** — see "Connection Registration" above |
| `SQUIT` | 3.1.8 | Recognized only — server-to-server command; this release has no server-to-server interface at all (FR-021) |
| `JOIN` | 3.2.1 | **Implemented** — see "Channel Operations" above |
| `PART` | 3.2.2 | **Implemented** — see "Channel Operations" above |
| `MODE` (channel) | 3.2.3 | **Implemented** — see "Moderation" above (`+m`/members-only variants only; other channel modes are recognized-only) |
| `TOPIC` | 3.2.4 | Recognized only — no channel topic concept in this release |
| `NAMES` | 3.2.5 | Recognized only — `JOIN` already returns `353`/`366` (see "Channel Operations"); a bare `NAMES` query is not implemented |
| `LIST` | 3.2.6 | Recognized only — no channel-listing feature in this release |
| `INVITE` | 3.2.7 | Recognized only — no invite-only channel concept in this release |
| `KICK` | 3.2.8 | **Implemented** — see "Moderation" above |
| `PRIVMSG` | 3.3.1 | **Implemented** — see "Channel Operations" above |
| `NOTICE` | 3.3.2 | **Implemented** — see "Channel Operations" above |
| `MOTD` | 3.4.1 | Recognized only — no message-of-the-day feature in this release |
| `LUSERS` | 3.4.2 | Recognized only |
| `VERSION` | 3.4.3 | Recognized only |
| `STATS` | 3.4.4 | Recognized only |
| `LINKS` | 3.4.5 | Recognized only — server-to-server; not applicable to a standalone release (FR-021) |
| `TIME` | 3.4.6 | Recognized only |
| `CONNECT` | 3.4.7 | Recognized only — server-to-server; not applicable to a standalone release (FR-021) |
| `TRACE` | 3.4.8 | Recognized only |
| `ADMIN` | 3.4.9 | Recognized only |
| `INFO` | 3.4.10 | Recognized only |
| `SERVLIST` | 3.5.1 | Recognized only — this server has no services-framework concept |
| `SQUERY` | 3.5.2 | Recognized only |
| `WHO` | 3.6.1 | Recognized only — no user-query feature in this release |
| `WHOIS` | 3.6.2 | Recognized only |
| `WHOWAS` | 3.6.3 | Recognized only |
| `KILL` | 3.7.1 | Recognized only — no forced-disconnect admin command in this release (an administrator can approximate this via a future `EXTENSION`-adjacent command, but none exists yet) |
| `PING` | 3.7.2 | **Implemented** — core connection keep-alive, answered with `PONG` |
| `PONG` | 3.7.3 | **Implemented** — accepted as a client's reply to the server's `PING` |
| `ERROR` | 3.7.4 | **Implemented** — sent by the server immediately before forcibly closing a connection (e.g., protocol violation) |
| `AWAY` | 4.1 | Recognized only — no away-status feature in this release |
| `REHASH` | 4.2 | **Implemented** — see "Administration" above (this project's version is IRC-command-only, not also a `DIE`/`RESTART`-style local-admin command) |
| `DIE` | 4.3 | Recognized only — no remote-shutdown admin command in this release |
| `RESTART` | 4.4 | Recognized only |
| `SUMMON` | 4.5 | Recognized only — this legacy command (notify a local Unix user) has no meaning for this server |
| `USERS` | 4.6 | Recognized only |
| `WALLOPS` | 4.7 | Recognized only — no server-wide operator broadcast in this release |
| `USERHOST` | 4.8 | Recognized only |
| `ISON` | 4.9 | Recognized only |
| `CAP` | IRCv3 | **Implemented** — see "Connection Registration" above |
| `AUTHENTICATE` | IRCv3 (SASL) | Recognized only — deferred with Story 3 (FR-009/FR-010); MUST NOT be offered as a capability (contracts note above) |
| `TAGMSG` | IRCv3 | Recognized only — no tag-only-message feature in this release, though `message-tags` (FR-025) itself is implemented for regular messages |
| `EXTENSION` | This project | **Implemented** — see "Administration" above; not an RFC 1459/2812 command |
| `WHOHOST` | This project | **Implemented** — see "Administration" above; not an RFC 1459/2812 command |

**Contract notes**:
- A "Recognized only" command still parses without error at the
  wire-protocol layer (correct command name, correct generic
  `COMMAND [params] [:trailing]` framing) — `jircd-core` is the layer that
  decides it has no handler and replies `421`. This split is what lets a
  future client library depend on `jircd-protocol` alone and get a
  complete, standard-compliant parser, independent of which commands any
  particular server (this one or another) has chosen to implement.
- Numeric replies associated with "Recognized only" commands (e.g.,
  `311`-`319` for `WHOIS`) are still defined in the full numeric catalog
  (irc-numeric-replies.md) for the same reason — a client library needs
  to be able to parse them from *any* server's responses, not only ones
  this server currently sends.
- This table does not change what any FR requires the server to *do* —
  it documents what the wire-protocol layer can *parse and represent*.
  Moving a "Recognized only" command to "Implemented" in a future release
  is a `jircd-core`/extension change, not a `jircd-protocol` change.

## Explicitly Out of Scope for This Plan

This section is about **server behavior scope** (what `jircd-core` and
its extensions implement) — distinct from the Full Command Catalog above,
which is about **wire-protocol recognition** and includes all of these by
name.

- `AUTHENTICATE` / SASL exchange (Story 3, FR-009/FR-010) — deferred.
- Any `NickServ`/`ChanServ`-style registration command (FR-023/FR-024/
  FR-026/FR-027) — deferred.
- Server-to-server commands (`SQUIT`, `LINKS`, `CONNECT`, federation
  generally — FR-021/FR-022/FR-028/FR-029) — deferred; this server has no
  server-to-server interface at all in this release, though their command
  grammar is still recognized per the Full Command Catalog above.
