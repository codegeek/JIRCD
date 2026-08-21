# Data Model: Argon2 Administrator Credential Verification

## Administrator Credential — no schema change

`ServerConfiguration.AdministratorCredential` (record: `username`, `hashedPassword`) is
unchanged. `hashedPassword` was already an opaque string with no format-specific field —
this feature changes which prefix values `ConfigurationLoader` accepts and which prefix
values `AdminCredentialVerifier` can actually check, not the entity's shape.

| Field | Type | Before this feature | After this feature |
|---|---|---|---|
| `username` | `String` | Unchanged | Unchanged |
| `hashedPassword` | `String` | Accepted prefixes: `$2a$`, `$2b$`, `$2y$` (verifiable), `$argon2` (accepted but never verifiable — the bug) | Accepted prefixes: `$2a$`, `$2b$`, `$2y$` (verifiable, unchanged), `$argon2id$` (now verifiable) |

No new entity, no new configuration key, no migration — a previously-configured `$argon2`
hash that happens to already be `$argon2id$`-prefixed starts working with no configuration
change required; one that is `$argon2i$`/`$argon2d$`-prefixed now fails fast at
configuration-load time (FR-004) instead of silently at `OPER` time, which is a stricter,
more correct rejection point for a value that could never have authenticated anyway.
