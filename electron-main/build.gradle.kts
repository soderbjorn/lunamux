// Kotlin/JS Node module for the Electron main process.
//
// Compiles to a CommonJS bundle that electron/main.js requires() at
// startup. Web-only consumers never depend on this module — only the
// :electron Gradle module pulls it in via copyMainBundle.

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    js(IR) {
        nodejs()
        binaries.executable()
        useCommonJs()
        compilations.named("main") {
            packageJson {
                customField("private", true)
            }
        }
    }

    sourceSets {
        jsMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            // Pulled in solely for the shared external-link constants
            // (LUNAMUX_*_URL in client's ExternalLinks.kt) surfaced in the Help
            // menu, so the desktop menu bar can never drift from the mobile and
            // renderer clients' canonical URLs. Kotlin/JS IR DCE strips the rest
            // of the client module from the main-process bundle.
            implementation(projects.client)
        }
    }
}
