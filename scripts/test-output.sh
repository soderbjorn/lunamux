#!/usr/bin/env bash
#
# test-output.sh
#
# Emits an incrementing integer to stdout twice per second (every 0.5s),
# starting at 1, until interrupted with Ctrl-C.
#
# Usage: scripts/test-output.sh

set -euo pipefail

count=0
while true; do
    count=$((count + 1))
    echo "$count"
    sleep 0.5
done
