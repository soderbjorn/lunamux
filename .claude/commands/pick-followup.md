---
description: Find an actionable repo-owner comment on any open PR, implement the requested change, push to the PR branch, and report back.
---

Arguments: $ARGUMENTS

Pick one open PR on `soderbjorn/termtastic` which has an **actionable, unaddressed** comment from the repo owner (`soderbjorn`). Implement the requested change, push it to the same PR branch, reply on the PR, and comment on the linked issue if one exists. Work autonomously — do not ask for confirmation at any step.

PR authorship does not matter — any open PR is in scope, whether Claude opened it or soderbjorn did. Review status also does not matter: the presence of an actionable owner comment is the only trigger. This skill is the "close the loop" counterpart to `/pick-issue` and `/pick-review`: when the owner has left feedback on any PR, this is how Claude acts on that feedback instead of leaving the PR to rot.

## 1. Fetch candidate PRs

```
gh pr list --state open --repo soderbjorn/termtastic \
  --json number,title,body,author,createdAt,headRefName,baseRefName,isDraft --limit 200
```

A PR is **in scope** for follow-up if it is open. Draft state is allowed — follow-ups on drafts are still useful. PR authorship is not a gate: any open PR counts, whether Claude opened it or soderbjorn did. The real filter is applied at the comment level in step 2 (actionable owner comment, newer than head, not yet addressed).

## 2. Scan each in-scope PR for actionable, unaddressed owner comments

For every in-scope PR, pull both the issue-comment stream and the review-comment stream, plus the head-branch commit history:

```
gh pr view <N> --json comments,reviews,reviewThreads,commits
gh api repos/soderbjorn/termtastic/pulls/<N>/comments
```

(The `comments` field in `gh pr view` is the "conversation" tab; `/pulls/<N>/comments` is the inline review comments tab. You need both.)

A comment is a **follow-up trigger** if:
- Author login is `soderbjorn` (the repo owner).
- Comment `createdAt` is newer than the last commit's `committedAt` on the PR's head branch — i.e. the comment landed after the most recent push, so the author could not have already addressed it implicitly.
- The comment is **actionable** — it contains a request or instruction. Use judgement, not keyword matching. Positive signals: imperative phrasing ("rename X to Y", "please change…", "can you add…", "we should…", "move this to…"), concrete specific asks, questions that imply a fix ("why is this not handling the empty case?"). Negative signals (not actionable): pure acknowledgement ("thanks!"), praise ("looks good"), open-ended musing with no ask, purely informational ("fyi the CI is flaky today"), or questions that are genuinely seeking information rather than implying a change.
- Claude has **not already addressed it**. The canonical marker is a later Claude Code reply comment whose body contains the exact phrase `addressed in commit <sha>` (see step 7 below — this is the footer format that future runs look for). If such a reply exists after the owner's comment, skip it.

Collect all qualifying comments across all PRs into a single list.

## 3. Pick ONE follow-up

- `$ARGUMENTS` empty → pick the **oldest qualifying comment** (smallest `createdAt`) across all PRs. Deterministic, matches the ordering policy of `/pick-issue` and `/pick-review`.
- `$ARGUMENTS` contains an explicit PR number (e.g. `42`, `#42`) → restrict to that PR; within it pick the oldest qualifying comment. If the PR has no qualifying comments, stop and report.
- `$ARGUMENTS` is a semantic description → pick the best semantic match across all qualifying comments. Use judgement.
- Ties → lower PR number wins; within one PR, earlier `createdAt` wins.

If zero qualifying comments exist across all in-scope PRs, stop and report that to the user. Do not proceed, do not touch any PR.

Report which PR and which comment were picked (PR number, PR title, a one-line excerpt of the comment, and the comment URL) before continuing.

## 4. Worktree on the PR branch

Always work in a **sibling worktree** at a sibling path to the main checkout. Do not work on `main`, and do not reuse the current checkout. Unlike `/pick-issue`, the branch already exists — you check it out, you don't create it.

```
git fetch origin <headRefName>
git worktree add ../pr-<N>-followup origin/<headRefName>
cd ../pr-<N>-followup
git checkout -B <headRefName> origin/<headRefName>
```

- The worktree path must be a sibling of the main checkout (e.g. `…/termtastic/pr-<N>-followup`).
- If that path already exists from a prior run, append `-2`, `-3`, etc. until unique.
- All remaining steps run from inside the new worktree.

If the PR branch is out of date with `main` in a way that blocks the requested change (merge conflicts, moved files, renamed symbols), do **not** silently rebase or merge. Stop and report — let the user decide whether to rebase manually.

## 5. Implement

Implement the change requested in the owner's comment. Follow `CLAUDE.md`'s documentation standards (file-level block comment on new files; KDoc/JSDoc on new public classes, functions, and significant properties with purpose, caller context, `@param`, `@return`, `@see`; update existing doc blocks when behaviour or signatures change; preserve inline comments, add new ones only where the *why* is non-obvious) — hard requirements, not suggestions.

For UI changes, build and verify in a browser/app session. If you can't exercise a code path at runtime, call it out in the follow-up comment as a gap rather than claiming success.

Edge-case handling:
- **Ambiguous comment.** If the owner's comment can be read two ways, state the interpretation in your reply and proceed with the most conservative reading. Do not ask the user.
- **Contradicts the linked issue.** If the comment asks for something that contradicts the linked issue's spec, flag the contradiction in the reply and proceed based on the comment (newer intent wins).
- **Multiple actionable comments on the same PR.** Address them together in a single push where possible. The PR reply lists each one.
- **Out of scope.** If the comment asks for something that is genuinely a separate piece of work (e.g. "while you're at it, also rewrite module X"), implement the in-scope part and call out the out-of-scope part in the reply — do not silently expand scope.

## 6. Build before pushing (mandatory)

You **must** attempt a build before pushing. Never push and comment without first trying to build. Same rules as `/pick-issue`:

- Run the project's standard build command from inside the worktree.
- If the build succeeds, note it in the follow-up PR comment's verification line.
- If the build **fails**, fix the failures and re-run until it passes. Do not push on a broken build.
- If the build fails in a way you can't fix (e.g. the owner's requested change reveals a deeper issue), **roll back your local commits** (`git reset --hard origin/<headRefName>`) and post a PR comment explaining the failure rather than pushing broken code. Do not leave the PR in a worse state than you found it.
- If the build cannot be run at all in this environment, state that explicitly in the follow-up comment as a gap — do not silently skip it and do not claim success.

## 7. Commit and push to the PR branch

Commit in logical chunks. Use a commit message that references the feedback, e.g.:

```
<short imperative summary> — addressing @soderbjorn feedback on #<N>
```

Then push to the **existing PR branch** — no new PR, no new branch:

```
git push origin HEAD
```

Capture the resulting HEAD SHA (`git rev-parse HEAD`) — you need it for the attribution footer in the next step.

## 8. Reply on the PR

Post **one** PR comment that quotes (or clearly references) the owner's original comment and reports what you did. Use this exact structure — the phrase "addressed in commit \<sha\>" is the canonical marker that future `/pick-followup` runs look for to know this comment has been handled.

```
gh pr comment <N> --body "$(cat <<'EOF'
**Claude Code** (an AI coding agent) picked up this feedback via the `/pick-followup` skill and pushed <sha>.

> <short quote or paraphrase of the owner's original comment, including a link to it if it's a review comment on a specific line>

<one paragraph: what was changed, what assumptions or interpretations you made, what you verified. Be concrete — cite files and line ranges where relevant.>

**Verification.** <e.g. "\`./gradlew build\` passes locally", or an explicit gap statement if something could not be verified.>

🤖 This comment was posted by [Claude Code](https://claude.com/claude-code) acting autonomously — addressed in commit <sha>. The human repo owner has not yet confirmed the change.
EOF
)"
```

Replace `<sha>` with the short SHA from step 7 (e.g. `abc1234`). The literal string `addressed in commit <sha>` must appear in the footer, not just the SHA on its own — the scanner in step 2 looks for that exact phrase.

If the owner's comment was an **inline review comment** (not a conversation comment), reply in-thread where possible using `gh api repos/soderbjorn/termtastic/pulls/<N>/comments` with an `in_reply_to` field, so the reply threads correctly on GitHub. If that fails, fall back to a conversation-tab comment and explicitly link back to the review comment URL in the body.

The comment must:
- State "Claude Code" (or "an AI coding agent") explicitly in the opening line.
- Reference the pushed commit SHA and include the "addressed in commit <sha>" marker verbatim.
- Include the Claude Code attribution link intact.
- Not impersonate the repo owner or claim the change has been reviewed.

## 9. Comment on the linked issue (if any)

If the PR body has a `Closes #I` reference, post a status comment on issue `I` noting that the PR received a follow-up push:

```
gh issue comment <I> --body "$(cat <<'EOF'
**Claude Code** (an AI coding agent) pushed a follow-up to #<N> via the `/pick-followup` skill, addressing feedback from @soderbjorn.

<one sentence summarising what changed since the last push>

🤖 This comment was posted by [Claude Code](https://claude.com/claude-code) acting autonomously — the repo owner has not yet re-reviewed the PR.
EOF
)"
```

Same rules as the `/pick-issue` issue-comment: explicit "Claude Code" attribution, link intact, no impersonation.

If the PR has no linked issue, skip this step — do not invent one.

## 10. Do not clean up

**Do not remove the worktree or delete the local branch.** Follow the `/pick-issue` convention — leave them in place so the user can revisit the work. The worktree path and the pushed commit SHA should be the last two things you report.

Report at the end:
- PR URL.
- The commit SHA that was pushed.
- The worktree path.
- A one-line summary of what was addressed.
