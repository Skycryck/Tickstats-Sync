# TickstatsSync

Folia is not supported. Targeting Paper 26.1.2+ (build #19 or later, JDK 25 required).

**TickstatsSync** is a Paper plugin that automatically syncs your Minecraft server's
vanilla stats (`world/players/stats/*.json` on Paper 26.x) to a GitHub repository
on a cron-driven schedule, in the layout consumed by upstream
[Tickstats](https://github.com/Skycryck/tickstats)' `generate.py`.

The plugin authenticates with a GitHub Personal Access Token (PAT), maintains a
plugin-private shallow clone of the target repository, pushes only when content
has actually changed, produces one first-write-wins snapshot per local calendar
day, and stays entirely off the main server thread.

---

## Quickstart

See [specs/001-paper-github-sync/quickstart.md](specs/001-paper-github-sync/quickstart.md)
for the full operator walkthrough. Short version:

1. Drop `TickstatsSync-<version>.jar` into `plugins/` and (re)start the server once.
   The plugin writes a default `plugins/TickstatsSync/config.yml` and waits.
2. Mint a GitHub Personal Access Token (see "GitHub token" below).
3. Edit `plugins/TickstatsSync/config.yml` — set `github.repo`, `github.token` (or
   export `TICKSTATSSYNC_GITHUB_TOKEN`), and `server.name` at minimum.
4. Run `/tickstats reload` in-game as an op.
5. Run `/tickstats sync` once to verify. Check your repository — you should see
   `stats/<server-name>/data/<uuid>.json` and
   `stats/<server-name>/snapshots/<today>/<uuid>.json`.

---

## GitHub token

### Recommended: fine-grained PAT

1. Go to <https://github.com/settings/personal-access-tokens/new>.
2. **Repository access** → *Only select repositories* → pick your stats repo.
3. **Permissions** → *Repository permissions* → **Contents: Read and write**.
4. Generate and copy the token. GitHub only shows it once.

### Fallback: classic PAT

Use this path only if your GitHub plan, organization, or Enterprise Server build
does not support fine-grained PATs. Classic PATs with the `repo` scope grant
access to **every** repository the issuing account can see, so:

1. Create a **dedicated machine/bot GitHub account** — never reuse your personal
   account.
2. Invite the bot to your stats repository as a collaborator with the **Write**
   role (not Admin — Write is sufficient for pushes and keeps the blast radius
   minimal if the token leaks).
3. Generate the classic PAT from the bot account at
   <https://github.com/settings/tokens> with the `repo` scope.

---

## Commands

| Command | Description |
|---------|-------------|
| `/tickstats sync` | Trigger a sync cycle immediately (off-thread). |
| `/tickstats status` | Show plugin health: last sync, next sync, PAT configured, reachability. |
| `/tickstats reload` | Re-read and re-validate `config.yml` without restarting the server. |

All commands require the `tickstats.admin` permission (default: op).

---

## Building from source

**Requirements**: JDK **25** (Temurin, Azul Zulu, Liberica — any vendor works).

The Gradle wrapper auto-provisions JDK 25 on first build through the
`org.gradle.toolchains.foojay-resolver-convention` plugin enabled in
`settings.gradle.kts`. Contributors behind corporate proxies that block
<https://api.foojay.io> must install JDK 25 manually.

```bash
./gradlew shadowJar
```

The shaded plugin JAR is written to `build/libs/TickstatsSync-<version>.jar`.

---

## Platform compatibility

- **Paper 26.1.2+** (build #19 or later). Servers running older Paper refuse to
  load the plugin because `plugin.yml` declares `api-version: "26.1.2"`.
- **Folia**: **not supported** in v1. Folia's plugin loader refuses the JAR
  because `folia-supported: true` is not declared.
- **Java 25** runtime, required by Paper 26.x.

---

## Configuration

See [specs/001-paper-github-sync/contracts/config-schema.md](specs/001-paper-github-sync/contracts/config-schema.md)
for the full key-by-key reference. The default `config.yml` generated on first
boot carries inline comments for every field.

---

## Troubleshooting

See [specs/001-paper-github-sync/quickstart.md §10](specs/001-paper-github-sync/quickstart.md).
