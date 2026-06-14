/* NodeExternals.kt
 * Minimal Node.js API externals used by the termtastic main process:
 * `path`, `fs`, `net`, `child_process`, plus a thin `process` accessor.
 * Each `@JsModule(...)` block declares only what the main process
 * actually calls — additions go here, not into a kitchen-sink import.
 */
@file:Suppress("ClassName", "FunctionName", "PropertyName")
package se.soderbjorn.termtastic.electron

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
