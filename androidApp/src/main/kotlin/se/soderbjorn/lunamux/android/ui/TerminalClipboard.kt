/**
 * System-clipboard bridge for the Android terminal.
 *
 * The Termux view and emulator both hand clipboard work to their session's
 * [com.termux.terminal.TerminalOutput] callbacks rather than touching
 * [ClipboardManager] themselves: the text-selection action bar calls
 * `onCopyTextToClipboard` / `onPasteTextFromClipboard`, and a program's OSC 52
 * sequence calls `onCopyTextToClipboard` from inside the emulator. Lunamux's
 * session (see [createExternalTerminalSession]) is the only implementation of
 * those callbacks in this app, and this object is what it delegates to.
 *
 * Everything here runs on the main thread. Two reasons: the emulator's OSC 52
 * path arrives on the background emulator dispatcher, and [Toast] needs a
 * looper; and `ClipboardManager` is a UI-thread API in practice — several OEM
 * implementations post internally and misbehave off it.
 *
 * @see createExternalTerminalSession
 * @see TerminalScreen
 */
package se.soderbjorn.lunamux.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Reads and writes the system clipboard on behalf of the terminal session.
 *
 * Called by the [com.termux.terminal.TerminalSession] subclass built in
 * [createExternalTerminalSession] — which is reached from the text-selection
 * action bar ("Copy" / "Paste") and from the emulator's OSC 52 handler.
 */
internal object TerminalClipboard {

    /** Label attached to the clip, shown by the system's clipboard UI. */
    private const val CLIP_LABEL = "Lunamux terminal"

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Put [text] on the system clipboard.
     *
     * Below API 33 the platform gives no visible acknowledgement, so a short
     * toast is shown — without it a successful copy is indistinguishable from
     * the no-op this used to be. From API 33 onwards Android shows its own
     * clipboard confirmation and an app toast would merely duplicate it.
     *
     * @param context any context; the application context is enough.
     * @param text the text to copy. Null or empty is ignored — a program can
     *   legitimately clear its OSC 52 selection, and there is nothing to say
     *   about it.
     * @see readFromClipboard
     */
    fun copyToClipboard(context: Context, text: String?) {
        if (text.isNullOrEmpty()) return
        onMainThread {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@onMainThread
            runCatching { clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text)) }
                .onSuccess {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }

    /**
     * Read the clipboard and hand its text to [sink].
     *
     * Asynchronous because the caller may be on the emulator dispatcher; [sink]
     * always runs on the main thread. It is not invoked at all when the
     * clipboard holds nothing usable, so callers need no empty-case branch.
     *
     * @param context any context; the application context is enough.
     * @param sink receives the clipboard text, on the main thread.
     * @see copyToClipboard
     */
    fun readFromClipboard(context: Context, sink: (String) -> Unit) {
        onMainThread {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@onMainThread
            // coerceToText rather than getText: a clip can carry an intent, a URI or
            // styled HTML, and every one of those has a sensible text rendering that a
            // terminal can accept. Wrapped because reading the clipboard is an IPC into
            // the system service and a dead source app can surface as an exception.
            val text = runCatching {
                clipboard.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(context)
                    ?.toString()
            }.getOrNull()
            if (!text.isNullOrEmpty()) sink(text)
        }
    }

    /**
     * Whether the clipboard currently holds something [readFromClipboard] could
     * return, used to enable or grey out the selection bar's "Paste" item.
     *
     * Must be called on the main thread — the action-mode callbacks that ask are
     * already there. Note that from API 29 the platform answers false whenever
     * the app is not focused; that is exactly when no action bar is up, so it
     * costs nothing here.
     *
     * @param context any context; the application context is enough.
     * @return true if a paste would produce text.
     */
    fun hasText(context: Context): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        return runCatching { clipboard.hasPrimaryClip() }.getOrDefault(false)
    }

    /**
     * Run [block] on the main thread, inline when already there.
     *
     * Inline rather than always posting so that a copy triggered from the action
     * bar completes before the action mode is torn down.
     *
     * @param block the work to run.
     */
    private inline fun onMainThread(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { block() }
    }
}
