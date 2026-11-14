#!/usr/bin/env bash
# Sondo 鸿蒙：把跨端模块 shared 编译成 libshared.so 并同步到 ohosApp。
#
#   ./ohos_build.sh              # Release 产物（默认）
#   ./ohos_build.sh debug        # Debug 产物（带完整调试信息，体积大）
#   ./ohos_build.sh assets       # 只同步资源（不编译）
#   ./ohos_build.sh release -PpageName=xxx   # 其余参数原样透传给 gradle
#
# 做三件事：
#   1. ./gradlew -c settings.ohos.gradle.kts :shared:linkShared{Release|Debug}SharedOhosArm64
#      （Kotlin 2.0.21-KBA-010 工具链；首次会下载 Kotlin/Native 鸿蒙工具链，体积较大）
#   2. libshared.so      → ohosApp/entry/libs/arm64-v8a/
#      libshared_api.h   → ohosApp/entry/src/main/cpp/thirdparty/biz_entry/
#   3. shared/src/commonMain/assets/common/ → ohosApp/entry/src/main/resources/resfile/common/
#      （Kuikly 的 assets://common/... 在鸿蒙上映射到 hap 的 resfile 目录）
#
# 之后用 DevEco Studio 打开 ohosApp 运行 / 打包，详见 ohosApp/README.md。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

OHOS_APP_DIR="$ROOT_DIR/ohosApp"
SO_DEST_DIR="$OHOS_APP_DIR/entry/libs/arm64-v8a"
HEADER_DEST_DIR="$OHOS_APP_DIR/entry/src/main/cpp/thirdparty/biz_entry"
ASSETS_SRC_DIR="$ROOT_DIR/shared/src/commonMain/assets/common"
ASSETS_DEST_DIR="$OHOS_APP_DIR/entry/src/main/resources/resfile/common"

MODE="release"
if [[ $# -gt 0 ]]; then
  case "$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" in
    release|debug|assets)
      MODE="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
      shift
      ;;
    -*)
      ;; # 直接是 gradle 参数，保持默认 release
    *)
      echo "未知模式: $1（可选 release | debug | assets）" >&2
      exit 2
      ;;
  esac
fi

sync_assets() {
  if [[ ! -d "$ASSETS_SRC_DIR" ]]; then
    echo "[ohos_build] 未找到资源目录 $ASSETS_SRC_DIR，跳过资源同步"
    return 0
  fi
  mkdir -p "$ASSETS_DEST_DIR"
  if command -v rsync >/dev/null 2>&1; then
    rsync -a --delete --exclude '.DS_Store' "$ASSETS_SRC_DIR/" "$ASSETS_DEST_DIR/"
  else
    rm -rf "$ASSETS_DEST_DIR"
    mkdir -p "$ASSETS_DEST_DIR"
    cp -R "$ASSETS_SRC_DIR/." "$ASSETS_DEST_DIR/"
  fi
  echo "[ohos_build] assets  : $ASSETS_SRC_DIR -> $ASSETS_DEST_DIR"
}

if [[ "$MODE" == "assets" ]]; then
  sync_assets
  exit 0
fi

if [[ "$MODE" == "debug" ]]; then
  LINK_TASK=":shared:linkSharedDebugSharedOhosArm64"
  OUT_DIR="$ROOT_DIR/shared/build/bin/ohosArm64/sharedDebugShared"
else
  LINK_TASK=":shared:linkSharedReleaseSharedOhosArm64"
  OUT_DIR="$ROOT_DIR/shared/build/bin/ohosArm64/sharedReleaseShared"
fi

# 本机工具链约定装在 ~/dev-tools（见 CLAUDE.md）；已设置 JAVA_HOME 时不覆盖。
DEFAULT_JAVA_HOME="$HOME/dev-tools/jdk/jdk-17.0.21+9/Contents/Home"
if [[ -z "${JAVA_HOME:-}" && -d "$DEFAULT_JAVA_HOME" ]]; then
  export JAVA_HOME="$DEFAULT_JAVA_HOME"
fi

# Kotlin/Native 插件在 macOS 上会给所有链接任务挂一个 xcodeVersion 检查（鸿蒙目标也不例外），
# 没装完整 Xcode 时这一步直接失败。鸿蒙链接并不用这个值：没有 Xcode 就跳过检查并放一个占位版本文件。
EXTRA_ARGS=()
if ! /usr/bin/xcrun xcodebuild -version >/dev/null 2>&1; then
  mkdir -p "$ROOT_DIR/shared/build"
  echo "16.0" > "$ROOT_DIR/shared/build/xcode-version.txt"
  EXTRA_ARGS+=("-x" ":shared:xcodeVersion")
  echo "[ohos_build] 未检测到完整 Xcode，跳过 :shared:xcodeVersion"
fi

echo "[ohos_build] gradle  : $LINK_TASK $*"
./gradlew -c settings.ohos.gradle.kts "$LINK_TASK" --console=plain ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"} "$@"

SO_FILE="$OUT_DIR/libshared.so"
HEADER_FILE="$OUT_DIR/libshared_api.h"
if [[ ! -f "$SO_FILE" || ! -f "$HEADER_FILE" ]]; then
  echo "[ohos_build] 编译产物缺失：$SO_FILE / $HEADER_FILE" >&2
  exit 1
fi

mkdir -p "$SO_DEST_DIR" "$HEADER_DEST_DIR"
cp -f "$SO_FILE" "$SO_DEST_DIR/libshared.so"
cp -f "$HEADER_FILE" "$HEADER_DEST_DIR/libshared_api.h"
echo "[ohos_build] so      : $SO_FILE -> $SO_DEST_DIR"
echo "[ohos_build] header  : $HEADER_FILE -> $HEADER_DEST_DIR"

sync_assets
echo "[ohos_build] 完成。用 DevEco Studio 打开 $OHOS_APP_DIR 运行。"
