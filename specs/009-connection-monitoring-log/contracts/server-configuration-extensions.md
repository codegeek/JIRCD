# Contract: Server Configuration Extensions

Extends `specs/001-ircv3-server/contracts/server-configuration.md`'s schema with one new
optional key, following that document's exact conventions (optional, validated at load
time, "explicit, actionable error, not a silent default substitution" — see that file's own
contract notes for the rationale, which applies unchanged here) — the same precedent
`002-extended-irc-commands/contracts/server-configuration-extensions.md` already
established for `whowasHistorySize`. This does not modify `001-ircv3-server`'s own contract
file.

## Schema addition (YAML)

```yaml
keepAliveFrequencySeconds: 120  # optional (FR-009/FR-010) — defaults to 120. Positive
                                  # integer, at most 3600. How long a connection may sit idle
                                  # before the server probes it with a server-initiated PING
                                  # (data-model.md). Previously a hardcoded, non-configurable
                                  # 30-second constant.
```

**Contract notes**:

- Validated the same way `nicknameMaxLength`/`channelNameMaxLength`/`topicMaxLength`/
  `whowasHistorySize` already are: a non-positive value, or one above the 3600-second
  ceiling, is a load-time validation error naming the offending key, not a
  silently-substituted default (`001-ircv3-server` contracts/server-configuration.md
  contract notes; FR-011).
- Resolved once per connection at accept time, the same way `rateLimit` already is — a
  connection already in progress keeps whatever interval was configured when it was
  accepted; a `REHASH`/`SIGHUP` reload's new value applies to connections accepted
  afterward, not retroactively to ones already open.
- Governs only the idle threshold that triggers a `PING` — the separate, fixed 10-second
  response timeout and 5-second internal check cadence are unaffected and remain
  implementation details (spec.md Assumptions).
