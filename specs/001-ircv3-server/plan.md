# Implementation Plan: Modular IRCv3 Chat Server

**Branch**: `001-ircv3-server` | **Date**: 2026-08-15 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-ircv3-server/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Build a standalone, modular IRCv3-capable chat server: clients connect, register a
unique nickname, join channels, and exchange messages in real time (Story 1);
capability-aware clients negotiate individually-toggleable capability
extensions (`message-tags`, `server-time`, `echo-message`) through a
capability-negotiation mechanism that is itself core, always-on protocol
infrastructure (Story 2); administrators enable/disable those capability
extensions, and separately-classed server extensions like cloaking and
in-band administration, at runtime without a server restart, either via
configuration file (Story 4) or in-band IRC commands (Story 6); channel
operators moderate their channels via the classic first-join-gets-operator
model — moderation, like user/channel modes, is core protocol behavior and
is never one of the toggleable extensions (Story 5). Every client identity
is presented in the standard `nickname!ident@hostname` form, with an
optional server extension to cloak the hostname from other clients while
keeping it visible to administrators. Authentication and the account
module (Story 3) are explicitly deferred. Technical approach: a
multi-level, multi-module Gradle/Java 25 project (base package `net.jircd`)
built around four bounded contexts (see "Domain Model & Bounded Contexts")
— a client-reusable wire-protocol library (`jircd-protocol`, the project's
one generic subdomain), the core Session & Messaging domain plus two
supporting subdomains (`jircd-core`), the Capability Negotiation
subdomain's concrete extensions (`jircd-capabilities/`), and the Server
Extensibility subdomain's concrete extensions (`jircd-server-extensions/`);
virtual-thread-per-connection networking on top of `java.nio` (blocking
I/O, including blocking TLS via `SSLSocket` — no external networking
framework), with message fan-out to other sessions always going through
each recipient's own outbound queue and writer thread rather than
cross-thread socket writes; a small custom SPI-based `Extension` system for
runtime hot-toggle, with explicit classloader-delegation and
quiesce-before-unload semantics (chosen over JPMS, whose static module
graph is a poor fit for dynamic enable/disable); and an in-memory-only
data model matching the initial release's "no persistent storage required"
scope.

## Technical Context

**Language/Version**: Java 25 (LTS)

**Primary Dependencies**: None required for the core protocol — built on the JDK's
own `java.nio` channels and Java 21+ virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`)
for connection concurrency, and `javax.net.ssl` for the optional TLS listener
(FR-018). SLF4J + Logback for logging (FR-019). SnakeYAML for the human-edited
extension/server configuration file (FR-012). A salted, computationally-expensive
password-hashing library (e.g., bcrypt/Argon2) for administrator credentials
(FR-034). NEEDS CLARIFICATION: none — resolved in research.md (see
"Networking model", "Extension system", and "Administrator credential storage").

**Storage**: N/A for the initial release — all live state (client sessions, channel
membership, nickname registry) is in-memory only, matching FR-001–FR-020's
scope. The account module (FR-023/FR-024) will need its own storage, but it is
deferred with Story 3 and out of scope for this plan.

**Testing**: JUnit 5 (Jupiter) + AssertJ for unit tests. Protocol-level
integration tests drive a running server instance over real TCP sockets
(in-process, ephemeral port) rather than mocking the network layer, per the
constitution's Testing Standards principle. NEEDS CLARIFICATION: none —
resolved in research.md (see "Deterministic testing under concurrency").

**Target Platform**: Linux server (primary deployment target), portable to any
JVM 25-capable host.

**Project Type**: Single backend network service, delivered as a multi-level,
multi-module Gradle build (build-time modularity, with a reusable protocol
library separated out) plus a custom runtime `Extension` system scoped to
optional capability/server extensions only (FR-011's hot-toggle requirement)
— see "Structure Decision" and "Domain Model & Bounded Contexts" below.

**Performance Goals**: Derived from spec Success Criteria — message delivery
to all channel members within 1s (SC-002) at up to 1,000 simultaneous
connections (SC-003); extension enable/disable changes reflected within 1
minute with no restart (SC-005); rate-limited abusive traffic must not
degrade delivery latency for well-behaved clients (SC-006).

**Constraints**: No server restart for extension state changes, ever (FR-011);
TLS MUST be offered but MUST NOT be mandatory (FR-018); the initial release
MUST NOT require any other server instance or external service to run
(FR-021); nickname/channel uniqueness, channel message delivery, and
connection-loss handling MUST be implemented so they remain extensible to a
future networked (federated) scope without redesign (FR-022); the
connection-acceptance path MUST call out to an as-yet-unclaimed extension
point before a session proceeds toward registration, so a future
connection-admission `ServerExtension` (e.g., a "G-line"-style network-mask
block) can be added without changing that path's core logic (FR-066).

**Scale/Scope**: ~1,000 concurrent client connections (SC-003); 6 user
stories (Stories 1, 2, 4, 5, 6, 7 mandatory for this plan; Story 3 and
everything that depends on it are deferred and excluded from this plan's
scope); 65 functional requirements, 57 of which are in scope for this
release (FR-009, FR-010, FR-023, FR-024, FR-026, FR-027, FR-028, FR-029 are
deferred).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Status |
|---|---|---|
| I. Code Quality | Multi-level, multi-module Gradle build enables single-responsibility boundaries down to the `jircd-capabilities/*` and `jircd-server-extensions/*` level, with the physical layout traced directly to the bounded contexts in "Domain Model & Bounded Contexts"; static analysis (Spotless + SpotBugs, see research.md) runs as a Gradle task gating merge; public SPI interfaces (`Extension`, `CapabilityExtension`, `ServerExtension`, the protocol/core boundary) documented at their definition. | PASS |
| II. Testing Standards | Every FR gets unit and/or protocol-level integration test coverage (see quickstart.md); tests must be deterministic — flagged risk (timing-based SC assertions) has a mitigation documented in research.md ("Deterministic testing under concurrency"); the message fan-out design (research.md "Message fan-out concurrency model") is itself unit-testable without timing assertions (queue contents, not wall-clock races). | PASS |
| III. User Experience Consistency | The "user-facing surface" is the IRC wire protocol: numeric replies and error messages MUST be consistent in format across all commands, core behavior, and extensions (FR-002, FR-012, FR-014, FR-015, FR-033); quickstart.md's validation scenarios double as the required manual usage-scenario check, now covering Story 6's admin commands too. | PASS |
| IV. Performance Requirements | SC-002/003/005/006/009 already state explicit latency/throughput/rate budgets; a load-test harness for connection handling and message routing is planned as part of the test suite (research.md "Deterministic testing under concurrency"); the per-session outbound queue (research.md "Message fan-out concurrency model") is the mechanism SC-002/006 are actually measured against, not just an implementation detail. | PASS |

No violations requiring justification. Complexity Tracking table below is intentionally empty.

**Post-Design Re-check** (after Phase 1 — data-model.md, contracts/,
quickstart.md): No new violations introduced. This check has been re-run
against the plan's full current scope, including the identity/cloak/
administration additions (FR-030–036, Story 6) and the fixes made after a
plan critique — not just the original Phase 0/1 draft:

- The **credential-hashing dependency** added for FR-034 (research.md
  "Administrator credential storage") reuses the same approach FR-024
  already required, so it isn't new complexity, just the same decision
  applied twice — no separate Code Quality concern.
- The **`OPER` in-band privilege-escalation command** (Story 6) is a
  security-relevant surface, not just a feature: it's covered by FR-019's
  security-event logging (failed attempts are logged) and FR-033's
  privilege check on every subsequent admin command, so it doesn't
  introduce an unreviewed gap — but it's called out here explicitly since
  the original Constitution Check pass predates Story 6 and never
  evaluated it.
- The extension system's per-extension classloader design (research.md
  "Extension system") now has explicit delegation-order and quiesce-before-
  unload rules (added in response to a plan critique) — the original pass
  had asserted isolation without specifying how it actually holds up under
  concurrent use; that gap is closed.
- The **message fan-out concurrency model** and the **TLS approach
  revision** (both added/changed in response to the same critique) were
  not present in the original Constitution Check pass at all. Both are
  now accounted for under Testing Standards and Performance Requirements
  above.

Gate remains PASS.

## Project Structure

### Documentation (this feature)

```text
specs/001-ircv3-server/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
jircd-protocol/                       # GENERIC SUBDOMAIN — wire-protocol library: message
├── src/main/java/                     # framing, command/reply parsing & serialization,
│   └── net/jircd/protocol/           # hostmask formatting (FR-030), CAP negotiation grammar.
└── src/test/java/                     # No server logic — reusable by a future IRC client.

jircd-core/                           # CORE DOMAIN + supporting subdomains, built on
├── src/main/java/                     # jircd-protocol. Internally packaged by bounded
│   └── net/jircd/core/                # context (see "Domain Model & Bounded Contexts"):
│       ├── session/                   #   session/     — Session & Messaging (core domain):
│       ├── capability/                #                  registration, nickname/channel
│       ├── extension/                 #                  identity, message routing, rate
│       └── config/                    #                  limiting, TLS, moderation (FR-013,
└── src/test/java/                     #                  014,036 — always present, never
                                       #                  an Extension)
                                       #   capability/  — Capability Negotiation (supporting):
                                       #                  the CAP mechanism itself (FR-006–
                                       #                  008,035 — always present)
                                       #   extension/   — Server Extensibility (supporting):
                                       #                  the ExtensionRegistry (FR-011) that
                                       #                  jircd-capabilities/* and
                                       #                  jircd-server-extensions/* plug into
                                       #   config/      — Server Configuration loading (FR-012)

jircd-capabilities/                   # CAPABILITY NEGOTIATION (supporting subdomain) — one
├── message-tags/                      # CapabilityExtension per in-scope IRCv3 capability
│   ├── src/main/java/                 # (FR-025), each independently toggleable (FR-011).
│   │   └── net/jircd/capabilities/messagetags/
│   └── src/test/java/                 # Multi-level Gradle subprojects:
├── server-time/                       # `:jircd-capabilities:message-tags`, etc.
│   ├── src/main/java/
│   │   └── net/jircd/capabilities/servertime/
│   └── src/test/java/
└── echo-message/
    ├── src/main/java/
    │   └── net/jircd/capabilities/echomessage/
    └── src/test/java/

jircd-server-extensions/              # SERVER EXTENSIBILITY (supporting subdomain) — one
├── cloak/                              # ServerExtension per non-capability optional behavior:
│   ├── src/main/java/                 # administrator/operational concerns, never CAP-
│   │   └── net/jircd/serverextensions/cloak/  # negotiated by a client. Optional hostname-
│   └── src/test/java/                 # obfuscation extension; real value always stays
│                                       # queryable by administrators via `admin` (FR-031,
│                                       # research.md "Cloak extension boundary")
└── admin/                             # In-band administrative command interface: OPER-style
    ├── src/main/java/                 # privilege grant + admin commands (extension toggle,
    │   └── net/jircd/serverextensions/admin/  # real-hostname lookup) (FR-032–034, Story 6).
    └── src/test/java/                 # Optional: disabling it leaves the configuration-file
                                       # path (Story 4) as the sole administration route.

jircd-server/                         # APPLICATION LAYER (composition root, not a domain
├── src/main/java/                     # bounded context) — the executable: reads Server
│   └── net/jircd/server/             # Configuration, starts jircd-core, discovers and loads
└── src/test/java/                     # enabled jircd-capabilities/* and
                                       # jircd-server-extensions/* subprojects

jircd-integration-tests/              # Cross-context, protocol-level tests: real TCP clients
└── src/test/java/                     # against a running jircd-server, covering each user
                                       # story's Independent Test and Acceptance Scenarios

build.gradle.kts                      # Root build; settings.gradle.kts declares subprojects,
settings.gradle.kts                   # including the nested jircd-capabilities:* and
                                       # jircd-server-extensions:* subprojects
```

**Structure Decision**: A multi-level, multi-module Gradle build (Option 1
shape, adapted for Java's package-per-module convention, base package
`net.jircd`) is used for build-time modularity, with the physical layout
now driven directly by the domain model in "Domain Model & Bounded
Contexts" below rather than by a single generic "core vs. modules" split:

1. **Protocol vs. server** (`jircd-protocol` vs. `jircd-core`): wire-format
   parsing/serialization and hostmask formatting have no server-side state
   or behavior — separating them into their own module means a future IRC
   *client* library could depend on `jircd-protocol` without pulling in any
   server engine code. `jircd-core` depends on `jircd-protocol`, never the
   reverse. This is the project's one Generic Subdomain.
2. **Core vs. supporting, inside `jircd-core`**: Session & Messaging
   (including moderation, FR-013,014,036) is the Core Domain and is
   package-separated (`session/`) from the two supporting subdomains it
   depends on but isn't defined by — Capability Negotiation's mechanism
   (`capability/`, FR-006–008,035) and Server Extensibility's registry
   (`extension/`, FR-011). All three stay in one Gradle module today
   because they're small and tightly coupled; the package split keeps
   their bounded contexts distinguishable without paying for physical
   separation the project doesn't need yet (see "Domain Model" below for
   why not every bounded context gets its own Gradle module).
3. **Capability Negotiation's concrete extensions** (`jircd-capabilities/`)
   vs. **Server Extensibility's concrete extensions**
   (`jircd-server-extensions/`): these were one undifferentiated
   `jircd-modules/` tree in the previous revision of this plan; splitting
   them reflects that they are genuinely different domain concepts, not
   two instances of the same thing — a `CapabilityExtension` is
   client-negotiable via `CAP` and appears in `CAP LS`; a `ServerExtension`
   (cloak, admin) is administrator-only and a client never sees it exists.
   Both still implement one shared `Extension` lifecycle contract and are
   tracked by the same `ExtensionRegistry` in `jircd-core` (FR-011,
   research.md "Extension system") — only their *role* differs, not
   their lifecycle mechanics. Each concrete extension is its own nested
   Gradle subproject: `:jircd-capabilities:message-tags`,
   `:jircd-server-extensions:cloak`, etc.
4. **Application entry point** (`jircd-server`): the composition root that
   wires configuration, `jircd-core`, and the enabled extensions from both
   trees together. Not a bounded context itself — it depends on all of the
   above without contributing its own domain vocabulary.

`jircd-account` (Story 3's account bounded context, FR-023/FR-024/FR-026/
FR-027) and any federation-related module (FR-021/FR-022/FR-028/FR-029)
are deliberately **not** created by this plan — both are deferred in the
spec, and scaffolding unused modules now would violate the constitution's
"no speculative generality" guidance (Development Workflow section). They
are called out here only so a future plan for Story 3 or federation can
slot them in (as their own bounded context(s), per the same modeling
approach) without restructuring what's built now.

FR-066's future connection-admission capability (a "G-line"-style
network-mask block) is a narrower case than either of those: it doesn't
need its own bounded context or module scaffolding today, or even when
it's eventually built — it fits the existing Server Extensibility
pattern directly, as a future `:jircd-server-extensions:gline` (or
similarly named) subproject claiming its own `extensionPoint`, the same
way `cloak` claims `hostname-display` today. The one piece of this
plan's *current* scope FR-066 actually constrains is `jircd-core`'s
connection-acceptance path (`PlaintextListener`/`TlsListener`/
`ConnectionHandler`): it MUST already call out to that not-yet-claimed
extension point on every accepted connection, always allowing the
connection through today (nothing claims it), so that a future `gline`
extension is a pure extension addition later, not a change to this
path's shape — see "Domain Model & Bounded Contexts" below and
research.md "Connection-admission extension point" for why the existing
`extensionPoint` mechanism is already a structural fit, unlike
federation's genuinely undecided extension shape.

## Domain Model & Bounded Contexts

This section makes the domain analysis behind "Project Structure" explicit,
so the physical layout is a consequence of the model, not a naming
exercise applied after the fact.

**Subdomain classification** (DDD's core/supporting/generic distinction):

| Subdomain | Kind | Why |
|---|---|---|
| Session & Messaging (registration, nickname/channel identity, message delivery, moderation) | **Core** | This is the actual reason the product exists — real-time IRC chat. Everything else supports it. |
| Capability Negotiation (CAP mechanism + concrete capabilities) | Supporting | IRC/IRCv3-specific and necessary, but it's an enhancement layer on top of chat, not chat itself. |
| Server Extensibility & Administration (extension registry + cloak/admin) | Supporting | Operational/administrative concerns specific to running this service, not to chatting. |
| Wire Protocol (framing, parsing, hostmask formatting) | **Generic** | Not IRC-*business*-specific — the same kind of code any line-based text protocol needs, and explicitly designed for reuse outside this product (a future client). |

**Bounded contexts and their ubiquitous language**:

| Bounded Context | Gradle location | Key terms |
|---|---|---|
| Wire Protocol | `jircd-protocol` | Message, Command, Reply, Hostmask |
| Session & Messaging | `jircd-core/session` | `ClientSession` (aggregate root), `Channel` (aggregate root), `UserIdentity` (value object — no identity of its own beyond its owning session), Registration, Membership, Moderation |
| Capability Negotiation | `jircd-core/capability` + `jircd-capabilities/*` | `Capability`, `CapabilityExtension`, Negotiation |
| Server Extensibility | `jircd-core/extension` + `jircd-server-extensions/*` | `Extension`, `ExtensionRegistry`, `ServerExtension`, Extension Point |
| Administration | `jircd-server-extensions/admin` | Administrator Privilege, Administrative Command |

A deliberate, stated principle: **bounded contexts and Gradle modules are
not required to be 1:1.** Session & Messaging, Capability Negotiation's
mechanism, and Server Extensibility's registry are three distinct bounded
contexts that currently share one Gradle module (`jircd-core`) because
they're small and change together today; they get separate *packages*
(`session/`, `capability/`, `extension/`) so the model stays legible, and
can be promoted to separate Gradle modules later without a rewrite if they
grow apart. Conversely, `jircd-capabilities` and `jircd-server-extensions`
got physical separation now because the user-facing distinction (client-
negotiable vs. administrator-only) was judged significant enough to
enforce at the build level, not just in package names.

**Terminology correction from the previous plan revision**: the generic
term "Module" (and its `ServerModule`/`ModuleRegistry` types) is replaced
throughout research.md, data-model.md, and contracts/ by **`Extension`**
(`ExtensionRegistry`), with two named specializations:

- **`CapabilityExtension`** — an `Extension` that also provides exactly
  one `Capability` (`message-tags`, `server-time`, `echo-message`); lives
  under `jircd-capabilities/`.
- **`ServerExtension`** — an `Extension` with no client-negotiable
  `Capability` (`cloak`, `admin`); lives under `jircd-server-extensions/`.

Both share the same lifecycle (`ENABLED`/`DISABLED`/`FAILED`, no-restart
toggle, FR-011) and the same `ExtensionRegistry`, so this is a shared base
interface with two role interfaces, not two unrelated systems — see
data-model.md's `Extension` entity. "Module" was generic, implementation-
flavored language; "Extension" is the real-world IRCv3 specification's own
term for a capability-negotiated add-on, and using it project-wide —
including the admin-facing `EXTENSION` command and the configuration
file's `capabilities`/`server-extensions` sections
(contracts/server-configuration.md, split by kind so the config file
mirrors the `CapabilityExtension`/`ServerExtension` distinction directly)
— keeps the same word in the administrator's vocabulary, the spec's
vocabulary, and the code's vocabulary, which is the actual point of
applying DDD here rather than renaming for its own sake.

**FR-066's connection-admission extension point**: `ExtensionRegistry`'s
existing `extensionPoint` single-owner-claim mechanism (data-model.md
`Extension`) already generalizes to "an extension supplies a value/
decision core code consumes at a specific point" — `cloak` claiming
`hostname-display` is today's only example. FR-066 adds a second,
currently-unclaimed named point, `connection-admission`, that `jircd-core`
must consult once per accepted connection before that session may
proceed toward registration (FR-001): with nothing claiming it (this
release's actual state), the connection always proceeds, the same
default-permissive behavior `hostname-display` has when `cloak` is
disabled. No new bounded context and no new Gradle subproject are needed
now — only the call-out point itself, inside Session & Messaging
(`jircd-core/session`), the same package `ConnectionHandler` already
lives in. A future `gline` `ServerExtension` claiming
`connection-admission` would live under `jircd-server-extensions/`
alongside `cloak`/`admin`, following the exact same pattern, whenever it
is actually planned and built.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No violations — this table is intentionally empty. See the Constitution Check
section above.
