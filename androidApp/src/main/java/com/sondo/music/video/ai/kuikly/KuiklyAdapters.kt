package com.sondo.music.video.ai.kuikly

import android.app.Application
import com.sondo.music.video.ai.kuikly.adapter.KRFontAdapter
import com.sondo.music.video.ai.kuikly.adapter.KRImageAdapter
import com.sondo.music.video.ai.kuikly.adapter.KRLogAdapter
import com.sondo.music.video.ai.kuikly.adapter.KRRouterAdapter
import com.sondo.music.video.ai.kuikly.adapter.KRThreadAdapter
import com.sondo.music.video.ai.kuikly.adapter.KRUncaughtExceptionHandlerAdapter
import com.tencent.kuikly.core.render.android.adapter.KuiklyRenderAdapterManager

/** Kuikly 渲染器适配器统一安装点（进程内只装一次）。 */
object KuiklyAdapters {

    @Volatile
    private var installed = false

    fun install(app: Application) {
        if (installed) return
        installed = true
        KuiklyRenderAdapterManager.krImageAdapter = KRImageAdapter(app)
        KuiklyRenderAdapterManager.krLogAdapter = KRLogAdapter
        KuiklyRenderAdapterManager.krUncaughtExceptionHandlerAdapter = KRUncaughtExceptionHandlerAdapter
        KuiklyRenderAdapterManager.krFontAdapter = KRFontAdapter
        KuiklyRenderAdapterManager.krRouterAdapter = KRRouterAdapter()
        KuiklyRenderAdapterManager.krThreadAdapter = KRThreadAdapter()
    }
}
