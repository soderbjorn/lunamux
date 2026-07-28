---
name: watch-ai-dev
description: Run an immediate /ai-dev sweep, then arm a background loop that repeats it on an interval. Default cadence 15m, configurable.
---

Arguments: $ARGUMENTS

Sweep the board **now**, then start a `/loop` that keeps sweeping it on a recurring
cadence. Stopping it is the user's business (interrupt the loop).

Each sweep snapshots this repo's Lunicle "ready for AI development" column, claims
everything in it, and drives each ticket to a pull request in its own sibling
worktree. See `/ai-dev` for what a sweep actually does.

**The first sweep runs immediately, before the loop is armed.** Somebody who arms a
watcher because there is work waiting should not sit through a full interval of
nothing happening — and if the column is empty, an idle sweep costs one board read.

## 1. Parse arguments

Split `$ARGUMENTS` into a **cadence token** and a **passthrough tail**:

- The first whitespace-separated token is the cadence if it looks like an interval
  (`15m`, `1h`, `45s`, `2h30m`) or is one of `auto` / `self-paced` / `dynamic` (all
  three mean "let the model pace itself").
- Everything after it is the tail, forwarded verbatim to `/ai-dev`.
- If the first token is not a cadence, the whole of `$ARGUMENTS` is the tail and the
  cadence is the default.

Default cadence: **15m**. Default tail: empty.

| `$ARGUMENTS` | Cadence | Tail forwarded |
|---|---|---|
| *(empty)* | `15m` | *(empty)* |
| `30m` | `30m` | *(empty)* |
| `1h --max 1` | `1h` | `--max 1` |
| `auto` | self-paced | *(empty)* |
| `--max 2` | `15m` | `--max 2` |

## 2. Sweep once, now

Invoke the `ai-dev` skill via the Skill tool with the tail as its arguments, and let
it finish. This is a full sweep — if the column has work in it, this step runs for as
long as that work takes.

Keep its report; you print it in §4.

Do this **before** arming the loop, not after. Arming first would let a tick fire
while this sweep is still running, and two sweeps overlapping would snapshot the same
column twice and dispatch every ticket twice over. Sweeping first also means the
cadence starts counting from a clean board.

If the sweep fails outright, still arm the loop — a transient failure should not
leave the user with no watcher — but say so in the report.

## 3. Arm the loop

Invoke the `loop` skill via the Skill tool:

- Fixed cadence → args `<cadence> /ai-dev <tail>`
- Self-paced → args `/ai-dev <tail>` (no interval)

Trim the trailing space when the tail is empty. Do not wrap the call in another
layer — calling `/loop` directly is this skill's entire job here.

## 4. Report and exit

Print the sweep's own report, then exactly one line about the loop:

```
Armed: /loop 15m /ai-dev — next sweep in 15m; interrupt the loop to stop it.
```

or, self-paced:

```
Armed: /loop /ai-dev (self-paced) — the model picks each interval; interrupt the loop to stop it.
```

Then stop.

## 5. Guard rails

- If a `/loop` is already running `/ai-dev` in this session, do **not** sweep and do
  **not** arm a second one. Two loops would both snapshot the same column and
  dispatch the same tickets twice. Report that it is already armed and exit.
- Warn inline if the cadence is under 5 minutes: one `/ai-dev` sweep routinely takes
  longer than that, since it builds and verifies real changes.
- Beyond the one sweep in §2, touch nothing yourself. No `git`, no worktrees, no
  `gh`, no Lunicle MCP writes — all of that belongs to `/ai-dev`.
