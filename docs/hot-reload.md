# Kotlin 源码开发热重载

保存 `shared/src/commonMain/kotlin` 中的 Kotlin 后，开发服务自动编译并发布新产物，已启用开发加载器的 Android、iOS、鸿蒙容器自动重建 Kuikly 页面。应用进程不重启；页面和业务单例的内存状态会重置，原生路由与页面参数保留。

这套入口使用项目当前的 Kuikly 2.30.0，不需要动态发布服务。三端加载器默认关闭，发布构建继续使用原有 Native 模式。

## 日常使用

```bash
cd /Users/kj/work/kuikly/sondo_cn_k
./dev.sh all
```

只开发一个平台可用 `./dev.sh android`、`./dev.sh ios`、`./dev.sh ohos`。保持终端和调试 App 运行，保存 Kotlin 即可。输入 `r` 回车重新编译，`q` 回车停止；Ctrl+C 也可退出。

服务默认端口 `8088`，终端会打印本机的局域网地址。Android USB 调试使用 `http://127.0.0.1:8088`，服务会建立 `adb reverse`；iOS 模拟器也可用此地址，iOS/鸿蒙真机使用可连接开发机的局域网地址。

首次启动会先编译。构建中或编译失败时保持上一次成功版本，错误详情位于 `.devtools/hot-reload/logs/`。修复 Kotlin 后再次保存会自动重试。需要先保留已有产物、只启动监听服务时用 `./dev.sh all --no-build`。

## Android 宿主首次启用

```bash
./gradlew :androidApp:installDebug \
  -Psondo.hotReloadUrl=http://127.0.0.1:8088
adb reverse tcp:8088 tcp:8088
adb shell am start -n com.sondo.music.video.ai/.kuikly.KuiklyLaunchActivity
```

这次安装之后，修改 shared Kotlin 不再安装 APK。服务只编译 shared 业务类并用 D8 生成 DEX，Debug 容器校验内容 SHA-256 后使用独立加载器更新业务代码，再按根页面到当前页面的顺序重建 Kuikly 容器。

查看热重载记录：

```bash
adb logcat -s SondoHotReload:I
```

日志的 `applied <hash> in process <pid>` 表示已切换版本。连续保存时 PID 保持不变。新增或修改 Android 宿主代码需要重新执行首次安装命令；不传 `sondo.hotReloadUrl` 的普通 Debug 包不会轮询服务，Release 始终关闭。

## iOS 宿主首次启用

先按 `iosApp/README.md` 安装 CocoaPods 依赖，并用 Xcode 打开 `iosApp/iosApp.xcworkspace`。Debug Scheme 的 Run → Arguments → Environment Variables 中加入：

```text
SONDO_HOT_RELOAD_URL = http://127.0.0.1:8088
```

真机把 `127.0.0.1` 换成终端打印的开发机局域网 IP。也可在构建时设置 `SD_HOT_RELOAD_URL`，通过 `Info.plist` 传入：

```bash
xcodebuild -workspace iosApp/iosApp.xcworkspace -scheme iosApp \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  SD_HOT_RELOAD_URL=http://127.0.0.1:8088 build
```

启动 Debug App 后保持运行。iOS 使用系统 JavaScriptCore 执行由同一份 Kotlin 代码编译的 JS bundle，每个版本共享运行时以支持 AppHost、UserStore 等跨页面对象；新 bundle 通过校验后只重建 Kuikly 页面。加载器和轮询代码通过 `#if DEBUG` 排除于 Release。

## 鸿蒙宿主首次启用

鸿蒙提供专用的 `hotReload` 开发产品，构建步骤见 `ohosApp/README.md` 的热重载部分。在工程根目录执行：

```bash
SONDO_HOT_RELOAD_URL=http://开发机局域网IP:8088 \
  bash scripts/hot/ohos-hot-reload-build.sh
```

脚本仅为本次 Debug 构建注入开关与地址。安装运行生成的 `ohosApp/entry/build/hotReload/outputs/default/entry-default-signed.hap` 即可。开发加载器通过官方 Kuikly C ABI 接入系统 JSVM，共享每个版本的 JS 运行时。保存 Kotlin 后下载新的不可变 bundle，只重建 Kuikly 页面，应用进程保持运行。该开发产品不需要先编译 `libshared.so`；正常 Native 产品仍使用原有 `./ohos_build.sh` 流程。

## 调试面板和验证

`kuikly-devtools` 面板为可选项。希望在热重载时同时查看插桩页面，可运行：

```bash
./dev.sh all --inspect
```

调试面板地址为 `http://localhost:8090`。源码监听、产物服务和页面重载仍使用 `8088`。

```bash
./dev.sh --check
node --test scripts/hot/hot-reload.test.mjs
./dev.sh ios --once
bash iosApp/tests/check-hot-reload-runtime.sh
```

`--once` 只构建并保存一次产物，不启动监听服务。运行中的开发服务与额外的手动构建应串行进行，避免 Kotlin/KSP 缓存并发竞争。

## 覆盖范围

- 热重载覆盖 `commonMain` 中的 Kotlin UI 与业务逻辑，以及开发专用的 `jsMain` 入口；当前不会保留页面内存状态。
- 新增原生 SDK、原生 Module/View 或宿主代码需要重新构建对应 APK/App/HAP。
- 资源文件随宿主打包；修改图片、字体等资源需要重新同步并构建宿主。
- Android 使用 JVM DEX，iOS/鸿蒙使用 Kotlin/JS；跨端逻辑仍应避免依赖某个 Native 平台独有的 API。
- 首次启用时必须至少有一次成功编译；服务暂不可达或后续编译失败时不会发布损坏产物。

## 实现依据

- [Kuikly Android 公共上下文接口](https://github.com/Tencent-TDS/KuiklyUI/blob/2.30.0/core-render-android/src/main/java/com/tencent/kuikly/core/render/android/context/KuiklyRenderCommonContextHandler.kt)
- [Kuikly iOS 执行模式接口](https://github.com/Tencent-TDS/KuiklyUI/blob/2.30.0/core-render-ios/Protocol/KuiklyRenderContextProtocol.h)
- [Kuikly 鸿蒙 C ABI](https://github.com/Tencent-TDS/KuiklyUI/blob/2.30.0/core/src/ohosArm64Main/ohosInterop/cinterop/ohos.def)

这些默认 Native 实现之外的扩展接口允许宿主增加开发加载器；它们与是否提供官方动态发布工具链是两个不同的接入条件。
