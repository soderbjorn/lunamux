plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// Bake the web/server bundle's build version into the JS bundle as
// `LUNAMUX_VERSION`, so the running web/Electron client reports the exact
// version it was built from (not a hand-maintained literal). The single source
// of truth is the Mac/desktop app's version in `electron/package.json` — the
// web bundle ships as part of that server/desktop release, so we read it there
// rather than duplicating the number. Because the bundle is served by the very
// server it connects to, this is effectively "the server's version", and a
// stale cached bundle honestly reports its own older version — which is what
// the server's agent-pane capability gate needs. `providers.fileContents`
// fingerprints the file as a build input, and we resolve to a plain String at
// configuration time to stay configuration-cache friendly.
val packageJsonText = providers.fileContents(
    layout.projectDirectory.file("../electron/package.json"),
).asText
val lunamuxVersion: String =
    Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(packageJsonText.getOrElse(""))
        ?.groupValues?.get(1)
        ?: "0.0.0"
val versionGenDir = layout.buildDirectory.dir("generated/version/kotlin")
val generateVersionConstant by tasks.registering {
    // Resolve everything the task action needs into plain serializable locals
    // (String + File) at configuration time, so the doLast closure captures no
    // script/project references — required for the configuration cache.
    val version = lunamuxVersion
    val outDir = versionGenDir.get().asFile
    inputs.property("version", version)
    outputs.dir(outDir)
    doLast {
        val out = File(outDir, "se/soderbjorn/lunamux/GeneratedVersion.kt")
        out.parentFile.mkdirs()
        out.writeText(
            "package se.soderbjorn.lunamux\n\n" +
                "/**\n" +
                " * Web/server build version baked in from `electron/package.json` at\n" +
                " * compile time. This is the version the web/Electron client reports to\n" +
                " * the server in `ClientIdentity` for capability gating.\n" +
                " */\n" +
                "internal const val LUNAMUX_VERSION: String = \"$version\"\n",
        )
    }
}

kotlin {
    js {
        browser {
            commonWebpackConfig {
                outputFileName = "web.js"
                cssSupport {
                    enabled.set(true)
                }
            }
            binaries.executable()
        }
    }

    sourceSets {
        jsMain {
            kotlin.srcDir(generateVersionConstant)
        }
        jsMain.dependencies {
            implementation(projects.clientServer)
            implementation(projects.client)
            implementation(libs.lunula.web)
            implementation(libs.kotlinx.html)
            implementation(libs.kotlinx.coroutines.core)
            implementation(npm("xterm", "5.3.0"))
            implementation(npm("xterm-addon-fit", "0.8.0"))
            // three.js for the 3D tab overview (Overview3D.kt). Pinned to a
            // release whose package.json still maps `require("three")` to
            // build/three.cjs — the Kotlin/JS UMD externals compile to a
            // CommonJS require, so the CJS entry point must exist.
            implementation(npm("three", "0.170.0"))
        }
    }
}

// `./gradlew build` runs the development and production webpack bundles both,
// and the Kotlin/JS plugin points all four of those tasks at the ONE shared
// `build/js/packages/Lunamux-web` directory. Gradle cannot infer an order from
// that — it only sees two tasks touching one location — so it aborts the build
// with an implicit-dependency validation error. Left unordered they also race
// for real: whichever webpack loses re-populates `kotlin/` underneath the other
// and it dies on a missing module (`NoSuchFileException: …/kotlin/ktor-ktor-io.js`).
//
// `mustRunAfter` rather than `dependsOn`, in both directions: neither bundle
// consumes the other's sync output, so a true dependency would overstate the
// relationship and drag the other executable into builds that asked for only
// one. This just says "not at the same time, and in this order".
tasks.named("jsBrowserProductionWebpack") {
    mustRunAfter("jsDevelopmentExecutableCompileSync")
}
tasks.named("jsBrowserDevelopmentWebpack") {
    mustRunAfter("jsProductionExecutableCompileSync")
}
