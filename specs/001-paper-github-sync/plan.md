# Implementation Plan: TickstatsSync — Automated Paper-to-GitHub Stats Sync

**Branch**: `001-paper-github-sync` | **Date**: 2026-04-22 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/001-paper-github-sync/spec.md`

## Summary

Deliver a self-contained Paper plugin (`TickstatsSync`) that, once configured, writes
the server's Minecraft stats to a GitHub repository on a cron-driven schedule in the
layout expected by upstream Tickstats' `generate.py`. The plugin authenticates with a
PAT, maintains a plugin-private shallow clone of the target repository, pushes only
when content has actually changed, produces one first-write-wins snapshot per local
calendar day, and stays entirely off the main server thread.

Technical approach (from user-provided stack + clarifications):

- Java 21 + Gradle (Kotlin DSL) + `com.gradleup.shadow` to ship one relocated fat JAR.
- Paper API 1.21 (`api-version: "1.21"`), tested against 1.21.11 stable and 1.21.12
  experimental.
- Embedded JGit (`org.eclipse.jgit` + `org.eclipse.jgit.http.apache`) for all git
  operations: clone once at first startup as
  `git clone --depth=1 --single-branch --branch=<config branch>` into
  `plugins/TickstatsSync/workdir/`, then for each cycle `fetch + hard-reset` to
  `origin/<branch>`, stage the current stats, check the working-tree diff via
  JGit's `Status` API, commit, push. This cycle-style avoids merge/rebase conflicts
  entirely: we never accumulate local orphan commits, and we never hold history
  for any branch other than the configured one. No external `git` binary — every
  operation goes through JGit. Fallback if a JGit shallow+push bug ever bites:
  drop `setDepth(1)` but keep `setBranchesToClone(...)` (full history, single
  branch) — documented in research R2.
- `com.cronutils:cron-utils` (Unix flavor) for schedule parsing and next-fire
  computation; the scheduler runs as a one-shot async task that reschedules itself
  after each firing.
- `PatMasker` + `SafeLogger` centralize credential redaction; every log emission
  inside the plugin goes through the wrapper.
- In-game commands route through a single `/tickstats <sub>` entry point gated by
  `tickstats.admin`.
- Concurrent-invocation rejection (FR-003a) is enforced by a single `SyncLock` that
  records the start `Instant` of the in-flight cycle. `/tickstats sync` rejects with
  `A sync is already in progress (started <N>s ago). Try again in a moment.`; the
  scheduler skips with a WARNING log `Scheduled sync skipped: previous sync still
  running (held for <N>s)` and does not reschedule — the next cron occurrence fires
  normally. The lock is released in a strict `finally` block around the full cycle.
- The project `README.md` documents setup in the same fine-grained-first hierarchy
  used in [contracts/config-schema.md](contracts/config-schema.md): fine-grained PAT
  as the primary procedure, classic PAT in a secondary "Alternative for
  organizations without fine-grained PAT support" section with the dedicated-bot +
  Write-role (not Admin) warning. This sectioning is a contract that authoring
  tasks in `/speckit.tasks` MUST follow.

## Technical Context

**Language/Version**: Java 21 (LTS), required by Paper 1.21.x and 1.21.12/build
26.1.2 experimental.
**Primary Dependencies**:
- `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT` (compile-only, provided by
  the server)
- `org.eclipse.jgit:org.eclipse.jgit` + `org.eclipse.jgit.http.apache` (shaded,
  relocated)
- `com.cronutils:cron-utils` (shaded, relocated)
- SnakeYAML — **NOT shaded**, consumed through Paper's bundled instance via the
  Bukkit `FileConfiguration` API.
- No other runtime dependencies.

**Storage**: Filesystem only.
- Input (read-only): the server's stats directory, default `world/stats/` (path
  configurable).
- Plugin state (read-write, plugin-owned): `plugins/TickstatsSync/workdir/` — the
  shallow clone of the target repository.
- Config (read-write on first boot, read on reload): `plugins/TickstatsSync/config.yml`.

**Testing**:
- JUnit 5 + AssertJ for unit tests.
- In-process integration test for `GitService`: point it at a temp-directory bare
  repository, drive it through full cycles.
- In-process integration test for `CronScheduler`: inject a virtual clock, assert
  next-fire computation against known-good cases.
- In-process integration test for `ConfigService`: drive through valid and invalid
  YAML fixtures.
- Manual smoke test: Paper 1.21.11 local server + a disposable GitHub repo (see
  `quickstart.md`).

**Target Platform**: Paper server running on Java 21 (Linux/Windows/macOS host). Tests
target two Paper versions:
1. Paper 1.21.11 (latest stable at time of writing)
2. Paper 1.21.12 build 26.1.2 (experimental track)

**Project Type**: Single Gradle project — server-side Minecraft plugin (distributed as
one shaded JAR).

**Performance Goals**:
- Zero operator-perceptible TPS drop during sync on a server with 10+ concurrent
  players (SC-004).
- `/tickstats sync` acknowledgement in-game under 1 second (SC-009).
- Scheduler loop uptime 100% across transient failure scenarios (SC-010).

**Constraints**:
- Main thread budget: **zero** (every I/O, git, and network call runs off-thread).
- No external native dependencies (no system `git`, no system `ssh`).
- JAR footprint: JGit + cron-utils together shade to roughly 3–4 MB; acceptable for a
  single-purpose plugin.
- PAT must never appear in any observable output (FR-023, SC-007).

**Scale/Scope**:
- Per plugin instance: one Minecraft server → one GitHub repository.
- Typical stats file count: 10–200 JSON files (one per UUID ever logged in); each
  file is a few KB. Total working-set under 1 MB in almost all cases.
- Sync cadence: operator-configurable; expected between 4×/day (every 6 h) and
  96×/day (every 15 min). The architecture supports higher cadences but they're
  outside the expected operational envelope.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Checked against [.specify/memory/constitution.md](../../.specify/memory/constitution.md) v1.0.0.

| # | Principle | Status | How the design complies |
|---|-----------|--------|-------------------------|
| I | Strict Tickstats Compatibility Contract | ✅ Pass | `SyncOrchestrator` writes exclusively to `stats/<server-name>/data/<uuid>.json` and, on the first sync of each local day, `stats/<server-name>/snapshots/YYYY-MM-DD/<uuid>.json`. `StatsReader` returns `Map<UUID, byte[]>` — raw bytes, never parsed or transformed. No other paths touched. |
| II | Non-Intrusive Server Operation | ✅ Pass | Every I/O, git, and network call runs on a `BukkitScheduler` async task. Main thread only does command-argument parsing. `world/stats/` is strictly read-only; the only filesystem writes happen inside `plugins/TickstatsSync/workdir/`. |
| III | Declarative, Hot-Reloadable Configuration | ✅ Pass | `ConfigService.load()` validates the full YAML on boot and on `/tickstats reload`, producing an immutable `TickstatsSyncConfig`. Reload is atomic: either the new config fully validates and becomes active, or the old config stays in place with a specific error message to the operator. |
| IV | Credential Safety | ✅ Pass | `PatMasker` replaces PAT substrings with `***` in any string it sees. `SafeLogger` runs every emission through it. Git operations use `UsernamePasswordCredentialsProvider` with an opaque token; JGit never logs it. Exception messages are re-wrapped through `SafeLogger` before propagation. |
| V | Pragmatic Observability | ✅ Pass | `SyncOrchestrator` emits one structured log line per cycle outcome (timestamp, outcome, file count, commit SHA on success, duration ms, failure category on failure). `/tickstats status` reads atomic snapshots of metrics maintained by the orchestrator. |
| VI | Resilience To Transient Failures | ✅ Pass | Retry loop in `SyncOrchestrator`: up to `retry.max-attempts` (default 3) with exponential backoff starting at `retry.initial-backoff-seconds` (default 10). Scheduler reschedules regardless of outcome. All exceptions are caught at the orchestrator boundary; none escape to kill the scheduler. |
| VII | Sync Idempotence | ✅ Pass | After writing all files into the working copy, `GitService.hasChanges()` invokes `git.status()` on the tracked paths. If clean, commit/push is skipped and the cycle ends as `success-no-changes`. |
| VIII | Explicit Permissions | ✅ Pass | `plugin.yml` declares the `tickstats.admin` permission with `default: op` and maps every `/tickstats` subcommand to it. Non-admin invocations return the generic "Unknown command" response. |
| IX | Deployment Simplicity | ✅ Pass | Single shaded JAR via `com.gradleup.shadow`. JGit + cron-utils relocated under `com.skycryck.tickstatssync.shaded.*`. No external CLI tools required. Default `config.yml` written on first boot. |
| X | Tickstats Convention Alignment | ✅ Pass | Default `schedule.timezone = Europe/Paris`. Snapshot dirs named `YYYY-MM-DD`. Commit messages follow `Update stats for <server-name> — YYYY-MM-DD HH:mm` in English. |
| XI | English-Only Code And Artifacts | ✅ Pass | All source, Javadoc, class/method/field names, log lines, in-game messages, commit messages, `config.yml` keys/comments, and `README.md` authored in English. PR review gate catches drift. |

**No violations.** Complexity Tracking table left empty below.

## Project Structure

### Documentation (this feature)

```text
specs/001-paper-github-sync/
├── plan.md              # This file
├── spec.md              # Feature specification (ratified via /speckit.specify + /speckit.clarify)
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   ├── config-schema.md
│   ├── plugin-yml.md
│   ├── commands.md
│   └── repo-layout.md
├── checklists/
│   └── requirements.md  # From /speckit.specify
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
build.gradle.kts
settings.gradle.kts
gradle.properties
gradle/
└── wrapper/
    ├── gradle-wrapper.jar
    └── gradle-wrapper.properties
gradlew
gradlew.bat
.gitignore
LICENSE
README.md

src/main/java/com/skycryck/tickstatssync/
├── TickstatsSyncPlugin.java          # Paper main class — onEnable/onDisable lifecycle
├── config/
│   ├── ConfigService.java            # Load + validate config.yml → TickstatsSyncConfig
│   ├── TickstatsSyncConfig.java      # Immutable record of validated configuration
│   ├── GitHubConfig.java             # Nested record: repo, branch, token, author
│   ├── ServerConfig.java             # Nested record: name, stats-path
│   ├── ScheduleConfig.java           # Nested record: cron, timezone, sync-on-startup, snapshots-enabled
│   └── RetryConfig.java              # Nested record: max-attempts, initial-backoff-seconds
├── stats/
│   └── StatsReader.java              # Read world/stats/*.json as raw byte maps
├── git/
│   ├── GitService.java               # initOrOpenLocalClone / fetchAndResetToRemote / writeFiles / hasChanges / commitAndPush
│   └── GitOperationException.java    # Typed git errors (auth, network, conflict, io)
├── sync/
│   ├── SyncOrchestrator.java         # Full cycle: read → fetch+reset → write → hasChanges? → commit+push, with retry
│   ├── SyncOutcome.java              # Enum: SUCCESS_WITH_COMMIT / SUCCESS_NO_CHANGES / TRANSIENT_FAILURE_RETRIED / ABANDONED_FAILURE
│   ├── SyncMetrics.java              # Atomic snapshot of last-success, next-scheduled, file-count, last-reachability
│   └── SyncLock.java                 # Non-blocking "is a sync in progress" gate for concurrent-invocation rejection
├── scheduler/
│   └── CronScheduler.java            # Parse cron expression, compute next fire, self-reschedule after each run
├── command/
│   ├── TickstatsCommand.java         # /tickstats root — dispatches to sub-handlers
│   ├── SyncSubcommand.java
│   ├── StatusSubcommand.java
│   └── ReloadSubcommand.java
└── util/
    ├── PatMasker.java                # Single-source PAT redaction utility
    └── SafeLogger.java               # Wraps java.util.logging.Logger; routes every message through PatMasker

src/main/resources/
├── plugin.yml                        # Paper plugin descriptor
└── config.yml                        # Default config template (generated on first boot if absent)

src/test/java/com/skycryck/tickstatssync/
├── config/
│   └── ConfigServiceTest.java
├── git/
│   └── GitServiceTest.java           # Uses a temp-directory bare repo as the "remote"
├── stats/
│   └── StatsReaderTest.java
├── sync/
│   └── SyncOrchestratorTest.java     # End-to-end with stubbed scheduler and temp remote
├── scheduler/
│   └── CronSchedulerTest.java        # Virtual clock, asserts next-fire timing
└── util/
    └── PatMaskerTest.java            # Property-based: token values must never survive mask()

src/test/resources/
├── config/
│   ├── valid-minimal.yml
│   ├── valid-full.yml
│   ├── invalid-missing-repo.yml
│   └── invalid-bad-cron.yml
└── stats/
    └── sample-stats/                 # Fixture UUIDs + vanilla-shaped JSON bodies
```

**Structure Decision**: Single Gradle project under `com.skycryck.tickstatssync`. The
user's 8-module mental model maps onto nine packages (the extra one splits
`SyncOrchestrator` into its data types and a non-blocking lock class for explicit
concurrent-invocation rejection — FR-003a). Paper plugins are conventionally a single
JAR, so no multi-module split is warranted; the test tree mirrors `src/main/java` at
the package level.

## Complexity Tracking

> No constitution violations — nothing to justify here.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| _(none)_ | — | — |
