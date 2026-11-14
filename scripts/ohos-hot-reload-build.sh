#!/usr/bin/env bash
# 构建开发期JSVM宿主；只需首次安装或原生能力变动时重建，Kotlin改动由hot-reload.mjs处理。
set -euo pipefail

SONDO_PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SONDO_DEVECO_DIR="${SONDO_DEVECO_DIR:-/Applications/DevEco-Studio.app/Contents}"
export DEVECO_SDK_HOME="$SONDO_DEVECO_DIR/sdk"
export JAVA_HOME="$SONDO_DEVECO_DIR/jbr/Contents/Home"
export NODE_HOME="$SONDO_DEVECO_DIR/tools/node"
export PATH="$JAVA_HOME/bin:$NODE_HOME/bin:$SONDO_DEVECO_DIR/tools/ohpm/bin:$SONDO_DEVECO_DIR/tools/hvigor/bin:$PATH"
export SONDO_KUIKLY_HOT_RELOAD=1
export SONDO_HOT_RELOAD_URL="${SONDO_HOT_RELOAD_URL:-http://127.0.0.1:8088}"

if [[ ! -x "$NODE_HOME/bin/node" ]]; then
  echo "未找到DevEco工具链：$SONDO_DEVECO_DIR" >&2
  exit 1
fi
# JSVM宿主仍从HAP读取assets://common/…；同步已有资源，不触发Kotlin/Native构建。
SONDO_ASSETS_SOURCE="$SONDO_PROJECT_DIR/shared/src/commonMain/assets/common"
SONDO_ASSETS_TARGET="$SONDO_PROJECT_DIR/ohosApp/entry/src/main/resources/resfile/common"
if [[ -d "$SONDO_ASSETS_SOURCE" ]]; then
  mkdir -p "$SONDO_ASSETS_TARGET"
  rsync -a --delete --exclude '.DS_Store' "$SONDO_ASSETS_SOURCE/" "$SONDO_ASSETS_TARGET/"
fi
cd "$SONDO_PROJECT_DIR/ohosApp"
if [[ ! -d entry/oh_modules ]]; then
  ohpm install --all
fi
hvigorw --mode module -p module=entry@default -p product=hotReload -p buildMode=debug \
  assembleHap --analyze=normal --parallel --incremental --no-daemon "$@"
echo "开发包：$SONDO_PROJECT_DIR/ohosApp/entry/build/hotReload/outputs/default/entry-default-signed.hap"
