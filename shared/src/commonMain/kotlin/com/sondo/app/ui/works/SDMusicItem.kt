package com.sondo.app.ui.works

import com.sondo.app.base.AppEnv
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.formIntText
import com.sondo.app.data.models.SongModel
import com.sondo.app.service.MusicItemType
import com.sondo.app.service.MusicService
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.atoms.SDAudioVisualizer
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDLoading
import com.sondo.app.ui.atoms.SDLoadingType
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

class SDMusicItemAttr : ComposeAttr() {
    /** 歌曲（响应式：父级替换列表元素或在 attr 里重新赋值都会刷新） */
    var item: SongModel by observable(SongModel())
    /** 是否自己的作品（决定底部按钮组） */
    var isMy: Boolean by observable(true)
    /** 所在列表：未注册 onTap 时作为 MusicService.onTap 的第三个参数 */
    var list: List<SongModel> = emptyList()
}

class SDMusicItemEvent : ComposeEvent() {
    internal var tapHandler: ((MusicItemType) -> Unit)? = null
    /** 不注册则默认 `MusicService.onTap(type, item, list)` */
    fun onTap(handler: (MusicItemType) -> Unit) { tapHandler = handler }
}

/**
 * Dart `MusicItemWidget`：作品库「我的音乐」行。
 * 状态：1 排队 / 2 生成中 / 3 成功 / 4 失败 / 5 特殊态（Android 视为可播，iOS 视为生成中）。
 */
class SDMusicItemView : ComposeView<SDMusicItemAttr, SDMusicItemEvent>() {
    override fun createAttr(): SDMusicItemAttr = SDMusicItemAttr()
    override fun createEvent(): SDMusicItemEvent = SDMusicItemEvent()

    internal fun fire(type: MusicItemType) {
        val h = event.tapHandler
        if (h != null) h(type) else MusicService.onTap(type, attr.item, attr.list)
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val ws = worksState
        return {
            vbind({ ctx.attr.item to ctx.attr.isMy }) {
                val item = ctx.attr.item
                val isMy = ctx.attr.isMy
                val st = item.musicGenerateStatus
                val playable = st == 3 || (st == 5 && AppEnv.isAndroid)
                val masked = st == 1 || st == 2 || (st == 5 && AppEnv.isIOS) || st == 4
                View {
                    // 顶部：封面 + 信息
                    View {
                        attr {
                            flexDirectionRow()
                            alignItemsCenter()
                        }
                        // 封面 105×90：90 的封面盖在右侧 70 的圆（「唱片」造型）上
                        View {
                            attr {
                                size(105f, 90f)
                                borderRadius(10f)
                            }
                            View {
                                attr {
                                    absolutePosition(top = 10f, right = 0f)
                                    size(70f, 70f)
                                    borderRadius(35f)
                                    backgroundColor(SDColors.white10)
                                }
                            }
                            View {
                                attr {
                                    absolutePosition(top = 0f, left = 0f)
                                    size(90f, 90f)
                                    borderRadius(14f)
                                    backgroundColor(Color(0xFF333333L))
                                }
                                event { click { ctx.fire(MusicItemType.play) } }
                                SDImage {
                                    attr {
                                        absolutePositionAllZero()
                                        borderRadius(14f)
                                        url = item.musicCoverUrl ?: ""
                                    }
                                }
                                if (playable) {
                                    View {
                                        attr {
                                            absolutePosition(left = 0f, right = 0f, bottom = 0f)
                                            height(26f)
                                            borderRadius(10f)
                                            backgroundLinearGradient(
                                                Direction.TO_BOTTOM,
                                                ColorStop(Color.TRANSPARENT, 0f),
                                                ColorStop(Color(0x99000000L), 1f),
                                            )
                                        }
                                    }
                                    SDAudioVisualizer {
                                        attr {
                                            absolutePosition(right = 0f, bottom = 0f)
                                            bg = Color.TRANSPARENT
                                            color = Color.WHITE
                                            barCount = 3
                                            size = 32f
                                            status = ws.playIcon(item.id, bufferingAsLoading = true)
                                        }
                                    }
                                }
                                if (masked) {
                                    View {
                                        attr {
                                            absolutePositionAllZero()
                                            borderRadius(14f)
                                            backgroundColor(Color(0x99000000L))
                                            allCenter()
                                        }
                                        if (st < 3 || st == 5) {
                                            SDLoading {
                                                attr {
                                                    size = 36f
                                                    type = SDLoadingType.LOTTIE
                                                }
                                            }
                                        } else {
                                            Image {
                                                attr {
                                                    size(28f, 28f)
                                                    src(ImageUri.commonAssets(AssetsImage.musicFailed))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // 信息区 90 高
                        View {
                            attr {
                                flex(1f)
                                marginLeft(10f)
                                height(90f)
                                justifyContentSpaceAround()
                            }
                            event { click { if (item.musicGenerateStatus == 3) ctx.fire(MusicItemType.detail) } }
                            Text {
                                attr {
                                    text(if (st < 3) "generating".tr else item.musicTitle ?: "")
                                    fontSize(14f)
                                    color(Color.WHITE)
                                    fontFamily("Poppins-SemiBold")
                                    lines(1)
                                    textOverFlowTail()
                                }
                            }
                            View {
                                attr {
                                    flexDirectionRow()
                                    alignItemsCenter()
                                }
                                worksStat(AssetsImage.heandpic, item.musicHitNum.formIntText())
                                worksStatDivider()
                                worksStat(AssetsImage.like2, item.isCollectNum.formIntText())
                                worksStatDivider()
                                worksStat(AssetsImage.share2, item.repostCount.formIntText())
                            }
                            Text {
                                attr {
                                    text(item.createTimeStr)
                                    fontSize(12f)
                                    color(SDColors.white50)
                                    lines(1)
                                }
                            }
                        }
                    }
                    if (st > 2 && !(st == 5 && AppEnv.isIOS)) {
                        View { attr { height(12f) } }
                    }
                    // 底部操作
                    if (st > 2 && isMy && !(st == 5 && AppEnv.isIOS)) {
                        View {
                            attr {
                                flexDirectionRow()
                                flexWrapWrap()
                                // 抵消胶囊的 runSpacing（Dart Wrap 末行下方没有间距）
                                marginBottom(-8f)
                            }
                            if (st == 3) {
                                worksChip(AssetsImage.videoCreate1, "create_mv".tr, 8f) { ctx.fire(MusicItemType.createMv) }
                                worksChip(AssetsImage.danceMore, "more".tr, 8f) { ctx.fire(MusicItemType.more) }
                            } else {
                                worksChip(AssetsImage.feedback2, "feedback".tr, 8f) { ctx.fire(MusicItemType.feedback) }
                                worksChip(AssetsImage.delete2, "delete".tr, 8f) { ctx.fire(MusicItemType.deleteItem) }
                            }
                        }
                    }
                    if (st == 3 && !isMy) {
                        View {
                            attr {
                                flexDirectionRow()
                                alignItemsCenter()
                                marginBottom(-8f)
                            }
                            worksChip(if (item.isCollect) AssetsImage.likeActive else AssetsImage.libLike, "like".tr, 8f) {
                                ctx.fire(MusicItemType.like)
                            }
                            val key = item.id.toString()
                            vif({ ws.musicSharing.contains(key) }) {
                                worksChipLoading(8f)
                            }
                            vif({ !ws.musicSharing.contains(key) }) {
                                worksChip(AssetsImage.share3, "share".tr, 8f, tint = SDColors.white80) {
                                    ctx.fire(MusicItemType.share)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Dart `MusicItemWidget`：作品库「我的音乐」行。 */
fun ViewContainer<*, *>.SDMusicItem(init: SDMusicItemView.() -> Unit) {
    addChild(SDMusicItemView(), init)
}
