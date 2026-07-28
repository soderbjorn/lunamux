---
name: ai-dev
description: One cycle of autonomous ticket work. Snapshots the Lunicle "ready for AI development" column, claims every ticket in it immediately, and drives each to a pull request in its own sibling worktree via its own subagent. Project-agnostic — everything repo-specific lives in config.json.
---

Arguments: $ARGUMENTS

Run **one cycle** of autonomous ticket work against this repo's Lunicle project.
Work fully autonomously — never ask the user for input. The person who armed this
may be asleep. Make reasonable assumptions, record them, and keep going.

`/watch-ai-dev` arms this on a timer. This skill is a single cycle; it does not
loop and does not schedule itself.

## 0. Load the configuration

Read `config.json` and `repos.md` from this skill's own directory. `config.json`
is the only per-repo file — every path, project name, column name and build
command below comes from it. Never hardcode any of them into your reasoning.

`$ARGUMENTS` may contain:

- `--max <n>` — override `maxConcurrent` for this cycle.
- One or more issue keys (`LNL-190 LNL-191`) — restrict the cycle to those tickets
  *if they are in the ready column*. A key that is not in that column is skipped
  with a note; this skill never pulls work that has not been marked ready.
- Anything else is ignored.

## 1. Snapshot the ready column

`list_projects` → find the project named `config.project` → `get_board` with that
id and `status: "<config.statuses.ready>"`.

Two failure modes to expect, because the board being read is a **deployed** server
that may be older than this checkout:

- **The `status` parameter is not supported yet.** An older server ignores the
  unknown argument and returns the whole board. Detect this by checking whether
  the `issues` array contains anything outside the ready column, and filter
  locally if so.
- **The response is too large for one tool result.** The tool then spills to a
  file and returns its path instead of the content. Do not retry the call — read
  and parse that file.

From the result, take **every** issue whose `status` is `config.statuses.ready`.

**This snapshot is frozen for the whole cycle.** Tickets that land in the column
while you are working belong to the *next* cycle. Never re-query it mid-cycle.

**Order the snapshot by priority**, using the board's own `priorities` array as the
ranking (index 0 is the most urgent — currently `Very high` → `Very low`). Do not
hardcode priority names; read the order from the board. Break ties by ascending
issue id, so older tickets go first. That order is the claim order and the
dispatch order, and it does not change for the rest of the cycle.

If the column is empty: print `Idle cycle — nothing in "<ready column>".`, send no
e-mail, and stop.

## 2. Claim every ticket, immediately

Before fetching detail, before creating a single worktree, walk the ordered
snapshot and for each ticket:

1. `move_issue(issue_id, status: "<config.statuses.claimed>", agent_name: "Claude Code")`
2. `add_comment(issue_id, agent_name: "Claude Code", body: …)`:

```
**Claude Code** (an AI coding agent) picked this up via the `/ai-dev` automation. A
subagent has been assigned and is starting work now on its own branch.

I'll comment again with a summary and a pull request link when it's done, or with
what I'm stuck on if I can't finish it.

🤖 Posted by [Claude Code](https://claude.com/claude-code) acting autonomously.
```

Claiming first is the point of the design: it is what stops the next cycle — or a
human glancing at the board — from picking up work that is already in flight.

## 3. Build a self-contained brief per ticket

Subagents **must not touch the Lunicle MCP**, and may not even have it: the server
is registered per project directory in `~/.claude.json`, and a sibling worktree path
is not one of those directories. You are the only writer to the board — that is what
keeps two concurrent tickets from fighting over a column move. So everything a
subagent needs must be written down for it now.

For each ticket: `get_issue(issue_id)` and write a brief to
`<config.worktreeParent>/.ai-dev/<KEY>.md` — outside every repo, so it can never
pollute a diff. Use the template in §7, and give each ticket the port §5 assigns it.

## 4. Create the worktrees

Slug: 3–5 kebab-case words from the title, feature-descriptive (not `fix`, not
`update`). Branch and directory share the name `<key-lowercase>-<slug>`. If either
already exists, append `-2`, `-3` until unique.

```
git -C <config.repoRoot> fetch origin main
git -C <config.repoRoot> worktree add -b <slug-branch> <config.worktreeParent>/<slug-branch> origin/main
```

Always branch from freshly fetched `origin/main`, never from local `HEAD`.

If `config.toolkit` is not null, create a **paired toolkit worktree** with the same
branch name:

```
git -C <config.toolkit.repoRoot> fetch origin main
git -C <config.toolkit.repoRoot> worktree add -b <slug-branch> <config.toolkit.worktreeParent>/<slug-branch> origin/main
```

Every sibling worktree would otherwise resolve the one shared toolkit checkout, and
concurrent tickets would corrupt each other's edits. See `repos.md` for why, and for
the relative-path trap in `-P<config.toolkit.gradleProperty>`.

## 5. Dispatch, capped

Spawn one subagent per ticket via the Agent tool:

- `subagent_type`: `"general-purpose"`
- `run_in_background`: `true`
- `description`: `"<KEY>"`
- `prompt`: the brief from §7, in full

Launch in the §1 priority order, holding at most `maxConcurrent` in flight (3 by
default). Start the next as each one returns. The cap exists because concurrent
Gradle builds contend on the shared caches and RAM — it is not a correctness
constraint, so `--max 1` is always safe.

**Assign each ticket a port** before you write its brief: `config.basePort + i`,
where `i` is the ticket's zero-based position in the dispatch order. Ports are
assigned per ticket rather than per slot, so a ticket that outlives its neighbours
can never collide with the one that replaced it. It is substituted for `{port}` in
`config.runInstructions`.

Each subagent returns exactly four lines:

```
STATUS: done | blocked
PR: <url or ->
TOOLKIT_PR: <url or ->
SUMMARY: <2–5 sentences>
```

If a subagent dies or returns something unparseable, treat it as `blocked` with the
reason "the subagent did not report back".

## 6. Close the loop on each ticket

As each result arrives — do not wait for the whole batch:

**`done`** →

1. `add_comment(issue_id, agent_name: "Claude Code", body: …)` — a *short* summary
   (the detail lives in the PR), the PR link, and the toolkit PR link when there is
   one. Say plainly that nobody has reviewed it yet.
2. `move_issue(issue_id, status: "<config.statuses.review>", agent_name: "Claude Code")`

```
**Claude Code** (an AI coding agent) finished this via the `/ai-dev` automation and
opened a pull request: <PR url>

<2–3 sentences on what changed and any assumption a reviewer should check.>

<When a toolkit PR exists:>
Companion toolkit change: <toolkit PR url> — both need to merge together.

🤖 Posted by [Claude Code](https://claude.com/claude-code) acting autonomously. Nobody
has reviewed this yet.
```

**`blocked`** → comment, and **leave the ticket in `config.statuses.claimed`**. Do
not move it, do not open a PR.

```
**Claude Code** (an AI coding agent) worked on this via the `/ai-dev` automation but
stopped without opening a pull request.

**What I need from you:** <the concrete decision — quote the ambiguous phrase, name
the contradiction, or list the options to pick between. "The requirements are
unclear" is not enough.>

<What was done so far, if anything, and where the worktree is.>

🤖 Posted by [Claude Code](https://claude.com/claude-code) acting autonomously. The
ticket stays in <claimed column> until this is resolved.
```

## 7. The subagent brief

Write this to `<config.worktreeParent>/.ai-dev/<KEY>.md` and pass it as the
subagent's entire prompt. Substitute every `<…>`. It must stand alone: the subagent
has none of your context and no MCP.

```
You are implementing exactly one ticket from the <config.project> issue tracker.
Work fully autonomously — never ask for input. Where the ticket is ambiguous, pick
the most sensible option and record the assumption in the pull request rather than
stopping.

# Ticket <KEY>: <title>
Filed by: <author>
Tracker link: <config.issueUrl with {id} substituted>

## Description
<the full description, verbatim>

## Comments
<every comment, verbatim, oldest first, each with its author. "None." if there are none.>

# Where you work

- Your worktree: <config.worktreeParent>/<slug-branch> — cd there first. The branch
  <slug-branch> is already created from origin/main.
- Your toolkit worktree: <config.toolkit.worktreeParent>/<slug-branch>
- NEVER touch <config.repoRoot> or <config.toolkit.repoRoot>. Other tickets may be
  running at the same time; those shared checkouts are not yours.

# The toolkit

<paste repos.md here>

Append -P<config.toolkit.gradleProperty>=<config.toolkit.relativeFromWorktree, {slug}
substituted> to EVERY Gradle invocation. It must stay relative — an absolute path
silently resolves to nothing and your toolkit edits vanish from a green build.

**When `config.toolkit` is null** — this repo *is* the toolkit, or has no such
dependency — drop the "Your toolkit worktree" bullet, drop this whole "The toolkit"
section except `repos.md`'s **Checkouts** table, and drop the `-P…` rule from every
instruction below. There is no companion PR: the ticket's own PR is the toolkit
change. Say so rather than leaving a subagent to wonder which repo to edit.

# Scope

Implement the whole ticket. Do not split it into "part 1" or descope because the
change feels large — a large refactor is the work, not a reason to ship a slice. If
the full scope genuinely will not fit one reviewable PR, that is a blocker.

Follow CLAUDE.md if this repo has one — its documentation standards are hard
requirements, not suggestions.

# Verification

1. `<config.build> -P<toolkit property>=<relative path>` is MANDATORY. If it fails,
   fix it and retry. Never open a PR on a broken build.
2. Verify at runtime when the change is user-visible, following this project's own
   instructions below. Other tickets may be running at the same time, so the
   isolation they describe is not optional.

<config.runInstructions, joined with newlines, verbatim — with {port} replaced by
your assigned port and {key} by <KEY>>

3. Anything you could NOT verify goes in the PR's Verification section as an
   explicit gap. Do not dress a compile-only check up as success.

# Shipping

Commit in logical chunks, then:

    git push -u origin HEAD
    gh pr create --repo <config.github> --title "<short title>" --body "..."

The PR must be full, not draft. Link the ticket by URL — <tracker link> — and do
NOT write "Closes #<n>", which would target a GitHub issue that does not exist.

If you changed the toolkit, do the same in its worktree: commit, push, and open a
PR against <config.toolkit.github>. Cross-link the two PRs in both bodies. Do not
bump any version. Do not file a ticket in the toolkit's own project.

PR body structure — a reviewer who has not seen the ticket must be able to follow
it. Write prose where prose belongs:

    ## Summary — what changed and why, in user-facing terms (2–4 sentences).
    ## Background — the problem, paraphrased from the ticket. If it was ambiguous,
       say so and state your interpretation.
    ## Approach — how it works end to end; key classes/functions added or changed.
    ## Decisions & reasoning — a subsection per non-obvious choice: what you picked,
       the alternatives, why, and the trade-off accepted. This is the most important
       section. If it is empty you either glossed over real choices or the task was
       trivial.
    ## Assumptions — anything the reviewer should confirm. "None." if none.
    ## Alternatives considered and rejected — one line each. Omit if genuinely none.
    ## Verification — commands run, flows exercised, edge cases poked, and every gap
       stated honestly.
    ## Files of note — which changes are load-bearing, which are mechanical.
    ## Follow-ups — anything intentionally out of scope. Omit if none.

    🤖 Generated with [Claude Code](https://claude.com/claude-code)

# If you are truly blocked

A true blocker is contradictory requirements, or a decision only the owner can make
where every option is genuinely unsafe. Ambiguity you can resolve with a sensible
default is NOT a blocker — decide, note it, continue.

When truly blocked: do not commit, do not push, do not open a PR, do not ship a
partial slice. Leave the worktree as it is and report back immediately.

# What you return

Your final message must be exactly these four lines and nothing else:

STATUS: done | blocked
PR: <url, or - if none>
TOOLKIT_PR: <url, or - if none>
SUMMARY: <2–5 sentences. If blocked, state exactly what decision is needed.>
```

## 8. E-mail one report

After the last ticket resolves, send **one** `send_email` for the whole cycle —
never one per ticket. `send_email` has no recipient parameter: it reaches the
account whose token this MCP connection holds, which is the person who armed the
automation.

Subject: `<n> ticket(s) worked — <d> done, <b> blocked`. Body is plain text (no
markdown), one short paragraph per ticket: key, title, outcome, and the PR URL or
the decision needed. Skip the e-mail entirely on an idle cycle.

## 9. Report, and do not clean up

Print one line per ticket, in dispatch order:

```
Cycle — <n> ticket(s): <d> done, <b> blocked.
  • <KEY> — done: <one line> → <PR url>
  • <KEY> — blocked: <what's needed>
```

Leave every app worktree and branch in place; the owner wants to revisit the work.

Remove a paired toolkit worktree only if it is **untouched** — clutter, not work.
Untouched means both of these, and checking only the first is a trap:

```
git -C <path> status --porcelain          # empty: nothing uncommitted
git -C <path> log --oneline origin/main..HEAD   # empty: nothing committed either
```

`status --porcelain` alone is empty *right after a commit*, so on its own it deletes
the toolkit worktrees where real work happened and keeps the ones left in a mess —
exactly backwards. The commits are pushed by then so nothing is lost, but the owner
would come back to an app worktree whose paired toolkit worktree had vanished, for
precisely the tickets where the toolkit side mattered most.

## Guard rails

- Never work a ticket that was not in the ready column at snapshot time.
- Never move a ticket to `config.statuses.review` without a PR URL.
- Never move a blocked ticket out of `config.statuses.claimed`.
- Never commit or push in `config.repoRoot` or `config.toolkit.repoRoot`.
- One `send_email` per cycle, maximum.
- A failing ticket must never stop the others. Record it and carry on.
