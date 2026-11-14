package com.sondo.app.ui.dialog

import com.sondo.app.base.BasePager
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.data.models.SquareModel
import com.sondo.app.ui.SDFit
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDLoading
import com.sondo.app.ui.overlay.DialogEntry
import com.sondo.app.ui.overlay.DialogHandle
import com.sondo.app.ui.video.SDListMvVideoPlayer
import com.tencent.kuikly.core.base.Anchor
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.Rotate
import com.tencent.kuikly.core.base.Scale
import com.tencent.kuikly.core.base.Skew
import com.tencent.kuikly.core.base.Translate
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.PageList
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** 预览页顶部栏（两种预览共用）：居中 `i/n` + 右侧关闭按钮（22 图标，内边距 5，黑 50% 圆底）。 */
private fun ViewContainer<*, *>.previewTopBar(statusBar: Float, counter: () -> String, onClose: () -> Unit) {
    View {
        attr {
            absolutePosition(top = statusBar, left = 0f, right = 0f)
            height(44f)
            allCenter()
        }
        Text {
            attr {
                text(counter())
                fontSize(16f)
                fontWeight500()
                color(Color.WHITE)
            }
        }
        View {
            attr {
                absolutePosition(top = 6f, right = 16f)
                padding(5f)
                borderRadius(40f)
                backgroundColor(Color(0, 0, 0, 0.5f))
            }
            event { click { onClose() } }
            Image {
                attr {
                    size(22f, 22f)
                    src(ImageUri.commonAssets(AssetsImage.close))
                    resizeContain()
                }
            }
        }
    }
}

// ============================================================================
// 图片预览
// ============================================================================

/** Dart `ImagePreviewItem`。 */
class ImagePreviewItem(val url: String, val status: Int) {
    val isSuccess: Boolean get() = status == STATUS_SUCCESS

    companion object {
        const val STATUS_GENERATING = 1
        const val STATUS_SUCCESS = 2
        const val STATUS_FAILED = 3
        const val STATUS_LOCKED = 4

        fun success(url: String) = ImagePreviewItem(url, STATUS_SUCCESS)
    }
}

internal class SDZoomImageAttr : ComposeAttr() {
    var url: String = ""
    var boxWidth: Float = 0f
    var boxHeight: Float = 0f
    /** 外部要求复位（翻页后）：值变化即复位 */
    var resetVersion: Int by observable(0)
}

internal class SDZoomImageEvent : ComposeEvent() {
    var zoomChanged: ((Boolean) -> Unit)? = null
}

/**
 * 可缩放图片：双指 1×–4×，双击在 1× 与 2× 间切换（以点击点为中心，220ms），放大后可拖动。
 * 位移 / 缩放存在普通字段里，用两个计数器触发刷新：`tick` 不带动画（跟手），`animTick` 带动画（双击）。
 */
internal class SDZoomImageView : ComposeView<SDZoomImageAttr, SDZoomImageEvent>() {
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var tick by observable(0)
    private var animTick by observable(0)
    private var zoomed by observable(false)
    private var pinchBase = 1f
    private var panStartX = 0f
    private var panStartY = 0f
    private var panBaseTx = 0f
    private var panBaseTy = 0f
    private var bound = false

    override fun createAttr(): SDZoomImageAttr = SDZoomImageAttr()
    override fun createEvent(): SDZoomImageEvent = SDZoomImageEvent()

    override fun viewDidLoad() {
        super.viewDidLoad()
        bindValueChange({ attr.resetVersion }) {
            if (!bound) bound = true else reset(animated = false)
        }
    }

    override fun viewDestroyed() {
        unbindAllValueChange()
        super.viewDestroyed()
    }

    private fun clampOffsets() {
        val maxX = (scale - 1f) * attr.boxWidth / 2f
        val maxY = (scale - 1f) * attr.boxHeight / 2f
        tx = tx.coerceIn(-maxX, maxX)
        ty = ty.coerceIn(-maxY, maxY)
    }

    private fun updateZoomed(value: Boolean) {
        if (zoomed != value) {
            zoomed = value
            event.zoomChanged?.invoke(value)
        }
    }

    private fun reset(animated: Boolean) {
        scale = 1f
        tx = 0f
        ty = 0f
        if (animated) animTick++ else tick++
        updateZoomed(false)
    }

    private fun onDoubleTap(x: Float, y: Float) {
        if (scale > 1.01f) {
            reset(animated = true)
            return
        }
        scale = 2f
        // 以点击点为中心放大：点击点在缩放后保持原位
        tx = (attr.boxWidth / 2f - x) * (scale - 1f)
        ty = (attr.boxHeight / 2f - y) * (scale - 1f)
        clampOffsets()
        animTick++
        updateZoomed(true)
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val a = ctx.attr
        return {
            attr {
                size(a.boxWidth, a.boxHeight)
                overflow(true)
            }
            event {
                doubleClick { p -> ctx.onDoubleTap(p.x, p.y) }
                pinch { p ->
                    when (p.state) {
                        "start" -> ctx.pinchBase = ctx.scale
                        "move" -> {
                            ctx.scale = (ctx.pinchBase * p.scale).coerceIn(1f, 4f)
                            ctx.clampOffsets()
                            ctx.tick++
                            ctx.updateZoomed(ctx.scale > 1.01f)
                        }
                        else -> {
                            if (ctx.scale <= 1.01f) ctx.reset(animated = false)
                        }
                    }
                }
            }
            SDImage {
                attr {
                    absolutePositionAllZero()
                    url = a.url
                    fit = SDFit.CONTAIN
                    placeholderColor = Color.TRANSPARENT
                    shimmer = false
                }
                // 位移 / 缩放单独一个 attr 块，读两个计数器建立依赖
                attr {
                    ctx.tick
                    transform(
                        Rotate.DEFAULT,
                        Scale(ctx.scale, ctx.scale),
                        Translate(
                            if (a.boxWidth > 0f) ctx.tx / a.boxWidth else 0f,
                            if (a.boxHeight > 0f) ctx.ty / a.boxHeight else 0f,
                        ),
                        Anchor.DEFAULT,
                        Skew.DEFAULT,
                    )
                    animate(Animation.easeOut(0.22f), ctx.animTick)
                }
            }
            // 放大后才出现的拖动层：1× 时不注册 pan，不抢外层翻页手势
            vif({ ctx.zoomed }) {
                View {
                    attr { absolutePositionAllZero() }
                    event {
                        doubleClick { p -> ctx.onDoubleTap(p.x, p.y) }
                        pan { p ->
                            when (p.state) {
                                "start" -> {
                                    ctx.panStartX = p.pageX
                                    ctx.panStartY = p.pageY
                                    ctx.panBaseTx = ctx.tx
                                    ctx.panBaseTy = ctx.ty
                                }
                                "move" -> {
                                    ctx.tx = ctx.panBaseTx + (p.pageX - ctx.panStartX)
                                    ctx.ty = ctx.panBaseTy + (p.pageY - ctx.panStartY)
                                    ctx.clampOffsets()
                                    ctx.tick++
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }
        }
    }
}

internal class SDImagePreviewAttr : ComposeAttr() {
    var images: List<ImagePreviewItem> = emptyList()
    var initialIndex: Int = 0
    lateinit var handle: DialogHandle
    var onDownload: ((ImagePreviewItem, Int) -> Unit)? = null
    /** Dart `AIStatusImagePreview` 外观（编辑页封面大图）：左上返回箭头、`(i/n)`、下载 27 宽放在 right 14 / bottom 200 */
    var aiStatusStyle: Boolean = false
}

internal class SDImagePreviewView : ComposeView<SDImagePreviewAttr, ComposeEvent>() {
    private var current by observable(0)
    private var zoomed by observable(false)
    private var resetVersion by observable(0)

    override fun createAttr(): SDImagePreviewAttr = SDImagePreviewAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun created() {
        super.created()
        current = attr.initialIndex
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val a = ctx.attr
        val pd = ctx.pagerData
        val w = pd.pageViewWidth
        val h = pd.pageViewHeight
        return {
            attr {
                flex(1f)
                backgroundColor(Color.BLACK)
            }
            PageList {
                attr {
                    absolutePositionAllZero()
                    pageItemWidth(w)
                    pageItemHeight(h)
                    pageDirection(true)
                    defaultPageIndex(a.initialIndex)
                    showScrollerIndicator(false)
                    // 放大时锁住翻页
                    scrollEnable(!ctx.zoomed)
                }
                event {
                    pageIndexDidChanged {
                        val index = (it as? JSONObject)?.optInt("index", 0) ?: 0
                        if (index != ctx.current) {
                            ctx.current = index
                            ctx.zoomed = false
                            // 翻页后所有页复位
                            ctx.resetVersion++
                        }
                    }
                }
                a.images.forEachIndexed { index, item ->
                    View {
                        attr {
                            size(w, h)
                            allCenter()
                        }
                        when (item.status) {
                            ImagePreviewItem.STATUS_GENERATING -> SDLoading { }
                            ImagePreviewItem.STATUS_FAILED -> Image {
                                attr {
                                    size(48f, 48f)
                                    src(ImageUri.commonAssets(AssetsImage.imageFailed))
                                    resizeContain()
                                }
                            }
                            else -> addChild(SDZoomImageView()) {
                                attr {
                                    url = item.url
                                    boxWidth = w
                                    boxHeight = h
                                    resetVersion = ctx.resetVersion
                                }
                                event {
                                    zoomChanged = { z -> if (ctx.current == index) ctx.zoomed = z }
                                }
                            }
                        }
                    }
                }
            }
            if (a.aiStatusStyle) {
                View {
                    attr {
                        absolutePosition(top = pd.statusBarHeight, left = 0f, right = 0f)
                        height(50f)
                        flexDirectionRow()
                        alignItemsCenter()
                        justifyContentSpaceBetween()
                    }
                    View {
                        attr {
                            size(48f, 48f)
                            allCenter()
                        }
                        event { click { a.handle.dismiss() } }
                        Image {
                            attr {
                                size(24f, 24f)
                                src(ImageUri.commonAssets(AssetsImage.backLeft))
                                tintColor(Color.WHITE)
                                resizeContain()
                            }
                        }
                    }
                    Text {
                        attr {
                            text("(${ctx.current + 1}/${a.images.size})")
                            fontSize(14f)
                            color(Color.WHITE)
                        }
                    }
                    View { attr { size(48f, 48f) } }
                }
            } else {
                previewTopBar(pd.statusBarHeight, { "${ctx.current + 1}/${a.images.size}" }) { a.handle.dismiss() }
            }
            // 下载按钮：仅成功的图片、且调用方提供了下载回调
            vif({
                a.onDownload != null && ctx.current in a.images.indices &&
                    a.images[ctx.current].isSuccess && a.images[ctx.current].url.isNotEmpty()
            }) {
                View {
                    attr {
                        if (a.aiStatusStyle) {
                            absolutePosition(right = 14f, bottom = pd.safeAreaInsets.bottom + 200f)
                            padding(5f)
                        } else {
                            absolutePosition(right = 16f, bottom = pd.safeAreaInsets.bottom + 80f)
                            padding(8f)
                        }
                    }
                    event {
                        click {
                            val i = ctx.current
                            if (i in a.images.indices) a.onDownload?.invoke(a.images[i], i)
                        }
                    }
                    Image {
                        attr {
                            val s = if (a.aiStatusStyle) 27f else 22f
                            size(s, s)
                            src(ImageUri.commonAssets(AssetsImage.downloadMv1))
                            resizeContain()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dart `DialogImagePreview.show(images:, initialIndex:)`：全屏黑底图片预览（横向翻页、缩放、顶部 `i/n` 与关闭）。
 *
 * - 生成中的项显示转圈，失败的项显示失败图标，其余可缩放查看。
 * - 下载：UI Kit 不依赖导出 / 相册服务。传了 [onDownload] 才显示右下角下载按钮，点击把当前项交给调用方
 *   （Dart 里是 `AigcMediaExport.prepareImage` + 存相册 + toast，由业务层实现）。
 * - 与 Dart 的差别：放大后翻页被锁定，需先双击 / 捏合回到 1× 再翻页（没有做「拖到边缘把剩余位移交给翻页」）。
 *
 * @return 句柄（`dismiss()` 等价 `DialogImagePreview.dismiss()`）；列表为空时返回 null
 */
fun BasePager.showImagePreview(
    images: List<ImagePreviewItem>,
    initialIndex: Int = 0,
    onDismiss: (() -> Unit)? = null,
    onDownload: ((item: ImagePreviewItem, index: Int) -> Unit)? = null,
    aiStatusStyle: Boolean = false,
): DialogHandle? {
    if (images.isEmpty()) return null
    val safeIndex = initialIndex.coerceIn(0, images.size - 1)
    return showDialog(
        align = DialogEntry.ALIGN_FILL,
        dismissOnMask = false,
        maskColor = Color.BLACK,
        onDismiss = onDismiss,
    ) { handle ->
        addChild(SDImagePreviewView()) {
            attr {
                this.images = images
                this.initialIndex = safeIndex
                this.handle = handle
                this.onDownload = onDownload
                this.aiStatusStyle = aiStatusStyle
            }
        }
    }
}

/** 预览一组图片 URL 的快捷方式。 */
fun BasePager.showImagePreview(urls: List<String>, initialIndex: Int = 0): DialogHandle? =
    showImagePreview(urls.map { ImagePreviewItem.success(it) }, initialIndex)

// ============================================================================
// 视频预览
// ============================================================================

/** Dart `VideoPreviewItem`。 */
class VideoPreviewItem(
    val url: String,
    val status: Int,
    val coverUrl: String = "",
    val startSeconds: Double = -1.0,
) {
    val isSuccess: Boolean get() = status == STATUS_SUCCESS

    companion object {
        const val STATUS_GENERATING = 1
        const val STATUS_SUCCESS = 2
        const val STATUS_FAILED = 3

        fun success(url: String, coverUrl: String = "", startSeconds: Double = -1.0) =
            VideoPreviewItem(url, STATUS_SUCCESS, coverUrl, startSeconds)
    }
}

/** Dart `VideoPreviewDismissResult`：通过点击 / 关闭按钮关闭时返回当前页与播放位置。 */
class VideoPreviewDismissResult(val index: Int, val positionMs: Long)

internal class SDVideoPreviewAttr : ComposeAttr() {
    var videos: List<VideoPreviewItem> = emptyList()
    var initialIndex: Int = 0
    var initialPositionMs: Long = 0L
    var showDownloadButton: Boolean = true
    lateinit var handle: DialogHandle
    var onDownload: ((VideoPreviewItem, Int) -> Unit)? = null
    var onClose: ((VideoPreviewDismissResult) -> Unit)? = null
    var resolveSource: (suspend (Int, VideoPreviewItem) -> String?)? = null
}

internal class SDVideoPreviewView : ComposeView<SDVideoPreviewAttr, ComposeEvent>() {
    private var current by observable(0)
    /** 每页解析出的可播放地址；空串 = 还没解析好（显示封面 + 转圈） */
    private val sources by observableList<String>()
    private var positions = LongArray(0)
    private var generation = 0
    private var alive = true

    override fun createAttr(): SDVideoPreviewAttr = SDVideoPreviewAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun created() {
        super.created()
        current = attr.initialIndex
        positions = LongArray(attr.videos.size)
        for (i in attr.videos.indices) sources.add("")
        resolve(current, debounce = false)
    }

    override fun viewDestroyed() {
        alive = false
        generation++
        super.viewDestroyed()
    }

    /** 解析某页的播放地址（调用方可在这里做合成 / 取缓存）；翻页后旧的解析结果作废。 */
    private fun resolve(index: Int, debounce: Boolean) {
        val item = attr.videos.getOrNull(index) ?: return
        if (!item.isSuccess || item.url.isEmpty()) return
        if (sources[index].isNotEmpty()) return
        val gen = ++generation
        val start = {
            val resolver = attr.resolveSource
            if (resolver == null) {
                if (alive && gen == generation) sources[index] = item.url
            } else {
                (getPager() as? Pager)?.lifecycleScope?.launch {
                    val resolved = resolver(index, item)
                    if (alive && gen == generation) sources[index] = resolved ?: item.url
                }
            }
        }
        // 快速连续翻页时不立刻起播（Dart 320ms 防抖）
        if (debounce) setTimeout(pagerId, 320) { if (alive && gen == generation) start() } else start()
    }

    private fun close() {
        val i = current
        val pos = if (i in positions.indices) positions[i] else 0L
        attr.onClose?.invoke(VideoPreviewDismissResult(i, pos))
        attr.handle.dismiss()
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val a = ctx.attr
        val pd = ctx.pagerData
        val w = pd.pageViewWidth
        val h = pd.pageViewHeight
        // Dart：Android 上下各留 12（播放器内部另有 12）；iOS 用安全区
        val padTop = if (pd.isIOS) pd.statusBarHeight else 12f
        val padBottom = if (pd.isIOS) pd.safeAreaInsets.bottom else 12f
        return {
            attr {
                flex(1f)
                backgroundColor(Color.BLACK)
            }
            PageList {
                attr {
                    absolutePositionAllZero()
                    pageItemWidth(w)
                    pageItemHeight(h)
                    pageDirection(true)
                    defaultPageIndex(a.initialIndex)
                    showScrollerIndicator(false)
                }
                event {
                    pageIndexDidChanged {
                        val index = (it as? JSONObject)?.optInt("index", 0) ?: 0
                        if (index != ctx.current) {
                            ctx.current = index
                            ctx.resolve(index, debounce = true)
                        }
                    }
                }
                a.videos.forEachIndexed { index, item ->
                    View {
                        attr {
                            size(w, h)
                            paddingTop(padTop)
                            paddingBottom(padBottom)
                            allCenter()
                        }
                        // 播放器以外的区域（生成中 / 失败 / 地址解析中）点击关闭并带回播放位置；
                        // 播放器自己接管单击做暂停 / 继续（Dart ListMvVideoPlayer 的 GestureDetector 优先）
                        event { click { ctx.close() } }
                        when (item.status) {
                            VideoPreviewItem.STATUS_GENERATING -> SDLoading { }
                            VideoPreviewItem.STATUS_FAILED -> Image {
                                attr {
                                    size(48f, 48f)
                                    src(ImageUri.commonAssets(AssetsImage.imageFailed))
                                    resizeContain()
                                }
                            }
                            else -> View {
                                attr {
                                    alignSelfStretch()
                                    flex(1f)
                                }
                                // 地址解析中：封面 + 转圈
                                vif({ ctx.sources[index].isEmpty() }) {
                                    View {
                                        attr {
                                            absolutePositionAllZero()
                                            allCenter()
                                            touchEnable(false)
                                        }
                                        if (item.coverUrl.isNotEmpty()) {
                                            SDImage {
                                                attr {
                                                    absolutePositionAllZero()
                                                    url = item.coverUrl
                                                    fit = SDFit.CONTAIN
                                                    placeholderColor = Color.TRANSPARENT
                                                    shimmer = false
                                                }
                                            }
                                        }
                                        vif({ ctx.current == index }) { SDLoading { } }
                                    }
                                }
                                // Dart ListMvVideoPlayer：只有当前页挂解码器；自带封面、缓冲转圈、暂停图标、错误重试、可拖动进度条
                                vif({ ctx.sources[index].isNotEmpty() }) {
                                    SDListMvVideoPlayer {
                                        attr {
                                            absolutePositionAllZero()
                                            this.index = index
                                            bypassPlaybackGate = true
                                            initialPositionMs =
                                                if (index == a.initialIndex && a.initialPositionMs > 0L) a.initialPositionMs else -1L
                                        }
                                        attr {
                                            mv = SquareModel(
                                                coverImage = item.coverUrl,
                                                firstFrameImg = item.coverUrl,
                                                resultUrl = ctx.sources[index],
                                            )
                                        }
                                        attr { isCurrentPage = ctx.current == index }
                                        event {
                                            onProgress { i, p, _ -> if (i in ctx.positions.indices) ctx.positions[i] = p }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            previewTopBar(
                pd.statusBarHeight,
                { if (a.videos.size > 1) "${ctx.current + 1}/${a.videos.size}" else "" },
            ) { ctx.close() }
            vif({
                a.showDownloadButton && a.onDownload != null && ctx.current in a.videos.indices &&
                    a.videos[ctx.current].isSuccess && a.videos[ctx.current].url.isNotEmpty()
            }) {
                View {
                    attr {
                        absolutePosition(right = 16f, bottom = pd.safeAreaInsets.bottom + 120f)
                        padding(8f)
                    }
                    event {
                        click {
                            val i = ctx.current
                            if (i in a.videos.indices) a.onDownload?.invoke(a.videos[i], i)
                        }
                    }
                    Image {
                        attr {
                            size(22f, 22f)
                            src(ImageUri.commonAssets(AssetsImage.downloadMv1))
                            resizeContain()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dart `DialogVideoPreview.show(videos:, initialIndex:, …)`：全屏黑底视频预览（横向翻页，当前页自动循环播放）。
 * 点画面暂停 / 继续（带可拖动进度条）；点关闭按钮（或生成中 / 失败 / 解析中的页面）关闭，[onResult] 收到 `VideoPreviewDismissResult(index, positionMs)`；
 * 被返回键关闭时 [onResult] 收到 null。
 *
 * UI Kit 只负责播放与外壳，以下业务能力通过回调接入：
 * - [resolveSource]：某页起播前解析真正的播放地址（Dart 里的「分镜 + 歌曲片段合成」「取本地缓存」）；
 *   返回 null 用 `item.url`。翻页后旧的解析结果自动作废。
 * - [onDownload]：传了才显示下载按钮（Dart 里是导出 AIGC 标识 + 存相册）。
 * - 全局播放闸门（Dart `AppVideoPlaybackGate`）：调用方在调用前 acquire、在 [onResult] 里 release。
 *
 * @param initialPositionMs 初始页的起播位置（只应用一次）
 * @return 句柄；列表为空返回 null
 */
fun BasePager.showVideoPreview(
    videos: List<VideoPreviewItem>,
    initialIndex: Int = 0,
    initialPositionMs: Long = 0L,
    showDownloadButton: Boolean = true,
    resolveSource: (suspend (index: Int, item: VideoPreviewItem) -> String?)? = null,
    onDownload: ((item: VideoPreviewItem, index: Int) -> Unit)? = null,
    onResult: ((VideoPreviewDismissResult?) -> Unit)? = null,
): DialogHandle? {
    if (videos.isEmpty()) return null
    val safeIndex = initialIndex.coerceIn(0, videos.size - 1)
    var result: VideoPreviewDismissResult? = null
    return showDialog(
        align = DialogEntry.ALIGN_FILL,
        dismissOnMask = false,
        maskColor = Color.BLACK,
        onDismiss = { onResult?.invoke(result) },
    ) { handle ->
        addChild(SDVideoPreviewView()) {
            attr {
                this.videos = videos
                this.initialIndex = safeIndex
                this.initialPositionMs = initialPositionMs
                this.showDownloadButton = showDownloadButton
                this.handle = handle
                this.onDownload = onDownload
                this.resolveSource = resolveSource
                this.onClose = { result = it }
            }
        }
    }
}

/** [showVideoPreview] 的挂起版：关闭后返回结果（返回键关闭为 null）。 */
suspend fun BasePager.previewVideos(
    videos: List<VideoPreviewItem>,
    initialIndex: Int = 0,
    initialPositionMs: Long = 0L,
    showDownloadButton: Boolean = true,
    resolveSource: (suspend (index: Int, item: VideoPreviewItem) -> String?)? = null,
    onDownload: ((item: VideoPreviewItem, index: Int) -> Unit)? = null,
): VideoPreviewDismissResult? = suspendCoroutine { cont ->
    val handle = showVideoPreview(videos, initialIndex, initialPositionMs, showDownloadButton, resolveSource, onDownload) {
        cont.resume(it)
    }
    if (handle == null) cont.resume(null)
}
