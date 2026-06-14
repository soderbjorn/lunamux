/**
 * A simple greeting utility used to verify that the Kotlin Multiplatform
 * expect/actual mechanism is wired up correctly across all targets.
 */
package se.soderbjorn.termtastic

/**
 * Produces a platform-specific greeting string. Primarily useful as a smoke
 * test to confirm that the correct [Platform] actual implementation is
 * resolved at runtime.
 *
 * @see getPlatform
 * @see Platform
 */
class Greeting {
    private val platform = getPlatform()

    /**
     * Returns a greeting string that includes the current platform's display name.
     *
     * @return a string of the form `"Hello, <platform name>!"`
     */
    fun greet(): String {
        return "Hello, ${platform.name}!"
    }
}