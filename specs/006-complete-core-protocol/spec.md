# Feature Specification: Complete Core Protocol Exclusions

**Feature Branch**: `006-complete-core-protocol`

**Created**: 2026-08-21

**Status**: Draft

**Input**: User description: "Complete a batch of core RFC1459/RFC2812 IRC protocol functionality
that prior features deliberately scoped out and documented as exclusions: channel modes
+l (user limit), +k (channel key), and +t (topic-lock); bare argument-less NAMES; RPL_LUSEROP and
RPL_LUSERME; INVITE to a not-yet-existing channel; and WHOWAS's optional count parameter."

This feature is a fourth correctness/completeness follow-up to `001-ircv3-server`/
`002-extended-irc-commands`/`003-irctest-conformance-fixes`/`005-fix-batch-conformance`
(`004-fix-tls-certificate` was an unrelated hardening fix). Every item here was previously
identified, named, and deliberately deferred as a documented scope exclusion in one of those
earlier features' own contracts or assumptions — this feature is where those deferred items get
picked up. It does not add any new IRCv3 capability or architectural subsystem — every item is
either a small, well-defined new channel-mode enforcement path alongside the seven core modes
already implemented, or a genuinely missing piece of an already-partially-implemented command.
Explicitly out of scope: IRCv3 capabilities never implemented at all (`multi-prefix`,
`userhost-in-names`, `labeled-response`, `batch`, `standard-replies`) — these are new capability
modules, not completions of existing commands; anything Ergo-specific (`+T` no-CTCP mode,
ban-mask case sensitivity, PRECIS non-ASCII nicknames); `metadata`/`chathistory`/`multiline`/
`relaymsg`/roleplay/bouncer; SASL/accounts; and `INFO`'s remote-server-target routing
(non-federated design, confirmed out of scope during `005-fix-batch-conformance`).

## Clarifications

### Session 2026-08-21

- Q: Should the bare (argument-less) `NAMES` command return the full membership of every visible
  channel unrestricted, or should something limit how much it can expose in one request? → A: Full
  output, no new limit — rely on the already-planned private/secret channel visibility filter
  (FR-010) and jircd's existing per-connection rate limiter (`RateLimitBucket`, already covering
  every command) as the abuse mitigation, matching RFC/most conformant servers' behavior; no new
  response-size cap or privilege gate.
- Q: When a channel is full (`+l`) or key-protected (`+k`), should an invited client be exempt
  from those restrictions the same way invited clients already bypass `+i` (invite-only), or must
  they still satisfy the limit/key even after being invited? → A: Invited clients bypass `+l` and
  `+k` too, consistent with the existing `+i` exemption — an invite is a targeted,
  operator-granted override of a channel's normal entry restrictions, not just its invite-only
  gate specifically.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Channel Operators Can Cap and Lock Down Who Can Join (Priority: P1)

A channel operator wants to limit how many members a channel can hold, and separately wants to
require a shared password before anyone can join at all — two of the most commonly relied-upon
channel-access controls in IRC, and both currently do nothing.

**Why this priority**: The highest-value gap in this batch — `+l` and `+k` are core,
universally-implemented access controls that every mainstream IRC server supports; their total
absence means a channel operator has no way to cap membership or gate entry with a shared secret
at all, a capability every comparable server already provides.

**Independent Test**: Set a membership limit on a channel, fill it to that limit, and verify the
next join attempt is rejected while joins from members already inside are unaffected; separately,
set a channel key and verify a join without the correct key is rejected while one with the correct
key succeeds — independent of every other story in this feature.

**Acceptance Scenarios**:

1. **Given** a channel operator, **When** they set a numeric membership limit on their channel,
   **Then** the channel accepts joins normally until membership reaches that limit.
2. **Given** a channel already at its set membership limit, **When** another client attempts to
   join, **Then** the join is rejected with the specific channel-is-full error.
3. **Given** a channel at its membership limit, **When** the limit is raised or removed,
   **Then** joins succeed again as soon as membership is no longer at or over the (new) limit.
4. **Given** a channel operator, **When** they set a key (password) on their channel,
   **Then** a subsequent join attempt with no key or an incorrect key is rejected with the
   specific bad-key error.
5. **Given** a channel with a key set, **When** a client joins supplying the correct key,
   **Then** the join succeeds normally.
6. **Given** a client holding a valid, pending invitation to a channel that is at its membership
   limit or key-protected, **When** that client joins, **Then** the join succeeds without a free
   slot or the correct key being required — the same exemption already granted for invite-only
   channels extends to both of these restrictions.

---

### User Story 2 - Topic-Change Privilege Follows the Topic-Lock Setting (Priority: P2)

A channel operator wants to control whether ordinary members can change the channel topic, by
toggling a single setting — the standard, universally-implemented way IRC channels manage this,
rather than topic-setting always being restricted to operators regardless of any setting.

**Why this priority**: A well-established, non-optional part of `TOPIC`'s own behavior on every
comparable server — currently this server behaves as if the lock were always on, which is a
narrower and less flexible behavior than the documented standard, though not a missing capability
category the way `+l`/`+k` are.

**Independent Test**: With the topic-lock setting off, verify an ordinary (non-operator) member
can change the topic; with it on, verify the same attempt is rejected — independent of every
other story in this feature.

**Acceptance Scenarios**:

1. **Given** a channel with the topic-lock setting off, **When** an ordinary member (not a
   channel operator) sets the topic, **Then** the change succeeds.
2. **Given** a channel with the topic-lock setting on, **When** an ordinary member attempts to
   set the topic, **Then** the change is rejected with the same operator-required error already
   used elsewhere for this condition.
3. **Given** a channel with the topic-lock setting on, **When** a channel operator sets the
   topic, **Then** the change succeeds regardless of the setting.

---

### User Story 3 - A Bare Membership Query Lists Every Channel a Client Can See (Priority: P2)

A client wants to see the current membership of every channel they can see at once, without
already knowing every channel name in advance — the standard behavior of the membership-query
command when given no specific channel to look up.

**Why this priority**: A core, non-optional form of an already-partially-implemented command
(the single-channel form already works correctly) — narrower in scope than `+l`/`+k`, but still a
gap in baseline command completeness rather than an edge case. This form can return a large
response on a server with many channels; per this feature's clarification, that's addressed by
the already-planned private/secret visibility filter (an operator can already opt a channel out of
bare enumeration) plus the server's existing per-connection rate limiter, not by a new
response-size cap or privilege restriction specific to this command.

**Independent Test**: As a client with no special visibility privileges, send the bare
membership-query command with no channel named, and verify the response lists every channel
visible to that client (and omits any private or secret channel the client isn't a member of) —
independent of every other story in this feature.

**Acceptance Scenarios**:

1. **Given** a client, **When** it sends the membership-query command with no channel specified,
   **Then** it receives the membership of every channel visible to it, followed by the query's
   closing reply.
2. **Given** a channel marked private or secret that the requesting client is not a member of,
   **When** that client sends the bare membership query, **Then** that channel's membership is
   omitted from the response, the same visibility rule already applied to the single-channel form
   of this query.

---

### User Story 4 - Server Statistics Report Operator Count and Always Close With a Summary Line (Priority: P3)

A client querying basic server statistics wants to see how many connected clients currently hold
operator status, and expects the statistics reply to always end with the standard "I have N
clients" summary line every comparable server sends, rather than stopping short of it.

**Why this priority**: Lowest priority in this batch — both gaps are additional lines within an
already-implemented, already-working reply, not missing functionality on their own.

**Independent Test**: Query basic server statistics and verify the reply includes an
operator-count line and always ends with the summary line, regardless of whether any operators
are currently connected — independent of every other story in this feature.

**Acceptance Scenarios**:

1. **Given** one or more currently-connected clients hold operator status, **When** a client
   queries basic server statistics, **Then** the reply includes a count of currently-connected
   operators.
2. **Given** any state, **When** a client queries basic server statistics, **Then** the reply
   always ends with the standard summary line reporting the total connected-client count.

---

### User Story 5 - Inviting Someone to a Channel That Doesn't Exist Yet Still Works (Priority: P3)

A client wants to invite another user to a channel that hasn't been created by anyone joining it
yet, and expects the invitation to go through the same way it would for an existing channel,
rather than being rejected outright for a reason that doesn't apply to a channel with no state to
violate in the first place.

**Why this priority**: Lowest priority — a narrow edge case of an already-working command,
affecting only the specific moment before a channel's first member has joined it.

**Independent Test**: With no channel of a given name currently existing anywhere on the server,
invite another connected client to that channel name and verify the invitation is accepted and
delivered — independent of every other story in this feature.

**Acceptance Scenarios**:

1. **Given** a channel name that does not currently exist on the server, **When** a client
   invites another connected client to it, **Then** the inviting client receives the standard
   invitation-sent confirmation and the invited client receives the invitation notification.
2. **Given** a channel that DOES exist, **When** a client who is not a member of it attempts to
   invite someone to it, **Then** the invitation continues to be rejected with the existing
   not-on-that-channel error — this story only changes the not-yet-existing case.

---

### User Story 6 - Looking Up a Former Nickname's History Can Request More Than the Latest Entry (Priority: P3)

A client looking up a nickname's most recent prior identity wants the option to request more than
just the single latest entry, when more history is available for that nickname — the documented,
optional part of this lookup command's grammar.

**Why this priority**: Lowest priority — a narrow enhancement to an already-working command's
optional grammar, useful only when more than one prior identity is retained for a given nickname.

**Independent Test**: Change nickname through more than one prior identity, then look up that
nickname's history requesting more than one entry, and verify more than one prior identity is
returned (bounded by how many are actually retained) — independent of every other story in this
feature.

**Acceptance Scenarios**:

1. **Given** a nickname with more than one retained prior identity, **When** a client looks it up
   requesting a specific positive count, **Then** up to that many of the most recent entries are
   returned, most recent first.
2. **Given** a nickname with more than one retained prior identity, **When** it's looked up with no
   count specified, **Then** every retained prior identity for that nickname is returned, most
   recent first — omitting the count is not the same as requesting a count of one.

---

### Edge Cases

- What happens if a channel operator sets `+l` to a value lower than the channel's current
  membership? Existing members are not removed — the limit only affects future join attempts.
- What happens if a channel operator sets `+k` while clients are already joining concurrently? A
  join already past its key check when the key changes is not retroactively rejected.
- What happens if an invited client's invitation is used to join a channel that is BOTH at its
  membership limit AND key-protected? The invitation exempts the join from both restrictions at
  once, not just whichever one is checked first (clarified 2026-08-21).
- What happens to `+t` and a topic that was set while the lock was off, once the lock is turned
  on? The topic itself is unaffected — only the privilege required to change it going forward.
- What happens if the bare membership query is sent by a client that is a member of a private or
  secret channel? That channel's membership IS included for that client, the same visibility rule
  the single-channel form already applies.
- What happens if a client sends the bare membership query repeatedly, or the server has many
  channels with large memberships? The existing per-connection rate limiter bounds request
  frequency the same way it already bounds every other command; there is no additional
  per-response size cap (clarified 2026-08-21) — an operator wanting a channel excluded from bare
  enumeration marks it private or secret, the same existing mechanism already available today.
- What happens if an invite is sent to a not-yet-existing channel and the channel is created (by
  someone else joining it) before the invited client accepts? The invitation stands; whether the
  invited client is exempt from that channel's invite-only mode on their eventual join follows the
  same existing invite-exemption behavior already implemented for the existing-channel case.
- What happens if the former-nickname lookup's count parameter is zero, negative, or simply
  omitted? Per RFC1459/RFC2812's own text for this parameter, all three mean "do a full search" —
  every retained prior identity for that nickname is returned. Only a positive count narrows the
  result.

## Requirements *(mandatory)*

### Functional Requirements

**Channel Capacity and Key Access Modes**

- **FR-001**: The server MUST allow a channel operator to set a numeric membership limit on a
  channel.
- **FR-002**: A join attempt on a channel at or over its set membership limit MUST be rejected
  with the specific channel-is-full error, UNLESS the joining client holds a valid, pending
  invitation to that channel (clarified 2026-08-21 — same exemption already granted for
  invite-only channels).
- **FR-003**: A channel's membership limit MUST be removable, and joins MUST succeed again once
  membership is under the (raised or removed) limit.
- **FR-004**: The server MUST allow a channel operator to set a key (password) on a channel.
- **FR-005**: A join attempt on a channel with a key set, supplying no key or an incorrect one,
  MUST be rejected with the specific bad-key error, UNLESS the joining client holds a valid,
  pending invitation to that channel (clarified 2026-08-21 — same exemption already granted for
  invite-only channels).
- **FR-006**: A join attempt supplying the channel's current, correct key MUST succeed.

**Topic-Lock Privilege**

- **FR-007**: The server MUST allow a channel operator to toggle a per-channel topic-lock
  setting.
- **FR-008**: When a channel's topic-lock setting is off, any member of that channel MUST be able
  to set its topic.
- **FR-009**: When a channel's topic-lock setting is on, only a channel operator MUST be able to
  set its topic — an ordinary member's attempt MUST be rejected with the same operator-required
  error already used elsewhere for this condition.

**Bare Membership Query**

- **FR-010**: A membership query sent with no channel specified MUST return the membership of
  every channel visible to the requesting client, applying the same private/secret visibility
  rule already used for the single-channel form of this query. No additional response-size limit
  or privilege restriction applies beyond that visibility rule and the server's existing
  per-connection rate limiting (clarified 2026-08-21).

**Server Statistics Completeness**

- **FR-011**: A basic server statistics query's reply MUST include a count of currently-connected
  clients holding operator status.
- **FR-012**: A basic server statistics query's reply MUST always end with the standard
  total-connected-client summary line, regardless of how many operators are connected.

**Invitation to a Not-Yet-Existing Channel**

- **FR-013**: Inviting a connected client to a channel name that does not currently exist
  anywhere on the server MUST succeed — the inviting client receives the standard
  invitation-sent confirmation and the invited client receives the invitation notification —
  distinct from, and MUST NOT be confused with, the existing case of an inviter who is not a
  member of a channel that DOES already exist, which continues to be rejected.

**Former-Nickname Lookup Count**

- **FR-014**: The former-nickname lookup command MUST accept an optional count parameter and, when
  given as a positive number, return up to that many of the most recently retained prior
  identities for the looked-up nickname, most recent first.
- **FR-015**: The former-nickname lookup command, when given no count parameter, or when given a
  count of zero or a negative number, MUST return every retained prior identity for that nickname
  (the RFC's own "non-positive — including omitted — means full search" rule; verified against
  irctest's own non-deprecated conformance test for this exact case, which corrected this
  feature's own initial, narrower assumption that an omitted count should keep returning only one
  entry).

### Key Entities

- **Channel** (already modeled in `001-ircv3-server`): gains two new pieces of per-channel state —
  a membership limit and a key — alongside its already-tracked mode flags.
- **WhowasHistory** (already modeled in `002-extended-irc-commands`): FR-014 depends on this
  already retaining more than a single most-recent entry per nickname; if it currently retains
  only one, this feature's planning phase MUST determine the retained-history bound before
  FR-014 can return more than one entry regardless of command-level changes.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A channel operator can cap channel membership and gate joining behind a shared key
  using only standard channel-mode commands, with no workaround needed, 100% of the time.
- **SC-002**: Whether an ordinary member can change a channel's topic always matches that
  channel's current topic-lock setting, with no exceptions.
- **SC-003**: A client querying channel membership with no channel named always receives the
  membership of every channel it's entitled to see, in one request, with no need to already know
  every channel name in advance.
- **SC-004**: Running the irctest conformance suite's tests corresponding to the seven items this
  feature addresses shows all of them passing (or, for the invitation item, the current behavior
  reconfirmed as intentional if the relevant test is itself deprecated with no current
  expectation — see Assumptions), with no regression in any previously-passing test.
- **SC-005**: Inviting a client to a not-yet-existing channel succeeds on the first attempt, with
  no need to first have someone else create the channel.

## Assumptions

- Every fix here reuses an already-established pattern elsewhere in this codebase where one
  exists (e.g. `+l`/`+k`/`+t` reuse the existing `GateAction`-gated channel-mode mechanism
  `+i`/`+b` already use where applicable; the bare membership query reuses the same visibility
  rule and per-channel reply helper the single-channel form already uses; the operator-count
  statistic reuses the same connected-session filtering pattern the existing invisible-count
  statistic already uses) — no new mechanism is invented from scratch where an existing one
  already fits.
- FR-010's bare membership query carries no new abuse-specific restriction (clarified
  2026-08-21): the data it exposes is already obtainable per-channel via the existing
  single-channel form, so a bare query is a convenience, not a new information disclosure; the
  existing per-connection rate limiter and the private/secret visibility mechanism (both already
  in place before this feature) are treated as sufficient, matching how most RFC-conformant
  servers handle this case.
- FR-002/FR-005's invite exemption (clarified 2026-08-21) reuses the same `channel.invited()`
  pending-invitation record `+i`'s existing exemption check already reads from (`JoinCommandHandler`)
  — `+l`/`+k`'s join-time checks consult that same record rather than introducing a second,
  independent notion of "invited."
- `RPL_LUSERUNKNOWN` remains out of scope and unsent, unlike `RPL_LUSEROP`/`RPL_LUSERME` — this
  server has no notion of a connection that hasn't yet become a full client session, so there is
  nothing meaningful for that specific reply to report; this reasoning, already documented in
  `002-extended-irc-commands`, is carried forward unchanged.
- FR-013's assumption — that inviting to a not-yet-existing channel should succeed rather than be
  rejected — follows RFC 2812's own `INVITE` error set, which has no not-found-channel error case
  at all (only missing-nickname, not-on-channel, already-on-channel, and
  operator-privilege-required). This feature's planning phase MUST re-confirm this default
  against the irctest suite's current, non-deprecated `INVITE` expectations before implementing
  it; if the suite's own relevant test is marked deprecated with no current expectation to
  satisfy, this item is downgraded to documenting the existing (reject) behavior as intentional
  rather than changing it, and FR-013/SC-004's invitation clause is treated as satisfied by that
  documentation instead.
- FR-014 depends on `WhowasHistory`'s underlying storage already retaining more than one entry
  per nickname; if planning determines it currently retains only the single most recent one, this
  feature's scope for FR-014 includes widening that retention to a small, bounded history list —
  not merely exposing a count parameter over data that doesn't yet exist to return.
- None of these fixes changes any previously-passing behavior from `001-ircv3-server`,
  `002-extended-irc-commands`, `003-irctest-conformance-fixes`, `004-fix-tls-certificate`, or
  `005-fix-batch-conformance` — every FR here is new enforcement for a previously-inert mode, or a
  small missing piece of an already-implemented command, not a behavior change to anything
  currently working correctly.
- The scope exclusions this feature does not touch (see Input above) remain exactly as previously
  decided — this feature does not reopen any of them.
