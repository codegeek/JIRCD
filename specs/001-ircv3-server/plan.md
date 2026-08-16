# Implementation Plan: Modular IRCv3 Chat Server

**Branch**: `001-ircv3-server` | **Date**: 2026-08-15 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-ircv3-server/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Build a standalone, modular IRCv3-capable chat server: clients connect, register a
unique nickname, join channels, and exchange messages in real time (Story 1);
capability-aware clients negotiate individually-toggleable capabilities
(`message-tags`, `server-time`, `echo-message`) through a capability-negotiation
mechanism that is itself core, always-on protocol infrastructure (Story 2);
administrators enable/disable those optional capability modules at runtime
without a server restart, either via configuration file (Story 4) or in-band
IRC commands (Story 6); channel operators moderate their channels via the
classic first-join-gets-operator model — moderation, like user/channel modes,
is core protocol behavior and is never one of the toggleable modules
(Story 5). Every client identity is presented in the standard
`nickname!ident@hostname` form, with an optional module to cloak the
hostname from other clients while keeping it visible to administrators.
Authentication and the account module (Story 3) are explicitly deferred.
Technical approach: a multi-level, multi-module Gradle/Java 25 project (base
package `net.jircd`) with a standalone, client-reusable wire-protocol
library (`jircd-protocol`) separated from the server engine (`jircd-core`)
and optional feature modules grouped under `jircd-modules/`; virtual-thread-
per-connection networking on top of `java.nio` (no external networking
framework); a small custom SPI-based module system for runtime hot-toggle of
`jircd-modules/*` (chosen over JPMS, whose static module graph is a poor fit
for dynamic enable/disable); and an in-memory-only data model matching the
initial release's "no persistent storage required" scope.

## Technical Context

**Language/Version**: Java 25 (LTS)

**Primary Dependencies**: None required for the core protocol — built on the JDK's
own `java.nio` channels and Java 21+ virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`)
for connection concurrency, and `javax.net.ssl` for the optional TLS listener
(FR-018). SLF4J + Logback for logging (FR-019). SnakeYAML for the human-edited
module/server configuration file (FR-012). A salted, computationally-expensive
password-hashing library (e.g., bcrypt/Argon2) for administrator credentials
(FR-034). NEEDS CLARIFICATION: none — resolved in research.md (see
"Networking model", "Module system", and "Administrator credential storage").

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
library separated out) plus a custom runtime module/plugin system scoped to
optional capability/feature modules only (FR-011's hot-toggle requirement) —
see "Structure Decision" below.

**Performance Goals**: Derived from spec Success Criteria — message delivery
to all channel members within 1s (SC-002) at up to 1,000 simultaneous
connections (SC-003); module enable/disable changes reflected within 1
minute with no restart (SC-005); rate-limited abusive traffic must not
degrade delivery latency for well-behaved clients (SC-006).

**Constraints**: No server restart for module state changes, ever (FR-011);
TLS MUST be offered but MUST NOT be mandatory (FR-018); the initial release
MUST NOT require any other server instance or external service to run
(FR-021); nickname/channel uniqueness, channel message delivery, and
connection-loss handling MUST be implemented so they remain extensible to a
future networked (federated) scope without redesign (FR-022).

**Scale/Scope**: ~1,000 concurrent client connections (SC-003); 6 user
stories (Stories 1, 2, 4, 5, 6 mandatory for this plan; Story 3 and
everything that depends on it are deferred and excluded from this plan's
scope); 36 functional requirements, 28 of which are in scope for this
release (FR-009, FR-010, FR-023, FR-024, FR-026, FR-027, FR-028, FR-029 are
deferred).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Status |
|---|---|---|
| I. Code Quality | Multi-module Gradle build enables single-responsibility module boundaries; static analysis (Spotless for formatting/style + SpotBugs for defect patterns, see research.md) runs as a Gradle task gating merge; public SPI interfaces documented at their definition. | PASS |
| II. Testing Standards | Every FR gets unit and/or protocol-level integration test coverage (see quickstart.md); tests must be deterministic — flagged risk (timing-based SC assertions) has a mitigation documented in research.md ("Deterministic testing under concurrency") rather than left unresolved. | PASS |
| III. User Experience Consistency | The "user-facing surface" here is the IRC wire protocol: numeric replies and error messages MUST be consistent in format across all commands and modules (FR-002, FR-012, FR-014, FR-015 already require clear, consistent errors); quickstart.md's validation scenarios double as the required manual usage-scenario check. | PASS |
| IV. Performance Requirements | SC-002/003/005/006 already state explicit latency/throughput/rate budgets; a load-test harness for connection handling and message routing is planned as part of the test suite (see research.md "Deterministic testing under concurrency" and quickstart.md). | PASS |

No violations requiring justification. Complexity Tracking table below is intentionally empty.

**Post-Design Re-check** (after Phase 1 — data-model.md, contracts/,
quickstart.md): No new violations introduced. The module system's
per-module classloader design (research.md "Module system") and the
two-tier testing strategy (research.md "Deterministic testing under
concurrency") were the two design decisions with the most potential to
strain Code Quality/Testing Standards, and both were chosen specifically
to satisfy those principles rather than work around them. Gate remains
PASS.

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
jircd-protocol/                       # Wire-protocol library: message framing, command/
├── src/main/java/                     # reply parsing & serialization, hostmask formatting
│   └── net/jircd/protocol/           # (FR-030), CAP negotiation grammar. No server logic —
└── src/test/java/                     # deliberately reusable by a future IRC client (FR-030,
                                       # contracts/irc-protocol-commands.md, irc-numeric-replies.md)

jircd-core/                           # Server engine, built on jircd-protocol: connection
├── src/main/java/                     # lifecycle, nickname/channel registry, message routing,
│   └── net/jircd/core/               # rate limiting, TLS listener, config loading, identity
└── src/test/java/                     # presentation. Core protocol behavior that is always
                                       # present (never a toggleable module): capability-
                                       # negotiation orchestration (FR-006–008,035) and channel
                                       # moderation (FR-013,014,036). Also owns the Module
                                       # SPI/registry that jircd-modules/* plug into (FR-011).

jircd-modules/                        # Container for optional, independently toggleable
├── capability-message-tags/           # modules (FR-011) — each a `ServerModule` (research.md
│   ├── src/main/java/                 # "Module system") providing one capability (FR-025) or
│   │   └── net/jircd/modules/messagetags/  # other optional behavior. Multi-level Gradle
│   └── src/test/java/                 # subprojects: `:jircd-modules:capability-message-tags`
├── capability-server-time/            # etc.
│   ├── src/main/java/
│   │   └── net/jircd/modules/servertime/
│   └── src/test/java/
├── capability-echo-message/
│   ├── src/main/java/
│   │   └── net/jircd/modules/echomessage/
│   └── src/test/java/
├── cloak/                             # Optional hostname-obfuscation module; real value
│   ├── src/main/java/                 # always stays queryable by administrators via the
│   │   └── net/jircd/modules/cloak/  # admin module (FR-031, research.md "Cloak module
│   └── src/test/java/                 # boundary")
└── admin/                             # In-band administrative command interface: OPER-style
    ├── src/main/java/                 # privilege grant + admin commands (module toggle,
    │   └── net/jircd/modules/admin/  # real-hostname lookup) (FR-032–034, Story 6). Optional:
    └── src/test/java/                 # disabling it leaves the configuration-file path (Story 4)
                                       # as the sole administration route.

jircd-server/                         # Main executable application: reads Server
├── src/main/java/                     # Configuration, starts jircd-core, discovers and loads
│   └── net/jircd/server/             # enabled jircd-modules/* subprojects
└── src/test/java/

jircd-integration-tests/              # Cross-module, protocol-level tests: real TCP clients
└── src/test/java/                     # against a running jircd-server, covering each user
                                       # story's Independent Test and Acceptance Scenarios

build.gradle.kts                      # Root build; settings.gradle.kts declares subprojects,
settings.gradle.kts                   # including the nested jircd-modules:* subprojects
```

**Structure Decision**: A multi-level, multi-module Gradle build (Option 1
shape, adapted for Java's package-per-module convention, base package
`net.jircd`) is used for build-time modularity. Four boundaries drive the
layout, each corresponding to a decision made in this planning round:

1. **Protocol vs. server** (`jircd-protocol` vs. `jircd-core`): wire-format
   parsing/serialization and hostmask formatting have no server-side state
   or behavior — separating them into their own module means a future IRC
   *client* library could depend on `jircd-protocol` without pulling in any
   server engine code. `jircd-core` depends on `jircd-protocol`, never the
   reverse.
2. **Core vs. optional** (`jircd-core` vs. `jircd-modules/*`): capability
   negotiation's mechanism and channel moderation are core, always-on
   protocol behavior (FR-035, FR-036) — equivalent to RFC 1459/2812 user
   and channel modes — and live in `jircd-core`, never in the toggleable
   module system. Only individual capabilities (`message-tags`,
   `server-time`, `echo-message`), cloaking, and in-band administration are
   optional and live under `jircd-modules/`, each as its own nested Gradle
   subproject discovered by `jircd-core`'s Module SPI/registry (FR-011,
   research.md "Module system"). Gradle subprojects alone only give
   compile-time separation, so `jircd-core` additionally does runtime
   module discovery/lifecycle management.
3. **Grouping optional modules** (`jircd-modules/`): a multi-level Gradle
   layout (`:jircd-modules:capability-message-tags`, etc.) keeps every
   toggleable module physically grouped and visually distinct from
   `jircd-core`'s always-on code, reinforcing the core/optional boundary
   at the file-tree level, not just in documentation.
4. **Application entry point** (`jircd-server`): the executable that wires
   configuration, `jircd-core`, and the enabled `jircd-modules/*` together.

`jircd-account` (Story 3's account module, FR-023/FR-024/FR-026/FR-027) and
any federation-related module (FR-021/FR-022/FR-028/FR-029) are deliberately
**not** created by this plan — both are deferred in the spec, and
scaffolding unused modules now would violate the constitution's "no
speculative generality" guidance (Development Workflow section). They are
called out here only so a future plan for Story 3 or federation can slot
them into `jircd-modules/` (or `jircd-core`, if federation turns out to need
core-level changes per FR-022) without restructuring what's built now.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No violations — this table is intentionally empty. See the Constitution Check
section above.
