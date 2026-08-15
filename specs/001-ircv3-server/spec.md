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

### User Story 3 - Authenticate to Protect an Identity (Priority: P3)

A person with a registered account authenticates during connection so that
other users and the server can trust their claimed identity, preventing
someone else from impersonating them by using the same nickname.

**Why this priority**: Identity trust becomes important once a community
has recurring members and protected nicknames/channels, but a server can
deliver value (Stories 1–2) before this is required — so it follows
core chat and capability negotiation in priority.

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

### User Story 4 - Tailor the Server with Optional Modules (Priority: P4)

A server administrator enables, disables, and configures optional feature
modules (for example, specific capabilities, moderation tools, or command
sets) to match their community's needs, without needing to modify the
server's core codebase.

**Why this priority**: Modularity is a stated goal of the product and
matters most to administrators, but the server delivers its primary value
to end users (chatting) even with a fixed default set of modules enabled
— so administrator-facing configurability is valuable but not blocking
for the core chat experience.

**Independent Test**: As an administrator, disable an optional module via
configuration while the server keeps running (no restart), and confirm
connected clients can no longer use that module's functionality while all
other functionality continues to work uninterrupted; re-enable it the
same way and confirm functionality returns without a restart.

**Acceptance Scenarios**:

1. **Given** the administrator has a list of optional modules, **When**
   they disable one via configuration while the server is running, **Then**
   the server no longer offers or accepts requests for that module's
   functionality, without the server process being restarted.
2. **Given** a module is disabled, **When** the administrator re-enables
   it via configuration while the server is running, **Then** its
   functionality becomes available again without requiring changes beyond
   configuration and without restarting the server process.
3. **Given** an administrator provides an invalid module configuration,
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
- What happens when two clients attempt to claim the same nickname at
  nearly the same instant?
- How does the server behave when an optional module fails to start or
  encounters an internal error while running — does the rest of the
  server continue operating?
- What happens when a client negotiates a capability, and mid-session the
  administrator disables the module providing that capability?
- How does the server respond when a channel or nickname name exceeds
  defined length or character constraints?
- What happens when the maximum number of concurrent connections is
  reached and a new client attempts to connect?
- How does the server handle an authenticated client's underlying account
  being deleted or suspended mid-session?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The server MUST accept incoming client connections and allow
  a client to register a unique nickname and associated user information
  before participating in chat.
- **FR-002**: The server MUST reject registration attempts that use a
  nickname currently claimed by another connected client, and MUST inform
  the requesting client clearly.
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
- **FR-009**: The server MUST support verifying a client's claimed account
  identity during connection registration via an authentication exchange,
  and MUST reflect authenticated status to other clients that request it.
  Authentication MUST remain optional per-connection at initial release:
  a client that does not authenticate MUST still be able to register a
  nickname and use standard chat functionality under core IRC behavior.
- **FR-010**: The server MUST reject invalid authentication attempts with
  a clear error and MUST NOT mark a session as authenticated when
  verification fails.
- **FR-011**: The server MUST allow an administrator to enable or disable
  individual optional feature modules via configuration, without requiring
  changes to the server's core codebase, and without requiring the running
  server process to be restarted for the change to take effect.
- **FR-012**: The server MUST report a clear, specific error when an
  administrator's configuration is invalid, rather than silently ignoring
  the problem or starting in an inconsistent state.
- **FR-013**: The server MUST support designated channel operators
  performing standard moderation actions, including removing a member from
  a channel and restricting who may send messages to it.
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
  single optional module fails to start or encounters a runtime error.
- **FR-021**: The server MUST operate as a complete, self-sufficient
  standalone deployment — a single instance MUST NOT require any other
  server instance to be present to serve clients. Server-to-server
  federation (linking multiple instances into one network) is out of
  scope for initial release, but the server's modular design MUST allow
  federation to be added later as an independent module without
  requiring a redesign of core connection, registration, or messaging
  behavior.
- **FR-022**: The server MUST verify account identities through a
  pluggable account-verification interface, allowing an administrator to
  configure it against an external, separately operated account system
  instead of (or in addition to) the server's own account store.
- **FR-022a**: The server MUST ship with a basic, built-in account module
  — a minimal account store covering account creation and credential
  verification — enabled by default, so the server is usable for
  authentication (Story 3) without requiring an external account system
  to be deployed first.
- **FR-023**: The server MUST offer exactly the following enhanced
  capabilities in its initial release, in addition to the authentication
  capability needed to support Story 3: structured message metadata
  (`message-tags`), accurate message timestamps (`server-time`), and
  delivery confirmation of a client's own sent messages (`echo-message`).
  Other enhanced capabilities (e.g., presence/away status, multi-device
  account awareness, batched message delivery, chat history replay) are
  out of scope for initial release and deferred to a later iteration.

### Key Entities

- **Client Session**: A single connected client's live state — its
  network connection, registration status, negotiated capabilities,
  authentication status, and channel memberships.
- **User Identity**: The persistent, human-recognizable identity a client
  presents (nickname, and optionally a verified account), independent of
  any single connection.
- **Channel**: A named, joinable group through which multiple clients
  exchange messages; tracks its membership list, operator/privilege
  assignments, and moderation settings.
- **Capability**: A named, independently negotiable protocol enhancement
  that a client may request; has an availability state (offered/not
  offered) determined by which modules are currently enabled.
- **Module**: An independently enableable/disableable unit of optional
  server functionality (e.g., a capability, a moderation tool, a command
  set) configured by the administrator.
- **Server Configuration**: The administrator-controlled settings
  determining which modules are active and how core and optional behavior
  is tuned.

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
- **SC-005**: An administrator can change which optional modules are
  active using configuration alone — no code changes, rebuild, or server
  restart — and observe the effect reflected for connected and new
  clients within 1 minute of applying the change.
- **SC-006**: During a sustained burst of excessive traffic from a single
  connection, message delivery latency for all other, well-behaved
  clients does not increase beyond the SC-002 target.
- **SC-007**: 100% of authentication attempts with valid credentials
  succeed, and 100% of attempts with invalid credentials are rejected,
  across repeated testing.
- **SC-008**: Administrators can identify the cause of a configuration
  error from the reported error message alone, without consulting source
  code, in under 5 minutes.

## Assumptions

- The implementation platform is Java, per explicit stakeholder direction;
  specific frameworks, libraries, and build tooling are determined during
  technical planning and are out of scope for this specification.
- "Modular" is interpreted as: optional functionality (capabilities,
  moderation tools, command sets) can be independently enabled, disabled,
  and configured by an administrator without modifying core server code
  and without restarting the running server process (FR-011). This
  applies to toggling existing modules on/off; whether entirely new,
  third-party modules can be installed at runtime without a restart is
  not required by this specification and is a planning-phase design
  choice.
- Authentication is optional per-connection by default (FR-009); an
  administrator MAY configure the server to require it, but that
  enforcement mode is a configuration concern, not a change to FR-009
  itself.
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
  FR-021); the modularity goal (Story 4) is expected to be the mechanism
  by which federation is added later, so federation readiness is a
  design constraint on the module system rather than a feature to build
  now.
