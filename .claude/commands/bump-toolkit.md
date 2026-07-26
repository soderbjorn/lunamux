---
description: Bump lunula, sync the consumers' pins, and refresh the lunamux and lunicle libs-repos to only the latest version. No commits.
---

Arguments: `$ARGUMENTS` — an explicit version (e.g. `0.2.16`), or empty to bump the patch number.

Do this, then stop. **Never commit or push** (not in lunamux, not in lunicle, not in lunula).

1. Read the current version from `../../lunula/main/build.gradle.kts` (the `allprojects { version = "..." }` line). The new version is `$ARGUMENTS` if given, otherwise the current version with its patch bumped by 1.
2. Set that new version in `../../lunula/main/build.gradle.kts`.
3. Set `lunula = "<new version>"` in both consumers' version catalogs: `gradle/libs.versions.toml` (lunamux's own) **and** `../../lunicle/main/gradle/libs.versions.toml` (lunicle's).
4. Publish. The direction is **consumer-pulls**: each consumer owns a `refreshLunula` task that invokes lunula's build with its own libs-repo as the publish target. So run it from each consumer, not from lunula:
   - `./gradlew refreshLunula` from lunamux (this repo)
   - `./gradlew refreshLunula` from `../../lunicle/main`

   Do **not** run `./gradlew publishAllToLibsRepo` in `lunula/main` on its own — with no `-Plunula.publishTarget` it publishes into the throwaway `lunula/main/build/local-libs-repo`, so it reports success while leaving both consumers on the old version (see the comment at the top of `lunula/main/build.gradle.kts`).
5. Clean both consumer libs-repos so each holds only the new version. In `libs-repo/se/soderbjorn/lunula/` (lunamux's own) **and** in `../../lunicle/main/libs-repo/se/soderbjorn/lunula/` (lunicle's), for every `lunula-*` module, delete every version subdirectory except the new version's:
   ```
   find <libs-repo>/se/soderbjorn/lunula -mindepth 2 -maxdepth 2 -type d ! -name "<new version>" -exec rm -rf {} +
   ```
6. Report the old → new version and confirm both libs-repos (lunamux and lunicle) now hold only the latest.
