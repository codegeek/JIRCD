# Implementation Plan: Argon2 Administrator Credential Verification

**Branch**: `008-argon2-admin-verification` | **Date**: 2026-08-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/008-argon2-admin-verification/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

`ConfigurationLoader` already accepts an `$argon2`-prefixed `hashedPassword` as a
recognized format (`BASE_64_HASH_PREFIXES`), but `AdminCredentialVerifier.verify()`
unconditionally calls `BCrypt.verifyer().verify(...)`, which throws on a non-bcrypt hash and
is caught into a silent `false` — an Argon2-hashed administrator credential loads without
error and then can never authenticate. This feature makes `AdminCredentialVerifier` branch
on the credential's actual hash prefix (bcrypt vs. Argon2id) and verifies both formats
through a single new library, `com.password4j:password4j`, which **replaces
`at.favre.lib:bcrypt` entirely** rather than sitting alongside it (clarified 2026-08-21 —
research.md "Password hashing library choice — consolidating onto Password4j"): one small,
well-reviewed dependency instead of two overlapping ones, with User Story 2's own
bcrypt-regression test serving as direct proof the consolidation is safe.
`ConfigurationLoader`'s accepted-format check is narrowed from the generic `$argon2` prefix
to the specific `$argon2id$` prefix this feature actually verifies, so an unsupported Argon2
variant is still rejected at config-load time (FR-004) rather than silently accepted — this
narrowing needs no library at all, since it is, and remains, a plain string-prefix check.

## Technical Context

**Language/Version**: Java 25 (LTS) — unchanged from `001`–`007`.

**Primary Dependencies**: `com.password4j:password4j` (new) — a small, actively-maintained,
pure-Java password-hashing library supporting bcrypt, scrypt, PBKDF2, and Argon2, with a
verify-against-PHC-encoded-hash API matching the shape `AdminCredentialVerifier` already
uses for bcrypt (`BCrypt.verifyer().verify(...)`). Apache License 2.0 (compatible with this
project's own Apache-2.0 license). **Replaces `at.favre.lib:bcrypt`**, which is removed
entirely — both hash formats now verify through Password4j
(`.withBcrypt()` / `.withArgon2()`).

**Storage**: N/A — no entity or schema change; `AdministratorCredential`'s
`hashedPassword` field already stores an opaque string, unchanged in shape.

**Testing**: JUnit 5 (Jupiter) + AssertJ, identical approach to every prior feature —
a new unit-test class for `AdminCredentialVerifier` covering both hash formats plus a
malformed-hash case, and an integration test proving `OPER` succeeds against both an
Argon2id-hashed and a bcrypt-hashed administrator credential end-to-end. `TestServer.java`'s
existing bcrypt-hash-generation helper is rewritten to generate its hash via Password4j
instead of `at.favre.lib:bcrypt`, doubling as this feature's own bcrypt-regression proof —
every existing integration test that depends on that helper continues to pass unchanged.

**Target Platform**: Linux server (unchanged).

**Project Type**: Single backend network service — same multi-module Gradle build; no new
subproject. Touches `jircd-server-extensions/admin` (`AdminCredentialVerifier` + its
dependency declaration) and `jircd-core` (`ConfigurationLoader`'s prefix string only — no
dependency change there; see Constraints).

**Performance Goals**: No new user-facing operation — `OPER` already exists. Argon2id is
deliberately more expensive per-hash than bcrypt by design (memory-hard); this is
inherent to the algorithm the administrator chose by configuring an Argon2 hash, not a
regression to budget against, and `OPER` is an already-infrequent, non-hot-path command
(mirrors bcrypt's own existing unbounded-cost-factor precedent).

**Constraints**: `AdminCredentialVerifier.verify()` must not let a hash-format mismatch or
verification error surface as an uncaught exception (FR-003) — the existing
`catch (IllegalArgumentException)` pattern around the verification call is preserved,
now guarding a Password4j call instead of a `BCrypt` call. `ConfigurationLoader`'s
validation MUST reject an Argon2 hash whose prefix declares a variant other than
`$argon2id$` (e.g., `$argon2i$`, `$argon2d$`) at config-load time (FR-004) — the current
bare `$argon2` prefix match is too permissive and must be narrowed; this is a plain string
literal change in `BASE_64_HASH_PREFIXES`, requiring no library dependency in `jircd-core`
at all. `jircd-core/build.gradle.kts` currently declares `implementation(rootProject.libs.
bcrypt)` despite no source file in that module ever importing it (confirmed via source
read) — this feature removes that unused entry as direct cleanup of a file already being
edited, per the constitution's Code Quality principle ("unused dependencies MUST be
removed").

**Scale/Scope**: 6 FRs across 3 user stories, 0 new entities, 1 dependency swapped
project-wide (bcrypt → Password4j, in `gradle/libs.versions.toml` plus the two modules that
actually use it: `jircd-server-extensions/admin` and `jircd-integration-tests`), 1 unused
dependency removed (`jircd-core`), 2 touched production files (`ConfigurationLoader.java`,
`AdminCredentialVerifier.java`), 1 touched test helper (`TestServer.java`), 0 new handler
classes, 0 new configuration keys (the `hashedPassword` field's accepted value space only
narrows for the Argon2 case).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Status |
|---|---|---|
| I. Code Quality | `AdminCredentialVerifier` gains one prefix-based branch, mirroring the single-responsibility, single-file shape it already has — no new abstraction layer. Consolidating onto one library (Password4j) instead of two overlapping ones (bcrypt + Password4j) directly serves this principle at the dependency-graph level; removing `jircd-core`'s pre-existing unused bcrypt dependency while editing that same file is exactly the "unused dependencies MUST be removed" rule in action, not scope creep. | PASS |
| II. Testing Standards | This is a bug fix (silent verification failure) — a regression test proving Argon2id credentials now authenticate is required by the constitution itself, plus explicit bcrypt-regression and malformed-hash coverage (US2, US3). | PASS |
| III. User Experience Consistency | No change to `OPER`'s wire behavior (same numerics, same failed-attempt lockout) — only which stored hash formats can now succeed. FR-003's fail-closed behavior keeps error handling consistent with the existing bcrypt-mismatch path, not a new failure mode. | PASS |
| IV. Performance Requirements | No new user-facing operation; `OPER` is unchanged in shape and remains a low-frequency command. Argon2id's higher per-call cost is an inherent, administrator-chosen property of the algorithm (like bcrypt's own configurable work factor), not a regression introduced by this feature. | PASS |

No violations requiring justification. Complexity Tracking table below is intentionally empty.

**Post-Design Re-check** (after Phase 1 — data-model.md, quickstart.md, and the
2026-08-21 clarification consolidating onto Password4j): No new violations. The
dependency swap is scoped to exactly the modules that use a hashing library at runtime
(`jircd-server-extensions/admin`) or in tests (`jircd-integration-tests`) — `jircd-core`
ends this feature with *fewer* dependencies than it started with, not more, since its
bcrypt declaration was unused. Gate remains PASS.

## Project Structure

### Documentation (this feature)

```text
specs/008-argon2-admin-verification/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md         # Phase 1 output (/speckit-plan command) — no entity change, documented as such
├── quickstart.md         # Phase 1 output (/speckit-plan command)
├── checklists/
│   └── requirements.md
└── tasks.md              # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

No `contracts/` directory of its own — this feature narrows existing contract text in
`001-ircv3-server`'s own contract file (`server-configuration.md`'s `hashedPassword`
example/description), the same precedent `003`–`007` established for correcting an
existing contract rather than duplicating it — scheduled as an implementation task, not
during planning.

### Source Code (repository root)

No new subproject, no new handler class.

```text
gradle/libs.versions.toml                # Remove bcrypt version+library entries, add
                                          # password4j's

jircd-core/
├── build.gradle.kts                     # Remove the unused `implementation(rootProject.
│                                         # libs.bcrypt)` line — no replacement dependency
│                                         # needed; ConfigurationLoader's prefix check is
│                                         # a plain string comparison
└── src/main/java/net/jircd/core/config/
    └── ConfigurationLoader.java         # Narrow "$argon2" to "$argon2id$" in
                                          # BASE_64_HASH_PREFIXES (FR-004)

jircd-server-extensions/admin/
├── build.gradle.kts                     # Replace the bcrypt dependency with password4j
└── src/main/java/net/jircd/serverextensions/admin/
    └── AdminCredentialVerifier.java     # Branch on hash prefix: bcrypt (via password4j's
                                          # .withBcrypt(), replacing the at.favre.lib call)
                                          # vs. argon2id (new, via password4j's
                                          # .withArgon2())

jircd-integration-tests/
├── build.gradle.kts                     # Replace the test-scoped bcrypt dependency with
│                                         # password4j
└── src/test/java/net/jircd/integration/
    ├── TestServer.java                  # Rewrite the existing bcrypt-hash-generation
    │                                     # helper to use password4j's .withBcrypt()
    │                                     # instead of at.favre.lib's BCrypt — doubles as
    │                                     # this feature's bcrypt-regression proof, since
    │                                     # every existing test using this helper still
    │                                     # must pass
    └── (new/extended test file — see tasks.md)

specs/001-ircv3-server/contracts/server-configuration.md
    # narrow the hashedPassword example's "bcrypt/Argon2" description to name the
    # specific supported Argon2 variant (argon2id)
```

**Structure Decision**: No new module. Purely within the existing `jircd-core`
(configuration validation) and `jircd-server-extensions/admin` (credential verification)
layers already responsible for this behavior — no server-extension registration change,
no composition-root wiring change (both touched classes are already constructed the same
way). Password4j is declared exactly where it's used (`jircd-server-extensions/admin`,
`jircd-integration-tests`), the same scoping discipline bcrypt's own declaration followed —
except bcrypt's declaration had drifted from that discipline in `jircd-core` (unused), which
this feature corrects as part of removing bcrypt from the dependency graph entirely.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No violations — table intentionally empty (see Constitution Check above, both gates PASS).
