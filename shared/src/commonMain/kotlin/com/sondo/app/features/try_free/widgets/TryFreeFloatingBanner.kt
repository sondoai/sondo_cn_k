package com.sondo.app.features.try_free.widgets

import com.sondo.app.base.Events
import com.sondo.app.core.constants.whiteA
import com.sondo.app.core.utils.nowMs
import com.sondo.app.data.models.jBool
import com.sondo.app.data.models.jDbl
import com.sondo.app.data.models.jStr
import com.sondo.app.service.TryFreeTaskService
import com.sondo.app.service.UserStore
import com.sondo.app.ui.SDFit
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDRingProgress
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.Blur
import com.tencent.kuikly.core.views.View

/** Try Free 主框架浮窗颜色与尺寸 token（Dart `TryFreeFloatColors`）。 */
internal object TryFreeFloatColors {
    const val leftRadius = 25f
    const val blurSigma = 10f
    const val ringSize = 44f
    const val paddingLeft = 4f
    const val paddingRight = 10f
    const val paddingVertical = 4f
    const val progressStrokeWidth = 2f

    val backgroundFill get() = whiteA(0.2f)
}

class TryFreeFloatingBannerAttr : ComposeAttr()

/**
 * 主框架试用任务浮窗（Dart `TryFreeFloatingBanner`）：贴右边缘展示封面与外侧进度环。
 *
 * 自包含：状态取自 [TryFreeTaskService.snapshot]，之后跟随 [Events.TRY_FREE_CHANGED] / [Events.USER_CHANGED]；
 * 无任务或未登录时不渲染任何内容（根节点尺寸为 0）。位置由父级给：
 * ```
 * TryFreeFloatingBanner { attr { absolutePosition(right = 0f, bottom = navBarTotalHeight + 25f) } }
 * ```
 */
class TryFreeFloatingBannerView : ComposeView<TryFreeFloatingBannerAttr, ComposeEvent>() {
    /** 未登录不展示，避免登出与 clearTask 时序竞态下短暂露出 */
    private var isLogin by observable(UserStore.isLogin)
    private var isTaskVisible by observable(false)
    private var coverUrl by observable("")

    /** 环当前显示的进度（阶段跳变时从上一次显示值缓动到新目标，避免环一下跳满） */
    private var shownProgress by observable(0f)

    private var tweenFrom = 0f
    private var tweenTo = 0f
    private var tweenStartMs = 0L
    private var tweening = false
    private var alive = true
    private val notifyRefs = ArrayList<Pair<String, CallbackRef>>()

    override fun createAttr(): TryFreeFloatingBannerAttr = TryFreeFloatingBannerAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun created() {
        super.created()
        applySnapshot(TryFreeTaskService.snapshot(), animated = false)
        val notify = getPager().acquireModule<NotifyModule>(NotifyModule.MODULE_NAME)
        notifyRefs.add(
            Events.TRY_FREE_CHANGED to notify.addNotify(Events.TRY_FREE_CHANGED) {
                if (alive) applySnapshot(it ?: TryFreeTaskService.snapshot(), animated = true)
            },
        )
        notifyRefs.add(
            Events.USER_CHANGED to notify.addNotify(Events.USER_CHANGED) {
                if (alive) isLogin = UserStore.isLogin
            },
        )
    }

    override fun viewDestroyed() {
        alive = false
        val notify = getPager().acquireModule<NotifyModule>(NotifyModule.MODULE_NAME)
        notifyRefs.forEach { (name, ref) -> notify.removeNotify(name, ref) }
        notifyRefs.clear()
        super.viewDestroyed()
    }

    private fun applySnapshot(json: JSONObject, animated: Boolean) {
        val wasVisible = isTaskVisible
        val firstFrame = json.jStr("firstFrameImg")
        coverUrl = if (firstFrame.isEmpty()) json.jStr("coverImage") else firstFrame
        isTaskVisible = json.jBool("isVisible", false)
        val target = json.jDbl("progress", 0.0).toFloat().coerceIn(0f, 1f)
        // 刚出现（Dart: TweenAnimationBuilder 首帧 begin == end）直接落到目标值
        if (!animated || !wasVisible || !isTaskVisible) {
            tweenTo = target
            tweening = false
            shownProgress = target
            return
        }
        if (target == tweenTo) return
        tweenFrom = shownProgress
        tweenTo = target
        tweenStartMs = nowMs()
        if (!tweening) {
            tweening = true
            tickTween()
        }
    }

    /** 600ms easeOutCubic 缓动。 */
    private fun tickTween() {
        if (!alive || !tweening) return
        val t = ((nowMs() - tweenStartMs).toFloat() / TWEEN_MS).coerceIn(0f, 1f)
        val inv = 1f - t
        val eased = 1f - inv * inv * inv
        shownProgress = tweenFrom + (tweenTo - tweenFrom) * eased
        if (t >= 1f) {
            tweening = false
            return
        }
        setTimeout(pagerId, 16) { tickTween() }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        // 环居中描边：封面直径 = 环外径 - 2 * stroke，贴环内缘外侧
        val coverSize = TryFreeFloatColors.ringSize - TryFreeFloatColors.progressStrokeWidth * 2f
        return {
            vif({ ctx.isLogin && ctx.isTaskVisible }) {
                View {
                    attr {
                        borderRadius(TryFreeFloatColors.leftRadius, 0f, TryFreeFloatColors.leftRadius, 0f)
                    }
                    event {
                        click { TryFreeTaskService.onFloatingBannerTap() }
                    }
                    Blur {
                        attr {
                            absolutePositionAllZero()
                            blurRadius(TryFreeFloatColors.blurSigma)
                        }
                    }
                    View {
                        attr {
                            absolutePositionAllZero()
                            backgroundColor(TryFreeFloatColors.backgroundFill)
                        }
                    }
                    View {
                        attr {
                            padding(
                                TryFreeFloatColors.paddingVertical,
                                TryFreeFloatColors.paddingLeft,
                                TryFreeFloatColors.paddingVertical,
                                TryFreeFloatColors.paddingRight,
                            )
                        }
                        View {
                            attr {
                                size(TryFreeFloatColors.ringSize, TryFreeFloatColors.ringSize)
                                allCenter()
                            }
                            SDImage {
                                attr {
                                    size(coverSize, coverSize)
                                    borderRadius(coverSize / 2f)
                                    url = ctx.coverUrl
                                    fit = SDFit.COVER
                                }
                            }
                            SDRingProgress {
                                attr {
                                    absolutePositionAllZero()
                                    strokeWidth = TryFreeFloatColors.progressStrokeWidth
                                    progress = ctx.shownProgress
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val TWEEN_MS = 600f
    }
}

fun ViewContainer<*, *>.TryFreeFloatingBanner(init: TryFreeFloatingBannerView.() -> Unit = {}) {
    addChild(TryFreeFloatingBannerView(), init)
}
