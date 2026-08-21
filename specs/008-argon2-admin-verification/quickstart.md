# Quickstart: Validating Argon2 Administrator Credential Verification

Validation guide for this feature's 6 requirements. Same prerequisites as
`specs/001-ircv3-server/quickstart.md` — JDK 25, a raw TCP client, a running
`./gradlew :jircd-server:run` instance. Generating either an Argon2id or a bcrypt test hash
uses the same `com.password4j:password4j` dependency (see research.md "Password hashing
library choice — consolidating onto Password4j") — `at.favre.lib:bcrypt` is removed by this
feature, so `TestServer.java`'s existing bcrypt-hash helper now goes through Password4j too.

## Story 1 — Argon2-hashed administrator authenticates (FR-001)

1. Generate an Argon2id hash of a known password (e.g. via `Password.hash("adminpass").
   withArgon2()`) and configure it as an `administratorCredentials` entry's
   `hashedPassword`.
2. Start the server with that configuration; confirm it starts without any configuration
   error (the `$argon2id$` prefix is accepted, per data-model.md).
3. Connect, register, and send `OPER <username> adminpass`.
   - **Expected**: `381 RPL_YOUREOPER` (or the server's existing successful-`OPER`
     numeric) — administrator privilege granted.
4. Repeat with an incorrect password.
   - **Expected**: the same refusal `OPER` already gives for an incorrect bcrypt
     password (contract's existing `464 ERR_PASSWDMISMATCH` or equivalent) — not a crash,
     not a different error class.

## Story 2 — Existing bcrypt administrators are unaffected (FR-002)

1. Configure an `administratorCredentials` entry with a bcrypt hash (as in every prior
   feature's own test fixtures).
2. Send `OPER` with the correct password.
   - **Expected**: succeeds identically to before this feature.
3. Send `OPER` with an incorrect password.
   - **Expected**: fails identically to before this feature.

## Story 3 — Malformed hash fails safely (FR-003)

1. Configure an `administratorCredentials` entry whose `hashedPassword` is a
   syntactically-plausible but corrupted/truncated Argon2id string (still starting with
   `$argon2id$` so it passes config-load validation, but with a mangled salt/hash
   segment).
2. Send `OPER` against that username with any password.
   - **Expected**: refused the same way an incorrect password is refused — no server
     crash, no unhandled exception visible to the client or in server logs as an
     uncaught-exception stack trace.

## Configuration-load rejection (FR-004)

1. Configure an `administratorCredentials` entry with an `$argon2i$`- or
   `$argon2d$`-prefixed hash (a real, valid hash of that variant — just not the
   `$argon2id$` variant this feature verifies).
2. Attempt to start the server with that configuration.
   - **Expected**: refuses to start, with a specific, actionable error naming the
     `hashedPassword` field and the unsupported variant — the server never reaches a
     state where it accepts connections with that credential silently unusable.

## Automated cross-check

Run `jircd-integration-tests`'s new/extended test class covering all three user stories
plus the configuration-rejection case (see tasks.md), and the full existing test suite, to
confirm no regression in bcrypt-based `OPER` flows or any other credential-loading path.
