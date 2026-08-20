# Phase 0 Research: Extended IRC Commands

All decisions here extend `001-ircv3-server`'s existing choices (Java 25,
virtual-thread-per-connection networking, in-memory-only state, no new runtime
dependencies) rather than replacing any of them. Nothing in this feature requires a new
library, a new module, or a change to the existing concurrency model — see
`specs/001-ircv3-server/research.md` for the baseline this builds on.

## VERSION + ISUPPORT reuse

**Decision**: `VERSION`'s handler sends `RPL_VERSION` followed by the exact same
`RPL_ISUPPORT` line(s) `RegistrationCompletion` already sends at registration, by having
both call a shared `SupportedFeatures`-to-`RPL_ISUPPORT` rendering routine instead of each
formatting it independently.

**Rationale**: `SupportedFeatures` (`jircd-core`, `001-ircv3-server` FR-055) is already the
single source of truth for the server's advertised limits; `RegistrationCompletion` already
knows how to turn a `SupportedFeatures` snapshot into one or more `005` lines
(`RegistrationCompletion.java:92`). Extracting that line-rendering step into a small shared
helper both `RegistrationCompletion` and the new `VersionCommandHandler` call avoids a
second, independently-maintained implementation of the same wire format — exactly the kind
of divergence risk `001-ircv3-server`'s own contract notes flag elsewhere (e.g. `WHOIS`/`WHO`
sharing one hostname-resolution routine rather than reimplementing it twice).

**Alternatives considered**: Having `VersionCommandHandler` re-read `ServerConfiguration`
and rebuild the `005` line set itself — rejected, exact duplication of logic that already
exists and is already recomputed reactively when `ExtensionRegistry`'s state changes.

## WHOWAS bounded history store

**Decision**: A single, server-scoped, global (not per-nickname) bounded history —
`WhowasHistory` — backed by a fixed-capacity ring buffer (`ArrayDeque` under an intrinsic
lock: `addLast` after evicting `removeFirst` at capacity), storing the most recent N
disconnections across all nicknames combined, most-recent-first per nickname lookup by
linear scan. Default capacity: 100 entries, administrator-configurable
(`ServerConfiguration.whowasHistorySize`) the same way `ServerConfiguration` already exposes
other bounded-resource knobs (`nicknameMaxLength`, `topicMaxLength`, the channel ban list's
existing fixed cap).

**Rationale**: Write volume is inherently low — one entry per disconnection, never more than
once per session's lifetime, across at most ~1,000 concurrent connections
(`001-ircv3-server` SC-003) — so a simple whole-structure lock has no measurable contention
risk; this matches the project's existing preference for the simplest data structure that's
provably correct over a lock-free structure bought at the cost of real complexity (`0.1.0`'s
`RateLimitBucket`, e.g., takes the same "simple and locked, not lock-free" approach for a
similarly low-volume per-connection counter). A global cap (rather than one bucket per
nickname) matches the spec's own Assumption and avoids unbounded memory growth from a
pathological pattern of many distinct, never-repeated nicknames each getting their own
small bucket.

**Alternatives considered**:
- A dedicated LRU-cache library (e.g. Caffeine) — rejected: this project has zero
  general-purpose caching dependency today, and a 100-entry ring buffer with linear scan is
  well within acceptable lookup cost for a rarely-invoked command; adding a dependency for
  this would be exactly the kind of unjustified complexity the constitution's Development
  Workflow section prohibits ("no speculative generality").
- Per-nickname history buckets (RFC 2812's own implied model, where `WHOWAS` optionally
  takes a count of *how many* past entries to return for one nickname) — rejected per the
  spec's explicit scope decision (FR-017: "the most recent entry", not a count); a global
  ring buffer is simpler and sufficient for that narrower scope.

## KILL disconnect path reuse

**Decision**: `KillCommandHandler` (new, in `jircd-server-extensions/admin`, alongside the
existing `OperCommandHandler`/`RehashCommandHandler`/`SajoinCommandHandler`/
`SamodeCommandHandler`/`WhohostCommandHandler`) resolves the target nickname via
`NicknameRegistry`, transitions that session to `CLOSING`, and calls the exact same
`DisconnectCleanup` path every other disconnection already uses
(`001-ircv3-server` FR-017), passing a `KILL`-specific reason string distinguishable from an
ordinary `QUIT` or keep-alive timeout — the identical pattern `LivenessMonitor`'s timeout
path and the pre-existing "Excess Flood" writer-overflow path both already follow.

**Rationale**: `DisconnectCleanup`/the `ConnectionHandler` socket-close backstop
(`0f28511`, this session's earlier work on `T162`) is already the single, correct path for
"a session becomes `CLOSING` for any reason" — introducing a second, `KILL`-specific
cleanup path would reintroduce exactly the cross-thread-close bug that commit fixed for the
other two callers. `KILL` triggers cleanup from the administrator's own session thread, not
the target's, so it needs the same cross-thread-safe close behavior `LivenessMonitor`
already established.

**Alternatives considered**: A `KILL`-specific immediate `socket.close()` call from the
admin's handler thread directly — rejected, exactly the cross-thread pattern that caused the
bug fixed in `ConnectionHandler`/`LivenessMonitor`; reusing the existing, now-correct path is
strictly simpler and already proven.

## TAGMSG delivery reuse

**Decision**: `TagmsgCommandHandler` resolves and validates its target (channel membership,
nickname existence, moderation/ban gates) using the same target-resolution logic
`MessageCommandHandler` already uses for `PRIVMSG`/`NOTICE`, refactored into a shared
target-resolution step both handlers call, then fans out a message carrying only tags (no
text parameter) to recipients that have `message-tags` negotiated, skipping recipients that
don't.

**Rationale**: `001-ircv3-server` FR-062's ban/moderation gating and FR-004/FR-005's
channel/nickname targeting rules must not be reimplemented a second time for `TAGMSG` — the
spec (FR-022) explicitly requires reusing them. `MessageCommandHandler` already parameterizes
on "is this a NOTICE" (`JircdServerApplication.java:177-183` constructs it with a boolean);
the same shape extends naturally to a tag-only variant.

**Alternatives considered**: A fully independent `TagmsgCommandHandler` with its own
target-resolution logic — rejected, directly contradicts FR-022 and risks the two paths
drifting apart over time (e.g. a future ban-list change applied to one but not the other).

## Away status placement

**Decision**: `awayReason` lives on `ClientSession` as an `Optional<String>` (present =
away, holding the reason; empty = not away), read by `MessageCommandHandler` (to send the
away-reply notice), `WhoisCommandHandler`, and `WhoCommandHandler`.

**Rationale**: Matches the existing `ClientSession` field pattern exactly — `ident` already
uses "presence of a value" as its own state signal rather than a separate boolean
(`data-model.md` `ClientSession.ident` note), and away status is a session property, not
channel- or nickname-scoped, matching FR-010's persistence-across-`NICK`-change requirement.

**Alternatives considered**: A separate `boolean away` field alongside a nullable reason —
rejected as exactly the kind of two-source-of-truth duplication `ClientSession`'s existing
fields (`administratorPrivilege`/`userModes`) already went out of their way to avoid.
