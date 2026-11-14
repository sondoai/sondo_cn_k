package com.sondo.music.video.ai.kuikly.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Canvas
import android.util.Log
import android.widget.ImageView
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import com.sondo.music.video.ai.kuikly.module.SDMediaKit
import com.tencent.kuikly.core.render.android.export.IKuiklyRenderViewExport
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import java.io.File
import java.io.FileInputStream

/**
 * Lottie 动画视图。
 *
 * 属性：src（shared 公共资源相对 `common/` 的路径，如 `lottie/loading.json`；或 http(s) URL；或本地绝对路径）、
 *       loop（默认 1）、autoPlay（默认 1）、speed
 * 事件：sdComplete（非循环播放自然结束时）
 * 方法：play / pause / stop
 */
class SDLottieView(context: Context) : LottieAnimationView(context), IKuiklyRenderViewExport {

    private var src = ""
    private var loop = true
    private var autoPlay = true
    private var speedValue = 1f
    private var completeCallback: KuiklyRenderCallback? = null

    private var destroyed = false
    private var applyPending = false
    private var cancelled = false
    private var loadedSrc: String? = null

    private val applyRunnable = Runnable { applySource() }

    private val animatorListener = object : AnimatorListenerAdapter() {
        override fun onAnimationStart(animation: Animator) {
            cancelled = false
        }

        override fun onAnimationCancel(animation: Animator) {
            cancelled = true
        }

        override fun onAnimationEnd(animation: Animator) {
            // 只在自然播完时上报；stop / 销毁触发的结束不算
            if (cancelled || destroyed) return
            try {
                completeCallback?.invoke(emptyMap<String, Any>())
            } catch (e: Throwable) {
                Log.w(TAG, "fire sdComplete failed", e)
            }
        }
    }

    init {
        scaleType = ImageView.ScaleType.FIT_CENTER
        repeatCount = LottieDrawable.INFINITE
        // 默认的失败监听会直接抛异常，这里改成只打日志
        setFailureListener { e -> Log.w(TAG, "load lottie failed: $src (${e?.message})") }
        addAnimatorListener(animatorListener)
    }

    override fun setProp(propKey: String, propValue: Any): Boolean {
        when (propKey) {
            "src" -> {
                val value = (propValue as? String)?.trim().orEmpty()
                if (value != src) {
                    src = value
                    scheduleApply()
                }
            }
            "loop" -> {
                loop = SDMediaKit.propFlag(propValue, true)
                repeatCount = if (loop) LottieDrawable.INFINITE else 0
            }
            "autoPlay" -> {
                val value = SDMediaKit.propFlag(propValue, true)
                if (value != autoPlay) {
                    autoPlay = value
                    if (!applyPending && loadedSrc != null) {
                        if (value && !isAnimating) playAnimation()
                    }
                }
            }
            "speed" -> {
                speedValue = SDMediaKit.propFloat(propValue, 1f).let { if (it <= 0f) 1f else it }
                speed = speedValue
            }
            EVENT_COMPLETE -> completeCallback = SDMediaKit.propCallback(propValue)
            else -> return super.setProp(propKey, propValue)
        }
        return true
    }

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        when (method) {
            "play" -> {
                flushPendingApply()
                if (loadedSrc != null) {
                    // 暂停中则续播，否则从头开始
                    if (!isAnimating && progress > 0f && progress < 1f) resumeAnimation() else if (!isAnimating) playAnimation()
                }
            }
            "pause" -> {
                flushPendingApply()
                pauseAnimation()
            }
            "stop" -> {
                flushPendingApply()
                cancelAnimation()
                progress = 0f
            }
            else -> return super.call(method, params, callback)
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyed = true
        SDMediaKit.mainHandler.removeCallbacks(applyRunnable)
        completeCallback = null
        try {
            cancelAnimation()
            removeAnimatorListener(animatorListener)
        } catch (e: Throwable) {
            // ignore
        }
    }

    override fun draw(canvas: Canvas) {
        val checkpoint = if (hasCustomClipPath()) canvas.save() else -1
        drawCommonDecoration(width, height, canvas)
        super.draw(canvas)
        if (checkpoint != -1) canvas.restoreToCount(checkpoint)
        drawCommonForegroundDecoration(width, height, canvas)
    }

    private fun scheduleApply() {
        // 等同一批属性（loop / autoPlay / speed）都到齐再加载
        applyPending = true
        SDMediaKit.mainHandler.removeCallbacks(applyRunnable)
        SDMediaKit.mainHandler.post(applyRunnable)
    }

    private fun flushPendingApply() {
        if (!applyPending) return
        SDMediaKit.mainHandler.removeCallbacks(applyRunnable)
        applySource()
    }

    private fun applySource() {
        applyPending = false
        if (destroyed) return
        cancelAnimation()
        loadedSrc = null
        if (src.isEmpty()) {
            setImageDrawable(null)
            return
        }
        try {
            repeatCount = if (loop) LottieDrawable.INFINITE else 0
            speed = speedValue
            val local = SDMediaKit.localPath(src)
            when {
                SDMediaKit.isHttp(src) -> setAnimationFromUrl(src)
                local != null -> setAnimation(FileInputStream(File(local)), local)
                else -> setAnimation(resolveAsset(src))
            }
            loadedSrc = src
            progress = 0f
            if (autoPlay) playAnimation()
        } catch (e: Throwable) {
            Log.w(TAG, "apply lottie src failed: $src", e)
        }
    }

    /** shared 模块的公共资源打进 APK 后位于 assets/common/ 下。 */
    private fun resolveAsset(raw: String): String {
        val path = raw.removePrefix("assets://").removePrefix("asset://").trimStart('/')
        if (path.startsWith("common/")) return path
        val candidate = "common/$path"
        return if (assetExists(candidate)) candidate else path
    }

    private fun assetExists(path: String): Boolean = try {
        context.assets.open(path).close()
        true
    } catch (e: Throwable) {
        false
    }

    companion object {
        const val VIEW_NAME = "SDLottieView"
        private const val TAG = "SDLottieView"
        private const val EVENT_COMPLETE = "sdComplete"
    }
}
