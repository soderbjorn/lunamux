/**
 * Shell initialization file management for OSC 7 cwd reporting.
 *
 * This file contains [ShellInitFiles], which configures shell environments
 * so that interactive shells emit OSC 7 escape sequences reporting their
 * current working directory. This enables [OscScanner] to instantly detect
 * directory changes without polling.
 *
 * Called by [TerminalSession.create] (in Application.kt) when spawning a
 * new PTY process. The method [ShellInitFiles.configureEnv] injects the
 * appropriate environment variables before the shell starts:
 *  - **zsh**: redirects `ZDOTDIR` to a generated bootstrap dir that forwards
 *    the user's full login startup chain (`.zshenv`, `.zprofile`, `.zshrc`)
 *    and installs a `chpwd_functions` hook. Forwarding every phase — not just
 *    `.zshrc` — matters because zsh resolves all startup files from `ZDOTDIR`,
 *    so a `.zshrc`-only bootstrap silently skips `~/.zprofile` (where macOS
 *    users put `brew shellenv`, i.e. the Homebrew `PATH`).
 *  - **bash**: appends an OSC 7 emitter to `PROMPT_COMMAND`.
 *  - **fish**: no action needed (fish emits OSC 7 by default).
 *
 * @see OscScanner
 * @see ProcessCwdReader
 * @see AppPaths
 */
package se.soderbjorn.termtastic.pty

import se.soderbjorn.termtastic.persistence.AppPaths
import java.io.File

/**
 * Makes interactive shells emit OSC 7 cwd reports so [OscScanner] can pick
 * them up. Most distros that ship VTE already do this; macOS bash/zsh do not.
 *
 * Strategy:
 *  - **zsh**: redirect ZDOTDIR to a generated bootstrap dir that forwards each
 *    phase of the user's real startup chain (`.zshenv` / `.zprofile` →
 *    `${'$'}{TERMTASTIC_REAL_ZDOTDIR:-${'$'}HOME}`), then in `.zshrc` restores
 *    `ZDOTDIR`, sources the real `.zshrc`, and registers a `chpwd_functions`
 *    hook. `.zlogin` is read from the restored real `ZDOTDIR` directly.
 *  - **bash**: append an OSC 7 emitter snippet to PROMPT_COMMAND.
 *  - **fish**: nothing — already emits OSC 7 by default.
 *  - **other**: nothing; the polling fallback in [ProcessCwdReader] still works.
 */
internal object ShellInitFiles {

    private val zshBootstrapDir: File by lazy {
        val dir = File(AppPaths.dataDir(), "shell-init/zsh")
        writeZshBootstrapFiles(dir)
        dir
    }

    /**
     * Materialise the zsh bootstrap startup files into [dir].
     *
     * zsh resolves every user startup file (`.zshenv`, `.zprofile`, `.zshrc`,
     * `.zlogin`) from `$ZDOTDIR`. Because we redirect `ZDOTDIR` here, the
     * bootstrap must replay each phase of the user's real startup chain, or
     * those files are silently skipped. The one that bites on macOS is
     * `~/.zprofile`: it's where `eval "$(brew shellenv)"` typically lives, so
     * skipping it drops Homebrew from `PATH` (which in turn makes `vim` resolve
     * to a stripped system build, etc.). See [zshForwardContents].
     *
     * Phase handling:
     *  - `.zshenv` / `.zprofile` forward to the user's real files while
     *    `ZDOTDIR` still points here (so the next phase is also found here).
     *  - `.zshrc` restores `ZDOTDIR`, sources the real `.zshrc`, and installs
     *    the OSC 7 hook (see [zshRcContents]). Restoring there — not earlier —
     *    keeps the recursion guard for re-execing startup chains (p10k instant
     *    prompt, `exec zsh`, …) that historically lived in `.zshrc`.
     *  - `.zlogin` needs no bootstrap file: by the time zsh reaches it,
     *    `ZDOTDIR` is restored, so the user's real `.zlogin` is read directly.
     *
     * Extracted so tests can generate the bootstrap into a temp dir and drive a
     * real shell through it.
     *
     * @param dir target directory (created if missing).
     */
    internal fun writeZshBootstrapFiles(dir: File) {
        dir.mkdirs()
        File(dir, ".zshenv").writeText(zshForwardContents(".zshenv"))
        File(dir, ".zprofile").writeText(zshForwardContents(".zprofile"))
        File(dir, ".zshrc").writeText(zshRcContents())
    }

    /**
     * Bootstrap content for an early startup phase ([fileName], e.g.
     * `.zshenv` / `.zprofile`) that simply sources the user's real counterpart.
     *
     * The real config dir is the original `ZDOTDIR` (stashed as
     * `TERMTASTIC_REAL_ZDOTDIR` by [configureZsh]) or `${'$'}HOME` when the user
     * had none. We deliberately do NOT restore `ZDOTDIR` or clear
     * `TERMTASTIC_REAL_ZDOTDIR` here — later phases (`.zprofile`, `.zshrc`)
     * still need to be resolved from this bootstrap dir; `.zshrc` does the
     * restore.
     *
     * One subtlety: the canonical way users relocate their zsh config is to
     * `export ZDOTDIR=…` from `~/.zshenv` (the only file zsh reads from a fixed
     * location). If we let that take effect, zsh would resolve `.zprofile`,
     * `.zshrc` and `.zlogin` from the user's new dir and skip our bootstrap
     * `.zshrc` — silently dropping the OSC 7 hook. So after sourcing the user's
     * file we re-pin `ZDOTDIR` back to the bootstrap and record the user's new
     * dir as `TERMTASTIC_REAL_ZDOTDIR`, so `.zshrc` still runs and then
     * restores to the user's chosen dir.
     *
     * @param fileName the startup file to forward (must start with a dot).
     * @return the bootstrap file contents.
     */
    private fun zshForwardContents(fileName: String): String = """
        # termtastic zsh bootstrap ($fileName) — forward to the user's real
        # $fileName. ZDOTDIR is redirected to this dir so .zshrc can install an
        # OSC 7 cwd hook; without forwarding each phase, files like ~/.zprofile
        # (PATH / `brew shellenv`) would be silently skipped.
        __termtastic_real_zdotdir="${'$'}{TERMTASTIC_REAL_ZDOTDIR:-${'$'}HOME}"
        __termtastic_boot_zdotdir="${'$'}ZDOTDIR"
        [[ -r "${'$'}__termtastic_real_zdotdir/$fileName" ]] && source "${'$'}__termtastic_real_zdotdir/$fileName"
        # If the user's $fileName repointed ZDOTDIR (config relocation), put it
        # back to the bootstrap so the remaining phases — including the .zshrc
        # that installs the OSC 7 hook — still run here; remember their choice so
        # .zshrc restores to it afterwards.
        if [[ "${'$'}ZDOTDIR" != "${'$'}__termtastic_boot_zdotdir" ]]; then
          export TERMTASTIC_REAL_ZDOTDIR="${'$'}ZDOTDIR"
          export ZDOTDIR="${'$'}__termtastic_boot_zdotdir"
        fi
        unset __termtastic_real_zdotdir __termtastic_boot_zdotdir
    """.trimIndent() + "\n"

    /**
     * Configure the environment variables in [env] so the shell at [shellPath]
     * will emit OSC 7 cwd reports on startup and on every directory change.
     *
     * @param shellPath absolute path to the shell binary (e.g. `/bin/zsh`)
     * @param env the mutable environment map that will be passed to the PTY process
     */
    fun configureEnv(shellPath: String, env: MutableMap<String, String>) {
        when (File(shellPath).name) {
            "zsh" -> configureZsh(env)
            "bash" -> configureBash(env)
            // fish: ships OSC 7 already.
        }
    }

    /**
     * Redirect `ZDOTDIR` to the generated bootstrap directory so our
     * `.zshrc` is sourced before the user's real one.
     *
     * @param env the environment map to modify
     */
    private fun configureZsh(env: MutableMap<String, String>) {
        // Stash whatever ZDOTDIR the user already had so the bootstrap can
        // delegate. If unset, the bootstrap falls back to ${'$'}HOME.
        env["ZDOTDIR"]?.let { env["TERMTASTIC_REAL_ZDOTDIR"] = it }
        env["ZDOTDIR"] = zshBootstrapDir.absolutePath
    }

    /**
     * Append an OSC 7 emitter `printf` snippet to `PROMPT_COMMAND` so
     * bash reports its cwd after every command.
     *
     * @param env the environment map to modify
     */
    private fun configureBash(env: MutableMap<String, String>) {
        val snippet =
            """printf '\033]7;file://%s%s\007' "${'$'}{HOSTNAME:-localhost}" "${'$'}PWD""""
        val existing = env["PROMPT_COMMAND"]
        env["PROMPT_COMMAND"] = if (existing.isNullOrBlank()) snippet else "$existing; $snippet"
    }

    /**
     * Generate the contents of the bootstrap `.zshrc` file.
     *
     * The generated script restores `ZDOTDIR`, sources the user's real
     * `.zshrc`, and installs a `chpwd_functions` hook that emits OSC 7
     * on every directory change. Also emits once immediately for the
     * initial cwd.
     *
     * @return the full `.zshrc` content as a string
     */
    private fun zshRcContents(): String = """
        # termtastic zsh bootstrap — sources the real .zshrc, then installs an
        # OSC 7 emitter so the host server can track this pane's cwd.
        #
        # We restore ZDOTDIR (and clear TERMTASTIC_REAL_ZDOTDIR) BEFORE sourcing
        # the real .zshrc. Otherwise, anything in the user's startup chain that
        # re-execs or spawns a new interactive zsh (p10k instant prompt, zinit,
        # tmux auto-attach, `exec zsh`, …) would see the bootstrap ZDOTDIR in
        # its env and re-enter this file recursively, eventually tripping zsh's
        # "job table full or recursion limit exceeded".
        if [[ -n "${'$'}TERMTASTIC_REAL_ZDOTDIR" ]]; then
          local __termtastic_real_zdotdir="${'$'}TERMTASTIC_REAL_ZDOTDIR"
          export ZDOTDIR="${'$'}__termtastic_real_zdotdir"
          unset TERMTASTIC_REAL_ZDOTDIR
          [[ -r "${'$'}__termtastic_real_zdotdir/.zshrc" ]] && source "${'$'}__termtastic_real_zdotdir/.zshrc"
          unset __termtastic_real_zdotdir
        else
          unset ZDOTDIR
          [[ -r "${'$'}HOME/.zshrc" ]] && source "${'$'}HOME/.zshrc"
        fi

        __termtastic_emit_osc7() {
          # zsh's parameter expansion handles the only character that commonly
          # appears in cwds and needs encoding (space). Everything else is left
          # alone — the server URL-decodes leniently.
          local enc=${'$'}{PWD// /%20}
          printf '\e]7;file://%s%s\e\\' "${'$'}{HOST:-localhost}" "${'$'}enc"
        }
        typeset -ga chpwd_functions
        chpwd_functions+=(__termtastic_emit_osc7)
        # Emit once for the initial cwd so the server learns it before the
        # first 'cd' happens.
        __termtastic_emit_osc7
    """.trimIndent() + "\n"
}
