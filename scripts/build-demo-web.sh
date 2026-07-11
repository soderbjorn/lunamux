#!/usr/bin/env bash
# Build the Lunamux web client and deploy it as the embedded demo into the
# marketing website (lunamux-web).
#
# The web client is a Kotlin/JS app; `:web:jsBrowserDistribution` runs the
# production webpack build and assembles the bundle (web.js, the lazy 731.js
# chunk, styles.css, index.html and the bundled fonts/) into
#   web/build/dist/js/productionExecutable/
#
# The website embeds that bundle in an <iframe> pointing at
#   demo-app/index.html?demo
# (see content.js -> iframeSrc), so we mirror the production bundle into the
# website's demo-app/ directory. Source maps (*.map) are intentionally
# excluded — they are not served and would only bloat the static site.
#
# The website repo location defaults to the sibling checkout below and can be
# overridden by passing a path as the first argument.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

WEBSITE_DIR="${1:-../../lunamux-web}"
DEST="$WEBSITE_DIR/demo-app"
DIST="web/build/dist/js/productionExecutable"

if [[ ! -d "$WEBSITE_DIR" ]]; then
    echo "error: website repo not found at: $WEBSITE_DIR" >&2
    echo "       pass the path as the first argument, e.g.:" >&2
    echo "       $0 /path/to/lunamux-web" >&2
    exit 1
fi

echo "==> Building production web bundle (:web:jsBrowserDistribution)"
./gradlew :web:jsBrowserDistribution

if [[ ! -f "$DIST/web.js" ]]; then
    echo "error: expected bundle not found at $DIST/web.js" >&2
    exit 1
fi

echo "==> Deploying bundle into $DEST"
mkdir -p "$DEST"
# Mirror the bundle, dropping any stale files in the destination and skipping
# source maps. --delete keeps demo-app/ an exact copy of the fresh build.
rsync -a --delete --exclude='*.map' "$DIST/" "$DEST/"

echo "==> Done. Embedded demo updated at: $DEST"
echo "    Commit & push in $WEBSITE_DIR to publish."
