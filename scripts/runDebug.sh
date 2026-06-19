#!/usr/bin/env bash
# Starts the Termtastic server on the debug TLS port (8444) so it can run
# alongside a production instance on the default TLS port (8443).
#
# The debug server is pointed at a separate SQLite database (termtastic-dev.db)
# via -Dtermtastic.dbPath, so it never shares on-disk state with a prod server
# running off the default termtastic.db. Without this override AppPaths falls
# back to termtastic.db for both, and the two instances would race on the same
# file (and its WAL). This is the same dev DB that scripts/delete-dev-config.sh
# wipes and that scripts/run-electron-dev.sh points its server at. The TLS
# keystore under tls/ is intentionally left shared — it's just the loopback
# cert.
set -euo pipefail
cd "$(dirname "$0")/.."

DEV_DB="$HOME/Library/Application Support/Termtastic/termtastic-dev.db"

./gradlew :server:run \
    -Dtermtastic.port=8444 \
    -Dtermtastic.dbPath="$DEV_DB" \
    "$@"
