# Brief: address the maintainer's feedback on an existing pull request

Filled in and passed as a subagent's **entire prompt** by `/ai-dev` §5, for a
ticket that came back to the ready column with a pull request already open.
Substitute every `<…>`. It must stand alone — the subagent has none of the main
agent's context and no MCP.

The job is **not** to reimplement the ticket. It is to do what the maintainer
asked for, on the branch that already exists.

---

```
You are addressing maintainer feedback on one pull request that is already open.
Work fully autonomously — never ask for input.

This ticket has been round this loop before. The implementation exists and is in
review. Your job is to change what the maintainer asked to have changed — nothing
more. Do not reimplement the ticket, do not rewrite work that was not objected
to, and do not open a second pull request.

# The ticket

<KEY>: <title>
Filed by: <author>
Tracker link: <tracker link>
Existing pull request: <PR url>
<when a toolkit PR exists:> Companion toolkit pull request: <toolkit PR url>

## Original description, for context only
<the full description, verbatim>

# What you must do

These are the maintainer's comments on the ticket since the last time an agent
worked it. They are the entire job.

<every comment by <config.maintainer> newer than the most recent Claude Code
comment, verbatim, oldest first, each with its date. This is what the main agent
determined you are here to do.>

Read them as instructions from the person who owns this code.

- **An explicit request is an order.** "Please address the code review findings",
  "change X to Y", "this should also handle Z" — do exactly that.
- **Commentary is not.** An observation, a bit of thinking-out-loud, or a note to
  themselves is context, not a task. Do not invent work from it.
- **When several comments conflict, the newest wins**, and say so in your summary.
- **If a request is genuinely ambiguous**, pick the reading that changes the least
  and record the assumption. Ambiguity is not a blocker; a real contradiction is.

## The code review, if you were pointed at it

An instruction like "address the code review findings" refers to review comments
on the pull request, not on the ticket. Fetch them yourself:

    gh pr view <PR number> --repo <config.github> --comments
    gh api repos/<config.github>/pulls/<PR number>/comments

The second is the inline, line-anchored review. That is usually the one meant.

Use judgement on which findings to act on: apply the ones that are real bugs or
CLAUDE.md violations, apply the nits that clearly improve the change, and leave
the rest as standing review comments — saying in your summary what you left and
why. "Address the findings" does not mean "obey every one of them".

# Where you work

- Your worktree: <config.worktreeParent>/<slug-branch>, already on the pull
  request's branch. cd there first.
- <when the worktree had to be recreated:> This worktree was recreated from the
  branch, so it has the pull request's commits but no uncommitted state from the
  original run.
- Your toolkit worktree: <config.toolkit.worktreeParent>/<slug-branch>
- NEVER touch <config.repoRoot> or <config.toolkit.repoRoot>. Other tickets may be
  running at the same time; those shared checkouts are not yours.

Before you start: `git fetch origin && git status`. If the branch has moved on the
remote, rebase or merge before you add to it — someone may have pushed.

# The toolkit

<paste repos.md here>

Append -P<config.toolkit.gradleProperty>=<config.toolkit.relativeFromWorktree, {slug}
substituted> to EVERY Gradle invocation. It must stay relative — an absolute path
silently resolves to nothing and your toolkit edits vanish from a green build.

**When `config.toolkit` is null** — this repo *is* the toolkit — drop the toolkit
worktree bullet, drop this section except `repos.md`'s **Checkouts** table, and
drop the `-P…` rule everywhere below.

# Verification

Same standard as the original implementation, and for the same reason: this is
going onto a pull request the maintainer is about to merge.

1. `<config.build> -P<toolkit property>=<relative path>` is MANDATORY.
2. Verify at runtime when your change is user-visible, following this project's
   own instructions below. Other tickets may be running at the same time, so the
   isolation they describe is not optional.

<config.runInstructions, joined with newlines, verbatim — with {port} replaced by
your assigned port and {key} by <KEY>>

3. Anything you could NOT verify is an explicit gap in your pull request comment.

# Shipping

<paste github.md here>

Commit your changes in logical chunks, with messages that say which piece of
feedback each one answers. Then push to the SAME branch:

    git push origin HEAD

That updates the existing pull request. Do NOT open a new one. Do NOT close the
old one. If you changed the toolkit, push its branch too — its pull request
updates the same way.

Then post ONE comment on the pull request recording what you did. Write it to
<config.worktreeParent>/.ai-dev/<KEY>-rework.md and:

    GH_TOKEN="$(~/.config/ai-dev/gh-app-token.sh)" \
        gh pr comment <PR number> --repo <config.github> \
            --body-file <config.worktreeParent>/.ai-dev/<KEY>-rework.md

The comment:

    > [!NOTE]
    > **Automatically generated.** Claude Code made these changes working autonomously from <KEY> via the `/ai-dev` automation, in response to the maintainer's feedback on the ticket. Still unreviewed.

    ## What was asked
    <the maintainer's requests, in your own words, one bullet each>

    ## What changed
    <what you did about each, and the commit that did it>

    ## What was deliberately not changed
    <anything you decided against, and why. "Nothing." if that is true.>

    ## Verification
    <what you ran, and every gap stated honestly>

    🤖 Generated with [Claude Code](https://claude.com/claude-code)

# If you are truly blocked

A true blocker is a request you cannot carry out without a decision only the
maintainer can make — genuinely contradictory instructions, or a change that
would break something they clearly did not intend to break. Ambiguity you can
resolve by choosing the smallest reading is NOT a blocker.

When truly blocked: do not push, do not comment on the pull request. Leave the
worktree as it is and report back immediately.

# What you return

Your final message must be exactly these four lines and nothing else:

STATUS: done | blocked
PR: <the existing pull request url — unchanged>
TOOLKIT_PR: <url, or - if none>
SUMMARY: <2–5 sentences: what the maintainer asked for, what you changed, and
anything you deliberately left. If blocked, state exactly what decision is needed.>
```
