# Research: Connection Monitoring Log

## Connection token generation scheme

**Decision**: Replace `ConnectionHandler`'s shared `AtomicLong connectionIdCounter` +
`"c" + counter.incrementAndGet()` (`ConnectionHandler.java:97,130`) with
`UUID.randomUUID().toString()`, generated once per accepted connection.

**Rationale**: `java.util.UUID.randomUUID()` is a JDK built-in backed by `SecureRandom`-grade
entropy (122 random bits for a version-4 UUID) — it satisfies FR-001/FR-002's uniqueness and
opacity requirements with zero new dependencies and zero custom code, consistent with this
project's own precedent (research.md "Administrator credential storage",
008-argon2-admin-verification) of preferring a well-reviewed, standard mechanism over a
hand-rolled one. Its fixed, recognizable format (`8-4-4-4-12` hex groups) also makes a
monitoring log immediately distinguishable from the old sequential scheme at a glance, which
is itself a small but real usability win for an administrator scanning logs.

**Alternatives considered**:
- `SecureRandom` + manual hex/Base64 encoding — rejected: `UUID.randomUUID()` already IS
  `SecureRandom`-backed under the hood; hand-rolling the same guarantee with more code adds
  risk (encoding bugs, alphabet choices) for no benefit.
- A shorter random token (e.g. 8 random alphanumeric characters) — rejected: no requirement
  in spec.md calls for brevity, this is a log-facing/PING-payload identifier (not shown to
  end users in any UI), and IRC's 512-byte line limit has no trouble accommodating a 36-character
  token alongside `PING :` and the CRLF.

## Consistency between the monitoring log and server-sent PING

**Decision**: No change needed to `LivenessMonitor.sendPing` (`LivenessMonitor.java:75-87`) —
confirmed via source read that it already sends `session.connectionId()` as `PING`'s sole
payload. Once the token generation scheme above changes what `connectionId()` returns, every
existing reader of that field (the monitoring log's own connect/disconnect entries, `PING`,
and every other pre-existing call site) automatically stays consistent, since they all read
the same immutable field on `ClientSession`. Also confirmed: `PongCommandHandler`'s
`onPongReceived()` (`LivenessMonitor.java:70-73`) already treats *any* `PONG` as proof of
liveness — it does not compare the returned payload against the token it sent, so this
feature does not need to add or change response validation to satisfy FR-005/FR-008.

**Rationale**: This is the direct payoff of the codebase already centralizing "this
connection's identity" behind one field — the consistency requirement (US2) falls out of the
existing design rather than needing new plumbing.

**Alternatives considered**: N/A — this was a verification of existing behavior, not a design
choice with real alternatives.

## Where the connect/disconnect monitoring events are recorded

**Decision**: A new static-only utility, `ConnectionMonitorLog`, sibling to
`SecurityEventLog` (same package, same shape: private constructor, one `slf4j` `Logger`,
structured `key=value` `LOG.info` lines). `connected(connectionId, remoteAddress)` is called
from `ConnectionHandler.handleConnection` right after the token and remote address are known
(`ConnectionHandler.java:122-130` — `remoteAddress` is already computed there and stored as
`ClientSession.realHostname()`, no new field needed for it). `disconnected(connectionId,
duration, reason)` is called from `DisconnectCleanup.cleanup` (`DisconnectCleanup.java:55-119`)
— the single funnel every disconnect trigger (client `QUIT`, keep-alive timeout, `OPER`
lockout, abrupt TCP loss) already passes through, and specifically *inside* the existing
idempotency guard (`session.lifecycle().closeIfNotAlreadyClosing()`, line 62) so a connection
is logged as disconnected exactly once even when two triggers race for the same session — the
same guarantee that guard already gives `WhowasHistory` (research.md "OPER failed-attempt
lockout", `001-ircv3-server`; the idempotency fix itself, `c88fa5e`).

**Rationale**: `SecurityEventLog`'s own javadoc scopes it to security-relevant events
(FR-019) — routine connect/disconnect is not one of those, so extending that class would
blur an already-established boundary rather than reuse it cleanly. A new sibling facility
with the identical structural convention keeps the codebase's logging style consistent
without repurposing a class whose name and javadoc promise something narrower.
`DisconnectCleanup.cleanup` already receives a human-readable `reason` string for every
disconnect cause — reusing it in the monitoring log entry is free, and directly satisfies the
"disconnect reason" field spec.md's Assumptions section allows for.

**Alternatives considered**: Extending `SecurityEventLog` itself with a
`connectionEvent(...)` method — rejected per spec.md's own explicit exclusion (FR-006: "a
facility distinct from ... security-event log") and the class's existing narrower purpose.

## Connection duration

**Decision**: `ClientSession` gains a new `private final Instant connectedAt =
Instant.now();` field with a `connectedAt()` accessor — a field initializer, not a
constructor parameter, mirroring `Channel.createdAt`'s identical precedent
(007-bare-mode-query data-model.md). `DisconnectCleanup.cleanup` computes
`Duration.between(session.connectedAt(), Instant.now())` for the disconnect log entry.

**Rationale**: No existing field tracks connection start time; `Channel.createdAt` already
established the exact pattern this need matches (immutable, construction-time timestamp, no
explicit reset/lifecycle management needed).

**Alternatives considered**: Deriving duration from the token itself (e.g. a
timestamp-prefixed token) — rejected: this would make the token's own bytes reveal *when* a
connection started, which sits awkwardly close to the "no ordering/timing information"
opacity goal FR-002/SC-004 establish; keeping duration as separate, log-only state (never
transmitted to the client) avoids that tension entirely.

## Making the keep-alive idle interval administrator-configurable

**Decision**: Add `int keepAliveFrequencySeconds` to `ServerConfiguration`
(`jircd-core/src/main/java/net/jircd/core/config/ServerConfiguration.java`), with
`DEFAULT_KEEP_ALIVE_FREQUENCY_SECONDS = 120` and a new
`KEEP_ALIVE_FREQUENCY_CEILING_SECONDS = 3600` (1 hour), validated in `ConfigurationLoader`
via the same `positiveIntWithinCeiling` helper `operFailureThreshold`/`whowasHistorySize`
already use — no new validation code, just one more call site. `ConnectionHandler` receives
a `Supplier<Integer>` for this value (constructor parameter, following `rateLimit`'s own
exact precedent: `Supplier<ServerConfiguration.RateLimit> rateLimit`, sourced in the
composition root as `() -> reloader.current().rateLimit()`,
`JircdServerApplication.java:129`) and resolves it once per accepted connection
(`rateLimit.get()`'s own precedent, `ConnectionHandler.java:131`) into a concrete
`Duration.ofSeconds(...)` passed to `LivenessMonitor`'s existing constructor — unchanged,
since it already accepts a plain `Duration idleInterval`. `KEEP_ALIVE_IDLE_INTERVAL`
(`ConnectionHandler.java:84`) and its accompanying javadoc explicitly stating it is
"deliberately not exposed as an administrator-configurable setting" are both removed.

**Rationale**: This mirrors an already-proven pattern in the same class for an equally
per-connection, REHASH-sensitive setting (`rateLimit`) — a connection already in progress
keeps whatever interval was configured when it was accepted (same as it already keeps
whatever rate limit was configured then), while every new connection picks up the
latest-configured value. `KEEP_ALIVE_TIMEOUT` and `LIVENESS_CHECK_TICK` remain fixed
constants — spec.md's Assumptions section explicitly scopes configurability to the idle
threshold alone, not the response-timeout or internal check cadence.

**Alternatives considered**: A `Duration`-typed configuration field instead of a bare
`int` (seconds) — rejected in favor of matching every existing numeric
`ServerConfiguration` field's own convention (`operFailureThreshold`, `whowasHistorySize`
are both plain `int`s converted at their point of use), keeping the record's field types
uniform rather than introducing the project's first `Duration`-typed config value.

## Prior-feature contract correction required

**Decision**: `001-ircv3-server/spec.md`'s own Assumptions section currently states,
verbatim: "Connection keep-alive (FR-039) uses reasonable industry-standard probe-interval
and response-timeout defaults, not exposed as an administrator-configurable Server
Configuration setting in this release" — and a cross-reference inside FR-063's ban-list
ceiling explanation repeats the same claim ("Unlike FR-016's rate-limiting thresholds or
FR-039's keep-alive timing, this specific ceiling is a fixed value, not
administrator-configurable"). Both statements are made false by this feature and MUST be
corrected as an implementation task — the same precedent `006`/`007`/`008` already
established for correcting a prior feature's own contract/spec text rather than leaving it
contradicting the current codebase.

**Rationale**: An uncorrected, now-false assumption in a prior feature's spec is a form of
documentation debt this project has consistently avoided elsewhere; leaving it would let a
future reader of `001-ircv3-server/spec.md` believe keep-alive timing is still fixed.

**Alternatives considered**: Leaving `001`'s spec text untouched since specs are nominally
point-in-time artifacts — rejected, matching this project's own established practice of
correcting prior specs' now-inaccurate statements (e.g. `007-bare-mode-query`'s correction
of `001`'s bare-`MODE`-query documentation) rather than letting them silently drift out of
sync with the running system.

## Test impact of changing the default idle interval

**Decision**: `KeepAliveLoadTest.java` (`@Tag("load")`) currently waits real wall-clock
time against the previous *hardcoded* 30-second idle interval (`readUntil("PING",
Duration.ofSeconds(40))`, `KeepAliveLoadTest.java:44,61`). Under the new 120-second default
this test would need to wait well over 120 seconds per assertion, making an already-slow
load test far slower. This feature's own new configurability is the fix: the test must
configure a short `keepAliveFrequencySeconds` (e.g. `2`) via its own server configuration
YAML instead of relying on the default, the same way other tests already configure
`operFailureThreshold`/`rateLimit` values that don't fit their production defaults
(`Story6OperTest.java`'s `operFailureThreshold: 3` precedent). No other test references the
hardcoded interval (confirmed via source read — `LivenessMonitorTest.java` already drives
`LivenessMonitor` directly with an injected fake clock and its own `Duration` values,
independent of `ConnectionHandler`'s default entirely) or asserts a specific sequential
`connectionId` value from production code (the three tests constructing `ClientSession`
directly with a literal `"c1"` — `CapabilityNegotiatorTest`, `LivenessMonitorTest`,
`DisconnectCleanupTest` — pass that string as a test fixture, not a value read from
`ConnectionHandler`, so they are unaffected either way).

**Rationale**: Confirmed via source read rather than assumed, per this project's own
established investigative rigor for every prior feature's test-impact analysis.

**Alternatives considered**: N/A — this was a verification/impact-scoping exercise, not a
design choice with real alternatives.
