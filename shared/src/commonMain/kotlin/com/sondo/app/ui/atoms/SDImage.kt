package com.sondo.app.ui.atoms

import com.sondo.app.ui.SDColors
import com.sondo.app.ui.SDFit
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.ImageView
import com.tencent.kuikly.core.views.View

/** 把参考工程里的图片地址（http / 绝对路径 / assets 相对路径 / 已带 scheme）规整成 Kuikly 可用的 src。 */
fun resolveImageSrc(url: String, pageName: String): String = when {
    url.isEmpty() -> ""
    url.startsWith("http") || url.startsWith("assets://") || url.startsWith("file://") || url.startsWith("data:image") -> url
    url.startsWith("/") -> ImageUri.file(url).toUrl(pageName)
    else -> ImageUri.commonAssets(url).toUrl(pageName)
}

class SDImageAttr : ComposeAttr() {
    /** 图片地址：http(s) / 本地绝对路径 / `AssetsImage.xxx` 相对路径。空串 → 一直显示占位 */
    var url: String by observable("")
    /** 填充方式，默认 cover（Dart: `fit ?? BoxFit.cover`） */
    var fit: SDFit by observable(SDFit.COVER)
    /** 加载中 / 失败 / 空地址时是否显示占位骨架（Dart 行为：始终显示） */
    var placeholder: Boolean = true
    /** 占位是否带扫光动画；长列表里可关掉省性能 */
    var shimmer: Boolean = true
    /** 占位底色（Dart: 白 10% 底 + 白 15% 高亮） */
    var placeholderColor: Color = SDColors.white10
    /** 加载完成后是否淡入 */
    var fadeIn: Boolean = true
    /** 着色（Dart Image.color） */
    var tintColor: Color? by observable<Color?>(null)
    /** 高斯模糊半径 0..12.5 */
    var blurRadius: Float by observable(0f)
    /** 自定义失败视图；为空时失败也显示占位（与 Dart 一致） */
    var errorBuilder: ViewBuilder? = null
}

class SDImageEvent : ComposeEvent() {
    internal var successHandler: (() -> Unit)? = null
    internal var failureHandler: (() -> Unit)? = null

    fun loadSuccess(handler: () -> Unit) { successHandler = handler }
    fun loadFailure(handler: () -> Unit) { failureHandler = handler }
}

/**
 * Dart `ImageWidget` → `SDImage`：网络 / 本地图片 + 占位骨架 + 淡入。
 *
 * 尺寸、圆角、外边距直接写在本组件的 attr 上（圆角会裁剪内部图片）：
 * ```
 * SDImage { attr { size(60f, 60f); borderRadius(8f); url = ctx.cover } }
 * ```
 * Dart 的 `isLazyLoad` / `memCacheWidth` 不需要移植：Kuikly 列表本身只为可见区域创建原生视图。
 */
class SDImageView : ComposeView<SDImageAttr, SDImageEvent>() {
    /** 已成功加载的地址（地址变化后自动回到占位态） */
    private var loadedUrl by observable("")
    private var failedUrl by observable("")

    override fun createAttr(): SDImageAttr = SDImageAttr()
    override fun createEvent(): SDImageEvent = SDImageEvent()

    private val isLoaded: Boolean get() = attr.url.isNotEmpty() && loadedUrl == attr.url
    private val isFailed: Boolean get() = attr.url.isNotEmpty() && failedUrl == attr.url

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { overflow(true) }

            // 占位（在图片下面；加载成功后移除）
            vif({ ctx.attr.placeholder && !ctx.isLoaded && !(ctx.isFailed && ctx.attr.errorBuilder != null) }) {
                SDShimmer {
                    attr {
                        absolutePositionAllZero()
                        baseColor = ctx.attr.placeholderColor
                        highlightColor = SDColors.white5
                        animated = ctx.attr.shimmer
                    }
                }
            }

            vif({ ctx.attr.url.isNotEmpty() }) {
                Image {
                    attr {
                        absolutePositionAllZero()
                        src(resolveImageSrc(ctx.attr.url, ctx.getPager().pageName))
                        when (ctx.attr.fit) {
                            SDFit.COVER -> resizeCover()
                            SDFit.CONTAIN -> resizeContain()
                            SDFit.FILL -> resizeStretch()
                        }
                        tintColor(ctx.attr.tintColor)
                        if (ctx.attr.blurRadius > 0f) blurRadius(ctx.attr.blurRadius)
                        if (ctx.attr.fadeIn) {
                            opacity(if (ctx.isLoaded) 1f else 0f)
                            // 绑定到 loadedUrl：加载完成才淡入；换地址时（url 变化）立即回到透明
                            animate(Animation.easeOut(0.2f), ctx.loadedUrl)
                        }
                    }
                    event {
                        loadSuccess {
                            ctx.loadedUrl = ctx.attr.url
                            ctx.event.successHandler?.invoke()
                        }
                        loadFailure {
                            ctx.failedUrl = ctx.attr.url
                            ctx.event.failureHandler?.invoke()
                        }
                    }
                }
            }

            vif({ ctx.isFailed && ctx.attr.errorBuilder != null }) {
                View {
                    attr {
                        absolutePositionAllZero()
                        allCenter()
                    }
                    ctx.attr.errorBuilder?.invoke(this)
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDImage(init: SDImageView.() -> Unit) {
    addChild(SDImageView(), init)
}

/**
 * 本地资源图（Dart `Image.asset(AssetsImage.xxx, width:, height:, color:)`）。
 * 就是原生 `Image`，预先设好 src / 尺寸 / contain；其余属性在 [init] 里补。
 *
 * ```
 * SDAssetImage(AssetsImage.close, 26f) { event { click { handle.dismiss() } } }
 * ```
 */
fun ViewContainer<*, *>.SDAssetImage(
    asset: String,
    width: Float,
    height: Float = width,
    tint: Color? = null,
    init: (ImageView.() -> Unit)? = null,
) {
    Image {
        attr {
            size(width, height)
            src(ImageUri.commonAssets(asset))
            resizeContain()
            if (tint != null) tintColor(tint)
        }
        init?.invoke(this)
    }
}

// ---------------------------------------------------------------------------

class SDAvatarAttr : ComposeAttr() {
    /** 头像地址 */
    var avatar: String by observable("")
    /** 直径（Dart AvatarWidget.size，默认 60） */
    var size: Float by observable(60f)
    /** 地址为空时显示的本地资源（如 AssetsImage.noLoginUser）；为空则显示占位色 */
    var fallbackAsset: String = ""
    /** 占位底色（Dart 首页头像 placeholder 0x4DFFFFFF） */
    var placeholderColor: Color = SDColors.white30
    /** 描边 */
    var borderWidth: Float = 0f
    var borderColor: Color = SDColors.white20
}

/** 圆形头像（Dart `AvatarWidget` / 导航栏头像）。 */
class SDAvatarView : ComposeView<SDAvatarAttr, ComposeEvent>() {
    override fun createAttr(): SDAvatarAttr = SDAvatarAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                size(ctx.attr.size, ctx.attr.size)
                borderRadius(ctx.attr.size / 2f)
                backgroundColor(ctx.attr.placeholderColor)
                allCenter()
                if (ctx.attr.borderWidth > 0f) {
                    border(com.tencent.kuikly.core.base.Border(ctx.attr.borderWidth, com.tencent.kuikly.core.base.BorderStyle.SOLID, ctx.attr.borderColor))
                }
            }
            vif({ ctx.attr.avatar.isNotEmpty() }) {
                SDImage {
                    attr {
                        absolutePositionAllZero()
                        borderRadius(ctx.attr.size / 2f)
                        url = ctx.attr.avatar
                        shimmer = false
                        placeholder = false
                    }
                }
            }
            vif({ ctx.attr.avatar.isEmpty() && ctx.attr.fallbackAsset.isNotEmpty() }) {
                Image {
                    attr {
                        size(ctx.attr.size / 2f, ctx.attr.size / 2f)
                        src(ImageUri.commonAssets(ctx.attr.fallbackAsset))
                        resizeContain()
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDAvatar(init: SDAvatarView.() -> Unit) {
    addChild(SDAvatarView(), init)
}
