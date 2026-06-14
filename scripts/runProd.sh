#!/usr/bin/env bash
# Starts the Termtastic server on the default production TLS port (8443).
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew :server:run "$@"
