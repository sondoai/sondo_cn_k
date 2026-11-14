package com.sondo.music.video.ai.kuikly.module

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import com.sondo.music.video.ai.kuikly.module.SDMediaKit.optFlag
import com.sondo.music.video.ai.kuikly.module.SDMediaKit.optLongOrNull
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 全局音频播放器：进程内唯一的 ExoPlayer，跨页面持续播放。
 *
 * - 每个 Kuikly 页面有自己的 [SDAudioPlayerModule] 实例，都转发到这里；事件向所有已注册的监听者广播。
 * - ExoPlayer 只在主线程访问；[stateJson] 供 Kuikly 线程同步读取，读的是线程安全的状态快照。
 * - 系统音频焦点在 play 时申请，但中断 / duck / 耳机拔出都只上报事件，不在原生侧自动处理。
 * - 队列、播放模式、中断后的恢复策略全部在 Kuikly 侧（PlayerService）。
 * - state 事件 / getState 里的 `playing` 是播放意图，不是「此刻正在出声」；缓冲由 state="buffering" 表达。
 */
@OptIn(markerClass = [UnstableApi::class])
internal object AudioPlayerManager : SDAudioSession.Client, SDAudioSession.OutputParticipant {

    private const val TAG = "SDAudioPlayer"
    private const val POSITION_INTERVAL_MS = 200L

    const val STATE_IDLE = "idle"
    const val STATE_LOADING = "loading"
    const val STATE_BUFFERING = "buffering"
    const val STATE_READY = "ready"
    const val STATE_COMPLETED = "completed"

    interface Listener {
        fun onAudioEvent(event: Map<String, Any>)
    }

    /** 供同步读取的不可变快照。 */
    private class Snapshot(
        val state: String,
        val playing: Boolean,
        val positionMs: Long,
        val bufferedMs: Long,
        val durationMs: Long,
        val speed: Float,
        val advancing: Boolean,
        val atElapsedMs: Long,
    )

    @Volatile
    private var snapshot = Snapshot(STATE_IDLE, false, 0L, 0L, 0L, 1f, false, 0L)

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val liveModules = HashSet<Any>()

    private var appContext: Context? = null
    private var player: ExoPlayer? = null
    private var sourceSet = false
    private var hasBeenReady = false
    private var pendingSetSource: KuiklyRenderCallback? = null
    private var volume = 1f
    private var speed = 1f
    private var loopOne = false
    private var lastState: String? = null
    private var lastPlaying: Boolean? = null
    private var lastDurationMs = -1L
    private var tickerRunning = false
    private var suppressStateEvents = false

    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            if (p == null || !p.isPlaying) {
                tickerRunning = false
                return
            }
            emitPosition()
            SDMediaKit.mainHandler.postDelayed(this, POSITION_INTERVAL_MS)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                hasBeenReady = true
                publishDuration()
                resolvePendingSetSource(SDMediaKit.ok("durationMs" to currentDurationMs().takeIf { it > 0 }))
            }
            if (playbackState == Player.STATE_ENDED) {
                // 非循环播完：播放意图随之结束，state 事件里 playing=false（单曲循环不会走到 ENDED）
                player?.playWhenReady = false
            }
            publishState()
            if (playbackState == Player.STATE_ENDED) emitPosition()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            publishState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refreshSnapshot()
            if (isPlaying) startTicker() else emitPosition()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            publishDuration()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            emitPosition()
        }

        override fun onPlayerError(error: PlaybackException) {
            val msg = "${error.errorCodeName}: ${error.message ?: ""}".trim()
            Log.w(TAG, "player error: $msg (${error.cause?.message ?: ""})")
            val p = player
            if (p != null) p.playWhenReady = false
            resolvePendingSetSource(SDMediaKit.fail(msg, "code" to error.errorCode))
            broadcast(mapOf("event" to "error", "msg" to msg, "code" to error.errorCode))
            publishState()
        }
    }

    // ---------------------------------------------------------------------------------
    // 模块实例管理
    // ---------------------------------------------------------------------------------

    fun attachModule(context: Context, module: Any) = SDMediaKit.runOnMain {
        ensureInit(context)
        liveModules.add(module)
    }

    /**
     * 页面销毁。所有用过播放器的页面都销毁后（应用界面已全部退出）释放播放器 ——
     * 没有后台播放能力，不能让声音在没有界面的进程里继续响。
     */
    fun detachModule(module: Any) = SDMediaKit.runOnMain {
        liveModules.remove(module)
        if (liveModules.isEmpty()) releaseInternal()
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** 新监听者注册后立即同步一次当前状态，页面不必等下一次变化。 */
    fun currentEvents(): List<Map<String, Any>> {
        val s = snapshot
        val out = ArrayList<Map<String, Any>>(3)
        out.add(mapOf("event" to "state", "state" to s.state, "playing" to s.playing))
        if (s.durationMs > 0) out.add(mapOf("event" to "duration", "durationMs" to s.durationMs))
        if (s.state != STATE_IDLE) {
            out.add(
                mapOf(
                    "event" to "position",
                    "positionMs" to extrapolatedPosition(s),
                    "bufferedMs" to s.bufferedMs,
                    "durationMs" to s.durationMs,
                )
            )
        }
        return out
    }

    // ---------------------------------------------------------------------------------
    // 对外方法（任意线程调用，内部切主线程）
    // ---------------------------------------------------------------------------------

    fun setSource(context: Context, params: JSONObject, callback: KuiklyRenderCallback?) = SDMediaKit.runOnMain {
        val uri = parseUri(params.optString("uri", ""))
        if (uri == null) {
            SDMediaKit.reply(callback, SDMediaKit.fail("invalid_uri"))
            return@runOnMain
        }
        val autoPlay = params.optFlag("autoPlay", true)
        loopOne = params.optFlag("loopOne", false)
        val clipStart = params.optLongOrNull("clipStartMs")?.coerceAtLeast(0L)
        val clipEnd = params.optLongOrNull("clipEndMs")
        val headers = LinkedHashMap<String, String>()
        params.optJSONObject("headers")?.let { h ->
            for (key in h.keys()) {
                val v = h.optString(key, "")
                if (key.isNotEmpty() && v.isNotEmpty()) headers[key] = v
            }
        }

        try {
            val p = ensurePlayer(context)
            resolvePendingSetSource(SDMediaKit.fail("superseded", "cancelled" to true))
            pendingSetSource = callback
            hasBeenReady = false
            sourceSet = true
            lastDurationMs = -1L

            val item = MediaItem.Builder().setUri(uri)
            if (clipStart != null || clipEnd != null) {
                val start = clipStart ?: 0L
                val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start)
                if (clipEnd != null && clipEnd > start) clip.setEndPositionMs(clipEnd)
                item.setClippingConfiguration(clip.build())
            }
            val http = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
            if (headers.isNotEmpty()) http.setDefaultRequestProperties(headers)
            val dataSourceFactory = DefaultDataSource.Factory(appContext ?: context.applicationContext, http)
            // 没有 Xing 头的 CBR MP3 / ADTS 也允许按码率估算 seek
            val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
            val source = DefaultMediaSourceFactory(dataSourceFactory, extractors).createMediaSource(item.build())

            p.repeatMode = if (loopOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            p.volume = volume
            p.setPlaybackSpeed(speed)
            p.setMediaSource(source, true)
            if (autoPlay) beforeAudiblePlay(context)
            p.playWhenReady = autoPlay
            p.prepare()
            publishState()
            // preload=false（直播 AAC 等）：不等 READY，设置完音源就回调，缓冲交给播放阶段
            if (!params.optFlag("preload", true)) resolvePendingSetSource(SDMediaKit.ok())
        } catch (e: Throwable) {
            Log.w(TAG, "setSource failed", e)
            pendingSetSource = null
            SDMediaKit.reply(callback, SDMediaKit.fail(SDMediaKit.errorText(e)))
        }
    }

    fun play(context: Context) = SDMediaKit.runOnMain {
        val p = player ?: return@runOnMain
        if (!sourceSet) return@runOnMain
        beforeAudiblePlay(context)
        // 「置播放意图 + prepare / 回到开头」是一个动作，中间状态不单独上报
        suppressStateEvents = true
        try {
            p.playWhenReady = true
            when (p.playbackState) {
                // stop() 或出错之后播放器回到 IDLE，需要重新 prepare
                Player.STATE_IDLE -> p.prepare()
                // 已播完再点播放：从头开始
                Player.STATE_ENDED -> p.seekTo(0L)
                else -> Unit
            }
        } finally {
            suppressStateEvents = false
        }
        publishState()
    }

    fun pause() = SDMediaKit.runOnMain {
        // 不在这里释放焦点：被系统打断后 Kuikly 侧会先 pause，焦点还得留着才能收到「打断结束」
        player?.playWhenReady = false
    }

    fun stop() = SDMediaKit.runOnMain {
        val p = player ?: return@runOnMain
        resolvePendingSetSource(SDMediaKit.fail("stopped", "cancelled" to true))
        hasBeenReady = false
        p.stop()
        p.playWhenReady = false
        SDAudioSession.releaseFocus(this)
        publishState()
    }

    fun seek(positionMs: Long) = SDMediaKit.runOnMain {
        val p = player ?: return@runOnMain
        if (!sourceSet) return@runOnMain
        p.seekTo(positionMs.coerceAtLeast(0L))
        emitPosition()
    }

    fun setVolume(context: Context, value: Float) = SDMediaKit.runOnMain {
        val wasSilent = volume <= 0f
        volume = value.coerceIn(0f, 1f)
        val p = player ?: return@runOnMain
        p.volume = volume
        // 播放中从静音变为有声，等同于开始出声
        if (wasSilent && volume > 0f && p.playWhenReady) SDAudioSession.claimOutput(this)
    }

    fun setSpeed(value: Float) = SDMediaKit.runOnMain {
        speed = value.coerceIn(0.25f, 4f)
        player?.setPlaybackSpeed(speed)
        refreshSnapshot()
    }

    fun setLoopOne(on: Boolean) = SDMediaKit.runOnMain {
        loopOne = on
        player?.repeatMode = if (on) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun setSessionActive(context: Context, active: Boolean, callback: KuiklyRenderCallback?) = SDMediaKit.runOnMain {
        ensureInit(context)
        val granted = if (active) {
            SDAudioSession.acquireFocus(context, this)
        } else {
            SDAudioSession.releaseFocus(this)
            true
        }
        SDMediaKit.reply(callback, if (granted) SDMediaKit.ok() else SDMediaKit.fail("audio_focus_denied"))
    }

    fun release() = SDMediaKit.runOnMain { releaseInternal() }

    /** 同步读取（Kuikly 线程）。播放中按上次采样时间外推位置，避免 200ms 的台阶感。 */
    fun stateJson(): String {
        val s = snapshot
        return JSONObject()
            .put("playing", s.playing)
            .put("positionMs", extrapolatedPosition(s))
            .put("bufferedMs", s.bufferedMs)
            .put("durationMs", s.durationMs)
            .put("state", s.state)
            .toString()
    }

    // ---------------------------------------------------------------------------------
    // SDAudioSession 回调
    // ---------------------------------------------------------------------------------

    override fun onInterruption(begin: Boolean, type: String) {
        broadcast(mapOf("event" to "interruption", "begin" to begin, "type" to type))
    }

    override fun onBecomingNoisy() {
        broadcast(mapOf("event" to "becomingNoisy"))
    }

    override fun isAudiblyPlaying(): Boolean {
        val p = player ?: return false
        if (!p.playWhenReady || volume <= 0f) return false
        val st = p.playbackState
        return st == Player.STATE_READY || st == Player.STATE_BUFFERING
    }

    /** 独占的有声视频开始播放：暂停并让出焦点，由 state 事件告知 Kuikly 侧，不自动恢复。 */
    override fun pauseForHandoff() {
        player?.playWhenReady = false
        SDAudioSession.releaseFocus(this)
    }

    // ---------------------------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------------------------

    private fun ensureInit(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        SDAudioSession.addClient(app, this)
        SDAudioSession.attachOutput(this)
    }

    private fun ensurePlayer(context: Context): ExoPlayer {
        ensureInit(context)
        player?.let { return it }
        val app = appContext ?: context.applicationContext
        val p = ExoPlayer.Builder(app)
            .setLooper(Looper.getMainLooper())
            .setHandleAudioBecomingNoisy(false)
            .build()
        // handleAudioFocus = false：焦点由 SDAudioSession 统一申请，打断只上报不自动处理
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            false
        )
        p.addListener(playerListener)
        player = p
        return p
    }

    /** 即将开始出声：申请系统焦点，并暂停应用内其它有声播放器（独占的有声视频）。 */
    private fun beforeAudiblePlay(context: Context) {
        // 拿不到焦点也继续播放，由播放器自己报告真实错误（与参考工程一致）
        SDAudioSession.acquireFocus(context, this)
        if (volume > 0f) SDAudioSession.claimOutput(this)
    }

    private fun releaseInternal() {
        resolvePendingSetSource(SDMediaKit.fail("released", "cancelled" to true))
        SDMediaKit.mainHandler.removeCallbacks(ticker)
        tickerRunning = false
        val p = player
        player = null
        sourceSet = false
        hasBeenReady = false
        if (p != null) {
            try {
                p.removeListener(playerListener)
                p.release()
            } catch (e: Throwable) {
                Log.w(TAG, "release failed", e)
            }
        }
        SDAudioSession.releaseFocus(this)
        lastDurationMs = -1L
        snapshot = Snapshot(STATE_IDLE, false, 0L, 0L, 0L, speed, false, SystemClock.elapsedRealtime())
        if (lastState != STATE_IDLE || lastPlaying != false) {
            lastState = STATE_IDLE
            lastPlaying = false
            broadcast(mapOf("event" to "state", "state" to STATE_IDLE, "playing" to false))
        }
    }

    private fun parseUri(raw: String): Uri? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        SDMediaKit.localPath(s)?.let { return Uri.fromFile(File(it)) }
        return try {
            val uri = Uri.parse(s)
            if (uri.scheme.isNullOrEmpty()) null else uri
        } catch (e: Throwable) {
            null
        }
    }

    private fun resolvePendingSetSource(result: Map<String, Any>) {
        val cb = pendingSetSource ?: return
        pendingSetSource = null
        SDMediaKit.reply(cb, result)
    }

    private fun currentState(p: ExoPlayer): String = when (p.playbackState) {
        Player.STATE_BUFFERING -> if (hasBeenReady) STATE_BUFFERING else STATE_LOADING
        Player.STATE_READY -> STATE_READY
        Player.STATE_ENDED -> STATE_COMPLETED
        else -> STATE_IDLE
    }

    private fun currentDurationMs(): Long {
        val d = player?.duration ?: C.TIME_UNSET
        return if (d == C.TIME_UNSET || d < 0) 0L else d
    }

    private fun refreshSnapshot(): Snapshot {
        val p = player
        val s = if (p == null) {
            Snapshot(STATE_IDLE, false, 0L, 0L, 0L, speed, false, SystemClock.elapsedRealtime())
        } else {
            val state = currentState(p)
            Snapshot(
                state = state,
                // playing 是播放「意图」（playWhenReady）：请求播放后在 loading / buffering 期间保持 true，
                // 只在 pause / stop / 非循环播完 / 出错 / 被独占视频抢占时变为 false
                playing = p.playWhenReady && state != STATE_IDLE,
                positionMs = p.currentPosition.coerceAtLeast(0L),
                bufferedMs = p.bufferedPosition.coerceAtLeast(0L),
                durationMs = currentDurationMs(),
                speed = p.playbackParameters.speed,
                advancing = p.isPlaying,
                atElapsedMs = SystemClock.elapsedRealtime(),
            )
        }
        snapshot = s
        return s
    }

    private fun extrapolatedPosition(s: Snapshot): Long {
        if (!s.advancing) return s.positionMs
        val elapsed = (SystemClock.elapsedRealtime() - s.atElapsedMs).coerceIn(0L, 2_000L)
        val pos = s.positionMs + (elapsed * s.speed).toLong()
        return if (s.durationMs > 0) pos.coerceAtMost(s.durationMs) else pos
    }

    private fun publishState() {
        if (suppressStateEvents) return
        val s = refreshSnapshot()
        if (s.state == lastState && s.playing == lastPlaying) return
        lastState = s.state
        lastPlaying = s.playing
        broadcast(mapOf("event" to "state", "state" to s.state, "playing" to s.playing))
    }

    private fun publishDuration() {
        val d = currentDurationMs()
        if (d <= 0 || d == lastDurationMs) return
        lastDurationMs = d
        refreshSnapshot()
        broadcast(mapOf("event" to "duration", "durationMs" to d))
    }

    private fun emitPosition() {
        if (player == null) return
        val s = refreshSnapshot()
        broadcast(
            mapOf(
                "event" to "position",
                "positionMs" to s.positionMs,
                "bufferedMs" to s.bufferedMs,
                "durationMs" to s.durationMs,
            )
        )
    }

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        SDMediaKit.mainHandler.removeCallbacks(ticker)
        SDMediaKit.mainHandler.post(ticker)
    }

    private fun broadcast(event: Map<String, Any>) {
        for (l in listeners) {
            try {
                l.onAudioEvent(event)
            } catch (e: Throwable) {
                Log.w(TAG, "listener failed", e)
            }
        }
    }
}
