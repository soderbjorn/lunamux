#!/usr/bin/env bash
# Build a signed + notarized release of the Termtastic Electron desktop app.
#
# Runs `./gradlew :electron:dist`, which signs the app with the personal
# Developer ID cert, notarizes it via Apple, and staples the ticket. We then
# sign, notarize, and staple the DMG container itself (Apple's DMG workflow is
# sign -> notarize -> staple) so the download passes Gatekeeper with no
# friction. Final artifact: a .dmg under electron/dist/.
#
# Notarization credentials come from these env vars (the Gradle `dist` task
# maps the first three onto the names electron-builder expects):
#   export APPLE_ID_PERSONAL="robert@soderbjorn.se"
#   export APPLE_APP_SPECIFIC_PASSWORD_PERSONAL="xxxx-xxxx-xxxx-xxxx"
#   export APPLE_TEAM_ID_PERSONAL="CCJP95ZXG4"
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

# --no-daemon so a stale daemon doesn't reuse cached env vars.
./gradlew --no-daemon :electron:dist

IDENTITY="Developer ID Application: Robert Söderbjörn (CCJP95ZXG4)"
DMG="$(ls -t electron/dist/Termtastic-*.dmg | head -1)"
echo "==> Built: $DMG"

# electron-builder signs/notarizes/staples the .app but leaves the DMG
# container unsigned and un-notarized. Apple's DMG workflow is sign -> notarize
# -> staple; without the signature, spctl reports "no usable signature" on the
# container even after stapling.
echo "==> Signing DMG container..."
codesign --force --timestamp --sign "$IDENTITY" "$DMG"

echo "==> Notarizing DMG container..."
xcrun notarytool submit "$DMG" \
    --apple-id "$APPLE_ID_PERSONAL" \
    --password "$APPLE_APP_SPECIFIC_PASSWORD_PERSONAL" \
    --team-id "$APPLE_TEAM_ID_PERSONAL" --wait
xcrun stapler staple "$DMG"

echo "==> Verifying..."
xcrun stapler validate "$DMG"
spctl -a -vvv -t open --context context:primary-signature "$DMG"
echo "==> Done: $DMG"
