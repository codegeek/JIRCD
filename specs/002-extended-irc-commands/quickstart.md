# Quickstart: Validating the Extended IRC Commands

Validation guide for this feature's five user stories. Command/reply details are defined in
[contracts/irc-protocol-commands-extended.md](./contracts/irc-protocol-commands-extended.md);
entity/state details in [data-model.md](./data-model.md). This builds directly on
`001-ircv3-server`'s own quickstart — see that file's Setup for how to build and start the
server; this guide assumes a running instance and at least Story 1's registered clients.

## Prerequisites

Same as `specs/001-ircv3-server/quickstart.md` — JDK 25, a raw TCP client (`nc localhost
6667`), a running `./gradlew :jircd-server:run` instance with a valid `jircd.yaml`. To
exercise Story 3 (`KILL`) below, use the same administrator-credentials config that
feature's Story 6 walkthrough uses.

## Story 1 — Query Server Information

1. Register a client (`NICK alice`, `USER alice 0 * :Alice`).
2. Send `VERSION`.
   - **Expected**: `351 RPL_VERSION` with this server's name/version, immediately followed
     by one or more `005 RPL_ISUPPORT` lines — byte-identical to the `005` line(s) already
     seen in alice's own registration burst.
3. Send `TIME`.
   - **Expected**: `391 RPL_TIME` with the server's current local time.
4. Send `LUSERS`.
   - **Expected**: `251 RPL_LUSERCLIENT` and `254 RPL_LUSERCHANNELS`, with counts matching
     however many clients/channels are actually active right now.

## Story 2 — Set and See Away Status

1. With alice registered (Story 1), open a second terminal and register `bob`.
2. As alice: send `AWAY :gone for lunch`.
   - **Expected**: `306 RPL_NOWAWAY`.
3. As bob: send `PRIVMSG alice :hi`.
   - **Expected**: bob's own delivery still succeeds, plus bob additionally receives `301
     RPL_AWAY alice :gone for lunch`.
4. As bob: send `WHOIS alice`.
   - **Expected**: the reply includes `301 RPL_AWAY alice :gone for lunch` after `311
     RPL_WHOISUSER`.
5. Both join `#lobby` (`JOIN #lobby`). As bob: send `WHO #lobby`.
   - **Expected**: alice's `352 RPL_WHOREPLY` entry shows status `G`, not `H`.
6. As alice: send `AWAY` with no parameter.
   - **Expected**: `305 RPL_UNAWAY`. Repeating steps 3-5 now shows alice as present (`H`,
     no `301` on message or `WHOIS`).

## Story 3 — Administrator Forcibly Disconnects a Client

1. Complete `001-ircv3-server`'s Story 6 setup (an `admin`-enabled config with
   `administratorCredentials`) and `OPER` as configured.
2. Register an ordinary client, `carol` (`NICK carol`, `USER carol 0 * :Carol`), in another
   terminal; have carol `JOIN #lobby` alongside alice/bob from Story 2.
3. As a non-privileged client (e.g. bob, before `OPER`): send `KILL carol :test`.
   - **Expected**: `481 ERR_NOPRIVILEGES`; carol's connection is unaffected.
4. As the administrator: send `KILL carol :abusive behavior`.
   - **Expected**: carol's terminal receives `ERROR :abusive behavior` and the connection
     closes; `#lobby`'s remaining members see a disconnect notification distinguishable from
     an ordinary `QUIT`.
5. As the administrator: send `KILL doesnotexist`.
   - **Expected**: `401 ERR_NOSUCHNICK`.

## Story 4 — Look Up a Disconnected User's Last-Known Identity

1. Continuing from Story 3 (carol was just `KILL`ed): as any registered client, send
   `WHOWAS carol`.
   - **Expected**: `314 RPL_WHOWASUSER` showing carol's last-known ident/hostname/realname,
     then `369 RPL_ENDOFWHOWAS`.
2. Send `WHOWAS neverconnected`.
   - **Expected**: `406 ERR_WASNOSUCHNICK`, then `369 RPL_ENDOFWHOWAS`.
3. Reconnect as `carol` again, then `QUIT`. Send `WHOWAS carol` once more.
   - **Expected**: the reply now reflects the *second* session's identity (most recent),
     not the one `KILL`ed in Story 3.

## Story 5 — Send Metadata-Only Messages

1. Two clients (alice, bob from Story 2) both negotiate `message-tags` at registration
   (`CAP REQ :message-tags` before `CAP END`) and are both in `#lobby`.
2. As alice: send `@+example.com/typing=active TAGMSG #lobby`.
   - **Expected**: bob receives a line carrying the same tag, with `TAGMSG` as the command
     and no trailing text parameter; nothing resembling a visible chat message appears in a
     plain client that ignores unknown tags.
3. Open a third terminal, register `dave` *without* negotiating `message-tags`, and have
   dave join `#lobby`. Repeat step 2.
   - **Expected**: dave receives nothing for this `TAGMSG` — no line at all.
4. As alice: send a `TAGMSG` with no tags at all (bare `TAGMSG #lobby`).
   - **Expected**: rejected the same way a malformed message is (FR-023) — no delivery.
