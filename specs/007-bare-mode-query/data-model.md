# Phase 1 Data Model: Bare Channel Mode Query

This document is an amendment to `001-ircv3-server/data-model.md`'s `Channel` entity (already
amended once by `006-complete-core-protocol/data-model.md`), not a replacement — it records only
what this feature adds. Every field and validation rule either of those documents already
establishes stays exactly as it is.

## `Channel` — new field

One new field joins `Channel`'s entity table:

| Field | Type | Notes |
|---|---|---|
| `createdAt` | `Instant`, final | Set once, at construction (`Instant.now()` field initializer) — never changed afterward. The `006-complete-core-protocol FR-007` reply value for `329 RPL_CHANNELCREATED`. Resets naturally when the channel is recreated after becoming empty, the same way `topic`/`memberLimit`/`key` already reset (`001`/`006` data-model.md) — a fresh `Channel` object means a fresh `createdAt`, with no explicit reset logic needed (FR-008). |

**Validation rules (additions to `001`'s/`006`'s existing Channel validation rules)**:

- `createdAt` MUST NOT be settable or mutable after construction — no setter exists, matching the
  reasoning in research.md ("Alternatives considered": a mutable, externally-settable timestamp
  was rejected as unnecessary indirection with no legitimate use).

## `NumericReply` — new numeric

| Constant | Code | Notes |
|---|---|---|
| `RPL_CHANNELCREATED` | 329 | Newly reserved — 326–330 were all previously unclaimed in the Full Numeric Catalog (`001-ircv3-server/contracts/irc-numeric-replies.md`), confirmed via source read before this feature claimed 329. Params: `<client> <channel> <creationtime>`, `<creationtime>` a Unix epoch-seconds string. |

No change to `RPL_CHANNELMODEIS` (324) itself — it already existed, unused, since `001`.
