#!/usr/bin/env bash
# 打包脚本公共部分：日志、工具链环境、版本号读取。由 scripts/build/*.sh source，不单独执行。
# 调用方需先定义 ROOT_DIR（工程根目录）。

log() { echo "[$(date +'%H:%M:%S')] $*"; }
logTitle() { echo ""; echo "=================== $* ==================="; }
die() { echo "Error: $*" >&2; exit 1; }

# 本机密钥（蒲公英 Key、鸿蒙签名材料），gitignore，模板见 scripts/build/build.local.sh.example
load_local_config() {
  local f="$ROOT_DIR/scripts/build/build.local.sh"
  if [[ -f "$f" ]]; then
    # shellcheck disable=SC1090
    source "$f"
  fi
}

# JDK / Android SDK：本机装在 ~/dev-tools（见 CLAUDE.md），已设置的不覆盖。
setup_android_env() {
  local jdk="$HOME/dev-tools/jdk/jdk-17.0.21+9/Contents/Home"
  if [[ -z "${JAVA_HOME:-}" && -d "$jdk" ]]; then
    export JAVA_HOME="$jdk"
  fi
  if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/dev-tools/android-sdk" ]]; then
    export ANDROID_HOME="$HOME/dev-tools/android-sdk"
  fi
  if [[ -n "${JAVA_HOME:-}" ]]; then
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
  # apksigner / zipalign：取最新的 build-tools
  if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/build-tools" ]]; then
    local bt
    bt="$(ls -1 "$ANDROID_HOME/build-tools" | sort -V | tail -n 1)"
    [[ -n "$bt" ]] && export PATH="$ANDROID_HOME/build-tools/$bt:$PATH"
  fi
  if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/platform-tools" ]]; then
    export PATH="$ANDROID_HOME/platform-tools:$PATH"
  fi
}

# DevEco Studio 自带的鸿蒙工具链（hvigor / ohpm / node / jbr），同 ohosApp/README.md「命令行构建」。
setup_ohos_env() {
  local d="${DEVECO_HOME:-/Applications/DevEco-Studio.app/Contents}"
  [[ -d "$d" ]] || die "未找到 DevEco Studio：$d（可用 DEVECO_HOME 指定 .app/Contents 目录）"
  export DEVECO_SDK_HOME="${DEVECO_SDK_HOME:-$d/sdk}"
  export NODE_HOME="${NODE_HOME:-$d/tools/node}"
  export OHOS_SDK_HOME="${OHOS_SDK_HOME:-$d/sdk/default/openharmony}"
  # hvigor 的 PackageHap 需要 Java；ohos_build.sh 的 Gradle 需要 JDK 17，DevEco 的 jbr 是 21，两者分开：
  # 这里只设置 DEVECO_JAVA_HOME，执行 hvigor 时再切换。
  export DEVECO_JAVA_HOME="$d/jbr/Contents/Home"
  export PATH="$d/tools/node/bin:$d/tools/ohpm/bin:$d/tools/hvigor/bin:$PATH"
  HAP_SIGN_TOOL="$OHOS_SDK_HOME/toolchains/lib/hap-sign-tool.jar"
}

# 版本号
android_version_name() { grep -E '^APP_VERSION_NAME_ANDROID=' "$ROOT_DIR/gradle.properties" | cut -d= -f2 | tr -d '[:space:]'; }
android_version_code() { grep -E '^APP_VERSION_CODE_ANDROID=' "$ROOT_DIR/gradle.properties" | cut -d= -f2 | tr -d '[:space:]'; }
ios_version_name() {
  grep -m1 'MARKETING_VERSION' "$ROOT_DIR/iosApp/iosApp.xcodeproj/project.pbxproj" | sed -E 's/.*= *([^;]+);.*/\1/' | tr -d '[:space:]'
}
ohos_version_name() {
  grep -m1 '"versionName"' "$ROOT_DIR/ohosApp/AppScope/app.json5" | sed -E 's/.*: *"([^"]+)".*/\1/'
}

# 产物归档目录
DIST_DIR="${DIST_DIR:-$ROOT_DIR/dist}"
timestamp() { date +'%Y%m%d%H%M'; }
