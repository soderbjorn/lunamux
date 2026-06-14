#!/usr/bin/env bash
# Starts the Termtastic server on the debug TLS port (8444) so it can run
# alongside a production instance on the default TLS port (8443).
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew :server:run -Dtermtastic.port=8444 "$@"
