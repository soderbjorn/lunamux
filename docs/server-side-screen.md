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
2. **A device switch adds nothing beyond what one standalone-terminal resize of the
   running program produces; lunamux itself never duplicates, drops, or rewrites
   output.** *Met.* This is a restatement — the original read "no duplicated output when
   switching devices", which turned out to be unreachable at the source for one program
   class because of an upstream Claude Code bug (next section). The restated criterion is
   pinned as executable tests in `TakeOverDuplicationTest`: faithfulness (exactly one
   archived copy per narrowing — fewer means a dedup heuristic crept back in, more means
   lunamux added duplication of its own) and safety (committed history survives every
   resize, repaint or not).
3. **A live (not replayed) session stays well-formatted on both laptop and phone.** *Met.*

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

Mechanically: the resize re-lays out the live screen, a narrowing archives the overflow
into scrollback, and the program's repaint can only reach the on-screen rows — scrollback
is immutable to the program. So the archived top of the old frame stays, and the fresh
re-emit stacks a second copy on top of it.

## The upstream bug, and the decision taken

The duplication is **Claude Code's**, not lunamux's:
[anthropics/claude-code#49086](https://github.com/anthropics/claude-code/issues/49086)
(closed unresolved) — on **any** PTY resize Claude re-emits its whole view into the
normal buffer without clearing the prior frame, so each resize leaks whatever scrolls off
into scrollback as a verbatim duplicate. It reproduces in plain Terminal.app, Ghostty and
Android Studio's terminal with no lunamux involved, was reported on v2.1.110 and still
reproduces on v2.1.220, and a family of open reports tracks it (#81135, #46834, #51828,
#52924, #69577, #79896). From this branch's own byte-exact trace: the post-`SIGWINCH`
repaint is `ESC[?25l` → `ESC[H` → rows×(`ESC[2K` `ESC[1B`) → `ESC[H` → the **whole
document** painted with CRLFs — more lines than fit, so the excess scrolls off. The
per-row erase reaches only the screen; whatever is already in scrollback is out of reach.

Lunamux cannot fix this at the source (the duplicate bytes are the program's own); it can
only amplify it — a human rarely drag-resizes mid-session, but a take-over resizes the
PTY on every device switch. Every server-side reconciliation was tried and measured as a
dead end (see *Tried and removed*).

**Decision (2026-07-27):** keep the take-over UX exactly as it is — scaled mirror when
viewing, take-over via typing or the badge/Reformat gesture resizes to native width — and
accept one leaked frame per deliberate take-over, which is identical to resizing a
standalone terminal once. The grid stays a **faithful recorder** with no dedup heuristics
in the tree: what a real terminal's scrollback would hold is what `SessionGrid` holds, so
the day upstream fixes the Ink repaint, lunamux is already correct with zero changes.

What keeps the cost at that floor, and what a user can do about the artifact:

- The size arbitration already costs exactly **one effective resize per take-over**
  (`TakeOverChurnTest`; Android and iOS debounce their vote bursts, and same-size
  switches move governance without any resize at all).
- Claude Code's `"tui": "fullscreen"` (settings.json) renders in the **alternate buffer**
  — no scrollback, nothing to leak. Users who switch devices heavily can turn it on and
  the artifact disappears entirely, at the cost of Claude-managed scrolling.
- `clear` / ED3 now truly clears (the external history included), so a session can always
  be reset to pristine.
- If a stricter posture is ever wanted, "take-over never resizes; only an explicit
  Fit-to-this-device gesture does" is a small **client-side** change (stop the automatic
  `forceResize` on typing in the web/Android input paths) — the server model already
  expresses it. Rejected for now: typing on a device and getting its native width is the
  UX this feature exists for.

## Measured facts

Keep these; they were expensive.

- **Reflow is reversible in isolation.** A 100-col box-drawn table narrowed to 60 and
  widened back is byte-identical, and stays so across eight cycles
  (`ReflowReversibilityTest`). It is *not* reversible with a repaint fed between cycles —
  the program's re-emit mints the duplicate, not the reflow.
- **Resize count is already optimal**: one effective size change per take-over
  (`TakeOverChurnTest`), after the Android vote-flood debounce. The cost is one *repaint*,
  not extra `SIGWINCH`es.
- **Claude re-emits banner + body on any resize** via cursor-addressed redraw (`v2.1.218`
  in 13 post-resize chunks, `Anthropic` in 9). It does not emit the plain string "Claude
  Code v" because it splits words with cursor moves — naive greps will mislead. An early
  hypothesis that the leak was gated on the screen being too *short* was disproved on
  device: it fires on any resize, at any height, width most severe.
- **The repaint is self-declaring in practice**: every post-SIGWINCH chunk observed opened
  `ESC[?25l` … `ESC[H` then exactly `rows` × (`ESC[2K` `ESC[1B`), across 22 consecutive
  resizes in both directions. (Recorded for completeness; nothing gates on it anymore.)
- **Pre-split behaviour looked bounded by accident.** With one reflowable transcript, a
  widening *reabsorbed* the narrowing's archived rows back onto the taller screen for the
  next repaint to erase — which is why a single narrowing duplicated while eight full
  narrow→wide cycles did not accumulate. Immutable history has no reabsorption, so the
  faithful count is one archived copy per narrowing, and the tests pin exactly that.

## The split (implemented)

**Live screen and history are separate.**

- **Scrollback** is an append-only log of *logical lines* with styles (`HistoryLog`),
  committed once when a line scrolls off, stored at authored width, **never reflowed**.
  Display-time wrapping is a pure function of `(logical line, width)`: history is emitted
  unwrapped and the receiving terminal wraps it.
- **Live screen** is the canonical emulator, width-adaptive, synthesized per client.
  `terminal-core` gained two additive changes: `transcriptRows == 0` builds a screen-only
  main buffer, and `setRowEvictionListener` reports each row as it leaves the screen while
  it is still intact.
- `GridSerializer` emits history and the live screen as **one continuous line stream**. It
  must not home the cursor before painting the screen when history precedes it — doing so
  paints over the history tail. Caught by the round-trip tests.
- The attach redraw (`SessionGrid.attachSnapshot`) serves the same history + screen paint
  as a live resync, with the dims it was authored at, under one grid-monitor hold.

Also fixed on the way: `TerminalRow.clear()` never reset the row's wrap flag, so a
recycled ring slot could claim a soft wrap it no longer had and fuse two unrelated lines
into one. A deep transcript hid this; a screen-only buffer exposed it immediately.

The split also fixes a cost the pre-split design carried silently: serializing a 3000-row
reflowable transcript on **every** cols change (multiple MB to a phone per take-over).
With history separate, the remaining known cost is that the resync still sends the whole
log per cols change — sending it once and streaming deltas afterwards is now possible and
remains future work.

## Tried and removed — do not resume

All of these are recorded because each was measured, not guessed.

- **Count-based truncation** (`RepaintDeclaration` + `truncateTranscriptToCompletedLines`,
  reverted in `92d5e23`): detect the repaint prologue, then withdraw the reflow's
  archival by truncating the transcript to its pre-resize completed-line count. It deleted
  genuine history (the archived rows are a mix of redundant frame-top and real content a
  count cannot separate), its width-invariance premise was false (widening pulled
  scrollback back onto the screen), and it was program-specific.
- **The reconciliation window** (removed on this branch after the upstream root cause was
  established): lines evicted during a resize were held pending, and the longest prefix
  re-appearing contiguously on the post-repaint screen was dropped. Synthetic fixtures
  passed; real traces did not — the re-emit's top scrolls straight off, so it duplicates
  content already in *history*, and matching pending-vs-screen never fires. Reconciling
  against committed history instead degraded once history held its own partial misses,
  and a pure commit-gate discarded the entire conversation under continuous take-over
  (genuine output scrolls through the same windows; timing cannot separate the two).
  Every variant is a heuristic that can eat genuine output, which upstreaming rules out.
- **Withholding the resync from the driver** (`0b0d344`, reverted in `64387ff`): the
  driver renders the program's native output, so the theory was it never needs the
  synthesized reconstruction. On device this regressed the driver into mixed-width
  stacking, because the periodic RIS-resync was also what cleared the client's own
  accumulated cross-width scrollback. Clients are not yet pure renderers (they still
  accumulate local scrollback between resyncs); until that changes, everyone gets the
  resync.

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

The frame is sent *before* the attach Size and redraw — and on live mutations the
Governance event is seq'd before the Size event — because clients compute their
mirror-vs-driving verdict inside their Size handler: Size-first meant every take-over was
judged with the previous verdict, which on Android momentarily dropped the mirror's grid
pin (and with it, correct addressing) on the very frame that changed hands. The same
ordering discipline applies one level down: `applySize` issues the `SIGWINCH` ioctl *last*,
after the grid resize and the Size event are seq'd, so the program's repaint bytes can
never reach clients ahead of the Size frame nor be fed into an old-geometry grid
(poisoning the next resync).

iOS ignores the new event (it casts events by type), so it keeps its current behaviour
until the mirror is driven further there. What iOS did get: opening a pane no longer
force-resizes the PTY (first-layout size is an ambient vote now; the Reformat button is
the explicit take-over), and its per-layout votes are debounced like Android's.

## Remaining work

1. **Resync deltas.** The synthesized resync still carries the whole history log on every
   cols change; with history now append-only, send-once + stream-deltas is possible. Cost,
   not correctness.
2. **Clients as pure renderers.** Web and Android still accumulate local xterm/emulator
   scrollback from the byte stream between resyncs and rely on the RIS-prefixed resync to
   stay coherent across width changes. Finishing this would also make the driver
   resync-skip viable again if it is ever wanted.
3. **Web vote-in-flight grace window** is redundant on the governed path now that the
   server assigns governance; it survives only for ungoverned/legacy servers.
4. **iOS mirror parity**: scaled passive mirror + take-over badge, matching web/Android.
5. **Upstream**: this branch produced a clean byte-level repro of #49086 (renderer
   isolated, htop control). Worth attaching to an open report (e.g. #81135) to help get it
   fixed — that fix, not anything in lunamux, is what retires the artifact.
