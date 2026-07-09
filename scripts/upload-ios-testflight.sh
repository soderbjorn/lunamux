#!/usr/bin/env bash
# Archive the iOS app, export it, and upload it to App Store Connect /
# internal TestFlight.
#
# Runs three steps with Apple's own tooling (no fastlane, no Ruby):
#   1. xcodebuild archive        -> build/ios/Lunamux.xcarchive
#   2. xcodebuild -exportArchive -> uploads directly to App Store Connect
#                                   (ExportOptions destination = upload)
# Authentication uses an App Store Connect API key (.p8), so there is no
# Apple-ID password or 2FA prompt. Internal TestFlight testers receive the
# build automatically once App Store Connect finishes processing it -- there
# is no Beta App Review for internal testing.
#
# The API key path, Key ID and Issuer ID are read from local.properties
# (keys lunamuxAscKeyPath / lunamuxAscKeyId / lunamuxAscIssuerId),
# mirroring how upload-android-appdistribution.sh reads its Firebase config.
#
# Required local.properties entries:
#   lunamuxAscKeyId=XXXXXXXXXX                 # 10-char Key ID
#   lunamuxAscIssuerId=xxxxxxxx-xxxx-xxxx-...  # Issuer ID (UUID)
#   lunamuxAscKeyPath=/path/to/key.p8          # optional; defaults below
# All three appear in App Store Connect > Users and Access > Integrations.
#
# Optional args:
#   --bump   Increment CURRENT_PROJECT_VERSION in Config.xcconfig before
#            archiving (App Store Connect rejects a build number it has
#            already seen for the current MARKETING_VERSION).
#   -h, --help   Show this help.
#
# Examples:
#   scripts/upload-ios-testflight.sh
#   scripts/upload-ios-testflight.sh --bump
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

# --- Usage -----------------------------------------------------------------
usage() {
    # Print the leading comment block (minus the shebang) as help text.
    sed -n '2,/^set -euo/{ /^set -euo/d; s/^# \{0,1\}//; p; }' "${BASH_SOURCE[0]}"
}

# --- Parse args ------------------------------------------------------------
BUMP=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --bump)    BUMP=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Error: unknown argument: $1" >&2; echo >&2; usage >&2; exit 2 ;;
    esac
done

# --- Project layout --------------------------------------------------------
PROJECT="iosApp/iosApp.xcodeproj"
SCHEME="iosApp"
CONFIG="iosApp/Configuration/Config.xcconfig"
EXPORT_OPTS="iosApp/Configuration/ExportOptions.plist"
ARCHIVE="build/ios/Lunamux.xcarchive"
DEFAULT_KEY_PATH="/Users/soderbjorn/.android/signing/lunamux-app-manager.p8"

# --- Pre-flight: App Store Connect API config ------------------------------
# Surface a clear error here rather than failing deep inside xcodebuild.
LOCAL_PROPS="local.properties"
# `|| true` so a missing key yields an empty string instead of a non-zero
# exit that `set -e` would treat as fatal (before the friendly error below).
prop() { grep -E "^$1=" "$LOCAL_PROPS" 2>/dev/null | head -1 | cut -d= -f2- || true; }

KEY_ID="$(prop lunamuxAscKeyId)"
ISSUER_ID="$(prop lunamuxAscIssuerId)"
KEY_PATH="$(prop lunamuxAscKeyPath)"
KEY_PATH="${KEY_PATH:-$DEFAULT_KEY_PATH}"

CONFIG_MISSING=()
[[ -f "$LOCAL_PROPS" ]] || CONFIG_MISSING+=("$LOCAL_PROPS file is absent")
[[ -z "$KEY_ID"    ]] && CONFIG_MISSING+=("lunamuxAscKeyId=<10-char Key ID> in $LOCAL_PROPS")
[[ -z "$ISSUER_ID" ]] && CONFIG_MISSING+=("lunamuxAscIssuerId=<issuer UUID> in $LOCAL_PROPS")
[[ -f "$KEY_PATH"  ]] || CONFIG_MISSING+=(".p8 API key not found at: $KEY_PATH")
[[ -f "$EXPORT_OPTS" ]] || CONFIG_MISSING+=("export options plist missing: $EXPORT_OPTS")

if [[ ${#CONFIG_MISSING[@]} -gt 0 ]]; then
    echo "Error: App Store Connect config incomplete. Fix the following:" >&2
    for c in "${CONFIG_MISSING[@]}"; do echo "  - $c" >&2; done
    exit 1
fi

# --- Optional: bump build number -------------------------------------------
# App Store Connect rejects a re-used (MARKETING_VERSION, CURRENT_PROJECT_VERSION)
# pair, so --bump increments the build number in Config.xcconfig.
if [[ "$BUMP" -eq 1 ]]; then
    CUR="$(grep -E '^CURRENT_PROJECT_VERSION=' "$CONFIG" | head -1 | cut -d= -f2 | tr -d '[:space:]')"
    NEXT=$(( CUR + 1 ))
    # macOS/BSD sed in-place edit.
    sed -i '' -E "s/^CURRENT_PROJECT_VERSION=.*/CURRENT_PROJECT_VERSION=$NEXT/" "$CONFIG"
    echo "==> Bumped CURRENT_PROJECT_VERSION: $CUR -> $NEXT"
fi

MARKETING="$(grep -E '^MARKETING_VERSION=' "$CONFIG" | head -1 | cut -d= -f2 | tr -d '[:space:]')"
BUILD_NUM="$(grep -E '^CURRENT_PROJECT_VERSION=' "$CONFIG" | head -1 | cut -d= -f2 | tr -d '[:space:]')"

echo "==> Scheme:      $SCHEME"
echo "==> Version:     $MARKETING ($BUILD_NUM)"
echo "==> Key ID:      $KEY_ID"
echo "==> API key:     $KEY_PATH"

# --- 1. Archive ------------------------------------------------------------
# -allowProvisioningUpdates lets Xcode create/refresh the distribution
# provisioning profile via your signed-in account (automatic signing).
echo "==> Archiving..."
xcodebuild archive \
    -project "$PROJECT" \
    -scheme "$SCHEME" \
    -configuration Release \
    -destination "generic/platform=iOS" \
    -archivePath "$ARCHIVE" \
    -allowProvisioningUpdates

# --- 2. Export + upload ----------------------------------------------------
# destination=upload in ExportOptions.plist makes this push straight to App
# Store Connect. -authenticationKeyPath accepts an arbitrarily-named .p8
# (unlike altool, which requires AuthKey_<KEYID>.p8 in a magic directory).
echo "==> Exporting and uploading to App Store Connect..."
xcodebuild -exportArchive \
    -archivePath "$ARCHIVE" \
    -exportOptionsPlist "$EXPORT_OPTS" \
    -allowProvisioningUpdates \
    -authenticationKeyPath "$KEY_PATH" \
    -authenticationKeyID "$KEY_ID" \
    -authenticationKeyIssuerID "$ISSUER_ID"

echo "==> Done. Build $MARKETING ($BUILD_NUM) uploaded."
echo "    It will appear for internal TestFlight testers once App Store"
echo "    Connect finishes processing (usually a few minutes)."
