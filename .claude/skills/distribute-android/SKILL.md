---
name: distribute-android
description: Release an Android build to testers. Bumps versionCode, builds the signed release APK against local Lunula sources, and uploads it to Firebase App Distribution for the internal group. Use for "release this on android", "ship an android build", "distribute to testers", "put this on my phone". Takes optional release notes as arguments; writes them from recent commits when omitted.
---

Arguments: `$ARGUMENTS` — optional release notes, free text.

Build the Android app and put it in front of the tester group, in one invocation.
The developer is the only tester, so this is closer to "put the current state of
both repos on my phone" than to a Play release — which is why it neither demands
a clean working tree nor pins the toolkit to a published version.

Constants for this repo:

| | |
|---|---|
| Firebase app ID | `1:870206727735:android:fbf68913cb7f22b46181b2` |
| Firebase project | `lunamux-f0049` (project number `870206727735`) |
| Tester group | `internal` |
| APK | `androidApp/build/outputs/apk/release/androidApp-release.apk` |

**There is no Gradle upload task.** `appDistributionUploadRelease` and
`scripts/upload-android-appdistribution.sh` were removed: they targeted the
pre-rename **Termtastic** project (`901234040631`) through a service account
scoped to it, which cannot reach the Lunamux project. Uploading now goes through
the `firebase` CLI under the developer's own login. If a search turns up either
name in an old commit or a stale note, that is why it is gone — do not resurrect
it.

## 1. Preflight

Run from the repo root. Confirm the Firebase CLI is present and authenticated:

```
firebase login:list
```

**If it is not logged in, stop.** Do not run `firebase login` yourself — it needs a
browser handoff that cannot complete inside a tool call, and a half-finished login
leaves the terminal wedged. Tell the user to run `! firebase login` and stop there.

Then confirm the signing credentials resolve. `androidApp/build.gradle.kts` reads
`lunamuxKeystoreProps` from `local.properties` (or `-P` / `LUNAMUX_KEYSTORE_PROPS`)
and registers the `lunamux` signing config **only if that file exists**. Without it
the release variant builds *unsigned*, and the APK is named
`androidApp-release-unsigned.apk` — App Distribution rejects it, and the failure
reads as an upload problem rather than a config one. Check the path exists before
building, and stop with that explanation if it does not.

## 2. Report which Lunula the build will use

`settings.gradle.kts` auto-detects a sibling toolkit checkout and switches to a
composite build, so the APK normally contains Lunula **from the working tree** —
which is what we want here. But the detection matches only `../../lunula/develop`
and `../../lunula/main`, never the other worktrees under `~/repo-private/lunula/`.
Editing Lunula in a worktree and building here produces a green build with none of
those changes in it. The failure is silent, and the place it surfaces is the phone.

So before building, resolve the same candidates `settings.gradle.kts` does, walking
up from the repo root, and **say out loud which path won** — or that none did and
the build will fall back to Maven Central. One line, before the build starts, so a
wrong answer is caught here rather than after an upload.

If the user wants a specific worktree, pass it through:

```
-Plunula.toolkit.path=../../lunula/<worktree>
```

## 3. Bump versionCode

Read `versionCode` from `androidApp/build.gradle.kts` and write it back
incremented by one. Bump **before** building, or the APK carries the old number.

App Distribution accepts a repeated version code — unlike Play, it will not reject
the upload. The bump is purely so two builds in the App Tester list are
distinguishable instead of both reading the same number. Leave `versionName` alone;
it tracks releases, and this is not one.

Two consequences worth knowing, neither a reason to skip the bump:

- This is a shipped Play app, so `versionCode` is a Play-facing number. Play only
  requires each upload to be strictly greater than the last, so tester builds
  spending numbers costs nothing but a gap in the sequence.
- The in-app update check compares `BuildConfig.VERSION_CODE` against
  `latestVersionCode` in the manifest at `https://lunamux.dev/versions.json`. A
  tester build sitting above the published number simply shows no update banner,
  which is correct. **Never edit that manifest from this skill** — it announces
  releases to everyone, and this build went to one phone.

## 4. Build

```
./gradlew :androidApp:assembleRelease
```

No `-Plunula.toolkit.useArtifacts` flag — local sources are the point (§2).

The release variant is correct here even though this is a tester build: it is
signed with the same key as the Play release (§1), so it installs straight over
whatever is on the phone instead of colliding on signature.

`assembleRelease` runs `lintVitalRelease`, which the debug variant does not, so
this can fail on lint where a debug build would have passed. That is a real
failure — report it, do not route around it by switching variants.

**If the build fails, restore `versionCode` to its previous value before
reporting.** A failed build must not leave the number advanced.

## 5. Write the release notes

If `$ARGUMENTS` is non-empty, use it verbatim. The user said what they wanted;
do not embellish it.

Otherwise write two or three lines from the commits since the last version bump
(`git log` on `androidApp/build.gradle.kts` finds it). Summarize what changed in
plain language — "stopped PTY readers starving the IO dispatcher", not a branch
name. Being able to write that summary is the reason this is a skill rather than a
shell script; a `git log` dump would be worse than nothing.

Either way, append a provenance line:

```
— lunula: <resolved path or "<version> from Maven Central">, <clean|uncommitted changes>
```

Because §2's build usually contains an uncommitted toolkit, this line is the only
record of what was actually in the APK. Without it, a bug found on the phone a week
later is unattributable.

## 6. Upload

```
firebase appdistribution:distribute \
  androidApp/build/outputs/apk/release/androidApp-release.apk \
  --app 1:870206727735:android:fbf68913cb7f22b46181b2 \
  --groups internal \
  --release-notes "<notes from §5>"
```

**A 403 here is almost always a wrong app ID, not a permissions problem.** Check the
project number in the error URL against `870206727735` before touching IAM — if it
reads `901234040631`, something is still pointing at the retired Termtastic project.

If the upload fails, restore `versionCode` as in §4 and report. A number that
advanced for a build nobody received is a number that lies.

## 7. Commit the bump

Only after the upload succeeds:

```
git commit -m "Bump Android versionCode to <n>" -- androidApp/build.gradle.kts
```

**Path-scoped, deliberately.** The working tree is expected to be dirty — shipping
uncommitted work to yourself is what this skill is for — and a bare `git commit -a`
would sweep whatever is in progress into a commit that claims to be a version bump.

**Never push.** A build that went to one tester is not a thing to publish; the user
pushes when the user means to.

## 8. Report

Four lines, no more:

- the new versionCode
- the Lunula path that went into the build
- the release notes as sent
- that the group `internal` has been notified

## Guard rails

- Never run `firebase login`, and never pass `--token`. Interactive auth belongs to
  the user (§1).
- Never add testers or create groups. Distribution is not membership management; a
  missing tester is something to report, not to fix.
- Never touch the Lunula repo. This skill reads which toolkit is in play and
  reports it; committing, pushing or publishing there is a separate decision.
- Never publish to Play, and never edit `versions.json` (§3). This skill's reach
  ends at the tester group.
- Never push, and never tag (§7).
- If any step fails, leave `versionCode` where it started. The only state this skill
  is allowed to advance is state that made it all the way to a tester.
