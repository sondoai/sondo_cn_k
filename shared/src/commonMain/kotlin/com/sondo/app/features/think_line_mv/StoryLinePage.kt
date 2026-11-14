package com.sondo.app.features.think_line_mv

import com.sondo.app.base.BasePager
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.c
import com.sondo.app.core.i18n.tr
import com.sondo.app.features.think_line_mv.data.SegmentItem
import com.sondo.app.ui.nav.SDNavBar
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 故事线只读页（Dart: think_line_mv/presentation/pages/story_line/story_line_page.dart）。
 * 国内版不再修改片段，只展示信息流下发的情节。
 *
 * 参数（Dart `StoryLineArgs`）：`{segments: [SegmentItem json…]}`，
 * 每项 `{emotion, timestamp_range, story_content, is_update}`；缺失时为空列表。无返回结果。
 */
@Page(Routes.storyLine)
internal class StoryLinePage : BasePager() {

    /** 待展示的情节片段，页面生命周期内不变。 */
    private var segments: List<SegmentItem> = emptyList()

    override fun pageBackgroundColor(): Color = c(0xFF131313L)

    override fun created() {
        super.created()
        segments = SegmentItem.listFromJson(pagerData.params.optJSONArray("segments"))
    }

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            attr { flex(1f) }
            SDNavBar {
                attr {
                    title = "story_line".tr
                    bgColor = c(0xFF131313L)
                }
            }
            Scroller {
                attr {
                    flex(1f)
                    padding(10f, 16f, 16f + ctx.safeBottom, 16f)
                    showScrollerIndicator(false)
                }
                ctx.segments.forEachIndexed { index, item ->
                    View {
                        attr {
                            marginTop(if (index == 0) 0f else 12f)
                            backgroundColor(c(0x1AFFFFFFL))
                            borderRadius(16f)
                            padding(14f, 14f, 16f, 14f)
                        }
                        View {
                            attr {
                                flexDirectionRow()
                                alignItemsCenter()
                                justifyContentSpaceBetween()
                            }
                            Text {
                                attr {
                                    text("Plot ${index + 1}")
                                    fontFamily("Carter")
                                    fontSize(16f)
                                    color(Color.WHITE)
                                }
                            }
                            Text {
                                attr {
                                    text(item.timestampRange)
                                    fontFamily("Poppins-Medium")
                                    fontSize(14f)
                                    color(c(0xCCC685FFL))
                                }
                            }
                        }
                        Text {
                            attr {
                                marginTop(10f)
                                text(item.storyContent)
                                fontFamily("Poppins-Medium")
                                fontSize(12f)
                                lineHeight(18f)
                                color(c(0x99FFFFFFL))
                            }
                        }
                    }
                }
            }
        }
    }
}
