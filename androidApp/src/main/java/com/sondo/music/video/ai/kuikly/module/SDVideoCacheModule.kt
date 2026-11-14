package com.sondo.music.video.ai.kuikly.module

import android.content.Context
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback

/**
 * 视频缓存模块：prefetch / resolve / cancelPrefetch / clear，操作的是 SDVideoView 播放时使用的同一份缓存。
 * 缓存策略见 [SDVideoCache]。
 */
class SDVideoCacheModule : KuiklyRenderBaseModule() {

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        val ctx = appContext()
        if (ctx == null) {
            SDMediaKit.reply(callback, SDMediaKit.fail("no_context"))
            return null
        }
        val json = SDMediaKit.parse(params)
        val url = json.optString("url", "").trim()
        when (method) {
            "prefetch" -> {
                if (url.isEmpty()) {
                    SDMediaKit.reply(callback, SDMediaKit.fail("invalid_url"))
                } else {
                    SDVideoCache.prefetch(ctx, url) { result -> SDMediaKit.reply(callback, result) }
                }
            }
            "resolve" -> SDMediaKit.io.execute {
                val path = if (url.isEmpty()) null else SDVideoCache.resolve(ctx, url)
                SDMediaKit.reply(callback, SDMediaKit.ok("localPath" to path))
            }
            "cancelPrefetch" -> if (url.isNotEmpty()) SDVideoCache.cancelPrefetch(url)
            "clear" -> SDMediaKit.io.execute {
                val ok = SDVideoCache.clear(ctx)
                SDMediaKit.reply(callback, if (ok) SDMediaKit.ok() else SDMediaKit.fail("clear_failed"))
            }
            else -> return super.call(method, params, callback)
        }
        return null
    }

    private fun appContext(): Context? = (activity ?: context)?.applicationContext
}
