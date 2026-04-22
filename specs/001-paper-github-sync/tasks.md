# Tasks: TickstatsSync — Automated Paper-to-GitHub Stats Sync

**Feature**: 001-paper-github-sync
**Input**: Design documents from `/specs/001-paper-github-sync/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/, quickstart.md
**Tests**: Included. `plan.md` explicitly calls out JUnit 5 + AssertJ unit tests, a `GitServiceTest` against a bare temp repo, and a property-style `PatMaskerTest`; every user story therefore pairs its implementation with its verification.

**Organization**: Tasks are grouped by user story so each story can be implemented, tested, and delivered independently.

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks).
- **[Story]**: Which user story the task belongs to (`US1`..`US5`). Setup, Foundational, and Polish phases carry no `[Story]` label.
- Every description carries an exact file path under `src/` (or repository root for build files).

## Path Conventions

Single Gradle project — Paper plugin distributed as a single shaded JAR. Source roots:
- Production: `src/main/java/com/skycryck/tickstatssync/…`
- Resources: `src/main/resources/`
- Tests: `src/test/java/com/skycryck/tickstatssync/…`
- Test resources: `src/test/resources/`

All tasks below use these paths verbatim, as pinned in [plan.md](plan.md) §Project Structure.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project scaffold, build tooling, shaded-JAR pipeline, and repository-level docs. Nothing plugin-specific is written yet.

- [ ] T001 Create Gradle Kotlin DSL project scaffold — write `settings.gradle.kts`, `gradle.properties`, and an empty package tree under `src/main/java/com/skycryck/tickstatssync/{config,stats,git,sync,scheduler,command,util}/` and mirror under `src/test/java/com/skycryck/tickstatssync/…` (one `.gitkeep` per empty directory).
- [ ] T002 Pin the Gradle wrapper to 9.4.1 — run `gradle wrapper --gradle-version 9.4.1 --distribution-type bin` and commit `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, and `gradle/wrapper/gradle-wrapper.properties` with the exact values from [research.md](research.md) §R9 (including `distributionSha256Sum` from gradle.org/release-checksums).
- [ ] T003 Author `build.gradle.kts` at repo root — Java **25** toolchain (set via `java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }`), `io.papermc.paper:paper-api:26.1.2.build.19` (compileOnly; **strict pin**, no `+` dynamic suffix — see [research.md](research.md) §R16 for the deterministic-build rationale and the manual-bump procedure), `org.eclipse.jgit:org.eclipse.jgit:6.10.1.202505221210-r` + `org.eclipse.jgit.http.apache:6.10.1.202505221210-r`, `com.cronutils:cron-utils:9.2.1`, test stack `org.junit.jupiter:junit-jupiter` + `org.assertj:assertj-core`, Paper snapshot + Eclipse JGit repositories, and `processResources` expansion of `${project.version}` for `plugin.yml` (per [contracts/plugin-yml.md](contracts/plugin-yml.md)). Add a top-of-file comment: `// Compiled against the latest build of Paper 26.1.2. Requires JDK 25 runtime.` In `settings.gradle.kts`, enable `id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"` so the wrapper auto-provisions JDK 25 for contributors who don't already have it installed.
- [ ] T004 Apply and configure the Shadow plugin in `build.gradle.kts` — `id("com.gradleup.shadow") version "9.4.1"`, relocation map exactly as listed in [research.md](research.md) §R9 (`org.eclipse.jgit` → `com.skycryck.tickstatssync.shaded.jgit`, `com.cronutils` → `…shaded.cronutils`, `org.apache.http` → `…shaded.httpclient`, `org.apache.hc` → `…shaded.hc`, `org.slf4j` → `…shaded.slf4j`, `org.bouncycastle` → `…shaded.bc`), exclude JSch and SSH transports, leave SnakeYAML unshaded.
- [ ] T005 [P] Write `README.md` at repo root — setup walkthrough sourced from [quickstart.md](quickstart.md); PAT guidance in the fine-grained-first order documented by [contracts/config-schema.md](contracts/config-schema.md) with the classic-PAT dedicated-bot + Write-role warning; prominent compatibility disclaimer near the top reading exactly `Folia is not supported. Targeting Paper 26.1.2+ (build #19 or later, JDK 25 required).` per [plan.md](plan.md) §Summary. Include a "Building from source" section documenting: JDK 25 minimum (Temurin, Azul Zulu, Liberica — any vendor works); the Gradle wrapper auto-provisions JDK 25 through the `org.gradle.toolchains.foojay-resolver-convention` plugin enabled in `settings.gradle.kts` (T003); contributors behind corporate proxies that block foojay must install JDK 25 manually. No mention of Paper 1.21.x (the pivot in [research.md](research.md) §R16 replaced dual targeting with Paper 26.1.2-only).
- [ ] T006 [P] Refresh `.gitignore` at repo root — add `plugins/TickstatsSync/workdir/`, Gradle build artifacts (`build/`, `.gradle/`), IDE files (`.idea/`, `*.iml`, `.vscode/`), and any stray `*.log`. Do not remove existing entries committed in `474ba35`.

**Checkpoint**: `./gradlew clean shadowJar` succeeds and produces an empty-but-valid relocated JAR; the README and .gitignore are in place.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Credential-safety primitives, configuration plumbing, plugin descriptor, lifecycle bootstrap, and the command-skeleton needed by every user story. Everything in this phase MUST land before any user-story phase begins.

**⚠️ CRITICAL**: `PatMasker` + `LogRedactionFilter` MUST be wired as the first two statements of `TickstatsSyncPlugin.onEnable()`. Any code that logs above the filter install is a constitutional violation ([plan.md](plan.md) §Bootstrap sequence, [research.md](research.md) §R8).

- [ ] T007 [P] Implement `PatMasker` in `src/main/java/com/skycryck/tickstatssync/util/PatMasker.java` — single `volatile String currentToken`, `setToken(String)`, and `String mask(String)` that performs the literal `String.replace(currentToken, "***")` contract from [research.md](research.md) §R8; no-op when token is null or empty.
- [ ] T008 [P] Implement `LogRedactionFilter` in `src/main/java/com/skycryck/tickstatssync/util/LogRedactionFilter.java` — `java.util.logging.Filter` that formats every record through a lightweight internal `Formatter`, runs the text and any throwable message through `PatMasker.mask`, and re-writes the log record before publication (per the §R8 exact behavior).
- [ ] T009 [P] Author `PatMaskerTest` in `src/test/java/com/skycryck/tickstatssync/util/PatMaskerTest.java` — property-style coverage: token value never survives `mask()` across hundreds of randomized substrings; no-op before `setToken`; rotation swaps the redacted value with no leakage window.
- [ ] T010 [P] Author `LogRedactionFilterTest` in `src/test/java/com/skycryck/tickstatssync/util/LogRedactionFilterTest.java` — attach the filter to a hand-rolled `Logger`, publish `LogRecord`s carrying the token in message + throwable message + args, assert post-filter text scans free of the literal token.
- [ ] T011 [P] Define `TickstatsSyncConfig` record in `src/main/java/com/skycryck/tickstatssync/config/TickstatsSyncConfig.java` — flat record with the 13 fields listed in [data-model.md](data-model.md) §Configuration record; hand-rolled `toString()` that redacts `token` ([data-model.md](data-model.md) Lifecycle note).
- [ ] T012 Implement `ConfigService` in `src/main/java/com/skycryck/tickstatssync/config/ConfigService.java` — `load()` reads `plugins/TickstatsSync/config.yml` via Paper's `FileConfiguration`, applies the full validation table in [contracts/config-schema.md](contracts/config-schema.md), resolves `TICKSTATSSYNC_GITHUB_TOKEN` fallback ([research.md](research.md) §R11), returns a new immutable `TickstatsSyncConfig` through an `AtomicReference` holder; emits localized English error strings ("config error: …") as specified in [contracts/config-schema.md](contracts/config-schema.md) §Error reporting.
- [ ] T013 [P] Create config YAML fixtures under `src/test/resources/config/` — `valid-minimal.yml`, `valid-full.yml`, `invalid-missing-repo.yml`, `invalid-bad-cron.yml` (all per [contracts/config-schema.md](contracts/config-schema.md)).
- [ ] T014 Author `ConfigServiceTest` in `src/test/java/com/skycryck/tickstatssync/config/ConfigServiceTest.java` — drive each fixture from T013 through `ConfigService.load()`; assert that valid cases produce a record with the exact expected field values (including `Europe/Paris` default, `main` default, `3`/`10 s` retry defaults) and that each invalid case raises a message matching the specified English error string.
- [ ] T015 [P] Add the default `config.yml` resource at `src/main/resources/config.yml` — exact template from [contracts/config-schema.md](contracts/config-schema.md) §Template, including the header-comment PAT guidance (fine-grained first, classic as fallback, dedicated-bot + Write-role warning).
- [ ] T016 [P] Add `plugin.yml` at `src/main/resources/plugin.yml` — exact content from [contracts/plugin-yml.md](contracts/plugin-yml.md): `name`, `version: ${project.version}`, `main: com.skycryck.tickstatssync.TickstatsSyncPlugin`, `api-version: "26.1.2"`, `load: POSTWORLD`, author/description/website, `permissions.tickstats.admin.default: op`; NO `commands:` block; NO `folia-supported` flag. The `api-version` value gates plugin loading — servers running Paper older than 26.1.2 refuse to load the JAR with a clear console message ([contracts/plugin-yml.md](contracts/plugin-yml.md) field-by-field table; [research.md](research.md) §R16).
- [ ] T017 [P] Define `SyncOutcome` enum in `src/main/java/com/skycryck/tickstatssync/sync/SyncOutcome.java` — exactly `SUCCESS_WITH_COMMIT`, `SUCCESS_NO_CHANGES`, `FAILURE` ([data-model.md](data-model.md) §SyncOutcome).
- [ ] T018 [P] Define `FailureCategory` enum in `src/main/java/com/skycryck/tickstatssync/sync/FailureCategory.java` — exactly `AUTH`, `NETWORK`, `CONFLICT`, `IO`, `UNKNOWN` ([data-model.md](data-model.md) §FailureCategory; no `CONFIG` value).
- [ ] T019 [P] Define `GitOperationException` in `src/main/java/com/skycryck/tickstatssync/git/GitOperationException.java` — checked exception carrying a `FailureCategory category()` field as in [data-model.md](data-model.md) §GitOperationException.
- [ ] T020 [P] Implement `SyncMetrics` in `src/main/java/com/skycryck/tickstatssync/sync/SyncMetrics.java` — all atomic fields from [data-model.md](data-model.md) §SyncMetrics (including `AtomicReference<SyncOrchestrator.Trigger> lastTrigger`, written by T033 on every terminal state and read by T041 `/tickstats status`), plus the inner `Reachability { OK, FAILED, UNKNOWN }` enum and a `String configInvalidReason` slot used by inert mode ([plan.md](plan.md) §Bootstrap step 5).
- [ ] T021 [P] Implement `SyncLock` in `src/main/java/com/skycryck/tickstatssync/sync/SyncLock.java` — exact shape from [data-model.md](data-model.md) §SyncLock: `tryAcquire()`, `release()`, `heldSince()`, injected `Clock`.
- [ ] T022 [P] Author `SyncLockTest` in `src/test/java/com/skycryck/tickstatssync/sync/SyncLockTest.java` — virtual `Clock`, asserts `tryAcquire()` second call returns false while held; `release()` clears holder; `heldSince()` returns the correct `Instant`; FR-003a rejection contract covered.

> **F4 resolution — Foundational-phase stubs (T022a–T022d).** The four tasks
> below add compile-time stubs for the US1 services so `TickstatsCommand` (T023)
> and `TickstatsSyncPlugin` (T024) can compile and run at the end of Phase 2.
> **Invariants for every stub**: (1) the public signature MUST exactly match the
> final class signature documented in [data-model.md](data-model.md) and the
> corresponding US1 implementation task, so US1 replaces the body without
> changing any declared method signature; (2) every method throws
> `new UnsupportedOperationException("TickstatsSync service not yet implemented — Foundational-phase stub, implemented in user story US1")`;
> (3) constructors may accept the same dependencies as the final classes but
> store them without using them. These stubs let `TickstatsSyncPlugin.onEnable`
> instantiate the service graph and register commands in inert mode before US1
> lands, so the Phase 2 checkpoint is actually runnable.

- [ ] T022a [P] Stub `StatsReader` in `src/main/java/com/skycryck/tickstatssync/stats/StatsReader.java` — public method `StatsSnapshot read(Path statsDir)` that throws the `UnsupportedOperationException` literal defined above. Also add a matching stub `StatsSnapshot` record in `src/main/java/com/skycryck/tickstatssync/stats/StatsSnapshot.java` with the two fields from [data-model.md](data-model.md) §StatsSnapshot (`Map<UUID, byte[]> files`, `Instant readAt`). US1 T031 replaces the method body; the record is final as authored here.
- [ ] T022b [P] Stub `GitService` in `src/main/java/com/skycryck/tickstatssync/git/GitService.java` — constructor accepting `TickstatsSyncConfig` and `Path workdir`; public methods `void initOrOpenLocalClone()`, `void fetchAndResetToRemote()`, `void writeFiles(Map<UUID, byte[]> data)`, `boolean hasChanges()`, `String commitAndPush(String author, String email, String message)` (returns 7-char SHA), `void writeSnapshot(LocalDate today, Map<UUID, byte[]> data)`. Each method throws the `UnsupportedOperationException` literal. Signatures frozen here; US1 T032 replaces the bodies.
- [ ] T022c [P] Stub `SyncOrchestrator` in `src/main/java/com/skycryck/tickstatssync/sync/SyncOrchestrator.java` — constructor accepting `(ConfigService, StatsReader, GitService, SyncLock, SyncMetrics, Clock, Logger)`; public method `SyncOutcome runOnce(Trigger trigger)` where `Trigger` is a nested enum `{ SCHEDULED, MANUAL, STARTUP }` declared on the class. Throws the `UnsupportedOperationException` literal. US1 T033 replaces the body.
- [ ] T022d [P] Stub `CronScheduler` in `src/main/java/com/skycryck/tickstatssync/scheduler/CronScheduler.java` — constructor accepting `(Plugin, ConfigService, SyncOrchestrator, SyncMetrics, SyncLock, Logger)`; public methods `void start()`, `void stop()`, `void reschedule(TickstatsSyncConfig newConfig)`. Each throws the `UnsupportedOperationException` literal. US1 T034 replaces the bodies.

- [ ] T023 [P] Implement `TickstatsCommand` skeleton in `src/main/java/com/skycryck/tickstatssync/command/TickstatsCommand.java` — single class with constructor dependencies (`ConfigService`, `SyncLock`, `SyncMetrics`, `SyncOrchestrator`, `CronScheduler`, `PatMasker`, `Plugin`), private `onSync`, `onStatus`, `onReload` methods stubbed to reply `§c[TickstatsSync] Subcommand not yet implemented.`, and a `register(LifecycleEventManager<Plugin>)` method that builds the Brigadier tree per [research.md](research.md) §R1 with `.requires(src -> src.getSender().hasPermission("tickstats.admin"))` on every node. Compiles because T022a–T022d provide stub types for `StatsReader`, `GitService`, `SyncOrchestrator`, `CronScheduler` (the command constructor only holds references; stubbed methods are never invoked from the "not yet implemented" responses).
- [ ] T024 Implement `TickstatsSyncPlugin` main class in `src/main/java/com/skycryck/tickstatssync/TickstatsSyncPlugin.java` — extends `JavaPlugin`; `onEnable()` executes bootstrap steps 1–8 exactly as pinned in [plan.md](plan.md) §Bootstrap sequence (PatMasker FIRST, filter SECOND, `HttpTransport.setConnectionFactory(new HttpClientConnectionFactory())` + `System.setProperty("org.eclipse.jgit.http.debug", "false")` THIRD, metrics + lock, `ConfigService.load()` with inert-mode fallback writing `SyncMetrics.configInvalidReason`, `patMasker.setToken(config.token())` only in healthy mode, construct remaining services via the **T022a–T022d stubs** — they instantiate without side effects and throw only if operator code reaches their methods — register `TickstatsCommand` through `LifecycleEvents.COMMANDS` in both modes). Steps 9 and 10 (scheduler start, startup sync) are intentionally left as TODO-markers that reference the stubs; US1 T035 fills them in once T033 + T034 replace the bodies.

**Checkpoint**: Plugin loads into a Paper 26.1.2 dev server (build #19 or later, JDK 25 runtime); `/tickstats <sub>` returns the "not yet implemented" stub for admins and the generic unknown-command response for non-admins; invalid `config.yml` triggers inert mode without disabling the plugin.

---

## Phase 3: User Story 1 — Autonomous Scheduled Sync (Priority: P1) 🎯 MVP

**Goal**: Deliver the scheduled sync loop end-to-end: on every cron firing the plugin reads `world/stats/`, fetch+resets the shallow clone, writes `stats/<server>/data/<uuid>.json`, commits + pushes when content changed, emits a structured log line, and arms the next firing — all off the main thread, with credential redaction active for every observable byte.

**Independent Test**: Start a dev Paper 26.1.2 server (build #19 or later, JDK 25) with a valid config pointing at a disposable GitHub repo and cron `*/5 * * * *`. Within five minutes, observe a commit on the configured branch containing `stats/<server>/data/<uuid>.json` for every file in `world/stats/`. Let it idle for an hour: no empty commits land. Change one stats file: the next firing pushes exactly the diff.

### Tests for User Story 1

> **NOTE**: These tests MUST be authored before (or paired with) the implementation tasks below, must fail initially, and must pass by the end of the phase.

- [ ] T025 [P] [US1] Create stats fixture tree `src/test/resources/stats/sample-stats/` — three UUID-named JSON files with vanilla-shaped bodies (advancement + stats keys) plus one malformed filename (`not-a-uuid.json`) to prove filename filtering.
- [ ] T026 [P] [US1] Author `StatsReaderTest` in `src/test/java/com/skycryck/tickstatssync/stats/StatsReaderTest.java` — reads the T025 fixture, asserts exactly the three UUID keys are returned, byte arrays are byte-for-byte equal to the fixture files, non-UUID filename is dropped (logged at DEBUG), empty directory returns an empty map (edge case per [spec.md](spec.md) §Edge Cases).
- [ ] T027 [US1] Author `GitServiceTest` in `src/test/java/com/skycryck/tickstatssync/git/GitServiceTest.java` — each test creates a bare remote via `Git.init().setBare(true).setDirectory(tmp).call()` per [research.md](research.md) §R12 and drives a `GitService` against the `file://` URL: (a) initial clone produces a depth-1 single-branch workdir; (b) `fetchAndResetToRemote` after an external commit on the remote resets the local HEAD cleanly; (c) `writeFiles` + `hasChanges` returns true iff content differs; (d) `commitAndPush` updates the remote tip; (e) corruption signals listed in [research.md](research.md) §R4 each trigger a full rebuild; (f) remote-URL drift after config change triggers a rebuild.
- [ ] T028 [P] [US1] Author `SyncOrchestratorTest` in `src/test/java/com/skycryck/tickstatssync/sync/SyncOrchestratorTest.java` — uses hand-rolled fakes from [research.md](research.md) §R12 plus a bare-repo `GitService`: (a) clean state (called with `Trigger.SCHEDULED`) → `SUCCESS_NO_CHANGES`, no commit, log line matches pinned R15 format including `trigger=SCHEDULED attempts=1`; (b) changed stats → `SUCCESS_WITH_COMMIT` with 7-char SHA, `trigger=SCHEDULED`, `attempts=1`; (c) **transient retry** — first two attempts throw `NETWORK`, third succeeds → `SUCCESS_WITH_COMMIT trigger=SCHEDULED attempts=3` with exponential backoff observed via injected `Clock`; (d) **non-transient immediate abandon** — a single attempt throws `AUTH` (HTTP 401) → `FAILURE trigger=SCHEDULED attempts=1 category=AUTH` with a log line containing `giving up immediately — non-transient failure, check your PAT and repo configuration` (verifying FR-028); (d2) **transient exhaustion** — three attempts all throw `NETWORK` → `FAILURE trigger=SCHEDULED attempts=3 category=NETWORK`; (d3) **trigger plumbing** — one cycle called with `Trigger.MANUAL` produces a log line with `trigger=MANUAL` and writes `SyncMetrics.lastTrigger = MANUAL`; a second cycle with `Trigger.STARTUP` updates `lastTrigger = STARTUP`; (e) `SyncLock.release()` runs in the finally block even when an unchecked exception escapes (verify via a throwing fake).
- [ ] T029 [P] [US1] Author `CronSchedulerTest` in `src/test/java/com/skycryck/tickstatssync/scheduler/CronSchedulerTest.java` — injected virtual `Clock` + fake Paper scheduler capturing runnables: `0 */6 * * *` yields the correct `ZonedDateTime` in `Europe/Paris`; DST spring-forward and fall-back cases produce exactly one next-fire each (no double-fire); `ExecutionTime.nextExecution(…)` returning empty (simulated) is unwrapped via `orElseThrow(IllegalStateException::new)` with a descriptive message per [research.md](research.md) §R3.

### Implementation for User Story 1

- [ ] T030 [P] [US1] **Verify** the `StatsSnapshot` record authored in Foundational T022a carries the defensive-copy invariant per [data-model.md](data-model.md) §StatsSnapshot — unmodifiable map, per-entry `byte[]` defensive copies in the canonical constructor, strict UUID key typing. If T022a left this as a minimal shell, complete the defensive-copy logic here in the same file (`src/main/java/com/skycryck/tickstatssync/stats/StatsSnapshot.java`). No signature change.
- [ ] T031 [P] [US1] Replace the Foundational T022a stub body of `StatsReader.read(Path)` in `src/main/java/com/skycryck/tickstatssync/stats/StatsReader.java` — enforces the UUID filename regex `^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.json$`; retries a brief window on transient read lock (spec §Edge Cases); returns empty snapshot on missing-or-empty directory with a single INFO log line. Signature unchanged from T022a.
- [ ] T032 [US1] Replace the Foundational T022b stub bodies in `src/main/java/com/skycryck/tickstatssync/git/GitService.java` — `initOrOpenLocalClone()` performs the shallow clone invocation from [research.md](research.md) §R2 (setDepth(1) + setBranchesToClone(refs/heads/<branch>) + UsernamePasswordCredentialsProvider); `fetchAndResetToRemote()`; `writeFiles(Map<UUID, byte[]> data)` truncates `stats/<server>/data/` to exactly the current UUID set and writes each entry as a byte-for-byte copy; `hasChanges()` via `git.status()` restricted to the `stats/` subtree per §R7; `commitAndPush(author, email, message)` returns the 7-char SHA; `writeSnapshot(today, data)` writes `stats/<server>/snapshots/<today>/<uuid>.json` (invoked from US2 T037); corruption-detection rebuild path per §R4 (each of the five failure signals triggers `rm -rf workdir` + re-clone); wraps every failure in `GitOperationException` with the right `FailureCategory`. Signatures unchanged from T022b.
- [ ] T033 [US1] Replace the Foundational T022c stub body in `src/main/java/com/skycryck/tickstatssync/sync/SyncOrchestrator.java` — `runOnce(Trigger trigger)` captures `startedAt`, `syncDate = ZonedDateTime.now(config.timezone())` fixed at start (spec §Edge Cases), reads stats, fetchAndReset, writeFiles, `hasChanges?` (skip → `SUCCESS_NO_CHANGES`), commitAndPush, and applies **per-category retry policy** (FR-027 / FR-028): `NETWORK`, `CONFLICT`, `IO` retry up to `config.maxAttempts()` (default 3) with exponential backoff starting at `config.initialBackoff()` (default 10 s, doubling each attempt); `AUTH` and non-transient `UNKNOWN` **abandon immediately at attempt 1** and log `giving up immediately — non-transient failure, check your PAT and repo configuration` at WARNING, then emit the terminal structured line. Single try/finally around the whole cycle always releases `SyncLock` per [data-model.md](data-model.md) §SyncLock Release discipline. Commit message `Update stats for <server> - YYYY-MM-DD HH:mm` (ASCII hyphen-minus `-`, not em-dash) from [contracts/repo-layout.md](contracts/repo-layout.md). Structured log line matches [research.md](research.md) §R15 pinned format for every outcome — including the `trigger=<value>` field sourced from the `runOnce(Trigger trigger)` parameter. **`SyncMetrics.lastReachability` lifecycle** (closes F8 ambiguity — read by T041 `/tickstats status`): on `SUCCESS_WITH_COMMIT` or `SUCCESS_NO_CHANGES` set `Reachability.OK`; on `FAILURE` with `category=AUTH` or `category=NETWORK` set `Reachability.FAILED`; on `FAILURE` with `category=IO` that never touched the remote (e.g., `world/stats/` read failure before any fetch) leave `lastReachability` unchanged; on `CONFLICT` set `Reachability.OK` (we reached the remote, the push just raced). `SyncMetrics` updates on every terminal state, including `lastTrigger = trigger` so `/tickstats status` can render `Last outcome: <outcome> (trigger: <TRIGGER>, attempts: <N>)` per [contracts/commands.md](contracts/commands.md).
- [ ] T034 [US1] Replace the Foundational T022d stub bodies in `src/main/java/com/skycryck/tickstatssync/scheduler/CronScheduler.java` — parses `config.cronExpression()` under `CronType.UNIX`, on each firing calls `SyncLock.tryAcquire()`; if acquired, submits `SyncOrchestrator.runOnce(Trigger.SCHEDULED)` via `Bukkit.getScheduler().runTaskAsynchronously(plugin, …)`; if not acquired, logs `Scheduled sync skipped: previous sync still running (held for <N>s)` at WARNING ([data-model.md](data-model.md) §SyncLock); in both branches computes the next firing via `executionTime.nextExecution(ZonedDateTime.now(zone)).orElseThrow(IllegalStateException::new)`, converts delta to ticks with `Math.max(1, deltaMs / 50)`, and arms itself through `runTaskLaterAsynchronously`; exposes `start()`, `stop()`, and `reschedule(TickstatsSyncConfig)`.
- [ ] T035 [US1] Fill bootstrap steps 9 and 10 in `src/main/java/com/skycryck/tickstatssync/TickstatsSyncPlugin.java` — healthy mode starts `CronScheduler`, inert mode does not; if `config.syncOnStartup()` is true and `SyncLock.tryAcquire()` succeeds, submit one immediate `SyncOrchestrator.runOnce(Trigger.STARTUP)` on the async thread.

**Checkpoint**: User Story 1 is fully functional. A fresh Paper 26.1.2 server + disposable GitHub repo produces commits on schedule; idle cycles produce no commits; 30 minutes of runtime with forced transient failures prove the retry loop and the scheduler's uptime invariant (SC-010). **Phase 3 closure is gated by T046a** (`generate.py` verification, physically grouped with the smoke test in Polish Phase 8 but executed at Phase 3 close) — the MVP is not shippable until the Tickstats producer contract is verified end-to-end (SC-002).

---

## Phase 4: User Story 2 — Daily Snapshot Archive (Priority: P2)

**Goal**: On the first successful sync of each local calendar day (in `config.timezone()`) that finds no existing `stats/<server>/snapshots/YYYY-MM-DD/` directory, duplicate the current stats into that directory. First-write-wins; never overwritten; honors the `sync.snapshots-enabled` config toggle; dateline computed at cycle start so day-boundary crossings mid-sync cannot double-write.

**Independent Test**: With snapshots enabled and cron `*/5 * * * *`, let the plugin run past local midnight. At the first firing on the new day, observe a new `snapshots/YYYY-MM-DD/` directory with the current JSON files; subsequent same-day firings leave it untouched. Toggle `sync.snapshots-enabled: false` via `/tickstats reload` (once US5 lands) or a restart: no new snapshot directories are produced.

### Tests for User Story 2

- [ ] T036 [P] [US2] Extend `SyncOrchestratorTest` in `src/test/java/com/skycryck/tickstatssync/sync/SyncOrchestratorTest.java` — (a) first cycle of a virtual day creates `snapshots/YYYY-MM-DD/` with exactly the byte-for-byte stats files; (b) second cycle same day leaves the directory unchanged (no mtime shift, no re-commit for snapshots); (c) `config.snapshotsEnabled() == false` suppresses snapshot writes; (d) `syncDate` fixed at cycle start — advance the virtual clock across midnight during the cycle and assert the snapshot lands under the start-of-cycle date; (e) `Europe/Paris` DST roll-over produces the correct next-day folder name.

### Implementation for User Story 2

- [ ] T037 [US2] Add snapshot branch to `SyncOrchestrator.runOnce` in `src/main/java/com/skycryck/tickstatssync/sync/SyncOrchestrator.java` — after `fetchAndResetToRemote` and before `writeFiles(data/…)`, when `config.snapshotsEnabled()` is true, compute `today = syncDate.toLocalDate()`, check `Files.isDirectory(workdir.resolve("stats").resolve(serverName).resolve("snapshots").resolve(today.toString()))` per [research.md](research.md) §R5; if absent, write each stats entry to `snapshots/<today>/<uuid>.json` as a byte-for-byte copy. Both `data/` and `snapshots/<today>/` are staged in the same cycle and land in the same commit (commit message unchanged — the repo-layout guarantee is that the one commit reflects all changed paths).

**Checkpoint**: User Stories 1 and 2 are both independently functional. A day-boundary-crossing test run proves the once-per-day invariant (FR-009) and the DST-safe date selection.

---

## Phase 5: User Story 3 — Manual On-Demand Sync (Priority: P3)

**Goal**: `/tickstats sync` fires a cycle immediately, off the main thread, gated by `SyncLock` for concurrent-invocation rejection (FR-003a). In inert mode the command refuses politely and points the operator at `/tickstats reload`.

**Independent Test**: As an op, run `/tickstats sync` on a healthy plugin — observe the `§a[TickstatsSync] Sync started.` acknowledgement within one second (SC-009) and the terminal message matching the contract a few moments later. Run it twice in quick succession — the second invocation must reply with `§e[TickstatsSync] A sync is already in progress (started <N>s ago). Try again in a moment.` Run it as a non-admin — observe the generic "Unknown or incomplete command" response.

### Tests for User Story 3

- [ ] T038 [P] [US3] Author `TickstatsCommandSyncTest` in `src/test/java/com/skycryck/tickstatssync/command/TickstatsCommandSyncTest.java` — hand-rolled `FakeCommandSender` (admin + non-admin variants) and `FakeScheduler`: (a) admin invocation with lock free submits an async runnable and replies with the "Sync started" template; (b) admin invocation while `SyncLock.heldSince()` is set replies with the rejection template carrying the correct held-for seconds; (c) inert mode replies with the "Configuration is invalid (<reason>)" template from [contracts/commands.md](contracts/commands.md); (d) non-admin invocation is filtered by the `.requires` predicate (no executor runs); (e) post-sync success message includes the 7-char SHA and `attempts` count when > 1; (f) sender-offline at completion is dropped silently per contract.

### Implementation for User Story 3

- [ ] T039 [US3] Fill the `onSync` method in `src/main/java/com/skycryck/tickstatssync/command/TickstatsCommand.java` — inert-mode guard first (refuse with the config-invalid template using `SyncMetrics.configInvalidReason`), then `SyncLock.tryAcquire()`; on success send `§a[TickstatsSync] Sync started.`, dispatch `SyncOrchestrator.runOnce(Trigger.MANUAL)` through `Bukkit.getScheduler().runTaskAsynchronously(plugin, …)`, and register a completion callback that sends the terminal message from [contracts/commands.md](contracts/commands.md) (one-attempt, multi-attempt, no-changes, or failure variants) back to the original sender iff they are still online; on failed acquire send the "already in progress" template with the computed held-for seconds.

**Checkpoint**: User Stories 1, 2, and 3 are independently functional. Concurrent-invocation rejection is enforced between the scheduler and the manual command (both share the same `SyncLock` instance per [data-model.md](data-model.md) §SyncLock Caller contract).

---

## Phase 6: User Story 4 — Plugin Health Observability (Priority: P3)

**Goal**: `/tickstats status` renders the healthy-mode or inert-mode template from [contracts/commands.md](contracts/commands.md) with the correct colour coding, the PAT value absent from output (SC-007), and the configured-timezone timestamps.

**Independent Test**: Run `/tickstats status` after a successful sync — observe the healthy layout with green values and the correct `(Europe/Paris)` suffix on timestamps. Run it after a forced `AUTH` failure — observe the red reachability and `FAILURE (attempts: 3)` lines. Run it in inert mode — observe the `§c⚠ config invalid: <reason>` banner and the elided `Next scheduled sync` row.

### Tests for User Story 4

- [ ] T040 [P] [US4] Author `TickstatsCommandStatusTest` in `src/test/java/com/skycryck/tickstatssync/command/TickstatsCommandStatusTest.java` — feed `SyncMetrics` and `ConfigService` fixtures matching each of the four example layouts in [contracts/commands.md](contracts/commands.md) (healthy, never-synced, broken-PAT, inert); assert the rendered lines match character-for-character including `§` colour codes, timezone suffix, and the `(trigger: <TRIGGER>, attempts: <N>)` parenthetical on the "Last outcome" row (or `none yet` when `SyncMetrics.lastOutcome` is null); assert the literal PAT string never appears in any rendered line even when the masker has a token loaded; non-admin sender is filtered upstream by `.requires`.

### Implementation for User Story 4

- [ ] T041 [US4] Fill the `onStatus` method in `src/main/java/com/skycryck/tickstatssync/command/TickstatsCommand.java` — read `SyncMetrics` atomically (one snapshot per invocation to avoid flicker, including `lastTrigger`); detect inert mode via `SyncMetrics.configInvalidReason != null` and render the inert template (no `Next scheduled sync` row, red banner with the stored reason, hint directing the operator to `/tickstats reload`); otherwise render the healthy template with green/yellow/red colour semantics from [contracts/commands.md](contracts/commands.md) §Color semantics. The `Last outcome` row renders as `<outcome> (trigger: <TRIGGER>, attempts: <N>)` when `lastOutcome != null`, else `none yet`. Format timestamps as `yyyy-MM-dd HH:mm:ss (<zoneId>)` in `config.timezone()`; emit `yes`/`no` booleans only — never the token; version string sourced from `plugin.getPluginMeta().getVersion()` (substituted at build time per [contracts/plugin-yml.md](contracts/plugin-yml.md)).

**Checkpoint**: User Stories 1–4 are independently functional. Operators have a visible health surface without tailing logs.

---

## Phase 7: User Story 5 — Hot-Reload Configuration (Priority: P3)

**Goal**: `/tickstats reload` re-reads `config.yml`, validates locally (no network probe per [research.md](research.md) §R13), updates `PatMasker` BEFORE swapping the config reference, rearms the scheduler from the new cron expression, and preserves the in-flight cycle under the old config (FR-020). On validation failure the previous config stays active with a specific error returned to the invoker. Recovering from inert mode to healthy must also restart the scheduler.

**Independent Test**: Edit `config.yml` to change the cron expression, run `/tickstats reload` — `/tickstats status` immediately shows the new `Next scheduled sync`. Break the file (invalid cron), run `/tickstats reload` — the command returns `Reload failed: …`, previous config remains. Recover from inert mode — the command returns `Configuration reloaded - plugin is now active. Next sync: …`.

### Tests for User Story 5

- [ ] T042 [P] [US5] Author `TickstatsCommandReloadTest` in `src/test/java/com/skycryck/tickstatssync/command/TickstatsCommandReloadTest.java` — using the T013 YAML fixtures: (a) valid reload replies with the success template carrying the new next-fire timestamp, scheduler's `reschedule` is called exactly once, `PatMasker.setToken` is called BEFORE the config reference is swapped (capture call order on a spy); (b) invalid reload replies with `Reload failed: <specific error>`, scheduler is NOT rescheduled, `ConfigService`'s `AtomicReference` still holds the previous config; (c) recovering from inert mode replies with the "plugin is now active" template and starts the scheduler; (d) during a reload, an in-flight sync completes under the old config — simulate by pre-acquiring `SyncLock` and verifying the rescheduled scheduler only arms the next firing against the new cron, not the currently-running cycle.

### Implementation for User Story 5

- [ ] T043 [US5] Fill the `onReload` method in `src/main/java/com/skycryck/tickstatssync/command/TickstatsCommand.java` — call `plugin.reloadConfig()`, then `ConfigService.load()`; on validation failure reply with `§c[TickstatsSync] Reload failed: <reason>. Previous configuration still active.` and return; on success execute the exact ordering from [research.md](research.md) §R13: (1) `patMasker.setToken(newConfig.token())`, (2) swap `ConfigService`'s `AtomicReference<TickstatsSyncConfig>`, (3) if transitioning from inert mode call `cronScheduler.start()`, else call `cronScheduler.reschedule(newConfig)`; reply with the inert-recovery variant when applicable, else the standard success template carrying the new next-fire timestamp.

**Checkpoint**: All five user stories are independently functional. The plugin can be reconfigured live with no server restart and no PAT-leakage window during the swap.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Close the remaining constitutional and observability invariants once all stories are in place.

- [ ] T044 [P] Add `invalid-malicious-yaml.yml` fixture under `src/test/resources/config/` and extend `ConfigServiceTest` in `src/test/java/com/skycryck/tickstatssync/config/ConfigServiceTest.java` — fixture contains `!!java.net.URL` and similar non-safe tags; assert SnakeYAML 2.x (via Paper) refuses them per [plan.md](plan.md) §Assumptions.
- [ ] T045 [P] Add a PAT-scanning end-to-end assertion — new test `src/test/java/com/skycryck/tickstatssync/PatLeakageTest.java` that drives a full success + full failure sync cycle with a well-known token value, captures every log record published to the plugin logger, every `sendMessage` call on the fake sender, every commit message, and every `/tickstats status` output, and asserts the literal token string never appears in any captured artifact (SC-007, FR-023).
- [ ] T046 Run the quickstart smoke test from [quickstart.md](quickstart.md) against a live Paper 26.1.2 dev server (build #19 or later, JDK 25 runtime) and a disposable GitHub repo; record the verification steps and any deviations inline in [quickstart.md](quickstart.md) §7 "Verify observability". Update troubleshooting rows in [quickstart.md](quickstart.md) §10 if any surprises surface. No dual 1.21.x / 26.x matrix — a single 26.1.2 test run is the sole target ([research.md](research.md) §R16).
- [ ] T046a **Tickstats producer-contract verification (SC-002 gate, mandatory before release)** — after T046's smoke cycle lands `stats/<server>/data/` and `stats/<server>/snapshots/YYYY-MM-DD/` in the test repo, run upstream `generate.py` against that data per [quickstart.md](quickstart.md) §8.1: clone `https://github.com/Skycryck/tickstats` into a scratch dir, set up a Python venv, `pip install -r requirements.txt`, clone the stats repo the plugin just pushed to, then `python generate.py --stats-dir <path-to-cloned-stats>/stats`. Pass criteria: exit code 0, no tracebacks or ERROR log lines, generated dashboard HTML renders without broken layout or JS console errors, ≥1 data row per UUID present in `stats/<server>/data/`. Any failure is a Principle I regression — stop the release and file it against [contracts/repo-layout.md](contracts/repo-layout.md) before shipping.
- [ ] T047 Run `./gradlew clean shadowJar` and verify the produced JAR — `jar tf build/libs/TickstatsSync-*.jar` shows every JGit, cron-utils, Apache HttpClient, slf4j, and Bouncy Castle class under `com/skycryck/tickstatssync/shaded/…`; no class under the original `org.eclipse.jgit`, `com.cronutils`, `org.apache.http`, `org.apache.hc`, `org.slf4j`, or `org.bouncycastle` namespace survives; JSch and SSH transports are absent.
- [ ] T048 [P] Final English-only sweep — scan `src/`, `README.md`, `config.yml`, `plugin.yml`, commit messages, and every log/chat string against Principle XI in [.specify/memory/constitution.md](.specify/memory/constitution.md); fix any non-English drift surfaced by review.

**Checkpoint**: Every success criterion (SC-001 through SC-010) has a passing verification artifact; the shaded JAR is release-ready; the constitution's inviolable principles (I and XI) are audited clean.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — starts immediately.
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS every user story. Within Phase 2, `ConfigService` (T012) requires the `TickstatsSyncConfig` record (T011); the four stub tasks **T022a–T022d** must land before T023 and T024 (they provide the compile-time types — `StatsReader`, `StatsSnapshot`, `GitService`, `SyncOrchestrator`, `CronScheduler`); `TickstatsSyncPlugin` (T024) depends on every earlier Phase-2 task; the remaining Phase-2 tasks are independent.
- **User Story 1 (Phase 3)**: Depends on Foundational. Delivers MVP.
- **User Story 2 (Phase 4)**: Depends on Foundational + `SyncOrchestrator` from US1 (T033). Snapshot logic is a localized edit to the orchestrator plus its test.
- **User Story 3 (Phase 5)**: Depends on Foundational + `SyncOrchestrator` + `SyncLock` (already in Foundational). Edits `TickstatsCommand.onSync`.
- **User Story 4 (Phase 6)**: Depends on Foundational only (reads `SyncMetrics` + `ConfigService`). Edits `TickstatsCommand.onStatus`.
- **User Story 5 (Phase 7)**: Depends on Foundational + `CronScheduler` from US1 (T034). Edits `TickstatsCommand.onReload`.
- **Polish (Phase 8)**: Depends on all user stories being complete (T047 is the release-JAR gate).

### Within Each User Story

Tests (if authored first) should fail before implementation lands; models → services → endpoints/commands; story must be fully green before moving to the next priority.

### Parallel Opportunities

- **Phase 1**: T005 (README) and T006 (.gitignore) run in parallel after T001–T004 land the build.
- **Phase 2**: T007–T022, the four stubs T022a–T022d, and T023 are all `[P]` — 20 independent files across `util/`, `config/`, `sync/`, `git/`, `stats/`, `scheduler/`, `command/`, and the two resource files. T024 (plugin main class) is the single serial task that closes the phase.
- **Phase 3 tests** (T025, T026, T028, T029) run in parallel; T027 (`GitServiceTest`) serializes against its own fixture. Implementation tasks T030, T031 run in parallel; T032 (`GitService`) unlocks T033 (`SyncOrchestrator`); T034 can run in parallel with T033 once its contracts are pinned; T035 serializes after T033 and T034.
- **Phases 4–7 tests** (T036, T038, T040, T042) all run in parallel — they exercise different methods on different fixtures.
- **Polish**: T044, T045, T048 run in parallel; T046, T046a, and T047 serialize on a live build (T046 produces the data that T046a consumes, then T047 produces the final shaded JAR).

---

## Parallel Example: Foundational Phase (Phase 2)

```bash
# After T011 lands TickstatsSyncConfig, the rest of Phase 2 can fan out:
Task: "T007 Implement PatMasker in src/main/java/com/skycryck/tickstatssync/util/PatMasker.java"
Task: "T008 Implement LogRedactionFilter in src/main/java/com/skycryck/tickstatssync/util/LogRedactionFilter.java"
Task: "T009 Author PatMaskerTest in src/test/java/com/skycryck/tickstatssync/util/PatMaskerTest.java"
Task: "T010 Author LogRedactionFilterTest in src/test/java/com/skycryck/tickstatssync/util/LogRedactionFilterTest.java"
Task: "T013 Create config YAML fixtures under src/test/resources/config/"
Task: "T015 Add default config.yml resource at src/main/resources/config.yml"
Task: "T016 Add plugin.yml at src/main/resources/plugin.yml"
Task: "T017 Define SyncOutcome enum in src/main/java/com/skycryck/tickstatssync/sync/SyncOutcome.java"
Task: "T018 Define FailureCategory enum in src/main/java/com/skycryck/tickstatssync/sync/FailureCategory.java"
Task: "T019 Define GitOperationException in src/main/java/com/skycryck/tickstatssync/git/GitOperationException.java"
Task: "T020 Implement SyncMetrics in src/main/java/com/skycryck/tickstatssync/sync/SyncMetrics.java"
Task: "T021 Implement SyncLock in src/main/java/com/skycryck/tickstatssync/sync/SyncLock.java"
Task: "T022 Author SyncLockTest in src/test/java/com/skycryck/tickstatssync/sync/SyncLockTest.java"
Task: "T022a Stub StatsReader + StatsSnapshot in src/main/java/com/skycryck/tickstatssync/stats/"
Task: "T022b Stub GitService in src/main/java/com/skycryck/tickstatssync/git/GitService.java"
Task: "T022c Stub SyncOrchestrator + Trigger enum in src/main/java/com/skycryck/tickstatssync/sync/SyncOrchestrator.java"
Task: "T022d Stub CronScheduler in src/main/java/com/skycryck/tickstatssync/scheduler/CronScheduler.java"
Task: "T023 Implement TickstatsCommand skeleton in src/main/java/com/skycryck/tickstatssync/command/TickstatsCommand.java"
```

T012 (ConfigService) depends on T011, and T014 (ConfigServiceTest) depends on T012 + T013. T022a–T022d must land before T023 (type dependencies). T024 (TickstatsSyncPlugin) serializes last in the phase.

---

## Parallel Example: User Story 1

```bash
# Tests first (all different files):
Task: "T025 Create stats fixture tree src/test/resources/stats/sample-stats/"
Task: "T026 Author StatsReaderTest in src/test/java/com/skycryck/tickstatssync/stats/StatsReaderTest.java"
Task: "T028 Author SyncOrchestratorTest in src/test/java/com/skycryck/tickstatssync/sync/SyncOrchestratorTest.java"
Task: "T029 Author CronSchedulerTest in src/test/java/com/skycryck/tickstatssync/scheduler/CronSchedulerTest.java"
# T027 (GitServiceTest) runs separately — it owns its own bare-repo harness.

# Independent implementations (different files) — each replaces a Foundational stub body, signatures unchanged:
Task: "T030 Verify / finish StatsSnapshot defensive-copy invariant (file authored in T022a)"
Task: "T031 Replace StatsReader.read stub body (file authored in T022a)"
# T032 (GitService) replaces the T022b stubs; must land before T033 (SyncOrchestrator); T034 (CronScheduler) can run in parallel with T033 because both only depend on the types declared in the T022b-d stubs.
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup.
2. Complete Phase 2: Foundational (critical — blocks every story).
3. Complete Phase 3: User Story 1.
4. **STOP and VALIDATE**: run the Phase 3 Independent Test against a live Paper 26.1.2 dev server (JDK 25) and a disposable GitHub repo. Confirm SC-001, SC-003, SC-004, and SC-010 at this stage. Then execute **T046a** to verify SC-002 (producer contract vs. upstream `generate.py`) — this is a mandatory Phase 3 closure gate. MVP is not shippable until T046a passes.
5. Ship MVP.

### Incremental Delivery

1. MVP ships US1.
2. US2 (snapshots) → verify SC-005 → ship.
3. US3 (manual sync) → verify SC-009 and the concurrent-invocation rejection contract → ship.
4. US4 (status) → verify SC-007 against the status output → ship.
5. US5 (reload) → verify SC-006 and the no-leakage-during-swap invariant → ship.
6. Polish phase → run T046 smoke + **T046a `generate.py` verification gate (SC-002)** + T047 final shaded JAR — release-ready output.

### Parallel Team Strategy

After Phase 2 completes:
- Developer A takes US1 (the biggest slice of implementation work).
- Developer B takes US4 (status) and US2 (snapshots — small orchestrator edit, independent of US3/US4/US5 once US1 lands).
- Developer C takes US3 (manual sync) and US5 (reload) — both edit `TickstatsCommand`, so serializing them on one owner avoids merge churn.

---

## Notes

- `[P]` tasks touch different files and have no dependency on any other incomplete task.
- `[Story]` maps the task to its user story for traceability.
- Every user story is independently testable — checkpoints are concrete pass/fail signals.
- `TickstatsCommand` is intentionally authored as a skeleton in Phase 2 (T023) and filled one method at a time by US3/US4/US5. Serializing method edits on a single owner (per Parallel Team Strategy) is the recommended way to avoid merge conflicts inside this single-file class.
- Constitutional invariants (especially Principle IV bootstrap order and Principle XI English-only artifacts) are reiterated inside the relevant tasks; PR review MUST reject drift.
- Every task description carries an exact file path. Tasks without a concrete path have been rewritten or rejected.
