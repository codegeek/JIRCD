# Quickstart: Validating the Bare Channel Mode Query

Validation guide for this feature's 8 requirements. Same prerequisites as
`specs/001-ircv3-server/quickstart.md` — JDK 25, a raw TCP client, a running
`./gradlew :jircd-server:run` instance.

## Story 1 — Mode settings summary (FR-001 through FR-004)

1. `chanop` joins `#chan` (becomes operator via first-join), sends `MODE #chan +int`.
   - **Expected**: echoed normally (unrelated to this feature — confirms the flags are actually
     set before querying them).
2. `chanop` sends `MODE #chan` (no flag argument).
   - **Expected**: `324 RPL_CHANNELMODEIS` with a mode string containing `i`, `n`, and `t` (order
     not significant), followed by `329 RPL_CHANNELCREATED` with a numeric timestamp.
3. `chanop` sends `MODE #chan +l 5`, then `MODE #chan +k secret`, then `MODE #chan` again.
   - **Expected**: the `324` mode string now also includes `l` and `k`, with `5` and `secret` as
     trailing value params, in the same order the letters appear.
4. `chanop` clears every active flag (`MODE #chan -intlk`), then sends `MODE #chan` again.
   - **Expected**: the `324` mode string is exactly `+` — not empty, not omitted.
5. `chanop` sets a ban (`MODE #chan +b mask!*@*`), then sends `MODE #chan` again.
   - **Expected**: the `324` mode string does NOT include `b`, and no ban-mask content appears in
     either reply — bans stay exclusively in `MODE #chan +b`'s own dedicated query.

## Story 2 — Query access (FR-005, FR-006)

1. A non-operator member of `#chan` sends `MODE #chan`.
   - **Expected**: succeeds identically to an operator's own query (`324`/`329`), not `482
     ERR_CHANOPRIVSNEEDED`.
2. A client who is NOT a member sends `MODE #secret` for a channel with `+s` active.
   - **Expected**: `403 ERR_NOSUCHCHANNEL` — indistinguishable from `#secret` not existing at all,
     the same way that channel is already hidden from `TOPIC`/`NAMES`.

## Story 3 — Creation time (FR-007, FR-008)

1. Immediately after `#chan` is first created (first `JOIN`), query `MODE #chan`.
   - **Expected**: `329`'s timestamp is very close to "now."
2. Every member parts `#chan` (channel becomes empty and is removed), then a new member joins,
   recreating it, then queries `MODE #chan`.
   - **Expected**: `329`'s timestamp reflects the recreation moment, not the original creation.

## Automated cross-check

Run the irctest suite (`github.com/jircd/irctest`'s `irctest.controllers.jircd` controller,
`--timeout=60 --timeout-method=signal`) and confirm `chmodes/modeis.py::testChannelModeIs` now
passes, with no regression in any previously-passing test (spec.md SC-003).
