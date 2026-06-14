---
description: Pick a GitHub issue (oldest-first or semantic match) and work on it end-to-end.
---

Arguments: $ARGUMENTS

Pick one open GitHub issue from `soderbjorn/termtastic`, implement it, push a branch, and open a non-draft PR. Work autonomously — do not ask for confirmation at any step.

## 1. Fetch candidates

Default filter is `--label ai-dev`. Only override if `$ARGUMENTS` explicitly says so (e.g. "any label", "label: bug", "no label filter"). A plain semantic description like "the issue about renaming color schemes" is **not** a label override — keep the `ai-dev` filter.

```
gh issue list --state open --label ai-dev --json number,title,body,labels,assignees,author,createdAt --limit 200
```

Exclude issues already assigned to someone else. Issues assigned to the current user (`@me`) are still eligible (likely a prior run).

**Exclude issues not authored by `soderbjorn`** (the repo owner). This gate prevents Claude from autonomously implementing issues filed by outside contributors — the owner must have opened (or re-opened) the ticket. Check `author.login == "soderbjorn"` on each candidate.

If zero candidates remain, stop and report that to the user. Do not proceed.

## 2. Pick ONE issue

- `$ARGUMENTS` empty (or only a label override) → pick the **oldest-created** candidate (smallest `createdAt`). Do not randomise — runs should be reproducible, and the user steers the backlog by keeping older items ready to go.
- Otherwise → read title + body of each candidate and pick the best **semantic** match. Use judgment, not string matching. "renaming color schemes" should match an issue titled "Rename 'theme' to 'color scheme' in settings UI" even with zero word overlap.
- If two candidates are genuinely tied (identical `createdAt` or equally strong semantic match), pick the lower-numbered one. Do not ask the user.

Tell the user which issue you picked (number + title) before continuing.

## 3. Claim, branch, worktree

Always create a **new feature branch** in a **separate worktree** at a sibling path to the main checkout. Do not work on `main`, and do not reuse the current checkout.

**The feature branch must always be created from `main`** — specifically from the freshly fetched `origin/main`, never from whatever branch `HEAD` currently points to, never from a stale local `main`.

```
gh issue edit <N> --add-assignee @me
git fetch origin main
git worktree add -b issue-<N>-<slug> ../issue-<N>-<slug> origin/main
cd ../issue-<N>-<slug>
```

- `<slug>` is 3–5 kebab-case words drawn from the issue title (feature-descriptive, not generic like "fix" or "update").
- The worktree path must be a sibling of the current repo directory (e.g. if the main checkout is `…/termtastic/main`, the worktree lives at `…/termtastic/issue-<N>-<slug>`).
- If that path or branch name already exists from a prior run, append `-2`, `-3`, etc. until unique.
- All remaining steps run from inside the new worktree.

## 4. Scope — implement the whole issue, or stop

Implement everything the issue asks for. Do not unilaterally split an issue into "part 1 / part 2", invent a follow-up series, or descope because the refactor feels large — "large refactor" is the work, not an excuse to ship a slice. If the full scope genuinely doesn't fit in one reviewable PR, that's a blocker (see below) — ask the owner before splitting, don't decide yourself.

**Stop before opening a PR** if you hit a true blocker:

- The issue is ambiguous or internally contradictory in a way the code can't resolve.
- The work needs a design decision the owner hasn't made (naming, API surface, UX trade-off) and your best guess is not safe.
- The full scope is larger than one reviewable PR and you need the owner to sanction a split (and dictate how to split it).

When blocked, post a question on the issue instead of opening a PR:

```
gh issue comment <N> --body "$(cat <<'EOF'
**Claude Code** (an AI coding agent) picked up this issue via the `/pick-issue` skill but stopped before opening a PR because of a blocker.

<be concrete: quote the ambiguous phrase, cite the contradiction, or list the design options the owner should pick between. "The API is unclear" is not enough.>

🤖 This comment was posted by [Claude Code](https://claude.com/claude-code) acting autonomously. No PR was opened; I'll pick this up again once the blocker is resolved.
EOF
)"
```

Then stop — do not push a branch, do not open a draft PR, do not ship a "part 1". Leave the worktree in place if you created one. Report the blocker-comment URL back to the user.

## 5. Implement

Follow `CLAUDE.md` — its documentation standards (file-level block comment for new files; KDoc/JSDoc on new public classes, functions, and significant properties with purpose, caller context, `@param`, `@return`, `@see`; update existing doc blocks when behaviour or signatures change; preserve inline comments and only add new ones where the *why* is non-obvious) are hard requirements, not suggestions.

For UI changes, build and verify in a browser/app session. If you can't exercise a code path at runtime, call that out in the PR's **Verification** section as an explicit gap — do not dress a compile-only check up as success.

## 6. Build before PR (mandatory)

Run the project's standard build (`./gradlew build` or equivalent) from the worktree before opening a PR. If it fails, fix it and retry — do not open the PR on a broken build. If the toolchain is unavailable in this environment, state that as a Verification gap rather than silently skipping it. A passing build is not a proof of correctness; it only rules out compile errors.

## 7. Commit, push, PR

Commit in logical chunks. Then:

```
git push -u origin HEAD
gh pr create --title "<short title>" --body "$(cat <<'EOF'
<PR body — see structure below>
EOF
)"
```

PR must be **full, not draft**. Report the PR URL and the worktree path at the end.

**Comment on the issue linking to the PR.** After the PR is created, post a comment on issue #N that links to the PR and clearly identifies the commenter as Claude Code (not the human repo owner).

```
gh issue comment <N> --body "$(cat <<'EOF'
**Claude Code** (an AI coding agent) picked up this issue via the `/pick-issue` skill and opened #<PR> with a proposed fix.

<one or two sentences summarising what the PR does>

🤖 This comment was posted by [Claude Code](https://claude.com/claude-code) acting autonomously — the repo owner has not yet reviewed the PR.
EOF
)"
```

The comment must:
- State "Claude Code" (or "an AI coding agent") explicitly in the opening line, so readers don't mistake it for a human author.
- Link to the PR by number (`#<PR>`), which GitHub auto-expands.
- Include the Claude Code attribution footer verbatim, with the [Claude Code](https://claude.com/claude-code) link intact.
- Not impersonate the repo owner or make claims about reviewer approval.

**Do not clean up after the PR.** Leave the worktree and the local feature branch in place — do not run `git worktree remove`, `git branch -d`, or anything equivalent. The user wants them kept so they can revisit the work.

### PR body structure

The PR body must be **comprehensive** — a reviewer who has not seen the issue should be able to understand what changed, why you made the choices you did, and what to scrutinize. Use the following structure, and write prose where prose is called for. Do not pad with bullet points where real explanation belongs.

```
Closes #<N>

## Summary
A short paragraph (2–4 sentences) describing what this PR changes and why. Name the user-facing behavior, not just the files.

## Background
What problem is this solving? Quote or paraphrase the relevant detail from the issue. If the issue was ambiguous and you had to interpret intent, say so here and state your interpretation.

## Approach
Describe the solution in prose. How does it work end-to-end? What are the key classes/functions introduced or changed? Which existing code did you lean on or extend?

## Decisions & reasoning
For every non-obvious design decision you made, write a short subsection:

### <decision>
What you chose, what the alternatives were, and **why** you picked this one. Include trade-offs accepted. This is the most important section — be honest about the judgement calls.

(Repeat per decision. Typical candidates: naming, placement of new code, choice of data structure, server-vs-client split, sync-vs-async, backward compatibility posture, error handling strategy, UI affordance choices, migration approach.)

## Assumptions
Anything you assumed that the reviewer should confirm or correct. If you assumed nothing, say "None."

## Alternatives considered and rejected
Approaches you evaluated but did not take, with a one-line reason each. Omit the section if there were genuinely none.

## Verification
What you did to convince yourself this works. Be specific: commands run, UI flows exercised, edge cases poked. If you could not verify something (e.g. couldn't launch the app, couldn't reach an external service), state that here as an explicit gap — do not paper over it.

## Files of note
Brief orientation for the reviewer: which files are the load-bearing changes, which are mechanical, which are new. Skip files the diff already makes obvious.

## Follow-ups
Anything intentionally out of scope, known limitations, or suggested next steps. Omit if none.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

Rules for the PR body:

- Use `Closes #<N>` **only if this PR fully resolves the issue**. If the owner has explicitly sanctioned a phased approach (in the issue or a comment), use `Refs #<N>` or `Part of #<N>` instead so the issue stays open. Never auto-close an issue your PR only partially addresses.
- Do not invent "part 1 of N" framing in the title or body. If a split wasn't sanctioned by the owner, you shouldn't be here (see §4) — if it was, cite the comment that authorised it.
- Write as if the reviewer has not read the issue. Don't say "see the issue" — summarize the relevant parts.
- The "Decisions & reasoning" section is the whole point — if you find yourself with nothing to write there, you either glossed over real choices or the task was trivial. Err toward surfacing decisions, not hiding them.
- Do not invent decisions that didn't happen. If a section would be dishonest, say "None" and move on.
- No `$ARGUMENTS` or other template literals in the final body.
