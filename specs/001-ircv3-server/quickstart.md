# Quickstart: Validating the Modular IRCv3 Chat Server

Validation guide for the in-scope stories (1, 2, 4, 5, 6, 7). Command/reply
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
   Then send `USER alice 0 * :Alice` again.
   - **Expected**: `462 ERR_ALREADYREGISTRED` — no second burst arrives
     (FR-001).
3. In terminal B: send `NICK alice` (same name, before A registers a
   different one).
   - **Expected**: `433 ERR_NICKNAMEINUSE` — validates FR-002's atomic
     uniqueness.
4. In terminal B: send `NICK bob` then `USER bob 0 * :Bob`, then
   `JOIN #lobby`.
5. In terminal A: send `JOIN #lobby`, then `PRIVMSG #lobby :hello`.
   - **Expected**: terminal B receives the `PRIVMSG` within ~1s (SC-002),
     with the sender prefix in `alice!<ident>@<hostname>` form (FR-030).
6. As alice (the operator, first to join per FR-013), send `TOPIC #lobby
   :Welcome to #lobby`.
   - **Expected**: both terminals see the `TOPIC` change (FR-040).
7. As bob (not an operator), send `TOPIC #lobby :hijacked`.
   - **Expected**: `482 ERR_CHANOPRIVSNEEDED`; the topic is unchanged.
8. Open a third terminal, register as `carol`, and — without joining
   `#lobby` — send `TOPIC #lobby`, `NAMES #lobby`, and `LIST`.
   - **Expected**: `332 RPL_TOPIC` showing the current topic, `353`/`366`
     showing alice and bob as members, and a `322 RPL_LIST` entry for
     `#lobby` followed by `323 RPL_LISTEND` — all without carol having
     joined the channel (FR-040/FR-041/FR-042).
9. As bob (terminal B), send `QUIT :heading out`.
   - **Expected**: bob's connection closes; carol (still connected, not
     in `#lobby`) sees nothing since she never joined; re-`JOIN #lobby`
     as carol and confirm bob is no longer listed (FR-017). This
     exercises `QUIT` with an explicit reason (FR-060).
10. Close terminal A's connection (Ctrl-C), without sending `QUIT`.
    - **Expected**: carol (now in `#lobby`) sees a `QUIT` notification
      for alice with a non-blank, server-generated reason, even though
      alice never sent `QUIT` herself (FR-017, FR-060).

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
     same channel receives the message without tags (FR-008). Note the
     `msgid` value on the echoed message.
4. From a *third* connection that negotiated only `message-tags` (`CAP
   REQ :message-tags`, no `server-time`, no `echo-message`), join
   `#lobby` and observe the `PRIVMSG #lobby :hi` sent in Step 3 arrive
   with a `msgid` tag but no `time` tag — `msgid` isn't gated behind
   `server-time` (FR-059). Send a second `PRIVMSG #lobby :hi again` from
   Step 2's connection and confirm its `msgid` differs from Step 3's.

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
4. As the operator of `#lobby` (bob still a member from Story 1), send
   `MODE #lobby +b bob`.
   - **Expected**: `MODE` confirmation echoed to members. Then, as bob,
     send `PRIVMSG #lobby :can you hear me`.
   - **Expected**: `404 ERR_CANNOTSENDTOCHAN`; bob is still listed in
     `NAMES #lobby` — muted, not removed (FR-062).
5. Have bob `PART #lobby`, then attempt `JOIN #lobby` again while the
   ban is still active.
   - **Expected**: `474 ERR_BANNEDFROMCHAN`.
6. As the operator, send `MODE #lobby b`.
   - **Expected**: `367 RPL_BANLIST #lobby bob!*@* <operator-nick>
     <timestamp>` (the partial mask `bob` was normalized to `bob!*@*`),
     then `368 RPL_ENDOFBANLIST`.
7. As the operator, send `MODE #lobby -b bob!*@*`, then have bob `JOIN
   #lobby` again.
   - **Expected**: the join succeeds normally — validates FR-062's
     removal path and Story 5 Acceptance Scenario 7 end-to-end.
8. Enable the `cloak` extension (Story 4's config-file/`SIGHUP` path or
   Story 6's `EXTENSION` command), have bob rejoin `#lobby`, and note
   the cloaked hostname other members now see in his message prefixes.
   As the operator, send `MODE #lobby +b *!*@<bob's real hostname>` (the
   *real*, uncloaked value — obtain it via `WHOHOST bob` as a
   privileged session, per Story 6 Step 4, or from server logs).
   - **Expected**: bob is muted (`404 ERR_CANNOTSENDTOCHAN` on his next
     `PRIVMSG`) even though the mask does not match his cloaked,
     publicly-visible hostmask — validates FR-062's dual-matching and
     Story 5 Acceptance Scenario 8: a ban targeting a real hostname/IP
     is not evadable via cloaking.
9. With alice and carol both members of `#lobby` (neither an operator),
   as the operator send `MODE #lobby +ov alice carol` in one command.
   - **Expected**: a single `MODE` echo reflecting both changes; `NAMES
     #lobby` shows alice with the operator prefix and carol with the
     voice prefix — validates FR-064's multi-flag, multi-parameter
     grouping (RFC 2812 §3.2.3).
   Next, send `MODE #lobby +ov-b dave eve bob!*@*` where `dave` is not
   currently a member.
   - **Expected**: `eve` still receives voice (the first flag/parameter
     pair processed) and `bob!*@*` is still removed from the ban list
     (the third pair), but the command stops at `441
     ERR_USERNOTINCHANNEL` for `dave` — confirms processing is left to
     right and non-atomic: an earlier flag's success is not undone by a
     later flag's failure (FR-064).
10. Lower the configured cap — set `maxModesPerCommand: 2` in the config
    file and trigger a reload (Story 4's `SIGHUP`/`REHASH` path) — then
    as the operator send `MODE #lobby +bbb mask1!*@* mask2!*@*
    mask3!*@*` (three ban masks in one command).
    - **Expected**: `MODE #lobby b` afterward shows only `mask1` and
      `mask2` on the list; there is no error reply naming the third —
      the `MODE` echo simply reflects the two that were applied,
      validating FR-064's silent-truncation behavior at the configured
      `maxModesPerCommand` limit and the `MODES` token in `005
      RPL_ISUPPORT` advertising `2`.

## Story 6 — Administer the Server via IRC Commands

1. As a connected, registered client (not yet privileged), attempt
   `EXTENSION DISABLE message-tags`.
   - **Expected**: `481 ERR_NOPRIVILEGES` — validates FR-033.
2. Send `OPER root-admin <configured-password>`.
   - **Expected**: `381 RPL_YOUREOPER`. Retry with a wrong password first
     to confirm `464 ERR_PASSWDMISMATCH` (and check it was logged, FR-019).
     Then send `MODE <your-nick>` (no mode string) and confirm
     `221 RPL_UMODEIS :+o` — validates FR-044's OPER-grants-`+o` link.
     From a *different*, non-privileged client, send `WHOIS <your-nick>`
     and confirm `313 RPL_WHOISOPERATOR` appears — operator status is
     visible to any client, not just administrators (FR-037). Then send
     `MODE <your-nick> -o` and confirm a subsequent admin command (e.g.
     `EXTENSION`) from that same session is rejected with
     `481 ERR_NOPRIVILEGES` — self-revocation actually revokes the
     privilege, not just the display flag.
3. Send `OPER root-admin <configured-password>` again to restore
   privilege, since Step 2's self-revocation just removed it. Now
   privileged, send `EXTENSION DISABLE message-tags`.
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
7. As a non-privileged client, send `SAJOIN #staff-only`.
   - **Expected**: `481 ERR_NOPRIVILEGES` — `SAJOIN` is admin-only, same
     as every other command in this section (FR-057).
8. As the privileged session, send `SAJOIN #staff-only`.
   - **Expected**: joins the channel via the normal `JOIN`/`353`/`366`
     replies — validates FR-057. (`ban-mask` is this release's one
     `JOIN`-gating flag, FR-062; if `#staff-only` has no active ban
     matching this session, this step still just confirms `SAJOIN`
     behaves like `JOIN` — see Steps 11-13 below for `SAJOIN` actually
     bypassing an active ban.)
9. Have two ordinary clients join `#busy`, letting the first become its
   operator (FR-013); have the privileged session `JOIN #busy` normally
   (now a member, but not an operator), then send `SAMODE #busy +o`.
   - **Expected**: the privileged session is granted operator status
     immediately, without any action from `#busy`'s existing operator —
     validates FR-058 and Story 6 Acceptance Scenario 5. Sending
     `SAMODE #busy +o` from the *non-privileged* first operator MUST
     still fail with `481 ERR_NOPRIVILEGES` — `SAMODE` is not a new way
     for a regular operator to do anything they couldn't already do via
     `MODE`.
10. As the privileged session, send `SAMODE #nonmember-channel +o` for a
    channel it has not joined.
    - **Expected**: `442 ERR_NOTONCHANNEL` — `SAMODE` does not implicitly
      join; `SAJOIN` (Step 8) is the separate step for that.
11. As `#busy`'s own operator (from Step 9, not the privileged session),
    send `MODE #busy +b *!*@*.example.com`.
    - **Expected**: `MODE` confirmation echoed to `#busy`'s members —
      an ordinary channel operator can already ban by host/IP pattern,
      no administrator involvement needed (FR-062).
12. Have a third client whose hostname matches `*.example.com` attempt
    `JOIN #busy`.
    - **Expected**: `474 ERR_BANNEDFROMCHAN`.
13. As the privileged session (still not a member of `#busy`), send
    `SAJOIN #busy`.
    - **Expected**: succeeds despite the same ban that just blocked
      Step 12's client — `SAJOIN` bypasses `ban-mask`'s `JOIN` gate the
      same as it would bypass any other `JOIN`-gating flag (FR-057).

## Story 7 — Look Up Information About a User

1. As `alice` (connected, registered, not privileged), send `WHOIS`
   (no target).
   - **Expected**: `311 RPL_WHOISUSER` showing alice's own real
     hostname/IP, then `318 RPL_ENDOFWHOIS` — even if the `cloak`
     extension is currently enabled and obscuring alice's hostname from
     everyone else (FR-038 case 1).
2. As `bob` (connected, registered, not privileged, and not alice), send
   `WHOIS alice`.
   - **Expected**: `311` shows alice's *presented* hostname — the same
     value bob already sees in alice's message prefixes (cloaked if
     `cloak` is enabled, real otherwise) — never alice's real hostname/IP
     if it differs from that (FR-038 case 3).
3. As a privileged session (per Story 6's `OPER`), send `WHOIS alice`.
   - **Expected**: `311` shows alice's real, unobfuscated hostname/IP,
     regardless of `cloak` state (FR-038 case 2) — the same value
     `WHOHOST alice` would return.
4. Send `WHOIS nonexistent-nick`.
   - **Expected**: `401 ERR_NOSUCHNICK`, no user data returned.
5. Have alice and bob both `JOIN #lobby` (from Story 1). As a third
   client, carol (not a member), send `WHO #lobby`.
   - **Expected**: one `352 RPL_WHOREPLY` for alice and one for bob, then
     `315 RPL_ENDOFWHO` — the same membership `NAMES #lobby` would show
     (FR-061).
6. As bob, send `MODE bob +i`.
   - **Expected**: `MODE` confirmation, no error — any client may set
     `invisible` on itself freely (FR-044).
7. As carol (not sharing a channel with bob — leave `#lobby` first if
   carol joined it in Step 5), send `WHO bob` (exact nickname) and
   `WHO b*` (mask).
   - **Expected**: both return bare `315 RPL_ENDOFWHO` with no `352` —
     bob is invisible and carol shares no channel with him (FR-061).
8. As carol, `JOIN #lobby`, then repeat `WHO bob`.
   - **Expected**: now returns a `352` match — sharing a channel with an
     invisible user makes them visible to `WHO` again (FR-061).
9. As a privileged session (per Story 6's `OPER`), send `WHO bob` without
   joining `#lobby`.
   - **Expected**: returns a `352` match — administrator privilege also
     bypasses the `invisible` exclusion (FR-032/FR-047's pattern, reused
     by FR-061).
10. Edit the config file to add `whoMaskEnabled: false`, then `REHASH`
    as the privileged session.
    - **Expected**: `382 RPL_REHASHING`.
11. As a non-privileged client that has *not* set `+i`, send
    `WHO <its own nickname>*` (mask) and bare `WHO`.
    - **Expected**: both now return bare `315 RPL_ENDOFWHO` with no
      `352` lines, even though this client isn't invisible — the
      restriction is server-wide, not tied to any individual's
      `invisible` state (FR-061). The same client's exact-nickname
      `WHO` and `WHO #lobby` still return normal results, unaffected.
12. As the privileged session, repeat a mask `WHO`.
    - **Expected**: still returns real matches — administrators are
      exempt from `whoMaskEnabled` regardless of its value (FR-061).

## Out of Scope for This Validation Pass

Story 3 (authentication) and everything depending on it (account module,
registered-channel/nickname priority, federation) are deferred — there is
nothing to validate for them in this release; see spec.md's Clarifications
for the deferral rationale.
