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
2. **No duplicated output when switching devices.** *Met* — by the live-screen/history
   split below, not by reconciling a repaint against a reflowable transcript.
3. **A live (not replayed) session stays well-formatted on both laptop and phone.** *Met.*

Criterion 2 is pinned as executable tests in `TakeOverDuplicationTest`. They were written
failing, as the definition of done, and the split satisfies them: a single narrowing
take-over, one preceded by stale in-flight output, a two-step resize burst, and eight
alternating switches all leave exactly one copy of the frame. The rest of that file is the
safety direction — committed history survives a take-over, and a resize with no repaint
loses nothing.

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
- **The duplicate was minted by the *narrowing*.** Widening reabsorbed it, pulling those
  rows back onto the taller screen for the next repaint to erase — which is why, before the
  split, a single narrowing switch duplicated while eight full narrow→wide cycles did not
  accumulate.

  **Superseded by the split, and worth knowing why:** reabsorption was a property of the
  reflowable transcript. Immutable history has none, so the same eight-cycle test went from
  passing to eight copies until each switch got its own resolved window. The artifact was
  never bounded by anything principled — it was bounded by an accident that also caused the
  bug.

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

## The driver renders native output (the mechanism that actually holds)

A real Claude Code capture, replayed offline through `SessionGrid`
(`PtyTrace` + `TraceAnalysis`), settled what synthetic fixtures could not: after a resize
the program re-emits its *whole* view from the banner down, and on a conversation taller
than the screen that re-emission's top scrolls straight back off — so it duplicates what is
already in history, not what is on the screen. Two clean policies were measured and both
failed: content reconciliation degrades once history holds its own partial misses (banner
settled at ×2, and it is a heuristic), and a pure commit-gate discards the *entire*
conversation when take-overs happen continuously while the program streams (all genuine
output scrolls off through the windows). No window policy can separate re-emission from
genuine output by timing, because during rapid take-over they arrive through the same
window.

What the capture also revealed is that the duplication a user sees on their **own** screen
was not the program's — it was ours. On every cols change the server broadcasts a
synthesized resync (RIS + history + screen) to *every* client, including the one that just
took over. That client is at the PTY's width, so the program's native output is already
width-correct for it; the resync then overwrites that clean native render with the server's
reconstruction, built from the history a re-emission duplicates.

So the driver renders native. `SessionEvent.Output.resync` marks the synthesized redraw,
and the `/pty` route withholds it from whichever connection currently governs (tracked from
the same ordered `Governance` events). The driving client applies the program's own output —
width-correct by construction, no synthesis, no reconciliation — and the device the user is
actually using is always clean. This is a subtraction, not an algorithm.

**What this leaves.** The synthesized redraw still goes to *mirror* clients (a phone
watching the laptop), and it is still built from history that a re-emission duplicates — so
a mirror can still show duplication until the user switches to it (at which point it becomes
the driver and renders native). Mirror-side duplication is the remaining, lower-stakes
concern; the reconciliation window below only ever affected that synthesized path and is a
candidate for removal once mirror scrollback is sourced from the program's re-emission
rather than accumulated scroll-off. The attach redraw is always sent (a fresh connection has
no native output to show yet); only the *live* resync is withheld from the driver.

## The split (implemented)

**Live screen and history are separate.**

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

How it is realised:

- `terminal-core` gained two additive changes, both mirroring machinery the alternate
  buffer already relied on: `transcriptRows == 0` builds a screen-only main buffer, and
  `setRowEvictionListener` reports each row as it leaves the screen while it is still
  intact. The rows-only resize path now derives "may this buffer hold transcript rows?"
  from capacity rather than from the caller's `altScreen` flag.
- `HistoryLog` holds the committed logical lines. Wrapping is not stored: serving a line at
  a client's width is emitting its characters and letting that terminal wrap them.
- `SessionGrid` owns both halves and the reconciliation window.
- `GridSerializer` emits history and the live screen as **one continuous line stream**. It
  must not home the cursor before painting the screen when history precedes it — doing so
  paints over the history tail, which then never scrolls into the receiver's own log and
  leaves both halves short. That was a real bug caught by the round-trip tests.

Also fixed on the way: `TerminalRow.clear()` never reset the row's wrap flag, so a recycled
ring slot could claim a soft wrap it no longer had and fuse two unrelated lines into one. A
deep transcript hid this (a reused slot was thousands of rows old); a screen-only buffer
reuses slots every few lines and exposed it immediately.

It also fixes a cost the current design carries silently. `GridSerializer.serialize()` is
RIS + ED3 + the full 3000-row transcript, broadcast on **every** cols change. At 200 cols
with SGR runs that is multiple MB to a phone per take-over, and the RIS discards each
client's local scroll position and selection. With a separate log, history goes once and
the wire carries deltas.

### Reconciling the repaint against the log

A resize opens a **window**: lines leaving the screen are held rather than committed. The
verdict is taken from content, not from the bytes that arrived — whatever the program has
drawn by then is on the screen, so a pending line that reappears there was reclaimed by the
repaint. The longest *prefix* of the pending lines that appears as a contiguous run on the
screen is dropped; everything past it is committed.

Prefix rather than anywhere, and contiguous rather than scattered, so an incidental
one-line coincidence in a shell's output cannot trigger it; the reflow pushes the frame's
top off first, so that is where a repaint's overlap must begin. It fails safe: no match
means commit everything.

Window lifecycle, all three parts load-bearing:

- opening is **idempotent**, so a take-over's burst (a cols change, then a rows-only
  keyboard adjust, with no output between) is one window spanning the program's whole
  response — the specific way the reverted approach failed;
- a new resize **resolves the previous window first**, but only once at least one chunk has
  been fed, i.e. the program has had a chance to answer. Without this, eight alternating
  switches poured into a single window and produced eight copies;
- reads **never** resolve it. They answer from the current screen — committed lines plus
  whatever is pending minus the part the program has visibly reclaimed — which is correct at
  every instant without deciding anything. A chunk count bounds a window nobody is looking
  at.

That last point is the one that would have bitten on device. The resync redraw is debounced
100 ms after a cols change, so a read-resolves design takes the verdict 100 ms after the
resize — a race against the program's repaint, lost whenever the program is slower than
that, and losing it commits a duplicate *permanently* now that immutable history has no
reabsorption. It would have shown up as intermittent duplication with no obvious cause.
Instead, `SessionGrid` reports when a repaint lands and part of the pending content becomes
redundant, and the session requests a resync at that moment. Pinned by
`TakeOverDuplicationTest`, whose early-read cases fail with two copies against the
read-resolves behaviour.

The safety property that separates this from the reverted truncation: a verdict can only
reach lines that left the screen inside its own window. Committed history is not
addressable from it, so a wrong verdict costs one resize's worth of scroll-off rather than
the user's scrollback.

Known failure mode to watch: legitimately repeated output (a build log emitting the same
line twice) inside a window could false-match. Bounded by the prefix + contiguity rules
above.

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

1. Wrapping is currently recomputed by the *receiver* (history is emitted unwrapped and the
   client's terminal wraps it), which sidesteps the memory-vs-CPU question entirely. Revisit
   only if a client ever needs the server's wrapped view.
2. Does the alt-buffer path (already clean) share code with this, or stay separate?
3. The resync still sends the whole log on every cols change. With history now a separate,
   append-only structure, sending it once and streaming deltas afterwards is finally
   possible — that is the remaining cost noted above, not a correctness gap.
4. `TerminalSessionManager` still holds `SizeChurnLog`, and the web still carries the
   vote-in-flight grace window that server-assigned governance made redundant on the
   governed path.

## Settled: governance is assigned, not inferred

Clients used to *infer* whether they were driving by comparing the authoritative width
against the width they would render at. That could not distinguish two clients that happen
to size alike (both concluded they were driving), and it could not represent governance
moving without the grid moving — which is exactly what a same-width take-over is. It also
needed a vote-in-flight grace window to stop a client flashing into a mirror before its own
vote was answered, and a bare width mismatch once flipped a lone client to passive on
restore (patched at the symptom in `d8a376a`).

`ClientSizeArbiter` has always computed the governor; it just never told anyone. It now
exposes `governor()`, `TermSession` broadcasts a `SessionEvent.Governance` whenever it
moves — including when the effective size does not change — and `AttachPayload` carries it
so an attaching client knows immediately.

The `/pty` route renders that broadcast id into a **per-connection boolean**
(`PtyServerMessage.Governance(driving, governed)`), so a client learns whether *it* drives
and never another client's identity. `governed = false` means nobody governs (a restored
session nobody has touched, or the governor just disconnected); clients then fall back to
the width comparison, which is also what an older server produces by saying nothing.

The frame is sent *before* the attach redraw, because the verdict decides how that paint is
presented — after it, a client would render one frame under the wrong presentation and
visibly correct itself.

iOS ignores the new event (it casts events by type), so it keeps its current behaviour
until the mirror is driven further there.

## Temporary diagnostics

`TerminalSessionManager.SizeChurnLog` logs every effective PTY size change, the first 3 KB
of post-resize program output, and every reconciliation-window verdict. Armed by
`LUNAMUX_SIZE_CHURN_LOG=<path>` (forwarded by `scripts/run-electron-dev.sh` as
`-PsizeChurnLog=`). Remove it, its Gradle wiring in `server/build.gradle.kts`, the script
pass-through, and `SessionGrid`'s `onWindowResolved` argument before upstream — the
parameter defaults to a no-op, so nothing else needs unpicking.

### Reading a device run

Verdict lines look like:

```
1721... WINDOW resize pending=19 dropped=19 kept=0 sample=["MARKER-TOP-OF-FRAME" | ...]
```

- `dropped > 0` with a sample that looks like the program's frame — working as designed.
- `dropped > 0` with a sample that looks like the **user's own output** — the one failure
  this design can still have. Bounded to that window (a verdict cannot reach committed
  history) but not self-healing, because immutable history has no reabsorption.
- `dropped = 0` on every take-over — the match is never firing, so duplicates are being
  committed instead. Check whether the program's repaint is arriving inside the window at
  all; the `post-resize output` capture in the same log answers that.
- `trigger=backstop` rather than `resize` — the window expired on the chunk count instead of
  being closed by the next resize. Not wrong, but it means the verdict was taken later than
  intended, so treat a dropped sample from one with more suspicion.

`Swallowed` is not a diagnostic and stays: it counts the exceptions the PTY path
deliberately swallows and logs the first per site, so those `catch` blocks cannot hide the
next class of bug indefinitely.
