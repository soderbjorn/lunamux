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
        }
    }
}
