# Brief: implement a ticket

Filled in and passed as a subagent's **entire prompt** by `/ai-dev` §5. Substitute
every `<…>`. It must stand alone — the subagent has none of the main agent's
context and no MCP.

Used for a ticket with no prior pull request. A ticket that already has one takes
`brief-rework.md` instead.

---

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

<paste github.md here>

Commit in logical chunks, then write the body to
<config.worktreeParent>/.ai-dev/<KEY>-pr.md and:

    git push -u origin HEAD
    GH_TOKEN="$(~/.config/ai-dev/gh-app-token.sh)" \
        gh pr create --repo <config.github> --title "<short title>" \
            --body-file <config.worktreeParent>/.ai-dev/<KEY>-pr.md

The PR must be full, not draft. Do NOT write "Closes #<n>": that targets a GitHub
issue which does not exist, since the tickets live in Lunicle.

If you changed the toolkit, do the same in its worktree: commit, push and open a
PR against <config.toolkit.github> — including the `GH_TOKEN` prefix, which covers
every repo the app is installed on. Cross-link the two PRs in both bodies. Do not
bump any version. Do not file a ticket in the toolkit's own project.

PR body structure — a reviewer who has not seen the ticket must be able to follow
it. Write prose where prose belongs, one paragraph per line. **The banner and the
ticket line come first, before the first heading**, and are not optional:

    > [!NOTE]
    > **Automatically generated.** Claude Code opened this pull request working autonomously from <KEY> via the `/ai-dev` automation. It is unreviewed, and the assumptions listed below have not been confirmed by a human.

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
