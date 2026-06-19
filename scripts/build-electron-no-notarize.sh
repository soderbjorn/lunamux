#!/usr/bin/env bash
# Build an UNSIGNED, un-notarized release of the Termtastic Electron desktop app.
#
# Same artifact as build-release-electron.sh minus all the Apple code-signing
# and notarization round trips: useful for fast local/test builds where you just
# want a .dmg without a Developer ID cert or Apple's notary service.
#
# We can't reuse `./gradlew :electron:dist` directly because that task always
# runs `npm run dist` (plain `electron-builder`), and the `mac.identity` /
# `notarize` blocks in electron/package.json make electron-builder sign and
# notarize whenever a cert / the APPLE_* credentials are present. Instead we:
#   1. Run only the Gradle staging tasks (install npm deps, stage the server
#      jar, jlink the bundled JRE, copy the Kotlin/JS main bundle). We skip
#      signServerJarNatives / signBundledJre since nothing gets signed.
#   2. Invoke electron-builder directly with code signing and notarization
#      disabled: `CSC_IDENTITY_AUTO_DISCOVERY=false` plus `-c.mac.identity=null`
#      skip signing, and `-c.mac.notarize=false` overrides the package.json
#      `notarize` block.
#
# The resulting app is neither signed nor notarized, so Gatekeeper will block it
# on other machines (and even locally it needs a right-click -> Open or a
# quarantine-attr removal). This build is intended for local verification only.
#
# Final artifact: a .dmg under electron/dist/.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

# --no-daemon so a stale daemon doesn't reuse cached env vars. These are the
# non-signing staging tasks; `:electron:dist` reaches copyServerJar/bundleJre
# transitively via its signing tasks, which we skip here, so we name them
# directly.
./gradlew --no-daemon \
    :electron:npmInstall \
    :electron:copyServerJar \
    :electron:bundleJre \
    :electron:copyMainBundle

echo "==> Packaging with electron-builder (signing + notarization disabled)..."
cd electron
CSC_IDENTITY_AUTO_DISCOVERY=false \
    ./node_modules/.bin/electron-builder \
    -c.mac.identity=null \
    -c.mac.notarize=false

DMG="$(ls -t dist/Termtastic-*.dmg | head -1)"
echo "==> Done (unsigned, NOT notarized): electron/$DMG"
