---
description: One tick of repo babysitting, run in an isolated subagent context so `/loop /babysit-repo` doesn't accumulate per-tick transcripts. Processes every actionable owner comment on any open PR, every unreviewed PR, and every `ai-dev`-labelled issue found at the start of the tick — each one in its own isolated sub-subagent.
---

Arguments: $ARGUMENTS

## Wrapper behaviour

Thin delegator: each invocation spawns one general-purpose orchestrator subagent (operational brief below). The orchestrator does discovery once and spawns one nested general-purpose subagent per actionable candidate. The parent session only ever sees the orchestrator's aggregated summary, so loop transcripts stay flat and no context bleeds between ticks or between actions. Each tick drains *every* candidate in priority order (all follow-ups → all reviews → all issues) rather than one per tick, so backlog burndown isn't paced by the loop interval.

## How to invoke the orchestrator

Make exactly one Agent tool call:

- `subagent_type`: `"general-purpose"`
- `description`: `"Babysit tick"`
- `prompt`: the entire block labelled **Orchestrator prompt** below,
  with every literal `$ARGUMENTS` replaced by the user's arguments
  string (empty string if no arguments were given). Pass the block
  verbatim otherwise — do not paraphrase, summarise, or restructure it.

Do **not** run any `gh`, `Read`, `Bash`, or `Skill` calls yourself
before or after the orchestrator call. Discovery, dispatch, and
aggregation are the orchestrator's job, not the parent's. The
parent's only responsibilities are:

1. Substitute `$ARGUMENTS` into the prompt.
2. Call Agent once with the substituted prompt.
3. When the orchestrator returns, print its summary verbatim (no
   prefix, no wrapping commentary, no re-explanation). That summary
   is the tick's entire output.

## Orchestrator prompt

```
You are the orchestrator subagent for one tick of the `/babysit-repo`
skill. You have no transcript from the parent session — that is
deliberate. Treat this prompt as your complete brief.

Arguments forwarded from the user: $ARGUMENTS

Repo: `soderbjorn/termtastic`. Work autonomously — do not ask for
confirmation. Your job is to discover every actionable candidate across
three tracks, then drive each one to completion by spawning a nested
general-purpose subagent per action via the Agent tool. Each nested
subagent runs one sub-skill (`/pick-followup`, `/pick-review`, or
`/pick-issue`) on one specific target and returns a short summary; you
aggregate those summaries into your final message to the parent.

### 1. Parse arguments

`$ARGUMENTS` controls which tracks are eligible this tick. Recognised
flags (case-insensitive, order-independent):

- `--followups-only` / `followups only` → consider PR follow-ups only.
- `--reviews-only` / `reviews only` → consider PR reviews only.
- `--issues-only` / `issues only` → consider issue implementation only.
- `--no-followups` → skip the follow-up track but keep issues and
  reviews eligible.
- `--label <value>` → override the issue-track label filter (default
  `ai-dev`). Example: `--label bug`.
- `--any-label` → disable the issue-track label filter entirely.

Anything else in `$ARGUMENTS` is ignored at this layer — the delegated
sub-skills can interpret target-specific hints (PR number, semantic
match) if the format matches, but because you always pass an explicit
target PR/issue number to each sub-skill, those hints will typically
be redundant here. Pass `$ARGUMENTS` through verbatim anyway so that
sub-skill-level flags like `--label` / `--any-label` still reach
`/pick-issue`.

If flags conflict (e.g. `--reviews-only --issues-only`, or any
`--*-only` combined with another `--*-only`), stop immediately and
return a one-sentence error summary; do not guess.

Default when `$ARGUMENTS` is empty: all three tracks eligible, `ai-dev`
label filter on the issue track.

### 2. Discover every candidate (one pass, parallel queries)

Run the enabled `gh` queries in the same tool-call block so discovery
doesn't serialise multiple round-trips:

```
gh pr list --state open --repo soderbjorn/termtastic --json number,title,body,author,isDraft,latestReviews,comments,createdAt,headRefName --limit 200
gh issue list --state open --repo soderbjorn/termtastic --label <label> --json number,title,assignees,createdAt --limit 200
```

Skip the calls that the flags have disabled. The PR query feeds both
the review track and the follow-up track — only one round-trip is
needed. For each open PR in the result, also fetch `gh pr view <N>
--json comments,commits` in parallel (one call per open PR, all in
the same tool-call block as each other) to surface the latest
`committedDate` on the head branch and the comment timestamps. Those
lookups feed both the re-review eligibility check and the follow-up
actionability approximation.

Classify every candidate into one of three lists — do not de-dupe
across lists; the same PR can legitimately land on both the review
and follow-up lists (though the dispatch order below means only the
follow-up action fires this tick for that PR).

**PR-review candidates** (same rules as `/pick-review`):
- open and not a draft
- `latestReviews` empty
- **Either** no existing comment whose body contains the pick-review
  attribution footer (`posted by [Claude Code]` in a review context),
  **or** the most recent such comment is older than the latest commit
  on the PR's head branch — i.e. new code has landed since Claude
  last reviewed (typically from a `/pick-followup` push), so the PR
  is eligible for a re-review. The re-review's own comment will be
  newer than the head commit, so the rule self-terminates.

**Issue candidates** (same rules as `/pick-issue`, plus a linked-PR
exclusion specific to babysit):
- open
- not assigned to someone other than the current user (`@me`-assigned
  is still eligible)
- **no open PR already linked to the issue via a closing keyword.**
  Parse each open PR's `body` for GitHub's closing keywords —
  case-insensitive `closes #N`, `close #N`, `closed #N`, `fixes #N`,
  `fix #N`, `fixed #N`, `resolves #N`, `resolve #N`, `resolved #N` —
  and subtract those issue numbers from the candidate set.

**Follow-up candidates** (same rules as `/pick-followup`):
- PR is open (draft allowed). PR authorship does **not** matter —
  an actionable owner comment on any open PR is a follow-up trigger,
  whether Claude opened the PR or soderbjorn did.
- has at least one comment from `soderbjorn` that is newer than the
  last commit on the PR's head branch, is actionable
  (imperative/request phrasing — use judgement), and has not already
  been answered by a Claude reply containing the canonical marker
  `addressed in commit <sha>`.

The review status of the PR is irrelevant to the follow-up track —
if an actionable comment is present, the follow-up fires regardless
of whether the PR has been reviewed (by Claude or anyone else).

Count-only heuristics are fine at discovery — the delegated sub-skill
will confirm actionability when it runs. A cheap follow-up
approximation: count PRs where `soderbjorn` posted after the last
commit.

**If a PR is on both the follow-up and review lists, keep it on the
follow-up list only for this tick.** The follow-up push will move
the PR forward and typically land a new commit, at which point the
next tick's discovery will surface it for re-review naturally.
Double-dispatching in the same tick would review the PR before the
follow-up commit lands and waste work.

### 3. Dispatch every candidate, prioritised

Process in this order: **all follow-ups, then all reviews, then all issues**, each list oldest-first (which `gh ... list` gives). Owner feedback beats fresh reviews; reviews beat opening new PRs.

For each candidate, spawn a nested `general-purpose` subagent via the Agent tool, with `description` naming the track and target (e.g. `"Follow-up on PR #13"`) and this `prompt`:

```
You are a nested subagent. Invoke the `<skill>` skill via the Skill tool with arguments `<target> <passthrough>`. Return a single sentence summarising what happened (include the PR URL if produced; note success or failure). Do not run additional tools after the skill returns.
```

- Follow-up: `<skill>` = `pick-followup`, `<target>` = PR number.
- Review: `<skill>` = `pick-review`, `<target>` = PR number.
- Issue: `<skill>` = `pick-issue`, `<target>` = issue number.

`<passthrough>` is `$ARGUMENTS` with any `--*-only` / `--no-followups` flags stripped (the track is already chosen). For the issue track, preserve `--label` / `--any-label`. Passing an explicit target avoids re-discovery.

Spawn nested subagents **sequentially** — sub-skills mutate the repo and parallel runs would race on the working tree and GitHub API. Record each one's one-sentence summary for the final aggregate. **Do not stop on failure**: record and continue; the next tick retries if still a candidate.

### 4. Respect tick boundaries

- Do not leave long-running background processes when exiting.
- Do not open a worktree yourself; each sub-skill manages its own.
  (`/pick-review` cleans up its own; `/pick-issue` and
  `/pick-followup` deliberately keep theirs, which is fine.)
- Do not re-query the candidate lists mid-tick. Snapshot at discovery;
  process the snapshot. A follow-up pushed this tick may make the
  same PR re-review-eligible — let the next tick see that.
- Do not chain across categories in a way that violates priority. If
  the follow-up list has items, drain it fully before starting the
  review list; drain the review list before the issue list.

### 5. Return value

Your final message to the parent must be a concise multi-line
summary, and nothing else. No headers, no prose commentary, no
re-explanation of the work — the parent prints it verbatim.

Format:

```
Babysit tick — <F> follow-up(s), <R> review(s), <I> issue(s) processed.
  • Follow-up PR #<n> — <one-line summary from nested subagent>
  • Review PR #<n> — <one-line summary from nested subagent>
  • Issue #<n> → PR #<m> — <one-line summary from nested subagent>
  ...
```

Include one bullet per action actually dispatched, in the order they
ran (follow-ups first, then reviews, then issues). If a nested
subagent reported failure, preserve that in the bullet ("… failed —
<reason>; loop will retry."). Each bullet should include a URL when
one was produced.

If nothing was dispatched at all:

```
Idle tick — 0 follow-ups, 0 PRs awaiting review, 0 open `<label>` issues.
```

(Substitute the effective label name — `ai-dev` by default, or the
value from `--label`, or `any` when `--any-label` is in effect.)

If arguments conflicted:

```
Conflicting flags in arguments: <detail>; no action taken.
```
```

## Usage

- `/loop 15m /babysit-repo` (or 30–60m on a busy repo) — fixed cadence.
- `/loop /babysit-repo` — self-paced; model picks 10–15m when busy, 45–60m when idle.
- `/loop /babysit-repo --reviews-only` / `--followups-only` — narrow watchdogs.

Stopping is the user's responsibility (Ctrl-C or explicit stop); this skill does not self-terminate.
