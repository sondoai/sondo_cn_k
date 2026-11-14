#!/usr/bin/env bash
#
# 下载美团 Walle CLI（walle-cli-all.jar）到 tools/walle/
#
# 用法：
#   ./scripts/build/download_walle.sh
#   ./scripts/build/download_walle.sh --force   # 已存在也重新下载覆盖
#
# 官方端点（按序尝试）：
#   1) GitHub Releases v1.1.6
#   2) jsDelivr gh mirror
#   3) ghproxy 镜像
# 项目页：https://github.com/Meituan-Dianping/walle
#
# 成功后需存在：tools/walle/walle-cli-all.jar

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$ROOT_DIR"

WALLE_HOME="${WALLE_HOME:-$ROOT_DIR/tools/walle}"
JAR_NAME="walle-cli-all.jar"
JAR_PATH="$WALLE_HOME/$JAR_NAME"
FORCE=0

URLS=(
  "https://github.com/Meituan-Dianping/walle/releases/download/v1.1.6/walle-cli-all.jar"
  "https://cdn.jsdelivr.net/gh/Meituan-Dianping/walle@v1.1.6/walle-cli/build/libs/walle-cli-all.jar"
  "https://ghproxy.com/https://github.com/Meituan-Dianping/walle/releases/download/v1.1.6/walle-cli-all.jar"
  "https://mirror.ghproxy.com/https://github.com/Meituan-Dianping/walle/releases/download/v1.1.6/walle-cli-all.jar"
)

die() { echo "Error: $*" >&2; exit 1; }
log() { echo "[download_walle] $*"; }

usage() {
  sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --force) FORCE=1; shift ;;
    *) die "未知参数: $1（见 --help）" ;;
  esac
done

if [[ -f "$JAR_PATH" && "$FORCE" -eq 0 ]]; then
  log "已存在: $JAR_PATH"
  ls -lh "$JAR_PATH"
  exit 0
fi

command -v curl >/dev/null || die "需要 curl"

mkdir -p "$WALLE_HOME"
# 保留 .gitkeep
[[ -f "$WALLE_HOME/.gitkeep" ]] || touch "$WALLE_HOME/.gitkeep"

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/walle_dl.XXXXXX")"
cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT

TMP_JAR="$TMP_DIR/$JAR_NAME"
USED_URL=""

download_one() {
  local url="$1"
  log "尝试: $url"
  if curl -L --fail --retry 2 --connect-timeout 20 --max-time 300 \
      -o "$TMP_JAR" "$url"; then
    # 拒绝 HTML/JSON 错误页冒充 jar
    if file "$TMP_JAR" | grep -qiE 'Zip archive|Java archive'; then
      USED_URL="$url"
      return 0
    fi
    log "响应不是 zip/jar，跳过"
  fi
  return 1
}

for url in "${URLS[@]}"; do
  rm -f "$TMP_JAR"
  if download_one "$url"; then
    break
  fi
done

[[ -n "$USED_URL" && -f "$TMP_JAR" ]] || die \
  "自动下载失败。请手动下载 walle-cli-all.jar 到 ${JAR_PATH}
Release: https://github.com/Meituan-Dianping/walle/releases/tag/v1.1.6"

cp -f "$TMP_JAR" "$JAR_PATH"
[[ -f "$JAR_PATH" ]] || die "安装失败: 缺少 $JAR_NAME"

log "安装完成:"
log "  jar:  $JAR_PATH ($(du -h "$JAR_PATH" | awk '{print $1}'))"
log "  url:  $USED_URL"
file "$JAR_PATH"
