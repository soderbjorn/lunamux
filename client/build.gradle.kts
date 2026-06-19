import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Client"
            isStatic = true
            export(projects.clientServer)
            export(libs.darkness.core)
        }
        // Tiny ObjC shim that exposes NSURLProtectionSpace.serverTrust and
        // the NSURLSessionAuthChallengeDisposition constants with concrete
        // Int types. The Kotlin/Native 2.3.20 Foundation binding does not
        // surface `.serverTrust` on the Kotlin side, and the disposition
        // typealias commonizes inconsistently between iosMain metadata and
        // the concrete iosArm64 / iosSimulatorArm64 targets, so the shim
        // is the cleanest way to get strict TOFU pinning on iOS.
        iosTarget.compilations.getByName("main") {
            cinterops {
                val termtasticTls by creating {
                    definitionFile.set(
                        project.file("src/nativeInterop/cinterop/termtasticTls.def")
                    )
                    packageName("se.soderbjorn.termtastic.client.tlsinterop")
                }
            }
        }
    }

    jvm()

    js {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.clientServer)
            api(libs.darkness.core)
            api(libs.ktor.client.core)
            api(libs.ktor.client.websockets)
            api(libs.ktor.client.contentNegotiation)
            api(libs.ktor.client.serializationKotlinxJson)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            // Backs the Android `SecureStore` actual (EncryptedSharedPreferences)
            // that holds the device-auth token outside the plain local_state.json.
            implementation(libs.androidx.security.crypto)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        jsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "se.soderbjorn.termtastic.client"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}
