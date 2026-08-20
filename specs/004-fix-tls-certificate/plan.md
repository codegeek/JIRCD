# Implementation Plan: Fix TLS Certificate Handling

**Branch**: `004-fix-tls-certificate` | **Date**: 2026-08-20 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-fix-tls-certificate/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Stop generating a fresh ephemeral self-signed TLS certificate on every server restart. Extend
`ServerConfiguration.Listener` with two mutually-exclusive optional certificate forms — a PEM
cert/key path pair (matching Let's Encrypt/certbot's `fullchain.pem`/`privkey.pem` output,
FR-002) or a PKCS12 keystore path (FR-005) — both validated and loaded at startup (FR-006). A
listener requesting TLS without either form configured now refuses to start with a specific error
(FR-003/FR-004), the same "refuse to start" posture every other malformed configuration value in
this codebase already gets, rather than silently substituting a temporary certificate. The
hardcoded zero-config default listener list drops its TLS entry, since that default is exactly
what triggers the self-signed path today with no administrator action at all. The existing
`jircd.tls.keystore`/`jircd.tls.keystorePassword` JVM system properties and the `keytool`
-shelling self-signed generator are removed outright (Clarifications).

## Technical Context

**Language/Version**: Java 25 (LTS) — unchanged from `001-ircv3-server`/`002`/`003`.

**Primary Dependencies**: None new — `java.security.cert.CertificateFactory`,
`java.security.spec.PKCS8EncodedKeySpec`/`KeyFactory`, and `java.security.KeyStore` are all
already-available JDK APIs; `javax.net.ssl.SSLContext`/`KeyManagerFactory` are already used by
`TlsSupport`/`TlsListener` today. No new library, no new build dependency.

**Storage**: N/A — no new persisted state; certificate/key files live wherever the administrator
already keeps them (e.g. `/etc/letsencrypt/live/<domain>/`), referenced by path from
configuration, never copied or cached by the server.

**Testing**: JUnit 5 (Jupiter) + AssertJ unit tests for `ConfigurationLoader`'s new validation
rules and the PEM/PKCS12 loading logic; protocol-level integration tests over real TCP+TLS
sockets (same approach `ConnectionSmokeTest` already uses) verifying certificate identity
persists across a real server restart (SC-001) and that a misconfigured TLS listener fails
startup with a specific, identifiable error (SC-004).

**Target Platform**: Linux server (unchanged).

**Project Type**: Single backend network service — same multi-module Gradle build; no new
subproject. Every change lands in existing files in `jircd-core`/`jircd-server`.

**Performance Goals**: No new Success Criteria beyond spec.md's SC-001 through SC-004
(functional correctness) — certificate loading happens once per listener at startup, never on a
per-connection or per-message hot path; parsing a PEM file or a small PKCS12 keystore is a
one-time, sub-millisecond-class cost at process start, not a runtime concern.

**Constraints**: FR-006 requires startup-time (not lazy, first-connection-time) certificate
validation — the one constraint with real ordering weight, since it means `TlsListener`'s
construction (or a check immediately before it) must fail fast rather than deferring to the first
TLS handshake attempt.

**Scale/Scope**: 6 functional requirements (FR-001 through FR-006), 0 new entities (`Listener` is
extended, not replaced — spec.md Key Entities), 1 config-schema record change
(`ServerConfiguration.Listener`), 4 touched production files (`ServerConfiguration.java`,
`ConfigurationLoader.java`, `TlsSupport.java`, `TlsListener.java`) plus one call-site change
(`JircdServerApplication.java`'s zero-config default and `TlsListener` construction), 0 new
configuration top-level keys (four new fields nested under the existing `listeners` entries).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Status |
|---|---|---|
| I. Code Quality | Every change is a small, focused extension of an already-single-purpose file (`TlsSupport` still only builds an `SSLContext`; `ConfigurationLoader.parseListeners` still only parses/validates listeners); removing the `keytool`-shelling self-signed path is a net reduction in code and external-process surface, not an addition requiring new abstraction. | PASS |
| II. Testing Standards | Every FR gets a regression test: startup-time rejection of a TLS listener without a cert (FR-003/FR-004), successful PEM load and handshake (FR-002), successful PKCS12 load (FR-005), and — the one this feature exists to fix — certificate identity surviving a real restart (SC-001), via a genuine stop/start of the integration test harness, not a mocked check. | PASS |
| III. User Experience Consistency | The new "refuse to start with a specific error" behavior reuses the exact validation posture and error style this codebase's `ConfigurationLoader` already established for every other malformed config value (research.md) — no new error-reporting convention is invented. | PASS |
| IV. Performance Requirements | Certificate loading is a one-time startup cost, not a per-connection or per-message path — consistent with `003-irctest-conformance-fixes`'s identical reasoning for its own low-frequency-command changes. | PASS |

No violations requiring justification. Complexity Tracking table below is intentionally empty.

**Post-Design Re-check** (after Phase 1 — quickstart.md, contract updates): No new violations
introduced. The zero-config default listener list change (research.md) is a behavior change
beyond a pure reply-content fix, but it's the direct, necessary consequence of FR-003/FR-004
applied consistently — not a new capability, and it preserves rather than breaks the "runs with
zero configuration" property (the server still starts and accepts plaintext connections
out of the box; only the previously-silent, ephemeral-cert-generating TLS default is removed).
Gate remains PASS.

## Project Structure

### Documentation (this feature)

```text
specs/004-fix-tls-certificate/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

No `data-model.md` — spec.md's Key Entities section states this feature extends the existing
`Listener` entity, introducing no new one. No `contracts/` directory of its own — this feature
corrects `001-ircv3-server`'s own `data-model.md` row and `contracts/server-configuration.md`
directly (see below), the same precedent `003-irctest-conformance-fixes` already established for
keeping prior features' contract text accurate to current behavior.

### Source Code (repository root)

No new subproject — every file below is an existing file from `001-ircv3-server`'s own Project
Structure.

```text
jircd-core/
└── src/main/java/net/jircd/core/
    ├── config/
    │   ├── ServerConfiguration.java   # Listener record gains certPath/keyPath/keystorePath/
    │   │                              # keystorePassword (FR-001, FR-002, FR-005)
    │   └── ConfigurationLoader.java   # parseListeners: read new fields, validate per research.md
    │                                  # "Validation rules" (FR-003, FR-004, FR-006)
    └── session/
        ├── TlsSupport.java            # remove system-property/self-signed path; add PEM and
        │                              # PKCS12 loading from an explicit Listener (FR-002, FR-005)
        └── TlsListener.java           # pass the resolved Listener through instead of calling
                                        # the no-argument buildServerContext()

jircd-server/
└── src/main/java/net/jircd/server/
    └── JircdServerApplication.java    # zero-config default listener list drops its TLS entry
                                        # (research.md "The zero-config default listener list")

jircd-integration-tests/
└── src/test/java/net/jircd/integration/
    └── TlsCertificateConfigTest.java  # new — one test per FR-001 through FR-006, including a
                                        # real stop/start restart proving certificate identity
                                        # persists (SC-001)

specs/001-ircv3-server/data-model.md
    # update the `listeners` field row to reflect the extended Listener shape

specs/001-ircv3-server/contracts/server-configuration.md
    # update the `listeners` example block and Behavioral Contract section with the new
    # cert-related fields and their startup-validation rules
```

**Structure Decision**: No new module, no new documentation artifact type. This feature is
purely corrective/hardening within the existing `jircd-core` config layer and the `jircd-core`
TLS-bootstrap code, plus one call-site change in `jircd-server`'s composition root. Its
documentation footprint is correspondingly small: a `plan.md`/`research.md`/`quickstart.md` triad
for itself, plus direct, targeted corrections to `001-ircv3-server`'s own data model and
configuration contract — the same "keep contracts accurate to current behavior" precedent
`003-irctest-conformance-fixes` already followed for its own contract-file updates.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No violations — table intentionally empty (see Constitution Check above, both gates PASS).
