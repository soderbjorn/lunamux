plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.termux.terminal"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // The near-JVM-pure Termux core now lives in :terminal-core. `api` re-exposes
    // it so :terminal-view's own `api(project(":terminal-emulator"))` transitively
    // hands com.termux.terminal.* to the Android app with zero import changes.
    // Only TerminalSession/JNI (Android/PTY-bound) remain in this module.
    api(project(":terminal-core"))
}
