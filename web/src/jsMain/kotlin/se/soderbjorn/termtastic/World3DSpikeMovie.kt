/**
 * The **demo movie** for the 3D world — a secret, demo-mode-only guided tour
 * (⌥⌘M) that "plays the keyboard" through a timed choreography showcasing the
 * spike: pane/tab navigation, zoom, grid resize, typing into a waiting Claude
 * session (at the `j` camera tilt, so the terminal is engaged at an angle),
 * stashing **two** panes and later browsing the shelf to retrieve only the
 * second one, a grand exterior orbit of the whole rotunda, and a free-flight
 * sequence with the cinematic pane fly-bys.
 *
 * **How it drives the app:** every beat that has a keyboard shortcut is played
 * as a *synthetic `KeyboardEvent` dispatched on `window`*, so it runs through
 * the real [buildKeyHandler] — the exact code path a human keypress takes.
 * That is deliberate: the shortcuts legend flashes its rows ([flashShortcut])
 * for every scripted press, mode badges appear, and the movie can never drift
 * from what the keys actually do. Only two things bypass the keyboard:
 *  - **typing** into an engaged terminal ([movieTypeIntoFront]) feeds
 *    characters straight into the demo session's line discipline
 *    (`TerminalEntry.sendInput` → `DemoTerminalSession.inputText`), because
 *    xterm.js listens on its own hidden textarea, not on `window`;
 *  - the **grand orbit** ([playGrandOrbit]) calls [flyCamTo] directly — it is
 *    a scripted camera move with no single-key equivalent.
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
package se.soderbjorn.termtastic

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
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
import se.soderbjorn.darkness.core.Appearance

/** The running movie coroutine, or `null` when no tour is playing. */
internal var spikeMovieJob: Job? = null

/**
 * The name of the built-in toolkit theme the tour plays under on the demo
 * website — the Amiga Workbench palette, matching the fixture workspace's
 * trackmo storyline. @see movieApplyTourTheme
 */
private const val TOUR_THEME_NAME = "Workbench"

/**
 * The appearance + dark-slot theme that were live before the tour swapped to
 * [TOUR_THEME_NAME], or `null` when no swap is active (Electron demo, or no
 * tour playing). Consumed (and cleared) by [movieRestoreTourTheme].
 */
private var movieSavedTheme: Pair<Appearance, String>? = null

/**
 * Swaps the app to the tour's [TOUR_THEME_NAME] — website demo only
 * ([isElectronClient] launches keep whatever theme is live): remembers the
 * current appearance + dark-slot selection in [movieSavedTheme], then binds
 * the Workbench theme to the dark slot and forces the dark appearance. The
 * reactive theme collector in `main.kt` repaints the chrome, every terminal,
 * and — via [restyleWorldChrome] — the open 3D world's pane chrome and
 * beacons, so the whole tour plays in Workbench colours.
 */
private suspend fun movieApplyTourTheme() {
    if (isElectronClient) return
    val s = appVm.stateFlow.value
    movieSavedTheme = s.appearance to s.darkThemeName
    appVm.setAppearance(Appearance.Dark)
    appVm.setDarkThemeName(TOUR_THEME_NAME)
}

/**
 * Restores the appearance + dark-slot theme saved by [movieApplyTourTheme],
 * if any. Called from [movieCleanup] on every stop path (natural end, Esc /
 * ⌥⌘M cancel, world close); the restore runs on a fresh coroutine because
 * cleanup executes in the dying movie job's `finally`.
 */
private fun movieRestoreTourTheme() {
    val (appearance, darkName) = movieSavedTheme ?: return
    movieSavedTheme = null
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    GlobalScope.launch {
        appVm.setDarkThemeName(darkName)
        appVm.setAppearance(appearance)
    }
}

/** The small top-centre termtastic branding pill shown while the movie plays, or `null`. */
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
            movieApplyTourTheme()
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
 * Removes the tour chrome (branding pill + narration row), restores the
 * pre-tour theme ([movieRestoreTourTheme]), and forgets the movie job.
 * Idempotent — called from the movie coroutine's `finally` (natural end,
 * ⌥⌘M cancel, or the cancel in [closeWorld3dSpike]), and safe when the
 * overlay is already gone.
 */
private fun movieCleanup() {
    spikeMovieBadge?.remove()
    spikeMovieBadge = null
    spikeMovieSubtitle?.remove()
    spikeMovieSubtitle = null
    spikeMovieJob = null
    movieRestoreTourTheme()
    updateDemoTourButton()
}

/**
 * Builds and shows the movie's two on-screen elements:
 *  - the small **top-centre branding pill** — one line naming the product
 *    (and its new 3D mode) with the URL (for shared recordings), styled like the
 *    termtastic wordmark: spring-green monospace on a near-black pill with a
 *    soft green glow;
 *  - the big **lower-right narration row** — a single large white line
 *    (styled after the nav "now showing" label, [showNavLabel]) that
 *    [movieNarrate] rewrites per beat so a viewer always knows what the tour
 *    is currently demonstrating.
 */
private fun showMovieBadge() {
    val overlay = spikeOverlay ?: return
    val pill = document.createElement("div") as HTMLElement
    pill.textContent = "● Termtastic · the multiplexing terminal server with Mac, Android and iOS " +
        "apps · now also in 3D · https://termtastic.soderbjorn.se"
    pill.style.cssText = "position:absolute;top:14px;left:50%;transform:translateX(-50%);z-index:5;" +
        "pointer-events:none;padding:6px 16px;border-radius:10px;border:1px solid #17301c;" +
        "background:#0a120acc;color:#8cf7a6;font:600 12px ui-monospace,Menlo,monospace;white-space:nowrap;" +
        "text-shadow:0 0 10px rgba(120,255,170,0.55),0 0 26px rgba(120,255,170,0.30);"
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
 * @param shift/alt/meta modifier flags for chord shortcuts (⌥⌘X, ⇧→, …).
 * @param holdMs how long to hold before the `keyup`; `0` sends no keyup.
 */
private suspend fun moviePress(
    key: String,
    code: String,
    shift: Boolean = false,
    alt: Boolean = false,
    meta: Boolean = false,
    holdMs: Long = 0,
) {
    fun dispatch(type: String) {
        window.dispatchEvent(
            KeyboardEvent(
                type,
                KeyboardEventInit(key = key, code = code, shiftKey = shift, altKey = alt, metaKey = meta, cancelable = true),
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
 *     [termtasticClient]): the fixture tabs/panes/states are re-published —
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

    termtasticClient.demoServer?.resetToFixtures()

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
 * Types [text] into the *front* pane's terminal at a human, slightly uneven
 * cadence by feeding characters straight into the demo session's line
 * discipline (which echoes them, so the pane shows live typing). Waits for
 * the pane's real terminal to be mounted first — engaging a mirror pane
 * promotes it within a few hundred ms ([tryPromoteMirror]).
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
 * The **grand orbit** — the one beat with no keyboard equivalent: a chain of
 * [flyCamTo] legs around the outside of the rotunda, every leg looking at the
 * world's centre, so the whole cylindrical arrangement of tab floors and pane
 * rings is seen from above, from the flank, from below and from behind. The
 * camera-distance fade regime ([spikeFlyReveal]) lights the entire sphere up
 * during the flight, which is exactly the postcard view.
 *
 * Waypoints are (azimuth°, height): azimuth 0 is the home direction (+Z),
 * increasing counter-clockwise; heights alternate above/below the tab stack
 * so the angles genuinely differ. The per-leg `pullout` bows each arc
 * outward so the chained legs read as one continuous circling move, and the
 * alternating `roll` banks into the turns.
 */
private suspend fun playGrandOrbit() {
    val r = RING_R * 2.8
    // (azimuth°, y, bank): up over the right shoulder → level behind →
    // under the far flank → rising back around toward home.
    val legs = listOf(
        Triple(70.0, 2_100.0, 0.10),
        Triple(160.0, 250.0, -0.10),
        Triple(250.0, -1_800.0, 0.10),
        Triple(335.0, 350.0, -0.08),
    )
    for ((azimuthDeg, y, bank) in legs) {
        if (!spikeOpen) return
        val a = azimuthDeg * PI / 180.0
        flyCamTo(
            r * sin(a), y, r * cos(a),
            0.0, 0.0, 0.0,
            landPristine = false, frames = 300.0,
            pullout = 900.0, rise = 0.0, roll = bank,
        )
        movieAwaitCamera()
        delay(400)
    }
}

/**
 * The choreography itself — one linear pass through every showcase beat, in
 * the order a first-time viewer best reads the world: what a pane is → what
 * the keys do to it → the agent is *alive* → panes have places (shelf, other
 * floors) → the world is a whole → you can fly it. Written against the demo
 * fixture workspace ([DemoFixtures.initialConfig]): the Compo tab fans
 * `Claude Code` (perpetually working), a build shell, and `claude: greets`
 * (finished, resumes when spoken to).
 *
 * Every keyed beat goes through [moviePress] (→ real handler → legend flash);
 * see the file doc for why. Cancellation (⌥⌘M, Esc/close) lands in the
 * caller's `finally`.
 */
private suspend fun playDemoMovie() {
    // ── Establish: rest on the working Claude pane, let it stream a moment.
    movieNarrate("a Claude session, live at work")
    movieAwaitSettled()
    delay(3_000)

    // ── Fan across the Compo tab: build shell, then the finished greets Claude.
    movieNarrate("arrow keys walk the windows of this tab")
    moviePress("ArrowRight", "ArrowRight"); movieAwaitSettled(); delay(1_300)
    moviePress("ArrowRight", "ArrowRight"); movieAwaitSettled(); delay(1_300)

    // ── Zoom: lean into the fronted window to read it, then back out — the
    //    natural companion of the grid resize that follows.
    movieNarrate("zooming into a window — and back out")
    moviePress("+", "Equal"); delay(700)
    moviePress("+", "Equal"); delay(2_000)
    moviePress("-", "Minus"); delay(700)
    moviePress("-", "Minus"); delay(900)
    moviePress("0", "Digit0"); delay(1_000)

    // ── Reshape its grid: wider, taller, then back — the content reflows live.
    movieNarrate("resizing the terminal grid — the content reflows live")
    moviePress(".", "Period"); delay(550)
    moviePress(".", "Period"); delay(550)
    moviePress(".", "Period"); delay(1_100)
    moviePress(">", "Period", shift = true); delay(550)
    moviePress(">", "Period", shift = true); delay(1_600)
    moviePress(",", "Comma"); delay(550)
    moviePress(",", "Comma"); delay(550)
    moviePress(",", "Comma"); delay(900)
    moviePress("<", "Comma", shift = true); delay(550)
    moviePress("<", "Comma", shift = true); delay(1_200)

    // ── Wake the finished Claude: tilt the camera off-axis first (typing into
    //    an angled terminal is the beat's whole look), engage, talk to it,
    //    watch it work, straighten up, step out.
    movieNarrate("typing into a waiting Claude — at a tilt, because we can")
    moviePress("j", "KeyJ")
    movieAwaitCamera(); delay(500)
    moviePress("Enter", "Enter"); delay(900)
    movieTypeIntoFront("add a POKE 53280,0")
    delay(500)
    movieSubmitFront()
    delay(11_000) // the scripted burst runs ~10 s with the working glow on
    moviePress("x", "KeyX", alt = true, meta = true); delay(600)
    moviePress("j", "KeyJ") // second `j` = fly home, straightening the tilt
    movieAwaitCamera(); delay(600)

    // ── Stash it: the pane sails up to the shelf, the camera rides along.
    movieNarrate("stashing the window on the shelf above the world — back for it later")
    moviePress(" ", "Space")
    movieAwaitCamera(); delay(1_400)
    moviePress("c", "KeyC")
    movieAwaitCamera(); delay(700)

    // ── Stash a second one: after the first stash the selection sits on the
    //    build shell ([selectNearestUnstashedInTab]), so Space sends it up to
    //    the next shelf slot. Only one of the two comes back later.
    movieNarrate("a second window joins it up there")
    moviePress(" ", "Space")
    movieAwaitCamera(); delay(1_400)
    moviePress("c", "KeyC")
    movieAwaitCamera(); delay(700)

    // ── Tab floors: down to Services, linger on the waiting (red) tests pane,
    //    shuffle a pane slot, then peek at Files and Changes and come back up.
    movieNarrate("red glow is an agent waiting for input")
    moviePress("ArrowDown", "ArrowDown"); movieAwaitSettled(); delay(1_500)
    moviePress("ArrowRight", "ArrowRight"); movieAwaitSettled(); delay(2_000)
    moviePress("ArrowRight", "ArrowRight", shift = true); delay(1_400)
    moviePress("ArrowLeft", "ArrowLeft", shift = true); delay(1_100)
    moviePress("ArrowDown", "ArrowDown"); movieAwaitSettled(); delay(1_900)
    moviePress("ArrowDown", "ArrowDown"); movieAwaitSettled(); delay(1_900)
    repeat(3) { moviePress("ArrowUp", "ArrowUp"); delay(650) }
    movieAwaitSettled(); delay(800)

    // ── The grand orbit: the whole rotunda from outside, four angles around.
    movieNarrate("the whole world at once — every tab, every window - still live")
    playGrandOrbit()
    moviePress("c", "KeyC")
    movieAwaitCamera(); delay(600)

    // ── Free flight: enter fly mode and take a *scripted* scenic route so
    //    something is always in frame: swing out beside the rotunda with the
    //    world centred, then the two-worlds tilt-pan, then the fly-bys.
    movieNarrate("free-flight mode — the camera is a spaceship, fly anywhere")
    moviePress("f", "KeyF"); delay(600)
    // Scenic leg 1: out beside the ring, the whole rotunda centred in frame;
    // the sway arcs the path around the panes instead of through them.
    flyCamTo(
        3_800.0, 1_400.0, 6_300.0, 0.0, -400.0, 0.0,
        landPristine = false, frames = 300.0,
        pullout = 600.0, rise = 0.0, sway = 1_600.0, roll = 0.08,
    )
    movieAwaitCamera(); delay(900)
    // Scenic leg 2: swing to a mid-altitude vantage looking *down* at the
    // world. A single static frame holding both the world and the shelf
    // (9.6k apart) renders each as a speck at opposite frame edges with void
    // between — verified and rejected — so the two-worlds moment is a
    // *tilt-pan* instead: world large below, then the nose sweeps up to the
    // shelf, holds, and comes back down. The camera motion is what conveys
    // "the shelf floats above the world".
    movieNarrate("the world below us…")
    flyCamTo(
        -1_800.0, 1_400.0, 3_600.0, 0.0, -300.0, 0.0,
        landPristine = false, frames = 280.0,
        pullout = 300.0, rise = 200.0, roll = -0.06,
    )
    movieAwaitCamera(); delay(1_400)
    // Tilt-pan up: the camera rises toward the shelf while the look point
    // jumps to it — the nose sweeps up and the shelf (both stashed windows +
    // its crystal beacon) gets its own clear, close moment.
    movieNarrate("…and the stash shelf floating above")
    flyCamTo(
        -1_600.0, 6_200.0, 3_600.0, -500.0, 9_600.0, 260.0,
        landPristine = false, frames = 260.0,
        pullout = 0.0, rise = 0.0,
    )
    movieAwaitCamera(); delay(2_200)
    // Pan back down to the world before the hand-flown approach.
    flyCamTo(
        -2_000.0, 3_000.0, 4_200.0, 0.0, 200.0, 800.0,
        landPristine = false, frames = 220.0,
        pullout = 0.0, rise = 0.0,
    )
    movieAwaitCamera(); delay(600)
    // Approach under real throttle so the return reads as hand-flown.
    moviePress("w", "KeyW", holdMs = 1_600); delay(1_200)
    movieNarrate("gliding behind a working window — live through the glass")
    moviePress("b", "KeyB")
    movieAwaitCamera(); delay(3_500)
    moviePress("n", "KeyN")
    movieAwaitCamera(); delay(2_600)
    moviePress("o", "KeyO")
    movieAwaitCamera(); delay(2_200)
    moviePress("f", "KeyF"); delay(700)

    // ── Back to the shelf: browse along the two stashed panes and take only
    //    the *second* one home — the first stays up there, because a shelf
    //    that keeps things is the point of a shelf.
    movieNarrate("back at the shelf — browsing, and taking just one window home")
    moviePress("v", "KeyV")
    movieAwaitCamera(); delay(1_300)
    moviePress("ArrowRight", "ArrowRight") // shelf-browse to the second slot
    movieAwaitCamera(); delay(1_200)
    moviePress(" ", "Space") // unstash the browsed (second) pane
    movieAwaitCamera(); delay(700)

    // ── Finale: camera home, zoom reset, rest where we began.
    movieNarrate("Thank you for watching!")
    moviePress("c", "KeyC")
    movieAwaitCamera()
    moviePress("0", "Digit0")
    delay(2_400) // let the closing line be read before the badge vanishes

}
