/**
 * Entry point for the Snake MCP driver.
 *
 * Usage (see examples/snake-mcp/README.md for the full walkthrough):
 *
 * ```
 * ./gradlew :examples:snake-mcp:run --args="--token <mcp-token> [--url https://localhost:8443/mcp] [--insecure]"
 * ```
 *
 * Then bring the "Snake" pane into view in any Lunamux client and
 * steer with the arrow keys; `q` quits.
 *
 * @see SnakeDriver
 */
package se.soderbjorn.lunamux.examples.snake

import kotlin.system.exitProcess

/**
 * Parse arguments, connect, and run the game loop.
 *
 * @param args `--token <t>` (required, a read+write MCP token),
 *   `--url <endpoint>` (default `https://localhost:8443/mcp`),
 *   `--insecure` (accept the localhost self-signed TLS cert),
 *   `--fps <n>` (default 10).
 */
fun main(args: Array<String>) {
    var url = "https://localhost:8443/mcp"
    var token: String? = null
    var insecure = false
    var fps = 10
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--url" -> url = args.getOrNull(++i) ?: usage()
            "--token" -> token = args.getOrNull(++i) ?: usage()
            "--insecure" -> insecure = true
            "--fps" -> fps = args.getOrNull(++i)?.toIntOrNull() ?: usage()
            else -> usage()
        }
        i++
    }
    val authToken = token ?: usage()

    val client = McpClient(url, authToken, insecureTls = insecure)
    println("snake-mcp: connecting to $url …")
    client.initialize()
    val driver = SnakeDriver(client)
    driver.open()
    println("snake-mcp: console open (window ${driver.windowId}) — play with the arrow keys in Lunamux; q quits.")
    driver.runLoop(fps = fps)
    println("snake-mcp: bye — final score ${driver.game.score}")
}

/** Print usage and exit. */
private fun usage(): Nothing {
    System.err.println(
        "usage: snake-mcp --token <mcp-token> [--url https://localhost:8443/mcp] [--insecure] [--fps 10]",
    )
    exitProcess(2)
}
