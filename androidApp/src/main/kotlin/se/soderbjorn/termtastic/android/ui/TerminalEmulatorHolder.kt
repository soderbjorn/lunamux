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
package se.soderbjorn.termtastic.android.ui

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import se.soderbjorn.termtastic.client.PtySocket
import androidx.compose.runtime.MutableState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Build a [TerminalSession] subclass whose I/O is wired to the supplied
 * [ptySocket] (write → server) and whose emulator is owned externally
 * (set after construction via [setEmulator]).
 *
 * Resize calls coming from the view are routed through [emulatorDispatcher]
 * with a lock on the emulator instance to serialise with append + onDraw.
 * If [applyingServerSize] is set we skip echoing the new dims back to the
 * server — this is what stops Android from clobbering a web client's
 * Reformat.
 */
internal fun createExternalTerminalSession(
    scope: CoroutineScope,
    emulatorDispatcher: CoroutineDispatcher,
    terminalViewRef: MutableState<TerminalView?>,
    applyingServerSize: AtomicBoolean,
    ptySocket: PtySocket,
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
            val e = externalEmulator
            if (e != null) {
                scope.launch(emulatorDispatcher) {
                    synchronized(e) {
                        runCatching { e.resize(columns, rows, cellWidthPixels, cellHeightPixels) }
                    }
                    terminalViewRef.value?.post { terminalViewRef.value?.invalidate() }
                }
            }
            if (!applyingServerSize.get()) {
                scope.launch { runCatching { ptySocket.resize(columns, rows) } }
            }
        }

        override fun initializeEmulator(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
            // no-op: emulator lifecycle is owned by the composable
        }

        override fun write(data: ByteArray, offset: Int, count: Int) {
            val copy = data.copyOfRange(offset, offset + count)
            scope.launch { ptySocket.send(copy) }
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
            scope.launch { ptySocket.send(bytes) }
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
