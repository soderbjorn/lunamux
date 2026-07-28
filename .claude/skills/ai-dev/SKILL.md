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
- `--force` — run even though another cycle holds the lock. See §1; only ever
  meaningful when typed by a human, so a loop tick must never pass it.
- One or more issue keys (`LNL-190 LNL-191`) — restrict the cycle to those tickets
  *if they are in the ready column*. A key that is not in that column is skipped
  with a note; this skill never pulls work that has not been marked ready.
- Anything else is ignored.

## 1. Take the cycle lock

**Before the first board call.** A tick can fire while the previous cycle is still
working, and two overlapping cycles break things the frozen snapshot cannot
protect: both hand out ports from `config.basePort` upward and collide on every
one of them, both run up to `maxConcurrent` subagents so the cap silently doubles,
and a tick landing in the narrow window between §2's snapshot and the end of its
claim loop sees the same tickets twice — which does not fail, because §4 appends
`-2` to a taken worktree name, it just quietly works one ticket twice and opens
two pull requests for it.

The lock is `<config.worktreeParent>/.ai-dev/cycle.lock`. One per repo, so a
Lunicle sweep and a Lunula sweep are free to run at the same time.

```
mkdir -p <config.worktreeParent>/.ai-dev
```

If the file exists and is **younger than 6 hours**, a cycle is already running.
Print `Skipped — a cycle started <when> is still running.` and stop. Do not
snapshot, do not claim, do not touch the board at all. The loop will try again
next tick, which is the correct behaviour: there is nothing to catch up on,
because the running cycle already claimed everything that was ready.

**Unless `--force` was passed**, in which case run anyway — but *unlocked*, and
say so:

```
Forcing — a cycle started <when> is still running. This one runs unlocked, on
ports <base>–<base+n>, and will not touch that cycle's lock.
```

Running unlocked means three things, and all three matter:

- **Do not take the lock and do not overwrite it.** It belongs to the other
  cycle, which will delete it when it finishes. A forced cycle that stamped its
  own timestamp over it would extend the other cycle's apparent life, and one
  that deleted it at §10 would unlock a cycle still running.
- **Offset the ports by 20** (`config.basePort + 20 + i` rather than
  `config.basePort + i`), because colliding with the live cycle's ports is the
  thing that would actually break — both run scripts refuse a held port. Two
  *forced* cycles at once would collide again; don't do that.
- **Expect the ready column to be nearly empty.** The running cycle claimed
  everything that was ready when it started, so unless tickets have landed since,
  a forced cycle finds nothing and idles. That is usually the honest answer to
  "why is nothing happening" — the work is already in flight.

`--force` exists for a human who knows the other cycle is wedged or irrelevant.
It is never the right thing for a loop tick to pass.

If it exists and is **older than 6 hours**, treat it as stale — a cycle that died
before §10 could clean up — and say so in your final report, because a cycle that
died mid-flight probably left tickets sitting in `config.statuses.claimed` with
nobody working them.

Otherwise write it, with the current timestamp and one line naming this cycle.
Six hours is chosen to be far longer than any real cycle; a human who knows better
can always delete the file.

**If you took the lock you own it, and you must remove it before you exit — on
every path.** Idle cycle, conflicting arguments, an error partway through: all of
them remove it on the way out. A lock left behind by a cycle that simply finished
is worse than no lock at all, because it silently disables the automation for six
hours.

**If you did not take it, never touch it.** A forced cycle releases nothing. Carry
"do I own the lock?" through the whole cycle and check it at §10 — releasing a lock
you do not own is the one way this design fails open.

## 2. Snapshot the ready column

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

If the column is empty: release the lock if you own it, print
`Idle cycle — nothing in "<ready column>".`, send no e-mail, and stop.

## 3. Claim every ticket, immediately

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

## 4. Build a self-contained brief per ticket

Subagents **must not touch the Lunicle MCP**, and may not even have it: the server
is registered per project directory in `~/.claude.json`, and a sibling worktree path
is not one of those directories. You are the only writer to the board — that is what
keeps two concurrent tickets from fighting over a column move. So everything a
subagent needs must be written down for it now.

For each ticket: `get_issue(issue_id)` and write a brief to
`<config.worktreeParent>/.ai-dev/<KEY>.md` — outside every repo, so it can never
pollute a diff. Use the template in §8, and give each ticket the port §6 assigns it.

## 5. Create the worktrees

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

## 6. Dispatch, capped

Spawn one subagent per ticket via the Agent tool:

- `subagent_type`: `"general-purpose"`
- `run_in_background`: `true`
- `description`: `"<KEY>"`
- `prompt`: the brief from §8, in full

Launch in the §2 priority order, holding at most `maxConcurrent` in flight (3 by
default). Start the next as each one returns. The cap exists because concurrent
Gradle builds contend on the shared caches and RAM — it is not a correctness
constraint, so `--max 1` is always safe.

**Assign each ticket a port** before you write its brief: `config.basePort + i`,
where `i` is the ticket's zero-based position in the dispatch order — or
`config.basePort + 20 + i` if §1 said you are running unlocked. Ports are
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

## 7. Close the loop on each ticket

As each result arrives, close that ticket out **completely, then and there** —
comment, column, e-mail, all three. Do not wait for the whole batch, and do not
save the e-mail for the end of the cycle. A ticket that has landed is news the
moment it lands: the point of this automation is that the owner can wake up, read
one message per finished ticket, and act on it. Batching turns three separate
results into one digest that arrives only when the slowest ticket does.

Other tickets are still running while you do this. Finish one ticket's three calls
before starting the next one's, so a result can never be half-reported.

**`done`** →

1. `add_comment(issue_id, agent_name: "Claude Code", body: …)` — a *short* summary
   (the detail lives in the PR), the PR link, and the toolkit PR link when there is
   one. Say plainly that nobody has reviewed it yet.
2. `move_issue(issue_id, status: "<config.statuses.review>", agent_name: "Claude Code")`
3. `send_email(…)` — see §9.

```
**Claude Code** (an AI coding agent) finished this via the `/ai-dev` automation and
opened a pull request: <PR url>

<2–3 sentences on what changed and any assumption a reviewer should check.>

<When a toolkit PR exists:>
Companion toolkit change: <toolkit PR url> — both need to merge together.

🤖 Posted by [Claude Code](https://claude.com/claude-code) acting autonomously. Nobody
has reviewed this yet.
```

**`blocked`** → comment, e-mail (§9), and **leave the ticket in
`config.statuses.claimed`**. Do not move it, do not open a PR. A blocked ticket is
the *more* urgent e-mail of the two: it is the one waiting on a human.

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

## 8. The subagent brief

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
    gh label create ai-generated --repo <config.github> \
        --description "Opened by an AI coding agent, unreviewed" --color BFD4F2 || true
    gh pr create --repo <config.github> --title "<short title>" \
        --label ai-generated --body "..."

The PR must be full, not draft. Do NOT write "Closes #<n>": that targets a GitHub
issue which does not exist, since the tickets live in Lunicle.

**The label is not decoration.** `gh` authenticates as the repo owner's own
account, so GitHub shows these pull requests as authored by a human, with their
avatar and an Owner badge — in the list view there is otherwise nothing to say an
agent opened it. The label is the only signal at that level. `gh label create`
fails harmlessly once the label exists, which is why it is `|| true`; never let it
stop the PR. If `--label` itself is rejected, open the PR without it and say so in
your summary rather than dropping the PR.

If you changed the toolkit, do the same in its worktree: commit, push, label and
open a PR against <config.toolkit.github>. Cross-link the two PRs in both bodies.
Do not bump any version. Do not file a ticket in the toolkit's own project.

PR body structure — a reviewer who has not seen the ticket must be able to follow
it. Write prose where prose belongs. **The banner and the ticket line come first,
before the first heading**, and are not optional:

    > [!NOTE]
    > **Automatically generated.** Claude Code opened this pull request working
    > autonomously from <KEY> via the `/ai-dev` automation. It is unreviewed, and
    > the assumptions listed below have not been confirmed by a human.

    Ticket: <tracker link> (<KEY>)
    <when a toolkit PR exists:> Companion toolkit change: <url> — merge together.

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

## 9. E-mail, one per ticket, as it lands

Send the e-mail as the third call of §7, immediately after the comment and the
column move — **not** batched at the end of the cycle. One ticket, one message.

`send_email` has no recipient parameter: it reaches the account whose token this
MCP connection holds, which is the person who armed the automation.

Subject: `<KEY> <done | needs a decision> — <short title>`.

Body is **plain text**, not markdown — asterisks and backticks arrive as
themselves, so lay it out with blank lines instead. Keep it to a few lines; the
detail is in the PR and the ticket comment, and this is the message read on a
phone before getting up.

- `done` — what changed, in a sentence or two. The PR URL on its own line. The
  toolkit PR URL too when there is one. Any assumption worth checking.
- `blocked` — the decision needed, stated concretely, and where the work got to.

Send nothing on an idle cycle, and send nothing when the cycle merely starts —
a claimed ticket is not news, a resolved one is.

## 10. Release the lock, report, and do not clean up

**If you own the lock, delete `<config.worktreeParent>/.ai-dev/cycle.lock`
first**, before printing anything. It is the one piece of cleanup that is not
optional: leave it behind and the next six hours of ticks all skip, and the
automation looks like it simply stopped working.

If §1 said you are running unlocked — `--force` over a live cycle — leave the file
exactly where it is. It is not yours, and the cycle that owns it is still running.

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

- Never run without the lock unless `--force` said so, never exit still holding one
  you took, and never delete one you did not take.
- Never work a ticket that was not in the ready column at snapshot time.
- Never move a ticket to `config.statuses.review` without a PR URL.
- Never move a blocked ticket out of `config.statuses.claimed`.
- Never commit or push in `config.repoRoot` or `config.toolkit.repoRoot`.
- Exactly one `send_email` per *resolved* ticket, sent as it resolves. None for a
  ticket that is merely claimed, and none at all on an idle cycle.
- A failing ticket must never stop the others. Record it and carry on.
