package com.sondo.app.ui.video

import com.tencent.kuikly.core.base.Color

// 视频播放器模块的公共类型（Dart: video_player/app_video_player_core.dart、app_video_playback_policy.dart、
// app_video_playback_profile.dart、app_video_resume.dart、app_video_style.dart、ui_core/video_playback_gate.dart）。
// 解码器 / 缓存 / 系统音频焦点 / 设备 Surface 类型都由原生 SDVideo 视图负责，这里只保留页面可见的策略与状态。

/** Dart `AppVideoDisplayMode`。 */
enum class AppVideoDisplayMode {
    /** 详情、卡片：按视频比例（或 aspectRatio）占位 */
    // 鸿蒙动态库会导出成 C/C++ 接口，不能用 C++ 关键字 inline 作名字
    inlineBox,
    /** 竖滑 MV、全屏：填满父级 */
    immersive,
    /** 封面优先：与 inline 同样的盒子 */
    thumbnail,
    /** Hero / 启动背景：填满，无按钮无进度条，不响应单击 */
    background,
}

/** Dart `AppVideoControlsMode`。 */
enum class AppVideoControlsMode { full, minimal, none }

/** Dart `AppVideoColors`：模块内置色，不读宿主主题。 */
object AppVideoColors {
    val white = Color(0xFFFFFFFFL)
    val black = Color(0xFF000000L)
    val surface = Color(0xFF1F1F1FL)
    val accent = Color(0xFF8B5CF6L)
}

/** Dart `AppVideoPlayerLabels`：参考工程里是写死的中文默认值。 */
class AppVideoPlayerLabels(
    val play: String = "播放",
    val pause: String = "暂停",
    val mute: String = "静音",
    val unmute: String = "取消静音",
    val playbackSpeed: String = "播放速度",
    val fullscreen: String = "全屏",
    val retry: String = "重试",
    val errorMessage: String = "无法播放此视频",
    val progress: String = "视频进度",
)

/**
 * Dart `AppVideoPlaybackGate` / 旧入口 `VideoPlaybackGate`：全局计数门闩。
 * 全屏 / 沉浸播放页 `acquire()`，列表 / Banner 里绑定门闩的播放器在被挡住时释放解码器。
 */
class AppVideoPlaybackGate {
    private var count = 0
    private var nextId = 0
    private val listeners = LinkedHashMap<Int, (Int) -> Unit>()

    val isBlocked: Boolean get() = count > 0
    val value: Int get() = count

    fun acquire() {
        count++
        notifyListeners()
    }

    fun release() {
        if (count == 0) return
        count--
        notifyListeners()
    }

    /** 监听计数变化；返回取消函数（组件销毁时必须调用）。 */
    fun addListener(listener: (Int) -> Unit): () -> Unit {
        val id = ++nextId
        listeners[id] = listener
        return { listeners.remove(id) }
    }

    fun dispose() {
        listeners.clear()
        count = 0
    }

    private fun notifyListeners() {
        val v = count
        listeners.values.toList().forEach {
            try {
                it(v)
            } catch (e: Throwable) {
                // 已销毁页面上的监听忽略
            }
        }
    }

    companion object {
        /** 进程级共享门闩（Dart `AppVideoPlaybackGate.instance`）。 */
        val instance = AppVideoPlaybackGate()
    }
}

/** 旧页面使用的门闩入口（Dart `VideoPlaybackGate.instance`），委托给 [AppVideoPlaybackGate.instance]。 */
object VideoPlaybackGate {
    val isBlocked: Boolean get() = AppVideoPlaybackGate.instance.isBlocked
    fun acquire() = AppVideoPlaybackGate.instance.acquire()
    fun release() = AppVideoPlaybackGate.instance.release()
    fun addListener(listener: (Int) -> Unit): () -> Unit = AppVideoPlaybackGate.instance.addListener(listener)
}

/** Dart `AppVideoPlaybackPolicy`：逐字移植的纯逻辑值对象。 */
data class AppVideoPlaybackPolicy(
    val isActive: Boolean,
    val forcePaused: Boolean,
    val deferInitUntilActive: Boolean,
    val bypassPlaybackGate: Boolean,
    val playbackGateBlocked: Boolean,
    val pauseOnLifecycleChange: Boolean,
    val isInBackground: Boolean,
    val hasOwnedController: Boolean,
) {
    val isEffectivelyActive: Boolean
        get() = isActive && !forcePaused && (bypassPlaybackGate || !playbackGateBlocked)

    val canInitialize: Boolean get() = isEffectivelyActive || !deferInitUntilActive

    val canPlay: Boolean get() = isEffectivelyActive && (!pauseOnLifecycleChange || !isInBackground)

    val shouldReleaseOwnedDecoder: Boolean
        get() = deferInitUntilActive && hasOwnedController &&
            (!isActive || (!forcePaused && !bypassPlaybackGate && playbackGateBlocked))
}

/** Dart `AppVideoInitializationRetryStrategy`：返回第 n 次重试前的等待毫秒，null = 停止。 */
fun interface AppVideoRetryStrategy {
    fun nextDelayMs(retryIndex: Int, error: String): Int?
}

/** Dart `AppVideoLinearRetryStrategy`：`base × (n+1)`。 */
class AppVideoLinearRetryStrategy(
    private val maxRetries: Int = 3,
    private val baseDelayMs: Int = 500,
) : AppVideoRetryStrategy {
    override fun nextDelayMs(retryIndex: Int, error: String): Int? =
        if (retryIndex >= maxRetries) null else baseDelayMs * (retryIndex + 1)
}

/** Dart `AppVideoExponentialRetryStrategy`：`base × 2^n`，不超过 maxDelay。 */
class AppVideoExponentialRetryStrategy(
    private val maxRetries: Int = 3,
    private val baseDelayMs: Int = 250,
    private val maxDelayMs: Int = 4000,
    private val shouldRetry: (String) -> Boolean = { true },
) : AppVideoRetryStrategy {
    override fun nextDelayMs(retryIndex: Int, error: String): Int? {
        if (retryIndex >= maxRetries || !shouldRetry(error)) return null
        var d = baseDelayMs
        repeat(retryIndex) { d *= 2 }
        return if (d > maxDelayMs) maxDelayMs else d
    }
}

/** Dart `AppVideoPlaybackProfile`：场景级默认值（Feed：与其它音频混播；MV：独占音频）。 */
class AppVideoPlaybackProfile(
    val retryStrategy: AppVideoRetryStrategy,
    val mixWithOthers: Boolean,
) {
    companion object {
        // 离线未命中缓存时原生直接报错，这里不再区分，统一按次数重试
        val feed = AppVideoPlaybackProfile(AppVideoExponentialRetryStrategy(2, 250, 2000), mixWithOthers = true)
        val mv = AppVideoPlaybackProfile(AppVideoExponentialRetryStrategy(3, 400, 4000), mixWithOthers = false)
        val default = AppVideoPlaybackProfile(AppVideoLinearRetryStrategy(3, 500), mixWithOthers = true)
    }
}

/** Dart `AppVideoResumePosition`。 */
class AppVideoResumePosition(val positionMs: Long, val durationMs: Long = 0L, val completed: Boolean = false)

/** Dart `AppVideoResumeStore`：续播位置存储（可注入持久化实现）。 */
interface AppVideoResumeStore {
    fun read(key: String): AppVideoResumePosition?
    fun write(key: String, position: AppVideoResumePosition)
    fun remove(key: String)
}

/** Dart `InMemoryAppVideoResumeStore.instance`：进程内存储（重启丢失，与参考工程一致）。 */
object InMemoryAppVideoResumeStore : AppVideoResumeStore {
    private val map = HashMap<String, AppVideoResumePosition>()
    override fun read(key: String): AppVideoResumePosition? = map[key]
    override fun write(key: String, position: AppVideoResumePosition) {
        map[key] = position
    }
    override fun remove(key: String) {
        map.remove(key)
    }
    fun clear() = map.clear()
}

/**
 * Dart `AppVideoSource.parse`：把原始字符串规整成 SDVideo 能播放的 src。
 * `assets/`、`videos/` 开头 → shared 公共资源相对路径；`file://` → 本地绝对路径；http(s) 原样；空白 → ""。
 */
object AppVideoSource {
    fun parse(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return ""
        if (s.startsWith("http://") || s.startsWith("https://")) return s
        if (s.startsWith("file://")) return s.removePrefix("file://")
        if (s.startsWith("assets/")) return s.removePrefix("assets/")
        return s
    }

    fun isNetwork(raw: String?): Boolean {
        val s = raw?.trim().orEmpty()
        return s.startsWith("http://") || s.startsWith("https://")
    }
}

/** Dart onInitialized 的 resolution 分档（按高度：2160/1440/1080/720/480/原值）。 */
fun appVideoResolutionBucket(width: Int, height: Int): Int {
    val h = if (height > 0) height else 0
    return when {
        h >= 2160 -> 2160
        h >= 1440 -> 1440
        h >= 1080 -> 1080
        h >= 720 -> 720
        h >= 480 -> 480
        else -> h
    }
}

/** Dart `_formatDuration`：`m:ss` / `h:mm:ss`。 */
fun formatVideoDuration(ms: Long): String {
    val total = (if (ms < 0) 0 else ms) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val ss = if (s < 10) "0$s" else "$s"
    return if (h > 0) {
        val mm = if (m < 10) "0$m" else "$m"
        "$h:$mm:$ss"
    } else {
        "$m:$ss"
    }
}

/**
 * Dart `AppVideoWakelock`：播放中保持亮屏的引用计数（多个播放器共用，别直接调 setKeepScreenOn）。
 * 原生开关是应用级的（SDAppModule.setKeepScreenOn）。
 */
object AppVideoWakelock {
    private val holders = HashSet<Any>()

    val holderCount: Int get() = holders.size

    fun acquire(owner: Any) {
        if (holders.add(owner) && holders.size == 1) toggle(true)
    }

    fun release(owner: Any) {
        if (holders.remove(owner) && holders.isEmpty()) toggle(false)
    }

    private fun toggle(on: Boolean) {
        try {
            com.sondo.app.core.utils.HostNative.app.setKeepScreenOn(on)
        } catch (e: Throwable) {
            // 宿主未就绪时忽略
        }
    }
}
