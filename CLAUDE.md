# CLAUDE.md

Sondo（国内版）的 Kuikly 实现：AI 音乐 / 音乐视频（MV）生成 App，目标平台 Android、iOS、鸿蒙。
业务逻辑与 UI 全部写在跨端模块 `shared`（Kotlin Multiplatform + Kuikly DSL），三个宿主工程只负责承载容器和原生能力。

功能、接口、文案、视觉以 Flutter 参考工程 `../sondo_cn` 为准（下文称「参考工程」）。

## 常用命令

```bash
# 环境（本机工具链装在 ~/dev-tools；用 Android Studio 时不需要）
export JAVA_HOME=~/dev-tools/jdk/jdk-17.0.21+9/Contents/Home
export ANDROID_HOME=~/dev-tools/android-sdk

# 只编译跨端业务代码（最快的检查方式）
./gradlew :shared:compileDebugKotlinAndroid --console=plain -q

# Android 调试包 → androidApp/build/outputs/apk/debug/
./gradlew :androidApp:assembleDebug

# 切换接口环境 / 打开测试入口（对应 Flutter 的 --dart-define）
./gradlew :androidApp:assembleDebug -PAPI_ENV=test -PSHOW_TOKEN_EDITOR=true

# iOS：需要完整 Xcode + CocoaPods
cd iosApp && pod install && open iosApp.xcworkspace

# 鸿蒙：先用 KBA 工具链把 shared 编成 libshared.so（没有完整 Xcode 时脚本会自动跳过 xcodeVersion 检查），
# 再用 DevEco Studio 打开 ohosApp；也可命令行出未签名 HAP，环境与命令见 ohosApp/README.md「命令行构建」
./ohos_build.sh
```

没有完整 Xcode 的机器上，`shared/build.gradle.kts` 会自动跳过 iOS target（`-Psondo.enableIos=true` 可强制打开）。

### 打包 / 蒲公英 / 渠道包

```bash
# 三端打包 →（可选）上传蒲公英；不带参数为交互式，产物在 dist/
./scripts/build/build_app.sh -p android|ios|ohos -e prod|test|local -m release|debug [--token] --upload|--no-upload [-d 描述]

# Android 渠道包：先打母包，再（可选外部加固重签后）用 Walle 写渠道，清单 androidApp/channel_list
./scripts/build/build_channels.sh --build [--env prod]
./scripts/build/build_channels.sh --signed dist/channels/sondo-<版本>-prod-mother.apk [--channels official,huawei]
```

- 蒲公英 Key、鸿蒙签名材料放 `scripts/build/build.local.sh`（gitignore，模板 `scripts/build/build.local.sh.example`）。
- 鸿蒙：脚本依次跑 `ohos_build.sh` → `ohpm install` → `hvigorw assembleHap`，环境经 `-p API_ENV=…`
  由 `ohosApp/entry/hvigorfile.ts` 覆盖 `buildProfileFields`；签名用 SDK 的 `hap-sign-tool.jar` + 本地发布证书
  （不依赖 `build-profile.json5` 里别人机器的签名路径）。
- iOS：需完整 Xcode + CocoaPods，导出配置 `iosApp/ExportOptions-debug.plist`（debugging、自动签名）。

## 工程结构

```
shared/src/commonMain/kotlin/com/sondo/app/
├── base/          BasePager、Nav、Routes、Events、AppHost、AppEnv
├── core/
│   ├── constants/ AppConstants、ApiPaths、StorageKey、UIColor、AssetsImage
│   ├── http/      Api（请求 + 401 刷新）、ApiResult、AuthSession
│   ├── storage/   Sp（键值存储）
│   ├── i18n/      I18n、ZhCn（文案表，机器转换自参考工程）
│   └── utils/
├── nativeapi/     原生桥接模块 / 原生视图在 Kuikly 侧的声明（三端实现的唯一契约）
├── data/          models（JSON → Kotlin）、各业务 Api
├── service/       跨页面的应用级服务（UserStore、ConfigService、PlayerService …）
├── ui/            通用组件（overlay 浮层、导航栏、按钮、列表、弹窗 …）
└── features/      每个业务一个目录：页面（@Page）+ 页面内组件
shared/src/commonMain/assets/common/   图片、lottie、引导视频（三端共用）
androidApp/        Android 宿主：KuiklyRenderActivity + kuikly/module/* + kuikly/view/*
iosApp/            iOS 宿主
ohosApp/           鸿蒙宿主
```

## 架构要点

**一个页面 = 一个 Pager = 一个原生容器。** 页面用 `@Page("name")` 注册，名字在 `base/Routes.kt`；
`nav.push(Routes.x, args)` 打开，目标页从 `pagerData.params` 取参数，`nav.pop(result)` 返回结果。
根页面是 `launch`（主框架），它同时承载隐私同意 Gate 和应用级服务，栈底永远是它。

**页面基类 `BasePager`。** 子类实现 `content()`，不要重写 `body()`。基类在内容上叠加了全局浮层，
所以任何页面都能直接 `toast()` / `toastError()` / `showLoading()` / `showDialog { }` / `showBottomSheet { }`。

**响应式字段只属于页面。** `by observable(...)` / `by observableList()` 只能声明在 Pager、ComposeView，
或持有 `PagerScope` 的类里。全局单例（`UserStore`、`ConfigService` …）只放普通数据，
变化后 `emit(Events.X)`；页面在 `created()` 里 `on(Events.X) { 同步到自己的 observable }`。

**跨页面存活的工作跑在根页面上。** Module 回调、定时器、协程都绑定在发起它的页面上，页面一关回调就丢。
轮询、上传、后台合成这类工作用 `AppHost.launch { }` 启动，结果用事件广播。

**网络。** `Api.get/post/put/delete(path, …)` 是挂起函数，返回 `ApiResult<JSONObject>`（成功时是完整信封 `{code,msg,data}`）。
必须同时处理成功和失败。业务错误默认弹顶部提示，`ReqOptions(quiet = true)` 关闭。
新增接口：路径加到 `ApiPaths`，方法加到 `data/` 下对应的 `XxxApi`。

**原生能力。** 一律经 `nativeapi/` 里的模块，命名 `SD*Module`，入参一个 JSON、回参 `{ok, error?, …}`。
新增能力要改四处：`nativeapi/` 声明、`NativeModules.kt` 注册、三端各自实现并注册同名模块。

**文案 / 颜色 / 图片。** `"key".tr`、`"key".trParams("name" to x)`；`UIColor.*` 或 `c(0xAARRGGBB)`；
`AssetsImage.xxx.asset()`。Flutter 的逻辑像素与 Kuikly 的尺寸单位 1:1，数值照抄。

## Kuikly DSL 注意事项

- 布局是 Flexbox：默认纵向排列、`alignItems` 为 stretch。横排用 `flexDirectionRow()`。
- `attr { }` 里读到的响应式字段变化时，整个闭包会重跑 —— 里面只做属性设置，不要有副作用；
  也不要在 `body()` 里把响应式字段提前取成局部变量。
- `vfor` 的闭包必须且只能产生一个根节点；`vif` / `vfor` 不能直接作为 `vfor` 的子节点（包一层 `View`）。
- `Text` 默认黑色，深色页面上必须显式 `color(...)`；`Image` 必须有尺寸。
- 列表用 `List`（懒加载视图），需要 `flex(1f)` 或明确高度。长列表项里别放重逻辑。
- 闭包里统一用 `val ctx = this` 引用页面 / 组件，避免 `this` 指到视图上。
- 复用的 UI 封成 `ComposeView`（见 `ui/`），对外暴露 `fun ViewContainer<*, *>.Xxx(init: XxxView.() -> Unit)`。
- 官方文档：https://kuikly.tds.qq.com/ ；版本见 `gradle.properties` 的 `KUIKLY_VERSION`，三端渲染器版本必须一致。
- 根页面 `launch` 的属性初始化（构造阶段）里不能读 `Sp` / `UserStore` / `ConfigService` 等依赖原生模块的东西：
  此时页面还没登记为当前页面，会抛 `PagerNotFoundException`。先给默认值，在 `created()` 里再取。
- 鸿蒙动态库会把所有 `public` 声明导出成 C/C++ 接口：公开的枚举值、类成员不能叫 C++ 关键字
  （`delete`、`inline`、`template`、`default`、`new` …），否则 Android 能编过、鸿蒙链接失败。
- 属性 `isFoo` / `foo` 的 setter 与同名方法 `setFoo(...)` 在 JVM 上签名冲突（前端检查不报，生成字节码时才报）：
  给方法加 `@kotlin.jvm.JvmName("setFooFn")`。
- `PageList` 的 `pageDirection(true)` 是**横向**翻页，`false` 是竖向（容易写反）。
- 列表里判断「是不是第一项」用 `list.firstOrNull() === item`，不要用 `vforIndex` 的下标（增删后不刷新）。
- `Scroller` 放进横排（`flexDirectionRow()`）容器时必须给 `flex(1f)` 或宽度，否则会被压成 0 宽、整条不可见。
- Android 上 `Scroller` 里绝对定位的子视图改 `left` 不会生效（滑块会停在原位）：选中态改成每项自己铺底色。
- 鸿蒙渲染器对「`minWidth` + 左右 `padding`」的处理和 Kotlin 侧布局不一致，纯图标按钮用固定宽度。
- 多个进程同时编译时 KSP 增量缓存可能报 `Storage ... is already registered`：
  `./gradlew --stop`，删 `shared/build/kspCaches`，或加 `-Pksp.incremental=false`。

## 模拟器调试

本机有一台 Android 34 虚拟机 `sondo_test`（`~/dev-tools/android-sdk/emulator/emulator -avd sondo_test`）。
启动应用请用 `adb shell am start -n com.sondo.music.video.ai/.kuikly.KuiklyLaunchActivity`（monkey 启动在该镜像上不生效）。
演示页：`ui_kit_demo`、`works_demo`、`select_demo`、`video_demo`（可在任意页面 `nav.push("…")` 打开）。

## 环境与开关

- `gradle.properties`：`API_ENV`（local/test/prod）、`SHOW_TOKEN_EDITOR`、Android 版本号。
- 业务开关在 `AppConstants`（`enableMvDanceShare`、`enableWorkPublish`、`enableMemberPageEntry` 等），与参考工程保持一致。
- 隐私合规：友盟、极光、微信、深链、联网都必须在用户同意隐私政策之后才初始化（根页面的 Gate 负责）。
