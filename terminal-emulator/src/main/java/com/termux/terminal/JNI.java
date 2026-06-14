package com.termux.terminal;

/**
 * Native methods for creating and managing pseudoterminal subprocesses. C code is in jni/termux.c.
 */
final class JNI {

    // The original Termux build loads a native "termux" library here to
    // drive an on-device PTY. Termtastic-Android is a remote-only client —
    // the emulator is fed bytes from a WebSocket, never from a local PTY —
    // so the vendored build has the native JNI code removed, and this
    // static initialiser is a no-op. TerminalSession.java still references
    // these native methods, but TerminalSession is not used by the remote
    // client (we talk to TerminalEmulator directly via its TerminalOutput
    // callback), so those methods are never invoked at runtime.

    /**
     * Create a subprocess. Differs from {@link ProcessBuilder} in that a pseudoterminal is used to communicate with the
     * subprocess.
     * <p/>
     * Callers are responsible for calling {@link #close(int)} on the returned file descriptor.
     *
     * @param cmd       The command to execute
     * @param cwd       The current working directory for the executed command
     * @param args      An array of arguments to the command
     * @param envVars   An array of strings of the form "VAR=value" to be added to the environment of the process
     * @param processId A one-element array to which the process ID of the started process will be written.
     * @return the file descriptor resulting from opening /dev/ptmx master device. The sub process will have opened the
     * slave device counterpart (/dev/pts/$N) and have it as stdint, stdout and stderr.
     */
    public static native int createSubprocess(String cmd, String cwd, String[] args, String[] envVars, int[] processId, int rows, int columns, int cellWidth, int cellHeight);

    /** Set the window size for a given pty, which allows connected programs to learn how large their screen is. */
    public static native void setPtyWindowSize(int fd, int rows, int cols, int cellWidth, int cellHeight);

    /**
     * Causes the calling thread to wait for the process associated with the receiver to finish executing.
     *
     * @return if >= 0, the exit status of the process. If < 0, the signal causing the process to stop negated.
     */
    public static native int waitFor(int processId);

    /** Close a file descriptor through the close(2) system call. */
    public static native void close(int fileDescriptor);

}
