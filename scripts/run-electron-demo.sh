#!/usr/bin/env bash
# Launch the Lunamux Electron desktop shell in DEMO mode — a fully
# self-contained run that needs no backend server and no Java.
#
# How it works (see also client/.../demo/DemoServer.kt and
# web/.../DemoSupport.kt):
#   - The web client treats demo mode as a property of the URL it loads: a
#     `?demo` query param (also `/demo` path or `#demo` hash) makes it boot
#     against the in-process `DemoServer` and its canned fixtures instead of
#     opening real network sockets.
#   - The Electron main process loads `LUNAMUX_URL` when set, and in that
#     case skips its embedded-server bootstrap entirely (ElectronMain.kt).
#   - Demo mode only stubs the *backend sockets*, so the HTML/JS bundle still
#     has to be served from somewhere. This script builds the production web
#     bundle and serves it over plain HTTP on a free loopback port (loopback
#     is a secure context in Chromium, so secure-context APIs still work),
#     then points Electron at `http://127.0.0.1:<port>/?demo`.
#
# The demo HTTP port is deliberately NOT 8443 (prod TLS) or 8444 (dev TLS),
# and is auto-incremented past anything already listening, so this can run
# alongside a live prod/dev server without colliding.
#
# Usage:
#   scripts/run-electron-demo.sh [--port N] [--no-build] [--help]
#
# Options:
#   --port N     Preferred demo HTTP port (default 8088, or $DEMO_PORT). If
#                busy, the next free port upward is used.
#   --no-build   Skip the Gradle build and reuse the already-built bundle.
#                Used by the `:electron:runDemo` Gradle task, which performs
#                the build via task dependencies instead.
#   --help       Show this help and exit.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

DIST="web/build/dist/js/productionExecutable"
PREFERRED_PORT="${DEMO_PORT:-8088}"
DO_BUILD=1
HTTP_LOG="${TMPDIR:-/tmp}/lunamux-demo-http.log"

# ── Parse arguments ─────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case "$1" in
        --port)     PREFERRED_PORT="${2:?--port needs a value}"; shift 2 ;;
        --no-build) DO_BUILD=0; shift ;;
        --help|-h)
            sed -n '2,32p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) echo "error: unknown argument: $1" >&2; exit 2 ;;
    esac
done

# ── Build the web bundle (+ electron-main bundle + npm deps) ─────────────────
# `:web:jsBrowserDistribution` runs the production webpack build into $DIST;
# `copyMainBundle` stages the Kotlin/JS Electron main process into
# electron/resources/main; `npmInstall` ensures Electron itself is present.
if [[ "$DO_BUILD" -eq 1 ]]; then
    echo "==> Building web bundle + Electron main (Gradle)"
    ./gradlew :web:jsBrowserDistribution :electron:copyMainBundle :electron:npmInstall
fi

if [[ ! -f "$DIST/web.js" ]]; then
    echo "error: web bundle not found at $DIST/web.js" >&2
    echo "       run without --no-build to build it first." >&2
    exit 1
fi

# ── Pick a free loopback port at or above the preferred one ──────────────────
# connect_ex == 0 means something is already listening (port taken); anything
# else means it is free.
port_is_free() {
    python3 - "$1" <<'PY'
import socket, sys
s = socket.socket()
s.settimeout(0.2)
sys.exit(0 if s.connect_ex(("127.0.0.1", int(sys.argv[1]))) != 0 else 1)
PY
}

PORT="$PREFERRED_PORT"
while ! port_is_free "$PORT"; do
    echo "==> Port $PORT busy, trying $((PORT + 1))"
    PORT=$((PORT + 1))
done

# ── Serve the bundle and tear the server down on exit ────────────────────────
echo "==> Serving demo bundle from $DIST on http://127.0.0.1:$PORT"
python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$DIST" \
    >"$HTTP_LOG" 2>&1 &
HTTP_PID=$!

cleanup() {
    if kill -0 "$HTTP_PID" 2>/dev/null; then
        echo "==> Stopping demo HTTP server (pid $HTTP_PID)"
        kill "$HTTP_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM

# Wait for the static server to accept connections before launching Electron.
for _ in $(seq 1 50); do
    if ! port_is_free "$PORT"; then break; fi
    if ! kill -0 "$HTTP_PID" 2>/dev/null; then
        echo "error: demo HTTP server failed to start; log follows:" >&2
        cat "$HTTP_LOG" >&2 || true
        exit 1
    fi
    sleep 0.1
done

# ── Launch Electron against the demo URL (foreground; blocks until closed) ───
echo "==> Launching Electron in demo mode (?demo)"
cd electron
# LUNAMUX_DEMO=1 makes ElectronMain label the app "Lunamux Demo" and run
# from an isolated, wiped-on-startup userData dir under the OS temp dir. That
# keeps its single-instance lock separate from a live prod (or dev) Lunamux
# — so this won't quit on the lock collision — and persists nothing between
# runs, with no exit-time cleanup to depend on.
LUNAMUX_DEMO=1 LUNAMUX_URL="http://127.0.0.1:$PORT/?demo" npm start
