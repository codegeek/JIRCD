# Quickstart: Validating TLS Certificate Handling

Validation guide for this feature's six requirements. Same prerequisites as
`specs/001-ircv3-server/quickstart.md` — JDK 25, a raw TCP/TLS client, a running
`./gradlew :jircd-server:run` instance — plus `openssl` to generate a throwaway PEM cert/key pair
and a PKCS12 keystore for testing.

## Setup: generate a throwaway PEM cert/key pair and a PKCS12 keystore

```sh
openssl req -x509 -newkey rsa:2048 -keyout /tmp/privkey.pem -out /tmp/fullchain.pem \
  -days 365 -nodes -subj "/CN=test.jircd.local"
openssl pkcs12 -export -in /tmp/fullchain.pem -inkey /tmp/privkey.pem \
  -out /tmp/keystore.p12 -passout pass:changeit
```

## FR-001/FR-002 — PEM cert/key loads and is used

1. Configure a listener:
   ```yaml
   listeners:
     - port: 6697
       tls: true
       certPath: /tmp/fullchain.pem
       keyPath: /tmp/privkey.pem
   ```
2. Start the server, connect a TLS client to port 6697.
   - **Expected**: the handshake succeeds; the presented certificate's subject is
     `CN=test.jircd.local`.

## FR-003/FR-004 — TLS listener without a cert refuses to start

1. Configure a listener with `tls: true` and no `certPath`/`keyPath`/`keystorePath`.
2. Start the server.
   - **Expected**: the server refuses to start, with an error naming the offending listener's
     port — no ephemeral certificate is generated, no TLS listener silently comes up.

## FR-005 — PKCS12 keystore loads and is used

1. Configure a listener:
   ```yaml
   listeners:
     - port: 6698
       tls: true
       keystorePath: /tmp/keystore.p12
       keystorePassword: changeit
   ```
2. Start the server, connect a TLS client to port 6698.
   - **Expected**: the handshake succeeds; the presented certificate's subject is
     `CN=test.jircd.local`.

## FR-006 — startup-time validation, not lazy

1. Configure a listener with `tls: true` and a `certPath` pointing at a file that doesn't exist.
2. Start the server.
   - **Expected**: the server refuses to start immediately, before any client ever attempts a
     connection — the error is visible in startup logs, not only on a client's failed handshake.

## SC-001 — certificate identity persists across a restart

1. Configure a listener with a real PEM cert/key pair (as in FR-001/FR-002).
2. Start the server, connect a TLS client, record the certificate's fingerprint (e.g. via
   `openssl x509 -in <(openssl s_client -connect localhost:6697 </dev/null 2>/dev/null) -noout
   -fingerprint`).
3. Stop the server, start it again (unchanged configuration).
4. Connect again, record the fingerprint.
   - **Expected**: the two fingerprints are identical.

## Automated cross-check

Run `./gradlew :jircd-integration-tests:test --tests
"net.jircd.integration.TlsCertificateConfigTest"` and confirm every FR-001 through FR-006 test
passes, plus the restart-identity test (SC-001).
