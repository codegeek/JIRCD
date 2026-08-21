# Quickstart: Validating the Connection Monitoring Log

Validation guide for this feature's 11 requirements. Same prerequisites as
`specs/001-ircv3-server/quickstart.md` — JDK 25, a raw TCP client, a running
`./gradlew :jircd-server:run` instance with its console/log output visible.

## Story 1 — Connect/disconnect monitoring log entries (FR-001 through FR-004, FR-006 through FR-008)

1. Start the server and watch its log output.
2. Connect a client (`NICK`/`USER`), then disconnect it (`QUIT` or close the socket).
   - **Expected**: a `connection-event=connected connection=<token> remoteAddress=...` line
     appears at connect time, and a `connection-event=disconnected connection=<token>
     durationMs=... reason=...` line appears at disconnect time — both lines carry the
     **identical** token, and that token is not of the form `c1`/`c2`/... (confirm it looks
     like a UUID: `8-4-4-4-12` hex groups).
3. Trigger a security-relevant event instead (e.g. a failed `OPER`) and confirm it still
   appears as a `security-event=...` line, distinct from the `connection-event=...` lines —
   the two facilities are separate (FR-006).

## Story 2 — PING carries the same token, and its frequency is configurable (FR-005, FR-008 through FR-011)

1. With no `keepAliveFrequencySeconds` configured, connect a client, note the token from its
   `connection-event=connected` log line, and stay idle.
   - **Expected**: after 120 seconds of inactivity, the server sends `PING :<token>` — the
     exact same token as the log entry.
2. Configure `keepAliveFrequencySeconds: 5` and repeat.
   - **Expected**: the server-initiated `PING` now arrives after roughly 5 seconds of
     inactivity instead of 120.
3. Configure `keepAliveFrequencySeconds: 0` (or a negative value, or a value above the
   ceiling) and attempt to start the server.
   - **Expected**: the server refuses to start, with a specific error naming
     `keepAliveFrequencySeconds`.

## Story 3 — Tokens reveal nothing about connection order or count (FR-002)

1. Connect three clients in quick succession and collect their three tokens from the
   monitoring log.
   - **Expected**: the three tokens share no arithmetic or lexical relationship (unlike the
     old `c1`/`c2`/`c3` scheme) — nothing about them reveals which connected first, second,
     or third, or that exactly three connections have occurred.

## Automated cross-check

Run the full `jircd-integration-tests` and `jircd-core` test suites and confirm no
regression — in particular, `KeepAliveLoadTest` (which now configures its own short
`keepAliveFrequencySeconds` rather than relying on the new 120-second default) and every
existing test that constructs a `ClientSession` directly with a fixed `"c1"`-style
connection id for its own fixture purposes, both confirmed unaffected by the token
generation change (research.md "Test impact of changing the default idle interval").
