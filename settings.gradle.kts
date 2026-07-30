rootProject.name = "Lunamux"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // JediTerm (headless VT emulator used server-side for AI assistant
        // state detection) is published to JetBrains' IntelliJ dependencies
        // repository, not Maven Central.
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") {
            mavenContent {
                includeGroupAndSubgroups("org.jetbrains.jediterm")
            }
        }
        // Committed file-Maven-repo holding lunula artifacts. Lets
        // Lunamux build with no lunula checkout on disk. Refresh
        // from the toolkit checkout with `./gradlew publishAllToLibsRepo`.
        maven {
            name = "lunulaLibsLocal"
            url = uri("libs-repo")
        }
    }
}

// Auto-detect a sibling lunula checkout. When present, switch to a
// Gradle composite build so toolkit edits flow into Lunamux with no extra
// steps. Pass -Plunula.toolkit.useArtifacts=true to force resolution from
// the committed libs-repo even when sources are present (verifies published
// artifacts). Pass -Plunula.toolkit.path=… to point at an explicit checkout.
val toolkitOverride: String? = settings.providers.gradleProperty("lunula.toolkit.path").orNull
val useArtifacts: Boolean = settings.providers.gradleProperty("lunula.toolkit.useArtifacts").orNull == "true"
val toolkitCandidates: List<String> = listOfNotNull(
    toolkitOverride,
    "../../lunula/develop",
    "../../lunula/main",
)
val toolkitPath: String? = if (useArtifacts) null else toolkitCandidates
    .firstOrNull { File(rootDir, it).resolve("settings.gradle.kts").exists() }
if (toolkitPath != null) {
    includeBuild(toolkitPath) {
        dependencySubstitution {
            substitute(module("se.soderbjorn.lunula:lunula-core")).using(project(":lunula-core"))
            substitute(module("se.soderbjorn.lunula:lunula-store")).using(project(":lunula-store"))
            substitute(module("se.soderbjorn.lunula:lunula-web")).using(project(":lunula-web"))
            substitute(module("se.soderbjorn.lunula:lunula-compose")).using(project(":lunula-compose"))
        }
    }
}

include(":web")
include(":server")
include(":clientServer")
include(":client")
include(":electron")
include(":electron-main")
include(":terminal-core")
include(":terminal-emulator")
include(":terminal-view")
include(":androidApp")
include(":examples:snake-mcp")

