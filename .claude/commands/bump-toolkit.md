---
description: Bump darkness-toolkit, sync termtastic's pin, and refresh libs-repo to only the latest version. No commits.
---

Arguments: `$ARGUMENTS` — an explicit version (e.g. `0.2.16`), or empty to bump the patch number.

Do this, then stop. **Never commit or push** (not in termtastic, not in darkness-toolkit).

1. Read the current version from `../../darkness-toolkit/main/build.gradle.kts` (the `allprojects { version = "..." }` line). The new version is `$ARGUMENTS` if given, otherwise the current version with its patch bumped by 1.
2. Set that new version in `../../darkness-toolkit/main/build.gradle.kts`.
3. Set `darkness = "<new version>"` in `gradle/libs.versions.toml`.
4. From `../../darkness-toolkit/main`, run `./gradlew publishAllToLibsRepo`.
5. In `libs-repo/se/soderbjorn/darkness/`, for every `toolkit-*` module, delete every version subdirectory except the new version's.
6. Report the old → new version and confirm libs-repo now holds only the latest.
