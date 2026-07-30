/*
 * Build script for :terminal-core — the pure-JVM extraction of the vendored
 * Termux terminal emulator core (ByteQueue, TerminalEmulator, TerminalBuffer,
 * KeyHandler, WcWidth, …). It is consumed by two very different runtimes:
 *   - :terminal-emulator (Android library) re-exposes it via `api`, so the
 *     Android app keeps importing com.termux.terminal.* unchanged; and
 *   - :server (headless JVM) uses it to run a canonical server-side screen.
 * Because both depend on it, this module MUST stay free of any Android
 * dependency and emit Java 11 bytecode — the floor shared by the Android
 * toolchain and the trimmed Java runtime bundled into the Electron app.
 */
plugins {
    `java-library`
}

java {
    // Match :server (and thus the bundled JRE). Compiled by whatever JDK Gradle
    // runs on (currently 21), targeting 11 keeps the class files loadable there.
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    testImplementation(libs.junit)
}

// The vendored suite is written in JUnit 3 style (junit.framework.TestCase);
// the JUnit 4 engine runs those TestCase subclasses via its built-in runner.
tasks.named<Test>("test") {
    useJUnit()
}
