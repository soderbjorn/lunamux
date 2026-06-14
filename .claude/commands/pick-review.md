---
description: Pick a GitHub pull request without a review (oldest-first, semantic match, or by PR number) and post a comprehensive AI code review.
---

Arguments: $ARGUMENTS

Pick one open PR on `soderbjorn/termtastic` that has not yet received a review, analyse it in depth, and post a single comprehensive review comment. Work autonomously — do not ask for confirmation at any step.

## 1. Resolve the target PR

- If `$ARGUMENTS` contains an explicit PR number (e.g. `42`, `#42`, `PR 42`, `pr/42`), target that PR directly. Skip to step 3. If the PR is closed/merged or does not exist, stop and report. **The author gate below still applies** — if the targeted PR is not authored by `soderbjorn`, stop and report rather than reviewing.
- Otherwise fetch candidates:

```
gh pr list --state open --json number,title,body,author,labels,isDraft,reviewDecision,latestReviews,comments,createdAt --limit 200
```

A PR is a **candidate** only if all of the following hold:
- It is open and **not a draft**.
- **Authored by `soderbjorn`** (the repo owner's GitHub account) — check `author.login == "soderbjorn"`. This gate prevents Claude from autonomously reviewing PRs from outside contributors; those should be triaged by the owner first. Note that PRs Claude Code opens through the owner's `gh` token also satisfy this gate — they show up on GitHub as authored by `soderbjorn` regardless of who composed the content, and a fresh review pass on them is legitimate work (follow-ups still take priority per `/babysit-repo` when the owner has actually commented).
- `latestReviews` is empty (no formal reviews yet). A PR is also a candidate if the only reviews are from the current user and were requested re-reviews — but in general, err toward "no reviews" = no entries.
- **Either** no existing issue/PR comment contains the pick-review attribution footer (`posted by [Claude Code]` acting as a review), **or** the most recent such comment is older than the latest commit on the PR's head branch. The first case is a fresh review; the second is a re-review triggered by a follow-up push (typically from `/pick-followup` addressing earlier feedback). To check this, look at the `createdAt` of the newest pick-review comment and compare it to the `committedDate` of the head commit (`gh pr view <N> --json comments,commits`). If the head commit is newer, the PR has gained code since the last Claude review and is eligible for another pass. This naturally terminates: the re-review itself will be newer than the head commit, so it won't trigger a third review unless yet more code lands.

If zero candidates remain, stop and report that to the user. Do not proceed.

## 2. Pick ONE PR

- `$ARGUMENTS` empty → pick the **oldest-created** candidate (smallest `createdAt`). Do not randomise — runs should be reproducible, and the user steers the review queue by keeping older PRs at the front.
- `$ARGUMENTS` is a semantic description → read the title and body of each candidate and pick the best semantic match. Use judgement, not string matching.
- If two candidates are genuinely tied (identical `createdAt` or equally strong semantic match), pick the lower-numbered one. Do not ask the user.

Tell the user which PR you picked (number + title) before continuing.

## 3. Gather everything needed to review

Collect enough context to form a real opinion, not a surface-level once-over. At minimum:

```
gh pr view <N> --json number,title,body,author,baseRefName,headRefName,additions,deletions,changedFiles,files,commits,labels
gh pr diff <N>
```

Also:
- Read the PR body carefully. Note what the author claims the PR does, what they say they verified, and what they flag as open questions.
- If the PR body references an issue (`Closes #X`, `Fixes #X`), fetch that issue (`gh issue view X`) so you can judge whether the PR actually addresses it.
- For any file that is load-bearing in the diff, open it at the PR's head (either via `gh pr checkout <N>` in a throwaway worktree, or by reading the file through `gh api` at the head SHA) so you can see the surrounding code, not just the hunks.
- Look at recent commits on `main` touching the same files (`git log --oneline -- <file>`) to check for conflicts in intent or obvious redundancy with in-flight work.
- If the project has a CLAUDE.md or similar, verify the PR conforms (documentation rules, testing rules, etc.).

If checking out the PR, use a sibling worktree so the main checkout stays clean:

```
git fetch origin pull/<N>/head:pr-<N>-review
git worktree add ../pr-<N>-review pr-<N>-review
```

Clean the worktree up when done (`git worktree remove ../pr-<N>-review` and `git branch -D pr-<N>-review`) — unlike `pick-issue`, there is nothing the user needs to revisit here.

## 4. Write the review

Write a single comprehensive review comment. It must be **substantive** — a reviewer reading it should understand what the PR does, whether it is correct, what could go wrong, and what to do about it. Avoid generic praise and avoid bullet-spam. Use prose where prose is called for.

Structure:

```
**Claude Code** (an AI coding agent) picked up this PR via the `/pick-review` skill and reviewed the changes. This is not a human review — treat it as a second set of eyes, not an approval.

## Summary of changes
A short paragraph describing, in your own words, what this PR actually does at the user-visible / behavioural level. Not a file list — a behaviour description. If it diverges from what the PR body claims, say so here.

## Functional review
Does the PR actually accomplish what it sets out to do? Walk through the happy path and the obvious edge cases. Call out cases the author did not verify, behaviours that differ from the linked issue, and anything user-facing that seems off (wording, affordances, defaults, accessibility, keyboard/mouse behaviour if relevant).

## Technical review
Code-level assessment. Architecture, layering, naming, separation of concerns, reuse vs. duplication, adherence to project conventions (CLAUDE.md, existing patterns), test coverage, documentation quality. Cite specific files and line ranges (`path/to/File.kt:42`) when making a point — vague feedback is not useful.

## Consequences
Downstream effects of merging this. Migrations, data/schema changes, API surface changes, settings format changes, behaviour shifts for existing users, interactions with other in-flight work, build/CI impact, performance implications. If there are none, say "None identified."

## Risks
What could go wrong. Bugs you suspect, regressions, race conditions, error-handling gaps, security concerns, resource leaks, thread-safety issues, untested code paths. Rank by severity (blocker / should-fix / nit) so the author knows what matters. If nothing rises to a real risk, say so plainly rather than inventing concerns.

## Ideas & alternatives
Suggestions the author may want to consider — refactors, simpler approaches, follow-ups, better naming, additional tests, places to extract or inline. These are optional; frame them as ideas, not demands. Omit the section if you have nothing genuine to offer.

## Verdict
One short paragraph: would you merge this as-is, merge with small changes, or request meaningful rework? Be honest. If you cannot tell (e.g. couldn't run the build, couldn't exercise the UI), say so here as an explicit gap rather than hedging.

🤖 This comment was posted by [Claude Code](https://claude.com/claude-code) acting autonomously — the human repo owner has not yet reviewed the PR. Use it as input, not as a gate.
```

Rules for the review body:

- Open with the attribution paragraph verbatim so readers don't mistake it for a human reviewer. Close with the footer verbatim, link intact.
- Be specific. `path/to/File.kt:42` beats "in the main file". A concrete counter-example beats "might have issues".
- Do not invent problems. If a section has nothing genuine in it, write "None identified." and move on. A thin honest review is better than a padded dishonest one.
- Do not approve or request-changes on the PR — this is a comment, not a formal review verdict. The verdict paragraph is advisory only.
- Do not impersonate the repo owner. Do not claim the PR has been tested in environments you did not actually run.
- No `$ARGUMENTS` or other template literals in the final body.

## 5. Post the comment

Post a single comment on the PR:

```
gh pr comment <N> --body "$(cat <<'EOF'
<review body from step 4>
EOF
)"
```

Report the PR URL and a one-line summary of your verdict at the end. Do not push code, open additional PRs, or modify the branch — this skill only reviews.
