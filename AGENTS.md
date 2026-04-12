# AGENTS

Add repo-specific instructions above or below the managed facts block. Keep manual guidance outside the generated markers.

<!-- BEGIN app-wabbit-dev managed facts -->
## Generated Facts

- Workspace config source of truth: `root.clj` at the workspace root.
- Use `dev where` from this repo to confirm the inferred workspace, repo, and project context.
- Canonical repo target: `kotlin-inspect`. Useful entrypoints: `dev project show kotlin-inspect`, `dev build kotlin-inspect`, `dev check kotlin-inspect`.
- Setup-managed files are regenerated with `dev setup kotlin-inspect`; avoid hand-editing stamped generated files.
- Sanctioned override files in this repo: `build.extra.gradle.kts`, `settings.local.gradle.kts`.
- Configured project types: `kotlin/jvm`. Docs: `dokka`.
<!-- END app-wabbit-dev managed facts -->
