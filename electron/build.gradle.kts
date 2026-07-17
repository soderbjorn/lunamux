import org.gradle.api.file.FileSystemOperations
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.process.ExecOperations
import javax.inject.Inject

// Thin Gradle wrapper around the Electron desktop shell.
// The Kotlin/JS main-process logic lives in the :electron-main module
// (compiled to resources/main/ here via copyMainBundle); the npm
// project below holds package.json, the preload script, the app icon,
// and the staged server jar.
//
// :electron:run is wired to the dev server, which runs TLS on 8444 (see
// server/build.gradle.kts). The packaged build talks to SERVER_TLS_PORT
// (8443) — that path is handled inside ElectronMain.kt, not here. The
// dev URL uses 127.0.0.1 explicitly to dodge IPv4/IPv6 dual-stack
// surprises against an IPv4-bound listener.

plugins {
    // Provides the JavaToolchainService used by :bundleJre to locate a
    // Temurin JDK for jlink. This project applies no other JVM plugin —
    // it is just the npm wrapper around electron-builder.
    id("jvm-toolchains")
}

val devServerPort = 8444
val targetUrl = "https://127.0.0.1:$devServerPort"

val nodeModulesDir = layout.projectDirectory.dir("node_modules")
val distDir = layout.projectDirectory.dir("dist")
val resourcesDir = layout.projectDirectory.dir("resources")
val mainResourcesDir = resourcesDir.dir("main")
val jreDir = resourcesDir.dir("jre")

// Resolve npm via PATH so Gradle's subprocess can find it even when
// /opt/homebrew/bin isn't on the JVM's default search path.
val npmExec: String = System.getenv("PATH")
    ?.split(File.pathSeparator)
    ?.map { File(it, "npm") }
    ?.firstOrNull { it.canExecute() }
    ?.absolutePath
    ?: "npm"

val npmInstall by tasks.registering(Exec::class) {
    group = "electron"
    description = "Install Electron npm dependencies."
    workingDir = projectDir
    commandLine(npmExec, "install")
    inputs.file("package.json")
    outputs.dir(nodeModulesDir)
}

// Copy the Kotlin/JS Node bundle (electron-main module) into
// electron/resources/main/. The stub main.js requires the entry file
// from this directory at startup. Matches notegrow's pattern.
val copyMainBundle by tasks.registering(Copy::class) {
    group = "electron"
    description = "Copy the Kotlin/JS Node bundle into electron/resources/main."
    val mainCompile = project(":electron-main").tasks.named("jsProductionExecutableCompileSync")
    dependsOn(mainCompile)
    from(project(":electron-main").layout.buildDirectory.dir("compileSync/js/main/productionExecutable/kotlin"))
    into(mainResourcesDir)
}

tasks.register<Exec>("run") {
    group = "electron"
    description = "Launch the Electron desktop shell pointing at the local server."
    dependsOn(npmInstall, copyMainBundle)
    workingDir = projectDir
    environment("LUNAMUX_URL", targetUrl)
    commandLine(npmExec, "start")
}

// Launch the Electron shell in DEMO mode: a fully self-contained run that
// needs no backend server and no Java. The :web bundle is served on a free
// loopback port and the renderer is pointed at it with the `?demo` flag, so
// the in-process DemoServer (client/.../demo/DemoServer.kt) drives the UI off
// canned fixtures.
//
// Gradle builds the web + electron-main bundles and installs npm deps via the
// task dependencies below; the shell script (invoked with --no-build) only
// serves the bundle and launches Electron, because Gradle can't manage the
// background-HTTP-server + foreground-Electron lifecycle and teardown that the
// script handles via a trap.
tasks.register<Exec>("runDemo") {
    group = "electron"
    description = "Launch the Electron desktop shell in demo mode (in-process fake server; no backend)."
    dependsOn(
        project(":web").tasks.named("jsBrowserDistribution"),
        copyMainBundle,
        npmInstall,
    )
    workingDir = rootProject.projectDir
    commandLine("bash", rootProject.file("scripts/run-electron-demo.sh").absolutePath, "--no-build")
}

// Stage the server fat jar inside the electron project so electron-builder
// picks it up via the `files` glob in package.json.
val copyServerJar by tasks.registering(Copy::class) {
    group = "electron"
    description = "Copy the server shadow jar into electron/resources/server.jar."
    val shadowJar = project(":server").tasks.named("shadowJar")
    dependsOn(shadowJar)
    from(shadowJar) {
        rename { "server.jar" }
    }
    into(resourcesDir)
}

// Sign the macOS native binaries embedded inside resources/server.jar so the
// notarized build passes Apple's nested-binary checks. electron-builder signs
// the .app but never reaches inside a .jar, so we sign these in place after the
// jar is staged and before packaging. macOS only — no-op elsewhere.
val signServerJarNatives by tasks.registering(Exec::class) {
    group = "electron"
    description = "Sign macOS native binaries inside resources/server.jar for notarization."
    dependsOn(copyServerJar)
    onlyIf { System.getProperty("os.name").startsWith("Mac") }
    workingDir = projectDir
    commandLine(
        "bash",
        rootProject.file("scripts/sign-server-jar-natives.sh").absolutePath,
        resourcesDir.file("server.jar").asFile.absolutePath,
    )
}

// Modules baked into the trimmed runtime. `java.se` pulls every Java SE
// module; the jdk.* extras cover what the embedded Ktor server needs at
// runtime but that the SE aggregator omits:
//   jdk.crypto.ec   - elliptic-curve TLS cipher suites (HTTPS on 8443)
//   jdk.unsupported - sun.misc.Unsafe, used by Netty and JNA
//   jdk.zipfs       - zip/jar filesystem provider
//   jdk.localedata  - non-English locale data
//   jdk.charsets    - extended charsets for arbitrary terminal output
//   jdk.net, jdk.management - extended socket options / JMX impl
val jreModules = listOf(
    "java.se",
    "jdk.crypto.ec",
    "jdk.unsupported",
    "jdk.zipfs",
    "jdk.localedata",
    "jdk.charsets",
    "jdk.net",
    "jdk.management",
)

// Resolve an Eclipse Temurin (Adoptium) JDK to jlink from. Temurin ships
// under GPLv2 + Classpath Exception, which permits bundling the runtime with
// this app regardless of the app's own license. JDK 17 is sufficient: every
// JVM module (including the vendored darkness-toolkit libs) pins its bytecode
// to Java 11. Resolves the locally-detected Temurin when present; otherwise
// Gradle provisions one.
val temurinLauncher = extensions.getByType(JavaToolchainService::class.java).launcherFor {
    languageVersion.set(JavaLanguageVersion.of(17))
    vendor.set(JvmVendorSpec.ADOPTIUM)
}

// Build a trimmed, self-contained Temurin JRE into electron/resources/jre so
// the packaged app spawns the server without depending on the end user having
// Java installed. electron-builder copies this into Contents/Resources/jre via
// the `extraResources` mapping in package.json, and signs its native binaries
// during app signing. macOS only — the dmg target is mac-only here.
val bundleJre by tasks.registering(JlinkTask::class) {
    group = "electron"
    description = "jlink a trimmed Temurin JRE into electron/resources/jre."
    onlyIf { System.getProperty("os.name").startsWith("Mac") }
    launcher.set(temurinLauncher)
    modules.set(jreModules)
    outputDir.set(jreDir)
}

// Sign the bundled JRE's Mach-O binaries for notarization. electron-builder
// generally signs loose nested binaries during app signing, but we sign here
// too so notarization doesn't depend on that (re-signing is idempotent).
// macOS only.
val signBundledJre by tasks.registering(Exec::class) {
    group = "electron"
    description = "Sign the bundled JRE's Mach-O binaries for notarization."
    dependsOn(bundleJre)
    onlyIf { System.getProperty("os.name").startsWith("Mac") }
    workingDir = projectDir
    commandLine(
        "bash",
        rootProject.file("scripts/sign-macho-tree.sh").absolutePath,
        jreDir.asFile.absolutePath,
        layout.projectDirectory.file("entitlements.mac.plist").asFile.absolutePath,
    )
}

tasks.register<Exec>("dist") {
    group = "electron"
    description = "Build a distributable Electron app via electron-builder."
    dependsOn(npmInstall, signServerJarNatives, signBundledJre, copyMainBundle)
    workingDir = projectDir
    // electron-builder reads the standard APPLE_ID / APPLE_APP_SPECIFIC_PASSWORD /
    // APPLE_TEAM_ID names for notarization. We keep the personal-account secrets in
    // *_PERSONAL env vars (so they don't collide with work-account credentials) and
    // map them onto the names electron-builder expects, only when they're present.
    System.getenv("APPLE_ID_PERSONAL")?.let { environment("APPLE_ID", it) }
    System.getenv("APPLE_APP_SPECIFIC_PASSWORD_PERSONAL")?.let { environment("APPLE_APP_SPECIFIC_PASSWORD", it) }
    System.getenv("APPLE_TEAM_ID_PERSONAL")?.let { environment("APPLE_TEAM_ID", it) }
    commandLine(npmExec, "run", "dist")
    inputs.file("package.json")
    inputs.file("main.js")
    inputs.file("preload.js")
    inputs.dir(resourcesDir)
    outputs.dir(distDir)
}

tasks.register<Delete>("clean") {
    group = "electron"
    description = "Remove node_modules, dist, and staged server resources."
    delete(nodeModulesDir, distDir, resourcesDir)
}

/**
 * Builds a trimmed Java runtime with `jlink` from a resolved Temurin JDK.
 *
 * Registered as the [bundleJre] task and run as part of `:electron:dist` so the
 * packaged macOS app carries its own JRE (see [resolveJavaBinary] on the
 * Kotlin/JS side, which prefers `Contents/Resources/jre/bin/java`). jlink also
 * copies each module's license files into `<output>/legal/`, satisfying the
 * Temurin (GPLv2 + Classpath Exception) attribution requirement automatically.
 *
 * Config-cache safe: file/exec work goes through injected [FileSystemOperations]
 * and [ExecOperations] rather than the `project` instance.
 *
 * @see bundleJre the task instance wired into the build
 */
abstract class JlinkTask : DefaultTask() {
    /** Launcher for the Temurin JDK whose `bin/jlink` builds the runtime. */
    @get:Nested
    abstract val launcher: Property<JavaLauncher>

    /** Module names passed to `jlink --add-modules`. */
    @get:Input
    abstract val modules: ListProperty<String>

    /** Destination directory for the linked runtime; wiped before each run. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @get:Inject
    abstract val fsOps: FileSystemOperations

    /**
     * Wipes any prior runtime (jlink refuses to write into an existing
     * directory) and links a fresh one, stripping debug info, man pages, and
     * header files to keep the bundle small.
     */
    @TaskAction
    fun link() {
        val jdkHome = launcher.get().metadata.installationPath
        val jlinkExe = jdkHome.file("bin/jlink").asFile
        val out = outputDir.get().asFile
        fsOps.delete { delete(out) }
        execOps.exec {
            commandLine(
                jlinkExe.absolutePath,
                "--add-modules", modules.get().joinToString(","),
                "--strip-debug",
                "--no-header-files",
                "--no-man-pages",
                "--compress=2",
                "--output", out.absolutePath,
            )
        }
        // jlink writes its `legal/` license files read-only (0444). When the
        // packaged app is downloaded as an auto-update it carries the macOS
        // quarantine xattr, and Squirrel.Mac's ShipIt strips that xattr from
        // EVERY file before swapping the bundle — which fails with EPERM on a
        // read-only file, so ShipIt aborts and silently relaunches the OLD
        // version. Make the runtime user-writable so the attribute can be
        // removed. macOS-only task (bundleJre is gated to Mac).
        execOps.exec {
            commandLine("chmod", "-R", "u+w", out.absolutePath)
        }
    }
}
