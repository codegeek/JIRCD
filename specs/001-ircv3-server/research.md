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

## Server identity (FR-050/FR-051)

**Decision**: `ServerConfiguration.serverName` (data-model.md) is the
source/prefix on every server-originated message, administrator-
configurable with a zero-configuration fallback to the deployment host's
own network hostname if unset. `serverVersion` is a sibling field, but
*not* administrator-configurable — sourced from the build/release itself
(e.g., a Gradle-generated build-time property, exact mechanism a
planning-phase decision) — since it identifies the running software, not
something an administrator has any reason to override. Upon successful
registration, `UserCommandHandler` sends a fixed burst using both:
`001 RPL_WELCOME`, `002 RPL_YOURHOST` (`serverName` + `serverVersion`),
`003 RPL_CREATED` (this process's start time — not a fixed software
release date), `004 RPL_MYINFO` (`serverName`, `serverVersion`, and the
currently-recognized user-mode and channel-mode letters, sourced live
from the same `ChannelMode` catalog research.md "Channel/user mode
extensibility" already established — an empty user-mode list this
release, per FR-044), then `422 ERR_NOMOTD` to close the burst.

**Rationale**: This closes two real gaps at once, not one. First, the
narrower one: the "Connection Registration" contract had referenced "the
standard post-registration burst" since its first draft without ever
defining what that burst actually contains — `001` alone was the only
numeric ever marked `Used`, the same class of "referenced but never
specified" gap the nickname-grammar fix closed earlier for `432`. Second,
the more foundational one it surfaced while investigating: nothing in
this project ever defined *any* server-name concept, even though every
numeric reply this server sends (not just the registration burst) needs
one as its message source — this was a latent gap in the wire protocol's
basic shape, not something specific to registration. Fixing the burst
without also introducing `serverName` would have meant inventing a
placeholder value at exactly the moment it's first needed and never
formalizing it, the same mistake the original "standard...burst" phrase
already made once. Reusing the `ChannelMode` catalog for `004`'s mode
list (rather than hand-maintaining a separate letter list) means it
never drifts out of sync with what `MODE` actually recognizes, including
once a future extension contributes a flag (FR-043).

**Alternatives considered**:
- *Hardcode a fixed server name (e.g., "jircd") instead of making it
  configurable*: rejected — every other identity-shaping value in this
  project (administrator credentials, listener ports, rate limits) is
  configurable via `ServerConfiguration`; a hardcoded name would be the
  one exception, and administrators running multiple named instances
  (e.g., separate test/production deployments) need to tell them apart
  in every client's server-info display.
- *Require the administrator to configure a server name, refuse to start
  otherwise*: rejected — every other optional `ServerConfiguration`
  field already has a sensible default (rate limits, keep-alive timing);
  requiring configuration for a value with an obvious fallback
  (the host's own hostname) would be inconsistent friction, not safety.
- *Implement a real MOTD (file-backed content) instead of always sending
  `422 ERR_NOMOTD`*: rejected for this release — `MOTD` is already
  "Recognized only" with no content-management story anywhere in this
  project; `422` alone is enough to give clients a defined burst-end
  signal without opening a new content/configuration surface this
  release doesn't need.
- *Send `375`/`372`/`376` (an empty MOTD) instead of `422`*: rejected —
  `422 ERR_NOMOTD` is the RFC-correct, honest signal for "no MOTD exists"
  (research.md convention: reuse the exact-fit existing numeric, the same
  choice already made for `476`/`417`); sending an empty MOTD body would
  misrepresent that one was configured.

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

## Channel/user mode extensibility (FR-043/FR-044)

**Decision**: `Channel.activeModes` is a `Set` of `ChannelMode` (data-model.md)
— a Value Object with its own stable `id`, a wire `flag` character, a
`kind` (`BOOLEAN`/`VALUE`/`LIST`/`MEMBER`), and who defines it (`CORE` or a
`ServerExtension`) — not a closed enum. Core unconditionally contributes
exactly two, both `BOOLEAN`: `moderated` (`m`) and `members-only` (`n`)
(FR-013), never gated by `Extension` state (FR-036), the same guarantee
the "Extension system" decision above already gives channel moderation as
a mechanism. A `ServerExtension` MAY additionally contribute further
`BOOLEAN` flags via a new `contributedChannelModes` field (data-model.md
`Extension`) — the same "an extension contributes a named thing core
aggregates" pattern already established for `CapabilityExtension`/
`Capability`, not a new mechanism. No extension contributes one in this
release; the field exists so a future one (e.g., an account module's
registered-channel/user flag, à la classic IRC `+r`) is purely an
extension addition, not a core data-model change. User modes (FR-044) are
expected to follow the identical pattern once the first one is actually
designed, rather than a separate, incompatible mechanism — nothing is
modeled for them yet since none exist.

**Rationale**: The original `sendRestriction` enum (`NONE`/`MEMBERS_ONLY`/
`MODERATED`) was a real design flaw, not just a naming choice: standard
IRC channel modes are an open, per-flag namespace (RFC 2811), and this
project's own roadmap already names a concrete future consumer — Story
3's account module wanting a registered-channel/user flag. A closed enum
would force a core code change (and a new release) for exactly the kind
of extension FR-011 exists to add without one. It was also already subtly
wrong even without that future case: `MEMBERS_ONLY` and `MODERATED` are
independent flags on real IRC servers (a channel can have both, either,
or neither), and a mutually-exclusive enum couldn't represent that —
fixed as part of the same change, not a separate concern.

**`id` vs. `definedBy`-only identity**: An earlier draft of `ChannelMode`
identified a flag only by its `flag` character plus `definedBy` (`CORE` or
an extension id) — no separate name of its own. Validated and rejected in
favor of adding a proper `id`, for three concrete reasons, not just
"consistency for its own sake":
1. **The wire-letter namespace is the actually scarce resource, and
   conflating it with identity makes conflicts harder to report.**
   `flag` has only 52 possible values, shared by every current and future
   core and extension flag combined; two independently-developed
   extensions choosing the same letter for *different* concepts is a real
   collision risk (unlike, say, `Capability` names, which are long enough
   that IRCv3's own convention of vendor-prefixing, e.g. `draft/multiline`,
   makes collisions rare in practice). When that collision happens, the
   rejection error needs to name *what* conflicts, not just *which letter*
   — "extension `registered-channel-plus`'s `registered` flag conflicts
   with extension `read-only-mode`'s `readonly` flag, both claiming `r`"
   is the kind of specific, actionable error the constitution's Principle
   III requires; "flag `r` is claimed twice" is not.
2. **Every other extensible, administrator-facing concept in this data
   model already has a stable `id` independent of its wire form**:
   `Extension.id`, `Capability.name`. A `ChannelMode` identified only by a
   single wire letter would be the one exception, for no principled
   reason — administrators and logs would have no legible way to refer to
   a specific mode.
3. **It costs one field.** `ChannelMode` is already a new Value Object
   with a defining-source field the closed enum didn't have; adding `id`
   alongside `flag` is a marginal addition to a type already being
   introduced, not a separate migration.

**Considering the rest of RFC 2811's standard channel modes surfaced a
second, more important finding**: most of them are not simple on/off
flags. `l` (user limit) and `k` (channel key) each carry a value;
`b`/`e`/`I` (ban/exception/invite-exception) are each a list of masks;
`o`/`v` (operator/voice) are per-*nickname* privileges, not per-channel
state at all — `o` is already exactly what `Channel.operators` models,
just not yet mutable via `MODE`. `Channel.activeModes: Set<ChannelMode>`
can only represent the on/off case. Rather than silently ignoring this
until someone tries to build a `VALUE`/`LIST`-kind extension and discovers
the shape doesn't fit, `ChannelMode.kind` classifies every flag now
(contracts/irc-protocol-commands.md "Full Channel Mode Catalog" catalogs
all eleven), and the `ChannelMode` validation rules explicitly forbid
contributing a `VALUE`/`LIST`-kind flag in this release: `Channel`'s shape
would need to grow (e.g. a `modeParameters` map) to hold one, and that
shape change is deliberately left undesigned until a real consumer
exists, rather than speculatively built for zero current uses — the same
judgment call "Alternatives considered" below already makes for a
general-purpose data bag, applied one layer more specifically.

**`MEMBER`-kind flags in practice (FR-045/FR-046)**: `voice` was this
release's first `MEMBER`-kind `ChannelMode`, and it validated the
taxonomy above: its state doesn't live in `activeModes` at all (a `Set`
can't express "which members," only "which flags") but in its own
dedicated `Channel.voiced` field. `operator` follows the identical
pattern via `MODE +o`/`-o <nickname>` (FR-046) and `Channel.operators` —
originally scoped out on the grounds that first-join-gets-operator
(FR-013) already answers "how does a member become one" and nothing
required a second path, but revisited once "an operator can grant
operator status to another member" was named as an explicit requirement.
Adding it cost nothing new: `Channel.operators` already existed (FR-013
depends on it), so unlike `voice`, no new `Channel` field was needed —
only a `ChannelMode` catalog entry and a `MODE` handler branch identical
in shape to `voice`'s. First-join-gets-operator (FR-013) remains the
*only* way a channel's first operator is established; `MODE +o` is how
operator status subsequently spreads to other members, the same
relationship voice's grant mechanism has to moderated-mode's send-check.

**Validating the extensibility promise against a future `JOIN`-gating
flag**: FR-043 promises a future `ServerExtension` can contribute a new
channel-mode flag "without requiring a change to the server's core
codebase." Checked against a concrete, near-certain future case — an
invite-only extension, gating `JOIN` rather than `PRIVMSG`/`NOTICE` — the
promise as originally built did **not** hold: every enforcement rule
written so far (the `PRIVMSG`/`NOTICE` validation rule above) was phrased
specifically in terms of "does the sender pass `MEMBERS_ONLY`/`MODERATED`,"
with `MessageCommandHandler` as the only place any `ChannelMode` was ever
checked. A flag gating `JOIN` would have had nowhere to plug in without
editing `JoinCommandHandler` to add hardcoded awareness of the new flag —
exactly the "requires a core codebase change" outcome FR-043 exists to
rule out. This was a real gap in an already-committed requirement, not a
hypothetical concern: it surfaced by asking the one question that matters
for any "extensible by design" claim — does it hold for the *next*
concrete case, not just the cases already built?

**Fix**: `ChannelMode` gains a `gates: Set<GateableAction>` field
(`SEND`, `JOIN` today; data-model.md), decoupling "which command does
this flag restrict" from `kind` ("what shape is this flag's data").
Every gateable command's handler iterates currently-active flags whose
`gates` includes its own action and defers the pass/fail decision to
whoever defines that flag — `CORE`'s own logic for the built-ins,
an extension's own logic for a contributed one — rather than the handler
hardcoding per-flag-id knowledge. This is not a new architectural idea:
it's the same "extension contributes a named thing + a hook the relevant
code calls generically" pattern `CapabilityExtension` already uses (a
capability extension exposes a formatting hook `SessionWriter` calls per
recipient; it doesn't get hardcoded into `SessionWriter` itself). Two
findings fell out of applying it:
- Most future `JOIN`-gating `BOOLEAN` flags — invite-only included —
  would not require `Channel`'s shape to grow at all. The gate hook
  receives the acting session and channel; an extension is free to keep
  its own bookkeeping (e.g., an invited-nicknames record) entirely inside
  itself, the same way `cloak` keeps its own hostname-obfuscation logic
  without `ClientSession` needing an extension-specific field for it.
- A `ServerExtension` implementing something like invite-only would also
  typically want to register its own paired command (e.g., claiming the
  already-recognized-but-unimplemented `INVITE`) — already solved,
  unaffected by this change: `admin` and `cloak` already register their
  own command handlers into `jircd-core`'s dispatch table when enabled
  (research.md "Extension system"), so no new mechanism is needed there.

What this fix does **not** claim to solve on its own: `p`/`s`
(private/secret) restrict *visibility* — whether a channel appears in
`TOPIC`/`NAMES`/`LIST` output at all for a non-member — not a simple
permit/deny of attempting an action, which `SEND`/`JOIN` both are. This
turned out to fit `GateableAction` anyway, with one addition: a third
value, `DISCOVER` (FR-047, data-model.md `ChannelMode`), whose gate
*failure* convention differs from `SEND`/`JOIN`'s — it MUST produce the
same response as "channel doesn't exist," not a distinguishable
permission error, since the entire point of `private`/`secret` is that a
non-member can't tell those two cases apart. `TOPIC`-viewing, `NAMES`,
and `LIST` were already grouped under this project's own "discovery
operations" terminology (contracts/irc-protocol-commands.md) before this
change, which is exactly why one shared `DISCOVER` action — checked once
by each of the three handlers, not three separately-named actions — was
the right shape rather than `TOPIC_VIEW`/`NAMES`/`LIST_ENTRY` individually.
`private`/`secret` are `CORE`-defined, not extension-contributed, and
grant an `administratorPrivilege` bypass (FR-047) — mirroring FR-032's
existing hostname-cloaking transparency guarantee for administrators,
extended here to channel visibility.

**Alternatives considered**:
- *Keep the enum, widen it each time a new mode is needed*: rejected —
  exactly the "requires core codebase changes" outcome the Extension
  system exists to avoid (FR-011).
- *A raw `Set<Character>` with no defining-source metadata*: rejected —
  loses the ability to say a flag stops being recognized when its owning
  extension is disabled (FR-020), and loses a place to attach each flag's
  semantics; `ChannelMode` costs one small Value Object for that.
- *Identify a `ChannelMode` by `flag` + `definedBy` alone, no separate
  `id`*: rejected — see "`id` vs. `definedBy`-only identity" above.
- *Pre-design `VALUE`/`LIST` storage on `Channel` now, since RFC 2811
  already defines what they'd need*: rejected as premature — no extension
  needs one yet, and guessing the right shape (a map? a richer per-flag
  state object?) without a concrete consumer risks designing the wrong
  thing and having to redesign it anyway once one exists.
- *A general-purpose "arbitrary key-value extension data" bag on
  `Channel`*: rejected as premature generality — `ChannelMode` solves the
  one concrete, named case (mode flags) this decision exists for, without
  inventing a schema-less mechanism nothing else in this project needs.
- *Leave `gates` out and let each command handler special-case which
  flag ids it cares about (as originally built)*: rejected — this is
  exactly the design validated and found insufficient above; it silently
  breaks FR-043's extensibility promise for any flag that isn't
  `SEND`-gating, and nothing about it would have surfaced that until
  someone actually tried to build a `JOIN`-gating extension and hit a
  wall.
- *A single `gates: GateableAction` (one value, not a set)*: rejected in
  favor of a set — costs nothing extra for this release's six flags
  (each populates zero or one value today), but a set doesn't foreclose
  a hypothetical future flag gating more than one action, and a set is
  no harder to check ("does `gates` contain this action") than a single
  value would be.
- *Three separate gateable actions (`TOPIC_VIEW`, `NAMES`, `LIST_ENTRY`)
  instead of one shared `DISCOVER`*: rejected — `TOPIC`-viewing, `NAMES`,
  and `LIST` already share identical membership-independence and
  identical `private`/`secret` semantics; three actions would just mean
  `private`/`secret` populate `gates: {TOPIC_VIEW, NAMES, LIST_ENTRY}`
  every time, in lockstep, with no case where a future flag would want
  one but not the others. One `DISCOVER` value says the same thing with
  fewer moving parts.
- *Implement `private` and `secret` with distinct behavior (RFC 2811's
  "listed but obscured" nuance for `private`)*: rejected for this
  release — real-world ircds disagree enough on `private`'s exact LIST
  behavior that guessing one would risk enshrining a convention nothing
  else agrees on; treating them identically (full hiding, like `secret`
  unambiguously requires) is simpler, defensible, and easy to relax later
  if a concrete reason to distinguish them ever shows up.

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

## Connection keep-alive (FR-039)

**Decision**: A per-connection `LivenessMonitor`, driven by an injectable
clock/scheduler rather than real time: when a connection has been idle
past a configured probe interval, it sends a server-initiated `PING`; if
no `PONG` arrives within a configured timeout, it sends `ERROR` and closes
the connection through the exact same disconnect path `QuitCommandHandler`
uses (FR-017's cleanup/notification), not a separate one. A
client-initiated `PING` is answered with an immediate `PONG` on any
connection, independently of the monitor's own probing (contracts/
irc-protocol-commands.md "Connection Keep-Alive").

**Rationale**: `jircd-protocol`'s Full Command Catalog already listed
`PING`/`PONG`/`ERROR` as "Implemented" (a completeness commitment this
project makes for every command it claims — see "Wire-protocol command &
numeric completeness" above), but nothing backed that claim: no FR, no
task, no code. That gap matters beyond documentation accuracy — FR-017
already commits to cleaning up a client's channel memberships "whether
gracefully or unexpectedly," but the *unexpected* case as originally
scoped only covers a TCP-level signal (a read returning EOF or an error).
A connection whose network path has gone silent without the OS ever
noticing (a common real-world case: a dead client behind a NAT/firewall
that silently drops idle mappings) produces neither a `QUIT` nor a
TCP-level error — without a keep-alive probe, that session would sit in
`REGISTERED` forever, still counted as a channel member, still a
candidate recipient in fan-out, indefinitely. The injectable-clock
requirement follows "Deterministic testing under concurrency" below: a
timeout mechanism tested with real `Thread.sleep` calls is exactly the
kind of flaky, slow test that principle rules out.

**Alternatives considered**:
- *Rely on TCP keep-alive (`SO_KEEPALIVE`) instead of an application-level
  `PING`/`PONG`*: OS-level keep-alive intervals are typically hours by
  default and not portably tunable to IRC-appropriate timescales (minutes)
  from Java without platform-specific code — and it wouldn't satisfy the
  Full Command Catalog's claim that `PING`/`PONG` are implemented,
  wire-visible commands.
- *No keep-alive at all, treat it as future scope*: rejected because it
  leaves the "Implemented" claim false and FR-017's "unexpectedly"
  disconnect case incomplete for a failure mode (silently dead
  connections) that is common enough in real deployments to matter for
  SC-003's 1,000-connection sustained-operation target — a server slowly
  accumulating ghost sessions would eventually violate it.

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
