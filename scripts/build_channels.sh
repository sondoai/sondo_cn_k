#!/usr/bin/env bash
#
# Android 渠道包（两步，流程同参考工程 ../sondo_cn/scripts/build_channels.sh）：
#   1) 打母包（assembleRelease，已用 androidApp/key 正式签名，v1+v2）
#        ./scripts/build_channels.sh --build
#        ./scripts/build_channels.sh --build --env test
#   2) 对签名包写渠道（母包可直接用；要加固就先在外部工具加固并重签，再传加固包）
#        ./scripts/build_channels.sh --signed dist/channels/sondo-2.3.2-prod-mother.apk
#        ./scripts/build_channels.sh --signed xxx_signed.apk --channels official,huawei
#
# Walle 把渠道写进已有的 v2 签名块，输入必须是已签名包。写完从 padding 扣回 4096 对齐：
# Walle 1.1.6 会把签名块撑大，Android 11+ 要求签名块是 4096 的整数倍，否则 INSTALL_PARSE_FAILED_NO_CERTIFICATES。
# App 侧读取见 androidApp/.../kuikly/InstallChannel.kt（请求头 App-Channel、友盟 / 极光渠道）。
#
# 可选：
#   --env prod|test|local   仅 --build 使用，默认 prod
#   --out <目录>            默认 dist/channels
#   CHANNEL_LIST_FILE       默认 androidApp/channel_list
#   Walle：tools/walle/walle-cli-all.jar，缺失时自动执行 scripts/download_walle.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"
load_local_config
setup_android_env

CHANNEL_LIST_FILE="${CHANNEL_LIST_FILE:-$ROOT_DIR/androidApp/channel_list}"
API_ENV="prod"
OUT_DIR="$DIST_DIR/channels"
MODE=""
SIGNED_APK=""
CHANNELS_FILTER=""

usage() {
  sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --build)
      [[ -z "$MODE" ]] || die "--build 与 --signed 不能同时使用"
      MODE=build; shift ;;
    --signed)
      [[ -z "$MODE" ]] || die "--build 与 --signed 不能同时使用"
      [[ $# -ge 2 ]] || die "--signed 需要 apk 路径"
      MODE=channels; SIGNED_APK="$2"; shift 2 ;;
    --channels) CHANNELS_FILTER="${2:-}"; shift 2 ;;
    --env) API_ENV="${2:-}"; shift 2 ;;
    --out) OUT_DIR="${2:-}"; shift 2 ;;
    *) echo "未知参数: $1" >&2; usage 1 ;;
  esac
done
[[ -n "$MODE" ]] || usage 1

########################################
# Step 1：母包
########################################
build_mother() {
  [[ -z "$CHANNELS_FILTER" ]] || die "--channels 只在 --signed 时使用"
  case "$API_ENV" in prod|test|local) ;; dev) API_ENV=local ;; *) die "环境只能是 prod / test / local：$API_ENV" ;; esac
  logTitle "打母包 assembleRelease（API_ENV=$API_ENV）"
  ./gradlew :androidApp:assembleRelease -PAPI_ENV="$API_ENV" -PSHOW_TOKEN_EDITOR=false --console=plain
  local apk
  apk="$(ls -t androidApp/build/outputs/apk/release/*.apk 2>/dev/null | head -n 1 || true)"
  [[ -n "$apk" ]] || die "未找到 release apk"
  mkdir -p "$OUT_DIR"
  local mother="$OUT_DIR/sondo-$(android_version_name)-$API_ENV-mother.apk"
  cp -f "$apk" "$mother"
  log "母包: $mother"
  echo ""
  echo "下一步（不加固可直接用母包；加固则先外部加固并重签）："
  echo "  ./scripts/build_channels.sh --signed $mother"
}

########################################
# Step 2：写渠道
########################################
load_channels() {
  [[ -f "$CHANNEL_LIST_FILE" ]] || die "渠道清单不存在: $CHANNEL_LIST_FILE"
  local all=() line
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%%#*}"
    line="$(echo "$line" | tr -d '[:space:]')"
    [[ -n "$line" ]] && all+=("$line")
  done < "$CHANNEL_LIST_FILE"
  [[ ${#all[@]} -gt 0 ]] || die "渠道清单为空: $CHANNEL_LIST_FILE"

  CHANNELS=()
  if [[ -n "$CHANNELS_FILTER" ]]; then
    local wanted w c found
    IFS=',' read -ra wanted <<< "$CHANNELS_FILTER"
    for w in "${wanted[@]}"; do
      w="$(echo "$w" | tr -d '[:space:]')"
      [[ -z "$w" ]] && continue
      found=0
      for c in "${all[@]}"; do
        [[ "$c" == "$w" ]] && { CHANNELS+=("$c"); found=1; break; }
      done
      [[ $found -eq 1 ]] || die "渠道不在清单中: $w（见 $CHANNEL_LIST_FILE）"
    done
  else
    CHANNELS=("${all[@]}")
  fi
  [[ ${#CHANNELS[@]} -gt 0 ]] || die "未选定任何渠道"
  log "渠道（${#CHANNELS[@]}）: ${CHANNELS[*]}"
}

require_signed_apk() {
  [[ -f "$SIGNED_APK" ]] || die "签名包不存在: $SIGNED_APK"
  command -v apksigner >/dev/null 2>&1 || die "未找到 apksigner（检查 ANDROID_HOME/build-tools）"
  apksigner verify "$SIGNED_APK" >/dev/null || die "签名校验失败，请确认该包已签名: $SIGNED_APK"
  log "签名校验通过: $SIGNED_APK"
}

ensure_walle() {
  WALLE_JAR="$ROOT_DIR/tools/walle/walle-cli-all.jar"
  if [[ ! -f "$WALLE_JAR" ]]; then
    log "未找到 Walle，自动下载..."
    "$SCRIPT_DIR/download_walle.sh"
  fi
  [[ -f "$WALLE_JAR" ]] || die "Walle 不存在: $WALLE_JAR（执行 ./scripts/download_walle.sh）"
  command -v python3 >/dev/null 2>&1 || die "未找到 python3，无法对齐 APK Signing Block"
}

write_channels() {
  logTitle "写渠道"
  mkdir -p "$OUT_DIR"
  local base channel out
  base="$(basename "$SIGNED_APK" .apk)"
  base="${base%-mother}"
  CHANNEL_APKS=()
  for channel in "${CHANNELS[@]}"; do
    out="$OUT_DIR/${base}-${channel}.apk"
    rm -f "$out"
    java -jar "$WALLE_JAR" put -c "$channel" "$SIGNED_APK" "$out" >/dev/null
    [[ -f "$out" ]] || die "渠道包未生成: $out"
    python3 "$SCRIPT_DIR/align_apk_signing_block.py" "$out"
    CHANNEL_APKS+=("$out")
    log "[$channel] $out"
  done
}

verify_outputs() {
  logTitle "校验"
  local apk shown
  for apk in "${CHANNEL_APKS[@]}"; do
    python3 "$SCRIPT_DIR/align_apk_signing_block.py" --check "$apk"
    apksigner verify "$apk" >/dev/null || die "签名校验失败: $apk"
    shown="$(java -jar "$WALLE_JAR" show "$apk" 2>/dev/null | tail -n 1)"
    log "OK $(basename "$apk")  $shown"
  done
}

if [[ "$MODE" == build ]]; then
  build_mother
  exit 0
fi

load_channels
require_signed_apk
ensure_walle
write_channels
verify_outputs
echo ""
log "已生成 ${#CHANNEL_APKS[@]} 个渠道包 → $OUT_DIR"
