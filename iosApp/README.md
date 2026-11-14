# iosApp — Sondo iOS 宿主

业务 UI 与逻辑全部在跨端模块 `shared`（Kotlin Multiplatform + Kuikly）。这个工程只负责：
承载 Kuikly 页面的原生容器、Kuikly 要求宿主实现的适配器、以及 `shared/.../nativeapi/` 约定的原生模块 / 原生视图。

## 开发阶段热重载

项目的 Debug 宿主可选用 JavaScriptCore 执行同一份 `shared` Kotlin 编译出的 JS，接收本机开发服务发布的新版本后，自动重建已打开的 Kuikly 页面。应用进程不重启；页面内存状态会重置。它使用公开的 Kuikly 2.30 执行环境协议实现，不依赖未开源的动态化 SDK。

```bash
# 在工程根目录运行：首次编译，然后监听 shared Kotlin 文件保存
./dev.sh ios
# 同时调试 Android / iOS / 鸿蒙时使用 ./dev.sh all
```

首次需要按下文安装 Pods 并用 Xcode 编译一次 Debug 宿主。在 Xcode 的 **Product → Scheme → Edit Scheme → Run → Arguments → Environment Variables** 添加：

```text
SONDO_HOT_RELOAD_URL=http://192.168.10.174:8088
```

把 IP 换成开发电脑当前的局域网 IP，手机与电脑需互通，首次连接时允许系统的「本地网络」权限。模拟器可使用 `http://127.0.0.1:8088`。也可通过构建参数 `SD_HOT_RELOAD_URL=http://电脑IP:8088` 写入 Info.plist；进程环境变量优先。

启动 App 后，修改 `shared/src` 下的 Kotlin 文件并保存，开发服务成功编译并发布 `iosVersion` 后，宿主会在约一秒的轮询间隔内下载 JS 并刷新页面。编译失败时继续运行上一次成功产物，控制台输出错误。初次尚无产物时等待服务就绪后重试。

未配置地址时 Debug 继续使用原有 `shared.framework`；Release 编译排除 JavaScriptCore 热重载执行器、网络轮询和重建代码。修改 Swift / Objective-C 原生模块、Pod 依赖或原生资源仍需用 Xcode 重新构建宿主。

已用实际编译出的完整 JS bundle 验证 JavaScriptCore 桥接、双页面共享单例环境、页面销毁、进程内切换新代码及错误产物和 SHA-256 不匹配拒绝；iOS 宿主改动已用 Apple iOS Simulator SDK 和 Kuikly 官方 2.30 头文件做 Debug / Release 类型检查。本机已完成 CocoaPods 集成、包含真实 `shared.framework` 和全部 SDK 的 Debug 完整构建，并在 iPhone 17 / iOS 26.2 模拟器完成端到端验收：保存 Kotlin 文案后，原生页面标题从「个人信息保护指引」自动变为测试文案，恢复源码后自动变回原文案，两次应用 PID 均保持 9585，未重装或重启。真机运行仍需签名与局域网权限验证。

可在 macOS 上复跑真实 JS 执行器检查：`bash iosApp/tests/check-hot-reload-runtime.sh`。它加载已编译的 `sondo.js`，用测试原生回调接收实际页面的渲染指令，不替代真机 UI 验证。

## 构建

环境：完整 Xcode 16+（工程用了文件夹同步分组，`objectVersion = 77`）、CocoaPods ≥ 1.16.2（xcodeproj ≥ 1.27，
更低版本读不了这种工程）、JDK 17。

```bash
# 0. JDK：Xcode 的脚本阶段会调用 ../gradlew，必须能找到 java。
#    本机 JDK 装在 ~/dev-tools 时，装一份系统级 JDK 17，或在 ~/.zshenv 里 export JAVA_HOME。
export JAVA_HOME=~/dev-tools/jdk/jdk-17.0.21+9/Contents/Home

# 1. 生成 shared.podspec 和占位 framework（shared/shared.podspec 是 Gradle 生成物，不要手写）
cd <repo>
./gradlew :shared:podspec :shared:generateDummyFramework

# 2. 安装 Pods
cd iosApp
pod install --repo-update

# 3. 打开 workspace（不是 xcodeproj），选 iosApp scheme 运行
open iosApp.xcworkspace
```

- 也可以用 `./gradlew :shared:podInstall` 一步完成 1、2（`shared/build.gradle.kts` 里配置了 `podfile`）。
- 之后每次 Xcode 编译，`shared` pod 的脚本阶段会自动执行 `:shared:syncFramework` 重新编译 Kotlin 代码。
- 没有完整 Xcode 的机器上 `shared` 会跳过 iOS target，此时步骤 1 需要加 `-Psondo.enableIos=true`（仍然需要 Xcode 才能真正编译）。
- Kuikly 渲染器版本取自根目录 `gradle.properties` 的 `KUIKLY_VERSION`（Podfile 自动读取），三端必须一致。

### 环境开关（对应 Android 的 `-PAPI_ENV` / `-PSHOW_TOKEN_EDITOR`）

Target 的 Build Settings 里有两个自定义项，经 Info.plist（`SDApiEnv` / `SDShowTokenEditor`）注入到每个页面的 pageData：

| Build Setting | 取值 | 默认 |
|---|---|---|
| `SD_API_ENV` | `local` / `test` / `prod` | `prod` |
| `SD_SHOW_TOKEN_EDITOR` | `YES` / `NO` | `NO` |

```bash
xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -configuration Debug \
  SD_API_ENV=test SD_SHOW_TOKEN_EDITOR=YES build
```

版本号：`MARKETING_VERSION = 1.0.0`、`CURRENT_PROJECT_VERSION = 100000`（与参考工程 pubspec 的 iOS 行一致）。
签名：Automatic，Team `XR9PF858VF`（参考工程是 Manual + `SONDO_AI_DEV`，按需改回）。

## 目录

```
iosApp/
├── Podfile                         三个分区：kuikly / media pods / sdk pods
├── iosApp.xcodeproj                手写工程；iosApp/ 整个目录是「文件夹同步分组」
└── iosApp/                         ← 放进来的 .swift/.m/.h/.xcassets/.storyboard/字体 自动进 Target，不用改工程文件
    ├── Info.plist                  （已从同步分组的资源里排除）
    ├── iosApp.entitlements         推送 / Associated Domains / App Attest
    ├── iosApp-Bridging-Header.h    分区：kuikly / app / media / sdk
    ├── LaunchScreen.storyboard, Assets.xcassets（AppIcon、启动图）
    ├── Fonts/                      参考工程 assets/fonts 的全部字体（Info.plist UIAppFonts 已登记）
    ├── App/                        AppDelegate、SceneDelegate、SDNavigationController、SDPageStack、
    │                               AppEvents（系统回调广播）、SDModuleSupport（模块公共工具）
    ├── Kuikly/                     KuiklyViewController（页面容器）、SDKuiklyAdapters（图片/字体/日志/路由适配器）、
    │                               SDKuiklySupport（ObjC 侧取参数 / 回调 / 所属控制器）
    ├── Modules/Core/               SDHttpModule、SDNavModule、SDAppModule、SDPermissionModule、SDFileModule、SDCryptoModule
    ├── Modules/Media/              音频播放、视频缓存、选图、媒体处理 …
    ├── Modules/SDK/                微信、极光、友盟、内购 …
    └── Views/                      原生视图（视频、Lottie、WebView …）
```

注意：`iosApp/iosApp/` 下不要放 README、脚本之类的文件——同步分组会把不认识的文件当资源拷进包里。

## 约定

**原生模块**：类名 = Kuikly 侧 `moduleName`，方法名 = `method` 字符串。

```swift
@objc(SDFooModule)
@objcMembers
final class SDFooModule: KRBaseModule {
    func doIt(_ args: NSDictionary) {                 // 异步：主线程进入
        let p = SD.params(args)                        // JSON 入参
        let done = SD.once(args)                       // 恰好回调一次
        SD.onIO { done.success(["value": p.sdString("key")]) }
    }
    func isReady(_ args: NSDictionary) -> NSString {   // 同步：Kuikly 线程进入，不能碰 UIKit、不能 main.sync
        return SD.bool(true)
    }
}
```

- 入参在 `args[KR_PARAM_KEY]`（JSON 字符串），回调在 `args[KR_CALLBACK_KEY]`（ObjC block）。
  Swift 里 `as? KuiklyRenderCallback` 取不出 block，统一用 `SD.callback(args)` / `SD.once(args)`。
- 常驻回调（`listen` / `addEventListener`）用 `EventBroadcaster`，模块 `deinit` 里 `remove(ownerId:)`。
- 模块所在页面的控制器：`sd_viewController`（主线程）；页面名：`sd_pageName`。
- 同步方法禁止 `DispatchQueue.main.sync`：首帧同步渲染、返回键事件期间主线程在等 Kuikly 线程，会死锁。

**系统回调**：SDK 模块不要改 AppDelegate，订阅 `AppEvents`（通知名与 userInfo 见 `App/AppEvents.swift` 顶部表格）。
冷启动参数在 `AppEvents.launchOptions` / `AppEvents.launchURL`。

**页面栈**：`RouterModule.openPage / closePage` 和 `SDNavModule` 都落到 `SDPageStack`。
pageData 里的 `sdTransition`（`bottom` / `fade` / `none`）决定转场，`sdTranslucent = 1` 的页面以
overFullScreen 模态盖在上一页之上（下面的页面会收到 pageDidDisappear，与 Android 透明 Activity 一致）。

**侧滑返回**：默认转场的页面用系统可交互侧滑，**不经过 Kuikly**。页面需要拦截返回（未保存提示、弹窗打开）时，
shared 侧要调用 `SDNavModule.setSwipeBackEnabled(false)`；此后边缘侧滑会改走 Kuikly 的 `BackPressHandler`。
自定义转场（bottom / fade / none）的页面边缘侧滑一律先问 Kuikly，未消费再按自己的转场关闭。

**资源**：`ImageUri.commonAssets("images/x.png")` → `assets://common/images/x.png` → 渲染器去掉前缀和扩展名后调用
`KuiklyContextParam urlForFileName:extension:` → 宿主的 `hr_customBundleUrlForFileName:extension:`
（`SDKuiklyAdapters.assetURL(relativePath:)`）在 App 包根目录找 `common/images/x.png`。
`shared.podspec` 的 `resources = ['src/commonMain/assets/**']` 由「[CP] Copy Pods Resources」把 `common/` 整个目录拷到包根。

**字体**：Kotlin 侧写 Flutter pubspec 里的族名 + `fontWeight` 即可（Poppins / Montserrat / Anton / Bangers / LemonMilk /
MomcakeBold / MomcakeThin / MonotonRegular / MontserratBlack / MontserratBlackItalic / Roboto / Carter），
也可以直接写字面名（`Poppins-SemiBold`）。映射表在 `SDKuiklyAdapters.m`。
渲染器自己会先 `UIFont fontWithName:@"Poppins"`（命中 Regular、忽略字重），所以宿主交换了 `+[KRConvertUtil UIFont:]`
的实现，在渲染器之前按「族名 + 字重」解析；升级 Kuikly 后如果字重失效，先看这里。

## 这台机器上验证过 / 没验证过的

宿主最初在没有 Xcode / CocoaPods 的环境中编写。2026-11-14 接入开发热重载时，已在本机完成首次 `pod install`、Kotlin/Native `syncFramework`、完整 iOS Debug 模拟器构建和启动。更早的单项检查记录：

- `plutil -lint` 通过：`project.pbxproj`、`Info.plist`、`iosApp.entitlements`；pbxproj 里没有悬空的对象 ID；`ruby -c Podfile` 通过。
- 用命令行工具自带的 Swift 6.3 / clang，以 Mac Catalyst（`arm64-apple-ios14.1-macabi`，Swift 5 模式）为目标做了类型检查：
  `App/`、`Modules/Core/` 的全部 Swift 和 `Kuikly/` 的全部 ObjC 零错误零警告。
  Kuikly 头文件用的是源码树里的真实头文件；SDWebImage 用的是按记忆手写的桩头文件。
- 在 macOS 上实际跑过：RSA / AES（用 openssl 解密比对）、HTTP 引擎（本地服务器：各方法、状态码透传、超时 / 断网 / 取消分类、
  整文件 / 分片 / multipart 上传的 Content-Length、下载原子落盘、Range 探测）、
  「ObjC NSInvocation → Swift 模块方法 → block 回调」这条调用链、字体文件的 PostScript 名。

首次完整构建后的验证清单：

- [x] `pod install` 已解析真实的 `OpenKuiklyIOSRender 2.30.0` 和其余 9 个 Pod，并成功构建。
- [x] CocoaPods 正常集成 `objectVersion = 77` 的工程，资源复制阶段构建通过；具体图片逐项显示仍需业务验收。
- [ ] `use_frameworks! :linkage => :static` 下各 SDK pod 与 `OpenKuiklyIOSRender` 的模块头能正常编译。
- [ ] 2.30.0 的渲染器头文件与本机源码树一致（重点：`onBackPressedWithCompletion:`、`hr_setImageWithUrl:imageParams:complete:`、
      `+[KRConvertUtil UIFont:]` 的入参仍是含 `fontFamily` / `fontSize` / `fontWeight` 的字典）。
- [ ] SDWebImage 的实际 API 与用到的几处一致（`loadImageWithURL:options:progress:completed:`、`storeImageToMemory:forKey:`、
      `sd_imageWithData:`、`+[SDImageCoderHelper decodedImageWithImage:]`）。
- [ ] 真机上的运行时行为全部没有验证：页面转场、透明页、侧滑返回、状态栏、键盘高度事件、深链冷启动补发、权限弹窗（尤其 ATT）、
      分享面板、应用内评分、首帧的状态栏高度 / 安全区是否正确。
- [ ] 签名：Team、Provisioning Profile 是否包含 Push、Associated Domains、App Attest。

## 与参考工程（Flutter）Info.plist 的差异

- 去掉：Flutter 专用键、`UIMainStoryboardFile`、AppsFlyer 遗留的 `NSAdvertisingAttributionReportEndpoint` / `SKAdNetworkItems`、
  没有用到的定位用途说明。
- `UIStatusBarHidden` 改为 `false`：Kuikly 在页面创建时读一次状态栏高度，启动时隐藏状态栏会读到兜底值。
- `UIViewControllerBasedStatusBarAppearance` 改为 `true`（状态栏样式由页面容器控制）；新增 `UIStatusBarStyle = LightContent`。
- 新增 `UIUserInterfaceStyle = Dark`（全站深色，键盘 / 系统弹层跟随深色）、`UIAppFonts`、`SDApiEnv` / `SDShowTokenEditor` / `SDAppStoreId`。
