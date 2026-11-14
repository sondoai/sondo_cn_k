package com.sondo.music.video.ai.kuikly

import com.tencent.kuikly.core.render.android.context.KuiklyRenderCoreExecuteModeBase

/** 发布包没有下载、轮询或动态类加载实现。 */
internal object KuiklyHotReload {
    fun executeMode(): KuiklyRenderCoreExecuteModeBase = KuiklyRenderCoreExecuteModeBase.JVM
    fun register(activity: KuiklyRenderActivity) = Unit
}
