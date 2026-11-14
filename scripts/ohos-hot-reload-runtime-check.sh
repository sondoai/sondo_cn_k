#!/usr/bin/env bash
set -euo pipefail
SONDO_PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SONDO_DEVECO_DIR="${SONDO_DEVECO_DIR:-/Applications/DevEco-Studio.app/Contents}"
SONDO_CHECK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/sondo-jsvm-check.XXXXXX")"
trap 'rm -rf "$SONDO_CHECK_DIR"' EXIT
# 在macOS宿主执行失败路径；未走到的NAPI/Kuikly符号由dyld延迟解析。
clang++ -std=c++17 -Wno-ignored-attributes -Wno-unknown-attributes \
  -idirafter "$SONDO_DEVECO_DIR/sdk/default/openharmony/native/sysroot/usr/include" \
  -I "$SONDO_PROJECT_DIR/ohosApp/entry/oh_modules/@kuikly-open/render/include" \
  -Wl,-undefined,dynamic_lookup \
  "$SONDO_PROJECT_DIR/scripts/ohos-hot-reload-runtime-check.cpp" -o "$SONDO_CHECK_DIR/check"
"$SONDO_CHECK_DIR/check"
