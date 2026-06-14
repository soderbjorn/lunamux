---
description: Arm a background loop that runs `/babysit-repo` on an interval. Default cadence 15m. One-shot starter — exits as soon as the loop is armed.
---

Arguments: $ARGUMENTS

Start a `/loop` that runs `/babysit-repo` on a recurring cadence and exit. This is a **one-shot starter**, not a long-running command — the actual watching happens inside the loop that this skill arms. Stopping the watcher is the user's responsibility (Ctrl-C on the loop, or `/loop` management commands).

Each tick of `/babysit-repo` dispatches across three tracks in priority order — **follow-ups → issues → reviews** — and stops at the first track with a candidate. Follow-ups address repo-owner comments on Claude-authored PRs; issues implement `ai-dev`-labelled work; reviews post AI reviews on unreviewed PRs. See `/babysit-repo` for the details.

## 1. Parse arguments

Split `$ARGUMENTS` into a **cadence token** and a **passthrough tail**:

- First whitespace-separated token is treated as the cadence if it looks like an interval (`30m`, `1h`, `5m`, `45s`, `2h30m`, etc.) or one of the literal strings `auto` / `self-paced` / `dynamic` (all three mean "model self-paces").
- Everything after the cadence token is the passthrough tail — forwarded verbatim to `/babysit-repo`.
- If the first token doesn't look like a cadence, treat `$ARGUMENTS` entirely as the passthrough tail and use the default cadence.

Defaults:

- Cadence: `15m`.
- Passthrough tail: empty (all three tracks eligible — follow-ups, issues, reviews — with `ai-dev` label filter on the issue track).

Examples:

| `$ARGUMENTS`                        | Cadence         | Tail forwarded to `/babysit-repo` |
|-------------------------------------|-----------------|-----------------------------------|
| *(empty)*                           | `15m`           | *(empty)*                         |
| `15m`                               | `15m`           | *(empty)*                         |
| `1h --reviews-only`                 | `1h`            | `--reviews-only`                  |
| `auto`                              | self-paced      | *(empty)*                         |
| `self-paced --label bug`            | self-paced      | `--label bug`                     |
| `--reviews-only`                    | `15m` (default) | `--reviews-only`                  |

## 2. Arm the loop

Assemble the loop invocation:

- If self-paced: the inner command is `/babysit-repo <tail>` (trim trailing whitespace when tail is empty) and the `/loop` call omits the interval.
- Otherwise: `/loop <cadence> /babysit-repo <tail>` (same trim rule).

Invoke the `loop` skill via the Skill tool with the composed args. Do **not** wrap the call in an extra layer — calling `/loop` directly is the whole point of this skill.

## 3. Report and exit

After the Skill tool returns, print exactly one line so the user sees what was armed, e.g.:

```
Armed: /loop 15m /babysit-repo — first tick scheduled; interrupt the loop to stop it.
```

Or for self-paced:

```
Armed: /loop /babysit-repo (self-paced) — model picks each tick interval; interrupt the loop to stop it.
```

Then exit. Do **not** attempt to run the first tick yourself — the loop's own wake-up handler will do that.

## 4. Guard rails

- If an existing `/loop` is already running `/babysit-repo` in this session, do not arm a second one. Report that the loop is already armed and exit.
- If the cadence looks suspiciously short (under 5 minutes for a wall-clock interval), warn the user inline that `/babysit-repo` can launch implementation turns that routinely exceed 5 minutes and ask whether to proceed. Short cadences are fine for review-only runs (`--reviews-only` in the tail).
- Do not modify repo state, do not open worktrees, do not call `gh` — this skill only arms the loop. All real work happens inside the ticks.
