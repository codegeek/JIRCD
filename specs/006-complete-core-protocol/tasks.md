---

description: "Task list for the Complete Core Protocol Exclusions feature"
---

# Tasks: Complete Core Protocol Exclusions

**Input**: Design documents from `/specs/006-complete-core-protocol/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, quickstart.md (all present)

**Tests**: Included. The project constitution's Testing Standards principle mandates automated
coverage for every feature's primary behavior. This feature additionally re-runs the specific
irctest tests each item corresponds to (SC-004), the same verification precedent `003`/`005`
both established.

**Organization**: Tasks are grouped by user story (spec.md Stories 1-6, priority order P1→P3).
Two shared-file coordination points exist across stories — see Dependencies below — everything
else is independently parallelizable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1-US6)
- File paths are relative to the repository root.

## Path Conventions

No new module — every path is an existing file from `001-ircv3-server`'s own module layout
(`jircd-core/session`, `jircd-core/session/command`, `jircd-core/extension`), plus
`jircd-integration-tests` (protocol-level tests) and one existing unit test file
(`jircd-core/src/test/java/net/jircd/core/session/WhowasHistoryTest.java`).

---

## Phase 1: Setup

Not applicable — no new module, no new build dependency, no new tooling.

## Phase 2: Foundational

Not applicable — no single blocking prerequisite spans all six stories. Two file-sharing points
exist (see Dependencies) but neither blocks the other four stories.

---

## Phase 3: User Story 1 - Channel Operators Can Cap and Lock Down Who Can Join (Priority: P1) 🎯 MVP

**Goal**: `+l` (user-limit) and `+k` (channel-key) become real, enforced channel-access modes.

**Independent Test**: Set a membership limit, fill it, verify the next join is rejected;
separately, set a key and verify a join without it is rejected while one with it succeeds.

- [X] T001 [US1] Split `ChannelMode.Kind.VALUE` into `VALUE_SET_ONLY` (parameter on set only)
  and `VALUE_ALWAYS` (parameter on both set and unset) in
  `jircd-core/src/main/java/net/jircd/core/session/ChannelMode.java` (research.md "Story 1",
  data-model.md "`ChannelMode.kind` — new values")
- [X] T002 [US1] Add `USER_LIMIT` (`l`, `VALUE_SET_ONLY`, `gates: {JOIN}`) and `CHANNEL_KEY`
  (`k`, `VALUE_ALWAYS`, `gates: {JOIN}`) constants to `CoreChannelModes.ALL`, in
  `jircd-core/src/main/java/net/jircd/core/session/CoreChannelModes.java` (depends on T001;
  shared file with US2's T008 — land this first)
- [X] T003 [P] [US1] Add `volatile int memberLimit` (`0` = unset) and `volatile String key`
  (`null` = unset) fields with accessors to
  `jircd-core/src/main/java/net/jircd/core/session/Channel.java`, reset to their unset default
  wherever the channel's other per-mode state (`bans`, `invited`) is already reset on
  zero-member recreation (data-model.md "`Channel` — new fields")
- [X] T004 [US1] Update `formatChanModes()` to populate its two previously-empty, already-commented
  `CHANMODES` groups (`alwaysParam`/`setOnlyParam`) from `VALUE_ALWAYS`/`VALUE_SET_ONLY`-kind
  modes, in `jircd-core/src/main/java/net/jircd/core/extension/SupportedFeatures.java` (depends
  on T001)
- [X] T005 [US1] Add `VALUE_SET_ONLY`/`VALUE_ALWAYS` branches to `applyChanges`'s per-flag
  dispatch: `VALUE_SET_ONLY` consumes a parameter only on `+` (parses it into `memberLimit`,
  silently skipping an invalid/non-positive value — no crash, no error reply, matching
  `irctest`'s own `testLimitInvalidValues`'s accepted "silently ignored" outcome), `-` clears it
  with no parameter consumed; `VALUE_ALWAYS` always consumes a parameter (sets/clears `key`,
  never validating the removed value against the current one, mirroring `-b <mask>`), in
  `jircd-core/src/main/java/net/jircd/core/session/command/ModeCommandHandler.java` (depends on
  T001, T002, T003; research.md "Story 1")
- [X] T006 [US1] Refactor `JoinCommandHandler`'s invite-exemption from a per-gate-consuming check
  into a single non-mutating peek used by the `+i`/`+l`/`+k` gates, with one consuming removal
  after every applicable gate passes; add the `+l` gate (`471 ERR_CHANNELISFULL` when
  `members().size() >= memberLimit`, `memberLimit > 0`) and the `+k` gate (`475
  ERR_BADCHANNELKEY` on a missing/incorrect key), both exempted by a pending invitation, in
  `jircd-core/src/main/java/net/jircd/core/session/command/JoinCommandHandler.java` (depends on
  T002, T003; research.md "Story 1" — invite exemption decision; shared file with US3's T011 —
  land this first)
- [X] T007 [US1] Integration tests: `+l` rejects once full and recovers once raised/removed; `+k`
  rejects a missing/incorrect key and accepts the correct one; a pending invitation exempts a
  join from both `+l` and `+k` at once (`chmodes/limit.py::testLimitWithInvite`'s scenario), in
  `jircd-integration-tests/src/test/java/net/jircd/integration/ChannelCapacityModesTest.java`
  (depends on T005, T006)

**Checkpoint**: `+l`/`+k` fully functional and independently testable.

---

## Phase 4: User Story 2 - Topic-Change Privilege Follows the Topic-Lock Setting (Priority: P2)

**Goal**: `+t` becomes a real, toggleable mode gating who may set a channel's topic.

**Independent Test**: With `+t` off, verify an ordinary member can set the topic; with it on,
verify the same attempt is rejected.

- [X] T008 [US2] Add `TOPIC_LOCK` (`t`, `BOOLEAN`, `gates: {}`) constant to `CoreChannelModes.ALL`,
  in `jircd-core/src/main/java/net/jircd/core/session/CoreChannelModes.java` (shared file with
  US1's T002 — land after it)
- [X] T009 [US2] Change the existing unconditional operator-only topic-set check to only apply
  when `TOPIC_LOCK` is active on the channel, in
  `jircd-core/src/main/java/net/jircd/core/session/command/TopicCommandHandler.java` (depends on
  T008; research.md "Story 2")
- [X] T010 [US2] Integration test: an ordinary member can set the topic with `+t` off, is rejected
  with `+t` on, and an operator can always set it regardless, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/TopicLockTest.java` (depends on
  T009)

**Checkpoint**: `+t` fully functional and independently testable.

---

## Phase 5: User Story 3 - A Bare Membership Query Lists Every Channel a Client Can See (Priority: P2)

**Goal**: Argument-less `NAMES` lists the membership of every channel the requester can see.

**Independent Test**: Send bare `NAMES` and verify the response lists every visible channel,
omitting any private/secret channel the requester isn't a member of, ending with one closing
reply.

- [X] T011 [P] [US3] Extract a new `sendNamesLine` static method (the `RPL_NAMREPLY`-only half of
  the existing `sendNamesReply`) so `sendNamesReply` calls it internally before its own
  `RPL_ENDOFNAMES` send — behavior of the single-channel/`JOIN` callers is unchanged, in
  `jircd-core/src/main/java/net/jircd/core/session/command/JoinCommandHandler.java` (shared file
  with US1's T006 — land after it; research.md "Story 3")
- [X] T012 [US3] Add a branch for `message.params().isEmpty()`: loop `channelRegistry.all()`,
  call `sendNamesLine` for each channel not hidden per `ChannelVisibility.isHiddenFrom`, then
  send one closing `366 RPL_ENDOFNAMES` targeted at `*`, in
  `jircd-core/src/main/java/net/jircd/core/session/command/NamesCommandHandler.java` (depends on
  T011)
- [X] T013 [US3] Integration test: bare `NAMES` returns every visible channel's membership,
  excludes a private/secret channel the requester isn't a member of, and ends with exactly one
  `366` targeted at `*`, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/BareNamesTest.java` (depends on
  T012)

**Checkpoint**: Bare `NAMES` fully functional and independently testable.

---

## Phase 6: User Story 4 - Server Statistics Report Operator Count and Always Close With a Summary Line (Priority: P3)

**Goal**: `LUSERS` reports connected-operator count and always ends with `RPL_LUSERME`.

**Independent Test**: Query `LUSERS` and verify it includes an operator-count line and always
ends with the summary line, regardless of how many operators are connected.

- [X] T014 [P] [US4] Add `252 RPL_LUSEROP` (operator count, filtered by `UserMode.OPERATOR` the
  same way the existing invisible-count line already filters by `UserMode.INVISIBLE`) and `255
  RPL_LUSERME` (unconditional, always last), in
  `jircd-core/src/main/java/net/jircd/core/session/command/LusersCommandHandler.java`
  (research.md "Story 4")
- [X] T015 [US4] Integration test: `LUSERS` includes the correct operator count both with zero
  and with one or more connected operators, and always ends with `255`, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/LusersCompletenessTest.java`
  (depends on T014)

**Checkpoint**: `LUSERS` completeness fully functional and independently testable.

---

## Phase 7: User Story 5 - Inviting Someone to a Channel That Doesn't Exist Yet Still Works (Priority: P3)

**Goal**: `INVITE` to a channel name that doesn't exist anywhere on the server succeeds.

**Independent Test**: With no channel of a given name existing, invite another connected client
to that name and verify the invitation is accepted and delivered.

- [X] T016 [P] [US5] Split the combined `found.isEmpty() || !members().contains(session)` check
  into two: a genuinely non-existent channel skips straight past the now-vacuously-satisfied
  membership/`invite-only` checks; an existing channel the inviter isn't a member of keeps the
  existing `442 ERR_NOTONCHANNEL` rejection, in
  `jircd-core/src/main/java/net/jircd/core/session/command/InviteCommandHandler.java`
  (research.md "Story 5")
- [X] T017 [US5] Integration test: inviting to a not-yet-existing channel succeeds (`RPL_INVITING`
  to the inviter, `INVITE` notification to the invited client); inviting to an existing channel
  by a non-member is still rejected with `442`, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/InviteNotYetExistingChannelTest.java`
  (depends on T016)

**Checkpoint**: `INVITE`-to-not-yet-existing-channel fully functional and independently testable.

---

## Phase 8: User Story 6 - Looking Up a Former Nickname's History Can Request More Than the Latest Entry (Priority: P3)

**Goal**: `WHOWAS` accepts an optional count parameter, including RFC's "non-positive means full
search" rule.

**Independent Test**: Change nickname through more than one prior identity, then look up that
nickname's history requesting more than one entry, and verify more than one is returned.

- [X] T018 [P] [US6] Add `mostRecentNFor(String nickname, int count)` returning
  `List<WhowasEntry>` (most-recent-first): bounded to `count` entries when `count > 0`, every
  retained match for that nickname when `count <= 0` (RFC1459 §4.5.3/RFC2812 §3.6.3's own
  "non-positive means full search" text), in
  `jircd-core/src/main/java/net/jircd/core/session/WhowasHistory.java` (research.md "Story 6")
- [X] T019 [US6] Parse an optional second parameter as an integer (a present-but-non-numeric
  value treated the same as `0`/full-search, not as absent); loop `RPL_WHOWASUSER` once per
  entry `mostRecentNFor` returns (preserving the existing FR-038 hostname resolution per entry)
  before the unconditional closing `369`; the no-count-given path still calls (or delegates to)
  the existing single-entry lookup, in
  `jircd-core/src/main/java/net/jircd/core/session/command/WhowasCommandHandler.java` (depends
  on T018)
- [X] T020 [P] [US6] Unit tests: `mostRecentNFor` returns up to `count` most-recent entries for a
  nickname with more retained than requested, and returns every retained entry for that nickname
  when `count` is `0` or negative, in
  `jircd-core/src/test/java/net/jircd/core/session/WhowasHistoryTest.java` (depends on T018; can
  run in parallel with T019 — different file)
- [X] T021 [US6] Integration test: `WHOWAS <nick> 2`/`WHOWAS <nick> 0`/`WHOWAS <nick> -1`/
  `WHOWAS <nick>` (no count) each return the expected number of entries against a nickname with
  three retained prior identities, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/WhowasCountTest.java` (depends on
  T019)

**Checkpoint**: `WHOWAS` count parameter fully functional and independently testable.

---

## Phase 9: Polish & Cross-Cutting Concerns

- [X] T022 [P] Update `specs/001-ircv3-server/contracts/irc-protocol-commands.md`'s Full Channel
  Mode Catalog: flip the `t`/`l`/`k` rows from `Reserved` to `Implemented`; update the `NAMES`,
  `INVITE`, and `MODE` (channel) rows in the Full Command Catalog to reflect the bare form,
  not-yet-existing-channel case, and the two new gated flags respectively
- [X] T023 [P] Update `specs/002-extended-irc-commands/contracts/irc-protocol-commands-extended.md`'s
  `LUSERS` row/notes (drop the now-stale "no operator-vs-non-operator breakdown" claim for
  `RPL_LUSEROP`/`RPL_LUSERME` specifically, keep it for `RPL_LUSERUNKNOWN`) and `WHOWAS` row
  (accepts an optional count parameter)
- [X] T024 [P] Code cleanup pass: confirm `./gradlew build` runs Spotless/SpotBugs/PMD clean
  across every touched module
- [X] T025 Run the full `specs/006-complete-core-protocol/quickstart.md` validation pass manually
  against a running `./gradlew :jircd-server:run` instance (constitution UX Consistency
  principle's required manual usage-scenario check)
- [X] T026 Re-run the irctest suite (`github.com/jircd/irctest`'s `irctest.controllers.jircd`
  controller, `--timeout=60 --timeout-method=signal`) and confirm every test named in
  quickstart.md's "Automated cross-check" now passes, with no regression in any
  previously-passing test (spec.md SC-004)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)** / **Foundational (Phase 2)**: Both empty.
- **User Stories (Phase 3-8)**: All six are independently implementable in any order — the only
  two file-sharing points are noted below, and neither creates a cross-story blocking chain (US4,
  US5, US6 can each start immediately; US1's and US2's shared `CoreChannelModes.java` edit and
  US1's and US3's shared `JoinCommandHandler.java` edit just need to land in a compatible order
  with each other, not with any other story).
- **Polish (Phase 9)**: T022/T023 can start once their respective FRs' stories land; T024 once all
  code changes are in; T025/T026 depend on everything.

### Shared-file coordination (read before parallelizing)

- **`CoreChannelModes.java`** is edited by both **US1** (T002, `USER_LIMIT`/`CHANNEL_KEY`) and
  **US2** (T008, `TOPIC_LOCK`). Land US1's T002 first, then US2's T008 — both add a constant to
  the same `ALL` set literal; land sequentially to avoid a merge conflict, no functional
  dependency between them.
- **`JoinCommandHandler.java`** is edited by both **US1** (T006, the invite-exemption refactor
  and new `+l`/`+k` gates inside `joinOne`) and **US3** (T011, extracting `sendNamesLine` from
  `sendNamesReply`) — different methods in the same file. Land US1's T006 first, then US3's T011,
  to avoid a merge conflict; no functional dependency between them.

### Within Each User Story

- Implementation before that story's own integration test.
- Where a story's tasks touch the same file in sequence (US1's T001→T002→T005, T002→T006), they
  are marked sequential, not `[P]`.

### Parallel Opportunities

- Stories 1, 2, 3, 4, 5, 6 can all start in parallel (respecting the two shared-file notes above).
- Within US1: T003 is `[P]` (different file, no dependency on T001/T002).
- Within US6: T018 and T020 are both eligible to run in parallel with T019 once T018 itself lands
  (T020 depends only on T018, not on T019).
- Across stories: T014 (US4), T016 (US5), T018 (US6) are all `[P]` — no shared files, no
  cross-story dependency.
