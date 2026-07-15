/**
 * Terminal emulator screen for the Lunamux Android app.
 *
 * Hosts a full xterm-compatible terminal session rendered by a Termux
 * [com.termux.view.TerminalView]. Composes the four supporting helpers
 * extracted from this file:
 *  - [TerminalEmulatorHolder] — the externally-fed [TerminalSession]
 *    subclass + companion [TerminalEmulator] factory.
 *  - [TerminalThemeResolver] — palette resolution + emulator colour
 *    application.
 *  - [ImeHelperToolbar] — sticky modifier toolbar above the soft keyboard.
 *  - [SwipeInputBar] — gesture-typing input.
 *
 * Navigated to from [TreeScreen] when the user taps a terminal leaf
 * pane.
 *
 * @see TreeScreen
 * @see se.soderbjorn.lunamux.android.net.ConnectionHolder
 */
package se.soderbjorn.lunamux.android.ui

import se.soderbjorn.darkness.core.*

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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import se.soderbjorn.lunamux.android.net.ConnectionHolder

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
 * Local terminal grid metrics — cols/rows of the TerminalView's emulator.
 * Cached in Compose state and refreshed whenever the grid size changes,
 * so the Reformat button can re-assert the view's natural size.
 */
private data class AndroidGridDims(
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

    val ptySocket = remember(sessionId) { client.openPtySocket(sessionId) }
    val ctrlSticky = remember { mutableStateOf(false) }
    val shiftSticky = remember { mutableStateOf(false) }
    var swipeInputActive by remember { mutableStateOf(false) }
    var swipeText by remember { mutableStateOf("") }
    val terminalViewRef = remember { mutableStateOf<TerminalView?>(null) }

    var localGrid by remember(sessionId) {
        mutableStateOf<AndroidGridDims?>(null)
    }

    val applyingServerSize = remember(sessionId) { AtomicBoolean(false) }

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
            applyingServerSize = applyingServerSize,
            ptySocket = ptySocket,
        )
    }

    val emulator = remember(sessionId) { createSyncedEmulator(session) }

    LaunchedEffect(sessionId) {
        ptySocket.output.collect { chunk ->
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

    LaunchedEffect(sessionId) {
        ptySocket.ptySize.collect { size ->
            if (size == null) return@collect
            val (cols, rows) = size
            val view = terminalViewRef.value ?: return@collect
            val renderer = view.mRenderer ?: return@collect
            val cellW = renderer.fontWidth.toInt().coerceAtLeast(1)
            val cellH = renderer.fontLineSpacing.coerceAtLeast(1)
            applyingServerSize.set(true)
            withContext(emulatorDispatcher) {
                synchronized(emulator) {
                    runCatching { emulator.resize(cols, rows, cellW, cellH) }
                }
            }
            view.post {
                view.onScreenUpdated()
                view.post { applyingServerSize.set(false) }
            }
        }
    }

    var hasAutoReformatted by remember(sessionId) { mutableStateOf(false) }
    LaunchedEffect(sessionId, localGrid) {
        if (hasAutoReformatted) return@LaunchedEffect
        val natural = localGrid ?: return@LaunchedEffect
        if (natural.cols <= 0 || natural.rows <= 0) return@LaunchedEffect
        hasAutoReformatted = true
        runCatching { ptySocket.forceResize(natural.cols, natural.rows) }
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
                            scope.launch {
                                runCatching { ptySocket.forceResize(natural.cols, natural.rows) }
                            }
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
                    modifier = Modifier
                        .fillMaxSize(),
                    factory = { context ->
                        val view = TerminalView(context, null)
                        val currentTextSize = intArrayOf(30)
                        view.setTextSize(currentTextSize[0])
                        view.setTypeface(TerminalFont.typeface(context))
                        view.isFocusable = true
                        view.isFocusableInTouchMode = true
                        var lastSent: Pair<Int, Int>? = null
                        view.setOnTerminalGridSizeChangedListener { cols, rows ->
                            if (applyingServerSize.get()) return@setOnTerminalGridSizeChangedListener
                            val next = cols to rows
                            localGrid = AndroidGridDims(cols = cols, rows = rows)
                            if (next != lastSent) {
                                lastSent = next
                                scope.launch { runCatching { ptySocket.resize(cols, rows) } }
                            }
                        }
                        view.setTerminalViewClient(object : TerminalViewClient {
                            override fun onScale(scale: Float): Float {
                                if (scale < 0.95f || scale > 1.05f) {
                                    val step = if (scale > 1f) 1 else -1
                                    val next = (currentTextSize[0] + step).coerceIn(14, 96)
                                    if (next != currentTextSize[0]) {
                                        currentTextSize[0] = next
                                        view.setTextSize(next)
                                    }
                                    return 1f
                                }
                                return scale
                            }
                            override fun onSingleTapUp(e: android.view.MotionEvent?) {
                                view.requestFocus()
                                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                                imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
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
                        view
                    },
                    update = { view ->
                        applyTerminalColors(view, emulator, terminalPalette)
                        // Recomposition (e.g. our own scroll-pause state changes)
                        // must not yank a scrolled-up user back to the bottom:
                        // onScreenUpdated() force-snaps, so only call it when at
                        // the bottom and otherwise just repaint in place.
                        if (view.topRow < 0) view.invalidate() else view.onScreenUpdated()
                    },
                )

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
                    scope.launch { ptySocket.send(bytes) }
                },
                theme = terminalPalette,
            )
        }
    }
}
