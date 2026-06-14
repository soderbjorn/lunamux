#!/usr/bin/env bash
# Delete the production Termtastic SQLite database and its WAL/SHM sidecars.
# Prod DB: ~/Library/Application Support/Termtastic/termtastic.db
#
# The prod server must not be running while this script wipes the file,
# otherwise SQLite's WAL would race with the delete and could corrupt a
# subsequent reopen. A timestamped backup of the main DB is kept so a
# misclick is recoverable.
set -euo pipefail

APP_DIR="$HOME/Library/Application Support/Termtastic"
PROD_DB="$APP_DIR/termtastic.db"

PROD_PORT="${TERMTASTIC_PROD_PORT:-8443}"
if lsof -tiTCP:"$PROD_PORT" -sTCP:LISTEN -n -P >/dev/null 2>&1; then
    echo "Prod server is running on port $PROD_PORT. Stop it first:" >&2
    echo "  scripts/kill-prod-server.sh" >&2
    exit 1
fi

if [[ ! -f "$PROD_DB" ]]; then
    echo "No prod database at $PROD_DB — nothing to delete."
    exit 0
fi

backup="$PROD_DB.bak.$(date +%Y%m%d-%H%M%S)"
cp "$PROD_DB" "$backup"
echo "Backed up existing prod DB to: $backup"

rm -f "$PROD_DB" "$PROD_DB-wal" "$PROD_DB-shm"
echo "Deleted prod DB and WAL/SHM sidecars at $PROD_DB"
