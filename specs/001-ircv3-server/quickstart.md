# Quickstart: Validating the Modular IRCv3 Chat Server

Validation guide for the in-scope stories (1, 2, 4, 5, 6). Command/reply
details are defined in [contracts/](./contracts/); entity/state details in
[data-model.md](./data-model.md). This guide proves the feature works
end-to-end — it does not contain implementation code.

## Prerequisites

- JDK 25 installed and on `PATH`.
- A raw TCP client for manual protocol checks — `nc localhost 6667` (or any
  terminal `telnet`/`nc` equivalent) is sufficient; a real IRC client (e.g.,
  WeeChat, HexChat) is preferable for the constitution's required manual
  usage-scenario check (Principle III).
- No external services required — this release has no database or
  external account system dependency (see data-model.md "Storage: N/A").

## Setup

```bash
./gradlew build               # compiles all subprojects (jircd-protocol, jircd-core,
                               # jircd-capabilities:*, jircd-server-extensions:*, jircd-server),
                               # runs unit tests + static analysis
./gradlew :jircd-server:run   # starts the server using the default config (see contracts/server-configuration.md)
```

Default listeners: `6667` (plaintext) and `6697` (TLS), per the example
schema in `contracts/server-configuration.md`.

## Story 1 — Connect and Chat in Real Time

1. Open two terminals, each: `nc localhost 6667`.
2. In terminal A: send `NICK alice` then `USER alice 0 * :Alice`.
   - **Expected**: `001` welcome reply (contracts/irc-numeric-replies.md).
3. In terminal B: send `NICK alice` (same name, before A registers a
   different one).
   - **Expected**: `433 ERR_NICKNAMEINUSE` — validates FR-002's atomic
     uniqueness.
4. In terminal B: send `NICK bob` then `USER bob 0 * :Bob`, then
   `JOIN #lobby`.
5. In terminal A: send `JOIN #lobby`, then `PRIVMSG #lobby :hello`.
   - **Expected**: terminal B receives the `PRIVMSG` within ~1s (SC-002),
     with the sender prefix in `alice!<ident>@<hostname>` form (FR-030).
6. Close terminal A's connection (Ctrl-C).
   - **Expected**: terminal B sees a `PART`/`QUIT` notification for alice
     (FR-017).

## Story 2 — Discover and Use Enhanced Capabilities

1. `nc localhost 6667`, send `CAP LS 302`.
   - **Expected**: response lists exactly `message-tags`, `server-time`,
     `echo-message` (FR-025) — no `sasl` or other capability.
2. Send `CAP REQ :server-time echo-message`, then `CAP END`, then register
   (`NICK`/`USER`) and `JOIN #lobby`.
3. Send `PRIVMSG #lobby :hi` from this same connection.
   - **Expected**: this connection receives its own message echoed back
     with a `server-time` tag (`echo-message` + `server-time` negotiated,
     FR-007), while a second, plain (non-negotiating) connection in the
     same channel receives the message without tags (FR-008).

## Story 4 — Tailor the Server with Optional Extensions

1. With the server running, edit the config file's
   `capabilities.message-tags` from `enabled` to `disabled`, then trigger a
   reload by sending the server process `SIGHUP` (e.g., `kill -HUP <pid>`)
   — reload is manually triggered, never automatic (research.md
   "Configuration reload mechanism"). No IRC connection is needed for this
   step, keeping this path independent of Story 6.
2. From a capability-negotiating connection (as in Story 2), request
   `CAP LS 302` again.
   - **Expected**: within SC-005's 1-minute budget, `message-tags` no
     longer appears in the offered capability list, and messages stop
     carrying `message-tags` metadata — no server restart occurred (verify
     the process was never stopped/started). Note that `KICK` (core
     moderation, FR-036) remains available throughout — it is not part of
     `capabilities` or `server-extensions` at all.
3. Re-enable `message-tags` in the file, send `SIGHUP` again, and confirm
   it's offered again without a restart.
4. Edit the config with an invalid value and send `SIGHUP` — confirm each
   of the following produces a specific error naming the offending key
   (surfaced in the server's log, since there's no IRC session in this
   scenario), and that the server keeps running on its previously-valid
   configuration rather than crashing or partially applying the change
   (FR-012, SC-008):
   - `capabilities.nonexistent: enabled` (unknown id)
   - `capabilities.moderation: disabled` (moderation isn't an extension at
     all, see FR-035/FR-036)
   - `capabilities.cloak: enabled` (`cloak` is a `ServerExtension` — wrong
     section; belongs under `server-extensions`, see
     contracts/server-configuration.md "Section/kind mismatch")

## Story 5 — Moderate a Channel

1. As the operator (first joiner, per FR-013) of `#lobby`, run `KICK
   #lobby bob :spamming`.
   - **Expected**: bob's session sees the `KICK`; both sessions receive a
     `KICK` notification.
2. As a non-operator member, attempt `KICK #lobby alice`.
   - **Expected**: `482 ERR_CHANOPRIVSNEEDED`, and alice is not removed.
3. Confirm `KICK` is available immediately after server startup with no
   configuration step required — validates FR-036 (core, always present,
   not something an administrator needs to enable).

## Story 6 — Administer the Server via IRC Commands

1. As a connected, registered client (not yet privileged), attempt
   `EXTENSION DISABLE message-tags`.
   - **Expected**: `481 ERR_NOPRIVILEGES` — validates FR-033.
2. Send `OPER root-admin <configured-password>`.
   - **Expected**: `381 RPL_YOUREOPER`. Retry with a wrong password first
     to confirm `464 ERR_PASSWDMISMATCH` (and check it was logged, FR-019).
3. Now privileged, send `EXTENSION DISABLE message-tags`.
   - **Expected**: same observable effect as Story 4 Step 2 — validates the
     "path equivalence" contract note in `contracts/server-configuration.md`,
     with no config file edit involved. Attempting `EXTENSION DISABLE
     moderation` or `EXTENSION DISABLE capability-negotiation` MUST fail
     with an error naming them as unknown/non-toggleable, not silently
     succeed.
4. Enable the `cloak` extension (via either path), have a client join a
   channel, then send `WHOHOST <that nickname>` as the privileged session.
   - **Expected**: the real hostname is returned, even though other
     channel members see the obfuscated form in that client's message
     prefixes (FR-031) — validates Story 6 Acceptance Scenario 4.
5. Edit the config file's `rateLimit.bucketSize` to a new value, then —
   without restarting or sending `SIGHUP` — send `REHASH` as the
   privileged session.
   - **Expected**: `382 RPL_REHASHING`, and the new rate limit is in
     effect — the in-band equivalent of Story 4's `SIGHUP` path (research.md
     "Configuration reload mechanism"), with no shell access used.
6. Introduce an invalid value into the config file (e.g.,
   `capabilities.nonexistent: enabled`) and send `REHASH` again.
   - **Expected**: a specific error naming the offending key is returned
     directly to this session (unlike Story 4 Step 4's log-only error,
     since there's an active IRC session to reply to here), and the
     server continues running on its previous, still-valid configuration.

## Out of Scope for This Validation Pass

Story 3 (authentication) and everything depending on it (account module,
registered-channel/nickname priority, federation) are deferred — there is
nothing to validate for them in this release; see spec.md's Clarifications
for the deferral rationale.
