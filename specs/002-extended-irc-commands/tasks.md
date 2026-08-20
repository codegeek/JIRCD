---

description: "Task list for the Extended IRC Commands feature"
---

# Tasks: Extended IRC Commands

**Input**: Design documents from `/specs/002-extended-irc-commands/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md (all present)

**Tests**: Included. The project constitution's Testing Standards principle mandates
automated coverage for every feature's primary behavior and documented edge cases before
it's considered done, and plan.md's Constitution Check commits to "every FR gets unit
and/or protocol-level integration coverage" — a project-level requirement, not an optional
add-on, the same standard `001-ircv3-server/tasks.md` was held to.

**Organization**: Tasks are grouped by user story (spec.md Stories 1-5, all in scope — none
deferred) to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1-US5)
- File paths are relative to the repository root and follow plan.md's Project Structure.

## Path Conventions

No new module — every path below lands in an existing `001-ircv3-server` module
(plan.md "Project Structure"): `jircd-core/session/command` (core protocol commands),
`jircd-server-extensions/admin` (the one administration command), `jircd-server`
(composition root), `jircd-integration-tests` (protocol-level tests).

---

## Phase 1: Setup

Not applicable to this feature — no new module, no new build dependency, no new tooling.
Every new class lands in an existing Gradle subproject with an existing `build.gradle.kts`
(plan.md "Technical Context" — "Primary Dependencies: None new").

## Phase 2: Foundational

Not applicable to this feature — unlike `001-ircv3-server`, no piece of new state or
extracted logic here is shared by more than one of this feature's five stories (each
story's prerequisite work — e.g. `ClientSession.awayReason` for Story 2, `WhowasHistory`
for Story 4 — is scoped to that story alone), so there is no cross-story blocking phase.
`Command.java` already recognizes all seven commands as of `001-ircv3-server`'s "wire-protocol
recognition MUST represent the full RFC set" (contracts/irc-protocol-commands.md) — no
change needed there either.

---

## Phase 3: User Story 1 - Query Server Information (Priority: P1) 🎯 MVP

**Goal**: A registered client can query `VERSION`, `TIME`, and `LUSERS` and get a complete,
correct reply to each.

**Independent Test**: Connect and register a client, send `VERSION`/`TIME`/`LUSERS`
independently of every other story in this feature, and verify each reply.

- [X] T001 [US1] Extract a shared `RPL_ISUPPORT`-line-rendering helper out of
  `RegistrationCompletion`'s existing `005` burst logic, callable by both it and a new
  handler, in `jircd-core/src/main/java/net/jircd/core/session/command/RegistrationCompletion.java`
  (research.md "VERSION + ISUPPORT reuse")
- [X] T002 [US1] Implement `VersionCommandHandler`: replies `351 RPL_VERSION` with server
  name/version, then the shared `ISUPPORT` burst from T001, in
  `jircd-core/src/main/java/net/jircd/core/session/command/VersionCommandHandler.java`
  (depends on T001)
- [X] T003 [P] [US1] Implement `TimeCommandHandler`: replies `391 RPL_TIME` with the
  server's current local time, in
  `jircd-core/src/main/java/net/jircd/core/session/command/TimeCommandHandler.java`
- [X] T004 [P] [US1] Implement `LusersCommandHandler`: replies `251 RPL_LUSERCLIENT` and
  `254 RPL_LUSERCHANNELS` using `NicknameRegistry`/`ChannelRegistry` counts, in
  `jircd-core/src/main/java/net/jircd/core/session/command/LusersCommandHandler.java`
- [X] T005 [US1] Register `Command.VERSION`/`Command.TIME`/`Command.LUSERS` handlers in
  `registerStory1Handlers`, in
  `jircd-server/src/main/java/net/jircd/server/JircdServerApplication.java`
  (depends on T002, T003, T004)
- [X] T006 [US1] Integration test: `VERSION` returns `351` followed by an `ISUPPORT` burst
  byte-identical to the one seen at registration; `TIME` returns `391`; `LUSERS` returns
  `251`/`254` with counts matching actual connected-client/active-channel state, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/ServerInfoQueriesTest.java`
  (depends on T005)

**Checkpoint**: `VERSION`/`TIME`/`LUSERS` fully functional and testable independently of
every other story below.

---

## Phase 4: User Story 2 - Set and See Away Status (Priority: P2)

**Goal**: A user can mark themselves away with a reason, clear it, and have that status
visible to others via direct messaging, `WHOIS`, and `WHO`.

**Independent Test**: Two registered clients; one sets/clears `AWAY`, the other observes the
status via `PRIVMSG` reply, `WHOIS`, and `WHO` — independent of Stories 1, 3, 4, 5.

> **Shared-file note**: T009 below and Story 5's T025 both modify
> `MessageCommandHandler.java`. Sequence these two stories (don't implement both in parallel
> across two people without coordinating on this one file) to avoid a merge conflict — this
> does not affect either story's independent *testability* once both are merged.

- [X] T007 [US2] Add `awayReason` (`Optional<String>`, absent by default) field to
  `ClientSession`, in `jircd-core/src/main/java/net/jircd/core/session/ClientSession.java`
  (data-model.md "ClientSession — addition")
- [X] T008 [P] [US2] Implement `AwayCommandHandler`: sets/replaces `awayReason` with
  `306 RPL_NOWAWAY` given a reason (validated as UTF-8, reusing FR-054's existing check),
  clears it with `305 RPL_UNAWAY` given none, in
  `jircd-core/src/main/java/net/jircd/core/session/command/AwayCommandHandler.java`
  (depends on T007)
- [X] T009 [P] [US2] Send `301 RPL_AWAY <nick> :<reason>` to the sender, alongside normal
  delivery, when `PRIVMSG`/`NOTICE` targets a session with `awayReason` present, in
  `jircd-core/src/main/java/net/jircd/core/session/command/MessageCommandHandler.java`
  (depends on T007)
- [X] T010 [P] [US2] Include `301 RPL_AWAY` immediately after `311 RPL_WHOISUSER` when the
  `WHOIS` target is currently away, in
  `jircd-core/src/main/java/net/jircd/core/session/command/WhoisCommandHandler.java`
  (depends on T007)
- [X] T011 [P] [US2] Send status letter `G` instead of `H` for an away match in `352
  RPL_WHOREPLY`, in
  `jircd-core/src/main/java/net/jircd/core/session/command/WhoCommandHandler.java`
  (depends on T007)
- [X] T012 [US2] Register `Command.AWAY` handler in `registerStory1Handlers`, in
  `jircd-server/src/main/java/net/jircd/server/JircdServerApplication.java`
  (depends on T008)
- [X] T013 [US2] Integration test: set/clear away with `305`/`306`; a `PRIVMSG` to an away
  target carries `301`; `WHOIS` shows the away line; `WHO` shows `G` while away and `H`
  after clearing; status persists across a `NICK` change, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/AwayStatusTest.java`
  (depends on T009, T010, T011, T012)

**Checkpoint**: Away status fully functional and testable independently of Stories 1, 3, 4,
5 (subject to the shared-file coordination note above with Story 5).

---

## Phase 5: User Story 3 - Administrator Forcibly Disconnects a Client (Priority: P2)

**Goal**: An administrator can disconnect a misbehaving client in one command.

**Independent Test**: An `OPER`-privileged session `KILL`s a second, ordinary client;
verify the disconnection and the privilege/no-such-nickname rejection paths — independent
of Stories 1, 2, 4, 5, using only the existing `OPER` mechanism from `001-ircv3-server`.

- [X] T014 [US3] Implement `KillCommandHandler`: resolves the target via `NicknameRegistry`,
  sends `ERROR :<reason>` to the target then transitions it to `CLOSING` through the
  existing `DisconnectCleanup` path with a `KILL`-distinct reason, replies `481
  ERR_NOPRIVILEGES` if the sender lacks administrator privilege or `401 ERR_NOSUCHNICK` if
  the target isn't connected, confirmation notice to the sender on success, in
  `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/KillCommandHandler.java`
  (research.md "KILL disconnect path reuse")
- [X] T015 [US3] Register `Command.KILL` handler alongside `OPER`/`EXTENSION`/`REHASH`/
  `SAJOIN`/`SAMODE`/`WHOHOST`, in
  `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/AdminExtension.java`
  (depends on T014)
- [X] T016 [US3] Integration test: non-privileged `KILL` rejected with `481` and no
  disconnection; privileged `KILL` disconnects the target with a channel-visible reason
  distinguishable from `QUIT`/timeout; `KILL` of a nonexistent nickname returns `401`; an
  administrator may `KILL` their own nickname, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/KillCommandTest.java`
  (depends on T015)

**Checkpoint**: `KILL` fully functional and testable independently of Stories 1, 2, 4, 5.

---

## Phase 6: User Story 4 - Look Up a Disconnected User's Last-Known Identity (Priority: P3)

**Goal**: Any registered client can recover a recently-disconnected nickname's last-known
identity via `WHOWAS`.

**Independent Test**: A client registers, disconnects, and a second client queries `WHOWAS`
for it — independent of Stories 1, 2, 3, 5 (its integration test additionally reuses
Story 3's `KILL` to exercise the "disconnected via `KILL`" case, so implement Phase 5 first
even though the stories are otherwise independent).

- [X] T017 [P] [US4] Implement `WhowasEntry` value object (`nickname`, `ident`, `hostname`,
  `realname`, `disconnectedAt`), in
  `jircd-core/src/main/java/net/jircd/core/session/WhowasEntry.java` (data-model.md
  "WhowasEntry")
- [X] T018 [US4] Implement `WhowasHistory`: a bounded, capacity-configurable ring buffer of
  `WhowasEntry`, thread-safe `record`/`mostRecentFor(nickname)` (research.md "WHOWAS bounded
  history store"), in `jircd-core/src/main/java/net/jircd/core/session/WhowasHistory.java`
  (depends on T017)
- [X] T019 [P] [US4] Unit tests for `WhowasHistory`: eviction of the oldest entry once at
  capacity, most-recent-wins lookup for a nickname with multiple entries, no-match returns
  empty, in `jircd-core/src/test/java/net/jircd/core/session/WhowasHistoryTest.java`
  (depends on T018)
- [X] T020 [P] [US4] Add `whowasHistorySize` (positive integer, default `100`) to
  `ServerConfiguration` with load-time validation matching the existing bounded-numeric-limit
  pattern, in `jircd-core/src/main/java/net/jircd/core/config/ServerConfiguration.java` and
  `jircd-core/src/main/java/net/jircd/core/config/ConfigurationLoader.java`
  (contracts/server-configuration-extensions.md)
- [X] T021 [US4] Record a `WhowasEntry` into `WhowasHistory` as one additional step of the
  existing FR-017 cleanup sequence, for every disconnection cause (`QUIT`, `KILL`, keep-alive
  timeout) alike, in
  `jircd-core/src/main/java/net/jircd/core/session/DisconnectCleanup.java`
  (depends on T018)
- [X] T022 [US4] Implement `WhowasCommandHandler`: `314 RPL_WHOWASUSER` then `369
  RPL_ENDOFWHOWAS` on a match, `406 ERR_WASNOSUCHNICK` then `369` otherwise, in
  `jircd-core/src/main/java/net/jircd/core/session/command/WhowasCommandHandler.java`
  (depends on T018, T020)
- [X] T023 [US4] Register `Command.WHOWAS` handler in `registerStory1Handlers`, in
  `jircd-server/src/main/java/net/jircd/server/JircdServerApplication.java`
  (depends on T021, T022)
- [X] T024 [US4] Integration test: `WHOWAS` after a voluntary `QUIT` and after a Story 3
  `KILL` both return the correct last-known identity; `WHOWAS` for a never-seen nickname
  returns `406`; a nickname disconnected and reconnected twice returns the most recent
  entry, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/WhowasCommandTest.java`
  (depends on T023; also exercises T014-T016)

**Checkpoint**: `WHOWAS` fully functional and independently testable (its own test suite
exercises Story 3's `KILL` as one of its disconnection-cause cases, but Story 4's own
functionality does not depend on Story 3 being present — a `QUIT`-only history is equally
valid).

---

## Phase 7: User Story 5 - Send Metadata-Only Messages (Priority: P3)

**Goal**: `message-tags`-negotiated clients can exchange tag-only signals invisible to
clients that don't support them.

**Independent Test**: Two `message-tags`-negotiated clients exchange a `TAGMSG`; a third,
non-negotiated client in the same channel receives nothing — independent of Stories 1, 2,
3, 4.

> **Shared-file note**: T025 below and Story 2's T009 both modify
> `MessageCommandHandler.java` — see that story's note above.

- [X] T025 [US5] Extract a shared target-resolution step (channel membership, nickname
  existence, moderation/ban gates — FR-022) out of `MessageCommandHandler`'s existing
  `PRIVMSG`/`NOTICE` logic, callable by both it and a new handler, in
  `jircd-core/src/main/java/net/jircd/core/session/command/MessageCommandHandler.java`
  (research.md "TAGMSG delivery reuse")
- [X] T026 [US5] Implement `TagmsgCommandHandler`: rejects a `TAGMSG` carrying no tags
  (FR-023); otherwise resolves the target via T025's shared step and fans out to recipients
  that have `message-tags` negotiated only, applying `echo-message`/`msgid` the same way
  `PRIVMSG` already does, in
  `jircd-core/src/main/java/net/jircd/core/session/command/TagmsgCommandHandler.java`
  (depends on T025)
- [X] T027 [US5] Register `Command.TAGMSG` handler in `registerStory1Handlers`, in
  `jircd-server/src/main/java/net/jircd/server/JircdServerApplication.java`
  (depends on T026)
- [X] T028 [US5] Integration test: `TAGMSG` delivered to `message-tags`-negotiated
  channel/direct-message recipients; silently dropped for a non-negotiated recipient in the
  same channel; rejected outright with no tags; sender with `echo-message` receives their
  own `TAGMSG` back, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/TagmsgCommandTest.java`
  (depends on T027)

**Checkpoint**: All five user stories now independently functional.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [X] T029 [P] Code cleanup pass: confirm `./gradlew build` runs Spotless/SpotBugs/PMD clean
  across every module touched by this feature (constitution Code Quality principle)
- [X] T030 Run the full `specs/002-extended-irc-commands/quickstart.md` validation pass
  manually against a running `./gradlew :jircd-server:run` instance, covering all five
  stories end-to-end (constitution UX Consistency principle's required manual
  usage-scenario check)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)** / **Foundational (Phase 2)**: Both empty for this feature — see notes
  above. User stories can begin immediately.
- **User Stories (Phase 3-7)**: Each independently testable once its own tasks are done.
  Two cross-story caveats (neither breaks independent *testability*, both affect
  *simultaneous* implementation by different people):
  - Story 2 (T009) and Story 5 (T025) both edit `MessageCommandHandler.java` — coordinate or
    sequence these two.
  - Every story's own handler-registration task (T005, T012, T015, T023, T027) edits
    `JircdServerApplication.java`'s `registerStory1Handlers` (or `AdminExtension.java` for
    Story 3) — the same one-file-many-editors consideration as `001-ircv3-server`'s
    identical pattern.
  - Story 4's integration test (T024) exercises Story 3's `KILL` as one of its scenarios —
    implement Phase 5 before running Phase 6's test, even though Story 4's actual
    functionality doesn't require Story 3 to exist.
- **Polish (Phase 8)**: Depends on all five stories being complete.

### Within Each User Story

- Entity/field additions before the handler(s) that read them.
- Handler implementation before its registration in `JircdServerApplication`/
  `AdminExtension`.
- Registration before that story's integration test.

### Parallel Opportunities

- T003/T004 (Story 1) — different files, no shared dependency beyond nothing.
- T008/T009/T010/T011 (Story 2) — different files, each depends only on the already-complete
  T007.
- T017/T020 (Story 4) — different files, independent of each other.
- Different stories may be worked on in parallel by different people, subject to the two
  shared-file caveats above.

---

## Parallel Example: User Story 2

```bash
# Once T007 (ClientSession.awayReason) is done, launch these four together:
Task: "Implement AwayCommandHandler in jircd-core/.../command/AwayCommandHandler.java"
Task: "Add away-notice send to MessageCommandHandler in jircd-core/.../command/MessageCommandHandler.java"
Task: "Add away line to WhoisCommandHandler in jircd-core/.../command/WhoisCommandHandler.java"
Task: "Switch WHO status letter in WhoCommandHandler in jircd-core/.../command/WhoCommandHandler.java"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 3 (Story 1 — `VERSION`/`TIME`/`LUSERS`).
2. **STOP and VALIDATE**: run `ServerInfoQueriesTest` and the relevant slice of
   quickstart.md.
3. This alone already closes the cheapest, most visible gap between this server and client
   expectations (spec.md Story 1 "Why this priority").

### Incremental Delivery

1. Story 1 → validate → (optional demo/merge point).
2. Story 2 → validate independently (mind the `MessageCommandHandler.java` note if Story 5
   is being worked concurrently).
3. Story 3 → validate independently.
4. Story 4 → validate independently (run after Story 3 so its `KILL`-sourced test case has
   something to exercise).
5. Story 5 → validate independently (mind the `MessageCommandHandler.java` note if Story 2
   is being worked concurrently).
6. Polish.

---

## Notes

- [P] tasks = different files, no dependencies on incomplete tasks.
- [Story] label maps each task to its user story for traceability.
- No task in this feature touches `001-ircv3-server`'s own spec/plan/contracts files —
  everything here is additive, in this feature's own directory and in application code.
- Commit after each task or logical group.
- Stop at any checkpoint to validate a story independently.
