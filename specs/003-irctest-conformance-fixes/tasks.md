---

description: "Task list for the irctest Conformance Fixes feature"
---

# Tasks: irctest Conformance Fixes

**Input**: Design documents from `/specs/003-irctest-conformance-fixes/`

**Prerequisites**: plan.md, spec.md, research.md, quickstart.md (all present)

**Tests**: Included. The project constitution's Testing Standards principle mandates
automated coverage for every feature's primary behavior before it's considered done — the
same standard every prior feature in this project was held to. This feature additionally
gets a second, independent verification channel: the irctest suite itself, per plan.md's
Technical Context.

**Organization**: Tasks are grouped by user story (spec.md Stories 1-8, all in scope — two
of them, 7 and 8, confirm no behavior change per this feature's Clarifications).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1-US8)
- File paths are relative to the repository root.

## Path Conventions

No new module — every path below is an existing file from `001-ircv3-server`'s or
`002-extended-irc-commands`'s own module layout: `jircd-core/session/command` (every
touched handler), `jircd-integration-tests` (protocol-level tests).

---

## Phase 1: Setup

Not applicable — no new module, no new build dependency, no new tooling.

## Phase 2: Foundational

Not applicable — every story below touches a distinct handler file with no shared
prerequisite; all eight stories are independently implementable and independently
testable in any order or in parallel.

---

## Phase 3: User Story 1 - Client Receives a Proper Disconnect Acknowledgment (Priority: P1)

**Goal**: `QUIT` sends `ERROR` before the connection closes.

**Independent Test**: A registered client sends `QUIT`; verify `ERROR` arrives before close.

- [X] T001 [US1] Send `ERROR :<reason>` to the quitting client's own writer before calling
  `disconnectCleanup.cleanup(...)`, mirroring the same order `LivenessMonitor.timeOut()`
  and `KillCommandHandler` already use, in
  `jircd-core/src/main/java/net/jircd/core/session/command/QuitCommandHandler.java`
  (research.md "QUIT sends ERROR before closing")
- [X] T002 [US1] Integration test: a client-initiated `QUIT` (with and without an explicit
  reason) receives `ERROR` before the connection closes, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/IrctestConformanceFixesTest.java`
  (depends on T001)

**Checkpoint**: `QUIT` acknowledgment fully functional and independently testable.

---

## Phase 4: User Story 2 - Registration Rejects an Empty Real Name (Priority: P1)

**Goal**: `USER ... :` with an empty realname is rejected with `461`, not accepted.

**Independent Test**: Send `USER user 0 * :`; verify `461`, not `001`.

- [X] T003 [US2] Reject a `USER` command whose realname parameter (`params().get(3)`) is
  empty with the same `461 ERR_NEEDMOREPARAMS "USER" "Not enough parameters"` already sent
  for too few parameters, returning before `setIdent`/`setRealname`/registration
  completion, in
  `jircd-core/src/main/java/net/jircd/core/session/command/UserCommandHandler.java`
  (research.md "Empty USER realname rejected")
- [X] T004 [US2] Integration test: `USER user 0 * :` (empty realname) is rejected with
  `461`; a non-empty realname still registers successfully, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/IrctestConformanceFixesTest.java`
  (depends on T003)

**Checkpoint**: Empty-realname rejection fully functional and independently testable.

---

## Phase 5: User Story 3 - Channel Listings Reveal a Channel's Actual Privacy Status (Priority: P1)

**Goal**: `353 RPL_NAMREPLY`'s visibility symbol is `=`/`*`/`@` matching the channel's
actual public/private/secret state.

**Independent Test**: Set a channel `secret`, request its listing; verify `@` not `=`.

- [X] T005 [US3] Compute `353`'s visibility symbol from `channel.activeModes()` (`@` for
  `CoreChannelModes.SECRET`, `*` for `CoreChannelModes.PRIVATE`, `=` otherwise) instead of
  the hardcoded `"="`, in the single shared `sendNamesReply` method both `JOIN` and `NAMES`
  already call, in
  `jircd-core/src/main/java/net/jircd/core/session/command/JoinCommandHandler.java`
  (research.md "NAMES/JOIN's 353 visibility symbol reflects actual mode")
- [X] T006 [US3] Integration test: a public channel's listing shows `=`, a `secret`
  channel's shows `@`, a `private` channel's shows `*` — via both `JOIN` and `NAMES`, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/IrctestConformanceFixesTest.java`
  (depends on T005)

**Checkpoint**: Visibility symbol fully functional and independently testable.

---

## Phase 6: User Story 4 - Server Statistics Reply Is Machine-Parseable (Priority: P2)

**Goal**: `LUSERS`' `251` reply text follows the conventional parseable shape.

**Independent Test**: Query `LUSERS`; verify the reply text matches the conventional shape.

- [X] T007 [US4] Reword `251 RPL_LUSERCLIENT`'s text to
  `"There are <clients> users and <invisible> invisible on 1 servers"`, computing
  `<invisible>` as a live filter over `nicknameRegistry.all()` for
  `UserMode.INVISIBLE` (no new tracked state), in
  `jircd-core/src/main/java/net/jircd/core/session/command/LusersCommandHandler.java`
  (research.md "LUSERS reply text matches the conventional shape")
- [X] T008 [US4] Integration test: `LUSERS`' `251` text matches the conventional shape and
  its invisible count reflects actual `+i` sessions, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/IrctestConformanceFixesTest.java`
  (depends on T007)

**Checkpoint**: `LUSERS` wording fully functional and independently testable.

---

## Phase 7: User Story 5 - Last-Known-Identity Lookup Uses the Precise Missing-Argument Error (Priority: P3)

**Goal**: Bare `WHOWAS` (no nickname) replies `431`, not `461`.

**Independent Test**: Send bare `WHOWAS`; verify `431`, not `461`.

- [X] T009 [US5] Reply `431 ERR_NONICKNAMEGIVEN "No nickname given"` (the same shape
  `NickCommandHandler` already uses for bare `NICK`) instead of `461` when `WHOWAS` is sent
  with no arguments, in
  `jircd-core/src/main/java/net/jircd/core/session/command/WhowasCommandHandler.java`
  (research.md "WHOWAS bare-command numeric")
- [X] T010 [US5] Integration test: bare `WHOWAS` returns `431`; `WHOWAS <nickname>`
  behavior is unchanged, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/IrctestConformanceFixesTest.java`
  (depends on T009)

**Checkpoint**: `WHOWAS` numeric fully functional and independently testable.

---

## Phase 8: User Story 6 - User and Channel Lookups Include a Server-Name Field (Priority: P3)

**Goal**: `WHO`'s `352` and `WHOIS` both report this server's own name, and `WHOIS <server>
<nickname>`'s two-parameter form parses correctly.

**Independent Test**: Query `WHO`/`WHOIS` for a connected user; verify the server-name field
appears in both, and the two-parameter `WHOIS` form still targets the right nickname.

- [X] T011 [P] [US6] Insert `serverName.get()` as a new field between the hostname and
  nickname fields of `352 RPL_WHOREPLY`, matching RFC 2812's
  `<channel> <user> <host> <server> <nick> ...` order, in
  `jircd-core/src/main/java/net/jircd/core/session/command/WhoCommandHandler.java`
  (research.md "WHO/WHOIS server-name field")
- [X] T012 [P] [US6] Send `312 RPL_WHOISSERVER <target-nick> <server-name> :jircd IRC
  server` immediately after `311 RPL_WHOISUSER`; separately, resolve the target nickname
  from the *last* parameter regardless of whether `WHOIS` was sent with one parameter
  (`<nickname>`) or two (`<target-server> <nickname>`) — the leading server-name argument,
  if present, is accepted but not used to route anywhere (no federation,
  `001-ircv3-server` FR-021), in
  `jircd-core/src/main/java/net/jircd/core/session/command/WhoisCommandHandler.java`
  (research.md "WHO/WHOIS server-name field" — "Discovered during planning")
- [X] T013 [US6] Integration test: `WHO <nick>` includes the server-name field in the
  correct position; `WHOIS <nick>` sends `312` after `311`; `WHOIS <server-name> <nick>`
  (two-parameter form) returns the same result as the one-parameter form, not `401`, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/IrctestConformanceFixesTest.java`
  (depends on T011, T012)

**Checkpoint**: Server-name field fully functional and independently testable.

---

## Phase 9: User Story 7 - Channel Listings Keep Hidden Channels Indistinguishable From Nonexistent Ones (Priority: P3)

**Goal**: Confirm — no code change. Lock in current behavior with an explicit regression
test so this finding isn't mistaken for an unaddressed gap later.

**Independent Test**: Query a never-created channel name; verify the same `403`-class
response a hidden channel already produces.

- [X] T014 [US7] Regression test: `NAMES`/`LIST`/`TOPIC` for a channel name that was never
  created returns the identical `403 ERR_NOSUCHCHANNEL` response a `private`/`secret`
  channel already produces for a non-member — confirming FR-008's explicit no-change
  decision, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/IrctestConformanceFixesTest.java`

**Checkpoint**: FR-008 behavior confirmed unchanged and covered by a regression test.

---

## Phase 10: User Story 8 - Nicknames Remain ASCII-Only (Priority: P3)

**Goal**: Confirm — no code change. Lock in current behavior with an explicit regression
test.

**Independent Test**: Attempt to register a UTF-8 non-ASCII nickname; verify it's still
rejected.

- [X] T015 [US8] Regression test: registering a nickname containing valid UTF-8 non-ASCII
  characters is still rejected with `432 ERR_ERRONEUSNICKNAME` under the existing RFC 2812
  §2.3.1 grammar — confirming FR-009's explicit no-change decision, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/IrctestConformanceFixesTest.java`

**Checkpoint**: FR-009 behavior confirmed unchanged and covered by a regression test.

---

## Phase 11: Polish & Cross-Cutting Concerns

- [X] T016 [P] Update `specs/001-ircv3-server/contracts/irc-protocol-commands.md`'s `QUIT`,
  `USER`, `NAMES`/`JOIN`, `WHO`, and `WHOIS` rows/contract notes to reflect FR-001, FR-002,
  FR-003, FR-006, and FR-007's corrected behavior
- [X] T017 [P] Update
  `specs/002-extended-irc-commands/contracts/irc-protocol-commands-extended.md`'s `LUSERS`
  and `WHOWAS` rows/contract notes to reflect FR-004 and FR-005's corrected behavior
- [X] T018 [P] Code cleanup pass: confirm `./gradlew build` runs Spotless/SpotBugs/PMD clean
  across every module touched by this feature (constitution Code Quality principle)
- [X] T019 Run the full `specs/003-irctest-conformance-fixes/quickstart.md` validation pass
  manually against a running `./gradlew :jircd-server:run` instance (constitution UX
  Consistency principle's required manual usage-scenario check — see this session's own
  T030/T031 precedent for why "the automated suite passed" is not sufficient evidence on
  its own)
- [X] T020 Re-run the irctest conformance suite (`github.com/jircd/irctest`,
  `irctest.controllers.jircd`, `--timeout=60 --timeout-method=signal`) and confirm the
  seven specific test cases named in quickstart.md's "Automated cross-check" now pass, with
  no regression in any previously-passing test (spec.md SC-005)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)** / **Foundational (Phase 2)**: Both empty — every story can begin
  immediately, in any order, in parallel.
- **User Stories (Phase 3-10)**: Each touches a distinct file with no cross-story overlap —
  fully parallelizable by different people with zero file-conflict risk, unlike
  `002-extended-irc-commands`'s `MessageCommandHandler.java` coordination note.
- **Polish (Phase 11)**: T016/T017 (contract-doc updates) can start as soon as their
  corresponding stories' implementation tasks land, independent of each other and of
  T018-T020. T019/T020 depend on all of Phases 3-10 being complete.

### Within Each User Story

- Implementation before that story's own integration test.
- Stories 7 and 8 have no implementation task — their one task is the regression test
  itself, since the point is confirming nothing changes.

### Parallel Opportunities

- All eight user-story phases (3-10) can run fully in parallel — no shared files.
- T011/T012 within Story 6 — different files (`WhoCommandHandler.java` vs.
  `WhoisCommandHandler.java`), independent of each other.
- T016/T017/T018 in Polish — different files, independent of each other.

---

## Parallel Example: Phase 8 (User Story 6)

```bash
# T011 and T012 touch different files and have no dependency on each other:
Task: "Add server-name field to 352 in jircd-core/.../command/WhoCommandHandler.java"
Task: "Add 312 + two-parameter WHOIS parsing in jircd-core/.../command/WhoisCommandHandler.java"
```

---

## Implementation Strategy

### MVP First (User Stories 1-3 Only)

1. Complete Phases 3-5 (Stories 1-3 — all P1: `QUIT` acknowledgment, empty-realname
   rejection, `NAMES`/`JOIN` visibility symbol).
2. **STOP and VALIDATE**: run the corresponding quickstart.md sections and irctest cases.
3. These three are independently the highest-value, most-hit fixes — everything after is
   P2/P3 polish or explicit no-change confirmation.

### Incremental Delivery

1. Stories 1-3 (P1) → validate → (optional demo/merge point).
2. Story 4 (P2) → validate independently.
3. Stories 5, 6, 7, 8 (P3) → validate independently, any order.
4. Polish.

---

## Notes

- [P] tasks = different files, no dependencies on incomplete tasks.
- [Story] label maps each task to its user story for traceability.
- No task in this feature modifies `spec.md`/`plan.md`/`research.md` of
  `001-ircv3-server` or `002-extended-irc-commands` — only their `contracts/` files, to
  keep documented behavior accurate to what's actually implemented, the same precedent
  `002-extended-irc-commands`'s own `WHOWAS` hostname-privacy fix already set.
- Commit after each task or logical group.
- Stop at any checkpoint to validate a story independently.
