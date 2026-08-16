# JIRCD

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A modular IRCv3 chat server, written in Java, designed to be extended
without touching its core.

> **Status: pre-implementation.** The project is currently a fully
> specified and planned feature — no server code has been written yet.
> See [Project status](#project-status) below.

## What is JIRCD?

JIRCD is a standalone IRC server that speaks both classic IRC
(RFC 1459/2812) and a defined set of [IRCv3](https://ircv3.net/) capability
extensions. Its defining goal is **modularity with a clear boundary**:
core protocol behavior (connection handling, channel moderation, capability
negotiation) is always present and never optional, while enhancements —
individual IRCv3 capabilities, hostname cloaking, in-band server
administration — are independently loadable extensions an administrator can
enable or disable at runtime, without a restart.

Planned for the first release:

- Real-time connection registration, channel messaging, and moderation
  (classic first-join-gets-operator model)
- IRCv3 capability negotiation, with `message-tags`, `server-time`, and
  `echo-message` as the initial capability set
- Runtime-toggleable extensions, configurable via file or in-band IRC
  administrative commands
- Standard `nickname!ident@hostname` identity presentation, with an
  optional hostname-cloaking extension

Authentication/accounts and server-to-server federation are deliberately
out of scope for the first release — see the spec for the reasoning.

## Project status

This project is being built with a spec-driven workflow
([GitHub Spec Kit](https://github.com/github/spec-kit)): every feature is
fully specified and planned *before* implementation begins. The complete
specification, clarifications, technical plan, data model, and API
contracts for the initial server feature live under
[`specs/001-ircv3-server/`](specs/001-ircv3-server/):

- [`spec.md`](specs/001-ircv3-server/spec.md) — what the server must do, and why
- [`plan.md`](specs/001-ircv3-server/plan.md) — the technical approach and domain model
- [`research.md`](specs/001-ircv3-server/research.md) — key technical decisions and their rationale
- [`data-model.md`](specs/001-ircv3-server/data-model.md) — entities and their relationships
- [`contracts/`](specs/001-ircv3-server/contracts/) — the wire protocol and configuration contracts

The project's governing principles (code quality, testing, UX consistency,
performance) are recorded in the
[constitution](.specify/memory/constitution.md).

## Getting started

There's no buildable server yet — task breakdown and implementation are
the next steps. Once implementation begins, build/run instructions will
live here.

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) for the
workflow and expectations. Please also review our
[Code of Conduct](CODE_OF_CONDUCT.md).

## Security

See [SECURITY.md](SECURITY.md) for how to report a vulnerability.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
