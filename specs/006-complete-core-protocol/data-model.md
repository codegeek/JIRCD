# Phase 1 Data Model: Complete Core Protocol Exclusions

This document is an amendment to `001-ircv3-server/data-model.md`'s `Channel` and `ChannelMode`
entities, not a replacement — it records only what this feature adds or changes. Every field and
validation rule `001` already documents for these two entities stays exactly as it is; nothing
below removes or redefines any of it.

## `ChannelMode.kind` — new values

`001-ircv3-server/data-model.md`'s `ChannelMode` entity documents `kind` as one of `BOOLEAN`/
`VALUE`/`LIST`/`MEMBER`. `VALUE` — reserved since `001` but never assigned to any mode — splits
into two:

| Kind | Parameter on set (`+`) | Parameter on unset (`-`) | This release's mode |
|---|---|---|---|
| `VALUE_SET_ONLY` | Required | None | `l` (user-limit) |
| `VALUE_ALWAYS` | Required | Required | `k` (channel-key) |

**Rationale**: RFC 2811/Modern IRC's own `CHANMODES` ISUPPORT categorization (type B vs. type C)
already draws exactly this distinction; `SupportedFeatures.formatChanModes()` (jircd-core)
already reserves two separate, named-in-comment groups for it. See research.md Story 1.

## `CoreChannelModes` — new constants

Two new `ChannelMode` constants join `CoreChannelModes.ALL` (currently `n`/`m`/`p`/`s`/`i`/`b`/
`o`/`v`, all `CORE`-defined):

| Flag | `id` | `kind` | `gates` | Notes |
|---|---|---|---|---|
| `l` | `user-limit` | `VALUE_SET_ONLY` | `{JOIN}` | State lives in the new `Channel.memberLimit` field, not `activeModes` — the same "each `kind` gets its own dedicated storage" pattern `bans`/`invited`/`operators`/`voiced` already established (`001` data-model.md). |
| `k` | `channel-key` | `VALUE_ALWAYS` | `{JOIN}` | State lives in the new `Channel.key` field, same pattern. |

A third new constant, `t` (`topic-lock`, `BOOLEAN`, `gates: {}`), also joins `CoreChannelModes.ALL`
— state lives in `activeModes` like every other `BOOLEAN` flag (`001-ircv3-server`'s existing
`n`/`m`/`p`/`s`/`i` pattern), consulted directly by `TopicCommandHandler` rather than through the
generic `GateAction` mechanism (research.md Story 2 — `GateAction` currently has no `TOPIC_SET`
action, and adding a fourth for a single flag with no other consumer would be speculative
generality this project's constitution argues against).

## `Channel` — new fields

Two new fields join `001-ircv3-server/data-model.md`'s `Channel` entity table:

| Field | Type | Notes |
|---|---|---|
| `memberLimit` | `int`, volatile | `0` means unset (mirrors `topic`'s `null`-means-unset shape, but uses `0` since a real limit is always a positive count — RFC's `+l` grammar has no concept of a zero-or-negative limit). The `user-limit` `ChannelMode`'s `VALUE_SET_ONLY`-kind state (FR-001). Set via `MODE +l <n>`, cleared via `MODE -l` (no parameter). Existing members already over a newly-lowered limit are never removed — the limit only gates future `JOIN` attempts (FR-003, spec.md Edge Cases). |
| `key` | `String`, volatile, nullable | `null` means unset. The `channel-key` `ChannelMode`'s `VALUE_ALWAYS`-kind state (FR-004). Set via `MODE +k <key>`, cleared via `MODE -k <any-value>` (a parameter is always required on this mode, per `VALUE_ALWAYS`'s shape, but its value is not checked against the current key when clearing — the same "don't require re-proving what you're removing" precedent `-b <mask>` already sets, since removal is always operator-privileged). |

Both fields are reset to their unset default when a channel is recreated from zero members, the
same reset `001-ircv3-server/data-model.md`'s existing "channel with zero members is not a
durable entity" validation rule already applies to `topic`/`bans`/`invited`/etc.

**Validation rules (additions to `001`'s existing Channel validation rules)**:

- A `JOIN` attempt on a channel where `members().size() >= memberLimit` (when `memberLimit > 0`)
  MUST be rejected with `471 ERR_CHANNELISFULL`, UNLESS the joining session holds a valid, pending
  invitation for that channel (FR-002, spec.md Clarifications) — the same exemption `invite-only`
  already grants.
- A `JOIN` attempt on a channel where `key` is set, supplying no key or a non-matching one, MUST
  be rejected with `475 ERR_BADCHANNELKEY` (FR-005), under the identical invitation exemption
  above.
- The pending-invitation check these two new gates and the existing `invite-only` gate all consult
  (`Channel.invited`, `001` data-model.md) MUST be read without mutating it (a non-consuming
  membership check) until every applicable gate for a given `JOIN` attempt has been evaluated; the
  entry is then removed at most once per successful `JOIN`, not once per gate that happened to
  exempt it (research.md Story 1 — prevents a channel with more than one of `+i`/`+l`/`+k` active
  from having the invitation consumed by the first gate checked and then incorrectly failing the
  second).
- `TOPIC`-setting's existing operator-only restriction (`001` data-model.md: "`topic` MUST only be
  set by a session in `operators`") now applies only when the new `topic-lock` `ChannelMode` is
  active on the channel (FR-009); when inactive, any current member may set the topic (FR-008) —
  this narrows `001`'s previously-unconditional restriction to a conditional one, superseding that
  specific clause of `001`'s validation rules (everything else in `001`'s `topic` validation is
  unchanged: UTF-8 validity, the `topicMaxLength` bound, and the reset-on-recreation behavior).

## `WhowasHistory` — no schema change

No new field or entity. `WhowasHistory` (`002-extended-irc-commands/data-model.md`) is already a
single global, bounded `Deque<WhowasEntry>` retaining more than one entry per nickname today (up
to whatever the configured global capacity allows before older entries of any nickname are
evicted) — confirmed by reading its current source before writing this plan. FR-014 needs only a
new query method (`mostRecentNFor(nickname, count)`, research.md Story 6), not a change to what's
stored or how it's evicted.
