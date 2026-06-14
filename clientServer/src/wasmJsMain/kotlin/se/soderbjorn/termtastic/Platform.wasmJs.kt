/**
 * Kotlin/WasmJS-specific [Platform] implementation for WebAssembly-based
 * browser clients.
 */
package se.soderbjorn.termtastic

/**
 * [Platform] implementation for the Kotlin/WasmJS compilation target.
 *
 * @see Platform
 * @see getPlatform
 */
class WasmPlatform: Platform {
    /** Returns the fixed string `"Web with Kotlin/Wasm"`. */
    override val name: String = "Web with Kotlin/Wasm"
}

/**
 * Returns a [WasmPlatform] instance.
 *
 * @return the Kotlin/WasmJS [Platform] implementation
 */
actual fun getPlatform(): Platform = WasmPlatform()