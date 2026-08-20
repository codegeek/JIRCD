# Feature Specification: Extended IRC Commands

**Feature Branch**: `002-extended-irc-commands`

**Created**: 2026-08-19

**Status**: Draft

**Input**: User description: "Add spec coverage for seven IRC commands currently marked
'Recognized only' (no handler) in the 001-ircv3-server feature's command catalog, approved
for implementation: VERSION, TIME, LUSERS (server information queries), AWAY (presence
status), KILL (administrator-forced disconnect), WHOWAS (last-known identity lookup after
disconnect), and TAGMSG (tag-only messages)."

This feature extends the already-implemented `001-ircv3-server` feature. It does not modify
any behavior that feature already delivers — it adds handlers for seven commands that
feature's own command catalog (`specs/001-ircv3-server/contracts/irc-protocol-commands.md`)
deliberately left "Recognized only" (parsed but rejected with `421 ERR_UNKNOWNCOMMAND`).
This supersedes `001-ircv3-server`'s Clarifications note that presence/away status was
"deferred to a later iteration" — that iteration is this feature, for `AWAY` specifically;
every other command that note or the command catalog deferred (SASL beyond the Story 3
minimum, `account-notify`, `batch`, `chathistory`, and every command not named above) remains
out of scope.

## Clarifications

### Session 2026-08-19

- Q: Should the server's `VERSION` reply also include a fresh `RPL_ISUPPORT` (`005`) burst, the way some real-world IRC servers do, or just the plain version reply on its own? → A: Yes — `VERSION` MUST be followed by a full `RPL_ISUPPORT` burst, reusing the same server-limits snapshot the registration completion burst already sends, letting a client re-learn current server limits mid-session (e.g. after an administrator's `REHASH` changed them) without reconnecting.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Query Server Information (Priority: P1)

A connecting client wants to know what server it's talking to, what time the server thinks
it is, and how many people/channels are currently active — the same low-risk introspection
queries most real-world IRC clients issue automatically on connect or on user request, and
which many clients treat a missing reply to as a compatibility quirk rather than a hard
failure.

**Why this priority**: Cheapest possible win — every value these commands report already
exists in server state (the version resource `001-ircv3-server` already generates, the
system clock, the existing session/channel registries). No new state, no privilege model,
no interaction with any other feature area. Implementing this closes the most visible gap
between this server and client expectations for the least implementation risk.

**Independent Test**: Connect a registered client and send `VERSION`, `TIME`, and `LUSERS`
independently of any other feature in this spec — each returns a complete, correct reply on
its own with no dependency on `AWAY`/`KILL`/`WHOWAS`/`TAGMSG` being implemented.

**Acceptance Scenarios**:

1. **Given** a registered client, **When** it sends `VERSION`, **Then** the server replies
   with its own name and version (the same values already shown at registration), followed by
   a fresh server-limits (`ISUPPORT`) burst.
2. **Given** a registered client, **When** it sends `TIME`, **Then** the server replies with
   its current local time in a human-readable form.
3. **Given** a registered client, **When** it sends `LUSERS`, **Then** the server replies
   with the current count of connected clients and active channels.

---

### User Story 2 - Set and See Away Status (Priority: P2)

A user who is stepping away from their client wants to let others know, without
disconnecting — and other users messaging that away user, or looking them up, want to see
that status.

**Why this priority**: A widely-used, user-facing presence feature with real client
demand — many clients set this automatically on idle. It's larger than Story 1 because it
touches session state that other commands (`PRIVMSG`, `WHOIS`, `WHO`) must now read, but the
read side was already designed for: `WHO`'s status field has reserved the `G` ("gone")
letter for exactly this since `001-ircv3-server` (currently always sent as `H`).

**Independent Test**: One client sets itself away with `AWAY :<reason>`, then a second client
either messages it or looks it up via `WHOIS`/`WHO` and observes the away indication — fully
testable without `KILL`, `WHOWAS`, or `TAGMSG` existing.

**Acceptance Scenarios**:

1. **Given** a registered client, **When** it sends `AWAY :<reason>`, **Then** the server
   confirms the away status is set and marks that session away.
2. **Given** an away client, **When** it sends `AWAY` with no parameter, **Then** the server
   confirms the away status is cleared.
3. **Given** an away client with a set reason, **When** another client sends it a `PRIVMSG`,
   **Then** the sender receives a reply carrying the away reason, in addition to normal
   delivery.
4. **Given** an away client, **When** another client sends `WHOIS` for it, **Then** the away
   reason is included in the response.
5. **Given** an away client that is a member of a channel, **When** another member of that
   channel sends `WHO` for the channel, **Then** that client's entry is marked with the away
   status letter instead of the normal "here" letter.

---

### User Story 3 - Administrator Forcibly Disconnects a Client (Priority: P2)

An administrator dealing with an abusive or malfunctioning client needs to disconnect it
immediately, without waiting for it to misbehave its way into a rate limit or ban.

**Why this priority**: Closes a real gap in the existing administrator toolkit
(`001-ircv3-server` Story 6 already ships `OPER`/`REHASH`/`SAJOIN`/`SAMODE`/`WHOHOST`, but
has no forced-disconnect capability at all) — but it's scoped below Story 1 because it's
narrower in audience (administrators only) and depends on the same privilege model Story 6
already established rather than introducing a new one.

**Independent Test**: An administrator session issues `KILL` against a second, ordinary
client's nickname; that client's connection is closed with the given reason. Fully testable
using only the existing `OPER` mechanism from `001-ircv3-server`, independent of Stories
1, 2, 4, and 5 in this spec.

**Acceptance Scenarios**:

1. **Given** an administrator session and a connected target client, **When** the
   administrator sends `KILL <nickname> :<reason>`, **Then** the target's connection is
   closed, carrying the given reason, and the administrator receives confirmation.
2. **Given** a non-administrator session, **When** it sends `KILL` against any nickname,
   **Then** the command is rejected and no disconnection occurs.
3. **Given** an administrator session, **When** it sends `KILL` naming a nickname that isn't
   currently connected, **Then** the server reports no such client and nothing is
   disconnected.

---

### User Story 4 - Look Up a Disconnected User's Last-Known Identity (Priority: P3)

A user trying to reach someone who just disconnected (voluntarily or via a `KILL`/timeout)
wants to see who that nickname most recently belonged to, without that person needing to
still be connected.

**Why this priority**: Real value, especially right after a `QUIT`/`KILL`/timeout, but
narrower in everyday use than Stories 1-3 and requires new state (a bounded history of
recently-disconnected identities) that nothing else in this feature depends on.

**Independent Test**: A client connects, registers under a nickname, and disconnects; a
second client then queries that nickname with `WHOWAS` and receives its last-known identity.
Testable independently of every other story in this spec.

**Acceptance Scenarios**:

1. **Given** a client that registered as `alice` and has since disconnected, **When** another
   client sends `WHOWAS alice`, **Then** the server replies with `alice`'s last-known ident,
   hostname, and real name.
2. **Given** a nickname that has never been used on this server, **When** a client sends
   `WHOWAS` for it, **Then** the server reports no history found.
3. **Given** a nickname reused by multiple different clients over time, **When** a client
   sends `WHOWAS` for it, **Then** the server returns the most recent disconnection's
   identity, not an older one.

---

### User Story 5 - Send Metadata-Only Messages (Priority: P3)

A client using the already-implemented `message-tags` capability wants to send a signal to a
channel or user (e.g., a "typing" indicator or similar client-defined metadata) without that
signal appearing as a visible chat message to clients that don't understand it.

**Why this priority**: Lowest priority — it's a protocol-completeness extension of a
capability (`message-tags`) that's already fully implemented, valuable mainly to
capability-aware clients, with no effect on plain-text IRC usage at all.

**Independent Test**: A client with `message-tags` negotiated sends `TAGMSG` with a tag to a
channel it's a member of; other members with `message-tags` negotiated receive it as a
tag-only message, independent of every other story in this spec.

**Acceptance Scenarios**:

1. **Given** a registered client that is a channel member, **When** it sends `TAGMSG` with
   tags targeting that channel, **Then** other members of the channel receive a tag-only
   message carrying the same tags.
2. **Given** a registered client, **When** it sends `TAGMSG` targeting another connected
   client's nickname, **Then** that client receives a tag-only message carrying the same
   tags.
3. **Given** a `TAGMSG` sent with no tags at all, **When** the server processes it, **Then**
   it is rejected the same way a `PRIVMSG`/`NOTICE` with a missing required parameter would
   be — a `TAGMSG` with nothing to carry has no purpose.

### Edge Cases

- What happens when a client sends `VERSION`/`TIME`/`LUSERS` before completing registration?
  Same as every other core query command in `001-ircv3-server` (`WHOIS`/`WHO`/`LIST`): it
  requires a registered session.
- What happens when an administrator `KILL`s their own nickname? Permitted — treated
  identically to killing any other connected client, including themselves.
- What happens when a client sends `AWAY` while already away, with a new reason? The reason
  is replaced; the server confirms as if setting away for the first time.
- What happens to a client's away status across a `NICK` change? It persists — away status is
  a property of the session, not the nickname.
- What happens to `WHOWAS` history when the same nickname disconnects and reconnects
  multiple times in quick succession? Each disconnection adds its own entry; `WHOWAS`
  without a count returns only the most recent.
- What happens when `TAGMSG` targets a channel the sender isn't a member of, or a nickname
  that isn't connected? Same rejection as the equivalent `PRIVMSG`/`NOTICE` case.
- What happens when a recipient of a `TAGMSG` has not negotiated `message-tags`? They don't
  receive it at all — a tag-only message has nothing left to show a client that can't parse
  tags.

## Requirements *(mandatory)*

### Functional Requirements

**Server Information Queries**

- **FR-001**: The server MUST respond to a `VERSION` command from a registered session with
  its own name and version — the same values already shown in the registration completion
  burst (`001-ircv3-server` FR-050/FR-051) — followed by a fresh `RPL_ISUPPORT` burst reusing
  the same server-limits snapshot the registration completion burst already sends
  (`001-ircv3-server` FR-055), so a client can re-learn current server limits mid-session
  (e.g. after an administrator's `REHASH` changed them) without reconnecting.
- **FR-002**: The server MUST respond to a `TIME` command from a registered session with its
  current local time in human-readable form.
- **FR-003**: The server MUST respond to a `LUSERS` command from a registered session with
  the current count of connected clients and the current count of active channels.

**Presence (Away Status)**

- **FR-004**: A registered session MUST be able to mark itself away by sending `AWAY` with a
  reason; the server MUST confirm this to the sender.
- **FR-005**: An away session MUST be able to clear its away status by sending `AWAY` with no
  parameter; the server MUST confirm this to the sender.
- **FR-006**: Sending `AWAY` with a reason while already away MUST replace the previous
  reason, confirmed the same way as setting it for the first time (FR-004).
- **FR-007**: When a client sends `PRIVMSG` or `NOTICE` to a session that is currently away,
  the server MUST additionally inform the sender of the target's away reason, without
  altering normal message delivery (`001-ircv3-server` FR-004/FR-005).
- **FR-008**: `WHOIS` output for a target that is currently away MUST include that target's
  away reason (`001-ircv3-server` FR-037).
- **FR-009**: `WHO` output for a match that is currently away MUST use the away status
  indicator instead of the "here" indicator that every other match uses
  (`001-ircv3-server` FR-061).
- **FR-010**: Away status MUST be a property of the connected session, persisting across a
  `NICK` change and cleared only by an explicit `AWAY` (FR-005) or disconnection.

**Administrator-Forced Disconnect**

- **FR-011**: A session holding administrator privilege MUST be able to forcibly disconnect
  any other connected client by nickname via `KILL`, optionally supplying a reason.
- **FR-012**: A `KILL` MUST be rejected for a session that does not hold administrator
  privilege, following the same privilege-check pattern as every other administrator command
  in `001-ircv3-server` (FR-034).
- **FR-013**: A `KILL` naming a nickname with no currently-connected session MUST be rejected
  as "no such nickname," the same as any other command targeting a nonexistent nickname.
- **FR-014**: A successful `KILL` MUST disconnect the target through the same cleanup path
  every other disconnection uses (`001-ircv3-server` FR-017), carrying a reason distinguishable
  from an ordinary `QUIT` or keep-alive timeout, so channels the target was in can tell it was
  administrator-initiated.
- **FR-015**: An administrator MUST be able to `KILL` their own nickname; this is not treated
  differently from killing any other connected client.

**Last-Known Identity Lookup**

- **FR-016**: The server MUST retain a bounded history of recently-disconnected sessions'
  last-known nickname, ident, hostname, and real name, regardless of how the session ended
  (voluntary `QUIT`, `KILL`, or keep-alive timeout).
- **FR-017**: A registered session MUST be able to query that history by nickname via
  `WHOWAS`, receiving the most recent entry for that nickname if one exists.
- **FR-018**: A `WHOWAS` query for a nickname with no retained history MUST be rejected as
  "no such nickname," distinguishable from a nickname that is merely not currently connected.
- **FR-019**: When the retained-history bound is reached, the oldest entry MUST be discarded
  to make room for the newest — this is a bounded cache of recent activity, not a permanent
  log.

**Tag-Only Messages**

- **FR-020**: A registered session that has negotiated `message-tags`
  (`001-ircv3-server` FR-025) MUST be able to send a `TAGMSG` carrying one or more tags to a
  channel it is a member of or to another connected client's nickname.
- **FR-021**: A `TAGMSG` MUST be delivered only to recipients that have themselves negotiated
  `message-tags` — a recipient without it receives nothing, since a tag-only message has no
  content for a client that cannot parse tags.
- **FR-022**: `TAGMSG` delivery MUST reuse the same targeting rules (channel membership,
  nickname existence, moderation gates) `PRIVMSG`/`NOTICE` already use
  (`001-ircv3-server` FR-004/FR-005/FR-062), except that there is no message body to validate.
- **FR-023**: A `TAGMSG` carrying no tags at all MUST be rejected, the same way a
  `PRIVMSG`/`NOTICE` missing a required parameter is rejected.

### Key Entities

- **Away Status**: A per-session flag plus an optional free-text reason, set/cleared by
  `AWAY`, read by message delivery, `WHOIS`, and `WHO`. Belongs to the connected session, not
  the nickname.
- **WHOWAS Entry**: A retained snapshot of one disconnected session's last-known nickname,
  ident, hostname, and real name, plus enough ordering information to determine "most
  recent" when a nickname has multiple entries. Held in a bounded, global (not per-nickname)
  history independent of any currently-connected session.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A standard IRC client connecting to this server can successfully query
  `VERSION`, `TIME`, and `LUSERS` and receive a complete reply to each, with no client-side
  timeout or fallback behavior triggered.
- **SC-002**: A user can mark themselves away and have that status correctly visible to
  another user through at least three independent channels — direct messaging, `WHOIS`, and
  `WHO` — without needing to reconnect or take any further action.
- **SC-003**: An administrator can disconnect a misbehaving client in a single command, with
  the disconnection taking effect immediately (no observable delay beyond ordinary network
  latency) and other channel members able to see it was administrator-initiated.
- **SC-004**: A user who just missed someone that disconnected can recover that person's
  identity information via a single lookup command, without needing server-side log access.
- **SC-005**: Two clients that both support the `message-tags` capability can exchange a
  metadata-only signal that is completely invisible to a third client in the same channel
  that does not support tag-only messages.

## Assumptions

- `VERSION`/`TIME`/`LUSERS` require a registered session, matching every other core query
  command's precondition in `001-ircv3-server` (`WHOIS`/`WHO`/`LIST`/`NAMES`); none of the
  three needs to be usable pre-registration since no client relies on them before completing
  the connection handshake.
- `LUSERS`'s counts are server-wide totals only (total connected clients, total active
  channels) — the fuller multi-line breakdown some real networks send (operators online,
  unknown connections, per-server figures) doesn't apply to a standalone, non-federated
  server (`001-ircv3-server` FR-021) and is out of scope.
- Away status has no server-side idle-timer or auto-away behavior — it is set and cleared
  exclusively by explicit client action (`AWAY`), matching this server's existing preference
  for explicit, client-driven state over inferred behavior.
- `KILL` requires only administrator privilege, the same single gate every other
  administrator command in `001-ircv3-server` uses (FR-034) — there is no additional
  confirmation step or cooldown, consistent with `SAJOIN`/`SAMODE`/`WHOHOST` requiring no
  such step either.
- The `WHOWAS` history is a single global bounded store (not one bucket per nickname) with a
  reasonable default retention count, administrator-configurable the same way other
  numeric limits in this server are (`001-ircv3-server` FR-056's pattern) — exact default
  count is a planning-phase decision, not a product decision requiring stakeholder input.
- `TAGMSG` recipients follow the exact same visibility/delivery rules `PRIVMSG`/`NOTICE`
  already enforce (membership, bans, moderation) — no new authorization model is introduced
  for it.
- This feature introduces no new IRCv3 capability — `TAGMSG` rides on the already-negotiated
  `message-tags` capability from `001-ircv3-server` (FR-025); a client that never negotiated
  `message-tags` simply never sends or receives one.
