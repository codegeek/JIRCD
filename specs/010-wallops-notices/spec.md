# Feature Specification: Wallops Notices

**Feature Branch**: `[010-wallops-notices]`

**Created**: 2026-08-22

**Status**: Draft

**Input**: User description: "the irc server must have the wallops functionality. An admin must be able to send notices via the wallops command, and users must be able to set mode +w to receive those notices"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Administrator broadcasts an operational notice (Priority: P1)

An administrator needs to alert connected users about something time-sensitive (an upcoming restart, a service disruption, an abuse warning) without messaging each user individually. They issue a single wallops notice and it reaches every currently connected user who has opted in to receive such notices.

**Why this priority**: This is the core value of the feature — without the ability to send the notice, there is nothing to opt into. It delivers value on its own even before any user has opted in (the command works; delivery scope grows as opt-ins grow).

**Independent Test**: Can be fully tested by having an authenticated administrator send a wallops notice while one or more test users have opted in, and confirming those users receive the notice while non-opted-in users do not.

**Acceptance Scenarios**:

1. **Given** an administrator is connected with administrator privileges, **When** they send a wallops notice with a message, **Then** every currently connected user who has opted in to receive wallops notices receives that message.
2. **Given** an administrator sends a wallops notice, **When** no connected user has opted in, **Then** the command completes without error and no notice is delivered to anyone.
3. **Given** an administrator has opted in to receive wallops notices themselves, **When** they send a wallops notice, **Then** they also receive a copy of their own notice.

---

### User Story 2 - User opts in or out of receiving operational notices (Priority: P2)

A user wants control over whether they see server-wide operational notices. They can turn this on when they want visibility (e.g., they're a channel operator who wants a heads-up on server issues) and turn it off when they don't want the noise.

**Why this priority**: Opt-in control is what makes the broadcast in User Story 1 meaningful and respectful of user preference; without it, the feature would need to either spam everyone or nobody. It is independently testable and valuable as a preference toggle regardless of whether a broadcast happens during the test.

**Independent Test**: Can be fully tested by having a user enable the receive-notices preference, confirming it is reflected back when they check their own settings, then disabling it and confirming that too — with no broadcast needed to verify the toggle itself.

**Acceptance Scenarios**:

1. **Given** a connected user, **When** they enable the wallops-notices preference on their own connection, **Then** querying their own settings shows the preference as enabled.
2. **Given** a connected user with the preference enabled, **When** they disable it, **Then** querying their own settings shows the preference as disabled and they stop receiving new wallops notices.
3. **Given** a connected user, **When** they attempt to enable or disable the wallops-notices preference for a different user, **Then** the system rejects the attempt (the preference is self-only).

---

### User Story 3 - Non-administrator is prevented from broadcasting notices (Priority: P3)

A regular, non-administrator user attempts to send a wallops notice, whether by mistake or intentionally. The system must not let them broadcast to other users.

**Why this priority**: This is a security boundary rather than new capability — it protects the trust and usefulness of the broadcast channel established by User Story 1. It's lower priority only because the boundary is a natural consequence of the access check already required by User Story 1, but it is called out and independently testable to ensure it is not overlooked.

**Independent Test**: Can be fully tested by having a non-administrator connection attempt to send a wallops notice and confirming no user receives it and the sender is informed they lack the required privilege.

**Acceptance Scenarios**:

1. **Given** a connected user without administrator privileges, **When** they attempt to send a wallops notice, **Then** the notice is not delivered to any user and the sender receives an indication that they lack the required privilege.

---

### Edge Cases

- What happens when an administrator loses their administrator privileges mid-session and then attempts to send a wallops notice? The attempt MUST be treated the same as a non-administrator's attempt (rejected).
- What happens when a user who has opted in disconnects before a notice is sent? They simply do not receive it — no error occurs for the sender.
- What happens when an administrator sends a wallops notice with empty or whitespace-only message text? The system MUST reject it the same way it rejects other empty outgoing messages.
- What happens when a user who has opted in receives multiple wallops notices in quick succession? Each MUST be delivered independently, in the order sent.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST allow a connected user with administrator privileges to send a wallops notice consisting of a text message.
- **FR-002**: System MUST reject a wallops-notice attempt from any connected user who does not currently hold administrator privileges, and MUST inform that user their attempt was rejected due to insufficient privilege.
- **FR-003**: System MUST NOT deliver any part of a rejected wallops-notice attempt to other users.
- **FR-004**: System MUST allow any connected user to enable, on their own connection, a preference to receive wallops notices.
- **FR-005**: System MUST allow any connected user to disable, on their own connection, a preference to receive wallops notices.
- **FR-006**: System MUST prevent a user from changing this preference on behalf of any other user.
- **FR-007**: System MUST reflect the current state of a user's wallops-notice preference when that user queries their own connection settings.
- **FR-008**: System MUST deliver a wallops notice, at the time it is sent, to every currently connected user whose preference is enabled — including the sending administrator if their own preference is enabled.
- **FR-009**: System MUST NOT deliver a wallops notice to any connected user whose preference is disabled or has never been enabled.
- **FR-010**: System MUST identify the sending administrator to each recipient of a wallops notice.
- **FR-011**: System MUST complete a wallops-notice send without error when zero users currently have the preference enabled.
- **FR-012**: System MUST reject empty or whitespace-only wallops-notice message text, consistent with how other outgoing message text is validated.

### Key Entities

- **Wallops Notice**: A real-time, one-to-many operational message originated by an administrator, consisting of the sender's identity and the message text; it is not persisted or replayed to users who were not connected and opted in at the moment it was sent.
- **Wallops-Notice Preference**: A per-connection, self-controlled on/off setting belonging to a connected user that determines whether that user receives wallops notices while connected.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of currently connected, opted-in users receive an administrator's wallops notice within the same delivery time as a standard direct message.
- **SC-002**: 0% of wallops-notice attempts from non-administrators result in any user receiving a message.
- **SC-003**: 0% of connected users receive a wallops notice while their preference is disabled.
- **SC-004**: 100% of preference changes a user makes to their own wallops-notice setting are reflected immediately (visible on the very next query of that user's own settings, and effective for the very next notice sent).

## Assumptions

- Administrator privilege for this feature is the same administrator privilege already established elsewhere in the system (e.g., via existing authentication); this feature does not introduce a new or separate notion of "administrator."
- The wallops-notice preference is scoped to the current connection: it defaults to off and is not automatically enabled for administrators or carried over between separate connections/sessions.
- This feature covers a single running server instance; relaying wallops notices between multiple linked server instances is out of scope.
- Wallops-notice message content follows the same general text conventions (allowed content, reasonable length) as other outgoing real-time messages in the system.
- Wallops notices are real-time only: a user who was not connected and opted in at the moment a notice was sent has no way to retrieve it afterward.
