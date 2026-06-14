#!/usr/bin/env bash
# Wipe every termtastic-touched persistence file: both prod and dev SQLite
# databases, plus all UI-settings / theme files under the shared Darkness
# directory. Use after a corrupt or stale-format settings file has wedged
# the app and a clean slate is faster than surgical fixes.
#
# Refuses to run while either server is listening (prod 8443 / dev 8444) so
# SQLite WAL doesn't race with the delete. Each file is backed up with a
# timestamp suffix before removal — recoverable on misclick.
#
# Files removed:
#   ~/Library/Application Support/Termtastic/termtastic.db (+ -wal/-shm)
#   ~/Library/Application Support/Termtastic/termtastic-dev.db (+ -wal/-shm)
#   ~/Library/Application Support/Darkness/termtastic.json
#   ~/Library/Application Support/Darkness/themes.json
#   ~/Library/Application Support/Darkness/ui-settings.json
#
# Not touched: existing *.db.bak.* archives (safety nets), other Darkness
# apps' files (Notegrow/, DarknessDemo/), and the Electron Chromium profile
# directories (those are Chrome shell state, not termtastic config).
set -euo pipefail

TERMTASTIC_DIR="$HOME/Library/Application Support/Termtastic"
DARKNESS_DIR="$HOME/Library/Application Support/Darkness"

PROD_DB="$TERMTASTIC_DIR/termtastic.db"
DEV_DB="$TERMTASTIC_DIR/termtastic-dev.db"
PER_APP_JSON="$DARKNESS_DIR/termtastic.json"
SHARED_THEMES_JSON="$DARKNESS_DIR/themes.json"
LEGACY_UI_SETTINGS_JSON="$DARKNESS_DIR/ui-settings.json"

PROD_PORT="${TERMTASTIC_PROD_PORT:-8443}"
DEV_PORT="${TERMTASTIC_DEV_PORT:-8444}"

for port_label in "prod:$PROD_PORT" "dev:$DEV_PORT"; do
    label="${port_label%%:*}"
    port="${port_label##*:}"
    if lsof -tiTCP:"$port" -sTCP:LISTEN -n -P >/dev/null 2>&1; then
        echo "$label server is running on port $port. Stop it first:" >&2
        echo "  scripts/kill-${label}-server.sh" >&2
        exit 1
    fi
done

stamp="$(date +%Y%m%d-%H%M%S)"
deleted_any=0

# Back up + delete a single file plus optional sidecars (e.g. SQLite WAL/SHM).
# $1 = main file, $2... = sidecar suffixes appended to $1 (no backup of sidecars).
remove_with_backup() {
    local main="$1"; shift
    if [[ ! -f "$main" ]]; then
        echo "  (skip)   $main — not present"
        return
    fi
    local backup="$main.bak.$stamp"
    cp "$main" "$backup"
    rm -f "$main"
    for suffix in "$@"; do
        rm -f "$main$suffix"
    done
    echo "  deleted  $main"
    echo "    backup $backup"
    deleted_any=1
}

echo "Termtastic SQLite databases:"
remove_with_backup "$PROD_DB" "-wal" "-shm"
remove_with_backup "$DEV_DB"  "-wal" "-shm"

echo
echo "Darkness shared UI-settings files:"
remove_with_backup "$PER_APP_JSON"
remove_with_backup "$SHARED_THEMES_JSON"
remove_with_backup "$LEGACY_UI_SETTINGS_JSON"

echo
if [[ "$deleted_any" -eq 1 ]]; then
    echo "Done. Restart termtastic to regenerate fresh state."
else
    echo "Nothing to delete — all targets were already absent."
fi
