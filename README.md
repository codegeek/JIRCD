# JIRCD

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A modular IRCv3 chat server, written in Java, designed to be extended
without touching its core.

> **Status: implemented.** All six mandatory user stories from the initial
> release plan (connect/chat, capability negotiation, extension toggling,
> channel moderation, in-band administration, and user lookup) are built
> and covered by integration tests. See [Project status](#project-status)
> below.

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

Requires JDK 25.

```bash
./gradlew build                 # build + unit/integration tests + static analysis, all subprojects
touch jircd-server/jircd.yaml   # a Server Configuration file must exist at this path; an empty
                                 # file is valid and loads every default
./gradlew :jircd-server:run     # starts the server on 6667 (plaintext) and 6697 (TLS)
```

Then connect with any IRC client, or a raw TCP tool for a quick check:

```bash
nc localhost 6667
NICK alice
USER alice 0 * :Alice
JOIN #lobby
PRIVMSG #lobby :hello
```

The empty config above enables no optional capabilities or extensions —
only core protocol behavior (connecting, messaging, moderation, `WHOIS`/
`WHO`). To turn on IRCv3 capabilities, hostname cloaking, or in-band
administration (`OPER`, `REHASH`, etc.), see the full annotated schema in
[`contracts/server-configuration.md`](specs/001-ircv3-server/contracts/server-configuration.md).
For a guided, story-by-story walkthrough of every feature, see
[`quickstart.md`](specs/001-ircv3-server/quickstart.md).

### Running a standalone build

`./gradlew :jircd-server:run` is for development only — it stays attached
to Gradle. For a standalone install (no Gradle needed to run it), build a
distribution instead:

```bash
./gradlew :jircd-server:installDist
```

This produces `jircd-server/build/install/jircd-server/`, containing a
`bin/jircd-server` (and `.bat`) launcher plus every runtime dependency —
including the bundled capability/extension jars, which are discovered via
`ServiceLoader` at startup — in `lib/`. Run it from that directory (it
looks for `jircd.yaml` in the working directory, same as `:run`):

```bash
cd jircd-server/build/install/jircd-server
touch jircd.yaml
bin/jircd-server
```

To produce a distributable archive instead (e.g. for attaching to a
release), use `distZip` or `distTar`, which land in
`jircd-server/build/distributions/`. See
[Releases](https://github.com/codegeek/JIRCD/releases) for prebuilt
archives — pushing a `vX.Y.Z` tag builds and publishes these automatically
(see [`.github/workflows/release.yml`](.github/workflows/release.yml)).

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) for the
workflow and expectations. Please also review our
[Code of Conduct](CODE_OF_CONDUCT.md).

## Security

See [SECURITY.md](SECURITY.md) for how to report a vulnerability.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
