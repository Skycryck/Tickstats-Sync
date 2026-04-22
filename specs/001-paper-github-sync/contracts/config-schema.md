# Contract: `config.yml` schema

**Feature**: 001-paper-github-sync
**Type**: Operator-facing configuration contract
**Format**: YAML 1.1 (Paper's bundled SnakeYAML)

The plugin reads its entire runtime configuration from
`plugins/TickstatsSync/config.yml`. On first boot, if the file does not exist, the
plugin writes the template below and refuses to sync until the operator edits it into
a valid state (FR-017).

Every key is treated as present-but-required unless marked **optional** — this lets us
fail fast with a specific error instead of silently falling back to a value the
operator never intended.

## Template (committed as `src/main/resources/config.yml`)

```yaml
# =============================================================================
# TickstatsSync — configuration file
# =============================================================================
# This file contains a GitHub Personal Access Token (PAT). Keep it secret:
#
#   - If your Minecraft server directory is under version control, add this
#     file (or its parent folder) to .gitignore.
#   - Rotate the token periodically and after every suspected leak.
#
# Supported token types:
#
#   1. Fine-grained PAT (recommended)
#      Scope: "Contents: Read and write" on the target repository only.
#      Setup: https://github.com/settings/personal-access-tokens/new
#
#   2. Classic PAT (fallback — use only if fine-grained is unavailable, e.g.
#      on GitHub Enterprise Server builds or organizations without fine-grained
#      PAT support)
#      Scope: `repo`.
#      IMPORTANT: classic PATs with the `repo` scope grant access to EVERY
#      repository the issuing user can see — there is no per-repo scoping.
#      Create a dedicated machine/bot GitHub account, invite it to the target
#      repository as a collaborator with the **Write** role (NOT Admin — Write
#      is sufficient for pushes and commits), then generate the classic PAT
#      from that bot account. Never use a classic PAT minted from your
#      personal user account for production syncs.
#
# For automated deployments (systemd, Docker, Pterodactyl), leave
# github.token empty below and export the PAT through the environment variable
# TICKSTATSSYNC_GITHUB_TOKEN. The plugin reads it automatically.
# =============================================================================

github:
  # Target repository in "owner/name" form. Required.
  repo: "owner/repo-name"

  # Target branch. Default: main.
  branch: "main"

  # PAT. Leave empty to source from the environment variable
  # TICKSTATSSYNC_GITHUB_TOKEN. One of the two MUST be set.
  token: ""

  # Identity used for git commits. Shown in the target repository's history.
  commit-author-name: "TickstatsSync Bot"
  commit-author-email: "tickstatssync@example.com"

server:
  # Short identifier used as the first path segment under stats/ in the target
  # repository: stats/<server.name>/data/... and stats/<server.name>/snapshots/...
  # Allowed characters: A-Z, a-z, 0-9, dot, underscore, hyphen. Max 64 chars.
  name: "my-server"

  # Path to the vanilla stats directory. Relative to the Minecraft server's
  # working directory. Override this if your world uses a non-default folder
  # name (e.g. "custom-world/stats").
  stats-path: "world/stats"

sync:
  # Standard 5-field Unix cron expression. Evaluated against the timezone
  # below. Examples:
  #   "0 */6 * * *"   every 6 hours, on the hour
  #   "0 8,14,22 * * *"  at 08:00, 14:00, and 22:00
  #   "*/15 * * * *"  every 15 minutes
  cron: "0 */6 * * *"

  # IANA timezone for cron evaluation and snapshot date naming.
  timezone: "Europe/Paris"

  # Trigger one sync at plugin startup. Default: false — avoids a surprise
  # push if you start the server with a half-finished config.
  sync-on-startup: false

  # Write one snapshot per calendar day into stats/<server-name>/snapshots/
  # YYYY-MM-DD/. First sync of the day wins; subsequent syncs the same day
  # leave the directory untouched.
  snapshots-enabled: true

retry:
  # Number of attempts per sync cycle before giving up until the next
  # scheduled tick. Range: 1..10.
  max-attempts: 3

  # Initial backoff between attempts, in seconds. Doubles each attempt.
  # Range: 1..300.
  initial-backoff-seconds: 10
```

## Formal key table

| Key | Type | Required | Default | Validation |
|-----|------|----------|---------|------------|
| `github.repo` | string | **yes** | — | Matches `^[A-Za-z0-9][A-Za-z0-9._-]*\/[A-Za-z0-9][A-Za-z0-9._-]*$` |
| `github.branch` | string | no | `main` | Non-empty; no whitespace or control chars |
| `github.token` | string | conditional | `""` | Must be non-empty after `TICKSTATSSYNC_GITHUB_TOKEN` fallback |
| `github.commit-author-name` | string | **yes** | — | Non-empty |
| `github.commit-author-email` | string | **yes** | — | Matches minimal `.+@.+` |
| `server.name` | string | **yes** | — | `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$` |
| `server.stats-path` | string | no | `world/stats` | Resolved path must exist, be a directory, be readable |
| `sync.cron` | string | **yes** | `0 */6 * * *` | Parses under `CronType.UNIX` |
| `sync.timezone` | string | no | `Europe/Paris` | Valid `ZoneId` |
| `sync.sync-on-startup` | boolean | no | `false` | — |
| `sync.snapshots-enabled` | boolean | no | `true` | — |
| `retry.max-attempts` | int | no | `3` | In `[1, 10]` |
| `retry.initial-backoff-seconds` | int | no | `10` | In `[1, 300]` |

## Error reporting on load

Every validation failure becomes an English message like:

- `config error: github.repo is missing (expected "owner/repo")`
- `config error: sync.cron is not a valid Unix cron expression: <reason>`
- `config error: sync.timezone "Europe/Nowhere" is not a recognized IANA zone`
- `config error: no GitHub token supplied — set github.token or export TICKSTATSSYNC_GITHUB_TOKEN`

Messages are emitted once to the server log, never include the token value, and are
also returned to the `/tickstats reload` invoker verbatim.

## Reload semantics

On `/tickstats reload`, this file is re-read from disk. If validation fails, the
previously-active config stays in place and the reload is reported as failed
(FR-019). If validation succeeds, the reachability probe (R13 in `research.md`) runs;
only on both passing does the swap occur.
