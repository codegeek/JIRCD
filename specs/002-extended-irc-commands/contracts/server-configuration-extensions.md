# Contract: Server Configuration Extensions

Extends `specs/001-ircv3-server/contracts/server-configuration.md`'s schema with one new
optional key, following that document's exact conventions (optional, validated at load
time, "explicit, actionable error, not a silent default substitution" — see that file's
own contract notes for the rationale, which applies unchanged here). This does not modify
`001-ircv3-server`'s own contract file.

## Schema addition (YAML)

```yaml
whowasHistorySize: 100  # optional (data-model.md WhowasHistory) — defaults to 100. Positive
                          # integer. The maximum number of WHOWAS entries retained globally
                          # (across all nicknames combined, not per-nickname) before the
                          # oldest is evicted to make room for a new one.
```

**Contract notes**:
- Validated the same way `nicknameMaxLength`/`channelNameMaxLength`/`topicMaxLength`
  already are: a non-positive value is a load-time validation error naming the offending
  key, not a silently-substituted default (`001-ircv3-server`
  contracts/server-configuration.md contract notes).
- Live-reloadable via the same `REHASH`/`SIGHUP` path as every other numeric limit
  (`001-ircv3-server` FR-011/FR-012) — reducing this value on reload evicts the oldest
  existing entries beyond the new capacity immediately, the same way reducing
  `maxModesPerCommand` takes effect for the very next `MODE` command.
