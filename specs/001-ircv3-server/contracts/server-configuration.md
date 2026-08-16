# Contract: Server Configuration (Administrator-Facing)

This project has two administrator-facing interfaces, not an HTTP API: this
configuration file (Story 4) and the in-band IRC administrative commands
documented in [irc-protocol-commands.md](./irc-protocol-commands.md#administration-story-6)
(Story 6). This contract documents the configuration file's schema and the
validation behavior it MUST provide (FR-011, FR-012, FR-034).

## Schema (YAML, see research.md "Configuration format")

```yaml
listeners:
  - port: 6667
    tls: false
  - port: 6697
    tls: true          # optional TLS listener (FR-018); plaintext MUST remain available unless the admin removes it

# channel moderation and capability negotiation are core (FR-035, FR-036)
# and never appear in either section below — see data-model.md "Extension".

capabilities:            # CapabilityExtension states only (jircd-capabilities/) —
  message-tags: enabled  # client-negotiable via CAP LS. A ServerExtension id
  server-time: enabled   # (e.g., cloak, admin) listed here is a configuration error.
  echo-message: enabled

server-extensions:      # ServerExtension states only (jircd-server-extensions/) —
  cloak: disabled        # never CAP-negotiated, administrator-only. A
  admin: enabled         # CapabilityExtension id listed here is a configuration
                          # error. cloak (FR-031) is off by default; admin
                          # (Story 6) MUST be enabled for OPER etc. to work at all.

rateLimit:
  bucketSize: 20        # tokens
  refillRatePerSecond: 5

administratorCredentials:
  - username: root-admin
    hashedPassword: "<bcrypt/Argon2 hash — never plain text, see research.md 'Administrator credential storage'>"
```

## Behavioral Contract

- **Load-time validation**: an unknown key in either `capabilities` or
  `server-extensions` — including a `moderation` or
  `capability-negotiation` entry, since neither is a toggleable extension
  (FR-035, FR-036) — a `listeners` entry missing `port`, or a non-positive
  `rateLimit` value MUST cause the server to refuse to start with an error
  naming the exact offending key/value (FR-012, SC-008) — not a stack
  trace, not a silent default substitution.
- **Section/kind mismatch is also a load-time validation error**: an id
  MUST appear in the section matching its actual kind — a `ServerExtension`
  id (e.g., `cloak`, `admin`) listed under `capabilities`, or a
  `CapabilityExtension` id (e.g., `message-tags`) listed under
  `server-extensions`, MUST be rejected with an error naming the id and
  which section it belongs in instead — not silently accepted or moved.
  This is the config-file expression of the `CapabilityExtension`/
  `ServerExtension` distinction (data-model.md "Extension").
- **Live reload**: changing an entry's value from `enabled` to `disabled`
  (or back) in either section and applying the change (mechanism — e.g.,
  SIGHUP, admin command, file-watch — is an implementation decision for
  the tasks phase) MUST take effect for already-connected and new clients
  within the SC-005 budget (1 minute), without restarting the process
  (FR-011). Changes to `listeners` or `rateLimit` MAY require a
  listener-level restart (rebinding a port) without violating FR-011,
  since FR-011 scopes "no restart" to *extension* state, not listener
  reconfiguration — this distinction should be revisited if a future
  clarification narrows it further.
- **Partial-failure isolation**: if one extension (from either section)
  fails to start after being set to `enabled` (e.g., a coding error in
  that extension), the server MUST still start and serve all other
  configured extensions (FR-020), and MUST report the failed extension's
  `state` as `FAILED` (see data-model.md) rather than silently treating it
  as disabled.
- **Credential storage**: `administratorCredentials[].hashedPassword` MUST
  NOT be accepted or stored as plain text — load-time validation MUST
  reject a value that isn't a recognized hash format (FR-034).
- **Path equivalence**: an extension state change applied via the
  `EXTENSION` in-band command (Story 6) MUST be observably identical to
  one applied by editing this file's `capabilities` or `server-extensions`
  section (Story 4), whichever the id belongs to — same effect, same
  no-restart guarantee (FR-011) — so an administrator can freely mix both
  access paths. The in-band `EXTENSION` command addresses both sections
  through one shared id space (data-model.md's `Extension.id`); it does
  not need a client to know or state which section an id lives in.
