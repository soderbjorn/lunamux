# Posting to GitHub

Shared reference for `/ai-dev`. Pasted into every brief that writes to GitHub —
a pull request, a comment on one, or a review of one. Identical in every repo
that uses the skill.

## Post as the bot, never as the maintainer

Every **write** to GitHub takes the app token:

    GH_TOKEN="$(~/.config/ai-dev/gh-app-token.sh)" gh …

That covers `gh pr create`, `gh pr comment` and `gh api … /reviews` alike. The
helper mints a short-lived GitHub App installation token, so the write lands as
`soderbjorn-agent[bot]`. Two things depend on it, and both matter:

- **GitHub does not let anyone approve their own pull request.** Anything filed
  under the maintainer's account is something the maintainer cannot approve.
- **The bot's name is what marks the work as agent-written** — in the pull
  request list, on the pull request, and on every comment and review it leaves.
  That is why there is no label to manage.

`git push` stays under the maintainer's own credentials. Only the `gh` calls take
the token; reads do not need it.

If the helper is missing or fails, do not stop: post as the maintainer and say so
in your summary. That summary is the only place a human learns that this one
wears no bot name — and, for a pull request, that merging it will need a bypass.

## Write the body to a file, never to `--body "…"`

    gh pr create  … --body-file <path>
    gh pr comment … --body-file <path>

Put the file under `<config.worktreeParent>/.ai-dev/` — outside every repo, so it
can never pollute a diff, and it stays as a record of what was posted. A body
inlined in the shell is one backtick away from command substitution eating half
of it, and these bodies are full of backticks.

## Do not hard-wrap the prose

GitHub renders a single newline inside a paragraph as a **line break**, not as a
space. Prose wrapped at 80 columns therefore *renders* wrapped at 80 columns: a
narrow ragged column down the left of a wide comment box, with the right half
empty. It looks like a bug in the page, and on a phone it reads worse still.

**One paragraph is one line, however long it gets.** Blank lines between
paragraphs, and let the browser do the wrapping. This applies to a blockquote
banner too — `> ` once, then the whole paragraph on that line.

Lists, tables, headings and fenced code are unaffected: their newlines are
structure rather than wrapping, and they must stay.
