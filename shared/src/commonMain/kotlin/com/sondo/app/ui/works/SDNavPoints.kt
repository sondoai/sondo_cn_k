package com.sondo.app.ui.works

import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.DevLog
import com.sondo.app.service.ConfigService
import com.sondo.app.service.RecallFlow
import com.sondo.app.service.UserStore
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.atoms.SDFlipCounter
import com.sondo.app.ui.anim.SDFloatDelta
import com.sondo.app.ui.input.hideKeyboard
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.velseif
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

class SDNavPointsAttr : ComposeAttr() {
    /** 始终显示积分数字（不显示「会员」文案） */
    var isOnlyPoints: Boolean = false
}

/** 一条飘起的积分增减（Dart FloatItem）。 */
internal class PointsFloat(val id: Int, val delta: Int)

/**
 * Dart `NavPoints`：导航栏积分胶囊（高 32，黑 60% 底 + 白 20% 描边）。
 * 优先级：回归周卡倒计时 > 幸运周卡倒计时 > 积分 / 「会员」。
 * 参考工程背后是 σ2 的毛玻璃；这里用同色半透明底近似（Kuikly 背景模糊在列表上方代价高）。
 */
class SDNavPointsView : ComposeView<SDNavPointsAttr, ComposeEvent>() {
    private val floats by observableList<PointsFloat>()
    private var floatSeq = 0

    override fun createAttr(): SDNavPointsAttr = SDNavPointsAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun created() {
        super.created()
        (getPager() as? BasePager)?.on(Events.POINTS_CHANGED) { json ->
            val delta = json.optInt("delta", 0)
            if (json.optBoolean("animate", false) && delta != 0) floats.add(PointsFloat(++floatSeq, delta))
        }
    }

    private fun handleTap() {
        val pager = getPager() as? BasePager ?: return
        hideKeyboard()
        pager.launch {
            var loginStatus = UserStore.isLogin
            if (!loginStatus) loginStatus = UserStore.showLoginDialog(pager)
            if (!loginStatus || !UserStore.isLogin) {
                DevLog.logger("nav_appbar: not login")
                return@launch
            }
            // 回归周卡窗口优先于幸运周卡，两者倒计时互不影响
            if (RecallFlow.openProductFromHome()) return@launch
            val seconds = ConfigService.countdown.remainingSeconds
            if (ConfigService.discountOpen && !ConfigService.mvDiscountModel.isTimeOut && seconds > 0) {
                pager.nav.push(Routes.discount)
                return@launch
            }
            // 主入口关闭时不进积分购买或订阅页；回归周卡和折扣仍可进
            if (!AppConstants.enableMemberPageEntry) return@launch
            if (UserStore.isSubscribed) {
                pager.nav.push(Routes.creditsPurchase)
                UmengUtils.logEventButtonClick("credit_purchase")
            } else {
                pager.nav.push(Routes.subscribe)
                UmengUtils.logEventButtonClick("subscription")
            }
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val ws = worksState
        return {
            attr {
                alignItemsFlexEnd()
                justifyContentCenter()
            }
            View {
                attr {
                    height(32f)
                    padding(0f, 10f, 0f, 12f)
                    borderRadius(16f)
                    backgroundColor(Color(0x99000000L))
                    border(com.tencent.kuikly.core.base.Border(1f, com.tencent.kuikly.core.base.BorderStyle.SOLID, SDColors.white20))
                    flexDirectionRow()
                    alignItemsCenter()
                }
                event { click { ctx.handleTap() } }
                vif({ ws.recallRemainingSeconds > 0 }) {
                    countdownLabel { ws.recallTimerText }
                }
                velseif({ ws.discountActive }) {
                    countdownLabel { ws.timerText }
                }
                velse {
                    View {
                        attr {
                            flexDirectionRow()
                            alignItemsCenter()
                        }
                        Image {
                            attr {
                                size(20f, 20f)
                                src(ImageUri.commonAssets(if (ws.isLogin && ws.isSubscribed) AssetsImage.pointsIcon else AssetsImage.member))
                            }
                        }
                        View { attr { width(6f) } }
                        vif({ ctx.attr.isOnlyPoints || (ws.isLogin && ws.isSubscribed) }) {
                            SDFlipCounter {
                                attr {
                                    fontFamily = "Poppins-SemiBold"
                                    weight = null
                                    value = ws.points
                                }
                            }
                        }
                        velse {
                            Text {
                                attr {
                                    text("member_badge".tr)
                                    fontSize(14f)
                                    fontFamily("Poppins-SemiBold")
                                    color(Color.WHITE)
                                }
                            }
                        }
                    }
                }
            }
            // 积分增减浮动（右对齐叠在胶囊上）
            vfor({ ctx.floats }) { f ->
                View {
                    attr {
                        absolutePosition(right = 0f, top = 8f)
                        touchEnable(false)
                    }
                    SDFloatDelta {
                        attr { delta = f.delta }
                        event { onFinished { ctx.floats.remove(f) } }
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.countdownLabel(text: () -> String) {
    View {
        attr {
            flexDirectionRow()
            alignItemsCenter()
        }
        Image {
            attr {
                size(16f, 16f)
                src(ImageUri.commonAssets(AssetsImage.clockIcon))
            }
        }
        Text {
            attr {
                marginLeft(4f)
                width(45f)
                text(text())
                fontSize(14f)
                fontFamily("Poppins-Bold")
                color(Color.WHITE)
                lines(1)
            }
        }
    }
}

/** Dart `NavPoints`：导航栏积分胶囊。 */
fun ViewContainer<*, *>.SDNavPoints(init: SDNavPointsView.() -> Unit = {}) {
    addChild(SDNavPointsView(), init)
}
