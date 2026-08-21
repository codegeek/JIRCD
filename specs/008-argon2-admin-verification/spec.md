# Feature Specification: Argon2 Administrator Credential Verification

**Feature Branch**: `008-argon2-admin-verification`

**Created**: 2026-08-21

**Status**: Draft

**Input**: User description: "Implement actual Argon2 verification for administrator credentials, closing a bug where ConfigurationLoader accepts $argon2-prefixed hashedPassword values as a recognized format but AdminCredentialVerifier can never verify them."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Administrator authenticates with an Argon2-hashed credential (Priority: P1)

An administrator configures their Server Configuration with an Argon2-hashed password for
their administrator credential (instead of bcrypt). When they issue the in-band
administrator-privilege command with the correct password, the server grants them
administrator privilege — exactly as it already does for a bcrypt-hashed credential today.

**Why this priority**: This is the entire point of the feature. Today, a configuration file
with an Argon2-hashed credential loads without any error, giving the administrator every
indication their setup is correct — and then the administrator-privilege command silently
and permanently fails for that account, with no diagnostic anywhere. That gap between what
the configuration format accepts and what the server can actually verify is a
confidence-breaking bug that must be closed for the feature to have any value at all.

**Independent Test**: Configure a Server Configuration with a single administrator
credential whose `hashedPassword` is a real Argon2 hash of a known password. Start the
server, issue the administrator-privilege command with that password, and confirm
administrator privilege is granted.

**Acceptance Scenarios**:

1. **Given** a Server Configuration with an administrator credential stored as a valid
   Argon2 hash, **When** the administrator issues the privilege command with the matching
   plaintext password, **Then** the server grants administrator privilege.
2. **Given** the same configuration, **When** the administrator issues the privilege command
   with an incorrect password, **Then** the server refuses the attempt the same way it
   already refuses an incorrect bcrypt password (clear rejection, logged as a
   security-relevant event, contributing toward the existing failed-attempt lockout).

---

### User Story 2 - Existing bcrypt-based administrators are unaffected (Priority: P2)

An administrator who already has a bcrypt-hashed credential configured continues to
authenticate exactly as before. Adding Argon2 support must not change bcrypt's own
behavior, performance, or error handling in any way.

**Why this priority**: Every administrator credential in production today is bcrypt-hashed.
A regression here — even a subtle one, such as a timing change or a different rejection
message — would be a more damaging outcome than the bug this feature fixes, since it would
break something that currently works.

**Independent Test**: Configure a Server Configuration with a bcrypt-hashed administrator
credential (as today), issue the administrator-privilege command with the correct and then
an incorrect password, and confirm both outcomes are unchanged from current behavior.

**Acceptance Scenarios**:

1. **Given** a Server Configuration with an administrator credential stored as a valid
   bcrypt hash, **When** the administrator issues the privilege command with the matching
   password, **Then** the server grants administrator privilege, identically to today.
2. **Given** the same configuration, **When** an incorrect password is supplied, **Then**
   the server refuses the attempt identically to today.

---

### User Story 3 - Malformed or unsupported hash values fail safely (Priority: P3)

If an administrator credential's stored hash is malformed, corrupted, or uses a hash format
the server does not actually support, an administrator-privilege attempt against that
credential is refused cleanly — never with a server-side crash, an unhandled exception, or
any behavior distinguishable by an attacker from "wrong password."

**Why this priority**: This is a safety net, not new functionality — it protects the two
higher-priority stories from regressing into an availability or information-disclosure
issue if a credential's hash ever ends up malformed despite passing configuration-load
validation.

**Independent Test**: Directly test the credential-verification path with a syntactically
invalid or truncated hash value and confirm the outcome is an ordinary refusal, not an
exception or crash.

**Acceptance Scenarios**:

1. **Given** an administrator credential whose stored hash is corrupted or truncated,
   **When** an administrator-privilege attempt is made against that credential, **Then**
   the attempt is refused the same way an incorrect password is refused — no crash, no
   uncaught exception, no distinguishing error message.

---

### Edge Cases

- What happens when the Server Configuration declares an Argon2 hash using a specific
  Argon2 variant (e.g., Argon2i or Argon2d) that this feature's verification does not
  support, even though it starts with the currently-accepted `$argon2` prefix? → Configuration
  loading MUST reject it at startup with a specific, actionable error naming the
  unsupported variant, the same fail-fast posture the configuration loader already uses for
  every other invalid value — never accepted and left to fail silently later.
- What happens when the same configuration file mixes bcrypt-hashed and Argon2-hashed
  administrator credentials for different usernames? → Both must verify correctly and
  independently; the hash format is a per-credential property, not a server-wide setting.
- What happens when an Argon2-hashed credential's password is supplied correctly but the
  hash's own embedded cost parameters (memory/iterations/parallelism) are unusually
  expensive? → Out of scope for this feature to police; the administrator who configured
  the hash controls its cost parameters, the same way they already control bcrypt's work
  factor today (Assumptions).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The server MUST verify an administrator credential stored as an Argon2 hash
  against the password supplied via the administrator-privilege command, granting
  administrator privilege when the password matches.
- **FR-002**: The server MUST continue to verify administrator credentials stored as bcrypt
  hashes exactly as it does today — no observable change in behavior, success/failure
  outcome, or logged events for bcrypt-hashed credentials.
- **FR-003**: An administrator-privilege attempt against a credential whose stored hash is
  malformed, corrupted, or not a supported format MUST be refused the same way an incorrect
  password is refused (fail closed) — the server MUST NOT crash or allow an unhandled error
  to propagate to the client connection.
- **FR-004**: Configuration loading MUST only accept Argon2 hash values using the specific
  Argon2 variant(s) this feature actually verifies; an Argon2 hash using an unsupported
  variant MUST be rejected at configuration-load time with a specific, actionable error
  naming the problem — never accepted and left to fail only when an administrator later
  attempts to use it.
- **FR-005**: The existing per-connection failed-attempt lockout for the
  administrator-privilege command MUST apply identically regardless of whether the
  credential being checked is bcrypt- or Argon2-hashed.
- **FR-006**: No error message, log entry, or client-visible response produced during
  administrator-privilege verification MUST ever include the supplied plaintext password or
  the credential's raw hash value.

### Key Entities

- **Administrator Credential**: Unchanged in shape (username + a stored password hash) —
  this feature changes what hash formats the server can actually verify, not the entity's
  structure. A credential's hash format (bcrypt vs. Argon2) is inherent to the stored hash
  value itself and requires no new field.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An administrator who configures an Argon2-hashed credential successfully
  gains administrator privilege on the first attempt with the correct password, with no
  additional configuration steps beyond what a bcrypt-hashed credential already requires
  today.
- **SC-002**: Every administrator-authentication scenario that succeeds today with a
  bcrypt-hashed credential continues to succeed after this change, with no observable
  difference in outcome.
- **SC-003**: An incorrect password against an Argon2-hashed credential, or a malformed
  hash value, is always refused cleanly — zero crashes, hangs, or unhandled exceptions
  across repeated attempts.
- **SC-004**: A Server Configuration file containing an unsupported or malformed
  `hashedPassword` value is rejected at server startup, before the server accepts any
  connections — never silently accepted only to fail later at authentication time.

## Assumptions

- The Argon2 variant this feature supports is Argon2id — the variant current password-hashing
  guidance (e.g., OWASP) recommends as the general-purpose default, and the natural
  counterpart to bcrypt's own single well-understood mode. Support for Argon2i/Argon2d is
  out of scope unless a future need is identified.
- `AdminCredentialVerifier` is used exclusively by the administrator-privilege command
  (`OPER`) — the deferred account/SASL credential module (FR-023/FR-024 of
  `001-ircv3-server`, not yet implemented) is a separate, currently-nonexistent code path
  and is unaffected by this feature.
- Choosing and adding an Argon2 library dependency is an implementation detail for the
  planning phase, subject to the same "small, well-reviewed library" standard the project
  already applied when choosing its bcrypt dependency.
- Administrators are responsible for choosing their own Argon2 cost parameters
  (memory/iterations/parallelism) when generating a hash, the same way they already choose
  bcrypt's work factor today — this feature does not enforce a minimum or maximum cost.
