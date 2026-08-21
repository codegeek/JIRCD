# Data Model: Connection Monitoring Log

## `ClientSession` — one new field

`connectionId` itself is unchanged in shape (still the same `private final String
connectionId`, `jircd-core/src/main/java/net/jircd/core/session/ClientSession.java:32`) —
only what `ConnectionHandler` puts into it changes (see research.md "Connection token
generation scheme"). `realHostname` (line 38) already carries the remote address the
connect log entry needs; no new field there either.

| Field | Type | Before this feature | After this feature |
|---|---|---|---|
| `connectionId` | `String` | `"c" + sequential counter` (e.g. `c1`, `c2`) | `UUID.randomUUID().toString()` — opaque, unique |
| `connectedAt` | `Instant` | *(does not exist)* | New: `private final Instant connectedAt = Instant.now();` — field initializer, immutable, mirrors `Channel.createdAt` (007-bare-mode-query) |

## `ServerConfiguration` — one new field

| Field | Type | Default | Ceiling | Validation |
|---|---|---|---|---|
| `keepAliveFrequencySeconds` | `int` | `120` (`DEFAULT_KEEP_ALIVE_FREQUENCY_SECONDS`) | `3600` (`KEEP_ALIVE_FREQUENCY_CEILING_SECONDS`) | `ConfigurationLoader.positiveIntWithinCeiling`, the same helper `operFailureThreshold`/`whowasHistorySize` already use — reject non-positive or above-ceiling values with a specific, actionable error (FR-011) |

No new `AdministratorCredential`-style nested record — a single scalar setting, same shape
as `operFailureThreshold`.

## `ConnectionMonitorLog` — new entity (a stateless logging facility, not a data record)

Sibling to `SecurityEventLog` (`jircd-core/src/main/java/net/jircd/core/session/
SecurityEventLog.java`) — same package, same static-utility shape.

| Method | Parameters | Logged fields |
|---|---|---|
| `connected` | `connectionId`, `remoteAddress` | `connection-event=connected connection={} remoteAddress={}` |
| `disconnected` | `connectionId`, `duration`, `reason` | `connection-event=disconnected connection={} durationMs={} reason={}` |

## Relationships

- `ConnectionHandler.handleConnection` generates the token, constructs `ClientSession`
  (which now also stamps `connectedAt`), and calls `ConnectionMonitorLog.connected(...)`.
- `LivenessMonitor.sendPing` (unchanged) reads `session.connectionId()` — automatically
  consistent with the connect log entry and any later disconnect log entry, since all three
  read the same immutable field.
- `DisconnectCleanup.cleanup` — inside its existing idempotency guard — computes the
  connection's duration from `session.connectedAt()` and calls
  `ConnectionMonitorLog.disconnected(...)`, reusing the `reason` string every disconnect
  trigger already supplies.
