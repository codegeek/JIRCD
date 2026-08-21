# Phase 0 Research: Complete Core Protocol Exclusions

No new dependency, no new module — every decision here fills a mechanism the codebase already
reserved for it. Each finding was confirmed against jircd-core's actual current source before
this feature was specified (`CoreChannelModes.ALL`, `SupportedFeatures.formatChanModes()`,
`WhowasHistory`, `JoinCommandHandler`'s existing invite-exemption logic), not guessed at spec
time — this phase records the *fix* decision for each, not fresh investigation.

## Story 1 — Channel capacity and key access modes (FR-001 through FR-006)

**Decision**: `ChannelMode.Kind.VALUE` — currently a single enum value, unused by any of the
seven core modes and unhandled anywhere in `ModeCommandHandler` — splits into two kinds:
`VALUE_SET_ONLY` (a parameter is present when setting, absent when unsetting — `+l`'s shape) and
`VALUE_ALWAYS` (a parameter is present on both setting and unsetting — `+k`'s shape, since a
client must supply a key to identify *which* key it's clearing, the same "must name what you're
removing" reasoning `LIST`-kind `+b` already follows). Two new `CoreChannelModes` constants,
`USER_LIMIT` (`l`, `VALUE_SET_ONLY`, `gates: {JOIN}`) and `CHANNEL_KEY` (`k`, `VALUE_ALWAYS`,
`gates: {JOIN}`), join `CoreChannelModes.ALL`. `Channel` gains two new fields: `volatile int
memberLimit` (`0` = unset, mirroring `volatile String topic`'s `null`-means-unset shape but using
`0` since a real limit is always positive) and `volatile String key` (`null` = unset).
`ModeCommandHandler.applyChanges` gets two new branches alongside the existing `BOOLEAN`/`MEMBER`/
`LIST` ones: `VALUE_SET_ONLY` consumes a parameter only when `change.sign() == '+'` (parsing it as
the new limit; a non-numeric or non-positive value leaves `memberLimit` unchanged and the change
is simply not added to `applied` — no crash, no error reply, the same "silently ignore an invalid
value" outcome `irctest`'s own `testLimitInvalidValues` explicitly accepts as valid, one of four
server behaviors Modern IRC's docs list as acceptable; `-l` clears it with no parameter consumed,
`parameterConsumingCount` unincremented for the unset direction); `VALUE_ALWAYS` always consumes a
parameter (storing it as the key on `+`, clearing it on `-` — the removed key's value itself is
not compared against the parameter given, matching how `-b <mask>` doesn't require the mask to
currently match an existing ban either; no dedicated key-grammar validator is added, for the same
"silently ignore" reasoning `testKeyValidation` also explicitly accepts). `JoinCommandHandler.joinOne` gains two new gate checks, structured exactly like the
existing `passesBanGate`/`passesInviteOnlyGate`: a full channel rejects with `471
ERR_CHANNELISFULL` (`channel.members().size() >= channel.memberLimit()` when `memberLimit > 0`); a
keyed channel rejects with `475 ERR_BADCHANNELKEY` when the supplied key (parsed from `JOIN`'s
existing, already-present-but-previously-unenforced comma-separated key list, `005`'s FR-013)
doesn't match `channel.key()`.

**Decision (invite exemption, spec.md Clarifications)**: `JoinCommandHandler.passesInviteOnlyGate`
currently *consumes* the invitation (`channel.invited().remove(folded)`) as part of the check
itself — safe today because it's the only gate that reads `channel.invited()`. Extending the same
exemption to `+l`/`+k` (not `+b` — ban exemption from invite is the already-documented,
out-of-scope Ergo-specific `INVITE`-exempts-from-ban behavior) means three gates can now each want
to consult the same pending invitation. Refactor: `joinOne` computes `boolean invited =
CaseMapping.fold(session.nickname())` membership in `channel.invited()` *once*, via a
non-mutating `contains()`, before any gate runs; each of the three gates (`+i`, `+l`, `+k`) treats
`invited` as an unconditional pass; after every gate has passed, `channel.invited().remove(folded)`
runs exactly once (a no-op if `invited` was `false`). This guarantees a channel with more than one
of these three modes active doesn't have its invitation consumed by the first gate checked, then
fail the second for lack of one.

**Rationale**: `SupportedFeatures.formatChanModes()` already has two separate, empty local
variables — `alwaysParam` and `setOnlyParam` — each carrying a comment naming exactly this future
case (`"VALUE-kind requiring a parameter on both set and unset — none this release"` and
`"...only when setting — none this release"` respectively); the codebase's own architects reserved
this exact two-way split before either mode existed. Reusing `GateAction.JOIN` for both new modes
follows the same precedent `001-ircv3-server`'s own contract notes call out for `ban-mask`:
"`ban-mask` is that promise's first *real* fulfillment... `invite-only` remains a still-
unimplemented but structurally-identical case" — `+l`/`+k` are two more real fulfillments of the
same `JOIN`-gate mechanism, requiring no change to `JoinCommandHandler`'s own gate-check shape,
only two new gate methods following the two already there.

**Alternatives considered**: Representing `+l`/`+k` as a third, generic "value" storage map on
`Channel` (`Map<ChannelMode, String>`) instead of two dedicated fields — rejected; every other
mode-specific state on `Channel` (`bans`, `invited`, `operators`, `voiced`) is its own
dedicated, precisely-typed field, not a generic collection keyed by mode, and two fields is not
enough repetition to justify a more general mechanism this codebase doesn't otherwise have.
Making the invite-exemption check itself a shared `GateAction`-driven mechanism (a fourth
`GateAction`, e.g. `INVITE_EXEMPT`) — rejected; only three of the existing `JOIN`-gated modes
need this exemption and the fourth (`+b`) explicitly must not have it, so a blanket
`GateAction`-level mechanism would need its own per-mode opt-out anyway, no simpler than the
three explicit `invited ||` checks this decision uses.

## Story 2 — Topic-lock privilege (FR-007 through FR-009)

**Decision**: New `CoreChannelModes.TOPIC_LOCK` constant (`t`, `BOOLEAN`, `gates: {}` — empty,
since it's not enforced through the generic `GateAction` mechanism any more than `operator`/
`voice` are). `TopicCommandHandler.handle()`'s existing unconditional `if
(!channel.operators().contains(session))` check becomes `if
(channel.activeModes().contains(CoreChannelModes.TOPIC_LOCK) &&
!channel.operators().contains(session))`.

**Rationale**: `001-ircv3-server`'s own contract already calls out that this hardcoded,
always-on restriction was a deliberate simplification pending `+t`'s real implementation
("this project's FR-040 hardcodes operator-only topic-setting... not toggleable the way real
`+t` is") — this is that follow-through, a one-line condition change on an already-correct
check, not a new privilege model. `TOPIC_LOCK` isn't `GateAction`-gated because `TOPIC`-setting
isn't one of the three actions `GateAction` currently models (`SEND`/`JOIN`/`DISCOVER` — the
existing `DISCOVER` gate covers `TOPIC`-*viewing*, per `ChannelVisibility`'s own doc comment, not
`TOPIC`-*setting*); adding a fourth `GateAction` for a single flag with no other user would be
speculative generality this codebase's own constitution (Principle I, "no abstraction beyond
what the task requires") argues against.

**Alternatives considered**: Adding a `GateAction.TOPIC_SET` and routing the check through the
generic gate mechanism `ModeCommandHandler`/`ChannelVisibility` use — rejected per the
constitution's anti-speculative-generality principle; a direct, one-flag check in
`TopicCommandHandler` (the only place that needs it) is simpler and exactly matches how
`operator`/`voice` privilege checks are already done directly against `Channel.operators()`
rather than through a gate abstraction.

## Story 3 — Bare membership query (FR-010)

**Decision**: `NamesCommandHandler.handle()` gains a branch for `message.params().isEmpty()`
(replacing today's unconditional `461 ERR_NEEDMOREPARAMS`): loop `channelRegistry.all()`, and
for each channel where `!ChannelVisibility.isHiddenFrom(channel, session, extensionRegistry)`,
send that channel's `RPL_NAMREPLY` line (a new `JoinCommandHandler.sendNamesLine` static method,
extracted from the existing `sendNamesReply`'s first half — the `RPL_NAMREPLY`-only part, no
`RPL_ENDOFNAMES`); after the loop, send one final `366 RPL_ENDOFNAMES` with target `*` (the
conventional bare-query closing target, distinct from a specific channel name). The existing
`sendNamesReply` (used by `JOIN` and the single-channel `NAMES` form) is unchanged in behavior —
it now simply calls the new `sendNamesLine` internally, then sends its own `RPL_ENDOFNAMES` with
the specific channel name, exactly as it already did.

**Rationale**: `ChannelVisibility.isHiddenFrom` and `sendNamesReply`'s formatting logic
(`@`/`+` member prefixes, the `=`/`*`/`@` visibility symbol) are already exactly the pieces a bare
`NAMES` needs — reused verbatim, split only enough to avoid sending a `RPL_ENDOFNAMES` per
channel (which the single-channel form correctly does, but a bare query must not — spec.md's
"followed by the query's closing reply" is singular). Per spec.md's Clarifications, no additional
response-size limit or privilege gate is added — the existing per-connection rate limiter and the
private/secret visibility filter (both already in place, the latter reused directly by this
story) are the only mitigations, matching how most RFC-conformant servers handle this case and how
this codebase's own `LIST` command (already unbounded) already precedent-sets.

**Alternatives considered**: A configurable cap on the number of channels returned — rejected per
spec.md's clarified decision; would be a new, project-specific restriction mechanism this
codebase has no precedent for on any other potentially-large reply (`LIST`, `WHO` with a mask both
already return unbounded results).

## Story 4 — Server statistics completeness (FR-011, FR-012)

**Decision**: `LusersCommandHandler.handle()` adds two `Replies.send` calls: `252
RPL_LUSEROP <count> "operator(s) online"` (operator count via
`nicknameRegistry.all().stream().filter(s -> s.userModes().contains(UserMode.OPERATOR)).count()`
— the identical filtering shape the handler's own existing `invisibleCount` line already uses,
just against `UserMode.OPERATOR` instead of `UserMode.INVISIBLE`) inserted before the existing
`RPL_LUSERCLIENT`/`RPL_LUSERCHANNELS` pair or after (order matches conventional RFC 2812
ordering: `251`, `252`, `254`, `255` — `252` before `254`), and `255 RPL_LUSERME "I have
<clientCount> clients and 1 servers"` sent unconditionally, last.

**Rationale**: `002-extended-irc-commands`'s own contract already documents exactly why
`RPL_LUSEROP`/`RPL_LUSERME` were deferred ("no operator-vs-non-operator... breakdown to
report") — `UserMode.OPERATOR` didn't have a clear per-session tracking story to point to at the
time that contract was written; it now does (used throughout `WHOIS`/`WHO`/`MODE +o` since), so
the blocking condition no longer holds for these two specific numerics. `RPL_LUSERUNKNOWN`'s
blocking condition — no notion of a connection that hasn't yet become a full `ClientSession` —
is unrelated to operator-tracking and still holds exactly as documented; it stays reserved and
unsent, unchanged from `002`.

**Alternatives considered**: None — this is the minimal, direct fill of an already-scoped and
already-justified gap; no design space to explore beyond confirming `RPL_LUSERUNKNOWN` should
stay excluded (already covered above).

## Story 5 — Invitation to a not-yet-existing channel (FR-013)

**Decision**: `InviteCommandHandler.handle()`'s single combined check —
`if (found.isEmpty() || !found.get().members().contains(session))` sending `442
ERR_NOTONCHANNEL` either way — splits into two: if `found.isEmpty()` (channel genuinely doesn't
exist anywhere), skip straight to the existing target-lookup/already-on-channel checks below
(there is no `invite-only` mode or membership state to check against a channel that doesn't
exist, so those checks are vacuously satisfied); if `found` is present but `!members().contains
(session)`, keep the existing `442 ERR_NOTONCHANNEL` rejection unchanged. The rest of the method
(target-nickname lookup, already-on-channel check, `invite-only`-and-not-operator check, the
`RPL_INVITING` success reply, and the delivered `INVITE` message) is unchanged — it already
naturally handles a non-existent `Channel` object being absent by simply not running the
`invite-only`/membership checks that need one.

**Rationale**: RFC 2812 §3.2.7's own `INVITE` error list has no not-found-channel case at all
(`ERR_NEEDMOREPARAMS`, `ERR_NOSUCHNICK`, `ERR_NOTONCHANNEL`, `ERR_USERONCHANNEL`,
`ERR_CHANOPRIVSNEEDED`) — the RFC's own design already treats a not-yet-existing channel as a
non-error case for `INVITE`, distinct from `JOIN`'s `getOrCreate` semantics only in that `INVITE`
doesn't create the channel, it just doesn't need it to exist yet either. `irctest`'s own
`invite.py` was checked for a current (non-deprecated) test asserting the opposite (reject a
not-yet-existing-channel invite): none exists — the only channel-existence-adjacent case in that
file concerns an already-existing channel's `invite-only` gate, unaffected by this change. No
downgrade-to-intentional fallback (spec.md Assumptions) is needed; the change proceeds as
specified.

**Alternatives considered**: Auto-creating the channel on invite (mirroring `JOIN`'s
`getOrCreate`) — rejected; RFC 2812 doesn't ask for this, and it would let a client alter server
state (a new empty channel appearing) via a command that fundamentally doesn't add a member to
it, an inconsistent side effect this codebase's `ChannelRegistry.getOrCreate` is otherwise
reserved for actual membership changes only (`JoinCommandHandler` is its sole caller today).

## Story 6 — Former-nickname lookup count (FR-014, FR-015)

**Decision**: `WhowasHistory` gains a new method, `mostRecentNFor(String nickname, int count)`,
returning `List<WhowasEntry>` (most-recent-first) — implemented by the same case-folded linear
scan `mostRecentFor` already does, collecting every match into a list, sorting by
`disconnectedAt` descending, then truncating to `count` entries UNLESS `count <= 0`, in which
case every retained match for that nickname is returned unbounded (RFC1459 §4.5.3/RFC2812
§3.6.3's own text is explicit: *"If a non-positive number is passed as being `<count>`, then a
full search is done"* — Modern IRC's docs restate the same rule). `mostRecentFor(nickname)` keeps
its own narrower single-entry meaning (delegating to `mostRecentNFor(nickname,
1).stream().findFirst()`), used only where a genuine single-entry lookup is wanted — it is
deliberately NOT what `WhowasCommandHandler` calls for its own no-count-given path (see below).
`WhowasCommandHandler.handle()` reads an optional second parameter
(`message.params().size() > 1`) and parses it as an integer; a present-but-non-numeric value, an
entirely absent one, and an explicit non-positive one are ALL treated identically — full search
(`count = 0` passed to `mostRecentNFor`) — leniently satisfying the same "don't reject on a
malformed optional refinement, fall back to the broadest reasonable behavior" posture the RFC
text itself already mandates for the numeric-but-non-positive case, extended to the omitted case
too. **Correction during implementation**: this feature's own initial draft assumed an omitted
count should keep returning exactly one entry (preserving the pre-006 behavior verbatim) — this
was disproven by `irctest`'s own non-deprecated `whowas.py::testWhowasMultiple`, which sends
`WHOWAS nick2` with NO count parameter and still asserts BOTH retained entries come back. FR-015
and this decision were corrected to match once this was caught during the T026 irctest re-run,
before this feature's implementation was considered complete. The handler loops `RPL_WHOWASUSER`
once per returned entry (each resolving `hostname` via the identical FR-038
administrator-vs-presented check the pre-006 single-entry path already did) before the
unconditional closing `369 RPL_ENDOFWHOWAS` — confirmed against `irctest`'s own
`whowas.py::testWhowasMultiple`/`testWhowasCount1`/`testWhowasCount2`/`testWhowasCountNegative`/
`testWhowasCountZero`.

**Rationale**: `WhowasHistory` was already confirmed, by reading its actual source, to be a
single *global*, bounded ring buffer (`Deque<WhowasEntry>`, capacity-limited across *all*
nicknames combined, not one slot per nickname) — multiple entries for the same nickname are
already retained today, up to whatever the global capacity allows before older entries (of any
nickname) get evicted; `mostRecentFor` simply never looked past the first match. This resolves
spec.md's Assumptions-section conditional definitively: no widening of retention is needed, only
a new query method — the data was already there.

**Alternatives considered**: A per-nickname bounded history (e.g. `Map<String, Deque<WhowasEntry>>`
with its own per-nickname cap) — rejected; would be a data-model change the evidence above shows
is unnecessary, and would change existing, already-correct eviction behavior (today's global,
recency-based eviction across all nicknames) for no benefit FR-014 actually requires.
