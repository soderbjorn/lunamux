#!/usr/bin/env bash
# Code-sign the macOS native binaries bundled inside server.jar.
#
# The Ktor server jar ships native helpers (pty4j, JNA, jansi, skiko,
# sqlite-jdbc) as Mach-O binaries. Apple's notary service unzips the jar and
# rejects the build unless each macOS binary is signed with a Developer ID
# cert, hardened runtime, and a secure timestamp. electron-builder signs the
# .app but never reaches inside a .jar, so we sign these in place first.
#
# Called by the :electron:dist Gradle task after the jar is staged into
# electron/resources/ and before electron-builder packages the app.
#
# Usage: sign-server-jar-natives.sh <path-to-server.jar>
set -euo pipefail

JAR="${1:?usage: sign-server-jar-natives.sh <path-to-server.jar>}"
IDENTITY="${SIGN_IDENTITY:-Developer ID Application: Robert Söderbjörn (CCJP95ZXG4)}"

JAR="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Extract only macOS-native-looking entries (dylib/jnilib, or paths under
# darwin / Mac / macos dirs). Non-Mach-O hits are filtered out below.
while IFS= read -r entry; do
    unzip -qo "$JAR" "$entry" -d "$WORK"
done < <(unzip -Z1 "$JAR" | grep -iE '\.(dylib|jnilib)$|/darwin/|/Mac/|macos')

cd "$WORK"
signed=()
while IFS= read -r f; do
    if file "$f" | grep -q 'Mach-O'; then
        codesign --force --options runtime --timestamp \
            --sign "$IDENTITY" "$f"
        signed+=("${f#./}")
    fi
done < <(find . -type f)

if (( ${#signed[@]} == 0 )); then
    echo "No macOS native binaries found to sign in $JAR" >&2
    exit 1
fi

# Write the signed binaries back into the jar (jar is a zip archive).
zip -q "$JAR" "${signed[@]}"
echo "Signed ${#signed[@]} native binaries inside $(basename "$JAR")"
