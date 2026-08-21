# Phase 0 Research: Bare Channel Mode Query

No new dependency, no new module — every decision here reuses a mechanism `001`/`006` already
established. This phase resolves the three open design questions the feature request itself
flagged as unresolved, each against concrete source evidence read before this document was
written.

## Mode string composition and scope boundary (FR-001 through FR-004)

**Decision**: A new private method on `ModeCommandHandler`, `sendChannelModeIs(session, channel)`,
replaces the current bare-query branch. It iterates
`extensionRegistry.recognizedChannelModes(CoreChannelModes.ALL)` — the identical collection and
helper `SupportedFeatures.formatChanModes()` already iterates for `CHANMODES` — skipping any
`LIST`- or `MEMBER`-kind flag entirely. For each remaining flag: a `BOOLEAN`-kind flag contributes
its letter to the mode string only when `channel.activeModes()` contains it; `VALUE_SET_ONLY`
(`l`) contributes its letter plus `String.valueOf(channel.memberLimit())` as a trailing value only
when `memberLimit() > 0`; `VALUE_ALWAYS` (`k`) contributes its letter plus `channel.key()` only
when `key() != null`. If the resulting letter string is empty, it's still prefixed with `+` alone
(FR-004) — the `+` is prepended unconditionally, before the loop, not appended after checking
whether anything was added. The reply is built as `Replies.send(session, serverName, 324,
channel.name(), modeString, ...values)` — `Replies.send` already prepends the requester's own
target automatically, so the trailing params here are exactly `[channel name, mode string,
value...]`, matching `irctest`'s own asserted shape (`params=["chanop", "#chan",
ListRemainder(ANYSTR, min_length=1)]` — the target `chanop` is the auto-prepended one).

**Rationale**: Special-casing `VALUE_SET_ONLY`/`VALUE_ALWAYS` by reading `Channel.memberLimit()`/
`Channel.key()` directly mirrors the *identical* special-casing `ModeCommandHandler.applyChanges`
already does for `+l`/`+k` (`006-complete-core-protocol` research.md "Story 1") — no new
"generic value-kind mode value getter" abstraction is invented; this release has exactly two
`VALUE`-kind flags, both `CORE`-defined with their own dedicated `Channel` fields, the same as
every other kind already has (`bans`/`invited`/`operators`/`voiced`, per `001`'s own data-model.md
pattern of "each kind gets its own dedicated storage"). Excluding `LIST`/`MEMBER`-kind flags
matches Modern IRC's own documented reply shape (cited in `irctest`'s test docstring) and this
server's own existing convention: `b` (ban masks) already has its own dedicated query form
(`MODE #chan +b`/`"b"`, `005-fix-batch-conformance` FR-016), and `o`/`v` (per-member privileges)
are already fully exposed via `NAMES`'s `@`/`+` prefixes — duplicating either into this
channel-level summary would be a second, redundant source of the same information.

**Alternatives considered**: Including `LIST`/`MEMBER`-kind state in the `324` reply (e.g.
appending ban-mask count, or operator/voice nicknames) — rejected; Modern IRC's own spec doesn't
call for it, `irctest`'s test doesn't check for it, and it would duplicate `MODE +b`/`NAMES`'s own
existing, dedicated purpose. A generic `Map<ChannelMode, Supplier<String>>` value-lookup
abstraction on `Channel` instead of two direct field reads — rejected as premature generality for
exactly two concrete cases, the same reasoning `006`'s own research.md already applied to reject a
generic `Map<ChannelMode, String>` alternative for `+l`/`+k` storage itself.

## Query access: no operator privilege, private/secret visibility (FR-005, FR-006)

**Decision**: No handler restructuring needed for FR-005 — `ModeCommandHandler.handle()`'s
existing whole-command operator-privilege check (`if (!channel.operators().contains(session))
{ ... 482 ... }`) already runs *after* the bare-query branch (confirmed via source read: the bare
ban-list query, `+b`/`"b"`, already exploits this same ordering, per `005-fix-batch-conformance`
FR-016's own precedent) — the new `sendChannelModeIs` branch simply needs to `return` before ever
reaching that check, exactly like the existing `sendBanList` branch already does. FR-006 adds one
new check inside `sendChannelModeIs` itself, at its very top:
`if (ChannelVisibility.isHiddenFrom(channel, session, extensionRegistry)) { ... 403
ERR_NOSUCHCHANNEL ... return; }` — the identical check and identical "hidden = indistinguishable
from nonexistent" convention `NamesCommandHandler`/`TopicCommandHandler` already use for their own
view-only queries.

**Rationale**: `TOPIC`'s own contract already establishes the precedent this feature reuses
directly: viewing requires no special privilege beyond the channel being visible at all; only
*setting* is operator-gated (`001-ircv3-server` "Channel Operations"). A bare `MODE #channel`
query is exactly the same shape of read-only request `TOPIC`'s query form already is — reusing the
identical gate rather than inventing a new privilege tier for read-only channel-settings access.
The channel-lookup step earlier in `handle()` (`found.isEmpty()` → 403) deliberately stays
unchanged — it doesn't consult `ChannelVisibility` today for *any* `MODE` path (setting included),
and this feature's own scope boundary (spec.md Assumptions: "`MODE #channel <flag>` ... is
entirely unaffected") means the visibility check belongs narrowly inside the new query branch, not
hoisted into the shared channel-lookup step where it would also affect flag-setting attempts —
that's a separate, out-of-scope question this feature doesn't reopen.

**Alternatives considered**: Adding the `ChannelVisibility` check to the shared channel-lookup step
so it applies to *every* `MODE` path, not just the bare query — rejected; out of this feature's
explicit scope (spec.md Assumptions), and changing how a `private`/`secret` channel's flags may be
*set* is a different, unrequested question with its own privilege implications not analyzed here.

## Channel creation time (FR-007, FR-008)

**Decision**: `Channel` gains one new field, `private final Instant createdAt = Instant.now();` —
a field initializer, not a constructor parameter, evaluated once per `Channel` object at
construction. `sendChannelModeIs` sends a second reply,
`Replies.send(session, serverName, 329, channel.name(),
String.valueOf(channel.createdAt().getEpochSecond()))`, immediately after the `324` reply.
`RPL_CHANNELCREATED` is added to `NumericReply` as `RPL_CHANNELCREATED(329)`, inserted between the
existing `RPL_UNIQOPIS(325)` and `RPL_NOTOPIC(331)` entries — confirmed via source read that
326–330 are all currently unreserved, so `329` has no conflict with anything already claimed.

**Rationale**: A field initializer needs no new constructor parameter and, being `final`, can
never drift from the moment of construction — the same reasoning `topic`/`memberLimit`/`key`
(all mutable, reset via a *fresh* `Channel` object on recreation, `006-complete-core-protocol`
data-model.md) already rely on for FR-008's reset-on-recreation requirement: since
`ChannelRegistry.getOrCreate` always constructs a genuinely new `Channel` instance for a
zero-member channel being recreated (confirmed via source read, `computeIfAbsent` on a fresh
`new Channel(name)`), a fresh field initializer naturally reflects the recreation moment with zero
additional reset logic required — structurally guaranteed, not something that needs its own
runtime check.

**Alternatives considered**: A mutable, externally-settable creation timestamp (e.g. a setter
called by `ChannelRegistry`) — rejected as unnecessary indirection; nothing after construction
should ever legitimately change a channel's own creation moment, so an immutable field initializer
is both simpler and strictly safer (no code path can accidentally overwrite it).
