package com.sondo.app.features.library

import com.sondo.app.base.BasePager
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.storage.Sp
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.EmailHelper
import com.sondo.app.core.utils.delayMs
import com.sondo.app.data.models.DanceFeedbackTag
import com.sondo.app.data.models.MvFeedbackModel
import com.sondo.app.data.models.MvType
import com.sondo.app.data.models.SquareModel
import com.sondo.app.features.library.data.LibraryVideoDetailApi
import com.sondo.app.features.library.widgets.MvRatingBanner
import com.sondo.app.features.library.widgets.TryFreeSubscribeBanner
import com.sondo.app.features.library.widgets.showMvFeedbackSheet
import com.sondo.app.features.library.widgets.watchDialogDismiss
import com.sondo.app.nativeapi.SDAppModule
import com.sondo.app.service.ConfigService
import com.sondo.app.service.LibraryTabRefreshService
import com.sondo.app.service.MvService
import com.sondo.app.service.PlayerService
import com.sondo.app.service.UserStore
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.ui.atoms.SDLoading
import com.sondo.app.ui.dialog.showConfirmDialog
import com.sondo.app.ui.nav.SDNavBack
import com.sondo.app.ui.overlay.DialogEntry
import com.sondo.app.ui.video.AppVideoController
import com.sondo.app.ui.video.SDListMvVideoPlayer
import com.sondo.app.ui.works.guide.GuideManager
import com.sondo.app.ui.works.guide.GuideStep
import com.sondo.app.ui.works.guide.GuideStepController
import com.sondo.app.ui.works.guide.GuideTipDirection
import com.sondo.app.ui.works.showReportTagOptions
import com.sondo.app.base.Events
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.timer.clearTimeout
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.DivView
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.PageList
import com.tencent.kuikly.core.views.PageListView
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import kotlin.math.floor

/** 首次进入自己 Library MV 成片播放页、本地未看过时才弹编辑引导。 */
fun shouldShowPlaybackEditGuide(alreadyShown: Boolean, isLibraryPage: Boolean, isMv: Boolean): Boolean =
    !alreadyShown && isLibraryPage && isMv

/** 试用 MV 订阅引导策略（Dart `TrialSubscribeFunnel`）。 */
class TrialSubscribeFunnel(
    private val isSubscribed: () -> Boolean,
    private val hasVisitedSubscribePage: () -> Boolean,
    private val isTrialMv: () -> Boolean,
) {
    /** 离开页面时是否应自动跳转订阅页。 */
    val shouldNavigateOnLeave: Boolean get() = isTrialMv() && !isSubscribed() && !hasVisitedSubscribePage()

    /** 播放结束时是否应自动跳转订阅页。 */
    val shouldNavigateOnPlayEnd: Boolean get() = isTrialMv() && !isSubscribed() && !hasVisitedSubscribePage()

    /** 末段是否应展示订阅卡片。 */
    val shouldShowSubscribeCardNearEnd: Boolean get() = isTrialMv() && !isSubscribed() && hasVisitedSubscribePage()

    /** 根据播放进度判断是否应展示末段订阅卡片。 */
    fun shouldShowCardNearEnd(positionMs: Long, durationMs: Long, cardAlreadyShown: Boolean): Boolean {
        if (!shouldShowSubscribeCardNearEnd || cardAlreadyShown) return false
        if (durationMs < 1000) return false
        val remaining = durationMs - positionMs
        return remaining in 0..3000
    }
}

/**
 * Library 视频详情页（Dart `MvListDetailPage` + `MvListDetailController`）：竖滑全屏播放、评分反馈、画质升级、编辑与删除。
 *
 * 页面参数：`{id: Long, type: "library"|"push", listKey?: String, dataType: Int(0 = MV)}`；
 * 列表经 `MvService.takeDetailList(listKey)` 取；`type == "push"` 时按 id 拉详情（未落库时每秒重试）。
 */
@Page(Routes.mvListDetail)
internal class MvListDetailPage : BasePager() {
    internal val mvList by observableList<SquareModel>()
    internal var currentIndex by observable(0)
    internal var listReady by observable(false)
    internal var isScrolling by observable(false)
    internal var showRatingBanner by observable(false)
    /** UserStore.isSubscribed 的响应式副本：订阅后返回本页，试用 MV 的分享按钮要重新出现（Dart Obx） */
    internal var subscribed by observable(false)
    internal var showSubscribeBanner by observable(false)
    internal var isPlaybackEditGuideShowing by observable(false)
    internal var pageType by observable("")
    internal var dataType by observable(0)
    internal var sharingUrls by observable(MvService.sharingUrls)
    internal var downloadingUrls by observable(MvService.downloadingUrls)

    private var currentPositionMs = 0L
    private var currentDurationMs = 0L

    internal var pageRef: ViewRef<PageListView<*, *>>? = null
    internal var editRef: ViewRef<DivView>? = null

    /** mv id → 当前持有的播放控制器（只有当前页挂载解码器）。 */
    private val videoControllers = HashMap<Long, AppVideoController>()

    private val skippedRatingIds = HashSet<Long>()
    private val ratingChecking = HashSet<Long>()
    private var ratingTriggerInFlight = false
    private val recommendTags = HashMap<Int, List<DanceFeedbackTag>>()
    private var tagsLoaded = false

    private var subscribeCardShown = false
    private var subscribeNavigated = false
    private var leaveHandled = false
    private var reportedViewTrialResults = false

    private var playbackEditGuideTimer: String? = null
    private var playbackEditGuideScheduled = false
    private var playbackEditGuideKeyRetry = 0
    private var isLeavingPage = false
    private var closed = false
    /** 带结果关页时结果先于本页 appear 到达：记下要恢复的视频，appear 后再播（Dart 等路由回到本页后 playVideo）。 */
    private var resumeOnAppearId: Long? = null

    private val funnel = TrialSubscribeFunnel(
        isSubscribed = { isSubscribed },
        hasVisitedSubscribePage = { hasVisitedSubscribePage },
        isTrialMv = { isCurrentTrialMv },
    )

    val isSubscribed: Boolean get() = UserStore.isSubscribed
    val hasVisitedSubscribePage: Boolean get() = Sp.getBool(StorageKey.hasVisitedSubscribePage, false)

    val isCurrentTrialMv: Boolean
        get() {
            if (mvList.isEmpty()) return false
            val i = currentIndex
            if (i < 0 || i >= mvList.size) return false
            return mvList[i].trialMvFlag
        }

    internal val currentId: Long
        get() = mvList.getOrNull(currentIndex)?.id ?: 0L

    override fun pageBackgroundColor(): Color = Color.BLACK

    override fun created() {
        super.created()
        skippedRatingIds.addAll(loadSkippedRatingIds())
        subscribed = UserStore.isSubscribed
        on(Events.USER_CHANGED) { subscribed = UserStore.isSubscribed }
        on(Events.MV_BUSY_CHANGED) {
            sharingUrls = MvService.sharingUrls
            downloadingUrls = MvService.downloadingUrls
        }
        keepScreenOn(true)
        // 暂停选择歌曲和音频播放器
        PlayerService.launch { PlayerService.pause() }
        val args = pagerData.params
        pageType = args.optString("type", "")
        dataType = args.optInt("dataType", 0)
        val listKey = args.optString("listKey", "")
        if (listKey.isNotEmpty()) mvList.addAll(MvService.takeDetailList(listKey))
        val raw = args.opt("id")
        val id: Long? = when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        }
        launch { onReady(id) }
    }

    private suspend fun onReady(id: Long?) {
        if (pageType == "push" && id != null) {
            showLoading()
            try {
                val mv = getMvDetail(id)
                // 轮询返回时用户可能已退出，禁止再写列表
                if (closed) return
                if (mv != null) mvList.add(mv)
            } catch (e: Throwable) {
                DevLog.logger("getMvDetail exception: ${e.message}")
            } finally {
                hideLoading()
            }
        }
        if (closed) return
        if (id != null) {
            val index = mvList.indexOfFirst { it.id == id }
            if (index != -1 && index != 0) currentIndex = index
        }
        listReady = true
        if (mvList.isNotEmpty()) prefetchRatingStatus(currentIndex)
        reportViewTrialResultsIfNeeded()
        schedulePlaybackEditGuide()
    }

    override fun pageWillDestroy() {
        handlePagePop()
        closed = true
        cancelPlaybackEditGuideTimer()
        // 引导浮层不随页面自动拆，必须手动 hide
        if (GuideManager.isShowing) GuideManager.hide()
        keepScreenOn(false)
        videoControllers.values.forEach {
            try {
                it.pause()
            } catch (e: Throwable) {
                DevLog.logger("页面关闭时暂停视频失败: ${e.message}")
            }
        }
        videoControllers.clear()
        super.pageWillDestroy()
    }

    private fun keepScreenOn(on: Boolean) {
        try {
            acquireModule<SDAppModule>(SDAppModule.MODULE_NAME).setKeepScreenOn(on)
        } catch (e: Throwable) {
            DevLog.logger("keepScreenOn: ${e.message}")
        }
    }

    /** 推送进页时成片可能尚未落库，需轮询；页面销毁后必须停。 */
    private suspend fun getMvDetail(id: Long): SquareModel? {
        var res = LibraryVideoDetailApi.fetchMvDetail(id).getOrNull()
        while (res == null && !closed) {
            delayMs(1000)
            if (closed) return null
            res = LibraryVideoDetailApi.fetchMvDetail(id).getOrNull()
        }
        return res?.let { SquareModel.fromMv(it) }
    }

    /** 试用成片进入详情页时上报一次，覆盖等待页 / 浮窗 / 作品库入口。 */
    private fun reportViewTrialResultsIfNeeded() {
        if (reportedViewTrialResults) return
        if (!isCurrentTrialMv) return
        reportedViewTrialResults = true
        UmengUtils.logEvent("view_trial_results", mapOf("version" to "v1", "source" to pageType))
    }

    // ------------------------------------------------------------------ 编辑引导

    private fun guideAllowed(): Boolean = shouldShowPlaybackEditGuide(
        alreadyShown = Sp.getBool(StorageKey.isMvPlaybackEditGuideShown, false),
        isLibraryPage = pageType == "library",
        isMv = dataType == 0,
    )

    /** 列表就绪后延迟 2 秒再弹；2 秒内切页 / 点编辑 / 离开会取消。 */
    private fun schedulePlaybackEditGuide() {
        if (isLeavingPage || playbackEditGuideScheduled) return
        if (!guideAllowed()) return
        if (mvList.isEmpty()) return
        playbackEditGuideScheduled = true
        playbackEditGuideTimer = setTimeout(pagerId, 2000) {
            if (closed || isLeavingPage) return@setTimeout
            onPlaybackEditGuideDelayElapsed()
        }
    }

    private fun cancelPlaybackEditGuideTimer() {
        playbackEditGuideTimer?.let { clearTimeout(it) }
        playbackEditGuideTimer = null
    }

    private fun onPlaybackEditGuideDelayElapsed() {
        if (closed || isLeavingPage) return
        if (!guideAllowed()) return
        pauseVideo(currentId)
        isPlaybackEditGuideShowing = true
        showPlaybackEditGuide()
    }

    /** 编辑图标就绪后插入引导；未挂上则有限次等下一帧。 */
    private fun showPlaybackEditGuide() {
        if (closed || isLeavingPage) return
        if (editRef?.view == null) {
            if (playbackEditGuideKeyRetry >= 8) {
                isPlaybackEditGuideShowing = false
                playVideo(currentId)
                playbackEditGuideKeyRetry = 0
                return
            }
            playbackEditGuideKeyRetry++
            setTimeout(pagerId, 16) {
                if (closed || isLeavingPage) return@setTimeout
                showPlaybackEditGuide()
            }
            return
        }
        val controller = GuideStepController(
            listOf(
                GuideStep(
                    id = "playback_edit",
                    target = { editRef?.view },
                    title = "",
                    description = "mv_playback_edit_guide_title".tr,
                    direction = GuideTipDirection.bottom,
                    allowTapOverlayNext = false,
                ),
            ),
        )
        GuideManager.show(
            this,
            controller,
            onFinish = { onPlaybackEditGuideTargetTapped() },
            onClose = { onPlaybackEditGuideSkipped() },
        )
    }

    private fun markPlaybackEditGuideShown() {
        Sp.setBool(StorageKey.isMvPlaybackEditGuideShown, true)
    }

    /** Skip：关引导并继续播放。 */
    private fun onPlaybackEditGuideSkipped() {
        markPlaybackEditGuideShown()
        isPlaybackEditGuideShowing = false
        if (closed) return
        playVideo(currentId)
    }

    /** 点击高亮编辑图标：关引导并进入编辑页。 */
    private fun onPlaybackEditGuideTargetTapped() {
        markPlaybackEditGuideShown()
        isPlaybackEditGuideShowing = false
        if (closed) return
        onEditTap()
    }

    // ------------------------------------------------------------------ 评分

    private fun loadSkippedRatingIds(): List<Long> {
        val arr = Sp.getJsonArray(StorageKey.mvRatingSkippedIds) ?: return emptyList()
        val out = ArrayList<Long>()
        for (i in 0 until arr.length()) {
            when (val v = arr.opt(i)) {
                is Number -> out.add(v.toLong())
                is String -> v.toLongOrNull()?.let { out.add(it) }
            }
        }
        return out
    }

    private fun markRatingSkipped(taskId: Long) {
        if (skippedRatingIds.contains(taskId)) return
        skippedRatingIds.add(taskId)
        val list = loadSkippedRatingIds().toMutableList()
        if (!list.contains(taskId)) list.add(taskId)
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        Sp.setJsonArray(StorageKey.mvRatingSkippedIds, arr)
    }

    private fun shouldSkipRatingFromFeedback(feedback: MvFeedbackModel): Boolean =
        feedback.hasRated || feedback.hasFeedback || (feedback.starRating ?: 0) > 0

    private suspend fun ensureRatingStatusChecked(taskId: Long) {
        if (skippedRatingIds.contains(taskId)) return
        // 同一条只查一次：并发时等前一次结束
        if (ratingChecking.contains(taskId)) {
            while (ratingChecking.contains(taskId) && !closed) delayMs(50)
            return
        }
        ratingChecking.add(taskId)
        try {
            val video = mvList.getOrNull(currentIndex) ?: return
            // 信息流版本：1=V1 information_flow_custom，2=V2 动态卡片
            if (video.mvType == MvType.mv && video.informationFlowVersion != 2) return
            val feedback = LibraryVideoDetailApi.fetchMvFeedback(taskId).getOrNull()
            if (feedback != null && shouldSkipRatingFromFeedback(feedback)) markRatingSkipped(taskId)
        } catch (e: Throwable) {
            DevLog.logger("查询评分状态失败: ${e.message}")
        } finally {
            ratingChecking.remove(taskId)
        }
    }

    private fun prefetchRatingStatus(index: Int) {
        val video = mvList.getOrNull(index) ?: return
        // 试用 MV 不触发评分预取
        if (video.trialMvFlag) return
        if (skippedRatingIds.contains(video.id)) return
        launch { ensureRatingStatusChecked(video.id) }
    }

    private fun checkRatingTrigger(index: Int, positionMs: Long, durationMs: Long) {
        val video = mvList.getOrNull(index) ?: return
        // 试用 MV 不展示评分横幅
        if (video.trialMvFlag) return
        if (durationMs < 6000) return
        val taskId = video.id
        if (skippedRatingIds.contains(taskId)) return
        if (showRatingBanner) return
        if (ratingTriggerInFlight) return
        if (video.mvType == MvType.mv && video.informationFlowVersion != 2) return
        val remaining = durationMs - positionMs
        if (remaining > 3000 || remaining < 0) return
        ratingTriggerInFlight = true
        launch { tryShowRatingBanner(index, taskId) }
    }

    private suspend fun tryShowRatingBanner(index: Int, taskId: Long) {
        try {
            if (index != currentIndex) return
            if (skippedRatingIds.contains(taskId)) return
            ensureRatingStatusChecked(taskId)
            if (skippedRatingIds.contains(taskId)) return
            if (index != currentIndex) return
            if (showRatingBanner) return
            val remaining = currentDurationMs - currentPositionMs
            if (remaining > 3000 || remaining < 0) return
            markRatingSkipped(taskId)
            showRatingBanner = true
            ensureTagsLoaded()
        } finally {
            ratingTriggerInFlight = false
        }
    }

    private fun ensureTagsLoaded() {
        if (tagsLoaded) return
        tagsLoaded = true
        launch {
            val tags = LibraryVideoDetailApi.fetchMvRecommendTags().getOrNull() ?: emptyList()
            for (e in tags) recommendTags[e.starRating] = e.tags
        }
    }

    internal fun onRatingBannerDismiss() {
        showRatingBanner = false
    }

    internal fun onRatingBannerStarTapped(starRating: Int) {
        val video = mvList.getOrNull(currentIndex) ?: return
        showRatingBanner = false
        ensureTagsLoaded()
        val capturedId = video.id
        pauseVideo(capturedId)
        showMvFeedbackSheet(
            initialRating = starRating,
            tags = recommendTags, // 传实时表，标签晚到也能显示
            onRatingChanged = { r ->
                launch {
                    val ok = LibraryVideoDetailApi.rateMv(capturedId, r).getOrNull() ?: false
                    if (ok) markRatingSkipped(capturedId)
                }
            },
            onSubmit = { handle, rating, tagCodes, content ->
                val res = LibraryVideoDetailApi.submitMvFeedback(capturedId, rating, tagCodes, content).getOrNull() ?: false
                if (res) {
                    markRatingSkipped(capturedId)
                    toast("feedback_submit_success".tr)
                }
                handle.dismiss()
            },
            onDismiss = { if (!closed) playVideo(currentId) },
        )
    }

    // ------------------------------------------------------------------ 订阅漏斗

    private fun checkTrialSubscribeCard(index: Int, positionMs: Long, durationMs: Long) {
        val video = mvList.getOrNull(index) ?: return
        if (!video.trialMvFlag) return
        if (funnel.shouldShowCardNearEnd(positionMs, durationMs, subscribeCardShown)) {
            subscribeCardShown = true
            showSubscribeBanner = true
        }
    }

    /** 试用 MV 播放结束：未访问订阅页的未订阅用户自动跳转。 */
    internal fun onTrialPlayCompleted() {
        if (!funnel.shouldNavigateOnPlayEnd || subscribeNavigated) return
        navigateToSubscribe()
    }

    /** 页面退出：立即关闭引导，并保留试用 MV 的离页订阅分流。 */
    private fun handlePagePop() {
        if (closed) return
        isLeavingPage = true
        cancelPlaybackEditGuideTimer()
        // 用户已经看到引导时即视为已读；仅 hide 不会触发 onClose 的持久化回调。
        if (isPlaybackEditGuideShowing && GuideManager.isShowing) markPlaybackEditGuideShown()
        isPlaybackEditGuideShowing = false
        if (GuideManager.isShowing) GuideManager.hide()
        if (isCurrentTrialMv) scheduleTrialSubscribeAfterLeave()
    }

    /** 离开后延迟打开订阅页的唯一出口（等关页结束后从下一层页面打开）。 */
    private fun scheduleTrialSubscribeAfterLeave() {
        if (leaveHandled || subscribeNavigated || !funnel.shouldNavigateOnLeave) return
        leaveHandled = true
        subscribeNavigated = true
        val host = com.sondo.app.service.AppNav.host ?: return
        setTimeout(host.pagerId, 350) {
            if (com.sondo.app.service.AppNav.currentRoute() == Routes.subscribe) return@setTimeout
            com.sondo.app.service.AppNav.push(Routes.subscribe, JSONObject().put("skipDiscountOnBack", true))
        }
    }

    /** 点击末段订阅卡片进入订阅页；显式点击不受自动跳转防重复标记影响。 */
    internal fun onSubscribeCardTap() {
        // 主入口关闭时不从订阅卡片进订阅页。
        if (!AppConstants.enableMemberPageEntry) return
        openSubscribePage()
    }

    /** 自动分流场景跳转订阅页（播放结束 / 下载拦截），防重复触发。 */
    private fun navigateToSubscribe() {
        if (subscribeNavigated) return
        subscribeNavigated = true
        openSubscribePage()
    }

    /** 打开订阅页；从播放页进入时跳过返回折扣分流，返回后恢复播放。 */
    private fun openSubscribePage() {
        showSubscribeBanner = false
        val id = currentId
        pauseVideo(id)
        nav.push(Routes.subscribe, JSONObject().put("skipDiscountOnBack", true)) {
            if (closed) return@push
            resumeAfterResult(id)
        }
    }

    // ------------------------------------------------------------------ 播放

    internal fun onPageChanged(index: Int) {
        if (index < 0 || index >= mvList.size) return
        // 滑动后又回弹到原页时 index 未变，无需暂停 / 切换
        if (index == currentIndex) return
        pauseVideo(currentId)
        currentIndex = index
        // 2 秒等待期内切页则取消引导
        cancelPlaybackEditGuideTimer()
        currentPositionMs = 0L
        currentDurationMs = 0L
        // 切换视频时隐藏评分横幅与订阅卡片
        showRatingBanner = false
        showSubscribeBanner = false
        ratingTriggerInFlight = false
        subscribeCardShown = false
        prefetchRatingStatus(index)
    }

    internal fun registerController(id: Long, controller: AppVideoController) {
        videoControllers[id] = controller
    }

    internal fun markControllerAsDisposed(id: Long) {
        videoControllers.remove(id)
    }

    internal fun onVideoProgress(id: Long, positionMs: Long, durationMs: Long) {
        if (id != currentId) return
        if (isScrolling) return
        currentPositionMs = positionMs
        currentDurationMs = durationMs
        checkRatingTrigger(currentIndex, positionMs, durationMs)
        checkTrialSubscribeCard(currentIndex, positionMs, durationMs)
    }

    internal fun pauseVideo(id: Long) {
        videoControllers[id]?.pause()
    }

    internal fun playVideo(id: Long) {
        if (!isPageVisible) return
        videoControllers[id]?.play()
    }

    /** 子页面关闭回调里恢复播放：本页还没重新可见时推迟到 [pageDidAppear]。 */
    private fun resumeAfterResult(id: Long) {
        if (isPageVisible) playVideo(id) else resumeOnAppearId = id
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        val id = resumeOnAppearId ?: return
        resumeOnAppearId = null
        if (!closed && id == currentId) playVideo(id)
    }

    // ------------------------------------------------------------------ 操作

    /** 是否展示画质升级按钮。 */
    internal fun getMvUpgrade(index: Int): Boolean {
        val video = mvList.getOrNull(index) ?: return false
        if (video.trialMvFlag) return false
        return video.quality == 480 && video.genMode == 2
    }

    internal fun onEditTap() {
        // 2 秒内自己点进编辑则视为已看过，返回后不再弹
        cancelPlaybackEditGuideTimer()
        markPlaybackEditGuideShown()
        val current = mvList.getOrNull(currentIndex) ?: return
        val id = current.id
        // 国内版没有旧版数据，成片页编辑一律进整合页。
        nav.push(Routes.editVideo, JSONObject().put("id", id).put("type", "mv_detail")) { result ->
            if (closed) return@push
            val value = result?.optString("result", "") ?: ""
            // 编辑页已调删除接口并刷新资源库列表；此处只同步本页快照
            if (value == "deleted") {
                removeCurrentFromDetailList()
                return@push
            }
            if (value.isNotEmpty()) {
                val i = mvList.indexOfFirst { it.id == id }
                if (i != -1) mvList[i] = mvList[i].copy(resultUrl = value)
            }
            resumeAfterResult(currentId)
        }
    }

    /** 计算超分所需积分。 */
    private fun getUpgradePoint(): Int {
        val video = mvList[currentIndex]
        val duration = MvService.getMvDuration(video.subDurationScope)
        val sdVideoPoint = if (video.mvVersion == 2 && video.mvType == MvType.mv) {
            ConfigService.mvSeedanceSdVideoPoint.toDouble()
        } else {
            ConfigService.sdVideoPoint.toDouble()
        }
        val point = floor(duration * sdVideoPoint).toInt()
        return point + ConfigService.againMergePoint
    }

    /** 升级画质弹窗（Dart `VideoIntroductionWidget(title: quality_upgrade)`）。 */
    internal fun onHdTap() {
        if (mvList.getOrNull(currentIndex) == null) return
        pauseVideo(currentId)
        val point = getUpgradePoint()
        val page = this
        val imageH = (screenWidth - 64f) * 9f / 16f
        showDialog(dismissOnMask = false) { handle ->
            qualityUpgradeDialog(
                width = page.screenWidth,
                point = point,
                imageH = imageH,
                onClose = {
                    handle.dismiss()
                    page.playVideo(page.currentId)
                },
                onBtnTap = {
                    handle.dismiss()
                    page.launch { page.onUpgradeTap(point) }
                },
            )
        }
    }

    /** 升级画质接口。 */
    internal suspend fun onUpgradeTap(point: Int) {
        val index = currentIndex
        val video = mvList.getOrNull(index) ?: return
        if (video.resultUrl.isEmpty()) return
        showLoading()
        val res = LibraryVideoDetailApi.superMergeMv(video.id, "720", video.genMode, point).getOrNull() ?: false
        hideLoading()
        if (res) {
            val i = mvList.indexOfFirst { it.id == video.id }
            if (i != -1) mvList[i] = mvList[i].copy(quality = 720)
            LibraryTabRefreshService.refreshMv()
            nav.pop()
            toast("please_wait_for_the_video_to_be_upgraded".tr)
        } else {
            toast("upgrade_failed".tr)
        }
    }

    /** 从详情快照移除当前项并切到相邻视频；列表空则退回上一页。 */
    private fun removeCurrentFromDetailList(showToast: Boolean = false) {
        if (mvList.isEmpty()) return
        val deletedIndex = currentIndex
        if (deletedIndex < 0 || deletedIndex >= mvList.size) return
        val deletedId = mvList[deletedIndex].id
        pauseVideo(deletedId)
        videoControllers.remove(deletedId)
        mvList.removeAt(deletedIndex)
        if (mvList.isEmpty()) {
            nav.pop()
            if (showToast) toast("delete_success".tr)
            return
        }
        // 删除最后一个 -> 播放上一个；否则播放当前位置（下一条顶上来）
        var targetIndex = if (deletedIndex >= mvList.size) mvList.size - 1 else deletedIndex
        if (targetIndex < 0) targetIndex = 0
        currentIndex = targetIndex
        currentPositionMs = 0L
        currentDurationMs = 0L
        setTimeout(pagerId, 16) {
            if (closed || mvList.isEmpty()) return@setTimeout
            pageRef?.view?.scrollToPageIndex(targetIndex, false)
        }
        if (showToast) toast("delete_success".tr)
    }

    internal fun deleteMv() {
        val video = mvList.getOrNull(currentIndex) ?: return
        pauseVideo(video.id)
        if (video.resultUrl.isEmpty()) {
            toast("no_video_to_delete".tr)
            return
        }
        launch {
            showLoading()
            val res = LibraryVideoDetailApi.deleteMv(video.id)
            if (closed) return@launch
            if (res.isSuccess) {
                // 刷新列表 mv
                launch { LibraryTabRefreshService.refreshMv() }
                hideLoading()
                removeCurrentFromDetailList(showToast = true)
            } else {
                hideLoading()
                toast("delete_failed".tr)
            }
        }
    }

    internal fun downloadVideo() {
        val video = mvList.getOrNull(currentIndex) ?: return
        if (video.trialMvFlag && !isSubscribed) {
            openSubscribePage()
            return
        }
        launch { MvService.downloadMv(video.resultUrl, isFullLoading = false, contentId = "${video.id}") }
    }

    internal fun shareVideo() {
        val video = mvList.getOrNull(currentIndex) ?: return
        launch { MvService.onShareMv(video.mvModel, video.resultUrl, isFullLoading = false) }
    }

    internal fun onMoreTap() {
        val index = currentIndex
        val item = mvList.getOrNull(index) ?: return
        val id = item.id
        pauseVideo(id)
        var result: String? = null
        val inset = safeBottom
        val isLibrary = pageType == "library"
        showDialog(
            align = DialogEntry.ALIGN_BOTTOM,
            maskColor = Color(0, 0, 0, 0.54f),
            onDismiss = {
                if (closed) return@showDialog
                when (result) {
                    null -> playVideo(currentId)
                    "report" -> openReport(id)
                    "delete" -> showConfirmDialog("are_you_sure_you_want_to_delete".tr) {
                        if (!closed) deleteMv()
                    }
                }
            },
        ) { handle ->
            View {
                attr {
                    margin(16f, 16f, 16f + inset, 16f)
                    height(122f)
                    borderRadius(24f)
                    backgroundColor(UIColor.c1C1C1C)
                    flexDirectionRow()
                    justifyContentSpaceEvenly()
                    alignItemsCenter()
                }
                moreSheetBtn("report".tr, AssetsImage.reportMv) {
                    result = "report"
                    handle.dismiss()
                }
                if (isLibrary) {
                    moreSheetBtn("delete".tr, AssetsImage.deleteMv) {
                        result = "delete"
                        handle.dismiss()
                    }
                }
            }
        }
    }

    private fun openReport(id: Long) {
        var selected = false
        val handle = showReportTagOptions { value ->
            selected = true
            launch {
                EmailHelper.sendEmail(
                    content = listOf(
                        "${"video_id".tr}: $id",
                        "${"report_reason".tr}: $value",
                        "report_email_hint".tr,
                    ).joinToString("\n"),
                )
            }
        }
        watchDialogDismiss(handle) {
            if (!closed && !selected) playVideo(id)
        }
    }

    // ------------------------------------------------------------------ UI

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    flex(1f)
                    backgroundColor(Color.BLACK)
                    paddingBottom(ctx.safeBottom)
                }
                vif({ ctx.listReady }) {
                    val startIndex = ctx.currentIndex
                    PageList {
                        ref { ctx.pageRef = it }
                        attr {
                            size(ctx.screenWidth, ctx.screenHeight - ctx.safeBottom)
                            pageItemWidth(ctx.screenWidth)
                            pageItemHeight(ctx.screenHeight - ctx.safeBottom)
                            pageDirection(false)
                            defaultPageIndex(startIndex)
                            showScrollerIndicator(false)
                            // 订阅卡片展示期间不响应滑动
                            scrollEnable(!ctx.showSubscribeBanner)
                            touchEnable(!ctx.showSubscribeBanner)
                        }
                        event {
                            dragBegin { ctx.isScrolling = true }
                            scrollEnd { ctx.isScrolling = false }
                            pageIndexDidChanged {
                                ctx.isScrolling = false
                                ctx.onPageChanged((it as? JSONObject)?.optInt("index", 0) ?: 0)
                            }
                        }
                        vfor({ ctx.mvList }) { mv ->
                            View {
                                attr { size(ctx.screenWidth, ctx.screenHeight - ctx.safeBottom) }
                                SDListMvVideoPlayer {
                                    attr {
                                        absolutePositionAllZero()
                                        this.mv = mv
                                        isCurrentPage = ctx.currentId == mv.id
                                        isScrolling = ctx.isScrolling
                                        forcePaused = ctx.isPlaybackEditGuideShowing
                                        showProgress = true
                                    }
                                    event {
                                        onControllerReady { _, c -> ctx.registerController(mv.id, c) }
                                        onDisposed { ctx.markControllerAsDisposed(mv.id) }
                                        onProgress { _, p, d -> ctx.onVideoProgress(mv.id, p, d) }
                                        if (mv.trialMvFlag) onPlayCompleted { ctx.onTrialPlayCompleted() }
                                    }
                                }
                            }
                        }
                    }
                }
                View {
                    attr {
                        absolutePosition(top = ctx.statusBarHeight, left = 0f)
                    }
                    SDNavBack(color = Color.WHITE)
                }
                vif({ ctx.showRatingBanner }) {
                    MvRatingBanner(
                        onDismiss = { ctx.onRatingBannerDismiss() },
                        onStarTapped = { ctx.onRatingBannerStarTapped(it) },
                    ) {
                        // Dart 整个 Stack 在 SafeArea 内；父级 paddingBottom 对绝对定位无效，要自己加
                        attr { absolutePosition(left = 16f, right = 16f, bottom = 60f + ctx.safeBottom) }
                    }
                }
                vif({ ctx.showSubscribeBanner }) {
                    TryFreeSubscribeBanner(onTap = { ctx.onSubscribeCardTap() }) {
                        attr { absolutePosition(left = 16f, right = 16f, bottom = 60f + ctx.safeBottom) }
                    }
                }
                detailActionColumn(ctx)
            }
        }
    }
}

/** 右侧操作列（Dart `_buildMoreButton`）：高清 / 编辑 / 分享 / 下载 / 更多，间距 15。 */
private fun ViewContainer<*, *>.detailActionColumn(ctx: MvListDetailPage) {
    View {
        attr {
            absolutePosition(right = 14f, bottom = 200f + ctx.safeBottom)
            alignItemsCenter()
        }
        vif({ ctx.getMvUpgrade(ctx.currentIndex) }) {
            actionIcon(AssetsImage.hdIcon, 27f) { ctx.onHdTap() }
        }
        vif({ ctx.dataType == 0 && ctx.pageType == "library" && ctx.mvList.getOrNull(ctx.currentIndex) != null }) {
            View {
                ref { ctx.editRef = it }
                attr { marginBottom(15f) }
                actionIcon(AssetsImage.editMv, 30f, gap = 0f) { ctx.onEditTap() }
            }
        }
        // 试用非会员不展示分享；产品开关关闭时整入口隐藏。
        if (AppConstants.enableMvDanceShare) {
            vif({ ctx.mvList.getOrNull(ctx.currentIndex) != null && !(ctx.isCurrentTrialMv && !ctx.subscribed) }) {
                View {
                    vif({ ctx.sharingUrls.contains(ctx.mvList.getOrNull(ctx.currentIndex)?.resultUrl ?: "") }) {
                        actionLoading()
                    }
                    velse {
                        actionIcon(AssetsImage.shareMv1, 27f) { ctx.shareVideo() }
                    }
                }
            }
        }
        vif({ ctx.mvList.getOrNull(ctx.currentIndex) != null }) {
            View {
                vif({ ctx.downloadingUrls.contains(ctx.mvList.getOrNull(ctx.currentIndex)?.resultUrl ?: "") }) {
                    actionLoading()
                }
                velse {
                    actionIcon(AssetsImage.downloadMv1, 27f) { ctx.downloadVideo() }
                }
            }
        }
        actionIcon(AssetsImage.moreMv, 30f, gap = 0f) { ctx.onMoreTap() }
    }
}

private fun ViewContainer<*, *>.actionIcon(asset: String, size: Float, gap: Float = 15f, onTap: () -> Unit) {
    View {
        attr {
            padding(5f)
            marginBottom(gap)
        }
        event { click { onTap() } }
        Image {
            attr {
                size(size, size)
                src(ImageUri.commonAssets(asset))
                resizeContain()
            }
        }
    }
}

private fun ViewContainer<*, *>.actionLoading() {
    View {
        attr {
            padding(5f)
            marginBottom(15f)
            size(37f, 37f)
            allCenter()
        }
        SDLoading { attr { size = 24f } }
    }
}

private fun ViewContainer<*, *>.moreSheetBtn(title: String, image: String, onTap: () -> Unit) {
    View {
        attr { alignItemsCenter() }
        event { click { onTap() } }
        View {
            attr {
                size(48f, 48f)
                borderRadius(48f)
                backgroundColor(Color(0x26FFFFFFL))
                allCenter()
            }
            Image {
                attr {
                    size(20f, 20f)
                    src(ImageUri.commonAssets(image))
                    resizeContain()
                }
            }
        }
        View { attr { height(6f) } }
        Text {
            attr {
                text(title)
                fontSize(12f)
                fontFamily("Poppins-SemiBold")
                color(Color(0x99FFFFFFL))
            }
        }
    }
}

/** 画质升级说明弹窗（Dart `VideoIntroductionWidget` 带标题的分支）。 */
private fun ViewContainer<*, *>.qualityUpgradeDialog(
    width: Float,
    point: Int,
    imageH: Float,
    onClose: () -> Unit,
    onBtnTap: () -> Unit,
) {
    View {
        // 弹窗容器按内容收缩：必须给宽度，否则标题行、按钮都缩成内容宽，图片容器宽度为 0 显示空白
        attr {
            width(width)
            padding(16f)
        }
        View {
            attr {
                padding(16f)
                backgroundColor(UIColor.c1C1C1C)
                borderRadius(24f)
            }
            View {
                attr {
                    flexDirectionRow()
                    justifyContentSpaceBetween()
                    alignItemsCenter()
                }
                Text {
                    attr {
                        text("quality_upgrade".tr)
                        fontSize(16f)
                        fontFamily("Poppins-SemiBold")
                        color(Color(0xCCFFFFFFL))
                    }
                }
                Image {
                    attr {
                        size(24f, 24f)
                        src(ImageUri.commonAssets(AssetsImage.close))
                        resizeContain()
                    }
                    event { click { onClose() } }
                }
            }
            View { attr { height(16f) } }
            Image {
                attr {
                    size(width - 64f, imageH)
                    borderRadius(10f)
                    src(ImageUri.commonAssets(AssetsImage.imageQuality))
                    resizeCover()
                }
            }
            View { attr { height(16f) } }
            Text {
                attr {
                    text("once_upgraded_to_quality_subsequent_clip_edits_will_use_quality_and_this_change_is_irreversible".tr)
                    fontSize(12f)
                    fontFamily("Poppins-SemiBold")
                    color(Color(0xCCFFFFFFL))
                    lines(10)
                }
            }
            View { attr { height(16f) } }
            View {
                attr {
                    height(48f)
                    borderRadius(48f)
                    backgroundColor(UIColor.cF3EFF7)
                    flexDirectionRow()
                    allCenter()
                }
                event { click { onBtnTap() } }
                Text {
                    attr {
                        text("quality_upgrade".tr)
                        fontSize(14f)
                        color(UIColor.c0A0A0A)
                    }
                }
                View { attr { width(6f) } }
                Image {
                    attr {
                        size(16f, 16f)
                        src(ImageUri.commonAssets(AssetsImage.c1Icon))
                        tintColor(UIColor.c0A0A0A)
                        resizeContain()
                    }
                }
                View { attr { width(4f) } }
                Text {
                    attr {
                        text("$point")
                        fontSize(12f)
                        fontFamily("Poppins-Medium")
                        color(UIColor.c0A0A0A)
                    }
                }
            }
        }
    }
}
