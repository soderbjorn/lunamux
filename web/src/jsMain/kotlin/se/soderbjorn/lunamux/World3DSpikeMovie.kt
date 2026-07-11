/**
 * The **demo movie** for the 3D world — a secret, demo-mode-only guided tour
 * (⌥⌘M) that "plays the keyboard" through a timed choreography showcasing the
 * spike, in the order a first-time viewer best reads it:
 *  1. **navigate** the panes of a tab (←/→) and the tabs themselves (↑/↓),
 *     resting on the red "waiting for input" agent along the way;
 *  2. **zoom** into the fronted window and back out (`+`/`−`/`0`);
 *  3. **resize its grid** — wider/taller and back (`.`/`,`/`>`/`<`), content
 *     reflowing live;
 *  4. **talk to Claude at a tilt** — tilt the camera off-axis (`j`), engage a
 *     *finished* Claude session (never a working or waiting one), type into it
 *     from the angle, and watch it pick the work back up;
 *  5. **shoot a window out of the sky** — the phaser-fire pane close (⌥X to
 *     arm, ⌥X to confirm);
 *  6. **stash a whole tab** to the shelf (⌃Space) and fly up to fetch it back
 *     (`v`, then ⌃Space at the dock);
 *  7. **grow a new window** through its wormhole (`n`);
 *  8. **overview → free flight** — frame the whole world (`m`), drop into free
 *     flight (`f`), glide behind a finished Claude window and type into it from
 *     behind, "through the glass".
 *
 * **How it drives the app:** every beat that has a keyboard shortcut is played
 * as a *synthetic `KeyboardEvent` dispatched on `window`*, so it runs through
 * the real [buildKeyHandler] — the exact code path a human keypress takes.
 * That is deliberate: the shortcuts legend flashes its rows ([flashShortcut])
 * for every scripted press, mode badges appear, and the movie can never drift
 * from what the keys actually do. Only two things bypass the keyboard:
 *  - **typing** into an engaged terminal ([movieTypeInto]) feeds characters
 *    straight into the demo session's line discipline
 *    (`TerminalEntry.sendInput` → `DemoTerminalSession.inputText`), because
 *    xterm.js listens on its own hidden textarea, not on `window`;
 *  - the **fly-behind approach** ([playFlyBehindAndType]) calls [flyCamTo]
 *    directly to park behind a specific pane on another tab floor — there is
 *    no single-key "fly behind *that* window" shortcut.
 *
 * **Only ever types into a *finished* Claude** ([DemoFixtures] `demo-s6`
 * greets, `demo-s8` logo, `demo-s9` copper): the working panes (`demo-s1`,
 * `demo-s7`) and the waiting one (`demo-s4` plasma) are shown but never typed
 * into, so the tour never puts words in a busy agent's mouth. Each finished
 * pane is woken at most once (a second message would land on it mid-burst).
 *
 * **Timing:** beats use fixed pauses only for dramatic dwell; every camera
 * journey and ring settle is *awaited* ([movieAwait] on [spikeCamReturning] /
 * [spikeSettledIndex]) so the movie stays in sync on slow machines instead of
 * running ahead of its own animations.
 *
 * **Gating:** the movie only ever arms in demo mode ([isDemoClient]) — it
 * types into terminals and reorders panes, which would be vandalism against a
 * real server. The ⌥⌘M chord is checked in all three key-handler modes
 * (navigate / engaged / fly) so the tour can be stopped mid-beat; closing the
 * world ([closeWorld3dSpike]) also stops it.
 *
 * @see buildKeyHandler for the ⌥⌘M hook
 * @see DemoFixtures for the workspace the choreography is written against
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import kotlin.random.Random
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit

/** The running movie coroutine, or `null` when no tour is playing. */
internal var spikeMovieJob: Job? = null

/** The small top-centre Lunamux branding pill shown while the movie plays, or `null`. */
internal var spikeMovieBadge: HTMLElement? = null

/** The big lower-right narration row — the live "what the tour is doing" line, or `null`. */
internal var spikeMovieSubtitle: HTMLElement? = null

/**
 * Starts the demo movie, or stops the one that is playing — the ⌥⌘M toggle.
 * Called from [buildKeyHandler] (all three modes), which already gates the
 * chord on [isDemoClient]; the guards here make direct calls safe too.
 * A finished or cancelled movie always cleans up after itself
 * ([movieCleanup] in the coroutine's `finally`).
 */
internal fun toggleDemoMovie() {
    val running = spikeMovieJob
    if (running != null) {
        running.cancel()
        return
    }
    if (!isDemoClient || !spikeOpen) return
    spikeMovieJob = GlobalScope.launch {
        try {
            showMovieBadge()
            movieRestoreDefaults()
            playDemoMovie()
        } finally {
            movieCleanup()
        }
    }
    // Flip the demo-tour button to its "stop" label (and end its attention
    // pulse); movieCleanup flips it back on every stop path.
    updateDemoTourButton()
}

/**
 * Removes the tour chrome (branding pill + narration row) and forgets the
 * movie job. Idempotent — called from the movie coroutine's `finally`
 * (natural end, ⌥⌘M cancel, or the cancel in [closeWorld3dSpike]), and safe
 * when the overlay is already gone.
 */
private fun movieCleanup() {
    spikeMovieBadge?.remove()
    spikeMovieBadge = null
    spikeMovieSubtitle?.remove()
    spikeMovieSubtitle = null
    spikeMovieJob = null
    updateDemoTourButton()
}

/**
 * Builds and shows the movie's two on-screen elements:
 *  - the small **top-centre branding pill** — one line naming the product
 *    (and its 2D/3D modes) with the URL (for shared recordings), styled in the
 *    live theme accent ([spikeChrome]): accent monospace on the theme's
 *    title-bar fill with a soft accent glow, so it keys to whatever theme the
 *    tour plays under (blue on the default theme);
 *  - the big **lower-right narration row** — a single large white line
 *    (styled after the nav "now showing" label, [showNavLabel]) that
 *    [movieNarrate] rewrites per beat so a viewer always knows what the tour
 *    is currently demonstrating.
 */
private fun showMovieBadge() {
    val overlay = spikeOverlay ?: return
    // Wear the live theme accent (blue on the default theme), matching the rest
    // of the ring chrome — title underlines, beacon glow, the tour button — so
    // the pill reads as part of the themed world. Softened glow via appended
    // alpha hex on the accent, exactly as the beacons do.
    val accent = spikeChrome().accent
    val pill = document.createElement("div") as HTMLElement
    pill.textContent = "● Lunamux · multiplexing terminal server for Mac with Android and iOS " +
        "apps · 2D and 3D always in sync · www.lunamux.dev"
    pill.style.cssText = "position:absolute;top:14px;left:50%;transform:translateX(-50%);z-index:5;" +
        "pointer-events:none;padding:6px 16px;border-radius:10px;border:1px solid $accent;" +
        "background:#0d1420e6;color:$accent;font:600 12px ui-monospace,Menlo,monospace;white-space:nowrap;" +
        "text-shadow:0 0 10px ${accent}8c,0 0 26px ${accent}4d;"
    overlay.appendChild(pill)
    spikeMovieBadge = pill

    val narration = document.createElement("div") as HTMLElement
    narration.style.cssText = "position:absolute;right:30px;bottom:26px;z-index:5;pointer-events:none;" +
        "font:700 26px ui-monospace,Menlo,monospace;color:#eef3fb;letter-spacing:0.5px;white-space:nowrap;" +
        "text-align:right;text-shadow:0 2px 20px rgba(0,0,0,0.9);transition:opacity 250ms ease;opacity:0;"
    overlay.appendChild(narration)
    spikeMovieSubtitle = narration
}

/**
 * Rewrites the lower-right narration row to [text] — the per-beat, viewer-facing
 * "what the tour is doing" line — with a quick fade so the change registers.
 * Called by [playDemoMovie] at the start of every beat; safe when the movie
 * chrome is already gone.
 *
 * @param text what the tour is doing right now, viewer-facing.
 */
private fun movieNarrate(text: String) {
    val sub = spikeMovieSubtitle ?: return
    sub.style.opacity = "0"
    window.setTimeout({
        sub.textContent = text
        sub.style.opacity = "1"
    }, 250)
}

/**
 * Dispatches one synthetic `keydown` (and, for [holdMs] > 0, the matching
 * delayed `keyup`) on `window`, so the press runs through the real
 * [buildKeyHandler] — flashing the shortcuts legend exactly like a human
 * press. The keyup matters only for the free-fly held keys, whose release is
 * tracked by the spike's window keyup listener.
 *
 * @param key the `KeyboardEvent.key` value (`"ArrowLeft"`, `"+"`, `" "`, …).
 * @param code the physical `KeyboardEvent.code` (`"KeyF"`, `"Space"`, …) —
 *   what the handler matches for letter and chord shortcuts.
 * @param shift/alt/meta/ctrl modifier flags for chord shortcuts (⌥⌘X, ⇧→,
 *   ⌃Space, …).
 * @param holdMs how long to hold before the `keyup`; `0` sends no keyup.
 */
private suspend fun moviePress(
    key: String,
    code: String,
    shift: Boolean = false,
    alt: Boolean = false,
    meta: Boolean = false,
    ctrl: Boolean = false,
    holdMs: Long = 0,
) {
    fun dispatch(type: String) {
        window.dispatchEvent(
            KeyboardEvent(
                type,
                KeyboardEventInit(
                    key = key, code = code,
                    shiftKey = shift, altKey = alt, metaKey = meta, ctrlKey = ctrl,
                    cancelable = true,
                ),
            ),
        )
    }
    dispatch("keydown")
    if (holdMs > 0) {
        delay(holdMs)
        dispatch("keyup")
    }
}

/**
 * Rolls the demo world back to its fixture defaults before the choreography
 * plays, so the tour — written against the fixture workspace — always finds
 * the world it expects no matter what the visitor did first:
 *
 *  1. backs out of any modal state: free-fly is landed, and [leaveFrontPane]
 *     drops selection mode and disengages an engaged terminal;
 *  2. resets the demo server ([DemoServer.resetToFixtures] via
 *     [lunamuxClient]): the fixture tabs/panes/states are re-published —
 *     the open ring reconciles live ([reconcileRing]), so visitor-created
 *     panes shrink away and closed fixture panes grow back — and every
 *     fixture session restarts its canned content in place, rewinding the
 *     attached terminals;
 *  3. re-fronts the fixture's opening pane (first pane of the first tab),
 *     forgets local per-pane zoom memory, and flies the camera home, waiting
 *     for the journey and the ring to settle so the first beat starts from
 *     rest.
 */
private suspend fun movieRestoreDefaults() {
    if (spikeFlyMode) toggleFlyMode()
    leaveFrontPane()

    lunamuxClient.demoServer?.resetToFixtures()

    spikeZoomByPane.clear()
    spikeTabIndex = 0
    for (i in spikeTabSel.indices) spikeTabSel[i] = 0
    spikeSettledIndex = -1
    loadFrontZoom()
    resetCamera()
    movieAwaitCamera()
    movieAwaitSettled()
    delay(600)
}

/**
 * Polls [cond] (every 120 ms) until it holds or [timeoutMs] elapses — the
 * movie's synchronization primitive for animations it cannot subscribe to
 * (camera tours, ring settling, mirror promotion). Aborts early when the
 * world closes; the timeout means a stuck animation degrades the tour's
 * pacing instead of hanging it.
 *
 * @param timeoutMs upper bound to wait.
 * @param cond the condition to wait for.
 */
private suspend fun movieAwait(timeoutMs: Int, cond: () -> Boolean) {
    var waited = 0
    while (spikeOpen && !cond() && waited < timeoutMs) {
        delay(120)
        waited += 120
    }
}

/** Waits for the current camera journey ([spikeCamReturning]) to land. */
private suspend fun movieAwaitCamera() {
    delay(300) // let the tour arm before sampling the flag
    movieAwait(14_000) { !spikeCamReturning }
}

/** Waits for the ring to settle the front pane (rotation/tab-slide finished). */
private suspend fun movieAwaitSettled() {
    movieAwait(6_000) { spikeSettledIndex >= 0 && spikeSettledIndex == frontIndex() }
}

/**
 * Waits for a stashed **tab bundle** to finish merging and flying and come to
 * rest ([BundleState.PARKED]) on the shelf — [bundlesBusy] is true the whole
 * journey and false only once the stack has landed. The tour holds on the stash
 * beat until this returns before flying the camera home, so the camera doesn't
 * leave while the tab is still sailing up.
 *
 * @see bundlesBusy @see stashTab
 */
private suspend fun movieAwaitBundleLanded() {
    delay(300) // let the stash arm before sampling the flag
    movieAwait(14_000) { spikeStashedTabs.isNotEmpty() && !bundlesBusy() }
}

/**
 * Waits for a newborn pane's **wormhole birth** to run its full course. The `n` create
 * round-trips through the server before [reconcileRing] builds the pane and arms the
 * spawn, so this first holds until [spikeWormholes] becomes non-empty (armed), then
 * until it tears down again — the vortex lifetime is FOCUS+OPEN+EMERGE ≈ 530 frames
 * (~8.8 s at 60 fps), which a fixed delay can't cover. Finally it waits for the
 * follow-the-pane return flight to land and the ring to settle the newborn. If no
 * wormhole arms within the arm window (e.g. [WORMHOLE_SPAWN_ENABLED] is off), it returns
 * after the short wait rather than stalling the tour.
 *
 * @see wormholeSpawnEligible @see tickWormhole @see armWormholeSpawn
 */
private suspend fun movieAwaitWormhole() {
    movieAwait(4_000) { spikeWormholes.isNotEmpty() } // hold until the spawn arms
    if (spikeWormholes.isEmpty()) return // it never fired — don't stall the tour
    movieAwait(16_000) { spikeWormholes.isEmpty() } // …then until it tears down
    movieAwaitCamera() // the follow-the-pane return flight lands
    movieAwaitSettled() // the ring finishes seating the newborn
}

/**
 * A **jittered** dwell — [ms] give or take ~a third — so the scripted key
 * cadence reads as a human at the keyboard rather than a metronome. Used for the
 * pauses between navigation presses (and their settles), where a fixed delay
 * looked robotic.
 *
 * @param ms the centre of the pause in milliseconds.
 */
private suspend fun moviePause(ms: Long) {
    val jitter = ms / 3
    delay(ms - jitter + Random.nextLong(jitter * 2 + 1))
}

/**
 * Types [text] into the *front* pane's terminal at a human, slightly uneven
 * cadence by feeding characters straight into the demo session's line
 * discipline (which echoes them, so the pane shows live typing). Waits for
 * the pane's real terminal to be mounted first — engaging a mirror pane
 * promotes it within a few hundred ms ([tryPromoteMirror]).
 *
 * Works for the free-flight "type from behind" beat too: engaging the centred
 * pane (`Enter`) makes it the app's active/focused pane, and because the tour
 * *fronts that pane in command center before flying to it*, [frontIndex] still
 * resolves to it in free flight — so this same helper reaches it.
 *
 * @param text the characters to type (send `"\r"` separately to submit).
 */
private suspend fun movieTypeIntoFront(text: String) {
    val p = spikePanes.getOrNull(frontIndex()) ?: return
    movieAwait(5_000) { terminals[p.paneId]?.sendInput != null }
    val send = terminals[p.paneId]?.sendInput ?: return

    // A practised typist, genuinely random: most keys land 30–100 ms apart,
    // spaces get a small extra breath, and roughly one key in twelve
    // hesitates an extra ~120–280 ms. The offsets are pre-computed…
    var at = 0L
    val schedule = text.map { ch ->
        var pause = 30L + Random.nextLong(70)
        if (ch == ' ') pause += Random.nextLong(60)
        if (Random.nextInt(12) == 0) pause += 120L + Random.nextLong(160)
        at += pause
        ch to at
    }
    // …and replayed against the wall clock, sending every character whose
    // moment has passed on each wake-up. A per-character `delay()` would
    // quantize to however often the busy render loop lets timers fire (the
    // tilted 3D beat can starve them to a few wakes per second), stretching
    // the line into slow, metronomic typing; wall-clock catch-up keeps the
    // total pace and its human unevenness intact under any frame rate.
    val start = window.performance.now()
    var sent = 0
    while (sent < schedule.size) {
        if (!spikeOpen) return
        val elapsed = (window.performance.now() - start).toLong()
        while (sent < schedule.size && schedule[sent].second <= elapsed) {
            send(schedule[sent].first.toString())
            sent++
        }
        if (sent < schedule.size) delay(16)
    }
}

/** Submits the typed line to the front pane's session (Enter at its prompt). */
private fun movieSubmitFront() {
    spikePanes.getOrNull(frontIndex())?.let { terminals[it.paneId]?.sendInput?.invoke("\r") }
}

/**
 * Fronts the pane with id [paneId] by **playing the arrow keys** — `↑`/`↓` to
 * climb to its tab floor, then `←`/`→` to rotate onto it — reading live state
 * ([spikeTabIndex], [frontIndex]) between presses to decide the direction. This
 * is deliberately *not* a fixed press count: stashing a whole tab drops it out
 * of the command center and renumbers the remaining tab floors, so the logo
 * pane's ordinal is not knowable ahead of time. Reading the target pane's live
 * [RingPane.tabOrd] / [RingPane.paneOrdInTab] each step keeps the tour landing on
 * the right window whatever the ordinals have become. The guards bound it so a
 * missing pane (or a settle that never lands) degrades to giving up, not hanging.
 *
 * @param paneId the fixture pane id to bring to the front (e.g. `"demo-p10"`).
 */
private suspend fun movieNavigateToPane(paneId: String) {
    val target = spikePanes.firstOrNull { it.paneId == paneId && !it.dying } ?: return
    var guard = 0
    while (spikeOpen && spikeTabIndex != target.tabOrd && guard++ < 12) {
        val up = target.tabOrd < spikeTabIndex
        moviePress(if (up) "ArrowUp" else "ArrowDown", if (up) "ArrowUp" else "ArrowDown")
        movieAwaitSettled(); moviePause(360) // jittered so the walk isn't metronomic
    }
    guard = 0
    while (spikeOpen && guard++ < 12) {
        val cur = spikePanes.getOrNull(frontIndex()) ?: break
        if (cur.paneId == paneId) break
        val right = target.paneOrdInTab > cur.paneOrdInTab
        moviePress(if (right) "ArrowRight" else "ArrowLeft", if (right) "ArrowRight" else "ArrowLeft")
        movieAwaitSettled(); moviePause(360)
    }
}

/**
 * The **fly-behind-and-type** beat — the free-flight counterpart of the `j`
 * tilt beat, and the one camera move with no single-key "fly behind *that*
 * window" shortcut. Reads the current *front* pane (the tour fronts the target
 * window in command center just before entering free flight, so [frontIndex]
 * still resolves to it), then [flyCamTo]s to park [PANE_BEHIND_DIST] behind its
 * back looking at it — the through-the-glass view where CSS3D backfaces show the
 * pane's mirrored content. `Enter` then engages the pane at screen centre (which
 * is exactly this pane), and because it is still the front pane
 * [movieTypeIntoFront] reaches it. The typed line runs in the pane and its reply
 * plays out, mirrored, behind the letters.
 *
 * @param text the line to type into the window from behind (a live shell or a
 *   finished Claude — never the live or waiting Claude panes).
 * @see flyBehindPane the interactive `B` fly-by this mirrors
 * @see paneAtScreenCenter why `Enter` targets this pane from behind
 */
private suspend fun playFlyBehindAndType(text: String) {
    val p = spikePanes.getOrNull(frontIndex()) ?: return
    val px = p.obj.position.x as Double
    val py = p.obj.position.y as Double
    val pz = p.obj.position.z as Double
    val (nx, ny, nz) = paneFacingNormal(p)
    flyCamTo(
        px - nx * PANE_BEHIND_DIST, py - ny * PANE_BEHIND_DIST, pz - nz * PANE_BEHIND_DIST,
        px, py, pz,
        landPristine = false, frames = PANE_TOUR_FRAMES,
        pullout = PANE_TOUR_PULLOUT, rise = PANE_TOUR_RISE,
    )
    movieAwaitCamera(); delay(1_000)
    movieNarrate("behind a window — typing through the glass")
    moviePress("Enter", "Enter"); delay(900) // engages the centred pane (this one)
    movieTypeIntoFront(text)
    delay(500)
    movieSubmitFront()
    delay(4_500) // hold on the mirrored backface while the reply lands
}

/**
 * The choreography itself — one linear pass through every showcase beat, in the
 * order a first-time viewer best reads the world: what a pane is → what the keys
 * do to it → the agent is *alive* → windows can be destroyed, stashed, born →
 * the world is a whole you can fly. Written against the demo fixture workspace
 * ([DemoFixtures.initialConfig]): the Compo tab fans a live `Claude Code`, a
 * build shell, and the finished `claude: greets`; the Trackmo tab holds the
 * red *waiting* `claude: plasma`; and the Assets/Delta tabs each pair their
 * viewer with a finished `claude: logo` / `claude: copper` we can safely wake.
 *
 * Every keyed beat goes through [moviePress] (→ real handler → legend flash);
 * see the file doc for why, and for the "finished panes only" typing rule.
 * Cancellation (⌥⌘M, Esc/close) lands in the caller's `finally`.
 */
private suspend fun playDemoMovie() {
    // ══ 1. Navigate: the windows of a tab (←/→) and the tabs themselves (↑/↓).
    // ── Establish: rest on the live Claude pane, let it stream a moment.
    movieNarrate("a Claude session, live at work")
    movieAwaitSettled()
    delay(3_000)

    // ── Fan across the Compo tab: the build shell, then the finished greets Claude.
    //    Jittered pauses so the walk reads human, not metronomic.
    movieNarrate("walk the windows of a tab")
    moviePress("ArrowRight", "ArrowRight"); movieAwaitSettled(); moviePause(1_300)
    moviePress("ArrowRight", "ArrowRight"); movieAwaitSettled(); moviePause(1_500)

    // ── Down through the tab floors: Trackmo, resting on the red waiting agent.
    movieNarrate("move between tabs")
    moviePress("ArrowDown", "ArrowDown"); movieAwaitSettled(); moviePause(1_600)
    movieNarrate("blue glow: working — yellow: needs input")
    moviePress("ArrowRight", "ArrowRight"); movieAwaitSettled(); moviePause(2_400)
    // ── Peek at the single-viewer tabs (each now paired with a finished Claude).
    moviePress("ArrowDown", "ArrowDown"); movieAwaitSettled(); moviePause(1_500)
    moviePress("ArrowDown", "ArrowDown"); movieAwaitSettled(); moviePause(1_500)
    // ── Come to rest on the small, finished greets window for the window edits —
    //    a *small* pane so the zoom and grid changes read big (the hero Claude
    //    pane already fills its half of the tab, so growing it shows nothing).
    movieNavigateToPane("demo-p9")
    movieAwaitSettled(); moviePause(900)

    // ══ 2. Zoom: ⇧+ snaps the small window to the largest zoom that still fits it
    //       on screen (lean in to read it), ⇧− drops to the zoom floor, then 0
    //       restores 1:1. The ⇧ presets glide (slow ease), so hold on each.
    //       The presets match on the physical `code`, which moves per keyboard
    //       layout (Swedish `+` sits on code `Minus`), so a hardcoded US code
    //       would fire the wrong preset — pull the live codes resolved from the
    //       real layout ([resolveZoomPresetCodes]). Bare `0` matches on key, so
    //       it needs no such care.
    val plusCode = spikeZoomPlusCodes.firstOrNull { !it.startsWith("Numpad") } ?: "Equal"
    val minusCode = spikeZoomMinusCodes.firstOrNull { !it.startsWith("Numpad") } ?: "Minus"
    movieNarrate("zoom into a window to read it — and back out")
    moviePress("+", plusCode, shift = true); delay(2_400)  // ⇧+ → zoom-to-fit (max)
    moviePress("_", minusCode, shift = true); delay(1_700) // ⇧− → zoom floor (min)
    moviePress("0", "Digit0"); delay(1_100)                // 0 → back to 1:1

    // ══ 3. Grid: reshape the terminal — wider, taller, then (mostly) back —
    //       reflowing live. Deliberately shrinks one step LESS than it grew on
    //       BOTH axes (narrows twice after 3 widens; shortens once after 2
    //       heightens), so the pane rests ~one GRID_COLS_STEP wider and one
    //       GRID_ROWS_STEP taller than the small fixture default going into the
    //       tilt-and-type beat below: typing reads far better on a slightly
    //       roomier terminal than on the cramped original.
    movieNarrate("resize its grid")
    moviePress(".", "Period"); delay(550)
    moviePress(".", "Period"); delay(550)
    moviePress(".", "Period"); delay(1_100)
    moviePress(">", "Period", shift = true); delay(550)
    moviePress(">", "Period", shift = true); delay(1_600)
    moviePress(",", "Comma"); delay(550)
    moviePress(",", "Comma"); delay(900) // one fewer narrow than the 3 widens → rests a step wider
    moviePress("<", "Comma", shift = true); delay(1_300) // one fewer shorten than the 2 heightens → rests a step taller

    // ══ 4. Talk to Claude at a tilt: we are already on the finished greets pane —
    //       tilt the camera off-axis (typing into an angled terminal is the beat's
    //       whole look), engage, send it the POKE (the fixture's funny wink), watch
    //       it pick the work back up, then straighten and step out. Only the
    //       *finished* greets pane is ever typed into — never the live or waiting one.
    movieNarrate("talk to a finished Claude — at a tilt, because we can")
    moviePress("j", "KeyJ")
    movieAwaitCamera(); delay(500)
    moviePress("Enter", "Enter"); delay(900)
    movieTypeIntoFront("add a POKE 53280,0")
    delay(500)
    movieSubmitFront()
    delay(11_000) // the scripted burst runs ~10 s with the working glow on
    moviePress("x", "KeyX", alt = true, meta = true); delay(600) // ⌥⌘X disengage
    moviePress("c", "KeyC") // fly home, straightening the tilt
    movieAwaitCamera(); delay(700)

    // ══ 5. Shoot a window out of the sky: front the Trackmo `watch` shell (a
    //       disposable plain shell) and phaser it — ⌥X arms the removal, a second
    //       ⌥X within the arm window confirms and the ship pours fire on it until
    //       it dies. Leaves us on the Trackmo tab for the stash below, and — key —
    //       spares the `~/code/lastlight` shell (demo-p2, Compo) for the fly-behind
    //       type beat.
    movieNavigateToPane("demo-p6") // → the watch shell (Trackmo)
    delay(300)
    movieNarrate("close a window — we shoot it out of the sky")
    moviePress("x", "KeyX", alt = true); delay(1_100) // arm
    moviePress("x", "KeyX", alt = true) // confirm → phaser fire
    delay(5_800) // ~4 s of fire + the implosion collapse
    movieAwaitSettled(); delay(700)

    // ══ 6. Stash a whole tab: we are on the Trackmo tab (the phasered watch shell
    //       lived there), so ⌃Space lifts the entire Trackmo tab off the ring as
    //       one merged bundle that sails up to the shelf; `c` then returns the
    //       camera home to the (now smaller) command center. The tab is left
    //       stashed — we do not revisit the shelf — so the remaining tab floors
    //       renumber under us, which is why the later beats navigate by live state.
    movieNarrate("Hiding a tab: docks it in a spaceship")
    delay(3_000) // let the viewer read the caption before the tab shoots off into the sky
    moviePress(" ", "Space", ctrl = true) // stash the whole tab up
    movieAwaitBundleLanded() // hold until the stack has merged, flown and parked
    delay(700)
    movieNarrate("…and bring the camera home")
    moviePress("c", "KeyC")
    movieAwaitCamera(); delay(1_100)

    // ══ 7. Grow a new window: `n` adds a pane to the fronted tab and it blinks in
    //       through its wormhole. Tab-agnostic — whichever floor we came home to.
    movieNarrate("create new window - it arrives through a wormhole")
    moviePress("n", "KeyN")
    movieAwaitWormhole(); delay(900) // hold through the whole vortex, then a beat to read it

    // ══ 8. Overview → free flight → type from behind: frame the whole world with
    //       `m`, drop into free flight with `f`, glide behind the `~/code/lastlight`
    //       shell and type into it from behind. Front that pane in command center
    //       first (by live state — its floor moved when Trackmo was stashed) so
    //       [playFlyBehindAndType] and the `Enter` engage resolve to it.
    movieNavigateToPane("demo-p2") // → ~/code/lastlight (shell)
    delay(500)
    movieNarrate("frame the whole world at once")
    moviePress("m", "KeyM")
    movieAwaitCamera(); delay(2_600)

    movieNarrate("free flight — the camera becomes a spaceship")
    moviePress("f", "KeyF"); delay(800)
    playFlyBehindAndType("ls")

    // ── Finale: disengage, fly home, land back in command center, rest.
    moviePress("x", "KeyX", alt = true, meta = true); delay(600) // ⌥⌘X disengage
    movieNarrate("Thank you for watching!")
    moviePress("c", "KeyC") // fly home (still in free flight)
    movieAwaitCamera(); delay(400)
    moviePress("f", "KeyF") // land back into command center
    delay(2_600) // let the closing line be read before the badge vanishes
}
