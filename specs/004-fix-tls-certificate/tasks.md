---

description: "Task list for the Fix TLS Certificate Handling feature"
---

# Tasks: Fix TLS Certificate Handling

**Input**: Design documents from `/specs/004-fix-tls-certificate/`

**Prerequisites**: plan.md, spec.md, research.md, quickstart.md (all present)

**Tests**: Included. The project constitution's Testing Standards principle mandates automated
coverage for every feature's primary behavior before it's considered done, and this feature is
itself a bug fix (Testing Standards: "every bug fix MUST include a regression test that fails
before the fix and passes after").

**Organization**: Tasks are grouped by user story (spec.md Stories 1-3, all P1). Story
numbering follows spec.md, but **implementation order follows the real dependency chain**: US3
(certificate loading mechanics) must land before US2 (validation that rejects a missing
certificate) can call into it, and both must exist before US1 (restart-persistence, an
end-to-end proof of the other two) can be meaningfully tested — see Dependencies below.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1-US3)
- File paths are relative to the repository root.

## Path Conventions

No new module — every path below is an existing file from `001-ircv3-server`'s own module
layout: `jircd-core/config`, `jircd-core/session`, `jircd-server` (composition root),
`jircd-integration-tests` (protocol-level tests).

---

## Phase 1: Setup

Not applicable — no new module, no new build dependency, no new tooling.

## Phase 2: Foundational

Blocking prerequisites every story depends on: the config-schema extension itself, and — because
every existing integration test starts a `TestServer` whose default config already requests a
TLS listener with no certificate — a working test fixture certificate, without which the entire
existing test suite (every feature from `001` through `003`) would start failing the moment
Story 2's validation lands.

- [X] T001 [P] Extend `ServerConfiguration.Listener` with four new optional fields —
  `certPath`, `keyPath` (the PEM form), `keystorePath`, `keystorePassword` (the PKCS12 form) — in
  `jircd-core/src/main/java/net/jircd/core/config/ServerConfiguration.java`
  (research.md "Config schema for a listener's certificate")
- [X] T002 [P] Generate a throwaway self-signed PEM certificate/key pair (`openssl req -x509
  -newkey rsa:2048 -nodes ...`, matching quickstart.md's Setup section) as committed test
  resources, and update `TestServer.baseYaml()` to reference them via `certPath`/`keyPath` on its
  default TLS listener, so every existing and new integration test that starts a TLS-enabled
  `TestServer` keeps working once TLS-without-a-certificate is rejected (Story 2), in
  `jircd-integration-tests/src/test/java/net/jircd/integration/TestServer.java` and new files
  under `jircd-integration-tests/src/test/resources/`

**Checkpoint**: Config schema and test fixtures ready — no story's tasks can usefully start
without both.

---

## Phase 3: User Story 3 - An Administrator Installs a Certificate from a Standard Certificate Authority (Priority: P1)

**Goal**: The server loads a PEM cert/key pair (FR-001, FR-002) or a PKCS12 keystore (FR-005)
referenced from configuration, and uses it for real TLS handshakes.

**Independent Test**: Configure a listener with a PEM-encoded certificate and key, start the
server, and verify a TLS client can complete a handshake and see that exact certificate.

- [X] T003 [US3] Implement PEM certificate/key loading in `buildServerContext` (or a new helper
  it calls): parse the certificate with `CertificateFactory.getInstance("X.509")
  .generateCertificate(...)` directly against the PEM bytes; parse the private key by stripping
  the `-----BEGIN/END PRIVATE KEY-----` PEM delimiters, base64-decoding, and loading via
  `PKCS8EncodedKeySpec` + `KeyFactory` (try `"RSA"`, fall back to `"EC"`); build an **in-memory**
  PKCS12 `KeyStore` (`load(null, null)`, `setKeyEntry`, `setCertificateEntry`) so no temp file is
  ever written, in
  `jircd-core/src/main/java/net/jircd/core/session/TlsSupport.java`
  (research.md "PEM parsing")
- [X] T004 [US3] Implement PKCS12 keystore-path loading (reusing the existing
  `KeyStore.getInstance("PKCS12")` load logic, now driven by the configured `keystorePath`/
  `keystorePassword` instead of a system property), and remove the
  `jircd.tls.keystore`/`jircd.tls.keystorePassword` system-property reads and
  `selfSignedKeystorePath()`'s `keytool`-shelling entirely, in the same file
  (research.md "Removing the system-property path")
- [X] T005 [US3] Change `buildServerContext()`'s signature to accept the resolved
  `ServerConfiguration.Listener` (or its cert-bearing fields) instead of reading system
  properties, and update `TlsListener`'s constructor to pass it through, in
  `jircd-core/src/main/java/net/jircd/core/session/TlsSupport.java` and
  `jircd-core/src/main/java/net/jircd/core/session/TlsListener.java`
  (depends on T003, T004)
- [X] T006 [US3] Integration test: a listener configured with a PEM cert/key pair completes a TLS
  handshake presenting that exact certificate (FR-001/FR-002); a listener configured with a
  PKCS12 keystore path and password completes a TLS handshake presenting that exact certificate
  (FR-005), in
  `jircd-integration-tests/src/test/java/net/jircd/integration/TlsCertificateConfigTest.java`
  (depends on T005)

**Checkpoint**: Certificate loading (both forms) fully functional and independently testable.

---

## Phase 4: User Story 2 - TLS Is Never Enabled Without an Administrator-Supplied Certificate (Priority: P1)

**Goal**: A listener requesting TLS without a certificate configured refuses to start, with a
specific error, at startup — not a silent ephemeral fallback, not a lazy first-connection
failure.

**Independent Test**: Configure a listener with TLS requested but no certificate specified, start
the server, and verify no ephemeral certificate is generated or used, and that startup is
refused with a clear error.

- [X] T007 [US2] Add startup-time validation to listener config-loading: for `tls: true`, reject
  (`ConfigurationException` naming the offending port) if neither `(certPath` and `keyPath)` nor
  `keystorePath` is present; reject if exactly one of `certPath`/`keyPath` is present; reject if
  both a PEM pair and `keystorePath` are present; when a cert form *is* present, actually load
  and parse it during this same startup pass (reusing T003/T004's loading logic) so an
  unreadable, malformed, or mismatched certificate also fails fast rather than lazily on first
  connection (FR-003, FR-004, FR-006); cert-related fields present on a `tls: false` listener are
  left untouched, matching this parser's existing precedent of ignoring unrecognized listener
  keys, in
  `jircd-core/src/main/java/net/jircd/core/config/ConfigurationLoader.java`
  (research.md "Validation rules", depends on T003, T004)
- [X] T008 [US2] [P] Change `JircdServerApplication.start()`'s hardcoded zero-config default
  listener list from `[{6667, tls=false}, {6697, tls=true}]` to `[{6667, tls=false}]` only, so a
  server started with no `listeners` configured at all runs plaintext-only rather than
  triggering a TLS-without-certificate failure, in
  `jircd-server/src/main/java/net/jircd/server/JircdServerApplication.java`
  (research.md "The zero-config default listener list")
- [X] T009 [US2] Integration tests: a listener with `tls: true` and no cert fields fails startup
  with a specific, identifiable error; a listener with only `certPath` (missing `keyPath`) fails;
  a listener with both a PEM pair and `keystorePath` fails; a listener with `tls: true` and a
  `certPath` pointing at a nonexistent file fails at startup, not lazily; a server started with
  no `listeners` key at all still starts, with only a plaintext listener and no TLS, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/TlsCertificateConfigTest.java`
  (depends on T007, T008)

**Checkpoint**: No-silent-fallback behavior fully functional and independently testable.

---

## Phase 5: User Story 1 - A Restarted Server Keeps Presenting the Same Certificate (Priority: P1)

**Goal**: A server configured with an explicit certificate presents the identical certificate —
same fingerprint — after being stopped and started again, proving no per-start regeneration
occurs anymore.

**Independent Test**: Configure a certificate, start the server, record the certificate's
fingerprint over a TLS connection, restart the server, connect again, and verify the fingerprint
is identical.

- [X] T010 [US1] Integration test: start two independent server instances against identical
  PEM-cert-configured YAML (same `certPath`/`keyPath`), connect to each over TLS, and verify both
  present a byte-identical certificate (SC-001) — proving certificate identity is a pure function
  of the configured files, not regenerated per process start, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/TlsCertificateConfigTest.java`
  (depends on T006, T009)

**Checkpoint**: Restart-persistence confirmed and independently testable — this is this
feature's core problem statement, now proven fixed.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T011 [P] Update `specs/001-ircv3-server/data-model.md`'s `listeners` field row to reflect
  the extended `Listener` shape (the four new optional fields, and which pair is mutually
  exclusive with which)
- [X] T012 [P] Update `specs/001-ircv3-server/contracts/server-configuration.md`'s `listeners`
  example block and "Behavioral Contract" section with the new cert-related fields and their
  startup-validation rules
- [X] T013 [P] Code cleanup pass: confirm `./gradlew build` runs Spotless/SpotBugs/PMD clean
  across every touched module, and confirm no dead code remains in `TlsSupport.java` (unused
  `keytool`-`ProcessBuilder` imports, the now-unused `OWNER_ONLY_DIR`/`selfSignedKeystorePath`
  machinery, etc.)
- [X] T014 Run the full `specs/004-fix-tls-certificate/quickstart.md` validation pass manually
  against a running `./gradlew :jircd-server:run` instance, including a real stop/start restart
  for the SC-001 scenario (constitution UX Consistency principle's required manual
  usage-scenario check — the same precedent this project's own convergence history already
  established for why "the automated suite passed" isn't sufficient evidence on its own)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: Empty.
- **Foundational (Phase 2)**: T001/T002 are both prerequisites for every story below — T001
  because nothing can read the new config fields without them existing, T002 because Story 2's
  validation would otherwise break every pre-existing integration test the moment it lands.
- **User Stories (Phase 3-5)**: Despite all three being P1, they have a genuine dependency
  chain, not independent parallelism: **US3 (cert loading mechanics) → US2 (validation that
  calls into that loading logic) → US1 (end-to-end proof built on both)**. This differs from
  `003-irctest-conformance-fixes`'s fully-parallel story structure specifically because this
  feature's stories are layers of the same mechanism, not disjoint command fixes.
- **Polish (Phase 6)**: T011/T012/T013 can start as soon as their respective subject matter
  lands (T011/T012 once T001/T007 are done; T013 once all code changes are in). T014 depends on
  everything.

### Within Each User Story

- Implementation before that story's own integration test.
- T007 and T008 (both US2) touch different files and have no dependency on each other, though
  both individually depend on Phase 2/3 landing first.

### Parallel Opportunities

- T001 and T002 (Foundational) — different files, no dependency on each other.
- T007 and T008 (Story 2) — different files, independent of each other.
- T011, T012, T013 (Polish) — different files, independent of each other.

---

## Parallel Example: Phase 2 (Foundational)

```bash
# T001 and T002 touch different files and have no dependency on each other:
Task: "Extend ServerConfiguration.Listener with cert fields in jircd-core/.../config/ServerConfiguration.java"
Task: "Add a test fixture cert and update TestServer.baseYaml() in jircd-integration-tests/.../TestServer.java"
```

---

## Implementation Strategy

### MVP First (User Story 3 Only)

1. Complete Phase 2 (Foundational) and Phase 3 (Story 3 — PEM/PKCS12 loading).
2. **STOP and VALIDATE**: run Phase 3's integration test and the corresponding quickstart.md
   sections (FR-001/FR-002, FR-005).
3. At this point administrators can already configure a real certificate — the ephemeral-cert
   problem isn't fully closed yet (a misconfigured listener could still silently do the wrong
   thing) until Story 2 lands, but the core capability this feature exists to add is already
   real and demonstrable.

### Incremental Delivery

1. Foundational → Story 3 (cert loading works) → validate.
2. Story 2 (no silent fallback, startup-time validation) → validate independently.
3. Story 1 (restart-persistence proof) → validate — this is the feature's own success criterion,
   SC-001.
4. Polish.

---

## Notes

- [P] tasks = different files, no dependencies on incomplete tasks.
- [Story] label maps each task to its user story for traceability.
- Unlike `003-irctest-conformance-fixes`, this feature's three stories are NOT independently
  parallelizable — the dependency chain (US3 → US2 → US1) is real and load-bearing; do not
  attempt to implement Story 2 or Story 1 before Story 3's loading mechanics exist.
- No task in this feature modifies `spec.md`/`plan.md`/`research.md` — only `data-model.md` and
  `contracts/server-configuration.md` in `001-ircv3-server`, the same "keep contracts accurate to
  current behavior" precedent `003-irctest-conformance-fixes` already established.
- Commit after each task or logical group.
- Stop at any checkpoint to validate a story independently.
