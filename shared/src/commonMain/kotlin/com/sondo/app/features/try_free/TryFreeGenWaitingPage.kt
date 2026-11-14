package com.sondo.app.features.try_free

import com.sondo.app.base.BasePager
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.whiteA
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.i18n.trParams
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.nowMs
import com.sondo.app.data.models.jInt
import com.sondo.app.data.models.jLong
import com.sondo.app.data.models.jStr
import com.sondo.app.features.try_free.data.TrialMvApi
import com.sondo.app.features.try_free.data.TrialMvDetailEntity
import com.sondo.app.features.try_free.data.TryFreeGenWaitingPageState
import com.sondo.app.features.try_free.widgets.TryFreeCloseButton
import com.sondo.app.features.try_free.widgets.TryFreeColors
import com.sondo.app.features.try_free.widgets.TryFreeSpinner
import com.sondo.app.service.PushService
import com.sondo.app.service.TrialMvStage
import com.sondo.app.service.TrialMvStatus
import com.sondo.app.service.TryFreeTaskService
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.ui.anim.SDBreathing
import com.sondo.app.ui.atoms.SDAssetImage
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.clearTimeout
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import kotlin.random.Random

/**
 * 试用生成等待页（Dart `TryFreeGenWaitingPage` + `TryFreeGenWaitingController`）。
 *
 * 入参（`TryFreeTaskService.genWaitingArgs`）：`{mvId: Long, initialState: "loading"|"failed", initialStepIndex: Int}`；
 * mvId 为 0 时不轮询。关闭 / 返回 / 「完成后通知我」都回到上一页（主框架），任务继续在主框架浮窗里显示。
 */
@Page(Routes.tryFreeGenWaiting)
internal class TryFreeGenWaitingPage : BasePager() {

    private val trackPage = UmengUtils.routePath(Routes.tryFreeGenWaiting)

    /** 试用 MV 任务 ID */
    private var mvId = 0L

    var pageState by observable(TryFreeGenWaitingPageState.loading)

    /** 后端阶段对应的步骤索引（与装饰列表解耦） */
    var activeStepIndex by observable(2)

    var isRetrying by observable(false)

    /** 本地预计剩余秒数；归零后顶部改为 Just a moment */
    var remainingSeconds by observable(countdownTotalSeconds)

    /** 装饰步骤当前选中索引（始终居中） */
    var displayStepIndex by observable(0)

    /** 步骤列表累计上滑像素（只增不减） */
    var scrollPixels by observable(0f)

    private var navigatedToPlay = false
    private var closed = false
    private var waitStartedAtMs = 0L

    private var pollTimer: String? = null
    private var pollGeneration = 0
    private var countdownTimer: String? = null
    private var stepAdvanceTimer: String? = null
    private var decorGeneration = 0

    // 步骤滚动动画
    private var lastSyncedIndex = -1
    private var animFrom = 0f
    private var animTo = 0f
    private var animStartMs = 0L
    private var animDurationMs = 480f
    private var animating = false

    /** 顶部倒计时文案：进行中为 `MM:SS left`，归零为最后一条步骤文案。 */
    val countdownText: String
        get() {
            if (remainingSeconds <= 0) return waitStepKeys.last().tr
            val minutes = (remainingSeconds / 60).toString().padStart(2, '0')
            val seconds = (remainingSeconds % 60).toString().padStart(2, '0')
            return "try_free_wait_time_left".trParams("time" to "$minutes:$seconds")
        }

    override fun pageBackgroundColor(): Color = TryFreeColors.bgWaiting

    // 系统返回与关闭按钮一致
    override fun interceptBack(): Boolean = true

    override fun onBackPressed(): Boolean {
        onCloseTap()
        return true
    }

    override fun created() {
        super.created()
        val params = pagerData.params
        mvId = params.jLong("mvId", 0L)
        pageState = if (params.jStr("initialState") == "failed") {
            TryFreeGenWaitingPageState.failed
        } else {
            TryFreeGenWaitingPageState.loading
        }
        activeStepIndex = params.jInt("initialStepIndex", 2).coerceIn(0, stepKeys.size - 1)
        if (pageState == TryFreeGenWaitingPageState.loading) startDecorTimers()
        if (mvId > 0L) startPolling()
    }

    override fun pageWillDestroy() {
        closed = true
        stopDecorTimers()
        stopPolling()
        animating = false
        super.pageWillDestroy()
    }

    /** 关闭页面（回到主框架，任务转为浮窗）。 */
    fun onCloseTap() {
        stopDecorTimers()
        stopPolling()
        if (nav.stack().size > 1) nav.pop() else nav.popToRoot()
    }

    fun setActiveStep(index: Int) {
        val next = index.coerceIn(0, stepKeys.size - 1)
        if (activeStepIndex == next) return
        activeStepIndex = next
    }

    fun setFailed() {
        if (pageState == TryFreeGenWaitingPageState.failed) return
        pageState = TryFreeGenWaitingPageState.failed
        stopDecorTimers()
    }

    fun setLoading() {
        if (pageState == TryFreeGenWaitingPageState.loading) return
        pageState = TryFreeGenWaitingPageState.loading
        startDecorTimers()
    }

    /** 「完成后通知我」：先申请通知权限，之后停止轮询并回到首页。 */
    fun onNotifyWhenReadyTap() {
        launch {
            try {
                val notifyResult = PushService.requestNotifyPermission(pager = this@TryFreeGenWaitingPage)
                UmengUtils.logEventButtonClick("trial_notify", pageName = trackPage, result = notifyResult)
            } catch (e: Throwable) {
                DevLog.logger("push fcm error")
            }
            if (closed) return@launch
            stopDecorTimers()
            stopPolling()
            nav.popToRoot()
        }
    }

    /** 失败态重试：重跑当前 trial_stage。 */
    fun onRetryTap() {
        if (mvId <= 0L || isRetrying) return
        UmengUtils.logEventButtonClick("trial_retry", pageName = trackPage, result = "wait_page")
        isRetrying = true
        launch {
            val result = TrialMvApi.retryStage(mvId)
            if (closed) return@launch
            result.fold(
                onSuccess = { success ->
                    if (!success) {
                        toast("try_free_gen_failed_hint".tr)
                    } else {
                        setLoading()
                        startPolling()
                    }
                },
                onFailure = { e ->
                    DevLog.logger("TryFreeGenWaitingPage.onRetryTap error: $e")
                    toast("try_free_gen_failed_hint".tr)
                },
            )
            isRetrying = false
        }
    }

    // ------------------------------------------------------------------ 轮询

    private fun startPolling() {
        stopPolling()
        val gen = pollGeneration
        pollDetailOnce()
        schedulePoll(gen)
    }

    private fun schedulePoll(gen: Int) {
        pollTimer = postDelayed(POLL_INTERVAL_MS) {
            pollTimer = null
            if (gen != pollGeneration || closed) return@postDelayed
            pollDetailOnce()
            schedulePoll(gen)
        }
    }

    private fun stopPolling() {
        pollGeneration++
        pollTimer?.let { clearTimeout(it) }
        pollTimer = null
    }

    /** 拉一次详情，同步步骤与终态。 */
    fun pollDetailOnce() {
        if (mvId <= 0L || closed) return
        launch {
            val result = TrialMvApi.getDetail(mvId)
            if (closed) return@launch
            result.fold(
                onSuccess = { detail ->
                    syncTaskService(detail)
                    setActiveStep(detail.stage.waitingStepIndex)
                    if (detail.status == TrialMvStatus.retryableFailed) {
                        stopPolling()
                        setFailed()
                        return@fold
                    }
                    if (detail.status == TrialMvStatus.completed) navigateToPlayPage(detail.mvId)
                },
                onFailure = { e -> DevLog.logger("TryFreeGenWaitingPage.pollDetail error: $e") },
            )
        }
    }

    /** 轮询结果同步到主框架浮窗服务。 */
    private fun syncTaskService(detail: TrialMvDetailEntity) {
        TryFreeTaskService.applyDetail(
            detailMvId = detail.mvId,
            detailStatus = detail.status,
            detailStage = detail.stage,
            detailCoverImage = detail.coverImage,
            detailResultUrl = detail.resultUrl,
            detailFourViewImg = detail.fourViewImg,
            detailFirstFrameImg = detail.firstFrameImg,
        )
    }

    /** 生成完成：替换当前页为作品库详情播放页，并清除浮窗任务。 */
    private fun navigateToPlayPage(taskMvId: Long) {
        if (navigatedToPlay || closed || taskMvId <= 0L) return
        navigatedToPlay = true
        stopDecorTimers()
        stopPolling()
        setActiveStep(TrialMvStage.merge.waitingStepIndex)
        TryFreeTaskService.clearTask()
        TryFreeTaskService.openCompletedMv(taskMvId, replaceCurrent = true)
    }

    // ------------------------------------------------------------------ 装饰倒计时 / 步骤滚动

    /** 按任务开始墙钟启动倒计时与步进；退出再进对齐已过进度。 */
    private fun startDecorTimers() {
        stopDecorTimers()
        resolveWaitStartedAt()
        // 再进页面时先跳到墙钟对应步骤，避免从 0 重开
        displayStepIndex = 0
        syncDecorFromWallClock(jumpToWallStep = true)
        onDisplayStepChanged()
        val gen = decorGeneration
        scheduleCountdown(gen)
        scheduleNextStepAdvance(gen)
    }

    private fun stopDecorTimers() {
        decorGeneration++
        countdownTimer?.let { clearTimeout(it) }
        countdownTimer = null
        stepAdvanceTimer?.let { clearTimeout(it) }
        stepAdvanceTimer = null
    }

    private fun scheduleCountdown(gen: Int) {
        countdownTimer = postDelayed(1000) {
            countdownTimer = null
            if (closed || gen != decorGeneration) return@postDelayed
            syncDecorFromWallClock(jumpToWallStep = false)
            if (remainingSeconds > 0) scheduleCountdown(gen)
        }
    }

    private fun resolveWaitStartedAt() {
        TryFreeTaskService.ensureWaitStartedAt()
        waitStartedAtMs = TryFreeTaskService.waitStartedAtMs
        if (waitStartedAtMs <= 0L) waitStartedAtMs = nowMs()
    }

    private fun decorElapsedSeconds(): Int {
        if (waitStartedAtMs <= 0L) return 0
        val elapsed = ((nowMs() - waitStartedAtMs) / 1000L).toInt()
        return if (elapsed < 0) 0 else elapsed
    }

    private fun syncDecorFromWallClock(jumpToWallStep: Boolean) {
        val elapsed = decorElapsedSeconds()
        val left = countdownTotalSeconds - elapsed
        remainingSeconds = if (left <= 0) 0 else if (left > countdownTotalSeconds) countdownTotalSeconds else left
        // 倒计时 tick 只刷新剩余秒；步骤由进入时对齐 + 随机追赶墙钟目标
        if (jumpToWallStep) displayStepIndex = TryFreeTaskService.decorStepIndexForElapsed(elapsed)
    }

    /** 随机 2–5 秒推进一格；墙钟进度只作下限，不挡住继续上滚。 */
    private fun scheduleNextStepAdvance(gen: Int) {
        stepAdvanceTimer?.let { clearTimeout(it) }
        stepAdvanceTimer = null
        if (displayStepIndex >= waitStepKeys.size - 1) return
        val delaySeconds = 2 + Random.nextInt(4)
        stepAdvanceTimer = postDelayed(delaySeconds * 1000) {
            stepAdvanceTimer = null
            if (closed || gen != decorGeneration || pageState != TryFreeGenWaitingPageState.loading) return@postDelayed
            val wallTarget = TryFreeTaskService.decorStepIndexForElapsed(decorElapsedSeconds())
            val last = waitStepKeys.size - 1
            // 落后于墙钟则抬到墙钟；否则自由前进一格
            if (displayStepIndex < wallTarget) {
                displayStepIndex = wallTarget.coerceIn(0, last)
                onDisplayStepChanged()
            } else if (displayStepIndex < last) {
                displayStepIndex++
                onDisplayStepChanged()
            }
            scheduleNextStepAdvance(gen)
        }
    }

    /** 步骤索引变化 → 列表滚动到对应位置（Dart `_scheduleSync` / `_scrollToIndex`）。 */
    private fun onDisplayStepChanged() {
        val target = displayStepIndex
        if (lastSyncedIndex == -1) {
            // 首次进入：直接落位
            lastSyncedIndex = target
            scrollPixels = target * SLOT_EXTENT
            return
        }
        if (target == lastSyncedIndex) return
        lastSyncedIndex = target
        val targetPixels = target * SLOT_EXTENT
        if (kotlin.math.abs(targetPixels - scrollPixels) < 0.5f) return
        // 回跳：直接落到目标（不做向下滚）
        if (targetPixels < scrollPixels) {
            animating = false
            scrollPixels = targetPixels
            return
        }
        animFrom = scrollPixels
        animTo = targetPixels
        val steps = (animTo - animFrom) / SLOT_EXTENT
        animDurationMs = (480f * steps).coerceIn(280f, 1200f)
        animStartMs = nowMs()
        if (!animating) {
            animating = true
            tickScroll()
        }
    }

    private fun tickScroll() {
        if (closed || !animating) return
        val t = ((nowMs() - animStartMs).toFloat() / animDurationMs).coerceIn(0f, 1f)
        val inv = 1f - t
        val eased = 1f - inv * inv * inv
        scrollPixels = animFrom + (animTo - animFrom) * eased
        if (t >= 1f) {
            animating = false
            return
        }
        postDelayed(16) { tickScroll() }
    }

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    flex(1f)
                    paddingTop(ctx.statusBarHeight)
                    paddingBottom(ctx.safeBottom)
                }
                // 顶部关闭栏
                View {
                    attr {
                        height(56f)
                        flexDirectionRow()
                        justifyContentFlexEnd()
                        alignItemsCenter()
                        paddingRight(12f)
                    }
                    TryFreeCloseButton { ctx.onCloseTap() }
                }
                vif({ ctx.pageState == TryFreeGenWaitingPageState.loading }) {
                    genWaitingLoadingView(ctx)
                }
                vif({ ctx.pageState == TryFreeGenWaitingPageState.failed }) {
                    genWaitingFailedView(ctx)
                }
            }
        }
    }

    companion object {
        /** 生成步骤文案 key（与 TrialMvStage.stageTextKeys 一致）。 */
        val stepKeys = TrialMvStage.stageTextKeys

        /** 装饰步骤文案 key：try_free_wait_step_01 … _60。 */
        val waitStepKeys: List<String> = List(TryFreeTaskService.decorStepCount) { i ->
            "try_free_wait_step_" + (i + 1).toString().padStart(2, '0')
        }

        const val countdownTotalSeconds = TryFreeTaskService.decorCountdownTotalSeconds
        private const val POLL_INTERVAL_MS = 3000

        /** 视窗可见槽位数 */
        const val VISIBLE_COUNT = 5

        /** 单槽高度（字高约 20 + 行距 8） */
        const val SLOT_EXTENT = 28f
    }
}

/** 等待正常态：光球 + 倒计时 + 步骤滚动 + 「完成后通知我」。 */
private fun ViewContainer<*, *>.genWaitingLoadingView(ctx: TryFreeGenWaitingPage) {
    View {
        attr {
            flex(1f)
            padding(60f, 0f, 24f, 0f)
            alignItemsCenter()
        }
        View {
            attr {
                flex(1f)
                justifyContentCenter()
                alignItemsCenter()
            }
            genWaitingOrb()
            View {
                attr { marginTop(20f); width(300f) }
                genWaitingStepList(ctx)
            }
        }
        View {
            attr {
                marginTop(16f)
                size(300f, 48f)
                borderRadius(24f)
                backgroundColor(TryFreeColors.closeButtonBg)
                allCenter()
            }
            event { click { ctx.onNotifyWhenReadyTap() } }
            Text {
                attr {
                    text("try_free_notify_when_ready".tr)
                    fontSize(12f)
                    fontFamily("Poppins-SemiBold")
                    color(whiteA(0.8f))
                }
            }
        }
    }
}

/** 中心光球 + 星芒（1.2s easeInOut 放大到 1.15 往返）。 */
private fun ViewContainer<*, *>.genWaitingOrb() {
    View {
        attr {
            size(300f, 180f)
            allCenter()
        }
        Image {
            attr {
                size(180f, 180f)
                src(ImageUri.commonAssets(AssetsImage.tryFreeWaitingOrb))
                resizeCover()
            }
        }
        View {
            attr {
                absolutePositionAllZero()
                allCenter()
            }
            SDBreathing {
                attr {
                    minScale = 1f
                    maxScale = 1.15f
                    periodS = 2.4f
                }
                content {
                    SDAssetImage(AssetsImage.tryFreeWaitingSparkle, 70f)
                }
            }
        }
    }
}

/** 本地倒计时 + 自下而上「无限加载」式步骤滚动（Dart `TryFreeGenWaitingStepList`）。 */
private fun ViewContainer<*, *>.genWaitingStepList(ctx: TryFreeGenWaitingPage) {
    val slot = TryFreeGenWaitingPage.SLOT_EXTENT
    val visible = TryFreeGenWaitingPage.VISIBLE_COUNT
    Text {
        attr {
            width(300f)
            text(ctx.countdownText)
            textAlignCenter()
            fontSize(18f)
            lineHeight(18f * 1.2f)
            fontFamily("Poppins-SemiBold")
            color(Color.WHITE)
        }
    }
    // 固定视窗：新项从底部裁剪边滚上来
    View {
        attr {
            marginTop(16f)
            size(300f, slot * visible)
            overflow(true)
        }
        View {
            attr {
                absolutePosition(left = 0f, right = 0f)
                top(-stepFrac(ctx.scrollPixels))
            }
            for (i in 0 until visible + 1) {
                View {
                    attr {
                        size(300f, slot)
                        flexDirectionRow()
                        justifyContentCenter()
                        alignItemsFlexStart()
                    }
                    vif({ stepKeyAt(ctx.scrollPixels, i) != null }) {
                        View {
                            attr {
                                flexDirectionRow()
                                alignItemsFlexStart()
                                maxWidth(300f)
                            }
                            Image {
                                attr {
                                    size(16f, 16f)
                                    src(ImageUri.commonAssets(AssetsImage.arrowRight))
                                    tintColor(whiteA(stepOpacity(ctx.scrollPixels, i)))
                                }
                            }
                            Text {
                                attr {
                                    marginLeft(4f)
                                    maxWidth(280f)
                                    text(stepKeyAt(ctx.scrollPixels, i)?.tr ?: "")
                                    fontSize(12f)
                                    lineHeight(12f * 1.2f)
                                    fontFamily("Poppins-Medium")
                                    color(whiteA(stepOpacity(ctx.scrollPixels, i)))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun stepBase(scrollPixels: Float): Int =
    kotlin.math.floor(scrollPixels / TryFreeGenWaitingPage.SLOT_EXTENT).toInt()

private fun stepFrac(scrollPixels: Float): Float =
    scrollPixels - stepBase(scrollPixels) * TryFreeGenWaitingPage.SLOT_EXTENT

/** 第 [slotIndex] 个槽位对应的文案 key；越界为 null（空槽）。 */
private fun stepKeyAt(scrollPixels: Float, slotIndex: Int): String? {
    val index = stepBase(scrollPixels) - 2 + slotIndex
    val keys = TryFreeGenWaitingPage.waitStepKeys
    return if (index < 0 || index >= keys.size) null else keys[index]
}

/** 按距中心连续插值透明度：-2→0.2, -1→0.4, 0→1.0, +1→0.6, +2→0.4。 */
private fun stepOpacity(scrollPixels: Float, slotIndex: Int): Float {
    val distance = (slotIndex - 2) - stepFrac(scrollPixels) / TryFreeGenWaitingPage.SLOT_EXTENT
    val stops = floatArrayOf(-2f, -1f, 0f, 1f, 2f)
    val values = floatArrayOf(0.2f, 0.4f, 1f, 0.6f, 0.4f)
    val d = distance.coerceIn(stops.first(), stops.last())
    for (i in 0 until stops.size - 1) {
        if (d <= stops[i + 1]) {
            val t = (d - stops[i]) / (stops[i + 1] - stops[i])
            return values[i] + (values[i + 1] - values[i]) * t
        }
    }
    return values.last()
}

/** 失败态：插图 + 文案 + 重试。 */
private fun ViewContainer<*, *>.genWaitingFailedView(ctx: TryFreeGenWaitingPage) {
    View {
        attr {
            flex(1f)
            alignItemsCenter()
        }
        View { attr { height(160f) } }
        SDAssetImage(AssetsImage.tryFreeGenerationFailed, 120f)
        View {
            attr {
                marginTop(20f)
                width(300f)
                alignItemsCenter()
            }
            Text {
                attr {
                    text("generation_failed".tr)
                    textAlignCenter()
                    fontSize(18f)
                    lineHeight(18f * 1.2f)
                    fontFamily("Poppins-SemiBold")
                    color(whiteA(0.8f))
                }
            }
            Text {
                attr {
                    marginTop(12f)
                    text("try_free_gen_failed_hint".tr)
                    textAlignCenter()
                    fontSize(12f)
                    lineHeight(12f * 1.4f)
                    fontFamily("Poppins-Medium")
                    color(TryFreeColors.secondaryText)
                }
            }
            View {
                attr {
                    marginTop(24f)
                    size(100f, 32f)
                    borderRadius(16f)
                    allCenter()
                    backgroundColor(
                        if (ctx.isRetrying) Color(0xF3, 0xEF, 0xF7, 0.5f) else TryFreeColors.btnPrimary,
                    )
                }
                event { click { if (!ctx.isRetrying) ctx.onRetryTap() } }
                vif({ ctx.isRetrying }) {
                    TryFreeSpinner(16f, 2f, TryFreeColors.btnPrimaryText)
                }
                vif({ !ctx.isRetrying }) {
                    Text {
                        attr {
                            text("retry".tr)
                            fontSize(12f)
                            fontFamily("Poppins-SemiBold")
                            color(TryFreeColors.btnPrimaryText)
                        }
                    }
                }
            }
        }
    }
}
