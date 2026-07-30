package com.termux.terminal;

/**
 * The interface for communication between {@link TerminalSession} and its client. It is used to
 * send callbacks to the client when {@link TerminalSession} changes or for sending other
 * back data to the client like logs.
 *
 * <p>Extends {@link TerminalSessionClientBase}, which declares the Android-independent
 * cursor/logging callbacks the emulator core ({@link TerminalEmulator}) relies on and lives in
 * :terminal-core. The session-scoped callbacks below reference the Android-bound
 * {@link TerminalSession}, so this interface stays in :terminal-emulator. The method set is
 * unchanged from before the split, so implementors (e.g. TerminalView, the Android app) are
 * unaffected.
 */
public interface TerminalSessionClient extends TerminalSessionClientBase {

    void onTextChanged(TerminalSession changedSession);

    void onTitleChanged(TerminalSession changedSession);

    void onSessionFinished(TerminalSession finishedSession);

    void onCopyTextToClipboard(TerminalSession session, String text);

    void onPasteTextFromClipboard(TerminalSession session);

    void onBell(TerminalSession session);

    void onColorsChanged(TerminalSession session);

}
