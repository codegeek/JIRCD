# Quickstart: Validating the Conformance Bug Batch

Validation guide for this feature's 23 requirements. Same prerequisites as
`specs/001-ircv3-server/quickstart.md` — JDK 25, a raw TCP client, a running
`./gradlew :jircd-server:run` instance with `server-extensions: admin: enabled` and at least
one `administratorCredentials` entry configured (needed for Story 6's `OPER` scenario).

## Story 1 — NICK broadcast (FR-001/FR-002)

1. Register `alice` and `bob`, both `JOIN #lobby`.
2. `alice` sends `NICK alicia`.
   - **Expected**: `alice` receives her own `NICK alicia` confirmation; `bob` receives a
     `NICK alicia` notification for the same change.

## Story 2 — Direct-message delivery guarantees (FR-003/FR-004)

1. Register `alice` and `bob`. `alice` negotiates `echo-message` (`CAP REQ :echo-message`,
   `CAP END`).
2. `alice` sends `PRIVMSG bob :hi`.
   - **Expected**: `alice` receives her own `PRIVMSG bob :hi` echoed back, the same as a
     channel message already would.
3. `alice` sends `PRIVMSG bob :`.
   - **Expected**: `412 ERR_NOTEXTTOSEND`, no message delivered.

## Story 3 — Connection and capability-negotiation precision (FR-005 through FR-012)

1. `alice` sends `PING abc`.
   - **Expected**: `PONG <server-name> abc` (two params, in that order).
2. `alice` sends bare `PING`.
   - **Expected**: `409 ERR_NOORIGIN`, no fabricated token.
3. `alice` negotiates `message-tags` only, sends `CAP LIST`.
   - **Expected**: the reply lists only `message-tags`, not the server's full offered set.
4. `alice` sends `CAP BOGUS`.
   - **Expected**: `410 ERR_INVALIDCAPCMD`.
5. `alice` sends `CAP REQ :foo bar foo`.
   - **Expected**: the `NAK` reply echoes back `foo bar foo`, not deduplicated to `foo bar`.
6. `alice` and `bob` both negotiate `message-tags`; `alice` sends
   `@+example-tag=value PRIVMSG bob :hi`.
   - **Expected**: `bob` receives the message still carrying `+example-tag=value`.
7. Send a line whose tag section alone exceeds 4096 bytes but whose total length is under
   4608 bytes.
   - **Expected**: `417 ERR_INPUTTOOLONG`.
8. Before registration, send `USER user 0 * :` with an invalid UTF-8 byte sequence in the
   realname field.
   - **Expected**: the connection receives `ERROR` and is closed — not left open with no
     further response.

## Story 4 — Channel-membership grammar completeness (FR-013 through FR-017)

1. `alice` sends `JOIN #one,#two`.
   - **Expected**: `alice` becomes a member of both `#one` and `#two`.
2. `bob` sets a topic on `#lobby` (`TOPIC #lobby :hello`); `carol` then `JOIN #lobby`.
   - **Expected**: `carol` receives `332`/`333` for `#lobby`'s topic as part of joining.
3. A channel operator sends `KICK #lobby carol` with no comment.
   - **Expected**: the relayed `KICK` includes the operator's own nickname as the comment.
4. A member sends `MODE #lobby +b`.
   - **Expected**: the channel's ban list is returned (`367`/`368`), not `461`.
5. A channel operator sends `MODE #lobby +o nonexistent-nick` (a nickname that isn't
   connected to the server at all).
   - **Expected**: `401 ERR_NOSUCHNICK`, not `441 ERR_USERNOTINCHANNEL`.

## Story 5 — Information-query completeness (FR-018 through FR-022)

1. `alice` sends `USERHOST bob`.
   - **Expected**: `302 RPL_USERHOST` with `bob`'s host information.
2. `alice` sends `INFO`.
   - **Expected**: `371 RPL_INFO` line(s) followed by `374 RPL_ENDOFINFO`, not `421`.
3. `alice` sends `WHOIS nonexistent-nick`.
   - **Expected**: `401 ERR_NOSUCHNICK` followed by `318 RPL_ENDOFWHOIS`.
4. `bob` sets `MODE bob +i`; `alice` (not sharing a channel with `bob`) sends `WHO bob` (exact
   nickname, not a mask).
   - **Expected**: `352 RPL_WHOREPLY` for `bob` is still returned.
5. `alice` sets away (`AWAY :brb`), then sends `AWAY :` (empty trailing argument).
   - **Expected**: `305 RPL_UNAWAY` — away status clears, same as bare `AWAY`.

## Story 6 — Operator self-notification (FR-023)

1. `alice` sends `OPER <configured-username> <configured-password>` with valid credentials.
   - **Expected**: in addition to `381 RPL_YOUREOPER`, `alice` receives an unsolicited
     `MODE alice +o`.

## Automated cross-check

Run the irctest suite (`github.com/jircd/irctest`'s `irctest.controllers.jircd` controller,
`--timeout=60 --timeout-method=signal`) and confirm every test named in the original triage
now passes: `regressions.py::testCaseChanges`/`testNickRelease`,
`echo_message.py::testDirectMessageEcho` (echo-message half only — see note below), `pingpong.py`,
`cap.py` (`testInvalidCapSubcommand`/`testNakExactString`/`testCapRemovalByClient`/
`testEmptyCapList`), `message_tags.py`, `messages.py`
(`testEmptyPrivmsg`/`testLineTooLong`/`testLineBeyondLimit`),
`connection_registration.py::testNonutf8Realname`, `utf8.py`
(`testNonutf8Realname`/`testNonutf8Username`), `join.py`, `kick.py`,
`chmodes/ban.py::testBanList`, `chmodes/operator.py`, `away.py`, `oper.py`,
`who.py::testWhoInvisible`, `whois.py::testWhoisMissingUser`, `info.py::testInfo` — with no
regression in any previously-passing test.

**Confirmed out of scope, not fixed by this feature** (verified during the T033 re-run —
each traces to a capability or mode this codebase has never implemented, not to any FR above):
`cap.py::testReqOne`/`testReqTwo`/`testReqOneThenOne`/`testReqPostRegistration` and every
`labeled_responses.py` test require the `multi-prefix`/`userhost-in-names`/`labeled-response`/
`batch` capabilities, none of which exist in `jircd-capabilities/*` — FR-009's NAK-dedup fix
only concerns capabilities the server actually offers.
`echo_message.py::testDirectMessageEcho`'s `label`-tag assertion depends on the same
unimplemented `labeled-response` capability (its earlier pass was a side effect of the FR-010
bug this feature fixed — client tags forwarded unconditionally, `label` included; the corrected,
capability-gated forwarding no longer masks the gap). `topic.py::testTopicMode`/
`TopicPrivilegesTestCase::testTopicPrivileges` and `chmodes/ban.py::testCaseInsensitive` depend
on already-documented exclusions (`+t` topic-lock, Ergo-specific ban-mask case sensitivity).
`messages.py::NoCTCPModeTestCase` and `utf8.py::ErgoUtf8NickEnabledTestCase` are Ergo-specific
(`+T` no-CTCP mode, PRECIS non-ASCII nicknames). `utf8.py::testNonUtf8Filtering` expects the
IRCv3 `standard-replies` `FAIL` mechanism, never implemented here. `info.py::testInfoNosuchserver`
exercises `INFO`'s deprecated remote-server-target form, out of scope for FR-019's single-server,
non-federated design.
