<!--
SYNC IMPACT REPORT
==================
Version change: (uninitialized template) → 1.0.0
Bump rationale: Initial ratification of the Tickstats-Sync constitution. All placeholder
tokens replaced with concrete, project-specific content. MAJOR version used because this
is the first binding version of the document; no prior semantic baseline exists.

Modified principles:
- [PRINCIPLE_1_NAME] → I. Strict Tickstats Compatibility Contract
- [PRINCIPLE_2_NAME] → II. Non-Intrusive Server Operation
- [PRINCIPLE_3_NAME] → III. Declarative, Hot-Reloadable Configuration
- [PRINCIPLE_4_NAME] → IV. Credential Safety
- [PRINCIPLE_5_NAME] → V. Pragmatic Observability
(Added) → VI. Resilience To Transient Failures
(Added) → VII. Sync Idempotence
(Added) → VIII. Explicit Permissions
(Added) → IX. Deployment Simplicity
(Added) → X. Tickstats Convention Alignment
(Added) → XI. English-Only Code And Artifacts

Added sections:
- Additional Constraints (Tickstats Producer Contract, Runtime Constraints)
- Development Workflow (Review Gates, Release Discipline)
- Governance (amendment procedure, versioning policy, compliance review,
  upstream contract rule, language rule enforcement)

Removed sections: None (template placeholders replaced in place).

Templates requiring updates:
- ✅ .specify/templates/plan-template.md — Constitution Check section references this
  constitution generically; no rewording required. Plan authors MUST instantiate the
  gate using principles I–XI.
- ✅ .specify/templates/spec-template.md — No direct conflict; mandatory sections remain
  compatible with principles I, II, III, V, VIII.
- ✅ .specify/templates/tasks-template.md — Task categories still apply; plan authors
  MUST add tasks for observability (V), resilience (VI), idempotence (VII), permissions
  (VIII), and language review (XI) where relevant.
- ⚠ README.md — Not yet created for this repository. Must be authored in English per
  principle XI when introduced.
- ⚠ docs/quickstart.md — Not present. Future quickstart content MUST be in English.

Deferred items / TODOs: None. All placeholders resolved.
-->

# Tickstats-Sync Constitution

Tickstats-Sync is an open-source Paper plugin that produces Minecraft statistics data
for the upstream [Tickstats](https://github.com/Skycryck/tickstats) static dashboard
pipeline. This constitution defines the non-negotiable rules that govern its design,
implementation, and evolution.

## Core Principles

### I. Strict Tickstats Compatibility Contract

The plugin is a data *producer* for the existing Tickstats pipeline and MUST preserve
bit-for-bit compatibility with the layout consumed by `generate.py`.

- The plugin MUST write exactly the following structure into the target repository:
  - `stats/<server-name>/data/<uuid>.json` — a verbatim copy of the server stats file.
  - `stats/<server-name>/snapshots/YYYY-MM-DD/<uuid>.json` — a daily archive, written
    **at most once per calendar day** with first-write-wins semantics.
- The plugin MUST NOT transform, reformat, rewrite, prettify, or enrich the Minecraft
  stats JSON in any way. Raw bytes from the server are pushed; all derivation happens
  in `generate.py` on CI.
- Any deviation from this layout is a breaking change against the upstream contract
  and is forbidden until the constitution is amended (see Governance).

**Rationale**: Tickstats' CI consumes this exact layout. Divergence silently breaks
the dashboard or worse, produces incorrect statistics with no upstream error.

### II. Non-Intrusive Server Operation

The plugin MUST NOT produce a perceptible impact on the Minecraft server's TPS.

- All I/O, git, network, and compression operations MUST run asynchronously on a
  dedicated executor, never on the main server thread.
- The plugin MUST NOT write to the live `world/stats/` directory; it has read-only
  access to those files.
- Long-running tasks MUST be cancellable and MUST yield if the server is shutting down.

**Rationale**: A stats plugin that lags the server is unusable. Writes into
`world/stats/` risk corrupting live player data — that directory is owned by the
Minecraft server alone.

### III. Declarative, Hot-Reloadable Configuration

All runtime behavior MUST be expressed in `config.yml` and MUST be reloadable without
a server restart.

- Required keys include (at minimum): target GitHub repository, Personal Access Token,
  server name, cron-like sync cadence, target branch, target path inside the repo, and
  snapshot options.
- A `/tickstats reload` command MUST re-read `config.yml` and apply every change
  (schedule, credentials, target paths) atomically without restarting the server or
  missing a scheduled sync.
- Invalid configuration MUST fail the reload with an explicit error and leave the
  previous valid configuration active.

**Rationale**: Operators manage live Paper servers; forcing restarts for a
configuration tweak is unacceptable. Atomic reload prevents split-brain state.

### IV. Credential Safety

The GitHub Personal Access Token (PAT) is sensitive and MUST NOT leak.

- The PAT MUST NOT appear in: plugin logs, in-game chat or command output, git commit
  messages, exception stack traces, thread dumps, metrics, or any other plugin-emitted
  artifact.
- `config.yml` MUST carry a prominent header comment (in English) warning that it
  contains a secret and recommending `.gitignore` inclusion when the Minecraft server
  directory is itself versioned.
- Any code path that handles the PAT MUST pass it as a redacted value when building
  log lines or diagnostic output.

**Rationale**: A leaked PAT grants write access to the Tickstats data repository.
Treat the token with the same discipline as a production deployment key.

### V. Pragmatic Observability

Every sync attempt — success or failure — MUST emit a structured log line.

- Success log lines MUST contain: ISO-8601 timestamp, number of files pushed, resulting
  commit SHA (short form acceptable), and total duration in milliseconds.
- Failure log lines MUST categorize the failure (auth, network, git conflict, I/O,
  other) and include enough context for debugging, with the PAT redacted.
- A `/tickstats status` command MUST display: last successful sync timestamp, next
  scheduled sync, effective configuration summary (without secrets), and the current
  health state.

**Rationale**: Operators run this headless for months. Without disciplined logging
and a status command, a broken sync is invisible until the dashboard goes stale.

### VI. Resilience To Transient Failures

Transient faults MUST NOT crash the plugin or block the scheduler thread.

- Network errors, HTTP 401/403/5xx from GitHub, push conflicts, and I/O hiccups MUST
  be retried with a bounded policy (recommended default: 3 attempts with exponential
  backoff) before giving up.
- After exhausting retries, the plugin MUST log the failure with full context
  (redacted PAT) and MUST wait for the next scheduled occurrence — never enter a busy
  retry loop.
- The scheduler MUST remain healthy regardless of any individual sync's outcome.

**Rationale**: A Minecraft server runs 24/7 across flaky networks and GitHub
maintenance windows. A single failure must never disable the producer permanently.

### VII. Sync Idempotence

Syncs with no underlying change MUST NOT produce a commit.

- Before creating a commit, the plugin MUST determine whether the staged tree differs
  from the remote tip. Acceptable mechanisms: content-hash comparison against the last
  known state, or `git diff --quiet` after staging.
- If nothing changed, the sync MUST succeed silently (log a "no changes" line) and
  MUST NOT create an empty commit.

**Rationale**: Empty commits pollute history, trigger pointless CI runs, and burn
GitHub Actions minutes on the Tickstats side.

### VIII. Explicit Permissions

All in-game commands MUST be gated by the `tickstats.admin` permission node, default
`op`. No sensitive command MUST be accessible to ordinary players.

- The permission node MUST be declared in `plugin.yml` with `default: op`.
- Every command registered by the plugin MUST check this permission before executing
  any effect and MUST reply with a non-revealing "unknown command" or "no permission"
  message to unauthorized users.
- Future read-only commands (if any) MAY be gated by a narrower node, but the default
  stance is admin-only.

**Rationale**: The plugin exposes operations (manual sync, reload, status) that can
reveal configuration or trigger external network activity. Only operators should see
or invoke them.

### IX. Deployment Simplicity

Installing the plugin MUST require: dropping one JAR into `plugins/`, editing one
`config.yml`, and performing a single server restart or reload.

- The distributed JAR MUST be self-contained (shaded) and MUST bundle all runtime
  dependencies: git client, YAML parser, cron scheduler, HTTP client.
- The plugin MUST NOT require operators to install native tools (`git`, `ssh`, etc.)
  or add external Maven/repositories to their server.
- The JAR MUST NOT require internet access at startup beyond what the sync itself
  needs.

**Rationale**: Paper plugin operators expect drop-in installs. Requiring native git
on the host defeats the plugin's purpose (many shared hosts disallow it).

### X. Tickstats Convention Alignment

The plugin MUST match the conventions of the existing Tickstats toolchain where they
affect produced artifacts.

- Default timezone for snapshot date computation MUST be `Europe/Paris`, aligning with
  `sync-stats.ps1`. The timezone MAY be overridden in `config.yml`, but the default
  MUST remain `Europe/Paris`.
- Snapshot directory names MUST use the `YYYY-MM-DD` format.
- Git commit messages MUST be human-readable, dated, and in English. Recommended form:
  `Update stats for <server-name> — YYYY-MM-DD HH:mm`.

**Rationale**: Producer and CI must agree on date boundaries. A mismatch silently
moves snapshots between days and corrupts daily-granularity dashboards.

### XI. English-Only Code And Artifacts

The project is open-source and targets an international audience. English is the only
accepted language for code and produced artifacts.

- The following MUST be written in English, without exception: source code, inline
  comments, Javadoc, class/method/field/variable/package names, log messages, error
  messages, git commit messages, `config.yml` keys and comments, in-game command
  output, and `README.md`.
- A pull request containing French (or any other non-English) strings in the above
  artifacts MUST be rejected at review, regardless of the language used in the prompt,
  issue, or conversation that produced it.
- User-facing prose *outside* produced artifacts (e.g., ad-hoc team chat) is not
  governed by this principle.

**Rationale**: Code written in a language not shared by the project's audience raises
the contribution barrier and fragments the ecosystem. Enforcement at review is the
only reliable gate.

## Additional Constraints

### Tickstats Producer Contract

- The repository layout described in Principle I is a **contract with an external
  consumer** (`generate.py`). Any change to `generate.py`'s expectations requires a
  constitutional amendment to this document *before* the plugin is modified.
- If upstream Tickstats diverges without coordination, the plugin MUST continue
  producing the old layout until this constitution is amended and a new version is
  cut.

### Runtime Constraints

- Target platform: Paper (and API-compatible forks) on a supported Java LTS.
- Memory footprint SHOULD remain negligible relative to the Minecraft server (no
  unbounded caches; sync working sets MUST be released after each run).
- Network activity MUST be confined to the configured GitHub endpoint.

## Development Workflow

### Review Gates

Every pull request MUST pass the following gates before merge:

1. **Constitution Check** — the author has reviewed principles I–XI and declared in
   the PR description which principles the change touches and how it complies.
2. **Language Check** — reviewer confirms no non-English strings in code, logs,
   comments, docs, or commit messages (Principle XI).
3. **Thread Safety Check** — reviewer confirms no new I/O, git, or network call runs
   on the main server thread (Principle II).
4. **Secret Hygiene Check** — reviewer confirms no new code path can emit the PAT
   (Principle IV).
5. **Contract Check** — if the change touches file layout, paths, or commit metadata,
   reviewer confirms Principles I and X are honored.

### Release Discipline

- Release artifacts MUST be a single shaded JAR plus release notes in English.
- Version bumps follow SemVer against the *plugin's* public surface: config schema,
  command set, permission nodes, and produced repository layout.
- Any change to the produced repository layout (Principle I) is a MAJOR plugin bump
  AND requires an upstream Tickstats compatibility note.

## Governance

This constitution supersedes ad-hoc practice and takes precedence over individual
opinions, including those expressed in issues, chat, or PR discussions.

**Amendment procedure**: Any change to a principle, to an added section, or to the
governance rules themselves MUST be proposed as a pull request that edits *this file
first*, before any dependent code, tests, or documentation are changed. The PR MUST
include:

1. Which principle(s) or section(s) change, and the old → new text.
2. Rationale for the change.
3. A version bump proposal following the versioning policy below.
4. A Sync Impact Report at the top of this file (HTML comment) listing affected
   templates, docs, and code areas.

**Versioning policy**: This document follows semantic versioning.

- **MAJOR**: A principle is removed, redefined in a backward-incompatible way, or the
  governance rules change in a way that invalidates prior compliance claims.
- **MINOR**: A new principle or section is added, or existing guidance is materially
  expanded.
- **PATCH**: Clarifications, typo fixes, wording improvements, or non-semantic
  refinements that do not alter which PRs would pass review.

**Upstream contract rule**: The file-layout compatibility contract with
`generate.py` (Principle I) is inviolable by the plugin alone. If Tickstats changes
its expectations, Principle I MUST be amended in this document *before* any
compatibility change ships in the plugin. An unannounced layout change in the plugin
is a constitution violation regardless of its technical merit.

**Language rule enforcement**: Principle XI applies uniformly. A PR containing any
non-English code, log string, config key, comment, or documentation MUST be rejected,
regardless of the language of the prompt, issue, or conversation that produced it.
Prompts in French, Spanish, German, etc. are welcome; their *output* is English.

**Compliance review**: At each release, the maintainer MUST verify that no principle
has silently eroded (e.g., new log lines added without redaction, sync code creeping
onto the main thread). Findings are recorded in the release notes.

**Version**: 1.0.0 | **Ratified**: 2026-04-22 | **Last Amended**: 2026-04-22
