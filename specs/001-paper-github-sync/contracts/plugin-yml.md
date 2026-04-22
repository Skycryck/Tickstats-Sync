# Contract: `plugin.yml`

**Feature**: 001-paper-github-sync
**Type**: Paper plugin descriptor contract
**Location**: `src/main/resources/plugin.yml`

The `plugin.yml` file declares plugin metadata and the `tickstats.admin` permission
node. Commands themselves are **not** declared here — they are registered at runtime
through Paper's Brigadier Lifecycle API (see R1 in `research.md`).

## Template

```yaml
name: TickstatsSync
version: ${project.version}
main: com.skycryck.tickstatssync.TickstatsSyncPlugin
api-version: "26.1.2"
load: POSTWORLD
authors: [Skycryck]
description: Automated Paper-to-GitHub stats sync for the Tickstats dashboard.
website: https://github.com/Skycryck/tickstats-sync

permissions:
  tickstats.admin:
    description: Full access to TickstatsSync administrative commands.
    default: op
```

## Field-by-field

| Key | Value | Rationale |
|-----|-------|-----------|
| `name` | `TickstatsSync` | Appears in `/plugins`, log prefixes, and plugin data folder name. |
| `version` | `${project.version}` | Substituted by Gradle's `processResources` task from `build.gradle.kts`. |
| `main` | `com.skycryck.tickstatssync.TickstatsSyncPlugin` | Java main class (extends `JavaPlugin`). |
| `api-version` | `"26.1.2"` | Pinned to the Paper 26.1.2 line per research R16. This value **gates plugin loading**: Paper servers older than 26.1.2 will refuse to load the plugin and log an explicit "api-version not satisfied" message to the server console. When Paper publishes 26.1.3 / 27.x and we re-test, the value is bumped in one commit. |
| `load` | `POSTWORLD` | No world-listener dependency; post-world load is safe and defers onEnable past world startup. |
| `authors` | `[Skycryck]` | Upstream Tickstats project owner. |
| `permissions.tickstats.admin.default` | `op` | Matches constitution Principle VIII — admin-only by default. |

## Commands deliberately absent

The `commands:` block is omitted. If it were present, Bukkit would register `/tickstats`
as a legacy-style command and race with the Brigadier registration from
`onEnable`, producing a duplicate-command warning. Relying on Brigadier alone gives
us per-subcommand permission enforcement and cleaner argument parsing.

## Folia declaration

**None.** `folia-supported: true` MUST NOT appear in this file. Folia is not a
supported target for v1 — see research R10 for the full rationale and the revisit
criteria. Without the flag, Folia refuses to load the plugin, which is the intended
behavior.

## Version injection

`build.gradle.kts` configures `processResources`:

```kotlin
tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") { expand(props) }
}
```

So `${project.version}` in `plugin.yml` resolves to the Gradle project version at
build time.
