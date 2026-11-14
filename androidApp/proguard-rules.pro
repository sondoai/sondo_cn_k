# Kuikly
-keep class com.tencent.kuikly.** { *; }
-keep class com.sondo.app.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn com.tencent.kuikly.**

# ==== 三方 SDK（支付 / 登录 / 推送 / 统计）====

# 原生模块由 Kuikly 按名字创建；极光回调类、微信回跳页由 SDK 按类名反射或由清单引用。
-keep class com.sondo.music.video.ai.kuikly.module.SDPayModule { *; }
-keep class com.sondo.music.video.ai.kuikly.module.SDAuthModule { *; }
-keep class com.sondo.music.video.ai.kuikly.module.SDPushModule { *; }
-keep class com.sondo.music.video.ai.kuikly.module.SDAnalyticsModule { *; }
-keep class com.sondo.music.video.ai.sdk.push.SondoJPushReceiver { *; }
-keep class com.sondo.music.video.ai.sdk.push.SondoJPushService { *; }
-keep class com.sondo.music.video.ai.wxapi.WXEntryActivity { *; }

# 极光推送 / 极光认证。release 开了 minify，缺这些规则收不到通知、拉不起授权页。
-keep class cn.jpush.** { *; }
-keep class cn.jiguang.** { *; }
-dontwarn cn.jpush.**
-dontwarn cn.jiguang.**
# 极光认证内置的三家运营商 SDK（移动 / 联通 / 电信）。
-keep class com.cmic.** { *; }
-keep class com.unicom.** { *; }
-keep class cn.com.chinatelecom.** { *; }
-dontwarn com.cmic.**
-dontwarn com.unicom.**
-dontwarn cn.com.chinatelecom.**

# 友盟统计。缺这些规则事件会静默丢失。
-keep class com.umeng.** { *; }
-keep class com.uc.** { *; }
-keep class com.efs.** { *; }
-keepclassmembers class * {
    public <init>(org.json.JSONObject);
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-dontwarn com.umeng.**
-dontwarn com.uc.**
-dontwarn com.efs.**

# 支付宝 App 支付。PayTask 被裁掉就拉不起收银台。
-keep class com.alipay.** { *; }
-dontwarn com.alipay.**

# 微信 OpenSDK（登录授权）。
-keep class com.tencent.mm.opensdk.** { *; }
-keep class com.tencent.wxop.** { *; }
-dontwarn com.tencent.mm.opensdk.**
