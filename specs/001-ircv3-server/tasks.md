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

- [X] T001 Create the multi-level Gradle project skeleton: `settings.gradle.kts` including `jircd-protocol`, `jircd-core`, `jircd-capabilities`, `:jircd-capabilities:message-tags`, `:jircd-capabilities:server-time`, `:jircd-capabilities:echo-message`, `jircd-server-extensions`, `:jircd-server-extensions:cloak`, `:jircd-server-extensions:admin`, `jircd-server`, `jircd-integration-tests`, plus empty `src/main/java`/`src/test/java` directories per plan.md's Project Structure tree
- [X] T002 Configure root `build.gradle.kts`: Java 25 toolchain, shared repositories, and a `subprojects {}` block applying the Java plugin, UTF-8 source encoding, and JUnit Platform test execution to every subproject
- [X] T003 [P] Add a Gradle version catalog in `gradle/libs.versions.toml` declaring SLF4J, Logback, SnakeYAML, JUnit 5 (Jupiter), AssertJ, a salted/computationally-expensive password-hashing library (research.md "Administrator credential storage"), the Spotless Gradle plugin, and the SpotBugs Gradle plugin
- [X] T004 [P] Configure Spotless in root `build.gradle.kts`, applied to all subprojects (research.md "Static analysis / code quality tooling")
- [X] T005 [P] Configure SpotBugs in root `build.gradle.kts`, applied to all subprojects, failing the build on findings (research.md "Static analysis / code quality tooling")
- [X] T006 [P] Configure JUnit 5 test tagging in root `build.gradle.kts`: the default `test` task excludes tests tagged `"load"`; add a separate `loadTest` Gradle task that runs only `"load"`-tagged tests (research.md "Deterministic testing under concurrency")
- [X] T007 [P] Create `jircd-protocol/build.gradle.kts` (JUnit 5 + AssertJ test dependencies only; no other module dependencies — research.md "Protocol/server boundary")
- [X] T008 Create `jircd-core/build.gradle.kts`: depends on `jircd-protocol`; runtime dependencies on SLF4J, Logback, SnakeYAML, and the password-hashing library; JUnit 5 + AssertJ test dependencies
- [X] T009 [P] Create `jircd-capabilities/message-tags/build.gradle.kts`, `jircd-capabilities/server-time/build.gradle.kts`, and `jircd-capabilities/echo-message/build.gradle.kts`, each depending on `jircd-core` (for the `Extension`/`CapabilityExtension` SPI, research.md "Extension system")
- [X] T010 [P] Create `jircd-server-extensions/cloak/build.gradle.kts` and `jircd-server-extensions/admin/build.gradle.kts`, each depending on `jircd-core` (for the `Extension`/`ServerExtension` SPI); `admin` additionally depends on the password-hashing library (FR-034)
- [X] T011 Create `jircd-server/build.gradle.kts`: depends on `jircd-core`, and declares a runtime dependency on every `jircd-capabilities/*` and `jircd-server-extensions/*` subproject so they're on the classpath for `ServiceLoader` discovery (research.md "Extension system")
- [X] T012 [P] Set `version` in root `gradle.properties` as the project's single source-of-truth version string, and wire a `generateVersionResource` task in `jircd-server/build.gradle.kts` into `processResources` that writes `net/jircd/server/version.properties` (one `version=<project.version>` line) onto the module's classpath, so it's present identically whether classes are loaded from exploded build output or a packaged JAR (research.md "Server identity")
- [X] T013 Create `jircd-integration-tests/build.gradle.kts`: depends on `jircd-server` and `jircd-protocol`; JUnit 5 + AssertJ
- [X] T014 [P] Configure Logback in `jircd-server/src/main/resources/logback.xml` with structured, leveled output and file rotation (research.md "Logging", FR-019)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure every user story depends on — wire
protocol, connection/session lifecycle, channel aggregate, networking,
the Extension system, capability-negotiation mechanism, and Server
Configuration loading plus its manual reload mechanism.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Wire protocol (`jircd-protocol`)

- [X] T015 Define the core message model, including a complete `Command` catalog covering every RFC 1459/2812 command plus `CAP`/`AUTHENTICATE`/`TAGMSG` (research.md "Wire-protocol command & numeric completeness", contracts/irc-protocol-commands.md "Full Command Catalog") — not only the commands `jircd-core` implements this release — plus the message tag map, in `jircd-protocol/src/main/java/net/jircd/protocol/Message.java`
- [X] T016 [P] Implement the line-based message parser (raw line → `Message`) in `jircd-protocol/src/main/java/net/jircd/protocol/MessageParser.java`
- [X] T017 [P] Implement the message serializer (`Message` → raw line, including `message-tags`-style tag prefix formatting) in `jircd-protocol/src/main/java/net/jircd/protocol/MessageSerializer.java`
- [X] T018 [P] Implement hostmask formatting (`nickname!ident@hostname`, FR-030) and the nickname/username content grammar (RFC 2812 §2.3.1 nickname BNF; username content rule) both defined in contracts/irc-protocol-commands.md "Connection Registration Grammar" — nickname/username format validation is a wire-protocol rule, not a `jircd-core` handler concern, so it lives here alongside `Hostmask`, which already needs the same rule to compose a valid `nickname!ident@hostname` (research.md "Connection registration grammar"); the nickname grammar's length ceiling is a caller-supplied parameter (`Hostmask.isValidNickname(String nickname, int maxLength)`), not a hardcoded constant — `jircd-protocol` MUST NOT depend on `ServerConfiguration` (FR-056, research.md "Protocol/server boundary" — Consequence for FR-056) — in `jircd-protocol/src/main/java/net/jircd/protocol/Hostmask.java`. Also implement the channel name grammar (leading `#`, additional characters excluding space/comma/control characters, up to a caller-supplied maximum length, FR-048/FR-056, contracts/irc-protocol-commands.md "Channel Name Grammar") as a sibling wire-protocol validator, same package and same parameterized-length approach, in `jircd-protocol/src/main/java/net/jircd/protocol/ChannelName.java`. Also implement a shared UTF-8 validity check (FR-054) as another sibling utility, used by `ChannelName` (channel names) and by `jircd-core`'s `PRIVMSG`/`NOTICE`/`TOPIC`-set/`USER` handlers (message bodies, topics, realnames) — not by `Hostmask`'s nickname/username grammars, which are ASCII-only and unaffected — in `jircd-protocol/src/main/java/net/jircd/protocol/Utf8Validator.java`
- [X] T019 [P] Implement `NickMask` (FR-062): `normalize(String mask)` fills a partial `nick!user@host` mask's missing `user`/`host` segment(s) with `*` (e.g. `alice` → `alice!*@*`); `matches(String identity, String mask)` performs case-insensitive `*`/`?` wildcard matching of a full `nickname!ident@hostname` string against a normalized mask — deliberately agnostic to *which* hostname value the caller passes (presented or real, T076/T118/T119 each call it twice, once per identity) — a pure string-matching utility, no `jircd-core` dependency, reusable by a future client library the same way `Hostmask`/`ChannelName` already are (research.md "Channel/user mode extensibility" — "`LIST`-kind flags in practice") in `jircd-protocol/src/main/java/net/jircd/protocol/NickMask.java`
- [X] T020 [P] Implement `ModeStringParser` (FR-064): `parse(String... modeArgs)` parses a `MODE` command's leading modestring argument(s) (e.g. `+bbb-o`, or the RFC-permitted `+b` `-o` split across multiple arguments) into an ordered `List<ModeChange>`, each holding a `sign` (`+`/`-`) and a `flag` (character) — no knowledge of what any flag means, whether it needs a parameter, or how many total arguments the command has; that's `jircd-core`'s `ChannelMode`/`UserMode` catalog lookup and parameter-consumption logic to apply, not this parser's concern. A pure, stateless utility, no `jircd-core` dependency, reusable by a future client library the same way `NickMask`/`Hostmask`/`ChannelName` already are; matches RFC 2812 §3.2.3's own `MODE` grammar (`*( (+/-) *modes *modeparam )`, research.md "MODE command grouping") in `jircd-protocol/src/main/java/net/jircd/protocol/ModeStringParser.java`
- [X] T021 [P] Implement the CAP negotiation grammar (`CAP LS`/`REQ`/`ACK`/`NAK`/`END` line formats) in `jircd-protocol/src/main/java/net/jircd/protocol/CapabilityNegotiationGrammar.java`
- [X] T022 [P] Define the complete numeric reply catalog per `contracts/irc-numeric-replies.md`'s "Full Numeric Catalog" — every RFC 1459/2812 `RPL_*`/`ERR_*` numeric, plus `417 ERR_INPUTTOOLONG` (the one deliberate non-RFC addition, FR-049) — not only the "Used in This Release" subset — in `jircd-protocol/src/main/java/net/jircd/protocol/NumericReply.java`
- [X] T023 [P] Unit tests for `MessageParser` covering valid and malformed lines (FR-015), including a completeness assertion that every command in the Full Command Catalog (contracts/irc-protocol-commands.md) parses to a recognized `Command` entry regardless of whether `jircd-core` implements it yet in `jircd-protocol/src/test/java/net/jircd/protocol/MessageParserTest.java`
- [X] T024 [P] Unit tests for `MessageSerializer` and `Hostmask` formatting, including a completeness assertion that every numeric in the Full Numeric Catalog (contracts/irc-numeric-replies.md) has a `NumericReply` entry with the documented code and name, nickname-grammar validation cases (valid/invalid leading character, valid/invalid body characters, exactly-9-characters boundary, 10-character rejection, all against the default `maxLength=9`, plus a case with a smaller caller-supplied `maxLength` proving the ceiling is a real parameter, not a hardcoded constant, FR-056), `ChannelName` grammar cases (valid/missing leading `#`, valid/invalid body characters, exactly-50-characters boundary, 51-character rejection, all against the default `maxLength=50`, plus a differently-configured-`maxLength` case mirroring the nickname one, FR-048/FR-056), and `Utf8Validator` cases (valid multi-byte UTF-8 accepted, a truncated/invalid multi-byte sequence rejected, FR-054) in `jircd-protocol/src/test/java/net/jircd/protocol/MessageSerializerTest.java`

### Session & channel aggregates (`jircd-core/session`)

- [X] T025 Define the `ClientSession` aggregate root (data-model.md fields: `connectionId`, I/O `channel`, `outboundQueue`, `registrationState`, `nickname`, `negotiatedCapabilities`, `channelMemberships`, `rateLimitBucket`, `ident`, `realHostname`, `administratorPrivilege`, `userModes` — `Set<UserMode>`, kept in lockstep with `administratorPrivilege`, never independently toggled, FR-044 — `failedOperAttempts`, a non-negative counter defaulting to `0`, incremented on each `OPER` credential failure and reset to `0` on success, FR-034, research.md "OPER failed-attempt lockout") in `jircd-core/src/main/java/net/jircd/core/session/ClientSession.java`
- [X] T026 [P] Define the `UserMode` value type (`id`, `flag`, `definedBy` — `CORE` or an `Extension` id, `clientSettable` — boolean, whether a client may set the `+` direction on itself with no privilege check, data-model.md `UserMode`) and its core catalog, populated with two entries: `id: operator, flag: o, definedBy: CORE, clientSettable: false` (FR-044) and `id: invisible, flag: i, definedBy: CORE, clientSettable: true` (FR-044/FR-061) in `jircd-core/src/main/java/net/jircd/core/session/UserMode.java`
- [X] T027 Implement the per-session bounded outbound queue of `OutboundMessage` (data-model.md) and its dedicated writer virtual thread — the only path that writes to that session's socket. At drain time, the writer thread converts each `OutboundMessage` into the actual wire line by applying *this session's own* `negotiatedCapabilities`, live-checked against current `CapabilityExtension` state, never cached (research.md "Message fan-out concurrency model", data-model.md "Capability" validation rules); queue-overflow transitions the session to `CLOSING` (data-model.md `ClientSession` validation rules) in `jircd-core/src/main/java/net/jircd/core/session/SessionWriter.java`
- [X] T028 Implement the connection lifecycle state machine (`CONNECTING` → `REGISTERED` → `CLOSING`, FR-001) in `jircd-core/src/main/java/net/jircd/core/session/ConnectionLifecycle.java`
- [X] T029 Implement the nickname registry with atomic claim/uniqueness — exactly one winner on a concurrent claim, no window where two sessions hold the same name (FR-002); claims compared using the rfc1459 casemapping (FR-052, research.md "IRC casemapping" — ASCII fold plus `[]\^` ↔ `{}|~`), storing the original casing a client registered with, never a folded/normalized form; scope the uniqueness check behind a single interface so it can later be widened from server-local to network-wide without callers changing (FR-022) in `jircd-core/src/main/java/net/jircd/core/session/NicknameRegistry.java`
- [X] T030 [P] Unit tests for `NicknameRegistry`, including a concurrent-claim race asserting exactly one winner (FR-002), and casemapping cases: "Alice" then "alice" is rejected as in-use, "Alice" then "ALICE", and a lookup by any casing resolves to the originally-registered casing (FR-052) in `jircd-core/src/test/java/net/jircd/core/session/NicknameRegistryTest.java`
- [X] T031 Define the `Channel` aggregate root (`name`, `members`, `operators`, `voiced` — subset of `members`, independent of `operators`, FR-045 — `activeModes` — a `Set<ChannelMode>`, not a closed enum, so a `ServerExtension` can later contribute additional `BOOLEAN`-kind flags without a data-model change, FR-043, research.md "Channel/user mode extensibility" — `topic`, first-join-gets-operator invariant enforced at creation, FR-013 — `bans` — `List<BanEntry>`, the `ban-mask` flag's dedicated `LIST`-kind storage, FR-062 — `invited` — `Set<String>` of casefolded nicknames, the `invite-only` flag's own dedicated bookkeeping, FR-065, data-model.md `Channel`). Also define the `ChannelMode` value type itself (`id`, `flag`, `kind`, `gates` — which command(s) this flag restricts, e.g. `SEND`/`JOIN`, independent of `kind`, so a future extension-contributed flag can gate a command other than `PRIVMSG`/`NOTICE` without a `Channel`/command-handler shape change, FR-043 — `definedBy`, data-model.md) and the `BanEntry` value type (`mask` — normalized `nick!user@host`, `setBy`, `setAt`, data-model.md `BanEntry`) in the same package in `jircd-core/src/main/java/net/jircd/core/session/Channel.java`
- [X] T032 Implement the channel registry with atomic name uniqueness and create-on-first-join (FR-003); names compared using the same rfc1459 casemapping as `NicknameRegistry` (T029, FR-052) — "#Foo" and "#foo" resolve to one channel, storing whichever casing created it; scope membership lookups behind a single interface so they can later span servers without callers changing (FR-022) in `jircd-core/src/main/java/net/jircd/core/session/ChannelRegistry.java`
- [X] T033 [P] Unit tests for `ChannelRegistry` covering name uniqueness, casemapping cases mirroring `NicknameRegistryTest` (T030, FR-052), and first-join-gets-operator assignment in `jircd-core/src/test/java/net/jircd/core/session/ChannelRegistryTest.java`
- [X] T034 Implement the shared disconnect-cleanup routine (channel membership removal — including removing the departing session from every one of its channels' `Channel.voiced` and `Channel.operators` sets, not just `members`, so a later rejoin starts with neither privilege, data-model.md `Channel` validation rules — `QUIT` notification carrying a caller-supplied reason to every affected channel, FR-017) as the one path every disconnect trigger funnels through — a client-sent `QUIT` (with the client-supplied or default reason), a keep-alive timeout (with its own fixed reason), an `OPER` lockout (T134, its own fixed reason), and an abrupt TCP-level loss (with its own fixed reason) all call this rather than duplicating the cleanup logic per trigger (research.md "Voluntary disconnect and quit reasons", FR-017, FR-060) in `jircd-core/src/main/java/net/jircd/core/session/DisconnectCleanup.java` (depends on T025, T028, T032)

### Networking

- [X] T035 Implement the plaintext connection listener (`ServerSocketChannel` accept loop → one virtual thread per connection, research.md "Networking model") in `jircd-core/src/main/java/net/jircd/core/session/PlaintextListener.java`
- [X] T036 Implement the TLS connection listener (`SSLServerSocket`/`SSLSocket`, research.md "TLS approach" — blocking API matching the virtual-thread model, not `SSLEngine`) in `jircd-core/src/main/java/net/jircd/core/session/TlsListener.java`
- [X] T037 Before creating this connection's `ClientSession` (T025) at all, consult `ExtensionRegistry` (T049) for whether any currently-`ENABLED` extension claims the `connection-admission` extension point (FR-066, research.md "Connection-admission extension point") and, if so, whether it rejects this connection's remote address; a rejection closes the connection immediately with no `ClientSession` ever created — with nothing claiming this point in this release (no such extension exists yet), every connection is admitted unconditionally, the same default-permissive fallback `hostname-display` has when `cloak` is disabled (T092). Past that check, implement the per-connection command dispatch loop (blocking read → parse via `jircd-protocol` → route to a registered handler, matched case-insensitively against the `Command` catalog — `join`/`Join`/`JOIN` all resolve to the same handler, FR-015); a read returning EOF or throwing an `IOException` (abrupt TCP-level connection loss) invokes `DisconnectCleanup` (T034) with a fixed reason (e.g. `"Connection reset by peer"`), the same shared cleanup path a client-sent `QUIT` or a keep-alive timeout uses (FR-017, research.md "Voluntary disconnect and quit reasons") in `jircd-core/src/main/java/net/jircd/core/session/ConnectionHandler.java` (depends on T015, T016, T025, T028, T034, T035, T036, T049)
- [X] T038 Add malformed/incomplete-message handling to `ConnectionHandler` — reject with `421`/`461` (contracts/irc-numeric-replies.md), never crash the connection or affect other clients (FR-015). Include the line-length limit (FR-049): a line exceeding 512 bytes (command+params, CR-LF inclusive) plus up to 4096 additional bytes for a `message-tags` tag section is rejected with `417 ERR_INPUTTOOLONG` — a dedicated error, not `421`/`461` — never truncated or partially processed, in `jircd-core/src/main/java/net/jircd/core/session/ConnectionHandler.java` (extends T037)
- [X] T039 [P] Integration test: two raw-socket clients connect to both the plaintext and TLS listeners as a connectivity smoke test in `jircd-integration-tests/src/test/java/net/jircd/integration/ConnectionSmokeTest.java`

### Connection keep-alive

- [X] T040 Implement the per-connection `LivenessMonitor`, driven by an injectable clock/scheduler rather than real time: sends a server-initiated `PING` after a configured idle interval, and — if no `PONG` arrives within a configured timeout — sends `ERROR` and drives the session's `ConnectionLifecycle` (T028) to `CLOSING`, invoking `DisconnectCleanup` (T034) with a fixed timeout-specific reason (e.g. `"Ping timeout"`), distinct from `QUIT`'s own default (research.md "Voluntary disconnect and quit reasons", FR-039, FR-060, data-model.md `ClientSession.lastLivenessAt`) in `jircd-core/src/main/java/net/jircd/core/session/LivenessMonitor.java` (depends on T025, T028, T034)
- [X] T041 Implement the `PING`/`PONG` command handlers, wired directly into `ConnectionHandler`'s dispatch loop (T037) rather than the post-registration command table (T084) since a client MAY `PING` before registering (contracts/irc-protocol-commands.md "Connection Keep-Alive"): a client-initiated `PING` receives an immediate `PONG` reply on any connection; a client's `PONG` updates `ClientSession.lastLivenessAt`, resetting that connection's `LivenessMonitor` (T040) timer (FR-039) in `jircd-core/src/main/java/net/jircd/core/session/command/PingPongCommandHandler.java` (depends on T037, T040)
- [X] T042 [P] Unit tests for `LivenessMonitor` using an injected fake clock: idle-beyond-interval triggers a `PING`; a `PONG` resets the timer; no `PONG` within the timeout transitions the session to `CLOSING` (FR-039, FR-017) in `jircd-core/src/test/java/net/jircd/core/session/LivenessMonitorTest.java`

### Rate limiting

- [X] T043 [P] Implement the per-connection token bucket rate limiter (research.md "Rate limiting", FR-016) in `jircd-core/src/main/java/net/jircd/core/session/RateLimitBucket.java`
- [X] T044 [P] Unit tests for token bucket refill/exhaustion behavior in `jircd-core/src/test/java/net/jircd/core/session/RateLimitBucketTest.java`

### Extension system (`jircd-core/extension`)

- [X] T045 [P] Define the `Extension` base interface (`start(ServerContext)`, `stop()`, `id`, `state`) in `jircd-core/src/main/java/net/jircd/core/extension/Extension.java`
- [X] T046 [P] Define the `CapabilityExtension` role interface (extends `Extension`; exposes exactly one `Capability`) in `jircd-core/src/main/java/net/jircd/core/extension/CapabilityExtension.java`
- [X] T047 [P] Define the `ServerExtension` role interface (extends `Extension`; no `Capability`; MAY optionally expose `contributedChannelModes` and `contributedUserModes`, both empty for every extension in this release, data-model.md `Extension`) in `jircd-core/src/main/java/net/jircd/core/extension/ServerExtension.java`
- [X] T048 Implement a per-extension `URLClassLoader` with parent-first delegation for `net.jircd.protocol.*` and `net.jircd.core.*` types (research.md "Delegation model") in `jircd-core/src/main/java/net/jircd/core/extension/ExtensionClassLoader.java` (depends on T045-T047)
- [X] T049 Implement `ExtensionRegistry`: `ServiceLoader`-based discovery, `ENABLED`/`DISABLED`/`FAILED` lifecycle, and quiesce-before-unload with a bounded timeout on in-flight calls before releasing a classloader (research.md "Quiesce before unload", FR-020) in `jircd-core/src/main/java/net/jircd/core/extension/ExtensionRegistry.java` (depends on T048)
- [X] T050 Add extension-point ownership enforcement to `ExtensionRegistry` — at most one `ENABLED` extension per `extensionPoint`, rejecting a conflicting enable with a specific error naming both ids (research.md "Extension-point ownership", FR-012) in `jircd-core/src/main/java/net/jircd/core/extension/ExtensionRegistry.java` (extends T049)
- [X] T051 Implement `SupportedFeatures` as a server-scoped value, not something computed per registration (FR-055, data-model.md `SupportedFeatures`): fixed tokens (`CASEMAPPING`, `CHANTYPES`, `UTF8ONLY`) are constants computed once; `CHANMODES`/`PREFIX` are (re)computed from the current `ChannelMode` catalog whenever `ExtensionRegistry`'s `ENABLED`/`DISABLED` state changes for a mode-contributing extension (a no-op recomputation trigger this release, since none exists yet); expose an `updateConfiguredLengths(int nicknameMaxLength, int channelNameMaxLength, int topicMaxLength, int maxModesPerCommand)` update point that recomputes `NICKLEN`/`CHANNELLEN`/`TOPICLEN`/`MODES` — `ExtensionRegistry` itself has no `ServerConfiguration` dependency; this is invoked by the configuration load/reload path (T057, T058) whenever `ServerConfiguration` is (re)loaded (FR-056, FR-064, research.md "Configurable protocol length limits", "MODE command grouping"), never recomputed on a per-`USER`-command basis in `jircd-core/src/main/java/net/jircd/core/extension/ExtensionRegistry.java` (extends T049, T050)
- [X] T052 [P] Unit tests for `ExtensionRegistry`: enable/disable/failed lifecycle, quiesce behavior, and extension-point conflict rejection in `jircd-core/src/test/java/net/jircd/core/extension/ExtensionRegistryTest.java`

### Capability negotiation mechanism (`jircd-core/capability`)

- [X] T053 Implement the CAP negotiation state machine (`LS`/`REQ`/`ACK`/`NAK`/`END`, gating registration completion per FR-006) in `jircd-core/src/main/java/net/jircd/core/capability/CapabilityNegotiator.java` (depends on T021, T045-T049)
- [X] T054 Wire `CapabilityNegotiator` so the offered/accepted capability list is sourced live from `CapabilityExtension` state via `ExtensionRegistry`, never cached (FR-007, FR-035, data-model.md "Capability" live-check) in `jircd-core/src/main/java/net/jircd/core/capability/CapabilityNegotiator.java` (extends T053)
- [X] T055 [P] Unit tests for `CapabilityNegotiator` confirming negotiation succeeds and registration completes even with zero enabled capabilities (FR-035, FR-008) in `jircd-core/src/test/java/net/jircd/core/capability/CapabilityNegotiatorTest.java`

### Server Configuration and manual reload (`jircd-core/config`)

- [X] T056 Define the `ServerConfiguration` aggregate root (`capabilityStates`, `serverExtensionStates`, `listeners`, `rateLimit`, `administratorCredentials`, `serverName` — optional, FR-050 — `serverVersion` — not administrator-configurable, read from the `net/jircd/server/version.properties` classpath resource (T012, research.md "Server identity"), FR-051 — `nicknameMaxLength`/`channelNameMaxLength`/`topicMaxLength` — optional, positive integer, at most 400, defaulting to `9`/`50`/`390` respectively, FR-056 — `whoMaskEnabled` — optional boolean, defaulting to `true`, gates `WHO`'s mask/no-argument forms for non-administrators, FR-061 — `maxModesPerCommand` — optional positive integer, at most 20, defaulting to `6`, the maximum parameter-consuming channel-mode flags applied per `MODE` command, FR-064 — `operFailureThreshold` — optional positive integer, at most 20, defaulting to `5`, the number of consecutive `OPER` credential failures a connection may accrue before being disconnected, FR-034, research.md "OPER failed-attempt lockout" — data-model.md) in `jircd-core/src/main/java/net/jircd/core/config/ServerConfiguration.java`
- [X] T057 Implement the YAML configuration loader and validation — unknown extension id, section/kind mismatch (a `ServerExtension` id under `capabilities` or vice versa), malformed `listeners`/`rateLimit`, a `serverName` containing no `.` (FR-050), a `nicknameMaxLength`/`channelNameMaxLength`/`topicMaxLength` that isn't a positive integer or exceeds 400 (FR-056), a `maxModesPerCommand` that isn't a positive integer or exceeds 20 (FR-064), an `operFailureThreshold` that isn't a positive integer or exceeds 20 (FR-034), and plain-text/unrecognized credential hash format all rejected with a specific, actionable error, leaving any previously-active configuration untouched on failure (contracts/server-configuration.md, FR-012, SC-008); on success, invoke `ExtensionRegistry.updateConfiguredLengths` (T051) with the resolved lengths and `maxModesPerCommand` in `jircd-core/src/main/java/net/jircd/core/config/ConfigurationLoader.java` (depends on T051, T056)
- [X] T058 Implement the core, manually-triggered reload operation — re-run `ConfigurationLoader`, and on success reconcile the result against `ExtensionRegistry` (including the updated `NICKLEN`/`CHANNELLEN`/`TOPICLEN` values, FR-056); on failure, leave the running configuration untouched and surface the same specific error `ConfigurationLoader` produced (research.md "Configuration reload mechanism", contracts/server-configuration.md "Live reload"). This is explicitly **not** automatic file-watching — it only runs when invoked by a trigger (T062, T136) in `jircd-core/src/main/java/net/jircd/core/config/ConfigurationReloader.java` (depends on T049, T057)
- [X] T059 [P] Unit tests for `ConfigurationLoader` validation errors: unknown id, section/kind mismatch, malformed listener, malformed rate-limit, dot-free `serverName`, a non-positive or over-400 `nicknameMaxLength`/`channelNameMaxLength`/`topicMaxLength` (FR-056), a non-positive or over-20 `operFailureThreshold` (FR-034), plain-text credential rejected in `jircd-core/src/test/java/net/jircd/core/config/ConfigurationLoaderTest.java`

### Security-event logging

- [X] T060 [P] Implement a security-event logger wrapper for structured, reviewable log entries (failed authentication, rejected moderation actions, etc., FR-019) in `jircd-core/src/main/java/net/jircd/core/session/SecurityEventLog.java`

### Application entry point

- [X] T061 Implement the `jircd-server` application entry point: load `ServerConfiguration`, start `ExtensionRegistry`, start the plaintext and TLS listeners, refuse to start with a specific error on invalid configuration (FR-012). Resolve `serverName` to the deployment host's network hostname if not configured, appending a fixed synthetic suffix (`.local`) if that hostname itself contains no `.` — the fallback path MUST satisfy FR-050's dot requirement, not only explicit administrator input — and `serverVersion` by reading the `net/jircd/server/version.properties` classpath resource (T012, FR-051, research.md "Server identity"), failing fast with a specific startup error if that resource is missing or unparsable (FR-012) in `jircd-server/src/main/java/net/jircd/server/JircdServerApplication.java` (depends on T035, T036, T049, T057, T012)
- [X] T062 Implement a `SIGHUP` signal handler in the `jircd-server` application entry point that invokes `ConfigurationReloader` — the manual, file-only reload trigger that keeps Story 4 usable without depending on Story 6's optional `admin` extension (research.md "Configuration reload mechanism") in `jircd-server/src/main/java/net/jircd/server/SighupReloadHandler.java` (depends on T058, T061)

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

- [X] T063 [P] [US1] Integration test: two clients register, join `#lobby`, exchange `PRIVMSG`, and the recipient sees the correct `nickname!ident@hostname` sender prefix (FR-004, FR-030) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1ChatTest.java`
- [X] T064 [P] [US1] Integration test: a client completing registration receives the full Registration Completion Burst in order — `001`, `002`, `003`, `004` (with a non-empty `serverName` and a user-mode letter list of exactly `o`, FR-044), `005 RPL_ISUPPORT` containing `CASEMAPPING=rfc1459`, `CHANTYPES=#`, `NICKLEN=9`, `CHANNELLEN=50`, `TOPICLEN=390`, `MODES=6` (default configuration, FR-056, FR-064), `CHANMODES=b,,,imnps`, `PREFIX=(ov)@+`, and `UTF8ONLY` (FR-055), then `422 ERR_NOMOTD` — not just `001` alone (FR-050, FR-051) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1RegistrationBurstTest.java`
- [X] T065 [P] [US1] Integration test: a fully-registered client sends `USER` again (with different argument values than its original) and receives `462 ERR_ALREADYREGISTRED`, with no second Registration Completion Burst sent; a client sends `USER` twice in a row *before* completing registration (before `NICK`/`CAP END`) and the second attempt is also rejected with `462`, not silently accepted (FR-001) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1UserOneShotTest.java`
- [X] T066 [P] [US1] Integration test: two clients attempt to register the same nickname concurrently; exactly one succeeds, the other gets `433 ERR_NICKNAMEINUSE` (FR-002); a client registering "Alice" then a second client attempting "alice"/"ALICE" is also rejected as in-use (FR-052); the `433` (and a preceding `432`/`431` if triggered) is addressed to `*` while the session has no nickname yet (FR-053) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1NicknameRaceTest.java`
- [X] T067 [P] [US1] Integration test: a client sends `QUIT :goodbye` while joined to a channel; remaining members receive a `QUIT` notification carrying `"goodbye"` (FR-017, FR-060); a second client sends bare `QUIT` (no reason) and remaining members receive a `QUIT` notification with a non-blank default reason; a third client's connection is closed abruptly (raw socket close, no `QUIT` sent) and remaining members still receive a `QUIT` notification with a non-blank, server-generated reason — all three trigger the identical cleanup (membership removal), differing only in reason text (research.md "Voluntary disconnect and quit reasons") in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1DisconnectCleanupTest.java`
- [X] T068 [P] [US1] Integration test: a client sends `NICK` only (no `USER` yet, registration incomplete) then `QUIT`; the connection closes cleanly with no error and no crash — `QUIT`'s "any time" precondition includes a still-registering session, which has no channel memberships to clean up (FR-060) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1QuitBeforeRegistrationTest.java`
- [X] T069 [P] [US1] Load-tagged (`@Tag("load")`) test: channel message delivery latency stays within SC-002's 1-second budget under moderate concurrent load in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1DeliveryLatencyLoadTest.java`
- [X] T070 [P] [US1] Integration test: any client can view a channel's topic (`331`/`332`) without being a member; a channel operator sets a new topic and members see the change; a non-operator's attempt is rejected with `482 ERR_CHANOPRIVSNEEDED` (FR-040); an operator's attempt to set a topic exceeding the default `topicMaxLength` (390 characters) is rejected with `417 ERR_INPUTTOOLONG`, leaving the previous topic unchanged (FR-056) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1TopicTest.java`
- [X] T071 [P] [US1] Integration test: `NAMES <channel>` returns the current membership list of a channel the requester has not joined (FR-041) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1NamesTest.java`
- [X] T072 [P] [US1] Integration test: `LIST` returns every currently active channel (FR-042) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1ListTest.java`
- [X] T073 [P] [US1] Integration test: a non-privileged client's `MODE <self>` (no mode string) returns `221 RPL_UMODEIS` with an empty mode list; that client's `MODE <self> +o` is rejected with `481 ERR_NOPRIVILEGES`; `MODE <self> -o` is a silent no-op (not an error) since it isn't set; `MODE <self> +z` (an unrecognized flag) is rejected with `501 ERR_UMODEUNKNOWNFLAG`; `MODE <anotherNickname>` (query or `+o`) is rejected with `502 ERR_USERSDONTMATCH` regardless of target (FR-044) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story1UserModeTest.java`

### Implementation for User Story 1

- [X] T074 [US1] Implement the `NICK` command handler: validate against `Hostmask`'s nickname grammar (T018), passing `ServerConfiguration.nicknameMaxLength` (FR-056) as the length ceiling, before attempting a claim via `NicknameRegistry` — format (`432`) and uniqueness (`433`) are independent, sequential checks; `431` for a missing argument. Any of `431`/`432`/`433` sent before this session has successfully claimed a nickname MUST address the reply to `*`, not an empty or unclaimed value (FR-053) in `jircd-core/src/main/java/net/jircd/core/session/command/NickCommandHandler.java` (depends on T018, T056)
- [X] T075 [US1] Implement the `USER` command handler completing registration: reject with `462 ERR_ALREADYREGISTRED` if `ClientSession.ident` is already set — this session has already processed a `USER` command, whether or not it has reached `REGISTERED` yet (FR-001, data-model.md `ClientSession` validation rules) — checked before anything else, including the UTF-8 validity check below; otherwise send the full Registration Completion Burst (FR-051, contracts/irc-protocol-commands.md "Registration Completion Burst") — `001 RPL_WELCOME`, `002 RPL_YOURHOST`/`004 RPL_MYINFO` (`ServerConfiguration.serverName`/`serverVersion`, FR-050), `003 RPL_CREATED` (process start time), `004`'s channel-mode-letter list from `ExtensionRegistry`'s already-computed `SupportedFeatures` (T051) and its user-mode-letter list from the `UserMode` catalog (T026, FR-044 — `o` this release; a separate source from `SupportedFeatures`, since `RPL_ISUPPORT` advertises no user-mode-equivalent token), and `005 RPL_ISUPPORT` (`CHANMODES`/`PREFIX` included) — never recomputed by this handler itself, split across multiple `005` lines if ever needed to respect FR-049's 512-byte limit (FR-051, FR-055) — then `422 ERR_NOMOTD`; apply `Hostmask`'s username content rule (T018) to derive `ClientSession.ident`, truncating to 9 characters rather than rejecting (contracts/irc-protocol-commands.md "Connection Registration Grammar"); reject with `421`-style malformed-message handling if `<realname>` isn't valid UTF-8 (T018's `Utf8Validator`, FR-054) in `jircd-core/src/main/java/net/jircd/core/session/command/UserCommandHandler.java` (depends on T074, T051, T026)
- [X] T076 [US1] Implement the `JOIN` command handler: validate `channel` against `ChannelName`'s grammar (passing `ServerConfiguration.channelNameMaxLength`, FR-056, as the length ceiling) and UTF-8 validity (T018) before create-or-join via `ChannelRegistry` — `476 ERR_BADCHANMASK` on either failure, checked independently of and before FR-003's uniqueness/create-or-join logic (FR-048, FR-054, contracts/irc-protocol-commands.md "Channel Name Grammar"); `353`/`366` replies, prefixing each member's nickname with `@` if in `operators` or `+` if in `voiced` — neither prefix for a plain member, `@` taking precedence over `+` if both, FR-045, contracts/irc-protocol-commands.md "Channel Operations". Include the `JOIN`-gate check point required by FR-043: reject unless the joiner passes every currently-recognized flag whose `gates` includes `JOIN` (data-model.md `Channel` validation rules), checked independently, not combined into one condition — two flags gate `JOIN` this release: `ban-mask` (FR-062): reject with `474 ERR_BANNEDFROMCHAN` if either the joiner's `UserIdentity.presentedForm` or `nickname!ident@ClientSession.realHostname` matches any entry in `Channel.bans` (`NickMask.matches`, T019, called once per identity) — checking both, not just the presented form, so a mask targeting the joiner's real network identity still applies under an active `cloak`; `invite-only` (FR-065): pass automatically if not currently in `activeModes`, otherwise reject with `473 ERR_INVITEONLYCHAN` unless the joiner's current casefolded nickname is present in `Channel.invited` (T120) — on a pass via this path, remove that entry from `Channel.invited` as part of the same successful join. The check point itself is written generically (iterate recognized `JOIN`-gating flags, not hardcoded to either one) — adding `invite-only` required no change to this handler's shape beyond plugging its predicate into the existing loop, the same way `ban-mask` did, confirming FR-043's extensibility promise a second time in `jircd-core/src/main/java/net/jircd/core/session/command/JoinCommandHandler.java` (depends on T018, T031, T056, T019)
- [X] T077 [US1] Implement the `PART` command handler: `<reason>`, if given, is echoed on the `PART` notification (absent otherwise — no default synthesized, unlike `QUIT`), rejecting an invalid-UTF-8 `<reason>` with `421`-style malformed-message handling (T018's `Utf8Validator`, FR-054); removes the parting session from the channel's `members`, and — the same as `DisconnectCleanup` (T034) does for a full disconnect — also from that channel's `voiced` and `operators` sets if present in either, so a later rejoin starts with neither privilege (data-model.md `Channel` validation rules) in `jircd-core/src/main/java/net/jircd/core/session/command/PartCommandHandler.java`
- [X] T078 [US1] Implement `PRIVMSG`/`NOTICE` command handlers: reject with `421`-style malformed-message handling if the message body isn't valid UTF-8 (T018's `Utf8Validator`, FR-054), before building the recipient set (channel members, or the direct-message target), construct one shared `OutboundMessage` (hostmask-prefixed sender, FR-030) per data-model.md — resolving `senderPresentedForm` by live-checking current `cloak` `ServerExtension` state at that moment, never cached, and never from any recipient's own state (data-model.md `OutboundMessage` validation rules) — and enqueue it onto every recipient's `SessionWriter` queue — this handler MUST NOT itself apply any capability-dependent (`message-tags`/`server-time`) formatting, that happens per-recipient in `SessionWriter` (T027) (FR-004, FR-005) in `jircd-core/src/main/java/net/jircd/core/session/command/MessageCommandHandler.java`
- [X] T079 [US1] Implement the `QUIT` command handler: usable at any time, including before registration completes (FR-060) — reject an invalid-UTF-8 `<reason>` with `421`-style malformed-message handling (T018's `Utf8Validator`, FR-054), then invoke `DisconnectCleanup` (T034) with the client-supplied `<reason>`, or a fixed default (e.g. `"Client Quit"`) if none was given — never a blank reason (FR-060, research.md "Voluntary disconnect and quit reasons") in `jircd-core/src/main/java/net/jircd/core/session/command/QuitCommandHandler.java` (depends on T034)
- [X] T080 [US1] Implement the `TOPIC` command handler: with no trailing argument, applies the `DISCOVER`-gate check required by FR-047 (`403 ERR_NOSUCHCHANNEL` if the channel doesn't exist, or if a currently-active flag in `activeModes` gates `DISCOVER` and the requester is neither a member nor holds `administratorPrivilege` — always the identical response either way, data-model.md `Channel` validation rules; a no-op check in this release until Story 5 defines `private`/`secret`), otherwise returns the channel's current topic (`332`) or `331` if unset; with a trailing argument, rejects with `421`-style malformed-message handling if the new topic isn't valid UTF-8 (T018's `Utf8Validator`, FR-054), rejects with `417 ERR_INPUTTOOLONG` if the new topic exceeds `ServerConfiguration.topicMaxLength` (FR-056, checked independently of and after the UTF-8 check), otherwise sets `Channel.topic` and echoes `TOPIC` to all members, rejecting a non-operator with `482 ERR_CHANOPRIVSNEEDED` (FR-040, data-model.md `Channel.topic`) in `jircd-core/src/main/java/net/jircd/core/session/command/TopicCommandHandler.java` (depends on T056)
- [X] T081 [US1] Implement the `NAMES` command handler, reusing the same `353`/`366` reply logic `JOIN` (T076) already produces — including the `@`/`+` nickname prefixing — and the same `DISCOVER`-gate check `TOPIC` (T080) applies (FR-041, FR-047) in `jircd-core/src/main/java/net/jircd/core/session/command/NamesCommandHandler.java`
- [X] T082 [US1] Implement the `LIST` command handler, iterating `ChannelRegistry`'s currently active channels and silently omitting any the requester fails the `DISCOVER`-gate check for (same check as `TOPIC`/`NAMES`, T080/T081, FR-042/FR-047 — a no-op filter in this release until Story 5 defines `private`/`secret`) in `jircd-core/src/main/java/net/jircd/core/session/command/ListCommandHandler.java`
- [X] T083 [US1] Implement the `MODE` (user) command handler: `502 ERR_USERSDONTMATCH` if `<nickname>` isn't the sender's own current nickname (query or set alike, no exceptions — self-only, FR-044); with no mode string, `221 RPL_UMODEIS` listing `ClientSession.userModes`; otherwise parse the modestring via `ModeStringParser` (T020, FR-064 — the same channel `MODE` uses, reused here even though no user-mode flag consumes a parameter) and apply each `(sign, flag)` left-to-right, stopping at the first failure (a later flag in the same modestring is never attempted after one fails): setting (`+`) a `clientSettable: false` flag (`operator`) is a no-op if the sender already holds it, otherwise `481 ERR_NOPRIVILEGES` and stop — `+o` can only be acquired via `OPER` (T134), never set directly; setting (`+`) a `clientSettable: true` flag (`invisible`) always succeeds immediately, no privilege check; clearing (`-`) any flag the sender holds always succeeds (a no-op if it doesn't) — for `operator` specifically, this also clears `administratorPrivilege` in the same act (data-model.md `ClientSession` validation rules); an unrecognized flag letter is `501 ERR_UMODEUNKNOWNFLAG` and stop; echoes the `MODE` confirmation (reflecting only flags actually applied) to the sender only, never broadcast (FR-044, FR-061, contracts/irc-protocol-commands.md "User Mode") in `jircd-core/src/main/java/net/jircd/core/session/command/UserModeCommandHandler.java` (depends on T025, T026, T020)
- [X] T084 [US1] Register the `NICK`/`USER`/`JOIN`/`PART`/`PRIVMSG`/`NOTICE`/`QUIT`/`TOPIC`/`NAMES`/`LIST` handlers, plus a `MODE` router in `ConnectionHandler`'s command dispatch table: routes to `UserModeCommandHandler` (T083) unless `<target>` matches the channel-name prefix (`CHANTYPES`, `#`, FR-048), in which case it's a `421`-style "not yet supported" rejection until Story 5 extends this same router with the channel branch (T122) — a single dispatch-table entry for `MODE` from the start, not two independently registered ones added later — in `jircd-core/src/main/java/net/jircd/core/session/ConnectionHandler.java` (depends on T074-T082, T083)

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

- [ ] T085 [P] [US2] Integration test: `CAP LS 302` returns exactly `message-tags`, `server-time`, `echo-message` — no `sasl` or other capability (FR-025) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story2CapabilityListTest.java`
- [ ] T086 [P] [US2] Integration test: a connection negotiating `server-time`+`echo-message` receives its own tagged `PRIVMSG` echoed back, while a second, non-negotiating connection in the same channel receives the message untagged (FR-007, FR-008) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story2NegotiatedDeliveryTest.java`
- [ ] T087 [P] [US2] Integration test: a connection negotiating only `message-tags` (not `server-time`) still receives a `msgid` tag on a delivered `PRIVMSG` — `msgid` isn't gated behind `server-time` (FR-059); two separate `PRIVMSG`s in quick succession receive two different `msgid` values; a channel message's `msgid` is identical across every recipient that negotiated `message-tags`, including the sender's own `echo-message`-negotiated copy, letting the sender correlate the echo with what it sent (FR-059, research.md "Message identifiers") in `jircd-integration-tests/src/test/java/net/jircd/integration/Story2MessageIdTest.java`

### Implementation for User Story 2

- [ ] T088 [P] [US2] Implement the `message-tags` `CapabilityExtension`, exposing a tag-decoration hook that `SessionWriter` (T027) calls per-recipient at drain time — it does not itself touch any `OutboundMessage` or queue, it only produces the tag-prefix content when asked, for whichever session currently has it negotiated and enabled. Unconditionally contributes the `msgid` tag from `OutboundMessage.messageId` (FR-059, research.md "Message identifiers") — present whenever this capability is negotiated at all, unlike `server-time`'s `time` tag (T089), which requires that capability to be *separately* negotiated too — in `jircd-capabilities/message-tags/src/main/java/net/jircd/capabilities/messagetags/MessageTagsExtension.java`
- [ ] T089 [P] [US2] Implement the `server-time` `CapabilityExtension`, exposing a hook that produces the `time` tag from `OutboundMessage.sentAt` (data-model.md — the sender's send-time instant, not each recipient's drain time), called the same per-recipient way as `message-tags` (T088) in `jircd-capabilities/server-time/src/main/java/net/jircd/capabilities/servertime/ServerTimeExtension.java`
- [ ] T090 [P] [US2] Implement the `echo-message` `CapabilityExtension`: unlike T088/T089, this affects *recipient-set construction*, not per-recipient formatting — it exposes a hook `MessageCommandHandler` (T078) calls once, when building the recipient list, to decide whether the sender's own session is included in the `OutboundMessage` fan-out in `jircd-capabilities/echo-message/src/main/java/net/jircd/capabilities/echomessage/EchoMessageExtension.java`
- [ ] T091 [P] [US2] Register `message-tags`, `server-time`, and `echo-message` as `ServiceLoader` providers via `META-INF/services/net.jircd.core.extension.CapabilityExtension` in each of `jircd-capabilities/message-tags/src/main/resources/`, `jircd-capabilities/server-time/src/main/resources/`, and `jircd-capabilities/echo-message/src/main/resources/`
- [ ] T092 [US2] Wire `SessionWriter`'s per-recipient drain-time formatting (T027) to actually call the `message-tags`/`server-time` decoration hooks (T088/T089) for each session's currently negotiated-and-enabled capabilities — `CapabilityNegotiator` itself only tracks what a session negotiated (T053/T054); it does not do formatting (depends on T027, T054, T088-T089) in `jircd-core/src/main/java/net/jircd/core/session/SessionWriter.java`

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

- [ ] T093 [P] [US4] Integration test: editing `capabilities.message-tags` to `disabled` and sending the process `SIGHUP` removes it from `CAP LS` and stops tagging already-connected sessions' messages within SC-005's 1-minute budget, with no server restart (SC-005, FR-011) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story4ConfigToggleTest.java`
- [ ] T094 [P] [US4] Integration test: an invalid configuration (`capabilities.nonexistent`, `capabilities.moderation`, `capabilities.cloak` — a section/kind mismatch) at both server startup and via a `SIGHUP`-triggered reload each produce a specific error naming the offending key, and a `SIGHUP` reload rejection leaves the server running on its previous, valid configuration (FR-012, SC-008) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story4InvalidConfigTest.java`
- [ ] T095 [P] [US4] Integration test: starting the server with `nicknameMaxLength: 5`, `channelNameMaxLength: 10`, `topicMaxLength: 20` configured enforces those (not the 9/50/390 defaults) against `NICK`/`JOIN`/`TOPIC`, and `005 RPL_ISUPPORT` advertises `NICKLEN=5`/`CHANNELLEN=10`/`TOPICLEN=20`; a config value of `0`, a non-integer, or `401` (over the 400 ceiling) for any of the three is rejected at startup with a specific error naming the offending key (FR-056, FR-012) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story4LengthLimitConfigTest.java`

### Implementation for User Story 4

- [ ] T096 [US4] Wire `JircdServerApplication` startup to refuse to start and report the specific validation error when `ConfigurationLoader` rejects the configuration (FR-012) in `jircd-server/src/main/java/net/jircd/server/JircdServerApplication.java`

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

- [ ] T097 [P] [US5] Integration test: a channel operator `KICK`s a member; the member is removed and both the target and remaining members are notified (FR-013, FR-014) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5KickTest.java`
- [ ] T098 [P] [US5] Integration test: a non-operator's `KICK` attempt is rejected with `482 ERR_CHANOPRIVSNEEDED`, and the target is not removed (FR-014) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5KickPermissionTest.java`
- [ ] T099 [P] [US5] Integration test: an operator sets moderated mode (`MODE +m`); a non-permitted member's `PRIVMSG` is not delivered and they receive `404 ERR_CANNOTSENDTOCHAN` (FR-013) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5ModeRestrictionTest.java`
- [ ] T100 [P] [US5] Integration test: `KICK` is available immediately after server startup with no configuration step required (FR-036) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5AlwaysAvailableTest.java`
- [ ] T101 [P] [US5] Integration test: an operator grants voice (`MODE +v`) to a non-operator member of a moderated channel; that member's `PRIVMSG` is now delivered, unlike a non-voiced, non-operator member's (FR-045); a non-operator's `MODE +v` attempt is rejected with `482 ERR_CHANOPRIVSNEEDED`; `MODE +v` naming a nickname not currently in the channel is rejected with `441 ERR_USERNOTINCHANNEL`; the voiced member then `PART`s and rejoins the channel — their `PRIVMSG` is no longer delivered (voice was not retained across the rejoin) until an operator grants `+v` again in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5VoiceTest.java`
- [ ] T102 [P] [US5] Integration test: `353 RPL_NAMREPLY` (via `JOIN` or `NAMES`) prefixes an operator's nickname with `@`, a voiced non-operator's with `+`, and a plain member's with neither (FR-045, contracts/irc-protocol-commands.md "Channel Operations") in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5NamesPrefixTest.java`
- [ ] T103 [P] [US5] Integration test: an operator grants operator status (`MODE +o`) to a non-operator member; that member can now perform a moderation action (e.g., `KICK`), the same as the original operator (FR-046); a non-operator's `MODE +o` attempt is rejected with `482 ERR_CHANOPRIVSNEEDED`; `MODE +o` naming a nickname not currently in the channel is rejected with `441 ERR_USERNOTINCHANNEL`; an operator revoking their own status (`MODE -o` on themselves) succeeds even if it leaves the channel with zero operators; the newly-granted operator then `PART`s and rejoins the channel — a subsequent moderation action (e.g. `KICK`) from them is rejected with `482 ERR_CHANOPRIVSNEEDED` (operator status was not retained across the rejoin) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5OperatorGrantTest.java`
- [ ] T104 [P] [US5] Integration test: an operator sets `MODE +s` on their channel; a non-member's `TOPIC`/`NAMES` on it receives `403 ERR_NOSUCHCHANNEL`, indistinguishable from a genuinely nonexistent channel, and it's omitted from a non-member's `LIST`; a current member sees it normally in all three; a privileged (administrator) session sees it normally despite not being a member (FR-047); setting `MODE +p` then `+s` on the same channel clears `+p` (mutual exclusion, data-model.md `Channel` validation rules) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5PrivacyTest.java`
- [ ] T105 [P] [US5] Integration test: an operator adds a ban mask (`MODE +b`) matching a member currently in the channel; that member's subsequent `PRIVMSG` to the channel is rejected with `404 ERR_CANNOTSENDTOCHAN`, and they remain a channel member (`NAMES` still lists them); a partial mask (bare nickname) is normalized to `<nick>!*@*` before matching, still muting the correct member; a non-operator's `MODE +b` attempt is rejected with `482 ERR_CHANOPRIVSNEEDED` (FR-062) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5BanMuteTest.java`
- [ ] T106 [P] [US5] Integration test: an operator adds a ban mask matching a client not currently in the channel; that client's `JOIN` attempt is rejected with `474 ERR_BANNEDFROMCHAN`; the operator then removes the ban (`MODE -b`) and the same client's `JOIN` now succeeds; a privileged administrator's `SAJOIN` on the same still-banned channel bypasses the ban entirely (FR-057, FR-062) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5BanJoinTest.java`
- [ ] T107 [P] [US5] Integration test: with the `cloak` extension enabled (presenting a member's channel-visible hostmask as something other than their real value), an operator adds a ban mask matching that member's *real*, uncloaked hostname/IP (not the cloaked one visible in their message prefixes); the member is muted (`404` on `PRIVMSG`) despite the mask not matching their publicly-visible presented hostmask — dual-matching (presented and real) makes the ban resistant to `cloak` evasion; a mask matching only the presented form still works as before, unaffected by the addition of real-hostname matching (FR-062, FR-031) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5BanRealHostnameTest.java`
- [ ] T108 [P] [US5] Integration test: an operator adds two ban masks, then queries `MODE <channel> b`; both are returned as `367 RPL_BANLIST` (with `setBy`/`setAt` populated), then `368 RPL_ENDOFBANLIST`; removing a mask not currently present is a silent no-op (no error, list unchanged); adding a mask already present is also a silent no-op (list doesn't grow); a channel with zero bans still closes the query with bare `368` (FR-062) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5BanListTest.java`
- [ ] T109 [P] [US5] Integration test: an operator adds 100 distinct ban masks successfully, then a 101st attempt is rejected with `478 ERR_BANLISTFULL`, leaving the existing 100 untouched; removing one of the 100 and retrying the addition then succeeds (FR-062) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5BanCapTest.java`
- [ ] T110 [P] [US5] Integration test: an operator sends `MODE #chan +ov-b nick1 nick2 mask1` in one command (two members already present, one ban mask already active); the reply/echo shows all three applied — `nick1` gains operator, `nick2` gains voice, `mask1` is removed from the ban list — confirmed via `NAMES` prefixes and `MODE #chan b`; a command combining pure `BOOLEAN` flags (`MODE #chan +mn`) sets both `moderated` and `members-only` in one invocation; a command where a later flag fails (`MODE #chan +ov nick1 not-a-member`) still applies the earlier flag (`nick1` gains operator) before stopping at `441 ERR_USERNOTINCHANNEL` — not atomic, not rolled back (FR-064) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5ModeGroupingTest.java`
- [ ] T111 [P] [US5] Integration test: starting the server with `maxModesPerCommand: 2`, an operator sends `MODE #chan +bbb mask1 mask2 mask3` (three bans, three params, one channel); only the first two are applied (confirmed via `MODE #chan b` showing exactly two entries) — no error reply for the third, the `MODE` echo simply reflects only the first two; a command within the limit (`+bb mask4 mask5`) applies both normally (FR-064) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5ModeCapConfigTest.java`
- [ ] T112 [P] [US5] Integration test: an operator sets `MODE #chan +i`; a client with no invitation is rejected with `473 ERR_INVITEONLYCHAN` on `JOIN`; the operator sends `INVITE <nick> #chan`, receiving `341 RPL_INVITING <nick> #chan`, and the target receives an `INVITE` message from the operator; the target's subsequent `JOIN` succeeds, and a second `JOIN` after `PART`ing is rejected again with `473` (the invitation was consumed by the first join); a non-operator member's `INVITE` attempt while `+i` is active is rejected with `482 ERR_CHANOPRIVSNEEDED`; the same member's `INVITE` succeeds once the operator clears `+i` (`MODE #chan -i`); `INVITE` naming a nickname not currently connected is rejected with `401 ERR_NOSUCHNICK`; `INVITE` naming a nickname already a channel member is rejected with `443 ERR_USERONCHANNEL`; `INVITE` from a sender who isn't a member of the target channel is rejected with `442 ERR_NOTONCHANNEL`; a target holding a valid invitation but also matching an active ban still has `JOIN` rejected with `474 ERR_BANNEDFROMCHAN` — an invitation bypasses `invite-only` only, never a ban (FR-062, FR-065) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story5InviteTest.java`

### Implementation for User Story 5

- [ ] T113 [US5] Implement the `KICK` command handler (operator-only, removes target, notifies the channel, `482` on unauthorized, logs rejected attempts via `SecurityEventLog`, FR-013/FR-014/FR-019) in `jircd-core/src/main/java/net/jircd/core/session/command/KickCommandHandler.java`
- [ ] T114 [US5] Implement the `MODE` command handler's general processing loop (FR-064): after the one, whole-command `482` operator-privilege check (not per-flag), parse the modestring via `ModeStringParser` (T020) into an ordered `List<ModeChange>`, then iterate left-to-right applying each: resolve `flag` against the currently-recognized `ChannelMode` catalog — `472 ERR_UNKNOWNMODE` and STOP if undefined (or on a bare mode query with no flag argument, FR-043); if `kind` needs a parameter (`MEMBER`/`LIST`, added in T116-T118) and this command has already applied `ServerConfiguration.maxModesPerCommand` (default `6`) parameter-consuming changes, STOP silently, no reply beyond the `MODE` echo of what was applied so far; if a needed parameter isn't available in the command's remaining parameter list, `461 ERR_NEEDMOREPARAMS` and STOP. This release's first flags plugged into this loop: `+m`/`-m` (`moderated`) and `+n`/`-n` (`members-only`), independently, both `BOOLEAN`-kind so never parameter-consuming (FR-013, contracts/irc-protocol-commands.md "Full Channel Mode Catalog"); echoes only the flags actually applied, never the originally-requested set; logs rejected attempts via `SecurityEventLog` in `jircd-core/src/main/java/net/jircd/core/session/command/ModeCommandHandler.java` (depends on T020, T056)
- [ ] T115 [US5] Extend the `MODE` command handler with `+p`/`-p` (`private`) and `+s`/`-s` (`secret`) (FR-047): set/clear independently in `Channel.activeModes` like `+m`/`+n` (T114), except setting one MUST clear the other if active (mutual exclusion, data-model.md `Channel` validation rules); `482` on unauthorized; logs rejected attempts via `SecurityEventLog` in `jircd-core/src/main/java/net/jircd/core/session/command/ModeCommandHandler.java` (depends on T114)
- [ ] T116 [US5] Extend the `MODE` command handler with `+v`/`-v <nickname>` (FR-045), `MEMBER`-kind: each occurrence of `v` in the modestring consumes the next not-yet-used parameter in the command's parameter list (T114's loop — not assumed to be the command's only parameter, since other flags earlier or later in the same modestring may consume others), resolved to a current member of the channel — `441 ERR_USERNOTINCHANNEL` and stop (T114) if not — then add/remove it from `Channel.voiced` (not `activeModes`, since `voice` is `MEMBER`-kind, data-model.md `ChannelMode`); logs rejected attempts via `SecurityEventLog` in `jircd-core/src/main/java/net/jircd/core/session/command/ModeCommandHandler.java` (depends on T114)
- [ ] T117 [US5] Extend the `MODE` command handler with `+o`/`-o <nickname>` (FR-046), the same shape as `+v`/`-v` (T116) — each occurrence consumes the next unused parameter in order, `441` and stop if the resolved nickname isn't a current member: add/remove it from `Channel.operators` (not `activeModes`); a sender MAY target themselves, including a self-revocation that leaves the channel with zero operators (data-model.md `Channel` validation rules); logs rejected attempts via `SecurityEventLog` in `jircd-core/src/main/java/net/jircd/core/session/command/ModeCommandHandler.java` (depends on T114)
- [ ] T118 [US5] Extend the `MODE` command handler with `b` (FR-062), `LIST`-kind: a bare query (`MODE <channel> b`, no `REGISTERED`-beyond precondition, no operator requirement, and not itself subject to T114's parameter-consuming cap since it consumes no parameter) replies one `367 RPL_BANLIST` per entry in `Channel.bans` (`<mask>`, `<setBy>`, `<setAt>`), then `368 RPL_ENDOFBANLIST`, even if empty; each `+b`/`-b` occurrence in a modestring consumes the next unused parameter in order as `<mask>` (T114's loop, subject to its cap and stop conditions); `+b` normalizes `<mask>` via `NickMask.normalize` (T019) and adds a new `BanEntry` (`setBy`: sender's nickname, `setAt`: now) to `Channel.bans` — no-op if the normalized mask is already present, `478 ERR_BANLISTFULL` and stop if `Channel.bans` already has 100 entries; `-b` normalizes and removes the matching entry — no-op if not present; logs rejected attempts via `SecurityEventLog` in `jircd-core/src/main/java/net/jircd/core/session/command/ModeCommandHandler.java` (depends on T031, T114, T019)
- [ ] T119 [US5] Enforce the `SEND`-gate check point required by FR-043 in the `PRIVMSG`/`NOTICE` path: reject unless the sender passes every currently-recognized flag whose `gates` includes `SEND`, checked independently per flag, not filtered to `activeModes` membership first (data-model.md `Channel` validation rules — "`LIST`-kind flags in practice") — `MEMBERS_ONLY` requires membership; `MODERATED` requires the sender to be in `operators` **or** `voiced` (FR-045, matching classic IRC's full `+m` semantic), both rejected with `404 ERR_CANNOTSENDTOCHAN`; `ban-mask` (FR-062) requires neither the sender's `UserIdentity.presentedForm` nor `nickname!ident@ClientSession.realHostname` to match any entry in `Channel.bans` (`NickMask.matches`, T019, called once per identity — dual-matching keeps a ban resistant to `cloak` evasion), also rejected with `404` — muting a matched sender without removing them from `members` (depends on T078, T116, T118, T019) in `jircd-core/src/main/java/net/jircd/core/session/command/MessageCommandHandler.java`
- [ ] T120 [US5] Extend the `MODE` command handler with `+i`/`-i` (`invite-only`, FR-065), `BOOLEAN`-kind: set/clear independently in `Channel.activeModes` like `+m`/`+n`/`+p`/`+s` (T114/T115), never parameter-consuming; `482` on unauthorized; logs rejected attempts via `SecurityEventLog` in `jircd-core/src/main/java/net/jircd/core/session/command/ModeCommandHandler.java` (depends on T114)
- [ ] T121 [US5] Implement the `INVITE` command handler (FR-065): `442 ERR_NOTONCHANNEL` if the sender isn't currently a member of `<channel>` (also covers `<channel>` not existing); `401 ERR_NOSUCHNICK` if `<nickname>` isn't currently connected (`NicknameRegistry`, T029); `443 ERR_USERONCHANNEL` if `<nickname>` is already a member of `<channel>`; `482 ERR_CHANOPRIVSNEEDED` if `invite-only` is currently in `Channel.activeModes` (T120) and the sender isn't in `Channel.operators`; otherwise add `<nickname>`'s casefolded form to `Channel.invited` (T031) and reply `341 RPL_INVITING <nickname> <channel>` to the sender, then relay a standard `INVITE` message from the sender to `<nickname>`'s session directly (not a channel broadcast — the target need not be a member yet); logs rejected attempts via `SecurityEventLog` in `jircd-core/src/main/java/net/jircd/core/session/command/InviteCommandHandler.java` (depends on T029, T031, T120)
- [ ] T122 [US5] Register the `KICK` and `INVITE` handlers, and extend the `MODE` router T084 already registered with its channel branch (`<target>` matching the channel-name prefix, `CHANTYPES`, `#`, FR-048, now delegates to `ModeCommandHandler` — T113-T117, T118, T120 — instead of T084's placeholder rejection); the user branch (`UserModeCommandHandler`, T083) is unchanged — in `ConnectionHandler`'s command dispatch table (depends on T084, T113, T114, T115, T116, T117, T118, T120, T121) in `jircd-core/src/main/java/net/jircd/core/session/ConnectionHandler.java`

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

- [ ] T123 [P] [US6] Integration test: a non-privileged session's `EXTENSION` command is rejected with `481 ERR_NOPRIVILEGES` (FR-033) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story6PrivilegeGateTest.java`
- [ ] T124 [P] [US6] Integration test: `OPER` with valid credentials returns `381 RPL_YOUREOPER`; with invalid credentials returns `464 ERR_PASSWDMISMATCH` and the failure is logged as a security event (FR-019, FR-034); on a connection starting the server with `operFailureThreshold: 3`, three consecutive invalid `OPER` attempts each return `464` and the connection remains open after the first two, but the third also closes the connection (FR-034, research.md "OPER failed-attempt lockout"); a connection that fails `OPER` twice then succeeds on a third, correct attempt is not disconnected, and a subsequent invalid attempt on that same connection starts counting from `1` again (not `3`), confirming the counter resets on success in `jircd-integration-tests/src/test/java/net/jircd/integration/Story6OperTest.java`
- [ ] T125 [P] [US6] Integration test: after a successful `OPER`, that session's own `MODE <self>` shows `221 RPL_UMODEIS :+o`, and a *different* client's `WHOIS` of that nickname includes `313 RPL_WHOISOPERATOR` (visible to any client, not just the operator or an administrator, FR-037/FR-044); the now-privileged session then sends `MODE <self> -o`, after which its own subsequent admin command (e.g. `WHOHOST`) is rejected with `481 ERR_NOPRIVILEGES` and a fresh `WHOIS` of it by another client no longer shows `313` — self-revocation actually revokes the privilege, not just the display flag (FR-044) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story6OperUserModeTest.java`
- [ ] T126 [P] [US6] Integration test: a privileged session's `EXTENSION DISABLE message-tags` has the same observable effect as Story 4's config-file path, with no config file edit involved, within SC-009's budget (path equivalence, contracts/server-configuration.md) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story6ExtensionToggleTest.java`
- [ ] T127 [P] [US6] Integration test: `EXTENSION DISABLE moderation` and `EXTENSION DISABLE capability-negotiation` are both rejected as unknown/non-toggleable, not silently accepted (FR-035, FR-036) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story6NonToggleableRejectionTest.java`
- [ ] T128 [P] [US6] Integration test: `WHOHOST` returns a target's real hostname to a privileged admin even while the `cloak` extension obscures it for other clients (FR-031, FR-032) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story6WhohostTest.java`
- [ ] T129 [P] [US6] Integration test: `EXTENSION DISABLE admin`, issued by a privileged session, succeeds; that session's subsequent admin commands are then rejected with `481`, while the configuration-file/`SIGHUP` path still works (contracts/irc-protocol-commands.md "Self-lockout") in `jircd-integration-tests/src/test/java/net/jircd/integration/Story6SelfLockoutTest.java`
- [ ] T130 [P] [US6] Integration test: a privileged session edits nothing but sends `REHASH` after the config file's `rateLimit.bucketSize` was changed on disk; the new value takes effect and `382 RPL_REHASHING` is returned — the in-band equivalent of Story 4's `SIGHUP` path (research.md "Configuration reload mechanism"); a `REHASH` against an invalid file returns the specific validation error directly to the session and leaves the running configuration untouched in `jircd-integration-tests/src/test/java/net/jircd/integration/Story6RehashTest.java`
- [ ] T131 [P] [US6] Integration test: a privileged session issues `SAJOIN` on a channel and joins it, receiving the same `JOIN`/`353`/`366` replies ordinary `JOIN` would; a non-privileged session's `SAJOIN` is rejected with `481 ERR_NOPRIVILEGES`; `SAJOIN` naming a malformed channel is rejected with `476 ERR_BADCHANMASK`, the same as ordinary `JOIN` — the grammar check is NOT bypassed (FR-057) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story6SajoinTest.java`
- [ ] T132 [P] [US6] Integration test: a privileged session that has already joined a channel with existing members and an existing operator issues `SAMODE <channel> +o` and is granted operator status immediately, with no involvement from the existing operator; a non-privileged session's `SAMODE` is rejected with `481 ERR_NOPRIVILEGES`; a privileged session's `SAMODE` on a channel it hasn't joined is rejected with `442 ERR_NOTONCHANNEL` (FR-058) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story6SamodeTest.java`

### Implementation for User Story 6

- [ ] T133 [US6] Implement administrator-credential verification against `ServerConfiguration.administratorCredentials` using the password-hashing library (research.md "Administrator credential storage", FR-034) in `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/AdminCredentialVerifier.java`
- [ ] T134 [US6] Implement the `OPER` command handler (grants `ClientSession.administratorPrivilege` on success, and adds `operator` to `ClientSession.userModes` in the same act, FR-044; `381`/`464`; logs failures via `SecurityEventLog`). On success, reset `ClientSession.failedOperAttempts` to `0`. On failure, increment `failedOperAttempts`, send `464`, log via `SecurityEventLog` as usual, and — if the incremented count has now reached `ServerConfiguration.operFailureThreshold` (default `5`) — additionally invoke `DisconnectCleanup` (T034) with a fixed reason (e.g. `"Too many failed OPER attempts"`), the same shared cleanup path every other disconnect trigger uses (FR-034, FR-017, research.md "OPER failed-attempt lockout") in `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/OperCommandHandler.java` (depends on T133, T026, T034, T056)
- [ ] T135 [US6] Implement the `EXTENSION` command handler (`ENABLE`/`DISABLE` via `ExtensionRegistry`; `481` if unprivileged; specific error for unknown or non-toggleable ids) in `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/ExtensionCommandHandler.java`
- [ ] T136 [US6] Implement the `REHASH` command handler — admin-privilege gated, invokes `ConfigurationReloader`; `382 RPL_REHASHING` on success, the reloader's specific validation error on failure, `481` if unprivileged (research.md "Configuration reload mechanism", FR-012) in `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/RehashCommandHandler.java` (depends on T058)
- [ ] T137 [US6] Implement the `WHOHOST` command handler (reads `ClientSession.realHostname` directly, bypassing any cloak extension) in `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/WhohostCommandHandler.java`
- [ ] T138 [US6] Implement the `SAJOIN` command handler — admin-privilege gated (`481 ERR_NOPRIVILEGES` if unprivileged); joins the sender via the same create-or-join path `JoinCommandHandler`/`ChannelRegistry` use (FR-003, FR-013) but skips the `JOIN`-gate check point (FR-043's `gates` mechanism) entirely; channel-name grammar and UTF-8 validity (T018) are NOT skipped, so `476 ERR_BADCHANMASK` still applies (FR-057, research.md "Administrator channel override") in `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/SajoinCommandHandler.java` (depends on T076)
- [ ] T139 [US6] Implement the `SAMODE` command handler — admin-privilege gated (`481 ERR_NOPRIVILEGES` if unprivileged); requires the sender to currently be a member of the target channel (`442 ERR_NOTONCHANNEL` if not); adds/removes the sender's own nickname from `Channel.operators` via the same mechanism `ModeCommandHandler`'s `+o`/`-o` uses (T117), bypassing FR-046's "sender must already be an operator" precondition — self-targeting only, accepts no target parameter (FR-058, research.md "Administrator channel override") in `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/SamodeCommandHandler.java` (depends on T117)
- [ ] T140 [US6] Implement the `admin` `ServerExtension` wiring, registering the `OPER`/`EXTENSION`/`REHASH`/`WHOHOST`/`SAJOIN`/`SAMODE` handlers with `jircd-core`'s command dispatch when enabled (depends on T134-T137, T138, T139) in `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/AdminExtension.java`
- [ ] T141 [US6] Implement the `cloak` `ServerExtension`, claiming the `hostname-display` extension point (research.md "Cloak extension boundary", FR-031) in `jircd-server-extensions/cloak/src/main/java/net/jircd/serverextensions/cloak/CloakExtension.java`
- [ ] T142 [P] [US6] Register `admin` and `cloak` as `ServiceLoader` providers via `META-INF/services/net.jircd.core.extension.ServerExtension` in `jircd-server-extensions/admin/src/main/resources/` and `jircd-server-extensions/cloak/src/main/resources/`

**Checkpoint**: All five mandatory user stories (1, 2, 4, 5, 6) work
independently.

---

## Phase 8: User Story 7 - Look Up Information About a User (Priority: P2)

**Goal**: A registered client can look up nickname/ident/hostname/real-name
information about themselves or another connected user (`WHOIS`), and
can search for users by channel, exact nickname, or wildcard mask
(`WHO`) — with the returned hostname/IP following FR-038's three-tier
visibility rule in both, and `WHO`'s search forms respecting `invisible`
(FR-044/FR-061).

**Independent Test**: Look up your own information and confirm it's
returned correctly; as a different, non-administrator client, look up
that same user's information and confirm you receive their presented
(not real) hostname; search for a channel's members via `WHO` and
confirm it matches `NAMES` exactly (quickstart.md Story 7).

### Tests for User Story 7

> **Write these tests FIRST, ensure they FAIL before implementation.**

- [ ] T143 [P] [US7] Integration test: a client performs a self-lookup (no target) and receives its own real hostname/IP, even while the `cloak` extension is currently obscuring it from other clients (FR-038 case 1) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story7SelfLookupTest.java`
- [ ] T144 [P] [US7] Integration test: a privileged session looks up a *different* connected client and receives that client's real hostname/IP, consistent with `WHOHOST` (FR-038 case 2) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story7AdminLookupTest.java`
- [ ] T145 [P] [US7] Integration test: a non-privileged session looks up a *different* connected client and receives only that client's presented hostname (the same value its message hostmask shows) — never the real value, whether or not `cloak` is enabled (FR-038 case 3, SC-010) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story7RegularLookupTest.java`
- [ ] T146 [P] [US7] Integration test: a lookup for a nickname that isn't connected returns `401 ERR_NOSUCHNICK`, no user data (FR-037) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story7NoSuchNickTest.java`
- [ ] T147 [P] [US7] Integration test: `WHO #channel` from a non-member returns the identical membership `NAMES #channel` would (one `352 RPL_WHOREPLY` per member, including any `invisible` member, then `315 RPL_ENDOFWHO`) — invisibility never filters this form (FR-061); `WHO` on a private/secret channel from a non-member/non-administrator returns `403 ERR_NOSUCHCHANNEL`, the identical response `TOPIC`/`NAMES` give (FR-047) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story7WhoChannelTest.java`
- [ ] T148 [P] [US7] Integration test: a requester sharing no channel with an `invisible`-set client gets zero matches for `WHO <thatNickname>` (exact) and `WHO <prefix>*` (mask) — both close with bare `315 RPL_ENDOFWHO`, not an error; the same requester, after joining a channel the invisible client is also in, repeats both queries and now gets a `352` match; separately, an administrator gets a match without sharing any channel; a bare `WHO` (no argument) similarly excludes the invisible client for a non-sharing, non-privileged requester (FR-044, FR-061) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story7WhoInvisibleTest.java`
- [ ] T149 [P] [US7] Integration test: starting the server with `whoMaskEnabled: false` makes a non-privileged client's `WHO <prefix>*` (mask) and bare `WHO` (no argument) both return zero matches — bare `315`, not an error — even for a target that isn't `invisible` and shares no relevant restriction otherwise; the identical requester's `WHO <exactNickname>` and `WHO #channel` still work normally, unaffected by the setting; a privileged (administrator) session's mask/no-argument `WHO` still returns real matches despite the setting (FR-061) in `jircd-integration-tests/src/test/java/net/jircd/integration/Story7WhoMaskConfigTest.java`

### Implementation for User Story 7

- [ ] T150 [US7] Implement the `WHOIS` command handler: resolve the target session (the sender's own if no argument given), apply FR-038's real-vs-presented resolution by reusing `UserIdentity.presentedForm`'s existing computation and `ClientSession.realHostname` (never a new, independent resolution — research.md "Cloak extension boundary"), reply `311 RPL_WHOISUSER`, then `313 RPL_WHOISOPERATOR` if the target's `userModes` currently contains `operator` — visible to any querying client, unlike the hostname resolution above (FR-037, FR-044) — then `318 RPL_ENDOFWHOIS`, or `401 ERR_NOSUCHNICK` if the target isn't connected. Core protocol behavior (FR-037), never an optional extension in `jircd-core/src/main/java/net/jircd/core/session/command/WhoisCommandHandler.java` (depends on T026)
- [ ] T151 [US7] Implement the `WHO` command handler: dispatch on `[mask]`'s shape (FR-061) — a channel name (`ChannelName`'s grammar, T018) applies the identical `DISCOVER`-gate check `NamesCommandHandler` (T081) already applies and lists exactly that method's membership set, never filtering by `invisible` or `whoMaskEnabled`; a `*`/`?`-containing argument matches nicknames case-insensitively (rfc1459 casemapping, FR-052) via wildcard; anything else is an exact-nickname match; omitted matches every connected session. For the latter two forms and the no-argument form: if the requester lacks `administratorPrivilege` and `ServerConfiguration.whoMaskEnabled` is `false`, short-circuit to zero matches immediately (checked before anything else, FR-061); otherwise exclude a matched session whose `userModes` contains `invisible` unless the requester's `channelMemberships` intersects that session's, or the requester's `administratorPrivilege` is `true` (data-model.md `UserMode` validation rules — an administrator requester bypasses both checks). Reply one `352 RPL_WHOREPLY` per surviving match — `<channel-or-*> <ident> <presentedHostname> <nickname> H[*][@|+] :0 <realname>`, reusing FR-038's hostname resolution (T150's approach) and, for the channel form, the `@`/`+` precedence `NamesCommandHandler` already computes — then `315 RPL_ENDOFWHO` always, even for zero matches (not an error, and not distinguishable from a `whoMaskEnabled`/`invisible` exclusion) in `jircd-core/src/main/java/net/jircd/core/session/command/WhoCommandHandler.java` (depends on T018, T056, T081, T150)
- [ ] T152 [US7] Register the `WHOIS`/`WHO` handlers in `ConnectionHandler`'s command dispatch table (depends on T084, T150, T151) in `jircd-core/src/main/java/net/jircd/core/session/ConnectionHandler.java`

**Checkpoint**: All six mandatory user stories (1, 2, 4, 5, 6, 7) work
independently.

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Whole-system validation and documentation that spans
multiple stories.

- [ ] T153 [P] Load-tagged (`@Tag("load")`) test: the server sustains 1,000 simultaneous connections without channel message delivery delay exceeding SC-002's target (SC-003) in `jircd-integration-tests/src/test/java/net/jircd/integration/ConcurrentConnectionScaleLoadTest.java`
- [ ] T154 [P] Load-tagged (`@Tag("load")`) test: during a sustained flood from one connection, delivery latency for other well-behaved clients does not increase beyond SC-002's target (SC-006) in `jircd-integration-tests/src/test/java/net/jircd/integration/RateLimitLoadTest.java`
- [ ] T155 [P] Update `README.md`'s "Getting started" section with real build/run instructions now that the project builds (`./gradlew build`, `./gradlew :jircd-server:run`), replacing the pre-implementation placeholder
- [ ] T156 Run the full `specs/001-ircv3-server/quickstart.md` validation pass manually against a running `./gradlew :jircd-server:run` instance, covering Stories 1, 2, 4, 5, 6, and 7 end-to-end (constitution UX Consistency principle's required manual usage-scenario check)
- [ ] T157 [P] Code cleanup pass: remove any dead code/TODOs introduced during implementation and confirm `./gradlew build` runs Spotless and SpotBugs clean across all subprojects (constitution Code Quality principle)

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
  Foundational (T058, T062), so Story 4's own phase is small.
- **User Story 5 (P5)**: No dependencies on other stories; builds on the
  `Channel` aggregate from Foundational.
- **User Story 6 (P4)**: Its core administration commands (`OPER`,
  `EXTENSION`, `WHOHOST`, `REHASH`) have no dependencies on other
  stories, and are most demonstrable once Story 2 exists — `REHASH`
  (T136) depends only on Foundational's `ConfigurationReloader` (T058),
  not on Story 4. `SAJOIN`/`SAMODE` (FR-057/FR-058) are the exception:
  `SAJOIN` reuses Story 1's `JoinCommandHandler`/`ChannelRegistry` path
  (T076), and `SAMODE` reuses Story 5's `MODE +o`/`-o` handling (T117)
  — both are real, not incidental, dependencies (each command bypasses a
  specific precondition the reused path already enforces, so the path
  has to exist first), so those two tasks specifically cannot start
  before Stories 1 and 5 respectively, even though the rest of Story 6
  can.
- **User Story 7 (P2)**: No dependencies on other stories to compile or
  run — the self-lookup case (T143) needs nothing beyond Foundational.
  Its administrator-lookup test (T144) is most meaningful once Story 6's
  `OPER` exists, and its cloak-interaction assertions (T143/T145) are most
  meaningful once Story 6's `cloak` extension exists, but the `WHOIS`
  handler itself (T150) only depends on `ClientSession.realHostname` and
  `UserIdentity.presentedForm`, both Foundational — it does not call into
  `WHOHOST` or any Story 6 code.

### Within Each User Story

- Tests MUST be written and FAIL before implementation.
- Command handlers before dispatch-table registration.
- Story complete (checkpoint) before moving to the next priority, if
  working sequentially.

### Parallel Opportunities

- All Setup tasks marked `[P]` can run in parallel.
- Within Foundational, the protocol-layer tasks (T016-T024), the
  `Extension`/`CapabilityExtension`/`ServerExtension` interface
  definitions (T045-T047), and the two independent test-writing tasks in
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

Watch for the one known cross-story file touch: Story 5's T119 extends
`MessageCommandHandler.java`, which Story 1's T078 creates — sequence
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
  T058/T062/T136) — there is deliberately no automatic file-watching task;
  see research.md "Configuration reload mechanism" for why.
- Verify each story's tests fail before implementing that story.
- Commit after each task or logical group.
- Stop at any checkpoint to validate a story independently before
  continuing.
- The two `[P]`-marked capability/server-extension `ServiceLoader`
  registration tasks (T091, T142) are each one task covering multiple
  small resource files (one per extension in that group) — split further
  only if working across them in true parallel by different people.
