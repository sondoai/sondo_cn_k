package com.sondo.app.features.my

import com.sondo.app.base.BasePager
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.i18n.tr
import com.sondo.app.data.models.RecommendModel
import com.sondo.app.features.my.data.ReferralApi
import com.sondo.app.ui.atoms.SDLoading
import com.sondo.app.ui.list.PagingState
import com.sondo.app.ui.list.SDRefreshList
import com.sondo.app.ui.nav.SDNavBar
import com.tencent.kuikly.core.annotations.Page
import com.sondo.app.ui.SDWeight
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 邀请记录全量列表页（Dart: ReferralRecordPage，复用邀请页的分页数据；这里独立分页）。无参数。
 */
@Page(Routes.referralRecord)
internal class ReferralRecordPage : BasePager() {
    val recordsPaging = PagingState<RecommendModel>(this, pageSize = 20, itemKey = { it.id }) { pageNo, pageSize ->
        ReferralApi.getRecommendRecords(pageNo, pageSize)
    }

    override fun created() {
        super.created()
        recordsPaging.refresh()
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
                attr {
                    title = "invitation_details".tr
                    titleSize = 18f
                    titleWeight = SDWeight.W500
                }
            }
            View {
                attr {
                    paddingLeft(16f)
                    paddingRight(16f)
                }
                referralTableHeader(14f)
            }
            SDRefreshList {
                attr {
                    flex(1f)
                    state = ctx.recordsPaging
                    scrollOverAndroid()
                    // Dart: Center 直接作为 SmartRefresher 的 child，在整个列表区域内居中
                    placeholderFill = true
                    loadingBuilder = {
                        View {
                            attr {
                                flex(1f)
                                allCenter()
                            }
                            // CircularProgressIndicator(color: white, backgroundColor: 0xFF7F58F7)：紫色底环 + 白色转圈
                            View {
                                attr {
                                    size(36f, 36f)
                                    borderRadius(18f)
                                    border(Border(4f, BorderStyle.SOLID, Color(0xFF7F58F7L)))
                                }
                            }
                            View {
                                attr { absolutePositionAllZero(); allCenter() }
                                SDLoading { attr { size = 36f; color = Color.WHITE } }
                            }
                        }
                    }
                    emptyBuilder = {
                        View {
                            attr {
                                flex(1f)
                                allCenter()
                            }
                            Text {
                                attr {
                                    text("no_data".tr)
                                    fontSize(14f)
                                    color(Color.WHITE)
                                }
                            }
                        }
                    }
                    footerExtraPadding = ctx.safeBottom
                }
                header { View { attr { height(10f) } } }
                items {
                    vfor({ ctx.recordsPaging.list }) { item ->
                        View {
                            attr { paddingBottom(20f) }
                            View {
                                attr {
                                    minHeight(50f)
                                    padding(6f, 16f, 6f, 16f)
                                    backgroundColor(Color(0xB32F1F4BL))
                                    justifyContentCenter()
                                }
                                referralTableRow(item, 2)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** ScrollOverWidget：仅 Android 关闭越界回弹。 */
private fun com.sondo.app.ui.list.SDRefreshListAttr.scrollOverAndroid() {
    bounces = !com.sondo.app.base.AppEnv.isAndroid
}
