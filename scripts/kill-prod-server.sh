#!/usr/bin/env bash
# Kill any Lunamux server listening on the prod TLS port (8443).
# The dev server runs on 8444 (see server/build.gradle.kts) and is left alone.
set -euo pipefail

PORT="${LUNAMUX_PROD_PORT:-8443}"

pids="$(lsof -tiTCP:"$PORT" -sTCP:LISTEN -n -P 2>/dev/null || true)"
if [[ -z "$pids" ]]; then
    echo "No process is listening on port $PORT."
    exit 0
fi

echo "Found process(es) on port $PORT: $pids"
# shellcheck disable=SC2086
ps -o pid=,command= -p $pids || true

# Try a graceful shutdown first.
# shellcheck disable=SC2086
kill $pids 2>/dev/null || true

# Give it a moment to exit.
for _ in 1 2 3 4 5; do
    sleep 0.2
    remaining="$(lsof -tiTCP:"$PORT" -sTCP:LISTEN -n -P 2>/dev/null || true)"
    [[ -z "$remaining" ]] && break
done

if [[ -n "${remaining:-}" ]]; then
    echo "Process still alive — sending SIGKILL."
    # shellcheck disable=SC2086
    kill -9 $remaining 2>/dev/null || true
fi

if lsof -tiTCP:"$PORT" -sTCP:LISTEN -n -P >/dev/null 2>&1; then
    echo "Failed to free port $PORT." >&2
    exit 1
fi

echo "Port $PORT is now free."
