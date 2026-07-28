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

Three invariants make that authority real rather than nominal. Each has a section below;
each was arrived at by finding what broke without it.

1. **One emulator speaks for the terminal.** The canonical grid answers device queries, and
   client replies are dropped server-side. N answerers to a question with one answer put the
   surplus into the shell as typed input.
2. **Geometry rides the byte stream.** Client grids are resized only by server frames, never
   by a local layout pass — including on the driving client. A client that refits itself is a
   second geometry authority, and two authorities disagree about the width a redraw was
   authored at.
3. **Canonical state is composite-tested.** History *plus* screen, read as one document,
   across resize ping-pong with realistic relative (zsh-style) repaints. Both structural
   holes this arc closed were invisible to tests that checked either half alone.

## Acceptance criteria

1. **Both devices edit at their own native size.** The driver is native; the other mirrors.
   *Met.* Since geometry became fully server-driven, the driver reaches its native size via
   the server rather than by fitting itself: it measures, asks, and reflows when the answer
   comes back. Same destination, one round trip later — the tmux feel, accepted
   deliberately (see "Clients as pure renderers").
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

## Two structural holes, closed

On-device testing of this branch kept producing canonical-state mangling — spliced echo,
duplicated prompt lines, interleaved transcript. Two investigations root-caused *all* of it
to two holes, both invisible to the tests that existed.

**1. The rows-only shrink corrupted the canonical ring.** `TerminalBuffer.resize`'s fast
path rotated the screen inside the ring: it advanced `mScreenFirstRow` modulo the OLD ring
size, then assigned the new (smaller) `mTotalRows` without reallocating `mLines`. Every
later `externalToInternalRow` then reduced modulo a size the array did not have, so the
screen aliased onto a rotated subset of its own rows — and the rows shifted off the top were
dropped on the floor. That is only reachable for a buffer with no room beyond the screen
(the alternate buffer, and the server's screen-only main buffer), and for the latter those
rows are the only copy, because its history lives outside the emulator behind
`RowEvictionListener`.

It hid because a **rows-only change fires no client resync**: nothing repainted from the
damaged grid until the next columns change baked it into immutable history and pushed it to
every attached client. The trigger is mundane — any rows-only resize, i.e. a phone
soft-keyboard settle.

`resizeRowsOnlyReallocating` handles that case: evict the departing top rows oldest-first,
then copy the surviving window into a fresh `mLines` with `mScreenFirstRow = 0`. **The
surviving screen is deliberately the same rows the same-size-ring path yields**, because
clients run transcript-ful buffers, take that path, and receive no resync — any divergence
would be a permanent, invisible split between what the server believes it shows and what a
client shows. For the same reason it does not defer to the reflow branch, which normalizes
content (drops trailing blank rows, truncates the cursor row at the cursor).

Two cursor bugs surfaced alongside. The reflow's blank-run flush was missing the
`newCursorRow--` its two sibling scroll sites already do, leaving the cursor a row too low —
on a blank row rather than on its own content. And a pending auto-wrap belongs to the width
it was armed at: it means the cursor is logically one cell right of the character it is
parked on, off the end of the row. After a widening that cell exists, so the wrap is spent
as a plain cursor advance in `emitCodePoint`; left armed, the next character overwrote the
one the cursor sat on — a character swallowed on every window widening. It is guarded on the
cursor still standing where the wrap was armed, because a stale flag can also mean a cursor
movement left the last column without clearing it (DECBI/DECFI do not). Deliberately *not*
in `resizeScreen`: `resize` stays a pure relayout, so the reflow keeps seeing the cursor on
its own character — moving it onto the padding to the right makes the reflow materialize that
padding and burn a screen row, which breaks the resize round trip.

`assertInvariants` now checks `mLines.length == mTotalRows`. Every existing invariant passed
while the ring was incoherent.

**2. The half-assembled history line was in no paint at all.** `HistoryLog` assembles a
logical line from consecutive soft-wrapped evictions and commits it only when an unwrapped row
arrives. Until then those runs are outside `lines()` — so they were outside every paint too,
and the first screenful of a long paragraph simply went missing until the paragraph's last row
happened to scroll off as well. A reflow makes that routine: it can evict a whole screenful of
one line's rows at once.

`pendingLine()` exposes it **without committing it**, and `GridSerializer` emits it ahead of
the live screen *with no terminating newline*, so the row flow continues the same logical line
and the receiver rewraps all of it at its own width.

The first attempt was to commit it at reflow boundaries instead (`closeOpenLine`). That is
lossless in content but not in structure, and it shipped briefly: committing freezes the column
the *old* width happened to wrap at into a permanent hard break, which on a restored session
reads as a word split down the middle — `deliberately not C` / `ompose Multiplatform`. Do not
resume it.

The persist form has one extra wrinkle, because it deliberately drops the **live** line (the
unterminated one holding the cursor — the shell reprints its own prompt on restore anyway):

- It must drop that line *whole*. Dropping only the cursor's row, as it did, persisted the rest
  of a soft-wrapped live line as a fragment that restored as a sentence cut off mid-word. The
  row flow now walks back over the wrap flags to the row the line starts on.
- It must still emit the pending partial in the ordinary case. A long output line commonly has
  its head evicted while its tail sits on screen *un-evicted* — the program has finished
  writing it, but the log has not seen its end — and omitting the pending there loses the first
  screenful of the paragraph outright. It is dropped only when the live line runs back past the
  top of the screen, i.e. when the pending *is* that line's head.

**3. `serializeForPersist` homed the cursor after emitting history.** `serialize` guards
against this (`homeFirst = history.isEmpty()`, with a comment on why); the persist form never
got the guard. The row flow paints without erasing, so homing back over the history just
emitted left the history text showing through wherever a screen row did not reach: restored
rows read as `screen text` + padding + `leftover history tail`, which is the "prose with
fragments of other lines spliced into it" report. The padding width is a giveaway — it is the
width the *screen* half was authored at.

**How they are pinned.** `PersistRestoreRoundTripTest` compares the session's *logical lines*
across a persist → restore round trip at the same, a wider and a narrower grid — rows are a
function of the width you look at, which is the whole point — and each of the three defects
above fails a distinct test when reintroduced. `RelativeRepaintPingPongTest` asserts the
**composite** — committed
history plus the live screen, read as one document — across a laptop↔phone ping-pong, and
drives it with the repaint shape a plain shell actually uses: zsh's ZLE answers `SIGWINCH`
*relatively* (`\r`, `ESC[<n>A`, `ESC[J`, reprint), not with the absolute `ESC[H` full-frame
repaint `TakeOverDuplicationTest` models for TUIs. A relative repaint erases only what it is
about to rewrite, so unlike the TUI case there is no faithful duplicate to account for:
every line must appear exactly once, in order. Verified against the unfixed code — four of
five scenarios fail with exactly the reported symptoms, including rotated content
(`L011, L028, L029, L030, L012, L021…`) and the cursor landing on an output row instead of
the prompt being edited.

## One answerer for the terminal

Every attached interactive client's emulator answered the running program's device queries —
CPR, DA, DSR, XTWINOPS, OSC colour — and the server wrote all of them to the PTY. A question
with exactly one correct answer got as many answers as there were clients, and ZLE consumed
the surplus as typed input and echoed it into canonical state. That is the spliced echo and
the duplicated prompt lines. The canonical grid, meanwhile, answered into a discard sink:
the one emulator entitled to speak for the session was the one being ignored.

Inverted. **The canonical grid is the session's single answerer:**

- `SessionGrid` gained an answer sink (default still discard, so tests and the round-trip
  harness are untouched). `TerminalSession` wires it to an `UNLIMITED` channel drained by
  one writer coroutine. The queue is not decoration: answers are generated inside
  `emulator.append` on the PTY reader thread while both `outboundLock` and the grid monitor
  are held, and `PtyProcess.outputStream.write` is a raw blocking `write(2)` — writing there
  is the both-ends-blocked deadlock shape. A single consumer keeps the replies in the order
  the emulator produced them, which is what makes them a valid answer stream.
- The sink is armed **only after the restore feed**. That feed replays a *dead* session's
  bytes and a legacy raw blob can contain queries; answering one would push a reply for a
  query nobody asked into the fresh shell's stdin.
- `PtyRoutes` **drops** clients' device replies instead of forwarding them, which mutes web,
  Android and iOS alike with no client change. Each client keeps its local auto-answer
  machinery; those answers simply die at the server.
- `TerminalInputClassifier` accepts the `n` and `t` finals (DSR-5 `ESC[0n`, XTWINOPS
  `ESC[…t`). Missing them made an idle mirror's reports read as real typing, which promoted
  the mirror to size governor and turned a terminal answering a question into a spurious
  take-over and a `SIGWINCH` repaint storm.

**Behaviour deltas worth knowing.** OSC 10/11 answers now carry the *server's* palette
rather than the viewing device's theme, and `CSI 14/16 t` answer with the grid's nominal 8×16
cell. Both follow from having one answerer: the answer describes the canonical terminal, not
whichever screen happens to be looking at it.

## Clients as pure renderers

The second design invariant: **geometry rides the byte stream.** A client's grid is set only
by a server `Size` frame; nothing local reflows the terminal.

Every local-fit site used to answer its own layout change by refitting and *then* telling the
server what it had done. Two clients doing that disagree about the width a synthesized
redraw was authored at, which is what puts blank bands and misplaced repaints in scrollback.
They all measure what they would like and ask instead; the reflow arrives when the server
answers. Otto accepted the tmux round trip for the driving client too.

- **Web**: `term.resize` is left to `applyServerSize` plus two paths with no server to defer
  to — the creation-time fit of a brand-new terminal, and demo mode, where the pane is its
  own size authority. The webfont-ready, `ResizeObserver`, tab-mount, reconnect,
  restore-settle, `reassertGrid`, font-family, font-size and `fitVisible` sites now measure
  via `measureNaturalGrid` and vote with explicit dims (`sendForceResize`/`sendResizeVote`
  used to read `term.cols` *after* the fit, i.e. read back their own answer). Scroll
  preservation moved into `applyServerSize` — it had none, and the local fits that carried it
  are gone. The 3D world's `setPaneGrid` only resizes when FOLLOWING a `Size` frame; a user
  grid command votes and waits, so a vote that loses no longer leaves the plane showing a
  grid the PTY never had.
- **Android**: the holder pin holds the server's grid whether the phone is driving or
  mirroring, so a layout pass is a no-op against the emulator, and the `Size` handler applies
  both axes unconditionally. `measureNaturalGrid` reproduces `TerminalView.updateSize`'s
  arithmetic against a throwaway `TerminalRenderer` at the **user's** font size, driven from
  the view's layout-change listener, and feeds `localGrid`, the connect-URL grid, the votes
  and the take-over targets.
- **iOS** keeps its current behaviour this arc (minimal-iOS stance), including its 200 ms
  vote debounce.

Clients still keep local scrollback this arc (the RIS resync clears it); mosh-style state
deltas remain future work.

### Votes are ack-clocked

The 200 ms trailing vote debounce is gone on web and Android. It was a guess at how long a
drag or a settling layout takes — too short and the storm gets through, too long and a
finished resize feels laggy — and Otto asked for a deterministic flow rather than another
magic number.

The pipeline (`requestPtyGrid` on web, `SizeVoteClock` on Android): vote immediately when
idle; **at most one vote in flight**; while one is in flight remember only the latest desired
grid. A vote resolves when any `Size` frame arrives, or when the grid we want is the grid the
server already has. A ~1 s timer is a **safety valve only** — a vote that loses to a THREE_D
override or a governing client produces no broadcast at all, and without an upper bound the
latch would stay shut — not a pacing mechanism. The server's answers set the pace.

A **take-over preempts** the one-in-flight rule and goes out at once. The rule exists to keep
ambient measurement from becoming a vote storm; making a user gesture wait up to the
safety-valve timeout behind an ambient vote it is about to overrule would be a second of dead
UI for nothing.

The Android take-over font-walk storm that motivated the debounce (19 row-only votes in
250 ms as the font walked from the mirror size back to the driving size) disappears
structurally: font changes no longer touch the emulator or the natural grid, so they
generate no votes at all. Web keeps its `resizeGestureActive` hold-and-flush, which was
already deterministic — the ack clock bounds how *many* votes a drag sends, not whether the
transient sizes it passes through are ones the PTY should ever see.

The web vote-pending grace armed at pane creation is gone too: "no `Size` frame has arrived
yet" is `ptyCols == null`, a fact that cannot expire mid-startup.

### Tombstones from this arc

- **The 200 ms vote debounce** (web `setTimeout`, Android `SIZE_VOTE_DEBOUNCE_MS`): replaced
  by ack-clocking, above. Do not reintroduce a timer to pace votes.
- **The cols-only mirror pin** on Android: the pin held only the *columns*, leaving rows to
  the view's capacity so a server screen taller than the phone could draw would bottom-anchor
  instead of clipping. Measured wrong on device — the mirrored stream is absolutely
  cursor-addressed for exactly the server's screen, and extra local rows shift every address
  while the content bottom-anchors, splicing typed characters mid-transcript. Clipping is
  handled where it belongs: the mirror font is fitted on both axes.
- **Local fits answering local layout changes** on either client: see above. A client that
  refits itself is a second geometry authority.

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
  accumulated cross-width scrollback. Clients are pure renderers for *geometry* now, but
  they still accumulate local scrollback between resyncs; until that changes too, everyone
  gets the resync.

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
the explicit take-over), and its per-layout votes are debounced — the 200 ms debounce web
and Android have since replaced with ack-clocking.

## Remaining work

1. **Resync deltas / mosh-style state deltas.** The synthesized resync still carries the
   whole history log on every cols change; with history append-only, send-once +
   stream-deltas is possible. Clients also still keep local scrollback between resyncs and
   rely on the RIS-prefixed resync to stay coherent — with geometry now server-driven, that
   is the remaining half of "pure renderer". Cost, not correctness.
2. **Reflow top-anchoring on widen.** Widening can leave the prompt mid-screen rather than
   anchored, because the reflow re-inserts skipped blank runs and then scrolls. Cosmetic,
   pre-existing, and orthogonal to the holes closed above — but it is what makes a widened
   screen look unlike what the same content would look like if written at that width.
3. **`justToCursor` right-of-cursor truncation.** The reflow copies the cursor row only up to
   the cursor, so anything to its right is dropped — RPROMPT loss on a narrowing.
4. **iOS mirror parity**: scaled passive mirror, take-over badge, and the ack-clocked vote
   pipeline, matching web/Android. iOS still runs its 200 ms debounce.
5. **`AgentSession`'s 64 KB raw ring** (`AgentSession.kt`) is a separate subsystem that was
   never migrated to the canonical grid; it still replays raw bytes.
6. **Upstream**: this branch produced a clean byte-level repro of #49086 (renderer
   isolated, htop control). Worth attaching to an open report (e.g. #81135) to help get it
   fixed — that fix, not anything in lunamux, is what retires the artifact.
