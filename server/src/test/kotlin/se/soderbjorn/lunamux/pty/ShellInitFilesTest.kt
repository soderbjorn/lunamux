/**
 * Regression tests for [ShellInitFiles]: the zsh OSC-7 bootstrap redirects
 * `ZDOTDIR`, and zsh resolves ALL user startup files from `$ZDOTDIR`. If the
 * bootstrap dir only contains a `.zshrc`, the login-shell files `~/.zshenv`
 * and `~/.zprofile` are silently skipped — which on macOS drops Homebrew's
 * `PATH` setup (`eval "$(brew shellenv)"` typically lives in `~/.zprofile`)
 * and makes `vim` resolve to a stripped system build.
 *
 * These drive a real `zsh -l -i` through the generated bootstrap and assert
 * that (a) every phase of the user's startup chain runs, (b) the OSC-7 hook is
 * still installed even when the user relocates their config via
 * `export ZDOTDIR` in `~/.zshenv`, and (c) `ZDOTDIR` ends up at the user's
 * chosen dir.
 */
package se.soderbjorn.lunamux.pty

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShellInitFilesTest {

    private fun zshPath(): String? =
        listOf("/bin/zsh", "/usr/bin/zsh").firstOrNull { File(it).canExecute() }

    /** Raw stdout of the spawned shell, plus the parsed `TTVARS:` map. */
    private data class ShellResult(val raw: String, val vars: Map<String, String>)

    /**
     * Materialises the real bootstrap into a temp dir, then runs `zsh -l -i -c`
     * with [homeFiles] written into a fake `$HOME` and [extraEnv] applied. The
     * command prints a `TTVARS:`-prefixed line plus the final `ZDOTDIR`.
     */
    private fun runLoginShell(
        homeFiles: Map<String, String>,
        extraEnv: Map<String, String> = emptyMap(),
    ): ShellResult {
        val zsh = zshPath()!!
        val fakeHome = createTempDirectory("tt-home").toFile().apply { deleteOnExit() }
        homeFiles.forEach { (name, body) -> File(fakeHome, name).apply { writeText(body); deleteOnExit() } }
        val bootstrap = createTempDirectory("tt-bootstrap").toFile().apply { deleteOnExit() }
        ShellInitFiles.writeZshBootstrapFiles(bootstrap)
        bootstrap.listFiles()?.forEach { it.deleteOnExit() }

        // The bootstrap .zshrc emits an OSC 7 escape (no trailing newline) at
        // startup; it lands right before this output, so we slice from a sentinel.
        val pb = ProcessBuilder(
            zsh, "-l", "-i", "-c",
            "print -r -- \"TTVARS:TT_ZSHENV=\$TT_ZSHENV;TT_ZPROFILE=\$TT_ZPROFILE;" +
                "TT_ZSHRC=\$TT_ZSHRC;TT_ZLOGIN=\$TT_ZLOGIN;ZDOTDIR=\$ZDOTDIR\"",
        )
        pb.environment().apply {
            clear()
            put("HOME", fakeHome.absolutePath)
            put("ZDOTDIR", bootstrap.absolutePath)
            put("TERM", "xterm-256color")
            put("PATH", "/usr/bin:/bin:/usr/sbin:/sbin")
            putAll(extraEnv)
        }
        val proc = pb.start()
        val raw = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        val line = (raw.lineSequence().firstOrNull { it.contains("TTVARS:") } ?: "").substringAfter("TTVARS:")
        val vars = line.split(";").mapNotNull {
            val i = it.indexOf('='); if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()
        return ShellResult(raw, vars)
    }

    /** True if the spawned shell emitted the OSC 7 cwd report (hook installed). */
    private fun ShellResult.osc7Emitted() = raw.contains("]7;file://")

    @Test
    fun bootstrapPreservesTheFullLoginStartupChain() {
        assumeTrue("zsh not available", zshPath() != null)
        val r = runLoginShell(
            mapOf(
                ".zshenv" to "export TT_ZSHENV=env_ran\n",
                ".zprofile" to "export TT_ZPROFILE=profile_ran\n",
                ".zshrc" to "export TT_ZSHRC=rc_ran\n",
                ".zlogin" to "export TT_ZLOGIN=login_ran\n",
            ),
        )
        assertEquals("env_ran", r.vars["TT_ZSHENV"], "user's ~/.zshenv was skipped")
        assertEquals("profile_ran", r.vars["TT_ZPROFILE"], "user's ~/.zprofile was skipped")
        assertEquals("rc_ran", r.vars["TT_ZSHRC"], "user's ~/.zshrc was not sourced")
        assertEquals("login_ran", r.vars["TT_ZLOGIN"], "user's ~/.zlogin was skipped")
        assertTrue(r.osc7Emitted(), "OSC 7 cwd hook was not installed")
    }

    @Test
    fun hookSurvivesWhenZshenvRelocatesZdotdir() {
        assumeTrue("zsh not available", zshPath() != null)
        val custom = createTempDirectory("tt-custom").toFile().apply { deleteOnExit() }
        File(custom, ".zprofile").apply { writeText("export TT_ZPROFILE=custom_profile\n"); deleteOnExit() }
        File(custom, ".zshrc").apply { writeText("export TT_ZSHRC=custom_rc\n"); deleteOnExit() }
        File(custom, ".zlogin").apply { writeText("export TT_ZLOGIN=custom_login\n"); deleteOnExit() }

        val r = runLoginShell(
            // The canonical config-relocation pattern: ~/.zshenv repoints ZDOTDIR.
            mapOf(".zshenv" to "export TT_ZSHENV=env_ran\nexport ZDOTDIR=${custom.absolutePath}\n"),
        )
        // The user's relocated config must fully load …
        assertEquals("env_ran", r.vars["TT_ZSHENV"])
        assertEquals("custom_profile", r.vars["TT_ZPROFILE"], "relocated ~/.zprofile not sourced")
        assertEquals("custom_rc", r.vars["TT_ZSHRC"], "relocated .zshrc not sourced")
        assertEquals("custom_login", r.vars["TT_ZLOGIN"], "relocated .zlogin not sourced")
        // … and ZDOTDIR must end at the user's chosen dir …
        assertEquals(custom.absolutePath, r.vars["ZDOTDIR"], "ZDOTDIR not restored to user's dir")
        // … AND the OSC-7 hook must still be installed (the regression).
        assertTrue(r.osc7Emitted(), "OSC 7 hook lost when ~/.zshenv relocates ZDOTDIR")
    }

    @Test
    fun forwardsToEnvironmentZdotdirWhenSet() {
        assumeTrue("zsh not available", zshPath() != null)
        val real = createTempDirectory("tt-realzdot").toFile().apply { deleteOnExit() }
        File(real, ".zprofile").apply { writeText("export TT_ZPROFILE=real_profile\n"); deleteOnExit() }
        File(real, ".zshrc").apply { writeText("export TT_ZSHRC=real_rc\n"); deleteOnExit() }

        // Mirrors configureZsh: original ZDOTDIR stashed as LUNAMUX_REAL_ZDOTDIR.
        val r = runLoginShell(
            homeFiles = emptyMap(),
            extraEnv = mapOf("LUNAMUX_REAL_ZDOTDIR" to real.absolutePath),
        )
        assertEquals("real_profile", r.vars["TT_ZPROFILE"], "env ZDOTDIR's .zprofile not sourced")
        assertEquals("real_rc", r.vars["TT_ZSHRC"], "env ZDOTDIR's .zshrc not sourced")
        assertEquals(real.absolutePath, r.vars["ZDOTDIR"], "ZDOTDIR not restored to env value")
        assertTrue(r.osc7Emitted(), "OSC 7 hook not installed with env ZDOTDIR")
    }
}
