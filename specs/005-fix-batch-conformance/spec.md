# Feature Specification: Fix Batch of Conformance Bugs

**Feature Branch**: `005-fix-batch-conformance`

**Created**: 2026-08-20

**Status**: Draft

**Input**: User description: "Fix a batch of 19 genuine conformance bugs surfaced by re-running the
irctest suite against jircd-server, via the custom controller in the jircd org's fork. Every item
is a small, scoped fix to an already-implemented command's behavior, not a new capability."

This feature is a third correctness follow-up to `001-ircv3-server`/`002-extended-irc-commands`/
`003-irctest-conformance-fixes` (`004-fix-tls-certificate` was an unrelated hardening fix). It
does not add new commands or capabilities — every item here corrects how an already-implemented
command replies or behaves. Each bug was independently investigated against real irctest failure
transcripts and confirmed against jircd-core's current source; none overlap with previously
documented, deliberate scope exclusions (channel modes `+l`/`+k`/`+t` and the associated
bare-`MODE`-query behavior, bare argument-less `NAMES`, `RPL_LUSEROP`/`UNKNOWN`/`ME`, `INVITE`
requiring an existing channel, `WHOWAS`'s count parameter, `metadata`/`chathistory`/
`multiline`/`relaymsg`/roleplay/bouncer, SASL/accounts, and anything Ergo-specific) — those are
either already accepted or flagged separately as new scope-exclusion candidates for a future,
separate clarification round, explicitly not part of this feature.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A Nickname Change Is Visible to Everyone Who Needs to Know (Priority: P1)

A client changes their nickname after registration. They expect to see their own confirmation,
and every channel they're currently in expects to see the change too — the same way every other
IRC server behaves.

**Why this priority**: The most severe finding in this batch — nickname changes currently produce
no confirmation and no channel notification at all, a silent, complete absence of a core,
universally-relied-upon piece of protocol behavior with no workaround.

**Independent Test**: Two clients share a channel; one changes nickname; verify both the renaming
client and its channel-mate receive a `NICK` message — independent of every other story in this
feature.

**Acceptance Scenarios**:

1. **Given** a registered client with no channel memberships, **When** it changes its nickname,
   **Then** it receives its own `NICK` change notification.
2. **Given** two clients sharing a channel, **When** one changes its nickname, **Then** the other
   receives a `NICK` notification for that change.

---

### User Story 2 - Direct Messages Honor the Same Guarantees as Channel Messages (Priority: P1)

A client sends a private message to another user and expects the same delivery guarantees already
correctly provided for channel messages: an empty message body is rejected the same way, and if
the client has negotiated to see its own outgoing messages echoed back, that echo arrives for
private messages exactly as it already does for channel ones.

**Why this priority**: `PRIVMSG` is the single most-used command; both gaps here directly break an
already-implemented, already-working guarantee (echo-message) or well-established RFC behavior
(empty-body rejection) specifically for the private-message case only.

**Independent Test**: With echo-message negotiated, send a private message and verify the sender
receives their own echo; separately, send an empty-body private message and verify it's rejected
— independent of every other story in this feature.

**Acceptance Scenarios**:

1. **Given** a client that has negotiated the echo-message capability, **When** it sends a private
   message to another connected user, **Then** it receives its own outgoing message echoed back,
   the same way it already does for a channel message.
2. **Given** a registered client, **When** it sends a private message with an empty body,
   **Then** the server rejects it with the standard missing-text error instead of delivering an
   empty message.

---

### User Story 3 - Connection and Capability Negotiation Follow the Documented Wire Format (Priority: P2)

A client relies on `PING`/`PONG`, capability negotiation (`CAP`), and message-tag length limits
matching their documented wire formats exactly, since client libraries parse replies
positionally and by exact numeric code — and expects that supplying a syntactically valid but
content-invalid registration field doesn't leave the connection in permanent limbo.

**Why this priority**: These are wire-format precision gaps in already-implemented, core
connection-layer behavior (present since `001-ircv3-server`) — real, but each is a narrow,
low-risk correction to an existing reply's exact shape, not a missing capability.

**Independent Test**: Send `PING` with and without a token and verify the exact reply shape;
negotiate a capability and verify `CAP LIST`/an invalid subcommand/a `NAK` with a repeated
capability all reply correctly; send a client-tagged message and verify the tag survives relay;
attempt registration with invalid UTF-8 in the real name and verify the connection doesn't hang
forever — independent of every other story in this feature.

**Acceptance Scenarios**:

1. **Given** a registered client, **When** it sends `PING` with a token, **Then** it receives
   `PONG` carrying both the server's own name and the token, in that order.
2. **Given** a registered client, **When** it sends `PING` with no token at all, **Then** the
   server replies with the standard missing-parameter error rather than fabricating a token.
3. **Given** a client that has negotiated one or more capabilities, **When** it sends `CAP LIST`,
   **Then** the reply lists only its currently negotiated capabilities, not every capability the
   server offers.
4. **Given** a client, **When** it sends an unrecognized `CAP` subcommand, **Then** it receives
   the specific invalid-subcommand error rather than the generic unknown-command error.
5. **Given** a client requesting capabilities including a repeated one in the same request,
   **When** the request is declined, **Then** the declining reply echoes back the full requested
   list, repeats included, not a deduplicated version.
6. **Given** a client that has negotiated message tags, **When** it sends a message carrying its
   own client-only tag, **Then** a recipient who has also negotiated message tags receives that
   same client tag on the relayed message.
7. **Given** a line whose message-tag section alone exceeds the tag-specific length limit but
   whose total length is still under the combined byte count previously used as the only check,
   **When** it's sent, **Then** the server rejects it as too long.
8. **Given** a connection attempting to register with a real name field containing invalid UTF-8,
   **When** it does so, **Then** the connection is cleanly closed or otherwise given a clear path
   forward, never left indefinitely half-registered with no further response.

---

### User Story 4 - Channel Membership Commands Match Their Documented Grammar (Priority: P2)

A client wants to join more than one channel in a single command (a core part of `JOIN`'s
grammar, not an optional extension), see a channel's topic immediately upon joining, receive a
`KICK` confirmation with a sensible default reason, query a channel's ban list using either
accepted query syntax, and get the precise error when granting channel operator status to a
nickname that isn't connected to the server at all.

**Why this priority**: Several of these are gaps in core, non-optional parts of long-established
commands (`JOIN`'s comma-separated grammar is not an extension) rather than edge-case polish, but
none block a P1 story's baseline functionality on their own.

**Independent Test**: Join two channels in one `JOIN` command and verify both memberships are
created; join a channel with an existing topic and verify the topic arrives immediately; kick a
member with no comment and verify a sensible default; query a channel's ban list with `+b`; grant
operator status to a nickname that isn't connected anywhere and verify the precise error —
independent of every other story in this feature.

**Acceptance Scenarios**:

1. **Given** a registered client, **When** it sends `JOIN` with a comma-separated list of channel
   names, **Then** it becomes a member of every valid channel named, not rejected as a single
   invalid channel name.
2. **Given** a channel with a topic already set, **When** a client joins it, **Then** the joining
   client receives the current topic as part of joining, without needing to query it separately.
3. **Given** a channel operator, **When** they kick a member with no comment given, **Then** the
   relayed kick confirmation includes a sensible default comment rather than omitting it.
4. **Given** a channel with active ban entries, **When** a member queries the ban list using
   either accepted query syntax, **Then** the ban list is returned either way.
5. **Given** a channel operator, **When** they attempt to grant channel-operator status to a
   nickname that is not connected to the server at all, **Then** they receive the specific
   nickname-does-not-exist error rather than the generic not-a-member-of-this-channel error.

---

### User Story 5 - User and Server Information Queries Are Complete (Priority: P3)

A client wants to look up host information for one or more nicknames, query basic server
information, look up a nickname that turns out not to exist and still receive a proper closing
reply, see a connected but invisible user when looking them up by their exact nickname, and
correctly clear (not set to a blank reason) their away status with an empty argument.

**Why this priority**: Lowest priority — a mix of one genuinely missing command, one existing
reply missing its required closing line, one narrow visibility-bypass gap, and one edge-case
input-handling gap, none of which block any higher-priority story.

**Independent Test**: Query host information for a nickname; query basic server information; look
up a nonexistent nickname and verify the closing reply still arrives; look up a connected,
invisible user by their exact nickname; send an empty-argument away-clear and verify away status
clears — independent of every other story in this feature.

**Acceptance Scenarios**:

1. **Given** a registered client, **When** it queries host information for a connected nickname,
   **Then** it receives that nickname's host information.
2. **Given** a registered client, **When** it queries basic server information, **Then** it
   receives a response rather than the generic unknown-command error.
3. **Given** a registered client, **When** it looks up a nickname that isn't connected, **Then**
   it still receives the lookup's closing reply, in addition to the not-found error.
4. **Given** a connected client with the invisible user mode set, **When** another client looks
   them up by their exact, correctly-cased nickname, **Then** the invisible client's information
   is still returned — invisibility only affects broader, non-exact lookups.
5. **Given** a client that is currently away, **When** it clears its away status using an empty
   trailing argument, **Then** its away status is cleared, the same as omitting the argument
   entirely.

---

### User Story 6 - Becoming a Server Operator Is Immediately Visible to the Operator's Own Client (Priority: P3)

A client successfully authenticates as a server operator and expects their own client-side state
tracking to immediately reflect the new operator status, without needing a separate query.

**Why this priority**: Lowest priority — a single missing notification line on an already-working
success path.

**Independent Test**: Successfully authenticate as a server operator and verify an unsolicited
mode-change notification for the new operator status arrives alongside the existing success
confirmation — independent of every other story in this feature.

**Acceptance Scenarios**:

1. **Given** a client with valid operator credentials, **When** it successfully authenticates as
   a server operator, **Then** it receives, in addition to the existing success confirmation, an
   unsolicited notification of its own new operator-mode status.

---

### Edge Cases

- What happens if a client is a member of many channels when it changes nickname? The
  notification MUST reach every channel it's currently a member of, not just one.
- What happens if a multi-channel `JOIN` names a mix of valid and already-excluded-grammar
  channels (e.g. one with a `+k` key this feature doesn't gate)? Each named channel is still
  processed independently on its own merits — this feature only fixes the comma-splitting itself,
  not per-channel gate enforcement, which is unchanged.
- What happens to a `CAP NAK` reply's repeated-capability echo if the repeated capability was
  requested three or more times? All occurrences are preserved, not just deduplicated to one.

## Requirements *(mandatory)*

### Functional Requirements

**Nickname Change Visibility**

- **FR-001**: A nickname change MUST be confirmed to the client that made it.
- **FR-002**: A nickname change MUST be relayed to every channel the changing client is
  currently a member of.

**Direct Message Delivery Guarantees**

- **FR-003**: A private message MUST be echoed back to its sender when the sender has negotiated
  the echo-message capability, the same guarantee already provided for channel messages.
- **FR-004**: A private message with an empty body MUST be rejected with the same
  missing-text error already used elsewhere for this condition, not delivered as an empty
  message.

**Connection and Capability Negotiation Precision**

- **FR-005**: `PING` with a token MUST reply with `PONG` carrying the server's own name and the
  token, in that order.
- **FR-006**: `PING` with no token MUST reply with the standard missing-parameter error rather
  than substituting a synthesized token.
- **FR-007**: `CAP LIST` MUST report only the requesting client's currently negotiated
  capabilities, distinct from `CAP LS`'s full offered list.
- **FR-008**: An unrecognized `CAP` subcommand MUST reply with the specific invalid-subcommand
  error rather than the generic unknown-command error.
- **FR-009**: A `CAP` request declined by the server MUST echo back the full list of requested
  capabilities exactly as given, including any repeats, not a deduplicated version.
- **FR-010**: A client-supplied message tag on an outgoing message MUST be preserved when the
  message is relayed to a recipient who has also negotiated message tags.
- **FR-011**: A message's tag section and its command-plus-parameters section MUST each be
  checked against their own independent length limit, not a single combined figure.
- **FR-012**: A registration attempt with invalid UTF-8 in the real name field MUST result in
  the connection being cleanly closed or otherwise given a clear path forward — never left
  indefinitely open with no further response.

**Channel Membership Command Completeness**

- **FR-013**: `JOIN` MUST accept a comma-separated list of channel names (with an optional
  matching comma-separated list of keys) and process each named channel, not reject the entire
  command as a single invalid channel name.
- **FR-014**: Joining a channel that already has a topic set MUST include that topic in the
  join's own reply burst, without requiring a separate query.
- **FR-015**: A `KICK` with no comment given MUST default its relayed comment to the kicking
  operator's own nickname, not omit it.
- **FR-016**: A channel ban-list query MUST be recognized whether sent as a bare list-mode letter
  or with a leading `+`.
- **FR-017**: Granting channel-operator or voice status to a nickname that is not connected to
  the server at all MUST reply with the specific nickname-does-not-exist error, distinct from the
  not-a-member-of-this-channel error used when the nickname exists but isn't in the target
  channel.

**Information Query Completeness**

- **FR-018**: The server MUST support a command for querying host information about one or more
  connected nicknames.
- **FR-019**: The server MUST support a command for querying basic server information.
- **FR-020**: A nickname lookup for a nickname that is not connected MUST still send the lookup's
  closing reply, in addition to the not-found error.
- **FR-021**: A lookup by a connected user's exact, correctly-cased nickname MUST return that
  user's information regardless of the invisible user mode — only a broader (masked or
  no-argument) lookup form is affected by invisibility.
- **FR-022**: Clearing away status with an empty trailing argument MUST behave identically to
  clearing it with no argument at all — both clear away status; neither sets an empty-string
  away reason.

**Operator Status Visibility**

- **FR-023**: Successfully authenticating as a server operator MUST result in an unsolicited
  notification of the client's own new operator-mode status, in addition to the existing success
  confirmation.

### Key Entities

This feature introduces no new entities — every change here is to the reply/notification content
an already-implemented command produces, or to already-modeled state (`ClientSession`,
`Channel`, `NicknameRegistry`) `001-ircv3-server`/`002-extended-irc-commands` already track.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A client changing nickname while a member of one or more channels always sees
  every co-member notified, 100% of the time, with no change to how quickly the change itself
  takes effect.
- **SC-002**: A client with echo-message negotiated sees its own private messages echoed back
  100% of the time, matching the existing channel-message guarantee exactly.
- **SC-003**: Running the irctest conformance suite's tests corresponding to the 19 findings this
  feature addresses (FR-001 through FR-023, grouped by the six user stories above) shows all of
  them passing, with no regression in any previously-passing test.
- **SC-004**: A client library using positional/exact-numeric parsing for `PING`/`PONG`, `CAP`,
  and message-tag replies parses every reply this feature touches without a parse failure.
- **SC-005**: No connection can be left indefinitely half-registered after supplying invalid
  UTF-8 in registration — every such attempt reaches a definite outcome (closed connection or a
  clear next step) within the same bounded time every other malformed-registration case already
  resolves in.

## Assumptions

- Every fix here reuses an already-established reply/notification pattern elsewhere in this
  codebase where one exists (e.g. `NICK`'s fan-out mirrors `MessageCommandHandler`'s existing
  channel fan-out; `+o`/`+v`'s corrected numeric reuses the existing nickname-registry-lookup
  pattern `WHOIS`/`KILL` already use) — no new reply format or notification convention is
  invented from scratch.
- "The standard missing-text error" (FR-004) and "the standard missing-parameter error" (FR-006)
  refer to the exact same numeric replies already used elsewhere in this codebase for the
  equivalent condition on other commands — reused exactly, not redefined.
- USERHOST (FR-018) and INFO (FR-019) are being implemented for the first time as part of this
  feature — both already exist as recognized-but-unhandled wire-protocol commands
  (`001-ircv3-server`'s "wire-protocol recognition MUST represent the full RFC set"), so this is
  completing an already-declared-but-unbuilt command, not adding a new one to the protocol
  surface. Their reply content follows the same minimal, RFC-conventional shape every other
  small query command in this codebase already uses — no new design surface.
- None of these fixes changes any previously-passing behavior from `001-ircv3-server`,
  `002-extended-irc-commands`, `003-irctest-conformance-fixes`, or `004-fix-tls-certificate` —
  every FR here is additive precision or a small missing piece of an already-implemented
  command, not a behavior change to anything currently working correctly.
- The already-documented scope exclusions this feature explicitly does not touch (see Input
  above) remain exactly as previously decided — this feature does not reopen any of them.
