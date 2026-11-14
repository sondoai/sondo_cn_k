package com.sondo.music.video.ai.kuikly.module

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 进程内唯一的「音频会话」：系统音频焦点 + 耳机拔出广播 + 应用内有声输出互斥。
 *
 * 对应参考工程里 audio_session（AudioSession.instance）与 AppAudioOutputCoordinator 两层：
 * - 系统焦点全应用只申请一份，按 owner 引用计数（全局音频播放器、每个独占的有声视频各算一个 owner），
 *   这样应用内的播放器互相切换时不会把自己「打断」；
 * - 焦点变化统一翻译成 interruption(begin, type) 广播给所有 [Client]，由各自决定怎么处理
 *   （音频播放器只上报给 Kuikly；视频视图在原生侧自行 duck / 暂停 / 恢复）；
 * - [claimOutput] 负责应用内互斥：新的有声输出开始播放前，暂停其它正在出声的参与者。
 *
 * 所有方法只在主线程调用。
 */
internal object SDAudioSession {

    private const val TAG = "SDAudioSession"

    const val TYPE_DUCK = "duck"
    const val TYPE_PAUSE = "pause"
    const val TYPE_UNKNOWN = "unknown"

    /** 系统层事件的接收方。 */
    interface Client {
        fun onInterruption(begin: Boolean, type: String) {}
        fun onBecomingNoisy() {}
    }

    /** 应用内互斥的参与者（会出声的播放器）。 */
    interface OutputParticipant {
        /** 当前是否正在（或即将）出声。 */
        fun isAudiblyPlaying(): Boolean

        /** 被其它有声播放器抢占：暂停并放弃焦点，不要自动恢复。 */
        fun pauseForHandoff()
    }

    private val clients = CopyOnWriteArraySet<Client>()
    private val participants = LinkedHashSet<OutputParticipant>()
    private val focusOwners = HashSet<Any>()

    private var appContext: Context? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    private var ducked = false
    private var noisyRegistered = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // 监听器绑定在主线程 Handler 上
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                val type = if (ducked) TYPE_DUCK else TYPE_PAUSE
                ducked = false
                dispatchInterruption(false, type)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // 永久失去焦点：系统已把我们移出焦点栈，下次播放需要重新申请
                hasFocus = false
                ducked = false
                dispatchInterruption(true, TYPE_UNKNOWN)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasFocus = false
                ducked = false
                dispatchInterruption(true, TYPE_PAUSE)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                ducked = true
                dispatchInterruption(true, TYPE_DUCK)
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            for (c in clients) {
                try {
                    c.onBecomingNoisy()
                } catch (e: Throwable) {
                    Log.w(TAG, "onBecomingNoisy failed", e)
                }
            }
        }
    }

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        audioManager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    fun addClient(context: Context, client: Client) {
        init(context)
        clients.add(client)
        ensureNoisyReceiver()
    }

    fun removeClient(client: Client) {
        clients.remove(client)
    }

    // ---------------------------------------------------------------------------------
    // 系统音频焦点
    // ---------------------------------------------------------------------------------

    /** [owner] 申请焦点。已持有时只登记 owner，不重复向系统申请。 */
    fun acquireFocus(context: Context, owner: Any): Boolean {
        init(context)
        val am = audioManager ?: run {
            focusOwners.add(owner)
            return true
        }
        if (hasFocus && focusRequest != null) {
            focusOwners.add(owner)
            return true
        }
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener, SDMediaKit.mainHandler)
            .build()
            .also { focusRequest = it }
        val granted = try {
            am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (e: Throwable) {
            Log.w(TAG, "requestAudioFocus failed", e)
            false
        }
        if (granted) {
            hasFocus = true
            ducked = false
            focusOwners.add(owner)
        }
        return granted
    }

    /** [owner] 释放焦点；最后一个 owner 离开时才真正还给系统。 */
    fun releaseFocus(owner: Any) {
        if (!focusOwners.remove(owner)) return
        if (focusOwners.isNotEmpty()) return
        abandon()
    }

    fun holdsFocus(owner: Any): Boolean = focusOwners.contains(owner)

    private fun abandon() {
        val request = focusRequest ?: return
        try {
            audioManager?.abandonAudioFocusRequest(request)
        } catch (e: Throwable) {
            Log.w(TAG, "abandonAudioFocus failed", e)
        }
        hasFocus = false
        ducked = false
    }

    private fun dispatchInterruption(begin: Boolean, type: String) {
        for (c in clients) {
            try {
                c.onInterruption(begin, type)
            } catch (e: Throwable) {
                Log.w(TAG, "onInterruption failed", e)
            }
        }
    }

    private fun ensureNoisyReceiver() {
        if (noisyRegistered) return
        val app = appContext ?: return
        try {
            // 只接收系统广播；进程级单例，随进程存活
            ContextCompat.registerReceiver(
                app,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            noisyRegistered = true
        } catch (e: Throwable) {
            Log.w(TAG, "register noisy receiver failed", e)
        }
    }

    // ---------------------------------------------------------------------------------
    // 应用内有声输出互斥
    // ---------------------------------------------------------------------------------

    fun attachOutput(participant: OutputParticipant) {
        participants.add(participant)
    }

    fun detachOutput(participant: OutputParticipant) {
        participants.remove(participant)
    }

    /** 除 [owner] 之外是否有参与者正在出声。 */
    fun isOtherAudiblyPlaying(owner: OutputParticipant): Boolean {
        for (other in participants) {
            if (other === owner) continue
            val audible = try {
                other.isAudiblyPlaying()
            } catch (e: Throwable) {
                false
            }
            if (audible) return true
        }
        return false
    }

    /** [owner] 即将出声：暂停其它正在出声的参与者。 */
    fun claimOutput(owner: OutputParticipant) {
        val others = participants.filter { it !== owner }
        for (other in others) {
            val audible = try {
                other.isAudiblyPlaying()
            } catch (e: Throwable) {
                false
            }
            if (!audible) continue
            try {
                other.pauseForHandoff()
            } catch (e: Throwable) {
                Log.w(TAG, "pauseForHandoff failed", e)
            }
        }
    }
}
