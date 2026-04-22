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

- Java 25 + **Gradle 9.4.1** (Kotlin DSL, pinned in
  `gradle/wrapper/gradle-wrapper.properties`) + `com.gradleup.shadow:9.4.1` to ship
  one relocated fat JAR. JDK 25 is required both at build time and at runtime
  because Paper 26.x runs on Java 25 (see research R16). The Gradle 9.x target is
  a conscious alignment with the active Shadow toolchain; rollback to Gradle
  8.14.x + Shadow 8.3.x is documented in research R9 as a one-commit escape hatch,
  but is not the default path.
- Paper API **26.1.2** (`api-version: "26.1.2"`), pinned strictly to
  `io.papermc.paper:paper-api:26.1.2.build.19`. No `+` dynamic suffix — the pin
  keeps dev-local and CI builds bit-identical; manual bumps are made consciously
  after reading the PaperMC release notes. There is no dual 1.21.x / 26.x build
  matrix — the pivot rationale and Paper's `<Minecraft-version>` =
  `<Paper-version>` coordinate convention are documented in research R16.
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
- `PatMasker` + a `java.util.logging.Filter` (installed on the plugin logger in the
  **first statement of `onEnable()`**) centralize credential redaction; every log
  record — including JGit's own JUL output — passes through the filter before
  publication. No wrapper class, no author discipline required.
- In-game commands route through a single `/tickstats <sub>` entry point gated by
  `tickstats.admin`.
- Concurrent-invocation rejection (FR-003a) is enforced by a single `SyncLock` that
  records the start `Instant` of the in-flight cycle. `/tickstats sync` rejects with
  `A sync is already in progress (started <N>s ago). Try again in a moment.`; on
  skip, the scheduler emits a WARNING log `Scheduled sync skipped: previous sync
  still running (held for <N>s)`, **re-arms the next scheduled firing** via
  `runTaskLaterAsynchronously`, and does NOT queue a catch-up cycle. The lock is
  released in a strict `finally` block around the full cycle.
- The project `README.md` documents setup in the same fine-grained-first hierarchy
  used in [contracts/config-schema.md](contracts/config-schema.md): fine-grained PAT
  as the primary procedure, classic PAT in a secondary "Alternative for
  organizations without fine-grained PAT support" section with the dedicated-bot +
  Write-role (not Admin) warning. The `README.md` MUST also carry a prominent
  compatibility line near the top:
  `Folia is not supported. Targeting Paper 26.1.2+ (build #19 or later, JDK 25 required).`
  This sectioning and disclaimer line are a contract that authoring tasks in
  `/speckit.tasks` MUST follow. The exact disclaimer (post-26.1.2 pivot) is:
  `Folia is not supported. Targeting Paper 26.1.2+ (build #19 or later, JDK 25 required).`
- On `onEnable()` with an invalid `config.yml`, the plugin stays **enabled-but-inert**:
  it registers its commands, `/tickstats reload` remains operational as the escape
  hatch, `/tickstats status` renders a red `⚠ config invalid: <reason>` banner at
  the top of its output, and `/tickstats sync` politely refuses with an English
  error message pointing the operator at the reload flow. The plugin is NEVER
  auto-disabled — `disablePlugin(this)` would kill the commands and strand the
  operator with no in-game recovery path.

### Bootstrap sequence (onEnable)

The order of service instantiation inside `TickstatsSyncPlugin.onEnable()` is
load-bearing for credential safety and must be preserved exactly:

1. **Instantiate `PatMasker`** — first statement, before any logger call.
2. **Install `LogRedactionFilter`** on `getLogger()` — immediately after step 1,
   still before any other code.
3. **Set JGit static config**: `HttpTransport.setConnectionFactory(new HttpClientConnectionFactory())`,
   `System.setProperty("org.eclipse.jgit.http.debug", "false")`.
4. **Instantiate `SyncMetrics`, `SyncLock`** (stateless singletons; can't log the
   PAT since they never see it).
5. **Run `ConfigService.load()`**. If this throws or validation fails, record the
   reason in `SyncMetrics.configInvalidReason` and continue to step 6 — DO NOT
   return early. Inert-mode still needs commands registered.
6. **Call `patMasker.setToken(config.token())`** — only if step 5 succeeded. In
   inert mode, the masker stays at the no-op default (`currentToken == null`).
7. **Instantiate `StatsReader`, `GitService`, `SyncOrchestrator`, `CronScheduler`** —
   references the config via `ConfigService`. In inert mode these can be
   constructed but `cronScheduler.start()` is NOT called.
8. **Register `TickstatsCommand`** through `LifecycleEvents.COMMANDS`. This happens
   in both healthy and inert mode so operators can run `/tickstats reload`.
9. **Start `CronScheduler`** — only in healthy mode.
10. **(Optional)** Fire an immediate sync if `syncOnStartup=true`.

Any code added above step 2 is a constitutional violation (Principle IV): review
MUST reject it.

## Technical Context

**Language/Version**: Java 25, required by Paper 26.x. The Gradle toolchain block
pins JDK 25; wrapper provisioning goes through the
`org.gradle.toolchains.foojay-resolver-convention` plugin in `settings.gradle.kts`
so contributors don't have to install JDK 25 manually. See research R16.
**Primary Dependencies**:
- `io.papermc.paper:paper-api:26.1.2.build.19` (compile-only, provided by the
  server; **strict pin**, no `+` dynamic suffix — see research R16 for the
  deterministic-build rationale)
- `org.eclipse.jgit:org.eclipse.jgit:6.10.1.202505221210-r` +
  `org.eclipse.jgit.http.apache:6.10.1.202505221210-r` (shaded, relocated; includes
  CVE-2025-4949 fix — see research R2)
- `com.cronutils:cron-utils:9.2.1` (shaded, relocated; no 9.3.x exists — verified)
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
- Manual smoke test: Paper 26.1.2 (build #19 or later) local server + a disposable
  GitHub repo (see `quickstart.md`).

**Target Platform**: Paper 26.1.2 server running on Java 25 (Linux/Windows/macOS
host). A single Paper target is supported — there is no dual 1.21.x / 26.x build
matrix (see research R16 for the pivot rationale).

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
| IV | Credential Safety | ✅ Pass | `PatMasker` + `LogRedactionFilter` (a `java.util.logging.Filter` installed on the plugin's root logger at the first statement of `onEnable`) redact the PAT out of every JUL record — including JGit's own logger output — before publication. Git operations use `UsernamePasswordCredentialsProvider` with an opaque token; JGit never logs it. No author discipline is required: the filter catches every emission from any caller. |
| V | Pragmatic Observability | ✅ Pass | `SyncOrchestrator` emits one structured log line per cycle outcome (timestamp, outcome, file count, commit SHA on success, duration ms, failure category on failure). `/tickstats status` reads atomic snapshots of metrics maintained by the orchestrator. |
| VI | Resilience To Transient Failures | ✅ Pass | Retry loop in `SyncOrchestrator` is **branched by `FailureCategory`**: `NETWORK`, `CONFLICT`, and `IO` retry up to `retry.max-attempts` (default 3) with exponential backoff starting at `retry.initial-backoff-seconds` (default 10); `AUTH` and any non-transient `UNKNOWN` abandon immediately at attempt 1 with a specific log line (`giving up immediately — non-transient failure, check your PAT and repo configuration`). Scheduler reschedules regardless of outcome. All exceptions are caught at the orchestrator boundary; none escape to kill the scheduler. |
| VII | Sync Idempotence | ✅ Pass | After writing all files into the working copy, `GitService.hasChanges()` invokes `git.status()` on the tracked paths. If clean, commit/push is skipped and the cycle ends as `success-no-changes`. |
| VIII | Explicit Permissions | ✅ Pass | `plugin.yml` declares the `tickstats.admin` permission node with `default: op`. Commands are registered through Paper's Brigadier `LifecycleEvents.COMMANDS` (not via a `commands:` block in `plugin.yml`), and every subcommand node carries a `.requires(source -> source.getSender().hasPermission("tickstats.admin"))` gate. Non-admin senders see the server's generic "Unknown or incomplete command" response — Brigadier hides the subcommand from tab-completion and rejects invocation. |
| IX | Deployment Simplicity | ✅ Pass | Single shaded JAR via `com.gradleup.shadow`. JGit + cron-utils + Apache HttpClient + slf4j + Bouncy Castle are all relocated under `com.skycryck.tickstatssync.shaded.*`. **SnakeYAML is intentionally NOT shaded** — Paper 26.x ships SnakeYAML 2.x on its own classpath and exposes it through the Bukkit `FileConfiguration` API, so the "YAML parser" dependency in Principle IX is satisfied by the Paper runtime the plugin already requires. Shading SnakeYAML on top would duplicate 300 KB of classes, risk split-classloader weirdness when Paper's and ours resolve the same class name, and produce no operator-visible benefit. The constitutional spirit ("operator drops one JAR, nothing else to install") is preserved: the operator still drops exactly one JAR; the YAML parser it depends on is already present on every Paper server by definition. No external CLI tools required. Default `config.yml` written on first boot. |
| X | Tickstats Convention Alignment | ✅ Pass | Default `sync.timezone = Europe/Paris`. Snapshot dirs named `YYYY-MM-DD`. Commit messages follow `Update stats for <server-name> - YYYY-MM-DD HH:mm` (ASCII hyphen-minus `-`, not em-dash — see [contracts/repo-layout.md](contracts/repo-layout.md)) in English. |
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
├── TickstatsSyncPlugin.java          # Paper main class — onEnable/onDisable lifecycle (bootstrap order is load-bearing, see above)
├── config/
│   ├── ConfigService.java            # Load + validate config.yml → TickstatsSyncConfig
│   └── TickstatsSyncConfig.java      # Flat immutable record — all config fields, no nested sub-records
├── stats/
│   └── StatsReader.java              # Read world/stats/*.json as raw byte maps
├── git/
│   ├── GitService.java               # initOrOpenLocalClone / fetchAndResetToRemote / writeFiles / hasChanges / commitAndPush
│   └── GitOperationException.java    # Typed git errors (auth, network, conflict, io, unknown)
├── sync/
│   ├── SyncOrchestrator.java         # Full cycle: read → fetch+reset → write → hasChanges? → commit+push, with retry (per-cycle state in local vars, no SyncCycle class)
│   ├── SyncOutcome.java              # Enum: SUCCESS_WITH_COMMIT / SUCCESS_NO_CHANGES / FAILURE
│   ├── SyncMetrics.java              # Atomic snapshot of last-success, next-scheduled, file-count, last-reachability, last-attempts
│   └── SyncLock.java                 # Non-blocking "is a sync in progress" gate for concurrent-invocation rejection
├── scheduler/
│   └── CronScheduler.java            # Parse cron expression, compute next fire, self-reschedule after each run
├── command/
│   └── TickstatsCommand.java         # /tickstats root — single class with private onSync/onStatus/onReload methods, all registered through one Brigadier builder
└── util/
    ├── PatMasker.java                # Single-source PAT redaction (volatile token ref)
    └── LogRedactionFilter.java       # java.util.logging.Filter installed on plugin logger — catches every record including JGit's

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
│   ├── invalid-bad-cron.yml
│   └── invalid-malicious-yaml.yml    # Contains !!java.net.URL etc. — asserts SnakeYAML 2.x rejects non-safe tags
└── stats/
    └── sample-stats/                 # Fixture UUIDs + vanilla-shaped JSON bodies
```

**Structure Decision**: Single Gradle project under `com.skycryck.tickstatssync`. The
user's 8-module mental model maps onto seven packages plus a consolidated
`TickstatsCommand` class (post-audit: nested config records, `SyncCycle`, and three
`*Subcommand` files were flattened into single files; `SafeLogger` became a JUL
`Filter` installed on the plugin logger). Paper plugins are conventionally a single
JAR, so no multi-module split is warranted; the test tree mirrors `src/main/java` at
the package level.

### Assumptions (planning-level)

- **SnakeYAML 2.x is provided by Paper runtime.** Paper 26.x bundles SnakeYAML 2.x,
  which uses `SafeConstructor` by default and rejects non-safe tags such as
  `!!java.net.URL`. The plugin depends on this via Paper's `FileConfiguration` API
  without shading SnakeYAML itself. `ConfigServiceTest` carries an
  `invalid-malicious-yaml.yml` fixture that asserts SnakeYAML 2.x refuses such tags,
  so any regression in Paper's bundled YAML parser surfaces at build time.
- The Paper server host has outbound HTTPS to `api.github.com` and `github.com`
  (also a spec-level assumption; restated here because JGit's Apache connector
  inherits the JVM's proxy settings).

## Complexity Tracking

> No constitution violations — nothing to justify here.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| _(none)_ | — | — |

---

### Footnote — Target path is not configurable

The path inside the target GitHub repository where stats land is **fixed by
Tickstats convention** at `stats/<server.name>/…` (`data/` and `snapshots/…`).
No `github.target-path` or `github.target_path` key exists in `config.yml`, and
`TickstatsSyncConfig` carries no `targetPath` field. `GitService.writeFiles`
writes directly under the repository root. If a future user needs a custom
target (e.g., to colocate multiple Tickstats feeds in one repository under
different prefixes), it would be a v2 feature and would require a constitution
amendment to Principle I. The current layout guarantee (see
[contracts/repo-layout.md](contracts/repo-layout.md)) is load-bearing for
upstream `generate.py`.
