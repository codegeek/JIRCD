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
- [ ] T012 [P] Set `version` in root `gradle.properties` as the project's single source-of-truth version string, and wire a `generateVersionResource` task in `jircd-server/build.gradle.kts` into `processResources` that writes `net/jircd/server/version.properties` (one `version=<project.version>` line) onto the module's classpath, so it's present identically whether classes are loaded from exploded build output or a packaged JAR (research.md "Server identity")
- [ ] T013 Create `jircd-integration-tests/build.gradle.kts`: depends on `jircd-server` and `jircd-protocol`; JUnit 5 + AssertJ
- [ ] T014 [P] Configure Logback in `jircd-server/src/main/resources/logback.xml` with structured, leveled output and file rotation (research.md "Logging", FR-019)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure every user story depends on — wire
protocol, connection/session lifecycle, channel aggregate, networking,
the Extension system, capability-negotiation mechanism, and Server
Configuration loading plus its manual reload mechanism.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Wire protocol (`jircd-protocol`)

- [ ] T015 Define the core message model, including a complete `Command` catalog covering every RFC 1459/2812 command plus `CAP`/`AUTHENTICATE`/`TAGMSG` (research.md "Wire-protocol command & numeric completeness", contracts/irc-protocol-commands.md "Full Command Catalog") — not only the commands `jircd-core` implements this release — plus the message tag map, in `jircd-protocol/src/main/java/net/jircd/protocol/Message.java`
- [ ] T016 [P] Implement the line-based message parser (raw line → `Message`) in `jircd-protocol/src/main/java/net/jircd/protocol/MessageParser.java`
- [ ] T017 [P] Implement the message serializer (`Message` → raw line, including `message-tags`-style tag prefix formatting) in `jircd-protocol/src/main/java/net/jircd/protocol/MessageSerializer.java`
- [ ] T018 [P] Implement hostmask formatting (`nickname!ident@hostname`, FR-030) and the nickname/username content grammar (RFC 2812 §2.3.1 nickname BNF; username content rule) both defined in contracts/irc-protocol-commands.md "Connection Registration Grammar" — nickname/username format validation is a wire-protocol rule, not a `jircd-core` handler concern, so it lives here alongside `Hostmask`, which already needs the same rule to compose a valid `nickname!ident@hostname` (research.md "Connection registration grammar"); the nickname grammar's length ceiling is a caller-supplied parameter (`Hostmask.isValidNickname(String nickname, int maxLength)`), not a hardcoded constant — `jircd-protocol` MUST NOT depend on `ServerConfiguration` (FR-056, research.md "Protocol/server boundary" — Consequence for FR-056) — in `jircd-protocol/src/main/java/net/jircd/protocol/Hostmask.java`. Also implement the channel name grammar (leading `#`, additional characters excluding space/comma/control characters, up to a caller-supplied maximum length, FR-048/FR-056, contracts/irc-protocol-commands.md "Channel Name Grammar") as a sibling wire-protocol validator, same package and same parameterized-length approach, in `jircd-protocol/src/main/java/net/jircd/protocol/ChannelName.java`. Also implement a shared UTF-8 validity check (FR-054) as another sibling utility, used by `ChannelName` (channel names) and by `jircd-core`'s `PRIVMSG`/`NOTICE`/`TOPIC`-set/`USER` handlers (message bodies, topics, realnames) — not by `Hostmask`'s nickname/username grammars, which are ASCII-only and unaffected — in `jircd-protocol/src/main/java/net/jircd/protocol/Utf8Validator.java`
- [ ] T019 [P] Implement the CAP negotiation grammar (`CAP LS`/`REQ`/`ACK`/`NAK`/`END` line formats) in `jircd-protocol/src/main/java/net/jircd/protocol/CapabilityNegotiationGrammar.java`
- [ ] T020 [P] Define the complete numeric reply catalog per `contracts/irc-numeric-replies.md`'s "Full Numeric Catalog" — every RFC 1459/2812 `RPL_*`/`ERR_*` numeric, plus `417 ERR_INPUTTOOLONG` (the one deliberate non-RFC addition, FR-049) — not only the "Used in This Release" subset — in `jircd-protocol/src/main/java/net/jircd/protocol/NumericReply.java`
- [ ] T021 [P] Unit tests for `MessageParser` covering valid and malformed lines (FR-015), including a completeness assertion that every command in the Full Command Catalog (contracts/irc-protocol-commands.md) parses to a recognized `Command` entry regardless of whether `jircd-core` implements it yet in `jircd-protocol/src/test/java/net/jircd/protocol/MessageParserTest.java`
- [ ] T022 [P] Unit tests for `MessageSerializer` and `Hostmask` formatting, including a completeness assertion that every numeric in the Full Numeric Catalog (contracts/irc-numeric-replies.md) has a `NumericReply` entry with the documented code and name, nickname-grammar validation cases (valid/invalid leading character, valid/invalid body characters, exactly-9-characters boundary, 10-character rejection, all against the default `maxLength=9`, plus a case with a smaller caller-supplied `maxLength` proving the ceiling is a real parameter, not a hardcoded constant, FR-056), `ChannelName` grammar cases (valid/missing leading `#`, valid/invalid body characters, exactly-50-characters boundary, 51-character rejection, all against the default `maxLength=50`, plus a differently-configured-`maxLength` case mirroring the nickname one, FR-048/FR-056), and `Utf8Validator` cases (valid multi-byte UTF-8 accepted, a truncated/invalid multi-byte sequence rejected, FR-054) in `jircd-protocol/src/test/java/net/jircd/protocol/MessageSerializerTest.java`

### Session & channel aggregates (`jircd-core/session`)

- [ ] T023 Define the `ClientSession` aggregate root (data-model.md fields: `connectionId`, I/O `channel`, `outboundQueue`, `registrationState`, `nickname`, `negotiatedCapabilities`, `channelMemberships`, `rateLimitBucket`, `ident`, `realHostname`, `administratorPrivilege`, `userModes` — `Set<UserMode>`, kept in lockstep with `administratorPrivilege`, never independently toggled, FR-044) in `jircd-core/src/main/java/net/jircd/core/session/ClientSession.java`
- [ ] T024 [P] Define the `UserMode` value type (`id`, `flag`, `definedBy` — `CORE` or an `Extension` id, data-model.md `UserMode`) and its core catalog, populated with exactly one entry (`id: operator, flag: o, definedBy: CORE`, FR-044) in `jircd-core/src/main/java/net/jircd/core/session/UserMode.java`
- [ ] T025 Implement the per-session bounded outbound queue of `OutboundMessage` (data-model.md) and its dedicated writer virtual thread — the only path that writes to that session's socket. At drain time, the writer thread converts each `OutboundMessage` into the actual wire line by applying *this session's own* `negotiatedCapabilities`, live-checked against current `CapabilityExtension` state, never cached (research.md "Message fan-out concurrency model", data-model.md "Capability" validation rules); queue-overflow transitions the session to `CLOSING` (data-model.md `ClientSession` validation rules) in `jircd-core/src/main/java/net/jircd/core/session/SessionWriter.java`
- [ ] T026 Implement the connection lifecycle state machine (`CONNECTING` → `REGISTERED` → `CLOSING`, FR-001) in `jircd-core/src/main/java/net/jircd/core/session/ConnectionLifecycle.java`
- [ ] T027 Implement the nickname registry with atomic claim/uniqueness — exactly one winner on a concurrent claim, no window where two sessions hold the same name (FR-002); claims compared using the rfc1459 casemapping (FR-052, research.md "IRC casemapping" — ASCII fold plus `[]\^` ↔ `{}|~`), storing the original casing a client registered with, never a folded/normalized form; scope the uniqueness check behind a single interface so it can later be widened from server-local to network-wide without callers changing (FR-022) in `jircd-core/src/main/java/net/jircd/core/session/NicknameRegistry.java`
- [ ] T028 [P] Unit tests for `NicknameRegistry`, including a concurrent-claim race asserting exactly one winner (FR-002), and casemapping cases: "Alice" then "alice" is rejected as in-use, "Alice" then "ALICE", and a lookup by any casing resolves to the originally-registered casing (FR-052) in `jircd-core/src/test/java/net/jircd/core/session/NicknameRegistryTest.java`
- [ ] T029 Define the `Channel` aggregate root (`name`, `members`, `operators`, `voiced` — subset of `members`, independent of `operators`, FR-045 — `activeModes` — a `Set<ChannelMode>`, not a closed enum, so a `ServerExtension` can later contribute additional `BOOLEAN`-kind flags without a data-model change, FR-043, research.md "Channel/user mode extensibility" — `topic`, first-join-gets-operator invariant enforced at creation, FR-013). Also define the `ChannelMode` value type itself (`id`, `flag`, `kind`, `gates` — which command(s) this flag restricts, e.g. `SEND`/`JOIN`, independent of `kind`, so a future extension-contributed flag can gate a command other than `PRIVMSG`/`NOTICE` without a `Channel`/command-handler shape change, FR-043 — `definedBy`, data-model.md) in the same package in `jircd-core/src/main/java/net/jircd/core/session/Channel.java`
- [ ] T030 Implement the channel registry with atomic name uniqueness and create-on-first-join (FR-003); names compared using the same rfc1459 casemapping as `NicknameRegistry` (T027, FR-052) — "#Foo" and "#foo" resolve to one channel, storing whichever casing created it; scope membership lookups behind a single interface so they can later span servers without callers changing (FR-022) in `jircd-core/src/main/java/net/jircd/core/session/ChannelRegistry.java`
- [ ] T031 [P] Unit tests for `ChannelRegistry` covering name uniqueness, casemapping cases mirroring `NicknameRegistryTest` (T028, FR-052), and first-join-gets-operator assignment in `jircd-core/src/test/java/net/jircd/core/session/ChannelRegistryTest.java`
- [ ] T032 Implement the shared disconnect-cleanup routine (channel membership removal, `QUIT` notification carrying a caller-supplied reason to every affected channel, FR-017) as the one path every disconnect trigger funnels through — a client-sent `QUIT` (with the client-supplied or default reason), a keep-alive timeout (with its own fixed reason), and an abrupt TCP-level loss (with its own fixed reason) all call this rather than duplicating the cleanup logic per trigger (research.md "Voluntary disconnect and quit reasons", FR-017, FR-060) in `jircd-core/src/main/java/net/jircd/core/session/DisconnectCleanup.java` (depends on T023, T026, T030)

### Networking

- [ ] T033 Implement the plaintext connection listener (`ServerSocketChannel` accept loop → one virtual thread per connection, research.md "Networking model") in `jircd-core/src/main/java/net/jircd/core/session/PlaintextListener.java`
- [ ] T034 Implement the TLS connection listener (`SSLServerSocket`/`SSLSocket`, research.md "TLS approach" — blocking API matching the virtual-thread model, not `SSLEngine`) in `jircd-core/src/main/java/net/jircd/core/session/TlsListener.java`
- [ ] T035 Implement the per-connection command dispatch loop (blocking read → parse via `jircd-protocol` → route to a registered handler, matched case-insensitively against the `Command` catalog — `join`/`Join`/`JOIN` all resolve to the same handler, FR-015); a read returning EOF or throwing an `IOException` (abrupt TCP-level connection loss) invokes `DisconnectCleanup` (T032) with a fixed reason (e.g. `"Connection reset by peer"`), the same shared cleanup path a client-sent `QUIT` or a keep-alive timeout uses (FR-017, research.md "Voluntary disconnect and quit reasons") in `jircd-core/src/main/java/net/jircd/core/session/ConnectionHandler.java` (depends on T015, T016, T023, T026, T032, T033)
- [ ] T036 Add malformed/incomplete-message handling to `ConnectionHandler` — reject with `421`/`461` (contracts/irc-numeric-replies.md), never crash the connection or affect other clients (FR-015). Include the line-length limit (FR-049): a line exceeding 512 bytes (command+params, CR-LF inclusive) plus up to 4096 additional bytes for a `message-tags` tag section is rejected with `417 ERR_INPUTTOOLONG` — a dedicated error, not `421`/`461` — never truncated or partially processed, in `jircd-core/src/main/java/net/jircd/core/session/ConnectionHandler.java` (extends T035)
- [ ] T037 [P] Integration test: two raw-socket clients connect to both the plaintext and TLS listeners as a connectivity smoke test in `jircd-integration-tests/src/test/java/net/jircd/integration/ConnectionSmokeTest.java`

### Connection keep-alive

- [ ] T038 Implement the per-connection `LivenessMonitor`, driven by an injectable clock/scheduler rather than real time: sends a server-initiated `PING` after a configured idle interval, and — if no `PONG` arrives within a configured timeout — sends `ERROR` and drives the session's `ConnectionLifecycle` (T026) to `CLOSING`, invoking `DisconnectCleanup` (T032) with a fixed timeout-specific reason (e.g. `"Ping timeout"`), distinct from `QUIT`'s own default (research.md "Voluntary disconnect and quit reasons", FR-039, FR-060, data-model.md `ClientSession.lastLivenessAt`) in `jircd-core/src/main/java/net/jircd/core/session/LivenessMonitor.java` (depends on T023, T026, T032)
- [ ] T039 Implement the `PING`/`PONG` command handlers, wired directly into `ConnectionHandler`'s dispatch loop (T035) rather than the post-registration command table (T082) since a client MAY `PING` before registering (contracts/irc-protocol-commands.md "Connection Keep-Alive"): a client-initiated `PING` receives an immediate `PONG` reply on any connection; a client's `PONG` updates `ClientSession.lastLivenessAt`, resetting that connection's `LivenessMonitor` (T038) timer (FR-039) in `jircd-core/src/main/java/net/jircd/core/session/command/PingPongCommandHandler.java` (depends on T035, T038)
- [ ] T040 [P] Unit tests for `LivenessMonitor` using an injected fake clock: idle-beyond-interval triggers a `PING`; a `PONG` resets the timer; no `PONG` within the timeout transitions the session to `CLOSING` (FR-039, FR-017) in `jircd-core/src/test/java/net/jircd/core/session/LivenessMonitorTest.java`

### Rate limiting

- [ ] T041 [P] Implement the per-connection token bucket rate limiter (research.md "Rate limiting", FR-016) in `jircd-core/src/main/java/net/jircd/core/session/RateLimitBucket.java`
- [ ] T042 [P] Unit tests for token bucket refill/exhaustion behavior in `jircd-core/src/test/java/net/jircd/core/session/RateLimitBucketTest.java`

### Extension system (`jircd-core/extension`)

- [ ] T043 [P] Define the `Extension` base interface (`start(ServerContext)`, `stop()`, `id`, `state`) in `jircd-core/src/main/java/net/jircd/core/extension/Extension.java`
- [ ] T044 [P] Define the `CapabilityExtension` role interface (extends `Extension`; exposes exactly one `Capability`) in `jircd-core/src/main/java/net/jircd/core/extension/CapabilityExtension.java`
- [ ] T045 [P] Define the `ServerExtension` role interface (extends `Extension`; no `Capability`; MAY optionally expose `contributedChannelModes` and `contributedUserModes`, both empty for every extension in this release, data-model.md `Extension`) in `jircd-core/src/main/java/net/jircd/core/extension/ServerExtension.java`
- [ ] T046 Implement a per-extension `URLClassLoader` with parent-first delegation for `net.jircd.protocol.*` and `net.jircd.core.*` types (research.md "Delegation model") in `jircd-core/src/main/java/net/jircd/core/extension/ExtensionClassLoader.java` (depends on T043-T045)
- [ ] T047 Implement `ExtensionRegistry`: `ServiceLoader`-based discovery, `ENABLED`/`DISABLED`/`FAILED` lifecycle, and quiesce-before-unload with a bounded timeout on in-flight calls before releasing a classloader (research.md "Quiesce before unload", FR-020) in `jircd-core/src/main/java/net/jircd/core/extension/ExtensionRegistry.java` (depends on T046)
- [ ] T048 Add extension-point ownership enforcement to `ExtensionRegistry` — at most one `ENABLED` extension per `extensionPoint`, rejecting a conflicting enable with a specific error naming both ids (research.md "Extension-point ownership", FR-012) in `jircd-core/src/main/java/net/jircd/core/extension/ExtensionRegistry.java` (extends T047)
- [ ] T049 Implement `SupportedFeatures` as a server-scoped value, not something computed per registration (FR-055, data-model.md `SupportedFeatures`): fixed tokens (`CASEMAPPING`, `CHANTYPES`, `MODES`, `UTF8ONLY`) are constants computed once; `CHANMODES`/`PREFIX` are (re)computed from the current `ChannelMode` catalog whenever `ExtensionRegistry`'s `ENABLED`/`DISABLED` state changes for a mode-contributing extension (a no-op recomputation trigger this release, since none exists yet); expose an `updateConfiguredLengths(int nicknameMaxLength, int channelNameMaxLength, int topicMaxLength)` update point that recomputes `NICKLEN`/`CHANNELLEN`/`TOPICLEN` — `ExtensionRegistry` itself has no `ServerConfiguration` dependency; this is invoked by the configuration load/reload path (T055, T056) whenever `ServerConfiguration` is (re)loaded (FR-056, research.md "Configurable protocol length limits"), never recomputed on a per-`USER`-command basis in `jircd-core/src/main/java/net/jircd/core/extension/ExtensionRegistry.java` (extends T047, T048)
- [ ] T050 [P] Unit tests for `ExtensionRegistry`: enable/disable/failed lifecycle, quiesce behavior, and extension-point conflict rejection in `jircd-core/src/test/java/net/jircd/core/extension/ExtensionRegistryTest.java`

### Capability negotiation mechanism (`jircd-core/capability`)

- [ ] T051 Implement the CAP negotiation state machine (`LS`/`REQ`/`ACK`/`NAK`/`END`, gating registration completion per FR-006) in `jircd-core/src/main/java/net/jircd/core/capability/CapabilityNegotiator.java` (depends on T019, T043-T047)
- [ ] T052 Wire `CapabilityNegotiator` so the offered/accepted capability list is sourced live from `CapabilityExtension` state via `ExtensionRegistry`, never cached (FR-007, FR-035, data-model.md "Capability" live-check) in `jircd-core/src/main/java/net/jircd/core/capability/CapabilityNegotiator.java` (extends T051)
- [ ] T053 [P] Unit tests for `CapabilityNegotiator` confirming negotiation succeeds and registration completes even with zero enabled capabilities (FR-035, FR-008) in `jircd-core/src/test/java/net/jircd/core/capability/CapabilityNegotiatorTest.java`

### Server Configuration and manual reload (`jircd-core/config`)

- [ ] T054 Define the `ServerConfiguration` aggregate root (`capabilityStates`, `serverExtensionStates`, `listeners`, `rateLimit`, `administratorCredentials`, `serverName` — optional, FR-050 — `serverVersion` — not administrator-configurable, read from the `net/jircd/server/version.properties` classpath resource (T012, research.md "Server identity"), FR-051 — `nicknameMaxLength`/`channelNameMaxLength`/`topicMaxLength` — optional, positive integer, at most 400, defaulting to `9`/`50`/`390` respectively, FR-056 — data-model.md) in `jircd-core/src/main/java/net/jircd/core/config/ServerConfiguration.java`
- [ ] T055 Implement the YAML configuration loader and validation — unknown extension id, section/kind mismatch (a `ServerExtension` id under `capabilities` or vice versa), malformed `listeners`/`rateLimit`, a `serverName` containing no `.` (FR-050), a `nicknameMaxLength`/`channelNameMaxLength`/`topicMaxLength` that isn't a positive integer or exceeds 400 (FR-056), and plain-text/unrecognized credential hash format all rejected with a specific, actionable error, leaving any previously-active configuration untouched on failure (contracts/server-configuration.md, FR-012, SC-008); on success, invoke `ExtensionRegistry.updateConfiguredLengths` (T049) with the resolved lengths in `jircd-core/src/main/java/net/jircd/core/config/ConfigurationLoader.java` (depends on T049, T054)
- [ ] T056 Implement the core, manually-triggered reload operation — re-run `ConfigurationLoader`, and on success reconcile the result against `ExtensionRegistry` (including the updated `NICKLEN`/`CHANNELLEN`/`TOPICLEN` values, FR-056); on failure, leave the running configuration untouched and surface the same specific error `ConfigurationLoader` produced (research.md "Configuration reload mechanism", contracts/server-configuration.md "Live reload"). This is explicitly **not** automatic file-watching — it only runs when invoked by a trigger (T060, T123) in `jircd-core/src/main/java/net/jircd/core/config/ConfigurationReloader.java` (depends on T047, T055)
- [ ] T057 [P] Unit tests for `ConfigurationLoader` validation errors: unknown id, section/kind mismatch, malformed listener, malformed rate-limit, dot-free `serverName`, a non-positive or over-400 `nicknameMaxLength`/`channelNameMaxLength`/`topicMaxLength` (FR-056), plain-text credential rejected in `jircd-core/src/test/java/net/jircd/core/config/ConfigurationLoaderTest.java`

### Security-event logging

- [ ] T058 [P] Implement a security-event logger wrapper for structured, reviewable log entries (failed authentication, rejected moderation actions, etc., FR-019) in `jircd-core/src/main/java/net/jircd/core/session/SecurityEventLog.java`

### Application entry point

- [ ] T059 Implement the `jircd-server` application entry point: load `ServerConfiguration`, start `ExtensionRegistry`, start the plaintext and TLS listeners, refuse to start with a specific error on invalid configuration (FR-012). Resolve `serverName` to the deployment host's network hostname if not configured, appending a fixed synthetic suffix (`.local`) if that hostname itself contains no `.` — the fallback path MUST satisfy FR-050's dot requirement, not only explicit administrator input — and `serverVersion` by reading the `net/jircd/server/version.properties` classpath resource (T012, FR-051, research.md "Server identity"), failing fast with a specific startup error if that resource is missing or unparsable (FR-012) in `jircd-server/src/main/java/net/jircd/server/JircdServerApplication.java` (depends on T033, T034, T047, T055, T012)
- [ ] T060 Implement a `SIGHUP` signal handler in the `jircd-server` application entry point that invokes `ConfigurationReloader` — the manual, file-only reload trigger that keeps Story 4 usable without depending on Story 6's optional `admin` extension (research.md "Configuration reload mechanism") in `jircd-server/src/main/java/net/jircd/server/SighupReloadHandler.java` (depends on T056, T059)

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

- [ ] T061 [P] [US1] Integration test: two clients register, join `#lobby`, exchange `PRIVMSG`, and the recipient sees the correct `nickname!ident@hostname` sender prefix (FR-004, FR-030) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1ChatTest.java`
- [ ] T062 [P] [US1] Integration test: a client completing registration receives the full Registration Completion Burst in order — `001`, `002`, `003`, `004` (with a non-empty `serverName` and a user-mode letter list of exactly `o`, FR-044), `005 RPL_ISUPPORT` containing `CASEMAPPING=rfc1459`, `CHANTYPES=#`, `NICKLEN=9`, `CHANNELLEN=50`, `TOPICLEN=390` (default configuration, FR-056), `CHANMODES=,,,mnps`, `PREFIX=(ov)@+`, and `UTF8ONLY` (FR-055), then `422 ERR_NOMOTD` — not just `001` alone (FR-050, FR-051) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1RegistrationBurstTest.java`
- [ ] T063 [P] [US1] Integration test: a fully-registered client sends `USER` again (with different argument values than its original) and receives `462 ERR_ALREADYREGISTRED`, with no second Registration Completion Burst sent; a client sends `USER` twice in a row *before* completing registration (before `NICK`/`CAP END`) and the second attempt is also rejected with `462`, not silently accepted (FR-001) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1UserOneShotTest.java`
- [ ] T064 [P] [US1] Integration test: two clients attempt to register the same nickname concurrently; exactly one succeeds, the other gets `433 ERR_NICKNAMEINUSE` (FR-002); a client registering "Alice" then a second client attempting "alice"/"ALICE" is also rejected as in-use (FR-052); the `433` (and a preceding `432`/`431` if triggered) is addressed to `*` while the session has no nickname yet (FR-053) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1NicknameRaceTest.java`
- [ ] T065 [P] [US1] Integration test: a client sends `QUIT :goodbye` while joined to a channel; remaining members receive a `QUIT` notification carrying `"goodbye"` (FR-017, FR-060); a second client sends bare `QUIT` (no reason) and remaining members receive a `QUIT` notification with a non-blank default reason; a third client's connection is closed abruptly (raw socket close, no `QUIT` sent) and remaining members still receive a `QUIT` notification with a non-blank, server-generated reason — all three trigger the identical cleanup (membership removal), differing only in reason text (research.md "Voluntary disconnect and quit reasons") in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1DisconnectCleanupTest.java`
- [ ] T066 [P] [US1] Integration test: a client sends `NICK` only (no `USER` yet, registration incomplete) then `QUIT`; the connection closes cleanly with no error and no crash — `QUIT`'s "any time" precondition includes a still-registering session, which has no channel memberships to clean up (FR-060) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1QuitBeforeRegistrationTest.java`
- [ ] T067 [P] [US1] Load-tagged (`@Tag("load")`) test: channel message delivery latency stays within SC-002's 1-second budget under moderate concurrent load in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1DeliveryLatencyLoadTest.java`
- [ ] T068 [P] [US1] Integration test: any client can view a channel's topic (`331`/`332`) without being a member; a channel operator sets a new topic and members see the change; a non-operator's attempt is rejected with `482 ERR_CHANOPRIVSNEEDED` (FR-040); an operator's attempt to set a topic exceeding the default `topicMaxLength` (390 characters) is rejected with `417 ERR_INPUTTOOLONG`, leaving the previous topic unchanged (FR-056) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1TopicTest.java`
- [ ] T069 [P] [US1] Integration test: `NAMES <channel>` returns the current membership list of a channel the requester has not joined (FR-041) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1NamesTest.java`
- [ ] T070 [P] [US1] Integration test: `LIST` returns every currently active channel (FR-042) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1ListTest.java`
- [ ] T071 [P] [US1] Integration test: a non-privileged client's `MODE <self>` (no mode string) returns `221 RPL_UMODEIS` with an empty mode list; that client's `MODE <self> +o` is rejected with `481 ERR_NOPRIVILEGES`; `MODE <self> -o` is a silent no-op (not an error) since it isn't set; `MODE <self> +z` (an unrecognized flag) is rejected with `501 ERR_UMODEUNKNOWNFLAG`; `MODE <anotherNickname>` (query or `+o`) is rejected with `502 ERR_USERSDONTMATCH` regardless of target (FR-044) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1UserModeTest.java`

### Implementation for User Story 1

- [ ] T072 [US1] Implement the `NICK` command handler: validate against `Hostmask`'s nickname grammar (T018), passing `ServerConfiguration.nicknameMaxLength` (FR-056) as the length ceiling, before attempting a claim via `NicknameRegistry` — format (`432`) and uniqueness (`433`) are independent, sequential checks; `431` for a missing argument. Any of `431`/`432`/`433` sent before this session has successfully claimed a nickname MUST address the reply to `*`, not an empty or unclaimed value (FR-053) in `jircd-core/src/main/java/net/jircd/core/session/command/NickCommandHandler.java` (depends on T018, T054)
- [ ] T073 [US1] Implement the `USER` command handler completing registration: reject with `462 ERR_ALREADYREGISTRED` if `ClientSession.ident` is already set — this session has already processed a `USER` command, whether or not it has reached `REGISTERED` yet (FR-001, data-model.md `ClientSession` validation rules) — checked before anything else, including the UTF-8 validity check below; otherwise send the full Registration Completion Burst (FR-051, contracts/irc-protocol-commands.md "Registration Completion Burst") — `001 RPL_WELCOME`, `002 RPL_YOURHOST`/`004 RPL_MYINFO` (`ServerConfiguration.serverName`/`serverVersion`, FR-050), `003 RPL_CREATED` (process start time), `004`'s channel-mode-letter list from `ExtensionRegistry`'s already-computed `SupportedFeatures` (T049) and its user-mode-letter list from the `UserMode` catalog (T024, FR-044 — `o` this release; a separate source from `SupportedFeatures`, since `RPL_ISUPPORT` advertises no user-mode-equivalent token), and `005 RPL_ISUPPORT` (`CHANMODES`/`PREFIX` included) — never recomputed by this handler itself, split across multiple `005` lines if ever needed to respect FR-049's 512-byte limit (FR-051, FR-055) — then `422 ERR_NOMOTD`; apply `Hostmask`'s username content rule (T018) to derive `ClientSession.ident`, truncating to 9 characters rather than rejecting (contracts/irc-protocol-commands.md "Connection Registration Grammar"); reject with `421`-style malformed-message handling if `<realname>` isn't valid UTF-8 (T018's `Utf8Validator`, FR-054) in `jircd-core/src/main/java/net/jircd/core/session/command/UserCommandHandler.java` (depends on T072, T049, T024)
- [ ] T074 [US1] Implement the `JOIN` command handler: validate `channel` against `ChannelName`'s grammar (passing `ServerConfiguration.channelNameMaxLength`, FR-056, as the length ceiling) and UTF-8 validity (T018) before create-or-join via `ChannelRegistry` — `476 ERR_BADCHANMASK` on either failure, checked independently of and before FR-003's uniqueness/create-or-join logic (FR-048, FR-054, contracts/irc-protocol-commands.md "Channel Name Grammar"); `353`/`366` replies, prefixing each member's nickname with `@` if in `operators` or `+` if in `voiced` — neither prefix for a plain member, `@` taking precedence over `+` if both, FR-045, contracts/irc-protocol-commands.md "Channel Operations". Include the `JOIN`-gate check point required by FR-043: reject unless the joiner passes every currently-active flag in `activeModes` whose `gates` includes `JOIN` (data-model.md `Channel` validation rules) — always a no-op in this release since no such flag is defined yet, but the check point itself MUST exist so a future `JOIN`-gating extension (e.g., invite-only) doesn't require editing this handler in `jircd-core/src/main/java/net/jircd/core/session/command/JoinCommandHandler.java` (depends on T018, T054)
- [ ] T075 [US1] Implement the `PART` command handler: `<reason>`, if given, is echoed on the `PART` notification (absent otherwise — no default synthesized, unlike `QUIT`), rejecting an invalid-UTF-8 `<reason>` with `421`-style malformed-message handling (T018's `Utf8Validator`, FR-054) in `jircd-core/src/main/java/net/jircd/core/session/command/PartCommandHandler.java`
- [ ] T076 [US1] Implement `PRIVMSG`/`NOTICE` command handlers: reject with `421`-style malformed-message handling if the message body isn't valid UTF-8 (T018's `Utf8Validator`, FR-054), before building the recipient set (channel members, or the direct-message target), construct one shared `OutboundMessage` (hostmask-prefixed sender, FR-030) per data-model.md — resolving `senderPresentedForm` by live-checking current `cloak` `ServerExtension` state at that moment, never cached, and never from any recipient's own state (data-model.md `OutboundMessage` validation rules) — and enqueue it onto every recipient's `SessionWriter` queue — this handler MUST NOT itself apply any capability-dependent (`message-tags`/`server-time`) formatting, that happens per-recipient in `SessionWriter` (T025) (FR-004, FR-005) in `jircd-core/src/main/java/net/jircd/core/session/command/MessageCommandHandler.java`
- [ ] T077 [US1] Implement the `QUIT` command handler: usable at any time, including before registration completes (FR-060) — reject an invalid-UTF-8 `<reason>` with `421`-style malformed-message handling (T018's `Utf8Validator`, FR-054), then invoke `DisconnectCleanup` (T032) with the client-supplied `<reason>`, or a fixed default (e.g. `"Client Quit"`) if none was given — never a blank reason (FR-060, research.md "Voluntary disconnect and quit reasons") in `jircd-core/src/main/java/net/jircd/core/session/command/QuitCommandHandler.java` (depends on T032)
- [ ] T078 [US1] Implement the `TOPIC` command handler: with no trailing argument, applies the `DISCOVER`-gate check required by FR-047 (`403 ERR_NOSUCHCHANNEL` if the channel doesn't exist, or if a currently-active flag in `activeModes` gates `DISCOVER` and the requester is neither a member nor holds `administratorPrivilege` — always the identical response either way, data-model.md `Channel` validation rules; a no-op check in this release until Story 5 defines `private`/`secret`), otherwise returns the channel's current topic (`332`) or `331` if unset; with a trailing argument, rejects with `421`-style malformed-message handling if the new topic isn't valid UTF-8 (T018's `Utf8Validator`, FR-054), rejects with `417 ERR_INPUTTOOLONG` if the new topic exceeds `ServerConfiguration.topicMaxLength` (FR-056, checked independently of and after the UTF-8 check), otherwise sets `Channel.topic` and echoes `TOPIC` to all members, rejecting a non-operator with `482 ERR_CHANOPRIVSNEEDED` (FR-040, data-model.md `Channel.topic`) in `jircd-core/src/main/java/net/jircd/core/session/command/TopicCommandHandler.java` (depends on T054)
- [ ] T079 [US1] Implement the `NAMES` command handler, reusing the same `353`/`366` reply logic `JOIN` (T074) already produces — including the `@`/`+` nickname prefixing — and the same `DISCOVER`-gate check `TOPIC` (T078) applies (FR-041, FR-047) in `jircd-core/src/main/java/net/jircd/core/session/command/NamesCommandHandler.java`
- [ ] T080 [US1] Implement the `LIST` command handler, iterating `ChannelRegistry`'s currently active channels and silently omitting any the requester fails the `DISCOVER`-gate check for (same check as `TOPIC`/`NAMES`, T078/T079, FR-042/FR-047 — a no-op filter in this release until Story 5 defines `private`/`secret`) in `jircd-core/src/main/java/net/jircd/core/session/command/ListCommandHandler.java`
- [ ] T081 [US1] Implement the `MODE` (user) command handler: `502 ERR_USERSDONTMATCH` if `<nickname>` isn't the sender's own current nickname (query or set alike, no exceptions — self-only, FR-044); with no mode string, `221 RPL_UMODEIS` listing `ClientSession.userModes`; with `+o`, a no-op if the sender already holds `operator`, otherwise `481 ERR_NOPRIVILEGES` — `+o` can only be acquired via `OPER` (T121), never set directly; with `-o`, a no-op if the sender doesn't hold `operator`, otherwise clears it from `userModes` *and* clears `administratorPrivilege` in the same act (data-model.md `ClientSession` validation rules), then echoes the `MODE` confirmation to the sender only (never broadcast); any other flag letter is `501 ERR_UMODEUNKNOWNFLAG` (FR-044, contracts/irc-protocol-commands.md "User Mode") in `jircd-core/src/main/java/net/jircd/core/session/command/UserModeCommandHandler.java` (depends on T023, T024)
- [ ] T082 [US1] Register the `NICK`/`USER`/`JOIN`/`PART`/`PRIVMSG`/`NOTICE`/`QUIT`/`TOPIC`/`NAMES`/`LIST` handlers, plus a `MODE` router in `ConnectionHandler`'s command dispatch table: routes to `UserModeCommandHandler` (T081) unless `<target>` matches the channel-name prefix (`CHANTYPES`, `#`, FR-048), in which case it's a `421`-style "not yet supported" rejection until Story 5 extends this same router with the channel branch (T109) — a single dispatch-table entry for `MODE` from the start, not two independently registered ones added later — in `jircd-core/src/main/java/net/jircd/core/session/ConnectionHandler.java` (depends on T072-T080, T081)

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

- [ ] T083 [P] [US2] Integration test: `CAP LS 302` returns exactly `message-tags`, `server-time`, `echo-message` — no `sasl` or other capability (FR-025) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story2CapabilityListTest.java`
- [ ] T084 [P] [US2] Integration test: a connection negotiating `server-time`+`echo-message` receives its own tagged `PRIVMSG` echoed back, while a second, non-negotiating connection in the same channel receives the message untagged (FR-007, FR-008) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story2NegotiatedDeliveryTest.java`
- [ ] T085 [P] [US2] Integration test: a connection negotiating only `message-tags` (not `server-time`) still receives a `msgid` tag on a delivered `PRIVMSG` — `msgid` isn't gated behind `server-time` (FR-059); two separate `PRIVMSG`s in quick succession receive two different `msgid` values; a channel message's `msgid` is identical across every recipient that negotiated `message-tags`, including the sender's own `echo-message`-negotiated copy, letting the sender correlate the echo with what it sent (FR-059, research.md "Message identifiers") in `jircd-integration-tests/src/test/java/net/jircd/integration/Story2MessageIdTest.java`

### Implementation for User Story 2

- [ ] T086 [P] [US2] Implement the `message-tags` `CapabilityExtension`, exposing a tag-decoration hook that `SessionWriter` (T025) calls per-recipient at drain time — it does not itself touch any `OutboundMessage` or queue, it only produces the tag-prefix content when asked, for whichever session currently has it negotiated and enabled. Unconditionally contributes the `msgid` tag from `OutboundMessage.messageId` (FR-059, research.md "Message identifiers") — present whenever this capability is negotiated at all, unlike `server-time`'s `time` tag (T087), which requires that capability to be *separately* negotiated too — in `jircd-capabilities/message-tags/src/main/java/net/jircd/capabilities/messagetags/MessageTagsExtension.java`
- [ ] T087 [P] [US2] Implement the `server-time` `CapabilityExtension`, exposing a hook that produces the `time` tag from `OutboundMessage.sentAt` (data-model.md — the sender's send-time instant, not each recipient's drain time), called the same per-recipient way as `message-tags` (T086) in `jircd-capabilities/server-time/src/main/java/net/jircd/capabilities/servertime/ServerTimeExtension.java`
- [ ] T088 [P] [US2] Implement the `echo-message` `CapabilityExtension`: unlike T086/T087, this affects *recipient-set construction*, not per-recipient formatting — it exposes a hook `MessageCommandHandler` (T076) calls once, when building the recipient list, to decide whether the sender's own session is included in the `OutboundMessage` fan-out in `jircd-capabilities/echo-message/src/main/java/net/jircd/capabilities/echomessage/EchoMessageExtension.java`
- [ ] T089 [P] Register `message-tags`, `server-time`, and `echo-message` as `ServiceLoader` providers via `META-INF/services/net.jircd.core.extension.CapabilityExtension` in each of `jircd-capabilities/message-tags/src/main/resources/`, `jircd-capabilities/server-time/src/main/resources/`, and `jircd-capabilities/echo-message/src/main/resources/`
- [ ] T090 [US2] Wire `SessionWriter`'s per-recipient drain-time formatting (T025) to actually call the `message-tags`/`server-time` decoration hooks (T086/T087) for each session's currently negotiated-and-enabled capabilities — `CapabilityNegotiator` itself only tracks what a session negotiated (T051/T052); it does not do formatting (depends on T025, T052, T086-T087) in `jircd-core/src/main/java/net/jircd/core/session/SessionWriter.java`

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

- [ ] T091 [P] [US4] Integration test: editing `capabilities.message-tags` to `disabled` and sending the process `SIGHUP` removes it from `CAP LS` and stops tagging already-connected sessions' messages within SC-005's 1-minute budget, with no server restart (SC-005, FR-011) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story4ConfigToggleTest.java`
- [ ] T092 [P] [US4] Integration test: an invalid configuration (`capabilities.nonexistent`, `capabilities.moderation`, `capabilities.cloak` — a section/kind mismatch) at both server startup and via a `SIGHUP`-triggered reload each produce a specific error naming the offending key, and a `SIGHUP` reload rejection leaves the server running on its previous, valid configuration (FR-012, SC-008) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story4InvalidConfigTest.java`
- [ ] T093 [P] [US4] Integration test: starting the server with `nicknameMaxLength: 5`, `channelNameMaxLength: 10`, `topicMaxLength: 20` configured enforces those (not the 9/50/390 defaults) against `NICK`/`JOIN`/`TOPIC`, and `005 RPL_ISUPPORT` advertises `NICKLEN=5`/`CHANNELLEN=10`/`TOPICLEN=20`; a config value of `0`, a non-integer, or `401` (over the 400 ceiling) for any of the three is rejected at startup with a specific error naming the offending key (FR-056, FR-012) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story4LengthLimitConfigTest.java`

### Implementation for User Story 4

- [ ] T094 [US4] Wire `JircdServerApplication` startup to refuse to start and report the specific validation error when `ConfigurationLoader` rejects the configuration (FR-012) in `jircd-server/src/main/java/net/jircd/server/JircdServerApplication.java`

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

- [ ] T095 [P] [US5] Integration test: a channel operator `KICK`s a member; the member is removed and both the target and remaining members are notified (FR-013, FR-014) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5KickTest.java`
- [ ] T096 [P] [US5] Integration test: a non-operator's `KICK` attempt is rejected with `482 ERR_CHANOPRIVSNEEDED`, and the target is not removed (FR-014) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5KickPermissionTest.java`
- [ ] T097 [P] [US5] Integration test: an operator sets moderated mode (`MODE +m`); a non-permitted member's `PRIVMSG` is not delivered and they receive a clear explanation (FR-013) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5ModeRestrictionTest.java`
- [ ] T098 [P] [US5] Integration test: `KICK` is available immediately after server startup with no configuration step required (FR-036) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5AlwaysAvailableTest.java`
- [ ] T099 [P] [US5] Integration test: an operator grants voice (`MODE +v`) to a non-operator member of a moderated channel; that member's `PRIVMSG` is now delivered, unlike a non-voiced, non-operator member's (FR-045); a non-operator's `MODE +v` attempt is rejected with `482 ERR_CHANOPRIVSNEEDED`; `MODE +v` naming a nickname not currently in the channel is rejected with `441 ERR_USERNOTINCHANNEL` in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5VoiceTest.java`
- [ ] T100 [P] [US5] Integration test: `353 RPL_NAMREPLY` (via `JOIN` or `NAMES`) prefixes an operator's nickname with `@`, a voiced non-operator's with `+`, and a plain member's with neither (FR-045, contracts/irc-protocol-commands.md "Channel Operations") in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5NamesPrefixTest.java`
- [ ] T101 [P] [US5] Integration test: an operator grants operator status (`MODE +o`) to a non-operator member; that member can now perform a moderation action (e.g., `KICK`), the same as the original operator (FR-046); a non-operator's `MODE +o` attempt is rejected with `482 ERR_CHANOPRIVSNEEDED`; `MODE +o` naming a nickname not currently in the channel is rejected with `441 ERR_USERNOTINCHANNEL`; an operator revoking their own status (`MODE -o` on themselves) succeeds even if it leaves the channel with zero operators in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5OperatorGrantTest.java`
- [ ] T102 [P] [US5] Integration test: an operator sets `MODE +s` on their channel; a non-member's `TOPIC`/`NAMES` on it receives `403 ERR_NOSUCHCHANNEL`, indistinguishable from a genuinely nonexistent channel, and it's omitted from a non-member's `LIST`; a current member sees it normally in all three; a privileged (administrator) session sees it normally despite not being a member (FR-047); setting `MODE +p` then `+s` on the same channel clears `+p` (mutual exclusion, data-model.md `Channel` validation rules) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5PrivacyTest.java`

### Implementation for User Story 5

- [ ] T103 [US5] Implement the `KICK` command handler (operator-only, removes target, notifies the channel, `482` on unauthorized, logs rejected attempts via `SecurityEventLog`, FR-013/FR-014/FR-019) in `jircd-core/src/main/java/net/jircd/core/session/command/KickCommandHandler.java`
- [ ] T104 [US5] Implement the `MODE` command handler against `Channel.activeModes`: set/clear `+m`/`-m` (`moderated`) and `+n`/`-n` (`members-only`) independently (both may be active at once, FR-013, contracts/irc-protocol-commands.md "Full Channel Mode Catalog"); `472` on a flag matching neither a core-defined nor a currently-`ENABLED` extension-defined `ChannelMode`, or on a bare mode query with no flag argument (FR-043); `482` on unauthorized; logs rejected attempts via `SecurityEventLog` in `jircd-core/src/main/java/net/jircd/core/session/command/ModeCommandHandler.java`
- [ ] T105 [US5] Extend the `MODE` command handler with `+p`/`-p` (`private`) and `+s`/`-s` (`secret`) (FR-047): set/clear independently in `Channel.activeModes` like `+m`/`+n` (T104), except setting one MUST clear the other if active (mutual exclusion, data-model.md `Channel` validation rules); `482` on unauthorized; logs rejected attempts via `SecurityEventLog` in `jircd-core/src/main/java/net/jircd/core/session/command/ModeCommandHandler.java` (depends on T104)
- [ ] T106 [US5] Extend the `MODE` command handler with `+v`/`-v <nickname>` (FR-045): resolve `<nickname>` to a current member of the channel — `441 ERR_USERNOTINCHANNEL` if not — then add/remove it from `Channel.voiced` (not `activeModes`, since `voice` is `MEMBER`-kind, data-model.md `ChannelMode`); `482` on unauthorized; logs rejected attempts via `SecurityEventLog` in `jircd-core/src/main/java/net/jircd/core/session/command/ModeCommandHandler.java` (depends on T104)
- [ ] T107 [US5] Extend the `MODE` command handler with `+o`/`-o <nickname>` (FR-046), the same shape as `+v`/`-v` (T106): resolve `<nickname>` to a current member — `441` if not — then add/remove it from `Channel.operators` (not `activeModes`); `482` on unauthorized; a sender MAY target themselves, including a self-revocation that leaves the channel with zero operators (data-model.md `Channel` validation rules); logs rejected attempts via `SecurityEventLog` in `jircd-core/src/main/java/net/jircd/core/session/command/ModeCommandHandler.java` (depends on T104)
- [ ] T108 [US5] Enforce `Channel.activeModes` in the `PRIVMSG`/`NOTICE` path — the `SEND`-gate check point required by FR-043: reject unless the sender passes every currently-active flag in `activeModes` whose `gates` includes `SEND`, checked independently per flag — `MEMBERS_ONLY` requires membership; `MODERATED` requires the sender to be in `operators` **or** `voiced` (FR-045, matching classic IRC's full `+m` semantic, data-model.md `Channel` validation rules) — with a clear error (depends on T076, T106) in `jircd-core/src/main/java/net/jircd/core/session/command/MessageCommandHandler.java`
- [ ] T109 [US5] Register the `KICK` handler, and extend the `MODE` router T082 already registered with its channel branch (`<target>` matching the channel-name prefix, `CHANTYPES`, `#`, FR-048, now delegates to `ModeCommandHandler` — T103-T107 — instead of T082's placeholder rejection); the user branch (`UserModeCommandHandler`, T081) is unchanged — in `ConnectionHandler`'s command dispatch table (depends on T082, T103, T104, T105, T106, T107) in `jircd-core/src/main/java/net/jircd/core/session/ConnectionHandler.java`

**Checkpoint**: User Stories 1, 2, 4, and 5 all work independently.

---

## Phase 7: User Story 6 - Administer the Server via IRC Commands (Priority: P4)

**Goal**: An administrator grants themselves privilege in-band and issues
administrative commands (extension toggle, hostname lookup, full config
reload, forced channel join, self-op) over the IRC protocol itself — the
first four with the same effect as the configuration-file/`SIGHUP` path,
the latter two (`SAJOIN`/`SAMODE`, FR-057/FR-058) with no configuration-
file equivalent at all, since they act on live channel state, not
configuration.

**Independent Test**: Issue the privilege-granting command with valid
credentials, confirm privilege is granted, then toggle an extension
in-band and confirm the effect matches the configuration-file path
(quickstart.md Story 6).

### Tests for User Story 6

> **Write these tests FIRST, ensure they FAIL before implementation.**

- [ ] T110 [P] [US6] Integration test: a non-privileged session's `EXTENSION` command is rejected with `481 ERR_NOPRIVILEGES` (FR-033) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story6PrivilegeGateTest.java`
- [ ] T111 [P] [US6] Integration test: `OPER` with valid credentials returns `381 RPL_YOUREOPER`; with invalid credentials returns `464 ERR_PASSWDMISMATCH` and the failure is logged as a security event (FR-019, FR-034) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story6OperTest.java`
- [ ] T112 [P] [US6] Integration test: after a successful `OPER`, that session's own `MODE <self>` shows `221 RPL_UMODEIS :+o`, and a *different* client's `WHOIS` of that nickname includes `313 RPL_WHOISOPERATOR` (visible to any client, not just the operator or an administrator, FR-037/FR-044); the now-privileged session then sends `MODE <self> -o`, after which its own subsequent admin command (e.g. `WHOHOST`) is rejected with `481 ERR_NOPRIVILEGES` and a fresh `WHOIS` of it by another client no longer shows `313` — self-revocation actually revokes the privilege, not just the display flag (FR-044) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story6OperUserModeTest.java`
- [ ] T113 [P] [US6] Integration test: a privileged session's `EXTENSION DISABLE message-tags` has the same observable effect as Story 4's config-file path, with no config file edit involved, within SC-009's budget (path equivalence, contracts/server-configuration.md) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story6ExtensionToggleTest.java`
- [ ] T114 [P] [US6] Integration test: `EXTENSION DISABLE moderation` and `EXTENSION DISABLE capability-negotiation` are both rejected as unknown/non-toggleable, not silently accepted (FR-035, FR-036) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story6NonToggleableRejectionTest.java`
- [ ] T115 [P] [US6] Integration test: `WHOHOST` returns a target's real hostname to a privileged admin even while the `cloak` extension obscures it for other clients (FR-031, FR-032) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story6WhohostTest.java`
- [ ] T116 [P] [US6] Integration test: `EXTENSION DISABLE admin`, issued by a privileged session, succeeds; that session's subsequent admin commands are then rejected with `481`, while the configuration-file/`SIGHUP` path still works (contracts/irc-protocol-commands.md "Self-lockout") in `jircd-integration-tests/src/test/java/net/jircd/integration/Story6SelfLockoutTest.java`
- [ ] T117 [P] [US6] Integration test: a privileged session edits nothing but sends `REHASH` after the config file's `rateLimit.bucketSize` was changed on disk; the new value takes effect and `382 RPL_REHASHING` is returned — the in-band equivalent of Story 4's `SIGHUP` path (research.md "Configuration reload mechanism"); a `REHASH` against an invalid file returns the specific validation error directly to the session and leaves the running configuration untouched in `jircd-integration-tests/src/test/java/net/jircd/integration/Story6RehashTest.java`
- [ ] T118 [P] [US6] Integration test: a privileged session issues `SAJOIN` on a channel and joins it, receiving the same `JOIN`/`353`/`366` replies ordinary `JOIN` would; a non-privileged session's `SAJOIN` is rejected with `481 ERR_NOPRIVILEGES`; `SAJOIN` naming a malformed channel is rejected with `476 ERR_BADCHANMASK`, the same as ordinary `JOIN` — the grammar check is NOT bypassed (FR-057) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story6SajoinTest.java`
- [ ] T119 [P] [US6] Integration test: a privileged session that has already joined a channel with existing members and an existing operator issues `SAMODE <channel> +o` and is granted operator status immediately, with no involvement from the existing operator; a non-privileged session's `SAMODE` is rejected with `481 ERR_NOPRIVILEGES`; a privileged session's `SAMODE` on a channel it hasn't joined is rejected with `442 ERR_NOTONCHANNEL` (FR-058) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story6SamodeTest.java`

### Implementation for User Story 6

- [ ] T120 [US6] Implement administrator-credential verification against `ServerConfiguration.administratorCredentials` using the password-hashing library (research.md "Administrator credential storage", FR-034) in `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/AdminCredentialVerifier.java`
- [ ] T121 [US6] Implement the `OPER` command handler (grants `ClientSession.administratorPrivilege` on success, and adds `operator` to `ClientSession.userModes` in the same act, FR-044; `381`/`464`; logs failures via `SecurityEventLog`) in `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/OperCommandHandler.java` (depends on T120, T024)
- [ ] T122 [US6] Implement the `EXTENSION` command handler (`ENABLE`/`DISABLE` via `ExtensionRegistry`; `481` if unprivileged; specific error for unknown or non-toggleable ids) in `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/ExtensionCommandHandler.java`
- [ ] T123 [US6] Implement the `REHASH` command handler — admin-privilege gated, invokes `ConfigurationReloader`; `382 RPL_REHASHING` on success, the reloader's specific validation error on failure, `481` if unprivileged (research.md "Configuration reload mechanism", FR-012) in `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/RehashCommandHandler.java` (depends on T056)
- [ ] T124 [US6] Implement the `WHOHOST` command handler (reads `ClientSession.realHostname` directly, bypassing any cloak extension) in `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/WhohostCommandHandler.java`
- [ ] T125 [US6] Implement the `SAJOIN` command handler — admin-privilege gated (`481 ERR_NOPRIVILEGES` if unprivileged); joins the sender via the same create-or-join path `JoinCommandHandler`/`ChannelRegistry` use (FR-003, FR-013) but skips the `JOIN`-gate check point (FR-043's `gates` mechanism) entirely; channel-name grammar and UTF-8 validity (T018) are NOT skipped, so `476 ERR_BADCHANMASK` still applies (FR-057, research.md "Administrator channel override") in `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/SajoinCommandHandler.java` (depends on T074)
- [ ] T126 [US6] Implement the `SAMODE` command handler — admin-privilege gated (`481 ERR_NOPRIVILEGES` if unprivileged); requires the sender to currently be a member of the target channel (`442 ERR_NOTONCHANNEL` if not); adds/removes the sender's own nickname from `Channel.operators` via the same mechanism `ModeCommandHandler`'s `+o`/`-o` uses (T107), bypassing FR-046's "sender must already be an operator" precondition — self-targeting only, accepts no target parameter (FR-058, research.md "Administrator channel override") in `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/SamodeCommandHandler.java` (depends on T107)
- [ ] T127 [US6] Implement the `admin` `ServerExtension` wiring, registering the `OPER`/`EXTENSION`/`REHASH`/`WHOHOST`/`SAJOIN`/`SAMODE` handlers with `jircd-core`'s command dispatch when enabled (depends on T121-T124, T125, T126) in `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/AdminExtension.java`
- [ ] T128 [US6] Implement the `cloak` `ServerExtension`, claiming the `hostname-display` extension point (research.md "Cloak extension boundary", FR-031) in `jircd-server-extensions/cloak/src/main/java/net/jircd/serverextensions/cloak/CloakExtension.java`
- [ ] T129 [P] [US6] Register `admin` and `cloak` as `ServiceLoader` providers via `META-INF/services/net.jircd.core.extension.ServerExtension` in `jircd-server-extensions/admin/src/main/resources/` and `jircd-server-extensions/cloak/src/main/resources/`

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

- [ ] T130 [P] [US7] Integration test: a client performs a self-lookup (no target) and receives its own real hostname/IP, even while the `cloak` extension is currently obscuring it from other clients (FR-038 case 1) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story7SelfLookupTest.java`
- [ ] T131 [P] [US7] Integration test: a privileged session looks up a *different* connected client and receives that client's real hostname/IP, consistent with `WHOHOST` (FR-038 case 2) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story7AdminLookupTest.java`
- [ ] T132 [P] [US7] Integration test: a non-privileged session looks up a *different* connected client and receives only that client's presented hostname (the same value its message hostmask shows) — never the real value, whether or not `cloak` is enabled (FR-038 case 3, SC-010) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story7RegularLookupTest.java`
- [ ] T133 [P] [US7] Integration test: a lookup for a nickname that isn't connected returns `401 ERR_NOSUCHNICK`, no user data (FR-037) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story7NoSuchNickTest.java`

### Implementation for User Story 7

- [ ] T134 [US7] Implement the `WHOIS` command handler: resolve the target session (the sender's own if no argument given), apply FR-038's real-vs-presented resolution by reusing `UserIdentity.presentedForm`'s existing computation and `ClientSession.realHostname` (never a new, independent resolution — research.md "Cloak extension boundary"), reply `311 RPL_WHOISUSER`, then `313 RPL_WHOISOPERATOR` if the target's `userModes` currently contains `operator` — visible to any querying client, unlike the hostname resolution above (FR-037, FR-044) — then `318 RPL_ENDOFWHOIS`, or `401 ERR_NOSUCHNICK` if the target isn't connected. Core protocol behavior (FR-037), never an optional extension in `jircd-core/src/main/java/net/jircd/core/session/command/WhoisCommandHandler.java` (depends on T024)
- [ ] T135 [US7] Register the `WHOIS` handler in `ConnectionHandler`'s command dispatch table (depends on T082, T134) in `jircd-core/src/main/java/net/jircd/core/session/ConnectionHandler.java`

**Checkpoint**: All six mandatory user stories (1, 2, 4, 5, 6, 7) work
independently.

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Whole-system validation and documentation that spans
multiple stories.

- [ ] T136 [P] Load-tagged (`@Tag("load")`) test: the server sustains 1,000 simultaneous connections without channel message delivery delay exceeding SC-002's target (SC-003) in `jircd-integration-tests/src/test/java/net/jircd/integration/ConcurrentConnectionScaleLoadTest.java`
- [ ] T137 [P] Load-tagged (`@Tag("load")`) test: during a sustained flood from one connection, delivery latency for other well-behaved clients does not increase beyond SC-002's target (SC-006) in `jircd-integration-tests/src/test/java/net/jircd/integration/RateLimitLoadTest.java`
- [ ] T138 [P] Update `README.md`'s "Getting started" section with real build/run instructions now that the project builds (`./gradlew build`, `./gradlew :jircd-server:run`), replacing the pre-implementation placeholder
- [ ] T139 Run the full `specs/001-ircv3-server/quickstart.md` validation pass manually against a running `./gradlew :jircd-server:run` instance, covering Stories 1, 2, 4, 5, 6, and 7 end-to-end (constitution UX Consistency principle's required manual usage-scenario check)
- [ ] T140 [P] Code cleanup pass: remove any dead code/TODOs introduced during implementation and confirm `./gradlew build` runs Spotless and SpotBugs clean across all subprojects (constitution Code Quality principle)

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
  Foundational (T056, T060), so Story 4's own phase is small.
- **User Story 5 (P5)**: No dependencies on other stories; builds on the
  `Channel` aggregate from Foundational.
- **User Story 6 (P4)**: Its core administration commands (`OPER`,
  `EXTENSION`, `WHOHOST`, `REHASH`) have no dependencies on other
  stories, and are most demonstrable once Story 2 exists — `REHASH`
  (T123) depends only on Foundational's `ConfigurationReloader` (T056),
  not on Story 4. `SAJOIN`/`SAMODE` (FR-057/FR-058) are the exception:
  `SAJOIN` reuses Story 1's `JoinCommandHandler`/`ChannelRegistry` path
  (T074), and `SAMODE` reuses Story 5's `MODE +o`/`-o` handling (T107)
  — both are real, not incidental, dependencies (each command bypasses a
  specific precondition the reused path already enforces, so the path
  has to exist first), so those two tasks specifically cannot start
  before Stories 1 and 5 respectively, even though the rest of Story 6
  can.
- **User Story 7 (P2)**: No dependencies on other stories to compile or
  run — the self-lookup case (T130) needs nothing beyond Foundational.
  Its administrator-lookup test (T131) is most meaningful once Story 6's
  `OPER` exists, and its cloak-interaction assertions (T130/T132) are most
  meaningful once Story 6's `cloak` extension exists, but the `WHOIS`
  handler itself (T134) only depends on `ClientSession.realHostname` and
  `UserIdentity.presentedForm`, both Foundational — it does not call into
  `WHOHOST` or any Story 6 code.

### Within Each User Story

- Tests MUST be written and FAIL before implementation.
- Command handlers before dispatch-table registration.
- Story complete (checkpoint) before moving to the next priority, if
  working sequentially.

### Parallel Opportunities

- All Setup tasks marked `[P]` can run in parallel.
- Within Foundational, the protocol-layer tasks (T016-T022), the
  `Extension`/`CapabilityExtension`/`ServerExtension` interface
  definitions (T043-T045), and the two independent test-writing tasks in
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

Watch for the one known cross-story file touch: Story 5's T108 extends
`MessageCommandHandler.java`, which Story 1's T076 creates — sequence
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
  T056/T060/T123) — there is deliberately no automatic file-watching task;
  see research.md "Configuration reload mechanism" for why.
- Verify each story's tests fail before implementing that story.
- Commit after each task or logical group.
- Stop at any checkpoint to validate a story independently before
  continuing.
- The two `[P]`-marked capability/server-extension `ServiceLoader`
  registration tasks (T089, T129) are each one task covering multiple
  small resource files (one per extension in that group) — split further
  only if working across them in true parallel by different people.
