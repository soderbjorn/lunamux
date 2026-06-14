/**
 * OS-level process working directory reader (polling fallback).
 *
 * This file contains [ProcessCwdReader], which reads a process's current
 * working directory directly from the operating system. It serves as a
 * fallback for shells that do not emit OSC 7 escape sequences (or when the
 * shell init bootstrap from [ShellInitFiles] did not take effect).
 *
 * Used by [TerminalSession]'s polling coroutine, which calls [ProcessCwdReader.read]
 * every 3 seconds with the PTY's PID and updates [TerminalSession.cwd] if the
 * directory has changed.
 *
 * Platform support:
 *  - Linux: reads the `/proc/<pid>/cwd` symlink.
 *  - macOS: runs `lsof -a -p <pid> -d cwd -Fn`.
 *  - Windows: not supported (returns null); ConPTY shells typically handle OSC 7.
 *
 * @see Osc7Scanner
 * @see ShellInitFiles
 * @see TerminalSession
 */
package se.soderbjorn.termtastic.pty

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Polling fallback for shells that don't (or can't) emit OSC 7 cwd reports.
 * Reads the running shell's own working directory directly from the OS.
 *
 * - **Linux**: `/proc/<pid>/cwd` is a symlink to the directory.
 * - **macOS**: `lsof -a -p <pid> -d cwd -Fn` — small fork, but vastly simpler
 *   than the JNA dance for `proc_pidinfo` and the cost is fine for a
 *   ~3 s polling cadence.
 * - **Windows**: not supported (returns null). ConPTY-hosted shells generally
 *   handle OSC 7 anyway.
 */
internal object ProcessCwdReader {

    private val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    private val isMac = os.contains("mac") || os.contains("darwin")
    private val isLinux = !isMac && os.contains("linux")

    /**
     * Read the current working directory of process [pid].
     *
     * @param pid the OS process id to query
     * @return the absolute path of the process's cwd, or null on failure
     *         or unsupported platform
     */
    fun read(pid: Long): String? = try {
        when {
            isLinux -> Files.readSymbolicLink(Path.of("/proc/$pid/cwd")).toString()
            isMac -> readMacViaLsof(pid)
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Read the cwd of [pid] on macOS by running `lsof -a -p <pid> -d cwd -Fn`.
     *
     * @param pid the process id to query
     * @return the cwd path, or null if `lsof` fails or times out
     */
    private fun readMacViaLsof(pid: Long): String? {
        val proc = ProcessBuilder("lsof", "-a", "-p", pid.toString(), "-d", "cwd", "-Fn")
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        if (!proc.waitFor(2, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return null
        }
        if (proc.exitValue() != 0) return null
        // Output: one record per line; lines starting with 'n' carry the path.
        var cwd: String? = null
        for (line in output.lineSequence()) {
            if (line.startsWith("n")) cwd = line.substring(1)
        }
        return cwd?.takeIf { it.isNotEmpty() }
    }
}
