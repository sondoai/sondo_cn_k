package com.sondo.app.nativeapi

import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.event.Event
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

// =====================================================================================
// SDVideoView —— 视频播放视图
// =====================================================================================

/**
 * 原生视频播放视图（Android: Media3 ExoPlayer；iOS: AVPlayer；鸿蒙: AVPlayer + XComponent）。
 *
 * 属性（setProp）：
 * - src: String            http(s) URL 或本地绝对路径；变化时重新加载
 * - autoPlay: 0|1          准备好后是否自动播放（默认 0）
 * - loop: 0|1              循环（默认 0）
 * - muted: 0|1             静音（默认 0）
 * - volume: Float 0..1
 * - speed: Float 0.25..4
 * - fit: "cover"|"contain"|"fill"（默认 contain）
 * - mixWithOthers: 0|1     是否与其他音频混播（默认 0：独占音频焦点）
 * - cache: 0|1             是否使用磁盘缓存（默认 1，规则见 SDVideoCacheModule）
 * - progressIntervalMs: Int  进度事件间隔（默认 250）
 *
 * 事件：
 * - sdState   {state:"idle"|"loading"|"ready"|"buffering"|"playing"|"paused"|"completed"|"error",
 *              durationMs, width, height, error?}
 * - sdProgress {positionMs, durationMs, bufferedMs}
 * - sdFirstFrame {}
 *
 * 方法（callMethod）：play / pause / stop / seekTo({positionMs}) / release
 * 视图移除时原生侧必须释放播放器。Android 在华为 / 荣耀 / 麒麟 / 海思机型上用 SurfaceView，其余用 TextureView。
 */
class SDVideoView : DeclarativeBaseView<SDVideoAttr, SDVideoEvent>() {
    override fun createAttr(): SDVideoAttr = SDVideoAttr()
    override fun createEvent(): SDVideoEvent = SDVideoEvent()
    override fun viewName(): String = "SDVideoView"

    fun play() = call("play")
    fun pause() = call("pause")
    fun stop() = call("stop")
    fun seekTo(positionMs: Long) = call("seekTo", JSONObject().put("positionMs", positionMs).toString())
    fun release() = call("release")

    private fun call(method: String, params: String? = null) {
        performTaskWhenRenderViewDidLoad {
            renderView?.callMethod(method, params)
        }
    }
}

class SDVideoAttr : Attr() {
    fun src(src: String): SDVideoAttr { "src" with src; return this }
    fun autoPlay(v: Boolean): SDVideoAttr { "autoPlay" with (if (v) 1 else 0); return this }
    fun loop(v: Boolean): SDVideoAttr { "loop" with (if (v) 1 else 0); return this }
    fun muted(v: Boolean): SDVideoAttr { "muted" with (if (v) 1 else 0); return this }
    fun volume(v: Float): SDVideoAttr { "volume" with v; return this }
    fun speed(v: Float): SDVideoAttr { "speed" with v; return this }
    fun fitCover(): SDVideoAttr { "fit" with "cover"; return this }
    fun fitContain(): SDVideoAttr { "fit" with "contain"; return this }
    fun fitFill(): SDVideoAttr { "fit" with "fill"; return this }
    fun mixWithOthers(v: Boolean): SDVideoAttr { "mixWithOthers" with (if (v) 1 else 0); return this }
    fun cache(v: Boolean): SDVideoAttr { "cache" with (if (v) 1 else 0); return this }
    fun progressIntervalMs(v: Int): SDVideoAttr { "progressIntervalMs" with v; return this }
}

class SDVideoState(
    val state: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val error: String,
) {
    val isPlaying: Boolean get() = state == PLAYING
    val isBuffering: Boolean get() = state == BUFFERING || state == LOADING

    companion object {
        const val IDLE = "idle"
        const val LOADING = "loading"
        const val READY = "ready"
        const val BUFFERING = "buffering"
        const val PLAYING = "playing"
        const val PAUSED = "paused"
        const val COMPLETED = "completed"
        const val ERROR = "error"
    }
}

class SDVideoProgress(val positionMs: Long, val durationMs: Long, val bufferedMs: Long)

class SDVideoEvent : Event() {
    fun onState(handler: (SDVideoState) -> Unit) {
        register("sdState") {
            val o = it as? JSONObject ?: JSONObject()
            handler(
                SDVideoState(
                    o.optString("state", SDVideoState.IDLE),
                    o.optLong("durationMs", 0L),
                    o.optInt("width", 0),
                    o.optInt("height", 0),
                    o.optString("error", ""),
                )
            )
        }
    }

    fun onProgress(handler: (SDVideoProgress) -> Unit) {
        register("sdProgress") {
            val o = it as? JSONObject ?: JSONObject()
            handler(SDVideoProgress(o.optLong("positionMs", 0L), o.optLong("durationMs", 0L), o.optLong("bufferedMs", 0L)))
        }
    }

    fun onFirstFrame(handler: () -> Unit) {
        register("sdFirstFrame") { handler() }
    }
}

fun ViewContainer<*, *>.SDVideo(init: SDVideoView.() -> Unit) {
    addChild(SDVideoView(), init)
}

// =====================================================================================
// SDLottieView —— Lottie 动画
// =====================================================================================

/**
 * 属性：src（shared assets 相对 common/ 的路径，如 "lottie/loading.json"，或 http(s) URL）、
 *       loop: 0|1（默认 1）、autoPlay: 0|1（默认 1）、speed: Float
 * 事件：sdComplete {}
 * 方法：play / pause / stop
 */
class SDLottieView : DeclarativeBaseView<SDLottieAttr, SDLottieEvent>() {
    override fun createAttr(): SDLottieAttr = SDLottieAttr()
    override fun createEvent(): SDLottieEvent = SDLottieEvent()
    override fun viewName(): String = "SDLottieView"

    fun play() = call("play")
    fun pause() = call("pause")
    fun stop() = call("stop")

    private fun call(method: String) {
        performTaskWhenRenderViewDidLoad { renderView?.callMethod(method, null) }
    }
}

class SDLottieAttr : Attr() {
    fun src(src: String): SDLottieAttr { "src" with src; return this }
    fun loop(v: Boolean): SDLottieAttr { "loop" with (if (v) 1 else 0); return this }
    fun autoPlay(v: Boolean): SDLottieAttr { "autoPlay" with (if (v) 1 else 0); return this }
    fun speed(v: Float): SDLottieAttr { "speed" with v; return this }
}

class SDLottieEvent : Event() {
    fun onComplete(handler: () -> Unit) {
        register("sdComplete") { handler() }
    }
}

fun ViewContainer<*, *>.SDLottie(init: SDLottieView.() -> Unit) {
    addChild(SDLottieView(), init)
}

// =====================================================================================
// SDWebView —— 内置网页（隐私政策 / 用户协议 / 活动页）
// =====================================================================================

/**
 * 属性：url
 * 事件：sdTitle {title}、sdLoad {state:"start"|"finish"|"error", progress(0..1)}
 * 方法：reload / goBack（无法后退时不做任何事）
 */
class SDWebView : DeclarativeBaseView<SDWebAttr, SDWebEvent>() {
    override fun createAttr(): SDWebAttr = SDWebAttr()
    override fun createEvent(): SDWebEvent = SDWebEvent()
    override fun viewName(): String = "SDWebView"

    fun reload() = performTaskWhenRenderViewDidLoad { renderView?.callMethod("reload", null) }
    fun goBack() = performTaskWhenRenderViewDidLoad { renderView?.callMethod("goBack", null) }
}

class SDWebAttr : Attr() {
    fun url(url: String): SDWebAttr { "url" with url; return this }
}

class SDWebEvent : Event() {
    fun onTitle(handler: (String) -> Unit) {
        register("sdTitle") { handler((it as? JSONObject)?.optString("title", "") ?: "") }
    }

    fun onLoad(handler: (state: String, progress: Float) -> Unit) {
        register("sdLoad") {
            val o = it as? JSONObject ?: JSONObject()
            handler(o.optString("state", ""), o.optDouble("progress", 0.0).toFloat())
        }
    }
}

fun ViewContainer<*, *>.SDWeb(init: SDWebView.() -> Unit) {
    addChild(SDWebView(), init)
}
