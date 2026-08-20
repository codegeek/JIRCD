# Feature Specification: Fix TLS Certificate Handling

**Feature Branch**: `004-fix-tls-certificate`

**Created**: 2026-08-20

**Status**: Draft

**Input**: User description: "TLS support as it is right now will cause issues if every time you
restart the server a new cert is created. Instead, only enable TLS support if configuration
explicitly defines a cert file. Make sure server is able to load .pem files as well."

This feature is a correctness/hardening follow-up to `001-ircv3-server`'s TLS support (FR-018),
in the same spirit as `003-irctest-conformance-fixes` was a follow-up to `001`/`002` — it does
not add new IRC protocol commands or capabilities. `001-ircv3-server` deliberately punted
certificate *management* as "a planning concern, not a specification one." This feature is that
planning-phase follow-through, now that the deferred default (an ephemeral, freshly generated
self-signed certificate on every process start) has proven to be a real operational problem: any
client that remembers or pins the server's certificate/fingerprint across restarts breaks every
time the server restarts, and an administrator has no way to install a real certificate (e.g.
from Let's Encrypt/certbot) through the server's own configuration file.

## Clarifications

### Session 2026-08-20

- Q: How should the certificate and private key be supplied in config — as two separate PEM
  files, or one combined file? → A: Two separate files — a certificate/chain path and a private
  key path, matching Let's Encrypt/certbot's own `fullchain.pem`/`privkey.pem` output exactly.
- Q: Should the existing PKCS12-keystore-via-JVM-system-property path be removed now that
  config-file-based certificates exist? → A: Remove the system-property mechanism, but let the
  configuration file accept a PKCS12 keystore path as an alternative to a PEM cert/key pair — one
  validated, config-file-based path supporting two formats.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A Restarted Server Keeps Presenting the Same Certificate (Priority: P1)

An administrator runs a TLS-enabled server with a certificate they've configured. They restart
the server (for a config change, an upgrade, or a crash recovery) and expect clients that
already trust or have pinned that certificate to keep connecting without a new trust prompt or a
broken pinned-certificate check.

**Why this priority**: This is the core problem statement — an administrator-supplied
certificate that changes on every restart defeats the entire point of using a real certificate,
and is the single most disruptive consequence of the current behavior.

**Independent Test**: Configure a certificate, start the server, record the certificate's
fingerprint over a TLS connection, restart the server, connect again, and verify the fingerprint
is identical — independent of every other story in this feature.

**Acceptance Scenarios**:

1. **Given** a server configuration with a certificate explicitly specified, **When** the server
   is started, stopped, and started again, **Then** every TLS connection made after the restart
   presents the exact same certificate (identical fingerprint) as before the restart.

---

### User Story 2 - TLS Is Never Enabled Without an Administrator-Supplied Certificate (Priority: P1)

An administrator enables a TLS listener in their configuration but has not yet supplied a
certificate (e.g. mid-setup, or a stale/incomplete config). They expect the server to make this
gap obvious and refuse to silently substitute a temporary certificate that changes on the next
restart.

**Why this priority**: This is the other half of the core problem — the automatic self-signed
fallback is exactly the mechanism causing User Story 1's disruption, so removing it (rather than
just recommending administrators configure a cert and leaving the fallback in place as a trap)
is what actually prevents the failure mode from recurring.

**Independent Test**: Configure a listener with TLS requested but no certificate specified, start
the server, and verify no ephemeral self-signed certificate is generated or used — independent of
every other story in this feature.

**Acceptance Scenarios**:

1. **Given** a listener configured with TLS requested but no certificate specified, **When** the
   server starts, **Then** the server does not generate or use any temporary/ephemeral
   certificate for that listener.
2. **Given** a listener configured with TLS requested but no certificate specified, **When** the
   server starts, **Then** the administrator is clearly informed (at startup) that this listener
   has no usable certificate, rather than the gap only surfacing later when a client's connection
   attempt inexplicably fails.

---

### User Story 3 - An Administrator Installs a Certificate from a Standard Certificate Authority (Priority: P1)

An administrator obtains a certificate the ordinary way most operators do today — via a
certificate authority workflow (such as Let's Encrypt/certbot) that produces PEM-encoded
certificate and private key files — and wants to point the server's configuration directly at
those files, without first converting them into some other format by hand.

**Why this priority**: PEM is the de facto standard output format for the most common
certificate-issuance workflows; requiring administrators to manually convert PEM into a
different format before the server can use it is exactly the kind of friction this feature
exists to remove.

**Independent Test**: Configure a listener with a PEM-encoded certificate and key, start the
server, and verify a TLS client can successfully complete a handshake and see that exact
certificate — independent of every other story in this feature.

**Acceptance Scenarios**:

1. **Given** a valid PEM-encoded certificate and private key referenced by the server
   configuration, **When** the server starts, **Then** the TLS listener uses that certificate for
   every TLS handshake.
2. **Given** a certificate or key file referenced by the configuration that is missing, unreadable,
   or not validly PEM-encoded, **When** the server starts, **Then** the server reports a clear
   configuration error identifying the problem, consistent with how other malformed configuration
   is already rejected at startup.

---

### Edge Cases

- What happens if the configured certificate file exists but the private key doesn't (or vice
  versa)? Treated as a configuration error at startup, the same as any other incomplete/malformed
  configuration (see User Story 3, Acceptance Scenario 2).
- What happens if a certificate's validity period has expired? Out of scope for this feature —
  the server loads and uses whatever valid-PEM certificate it's given; validating expiry, warning
  before expiry, or automatically renewing a certificate are not addressed here.
- What happens to a plaintext (non-TLS) listener defined alongside a misconfigured TLS listener?
  Unaffected — a TLS listener's configuration problem MUST NOT prevent the server from starting
  its other listeners (`001-ircv3-server` FR-018's "plaintext MUST remain available" guarantee is
  unaffected by this feature).

## Requirements *(mandatory)*

### Functional Requirements

**Certificate Configuration**

- **FR-001**: The server's own configuration file MUST support specifying a certificate for a
  TLS-enabled listener directly, without relying on any mechanism external to that configuration
  file.
- **FR-002**: The server MUST be able to load a certificate and private key supplied as two
  separate PEM-encoded files — one containing the certificate (and any intermediate chain), one
  containing the private key — matching the two-file convention Let's Encrypt/certbot produces
  (`fullchain.pem` + `privkey.pem`), referenced from configuration by two distinct paths
  (Clarifications).

**No Silent Ephemeral Fallback**

- **FR-003**: A listener configured to require TLS MUST NOT have a certificate automatically
  generated for it under any circumstance; TLS for that listener MUST be enabled only when a
  certificate has been explicitly configured for it.
- **FR-004**: If a listener is configured to require TLS without a certificate configured for it,
  the server MUST reject the configuration at startup with a clear, specific error identifying
  which listener is missing a certificate — consistent with how this server already rejects other
  incomplete or malformed configuration at startup (e.g. a malformed listener entry, an
  out-of-range value) rather than starting in a partially-working state.

**Existing Certificate Path Compatibility**

- **FR-005**: The server MUST NOT continue to support certificate configuration via a JVM system
  property outside the configuration file. The server MUST support loading a PKCS12 keystore
  referenced directly from the configuration file, as an alternative to the two-file PEM form
  (FR-002), so administrators with an existing PKCS12 keystore are not required to convert it to
  PEM (Clarifications).

**Startup Validation**

- **FR-006**: The server MUST validate a configured certificate and private key at startup (not
  only lazily on the first TLS connection attempt), so a misconfigured certificate is reported
  immediately rather than surfacing as an unexplained client-facing connection failure later.

### Key Entities

- **Listener**: An existing entity (`001-ircv3-server` data-model.md) representing one
  TCP-accepting endpoint (`port`, whether TLS is requested). This feature adds a certificate
  reference to the TLS-enabled case: either a certificate-path/key-path pair (PEM form, FR-002)
  or a single keystore path (PKCS12 form, FR-005) — exactly one of the two forms per listener
  that requests TLS.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A server restarted with an unchanged, explicitly configured certificate presents
  the identical certificate fingerprint to clients before and after the restart, 100% of the
  time.
- **SC-002**: A server with a TLS listener configured but no certificate specified never
  completes a TLS handshake using a self-generated certificate — it either refuses to start (per
  FR-004) or that listener never accepts a TLS connection at all.
- **SC-003**: An administrator who points the configuration at a valid, unmodified PEM
  certificate/key pair produced by a standard certificate-issuance workflow succeeds in bringing
  up a working TLS listener without converting the files into any other format first.
- **SC-004**: An administrator who misconfigures a certificate (missing file, malformed PEM,
  mismatched key) sees a specific startup-time error identifying the problem, not a generic
  failure or a server that appears to start successfully but silently can't accept TLS
  connections.

## Assumptions

- Certificate configuration is per-listener (reusing the existing `Listener` entity's scope),
  matching how `tls` itself is already a per-listener flag — this feature does not introduce a
  server-wide default certificate shared implicitly across listeners that don't each reference
  it.
- "Reject the configuration at startup" (FR-004) reuses this server's already-established
  configuration-validation convention (`ConfigurationLoader` already rejects other malformed or
  incomplete listener configuration eagerly, at startup, rather than deferring the failure) — no
  new validation philosophy is introduced.
- Passphrase-protected (encrypted) PEM private keys are out of scope for this feature — the
  primary motivating workflow (Let's Encrypt/certbot) produces an unencrypted private key file by
  default, and supporting an encrypted key adds a distinct, separately-scoped configuration
  surface (a passphrase field, key-decryption handling) not required to fix the problem this
  feature addresses.
- Certificate *renewal* (detecting an updated certificate file on disk and reloading it without a
  full server restart) is out of scope — this feature fixes certificate *identity* persisting
  across restarts, not automatic hot-reload of a rotated certificate.
- Validating a certificate's expiry date, or warning when one is approaching expiry, is out of
  scope (see Edge Cases).
