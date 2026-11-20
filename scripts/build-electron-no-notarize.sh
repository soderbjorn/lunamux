#!/usr/bin/env bash
# Build an un-notarized release of the Lunamux Electron desktop app, ad-hoc signed
# rather than signed with a Developer ID certificate.
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
#   3. Ad-hoc sign the packed app through the electron/adhoc-sign.js afterPack
#      hook. Left unsigned, the app keeps stock Electron's identity, and macOS
#      applies Electron's local network permission instead of Lunamux's: LAN
#      connections from its terminals then fail with "No route to host" whatever
#      the Lunamux toggle in System Settings says. The ad-hoc signature carries
#      the bundle identifier and no certificate.
#
# The resulting app is not Developer ID signed nor notarized, so Gatekeeper will block it
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
    -c.mac.notarize=false \
    -c.afterPack=adhoc-sign.js

DMG="$(ls -t dist/Lunamux-*.dmg | head -1)"
echo "==> Done (ad-hoc signed, NOT notarized): electron/$DMG"
