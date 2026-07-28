# The repositories, and the toolkit policy

Shared reference for `/ai-dev`. Identical in every repo that uses the skill — the
per-repo details live in `config.json`, not here.

## Checkouts

| Project | Main checkout | GitHub |
|---|---|---|
| Lunicle (issue tracker) | `/Users/soderbjorn/repo-private/lunicle/main` | https://github.com/soderbjorn/lunicle |
| Lunamux (terminal) | `/Users/soderbjorn/repo-private/lunamux/main` | https://github.com/soderbjorn/lunamux |
| Lunula (UI toolkit) | `/Users/soderbjorn/repo-private/lunula/main` | https://github.com/soderbjorn/lunula |

Worktrees are **siblings of the main checkout**, never nested inside it:
`/Users/soderbjorn/repo-private/lunicle/lnl-190-some-slug`. (Some older worktrees
live under `.claude/worktrees/` — leave those alone, but do not add to them.)

## Editing the toolkit is encouraged

If a ticket is best solved by a change to Lunula, **make the change in Lunula**.
That is preferable to a clumsy local contraption in the consuming app, and it lets
every other Lunula consumer benefit.

- The Lunula change needs its **own PR** in `soderbjorn/lunula`, cross-linked with
  the app PR and linked from the Lunicle issue.
- It does **not** need its own ticket in the Lunula project.
- It does **not** normally need a version bump. Lunicle and Lunamux pick Lunula up
  **from sources**, via a relative directory path, so a toolkit edit flows into the
  consuming build with no publish step.

## How the sources pickup actually resolves

`settings.gradle.kts` finds Lunula by walking **up** from the consuming project's
`rootDir` looking for `../lunula/develop` or `../lunula/main`, then wiring it in as
a Gradle composite build. Two consequences that matter to `/ai-dev`:

1. **Every sibling worktree resolves the same shared `lunula/main` tree.** Two
   tickets running at once would edit one working tree and corrupt each other's
   change. So `/ai-dev` gives every ticket its **own** Lunula worktree and points
   the build at it with `-Plunula.toolkit.path=…`.
2. **That path must be relative.** It is resolved with `File(rootDir, path)`, and
   Java re-relativises an absolute child — so an absolute path finds nothing,
   resolution falls back silently to the committed `libs-repo`, and your toolkit
   edits are simply not in the build. With a green build to say so. Always pass
   `-Plunula.toolkit.path=../../lunula/<slug>`.
