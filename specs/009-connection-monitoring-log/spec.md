# Feature Specification: Connection Monitoring Log

**Feature Branch**: `009-connection-monitoring-log`

**Created**: 2026-08-21

**Status**: Draft

**Input**: User description: "Log client connections for monitoring. Client connection ids must be unique, but also tokenized (no c1,c2,c3, etc), and consistent (server-sent ping must include token)" — amended: "Also set a configuration option for server-initiated ping command frequency, and set the default to 120 seconds"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Administrator reviews connection activity in a monitoring log (Priority: P1)

An administrator watching the server's logs sees a clear entry every time a client connects and every time a client's connection ends, each carrying a unique identifying token for that connection, so they can monitor connection activity (volume, origin, duration) without cross-referencing unrelated log output.

**Why this priority**: This is the entire point of the feature — without a dedicated, readable log of connection activity, there is nothing to monitor. Every other requirement in this feature exists to make this log trustworthy and useful.

**Independent Test**: Connect a client, exchange some traffic, and disconnect. Confirm a connect-event log entry and a disconnect-event log entry both appear, both referencing the same connection token.

**Acceptance Scenarios**:

1. **Given** the server is running, **When** a client successfully connects, **Then** a monitoring log entry is recorded identifying that connection by a unique token.
2. **Given** an active client connection, **When** that connection ends (client quits, times out, or is disconnected by the server), **Then** a monitoring log entry is recorded for that same token, including how long the connection lasted.

---

### User Story 2 - Administrator correlates a live keep-alive check with a logged connection (Priority: P2)

An administrator investigating a specific client's behavior in real time (e.g. via a packet capture or client-side debug output) can match the token in a server-sent keep-alive message against the token in the monitoring log, confirming both refer to the same connection.

**Why this priority**: This turns the monitoring log from a passive record into something usable for live troubleshooting — the same identifier a client can observe on the wire is the one an administrator sees in the log, closing the loop between "what the client sees" and "what the server recorded." Making the check's frequency configurable belongs to this same story: an administrator tuning how often that live correlation opportunity occurs (more frequent checks for tighter monitoring, less frequent for lower overhead) is adjusting the same mechanism, not a separate concern.

**Independent Test**: Connect a client, capture the token from its monitoring log entry, wait for (or trigger) a server-sent keep-alive check, and confirm the keep-alive message carries the identical token.

**Acceptance Scenarios**:

1. **Given** an active client connection with a known monitoring-log token, **When** the server sends that client a keep-alive check, **Then** the keep-alive message includes that exact token.
2. **Given** an administrator has not configured a keep-alive frequency, **When** the server runs, **Then** it checks each connection's liveness (and sends a keep-alive message if idle) every 120 seconds.
3. **Given** an administrator has configured a different keep-alive frequency, **When** the server runs, **Then** it uses that configured value instead of the default.

---

### User Story 3 - Connection tokens reveal nothing about server activity (Priority: P3)

Someone who observes one or more connection tokens (e.g. a client sees its own token on the wire) cannot use them to determine how many other clients are connected, in what order connections were accepted, or any other information about the server's overall activity.

**Why this priority**: This is a safety property, not new functionality — it ensures the switch away from the previous sequential scheme actually achieves its purpose (opacity) rather than just changing the token's appearance while leaving the same information leak in place.

**Independent Test**: Connect several clients in sequence and compare their tokens; confirm no arithmetic or lexical relationship reveals connection order or count.

**Acceptance Scenarios**:

1. **Given** multiple client connections accepted one after another, **When** their tokens are compared, **Then** no relationship between the tokens reveals the order or number of connections.

### Edge Cases

- What happens if writing a monitoring log entry fails (e.g. the logging subsystem is misconfigured)? The client connection MUST NOT be delayed, degraded, or refused because of it — monitoring is an observability concern, not a precondition for service.
- What happens to a connection's token if the connection is re-established (client reconnects after a drop)? A reconnect is a new connection and MUST receive a new, independent token — tokens are never reused or resumed.
- What happens if two connections are accepted at effectively the same instant? Their tokens MUST still be distinct — uniqueness cannot depend on timing.
- What happens if the configured keep-alive frequency is invalid (zero, negative, or absurdly large)? The system MUST reject it at startup with a clear, actionable error, the same way every other invalid Server Configuration value is already rejected — never silently substituting a default or starting in a degraded state.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST assign every accepted client connection a unique identifying token before any other connection-level processing occurs.
- **FR-002**: A connection's token MUST be generated so that no other connection's token, and no external observation of one token, allows predicting another connection's token, the order connections were accepted, or the total number of connections the server has handled.
- **FR-003**: The system MUST record a monitoring log entry when a client connection is accepted, identified by that connection's token.
- **FR-004**: The system MUST record a monitoring log entry when a client connection ends, identified by that same connection's token and including the connection's duration.
- **FR-005**: Every server-initiated keep-alive message sent to a client MUST include that connection's token as part of its content.
- **FR-006**: Connection monitoring log entries MUST be recorded through a facility distinct from the system's existing security-event log, since a routine connect/disconnect is not inherently a security event.
- **FR-007**: Generating a connection's token and recording its monitoring log entries MUST NOT block, delay, or cause failure of an otherwise-successful client connection.
- **FR-008**: A connection's token MUST remain constant for the entire lifetime of that connection — every log entry and keep-alive message referencing it uses the identical value.
- **FR-009**: The system MUST provide an administrator-configurable setting controlling how frequently it checks a connection's liveness (and, when idle, sends that connection a keep-alive message) — see FR-010 for its default.
- **FR-010**: When the keep-alive frequency setting is not explicitly configured, the system MUST use a default of 120 seconds.
- **FR-011**: An invalid keep-alive frequency value (e.g. zero, negative) MUST be rejected when the system starts, with an error identifying the offending setting — never silently ignored or substituted.

### Key Entities

- **Connection Token**: An opaque, unique value assigned to a client connection when it is accepted, used consistently in that connection's monitoring log entries and in every keep-alive message sent to it. Carries no information about connection order, count, or timing.
- **Connection Monitoring Log Entry**: A record of a single connection lifecycle event (connect or disconnect), identified by the connection's token, with a timestamp and — for a disconnect entry — the connection's duration.
- **Keep-Alive Frequency**: An administrator-configurable setting determining how often the system checks each connection's liveness and, when a connection has been idle that long, sends it a keep-alive message. Defaults to 120 seconds.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of accepted client connections produce a monitoring log entry containing that connection's token.
- **SC-002**: 100% of server-initiated keep-alive messages contain the exact same token as their connection's own monitoring log entry.
- **SC-003**: Across a single server run, no two connections are ever assigned the same token.
- **SC-004**: Given any set of connection tokens, no ordering or counting information about the underlying connections can be recovered from them.
- **SC-005**: Enabling connection monitoring introduces no observable change in how quickly a connection is accepted or in any client-visible behavior other than the token's own value.
- **SC-006**: With no explicit configuration, the server checks connection liveness every 120 seconds; an administrator can change that frequency without modifying code, and an invalid configured value prevents the server from starting rather than being silently ignored.

## Assumptions

- A connection-monitoring log entry records, at minimum, the connection token, the event type (connect or disconnect), a timestamp, and the client's remote address; a disconnect entry additionally records the connection's duration. Richer fields (e.g. an authenticated nickname once registration completes) are not required by this feature and may be added later without conflicting with it.
- The exact token format (length, encoding, generation source) is an implementation decision for the planning phase — this specification requires only that tokens be unique and opaque, not a specific representation.
- The monitoring log is written through the same general logging mechanism already used elsewhere in the system (structured, administrator-reviewable log lines), not a new, separate logging destination or storage system.
- "Unique" means unique among connections handled during a single continuous server run; tokens are not required to remain unique across a server restart.
- This feature is independent of and does not require the deferred account/authentication module — connection monitoring applies to every connection regardless of whether it later authenticates.
- "Keep-alive frequency" (FR-009/FR-010) refers specifically to how long a connection may sit idle before the server checks it and, if needed, sends a keep-alive message — not the separate internal timing of how promptly the server reacts to that threshold being crossed, nor how long it waits for a response before treating the connection as dead. Only the idle threshold itself becomes administrator-configurable; the other two remain implementation details.
