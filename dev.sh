#!/usr/bin/env bash
set -euo pipefail
SONDO_PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec node "$SONDO_PROJECT_DIR/scripts/hot-reload.mjs" "$@"
