/**
 * Demo-mode entry point detection shared by every client.
 *
 * Demo mode is selected purely by a magic host name: the web client passes
 * it when the page is opened on the `/demo` URL (or with a `demo` query/hash
 * marker), and mobile users add a server entry whose host is simply `demo`.
 * A [se.soderbjorn.lunamux.client.LunamuxClient] constructed for that
 * host runs entirely against the in-process [DemoServer] simulation and
 * never makes a network call.
 *
 * @see DemoServer
 * @see se.soderbjorn.lunamux.client.LunamuxClient.demoMode
 */
package se.soderbjorn.lunamux.client.demo

/**
 * The magic host name that switches a client into demo mode. Entering this
 * as the host of a server entry on mobile (any port) connects to the
 * built-in simulation instead of a real server.
 */
const val DEMO_HOST: String = "demo"

/**
 * Whether [host] selects the in-process demo simulation.
 *
 * Called by [se.soderbjorn.lunamux.client.LunamuxClient] at
 * construction time. Case-insensitive and whitespace-tolerant so a user
 * typing `Demo ` on a phone keyboard still lands in the demo.
 *
 * @param host the host part of the server URL the client was created for.
 * @return `true` when the client should simulate the server locally.
 */
fun isDemoHost(host: String): Boolean = host.trim().lowercase() == DEMO_HOST
