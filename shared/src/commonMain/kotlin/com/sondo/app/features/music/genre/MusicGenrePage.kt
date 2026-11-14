package com.sondo.app.features.music.genre

import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AppTheme
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.http.ApiResult
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.DevLog
import com.sondo.app.data.models.PageResult
import com.sondo.app.data.models.SongEntity
import com.sondo.app.data.models.SongModel
import com.sondo.app.data.models.toEntity
import com.sondo.app.data.models.toModel
import com.sondo.app.features.music.data.MusicGenreApi
import com.sondo.app.service.MusicItemType
import com.sondo.app.service.MusicService
import com.sondo.app.service.PlayerService
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.ui.input.SDInput
import com.sondo.app.ui.input.SDReturnKey
import com.sondo.app.ui.input.SDUnfocus
import com.sondo.app.ui.list.PagingState
import com.sondo.app.ui.list.SDRefreshList
import com.sondo.app.ui.nav.SDNavBack
import com.sondo.app.ui.works.SDNavPlayer
import com.sondo.app.ui.works.SDSongItem
import com.sondo.app.ui.works.WorksState
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.clearTimeout
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 音乐歌单 / 搜索列表页（Dart: features/music/presentation/pages/genre/genre_page.dart + MusicGenreController）。
 *
 * 参数：`{genre: String}`——曲风名（作标题并按 musicCategory 过滤）；`"search"` 为搜索模式（标题位换成搜索框，
 * 关键字为空时展示 PlayerService.hotList）。
 */
@Page(Routes.musicGenre)
internal class MusicGenrePage : BasePager() {
    var title by observable("")
    var isSearch by observable(false)
    var keyword by observable("")
    private var searchDebounce: String? = null
    private lateinit var works: WorksState

    /** 分页：loadData 失败时 Dart 返回空列表（按空页处理）。 */
    val paging = PagingState<SongEntity>(this, pageSize = 10, itemKey = { it.id }) { pageNo, pageSize ->
        loadData(pageNo, pageSize)
    }

    private suspend fun loadData(pageNo: Int, pageSize: Int): ApiResult<PageResult<SongEntity>> {
        // 搜索模式且没有关键字：展示热门歌曲，不分页
        if (isSearch && keyword.isEmpty()) {
            DevLog.logger("show hot list-----")
            if (pageNo > 1) return ApiResult.Success(PageResult(emptyList(), 0))
            val hot = PlayerService.hotList.map { it.toEntity() }
            return ApiResult.Success(PageResult(hot, hot.size))
        }
        val search = keyword
        val result = MusicGenreApi.getMusicListPage(
            pageNo = pageNo,
            pageSize = pageSize,
            musicCategory = if (isSearch) null else title,
            musicTitle = if (isSearch) search else null,
        )
        if (isSearch && search.isNotEmpty()) {
            UmengUtils.logEvent("search_song", mapOf("keyword" to search))
        }
        return when (result) {
            is ApiResult.Success -> result
            is ApiResult.Failure -> ApiResult.Success(PageResult(emptyList(), 0))
        }
    }

    override fun pageBackgroundColor(): Color = AppTheme.primary

    override fun created() {
        super.created()
        val params = pagerData.params
        DevLog.logger("arguments: $params")
        title = params.optString("genre", "")
        isSearch = title == "search"
        if (isSearch) paging.isShowLoading = true
        works = WorksState.of(this)
        on(Events.MUSIC_LIST_CHANGED) { data ->
            val item = data.optJSONObject("item") ?: return@on
            updateMusicList(data.optString("type", ""), SongModel.fromJson(item))
        }
        paging.refresh()
    }

    override fun pageWillDestroy() {
        searchDebounce?.let { clearTimeout(it) }
        searchDebounce = null
        super.pageWillDestroy()
    }

    /** 节流搜索：500ms 内的重复提交只发一次。 */
    fun throttle() {
        searchDebounce?.let { clearTimeout(it) }
        searchDebounce = postDelayed(500) {
            searchDebounce = null
            paging.refresh()
        }
    }

    fun updateLike(song: SongEntity) {
        MusicService.onTap(MusicItemType.like, song.toModel(), emptyList())
    }

    private fun updateMusicList(type: String, item: SongModel) {
        val index = paging.list.indexOfFirst { it.id == item.id }
        if (index == -1) return
        when (type) {
            "like" -> paging.list[index] = paging.list[index].copy(isCollect = item.isCollect, isCollectNum = item.isCollectNum)
            "share" -> paging.list[index] = paging.list[index].copy(repostCount = item.repostCount)
            "delete" -> paging.list.removeAt(index)
        }
    }

    private fun currentPlaylist(): List<SongModel> = paging.list.map { it.toModel() }

    fun onDetail(song: SongModel) {
        val playlist = currentPlaylist()
        launch {
            PlayerService.setPlaylist(playlist)
            nav.push(Routes.musicDetail, JSONObject().put("id", song.id).put("original", Routes.musicGenre))
        }
    }

    fun addPlayList(song: SongModel) {
        val playlist = currentPlaylist()
        PlayerService.launch { PlayerService.playSong(song, songs = playlist) }
    }

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            SDUnfocus {
                attr {
                    paddingTop(ctx.statusBarHeight)
                    paddingBottom(ctx.safeBottom)
                }
                // AppBar
                View {
                    attr {
                        height(56f)
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    vif({ !ctx.isSearch }) {
                        View {
                            attr {
                                absolutePosition(top = 0f, bottom = 0f, left = 60f, right = 60f)
                                allCenter()
                            }
                            Text {
                                attr {
                                    text(ctx.title)
                                    fontSize(16f)
                                    color(Color.WHITE)
                                    lines(1)
                                    textOverFlowTail()
                                }
                            }
                        }
                    }
                    View {
                        attr {
                            width(50f)
                            alignItemsFlexStart()
                        }
                        SDNavBack()
                    }
                    vif({ ctx.isSearch }) {
                        View {
                            attr {
                                flex(1f)
                                marginRight(10f)
                                backgroundColor(Color(0x1AFFFFFFL))
                                borderRadius(10f)
                                flexDirectionRow()
                                alignItemsCenter()
                                paddingLeft(10f)
                            }
                            Image {
                                attr {
                                    size(20f, 20f)
                                    src(ImageUri.commonAssets(AssetsImage.searchIcon))
                                    resizeContain()
                                }
                            }
                            SDInput {
                                attr {
                                    flex(1f)
                                    text = ctx.keyword
                                    placeholder = "enter_a_song_title".tr
                                    hintColor = Color(0x8AFFFFFFL)
                                    // 参考 InputWidget 把输入字和提示字都固定成 14
                                    fontSize = 14f
                                    returnKey = SDReturnKey.SEARCH
                                }
                                event {
                                    onChanged { ctx.keyword = it }
                                    onSubmitted { ctx.throttle() }
                                }
                            }
                            vif({ ctx.keyword.isNotEmpty() }) {
                                View {
                                    attr { padding(10f) }
                                    event {
                                        click {
                                            ctx.keyword = ""
                                            ctx.throttle()
                                        }
                                    }
                                    Image {
                                        attr {
                                            size(18f, 18f)
                                            src(ImageUri.commonAssets(AssetsImage.clear))
                                            resizeContain()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                View {
                    attr { flex(1f) }
                    SDRefreshList {
                        attr {
                            flex(1f)
                            state = ctx.paging
                            emptyImage = AssetsImage.libraryEmpty
                            emptyText = "no_data".tr
                        }
                        items {
                            vfor({ ctx.paging.list }) { entity ->
                                View {
                                    attr { padding(8f, 16f, 8f, 16f) }
                                    SDSongItem {
                                        attr {
                                            song = entity.toModel()
                                            type = "other"
                                        }
                                        event {
                                            onDetail { ctx.onDetail(it) }
                                            addPlayList { ctx.addPlayList(it) }
                                            onLike { ctx.updateLike(entity) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // 迷你播放条：当前歌曲生成完成才显示；列表为空时参考只显示空状态
                    vif({ ctx.paging.list.isNotEmpty() && ctx.works.hasCurrentSong && ctx.works.currentStatus == 3 }) {
                        View {
                            attr { absolutePosition(left = 0f, right = 0f, bottom = 0f) }
                            SDNavPlayer()
                        }
                    }
                }
            }
        }
    }
}
