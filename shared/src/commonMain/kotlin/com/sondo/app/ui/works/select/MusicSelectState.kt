package com.sondo.app.ui.works.select

import com.sondo.app.base.AppHost
import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.http.ApiResult
import com.sondo.app.core.storage.Sp
import com.sondo.app.core.utils.CommonUtils
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.nowMs
import com.sondo.app.core.utils.truncateGraphemes
import com.sondo.app.data.api.AudioUploadApi
import com.sondo.app.data.api.MusicActionApi
import com.sondo.app.data.models.AudioItemStatus
import com.sondo.app.data.models.AudioUploadItem
import com.sondo.app.data.models.SongModel
import com.sondo.app.service.MemoryCacheKeys
import com.sondo.app.service.MemoryCacheService
import com.sondo.app.service.OssUploadService
import com.sondo.app.service.PlayerService
import com.sondo.app.service.UserStore
import com.sondo.app.service.audio.AppAudioPlaybackStatus
import com.sondo.app.service.audio.AppAudioPlayer
import com.sondo.app.service.audio.AppAudioPreviewCoordinator
import com.sondo.app.service.audio.AppAudioTrack
import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList

/** 列表加载状态（Dart `MusicSelectListState`）。 */
internal enum class MusicSelectListState { idle, loading, success, empty, error }

/** 空态类型（Dart `MusicSelectEmptyType`）。 */
internal enum class MusicSelectEmptyType { my, upload, search }

/** 单个 Tab 的会话缓存（Dart `MusicSelectTabCache<T>`）。 */
internal data class MusicSelectTabCache<T>(
    val items: List<T>,
    val pageNo: Int,
    val hasMore: Boolean,
    val loadedAt: Long,
)

/**
 * Dart `MusicSelectController`：Tab 切换、搜索、分页、会话缓存、上传任务合并与试听。
 * 刷新 / 加载更多的结束通过回调交回列表组件（`done(success, noMore)`）。
 */
internal class MusicSelectState(
    val pager: BasePager,
    val type: String,
    initialTab: MusicSelectTab,
    private val close: (MusicSelectResult?) -> Unit,
) {
    private val initialTab: MusicSelectTab = initialTab

    var currentTab by pager.observable(initialTab)
    var isSearchMode by pager.observable(false)
    var searchKeyword by pager.observable("")
    var isLogin by pager.observable(UserStore.isLogin)

    /** Upload Tab 删除管理模式。 */
    var isUploadDeleteMode by pager.observable(false)

    var myListState by pager.observable(MusicSelectListState.idle)
    var uploadListState by pager.observable(MusicSelectListState.idle)
    var trendingListState by pager.observable(MusicSelectListState.idle)

    /** My Tab（Dart RefreshMixin.dataList）。 */
    val dataList by pager.observableList<SongModel>()
    private val trendingList = ArrayList<SongModel>()
    val filteredTrendingList by pager.observableList<SongModel>()

    /** 全部上传条目（本地任务 + 服务端分页）。 */
    private val uploadList = ArrayList<AudioUploadItem>()

    /** 按搜索词过滤后的上传条目（Dart visibleUploadList）。 */
    val visibleUploadList by pager.observableList<AudioUploadItem>()

    var playingKey by pager.observable("")
    var isPlaying by pager.observable(false)
    var isLoadingAudio by pager.observable(false)
    var deletingKey by pager.observable("")

    // My 分页
    private var pageNo = 1
    private var hasMore = true
    private var isLoading = false

    // Upload 分页
    private var uploadPageNo = 1
    private var uploadHasMore = true
    private var isUploadLoading = false

    private val loadedTabs = HashSet<MusicSelectTab>()
    private var isClosed = false
    private var mainPaused = false
    private val notifyRefs = ArrayList<Pair<String, CallbackRef>>()

    private val pageSize = 10
    private val uploadPageSize = 20

    // ------------------------------------------------------------------ 生命周期

    /** Dart onInit + onReady。 */
    fun start() {
        if (!isLogin) currentTab = MusicSelectTab.trending
        // 打开弹窗即暂停主播放器（关闭时不恢复）
        pager.launch {
            try {
                PlayerService.pause()
            } catch (e: Throwable) {
                DevLog.logger("pause main player error: $e")
            }
            mainPaused = true
        }
        val notify = pager.acquireModule<NotifyModule>(NotifyModule.MODULE_NAME)
        notifyRefs.add(Events.AUDIO_STATE to notify.addNotify(Events.AUDIO_STATE) { applyPreviewState() })
        notifyRefs.add(Events.UPLOAD_TASKS_CHANGED to notify.addNotify(Events.UPLOAD_TASKS_CHANGED) { mergeLocalUploadTasks() })
        applyPreviewState()
        pager.launch { ensureTabLoaded(if (isLogin) initialTab else MusicSelectTab.trending) }
    }

    /** Dart onClose：停试听、注销监听。幂等。 */
    fun dispose() {
        if (isClosed) return
        isClosed = true
        CommonUtils.hideKeyboard()
        try {
            val notify = pager.acquireModule<NotifyModule>(NotifyModule.MODULE_NAME)
            notifyRefs.forEach { (name, ref) -> notify.removeNotify(name, ref) }
        } catch (e: Throwable) {
        }
        notifyRefs.clear()
        val owner = this
        AppHost.launch { AppAudioPreviewCoordinator.stop(owner) }
    }

    // ------------------------------------------------------------------ 试听状态

    private fun trackIdOf(key: String) = "preview:music_select:$key"

    private fun applyPreviewState() {
        if (isClosed) return
        val state = AppAudioPlayer.state
        val active = AppAudioPreviewCoordinator.owns(this) && state.currentTrack?.id == trackIdOf(playingKey)
        isPlaying = active && state.isPlaying
        isLoadingAudio = active && (state.status == AppAudioPlaybackStatus.loading || state.status == AppAudioPlaybackStatus.buffering)
    }

    fun isPlaybackLoading(key: String): Boolean = playingKey == key && isLoadingAudio

    fun isDeletingItem(key: String): Boolean = deletingKey.isNotEmpty() && deletingKey == key

    private suspend fun togglePlay(url: String, key: String) {
        if (url.isEmpty()) return
        try {
            if (!mainPaused) {
                PlayerService.pause()
                mainPaused = true
            }
            if (playingKey == key && isPlaying) {
                AppAudioPreviewCoordinator.pause(this)
                return
            }
            if (playingKey != key || !AppAudioPreviewCoordinator.owns(this)) {
                playingKey = key
                isLoadingAudio = true
                AppAudioPreviewCoordinator.play(this, AppAudioTrack.fromUrl(id = trackIdOf(key), url = url))
            } else {
                AppAudioPreviewCoordinator.resume(this)
            }
            applyPreviewState()
        } catch (e: Throwable) {
            DevLog.logger("togglePlay error: $e")
        }
    }

    private suspend fun stopPlayback() {
        AppAudioPreviewCoordinator.stop(this)
        playingKey = ""
        isPlaying = false
        isLoadingAudio = false
    }

    // ------------------------------------------------------------------ Tab / 搜索

    private fun stateForTab(tab: MusicSelectTab): MusicSelectListState = when (tab) {
        MusicSelectTab.my -> myListState
        MusicSelectTab.upload -> uploadListState
        MusicSelectTab.trending -> trendingListState
    }

    private fun setStateForTab(tab: MusicSelectTab, state: MusicSelectListState) {
        when (tab) {
            MusicSelectTab.my -> myListState = state
            MusicSelectTab.upload -> uploadListState = state
            MusicSelectTab.trending -> trendingListState = state
        }
    }

    val currentListState: MusicSelectListState get() = stateForTab(currentTab)

    private fun persistTab(tab: MusicSelectTab) = Sp.setInt(StorageKey.musicSelectLastTab, tab.ordinal)

    fun enterSearchMode() {
        exitUploadDeleteMode()
        isSearchMode = true
    }

    fun exitSearchMode() {
        CommonUtils.hideKeyboard()
        isSearchMode = false
        searchKeyword = ""
        recomputeVisibleUploads()
        pager.launch { ensureTabLoaded(currentTab, force = false) }
    }

    fun clearSearch() {
        if (searchKeyword.isEmpty()) return
        searchKeyword = ""
        if (isSearchMode) applySearch()
    }

    fun onSearchTextUpdated(text: String) {
        if (text == searchKeyword) return
        searchKeyword = text
        if (isSearchMode) applySearch()
    }

    /** 点击 Tab 或滑动分页。 */
    fun switchTab(tab: MusicSelectTab) {
        if (isClosed) return
        if (currentTab == tab) return
        exitUploadDeleteMode()
        if (tab == MusicSelectTab.trending && isSearchMode) {
            isSearchMode = false
            CommonUtils.hideKeyboard()
            searchKeyword = ""
            recomputeVisibleUploads()
        }
        currentTab = tab
        persistTab(tab)
        pager.launch { ensureTabLoaded(tab) }
    }

    fun toggleUploadDeleteMode() {
        if (!isUploadDeleteMode) {
            pager.launch {
                if (isPlaying) AppAudioPreviewCoordinator.pause(this@MusicSelectState)
                applyPreviewState()
                isUploadDeleteMode = true
            }
        } else {
            isUploadDeleteMode = false
        }
    }

    fun exitUploadDeleteMode() {
        if (isUploadDeleteMode) isUploadDeleteMode = false
    }

    fun emptyTypeForTab(tab: MusicSelectTab): MusicSelectEmptyType {
        if (isSearchMode) return MusicSelectEmptyType.search
        return if (tab == MusicSelectTab.upload) MusicSelectEmptyType.upload else MusicSelectEmptyType.my
    }

    private fun applySearch() {
        val keyword = searchKeyword.trim().lowercase()
        if (currentTab == MusicSelectTab.my && isLogin) {
            loadedTabs.remove(MusicSelectTab.my)
            pager.launch { ensureTabLoaded(MusicSelectTab.my, force = true) }
            return
        }
        if (currentTab == MusicSelectTab.upload) {
            updateUploadListState()
            return
        }
        applyTrendingFilter(keyword)
        trendingListState = if (filteredTrendingList.isEmpty()) MusicSelectListState.empty else MusicSelectListState.success
    }

    private fun applyTrendingFilter(keyword: String) {
        val next = if (keyword.isEmpty()) trendingList.toList() else trendingList.filter { (it.musicTitle ?: "").lowercase().contains(keyword) }
        filteredTrendingList.clear()
        filteredTrendingList.addAll(next)
    }

    // ------------------------------------------------------------------ 加载

    suspend fun retryCurrentTab() = ensureTabLoaded(currentTab, force = true)

    private suspend fun ensureTabLoaded(tab: MusicSelectTab, force: Boolean = false) {
        if (isClosed) return
        if (!force) {
            if (restoreFromCache(tab)) {
                loadedTabs.add(tab)
                return
            }
        }
        when (tab) {
            MusicSelectTab.my -> loadMyTab(force)
            MusicSelectTab.upload -> loadUploadList(refresh = true)
            MusicSelectTab.trending -> loadTrendingTab(force)
        }
        if (isClosed) return
        loadedTabs.add(tab)
    }

    private val myCacheKeyword: String get() = if (isSearchMode) searchKeyword.trim() else ""

    private fun myCacheKey() = MemoryCacheKeys.musicSelectMyKey(type, keyword = myCacheKeyword)

    /** 有缓存时恢复并返回 true。 */
    private fun restoreFromCache(tab: MusicSelectTab): Boolean {
        when (tab) {
            MusicSelectTab.my -> {
                val entry = MemoryCacheService.get<MusicSelectTabCache<SongModel>>(myCacheKey()) ?: return false
                setList(dataList, entry.items)
                pageNo = entry.pageNo
                hasMore = entry.hasMore
                myListState = if (entry.items.isEmpty()) MusicSelectListState.empty else MusicSelectListState.success
            }
            MusicSelectTab.upload -> {
                val entry = MemoryCacheService.get<MusicSelectTabCache<AudioUploadItem>>(MemoryCacheKeys.musicSelectUploadKey(type))
                    ?: return false
                uploadList.clear()
                uploadList.addAll(entry.items)
                uploadPageNo = entry.pageNo
                uploadHasMore = entry.hasMore
                mergeLocalUploadTasks()
                recomputeVisibleUploads()
                uploadListState = if (uploadList.isEmpty()) MusicSelectListState.empty else MusicSelectListState.success
            }
            MusicSelectTab.trending -> {
                val entry = MemoryCacheService.get<MusicSelectTabCache<SongModel>>(MemoryCacheKeys.musicSelectTrendingKey(type))
                    ?: return false
                trendingList.clear()
                trendingList.addAll(entry.items)
                applyTrendingFilter(searchKeyword.trim().lowercase())
                trendingListState = if (filteredTrendingList.isEmpty()) MusicSelectListState.empty else MusicSelectListState.success
            }
        }
        return true
    }

    private fun <T> setList(target: MutableList<T>, items: List<T>) {
        target.clear()
        target.addAll(items)
    }

    private suspend fun loadMyData(): List<SongModel> {
        val res = MusicActionApi.mySongList(pageNo, pageSize = pageSize, musicTitle = searchKeyword.trim(), status = 3)
        return when (res) {
            is ApiResult.Success -> res.data.list
            is ApiResult.Failure -> throw IllegalStateException(res.toString())
        }
    }

    private suspend fun loadMyTab(force: Boolean) {
        if (!isLogin) {
            dataList.clear()
            myListState = MusicSelectListState.empty
            return
        }
        if (force) {
            pageNo = 1
            hasMore = true
            onRefresh(null)
            return
        }
        myListState = MusicSelectListState.loading
        pageNo = 1
        hasMore = true
        isLoading = true
        try {
            val result = loadMyData()
            if (isClosed) return
            setList(dataList, result)
            writeMyCache()
            myListState = if (dataList.isEmpty()) MusicSelectListState.empty else MusicSelectListState.success
            if (result.size < pageSize) hasMore = false
        } catch (e: Throwable) {
            if (isClosed) return
            DevLog.logger("loadMyTab error: $e")
            dataList.clear()
            myListState = MusicSelectListState.error
        } finally {
            isLoading = false
        }
    }

    private fun writeMyCache() {
        MemoryCacheService.put(myCacheKey(), MusicSelectTabCache(dataList.toList(), pageNo, hasMore, nowMs()))
    }

    /** My Tab 下拉刷新（Dart onRefresh）。[done] = (success, noMore)。 */
    suspend fun onRefresh(done: ((Boolean, Boolean) -> Unit)?) {
        MemoryCacheService.clearByPrefix(MemoryCacheKeys.musicSelectMyTypePrefix(type))
        loadedTabs.remove(MusicSelectTab.my)
        pageNo = 1
        hasMore = true
        isLoading = true
        // 已有列表时不切 loading，避免卸掉刷新头
        if (myListState != MusicSelectListState.success) myListState = MusicSelectListState.loading
        try {
            val result = loadMyData()
            if (isClosed) return
            setList(dataList, result)
            writeMyCache()
            myListState = if (dataList.isEmpty()) MusicSelectListState.empty else MusicSelectListState.success
            if (result.size < pageSize) hasMore = false
            done?.invoke(true, !hasMore)
        } catch (e: Throwable) {
            if (isClosed) return
            DevLog.logger("onRefresh error: $e")
            dataList.clear()
            myListState = MusicSelectListState.error
            done?.invoke(false, false)
        } finally {
            isLoading = false
        }
    }

    /** My Tab 上拉加载（Dart onLoad）。 */
    suspend fun onLoad(done: (Boolean, Boolean) -> Unit) {
        if (!hasMore || isLoading) {
            done(true, !hasMore)
            return
        }
        isLoading = true
        try {
            pageNo++
            val result = loadMyData()
            if (isClosed) return
            dataList.addAll(result)
            writeMyCache()
            if (result.size < pageSize) hasMore = false
            done(true, !hasMore)
        } catch (e: Throwable) {
            if (isClosed) return
            pageNo--
            done(false, false)
        } finally {
            isLoading = false
        }
    }

    suspend fun refreshMyTab() {
        MemoryCacheService.clearByPrefix(MemoryCacheKeys.musicSelectMyTypePrefix(type))
        loadedTabs.remove(MusicSelectTab.my)
        ensureTabLoaded(MusicSelectTab.my, force = true)
    }

    private fun loadTrendingTab(force: Boolean) {
        if (!force && restoreFromCache(MusicSelectTab.trending)) return
        trendingListState = MusicSelectListState.loading
        try {
            // 不发请求：复制首页热门加载过的列表
            trendingList.clear()
            trendingList.addAll(PlayerService.hotList.map { it.copy() })
            applyTrendingFilter(searchKeyword.trim().lowercase())
            MemoryCacheService.put(
                MemoryCacheKeys.musicSelectTrendingKey(type),
                MusicSelectTabCache(trendingList.toList(), 1, false, nowMs()),
            )
            trendingListState = if (filteredTrendingList.isEmpty()) MusicSelectListState.empty else MusicSelectListState.success
        } catch (e: Throwable) {
            DevLog.logger("loadTrendingTab error: $e")
            trendingList.clear()
            filteredTrendingList.clear()
            trendingListState = MusicSelectListState.error
        }
    }

    suspend fun refreshTrendingTab() {
        MemoryCacheService.remove(MemoryCacheKeys.musicSelectTrendingKey(type))
        loadedTabs.remove(MusicSelectTab.trending)
        ensureTabLoaded(MusicSelectTab.trending, force = true)
    }

    suspend fun refreshUploadTab() {
        MemoryCacheService.remove(MemoryCacheKeys.musicSelectUploadKey(type))
        loadedTabs.remove(MusicSelectTab.upload)
        ensureTabLoaded(MusicSelectTab.upload, force = true)
    }

    suspend fun onUploadRefresh(done: (Boolean, Boolean) -> Unit) {
        if (isUploadLoading) {
            done(true, !uploadHasMore)
            return
        }
        MemoryCacheService.remove(MemoryCacheKeys.musicSelectUploadKey(type))
        loadedTabs.remove(MusicSelectTab.upload)
        loadUploadList(refresh = true, silent = true)
        done(uploadListState != MusicSelectListState.error, !uploadHasMore)
    }

    suspend fun onUploadLoad(done: (Boolean, Boolean) -> Unit) {
        if (isUploadLoading) {
            done(true, !uploadHasMore)
            return
        }
        if (!uploadHasMore) {
            done(true, true)
            return
        }
        loadUploadList(refresh = false, silent = true)
        done(uploadListState != MusicSelectListState.error, !uploadHasMore)
    }

    private suspend fun loadUploadList(refresh: Boolean, silent: Boolean = false) {
        if (isUploadLoading) return
        if (!refresh) {
            val cached = MemoryCacheService.get<MusicSelectTabCache<AudioUploadItem>>(MemoryCacheKeys.musicSelectUploadKey(type))
            if (cached != null && uploadList.isEmpty()) {
                restoreFromCache(MusicSelectTab.upload)
                return
            }
        }
        isUploadLoading = true
        if (!silent) uploadListState = MusicSelectListState.loading
        if (refresh) {
            uploadPageNo = 1
            uploadHasMore = true
        } else {
            uploadPageNo++
        }
        try {
            // 失败时 Dart 返回空页（显示空态而不是错误态）
            val remote = when (val res = AudioUploadApi.listPage(uploadPageNo, pageSize = uploadPageSize)) {
                is ApiResult.Success -> res.data.list
                is ApiResult.Failure -> emptyList()
            }
            if (isClosed) return
            if (refresh) {
                val local = OssUploadService.tasks
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
                uploadList.clear()
                uploadList.addAll(local)
                uploadList.addAll(remote)
            } else {
                uploadList.addAll(remote)
            }
            uploadHasMore = remote.size >= uploadPageSize
            writeUploadCache()
            updateUploadListState()
        } catch (e: Throwable) {
            if (isClosed) return
            DevLog.logger("loadUploadList error: $e")
            if (refresh) uploadList.clear() else uploadPageNo--
            recomputeVisibleUploads()
            uploadListState = MusicSelectListState.error
        } finally {
            isUploadLoading = false
        }
    }

    private fun writeUploadCache() {
        MemoryCacheService.put(
            MemoryCacheKeys.musicSelectUploadKey(type),
            MusicSelectTabCache(uploadList.toList(), uploadPageNo, uploadHasMore, nowMs()),
        )
    }

    private fun recomputeVisibleUploads() {
        val keyword = searchKeyword.trim().lowercase()
        val next = if (keyword.isEmpty()) uploadList.toList() else uploadList.filter { it.fileName.lowercase().contains(keyword) }
        // 逐项比较，未变化的行不重建
        if (next.size == visibleUploadList.size && next.indices.all { next[it] == visibleUploadList[it] }) return
        if (next.size == visibleUploadList.size) {
            next.indices.forEach { if (next[it] != visibleUploadList[it]) visibleUploadList[it] = next[it] }
            return
        }
        visibleUploadList.clear()
        visibleUploadList.addAll(next)
    }

    private fun updateUploadListState() {
        recomputeVisibleUploads()
        uploadListState = if (visibleUploadList.isEmpty()) MusicSelectListState.empty else MusicSelectListState.success
    }

    /** 把 OssUploadService 的本地任务进度合并进列表（Dart ever(tasks) worker）。 */
    private fun mergeLocalUploadTasks() {
        if (isClosed) return
        val serviceTasks = OssUploadService.tasks
        var changed = false
        for (item in serviceTasks.reversed()) {
            if (uploadList.none { it.isSameRecord(item) }) {
                uploadList.add(0, item)
                changed = true
            }
        }
        for (serviceItem in serviceTasks) {
            val idx = uploadList.indexOfFirst { it.isSameRecord(serviceItem) }
            if (idx != -1 && uploadList[idx] != serviceItem) {
                uploadList[idx] = serviceItem
                changed = true
            }
        }
        val removed = uploadList.filter { e -> e.id == 0L && serviceTasks.none { it.uploadId == e.uploadId } }
        if (removed.isNotEmpty()) {
            for (item in removed) {
                uploadList.remove(item)
                if (item.status == AudioItemStatus.completed) {
                    MemoryCacheService.remove(MemoryCacheKeys.musicSelectUploadKey(type))
                    loadedTabs.remove(MusicSelectTab.upload)
                }
            }
            changed = true
        }
        if (changed) {
            writeUploadCache()
            updateUploadListState()
        }
    }

    // ------------------------------------------------------------------ 操作

    fun selectSong(song: SongModel) {
        CommonUtils.hideKeyboard()
        pager.launch { stopPlayback() }
        close(MusicSelectResult.Song(song))
    }

    fun playSongPreview(song: SongModel) {
        pager.launch { togglePlay(song.musicAudioUrl ?: "", song.musicAudioUrl ?: "") }
    }

    /** 已完成的上传条目 → 构造 SongModel（文件名去扩展名，最多 30 字）。 */
    fun selectUploadItem(item: AudioUploadItem) {
        if (item.status != AudioItemStatus.completed) return
        val fileName = item.fileName
        var title = ""
        if (fileName.isNotEmpty()) {
            title = if (fileName.contains('.')) fileName.split('.').first() else fileName
            title = truncateGraphemes(title, 30)
        }
        selectSong(
            SongModel(
                id = 0L,
                musicTitle = title,
                musicAudioUrl = item.fileUrl,
                musicCoverUrl = "",
                musicDurationStr = formatDurationMs(item.duration),
            ),
        )
    }

    /** 上传行右侧按钮：删除模式 → 删除；否则按状态播放 / 删除 / 恢复 / 暂停。 */
    fun onUploadRowAction(item: AudioUploadItem) {
        if (isUploadDeleteMode) {
            if (item.status.isActive) return
            pager.launch { removeUploadItem(item, exitDeleteModeIfEmpty = true) }
            return
        }
        if (deletingKey.isNotEmpty()) return
        when (item.status) {
            AudioItemStatus.completed -> pager.launch { togglePlay(item.fileUrl ?: "", item.playbackKey) }
            AudioItemStatus.failed -> pager.launch { removeUploadItem(item) }
            AudioItemStatus.paused -> OssUploadService.resumeTask(item.uploadId)
            AudioItemStatus.uploading, AudioItemStatus.preparing, AudioItemStatus.waiting -> OssUploadService.pauseTask(item.uploadId)
            else -> {}
        }
    }

    private suspend fun removeUploadItem(item: AudioUploadItem, exitDeleteModeIfEmpty: Boolean = false) {
        if (deletingKey.isNotEmpty()) return
        deletingKey = item.playbackKey
        try {
            if (playingKey == item.playbackKey) {
                stopPlayback()
            } else if (isPlaying) {
                AppAudioPreviewCoordinator.pause(this)
            }
            OssUploadService.removeItem(item)
            if (isClosed) return
            uploadList.removeAll { it.isSameRecord(item) }
            writeUploadCache()
            updateUploadListState()
            if (exitDeleteModeIfEmpty && visibleUploadList.isEmpty()) exitUploadDeleteMode()
        } finally {
            if (!isClosed) deletingKey = ""
        }
    }

    /** 我的 Tab 空态 Try Now：已登录关闭并返回 TryNow；否则登录后重载。 */
    fun onTryNow() {
        if (isLogin) {
            close(MusicSelectResult.TryNow)
            return
        }
        pager.launch {
            val ok = UserStore.showLoginDialog(pager)
            if (ok && !isClosed) {
                isLogin = UserStore.isLogin
                loadedTabs.remove(MusicSelectTab.my)
                MemoryCacheService.clearByPrefix(MemoryCacheKeys.musicSelectMyTypePrefix(type))
                ensureTabLoaded(MusicSelectTab.my, force = true)
            }
        }
    }

    /** 上传区：使用声明 → 登录 → 选文件上传 → 关闭并带回结果。 */
    fun onUploadTap() {
        CommonUtils.hideKeyboard()
        pager.launch {
            val agree = UserStore.showAgree(StorageKey.isAgreeUpload, "upload", pager)
            if (!agree || isClosed) return@launch
            if (!isLogin) {
                val ok = UserStore.showLoginDialog(pager)
                if (!ok || isClosed) return@launch
                isLogin = UserStore.isLogin
            }
            val result = OssUploadService.pickAndUpload(type) ?: return@launch
            if (isClosed) return@launch
            when (result) {
                is SongModel -> close(MusicSelectResult.Song(result))
                is AudioUploadItem -> close(MusicSelectResult.Upload(result))
            }
        }
    }

    companion object {
        fun formatDurationMs(durationMs: Int?): String {
            if (durationMs == null) return ""
            val total = durationMs / 1000
            return "${(total / 60).toString().padStart(2, '0')}:${(total % 60).toString().padStart(2, '0')}"
        }
    }
}
