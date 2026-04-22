# Feature Specification: TickstatsSync — Automated Paper-to-GitHub Stats Sync

**Feature Branch**: `001-paper-github-sync`
**Created**: 2026-04-22
**Status**: Draft
**Input**: User description: build the Paper plugin that automates the third Tickstats
usage mode — periodic, autonomous sync of a Minecraft Paper server's stats into a
GitHub repository, so the Tickstats GitHub Pages dashboard updates multiple times per
day without human or third-party intervention.

## Clarifications

### Session 2026-04-22

- Q: Which strategy materializes commits on the remote — local git working copy vs
  REST API? → A: Embedded git client with a local shallow (depth-1) clone of the
  target repository, maintained in a plugin-private directory; each sync cycle stages
  and commits against this working copy and pushes via HTTPS.
- Q: What happens when `/tickstats sync` is invoked while a sync is already running?
  → A: Reject with a clear "sync already in progress" message; no queueing, no
  attachment, no replacement of the in-flight cycle.
- Q: Which GitHub token type should documentation steer operators toward? → A:
  Fine-grained PAT preferred (scoped to the single target repo, `Contents: Read and
  write`); classic PAT with `repo` scope documented as a fallback for operators on
  GitHub plans or Enterprise Server versions without fine-grained support. The
  `config.yml` header comment MUST list both options with their required scopes, and
  the classic-PAT note MUST include an explicit warning recommending a dedicated
  machine/bot account granted Write collaborator access only on the target repo,
  because classic PATs cannot be scoped to a single repository.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Autonomous Scheduled Sync (Priority: P1)

As a Minecraft server operator, I install the plugin, fill in a minimal `config.yml`
(target repository, PAT, server name, cron cadence), and from that moment on my
Tickstats GitHub repository is updated automatically — I never touch anything else.

**Why this priority**: This is the entire reason the plugin exists. Tickstats defines
three usage modes; this plugin is the *autonomous* mode. Frequency and hands-off
operation are its unique value. Without this story, the plugin has no reason to exist.

**Independent Test**: Install the plugin, configure a repository, PAT, and a cron
expression of `*/5 * * * *`. Within 5 minutes of the next tick, observe that the
target repository has a new commit at the configured path containing
`stats/<server-name>/data/<uuid>.json` for every stats file present in `world/stats/`.
Let it run for an hour with no player activity: confirm no empty commit is created.

**Acceptance Scenarios**:

1. **Given** a freshly installed plugin with a valid configuration, **When** the next
   scheduled cron tick fires, **Then** within 5 minutes a commit appears on the
   configured branch containing every current stats file under the contract path
   `stats/<server-name>/data/<uuid>.json`.
2. **Given** a previous successful sync and no subsequent change to any stats file,
   **When** the next cron tick fires, **Then** no commit is created and a log line
   records the outcome as "no changes".
3. **Given** a single stats file changed since the last sync, **When** the next cron
   tick fires, **Then** a single commit containing only the updated file(s) is pushed.
4. **Given** a sync is in progress, **When** players continue playing, **Then** server
   TPS remains within normal variation with no operator-perceptible drop.

---

### User Story 2 - Daily Snapshot Archive (Priority: P2)

As a server operator, I want the plugin to automatically publish one immutable
snapshot of stats per calendar day into my Tickstats repository, so the dashboard can
show time-series data without me remembering to do anything.

**Why this priority**: Tickstats' value proposition includes historical trends. Without
daily snapshots every sync overwrites the previous state and no history exists.
One-per-day first-write-wins semantics keep the repository small and the data point
stable across the day. This is a strong second after the core sync loop.

**Independent Test**: With snapshots enabled and a cron firing multiple times per day,
observe that on the first sync of each new local calendar day a new
`stats/<server-name>/snapshots/YYYY-MM-DD/` directory appears in the repository
containing the current JSON files, and that subsequent syncs the same day do not
touch it.

**Acceptance Scenarios**:

1. **Given** snapshots are enabled and today's snapshot directory does not yet exist,
   **When** the first scheduled sync of today (in the configured timezone) runs,
   **Then** a new `stats/<server-name>/snapshots/YYYY-MM-DD/` directory is created
   containing a copy of every current stats file.
2. **Given** today's snapshot directory already exists, **When** a later sync on the
   same calendar day runs, **Then** the snapshot directory is left untouched — no
   overwrite, no duplicate, no commit entry for snapshots.
3. **Given** snapshots are disabled in configuration, **When** any sync runs, **Then**
   no snapshot directory is created regardless of the calendar day.
4. **Given** the configured timezone is `Europe/Paris`, **When** the calendar day rolls
   over at Paris midnight, **Then** the next sync creates a snapshot directory named
   with the new Paris date (not UTC and not the server host's local date).

---

### User Story 3 - Manual On-Demand Sync (Priority: P3)

As an operator, I want to run `/tickstats sync` in game to force a sync immediately —
useful before a community announcement or to validate the setup after a config change.

**Why this priority**: The core value is delivered by scheduled syncs (US1). Manual
sync is a quality-of-life escape hatch — valuable but not essential for MVP.

**Independent Test**: As an operator (op), run `/tickstats sync`. Confirm an immediate
acknowledgement in chat, observe a sync starts off the main thread, and verify within
seconds that a new commit appears in the target repository (or that the command
reports "no changes" if nothing differs).

**Acceptance Scenarios**:

1. **Given** the plugin is running with valid configuration, **When** an operator runs
   `/tickstats sync`, **Then** a sync starts immediately off the main thread and the
   operator receives an in-game acknowledgement.
2. **Given** stats have changed since the last sync, **When** `/tickstats sync`
   completes, **Then** a new commit is present in the target repository dated within
   seconds of the command.
3. **Given** stats have not changed since the last sync, **When** `/tickstats sync`
   completes, **Then** the command reports "no changes" and no commit is created.
4. **Given** a non-admin player types `/tickstats sync`, **When** they submit the
   command, **Then** they receive the same response as for an unknown command — the
   plugin's commands are not discoverable to unauthorized users.
5. **Given** a sync is already in progress (scheduled or manual), **When** an
   operator runs `/tickstats sync`, **Then** the command immediately replies with a
   "sync already in progress" message, no second cycle is started, and the in-flight
   cycle continues unaffected.

---

### User Story 4 - Plugin Health Observability (Priority: P3)

As an operator, I want `/tickstats status` to tell me whether the plugin is healthy
without tailing log files, so I can quickly verify my setup or reassure myself during
an incident.

**Why this priority**: Headless syncs are invisible until they break. The status
command is the operator's primary at-a-glance health check and a key support tool.

**Independent Test**: Run `/tickstats status` in game as an operator. Confirm the
response shows last successful sync, next scheduled sync, detected stats file count,
PAT-configured indicator, last known repo reachability, and plugin version. Scan the
output: the PAT value itself must not appear.

**Acceptance Scenarios**:

1. **Given** at least one successful sync has occurred, **When** an operator runs
   `/tickstats status`, **Then** the response shows last successful sync timestamp,
   next scheduled sync timestamp, detected stats file count, PAT-configured (yes/no),
   last known repository reachability (ok/failed/unknown), and plugin version.
2. **Given** no sync has ever succeeded, **When** an operator runs `/tickstats status`,
   **Then** the "last successful sync" field is clearly rendered as "never" — no
   misleading value, no error.
3. **Given** a PAT is configured, **When** `/tickstats status` renders its output,
   **Then** only a boolean "configured" indicator is shown; the PAT value itself is
   absent from the response.
4. **Given** a non-admin player runs `/tickstats status`, **When** they submit the
   command, **Then** they receive the same response as for an unknown command.

---

### User Story 5 - Hot-Reload Configuration (Priority: P3)

As an operator, I want `/tickstats reload` to apply changes to `config.yml` without
restarting the server, so I can tweak cadence, change the source stats path, or
rotate the PAT without dropping players.

**Why this priority**: A Minecraft restart is disruptive. Hot-reload is a critical
operator convenience, especially for PAT rotation and cadence tuning.

**Independent Test**: Edit `config.yml` to change the cron expression. Run
`/tickstats reload` in game as operator. Run `/tickstats status` and confirm the
"next scheduled sync" reflects the new expression, with no server restart.

**Acceptance Scenarios**:

1. **Given** an operator has edited `config.yml` with a valid new cron expression,
   **When** they run `/tickstats reload`, **Then** `/tickstats status` immediately
   after shows the new "next scheduled sync" time and no restart occurred.
2. **Given** `config.yml` has become invalid (missing required field, malformed cron
   expression), **When** an operator runs `/tickstats reload`, **Then** the command
   reports a specific error, the previous valid configuration remains active, and no
   scheduled job is lost.
3. **Given** a sync is currently in progress, **When** an operator runs
   `/tickstats reload` successfully, **Then** the in-flight sync completes under the
   old configuration and the new configuration applies to the next scheduled tick.
4. **Given** the PAT has been rotated in `config.yml`, **When** an operator runs
   `/tickstats reload`, **Then** the next sync authenticates with the new PAT.

---

### Edge Cases

- **Empty stats directory (brand new world)**: Plugin no-ops gracefully, logs "no
  stats files found", creates no commit.
- **Target repository exists but is empty (no branches, no commits)**: Plugin
  initializes the target branch with a first commit containing the stats.
- **Target repository is missing the `stats/<server-name>/` tree on first sync**:
  Plugin creates the intermediate directories on first sync.
- **Remote branch advanced between local commit and push (push conflict)**: Plugin
  fetches the remote tip and attempts to integrate its own commits (rebase or merge).
  If still conflicting, the cycle aborts and the next scheduled tick retries from a
  clean state. The plugin NEVER force-pushes.
- **PAT expired, revoked, or lacking scope (HTTP 401/403)**: Plugin logs a redacted
  authentication error, leaves the schedule intact, and retries on the next tick.
- **Network outage**: Plugin retries within the per-cycle retry budget, then aborts
  gracefully; next scheduled occurrence tries again.
- **Server shutdown during a sync**: The in-flight sync is cancelled cleanly; no
  partial on-disk state survives. Next startup resumes normal scheduling.
- **Clock skew, DST transitions in the configured timezone**: Cron fires against
  configured-timezone wall clock. Ambiguous or skipped hours during DST resolve
  deterministically and never double-fire.
- **Stats file momentarily locked by the server while being read**: Plugin retries the
  read briefly or skips that file for the current cycle without crashing the sync.
- **Calendar day boundary crossed during a long sync**: The snapshot date is fixed at
  the start of the sync attempt, preventing files from landing in two different
  snapshot directories.
- **`config.yml` missing on first startup**: Plugin writes a commented default
  template, logs an instruction to the operator, and refuses to attempt any sync
  until a valid configuration exists.
- **Server name with invalid directory characters**: Plugin rejects the configuration
  with an explicit error; reload fails; previous valid config remains active.

## Requirements *(mandatory)*

### Functional Requirements

**Scheduling and triggering**

- **FR-001**: System MUST schedule syncs according to a 5-field cron expression
  provided in `config.yml`.
- **FR-002**: System MUST evaluate the cron expression against a configurable
  timezone, defaulting to `Europe/Paris`.
- **FR-003**: System MUST expose a `/tickstats sync` command (permission
  `tickstats.admin`) that triggers a sync immediately, independent of the schedule.
- **FR-003a**: When `/tickstats sync` is invoked and a sync (scheduled or manual) is
  already in progress, the plugin MUST reject the command with a clear in-game
  message indicating a sync is already running, and MUST NOT queue the request,
  attach to the in-flight cycle, or cancel/replace it. The operator may re-invoke
  the command after the current cycle finishes.
- **FR-004**: System MUST continue honoring the schedule even if a prior sync
  attempt failed, was cancelled, or raised an internal error.

**File production (Tickstats producer contract)**

- **FR-005**: System MUST read player stats files from the source path (default
  `world/stats`) as read-only.
- **FR-006**: System MUST write each stats file verbatim to
  `stats/<server-name>/data/<uuid>.json` at the root of the target repository. The
  target path inside the repository is fixed by Tickstats convention and is NOT
  configurable — `generate.py` expects this exact layout.
- **FR-007**: System MUST NOT transform, reformat, enrich, or otherwise alter the
  JSON contents — bytes in equal bytes out.
- **FR-008**: System MUST, on the first sync of each local calendar day (configured
  timezone), duplicate the current stats into
  `stats/<server-name>/snapshots/YYYY-MM-DD/<uuid>.json`.
- **FR-009**: System MUST NOT overwrite an existing `snapshots/YYYY-MM-DD/`
  directory — first-sync-of-day wins, one snapshot per calendar day maximum.
- **FR-010**: System MUST allow snapshot generation to be toggled off entirely via a
  `config.yml` flag (default: enabled).

**Git operations**

- **FR-010a**: System MUST maintain a plugin-private shallow (depth-1) clone of the
  target repository. The clone MUST be created lazily on first sync and reused across
  cycles. If the working copy is missing, corrupted, or points to a different remote
  than the current configuration, the plugin MUST rebuild it automatically without
  operator intervention. The clone directory MUST live under the plugin's own data
  folder — never inside `world/` or any Minecraft-owned path.
- **FR-011**: System MUST commit changed files to the configured target branch
  (default `main`) using the configured author name and email.
- **FR-012**: System MUST NOT create an empty commit — if no stats file has changed
  since the last synced state, no commit is created.
- **FR-013**: Commit messages MUST follow the pattern
  `Update stats for <server-name> - YYYY-MM-DD HH:mm` (ASCII hyphen-minus `-`
  between server name and timestamp, not an em-dash), in English, dated in the
  configured timezone. ASCII-only keeps the message readable in every terminal and
  log viewer regardless of UTF-8 support.
- **FR-014**: System MUST push each commit to the remote immediately after creating
  it, using HTTPS authentication with the configured PAT.
- **FR-015**: System MUST NEVER force-push. On push conflict the plugin fetches the
  remote tip, attempts to integrate its own commits, and — if still conflicting —
  aborts the cycle cleanly.

**Configuration and hot-reload**

- **FR-016**: `config.yml` MUST expose at minimum the following keys, using
  YAML kebab-case for multi-word names (consistent with Paper/Bukkit
  conventions — e.g., `commit-author-name`, never `commit_author_name` or
  `commitAuthorName`):
  `github.repo` (combined `owner/name` form, e.g. `Skycryck/mc-server-stats`),
  `github.branch` (default `main`),
  `github.token` (may be empty if `TICKSTATSSYNC_GITHUB_TOKEN` env var is set),
  `github.commit-author-name`,
  `github.commit-author-email`,
  `server.name`,
  `server.stats-path` (default `world/stats`),
  `sync.cron`,
  `sync.timezone` (default `Europe/Paris`),
  `sync.sync-on-startup` (default `false`),
  `sync.snapshots-enabled` (default `true`),
  `retry.max-attempts` (default `3`),
  `retry.initial-backoff-seconds` (default `10`).
  The target path inside the GitHub repository is fixed by Tickstats convention
  (`stats/<server.name>/…`) and is NOT exposed as a config key.
- **FR-017**: On first startup with no `config.yml` present, the plugin MUST write a
  commented default template in English and refuse to sync until the file is edited
  into a valid state.
- **FR-017a**: The generated `config.yml` MUST carry a prominent header comment that:
  (1) warns the file contains a secret and recommends adding it to `.gitignore` if
  the Minecraft server directory is itself versioned; (2) documents both supported
  token types — **fine-grained PAT** with `Contents: Read and write` on the single
  target repository (recommended) and **classic PAT** with the `repo` scope
  (fallback); (3) for the classic-PAT case, includes an explicit warning recommending
  the use of a dedicated machine/bot account granted Write collaborator access only
  on the target repository, because classic PATs cannot be scoped to a single
  repository. The same guidance MUST also appear in the project `README.md`.
- **FR-018**: `/tickstats reload` (permission `tickstats.admin`) MUST re-read
  `config.yml` and apply every change atomically without a server restart.
- **FR-019**: If the reloaded configuration is invalid (missing required field,
  malformed cron expression, unreachable repository on smoke check), the reload MUST
  fail, the previous valid configuration MUST remain active, and the failure MUST be
  reported to the command caller.
- **FR-020**: An in-flight sync at the moment of a successful reload MUST complete
  under the old configuration; the new configuration applies from the next scheduled
  tick onward.

**Observability**

- **FR-021**: Every sync attempt (success, failure, or no-op) MUST emit a log line
  containing: ISO-8601 timestamp, outcome (`success`, `no-changes`, `transient-failure`,
  `abandoned`), files-pushed count (on success), commit SHA (on success), duration in
  milliseconds, and failure category (on failure).
- **FR-022**: `/tickstats status` (permission `tickstats.admin`) MUST display:
  last successful sync timestamp, next scheduled sync timestamp, detected stats file
  count, PAT-configured indicator (boolean), last known repository reachability
  (`ok` / `failed` / `unknown`), and plugin version.
- **FR-023**: The PAT value MUST NEVER appear in any log line, in-game message, git
  commit message, `/tickstats status` output, or exception trace.

**Permissions and safety**

- **FR-024**: All plugin commands MUST be gated by the `tickstats.admin` permission
  node, default `op`.
- **FR-025**: Non-admin players invoking any plugin command MUST receive the same
  response as for a non-existent command — no disclosure of command existence.
- **FR-026**: All I/O, network, git, compression, and long-running work MUST run off
  the main server thread; no plugin code path MUST block the main thread beyond
  parsing a command argument.

**Resilience**

- **FR-027**: On transient failure (network error, HTTP 5xx, push conflict, I/O
  hiccup), the plugin MUST retry up to a bounded number of attempts (default: 3)
  with exponential backoff before abandoning the cycle.
- **FR-028**: On non-transient failure (HTTP 401/403, invalid repository, malformed
  configuration), the plugin MUST abandon the cycle immediately after logging a
  redacted error.
- **FR-029**: A failed sync MUST NEVER crash the plugin, disable the scheduler, or
  prevent the next scheduled sync from firing.

**Idempotence**

- **FR-030**: Before creating a commit, the plugin MUST determine whether the staged
  content differs from the remote tip (via content hash or git-diff equivalent) and
  MUST skip commit creation if no difference exists.

**Language and artifacts**

- **FR-031**: All plugin artifacts authored or produced by the project MUST be in
  English, matching constitution Principle XI exactly. This covers: source code;
  inline comments; Javadoc; class, method, field, parameter, variable, and package
  names; log lines and log-message formatters; error messages; exception messages;
  in-game command output; `config.yml` keys and comments; git commit messages; and
  `README.md` (plus any other repository documentation authored as part of this
  project). A pull request containing non-English strings in any of these artifacts
  MUST be rejected at review regardless of the language of the prompt, issue, or
  conversation that produced it.

### Key Entities

- **Server Stats File**: A Minecraft-native JSON file stored under the server's stats
  path and named `<player-uuid>.json`. Owned by the Minecraft server; read-only from
  the plugin's perspective. One per player who has ever logged in.
- **Sync Cycle**: A single execution of the sync logic triggered by either a cron
  tick or the `/tickstats sync` command. Each cycle resolves to exactly one outcome:
  `success-with-commit`, `success-no-changes`, `transient-failure-retried`, or
  `abandoned-failure`.
- **Snapshot**: A dated, immutable copy of all current stats files at
  `stats/<server-name>/snapshots/YYYY-MM-DD/`. At most one per calendar day per
  server, produced by the first successful sync of that day (in the configured
  timezone) that finds no existing directory for today.
- **Plugin Configuration**: The parsed and validated content of `config.yml`. Carries
  target repository coordinates, credentials, schedule, author identity, source
  stats path, and feature toggles. Hot-reloadable.
- **Sync Schedule**: A cron expression plus a timezone, interpreted against the
  configured timezone's wall clock, producing a stream of future firing times.
- **Credentials**: The GitHub Personal Access Token and commit author identity. The
  PAT MUST never leak into any observable artifact.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: From a freshly installed plugin with a minimal valid configuration
  (target repository, PAT, server name, cadence), the first successful push lands in
  the target repository within 5 minutes of the next scheduled cron tick.
- **SC-002**: Running the upstream Tickstats `generate.py` against the data produced
  by the plugin completes without error and produces a valid dashboard.
- **SC-003**: When the stats tree is byte-for-byte unchanged between two consecutive
  sync ticks, zero commits are created on the remote branch.
- **SC-004**: On a Paper server with at least 10 concurrently active players, a full
  sync (read + commit + push) causes no operator-perceptible TPS drop and no
  player-visible lag during the operation.
- **SC-005**: A new `snapshots/YYYY-MM-DD/` directory appears in the target
  repository within one cron tick of the first sync of each new local calendar day,
  100% of the time when snapshots are enabled and the plugin is running.
- **SC-006**: A configuration change followed by `/tickstats reload` results in the
  new schedule being active within one command round-trip — the operator sees the
  updated "next scheduled sync" via `/tickstats status` immediately after the reload.
- **SC-007**: Across every log file, in-game message, git commit message, and command
  output produced by the plugin in any test scenario, the literal PAT value is never
  present (verified by scanning the artifacts for the token string).
- **SC-008**: After 30 consecutive days of scheduled operation on a live server with
  healthy network and GitHub availability, the plugin has required zero manual
  interventions (no restart, no config fix, no manual rescue).
- **SC-009**: A manual `/tickstats sync` command returns an in-game acknowledgement
  within 1 second and completes its sync within the same time budget as a scheduled
  sync.
- **SC-010**: Transient network or GitHub errors do not prevent the next scheduled
  sync from firing — the scheduling loop maintains 100% uptime across transient
  failure test scenarios.

## Assumptions

- The target GitHub repository already exists and the configured token is one of:
  (a) a **fine-grained PAT** scoped to the target repository with `Contents: Read and
  write` (recommended), or (b) a **classic PAT** with the `repo` scope (fallback,
  discouraged for personal accounts because it grants access to all the owner's
  repositories). In case (b), a dedicated machine/bot account with Write collaborator
  access on the target repo is strongly recommended. The plugin does not create
  repositories.
- The Paper server's data folder has enough free disk space to hold a shallow
  (depth-1) clone of the target repository. Typical footprint is a few MB; large
  historical snapshot trees may push this higher. Operators are expected to provision
  disk accordingly.
- The operator is a server administrator (`op`) with filesystem access to `plugins/`
  and `config.yml`. Non-admin players cannot configure the plugin.
- The host running the Paper server has outbound HTTPS connectivity to
  `api.github.com` and `github.com`. Proxy configuration is inherited from the JVM
  environment if set; the plugin does not implement its own proxy logic.
- Stats files are written by the vanilla Minecraft server on player logout or
  autosave; the plugin relies on this existing behavior and does not trigger saves
  itself.
- The configured server name is safe for use as a directory segment (alphanumeric,
  hyphen, underscore). Exact validation rules are refined at the planning stage.
- The upstream Tickstats `generate.py` file-layout contract remains stable for the
  lifetime of this specification. Any upstream change is handled via a constitution
  amendment before the plugin reacts.
- The plugin is the *third* way to use Tickstats; the `sync-stats.ps1` script and the
  local `generate.py` one-shot flow both remain supported upstream and are out of
  this project's scope.

## Out of Scope

- Modifying `generate.py` or any other Python code in upstream Tickstats. The plugin
  is a pure producer; Tickstats consumes the file-layout contract.
- Generating the HTML dashboard from within the plugin. Dashboard generation remains
  the job of `generate.py` running in upstream CI.
- Replacing or deprecating the existing `sync-stats.ps1` workflow — it remains
  usable for Crafty or Windows operators who do not run the plugin.
- Any web UI or admin dashboard for configuring the plugin. Configuration is
  `config.yml` plus in-game commands.
- Multi-repository or multi-server-per-plugin operation. One plugin instance syncs
  one Minecraft server to one GitHub repository.
- Support for non-GitHub remotes (GitLab, Gitea, Bitbucket, self-hosted Git). GitHub
  only in this iteration.
- Authentication mechanisms other than a GitHub PAT (SSH keys, GitHub App
  installation tokens, OAuth device flow). PAT only.
- Retention or pruning of historical snapshots. Once a `snapshots/YYYY-MM-DD/`
  directory is written, it is permanent unless the operator prunes it manually in the
  target repository.
