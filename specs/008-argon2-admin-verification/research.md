# Research: Argon2 Administrator Credential Verification

## Password hashing library choice — consolidating onto Password4j

**Decision**: `com.password4j:password4j` (Apache License 2.0), pure Java, no native/JNI
dependency, **replaces `at.favre.lib:bcrypt` entirely** rather than being added alongside
it. `AdminCredentialVerifier` verifies both hash formats through the same library:
`Password.check(plainPassword, storedHash).withArgon2()` for `$argon2id$` hashes,
`Password.check(plainPassword, storedHash).withBcrypt()` for `$2a$`/`$2b$`/`$2y$` hashes.
Both extract salt and cost parameters directly from the stored PHC-format string, requiring
no separately-stored configuration — matching `BCrypt.verifyer().verify(...)`'s existing
zero-config verification shape. Hashing (used only by tests, to generate known hashes) is
`Password.hash(plainPassword).withArgon2()` / `.withBcrypt()`.

**Rationale** (clarified 2026-08-21, superseding the original additive-only plan): This
project's own dependency footprint favors one small, well-reviewed library per concern over
two libraries that do overlapping work — Code Quality's single-clear-responsibility
principle applies as naturally to the dependency graph as it does to a single class.
Password4j's bcrypt verification is a documented, zero-extra-configuration operation that
auto-detects the `a`/`b`/`x`/`y` version from the hash string itself, the same way the
existing `at.favre.lib:bcrypt` verification already does — so consolidating does not lose
any of the three currently-accepted bcrypt prefixes. The risk this introduces (re-proving
bcrypt-verification compatibility for every existing credential, since the actual hashing
implementation changes even though the format doesn't) is real but bounded and directly
testable: User Story 2's existing bcrypt-regression coverage (spec.md) already requires a
test proving bcrypt credentials still authenticate after this change — that same test now
also proves the consolidation is safe, rather than needing separate justification.

Consolidating also surfaces and lets this feature clean up a pre-existing violation of the
same principle: `jircd-core/build.gradle.kts` already declares `implementation(rootProject.
libs.bcrypt)`, but no source file in `jircd-core` actually imports or uses it (confirmed via
source read — `at.favre.lib.crypto.bcrypt.BCrypt` is only ever imported in
`jircd-server-extensions/admin` and `jircd-integration-tests`). This is an unused
dependency the constitution's Code Quality principle already requires removing ("unused
dependencies MUST be removed rather than left 'for later'"); since this feature is already
editing every `build.gradle.kts` that references bcrypt, removing this vestigial entry
alongside the deliberate ones is direct cleanup of a file already in the diff, not scope
creep. `ConfigurationLoader`'s own prefix check (`BASE_64_HASH_PREFIXES`) needs no crypto
library at all — it is, and remains, a plain string-prefix `Set` membership check — so
`jircd-core` needs no replacement dependency once the unused one is removed.

**Alternatives considered**:
- Keep `at.favre.lib:bcrypt` for existing bcrypt verification and add Password4j only for
  the new Argon2id path (the original plan, before this clarification) — rejected in favor
  of consolidation: reduces total dependency count and gives `AdminCredentialVerifier` one
  uniform verification API instead of two different libraries' shapes, at an acceptable,
  already-tested-for risk (User Story 2's own regression coverage).
- `de.mkammerer:argon2-jvm` — the most commonly cited Argon2 JVM binding, but is a JNI
  wrapper around the C reference implementation, bundling native libraries per platform.
  Rejected: adds a class of build/deployment complexity (native library resolution,
  platform compatibility) this project has never needed for any dependency, for a feature
  whose entire point is closing a narrow verification gap, not adopting a new architecture.
- Bouncy Castle (`org.bouncycastle:bcprov-jdk18on`)'s `Argon2BytesGenerator` — a
  well-reviewed, pure-Java primitive, but it only exposes the raw hash-generation
  primitive, not a verify-against-an-encoded-PHC-string API; the caller would have to
  hand-parse the `$argon2id$v=19$m=...,t=...,p=...$salt$hash` format to extract
  parameters before calling it. Rejected: this project's own constitution (Code Quality)
  favors reusing an existing, well-reviewed higher-level API over adding hand-rolled
  parsing logic that a purpose-built library already handles.

## Narrowing `ConfigurationLoader`'s accepted Argon2 prefix

**Decision**: `ConfigurationLoader.BASE_64_HASH_PREFIXES`'s `"$argon2"` entry is narrowed
to `"$argon2id$"` — the specific variant this feature verifies.

**Rationale**: The current bare `"$argon2"` prefix match accepts `$argon2i$` and
`$argon2d$` hashes too, both of which `AdminCredentialVerifier` cannot verify. Leaving the
broader match in place would only shrink the original bug's blast radius (from "no Argon2
variant works" to "two of three Argon2 variants still silently fail") rather than closing
it — the same class of bug (config accepts a format the verifier can't check) would
survive for i/d variants. Narrowing to the exact supported prefix restores the
config-loader's own stated contract (FR-012/SC-008: reject any invalid value with a
specific, actionable error, never a partially-applied result) for every Argon2 variant, not
just id.

**Alternatives considered**: Leaving `"$argon2"` unnarrowed and instead documenting
i/d as "accepted at config-load but unsupported at runtime" — rejected outright; this is
exactly the inconsistency this feature exists to eliminate, not a lesser version of it to
preserve for two of three variants.

## Argon2 variant scope

**Decision**: Only Argon2id is verified. This matches spec.md's Assumptions section:
Argon2id is the OWASP-recommended general-purpose default and the natural
one-well-understood-mode counterpart to bcrypt's own single mode.

**Rationale**: No requirement in FR-034 (`001-ircv3-server`) or this feature's own spec
calls for Argon2i/Argon2d specifically; supporting only the currently-recommended variant
avoids maintaining verification code paths for two variants no current or foreseeable
credential would use, consistent with the constitution's "no speculative generality"
guidance (Development Workflow section).

**Alternatives considered**: Supporting all three Argon2 variants — rejected as
unjustified speculative scope; Password4j does support i/d as well
(`.withArgon2()` accepts a configurable type), so extending later if a real need
emerges is a small, additive change, not a rework.

## Scope confirmation: `AdminCredentialVerifier` is `OPER`-only

**Decision**: No change needed outside `AdminCredentialVerifier` and
`ConfigurationLoader` — the account/SASL credential module (FR-023/FR-024,
`001-ircv3-server`) is unaffected.

**Rationale**: Confirmed via source read — `AdminCredentialVerifier` has exactly one
caller, `OperCommandHandler` (`jircd-server-extensions/admin/src/main/java/net/jircd/
serverextensions/admin/OperCommandHandler.java:67`). FR-023/FR-024 are explicitly
deferred and not yet implemented anywhere in the codebase (confirmed via
`001-ircv3-server/spec.md`'s own "Deferred — not required for initial release" markers on
both). `AdminCredentialVerifier`'s javadoc claim of "the same ... approach FR-024's account
credentials use" describes an intended future parity, not existing shared code — nothing
today reuses this verifier outside the `OPER` path.

**Alternatives considered**: N/A — this was a scope-boundary confirmation, not a design
choice with real alternatives.
