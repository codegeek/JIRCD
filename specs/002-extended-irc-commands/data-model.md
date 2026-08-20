# Phase 1 Data Model: Extended IRC Commands

This feature adds one new field to an existing entity and one new entity; everything else
(`VERSION`, `TIME`, `LUSERS`, `KILL`, `TAGMSG`) reads or acts on state that
`001-ircv3-server`'s data model (`specs/001-ircv3-server/data-model.md`) already defines —
see that document for `ClientSession`'s other fields, `Channel`, `UserIdentity`, and
`SupportedFeatures`, none of which are otherwise changed here.

## ClientSession — *addition to existing Aggregate Root, Session & Messaging*

One new field, following the same conventions as every existing `ClientSession` field
(`specs/001-ircv3-server/data-model.md` "ClientSession").

| Field | Type | Notes |
|---|---|---|
| `awayReason` | `Optional<String>`, absent by default | Set by `AWAY :<reason>` (FR-004), cleared by `AWAY` with no parameter (FR-005) or replaced by a new `AWAY :<reason>` (FR-006). Presence, not a separate boolean, is the away/not-away signal — the same pattern `ident`'s presence already uses as its own registration-state signal (`specs/001-ircv3-server/data-model.md` `ClientSession.ident`). Read by `PRIVMSG`/`NOTICE` delivery (FR-007), `WHOIS` (FR-008), and `WHO` (FR-009). Persists across a `NICK` change (FR-010); cleared only by explicit `AWAY` or disconnection — never by any other command. |

**Validation rules**:
- `awayReason`, when present, MUST be valid UTF-8, the same requirement every other
  human-readable field on this server already enforces (`001-ircv3-server` FR-054) —
  rejected the same way an invalid `PRIVMSG` body already is if it isn't.
- Setting `awayReason` while already away (FR-006) replaces the value in place; it is never
  additive and never requires first clearing it.

## WhowasEntry — *Value Object, Session & Messaging*

A snapshot of one disconnected session's last-known identity. Immutable once created —
disconnection is a point-in-time event, not a thing that changes afterward.

| Field | Type | Notes |
|---|---|---|
| `nickname` | string | The nickname this session held at the moment it disconnected; the lookup key `WHOWAS` matches against (case-insensitively, the same casemapping every other nickname comparison on this server already uses — `001-ircv3-server` FR-052). |
| `ident` | string | Same value `UserIdentity.username`/the wire hostmask's `ident` field held at disconnection (`001-ircv3-server` data-model.md `UserIdentity`). |
| `hostname` | string | The *real* hostname/IP, not a cloaked presentation — matching `WHOHOST`'s existing real-value-only convention (`001-ircv3-server` FR-032), since there is no longer a live session whose presented-vs-real distinction (FR-030/FR-038) could apply. |
| `realname` | string | Same value `UserIdentity.realname` held at disconnection. |
| `disconnectedAt` | instant | Used only to order entries newest-first within `WhowasHistory`; never itself displayed to a client. |

**Validation rules**:
- Created exactly once, at the moment a session transitions to `CLOSING`
  (`001-ircv3-server` FR-017's cleanup path), from that session's own then-current state —
  never retroactively edited.

## WhowasHistory — *Aggregate Root, Session & Messaging (server-scoped)*

The bounded, global store of `WhowasEntry` values. One instance, owned by the same
composition root that owns `NicknameRegistry`/`ChannelRegistry`
(`001-ircv3-server` data-model.md "Entity Relationships").

| Field | Type | Notes |
|---|---|---|
| `entries` | bounded ring buffer of `WhowasEntry`, capacity `ServerConfiguration.whowasHistorySize` | Global across all nicknames (research.md "WHOWAS bounded history store") — not one bucket per nickname. Newest entry evicts the oldest once at capacity, regardless of which nicknames either belongs to. |

**Validation rules**:
- A lookup for a nickname with no matching entry MUST be distinguishable from a nickname
  that merely isn't *currently* connected (`406 ERR_WASNOSUCHNICK`, distinct from `401
  ERR_NOSUCHNICK` — `contracts/irc-protocol-commands-extended.md`).
- A lookup for a nickname with multiple matching entries MUST return the one with the latest
  `disconnectedAt` — never an older entry while a newer one for the same nickname exists in
  the buffer.
- `whowasHistorySize` MUST be a positive integer; `ServerConfiguration` validation rejects a
  non-positive value at load time, the same "explicit, actionable validation error, not a
  silent default substitution" posture every other bounded numeric setting already uses
  (`specs/001-ircv3-server/contracts/server-configuration.md`).

## Entity Relationships (this feature's additions)

- `ClientSession.awayReason` is owned by `ClientSession` itself — no new relationship, just
  a new field on an existing aggregate.
- `WhowasHistory` is populated by the same FR-017 cleanup path already responsible for
  removing a disconnected session from `NicknameRegistry`/`Channel.members` — one additional
  step in that existing sequence (record a `WhowasEntry` before or as the session's other
  state is torn down), not a new trigger point.
- `WhowasHistory` has no relationship to `NicknameRegistry` beyond sharing the same
  nickname-comparison casemapping rule — a nickname appearing in both simultaneously is not
  a conflict (a currently-connected nickname can still have older `WhowasEntry` history from
  a previous holder, or its own earlier session, e.g. if the same client quit and reconnected
  under the same nickname before this data ages out).
