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
- Validated FR-043's extensibility promise against a concrete future
  case (invite-only, gating `JOIN`) and found it didn't actually hold:
  every enforcement rule built so far was hardcoded to `PRIVMSG`/`NOTICE`
  in `MessageCommandHandler`, with no equivalent check point in
  `JoinCommandHandler` — a future `JOIN`-gating flag would have required
  editing core code, exactly what FR-043 promises to avoid. Fixed by
  adding `ChannelMode.gates` (which command(s) a flag restricts,
  independent of `kind`) and generalizing enforcement to "iterate active
  flags gating this action," the same extension-contributes-a-hook
  pattern `CapabilityExtension` already uses. Confirmed two consequences
  worth keeping explicit: (1) most future `JOIN`-gating flags wouldn't
  need `Channel`'s shape to grow, since an extension can keep its own
  bookkeeping; (2) a paired command like `INVITE` needs no new mechanism
  either, since extensions can already register their own command
  handlers (`admin`/`cloak` already do). Also confirmed what's still
  unsolved: `private`/`secret` restrict `LIST`/`WHOIS` visibility, not a
  single gateable action, and remain deliberately uncataloged beyond
  their Full Channel Mode Catalog entry. No FR count change — this
  refines FR-043's existing scope rather than adding new normative
  behavior.
- Closed that gap: added FR-047 (`private`/`secret`, `MODE +p`/`+s`) —
  a non-member's `TOPIC`/`NAMES`/`LIST` on such a channel MUST be
  indistinguishable from the channel not existing (`403
  ERR_NOSUCHCHANNEL` for `TOPIC`/`NAMES`, silent omission from `LIST`),
  with an administrator bypass mirroring FR-032's existing hostname-
  cloaking transparency guarantee. Amended FR-040/FR-041/FR-042 to
  cross-reference the exception, since they previously promised
  unconditional non-member access with no carve-out. `ChannelMode.gates`
  gained a third value, `DISCOVER`, validating the mechanism generalizes
  beyond simple permit/deny (its failure mode is deliberately
  "respond as not-found," unlike `SEND`/`JOIN`'s explicit errors).
  `private`/`secret` are mutually exclusive per RFC 2811 (setting one
  clears the other) and, for this release, treated identically —
  documented as a deliberate simplification of `private`'s more
  historically inconsistent "listed but obscured" variant. 47 FRs total.
- Ran `/speckit-clarify`, which surfaced and resolved four real gaps:
  (1) FR-004 amended — channel-message sending never required membership
  by default, only `members-only` restricts it; the `PRIVMSG` contract
  row had been claiming unconditional membership all along, silently
  making that flag's default state meaningless. (2) FR-048 added —
  channel names never had a defined grammar the way nicknames did,
  despite an Edge Case already asking about it; now RFC 2812's `#`-led,
  50-char-max grammar, rejected with the existing (previously
  uncataloged-as-Used) `476 ERR_BADCHANMASK`. (3) FR-049 added — protocol
  line length was completely unbounded; now 512 bytes plus the IRCv3
  message-tags spec's required 4096-byte server-side tag allowance
  (FR-025), rejected under FR-015's existing malformed-message handling.
  (4) A max-connections Assumptions bullet — deliberately no server-level
  cap, an already-reasonable default made explicit rather than left
  silently unstated. Then fixed the contracts inconsistencies (1) and (2)
  actually named: `PRIVMSG`'s precondition column, `442`'s trigger
  description, a new "Channel Name Grammar" subsection (mirroring
  "Connection Registration Grammar"), and a line-length note in the
  contract's intro — plus the matching `tasks.md` tasks (T017, T021,
  T033, T066), all as text-only edits (no renumbering, no new IDs beyond
  the wire-protocol grammar work already folded into T017/T021). 49 FRs
  total.
- Replaced FR-049's generic `421`/`461` line-length rejection with `417
  ERR_INPUTTOOLONG` — a real, widely-adopted numeric filling an
  otherwise-unused gap in RFC 1459/2812's own numbering (415→421),
  purpose-built for exactly this case. The one deliberate exception to
  this project's "RFC 1459/2812 numerics only" catalog scope, alongside
  IRCv3's `CAP` numerics, since there's no existing RFC-standard
  alternative to reuse (unlike `476 ERR_BADCHANMASK`, which already
  existed for channel-name-grammar violations). Updated FR-049 to
  require a specific, distinct error without naming the numeric in
  spec.md itself, matching the established pattern (FR-002/FR-048 don't
  name `432`/`476` either — that detail lives in contracts).
- Closed a real gap: the "Connection Registration" contract had said
  "`001 RPL_WELCOME` and standard post-registration burst" since its
  first draft without ever defining what that burst actually contains —
  only `001` was ever marked `Used`. Investigating it surfaced a more
  foundational, previously-unaddressed gap: this project never defined
  *any* server-name concept, even though every numeric reply needs one
  as its message source, not just the registration burst. Added FR-050
  (administrator-configurable `serverName`, hostname fallback if unset —
  the server-side counterpart to FR-030's client hostmask) and FR-051
  (the burst itself: `001`/`002`/`003`/`004`/`422 ERR_NOMOTD`, `004`'s
  mode-letter lists sourced live from the `ChannelMode` catalog so they
  never drift from what `MODE` actually recognizes). Deliberately kept
  MOTD itself out of scope — `422` alone gives clients a defined
  burst-end signal without opening a real MOTD content/config surface
  this release doesn't need. New `Story1RegistrationBurstTest` (Story 1,
  renumbered — 125 tasks, sequential). 51 FRs total.
- Did a targeted audit of classic IRC protocol details prompted by one
  named example (a dot-free `serverName` being ambiguous with a
  nickname) and found four real gaps, the biggest being the most classic
  IRC implementation mistake there is: nickname/channel comparisons were
  never specified as case-insensitive anywhere in this spec. Fixed via
  FR-052 (RFC 2812 §2.2's "rfc1459" casemapping — ASCII fold plus
  `[]\^` ↔ `{}|~` — applied to FR-002/FR-003 uniqueness and every
  command that targets a nickname/channel; original casing is stored
  and displayed, only comparison folds case). Also fixed: (1) the named
  example itself — FR-050 amended to require `serverName` contain a
  `.`, rejected at config-load time if not, with the hostname fallback
  guaranteeing it too (a synthetic suffix if the host's own hostname
  lacks one) — nicknames can never contain a `.`, so this is what keeps
  a server-originated prefix unambiguous from a client one; (2) FR-015
  amended — command recognition MUST be case-insensitive
  (`join`/`JOIN`); (3) new FR-053 — numerics sent before a session has
  claimed a nickname (`431`/`432`/`433`) MUST address `*`, not an empty
  or unclaimed value. All four fixed across spec.md, data-model.md,
  research.md, contracts/, and tasks.md (text-only task edits, no
  renumbering — still 125 tasks). 53 FRs total.
- Ran `/speckit-clarify` again and closed the one remaining gap the scan
  found: message-text encoding was never addressed anywhere. Added
  FR-054 — `PRIVMSG`/`NOTICE` bodies, topics, realnames, and channel
  names MUST be valid UTF-8, rejected under FR-015's generic
  malformed-message handling (`421`, specifically — not `461`, since the
  parameter is present, just invalidly encoded, a different failure than
  a missing one) if not. Explicitly excludes nicknames and the `USER`
  command's `<user>` parameter, which already have their own
  ASCII-oriented grammars. Synced across data-model.md (`realname`,
  `OutboundMessage.body`, `Channel.topic`/`name`), contracts/ (new
  `Utf8Validator` referenced alongside `Hostmask`/`ChannelName`; channel
  names reuse `476` since a UTF-8 failure and a grammar failure are the
  same "not a legal channel name" case from the client's perspective;
  everything else reuses `421`, no new numeric invented since none
  exists in the wild to reuse the way `417`/`476` did), and tasks.md
  (T017, T066, T067, T069, T071, T021 — all text-only, still 125 tasks).
  54 FRs total.
- Implemented `RPL_ISUPPORT` (numeric `005`), resolving a deferral this
  project had explicitly left open earlier: "revisit if a future
  capability needs to advertise server limits/features this way"
  (irc-numeric-replies.md's original `005` note). FR-054's UTF-8
  enforcement was exactly that trigger — `UTF8ONLY` is the standard
  token for declaring it, and a server enforcing UTF-8 without
  advertising it leaves clients to discover that by trial and error.
  Added FR-055 and a new `SupportedFeatures` data-model concept (a
  computed, registration-time snapshot, not stored state) with a
  deliberately minimal, fixed token set — `CASEMAPPING`, `CHANTYPES`,
  `NICKLEN`, `CHANNELLEN`, `MODES`, `CHANMODES`, `PREFIX`, `UTF8ONLY` —
  every token restating a value already committed to elsewhere (FR-052
  casemapping, FR-048 grammar limits, FR-045/FR-046 prefixes), none a
  new setting. `CHANMODES` and `004`'s mode-letter list read the same
  live `ChannelMode` catalog, so they cannot disagree by construction.
  Tokens with no concrete answer in this spec (`TOPICLEN`, `CHANLIMIT`,
  `NETWORK`, `TARGMAX`) are deliberately omitted rather than sent with
  an invented value. Synced across spec.md (FR-055, FR-051 amended),
  data-model.md (new `SupportedFeatures` section), research.md (new
  "ISUPPORT / RPL_ISUPPORT" decision), contracts/ (`005`'s Reserved
  `RPL_BOUNCE`→Used-as-`RPL_ISUPPORT` resolution, Registration
  Completion Burst table), and tasks.md (T066, T058 — text-only, still
  125 tasks). 55 FRs total.
- Corrected a modeling mistake in `SupportedFeatures` caught immediately
  after introducing it: it was described as computed per `ClientSession`
  at registration time, mirroring `UserIdentity.presentedForm` — but
  every one of its tokens is either a fixed constant or derived from
  server-wide `ExtensionRegistry` state, none varies by session. Fixed
  to server-scoped: fixed tokens are constants; `CHANMODES`/`PREFIX`
  (the only tokens with anything to recompute) are recomputed on
  `ExtensionRegistry` state transitions, not on every registration —
  avoids SC-003's 1,000 concurrent connections each redundantly walking
  an unchanged `ChannelMode` catalog. Also fixed a latent contradiction
  this exposed: an earlier research.md "Alternatives considered" entry
  had argued *against* caching `CHANMODES` (in favor of reading it fresh
  every registration) — the opposite of the now-corrected design;
  rewritten to explain the reconsideration rather than leave the
  contradiction standing. New Foundational task (`ExtensionRegistry`
  owns `SupportedFeatures`) inserted before the `USER` handler that
  reads it; tasks.md renumbered — 126 tasks, sequential. No FR change
  (FR-055 already described *what* is advertised; this was a
  data-model/task-level correction of *when* it's computed). 55 FRs
  total.
