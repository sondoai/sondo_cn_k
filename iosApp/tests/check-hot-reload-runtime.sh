#!/usr/bin/env bash
set -euo pipefail
SONDO_REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SONDO_JS_BUNDLE="${1:-$SONDO_REPO_DIR/shared/build/kotlin-webpack/js/developmentExecutable/sondo.js}"
if [[ ! -f "$SONDO_JS_BUNDLE" ]]; then
    printf '缺少真实 JS bundle，请先在工程根目录运行 ./dev.sh ios --once\n' >&2
    exit 1
fi
SONDO_TEST_DIR="$(mktemp -d "${TMPDIR:-/tmp}/sondo-jsc-check.XXXXXX")"
trap 'rm -rf "$SONDO_TEST_DIR"' EXIT
xcrun clang -fobjc-arc -fmodules -DDEBUG=1 -framework Foundation -framework JavaScriptCore \
    "$SONDO_REPO_DIR/iosApp/tests/HotReloadRuntimeCheck.m" \
    "$SONDO_REPO_DIR/iosApp/iosApp/Kuikly/SDHotReloadRuntime.m" \
    -o "$SONDO_TEST_DIR/check"
SONDO_JS_VERSION="$(shasum -a 256 "$SONDO_JS_BUNDLE" | awk '{print $1}')"
"$SONDO_TEST_DIR/check" "$SONDO_JS_BUNDLE" "$SONDO_JS_VERSION"
