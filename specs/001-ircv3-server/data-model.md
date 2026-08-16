# Phase 1 Data Model: Modular IRCv3 Chat Server

**Input**: [spec.md](./spec.md) Key Entities | **Research**: [research.md](./research.md)

All state below is in-memory only for this release (see plan.md "Storage").
Entities that exist only to support the deferred Story 3 (`Account`) are
noted but not modeled in detail here — they are out of scope for this plan.

Each entity below is tagged with its DDD role (**Aggregate Root**, Entity,
or Value Object) and the bounded context it belongs to (see plan.md "Domain
Model & Bounded Contexts"), so the data model and the domain model stay one
document, not two that can drift apart.

## ClientSession — *Aggregate Root, Session & Messaging*

A single connected client's live state, from TCP accept to disconnect. Its
`connectionId` is its identity; everything else about it is mutable state
guarded by this aggregate.

| Field | Type | Notes |
|---|---|---|
| `connectionId` | opaque identifier | Internal correlation key; never sent on the wire. |
| `channel` (I/O) | `SocketChannel` | The underlying connection (plaintext or TLS-wrapped, FR-018). |
| `outboundQueue` | bounded queue of `PendingDelivery` (below) | The *only* path by which any other session delivers a message to this one (FR-004 fan-out); drained exclusively by this session's own writer thread, per research.md "Message fan-out concurrency model". Never written to directly from another session's thread. Queue elements are **not** pre-formatted wire lines — this session's own writer thread does that at drain time, using this session's own `negotiatedCapabilities` (see `PendingDelivery`). |
| `registrationState` | enum: `CONNECTING`, `REGISTERED`, `CLOSING` | A session must reach `REGISTERED` (nickname + user info accepted, FR-001) before most commands are valid. |
| `nickname` | string, 0..1 | Present once registration succeeds; unique across all sessions (FR-002); MUST also conform to the nickname grammar (contracts/irc-protocol-commands.md "Connection Registration Grammar") — uniqueness and format are independent checks (`433` vs. `432`). |
| `negotiatedCapabilities` | set of `Capability` names | Populated via CAP negotiation (FR-006, FR-007); empty for clients that never negotiate (FR-008). |
| `channelMemberships` | set of `Channel` references | Channels this session has joined; drives cleanup on disconnect (FR-017). |
| `rateLimitBucket` | token bucket state | Per-connection (FR-016); see research.md "Rate limiting". |
| `ident` | string | Derived from the `USER` command's username field at registration (FR-030); not independently verified (see spec.md Assumptions re: RFC 1413). |
| `realHostname` | string | The connection's actual hostname/IP; always populated regardless of cloaking; source of truth for FR-032 (`WHOHOST`) and FR-038's self-lookup/administrator `WHOIS` cases. Never sent to a non-administrator client looking up a *different* client — see `UserIdentity.presentedForm` for the *display* value that case uses instead. |
| `administratorPrivilege` | boolean | Granted via FR-034's in-band credential command; authorizes FR-032 administrative commands. Independent of `channelMemberships`/operator status. |
| `lastLivenessAt` | instant | Updated whenever this connection is known to be alive — traffic received from it, or a `PONG` answering the server's own `PING`. Read by this session's `LivenessMonitor` (research.md "Connection keep-alive") to decide when to probe and when to time out (FR-039). |

**Validation rules**:
- `nickname` MUST be unique across all `ClientSession`s at the moment it is
  committed (FR-002); the commit MUST be atomic so no two sessions can hold
  the same nickname even under concurrent registration attempts.
- A session in `CONNECTING` state MUST reject channel/messaging commands
  that require `REGISTERED` (FR-001).
- `realHostname` MUST NOT be overwritten or cleared by a cloak extension —
  see research.md "Cloak extension boundary" for why the real value's
  source of truth lives on `ClientSession` itself, not in the cloak
  extension.
- `administratorPrivilege` MUST only be settable via FR-034's credential
  verification, never inferred from channel-operator status or any other
  field.
- `outboundQueue` reaching capacity (a member too slow to keep up with
  fan-out) MUST transition that session to `CLOSING` and run the same
  FR-017 cleanup as any other connection loss — a sender MUST NOT block
  waiting for a slow recipient's queue to drain (research.md "Message
  fan-out concurrency model").
- A `LivenessMonitor`-detected timeout (no traffic and no `PONG` within
  the configured window since `lastLivenessAt`) MUST transition that
  session to `CLOSING` and run the same FR-017 cleanup as any other
  connection loss (FR-039, research.md "Connection keep-alive") — a
  silently dead connection is not a special case distinct from a `QUIT`
  or a TCP-level close as far as cleanup is concerned, only in how it's
  detected.

**Lifecycle**: `CONNECTING` → `REGISTERED` → `CLOSING` (terminal; triggers
FR-017 cleanup: membership removal + notification to affected channels).
There is no path back from `CLOSING`.

## UserIdentity — *Value Object, Session & Messaging*

The nickname-level identity a session presents. Has no identity of its own
separate from its owning `ClientSession` — it's a computed presentation of
that session's state, not a thing you look up independently, which is what
makes it a Value Object rather than an Entity. For this release (Story 3
deferred), nothing survives a disconnect/reconnect except by the client
re-registering the same nickname (which succeeds only if no other session
currently holds it).

| Field | Type | Notes |
|---|---|---|
| `nickname` | string | See FR-002 uniqueness rule above. |
| `username` / `realname` | string | Supplied at registration (FR-001); not independently unique. |
| *(computed)* `presentedForm` | string | `nickname!ident@displayHostname` (FR-030) — `displayHostname` is `ClientSession.realHostname` unless the `cloak` `ServerExtension` is currently enabled — live-checked against `ExtensionRegistry` state at computation time, never cached — in which case it is that extension's obfuscated value (FR-031, research.md "Cloak extension boundary"). Never persisted; computed at send time so a mid-session extension toggle is reflected immediately. |

*(Deferred, not modeled here: linking a `UserIdentity` to a persistent
`Account` — see spec.md's Account entity, FR-023/FR-024/FR-026/FR-027.)*

## PendingDelivery — *Value Object, Session & Messaging*

The element type of `ClientSession.outboundQueue` (research.md "Message
fan-out concurrency model"). Represents one message on its way to one
recipient, holding only the parts that are the same for every recipient of
that message — never a finished wire line. It has no identity of its own
and is immutable once created, so the same instance can be safely shared
across every recipient's queue for a single fan-out (e.g., every member of
a channel a `PRIVMSG` was sent to).

| Field | Type | Notes |
|---|---|---|
| `senderPresentedForm` | string | The sender's `UserIdentity.presentedForm` at send time — resolved by live-checking the current `cloak` `ServerExtension` state at that moment (FR-031), never cached. Computed once by the sender's thread, not per recipient, since cloaking is a uniform display transform applied identically to every viewer — unlike a negotiated capability, no recipient-specific input ever factors into this value (see Validation rules below). |
| `command` | string | e.g., `PRIVMSG`, `NOTICE`, `JOIN`, `KICK` — which wire command this delivery represents. |
| `target` | string | Channel name or nickname the original command targeted. |
| `body` | string, 0..1 | The message text, where applicable (absent for e.g. a bare `JOIN`/`PART` notification). |
| `sentAt` | instant | Captured once, by the sender's thread, at the moment of sending — the value the `server-time` capability's `time` tag reflects for every recipient (not each recipient's own drain time). |

**Validation rules**: `PendingDelivery` MUST NOT contain a `message-tags`
prefix, a `time` tag, or any other capability-dependent decoration —
those are computed by each recipient's own writer thread at drain time
(research.md "Message fan-out concurrency model"), by re-checking that
recipient's `ClientSession.negotiatedCapabilities` against the live state
of the corresponding `CapabilityExtension` (`Capability` validation rules,
above). A `PendingDelivery` is capability-agnostic by construction — that
is what makes sharing one instance across recipients with different
negotiated capabilities safe and correct. Symmetrically, `senderPresentedForm`
MUST be resolved solely from the live `cloak` `ServerExtension` state at the
sender thread's send time — never from a cached hostname value, and never
influenced by any individual recipient's state (capabilities or otherwise).
That is what makes baking it once into a shared immutable `PendingDelivery`
correct rather than merely convenient: server-extension state is uniform
across every recipient, so resolving it once is equivalent to resolving it
per recipient, which is exactly the property capability state lacks.

## Channel — *Aggregate Root, Session & Messaging*

A named, joinable group through which members exchange messages. Its
`name` is its identity; membership and moderation state are guarded by
this aggregate (e.g., "first joiner becomes operator" is an invariant
enforced at channel-creation time, not left to callers).

| Field | Type | Notes |
|---|---|---|
| `name` | string | Unique across the server (FR-003); first JOIN of a name creates it. |
| `members` | set of `ClientSession` references | Current membership; drives message fan-out (FR-004). |
| `operators` | set of `ClientSession` references (subset of `members`) | Who may perform moderation actions (FR-013, FR-014). |
| `sendRestriction` | enum: `NONE`, `MEMBERS_ONLY`, `MODERATED` | `NONE`: anyone can send. `MEMBERS_ONLY`: only current `members` may send (non-members' `PRIVMSG` rejected, FR-013). `MODERATED`: only `operators` may send (matches classic IRC's `+m`). Set/cleared only by an operator via `MODE` (FR-013, FR-014). This is core moderation state (FR-036) — never gated by `Extension` state. |
| `topic` | string, 0..1 | Absent (no topic set) by default. Visible to any client via `TOPIC` regardless of membership (FR-041's discovery framing applies here too); settable only by an `operator` (FR-040). Distinct from `sendRestriction` — viewing/setting the topic is not a "who may send a `PRIVMSG`" concern. |

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
  `topic` is reset along with everything else — a recreated channel starts
  with no topic set, same as a brand-new one.
- `topic` MUST only be set by a session in `operators` (FR-040); a
  non-operator's attempt MUST be rejected with the same `482
  ERR_CHANOPRIVSNEEDED` error FR-014's other operator-gated actions use,
  not a new error of its own.

**Lifecycle**: created on first JOIN → members join/part → removed when
membership reaches zero (no persistence across recreation, per above).

## Capability — *Value Object, Capability Negotiation*

A named, independently negotiable IRCv3 protocol enhancement. Identified
by `name` alone within the capability catalog; carries no state of its own
beyond that name and its current availability, both derived from its
owning `CapabilityExtension` — it is not something with its own lifecycle
independent of that extension.

| Field | Type | Notes |
|---|---|---|
| `name` | string | One of `message-tags`, `server-time`, `echo-message` for this release (FR-025). |
| `available` | boolean | Derived from whether the owning `CapabilityExtension` is currently `ENABLED` (FR-007, FR-025). |

**Validation rules**: A capability's `available` state MUST be sourced from
its owning `CapabilityExtension`'s enabled/disabled state at request time
(FR-007's accept/decline response must reflect current, not stale,
availability). This live-check applies at **every** point the capability's
effect is used, not only at `CAP REQ` time: a
`ClientSession.negotiatedCapabilities` entry is not a permanent grant once
negotiated — each outgoing message is formatted by re-checking whether the
corresponding `CapabilityExtension` is currently `ENABLED` (`Extension`,
below), so disabling it stops its effect for already-connected sessions
immediately (SC-005), not only for future negotiations.
`negotiatedCapabilities` records what the client *asked for and was
granted at negotiation time* (so it isn't re-offered or silently
re-added); whether it's still honored on the wire is a separate, live
check.

## Extension — *Entity, Server Extensibility* (base type)

An independently enableable/disableable unit of optional server
functionality, per FR-011. Has identity (`id`) and mutable lifecycle state,
which is why it's an Entity rather than a Value Object. Two role
specializations exist — see research.md "Extension system" for why the
split is a compiler-enforced domain distinction, not just documentation:

- **`CapabilityExtension`**: also provides exactly one `Capability` and is
  therefore visible to clients via `CAP LS`. Lives under
  `jircd-capabilities/`. This release's set: `message-tags`, `server-time`,
  `echo-message`.
- **`ServerExtension`**: no client-negotiable `Capability`; administrator-
  only, a client never learns it exists. Lives under
  `jircd-server-extensions/`. This release's set: `cloak` (FR-031),
  `admin` (FR-032).

Channel moderation and the capability-negotiation mechanism are core,
always-present behavior (FR-035, FR-036) and are deliberately **not**
modeled as an `Extension` at all — there is no `Extension` instance for
them and no `id` an administrator could use to disable them.

| Field | Type | Notes |
|---|---|---|
| `id` | string | Stable identifier used in Server Configuration (FR-012). This release's full set: `message-tags`, `server-time`, `echo-message` (`CapabilityExtension`), `cloak`, `admin` (`ServerExtension`) — one Gradle subproject each. |
| `state` | enum: `ENABLED`, `DISABLED`, `FAILED` | `FAILED` = failed to start/errored at runtime without affecting other extensions (FR-020). |
| `providedCapability` | `Capability` name, 0..1 | Present only for `CapabilityExtension`; absent for `ServerExtension` (e.g., cloak, admin). |
| `extensionPoint` | string, 0..1 | Set only for extensions that supply a value core code consumes rather than just adding a capability (e.g., `cloak` claims `hostname-display`). `null` for extensions with no such claim. |

**Validation rules**: A transition to `DISABLED` or back to `ENABLED` MUST
NOT require restarting the server process (FR-011) and MUST take effect for
already-connected clients, not only new connections (SC-005). At most one
`ENABLED` extension MAY claim a given non-null `extensionPoint` at a time;
attempting to enable an extension whose `extensionPoint` is already claimed
by another currently-`ENABLED` extension MUST be rejected as a
configuration error naming both conflicting extension ids (FR-012), not
silently allowed to override the existing claim (research.md "Extension
system" — "Extension-point ownership"). Disabling an extension releases
any `extensionPoint` it held, in the same quiesced-then-release sequence
as any other disable (research.md "Extension system" — "Quiesce before
unload").

**Lifecycle**: `ENABLED` ⇄ `DISABLED` (administrator-driven, either
direction, no restart, quiesced per research.md); `ENABLED` → `FAILED`
(runtime error, isolated per FR-020) — a `FAILED` extension does not
auto-recover; it requires an administrator action (out of scope to specify
the exact recovery UX here).

## ServerConfiguration — *Aggregate Root, Server Extensibility (config) / Administration*

The administrator-controlled settings loaded at startup and re-applied on
extension state changes.

| Field | Type | Notes |
|---|---|---|
| `capabilityStates` | map of `CapabilityExtension` `id` → desired `state` | Config-file section `capabilities` (contracts/server-configuration.md). Source of truth the matching `Extension.state` is reconciled against (FR-011, FR-012). |
| `serverExtensionStates` | map of `ServerExtension` `id` → desired `state` | Config-file section `server-extensions`. Same reconciliation contract as `capabilityStates`, kept as a separate field because the two id spaces are validated against different `Extension` specializations (see Validation rules). |
| `listeners` | list of {port, tlsEnabled} | Plaintext and/or TLS listeners (FR-018 — both may coexist). |
| `rateLimit` | {bucketSize, refillRate} | FR-016; see research.md "Rate limiting". |
| `administratorCredentials` | list of {username, hashedPassword} | Verified by FR-034's in-band privilege command; hashed per research.md "Administrator credential storage" — never stored or logged in plain text. |

**Validation rules**: An invalid configuration (unknown extension id,
conflicting listener ports, malformed rate-limit values) MUST be rejected
with a specific, actionable error identifying the problem field (FR-012,
SC-008) rather than falling back to a partially-applied state. An id MUST
appear in the field matching its actual kind: a `CapabilityExtension` id
listed in `serverExtensionStates`, or a `ServerExtension` id listed in
`capabilityStates`, is itself an invalid-configuration case (a
section/kind mismatch, contracts/server-configuration.md), rejected the
same way — not silently accepted into the wrong field. The in-band
`EXTENSION` administrative command (FR-032) addresses `Extension.id`
directly and MAY resolve to either field without the caller having to
know which one; only the configuration *file* has two sections.

## Entity Relationships

```text
ClientSession *---1 UserIdentity      (nickname, scoped to the session)
ClientSession *---* Channel           (via channelMemberships / members)
Channel        1---* ClientSession    (via operators, subset of members)
CapabilityExtension 1---1 Capability  (providedCapability)
ClientSession  *---* Capability       (negotiatedCapabilities)
ServerConfiguration 1---* CapabilityExtension (capabilityStates)
ServerConfiguration 1---* ServerExtension     (serverExtensionStates)
ServerConfiguration 1---* administratorCredentials (FR-034)
ClientSession   1---1 realHostname    (always on core; FR-030/031's display
                                        value is computed, not a separate entity)
ClientSession   1---* PendingDelivery (outboundQueue; one shared instance may
                                        appear in many recipients' queues at once)
```
