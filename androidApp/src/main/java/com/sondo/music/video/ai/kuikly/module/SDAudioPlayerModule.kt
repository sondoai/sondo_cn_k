package com.sondo.music.video.ai.kuikly.module

import android.content.Context
import com.sondo.music.video.ai.kuikly.module.SDMediaKit.optFlag
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局音频播放器的 Kuikly 模块。每个页面一个实例，全部转发给进程级的 [AudioPlayerManager]。
 *
 * 方法：setSource / play / pause / stop / seek / setVolume / setSpeed / setLoopOne /
 *       getState(sync) / setSessionActive / release / addEventListener(常驻回调)
 */
class SDAudioPlayerModule : KuiklyRenderBaseModule() {

    private val eventCallbacks = CopyOnWriteArrayList<KuiklyRenderCallback>()

    @Volatile
    private var attached = false

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        // getState 是同步方法，运行在 Kuikly 线程：只读快照，不碰播放器
        if (method == "getState") return AudioPlayerManager.stateJson()

        val ctx = appContext()
        if (ctx == null) {
            SDMediaKit.reply(callback, SDMediaKit.fail("no_context"))
            return null
        }
        ensureAttached(ctx)
        val json = SDMediaKit.parse(params)
        when (method) {
            "setSource" -> AudioPlayerManager.setSource(ctx, json, callback)
            "play" -> AudioPlayerManager.play(ctx)
            "pause" -> AudioPlayerManager.pause()
            "stop" -> AudioPlayerManager.stop()
            "seek" -> AudioPlayerManager.seek(json.optLong("positionMs", 0L))
            "setVolume" -> AudioPlayerManager.setVolume(ctx, json.optDouble("volume", 1.0).toFloat())
            "setSpeed" -> AudioPlayerManager.setSpeed(json.optDouble("speed", 1.0).toFloat())
            "setLoopOne" -> AudioPlayerManager.setLoopOne(json.optFlag("on", false))
            "setSessionActive" -> AudioPlayerManager.setSessionActive(ctx, json.optFlag("active", true), callback)
            "release" -> AudioPlayerManager.release()
            "addEventListener" -> addEventListener(callback)
            else -> return super.call(method, params, callback)
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        eventCallbacks.clear()
        AudioPlayerManager.removeListener(listener)
        if (attached) {
            attached = false
            AudioPlayerManager.detachModule(this)
        }
    }

    private val listener = object : AudioPlayerManager.Listener {
        override fun onAudioEvent(event: Map<String, Any>) {
            for (cb in eventCallbacks) {
                try {
                    cb.invoke(event)
                } catch (e: Throwable) {
                    // 页面销毁过程中的回调，忽略
                }
            }
        }
    }

    private fun addEventListener(callback: KuiklyRenderCallback?) {
        if (callback == null) return
        eventCallbacks.add(callback)
        AudioPlayerManager.addListener(listener)
        // 立即同步一次当前状态（后打开的页面不必等下一次状态变化）
        for (event in AudioPlayerManager.currentEvents()) {
            try {
                callback.invoke(event)
            } catch (e: Throwable) {
                // ignore
            }
        }
    }

    private fun ensureAttached(ctx: Context) {
        if (attached) return
        attached = true
        AudioPlayerManager.attachModule(ctx, this)
    }

    private fun appContext(): Context? = (activity ?: context)?.applicationContext
}
