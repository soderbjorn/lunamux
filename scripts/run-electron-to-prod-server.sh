#!/usr/bin/env bash
# Launch a SECOND, independent Termtastic instance — this branch's web bundle in
# its own Electron shell — as a pure client against an already-running server
# (by default your REAL production server).
#
# The point: try out whatever this branch changes (UI, client behavior, anything)
# on top of your actual live sessions — the same tabs/panes/scrollback your
# production Termtastic is driving — WITHOUT touching production. It works
# because the UI and the API are decoupled at the wire: the renderer only
# derives its backend from the page origin, so we serve THIS branch's web
# bundle from a throwaway static origin and tell it (via the `?backend=host:port`
# URL param) to send all API/WebSocket traffic to the target server instead.
#
# How the pieces fit (see also ElectronMain.kt and web/.../main.kt):
#   - We build this branch's web bundle and serve it over plain HTTP on a free
#     loopback port (loopback is a secure context in Chromium).
#   - Electron loads `http://127.0.0.1:<port>/?backend=<BACKEND>`. The renderer
#     reads `?backend=` and points ServerUrl + every pty/agent socket at it.
#   - `TERMTASTIC_INSECURE_BACKEND=1` relaxes the BrowserWindow's same-origin
#     policy (webSecurity=false) so the cross-origin calls aren't blocked. The
#     production server itself does no Origin/CORS check — it only gates on the
#     connection being loopback — so this is purely a browser-side relaxation.
#   - Setting `TERMTASTIC_URL` makes ElectronMain a *pure client* (it never
#     spawns its own server), and `TERMTASTIC_INSTANCE=<name>` labels the app
#     "Termtastic <name>" with its own isolated userData dir + single-instance
#     lock. So it never fights your real Termtastic's lock and can't shut down
#     the real server on quit (its shutdown target is the static UI port, not
#     the backend port).
#
# First connect: the target server sees a new device token (each instance name
# has its own userData dir, hence its own token) and pops its one-time approval
# dialog. Approve it once; subsequent launches under the same name are silent.
#
# PRECONDITION: the target server must already be running on the backend port
# (default 127.0.0.1:8443). This script does NOT start it — that's the whole
# point (production stays untouched and alive).
#
# Usage:
#   scripts/run-electron-to-prod-server.sh [--name NAME] [--backend host:port] [--port N] [--no-build] [--help]
#
# Options:
#   --name NAME    Instance name (default "Test", or $INSTANCE_NAME). Shown in
#                  the dock/menu as "Termtastic <NAME>" and keys the isolated
#                  userData dir + single-instance lock, so differently-named
#                  instances (and production) all coexist.
#   --backend H:P  Server authority to drive (default 127.0.0.1:8443, or $BACKEND).
#   --port N       Preferred static-UI HTTP port (default 8090, or $UI_PORT). If
#                  busy, the next free port upward is used.
#   --no-build     Skip the Gradle build and reuse the already-built bundle.
#   --help         Show this help and exit.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

# Serve the DEVELOPMENT web bundle, not the production one. The production
# bundle goes through Kotlin/JS DCE + member-name mangling, which has a
# layout-sensitive collision bug on this project: the minified output throws
# `<mangled> is not a function` at module init (e.g. `t.get_low_..._k$` off a
# Long), and the exact failure flips with *any* source edit — a given prod
# bundle can pass purely by luck. The unminified dev bundle skips that pass and
# runs reliably (verified headless). For a local second instance this is the
# right trade: a larger bundle over loopback, but correct and edit-stable.
DIST="web/build/dist/js/developmentExecutable"
INSTANCE_NAME="${INSTANCE_NAME:-Test}"
BACKEND="${BACKEND:-127.0.0.1:8443}"
PREFERRED_PORT="${UI_PORT:-8090}"
DO_BUILD=1

# ── Parse arguments ─────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case "$1" in
        --name)     INSTANCE_NAME="${2:?--name needs a value}"; shift 2 ;;
        --backend)  BACKEND="${2:?--backend needs a value}"; shift 2 ;;
        --port)     PREFERRED_PORT="${2:?--port needs a value}"; shift 2 ;;
        --no-build) DO_BUILD=0; shift ;;
        --help|-h)
            sed -n '2,50p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) echo "error: unknown argument: $1" >&2; exit 2 ;;
    esac
done

# Per-instance log file (instance name sanitized to a safe filename slug).
LOG_SLUG=$(printf '%s' "$INSTANCE_NAME" | tr -c 'A-Za-z0-9._-' '-')
HTTP_LOG="${TMPDIR:-/tmp}/termtastic-${LOG_SLUG}-http.log"

# ── Build the web bundle (+ electron-main bundle + npm deps) ─────────────────
if [[ "$DO_BUILD" -eq 1 ]]; then
    echo "==> Building web bundle (dev) + Electron main (Gradle)"
    ./gradlew :web:jsBrowserDevelopmentExecutableDistribution :electron:copyMainBundle :electron:npmInstall
fi

if [[ ! -f "$DIST/web.js" ]]; then
    echo "error: web bundle not found at $DIST/web.js" >&2
    echo "       run without --no-build to build it first." >&2
    exit 1
fi

# ── Pick a free loopback port at or above the preferred one ──────────────────
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
echo "==> Serving 'Termtastic $INSTANCE_NAME' bundle from $DIST on http://127.0.0.1:$PORT"
python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$DIST" \
    >"$HTTP_LOG" 2>&1 &
HTTP_PID=$!

cleanup() {
    if kill -0 "$HTTP_PID" 2>/dev/null; then
        echo "==> Stopping static UI server (pid $HTTP_PID)"
        kill "$HTTP_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM

# Wait for the static server to accept connections before launching Electron.
for _ in $(seq 1 50); do
    if ! port_is_free "$PORT"; then break; fi
    if ! kill -0 "$HTTP_PID" 2>/dev/null; then
        echo "error: static UI server failed to start; log follows:" >&2
        cat "$HTTP_LOG" >&2 || true
        exit 1
    fi
    sleep 0.1
done

# ── Launch Electron pointed at the static UI, driving the target backend ─────
echo "==> Launching Termtastic $INSTANCE_NAME → backend $BACKEND"
echo "    (a server must be listening on $BACKEND; approve the device once when prompted)"
cd electron
TERMTASTIC_INSECURE_BACKEND=1 \
    TERMTASTIC_INSTANCE="$INSTANCE_NAME" \
    TERMTASTIC_URL="http://127.0.0.1:$PORT/?backend=$BACKEND" \
    npm start
