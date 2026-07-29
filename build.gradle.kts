plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.firebaseAppDistribution) apply false
}

// ---------------------------------------------------------------------------
// Toolkit refresh. The inverse of the old arrangement where lunula published
// into every consumer: lunula now stays agnostic of who consumes it, and each
// consumer pulls fresh artifacts into its own committed `libs-repo`.
//
//   ./gradlew refreshLunula
//
// Locates the toolkit the way settings.gradle.kts does — an explicit
// -Plunula.toolkit.path wins, else the first existing sibling checkout — then
// invokes lunula's own build, targeting this repo's libs-repo. Needs a lunula
// checkout on disk (always the case when there's something to refresh from).
// ---------------------------------------------------------------------------
tasks.register<Exec>("refreshLunula") {
    group = "build setup"
    description = "Publishes the sibling lunula toolkit into this repo's libs-repo."
    // Capture plain, serializable values at configuration time so the task
    // stays compatible with the configuration cache — no script/project
    // references may leak into the execution-time action below.
    val projectRoot = rootDir
    val libsRepo = projectRoot.resolve("libs-repo")
    val toolkitOverride = providers.gradleProperty("lunula.toolkit.path").orNull
    val candidates = listOfNotNull(toolkitOverride, "../../lunula/develop", "../../lunula/main")
    // Placeholder command; the real working dir + command are resolved on
    // execution, so switching lunula worktrees never replays a stale command.
    commandLine("true")
    doFirst {
        val toolkit = candidates.map { projectRoot.resolve(it) }
            .firstOrNull { it.resolve("settings.gradle.kts").exists() }
            ?: throw GradleException(
                "No lunula checkout found (looked for $candidates relative to $projectRoot). " +
                    "Pass -Plunula.toolkit.path=… to point at one.",
            )
        workingDir = toolkit
        commandLine(
            "./gradlew", "publishAllToLibsRepo",
            "-Plunula.publishTarget=$libsRepo",
        )
    }
}