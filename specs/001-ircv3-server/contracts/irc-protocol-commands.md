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
negotiation layered on top. A line MUST NOT exceed 512 bytes (including
the trailing CR-LF) for its command and parameters, plus up to 4096
additional bytes for a `message-tags` tag section specifically, per the
IRCv3 message-tags specification's required server-side allowance
(FR-025, FR-049) — this server implements `message-tags`, so the
allowance applies. A line exceeding either budget MUST be rejected with
`417 ERR_INPUTTOOLONG` (contracts/irc-numeric-replies.md) — a dedicated
error distinct from `421`/`461`'s generic malformed-message handling
(FR-015, FR-049), not silently truncated or partially processed.

Command names are matched case-insensitively (FR-015) — every command
below is written in canonical uppercase purely for readability, not
because case is significant on the wire; `join`/`Join`/`JOIN` are the
same command. Nicknames and channel names are likewise compared
case-insensitively wherever a command names one as a target (FR-052,
research.md "IRC casemapping") — `PRIVMSG alice` and `PRIVMSG Alice`
reach the same client if that client registered as either casing.

Human-readable message text — `PRIVMSG`/`NOTICE` bodies, channel topics,
realnames, and channel names — MUST be valid UTF-8 (FR-054). A field
containing an invalid UTF-8 byte sequence is rejected as malformed
(FR-015, `421 ERR_UNKNOWNCOMMAND`-style rejection — the same generic
malformed-message handling as any other unparseable message; unlike the
line-length/channel-grammar cases, this doesn't get a dedicated numeric
like `417`/`476`, since RFC 1459/2812 predates UTF-8 entirely and no de
facto standard numeric for this exists to reuse. Not `461
ERR_NEEDMOREPARAMS` — the parameter is present, just invalidly encoded,
a different failure than a missing one)
rather than passed through, silently mistranscoded, or partially
accepted. This does not apply to nicknames or the `USER` command's
`<user>` parameter — both are protocol identifiers with their own
dedicated, ASCII-oriented grammars ("Connection Registration Grammar"
below), not human-readable content.

Numeric reply references point to [irc-numeric-replies.md](./irc-numeric-replies.md).

Every command below that includes a sender prefix on its outgoing message
(`JOIN`, `PART`, `PRIVMSG`, `NOTICE`, `QUIT`, `KICK`, `MODE`) presents that
sender in the standard `nickname!ident@hostname` form (FR-030), where
`hostname` is subject to the cloak `ServerExtension` described in
"Administration" below. Every numeric reply, by contrast, is prefixed
with the server's own name (`ServerConfiguration.serverName`, FR-050,
data-model.md) — the server-originated counterpart to a client's
hostmask prefix — not any client's identity.

## Implemented in This Release

### Connection Registration

| Command | Direction | Preconditions | Effect | Replies (see numeric-replies contract) |
|---|---|---|---|---|
| `CAP LS [version]` | C→S | Any time before registration completes | Server returns its currently-available capability list | `CAP * LS :<capabilities>` |
| `CAP REQ :<caps>` | C→S | After `CAP LS` | Server enables requested capabilities it supports, declines the rest | `CAP * ACK`/`CAP * NAK` |
| `CAP END` | C→S | After negotiation | Ends negotiation; registration may complete | (none; unblocks registration) |
| `NICK <nickname>` | C→S | Session not yet holding a nickname, or changing an existing one | Atomically claims the nickname (FR-002) | `433 ERR_NICKNAMEINUSE` on conflict; silent success otherwise (reflected via subsequent replies) |
| `USER <user> <mode> <unused> :<realname>` | C→S | Nickname claimed; `<realname>` MUST be valid UTF-8 (FR-054) | Completes registration (FR-001) | Registration Completion Burst (below); `421 ERR_UNKNOWNCOMMAND`-style malformed-message rejection (FR-015) if `<realname>` isn't valid UTF-8 |

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
negotiation, if used, MAY be interleaved with either. The Registration
Completion Burst below fires the moment all applicable conditions are
satisfied, regardless of which arrived last.

#### Registration Completion Burst

What "`001 RPL_WELCOME` and standard post-registration burst" actually
means, concretely (FR-051) — previously referenced without ever being
defined, the same class of gap the nickname/channel grammars closed for
`432`/`476`:

| Numeric | Content |
|---|---|
| `001 RPL_WELCOME` | Welcome confirmation, addressed to the newly registered nickname |
| `002 RPL_YOURHOST` | `serverName` and `serverVersion` (`ServerConfiguration`, FR-050, data-model.md) |
| `003 RPL_CREATED` | This running process's start time — not a fixed software release date |
| `004 RPL_MYINFO` | `serverName`, `serverVersion`, the currently-recognized user-mode letters (empty this release, FR-044), and the currently-recognized channel-mode letters — sourced live from the same `ChannelMode` catalog `MODE` itself consults (research.md "Channel/user mode extensibility"), so this never drifts out of sync with what `MODE` actually recognizes |
| `422 ERR_NOMOTD` | Closes the burst — this release implements no message-of-the-day content (`MOTD` itself remains "Recognized only," Full Command Catalog below); `422` gives clients a defined completion signal instead of an indefinite wait for one |

**Contract notes**:
- `CAP` negotiation MUST be usable before `NICK`/`USER` completes
  registration (FR-006), and registration MUST still succeed for clients
  that skip `CAP` entirely (FR-008).
- Only `message-tags`, `server-time`, and `echo-message` may appear in the
  `CAP LS` response for this release (FR-025); the SASL capability
  referenced in the spec is deferred with Story 3 and MUST NOT appear.
- Any numeric reply sent while a session has not yet claimed a nickname
  — most notably `431`/`432`/`433`, all reachable during `NICK`
  negotiation before registration completes — MUST address that reply
  to `*` (FR-053), the standard "no nickname yet" placeholder, not an
  empty value or a nickname the session hasn't actually claimed.

### Connection Keep-Alive

| Command | Direction | Preconditions | Effect | Replies |
|---|---|---|---|---|
| `PING [token]` | C→S | Any time | Server replies immediately | `PONG [token]` |
| `PING [token]` | S→C | This connection has been idle beyond the configured keep-alive probe interval (FR-039) | Probes whether the client is still there | Client is expected to reply `PONG [token]`; no reply within the configured timeout closes the connection (`ERROR` sent first, then the same FR-017 cleanup/notification path as any other disconnect) |
| `PONG [token]` | C→S | Sent in reply to a server-initiated `PING` | Resets this connection's keep-alive timer (FR-039) | (none; an unsolicited `PONG` with no outstanding server `PING` is accepted and ignored) |
| `ERROR :<reason>` | S→C | Server is about to forcibly close this connection (a keep-alive timeout, FR-039, or another protocol-level cause) | Final line sent before closing the socket | (none; the connection closes immediately after) |

**Contract notes**:
- `PING`/`PONG` are core connection-management behavior, like capability
  negotiation (FR-035) and channel moderation (FR-036) — always present,
  never one of the toggleable `capabilities`/`server-extensions`.
- This is symmetric, not just server-initiated: a client MAY send `PING`
  at any time (even before registration completes) and MUST receive an
  immediate `PONG`, independently of whatever keep-alive probing the
  server itself is doing on that connection.

### Channel Operations

| Command | Direction | Preconditions | Effect | Replies |
|---|---|---|---|---|
| `JOIN <channel>` | C→S | `REGISTERED` session; `channel` conforms to the Channel Name Grammar (below) | Creates the channel if absent (first joiner becomes operator, FR-013) or joins existing (FR-003) | `JOIN` echoed to all members; `353 RPL_NAMREPLY` + `366 RPL_ENDOFNAMES` to joiner; `476 ERR_BADCHANMASK` if `channel` violates the grammar |
| `PART <channel> [:reason]` | C→S | Session is a member | Removes membership | `PART` echoed to all (former) members |
| `PRIVMSG <target> :<text>` | C→S | `REGISTERED` session; for a channel target, membership is NOT required by default — only when that channel's `members-only` restriction is active (FR-004, FR-013/FR-043); for a nickname target, it must be any registered nickname; `<text>` MUST be valid UTF-8 (FR-054) | Delivers to all other channel members (FR-004) or the direct-message recipient (FR-005) | `PRIVMSG` delivered to recipients; `echo-message`-negotiated senders also receive their own message back; `442 ERR_NOTONCHANNEL` if `members-only` is active and the sender isn't a member (FR-013/FR-043); `421 ERR_UNKNOWNCOMMAND`-style malformed-message rejection (FR-015) if `<text>` isn't valid UTF-8 |
| `NOTICE <target> :<text>` | C→S | Same as `PRIVMSG` | Same delivery semantics as `PRIVMSG`, but MUST NOT trigger automated replies | Delivered like `PRIVMSG` |
| `QUIT [:reason]` | C→S | Any time | Disconnects; removes all channel memberships (FR-017) | `QUIT` echoed to all affected channels |
| `TOPIC <channel>` | C→S | `REGISTERED` session; no membership required for a non-private/secret channel, or for a member/administrator of one (FR-040/FR-041's discovery framing, subject to FR-047) | Returns `channel`'s current topic | `332 RPL_TOPIC` if a topic is set, `331 RPL_NOTOPIC` if not; `403 ERR_NOSUCHCHANNEL` if `channel` doesn't exist, or is private/secret and the requester is neither a member nor an administrator (FR-047 — same response either way) |
| `TOPIC <channel> :<topic>` | C→S | `REGISTERED` session; sender is a channel operator (FR-013); `<topic>` MUST be valid UTF-8 (FR-054) | Sets/changes `channel`'s topic (FR-040) | `TOPIC` echoed to all members on success; `482 ERR_CHANOPRIVSNEEDED` if sender isn't an operator; `421 ERR_UNKNOWNCOMMAND`-style malformed-message rejection (FR-015) if `<topic>` isn't valid UTF-8 |
| `NAMES <channel>` | C→S | `REGISTERED` session; no membership required for a non-private/secret channel, or for a member/administrator of one (FR-041, subject to FR-047) | Returns `channel`'s current membership list, the same on-demand query `JOIN` already triggers automatically | `353 RPL_NAMREPLY` + `366 RPL_ENDOFNAMES`; `461 ERR_NEEDMOREPARAMS` if `channel` is omitted; `403 ERR_NOSUCHCHANNEL` under the identical private/secret condition `TOPIC` uses (FR-047) |
| `LIST` | C→S | `REGISTERED` session | Returns every currently active channel (FR-042), except a private/secret one the requester isn't a member of and isn't an administrator for (FR-047) — silently omitted, not flagged as skipped | One `322 RPL_LIST` per (visible) channel, then `323 RPL_LISTEND` |

#### Channel Name Grammar

Mirrors "Connection Registration Grammar" above: no dedicated grammar
*class* is needed (a channel name is a single token, already covered by
the generic `COMMAND [params]` framing `MessageParser` handles), but the
**content** grammar — what characters and length are actually legal —
was previously missing and needed its own definition (FR-048).

- **Channel name** (RFC 2812 §1.3, standard-type channels only —
  `#`-prefixed; the `&`/`+`/`!` channel-type prefixes RFC 2811 also
  defines are out of scope, this server has one channel namespace):
  a leading `#`, followed by 1 to 49 additional characters excluding
  space, comma, and control characters (50 characters total, maximum). A
  `JOIN` naming a channel that violates this is `476 ERR_BADCHANMASK`
  (contracts/irc-numeric-replies.md) — RFC 2812's existing numeric for
  "the channel name/mask given is invalid," reused rather than inventing
  a new one, the same way `432 ERR_ERRONEUSNICKNAME` was reused (not
  invented) for nickname grammar.
- This is an *independent* check from FR-003's channel-name uniqueness,
  the same relationship nickname format (`432`) and nickname uniqueness
  (`433`) already have to each other: a syntactically valid name that's
  already claimed still succeeds (joins the existing channel, FR-003);
  a syntactically invalid name is rejected before uniqueness is even
  considered.
- A third, independent check: the name MUST also be valid UTF-8 (FR-054)
  — the byte-exclusion grammar above doesn't by itself guarantee
  well-formed UTF-8, so a name can pass it and still fail this check.
  Also `476 ERR_BADCHANMASK`, the same numeric a grammar violation uses —
  both are "this isn't a legal channel name" from the client's point of
  view, not two different failure classes worth distinguishing.

**Contract notes**:
- `TOPIC`-viewing, `NAMES`, and `LIST` deliberately do not require channel
  membership — they are discovery operations (FR-041/FR-042), the same
  role `WHOIS` (below) plays for user information, not moderation-gated
  like `KICK`/`MODE` (Story 5) — **except** for a private/secret channel
  (FR-047), where all three treat a non-member, non-administrator
  requester exactly as if the channel didn't exist. `TOPIC`/`NAMES` use
  `403 ERR_NOSUCHCHANNEL` (the same error a genuinely nonexistent channel
  already produces); `LIST` simply omits the channel from its output.
  Neither response is distinguishable from the channel not existing at
  all — a weaker, "access denied"-style error would defeat the entire
  point of `private`/`secret`.
- `TOPIC`-setting is the one operator-gated action in this section; it
  reuses `Channel.operators` (FR-013), already established at `JOIN` — no
  separate authorization mechanism is introduced for it.
- `353 RPL_NAMREPLY`'s nickname list MUST prefix each operator's nickname
  with `@` and each voiced (non-operator) member's nickname with `+`,
  the standard IRC convention every client already expects — without it,
  `voice` (FR-045) would be invisible in practice even though it's
  correctly enforced server-side: a client showing no visual difference
  between a voiced and a non-voiced member defeats the point of granting
  voice at all. A member who is both an operator and voiced is prefixed
  `@` only, matching standard client expectations (operator implies the
  ability to speak; the `+` prefix communicates *additional* information
  a plain `@` doesn't).

### User Queries (Story 7)

| Command | Direction | Preconditions | Effect | Replies |
|---|---|---|---|---|
| `WHOIS [target]` | C→S | `REGISTERED` session | Looks up `target` (the sender's own session if omitted) and returns its nickname, ident, hostname, and real name (FR-037). The returned hostname follows FR-038's three-tier resolution: real value for a self-lookup or an administrator, otherwise the same presented value the target's message hostmask already shows to this sender (FR-030/031) | `311 RPL_WHOISUSER` then `318 RPL_ENDOFWHOIS` on success; `401 ERR_NOSUCHNICK` if `target` isn't connected |

**Contract notes**:
- `WHOIS` is core protocol behavior (FR-037), like moderation (FR-036) and
  capability negotiation (FR-035) — it is never one of the toggleable
  `jircd-capabilities/*`/`jircd-server-extensions/*` extensions, and an
  administrator cannot disable it.
- The hostname field's real-vs-presented resolution (FR-038) MUST reuse
  the exact same computation `UserIdentity.presentedForm` (data-model.md)
  already uses for message hostmasks, and the exact same
  `ClientSession.realHostname` source of truth `WHOHOST` (Administration,
  below) already reads for the administrator case — `WHOIS` MUST NOT
  reimplement this resolution independently. Two independent
  implementations of "who gets to see the real value" is exactly the kind
  of divergence that turns into a privacy bug later.

### Moderation (Story 5)

| Command | Direction | Preconditions | Effect | Replies |
|---|---|---|---|---|
| `KICK <channel> <nickname> [:reason]` | C→S | Sender is a channel operator (FR-013) | Removes the target from the channel | `KICK` echoed to all (former) members; `482 ERR_CHANOPRIVSNEEDED` if sender lacks privilege (FR-014) |
| `MODE <channel> <+/->m\|n` | C→S | Sender is a channel operator | Sets/clears `moderated` (`m`) or `members-only` (`n`), independently (FR-013; "Full Channel Mode Catalog" below) | `MODE` echoed to all members; `482 ERR_CHANOPRIVSNEEDED` if unauthorized; `472 ERR_UNKNOWNMODE` for any other flag letter, or a bare mode query with no flag argument (FR-043) |
| `MODE <channel> <+/->v <nickname>` | C→S | Sender is a channel operator; `<nickname>` is a current member of `<channel>` | Grants (`+v`) or revokes (`-v`) `<nickname>`'s voice privilege (FR-045) — while `moderated` (`m`) is active, `<nickname>` may now send in addition to operators | `MODE` echoed to all members; `482 ERR_CHANOPRIVSNEEDED` if sender isn't an operator; `441 ERR_USERNOTINCHANNEL` if `<nickname>` isn't a current member |
| `MODE <channel> <+/->o <nickname>` | C→S | Sender is a channel operator; `<nickname>` is a current member of `<channel>` | Grants (`+o`) or revokes (`-o`) `<nickname>`'s operator status (FR-046) — in addition to, not a replacement for, first-join-gets-operator (FR-013); `<nickname>` MAY be the sender themselves (self-revocation is permitted, even if it leaves the channel with zero operators) | `MODE` echoed to all members; `482 ERR_CHANOPRIVSNEEDED` if sender isn't an operator; `441 ERR_USERNOTINCHANNEL` if `<nickname>` isn't a current member |
| `MODE <channel> <+/->p\|s` | C→S | Sender is a channel operator | Sets/clears `private` (`p`) or `secret` (`s`) (FR-047) — setting either one clears the other if it was active (mutually exclusive, data-model.md `Channel` validation rules); while active, hides the channel from `TOPIC`/`NAMES`/`LIST` for non-members ("Channel Operations" above) | `MODE` echoed to all members; `482 ERR_CHANOPRIVSNEEDED` if unauthorized; `472 ERR_UNKNOWNMODE` for any other flag letter |

**Contract notes**:
- This release's channel `MODE` implements four `BOOLEAN`-kind flags —
  the two FR-013 defines (`moderated`, `members-only`), plus `private`/
  `secret` (FR-047) — directly, unconditionally (never gated by an
  `Extension`, FR-036), plus the two `MEMBER`-kind flags FR-045/FR-046
  define (`voice`, `operator`). Every other standard channel mode ("Full
  Channel Mode Catalog" below — invite-only, channel-key, user-limit,
  ban-mask, topic-lock) remains out of scope for this release's behavior
  (FR-043).
- `operator` status has two independent paths to acquiring it in this
  release: first-join-gets-operator (FR-013, how a channel's very first
  operator is established) and `MODE +o` from an existing operator
  (FR-046, how it subsequently spreads). Neither supersedes the other —
  first-join only ever applies at channel creation, and `MODE +o` only
  ever applies to an already-existing channel with at least one current
  operator to issue it.
- `moderated` (`+m`) means "operators **and** voiced members may send,"
  matching classic IRC's full `+m` semantic — not "operators only." A
  member who is voiced but not an operator may send while `moderated` is
  active; an operator never needs to also be voiced.
- Unlike the "recognized only" commands elsewhere in this catalog, the
  boundary above isn't meant to be permanent for `BOOLEAN`-kind flags:
  FR-043 requires the `MODE` mechanism itself to support an optional
  server extension contributing additional `BOOLEAN` flags later
  (research.md "Channel/user mode extensibility", data-model.md
  `ChannelMode`) without a core protocol change — no extension does so in
  this release. `VALUE`/`LIST`-kind flags (channel-key, user-limit,
  ban-mask) are a narrower promise: cataloged below, but `Channel`'s
  shape can't hold one yet, so adding one is a bigger change than adding
  another `BOOLEAN` flag would be.
- User-level `MODE` is entirely unimplemented (FR-044), not partially
  implemented like channel `MODE` — this server has no user-mode concept
  at all yet, deliberately, not as an oversight. When one is added, it
  MUST use the same extension-contribution mechanism as channel `MODE`.

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
| `MODE` (user) | 3.1.5 | Recognized only — no user modes defined in this release, deliberately (FR-044; only channel `MODE`, see below) |
| `SERVICE` | 3.1.6 | Recognized only — this server has no services-framework concept |
| `QUIT` | 3.1.7 | **Implemented** — see "Connection Registration" above |
| `SQUIT` | 3.1.8 | Recognized only — server-to-server command; this release has no server-to-server interface at all (FR-021) |
| `JOIN` | 3.2.1 | **Implemented** — see "Channel Operations" above |
| `PART` | 3.2.2 | **Implemented** — see "Channel Operations" above |
| `MODE` (channel) | 3.2.3 | **Implemented** — see "Moderation" above (`+m`/members-only variants only; other channel mode flags, and a bare mode query, are scoped out per FR-043, not merely recognized-only-and-forgotten) |
| `TOPIC` | 3.2.4 | **Implemented** — see "Channel Operations" above |
| `NAMES` | 3.2.5 | **Implemented** — see "Channel Operations" above (requires a channel argument; a bare, argument-less global `NAMES` is not implemented) |
| `LIST` | 3.2.6 | **Implemented** — see "Channel Operations" above |
| `INVITE` | 3.2.7 | Recognized only — no invite-only channel concept in this release; paired with the `invite-only` (`i`) flag in the Full Channel Mode Catalog below, which is why both remain Reserved/Recognized-only together, not independently |
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
| `WHOIS` | 3.6.2 | **Implemented** — see "User Queries" above |
| `WHOWAS` | 3.6.3 | Recognized only |
| `KILL` | 3.7.1 | Recognized only — no forced-disconnect admin command in this release (an administrator can approximate this via a future `EXTENSION`-adjacent command, but none exists yet) |
| `PING` | 3.7.2 | **Implemented** — see "Connection Keep-Alive" above |
| `PONG` | 3.7.3 | **Implemented** — see "Connection Keep-Alive" above |
| `ERROR` | 3.7.4 | **Implemented** — see "Connection Keep-Alive" above |
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
  `312`/`313`/`317`/`319` — the server/operator/idle/channel-list parts of
  a fuller `WHOIS` reply this release doesn't implement) are still defined
  in the full numeric catalog (irc-numeric-replies.md) for the same
  reason — a client library needs to be able to parse them from *any*
  server's responses, not only ones this server currently sends.
- This table does not change what any FR requires the server to *do* —
  it documents what the wire-protocol layer can *parse and represent*.
  Moving a "Recognized only" command to "Implemented" in a future release
  is a `jircd-core`/extension change, not a `jircd-protocol` change.

## Full Channel Mode Catalog (`jircd-protocol` — Wire-Protocol Recognition)

Unlike commands and numerics, an individual `MODE` flag isn't its own
grammar element — `jircd-protocol` parses any `<+/-letter>` generically,
so nothing here is about wire-level parsing. This table exists for the
reason FR-043 requires the `MODE` mechanism to be extension-friendly
(research.md "Channel/user mode extensibility"): it gives a future core
release or `ServerExtension` a canonical `id`/`kind` to align a new flag
with, instead of each independently reinventing one for the same RFC
2811 concept — and it makes explicit which flags this release's
`Channel.activeModes: Set<ChannelMode>` (data-model.md) shape can even
represent.

| Flag | RFC 2811 §4 | `id` | `kind` | `gates` | Status |
|---|---|---|---|---|---|
| `n` | 4.2.5 | `members-only` | `BOOLEAN` | `SEND` | **Implemented** (`CORE`, FR-013) |
| `m` | 4.2.6 | `moderated` | `BOOLEAN` | `SEND` | **Implemented** (`CORE`, FR-013) |
| `p` | 4.2.1 | `private` | `BOOLEAN` | `DISCOVER` | **Implemented** (`CORE`, FR-047) — see "Moderation" above; treated identically to `secret` in this release (mutually exclusive with it, data-model.md `Channel` validation rules), not the softer "listed but obscured" variant some historical networks gave `private` alone |
| `s` | 4.2.2 | `secret` | `BOOLEAN` | `DISCOVER` | **Implemented** (`CORE`, FR-047) — see "Moderation" above |
| `i` | 4.2.3 | `invite-only` | `BOOLEAN` | `JOIN` | Reserved — structurally representable today (a `BOOLEAN` flag gating `JOIN`, data-model.md `Channel` validation rules), but not implemented: no extension defines it, and its accompanying `INVITE` command (below) is itself still Recognized-only |
| `t` | 4.2.4 | `topic-lock` | `BOOLEAN` | — | Reserved — this release's topic-setting restriction (FR-040) is core and unconditionally operator-only, not toggleable the way real `+t` is, and isn't `ChannelMode`-driven at all; see note below |
| `l` | 4.2.7 | `user-limit` | `VALUE` | `JOIN` | Reserved — not representable by `Channel.activeModes` in this release (data-model.md `ChannelMode` validation rules) |
| `k` | 4.2.9 | `channel-key` | `VALUE` | `JOIN` | Reserved — same limitation as `user-limit` |
| `b` | 4.2.8 | `ban-mask` | `LIST` | `JOIN` | Reserved — same limitation, plus list storage |
| `o` | 4.1 | `operator` | `MEMBER` | — | **Implemented** (`CORE`, FR-013/FR-046) — see "Moderation" above; state lives in `Channel.operators` (data-model.md), not `activeModes`; first-join-gets-operator (FR-013) is the only path to a channel's *first* operator, `MODE +o`/`-o` (FR-046) is how it spreads afterward |
| `v` | 4.1 | `voice` | `MEMBER` | — | **Implemented** (`CORE`, FR-045) — see "Moderation" above; state lives in `Channel.voiced` (data-model.md), not `activeModes` |

**Contract notes**:
- Every flag above is `Status: Reserved` except the six this release
  implements — "Reserved" here means the identical thing it means in the
  Full Numeric Catalog (irc-numeric-replies.md): a canonical `id` exists
  for future use, but nothing in this release emits or enforces it.
- `private`/`secret`'s `DISCOVER` gate validates that `ChannelMode.gates`
  generalizes beyond a simple permit/deny of an action: its failure mode
  is deliberately "respond as if the channel doesn't exist," not a
  distinguishable error, for the reason FR-047 states (research.md
  "Channel/user mode extensibility"). `moderated`/`members-only`'s `SEND`
  gate and `private`/`secret`'s `DISCOVER` gate now cover this release's
  two different failure-mode conventions; a future `JOIN`-gating flag
  (e.g., invite-only) would need its own explicit error, distinct from
  both — its failure is a permission problem the requester should be
  told about, not something to hide.
- The `gates` column validates FR-043's extensibility promise against a
  concrete future case: `invite-only` gates `JOIN`, not `SEND` like this
  release's two implemented flags — a deliberately different case,
  chosen to confirm the mechanism generalizes rather than only working
  for the one action already built (research.md "Channel/user mode
  extensibility", "Validating the extensibility promise against a future
  `JOIN`-gating flag"). A future extension implementing `invite-only`
  would define a `ChannelMode` with `gates: {JOIN}` and register its own
  `JOIN`-attempt hook — no change to `JoinCommandHandler`'s own code, the
  same way no `MessageCommandHandler` change was needed for `voice`
  (FR-045) to start participating in `moderated`'s `SEND` check. It would
  also typically claim the wire-recognized-but-unimplemented `INVITE`
  command (3.2.7, Full Command Catalog above) via the same
  extension-registers-its-own-command
  mechanism `admin`/`cloak` already use (research.md "Extension system")
  — nothing new required there either.
- `operator` and `voice` share `kind: MEMBER` and, as of FR-046, both
  have a `MODE`-based mutator, following the identical pattern: resolve
  the target to a current member (`441` if not), operator-gate the
  request (`482` if not), then add/remove from the relevant dedicated
  `Channel` field. `operator` additionally has the first-join path
  `voice` never had — it's the only way a channel's very first operator
  is ever established, since `MODE +o` always requires an existing
  operator to issue it.
- `topic-lock` deserves a specific callout: real IRC's `+t` is a
  *toggleable* flag (some channels run with `-t`, letting anyone set the
  topic), but this project's FR-040 hardcodes operator-only topic-setting
  unconditionally. Implementing real `+t` later means FR-040 itself would
  need revisiting, not just adding a `ChannelMode` — a bigger change than
  the other reserved rows, flagged here so it isn't mistaken for a
  same-effort addition.
- `user-limit`, `channel-key`, and `ban-mask` are exactly the "client
  limit" and "invite-only"-adjacent modes real IRC clients most commonly
  expect; cataloging them here without implementing them keeps this
  release's `MODE` behavior honest (FR-043's `472` on anything else)
  while giving a future extension author a name to converge on instead of
  inventing `channel-key` vs. `chan-password` vs. `key` independently.

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
