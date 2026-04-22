<!-- SPECKIT START -->
Active feature: **001-paper-github-sync** — TickstatsSync, a Paper plugin that syncs
Minecraft stats to a GitHub repository on a cron schedule.

Before writing or modifying TickstatsSync code, read:

- [specs/001-paper-github-sync/plan.md](specs/001-paper-github-sync/plan.md) — stack,
  architecture, project structure, Constitution Check.
- [specs/001-paper-github-sync/spec.md](specs/001-paper-github-sync/spec.md) — spec
  + clarifications (git strategy, concurrent-sync rejection, PAT guidance).
- [specs/001-paper-github-sync/research.md](specs/001-paper-github-sync/research.md)
  — 15 resolved design decisions (JGit 6.10, cron-utils 9.2, Brigadier commands,
  shadow relocation map, credential redaction, etc.).
- [specs/001-paper-github-sync/data-model.md](specs/001-paper-github-sync/data-model.md)
  — Java record/enum types.
- [specs/001-paper-github-sync/contracts/](specs/001-paper-github-sync/contracts) —
  config.yml schema, plugin.yml, /tickstats command surface, produced repo layout
  (upstream Tickstats contract).

Constitution: [.specify/memory/constitution.md](.specify/memory/constitution.md) —
Principle I (repo layout) and XI (English-only artifacts) are inviolable.
<!-- SPECKIT END -->
