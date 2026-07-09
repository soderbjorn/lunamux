#!/usr/bin/env bash
# Wipe EVERY on-disk config file, database, and cached state the macOS
# Electron app has ever written — under BOTH its old "Termtastic" identity and
# its new "Lunamux" identity — so you can test a pristine first-run install and
# a Termtastic→Lunamux upgrade from a clean slate.
#
# Why so many locations? The Termtastic→Lunamux rename deliberately did NOT
# relocate existing installs (see electron-main ElectronMain.kt): prod pins its
# Electron userData dir, its SQLite dir, its bundle id, and its shared-settings
# filename to the ORIGINAL "Termtastic" names so upgrades keep their data. Only
# dev launches, named instances, the demo, logs, and the productName-derived
# defaults use the new "Lunamux" names. On top of that, Chromium/Electron create
# an extra userData dir keyed off electron/package.json's "name" field (which
# changed termtastic-electron → lunamux-electron) BEFORE app.setName() runs, and
# those dirs hold real renderer state (local_state.json = device auth token).
# The net effect is that config is scattered across ~a dozen directories under
# three different naming schemes. This script targets all of them.
#
# See scripts/config-locations.html (open in a browser) for a visual map of
# every location below and who reads/writes it.
#
# SAFETY
#   - Refuses to run while the prod (8443) or dev (8444) server is listening, so
#     a live SQLite WAL can't race the delete. Quit the app / stop the servers
#     first (scripts/kill-prod-server.sh / scripts/kill-dev-server.sh).
#   - Nothing is hard-deleted: whole userData/log dirs are renamed to
#     "<name>.bak.<timestamp>" (an instant, space-free rename you can restore or
#     `rm -rf` later); individual shared-settings files are copied to
#     "<file>.bak.<timestamp>" before removal; NSUserDefaults are backed up then
#     cleared via `defaults delete`. The one exception is the demo scratch dir,
#     which is throwaway (the app wipes it on every launch anyway).
#   - Other Darkness apps are left untouched: Notegrow/, TreeFacts/,
#     DarknessDemo/ userData dirs, their *.json in the shared Darkness dir, and
#     their plists are all out of scope.
#
# To purge the safety-net backups this script leaves behind:
#   rm -rf ~/Library/Application\ Support/*.bak.* \
#          ~/Library/Logs/{Termtastic,Lunamux}.bak.* \
#          ~/Library/Application\ Support/Darkness/*.bak.* \
#          ~/Library/Preferences/se.soderbjorn.*.plist.bak.*
set -euo pipefail

APP_SUPPORT="$HOME/Library/Application Support"
DARKNESS_DIR="$APP_SUPPORT/Darkness"
PREFS_DIR="$HOME/Library/Preferences"
LOGS_DIR="$HOME/Library/Logs"
TMP_DIR="${TMPDIR:-/tmp}"

PROD_PORT="${LUNAMUX_PROD_PORT:-8443}"
DEV_PORT="${LUNAMUX_DEV_PORT:-8444}"

# ── Guard: no live server ────────────────────────────────────────────────────
for port_label in "prod:$PROD_PORT" "dev:$DEV_PORT"; do
    label="${port_label%%:*}"
    port="${port_label##*:}"
    if lsof -tiTCP:"$port" -sTCP:LISTEN -n -P >/dev/null 2>&1; then
        echo "The $label server is listening on port $port. Quit the app / stop it first:" >&2
        echo "  scripts/kill-${label}-server.sh" >&2
        exit 1
    fi
done

stamp="$(date +%Y%m%d-%H%M%S)"
removed_any=0

# Rename a whole directory out of the way (instant, recoverable). Skips paths
# that are already backups so re-runs don't nest .bak.<stamp>.<stamp> dirs.
# $1 = absolute directory path.
remove_dir() {
    local dir="$1"
    [[ "$dir" == *.bak.* ]] && return
    if [[ ! -d "$dir" ]]; then
        return
    fi
    mv "$dir" "$dir.bak.$stamp"
    echo "  moved    $dir"
    echo "    backup $dir.bak.$stamp"
    removed_any=1
}

# Copy a single file to a timestamped backup, then delete it plus any sidecars.
# $1 = main file, $2... = sidecar suffixes appended to $1 (deleted, not backed up).
remove_file() {
    local main="$1"; shift
    if [[ ! -f "$main" ]]; then
        return
    fi
    cp "$main" "$main.bak.$stamp"
    rm -f "$main"
    for suffix in "$@"; do
        rm -f "$main$suffix"
    done
    echo "  deleted  $main"
    echo "    backup $main.bak.$stamp"
    removed_any=1
}

# ── 1. Electron userData dirs (SQLite DBs, device token, TLS keystore, shell
#       init, Chromium state) — both naming schemes + every dev/instance/demo
#       variant, plus the productName-derived defaults. ─────────────────────────
echo "Electron userData directories:"
shopt -s nullglob
# Capitalised app-name dirs: "Termtastic", "Termtastic Dev", "Termtastic Test",
# "Termtastic 3D Dev", "Lunamux", "Lunamux Dev", "Lunamux Demo", "Lunamux Test",
# "Lunamux 3D Dev", and any other "Lunamux <instance>" / "Termtastic <instance>".
for dir in "$APP_SUPPORT"/Termtastic "$APP_SUPPORT"/Termtastic\ * \
           "$APP_SUPPORT"/Lunamux "$APP_SUPPORT"/Lunamux\ *; do
    remove_dir "$dir"
done
# Lowercase electron/package.json "name" dirs created by Chromium before
# app.setName() runs (termtastic-electron → lunamux-electron; termtastic3d from
# the 3D spike). These hold local_state.json / news_state.json / cookies.
for dir in "$APP_SUPPORT"/termtastic-electron \
           "$APP_SUPPORT"/termtastic3d-electron \
           "$APP_SUPPORT"/lunamux-electron; do
    remove_dir "$dir"
done
shopt -u nullglob

# ── 2. Shared Darkness dir: only this app's files (per-app UI settings + the
#       cross-app theme definitions + the legacy pre-split file). Other apps'
#       files (notegrow.json, treefacts.json, darkness-demo.json) are left. ─────
echo
echo "Shared Darkness UI-settings files:"
remove_file "$DARKNESS_DIR/termtastic.json"      # per-app UI settings (appName pinned to "termtastic")
remove_file "$DARKNESS_DIR/lunamux.json"         # defensive: if appName ever follows the rename
remove_file "$DARKNESS_DIR/themes.json"          # cross-app theme/scheme definitions (shared)
remove_file "$DARKNESS_DIR/ui-settings.json"     # legacy pre-split settings file

# ── 3. NSUserDefaults plists (macOS Preferences). Bundle id is pinned to
#       se.soderbjorn.termtastic; the lunamux one is defensive. Clear the
#       cfprefsd cache too, else it can rewrite the file on next app exit. ──────
echo
echo "NSUserDefaults (macOS Preferences):"
for bundle in se.soderbjorn.termtastic se.soderbjorn.lunamux; do
    plist="$PREFS_DIR/$bundle.plist"
    if [[ -f "$plist" ]]; then
        cp "$plist" "$plist.bak.$stamp"
        defaults delete "$bundle" >/dev/null 2>&1 || true
        rm -f "$plist"
        echo "  cleared  $bundle"
        echo "    backup $plist.bak.$stamp"
        removed_any=1
    fi
done

# ── 4. Log directories (app.getPath('logs') = ~/Library/Logs/<APP_NAME>). ─────
echo
echo "Log directories:"
remove_dir "$LOGS_DIR/Termtastic"
remove_dir "$LOGS_DIR/Lunamux"

# ── 5. Demo scratch dir under the OS temp dir. Throwaway — the app rm -rf's it
#       on every demo launch — so hard-delete, no backup. ──────────────────────
echo
echo "Demo scratch directories:"
for dir in "$TMP_DIR/lunamux-demo" "$TMP_DIR/termtastic-demo"; do
    if [[ -d "$dir" ]]; then
        rm -rf "$dir"
        echo "  deleted  $dir"
        removed_any=1
    fi
done

echo
if [[ "$removed_any" -eq 1 ]]; then
    echo "Done. Every Termtastic/Lunamux config location is cleared."
    echo "Relaunch the app for a fresh first-run install."
else
    echo "Nothing to delete — all targets were already absent."
fi
