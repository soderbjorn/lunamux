/**
 * Lifecycle helpers for the headless Termux [TerminalEmulator] that
 * backs the Android terminal screen.
 *
 * [createExternalTerminalSession] returns a [TerminalSession] subclass
 * that bypasses Termux's JNI PTY path: any bytes the view writes are
 * forwarded to a [PtySocket], and the view renders the externally-fed
 * emulator. [createSyncedEmulator] wires a fresh [TerminalEmulator] to
 * that session and returns it.
 *
 * Used internally by [TerminalScreen] so the long-running session
 * subclass body lives outside the composable.
 *
 * @see TerminalScreen
 * @see PtySocket
 */
package se.soderbjorn.lunamux.android.ui

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import se.soderbjorn.lunamux.client.PtySocket
import androidx.compose.runtime.MutableState
import java.util.concurrent.atomic.AtomicReference

/**
 * Build a [TerminalSession] subclass whose I/O is wired to the supplied
 * [ptySocket] (write → server) and whose emulator is owned externally
 * (set after construction via [setEmulator]).
 *
 * Resize calls coming from the view are routed through [emulatorDispatcher]
 * with a lock on the emulator instance to serialise with append + onDraw. The
 * view's `updateSize` resizes the local emulator here but does **not** vote the
 * new dims to the server: the single (deduped) size-vote chokepoint is the
 * view's grid-size-changed listener in [TerminalScreen], so voting here too
 * double-sent every natural resize.
 *
 * **Passive mirror pin.** In the server-authoritative model the phone renders
 * the server's grid (a live mirror), not its own pixel-derived one. When
 * [passiveGridPin] holds a `(cols, rows)`, `updateSize` resizes the emulator to
 * *that* — the server grid, both axes — instead of the view's computed grid, so
 * the synthesized redraw the server sends (authored at those dims) reconstructs
 * cell-for-cell regardless of the phone's font/viewport. Null → drive the
 * view's own grid (the phone is the governor).
 *
 * Tombstone: the pin used to hold only the *columns*, with rows left to the
 * view's capacity so a server screen taller than the phone could draw would
 * bottom-anchor instead of clipping the prompt. That was measured wrong on
 * device: the mirrored stream is absolutely cursor-addressed for exactly the
 * server's screen (Claude Code's repaints anchor at `ESC[H`; the synthesized
 * redraw ends in an absolute CUP), and with `viewRows − serverRows` extra rows
 * the *content* bottom-anchors but the *addresses* do not shift with it — every
 * echo and partial repaint landed that many rows too high, splicing typed
 * characters into the middle of the mirrored transcript. The clipping problem
 * the free rows solved is handled where it belongs instead: the mirror font is
 * fitted on both axes ([PtyPresentation.passiveFontSize]), so the pinned screen
 * fits the view whenever the font is above the legibility floor.
 *
 * Input the view produces (keystrokes, mouse/focus reports) goes to
 * [handleInput], which decides per burst: while passively mirroring, ambient
 * reports are dropped and real input takes over (forces the PTY to the phone's
 * grid) before the bytes are sent; while driving, it just sends. Keeping that
 * policy in [TerminalScreen] (where the mode state lives) is why this indirects
 * through a callback rather than sending directly.
 */
internal fun createExternalTerminalSession(
    scope: CoroutineScope,
    emulatorDispatcher: CoroutineDispatcher,
    terminalViewRef: MutableState<TerminalView?>,
    ptySocket: PtySocket,
    passiveGridPin: AtomicReference<Pair<Int, Int>?>,
    handleInput: suspend (ByteArray) -> Unit,
): TerminalSession {
    return object : TerminalSession(
        "/system/bin/sh",
        "/",
        emptyArray(),
        emptyArray(),
        8192,
        null,
    ) {
        private var externalEmulator: TerminalEmulator? = null

        fun setEmulator(e: TerminalEmulator) { externalEmulator = e }

        override fun getEmulator(): TerminalEmulator? = externalEmulator

        override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
            val e = externalEmulator ?: return
            // While passively mirroring, pin BOTH axes to the server's grid: cols
            // decide wrapping, and rows decide where every absolutely-addressed
            // sequence lands — the mirrored stream is authored for exactly the
            // server's screen. (Rows used to follow the view so a too-tall screen
            // would bottom-anchor; that shifted every address and spliced echoed
            // input mid-transcript — see the pin tombstone in the factory kdoc.)
            val pin = passiveGridPin.get()
            val effectiveCols = pin?.first ?: columns
            val effectiveRows = pin?.second ?: rows
            // Resize on the CALLING (main) thread rather than hopping to the emulator
            // dispatcher. TerminalView renders and reads the buffer (onScreenUpdated ->
            // getText) on the main thread WITHOUT taking the emulator lock, so a resize
            // running on a background thread can reallocate mLines / change mTotalRows
            // underneath a live read — seen as ArrayIndexOutOfBoundsException and NPE
            // crashes, most reliably while pinch-zooming (one resize per gesture step).
            // Running it here makes resize and render mutually exclusive by being on one
            // thread; the lock still excludes the background append path.
            synchronized(e) {
                runCatching { e.resize(effectiveCols, effectiveRows, cellWidthPixels, cellHeightPixels) }
            }
            terminalViewRef.value?.invalidate()
            // Deliberately no ptySocket.resize() here — see the kdoc: the grid
            // listener in TerminalScreen is the sole, deduped voting path.
        }

        override fun initializeEmulator(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
            // no-op: emulator lifecycle is owned by the composable
        }

        override fun write(data: ByteArray, offset: Int, count: Int) {
            val copy = data.copyOfRange(offset, offset + count)
            scope.launch { handleInput(copy) }
        }

        // TerminalSession's default implementations of these forward to mClient,
        // but we passed null for that, so we must override all of them or
        // they'll NPE. Even reset() inside the emulator ctor calls onColorsChanged.
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit

        override fun writeCodePoint(prependEscape: Boolean, codePoint: Int) {
            val out = java.io.ByteArrayOutputStream(5)
            if (prependEscape) out.write(0x1b)
            when {
                codePoint <= 0x7f -> out.write(codePoint)
                codePoint <= 0x7ff -> {
                    out.write(0xc0 or (codePoint shr 6))
                    out.write(0x80 or (codePoint and 0x3f))
                }
                codePoint <= 0xffff -> {
                    out.write(0xe0 or (codePoint shr 12))
                    out.write(0x80 or ((codePoint shr 6) and 0x3f))
                    out.write(0x80 or (codePoint and 0x3f))
                }
                else -> {
                    out.write(0xf0 or (codePoint shr 18))
                    out.write(0x80 or ((codePoint shr 12) and 0x3f))
                    out.write(0x80 or ((codePoint shr 6) and 0x3f))
                    out.write(0x80 or (codePoint and 0x3f))
                }
            }
            val bytes = out.toByteArray()
            scope.launch { handleInput(bytes) }
        }
    }
}

/**
 * Build a [TerminalEmulator] sized 80x24, wire it back to [session] via
 * its `setEmulator` hook, and return it. The session must be the one
 * produced by [createExternalTerminalSession].
 */
internal fun createSyncedEmulator(session: TerminalSession): TerminalEmulator {
    val emulator = TerminalEmulator(
        session,
        80,
        24,
        0,
        0,
        8192,
        null,
    )
    // [session] is always our anonymous subclass with setEmulator;
    // expose the call via reflection to avoid leaking the type.
    val setter = session::class.java.declaredMethods.firstOrNull { it.name == "setEmulator" }
    if (setter != null) {
        setter.isAccessible = true
        setter.invoke(session, emulator)
    }
    return emulator
}
