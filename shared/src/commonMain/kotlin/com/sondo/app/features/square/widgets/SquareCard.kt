package com.sondo.app.features.square.widgets

import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.asset
import com.sondo.app.core.utils.formIntText
import com.sondo.app.data.models.VideoContentEntity
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDShimmer
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 广场瀑布流卡片（Dart `SquareCard`）。9:16 封面高 210，其它 118。
 * @param onTapAuthor 点头像 / 昵称（进个人主页）
 */
internal fun ViewContainer<*, *>.squareCard(
    video: VideoContentEntity,
    width: Float,
    onTap: () -> Unit,
    onTapAuthor: () -> Unit,
    onTapLike: (() -> Unit)? = null,
) {
    val isVertical = video.imageComp == "9:16"
    View {
        attr { width(width) }
        event { click { onTap() } }
        SDImage {
            attr {
                size(width, if (isVertical) 210f else 118f)
                borderRadius(12f)
                url = video.cover
            }
        }
        View {
            attr { padding(8f, 0f, 8f, 0f) }
            Text {
                attr {
                    text(video.title)
                    fontSize(14f)
                    color(Color.WHITE)
                    fontFamily("Poppins-SemiBold")
                    lines(1)
                    textOverFlowTail()
                }
            }
            View {
                attr {
                    marginTop(4f)
                    flexDirectionRow()
                    alignItemsCenter()
                }
                View {
                    attr {
                        flex(1f)
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    event { click { onTapAuthor() } }
                    View {
                        attr {
                            size(18f, 18f)
                            borderRadius(9f)
                            backgroundLinearGradient(
                                Direction.TO_BOTTOM_RIGHT,
                                ColorStop(Color(0xFF8B5CF6L), 0f),
                                ColorStop(Color(0xFF6366F1L), 1f),
                            )
                        }
                        SDImage {
                            attr {
                                size(18f, 18f)
                                borderRadius(9f)
                                url = video.avatar
                                placeholder = false
                            }
                        }
                    }
                    Text {
                        attr {
                            flex(1f)
                            marginLeft(4f)
                            text(video.nicknameStr)
                            fontSize(12f)
                            color(Color(0xB3FFFFFFL))
                            lines(1)
                            textOverFlowTail()
                        }
                    }
                }
                View {
                    attr {
                        marginLeft(8f)
                        padding(2f, 4f, 2f, 4f)
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    event { click { onTapLike?.invoke() } }
                    Image {
                        attr {
                            size(16f, 16f)
                            src((if (video.isCollect) AssetsImage.likeActive else AssetsImage.like).asset())
                            if (!video.isCollect) tintColor(Color(255, 255, 255, 0.5f))
                        }
                    }
                    View { attr { width(4f) } }
                    if (video.collectNum > 0) {
                        Text {
                            attr {
                                text(video.collectNum.formIntText())
                                fontSize(12f)
                                color(Color(255, 255, 255, 0.5f))
                                fontFamily("Poppins-Medium")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 瀑布流首屏骨架（Dart `_buildLoadingView` 单项）。 */
internal fun ViewContainer<*, *>.squareCardSkeleton(width: Float, index: Int) {
    View {
        attr { width(width) }
        SDShimmer {
            attr {
                size(width, if (index % 2 == 0) 200f else 140f)
                radius = 10f
                highlightColor = Color(255, 255, 255, 0.3f)
            }
        }
        SDShimmer {
            attr {
                marginTop(6f)
                size(width, 20f)
                radius = 10f
                highlightColor = Color(255, 255, 255, 0.3f)
            }
        }
        View {
            attr {
                marginTop(6f)
                flexDirectionRow()
                justifyContentSpaceBetween()
            }
            View {
                attr { flexDirectionRow() }
                SDShimmer { attr { size(26f, 26f); radius = 13f; highlightColor = Color(255, 255, 255, 0.3f) } }
                SDShimmer { attr { marginLeft(4f); size(60f, 20f); radius = 4f; highlightColor = Color(255, 255, 255, 0.3f) } }
            }
            SDShimmer { attr { size(40f, 20f); radius = 4f; highlightColor = Color(255, 255, 255, 0.3f) } }
        }
    }
}
