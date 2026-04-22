# Contract: produced target-repository layout

**Feature**: 001-paper-github-sync
**Type**: Upstream Tickstats producer contract
**Constitution reference**: Principle I (inviolable)

The plugin writes exclusively into the following paths inside the target GitHub
repository, always anchored at the repository root under `stats/<server.name>/`.
The in-repo target path is fixed by Tickstats convention and is NOT configurable
— see the footnote in [plan.md](../plan.md) and [spec.md](../spec.md) FR-006 /
FR-016. No other paths are ever created, modified, or deleted by the plugin.

## Path layout

```text
<repo-root>/
└── stats/
    └── <server.name>/
        ├── data/
        │   ├── <uuid>.json      ← byte-for-byte copy of world/stats/<uuid>.json
        │   ├── <uuid>.json
        │   └── ...
        └── snapshots/
            ├── 2026-04-22/      ← first-sync-of-day wins; one dir per calendar day
            │   ├── <uuid>.json
            │   ├── <uuid>.json
            │   └── ...
            ├── 2026-04-23/
            └── ...
```

## Guarantees

1. **Byte-for-byte copy**: each `<uuid>.json` in `data/` and any snapshot directory
   contains exactly the bytes the plugin read from
   `<server.stats-path>/<uuid>.json`. No pretty-printing, no re-serialization, no
   encoding change. (FR-007)

2. **Data directory always reflects the latest sync**: `data/` is truncated (any
   stale UUIDs no longer present in the source are removed) and rewritten every
   cycle in which content changes. A commit either updates every changed file in one
   atomic commit or none.

3. **Snapshot directory is immutable once written**: once
   `snapshots/YYYY-MM-DD/` exists on disk, no subsequent sync in the same calendar
   day modifies or overwrites it. (FR-009)

4. **UTC-independent date labels**: `YYYY-MM-DD` is computed in the configured
   `sync.timezone` (default `Europe/Paris`), not UTC and not the host's local
   timezone. (FR-008)

5. **No other files produced**: the plugin does NOT create a `README.md`,
   `.github/workflows/`, `.gitignore`, or any other meta file in the target
   repository. The target repository's existing content at any other path is left
   untouched forever.

6. **Deletions are scoped**: file removals only happen inside
   `stats/<server.name>/data/`. Snapshots and paths outside the plugin-owned subtree
   are never deleted by the plugin.

## Commit contract

- Commit author: `<github.commit-author-name> <<github.commit-author-email>>`.
- Commit message: exactly `Update stats for <server.name> - YYYY-MM-DD HH:mm`, where
  the timestamp is in the configured timezone. An ASCII hyphen-minus (`-`, U+002D)
  is used as the separator — plain ASCII renders correctly in every terminal,
  commit viewer, and locale regardless of UTF-8 support. The constitution's em-dash
  example (Principle X) is illustrative, not mandatory.
- One commit per sync cycle that produced changes. Zero commits per cycle that
  produced no changes (FR-012).
- Parent: always the current remote tip of the target branch at the time of push
  (because each cycle begins with fetch + hard-reset). (R4 in `research.md`)

## Branch contract

- Pushes go to `<github.branch>` (default `main`).
- Never force-push (FR-015). If a push would require force, the cycle aborts with
  `FailureCategory.CONFLICT` and the next cycle tries again from a clean
  fetch+reset.

## Relation to `generate.py`

This layout IS the contract consumed by upstream Tickstats'
[`generate.py`](https://github.com/Skycryck/tickstats). Any change to `generate.py`'s
expectations requires a constitution amendment (Principle I) BEFORE the plugin's
output changes.
