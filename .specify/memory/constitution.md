<!--
Sync Impact Report
Version change: (none) → 1.0.0
Rationale: Initial ratification — no prior constitution existed (template
placeholders only). MAJOR version 1.0.0 chosen as the initial baseline.

Modified principles: n/a (initial adoption)
Added principles:
  - I. Code Quality
  - II. Testing Standards
  - III. User Experience Consistency
  - IV. Performance Requirements
Added sections:
  - Quality Gates (Section 2)
  - Development Workflow (Section 3)
  - Governance
Removed sections: none

Deferred placeholders: none — all bracket tokens resolved.

Templates requiring follow-up: none modified by this command (out of scope
per constitution workflow). Downstream commands (/speckit-plan,
/speckit-tasks, /speckit-checklist) read this constitution at runtime and
do not require edits for this change.
-->

# JIRCD Constitution

## Core Principles

### I. Code Quality
All code MUST be reviewed before merge; no self-approved changes reach the
main branch. Every function and module MUST have a single, clear
responsibility — if a change description needs "and" to explain what it
does, it MUST be split. Static analysis and linting MUST run clean (zero
errors, warnings triaged and either fixed or explicitly suppressed with a
documented reason) before a change is considered mergeable. Dead code,
commented-out blocks, and unused dependencies MUST be removed rather than
left "for later." Public interfaces (APIs, CLI commands, exported
functions) MUST be documented at the point of definition, not in a
separate document that can drift out of sync.

**Rationale**: Code quality debt compounds silently until it blocks
delivery; enforcing these checks at merge time is cheaper than any later
remediation effort.

### II. Testing Standards
Every bug fix MUST include a regression test that fails before the fix and
passes after. Every new feature MUST ship with automated tests covering
its primary behavior and its documented edge cases before the feature is
considered done. Tests MUST be deterministic — flaky tests MUST be fixed
or removed, never silenced by retries or skips. The test suite MUST pass
in CI before merge; a red suite blocks all merges, including unrelated
ones, until fixed. Test coverage MUST NOT decrease on any merged change;
exceptions require explicit written justification in the PR description.

**Rationale**: Untested code is unverified code; a green, trustworthy test
suite is the mechanism that lets the team change code confidently instead
of fearfully.

### III. User Experience Consistency
User-facing behavior (CLI output, API responses, UI copy, error messages)
MUST follow the same interaction patterns across every surface of the
product — the same command produces the same class of output whether
invoked from CLI, API, or UI. Error messages MUST state what went wrong
and what the user can do about it; silent failures are prohibited. Breaking
changes to any user-facing interface (CLI flags, API contracts, UI
workflows) MUST be documented with a migration path and MUST NOT ship
without a version bump signaling the break. New user-facing features MUST
be validated against at least one real usage scenario (manual walkthrough
or user test) before being marked complete — passing automated tests alone
is not sufficient evidence of a working feature.

**Rationale**: Inconsistent interaction patterns and unvalidated
assumptions are what erode user trust fastest, even when the underlying
logic is correct.

### IV. Performance Requirements
Every feature that adds a new user-facing operation (API endpoint, CLI
command, UI interaction) MUST define an explicit latency or throughput
budget before implementation begins. Changes that regress a measured
performance budget by more than 10% MUST be flagged in review and either
justified in writing or optimized before merge. Performance-sensitive code
paths MUST include a benchmark or load test that can be re-run to catch
future regressions — performance MUST be verified by measurement, not
assumed from code review alone. Resource usage (memory, CPU, network
calls, database queries) MUST be considered part of the definition of
done for any change touching a hot path.

**Rationale**: Performance regressions are invisible in code review and
expensive to diagnose after the fact; defining budgets up front turns
performance into a testable requirement instead of an afterthought.

## Quality Gates

Every change MUST pass the following gates before merge, in order: (1)
automated tests green, (2) static analysis/linting clean, (3) code review
approval from at least one other contributor, (4) for user-facing changes,
a manual or scripted UX consistency check against Principle III, and (5)
for performance-sensitive changes, a benchmark result against Principle
IV's declared budget. A gate MAY be skipped only with an explicit,
recorded justification (e.g., in the PR description) — silent skips are a
constitution violation.

## Development Workflow

Work MUST be tracked before it starts (issue, ticket, or task entry) so
intent is recorded independent of the eventual diff. Pull requests MUST
describe what changed and why, not just what changed — the "why" is what
reviewers and future maintainers need most. Reviewers MUST verify
compliance with all four Core Principles, not only correctness; a
correct-but-untested or correct-but-inconsistent change does not satisfy
this constitution. Complexity (new abstractions, new dependencies, new
services) MUST be justified against a concrete, current requirement — no
speculative generality.

## Governance

This constitution supersedes all other project practices, style guides,
and informal conventions where they conflict. Amendments require: (1) a
documented rationale for the change, (2) an update to this file including
a Sync Impact Report describing what changed, and (3) a version bump
following semantic versioning — MAJOR for backward-incompatible principle
removals or redefinitions, MINOR for new principles or materially expanded
guidance, PATCH for wording clarifications and typo fixes. All pull
requests and code reviews MUST verify compliance with this constitution;
unresolved conflicts between this document and any other guidance file
MUST be resolved in favor of this constitution unless an amendment is
made. Complexity introduced in violation of these principles MUST be
justified explicitly in the PR/review or removed.

**Version**: 1.0.0 | **Ratified**: 2026-08-15 | **Last Amended**: 2026-08-15
