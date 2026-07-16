---
description: Move a GitHub issue to Linear — create a labelled Backlog ticket in the Lunamux project, then close the GitHub issue with "Moved to Linear".
---

Arguments: $ARGUMENTS

Migrate one or more GitHub issues from `soderbjorn/lunamux` into Linear. For each issue: create a Linear ticket in the **Lunamux** project (team **Söderbjörn** / `SOD`) in the **Backlog** state, carry the labels across, credit the original author if it wasn't the repo owner, then close the GitHub issue with the comment `Moved to Linear`.

Work autonomously — do not ask for confirmation between issues. The one exception is the duplicate guard in §3, which stops that single issue rather than the batch.

## 0. Ground rules

- **This is a one-way move, and closing the GitHub issue is irreversible-ish.** Never close a GitHub issue until its Linear ticket exists and you have the ticket URL in hand. If ticket creation fails, leave the GitHub issue open and report the failure.
- **Never impersonate.** Linear stamps the authenticated user (`soderbjorn`) as the creator of everything this skill makes — that cannot be changed, and you must not write the description in another person's voice to paper over it. Attribution to the real author is handled explicitly in §4, as a stated fact rather than a disguise.
- **One issue at a time, strictly sequential.** Each issue is a create-then-close pair; interleaving them risks closing an issue whose ticket didn't land.
- The Linear MCP tools may be deferred. Load them in **one** `ToolSearch` call before starting:
  `select:mcp__linear-server__save_issue,mcp__linear-server__list_issues,mcp__linear-server__list_issue_labels,mcp__linear-server__list_projects,mcp__linear-server__save_comment,mcp__linear-server__prepare_attachment_upload,mcp__linear-server__create_attachment_from_upload`

## 1. Parse the issue list

`$ARGUMENTS` contains the issues to move — numbers in any reasonable format (`58 61 74`, `#58, #61, #74`, `58,61,74`). Extract, de-duplicate, preserve the given order; that's the processing order.

If `$ARGUMENTS` contains no parseable issue numbers, stop and ask for a list. Do not pick issues yourself — this skill only moves what it's told to move.

Fetch each one up front:

```
gh issue view <N> --repo soderbjorn/lunamux --json number,title,body,labels,author,url,state,comments
```

If an issue is already **closed**, skip it and say so — a closed issue has usually already been moved or resolved, and re-filing it creates a duplicate in Linear. Only move it if `$ARGUMENTS` explicitly names it alongside a clear instruction to move closed issues too.

## 2. Resolve the Linear target

Do not hardcode IDs — resolve by name each run, so this keeps working if the workspace changes:

- **Team**: `Söderbjörn` (key `SOD`)
- **Project**: `Lunamux` — confirm with `list_projects`. If the project no longer exists, stop and report; do not file the ticket into a team-level backlog and hope.
- **State**: `Backlog`

## 3. Duplicate guard

Before creating anything, check whether this GitHub issue is already in Linear:

```
list_issues with query "<the GitHub issue title>" (team: Söderbjörn, includeArchived: true)
```

Also scan the results for the GitHub URL or `#<N>` in their descriptions. If a plausible match exists, **stop on this issue** — do not create a second ticket and do not close the GitHub issue. Report the existing Linear ticket URL and move on to the next issue in the list. A false duplicate is cheap to resolve by hand; a double-filed ticket plus a closed GitHub issue is not.

## 4. Compose the ticket

**Title**: reuse the GitHub issue title verbatim. Don't editorialise it.

**Description**: Markdown, in this order.

If the issue author is **not** `soderbjorn`, the first line must be the attribution — before the body, before the link, before anything else:

```
> Originally reported by @<author.login> on GitHub: <url>
```

Then a blank line, then the GitHub body — **normalized per §4a**.

If the author **is** `soderbjorn`, skip the attribution line and open with a plain backlink instead:

```
> Moved from GitHub: <url>
```

Then the normalized body.

That's the whole description — the GitHub **comments do not go in here**. They're carried across as real Linear comments in §8, which keeps the ticket readable and preserves the shape of the original discussion.

### 4a. Normalize GitHub HTML — do not paste the body verbatim

**Linear does not render raw HTML.** Its description and comment fields are markdown (ProseMirror), so any HTML in a GitHub body comes out as literal source text — and worse, Linear autolinks the URL *inside* the tag, producing this mess:

```
<img width="359" height="300" alt="Image" src="[https://github.com/...](<https://github.com/...>)" />
```

This is not hypothetical: GitHub's web uploader writes `<img>` tags, **not** markdown, so most screenshot-bearing issues in this repo hit it. Converting later in §7d works but leaves the ticket briefly broken; convert **before** the first `save_issue` instead.

Convert while composing, applying to both the description and every §8 comment body:

| GitHub HTML | Linear markdown |
|---|---|
| `<img src="URL" alt="TEXT" ...>` | `![TEXT](URL)` |
| `<img src="URL" alt="Image">` or no/generic alt | `![<describe it>](URL)` — see below |
| `<br>` | a real line break |
| `<details><summary>S</summary>…</details>` | `**S**` + the content inline (Linear has no collapsible) |
| `<b>`/`<strong>`, `<i>`/`<em>`, `<code>` | `**…**`, `*…*`, `` `…` `` |
| `<a href="URL">TEXT</a>` | `[TEXT](URL)` |

Notes:

- **`width`/`height` are dropped.** Markdown images can't carry them and Linear ignores them — the image renders at its natural size. That's a fine trade for it rendering at all; don't try to preserve sizing with HTML.
- **Alt text**: GitHub's uploader writes `alt="Image"` for every screenshot, which is useless. You haven't seen the images yet at this point (§7 downloads them), so use the best alt you can infer from the surrounding text now, then **refine it in §7d** — by then you'll have opened each file to title its attachment, so you can replace a generic alt with something factual like `![Usage tooltip — Week 0%](…)`. Describe only what's visibly there; don't infer a diagnosis you can't see.
- Put a blank line between consecutive images — GitHub tolerates them stacked, Linear renders them better separated.
- Videos (`.mov`/`.mp4`) don't render inline via `![](…)`. Use a plain link — `[Screen recording](URL)` — and let the §7 attachment row carry the file.
- Fenced code blocks, lists, and tables are already markdown on both sides. Leave them alone.

Do not add a "generated by Claude Code" footer to the Linear ticket description. The ticket is a work item, not a message from you; the attribution that matters is the author's, above.

## 5. Map the labels

Start by listing the real labels — **always pass `team`**:

```
list_issue_labels with team: "Söderbjörn", limit: 250
```

Two traps here, both of which have already bitten this skill once:

- **Omitting `team` silently hides labels.** An unscoped call returns only the workspace-level labels (the type ones) and drops every team-scoped label (the platform ones). It returns success with a short list — it does not error — so a run that forgets `team` will quietly file every ticket with no platform label.
- **The `name:` filter is an exact, case-sensitive match** and is *also* team-scoped. `name: "android"` returns `[]` while the `Android` label plainly exists. Don't use `name:` to probe for a label — list all of the team's labels once, then match case-insensitively in your head against that list.

At time of writing the team has eight labels in three independent groups. Treat this as a snapshot to sanity-check against, not as the source of truth — `save_issue`'s `labels` field **replaces the whole set**, so a stale or misspelled name silently drops labels rather than erroring.

**Type** (workspace-level — pick at most one):

| GitHub label | Linear type |
|---|---|
| `bug` | `Bug` |
| `enhancement` | `Feature` |
| `documentation` | `Improvement` |

**Code health** (team-scoped — zero or one): `Codebase`, mapped 1:1 from GitHub's `codebase` label.

This is **orthogonal to the type**, not a type of its own — it marks "this touches code health / internal quality", which an issue can be *in addition to* being a Feature or a Bug. So `enhancement` + `codebase` yields `Feature` **and** `Codebase`, and neither displaces the other. Apply it when GitHub carries `codebase`; you may also add it to an unlabelled issue that is plainly a refactor or internal cleanup.

**Platform** (team-scoped — zero or more): `Android`, `iOS`, `Desktop`, `Server`.

GitHub has **no** platform labels, so these can only be inferred from the title and body. Typical signals in this repo:

- `Mobile:` prefix, "Android", "emulator", "phone" → `Android`
- "iOS", "iPhone", "iPad", "Swift" → `iOS`
- `Mac:`, "Electron", "web", "browser", "desktop" → `Desktop`
- "server", "pairing", "socket", "host" → `Server`

Cross-platform issues legitimately take several (e.g. *"Mobile: map the split-chrome theme tokens onto the Android/iOS shells"* → `Android` + `iOS`).

**`Desktop` is the default.** If the issue gives no clear signal pointing to `Server`, `Android`, or `iOS`, label it `Desktop` — that's the primary surface, so an unqualified issue is almost always about it. Only leave the platform off if the issue is explicitly about shared/common code with no platform angle at all. This makes `Desktop` the fallback, not a guess you have to justify: prefer it over an empty platform whenever you're merely unsure.

Rules:

- The final `labels` array is **the type, plus `Codebase` if it applies, plus every applicable platform**, passed together in one call — the three groups are independent, not alternatives. Worked example: #129 *"Mobile: map the split-chrome theme tokens onto the Android/iOS shells"* (`enhancement`, `codebase`) → `["Feature", "Codebase", "Android", "iOS"]`.
- **No match → no label.** `question`, `duplicate`, `invalid`, `wontfix`, `help wanted`, `good first issue`, and `ai-dev` are GitHub workflow labels with no Linear equivalent — drop them silently.
- **Unlabelled on GitHub** → infer the type from title and body: a defect report is `Bug`, a request for something new is `Feature`, a refactor/cleanup/docs change is `Improvement`. If it reads as none of the three, leave the type off.
- Never invent a label. `save_issue` takes label *names*, and a name that isn't in the list you fetched is an error — it does not create the label.
- Multiple GitHub labels collapsing to the same Linear label become one.
- **If GitHub labels map to conflicting types, pick one by precedence: `Bug` > `Feature` > `Improvement`.** E.g. `bug` + `enhancement` → `Bug` alone. Never pass two type labels. (`codebase` is not a type and never participates in this — it rides alongside.)
- Platform inference reads prose, so it's a judgement call — but don't agonise. A positive signal for `Server`/`Android`/`iOS` wins; absent one, it's `Desktop`. Any of these is trivial to correct in Linear.

## 6. Create the ticket

```
save_issue with:
  team: "Söderbjörn"
  project: "Lunamux"
  title: <from §4>
  description: <from §4>
  labels: <from §5, omit the field entirely if none>
  state: "Backlog"
```

Do **not** pass `id` (that would update an existing issue instead of creating one). Do not set an assignee — these land in the backlog unassigned.

Capture the returned Linear identifier (e.g. `SOD-12`) and URL. If the call fails, stop on this issue, leave the GitHub issue open, report the error, and continue to the next issue.

## 7. Carry the attachments across

Most issues in this repo have screenshots or screen recordings, and they are usually the whole point of the report. **Re-host them in Linear — do not leave the ticket pointing at GitHub.**

Why re-upload rather than keep the GitHub link: a `github.com/user-attachments/assets/<uuid>` URL 302-redirects to an S3 URL signed with `X-Amz-Expires=300`. The outer URL is stable and re-signs per request, so it *appears* to work today only because this repo is public. It is not a durable reference — it breaks the moment the repo goes private, and the ticket is supposed to outlive the closed issue.

This step runs **after** §6 (uploads need an existing issue ID) and **before** §9 (never close until the attachments are safely across).

### 7a. Find them

Scan the description **and every comment you'll carry across in §8** for GitHub attachment URLs — screenshots are at least as likely to show up in a follow-up comment as in the original report. Cover every shape:

- `![alt](https://github.com/user-attachments/assets/<uuid>)` — images and videos
- `<img src="https://github.com/user-attachments/assets/<uuid>" ...>` — HTML embeds
- `[name.log](https://github.com/user-attachments/files/<id>/<name>)` — the **`/files/`** path, a different shape from `/assets/`
- `https://user-images.githubusercontent.com/...` — legacy, still present on older issues
- bare URLs on their own line (that's how `.mov` recordings auto-embed)

If there are none, skip to §8.

### 7b. Download

```
curl -sL "<url>" -o "<dir>/<name>"
```

**Use `GET` with `-L`, never `HEAD`.** A `curl -I` probe returns **403** on these URLs even when the asset is perfectly fetchable — the signed S3 request rejects it. Do not use `-I` to test whether an attachment exists; you will conclude it's missing when it isn't.

Verify each download before uploading it — a 403/404 HTML error page will otherwise sail through as a "successful" file:

```
file --mime-type -b "<path>"   # real type; also gives you the extension
wc -c < "<path>"               # exact byte size, required by prepare_attachment_upload
```

If `file` reports HTML/XML where you expected an image, the download failed. Treat that as a failure (§7e) rather than uploading the error page.

`/assets/<uuid>` URLs carry no filename. Derive one from the alt text, falling back to `<uuid>.<ext>` with the extension from the sniffed MIME type. (The S3 redirect target also contains the original filename if you want a nicer one.)

Use the scratchpad directory for downloads, not the repo.

### 7c. Upload — one file at a time, strictly

For each file, complete all three steps before starting the next:

1. `prepare_attachment_upload` with `issue: <SOD-xx>`, `filename`, `contentType` (the sniffed MIME type), `size` (exact bytes) → returns `assetUrl` and `uploadRequest {url, headers}`.
2. `PUT` the raw bytes to `uploadRequest.url`, passing **every** header from `uploadRequest.headers` verbatim — including casing. Any omitted or altered header returns 403.
   ```
   curl -X PUT --data-binary @"<path>" -H "<each signed header>" "<uploadRequest.url>"
   ```
   Send the raw bytes: `--data-binary`, never `-d` (which mangles binary) and never base64.
3. `create_attachment_from_upload` with `issue` and `assetUrl`.

**Do not batch the prepares.** The signed URL expires in **60 seconds**, so preparing several files up front means the early URLs die while you're still uploading the later ones. Prepare → PUT → finalize, then move to the next file.

Ignore the deprecated `create_attachment` (base64) tool.

### 7d. Rewrite the description

Uploading alone doesn't fix the description — it still points at GitHub. Once all files are up, `save_issue` with `id: <SOD-xx>` and a description where every GitHub attachment URL is swapped for its Linear `assetUrl`, keeping the markdown structure so images stay inline exactly where the author put them.

This is a **URL swap only** — the HTML→markdown conversion already happened in §4a, so the `![alt](…)` syntax is in place and you're just changing what's inside the parentheses. If you find yourself rewriting `<img>` tags here, §4a was skipped.

Paste the bare `assetUrl`. Linear appends its own `?signature=…` read token when it stores the description; that's expected, and you should not add or copy one yourself.

Keep a **URL map** of `<github url> → <linear assetUrl>` as you go. §8 needs it to rewrite attachments that appeared in comments rather than the description.

Two things to keep straight, because they're different surfaces:
- **Rewriting the markdown** is what makes a screenshot render *inline, in context*. This is the part that matters.
- **`create_attachment_from_upload`** adds an attachment *row* on the issue. Do both: the row makes the file durable and findable, the inline URL keeps it readable.

Remember `save_issue` replaces the description wholesale — send the complete rewritten text, not a fragment.

### 7e. If an attachment fails

**Leave the GitHub issue open, and do not close it (§9) for that issue.** Report which attachment failed and why. The Linear ticket stays (with its original GitHub URLs, which still resolve while the repo is public), so this is recoverable by hand — but a closed issue whose screenshots didn't make it is exactly the silent loss this ordering exists to prevent.

## 8. Carry the comments across as comments

GitHub comments become **real Linear comments** — one `save_comment` per GitHub comment, in chronological order. Don't flatten them into the description: the back-and-forth of a bug report (repro, follow-up, decision) is most of its value, and it survives only if it keeps its shape.

```
save_comment with:
  issueId: <SOD-xx>
  body: <see below>
```

### Attribution — same rule as the ticket

Every comment **not** authored by `soderbjorn` must open with its original author, as the first line, before the body:

```
> **@<login>** commented on GitHub on <YYYY-MM-DD>:
```

Then a blank line, then the comment body.

Comments authored by `soderbjorn` get **no** prefix — they're already correctly attributed, since Linear stamps `soderbjorn` as the author of everything this skill writes. That asymmetry *is* the point: the prefix exists precisely where Linear's own authorship would otherwise be wrong. Without it, another person's words would silently read as the owner's.

Never drop the prefix to make a thread read more smoothly, and never write a comment in someone else's voice — state the attribution, don't simulate the author.

### What to carry

- **Substantive comments**: repro steps, design discussion, decisions, "this also happens on X". Carry them.
- **Skip**: `+1`/`👍`-only comments, bot noise, and pure cross-post chatter that dies with the GitHub thread. If in doubt, carry it — a redundant comment is cheaper than a lost decision.
- GitHub issue comments are flat, so each becomes a **top-level** Linear comment. Don't invent `parentId` threading that wasn't in the source. The one exception: if a comment is plainly a reply to the one before it *and* you're carrying both, `parentId` is a reasonable way to preserve that.
- Rewrite any attachment URLs in the body using the §7 URL map, so images render inline in the Linear comment instead of pointing at GitHub.
- Preserve the original markdown — code blocks, lists, and log dumps should survive intact.

If a `save_comment` call fails, treat it like a failed attachment (§7e): **leave the GitHub issue open** and report it. A closed issue whose discussion didn't make it is the same silent loss.

## 9. Close the GitHub issue

Only once §6 returned a real ticket URL, §7 moved every attachment, **and** §8 carried every comment:

```
gh issue close <N> --repo soderbjorn/lunamux --comment "Moved to Linear"
```

The comment body is exactly `Moved to Linear` — the owner asked for that literal text, so don't embellish it with the Linear URL or an attribution footer. (Linear is a private workspace, and this repo is public, so the link would be dead weight — or a tease — for any outside reader.)

If the close fails, report it — the Linear ticket already exists, so this needs a manual close rather than a retry of the whole flow.

## 10. Report

One line per issue, in processing order:

```
Moved <D>/<T> issue(s) to Linear (project Lunamux, Backlog).
  • #<N> → <SOD-xx> <title> — labels: <Linear labels or "none">, credited @<author>, <A> attachment(s), <C> comment(s) | closed on GitHub
  • #<N> — skipped: already closed on GitHub
  • #<N> — skipped: likely duplicate of <SOD-yy>
  • #<N> — incomplete: <SOD-xx> created but <attachment|comment> transfer failed — GitHub issue left OPEN
  • #<N> — failed: <reason> (GitHub issue left open)
```

Two states need explicit callouts, because both need a human:

- **Ticket created, GitHub close failed** — needs a manual close.
- **Ticket created, attachments or comments incomplete** — the GitHub issue is deliberately still open; say which pieces didn't make it, so the owner can finish the move rather than discovering the gap later.
