package com.termux.terminal;

/**
 * The Android-independent slice of {@link TerminalSessionClient}: the callbacks a bare
 * {@link TerminalEmulator} needs — cursor style/visibility plus logging — none of which
 * reference the Android-bound TerminalSession.
 *
 * <p>This split lets {@link TerminalEmulator} and {@link Logger} live in the pure-JVM
 * :terminal-core module (shared by the Android app and the headless server), while the
 * full {@link TerminalSessionClient} — whose remaining callbacks take a TerminalSession —
 * stays in the Android :terminal-emulator module. The Android app still implements the
 * complete {@link TerminalSessionClient}; this interface merely names the subset the
 * emulator core actually depends on, so no implementor changes.
 */
public interface TerminalSessionClientBase {

    void onTerminalCursorStateChange(boolean state);

    Integer getTerminalCursorStyle();

    void logError(String tag, String message);

    void logWarn(String tag, String message);

    void logInfo(String tag, String message);

    void logDebug(String tag, String message);

    void logVerbose(String tag, String message);

    void logStackTraceWithMessage(String tag, String message, Exception e);

    void logStackTrace(String tag, Exception e);

}
