#!/usr/bin/env bash
# Delete the development Termtastic SQLite database and its WAL/SHM sidecars.
# Dev DB: ~/Library/Application Support/Termtastic/termtastic-dev.db
#
# The dev server must not be running while this script wipes the file,
# otherwise SQLite's WAL would race with the delete and could corrupt a
# subsequent reopen. A timestamped backup of the main DB is kept so a
# misclick is recoverable.
set -euo pipefail

APP_DIR="$HOME/Library/Application Support/Termtastic"
DEV_DB="$APP_DIR/termtastic-dev.db"

DEV_PORT="${TERMTASTIC_DEV_PORT:-8444}"
if lsof -tiTCP:"$DEV_PORT" -sTCP:LISTEN -n -P >/dev/null 2>&1; then
    echo "Dev server is running on port $DEV_PORT. Stop it first:" >&2
    echo "  scripts/kill-dev-server.sh" >&2
    exit 1
fi

if [[ ! -f "$DEV_DB" ]]; then
    echo "No dev database at $DEV_DB — nothing to delete."
    exit 0
fi

backup="$DEV_DB.bak.$(date +%Y%m%d-%H%M%S)"
cp "$DEV_DB" "$backup"
echo "Backed up existing dev DB to: $backup"

rm -f "$DEV_DB" "$DEV_DB-wal" "$DEV_DB-shm"
echo "Deleted dev DB and WAL/SHM sidecars at $DEV_DB"
