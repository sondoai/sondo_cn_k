package com.sondo.app.ui.works

import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.constants.AppTheme
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.formIntText
import com.sondo.app.data.models.ModelDate
import com.sondo.app.data.models.SongModel
import com.sondo.app.data.models.jStrOrNull
import com.sondo.app.service.MusicItemType
import com.sondo.app.service.MusicService
import com.sondo.app.service.PlayerService
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.atoms.SDAudioVisualizer
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.anim.SDRotating
import com.sondo.app.ui.atoms.SDRingProgress
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

class SDSongItemAttr : ComposeAttr() {
    var song: SongModel by observable(SongModel())
    /** 'create'（自己的作品）| 'other' | 'liked' | 其它（如 'genre'） */
    var type: String = "create"
}

class SDSongItemEvent : ComposeEvent() {
    internal var onTapMoreHandler: ((SongModel) -> Unit)? = null
    internal var onLikeHandler: ((SongModel) -> Unit)? = null
    internal var deleteSongHandler: ((SongModel) -> Unit)? = null
    internal var shareVideoHandler: ((SongModel) -> Unit)? = null
    internal var addPlayListHandler: ((SongModel) -> Unit)? = null
    internal var onDetailHandler: ((SongModel) -> Unit)? = null

    fun onTapMore(handler: (SongModel) -> Unit) { onTapMoreHandler = handler }
    fun onLike(handler: (SongModel) -> Unit) { onLikeHandler = handler }
    fun deleteSong(handler: (SongModel) -> Unit) { deleteSongHandler = handler }
    /** 与 Dart 一致：保留参数但分享按钮直接走 MusicService.onTap(share) */
    fun shareVideo(handler: (SongModel) -> Unit) { shareVideoHandler = handler }
    fun addPlayList(handler: (SongModel) -> Unit) { addPlayListHandler = handler }
    fun onDetail(handler: (SongModel) -> Unit) { onDetailHandler = handler }
}

/** Dart `SongItemWidget`：紧凑歌曲行（高 60）。 */
class SDSongItemView : ComposeView<SDSongItemAttr, SDSongItemEvent>() {
    override fun createAttr(): SDSongItemAttr = SDSongItemAttr()
    override fun createEvent(): SDSongItemEvent = SDSongItemEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        val ws = worksState
        return {
            attr {
                flexDirectionRow()
                alignItemsCenter()
            }
            vbind({ ctx.attr.song }) {
                val song = ctx.attr.song
                val type = ctx.attr.type
                val st = song.musicGenerateStatus
                val dateText = ModelDate.formatMMMdyyyy(song.updateTime ?: (song.createTime ?: 0L))
                View {
                    attr {
                        flex(1f)
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    // 封面 60×60
                    View {
                        attr { size(60f, 60f) }
                        SDImage {
                            attr {
                                absolutePositionAllZero()
                                borderRadius(12f)
                                url = song.musicCoverUrl ?: ""
                            }
                        }
                        if (st < 3) {
                            View {
                                attr {
                                    absolutePositionAllZero()
                                    allCenter()
                                }
                                // Material CircularProgressIndicator：紫色底环 + 白色 50% 进度，默认 36 / 线宽 4
                                SDRotating {
                                    content {
                                        SDRingProgress {
                                            attr {
                                                size(36f, 36f)
                                                strokeWidth = 4f
                                                progress = 0.25f
                                                progressColor = Color(0x80FFFFFFL)
                                                trackColor = Color(0xAB8058F7L)
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            View {
                                attr {
                                    absolutePositionAllZero()
                                    allCenter()
                                }
                                event { click { if (song.musicGenerateStatus == 3) ctx.event.addPlayListHandler?.invoke(song) } }
                                if (st == 3) {
                                    SDAudioVisualizer {
                                        attr {
                                            color = Color.WHITE
                                            barCount = 3
                                            size = 32f
                                            imageSize = 16f
                                            status = ws.playIcon(song.id)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // 文字列
                    View {
                        attr {
                            flex(1f)
                            height(60f)
                            padding(0f, 8f, 3f, 0f)
                            justifyContentSpaceBetween()
                        }
                        View {
                            attr {
                                flexDirectionRow()
                                alignItemsCenter()
                            }
                            event { click { if (song.musicGenerateStatus == 3) ctx.event.onDetailHandler?.invoke(song) } }
                            Text {
                                attr {
                                    flex(1f)
                                    text((song.musicTitle ?: "").replace("\n", ""))
                                    fontSize(14f)
                                    lineHeight(14f * 1.2f)
                                    fontWeight400()
                                    lines(1)
                                    textOverFlowTail()
                                    color(if (st == 4) Color(0x8AFFFFFFL) else AppTheme.inversePrimary)
                                    if (st == 4) textDecorationLineThrough()
                                }
                            }
                            if (AppConstants.enableWorkPublish && type == "create") {
                                when (song.musicPublish) {
                                    1 -> publishTag("published".tr)
                                    2 -> publishTag("review-1".tr)
                                    3 -> publishTag("rejected-1".tr)
                                }
                            }
                        }
                        if (type != "create") {
                            View {
                                attr {
                                    flexDirectionRow()
                                    alignItemsCenter()
                                }
                                val avatar = song.userInfo?.jStrOrNull("avatar") ?: ""
                                if (avatar.isNotEmpty()) {
                                    SDImage {
                                        attr {
                                            size(16f, 16f)
                                            borderRadius(8f)
                                            url = avatar
                                        }
                                    }
                                }
                                if (song.nicknameStr.isNotEmpty()) {
                                    Text {
                                        attr {
                                            flex(1f)
                                            marginLeft(4f)
                                            text(song.nicknameStr)
                                            fontSize(12f)
                                            lineHeight(14.4f)
                                            lines(1)
                                            textOverFlowTail()
                                            color(AppTheme.secondary)
                                        }
                                    }
                                }
                                if (song.userInfo == null) {
                                    Text {
                                        attr {
                                            text(dateText)
                                            fontSize(14f)
                                            lines(1)
                                            color(AppTheme.secondary)
                                        }
                                    }
                                }
                            }
                        }
                        // 播放 / 收藏 / 分享数（图标 14，字 12）
                        View {
                            attr {
                                flexDirectionRow()
                                alignItemsCenter()
                            }
                            worksStat(AssetsImage.heandpic, song.musicHitNum.formIntText(), 14f, AppTheme.secondary)
                            worksStatDivider()
                            worksStat(AssetsImage.like2, song.isCollectNum.formIntText(), 14f, AppTheme.secondary)
                            worksStatDivider()
                            worksStat(AssetsImage.share2, song.repostCount.formIntText(), 14f, AppTheme.secondary)
                        }
                        if (type == "create") {
                            Text {
                                attr {
                                    text(dateText)
                                    fontSize(14f)
                                    lines(1)
                                    color(AppTheme.secondary)
                                }
                            }
                        }
                    }
                }
                // 右侧按钮
                View {
                    attr {
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    if (type != "create") {
                        iconBtn(if (song.isCollect) AssetsImage.likeActive else AssetsImage.like) {
                            ctx.event.onLikeHandler?.invoke(song)
                        }
                        iconBtn(AssetsImage.share) {
                            if (!song.musicAudioUrl.isNullOrEmpty()) MusicService.onTap(MusicItemType.share, song, emptyList())
                        }
                    }
                    if (type == "other" || type == "liked") {
                        View {
                            attr {
                                marginLeft(8f)
                                size(46f, 28f)
                                borderRadius(14f)
                                backgroundColor(Color(0x1FFFFFFFL))
                                allCenter()
                            }
                            event {
                                click {
                                    if (song.musicGenerateStatus == 3) {
                                        UmengUtils.logEvent("create_similar")
                                        PlayerService.createSong(song)
                                    }
                                }
                            }
                            Text {
                                attr {
                                    text("create".tr)
                                    fontSize(10f)
                                    fontWeight400()
                                    color(Color.WHITE)
                                }
                            }
                        }
                    }
                    if (type == "create" && st == 3) {
                        View {
                            attr { padding(2f, 10f, 0f, 0f) }
                            event { click { ctx.event.onTapMoreHandler?.invoke(song) } }
                            Image {
                                attr {
                                    size(20f, 20f)
                                    src(ImageUri.commonAssets(AssetsImage.moreWIcon))
                                }
                            }
                        }
                    }
                    if (type == "create" && st == 4) {
                        // Material Icons.delete_forever_outlined → 资源库里的删除图标
                        iconBtn(AssetsImage.delete2) { ctx.event.deleteSongHandler?.invoke(song) }
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.iconBtn(icon: String, onTap: () -> Unit) {
    View {
        attr {
            size(30f, 30f)
            allCenter()
        }
        event { click { onTap() } }
        Image {
            attr {
                size(20f, 20f)
                src(ImageUri.commonAssets(icon))
            }
        }
    }
}

private fun ViewContainer<*, *>.publishTag(text: String) {
    View {
        attr {
            marginLeft(4f)
            padding(4f, 8f, 4f, 8f)
            borderRadius(32f)
            backgroundColor(SDColors.white10)
        }
        Text {
            attr {
                text(text)
                fontSize(11f)
                lineHeight(13.2f)
                color(SDColors.white40)
            }
        }
    }
}

/** Dart `SongItemWidget`：紧凑歌曲行（曲风页 / 个人主页音乐 Tab）。 */
fun ViewContainer<*, *>.SDSongItem(init: SDSongItemView.() -> Unit) {
    addChild(SDSongItemView(), init)
}
