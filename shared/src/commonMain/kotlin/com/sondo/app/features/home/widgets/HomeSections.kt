package com.sondo.app.features.home.widgets

import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.asset
import com.sondo.app.core.i18n.tr
import com.sondo.app.data.models.toModel
import com.sondo.app.features.home.HomeTabView
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDShimmer
import com.sondo.app.ui.atoms.SDSongSkeleton
import com.sondo.app.ui.atoms.SDSongSkeletonAttr
import com.sondo.app.ui.list.scrollOver
import com.sondo.app.ui.works.SDSongCard
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vforIndex
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.List
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/** 区块标题栏，含「更多」入口（Dart `HomeSectionHeader`）。 */
internal fun ViewContainer<*, *>.homeSectionHeader(title: String, onTap: () -> Unit) {
    View {
        attr {
            padding(0f, 16f, 16f, 16f)
            flexDirectionRow()
            alignItemsCenter()
            justifyContentSpaceBetween()
        }
        Text {
            attr {
                text(title)
                fontSize(18f)
                color(Color.WHITE)
                fontFamily("Poppins-Bold")
                lines(1)
            }
        }
        View {
            attr { paddingLeft(16f) }
            event { click { onTap() } }
            Text {
                attr {
                    text("more".tr)
                    fontSize(12f)
                    color(Color(0xFFB3B3B3L))
                    fontFamily("Poppins-Medium")
                }
            }
        }
    }
}

/** 新歌标题（Dart `HomePage._buildTitle(showMore: false)`）。 */
internal fun ViewContainer<*, *>.homeSectionTitle(title: String) {
    View {
        attr { padding(20f, 16f, 16f, 16f) }
        Text {
            attr {
                text(title)
                fontSize(18f)
                lineHeight(18f)
                fontFamily("Montserrat-Bold")
                color(Color.WHITE)
                lines(1)
            }
        }
    }
}

/** 音乐分类入口（Dart `HomeCategorySection`）：固定两张双宽卡。 */
internal fun ViewContainer<*, *>.homeCategorySection(ctx: HomeTabView) {
    val cardWidth = (ctx.bannerSide - 32f - 12f) / 2f
    // 素材 632×408
    val cardHeight = cardWidth * 408f / 632f
    View {
        attr {
            padding(10f, 16f, 0f, 16f)
            flexDirectionRow()
            justifyContentSpaceBetween()
        }
        Image {
            attr {
                size(cardWidth, cardHeight)
                src(AssetsImage.homeMvWide.asset())
                resizeCover()
            }
            event { click { ctx.toNavBar(1) } }
        }
        Image {
            attr {
                size(cardWidth, cardHeight)
                src(AssetsImage.homeMusicWide.asset())
                resizeCover()
            }
            event { click { ctx.toNavBar(3) } }
        }
    }
}

/** 热门 MV 区块（Dart `HomeMvSection`）。 */
internal fun ViewContainer<*, *>.homeMvSection(ctx: HomeTabView) {
    View {
        attr { paddingTop(20f) }
        homeSectionHeader("trending_video".tr) { ctx.openSquare() }
        vif({ ctx.mvList.isEmpty() || ctx.mvLoading }) {
            View {
                attr {
                    height(237f)
                    flexDirectionRow()
                    paddingLeft(16f)
                }
                for (i in 0 until 4) {
                    SDShimmer {
                        attr {
                            if (i > 0) marginLeft(16f)
                            size(140f, 236f)
                            radius = 12f
                            highlightColor = Color(255, 255, 255, 0.3f)
                        }
                    }
                }
            }
        }
        vif({ ctx.mvList.isNotEmpty() && !ctx.mvLoading }) {
            List {
                attr {
                    height(237f)
                    flexDirectionRow()
                    showScrollerIndicator(false)
                    scrollOver()
                }
                View { attr { width(16f) } }
                vforIndex({ ctx.mvList }) { mv, index, _ ->
                    View {
                        attr {
                            width(140f)
                            marginRight(16f)
                        }
                        event { click { ctx.onTapMv(index, "9:16") } }
                        SDImage {
                            attr {
                                size(140f, 210f)
                                borderRadius(12f)
                                url = mv.cover
                            }
                        }
                        Text {
                            attr {
                                marginTop(6f)
                                width(140f)
                                text(mv.title)
                                fontSize(14f)
                                color(Color.WHITE)
                                fontFamily("Poppins")
                                lines(1)
                                textOverFlowTail()
                            }
                        }
                    }
                }
            }
        }
        View { attr { height(8f) } }
    }
}

/** 广告横幅（Dart `HomeAdBanner`）：已登录且主入口开启时进推荐奖励；主入口关闭时展示创建 MV 广告。 */
internal fun ViewContainer<*, *>.homeAdBanner(ctx: HomeTabView) {
    val width = ctx.bannerSide - 32f
    View {
        attr {
            margin(20f, 16f, 10f, 16f)
            borderRadius(16f)
        }
        event {
            click {
                val isLogin = ctx.isLogin
                val memberEntry = AppConstants.enableMemberPageEntry
                if (isLogin && !memberEntry) {
                    ctx.toNavBar(1)
                } else if (isLogin) {
                    ctx.toReferral()
                } else {
                    UmengUtils.logEventButtonClick("ad_login_gift")
                    ctx.showLoginDialog()
                }
            }
        }
        Image {
            attr {
                val memberEntry = AppConstants.enableMemberPageEntry
                // 主入口关闭且已登录：改展示创建 MV 广告。未登录仍走新人礼包
                val image = if (ctx.isLogin) {
                    if (memberEntry) "join_now.png" else "create-mv-ad.png"
                } else if (ctx.loginPoint > 0) {
                    "newcomer_gift.png"
                } else {
                    "create-mv-ad.png"
                }
                // 素材宽 1312，高 351~356
                val h = when (image) {
                    "join_now.png" -> 351f
                    "newcomer_gift.png" -> 356f
                    else -> 352f
                }
                size(width, width * h / 1312f)
                src("images/common/$image".asset())
                resizeCover()
            }
        }
    }
}

/** 热门歌曲区块（Dart `HomeTrendingSection`）。 */
internal fun ViewContainer<*, *>.homeTrendingSection(ctx: HomeTabView) {
    View {
        homeSectionHeader("hot_songs".tr) { ctx.openGenre("search") }
        vif({ ctx.hotList.isEmpty() }) {
            View {
                attr {
                    height(176f)
                    flexDirectionRow()
                    paddingLeft(8f)
                    overflow(true)
                }
                for (i in 0 until 5) {
                    View {
                        attr { if (i > 0) marginLeft(8f) }
                        SDSongSkeleton { attr { type = SDSongSkeletonAttr.TYPE_CARD } }
                    }
                }
            }
        }
        vif({ ctx.hotList.isNotEmpty() }) {
            List {
                attr {
                    height(176f)
                    flexDirectionRow()
                    showScrollerIndicator(false)
                    scrollOver()
                }
                View { attr { width(8f) } }
                vforIndex({ ctx.hotList }) { song, _, _ ->
                    View {
                        attr { marginRight(8f) }
                        SDSongCard {
                            attr { this.song = song.toModel() }
                            event {
                                onDetail { s -> ctx.toMusicDetail(s, ctx.hotList.map { it.toModel() }) }
                                addPlayList { s -> ctx.playSong(s, ctx.hotList.map { it.toModel() }) }
                            }
                        }
                    }
                }
            }
        }
    }
}
