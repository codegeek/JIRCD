# Feature Specification: Modular IRCv3 Chat Server

**Feature Branch**: `001-ircv3-server`

**Created**: 2026-08-15

**Status**: Draft

**Input**: User description: "Build a modular IRCv3 server in Java with the latest capabilities"

## Clarifications

### Session 2026-08-15

- Q: Should this be a single standalone server, or must multiple server instances be able to link together into one federated network? → A: Start standalone; architecture must support adding server-to-server federation as a later, independently addable module without a core redesign.
- Q: Should the server own and manage user accounts and channel-registration/ownership records itself, or should it only verify identities against an external, separately operated account system? → A: Identity verification MUST support external account systems (pluggable), AND the initial release MUST ship a basic, built-in account module (its own minimal account store) as the default pluggable implementation.
- Q: Which specific IRCv3 capabilities must the server support in its initial release? → A: Minimal core set only — `message-tags` (structured metadata on messages), `server-time` (accurate timestamps), and `echo-message` (delivery confirmation of a client's own sent messages). Broader capabilities (SASL beyond the minimum needed for Story 3, account-notify, away-notify, batch, chathistory, etc.) are deferred to a later iteration.
- Q: Is authentication (Story 3) mandatory for all connections, and what identity-uniqueness rules are non-negotiable? → A: Authentication remains optional per-connection at initial release — unauthenticated clients continue to be served under standard IRC behavior. What is non-negotiable, per core IRC protocol requirements, is that nicknames and channel names are each a single, server-wide unique namespace: a nickname or channel name identifies exactly one client/channel at a time, with no duplicates permitted regardless of authentication status.
- Q: Must every client connection be encrypted in transit? → A: No — encryption is not part of the core IRC protocol. The server MUST offer an encrypted (TLS) connection option, but MUST also continue to accept unencrypted connections; encryption is a per-connection/administrator choice, not a mandatory requirement for all clients.
- Q: Does "modular" require that modules can be loaded and unloaded while the server keeps running, or is a restart acceptable to apply a module change? → A: Modules MUST be enable/disable-able by an administrator, and that change MUST take effect without restarting the running server process — no restart or reload interruption is acceptable for a module state change.
- Q: When two clients attempt to claim the same nickname at nearly the same instant, how must the server resolve the race? → A: First-registration-wins: the server MUST treat nickname claims atomically against its live state, so whichever request it commits first holds the nickname, and every other concurrent or later request for that same name is rejected with the standard "nickname in use" error (FR-002) — consistent with RFC 2812's ERR_NICKNAMEINUSE (433), which the original IRC protocol defines for exactly this case.
- Q: How is channel-operator status determined, and how does that interact with the account module? → A: By default (classic IRC behavior), the first client to join a channel becomes its operator. When the account module (FR-023/FR-024) is enabled, a channel MAY instead be registered/owned by an account and a nickname MAY be registered to an account (NickServ/ChanServ-style, used here as the familiar reference pattern, not a mandated command set); wherever such registration exists, it takes priority over the classic first-join rule — a registered channel's operator authority comes from its account-system ownership/access records, and by default an unauthenticated or wrongly-authenticated client MUST NOT be able to claim a nickname already registered to another account (administrator-configurable to allow).
- Q: Is User Story 3 (authentication) mandatory for the first iteration? → A: No — Story 3 is deferred to a later release. The first iteration MUST be fully usable via Stories 1, 2, and 4 alone. All requirements that exist solely to support Story 3 (FR-009, FR-010, FR-023, FR-024, FR-026, FR-027) and its success criterion (SC-007) are deferred with it; the classic first-join-gets-operator rule (FR-013) is the only channel-operator behavior in effect until the account module is later built.
- Q: Does treating federation as "just another module" (like Story 4's capabilities/moderation-tool modules) correctly capture what a future server-to-server implementation would need? → A: No — that framing was reconsidered as an oversimplification. Federation introduces a server-to-server trust boundary and distributed state (network-wide identity uniqueness, cross-server message routing, netsplit handling) that a client-facing module does not; it is not required to fit the FR-011 module abstraction, and its actual extension mechanism is left as a planning-phase decision made when federation is scoped. What this specification commits to now instead is narrower and testable: FR-021 (standalone operation, federation out of initial scope) plus FR-022, which names the specific core behaviors — nickname/channel uniqueness scope, channel message delivery, connection-loss handling — that MUST be implemented in a way that doesn't foreclose extending them later.
- Q: Once federation exists, must every linked server run the same active modules, and must authentication stay consistent network-wide? → A: Yes to both. When federation is introduced, linked servers MUST present a consistent set of active modules to clients network-wide — a client's experience must not depend on which linked server it connects to (FR-028). Where the account module is in use in a federated network, account/identity verification and registration records MUST be managed by a single authoritative source shared across all linked servers, not independently per-instance (FR-029). Neither constraint requires anything of the initial, standalone release; they bound how a future federation effort must behave.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Connect and Chat in Real Time (Priority: P1)

A person using any standard IRC client connects to the server, registers a
nickname, joins a channel, and exchanges messages with other people
connected to that channel in real time.

**Why this priority**: This is the core reason the server exists — without
reliable connection, registration, and message delivery, nothing else
matters. It is the minimum viable product.

**Independent Test**: Connect two separate IRC clients to the server,
register distinct nicknames, join the same channel, and confirm each
client sees messages sent by the other within a second.

**Acceptance Scenarios**:

1. **Given** the server is running and reachable, **When** a client
   connects and sends valid registration commands (nickname and user
   info), **Then** the server confirms registration and the client is
   recognized as an active user.
2. **Given** two registered clients have joined the same channel, **When**
   one sends a message to the channel, **Then** the other receives the
   message attributed to the correct sender.
3. **Given** a client attempts to register with a nickname already in use
   by another connected client, **When** the registration is submitted,
   **Then** the server rejects it with a clear "nickname in use" response
   and the client can retry with a different nickname.

---

### User Story 2 - Discover and Use Enhanced Capabilities (Priority: P2)

A person using a modern IRC client that supports capability negotiation
connects to the server, discovers which enhanced protocol capabilities are
available, and enables the ones its client supports so it can display
richer information (such as message timestamps and delivery
confirmations) without breaking clients that do not support them.

**Why this priority**: Capability negotiation is what distinguishes an
"IRCv3" server from a legacy IRC server — it is the mechanism that lets
the server offer modern features while remaining backward compatible with
older clients, so it is core to the product's identity but depends on
basic connectivity (Story 1) already working.

**Independent Test**: Connect a capability-aware test client, request the
list of available capabilities, negotiate a subset, and confirm the
server's subsequent messages include the negotiated enhancements (e.g., a
timestamp on each message) while a plain client connected at the same time
sees standard, unaugmented messages.

**Acceptance Scenarios**:

1. **Given** a client connects and requests the capability list before
   registering, **When** the server responds, **Then** the client receives
   a complete, accurate list of capabilities currently available.
2. **Given** a client requests a specific supported capability, **When**
   the request is processed, **Then** the server acknowledges it and
   begins including that capability's enhancements in relevant messages
   for that client's session.
3. **Given** a client requests a capability the server does not support,
   **When** the request is processed, **Then** the server clearly declines
   that specific capability without breaking the rest of the negotiation
   or disconnecting the client.

---

### User Story 3 - Authenticate to Protect an Identity (Priority: P3, Deferred)

A person with a registered account authenticates during connection so that
other users and the server can trust their claimed identity, preventing
someone else from impersonating them by using the same nickname.

**Why this priority**: Identity trust becomes important once a community
has recurring members and protected nicknames/channels, but a server can
deliver value (Stories 1–2) before this is required — so it follows
core chat and capability negotiation in priority. **This story is not
mandatory for the first iteration**: the server MUST be fully usable
(Stories 1–2, and Story 4's extensibility) without it, and it is deferred
to a later release. It is documented now so the boundary it requires (the
account module) can be designed for without committing to build it yet.

**Independent Test**: Attempt to authenticate a client with valid account
credentials and confirm the session is marked as authenticated to other
clients; attempt authentication with invalid credentials and confirm it is
rejected with a clear error and the connection is not marked as
authenticated.

**Acceptance Scenarios**:

1. **Given** a client provides valid account credentials during
   authentication, **When** the server verifies them, **Then** the session
   is marked authenticated and the client's verified identity is visible
   to other clients that request it.
2. **Given** a client provides invalid credentials, **When** the server
   attempts verification, **Then** the server rejects the attempt with a
   clear error and does not mark the session as authenticated.
3. **Given** an authenticated client disconnects, **When** it reconnects
   with the same credentials, **Then** it is able to re-authenticate and
   regain its verified status.

---

### User Story 4 - Tailor the Server with Optional Extensions (Priority: P4)

A server administrator enables, disables, and configures optional
extensions (for example, individual IRCv3 capabilities such as
`message-tags` or `server-time`) to match their community's needs, without
needing to modify the server's core codebase. Core protocol behavior —
including channel moderation and the capability-negotiation mechanism
itself — is always present and is not part of what this story's toggling
applies to (see FR-035, FR-036).

**Why this priority**: Extensibility is a stated goal of the product and
matters most to administrators, but the server delivers its primary value
to end users (chatting) even with a fixed default set of extensions
enabled — so administrator-facing configurability is valuable but not
blocking for the core chat experience.

**Independent Test**: As an administrator, disable an optional extension
via configuration while the server keeps running (no restart), and confirm
connected clients can no longer use that extension's functionality while
all other functionality continues to work uninterrupted; re-enable it the
same way and confirm functionality returns without a restart.

**Acceptance Scenarios**:

1. **Given** the administrator has a list of optional extensions, **When**
   they disable one via configuration while the server is running, **Then**
   the server no longer offers or accepts requests for that extension's
   functionality, without the server process being restarted.
2. **Given** an extension is disabled, **When** the administrator
   re-enables it via configuration while the server is running, **Then**
   its functionality becomes available again without requiring changes
   beyond configuration and without restarting the server process.
3. **Given** an administrator provides an invalid extension configuration,
   **When** the server loads its configuration, **Then** the server
   reports a clear configuration error identifying the problem instead of
   starting in a broken or partially-configured state.

---

### User Story 5 - Moderate a Channel (Priority: P5)

A channel operator manages who can participate in their channel and how,
using standard moderation actions (such as removing a disruptive user or
restricting who may speak) to keep the community usable.

**Why this priority**: Moderation matters once channels have active,
larger communities, but small or trusted communities can function
initially without it — it rounds out the product rather than gating its
core value.

**Independent Test**: As a channel operator, remove a user from a channel
and confirm they are no longer a member and are notified of the removal;
confirm other channel members see the removal reflected.

**Acceptance Scenarios**:

1. **Given** a user is a channel operator, **When** they remove another
   member from the channel, **Then** that member is no longer part of the
   channel and all remaining members are notified of the removal.
2. **Given** a user is not a channel operator, **When** they attempt a
   moderation action, **Then** the server rejects the action with a clear
   permissions error.
3. **Given** an operator restricts who may send messages to a channel,
   **When** a non-permitted member attempts to send a message, **Then**
   the message is not delivered and the sender receives a clear
   explanation.

---

### User Story 6 - Administer the Server via IRC Commands (Priority: P4)

An administrator, connected as an ordinary IRC client, grants themselves
administrator privilege via an in-band command and then issues
administrative commands — such as enabling or disabling an optional
extension, or looking up a client's real hostname — directly through the
IRC protocol, without needing file system or configuration-file access to
the server host.

**Why this priority**: In-band administration is a peer capability to
Story 4's configuration-file path, not a lesser one — administering a
server that requires host/file access for every change is not practically
usable, so this shares Story 4's priority tier.

**Independent Test**: Connect as a normal client, issue the
privilege-granting command with valid administrator credentials, confirm
privilege is granted, then issue an extension-toggle administrative
command and confirm the extension's state change takes effect for other
connected clients — all without touching the configuration file.

**Acceptance Scenarios**:

1. **Given** a connected, registered client, **When** it issues the
   administrator-privilege command with valid credentials, **Then** the
   server grants administrator privilege to that session and confirms it.
2. **Given** a session without administrator privilege, **When** it
   attempts an administrative command, **Then** the server rejects it with
   a clear permissions error and takes no action.
3. **Given** a session holding administrator privilege, **When** it issues
   a command to disable an enabled extension, **Then** the extension
   becomes unavailable to all clients without a server restart — the same
   observable effect as a configuration-file-driven change (FR-011).
4. **Given** a session holding administrator privilege, **When** it
   requests a specific client's real hostname, **Then** the server returns
   the real, unobfuscated value even if a cloaking extension currently
   obscures that client's hostname from other clients (FR-031).

---

### Edge Cases

- What happens when a client attempts to register with a nickname or
  username that violates protocol formatting rules (invalid characters,
  excessive length)?
- How does the server handle a client that sends messages faster than an
  acceptable rate (flooding), whether accidental or malicious?
- What happens when a client disconnects abruptly (no clean quit) while
  still a member of one or more channels?
- How does the server respond to a malformed or incomplete protocol
  message that cannot be parsed?
- How does the server behave when an optional extension fails to start or
  encounters an internal error while running — does the rest of the
  server continue operating?
- What happens when a client negotiates a capability, and mid-session the
  administrator disables the extension providing that capability?
- How does the server respond when a channel or nickname name exceeds
  defined length or character constraints?
- What happens when the maximum number of concurrent connections is
  reached and a new client attempts to connect?
- What happens when a client issues the administrator-privilege command
  (FR-034) with invalid credentials — is the attempt logged as a
  security-relevant event (FR-019), and is the client disconnected or just
  refused privilege?
- What happens to already-cloaked clients' presented hostnames when the
  cloaking extension is disabled while they remain connected — is cloaking
  removed immediately, or does it persist for that session until
  reconnect?
- *(Applies once Story 3 / the account module is implemented — not
  applicable to the initial release)* How does the server handle an
  authenticated client's underlying account being deleted or suspended
  mid-session?
- *(Applies once Story 3 / the account module is implemented — not
  applicable to the initial release)* What happens when an unregistered
  channel already has a first-join operator, and a member later registers
  ownership of that channel to an account — does the account's authority
  immediately supersede the existing operator (per FR-026), and how are
  current members notified?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The server MUST accept incoming client connections and allow
  a client to register a unique nickname and associated user information
  before participating in chat.
- **FR-002**: The server MUST reject registration attempts that use a
  nickname currently claimed by another connected client, and MUST inform
  the requesting client clearly. Nickname claims MUST be resolved
  atomically against the server's live state: when multiple clients
  request the same nickname concurrently, exactly one MUST succeed (the
  one the server commits first) and every other request for that name —
  concurrent or later — MUST be rejected with the same "nickname in use"
  error, with no window in which two clients both hold the name.
- **FR-003**: The server MUST allow registered clients to create or join
  named channels and to leave channels they have joined. Channel names
  MUST form a single, server-wide unique namespace: a given channel name
  identifies exactly one channel at a time, so joining a name that
  already exists MUST add the client to that existing channel rather than
  creating a separate, duplicate one.
- **FR-004**: The server MUST deliver messages sent to a channel to every
  other client currently joined to that channel, attributed to the correct
  sender.
- **FR-005**: The server MUST support direct, private messaging between
  two registered clients without requiring a shared channel.
- **FR-006**: The server MUST support capability negotiation, allowing a
  connecting client to request the list of capabilities the server
  currently offers before completing registration.
- **FR-007**: The server MUST allow a client to request one or more
  specific capabilities and MUST respond indicating which were accepted
  and which were declined.
- **FR-008**: The server MUST continue to support clients that do not
  perform capability negotiation at all, providing them standard,
  unaugmented protocol behavior.
- **FR-009** *(Deferred — not required for initial release; supports
  Story 3)*: The server MUST support verifying a client's claimed account
  identity during connection registration via an authentication exchange,
  and MUST reflect authenticated status to other clients that request it.
  Authentication MUST remain optional per-connection whenever it is
  implemented: a client that does not authenticate MUST still be able to
  register a nickname and use standard chat functionality under core IRC
  behavior.
- **FR-010** *(Deferred — not required for initial release; supports
  Story 3)*: The server MUST reject invalid authentication attempts with
  a clear error and MUST NOT mark a session as authenticated when
  verification fails.
- **FR-011**: The server MUST allow an administrator to enable or disable
  individual optional extensions via configuration, without requiring
  changes to the server's core codebase, and without requiring the running
  server process to be restarted for the change to take effect.
- **FR-012**: The server MUST report a clear, specific error when an
  administrator's configuration is invalid, rather than silently ignoring
  the problem or starting in an inconsistent state.
- **FR-013**: The server MUST support designated channel operators
  performing standard moderation actions, including removing a member from
  a channel and restricting who may send messages to it. By default, a
  channel's first operator MUST be the client whose join created that
  channel (classic first-join-gets-operator behavior). FR-026 and FR-027
  define how this default is later superseded once the (currently
  deferred) account module manages the channel or nickname involved; for
  the initial release, the classic default is the only behavior in
  effect.
- **FR-014**: The server MUST reject moderation actions attempted by
  clients who do not hold the required channel privileges, with a clear
  permissions error.
- **FR-015**: The server MUST detect and reject malformed protocol
  messages without crashing or disconnecting unrelated clients.
- **FR-016**: The server MUST limit the rate of messages or commands
  accepted from a single connection to protect overall service
  availability for other clients.
- **FR-017**: The server MUST clean up a client's channel memberships and
  notify affected channels when that client disconnects, whether
  gracefully or unexpectedly.
- **FR-018**: The server MUST offer clients the option to connect over an
  encrypted (TLS) connection, and MUST also continue to accept
  unencrypted connections. Encryption is a per-connection choice made by
  the client (or an administrator-configured policy for a given
  deployment), not a mandatory requirement of the protocol itself.
- **FR-019**: The server MUST log security-relevant events (including
  failed authentication attempts and rejected moderation actions) in a
  form an administrator can review.
- **FR-020**: The server MUST continue serving unaffected clients when a
  single optional extension fails to start or encounters a runtime error.
- **FR-021**: The server MUST operate as a complete, self-sufficient
  standalone deployment — a single instance MUST NOT require any other
  server instance to be present to serve clients. Server-to-server
  federation (linking multiple instances into one network experienced by
  end users as a single chat network) is out of scope for the initial
  release. The core design MUST NOT foreclose adding federation in a
  later release, but this specification does NOT require federation to
  fit the same independent-extension abstraction used for individual
  capabilities and command sets (FR-011): federation introduces a
  server-to-server trust boundary and distributed state that a
  client-facing extension does not, so it may need its own extension
  mechanism, to be defined when federation is actually planned rather
  than assumed now.
- **FR-022**: To keep a future federation effort viable without a full
  redesign, the initial release's implementation of the following
  client-facing behaviors MUST be treated as later-extensible rather than
  hard-assumed to be server-local: nickname and channel uniqueness
  scope (FR-002, FR-003 — "unique on this server" today, potentially
  "unique across the network" later), message delivery to a channel's
  membership (FR-004 — membership may later span servers), and
  connection-loss cleanup and notification (FR-017 — a "disconnect" may
  later originate from a peer server rather than the client). No
  federation behavior itself is required for the initial release; this
  requirement only constrains how the in-scope behaviors above are
  implemented so they remain compatible with a later change.
- **FR-023** *(Deferred — not required for initial release; supports
  Story 3)*: The server MUST verify account identities through a
  pluggable account-verification interface, allowing an administrator to
  configure it against an external, separately operated account system
  instead of (or in addition to) the server's own account store.
- **FR-024** *(Deferred — not required for initial release; supports
  Story 3)*: The server MUST ship with a basic, built-in account module —
  a minimal account store covering account creation and credential
  verification — enabled by default, so the server is usable for
  authentication (Story 3) without requiring an external account system
  to be deployed first. Whenever this module is implemented, it MUST
  store credentials securely: credentials MUST NOT be persisted in plain
  text, and MUST be protected using an industry-standard, purpose-built
  method for the credential type (e.g., a salted, computationally-expensive
  hash for passwords) such that recovering the original credential from
  stored data alone is infeasible.
- **FR-025**: The server MUST offer the following enhanced capabilities in
  its initial release: structured message metadata (`message-tags`),
  accurate message timestamps (`server-time`), and delivery confirmation
  of a client's own sent messages (`echo-message`). The authentication
  capability needed to support Story 3 is deferred along with that story
  (see FR-023). Other enhanced capabilities (e.g., presence/away status,
  multi-device account awareness, batched message delivery, chat history
  replay) are likewise out of scope for initial release and deferred to a
  later iteration.
- **FR-026** *(Deferred — not required for initial release; depends on
  the account module, FR-023/FR-024)*: When the account module is
  enabled, an account MUST be able to register ownership of a channel.
  Where a channel is registered, its operator/privilege assignments MUST
  be derived from that account's stored access records rather than from
  join order, and this registered-ownership authority MUST take priority
  over the classic first-join-gets-operator default (FR-013) whenever the
  two would conflict — for example, an authenticated owner joining a
  registered channel after others are already present MUST still receive
  its operator authority. Until the account module is implemented, every
  channel's operator status follows the classic default only.
- **FR-027** *(Deferred — not required for initial release; depends on
  the account module, FR-023/FR-024)*: When the account module is
  enabled, an account MUST be able to register ownership of a nickname.
  By default, the server MUST reject an attempt by an unauthenticated
  client, or a client authenticated to a different account, to claim a
  nickname already registered to another account. An administrator MUST
  be able to configure the server to allow this instead (e.g., permitting
  the claim with a warning rather than a rejection).
- **FR-028** *(Deferred — constrains a future federation effort; not
  applicable to the initial, standalone release)*: Once federation
  (FR-021) is introduced, every server linked into the same network MUST
  present a consistent set of active extensions to clients — an optional
  capability or other extension enabled on one linked server MUST be
  enabled (or consistently unavailable) network-wide, so a client's
  experience does not depend on which linked server it happens to be
  connected to.
- **FR-029** *(Deferred — constrains a future federation effort; not
  applicable to the initial, standalone release)*: Once federation
  (FR-021) is introduced and the account module (FR-023/FR-024) is in
  use, account verification and account/nickname/channel registration
  records MUST be managed by a single authoritative source shared across
  every linked server in the network. No linked server instance may
  operate its own independent, potentially divergent account store.
- **FR-030**: The server MUST present each client's identity, on protocol
  messages that include a sender (e.g., channel and direct messages, join/
  part/quit notifications), in the standard `nickname!ident@hostname` form,
  where `ident` and `hostname` are derived from the client's connection.
- **FR-031**: The server MUST support an optional extension that replaces
  the hostname/IP portion of a client's presented identity (FR-030) with an
  obfuscated value shown to other clients, while the server continues to
  record that client's real, unobfuscated hostname/IP internally.
  Administrators MUST be able to view a client's real hostname/IP at any
  time regardless of whether cloaking is currently applied to it (see
  FR-032).
- **FR-032**: The server MUST provide an in-band administrative command
  interface, available over the IRC protocol itself, through which an
  authorized administrator can perform administrative actions — at
  minimum, enabling/disabling optional extensions (FR-011) and viewing a
  client's real, unobfuscated hostname/IP (FR-031) — without requiring
  direct access to the server's configuration file or host filesystem.
- **FR-033**: The server MUST restrict administrative commands (FR-032) to
  sessions holding administrator privilege, and MUST reject attempts from
  any other session with a clear permissions error — mirroring the
  channel-operator privilege pattern (FR-014), but as a distinct,
  server-wide privilege independent of channel-operator status.
- **FR-034**: The server MUST support granting administrator privilege to
  an already-registered session via an in-band credential-verification
  command (classic IRC OPER-style), checked against administrator
  credentials defined in the Server Configuration. This mechanism MUST be
  independent of the deferred account module (FR-023/FR-024), since
  administrative access must not depend on functionality that is out of
  scope for the initial release. Administrator credentials stored in the
  Server Configuration MUST be protected the same way as FR-024 requires
  for the account module (not plain text; industry-standard hashing).
- **FR-035**: The capability-negotiation mechanism itself (FR-006, FR-007,
  FR-008 — a client's ability to request the capability list and negotiate
  a subset) MUST always be available and MUST NOT be an optional extension
  subject to FR-011 toggling. Only the individual capabilities it offers
  (FR-025's `message-tags`, `server-time`, `echo-message`) are optional,
  independently toggleable extensions; disabling all of them MUST still
  leave a client able to perform capability negotiation and simply receive
  an empty or reduced capability list.
- **FR-036**: Channel moderation (FR-013, FR-014 — channel-operator
  designation and standard moderation actions) is core protocol behavior,
  equivalent to user modes and channel modes in standard IRC, and MUST
  always be available. It MUST NOT be an optional extension subject to
  FR-011 toggling; an administrator MUST NOT be able to disable moderation
  capability network-wide.

### Key Entities

- **Client Session**: A single connected client's live state — its
  network connection, registration status, negotiated capabilities,
  authentication status, and channel memberships.
- **User Identity**: The persistent, human-recognizable identity a client
  presents (nickname, and optionally a verified account), independent of
  any single connection. Presented on the wire in the standard
  `nickname!ident@hostname` form (FR-030). The `hostname` portion MAY be
  obfuscated for other clients by an optional cloaking extension (FR-031),
  but the real value is always retained internally and remains visible to
  administrators (FR-032).
- **Administrator Privilege**: A server-wide grant on a Client Session,
  obtained via FR-034's in-band credential command, that authorizes
  FR-032's administrative commands. Distinct from channel-operator status
  (Channel entity) and independent of the deferred Account entity.
- **Account** *(deferred with Story 3 — not present in the initial
  release)*: A registered identity managed by the account module
  (built-in or external), distinct from a nickname — one account may be
  associated with a nickname registration and/or one or more registered
  channels, and persists independently of any single connection or
  session.
- **Channel**: A named, joinable group through which multiple clients
  exchange messages; tracks its membership list, operator/privilege
  assignments, and moderation settings. In a later release, once the
  account module exists, a channel MAY be registered to an Account, in
  which case its operator/privilege assignments are sourced from that
  account's access records (FR-026) instead of join order; the initial
  release has no such registration.
- **Capability**: A named, independently negotiable protocol enhancement
  that a client may request; has an availability state (offered/not
  offered) determined by which extensions are currently enabled.
- **Extension**: An independently enableable/disableable unit of optional
  server functionality (e.g., an individual capability, a command set)
  configured by the administrator. Core protocol behavior — channel
  moderation (FR-036) and the capability-negotiation mechanism itself
  (FR-035) — is never modeled as an Extension; it is always present.
- **Server Configuration**: The administrator-controlled settings
  determining which extensions are active and how core and optional
  behavior is tuned.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A new user can go from initial connection to successfully
  sending their first channel message in under 10 seconds using any
  standard IRC client.
- **SC-002**: Messages sent to a channel are delivered to all other
  members of that channel within 1 second under normal operating
  conditions.
- **SC-003**: The server sustains at least 1,000 simultaneously connected
  clients without message delivery delay exceeding the 1-second target in
  SC-002.
- **SC-004**: A capability-aware client can complete capability discovery
  and negotiation in a single round trip before registration completes.
- **SC-005**: An administrator can change which optional extensions are
  active using configuration alone — no code changes, rebuild, or server
  restart — and observe the effect reflected for connected and new
  clients within 1 minute of applying the change.
- **SC-006**: During a sustained burst of excessive traffic from a single
  connection, message delivery latency for all other, well-behaved
  clients does not increase beyond the SC-002 target.
- **SC-007** *(Deferred with Story 3 — not an initial-release gate)*: 100%
  of authentication attempts with valid credentials succeed, and 100% of
  attempts with invalid credentials are rejected, across repeated
  testing.
- **SC-008**: Administrators can identify the cause of a configuration
  error from the reported error message alone, without consulting source
  code, in under 5 minutes.
- **SC-009**: An authorized administrator can gain administrator privilege
  and enable/disable an extension entirely through in-band IRC client
  commands — no file system or configuration-file access to the server
  host required — with the same effect and timing as SC-005.

## Assumptions

- The implementation platform is Java, per explicit stakeholder direction;
  specific frameworks, libraries, and build tooling are determined during
  technical planning and are out of scope for this specification.
- "Modular" is interpreted as: optional functionality — individual
  extensions (capabilities, command sets) — can be independently enabled,
  disabled, and configured by an administrator without modifying core
  server code and without restarting the running server process (FR-011).
  Channel moderation and the capability-negotiation mechanism are
  explicitly core, always-present behavior, not part of this optional-
  extension surface (FR-035, FR-036). This applies to toggling existing
  extensions on/off; whether entirely new, third-party extensions can be
  installed at runtime without a restart is not required by this
  specification and is a planning-phase design choice.
- Authentication (Story 3, FR-009/FR-010) and the account module
  (FR-023/FR-024) are deferred and not required for the first iteration;
  when they are implemented, authentication remains optional per-connection
  by default, and an administrator MAY configure the server to require it
  as a configuration concern rather than a change to FR-009 itself.
- The optional TLS connection offering (FR-018) uses standard transport
  encryption; certificate management approach is a planning concern, not
  a specification concern.
- Rate limiting/flood protection (FR-016, SC-006) uses reasonable
  industry-standard defaults, configurable by the administrator, rather
  than a single fixed universal threshold.
- No mobile app, web client, or GUI is in scope — this specification
  covers the server and its protocol behavior only; any client software
  is out of scope.
- Server-to-server federation is deferred past initial release (see
  FR-021). It is not assumed to fit Story 4's client-facing extension
  system (individual capabilities, command sets) — federation's
  server-to-server trust boundary and distributed state make it a
  different kind of extension, whose own mechanism is a planning-phase
  decision to be made when federation is actually scoped. What this
  specification does commit to now is narrower: the specific core
  behaviors named in FR-022 (nickname/channel uniqueness scope, channel
  message delivery, connection-loss handling) must be implemented so a
  later federation effort doesn't require redesigning them, plus two
  standing constraints on that future effort itself — extension
  consistency across linked servers (FR-028) and a single authoritative
  account source network-wide (FR-029).
- The `ident` portion of FR-030's `nickname!ident@hostname` identity is
  derived from the username the client supplies at registration (FR-001),
  not from an RFC 1413 IDENT-protocol lookup against the client's host —
  IDENT-protocol verification is out of scope for this release.
- FR-034's administrator-privilege mechanism intentionally mirrors the
  classic IRC OPER pattern (credentials defined in server-side
  configuration, verified in-band) specifically because it must not depend
  on the deferred account module; if Story 3 is later implemented, whether
  administrator privilege should additionally support account-based
  grants is a decision for that future work, not this release.
