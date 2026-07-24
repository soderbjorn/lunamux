# Server-authoritative terminal screen

Design record for the `feature/server-side-screen` work: what it is, what is settled,
what is not, and the measurements already paid for. Kept in the tree rather than in a PR
comment so it can be diffed against the code it describes and so no future pass has to
re-derive it.

## Goal

Move terminal-screen authority to the server (the tmux/mosh model): one canonical headless
emulator per session, and a *synthesized*, width-correct redraw per client, so a client
never reinterprets bytes authored for another client's width.

The mechanism is `SessionGrid` (the canonical emulator) plus `GridSerializer` (reads it
back as constructed paint — RIS-prefixed, styled SGR runs, wrap-faithful line flow,
explicit cursor/mode epilogue). Because the redraw is constructed rather than replayed it
is width-correct by construction and contains no device queries, so it carries no
re-answer hazard.

## Acceptance criteria

1. **Both devices edit at their own native size.** The driver is native; the other mirrors.
   *Met.*
2. **No duplicated output when switching devices.** *Not met* — see below.
3. **A live (not replayed) session stays well-formatted on both laptop and phone.** *Met.*

Criterion 2 is pinned as executable tests in `TakeOverDuplicationTest`. The tests marked
`@Ignore` there are exactly this criterion: they fail today, on purpose, and are the
definition of done. The live tests in the same file are the safety direction that any fix
must preserve.

## The root tension

One PTY has one `winsize`. Native-size-per-client therefore forces a PTY resize whenever
the driving client changes, and a resize is a `SIGWINCH`. How a program answers it decides
everything:

- **Append-only shells** — no repaint; nothing to duplicate. Safe.
- **Alternate-buffer TUIs** (vim, less, htop) — the alt buffer has no scrollback, so a
  repaint has nothing to duplicate into. Safe.
- **Normal-buffer full-screen repainters** — the hard class, and a small one. Claude Code
  (React + Ink) is the flagship case: it renders into the *normal* buffer and re-renders
  its whole managed view on `SIGWINCH`.

Mechanically: the resize reflows the transcript, a narrowing archives the overflow into
scrollback, and the program's repaint can only reach the on-screen rows — scrollback is
immutable to the program. So the archived top of the old frame stays, and the fresh
re-emit stacks a second copy on top of it.

The duplication lives in the server's canonical grid, and the grid is *faithfully*
reproducing what a real terminal shows when you resize Claude Code. It is not a client
rendering bug and not a reflow bug. The pre-refactor build avoided it only by pinning the
PTY width and never resizing — the native-sizing sacrifice this work exists to undo.

## Measured facts

Keep these; they were expensive.

- **Reflow is reversible in isolation.** A 100-col box-drawn table narrowed to 60 and
  widened back is byte-identical, and stays so across eight cycles
  (`ReflowReversibilityTest`). It is *not* reversible with a repaint fed between cycles —
  the program's re-emit mints the duplicate, not the reflow.
- **Resize count is already optimal**: one effective size change per take-over
  (`TakeOverChurnTest`), after the Android vote-flood debounce. The cost is one *repaint*,
  not extra `SIGWINCH`es.
- **Claude re-emits banner + body on resize** via cursor-addressed redraw (`v2.1.218` in 13
  post-resize chunks, `Anthropic` in 9). It does not emit the plain string "Claude Code v"
  because it splits words with cursor moves — naive greps will mislead.
- **The repaint is self-declaring in practice**: every post-SIGWINCH chunk observed opened
  `ESC[?25l` … `ESC[H` then exactly `rows` × (`ESC[2K` `ESC[1B`), across 22 consecutive
  resizes in both directions.
- **The artifact is bounded, not linear in switches.** The duplicate is minted by the
  *narrowing* and reabsorbed by the *widening*, which pulls those rows back onto the taller
  screen for the next repaint to erase. `TakeOverDuplicationTest` shows a single narrowing
  switch duplicating while eight full narrow→wide cycles do not accumulate. An accumulating
  count on device (≈3 banner copies over ~20 take-overs) must therefore come from content
  committed between switches and frames of varying height, not from the switch count.

## What was tried and reverted

`RepaintDeclaration` + `truncateTranscriptToCompletedLines` (commits `d2cc6a1`, `c88c61a`,
`b4d63c5`): detect the full-screen-erase prologue, then withdraw the reflow's archival by
truncating the transcript back to its pre-resize completed-line count. Reverted, not
merely disabled. Why it was the wrong shape:

- **It deleted genuine history.** On a narrowing the archived rows are a *mix* of "frame
  top the program will redraw" (redundant) and "content that no longer fits and will not be
  redrawn" (real history). A count-based truncate cannot tell them apart.
- **Its premise was false.** "Completed-line count is width-invariant" does not hold in
  practice, because widening pulls scrollback back onto the taller screen — committed
  history is not a stable quantity when content oscillates across the screen-fit boundary.
- **It was program-specific.** It recognised one repaint idiom and undid an archival the
  same layer had just created: two patches fighting each other.

The through-line: it reconciled the program's re-render against scrollback *after the
fact*, on a data structure that tangles live screen and history together and offers no
clean seam to reconcile on.

## Direction

**Split live screen from history.**

- **Scrollback** becomes an append-only log of *logical lines* with styles, committed once
  when a line scrolls off during normal operation, stored at authored width, **never
  reflowed**. Display-time wrapping is a pure function of `(logical line, width)` computed
  per client.
- **Live screen** stays the canonical emulator, width-adaptive, synthesized per client.
- **Clients become pure renderers.** They must not accumulate their own scrollback from the
  raw byte stream — that is the width-bound thing this refactor exists to kill. (Today they
  still do between resyncs; the job is not finished until they don't.)

This dissolves rather than patches the bug: with history separate and immutable to reflow,
a resize touches only the live screen.

It also fixes a cost the current design carries silently. `GridSerializer.serialize()` is
RIS + ED3 + the full 3000-row transcript, broadcast on **every** cols change. At 200 cols
with SGR runs that is multiple MB to a phone per take-over, and the RIS discards each
client's local scroll position and selection. With a separate log, history goes once and
the wire carries deltas.

### Reconciling the repaint against the log

The remaining question is whether content that scrolls off during a repaint storm belongs
in history. Prefer **content-based suffix matching** over both the reverted count-based
truncate and a byte-prologue gate:

1. On resize, record the log tail (last ~200 logical lines, hashed).
2. During the post-resize window, buffer lines that scroll off rather than appending them.
3. When the window closes, find the longest suffix of the pre-resize tail matching a prefix
   of the buffered lines. That overlap is the re-emit — drop it, append the rest.

Why this survives the objections above: it is content-based, so width-invariance is
irrelevant; it is program-agnostic, so repaint-prologue detection becomes an optimisation
rather than a correctness dependency; and it fails safe — no match means append everything,
i.e. today's behaviour. It requires logical lines at authored width, which is another
reason the log split comes first.

Known failure mode to bound: legitimately repeated output (a build log emitting the same
line twice) inside the window can false-match. Keep it window-scoped, contiguous-suffix
only, with a minimum match length.

### The trade-off to decide explicitly

For normal-buffer repainters, of { native-size-per-client, zero duplication,
never-pin-the-PTY-width } any two are straightforward. All three require the server to
reconcile the program's re-render against its own model on every switch.

The cheaper answer, and the recommended default: **make the take-over resize an explicit
user action rather than an automatic consequence of interacting with a device.** If the PTY
only resizes when the user asks for it, criterion 2 holds by default, criterion 1 is
available on demand, and the artifact — when it appears — is something the user just
requested and can attribute. Build the reconciler only if that proves insufficient in
practice.

## Open questions

1. Is a commit-gate during a declared repaint sufficient, or is the suffix-match
   reconciliation above needed?
2. Should scrollback wrapping be recomputed per client at render time, or cached?
   (Memory vs CPU; a long session is a few MB of styled logical lines.)
3. Can the full-screen-repaint signal be made program-agnostic robustly enough to gate on,
   or should it be inferred from emulator state transitions rather than the byte prologue?
4. Does the alt-buffer path (already clean) share code with this, or stay separate?
5. **Driver identity is currently inferred, not assigned.** `PtyPresentation.isPassive` is
   `serverCols != naturalCols`, so two clients at the same width both believe they drive,
   and a bare width mismatch once flipped a lone client to passive on restore (patched at
   the symptom in `d8a376a`). The server already computes the governor in
   `ClientSizeArbiter` but never tells anyone. Putting the governor's client id in the
   `Size` event and `AttachPayload` removes the whole class.

## Temporary diagnostics

`TerminalSessionManager.SizeChurnLog` logs every effective PTY size change and the first
3 KB of post-resize program output. Armed by `LUNAMUX_SIZE_CHURN_LOG=<path>` (forwarded by
`scripts/run-electron-dev.sh` as `-PsizeChurnLog=`). Remove it, its Gradle wiring in
`server/build.gradle.kts`, and the script pass-through before upstream.

`Swallowed` is not a diagnostic and stays: it counts the exceptions the PTY path
deliberately swallows and logs the first per site, so those `catch` blocks cannot hide the
next class of bug indefinitely.
