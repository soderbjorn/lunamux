import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * examples/snake-mcp — a standalone MCP client that plays Snake inside a
 * Termtastic agent-console pane, proving the Groups F+G tool surface end
 * to end (screen_draw/screen_present out, console_poll_input in).
 *
 * The main source set has NO dependency on the server — it talks to the
 * public /mcp endpoint over plain JSON-RPC (java.net.http + kotlinx
 * serialization), exactly like an external client would. Only the test
 * source set depends on :server, to boot an in-process listener and feed
 * synthetic key events.
 */
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

group = "se.soderbjorn.termtastic.examples"
version = "1.0.0"

// Match the server's Java 11 pin so the example builds in the same CI env.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

application {
    mainClass.set("se.soderbjorn.termtastic.examples.snake.MainKt")
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.testJunit)
    // The headless test boots the real MCP routes in-process and injects
    // synthetic player input into the agent session's input channel.
    testImplementation(project(":server"))
    testImplementation(projects.clientServer)
    testImplementation(libs.ktor.serverCore)
    testImplementation(libs.ktor.serverNetty)
    testImplementation(libs.kotlinx.coroutines.core)
}
