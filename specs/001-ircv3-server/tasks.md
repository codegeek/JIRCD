---

description: "Task list for the Modular IRCv3 Chat Server feature"
---

# Tasks: Modular IRCv3 Chat Server

**Input**: Design documents from `/specs/001-ircv3-server/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md (all present)

**Tests**: Included. The project constitution's Testing Standards principle
mandates automated coverage for every feature's primary behavior and
documented edge cases before it's considered done, and plan.md's
Constitution Check commits to "every FR gets unit and/or protocol-level
integration test coverage" — so this is a project-level requirement, not
an optional add-on. Per research.md "Deterministic testing under
concurrency", timing-sensitive Success Criteria (SC-002, SC-003, SC-006)
are covered by separate `@Tag("load")` tests, kept out of the default fast
suite so it stays deterministic.

**Organization**: Tasks are grouped by user story (spec.md Stories 1, 2,
4, 5, 6 — Story 3 is deferred and excluded, see spec.md Clarifications) to
enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US4, US5, US6)
- File paths are relative to the repository root and follow plan.md's
  Project Structure (base package `net.jircd`).

## Path Conventions

Multi-level, multi-module Gradle project (plan.md "Project Structure"):

- `jircd-protocol/` — wire-protocol library (generic subdomain)
- `jircd-core/` — server engine, packaged by bounded context:
  `session/` (core domain), `capability/` and `extension/` (supporting
  subdomains), `config/`
- `jircd-capabilities/<name>/` — one Gradle subproject per `CapabilityExtension`
- `jircd-server-extensions/<name>/` — one Gradle subproject per `ServerExtension`
- `jircd-server/` — application composition root
- `jircd-integration-tests/` — cross-context, protocol-level tests

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Gradle project skeleton, toolchain, and quality tooling — no
feature code yet.

- [ ] T001 Create the multi-level Gradle project skeleton: `settings.gradle.kts` including `jircd-protocol`, `jircd-core`, `jircd-capabilities`, `:jircd-capabilities:message-tags`, `:jircd-capabilities:server-time`, `:jircd-capabilities:echo-message`, `jircd-server-extensions`, `:jircd-server-extensions:cloak`, `:jircd-server-extensions:admin`, `jircd-server`, `jircd-integration-tests`, plus empty `src/main/java`/`src/test/java` directories per plan.md's Project Structure tree
- [ ] T002 Configure root `build.gradle.kts`: Java 25 toolchain, shared repositories, and a `subprojects {}` block applying the Java plugin, UTF-8 source encoding, and JUnit Platform test execution to every subproject
- [ ] T003 [P] Add a Gradle version catalog in `gradle/libs.versions.toml` declaring SLF4J, Logback, SnakeYAML, JUnit 5 (Jupiter), AssertJ, a salted/computationally-expensive password-hashing library (research.md "Administrator credential storage"), the Spotless Gradle plugin, and the SpotBugs Gradle plugin
- [ ] T004 [P] Configure Spotless in root `build.gradle.kts`, applied to all subprojects (research.md "Static analysis / code quality tooling")
- [ ] T005 [P] Configure SpotBugs in root `build.gradle.kts`, applied to all subprojects, failing the build on findings (research.md "Static analysis / code quality tooling")
- [ ] T006 [P] Configure JUnit 5 test tagging in root `build.gradle.kts`: the default `test` task excludes tests tagged `"load"`; add a separate `loadTest` Gradle task that runs only `"load"`-tagged tests (research.md "Deterministic testing under concurrency")
- [ ] T007 [P] Create `jircd-protocol/build.gradle.kts` (JUnit 5 + AssertJ test dependencies only; no other module dependencies — research.md "Protocol/server boundary")
- [ ] T008 Create `jircd-core/build.gradle.kts`: depends on `jircd-protocol`; runtime dependencies on SLF4J, Logback, SnakeYAML, and the password-hashing library; JUnit 5 + AssertJ test dependencies
- [ ] T009 [P] Create `jircd-capabilities/message-tags/build.gradle.kts`, `jircd-capabilities/server-time/build.gradle.kts`, and `jircd-capabilities/echo-message/build.gradle.kts`, each depending on `jircd-core` (for the `Extension`/`CapabilityExtension` SPI, research.md "Extension system")
- [ ] T010 [P] Create `jircd-server-extensions/cloak/build.gradle.kts` and `jircd-server-extensions/admin/build.gradle.kts`, each depending on `jircd-core` (for the `Extension`/`ServerExtension` SPI); `admin` additionally depends on the password-hashing library (FR-034)
- [ ] T011 Create `jircd-server/build.gradle.kts`: depends on `jircd-core`, and declares a runtime dependency on every `jircd-capabilities/*` and `jircd-server-extensions/*` subproject so they're on the classpath for `ServiceLoader` discovery (research.md "Extension system")
- [ ] T012 Create `jircd-integration-tests/build.gradle.kts`: depends on `jircd-server` and `jircd-protocol`; JUnit 5 + AssertJ
- [ ] T013 [P] Configure Logback in `jircd-server/src/main/resources/logback.xml` with structured, leveled output and file rotation (research.md "Logging", FR-019)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure every user story depends on — wire
protocol, connection/session lifecycle, channel aggregate, networking,
the Extension system, capability-negotiation mechanism, and Server
Configuration loading plus its manual reload mechanism.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Wire protocol (`jircd-protocol`)

- [ ] T014 Define the core message model, including a complete `Command` catalog covering every RFC 1459/2812 command plus `CAP`/`AUTHENTICATE`/`TAGMSG` (research.md "Wire-protocol command & numeric completeness", contracts/irc-protocol-commands.md "Full Command Catalog") — not only the commands `jircd-core` implements this release — plus the message tag map, in `jircd-protocol/src/main/java/net/jircd/protocol/Message.java`
- [ ] T015 [P] Implement the line-based message parser (raw line → `Message`) in `jircd-protocol/src/main/java/net/jircd/protocol/MessageParser.java`
- [ ] T016 [P] Implement the message serializer (`Message` → raw line, including `message-tags`-style tag prefix formatting) in `jircd-protocol/src/main/java/net/jircd/protocol/MessageSerializer.java`
- [ ] T017 [P] Implement hostmask formatting (`nickname!ident@hostname`, FR-030) and the nickname/username content grammar (RFC 2812 §2.3.1 nickname BNF; username content rule) both defined in contracts/irc-protocol-commands.md "Connection Registration Grammar" — nickname/username format validation is a wire-protocol rule, not a `jircd-core` handler concern, so it lives here alongside `Hostmask`, which already needs the same rule to compose a valid `nickname!ident@hostname` (research.md "Connection registration grammar") in `jircd-protocol/src/main/java/net/jircd/protocol/Hostmask.java`
- [ ] T018 [P] Implement the CAP negotiation grammar (`CAP LS`/`REQ`/`ACK`/`NAK`/`END` line formats) in `jircd-protocol/src/main/java/net/jircd/protocol/CapabilityNegotiationGrammar.java`
- [ ] T019 [P] Define the complete numeric reply catalog per `contracts/irc-numeric-replies.md`'s "Full Numeric Catalog" — every RFC 1459/2812 `RPL_*`/`ERR_*` numeric, not only the "Used in This Release" subset — in `jircd-protocol/src/main/java/net/jircd/protocol/NumericReply.java`
- [ ] T020 [P] Unit tests for `MessageParser` covering valid and malformed lines (FR-015), including a completeness assertion that every command in the Full Command Catalog (contracts/irc-protocol-commands.md) parses to a recognized `Command` entry regardless of whether `jircd-core` implements it yet in `jircd-protocol/src/test/java/net/jircd/protocol/MessageParserTest.java`
- [ ] T021 [P] Unit tests for `MessageSerializer` and `Hostmask` formatting, including a completeness assertion that every numeric in the Full Numeric Catalog (contracts/irc-numeric-replies.md) has a `NumericReply` entry with the documented code and name, and nickname-grammar validation cases (valid/invalid leading character, valid/invalid body characters, exactly-9-characters boundary, 10-character rejection) in `jircd-protocol/src/test/java/net/jircd/protocol/MessageSerializerTest.java`

### Session & channel aggregates (`jircd-core/session`)

- [ ] T022 Define the `ClientSession` aggregate root (data-model.md fields: `connectionId`, I/O `channel`, `outboundQueue`, `registrationState`, `nickname`, `negotiatedCapabilities`, `channelMemberships`, `rateLimitBucket`, `ident`, `realHostname`, `administratorPrivilege`) in `jircd-core/src/main/java/net/jircd/core/session/ClientSession.java`
- [ ] T023 Implement the per-session bounded outbound queue of `PendingDelivery` (data-model.md) and its dedicated writer virtual thread — the only path that writes to that session's socket. At drain time, the writer thread converts each `PendingDelivery` into the actual wire line by applying *this session's own* `negotiatedCapabilities`, live-checked against current `CapabilityExtension` state, never cached (research.md "Message fan-out concurrency model", data-model.md "Capability" validation rules); queue-overflow transitions the session to `CLOSING` (data-model.md `ClientSession` validation rules) in `jircd-core/src/main/java/net/jircd/core/session/SessionWriter.java`
- [ ] T024 Implement the connection lifecycle state machine (`CONNECTING` → `REGISTERED` → `CLOSING`, FR-001) in `jircd-core/src/main/java/net/jircd/core/session/ConnectionLifecycle.java`
- [ ] T025 Implement the nickname registry with atomic claim/uniqueness — exactly one winner on a concurrent claim, no window where two sessions hold the same name (FR-002); scope the uniqueness check behind a single interface so it can later be widened from server-local to network-wide without callers changing (FR-022) in `jircd-core/src/main/java/net/jircd/core/session/NicknameRegistry.java`
- [ ] T026 [P] Unit tests for `NicknameRegistry`, including a concurrent-claim race asserting exactly one winner (FR-002) in `jircd-core/src/test/java/net/jircd/core/session/NicknameRegistryTest.java`
- [ ] T027 Define the `Channel` aggregate root (`name`, `members`, `operators`, `sendRestriction`, `topic`, first-join-gets-operator invariant enforced at creation, FR-013) in `jircd-core/src/main/java/net/jircd/core/session/Channel.java`
- [ ] T028 Implement the channel registry with atomic name uniqueness and create-on-first-join (FR-003); scope membership lookups behind a single interface so they can later span servers without callers changing (FR-022) in `jircd-core/src/main/java/net/jircd/core/session/ChannelRegistry.java`
- [ ] T029 [P] Unit tests for `ChannelRegistry` covering name uniqueness and first-join-gets-operator assignment in `jircd-core/src/test/java/net/jircd/core/session/ChannelRegistryTest.java`

### Networking

- [ ] T030 Implement the plaintext connection listener (`ServerSocketChannel` accept loop → one virtual thread per connection, research.md "Networking model") in `jircd-core/src/main/java/net/jircd/core/session/PlaintextListener.java`
- [ ] T031 Implement the TLS connection listener (`SSLServerSocket`/`SSLSocket`, research.md "TLS approach" — blocking API matching the virtual-thread model, not `SSLEngine`) in `jircd-core/src/main/java/net/jircd/core/session/TlsListener.java`
- [ ] T032 Implement the per-connection command dispatch loop (blocking read → parse via `jircd-protocol` → route to a registered handler) in `jircd-core/src/main/java/net/jircd/core/session/ConnectionHandler.java` (depends on T014, T015, T022, T024, T030)
- [ ] T033 Add malformed/incomplete-message handling to `ConnectionHandler` — reject with `421`/`461` (contracts/irc-numeric-replies.md), never crash the connection or affect other clients (FR-015) in `jircd-core/src/main/java/net/jircd/core/session/ConnectionHandler.java` (extends T032)
- [ ] T034 [P] Integration test: two raw-socket clients connect to both the plaintext and TLS listeners as a connectivity smoke test in `jircd-integration-tests/src/test/java/net/jircd/integration/ConnectionSmokeTest.java`

### Connection keep-alive

- [ ] T035 Implement the per-connection `LivenessMonitor`, driven by an injectable clock/scheduler rather than real time: sends a server-initiated `PING` after a configured idle interval, and — if no `PONG` arrives within a configured timeout — sends `ERROR` and drives the session's `ConnectionLifecycle` (T024) to `CLOSING`, running the same FR-017 cleanup as any other connection loss (research.md "Connection keep-alive", FR-039, data-model.md `ClientSession.lastLivenessAt`) in `jircd-core/src/main/java/net/jircd/core/session/LivenessMonitor.java` (depends on T022, T024)
- [ ] T036 Implement the `PING`/`PONG` command handlers, wired directly into `ConnectionHandler`'s dispatch loop (T032) rather than the post-registration command table (T073) since a client MAY `PING` before registering (contracts/irc-protocol-commands.md "Connection Keep-Alive"): a client-initiated `PING` receives an immediate `PONG` reply on any connection; a client's `PONG` updates `ClientSession.lastLivenessAt`, resetting that connection's `LivenessMonitor` (T035) timer (FR-039) in `jircd-core/src/main/java/net/jircd/core/session/command/PingPongCommandHandler.java` (depends on T032, T035)
- [ ] T037 [P] Unit tests for `LivenessMonitor` using an injected fake clock: idle-beyond-interval triggers a `PING`; a `PONG` resets the timer; no `PONG` within the timeout transitions the session to `CLOSING` (FR-039, FR-017) in `jircd-core/src/test/java/net/jircd/core/session/LivenessMonitorTest.java`

### Rate limiting

- [ ] T038 [P] Implement the per-connection token bucket rate limiter (research.md "Rate limiting", FR-016) in `jircd-core/src/main/java/net/jircd/core/session/RateLimitBucket.java`
- [ ] T039 [P] Unit tests for token bucket refill/exhaustion behavior in `jircd-core/src/test/java/net/jircd/core/session/RateLimitBucketTest.java`

### Extension system (`jircd-core/extension`)

- [ ] T040 [P] Define the `Extension` base interface (`start(ServerContext)`, `stop()`, `id`, `state`) in `jircd-core/src/main/java/net/jircd/core/extension/Extension.java`
- [ ] T041 [P] Define the `CapabilityExtension` role interface (extends `Extension`; exposes exactly one `Capability`) in `jircd-core/src/main/java/net/jircd/core/extension/CapabilityExtension.java`
- [ ] T042 [P] Define the `ServerExtension` role interface (extends `Extension`; no `Capability`) in `jircd-core/src/main/java/net/jircd/core/extension/ServerExtension.java`
- [ ] T043 Implement a per-extension `URLClassLoader` with parent-first delegation for `net.jircd.protocol.*` and `net.jircd.core.*` types (research.md "Delegation model") in `jircd-core/src/main/java/net/jircd/core/extension/ExtensionClassLoader.java` (depends on T040-T042)
- [ ] T044 Implement `ExtensionRegistry`: `ServiceLoader`-based discovery, `ENABLED`/`DISABLED`/`FAILED` lifecycle, and quiesce-before-unload with a bounded timeout on in-flight calls before releasing a classloader (research.md "Quiesce before unload", FR-020) in `jircd-core/src/main/java/net/jircd/core/extension/ExtensionRegistry.java` (depends on T043)
- [ ] T045 Add extension-point ownership enforcement to `ExtensionRegistry` — at most one `ENABLED` extension per `extensionPoint`, rejecting a conflicting enable with a specific error naming both ids (research.md "Extension-point ownership", FR-012) in `jircd-core/src/main/java/net/jircd/core/extension/ExtensionRegistry.java` (extends T044)
- [ ] T046 [P] Unit tests for `ExtensionRegistry`: enable/disable/failed lifecycle, quiesce behavior, and extension-point conflict rejection in `jircd-core/src/test/java/net/jircd/core/extension/ExtensionRegistryTest.java`

### Capability negotiation mechanism (`jircd-core/capability`)

- [ ] T047 Implement the CAP negotiation state machine (`LS`/`REQ`/`ACK`/`NAK`/`END`, gating registration completion per FR-006) in `jircd-core/src/main/java/net/jircd/core/capability/CapabilityNegotiator.java` (depends on T018, T040-T044)
- [ ] T048 Wire `CapabilityNegotiator` so the offered/accepted capability list is sourced live from `CapabilityExtension` state via `ExtensionRegistry`, never cached (FR-007, FR-035, data-model.md "Capability" live-check) in `jircd-core/src/main/java/net/jircd/core/capability/CapabilityNegotiator.java` (extends T047)
- [ ] T049 [P] Unit tests for `CapabilityNegotiator` confirming negotiation succeeds and registration completes even with zero enabled capabilities (FR-035, FR-008) in `jircd-core/src/test/java/net/jircd/core/capability/CapabilityNegotiatorTest.java`

### Server Configuration and manual reload (`jircd-core/config`)

- [ ] T050 Define the `ServerConfiguration` aggregate root (`capabilityStates`, `serverExtensionStates`, `listeners`, `rateLimit`, `administratorCredentials` — data-model.md) in `jircd-core/src/main/java/net/jircd/core/config/ServerConfiguration.java`
- [ ] T051 Implement the YAML configuration loader and validation — unknown extension id, section/kind mismatch (a `ServerExtension` id under `capabilities` or vice versa), malformed `listeners`/`rateLimit`, and plain-text/unrecognized credential hash format all rejected with a specific, actionable error, leaving any previously-active configuration untouched on failure (contracts/server-configuration.md, FR-012, SC-008) in `jircd-core/src/main/java/net/jircd/core/config/ConfigurationLoader.java` (depends on T050)
- [ ] T052 Implement the core, manually-triggered reload operation — re-run `ConfigurationLoader`, and on success reconcile the result against `ExtensionRegistry`; on failure, leave the running configuration untouched and surface the same specific error `ConfigurationLoader` produced (research.md "Configuration reload mechanism", contracts/server-configuration.md "Live reload"). This is explicitly **not** automatic file-watching — it only runs when invoked by a trigger (T056, T102) in `jircd-core/src/main/java/net/jircd/core/config/ConfigurationReloader.java` (depends on T044, T051)
- [ ] T053 [P] Unit tests for `ConfigurationLoader` validation errors: unknown id, section/kind mismatch, malformed listener, malformed rate-limit, plain-text credential rejected in `jircd-core/src/test/java/net/jircd/core/config/ConfigurationLoaderTest.java`

### Security-event logging

- [ ] T054 [P] Implement a security-event logger wrapper for structured, reviewable log entries (failed authentication, rejected moderation actions, etc., FR-019) in `jircd-core/src/main/java/net/jircd/core/session/SecurityEventLog.java`

### Application entry point

- [ ] T055 Implement the `jircd-server` application entry point: load `ServerConfiguration`, start `ExtensionRegistry`, start the plaintext and TLS listeners, refuse to start with a specific error on invalid configuration (FR-012) in `jircd-server/src/main/java/net/jircd/server/JircdServerApplication.java` (depends on T030, T031, T044, T051)
- [ ] T056 Implement a `SIGHUP` signal handler in the `jircd-server` application entry point that invokes `ConfigurationReloader` — the manual, file-only reload trigger that keeps Story 4 usable without depending on Story 6's optional `admin` extension (research.md "Configuration reload mechanism") in `jircd-server/src/main/java/net/jircd/server/SighupReloadHandler.java` (depends on T052, T055)

**Checkpoint**: Foundation ready — user story implementation can now begin.

---

## Phase 3: User Story 1 - Connect and Chat in Real Time (Priority: P1) 🎯 MVP

**Goal**: A client connects, registers a unique nickname, joins a channel,
and exchanges real-time messages with other members; disconnects clean up
after themselves.

**Independent Test**: Connect two separate IRC clients, register distinct
nicknames, join the same channel, and confirm each client sees messages
sent by the other within a second (quickstart.md Story 1).

### Tests for User Story 1

> **Write these tests FIRST, ensure they FAIL before implementation.**

- [ ] T057 [P] [US1] Integration test: two clients register, join `#lobby`, exchange `PRIVMSG`, and the recipient sees the correct `nickname!ident@hostname` sender prefix (FR-004, FR-030) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1ChatTest.java`
- [ ] T058 [P] [US1] Integration test: two clients attempt to register the same nickname concurrently; exactly one succeeds, the other gets `433 ERR_NICKNAMEINUSE` (FR-002) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1NicknameRaceTest.java`
- [ ] T059 [P] [US1] Integration test: a client disconnects (both clean `QUIT` and abrupt close) while joined to a channel; remaining members receive a `PART`/`QUIT` notification (FR-017) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1DisconnectCleanupTest.java`
- [ ] T060 [P] [US1] Load-tagged (`@Tag("load")`) test: channel message delivery latency stays within SC-002's 1-second budget under moderate concurrent load in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1DeliveryLatencyLoadTest.java`
- [ ] T061 [P] [US1] Integration test: any client can view a channel's topic (`331`/`332`) without being a member; a channel operator sets a new topic and members see the change; a non-operator's attempt is rejected with `482 ERR_CHANOPRIVSNEEDED` (FR-040) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1TopicTest.java`
- [ ] T062 [P] [US1] Integration test: `NAMES <channel>` returns the current membership list of a channel the requester has not joined (FR-041) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1NamesTest.java`
- [ ] T063 [P] [US1] Integration test: `LIST` returns every currently active channel (FR-042) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1ListTest.java`

### Implementation for User Story 1

- [ ] T064 [US1] Implement the `NICK` command handler: validate against `Hostmask`'s nickname grammar (T017) before attempting a claim via `NicknameRegistry` — format (`432`) and uniqueness (`433`) are independent, sequential checks; `431` for a missing argument in `jircd-core/src/main/java/net/jircd/core/session/command/NickCommandHandler.java`
- [ ] T065 [US1] Implement the `USER` command handler completing registration (`001 RPL_WELCOME` burst); apply `Hostmask`'s username content rule (T017) to derive `ClientSession.ident`, truncating to 9 characters rather than rejecting (contracts/irc-protocol-commands.md "Connection Registration Grammar") in `jircd-core/src/main/java/net/jircd/core/session/command/UserCommandHandler.java` (depends on T064)
- [ ] T066 [US1] Implement the `JOIN` command handler (create-or-join via `ChannelRegistry`; `353`/`366` replies) in `jircd-core/src/main/java/net/jircd/core/session/command/JoinCommandHandler.java`
- [ ] T067 [US1] Implement the `PART` command handler in `jircd-core/src/main/java/net/jircd/core/session/command/PartCommandHandler.java`
- [ ] T068 [US1] Implement `PRIVMSG`/`NOTICE` command handlers: build the recipient set (channel members, or the direct-message target), construct one shared `PendingDelivery` (hostmask-prefixed sender, FR-030) per data-model.md — resolving `senderPresentedForm` by live-checking current `cloak` `ServerExtension` state at that moment, never cached, and never from any recipient's own state (data-model.md `PendingDelivery` validation rules) — and enqueue it onto every recipient's `SessionWriter` queue — this handler MUST NOT itself apply any capability-dependent (`message-tags`/`server-time`) formatting, that happens per-recipient in `SessionWriter` (T023) (FR-004, FR-005) in `jircd-core/src/main/java/net/jircd/core/session/command/MessageCommandHandler.java`
- [ ] T069 [US1] Implement the `QUIT` command handler and abrupt-disconnect cleanup (membership removal, `PART`/`QUIT` notification to affected channels, FR-017) in `jircd-core/src/main/java/net/jircd/core/session/command/QuitCommandHandler.java`
- [ ] T070 [US1] Implement the `TOPIC` command handler: with no trailing argument, returns the channel's current topic (`332`) or `331` if unset, open to any registered client regardless of membership; with a trailing argument, sets `Channel.topic` and echoes `TOPIC` to all members, rejecting a non-operator with `482 ERR_CHANOPRIVSNEEDED` (FR-040, data-model.md `Channel.topic`) in `jircd-core/src/main/java/net/jircd/core/session/command/TopicCommandHandler.java`
- [ ] T071 [US1] Implement the `NAMES` command handler, reusing the same `353`/`366` reply logic `JOIN` (T066) already produces, open to any registered client regardless of membership (FR-041) in `jircd-core/src/main/java/net/jircd/core/session/command/NamesCommandHandler.java`
- [ ] T072 [US1] Implement the `LIST` command handler, iterating `ChannelRegistry`'s currently active channels (FR-042) in `jircd-core/src/main/java/net/jircd/core/session/command/ListCommandHandler.java`
- [ ] T073 [US1] Register the `NICK`/`USER`/`JOIN`/`PART`/`PRIVMSG`/`NOTICE`/`QUIT`/`TOPIC`/`NAMES`/`LIST` handlers in `ConnectionHandler`'s command dispatch table in `jircd-core/src/main/java/net/jircd/core/session/ConnectionHandler.java` (depends on T064-T072)

**Checkpoint**: User Story 1 is fully functional and independently
testable — the MVP.

---

## Phase 4: User Story 2 - Discover and Use Enhanced Capabilities (Priority: P2)

**Goal**: Capability-aware clients negotiate `message-tags`, `server-time`,
and `echo-message`; clients that skip negotiation still work normally.

**Independent Test**: Connect a capability-aware test client, negotiate a
subset of capabilities, and confirm the server's messages include the
negotiated enhancements while a plain client sees standard, unaugmented
messages (quickstart.md Story 2).

### Tests for User Story 2

> **Write these tests FIRST, ensure they FAIL before implementation.**

- [ ] T074 [P] [US2] Integration test: `CAP LS 302` returns exactly `message-tags`, `server-time`, `echo-message` — no `sasl` or other capability (FR-025) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story2CapabilityListTest.java`
- [ ] T075 [P] [US2] Integration test: a connection negotiating `server-time`+`echo-message` receives its own tagged `PRIVMSG` echoed back, while a second, non-negotiating connection in the same channel receives the message untagged (FR-007, FR-008) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story2NegotiatedDeliveryTest.java`

### Implementation for User Story 2

- [ ] T076 [P] [US2] Implement the `message-tags` `CapabilityExtension`, exposing a tag-decoration hook that `SessionWriter` (T023) calls per-recipient at drain time — it does not itself touch any `PendingDelivery` or queue, it only produces the tag-prefix content when asked, for whichever session currently has it negotiated and enabled in `jircd-capabilities/message-tags/src/main/java/net/jircd/capabilities/messagetags/MessageTagsExtension.java`
- [ ] T077 [P] [US2] Implement the `server-time` `CapabilityExtension`, exposing a hook that produces the `time` tag from `PendingDelivery.sentAt` (data-model.md — the sender's send-time instant, not each recipient's drain time), called the same per-recipient way as `message-tags` (T076) in `jircd-capabilities/server-time/src/main/java/net/jircd/capabilities/servertime/ServerTimeExtension.java`
- [ ] T078 [P] [US2] Implement the `echo-message` `CapabilityExtension`: unlike T076/T077, this affects *recipient-set construction*, not per-recipient formatting — it exposes a hook `MessageCommandHandler` (T068) calls once, when building the recipient list, to decide whether the sender's own session is included in the `PendingDelivery` fan-out in `jircd-capabilities/echo-message/src/main/java/net/jircd/capabilities/echomessage/EchoMessageExtension.java`
- [ ] T079 [P] Register `message-tags`, `server-time`, and `echo-message` as `ServiceLoader` providers via `META-INF/services/net.jircd.core.extension.CapabilityExtension` in each of `jircd-capabilities/message-tags/src/main/resources/`, `jircd-capabilities/server-time/src/main/resources/`, and `jircd-capabilities/echo-message/src/main/resources/`
- [ ] T080 [US2] Wire `SessionWriter`'s per-recipient drain-time formatting (T023) to actually call the `message-tags`/`server-time` decoration hooks (T076/T077) for each session's currently negotiated-and-enabled capabilities — `CapabilityNegotiator` itself only tracks what a session negotiated (T047/T048); it does not do formatting (depends on T023, T048, T076-T077) in `jircd-core/src/main/java/net/jircd/core/session/SessionWriter.java`

**Checkpoint**: User Stories 1 and 2 both work independently.

---

## Phase 5: User Story 4 - Tailor the Server with Optional Extensions (Priority: P4)

**Goal**: An administrator enables/disables optional extensions via the
configuration file and a manually-triggered reload, with changes taking
effect for connected and new clients without a server restart.

**Independent Test**: Disable an optional extension via configuration and
a `SIGHUP`-triggered reload while the server keeps running, confirm
connected clients can no longer use it, then re-enable it the same way and
confirm functionality returns — all without a restart (quickstart.md
Story 4).

### Tests for User Story 4

> **Write these tests FIRST, ensure they FAIL before implementation.**

- [ ] T081 [P] [US4] Integration test: editing `capabilities.message-tags` to `disabled` and sending the process `SIGHUP` removes it from `CAP LS` and stops tagging already-connected sessions' messages within SC-005's 1-minute budget, with no server restart (SC-005, FR-011) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story4ConfigToggleTest.java`
- [ ] T082 [P] [US4] Integration test: an invalid configuration (`capabilities.nonexistent`, `capabilities.moderation`, `capabilities.cloak` — a section/kind mismatch) at both server startup and via a `SIGHUP`-triggered reload each produce a specific error naming the offending key, and a `SIGHUP` reload rejection leaves the server running on its previous, valid configuration (FR-012, SC-008) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story4InvalidConfigTest.java`

### Implementation for User Story 4

- [ ] T083 [US4] Wire `JircdServerApplication` startup to refuse to start and report the specific validation error when `ConfigurationLoader` rejects the configuration (FR-012) in `jircd-server/src/main/java/net/jircd/server/JircdServerApplication.java`

**Checkpoint**: User Stories 1, 2, and 4 all work independently.

---

## Phase 6: User Story 5 - Moderate a Channel (Priority: P5)

**Goal**: Channel operators can remove disruptive members and restrict who
may speak; non-operators are rejected with a clear error.

**Independent Test**: As a channel operator, remove a member and confirm
they're no longer a member and all remaining members are notified
(quickstart.md Story 5).

### Tests for User Story 5

> **Write these tests FIRST, ensure they FAIL before implementation.**

- [ ] T084 [P] [US5] Integration test: a channel operator `KICK`s a member; the member is removed and both the target and remaining members are notified (FR-013, FR-014) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5KickTest.java`
- [ ] T085 [P] [US5] Integration test: a non-operator's `KICK` attempt is rejected with `482 ERR_CHANOPRIVSNEEDED`, and the target is not removed (FR-014) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5KickPermissionTest.java`
- [ ] T086 [P] [US5] Integration test: an operator sets moderated mode (`MODE +m`); a non-permitted member's `PRIVMSG` is not delivered and they receive a clear explanation (FR-013) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5ModeRestrictionTest.java`
- [ ] T087 [P] [US5] Integration test: `KICK` is available immediately after server startup with no configuration step required (FR-036) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5AlwaysAvailableTest.java`

### Implementation for User Story 5

- [ ] T088 [US5] Implement the `KICK` command handler (operator-only, removes target, notifies the channel, `482` on unauthorized, logs rejected attempts via `SecurityEventLog`, FR-013/FR-014/FR-019) in `jircd-core/src/main/java/net/jircd/core/session/command/KickCommandHandler.java`
- [ ] T089 [US5] Implement the `MODE` command handler for `Channel.sendRestriction` (`+m` moderated / members-only variants; `472` on an unknown flag, `482` on unauthorized, logs rejected attempts via `SecurityEventLog`) in `jircd-core/src/main/java/net/jircd/core/session/command/ModeCommandHandler.java`
- [ ] T090 [US5] Enforce `Channel.sendRestriction` in the `PRIVMSG`/`NOTICE` path, rejecting non-permitted senders with a clear error (depends on T068) in `jircd-core/src/main/java/net/jircd/core/session/command/MessageCommandHandler.java`
- [ ] T091 [US5] Register the `KICK`/`MODE` handlers in `ConnectionHandler`'s command dispatch table (depends on T073, T088, T089) in `jircd-core/src/main/java/net/jircd/core/session/ConnectionHandler.java`

**Checkpoint**: User Stories 1, 2, 4, and 5 all work independently.

---

## Phase 7: User Story 6 - Administer the Server via IRC Commands (Priority: P4)

**Goal**: An administrator grants themselves privilege in-band and issues
administrative commands (extension toggle, hostname lookup, full config
reload) over the IRC protocol itself, with the same effect as the
configuration-file/`SIGHUP` path.

**Independent Test**: Issue the privilege-granting command with valid
credentials, confirm privilege is granted, then toggle an extension
in-band and confirm the effect matches the configuration-file path
(quickstart.md Story 6).

### Tests for User Story 6

> **Write these tests FIRST, ensure they FAIL before implementation.**

- [ ] T092 [P] [US6] Integration test: a non-privileged session's `EXTENSION` command is rejected with `481 ERR_NOPRIVILEGES` (FR-033) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story6PrivilegeGateTest.java`
- [ ] T093 [P] [US6] Integration test: `OPER` with valid credentials returns `381 RPL_YOUREOPER`; with invalid credentials returns `464 ERR_PASSWDMISMATCH` and the failure is logged as a security event (FR-019, FR-034) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story6OperTest.java`
- [ ] T094 [P] [US6] Integration test: a privileged session's `EXTENSION DISABLE message-tags` has the same observable effect as Story 4's config-file path, with no config file edit involved, within SC-009's budget (path equivalence, contracts/server-configuration.md) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story6ExtensionToggleTest.java`
- [ ] T095 [P] [US6] Integration test: `EXTENSION DISABLE moderation` and `EXTENSION DISABLE capability-negotiation` are both rejected as unknown/non-toggleable, not silently accepted (FR-035, FR-036) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story6NonToggleableRejectionTest.java`
- [ ] T096 [P] [US6] Integration test: `WHOHOST` returns a target's real hostname to a privileged admin even while the `cloak` extension obscures it for other clients (FR-031, FR-032) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story6WhohostTest.java`
- [ ] T097 [P] [US6] Integration test: `EXTENSION DISABLE admin`, issued by a privileged session, succeeds; that session's subsequent admin commands are then rejected with `481`, while the configuration-file/`SIGHUP` path still works (contracts/irc-protocol-commands.md "Self-lockout") in `jircd-integration-tests/src/test/java/net/jircd/integration/Story6SelfLockoutTest.java`
- [ ] T098 [P] [US6] Integration test: a privileged session edits nothing but sends `REHASH` after the config file's `rateLimit.bucketSize` was changed on disk; the new value takes effect and `382 RPL_REHASHING` is returned — the in-band equivalent of Story 4's `SIGHUP` path (research.md "Configuration reload mechanism"); a `REHASH` against an invalid file returns the specific validation error directly to the session and leaves the running configuration untouched in `jircd-integration-tests/src/test/java/net/jircd/integration/Story6RehashTest.java`

### Implementation for User Story 6

- [ ] T099 [US6] Implement administrator-credential verification against `ServerConfiguration.administratorCredentials` using the password-hashing library (research.md "Administrator credential storage", FR-034) in `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/AdminCredentialVerifier.java`
- [ ] T100 [US6] Implement the `OPER` command handler (grants `ClientSession.administratorPrivilege` on success; `381`/`464`; logs failures via `SecurityEventLog`) in `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/OperCommandHandler.java` (depends on T099)
- [ ] T101 [US6] Implement the `EXTENSION` command handler (`ENABLE`/`DISABLE` via `ExtensionRegistry`; `481` if unprivileged; specific error for unknown or non-toggleable ids) in `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/ExtensionCommandHandler.java`
- [ ] T102 [US6] Implement the `REHASH` command handler — admin-privilege gated, invokes `ConfigurationReloader`; `382 RPL_REHASHING` on success, the reloader's specific validation error on failure, `481` if unprivileged (research.md "Configuration reload mechanism", FR-012) in `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/RehashCommandHandler.java` (depends on T052)
- [ ] T103 [US6] Implement the `WHOHOST` command handler (reads `ClientSession.realHostname` directly, bypassing any cloak extension) in `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/WhohostCommandHandler.java`
- [ ] T104 [US6] Implement the `admin` `ServerExtension` wiring, registering the `OPER`/`EXTENSION`/`REHASH`/`WHOHOST` handlers with `jircd-core`'s command dispatch when enabled (depends on T100-T103) in `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/AdminExtension.java`
- [ ] T105 [US6] Implement the `cloak` `ServerExtension`, claiming the `hostname-display` extension point (research.md "Cloak extension boundary", FR-031) in `jircd-server-extensions/cloak/src/main/java/net/jircd/serverextensions/cloak/CloakExtension.java`
- [ ] T106 [P] [US6] Register `admin` and `cloak` as `ServiceLoader` providers via `META-INF/services/net.jircd.core.extension.ServerExtension` in `jircd-server-extensions/admin/src/main/resources/` and `jircd-server-extensions/cloak/src/main/resources/`

**Checkpoint**: All five mandatory user stories (1, 2, 4, 5, 6) work
independently.

---

## Phase 8: User Story 7 - Look Up Information About a User (Priority: P2)

**Goal**: A registered client can look up nickname/ident/hostname/real-name
information about themselves or another connected user, with the returned
hostname/IP following FR-038's three-tier visibility rule.

**Independent Test**: Look up your own information and confirm it's
returned correctly; as a different, non-administrator client, look up
that same user's information and confirm you receive their presented
(not real) hostname (quickstart.md Story 7).

### Tests for User Story 7

> **Write these tests FIRST, ensure they FAIL before implementation.**

- [ ] T107 [P] [US7] Integration test: a client performs a self-lookup (no target) and receives its own real hostname/IP, even while the `cloak` extension is currently obscuring it from other clients (FR-038 case 1) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story7SelfLookupTest.java`
- [ ] T108 [P] [US7] Integration test: a privileged session looks up a *different* connected client and receives that client's real hostname/IP, consistent with `WHOHOST` (FR-038 case 2) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story7AdminLookupTest.java`
- [ ] T109 [P] [US7] Integration test: a non-privileged session looks up a *different* connected client and receives only that client's presented hostname (the same value its message hostmask shows) — never the real value, whether or not `cloak` is enabled (FR-038 case 3, SC-010) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story7RegularLookupTest.java`
- [ ] T110 [P] [US7] Integration test: a lookup for a nickname that isn't connected returns `401 ERR_NOSUCHNICK`, no user data (FR-037) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story7NoSuchNickTest.java`

### Implementation for User Story 7

- [ ] T111 [US7] Implement the `WHOIS` command handler: resolve the target session (the sender's own if no argument given), apply FR-038's real-vs-presented resolution by reusing `UserIdentity.presentedForm`'s existing computation and `ClientSession.realHostname` (never a new, independent resolution — research.md "Cloak extension boundary"), reply `311 RPL_WHOISUSER` + `318 RPL_ENDOFWHOIS`, or `401 ERR_NOSUCHNICK` if the target isn't connected. Core protocol behavior (FR-037), never an optional extension in `jircd-core/src/main/java/net/jircd/core/session/command/WhoisCommandHandler.java`
- [ ] T112 [US7] Register the `WHOIS` handler in `ConnectionHandler`'s command dispatch table (depends on T073, T111) in `jircd-core/src/main/java/net/jircd/core/session/ConnectionHandler.java`

**Checkpoint**: All six mandatory user stories (1, 2, 4, 5, 6, 7) work
independently.

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Whole-system validation and documentation that spans
multiple stories.

- [ ] T113 [P] Load-tagged (`@Tag("load")`) test: the server sustains 1,000 simultaneous connections without channel message delivery delay exceeding SC-002's target (SC-003) in `jircd-integration-tests/src/test/java/net/jircd/integration/ConcurrentConnectionScaleLoadTest.java`
- [ ] T114 [P] Load-tagged (`@Tag("load")`) test: during a sustained flood from one connection, delivery latency for other well-behaved clients does not increase beyond SC-002's target (SC-006) in `jircd-integration-tests/src/test/java/net/jircd/integration/RateLimitLoadTest.java`
- [ ] T115 [P] Update `README.md`'s "Getting started" section with real build/run instructions now that the project builds (`./gradlew build`, `./gradlew :jircd-server:run`), replacing the pre-implementation placeholder
- [ ] T116 Run the full `specs/001-ircv3-server/quickstart.md` validation pass manually against a running `./gradlew :jircd-server:run` instance, covering Stories 1, 2, 4, 5, 6, and 7 end-to-end (constitution UX Consistency principle's required manual usage-scenario check)
- [ ] T117 [P] Code cleanup pass: remove any dead code/TODOs introduced during implementation and confirm `./gradlew build` runs Spotless and SpotBugs clean across all subprojects (constitution Code Quality principle)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately.
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories.
- **User Stories (Phase 3-8)**: All depend on Foundational completion.
  - Can proceed in parallel (if staffed) or sequentially in priority order (P1 → P2 → P2 → P4 → P4 → P5, per spec.md — Stories 2 and 7 share priority P2, Stories 4 and 6 share priority P4). Story 7 is phased last here purely for minimal task-list disruption when it was added, not because of a priority or code dependency — see "User Story Dependencies" below.
- **Polish (Phase 9)**: Depends on all six user stories being complete.

### User Story Dependencies

- **User Story 1 (P1)**: No dependencies on other stories. The MVP.
- **User Story 2 (P2)**: No dependencies on other stories; extends the
  capability-negotiation mechanism built in Foundational.
- **User Story 4 (P4)**: No dependencies on other stories, but its
  Independent Test is most meaningful once Story 2 exists (something to
  toggle) — implement after Story 2 for a clearer demo, though the code
  itself doesn't require it. Its reload trigger (`SIGHUP`) is entirely
  Foundational (T052, T056), so Story 4's own phase is small.
- **User Story 5 (P5)**: No dependencies on other stories; builds on the
  `Channel` aggregate from Foundational.
- **User Story 6 (P4)**: No dependencies on other stories, but — like
  Story 4 — is most demonstrable once Story 2 exists. Its `REHASH`
  command (T102) depends only on Foundational's `ConfigurationReloader`
  (T052), not on Story 4.
- **User Story 7 (P2)**: No dependencies on other stories to compile or
  run — the self-lookup case (T107) needs nothing beyond Foundational.
  Its administrator-lookup test (T108) is most meaningful once Story 6's
  `OPER` exists, and its cloak-interaction assertions (T107/T109) are most
  meaningful once Story 6's `cloak` extension exists, but the `WHOIS`
  handler itself (T111) only depends on `ClientSession.realHostname` and
  `UserIdentity.presentedForm`, both Foundational — it does not call into
  `WHOHOST` or any Story 6 code.

### Within Each User Story

- Tests MUST be written and FAIL before implementation.
- Command handlers before dispatch-table registration.
- Story complete (checkpoint) before moving to the next priority, if
  working sequentially.

### Parallel Opportunities

- All Setup tasks marked `[P]` can run in parallel.
- Within Foundational, the protocol-layer tasks (T015-T021), the
  `Extension`/`CapabilityExtension`/`ServerExtension` interface
  definitions (T040-T042), and the two independent test-writing tasks in
  each subsection marked `[P]` can run in parallel.
- Once Foundational completes, Stories 1, 2, 4, 5, and 6 can be worked in
  parallel by different developers/agents — none of them modify the same
  files as another story's implementation tasks (verify no overlap before
  parallelizing in practice, since some later stories extend files earlier
  stories created, e.g., Story 5 extends `MessageCommandHandler` from
  Story 1).
- All tests within a story marked `[P]` can run in parallel.
- All `CapabilityExtension`/`ServerExtension` implementations within a
  story marked `[P]` can run in parallel (separate Gradle subprojects).

---

## Parallel Example: User Story 1

```bash
# Launch all tests for User Story 1 together:
Task: "Integration test: two clients register, join #lobby, exchange PRIVMSG in jircd-integration-tests/.../Story1ChatTest.java"
Task: "Integration test: concurrent NICK registration race in jircd-integration-tests/.../Story1NicknameRaceTest.java"
Task: "Integration test: disconnect cleanup in jircd-integration-tests/.../Story1DisconnectCleanupTest.java"
Task: "Load test: delivery latency within SC-002 in jircd-integration-tests/.../Story1DeliveryLatencyLoadTest.java"
Task: "Integration test: topic view/set/permission in jircd-integration-tests/.../Story1TopicTest.java"
Task: "Integration test: NAMES on an unjoined channel in jircd-integration-tests/.../Story1NamesTest.java"
Task: "Integration test: LIST returns active channels in jircd-integration-tests/.../Story1ListTest.java"
```

## Parallel Example: User Story 2 (capability extensions)

```bash
# Launch all three capability extensions together (separate Gradle subprojects):
Task: "Implement message-tags CapabilityExtension in jircd-capabilities/message-tags/..."
Task: "Implement server-time CapabilityExtension in jircd-capabilities/server-time/..."
Task: "Implement echo-message CapabilityExtension in jircd-capabilities/echo-message/..."
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup.
2. Complete Phase 2: Foundational (CRITICAL — blocks all stories).
3. Complete Phase 3: User Story 1.
4. **STOP and VALIDATE**: run Story 1's Independent Test and quickstart.md's
   Story 1 section against a running server.
5. Demo if ready — a working, if minimal, IRC server.

### Incremental Delivery

1. Setup + Foundational → foundation ready.
2. Add Story 1 → validate independently → MVP.
3. Add Story 2 → validate independently (capability negotiation now demoable).
4. Add Story 4 → validate independently (config-file extension toggling via `SIGHUP`).
5. Add Story 5 → validate independently (moderation).
6. Add Story 6 → validate independently (in-band administration, including `REHASH`).
7. Add Story 7 → validate independently (user lookup / `WHOIS`).
8. Polish: scale/load validation, full quickstart pass, docs.

Each story adds value without breaking previously delivered stories.

### Parallel Team Strategy

With multiple developers/agents, once Foundational is done:

- Developer/Agent A: User Story 1
- Developer/Agent B: User Story 2, then User Story 7 (both are P2 and
  small; Story 7's `WHOIS` handler is a natural follow-on once the
  developer is already deep in capability/identity code)
- Developer/Agent C: User Story 5
- Developer/Agent D: User Stories 4 and 6 (both build on the reload
  mechanism from Foundational, so pairing them reduces cross-agent
  coordination)

Watch for the one known cross-story file touch: Story 5's T090 extends
`MessageCommandHandler.java`, which Story 1's T068 creates — sequence
those two specifically, even if the stories otherwise run in parallel.

---

## Notes

- `[P]` tasks = different files, no dependencies on incomplete tasks.
- `[Story]` label maps each task to its user story for traceability back
  to spec.md.
- Story 3 (authentication) and everything that depends on it (FR-009,
  FR-010, FR-023, FR-024, FR-026, FR-027) are deferred per spec.md's
  Clarifications — no tasks are generated for them. Likewise FR-028/
  FR-029 (federation consistency) — federation itself is out of scope.
- Configuration reload is manually triggered only (`SIGHUP` or `REHASH`,
  T052/T056/T102) — there is deliberately no automatic file-watching task;
  see research.md "Configuration reload mechanism" for why.
- Verify each story's tests fail before implementing that story.
- Commit after each task or logical group.
- Stop at any checkpoint to validate a story independently before
  continuing.
- The two `[P]`-marked capability/server-extension `ServiceLoader`
  registration tasks (T079, T106) are each one task covering multiple
  small resource files (one per extension in that group) — split further
  only if working across them in true parallel by different people.
