# Specification Quality Checklist: Modular IRCv3 Chat Server

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-08-15
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- Items marked incomplete require spec updates before `/speckit-clarify` or `/speckit-plan`
- All [NEEDS CLARIFICATION] markers resolved via `/speckit-clarify` across
  sessions on 2026-08-15. See spec.md § Clarifications for full detail.
- User Story 3 (authentication) and everything that exists solely to
  support it (FR-009, FR-010, FR-023, FR-024, FR-026, FR-027, SC-007, the
  Account entity) are explicitly deferred — not required for the initial
  release. The mandatory initial-release scope is Stories 1, 2, and 4
  (plus Story 5, which does not depend on
  the account module).
- Federation is no longer specified as fitting the FR-011 module
  abstraction (FR-021 revised, FR-022 added). It is deferred and its
  eventual extension mechanism is left to future planning; the spec only
  constrains how nickname/channel uniqueness, channel message delivery,
  and connection-loss handling are implemented now so that door stays
  open.
- Two additional standing constraints on a future federation effort were
  added: module consistency across all linked servers (FR-028) and a
  single authoritative account source shared network-wide (FR-029).
  Neither applies to the initial, standalone release.
- Added during planning (not deferred): standard `nickname!ident@hostname`
  identity presentation with an optional cloaking module and mandatory
  administrator visibility of real hostnames (FR-030/FR-031), and a new
  mandatory User Story 6 for in-band IRC administrative commands
  (FR-032/FR-033/FR-034, SC-009) as an access path alongside the existing
  configuration-file path (Story 4).
- Clarified module boundary: channel moderation and the capability-
  negotiation mechanism are core, always-on protocol behavior, not part of
  the optional/toggleable module surface (FR-035, FR-036 added; Story 4,
  FR-028, Key Entities "Module", and Assumptions updated to remove
  "moderation tools" from the optional-module examples).
- Terminology pass (DDD alignment with planning): the generic entity
  "Module" is renamed "Extension" throughout the spec's normative text
  (Story 4/6, FR-011/012/020/021/022/028/031/032/035/036, Key Entities,
  SC-005/SC-009, Assumptions) to match the code's domain model
  (plan.md "Domain Model & Bounded Contexts") — IRCv3's own term for this
  concept, and now used consistently by administrators, the spec, and the
  code. The historical Clarifications Q&A log (dated bullets) and every
  "account module" reference (Story 3's distinct, still-undesigned future
  subsystem) were deliberately left as-is.
- Added mandatory User Story 7 (WHOIS-equivalent user lookup, priority
  P2) with FR-037/FR-038 and SC-010: self-lookup and administrator
  lookup always return the real hostname/IP; a non-administrator looking
  up a *different* client only ever gets the presented (cloak-affected)
  value, reusing FR-030/031/032/033's existing display-vs-real model
  rather than introducing a new one.
- Closed a completeness gap: `PING`/`PONG` connection keep-alive was
  already claimed as "Implemented" in the wire-protocol contract's Full
  Command Catalog, but no functional requirement, contract detail, or
  task backed that claim. Added FR-039 (silent-connection detection via
  a bounded liveness probe, feeding the existing FR-017 disconnect-
  cleanup path; also requires replying to a client-initiated probe), a
  new Edge Case distinguishing a fully silent connection from the
  already-covered abrupt-TCP-close case, and an Assumptions bullet
  fixing keep-alive timing as a reasonable default rather than an
  administrator-configurable Server Configuration setting.
- Moved `TOPIC`/`NAMES`/`LIST` from "Recognized only" into User Story 1's
  scope (FR-040/FR-041/FR-042): any client may view a channel's topic or
  query its membership/the server's active-channel list without being a
  member (a discovery operation, like `WHOIS`), but only a channel
  operator may set the topic — reusing FR-013's existing operator concept
  rather than introducing a new authorization mechanism. Added `Channel.topic`
  to the data model and two new Acceptance Scenarios to Story 1.
- Closed a spec-coverage gap for user/channel modes: the wire-protocol
  contract already noted channel `MODE` as partially implemented and user
  `MODE` as "Recognized only," but spec.md never acknowledged either as a
  deliberate scope decision — unlike `AUTHENTICATE`/SASL and `NickServ`/
  `ChanServ`, which are explicitly documented as deferred. Added FR-043
  (channel `MODE` scoped to exactly FR-013's two flags; anything else,
  including a bare mode query, rejected with the same specific error) and
  FR-044 (user modes entirely unimplemented, not partially like channel
  `MODE`), an Edge Case, and an Assumptions bullet giving both the same
  deferred-scope treatment `AUTHENTICATE`/`NickServ`/`ChanServ` already
  get. No behavior changed — this release's actual `MODE` implementation
  (Story 5) is unaffected; only the spec's honesty about what's
  deliberately out of scope changed.
- Corrected a real data-model flaw surfaced by the mode-coverage review
  above: `Channel.sendRestriction` was a closed 3-value enum, which both
  blocked the FR-043 extensibility just added (a future account-module
  extension wanting a registered-channel flag would have forced a core
  code change) and was already subtly wrong on its own terms
  (`MEMBERS_ONLY`/`MODERATED` are independent flags on real IRC servers,
  not mutually-exclusive states). Replaced with `Channel.activeModes: Set
  <ChannelMode>`, a new Value Object naming a flag and its defining
  source (core, unconditionally, for the two built-in flags; optionally a
  `ServerExtension` for any future one, via a new `contributedChannelModes`
  field on `Extension`). Rewrote FR-043/FR-044 to require this
  extensibility explicitly rather than only stating a scope boundary.
- Validated a follow-up data-model trade-off: `ChannelMode` identified
  only by `flag` + `definedBy` (no separate name) vs. giving it its own
  stable `id`, matching `Extension.id`/`Capability.name`. Chose `id` —
  the wire-letter namespace (52 characters, shared by every current and
  future flag) is too scarce and too illegible to double as identity for
  actionable conflict errors (constitution Principle III). Surveying the
  rest of RFC 2811's standard channel modes in the process surfaced a
  more important finding: most of them (`l` user-limit, `k` channel-key,
  `b` ban-mask, `o`/`v` operator/voice) aren't simple on/off flags —
  `Channel.activeModes: Set<ChannelMode>` can't represent a value or a
  list. Added `ChannelMode.kind` (`BOOLEAN`/`VALUE`/`LIST`/`MEMBER`) to
  classify this explicitly rather than let it surface as a surprise gap
  later, added a "Full Channel Mode Catalog" to contracts (mirroring the
  Full Command/Numeric catalogs) cataloging all eleven RFC 2811 modes,
  and fixed a related pre-existing gap: `members-only`'s wire letter had
  never actually been pinned to `n` anywhere. Also fixed a misplaced
  `Channel` lifecycle note that had been left stranded under the
  `ChannelMode` heading by an earlier edit.
- Validated (and found incomplete) `moderated` mode's definition: it was
  specified as "operators only may send," but standard IRC's `+m` is
  "operators **and voiced members** may send" — a real correctness gap in
  an already-`Implemented` feature, not a deferred-scope one. Confirmed
  mode-*setting* was already correctly operator-gated throughout (FR-013/
  FR-014, and structurally via a `Channel.activeModes`-level rule that
  already covers any future extension-contributed flag too, not just the
  two named ones). Added FR-045 (voice grant/revoke, restricted to
  operators; moderated-mode's complete op-or-voice definition), a new
  `Channel.voiced` field, promoted `voice` (`v`) from Reserved to
  Implemented in the Channel Mode Catalog, added `MODE +v`/`-v
  <nickname>` to the contract (`441 ERR_USERNOTINCHANNEL` for a
  non-member target), and closed a related visibility gap: `353
  RPL_NAMREPLY` never documented the standard `@`/`+` operator/voice
  nickname-prefix convention clients rely on to show the privilege at
  all — without it, voice would have been correctly enforced but
  invisible in any real client.
- Extended operator status the same way voice was extended: added FR-046
  (an existing operator MAY grant/revoke operator status on another
  member, or revoke their own, via `MODE +o`/`-o <nickname>`) alongside
  first-join-gets-operator (FR-013), which remains the only path to a
  channel's *first* operator. Reused the existing `Channel.operators`
  field (no new field needed, unlike `voiced`); flipped `operator`'s
  Channel Mode Catalog entry from Reserved to Implemented and corrected
  two notes elsewhere (research.md, contracts/irc-protocol-commands.md)
  that had explicitly and correctly stated, before this change, that
  `operator` had no `MODE` mutator — now stale and rewritten. Fixed a
  data-model claim that first-join was "the only operator-assignment
  rule in this release," now scoped to "the only *initial* assignment
  rule," and documented the resulting edge case: an existing channel can
  end up with zero operators if every operator revokes their own status
  (or leaves) without granting a successor — no automatic reassignment,
  by design, mirroring how first-join only applies at channel creation.
