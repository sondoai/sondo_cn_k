package com.sondo.music.video.ai.kuikly

import com.tencent.kuikly.core.render.android.IKuiklyRenderExport

/** 原生扩展 Module / View 的集中注册表。名字必须与 shared 侧 Module.moduleName() 一致。 */
object NativeRegistry {

    fun registerModules(export: IKuiklyRenderExport) {
        with(export) {
            // 核心：网络 / 页面栈 / 应用与系统 UI / 权限 / 文件 / 加密
            moduleExport("SDHttpModule") { com.sondo.music.video.ai.kuikly.module.SDHttpModule() }
            moduleExport("SDNavModule") { com.sondo.music.video.ai.kuikly.module.SDNavModule() }
            moduleExport("SDAppModule") { com.sondo.music.video.ai.kuikly.module.SDAppModule() }
            moduleExport("SDPermissionModule") { com.sondo.music.video.ai.kuikly.module.SDPermissionModule() }
            moduleExport("SDFileModule") { com.sondo.music.video.ai.kuikly.module.SDFileModule() }
            moduleExport("SDCryptoModule") { com.sondo.music.video.ai.kuikly.module.SDCryptoModule() }
            // 三方 SDK：支付 / 登录 / 推送 / 统计
            moduleExport("SDPayModule") { com.sondo.music.video.ai.kuikly.module.SDPayModule() }
            moduleExport("SDAuthModule") { com.sondo.music.video.ai.kuikly.module.SDAuthModule() }
            moduleExport("SDPushModule") { com.sondo.music.video.ai.kuikly.module.SDPushModule() }
            moduleExport("SDAnalyticsModule") { com.sondo.music.video.ai.kuikly.module.SDAnalyticsModule() }
            // 媒体：全局音频播放器 / 视频缓存 / 相册相机文件 / 媒体处理
            moduleExport("SDAudioPlayerModule") { com.sondo.music.video.ai.kuikly.module.SDAudioPlayerModule() }
            moduleExport("SDVideoCacheModule") { com.sondo.music.video.ai.kuikly.module.SDVideoCacheModule() }
            moduleExport("SDMediaPickerModule") { com.sondo.music.video.ai.kuikly.module.SDMediaPickerModule() }
            moduleExport("SDMediaProcessModule") { com.sondo.music.video.ai.kuikly.module.SDMediaProcessModule() }
        }
    }

    fun registerViews(export: IKuiklyRenderExport) {
        with(export) {
            // 原生视图：视频 / Lottie / 网页
            renderViewExport("SDVideoView", { com.sondo.music.video.ai.kuikly.view.SDVideoView(it) })
            renderViewExport("SDLottieView", { com.sondo.music.video.ai.kuikly.view.SDLottieView(it) })
            renderViewExport("SDWebView", { com.sondo.music.video.ai.kuikly.view.SDWebView(it) })
        }
    }
}
