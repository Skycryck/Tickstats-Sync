# Quickstart — TickstatsSync

**Audience**: A Paper server operator installing TickstatsSync for the first time.
**Time to first successful sync**: ~10 minutes.
**Prerequisites**: a running Paper 1.21.x server, a GitHub account, operator (op)
access on the server.

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
[GRAY]Last outcome:         [GREEN]SUCCESS_WITH_COMMIT
```

The token value is never printed. Only the boolean "yes/no" is shown.

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
| Nothing is being committed even though players are playing | Check `snapshots-enabled`, check cron expression in `config.yml`, check `sync.timezone`. |
| Log lines look garbled | The plugin emits UTF-8. Ensure your terminal / log viewer renders UTF-8. |

For anything else, read `plugins/TickstatsSync/logs/` (if present) or the main server
log — the plugin's failure lines all carry a `[TickstatsSync]` prefix.
