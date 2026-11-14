package com.sondo.app.features.menu

import com.sondo.app.base.BasePager
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.asset
import com.sondo.app.core.constants.c
import com.sondo.app.core.i18n.tr
import com.sondo.app.features.main.MainController
import com.sondo.app.features.main.MainNavTab
import com.sondo.app.service.MvService
import com.sondo.app.service.UserStore
import com.sondo.app.service.analytics.UmengUtils
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.Rotate
import com.tencent.kuikly.core.base.Translate
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Blur
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/** 创作菜单选项（Dart `CreateMenuOption`）。 */
internal enum class CreateMenuOption { musicVideo, aiMusic }

/** 创作菜单颜色与尺寸 token（Dart `CreateMenuColors`）。 */
internal object CreateMenuColors {
    val cardFill: Color get() = c(0x1AFFFFFFL)
    val closeButtonFill: Color get() = c(0x1AFFFFFFL)
    val titleColor: Color get() = c(0xFFFFFFFFL)
    val subtitleColor: Color get() = c(0x66FFFFFFL)

    const val horizontalInset = 16f
    const val cardHeight = 84f
    const val cardRadius = 100f
    const val cardPaddingH = 24f
    const val cardGap = 16f
    const val iconSize = 42f
    const val iconTextGap = 16f
    const val titleFontSize = 16f
    const val subtitleFontSize = 12f
    const val titleSubtitleGap = 6f

    const val menuToButtonGap = 40f
    const val closeButtonSize = 48f
    const val closeButtonBottomPadding = 16f

    /** 卡片毛玻璃（Kuikly Blur 上限 12.5）。 */
    const val cardBlurSigma = 12.5f

    const val openAnimationS = 0.26f
    const val closeAnimationS = 0.12f
    const val staggerDelayS = 0.07f
}

/**
 * 创作菜单页（Dart `CreateMenuPage` + `CreateMenuController`）。透明页、无容器转场：
 * 遮罩淡入 + 菜单整体从底部滑入，两张卡片左右交错滑入；点遮罩 / 关闭按钮 / 返回键反向收起后关闭。
 * 选项点击后用创作页替换本页（避免先关菜单闪主页）。
 *
 * 页面参数：无。
 */
@Page(Routes.createMenu)
internal class CreateMenuPage : BasePager() {
    internal var opened by observable(false)
    internal var closing by observable(false)
    private var leaving = false

    override fun pageBackgroundColor(): Color = Color.TRANSPARENT

    override fun created() {
        super.created()
        warmupCreatePages()
        // 首帧停在收起态，下一帧再播放入场
        postDelayed(32) { opened = true }
    }

    /** 预热 MvService 缓存数据（Dart 另外预缓存了两张创作页背景图，Kuikly 无对应能力，由图片库自行缓存）。 */
    private fun warmupCreatePages() {
        launch { UserStore.getUserInfo() }
        if (MvService.roleList.isEmpty()) launch { MvService.getRecentRoleList() }
        if (MvService.mvTemplateList.isEmpty()) launch { MvService.getMvTemplateList() }
    }

    override fun interceptBack(): Boolean = true

    override fun onBackPressed(): Boolean {
        dismiss()
        return true
    }

    /** 关闭创作菜单：反向动画后返回上一页。 */
    internal fun dismiss() {
        if (leaving) return
        leaving = true
        closing = true
        opened = false
        postDelayed((CreateMenuColors.closeAnimationS * 1000).toInt() + 20) { nav.pop() }
    }

    internal fun selectOption(option: CreateMenuOption) {
        if (leaving) return
        val pageName = when (option) {
            CreateMenuOption.musicVideo -> "mv"
            CreateMenuOption.aiMusic -> "music"
        }
        UmengUtils.logEvent("root_page_change", mapOf("pageName" to pageName))
        leaving = true
        // 用二级页替换菜单，避免先 back 闪主页。
        MainController.openCreate(
            if (option == CreateMenuOption.musicVideo) MainNavTab.mv else MainNavTab.music,
            replace = true,
            from = this,
        )
    }

    internal fun anim(staggerIndex: Int = -1): Animation {
        if (closing) return Animation.easeInOut(CreateMenuColors.closeAnimationS)
        if (staggerIndex < 0) return Animation.easeInOut(CreateMenuColors.openAnimationS)
        val delay = (staggerIndex * CreateMenuColors.staggerDelayS).coerceAtMost(CreateMenuColors.openAnimationS * 0.85f)
        return Animation.easeInOut(CreateMenuColors.openAnimationS - delay).delay(delay)
    }

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            attr { flex(1f) }
            // 遮罩
            View {
                attr {
                    absolutePositionAllZero()
                    backgroundColor(Color.BLACK)
                    opacity(if (ctx.opened) 1f else 0f)
                    animate(ctx.anim(), ctx.opened)
                }
                event { click { ctx.dismiss() } }
            }
            // 菜单整体从屏幕底部滑入
            View {
                attr {
                    absolutePosition(left = 0f, right = 0f, bottom = 0f)
                    alignItemsCenter()
                    paddingBottom(ctx.safeBottom + CreateMenuColors.closeButtonBottomPadding)
                    transform(Translate(0f, if (ctx.opened) 0f else 1f))
                    animate(ctx.anim(), ctx.opened)
                }
                createMenuPanel(ctx)
                View { attr { height(CreateMenuColors.menuToButtonGap) } }
                View {
                    attr {
                        size(CreateMenuColors.closeButtonSize, CreateMenuColors.closeButtonSize)
                        borderRadius(CreateMenuColors.closeButtonSize / 2f)
                        backgroundColor(CreateMenuColors.closeButtonFill)
                        allCenter()
                    }
                    event { click { ctx.dismiss() } }
                    // + 旋转 45° 复用为关闭图标
                    Image {
                        attr {
                            size(24f, 24f)
                            resizeContain()
                            src(AssetsImage.rootNavCreatePlus.asset())
                            tintColor(Color.WHITE)
                            transform(Rotate(45f))
                        }
                    }
                }
            }
        }
    }
}

/** Dart `CreateMenuPanel`：MV / 音乐两个入口，奇偶交错左右滑入 + 淡入。 */
private fun ViewContainer<*, *>.createMenuPanel(ctx: CreateMenuPage) {
    val order = listOf(CreateMenuOption.musicVideo, CreateMenuOption.aiMusic)
    View {
        attr {
            alignSelfStretch()
            padding(0f, CreateMenuColors.horizontalInset, 0f, CreateMenuColors.horizontalInset)
        }
        order.forEachIndexed { index, option ->
            if (index > 0) View { attr { height(CreateMenuColors.cardGap) } }
            val slideFrom = if (index % 2 == 0) -1f else 1f
            View {
                attr {
                    transform(Translate(if (ctx.opened) 0f else slideFrom, 0f))
                    opacity(if (ctx.opened) 1f else 0f)
                    animate(ctx.anim(index), ctx.opened)
                }
                createMenuItemCard(option) { ctx.selectOption(option) }
            }
        }
    }
}

/** Dart `CreateMenuItemCard`。 */
private fun ViewContainer<*, *>.createMenuItemCard(option: CreateMenuOption, onTap: () -> Unit) {
    val (icon, titleKey, subtitleKey) = when (option) {
        CreateMenuOption.musicVideo -> Triple(AssetsImage.rootNavCreateMv, "main_create_mv_title", "main_create_mv_subtitle")
        CreateMenuOption.aiMusic -> Triple(AssetsImage.rootNavCreateMusic, "main_create_music_title", "main_create_music_subtitle")
    }
    View {
        attr {
            height(CreateMenuColors.cardHeight)
            borderRadius(CreateMenuColors.cardRadius)
        }
        event { click { onTap() } }
        Blur {
            attr {
                absolutePositionAllZero()
                blurRadius(CreateMenuColors.cardBlurSigma)
            }
        }
        View {
            attr {
                absolutePositionAllZero()
                backgroundColor(CreateMenuColors.cardFill)
            }
        }
        View {
            attr {
                flex(1f)
                flexDirectionRow()
                alignItemsCenter()
                padding(0f, CreateMenuColors.cardPaddingH, 0f, CreateMenuColors.cardPaddingH)
            }
            Image {
                attr {
                    size(CreateMenuColors.iconSize, CreateMenuColors.iconSize)
                    resizeContain()
                    src(icon.asset())
                }
            }
            View { attr { width(CreateMenuColors.iconTextGap) } }
            View {
                attr {
                    flex(1f)
                    justifyContentCenter()
                }
                Text {
                    attr {
                        text(titleKey.tr)
                        fontSize(CreateMenuColors.titleFontSize)
                        fontWeightSemiBold()
                        lineHeight(CreateMenuColors.titleFontSize * 1.2f)
                        color(CreateMenuColors.titleColor)
                        lines(1)
                        textOverFlowTail()
                    }
                }
                View { attr { height(CreateMenuColors.titleSubtitleGap) } }
                Text {
                    attr {
                        text(subtitleKey.tr)
                        fontSize(CreateMenuColors.subtitleFontSize)
                        fontWeightMedium()
                        lineHeight(CreateMenuColors.subtitleFontSize * 1.2f)
                        color(CreateMenuColors.subtitleColor)
                        lines(2)
                        textOverFlowTail()
                    }
                }
            }
        }
    }
}
