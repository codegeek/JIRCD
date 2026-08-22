# Quickstart: Validating Wallops Notices

Validates the feature end-to-end over real client connections, the same way this project's
other admin-extension commands are validated (`jircd-integration-tests`). See
[`contracts/wallops-command.md`](./contracts/wallops-command.md) for exact reply/wire
details and [`data-model.md`](./data-model.md) for what state is involved.

## Prerequisites

- An administrator credential configured for this server instance (same setup
  `specs/008-argon2-admin-verification` and existing `OperCommandHandler` tests already
  use — see `jircd-integration-tests/src/test/java/net/jircd/integration/Story6OperTest.java`
  for the established pattern of `OPER`-ing a test connection before exercising an
  admin-gated command).
- Build/run this feature's code with `./gradlew build` from the repo root (compiles all
  modules, including the new `UserMode.WALLOPS` catalog entry and `WallopsCommandHandler`).

## Scenario 1 — Administrator broadcasts to an opted-in user (User Story 1, P1)

1. Connect and register two clients, A and B.
2. On A: `OPER <admin-user> <admin-pass>` → confirm A now holds administrator privilege.
3. On B: `MODE B +w` → confirm the server echoes the mode change and a subsequent
   `MODE B` query shows `+w`.
4. On A: `WALLOPS :server restarting in 5 minutes`.
5. **Expect**: B receives a `WALLOPS` message with A's hostmask as prefix and the exact
   text sent. No other connected, non-opted-in client receives anything.

## Scenario 2 — No opted-in recipients (User Story 1, edge case)

1. On A (already `OPER`'d): `WALLOPS :test with no subscribers` while no connected
   session has `+w` set.
2. **Expect**: the command completes with no error reply to A and no message delivered
   to anyone (FR-011).

## Scenario 3 — Administrator receives their own notice only if opted in (User Story 1)

1. On A (already `OPER`'d): `MODE A +w`.
2. On A: `WALLOPS :self-test`.
3. **Expect**: A receives a copy of their own notice (FR-008).

## Scenario 4 — User self-controls the preference (User Story 2, P2)

1. On a non-administrator client C: `MODE C +w` → confirm accepted, `MODE C` query shows
   `+w`.
2. On C: `MODE C -w` → confirm accepted, `MODE C` query no longer shows `w`.
3. On C: attempt `MODE <other-nickname> +w` → **expect** `502 ERR_USERSDONTMATCH`, and the
   other user's preference is unchanged.

## Scenario 5 — Non-administrator cannot broadcast (User Story 3, P3)

1. On non-administrator client D (no prior `OPER`): `WALLOPS :should not be delivered`.
2. **Expect**: `481 ERR_NOPRIVILEGES` returned to D; no connected client, even one with
   `+w` set, receives anything.

## Scenario 6 — Empty/missing text is rejected

1. On an `OPER`'d client: `WALLOPS` (no parameter) → **expect** `461 ERR_NEEDMOREPARAMS`.
2. On an `OPER`'d client: `WALLOPS :` (empty text) → **expect** `412 ERR_NOTEXTTOSEND`.
3. Neither attempt delivers anything to any recipient.

## Scenario 7 — Privilege lost mid-session (Edge Case)

1. On an `OPER`'d client: revoke administrator privilege the same way any existing test
   does (`MODE <self> -o`, per `Story6OperUserModeTest.java`'s established pattern).
2. Attempt `WALLOPS :should now be rejected` → **expect** `481 ERR_NOPRIVILEGES`, identical
   to Scenario 5.

Each scenario above corresponds to one acceptance scenario or edge case in
[`spec.md`](./spec.md) and should become one `@Test` method in the new
`jircd-integration-tests/.../WallopsCommandTest.java` (see `plan.md` Project Structure).
