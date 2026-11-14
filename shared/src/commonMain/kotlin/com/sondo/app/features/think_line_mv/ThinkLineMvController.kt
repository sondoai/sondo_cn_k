package com.sondo.app.features.think_line_mv

import com.sondo.app.base.Routes
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.i18n.trParams
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.LogUtils
import com.sondo.app.core.utils.delayMs
import com.sondo.app.data.models.DanceFeedbackTag
import com.sondo.app.data.models.MvFeedbackModel
import com.sondo.app.data.models.MvModel
import com.sondo.app.data.models.MvStatus
import com.sondo.app.data.models.MvThinkCard
import com.sondo.app.data.models.MvThinkCardStatus
import com.sondo.app.data.models.MvThinkCardType
import com.sondo.app.data.models.MvThinkGeneratedCard
import com.sondo.app.data.models.MvThinkStep
import com.sondo.app.data.models.MvThinkStepStatus
import com.sondo.app.data.models.ThinkLineMvDisplayCache
import com.sondo.app.features.four_view.runOnPage
import com.sondo.app.features.think_line_mv.data.SegmentItem
import com.sondo.app.features.think_line_mv.data.ThinkLineMvApi
import com.sondo.app.features.think_line_mv.widgets.TLFeedbackSeed
import com.sondo.app.service.MvService
import com.sondo.app.service.PollingService
import com.sondo.app.service.UserStore
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.ui.dialog.ImagePreviewItem
import com.sondo.app.ui.dialog.VideoPreviewItem
import com.sondo.app.ui.dialog.showConfirmDialog
import com.sondo.app.ui.input.hideKeyboard
import com.sondo.app.ui.works.WorksPreviewUnlock
import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.coroutines.async
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 步骤图标状态。 */
internal enum class TLStepIcon { none, generating, success, failed }

/** 一张已揭示的卡片（身份稳定；内容变化只换 [card]，入场动画不重播）。 */
internal class TLCardVm(scope: PagerScope, card: MvThinkCard, val animated: Boolean) {
    var card by scope.observable(card)
}

/** 一个可见步骤的展示态（对应 Dart `_buildStepItem` 读到的那组字段）。 */
internal class TLStepVm(scope: PagerScope, val index: Int) {
    private val scopeRef = scope
    var title by scope.observable("")
    var desc by scope.observable("")
    var titleFailed by scope.observable(false)
    var icon by scope.observable(TLStepIcon.generating)
    var isLast by scope.observable(true)
    var durationSec by scope.observable(0)
    var showCards by scope.observable(false)
    var showRetry by scope.observable(false)
    var noCardRetry by scope.observable(false)
    var starVisible by scope.observable(true)
    val cards: ObservableList<TLCardVm> by scope.observableList<TLCardVm>()

    fun newCard(card: MvThinkCard, animated: Boolean) = TLCardVm(scopeRef, card, animated)
}

/**
 * MV 信息流页控制器（Dart: think_line_mv/presentation/controllers/think_line_controller.dart `ThinkLineMvController`）。
 *
 * 逻辑与字段一一对应 Dart（普通字段 + 运行时并行数组）；Dart 的 `update([_id])` 在这里是 [update]：
 * 把普通状态同步到页面的响应式展示态（[TLStepVm] 列表 + 页面级 observable），只改变化了的字段。
 */
internal class ThinkLineMvController(private val page: ThinkLineMvPage) {

    companion object {
        private const val ID = "think_line_mv"
        private const val SCROLL_BOTTOM_THRESHOLD = 100f
        private const val POLL_INTERVAL_MS = 12_000
        private const val TYPING_INTERVAL_MS = 25
        private const val CARD_REVEAL_INITIAL_DELAY_MS = 300
        private const val CARD_REVEAL_INTERVAL_MS = 600
        private const val STEP_TRANSITION_DELAY_MS = 400
        private const val TYPING_SCROLL_LERP_FACTOR = 0.28f
        private val MODE = mapOf("mode" to "mv")
    }

    // ------------------------------------------------------------------ 状态（同 Dart）

    var stepStatuses = ArrayList<MvThinkStepStatus>()
    var feedbackModel = MvFeedbackModel(taskId = 0L)
    private val displayedTitles = ArrayList<String>()
    private val displayedDescriptions = ArrayList<String>()
    var isStepTyping = ArrayList<Boolean>()
    var stepReadFlags = ArrayList<Boolean>()
    var maxVisibleStep = -1

    val visibleStepCount: Int
        get() {
            if (maxVisibleStep < 0 || steps.isEmpty()) return 0
            return (maxVisibleStep + 1).coerceIn(0, steps.size)
        }

    var visibleCardCounts = ArrayList<Int>()
    var stepCardsUseAnimatedReveal = ArrayList<Boolean>()
    var litStars = 0

    val starCardRating: Int get() = max(litStars, feedbackModel.starRating ?: 0)

    private var userIsTouching = false
    private var feedbackFormScrolled = false

    fun onScrollPointerDown() {
        userIsTouching = true
    }

    fun onScrollPointerUp() {
        userIsTouching = false
    }

    private var stepAdvanced = ArrayList<Boolean>()
    private var cardRevealInProgress = ArrayList<Boolean>()
    private var cardRevealDone = ArrayList<Boolean>()

    /** 每步揭示世代号；reset 后递增，作废已排队的 delayed 回调 */
    private var cardRevealGenerations = ArrayList<Int>()

    /** 步骤重试时记下的失败 cards 签名；轮询仍返回同一批则不恢复、不揭示 */
    private val repairHiddenCardSignatures = HashMap<Int, String>()

    private var activeStepIndex = -1

    var steps = ArrayList<MvThinkStep>()
    var taskId = 0L
    var type = ""

    private var flowCompleted = false
    private var pollingPaused = false
    private var payCompleted = false
    private var wasPayButtonVisible = false
    private val retryingCardUniqueKeys = HashSet<Long>()

    val showPayButton: Boolean get() = !payCompleted && findPayStepIndex() != null && needPoint > 0

    /** 详情 unlock 为 0 表示仍锁定。底部解锁按钮等合成成功并取到积分后再展示。 */
    var previewUnlock: Int? = null
    val needsPreviewUnlock: Boolean get() = previewUnlock == 0
    private var previewPointsQueried = false
    private var previewPointsLoaded = false
    val showPreviewUnlockButton: Boolean get() = isFinalStepSucceeded() && needsPreviewUnlock && previewPointsLoaded

    /** （isPay != 1）不再展示星级卡片。 */
    fun shouldShowStarCard(stepIndex: Int): Boolean {
        if (stepIndex < 0 || stepIndex >= steps.size) return true
        return steps[stepIndex].isPay == 1
    }

    var needPoint = 0
    var imageComp = ""
    var musicUrl = ""
    var mvStartEndTime = ""
    var mvVersion = 0
    var isDeletable = false

    var recommendTags = LinkedHashMap<Int, List<DanceFeedbackTag>>()

    private var wasRestoredFromCache = false
    private val markReadFailedFlowIds = HashSet<Long>()

    var isClosed = false
        private set

    // 打字机定时器（世代号作废旧的 tick）
    private var typingGen = 0
    private var typingScrollFollowActive = false

    // ------------------------------------------------------------------ 生命周期

    fun onInit() {
        val args = com.sondo.app.features.think_line_mv.data.ThinkLineMvArgs.fromParams(page.pagerData.params)
        taskId = args.id
        type = args.type
        initDate()
        page.setKeepScreenOn(true)
        UmengUtils.logEvent("flow_enter", MODE)
    }

    private fun initDate() {
        restoreDisplayCache()
        // 每次进入都需要显示加载
        page.showLoading()
        page.launch {
            val tags = async { getRecommendTags() }
            val fb = async { getMvFeedback() }
            getMvDetail()
            tags.await()
            fb.await()
            if (isClosed) return@launch
            update()
            if (type != "create") ThinkLineMvApi.markAllFlowRead(taskId)
            // 每次进入都去关闭加载
            poll(ignorePollingPause = true, isFirst = true)
            if (isClosed) return@launch
            if (shouldContinuePolling()) {
                registerPolling(startImmediately = false)
            }
            update()
        }
    }

    fun onReady() {
        // 通知权限引导（Dart _getNotified：首帧后 1s）
        page.postDelayed(1000) {
            if (isClosed) return@postDelayed
            page.launch {
                try {
                    com.sondo.app.service.PushService.showNotifiedDialog("video", pager = page)
                } catch (e: Throwable) {
                    DevLog.logger("push fcm error")
                }
            }
        }
    }

    fun onClose() {
        saveDisplayCache()
        page.setKeepScreenOn(false)
        UmengUtils.logEvent("flow_leave", MODE)
        typingGen++
        stopTypingScrollFollow()
        isClosed = true
        if (PollingService.isTaskActive(ID)) {
            DevLog.logger("[ThinkLineMv] 停止轮询: 页面销毁, taskId=$taskId")
        }
        PollingService.stopTask(ID)
    }

    private fun registerPolling(startImmediately: Boolean) {
        PollingService.registerTask(
            taskId = ID,
            intervalMs = POLL_INTERVAL_MS,
            task = { page.runOnPage { poll() } },
            shouldContinue = { !isClosed && shouldContinuePolling() },
            startImmediately = startImmediately,
        )
    }

    // ------------------------------------------------------------------ 展示缓存

    private fun restoreDisplayCache(): Boolean {
        if (taskId <= 0) return false
        val cache = MvService.getThinkLineMvDisplayCache(taskId) ?: return false
        if (cache.steps.isEmpty()) return false
        applyDisplayCache(cache)
        update()
        page.postDelayed(16) { scrollToBottom(force = true) }
        DevLog.logger("[ThinkLineMv] 恢复缓存展示, taskId=$taskId")
        wasRestoredFromCache = true
        return true
    }

    private fun saveDisplayCache() {
        if (taskId <= 0 || steps.isEmpty()) return
        MvService.saveThinkLineMvDisplayCache(taskId, buildDisplayCache())
    }

    private fun buildDisplayCache(): ThinkLineMvDisplayCache = ThinkLineMvDisplayCache(
        steps = ThinkLineMvDisplayCache.copySteps(steps),
        stepStatuses = ArrayList(stepStatuses),
        displayedTitles = List(steps.size) { i ->
            if (i <= maxVisibleStep) steps[i].title else displayedTitles.getOrElse(i) { "" }
        },
        displayedDescriptions = List(steps.size) { i ->
            if (i <= maxVisibleStep) steps[i].description else displayedDescriptions.getOrElse(i) { "" }
        },
        isStepTyping = List(steps.size) { i -> if (i <= maxVisibleStep) false else isStepTyping.getOrElse(i) { false } },
        stepReadFlags = ArrayList(stepReadFlags),
        maxVisibleStep = maxVisibleStep,
        visibleCardCounts = ArrayList(visibleCardCounts),
        stepCardsUseAnimatedReveal = ArrayList(stepCardsUseAnimatedReveal),
        litStars = litStars,
        stepAdvanced = ArrayList(stepAdvanced),
        cardRevealInProgress = ArrayList(cardRevealInProgress),
        cardRevealDone = ArrayList(cardRevealDone),
        activeStepIndex = activeStepIndex,
        flowCompleted = flowCompleted,
        pollingPaused = pollingPaused,
        payCompleted = payCompleted,
        needPoint = needPoint,
        imageComp = imageComp,
        isDeletable = isDeletable,
    )

    private fun applyDisplayCache(cache: ThinkLineMvDisplayCache) {
        steps = ArrayList(ThinkLineMvDisplayCache.copySteps(cache.steps))
        stepStatuses = ArrayList(cache.stepStatuses)
        displayedTitles.clear()
        displayedTitles.addAll(cache.displayedTitles)
        displayedDescriptions.clear()
        displayedDescriptions.addAll(cache.displayedDescriptions)
        isStepTyping = ArrayList(cache.isStepTyping)
        stepReadFlags = ArrayList(cache.stepReadFlags)
        maxVisibleStep = cache.maxVisibleStep
        visibleCardCounts = ArrayList(cache.visibleCardCounts)
        stepCardsUseAnimatedReveal = ArrayList(cache.stepCardsUseAnimatedReveal)
        litStars = cache.litStars
        stepAdvanced = ArrayList(cache.stepAdvanced)
        cardRevealInProgress = ArrayList(cache.cardRevealInProgress)
        cardRevealDone = ArrayList(cache.cardRevealDone)
        cardRevealGenerations = ArrayList(List(steps.size) { 0 })
        repairHiddenCardSignatures.clear()
        activeStepIndex = cache.activeStepIndex
        flowCompleted = cache.flowCompleted
        pollingPaused = cache.pollingPaused
        payCompleted = cache.payCompleted
        needPoint = cache.needPoint
        imageComp = cache.imageComp
        isDeletable = cache.isDeletable
        // 缓存里各列表可能比 steps 短，写入展示文案前先按各自长度补齐。
        ensureRuntimeCapacity(steps.size)
        var i = 0
        while (i <= cache.maxVisibleStep && i < steps.size) {
            if (i >= displayedTitles.size || i >= displayedDescriptions.size) break
            displayedTitles[i] = steps[i].title
            displayedDescriptions[i] = steps[i].description
            if (i < isStepTyping.size) isStepTyping[i] = false
            i++
        }
    }

    // ------------------------------------------------------------------ 接口

    suspend fun getRecommendTags() {
        val tags = ThinkLineMvApi.fetchRecommendTags().getOrNull() ?: emptyList()
        if (tags.isEmpty()) return
        for (e in tags) recommendTags[e.starRating] = e.tags
    }

    suspend fun getMvFeedback() {
        if (taskId <= 0) return
        val feedback = ThinkLineMvApi.fetchFeedback(taskId).getOrNull() ?: return
        feedbackModel = feedback
        val rating = feedback.starRating ?: 0
        if (rating > 0) litStars = max(litStars, rating)
        if (rating > 0 || feedback.hasRated || feedback.hasFeedback) feedbackFormScrolled = true
    }

    suspend fun getMvDetail() {
        var res: MvModel? = ThinkLineMvApi.fetchMvDetail(taskId).getOrNull()
        var retryCount = 0
        while (res == null && retryCount < 3) {
            retryCount++
            delayMs(2000)
            if (isClosed) return
            res = ThinkLineMvApi.fetchMvDetail(taskId).getOrNull()
        }
        DevLog.logger("getMvDetail: ${res?.continueFullMvNeedPoint}")
        if (res != null) {
            previewUnlock = res.unlock
            // 锁定积分等合成完成后再查，避免进页时把 0 写上按钮。
            if (res.unlock != 0) needPoint = res.continueFullMvNeedPoint
            imageComp = res.imageComp
            musicUrl = res.audioUrl
            mvStartEndTime = res.subDurationScope
            mvVersion = res.mvVersion
            update()
        }
    }

    /** 合成完成后单独查详情，只刷新解锁积分。同一页只查一次，失败则不展示按钮。 */
    private fun loadPreviewUnlockPoints() {
        if (previewPointsQueried || isClosed || taskId <= 0) return
        previewPointsQueried = true
        page.launch {
            var res: MvModel? = ThinkLineMvApi.fetchMvDetail(taskId).getOrNull()
            var retryCount = 0
            while (res == null && retryCount < 3) {
                retryCount++
                delayMs(2000)
                if (isClosed) return@launch
                res = ThinkLineMvApi.fetchMvDetail(taskId).getOrNull()
            }
            if (isClosed || res == null) return@launch
            needPoint = res.continueFullMvNeedPoint
            previewPointsLoaded = true
            update()
        }
    }

    suspend fun poll(ignorePollingPause: Boolean = false, isFirst: Boolean = false) {
        if (taskId <= 0 || flowCompleted) {
            if (isFirst) page.postDelayed(200) { page.hideLoading() }
            return
        }
        if (!ignorePollingPause && pollingPaused) return

        val apiSteps = ThinkLineMvApi.fetchFlowSteps(taskId).getOrNull() ?: emptyList()
        if (isClosed) return
        if (apiSteps.isEmpty()) {
            if (isFirst) page.postDelayed(200) { page.hideLoading() }
            return
        }
        applyStepsFromApi(apiSteps)
        if (isFirst) page.postDelayed(200) { page.hideLoading() }
    }

    private fun pollAsync(ignorePollingPause: Boolean = false) {
        page.launch { poll(ignorePollingPause = ignorePollingPause) }
    }

    // ------------------------------------------------------------------ 合并接口步骤

    private fun applyStepsFromApi(apiSteps: List<MvThinkStep>) {
        val isFirstLoad = steps.isEmpty()
        val previousSteps: List<MvThinkStep> = if (isFirstLoad) emptyList() else ArrayList(steps)
        steps = ArrayList(mergeApiSteps(previousSteps, apiSteps))
        val n = steps.size
        if (n == 0) return

        // 重试后后端可能短暂仍返回失败批；同签名不恢复，避免旧卡闪回
        dropStaleRepairedStepCards()
        clampVisibleCardCounts()
        syncMaxVisibleStepBounds()

        if (isFirstLoad) {
            initRuntimeState(n)
            catchUpHistoricalSteps()
            syncPollingControl()
            return
        }

        var cardContentChanged = false
        ensureRuntimeCapacity(n)
        for (i in 0 until n) {
            syncStepStatusFromApi(i)
            syncDisplayedTextForStep(i)
            if (markReadFailedFlowIds.contains(steps[i].flowId)) markStepReadIfNeeded(i)
            if (i <= maxVisibleStep && syncVisibleStepCardsFromApi(i, previousSteps)) cardContentChanged = true
        }
        if (wasRestoredFromCache) {
            resumeInterruptedRuntimeState()
            wasRestoredFromCache = false
        }
        tryRevealNextPendingStep()
        update()
        if (cardContentChanged) scrollToBottom()
        syncPollingControl()
    }

    private fun mergeApiSteps(previous: List<MvThinkStep>, api: List<MvThinkStep>): List<MvThinkStep> {
        if (previous.isEmpty()) return api
        return List(api.size) { i ->
            val apiStep = api[i]
            if (i >= previous.size || previous[i].id != apiStep.id) apiStep else mergeStepWithPrevious(previous[i], apiStep)
        }
    }

    private fun mergeStepWithPrevious(prev: MvThinkStep, api: MvThinkStep): MvThinkStep {
        if (prev.cards.size != api.cards.size) return api
        val mergedCards = List(api.cards.size) { ci -> mergePreviewCard(prev.cards[ci], api.cards[ci]) }
        return api.copy(cards = mergedCards)
    }

    private fun mergePreviewCard(prev: MvThinkCard, api: MvThinkCard): MvThinkCard {
        if (!api.isImageListCard && !api.isVideoListCard && !api.isSingerImageCard) return api
        return api.copy(items = mergeGeneratedItems(prev.items, api.items))
    }

    private fun mergeGeneratedItems(previous: List<MvThinkGeneratedCard>?, api: List<MvThinkGeneratedCard>?): List<MvThinkGeneratedCard>? {
        val apiItems = api ?: emptyList()
        val prevItems = previous ?: emptyList()
        if (apiItems.isEmpty()) return if (prevItems.isEmpty()) api else ArrayList(prevItems)
        if (prevItems.isEmpty()) return ArrayList(apiItems)
        val byId = LinkedHashMap<Long, MvThinkGeneratedCard>()
        prevItems.forEach { byId[it.id] = it }
        apiItems.forEach { byId[it.id] = it }
        return byId.values.sortedBy { it.id }
    }

    private fun syncVisibleStepCardsFromApi(index: Int, previousSteps: List<MvThinkStep>): Boolean {
        if (index < 0 || index >= steps.size || index > maxVisibleStep) return false
        ensureRuntimeCapacity(steps.size)
        if (visibleCardCounts[index] > steps[index].cards.size) visibleCardCounts[index] = steps[index].cards.size
        var changed = false
        val prevStep = previousSteps.getOrNull(index)
        if (stepReadFlags[index] && startStepCardReveal(index)) changed = true
        if (prevStep != null && didListCardItemsChange(prevStep, steps[index])) changed = true
        return changed
    }

    private fun isListCard(card: MvThinkCard): Boolean = card.isImageListCard || card.isVideoListCard || card.isSingerImageCard

    private fun didListCardItemsChange(prev: MvThinkStep, current: MvThinkStep): Boolean {
        for (ci in current.cards.indices) {
            val cur = current.cards[ci]
            if (!isListCard(cur)) continue
            if (ci >= prev.cards.size) return true
            if (listCardItemsSignature(prev.cards[ci]) != listCardItemsSignature(cur)) return true
        }
        return false
    }

    private fun listCardItemsSignature(card: MvThinkCard): String {
        val items = card.items
        if (items.isNullOrEmpty()) return "empty"
        return items.joinToString("|") { "${it.id}:${it.status.name}:${it.displayStatus.name}:${it.previewUrl}" }
    }

    private fun hasIncompleteListCardItems(): Boolean {
        var i = 0
        while (i <= maxVisibleStep && i < steps.size) {
            if (i < stepReadFlags.size && stepReadFlags[i]) {
                for (card in steps[i].cards) {
                    if (!isListCard(card)) continue
                    val items = card.items
                    if (items.isNullOrEmpty()) return true
                    if (items.any { it.status != MvThinkCardStatus.failed && it.displayStatus != MvThinkCardStatus.success }) return true
                }
            }
            i++
        }
        return false
    }

    private val finalStepIndex: Int?
        get() {
            for (i in steps.indices) if (steps[i].isFinalStep) return i
            return null
        }

    private fun initRuntimeState(n: Int) {
        stepStatuses = ArrayList(List(n) { MvThinkStepStatus.generating })
        displayedTitles.clear()
        displayedTitles.addAll(List(n) { i -> if (steps[i].isRead) steps[i].title else "" })
        displayedDescriptions.clear()
        displayedDescriptions.addAll(List(n) { i -> if (steps[i].isRead) steps[i].description else "" })
        isStepTyping = ArrayList(List(n) { false })
        stepReadFlags = ArrayList(steps.map { it.isRead })
        visibleCardCounts = ArrayList(List(n) { 0 })
        stepCardsUseAnimatedReveal = ArrayList(List(n) { false })
        stepAdvanced = ArrayList(List(n) { false })
        cardRevealInProgress = ArrayList(List(n) { false })
        cardRevealDone = ArrayList(List(n) { false })
        cardRevealGenerations = ArrayList(List(n) { 0 })
        repairHiddenCardSignatures.clear()
        maxVisibleStep = -1
        activeStepIndex = -1
        litStars = 0
        feedbackFormScrolled = false
        wasPayButtonVisible = false
    }

    private fun ensureRuntimeCapacity(n: Int) {
        // 只在 stepStatuses 更短时一起补长会漏掉缓存里更短的并行列表。
        while (stepStatuses.size < n && stepStatuses.size < steps.size) {
            val step = steps[stepStatuses.size]
            stepStatuses.add(if (step.isRead) step.status else MvThinkStepStatus.generating)
        }
        while (displayedTitles.size < n && displayedTitles.size < steps.size) {
            val step = steps[displayedTitles.size]
            displayedTitles.add(if (step.isRead) step.title else "")
        }
        while (displayedDescriptions.size < n && displayedDescriptions.size < steps.size) {
            val step = steps[displayedDescriptions.size]
            displayedDescriptions.add(if (step.isRead) step.description else "")
        }
        while (isStepTyping.size < n) isStepTyping.add(false)
        while (stepReadFlags.size < n && stepReadFlags.size < steps.size) stepReadFlags.add(steps[stepReadFlags.size].isRead)
        while (visibleCardCounts.size < n) visibleCardCounts.add(0)
        while (stepCardsUseAnimatedReveal.size < n) stepCardsUseAnimatedReveal.add(false)
        while (stepAdvanced.size < n) stepAdvanced.add(false)
        while (cardRevealInProgress.size < n) cardRevealInProgress.add(false)
        while (cardRevealDone.size < n) cardRevealDone.add(false)
        while (cardRevealGenerations.size < n) cardRevealGenerations.add(0)
    }

    private fun syncMaxVisibleStepBounds() {
        if (steps.isEmpty()) {
            maxVisibleStep = -1
            activeStepIndex = -1
            return
        }
        ensureRuntimeCapacity(steps.size)
        if (maxVisibleStep >= steps.size) maxVisibleStep = steps.size - 1
        if (activeStepIndex >= steps.size) activeStepIndex = -1
    }

    private fun syncStepStatusFromApi(index: Int) {
        if (index < 0 || index >= steps.size) return
        ensureRuntimeCapacity(steps.size)
        val apiStep = steps[index]

        // 最终步骤成功后会停止轮询，先同步本地终态，避免 UI 永久停在生成中。
        if (apiStep.isFinalStep && apiStep.status == MvThinkStepStatus.success) {
            stepStatuses[index] = apiStep.status
            if (stepReadFlags[index]) tryAdvance(index)
            return
        }
        if (index == activeStepIndex &&
            stepStatuses[index] == MvThinkStepStatus.generating &&
            apiStep.status != MvThinkStepStatus.generating
        ) {
            stepStatuses[index] = apiStep.status
            if (stepReadFlags[index]) tryAdvance(index)
            return
        }
        if (index <= maxVisibleStep) {
            if (stepAdvanced[index]) {
                stepStatuses[index] = apiStep.status
                return
            }
            if (stepReadFlags[index] &&
                stepStatuses[index] == MvThinkStepStatus.generating &&
                apiStep.status != MvThinkStepStatus.generating
            ) {
                stepStatuses[index] = apiStep.status
                tryAdvance(index)
            }
        }
    }

    private fun resumeInterruptedRuntimeState() {
        typingGen++
        stopTypingScrollFollow()

        if (flowCompleted && !isFinalStepSucceeded()) flowCompleted = false

        for (i in steps.indices) {
            ensureRuntimeCapacity(steps.size)
            val step = steps[i]
            if (i > maxVisibleStep) continue

            if (step.isRead && (!stepAdvanced[i] || !stepReadFlags[i])) {
                revealHistoricalStepInstant(i)
                continue
            }
            if (cardRevealInProgress[i]) {
                cardRevealInProgress[i] = false
                if (stepReadFlags[i] && !cardRevealDone[i]) {
                    if (!startStepCardReveal(i)) {
                        showStepCardsInstantly(i)
                        cardRevealDone[i] = true
                        tryProceedToNextStep(i)
                    }
                }
            }
            if (isStepTyping[i]) {
                if (step.status == MvThinkStepStatus.generating) {
                    runTypingPhases(i)
                } else {
                    isStepTyping[i] = false
                    displayedTitles[i] = step.title
                    displayedDescriptions[i] = step.description
                    stepReadFlags[i] = true
                    stepStatuses[i] = step.status
                    if (!cardRevealDone[i]) tryAdvance(i)
                }
                continue
            }
            if (step.status != MvThinkStepStatus.generating &&
                stepStatuses[i] == MvThinkStepStatus.generating &&
                stepReadFlags[i]
            ) {
                stepStatuses[i] = step.status
                tryAdvance(i)
            }
        }
        syncPollingControl()
    }

    /** 锁定分镜用 status。displayStatus 会把有封面的锁镜当成成功。 */
    private fun hasLockedShots(): Boolean {
        for (step in steps) {
            for (card in step.cards) {
                if (card.status == MvThinkCardStatus.locked) return true
                if (card.items?.any { it.status == MvThinkCardStatus.locked } == true) return true
            }
        }
        return false
    }

    private fun shouldContinuePolling(): Boolean {
        if (flowCompleted || pollingPaused) return false
        // 点解锁已扣费，锁镜还在就继续拉，避免合成成功把轮询停掉。
        if (!needsPreviewUnlock && hasLockedShots()) return true
        if (steps.isEmpty()) return true
        if (hasIncompleteListCardItems()) return true
        if (maxVisibleStep < 0) return true
        val finalIndex = finalStepIndex ?: return true
        if (maxVisibleStep < finalIndex) return true
        if (finalIndex >= stepAdvanced.size || finalIndex >= stepStatuses.size) return true
        if (!stepAdvanced[finalIndex]) return true
        if (stepStatuses[finalIndex] == MvThinkStepStatus.generating) return true
        return !isFinalStepSucceeded()
    }

    private fun areStepCardsAllSuccess(step: MvThinkStep): Boolean {
        if (step.cards.isEmpty()) return true
        return step.cards.all { isCardComplete(it) }
    }

    private fun isCardComplete(card: MvThinkCard): Boolean {
        if (isListCard(card)) {
            val items = card.items
            if (items.isNullOrEmpty()) return false
            return items.all { it.isComplete }
        }
        if (card.isImageCard) return card.status == MvThinkCardStatus.success
        return true
    }

    private fun findPayStepIndex(): Int? {
        for (i in steps.indices) {
            if (i > maxVisibleStep) break
            if (i >= stepAdvanced.size || !stepAdvanced[i]) continue
            if (steps[i].isPay == 1) return i
        }
        return null
    }

    private fun findPausePollingPayStepIndex(): Int? {
        for (i in steps.indices) {
            if (i > maxVisibleStep) break
            if (i >= stepAdvanced.size || !stepAdvanced[i]) continue
            val step = steps[i]
            if (step.isPay == 1 && areStepCardsAllSuccess(step)) return i
        }
        return null
    }

    private fun firstVisibleFailedStepIndex(): Int? {
        var i = 0
        while (i <= maxVisibleStep && i < steps.size) {
            if (steps[i].status == MvThinkStepStatus.failed) return i
            i++
        }
        return null
    }

    /** 失败步之前的步骤，或这些步骤（含失败步）下的卡片/分镜仍在生成。只看接口 status。 */
    private fun hasGeneratingBeforeFailure(failedIndex: Int): Boolean {
        val last = if (failedIndex < steps.size) failedIndex else steps.size - 1
        for (i in 0..last) {
            if (i < failedIndex && steps[i].status == MvThinkStepStatus.generating) return true
            for (card in steps[i].cards) {
                if (card.status == MvThinkCardStatus.generating) return true
                if (card.items?.any { it.status == MvThinkCardStatus.generating } == true) return true
            }
        }
        return false
    }

    private fun isFinalStepSucceeded(): Boolean {
        val idx = finalStepIndex ?: return false
        if (idx >= steps.size) return false
        val step = steps[idx]
        return step.isFinalStep && step.status == MvThinkStepStatus.success
    }

    private fun syncPollingControl() {
        if (!needsPreviewUnlock && hasLockedShots()) {
            // 解锁后后端会重开生成。锁还在时不要再次标完成。
            flowCompleted = false
            setDeletable(false)
            resumePolling()
            return
        }
        if (isFinalStepSucceeded()) {
            flowCompleted = true
            setDeletable(true)
            UmengUtils.logEvent("flow_finish", MODE)
            pausePolling("isFinalStep=true 且步骤成功，流程已完成 (step=$finalStepIndex)")
            // 仍锁定才补查积分，查完再显示底部解锁按钮。
            if (needsPreviewUnlock) loadPreviewUnlockPoints()
            return
        }
        val payIndex = findPausePollingPayStepIndex()
        if (payIndex != null) {
            setDeletable(true)
            pausePolling("待支付且卡片均已成功 (step=$payIndex, isPay=${steps[payIndex].isPay})")
            update()
            return
        }
        val failedIndex = firstVisibleFailedStepIndex()
        // 前面步骤或卡片仍在生成时先不停，等它们终态后再按失败收口。
        if (failedIndex != null && !hasGeneratingBeforeFailure(failedIndex)) {
            setDeletable(true)
            UmengUtils.logEvent("flow_fail", MODE)
            pausePolling("步骤失败 (step=$failedIndex, isRetryable=${isRetryableStep(failedIndex)})")
            return
        }
        setDeletable(false)
        if (pollingPaused) resumePolling()
        syncPayButtonVisibilityScroll()
    }

    @kotlin.jvm.JvmName("setDeletableFn")
    private fun setDeletable(value: Boolean) {
        isDeletable = value
        page.isDeletable = value
    }

    private fun pausePolling(reason: String) {
        pollingPaused = true
        if (PollingService.isTaskActive(ID)) {
            DevLog.logger("[ThinkLineMv] 停止轮询: $reason, taskId=$taskId")
            PollingService.stopTask(ID)
        }
    }

    private fun syncPayButtonVisibilityScroll() {
        val visible = showPayButton
        if (visible && !wasPayButtonVisible) {
            page.postDelayed(16) { if (showPayButton) scrollToBottom(force = true) }
        }
        wasPayButtonVisible = visible
    }

    private val currentStepShouldForceScrollToBottom: Boolean
        get() {
            if (payCompleted) return false
            val index = if (activeStepIndex >= 0) activeStepIndex else maxVisibleStep
            if (index < 0 || index >= steps.size) return false
            return steps[index].isPay == 1
        }

    private fun resumePolling() {
        if (flowCompleted || isClosed) return
        pollingPaused = false
        if (PollingService.isTaskActive(ID)) return
        registerPolling(startImmediately = true)
    }

    // ------------------------------------------------------------------ 评分 / 反馈

    fun onFeedbackRatingChanged(starRating: Int) {
        if (starRating <= 0) return
        page.hideKeyboard()
        if (litStars != starRating) {
            litStars = starRating
            feedbackModel = feedbackModel.copy(starRating = starRating, hasRated = true)
            update()
        }
        if (!feedbackFormScrolled) {
            feedbackFormScrolled = true
            scrollToBottom(force = true)
        }
        if (taskId <= 0) return
        page.launch { ThinkLineMvApi.rateMv(taskId, starRating) }
    }

    suspend fun onFeedbackSubmit(starRating: Int, tagCodes: List<String>, content: String) {
        if (taskId <= 0 || starRating <= 0) return
        val res = ThinkLineMvApi.submitFeedback(taskId, starRating, tagCodes, content)
        if (res.isSuccess && !isClosed) {
            feedbackModel = feedbackModel.copy(
                starRating = starRating,
                hasRated = true,
                hasFeedback = true,
                recommendTagCodes = tagCodes,
                content = content,
            )
            litStars = max(litStars, starRating)
            update()
            page.toast("feedback_submit_success".tr)
        }
    }

    // ------------------------------------------------------------------ 解锁 / 继续生成

    /** 未解锁成片不走全屏弹窗，进独立试看页，进度条和区间锁都在那一页。 */
    fun openPreviewPlay() {
        if (isClosed || taskId <= 0) return
        page.nav.push(Routes.mvPreviewPlay, JSONObject().put("id", taskId))
    }

    /** 底部解锁按钮不弹窗，直接扣费。 */
    fun unlockPreviewDirect() {
        if (isClosed || !needsPreviewUnlock) return
        page.launch { WorksPreviewUnlock.unlock(page, taskId, needPoint) { onPreviewUnlocked() } }
    }

    private fun onPreviewUnlocked() {
        if (isClosed) return
        // 扣费成功后任务回到生成中，信息流去掉解锁态，按原继续生成逻辑恢复轮询，不跳页。
        previewUnlock = 1
        afterContinuePaid(forcePoll = true, unlock = 1)
    }

    fun onPayToContinue() {
        if (page.isPayLoading) return
        page.isPayLoading = true
        UmengUtils.logEvent("flow_confirm", MODE)
        page.launch {
            // 不回传故事片段，服务端沿用已生成内容
            val res = ThinkLineMvApi.continueGenMv(taskId, needPoint)
            if (isClosed) return@launch
            if (res.isSuccess) afterContinuePaid()
            page.isPayLoading = false
        }
    }

    /**
     * [forcePoll] 试看解锁时为 true：合成成功已停轮询，扣费后要重新拉生成中的卡片。
     * [unlock] 仅试看解锁传入 1，继续生成不改列表上的 unlock。
     */
    private fun afterContinuePaid(forcePoll: Boolean = false, unlock: Int? = null) {
        payCompleted = true
        needPoint = 0
        wasPayButtonVisible = false
        if (forcePoll) {
            flowCompleted = false
            pollingPaused = false
        }
        update()
        pollAsync()
        resumePolling()
        refreshMvInLibrary(forcePoll = forcePoll, unlock = unlock)
        page.launch { getMvFeedback(); update() }
        page.launch { UserStore.getUserInfo() }
    }

    private fun tryRevealNextPendingStep() {
        if (steps.isEmpty() || maxVisibleStep < 0) return
        ensureRuntimeCapacity(steps.size)
        if (maxVisibleStep >= steps.size) return
        if (steps[maxVisibleStep].isFinalStep) return

        val cur = maxVisibleStep
        if (cur < stepStatuses.size &&
            stepReadFlags[cur] &&
            !stepAdvanced[cur] &&
            stepStatuses[cur] == MvThinkStepStatus.generating &&
            steps[cur].status != MvThinkStepStatus.generating
        ) {
            stepStatuses[cur] = steps[cur].status
            tryAdvance(cur)
            return
        }
        val next = maxVisibleStep + 1
        if (next >= steps.size) return
        for (i in 0 until next) if (stepStatuses[i] == MvThinkStepStatus.failed) return
        val prev = maxVisibleStep
        if (!stepAdvanced[prev] || stepStatuses[prev] == MvThinkStepStatus.failed) return
        revealStep(next)
    }

    fun isRetryableStep(stepIndex: Int): Boolean = steps[stepIndex].isRetryable

    private fun isRetryableFailedStepFromApi(stepIndex: Int): Boolean =
        stepIndex >= 0 && stepIndex < steps.size &&
            steps[stepIndex].status == MvThinkStepStatus.failed && steps[stepIndex].isRetryable

    fun shouldShowRetryCard(stepIndex: Int): Boolean =
        isRetryableFailedStepFromApi(stepIndex) && stepIndex < cardRevealDone.size && cardRevealDone[stepIndex]

    private fun refreshMvInLibrary(forcePoll: Boolean = false, unlock: Int? = null) {
        MvService.refreshMvStatus(taskId, MvStatus.generating, unlock = unlock, forcePoll = forcePoll)
    }

    // ------------------------------------------------------------------ 重试

    private fun findCardLocation(uniqueKey: Long): Pair<Int, Int>? {
        for (si in steps.indices) {
            for (ci in steps[si].cards.indices) {
                if (steps[si].cards[ci].uniqueKey == uniqueKey) return si to ci
            }
        }
        return null
    }

    private fun applyCardRetryGenerating(uniqueKey: Long) {
        val (stepIndex, cardIndex) = findCardLocation(uniqueKey) ?: return
        ensureRuntimeCapacity(steps.size)
        stepStatuses[stepIndex] = MvThinkStepStatus.generating
        val step = steps[stepIndex]
        val cards = ArrayList(step.cards)
        val target = cards[cardIndex]
        // 重试只能更新 uniqueKey 对应的卡片，禁止同一步骤的歌手四视图覆盖封面槽位
        if (target.isSingerImageCard) {
            val items = target.items?.map { if (it.status != MvThinkCardStatus.failed) it else it.copy(status = MvThinkCardStatus.generating) }
            cards[cardIndex] = target.copy(items = items)
        } else if (target.isImageCard && target.status == MvThinkCardStatus.failed) {
            cards[cardIndex] = target.copy(status = MvThinkCardStatus.generating)
        }
        steps[stepIndex] = step.copy(status = MvThinkStepStatus.generating, cards = cards)
        setDeletable(false)
    }

    /** 重试指定失败卡片；成功后立即刷新信息流并恢复轮询。 */
    fun onRepairCrad(uniqueKey: Long) {
        if (retryingCardUniqueKeys.contains(uniqueKey)) return
        page.hideKeyboard()
        retryingCardUniqueKeys.add(uniqueKey)
        page.showLoading()
        page.launch {
            val ok = ThinkLineMvApi.retryCard(taskId, uniqueKey).isSuccess
            if (isClosed) {
                page.hideLoading()
                retryingCardUniqueKeys.remove(uniqueKey)
                return@launch
            }
            if (ok) {
                applyCardRetryGenerating(uniqueKey)
                update()
                page.hideLoading()
                poll()
                if (isClosed) {
                    retryingCardUniqueKeys.remove(uniqueKey)
                    return@launch
                }
                resumePolling()
                refreshMvInLibrary()
                page.toast("retry_success".tr)
            } else {
                page.hideLoading()
                page.toast("retry_failed".tr)
            }
            retryingCardUniqueKeys.remove(uniqueKey)
        }
    }

    /** 重试失败步骤：立刻收起旧卡片，等轮询到新 cards 再逐张揭示 */
    fun onRepairStep(stepIndex: Int) {
        if (stepIndex < 0 || stepIndex >= steps.size) return
        if (page.isRetryLoading) return
        page.isRetryLoading = true
        UmengUtils.logEvent("flow_retry", MODE)
        val step = steps[stepIndex]
        page.launch {
            val ok = ThinkLineMvApi.retryStage(taskId, step.type).isSuccess
            if (isClosed) return@launch
            if (ok && stepIndex < steps.size) {
                resumePolling()
                ensureRuntimeCapacity(steps.size)
                stepAdvanced[stepIndex] = false
                stepStatuses[stepIndex] = MvThinkStepStatus.generating
                // 记下失败批签名，轮询滞后返回旧 cards 时不闪回
                repairHiddenCardSignatures[stepIndex] = cardsSignature(steps[stepIndex].cards)
                resetStepCardReveal(stepIndex)
                activeStepIndex = stepIndex
                isStepTyping[stepIndex] = false
                syncDisplayedTextForStep(stepIndex)
                setDeletable(false)
                // 立刻收起旧卡片；空列表不走揭示推进，等轮询带来新 cards
                steps[stepIndex] = steps[stepIndex].copy(status = MvThinkStepStatus.generating, cards = emptyList())
                update()
                refreshMvInLibrary()
                launchUserInfo()
            }
            DevLog.logger("onRepair step=${step.type} taskId=$taskId")
            page.isRetryLoading = false
        }
    }

    private fun launchUserInfo() {
        page.launch { UserStore.getUserInfo() }
    }

    // ------------------------------------------------------------------ 删除 / 卡片点击

    fun openDeleteDialog() {
        if (!isDeletable) {
            page.toast("task_in_progress_try_later".tr)
            return
        }
        page.showConfirmDialog("confirm_delete_mv_no_refund".trParams("name" to "music_video".tr)) { deleteMv() }
    }

    fun onCardTap(stepIndex: Int, card: MvThinkCard) {
        if (!card.isActionRowCard) return
        val params = card.bodyText
        val route = card.route ?: ""
        if (route.isEmpty()) return
        when (route) {
            "/edit_video" -> {
                // 高潮试看锁定时不提供编辑
                if (needsPreviewUnlock) return
                // 合成接口受理后按入口直接回根页，避免停在思考线
                page.nav.push(Routes.editVideo, JSONObject().put("id", taskId).put("type", "think_line"))
            }
            "/mv_list_detail" -> {
                page.nav.push(Routes.mvListDetail, JSONObject().put("id", taskId).put("type", "push").put("dataType", 0))
            }
            "/story_line" -> {
                if (params.isNullOrEmpty()) return
                try {
                    val segments = SegmentItem.listFromJson(JSONArray(params))
                    // 只查看已生成片段，不接收编辑结果
                    page.nav.push(Routes.storyLine, JSONObject().put("segments", SegmentItem.listToJson(segments)))
                } catch (e: Throwable) {
                    LogUtils.logRed("story line segments error: $e")
                }
            }
        }
    }

    /** Recreate 成功当下恢复轮询，不等 retry_shots 返回。 */
    fun resumePollingAfterShotRetry() {
        if (isClosed) return
        refreshMvInLibrary()
        pollAsync(ignorePollingPause = true)
        resumePolling()
    }

    fun onErrorCardTap(card: MvThinkCard) {
        if (card.type != MvThinkCardType.error) return
        if (card.route == "retryFailedShots") page.nav.push(Routes.retryShots, JSONObject().put("id", taskId))
    }

    fun deleteMv() {
        page.showLoading()
        page.launch {
            val res = ThinkLineMvApi.deleteMv(taskId)
            if (res.isSuccess) {
                MvService.refreshMv(
                    "delete",
                    MvModel(id = taskId, title = "", status = MvStatus.failed, userId = 0L, audioUrl = "", subDurationScope = ""),
                )
                MvService.removeThinkLineMvDisplayCache(taskId)
                page.hideLoading()
                page.toast("delete_success".tr)
                // 已删除：关页时不再写回展示缓存
                steps.clear()
                page.nav.pop()
            } else {
                page.hideLoading()
                DevLog.logger(res.errorOrNull()?.message ?: "")
                page.toast("delete_failed".tr)
            }
        }
    }

    // ------------------------------------------------------------------ 步骤展示（三阶段）

    fun displayedTitleAt(index: Int): String = displayedTitles.getOrElse(index) { "" }

    fun displayedDescriptionAt(index: Int): String = displayedDescriptions.getOrElse(index) { "" }

    private fun syncDisplayedTextForStep(index: Int) {
        if (index < 0 || index >= steps.size) return
        ensureRuntimeCapacity(steps.size)
        if (index >= isStepTyping.size || index >= stepReadFlags.size ||
            index >= displayedTitles.size || index >= displayedDescriptions.size
        ) return
        val step = steps[index]
        if (!step.isRead && (isStepTyping[index] || !stepReadFlags[index])) return
        if (displayedTitles[index] == step.title && displayedDescriptions[index] == step.description) return
        displayedTitles[index] = step.title
        displayedDescriptions[index] = step.description
    }

    private fun catchUpHistoricalSteps() {
        if (steps.isEmpty()) return
        ensureRuntimeCapacity(steps.size)
        var index = 0
        while (index < steps.size) {
            val step = steps[index]
            if (!step.isRead) break
            revealHistoricalStepInstant(index)
            if (step.status == MvThinkStepStatus.failed) {
                index++
                break
            }
            index++
        }
        update()
        val nextIndex = index
        page.postDelayed(16) {
            if (isClosed) return@postDelayed
            scrollToBottom(force = true)
            if (nextIndex < steps.size) revealStep(nextIndex)
        }
    }

    private fun revealHistoricalStepInstant(index: Int) {
        if (index < 0 || index >= steps.size) return
        ensureRuntimeCapacity(steps.size)
        val step = steps[index]
        activeStepIndex = index
        maxVisibleStep = index
        syncDisplayedTextForStep(index)
        stepStatuses[index] = step.status
        stepReadFlags[index] = true
        stepAdvanced[index] = true
        cardRevealDone[index] = true
        isStepTyping[index] = false
        showStepCardsInstantly(index)
    }

    private fun revealStep(index: Int) {
        if (isClosed) return
        if (index < 0 || index >= steps.size) return
        ensureRuntimeCapacity(steps.size)
        syncMaxVisibleStepBounds()
        if (index > 0) {
            for (i in 0 until index) {
                if (i >= stepStatuses.size) return
                if (stepStatuses[i] == MvThinkStepStatus.failed) return
            }
        }
        if (maxVisibleStep >= index) return

        activeStepIndex = index
        maxVisibleStep = index
        val step = steps[index]

        if (step.isRead) {
            revealHistoricalStepInstant(index)
            update()
            scrollToBottom(force = true)
            if (step.status == MvThinkStepStatus.failed) {
                syncPollingControl()
            } else if (!step.isFinalStep && index + 1 < steps.size) {
                revealStep(index + 1)
            } else {
                syncPollingControl()
            }
            return
        }

        // 最终步骤成功后轮询会停止，首次展示时不能把终态回退为生成中。
        stepStatuses[index] = if (step.isFinalStep && step.status == MvThinkStepStatus.success) step.status else MvThinkStepStatus.generating
        isStepTyping[index] = true
        update()
        runTypingPhases(index)
        scrollToBottom(force = true)
    }

    // ── 阶段 1 / 2：打字机 ──

    private fun runTypingPhases(stepIndex: Int) {
        if (stepIndex < 0 || stepIndex >= steps.size) return
        ensureRuntimeCapacity(steps.size)
        var phaseTitle = true
        var charPos = 0
        val gen = ++typingGen
        startTypingScrollFollow()

        fun tick() {
            if (isClosed || gen != typingGen) return
            if (stepIndex >= steps.size) return
            val step = steps[stepIndex]
            val isTerminal = stepStatuses[stepIndex] != MvThinkStepStatus.generating
            if (phaseTitle) {
                // 先清理非法代理项，清理后为空时继续等待轮询文案或步骤终态。
                val title = stripUnpairedSurrogates(step.title)
                if (title.isEmpty()) {
                    if (isTerminal) {
                        enterPhase3ReadAndCards(stepIndex)
                        return
                    }
                } else {
                    val len = graphemeLength(title)
                    if (charPos > len) charPos = len
                    if (charPos < len) {
                        displayedTitles[stepIndex] = truncateGraphemes(title, ++charPos)
                        update()
                        followScrollTowardBottom()
                    } else {
                        phaseTitle = false
                        charPos = 0
                    }
                }
            } else {
                val description = stripUnpairedSurrogates(step.description)
                if (description.isEmpty()) {
                    if (isTerminal) {
                        enterPhase3ReadAndCards(stepIndex)
                        return
                    }
                } else {
                    val len = graphemeLength(description)
                    if (charPos > len) charPos = len
                    if (charPos < len) {
                        displayedDescriptions[stepIndex] = truncateGraphemes(description, ++charPos)
                        update()
                        followScrollTowardBottom()
                    } else {
                        enterPhase3ReadAndCards(stepIndex)
                        return
                    }
                }
            }
            page.postDelayed(TYPING_INTERVAL_MS) { tick() }
        }
        page.postDelayed(TYPING_INTERVAL_MS) { tick() }
    }

    // ── 阶段 3：标记已读 + 卡片动画 ──

    private fun enterPhase3ReadAndCards(stepIndex: Int) {
        if (stepIndex < 0 || stepIndex >= steps.size) return
        ensureRuntimeCapacity(steps.size)
        stopTypingScrollFollow()
        isStepTyping[stepIndex] = false
        stepReadFlags[stepIndex] = true
        update()
        scrollToBottom()
        markStepReadIfNeeded(stepIndex)
        tryAdvance(stepIndex)
    }

    private fun markStepReadIfNeeded(stepIndex: Int) {
        val step = steps[stepIndex]
        if (step.isRead) {
            markReadFailedFlowIds.remove(step.flowId)
            return
        }
        UmengUtils.logEvent("flow_update", MODE)
        page.launch {
            var result = ThinkLineMvApi.markFlowRead(step.flowId).isSuccess
            var retryCount = 0
            while (!result && retryCount < 3) {
                delayMs(1000)
                result = ThinkLineMvApi.markFlowRead(step.flowId).isSuccess
                retryCount++
            }
            markReadFailedFlowIds.remove(step.flowId)
            if (result) {
                DevLog.logger("更新 MV 步骤阅读状态: id=${step.id}, success=true")
                pollAsync(ignorePollingPause = true)
            } else {
                DevLog.logger("更新 MV 步骤阅读状态失败: id=${step.id}, success=false")
                markReadFailedFlowIds.add(step.flowId)
            }
        }
    }

    // ── 步骤推进 ──

    private fun tryAdvance(stepIndex: Int) {
        if (stepIndex < 0 || stepIndex >= steps.size) return
        ensureRuntimeCapacity(steps.size)
        val step = steps[stepIndex]
        if (step.isRead) {
            syncDisplayedTextForStep(stepIndex)
            if (!stepReadFlags[stepIndex]) stepReadFlags[stepIndex] = true
        }
        if (!stepReadFlags[stepIndex]) return
        if (!cardRevealDone[stepIndex]) {
            startStepCardReveal(stepIndex)
            return
        }
        tryProceedToNextStep(stepIndex)
    }

    private fun startStepCardReveal(stepIndex: Int): Boolean {
        if (stepIndex < 0 || stepIndex >= steps.size) return false
        if (!stepReadFlags[stepIndex]) return false
        if (cardRevealInProgress[stepIndex]) return false
        val step = steps[stepIndex]
        val showRetry = isRetryableFailedStepFromApi(stepIndex)

        if (step.cards.isEmpty()) {
            // 重试后故意收起，空列表不代表揭示完成，避免立刻推进下一步
            if (repairHiddenCardSignatures.containsKey(stepIndex)) return false
            if (cardRevealDone[stepIndex]) return false
            cardRevealDone[stepIndex] = true
            update()
            tryProceedToNextStep(stepIndex, showRetry)
            return true
        }
        if (cardRevealDone[stepIndex]) {
            if (visibleCardCounts[stepIndex] >= step.cards.size) return false
            revealCardsSequentially(stepIndex, showRetry, startFrom = visibleCardCounts[stepIndex])
            return true
        }
        revealCardsSequentially(stepIndex, showRetry)
        return true
    }

    private fun tryProceedToNextStep(stepIndex: Int, showRetryOnComplete: Boolean = false) {
        if (stepIndex < 0 || stepIndex >= steps.size) return
        ensureRuntimeCapacity(steps.size)
        if (!stepReadFlags[stepIndex] || !cardRevealDone[stepIndex]) return
        if (stepAdvanced[stepIndex]) return

        val step = steps[stepIndex]
        val failed = step.status == MvThinkStepStatus.failed
        val showRetry = showRetryOnComplete || isRetryableFailedStepFromApi(stepIndex)

        if (showRetry) {
            stepAdvanced[stepIndex] = true
            update()
            // 重试卡插入后高度超过底部阈值，必须强制跟随，否则贴底用户看不到新卡
            scrollToBottom(force = true)
            syncPollingControl()
            return
        }
        if (failed) {
            stepAdvanced[stepIndex] = true
            scrollToBottom(force = true)
            syncPollingControl()
            return
        }
        if (stepStatuses[stepIndex] == MvThinkStepStatus.generating) {
            syncPollingControl()
            return
        }
        stepAdvanced[stepIndex] = true
        if (!step.isFinalStep && stepIndex + 1 < steps.size) {
            page.postDelayed(STEP_TRANSITION_DELAY_MS) { revealStep(stepIndex + 1) }
        } else {
            scrollToBottom()
        }
        syncPollingControl()
    }

    // ── 卡片 ──

    private fun revealCardsSequentially(stepIndex: Int, showRetryOnComplete: Boolean = false, startFrom: Int = 0) {
        if (stepIndex < 0 || stepIndex >= steps.size) return
        ensureRuntimeCapacity(steps.size)
        if (cardRevealInProgress[stepIndex]) return
        if (startFrom >= steps[stepIndex].cards.size) {
            completeSequentialCardReveal(stepIndex, showRetryOnComplete)
            return
        }
        cardRevealInProgress[stepIndex] = true
        stepCardsUseAnimatedReveal[stepIndex] = true
        var cardIndex = startFrom
        if (startFrom == 0) visibleCardCounts[stepIndex] = 0

        // 闭包只记世代号，每次读实时 cards；reset 后旧 delayed 自动作废
        val generation = cardRevealGenerations[stepIndex]

        fun revealNext() {
            if (isClosed) return
            if (stepIndex < 0 || stepIndex >= steps.size) return
            if (generation != cardRevealGenerations[stepIndex]) return
            val currentCards = steps[stepIndex].cards
            if (currentCards.isEmpty() || cardIndex >= currentCards.size) {
                cardRevealInProgress[stepIndex] = false
                completeSequentialCardReveal(stepIndex, showRetryOnComplete)
                return
            }
            visibleCardCounts[stepIndex] = cardIndex + 1
            update()
            scrollToBottom()
            cardIndex++
            page.postDelayed(CARD_REVEAL_INTERVAL_MS) { revealNext() }
        }
        page.postDelayed(CARD_REVEAL_INITIAL_DELAY_MS) { revealNext() }
    }

    private fun completeSequentialCardReveal(stepIndex: Int, showRetryOnComplete: Boolean) {
        cardRevealDone[stepIndex] = true
        tryProceedToNextStep(stepIndex, showRetryOnComplete)
    }

    private fun resetStepCardReveal(stepIndex: Int) {
        visibleCardCounts[stepIndex] = 0
        stepCardsUseAnimatedReveal[stepIndex] = false
        cardRevealInProgress[stepIndex] = false
        cardRevealDone[stepIndex] = false
        if (stepIndex < cardRevealGenerations.size) cardRevealGenerations[stepIndex]++
    }

    private fun cardsSignature(cards: List<MvThinkCard>): String {
        if (cards.isEmpty()) return "empty"
        return cards.joinToString("|") { "${it.uniqueKey}:${it.type.name}:${it.status.name}" }
    }

    private fun dropStaleRepairedStepCards() {
        for ((i, sig) in repairHiddenCardSignatures.entries.toList()) {
            if (i < 0 || i >= steps.size) {
                repairHiddenCardSignatures.remove(i)
                continue
            }
            if (cardsSignature(steps[i].cards) == sig) {
                // 轮询仍是失败那一批，继续收起避免闪回旧卡
                steps[i] = steps[i].copy(status = MvThinkStepStatus.generating, cards = emptyList())
            } else {
                repairHiddenCardSignatures.remove(i)
            }
        }
    }

    private fun clampVisibleCardCounts() {
        val n = min(visibleCardCounts.size, steps.size)
        for (i in 0 until n) {
            val maxCount = steps[i].cards.size
            if (visibleCardCounts[i] > maxCount) visibleCardCounts[i] = maxCount
        }
    }

    private fun showStepCardsInstantly(stepIndex: Int) {
        stepCardsUseAnimatedReveal[stepIndex] = false
        cardRevealInProgress[stepIndex] = false
        visibleCardCounts[stepIndex] = steps[stepIndex].cards.size
    }

    // ------------------------------------------------------------------ 自动滚动

    private fun isNearScrollBottom(): Boolean {
        val view = page.scrollViewHeight
        if (view <= 0f) return true
        val distanceFromBottom = page.scrollContentHeight - view - page.scrollOffsetY
        return distanceFromBottom <= SCROLL_BOTTOM_THRESHOLD
    }

    private var scrollToBottomScheduled = false
    private var pendingScrollForce = false

    private fun startTypingScrollFollow() {
        typingScrollFollowActive = true
    }

    private fun stopTypingScrollFollow() {
        typingScrollFollowActive = false
    }

    /** 打字期间每帧向底部插值靠拢（仅在贴底且未触摸时）。 */
    private fun followScrollTowardBottom() {
        if (!typingScrollFollowActive || userIsTouching) return
        page.postDelayed(16) {
            if (isClosed || userIsTouching || !isNearScrollBottom()) return@postDelayed
            val target = (page.scrollContentHeight - page.scrollViewHeight).coerceAtLeast(0f)
            val current = page.scrollOffsetY
            val delta = target - current
            if (abs(delta) <= 0.5f) return@postDelayed
            val next = (current + delta * TYPING_SCROLL_LERP_FACTOR).coerceIn(0f, target)
            page.setScrollOffset(next, animated = false)
        }
    }

    fun scrollToBottom(force: Boolean = false) {
        if (userIsTouching) return
        val shouldForce = force || currentStepShouldForceScrollToBottom
        if (!shouldForce && !isNearScrollBottom()) return
        pendingScrollForce = pendingScrollForce || shouldForce
        if (scrollToBottomScheduled) return
        scrollToBottomScheduled = true
        // 等本次状态变更布局完成（内容高度回调）后再滚
        page.postDelayed(80) {
            scrollToBottomScheduled = false
            val scrollForce = pendingScrollForce
            pendingScrollForce = false
            if (isClosed || userIsTouching) return@postDelayed
            if (!scrollForce && !isNearScrollBottom()) return@postDelayed
            val target = (page.scrollContentHeight - page.scrollViewHeight).coerceAtLeast(0f)
            page.setScrollOffset(target, animated = true)
        }
    }

    // ------------------------------------------------------------------ 预览

    fun showPreviewDialog(card: MvThinkCard, isVideo: Boolean, index: Int) {
        page.hideKeyboard()
        val list = card.items
        if (list.isNullOrEmpty()) return
        // displayStatus 不会返回 locked，有封面时会被当成成功，过滤必须看 status。
        val playable = list.filter { it.status != MvThinkCardStatus.locked }
        if (playable.isEmpty()) return
        val previewIndex = list.take(index).count { it.status != MvThinkCardStatus.locked }
        if (isVideo) {
            val items = playable.map { e ->
                VideoPreviewItem(
                    url = e.videoUrl ?: "",
                    status = when (e.displayStatus) {
                        MvThinkCardStatus.generating -> VideoPreviewItem.STATUS_GENERATING
                        MvThinkCardStatus.success -> VideoPreviewItem.STATUS_SUCCESS
                        MvThinkCardStatus.failed, MvThinkCardStatus.locked -> VideoPreviewItem.STATUS_FAILED
                    },
                    coverUrl = e.imageUrl ?: "",
                    startSeconds = e.startSeconds,
                )
            }
            ThinkLinePreview.showVideos(
                page,
                items,
                previewIndex,
                musicUrl = musicUrl,
                startEndScope = mvStartEndTime,
                // 未解锁只让看分镜，不提供下载。
                showDownloadButton = !needsPreviewUnlock,
            )
            return
        }
        val images = playable.map { e ->
            ImagePreviewItem(
                e.previewUrl,
                when (e.displayStatus) {
                    MvThinkCardStatus.generating -> ImagePreviewItem.STATUS_GENERATING
                    MvThinkCardStatus.success -> ImagePreviewItem.STATUS_SUCCESS
                    MvThinkCardStatus.failed, MvThinkCardStatus.locked -> ImagePreviewItem.STATUS_FAILED
                },
            )
        }
        ThinkLinePreview.showImages(page, images, previewIndex)
    }

    fun showSingleImagePreview(card: MvThinkCard) {
        page.hideKeyboard()
        val status = when (card.status) {
            MvThinkCardStatus.generating -> ImagePreviewItem.STATUS_GENERATING
            MvThinkCardStatus.success -> ImagePreviewItem.STATUS_SUCCESS
            MvThinkCardStatus.failed -> ImagePreviewItem.STATUS_FAILED
            MvThinkCardStatus.locked -> ImagePreviewItem.STATUS_LOCKED
        }
        ThinkLinePreview.showImages(page, listOf(ImagePreviewItem(card.imageUrl ?: "", status)), 0)
    }

    /** 成片卡全屏：未解锁进试看页，否则全屏预览（带回位置后内联继续）。 */
    fun openMvPreviewFullscreen(card: MvThinkCard, positionMs: Long, done: () -> Unit) {
        if (needsPreviewUnlock) {
            openPreviewPlay()
            done()
            return
        }
        ThinkLinePreview.showVideos(
            page,
            listOf(VideoPreviewItem.success(card.videoUrl ?: "", coverUrl = card.firstFrameImg ?: "")),
            0,
            initialPositionMs = positionMs,
            musicUrl = musicUrl,
            startEndScope = mvStartEndTime,
        ) { done() }
    }

    // ------------------------------------------------------------------ update → 响应式展示态

    /** Dart `update([_id])`：把普通状态同步到页面的展示态。 */
    fun update() {
        if (isClosed) return
        page.needPoint = needPoint
        page.imageComp = imageComp
        page.needsPreviewUnlock = needsPreviewUnlock
        page.isDeletable = isDeletable
        page.showUnlockButton = showPreviewUnlockButton
        page.showPayButton = !needsPreviewUnlock && showPayButton
        val seed = TLFeedbackSeed(
            rating = starCardRating,
            tagCodes = feedbackModel.recommendTagCodes,
            content = feedbackModel.content,
            tags = LinkedHashMap(recommendTags),
        )
        if (page.feedbackSeed != seed) page.feedbackSeed = seed

        val rows = page.stepRows
        val n = visibleStepCount
        while (rows.size > n) rows.removeAt(rows.size - 1)
        while (rows.size < n) rows.add(TLStepVm(page, rows.size))
        for (i in 0 until n) syncStepRow(rows[i], i)
    }

    private fun syncStepRow(vm: TLStepVm, i: Int) {
        if (i >= steps.size || i >= visibleCardCounts.size) return
        val step = steps[i]
        val title = displayedTitleAt(i)
        val desc = displayedDescriptionAt(i)
        if (vm.title != title) vm.title = title
        if (vm.desc != desc) vm.desc = desc
        val failed = stepStatuses.getOrNull(i) == MvThinkStepStatus.failed
        if (vm.titleFailed != failed) vm.titleFailed = failed
        val icon = stepIcon(i)
        if (vm.icon != icon) vm.icon = icon
        val isLast = i == maxVisibleStep
        if (vm.isLast != isLast) vm.isLast = isLast
        if (vm.durationSec != step.durationSec) vm.durationSec = step.durationSec

        // 重试后轮询可能先缩短 cards，visibleCardCounts 仍可能偏大，夹紧避免下标越界
        val visibleCards = min(visibleCardCounts[i], step.cards.size)
        val showCards = step.cards.isNotEmpty() && stepReadFlags.getOrElse(i) { false } && visibleCards > 0
        val noCardRetry = step.cards.isEmpty() && !showCards
        val showRetry = shouldShowRetryCard(i)
        if (vm.showCards != showCards) vm.showCards = showCards
        if (vm.noCardRetry != noCardRetry) vm.noCardRetry = noCardRetry
        if (vm.showRetry != showRetry) vm.showRetry = showRetry
        val star = shouldShowStarCard(i)
        if (vm.starVisible != star) vm.starVisible = star

        val target = if (showCards) visibleCards else 0
        val cards = vm.cards
        while (cards.size > target) cards.removeAt(cards.size - 1)
        for (ci in 0 until target) {
            val card = step.cards[ci]
            if (ci < cards.size) {
                val cvm = cards[ci]
                if (cvm.card.type != card.type) {
                    cards[ci] = vm.newCard(card, stepCardsUseAnimatedReveal[i] && !isListCard(card))
                } else if (cvm.card != card) {
                    cvm.card = card
                }
            } else {
                cards.add(vm.newCard(card, stepCardsUseAnimatedReveal[i] && !isListCard(card)))
            }
        }
    }

    private fun stepIcon(i: Int): TLStepIcon {
        val typing = isStepTyping.getOrElse(i) { false }
        val status = stepStatuses.getOrElse(i) { MvThinkStepStatus.generating }
        val step = steps[i]
        if (status == MvThinkStepStatus.failed) return TLStepIcon.failed
        // 最终步骤由任务状态收口，不等待服务端回写已读标记。
        if (typing || (!step.isFinalStep && !step.isRead)) return TLStepIcon.generating
        if (status == MvThinkStepStatus.success) return TLStepIcon.success
        if (status == MvThinkStepStatus.generating) return TLStepIcon.generating
        return TLStepIcon.none
    }
}

// ------------------------------------------------------------------ 文本工具（Dart grapheme helpers）

/** 去掉落单的 UTF-16 代理项。 */
internal fun stripUnpairedSurrogates(s: String): String {
    if (s.isEmpty()) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        if (ch.isHighSurrogate()) {
            if (i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                sb.append(ch).append(s[i + 1])
                i += 2
                continue
            }
        } else if (!ch.isLowSurrogate()) {
            sb.append(ch)
        }
        i++
    }
    return sb.toString()
}

/** 字符数（按码点计；输入已去掉落单代理项）。 */
internal fun graphemeLength(s: String): Int {
    var count = 0
    var i = 0
    while (i < s.length) {
        i += if (s[i].isHighSurrogate() && i + 1 < s.length) 2 else 1
        count++
    }
    return count
}

/** 取前 [n] 个字符（按码点，不拆代理对）。 */
internal fun truncateGraphemes(s: String, n: Int): String {
    var count = 0
    var i = 0
    while (i < s.length && count < n) {
        i += if (s[i].isHighSurrogate() && i + 1 < s.length) 2 else 1
        count++
    }
    return s.substring(0, i)
}
