# Phase 0 Research — TickstatsSync

**Feature**: 001-paper-github-sync
**Date**: 2026-04-22
**Purpose**: Resolve every remaining technical decision before data-model, contracts,
and code. No `[NEEDS CLARIFICATION]` markers from the spec remain unresolved after
this phase.

---

## R1 — Paper command registration idiom for 1.21.x

**Decision**: Register `/tickstats` through Paper's Brigadier-backed Lifecycle API
(`LifecycleEvents.COMMANDS`), and declare only the permission node in `plugin.yml`.

**Rationale**:
- Paper 1.21 formally exposes `io.papermc.paper.command.brigadier.Commands` through
  `LifecycleEventManager`, replacing the legacy `plugin.yml`-registered command path.
- Brigadier gives us per-subcommand permission checks, clean argument validation, and
  proper "unknown command" fallthrough for unauthorized senders (FR-025).
- Declaring the command in `plugin.yml` in addition to Brigadier would fragment the
  permission model.

**Alternatives considered**:
- `CommandExecutor` + `plugin.yml`: works everywhere but is deprecated-shaped on
  modern Paper and makes subcommand permissions clunky.
- `PluginManager.registerCommand` reflection hack: avoided — not future-proof.

**Consequences for implementation**:
- `TickstatsCommand` is registered via
  `plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, ...)`
  in `onEnable`.
- `plugin.yml` declares only `permissions:` and plugin metadata.
- No `commands:` block in `plugin.yml`.

---

## R2 — JGit version and module set

**Decision**: Pin JGit to `6.10.0.202406032230-r` (latest 6.x line at Java 21 baseline)
using two artifacts:

```kotlin
implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")
implementation("org.eclipse.jgit:org.eclipse.jgit.http.apache:6.10.0.202406032230-r")
```

The Apache HTTP connector (not the default JDK connector) is chosen for its proven
HTTPS-proxy behavior and because it respects `JAVA_HTTP(S)_PROXY` env vars via Apache
HttpClient. Both artifacts are shaded and relocated.

**Rationale**:
- JGit 6.x targets Java 11+ and runs cleanly on Java 21.
- The `http.apache` add-on handles corporate proxy and SNI edge cases better than
  JGit's default `JDKHttpConnector`.
- Pinning an explicit version (not a SNAPSHOT or `[6.0,)`) keeps shaded-JAR output
  reproducible — important for releases.

**Alternatives considered**:
- JGit 7.x (dropped Java 11, still Java 11-compatible): considered but 6.10.x is the
  stable line most downstream projects pin against and has broader compatibility if
  a consumer later downgrades to Java 17.
- JDK built-in HTTP connector: rejected for proxy edge cases.

**Implementation note**: on plugin startup, set
`HttpTransport.setConnectionFactory(new HttpClientConnectionFactory())` once, before
any `GitService` call.

**Clone invocation**: `GitService.initOrOpenLocalClone()` performs the equivalent of
`git clone --depth=1 --single-branch --branch=<github.branch> <remote-url>
plugins/TickstatsSync/workdir`. In JGit this is:

```java
Git.cloneRepository()
   .setURI(remoteUrl)
   .setDirectory(workdir)
   .setDepth(1)
   .setBranch(branch)
   .setBranchesToClone(List.of("refs/heads/" + branch))
   .setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, token))
   .call();
```

The `--single-branch` behavior comes from restricting `setBranchesToClone` to the one
configured branch. Combined with `setDepth(1)`, the working copy carries a single
commit's worth of history for the single relevant branch — minimal disk, minimal
bandwidth.

**Fallback for shallow+push edge cases**: JGit has historically carried intermittent
bugs around pushing from shallow clones (most notably requiring the server to support
the `shallow` capability and emit a `deepen-since` response). If the chosen JGit
version is observed to fail pushes from a depth-1 clone against `github.com`, the
documented fallback is to drop `setDepth(1)` while keeping
`setBranchesToClone(...)` — a full-history single-branch clone. Disk footprint rises
proportionally to branch history (typically still a few MB for a stats-only repo)
but the push path is bulletproof. The switch is a one-line change in `GitService`
and MUST NOT introduce a multi-branch clone.

---

## R3 — cron-utils version and flavor

**Decision**: `com.cronutils:cron-utils:9.2.1`, configured with
`CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX)` (5-field classic Unix
cron). Evaluation uses `ExecutionTime.forCron(...)` with a `ZonedDateTime` in the
operator-configured timezone (default `Europe/Paris`).

**Rationale**:
- Unix flavor matches operator muscle memory and the examples in `config.yml`
  (`0 */6 * * *`, `0 8,14,22 * * *`).
- cron-utils 9.2.x is Java 11-compatible and runs on Java 21.
- `ExecutionTime.nextExecution(ZonedDateTime)` gives us millisecond-precise next-fire
  computation; DST handling is deterministic (the library falls back to UTC offsets
  on ambiguous local times, never double-fires).

**Alternatives considered**:
- Quartz Scheduler: overkill; brings triggers, job stores, and thread pools we don't
  need. Also tricky to shade cleanly.
- Home-grown cron parser: wastes time; cron-utils' DST handling alone justifies the
  dependency.
- `java.time.DayOfWeek` + hand-rolled "every N hours": too limited for the "at 08,
  14, 22" acceptance scenario.

---

## R4 — Git cycle shape: fetch+hard-reset vs rebase

**Decision**: Each cycle performs **fetch → hard-reset to origin/branch → stage current
stats → diff → commit if changed → push**. The plugin never holds unpushed local
commits across cycles. If push fails (conflict, 401, network), the cycle aborts; the
next cycle starts fresh against the new remote tip.

**Rationale**:
- Eliminates the whole rebase/merge conflict class: the working tree always starts at
  `origin/branch`, so there's nothing to rebase.
- Idempotence is trivial: `git.status()` after `writeFiles` tells us whether there's
  anything to commit.
- Crash-safety: if the server dies mid-cycle, the local clone still tracks the remote
  cleanly on next boot (we reset again).
- Matches constitution Principle XV (never force-push) — we only push new commits
  that are ahead of the remote by exactly one commit.

**Alternatives considered**:
- Rebase-on-conflict: preserves a partial commit across a failed cycle, but adds a
  stateful recovery path and can snowball if conflicts persist. The hard-reset
  approach is simpler to reason about and cheaper to implement.
- Force-push on conflict: forbidden by FR-015.

**Push-conflict semantics**: a push conflict after fetch+reset is rare (would require
another producer writing to the same branch between our fetch and our push). When it
happens, we classify as `transient-failure`, log with redacted context, and let the
retry loop handle it.

---

## R5 — Daily snapshot first-write-wins detection

**Decision**: Before writing any `snapshots/YYYY-MM-DD/` content, `SyncOrchestrator`
checks the **working tree** (which is up-to-date with remote after `fetchAndReset`)
for a directory entry at that path. If present, snapshot writing is skipped for this
cycle. The check uses
`Files.isDirectory(workdir.resolve("stats").resolve(serverName).resolve("snapshots").resolve(today))`.

**Rationale**:
- The working copy is always current with the remote tip after `fetchAndReset`, so
  filesystem presence is a truthful proxy for remote state.
- No extra network call needed.
- Race-free within a single plugin (we reject concurrent `/tickstats sync` per FR-003a).

**Alternatives considered**:
- GitHub REST API `GET /contents/...`: another network call; not needed given the
  fetch.
- Commit-message grep of recent history: brittle; breaks if a human manually
  rebuilt snapshots.

---

## R6 — Scheduler: cron-utils → Bukkit ticks

**Decision**: `CronScheduler` is a self-rescheduling async task. On each firing:

1. Run the sync cycle (via `SyncOrchestrator.runOnce()`).
2. Compute next-fire from `ExecutionTime.nextExecution(ZonedDateTime.now(zone))`.
3. Convert the delta-milliseconds to server ticks (`deltaMs / 50`), clamp to a
   minimum of 1 tick.
4. Call `scheduler.runTaskLaterAsynchronously(plugin, this, ticks)` to arm the next
   firing.

**Rationale**:
- One-shot self-rescheduling lets us pick up cron-expression changes atomically on
  reload: after `/tickstats reload`, we cancel the pending task and arm a fresh one
  from the new expression.
- No persistent thread pool to manage — Paper's scheduler is the pool.
- Tick-based delay is fine at cron granularities (minutes). A 50 ms granularity is
  far finer than cron's 1-minute resolution.

**Alternatives considered**:
- `ScheduledExecutorService`: would work but duplicates Paper's scheduler and risks
  a task surviving a plugin disable.
- Fixed-period `runTaskTimerAsynchronously`: incompatible with cron (non-uniform
  intervals).

**DST handling**: cron-utils computes the next firing against the configured
timezone's wall clock. During DST transitions, ambiguous hours resolve deterministically
per the library. We never double-fire because each firing schedules exactly one
successor.

---

## R7 — Idempotence detection with JGit

**Decision**: `GitService.hasChanges()` returns
`!git.status().call().isClean()` restricted to the `stats/` subtree. If the status
call reports any `added`, `changed`, `modified`, `missing`, `untracked`, or `removed`
entries under `stats/`, we commit; otherwise we skip.

**Rationale**:
- `git.status()` is the canonical, content-aware diff against the index + HEAD.
  Matches how a human would verify "did anything change?".
- Cheaper than a content-hash per file because JGit uses stat-info shortcuts.
- Byte-for-byte accurate because `writeFiles` always writes the full current content
  into the working copy; identical files produce identical SHA-1 blobs.

**Alternatives considered**:
- Per-file SHA-256 compare against previously-synced hashes kept in memory:
  duplicates what git already tracks and adds a cache invalidation problem across
  restarts.

---

## R8 — Credential redaction strategy

**Decision**: `PatMasker` holds a volatile `String currentToken` reference. On every
config load, the new token replaces the old. `PatMasker.mask(String s)` returns
`s.replace(currentToken, "***")` when `currentToken` is non-empty, otherwise returns
`s` unchanged. `SafeLogger` wraps `java.util.logging.Logger` and runs every message
— and every exception message/stack-trace summary — through `PatMasker.mask(...)`.

**Rationale**:
- Single source of truth for the active token means rotation (via `/tickstats reload`)
  automatically updates redaction scope.
- All plugin code is required to go through `SafeLogger` — enforced by review and by
  a spotbugs/checkstyle rule forbidding `Logger.getLogger(...)` calls outside
  `util/`.
- `String.replace` is O(n·m) but strings are small (log lines) and n·m is bounded.

**Alternatives considered**:
- java.util.logging `Filter`: would require installing the filter on every Logger a
  downstream library might create; fragile.
- Regex-based heuristic ("anything that looks PAT-shaped"): would catch valid tokens
  but also produce false positives on commit SHAs. Rejected.

**Extra precaution**: JGit's `UsernamePasswordCredentialsProvider` does not log the
password. We double-check by setting `-Dorg.eclipse.jgit.http.debug=false` and by
wrapping JGit's `Logger` facade through `SafeLogger` where possible. JGit exception
`.getMessage()` is passed through the masker before emission.

---

## R9 — Shadow relocation strategy

**Decision**: All shaded classes are relocated under
`com.skycryck.tickstatssync.shaded`:

| Source package | Shaded package |
|----------------|----------------|
| `org.eclipse.jgit` | `com.skycryck.tickstatssync.shaded.jgit` |
| `com.cronutils` | `com.skycryck.tickstatssync.shaded.cronutils` |
| `org.apache.http` | `com.skycryck.tickstatssync.shaded.httpclient` |
| `org.apache.hc` | `com.skycryck.tickstatssync.shaded.hc` |
| `org.slf4j` | `com.skycryck.tickstatssync.shaded.slf4j` |
| `org.bouncycastle` | `com.skycryck.tickstatssync.shaded.bc` |

`java-util`, `jsch`, and SSH transports from JGit are **excluded** (we don't use SSH
auth). SnakeYAML is not shaded — consumed through Paper's bundled copy.

**Rationale**:
- Prevents classpath collisions if another plugin on the same server already ships
  JGit, cron-utils, or Apache HttpClient.
- Keeps the plugin's namespace predictable; dumps and stack traces show
  `com.skycryck.tickstatssync.shaded.*` instead of unrelocated library packages.
- Excluding JSch trims ~1 MB off the JAR without losing functionality — we only speak
  HTTPS.

**Alternatives considered**:
- No relocation: risks breakage on servers with other git-based plugins.
- Module-path isolation via Java 9 modules: incompatible with Paper's classloader.

---

## R10 — Folia compatibility

**Decision**: Ship the plugin as Folia-safe without claiming Folia support. No
`folia-supported: true` in `plugin.yml`, but the code avoids Folia-forbidden patterns
(main-thread assumptions, global state shared across region threads).

**Rationale**:
- The plugin does not touch any per-region state (entities, worlds, blocks). Every
  async task is independent of region threads.
- Adding `folia-supported: true` requires a Folia test matrix we're not committing
  to in this iteration.
- Users running Folia can safely load the plugin by editing `plugin.yml` themselves;
  that's out of scope.

**Alternatives considered**:
- Declare full Folia support: would require testing Folia builds. Deferred.
- Reject Folia loads: unnecessarily restrictive.

---

## R11 — Token sourcing: config file vs environment variable

**Decision**: `ConfigService` reads `github.token`. If the value is empty or absent,
it falls back to the `TICKSTATSSYNC_GITHUB_TOKEN` environment variable. If both are
missing or empty, config validation fails with an explicit "no GitHub token supplied"
error — no degraded startup.

**Rationale**:
- Env-var fallback lets operators who manage secrets via systemd unit files,
  Docker Compose, or Pterodactyl avoid putting the PAT in plaintext on disk.
- Empty string in `config.yml` is the common case when using the env-var path, and
  the generated default config documents this.

**Alternatives considered**:
- Only env var: too inflexible for shared-host operators who don't control the
  server process environment.
- External secret manager integration (Vault, AWS Secrets Manager, HashiCorp, etc.):
  out of scope; over-engineered for the target audience.

---

## R12 — Testing framework and harness

**Decision**:
- **Unit tests**: JUnit 5 (`org.junit.jupiter:junit-jupiter`) + AssertJ
  (`org.assertj:assertj-core`).
- **Paper interaction**: no MockBukkit dependency. Paper-dependent code is thin
  (command registration, scheduler dispatch) and is tested through hand-rolled
  fakes: a `FakeScheduler` that captures scheduled runnables and lets the test
  advance a virtual clock; a `FakeCommandSender` for permission + output
  assertions.
- **Git tests**: each `GitServiceTest` method creates a `tmpfs`-style temp directory,
  initializes a **bare** repo as the "remote" via
  `Git.init().setBare(true).setDirectory(tmpRemote).call()`, points `GitService` at
  its `file://` URL, and asserts on the bare repo's refs/objects after cycles.
- **Cron tests**: inject a `Clock` into `CronScheduler` rather than using the wall
  clock; every assertion about "next fire" is deterministic.
- **Config tests**: drive `ConfigService` with YAML fixtures under
  `src/test/resources/config/`.

**Rationale**:
- MockBukkit lags Paper API by a few versions and doesn't fully model Paper's
  Brigadier commands. A narrow hand-rolled fake is faster to evolve.
- Bare repo as fake remote is the standard JGit testing pattern; no network calls,
  no credentials needed, no GitHub account in CI.

**Alternatives considered**:
- MockBukkit: rejected for version-drift reasons.
- Testcontainers + Gitea: heavyweight; gives full end-to-end but slower CI and adds
  a Docker requirement.

---

## R13 — `config.yml` hot-reload atomicity

**Decision**: `/tickstats reload` follows this sequence:
1. Call `plugin.reloadConfig()` to refresh Paper's `FileConfiguration`.
2. Invoke `ConfigService.load()` which validates the full tree. On any error, the
   old `TickstatsSyncConfig` reference stays active and the command reports the
   error — no partial apply.
3. On success, perform a repo reachability probe (HTTPS HEAD to
   `https://github.com/<owner>/<repo>.git/info/refs?service=git-upload-pack` with
   the new token) off-thread. If the probe fails, treat as config error (FR-019).
4. Swap the active config reference atomically (single volatile write).
5. Call `cronScheduler.reschedule(newConfig.schedule())`, which cancels the pending
   task and arms a new one from the new cron expression.
6. Call `patMasker.setToken(newConfig.github().token())` so log redaction tracks
   the new token.

**Rationale**:
- Probe-before-swap prevents a reload from silently installing a broken config.
- Single volatile write makes the swap atomic; ongoing reads of the reference see
  either the old or the new config, never a half-built object.
- Scheduler rescheduling is idempotent — cancelling a non-existent task is a no-op.

**Alternatives considered**:
- Lock-based swap with a `ReadWriteLock`: more complex, no observable benefit given
  read-mostly access and immutable config records.
- Validate-only reload (no reachability probe): rejected because a PAT typo would
  survive reload and only fail at the next cron tick.

---

## R14 — Status command output format

**Decision**: `/tickstats status` renders as plain-text lines with Minecraft chat
color codes (`§` prefix) applied sparingly:

```
§6TickstatsSync §7v1.0.0
§7Last successful sync: §a2026-04-22 14:00:03 (Europe/Paris)
§7Next scheduled sync:  §a2026-04-22 20:00:00 (Europe/Paris)
§7Detected stats files: §a42
§7PAT configured:       §ayes
§7Repo reachability:    §aok (checked at last sync)
```

Unhealthy states use `§c` red instead of `§a` green. The PAT value never appears in
this output (only the `yes/no` indicator).

**Rationale**:
- Paper renders `§` color codes natively in chat; legacy but universally supported.
- Multi-line response reads well in a chat window and a console log.
- Sparse color (only for values) keeps the output readable when color is stripped.

**Alternatives considered**:
- `Component` builder from Adventure API: richer (hover text, click actions) but
  adds plumbing for no operator-visible gain.
- JSON output: not operator-friendly in chat.

---

## R15 — Error categorization for observability

**Decision**: `SyncOrchestrator` labels every failure with one of:

| Category | Example |
|----------|---------|
| `AUTH` | HTTP 401/403 from GitHub — PAT missing scope or revoked |
| `NETWORK` | `UnknownHostException`, connect/read timeout, TLS failure |
| `CONFLICT` | Push rejected non-fast-forward after fetch+reset (rare; external writer) |
| `IO` | Filesystem error in `world/stats/` or `workdir/` |
| `CONFIG` | Invariant broken at runtime (e.g., server_name contains `/`) |
| `UNKNOWN` | Catch-all for unexpected exceptions |

Each log line includes the category; the metrics snapshot exposed to `/tickstats
status` tracks the most recent category per outcome.

**Rationale**:
- Operators debugging a broken sync first need to know "is this me or GitHub?".
  Five categories map cleanly to the five common root causes.
- `UNKNOWN` surfaces plugin bugs distinctly from operator/environment issues.

**Alternatives considered**:
- Free-form error strings: harder to grep, breaks status UI.
- HTTP-status-only categories: insufficient (filesystem errors have no HTTP status).

---

## Remaining unresolved items

None. Every Technical Context entry is resolved; every spec `[NEEDS CLARIFICATION]`
marker was resolved in `/speckit.clarify` (see `spec.md` Clarifications section).
