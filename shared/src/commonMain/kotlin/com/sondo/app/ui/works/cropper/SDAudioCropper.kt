package com.sondo.app.ui.works.cropper

import com.sondo.app.base.AppHost
import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.CommonUtils
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.FileUtils
import com.sondo.app.core.utils.Uuid
import com.sondo.app.core.utils.nowMs
import com.sondo.app.data.models.VideoGenParams
import com.sondo.app.service.PlayerService
import com.sondo.app.service.audio.AppAudioClip
import com.sondo.app.service.audio.AppAudioPlaybackStatus
import com.sondo.app.service.audio.AppAudioPlayer
import com.sondo.app.service.audio.AppAudioPreviewCoordinator
import com.sondo.app.service.audio.AppAudioTrack
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDLoading
import com.sondo.app.ui.works.guide.GuideCropHandlesConfig
import com.sondo.app.ui.works.guide.GuideManager
import com.tencent.kuikly.core.base.AbstractBaseView
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.Scale
import com.tencent.kuikly.core.base.Translate
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.event.layoutFrameDidChange
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.clearTimeout
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.DivView
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import kotlin.math.abs

class SDAudioCropperAttr : ComposeAttr() {
    /** http(s) 或本地路径。构建时读取一次：换歌请用 `vbind({ song.id to song.musicAudioUrl }) { SDAudioCropper { … } }` 重建（Dart 用 ValueKey）。 */
    var url: String = ""
    var fileName: String = ""
    /** 初始总时长（毫秒，探测前显示）。 */
    var durationMs: Long? = null
    /** 0 或大于时长 → 整首；小于 30 按 30。 */
    var defaultSelectionSeconds: Int = 0
    /** 已保存的 `"mm:ss,mm:ss"`；有效时优先。 */
    var initialSelectionScope: String = ""
    var musicCoverUrl: String? = null
    /** obs：裁剪引导模式（禁用选区拖动、显示水波纹）。 */
    var isCropGuideActive: Boolean by observable(false)
    /** obs：false→true 的上升沿暂停试听。 */
    var isPauseChooseSong: Boolean by observable(false)
}

class SDAudioCropperEvent : ComposeEvent() {
    internal var changeHandler: ((String, String, Long, Long) -> Unit)? = null
    internal var clearHandler: (() -> Unit)? = null
    internal var durationLoadedHandler: (() -> Unit)? = null

    /** 加载完成及每次拖动时回调 (起点 mm:ss, 终点 mm:ss, 起点毫秒, 终点毫秒)。 */
    fun onChange(handler: (start: String, end: String, startMs: Long, endMs: Long) -> Unit) { changeHandler = handler }

    /** 点删除（下一帧回调）。 */
    fun onClear(handler: () -> Unit) { clearHandler = handler }

    fun onDurationLoaded(handler: () -> Unit) { durationLoadedHandler = handler }
}

/**
 * Dart `AudioCropper`：歌曲信息卡 + 波形区间选择器，循环试听选中片段。
 *
 * - 最短 30 秒（≤ 30 秒的音频锁定整首），无上限；区间以 0..1 百分比保存，对外报 `mm:ss` 与毫秒。
 * - 时长探测：`AppAudioPreviewCoordinator.load` 失败重试 3 次，已下载本地文件时改用本地文件再试；全部失败显示「音频加载失败」。
 * - 网络地址先下载到缓存（失败 300ms 后重试一次），本地文件喂给波形。
 * - 拖手柄 / 拖选区时隐藏进度竖线；松手时若在试听，用新区间重新加载并播放。
 * - 进度竖线：50ms 定时器按经过时间推进、超出片段取模，与播放器位置偏差 > 300ms 时校准。
 */
class SDAudioCropperView : ComposeView<SDAudioCropperAttr, SDAudioCropperEvent>() {
    private var totalDurationMs by observable(1000L)
    private var startPercent by observable(0f)
    private var endPercent by observable(1f)
    private var waveformWidth by observable(0f)
    /** 区间内进度 0..1；null 隐藏竖线。 */
    private var progress by observable<Float?>(null)
    private var musicFile by observable<String?>(null)
    private var url by observable("")
    private var isMusicLoading by observable(false)
    private var musicInitLoading by observable(false)
    private var isError by observable(false)
    private var previewPlaying by observable(false)
    private var rippleOn by observable(false)
    private var loadingBarRight by observable(false)
    private var loadingTrackWidth by observable(0f)
    private var waveShown by observable(false)

    private val handleWidth = 14f
    private var isDragging = false
    private var isTimeChange = true
    private var initCount = 0
    private var setFileCount = 0
    private var downloadCount = 0
    private var disposed = false
    private var positionTimer: String? = null
    private var loadingBarTimer: String? = null
    private var playbackPositionMs = 0L
    private var playbackOffsetMs = 0L
    private var tickAnchor: Long? = null
    private var lastPauseFlag = false
    private var notifyRef: CallbackRef? = null
    private var dragStartX = 0f
    private val trackId = "preview:cropper:" + Uuid.v4()

    /** 波形轨道视图（引导层锚点，Dart cropHandlesKey）。 */
    var waveformRef: ViewRef<DivView>? = null
        private set

    override fun createAttr(): SDAudioCropperAttr = SDAudioCropperAttr()
    override fun createEvent(): SDAudioCropperEvent = SDAudioCropperEvent()

    private val trackWidth: Float get() = waveformWidth - 2 * handleWidth

    private val minimumSelectionPercent: Float
        get() {
            val sec = totalDurationMs / 1000L
            if (sec <= MIN_SELECTION_SECONDS) return 1f
            return MIN_SELECTION_SECONDS.toFloat() / sec
        }

    /** 是否满足裁剪引导条件（≥ 30 秒）。 */
    val isEligibleForCropGuide: Boolean get() = totalDurationMs / 1000L >= MIN_SELECTION_SECONDS

    /** 波形轨道视图，供 `GuideStep(target = { cropper.waveformView })`。 */
    val waveformView: AbstractBaseView<*, *>? get() = waveformRef?.view

    private val startMs: Long get() = (totalDurationMs * startPercent).toLong()
    private val endMs: Long get() = (totalDurationMs * endPercent).toLong()

    /**
     * 引导层裁剪手柄配置（Dart buildCropGuideHandlesConfig）：手柄拖动经引导层回到本组件，拖完重绘引导层并回调 [onDragEnd]。
     */
    fun buildCropGuideHandlesConfig(onDragEnd: () -> Unit): GuideCropHandlesConfig = GuideCropHandlesConfig(
        getStartPercent = { startPercent },
        getEndPercent = { endPercent },
        handleWidth = handleWidth,
        trackHeight = WAVEFORM_HEIGHT,
        onStartDragUpdate = { d ->
            updateStartPercent(d)
            notifySelectionChange()
            GuideManager.repaint()
        },
        onEndDragUpdate = { d ->
            updateEndPercent(d)
            notifySelectionChange()
            GuideManager.repaint()
        },
        onSelectionDragUpdate = { d ->
            updateSelectionOffset(d)
            notifySelectionChange()
            GuideManager.repaint()
        },
        onDragEnd = {
            if (!disposed) {
                updatePlayMusic()
                GuideManager.repaint()
                onDragEnd()
            }
        },
    )

    // ------------------------------------------------------------------ 生命周期

    override fun created() {
        super.created()
        url = attr.url
        attr.durationMs?.let { if (it > 0) totalDurationMs = it }
        val notify = getPager().acquireModule<NotifyModule>(NotifyModule.MODULE_NAME)
        notifyRef = notify.addNotify(Events.AUDIO_STATE) { onPreviewStateChanged() }
        // 首帧后再加载（Dart addPostFrameCallback）
        setTimeout(pagerId, 0) {
            if (disposed) return@setTimeout
            page().launch { init() }
            page().launch { downloadAudio() }
        }
        setTimeout(pagerId, 16) { rippleOn = true }
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        // isPauseChooseSong 上升沿：暂停试听（绑定时会先按当前值执行一次）
        bindValueChange({ attr.isPauseChooseSong }) {
            val p = attr.isPauseChooseSong
            if (p && !lastPauseFlag) {
                page().launch { pausePreviewPlayer() }
                stopPositionListener()
            }
            lastPauseFlag = p
        }
    }

    override fun viewDestroyed() {
        disposed = true
        unbindAllValueChange()
        notifyRef?.let {
            try {
                getPager().acquireModule<NotifyModule>(NotifyModule.MODULE_NAME).removeNotify(Events.AUDIO_STATE, it)
            } catch (e: Throwable) {
            }
        }
        stopPositionListener()
        loadingBarTimer?.let { clearTimeout(it) }
        val owner = this
        AppHost.launch { AppAudioPreviewCoordinator.stop(owner) }
        super.viewDestroyed()
    }

    private fun page(): BasePager = getPager() as BasePager

    private fun onPreviewStateChanged() {
        if (disposed) return
        val st = AppAudioPlayer.state
        previewPlaying = AppAudioPreviewCoordinator.owns(this) && st.isPlaying
        isMusicLoading = previewPlaying &&
            (st.status == AppAudioPlaybackStatus.loading || st.status == AppAudioPlaybackStatus.buffering)
    }

    private suspend fun pausePreviewPlayer() {
        try {
            AppAudioPreviewCoordinator.pause(this)
        } catch (e: Throwable) {
            DevLog.logger("AudioCropper preview pause failed: $e")
        }
        onPreviewStateChanged()
    }

    private suspend fun pauseMainPlayer() {
        try {
            PlayerService.pause()
        } catch (e: Throwable) {
            DevLog.logger("AudioCropper main player pause failed: $e")
        }
    }

    // ------------------------------------------------------------------ 进度竖线

    private fun startPositionListener(reset: Boolean) {
        stopPositionListener()
        if (reset) {
            playbackOffsetMs = 0L
            playbackPositionMs = 0L
            progress = 0f
        }
        tickAnchor = nowMs()
        scheduleTick()
    }

    private fun scheduleTick() {
        positionTimer = setTimeout(pagerId, 50) {
            positionTimer = null
            if (disposed || tickAnchor == null) return@setTimeout
            tickPlaybackPosition()
            scheduleTick()
        }
    }

    private fun stopPositionListener(resetAll: Boolean = false) {
        positionTimer?.let { clearTimeout(it) }
        positionTimer = null
        tickAnchor = null
        if (resetAll) {
            playbackOffsetMs = 0L
            playbackPositionMs = 0L
            progress = null
        }
    }

    private fun tickPlaybackPosition() {
        val anchor = tickAnchor ?: return
        if (isDragging) return
        val clip = endMs - startMs
        if (clip <= 0L) return
        val now = nowMs()
        var relative = playbackOffsetMs + (now - anchor)
        if (relative >= clip) {
            relative %= clip
            playbackOffsetMs = relative
            tickAnchor = now
        }
        val playerRelative = playerRelativePosition()
        if (playerRelative != null) {
            val drift = abs(playerRelative - relative)
            if (drift > 300L || playerRelative > relative) {
                relative = playerRelative
                playbackOffsetMs = playerRelative
                tickAnchor = now
            }
        }
        if (relative == playbackPositionMs) return
        playbackPositionMs = relative
        progress = (relative.toFloat() / clip).coerceIn(0f, 1f)
    }

    private fun playerRelativePosition(): Long? {
        val st = AppAudioPlayer.state
        if (!AppAudioPreviewCoordinator.owns(this) || st.status != AppAudioPlaybackStatus.ready) return null
        val start = startMs
        val end = endMs
        val sel = end - start
        if (sel <= 0L) return null
        val pos = st.positionMs
        if (pos in start..end) return pos - start
        if (pos in 1..sel) return pos
        return null
    }

    // ------------------------------------------------------------------ 加载

    private fun notifySelectionChange() {
        if (disposed) return
        val s = startMs
        val e = endMs
        event.changeHandler?.invoke(formatTime(s), formatTime(e), s, e)
    }

    private fun trackFor(source: String): AppAudioTrack =
        if (source.startsWith("http://") || source.startsWith("https://")) {
            AppAudioTrack.fromUrl(id = trackId, url = source, title = attr.fileName)
        } else {
            AppAudioTrack.fromFile(id = trackId, path = source, title = attr.fileName)
        }

    /** 加载音源拿时长；失败抛异常，被其他入口接管返回 null。 */
    private suspend fun loadDuration(source: String): Long? {
        AppAudioPreviewCoordinator.load(this, trackFor(source))
        if (!AppAudioPreviewCoordinator.owns(this)) return null
        val st = AppAudioPlayer.state
        if (st.status == AppAudioPlaybackStatus.error) throw IllegalStateException(st.error ?: "音频加载失败")
        return if (st.durationMs > 0L) st.durationMs else null
    }

    private fun applyInitialSelection() {
        startPercent = 0f
        endPercent = 1f
        val totalSeconds = (totalDurationMs / 1000L).toInt()
        if (totalSeconds <= 0) return
        if (applyRestoredSelection(totalSeconds)) return
        val field = attr.defaultSelectionSeconds
        if (field == 0) return
        val sel = if (field < MIN_SELECTION_SECONDS) MIN_SELECTION_SECONDS else field
        if (sel > totalSeconds) return
        endPercent = sel.toFloat() / totalSeconds
    }

    /** 草稿区间必须落在当前音频内且满足最短时长。 */
    private fun applyRestoredSelection(totalSeconds: Int): Boolean {
        val sel = VideoGenParams.tryParseSubDurationScope(attr.initialSelectionScope) ?: return false
        val endSec = (sel.end / 1000L).toInt()
        if (sel.durationInSeconds < MIN_SELECTION_SECONDS || endSec > totalSeconds) return false
        startPercent = (sel.start / 1000L).toFloat() / totalSeconds
        endPercent = if (endSec == totalSeconds) 1f else endSec.toFloat() / totalSeconds
        return true
    }

    private fun applyLoadedDuration(duration: Long, clearInitLoading: Boolean) {
        if (disposed) return
        totalDurationMs = duration
        if (clearInitLoading) setInitLoading(false)
        applyInitialSelection()
        notifySelectionChange()
        event.durationLoadedHandler?.invoke()
    }

    private fun setInitLoading(v: Boolean) {
        musicInitLoading = v
        if (v) startLoadingBar() else {
            loadingBarTimer?.let { clearTimeout(it) }
            loadingBarTimer = null
            // 下一帧触发波形淡入
            setTimeout(pagerId, 16) { waveShown = true }
        }
    }

    private fun startLoadingBar() {
        loadingBarTimer?.let { clearTimeout(it) }
        loadingBarTimer = setTimeout(pagerId, 1200) {
            loadingBarRight = !loadingBarRight
            if (musicInitLoading && !disposed) startLoadingBar()
        }
        // 立即启动第一段
        loadingBarRight = !loadingBarRight
    }

    private suspend fun init() {
        while (!disposed) {
            try {
                setInitLoading(true)
                waveShown = false
                val duration = loadDuration(url)
                if (disposed || duration == null) return
                applyLoadedDuration(duration, clearInitLoading = true)
                return
            } catch (e: Throwable) {
                if (disposed) return
                initCount++
                val file = musicFile
                if (file != null) {
                    setFilePath(file)
                    return
                }
                if (initCount > 3) {
                    setInitLoading(false)
                    isError = true
                    return
                }
            }
        }
    }

    /** 用本地缓存文件重新加载（init 失败的降级路径）。 */
    private suspend fun setFilePath(file: String) {
        while (!disposed) {
            try {
                val duration = loadDuration(file)
                if (disposed || duration == null || duration <= 0L) return
                applyLoadedDuration(duration, clearInitLoading = false)
                return
            } catch (e: Throwable) {
                if (disposed) return
                if (setFileCount > 3) {
                    setInitLoading(false)
                    isError = true
                    return
                }
                setFileCount++
            }
        }
    }

    private suspend fun downloadAudio() {
        if (disposed || url.isEmpty()) return
        val src = url
        val path: String? = if (src.startsWith("http://") || src.startsWith("https://")) {
            val ext = FileUtils.extensionOf(src).ifEmpty { "mp3" }
            CommonUtils.downloadWithCache(src, ext)
        } else {
            src
        }
        if (disposed) return
        if (path != null) {
            musicFile = path
            return
        }
        DevLog.logger("AudioCropper audio file load failed: $src")
        if (downloadCount >= 1) return
        downloadCount++
        com.sondo.app.core.utils.delayMs(300)
        if (disposed) return
        downloadAudio()
    }

    // ------------------------------------------------------------------ 拖动

    private fun updateStartPercent(delta: Float) {
        if (disposed || trackWidth <= 0f) return
        isDragging = true
        progress = null
        isTimeChange = true
        var v = startPercent + delta / trackWidth
        if (v < 0f) v = 0f
        if (v > endPercent - minimumSelectionPercent) v = endPercent - minimumSelectionPercent
        startPercent = v
    }

    private fun updateEndPercent(delta: Float) {
        if (disposed || trackWidth <= 0f) return
        isDragging = true
        progress = null
        isTimeChange = true
        var v = endPercent + delta / trackWidth
        if (v > 1f) v = 1f
        if (v < startPercent + minimumSelectionPercent) v = startPercent + minimumSelectionPercent
        endPercent = v
    }

    private fun updateSelectionOffset(delta: Float) {
        if (disposed || trackWidth <= 0f) return
        val width = endPercent - startPercent
        var newStart = startPercent + delta / trackWidth
        var newEnd = newStart + width
        if (newStart < 0f) {
            newStart = 0f
            newEnd = width
        } else if (newEnd > 1f) {
            newEnd = 1f
            newStart = 1f - width
        }
        isDragging = true
        progress = null
        isTimeChange = true
        startPercent = newStart
        endPercent = newEnd
    }

    /** 区间变了才重设音源；返回 true = 需重置进度，false = 未变，null = 出错。 */
    private suspend fun ensureClipReady(): Boolean? {
        if (disposed) return null
        if (!isTimeChange) return false
        isTimeChange = false
        return try {
            AppAudioPreviewCoordinator.load(
                this,
                trackFor(musicFile ?: url),
                clip = AppAudioClip(startMs, endMs),
                loop = true,
            )
            if (!AppAudioPreviewCoordinator.owns(this)) throw IllegalStateException("试听会话已由其他页面接管")
            if (AppAudioPlayer.state.status == AppAudioPlaybackStatus.error) throw IllegalStateException("裁剪片段加载失败")
            if (disposed) null else true
        } catch (e: Throwable) {
            null
        }
    }

    /** 不等待：刚换过音源时 play 可能迟迟不返回，但声音已经在播。 */
    private fun startClipPlayback() {
        val owner = this
        page().launch {
            try {
                AppAudioPreviewCoordinator.resume(owner)
            } catch (e: Throwable) {
                DevLog.logger("audio cropper play failed: $e")
            }
            onPreviewStateChanged()
        }
    }

    /** 点播放 / 暂停。 */
    private fun playMusic() {
        if (disposed) return
        if (isError) {
            page().toast("audio_file_load_failed".tr)
            return
        }
        page().launch { pauseMainPlayer() }
        if (isMusicLoading) return
        page().launch {
            if (previewPlaying) {
                pausePreviewPlayer()
                if (disposed) return@launch
                playbackOffsetMs = playbackPositionMs
                stopPositionListener()
            } else {
                isMusicLoading = true
                val changed = ensureClipReady()
                if (disposed) return@launch
                if (changed == null) {
                    isMusicLoading = false
                    return@launch
                }
                if (changed) {
                    playbackOffsetMs = 0L
                    playbackPositionMs = 0L
                    progress = 0f
                }
                startClipPlayback()
                startPositionListener(reset = false)
                isMusicLoading = false
            }
        }
    }

    /** 拖动结束：正在试听则用新区间重新播放。 */
    private fun updatePlayMusic() {
        if (disposed) return
        isDragging = false
        if (!previewPlaying) return
        if (isError) {
            page().toast("audio_file_load_failed".tr)
            return
        }
        if (isMusicLoading) return
        page().launch {
            isMusicLoading = true
            val changed = ensureClipReady()
            if (disposed) return@launch
            if (changed == null) {
                pausePreviewPlayer()
                isMusicLoading = false
                return@launch
            }
            if (changed) {
                playbackOffsetMs = 0L
                playbackPositionMs = 0L
                progress = 0f
            }
            startClipPlayback()
            startPositionListener(reset = false)
            isMusicLoading = false
        }
    }

    private fun onDelete() {
        if (disposed) return
        page().launch { pausePreviewPlayer() }
        stopPositionListener(resetAll = true)
        page().launch { pauseMainPlayer() }
        url = ""
        totalDurationMs = 10_000L
        startPercent = 0.3f
        endPercent = 0.6f
        setTimeout(pagerId, 0) {
            if (!disposed) event.clearHandler?.invoke()
        }
    }

    /** 横向拖动手势（pan）：把 pageX 的增量交给 [onDelta]。 */
    private fun handlePan(state: String, pageX: Float, onDelta: (Float) -> Unit) {
        when (state) {
            "start" -> dragStartX = pageX
            "move" -> {
                val d = pageX - dragStartX
                dragStartX = pageX
                if (d != 0f) {
                    onDelta(d)
                    notifySelectionChange()
                }
            }
            else -> updatePlayMusic()
        }
    }

    // ------------------------------------------------------------------ UI

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            audioInfoCard(ctx)
            vif({ !ctx.musicInitLoading || ctx.musicFile != null }) {
                View {
                    attr {
                        overflow(true)
                        // 播放器未就绪时高度压成 0、不响应手势
                        maxHeight(if (ctx.musicInitLoading) 0f else 1000f)
                        touchEnable(!ctx.musicInitLoading)
                    }
                    View {
                        attr {
                            opacity(if (ctx.waveShown) 1f else 0f)
                            transform(Translate(0f, if (ctx.waveShown) 0f else -0.1f))
                            animate(Animation.easeOut(0.35f), ctx.waveShown)
                        }
                        waveformSection(ctx)
                    }
                }
            }
        }
    }

    companion object {
        const val MIN_SELECTION_SECONDS = 30
        const val WAVEFORM_HEIGHT = 50f
        internal val handleColor = Color(0xFFE5E7E9L)
        internal val handleGripColor = Color(0xFF262B3CL)
        internal val cursorColor = Color(0xFFC685FFL)

        fun formatTime(ms: Long): String {
            val totalSec = ms / 1000L
            val m = totalSec / 60
            val s = totalSec % 60
            return "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
        }
    }

    // 供顶层构建函数读取的状态（构建函数必须是顶层的）
    internal val vTotalDurationMs get() = totalDurationMs
    internal val vStartPercent get() = startPercent
    internal val vEndPercent get() = endPercent
    internal val vTrackWidth get() = trackWidth
    internal val vProgress get() = progress
    internal val vMusicFile get() = musicFile
    internal val vIsMusicLoading get() = isMusicLoading
    internal val vMusicInitLoading get() = musicInitLoading
    internal val vIsError get() = isError
    internal val vPreviewPlaying get() = previewPlaying
    internal val vRippleOn get() = rippleOn
    internal val vLoadingBarRight get() = loadingBarRight
    internal val vLoadingTrackWidth get() = loadingTrackWidth
    internal val vHandleWidth get() = handleWidth
    internal val vAttr get() = attr
    internal fun vSetWaveformWidth(w: Float) { waveformWidth = w }
    internal fun vSetLoadingTrackWidth(w: Float) { loadingTrackWidth = w }
    internal fun vSetWaveformRef(r: ViewRef<DivView>) { waveformRef = r }
    internal fun vPlay() = playMusic()
    internal fun vDelete() = onDelete()
    internal fun vPan(state: String, pageX: Float, kind: Int) {
        // Dart：引导期间选区的拖动回调为 null（不移动、不回调、不试听）
        if (kind == 2 && attr.isCropGuideActive) return
        handlePan(state, pageX) { d ->
            when (kind) {
                0 -> updateStartPercent(d)
                1 -> updateEndPercent(d)
                else -> if (!attr.isCropGuideActive) updateSelectionOffset(d)
            }
        }
    }
    internal val vStartMs get() = startMs
    internal val vEndMs get() = endMs
}

fun ViewContainer<*, *>.SDAudioCropper(init: SDAudioCropperView.() -> Unit) {
    addChild(SDAudioCropperView(), init)
}

// ============================================================================ 构建函数

/** 信息卡：封面 / 文件图标 + 播放按钮、文件名 + 时长（加载中为往返滑块）、删除。 */
private fun ViewContainer<*, *>.audioInfoCard(ctx: SDAudioCropperView) {
    val cover = ctx.vAttr.musicCoverUrl ?: ""
    View {
        attr {
            padding(10f)
            backgroundColor(SDColors.white10)
            borderRadius(12f)
            flexDirectionRow()
            alignItemsCenter()
        }
        View {
            attr {
                size(50f, 50f)
                allCenter()
            }
            event { click { ctx.vPlay() } }
            if (cover.isNotEmpty()) {
                SDImage {
                    attr {
                        absolutePositionAllZero()
                        borderRadius(10f)
                        url = cover
                    }
                }
            } else {
                Image {
                    attr {
                        absolutePositionAllZero()
                        src(ImageUri.commonAssets(if (ctx.vMusicInitLoading) AssetsImage.loadingFileIcon else AssetsImage.fileIcon))
                    }
                }
            }
            vif({ !ctx.vMusicInitLoading }) {
                View {
                    attr {
                        size(30f, 30f)
                        borderRadius(20f)
                        backgroundColor(if (cover.isNotEmpty()) Color(0, 0, 0, 0.5f) else Color.TRANSPARENT)
                        allCenter()
                    }
                    vif({ ctx.vIsMusicLoading }) {
                        SDLoading {
                            attr {
                                size = 20f
                                color = Color.WHITE
                            }
                        }
                    }
                    velse {
                        Image {
                            attr {
                                size(22f, 22f)
                                src(ImageUri.commonAssets(if (ctx.vPreviewPlaying) AssetsImage.itemPause1 else AssetsImage.play2))
                            }
                        }
                    }
                }
            }
        }
        View {
            attr {
                flex(1f)
                marginLeft(8f)
                height(50f)
                justifyContentSpaceAround()
            }
            Text {
                attr {
                    text(if (ctx.vIsError) "audio_file_load_failed".tr else ctx.vAttr.fileName)
                    color(if (ctx.vIsError) Color(0xFFF44336L) else Color.WHITE)
                    fontSize(14f)
                    fontWeight600()
                    lines(1)
                    textOverFlowTail()
                }
            }
            View {
                attr {
                    height(24f)
                    justifyContentCenter()
                }
                vif({ ctx.vMusicInitLoading }) {
                    View {
                        attr {
                            height(4f)
                            borderRadius(256f)
                            backgroundColor(SDColors.white10)
                            overflow(true)
                        }
                        event { layoutFrameDidChange { ctx.vSetLoadingTrackWidth(it.width) } }
                        View {
                            attr {
                                size(18f, 4f)
                                borderRadius(256f)
                                backgroundColor(SDAudioCropperView.cursorColor)
                                val max = (ctx.vLoadingTrackWidth - 18f).coerceAtLeast(0f)
                                transform(Translate(0f, 0f, if (ctx.vLoadingBarRight) max else 0f, 0f))
                                animate(Animation.easeInOut(1.2f), ctx.vLoadingBarRight)
                            }
                        }
                    }
                }
                velse {
                    Text {
                        attr {
                            text(SDAudioCropperView.formatTime(ctx.vTotalDurationMs))
                            color(SDColors.white40)
                            fontSize(10f)
                            fontFamily("Poppins-Medium")
                        }
                    }
                }
            }
        }
        vif({ !ctx.vMusicInitLoading }) {
            View {
                attr { padding(10f, 10f, 10f, 0f) }
                event { click { ctx.vDelete() } }
                Image {
                    attr {
                        size(24f, 24f)
                        src(ImageUri.commonAssets(AssetsImage.uploadDel))
                    }
                }
            }
        }
    }
}

/** 波形 + 选区 + 手柄 + 进度竖线 + 起止时间。 */
private fun ViewContainer<*, *>.waveformSection(ctx: SDAudioCropperView) {
    val hw = ctx.vHandleWidth
    val h = SDAudioCropperView.WAVEFORM_HEIGHT
    View {
        ref { ctx.vSetWaveformRef(it) }
        attr {
            marginTop(12f)
            height(h)
        }
        event { layoutFrameDidChange { ctx.vSetWaveformWidth(it.width) } }
        SDAudioWaveform {
            attr {
                absolutePosition(top = 0f, left = hw, right = hw, bottom = 0f)
                audioPath = ctx.vMusicFile
                durationMs = ctx.vTotalDurationMs
                waveColor = Color(0xFF797A7AL)
                strokeWidth = 2f
                pixelsPerStep = 4f
            }
        }
        // 选区高亮（引导期间只展示，交互由引导层处理）
        View {
            attr {
                absolutePosition(top = 0f, left = ctx.vStartPercent * ctx.vTrackWidth)
                size(2 * hw + (ctx.vEndPercent - ctx.vStartPercent) * ctx.vTrackWidth.coerceAtLeast(0f), h)
                backgroundColor(Color(255, 255, 255, 22f / 255f))
                border(Border(2f, BorderStyle.SOLID, SDAudioCropperView.handleColor))
                borderRadius(12f)
            }
            event { pan { p -> ctx.vPan(p.state, p.pageX, 2) } }
        }
        // 教程水波纹
        vif({ ctx.vAttr.isCropGuideActive }) {
            View {
                attr {
                    absolutePosition(top = 0f, left = ctx.vStartPercent * ctx.vTrackWidth)
                    size(h, h)
                    touchEnable(false)
                }
                tutorialRipple(ctx, 0f)
                tutorialRipple(ctx, 0.5f)
            }
        }
        cropHandle(ctx, isLeft = true)
        cropHandle(ctx, isLeft = false)
        // 进度竖线
        vif({ ctx.vProgress != null }) {
            View {
                attr {
                    val p = ctx.vProgress ?: 0f
                    val left = hw + (ctx.vStartPercent + p * (ctx.vEndPercent - ctx.vStartPercent)) * ctx.vTrackWidth
                    absolutePosition(top = 2f, left = left - 1f, bottom = 2f)
                    width(2f)
                    backgroundColor(SDAudioCropperView.cursorColor)
                    touchEnable(false)
                }
            }
        }
    }
    View {
        attr {
            marginTop(4f)
            flexDirectionRow()
            justifyContentSpaceBetween()
        }
        Text {
            attr {
                text(SDAudioCropperView.formatTime(ctx.vStartMs))
                color(Color(0x80FFFFFFL))
                fontSize(10f)
                fontWeight600()
            }
        }
        Text {
            attr {
                text(SDAudioCropperView.formatTime(ctx.vEndMs))
                color(Color(0x80FFFFFFL))
                fontSize(10f)
                fontWeight600()
            }
        }
    }
}

private fun ViewContainer<*, *>.cropHandle(ctx: SDAudioCropperView, isLeft: Boolean) {
    val hw = ctx.vHandleWidth
    View {
        attr {
            val left = if (isLeft) ctx.vStartPercent * ctx.vTrackWidth else hw + ctx.vEndPercent * ctx.vTrackWidth
            absolutePosition(top = 0f, left = left)
            size(hw, SDAudioCropperView.WAVEFORM_HEIGHT)
            backgroundColor(SDAudioCropperView.handleColor)
            if (isLeft) borderRadius(12f, 0f, 12f, 0f) else borderRadius(0f, 12f, 0f, 12f)
            allCenter()
        }
        event { pan { p -> ctx.vPan(p.state, p.pageX, if (isLeft) 0 else 1) } }
        View {
            attr {
                size(4f, 16f)
                borderRadius(10f)
                backgroundColor(SDAudioCropperView.handleGripColor)
            }
        }
    }
}

/** 白色 50 圆：scale 0→1.2、透明度 0.8→0，1500ms 循环。 */
private fun ViewContainer<*, *>.tutorialRipple(ctx: SDAudioCropperView, delayS: Float) {
    View {
        attr {
            absolutePosition(top = 0f, left = -16f)
            size(SDAudioCropperView.WAVEFORM_HEIGHT, SDAudioCropperView.WAVEFORM_HEIGHT)
            borderRadius(50f)
            backgroundColor(Color.WHITE)
            val on = ctx.vRippleOn
            transform(Scale(if (on) 1.2f else 0f, if (on) 1.2f else 0f))
            opacity(if (on) 0f else 0.8f)
            animate(Animation.easeOut(1.5f).delay(delayS).repeatForever(true), ctx.vRippleOn)
        }
    }
}
