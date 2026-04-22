# Contract: `/tickstats` command surface

**Feature**: 001-paper-github-sync
**Type**: In-game command contract
**Permission**: `tickstats.admin` (default `op`) on every subcommand
**Registration**: Paper Brigadier via `LifecycleEvents.COMMANDS` (see R1 in
`research.md`).

The root command is `/tickstats`, with three subcommands: `sync`, `status`, `reload`.
Non-admin senders see the same response as for a non-existent command — Brigadier
naturally omits the command from tab-completion and its executor rejects
unauthorized senders silently (FR-025).

---

## `/tickstats sync`

**Purpose**: Trigger a sync cycle immediately, off the main thread.

**Permission**: `tickstats.admin`.

**Arguments**: none.

**Preconditions**: no sync is currently in progress (FR-003a). The server-log
counterpart on a scheduled tick that races with an in-flight cycle is
`Scheduled sync skipped: previous sync still running (held for <N>s)` at WARNING
level — the cron schedule is unchanged and resumes normally on the next occurrence.

**Responses** (English, in-game chat text):

| Scenario | Response template |
|----------|-------------------|
| Accepted, queued on async thread | `§a[TickstatsSync] Sync started.` |
| Rejected: sync already running | `§e[TickstatsSync] A sync is already in progress (started <N>s ago). Try again in a moment.` |
| Rejected: config invalid (inert mode) | `§c[TickstatsSync] Configuration is invalid (<reason>). Edit config.yml and run /tickstats reload.` |
| Post-sync success with commit (one attempt) | `§a[TickstatsSync] Sync complete: pushed <N> file(s), commit <sha7>, <ms> ms.` |
| Post-sync success with commit (multiple attempts) | `§a[TickstatsSync] Sync complete after <K> attempts: pushed <N> file(s), commit <sha7>, <ms> ms.` |
| Post-sync success (no changes) | `§a[TickstatsSync] Sync complete: no changes since last sync.` |
| Post-sync failure | `§c[TickstatsSync] Sync failed (<category>) after <K> attempts. See server log for details.` |
| Non-admin sender | Default Brigadier "Unknown or incomplete command" response (Paper default; not emitted by us). |

The post-sync messages use the three-variant `SyncOutcome` taxonomy
(`SUCCESS_WITH_COMMIT`, `SUCCESS_NO_CHANGES`, `FAILURE`) plus the separate
`attempts` count — retried-then-succeeded is still a success with `attempts > 1`.

The post-sync messages are delivered to the original command sender when they are
still online. If the sender has logged out by the time the sync finishes, the
outcome is dropped (no broadcast, no stored notification) — the log line remains
the durable record.

---

## `/tickstats status`

**Purpose**: Dump a compact health summary to the sender (FR-022).

**Permission**: `tickstats.admin`.

**Arguments**: none.

**Response template (healthy mode)**:

```
§6TickstatsSync §7v<version>
§7Last successful sync: §<color><timestamp with tz> | never
§7Next scheduled sync:  §<color><timestamp with tz> | unscheduled
§7Detected stats files: §<color><count>
§7PAT configured:       §<color>yes | no
§7Repo reachability:    §<color>ok | failed | unknown <optional hint>
§7Last outcome:         §<color><outcome> (trigger: <TRIGGER>, attempts: <N>) | none yet
```

The `trigger` field on the last-outcome row is the
`SyncOrchestrator.Trigger` enum value (`SCHEDULED`, `MANUAL`, or `STARTUP`)
that kicked the most recent cycle, sourced from `SyncMetrics.lastTrigger` and
mirroring the `trigger=<value>` field in the structured log line (R15).
Before the first sync has run, the whole row renders as
`§7Last outcome:         §enone yet` with no trigger or attempts parenthetical.

**Response template (inert mode — config is invalid)**:

```
§6TickstatsSync §7v<version>
§c⚠ config invalid: <reason>
§7Edit plugins/TickstatsSync/config.yml and run §b/tickstats reload§7.
§7Last successful sync: §<color><timestamp with tz> | never
§7Detected stats files: §<color><count>
```

In inert mode the "Next scheduled sync" row is omitted because no cron task is
armed. `Last successful sync` still reports the previous healthy state (if any) so
the operator knows how stale the data is.

**Color semantics**:
- `§a` (green) for healthy values (successful sync, next scheduled in future, PAT
  present, repo ok).
- `§e` (yellow) for missing-but-expected values (never synced yet, unknown
  reachability).
- `§c` (red) for broken values (no PAT, repo failed, last outcome was abandonment).

**Examples**:

Healthy:
```
§6TickstatsSync §7v1.0.0
§7Last successful sync: §a2026-04-22 14:00:03 (Europe/Paris)
§7Next scheduled sync:  §a2026-04-22 20:00:00 (Europe/Paris)
§7Detected stats files: §a42
§7PAT configured:       §ayes
§7Repo reachability:    §aok (checked at last sync)
§7Last outcome:         §aSUCCESS_WITH_COMMIT (trigger: SCHEDULED, attempts: 1)
```

Never-synced:
```
§6TickstatsSync §7v1.0.0
§7Last successful sync: §enever
§7Next scheduled sync:  §a2026-04-22 20:00:00 (Europe/Paris)
§7Detected stats files: §a42
§7PAT configured:       §ayes
§7Repo reachability:    §eunknown
§7Last outcome:         §enone yet
```

Broken PAT:
```
§6TickstatsSync §7v1.0.0
§7Last successful sync: §a2026-04-21 08:00:04 (Europe/Paris)
§7Next scheduled sync:  §a2026-04-22 20:00:00 (Europe/Paris)
§7Detected stats files: §a42
§7PAT configured:       §ayes
§7Repo reachability:    §cfailed (AUTH) - check server log
§7Last outcome:         §cFAILURE (trigger: SCHEDULED, attempts: 1)
```

Inert mode (invalid config after a bad `/tickstats reload`):
```
§6TickstatsSync §7v1.0.0
§c⚠ config invalid: sync.cron is not a valid Unix cron expression
§7Edit plugins/TickstatsSync/config.yml and run §b/tickstats reload§7.
§7Last successful sync: §a2026-04-21 14:00:03 (Europe/Paris)
§7Detected stats files: §a42
```

**Invariants**:
- The PAT value itself never appears in the output (FR-023, SC-007). Only the
  boolean "configured" indicator.
- All timestamps shown in the configured timezone (not UTC, not host local).

---

## `/tickstats reload`

**Purpose**: Re-read and re-validate `config.yml` without restarting the server
(FR-018).

**Permission**: `tickstats.admin`.

**Arguments**: none.

**Responses**:

| Scenario | Response template |
|----------|-------------------|
| Config parsed and validated (local shape only) | `§a[TickstatsSync] Configuration reloaded. Next sync: <timestamp with tz>.` |
| Config parsed but validation failed | `§c[TickstatsSync] Reload failed: <specific error>. Previous configuration still active.` |
| Config was previously invalid and the reload fixed it (recovering from inert mode) | `§a[TickstatsSync] Configuration reloaded - plugin is now active. Next sync: <timestamp with tz>.` |
| Non-admin sender | Default Brigadier "Unknown or incomplete command" response. |

**Invariants**:
- The previously-active configuration stays in force on any validation failure
  (FR-019).
- An in-flight sync at reload time completes under the old config; the new config
  applies to the next scheduled tick (FR-020).
- The PAT is not echoed in any response — only the binary outcome.
- **Reload ordering**: on successful validation, `PatMasker.setToken(newToken)` is
  called BEFORE the active `TickstatsSyncConfig` reference is swapped, so no log
  line emitted by background threads during the swap can carry the new token
  before redaction is armed for it. See research R13.
- No network probe: `/tickstats reload` validates the local configuration shape
  only. Auth and reachability are verified implicitly by the next scheduled sync
  (or an immediate `/tickstats sync`), which surfaces any failure through the
  normal observability path (structured log line + `/tickstats status`).

---

## Non-admin behavior (all subcommands)

Brigadier registers each subcommand with a `requires(source -> source.getSender().hasPermission("tickstats.admin"))` gate.
Non-admin senders never see the subcommand in tab-completion and invoking it
manually yields the server's default "Unknown or incomplete command: /tickstats"
response — identical to typing a non-existent command (FR-025).
