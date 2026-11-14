package com.sondo.app.service

import com.sondo.app.base.AppEnv
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.constants.GlobalData
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.i18n.trParams
import com.sondo.app.core.storage.Sp
import com.sondo.app.core.utils.CdnDownload
import com.sondo.app.core.utils.ConfirmDialogLite
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.EmailHelper
import com.sondo.app.core.utils.HostNative
import com.sondo.app.core.utils.truncateGraphemes
import com.sondo.app.data.models.SongModel
import com.sondo.app.data.models.jStrOrNull
import com.sondo.app.nativeapi.SDAppModule
import com.sondo.app.nativeapi.SDMediaPickerModule
import com.sondo.app.nativeapi.nativeModule
import com.sondo.app.nativeapi.ok
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.ui.overlay.DialogHandle
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.random.Random

/** 歌曲列表项的操作类型（对应 Flutter 侧 MusicItemType）。 */
enum class MusicItemType { // 鸿蒙动态库会把公开枚举导出成 C/C++ 接口，不能用 C++ 关键字 delete 作名字
    deleteItem, share, like, more, detail, play, createMv, feedback }

/**
 * 歌曲操作分发 + 生成进度轮询。
 *
 * 1. 操作分发（对应 Flutter 侧 MusicService）：[onTap] 处理列表项的删除 / 分享 / 收藏 / 更多 / 详情 /
 *    播放 / 去做 MV / 反馈；变更后经 [refreshMusic] 广播 [Events.MUSIC_LIST_CHANGED]，各列表页自行回写。
 * 2. 生成进度轮询（对应 Flutter 侧 LibraryMusicController 的轮询部分，移到服务里以便跨页面存活）：
 *    作品库「我的歌曲」每次加载后调用 [afterMusic]；服务每 4 秒按 id 查进度（单次最多 50 个，后台不请求），
 *    有变化时广播 [Events.MUSIC_TASK_UPDATED]，页面用 [applyProgress] 把补丁写回自己的列表项。
 *
 * 参考工程的 MusicService 不缓存曲风 / 提示词字典，也不提交生成任务（都在创作页自己的数据源里），这里同样没有。
 */
object MusicService {
    private const val TAG = "MusicService"

    private val sharing = BusyKeys { notifyBusy() }
    private val downloading = BusyKeys { notifyBusy() }

    /** 分享进行中的歌曲 id（防重入、转圈）。变化时广播 [Events.MUSIC_BUSY_CHANGED]。 */
    val sharingUrls: Set<String> get() = sharing.keys

    /** 下载进行中的歌曲 id。 */
    val downloadingUrls: Set<String> get() = downloading.keys

    /** 向后兼容：上传基础设施是否已就绪，代理至 [OssUploadService]。 */
    val isInitialized: Boolean get() = OssUploadService.isInitialized

    /**
     * 主框架上「当前作品库音乐 Tab 的列表」（我的 / 收藏）。作品库页面挂载时赋值、销毁时置空；
     * 在主框架点详情 / 播放时用它作为播放列表（参考工程直接读 LibraryMusicController）。
     */
    var libraryMusicListProvider: (() -> List<SongModel>)? = null

    private var inited = false
    private var actionSheet: DialogHandle? = null

    fun init() {
        Svc.ensureBound()
        if (inited) return
        inited = true
        // 退出登录后停掉进行中的生成轮询，避免用旧 id 打到新会话
        Svc.onLogout { cancelMusicData() }
    }

    // ================================================================== 操作分发

    /**
     * 列表项操作入口。
     * @param list 当前页面的歌曲列表（个人主页的详情 / 播放用它作播放列表；主框架用 [libraryMusicListProvider]）
     */
    fun onTap(type: MusicItemType, item: SongModel, list: List<SongModel> = emptyList()) {
        val ui = UiCtx.capture()
        when (type) {
            MusicItemType.deleteItem -> openDelete(ui, item)
            MusicItemType.share -> ui.launch { shareSong(ui, item) }
            MusicItemType.like -> ui.launch { onLikeSong(ui, item, null) }
            MusicItemType.more -> showActions(ui, item)
            MusicItemType.detail -> ui.launch { toSongDetail(ui, item, list) }
            MusicItemType.play -> ui.launch { playSong(ui, item, list) }
            MusicItemType.createMv -> toCreateMv(item)
            MusicItemType.feedback -> ui.launch { onFeedback(item) }
        }
    }

    private suspend fun onFeedback(item: SongModel) {
        val str = "${"music_id".tr}: ${item.id}\n"
        EmailHelper.sendEmail(content = str, userId = Account.userId)
    }

    /** 打开删除确认：首次展示「移入回收站，保留 N 天」说明。 */
    private fun openDelete(ui: UiCtx, item: SongModel) {
        val pager = ui.pager ?: return
        val isShow = Sp.getBool(StorageKey.isDeleteDialog, false)
        if (isShow) {
            ConfirmDialogLite.show(
                pager,
                title = "delete_notice".tr,
                content = "delete_song".tr,
                onConfirm = { ui.launch { deleteSong(ui, item) } },
            )
        } else {
            ConfirmDialogLite.show(
                pager,
                title = "delete_notice".tr,
                content = "trash_retention_notice".tr,
                confirmText = "move_to_trash".tr,
                onConfirm = {
                    Sp.setBool(StorageKey.isDeleteDialog, true)
                    ui.launch { deleteSong(ui, item) }
                },
            )
        }
    }

    private suspend fun deleteSong(ui: UiCtx, song: SongModel) {
        ui.showLoading()
        val res = WorksApi.deleteSong(song.id)
        if (res == 1) {
            // 详情页等二级页删除后返回上一页；在主框架上不动
            ui.back()
            refreshMusic("delete", song)
            ui.toast("delete_success".tr)
            UmengUtils.logEvent("delete_song", mapOf("workStatus" to "success"))
        } else {
            UmengUtils.logEvent("delete_song", mapOf("workStatus" to "failed"))
        }
        ui.closeLoading()
    }

    /** 跳转到创建 MV 页面，带上这首歌。 */
    private fun toCreateMv(item: SongModel) {
        DevLog.logger("跳转到创建MV页面: ${item.id}")
        WorksUi.openCreatePage(Routes.musicVideoGen, JSONObject().put("song", item.toJson()))
    }

    /** 主框架 / 个人主页上的播放列表来源。 */
    private fun resolvePlaylist(ui: UiCtx, list: List<SongModel>): List<SongModel> {
        val route = ui.pageName
        if (route == Routes.root) return libraryMusicListProvider?.invoke() ?: emptyList()
        if (route == Routes.profile) return list
        return emptyList()
    }

    /** 跳转到音乐详情页：args {id, original: 来源页面名}。 */
    private suspend fun toSongDetail(ui: UiCtx, item: SongModel, list: List<SongModel>) {
        val route = ui.pageName
        if (route == Routes.root || route == Routes.profile) {
            // 参考工程不等 setPlaylist：换队列要等原生音源重新就绪，跳转不能被它拖住
            val playlist = resolvePlaylist(ui, list)
            Svc.launch { PlayerService.setPlaylist(playlist) }
        }
        ui.push(Routes.musicDetail, JSONObject().put("id", item.id).put("original", route))
    }

    private suspend fun playSong(ui: UiCtx, item: SongModel, list: List<SongModel>) {
        PlayerService.playSong(item, songs = resolvePlaylist(ui, list))
    }

    /** 正在提交收藏请求的歌曲：连点只算一次。 */
    private val likingSongs = HashSet<Long>()

    /**
     * 本次会话里收藏操作后的最新状态（id → 是否收藏 / 收藏数）。列表不再随收藏整项替换，
     * 调用方传进来的条目可能还是旧值；取反时以这里为准。换账号 / 退出登录时清空（[cancelMusicData]）。
     */
    private val likeStates = HashMap<Long, Pair<Boolean, Int>>()

    /**
     * 收藏 / 取消收藏（乐观更新）：先广播新状态让界面立刻变，再请求接口，失败回滚并提示。
     * [isCollect] 为面板当前展示的收藏态，null 时取最近一次操作的结果或 item 自身。
     */
    private suspend fun onLikeSong(ui: UiCtx, item: SongModel, isCollect: Boolean?) {
        // 判断是否登录
        if (!Account.ensureLogin(ui.pager)) return
        if (!likingSongs.add(item.id)) return
        try {
            val known = likeStates[item.id]
            val collect = isCollect ?: known?.first ?: item.isCollect
            val count = known?.second ?: item.isCollectNum
            val collectStatus = if (collect) 0 else 1
            val before = item.copy(isCollect = collect, isCollectNum = count)
            val after = item.copy(
                isCollect = !collect,
                isCollectNum = if (collectStatus == 1) count + 1 else (count - 1).coerceAtLeast(0),
            )
            applyLike(after)
            val res = WorksApi.collectSong(item.id, collectStatus)
            if (res == 1) {
                ui.toast(if (collectStatus == 1) "collect_success".tr else "uncollect_success".tr)
                UmengUtils.logEvent(if (collectStatus == 1) "like_song" else "unlike_song")
            } else {
                applyLike(before)
                ui.toast(if (collectStatus == 1) "collect_failed".tr else "uncollect_failed".tr)
            }
        } finally {
            likingSongs.remove(item.id)
        }
    }

    private fun applyLike(song: SongModel) {
        likeStates[song.id] = song.isCollect to song.isCollectNum
        // 「更多」面板展示的是 PlayerService.song：只在它就是这首歌时同步，别把面板换成别的歌
        val panel = PlayerService.song
        if (panel == null || panel.id == song.id) PlayerService.setSong(song, "dialog_more")
        refreshMusic("like", song)
    }

    /** 下载歌曲：下载声明 → 仅会员（否则去订阅页）→ 拉取音频 → 系统「存储为」。 */
    private suspend fun downloadSong(ui: UiCtx, item: SongModel) {
        val key = item.id.toString()
        if (downloading.contains(key)) return
        // 第一次挂起之前就占位：声明弹窗开着时的第二次点击会被上面的判断挡掉
        downloading.add(key, ui)
        notifyBusy()
        var tempPath: String? = null
        try {
            val isAgree = Account.showAgree(StorageKey.isAgreeDown, Account.USAGE_DOWNLOAD, ui.pager)
            if (!isAgree) {
                closeActionSheet()
                return
            }
            if (!Account.isSubscribed) {
                closeActionSheet()
                ui.push(Routes.subscribe, null)
                return
            }
            // 文件名按字素截断，避免 emoji 被切成孤立代理项。
            val fileName = truncateGraphemes(item.musicTitle ?: "", 25)
            val url = item.musicAudioUrl ?: ""
            tempPath = if (url.isEmpty()) null else CdnDownload.downloadFile(url, "mp3")
            ui.hop()
            // 只有非空文件才交给系统保存
            if (tempPath == null) {
                ui.toast("download_failed".tr)
                return
            }
            val safeTitle = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            val outputName = "${safeTitle.ifEmpty { "song" }}-${Random.nextInt(1000000)}.mp3"
            val src = tempPath
            // 系统「存储为」要由当前页面发起（Android 上 activity-result 只投给前台页面）
            val saved = ui.native({ JSONObject().put("ok", false).put("cancelled", true) }) {
                nativeModule<SDMediaPickerModule>(SDMediaPickerModule.MODULE_NAME).saveFileAs(src, outputName, "audio/mpeg")
            }
            if (!saved.ok()) {
                // 取消静默返回（FilePicker 返回 null）；真正失败与参考工程的 catch 一致提示下载失败
                if (!saved.optBoolean("cancelled", false)) ui.toast("download_failed".tr)
                return
            }
            val outputFile = displaySavedPath(saved, outputName)
            ui.toast("download_to_storage".trParams("value" to outputFile))
            UmengUtils.logEvent("download", mapOf("workType" to "music"))
        } catch (e: Throwable) {
            ui.toast("download_failed".tr)
            DevLog.logger("Download error: ${e.message}")
        } finally {
            if (tempPath != null) HostNative.file.delete(tempPath)
            downloading.remove(key)
            notifyBusy()
        }
    }

    /**
     * 「已下载到 …」里展示的位置：参考工程展示保存后的文件路径。
     * Android 的「存储为」只给 content:// URI，不能直接给用户看，改用文件名。
     */
    private fun displaySavedPath(saved: JSONObject, fallback: String): String {
        val path = saved.optString("savedPath", "").removePrefix("file://")
        if (path.startsWith("/")) return path
        return saved.optString("name", "").ifEmpty { fallback }
    }

    /** 分享歌曲：分享声明 → 系统分享深链文本 → 成功后累加转发数。 */
    private suspend fun shareSong(ui: UiCtx, song: SongModel) {
        if (song.musicAudioUrl.isNullOrEmpty()) return
        val key = song.id.toString()
        if (sharing.contains(key)) return
        sharing.add(key, ui)
        notifyBusy()
        try {
            val isAgree = Account.showAgree(StorageKey.isAgreeShare, Account.USAGE_SHARE, ui.pager)
            if (!isAgree) return
            ui.closeLoading()
            // 分享新域名的直接深链，页面和下载处理由该域名的站点承接。
            val url = GlobalData.shareUrl(song.id)
            val result = ui.native({ JSONObject() }) {
                nativeModule<SDAppModule>(SDAppModule.MODULE_NAME).share(text = url)
            }
            ui.hop()
            if (result.optString("status", "") == "success") {
                UmengUtils.logEvent("share", mapOf("workType" to "music"))
                val res = WorksApi.addMusicRepostCount(song.id)
                if (res == 1) refreshMusic("share", song.copy(repostCount = song.repostCount + 1))
            }
        } catch (e: Throwable) {
            DevLog.logger("share error: ${e.message}")
        } finally {
            sharing.remove(key)
            notifyBusy()
        }
    }

    /** 显示操作菜单（编辑 / 收藏 / 分享 / 发布 / 删除 / 下载）。 */
    private fun showActions(ui: UiCtx, song: SongModel) {
        val pager = ui.pager ?: return
        var item = song
        PlayerService.setSong(item, "dialog_more")
        val sheet = WorksUi.songActionsSheet
        if (sheet == null) {
            DevLog.logger("$TAG: songActionsSheet 未挂载")
            return
        }
        actionSheet = sheet(
            pager,
            SongActionsRequest(
                item = item,
                type = "my_song",
                onEdit = {
                    closeActionSheet()
                    ui.launch { openEditSong(ui, item) }
                },
                onLike = { isCollect -> ui.launch { onLikeSong(ui, item, isCollect) } },
                onShare = { ui.launch { shareSong(ui, item) } },
                onPublish = { value ->
                    // 产品开关关闭时不走发布，避免漏网入口仍调发布接口。歌曲编辑走 openEditSong，不受影响。
                    if (AppConstants.enableWorkPublish) {
                        ui.launch {
                            val oldValue = item.musicPublish
                            val publishStatus = if (value) 1 else 0
                            item = item.copy(musicPublish = publishStatus)
                            PlayerService.setSong(item, "dialog_more")
                            val res = WorksApi.publishOperation(musicId = item.id, musicPublish = publishStatus)
                            if (res == 1) {
                                item = item.copy(musicPublish = publishStatus)
                                refreshMusic("publish", item)
                                ui.toast(if (item.musicPublish == 0) "unpublish_success".tr else "publish_success".tr)
                                UmengUtils.logEvent(
                                    if (item.musicPublish == 0) "unpublish_song" else "publish_song",
                                    mapOf("workStatus" to "success"),
                                )
                                UmengUtils.logEvent("publish", mapOf("workType" to "music"))
                                PlayerService.setSong(item, "dialog_more")
                                // 个人主页取消发布后该条目应从列表移除：页面收到 type = 'publish' 且 musicPublish == 0 时处理
                            } else {
                                item = item.copy(musicPublish = oldValue)
                                PlayerService.setSong(item, "dialog_more")
                                ui.toast(if (item.musicPublish == 0) "publish_failed".tr else "unpublish_failed".tr)
                                UmengUtils.logEvent(
                                    if (item.musicPublish == 0) "publish_song" else "unpublish_song",
                                    mapOf("workStatus" to "failed"),
                                )
                            }
                        }
                    }
                },
                onDelete = {
                    openDelete(ui, item)
                    closeActionSheet()
                },
                onDownload = { ui.launch { downloadSong(ui, item) } },
            ),
        )
    }

    private fun closeActionSheet() {
        actionSheet?.dismiss()
        actionSheet = null
    }

    /** 编辑歌曲：标题 / 封面 / 分类。 */
    private suspend fun openEditSong(ui: UiCtx, origin: SongModel) {
        val pager = ui.pager ?: return
        val sheet = WorksUi.editSongSheet
        if (sheet == null) {
            DevLog.logger("$TAG: editSongSheet 未挂载")
            return
        }
        val result = sheet(pager, origin) ?: return
        ui.showLoading()
        val newTitle = result.optString("title", "")
        val newImage = optStringOrNull(result, "image")
        val categoryId = optStringOrNull(result, "categoryId")
        val categoryName = optStringOrNull(result, "categoryName")
        val res = WorksApi.publishOperation(
            musicId = origin.id,
            musicTitle = newTitle,
            musicPublish = origin.musicPublish,
            musicCoverUrl = newImage,
            categoryId = categoryId,
            categoryName = categoryName,
        )
        if (res == 1) {
            // 为 null 的字段保留原值
            val song = origin.copy(
                musicTitle = newTitle,
                musicCoverUrl = newImage ?: origin.musicCoverUrl,
                musicCategoryId = categoryId?.toLongOrNull() ?: origin.musicCategoryId,
                musicCategory = categoryName ?: origin.musicCategory,
            )
            refreshMusic("edit", song)
            ui.closeLoading()
            UmengUtils.logEventButtonClick("music_edit_successful", pageName = UmengUtils.routePath(ui.pageName))
            ui.back()
        } else {
            UmengUtils.logEventButtonClick("music_edit_failed", pageName = UmengUtils.routePath(ui.pageName))
            ui.closeLoading()
        }
    }

    private fun optStringOrNull(o: JSONObject, key: String): String? {
        return o.jStrOrNull(key)
    }

    /**
     * 歌曲变更扇出：广播 [Events.MUSIC_LIST_CHANGED]，data = {type, item}，
     * type 为 delete | like | share | publish | edit，item 为变更后的 SongModel JSON。
     * 个人主页、首页、作品库、曲风页、详情页各自按 type 回写；播放器歌单在这里直接同步。
     */
    fun refreshMusic(type: String, item: SongModel) {
        if (type == "delete") {
            tracked.remove(item.id)
        } else {
            val cur = tracked[item.id]
            if (cur != null && type == "edit") {
                tracked[item.id] = cur.copy(musicTitle = item.musicTitle, musicCoverUrl = item.musicCoverUrl)
            }
        }
        PlayerService.updateMusicList(type, item)
        Svc.emit(Events.MUSIC_LIST_CHANGED, JSONObject().put("type", type).put("item", item.toJson()))
    }

    private fun notifyBusy() {
        val sharingArr = JSONArray()
        sharingUrls.forEach { sharingArr.put(it) }
        val downloadingArr = JSONArray()
        downloadingUrls.forEach { downloadingArr.put(it) }
        Svc.emit(Events.MUSIC_BUSY_CHANGED, JSONObject().put("sharing", sharingArr).put("downloading", downloadingArr))
    }

    // ================================================================== 生成进度轮询

    private const val POLLING_TASK_ID = "music_generating_task"
    private const val POLL_INTERVAL_MS = 4000
    private const val MAX_IDS_PER_REQUEST = 50

    /** 排队中(1)或生成中(2)的歌曲 id；进入完成(3)或失败(4)后移除。 */
    private val generatingIds = LinkedHashSet<Long>()

    /** 「我的歌曲」列表的镜像（id → 最近一次已知内容），用来判断进度字段是否真的变了。 */
    private val tracked = HashMap<Long, SongModel>()

    /** 退出登录时递增，丢弃仍在飞的旧账号轮询结果。 */
    private var pollGeneration = 0

    /** 当前仍在轮询的歌曲 id。 */
    val generatingSongIds: Set<Long> get() = generatingIds

    val isPollingMusic: Boolean get() = PollingService.isTaskActive(POLLING_TASK_ID)

    /**
     * 「我的歌曲」列表返回后调用（下拉与上拉都传入当前完整列表）：按仍在排队或生成中的歌曲启动进度轮询。
     * 刷新会整表替换，按列表重建，不沿用已滑出的 id。
     */
    fun afterMusic(list: List<SongModel>): Boolean {
        tracked.clear()
        list.forEach { tracked[it.id] = it }
        generatingIds.clear()
        list.forEach { if (isInProgress(it.musicGenerateStatus)) generatingIds.add(it.id) }
        if (generatingIds.isEmpty()) {
            PollingService.stopTask(POLLING_TASK_ID)
            return true
        }
        if (PollingService.isTaskActive(POLLING_TASK_ID)) return true
        PollingService.registerTask(
            taskId = POLLING_TASK_ID,
            intervalMs = POLL_INTERVAL_MS,
            startImmediately = false,
            shouldContinue = { generatingIds.isNotEmpty() },
            task = { pollMusicProgress() },
        )
        return true
    }

    /** 退出登录 / 作品库销毁：停掉进行中的生成轮询并清空跟踪数据。 */
    fun cancelMusicData() {
        pollGeneration++
        generatingIds.clear()
        tracked.clear()
        likeStates.clear()
        PollingService.stopTask(POLLING_TASK_ID)
    }

    /** 后台本轮不请求；回前台后定时器继续。单次最多 50 个 id。 */
    private suspend fun pollMusicProgress() {
        if (Svc.appInBackground) return
        val generation = pollGeneration
        val ids = generatingIds.toList()
        if (ids.isEmpty()) return
        val patches = JSONArray()
        var terminal = false
        var failed = false
        var start = 0
        while (start < ids.size) {
            val end = minOf(start + MAX_IDS_PER_REQUEST, ids.size)
            val chunk = ids.subList(start, end).joinToString(",")
            val list = WorksApi.getMusicListByIds(chunk)
            if (generation != pollGeneration) return
            val applied = applyMusicProgress(list, patches)
            terminal = terminal || applied.first
            failed = failed || applied.second
            start += MAX_IDS_PER_REQUEST
        }
        if (generation != pollGeneration) return
        // 生成失败会退积分
        if (failed) Account.refreshUserInfo()
        // 终态后选曲弹窗不能继续用生成中的缓存
        if (terminal) MemoryCacheService.clearByPrefix(MemoryCacheKeys.musicSelectMy)
        if (patches.length() > 0) {
            Svc.emit(
                Events.MUSIC_TASK_UPDATED,
                JSONObject().put("items", patches).put("terminal", terminal).put("failed", failed),
            )
        }
    }

    /**
     * 按 id 生成补丁。3 更新可播放信息，4 只写失败原因；进行中仅在字段变化时刷新。
     * @return (terminal, failed)
     */
    private fun applyMusicProgress(list: List<SongModel>, patches: JSONArray): Pair<Boolean, Boolean> {
        var terminal = false
        var failed = false
        for (item in list) {
            val status = item.musicGenerateStatus
            val current = tracked[item.id]
            if (isInProgress(status)) {
                if (current == null) continue
                if (unchanged(current, item)) continue
                tracked[item.id] = mergeProgress(current, item)
                patches.put(progressPatch(item, withError = false))
                // 5 在 Android 上已可边生成边播：把新地址同步给播放器
                if (status == 5 && AppEnv.isAndroid) {
                    PlayerService.updatePlayInfo(item.id, musicAudioUrl = item.musicAudioUrl, status = status)
                }
                continue
            }
            // 3 完成、4 失败后移出轮询；其它非进行中状态同样移出，避免空转
            generatingIds.remove(item.id)
            if (status != 3 && status != 4) continue
            terminal = true
            if (current == null) continue
            if (status == 4) {
                failed = true
                tracked[item.id] = current.copy(musicGenerateStatus = status, errorMsg = item.errorMsg ?: "")
                patches.put(JSONObject().put("id", item.id).put("musicGenerateStatus", status).put("errorMsg", item.errorMsg ?: ""))
            } else {
                tracked[item.id] = mergeProgress(current, item)
                patches.put(progressPatch(item, withError = false))
                if (AppEnv.isAndroid) {
                    PlayerService.updatePlayInfo(item.id, musicAudioUrl = item.musicAudioUrl, status = status)
                }
            }
        }
        return terminal to failed
    }

    /**
     * 仍需轮询的状态：1 排队、2 生成中、5 音频可流式播放但未最终完成。
     * 参考工程只轮询 1/2，碰到 5 就移出且不回写，卡片会一直停在「生成中」直到手动刷新；这里把 5 也当进行中，等到 3/4 再结束。
     */
    private fun isInProgress(status: Int): Boolean = status == 1 || status == 2 || status == 5

    /** 接口缺省字段为 null，视为未变化，避免空值覆盖列表已有标题或地址。 */
    private fun same(current: String?, incoming: String?): Boolean = incoming == null || current == incoming

    private fun unchanged(current: SongModel, item: SongModel): Boolean =
        current.musicGenerateStatus == item.musicGenerateStatus &&
            same(current.musicTitle, item.musicTitle) &&
            same(current.musicCoverUrl, item.musicCoverUrl) &&
            same(current.musicAudioUrl, item.musicAudioUrl)

    private fun mergeProgress(current: SongModel, item: SongModel): SongModel = current.copy(
        musicAudioUrl = item.musicAudioUrl ?: current.musicAudioUrl,
        musicCoverUrl = item.musicCoverUrl ?: current.musicCoverUrl,
        musicTitle = item.musicTitle ?: current.musicTitle,
        musicGenerateStatus = item.musicGenerateStatus,
    )

    private fun progressPatch(item: SongModel, withError: Boolean): JSONObject {
        val o = JSONObject().put("id", item.id).put("musicGenerateStatus", item.musicGenerateStatus)
        if (item.musicTitle != null) o.put("musicTitle", item.musicTitle)
        if (item.musicAudioUrl != null) o.put("musicAudioUrl", item.musicAudioUrl)
        if (item.musicCoverUrl != null) o.put("musicCoverUrl", item.musicCoverUrl)
        if (withError) o.put("errorMsg", item.errorMsg ?: "")
        return o
    }

    /**
     * 把 [Events.MUSIC_TASK_UPDATED] 里的一条补丁写到列表项上，返回新对象（页面用它替换原元素）。
     * 补丁：{id, musicGenerateStatus, musicTitle?, musicAudioUrl?, musicCoverUrl?, errorMsg?}，缺省字段保留原值。
     */
    fun applyProgress(current: SongModel, patch: JSONObject): SongModel = current.copy(
        musicGenerateStatus = patch.optInt("musicGenerateStatus", current.musicGenerateStatus),
        musicTitle = if (patch.has("musicTitle")) patch.optString("musicTitle", "") else current.musicTitle,
        musicAudioUrl = if (patch.has("musicAudioUrl")) patch.optString("musicAudioUrl", "") else current.musicAudioUrl,
        musicCoverUrl = if (patch.has("musicCoverUrl")) patch.optString("musicCoverUrl", "") else current.musicCoverUrl,
        errorMsg = if (patch.has("errorMsg")) patch.optString("errorMsg", "") else current.errorMsg,
    )
}
