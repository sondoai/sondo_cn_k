# Sondo 鸿蒙宿主（ohosApp）

Kuikly 页面的鸿蒙（HarmonyOS NEXT / ArkTS）承载工程。业务 UI 与逻辑全部在跨端模块 `shared`，
这里只有：Kuikly 容器、页面栈、原生模块（`SD*Module`）、原生视图、适配器。

> **状态**：已用 DevEco Studio 自带的 hvigor（命令行，SDK HarmonyOS 26.0.0 / API 26，`compatibleSdkVersion` 12）
> 跑通 `assembleHap`（debug 与 release 都 BUILD SUCCESSFUL，含 `CompileArkTS` 的类型检查 + ArkTS 严格规则、
> `BuildNativeWithCmake/Ninja`、资源编译、打包）。其中 `libshared.so` / `libshared_api.h` 用的是**占位桩**，
> 真实产物的链接还没验证；**从未在设备上运行**。未验证项见文末清单。

## 构建步骤

前置：DevEco Studio 5.0.x 及以上（HarmonyOS SDK API 12+）、JDK 17、真机或 **arm64** 模拟器
（`libshared.so` 只产出 `arm64-v8a`，x86_64 模拟器不可用）。

1. **编译跨端产物**（工程根目录，不是 `ohosApp/`）：

   ```bash
   ./ohos_build.sh            # Release（默认）
   ./ohos_build.sh debug      # Debug
   ./ohos_build.sh assets     # 只同步图片等资源，不编译
   ```

   脚本做三件事：
   - `./gradlew -c settings.ohos.gradle.kts :shared:linkShared{Release|Debug}SharedOhosArm64`
     （Kotlin `2.0.21-KBA-010` 工具链 + Kuikly `-ohos` 产物；首次会下载 Kotlin/Native 鸿蒙工具链，体积较大。
     若提示找不到 OHOS SDK，设置环境变量 `OHOS_SDK_HOME` 指向 DevEco 的 `sdk/default/openharmony`）；
   - `libshared.so` → `entry/libs/arm64-v8a/`，`libshared_api.h` → `entry/src/main/cpp/thirdparty/biz_entry/`；
   - `shared/src/commonMain/assets/common/` → `entry/src/main/resources/resfile/common/`。

   这三类产物都被 `.gitignore` 忽略，**每次改了 `shared` 的代码或资源都要重新执行**。

2. **用 DevEco Studio 打开 `ohosApp/`**，等待 `ohpm install` 完成（或在 `ohosApp/entry` 下手动执行）。
   依赖在 `entry/oh-package.json5`，其中 `@kuikly-open/render` 的版本必须与根目录
   `gradle.properties` 的 `KUIKLY_VERSION` 一致。

3. **签名**：File > Project Structure > Signing Configs，登录华为开发者账号并勾选
   *Automatically generate signature*（IDE 会把签名材料写进 `build-profile.json5` 的 `signingConfigs`，
   不要提交个人证书路径）。发布包使用 AGC 上申请的发布证书与 Profile。

4. 选择 `entry` 运行。

### 环境开关

| 开关 | 位置 | 说明 |
|---|---|---|
| 接口环境 `API_ENV`（local / test / prod） | `entry/build-profile.json5` → `buildOption.arkOptions.buildProfileFields` | 注入 `pageData.apiEnv` |
| 测试入口 `SHOW_TOKEN_EDITOR` | 同上 | 注入 `pageData.showTokenEditor` |
| Debug | DevEco 的 buildMode（debug / release） | 注入 `pageData.debug` |
| 版本号 | `AppScope/app.json5` 的 `versionName` / `versionCode` | 注入 `pageData.appVersionName / appVersionCode` |

读取位置：`entry/src/main/ets/kuikly/AppConfig.ets`；注入位置：`kuikly/container/SDNavigator.ets` 的 `createEntry`。

## 命令行构建 / 重新跑 ArkTS 检查

不开 IDE，直接用 DevEco Studio 自带的工具链（路径按默认安装位置）：

```bash
D=/Applications/DevEco-Studio.app/Contents
export DEVECO_SDK_HOME=$D/sdk JAVA_HOME=$D/jbr/Contents/Home NODE_HOME=$D/tools/node
export PATH=$JAVA_HOME/bin:$D/tools/node/bin:$D/tools/ohpm/bin:$D/tools/hvigor/bin:$PATH
cd ohosApp
ohpm install --all                      # 依赖装到 oh_modules / entry/oh_modules（已 gitignore）
hvigorw --mode module -p module=entry@default -p product=default -p buildMode=debug \
        assembleHap --analyze=normal --parallel --incremental --no-daemon
# 产物：entry/build/default/outputs/default/entry-default-unsigned.hap
```

- `CompileArkTS` 就是 DevEco 的 ArkTS 编译器（类型检查 + ArkTS linter 严格规则），有 `ArkTS:ERROR` 即失败；
  `ArkTS:WARN`（「Function may throw exceptions」「system capacity … not supported on all devices」）是提示，不影响构建。
- `PackageHap` 需要 Java：用 DevEco 自带的 `jbr`（上面的 `JAVA_HOME`），否则报 `Unable to locate a Java Runtime`。
- CMake 需要 `entry/libs/arm64-v8a/libshared.so` 与 `entry/src/main/cpp/thirdparty/biz_entry/libshared_api.h`
  （`../ohos_build.sh` 产出）。还没有这两个文件时，可以在工程副本里放占位桩先检查 ArkTS 与 C++：
  维护者机器上的 `scratchpad/ohos/build_check.sh` 就是这么做的（rsync 到副本 → 缺产物时注入桩 → 同上的 hvigor 命令；
  `--inplace` 直接构建本目录，`BUILD_MODE=release` 构建 release）。
- 签名：`assembleHap` 产出 unsigned HAP，不需要签名材料；装机前按「构建步骤 3」配置签名。

## 目录

```
ohosApp/
├── AppScope/                         bundleName com.sondo.music.video.ai、应用名、图标
├── build-profile.json5               compatibleSdkVersion 5.0.0(12)、useNormalizedOHMUrl（字节码 HAR 需要）
└── entry/
    ├── oh-package.json5              @kuikly-open/render + 各 SDK
    ├── build-profile.json5           C++ 构建、abiFilters、buildProfileFields
    ├── libs/arm64-v8a/libshared.so   ← ohos_build.sh
    └── src/main/
        ├── module.json5              权限、深链 skills、querySchemes
        ├── cpp/                      libkuikly_entry.so：NAPI initKuikly + C 侧日志 / 字体适配器
        ├── resources/rawfile/fonts/  自定义字体
        ├── resources/resfile/common/ ← ohos_build.sh（Kuikly 的 assets://common/…）
        └── ets/
            ├── entryability/EntryAbility.ets   窗口沉浸式、适配器注册、生命周期 → AppEvents
            ├── pages/Index.ets                 唯一的 ArkUI 页面：Navigation
            └── kuikly/
                ├── AppConfig.ets               宿主配置（环境开关、版本）
                ├── MyNativeManager.ets         initKuikly 入口
                ├── KuiklyViewDelegate.ets      模块 / 视图注册表（core / media / sdk / views 分区）
                ├── container/                  SDNavigator（页面栈）+ KuiklyPage（NavDestination 容器）
                ├── adapters/                   路由、日志、字体
                ├── support/                    SDModuleSupport（参数 / 回参 / 广播 / 宿主环境）、AppEvents
                ├── modules/core/               SDHttp / SDNav / SDApp / SDPermission / SDFile / SDCrypto
                ├── modules/media/、modules/sdk/  媒体与三方 SDK 模块
                └── views/                      原生视图
```

## 工作方式

### 容器与页面栈

- `pages/Index` 里只有一个 `Navigation(SDNavigator.shared().pathStack)`。**每个 Kuikly 页面是一个
  `NavDestination`**（`KuiklyPage`），根页面 `launch` 也是，永远在栈底。
- 每个页面的路由名唯一（`page_<id>`），页面信息（pageName、pageData、转场）保存在 `SDNavigator` 的
  `SDPageEntry` 里。所有栈变更都经过 `SDNavigator`，系统返回手势也先被 `KuiklyPage` 拦截再回到它，
  所以它的记录与 `NavPathStack` 始终一致，`SDNavModule.stack / popTo / popToRoot / replace / resetRoot`
  都基于这份记录。
- `RouterModule.openPage` → `SDNavigator.push`；`RouterModule.closePage` → `SDNavigator.closeFromRouter`。
  Kuikly 的路由适配器接口不带「谁调用的」，这里取「自顶向下第一个容器已创建的页面」
  （刚 push、容器还没创建的页面不可能发起调用，所以 `nav.push(X); nav.pop()` 关的是发起页）。
  `SDNavModule` 的方法通过模块所属的 controller 精确定位调用页。
- 转场（`pageData.sdTransition` / `sdTranslucent`）：

  | 取值 | 实现 |
  |---|---|
  | 默认 | `STANDARD` 模式 + 系统转场 |
  | `none` | `STANDARD` 模式，无动画 |
  | `bottom` | `DIALOG` 模式（上一页保持可见），内容自底向上滑入，关闭时滑出后再出栈 |
  | `fade` | `DIALOG` 模式，淡入 / 淡出 |
  | `sdTranslucent=1` | `DIALOG` 模式 + 透明背景 |

- 页面可见性（`pageDidAppear / pageDidDisappear`）= 应用在前台 且 NavDestination 显示中 且 位于栈顶。
- 返回键 / 侧滑：`NavDestination.onBackPressed` 始终拦截 → `controller.onBackPress()`（Kuikly 的
  BackPressHandler）→ 未消费且 `setSwipeBackEnabled` 未禁用时关闭页面；根页面则 `moveAbilityToBackground`。
- 注入 pageData：`debug`、`apiEnv`、`showTokenEditor`、`appVersionName`、`appVersionCode`。

### 模块约定

所有 `SD*Module` 继承 `KuiklyRenderBaseModule`，`call(method, params, callback)` 里：
入参用 `SD.parse(params)` 解析（唯一的 JSON 字符串或 null），异步回参 `{ok, error?, …}`，
同步回参是字符串（`"1"/"0"` 或 JSON 字符串），常驻回调反复回 `{event: …}`。
Kuikly 鸿蒙端的 ArkTS 模块**全部在主线程执行**（同步调用时 Kuikly 线程阻塞等待），
有同步方法的模块 `syncMode()` 返回 true（SDNav / SDApp / SDCrypto），其余 false。

新增模块：`KuiklyViewDelegate.ets` 对应分区加 import + `map.set`；需要依赖 / 权限时改
`entry/oh-package.json5`、`entry/src/main/module.json5`。需要 Ability 生命周期或 Want 时订阅
`AppEvents`（`CREATE / NEW_WANT / FOREGROUND / BACKGROUND / ACTIVE / INACTIVE / DESTROY`），不要改 `EntryAbility`。

### 图片与资源

Kuikly 的 `ImageUri.commonAssets("images/x.png")` 到渲染层是 `assets://common/images/x.png`，
鸿蒙渲染器内置把它映射到 `file://<context.resourceDir>/common/images/x.png`，也就是 hap 的
`resfile/common/`（`ohos_build.sh` 负责同步）。http(s) / `file://` / base64 也由渲染器内置处理，
所以没有注册自定义图片适配器。`SDFileModule.copyAsset` 同样从 `resourceDir/common/` 取文件。

### 字体

字体文件在 `resources/rawfile/fonts/`（拷自参考工程 `assets/fonts/`）。family 名与参考工程一致：
`Poppins`、`Montserrat`、`Anton`、`Bangers`、`LemonMilk`、`MomcakeBold`、`MomcakeThin`、
`MonotonRegular`、`MontserratBlack`、`MontserratBlackItalic`、`Roboto`、`Carter`（另有 `PaytoneOne`）。
鸿蒙一个 family 名只能绑定一个字体文件，所以多字重 family 额外提供显式字面名：
`Poppins-Thin / -Light / -Regular / -Medium / -SemiBold / -Bold`、`Montserrat-Medium / -SemiBold / -Bold`；
不带后缀的 `Poppins` = Regular，`Montserrat` = Medium（参考工程最轻的字重）。

注册两处、表必须一致：`cpp/napi_init.cpp` 的 `FontTable()`（Kuikly 文本，`KRRegisterFontAdapter`）
与 `kuikly/adapters/AppFonts.ets`（ArkUI 原生组件，`UIContext.getFont().registerFont`）。

### 权限

`module.json5` 已声明：`INTERNET`、`GET_NETWORK_INFO`、`VIBRATE`（触感）、`CAMERA`、`MICROPHONE`。
相册读写走系统 Picker / 保存控件，不需要权限（`SDPermissionModule` 对 photos / photosAddOnly / storage /
tracking 恒返回 granted）。如果产品只用系统相机 Picker，可以删掉 `CAMERA` 声明，此时模块对 camera 返回 granted。

## 未验证清单

没有任何一项在设备上跑过。按风险从高到低：

**构建**
- [x] ArkTS 编译器 / hvigor / CMake：命令行 `assembleHap` debug、release 均通过（真实 ohpm 依赖；`libshared` 为占位桩）。
- [ ] 用真实 `libshared.so` + `libshared_api.h` 链接 `libkuikly_entry.so`（入口符号 `libshared_symbols()->kotlin.root.initKuikly`
      是否与 KSP 生成的头文件一致，要等真实头文件出来才能确认）。
- [ ] `./ohos_build.sh` 的链接任务没有跑过（只验证了 Gradle 配置能解析：`linkSharedReleaseSharedOhosArm64` /
      `linkSharedDebugSharedOhosArm64` / `kspKotlinOhosArm64` 任务存在，`core` / `core-annotations`
      的 `2.30.0-2.0.21-ohos` 能解析）。`shared` 的代码是否能通过 KBA 工具链编译未知。
- [x] `@kuikly-open/render@2.30.0` 能从 ohpm 拉取，ArkTS 侧 API 与 C++ 头文件 `Kuikly/Kuikly.h` 编译通过。
- [x] `CMakeLists.txt` 经 `PACKAGE_FIND_FILE` + `find_package(render)` 拿到 `render::kuikly`，配置与链接通过。
- [x] `import BuildProfile from 'BuildProfile'` 与 `buildProfileFields` 自定义字段（`AppConfig.ets`）编译通过。
- [ ] `napi_init.cpp` 假定入口符号是 `libshared_symbols()->kotlin.root.initKuikly`（KSP 生成在根包）。
- [ ] 支付宝 SDK 钉死在 `15.8.37`（15.8.38 起要求 API 13）；若把 `compatibleSdkVersion` 提到 `5.0.1(13)` 可升级。
- [ ] `build-profile.json5` 没写 `targetSdkVersion`（hvigor 提示建议配置；默认取编译 SDK 的版本）。

**容器 / 导航**
- [ ] 以 `NavDestination` 承载 Kuikly（demo 用的是 `@Entry` 页面 + router）：首帧尺寸、safeArea、
      键盘避让（`expandSafeArea([SYSTEM, KEYBOARD])`）是否与 demo 表现一致。
- [ ] `DIALOG` 模式页面的自绘转场（`animateTo` 改 `translate` / `opacity`）与退场后出栈的时序。
- [ ] `closePage` 的调用方推断、`replace` / `resetRoot` 在同一帧内连续改栈的表现。
- [ ] `NavDestination.onShown / onHidden` 在应用前后台切换时是否触发（已另外用 `AppEvents` 兜底）。
- [ ] 根页面未消费返回时 `moveAbilityToBackground`（SDK 说明：平板要 API 13 起才支持，API 12 的平板上返回 16000061，
      代码只打日志，表现为根页面按返回无反应）。
- [ ] `EntryAbility` 里的 `setWindowBackgroundColor`：编译器按 SDK 设备数据给了「unavailable to phone, tablet」提示
      （`string | ColorMetrics` 新签名的设备表），已 try/catch；真机上确认背景色是否生效。

**原生模块**
- [ ] `SDHttpModule`（`@kit.NetworkKit` http）：PUT 发送 `ArrayBuffer` 时 `Content-Length` 精确且不分块；
      `dataSendProgress` 进度；`requestInStream` 的 `dataReceive` / `dataEnd` 与 Promise 的先后顺序；
      `destroy()` 取消后的回调行为（模块自己保证先回 `errorType=cancel`）；错误码到 errorType 的映射。
      已知限制：不支持 PATCH；只有 POST / PUT 能带请求体（DELETE 的 body 会被丢弃，目前业务未使用）；
      非 multipart 上传会把请求体读入内存（大文件走分片即可）。
- [ ] `SDCryptoModule`：`cryptoFramework` 同步接口（`convertKeySync` / `initSync` / `doFinalSync`）、
      `RSA<bits>|PKCS1` 与 `AES256|CBC|PKCS7` 的输出是否与参考工程（encrypt 包）可互通——需要用服务端解密验证。
- [ ] `SDPermissionModule` 的 `permanentlyDenied` 判定：系统权限靠 `dialogShownResults`，
      通知靠「申请是否立即返回」的时间启发式（350ms）。
- [ ] `SDAppModule`：分享面板只能感知「面板关闭」，无法区分分享成功与取消，目前关闭即回 `success`；
      `openUrl` 的 `inApp` 模式等同 external（鸿蒙没有系统级应用内浏览器）；
      应用设置 / 通知设置 / 应用市场详情页的 Want 参数；`canOpenUrl` 依赖 `module.json5` 的 `querySchemes`；
      `requestReview` 是打开应用市场详情页而不是应用内评分面板；网络类型与键盘高度事件。
- [ ] 字体：`rawfile:` 路径经 `KRRegisterFontAdapter` 加载；一个 family 一个文件的假设。

**发布相关**
- [ ] `https://go.sondo.vip/link` 的 App Linking 需要在 AGC 开通并在域名下部署 `applinking.json`；
      `sondo://` Deep Link 只需 `module.json5` 的 skills（已配置）。
- [ ] 图标用的是参考工程 iOS 的 1024 图标（前景）+ 纯黑背景的分层图标，上架前按应用市场规范重新出图。
- [ ] `versionName 1.0.0 / versionCode 1000000` 是占位，参考工程没有鸿蒙版本号。
- [ ] `Request-Source` 请求头在鸿蒙上的取值（shared 侧暂按 Android 的 `2`）需要后端确认。
