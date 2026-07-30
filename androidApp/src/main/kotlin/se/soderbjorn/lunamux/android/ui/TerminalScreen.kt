/**
 * Terminal emulator screen for the Lunamux Android app.
 *
 * Hosts a full xterm-compatible terminal session rendered by a Termux
 * [com.termux.view.TerminalView]. Composes the supporting helpers
 * extracted from this file:
 *  - [TerminalEmulatorHolder] — the externally-fed [TerminalSession]
 *    subclass + companion [TerminalEmulator] factory.
 *  - [TerminalThemeResolver] — palette resolution + emulator colour
 *    application.
 *  - [ImeHelperToolbar] — sticky modifier toolbar above the soft keyboard.
 *  - [SwipeInputBar] — gesture-typing input.
 *  - [measureNaturalGrid] — the grid this phone would fit at the user's font.
 *  - [SizeVoteClock] — pacing this client's size requests against the server's answers.
 *
 * ## Sizing: the phone is a pure renderer
 *
 * The emulator's grid comes from the server's `Size` frames and from nowhere else. A layout
 * pass — rotation, the soft keyboard, a font change — does not resize it; the pin in
 * [TerminalEmulatorHolder] makes `updateSize` a no-op against the emulator once the server
 * has spoken. What layout does instead is *measure* the grid this phone would fit at the
 * user's own font ([measureNaturalGrid]) and *ask* for it ([SizeVoteClock]); the reflow
 * arrives when the server answers. That is the tmux round trip, and it applies to the
 * driving phone as much as to a mirroring one — one authority, no client with a private
 * geometry of its own.
 *
 * Navigated to from [TreeScreen] when the user taps a terminal leaf
 * pane.
 *
 * @see TreeScreen
 * @see se.soderbjorn.lunamux.android.net.ConnectionHolder
 */
package se.soderbjorn.lunamux.android.ui

import se.soderbjorn.lunula.core.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.soderbjorn.lunamux.WindowConfig
import android.content.Context
import android.view.inputmethod.InputMethodManager
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import se.soderbjorn.lunamux.android.net.ConnectionHolder
import se.soderbjorn.lunamux.client.MirrorFit
import se.soderbjorn.lunamux.client.PtyEvent
import se.soderbjorn.lunamux.client.PtyPresentation
import kotlin.math.roundToInt

/** Theme accent colour for the terminal screen top bar. */
private val HeaderAccent: Color
    @Composable @ReadOnlyComposable
    get() = SidebarAccent

/**
 * Quiet threshold for the on-resume PTY refresh: anything that streamed
 * output within the last few seconds is clearly alive and is left alone;
 * everything else (idle or dead) gets a reconnect + reset-prefixed replay.
 */
private const val PTY_RESUME_STALE_MS = 3_000L

/** The phone's normal (driving) terminal font size in px. */
private const val DRIVING_FONT_PX = 30

/**
 * The mirror's zoom ceiling. `1.0` is the font that fills the view's height with the server's
 * rows, and the cap is deliberate: above it the last row — the prompt — goes below the fold,
 * and reaching it would need a second panning axis whose gesture collides with scrollback.
 * A user who wants bigger text takes over. The floor is not a constant; it is derived per
 * geometry by [MirrorFit.zoomFloor] so zooming out always reaches the whole-width overview.
 */
private const val MIRROR_ZOOM_MAX = 1f

/** Absolute bounds for the resulting mirror font size, in px. */
private const val MIRROR_FONT_MIN_PX = 6
private const val MIRROR_FONT_MAX_PX = 48

/**
 * The mirror's window onto the server's grid: what font to draw it at, where to put it, and
 * how far the zoom may go.
 *
 * All of it is presentation — the emulator stays pinned to the server's grid on both axes — so
 * every field here is free of any resize, vote or `SIGWINCH`.
 *
 * @property fontPx the font actually applied, i.e. the fill-height font scaled by the pinch
 *   zoom and clamped.
 * @property fillHeightFontPx the font at which all the server's rows fill the view's height:
 *   the default, and the zoom ceiling.
 * @property offsetY pixels to shift the grid down by, centring it while it is shorter than the
 *   view — which is what keeps a zoomed-out mirror in the middle of the screen.
 * @property zoomFloor the smallest useful zoom multiplier, where the whole width fits at once.
 */
private data class MirrorWindow(
    val fontPx: Int,
    val fillHeightFontPx: Int,
    val offsetY: Float,
    val zoomFloor: Float,
)

/**
 * Whether [bytes] contains a full terminal reset (RIS, `ESC c`). Termux's
 * emulator resets its colour table to the built-in default scheme on RIS
 * (see `TerminalColors.reset()`), discarding the applied theme — default-
 * coloured text then paints in the stock palette against our themed view
 * background and becomes unreadable. The output collector watches for RIS
 * (whether from the [PtySocket] reconnect replay or a real `reset` run on
 * the server) and re-applies the theme right after.
 *
 * @param bytes one PTY output frame.
 * @return `true` when the frame contains `ESC c`.
 */
internal fun containsTerminalReset(bytes: ByteArray): Boolean {
    for (i in 0 until bytes.size - 1) {
        if (bytes[i] == 0x1b.toByte() && bytes[i + 1] == 'c'.code.toByte()) return true
    }
    return false
}

/**
 * The phone's *natural* terminal grid: the cols/rows this view would fit at the user's own
 * font size.
 *
 * Cached in Compose state and refreshed by [measureNaturalGrid] whenever the view's box or
 * the user's font size changes. Deliberately NOT the emulator's grid — that is the server's,
 * and following it here would make the take-over target and the mirror font-fit baseline
 * circular. It is what Reformat and every take-over ask the PTY for.
 */
internal data class AndroidGridDims(
    val cols: Int,
    val rows: Int,
)

/**
 * Mutable bookkeeping for terminal scroll-pause, all touched only on the main
 * thread (the visibility poll, the output `view.post`, and the pill tap all
 * run there). Termux's [TerminalView.onScreenUpdated] force-snaps the view to
 * the bottom; we let it snap and then restore the user's offset from here so
 * scrolling up pauses auto-follow without editing the vendored view.
 *
 * @property lastOffset the most recent `topRow` while scrolled up (<= 0; 0 = at
 *   bottom). Preserved across a resume reset (`ESC c`) that wipes scrollback so
 *   the position can be re-applied once the replay settles.
 * @property pendingRestore the offset to scroll back to after a resume replay,
 *   or null when no restore is pending.
 * @property restoreJob debounce coroutine for [pendingRestore]; re-armed on
 *   every output chunk and fires once output goes quiet.
 */
private class ScrollPauseState {
    var lastOffset: Int = 0
    var pendingRestore: Int? = null
    var restoreJob: Job? = null
}

/**
 * Searches the [WindowConfig] pane tree for a leaf whose session ID
 * matches [sessionId] and returns its display title.
 */
private fun findLeafTitle(config: WindowConfig?, sessionId: String): String? {
    if (config == null) return null
    // Search every world's tabs (worlds are the source of truth for >=1.9
    // clients and the opened session may belong to any of them); fall back to
    // the legacy flat tabs when the config carries no worlds (pre-1.9 server).
    val tabs = config.worlds.flatMap { it.tabs }.ifEmpty { config.tabs }
    for (tab in tabs) {
        tab.panes.firstOrNull { it.leaf.sessionId == sessionId }?.let { return it.leaf.title }
    }
    return null
}

/**
 * A single-session terminal screen.
 *
 * @param sessionId the PTY session identifier to connect to on the server.
 * @param onBack callback invoked when the user navigates back to [TreeScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    sessionId: String,
    onBack: () -> Unit,
) {
    val client = ConnectionHolder.client()
    if (client == null) {
        onBack()
        return
    }

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val emulatorDispatcher = remember {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    }

    val windowConfig by client.windowState.config.collectAsStateWithLifecycle()
    val headerTitle = remember(windowConfig, sessionId) {
        findLeafTitle(windowConfig, sessionId) ?: sessionId
    }

    val sessionStates by client.windowState.states.collectAsStateWithLifecycle()
    val paneState = sessionStates[sessionId]

    // The grid this phone would like, mirrored onto the connect URL so the server
    // synthesizes the attach redraw at our width (no 80x24-seed reflow flash). Fed by
    // [remeasureAndAsk] from the view's layout listener below.
    val gridFlow = remember(sessionId) { MutableStateFlow<Pair<Int, Int>?>(null) }
    val ptySocket = remember(sessionId) { client.openPtySocket(sessionId, gridFlow) }
    val ctrlSticky = remember { mutableStateOf(false) }
    val shiftSticky = remember { mutableStateOf(false) }
    var swipeInputActive by remember { mutableStateOf(false) }
    var swipeText by remember { mutableStateOf("") }
    val terminalViewRef = remember { mutableStateOf<TerminalView?>(null) }

    // Size requests are paced against the server's answers, not against a timer: one vote in
    // flight, latest desire remembered. @see SizeVoteClock
    val sizeVotes = remember(sessionId) {
        SizeVoteClock(
            scope = scope,
            sendVote = { cols, rows -> ptySocket.resize(cols, rows) },
            sendForce = { cols, rows -> ptySocket.forceResize(cols, rows) },
        )
    }
    DisposableEffect(sessionId) { onDispose { sizeVotes.cancel() } }

    // [localGrid] = the phone's NATURAL grid — the one it WOULD fit at the user's own font,
    // measured by [measureNaturalGrid] from the view's layout listener and applied to
    // nothing. It is the take-over target, the grid put on the connect URL, and the baseline
    // the passive font-fit is measured against.
    var localGrid by remember(sessionId) {
        mutableStateOf<AndroidGridDims?>(null)
    }

    // The phone renders the server's authoritative grid as a live mirror. When that
    // grid is wider than [localGrid] (another device — the laptop — is driving) the
    // phone is PASSIVE: it pins the emulator to the server grid ([passiveGridPin],
    // read by the holder's updateSize) and shrinks the font so the grid fits,
    // instead of reflowing wide output into the narrow phone grid (the old mangle).
    // Take-over (typing / tap / badge) forces the PTY to [localGrid] and the mirror
    // snaps back to the phone's own width.
    //
    // [serverGrid] is the server's last authoritative PTY size (Compose state so the
    // badge + font-fit react). [drivingTo] holds the grid we last forced to,
    // optimistically, so a keystroke burst doesn't each fire a forceResize before
    // the server's Size echo returns.
    var serverGrid by remember(sessionId) { mutableStateOf<Pair<Int, Int>?>(null) }

    // The server's governance verdict for THIS connection: true when the phone is the
    // driving client, false when another device is, null when no client governs yet
    // (or the server is too old to say). Authoritative — governance is the server's
    // decision — so it outranks the width comparison the mirror used to infer it from,
    // which could not tell two same-width clients apart and could not see governance
    // move without the grid moving. Null falls back to that comparison.
    var driving by remember(sessionId) { mutableStateOf<Boolean?>(null) }

    val drivingTo = remember(sessionId) { AtomicReference<Pair<Int, Int>?>(null) }

    // The server's grid, as the holder's updateSize reads it. Set on the first Size frame and
    // kept at the latest one thereafter — for a DRIVING phone as much as a mirroring one, so
    // a layout pass can never resize the emulator (see TerminalEmulatorHolder). Null only
    // before the server has said anything, where the view's own dims are all there is.
    val serverGridPin = remember(sessionId) { AtomicReference<Pair<Int, Int>?>(null) }

    // The driving font size. Deliberately NOT pinch-adjustable: see onScale — while
    // driving, a font change re-fits this phone's grid and re-votes the SHARED PTY
    // size, so a viewing gesture would reflow the session for every attached client.
    var userFontSize by remember(sessionId) { mutableStateOf(DRIVING_FONT_PX) }

    // Extra zoom applied to the mirror only, driven by pinch. Purely local
    // presentation: the server grid is untouched, so no other client sees it. Kept
    // across passive/driving transitions so re-entering the mirror preserves it.
    var mirrorZoom by remember(sessionId) { mutableStateOf(1f) }

    // Passive = the server grid is a different (wider) width than this phone's own.
    // Cols only: a rows-only difference doesn't change wrapping.
    val passive = PtyPresentation.isPassive(
        naturalCols = localGrid?.cols ?: 0,
        serverCols = serverGrid?.first ?: 0,
        driving = driving,
    )

    // The view's pixel box, from the layout listener. The mirror's fit needs the real box
    // rather than the natural grid it implies: cell metrics are integers with a ceil in them,
    // so going back through a grid loses exactly the pixel that decides whether the last row
    // fits. @see cellMetricsProvider
    var viewBox by remember(sessionId) { mutableStateOf<Pair<Int, Int>?>(null) }

    // The mirror's window onto the server grid. Null while driving — then the grid IS this
    // phone's own, so it fits by construction and the window is the whole view.
    //
    // Fitted to the ROWS, not to both axes: a portrait phone at a laptop's column count has
    // room for ~3.5x the rows the session has, so fitting both let the cols ratio bind and left
    // the text at ~0.4x the user's own font with two thirds of the screen unused. Filling the
    // height is legible (larger than the driving font, on the measured geometry) and the columns
    // that overflow are panned over instead of shrunk away. The cost, stated: about a third of
    // each line is visible in portrait, so this is monitoring at a glance — landscape, where the
    // rows bind and everything fits with no pan at all, is for reading in full.
    val mirrorWindow: MirrorWindow? = run {
        val box = viewBox
        val sg = serverGrid
        if (!passive || box == null || sg == null || sg.first <= 0 || sg.second <= 0) {
            null
        } else {
            val metrics = cellMetricsProvider(TerminalFont.typeface(ctx))
            val fillHeight = MirrorFit.solveFillHeightFont(
                viewHeightPx = box.second,
                serverRows = sg.second,
                minPx = MIRROR_FONT_MIN_PX,
                maxPx = MIRROR_FONT_MAX_PX,
                metrics = metrics,
            )
            val overview = MirrorFit.solveFitWidthFont(
                viewWidthPx = box.first,
                serverCols = sg.first,
                minPx = MIRROR_FONT_MIN_PX,
                maxPx = MIRROR_FONT_MAX_PX,
                metrics = metrics,
            )
            val floor = MirrorFit.zoomFloor(fillHeight, overview)
            val fontPx = (fillHeight * mirrorZoom.coerceIn(floor, MIRROR_ZOOM_MAX))
                .roundToInt()
                .coerceIn(MIRROR_FONT_MIN_PX, fillHeight)
            MirrorWindow(
                fontPx = fontPx,
                fillHeightFontPx = fillHeight,
                // Centred at the APPLIED font, not the fill-height one: a zoomed-out grid is
                // shorter than the view, and centring it is what Otto asked for over parking
                // it against the top edge.
                offsetY = MirrorFit.centreOffsetY(box.second, sg.second, metrics(fontPx)),
                zoomFloor = floor,
            )
        }
    }

    // The font actually applied to the view: the user's size while driving, the mirror window's
    // while another device does. Applied in the AndroidView update block.
    val appliedFontSize = mirrorWindow?.fontPx ?: userFontSize

    // The live zoom floor, in a plain holder so the double-tap listener registered once in the
    // factory can read the current geometry rather than the geometry at registration time.
    val zoomFloorRef = remember(sessionId) { floatArrayOf(1f) }
    zoomFloorRef[0] = mirrorWindow?.zoomFloor ?: 1f
    // Last font size pushed to the view — a plain holder (not Compose state) so the
    // guarded setTextSize in the update block can't feed back into recomposition.
    val appliedFontRef = remember(sessionId) { intArrayOf(-1) }

    // Re-measure the phone's natural grid and ask the PTY for it. Driven by the view's
    // layout-change listener (the box moved: rotation, keyboard show/hide, split) and by a
    // change to the user's font size (same cell arithmetic, different cell).
    //
    // Measuring is unconditional; only the ASK is a vote. That matters while mirroring: the
    // vote is soft, and the arbiter moves governance only on an explicit force or on real
    // input, so rotating the phone rescales the mirror instead of stealing the PTY — while
    // still keeping [localGrid] (the take-over target and the font-fit baseline) truthful.
    // The old grid-size listener could not do both: it was gated on not-mirroring, because
    // the grid it reported was measured at the shrunken mirror font.
    val remeasureAndAsk: (TerminalView) -> Unit = remember(sessionId) {
        { view ->
            val natural = measureNaturalGrid(view, userFontSize, TerminalFont.typeface(view.context))
            if (natural != null) {
                if (natural != localGrid) {
                    localGrid = natural
                    gridFlow.value = natural.cols to natural.rows
                }
                sizeVotes.request(natural.cols, natural.rows, force = false)
            }
        }
    }

    // The user's font size is the other input to the natural grid: a bigger font means fewer
    // cells in the same box. Re-measure when it changes — note this is the *user's* size, not
    // the applied one, so the mirror's font-fit walk generates nothing here. That walk (19
    // votes in 250 ms on device) is what the deleted vote debounce existed to absorb; it is
    // gone structurally rather than smoothed over.
    LaunchedEffect(sessionId, userFontSize) {
        terminalViewRef.value?.let { remeasureAndAsk(it) }
    }

    // Take-over: force the shared PTY to this phone's natural grid. No-op when the
    // server already matches (ordinary typing while driving is free) or when a force
    // to that grid is already in flight. After the force, the server's resync Size
    // arrives, [passive] clears, and the font restores. Invoked by real input,
    // tap-to-focus and the take-over badge — the "intent, not presence" model.
    val ensureDriving: suspend () -> Unit = remember(sessionId) {
        {
            val local = localGrid
            if (local != null && local.cols > 0 && local.rows > 0) {
                val target = local.cols to local.rows
                if (serverGrid != target && drivingTo.get() != target) {
                    drivingTo.set(target)
                    sizeVotes.request(target.first, target.second, force = true)
                }
            }
        }
    }

    // Input policy for view-produced bytes. Real input takes over first, then sends;
    // but ambient reports (mouse wheel, focus in/out) the mirror emits from scrolling
    // or focus are dropped while passive — they must neither reach the shell nor
    // count as a take-over, or scrolling the mirror would steal the grid. Reads live
    // state (safe outside composition); classifier is the shared PtyPresentation.
    val handleInput: suspend (ByteArray) -> Unit = remember(sessionId) {
        { bytes ->
            val passiveNow = PtyPresentation.isPassive(
                naturalCols = localGrid?.cols ?: 0,
                serverCols = serverGrid?.first ?: 0,
                driving = driving,
            )
            when {
                // The emulator answering a query the remote program sent (cursor
                // position, device attributes, colour reports). Must be delivered — the
                // program is waiting — but it is NOT user intent: treating it as such
                // made the phone seize the PTY whenever a program probed the terminal.
                PtyPresentation.isDeviceReply(bytes) ->
                    runCatching { ptySocket.send(bytes) }
                // Mouse wheel / focus reports the mirror emits from scrolling or focus:
                // neither input nor a take-over.
                passiveNow && PtyPresentation.isAmbientReport(bytes) -> Unit
                // Real input: take over first so it lands at this phone's width.
                else -> {
                    ensureDriving()
                    runCatching { ptySocket.send(bytes) }
                }
            }
        }
    }

    // Scroll-pause: whether the user has scrolled up off the bottom (drives the
    // floating "jump to bottom" pill) and whether fresh output arrived while
    // they were scrolled up (switches the pill to a "New output" hint).
    var scrolledUp by remember(sessionId) { mutableStateOf(false) }
    var hasNewOutput by remember(sessionId) { mutableStateOf(false) }
    val scrollPause = remember(sessionId) { ScrollPauseState() }

    val terminalPalette = rememberTerminalPalette(client, sessionId)
    val bgComposeColor = Color(terminalPalette.bg)

    val session = remember(sessionId) {
        createExternalTerminalSession(
            scope = scope,
            emulatorDispatcher = emulatorDispatcher,
            terminalViewRef = terminalViewRef,
            ptySocket = ptySocket,
            serverGridPin = serverGridPin,
            handleInput = handleInput,
        )
    }

    val emulator = remember(sessionId) { createSyncedEmulator(session) }

    // Single ordered event stream: output bytes, size changes and reconnect
    // resets are applied to the emulator in the order the server produced them,
    // so a resize never races the redraw bytes it triggers (the old split
    // output + ptySize flows could interleave and mangle the grid).
    LaunchedEffect(sessionId) {
        // A server Size may have been recorded in the conflated ptySize mirror
        // before this collector started; seed from it so the mode machine (passive
        // detection, badge) sees the current PTY width immediately.
        ptySocket.ptySize.value?.let { serverGrid = it }
        ptySocket.events.collect { ev ->
            // Passive = the server PTY is at another device's width. Raw bytes are
            // cursor-addressed for that width, so appending them into this phone's
            // narrow grid is the mangle — and the repeated wide repaints during
            // contention (you type on the laptop while the phone holds a different
            // width) stack up as the duplicated banners/input-lines. So once the
            // phone has its OWN clean frame we FREEZE while passive: hold the last
            // clean frame and drop output + reconnect resets until the phone is at
            // its own width again. BEFORE that first frame ([hasDrivenOwnWidth] is
            // false) we feed everything — including the tab-return replay that
            // carries the scrollback — so re-entering a terminal never loses history.
            when (ev) {
                is PtyEvent.Size -> {
                    val sz = ev.cols to ev.rows
                    serverGrid = sz
                    // Server drifted off the grid we forced to → another device
                    // reclaimed; drop the optimistic guard so the next real input
                    // re-takes-over to this phone's width.
                    val d = drivingTo.get()
                    if (d != null && d != sz) drivingTo.set(null)
                    // Pin the emulator to the server's grid — unconditionally, driving or
                    // mirroring. This is the geometry authority now, so the view's own layout
                    // can never reflow the emulator out from under the synthesized redraw the
                    // server orders right after this Size.
                    serverGridPin.set(sz)
                    // The vote pipeline is clocked by these frames: any answer from the server
                    // resolves whatever this phone last asked for.
                    sizeVotes.onServerGrid(sz.first, sz.second)
                    // Deliberately NOT on the emulator dispatcher: this collector runs on
                    // the UI dispatcher, and the view reads the buffer on the main thread
                    // without the lock, so a background resize can reallocate it mid-read
                    // (see the note in TerminalEmulatorHolder.updateSize).
                    //
                    // BOTH axes follow the server. The stream is absolutely cursor-addressed
                    // (ESC[H-anchored repaints, the redraw's CUP epilogue) for exactly the
                    // server's screen, so extra local rows shift every address — see the
                    // tombstone in TerminalEmulatorHolder. Rows used to keep following the
                    // view's own capacity while driving, which made the driving client its own
                    // private geometry authority: the disagreement the tmux model removes.
                    synchronized(emulator) {
                        runCatching { emulator.resize(sz.first, sz.second, 1, 1) }
                    }
                    // Repaint after the resize. A cols change is followed by the
                    // synthesized redraw Bytes (which repaint), but a rows-only Size
                    // carries no redraw, and even on a cols change the view otherwise
                    // shows the pre-resize buffer until those Bytes land — the
                    // "invisible until you scroll" flash on take-over. Respect a
                    // scrolled-up user: onScreenUpdated force-snaps to the bottom, so
                    // only call it at the bottom and otherwise just invalidate in place.
                    terminalViewRef.value?.post {
                        val view = terminalViewRef.value ?: return@post
                        if (view.topRow < 0) view.invalidate() else view.onScreenUpdated()
                    }
                    return@collect
                }
                PtyEvent.Reset -> {
                    // Reconnect boundary: the server re-sends a fresh synthesized
                    // attach redraw (RIS-prefixed) as the next Bytes, which clears
                    // and repaints the emulator itself — so, unlike the old ring
                    // replay, we feed no local RIS here, and the theme re-applies on
                    // the Bytes path (containsTerminalReset detects the redraw's RIS).
                    // Stash the scroll offset so the user lands near where they were.
                    if (scrollPause.lastOffset < 0) {
                        scrollPause.pendingRestore = scrollPause.lastOffset
                    }
                    return@collect
                }
                // Live output (incl. the RIS-prefixed synthesized redraw): always fed
                // into the emulator below. No freeze — the phone mirrors the server's
                // coherent grid at whatever font-fit the mode machine applies.
                is PtyEvent.Governance -> {
                    // Who drives is the server's call. An ungoverned session (nobody has
                    // acted yet, or the governor just left) clears the verdict rather than
                    // pinning a stale one, so the width fallback resumes.
                    driving = if (ev.governed) ev.driving else null
                    // The pin does not depend on the verdict any more — it is the server grid
                    // whoever is driving — but a same-size take-over moves governance with no
                    // Size frame at all, so this is still where a phone that has just become
                    // the mirror must adopt the grid.
                    val sg = serverGrid
                    val nowPassive = PtyPresentation.isPassive(
                        naturalCols = localGrid?.cols ?: 0,
                        serverCols = sg?.first ?: 0,
                        driving = driving,
                    )
                    if (sg != null) serverGridPin.set(sg)
                    if (nowPassive && sg != null) {
                        // Adopt the server grid NOW, on the event stream, not on the
                        // next layout pass: a same-size take-over moves governance
                        // with no Size frame at all, and the emulator's rows may
                        // still be the view's own — every absolutely-addressed byte
                        // that follows would land shifted until a relayout happened
                        // to run. No-op when the dims already match.
                        synchronized(emulator) {
                            runCatching { emulator.resize(sg.first, sg.second, 1, 1) }
                        }
                        terminalViewRef.value?.post {
                            val view = terminalViewRef.value ?: return@post
                            if (view.topRow < 0) view.invalidate() else view.onScreenUpdated()
                        }
                    }
                    return@collect
                }
                is PtyEvent.Bytes -> Unit
            }
            val chunk = ev.data
            withContext(emulatorDispatcher) {
                synchronized(emulator) {
                    emulator.append(chunk, chunk.size)
                }
            }
            val isReset = containsTerminalReset(chunk)
            // A terminal reset (the reconnect replay's prefix, or a real
            // `reset` on the server) reverts the emulator's colour table to
            // the stock scheme — re-apply the theme before repainting or
            // default-coloured text becomes unreadable on the themed
            // background until the screen is rebuilt.
            if (isReset) {
                terminalViewRef.value?.post {
                    terminalViewRef.value?.let { applyTerminalColors(it, emulator, terminalPalette) }
                }
                // A resume reset wipes scrollback while the user may have been
                // reading history. Stash their last offset so we can return
                // them once the replay settles (best-effort: the replayed
                // ring buffer is the same content, so the row offset lands
                // close to where they were).
                if (scrollPause.lastOffset < 0) {
                    scrollPause.pendingRestore = scrollPause.lastOffset
                }
            }
            terminalViewRef.value?.post {
                val view = terminalViewRef.value ?: return@post
                val before = view.topRow
                if (before < 0) {
                    // User has scrolled up — let onScreenUpdated snap to the
                    // bottom (it also clears the scroll counter), then shift
                    // the view back up by the number of newly-scrolled lines so
                    // the content the user is reading stays put. All in one
                    // post = one render frame, so there's no visible flicker.
                    val shift = synchronized(emulator) { emulator.scrollCounter }
                    view.onScreenUpdated()
                    val history = emulator.screen.activeTranscriptRows
                    val restored = (before - shift).coerceIn(-history, 0)
                    view.topRow = restored
                    view.invalidate()
                    scrollPause.lastOffset = restored
                    scrolledUp = restored < 0
                    if (restored < 0) hasNewOutput = true
                } else {
                    view.onScreenUpdated()
                }
            }
            // Debounce a resume-restore: re-armed on every chunk, it fires once
            // output goes quiet so we land after the whole replay has been fed.
            if (scrollPause.pendingRestore != null) {
                scrollPause.restoreJob?.cancel()
                scrollPause.restoreJob = scope.launch {
                    delay(300)
                    val target = scrollPause.pendingRestore ?: return@launch
                    val view = terminalViewRef.value ?: return@launch
                    view.post {
                        val history = emulator.screen.activeTranscriptRows
                        val restored = target.coerceIn(-history, 0)
                        view.topRow = restored
                        view.invalidate()
                        scrollPause.lastOffset = restored
                        scrolledUp = restored < 0
                        if (restored < 0) hasNewOutput = true
                    }
                    scrollPause.pendingRestore = null
                }
            }
        }
    }

    // Poll the view's scroll offset so the pill appears/disappears even when
    // the user scrolls a static screen (Termux's TerminalView has no scroll
    // callback). Cheap and main-thread only; runs only while this screen is
    // composed. Output-driven scroll changes are handled inline above, but the
    // poll also covers them as a backstop.
    LaunchedEffect(sessionId) {
        while (isActive) {
            delay(80)
            val view = terminalViewRef.value ?: continue
            val tr = view.topRow
            if (tr < 0) {
                scrollPause.lastOffset = tr
                if (!scrolledUp) scrolledUp = true
            } else {
                scrollPause.lastOffset = 0
                if (scrolledUp) scrolledUp = false
                if (hasNewOutput) hasNewOutput = false
            }
        }
    }

    // Take-over badge: shown while another device drives (i.e. while [passive]),
    // debounced so a momentary handoff blip doesn't flash it.
    var showTakeOver by remember(sessionId) { mutableStateOf(false) }
    LaunchedEffect(passive) {
        if (passive) {
            delay(300)
            showTakeOver = true
        } else {
            showTakeOver = false
        }
    }

    DisposableEffect(sessionId) {
        onDispose {
            ptySocket.closeDetached()
            emulatorDispatcher.close()
        }
    }

    // Refresh the terminal whenever the screen returns to the foreground:
    // if the PTY stream has been quiet (idle shell, or a connection the OS
    // silently killed while the phone slept), the socket reconnects and the
    // server's ring-buffer replay — prefixed with a terminal reset — brings
    // the emulator up to date with whatever happened while we were away.
    // Actively-streaming sessions are left alone. ON_RESUME also fires on
    // first composition, which is harmless: the socket just connected, so
    // it is never stale at that point.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, sessionId) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                ptySocket.reconnectIfStale(PTY_RESUME_STALE_MS)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration.orientation, configuration.screenWidthDp, configuration.screenHeightDp) {
        terminalViewRef.value?.requestLayout()
    }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = HeaderAccent,
                        )
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Leading pane-type icon (issue #48) — the same glyph the
                        // session list shows before each pane title, so the
                        // full-screen header stays consistent with the list. This
                        // screen only ever hosts terminal panes, hence the fixed
                        // [LeafKind.TERMINAL]; it is never a floating window here.
                        PaneIcon(kind = LeafKind.TERMINAL, floating = false)
                        Spacer(Modifier.width(8.dp))
                        // Pane status indicator (issue #38), painted in the
                        // theme foreground colour: idle = solid dot, working =
                        // breathing dot, waiting = pulsing warning triangle. The
                        // 18dp box bakes in ~5dp of trailing gap to the title.
                        StatusDot(state = paneState, boxDp = 18)
                        Text(
                            text = headerTitle,
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = SidebarTextPrimary,
                            ),
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { swipeInputActive = !swipeInputActive }) {
                        // Material extended's KeyboardHide (a keyboard with a
                        // downward chevron) mirrors the iOS toolbar's
                        // keyboard.chevron.compact.down toggle, so the
                        // text-input affordance reads the same on both apps.
                        Icon(
                            Icons.Filled.KeyboardHide,
                            contentDescription = "Text input bar",
                            tint = if (swipeInputActive) HeaderAccent else Color.Gray,
                        )
                    }
                    IconButton(onClick = {
                        val natural = localGrid
                        if (natural != null && natural.cols > 0 && natural.rows > 0) {
                            // Explicit fit: force this phone's grid unconditionally
                            // (bypass ensureDriving's no-op check — Reformat is how
                            // the user re-asserts their width even when the server
                            // already reports it). Record it so it doesn't
                            // immediately re-drive on the next keystroke.
                            drivingTo.set(natural.cols to natural.rows)
                            sizeVotes.request(natural.cols, natural.rows, force = true)
                        }
                    }) {
                        ReformatIcon(tint = HeaderAccent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SidebarBackground,
                    titleContentColor = SidebarTextPrimary,
                ),
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(bgComposeColor),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        val view = TerminalView(context, null)
                        view.setTextSize(userFontSize)
                        view.setTypeface(TerminalFont.typeface(context))
                        view.isFocusable = true
                        view.isFocusableInTouchMode = true
                        // Layout, not the view's grid-size listener. That listener reports
                        // the grid the view computed at the APPLIED font — which is the
                        // shrunken mirror font whenever another device drives, so it answered
                        // for a grid several times too large — and it fires as a consequence
                        // of the view having already resized the emulator, which a pure
                        // renderer must never do. What matters here is only "the box changed",
                        // and the grid is then MEASURED at the user's own font.
                        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                            (v as? TerminalView)?.let {
                                // The raw box drives the mirror's fit; the measured grid drives
                                // the votes and the take-over target.
                                if (it.width > 0 && it.height > 0) {
                                    val box = it.width to it.height
                                    if (box != viewBox) viewBox = box
                                }
                                remeasureAndAsk(it)
                            }
                        }
                        // Double tap toggles the mirror between filling the height and the
                        // whole-width overview — the photo-viewer idiom, and what makes a panned
                        // mirror navigable: the overview is the map.
                        view.setOnMirrorDoubleTapListener {
                            val floor = zoomFloorRef[0]
                            if (floor < MIRROR_ZOOM_MAX) {
                                mirrorZoom =
                                    if (mirrorZoom > (floor + MIRROR_ZOOM_MAX) / 2f) floor
                                    else MIRROR_ZOOM_MAX
                            }
                        }
                        view.setTerminalViewClient(object : TerminalViewClient {
                            override fun onScale(scale: Float): Float {
                                // Pinch-zoom is a MIRROR-ONLY gesture. While driving, a
                                // font change re-fits this phone's grid and re-votes the
                                // SHARED PTY size — so a pinch here would reflow the
                                // session for the laptop too, which is far too much blast
                                // radius for a viewing gesture (and the per-step reflow is
                                // what made it feel rough). The driving font stays a
                                // setting. While mirroring, zoom is purely local: the
                                // server grid is untouched and only this phone rescales.
                                val passiveNow = PtyPresentation.isPassive(
                                    naturalCols = localGrid?.cols ?: 0,
                                    serverCols = serverGrid?.first ?: 0,
                                    driving = driving,
                                )
                                if (!passiveNow) return scale
                                if (scale < 0.95f || scale > 1.05f) {
                                    // Bounds are derived, not constant: the floor is the font at
                                    // which the whole width fits (the overview), the ceiling the
                                    // one that fills the height. A fixed 0.5 could not reach the
                                    // overview, and anything above 1.0 hides the prompt.
                                    mirrorZoom = (mirrorZoom * scale)
                                        .coerceIn(zoomFloorRef[0], MIRROR_ZOOM_MAX)
                                    return 1f
                                }
                                return scale
                            }
                            override fun onSingleTapUp(e: android.view.MotionEvent?) {
                                // A tap on the mirror does NOTHING. It used to take over, on
                                // the reading that a tap is a deliberate "use this pane"
                                // gesture — defensible while the mirror was inert, wrong now
                                // that it is something you drag around: a pan that ends with
                                // barely any movement arrives here as a tap, and each one would
                                // cost a real SIGWINCH, a full repaint of the running program,
                                // one frame leaked into its scrollback (upstream #49086) and a
                                // reflow under whoever is using the laptop. Take-over stays
                                // explicit — the badge, the Reformat button, or actually typing.
                                val passiveNow = PtyPresentation.isPassive(
                                    naturalCols = localGrid?.cols ?: 0,
                                    serverCols = serverGrid?.first ?: 0,
                                    driving = driving,
                                )
                                if (passiveNow) return
                                view.requestFocus()
                                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                                imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                                // While driving, tap-to-focus keeps its keyboard and re-fits
                                // the PTY if this phone's grid has drifted from the server's.
                                scope.launch { ensureDriving() }
                            }
                            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
                            override fun shouldEnforceCharBasedInput(): Boolean = false
                            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
                            override fun isTerminalViewSelected(): Boolean = true
                            override fun copyModeChanged(copyMode: Boolean) = Unit
                            override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent?, session: com.termux.terminal.TerminalSession?): Boolean = false
                            override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent?): Boolean = false
                            override fun onLongPress(event: android.view.MotionEvent?): Boolean = false
                            override fun readControlKey(): Boolean = ctrlSticky.value
                            override fun readAltKey(): Boolean = false
                            override fun readShiftKey(): Boolean = shiftSticky.value
                            override fun readFnKey(): Boolean = false
                            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: com.termux.terminal.TerminalSession?): Boolean = false
                            override fun onEmulatorSet() = Unit
                            override fun logError(tag: String?, message: String?) = Unit
                            override fun logWarn(tag: String?, message: String?) = Unit
                            override fun logInfo(tag: String?, message: String?) = Unit
                            override fun logDebug(tag: String?, message: String?) = Unit
                            override fun logVerbose(tag: String?, message: String?) = Unit
                            override fun logStackTraceWithMessage(tag: String?, message: String?, e: java.lang.Exception?) = Unit
                            override fun logStackTrace(tag: String?, e: java.lang.Exception?) = Unit
                        })
                        view.attachSession(session)
                        try {
                            val field = TerminalView::class.java.getDeclaredField("mEmulator")
                            field.isAccessible = true
                            field.set(view, emulator)
                        } catch (_: Throwable) {
                        }
                        applyTerminalColors(view, emulator, terminalPalette)
                        terminalViewRef.value = view
                        // No size seeding here: the natural grid is measured on the first
                        // layout pass (the layout listener above) and the emulator's own grid
                        // comes from the server, with serverGrid seeded from the ptySize
                        // mirror in the events collector.
                        view
                    },
                    update = { view ->
                        applyTerminalColors(view, emulator, terminalPalette)
                        // While mirroring, a swipe scrolls our own transcript instead of
                        // being turned into wheel reports / arrow keys for the remote
                        // program. The synthesized redraw replays the driving program's
                        // mouse-tracking modes, so without this the view produced wheel
                        // reports — which the mirror drops (it must not inject input) —
                        // and the mirror could not be scrolled at all.
                        view.setLocalScrollOnly(passive)
                        // Apply the derived font: the user's size while driving, shrunk
                        // to fit while mirroring a wider grid. Guarded (setTextSize
                        // rebuilds the renderer + relayouts unconditionally) so it fires
                        // only on a real change. The relayout re-runs the layout listener,
                        // which measures at the USER font — so a mirror-fit font change
                        // cannot move the natural grid.
                        if (appliedFontRef[0] != appliedFontSize) {
                            val cellBefore = view.cellWidthPx
                            appliedFontRef[0] = appliedFontSize
                            view.setTextSize(appliedFontSize)
                            // A pinch scales around its focal point, not around the left edge:
                            // hold whatever was under the fingers in place across the font
                            // change. The view records the focus; only this side knows the cell
                            // width the new font came out at.
                            val focusX = view.consumePinchFocusX()
                            if (!focusX.isNaN()) {
                                view.panX = MirrorFit.focalAnchoredPan(
                                    oldPanPx = view.panX,
                                    focusXPx = focusX,
                                    oldCellWidthPx = cellBefore,
                                    newCellWidthPx = view.cellWidthPx,
                                )
                            }
                        }
                        // The window's vertical placement, and its collapse on take-over: a
                        // driving grid fits its own view, so there is nothing to centre and
                        // nowhere to pan.
                        if (mirrorWindow != null) {
                            view.setContentOffsetY(mirrorWindow.offsetY)
                        } else {
                            view.setContentOffsetY(0f)
                            view.resetPan()
                        }
                        // Recomposition (e.g. our own scroll-pause state changes)
                        // must not yank a scrolled-up user back to the bottom:
                        // onScreenUpdated() force-snaps, so only call it when at
                        // the bottom and otherwise just repaint in place.
                        if (view.topRow < 0) view.invalidate() else view.onScreenUpdated()
                    },
                )

                // Take-over badge: shown while another device drives the PTY (this
                // phone is passive). Tapping it is an explicit, input-free take-over
                // — fit the shared PTY to this phone's width. Neutral copy: the size
                // broadcast doesn't carry which device is driving.
                if (showTakeOver) {
                    // Filled with the accent (rather than the surface tint) so it reads
                    // as an action over the mirrored content instead of blending into
                    // the terminal chrome — same treatment as the "New output" pill.
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(terminalPalette.accent))
                            .clickable { scope.launch { ensureDriving() } }
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Mirroring another device",
                            color = Color(terminalPalette.bg),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "· Tap to take over",
                            color = Color(terminalPalette.bg),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                // Floating "jump to bottom" pill, shown only while scrolled up.
                // Tapping it snaps back to the bottom and resumes auto-follow.
                // While paused, fresh output flips the label to "New output".
                if (scrolledUp) {
                    val pillBg = if (hasNewOutput) {
                        Color(terminalPalette.accent)
                    } else {
                        Color(terminalPalette.surface)
                    }
                    val pillFg = if (hasNewOutput) {
                        Color(terminalPalette.bg)
                    } else {
                        Color(terminalPalette.text)
                    }
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(pillBg)
                            .clickable {
                                terminalViewRef.value?.let { view ->
                                    view.topRow = 0
                                    view.onScreenUpdated()
                                }
                                scrollPause.lastOffset = 0
                                scrollPause.pendingRestore = null
                                scrollPause.restoreJob?.cancel()
                                scrolledUp = false
                                hasNewOutput = false
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            text = if (hasNewOutput) "New output" else "Jump to bottom",
                            color = pillFg,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "↓",
                            color = pillFg,
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            if (swipeInputActive) {
                SwipeInputBar(
                    text = swipeText,
                    onTextChange = { swipeText = it },
                    onSubmit = {
                        // Send the typed text and the carriage return as two
                        // separate frames so Enter lands as its own keystroke —
                        // matching how native typing submits. A single
                        // "<text>\r" burst written raw to the PTY often isn't
                        // treated as accept-line (the trailing CR gets absorbed
                        // into the burst), which made the command text appear
                        // but never run. An empty field still sends a bare CR so
                        // the user can press Enter without leaving word mode.
                        val text = swipeText
                        scope.launch {
                            // Take over before the input reaches the PTY (see
                            // [ensureDriving]) so it is processed at this phone's
                            // grid, not the desktop's.
                            ensureDriving()
                            if (text.isNotEmpty()) {
                                ptySocket.send(text.toByteArray(Charsets.UTF_8))
                            }
                            ptySocket.send("\r".toByteArray(Charsets.UTF_8))
                        }
                        swipeText = ""
                    },
                    theme = terminalPalette,
                )
            }

            ImeHelperToolbar(
                ctrlSticky = ctrlSticky.value,
                onCtrlToggle = { ctrlSticky.value = !ctrlSticky.value },
                shiftSticky = shiftSticky.value,
                onShiftToggle = { shiftSticky.value = !shiftSticky.value },
                onSend = { bytes ->
                    scope.launch { ensureDriving(); ptySocket.send(bytes) }
                },
                theme = terminalPalette,
            )
        }
    }
}
