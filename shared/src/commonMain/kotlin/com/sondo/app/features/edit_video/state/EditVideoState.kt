package com.sondo.app.features.edit_video.state

import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.storage.Sp
import com.sondo.app.core.utils.CommonUtils
import com.sondo.app.core.utils.DevLog
import com.sondo.app.data.models.MergeMv
import com.sondo.app.data.models.MvModel
import com.sondo.app.data.models.MvShot
import com.sondo.app.data.models.MvStatus
import com.sondo.app.data.models.VideoGenParams
import com.sondo.app.features.edit_video.data.EditVideoRepository
import com.sondo.app.service.ConfigService
import com.sondo.app.service.MvService
import com.sondo.app.service.UserStore
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.ui.dialog.showConfirmDialog
import com.sondo.app.ui.input.SDKeyboardTopInputController
import com.sondo.app.ui.works.guide.GuideManager
import com.sondo.app.ui.works.guide.GuideStep
import com.sondo.app.ui.works.guide.GuideStepController
import com.sondo.app.ui.works.guide.GuideTipDirection
import com.sondo.app.ui.works.upload.ShotTagTextController
import com.sondo.app.ui.works.upload.showDialogShotTag
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.DivView
import kotlin.math.floor

/**
 * 已合成 MV 的再编辑页状态（Dart `EditVideoController`）：上半分镜精修，下半 Export Options 折叠后再合成。
 */
class EditVideoState(pager: BasePager) : StoryboardState(pager) {

    override val retryMode: Boolean = false

    /** 编辑页入口；mv_detail 为成片播放页，think_line 为思考线。 */
    private var entryType = ""

    var point by pager.observable(0)

    /** 键盘上方输入条（封面描述）。 */
    val keyboardController = SDKeyboardTopInputController()

    // --- 首次操作引导目标
    var timelineGuideRef: ViewRef<DivView>? = null
    var editAreaGuideRef: ViewRef<DivView>? = null
    var optionsGuideRef: ViewRef<DivView>? = null
    var mergeGuideRef: ViewRef<DivView>? = null

    /** 本会话已调度过引导（含已看过），避免分镜轮询重复弹 */
    private var editGuideScheduled = false
    private var editGuideKeyRetry = 0
    private var blockingPlayback = false
    override val isEditGuideBlockingPlayback: Boolean get() = blockingPlayback

    /** 展开 Export 后滚到底部（页面注入）。 */
    var scrollMainToBottom: (() -> Unit)? = null

    /** 页面注入的弹窗（widgets/EditVideoDialogs.kt）。 */
    var incompleteTipPresenter: (() -> Unit)? = null
    var qualityUpgradePresenter: ((upgradePoint: Int, onClose: () -> Unit, onUpgrade: () -> Unit) -> Unit)? = null
    var imagePreviewPresenter: ((url: String, index: Int) -> Unit)? = null

    // ------------------------------------------------------------------ 积分

    /** 价格刷新计数（Dart 的 Obx 读 configService Rx 值）：读积分的闭包随 CONFIG_CHANGED 重算 */
    var pricingTick by pager.observable(0)

    val mergePoint: Int
        get() {
            pricingTick
            if (isUpgrade) return upgradePoint
            return ConfigService.againMergePoint
        }

    val upgradePoint: Int
        get() {
            val video = mvDetail
            val duration = MvService.getMvDuration(video.subDurationScope)
            val per = if (video.mvVersion == 2) ConfigService.mvSeedanceSdVideoPoint else ConfigService.sdVideoPoint
            return floor((duration * per).toDouble()).toInt() + ConfigService.againMergePoint
        }

    val reCoverPoint: Int
        get() = if (pricingTick >= 0 && mvDetail.mvVersion == 2) ConfigService.mvSeedanceCoverRegenPoint else ConfigService.againMergePicPointV1

    /** 当前成片 480、用户选 720 时需要加价升级 */
    val isUpgrade: Boolean get() = exportQuality == "720" && qualityInt == 480

    /** 按指定分镜列表同步合成资格；空列表或任一分镜未成功都不可合成。 */
    private fun syncShotCompletion(shots: List<MvShot>? = null) {
        val source = shots ?: shotList
        shotIsCompleted = areAllShotStatusesCompleted(source.map { it.status })
    }

    override fun computePoint(duration: Int?) {
        if (shotList.isEmpty()) return
        val d = duration ?: (currentShot?.duration ?: 0)
        if (mvVersion == 2) {
            var basePoint = ConfigService.mvSeedanceGachaSdVideoPoint
            // 720p 模式 双倍积分
            if (quality == 720) basePoint = ConfigService.mvSeedanceGachaHdVideoPoint
            point = basePoint * d + ConfigService.mvSeedanceGachaImagePoint
        } else {
            var basePoint = ConfigService.gachaSdVideoPoint
            if (quality == 720) basePoint = ConfigService.gachaHdVideoPoint
            point = basePoint * d + ConfigService.gachaImagePoint
        }
    }

    override fun recreatePoint(shot: MvShot): Int = point

    override fun isPointInsufficient(userPoint: Int, needPoint: Int): Boolean = userPoint < needPoint || userPoint <= 0

    override fun onShotListFetched(result: List<MvShot>) {
        // 每次轮询成功都同步，避免服务端状态与本地列表相同时跳过合成资格更新
        syncShotCompletion(result)
    }

    override fun afterGetShotList() {
        // 分镜列表就绪后再尝试首次引导；未满足条件时轮询会再次进入
        tryShowEditGuide()
    }

    override fun onVideoReadyExtra() {
        // 引导可能早于视频初始化完成，播放器就绪后补充启动
        videoPlayer.videoPlay()
    }

    override fun onRecreateAccepted() {
        // 接口已受理重生成，立即关闭合成资格，不能等待下一次轮询
        syncShotCompletion()
    }

    // ------------------------------------------------------------------ 首次引导

    fun tryShowEditGuide() {
        if (closed || editGuideScheduled) return
        val alreadyShown = Sp.getBool(StorageKey.isEditVideoGuideShown, false)
        if (alreadyShown) {
            editGuideScheduled = true
            return
        }
        if (!shouldShowEditVideoGuide(isLoadingMv || isLoadingShot, shotList.isNotEmpty(), alreadyShown)) return
        editGuideScheduled = true
        // 引导浮层挂载前也要先暂停，避免视频在首帧回调与浮层之间抢先播放
        blockingPlayback = true
        videoPlayer.videoPause()
        pager.postDelayed(16) { if (!closed) showEditGuide() }
    }

    private fun isLaidOut(ref: ViewRef<DivView>?): Boolean {
        val v = ref?.view ?: return false
        return v.frame.width > 0f && v.frame.height > 0f
    }

    private fun showEditGuide() {
        if (closed) return
        if (!isLaidOut(timelineGuideRef) || !isLaidOut(editAreaGuideRef) ||
            !isLaidOut(optionsGuideRef) || !isLaidOut(mergeGuideRef)
        ) {
            if (editGuideKeyRetry >= 8) {
                editGuideScheduled = false
                editGuideKeyRetry = 0
                // 目标始终未挂载时放弃本轮引导，不能让播放器一直停在等待态
                releaseEditGuidePlayback()
                return
            }
            editGuideKeyRetry++
            pager.postDelayed(16) { if (!closed) showEditGuide() }
            return
        }
        val controller = GuideStepController(
            listOf(
                GuideStep("select_clip", { timelineGuideRef?.view }, "", "edit_video_guide_select_desc".tr, GuideTipDirection.bottom),
                GuideStep("edit_shot", { editAreaGuideRef?.view }, "", "edit_video_guide_edit_desc".tr, GuideTipDirection.top),
                GuideStep("export_options", { optionsGuideRef?.view }, "", "edit_video_guide_options_desc".tr, GuideTipDirection.top),
                GuideStep("merge_video", { mergeGuideRef?.view }, "", "edit_video_guide_merge_desc".tr, GuideTipDirection.top),
            ),
        )
        GuideManager.show(
            pager,
            controller,
            onFinish = { finishEditGuideAndResumePlayback() },
            onClose = { finishEditGuideAndResumePlayback() },
        )
    }

    /** 完成或跳过引导后记录已看，并恢复当前分镜播放。 */
    private fun finishEditGuideAndResumePlayback() {
        Sp.setBool(StorageKey.isEditVideoGuideShown, true)
        releaseEditGuidePlayback()
    }

    private fun releaseEditGuidePlayback() {
        blockingPlayback = false
        if (closed || !isActiveRoute) return
        videoPlayer.videoPlay()
    }

    // ------------------------------------------------------------------ 分镜操作

    /** 选择分镜标签：插入到描述末尾（Kuikly 输入框拿不到光标，同 Dart 选区无效时的规则）。 */
    fun onShotTag() {
        CommonUtils.hideKeyboard()
        val shot = currentShot ?: return
        if (shot.status == 0 || shot.status == 1) {
            pager.toast("please_wait_for_the_shot_to_be_generated".tr)
            return
        }
        if (MvService.mvTagList.isEmpty()) pager.launch { MvService.getMvTagsList() }
        pager.postDelayed(100) {
            if (closed) return@postDelayed
            pager.showDialogShotTag { tag -> insertShotTag(tag) }
        }
    }

    private fun insertShotTag(tagName: String) {
        if (closed) return
        shortDescText = ShotTagTextController.insertTag(shortDescText, tagName).first
    }

    fun onCancelRecreate(shotId: Long) {
        pager.showConfirmDialog(
            title = "cancel_task".tr,
            content = "cancel_ongoing_task_no_refund".tr,
        ) {
            UmengUtils.logEvent("cancel_fragment_generation")
            pager.launch {
                pager.showLoading()
                val result = EditVideoRepository.cancelRecreate(shotId, genMode)
                pager.hideLoading()
                if (closed) return@launch
                val status = result.getOrNull()
                if (status == null) {
                    pager.toast("cancel_failed".tr)
                    return@launch
                }
                DevLog.logger("cancelRecreateApi result: $status")
                pager.toast("cancel_success".tr)
                val index = shotList.indexOfFirst { it.id == shotId }
                if (index != -1) {
                    setShotAt(index, shotList[index].copy(status = status))
                    // 取消后的最终状态也要立即反映到合成资格
                    syncShotCompletion()
                }
                // 没有进行中的分镜则停轮询
                if (shotList.none { it.status == 0 || it.status == 1 }) {
                    cancelTimer()
                    reCreateIndexList.clear()
                    isRecreateBtn = false
                }
                if (index == shotIndex) onSelectShot(index, isFirst = true)
            }
        }
    }

    /** 分镜历史：返回所选历史还原后的 MvShot（见 ShotHistoryPage KDoc）。 */
    fun onShotHistory() {
        CommonUtils.hideKeyboard()
        val shot = currentShot ?: return
        videoPlayer.videoPause()
        if (shot.status == 0 || shot.status == 1) {
            pager.toast("please_wait_for_the_shot_to_be_generated".tr)
            return
        }
        val args = JSONObject()
            .put("id", shot.id)
            .put("videoUrl", shot.videoUrl ?: "")
            .put("genMode", genMode)
            .put("audioUri", audioUri)
            .put("subDurationScope", subDurationScope)
        pager.nav.push(Routes.shotHistory, args) { result ->
            if (result == null || closed) return@push
            val restored = MvShot.fromJsonOrNull(result.optJSONObject("shot")) ?: return@push
            setShotAt(shotIndex, restored)
            // 历史版本可能改变分镜状态，返回后同步合成资格
            syncShotCompletion()
            onSelectShot(shotIndex, isFirst = true)
        }
    }

    // ------------------------------------------------------------------ Export Options

    /** 展开 Export 时暂停分镜，并滚到底部露出完整选项。 */
    fun toggleExportOptions() {
        exportOptionsExpanded = !exportOptionsExpanded
        if (exportOptionsExpanded) {
            videoPlayer.videoPause()
            // 展开后等布局出高度再滚，补一次避免内容高度尚未更新
            pager.postDelayed(32) { if (!closed) scrollMainToBottom?.invoke() }
            pager.postDelayed(120) { if (!closed) scrollMainToBottom?.invoke() }
        }
    }

    fun toggleMvTitle(value: Boolean) {
        mvTitleEnabled = value
    }

    fun updateMvTitle(value: String) {
        mvTitleText = value
    }

    fun toggleAuthorName(value: Boolean) {
        authorNameEnabled = value
    }

    fun updateAuthorName(value: String) {
        authorNameText = value
    }

    fun updateCoverDescription(value: String) {
        coverDescriptionText = value
    }

    /** 开特效需订阅。 */
    fun toggleCapcut(value: Boolean) {
        if (value && !UserStore.isSubscribed) {
            pager.nav.push(Routes.subscribe)
            return
        }
        capcutEnabled = value
    }

    fun toggleLyricsSubtitles(value: Boolean) {
        lyricsSubtitlesEnabled = value
    }

    /** 封面描述走键盘上方浮层；完成即触发生成。 */
    fun openCoverDescriptionInput() {
        if (isMerging) {
            pager.toast("video_is_being_merged".tr)
            return
        }
        if (coverStatus == 1) {
            pager.toast("cover_is_being_generated".tr)
            return
        }
        keyboardController.show(
            text = coverDescriptionText,
            hintText = "enter_cover_description".tr,
            maxLines = 7,
            maxLength = maxCoverDescriptionLength,
            onChanged = { updateCoverDescription(it) },
            onDone = { text ->
                if (!closed) {
                    coverDescriptionText = text
                    if (text.isNotEmpty()) refreshCover() else pager.toast("enter_cover_description".tr)
                }
            },
            onCancel = { text -> if (!closed) coverDescriptionText = text },
        )
    }

    fun upgradeQuality() {
        pager.postDelayed(0) {
            qualityUpgradePresenter?.invoke(
                upgradePoint,
                { exportQuality = "480" },
                {
                    exportQuality = "720"
                    pager.postDelayed(16) { if (!closed) onSynthesis() }
                },
            )
        }
    }

    fun openIncompleteShotTip() {
        if (shotIsCompleted) return
        incompleteTipPresenter?.invoke()
    }

    fun refreshCover() {
        if (isMerging) {
            pager.toast("video_is_being_merged".tr)
            return
        }
        if (coverStatus == 1) {
            pager.toast("cover_is_being_generated".tr)
            return
        }
        if (coverDescriptionText.isEmpty()) {
            pager.toast("enter_cover_description".tr)
            return
        }
        againMergeCoverImg()
    }

    fun againMergeCoverImg() {
        pager.launch {
            pager.showLoading()
            val text = coverDescriptionText
            if (text.isNotEmpty()) {
                val msg = EditVideoRepository.checkText(text).getOrNull() ?: ""
                if (closed) {
                    pager.hideLoading()
                    return@launch
                }
                if (msg.isNotEmpty()) {
                    pager.hideLoading()
                    pager.toast(msg)
                    return@launch
                }
            }
            val res = EditVideoRepository.againMergeCover(mvId, text)
            pager.hideLoading()
            if (closed) return@launch
            if (res.getOrNull() == true) {
                coverStatus = 1
                startCoverTimer()
                pager.launch { UserStore.getUserInfo() }
            } else {
                pager.toast("regenerate_cover_failed".tr)
            }
            UmengUtils.logEventButtonClick("cover_retry")
        }
    }

    fun openDeleteDialog() {
        if (isMerging) {
            pager.toast("video_is_being_merged".tr)
            return
        }
        if (coverStatus == 1) {
            pager.toast("cover_is_being_generated".tr)
            return
        }
        pager.showConfirmDialog("are_you_sure_you_want_to_delete".tr) { deleteMv() }
    }

    fun deleteMv() {
        pager.launch {
            pager.showLoading()
            val res = EditVideoRepository.deleteMv(mvId)
            pager.hideLoading()
            if (closed) return@launch
            if (res.getOrNull() != null) {
                MvService.refreshMv("delete", MvModel(id = mvId, status = MvStatus.failed))
                pager.launch { UserStore.getUserInfo() }
                pager.toast("delete_success".tr)
                pager.nav.pop(JSONObject().put("result", "deleted"))
            } else {
                pager.toast("delete_failed".tr)
            }
        }
    }

    /** 分镜未完成 / 封面生成中 / 合成中不可 Merge。 */
    fun onSynthesis() {
        if (mergeLoading || isMerging) {
            pager.toast("video_is_being_merged".tr)
            return
        }
        if (coverStatus == 1) {
            pager.toast("cover_is_being_generated".tr)
            return
        }
        // 点击 Merge 时按当前列表重新校验，生成中或失败分镜只提示、不提交
        syncShotCompletion()
        if (!shotIsCompleted) {
            openIncompleteShotTip()
            return
        }
        val userPoint = UserStore.userInfo?.point ?: 0
        val needPoint = mergePoint
        if (needPoint > 0 && userPoint < needPoint) {
            goBuyPoints()
            return
        }
        val titleText = mvTitleText
        if (titleText.isEmpty()) {
            pager.toast("enter_mv_title".tr)
            return
        }
        pager.launch {
            pager.showLoading()
            mergeLoading = true
            val titleMsg = EditVideoRepository.checkText(titleText).getOrNull() ?: ""
            if (closed) {
                pager.hideLoading()
                return@launch
            }
            if (titleMsg.isNotEmpty()) {
                pager.hideLoading()
                mergeLoading = false
                pager.toast(titleMsg)
                return@launch
            }
            val authorText = authorNameText
            if (authorText.isNotEmpty()) {
                val msg = EditVideoRepository.checkText(authorText).getOrNull() ?: ""
                if (closed) {
                    pager.hideLoading()
                    return@launch
                }
                if (msg.isNotEmpty()) {
                    pager.hideLoading()
                    mergeLoading = false
                    pager.toast(msg)
                    return@launch
                }
            }
            val detail = mvDetail
            val data = MergeMv(
                mvId = mvId,
                mergeBs = 0,
                quality = exportQuality,
                genMode = detail.genMode,
                title = mvTitleText,
                name = authorNameText,
                isTitle = if (mvTitleEnabled) 0 else 1,
                isName = if (authorNameEnabled) 0 else 1,
                isLyric = if (detail.isLyric == null) null else if (lyricsSubtitlesEnabled) 0 else 1,
                // 下载水印改由设置页控制。存量 1 表示去除，合成固定不去除
                isWatermark = VideoGenParams.keptWatermark,
                firstFrameImg = detail.firstFrameImg,
                isupdt = 1,
                userNeedPoint = needPoint,
                capcutEnabled = capcutEnabled,
            )
            val res = EditVideoRepository.againMergeMv(data)
            if (closed) {
                pager.hideLoading()
                return@launch
            }
            if (res.getOrNull() != null) {
                qualityInt = if (exportQuality == "720") 720 else 480
                mergeLoading = false
                isMerging = true
                pager.toast("please_wait_for_the_video_to_be_merged".tr)
                UmengUtils.logEvent("video_merge")
                pager.hideLoading()
                pager.launch { UserStore.getUserInfo() }
                MvService.refreshMvStatus(mvId, MvStatus.generating, title = mvTitleText, isupdt = 1)
                pager.postDelayed(100) { if (!closed) leaveAfterSynthesis() }
            } else {
                mergeLoading = false
                pager.toast("merge_failed".tr)
                pager.hideLoading()
            }
        }
    }

    private fun leaveAfterSynthesis() {
        if (!shouldReturnToRootLibrary(entryType)) {
            pager.nav.pop()
            return
        }
        // 播放详情或思考线压在根页之上；受理后切到作品库 Tab（Dart changeBarIndex(4)）再一次性退回根页
        pager.nav.popToRoot(LIBRARY_TAB_INDEX)
    }

    fun showImagePreviewDialog(imageUrl: String, itemIndex: Int) {
        imagePreviewPresenter?.invoke(imageUrl, itemIndex)
    }

    // ------------------------------------------------------------------ 生命周期

    override fun onInit(args: JSONObject) {
        super.onInit(args)
        entryType = args.optString("type", "")
    }

    override fun initData() {
        super.initData()
        // Dart ever(configService.updatePointTime)：价格刷新后重算单镜积分
        pager.on(Events.CONFIG_CHANGED) {
            if (closed) return@on
            pricingTick++
            if (ConfigService.updatePointTime > 0) computePoint()
        }
    }

    override fun onClose() {
        // 引导浮层不随页面自动拆，必须手动 hide
        if (GuideManager.isShowing) GuideManager.hide()
        keyboardController.hide()
        super.onClose()
    }

    companion object {
        /** 作品库 Tab（Dart MainController.changeBarIndex(4)）。 */
        const val LIBRARY_TAB_INDEX = 4
    }
}
