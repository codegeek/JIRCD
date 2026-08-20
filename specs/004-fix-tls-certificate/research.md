# Phase 0 Research: Fix TLS Certificate Handling

No new module, no new external dependency — every decision here works within `jircd-core`'s
existing config-loading and TLS-bootstrapping code, using only the JDK's own `java.security`/
`javax.net.ssl` APIs already in use.

## Current behavior, confirmed by reading the code

`TlsSupport.buildServerContext()`
(`jircd-core/src/main/java/net/jircd/core/session/TlsSupport.java:47-66`) only ever consults two
JVM system properties (`jircd.tls.keystore`/`jircd.tls.keystorePassword`), never the YAML
configuration file. If `jircd.tls.keystore` is unset — the default, since nothing wires it from
config — `selfSignedKeystorePath()` (lines 71-120) shells out to the JDK's own `keytool
-genkeypair` and writes a fresh PKCS12 keystore into a brand-new owner-only temp directory,
deleted on JVM exit. `ServerConfiguration.Listener` (`jircd-core/.../config/ServerConfiguration.
java:40`) is `record Listener(int port, boolean tls)` — no cert field exists at all.
Critically, `JircdServerApplication.start()`
(`jircd-server/src/main/java/net/jircd/server/JircdServerApplication.java:276-281`) falls back to
a **hardcoded default listener list, `[{6667, tls=false}, {6697, tls=true}]`, whenever
`configuration.listeners()` is empty** — meaning a server started with zero listener
configuration at all (the simplest possible quickstart) enables a TLS listener on 6697 by
default, which is exactly the path that triggers ephemeral self-signed generation today with no
administrator action required.

## Config schema for a listener's certificate (FR-001, FR-002, FR-005)

**Decision**: Extend `ServerConfiguration.Listener` with four new optional fields:
`certPath`/`keyPath` (the PEM form, Clarifications) and `keystorePath`/`keystorePassword` (the
PKCS12 form, Clarifications) — `record Listener(int port, boolean tls, String certPath, String
keyPath, String keystorePath, String keystorePassword)`. Exactly one of the two forms — both
`certPath` and `keyPath` present, or `keystorePath` present — is valid for a listener with
`tls: true`; `keystorePassword` is REQUIRED whenever `keystorePath` is set, with no default
substituted (`ConfigurationLoader` rejects a `keystorePath` with no `keystorePassword` the same
way it rejects any other incomplete certificate configuration).

**Amendment**: The original decision here defaulted `keystorePassword` to `"changeit"` (the same
default the pre-004 `jircd.tls.keystorePassword` system property already used), reasoned as "not
a new design fork." Reconsidered after implementation: a PKCS12 keystore's password isn't just an
access gate, it's the key-derivation input for the password-based encryption protecting the
private key entry inside the file — a well-known default like `"changeit"` (flagged by CIS/OWASP
as a known-default-credential smell) means anyone who obtains the `.p12` file also trivially
obtains the key. No default is substituted now; the administrator must supply one explicitly.

**Rationale**: A `Listener` is already the natural per-endpoint scope (`tls` itself is already
per-listener) — reusing it avoids introducing a second, parallel "certificate" entity that would
need its own cross-referencing back to a listener. Two mutually-exclusive optional field pairs
(rather than a single polymorphic "certificate" sub-object) keeps the YAML shape flat and
consistent with every other field in this config schema, none of which uses nested objects.

**Alternatives considered**: A dedicated top-level `certificates:` map keyed by an id, referenced
from a listener by name — rejected as unnecessary indirection for a schema where certificates
are never shared across listeners in this feature's scope (`Key Entities`, spec.md).

## Validation rules (FR-004, FR-006)

**Decision**: `ConfigurationLoader.parseListeners` gains these checks, each raising
`ConfigurationException` — the same exception every other malformed-config case in this method
already raises, refusing to start rather than degrading (see below):

1. If `tls == true` and neither `(certPath and keyPath)` nor `keystorePath` is present →
   reject: "listener on port N requests TLS but no certificate is configured."
2. If `tls == true` and exactly one of `certPath`/`keyPath` is present (not both) → reject: an
   incomplete PEM pair is a configuration error, not a partial success.
3. If `tls == true` and both a PEM pair and `keystorePath` are present → reject: ambiguous, two
   certificate sources for one listener.
4. If `tls == true`, the certificate is loaded and parsed **at startup** (FR-006), not lazily on
   first connection — an unreadable file, invalid PEM, or a key that doesn't match the
   certificate fails the same way, at the same time, as the checks above.
5. Cert-related fields present on a listener with `tls == false` are silently ignored, not
   rejected — matching this exact parser's own existing precedent of only reading recognized
   keys (`port`, `tls`) and never rejecting unrelated ones present in a listener's map.

**Rationale**: This project's `ConfigurationLoader` already has an established, tested
convention — malformed listener/rate-limit/length-limit/server-name values refuse startup with a
specific error naming the offending value (`specs/001-ircv3-server/contracts/server-
configuration.md` "Behavioral Contract"), never a silent default substitution or a partially
working state. A TLS listener silently missing its certificate is exactly that same class of
problem; reusing the identical validation posture needs no new philosophy.

**Alternatives considered**: Deferring cert validation to first-connection (lazy) — rejected;
FR-006 requires startup-time validation specifically, so a broken cert is visible immediately
rather than only when a client happens to attempt a TLS handshake, potentially long after
deployment.

## PEM parsing (FR-002)

**Decision**: Parse the certificate file with `java.security.cert.CertificateFactory
.getInstance("X.509").generateCertificate(InputStream)` directly against the PEM file's bytes —
`CertificateFactory` already accepts PEM's base64-with-header/footer encoding natively, no manual
stripping needed. Parse the private key by stripping the `-----BEGIN PRIVATE KEY-----`/`-----END
PRIVATE KEY-----` PEM delimiters, base64-decoding the body, and loading it via
`java.security.spec.PKCS8EncodedKeySpec` + `KeyFactory.getInstance("RSA")` (falling back to `EC`
if RSA parsing fails, covering both key types without requiring the administrator to specify
which). Once both are in hand, build an **in-memory** PKCS12 `KeyStore`
(`KeyStore.getInstance("PKCS12")`, `load(null, null)`, `setKeyEntry(...)`,
`setCertificateEntry(...)`) so the existing `KeyManagerFactory`/`SSLContext` construction in
`TlsSupport.buildServerContext()` needs no further change beyond swapping its input source — no
temp file is ever written for the PEM path.

**Rationale**: Let's Encrypt/certbot's `privkey.pem` is PKCS#8-encoded (`-----BEGIN PRIVATE
KEY-----`) by default, matching `PKCS8EncodedKeySpec` directly with no conversion. Building the
in-memory keystore rather than temp-file-and-load keeps the rest of the SSL bootstrap code
unchanged and, as a side benefit, removes the private-key-touches-disk-in-a-new-file exposure the
current self-signed path has to defend against (`TlsSupport.java`'s `OWNER_ONLY_DIR` comment,
CWE-379) — the PEM/keystore files the administrator points at are their own responsibility to
secure; jircd never writes a copy of the key anywhere.

**Alternatives considered**: The legacy PKCS#1 format (`-----BEGIN RSA PRIVATE KEY-----`) —
explicitly out of scope; certbot's default output is PKCS#8, and supporting PKCS#1 requires
manually re-wrapping the key in a PKCS#8 `PrivateKeyInfo` DER structure (a distinct, more
involved parsing path with its own failure modes) for a format the primary motivating workflow
doesn't produce. An administrator with a PKCS#1 key can convert it with `openssl pkcs8 -topk8
-nocrypt` — a single well-known command, not a jircd-side gap.

**Amendment**: A passphrase-encrypted PEM key (`-----BEGIN ENCRYPTED PRIVATE KEY-----`) is real,
if uncommon in practice — certbot itself never produces one (an encrypted key needs a human to
type a passphrase on every unattended renewal/restart, defeating the point of automated renewal),
but nothing stops an administrator from manually re-encrypting certbot's output, or using a
different ACME client that does. Still out of scope for decryption support (spec.md Assumptions),
but `readPemPrivateKey` now detects both the encrypted-PKCS#8 and legacy-PKCS#1 headers before
attempting to parse, and fails with a specific message naming which unsupported format was found
and the `openssl pkcs8` command to fix it — instead of the generic `InvalidKeySpecException`
either would otherwise produce, which gave no indication of *why* the key was unreadable.

## The zero-config default listener list (FR-003, FR-004 applied)

**Decision**: Change `JircdServerApplication.start()`'s hardcoded empty-config fallback from
`[{6667, tls=false}, {6697, tls=true}]` to `[{6667, tls=false}]` only — a server started with no
`listeners` configured at all now runs plaintext-only, with no TLS listener and therefore no
certificate requirement.

**Rationale**: This is the direct, unavoidable consequence of FR-003/FR-004 applied to the one
place they'd otherwise silently break: today, *not configuring anything* is exactly what
triggers the ephemeral self-signed certificate on port 6697, since the hardcoded default already
requests TLS. Leaving that default in place after this feature would mean the simplest possible
quickstart (no config file at all) now fails to start outright — a real functionality regression,
not a bug fix. Removing the default TLS entry preserves "the server starts and works with zero
configuration" while remaining fully consistent with "TLS is opt-in and only enabled once a
certificate is explicitly configured."

**Alternatives considered**: Keeping the default TLS entry and letting the zero-config case fail
to start — rejected, since it breaks the existing, documented "just run it" experience for a
capability (encrypted connections) the administrator never asked for in the first place.

## Removing the system-property path (FR-005)

**Decision**: Delete `TlsSupport`'s reliance on `jircd.tls.keystore`/`jircd.tls.keystorePassword`
system properties and `selfSignedKeystorePath()`'s `keytool`-shelling entirely.
`TlsListener`'s constructor changes from calling the no-argument `TlsSupport.buildServerContext()`
to passing the resolved `ServerConfiguration.Listener` (or its cert fields) through explicitly.

**Rationale**: Per Clarifications, the system-property mechanism is removed outright — it bypasses
the same configuration file FR-001 requires everything to be visible through, and its removal
also deletes the external `keytool` process dependency, a net simplification.

## Pre-existing, out-of-scope: single `tlsListener` field

**Note, not a decision**: `JircdServerApplication` tracks only one `tlsListener` field
(`.../JircdServerApplication.java:89`), overwritten inside the `for` loop over configured
listeners (line 284) — if more than one `tls: true` listener were ever configured, only the last
one stays reachable via `stop()`. This predates this feature (nothing here changes how many TLS
listeners can be configured or iterated) and spec.md's scope doesn't ask for multi-TLS-listener
support — left untouched, noted here only so it isn't mistaken for something this feature was
supposed to fix.
