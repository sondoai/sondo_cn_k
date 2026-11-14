package com.sondo.app.service

import com.sondo.app.base.AppEnv
import com.sondo.app.core.utils.onHost
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.storage.Sp
import com.sondo.app.core.utils.AigcMediaExport
import com.sondo.app.core.utils.CdnDownload
import com.sondo.app.core.utils.CommonUtils
import com.sondo.app.core.utils.ConfirmDialogLite
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.EmailHelper
import com.sondo.app.core.utils.HostBinding
import com.sondo.app.core.utils.nowMs
import com.sondo.app.data.models.MusicStyleModel
import com.sondo.app.data.models.MvModel
import com.sondo.app.data.models.MvStatus
import com.sondo.app.data.models.MvTagLabelModel
import com.sondo.app.data.models.RoleModel
import com.sondo.app.data.models.ShotImageModel
import com.sondo.app.data.models.SquareModel
import com.sondo.app.data.models.ThinkLineMvDisplayCache
import com.sondo.app.data.models.VideoPublishStatus
import com.sondo.app.data.models.jIntOrNull
import com.sondo.app.data.models.jStrOrNull
import com.sondo.app.nativeapi.SDAppModule
import com.sondo.app.nativeapi.nativeModule
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.ui.overlay.DialogHandle
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.random.Random

/** MV 列表项的操作类型（对应 Flutter 侧 MvItemType）。 */
enum class MvItemType {
    // 鸿蒙动态库会把公开枚举导出成 C/C++ 接口，不能用 C++ 关键字 delete 作名字
    deleteItem,
    edit,
    share,
    like,
    more,
    detail,
    thinkLine,
    recompose,
    feedback,

    /** 进入试用 MV 生成等待页。 */
    trialWaiting,

    /** 高潮试看已合成，打开解锁弹窗。 */
    previewUnlock,
}

class MvIdeasGrouped(val labels: List<MvTagLabelModel>, val map: Map<String, List<MusicStyleModel>>)

/** 相同 label 合成一类，空 label 或空 value 不进入目录。顺序跟接口返回一致。id 为 label 的 md5。 */
fun groupMvIdeas(items: List<MusicStyleModel>): MvIdeasGrouped {
    val labels = ArrayList<MvTagLabelModel>()
    val map = LinkedHashMap<String, ArrayList<MusicStyleModel>>()
    for (element in items) {
        if (element.label.isEmpty() || element.value.isEmpty()) continue
        val id = Svc.md5(element.label)
        map.getOrPut(id) { ArrayList() }.add(element)
        if (labels.none { it.id == id }) labels.add(MvTagLabelModel(id = id, label = element.label))
    }
    return MvIdeasGrouped(labels, map)
}

/** 试用 MV 与作品库列表项状态同步工具。 */
object TrialMvListSync {
    /** 是否应纳入作品库通用 MV 轮询（试用 MV 走 TryFreeTaskService）。 */
    fun shouldPollMvItem(item: MvModel): Boolean = !item.trialMvFlag

    /** TrialMvStatus → 列表展示用 MvStatus。 */
    fun mapTrialStatus(status: TrialMvStatus): MvStatus = when (status) {
        TrialMvStatus.completed -> MvStatus.success
        TrialMvStatus.retryableFailed -> MvStatus.failed
        TrialMvStatus.generating -> MvStatus.generating
    }

    /** 将 TryFreeTaskService 状态合并到列表项（空值不覆盖；resultUrl 仅成功态写入）。 */
    fun applyTrialServiceState(
        item: MvModel,
        status: TrialMvStatus,
        trialStage: Int,
        coverImage: String? = null,
        firstFrameImg: String? = null,
        resultUrl: String? = null,
        fourViewImg: String? = null,
    ): MvModel {
        val mappedStatus = mapTrialStatus(status)
        val hasCover = !coverImage.isNullOrEmpty()
        val hasFirstFrame = !firstFrameImg.isNullOrEmpty()
        val hasResult = mappedStatus == MvStatus.success && !resultUrl.isNullOrEmpty()
        val hasFourView = !fourViewImg.isNullOrEmpty()
        return item.copy(
            status = mappedStatus,
            trialMvFlag = true,
            trialStage = trialStage,
            resultUrl = if (hasResult) resultUrl else item.resultUrl,
            coverImage = if (hasCover) coverImage else item.coverImage,
            firstFrameImg = if (hasFirstFrame) firstFrameImg else item.firstFrameImg,
            fourViewImg = if (hasFourView) fourViewImg!! else item.fourViewImg,
        )
    }
}

/**
 * MV 字典 / 模板 / 最近角色缓存 + 列表项操作分发 + 生成进度轮询。
 *
 * 1. 缓存与操作分发对应 Flutter 侧 MvService。缓存是普通字段，刷新后广播 [Events.MV_DICT_CHANGED] {kind}。
 * 2. 轮询对应 Flutter 侧 LibraryMvController 的轮询部分（移到服务里以便跨页面存活）：
 *    作品库「我的 MV」每次加载后调用 [afterMv]；生成中 / 排队中的条目每 12 秒查一次，
 *    最近 6 小时内失败的条目每 60 秒复查（最多 60 多次，服务端可能自动重试）；
 *    有变化时广播 [Events.MV_TASK_UPDATED] {items: [补丁]}，页面用 [applyTaskPatch] 写回列表项。
 *    与参考工程一致，MV 轮询在后台不暂停。
 * 3. 变更扇出：[refreshMv] 广播 [Events.MV_LIST_CHANGED] {type, item}。
 */
object MvService {
    private const val TAG = "MvService"

    private const val DOWNLOADING_KEY = "mv_download"
    private val busy = BusyKeys()
    private val downloading = BusyKeys { notifyBusy() }
    private val sharing = BusyKeys { notifyBusy() }

    /** 下载进行中的视频地址（条目级转圈）。变化时广播 [Events.MV_BUSY_CHANGED]。 */
    val downloadingUrls: Set<String> get() = downloading.keys

    /** 分享进行中的视频地址。 */
    val sharingUrls: Set<String> get() = sharing.keys

    /** 分镜参考图 Map，key = shotId 字符串。 */
    val shotImageMap = LinkedHashMap<String, ShotImageModel>()

    var isLoadingMvTags = false
        private set

    /** mv 标签 Map：分类 id（label 的 md5）→ 标签列表。 */
    val mvTagMap = LinkedHashMap<String, MutableList<MusicStyleModel>>()

    /** mv 标签分类列表。 */
    val mvTagList = ArrayList<MvTagLabelModel>()
    val mvShotTagNames = ArrayList<String>()

    var isLoadingMvIdeas = false
        private set

    /** @ Ideas 分类。id 为 label 的 md5，和分镜标签分开存。 */
    val mvIdeas = ArrayList<MvTagLabelModel>()
    val mvIdeasMap = LinkedHashMap<String, List<MusicStyleModel>>()
    private var mvIdeasSerial = 0

    /** MV 风格模板。 */
    val mvTemplateList = ArrayList<MusicStyleModel>()
    var isLoadingMvTemplate = false
        private set

    /** 最近使用的四视图角色。 */
    val roleList = ArrayList<RoleModel>()

    private val thinkLineMvDisplayCaches = HashMap<Long, ThinkLineMvDisplayCache>()

    /** 「更多」面板里的 MV。变化时广播 [Events.MV_CURRENT_CHANGED]。 */
    var currentMv: MvModel = MvModel(id = 0L, userId = 0L, title = "", artist = "", audioUrl = "", status = MvStatus.queue, subDurationScope = "")
        private set

    private var inited = false
    private val hostBinding = HostBinding()
    private var actionSheet: DialogHandle? = null

    // 详情页播放列表太大不适合走页面参数，暂存在这里按 key 取用
    private val detailLists = HashMap<String, List<SquareModel>>()
    private var detailListSeq = 0

    fun getThinkLineMvDisplayCache(taskId: Long): ThinkLineMvDisplayCache? {
        if (taskId <= 0L) return null
        return thinkLineMvDisplayCaches[taskId]
    }

    fun saveThinkLineMvDisplayCache(taskId: Long, cache: ThinkLineMvDisplayCache) {
        if (taskId <= 0L) return
        thinkLineMvDisplayCaches[taskId] = cache
    }

    fun removeThinkLineMvDisplayCache(taskId: Long) {
        if (taskId <= 0L) return
        thinkLineMvDisplayCaches.remove(taskId)
    }

    /** 启动时拉最近使用的四视图角色与模板数据（不等待）；登录后补拉角色，登出清空。 */
    fun init() {
        Svc.ensureBound()
        if (hostBinding.needsBind()) {
            // 旧宿主上没跑完的请求不会再回来，加载标记不能卡住
            isLoadingMvTemplate = false
            isLoadingMvTags = false
            isLoadingMvIdeas = false
        }
        Svc.launch { getRecentRoleList() }
        Svc.launch { getMvTemplateList() }
        if (inited) return
        inited = true
        Svc.onLogout {
            // 清除列表轮询以及角色列表
            cancelMvData()
            if (roleList.isNotEmpty()) {
                roleList.clear()
                notifyDict("roles")
            }
        }
        Svc.onLogin {
            // 角色列表为空则获取最近使用角色列表
            if (roleList.isEmpty()) Svc.launch { getRecentRoleList() }
        }
    }

    private fun notifyDict(kind: String) = Svc.emit(Events.MV_DICT_CHANGED, JSONObject().put("kind", kind))

    suspend fun getRecentRoleList() = onHost { getRecentRoleListImpl() }

    private suspend fun getRecentRoleListImpl() {
        val res = WorksApi.fetchRecentRoles()
        if (res.isNotEmpty()) {
            roleList.clear()
            roleList.addAll(res)
            notifyDict("roles")
        }
    }

    /**
     * 获取 MV 模板风格列表（字典 `music_mv_style_v2`）：为空时每 1 秒重试，最多 3 次；
     * 已有数据且非 [reload] 时直接返回。只保留 value 是合法 JSON、label 以 http 开头（预览地址）、remark 非空的条目。
     */
    suspend fun getMvTemplateList(reload: Boolean = false) = onHost { getMvTemplateListImpl(reload) }

    private suspend fun getMvTemplateListImpl(reload: Boolean) {
        if (isLoadingMvTemplate) return
        if (!reload && mvTemplateList.isNotEmpty()) return
        if (reload) mvTemplateList.clear()
        isLoadingMvTemplate = true
        notifyDict("template")
        var res = WorksApi.getDictByLanguage("music_mv_style_v2")
        var retryCount = 0
        while (res.isEmpty() && retryCount < 3) {
            retryCount++
            Svc.sleep(1000)
            res = WorksApi.getDictByLanguage("music_mv_style_v2")
        }
        isLoadingMvTemplate = false
        if (res.isNotEmpty()) {
            val filtered = res.filter { e ->
                isValidJson(e.value) && e.label.isNotEmpty() && e.label.startsWith("http") && !e.remark.isNullOrEmpty()
            }
            mvTemplateList.clear()
            mvTemplateList.addAll(filtered)
        }
        notifyDict("template")
    }

    private fun isValidJson(text: String): Boolean {
        val t = text.trim()
        return try {
            when {
                t.startsWith("{") -> {
                    JSONObject(t)
                    true
                }
                t.startsWith("[") -> {
                    JSONArray(t)
                    true
                }
                // jsonDecode 也接受数字 / 布尔 / null / 带引号的字符串这类标量
                t == "true" || t == "false" || t == "null" -> true
                t.length >= 2 && t.startsWith("\"") && t.endsWith("\"") -> true
                else -> t.toDoubleOrNull() != null
            }
        } catch (e: Throwable) {
            false
        }
    }

    /** 各分镜的历史参考图 → [shotImageMap]（shotId → coverImage）。 */
    suspend fun getMvShotRefImg(mvId: Long): Map<String, ShotImageModel> = onHost { getMvShotRefImgImpl(mvId) }

    private suspend fun getMvShotRefImgImpl(mvId: Long): Map<String, ShotImageModel> {
        shotImageMap.clear()
        val result = WorksApi.fetchShotRefImgs(mvId)
        for (element in result) {
            shotImageMap["${element.shotId}"] = ShotImageModel(imageUrl = element.coverImage)
        }
        notifyDict("shotImages")
        return shotImageMap
    }

    /** 分镜标签（字典 `music_mv_tags`）：为空时每 1 秒重试，最多 3 次；按 label 的 md5 分组。 */
    suspend fun getMvTagsList() = onHost { getMvTagsListImpl() }

    private suspend fun getMvTagsListImpl() {
        if (isLoadingMvTags) return
        isLoadingMvTags = true
        var res = WorksApi.getDictByType("music_mv_tags")
        mvShotTagNames.clear()
        mvTagList.clear()
        mvTagMap.clear()
        var retryCount = 0
        while (res.isEmpty() && retryCount < 3) {
            retryCount++
            Svc.sleep(1000)
            res = WorksApi.getDictByType("music_mv_tags")
        }
        for (element in res) {
            // label 转 md5 作为分类 id
            val md5Str = Svc.md5(element.label)
            mvTagMap.getOrPut(md5Str) { ArrayList() }.add(element)
            mvShotTagNames.add(element.value)
            if (mvTagList.none { it.id == md5Str }) mvTagList.add(MvTagLabelModel(id = md5Str, label = element.label))
        }
        isLoadingMvTags = false
        notifyDict("tags")
    }

    /** 多语言字典 `music_mv_idea_tags`。后发起的请求才写入，避免旧结果覆盖新结果。 */
    suspend fun getMvIdeas() = onHost { getMvIdeasImpl() }

    private suspend fun getMvIdeasImpl() {
        val serial = ++mvIdeasSerial
        isLoadingMvIdeas = true
        var res = WorksApi.getDictByLanguage("music_mv_idea_tags")
        var retryCount = 0
        while (res.isEmpty() && retryCount < 3 && serial == mvIdeasSerial) {
            retryCount++
            Svc.sleep(1000)
            if (serial != mvIdeasSerial) return
            res = WorksApi.getDictByLanguage("music_mv_idea_tags")
        }
        if (serial != mvIdeasSerial) return
        isLoadingMvIdeas = false
        // 失败或仍为空时保留上一次，避免正文里的 @名称 突然失去高亮
        if (res.isEmpty()) {
            notifyDict("ideas")
            return
        }
        val grouped = groupMvIdeas(res)
        if (grouped.labels.isEmpty()) {
            notifyDict("ideas")
            return
        }
        mvIdeas.clear()
        mvIdeas.addAll(grouped.labels)
        mvIdeasMap.clear()
        mvIdeasMap.putAll(grouped.map)
        notifyDict("ideas")
    }

    // ================================================================== 操作分发

    fun onTap(type: MvItemType, item: MvModel, mvList: List<MvModel> = emptyList()) {
        val ui = UiCtx.capture()
        when (type) {
            MvItemType.recompose -> ui.launch { recomposeMv(ui, item) }
            MvItemType.deleteItem -> deleteMv(ui, item)
            MvItemType.edit -> toEditMv(ui, item)
            MvItemType.more -> showMoreOptions(ui, item)
            MvItemType.detail -> toMvDetail(ui, item, mvList)
            MvItemType.thinkLine -> toThinkLine(ui, item)
            MvItemType.feedback -> ui.launch { onFeedback(item) }
            MvItemType.share -> ui.launch { onShareMv(item, item.resultUrl ?: "", isFullLoading = false) }
            MvItemType.like -> ui.launch { onCollectMv(ui, item) }
            MvItemType.trialWaiting -> toTrialGenWaiting(ui, item)
            MvItemType.previewUnlock -> {
                val hook = WorksUi.previewUnlock
                if (hook == null) {
                    DevLog.logger("$TAG: previewUnlock 未挂载")
                } else {
                    hook(item.id, item.continueFullMvNeedPoint) {
                        refreshMvStatus(item.id, MvStatus.generating, unlock = 1, forcePoll = true)
                    }
                }
            }
        }
    }

    private suspend fun onFeedback(item: MvModel) {
        val str = "${"video_id".tr}: ${item.id}\n"
        EmailHelper.sendEmail(content = str, userId = Account.userId)
    }

    /** 正在提交收藏请求的 MV：连点只算一次。 */
    private val likingMvs = HashSet<Long>()

    /** 本次会话里收藏操作后的最新状态（同 MusicService）：调用方条目可能是旧值，取反以这里为准。 */
    private val likeStates = HashMap<Long, Pair<Boolean, Int>>()

    /** 收藏：先乐观更新，失败回滚。 */
    private suspend fun onCollectMv(ui: UiCtx, item: MvModel) {
        if (!Account.ensureLogin(ui.pager)) return
        if (!likingMvs.add(item.id)) return
        try {
            val known = likeStates[item.id]
            val collect = known?.first ?: item.isCollect
            val count = known?.second ?: item.collectNum
            val before = item.copy(isCollect = collect, collectNum = count)
            val after = item.copy(
                isCollect = !collect,
                collectNum = if (collect) (count - 1).coerceAtLeast(0) else count + 1,
            )
            likeStates[item.id] = after.isCollect to after.collectNum
            refreshMv("like", after)
            val res = WorksApi.collectMv(item.id, if (collect) 0 else 1)
            if (res != 1) {
                likeStates[item.id] = before.isCollect to before.collectNum
                refreshMv("like", before)
            }
        } finally {
            likingMvs.remove(item.id)
        }
    }

    /** 跳转到思考线（MV 信息流）：args {id}。 */
    private fun toThinkLine(ui: UiCtx, item: MvModel) {
        if (item.trialMvFlag && item.status != MvStatus.success) {
            toTrialGenWaiting(ui, item)
            return
        }
        ui.push(Routes.thinkLineMv, JSONObject().put("id", item.id))
    }

    /** 跳转试用 MV 生成等待页。 */
    private fun toTrialGenWaiting(ui: UiCtx, item: MvModel) {
        val initialState = if (item.status == MvStatus.failed) "failed" else "loading"
        val stepIndex = TrialMvStage.fromValue(item.trialStage).waitingStepIndex
        ui.push(Routes.tryFreeGenWaiting, TryFreeTaskService.genWaitingArgs(item.id, initialState, stepIndex))
    }

    /**
     * 跳转到 MV 详情。路由规则：
     * - 试用 MV 未成功 → 试用等待页；
     * - 已成功但未解锁（unlock == 0）→ 试看页 mv_preview_play，args {id, item: MvModel JSON}；
     * - genMode 2：待确认 / 图片失败 / 视频失败 → 信息流 {id}；成功 → mv_list_detail
     *   {id, type: 'library', listKey, dataType: 0}（播放列表用 [takeDetailList] 按 listKey 取）；
     * - 旧数据：成功且有 http 成片 → mv_list_detail；成功无成片 → edit_video {id}。
     */
    private fun toMvDetail(ui: UiCtx, item: MvModel, mvList: List<MvModel>) {
        if (item.trialMvFlag && item.status != MvStatus.success) {
            toTrialGenWaiting(ui, item)
            return
        }
        if (item.unlock == 0 && item.status == MvStatus.success) {
            ui.push(Routes.mvPreviewPlay, JSONObject().put("id", item.id).put("item", item.toJson()))
            return
        }
        // 作品库打开浮窗同源完成态试用时关闭浮窗，与浮窗完成态点击一致
        if (item.trialMvFlag && item.status == MvStatus.success) {
            TryFreeTaskService.clearIfMatching(item.id)
        }
        val list = mvList.filter { it.status == MvStatus.success }.map { SquareModel.fromMv(it) }
        if (item.genMode == 2) {
            if (item.status == MvStatus.waitingConfirm ||
                item.status == MvStatus.imageFailed ||
                item.status == MvStatus.videoFailed
            ) {
                // 待确认、图片失败、视频失败都进 MV 信息流
                ui.push(Routes.thinkLineMv, JSONObject().put("id", item.id))
                return
            }
            if (item.status == MvStatus.success) {
                ui.push(Routes.mvListDetail, listDetailArgs(item.id, list))
                return
            }
        } else {
            // 第一个版本的数据（文生视频）
            if (item.status == MvStatus.success) {
                val resultUrl = item.resultUrl
                if (!resultUrl.isNullOrEmpty()) {
                    if (resultUrl.startsWith("http")) ui.push(Routes.mvListDetail, listDetailArgs(item.id, list))
                } else {
                    // 国内版已去掉旧编辑板，无成片时进整合编辑页。
                    ui.push(Routes.editVideo, JSONObject().put("id", item.id))
                }
            }
        }
    }

    private fun listDetailArgs(id: Long, list: List<SquareModel>): JSONObject {
        val key = "mv_detail_${++detailListSeq}"
        // 只保留最近一份，避免堆积
        detailLists.clear()
        detailLists[key] = list
        return JSONObject().put("id", id).put("type", "library").put("listKey", key).put("dataType", 0)
    }

    /** 详情页取走播放列表（取后即清）。key 来自页面参数 listKey；没有则返回空列表。 */
    fun takeDetailList(key: String): List<SquareModel> = detailLists.remove(key) ?: emptyList()

    /** 删除 MV：首次展示回收站保留说明。 */
    private fun deleteMv(ui: UiCtx, item: MvModel) {
        val pager = ui.pager ?: return
        val doDelete: () -> Unit = {
            ui.launch {
                ui.showLoading()
                val ok = WorksApi.deleteMv(item.id)
                if (ok) {
                    refreshMv("delete", item)
                    ui.closeLoading()
                    ui.toast("delete_success".tr)
                } else {
                    ui.closeLoading()
                    ui.toast("delete_failed".tr)
                }
            }
        }
        val isShow = Sp.getBool(StorageKey.isDeleteDialog, false)
        if (isShow) {
            ConfirmDialogLite.show(
                pager,
                title = "delete_notice".tr,
                content = "are_you_sure_you_want_to_delete".tr,
                onConfirm = doDelete,
            )
        } else {
            ConfirmDialogLite.show(
                pager,
                title = "delete_notice".tr,
                content = "trash_retention_notice".tr,
                confirmText = "move_to_trash".tr,
                onConfirm = {
                    Sp.setBool(StorageKey.isDeleteDialog, true)
                    doDelete()
                },
            )
        }
    }

    private fun toEditMv(ui: UiCtx, item: MvModel) {
        // 国内版没有旧版数据，编辑一律进整合页。
        ui.push(Routes.editVideo, JSONObject().put("id", item.id))
    }

    /**
     * 分享 MV 文件。产品开关 [AppConstants.enableMvDanceShare] 关闭时不响应。
     * @param isFullLoading true 用全屏 Loading；false 用 [sharingUrls] 做条目级转圈
     */
    suspend fun onShareMv(item: MvModel, url: String, isFullLoading: Boolean = true) {
        // 产品开关关闭时不走分享，避免漏网入口仍调起系统分享。
        if (!AppConstants.enableMvDanceShare) return
        val ui = UiCtx.capture()
        if (url.isEmpty()) {
            if (!isFullLoading) closeActionSheet()
            ui.toast("no_video_to_share".tr)
            return
        }
        if (!isFullLoading && sharing.contains(url)) return
        if (!isFullLoading) {
            sharing.add(url, ui)
            notifyBusy()
        }
        var handedOff = false
        try {
            val isAgree = Account.showAgree(StorageKey.isAgreeShare, Account.USAGE_SHARE, ui.pager)
            if (!isAgree) return
            handedOff = true
            // 下载 + 分享在宿主上跑完（参考工程在 GetxService 里）：发起页中途关闭也要清掉占位
            onHost { shareMvOnHost(ui, item, url, isFullLoading) }
        } finally {
            if (!isFullLoading && !handedOff) {
                sharing.remove(url)
                notifyBusy()
            }
        }
    }

    private suspend fun shareMvOnHost(ui: UiCtx, item: MvModel, url: String, isFullLoading: Boolean) {
        if (!isFullLoading) sharing.claim(url)
        try {
            if (isFullLoading) ui.showLoading()
            var videoPath = ""
            if (url.startsWith("http")) {
                videoPath = CdnDownload.downloadWithCache(url, "mp4")
            } else if (CdnDownload.isValidCachedMediaFile(url)) {
                videoPath = url
            }
            if (videoPath.isEmpty()) {
                ui.toast("no_video_to_share".tr)
                if (isFullLoading) ui.closeLoading()
                return
            }
            // 分享和下载都不烧「AI生成」，直接用已下载的原文件。
            val marked = AigcMediaExport.prepareVideo(localPath = videoPath, source = url, contentId = "${item.id}")
            if (marked == null) {
                ui.toast("aigc_export_failed".tr)
                if (isFullLoading) ui.closeLoading()
                return
            }
            // 分享面板要由当前页面弹出（Android 上 activity-result 只投给前台页面）
            val result = ui.native({ JSONObject() }) {
                nativeModule<SDAppModule>(SDAppModule.MODULE_NAME).share(files = listOf(marked))
            }
            if (isFullLoading) ui.closeLoading()
            val status = result.optString("status", "")
            if (status == "success") {
                ui.toast("share_success".tr)
                UmengUtils.logEvent("share", mapOf("workType" to "mv"))
                Svc.launch { WorksApi.addMvRepostCount(item.id) }
                refreshMv("share", item.copy(repostCount = item.repostCount + 1))
            }
            // 鸿蒙分享面板不回报是否真的分享，关闭一律是 dismissed，不能当失败提示
            if (status == "dismissed" && !AppEnv.isOhos) ui.toast("share_failed".tr)
        } catch (e: Throwable) {
            if (isFullLoading) ui.closeLoading()
            ui.toast("share_failed".tr)
        } finally {
            if (!isFullLoading) {
                sharing.remove(url)
                notifyBusy()
            }
        }
    }

    /**
     * 下载 MV 到相册：下载声明 → 仅会员（否则去订阅页）→ 带缓存下载 → AIGC 导出步骤 → 写相册。
     * 同一时间只允许一个下载。
     */
    suspend fun downloadMv(url: String, isFullLoading: Boolean = true, contentId: String? = null) {
        if (busy.contains(DOWNLOADING_KEY)) return
        val ui = UiCtx.capture()
        val isAgree = Account.showAgree(StorageKey.isAgreeDown, Account.USAGE_DOWNLOAD, ui.pager)
        if (!isAgree) return
        // 判断是否是会员
        if (!Account.isSubscribed) {
            ui.push(Routes.subscribe, null)
            return
        }
        if (url.isEmpty()) {
            if (!isFullLoading) closeActionSheet()
            ui.toast("no_video_to_download".tr)
            return
        }
        busy.add(DOWNLOADING_KEY, ui)
        if (!isFullLoading) {
            downloading.add(url, ui)
            notifyBusy()
        }
        // 下载 → 导出 → 写相册在宿主上跑完（参考工程在 GetxService 里）：发起页中途关闭也要写完、提示并清掉占位
        onHost { downloadMvOnHost(ui, url, isFullLoading, contentId) }
    }

    private suspend fun downloadMvOnHost(ui: UiCtx, url: String, isFullLoading: Boolean, contentId: String?) {
        busy.claim(DOWNLOADING_KEY)
        if (!isFullLoading) downloading.claim(url)
        try {
            if (isFullLoading) ui.showLoading()
            val videoPath = CdnDownload.downloadWithCache(url, "mp4")
            val marked = AigcMediaExport.prepareVideo(localPath = videoPath, source = url, contentId = contentId)
            if (marked == null) {
                ui.toast("aigc_export_failed".tr)
                if (isFullLoading) ui.closeLoading()
                return
            }
            // 写相册的权限说明和系统授权框要弹在发起页上；发起页已关时在宿主上写
            val result = ui.native({ false }) {
                CommonUtils.saveVideoToGallery(marked, albumName = "video_${Random.nextInt(1000000)}.mp4")
            }
            if (result) {
                UmengUtils.logEvent("download", mapOf("workType" to "mv"))
                ui.toast("download_success".tr)
            } else {
                ui.toast("download_failed".tr)
            }
            if (isFullLoading) ui.closeLoading()
        } catch (e: Throwable) {
            if (isFullLoading) ui.closeLoading()
            ui.toast("download_failed".tr)
        } finally {
            busy.remove(DOWNLOADING_KEY)
            if (!isFullLoading) {
                downloading.remove(url)
                notifyBusy()
            }
        }
    }

    /** 重新合成 MV。 */
    private suspend fun recomposeMv(ui: UiCtx, item: MvModel) {
        ui.showLoading()
        val ok = WorksApi.mergeMvShot(item.id, item.genMode, item.isUpdt ?: 0)
        if (ok) {
            ui.toast("please_wait_for_the_video_to_be_merged".tr)
            refreshMvStatus(item.id, MvStatus.generating)
        } else {
            ui.toast("merge_failed".tr)
        }
        ui.closeLoading()
    }

    /** 发布 / 取消发布。产品开关 [AppConstants.enableWorkPublish] 关闭时不响应；首次发布需确认授权。 */
    private fun onPublishMv(ui: UiCtx, item: MvModel, value: Boolean) {
        // 产品开关关闭时不走发布，避免漏网入口仍调发布接口。
        if (!AppConstants.enableWorkPublish) return
        val pager = ui.pager ?: return
        val isAgreePublish = Sp.getBool(StorageKey.isAgreePublish, false)
        if (value && !isAgreePublish) {
            ConfirmDialogLite.show(
                pager,
                title = "publish_confirmation_title".tr,
                content = "publish_confirmation_content".tr,
                onConfirm = {
                    Sp.setBool(StorageKey.isAgreePublish, true)
                    ui.launch { onPublishMvApi(ui, item, value) }
                },
            )
        } else {
            ui.launch { onPublishMvApi(ui, item, value) }
        }
    }

    private suspend fun onPublishMvApi(ui: UiCtx, item: MvModel, value: Boolean) {
        ui.showLoading()
        val res = WorksApi.publishMvOperation(item.id, if (value) 1 else 0)
        if (res != -1) {
            val newItem = item.copy(mvPublish = VideoPublishStatus.fromValue(res))
            setCurrentMv(newItem)
            refreshMv("publish", newItem)
            UmengUtils.logEvent("publish", mapOf("workType" to "mv"))
        } else {
            ui.toast("publish_failed".tr)
        }
        ui.closeLoading()
    }

    private fun setCurrentMv(item: MvModel) {
        currentMv = item
        Svc.emit(Events.MV_CURRENT_CHANGED, item.toJson())
    }

    /** 显示更多选项（分享 / 发布 / 删除 / 下载）。 */
    private fun showMoreOptions(ui: UiCtx, item: MvModel) {
        val pager = ui.pager ?: return
        setCurrentMv(item)
        val sheet = WorksUi.mvActionsSheet
        if (sheet == null) {
            DevLog.logger("$TAG: mvActionsSheet 未挂载")
            return
        }
        actionSheet = sheet(
            pager,
            MvActionsRequest(
                item = item,
                type = "mv",
                url = item.resultUrl ?: "",
                onShare = { ui.launch { onShareMv(item, item.resultUrl ?: "", isFullLoading = false) } },
                onPublish = { value -> onPublishMv(ui, item, value) },
                onDelete = {
                    closeActionSheet()
                    deleteMv(ui, item)
                },
                onDownload = {
                    ui.launch { downloadMv(item.resultUrl ?: "", isFullLoading = false, contentId = "${item.id}") }
                },
            ),
        )
    }

    private fun closeActionSheet() {
        actionSheet?.dismiss()
        actionSheet = null
    }

    /** 举报：登录 → 选择原因 → 邮件。 */
    fun reportMv(item: MvModel) {
        val ui = UiCtx.capture()
        ui.launch {
            if (!Account.ensureLogin(ui.pager)) return@launch
            val pager = ui.pager ?: return@launch
            val sheet = WorksUi.reportOptionsSheet
            if (sheet == null) {
                DevLog.logger("$TAG: reportOptionsSheet 未挂载")
                return@launch
            }
            sheet(pager) { value ->
                val content = listOf(
                    "${"video_id".tr}: ${item.id}",
                    "${"report_reason".tr}: $value",
                    "report_email_hint".tr,
                )
                ui.launch { EmailHelper.sendEmail(content = content.joinToString("\n"), userId = Account.userId) }
            }
        }
    }

    /** 回到主框架并打开 MV 创作页。有非空白剧情时带进创作页描述框。 */
    fun toCreateMv(customStoryPlot: String? = null) {
        val plot = customStoryPlot?.trim() ?: ""
        val args = if (plot.isEmpty()) null else JSONObject().put("customStoryPlot", plot)
        if (!AppNav.isOnRoot()) {
            AppNav.popToRoot()
            // 等上层页面关闭后再打开创作页
            Svc.setTimeout(350) { WorksUi.openCreatePage(Routes.musicVideoGen, args) }
        } else {
            WorksUi.openCreatePage(Routes.musicVideoGen, args)
        }
    }

    /** 解析 `mm:ss,mm:ss` → 总时长（秒，结束 − 开始）；格式不对返回 0。 */
    fun getMvDuration(subDurationScope: String): Int {
        if (subDurationScope.isEmpty() || !subDurationScope.contains(",")) return 0
        return try {
            val parts = subDurationScope.split(",")
            val start = parts[0].split(":")
            val end = parts[1].split(":")
            val startSeconds = start[0].trim().toInt() * 60 + start[1].trim().toInt()
            val endSeconds = end[0].trim().toInt() * 60 + end[1].trim().toInt()
            endSeconds - startSeconds
        } catch (e: Throwable) {
            0
        }
    }

    /**
     * MV 变更扇出：广播 [Events.MV_LIST_CHANGED]，data = {type, item}，
     * type 为 like | share | play | delete | publish，item 为变更后的 MvModel JSON。
     * 个人主页、广场列表、作品库、广场播放页、首页各自按 type 回写。
     */
    fun refreshMv(type: String, item: MvModel) {
        if (type == "delete") myMvItems.remove(item.id)
        Svc.emit(Events.MV_LIST_CHANGED, JSONObject().put("type", type).put("item", item.toJson()))
    }

    /**
     * 更新作品库里一条 MV 的状态（重新合成、试看解锁后等），必要时把它拉进生成轮询。
     * 页面收到 [Events.MV_TASK_UPDATED] 的补丁后写回。
     * @param forcePoll 试用 MV 默认不进通用轮询；高潮试看解锁后必须进入，传 true
     */
    fun refreshMvStatus(
        id: Long,
        status: MvStatus,
        firstFrameImg: String? = null,
        title: String? = null,
        isupdt: Int? = null,
        unlock: Int? = null,
        forcePoll: Boolean = false,
    ) {
        updateMvStatus(id, status, firstFrameImg, title, isupdt, unlock, forcePoll)
    }

    private fun notifyBusy() {
        val sharingArr = JSONArray()
        sharingUrls.forEach { sharingArr.put(it) }
        val downloadingArr = JSONArray()
        downloadingUrls.forEach { downloadingArr.put(it) }
        Svc.emit(Events.MV_BUSY_CHANGED, JSONObject().put("sharing", sharingArr).put("downloading", downloadingArr))
    }

    // ================================================================== 生成进度轮询

    private const val POLLING_TASK_ID = "mv_generating_task"
    private const val POLLING_MV_FAILED_TASK_ID = "mv_failed_task"
    private const val POLL_INTERVAL_MS = 12_000
    private const val POLL_FAILED_INTERVAL_MS = 60_000
    private const val RECENT_FAILED_HOURS = 6

    /** 「我的 MV」列表的镜像（id → 最近一次已知内容）。 */
    private val myMvItems = LinkedHashMap<Long, MvModel>()

    private val mvGeneratingIds = LinkedHashSet<Long>()
    private val mvRecord = HashSet<Long>()
    private val mvFailedIds = LinkedHashSet<Long>()
    private var mvFailedCount = 0

    /** 退出登录时递增，丢弃仍在飞的旧账号轮询结果。 */
    private var pollGeneration = 0

    val generatingMvIds: Set<Long> get() = mvGeneratingIds
    val failedWatchMvIds: Set<Long> get() = mvFailedIds

    /**
     * 「我的 MV」列表返回后调用。
     * @param list 当前完整列表（第一页即整表替换；加载更多时为追加后的全量）
     * @param pageNo 刚加载的页码；第一页会重置全部跟踪数据
     */
    fun afterMv(list: List<MvModel>, pageNo: Int): Boolean {
        if (pageNo == 1) {
            mvFailedIds.clear()
            mvGeneratingIds.clear()
            mvRecord.clear()
            myMvItems.clear()
        }
        val ids = LinkedHashSet<Long>()
        val now = nowMs()
        for (item in list) {
            // 加载更多时跳过已处理的旧数据，只处理新增条目
            if (pageNo > 1 && mvRecord.contains(item.id)) continue
            mvRecord.add(item.id)
            myMvItems[item.id] = item
            // 试用 MV 不走通用轮询，从 TryFreeTaskService 同步状态
            if (item.trialMvFlag) {
                syncTrialItemFromService(item.id)
                continue
            }
            if (item.status == MvStatus.generating || item.status == MvStatus.queue) {
                ids.add(item.id)
            } else if (item.status == MvStatus.failed) {
                // 最近 6 小时内失败的条目继续观察：服务端可能自动重试
                val isRecent = (now - item.createTime) / 3_600_000L < RECENT_FAILED_HOURS
                if (isRecent) mvFailedIds.add(item.id)
            }
        }
        if (ids.isNotEmpty()) {
            mvGeneratingIds.addAll(ids)
            loopMvData()
        }
        if (mvFailedIds.isNotEmpty()) {
            mvFailedCount = 0
            loopMvFailedData()
        }
        return true
    }

    /** 退出登录 / 作品库销毁：停掉两个轮询并清空跟踪数据。 */
    fun cancelMvData() {
        pollGeneration++
        PollingService.stopTask(POLLING_TASK_ID)
        PollingService.stopTask(POLLING_MV_FAILED_TASK_ID)
        mvGeneratingIds.clear()
        mvFailedIds.clear()
        mvRecord.clear()
        myMvItems.clear()
        likeStates.clear()
    }

    private fun emitPatches(patches: JSONArray) {
        if (patches.length() == 0) return
        Svc.emit(Events.MV_TASK_UPDATED, JSONObject().put("items", patches))
    }

    private fun isTerminal(status: MvStatus): Boolean =
        status == MvStatus.success ||
            status == MvStatus.mergeFailed ||
            status == MvStatus.failed ||
            status == MvStatus.waitingConfirm ||
            status == MvStatus.imageFailed ||
            status == MvStatus.videoFailed

    /** 生成中 / 排队中的条目：12 秒一次，不立即执行；没有 id 时自动停止。 */
    private fun loopMvData() {
        PollingService.registerTask(
            taskId = POLLING_TASK_ID,
            intervalMs = POLL_INTERVAL_MS,
            startImmediately = false,
            shouldContinue = { mvGeneratingIds.isNotEmpty() },
            task = {
                val generation = pollGeneration
                val list = WorksApi.fetchMvListByIds(mvGeneratingIds.joinToString(","))
                if (generation == pollGeneration && list.isNotEmpty()) {
                    val patches = JSONArray()
                    // 本批只要有生成失败就拉一次用户信息，避免多条失败并发重复请求
                    var shouldRefreshUser = false
                    for (data in list) {
                        if (isTerminal(data.status)) {
                            // 成功、失败、等待确认、图片生成失败、视频生成失败都需要移除当前 id
                            // 生成失败会退积分；条目已不在列表也要刷新余额
                            if (data.status == MvStatus.failed) shouldRefreshUser = true
                            mvGeneratingIds.remove(data.id)
                            val item = myMvItems[data.id] ?: continue
                            if (data.status == MvStatus.failed &&
                                item.status == MvStatus.generating &&
                                TrialMvListSync.shouldPollMvItem(item)
                            ) {
                                if (!mvFailedIds.contains(data.id)) {
                                    mvFailedIds.add(data.id)
                                    mvFailedCount = 0
                                    loopMvFailedData()
                                }
                            }
                            val resultUrl = if (data.status == MvStatus.success) data.resultUrl else null
                            // 轮询积分为 0 或缺失时保留列表上的原值；试看完成带 unlock 0，解锁后完成带 1
                            val needPoint = if (data.continueFullMvNeedPoint > 0) data.continueFullMvNeedPoint else null
                            myMvItems[data.id] = item.copy(
                                status = data.status,
                                firstFrameImg = data.firstFrameImg ?: item.firstFrameImg,
                                resultUrl = resultUrl ?: item.resultUrl,
                                quality = data.quality,
                                trialStage = data.trialStage,
                                trialMvFlag = data.trialMvFlag,
                                unlock = data.unlock ?: item.unlock,
                                continueFullMvNeedPoint = needPoint ?: item.continueFullMvNeedPoint,
                            )
                            val patch = JSONObject()
                                .put("id", data.id)
                                .put("status", data.status.value)
                                .put("quality", data.quality)
                                .put("trialStage", data.trialStage)
                                .put("trialMvFlag", data.trialMvFlag)
                            if (data.firstFrameImg != null) patch.put("firstFrameImg", data.firstFrameImg)
                            if (resultUrl != null) patch.put("resultUrl", resultUrl)
                            if (data.unlock != null) patch.put("unlock", data.unlock)
                            if (needPoint != null) patch.put("continueFullMvNeedPoint", needPoint)
                            patches.put(patch)
                        } else if (data.status == MvStatus.generating) {
                            val item = myMvItems[data.id] ?: continue
                            // 试用 MV 阶段推进时 status 仍为 generating，需同步 trialStage
                            if (item.status != MvStatus.generating ||
                                item.trialStage != data.trialStage ||
                                item.firstFrameImg != data.firstFrameImg ||
                                (data.unlock != null && item.unlock != data.unlock) ||
                                (data.continueFullMvNeedPoint > 0 && item.continueFullMvNeedPoint != data.continueFullMvNeedPoint)
                            ) {
                                val needPoint = if (data.continueFullMvNeedPoint > 0) data.continueFullMvNeedPoint else null
                                myMvItems[data.id] = item.copy(
                                    status = data.status,
                                    firstFrameImg = data.firstFrameImg ?: item.firstFrameImg,
                                    trialStage = data.trialStage,
                                    trialMvFlag = data.trialMvFlag,
                                    unlock = data.unlock ?: item.unlock,
                                    continueFullMvNeedPoint = needPoint ?: item.continueFullMvNeedPoint,
                                )
                                val patch = JSONObject()
                                    .put("id", data.id)
                                    .put("status", data.status.value)
                                    .put("trialStage", data.trialStage)
                                    .put("trialMvFlag", data.trialMvFlag)
                                if (data.firstFrameImg != null) patch.put("firstFrameImg", data.firstFrameImg)
                                if (data.unlock != null) patch.put("unlock", data.unlock)
                                if (needPoint != null) patch.put("continueFullMvNeedPoint", needPoint)
                                patches.put(patch)
                            }
                        }
                    }
                    emitPatches(patches)
                    if (shouldRefreshUser) Account.refreshUserInfo()
                }
            },
        )
    }

    /** 最近失败的条目：60 秒一次，立即执行；超过 60 次或没有 id 时停止。 */
    private fun loopMvFailedData() {
        PollingService.registerTask(
            taskId = POLLING_MV_FAILED_TASK_ID,
            intervalMs = POLL_FAILED_INTERVAL_MS,
            startImmediately = true,
            shouldContinue = { mvFailedIds.isNotEmpty() },
            task = {
                val generation = pollGeneration
                val list = WorksApi.fetchMvListByIds(mvFailedIds.joinToString(","))
                if (generation == pollGeneration) {
                    if (mvFailedCount > 60) {
                        PollingService.stopTask(POLLING_MV_FAILED_TASK_ID)
                        mvFailedIds.clear()
                    } else {
                        mvFailedCount++
                        val patches = JSONArray()
                        for (data in list) {
                            if (data.status == MvStatus.failed) continue
                            val item = myMvItems[data.id] ?: continue
                            val needPoint = if (data.continueFullMvNeedPoint > 0) data.continueFullMvNeedPoint else null
                            val updated = item.copy(
                                status = data.status,
                                trialStage = data.trialStage,
                                trialMvFlag = data.trialMvFlag,
                                unlock = data.unlock ?: item.unlock,
                                continueFullMvNeedPoint = needPoint ?: item.continueFullMvNeedPoint,
                            )
                            myMvItems[data.id] = updated
                            val patch = JSONObject()
                                .put("id", data.id)
                                .put("status", data.status.value)
                                .put("trialStage", data.trialStage)
                                .put("trialMvFlag", data.trialMvFlag)
                            if (data.unlock != null) patch.put("unlock", data.unlock)
                            if (needPoint != null) patch.put("continueFullMvNeedPoint", needPoint)
                            patches.put(patch)
                            if (data.status == MvStatus.generating) {
                                // 服务端自动重试：回到生成中轮询
                                if (!mvGeneratingIds.contains(data.id) && TrialMvListSync.shouldPollMvItem(updated)) {
                                    mvGeneratingIds.add(data.id)
                                    mvRecord.add(data.id)
                                    loopMvData()
                                }
                            }
                            mvFailedIds.remove(data.id)
                        }
                        emitPatches(patches)
                    }
                }
            },
        )
    }

    private fun updateMvStatus(
        id: Long,
        status: MvStatus,
        firstFrameImg: String?,
        title: String?,
        isupdt: Int?,
        unlock: Int?,
        forcePoll: Boolean,
    ) {
        val item = myMvItems[id] ?: return
        // 试看扣费成功时把 unlock 写成 1，生成中卡片不再露出解锁按钮。
        myMvItems[id] = item.copy(
            status = status,
            firstFrameImg = firstFrameImg ?: item.firstFrameImg,
            title = title ?: item.title,
            isUpdt = isupdt ?: item.isUpdt,
            unlock = unlock ?: item.unlock,
        )
        val patch = JSONObject().put("id", id).put("status", status.value)
        if (firstFrameImg != null) patch.put("firstFrameImg", firstFrameImg)
        if (title != null) patch.put("title", title)
        if (isupdt != null) patch.put("isUpdt", isupdt)
        if (unlock != null) patch.put("unlock", unlock)
        emitPatches(JSONArray().put(patch))
        // 试用 MV 状态由 TryFreeTaskService 同步。高潮试看解锁后必须进入现有轮询，不能被这里提前返回挡住。
        if (item.trialMvFlag && !forcePoll) return
        if (status == MvStatus.queue || status == MvStatus.generating) {
            if (!mvGeneratingIds.contains(id)) {
                mvGeneratingIds.add(id)
                mvRecord.add(id)
            }
            loopMvData()
        }
    }

    /** 将 TryFreeTaskService 当前状态写入列表中匹配的试用 MV 项（由试用服务在状态变化时调用）。 */
    fun syncTrialItemFromService(itemId: Long) {
        if (itemId <= 0L) return
        if (TryFreeTaskService.mvId != itemId) return
        val item = myMvItems[itemId] ?: return
        if (!item.trialMvFlag) return
        val updated = TrialMvListSync.applyTrialServiceState(
            item,
            status = TryFreeTaskService.status,
            trialStage = TryFreeTaskService.stage.value,
            coverImage = TryFreeTaskService.coverImage,
            firstFrameImg = TryFreeTaskService.firstFrameImg,
            resultUrl = TryFreeTaskService.resultUrl,
            fourViewImg = TryFreeTaskService.fourViewImg,
        )
        if (updated.status == item.status &&
            updated.trialStage == item.trialStage &&
            updated.resultUrl == item.resultUrl &&
            updated.coverImage == item.coverImage &&
            updated.firstFrameImg == item.firstFrameImg &&
            updated.fourViewImg == item.fourViewImg
        ) return
        myMvItems[itemId] = updated
        val patch = JSONObject()
            .put("id", itemId)
            .put("status", updated.status.value)
            .put("trialMvFlag", true)
            .put("trialStage", updated.trialStage)
            .put("fourViewImg", updated.fourViewImg)
        if (updated.resultUrl != null) patch.put("resultUrl", updated.resultUrl)
        if (updated.coverImage != null) patch.put("coverImage", updated.coverImage)
        if (updated.firstFrameImg != null) patch.put("firstFrameImg", updated.firstFrameImg)
        emitPatches(JSONArray().put(patch))
    }

    /**
     * 把 [Events.MV_TASK_UPDATED] 里的一条补丁写到列表项上，返回新对象（页面用它替换原元素）。
     * 补丁：{id, status, firstFrameImg?, resultUrl?, quality?, trialStage?, trialMvFlag?, unlock?,
     * continueFullMvNeedPoint?, title?, isUpdt?, coverImage?, fourViewImg?}，缺省字段保留原值。
     */
    fun applyTaskPatch(current: MvModel, patch: JSONObject): MvModel = current.copy(
        status = if (patch.has("status")) MvStatus.getStatus(patch.optInt("status", current.status.value)) else current.status,
        firstFrameImg = patch.jStrOrNull("firstFrameImg") ?: current.firstFrameImg,
        resultUrl = patch.jStrOrNull("resultUrl") ?: current.resultUrl,
        quality = patch.jIntOrNull("quality") ?: current.quality,
        trialStage = patch.jIntOrNull("trialStage") ?: current.trialStage,
        trialMvFlag = if (patch.has("trialMvFlag")) patch.optBoolean("trialMvFlag", current.trialMvFlag) else current.trialMvFlag,
        unlock = patch.jIntOrNull("unlock") ?: current.unlock,
        continueFullMvNeedPoint = patch.jIntOrNull("continueFullMvNeedPoint") ?: current.continueFullMvNeedPoint,
        title = patch.jStrOrNull("title") ?: current.title,
        isUpdt = patch.jIntOrNull("isUpdt") ?: current.isUpdt,
        coverImage = patch.jStrOrNull("coverImage") ?: current.coverImage,
        fourViewImg = patch.jStrOrNull("fourViewImg") ?: current.fourViewImg,
    )
}
