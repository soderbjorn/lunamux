#!/usr/bin/env bash
# Code-sign every Mach-O binary under a directory tree.
#
# Used to sign the bundled Temurin JRE (electron/resources/jre) before
# electron-builder packages the app. The JRE ships ~37 loose Mach-O binaries
# (bin/java, lib/server/libjvm.dylib, jspawnhelper, assorted *.dylib) that
# Apple's notary service rejects unless each is signed with a Developer ID
# cert, hardened runtime, and a secure timestamp. electron-builder generally
# signs loose nested binaries during app signing, but we sign here too so
# notarization does not depend on that behavior (re-signing is idempotent).
#
# Called by the :electron:signBundledJre Gradle task after jlink stages the
# runtime and before electron-builder runs. macOS only.
#
# The entitlements file is required: the JVM JIT-compiles code at runtime, so
# `bin/java` must be signed with com.apple.security.cs.allow-jit (and the
# unsigned-executable-memory / library-validation entitlements). Without them
# the hardened runtime kills the process with SIGTRAP (exit 133) on launch.
#
# Usage: sign-macho-tree.sh <directory> [entitlements.plist]
set -euo pipefail

ROOT="${1:?usage: sign-macho-tree.sh <directory> [entitlements.plist]}"
ENTITLEMENTS="${2:-}"
IDENTITY="${SIGN_IDENTITY:-Developer ID Application: Robert Söderbjörn (CCJP95ZXG4)}"

ent_args=()
if [[ -n "$ENTITLEMENTS" ]]; then
    ent_args=(--entitlements "$ENTITLEMENTS")
fi

count=0
while IFS= read -r f; do
    if file "$f" | grep -q 'Mach-O'; then
        codesign --force --options runtime --timestamp \
            "${ent_args[@]}" --sign "$IDENTITY" "$f"
        count=$((count + 1))
    fi
done < <(find "$ROOT" -type f)

if (( count == 0 )); then
    echo "No Mach-O binaries found to sign under $ROOT" >&2
    exit 1
fi
echo "Signed $count Mach-O binaries under $ROOT"
