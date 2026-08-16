# Phase 1 Data Model: Modular IRCv3 Chat Server

**Input**: [spec.md](./spec.md) Key Entities | **Research**: [research.md](./research.md)

All state below is in-memory only for this release (see plan.md "Storage").
Entities that exist only to support the deferred Story 3 (`Account`) are
noted but not modeled in detail here — they are out of scope for this plan.

## ClientSession

A single connected client's live state, from TCP accept to disconnect.

| Field | Type | Notes |
|---|---|---|
| `connectionId` | opaque identifier | Internal correlation key; never sent on the wire. |
| `channel` (I/O) | `SocketChannel` | The underlying connection (plaintext or TLS-wrapped, FR-018). |
| `registrationState` | enum: `CONNECTING`, `REGISTERED`, `CLOSING` | A session must reach `REGISTERED` (nickname + user info accepted, FR-001) before most commands are valid. |
| `nickname` | string, 0..1 | Present once registration succeeds; unique across all sessions (FR-002). |
| `negotiatedCapabilities` | set of `Capability` names | Populated via CAP negotiation (FR-006, FR-007); empty for clients that never negotiate (FR-008). |
| `channelMemberships` | set of `Channel` references | Channels this session has joined; drives cleanup on disconnect (FR-017). |
| `rateLimitBucket` | token bucket state | Per-connection (FR-016); see research.md "Rate limiting". |
| `ident` | string | Derived from the `USER` command's username field at registration (FR-030); not independently verified (see spec.md Assumptions re: RFC 1413). |
| `realHostname` | string | The connection's actual hostname/IP; always populated regardless of cloaking; source of truth for FR-032 admin lookups. Never sent to non-administrator clients directly — see `Channel`/message-delivery contracts for the *display* value. |
| `administratorPrivilege` | boolean | Granted via FR-034's in-band credential command; authorizes FR-032 administrative commands. Independent of `channelMemberships`/operator status. |

**Validation rules**:
- `nickname` MUST be unique across all `ClientSession`s at the moment it is
  committed (FR-002); the commit MUST be atomic so no two sessions can hold
  the same nickname even under concurrent registration attempts.
- A session in `CONNECTING` state MUST reject channel/messaging commands
  that require `REGISTERED` (FR-001).
- `realHostname` MUST NOT be overwritten or cleared by a cloaking module —
  see research.md "Cloak module boundary" for why the real value's source
  of truth lives on `ClientSession` itself, not in the cloak module.
- `administratorPrivilege` MUST only be settable via FR-034's credential
  verification, never inferred from channel-operator status or any other
  field.

**Lifecycle**: `CONNECTING` → `REGISTERED` → `CLOSING` (terminal; triggers
FR-017 cleanup: membership removal + notification to affected channels).
There is no path back from `CLOSING`.

## UserIdentity

The nickname-level identity a session presents. For this release (Story 3
deferred), a `UserIdentity` is scoped 1:1 to its current `ClientSession` —
there is no persistent account behind it, and nothing survives a
disconnect/reconnect except by the client re-registering the same nickname
(which succeeds only if no other session currently holds it).

| Field | Type | Notes |
|---|---|---|
| `nickname` | string | See FR-002 uniqueness rule above. |
| `username` / `realname` | string | Supplied at registration (FR-001); not independently unique. |
| *(computed)* `presentedForm` | string | `nickname!ident@displayHostname` (FR-030) — `displayHostname` is `ClientSession.realHostname` unless a cloak module is currently enabled, in which case it is that module's obfuscated value (FR-031, research.md "Cloak module boundary"). Never persisted; computed at send time so a mid-session module toggle is reflected immediately. |

*(Deferred, not modeled here: linking a `UserIdentity` to a persistent
`Account` — see spec.md's Account entity, FR-023/FR-024/FR-026/FR-027.)*

## Channel

A named, joinable group through which members exchange messages.

| Field | Type | Notes |
|---|---|---|
| `name` | string | Unique across the server (FR-003); first JOIN of a name creates it. |
| `members` | set of `ClientSession` references | Current membership; drives message fan-out (FR-004). |
| `operators` | set of `ClientSession` references (subset of `members`) | Who may perform moderation actions (FR-013, FR-014). |
| `restrictedSend` | boolean / mode flag | Whether only members (or only operators) may send, per FR-013's moderation actions. |

**Validation rules**:
- `name` uniqueness is enforced the same way as nickname uniqueness (single
  atomic namespace, FR-003).
- The first session to join a not-yet-existing channel is added to
  `operators` (classic first-join-gets-operator default, FR-013); this is
  the **only** operator-assignment rule in this release (FR-026/FR-027's
  account-based override is deferred).
- A channel with zero members is not a durable entity — the next JOIN of
  that name creates a fresh channel with default (empty) `operators`, per
  FR-003 (this release keeps no channel history/state after last-member-
  leaves, since Story 3's chathistory-adjacent capabilities are deferred).

**Lifecycle**: created on first JOIN → members join/part → removed when
membership reaches zero (no persistence across recreation, per above).

## Capability

A named, independently negotiable IRCv3 protocol enhancement.

| Field | Type | Notes |
|---|---|---|
| `name` | string | One of `message-tags`, `server-time`, `echo-message` for this release (FR-025). |
| `available` | boolean | Derived from whether the owning `Module` is currently enabled (FR-007, FR-025). |

**Validation rules**: A capability's `available` state MUST be sourced from
its owning module's enabled/disabled state at request time (FR-007's
accept/decline response must reflect current, not stale, availability).

## Module

An independently enableable/disableable unit of optional server
functionality (a `Capability` provider, cloaking, or in-band
administration), per FR-011. Channel moderation and the capability-
negotiation mechanism are core, always-present behavior (FR-035, FR-036)
and are deliberately **not** modeled as a Module at all — there is no
`Module` instance for them and no `id` an administrator could use to
disable them.

| Field | Type | Notes |
|---|---|---|
| `id` | string | Stable identifier used in Server Configuration (FR-012). This release's module set: `message-tags`, `server-time`, `echo-message` (capabilities), `cloak` (FR-031), `admin` (FR-032) — one Gradle subproject each under `jircd-modules/`. |
| `state` | enum: `ENABLED`, `DISABLED`, `FAILED` | `FAILED` = failed to start/errored at runtime without affecting other modules (FR-020). |
| `providedCapabilities` | set of `Capability` names | Empty for non-capability modules (e.g., cloak, admin). |

**Validation rules**: A transition to `DISABLED` or back to `ENABLED` MUST
NOT require restarting the server process (FR-011) and MUST take effect for
already-connected clients, not only new connections (SC-005).

**Lifecycle**: `ENABLED` ⇄ `DISABLED` (administrator-driven, either
direction, no restart); `ENABLED` → `FAILED` (runtime error, isolated per
FR-020) — a `FAILED` module does not auto-recover; it requires an
administrator action (out of scope to specify the exact recovery UX here).

## ServerConfiguration

The administrator-controlled settings loaded at startup and re-applied on
module state changes.

| Field | Type | Notes |
|---|---|---|
| `moduleStates` | map of module `id` → desired `state` | Source of truth Module.state is reconciled against (FR-011, FR-012). |
| `listeners` | list of {port, tlsEnabled} | Plaintext and/or TLS listeners (FR-018 — both may coexist). |
| `rateLimit` | {bucketSize, refillRate} | FR-016; see research.md "Rate limiting". |
| `administratorCredentials` | list of {username, hashedPassword} | Verified by FR-034's in-band privilege command; hashed per research.md "Administrator credential storage" — never stored or logged in plain text. |

**Validation rules**: An invalid configuration (unknown module id,
conflicting listener ports, malformed rate-limit values) MUST be rejected
with a specific, actionable error identifying the problem field (FR-012,
SC-008) rather than falling back to a partially-applied state.

## Entity Relationships

```text
ClientSession *---1 UserIdentity (nickname, scoped to the session)
ClientSession *---* Channel        (via channelMemberships / members)
Channel        1---* ClientSession (via operators, subset of members)
Module          1---* Capability   (providedCapabilities)
ClientSession  *---* Capability    (negotiatedCapabilities)
ServerConfiguration 1---* Module   (moduleStates)
ServerConfiguration 1---* administratorCredentials (FR-034)
ClientSession   1---1 realHostname (always on core; FR-030/031's display
                                    value is computed, not a separate entity)
```
