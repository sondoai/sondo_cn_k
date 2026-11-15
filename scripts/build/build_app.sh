#!/usr/bin/env bash
#
# Sondo（Kuikly）打包 →（可选）上传蒲公英。三端：Android / iOS / HarmonyOS。
#
# 用法：
#   ./scripts/build/build_app.sh                       # 全交互
#   ./scripts/build/build_app.sh -p android -e test -m release --no-token --no-upload
#   ./scripts/build/build_app.sh -p android -e test -m release --token --upload -d "修复登录"
#
# 参数（给了就不再问，全部给齐即可免交互）：
#   -p, --platform android|ios|ohos
#   -e, --env      prod|test|local     接口环境（Android -PAPI_ENV / iOS SD_API_ENV / 鸿蒙 buildProfileFields）
#   -m, --mode     release|debug
#   --token / --no-token               是否展示设置页「修改 Token」测试入口
#   --upload / --no-upload             是否上传蒲公英
#   -d, --desc     <更新描述>          上传时的版本描述，默认「[平台/环境] 时间」
#
# 产物复制到 dist/sondo-<平台>-<版本>-<环境>-<模式>-<时间>.<apk|ipa|hap>
# 密钥（蒲公英 Key、鸿蒙签名材料）在 scripts/build/build.local.sh，模板见 scripts/build/build.local.sh.example。
# Android 渠道包另见 scripts/build/build_channels.sh。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$ROOT_DIR"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"
# shellcheck source=lib/pgyer.sh
source "$SCRIPT_DIR/lib/pgyer.sh"
load_local_config

usage() {
  sed -n '2,21p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

PLATFORM="" API_ENV="" MODE="" TOKEN="" UPLOAD="" DESC=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage 0 ;;
    -p|--platform) PLATFORM="${2:-}"; shift 2 ;;
    -e|--env) API_ENV="${2:-}"; shift 2 ;;
    -m|--mode) MODE="${2:-}"; shift 2 ;;
    --token) TOKEN=true; shift ;;
    --no-token) TOKEN=false; shift ;;
    --upload) UPLOAD=true; shift ;;
    --no-upload) UPLOAD=false; shift ;;
    -d|--desc) DESC="${2:-}"; shift 2 ;;
    *) echo "未知参数: $1" >&2; usage 1 ;;
  esac
done

# choose <变量名> <标题> <默认序号> <选项1> <选项2> ...；选项写成「值|说明」
choose() {
  local var="$1" title="$2" def="$3"; shift 3
  local opts=("$@") i=1 o
  logTitle "$title"
  for o in "${opts[@]}"; do
    echo "$i) ${o#*|}"
    i=$((i + 1))
  done
  local ans
  read -rp "请输入序号 [默认 $def]: " ans
  ans="${ans:-$def}"
  if ! [[ "$ans" =~ ^[0-9]+$ ]] || (( ans < 1 || ans > ${#opts[@]} )); then
    ans="$def"
  fi
  printf -v "$var" '%s' "${opts[$((ans - 1))]%%|*}"
}

########################################
# 1. 收集参数
########################################
# 校验已给出的参数（空值跳过，留给交互），交互前后各跑一次
validate_args() {
  case "$PLATFORM" in ""|android|ios|ohos) ;; *) die "平台只能是 android / ios / ohos：$PLATFORM" ;; esac
  case "$API_ENV" in ""|prod|test|local) ;; dev) API_ENV=local ;; *) die "环境只能是 prod / test / local：$API_ENV" ;; esac
  case "$MODE" in ""|release|debug) ;; *) die "模式只能是 release / debug：$MODE" ;; esac
}
validate_args

[[ -n "$PLATFORM" ]] || choose PLATFORM "选择平台" 1 "android|Android（apk）" "ios|iOS（ipa，需完整 Xcode）" "ohos|HarmonyOS（hap）"
[[ -n "$API_ENV" ]] || choose API_ENV "选择接口环境" 1 "prod|prod  正式 api.sondo.ai" "test|test  测试 api-test1.sondo.ai" "local|local 本地联调"
[[ -n "$MODE" ]] || choose MODE "选择构建模式" 1 "release|Release" "debug|Debug"
[[ -n "$TOKEN" ]] || choose TOKEN "设置页「修改 Token」测试入口" 1 "false|关闭（线上包）" "true|开启"
[[ -n "$UPLOAD" ]] || choose UPLOAD "是否上传蒲公英" 1 "true|打包并上传" "false|仅打包"

validate_args

if [[ "$UPLOAD" == true ]]; then
  pgyer_require_key
  if [[ -z "$DESC" ]]; then
    local_default="[$PLATFORM/$API_ENV/$MODE] $(date +'%Y-%m-%d %H:%M')"
    if [[ -t 0 ]]; then
      read -rp "版本更新描述 [默认: $local_default]: " DESC
    fi
    DESC="${DESC:-$local_default}"
  fi
fi

log "平台=$PLATFORM 环境=$API_ENV 模式=$MODE Token入口=$TOKEN 上传=$UPLOAD"
mkdir -p "$DIST_DIR"
MODE_CAP="$(printf '%s' "${MODE:0:1}" | tr '[:lower:]' '[:upper:]')${MODE:1}"

########################################
# 2. 各平台打包
########################################
build_android() {
  setup_android_env
  logTitle "Android assemble$MODE_CAP"
  ./gradlew ":androidApp:assemble$MODE_CAP" \
    -PAPI_ENV="$API_ENV" -PSHOW_TOKEN_EDITOR="$TOKEN" --console=plain
  local apk
  apk="$(ls -t "androidApp/build/outputs/apk/$MODE"/*.apk 2>/dev/null | head -n 1 || true)"
  [[ -n "$apk" ]] || die "未找到 apk：androidApp/build/outputs/apk/$MODE/"
  if command -v apksigner >/dev/null 2>&1; then
    apksigner verify "$apk" >/dev/null || die "apk 签名校验失败: $apk"
  fi
  ARTIFACT="$DIST_DIR/sondo-android-$(android_version_name)-$API_ENV-$MODE-$(timestamp).apk"
  cp -f "$apk" "$ARTIFACT"
  BUILD_TYPE=apk
}

build_ios() {
  command -v xcodebuild >/dev/null 2>&1 && xcodebuild -version >/dev/null 2>&1 \
    || die "iOS 打包需要完整 Xcode（当前机器 xcodebuild 不可用）"
  setup_android_env   # Xcode 脚本阶段会调用 ../gradlew 编 shared，需要 JDK
  if [[ ! -d iosApp/iosApp.xcworkspace || ! -d iosApp/Pods ]]; then
    command -v pod >/dev/null 2>&1 || die "未找到 CocoaPods（pod）"
    logTitle "生成 shared.podspec 并 pod install"
    ./gradlew :shared:podInstall -Psondo.enableIos=true --console=plain
  fi
  local config archive export_dir plist
  if [[ "$MODE" == release ]]; then config=Release; else config=Debug; fi
  local work="$ROOT_DIR/iosApp/build"
  archive="$work/Sondo.xcarchive"
  export_dir="$work/export"
  plist="${IOS_EXPORT_OPTIONS:-$ROOT_DIR/iosApp/ExportOptions-debug.plist}"
  [[ -f "$plist" ]] || die "导出配置不存在: $plist"
  local token_flag=NO
  [[ "$TOKEN" == true ]] && token_flag=YES
  rm -rf "$archive" "$export_dir"

  logTitle "xcodebuild archive ($config)"
  xcodebuild archive \
    -workspace iosApp/iosApp.xcworkspace -scheme iosApp -configuration "$config" \
    -destination 'generic/platform=iOS' -archivePath "$archive" \
    -allowProvisioningUpdates \
    SD_API_ENV="$API_ENV" SD_SHOW_TOKEN_EDITOR="$token_flag"

  logTitle "xcodebuild -exportArchive"
  xcodebuild -exportArchive -archivePath "$archive" -exportPath "$export_dir" \
    -exportOptionsPlist "$plist" -allowProvisioningUpdates
  local ipa
  ipa="$(ls -t "$export_dir"/*.ipa 2>/dev/null | head -n 1 || true)"
  [[ -n "$ipa" ]] || die "未找到 ipa：$export_dir"
  ARTIFACT="$DIST_DIR/sondo-ios-$(ios_version_name)-$API_ENV-$MODE-$(timestamp).ipa"
  cp -f "$ipa" "$ARTIFACT"
  BUILD_TYPE=ipa
}

# 用 hap-sign-tool 按 build.local.sh 里的发布证书签名；材料不全返回 1
sign_hap() {
  local in="$1" out="$2"
  local f
  for f in "${OHOS_P12:-}" "${OHOS_CERT:-}" "${OHOS_PROFILE:-}"; do
    [[ -n "$f" && -f "$f" ]] || return 1
  done
  [[ -n "${OHOS_P12_PASSWORD:-}" ]] || return 1
  [[ -f "$HAP_SIGN_TOOL" ]] || die "未找到 hap-sign-tool: $HAP_SIGN_TOOL"
  logTitle "hap-sign-tool 签名（$(basename "$OHOS_P12")）"
  "$DEVECO_JAVA_HOME/bin/java" -jar "$HAP_SIGN_TOOL" sign-app \
    -mode localSign \
    -keyAlias "${OHOS_KEY_ALIAS:-sondo_release}" \
    -keyPwd "${OHOS_KEY_PASSWORD:-$OHOS_P12_PASSWORD}" \
    -appCertFile "$OHOS_CERT" \
    -profileFile "$OHOS_PROFILE" \
    -keystoreFile "$OHOS_P12" \
    -keystorePwd "$OHOS_P12_PASSWORD" \
    -signAlg SHA256withECDSA \
    -inFile "$in" -outFile "$out"
}

build_ohos() {
  setup_android_env
  setup_ohos_env
  logTitle "编译 libshared.so（ohos_build.sh $MODE）"
  ./ohos_build.sh "$MODE"

  logTitle "ohpm install + hvigor assembleHap ($MODE)"
  local out_dir="$ROOT_DIR/ohosApp/entry/build/default/outputs/default"
  rm -f "$out_dir"/*.hap
  (
    cd ohosApp
    export JAVA_HOME="$DEVECO_JAVA_HOME"
    export PATH="$JAVA_HOME/bin:$PATH"
    ohpm install --all
    # API_ENV / SHOW_TOKEN_EDITOR 由 entry/hvigorfile.ts 读取并覆盖 buildProfileFields；
    # SONDO_SIGN_FALLBACK 由根 hvigorfile.ts 读取：签名文件不在本机时跳过 hvigor 签名，交给下面的 sign_hap
    hvigorw --mode module -p module=entry@default -p product=default -p buildMode="$MODE" \
      -p API_ENV="$API_ENV" -p SHOW_TOKEN_EDITOR="$TOKEN" -p SONDO_SIGN_FALLBACK=true \
      assembleHap --analyze=normal --parallel --incremental --no-daemon
  )

  local unsigned signed hap
  unsigned="$out_dir/entry-default-unsigned.hap"
  signed="$out_dir/entry-default-signed.hap"
  [[ -f "$unsigned" || -f "$signed" ]] || die "未找到 hap：$out_dir"
  if [[ -f "$unsigned" ]] && sign_hap "$unsigned" "$signed"; then
    hap="$signed"
  elif [[ -f "$signed" ]]; then
    hap="$signed"   # hvigor 按 build-profile.json5 的 signingConfigs 签好了
  else
    hap="$unsigned"
    log "警告: 未签名（scripts/build/build.local.sh 未配置 OHOS_P12 / OHOS_CERT / OHOS_PROFILE），只能用于检查，真机和蒲公英装不上"
  fi
  local suffix=""
  [[ "$hap" == "$unsigned" ]] && suffix="-unsigned"
  ARTIFACT="$DIST_DIR/sondo-ohos-$(ohos_version_name)-$API_ENV-$MODE-$(timestamp)$suffix.hap"
  cp -f "$hap" "$ARTIFACT"
  BUILD_TYPE=hap
}

ARTIFACT="" BUILD_TYPE=""
"build_$PLATFORM"
logTitle "打包完成"
log "产物: $ARTIFACT"

########################################
# 3. 上传蒲公英
########################################
if [[ "$UPLOAD" != true ]]; then
  exit 0
fi
if [[ "$ARTIFACT" == *-unsigned.hap ]]; then
  die "未签名的 hap 不能上传蒲公英：先在 scripts/build/build.local.sh 配置鸿蒙签名材料"
fi
pgyer_upload "$ARTIFACT" "$BUILD_TYPE" "$DESC"
if [[ "$PLATFORM" == ohos ]]; then
  pgyer_upload_harmony_cert
fi
logTitle "上传完成"
log "平台=$PLATFORM 环境=$API_ENV 产物=$ARTIFACT"
[[ -n "${PGYER_URL:-}" ]] && log "下载地址: $PGYER_URL"
exit 0
