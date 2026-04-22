# Phase 0 Research — TickstatsSync

**Feature**: 001-paper-github-sync
**Date**: 2026-04-22
**Purpose**: Resolve every remaining technical decision before data-model, contracts,
and code. No `[NEEDS CLARIFICATION]` markers from the spec remain unresolved after
this phase.

---

## R1 — Paper command registration idiom for 26.x

**Decision**: Register `/tickstats` through Paper's Brigadier-backed Lifecycle API
(`LifecycleEvents.COMMANDS`), and declare only the permission node in `plugin.yml`.

**Verified (2026-04-22) against Paper docs + Javadoc** (valid on the 26.x line;
the lifecycle command API has been stable since it landed in 1.20.6 / 1.21.0):
- `LifecycleEvents.COMMANDS` FQN: `io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS`.
- `JavaPlugin.getLifecycleManager()` returns
  `io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager<Plugin>`.
- `Commands` FQN: `io.papermc.paper.command.brigadier.Commands`. Every node's source
  type is `io.papermc.paper.command.brigadier.CommandSourceStack`.
- Concrete registration sketch (compiles on Paper 26.x):

```java
this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
    LiteralCommandNode<CommandSourceStack> node = Commands.literal("tickstats")
        .requires(src -> src.getSender().hasPermission("tickstats.admin"))
        .then(Commands.literal("sync").executes(this::onSync))
        .then(Commands.literal("status").executes(this::onStatus))
        .then(Commands.literal("reload").executes(this::onReload))
        .build();
    event.registrar().register(node, "TickstatsSync administration");
});
```

**Version caveat**: `Commands.restricted(Predicate)` (added on the 1.21.x branch)
is available on 26.x but we still prefer the plain
`.requires(Predicate<CommandSourceStack>)` form for forward-portability across
Paper releases and because `.requires` has been stable since the lifecycle
command API landed in 1.20.6 / 1.21.0.

**Rationale**:
- Brigadier gives per-subcommand permission checks, clean argument validation, and
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
  in `onEnable` (after the bootstrap steps that install the PAT masker — see plan
  "Bootstrap sequence").
- `plugin.yml` declares only `permissions:` and plugin metadata.
- No `commands:` block in `plugin.yml`.

**Sources**:
- https://docs.papermc.io/paper/dev/command-api/basics/registration
- https://docs.papermc.io/paper/dev/command-api/basics/requirements
- https://jd.papermc.io/paper/1.21.4/io/papermc/paper/plugin/lifecycle/event/types/LifecycleEvents.html
- https://jd.papermc.io/paper/ (26.x Javadoc index — use the current published
  version at the time of onboarding; Paper's lifecycle command API is stable
  across the 26.x line)

---

## R2 — JGit version and module set

**Decision** (bumped 2026-04-22): Pin JGit to **`6.10.1.202505221210-r`** — the
latest 6.x service release. This includes the fix for **CVE-2025-4949** (XML
entity hardening). Previous pin was `6.10.0.202406032230-r` which predates the
advisory.

```kotlin
implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.1.202505221210-r")
implementation("org.eclipse.jgit:org.eclipse.jgit.http.apache:6.10.1.202505221210-r")
```

The Apache HTTP connector (not the default JDK connector) is chosen for its proven
HTTPS-proxy behavior and because it respects `JAVA_HTTP(S)_PROXY` env vars via Apache
HttpClient. Both artifacts are shaded and relocated.

**Rationale for the 6.x line**:
- JGit 6.x minimum is Java 11; runs cleanly on Java 25.
- Service release `6.10.1` (2025-05-22) is the latest 6.x; API-compatible with
  6.10.0.
- Pinning an explicit version keeps shaded-JAR output reproducible.

**Open question — stay on 6.x or jump to 7.x?**

JGit 7.x exists with notable improvements:

| Aspect | JGit 6.10.1 | JGit 7.6.0 |
|--------|-------------|------------|
| Release date | 2025-05-22 | 2026-03-02 |
| Java minimum | 11 | 17 |
| Push concurrency | baseline | "Do not always refresh packed-refs during ref updates" (7.x improvement) |
| Multi-pack index work | no | yes (landed in 7.5.0) |
| Security patches | CVE-2025-4949 included | includes all 6.x fixes |

Because Paper 26.x already requires Java 25, the "Java 17 minimum" in 7.x is
trivially satisfied and not a constraint.

**Arbitration (2026-04-22)**: this iteration stays on **JGit 6.10.1**. No jump to 7.x
now. Migration to 7.x is explicitly envisaged as a **v2 follow-up** *only* if concrete
push-performance issues surface against real operator repositories — there is no
present need. The small API audit (6.x → 7.x) is deferred until evidence-driven.
Document any such evidence in the v2 migration issue when it opens.

**Shallow+push status (verified 2026-04-22)**: no explicit release note in either
6.10.1 or 7.x claims a shallow+push-to-GitHub fix. The historical caveat stands —
our shallow clone fallback (documented below) is still load-bearing.

**Alternatives considered**:
- JDK built-in HTTP connector: rejected for proxy edge cases.
- Staying on 6.10.0: rejected after CVE-2025-4949 disclosure.

**Sources**:
- https://projects.eclipse.org/projects/technology.jgit/releases/6.10.1
- https://projects.eclipse.org/projects/technology.jgit/releases/7.6.0
- https://projects.eclipse.org/projects/technology.jgit/releases/7.0.0
- https://github.com/eclipse-jgit/jgit/tags

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

**Coexistence caveat — `HttpTransport.setConnectionFactory` is process-global**:
JGit's HTTP connection factory is a JVM-wide static. Calling
`HttpTransport.setConnectionFactory(new HttpClientConnectionFactory())` in our
`onEnable` affects every other plugin on the same server that uses JGit directly or
transitively. If another plugin also sets its own connector — or relies on JGit's
default JDK connector — whichever plugin enables last wins. Documented consequences:

- If we load last, we win and proxy handling is Apache-based for all JGit consumers
  on that server.
- If another plugin loads last and installs a different connector, our HTTPS calls go
  through their choice. This has no observable impact on correctness (HTTPS still
  works), but proxy-edge-case behavior may differ.
- The plugin does NOT try to detect or mediate this — the interaction is silent and
  benign in practice. Operators running multiple JGit-based plugins should be aware.

---

## R3 — cron-utils version and flavor

**Decision**: `com.cronutils:cron-utils:9.2.1` (verified 2026-04-22 as the latest
published release — there is no 9.3.x), configured with
`CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX)` for classic 5-field Unix
cron.

**Verified API shape**:
- `CronType.UNIX` is a valid enum value (the full enum is
  `{ CRON4J, QUARTZ, UNIX, SPRING, SPRING53 }`).
- Unix flavor requires **exactly 5 fields** (minute, hour, day-of-month, month,
  day-of-week). A 6-field expression under `CronType.UNIX` throws
  `IllegalArgumentException` at `parser.parse(...)`. Validation catches this during
  `ConfigService.load()`.
- `ExecutionTime.nextExecution(...)` signature (from source):

  ```java
  Optional<ZonedDateTime> nextExecution(final ZonedDateTime date);
  Optional<ZonedDateTime> lastExecution(final ZonedDateTime date);
  ```

  **Returns `Optional<ZonedDateTime>`, not a bare `ZonedDateTime`.** Implementation
  code MUST call `.orElseThrow(...)` or `.ifPresent(...)`. For a well-formed Unix
  cron expression the `Optional` is always present, but unwrapping unsafely would
  NPE on any library-side corner case — use `orElseThrow(IllegalStateException::new)`
  with a descriptive message. `CronScheduler` is the single call site; pin this
  idiom there once.

**Rationale**:
- Unix flavor matches operator muscle memory and the examples in `config.yml`
  (`0 */6 * * *`, `0 8,14,22 * * *`).
- cron-utils 9.2.x is Java 11-compatible and runs on Java 25.
- `nextExecution(ZonedDateTime)` returns zone-aware millisecond-precise next-fire;
  DST handling is deterministic (the library falls back to UTC offsets on ambiguous
  local times, never double-fires).

**Alternatives considered**:
- Quartz Scheduler: overkill; brings triggers, job stores, and thread pools we don't
  need. Also tricky to shade cleanly.
- Home-grown cron parser: wastes time; cron-utils' DST handling alone justifies the
  dependency.
- `java.time.DayOfWeek` + hand-rolled "every N hours": too limited for the "at 08,
  14, 22" acceptance scenario.

**Sources**:
- https://github.com/jmrozanec/cron-utils/blob/master/src/main/java/com/cronutils/model/time/ExecutionTime.java
- https://github.com/jmrozanec/cron-utils/blob/master/src/main/java/com/cronutils/model/CronType.java
- https://github.com/jmrozanec/cron-utils/releases

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

**Local-clone corruption detection (for `GitService.initOrOpenLocalClone()`)**: on
every plugin start and on every cycle, the plugin tests the local working copy against
a fixed list of failure signals. Hitting ANY of them triggers a full rebuild
(`rm -rf workdir` + re-clone) without operator intervention, per FR-010a:

1. `workdir/` exists but contains no `.git/` directory — or `.git/` is missing its
   core files (`HEAD`, `config`, `refs/`).
2. `git.getRepository().resolve("HEAD")` throws or returns null.
3. The remote URL configured in `.git/config` does not match the current
   `github.repo` after resolution.
4. A `fetch` on the existing working copy raises
   `org.eclipse.jgit.errors.RepositoryNotFoundException`, `CorruptObjectException`, or
   `IOException` with a broken-object signature.
5. `git.status()` itself throws — meaning the index or working tree is structurally
   unreadable.

Any other fetch error (auth, network, conflict) is treated as a normal transient
failure and does NOT trigger a rebuild. The distinction matters: we don't want to
re-clone on every 401, or we'd burn bandwidth without solving the underlying
credential issue.

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

1. Try to acquire `SyncLock`. If acquired, run the sync cycle (via
   `SyncOrchestrator.runOnce(Trigger.SCHEDULED)` — the cron path always passes
   `SCHEDULED`; `MANUAL` and `STARTUP` are passed by the command and bootstrap
   call-sites respectively); if not acquired, emit a WARNING log
   `Scheduled sync skipped: previous sync still running (held for <N>s)` and
   proceed directly to step 2 — **the next scheduled firing is re-armed
   regardless** of whether the cycle ran. There is no catch-up cycle and no
   missed-occurrence queue.
2. Compute next-fire from
   `executionTime.nextExecution(ZonedDateTime.now(zone)).orElseThrow(...)` — the
   library returns `Optional<ZonedDateTime>` (see R3); for a well-formed Unix
   expression it is always present, but we unwrap safely and log a clear error if
   the Optional is empty (indicates a library regression, not an operator issue).
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

**Decision**: install a single `java.util.logging.Filter` on the plugin's root
`Logger` at the **very first statement** of `onEnable()`, before any other code
runs. The filter consults `PatMasker` and rewrites the log record's message through
`PatMasker.mask(...)` before the record reaches any handler.

```java
// First statement of onEnable()
PatMasker masker = new PatMasker();                         // no token yet
Logger pluginLogger = getLogger();
pluginLogger.setFilter(new LogRedactionFilter(masker));     // catches every record
// ... later, ConfigService.load() runs; once a token is resolved,
// masker.setToken(token) is called so subsequent log lines are redacted.
```

`PatMasker` holds a single volatile `String currentToken` (null/empty before the
first config load). `mask(String s)` returns `s.replace(currentToken, "***")` when
`currentToken` is non-empty, otherwise returns `s` unchanged. The filter uses the
rendered record text (including formatted arguments and any throwable's message)
produced by a lightweight internal `Formatter`, runs it through
`PatMasker.mask(...)`, and publishes the redacted record via the standard handler
chain.

**Rationale**:
- Single enforcement point: any code path that emits through
  `java.util.logging.Logger` — including JGit's own logger hierarchy, since JGit uses
  JUL — is caught regardless of author discipline.
- No wrapper class: `SafeLogger` (formerly proposed) is removed; plugin code calls
  `Logger.getLogger(...)` or `getLogger()` normally. There is nothing to audit.
- Rotation works automatically: on `/tickstats reload` the masker's token reference
  is updated and the same filter keeps running.
- `String.replace` is O(n·m) but strings are small (log lines) and n·m is bounded.

**Alternatives considered**:
- Caller-discipline wrapping (`SafeLogger` class with mandatory `log(...)` calls):
  rejected — every new contributor has to remember the convention, and library code
  (JGit) bypasses it entirely. The Filter approach is an upgrade, not a regression:
  a single filter installation wins at the JUL root handler, which JGit uses too.
- Regex-based heuristic ("anything that looks PAT-shaped"): would catch valid tokens
  but also produce false positives on commit SHAs. Rejected.

**Startup ordering invariant**: `PatMasker` construction + filter installation MUST
precede every other statement in `onEnable()`, including any `getLogger().info(...)`
banner. If a line of code above the masker install ever logs the token — say, during
future changes — the token will leak. Reviewers MUST reject PRs that add code above
the masker install.

**Library-side double-check**: JGit's `UsernamePasswordCredentialsProvider` does not
log the password. We additionally set `-Dorg.eclipse.jgit.http.debug=false` at
startup as a belt-and-suspenders measure.

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

### Shadow plugin coordinate and version (verified 2026-04-22)

**Plugin ID**: `com.gradleup.shadow` — the active fork. The old
`com.github.johnrengelman.shadow` upstream was archived when maintenance
transferred to the GradleUp org.

**Pinned version**: `9.4.1` (released 2026-03-27). Application syntax:

```kotlin
plugins {
    id("com.gradleup.shadow") version "9.4.1"
}
```

**Prerequisites of Shadow 9.4.1**:
- **Gradle 9.0+** — see the Gradle wrapper subsection below for the exact pin.
- **Java 17+** (runtime; trivially satisfied by Paper 26.x's Java 25 requirement).
- Shadow 9.4.1 also updated its embedded ASM / jdependency to handle Java 26 class
  files — useful headroom if the project ever moves off Java 25.

### Gradle wrapper version (arbitrated 2026-04-22)

**Decision**: pin the Gradle wrapper to **Gradle 9.4.1** (released 2026-03-19 — the
latest stable Gradle 9.x at the time of planning). The pin is a conscious choice:
it aligns the build-tool major with Shadow 9.4.1's current toolchain and keeps the
project on the version line where Shadow lands fixes.

`gradle/wrapper/gradle-wrapper.properties`:

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.4.1-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

The `distributionSha256Sum` field is recommended in addition, pulled from
<https://gradle.org/release-checksums/#9.4.1>, and added in the same commit that
generates the wrapper (so nobody has to hand-edit later).

**Rollback path (not the default)**: if a blocker appears on Gradle 9.x
specifically — for example, a Shadow 9.4.x regression that can't be worked around —
the documented rollback is to pin:

- Gradle wrapper to the last 8.x stable (Gradle 8.14.x as of this research date).
- Shadow to **`8.3.x`** (the last 8.x-compatible line, same coordinate
  `com.gradleup.shadow`).

This rollback is explicitly NOT the default. It exists only as a one-commit escape
hatch, should the need arise. No code or contract should assume it.

**Sources**:
- https://gradleup.com/shadow/
- https://github.com/GradleUp/shadow/releases
- https://plugins.gradle.org/plugin/com.gradleup.shadow
- https://gradle.org/releases/
- https://services.gradle.org/distributions/gradle-9.4.1-bin.zip
- https://gradle.org/release-checksums/

---

## R10 — Folia compatibility

**Decision (arbitrated 2026-04-22)**: **Paper only. Folia is NOT supported in v1.**

- `plugin.yml` does NOT declare `folia-supported: true`.
- The plugin uses `Bukkit.getScheduler().runTaskLaterAsynchronously(...)` throughout.
- Folia operators attempting to load the JAR get an explicit refusal from Folia's
  own plugin loader (Folia hard-gates on the missing flag) — which is the honest
  behavior, since our scheduler code would throw `UnsupportedOperationException` on
  Folia anyway.
- `README.md` MUST carry the line:
  `Folia is not supported. Targeting Paper 26.1.2+ (build #19 or later, JDK 25 required).`

**Audience-mismatch justification**:
TickstatsSync's target audience is Paper operators running ~10–100-player servers —
the community-scale, single-region workloads that Tickstats' dashboard was built
for. Folia targets the opposite end: sharded, high-concurrency servers (1000+
players, multi-region threading). There is no meaningful overlap. Adding dual
Paper/Folia support would pay a steady tax in code, testing, and documentation to
serve a population that is unlikely to deploy a stats-sync plugin at all.

**Context confirmed against Folia docs and source** (the "why" for future readers):

1. Folia refuses to load plugins without `folia-supported: true` — full stop.
2. `BukkitScheduler.runTaskAsynchronously` / `runTaskLaterAsynchronously` throw
   `UnsupportedOperationException` on Folia; async work there goes through
   `getServer().getAsyncScheduler()` returning
   `io.papermc.paper.threadedregions.scheduler.AsyncScheduler`, with delays in
   `(long, TimeUnit)` rather than ticks.
3. A hypothetical Folia path would require runtime detection
   (`Class.forName("io.papermc.paper.threadedregions.RegionizedServer")`), a branch
   in `CronScheduler`, and the `folia-supported: true` manifest flag.

**Revisit criteria (v2)**: revisit the Paper-only decision only if a concrete user
request arrives from a Folia operator asking for TickstatsSync to run on their
deployment. At that point, Option B (dual support, ~30 LOC in `CronScheduler`
plus a Folia smoke test) is a self-contained follow-up. Until then, no code and
no docs mention Folia outside this research entry.

**Sources**:
- https://docs.papermc.io/paper/dev/folia-support/
- https://jd.papermc.io/folia/1.21/io/papermc/paper/threadedregions/scheduler/AsyncScheduler.html
- https://github.com/PaperMC/Folia

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

**Decision**: `/tickstats reload` follows this sequence, with a strict ordering
that prevents any token-leakage window:

1. Call `plugin.reloadConfig()` to refresh Paper's `FileConfiguration`.
2. Invoke `ConfigService.load()` which validates the full tree. On any error, the
   old `TickstatsSyncConfig` reference stays active and the command reports the
   error — no partial apply.
3. **Update `PatMasker` token FIRST**: `patMasker.setToken(newConfig.token())`.
   From this instant, any log line that mentions either the old or the new token
   is redacted (the masker uses the current token for `replace`; prior log records
   are already past the filter).
4. **Swap the active config reference** atomically (single volatile write on
   `ConfigService`'s `AtomicReference<TickstatsSyncConfig>`).
5. Call `cronScheduler.reschedule(newConfig)`, which cancels the pending task and
   arms a new one from the new cron expression.
6. Command replies with the new "next scheduled sync" time.

**No reachability probe**. If the new configuration has an incorrect PAT, a wrong
`owner/repo`, or an unreachable host, the next scheduled cron tick (or an immediate
`/tickstats sync`) surfaces the failure through the normal observability path —
log line with `outcome=FAILURE category=AUTH|NETWORK` and `/tickstats status`
showing the broken state. The reload command's job is limited to validating
**local** shape (YAML parses, fields present, cron compiles, timezone resolves);
network validation is deferred to the next actual sync.

**Ordering rationale** (steps 3 and 4):

- Token-update-before-config-swap closes the race where a background thread,
  reading the newly-swapped config for an in-flight git operation, could log
  through a PatMasker whose token hasn't yet been updated — leaking the new token
  before the first filter hit after reload.
- Config-swap-before-scheduler-reschedule is safe because the scheduler reads the
  config fresh when it arms the next task.

**Rationale for dropping the reachability probe**:
- The probe duplicated the very next sync's auth/network checks.
- A probe that passes does not guarantee a subsequent sync will pass (network state
  changes in seconds).
- Eliminating the probe removes an HTTP round-trip, a second failure mode
  ("reload succeeded, but five minutes later sync fails"), and an out-of-band
  code path that duplicated `GitService.fetchAndReset` minus the reset step.

**Alternatives considered**:
- Lock-based swap with a `ReadWriteLock`: more complex, no observable benefit given
  read-mostly access and immutable config records.
- Keep the probe: rejected per over-engineering audit — duplicates the next sync.
- Swap config before updating masker: rejected — leaves a window for token leakage
  during the reload's own post-swap log lines.

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

## R15 — Error categorization and log line format

**Decision**: `SyncOrchestrator` labels every failure with one of the five
categories below. Each sync cycle emits exactly one log line at `INFO` (success /
no-changes) or `WARNING` (failure) level, conforming to the pinned format further
down.

**Failure categories**:

| Category | Example |
|----------|---------|
| `AUTH` | HTTP 401/403 from GitHub — PAT missing scope or revoked |
| `NETWORK` | `UnknownHostException`, connect/read timeout, TLS failure |
| `CONFLICT` | Push rejected non-fast-forward after fetch+reset (rare; external writer) |
| `IO` | Filesystem error in `world/stats/` or `workdir/` |
| `UNKNOWN` | Catch-all for unexpected exceptions, including plugin bugs |

No `CONFIG` category: configuration is validated at load/reload time and the
runtime pipeline never sees an invalid `TickstatsSyncConfig`. A runtime assertion
break on config shape falls under `UNKNOWN` (it's a plugin bug, not an operator
issue).

**Pinned log line format**:

```
[TickstatsSync] outcome=<OUTCOME> trigger=<TRIGGER> files=<N> commit=<sha7-or-none> duration_ms=<N> category=<CATEGORY-or-none> attempts=<N>
```

Every field is always present; fields not applicable to the outcome use `none`:

| Field | Always present? | Value when outcome applies | Value when not applicable |
|-------|-----------------|----------------------------|---------------------------|
| `outcome` | yes | `SUCCESS_WITH_COMMIT` / `SUCCESS_NO_CHANGES` / `FAILURE` | — (never empty) |
| `trigger` | yes | one of `SCHEDULED` / `MANUAL` / `STARTUP` — the `SyncOrchestrator.Trigger` value that kicked the cycle | — (never empty) |
| `files` | yes | count of files staged (or pushed) this cycle | `0` if nothing was read / written |
| `commit` | yes | 7-char short SHA on `SUCCESS_WITH_COMMIT` | `none` otherwise |
| `duration_ms` | yes | total wall-clock ms from cycle start to outcome | — (always present) |
| `category` | yes | one of the 5 `FailureCategory` values on `FAILURE` | `none` on success outcomes |
| `attempts` | yes | number of attempts the cycle executed (≥ 1) | — (always present) |

Example success lines:

```
[TickstatsSync] outcome=SUCCESS_WITH_COMMIT trigger=SCHEDULED files=42 commit=a1b2c3d duration_ms=812 category=none attempts=1
[TickstatsSync] outcome=SUCCESS_NO_CHANGES trigger=SCHEDULED files=42 commit=none duration_ms=124 category=none attempts=1
[TickstatsSync] outcome=SUCCESS_WITH_COMMIT trigger=MANUAL files=42 commit=e5f6a7b duration_ms=2348 category=none attempts=2
```

Example failure lines:

```
[TickstatsSync] outcome=FAILURE trigger=SCHEDULED files=42 commit=none duration_ms=1532 category=AUTH attempts=3
[TickstatsSync] outcome=FAILURE trigger=STARTUP files=0 commit=none duration_ms=62 category=IO attempts=1
```

**Rationale for this format**:
- `key=value` is trivially grep-able and jq-convertible (`awk '{for(i=2;i<=NF;i++)print $i}' | sort | uniq -c`).
- Fixed field order + always-present fields lets operators write monitoring rules without
  null checks.
- The `[TickstatsSync]` prefix matches Paper's plugin log convention so it appears
  consistently in `server.log` alongside other plugins.
- No free-form tail; operators get all the detail they need in structured form and
  the full throwable (for `FAILURE`) is logged via a subsequent `.log(Level.WARNING, throwable)`
  call whose rendered output also flows through the `LogRedactionFilter`.

**Rationale**:
- Operators debugging a broken sync first need to know "is this me or GitHub?".
  Five categories map cleanly to the five common root causes.
- `UNKNOWN` surfaces plugin bugs distinctly from operator/environment issues.

**Alternatives considered**:
- Free-form error strings: harder to grep, breaks status UI.
- HTTP-status-only categories: insufficient (filesystem errors have no HTTP status).

---

## R16 — Paper 26.1.2 pivot (supersedes earlier dual 1.21.x / 26.x targeting)

**Decision**: Target **Paper 26.1.2 only**. Drop the earlier "1.21.11 stable +
1.21.12/26.1.2 experimental" dual-track positioning. Build against
`io.papermc.paper:paper-api:26.1.2.build.19` (strict pin — see
"Deterministic-build pin" subsection below) on JDK 25, ship against
`api-version: "26.1.2"`.

**Motivations (explicitly traced)**:

1. **Alignment with Paper's current versioning scheme**. Starting with the 26.x
   line, Paper's Maven coordinate scheme drops the `-R0.1-SNAPSHOT` suffix and
   uses `<mc-version>.build.<build-number>` directly (e.g.
   `26.1.2.build.19`), matching the
   Minecraft-version-equals-Paper-version convention documented in the Paper
   project-setup guide. Staying on 1.21.x would anchor us to the legacy coordinate
   scheme and a discontinued API surface.
2. **Avoidance of a dual build matrix**. A 1.21.x + 26.x matrix would double the
   CI cost (two `paper-api` versions, two test runs), fragment the `api-version`
   declaration in `plugin.yml`, and require feature detection at runtime for
   APIs that moved between the two lines. The plugin's feature surface is narrow
   (stats read + git push + scheduler + 3 commands) and doesn't justify that tax.
3. **Alignment with the maintainer's real test environment**. The maintainer's
   own Paper server runs on 26.1.2 build #19. Test feedback matches deployment
   reality, and smoke tests (T046) run on the exact target.

**Deterministic-build pin (no `+` dynamic suffix)**:

The paper-api coordinate is pinned strictly to
`io.papermc.paper:paper-api:26.1.2.build.19` — no `+` dynamic suffix. Rationale:

- **Reproducibility first.** A dynamic `+` suffix would let Gradle pick whatever
  build the Paper repository happens to publish at resolution time, which means
  a dev-local build and a CI build (or two CI builds minutes apart) can silently
  diverge. Shaded JARs would differ in Paper-API bytecode without any code
  change in this project. That's the opposite of what a release pipeline needs.
- **Conscious bumps only.** Paper publishes frequent 26.1.2 builds; most of
  them don't affect TickstatsSync's narrow API surface (stats read +
  `Bukkit.getScheduler()` + `LifecycleEvents.COMMANDS` + `JavaPlugin`). Bumping
  the pin is a deliberate act: read the
  [PaperMC release notes](https://github.com/PaperMC/Paper/releases) for every
  published build between the current pin and the candidate new pin, confirm
  none of them touch the APIs we depend on (or that the change is desired),
  update the single `build.gradle.kts` line, and re-run the T046 smoke + T046a
  `generate.py` verification before shipping.
- **No auto-upgrade surprises.** If Paper publishes a 26.1.2 build that
  regresses the lifecycle command API or changes `getLifecycleManager()`'s
  signature, dynamic resolution would break our build on the next `./gradlew
  build` with no local change. A strict pin isolates us until we opt in.

The tradeoff — occasional manual review — is acceptable because builds are
published on a cadence slower than our release cadence. Operators who want the
absolute latest Paper can always keep their server build current; the plugin's
`api-version: "26.1.2"` floor remains compatible with every 26.1.2 build (and
forward to 26.1.3+ by policy; see "api-version consequences" below).

**JDK 25 requirement (build + runtime)**:

- Paper 26.x's compiled bytecode targets Java 25, so both the build toolchain
  and the runtime JRE MUST be JDK 25 or higher.
- The Gradle wrapper provisions JDK 25 automatically via the
  `org.gradle.toolchains.foojay-resolver-convention` plugin, which MUST be
  enabled in `settings.gradle.kts`:

  ```kotlin
  plugins {
      id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
  }
  ```

  The `java` block in `build.gradle.kts` then declares:

  ```kotlin
  java {
      toolchain {
          languageVersion.set(JavaLanguageVersion.of(25))
      }
  }
  ```

  Contributors without JDK 25 on their PATH get it auto-downloaded through
  foojay on first build. Those behind corporate proxies that block foojay MUST
  install JDK 25 manually (Temurin, Azul Zulu, Liberica — any vendor works).

**`api-version: "26.1.2"` consequences**:

- Paper refuses to load the plugin on servers older than 26.1.2 with a clear
  console message. This is the intended behavior: we cannot test every legacy
  Paper build, so we hard-gate on the known-good line.
- When Paper publishes 26.1.3 (or 27.x), the plugin can be re-tested and
  re-shipped with a bumped `api-version`; until that happens, 26.1.2 is the
  authoritative floor.

**Sources**:
- https://docs.papermc.io/paper/dev/project-setup/ (coordinate scheme + api-version)
- https://github.com/PaperMC/Paper (README, 26.x branch)
- https://docs.gradle.org/current/userguide/toolchains.html
- https://github.com/gradle/foojay-toolchains

**Supersedes**: all earlier references to 1.21.11, 1.21.12, and "dual Paper
targeting" in R1, R2, R10 (Folia disclaimer wording), and the plan's Technical
Context. Those sections have been edited in place. Any residual mention of
1.21.x in this research document is preserved only for historical API caveats
(e.g., when an API landed on 1.21.x before being carried into 26.x).

---

## Remaining unresolved items

None. Every Technical Context entry is resolved; every spec `[NEEDS CLARIFICATION]`
marker was resolved in `/speckit.clarify` (see `spec.md` Clarifications section).
