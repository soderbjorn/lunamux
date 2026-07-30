import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ktor)
    alias(libs.plugins.sqldelight)
    application
}

group = "se.soderbjorn.lunamux"
version = "1.0.0"

// Pin the server's bytecode to Java 11 so the shadow jar runs on the trimmed
// Java 17 JRE bundled into the Electron app (Contents/Resources/jre — see
// electron/build.gradle.kts). Without this the module inherits whatever JDK
// Gradle runs on (currently 21), emitting class file version 65.0 that a 17
// runtime rejects with UnsupportedClassVersionError, so the embedded server
// exits with code 1.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

// Keep javac's target in lockstep with Kotlin's jvmTarget above; otherwise
// Gradle fails the build with "Inconsistent JVM-target compatibility" when the
// build host runs a newer JDK (e.g. 21).
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

// The server embeds and serves the DEVELOPMENT web bundle, not the production
// one. The production pipeline (Kotlin/JS DCE + member-name mangling) has a
// layout-sensitive miscompilation bug on this codebase: mangled member calls
// can bind to the wrong symbol, throwing `<mangled> is not a function` — loudly
// at module init on some builds, or silently inside runCatching-wrapped
// feature paths on others (e.g. the 3D world grid-resize keys no-oping while
// everything around them works). Any source edit reshuffles the symbol layout,
// so a passing prod bundle passes by luck. The unminified dev bundle skips the
// optimizer entirely and is immune. See KOTLIN_JS_PROD_CRASH.html for the full
// investigation, and scripts/run-electron-to-prod-server.sh for the same
// mitigation on the side-instance script. Trade-off: ~25 MB vs ~4.5 MB, served
// over loopback — negligible for a desktop app. Revert to productionExecutable
// (and jsBrowserDistribution below) once the upstream optimizer bug is fixed.
val webDistDir = project(":web").layout.buildDirectory.dir("dist/js/developmentExecutable")
val webDistTask = ":web:jsBrowserDevelopmentExecutableDistribution"
val embeddedWebResourcesDir = layout.buildDirectory.dir("generated/web-resources")

application {
    mainClass.set("se.soderbjorn.lunamux.ApplicationKt")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf(
        "-Dio.ktor.development=$isDevelopment",
        "-Dlunamux.webDist=${webDistDir.get().asFile.absolutePath}"
    )
}

// Stage the web bundle under build/generated/web-resources/web/ so it ends up
// inside the shadow jar at /web on the classpath. The packaged server reads it
// via staticResources when no on-disk webDist is provided.
val copyWebDistToResources by tasks.registering(Copy::class) {
    dependsOn(webDistTask)
    from(webDistDir)
    into(embeddedWebResourcesDir.map { it.dir("web") })
}

sourceSets["main"].resources.srcDir(embeddedWebResourcesDir)

tasks.named("processResources") {
    dependsOn(copyWebDistToResources)
}

dependencies {
    implementation(projects.clientServer)
    // Vendored Termux terminal core, extracted to a pure-JVM module so the server
    // can run a canonical headless screen (SessionGrid) with the exact emulator the
    // Android renderer uses. No Android dependency — safe on the bundled JRE.
    implementation(projects.terminalCore)
    implementation(libs.lunula.core)
    implementation(libs.lunula.store)
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverWebsockets)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serializationKotlinxJson)
    implementation(libs.ktor.networkTlsCertificates)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.pty4j)
    implementation(libs.jediterm.core)
    implementation(libs.sqldelight.sqliteDriver)
    implementation(libs.sqldelight.coroutinesExtensions)
    implementation(libs.flexmark.all)
    implementation(libs.jsoup)
    // QR bitmap generation for the device-pairing dialog (pure Java, no
    // javase/AWT-helper artifact needed — we rasterize the BitMatrix ourselves).
    implementation(libs.zxing.core)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(compose.desktop.currentOs)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
}

sqldelight {
    databases {
        create("LunamuxDatabase") {
            packageName.set("se.soderbjorn.lunamux.db")
        }
    }
}

// Ensure the web bundle exists before the server starts.
// Dev server runs on a non-production port so a packaged Lunamux on
// SERVER_TLS_PORT (8443) can keep running alongside developer iterations.
// It also writes to its own SQLite database (`lunamux-dev.db` next to
// the production one) so a packaged build using stale code can't stomp on
// the dev server's window config — both processes used to share the same
// `termtastic.db` and the older one would silently strip fields it didn't
// recognise on its next save, e.g. losing freshly-introduced LeafContent
// variants like the markdown overview pane.
tasks.named<JavaExec>("run") {
    dependsOn(webDistTask)
    systemProperty("lunamux.port", "8444")
    val devDb = File(
        System.getProperty("user.home"),
        "Library/Application Support/Termtastic/lunamux-dev.db",
    )
    systemProperty("lunamux.dbPath", devDb.absolutePath)
}
