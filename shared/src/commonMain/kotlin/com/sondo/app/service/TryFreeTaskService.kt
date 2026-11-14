package com.sondo.app.service

import com.sondo.app.core.utils.onHost
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.base.currentPager
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.storage.Sp
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.HostBinding
import com.sondo.app.core.utils.nowMs
import com.sondo.app.data.models.jInt
import com.sondo.app.data.models.jLong
import com.sondo.app.data.models.jStr
import com.sondo.app.data.models.jStrOrNull
import com.sondo.app.service.analytics.UmengUtils
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 试用 MV 任务状态（与后端 status 字段一致）。 */
enum class TrialMvStatus(val value: Int) {
    /** 生成中 */
    generating(1),

    /** 已完成 */
    completed(2),

    /** 可重试的失败 */
    retryableFailed(3);

    val isGenerating: Boolean get() = this == generating
    val isCompleted: Boolean get() = this == completed
    val canRetryStage: Boolean get() = this == retryableFailed

    companion object {
        fun fromValue(value: Int): TrialMvStatus = values().firstOrNull { it.value == value } ?: generating
    }
}

/** 试用 MV 生成阶段（与后端 trialStage 字段一致）。 */
enum class TrialMvStage(val value: Int) {
    fourView(1),
    image(2),
    video(3),
    merge(4);

    /** 等待页步骤下标（0-based）。 */
    val waitingStepIndex: Int get() = value - 1

    companion object {
        fun fromValue(value: Int): TrialMvStage = values().firstOrNull { it.value == value } ?: fourView

        val stageTextKeys = listOf(
            "try_free_step_analyzing_photo",
            "try_free_step_crafting_frame",
            "try_free_step_rendering_frame",
            "try_free_step_merging_clips",
        )

        fun stageTextKeyFromValue(trialStage: Int): String {
            val index = fromValue(trialStage).waitingStepIndex.coerceIn(0, stageTextKeys.size - 1)
            return stageTextKeys[index]
        }
    }
}

/**
 * 试用 MV 活跃任务服务：管理主框架浮窗状态与后台轮询（3 秒一次）。
 *
 * 状态机：
 * - 空闲（mvId = 0）→ [startTask] → 生成中（立即拉一次，之后每 3s 拉详情）；
 * - 生成中 → 已完成 / 可重试失败：停止轮询，`trial_generate` 每个 mvId 只上报一次；
 * - 可重试失败 → 生成中：由等待页发起重试并用自己的轮询喂 [applyDetail]，不重新 startTask（倒计时锚点保留）；
 *   与参考工程一致，applyDetail 不会重启本服务的定时器；
 * - 任意 → 空闲：[clearTask]（登出 / 换号、完成态点浮窗、从作品库或推送打开同一条 MV）。
 *
 * 状态变化广播 [Events.TRY_FREE_CHANGED]（data 即 [snapshot]），并同步作品库列表里的同一条试用 MV。
 */
object TryFreeTaskService {
    private const val TAG = "TryFreeTaskService"
    private const val POLL_INTERVAL_MS = 3000

    /** 等待页预计总时长（秒）。 */
    const val decorCountdownTotalSeconds = 240

    /** 等待页装饰步骤数（与 waitStepKeys 一致）。 */
    const val decorStepCount = 60

    // --- 任务状态 ---

    /** 当前活跃任务 ID；0 表示无任务。 */
    var mvId: Long = 0L
        private set

    /** 用户上传头像 URL。 */
    var coverImage: String = ""
        private set

    var status: TrialMvStatus = TrialMvStatus.generating
        private set

    var stage: TrialMvStage = TrialMvStage.fourView
        private set

    /** 合成完成后的视频 URL，供作品库列表同步。 */
    var resultUrl: String = ""
        private set

    /** 四视图结果图 URL。 */
    var fourViewImg: String = ""
        private set

    /** 首帧图 URL。 */
    var firstFrameImg: String = ""
        private set

    /** 等待页装饰倒计时锚点（毫秒时间戳）；0 表示尚未写入。 */
    var waitStartedAtMs: Long = 0L
        private set

    private var pollTimer: String? = null
    private var pollGeneration = 0
    /** 正在请求中的轮询所属代次；-1 表示没有。只挡同一代次的叠加，新任务的首次轮询不受旧请求影响。 */
    private var pollingGeneration = -1

    /** 已上报 trial_generate 的任务 ID，避免轮询重复上报。 */
    private var reportedGenerateMvId = 0L
    private var templateTag = ""
    private var photoSource = ""
    private var inited = false
    private val hostBinding = HostBinding()

    /** 是否应在主框架显示浮窗。 */
    val isVisible: Boolean get() = mvId > 0L

    /** 环形进度：completed 为 1.0，否则按阶段估算。 */
    val progress: Double
        get() {
            if (status == TrialMvStatus.completed) return 1.0
            return (stage.value.toDouble() / TrialMvStage.merge.value).coerceIn(0.0, 1.0)
        }

    /** 浮窗状态文案 i18n key（三态：生成中 / 已完成 / 失败）。 */
    val statusTextKey: String
        get() = when (status) {
            TrialMvStatus.completed -> "try_free_float_status_complete"
            TrialMvStatus.retryableFailed -> "try_free_float_banner_failed"
            TrialMvStatus.generating -> "try_free_float_status_generating"
        }

    /** 自任务开始已过去的秒数（墙钟）。 */
    val decorElapsedSeconds: Int
        get() {
            if (waitStartedAtMs <= 0L) return 0
            val elapsed = ((nowMs() - waitStartedAtMs) / 1000L).toInt()
            return if (elapsed < 0) 0 else elapsed
        }

    /** 等待页剩余秒数；归零后展示 Just a moment。 */
    val decorRemainingSeconds: Int
        get() {
            val left = decorCountdownTotalSeconds - decorElapsedSeconds
            if (left <= 0) return 0
            if (left > decorCountdownTotalSeconds) return decorCountdownTotalSeconds
            return left
        }

    /** 按墙钟推算的装饰步骤索引（0-based，最大 59）。 */
    val decorDisplayStepIndex: Int get() = decorStepIndexForElapsed(decorElapsedSeconds)

    /** 由已过秒数计算装饰步骤索引。 */
    fun decorStepIndexForElapsed(elapsedSeconds: Int): Int {
        if (elapsedSeconds <= 0) return 0
        val last = decorStepCount - 1
        val index = elapsedSeconds * last / decorCountdownTotalSeconds
        if (index < 0) return 0
        if (index > last) return last
        return index
    }

    /** 无锚点时写入「现在」，兼容旧本地数据。 */
    fun ensureWaitStartedAt() {
        if (waitStartedAtMs > 0L) return
        waitStartedAtMs = nowMs()
        if (mvId > 0L) persist()
    }

    /** 页面初始化用的同步快照（与 TRY_FREE_CHANGED 的 data 相同）。 */
    fun snapshot(): JSONObject = JSONObject()
        .put("mvId", mvId)
        .put("isVisible", isVisible)
        .put("coverImage", coverImage)
        .put("status", status.value)
        .put("stage", stage.value)
        .put("resultUrl", resultUrl)
        .put("fourViewImg", fourViewImg)
        .put("firstFrameImg", firstFrameImg)
        .put("waitStartedAtMs", waitStartedAtMs)
        .put("progress", progress)
        .put("statusTextKey", statusTextKey)

    /**
     * 初始化：恢复本地任务并视情况启动轮询；登出 / 换号时清任务（经 AccountHooks）。
     * 宿主（根页面）重建后再次调用时，重新挂上轮询定时器。
     */
    fun init() {
        Svc.ensureBound()
        // 登出 / 换号后不能残留浮窗
        AccountHooks.clearTryFreeTask = { clearTask() }
        val rebind = hostBinding.needsBind()
        if (!inited) {
            inited = true
            restoreFromStorage()
        }
        if (!rebind) return
        // 旧宿主上的定时器 / 请求已失效
        pollTimer = null
        pollingGeneration = -1
        if (isVisible) {
            notifyChanged()
            Svc.launch {
                pollOnce()
                startPolling()
            }
        }
    }

    /** 提交试用成功后登记任务，供主框架浮窗展示。 */
    fun startTask(
        taskMvId: Long,
        taskCoverImage: String,
        initialStage: TrialMvStage = TrialMvStage.fourView,
        templateTag: String = "",
        photoSource: String = "",
    ) {
        if (taskMvId <= 0L) return
        reportedGenerateMvId = 0L
        this.templateTag = templateTag
        this.photoSource = photoSource
        mvId = taskMvId
        coverImage = taskCoverImage
        status = TrialMvStatus.generating
        stage = initialStage
        resultUrl = ""
        fourViewImg = ""
        firstFrameImg = ""
        // 新任务登记时刻作为全局倒计时锚点；同 mvId 重试不会再走 startTask
        waitStartedAtMs = nowMs()
        persist()
        notifyChanged()
        startPolling()
    }

    /** 清除活跃任务并停止轮询。 */
    fun clearTask() {
        stopPolling()
        val had = mvId > 0L
        reportedGenerateMvId = 0L
        templateTag = ""
        photoSource = ""
        mvId = 0L
        coverImage = ""
        status = TrialMvStatus.generating
        stage = TrialMvStage.fourView
        resultUrl = ""
        fourViewImg = ""
        firstFrameImg = ""
        waitStartedAtMs = 0L
        clearStorage()
        if (had) notifyChanged()
    }

    /** 进入播放页时消费同源试用任务，关闭主框架悬浮球。 */
    fun clearIfMatching(id: Long) {
        if (id > 0L && mvId == id) clearTask()
    }

    /**
     * 用详情同步任务状态（等待页轮询与本服务轮询共用）。
     * [detail] 为 `/member/trial-mv/detail/{mvId}` 的 data：
     * {mvId, status, trialStage, coverImage, fourViewImg?, firstFrameImg?, resultUrl?, …}
     */
    fun applyDetail(detail: JSONObject) {
        applyDetail(
            detailMvId = detail.jLong("mvId", 0L),
            detailStatus = TrialMvStatus.fromValue(detail.jInt("status", 1)),
            detailStage = TrialMvStage.fromValue(detail.jInt("trialStage", 1)),
            detailCoverImage = detail.jStr("coverImage"),
            detailResultUrl = detail.jStrOrNull("resultUrl"),
            detailFourViewImg = detail.jStrOrNull("fourViewImg"),
            detailFirstFrameImg = detail.jStrOrNull("firstFrameImg"),
        )
    }

    fun applyDetail(
        detailMvId: Long,
        detailStatus: TrialMvStatus,
        detailStage: TrialMvStage,
        detailCoverImage: String = "",
        detailResultUrl: String? = null,
        detailFourViewImg: String? = null,
        detailFirstFrameImg: String? = null,
    ) {
        if (detailMvId <= 0L) return
        mvId = detailMvId
        if (detailCoverImage.isNotEmpty()) coverImage = detailCoverImage
        status = detailStatus
        stage = detailStage
        if (!detailResultUrl.isNullOrEmpty()) resultUrl = detailResultUrl
        if (!detailFourViewImg.isNullOrEmpty()) fourViewImg = detailFourViewImg
        if (!detailFirstFrameImg.isNullOrEmpty()) firstFrameImg = detailFirstFrameImg

        if (detailStatus == TrialMvStatus.retryableFailed || detailStatus == TrialMvStatus.completed) {
            stopPolling()
            reportTrialGenerateIfNeeded(detailMvId, detailStatus)
        }
        notifyChanged()
    }

    private fun reportTrialGenerateIfNeeded(detailMvId: Long, detailStatus: TrialMvStatus) {
        if (reportedGenerateMvId == detailMvId) return
        reportedGenerateMvId = detailMvId
        val isSuccess = detailStatus == TrialMvStatus.completed
        UmengUtils.logEvent(
            "trial_generate",
            mapOf(
                "version" to "v1",
                "result" to if (isSuccess) "success" else "fail",
                "tag" to templateTag,
                "photo_source" to photoSource,
            ),
        )
    }

    /** 拉取一次任务详情。失败只记日志，轮询继续。 */
    suspend fun pollOnce() = onHost { pollOnceImpl() }

    private suspend fun pollOnceImpl() {
        val id = mvId
        if (id <= 0L) return
        val detail = try {
            WorksApi.getTrialDetail(id)
        } catch (e: Throwable) {
            null
        }
        if (detail == null) {
            DevLog.logger("$TAG.pollOnce error")
            return
        }
        // 请求期间任务被清除或换成了别的任务：旧结果丢弃
        if (mvId != id) return
        applyDetail(detail)
    }

    /** 打开已完成试用 MV 的作品库详情播放页。 */
    fun openCompletedMv(id: Long, replaceCurrent: Boolean = false) {
        if (id <= 0L) {
            AppNav.popToRoot()
            return
        }
        val arguments = JSONObject().put("id", id).put("type", "push").put("dataType", 0)
        val pager = currentPager() ?: AppNav.host ?: return
        if (replaceCurrent && !AppNav.isOnRoot()) {
            pager.nav.replace(Routes.mvListDetail, arguments)
        } else {
            pager.nav.push(Routes.mvListDetail, arguments)
        }
    }

    /** 浮窗点击：成功态进作品库详情并清除任务，其余进等待页。 */
    fun onFloatingBannerTap() {
        val id = mvId
        if (id <= 0L) return

        val floatResult = when (status) {
            TrialMvStatus.completed -> "complete"
            TrialMvStatus.retryableFailed -> "failed"
            TrialMvStatus.generating -> "generating"
        }
        UmengUtils.logEventButtonClick("trial_float_card", pageName = "/home", result = floatResult)

        if (status == TrialMvStatus.completed) {
            clearTask()
            openCompletedMv(id)
            return
        }
        val initialState = if (status == TrialMvStatus.retryableFailed) "failed" else "loading"
        val pager = currentPager() ?: AppNav.host ?: return
        pager.nav.push(Routes.tryFreeGenWaiting, genWaitingArgs(id, initialState, stage.waitingStepIndex))
    }

    /**
     * 试用等待页入参（TryFreeGenWaitingArgs）：
     * {mvId, initialState: "loading" | "failed", initialStepIndex}
     */
    fun genWaitingArgs(mvId: Long, initialState: String, initialStepIndex: Int): JSONObject = JSONObject()
        .put("mvId", mvId)
        .put("initialState", initialState)
        .put("initialStepIndex", initialStepIndex)

    private fun startPolling() {
        stopPolling()
        if (!isVisible) return
        if (status == TrialMvStatus.completed || status == TrialMvStatus.retryableFailed) return
        val generation = pollGeneration
        firePoll(generation)
        scheduleNext(generation)
    }

    private fun scheduleNext(generation: Int) {
        pollTimer = Svc.setTimeout(POLL_INTERVAL_MS) {
            pollTimer = null
            if (generation != pollGeneration) return@setTimeout
            scheduleNext(generation)
            firePoll(generation)
        }
    }

    private fun firePoll(generation: Int) {
        // 同一代次上一次请求还没回来就不叠加
        if (pollingGeneration == generation) return
        pollingGeneration = generation
        Svc.launch {
            try {
                if (generation == pollGeneration) pollOnce()
            } finally {
                if (pollingGeneration == generation) pollingGeneration = -1
            }
        }
    }

    private fun stopPolling() {
        pollGeneration++
        Svc.clearTimeout(pollTimer)
        pollTimer = null
    }

    private fun notifyChanged() {
        Svc.emit(Events.TRY_FREE_CHANGED, snapshot())
        MvService.syncTrialItemFromService(mvId)
    }

    private fun persist() {
        Sp.setLong(StorageKey.tryFreeActiveMvId, mvId)
        Sp.setString(StorageKey.tryFreeActiveCoverImage, coverImage)
        Sp.setLong(StorageKey.tryFreeWaitStartedAtMs, waitStartedAtMs)
    }

    private fun restoreFromStorage() {
        val storedMvId = Sp.getLong(StorageKey.tryFreeActiveMvId, 0L)
        val storedCover = Sp.getString(StorageKey.tryFreeActiveCoverImage, "")
        if (storedMvId <= 0L) return
        mvId = storedMvId
        coverImage = storedCover
        waitStartedAtMs = Sp.getLong(StorageKey.tryFreeWaitStartedAtMs, 0L)
        // 旧版本无锚点：补写当前时刻，避免每次进等待页都从 04:00 重开
        ensureWaitStartedAt()
    }

    private fun clearStorage() {
        Sp.remove(StorageKey.tryFreeActiveMvId)
        Sp.remove(StorageKey.tryFreeActiveCoverImage)
        Sp.remove(StorageKey.tryFreeWaitStartedAtMs)
    }
}
