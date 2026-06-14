plugins {
    alias(libs.plugins.kotlinMultiplatform)
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
        jsMain.dependencies {
            implementation(projects.clientServer)
            implementation(projects.client)
            implementation(libs.darkness.web)
            implementation(libs.kotlinx.html)
            implementation(libs.kotlinx.coroutines.core)
            implementation(npm("xterm", "5.3.0"))
            implementation(npm("xterm-addon-fit", "0.8.0"))
        }
    }
}
