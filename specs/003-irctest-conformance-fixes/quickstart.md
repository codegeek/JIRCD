# Quickstart: Validating the irctest Conformance Fixes

Validation guide for this feature's seven corrections. Same prerequisites as
`specs/001-ircv3-server/quickstart.md` — JDK 25, a raw TCP client, a running
`./gradlew :jircd-server:run` instance.

## FR-001 — QUIT sends ERROR

1. Register a client, send `QUIT :bye`.
   - **Expected**: an `ERROR` line arrives before the connection closes.

## FR-002 — Empty realname rejected

1. Send `NICK alice`, then `USER alice 0 * :` (empty trailing realname).
   - **Expected**: `461 ERR_NEEDMOREPARAMS`, not `001`.

## FR-003 — NAMES/JOIN visibility symbol

1. Register two clients, `JOIN #lobby` as both.
2. As an operator in `#lobby`, `MODE #lobby +s`.
3. Either client sends `NAMES #lobby`.
   - **Expected**: `353` shows `@` as the symbol before `#lobby` (not `=`).
4. `MODE #lobby -s+p`, repeat `NAMES #lobby`.
   - **Expected**: `353` shows `*`.
5. `MODE #lobby -p`, repeat `NAMES #lobby`.
   - **Expected**: `353` shows `=`.

## FR-004 — LUSERS text shape

1. Register a client, send `LUSERS`.
   - **Expected**: `251` reads `"There are N users and M invisible on 1 servers"`, with `M`
     matching however many currently-connected clients have `+i` set.

## FR-005 — WHOWAS numeric

1. Register a client, send bare `WHOWAS` (no argument).
   - **Expected**: `431 ERR_NONICKNAMEGIVEN`, not `461`.

## FR-006/FR-007 — WHO/WHOIS server-name field

1. Register two clients, `alice` and `bob`.
2. As `bob`, send `WHO alice`.
   - **Expected**: `352`'s field layout includes the server's own name between the hostname
     and nickname fields.
3. As `bob`, send `WHOIS alice`.
   - **Expected**: a `312 RPL_WHOISSERVER alice <server-name> :<info>` line appears after
     `311`.
4. As `bob`, send `WHOIS <server-name> alice` (the two-parameter form).
   - **Expected**: identical result to step 3 — the leading server-name argument is accepted
     and the reply still targets `alice`, not a `401 ERR_NOSUCHNICK` for a nickname called
     `<server-name>`.

## FR-008/FR-009 — Confirmed unchanged

1. Send `NAMES` for a channel name that was never created.
   - **Expected**: `403 ERR_NOSUCHCHANNEL` (same as a `private`/`secret` channel a
     non-member can't see) — unchanged.
2. Attempt to register a nickname containing UTF-8 non-ASCII characters (e.g. `Işıl`).
   - **Expected**: `432 ERR_ERRONEUSNICKNAME` — unchanged.

## Automated cross-check

Run the irctest suite (via `github.com/jircd/irctest`'s `irctest.controllers.jircd`
controller, `--timeout=60 --timeout-method=signal`) and confirm the specific test cases that
originally surfaced each finding now pass:
`connection_registration.py::testQuitErrors`,
`connection_registration.py::testEmptyRealname`,
`who.py`/`names.py` (visibility-symbol assertions),
`lusers.py` (reply-parsing assertions),
`whowas.py` (missing-nickname-argument assertion),
`who.py` (server-field assertions),
`whois.py::testWhoisUser[target_server]`.
