# Phase 1 Data Model — TickstatsSync

**Feature**: 001-paper-github-sync
**Date**: 2026-04-22
**Scope**: Java types that materialize the entities identified in `spec.md` §Key
Entities. Everything is immutable where practical; mutable state is quarantined in
`SyncMetrics` behind atomic accessors.

---

## Configuration records (package `config/`)

All configuration types are Java 21 `record`s — immutable, value-equality, auto-generated
accessors. Instances are produced only by `ConfigService.load()` after full validation;
no constructor accepts raw YAML.

### `TickstatsSyncConfig`

```java
public record TickstatsSyncConfig(
    GitHubConfig github,
    ServerConfig server,
    ScheduleConfig schedule,
    RetryConfig retry
) {}
```

Root of the configuration tree. Passed by reference to every service that needs it.
Rebuilt on each `/tickstats reload`; never mutated in place.

### `GitHubConfig`

```java
public record GitHubConfig(
    String ownerAndRepo,         // "owner/repo-name"
    String branch,               // default "main"
    String token,                // resolved from config.yml or env var; validated non-empty
    String commitAuthorName,
    String commitAuthorEmail
) {}
```

**Validation rules**:
- `ownerAndRepo` must match `^[A-Za-z0-9][A-Za-z0-9._-]*\/[A-Za-z0-9][A-Za-z0-9._-]*$`.
- `branch` must be non-empty and a valid git ref name (no spaces, no control chars).
- `token` must be non-empty after env-var fallback (`TICKSTATSSYNC_GITHUB_TOKEN`).
- `commitAuthorEmail` must match a minimal email shape (`something@something`); we do
  not RFC-validate.

**Lifecycle**: built once per `ConfigService.load()`. Token is redacted in any
`toString()` override (never auto-generated).

### `ServerConfig`

```java
public record ServerConfig(
    String name,
    Path statsPath              // absolute, resolved against the server directory
) {}
```

**Validation rules**:
- `name` must match `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$` — safe as a single path
  segment, length-bounded.
- `statsPath` must exist, be a directory, and be readable at load time. Missing =
  config error. (Not a sync error: the server itself is misconfigured if this
  directory is absent.)

### `ScheduleConfig`

```java
public record ScheduleConfig(
    String cronExpression,      // raw, already validated by cron-utils
    ZoneId timezone,
    boolean syncOnStartup,
    boolean snapshotsEnabled
) {}
```

**Validation rules**:
- `cronExpression` must parse under `CronType.UNIX` via cron-utils.
- `timezone` must be a valid `java.time.ZoneId` (e.g., `Europe/Paris`, `UTC`,
  `America/New_York`). Default: `Europe/Paris`.
- `syncOnStartup` default `false` — avoids a startup-time surprise push on config
  mistakes.
- `snapshotsEnabled` default `true`.

### `RetryConfig`

```java
public record RetryConfig(
    int maxAttempts,            // default 3
    Duration initialBackoff     // default Duration.ofSeconds(10)
) {}
```

**Validation rules**:
- `maxAttempts` in `[1, 10]`.
- `initialBackoff` in `[1 second, 5 minutes]`.
- Exponential doubling per attempt: 10 s → 20 s → 40 s for default settings.

---

## Sync domain types (package `sync/`)

### `SyncCycle` (transient value, not persisted)

Ephemeral: constructed at the top of `SyncOrchestrator.runOnce()`, discarded at the
bottom. Not a `record` because it accumulates information as the cycle progresses.

Fields of interest (all package-private):
- `Instant startedAt`
- `ZonedDateTime syncDate` (for snapshot folder name determination)
- `Map<UUID, byte[]> readStats`
- `boolean snapshotWritten`
- `Optional<ObjectId> commitSha`
- `SyncOutcome outcome`
- `Optional<FailureCategory> failureCategory`
- `Optional<Throwable> failure`

**State transitions**:

```
  STARTED
    │
    ├─ readStats()       ──▶ READ_OK      or ──▶ FAILED(IO)
    ├─ fetchAndReset()   ──▶ FETCH_OK     or ──▶ FAILED(AUTH | NETWORK | CONFLICT | IO)
    ├─ writeFiles()      ──▶ STAGED       or ──▶ FAILED(IO)
    ├─ hasChanges()? ────┬─ false         ──▶ NO_CHANGES (terminal)
    │                    └─ true
    ├─ commitAndPush()   ──▶ PUSHED       or ──▶ FAILED(AUTH | NETWORK | CONFLICT)
    └─ terminal: SUCCESS_WITH_COMMIT
                or SUCCESS_NO_CHANGES
                or TRANSIENT_FAILURE_RETRIED
                or ABANDONED_FAILURE
```

### `SyncOutcome` (enum)

```java
public enum SyncOutcome {
    SUCCESS_WITH_COMMIT,
    SUCCESS_NO_CHANGES,
    TRANSIENT_FAILURE_RETRIED,     // at least one attempt failed; a later attempt succeeded
    ABANDONED_FAILURE              // all retries exhausted; cycle gave up
}
```

### `FailureCategory` (enum)

```java
public enum FailureCategory {
    AUTH,        // 401/403 from GitHub
    NETWORK,     // DNS, TCP, TLS, timeout
    CONFLICT,    // push rejected non-fast-forward (rare after fetch+reset)
    IO,          // filesystem error
    CONFIG,      // runtime invariant broken
    UNKNOWN      // catch-all
}
```

### `SyncMetrics` (mutable state, atomic)

```java
public final class SyncMetrics {
    private final AtomicReference<Instant> lastSuccessAt = new AtomicReference<>();
    private final AtomicReference<Instant> nextScheduledAt = new AtomicReference<>();
    private final AtomicInteger detectedStatsFiles = new AtomicInteger(0);
    private final AtomicReference<Reachability> lastReachability = new AtomicReference<>(Reachability.UNKNOWN);
    private final AtomicReference<SyncOutcome> lastOutcome = new AtomicReference<>();
    private final AtomicReference<FailureCategory> lastFailureCategory = new AtomicReference<>();

    public enum Reachability { OK, FAILED, UNKNOWN }

    // getters for /tickstats status; setters called by SyncOrchestrator + CronScheduler
}
```

**Lifecycle**: instantiated once in `TickstatsSyncPlugin.onEnable`. Persists across
reloads (metrics survive config changes). Reset to zero only on `onDisable`.

**Threading contract**: written by the async sync thread, read by the command thread.
No lock needed — every field is an atomic / volatile.

### `SyncLock`

```java
public final class SyncLock {
    private final AtomicReference<Instant> heldSince = new AtomicReference<>(null);
    private final Clock clock;

    public SyncLock(Clock clock) { this.clock = clock; }

    /** Returns true and records the current Instant on success; false if held. */
    public boolean tryAcquire() {
        return heldSince.compareAndSet(null, clock.instant());
    }

    /** MUST be called in a finally block by every cycle that successfully acquired. */
    public void release() { heldSince.set(null); }

    /** Present when a cycle is in-flight; empty otherwise. Thread-safe. */
    public Optional<Instant> heldSince() { return Optional.ofNullable(heldSince.get()); }
}
```

Used to enforce FR-003a. `Clock` is injected so tests can drive a virtual clock; in
production it is `Clock.systemUTC()`.

**Caller contract — both call sites share the same lock instance**:

1. `/tickstats sync` (manual) calls `lock.tryAcquire()`:
   - On `true`: the command schedules the sync cycle on the async thread and replies
     `§a[TickstatsSync] Sync started.`
   - On `false`: the command reads `lock.heldSince()`, computes
     `Duration.between(heldSince, clock.instant()).toSeconds()`, and replies
     `§e[TickstatsSync] A sync is already in progress (started <N>s ago). Try again in a moment.`

2. `CronScheduler` calls `lock.tryAcquire()` at each scheduled firing:
   - On `true`: the cycle runs normally.
   - On `false`: the scheduler emits a WARNING-level log line
     `Scheduled sync skipped: previous sync still running (held for <N>s)`, does NOT
     force a re-run, does NOT alter the schedule. The next scheduled cron occurrence
     fires normally.

**Release discipline**: `SyncOrchestrator.runOnce()` MUST wrap its entire cycle —
including read, fetch, write, commit, push, retry loop — in a single try/finally.
The `finally` block unconditionally calls `lock.release()`, even if the thread was
interrupted, even if an unchecked exception escaped the cycle. This is a
non-negotiable invariant: a stuck lock disables all further syncs until the plugin
is reloaded.

---

## Stats reader types (package `stats/`)

### `StatsSnapshot`

```java
public record StatsSnapshot(
    Map<UUID, byte[]> files,     // keyed by UUID extracted from filename
    Instant readAt
) {}
```

**Invariants**:
- `files` is an unmodifiable map.
- Each `byte[]` is a defensive copy — mutation by a caller must not affect the
  source file.
- `UUID` keys parsed strictly from filenames matching
  `^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.json$`. Files
  not matching are skipped and logged at DEBUG.

**Production**: `StatsReader.read(Path statsDir)` returns one of these per call.

---

## Git domain types (package `git/`)

### `GitOperationException`

Checked exception with a `FailureCategory` tag:

```java
public final class GitOperationException extends Exception {
    private final FailureCategory category;

    public GitOperationException(FailureCategory category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public FailureCategory category() { return category; }
}
```

Thrown by every `GitService` method on failure. The orchestrator reads
`category()` to populate `SyncMetrics.lastFailureCategory` and the log line.

**PAT safety**: messages are pre-masked before being passed to the constructor, so
the constructed exception can never carry the raw token. Callers assume this
invariant.

---

## Relationships

```
TickstatsSyncPlugin
 ├── holds → ConfigService ──► AtomicReference<TickstatsSyncConfig>
 ├── holds → SyncMetrics (singleton, thread-safe)
 ├── holds → SyncLock (singleton)
 ├── holds → PatMasker (singleton, volatile token ref)
 ├── holds → SafeLogger (wraps java.util.logging)
 ├── holds → StatsReader
 ├── holds → GitService (references TickstatsSyncConfig via ConfigService)
 ├── holds → SyncOrchestrator ──► uses StatsReader, GitService, SyncLock, SyncMetrics, RetryConfig
 ├── holds → CronScheduler  ──► references SyncOrchestrator, ScheduleConfig, SyncMetrics
 └── holds → TickstatsCommand ──► dispatches to {Sync,Status,Reload}Subcommand
                                  which reference ConfigService, SyncLock, SyncMetrics, CronScheduler, SyncOrchestrator
```

No cycles. Configuration flows top-down from `ConfigService`; metrics flow bottom-up
from `SyncOrchestrator`.

---

## Validation summary

| Type | Validates | When |
|------|-----------|------|
| `TickstatsSyncConfig` | Aggregate record (all non-null) | `ConfigService.load()` |
| `GitHubConfig` | owner/repo shape, branch name, token non-empty, email shape | `ConfigService.load()` |
| `ServerConfig` | name charset + length, statsPath exists | `ConfigService.load()` |
| `ScheduleConfig` | cron parses, timezone resolvable | `ConfigService.load()` |
| `RetryConfig` | attempts in `[1,10]`, backoff in `[1s, 5m]` | `ConfigService.load()` |
| `StatsSnapshot` | UUID filename match | `StatsReader.read()` |

Every `[NEEDS CLARIFICATION]` for data shape is resolved. No deferred fields.
