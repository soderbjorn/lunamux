#!/usr/bin/env bash
# Copy the production Lunamux SQLite database over the development one.
# Prod DB:  ~/Library/Application Support/Termtastic/termtastic.db
# Dev DB:   ~/Library/Application Support/Termtastic/lunamux-dev.db
#
# The dev server must not be running while this script overwrites the file,
# otherwise SQLite's WAL could leave the dev DB in an inconsistent state.
set -euo pipefail

APP_DIR="$HOME/Library/Application Support/Termtastic"
PROD_DB="$APP_DIR/termtastic.db"
DEV_DB="$APP_DIR/lunamux-dev.db"

if [[ ! -f "$PROD_DB" ]]; then
    echo "Production database not found: $PROD_DB" >&2
    exit 1
fi

# Refuse to run while something is listening on the dev port — a live server
# holds the DB open and would race with the copy.
DEV_PORT="${LUNAMUX_DEV_PORT:-8444}"
if lsof -tiTCP:"$DEV_PORT" -sTCP:LISTEN -n -P >/dev/null 2>&1; then
    echo "Dev server is running on port $DEV_PORT. Stop it first:" >&2
    echo "  scripts/kill-dev-server.sh" >&2
    exit 1
fi

# Back up whatever dev DB is there so an accidental overwrite is recoverable.
if [[ -f "$DEV_DB" ]]; then
    backup="$DEV_DB.bak.$(date +%Y%m%d-%H%M%S)"
    cp "$DEV_DB" "$backup"
    echo "Backed up existing dev DB to: $backup"
fi

# Use SQLite's online backup API so a prod server running against the source
# file can stay up during the copy. Falls back to a plain cp if sqlite3 is
# unavailable (macOS ships with it, so this is just belt-and-braces).
if command -v sqlite3 >/dev/null 2>&1; then
    sqlite3 "$PROD_DB" ".backup '$DEV_DB'"
else
    cp "$PROD_DB" "$DEV_DB"
fi

# Clean up any stale WAL/SHM files left over from a previous dev session so
# SQLite reopens cleanly against the freshly copied main file.
rm -f "$DEV_DB-wal" "$DEV_DB-shm"

echo "Copied prod DB to dev: $DEV_DB"
