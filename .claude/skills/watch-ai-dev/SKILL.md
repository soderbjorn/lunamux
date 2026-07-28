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

Split `$ARGUMENTS` into a **cadence** and a **passthrough tail** forwarded verbatim
to `/ai-dev`. Default cadence: **15m**. Default tail: empty.

`$ARGUMENTS` opens with a cadence when its first token starts with a digit, or is
one of `auto` / `self-paced` / `dynamic` (all three mean "let the model pace
itself"). Read it generously — all of these are the same thing:

```
30m      30 minutes      30 min      30
1h       1 hour          1 hr        90m      1h30m
```

- **A bare number is minutes.** `30` is half an hour, `2` is two minutes — one
  rule, no guessing from magnitude. A mistyped `2` trips the under-5-minutes
  warning in §5 rather than quietly thrashing.
- **A word form may be two tokens.** `1 hour` is a cadence followed by an empty
  tail, not a cadence of `1` and a tail of `hour`. Consume the unit word.
- **Everything after the cadence is the tail**, untouched.
- **No leading cadence at all** — `$ARGUMENTS` is empty, or starts with something
  that is plainly an argument like `--max 2` — means the default cadence and the
  whole string as tail. That is not an error.

**If the first token starts with a digit but you cannot read it as a duration,
stop.** `30mn`, `1hh`, `5x` — do not arm anything, do not sweep. Say what you
could not parse and list the accepted forms. Somebody typing a number meant a
cadence, and arming a 15m default they did not ask for is worse than doing
nothing: they would walk away believing the loop runs on their number.

| `$ARGUMENTS` | Cadence | Tail forwarded |
|---|---|---|
| *(empty)* | `15m` | *(empty)* |
| `30m` | `30m` | *(empty)* |
| `30` | `30m` | *(empty)* |
| `2 hours` | `2h` | *(empty)* |
| `1h --max 1` | `1h` | `--max 1` |
| `45 min --max 1` | `45m` | `--max 1` |
| `auto` | self-paced | *(empty)* |
| `--max 2` | `15m` | `--max 2` |
| `30mn` | — | refuse, do not arm |

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

- Never arm a cadence the user did not ask for. An unreadable duration is a refusal
  (§1), not a fallback to 15m.
- If a `/loop` is already running `/ai-dev` in this session, do **not** sweep and do
  **not** arm a second one. Two loops would both snapshot the same column and
  dispatch the same tickets twice. Report that it is already armed and exit.
- Warn inline if the cadence is under 5 minutes: one `/ai-dev` sweep routinely takes
  longer than that, since it builds and verifies real changes.
- Beyond the one sweep in §2, touch nothing yourself. No `git`, no worktrees, no
  `gh`, no Lunicle MCP writes — all of that belongs to `/ai-dev`.
