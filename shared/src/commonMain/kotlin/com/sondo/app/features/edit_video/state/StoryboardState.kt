package com.sondo.app.features.edit_video.state

import com.sondo.app.base.BasePager
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.i18n.trParams
import com.sondo.app.core.storage.Sp
import com.sondo.app.core.utils.AigcMediaExport
import com.sondo.app.core.utils.CancelToken
import com.sondo.app.core.utils.CommonUtils
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.EmailHelper
import com.sondo.app.data.models.ImageUploadStatus
import com.sondo.app.data.models.MvModel
import com.sondo.app.data.models.MvShot
import com.sondo.app.data.models.MvStatus
import com.sondo.app.data.models.RoleModel
import com.sondo.app.data.models.ShotImageModel
import com.sondo.app.features.edit_video.data.EditVideoRepository
import com.sondo.app.nativeapi.SDAppModule
import com.sondo.app.service.MvService
import com.sondo.app.service.PlayerService
import com.sondo.app.service.UserStore
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.ui.dialog.showSheetImageOption
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.ListView
import kotlin.random.Random

/** 与 [MvShot.status] 一致：失败。 */
const val retryShotFailedStatus = 3

/** 第一个失败镜头下标；没有失败则 null。 */
fun firstFailedShotIndex(shots: List<MvShot>): Int? {
    for (i in shots.indices) if (shots[i].status == retryShotFailedStatus) return i
    return null
}

/** 从 [current] 之后环形找下一个失败镜头；没有其他失败则 null。 */
fun nextFailedShotIndex(shots: List<MvShot>, current: Int): Int? {
    if (shots.isEmpty()) return null
    val len = shots.size
    for (step in 1 until len) {
        val i = (current + step) % len
        if (shots[i].status == retryShotFailedStatus) return i
    }
    return null
}

/** 是否存在当前以外的失败镜头（用于展示 Next）。 */
fun hasOtherFailedShots(shots: List<MvShot>, current: Int): Boolean =
    shots.indices.any { it != current && shots[it].status == retryShotFailedStatus }

/** 分镜已加载且本地未看过时才弹引导。 */
fun shouldShowEditVideoGuide(isLoading: Boolean, hasShots: Boolean, alreadyShown: Boolean): Boolean =
    !isLoading && hasShots && !alreadyShown

/** 仅在分镜列表非空且全部生成成功时允许合成。 */
fun areAllShotStatusesCompleted(statuses: Iterable<Int>): Boolean =
    statuses.any() && statuses.all { it == 2 }

/** 成片播放页或思考线进入时，合成接口受理后直接回到根作品库。 */
fun shouldReturnToRootLibrary(entryType: String): Boolean = entryType == "mv_detail" || entryType == "think_line"

/**
 * 编辑页（[EditVideoState]）与失败分镜重试页（[RetryShotsState]）共用的分镜精修状态
 * （Dart `EditVideoController` / `RetryShotsController` 中逐字相同的部分 + `StoryLineState`）。
 * 方法名与 Dart 保持一致；两页差异经 open 钩子实现。
 */
abstract class StoryboardState(val pager: BasePager) {

    /** 当前页面路由（Dart `_isActiveRoute`：Get.currentRoute == 本页）。 */
    val isActiveRoute: Boolean get() = pager.isPageVisible && !closed

    /** 页面已销毁（Dart isClosed）。 */
    var closed = false
        private set

    var mvId = 0L
    var mvIndex = 0
    var reTryIndex = 0
    private var isDownloadingVideo = false

    val videoPlayer = StoryboardVideoPlayer(pager) { isActiveRoute }

    private var currentCancelToken: CancelToken? = null
    var audioUri = ""
    var videoRatio by pager.observable("16:9")
    /** 高级版 mv类型 0 常规类型，1 对口型类型 */
    var modelType by pager.observable(0)
    /** mv版本 0 家豪版本，1 译峰版本 2 seedance mv */
    var mvVersion by pager.observable(0)
    var coverImage by pager.observable("")
    var fourViewImage by pager.observable("")
    var subDurationScope = ""

    /** 分镜描述输入框（Dart shortDescController.text） */
    var shortDescText by pager.observable("")
    /** 对话框输入框（Dart dialogDescController，页面上已无入口，仅随选镜清空） */
    var dialogDescText = ""

    /** 记录重新生成分镜的索引 */
    val reCreateIndexList = ArrayList<Int>()

    /** shotId → 参考图编辑态。整表替换以触发刷新（Dart RxMap.refresh）。 */
    var shotImageMap by pager.observable<Map<String, List<ShotImageModel>>>(emptyMap())

    // putFile 全量读文件，限制并发防 OOM（对齐 OssUploadService）
    private var activeUploadCount = 0
    private val uploadWaitQueue = ArrayList<suspend () -> Unit>()

    // ---------------- StoryLineState
    val shotList by pager.observableList<MvShot>()
    var shotIndex by pager.observable(0)
    /** shotList[shotIndex] 的响应式镜像（列表 / 下标变化后同步）。 */
    var currentShot by pager.observable<MvShot?>(null)
    var isAllVideoLoaded by pager.observable(false)
    var isAllImageLoaded by pager.observable(false)
    var isBtnDisabled by pager.observable(false)
    var isRecreateBtn by pager.observable(false)
    var videoStatus by pager.observable(0)
    var isDelete by pager.observable(false)
    /** 0：文生视频  1：文生图->图生视频 */
    var mvType by pager.observable(0)
    /** 0：图片生成阶段  1：代表视频生成阶段 */
    var taskPhase by pager.observable(0)
    var musicStartMs = 0L
    var musicEndMs = 0L

    var genMode by pager.observable(0)
    var chargingModel by pager.observable(-1)
    /** 视频质量 480 720 */
    var quality by pager.observable(480)

    /** 是否在加载视频详情 */
    var isLoadingMv by pager.observable(true)
    /** 是否在加载分镜 */
    var isLoadingShot by pager.observable(true)

    // ---------------- Export Options（两页都由 getMvDetail 写入）
    var exportOptionsExpanded by pager.observable(false)
    var mvDetail by pager.observable(MvModel())
    var coverDescriptionText by pager.observable("")
    val maxCoverDescriptionLength = 2000
    var mvTitleEnabled by pager.observable(false)
    var mvTitleText by pager.observable("")
    val maxTitleLength = 30
    var authorNameEnabled by pager.observable(false)
    var authorNameText by pager.observable("")
    val maxAuthorNameLength = 30
    var capcutEnabled by pager.observable(false)
    var lyricsSubtitlesEnabled by pager.observable(true)
    /** 整支视频正在后台合成（queue/generating） */
    var isMerging by pager.observable(false)
    var mergeLoading by pager.observable(false)
    /** 封面状态：0 未生成 / 1 生成中 / 2 成功 / 3 失败 */
    var coverStatus by pager.observable(0)
    /** 合成目标画质；qualityInt 为当前成片画质 */
    var exportQuality by pager.observable("480")
    var qualityInt by pager.observable(480)
    /** 分镜是否非空且全部生成成功 */
    var shotIsCompleted by pager.observable(true)

    private var timer15Seq = 0
    private var timerCoverSeq = 0

    /** 时间轴横向列表（滚动定位）。 */
    var shotListRef: ViewRef<ListView<*, *>>? = null

    // ------------------------------------------------------------------ 子类钩子

    /** 重试页：拉分镜带 calculateRetryPoints，比较时也看 errorMsg / retryNeedPoint。 */
    protected abstract val retryMode: Boolean

    /** 首次引导期间禁止播放器自行起播。 */
    protected open val isEditGuideBlockingPlayback: Boolean get() = false

    /** 每次分镜接口成功后（比较前）。 */
    protected open fun onShotListFetched(result: List<MvShot>) {}

    /** 列表有变化并写入后（自动播放之前）。 */
    protected open fun onShotListChanged(result: List<MvShot>) {}

    /** getShotList 末尾（无论是否变化）。 */
    protected open fun afterGetShotList() {}

    /** 进页默认选中的分镜下标。 */
    protected open fun initialShotIndex(): Int = mvIndex

    /** 进页后是否滚动时间轴到选中项。 */
    protected open fun shouldScrollOnFirstSelect(index: Int): Boolean = index > 2

    /** 选镜时计算单镜积分（编辑页）。 */
    protected open fun computePoint(duration: Int? = null) {}

    /** 选中分镜后，视频就绪（或初始化中）回调里的附加处理。 */
    protected open fun onVideoReadyExtra() {}

    /** 页面首次出现：播放器若在此之前就绪且只因页面不可见而暂停，补起播（引导中不播）。 */
    fun onFirstPageAppear() {
        if (closed || isEditGuideBlockingPlayback) return
        videoPlayer.resumeIfRouteGated()
    }

    // ------------------------------------------------------------------ 列表辅助

    protected fun syncCurrentShot() {
        val i = shotIndex
        currentShot = if (i in shotList.indices) shotList[i] else null
    }

    protected fun setShotAt(index: Int, shot: MvShot) {
        if (index !in shotList.indices) return
        shotList[index] = shot
        syncCurrentShot()
    }

    private fun setShots(list: List<MvShot>) {
        shotList.clear()
        shotList.addAll(list)
        syncCurrentShot()
    }

    // ------------------------------------------------------------------ 参考图

    fun getShotImages(shotId: Long): List<ShotImageModel> = shotImageMap["$shotId"] ?: emptyList()

    fun buildCoverImgParam(shotId: Long): List<String> =
        getShotImages(shotId).filter { it.imageUrl.startsWith("https") }.map { it.imageUrl }

    fun isAnyImageLoading(shotId: Long): Boolean = getShotImages(shotId).any { it.imageLoading }

    fun getFirstSensitiveImage(shotId: Long): ShotImageModel? = getShotImages(shotId).firstOrNull { it.isHasSensitive }

    private fun isPendingUploadPlaceholder(img: ShotImageModel): Boolean =
        img.imageLoading && img.imageUrl.isEmpty() && !img.isImageFailed

    private fun hasPendingUploadAtEnd(shotId: Long): Boolean {
        val images = getShotImages(shotId)
        return images.isNotEmpty() && isPendingUploadPlaceholder(images.last())
    }

    fun shouldShowAddSlot(shotId: Long): Boolean {
        val images = getShotImages(shotId)
        if (images.size >= maxReferenceImages) return false
        return !hasPendingUploadAtEnd(shotId)
    }

    private fun resolveImageIndex(shotId: Long, imageIndex: Int?): Int = imageIndex ?: getShotImages(shotId).size

    private fun canAddImage(shotId: Long, imageIndex: Int): Boolean {
        val images = getShotImages(shotId)
        if (imageIndex < images.size) return true
        if (images.size >= maxReferenceImages) {
            pager.toast("max_reference_images".trParams("value" to "$maxReferenceImages"))
            return false
        }
        if (hasPendingUploadAtEnd(shotId)) {
            pager.toast("almost_done_please_wait".tr)
            return false
        }
        return true
    }

    private fun updateShotImageAtImpl(shotId: String, index: Int, model: ShotImageModel) {
        val list = ArrayList(shotImageMap[shotId] ?: emptyList())
        if (index >= list.size) list.add(model) else list[index] = model
        shotImageMap = shotImageMap + (shotId to list)
    }

    private fun patchShotImageAt(shotId: String, index: Int, patch: (ShotImageModel) -> ShotImageModel) {
        val images = shotImageMap[shotId] ?: emptyList()
        val current = if (index < images.size) images[index] else ShotImageModel()
        updateShotImageAtImpl(shotId, index, patch(current))
    }

    private fun removeShotImageAtImpl(shotId: String, index: Int) {
        val list = ArrayList(shotImageMap[shotId] ?: emptyList())
        if (index < 0 || index >= list.size) return
        list.removeAt(index)
        shotImageMap = if (list.isEmpty()) shotImageMap - shotId else shotImageMap + (shotId to list)
    }

    /** 选图前同步占位，避免快速连点时 index 竞态。 */
    private fun reserveShotImageSlot(shotId: String, index: Int) {
        val images = shotImageMap[shotId] ?: emptyList()
        if (index < images.size) {
            updateShotImageAtImpl(
                shotId, index,
                images[index].copy(imageLoading = true, isImageFailed = false, isHasSensitive = false, errorMsg = ""),
            )
        } else {
            updateShotImageAtImpl(shotId, index, ShotImageModel(imageLoading = true))
        }
    }

    private fun revertReservedSlotOnPickCancel(shotId: String, index: Int) {
        val images = shotImageMap[shotId] ?: emptyList()
        if (index >= images.size) return
        val existing = images[index]
        if (existing.imageUrl.isEmpty() && !existing.isImageFailed) {
            removeShotImageAtImpl(shotId, index)
        } else {
            updateShotImageAtImpl(shotId, index, existing.copy(imageLoading = false, imageFile = null))
        }
    }

    private fun revertUploadSlotOnError(shotId: String, image: String) {
        val index = findActiveUploadIndex(shotId, image) ?: return
        val existing = (shotImageMap[shotId] ?: emptyList())[index]
        if (existing.imageUrl.isEmpty() && !existing.isImageFailed) {
            removeShotImageAtImpl(shotId, index)
        } else {
            updateShotImageAtImpl(shotId, index, existing.copy(imageLoading = false, isImageFailed = true))
        }
    }

    private fun enqueueShotImageUpload(task: suspend () -> Unit) {
        uploadWaitQueue.add(task)
        scheduleNextUpload()
    }

    private fun scheduleNextUpload() {
        while (activeUploadCount < maxConcurrentUploads && uploadWaitQueue.isNotEmpty()) {
            val task = uploadWaitQueue.removeAt(0)
            activeUploadCount++
            pager.launch {
                try {
                    task()
                } catch (e: Throwable) {
                    DevLog.logger("upload task error: ${e.message}")
                } finally {
                    activeUploadCount--
                    scheduleNextUpload()
                }
            }
        }
    }

    /** 上传过程中列表可能因删除其他图片而移位，按本地文件路径定位当前槽位。 */
    private fun findActiveUploadIndex(shotId: String, image: String): Int? {
        val images = shotImageMap[shotId] ?: return null
        for (i in images.indices) {
            val img = images[i]
            if (img.imageLoading && img.imageFile == image) return i
        }
        return null
    }

    private suspend fun performShotImageUpload(shotIdStr: String, shotId: Long, image: String) {
        try {
            val upload = EditVideoRepository.uploadImageWithCheck(image).getOrNull()
            if (closed) return
            if (upload == null) {
                revertUploadSlotOnError(shotIdStr, image)
                return
            }
            val currentIndex = findActiveUploadIndex(shotIdStr, image)
            if (currentIndex == null) {
                DevLog.logger("_performShotImageUpload: slot removed during upload, discarding result")
                return
            }
            when (upload.status) {
                ImageUploadStatus.success -> {
                    val url = upload.url ?: ""
                    updateShotImageAtImpl(shotIdStr, currentIndex, ShotImageModel(imageUrl = url))
                    promoteUsageRecord(url)
                    syncShotCoverImg(shotId)
                }
                ImageUploadStatus.check -> updateShotImageAtImpl(
                    shotIdStr, currentIndex,
                    ShotImageModel(isHasSensitive = true, errorMsg = "violation_detected".tr, imageFile = image),
                )
                ImageUploadStatus.failure -> {
                    upload.size?.let { pager.toast("the_image_size_cannot_exceed".trParams("value" to "$it")) }
                    patchShotImageAt(shotIdStr, currentIndex) {
                        it.copy(isImageFailed = true, imageLoading = false, imageFile = image)
                    }
                }
            }
        } catch (e: Throwable) {
            DevLog.logger("_performShotImageUpload error: ${e.message}")
            revertUploadSlotOnError(shotIdStr, image)
        }
    }

    private fun syncShotCoverImg(shotId: Long) {
        val coverImgs = buildCoverImgParam(shotId)
        val index = shotList.indexOfFirst { it.id == shotId }
        if (index != -1) setShotAt(index, shotList[index].copy(referenceImgs = coverImgs.joinToString(",")))
    }

    private fun usageRecords(): MutableList<String> =
        Sp.getString(StorageKey.uploadedImage, "").split(",").filter { it.startsWith("https://") }.toMutableList()

    private fun promoteUsageRecord(url: String) {
        try {
            val imgs = usageRecords()
            imgs.remove(url)
            imgs.add(0, url)
            Sp.setString(StorageKey.uploadedImage, imgs.joinToString(","))
        } catch (e: Throwable) {
            DevLog.logger("_promoteUsageRecord error: ${e.message}")
        }
    }

    private fun sanitizeShotImages(images: List<ShotImageModel>): List<ShotImageModel> =
        images.filter { !(it.imageLoading && it.imageUrl.isEmpty() && !it.isImageFailed && it.imageFile == null) }

    private fun initialShotImages(shot: MvShot): List<ShotImageModel> {
        val images = when {
            shot.referenceImgsList.isNotEmpty() -> shot.referenceImgsList
            shot.coverImg.isEmpty() -> return emptyList()
            else -> shot.coverImg.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { ShotImageModel(imageUrl = it) }
        }
        return sanitizeShotImages(images)
    }

    fun openImageOption(coverImg: String, shotId: Long, imageIndex: Int? = null) {
        val shot = currentShot ?: return
        if (shot.status != 2 && shot.status != 3) {
            pager.toast("please_wait_for_the_shot_to_be_generated".tr)
            return
        }
        val resolvedIndex = resolveImageIndex(shotId, imageIndex)
        if (!canAddImage(shotId, resolvedIndex)) return
        val imgs = try {
            usageRecords()
        } catch (e: Throwable) {
            mutableListOf()
        }
        pager.showSheetImageOption(
            onRoleLibrary = { toRoleLibray(coverImg, shotId, resolvedIndex) },
            onCamera = { pager.launch { uploadShotImage(shotId, resolvedIndex, source = CommonUtils.SOURCE_CAMERA) } },
            onAlbum = { pager.launch { uploadShotImage(shotId, resolvedIndex, source = CommonUtils.SOURCE_GALLERY) } },
            usageRecords = imgs,
            onUsageRecordTap = { url ->
                updateShotImageAtImpl("$shotId", resolvedIndex, ShotImageModel(imageUrl = url))
                syncShotCoverImg(shotId)
                promoteUsageRecord(url)
            },
        )
    }

    /** 角色库选图（Routes.fourView 返回 `{role: RoleModel json}`）。 */
    fun toRoleLibray(coverImg: String, shotId: Long, imageIndex: Int? = null) {
        videoPlayer.videoPause()
        pager.nav.push(Routes.fourView, JSONObject().put("imageUrl", coverImg)) { result ->
            val roleJson = result?.optJSONObject("role") ?: return@push
            if (closed) return@push
            val role = RoleModel.fromJson(roleJson)
            val resolvedIndex = resolveImageIndex(shotId, imageIndex)
            if (!canAddImage(shotId, resolvedIndex)) return@push
            updateShotImageAtImpl("$shotId", resolvedIndex, ShotImageModel(imageUrl = role.roleImg))
            syncShotCoverImg(shotId)
            if (role.roleImg.startsWith("https://")) promoteUsageRecord(role.roleImg)
        }
    }

    /**
     * 上传参考图。[source] 给定时先走使用声明 + 选图；否则重试 [imageFile]。
     */
    suspend fun uploadShotImage(shotId: Long, imageIndex: Int, source: String? = null, imageFile: String? = null) {
        val shotIdStr = "$shotId"
        val resolvedIndex = resolveImageIndex(shotId, imageIndex)
        var image = imageFile
        if (source != null) {
            val isAgree = UserStore.showAgree(StorageKey.isAgreeUpload, "upload", pager)
            if (!isAgree || closed) return
            if (!canAddImage(shotId, resolvedIndex)) return
            DevLog.logger("uploadShotImage: $shotId index=$resolvedIndex")
            UmengUtils.logEventButtonClick("ref_img_replace")
            reserveShotImageSlot(shotIdStr, resolvedIndex)
            // 模型参考图宽高比须在 0.4–2.5，超限直接拦截不上传
            image = CommonUtils.pickImage(source, pager, isCheckImageAspectRatio = true, isCheckImageHW = true)
            if (closed) return
            if (image == null) {
                revertReservedSlotOnPickCancel(shotIdStr, resolvedIndex)
                return
            }
            // 选图期间列表被刷新或删掉占位后，下标已失效
            val images = getShotImages(shotId)
            if (resolvedIndex < 0 || resolvedIndex >= images.size) {
                revertReservedSlotOnPickCancel(shotIdStr, resolvedIndex)
                return
            }
            updateShotImageAtImpl(
                shotIdStr, resolvedIndex,
                images[resolvedIndex].copy(
                    imageLoading = true, isImageFailed = false, isHasSensitive = false, errorMsg = "", imageFile = image,
                ),
            )
        } else {
            if (image == null) return
            patchShotImageAt(shotIdStr, resolvedIndex) {
                it.copy(imageLoading = true, isImageFailed = false, isHasSensitive = false, errorMsg = "", imageFile = image)
            }
        }
        val fileToUpload = image
        enqueueShotImageUpload { performShotImageUpload(shotIdStr, shotId, fileToUpload) }
    }

    fun onClearImage(imageIndex: Int) {
        val shot = currentShot ?: return
        if (shot.status != 2 && shot.status != 3) {
            pager.toast("please_wait_for_the_shot_to_be_generated".tr)
            return
        }
        removeShotImageAtImpl("${shot.id}", imageIndex)
        syncShotCoverImg(shot.id)
    }

    // ------------------------------------------------------------------ 详情 / 分镜

    fun getMvDetail(isSelectFirst: Boolean = false) {
        pager.launch {
            val result = EditVideoRepository.fetchMvDetail(mvId)
            if (closed) return@launch
            val data = result.getOrNull()
            if (data == null) {
                DevLog.logger("getMvDetail error: ${result.errorOrNull()}")
                reTryIndex++
                if (reTryIndex < 3) {
                    pager.postDelayed(1000) { if (!closed) getMvDetail(isSelectFirst) }
                }
                return@launch
            }
            isLoadingMv = false
            mvDetail = data
            videoRatio = data.imageComp
            audioUri = data.audioUrl
            mvType = data.mvType ?: 0
            taskPhase = data.taskPhase ?: 0
            chargingModel = data.chargingModel ?: 0
            genMode = data.genMode
            modelType = data.modelType
            mvVersion = data.mvVersion
            coverImage = data.coverImage ?: ""
            fourViewImage = data.fourViewImg
            quality = data.quality
            qualityInt = data.quality
            exportQuality = "${data.quality}"
            subDurationScope = data.subDurationScope
            mvTitleEnabled = data.isTitle == 0
            mvTitleText = data.title
            authorNameEnabled = data.isName == 0
            authorNameText = data.artist
            lyricsSubtitlesEnabled = data.isLyric == 0
            capcutEnabled = data.capcutEnabled
            coverDescriptionText = data.coverPrompt
            isMerging = data.status == MvStatus.generating || data.status == MvStatus.queue
            parseMusicStartDuration(data.subDurationScope)
            getShotList(isSelectFirst)
            getAgainMergeCoverImg()
            reTryIndex = 0
        }
    }

    /** 解析音乐开始 / 结束时间 `mm:ss,mm:ss`。 */
    private fun parseMusicStartDuration(scope: String) {
        DevLog.logger("subDurationScope: $scope")
        if (scope.isNotEmpty() && scope.contains(",")) {
            val parts = scope.split(",")
            musicStartMs = parseClockMs(parts[0])
            musicEndMs = parseClockMs(parts[1])
        }
    }

    /** 15 秒轮询分镜（仅本页可见时请求）。 */
    fun startTimer() {
        val seq = ++timer15Seq
        fun schedule() {
            pager.postDelayed(15_000) {
                if (closed || seq != timer15Seq) return@postDelayed
                if (isActiveRoute) getShotList()
                schedule()
            }
        }
        schedule()
    }

    protected fun cancelTimer() {
        timer15Seq++
    }

    fun getShotList(isSelectFirst: Boolean = false) {
        pager.launch {
            val resultWrap = EditVideoRepository.fetchShotList(mvId, calculateRetryPoints = retryMode)
            if (closed) return@launch
            val result = resultWrap.getOrNull() ?: emptyList()
            if (result.isEmpty()) {
                DevLog.logger("getShotList error: ${resultWrap.errorOrNull()}")
                return@launch
            }
            onShotListFetched(result)
            if (!listsEqual(shotList, result)) {
                setShots(result)
                if (isSelectFirst) {
                    val index = initialShotIndex().coerceIn(0, shotList.size - 1)
                    onSelectShot(index, isFirst = true)
                    // 生成中的分镜记下来：完成后自动播放
                    reCreateIndexList.clear()
                    for (i in shotList.indices) {
                        if (shotList[i].status == 0 || shotList[i].status == 1) reCreateIndexList.add(i)
                    }
                    if (shouldScrollOnFirstSelect(index)) scrollShotIntoView(index)
                }
                onShotListChanged(result)
                val isStopTimer = shotList.none { it.status != 2 && it.status != 3 }
                autoPlayShot()
                if (isStopTimer) {
                    DevLog.logger("is stop timer")
                    pager.launch { UserStore.getUserInfo() }
                    cancelTimer()
                    reCreateIndexList.clear()
                }
                isLoadingShot = false
            }
            afterGetShotList()
        }
    }

    private fun listsEqual(list1: List<MvShot>, list2: List<MvShot>): Boolean {
        if (list1.size != list2.size) return false
        for (i in list1.indices) {
            val a = list1[i]
            val b = list2[i]
            if (a.id != b.id || a.status != b.status || a.klingImg != b.klingImg || a.videoUrl != b.videoUrl) return false
            if (retryMode && (a.errorMsg != b.errorMsg || a.retryNeedPoint != b.retryNeedPoint)) return false
        }
        return true
    }

    /** 时间轴滚到 [index]，让选中镜头尽量居中（每格约 87）。 */
    fun scrollShotIntoView(index: Int) {
        if (index < 0 || index >= shotList.size) return
        pager.postDelayed(32) {
            if (closed) return@postDelayed
            val list = shotListRef?.view ?: return@postDelayed
            try {
                val viewWidth = list.frame.width
                val contentWidth = list.contentView?.frame?.width ?: 0f
                val maxScrollExtent = (contentWidth - viewWidth).coerceAtLeast(0f)
                val raw = index * 87f - pager.screenWidth / 2
                val target = (if (index == shotList.size - 1) maxScrollExtent else raw).coerceIn(0f, maxScrollExtent)
                list.setContentOffset(target, 0f, true)
            } catch (e: Throwable) {
                DevLog.logger("scroll shot into view error: ${e.message}")
            }
        }
    }

    fun autoPlayShot() {
        val index = shotIndex
        // 轮询刷新后列表可能变短，旧下标会越界
        if (index < 0 || index >= shotList.size) return
        val isPlaying = !videoPlayer.isPaused && !videoPlayer.isVideoError && videoPlayer.readySrc.isNotEmpty()
        if (isPlaying || videoPlayer.isVideoLoading) return
        if (shotList[index].status != 2) return
        if (!reCreateIndexList.contains(index)) return
        onSelectShot(index, isFirst = true)
        reCreateIndexList.remove(index)
    }

    /** 开启 / 关闭列表循环播放。 */
    fun onRepeatVideo() {
        videoPlayer.isListRepeat = !videoPlayer.isListRepeat
        pager.toast(if (videoPlayer.isListRepeat) "list_repeat".tr else "single_video_repeat".tr)
    }

    /** 选择分镜。 */
    fun onSelectShot(index: Int, isFirst: Boolean = false) {
        if (index !in shotList.indices) return
        val shot = shotList[index]
        // 防止重复选择同一个分镜
        if (index == shotIndex && !isFirst) return
        val referenceImgs = initialShotImages(shot)
        if (referenceImgs.isNotEmpty() && !shotImageMap.containsKey("${shot.id}")) {
            shotImageMap = shotImageMap + ("${shot.id}" to referenceImgs)
        }
        // 防止在视频加载过程中切换分镜
        if (videoPlayer.isVideoLoading && !isFirst) {
            DevLog.logger("视频正在加载中，跳过分镜切换")
            return
        }
        dialogDescText = ""
        computePoint(shot.duration)
        // 取消上一次的操作
        currentCancelToken?.cancel()
        if (isFirst) videoPlayer.reset()
        if (closed) return
        val token = CancelToken()
        currentCancelToken = token
        updateShotSelection(shot, index)
        pager.launch {
            videoPlayer.selectShot(
                shot,
                audioUri = audioUri,
                musicStartMs = musicStartMs,
                cancelToken = token,
                // 首次引导结束前只初始化画面，禁止播放器自行起播
                autoPlay = !isEditGuideBlockingPlayback,
                // 自定义 MV 模式（genMode 2）的分镜从截取起点开始，不再叠加音乐起点
                isMergeStartDuration = genMode != 2,
                onProgressUpdate = { progress ->
                    if (videoPlayer.videoProgress != progress) videoPlayer.videoProgress = progress
                    if (!isActiveRoute) videoPlayer.videoPause()
                },
                onVideoReady = {
                    if (!closed) {
                        if (!isActiveRoute || isEditGuideBlockingPlayback) {
                            videoPlayer.videoPause()
                        } else {
                            onVideoReadyExtra()
                        }
                    }
                },
                onListRepeat = {
                    if (!isActiveRoute) videoPlayer.videoPause() else autoSelectNextShot()
                },
            )
        }
    }

    /** 更新分镜选择状态（StoryLineState.updateShotSelection + 描述回填）。 */
    private fun updateShotSelection(shot: MvShot, index: Int) {
        shotIndex = index
        isRecreateBtn = shot.status == 2 || shot.status == 3
        syncCurrentShot()
        shortDescText = if (genMode == 2) shot.videoPrompt else shot.description
        if (shot.status != 2) {
            videoPlayer.positions = 0L
            videoPlayer.durations = 0L
            videoPlayer.isPaused = false
            videoPlayer.videoProgress = ""
        }
    }

    fun autoSelectNextShot() {
        if (shotList.isEmpty()) return
        val startIndex = shotIndex + 1
        for (i in shotList.indices) {
            val index = (startIndex + i) % shotList.size
            if (shotList[index].status == 2) {
                onSelectShot(index, isFirst = false)
                return
            }
        }
        // 没有可播放的分镜：循环到下一个并展示未就绪
        onSelectShot(startIndex % shotList.size, isFirst = false)
    }

    fun playOrPauseVideo() {
        videoPlayer.playOrPause()
    }

    fun onVideoSeekEnd(ms: Float) {
        videoPlayer.seekTo(ms.toLong())
    }

    /** 全屏播放回调：由页面打开全屏播放器（见 widgets/EditVideoDialogs.kt）。 */
    var fullScreenPresenter: ((url: String, positionMs: Long, onDownload: () -> Unit, onClosed: () -> Unit) -> Unit)? = null

    fun onEnterFullScreen() {
        videoPlayer.videoPause()
        val url = videoPlayer.currentUrl
        val currentPosition = videoPlayer.positions
        if (url.isEmpty()) return
        val presenter = fullScreenPresenter ?: return
        presenter(url, currentPosition, { downloadVideo() }) { if (!closed) videoPlayer.videoPlay() }
    }

    /** 分镜失败后的反馈邮件。 */
    fun onFeedback() {
        val shot = currentShot ?: return
        val str = "${"video_id".tr}: $mvId\n${"shot_id".tr}: ${shot.id}\n"
        pager.launch { EmailHelper.sendEmail(content = str, userId = UserStore.userInfo?.id) }
    }

    private fun downloadVideo() {
        if (isDownloadingVideo) return
        if (!UserStore.isSubscribed) {
            pager.nav.push(Routes.subscribe)
            return
        }
        val shot = currentShot ?: return
        val videoUrl = shot.videoUrl
        if (videoUrl.isNullOrEmpty()) {
            pager.toast("no_video_to_download".tr)
            return
        }
        isDownloadingVideo = true
        pager.launch {
            try {
                DevLog.logger("downloadVideo url: $videoUrl")
                pager.showLoading()
                val local = CommonUtils.downloadWithCache(videoUrl, "mp4")
                if (local == null) {
                    pager.hideLoading()
                    pager.toast("download_failed".tr)
                    return@launch
                }
                val marked = AigcMediaExport.prepareVideo(localPath = local, source = videoUrl, contentId = "${shot.id}")
                if (closed) {
                    pager.hideLoading()
                    return@launch
                }
                if (marked == null) {
                    pager.toast("aigc_export_failed".tr)
                    pager.hideLoading()
                    return@launch
                }
                val ok = CommonUtils.saveVideoToGallery(marked, albumName = "video_${Random.nextInt(1000000)}.mp4")
                if (ok) {
                    UmengUtils.logEvent("download_mv_shot", mapOf("mv_id" to mvId))
                    pager.toast("download_success".tr)
                } else {
                    pager.toast("download_failed".tr)
                }
                pager.hideLoading()
            } catch (e: Throwable) {
                pager.hideLoading()
                pager.toast("download_failed".tr)
            } finally {
                isDownloadingVideo = false
            }
        }
    }

    /** 下载封面预览图（Dart AIStatusImagePreview._downloadImage，type = image_generation）。 */
    private var isDownloadingImage = false

    fun downloadImage(imageUrl: String) {
        if (isDownloadingImage) return
        isDownloadingImage = true
        pager.launch {
            try {
                pager.showLoading()
                val marked = AigcMediaExport.prepareImage(imageUrl)
                if (marked == null) {
                    pager.toast("aigc_export_failed".tr)
                    pager.hideLoading()
                    return@launch
                }
                val ok = CommonUtils.saveImageToGallery(marked, albumName = "image_${Random.nextInt(1000000)}.png")
                if (ok) {
                    UmengUtils.logEvent("download_ai_image", mapOf("type" to "image_generation"))
                    pager.toast("download_success".tr)
                } else {
                    pager.toast("download_failed".tr)
                }
                pager.hideLoading()
            } catch (e: Throwable) {
                pager.hideLoading()
                pager.toast("download_failed".tr)
            } finally {
                isDownloadingImage = false
            }
        }
    }

    // ------------------------------------------------------------------ 重生成

    /** 积分不足时的去向：订阅用户去积分包，否则去订阅页。 */
    protected fun goBuyPoints() {
        if (UserStore.isSubscribed) {
            UmengUtils.logEvent("credit_insufficient_popup")
            pager.nav.push(Routes.creditsPurchase)
        } else {
            pager.nav.push(Routes.subscribe)
        }
    }

    /** 本次重生成所需积分；编辑页为 point，重试页为分镜 retryNeedPoint。 */
    protected abstract fun recreatePoint(shot: MvShot): Int

    /** 积分是否不足（两页判断条件不同）。 */
    protected abstract fun isPointInsufficient(userPoint: Int, needPoint: Int): Boolean

    /** 重生成接口受理后的附加处理。 */
    protected open fun onRecreateAccepted() {}

    /** 重生成当前分镜；合成中禁止，避免和整支 Merge 抢任务。 */
    fun onRecreate() {
        if (isMerging) {
            pager.toast("video_is_being_merged".tr)
            return
        }
        val shot = currentShot ?: return
        if (shot.status != 2 && shot.status != 3) {
            pager.toast("please_wait_for_the_shot_to_be_generated".tr)
            return
        }
        val needPoint = recreatePoint(shot)
        val userPoint = UserStore.userInfo?.point ?: 0
        if (isPointInsufficient(userPoint, needPoint)) {
            goBuyPoints()
            return
        }
        if (isAnyImageLoading(shot.id)) {
            pager.toast("image_checking".tr)
            return
        }
        if (getFirstSensitiveImage(shot.id) != null) {
            pager.toast("image_sensitive_copyrighted_cannot_use".tr)
            return
        }
        pager.launch {
            pager.showLoading()
            val text = shortDescText
            val textCheckResult = EditVideoRepository.checkText(text).getOrNull() ?: ""
            if (closed) {
                pager.hideLoading()
                return@launch
            }
            if (textCheckResult.isNotEmpty()) {
                pager.hideLoading()
                pager.toast(textCheckResult)
                return@launch
            }
            videoPlayer.forceDispose()
            UmengUtils.logEvent("retry_fragment_video")
            // 请求发出前就记下索引：await 期间用户可能切走分镜
            val recreateIndex = shotIndex
            reCreateIndexList.add(recreateIndex)
            isRecreateBtn = false
            val coverImgs = buildCoverImgParam(shot.id)
            DevLog.logger("onRecreate: $text")
            val res = if (retryMode) {
                EditVideoRepository.retryFlowShot(
                    shot.id, shot.shortDesc, text, "0", isUpdt = 0, regenImage = 1, point = needPoint, referenceUrls = coverImgs,
                )
            } else {
                EditVideoRepository.regenCustomShot(
                    shot.id, shot.shortDesc, text, "0", isUpdt = 1, regenImage = 1, point = needPoint, referenceUrls = coverImgs,
                )
            }
            if (closed) {
                pager.hideLoading()
                return@launch
            }
            val data = res.getOrNull()
            if (data == null) {
                pager.toast("recreate_failed".tr)
                pager.hideLoading()
                restoreAfterRecreateFailed(recreateIndex)
                return@launch
            }
            isDelete = false
            isAllVideoLoaded = false
            val updated = if (genMode == 2) data.copy(status = 0, shortDesc = text) else data.copy(status = 0, description = text)
            setShotAt(shotIndex, updated)
            onRecreateAccepted()
            shortDescText = text
            // 重新开始轮询
            startTimer()
            onSelectShot(shotIndex, isFirst = true)
            pager.hideLoading()
            UserStore.getUserInfo()
        }
    }

    /** 重新生成接口失败：撤销预提交副作用，并把原分镜视频重新挂回播放器。 */
    private fun restoreAfterRecreateFailed(recreateIndex: Int) {
        reCreateIndexList.remove(recreateIndex)
        if (closed) return
        if (shotIndex != recreateIndex) return
        onSelectShot(recreateIndex, isFirst = true)
    }

    /** 点时间轴：留在本页选中分镜，合成中不可改。 */
    fun toStoryboard(index: Int) {
        CommonUtils.hideKeyboard()
        if (isMerging) {
            pager.toast("video_is_being_merged".tr)
            return
        }
        onSelectShot(index)
    }

    fun onClearShotDescription() {
        val shot = currentShot ?: return
        if (shot.status != 2 && shot.status != 3) {
            pager.toast("please_wait_for_the_shot_to_be_generated".tr)
            return
        }
        shortDescText = ""
        dialogDescText = ""
    }

    // ------------------------------------------------------------------ 封面状态

    fun getAgainMergeCoverImg() {
        pager.launch {
            val data = EditVideoRepository.fetchCoverStatus(mvId).getOrNull() ?: return@launch
            if (closed) return@launch
            when (data.againCoverImgStatus) {
                1 -> startCoverTimer()
                2 -> {
                    timerCoverSeq++
                    mvDetail = mvDetail.copyWith(firstFrameImg = data.firstFrameImg)
                }
                3 -> timerCoverSeq++
            }
            coverStatus = data.againCoverImgStatus ?: 0
        }
    }

    fun startCoverTimer() {
        val seq = ++timerCoverSeq
        fun schedule() {
            pager.postDelayed(10_000) {
                if (closed || seq != timerCoverSeq) return@postDelayed
                if (!isActiveRoute) {
                    schedule()
                    return@postDelayed
                }
                pager.launch {
                    val data = EditVideoRepository.fetchCoverStatus(mvId).getOrNull()
                    if (closed || seq != timerCoverSeq) return@launch
                    if (data != null && (data.againCoverImgStatus == 2 || data.againCoverImgStatus == 3)) {
                        timerCoverSeq++
                        if (data.againCoverImgStatus == 2) {
                            mvDetail = mvDetail.copyWith(firstFrameImg = data.firstFrameImg)
                            MvService.refreshMvStatus(mvId, MvStatus.success, firstFrameImg = data.firstFrameImg)
                            pager.toast("regenerate_cover_success".tr)
                        } else {
                            pager.toast("regenerate_cover_failed".tr)
                        }
                        coverStatus = data.againCoverImgStatus ?: 0
                    } else {
                        schedule()
                    }
                }
            }
        }
        schedule()
    }

    protected fun cancelCoverTimer() {
        timerCoverSeq++
    }

    // ------------------------------------------------------------------ 生命周期

    /** Dart onInit：读路由参数。 */
    open fun onInit(args: JSONObject) {
        mvId = args.optLong("id", 0L)
        mvIndex = args.optInt("index", 0)
    }

    protected open fun initData() {
        getMvDetail(isSelectFirst = true)
        startTimer()
    }

    /** Dart onReady：暂停音乐、常亮、刷新价格，然后拉数据。 */
    open fun onReady() {
        PlayerService.launch { PlayerService.pause() }
        keepScreenOn(true)
        pager.launch { com.sondo.app.service.ConfigService.getCountryPricing() }
        videoPlayer.reset()
        initData()
    }

    private fun keepScreenOn(on: Boolean) {
        try {
            pager.acquireModule<SDAppModule>(SDAppModule.MODULE_NAME).setKeepScreenOn(on)
        } catch (e: Throwable) {
        }
    }

    /** Dart onClose：释放播放器、轮询与常亮。 */
    open fun onClose() {
        if (closed) return
        keepScreenOn(false)
        cancelTimer()
        cancelCoverTimer()
        currentCancelToken?.cancel()
        reCreateIndexList.clear()
        isRecreateBtn = false
        videoPlayer.dispose()
        closed = true
    }

    companion object {
        private const val maxConcurrentUploads = 2
        const val maxReferenceImages = 4
    }
}
