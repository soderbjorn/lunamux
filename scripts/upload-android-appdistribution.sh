#!/usr/bin/env bash
# Build a signed Android RELEASE and upload it to Firebase App Distribution.
#
# Always builds the release variant: runs `:androidApp:assembleRelease` then
# `:androidApp:appDistributionUploadRelease`. The Firebase App ID and the
# service-account credentials path are read by androidApp/build.gradle.kts from
# local.properties (keys `lunamuxFirebaseAppId` / `lunamuxFirebaseCreds`),
# so they are NOT passed here.
#
# No arguments are required: by default the build is distributed to the
# "android-testers" tester group with auto-generated release notes.
#
# Optional args:
#   --groups <aliases>   Override the tester-group ALIASES to distribute to
#                        (alias, not display name). Default: android-testers.
#   --testers <emails>   Comma-separated individual tester emails (in addition
#                        to the groups).
#   --notes <text>       Release notes. Defaults to "<short-sha> (<branch>)".
#   -h, --help           Show this help.
#
# Examples:
#   scripts/upload-android-appdistribution.sh
#   scripts/upload-android-appdistribution.sh --notes "Fixes overview rendering"
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

# --- Usage -----------------------------------------------------------------
usage() {
    # Print the leading comment block (minus the shebang) as help text.
    sed -n '2,/^set -euo/{ /^set -euo/d; s/^# \{0,1\}//; p; }' "${BASH_SOURCE[0]}"
}

# --- Parse args ------------------------------------------------------------
# Defaults make the script runnable with no arguments. NOTE: do not name the
# groups variable GROUPS — that is a special built-in bash array (the current
# user's group IDs); assigning to it is ignored.
TESTER_GROUPS="android-testers"
TESTERS=""
NOTES=""
VARIANT="release"   # always release; not overridable

while [[ $# -gt 0 ]]; do
    case "$1" in
        --groups)  TESTER_GROUPS="${2:-}"; shift 2 ;;
        --testers) TESTERS="${2:-}"; shift 2 ;;
        --notes)   NOTES="${2:-}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Error: unknown argument: $1" >&2; echo >&2; usage >&2; exit 2 ;;
    esac
done

if [[ -z "$TESTER_GROUPS" ]]; then
    echo "Error: --groups was given an empty value." >&2
    exit 2
fi

# --- Pre-flight: Firebase config must be present in local.properties --------
# androidApp/build.gradle.kts reads these; surface a clear error here rather
# than letting the upload task fail deep in the build.
LOCAL_PROPS="local.properties"
prop() { grep -E "^$1=" "$LOCAL_PROPS" 2>/dev/null | head -1 | cut -d= -f2-; }

CONFIG_MISSING=()
[[ -f "$LOCAL_PROPS" ]] || CONFIG_MISSING+=("$LOCAL_PROPS file is absent")
APP_ID="$(prop lunamuxFirebaseAppId)"
CREDS="$(prop lunamuxFirebaseCreds)"
[[ -z "$APP_ID" ]] && CONFIG_MISSING+=("lunamuxFirebaseAppId=<1:NNN:android:XXX> in $LOCAL_PROPS")
[[ -z "$CREDS"  ]] && CONFIG_MISSING+=("lunamuxFirebaseCreds=<path/to/service-account.json> in $LOCAL_PROPS")
[[ -n "$CREDS" && ! -f "$CREDS" ]] && CONFIG_MISSING+=("service-account file not found at: $CREDS")

if [[ ${#CONFIG_MISSING[@]} -gt 0 ]]; then
    echo "Error: Firebase config incomplete. Fix the following:" >&2
    for c in "${CONFIG_MISSING[@]}"; do echo "  - $c" >&2; done
    exit 1
fi

# --- Default release notes -------------------------------------------------
if [[ -z "$NOTES" ]]; then
    SHA="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
    BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
    NOTES="$SHA ($BRANCH)"
fi

# --- Build + upload --------------------------------------------------------
# Capitalize the variant for the Gradle task names (assembleRelease /
# appDistributionUploadRelease).
VARIANT_CAP="$(tr '[:lower:]' '[:upper:]' <<< "${VARIANT:0:1}")${VARIANT:1}"

echo "==> Variant:     $VARIANT"
echo "==> Groups:      $TESTER_GROUPS"
[[ -n "$TESTERS" ]] && echo "==> Testers:     $TESTERS"
echo "==> App ID:      $APP_ID"
echo "==> Notes:       $NOTES"

UPLOAD_ARGS=(--groups "$TESTER_GROUPS" --releaseNotes "$NOTES")
[[ -n "$TESTERS" ]] && UPLOAD_ARGS+=(--testers "$TESTERS")

./gradlew --no-daemon \
    ":androidApp:assemble${VARIANT_CAP}" \
    ":androidApp:appDistributionUpload${VARIANT_CAP}" \
    "${UPLOAD_ARGS[@]}"

echo "==> Done. Uploaded $VARIANT build to Firebase App Distribution."
