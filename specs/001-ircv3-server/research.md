# Phase 0 Research: Modular IRCv3 Chat Server

**Input**: [plan.md](./plan.md) Technical Context | **Spec**: [spec.md](./spec.md)

All items below were flagged in Technical Context as needing a documented
decision before Phase 1 design. Each follows Decision / Rationale /
Alternatives Considered.

## Protocol/server module boundary

**Decision**: A standalone `jircd-protocol` Gradle subproject owns wire
format concerns only — message line framing, command/reply parsing and
serialization, hostmask (`nickname!ident@hostname`, FR-030) formatting, and
the CAP negotiation grammar. It has no dependency on `jircd-core`, no
socket/connection code, and no server-side state (registries, sessions).
`jircd-core` depends on `jircd-protocol`; the dependency never goes the
other way.

**Rationale**: This is a direct requirement, not just good layering —
protocol code needs to be reusable by a future IRC *client* library, which
must not have to pull in server engine code (connection lifecycle,
nickname registry, rate limiting) it has no use for. Enforcing the
one-directional dependency at the Gradle level (not just by convention)
makes an accidental server-logic leak into the protocol layer a build
failure, not a code-review judgment call.

**Alternatives considered**: Keep protocol parsing inside `jircd-core`
alongside server logic (simplest short-term option, but reuse for a client
library would require extracting it later anyway, and nothing would stop
server-specific concerns from creeping into the parsing code in the
meantime).

## Networking model

**Decision**: Blocking-style I/O per connection on Java virtual threads
(`Executors.newVirtualThreadPerTaskExecutor()`), built directly on
`java.nio.channels.ServerSocketChannel`/`SocketChannel`. No external
networking framework (e.g., Netty).

**Rationale**: Java 25's virtual threads (finalized since Java 21) make
thread-per-connection cheap at the ~1,000-connection scale SC-003 requires —
each blocked read is a park, not an OS thread, so simple sequential,
easy-to-test code per connection scales without the callback/pipeline
complexity an async framework like Netty requires. This is now the
mainstream "standard practice" recommendation for new JVM network servers at
this scale, and it minimizes external dependencies (constitution Code
Quality principle: no unjustified complexity).

**Alternatives considered**:
- *Netty*: proven at far larger scale (tens of thousands of connections) and
  has IRC-adjacent protocol codec examples, but its callback/pipeline model
  is significantly more code and cognitive overhead than this project's
  ~1,000-connection target justifies, and it would be the project's first
  and only major external dependency for something the JDK now handles
  natively.
- *Classic platform-thread-per-connection*: same programming model as
  virtual threads but each connection costs a real OS thread (~1MB stack);
  1,000 connections is borderline-risky for default JVM thread-stack
  budgets and would need manual thread-pool tuning. Virtual threads remove
  this constraint outright.

## Module system (runtime hot-toggle)

**Decision**: A small, custom SPI: each module implements a `ServerModule`
interface (`start(ServerContext)`, `stop()`, metadata) discovered via
`java.util.ServiceLoader`, but loaded through a dedicated, discardable
`URLClassLoader` per module instance so a disabled module's classes (and any
`static` state) can be fully released and a re-enabled module gets a fresh
load — not just a "hidden" reference to code still resident in the JVM.
`jircd-core` owns a `ModuleRegistry` that tracks each module's lifecycle
state and enforces FR-020 (one module's failure doesn't affect others).

**Rationale**: FR-011 requires enable/disable to take effect **without
restarting the server process** — this is a runtime lifecycle requirement,
not just a build-time packaging concern. `ServiceLoader` alone discovers
implementations but has no notion of unloading; pairing it with a
per-module classloader gives genuine start/stop/reload semantics with
clean isolation, which is also what FR-020's fault-isolation requirement
needs (a module's failure must be containable).

**Alternatives considered**:
- *Java Platform Module System (JPMS)*: JPMS's module graph is resolved and
  fixed at JVM launch (or via `ModuleLayer`, which is possible to create
  dynamically but is a heavyweight, seldom-used API primarily meant for
  frameworks like application servers). It optimizes for strong
  encapsulation and reliable static configuration, not the frequent,
  administrator-driven runtime toggling FR-011 describes. Using it here
  would fight the requirement rather than serve it.
- *OSGi*: the traditional Java answer to exactly this problem
  (dynamic module lifecycle, isolated classloading), but it is a large
  framework with its own deployment model, bundle manifest format, and
  learning curve — disproportionate to a project with 5 initial optional
  modules under `jircd-modules/` (3 capabilities, cloak, admin — moderation
  and the capability-negotiation mechanism itself are core and not part of
  this system at all, see FR-035/FR-036). Revisit if the module count and
  third-party-plugin ambitions grow.
- *No isolation, just a registry of enabled booleans*: simplest option, but
  fails FR-020 (a module that leaks state or throws during static init can
  still affect the whole process) and doesn't give a clean "reload" story.

## Deterministic testing under concurrency

**Decision**: Success criteria expressed as wall-clock budgets (SC-002's 1s
delivery, SC-003's 1,000 connections, SC-006's no-degradation-under-load) are
tested with two tiers: (1) fast, deterministic unit/component tests that
assert *ordering and correctness* (e.g., "message is enqueued to every
member's outbound queue before the send call returns") without any sleep or
wall-clock assertion, and (2) a small number of explicitly-labeled
`@Tag("load")` integration tests, excluded from the default fast test run
and run separately (e.g., a dedicated Gradle task / CI stage), using
generous, documented margins (e.g., asserting p99 < 1s, not a tight bound)
specifically so they tolerate CI environment noise without becoming flaky.

**Rationale**: The constitution's Testing Standards principle is explicit
that flaky tests "MUST be fixed or removed, never silenced by retries or
skips" — so timing-sensitive success criteria cannot be asserted the same
way as functional correctness. Separating "does it behave correctly" from
"does it meet its performance budget" keeps the default (must-pass-every-
run) suite deterministic while still giving SC-002/003/006 real, repeatable
verification.

**Alternatives considered**:
- *Assert wall-clock timing in every test*: simplest to write, but directly
  produces the flaky/non-deterministic tests the constitution forbids,
  especially for SC-003's 1,000-connection scenario on shared CI hardware.
- *Skip performance testing, rely on manual/production observation*: fails
  the constitution's Performance Requirements principle, which requires a
  re-runnable benchmark/load test for performance-sensitive paths.

## Configuration format

**Decision**: YAML via SnakeYAML for the Server Configuration file (FR-012):
module enable/disable flags, listener ports (plaintext + optional TLS),
rate-limit thresholds.

**Rationale**: Human-edited, hierarchical, comment-friendly — matches an
administrator-facing config file (FR-012 requires clear, specific error
reporting on invalid config, which is easier with YAML's structure than flat
`.properties`). SnakeYAML is a small, mature, dependency-light library with
no transitive framework baggage.

**Alternatives considered**:
- *Java `.properties`*: zero dependencies, but flat key-value structure is
  awkward for per-module nested settings and doesn't support comments well
  for admin documentation.
- *HOCON (Typesafe Config)*: richer feature set (includes, substitutions),
  but pulls in a heavier library for features this project doesn't need at
  its current scope.

## Logging

**Decision**: SLF4J API + Logback implementation.

**Rationale**: De facto standard Java logging pairing; satisfies FR-019's
requirement for reviewable security-event logs with structured, leveled
output and file/rotation support out of the box.

**Alternatives considered**: `java.util.logging` (built into the JDK, zero
dependencies, but noticeably weaker configuration ergonomics and no
structured-logging story — a worse fit for FR-019's "an administrator can
review" bar).

## Static analysis / code quality tooling

**Decision**: Spotless (formatting/style enforcement) + SpotBugs (bug
patterns) as Gradle-integrated build tasks, both configured to fail the
build on violations.

**Rationale**: Directly implements the constitution's Code Quality
principle ("Static analysis and linting MUST run clean... before a change
is considered mergeable"). Spotless auto-formats and verifies formatting in
one Gradle task (lower friction than Checkstyle's report-only style, since
`spotlessApply` can fix violations directly rather than only flagging
them), while SpotBugs covers the complementary concern of actual defect
patterns.

**Alternatives considered**: Checkstyle (viable, but Spotless's
apply-and-fix workflow was preferred for day-to-day contributor ergonomics
over Checkstyle's report-only model). PMD (overlaps significantly with
SpotBugs; adding both was judged redundant tooling for this project's size
— revisit only if SpotBugs proves insufficient in practice).

## Administrator credential storage (FR-034)

**Decision**: Administrator credentials in the Server Configuration are
stored and verified the same way as FR-024's account credentials: never in
plain text, protected with a salted, computationally-expensive hash (e.g.,
bcrypt/Argon2 via a small, well-reviewed library rather than a hand-rolled
implementation).

**Rationale**: FR-034 explicitly requires this parity with FR-024. Reusing
one hashing approach (rather than inventing a second one specific to admin
credentials) keeps the credential-handling code path singular, which is
easier to review and test — directly serving the constitution's Code
Quality principle (single, clear responsibility).

**Alternatives considered**: Storing an admin password hash with a
weaker/faster algorithm (e.g., plain SHA-256) on the reasoning that it's
"just local config" — rejected because the threat model (config file
disclosure, e.g., via backup leak or misconfigured permissions) is the same
threat FR-024 is already written to defend against; there's no principled
reason to weaken it for this one credential type.

## Cloak module boundary (FR-031)

**Decision**: `jircd-modules/cloak` is a `ServerModule` like any capability
module (research.md "Module system"), but it does not itself own a
client's real hostname — `jircd-core` always records the real value
on the `ClientSession` (data-model.md) and asks the currently-enabled cloak
module, if any, for the *display* value used when presenting identity to
other clients (FR-030). Administrative hostname lookups (FR-032) always
read `jircd-core`'s stored real value directly, bypassing the cloak
module entirely.

**Rationale**: This directly satisfies the edge case of "what happens when
cloaking is disabled while clients are connected" (spec.md Edge Cases): if
the real value only ever lived in `jircd-core`, disabling `jircd-cloak` at
runtime (FR-011) simply means the display-value lookup returns the real
value again immediately — no migration, no per-session state to fix up,
and no way for a cloak-module bug to lose or corrupt the real value the
rest of the system depends on.

**Alternatives considered**: Having the cloak module own/replace the
stored hostname (with the real value kept in a side table only the cloak
module manages) — rejected because it makes administrator lookups (FR-032)
and disabling-while-connected behavior depend on the cloak module's own
bookkeeping being correct, which is a larger trust surface for what should
be a purely presentational concern.

## TLS approach

**Decision**: `SSLContext` + `SSLEngine` (non-blocking-friendly TLS API)
wrapping the same `SocketChannel` connections used for plaintext, rather
than `SSLServerSocket`/`SSLSocket`.

**Rationale**: `SSLEngine` operates directly on the same channel-based I/O
model as the rest of the connection-handling code (see "Networking model"
above), so the optional-TLS listener (FR-018) shares its connection
lifecycle and virtual-thread-per-connection handling with the plaintext
listener instead of needing a parallel code path built on the older
socket-based TLS API.

**Alternatives considered**: `SSLServerSocket`: simpler API, but is built on
the older blocking-socket model and would require a second, divergent
connection-acceptance code path alongside the channel-based one.

## Rate limiting (FR-016)

**Decision**: Per-connection token bucket, refilled at a fixed rate,
configurable via the Server Configuration (bucket size and refill rate).

**Rationale**: Token bucket is the standard, well-understood approach for
this exact case (bursty-but-bounded traffic from a single source); it is
simple to reason about, cheap per-connection, and its two parameters map
directly to an administrator-tunable config (matching the spec's Assumptions
section: "reasonable industry-standard defaults, configurable by the
administrator").

**Alternatives considered**: Fixed window counter (simpler but allows burst
doubling at window boundaries); sliding-window log (more precise but
higher per-connection memory cost for a benefit this project doesn't need).
