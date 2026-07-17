/* NodeExternals.kt
 * Minimal Node.js API externals used by the Lunamux main process:
 * `path`, `fs`, `net`, `os`, `child_process`, plus a thin `process` accessor.
 * Each `@JsModule(...)` block declares only what the main process
 * actually calls — additions go here, not into a kitchen-sink import.
 */
@file:Suppress("ClassName", "FunctionName", "PropertyName")
package se.soderbjorn.lunamux.electron

@JsModule("path")
@JsNonModule
external object NodePath {
    fun join(vararg parts: String): String
    fun dirname(path: String): String
}

@JsModule("fs")
@JsNonModule
external object NodeFs {
    fun existsSync(path: String): Boolean
    fun readFileSync(path: String, encoding: String): String
    fun writeFileSync(path: String, data: String)
    fun mkdirSync(path: String, options: dynamic = definedExternally)
    fun rmSync(path: String, options: dynamic = definedExternally)
    fun openSync(path: String, flags: String): Int
    fun closeSync(fd: Int)
}

@JsModule("net")
@JsNonModule
external object NodeNet {
    @JsName("Socket")
    class NetSocket {
        fun setTimeout(ms: Int)
        fun once(event: String, listener: (dynamic) -> Unit)
        fun connect(port: Int, host: String)
        fun destroy()
    }
}

@JsModule("os")
@JsNonModule
external object NodeOs {
    /**
     * Returns the host's network interfaces keyed by interface name. Each value
     * is an array of address records carrying `address`, `family`
     * (`"IPv4"`/`"IPv6"`), and `internal` (true for loopback) fields.
     *
     * Called by the `get-local-ip-addresses` IPC handler in [main] to surface
     * the machine's LAN IPv4 addresses in the renderer's About dialog, so the
     * user knows which host to add from the Android and iOS clients.
     *
     * @return a dynamic object mapping interface name to an array of address
     *   records, matching Node's `os.networkInterfaces()` shape.
     */
    fun networkInterfaces(): dynamic

    /**
     * Returns the host's network name as reported by the OS (e.g.
     * `framnasnmacbook`), with no `.local` mDNS suffix.
     *
     * Called by the `get-local-ip-addresses` IPC handler in [main] to surface
     * the machine's hostname in the renderer's About dialog, so the user can
     * connect from the Android/iOS clients by name as an alternative to the
     * raw LAN IP address.
     *
     * @return the OS hostname, matching Node's `os.hostname()`.
     */
    fun hostname(): String
}

@JsModule("child_process")
@JsNonModule
external object NodeChildProcess {
    fun spawn(command: String, args: Array<String>, options: dynamic = definedExternally): NodeChildProcessChild
    fun execFileSync(command: String, options: dynamic = definedExternally): String
}

external interface NodeChildProcessChild {
    fun once(event: String, listener: (dynamic) -> Unit)
    fun unref()
}

/** Thin accessor for `globalThis.process` so we can read env, platform, argv etc. */
external val process: NodeProcess

external interface NodeProcess {
    val platform: String
    val env: dynamic
    val argv: Array<String>
    val resourcesPath: String
}

/** Node.js `__dirname` — absolute directory of the running module. */
external val __dirname: String

/**
 * Node.js `setImmediate` — schedules [callback] to run at the end of the
 * current event-loop turn. Used by [quitAndInstallUpdate] to defer the
 * app teardown until after the triggering IPC reply has been delivered.
 *
 * @param callback invoked once, on the next event-loop tick.
 */
external fun setImmediate(callback: () -> Unit)
