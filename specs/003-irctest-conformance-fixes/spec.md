# Feature Specification: irctest Conformance Fixes

**Feature Branch**: `003-irctest-conformance-fixes`

**Created**: 2026-08-20

**Status**: Draft

**Input**: User description: "Fix conformance findings surfaced by running the irctest suite
against jircd-server: QUIT missing an ERROR acknowledgment, an empty USER realname being
accepted instead of rejected, NAMES' channel-visibility symbol never reflecting
private/secret status, LUSERS' reply text not matching the conventional parseable shape, and
WHOWAS using the wrong numeric for a missing nickname argument — plus three deliberate
existing design decisions (WHO/WHOIS omitting the server-name field, NAMES/LIST/TOPIC not
distinguishing a hidden channel from a nonexistent one, and UTF8ONLY being advertised while
nicknames stay ASCII-only) that a stricter/RFC-conformant client disagrees with and need an
explicit decision before changing."

This feature is a correctness follow-up to `001-ircv3-server` and `002-extended-irc-commands`,
found by running the third-party [irctest](https://github.com/progval/irctest) conformance
suite (via a custom controller in `github.com/jircd/irctest`) against a real running
`jircd-server` instance. It does not add new commands or capabilities — every item here
corrects how an already-implemented command replies. Everything irctest also flagged that
those two features already scoped out deliberately and documented (`+l`/`+k` channel modes,
`metadata`, `chathistory`/`multiline`/`relaymsg`/`roleplay`/bouncer features, SASL/accounts,
`WHOWAS`'s count parameter, Ergo-specific extensions) is explicitly **not** revisited here.

## Clarifications

### Session 2026-08-20

- Q: Should `WHO`/`WHOIS` replies restore the RFC 2812 server-name field they currently omit, even though the value would always just repeat this server's own name (there's no federation, so no other server name could ever appear there)? → A: Restore it, always populated with this server's own name.
- Q: Should `NAMES`/`LIST`/`TOPIC` start returning an empty success (`366`) for a channel that was never created, distinguishing it from a `private`/`secret` channel a non-member can't see (currently both return the identical `403`)? → A: Keep unified — no change; the original FR-047 privacy guarantee (a non-member cannot tell whether a private/secret channel exists) stands.
- Q: Should nicknames become UTF-8-legal to match the already-advertised `UTF8ONLY` token, given nickname uniqueness currently uses ASCII-only rfc1459 casemapping? → A: Keep ASCII-only — no change; `UTF8ONLY` continues to mean message content/topics/realnames/channel names only.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Client Receives a Proper Disconnect Acknowledgment (Priority: P1)

A client sends `QUIT` to disconnect voluntarily and expects the server to acknowledge the
disconnect the same standard way every other IRC server does, before the connection closes.

**Why this priority**: `QUIT` is the single most common way a client ever disconnects — every
other disconnect path this server has (`KILL`, keep-alive timeout, queue overflow) already
sends this acknowledgment; `QUIT` being the one exception is both the most-hit gap and the
easiest to fix, since the pattern already exists elsewhere in this codebase.

**Independent Test**: A registered client sends `QUIT`; verify an `ERROR` line arrives before
the connection closes — independent of every other story in this feature.

**Acceptance Scenarios**:

1. **Given** a registered client, **When** it sends `QUIT :goodbye`, **Then** it receives an
   `ERROR` line (carrying its quit reason or a description of it) before the connection
   closes.
2. **Given** a registered client, **When** it sends bare `QUIT` (no reason), **Then** it still
   receives an `ERROR` line before the connection closes, using the same default reason
   already used elsewhere.

---

### User Story 2 - Registration Rejects an Empty Real Name (Priority: P1)

A client attempts to register with an empty real name field and expects the server to reject
this the same way it already rejects other missing-parameter cases, rather than silently
accepting an empty identity field.

**Why this priority**: A correctness gap in the connection-registration path — the single
most exercised part of the protocol — with a well-established, unambiguous expected behavior
(referenced directly by the current IRC specification maintainers).

**Independent Test**: Send `USER user 0 * :` (empty trailing real name) before `NICK`/after
`NICK`; verify registration is rejected with the standard missing-parameter error —
independent of every other story in this feature.

**Acceptance Scenarios**:

1. **Given** a session that has claimed a nickname, **When** it sends `USER user 0 * :` with
   an empty real name, **Then** the server rejects it with the standard missing-parameter
   error instead of completing registration.

---

### User Story 3 - Channel Listings Reveal a Channel's Actual Privacy Status (Priority: P1)

A client viewing a channel's member listing wants the listing's own visibility marker to
honestly reflect whether that channel is `private`, `secret`, or neither — not always claim
"public" regardless of the channel's actual mode.

**Why this priority**: `private`/`secret` channel modes are already fully implemented and
enforced (`001-ircv3-server` FR-047) — only this one display detail is wrong, and it's
visible on every single channel-membership listing a client ever requests.

**Independent Test**: Set a channel `secret`, then request its member listing; verify the
listing's visibility marker reflects `secret` rather than always showing the public marker —
independent of every other story in this feature.

**Acceptance Scenarios**:

1. **Given** a channel with no `private`/`secret` mode active, **When** a member requests its
   membership listing, **Then** the listing's visibility marker indicates "public."
2. **Given** a channel with `secret` active, **When** a member requests its membership
   listing, **Then** the listing's visibility marker indicates "secret," distinct from both
   "public" and "private."
3. **Given** a channel with `private` active, **When** a member requests its membership
   listing, **Then** the listing's visibility marker indicates "private," distinct from both
   "public" and "secret."

---

### User Story 4 - Server Statistics Reply Is Machine-Parseable (Priority: P2)

A client or tool that queries the server's connected-user count wants the reply text in the
conventional, widely-recognized shape every other server uses, so automated parsers (and
users skimming the line) can actually read the count out of it reliably.

**Why this priority**: A cosmetic-seeming but real interoperability gap — the reply is
correct in spirit (this server's own scoped-down user-count answer, `002-extended-irc-commands`
FR-003) but its wording doesn't match the shape client tooling generally expects, which
defeats the purpose of returning a countable number at all.

**Independent Test**: Query the server's connected-user count; verify the reply text follows
the conventional shape — independent of every other story in this feature.

**Acceptance Scenarios**:

1. **Given** a running server with a known number of connected clients, **When** a registered
   client queries the connected-user count, **Then** the reply text follows the same
   conventional "there are N users ... on ... servers" shape every other server's equivalent
   reply uses, with this server's own count filled in.

---

### User Story 5 - Last-Known-Identity Lookup Uses the Precise Missing-Argument Error (Priority: P3)

A client that queries the last-known-identity lookup command with no nickname argument at
all wants the specific "you didn't give a nickname" error, not the generic
missing-parameters error every other command uses.

**Why this priority**: Lowest priority — a single numeric-reply substitution with no
behavioral change beyond which specific standard error code is returned.

**Independent Test**: Send the bare lookup command with no nickname argument; verify the
specific missing-nickname error is returned — independent of every other story in this
feature.

**Acceptance Scenarios**:

1. **Given** a registered client, **When** it sends the last-known-identity lookup command
   with no nickname argument, **Then** the server replies with the specific
   "no nickname given" error rather than the generic missing-parameters error.

---

### User Story 6 - User and Channel Lookups Include a Server-Name Field (Priority: P3)

A client or tool relying on the full RFC 2812 field layout for user/channel lookup replies
wants every documented field present, including the server-name field this server currently
omits (`001-ircv3-server` FR-021 originally omitted it since there's no federation concept),
so strict positional parsing doesn't break.

**Why this priority**: Lowest priority — a wire-format compatibility improvement for strict
tooling, not a behavioral gap. Per Clarifications, the field is restored, always populated
with this server's own name (the only value it could ever hold, absent federation).

**Independent Test**: Query a connected user's or channel's information; verify the reply
includes a server-name field populated with this server's own name — independent of every
other story in this feature.

**Acceptance Scenarios**:

1. **Given** a registered client, **When** it looks up another connected user's full
   information, **Then** the reply includes a field carrying this server's own name, in the
   position RFC 2812 defines for it.
2. **Given** a registered client, **When** it looks up a channel's membership (the form that
   already reports per-member details), **Then** each reported member's entry includes a
   field carrying this server's own name, in the position RFC 2812 defines for it.

---

### User Story 7 - Channel Listings Keep Hidden Channels Indistinguishable From Nonexistent Ones (Priority: P3)

A client querying a channel that was never created currently gets the exact same response
as querying a `private`/`secret` channel it can't see (`001-ircv3-server` FR-047's
deliberate privacy choice) — this feature confirms that behavior is intentional and keeps
it, rather than letting a stricter/RFC-conformant client's differing expectation quietly
erode it.

**Why this priority**: Lowest priority — per Clarifications, no behavior changes here; this
story exists to record that the finding was considered and explicitly not acted on, so it
isn't mistaken for an overlooked gap in a future review.

**Independent Test**: Query a channel name that was never created; verify the response is
identical in kind to a `private`/`secret` channel's non-member response — independent of
every other story in this feature.

**Acceptance Scenarios**:

1. **Given** a channel name that has never been created, **When** a registered client
   queries it via `NAMES`, `LIST`, or `TOPIC`, **Then** the response is the same
   `403`-class "as if it doesn't exist" reply a `private`/`secret` channel already produces
   for a non-member — unchanged from current behavior.

---

### User Story 8 - Nicknames Remain ASCII-Only (Priority: P3)

A client wants to register a nickname containing non-ASCII UTF-8 characters, since this
server already advertises the `UTF8ONLY` token — this feature confirms that token's scope
is intentionally limited to human-readable content and keeps nicknames ASCII-only, rather
than letting a stricter/RFC-conformant client's differing expectation quietly change it.

**Why this priority**: Lowest priority — per Clarifications, no behavior changes here; this
story exists to record that the finding was considered and explicitly not acted on.

**Independent Test**: Attempt to register a nickname containing valid UTF-8 non-ASCII
characters; verify it is still rejected under the existing RFC 2812 §2.3.1 grammar —
independent of every other story in this feature.

**Acceptance Scenarios**:

1. **Given** an unregistered connection, **When** it attempts to claim a nickname containing
   valid UTF-8 non-ASCII characters, **Then** the server rejects it under the existing
   ASCII-only nickname grammar — unchanged from current behavior.

---

### Edge Cases

- What happens when a client's `QUIT` and the server's own concurrent decision to disconnect
  that same client (e.g. a keep-alive timeout firing at nearly the same moment) race? Only
  one `ERROR` line is ever sent — whichever cause is processed first wins; this is an
  existing property of the single disconnect-cleanup path every cause already funnels
  through, not something this feature changes.
- What happens to an already-connected client's existing NAMES/WHO output if a channel's
  `private`/`secret` mode changes while a listing is in flight? Not a new concern this
  feature introduces — listings are generated fresh per request, same as every other
  channel-state query already behaves.
- What happens when `LUSERS`' reply text is requested on a server with zero connected users
  (the requester's own connection notwithstanding, since a query requires a registered
  session)? The count is never zero in practice — the requester itself is always at least
  one connected user — so no special zero-case wording is needed.

## Requirements *(mandatory)*

### Functional Requirements

**Disconnect Acknowledgment**

- **FR-001**: A client-initiated `QUIT` MUST result in an `ERROR` message sent to that
  client before its connection closes, the same acknowledgment pattern this server's other
  disconnect paths (administrator-forced disconnect, keep-alive timeout) already use.

**Registration Validation**

- **FR-002**: A `USER` command whose real name parameter is empty MUST be rejected with the
  same missing-parameters error already used for a `USER` command with too few parameters,
  rather than completing registration.

**Channel Listing Accuracy**

- **FR-003**: A channel membership listing's visibility marker MUST indicate "public,"
  "private," or "secret" to match that channel's actual currently-active mode
  (`001-ircv3-server` FR-047), never unconditionally "public."

**Server Statistics Wording**

- **FR-004**: The connected-user-count reply's text MUST follow the conventional
  "there are N users [...] on [...] servers"-shaped sentence already used across IRC server
  implementations generally, continuing to report only the server-wide totals
  `002-extended-irc-commands` FR-003 already scoped this command to (no new counts are
  tracked; only the wording of the existing count changes).

**Last-Known-Identity Lookup Error Precision**

- **FR-005**: The last-known-identity lookup command, when sent with no nickname argument at
  all, MUST reply with the specific "no nickname given" error rather than the generic
  missing-parameters error.

**Lookup Reply Field Completeness**

- **FR-006**: A user information lookup's reply MUST include a field carrying this server's
  own name, in the position RFC 2812 defines for the server-name field, matching the same
  value already shown elsewhere (e.g. the registration completion burst).
- **FR-007**: A channel-membership lookup's per-member reply lines MUST include the same
  server-name field described in FR-006.

**Channel Existence vs. Visibility (Explicitly Unchanged)**

- **FR-008**: `NAMES`/`LIST`/`TOPIC` on a channel that does not exist MUST continue to
  produce the same `403`-class response a `private`/`secret` channel already produces for a
  non-member — indistinguishable, preserving the existing `001-ircv3-server` FR-047 privacy
  guarantee. This feature makes no change here (Clarifications); recorded so the finding
  isn't mistaken for an unaddressed gap later.

**Nickname Character Set (Explicitly Unchanged)**

- **FR-009**: Nicknames MUST remain governed by the existing RFC 2812 §2.3.1 ASCII-only
  grammar. `UTF8ONLY` continues to apply only to human-readable content (message bodies,
  topics, realnames, channel names), not identifiers. This feature makes no change here
  (Clarifications); recorded for the same reason as FR-008.

### Key Entities

This feature introduces no new entities — every change here is to the reply content an
already-implemented command produces, using state (`ClientSession`, `Channel`,
`ChannelMode`) `001-ircv3-server`/`002-extended-irc-commands` already model.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A client disconnecting voluntarily via `QUIT` always sees a closing
  acknowledgment before the connection ends, with no change to how quickly the disconnect
  itself takes effect.
- **SC-002**: An attempt to register with an empty real name is rejected 100% of the time,
  with no change to the acceptance rate of registrations that supply a non-empty one.
- **SC-003**: A channel listing's visibility marker matches that channel's actual mode in
  100% of cases across all three visibility states (public, private, secret).
- **SC-004**: A general-purpose IRC client-tooling library's standard reply parser can
  extract this server's connected-user count from the relevant reply without reporting a
  parse failure.
- **SC-005**: Running the irctest conformance suite's tests corresponding to the six
  findings this feature changes behavior for (FR-001 through FR-007) shows all of them
  passing, with no regression in any previously-passing test. The two findings this feature
  explicitly declines to change (FR-008, FR-009) continue to show their existing,
  already-expected irctest results.

## Assumptions

- "The same missing-parameters error" (FR-002, FR-005's contrast case) and "the specific
  no-nickname-given error" refer to the two distinct standard IRC error replies already
  used elsewhere in this project for, respectively, a command sent with too few parameters
  in general, and a command specifically requiring a nickname that was not given at all —
  this feature reuses both exactly as already defined, introducing no new error types.
  "The same acknowledgment pattern" (FR-001) and "the same missing-parameters error"
  (FR-002) likewise reuse exactly what `001-ircv3-server`/`002-extended-irc-commands`
  already defined for their respective existing use cases — no new reply formats are
  introduced beyond FR-003's three-way visibility marker and FR-004's reworded count
  sentence, both already scoped above.
- FR-004's exact reply wording is a planning-phase detail (the precise sentence used by
  comparable, widely-deployed IRC servers), not a product decision requiring further
  stakeholder input — it only needs to (a) be conventional enough for standard parsers to
  extract a count from and (b) continue reporting only the totals already in scope.
- None of these fixes changes any previously-passing behavior from `001-ircv3-server` or
  `002-extended-irc-commands` — FR-001 through FR-007 are additive precision to a reply's
  content, not a change to when or to whom a reply is sent; FR-008 and FR-009 make no
  change at all, per Clarifications.
