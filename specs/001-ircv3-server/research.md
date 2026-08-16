# Phase 0 Research: Modular IRCv3 Chat Server

**Input**: [plan.md](./plan.md) Technical Context | **Spec**: [spec.md](./spec.md)

All items below were flagged in Technical Context as needing a documented
decision before Phase 1 design. Each follows Decision / Rationale /
Alternatives Considered.

## Protocol/server boundary

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

## Wire-protocol command & numeric completeness

**Decision**: `jircd-protocol`'s `Command` and `NumericReply` catalogs
cover the complete RFC 1459/2812 command and numeric-reply sets (plus the
IRCv3 framework commands this project uses — `CAP`, `AUTHENTICATE`,
`TAGMSG`), regardless of which commands `jircd-core` actually implements a
handler for in this release. contracts/irc-protocol-commands.md and
contracts/irc-numeric-replies.md each carry the full catalog, with a
"Used in this release" subset called out separately from the
"Recognized/Reserved" remainder.

**Rationale**: This is a direct consequence of the "Protocol/server
boundary" decision above, not a separate scope expansion — if
`jircd-protocol` is genuinely meant to be reusable by a future IRC
*client* library, that library needs to parse `311 RPL_WHOISUSER` or a
`WHOIS` line from *any* server it connects to, not only from this one.
Scoping the catalog down to "whatever `jircd-core` currently implements"
would silently couple a supposedly-generic library to this release's
feature set, and would force a breaking model change every time a future
release adds a command this server didn't originally implement (e.g.,
`TOPIC` or `WHOIS`) — versus just flipping that command from
"Recognized only" to "Implemented" against a catalog that already has it.
The cost of building the catalog complete now is low (it's enum-style
data, not behavior); the cost of discovering it's incomplete later, after
a client library already depends on it, is not.

**Alternatives considered**: Scope the catalog to only the commands/
numerics this release's stories actually use (the original approach) —
rejected per above: it undermines the entire stated reason
`jircd-protocol` exists as a separate module in the first place, and
defers real rework (extending the catalog under a live client-library
dependency) rather than avoiding it.

## Connection registration grammar

**Decision**: `NICK`/`USER` get generic line parsing (`Command`
arity metadata, T014, same as any other fixed-arity command) rather than
a dedicated grammar class — unlike `CAP`, which does get one
(`CapabilityNegotiationGrammar`, T018). What *does* get its own defined
grammar is the **nickname format** itself (RFC 2812 §2.3.1: one leading
letter/`special` character, up to 8 more letters/digits/`special`/`-`,
9 characters total) and the **username** content rule (any octet except
NUL/CR/LF/space/`@`, truncated to 9 characters for the `ident` shown in
the hostmask) — both now spelled out in
contracts/irc-protocol-commands.md's "Connection Registration Grammar".
Registration *completion sequencing* (what combination/order of `NICK`,
`USER`, and optional `CAP` negotiation triggers `001 RPL_WELCOME`) is a
`jircd-core`/`ConnectionLifecycle` state-machine concern, not a
wire-protocol grammar concern, and is documented there instead.

**Rationale**: `CAP` needed a dedicated grammar component because its
subcommands (`LS`/`REQ`/`ACK`/`NAK`/`END`) branch into genuinely different
follow-on syntax within one line — a real sub-language. `NICK`/`USER`
don't have that: fixed parameter counts, no branching, so the generic
parser plus arity metadata already covers *how many parameters* a
well-formed line has. What was actually missing — and is a different kind
of thing — is *what characters are legal inside* the nickname parameter.
`432 ERR_ERRONEUSNICKNAME` (contracts/irc-numeric-replies.md) referenced
"nickname format rules" from the very first draft of this plan without
those rules ever being defined anywhere, which is a real gap this closes:
a `jircd-core` handler can't correctly reject a malformed nickname against
a rule that was never written down. Defining it in `jircd-protocol`
(alongside `Hostmask`, T017, which already needs to know what a nickname
looks like when composing `nickname!ident@hostname`) rather than inline
in `jircd-core`'s `NICK` handler keeps it reusable by the same future
client library the rest of `jircd-protocol` is built for — a client
validating a nickname before sending `NICK` needs the identical rule.

**Alternatives considered**:
- *A dedicated `RegistrationGrammar` class mirroring
  `CapabilityNegotiationGrammar`*: rejected — there's no sub-language to
  justify it; would be a class that just re-does what generic parsing
  plus arity metadata already does, adding indirection without adding
  correctness.
- *Leave nickname format as an inline check inside the `NICK` handler
  (`jircd-core`)*: works for this server alone, but ties a wire-protocol-
  level rule to `jircd-core` specifically, unlike everything else
  `Hostmask` already centralizes — and silently reopens the same "never
  formally defined" gap the moment anyone other than that one handler
  needs the rule (e.g., a future client library, or a second place in
  `jircd-core` that also validates nicknames).

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

## Message fan-out concurrency model

**Decision**: Each `ClientSession` owns a bounded outbound queue and a
single dedicated writer virtual thread that is the *only* thread ever
allowed to write to that session's `SocketChannel`. Delivering a message to
a channel (FR-004) is a **two-stage** pipeline, split by who has the
information needed for each stage:

1. **Sender's thread, once**: resolves the parts of the message that do
   *not* vary by recipient — the hostmask and the message body/command.
   The hostmask is resolved by live-checking the current `cloak`
   `ServerExtension` state at that exact moment — enabled or not, never a
   cached/stale value (`UserIdentity.presentedForm`, data-model.md) — the
   same "live-check, never cached" discipline stage 2 applies to
   capabilities below, just evaluated once here rather than once per
   recipient, because cloaking is a uniform display transform applied the
   same way to every viewer, not a per-viewer choice. Server extension
   state is the *only* thing allowed to change the hostmask at this stage
   — no recipient's own state (capabilities, session data) ever factors
   into it, which is exactly what makes it safe to bake one resolved
   hostmask into a single immutable object shared by every recipient of a
   fan-out. This produces one shared, immutable "pending delivery" object,
   enqueued onto every recipient member's outbound queue. No
   capability-driven formatting decisions are made at this stage — it
   never writes to another session's socket directly, only enqueues.
2. **Each recipient's own writer thread, at drain time**: converts that
   session's next queued pending delivery into the actual wire line,
   applying *that recipient's* `negotiatedCapabilities` — live-checked
   against current `CapabilityExtension` state, never cached (data-model.md
   "Capability" validation rules) — immediately before the
   `SocketChannel.write`. This is what makes it correct for Alice (who
   negotiated `message-tags`+`server-time`) and Bob (who negotiated
   nothing) to receive different wire-level renderings of the *same*
   channel message, drawn from the *same* queued object: the per-recipient
   decoration (tag prefix, `time` tag) happens independently in each
   recipient's own thread, not once on the sender's.

`echo-message` is a different kind of decision made at a different point:
whether the sender's *own* session belongs in the recipient set at all is
decided once, by the sender's thread, when it builds the member list for
stage 1 — it is not a per-write formatting concern like the tag
capabilities are.

A member's queue reaching capacity is treated as that connection being too
slow to keep up and is handled the same way as any other connection-loss
condition (FR-017 cleanup), not by blocking the sender.

**Rationale**: This closes two gaps at once. First, the one the earlier
plan review flagged — without a shared writer-owns-its-socket model,
`PRIVMSG`/`NOTICE`/`JOIN`/`PART`/`KICK`/`MODE` fan-out (FR-004, and every
other command that echoes to multiple members) would mean one session's
thread writing directly to N other sessions' `SocketChannel`s, which is
unsafe if that member's own writer thread could run concurrently on the
same channel. Second — and this is why formatting is split into two
stages rather than done once by the sender — a single shared "already
formatted" queue element cannot simultaneously be correct for recipients
with different negotiated capabilities; doing the capability-dependent
part of formatting in each recipient's own writer thread, at the latest
possible moment, is both the only way to get per-recipient correctness
and a natural extension of the "one thread owns this socket" invariant
already established for write-safety.

**Alternatives considered**:
- *Format once on the sender's thread, before enqueueing (the original
  design)*: cannot express "Alice sees a `time` tag, Bob doesn't" from one
  shared formatted string — reverted for exactly that reason.
- *Direct cross-thread writes, synchronized on the target channel*: works,
  but requires every code path that might deliver a message to remember to
  synchronize correctly, and turns a slow recipient's contended lock into a
  latency problem for whichever sender thread happens to hit it — exactly
  the kind of thing SC-002's 1s delivery budget is sensitive to.
- *A single global outbound event loop (Netty-style)*: reintroduces the
  callback/pipeline complexity "Networking model" above rejected, for a
  problem the per-session-queue-plus-writer approach solves at
  ~1,000-connection scale without it.

## Extension system (runtime hot-toggle)

**Decision** *(renamed from "Module system" — see plan.md "Domain Model &
Bounded Contexts")*: A small, custom SPI: each extension implements the
`Extension` interface (`start(ServerContext)`, `stop()`, metadata)
discovered via `java.util.ServiceLoader`. Two role interfaces extend it:
`CapabilityExtension` (also exposes exactly one `Capability`; implemented
by `jircd-capabilities/*`) and `ServerExtension` (no `Capability`;
implemented by `jircd-server-extensions/*`) — see plan.md for why these are
different domain concepts, not just two packages of the same thing. Each
extension is loaded through a dedicated, discardable `URLClassLoader` per
instance so a disabled extension's classes (and any `static` state) can be
fully released and a re-enabled extension gets a fresh load — not just a
"hidden" reference to code still resident in the JVM. `jircd-core` owns an
`ExtensionRegistry` that tracks each extension's lifecycle state and
enforces FR-020 (one extension's failure doesn't affect others).

Three details make this actually work, not just sound plausible:

- **Delegation model**: each extension's `URLClassLoader` delegates to the
  application classloader *first* for any class in `net.jircd.protocol.*`
  or `net.jircd.core.*` (the shared SPI/contract types, including
  `Extension`/`CapabilityExtension`/`ServerExtension` themselves), so both
  the extension and `jircd-core` always see the identical `Class` object
  for those types — avoiding the classic "same class loaded twice" hazard
  where `instanceof`/casts across the extension boundary fail
  unpredictably. Only the extension's own classes (and its private
  dependencies, if any) are loaded by its own loader, which is exactly
  what's discarded on disable.
- **Quiesce before unload**: disabling an extension is two steps, not one
  — (1) `ExtensionRegistry` immediately marks it `DISABLED` so no *new*
  work is routed to it (new CAP negotiations stop offering a
  `CapabilityExtension`, new admin commands are rejected, etc. — this
  alone satisfies most of FR-011/SC-005's "takes effect immediately" bar),
  then (2) the registry waits for any in-flight invocation already running
  on the extension's code to finish (each `Extension` call is made through
  a small wrapper that tracks an in-flight count) before dropping the last
  reference to its classloader. Only after that does the classloader
  actually become collectible. An extension that hangs and never returns
  is itself an FR-020 failure condition — logged (FR-019) and surfaced as
  `FAILED`, not silently waited on forever (a bounded timeout applies).
- **Extension-point ownership**: for cases where an extension supplies a
  value core code consumes (e.g., cloak's hostname-display transform, see
  "Cloak extension boundary"), `ExtensionRegistry` allows at most one
  *enabled* extension to claim a given extension point at a time.
  Attempting to enable a second extension claiming an already-claimed
  extension point is treated as a configuration error (FR-012 style:
  rejected with a specific error naming the conflicting extension ids),
  not a silent override. Only one extension (`cloak`) claims an extension
  point in this release, so this is currently unreachable in practice, but
  the rule is stated now so it doesn't have to be invented ad hoc when a
  second one is added later.

**Rationale**: FR-011 requires enable/disable to take effect **without
restarting the server process** — this is a runtime lifecycle requirement,
not just a build-time packaging concern. `ServiceLoader` alone discovers
implementations but has no notion of unloading; pairing it with a
per-extension classloader gives genuine start/stop/reload semantics with
clean isolation, which is also what FR-020's fault-isolation requirement
needs (an extension's failure must be containable). The delegation and
quiesce details above exist because "use a classloader per extension" is
not by itself sufficient to deliver on FR-011/FR-020 — get the delegation
order wrong and cross-extension type checks break; skip the quiesce step
and an extension can keep running after being reported "disabled," or its
classloader is never actually reclaimed. Splitting `CapabilityExtension`
and `ServerExtension` as role interfaces (rather than one flat `Extension`
type with an optional capability field) makes the domain distinction
enforceable by the compiler: code that only makes sense for negotiated
capabilities (offering something in `CAP LS`) can require a
`CapabilityExtension` specifically, and can't accidentally be handed a
`ServerExtension` that was never meant to be client-visible.

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
  extensions across `jircd-capabilities/` and `jircd-server-extensions/`
  (3 capability extensions, cloak and admin as server extensions —
  moderation and the capability-negotiation mechanism itself are core and
  not part of this system at all, see FR-035/FR-036). Revisit if the
  extension count and third-party-plugin ambitions grow.
- *A single flat `Extension` type with no `CapabilityExtension`/
  `ServerExtension` split*: fewer types, but loses the compiler-enforced
  distinction described in Rationale, and matches the code's model to
  reality less closely — the earlier, undifferentiated "Module" design
  this replaces.
- *No isolation, just a registry of enabled booleans*: simplest option, but
  fails FR-020 (an extension that leaks state or throws during static init
  can still affect the whole process) and doesn't give a clean "reload"
  story.

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
extension enable/disable flags, listener ports (plaintext + optional TLS),
rate-limit thresholds.

**Rationale**: Human-edited, hierarchical, comment-friendly — matches an
administrator-facing config file (FR-012 requires clear, specific error
reporting on invalid config, which is easier with YAML's structure than flat
`.properties`). SnakeYAML is a small, mature, dependency-light library with
no transitive framework baggage.

**Alternatives considered**:
- *Java `.properties`*: zero dependencies, but flat key-value structure is
  awkward for per-extension nested settings and doesn't support comments
  well for admin documentation.
- *HOCON (Typesafe Config)*: richer feature set (includes, substitutions),
  but pulls in a heavier library for features this project doesn't need at
  its current scope.

## Configuration reload mechanism

**Decision**: A manually-triggered reload — not automatic file-watching.
A single core reload operation (re-run `ConfigurationLoader`, validate,
reconcile the result against `ExtensionRegistry` — succeed or reject
atomically, same validation path as startup, FR-012) is exposed through
two independent triggers:

1. **`SIGHUP`** — the classic Unix daemon convention (nginx, sshd, most
   IRCds themselves), handled in `jircd-server`'s application entry point.
   Requires only shell/process access — no IRC connection.
2. **`REHASH`** — an in-band, administrator-privileged IRC command (added
   to the Story 6 admin command set, contracts/irc-protocol-commands.md),
   matching the command name and behavior most IRC daemons (ircu,
   InspIRCd, UnrealIRCd, charybdis) use for exactly this.

Both call the same underlying reload operation; neither depends on the
other being available.

**Rationale**: An administrator-driven manual trigger — rather than
detecting file changes automatically — avoids reloading against a file
an editor has only partially written, and matches what IRC operators
already expect from `REHASH`. Exposing it via *two* independent triggers,
rather than picking just one, is what keeps Story 4 and Story 6
independently deliverable: Story 4 is specifically the
configuration-file-only administration path (no IRC access required), so
its reload trigger can't live inside the optional `admin` `ServerExtension`
that Story 6 delivers — `SIGHUP` is handled in the core application
process regardless of which extensions are enabled. Story 6 gets `REHASH`
as the in-band equivalent, consistent with its "no file system access"
premise and with real-world IRC operator convention.

**Alternatives considered**:
- *Automatic file-watching (`java.nio.file.WatchService`)*: the original
  choice, reverted here. It reacts to file-system events, which risks
  triggering mid-write (a half-saved YAML file from certain editors/save
  patterns) and doesn't match how IRC administrators actually expect to
  apply a config change (explicitly, on their own timing) — `REHASH`'s
  request/response also gives immediate confirmation or a specific
  validation error, which a background watcher can't easily surface back
  to the admin's terminal.
- *`SIGHUP` only, no `REHASH` command*: would leave a Story 6 administrator
  needing shell access to apply anything beyond an `EXTENSION` toggle
  (which bypasses the file entirely), undermining Story 6's "no file
  system access" premise for the rest of the config (rate limits,
  listeners, credentials).
- *`REHASH` command only, no `SIGHUP`*: would make Story 4 (file-only
  administration) depend on the optional `admin` extension existing and
  being enabled just to apply its own file edits — contradicts Story 4's
  independence and FR-011's baseline "no restart" guarantee, which holds
  regardless of which extensions are built.

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

## Cloak extension boundary (FR-031)

**Decision**: `jircd-server-extensions/cloak` is a `ServerExtension` (see
"Extension system" — it has no negotiable `Capability`, so it's a
`ServerExtension`, not a `CapabilityExtension`), but it does not itself own
a client's real hostname — `jircd-core` always records the real value on
the `ClientSession` (data-model.md) and asks the currently-enabled cloak
extension, if any, for the *display* value used when presenting identity
to other clients (FR-030). It does this by claiming the `hostname-display`
extension point (data-model.md's `Extension.extensionPoint`), so the
"Extension system" decision's ownership rule — at most one enabled
extension per extension point — already prevents a second, competing
cloaking extension from being enabled at the same time without inventing a
cloak-specific conflict rule. Administrative hostname lookups (FR-032,
via `WHOHOST`) and `WHOIS`'s self-lookup/administrator cases (FR-037/
FR-038) always read `jircd-core`'s stored real value directly, bypassing
the cloak extension entirely; `WHOIS`'s non-administrator-looking-up-
someone-else case instead calls the exact same display-value lookup
message hostmasks use (`UserIdentity.presentedForm`, data-model.md) —
one resolution function, three call sites (`presentedForm`, `WHOHOST`,
`WHOIS`), not three independent implementations that could drift apart.

**Rationale**: This directly satisfies the edge case of "what happens when
cloaking is disabled while clients are connected" (spec.md Edge Cases): if
the real value only ever lived in `jircd-core`, disabling `cloak` at
runtime (FR-011) simply means the display-value lookup returns the real
value again immediately — no migration, no per-session state to fix up,
and no way for a cloak-extension bug to lose or corrupt the real value the
rest of the system depends on.

**Alternatives considered**: Having the cloak extension own/replace the
stored hostname (with the real value kept in a side table only the cloak
extension manages) — rejected because it makes administrator lookups
(FR-032) and disabling-while-connected behavior depend on the cloak
extension's own bookkeeping being correct, which is a larger trust surface
for what should be a purely presentational concern.

## TLS approach

**Decision** *(revised)*: `SSLServerSocket`/`SSLSocket` — the blocking TLS
API — for the optional TLS listener (FR-018), not `SSLEngine`.

**Rationale**: This decision was originally made to match a non-blocking,
channel-based I/O model, but "Networking model" above actually chose
*blocking* I/O per connection on virtual threads, not a non-blocking
reactor. `SSLEngine` is designed for exactly the non-blocking case — you
drive its wrap/unwrap/handshake state machine by hand — and using it in a
blocking-per-thread design means hand-rolling that state machine for no
benefit, since nothing else in the connection-handling code is
non-blocking. `SSLSocket` wraps a blocking socket and, like the rest of
the design, reads/writes as ordinary blocking calls on a virtual thread —
it is the pairing that actually matches "Networking model," not the
mismatch the original version of this decision introduced. Both the
plaintext (`ServerSocketChannel`/`SocketChannel`) and TLS
(`SSLServerSocket`/`SSLSocket`) listeners end up structurally similar:
accept a connection, hand it to a virtual thread, read/write blocking
calls from there — TLS or not is an implementation detail of that one
connection's I/O calls, not a fork in the overall concurrency model.

**Alternatives considered**: `SSLEngine` (the original choice, reverted —
see Rationale: right API for a non-blocking reactor, wrong one for this
project's actual blocking/virtual-thread model). Wrapping
`SocketChannel` in a hand-written blocking adapter just to keep using
`SSLEngine`: technically possible but reinvents what `SSLSocket` already
provides, for no gain.

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
