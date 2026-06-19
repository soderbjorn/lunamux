#!/usr/bin/env bash
# Launch the Termtastic Electron desktop shell in DEV mode — a real backend
# server, but fully isolated from a production install so the two can run
# side by side.
#
# How dev mode differs from prod (see also ElectronMain.kt and AppPaths.kt):
#   - Port: the dev server listens on the dev TLS port (8444) instead of the
#     production port (8443), so it never contends with a running prod server.
#   - Server state: the dev server is started with `-Dtermtastic.dbPath`
#     pointing at `termtastic-dev.db`, so it keeps a separate SQLite database
#     from prod's `termtastic.db` (matches scripts/delete-dev-config.sh). The
#     TLS keystore under `tls/` is shared — it's just the loopback cert.
#   - Shell state: setting `TERMTASTIC_URL` makes ElectronMain treat this as a
#     dev launch (IS_DEV_LAUNCH): the window/menu label becomes "Termtastic
#     Dev" and the renderer's userData dir + single-instance lock live under
#     "Termtastic Dev", distinct from prod's "Termtastic". So a dev launch
#     never steals prod's lock and never quits a running prod instance.
#
# This mirrors scripts/run-electron-demo.sh, but instead of an in-process fake
# server it starts a genuine `:server:run` on the dev port and points the
# Electron shell at it. Like the demo script it manages the background-server +
# foreground-Electron lifecycle and tears the server down on exit via a trap.
#
# If a server is already listening on the dev port, this script reuses it
# (and leaves it running on exit) rather than starting a second one.
#
# Usage:
#   scripts/run-electron-dev.sh [--port N] [--no-build] [--help]
#
# Options:
#   --port N     Dev TLS port for the server + shell (default 8444, or
#                $TERMTASTIC_DEV_PORT).
#   --no-build   Skip the Gradle build of the Electron main bundle / npm deps
#                and reuse what's already staged. The server is still built and
#                started by Gradle's `:server:run` regardless.
#   --help       Show this help and exit.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

PORT="${TERMTASTIC_DEV_PORT:-8444}"
DO_BUILD=1
SERVER_LOG="${TMPDIR:-/tmp}/termtastic-dev-server.log"

# Dev SQLite database, isolated from prod's termtastic.db. Kept under the same
# macOS Application Support dir so the shared TLS keystore (tls/) is reused.
# scripts/delete-dev-config.sh wipes exactly this file.
DEV_DB="$HOME/Library/Application Support/Termtastic/termtastic-dev.db"

# ── Parse arguments ─────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case "$1" in
        --port)     PORT="${2:?--port needs a value}"; shift 2 ;;
        --no-build) DO_BUILD=0; shift ;;
        --help|-h)
            sed -n '2,36p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) echo "error: unknown argument: $1" >&2; exit 2 ;;
    esac
done

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

# ── Start (or reuse) the dev server on $PORT ────────────────────────────────
SERVER_PID=""
STARTED_SERVER=0
if port_is_free "$PORT"; then
    echo "==> Starting dev server on port $PORT (db: $DEV_DB)"
    echo "    Logs: $SERVER_LOG"
    ./gradlew :server:run \
        -Dtermtastic.port="$PORT" \
        -Dtermtastic.dbPath="$DEV_DB" \
        >"$SERVER_LOG" 2>&1 &
    SERVER_PID=$!
    STARTED_SERVER=1
else
    echo "==> Reusing server already listening on port $PORT (leaving it running on exit)"
fi

cleanup() {
    # Only tear down a server we started; a pre-existing one is left alone.
    if [[ "$STARTED_SERVER" -eq 1 ]]; then
        echo "==> Stopping dev server"
        if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
            kill "$SERVER_PID" 2>/dev/null || true
        fi
        # The `application` plugin forks a JVM, so killing the Gradle wrapper
        # may leave the server bound. kill-dev-server.sh frees the port for
        # certain.
        TERMTASTIC_DEV_PORT="$PORT" scripts/kill-dev-server.sh >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT INT TERM

# Wait for the dev server to bind before launching Electron. The first run
# compiles the server via Gradle, so allow a generous window.
if [[ "$STARTED_SERVER" -eq 1 ]]; then
    echo "==> Waiting for dev server to accept connections on $PORT"
    for _ in $(seq 1 600); do
        if ! port_is_free "$PORT"; then break; fi
        if ! kill -0 "$SERVER_PID" 2>/dev/null; then
            echo "error: dev server exited before binding; log follows:" >&2
            tail -n 40 "$SERVER_LOG" >&2 || true
            exit 1
        fi
        sleep 0.5
    done
    if port_is_free "$PORT"; then
        echo "error: dev server did not start listening on $PORT in time." >&2
        tail -n 40 "$SERVER_LOG" >&2 || true
        exit 1
    fi
fi

# ── Build the Electron main bundle + npm deps ───────────────────────────────
# `:electron:copyMainBundle` stages the Kotlin/JS Electron main process into
# electron/resources/main; `:electron:npmInstall` ensures Electron is present.
if [[ "$DO_BUILD" -eq 1 ]]; then
    echo "==> Building Electron main bundle + npm deps (Gradle)"
    ./gradlew :electron:copyMainBundle :electron:npmInstall
fi

if [[ ! -f "electron/resources/main/electron-main.js" ]] \
    && ! ls electron/resources/main/*.js >/dev/null 2>&1; then
    echo "error: Electron main bundle not found in electron/resources/main." >&2
    echo "       run without --no-build to build it first." >&2
    exit 1
fi

# ── Launch Electron against the dev server (foreground; blocks until closed) ─
echo "==> Launching Electron in dev mode (Termtastic Dev → https://127.0.0.1:$PORT)"
cd electron
# TERMTASTIC_URL makes ElectronMain treat this as a dev launch: separate
# "Termtastic Dev" userData dir + single-instance lock, and the server port is
# parsed from this URL (so /admin/shutdown targets the dev server, not prod).
TERMTASTIC_URL="https://127.0.0.1:$PORT" npm start
