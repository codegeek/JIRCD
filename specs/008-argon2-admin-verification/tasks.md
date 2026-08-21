---

description: "Task list for Argon2 Administrator Credential Verification"
---

# Tasks: Argon2 Administrator Credential Verification

**Input**: Design documents from `/specs/008-argon2-admin-verification/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, quickstart.md (all present)

**Tests**: Included. The project constitution's Testing Standards principle mandates a
regression test for every bug fix (this is one) and coverage of primary behavior and
documented edge cases for every feature.

**Organization**: Phase 1 (Setup) swaps the dependency project-wide. Phase 2
(Foundational) rewrites the two files whose compilation every other task depends on —
`AdminCredentialVerifier` (production) and `TestServer` (shared test fixture used by
every existing OPER-related test, not just this feature's new ones). User story phases
3–5 add each story's own test coverage on top of that already-completed implementation —
per the 2026-08-21 clarification, the bcrypt and Argon2id branches are inseparable parts
of one method rewrite, so Foundational carries the implementation and each story phase
carries only that story's proof.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1-US3)
- File paths are relative to the repository root.

## Path Conventions

No new module — every path is an existing file from `001-ircv3-server`'s own module layout
(`jircd-core`, `jircd-server-extensions/admin`, `jircd-integration-tests`), plus
`gradle/libs.versions.toml`.

---

## Phase 1: Setup (dependency swap)

**Purpose**: Replace `at.favre.lib:bcrypt` with `com.password4j:password4j` in the version
catalog and every module that declares it, before any production code changes (research.md
"Password hashing library choice — consolidating onto Password4j").

- [X] T001 In `gradle/libs.versions.toml`, remove the `bcrypt` version entry and the
  `bcrypt` library entry (`at.favre.lib:bcrypt`); add a `password4j` version entry and a
  `password4j` library entry (`com.password4j:password4j`)
- [X] T002 [P] In `jircd-server-extensions/admin/build.gradle.kts`, replace
  `implementation(rootProject.libs.bcrypt)` with
  `implementation(rootProject.libs.password4j)` (depends on T001)
- [X] T003 [P] In `jircd-integration-tests/build.gradle.kts`, replace
  `testImplementation(rootProject.libs.bcrypt)` with
  `testImplementation(rootProject.libs.password4j)` (depends on T001)
- [X] T004 [P] In `jircd-core/build.gradle.kts`, remove the unused
  `implementation(rootProject.libs.bcrypt)` line entirely — no replacement dependency is
  added; confirmed via source read that no file in `jircd-core` imports `BCrypt`, and
  `ConfigurationLoader`'s prefix check needs no library (research.md)

**Checkpoint**: The version catalog and every module's dependency declarations reference
Password4j, not bcrypt.

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: Rewrite the two files that must compile against Password4j before any test —
old or new — can run. Both are hard compile-time dependencies of Phase 1: once bcrypt is
removed from a module's dependencies, any file still importing `at.favre.lib.crypto.bcrypt`
fails to compile.

**⚠️ CRITICAL**: No user story task can pass until this phase is complete.

- [X] T005 In `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/AdminCredentialVerifier.java`,
  replace the `import at.favre.lib.crypto.bcrypt.BCrypt` and its `BCrypt.verifyer().verify(...)`
  call with Password4j: branch on `credential.hashedPassword()`'s prefix — `$2a$`/`$2b$`/`$2y$`
  → `Password.check(password, hash).withBcrypt()`; `$argon2id$` → `Password.check(password,
  hash).withArgon2()`; any other prefix → `false` (unreachable given `ConfigurationLoader`'s
  own validation, but defensive). Wrap the verification call in a catch for whatever
  unchecked exception Password4j throws on a malformed/corrupted hash (confirm the exact
  type — expected to be `com.password4j.BadParametersException` or a common superclass —
  against the resolved dependency's actual API once T002 lands) so a corrupted hash returns
  `false` rather than propagating an uncaught exception (FR-003). (depends on T002)
- [X] T006 In `jircd-integration-tests/src/test/java/net/jircd/integration/TestServer.java`,
  replace the `import at.favre.lib.crypto.bcrypt.BCrypt` and both existing
  `BCrypt.withDefaults().hashToString(10, ADMIN_PASSWORD.toCharArray())` call sites (in
  `adminEnabledYaml()` and `adminAndCloakEnabledYaml()`) with a single private helper using
  `Password.hash(ADMIN_PASSWORD).withBcrypt().getResult()`, reused by both methods (removes
  the existing duplication). Add a new static method, `argon2AdminEnabledYaml()`, mirroring
  `adminEnabledYaml()`'s shape exactly but hashing `ADMIN_PASSWORD` via
  `Password.hash(ADMIN_PASSWORD).withArgon2().getResult()` instead. (depends on T003)

**Checkpoint**: `AdminCredentialVerifier` and `TestServer` both compile and are internally
consistent; every existing test that was passing before this feature should still compile
(behavioral proof is each story phase's own task below).

---

## Phase 3: User Story 1 - Administrator authenticates with an Argon2-hashed credential (Priority: P1) 🎯 MVP

**Goal**: An administrator-privilege attempt against an Argon2id-hashed credential
succeeds with the correct password.

**Independent Test**: Configure a Server Configuration with a single administrator
credential whose `hashedPassword` is a real Argon2id hash of a known password, start the
server, issue `OPER` with that password, and confirm administrator privilege is granted.

- [X] T007 [P] [US1] In `jircd-core/src/main/java/net/jircd/core/config/ConfigurationLoader.java`,
  narrow the `"$argon2"` entry in `BASE_64_HASH_PREFIXES` to `"$argon2id$"` (FR-004) — no
  dependency on T005/T006, different file
- [X] T008 [US1] New integration test file,
  `jircd-integration-tests/src/test/java/net/jircd/integration/AdminArgon2CredentialTest.java`:
  using `TestServer.argon2AdminEnabledYaml()`, prove `OPER <admin-username> <correct
  password>` returns `381 RPL_YOUREOPER` and `OPER <admin-username> <wrong password>`
  returns `464 ERR_PASSWDMISMATCH` (mirroring `Story6OperTest`'s existing shape); include a
  third test reusing `Story6OperTest`'s own `operFailureThreshold` lockout scenario but
  against the Argon2 credential, proving FR-005 (the existing failed-attempt lockout applies
  identically regardless of hash format) (depends on T006, T007)

**Checkpoint**: Argon2id-hashed administrator credentials authenticate correctly, fail
correctly on a wrong password, and participate in the existing lockout — independently
testable and demonstrable.

---

## Phase 4: User Story 2 - Existing bcrypt-based administrators are unaffected (Priority: P2)

**Goal**: Prove the Password4j consolidation introduced no regression in bcrypt-based
`OPER` authentication.

**Independent Test**: Configure a Server Configuration with a bcrypt-hashed administrator
credential (as today), issue `OPER` with the correct and then an incorrect password, and
confirm both outcomes are unchanged from current behavior.

- [X] T009 [US2] Run `jircd-integration-tests/src/test/java/net/jircd/integration/Story6OperTest.java`'s
  three existing tests unmodified against the Password4j-based bcrypt verification path —
  this file already exercises exactly US2's Independent Test (correct password succeeds,
  incorrect password fails, third failure locks out) via `TestServer.adminEnabledYaml()`, so
  no new test code is needed; if any of the three fail, that is a real regression from the
  consolidation and must be fixed before proceeding (depends on T006)

**Checkpoint**: Every existing bcrypt-based administrator-authentication scenario still
passes, unchanged.

---

## Phase 5: User Story 3 - Malformed or unsupported hash values fail safely (Priority: P3)

**Goal**: A corrupted/truncated hash value never crashes or throws — it fails the same way
an incorrect password fails.

**Independent Test**: Configure an administrator credential whose stored hash is a
syntactically-plausible but corrupted `$argon2id$`-prefixed string (passes
`ConfigurationLoader`'s prefix check, but its salt/hash segment is mangled), attempt
`OPER` against it, and confirm a clean `464` refusal rather than a crash or unhandled
exception.

- [X] T010 [US3] Extend `AdminArgon2CredentialTest.java` (from T008) with a test configuring
  an `administratorCredentials` entry whose `hashedPassword` is a `$argon2id$`-prefixed but
  corrupted/truncated string, confirming `OPER` against it returns `464
  ERR_PASSWDMISMATCH` (not a dropped/reset connection, not a timeout) — this directly
  exercises T005's catch-and-fail-closed branch (FR-003) (depends on T008)
- [X] T011 [P] [US3] In `jircd-core/src/test/java/net/jircd/core/config/ConfigurationLoaderTest.java`,
  add a test proving an `administratorCredentials` entry with an `$argon2i$`- or
  `$argon2d$`-prefixed `hashedPassword` is rejected at configuration-load time with a
  specific error naming the field (FR-004), alongside a test proving a well-formed
  `$argon2id$`-prefixed value is accepted — no dependency on T005/T006/T008, different file
  (depends on T007)

**Checkpoint**: All three user stories are independently functional; a malformed or
unsupported hash can never reach a state where it looks configured-correctly but silently
fails later.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T012 [P] Update `specs/001-ircv3-server/contracts/server-configuration.md`'s
  `hashedPassword` example (currently `"<bcrypt/Argon2 hash — ...>"`) to name the specific
  supported Argon2 variant — `"<bcrypt/Argon2id hash — ...>"` — reflecting FR-004's
  narrowed scope
- [X] T013 [P] Code cleanup pass: confirm `./gradlew build` runs Spotless/SpotBugs/PMD
  clean across every touched module, and that the full existing test suite (not just this
  feature's own tests) passes with zero regressions
- [X] T014 Run the full `specs/008-argon2-admin-verification/quickstart.md` validation
  pass manually against a running `./gradlew :jircd-server:run` instance (constitution UX
  Consistency principle's required manual usage-scenario check)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately. T002/T003/T004 depend on
  T001 (the version catalog entries must exist first) but are otherwise independent of
  each other.
- **Foundational (Phase 2)**: T005 depends on T002; T006 depends on T003. Both **block
  every user story task** — none of Phases 3–5's tests can pass (some won't even compile)
  until Phase 2 lands.
- **User Stories (Phase 3-5)**: All depend on Foundational. Land in priority order (US1 →
  US2 → US3) per the implementation strategy below, though US2 (T009) has no code
  dependency on US1/US3 beyond Foundational and could run immediately after Phase 2.
- **Polish (Phase 6)**: T012/T013 can start once Phase 2 lands; T014 depends on all user
  stories being complete.

### Shared-file coordination (read before parallelizing)

- **`AdminCredentialVerifier.java`** is touched exactly once (T005, Foundational) — both
  the bcrypt-via-Password4j and Argon2id branches land together, since splitting a single
  if/else rewrite across two tasks would be artificial. US1/US2/US3 each only add test
  coverage against the already-complete implementation.
- **`TestServer.java`** is touched exactly once (T006, Foundational) — it is shared test
  infrastructure used by every existing OPER-related integration test (not just this
  feature's new ones), so its rewrite cannot be deferred to a specific story.
- **`AdminArgon2CredentialTest.java`** is created by T008 (US1) and extended by T010
  (US3) — land in that order.

### Parallel Opportunities

- T002, T003, T004 (Setup) can run in parallel once T001 lands.
- T007 (US1, `ConfigurationLoader.java`) has no dependency on T005/T006 and can run in
  parallel with Foundational.
- T011 (US3, `ConfigurationLoaderTest.java`) has no dependency on T005/T006/T008/T010 and
  can run in parallel with Phases 3–4 once T007 lands.
- T012/T013 (Polish) are `[P]` — different files, no dependency on each other.

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1 (Setup) and Phase 2 (Foundational) — the dependency swap and the
   rewritten verifier/test-fixture are both required before US1 can be demonstrated at
   all.
2. Complete Phase 3 (US1) — an Argon2id-hashed administrator credential now authenticates.
3. **STOP and VALIDATE**: confirm T008's three tests pass.
4. Layer US2 (regression proof) and US3 (safety net) — both are additive verification, not
   further implementation.

### Incremental Delivery

1. Setup + Foundational → the actual bug fix exists, unverified.
2. US1 → the fix is proven to work (MVP).
3. US2 → the fix is proven not to have broken anything.
4. US3 → the fix is proven safe against malformed input.
5. Polish → documentation, cleanup, and manual validation close out the feature.
