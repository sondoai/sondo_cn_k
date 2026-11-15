package com.sondo.app.features.music_video

import com.sondo.app.base.AppHost
import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.http.ApiResult
import com.sondo.app.core.http.AuthSession
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.i18n.trParams
import com.sondo.app.core.storage.Sp
import com.sondo.app.core.utils.CommonUtils
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.truncateGraphemes
import com.sondo.app.data.api.StreamHandle
import com.sondo.app.data.models.AudioItemStatus
import com.sondo.app.data.models.AudioUploadItem
import com.sondo.app.data.models.ImageUploadStatus
import com.sondo.app.data.models.MusicStyleEntity
import com.sondo.app.data.models.MusicStyleModel
import com.sondo.app.data.models.RoleModel
import com.sondo.app.data.models.SongModel
import com.sondo.app.data.models.VideoCoverImage
import com.sondo.app.data.models.VideoGenParams
import com.sondo.app.data.models.jArr
import com.sondo.app.data.models.jInt
import com.sondo.app.data.models.jObj
import com.sondo.app.data.models.jStr
import com.sondo.app.features.music_video.data.MusicVideoGenApi
import com.sondo.app.features.music_video.data.MvTemplateEntity
import com.sondo.app.features.music_video.data.MvTemplateMapper
import com.sondo.app.features.music_video.description.DescriptionIdeaCatalog
import com.sondo.app.features.music_video.description.ideaCatalogFromMvIdeas
import com.sondo.app.ui.works.select.MusicSelectResult
import com.sondo.app.ui.works.select.PhotoSelectAction
import com.sondo.app.ui.works.select.PhotoSelectItem
import com.sondo.app.ui.works.select.PhotoSelectItemType
import com.sondo.app.ui.works.select.PhotoSelectSectionType
import com.sondo.app.ui.works.select.musicSelect
import com.sondo.app.ui.works.select.photoSelect
import com.sondo.app.service.ConfigService
import com.sondo.app.service.LibraryTabRefreshService
import com.sondo.app.service.MemoryCacheKeys
import com.sondo.app.service.MemoryCacheService
import com.sondo.app.service.MvService
import com.sondo.app.service.OssUploadService
import com.sondo.app.service.PlayerService
import com.sondo.app.service.UserStore
import com.sondo.app.service.WorksUi
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.ui.dialog.showVideoInfoDialog
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.setTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** MV 创建表单校验字段。 */
enum class MvFormField { music, cover, description, title, author }

/** 单个歌手上传槽；封面 URL、四视图角色与上传态互斥存放（Dart `MvSingerSlot`）。 */
class MvSingerSlot(
    var imageUrl: String = "",
    /** 本地选图路径（上传失败 / 违规时保留预览与重试）。 */
    var imageFile: String? = null,
    var role: RoleModel = RoleModel(id = -1L),
    var isImageLoading: Boolean = false,
    var isImageFailed: Boolean = false,
    var isHasSensitive: Boolean = false,
    var errorMessage: String = "",
    var errMsgToast: String = "",
    var imageProgress: Int = 0,
) {
    val hasPreview: Boolean get() = imageUrl.isNotEmpty() || imageFile != null || (role.id > 0 && role.roleImg.isNotEmpty())
    val isReady: Boolean get() = imageUrl.isNotEmpty() || (role.id > 0 && role.roleImg.isNotEmpty())
    val coverUrl: String get() = if (role.id > 0 && role.roleImg.isNotEmpty()) role.roleImg else imageUrl
    val fourViewId: Long get() = if (role.id > 0) role.id else 0L

    fun toDraftJson(): JSONObject {
        val o = JSONObject().put("imageUrl", imageUrl)
        if (role.id > 0) o.put("role", role.toJson())
        return o
    }

    companion object {
        fun fromDraftJson(json: JSONObject): MvSingerSlot = MvSingerSlot(
            imageUrl = json.jStr("imageUrl"),
            role = json.jObj("role")?.let { RoleModel.fromJson(it) } ?: RoleModel(id = -1L),
        )
    }
}

/** 歌手槽列表的截断、删除前移与提交组包（Dart `MvSingerSlots`）。 */
object MvSingerSlots {
    fun truncate(slots: List<MvSingerSlot>, maxCount: Int): List<MvSingerSlot> {
        if (maxCount < 0) return emptyList()
        return if (slots.size <= maxCount) slots.toList() else slots.take(maxCount)
    }

    fun afterClear(slots: List<MvSingerSlot>, index: Int): List<MvSingerSlot> {
        if (index < 0 || index >= slots.size) return slots.toList()
        return slots.toMutableList().also { it.removeAt(index) }
    }

    /** 选图中止：无预览的占位槽删掉，已有图的槽保留。 */
    fun afterAbortedPick(slots: List<MvSingerSlot>, index: Int): List<MvSingerSlot> {
        if (index < 0 || index >= slots.size) return slots.toList()
        if (slots[index].hasPreview) return slots.toList()
        return afterClear(slots, index)
    }

    fun fromDraft(draft: JSONObject): List<MvSingerSlot> {
        val singersJson = draft.jArr("singers")
        if (singersJson != null) {
            val out = ArrayList<MvSingerSlot>()
            for (i in 0 until singersJson.length()) {
                val item = singersJson.opt(i) as? JSONObject ?: continue
                val slot = MvSingerSlot.fromDraftJson(item)
                if (slot.isReady) out.add(slot)
            }
            return out
        }
        // 旧草稿只有单槽 imageUrl / role
        val legacy = MvSingerSlot(
            imageUrl = draft.jStr("imageUrl"),
            role = draft.jObj("role")?.let { RoleModel.fromJson(it) } ?: RoleModel(id = -1L),
        )
        return if (legacy.isReady) listOf(legacy) else emptyList()
    }

    fun coverImages(slots: List<MvSingerSlot>): List<VideoCoverImage> {
        val ready = slots.filter { it.isReady }
        return ready.mapIndexed { i, s ->
            VideoCoverImage(
                // 四视图只传 id，url 必须空串；上传图只传 url，fourViewId 必须为 0。
                url = if (s.fourViewId > 0) "" else s.imageUrl,
                fourViewId = s.fourViewId,
                roleType = if (i == 0) VideoCoverImage.roleTypeLead else VideoCoverImage.roleTypeSupporting,
            )
        }
    }

    fun characterCount(slots: List<MvSingerSlot>): String = "${coverImages(slots).size}"
}

/**
 * MV 创作表单（Dart `MvCreateForm`）。不是页面，普通页和活动页各持有一份。
 * 字段是挂在页面上的 observable，组件在 `attr { }` 里直接读。
 *
 * @param persistPreferences 活动页为 false：不把画幅、模式、字幕、特效写进本地
 */
class MvCreateForm(
    val pager: BasePager,
    val persistPreferences: Boolean = true,
    val pageName: String = "/music_video_gen",
) {
    private var disposed = false

    /** 页面已销毁或正在关闭时为 true。写状态前先看它。 */
    fun isInactive(): Boolean = disposed

    // --- 表单输入 ---
    var description by pager.observable("")
    var title by pager.observable("")
    var author by pager.observable("")

    /** 描述框注册的写入口：程序改描述（模板 / 润色 / 预设）时把文字推进输入框。参数：文本、光标（null 放末尾）。 */
    var descriptionWriter: ((String, Int?) -> Unit)? = null

    /** 描述框注册：打开 @ 玩法 标签列表（参数为被点的标签名，null 为按钮入口）。 */
    var openIdeas: ((String?) -> Unit)? = null

    /** 校验失败后滚到对应区块（页面注册）。 */
    var scrollToSection: ((MvFormField) -> Unit)? = null

    /** 展开高级选项后滚到底（页面注册）。 */
    var scrollToBottom: (() -> Unit)? = null

    // --- 校验定位 ---
    var invalidField by pager.observable<MvFormField?>(null)

    // --- 音频上传 ---
    var mvUploadItem by pager.observable<AudioUploadItem?>(null)

    // --- 选曲与裁剪 ---
    var videoAspectRatio by pager.observable(if (persistPreferences) Sp.getString(StorageKey.videoAspectRatio, "9:16") else "9:16")
    var quality by pager.observable("480")
    /** MV 模式：1=标准，2=Ultra（Seedance）。 */
    var mvVersion by pager.observable(if (persistPreferences) Sp.getString(StorageKey.newMvMode, "1") else "1")
    var isPauseChooseSong by pager.observable(false)
    /** 当前选中歌曲；id=0 表示本地上传 */
    var selectedSong by pager.observable(SongModel(id = 0L))
    var isShowCropper by pager.observable(false)

    // --- 歌手参考图 ---
    private val originalImage = ArrayList<MusicStyleEntity>()
    var imageList by pager.observable<List<MusicStyleEntity>>(emptyList())
    /** 歌手槽；槽对象原地修改后 [singerRev] +1 触发刷新。 */
    val singers = ArrayList<MvSingerSlot>()
    var singerRev by pager.observable(0)
    var errMsgToast = ""

    // --- 时长与提交 ---
    var subDurationScope by pager.observable("")
    var subDuration by pager.observable(0)
    var isMusicCropReady = false
    var canCreate by pager.observable(false)
    var isCreateLoading by pager.observable(false)
    /** 选中歌曲原始时长（秒，活动预设音频带入）。 */
    var musicDuration by pager.observable<Int?>(null)

    // --- 高级选项 ---
    var taskType = 0
    var optionsExpanded by pager.observable(false)
    var isStreamLoading by pager.observable(false)
    var isTextOutputting by pager.observable(false)
    private var polishStream: StreamHandle? = null
    var isSubtitles by pager.observable(Sp.getBool(StorageKey.isRemoveSubtitles, true))
    var isEffectsEnabled by pager.observable(false)

    var isLoadingImage by pager.observable(false)

    /** 选中的风格模板。只回填描述和决定 @ Ideas，不进创建接口。 */
    var template by pager.observable(MvTemplateEntity())

    var defaultSelectionSeconds by pager.observable(0)

    // --- 服务镜像 ---
    var ideasCatalog by pager.observable<DescriptionIdeaCatalog>(readIdeas())
    var templateListRev by pager.observable(0)
    var isLogin by pager.observable(UserStore.isLogin)
    var createLabelKey by pager.observable(UserStore.createButtonLabelKey)
    var createShowPoints by pager.observable(UserStore.createButtonShowPoints)
    var mvFlagshipOpen by pager.observable(ConfigService.mvFlagshipOpen)
    var mvSpecialEffectsOpen by pager.observable(ConfigService.mvSpecialEffectsOpen)
    /** 配置刷新后积分重算（requiredPoints 读这个触发响应）。 */
    var configRev by pager.observable(0)

    /** 本次生成所需积分，Ultra 模式（mvVersion=2）使用 Seedance 积分。 */
    val requiredPoints: Int
        get() {
            configRev
            return if (mvVersion == "2") ConfigService.mvSeedanceCreatePoint else ConfigService.customOnePoint
        }

    val isUltraMode: Boolean get() = mvVersion == "2"

    /** Ultra 默认不展示/不使用模板；高级版始终可用。切模式不清 template，便于切回后恢复展示。 */
    val showStyleTemplate: Boolean get() = !isUltraMode || ultraStyleTemplateEnabled

    private val canUseEffects: Boolean get() = (UserStore.userInfo?.isUseCapcut ?: 1) == 0

    /** 本地开关打开时最多 2 位，关闭时 1 位。 */
    val maxSingerCount: Int get() = if (AppConstants.enableMvDualSinger) 2 else 1

    /** Style Template 按钮预览图：优先当前选中模板，否则列表前两项。 */
    val stylePreviewUrls: List<String>
        get() {
            templateListRev
            val list = MvService.mvTemplateList
            if (template.id > 0) {
                val selected = list.filter { it.id == template.id }.map { it.label }.filter { it.startsWith("http") }
                if (selected.isNotEmpty()) return selected.take(2)
            }
            return list.map { it.label }.filter { it.startsWith("http") }.take(2)
        }

    fun readIdeas(): DescriptionIdeaCatalog = ideaCatalogFromMvIdeas(MvService.mvIdeas.toList(), LinkedHashMap(MvService.mvIdeasMap))

    private fun notifySingers() {
        if (isInactive()) return
        singerRev++
    }

    private fun syncUser() {
        isLogin = UserStore.isLogin
        createLabelKey = UserStore.createButtonLabelKey
        createShowPoints = UserStore.createButtonShowPoints
    }

    /** 订阅上传、服务字典和账号。不读路由，不恢复草稿。 */
    fun bind() {
        pager.on(Events.UPLOAD_TASKS_CHANGED) { onUploadTasksChanged(it) }
        pager.on(Events.MV_DICT_CHANGED) {
            when (it.optString("kind", "")) {
                "ideas" -> ideasCatalog = readIdeas()
                "template" -> templateListRev++
            }
        }
        pager.on(Events.USER_CHANGED) { syncUser() }
        pager.on(Events.POINTS_CHANGED) { syncUser() }
        pager.on(Events.CONFIG_CHANGED) {
            mvFlagshipOpen = ConfigService.mvFlagshipOpen
            mvSpecialEffectsOpen = ConfigService.mvSpecialEffectsOpen
            configRev++
        }
        if (MvService.mvTemplateList.isEmpty()) pager.launch { MvService.getMvTemplateList() }
        if (MvService.roleList.isEmpty()) pager.launch { MvService.getRecentRoleList() }
    }

    /** 草稿恢复之后对齐模式、裁剪时长和特效。活动页改走 [lockStandardMode]。 */
    fun applyStartupMode() {
        if (!ConfigService.mvFlagshipOpen && isUltraMode) setMvMode("1")
        defaultSelectionSeconds = if (isUltraMode) ConfigService.mvFlowV2AiDanceMusicChoseSecond else ConfigService.mvFlowV2MusicChoseSecond
        if (!isUltraMode) {
            val wantsOn = if (Sp.has(StorageKey.mvEffectsEnabled)) Sp.getBool(StorageKey.mvEffectsEnabled, true) else true
            val enabled = wantsOn && canUseEffects
            if (persistPreferences && wantsOn && !enabled) Sp.setBool(StorageKey.mvEffectsEnabled, false)
            isEffectsEnabled = enabled
        }
    }

    /** 活动页没有 Ultra：模式、画幅、清晰度用本次固定值，不读不写本地偏好。 */
    fun lockStandardMode() {
        mvVersion = "1"
        videoAspectRatio = "9:16"
        quality = "480"
        defaultSelectionSeconds = ConfigService.mvFlowV2MusicChoseSecond
        val wantsOn = if (Sp.has(StorageKey.mvEffectsEnabled)) Sp.getBool(StorageKey.mvEffectsEnabled, true) else true
        isEffectsEnabled = wantsOn && canUseEffects
    }

    /** 首帧后再拉推荐头像和 Ideas，避免挡住进页。 */
    fun onFirstFrame() {
        setTimeout(pager.pagerId, 0) {
            if (isInactive()) return@setTimeout
            pager.launch { loadDefaultHeadImages() }
            pager.launch {
                MvService.getMvIdeas()
                if (!isInactive()) ideasCatalog = readIdeas()
            }
        }
    }

    /** 取消润色流。页面销毁时调用。 */
    fun unbind() {
        polishStream?.close()
        polishStream = null
        disposed = true
        descriptionWriter = null
        scrollToSection = null
        scrollToBottom = null
    }

    // ------------------------------------------------------------------ 输入回调

    fun onTitleChanged(text: String) {
        if (isInactive()) return
        title = text
        if (invalidField == MvFormField.title && text.isNotEmpty()) invalidField = null
    }

    fun onAuthorChanged(text: String) {
        if (isInactive()) return
        author = text
        if (invalidField == MvFormField.author && text.isNotEmpty()) invalidField = null
    }

    /** 用户在描述框里输入。 */
    fun onDescriptionInput(text: String) {
        if (isInactive()) return
        description = text
        if (invalidField == MvFormField.description && text.isNotEmpty()) invalidField = null
    }

    /** 程序写描述（同步到输入框）。 */
    fun setDescription(text: String, cursor: Int? = null) {
        if (isInactive()) return
        description = text
        if (invalidField == MvFormField.description && text.isNotEmpty()) invalidField = null
        descriptionWriter?.invoke(text, cursor)
    }

    // ------------------------------------------------------------------ 校验

    /** 标记校验失败字段：红框 + 滚动 + Toast。 */
    private fun markInvalid(field: MvFormField, toastMessage: String) {
        if (isInactive()) return
        invalidField = field
        if (field == MvFormField.author && !optionsExpanded) optionsExpanded = true
        pager.toast(toastMessage)
        val scroll = scrollToSection
        // 展开选项面板需要一帧布局后再定位
        setTimeout(pager.pagerId, if (field == MvFormField.author) 220 else 16) { scroll?.invoke(field) }
    }

    private fun hasSong(): Boolean = selectedSong.id != 0L || !selectedSong.musicAudioUrl.isNullOrEmpty()

    private fun clearMusicInvalidIfNeeded() {
        if (isInactive() || invalidField != MvFormField.music) return
        if (hasSong() && isMusicCropReady && subDuration >= 30) invalidField = null
    }

    /** 选曲成功后清除音乐区块红框（不等待裁剪时长达标）。 */
    private fun clearMusicInvalidOnSongSelected() {
        if (isInactive()) return
        if (invalidField == MvFormField.music) invalidField = null
    }

    private fun clearCoverInvalidIfNeeded() {
        if (isInactive()) return
        val hasCover = singers.any { it.isReady }
        if (invalidField == MvFormField.cover && hasCover && singers.none { it.isImageLoading } && singers.none { it.isHasSensitive }) {
            invalidField = null
        }
    }

    /** 路由带入的歌曲优先于草稿。 */
    fun applyRoutedSong(songJson: JSONObject) {
        if (isInactive()) return
        resetMusicCropState()
        val song = SongModel.fromJson(songJson)
        selectedSong = song
        title = song.musicTitle ?: ""
        isShowCropper = true
        mvUploadItem = null
        updateCreateBtn()
    }

    /** 更新创建按钮可用态：选曲、时长≥30s；照片选填，但进行中或违规图不可提交。 */
    fun updateCreateBtn() {
        if (isInactive()) return
        clearMusicInvalidIfNeeded()
        clearCoverInvalidIfNeeded()
        if (selectedSong.id == 0L && selectedSong.musicAudioUrl.isNullOrEmpty()) {
            canCreate = false
            return
        }
        if (!isMusicCropReady || subDuration < 30) {
            canCreate = false
            return
        }
        if (singers.any { it.isImageLoading } || singers.any { it.isHasSensitive } || singers.any { it.hasPreview && !it.isReady }) {
            canCreate = false
            return
        }
        canCreate = true
    }

    /** 裁剪器回写当前音源的区间字符串；旧歌曲组件的迟到回调会被丢弃。 */
    fun updateMusicCropScope(source: SongModel, scope: String) {
        if (isInactive() || !isCurrentMusicSource(source)) return
        subDurationScope = scope
    }

    /** 当前音频加载或拖动完成后，同步裁剪秒数并开放提交校验。 */
    fun updateMusicCropDuration(source: SongModel, seconds: Int) {
        if (isInactive() || !isCurrentMusicSource(source)) return
        subDuration = seconds
        isMusicCropReady = true
        updateCreateBtn()
    }

    private fun isCurrentMusicSource(source: SongModel): Boolean =
        selectedSong.id == source.id && selectedSong.musicAudioUrl == source.musicAudioUrl

    // ------------------------------------------------------------------ 模式 / 选项

    /** 切换 MV 模式，首次选 Ultra 时展示说明弹窗。 */
    fun setMvMode(value: String) {
        if (isInactive()) return
        mvVersion = value
        if (persistPreferences) Sp.setString(StorageKey.newMvMode, value)
        val isShowMvModeDialog = Sp.getBool(StorageKey.newMvModeDialog, false)
        if (value == "2" && !isShowMvModeDialog) {
            if (persistPreferences) Sp.setBool(StorageKey.newMvModeDialog, true)
            showMvModeInfo()
        }
        defaultSelectionSeconds = if (value == "2") ConfigService.mvFlowV2AiDanceMusicChoseSecond else ConfigService.mvFlowV2MusicChoseSecond
    }

    /** 展示 Ultra 模式说明弹窗。 */
    fun showMvModeInfo() {
        pager.showVideoInfoDialog(
            title = "effects_preview".tr,
            videoUrl = "https://cdn.sondo.ai/sc2026-07-02/wzr_nb666.mp4",
            description = "mv_mode_info_description".tr,
            onTry = { setMvMode("2") },
        )
    }

    /** 展开/收起高级选项。 */
    fun toggleOptions() {
        if (isInactive()) return
        optionsExpanded = !optionsExpanded
        if (optionsExpanded) {
            setTimeout(pager.pagerId, 216) {
                if (!isInactive()) scrollToBottom?.invoke()
            }
        }
    }

    /** 更新画幅比并持久化。 */
    fun updateVideoAspectRatio(value: String) {
        if (isInactive()) return
        videoAspectRatio = value
        if (persistPreferences) Sp.setString(StorageKey.videoAspectRatio, value)
    }

    private suspend fun ensureLogin(): Boolean {
        if (UserStore.isLogin) return true
        return UserStore.showLoginDialog(pager)
    }

    /** 打开页面并等它关闭（Get.toNamed 的 await）。 */
    private suspend fun pushAwait(page: String, args: JSONObject? = null): JSONObject? =
        suspendCoroutine { cont -> pager.nav.push(page, args) { cont.resume(it) } }

    /** 更新输出清晰度（需登录且订阅）。 */
    fun updateQuality(value: String) {
        pager.launch {
            if (!ensureLogin() || isInactive()) return@launch
            // 去水印为订阅权益
            if (!UserStore.isSubscribed) {
                pushAwait(Routes.subscribe)
                return@launch
            }
            quality = value
            updateCreateBtn()
        }
    }

    /** 切换字幕显示，需登录且订阅。 */
    fun changeSubtitles(value: Boolean) {
        pager.launch {
            if (!ensureLogin() || isInactive()) return@launch
            if (!UserStore.isSubscribed) {
                pushAwait(Routes.subscribe)
                return@launch
            }
            isSubtitles = value
            if (persistPreferences) Sp.setBool(StorageKey.isRemoveSubtitles, value)
        }
    }

    /** 切换 Effects，开启需订阅或首次免费特效未用。 */
    fun changeEffects(value: Boolean) {
        if (isInactive()) return
        if (!value) {
            isEffectsEnabled = false
            if (persistPreferences) Sp.setBool(StorageKey.mvEffectsEnabled, false)
            return
        }
        pager.launch {
            if (!ensureLogin() || isInactive()) return@launch
            // 没有试用 并且 不是会员 则跳转订阅页
            if (!canUseEffects && !UserStore.isSubscribed) {
                pushAwait(Routes.subscribe)
                return@launch
            }
            isEffectsEnabled = true
            if (persistPreferences) Sp.setBool(StorageKey.mvEffectsEnabled, true)
        }
    }

    // ------------------------------------------------------------------ 选曲 / 上传

    /** 打开选曲弹窗（曲库 Tab）。 */
    fun onChooseSong() {
        if (isInactive()) return
        CommonUtils.hideKeyboard()
        UmengUtils.logEventButtonClick("mv_select_song", pageName = pageName)
        pager.launch {
            val result = pager.musicSelect("mv")
            if (isInactive()) return@launch
            handleMusicSelectResult(result)
            if (isInactive()) return@launch
            syncPendingUploadAfterSheet()
        }
    }

    private fun handleMusicSelectResult(result: MusicSelectResult?) {
        if (isInactive() || result == null) return
        // Try Now：跳转音乐创作页
        if (result is MusicSelectResult.TryNow) {
            WorksUi.openCreatePage(Routes.musicCreate, null)
            MemoryCacheService.clearByPrefix(MemoryCacheKeys.musicSelectMyTypePrefix("mv"))
            return
        }
        // 弹窗内已开传：关窗后由页面跟进度，完成后打开裁剪器
        if (result is MusicSelectResult.Upload) {
            resetMusicCropState()
            isShowCropper = false
            selectedSong = SongModel(id = 0L)
            mvUploadItem = result.item
            return
        }
        if (result is MusicSelectResult.Song) {
            val picked = result.song
            DevLog.logger("music_video_gen SongModel: ${picked.toJson()}")
            val current = selectedSong
            val sourceChanged = current.id != picked.id || current.musicAudioUrl != picked.musicAudioUrl
            // 同一音源重复选择时保留用户裁剪；仅新音源等待重新加载真实区间。
            if (sourceChanged) resetMusicCropState()
            isShowCropper = true
            title = picked.musicTitle ?: ""
            selectedSong = picked
            mvUploadItem = null
            clearMusicInvalidOnSongSelected()
            updateCreateBtn()
        }
    }

    /** 弹窗关闭后同步进行中的上传任务到页面展示。 */
    private fun syncPendingUploadAfterSheet() {
        if (isInactive() || isShowCropper) return
        val pending = OssUploadService.tasks
            .filter { it.status.isActive || it.status == AudioItemStatus.paused }
            .sortedByDescending { it.createdAt ?: 0L }
        if (pending.isEmpty()) return
        mvUploadItem = pending.first()
    }

    private fun onUploadTasksChanged(json: JSONObject) {
        if (isInactive()) return
        val uploadId = mvUploadItem?.uploadId ?: return
        val tasks = OssUploadService.tasks
        val idx = tasks.indexOfFirst { it.uploadId == uploadId }
        if (idx == -1) {
            mvUploadItem = null
            return
        }
        val updated = tasks[idx].copy()
        mvUploadItem = updated
        if (updated.status == AudioItemStatus.completed && updated.fileUrl != null && !isShowCropper) onMvUploadComplete(updated)
    }

    /** 上传完成后填充歌曲并打开裁剪器。 */
    private fun onMvUploadComplete(item: AudioUploadItem) {
        if (isInactive()) return
        val url = item.fileUrl ?: ""
        val fileName = item.fileName
        var t = ""
        if (fileName.isNotEmpty()) {
            t = if (fileName.contains(".")) fileName.split(".").first() else fileName
            t = truncateGraphemes(t, titleMaxLength)
        }
        // 上传文件是新的音频源，必须等待裁剪器重新写入其真实区间。
        resetMusicCropState()
        selectedSong = SongModel(id = 0L, musicTitle = t, musicAudioUrl = url, musicCoverUrl = "")
        title = t
        isShowCropper = true
        mvUploadItem = null
        clearMusicInvalidOnSongSelected()
        updateCreateBtn()
    }

    fun onRemoveMvUpload(item: AudioUploadItem) {
        pager.launch {
            pager.showLoading()
            try {
                OssUploadService.removeItem(item)
            } finally {
                pager.hideLoading()
            }
            if (isInactive()) return@launch
            mvUploadItem = null
        }
    }

    /** 暂停当前页展示的上传任务，并立刻同步卡片状态。 */
    fun pauseMvUpload(uploadId: String) {
        OssUploadService.pauseTask(uploadId)
        syncMvUploadItem(uploadId)
    }

    /** 恢复暂停的上传；若旧任务仍在收尾，由服务侧排队拉起。 */
    fun resumeMvUpload(uploadId: String) {
        OssUploadService.resumeTask(uploadId)
        syncMvUploadItem(uploadId)
    }

    /** 失败后重试上传（与 resume 共用服务实现）。 */
    fun retryMvUpload(uploadId: String) {
        OssUploadService.retryTask(uploadId)
        syncMvUploadItem(uploadId)
    }

    private fun syncMvUploadItem(uploadId: String) {
        if (isInactive()) return
        val item = OssUploadService.tasks.firstOrNull { it.uploadId == uploadId } ?: return
        mvUploadItem = item.copy()
    }

    private fun resetMusicCropState() {
        musicDuration = null
        subDuration = 0
        subDurationScope = ""
        isMusicCropReady = false
        canCreate = false
    }

    /** 清空已选歌曲。 */
    fun clearChooseSong() {
        if (isInactive()) return
        mvUploadItem = null
        isShowCropper = false
        selectedSong = SongModel(id = 0L)
        resetMusicCropState()
        updateCreateBtn()
    }

    // ------------------------------------------------------------------ 歌手图

    private suspend fun loadDefaultHeadImages() {
        if (isInactive() || originalImage.isNotEmpty() || isLoadingImage) return
        isLoadingImage = true
        val result = MusicVideoGenApi.getDefaultHeadImages()
        // 页面退出后不再写推荐图列表。
        if (isInactive()) return
        result.onSuccess { list ->
            originalImage.clear()
            originalImage.addAll(list)
            imageList = list.toList()
        }
        isLoadingImage = false
        notifySingers()
    }

    private fun ensureSlot(index: Int) {
        if (index < 0 || index >= maxSingerCount) return
        while (singers.size <= index) singers.add(MvSingerSlot())
    }

    /** 取消选图/角色库后丢掉无预览空槽，避免角色区从「上传」变成空白卡。 */
    private fun discardEmptySlot(index: Int) {
        if (isInactive()) return
        if (index >= 0 && index < singers.size) singers[index].isImageLoading = false
        val next = MvSingerSlots.afterAbortedPick(singers, index)
        singers.clear()
        singers.addAll(next)
        notifySingers()
    }

    private val limitedSingers: List<MvSingerSlot> get() = MvSingerSlots.truncate(singers, maxSingerCount)

    /** 打开选图弹窗。 */
    fun openSelectPhoto(index: Int = 0) {
        if (index < 0 || index >= maxSingerCount) return
        CommonUtils.hideKeyboard()
        val items = ArrayList<PhotoSelectItem>()
        MvService.roleList.filter { it.roleImg.startsWith("http") }.forEach { items.add(PhotoSelectItem.fromRole(it)) }
        imageList.filter { it.value.startsWith("http") }.forEach { items.add(PhotoSelectItem.fromRecommend(it.value)) }
        // 第一槽是歌手，第二槽是配角；标题/说明按角色区分。
        val isSupportingRole = index > 0
        pager.launch {
            val result = pager.photoSelect(
                sectionType = PhotoSelectSectionType.usageRecord,
                items = items,
                title = (if (isSupportingRole) "upload_supporting_role_photos" else "upload_singer_photos").tr,
                tip = (if (isSupportingRole) "upload_supporting_role_photos_tip" else "upload_singer_photos_tip").tr,
            ) ?: return@launch
            if (isInactive()) return@launch
            when (result.action) {
                PhotoSelectAction.camera, PhotoSelectAction.album -> pickImage(
                    result.imageSource ?: if (result.action == PhotoSelectAction.camera) CommonUtils.SOURCE_CAMERA else CommonUtils.SOURCE_GALLERY,
                    index,
                )
                PhotoSelectAction.itemSelected -> {
                    val item = result.selectedItem ?: return@launch
                    val role = item.role
                    if (item.type == PhotoSelectItemType.recommend || role == null) selectPresetImage(item.imageUrl, index) else selectRole(role, index)
                }
                PhotoSelectAction.roleLibrary -> openRoleLibrary(index)
            }
        }
    }

    /** 从相机/相册选图并上传检测。 */
    private suspend fun pickImage(source: String, index: Int) {
        if (index < 0 || index >= maxSingerCount) return
        CommonUtils.hideKeyboard()
        val isAgree = UserStore.showAgree(StorageKey.isAgreeUpload, "upload", pager)
        if (isInactive() || !isAgree) return
        try {
            val picked = CommonUtils.pickImage(
                source,
                pager = pager,
                callback = { type ->
                    if (!isInactive() && type == "pick_image_success") {
                        // 真正选中后才占槽，压缩/校验期间显示 loading
                        ensureSlot(index)
                        singers[index].isImageLoading = true
                        notifySingers()
                    }
                },
                isCheckImageSize = true,
                isCheckImageAspectRatio = true,
                isCheckImageHW = true,
            )
            if (isInactive()) return
            if (picked == null) {
                discardEmptySlot(index)
                return
            }
            if (!ensureLogin() || isInactive()) {
                discardEmptySlot(index)
                return
            }
            ensureSlot(index)
            singers[index].imageFile = picked
            retryUploadImage(picked, index)
        } catch (e: Throwable) {
            if (isInactive()) return
            discardEmptySlot(index)
            pager.toast("image_pick_failed".tr)
        }
    }

    /** 失败槽点击重试。 */
    fun retryUpload(index: Int) {
        if (index < 0 || index >= singers.size) return
        val file = singers[index].imageFile ?: return
        pager.launch { retryUploadImage(file, index) }
    }

    /** 上传图片并检测；成功写 URL，check 标敏感，failure 显示通用失败提示（超限另弹 toast）。 */
    private suspend fun retryUploadImage(image: String, index: Int) {
        if (isInactive() || index < 0 || index >= maxSingerCount) return
        ensureSlot(index)
        val slot = singers[index]
        slot.isImageFailed = false
        slot.isImageLoading = true
        slot.isHasSensitive = false
        slot.errMsgToast = ""
        slot.errorMessage = ""
        slot.imageProgress = 0
        errMsgToast = ""
        // 图片已进入上传流程，立即移除“未上传”红框；上传失败后再次创作会重新校验。
        if (invalidField == MvFormField.cover) invalidField = null
        notifySingers()
        UmengUtils.logEventButtonClick("mv_upload_photo", pageName = pageName)

        val result = MusicVideoGenApi.uploadImageWithCheck(image) { progress ->
            if (!isInactive()) {
                slot.imageProgress = progress
                notifySingers()
            }
        }
        if (isInactive()) return
        when (result) {
            is ApiResult.Success -> {
                val upload = result.data
                when (upload.status) {
                    ImageUploadStatus.success -> {
                        slot.imageUrl = upload.url ?: ""
                        slot.role = RoleModel(id = -1L)
                        slot.isHasSensitive = false
                    }
                    ImageUploadStatus.check -> {
                        slot.imageFile = image
                        slot.isHasSensitive = true
                        slot.errorMessage = "violation_detected".tr
                        slot.errMsgToast = "image_sensitive_copyrighted_cannot_use".tr
                        errMsgToast = slot.errMsgToast
                    }
                    ImageUploadStatus.failure -> {
                        if (upload.size != null) pager.toast("the_image_size_cannot_exceed".trParams("value" to "${upload.size}"))
                        // 上传失败保留本地预览和重试入口，并在卡片下方显示统一文案。
                        slot.isImageFailed = true
                        slot.imageFile = image
                        slot.errorMessage = "upload_failed".tr
                    }
                }
                // 上传完成后再重算创建条件，避免 loading 状态阻断最终结果刷新。
                slot.isImageLoading = false
                updateCreateBtn()
                notifySingers()
            }
            is ApiResult.Failure -> {
                slot.isImageFailed = true
                slot.isImageLoading = false
                slot.imageFile = image
                slot.errorMessage = "upload_failed".tr
                notifySingers()
            }
        }
    }

    fun selectPresetImage(url: String, index: Int = 0) {
        if (isInactive() || index < 0 || index >= maxSingerCount) return
        ensureSlot(index)
        val slot = singers[index]
        slot.imageUrl = url
        slot.imageFile = null
        slot.role = RoleModel(id = -1L)
        slot.isImageFailed = false
        slot.isHasSensitive = false
        slot.errorMessage = ""
        slot.errMsgToast = ""
        updateCreateBtn()
        notifySingers()
    }

    fun selectRole(item: RoleModel, index: Int = 0) {
        if (isInactive() || index < 0 || index >= maxSingerCount) return
        ensureSlot(index)
        val slot = singers[index]
        slot.role = item
        slot.imageUrl = ""
        slot.imageFile = null
        slot.isImageFailed = false
        slot.isHasSensitive = false
        slot.errorMessage = ""
        slot.errMsgToast = ""
        updateCreateBtn()
        notifySingers()
    }

    /** 清除指定槽歌手参考图，后续槽前移补位。 */
    fun clearSingerImage(index: Int = 0) {
        if (isInactive()) return
        val next = MvSingerSlots.afterClear(singers, index)
        singers.clear()
        singers.addAll(next)
        updateCreateBtn()
        notifySingers()
    }

    private suspend fun openRoleLibrary(index: Int) {
        if (index < 0 || index >= maxSingerCount) return
        if (!ensureLogin() || isInactive()) return
        ensureSlot(index)
        val result = pushAwait(Routes.fourView, JSONObject().put("imageUrl", singers[index].role.roleImg))
        if (isInactive()) return
        val roleJson = result?.optJSONObject("role")
        if (roleJson != null) {
            selectRole(RoleModel.fromJson(roleJson), index)
        } else {
            // 未选角色则丢掉占位空槽，已有图的槽由 afterAbortedPick 保留
            discardEmptySlot(index)
        }
    }

    // ------------------------------------------------------------------ 描述

    fun clearDescription() {
        if (isInactive() || isTextOutputting) return
        // Ultra 关闭模板时只清当前描述，保留高级版 template；高级版清空才连带清模板
        if (showStyleTemplate) template = MvTemplateEntity()
        setDescription("")
    }

    /** 打开风格模板选择页，确认后回填描述与模板字段。 */
    fun openStyleTemplate() {
        if (!showStyleTemplate) return
        CommonUtils.hideKeyboard()
        pager.launch {
            // 暂停创作页音乐预览，避免与模板页音频资源冲突。
            PlayerService.pause()
            if (isInactive()) return@launch
            val args = JSONObject()
            if (template.id > 0) args.put("initialSelectedId", template.id)
            val result = pushAwait(Routes.mvStyleTemplateSelect, args) ?: return@launch
            if (isInactive()) return@launch
            val itemJson = result.optJSONObject("template") ?: return@launch
            val model = MusicStyleModel.fromJson(itemJson)
            val selected = MvTemplateMapper.toEntity(model.template)
            template = template.copy(
                id = model.id,
                templateType = selected.templateType,
                templateCover = model.imageUrl.ifEmpty { model.remark ?: "" },
                shotStyle = selected.shotStyle,
                cameraPace = selected.cameraPace,
                emotionalArc = selected.emotionalArc,
                emotion = selected.emotion,
                description = selected.description,
            )
            // 选中后回填输入框，用户可继续编辑/润色
            setDescription(selected.description)
        }
    }

    /** AI 润色 MV 描述。 */
    fun polishDescription() {
        pager.launch {
            if (!ensureLogin() || isInactive()) return@launch
            val revision = AuthSession.revision
            fun isCurrent() = !isInactive() && AuthSession.isCurrent(revision)
            CommonUtils.hideKeyboard()

            if (description.isEmpty()) {
                isStreamLoading = true
                isTextOutputting = true
                val result = MusicVideoGenApi.getRandomDescription()
                if (isInactive()) return@launch
                isStreamLoading = false
                isTextOutputting = false
                if (!isCurrent()) return@launch
                result.onSuccess { text -> if (text.isNotEmpty()) setDescription(text) }
                return@launch
            }

            if (isStreamLoading) return@launch
            val originalText = description
            isStreamLoading = true
            isTextOutputting = true
            var metaData = JSONObject()
            // meta 到达时会先清空描述；若之后一段内容都没收到就结束，要把原描述放回去
            var received = false
            // 重复润色时先取消上一次流，避免双回调写同一输入框
            polishStream?.close()
            polishStream = MusicVideoGenApi.polishDescriptionStream(
                description = originalText,
                expectedRevision = revision,
                onMeta = { meta ->
                    if (isCurrent()) {
                        setDescription("")
                        DevLog.logger("on start: $meta")
                        metaData = meta
                    }
                },
                onDelta = { text ->
                    if (isCurrent()) {
                        if (text.isNotEmpty()) received = true
                        if (isStreamLoading) isStreamLoading = false
                        setDescription(description + text)
                    }
                },
                onError = { msg ->
                    if (isCurrent()) {
                        DevLog.logger("on error: $msg $originalText")
                        isStreamLoading = false
                        isTextOutputting = false
                        // error 类型为配额/订阅限制，按 meta 区分会员与非会员提示
                        if (msg == "error") {
                            val subscribed = metaData.optBoolean("subscribed", false)
                            val subscribeHint = metaData.optString("subscribeHintZh", "")
                            val needSubscribe = metaData.optBoolean("needSubscribe", false)
                            if (subscribed) {
                                val dailyRemaining = metaData.optInt("dailyRemaining", 0)
                                if (dailyRemaining <= 0 && subscribeHint.isNotEmpty()) {
                                    pager.toast("insufficient_optimization_descriptions".tr)
                                }
                            } else {
                                val trialRemaining = metaData.optInt("trialRemaining", 0)
                                if (trialRemaining <= 0) {
                                    pager.toast("polish_need_subscribe".tr)
                                    if (needSubscribe) pager.nav.push(Routes.subscribe)
                                }
                            }
                        } else {
                            pager.toast("optimize_description_failed".tr)
                        }
                        setTimeout(pager.pagerId, 16) { setDescription(originalText) }
                    }
                },
                onDone = {
                    if (!isInactive()) {
                        isStreamLoading = false
                        isTextOutputting = false
                        if (!received && description.isEmpty()) setDescription(originalText)
                    }
                },
            )
        }
    }

    // ------------------------------------------------------------------ 提交

    /** 提交 MV 生成：积分校验 → 表单校验 → 内容审核 → 构建参数 → 创建任务。 */
    fun generateMv() {
        if (isInactive()) return
        UmengUtils.logEventButtonClick("mv_create", pageName = pageName)
        pager.launch { generateMvImpl() }
    }

    private suspend fun generateMvImpl() {
        if (!ensureLogin() || isInactive()) return
        val isTryFree = (UserStore.userInfo?.hasTried ?: 0) > 0
        // 有使用次数不用判断积分
        if (!isTryFree) {
            val userPoint = UserStore.userInfo?.point ?: 0
            val points = requiredPoints
            // 积分不足：已订阅走积分购买，未订阅走订阅页
            if (userPoint < points || userPoint <= 0) {
                if (UserStore.isSubscribed) {
                    UmengUtils.logEvent("credit_insufficient_popup")
                    pushAwait(Routes.creditsPurchase)
                } else {
                    pushAwait(Routes.subscribe)
                }
                return
            }
        }

        if (selectedSong.id == 0L && selectedSong.musicAudioUrl == null) {
            markInvalid(MvFormField.music, "please_select_song".tr)
            return
        }
        // 草稿值不能在当前音频完成加载和边界校验前直接提交。
        if (!isMusicCropReady) {
            markInvalid(MvFormField.music, "wait_music_finish".tr)
            return
        }
        if (subDuration < 30) {
            markInvalid(MvFormField.music, "music_duration_less_error".trParams("value" to "30"))
            return
        }
        val limited = limitedSingers
        // 上传未完成时 coverUrl 仍为空，必须先拦上传中，避免误提示未选图
        if (limited.any { it.isImageLoading }) {
            markInvalid(MvFormField.cover, "image_uploading".tr)
            return
        }
        if (limited.any { it.isHasSensitive }) {
            markInvalid(MvFormField.cover, "image_violation".tr)
            return
        }
        // 有本地预览但没有 URL：用户以为照片已带上，必须拦住并提示槽上的失败文案。
        val failedSlot = limited.firstOrNull { it.hasPreview && !it.isReady }
        if (failedSlot != null) {
            markInvalid(MvFormField.cover, failedSlot.errMsgToast.ifEmpty { failedSlot.errorMessage })
            return
        }
        val coverImages = MvSingerSlots.coverImages(limited)
        if (title.isEmpty()) {
            markInvalid(MvFormField.title, "please_enter_mv_title".tr)
            return
        }
        invalidField = null
        // 提交前再校验 Effects 权限（会员状态可能已变更）
        if (!isUltraMode && isEffectsEnabled && !canUseEffects && !UserStore.isSubscribed) {
            isEffectsEnabled = false
            if (persistPreferences) Sp.setBool(StorageKey.mvEffectsEnabled, false)
            pushAwait(Routes.subscribe)
            return
        }
        // 撤回后本机标记已清，提交前再问一次。取消不创建任务、不扣积分。
        val isAgree = UserStore.showAgree(StorageKey.isAgreeUpload, "upload", pager)
        if (isInactive() || !isAgree) return
        if (isCreateLoading) return
        isCreateLoading = true
        pager.showLoading()

        for (slot in limited.filter { it.fourViewId > 0 }) {
            val violation = MusicVideoGenApi.checkImageV2(slot.coverUrl, 1)
            if (isInactive()) {
                pager.hideLoading()
                return
            }
            if (violation.isNotEmpty()) {
                markInvalid(MvFormField.cover, "image_violation".tr)
                isCreateLoading = false
                pager.hideLoading()
                return
            }
        }

        // 选模板已回填输入框，审核与提交都用当前文案
        val checks = ArrayList<Pair<MvFormField, String>>()
        checks.add(MvFormField.title to title)
        if (author.isNotEmpty()) checks.add(MvFormField.author to author)
        if (description.isNotEmpty()) checks.add(MvFormField.description to description)
        for ((field, text) in checks) {
            val check = MusicVideoGenApi.checkText(text)
            if (isInactive()) {
                pager.hideLoading()
                return
            }
            val violation = check.getOrNull() ?: ""
            if (violation.isNotEmpty()) {
                isCreateLoading = false
                pager.hideLoading()
                markInvalid(field, violation)
                return
            }
        }

        // 选没选模板都只传输入框文案。远端要求描述时，空文案同样拦截。
        if (ConfigService.mvDescIsNeed && description.isEmpty()) {
            isCreateLoading = false
            pager.hideLoading()
            markInvalid(MvFormField.description, "please_enter_mv_description".tr)
            return
        }
        val customStoryPlot = description

        // 本地上传歌曲用 audioUrl，曲库歌曲用 musicId
        val isLocalSong = selectedSong.id == 0L
        val audioUrl = if (isLocalSong) selectedSong.musicAudioUrl ?: "" else ""
        val musicId = if (isLocalSong) "" else "${selectedSong.id}"
        if (!isLocalSong) taskType = 0

        // 后端以 UI 裁剪器回写的起止区间为准，subDuration 仅用于前端最短时长校验。
        val params = VideoGenParams.forMv(
            title = title,
            artist = author,
            musicId = musicId,
            audioUrl = audioUrl,
            taskType = taskType,
            coverImages = coverImages,
            // 没选照片仍按 1 位主角提交；有就绪照片时等于就绪张数。
            characterCount = if (coverImages.isEmpty()) "1" else MvSingerSlots.characterCount(limited),
            subDurationScope = subDurationScope,
            imageComp = videoAspectRatio,
            quality = quality.toIntOrNull() ?: 480,
            mvVersion = mvVersion.toIntOrNull() ?: 0,
            isWatermark = VideoGenParams.keptWatermark,
            isLyric = VideoGenParams.isLyricFromSubtitlesEnabled(isSubtitles),
            capcutEnabled = !isUltraMode && isEffectsEnabled,
            customStoryPlot = customStoryPlot,
            storyRequest = "",
        )

        val result = MusicVideoGenApi.createMvTask(params)
        if (isInactive()) {
            pager.hideLoading()
            return
        }
        when (result) {
            is ApiResult.Success -> {
                UmengUtils.logEvent("generate_mv")
                // 首次免费特效已随本次创建消耗：立刻关开关并落盘，避免 getUserInfo 延迟期间再次进页仍默认开启
                if (isEffectsEnabled && (UserStore.userInfo?.isUseCapcut ?: 1) == 0) {
                    isEffectsEnabled = false
                    if (persistPreferences) Sp.setBool(StorageKey.mvEffectsEnabled, false)
                }
                // 成功不清草稿，页面销毁时写回后下次进入可继续用同一套参数
                isCreateLoading = false
                pager.hideLoading()
                pager.toast("generate_success".tr)
                openCreateResult(result.data.id)
            }
            is ApiResult.Failure -> {
                isCreateLoading = false
                pager.hideLoading()
                AppHost.launch { UserStore.getUserInfo() }
            }
        }
    }

    /**
     * 创作成功（MainController.openCreateResult）：主框架切到作品库，再用思考流页面替换当前创作页；
     * 800ms 后刷新用户、最近角色和作品库（跑在根页面上，创作页已关闭）。
     */
    private fun openCreateResult(id: Long) {
        pager.emit(Events.SWITCH_TAB, JSONObject().put("tab", 4))
        pager.nav.replace(Routes.thinkLineMv, JSONObject().put("id", id).put("type", "create"))
        AppHost.run {
            setTimeout(AppHost.pager.pagerId, 800) {
                AppHost.launch {
                    UserStore.getUserInfo()
                    MvService.getRecentRoleList()
                    LibraryTabRefreshService.refreshMv(isCreate = true)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 草稿

    /** 有内容时返回可写入草稿的快照，空表单返回 null。 */
    fun draftSnapshot(): JSONObject? {
        val hasContent = hasSong() || description.isNotEmpty() || title.isNotEmpty() || author.isNotEmpty() ||
            template.id > 0 || singers.any { it.imageUrl.isNotEmpty() || it.role.id > 0 }
        if (!hasContent) return null
        val singersArr = JSONArray()
        singers.filter { it.isReady }.forEach { singersArr.put(it.toDraftJson()) }
        return JSONObject()
            .put("description", description)
            .put("title", title)
            .put("author", author)
            .put("selectedSong", selectedSong.toJson())
            .put("subDurationScope", subDurationScope)
            .put("subDuration", subDuration)
            .put("quality", quality)
            .put("mvVersion", mvVersion)
            .put("taskType", taskType)
            .put("singers", singersArr)
            .put("template", if (template.id > 0) template.toJson() else null)
    }

    /** 把草稿字段写回当前表单。 */
    fun restoreFields(draft: JSONObject) {
        if (isInactive()) return
        setDescription(draft.jStr("description"))
        title = draft.jStr("title")
        author = draft.jStr("author")
        val songJson = draft.jObj("selectedSong")
        if (songJson != null) {
            selectedSong = SongModel.fromJson(songJson)
            isShowCropper = hasSong()
        }
        // 上传任务以服务内存为准
        syncPendingUploadAfterSheet()
        subDurationScope = draft.jStr("subDurationScope")
        subDuration = draft.jInt("subDuration", 0)
        quality = draft.jStr("quality", quality)
        mvVersion = draft.jStr("mvVersion", mvVersion)
        taskType = draft.jInt("taskType", taskType)
        singers.clear()
        singers.addAll(MvSingerSlots.truncate(MvSingerSlots.fromDraft(draft), maxSingerCount))
        draft.jObj("template")?.let { template = MvTemplateEntity.fromDraftJson(it) }
        // 旧草稿可能只把描述存在 template，输入框为空时回填
        if (description.isEmpty() && template.description.isNotEmpty()) setDescription(template.description)
        updateCreateBtn()
        notifySingers()
    }

    // ------------------------------------------------------------------ 活动预设

    /** 活动配置里的远程音频。id=0 走 audioUrl 提交，用户仍可换歌。[audioName] 只填歌曲名，不写入 MV 标题。 */
    fun applyPresetAudio(url: String, durationSeconds: Int, audioName: String = "") {
        if (isInactive()) return
        val trimmed = url.trim()
        if (!trimmed.startsWith("http")) return
        resetMusicCropState()
        selectedSong = SongModel(id = 0L, musicAudioUrl = trimmed, musicTitle = audioName.trim())
        if (durationSeconds > 0) musicDuration = durationSeconds
        isShowCropper = true
        mvUploadItem = null
        updateCreateBtn()
    }

    /** 按顺序填角色图。没有合法地址则保持空槽，让用户自己传。 */
    fun applyPresetRoleImages(urls: List<String>) {
        if (isInactive()) return
        val ready = ArrayList<MvSingerSlot>()
        for (raw in urls) {
            val url = raw.trim()
            if (!url.startsWith("http")) continue
            ready.add(MvSingerSlot(imageUrl = url))
            if (ready.size >= maxSingerCount) break
        }
        if (ready.isEmpty()) return
        singers.clear()
        singers.addAll(ready)
        updateCreateBtn()
        notifySingers()
    }

    fun applyPresetDescription(text: String) {
        if (isInactive()) return
        val value = text.trim()
        if (value.isEmpty()) return
        setDescription(value)
    }

    fun applyPresetTitle(text: String) {
        if (isInactive()) return
        val value = text.trim()
        if (value.isEmpty()) return
        title = value
    }

    /** 只认 9:16 / 16:9。没值或匹配不上用 9:16，不写本地。 */
    fun applyPresetAspectRatio(raw: String?) {
        if (isInactive()) return
        val value = raw?.trim() ?: ""
        videoAspectRatio = if (value == "9:16" || value == "16:9") value else "9:16"
    }

    /** 只认 480 / 720。720 需已订阅，否则以及匹配不上都用 480。 */
    fun applyPresetQuality(raw: String?) {
        if (isInactive()) return
        val value = raw?.trim() ?: ""
        quality = if (value == "720" && UserStore.isSubscribed) "720" else "480"
        updateCreateBtn()
    }

    /** 只认 true / false。和当前值不同且未订阅时保持现有字幕状态。 */
    fun applyPresetSubtitles(raw: String?) {
        if (isInactive()) return
        val value = raw?.trim()?.lowercase() ?: ""
        if (value != "true" && value != "false") return
        val enabled = value == "true"
        if (enabled != isSubtitles && !UserStore.isSubscribed) return
        isSubtitles = enabled
    }

    companion object {
        const val titleMaxLength = 30
        const val authorMaxLength = 30
        const val descriptionMaxLength = 2000

        /** 日后要对 Ultra 开放模板时改为 true。 */
        const val ultraStyleTemplateEnabled = false
    }
}
