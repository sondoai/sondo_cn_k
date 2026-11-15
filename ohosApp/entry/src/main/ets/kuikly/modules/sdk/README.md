# modules/sdk —— 支付与三方 SDK（鸿蒙）

契约在 `shared/src/commonMain/kotlin/com/sondo/app/nativeapi/SDBizModules.kt`，这里是它在鸿蒙端的实现。
Flutter 参考工程没有鸿蒙实现，本目录是新写的；**已用 hvigor 命令行编译通过，未经真机验证**。

## 先看这里：验证状态

所有适配文件已经对照 **ohpm 上实际拉到的包**（`entry/oh_modules/<包>/` 里的 `.d.ets` / `.d.ts`、包内 README，
极光另对照了官方《HarmonyOS SDK API》文档）逐条核对，并经 DevEco 自带 hvigor 的 `CompileArkTS`（类型检查 + ArkTS 严格规则）
编译通过。「已核对」= 调用签名与真实声明一致且编译通过；运行时行为仍未在真机验证。

| SDK | ohpm 依赖（实际安装版本） | 适配文件（唯一 import SDK 的地方） | 状态 | 核对时的修正 / 仍需真机确认的点 |
|---|---|---|---|---|
| 支付宝收银台 | `@cashier_alipay/cashiersdk` `15.8.37` | `pay/AlipayAdapter.ets` | 已核对 | `Pay.pay(order, showLoading?): Promise<Map<string,string>>` 与声明一致。**版本从 `^15.8.42` 改为钉死 `15.8.37`**：15.8.38 起包要求 API 13，hvigor 直接拒绝（`compatibleSdkVersion 12 cannot be lower than … 13`）。按包内 README 在 `querySchemes` 加了 `https`。未装支付宝时 SDK 用 router 打开自带 H5 收银台（未真机验证） |
| 微信 OpenSDK（仅授权） | `@tencent/wechat_open_sdk` `1.0.15` | `auth/WechatAdapter.ets` | 已核对 | 与 `WXAPIFactory` / `Base` / `SendAuth` 声明一致，无需修改（`errCode` 非空，`sendReq` 返回 `boolean \| Promise<boolean>`，`handleWant` 返回 `boolean`） |
| 极光推送 | `@jg/push` `1.4.2` | `push/JPushAdapter.ets` | 已核对 | `init()` 与 `setClickWant()` 实际返回 Promise —— **原代码没处理，异步失败会成为未捕获的 rejection**；现在由 `PushManager` `.catch` 打日志。`extras` 声明为 `Record<string, Object>`（`PushPayload` 本来就兼容）。官方文档：`onArrivedMessage` 只在极光通道触发；鸿蒙通道的「前台不展示」(`onUnShowMessage`) 需要额外的 `PushMessageAbility`（见下文「推送」），本工程未接 |
| 推送 Want 兜底解析 | — | `push/PushPayload.ets` 的 `fromWant` | **未核对** | 键名没有文档；`@jg/push` 字节码字符串表里出现 `JMessageExtra`、`_j_data_`、`msg_id / n_title / n_content / n_extras`，现在两个键都尝试。只在 SDK 没回调时才用，不命中就返回空 |
| 友盟统计 | `@umeng/common` `1.2.2`（`^1.1.12`）、`@umeng/analytics` `1.2.12` | `analytics/UmengAdapter.ets` | 已核对 | **修正：`onProfileSignIn(provider, puid)` 参数顺序与 Android 相反，原代码传反了**；`init()` 返回 Promise，现在 `.catch`。`preInit` 的 `UMConfig` 只有 `context` 必填（也有 `appKey? / channel?`，未使用，仍靠 `umconfig.json`）。**SDK 不导出 `onPageStart / onPageEnd`**，`pageStart / pageEnd` 保持空实现 |
| 极光一键登录 | 未加依赖（`@jg/verify`） | `auth/JVerifyAdapter.ets`（生效的是 `UnsupportedJVerifyAdapter`） | **降级：不支持** | `auth/JVerifySdkAdapter.ets.draft` 是按官网文档写的草稿，不参与编译，全部未核对 |

`@jg/push`、`@tencent/wechat_open_sdk`、`@cashier_alipay/cashiersdk` 钉死了版本；升级前对照 `entry/oh_modules/<包>/` 的声明复核对应的适配文件，
然后重新跑一遍命令行构建（见 `ohosApp/README.md`「命令行构建」）。

## 文件

```
SDPayModule.ets          支付宝；iap* 一律 {ok:false, available:false, error:"unsupported"}
SDAuthModule.ets         微信授权 + 一键登录编排（超时兜底、只回调一次）
SDPushModule.ets         → push/PushManager
SDAnalyticsModule.ets    友盟；init 之前的埋点直接丢弃
pay/AlipayAdapter.ets
auth/WechatAdapter.ets   auth/WechatManager.ets（应用级单例，广播 wechatAuthResp）
auth/JVerifyAdapter.ets  接口 + 「不支持」实现 + JVerifyProvider
auth/JVerifySdkAdapter.ets.draft   草稿，未编译
push/JPushAdapter.ets    push/PushManager.ets（应用级单例）   push/PushPayload.ets（extras 拍平、Want 兜底解析）
analytics/UmengAdapter.ets
```

模块实例是每个 Kuikly 页面一份，SDK 状态都放在应用级的 static 管理器里；事件用 `EventBroadcaster` 广播给所有页面的监听。
Want（微信回跳、通知点击）不改 `EntryAbility`，统一从 `support/AppEvents` 订阅。

## 行为要点

**隐私**：四个模块在各自的 `wechatRegister` / `setup` / `init` / `alipayPay` 被调用之前不调用任何 SDK 接口。
`PushManager` 在加载时就订阅了 `onNewWant`，但 setup 之前只是把 Want 留在内存里。
（SDK 包是静态 import 的，包内的顶层代码会随应用启动加载；这一点无法在适配层规避。）

**支付**：`alipayPay` 回 `{ok:true, resultStatus, result, memo}`；SDK 抛错或没给状态码回 `{ok:false, error}`；10 分钟没有任何结果回 `{ok:false, error:"timeout"}`。
权益只认后端轮询。自动续费签约不走本模块（Kuikly 侧 `SDAppModule.openUrl` 拉 `alipays://`）。

**微信**：`wechatAuth` 回 `{ok, launched}`，授权结果走事件 `{event:"wechatAuthResp", errCode, code?, state?}`。
`sendReq` 8 秒没返回时：应用已退到后台按 `launched:true`，仍在前台按 `{ok:false, launched:false, error:"timeout"}`。
`universalLink` 仅 iOS 需要，鸿蒙忽略。

**一键登录**：当前恒回「不支持」—— `jverifySetup → {ok:true, code:-1}`、`jverifyCheckEnable → {ok:true, enable:false}`、
`jverifyPreLogin → {ok:true, code:-1}`、`jverifyLoginAuth → {ok:true, action:"unsupported"}`，Kuikly 侧退回应用内登录弹层。

**推送**：
- `setup` 幂等；通知权限由应用自己申请（已调用 `setUserRequestNotificationPermission(ctx, true)`，SDK 不会弹框）。
- 冷启动点击：启动 Want 在 setup 之后交给极光，回调结果只通过 `getLaunchNotification` 给出（读取后清空），不再发 `notificationOpened`，避免重复跳转。`getLaunchNotification` 最多等 SDK 1.5 秒。
- 热启动点击：`notificationOpened`；没有任何页面在监听时暂存，等第一个 `addEventListener` 补发。
- 前台通知：鸿蒙没有 Android 的「前台不弹横幅」开关，由**下发参数** `foregroundShow` 决定。
  极光文档：消息走**鸿蒙系统通道**时，`onUnShowMessage` 只有在工程里额外建一个 UIAbility（如 `PushMessageAbility`），
  在其 `onCreate` 里 `pushService.receiveMessage('DEFAULT', this, data => JPushInterface.defaultMessageBackgroundData(data))`
  并按《HarmonyOS SDK 集成指南》「配置前台不展示功能」声明到 `module.json5` 之后才会触发；本工程**没有接**，所以目前前台的鸿蒙通道消息
  只会由系统展示横幅，不会转成 `notificationReceived`。只有 `onUnShowMessage`（系统没展示）会转成 `notificationReceived`；`onArrivedMessage` 默认不转发（`push/PushManager.ets` 顶部的常量 `EMIT_ARRIVED_AS_RECEIVED`）。
  要和 Android / iOS 一样「前台只弹应用内提示」，服务端给鸿蒙通道下发时要带 `foregroundShow=false`。
- `extras` 已拍平（兼容 JSON 字符串与 `cn.jpush.android.EXTRA` 嵌套）。
- `setBadge` 用系统的 `notificationManager.setBadgeNumber`，不依赖极光。

**统计**：`onEvent` 的属性值只留 String（截到 256）/ Number，Boolean 转 0/1，其余转 JSON 文本。
`onPageStart` / `onPageEnd` 当前是空实现（见上表）；页面路径由业务自己的 `page_view` 事件覆盖。

## 已改的工程配置

`entry/oh-package.json5`：加了上表 5 个依赖。

`entry/src/main/module.json5`：
- `querySchemes`: `alipays`、`https`（支付宝 SDK README 要求）、`weixin`、`wxopensdk`
- `metadata`: `client_id` = **占位值 `REPLACE_WITH_AGC_APP_CLIENT_ID`**（Push Kit 必填，见下）
- `EntryAbility` 的 home skill 的 `actions` 追加 `wxentity.action.open`
- 权限没有新增（`INTERNET`、`GET_NETWORK_INFO` 宿主已声明）

## 还需要宿主工程 / 开发者做的事

### 必须

1. **友盟配置文件**（不在本目录的改动范围内，需要新建）：`ohosApp/AppScope/resources/rawfile/umconfig.json`
   ```json
   { "appKey": "<AppConstants.umengOhosAppKey>", "channel": "<渠道，如 official>" }
   ```
   友盟鸿蒙 SDK 从这个文件读 AppKey，代码里传的 `appKey` 只用于核对（不一致会打 error 日志）。没有它统计不会上报。
2. **AppGallery Connect**（推送的前提）：
   - 用正式的包名 `com.sondo.music.video.ai.hm` 创建应用，开通「推送服务」；
   - 配置手动签名（发布 / 调试证书 + Profile），在 AGC 里登记证书的 SHA256 指纹；
   - 把应用级 Client ID 填进 `module.json5` 的 `metadata.client_id`（替换占位值）。
3. **极光控制台**（AppKey 见 `AppConstants.jpushAppKey`）：添加 HarmonyOS 平台，包名与 `app.json5` 一致，
   并按极光《HarmonyOS SDK 集成指南》在「推送设置 → 集成设置 → HarmonyOS」里配置鸿蒙通道。
   通知的点击行为选「打开应用」（Want 进 `EntryAbility`，由 `setClickWant` 解析），业务字段 `jumpAddress` / `jumpParams` 放 extras。
4. **微信开放平台**：给现有移动应用（`AppConstants.wechatAppId`）补充鸿蒙平台信息 —— Bundle ID 和应用的 `appIdentifier`
   （`bundleManager.getBundleInfoForSelf` 返回的 `signatureInfo.appIdentifier`），提交审核。
   微信按签名校验：调试时不要用 DevEco 的自动签名，用在 AGC 申请的调试证书手动签名，否则授权回跳会失败。
5. **支付宝开放平台**：确认应用（APPID）已开通「App 支付」，并按开放平台的要求登记鸿蒙应用信息。
   服务端下单接口不变（`/member/alipay/trade/create` 返回的 `orderStr` 直接透传给 SDK）。
   公开资料提到 SDK ≥ 15.8.27 要求支付宝客户端 ≥ 10.6.50（未核对）；未安装支付宝时 SDK 的行为没有核对过，Kuikly 侧应先用 `alipayIsInstalled` 判断。

### 可选

- `ohos.permission.APP_TRACKING_CONSENT`（OAID）：极光、友盟的文档都建议申请，用于设备标识。没有声明，SDK 仍可工作。
  要加的话需要在 `module.json5` 声明（带 `reason` 字符串资源和 `usedScene`），并在隐私同意后运行时申请。
- 极光自定义消息 / 通知扩展 / VoIP：未接（参考工程没用到）。需要时按极光文档加 `action.ohos.push.listener` 的 Ability 与 `proxyData`。

## 如何补完未核对的部分

**每个适配器通用**：升级依赖后打开 `entry/oh_modules/<包>/Index.d.ets`（友盟是 `Index.d.ts`），逐条对照文件里 `>>> SDK CALL SITE` 之间的调用；改动只应发生在适配文件内。

**友盟页面统计**：`@umeng/analytics` 1.2.12 没有 `onPageStart` / `onPageEnd`。以后的版本若提供，在
`analytics/UmengAdapter.ets` 的 import 里加上它们并实现 `pageStart` / `pageEnd`。

**友盟 preInit 时机**：友盟文档建议在 `AbilityStage.onCreate` 调 `preInit`、同意隐私后再 `init`。
这里为了「同意前零调用」把两者都放在 `init` 里。如果实测首个会话 / 时长统计不准，可以把 `preInit` 挪到 AbilityStage（它不采集数据），`init` 保持在同意之后。

**推送前台行为**：真机上分别在前台、后台、进程被杀三种状态发一条带 `jumpAddress` 的通知，看 hilog（tag `PushManager`）：
- 前台是否收到 `onUnShowMessage` / `onArrivedMessage`，据此决定 `EMIT_ARRIVED_AS_RECEIVED`；
- 冷启动点击后 `getLaunchNotification` 是否带回消息；若 SDK 回调稳定，`PushPayload.fromWant` 的兜底可以删掉，
  若 SDK 不回调而兜底也解析不到，把 `EntryAbility.onCreate` 收到的 `want.parameters` 打出来，按实际键名改 `fromWant`。

**极光一键登录**：见 `auth/JVerifySdkAdapter.ets.draft` 头部的步骤。要点：
- 宿主是 Navigation 结构（`pages/Index` + `SDNavigator.shared().pathStack`），`JVerificationLoginSettings` 需要 `isRouter=false` 并传 `navPathStack`；
- 契约要求的底部弹层、短信 / 微信入口、协议行要用 `JVerifyUIConfigBuilder` + `addCustomView` 实现，移动号段另配 `GenAuthThemeConfigBuilder`；
- 入口按钮做出来之前不要切换到 SDK 适配器：否则用户不想一键登录时只能取消，没有别的登录路径；
- 完成后把 `JVerifyAdapter.ets` 里 `JVerifyProvider.create()` 换成 `new JVerifySdkAdapter()`，`SDAuthModule` 不用改。
