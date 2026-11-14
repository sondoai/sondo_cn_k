package com.sondo.app.features.my

import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.asset
import com.sondo.app.core.http.ApiResult
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.delayMs
import com.sondo.app.core.utils.truncateGraphemes
import com.sondo.app.data.models.AudioItemStatus
import com.sondo.app.data.models.AudioUploadItem
import com.sondo.app.data.models.PageResult
import com.sondo.app.data.models.SongModel
import com.sondo.app.features.my.data.MyAudioApi
import com.sondo.app.service.OssUploadService
import com.sondo.app.service.audio.AppAudioPreviewCoordinator
import com.sondo.app.service.audio.AppAudioTrack
import com.sondo.app.ui.atoms.SDMusicLoading
import com.sondo.app.ui.list.PagingState
import com.sondo.app.ui.list.SDRefreshList
import com.sondo.app.ui.nav.SDNavBar
import com.sondo.app.ui.works.SDAudioTaskCard
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 我的音频页（Dart: MyAudioPage + MyAudioController）：上传列表、试听、暂停 / 恢复 / 重试 / 删除，点已完成的音频作为选曲结果返回。
 *
 * 参数（均可选）：
 * - `returnOnUpload: Boolean` —— 选文件并开始上传后立即返回调用方；
 * - `funName: String` —— 上传统计用的入口名。
 *
 * 结果：
 * - 点选已完成音频：`{song: SongModel json}`（id = 0，标题取文件名第一个 `.` 之前、最多 30 个字素）；
 * - `returnOnUpload` 时上传已启动：`{song: SongModel json}`（秒传）或 `{upload: AudioUploadItem json}`（已入队）。
 */
@Page(Routes.myAudio)
internal class MyAudioPage : BasePager() {
    val paging = PagingState<AudioUploadItem>(this, pageSize = PAGE_SIZE) { pageNo, pageSize -> loadData(pageNo, pageSize) }

    // —— 播放状态 —— //
    /** 当前正在试听的条目键（AudioUploadItem.playbackKey），无播放时为空串 */
    var playingUploadId by observable("")
    var isPlaying by observable(false)
    var isLoadingAudio by observable(false)
    var playPositionMs = 0L
    var playDurationMs = 0L

    private var hasAutoPickedOnce = false

    private val returnOnUpload: Boolean get() = pagerData.params.optBoolean("returnOnUpload", false)
    private val funName: String get() = pagerData.params.optString("funName", "")

    override fun created() {
        super.created()
        paging.refresh()
        on(Events.AUDIO_STATE) { onPreviewStateChanged(it) }
        // 监听上传任务变化，同步到分页列表
        on(Events.UPLOAD_TASKS_CHANGED) { syncServiceTasks() }
    }

    override fun pageWillDestroy() {
        // 释放页面试听，应用播放器仍由全局服务持有
        val owner = this
        com.sondo.app.base.AppHost.launch { AppAudioPreviewCoordinator.stop(owner) }
        super.pageWillDestroy()
    }

    private fun trackId(key: String) = "preview:my_audio:$key"

    private fun onPreviewStateChanged(state: JSONObject) {
        val active = AppAudioPreviewCoordinator.owns(this) && state.optString("trackId", "") == trackId(playingUploadId)
        isPlaying = active && state.optBoolean("isPlaying", false)
        val status = state.optString("status", "")
        isLoadingAudio = active && (status == "loading" || status == "buffering")
        if (active) {
            playPositionMs = state.optLong("positionMs", 0L)
            playDurationMs = state.optLong("durationMs", 0L)
        }
    }

    /** 切换指定音频的播放 / 暂停；切换曲目时自动停止上一首 */
    fun togglePlay(item: AudioUploadItem) {
        val owner = this
        launch {
            try {
                val url = item.fileUrl
                DevLog.logger("togglePlay: $url")
                if (url.isNullOrEmpty()) return@launch
                val track = AppAudioTrack.fromUrl(id = trackId(item.playbackKey), url = url, title = item.fileName)
                if (playingUploadId == item.playbackKey) {
                    if (isPlaying) {
                        AppAudioPreviewCoordinator.pause(owner)
                    } else if (AppAudioPreviewCoordinator.owns(owner)) {
                        AppAudioPreviewCoordinator.resume(owner)
                    } else {
                        AppAudioPreviewCoordinator.play(owner, track)
                    }
                } else {
                    playingUploadId = item.playbackKey
                    playPositionMs = 0L
                    playDurationMs = 0L
                    AppAudioPreviewCoordinator.play(owner, track)
                }
            } catch (e: Throwable) {
                DevLog.logger(e.toString())
            }
        }
    }

    suspend fun stopPlay() {
        try {
            AppAudioPreviewCoordinator.stop(this)
            playingUploadId = ""
            isPlaying = false
            isLoadingAudio = false
            playPositionMs = 0L
            playDurationMs = 0L
        } catch (e: Throwable) {
            DevLog.logger("$e")
        }
    }

    // —— 分页 —— //

    /** 服务端失败（Dart total == -1）时最多重试 3 次，间隔 1 秒。 */
    private suspend fun fetchPage(pageNo: Int, pageSize: Int): ApiResult<PageResult<AudioUploadItem>> {
        var retryCount = 0
        while (true) {
            val result = MyAudioApi.listPage(pageNo, pageSize)
            if (result is ApiResult.Success || retryCount >= 3) return result
            retryCount++
            delayMs(1000)
        }
    }

    private suspend fun loadData(pageNo: Int, pageSize: Int): ApiResult<PageResult<AudioUploadItem>> {
        val result = fetchPage(pageNo, pageSize)
        if (pageNo != 1) return result
        val page = (result as? ApiResult.Success)?.data
        // 第一页：本地进行中 / 未登记的任务排在服务端列表之前（Dart 失败时也合入本地任务）
        val localItems = OssUploadService.tasks
            .filter { it.id == 0L || it.status != AudioItemStatus.completed }
            .sortedWith { a, b ->
                val at = a.createdAt
                val bt = b.createdAt
                when {
                    at == null && bt == null -> 0
                    at == null -> 1
                    bt == null -> -1
                    else -> bt.compareTo(at)
                }
            }
        val list = localItems + (page?.list ?: emptyList())
        if (list.isEmpty() && !hasAutoPickedOnce) {
            pickAndUpload()
        }
        hasAutoPickedOnce = true
        return ApiResult.Success(PageResult(list, page?.total ?: list.size))
    }

    // —— 上传任务同步 —— //

    /** OssUploadService.tasks 变化时，把变更同步到分页列表。 */
    private fun syncServiceTasks() {
        val serviceTasks = OssUploadService.tasks
        val list = paging.list
        val serviceUploadIds = serviceTasks.map { it.uploadId }.toSet()

        for (item in serviceTasks.reversed()) {
            if (list.none { it.isSameRecord(item) }) list.add(0, item)
        }
        for (serviceItem in serviceTasks) {
            val idx = list.indexOfFirst { it.isSameRecord(serviceItem) }
            if (idx != -1) {
                val cur = list[idx]
                if (cur.status != serviceItem.status ||
                    cur.uploadedBytes != serviceItem.uploadedBytes ||
                    cur.fileUrl != serviceItem.fileUrl ||
                    cur.lastError != serviceItem.lastError
                ) {
                    list[idx] = serviceItem.copy()
                }
            }
        }
        val toRemove = list.filter { it.id == 0L && !serviceUploadIds.contains(it.uploadId) }
        var needsRefresh = false
        for (item in toRemove) {
            list.remove(item)
            if (item.status == AudioItemStatus.completed) needsRefresh = true
        }
        if (needsRefresh) paging.refresh()
    }

    // —— 用户操作 —— //

    /** 选择并上传文件；参数 `returnOnUpload: true` 时上传启动后立即返回调用方。 */
    fun pickAndUpload() {
        launch {
            val result = OssUploadService.pickAndUpload(funName) ?: return@launch
            if (returnOnUpload) {
                when (result) {
                    is SongModel -> nav.pop(JSONObject().put("song", result.toJson()))
                    is AudioUploadItem -> nav.pop(JSONObject().put("upload", result.toJson()))
                    else -> nav.pop()
                }
                return@launch
            }
            // 秒传无本地进行中任务，刷新服务端列表
            if (result is SongModel) paging.refresh()
        }
    }

    fun removeItem(item: AudioUploadItem) {
        launch {
            stopPlay()
            val idx = paging.list.indexOfFirst { it.isSameRecord(item) }
            if (idx != -1) paging.list[idx] = paging.list[idx].copy(status = AudioItemStatus.deleting)
            OssUploadService.removeItem(item)
            paging.removeWhere { it.isSameRecord(item) }
        }
    }

    /** 选择已完成音频并返回 SongModel 给调用方（如 MV 创作页）。 */
    fun onSelect(item: AudioUploadItem) {
        val fileName = item.fileName
        var title = ""
        if (fileName.isNotEmpty()) {
            title = if (fileName.contains('.')) fileName.substringBefore('.') else fileName
            title = truncateGraphemes(title, 30)
        }
        val song = SongModel(id = 0L, musicTitle = title, musicAudioUrl = item.fileUrl, musicCoverUrl = "")
        DevLog.logger("onSelect: ${song.toJson()}")
        nav.pop(JSONObject().put("song", song.toJson()))
    }

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            attr { flex(1f) }
            Image {
                attr {
                    absolutePositionAllZero()
                    src(ImageUri.commonAssets(AssetsImage.mainBg))
                    resizeCover()
                }
            }
            SDNavBar {
                attr { title = "my_audio".tr }
            }
            // 上传入口
            View {
                attr {
                    height(80f)
                    margin(12f, 16f, 12f, 16f)
                    padding(12f, 16f, 12f, 16f)
                    backgroundColor(Color(0x1AFFFFFFL))
                    borderRadius(12f)
                    allCenter()
                }
                event { click { ctx.pickAndUpload() } }
                Image {
                    attr {
                        size(24f, 24f)
                        src(AssetsImage.uploadIcon.asset())
                    }
                }
                Text {
                    attr {
                        text("upload_music".tr)
                        fontSize(14f)
                        fontFamily("Poppins-Medium")
                        color(Color.WHITE)
                    }
                }
            }
            vif({ ctx.paging.loading && ctx.paging.pageNo == 1 && ctx.paging.list.isEmpty() }) {
                View {
                    attr {
                        flex(1f)
                        allCenter()
                    }
                    SDMusicLoading()
                }
            }
            velse {
                SDRefreshList {
                    attr {
                        flex(1f)
                        state = ctx.paging
                        placeholderHeight = 400f
                        // Dart: body SafeArea + ListView padding v12
                        footerExtraPadding = 12f + ctx.safeBottom
                        emptyBuilder = {
                            View {
                                attr {
                                    height(400f)
                                    allCenter()
                                }
                                Image {
                                    attr {
                                        size(80f, 80f)
                                        src(AssetsImage.audioEmpty.asset())
                                    }
                                }
                                Text {
                                    attr {
                                        marginTop(16f)
                                        text("Nothing Here".tr)
                                        fontSize(14f)
                                        color(Color(0x8AFFFFFFL))
                                    }
                                }
                            }
                        }
                    }
                    header { View { attr { height(12f) } } }
                    items {
                        vfor({ ctx.paging.list }) { item ->
                            View {
                                attr {
                                    marginLeft(16f)
                                    marginRight(16f)
                                }
                                SDAudioTaskCard {
                                    attr {
                                        this.item = item
                                        playingUploadId = ctx.playingUploadId
                                        isFirstItem = ctx.paging.list.firstOrNull()?.isSameRecord(item) == true
                                        isLastItem = ctx.paging.list.lastOrNull()?.isSameRecord(item) == true
                                        isLoadingAudio = ctx.isLoadingAudio
                                        isPlaying = ctx.isPlaying
                                    }
                                    event {
                                        onSelect { ctx.onSelect(it) }
                                        onPause { OssUploadService.pauseTask(it) }
                                        onResume { OssUploadService.resumeTask(it) }
                                        onRetry { OssUploadService.retryTask(it) }
                                        onRemove { ctx.removeItem(it) }
                                        onTogglePlay { ctx.togglePlay(it) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val PAGE_SIZE = 20
    }
}
