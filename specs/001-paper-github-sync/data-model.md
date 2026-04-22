# Phase 1 Data Model — TickstatsSync

**Feature**: 001-paper-github-sync
**Date**: 2026-04-22
**Scope**: Java types that materialize the entities identified in `spec.md` §Key
Entities. Everything is immutable where practical; mutable state is quarantined in
`SyncMetrics` behind atomic accessors.

---

## Configuration record (package `config/`)

A single flat `record` holds the entire validated configuration. Built only by
`ConfigService.load()` after full validation; no constructor accepts raw YAML.

### `TickstatsSyncConfig`

```java
public record TickstatsSyncConfig(
    // GitHub section
    String ownerAndRepo,         // "owner/repo-name"
    String branch,               // default "main"
    String token,                // resolved from config.yml or env var; validated non-empty
    String commitAuthorName,
    String commitAuthorEmail,

    // Server section
    String serverName,
    Path statsPath,              // absolute, resolved against the server directory

    // Schedule section
    String cronExpression,       // raw, already validated by cron-utils
    ZoneId timezone,
    boolean syncOnStartup,
    boolean snapshotsEnabled,

    // Retry section
    int maxAttempts,             // default 3
    Duration initialBackoff      // default Duration.ofSeconds(10)
) {}
```

Only one record, no nested sub-records — the full config is passed wholesale to every
service that needs it, and the grouping is preserved by comment bands rather than
extra types. Rebuilt on each `/tickstats reload`; never mutated in place.

**Validation rules** (all enforced at `ConfigService.load()` time):

| Field | Rule |
|-------|------|
| `ownerAndRepo` | Matches `^[A-Za-z0-9][A-Za-z0-9._-]*\/[A-Za-z0-9][A-Za-z0-9._-]*$` |
| `branch` | Non-empty; no whitespace or control characters; valid git ref name |
| `token` | Non-empty after env-var fallback (`TICKSTATSSYNC_GITHUB_TOKEN`) |
| `commitAuthorName` | Non-empty |
| `commitAuthorEmail` | Matches minimal `.+@.+` shape (no RFC validation) |
| `serverName` | Matches `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$` — path-segment-safe, length-bounded, no leading `.` |
| `statsPath` | Exists, is a directory, is readable at load time |
| `cronExpression` | Parses under `CronType.UNIX` via cron-utils |
| `timezone` | Resolvable `java.time.ZoneId`; default `Europe/Paris` |
| `syncOnStartup` | Default `false` |
| `snapshotsEnabled` | Default `true` |
| `maxAttempts` | In `[1, 10]`; default `3` |
| `initialBackoff` | In `[1 second, 5 minutes]`; default `10 seconds`; doubles each attempt |

**Lifecycle**: built once per `ConfigService.load()`. A `toString()` override is
written by hand (not auto-generated) so the token is redacted whenever the record is
logged.

---

## Sync domain types (package `sync/`)

### Cycle state (local variables, no class)

`SyncOrchestrator.runOnce(Trigger trigger)` tracks per-cycle state in **local
variables**, not a dedicated class. The variables are approximately:

```java
Instant startedAt = clock.instant();
ZonedDateTime syncDate = ZonedDateTime.now(config.timezone());  // fixed at start
StatsSnapshot snapshot = null;
boolean snapshotWritten = false;
ObjectId commitSha = null;
int attempts = 0;
SyncOutcome outcome;
FailureCategory failureCategory = null;
// `trigger` is the method parameter (see Trigger enum below); passed through to
// the R15 log line (`trigger=<value>`) and to SyncMetrics.lastTrigger on every
// terminal state so /tickstats status can render "Last sync trigger: MANUAL".
```

No `SyncCycle` class is created — the state lives only for the duration of the
method and never leaks to another thread.

### `Trigger` (enum nested on `SyncOrchestrator`)

```java
public enum Trigger {
    SCHEDULED,   // cron-driven firing from CronScheduler
    MANUAL,      // operator invocation via /tickstats sync
    STARTUP      // one-shot firing at onEnable when syncOnStartup=true
}
```

Declared as a nested enum on `SyncOrchestrator` so call-sites read
`SyncOrchestrator.Trigger.MANUAL`. Consumed by: the R15 log line
(`trigger=<value>` field, always present), `SyncMetrics.lastTrigger` (atomic,
written at terminal state), and `/tickstats status` (renders as
`Last sync trigger: <value>` on the outcome line — see
[contracts/commands.md](contracts/commands.md)).

**State transitions** inside `runOnce(Trigger trigger)`:

```
  STARTED
    │
    ├─ readStats()       ──▶ READ_OK      or ──▶ FAILURE(IO)
    ├─ fetchAndReset()   ──▶ FETCH_OK     or ──▶ FAILURE(AUTH | NETWORK | CONFLICT | IO)
    ├─ writeFiles()      ──▶ STAGED       or ──▶ FAILURE(IO)
    ├─ hasChanges()? ────┬─ false         ──▶ SUCCESS_NO_CHANGES (terminal)
    │                    └─ true
    ├─ commitAndPush()   ──▶ PUSHED       or ──▶ FAILURE(AUTH | NETWORK | CONFLICT)
    └─ terminal: SUCCESS_WITH_COMMIT
                or SUCCESS_NO_CHANGES
                or FAILURE   (with attempts counter + category)
```

### `SyncOutcome` (enum)

```java
public enum SyncOutcome {
    SUCCESS_WITH_COMMIT,
    SUCCESS_NO_CHANGES,
    FAILURE          // final attempt failed after exhausting retries
}
```

Whether a successful outcome took retries is captured separately as an
`int attempts` field on the cycle's metrics record — NOT as a distinct outcome. This
keeps the taxonomy tight: either we ended with a commit, nothing to commit, or we
ultimately failed. Retried-then-succeeded is still `SUCCESS_WITH_COMMIT` with
`attempts > 1`.

### `FailureCategory` (enum)

```java
public enum FailureCategory {
    AUTH,        // 401/403 from GitHub
    NETWORK,     // DNS, TCP, TLS, timeout
    CONFLICT,    // push rejected non-fast-forward (rare after fetch+reset)
    IO,          // filesystem error
    UNKNOWN      // catch-all — including plugin bugs
}
```

No `CONFIG` value: configuration is validated at load/reload time and never
becomes invalid mid-cycle. A runtime assertion failure on configuration shape would
be a plugin bug and falls under `UNKNOWN`.

### `SyncMetrics` (mutable state, atomic)

```java
public final class SyncMetrics {
    private final AtomicReference<Instant> lastSuccessAt = new AtomicReference<>();
    private final AtomicReference<Instant> nextScheduledAt = new AtomicReference<>();
    private final AtomicInteger detectedStatsFiles = new AtomicInteger(0);
    private final AtomicReference<Reachability> lastReachability = new AtomicReference<>(Reachability.UNKNOWN);
    private final AtomicReference<SyncOutcome> lastOutcome = new AtomicReference<>();
    private final AtomicReference<FailureCategory> lastFailureCategory = new AtomicReference<>();
    private final AtomicInteger lastAttempts = new AtomicInteger(0);
    private final AtomicReference<SyncOrchestrator.Trigger> lastTrigger = new AtomicReference<>();

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
     force a re-run, does NOT queue a catch-up cycle — and **re-arms the next
     scheduled firing** via `runTaskLaterAsynchronously` (the self-rescheduling
     one-shot model requires arming the next occurrence in both branches, else the
     scheduler dies silently). See research R6 for the full decision.

**Release discipline**: `SyncOrchestrator.runOnce(Trigger trigger)` MUST wrap its entire cycle —
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

**PAT safety**: there is no longer a per-callsite masking contract. A
`java.util.logging.Filter` installed on the plugin logger at the first statement of
`onEnable()` intercepts every emitted record — including throwable chains rendered by
`java.util.logging.Formatter` — and runs the rendered string through `PatMasker`
before publication (see research R8). Exception messages constructed anywhere in the
codebase are therefore safe without author discipline.

---

## Relationships

```
TickstatsSyncPlugin
 ├── holds → PatMasker (singleton, volatile token ref — installed FIRST in onEnable)
 ├── holds → LogRedactionFilter (java.util.logging.Filter — installed immediately after PatMasker)
 ├── holds → ConfigService ──► AtomicReference<TickstatsSyncConfig>
 ├── holds → SyncMetrics (singleton, thread-safe)
 ├── holds → SyncLock (singleton)
 ├── holds → StatsReader
 ├── holds → GitService (references TickstatsSyncConfig via ConfigService)
 ├── holds → SyncOrchestrator ──► uses StatsReader, GitService, SyncLock, SyncMetrics
 ├── holds → CronScheduler  ──► references SyncOrchestrator, ConfigService, SyncMetrics
 └── holds → TickstatsCommand (single class, methods onSync/onStatus/onReload)
                                  ──► references ConfigService, SyncLock, SyncMetrics, CronScheduler, SyncOrchestrator
```

No cycles. Configuration flows top-down from `ConfigService`; metrics flow bottom-up
from `SyncOrchestrator`.

---

## Validation summary

| Type | Validates | When |
|------|-----------|------|
| `TickstatsSyncConfig` | All field rules in the Configuration record table above | `ConfigService.load()` |
| `StatsSnapshot` | UUID filename match | `StatsReader.read()` |

Every `[NEEDS CLARIFICATION]` for data shape is resolved. No deferred fields.
