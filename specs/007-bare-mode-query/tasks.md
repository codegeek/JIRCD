---

description: "Task list for the Bare Channel Mode Query feature"
---

# Tasks: Bare Channel Mode Query

**Input**: Design documents from `/specs/007-bare-mode-query/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, quickstart.md (all present)

**Tests**: Included. The project constitution's Testing Standards principle mandates automated
coverage for every feature's primary behavior. This feature additionally re-runs the specific
irctest test this item corresponds to (SC-003), the same verification precedent
`003`/`005`/`006` established.

**Organization**: Tasks are grouped by user story (spec.md Stories 1-3, priority order P1→P3).
All three stories edit the same method in the same file
(`ModeCommandHandler.sendChannelModeIs`), built up incrementally — see Dependencies below.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1-US3)
- File paths are relative to the repository root.

## Path Conventions

No new module — every path is an existing file from `001-ircv3-server`'s own module layout
(`jircd-protocol`, `jircd-core/session`, `jircd-core/session/command`), plus
`jircd-integration-tests` (protocol-level tests).

---

## Phase 1: Setup

Not applicable — no new module, no new build dependency, no new tooling.

## Phase 2: Foundational

Not applicable — no single blocking prerequisite spans all three stories; they build
incrementally on the same one method in the same file (see Dependencies).

---

## Phase 3: User Story 1 - A Member Sees a Channel's Current Mode Settings at a Glance (Priority: P1) 🎯 MVP

**Goal**: A bare `MODE #channel` query returns the channel's active mode string instead of the
generic unknown-mode error.

**Independent Test**: Set a mix of boolean and value-carrying modes on a channel, query with no
flag argument, and verify the reply's mode string includes every active flag with each
value-carrying flag's value appended.

- [X] T001 [US1] Add a new private method `sendChannelModeIs(ClientSession, Channel)` to
  `jircd-core/src/main/java/net/jircd/core/session/command/ModeCommandHandler.java`, replacing
  the existing bare-query branch (`message.params().size() < 2`, currently `472
  ERR_UNKNOWNMODE`): iterate `extensionRegistry.recognizedChannelModes(CoreChannelModes.ALL)`,
  skip `LIST`/`MEMBER`-kind flags, build a `+`-prefixed letter string from active `BOOLEAN` flags
  plus active `VALUE_SET_ONLY`/`VALUE_ALWAYS` flags (reading `channel.memberLimit()`/
  `channel.key()` directly, mirroring `applyChanges`'s own existing special-casing), collecting
  each value-carrying flag's current value as a trailing param in the same order its letter
  appears; send `324 RPL_CHANNELMODEIS` with `[channel.name(), modeString, ...values]` (research.md
  "Mode string composition and scope boundary")
- [X] T002 [US1] Integration tests: the mode string includes active boolean and value-carrying
  flags (with correct trailing values, in order); an empty-flags channel's mode string is exactly
  `+`; an active ban list does NOT appear in the reply, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/ChannelModeIsTest.java` (depends
  on T001)

**Checkpoint**: The mode-string summary is fully functional and independently testable.

---

## Phase 4: User Story 2 - Any Member Can Check Settings, Not Just Operators (Priority: P2)

**Goal**: The query succeeds for any member (no operator privilege required) and respects
existing private/secret channel visibility.

**Independent Test**: As a non-operator member, query a channel's modes and verify success; as a
non-member of a private/secret channel, verify the query is refused the same way that channel is
already hidden elsewhere.

- [X] T003 [US2] Add a `ChannelVisibility.isHiddenFrom(channel, session, extensionRegistry)` check
  at the top of `sendChannelModeIs`, replying `403 ERR_NOSUCHCHANNEL` and returning if hidden — no
  other change needed for the no-operator-privilege half of this story, since the bare-query
  branch already runs before the handler's whole-command operator check (research.md "Query
  access"), in
  `jircd-core/src/main/java/net/jircd/core/session/command/ModeCommandHandler.java` (depends on
  T001)
- [X] T004 [US2] Integration tests: a non-operator member's query succeeds identically to an
  operator's; a non-member of a private/secret channel gets `403`, indistinguishable from a
  nonexistent channel, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/ChannelModeIsTest.java` (depends
  on T002, T003)

**Checkpoint**: Query access (privilege + visibility) fully functional and independently
testable.

---

## Phase 5: User Story 3 - The Query Also Reports When the Channel Was Created (Priority: P3)

**Goal**: The query's reply also includes the channel's creation time.

**Independent Test**: Query a channel's modes and verify the reply includes a creation-time
numeric distinct from the mode-string reply; verify a recreated channel reports a fresh time.

- [X] T005 [P] [US3] Add `RPL_CHANNELCREATED(329)` to
  `jircd-protocol/src/main/java/net/jircd/protocol/NumericReply.java`, inserted between the
  existing `RPL_UNIQOPIS(325)` and `RPL_NOTOPIC(331)` entries (data-model.md — 326-330 confirmed
  unreserved)
- [X] T006 [P] [US3] Add a `private final Instant createdAt = Instant.now();` field (with a
  `createdAt()` accessor) to
  `jircd-core/src/main/java/net/jircd/core/session/Channel.java` — a field initializer, not a
  constructor parameter, so it resets naturally on recreation the same way `topic`/`memberLimit`/
  `key` already do (data-model.md)
- [X] T007 [US3] After the `324` send in `sendChannelModeIs`, add
  `Replies.send(session, serverName.get(), NumericReply.RPL_CHANNELCREATED, channel.name(),
  String.valueOf(channel.createdAt().getEpochSecond()))`, in
  `jircd-core/src/main/java/net/jircd/core/session/command/ModeCommandHandler.java` (depends on
  T003, T005, T006)
- [X] T008 [US3] Integration tests: the reply includes `329` with a near-current timestamp; a
  channel recreated after becoming empty reports a fresh creation time on its next query, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/ChannelModeIsTest.java` (depends
  on T007)

**Checkpoint**: Creation-time reporting fully functional and independently testable.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T009 [P] Update `specs/001-ircv3-server/contracts/irc-protocol-commands.md`'s `MODE`
  (channel) Full Command Catalog row and the existing bare-mode-query note (previously
  documenting the `472` behavior as a deliberate exclusion) to reflect the real `324`/`329`
  query; update `specs/001-ircv3-server/contracts/irc-numeric-replies.md`'s reserved-numerics
  table: mark `324 RPL_CHANNELMODEIS` `*(Used)*`, add a `329 RPL_CHANNELCREATED *(Used)*` row
- [X] T010 [P] Code cleanup pass: confirm `./gradlew build` runs Spotless/SpotBugs/PMD clean
  across every touched module
- [X] T011 Run the full `specs/007-bare-mode-query/quickstart.md` validation pass manually
  against a running `./gradlew :jircd-server:run` instance (constitution UX Consistency
  principle's required manual usage-scenario check)
- [X] T012 Re-run the irctest suite (`github.com/jircd/irctest`'s `irctest.controllers.jircd`
  controller, `--timeout=60 --timeout-method=signal`) and confirm
  `chmodes/modeis.py::testChannelModeIs` now passes, with no regression in any previously-passing
  test (spec.md SC-003)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)** / **Foundational (Phase 2)**: Both empty.
- **User Stories (Phase 3-5)**: Build incrementally on the same method
  (`sendChannelModeIs`) in the same file — US1 creates it, US2 and US3 each extend it further.
  Land in priority order (US1 → US2 → US3); US3's `T005`/`T006` (different files) may land
  earlier in parallel, but `T007` itself depends on `T003` (US2) having already landed.
- **Polish (Phase 6)**: `T009`/`T010` can start once all three stories land; `T011`/`T012` depend
  on everything.

### Shared-file coordination (read before parallelizing)

- **`ModeCommandHandler.java`** is edited by **US1** (`T001`), **US2** (`T003`), and **US3**
  (`T007`), all within the same `sendChannelModeIs` method. Land strictly in that order — each
  builds on the previous story's version of the method, not a merge-conflict risk so much as a
  genuine functional dependency (US2's visibility check and US3's `329` send both need `T001`'s
  method to already exist).
- **`ChannelModeIsTest.java`** is extended by all three stories' own test tasks (`T002`, `T004`,
  `T008`) — land in the same order as the production-code tasks they verify.

### Within Each User Story

- Implementation before that story's own integration test additions.
- `T005`/`T006` (US3) are `[P]` — different files, no dependency on each other — but `T007`
  itself depends on both, plus on `T003` (US2) having already landed.

### Parallel Opportunities

- `T005` and `T006` can run in parallel with each other (different files), and either can start
  as soon as US1/US2 are underway — `T007` is the only task that needs all three to have landed.
- `T009` and `T010` (Polish) are `[P]` — different files, no dependency on each other.

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 3 (US1) — the mode-string summary alone already turns the generic error into a
   substantive, useful reply.
2. **STOP and VALIDATE**: confirm the mode string is correct for a mix of flag kinds.
3. Layer US2 (privilege/visibility correctness), then US3 (creation time) incrementally — each
   is a small, additive extension of the same method, not a rework.
