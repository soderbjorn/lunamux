import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.firebaseAppDistribution)
}

// Resolve the signing-credentials properties file, which lives OUTSIDE the
// repo so secrets never touch version control. The *path* to it comes from one
// of the following, in precedence order:
//   1. -PtermtasticKeystoreProps=/path/to/termtastic.properties (command line)
//      or the same key in ~/.gradle/gradle.properties (machine-global)
//   2. `termtasticKeystoreProps` in the repo-root `local.properties`
//      (project-local, gitignored — alongside sdk.dir)
//   3. TERMTASTIC_KEYSTORE_PROPS environment variable (handy for CI)
// If none resolve to an existing file, debug uses the default debug key and
// release stays unsigned, so a fresh checkout still builds.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val keystorePropsFile: File? =
    ((findProperty("termtasticKeystoreProps") as String?)
        ?: localProps.getProperty("termtasticKeystoreProps")
        ?: System.getenv("TERMTASTIC_KEYSTORE_PROPS"))
        ?.let { File(it) }
        ?.takeIf { it.exists() }
val keystoreProps = Properties().apply {
    keystorePropsFile?.inputStream()?.use { load(it) }
}

// Firebase App Distribution upload configuration. Like the signing
// credentials, the Firebase App ID and the service-account key file live
// OUTSIDE the repo and are read from the same external properties file as the
// keystore (see above), or overridden via -P / env. Resolution order matches
// the keystore's:
//   1. -PtermtasticFirebaseAppId / -PtermtasticFirebaseCreds (command line)
//   2. `termtasticFirebaseAppId` / `termtasticFirebaseCreds` in local.properties
//   3. `firebaseAppId` / `firebaseServiceCredentials` in the keystore props file
//   4. TERMTASTIC_FIREBASE_APP_ID / TERMTASTIC_FIREBASE_CREDS env vars
// When unset, the plugin is still applied (so the upload task exists) but
// normal builds are unaffected; the upload task only fails if actually invoked
// without these values.
val firebaseAppId: String? =
    (findProperty("termtasticFirebaseAppId") as String?)
        ?: localProps.getProperty("termtasticFirebaseAppId")
        ?: keystoreProps.getProperty("firebaseAppId")
        ?: System.getenv("TERMTASTIC_FIREBASE_APP_ID")
val firebaseCredsFile: File? =
    ((findProperty("termtasticFirebaseCreds") as String?)
        ?: localProps.getProperty("termtasticFirebaseCreds")
        ?: keystoreProps.getProperty("firebaseServiceCredentials")
        ?: System.getenv("TERMTASTIC_FIREBASE_CREDS"))
        ?.let { raw ->
            // Like `storeFile`, a relative path resolves against the props
            // file's own directory so the JSON can sit next to the keystore.
            File(raw).takeIf { it.isAbsolute }
                ?: keystorePropsFile?.parentFile?.resolve(raw)
        }

android {
    namespace = "se.soderbjorn.termtastic.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "se.soderbjorn.termtastic.android"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 10
        versionName = "1.5.0"
    }

    buildFeatures {
        compose = true
        // Generates `BuildConfig` so the update checker can read the running
        // build's VERSION_CODE / VERSION_NAME (see UpdateCheckController).
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }


    signingConfigs {
        // A single custom signing config, used for both debug and release so
        // every build is signed with the same key. Only registered when the
        // external properties file was found; otherwise debug uses the default
        // debug key and release stays unsigned.
        if (keystorePropsFile != null) {
            create("termtastic") {
                // `storeFile` in the properties file may be absolute or relative;
                // a relative path is resolved against the properties file's own
                // directory, so the keystore can sit next to it outside the repo.
                val rawStore = keystoreProps.getProperty("storeFile")
                storeFile = File(rawStore).takeIf { it.isAbsolute }
                    ?: keystorePropsFile.parentFile.resolve(rawStore)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    lint {
        // `MainActivity` extends androidx.activity.ComponentActivity, which is a
        // subclass of android.app.Activity, so it is genuinely instantiatable.
        // The Instantiatable check misfires during lintVitalRelease because lint's
        // release analysis can't always resolve the AndroidX inheritance chain,
        // producing a fatal false positive. Disable just that one check.
        disable += "Instantiatable"
    }

    buildTypes {
        val termtasticSigning = signingConfigs.findByName("termtastic")
        debug {
            termtasticSigning?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            termtasticSigning?.let { signingConfig = it }
        }
    }
}

// Wire the externally-resolved Firebase values into the App Distribution
// plugin. Only set properties that resolved, so an unconfigured checkout still
// configures cleanly. Testers/groups and release notes are passed at invoke
// time (e.g. `--groups testers`) to keep this block minimal.
firebaseAppDistribution {
    firebaseAppId?.let { appId = it }
    firebaseCredsFile?.let { serviceCredentialsFile = it.absolutePath }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(projects.clientServer)
    implementation(projects.client)
    implementation(project(":terminal-view"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.lifecycle.runtimeCompose)
    implementation(libs.androidx.lifecycle.viewmodelCompose)

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
