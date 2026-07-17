#!/usr/bin/env bash
# Cut a Lunamux desktop release and publish it — with all the electron-updater
# metadata — to a GitHub DRAFT release in one command.
#
# Why this exists: electron-updater needs `latest-mac.yml` and the per-artifact
# `.blockmap` files uploaded ALONGSIDE the installer. Doing GitHub releases by
# hand, a human eventually forgets one of those and silently breaks auto-update
# for everyone. This script builds locally (no CI — mac CI is slow/expensive),
# then uploads a fixed GLOB of every required file, so the metadata can never be
# left out. It always publishes as a DRAFT: a human reviews it in the GitHub UI
# and clicks Publish, which is when clients start seeing the update.
#
# Usage:
#   scripts/release-electron.sh <version> [options]
#
#   <version>            Semver to release, e.g. 1.9.1. Stamped into
#                        electron/package.json (version + mac.bundleShortVersion)
#                        and the integer mac.bundleVersion is bumped by one.
#
# Options:
#   --repo <owner/repo>  Target GitHub repo. Default: soderbjorn/lunamux.
#                        In a test build (see below) this ALSO sets the publish
#                        provider baked into app-update.yml, so the built app
#                        checks that repo for updates. Test builds REFUSE to
#                        publish to the production repo — point this at a fork
#                        (or use --no-publish).
#   --identity <name>    Sign with this macOS code-signing identity instead of
#                        the production Developer ID, and skip notarization.
#                        Use a free self-signed "Code Signing" cert from Keychain
#                        to test the auto-update loop without an Apple Developer
#                        ID — Squirrel.Mac only requires the running app and the
#                        update to share a signing identity (a MATCH), not
#                        notarization. Implies a test build.
#   --no-notarize        Build unsigned (identity=null) and skip notarization.
#                        Implies a test build. (Unsigned macOS apps cannot
#                        auto-update; prefer --identity for update testing.)
#   --no-publish         Build locally only — skip creating/uploading the GitHub
#                        release. For fast local build → install test loops (e.g.
#                        iterating on the app or the update banner) without
#                        creating a release each time. Artifacts stay in
#                        electron/dist/ for you to install by hand.
#
# Production build (no --identity / --no-notarize): signs + notarizes with the
# maintainer's Developer ID (from electron/package.json) and staples the DMG,
# exactly like build-release-electron.sh. Requires the notarization creds in
# APPLE_ID_PERSONAL / APPLE_APP_SPECIFIC_PASSWORD_PERSONAL / APPLE_TEAM_ID_PERSONAL.
#
# Examples:
#   # Production draft release to soderbjorn/lunamux:
#   scripts/release-electron.sh 1.9.1
#
#   # Self-signed test release to your fork, to exercise the update loop:
#   scripts/release-electron.sh 1.9.1 --repo ottojaa/lunamux \
#       --identity "Lunamux Test Signing"
#
# Publishing (upload) uses the `gh` CLI, so `gh auth login` must be done and the
# token must have write access to the target repo.
set -euo pipefail
shopt -s nullglob

# ── Args ─────────────────────────────────────────────────────────────
# The real release target. Test builds may never publish here (see the guard
# after arg parsing): a self-signed draft — or worse, a --clobber onto an
# existing production tag — must not be one forgotten --repo away.
PROD_REPO="soderbjorn/lunamux"

VERSION=""
REPO="$PROD_REPO"
IDENTITY=""
NO_NOTARIZE=0
NO_PUBLISH=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --repo) REPO="$2"; shift 2 ;;
        --identity) IDENTITY="$2"; shift 2 ;;
        --no-notarize) NO_NOTARIZE=1; shift ;;
        --no-publish) NO_PUBLISH=1; shift ;;
        # Print the header comment block (everything up to the first non-#
        # line), stripped of the leading "# " — robust to the header growing,
        # unlike a fixed line range.
        -h|--help) awk 'NR>1 { if (!/^#/) exit; sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
        -*) echo "Unknown option: $1" >&2; exit 2 ;;
        *) if [[ -z "$VERSION" ]]; then VERSION="$1"; shift; else echo "Unexpected arg: $1" >&2; exit 2; fi ;;
    esac
done

if [[ -z "$VERSION" ]]; then
    echo "error: <version> is required (e.g. 1.9.1). See --help." >&2
    exit 2
fi
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "error: version '$VERSION' is not semver X.Y.Z." >&2
    exit 2
fi

OWNER="${REPO%%/*}"
REPO_NAME="${REPO##*/}"
TAG="v$VERSION"

# A test build = anything not signed + notarized with the production Developer ID.
TEST_BUILD=0
if [[ -n "$IDENTITY" || "$NO_NOTARIZE" == "1" ]]; then TEST_BUILD=1; fi

# Never let a test build near the production repo's releases. Without this, a
# forgotten --repo on a fork-test run would upload self-signed artifacts to
# $PROD_REPO — and, if the tag already existed, --clobber them over real ones.
if [[ $TEST_BUILD == 1 && $NO_PUBLISH == 0 && "$REPO" == "$PROD_REPO" ]]; then
    echo "error: refusing to publish a TEST build (--identity / --no-notarize) to $PROD_REPO." >&2
    echo "       Pass --repo <owner/repo> pointing at your fork, or --no-publish for a" >&2
    echo "       local-only build." >&2
    exit 2
fi

cd "$(dirname "${BASH_SOURCE[0]}")/.."
ROOT="$(pwd)"

# Restore electron/package.json unless we reach a successful PRODUCTION publish, so
# a failed build, a --no-publish local build, or a fork test publish never leaves
# the version fields churned (which would double-bump on the next run). KEEP_BUMP
# flips to 1 only for a real release, where the bump is meant to be committed.
KEEP_BUMP=0
PKG_BACKUP="$(mktemp)"
cp "$ROOT/electron/package.json" "$PKG_BACKUP"
restore_pkg() {
    if [[ $KEEP_BUMP -eq 0 ]]; then cp "$PKG_BACKUP" "$ROOT/electron/package.json"; fi
    rm -f "$PKG_BACKUP"
}
trap restore_pkg EXIT

echo "==> Releasing $TAG to $REPO ($([[ $TEST_BUILD == 1 ]] && echo 'TEST build' || echo 'production build'))"

# ── 1. Stamp the version (single source of truth) ────────────────────
# electron-updater compares app.getVersion() (== mac.bundleShortVersion on macOS)
# against latest-mac.yml's version (== package.json version), so these MUST match.
VERSION="$VERSION" node -e '
  const fs = require("fs");
  const p = "electron/package.json";
  const j = JSON.parse(fs.readFileSync(p, "utf8"));
  const v = process.env.VERSION;
  j.version = v;
  j.build.mac.bundleShortVersion = v;
  j.build.mac.bundleVersion = String((parseInt(j.build.mac.bundleVersion, 10) || 0) + 1);
  fs.writeFileSync(p, JSON.stringify(j, null, 2) + "\n");
  console.error(`==> Stamped version=${v}, bundleShortVersion=${v}, bundleVersion=${j.build.mac.bundleVersion}`);
'

# ── 2. Build ─────────────────────────────────────────────────────────
# The mac target is dmg+zip and the publish provider is set (electron/package.json),
# so electron-builder emits latest-mac.yml + .blockmap files into electron/dist/.
rm -rf electron/dist

if [[ $TEST_BUILD == 0 ]]; then
    # Production: the proven signed + notarized path. `:electron:dist` runs the
    # native-signing staging tasks then electron-builder (which signs, notarizes,
    # and staples the .app). app-update.yml is baked from the package.json publish
    # config (soderbjorn/lunamux).
    if [[ "$REPO" != "soderbjorn/lunamux" ]]; then
        echo "WARNING: production build bakes app-update.yml from package.json" >&2
        echo "         (soderbjorn/lunamux), but you're uploading to $REPO — the" >&2
        echo "         installed app would check soderbjorn/lunamux, not $REPO." >&2
        echo "         For a fork test build, pass --identity so the repo override" >&2
        echo "         is applied to app-update.yml too." >&2
    fi
    ./gradlew --no-daemon :electron:dist
else
    # Test: stage without native signing (mirrors build-electron-no-notarize.sh),
    # then run electron-builder directly with the overrides. -c.publish.owner/repo
    # bakes the fork into app-update.yml so the built app checks it.
    ./gradlew --no-daemon \
        :electron:npmInstall \
        :electron:copyServerJar \
        :electron:bundleJre \
        :electron:copyMainBundle

    EB_ARGS=(
        -c.publish.owner="$OWNER"
        -c.publish.repo="$REPO_NAME"
        -c.mac.notarize=false
        # Self-signed / unsigned apps can't satisfy hardened-runtime nested-signing
        # expectations; turn it off so the test build launches and Squirrel.Mac can
        # apply the update (it checks the signature MATCH, not hardened runtime).
        -c.mac.hardenedRuntime=false
    )
    if [[ -n "$IDENTITY" ]]; then
        EB_ARGS+=(-c.mac.identity="$IDENTITY")
    else
        EB_ARGS+=(-c.mac.identity=null)
    fi

    cd electron
    if [[ -z "$IDENTITY" ]]; then
        CSC_IDENTITY_AUTO_DISCOVERY=false ./node_modules/.bin/electron-builder "${EB_ARGS[@]}" --publish never
    else
        ./node_modules/.bin/electron-builder "${EB_ARGS[@]}" --publish never
    fi
    cd "$ROOT"
fi

DIST="$ROOT/electron/dist"

# electron-updater is useless without this file; fail loudly if the build didn't
# produce it (usually means the mac `zip` target or the publish config is missing).
if [[ ! -f "$DIST/latest-mac.yml" ]]; then
    echo "error: $DIST/latest-mac.yml was not generated — electron-updater needs it." >&2
    echo "       Check that electron/package.json has mac.target including 'zip' and" >&2
    echo "       a 'publish' block." >&2
    exit 1
fi

# ── 3. Staple the DMG container (production only) ─────────────────────
# electron-builder signs/notarizes/staples the .app (inside both dmg and zip) but
# leaves the DMG *container* unsigned, so a downloaded DMG hits Gatekeeper
# friction. Apple's DMG workflow is sign -> notarize -> staple. (The auto-update
# path uses the zip, whose .app is already stapled, so this is only for the
# first-install DMG download.)
if [[ $TEST_BUILD == 0 ]]; then
    : "${APPLE_ID_PERSONAL:?set APPLE_ID_PERSONAL for notarization}"
    : "${APPLE_APP_SPECIFIC_PASSWORD_PERSONAL:?set APPLE_APP_SPECIFIC_PASSWORD_PERSONAL}"
    : "${APPLE_TEAM_ID_PERSONAL:?set APPLE_TEAM_ID_PERSONAL}"
    # dist/ is wiped at the top of every run, so exactly one DMG exists. Guard
    # anyway: under nullglob an empty match would otherwise hand `ls -t` zero
    # args and it would happily pick the newest file of the CURRENT DIRECTORY.
    DMGS=( "$DIST"/Lunamux-*.dmg )
    if [[ ${#DMGS[@]} -ne 1 ]]; then
        echo "error: expected exactly one Lunamux-*.dmg in $DIST, found ${#DMGS[@]}." >&2
        exit 1
    fi
    DMG="${DMGS[0]}"
    IDENTITY_FULL="Developer ID Application: Robert Söderbjörn (CCJP95ZXG4)"
    echo "==> Signing + notarizing + stapling DMG container: $(basename "$DMG")"
    codesign --force --timestamp --sign "$IDENTITY_FULL" "$DMG"
    xcrun notarytool submit "$DMG" \
        --apple-id "$APPLE_ID_PERSONAL" \
        --password "$APPLE_APP_SPECIFIC_PASSWORD_PERSONAL" \
        --team-id "$APPLE_TEAM_ID_PERSONAL" --wait
    xcrun stapler staple "$DMG"
    xcrun stapler validate "$DMG"

    # Signing + stapling just rewrote the DMG, but electron-builder computed
    # latest-mac.yml's dmg sha512/size from the pre-staple bytes. macOS
    # auto-update downloads the zip (untouched above), so the stale hash is
    # latent — but a manifest that misdescribes an asset it lists is a landmine
    # (it breaks the moment anything validates the dmg entry), so refresh it.
    # The dmg .blockmap is left as-is deliberately: it only serves dmg
    # differential downloads, which the macOS updater never performs.
    DMG_PATH="$DMG" node -e '
      const fs = require("fs"), path = require("path"), crypto = require("crypto");
      const dmg = process.env.DMG_PATH;
      const yml = path.join(path.dirname(dmg), "latest-mac.yml");
      const name = path.basename(dmg);
      const sha512 = crypto.createHash("sha512").update(fs.readFileSync(dmg)).digest("base64");
      const size = fs.statSync(dmg).size;
      const lines = fs.readFileSync(yml, "utf8").split("\n");
      let inDmgEntry = false, patched = 0;
      for (let i = 0; i < lines.length; i++) {
        const l = lines[i];
        const url = l.match(/^\s*-\s+url:\s*(.+?)\s*$/);
        if (url) { inDmgEntry = url[1] === name; continue; }
        // Top-level `path:` names the primary artifact (the zip on macOS); its
        // following top-level sha512 is patched only if it names the dmg.
        const top = l.match(/^path:\s*(.+?)\s*$/);
        if (top) { inDmgEntry = top[1] === name; continue; }
        if (!inDmgEntry) continue;
        if (/^\s*sha512:/.test(l)) { lines[i] = l.replace(/sha512:.*/, "sha512: " + sha512); patched++; }
        else if (/^\s*size:/.test(l)) { lines[i] = l.replace(/size:.*/, "size: " + size); patched++; }
      }
      if (patched === 0) {
        console.error("error: latest-mac.yml has no entry for " + name + " — refusing to publish a stale manifest.");
        process.exit(1);
      }
      fs.writeFileSync(yml, lines.join("\n"));
      console.error("==> Refreshed " + patched + " latest-mac.yml field(s) for " + name + " after stapling.");
    '
fi

# Build-only: stop before publishing (fast local build → install loops).
if [[ $NO_PUBLISH == 1 ]]; then
    echo
    echo "==> Built locally (--no-publish); no GitHub release created."
    echo "    Artifacts in $DIST:"
    for a in "$DIST"/*.dmg "$DIST"/*.zip; do [[ -e "$a" ]] && echo "      $(basename "$a")"; done
    echo "    Install the .dmg to test the app / update banner."
    exit 0
fi

# ── 4. Publish the draft release with ALL update metadata ────────────
# The fixed glob is the whole point: dmg + zip + latest-mac.yml + every .blockmap,
# so nothing electron-updater needs can be forgotten. nullglob would silently
# drop an entire missing class from the upload — defeating that guarantee — so
# assert each artifact kind actually exists before building the list.
DMG_ASSETS=( "$DIST"/*.dmg )
ZIP_ASSETS=( "$DIST"/*.zip )
BLOCKMAP_ASSETS=( "$DIST"/*.blockmap )
[[ ${#DMG_ASSETS[@]} -gt 0 ]] || { echo "error: no .dmg in $DIST." >&2; exit 1; }
[[ ${#ZIP_ASSETS[@]} -gt 0 ]] || { echo "error: no .zip in $DIST — macOS electron-updater updates from the zip." >&2; exit 1; }
[[ ${#BLOCKMAP_ASSETS[@]} -gt 0 ]] || { echo "error: no .blockmap in $DIST — differential updates need them." >&2; exit 1; }
ASSETS=( "${DMG_ASSETS[@]}" "${ZIP_ASSETS[@]}" "${BLOCKMAP_ASSETS[@]}" "$DIST/latest-mac.yml" )
echo "==> Uploading ${#ASSETS[@]} assets to $REPO draft release $TAG:"
for a in "${ASSETS[@]}"; do echo "      $(basename "$a")"; done

NOTES="Automated draft for $TAG. Review, then Publish to release the update to clients."
if IS_DRAFT="$(gh release view "$TAG" --repo "$REPO" --json isDraft --jq .isDraft 2>/dev/null)"; then
    # Overwrite assets only while the release is still an unreviewed draft. A
    # PUBLISHED release's assets are live for clients (possibly mid-download);
    # clobbering them in place could hand out a zip that no longer matches the
    # published latest-mac.yml. Cut a new version instead.
    if [[ "$IS_DRAFT" != "true" ]]; then
        echo "error: release $TAG on $REPO is already PUBLISHED — refusing to overwrite its assets." >&2
        echo "       Cut a new version instead." >&2
        exit 1
    fi
    echo "==> Draft release $TAG already exists — clobbering assets."
    gh release upload "$TAG" --repo "$REPO" --clobber "${ASSETS[@]}"
else
    gh release create "$TAG" --repo "$REPO" --draft --title "$TAG" --notes "$NOTES" "${ASSETS[@]}"
fi

# A real release is published: keep the version bump (the trap won't revert it) so
# it can be committed. Test/fork publishes leave package.json untouched.
if [[ $TEST_BUILD == 0 ]]; then KEEP_BUMP=1; fi

echo
echo "==> Done. Draft release: https://github.com/$REPO/releases"
echo "    Review the draft and click Publish; clients update only once it is published."
if [[ $TEST_BUILD == 0 ]]; then
    echo "    Remember to commit the version bump in electron/package.json ($TAG)."
fi
