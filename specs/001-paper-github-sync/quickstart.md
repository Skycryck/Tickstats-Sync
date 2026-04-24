# Quickstart — TickstatsSync

**Audience**: A Paper server operator installing TickstatsSync for the first time.
**Time to first successful sync**: ~10 minutes.
**Prerequisites**: a running Paper **26.1.2** server (build #19 or later, JDK 25
runtime), a GitHub account, operator (op) access on the server. Older Paper
releases refuse to load the plugin because `plugin.yml` declares
`api-version: "26.1.2"`. To build from source you also need JDK 25 on your PATH
(or trust the Gradle wrapper to auto-provision it via foojay — see the
project README).

---

## 1. Create the target GitHub repository

1. Go to <https://github.com/new> and create a **private** (or public — your choice)
   repository. Example: `your-username/mc-server-stats`.
2. Initialize with a README (any content is fine — the plugin works with empty repos
   too, but starting with at least one commit on `main` is the smoothest path).

---

## 2. Mint a GitHub token

### Recommended: fine-grained PAT

1. Go to <https://github.com/settings/personal-access-tokens/new>.
2. Set the expiration to something reasonable (90 days is a good default).
3. **Repository access** → *Only select repositories* → pick your stats repo.
4. **Permissions** → *Repository permissions* → **Contents: Read and write**.
5. Generate the token. **Copy it immediately** — GitHub only shows it once.

### Fallback: classic PAT

Only use this path if your GitHub plan, organization, or Enterprise Server version
does not support fine-grained PATs. Then:

1. **Create a dedicated machine/bot GitHub account.** Do not reuse your personal
   account — a classic PAT's `repo` scope grants access to *every* repository the
   account can see.
2. Invite the bot account to your stats repository as a collaborator with the
   **Write** role (not Admin — Write is sufficient for committing and pushing, and
   keeps the blast radius minimal if the token leaks).
3. From the bot account, generate a classic PAT at
   <https://github.com/settings/tokens> with the `repo` scope.

---

## 3. Drop the plugin JAR in place

1. Download `TickstatsSync-<version>.jar` from the project's releases.
2. Place it in your Paper server's `plugins/` directory.
3. Start (or restart) the server once. On first startup the plugin writes a default
   `plugins/TickstatsSync/config.yml` and logs a line like:

   ```
   [TickstatsSync] Config file created at plugins/TickstatsSync/config.yml — edit it and run /tickstats reload.
   ```

4. The plugin does NOT attempt a sync yet — it waits for a valid config.

---

## 4. Fill in `config.yml`

Open `plugins/TickstatsSync/config.yml` in a text editor and change at minimum:

```yaml
github:
  repo: "your-username/mc-server-stats"
  token: "ghp_..."                   # or leave "" and export TICKSTATSSYNC_GITHUB_TOKEN

server:
  name: "your-server-short-name"
```

Leave the rest at their defaults (sync every 6 h in `Europe/Paris`, snapshots
enabled). See `contracts/config-schema.md` for every option.

### Alternative: token from environment variable

If you manage secrets outside of files (systemd, Docker, Pterodactyl), leave
`github.token: ""` and export the PAT into the server's environment:

```bash
# systemd service example
Environment=TICKSTATSSYNC_GITHUB_TOKEN=ghp_...
```

The plugin reads it automatically on load.

---

## 5. Activate the new config

In-game, as an op, run:

```
/tickstats reload
```

Expected response:

```
[TickstatsSync] Configuration reloaded. Next sync: 2026-04-22 20:00:00 (Europe/Paris).
```

If you see a red error message instead, fix the reported issue in `config.yml` and
run `/tickstats reload` again. The previous configuration (which was none, on first
setup) stays inactive until a valid one lands.

---

## 6. Verify with a manual sync

```
/tickstats sync
```

Expected response (first run against an empty/fresh repo):

```
[TickstatsSync] Sync started.
[TickstatsSync] Sync complete: pushed 4 file(s), commit a1b2c3d, 812 ms.
```

Now open your repository in a browser. You should see:

```
stats/
└── your-server-short-name/
    ├── data/
    │   ├── <uuid>.json
    │   └── ...
    └── snapshots/
        └── 2026-04-22/        ← first sync also wrote today's snapshot
            ├── <uuid>.json
            └── ...
```

---

## 7. Verify observability

```
/tickstats status
```

Expected (color codes shown as labels):

```
[GOLD]TickstatsSync [GRAY]v1.0.0
[GRAY]Last successful sync: [GREEN]2026-04-22 14:00:03 (Europe/Paris)
[GRAY]Next scheduled sync:  [GREEN]2026-04-22 20:00:00 (Europe/Paris)
[GRAY]Detected stats files: [GREEN]42
[GRAY]PAT configured:       [GREEN]yes
[GRAY]Repo reachability:    [GREEN]ok (checked at last sync)
[GRAY]Last outcome:         [GREEN]SUCCESS_WITH_COMMIT (trigger: SCHEDULED, attempts: 1)
```

The token value is never printed. Only the boolean "yes/no" is shown.

Also verify the structured log line (R15 format) after each cycle; tail the
server log and confirm one `[TickstatsSync]` line per outcome with every
field present:

```
[TickstatsSync] outcome=SUCCESS_WITH_COMMIT trigger=SCHEDULED files=42 commit=a1b2c3d duration_ms=812 category=none attempts=1
[TickstatsSync] outcome=SUCCESS_NO_CHANGES trigger=SCHEDULED files=42 commit=none duration_ms=124 category=none attempts=1
```

### 7.1 Release-gate empirical record (Phase 8 / T046)

This walkthrough was last executed end-to-end against a live Paper 26.1.2
build #19-alpha server running on JDK 25 (Linux) with the disposable
repository [Skycryck/tickstats-live-test](https://github.com/Skycryck/tickstats-live-test).
Empirical outcome recorded at release prep:

- **SC-001** (sync lands correctly on schedule): cron `*/5 * * * *` fired
  within five minutes of server start, producing a `Update stats for
  serveur-test - YYYY-MM-DD HH:mm` commit with one file per UUID in
  `stats/serveur-test/data/`. Verified via the repo's commit history.
- **SC-003** (idempotence): subsequent scheduled ticks with unchanged
  stats emitted `outcome=SUCCESS_NO_CHANGES` log lines and produced no
  commits.
- **SC-004** (non-intrusive): no operator-perceptible TPS drop with 10+
  concurrent players during a sync cycle. Sync work runs on Paper's async
  scheduler; the main thread only parses command arguments.
- **SC-010** (scheduler loop survives failure): a forced transient
  failure (wrong PAT scope, then corrected) did NOT kill the cron loop;
  the next scheduled tick fired on time after the AUTH failure was logged.
- **R15 log format**: every captured log line conformed to the pinned
  `outcome=... trigger=... files=... commit=... duration_ms=... category=... attempts=...`
  shape.
- **Inert mode**: with a deliberately broken `config.yml`, the plugin
  stayed enabled; `/tickstats status` rendered the red `⚠ config invalid:
  <reason>` banner; `/tickstats reload` recovered cleanly once the file
  was fixed, returning the "plugin is now active" template.

---

## 8. Wire up the upstream dashboard (optional but recommended)

This is a one-time setup on the **target repository** (the stats repo you just
created), not on the Minecraft server:

1. Fork or reference upstream [`generate.py`](https://github.com/Skycryck/tickstats)
   from your stats repo's CI (GitHub Actions).
2. Add a workflow that runs `generate.py` on every push to `main` and deploys the
   result to GitHub Pages.
3. Open `https://<your-username>.github.io/<repo>/` — your dashboard updates every
   time the plugin pushes.

Plugin side: nothing else to configure. The file-layout contract documented in
`contracts/repo-layout.md` is all `generate.py` needs.

### 8.1 Verification procedure (run this once before shipping — powers task T046a)

Before declaring a release ready, run upstream `generate.py` locally against the
data the plugin actually produced. This verifies SC-002 (the Tickstats producer
contract is honoured end-to-end). This is **not** optional for maintainers
cutting a release — it is the gate that catches layout regressions before users
see them.

**Python version**: upstream `generate.py` uses PEP 604 type unions (`Path | None`)
and generic alias subscripts (`list[str]`) introduced in Python 3.10. Use
**Python 3.10 or newer**. Python 3.9 will fail at import time with
`TypeError: unsupported operand type(s) for |`. Upstream's own CI pins
`python-version: "3.12"` — matching that is the safest choice.

1. Trigger one real sync cycle against a disposable GitHub repo (either wait for
   a scheduled tick or run `/tickstats sync`). Verify in a browser that
   `stats/<server-name>/data/<uuid>.json` and
   `stats/<server-name>/snapshots/YYYY-MM-DD/<uuid>.json` landed.
2. Clone the upstream Tickstats repo into a scratch directory:
   ```bash
   git clone https://github.com/Skycryck/tickstats /tmp/tickstats-check
   cd /tmp/tickstats-check
   ```
3. Upstream `generate.py` has **no third-party dependencies** — the imports are
   all Python stdlib (`json`, `urllib.request`, `zoneinfo`, `argparse`, `pathlib`).
   There is no `requirements.txt` to install. A venv is still fine if you want
   isolation from any site-packages globals, but no `pip install` step is
   required:
   ```bash
   python -m venv .venv
   source .venv/bin/activate     # on Windows: .venv\Scripts\Activate.ps1
   # no pip install needed
   ```
4. Clone the stats repo the plugin just pushed to, into a sibling directory, so
   `generate.py` sees it as a local path:
   ```bash
   git clone https://github.com/<your-username>/mc-server-stats /tmp/mc-stats-check
   ```
5. Run `scripts/generate.py` against the **`data/` directory** of the server
   (note: `generate.py` takes a positional `<data_dir>` argument, not a
   `--stats-dir` flag):
   ```bash
   python scripts/generate.py /tmp/mc-stats-check/stats/<server-name>/data \
       --title "<server-name>"
   echo "exit code: $?"
   ```
   The HTML is written to
   `/tmp/mc-stats-check/stats/<server-name>/index.html` (next to the `data/`
   folder, per upstream convention).
6. Pass criteria:
   - exit code is `0`;
   - no tracebacks or `[ERR]` log lines on stderr;
   - the generated dashboard HTML opens in a browser without broken layout or
     JavaScript console errors;
   - at least one data row per UUID present in `stats/<server-name>/data/`.
7. If `generate.py` fails, stop — the plugin's output has drifted from the
   Tickstats producer contract (Principle I). File the discrepancy as a
   regression against [contracts/repo-layout.md](contracts/repo-layout.md)
   before shipping.

**Release-gate empirical record (Phase 8 / T046a)**: the
[Skycryck/tickstats-live-test](https://github.com/Skycryck/tickstats-live-test)
repository receives commits from a live Paper 26.1.2 / JDK 25 server running
this plugin. Its GitHub Actions pipeline (`.github/workflows/update-stats.yml`,
pinned to `python-version: "3.12"`) runs `python scripts/generate.py
"stats/${SERVER}/data" --title "${TITLE}"` on every push that touches
`stats/*/data/**`, then auto-commits the refreshed `stats/<server>/index.html`
and deploys via GitHub Pages. At release prep the upstream-CI job had run
successfully against the plugin-produced layout (7 UUID JSON files, vanilla
`DataVersion` + `stats` shape, a `snapshots/YYYY-MM-DD/` folder for today),
producing a 50 KB `index.html` deployed to the live dashboard. The producer
contract is therefore verified end-to-end in the exact pipeline operators will
use. Maintainers MUST re-run step 5 locally (with Python ≥ 3.10) whenever the
plugin's output layout changes.

---

## 9. Routine operations

| Operator intent | Command |
|-----------------|---------|
| "Did the last scheduled sync succeed?" | `/tickstats status` |
| "Force a push right now (before an announcement)" | `/tickstats sync` |
| "I just edited config.yml to change the cadence" | `/tickstats reload` |
| "I rotated the PAT" | Update `github.token` in config, then `/tickstats reload` |
| "Temporarily stop syncing" | Set `sync.cron` to something far-future and `/tickstats reload`, OR unload the plugin |

---

## 10. Troubleshooting quick reference

| Symptom | First thing to check |
|---------|----------------------|
| `/tickstats status` shows `Repo reachability: failed (AUTH)` | Token expired or missing scope. Mint a fresh fine-grained PAT with `Contents: Read and write`. |
| `Reload failed: target repository not reachable` | `github.repo` typo, private repo without collaborator, or token revoked. |
| `Sync failed (NETWORK). See server log for details.` | Transient — wait for next cron tick or check outbound HTTPS. |
| Nothing is being committed even though players are playing | Check `sync.snapshots-enabled`, check `sync.cron` expression in `config.yml`, check `sync.timezone`. |
| Plugin logs `config invalid: ...` on start and `/tickstats status` shows the red `⚠ config invalid` banner | `config.yml` failed validation (missing key, bad cron, unknown timezone) OR contains a non-safe YAML tag (`!!java.net.URL`, `!!javax.script.ScriptEngineManager`, etc.) — Paper's bundled SnakeYAML 2.x refuses those by design. Fix the file and run `/tickstats reload`. The plugin stays enabled in inert mode meanwhile so commands keep working. |
| Running upstream `generate.py` locally throws `TypeError: unsupported operand type(s) for \|: 'type' and 'NoneType'` | Python < 3.10. Upstream uses PEP 604 type unions; use Python 3.10+ (upstream CI runs 3.12). |
| Log lines look garbled | The plugin emits UTF-8. Ensure your terminal / log viewer renders UTF-8. |

For anything else, read `plugins/TickstatsSync/logs/` (if present) or the main server
log — the plugin's failure lines all carry a `[TickstatsSync]` prefix.
