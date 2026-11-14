package com.sondo.app.features.try_free

import com.sondo.app.base.AppHost
import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.i18n.trParams
import com.sondo.app.core.utils.CommonUtils
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.nowMs
import com.sondo.app.data.api.DictDataApi
import com.sondo.app.data.api.FileUploadApi
import com.sondo.app.data.models.ImageUploadStatus
import com.sondo.app.features.try_free.data.TrialMvApi
import com.sondo.app.features.try_free.data.TrialMvTemplateEntity
import com.sondo.app.features.try_free.data.TryFreeCaseEntity
import com.sondo.app.features.try_free.data.TryFreeMockCases
import com.sondo.app.features.try_free.widgets.TryFreeCaseCard
import com.sondo.app.features.try_free.widgets.TryFreeCaseCardSize
import com.sondo.app.features.try_free.widgets.TryFreeCaseSkeleton
import com.sondo.app.features.try_free.widgets.TryFreeCloseButton
import com.sondo.app.features.try_free.widgets.TryFreeColors
import com.sondo.app.ui.works.select.PhotoSelectAction
import com.sondo.app.ui.works.select.PhotoSelectItem
import com.sondo.app.ui.works.select.PhotoSelectResult
import com.sondo.app.ui.works.select.PhotoSelectSectionType
import com.sondo.app.ui.works.select.showPhotoSelect
import com.sondo.app.ui.works.upload.SDCreatorPhotoUploadSection
import com.sondo.app.features.try_free.widgets.TryFreeSpinner
import com.sondo.app.nativeapi.SDAppModule
import com.sondo.app.service.AppNav
import com.sondo.app.service.TryFreeTaskService
import com.sondo.app.service.UserStore
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.ui.dialog.VideoPreviewDismissResult
import com.sondo.app.ui.dialog.VideoPreviewItem
import com.sondo.app.ui.dialog.previewVideos
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.Scale
import com.tencent.kuikly.core.base.Translate
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.event.layoutFrameDidChange
import com.tencent.kuikly.core.directives.vforIndex
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import kotlin.math.abs
import kotlin.math.min

/**
 * 引导试用创建页（Dart `TryFreePage` + `TryFreeController`）。无入参。
 *
 * 关闭 / 系统返回（2 秒内两次）→ 回到主框架；提交成功后 replace 成 [Routes.tryFreeGenWaiting]。
 */
@Page(Routes.tryFreeCreate)
internal class TryFreeCreatePage : BasePager() {

    private val trackPage = UmengUtils.routePath(Routes.tryFreeCreate)

    /** 选图弹窗推荐预设图列表 */
    private val recommendItems = ArrayList<PhotoSelectItem>()

    // --- 案例数据 ---
    val cases by observableList<TryFreeCaseEntity>()

    /** 远端模板实体，与 [cases] 索引一一对应 */
    private val templates = ArrayList<TrialMvTemplateEntity>()
    var currentIndex by observable(0)

    /** 模板拉取中；轮播区展示骨架 */
    var isTemplatesLoading by observable(true)

    // --- 参考图上传 ---
    var imageUrl by observable("")
    var imageFile by observable<String?>(null)
    var isImageLoading by observable(false)
    var isImageFailed by observable(false)
    var isHasSensitive by observable(false)
    var errorMessage by observable("")
    var imageProgress by observable(0)

    /** 提交试用中，防重入 */
    var isSubmitting by observable(false)

    /** 决定底部按钮文案（免费试用 / 注册并免费试用） */
    var isLogin by observable(UserStore.isLogin)

    /** 全屏预览期间挂起轮播：释放解码器 */
    var isPlaybackSuspended by observable(false)

    /** 登录弹窗期间软暂停：保留解码器与当前帧 */
    var isLoginPlaybackPaused by observable(false)

    /** 全屏预览关闭后，卡片解码器重建时待 seek 的进度（毫秒） */
    var pendingSeekMs by observable<Long?>(null)

    /** 活跃卡最近一次播放进度；解码器未就绪为 null */
    private var activePositionMs: Long? = null

    /** 选图来源，供 trial_generate 上报：camera / album / recommend */
    private var photoSource = ""
    private var lastBackPressedTime = 0L
    private var closed = false

    // --- 轮播拖拽 ---
    var dragX by observable(0f)
    var settleGen by observable(0)
    private var dragStartX = 0f
    private var dragStartMs = 0L

    override fun pageBackgroundColor(): Color = TryFreeColors.bgPrimary

    override fun interceptBack(): Boolean = true

    override fun onBackPressed(): Boolean {
        handleDoubleBack()
        return true
    }

    override fun created() {
        super.created()
        // 登录成功 / 退出后同步底部按钮文案
        on(Events.USER_CHANGED) { isLogin = UserStore.isLogin }
        launch { loadTemplates() }
        launch { loadRecommendImages() }
    }

    override fun pageWillDestroy() {
        closed = true
        super.pageWillDestroy()
    }

    /** 两秒内重复返回才关闭页面，第一次提示。 */
    fun handleDoubleBack() {
        val now = nowMs()
        val isWarning = lastBackPressedTime == 0L || now - lastBackPressedTime > 2000L
        if (isWarning) {
            lastBackPressedTime = now
            toast("try_free_press_again_to_exit".tr)
        } else {
            onCloseTap()
        }
    }

    /** 关闭页面并进入首页主框架。 */
    fun onCloseTap() {
        UmengUtils.logEventButtonClick("trial_guide_close", pageName = trackPage)
        nav.popToRoot()
    }

    /** 轮播切换回调，切换后立即播放新案例。 */
    fun onCaseChanged(index: Int) {
        if (currentIndex == index) return
        currentIndex = index
        activePositionMs = null
        // 手动滑走时丢弃待 seek，避免套用到错误案例
        pendingSeekMs = null
        UmengUtils.logEventButtonClick("trial_case_switch", pageName = trackPage, result = currentTemplateTag)
    }

    /** 程序化切页（点侧边卡 / 全屏内切过案例）。 */
    fun moveTo(index: Int) {
        if (index < 0 || index >= cases.size) return
        settleGen++
        dragX = 0f
        onCaseChanged(index)
    }

    fun onPan(state: String, pageX: Float) {
        if (cases.size <= 1 || isTemplatesLoading) return
        when (state) {
            "start" -> {
                dragStartX = pageX
                dragStartMs = nowMs()
            }
            "move" -> {
                var dx = pageX - dragStartX
                // 不循环：首尾再往外拖加阻尼
                if ((currentIndex == 0 && dx > 0f) || (currentIndex == cases.size - 1 && dx < 0f)) dx *= 0.3f
                dragX = dx
            }
            else -> {
                val dx = dragX
                val dt = (nowMs() - dragStartMs).coerceAtLeast(1L)
                val fast = abs(dx) / dt > 0.5f && abs(dx) > 20f
                val threshold = TryFreeCaseCardSize.width / 4f
                var target = currentIndex
                if (dx < -threshold || (fast && dx < 0f)) target = currentIndex + 1
                if (dx > threshold || (fast && dx > 0f)) target = currentIndex - 1
                target = target.coerceIn(0, cases.size - 1)
                settleGen++
                dragX = 0f
                onCaseChanged(target)
            }
        }
    }

    fun onCardTap(index: Int) {
        if (index != currentIndex) {
            moveTo(index)
            return
        }
        // 先读进度再挂起：挂起后会释放解码器
        val position = activePositionMs
        activePositionMs = null
        val openedIndex = currentIndex
        launch {
            val result = openCasePreview(position)
            if (closed) return@launch
            // 全屏内切过案例时先对齐轮播，再恢复播放并带上续播进度
            if (result != null && result.index != openedIndex && result.index >= 0 && result.index < cases.size) {
                moveTo(result.index)
            }
            resumeAfterPreview(result)
        }
    }

    /** 点击当前案例卡：全屏放大播放；保持 [isPlaybackSuspended] 直到 [resumeAfterPreview]。 */
    private suspend fun openCasePreview(positionMs: Long?): VideoPreviewDismissResult? {
        if (cases.isEmpty()) return null
        UmengUtils.logEventButtonClick("trial_case_preview", pageName = trackPage, result = currentTemplateTag)
        isPlaybackSuspended = true
        // 试用案例仅为预览，不允许下载模板视频
        return previewVideos(
            videos = cases.map { VideoPreviewItem.success(it.videoUrl, coverUrl = it.coverUrl) },
            initialIndex = currentIndex,
            initialPositionMs = positionMs ?: 0L,
            showDownloadButton = false,
            resolveSource = { _, item -> if (item.url.startsWith("assets/")) item.url.removePrefix("assets/") else item.url },
        )
    }

    /** 全屏关闭：写入续播进度并恢复轮播解码器。 */
    private fun resumeAfterPreview(result: VideoPreviewDismissResult?) {
        pendingSeekMs = result?.positionMs
        isPlaybackSuspended = false
    }

    /** 卡片完成 seek 后清空。 */
    fun clearPendingSeek() {
        if (pendingSeekMs == null) return
        pendingSeekMs = null
    }

    fun onActivePosition(ms: Long?) {
        activePositionMs = ms
    }

    /** 打开选照片弹窗，本阶段不校验登录。 */
    fun openSelectPhoto() {
        if (isSubmitting || isImageLoading) return
        UmengUtils.logEventButtonClick("trial_upload_photo", pageName = trackPage)
        UmengUtils.logEventPopupView("trial_photo_select")
        showPhotoSelect(PhotoSelectSectionType.recommend, recommendItems.toList()) { result ->
            if (result != null) launch { onPhotoSelected(result) }
        }
    }

    /** 用户点 Sign Up：按状态校验后发起试用生成或提示。 */
    fun onSignUpTap() {
        if (isSubmitting) return
        launch {
            if (!UserStore.isLogin) {
                val ok = withLoginGate { UserStore.showLoginDialog(this@TryFreeCreatePage) }
                if (!ok || closed) return@launch
                // 登录成功后拉取最新用户信息（含 hasTried）
                UserStore.refreshUserInfo()
                if (closed) return@launch
            }
            // 已试用过则直接进入主页
            if ((UserStore.userInfo?.hasTried ?: 0) <= 0) {
                goRootWithToast("try_free_already_tried".tr)
                return@launch
            }
            if (isImageLoading) {
                toast("image_checking".tr)
                return@launch
            }
            if (isHasSensitive) {
                toast("image_violation".tr)
                return@launch
            }
            if (!imageUrl.startsWith("https")) {
                // 未上传有效照片时拦截试用提交
                toast("try_free_upload_then_try".tr)
                return@launch
            }
            UmengUtils.logEventButtonClick("trial_try_free", pageName = trackPage)
            isSubmitting = true
            try {
                submitTrialAndNavigate(imageUrl)
            } finally {
                if (!closed) isSubmitting = false
            }
        }
    }

    /** 选图完成：仅上传或设置预设图，不自动提交。 */
    private suspend fun onPhotoSelected(result: PhotoSelectResult) {
        if (isSubmitting || isImageLoading) return
        // 相册和相机需要登录
        if (result.action == PhotoSelectAction.camera || result.action == PhotoSelectAction.album) {
            if (!UserStore.isLogin) {
                val ok = withLoginGate { UserStore.showLoginDialog(this) }
                if (!ok || closed) return
                UserStore.refreshUserInfo()
                if (closed) return
            }
            if ((UserStore.userInfo?.hasTried ?: 0) <= 0) {
                goRootWithToast("try_free_already_tried".tr)
                return
            }
        }
        when (result.action) {
            PhotoSelectAction.camera -> {
                logPhotoSelect("camera")
                pickImage(result.imageSource ?: CommonUtils.SOURCE_CAMERA)
            }
            PhotoSelectAction.album -> {
                logPhotoSelect("album")
                pickImage(result.imageSource ?: CommonUtils.SOURCE_GALLERY)
            }
            PhotoSelectAction.itemSelected -> {
                val url = result.selectedItem?.imageUrl ?: return
                logPhotoSelect("recommend")
                selectPresetImage(url)
            }
            PhotoSelectAction.roleLibrary -> toast("try_free_gen_failed_hint".tr)
        }
    }

    /** 从相机或相册选参考图并上传检测。 */
    private suspend fun pickImage(source: String) {
        hideKeyboardSafe()
        try {
            val picked = CommonUtils.pickImage(
                source,
                pager = this,
                callback = { type -> if (type == "pick_image_success") isImageLoading = true },
                isCheckImageSize = true,
                isCheckImageAspectRatio = true,
                isCheckImageHW = true,
            )
            if (closed) return
            if (picked == null) {
                isImageLoading = false
                return
            }
            imageFile = picked
            retryUploadImage(picked)
        } catch (e: Throwable) {
            DevLog.logger("TryFreeCreatePage.pickImage error: $e")
            if (closed) return
            isImageLoading = false
            toast("image_pick_failed".tr)
        }
    }

    private fun hideKeyboardSafe() {
        try {
            CommonUtils.hideKeyboard()
        } catch (e: Throwable) {
            DevLog.logger("hideKeyboard error: $e")
        }
    }

    /** 上传并检测；成功写 URL，check 标敏感，failure 标失败（超限弹 toast）。 */
    suspend fun retryUploadImage(image: String) {
        isImageFailed = false
        isImageLoading = true
        isHasSensitive = false
        errorMessage = ""
        imageProgress = 0
        val result = FileUploadApi.uploadImageWithCheck(image) { progress -> if (!closed) imageProgress = progress }
        if (closed) return
        result.fold(
            onSuccess = { upload ->
                when (upload.status) {
                    ImageUploadStatus.success -> {
                        imageUrl = upload.url ?: ""
                        imageFile = null
                        isHasSensitive = false
                    }
                    ImageUploadStatus.check -> {
                        imageFile = image
                        isHasSensitive = true
                        errorMessage = "violation_detected".tr
                    }
                    ImageUploadStatus.failure -> {
                        val size = upload.size
                        if (size != null) toast("the_image_size_cannot_exceed".trParams("value" to "$size"))
                        isImageFailed = true
                        imageFile = image
                    }
                }
                isImageLoading = false
            },
            onFailure = {
                isImageFailed = true
                isImageLoading = false
                imageFile = image
            },
        )
    }

    /** 选中推荐预设头像。 */
    fun selectPresetImage(url: String) {
        imageUrl = url
        imageFile = null
        isImageFailed = false
        isHasSensitive = false
        errorMessage = ""
    }

    /** 清除已选参考图。 */
    fun clearCoverImage() {
        imageUrl = ""
        imageFile = null
        isImageFailed = false
        isHasSensitive = false
        errorMessage = ""
    }

    private suspend fun loadRecommendImages() {
        val result = DictDataApi.getDefaultHeadImageUrls()
        if (closed) return
        recommendItems.clear()
        result.fold(
            onSuccess = { urls -> recommendItems.addAll(urls.map { PhotoSelectItem.fromRecommend(it) }) },
            onFailure = { e -> DevLog.logger("TryFreeCreatePage.loadRecommendImages error: $e") },
        )
    }

    /** 拉取试用模板；失败或为空时回退本地案例。 */
    private suspend fun loadTemplates() {
        isTemplatesLoading = true
        val result = TrialMvApi.getTemplates()
        if (closed) return
        result.fold(
            onSuccess = { list ->
                if (list.isEmpty()) {
                    applyFallbackCases()
                } else {
                    templates.clear()
                    templates.addAll(list)
                    cases.clear()
                    cases.addAll(list.map { TryFreeCaseEntity.fromTrialTemplate(it) })
                    currentIndex = 0
                    isTemplatesLoading = false
                }
            },
            onFailure = { e ->
                DevLog.logger("TryFreeCreatePage.loadTemplates error: $e")
                applyFallbackCases()
            },
        )
    }

    private fun applyFallbackCases() {
        templates.clear()
        cases.clear()
        cases.addAll(TryFreeMockCases.all)
        currentIndex = 0
        isTemplatesLoading = false
    }

    private suspend fun submitTrialAndNavigate(coverImage: String) {
        val templateId = selectedTemplateId
        if (templateId == null) {
            toast("try_free_gen_failed_hint".tr)
            return
        }
        val result = TrialMvApi.startTrial(templateId, coverImage)
        if (closed) return
        result.fold(
            onSuccess = { task ->
                // 请求用户信息
                AppHost.launch { UserStore.refreshUserInfo() }
                TryFreeTaskService.startTask(
                    taskMvId = task.mvId,
                    taskCoverImage = coverImage,
                    initialStage = task.stage,
                    templateTag = currentTemplateTag,
                    photoSource = photoSource,
                )
                nav.replace(
                    Routes.tryFreeGenWaiting,
                    TryFreeTaskService.genWaitingArgs(task.mvId, "loading", task.stage.waitingStepIndex),
                )
            },
            onFailure = { e ->
                if (e.code == 1043000001) toast("try_free_gen_failed_hint".tr)
                DevLog.logger("TryFreeCreatePage.submitTrialAndNavigate error: $e")
            },
        )
    }

    /** 登录弹窗期间软暂停轮播视频，关闭后恢复。 */
    private suspend fun <T> withLoginGate(action: suspend () -> T): T {
        isLoginPlaybackPaused = true
        try {
            return action()
        } finally {
            if (!closed) {
                isLoginPlaybackPaused = false
                isLogin = UserStore.isLogin
            }
        }
    }

    /** offAllNamed(root) 后的 toast 要落在根页面上（本页随即销毁）。 */
    private fun goRootWithToast(msg: String) {
        nav.popToRoot()
        val host = AppNav.host
        if (host != null && host !== this) host.toast(msg) else toast(msg)
    }

    private fun logPhotoSelect(source: String) {
        photoSource = source
        UmengUtils.logEventButtonClick("trial_photo_select", pageName = trackPage, result = source)
    }

    /** 当前案例风格标签，供埋点 result / trial_generate tag。 */
    private val currentTemplateTag: String
        get() {
            if (templates.isNotEmpty() && currentIndex < templates.size) return templates[currentIndex].templateType
            if (currentIndex < cases.size) return cases[currentIndex].title
            return ""
        }

    /** 当前选中案例对应的模板 ID；mock 数据无模板时为 null。 */
    private val selectedTemplateId: Long?
        get() {
            if (templates.isNotEmpty() && currentIndex < templates.size) return templates[currentIndex].templateId
            val caseId = if (currentIndex < cases.size) cases[currentIndex].id else return null
            return caseId.toLongOrNull()
        }

    /** Android 系统手势排除（rect 相对页面根视图，dp；iOS 端模块无此实现，忽略失败）。 */
    fun updateCarouselGestureExclusion(x: Float, y: Float, w: Float, h: Float) {
        val top = y + 20f
        val height = h - 40f
        if (w <= 0f || height <= 0f) return
        try {
            val rects = JSONArray().put(JSONObject().put("x", x).put("y", top).put("w", w).put("h", height))
            acquireModule<SDAppModule>(SDAppModule.MODULE_NAME).setGestureExclusionRects(rects)
        } catch (e: Throwable) {
        }
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
                tryFreeAppBar(ctx)
                View {
                    attr {
                        flex(1f)
                        justifyContentCenter()
                    }
                    // Dart AndroidGestureExclusionContainer(verticalExclusionMargin: 20)：轮播区不触发系统侧滑返回
                    event { layoutFrameDidChange { f -> ctx.updateCarouselGestureExclusion(f.x, f.y, f.width, f.height) } }
                    tryFreeCaseCarousel(ctx)
                }
                tryFreeBottomActions(ctx)
            }
        }
    }
}

/** 顶部栏：居中标题 + 右侧关闭按钮。 */
private fun ViewContainer<*, *>.tryFreeAppBar(ctx: TryFreeCreatePage) {
    View {
        attr {
            height(56f)
            allCenter()
        }
        Text {
            attr {
                text("try_free_title".tr)
                fontSize(16f)
                fontFamily("Poppins-Bold")
                color(Color.WHITE)
            }
        }
        View {
            attr { absolutePosition(top = 12f, right = 16f) }
            TryFreeCloseButton { ctx.onCloseTap() }
        }
    }
}

/** 案例横向轮播（Dart `TryFreeCaseCarousel` / card_swiper：viewportFraction 280/屏宽、scale 0.92、不循环）。 */
private fun ViewContainer<*, *>.tryFreeCaseCarousel(ctx: TryFreeCreatePage) {
    vif({ ctx.isTemplatesLoading }) {
        TryFreeCaseSkeleton()
    }
    vif({ !ctx.isTemplatesLoading }) {
        View {
            attr {
                height(TryFreeCaseCardSize.height)
            }
            event {
                pan { ctx.onPan(it.state, it.pageX) }
            }
            vforIndex({ ctx.cases }) { item, index, _ ->
                View {
                    attr {
                        absolutePosition(top = 0f, left = (ctx.screenWidth - TryFreeCaseCardSize.width) / 2f)
                        size(TryFreeCaseCardSize.width, TryFreeCaseCardSize.height)
                        val pos = (index - ctx.currentIndex) + ctx.dragX / TryFreeCaseCardSize.width
                        val s = 1f - 0.08f * min(abs(pos), 1f)
                        transform(scale = Scale(s, s), translate = Translate(pos, 0f))
                        animate(Animation.easeOut(0.3f), ctx.settleGen)
                    }
                    event { click { ctx.onCardTap(index) } }
                    TryFreeCaseCard(
                        caseItem = item,
                        isActiveProvider = { index == ctx.currentIndex && !ctx.isPlaybackSuspended },
                        isPausedProvider = { ctx.isLoginPlaybackPaused },
                        seekOnReadyProvider = { ctx.pendingSeekMs },
                        onSeekApplied = { ctx.clearPendingSeek() },
                        onPosition = { if (index == ctx.currentIndex) ctx.onActivePosition(it) },
                    )
                }
            }
        }
    }
}

/** 底部操作区：上传照片 + 注册试用按钮。 */
private fun ViewContainer<*, *>.tryFreeBottomActions(ctx: TryFreeCreatePage) {
    View {
        attr {
            padding(20f, 20f, 30f, 20f)
            backgroundLinearGradient(
                Direction.TO_BOTTOM,
                ColorStop(Color(10, 10, 10, 0f), 0.068f),
                ColorStop(TryFreeColors.bgPrimary, 0.773f),
            )
        }
        SDCreatorPhotoUploadSection {
            attr {
                imageUrl = ctx.imageUrl
                imageFile = ctx.imageFile
                isImageLoading = ctx.isImageLoading
                isImageFailed = ctx.isImageFailed
                isHasSensitive = ctx.isHasSensitive
                errorMessage = ctx.errorMessage
                imageProgress = ctx.imageProgress
                showSectionTitle = false
                emptyLabelKey = "try_free_upload_hint"
            }
            event {
                onUploadTap { ctx.openSelectPhoto() }
                onClearImage { ctx.clearCoverImage() }
                onRetryUpload {
                    val file = ctx.imageFile
                    if (file != null) ctx.launch { ctx.retryUploadImage(file) }
                }
            }
        }
        View {
            attr {
                marginTop(20f)
                height(48f)
                borderRadius(24f)
                backgroundColor(TryFreeColors.btnPrimary)
                allCenter()
            }
            event { click { ctx.onSignUpTap() } }
            vif({ ctx.isSubmitting }) {
                TryFreeSpinner(20f, 2f, TryFreeColors.btnPrimaryText)
            }
            vif({ !ctx.isSubmitting }) {
                Text {
                    attr {
                        // 已登录展示「免费试用」，未登录引导注册
                        text(if (ctx.isLogin) "free_trial".tr else "try_free_sign_up".tr)
                        fontSize(16f)
                        fontFamily("Poppins-SemiBold")
                        color(TryFreeColors.btnPrimaryText)
                    }
                }
            }
        }
    }
}
