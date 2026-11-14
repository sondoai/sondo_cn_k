package com.sondo.app.ui.dialog

import com.sondo.app.base.BasePager
import com.sondo.app.core.utils.nowMs
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.i18n.tr
import com.sondo.app.nativeapi.SDAppModule
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.atoms.SDLoading
import com.sondo.app.ui.overlay.DialogEntry
import com.sondo.app.ui.overlay.DialogHandle
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.Translate
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.base.event.PanGestureParams
import com.tencent.kuikly.core.base.event.layoutFrameDidChange
import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Blur
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.min

// ============================================================================
// 标准底部弹层（Dart AppBottomSheet）
// ============================================================================

internal class SDSheetContainerAttr : ComposeAttr() {
    lateinit var handle: DialogHandle
    var builder: (ViewContainer<*, *>.(DialogHandle) -> Unit)? = null
    var maxHeight: Float = 0f
    var enableDrag: Boolean = true
    var showHandle: Boolean = true
    var keyboardAware: Boolean = false
    var topRadius: Float = 24f
    var bgColor: Color = SDColors.sheetBg
    var safeBottom: Boolean = true
}

/** 弹层外壳：圆角 + 拖拽条（可下拉关闭）+ 底部安全区 + 可选的键盘避让。 */
internal class SDSheetContainerView : ComposeView<SDSheetContainerAttr, ComposeEvent>() {
    private var dragOffset by observable(0f)
    private var dragging by observable(false)
    private var keyboardHeight by observable(0f)
    private var dragStartY = 0f
    private var sheetHeight = 0f
    private var eventRef: CallbackRef? = null

    override fun createAttr(): SDSheetContainerAttr = SDSheetContainerAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun created() {
        super.created()
        if (attr.keyboardAware) {
            try {
                val module = getPager().acquireModule<SDAppModule>(SDAppModule.MODULE_NAME)
                eventRef = module.addEventListener { e ->
                    if (e.optString("event", "") == "keyboard") {
                        keyboardHeight = e.optDouble("height", 0.0).toFloat().coerceAtLeast(0f)
                    }
                }
            } catch (e: Throwable) {
            }
        }
    }

    override fun viewDestroyed() {
        val ref = eventRef
        if (ref != null) {
            try {
                getPager().acquireModule<SDAppModule>(SDAppModule.MODULE_NAME).removeEventListener(ref)
            } catch (e: Throwable) {
            }
        }
        super.viewDestroyed()
    }

    /** 弹层内的输入框把键盘高度报上来（`event { onKeyboardHeight { … } }` 里调用）。 */
    fun updateKeyboardHeight(height: Float) {
        if (attr.keyboardAware) keyboardHeight = height.coerceAtLeast(0f)
    }

    private var lastMoveY = 0f
    private var lastMoveAt = 0L
    private var velocityY = 0f

    /** 下拉跟手；松手时超过弹层一半高度或向下甩（> 700/s）就关闭，否则回弹（Flutter BottomSheet 的阈值）。 */
    private fun onPan(p: PanGestureParams) {
        if (!attr.enableDrag) return
        when (p.state) {
            "start" -> {
                dragStartY = p.pageY
                lastMoveY = p.pageY
                lastMoveAt = nowMs()
                velocityY = 0f
                dragging = true
            }
            "move" -> {
                val now = nowMs()
                val dt = now - lastMoveAt
                if (dt > 0L) velocityY = (p.pageY - lastMoveY) / dt.toFloat() * 1000f
                lastMoveY = p.pageY
                lastMoveAt = now
                val dy = p.pageY - dragStartY
                dragOffset = if (dy > 0f) dy else 0f
            }
            else -> {
                val dy = dragOffset
                // 停顿后再松手不算甩
                val flung = velocityY > 700f && nowMs() - lastMoveAt < 100L
                val half = if (sheetHeight > 0f) sheetHeight / 2f else 80f
                if (dy > 0f && (dy > half || flung)) {
                    attr.handle.dismiss()
                } else {
                    dragging = false
                    dragOffset = 0f
                }
            }
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val a = ctx.attr
        val pd = ctx.pagerData
        return {
            attr {
                // 键盘上推：同时扣掉顶部安全区，避免弹层顶到状态栏
                val kb = ctx.keyboardHeight
                val limit = if (a.keyboardAware) {
                    min(a.maxHeight, (pd.pageViewHeight - kb - pd.statusBarHeight).coerceAtLeast(0f))
                } else {
                    a.maxHeight
                }
                maxHeight(limit)
                marginBottom(kb)
                backgroundColor(a.bgColor)
                borderRadius(a.topRadius, a.topRadius, 0f, 0f)
                // 键盘弹出时不再补底部安全区（Dart: SafeArea(bottom: !ignoreKeyboardInsets)）
                paddingBottom(if (a.safeBottom && !a.keyboardAware) pd.safeAreaInsets.bottom else 0f)
                overflow(true)
                // 拖动中跟手；松手（dragging 变 false）时回到 0，这一次变化带动画
                transform(Translate(0f, 0f, 0f, if (ctx.dragging) ctx.dragOffset else 0f))
                animate(Animation.easeOut(0.2f), ctx.dragging)
            }
            // 整个弹层都可下拉关闭（Material BottomSheet enableDrag）；内部 Scroller / 可点控件优先消费手势
            event {
                layoutFrameDidChange { ctx.sheetHeight = it.height }
                pan { p -> ctx.onPan(p) }
            }
            if (a.showHandle) {
                View {
                    attr {
                        height(32f)
                        alignItemsCenter()
                    }
                    View {
                        attr {
                            marginTop(10f)
                            size(40f, 6f)
                            borderRadius(100f)
                            backgroundColor(SDColors.white10)
                        }
                    }
                }
            }
            a.builder?.invoke(this, a.handle)
        }
    }
}

/**
 * Dart `AppBottomSheet.show(child:)` → 标准底部弹层：底色 #1C1C1C、顶部圆角 24、拖拽条 40×6、
 * 最大高度屏高 85%、底部安全区、遮罩黑 70%。
 *
 * ```
 * showAppBottomSheet { handle ->
 *     SDSheetHeader("more".tr) { handle.dismiss() }
 *     …内容…（内容较长时自己放 Scroller / List，并给 flex(1f) 或固定高度）
 * }
 * ```
 * @param isDismissible 点遮罩是否关闭（返回键始终可关，同 Get.bottomSheet）
 * @param enableDrag 是否可下拉弹层关闭
 * @param maxHeight 最大高度；默认屏高 85%
 * @param keyboardAware 弹层里有输入框时置 true：键盘弹出时整体上推并压缩最大高度（Dart `ignoreKeyboardInsets: true`）
 * @param showHandle 是否显示拖拽条（旧样式弹层没有）
 * @param topRadius 顶部圆角（AppBottomSheet 24；旧弹层 16）
 */
fun BasePager.showAppBottomSheet(
    isDismissible: Boolean = true,
    enableDrag: Boolean = true,
    maxHeight: Float? = null,
    keyboardAware: Boolean = false,
    showHandle: Boolean = true,
    topRadius: Float = 24f,
    maskColor: Color = SDColors.barrier,
    onDismiss: (() -> Unit)? = null,
    builder: ViewContainer<*, *>.(DialogHandle) -> Unit,
): DialogHandle {
    val limit = maxHeight ?: (screenHeight * 0.85f)
    return showDialog(
        align = DialogEntry.ALIGN_BOTTOM,
        dismissOnMask = isDismissible,
        // Get.bottomSheet(isDismissible:) 只管点遮罩，系统返回照样 pop 弹层路由
        dismissOnBack = true,
        maskColor = maskColor,
        onDismiss = onDismiss,
    ) { handle ->
        addChild(SDSheetContainerView()) {
            attr {
                this.handle = handle
                this.builder = builder
                this.maxHeight = limit
                this.enableDrag = enableDrag
                this.showHandle = showHandle
                this.keyboardAware = keyboardAware
                this.topRadius = topRadius
            }
        }
    }
}

/**
 * 旧样式底部弹层（Dart 各 `Get.bottomSheet(...)` / SmartDialog 底部对齐：顶部圆角 16、内边距 16、无拖拽条、遮罩黑 60%）。
 * 内容通常以 [SDSheetHeader] 开头。
 *
 * @param enableDrag 整块可下拉关闭（对应 `Get.bottomSheet` 默认 enableDrag: true；SmartDialog 底部弹窗不可拖，保持 false）
 */
fun BasePager.showPlainBottomSheet(
    isDismissible: Boolean = true,
    enableDrag: Boolean = false,
    padding: Float = 16f,
    topRadius: Float = 16f,
    maskColor: Color = Color(0, 0, 0, 0.6f),
    onDismiss: (() -> Unit)? = null,
    builder: ViewContainer<*, *>.(DialogHandle) -> Unit,
): DialogHandle = showAppBottomSheet(
    isDismissible = isDismissible,
    enableDrag = enableDrag,
    showHandle = false,
    topRadius = topRadius,
    maskColor = maskColor,
    onDismiss = onDismiss,
) { handle ->
    View {
        attr { padding(padding) }
        builder(handle)
    }
}

/**
 * 弹层标题行：左标题（16 bold）+ 右侧关闭图标（26）。Dart 各弹层的 `Row[TextWidget(title), Image.asset(close).tap()]`。
 */
fun ViewContainer<*, *>.SDSheetHeader(
    title: String,
    titleSize: Float = 16f,
    onClose: (() -> Unit)? = null,
) {
    View {
        attr {
            flexDirectionRow()
            alignItemsCenter()
            justifyContentSpaceBetween()
        }
        Text {
            attr {
                text(title)
                fontSize(titleSize)
                fontWeightBold()
                color(Color.WHITE)
                lines(1)
            }
        }
        if (onClose != null) {
            Image {
                attr {
                    size(26f, 26f)
                    src(ImageUri.commonAssets(AssetsImage.close))
                    resizeContain()
                }
                event { click { onClose() } }
            }
        }
    }
}

// ============================================================================
// 操作面板 / 选项选择
// ============================================================================

/**
 * 操作面板的一行。
 * @param icon 本地图标（AssetsImage.xxx，20×20）；空串无图标
 * @param color 文字色；默认白 80%
 * @param danger 危险操作（红色）
 * @param loading 行尾显示转圈（如下载中）；此时点击无效
 */
class SDActionItem(
    val text: String,
    val icon: String = "",
    val color: Color? = null,
    val danger: Boolean = false,
    val loading: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * 操作面板（Dart `DialogActionsMv` / `DialogActionsSong` 的行样式：行内边距上下 14、图标 20 + 文字 14 w500 白 80%）。
 *
 * @param title 标题（如 `"more".tr`）；带关闭按钮
 * @param onSelect 点击某一行（先关闭弹层再回调）
 */
fun BasePager.showActionSheet(
    title: String = "",
    items: List<SDActionItem>,
    onDismiss: (() -> Unit)? = null,
    onSelect: (index: Int) -> Unit,
): DialogHandle = showPlainBottomSheet(onDismiss = onDismiss) { handle ->
    if (title.isNotEmpty()) SDSheetHeader(title) { handle.dismiss() }
    View {
        attr { marginTop(if (title.isNotEmpty()) 6f else 0f) }
        items.forEachIndexed { index, item ->
            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                    padding(14f, 0f, 14f, 0f)
                    opacity(if (item.enabled) 1f else 0.4f)
                }
                event {
                    click {
                        if (item.enabled && !item.loading) {
                            handle.dismiss()
                            onSelect(index)
                        }
                    }
                }
                if (item.icon.isNotEmpty()) {
                    Image {
                        attr {
                            size(20f, 20f)
                            marginRight(10f)
                            src(ImageUri.commonAssets(item.icon))
                            resizeContain()
                        }
                    }
                }
                Text {
                    attr {
                        flex(1f)
                        text(item.text)
                        fontSize(14f)
                        fontWeight500()
                        color(item.color ?: if (item.danger) SDColors.errorRed else SDColors.white80)
                        lines(1)
                    }
                }
                if (item.loading) {
                    SDLoading { attr { size = 16f } }
                }
            }
        }
    }
}

/** [showActionSheet] 的挂起版：返回选中的下标；直接关闭返回 null。 */
suspend fun BasePager.pickAction(title: String = "", items: List<SDActionItem>): Int? = suspendCoroutine { cont ->
    var done = false
    showActionSheet(
        title = title,
        items = items,
        onDismiss = {
            if (!done) {
                done = true
                cont.resume(null)
            }
        },
    ) { index ->
        if (!done) {
            done = true
            cont.resume(index)
        }
    }
}

/**
 * 选项选择器：一列文字选项，当前项高亮打勾（时长 / 比例 / 画质等单选）。
 *
 * @param selectedIndex 当前选中项（-1 无）
 * @param onSelect 点击后先关闭再回调
 */
fun BasePager.showOptionPicker(
    title: String,
    options: List<String>,
    selectedIndex: Int = -1,
    maxListHeight: Float = 360f,
    onSelect: (index: Int) -> Unit,
): DialogHandle = showAppBottomSheet { handle ->
    View {
        attr { padding(0f, 20f, 16f, 20f) }
        SDSheetHeader(title) { handle.dismiss() }
        Scroller {
            attr {
                marginTop(12f)
                maxHeight(maxListHeight)
                showScrollerIndicator(false)
            }
            options.forEachIndexed { index, option ->
                val selected = index == selectedIndex
                View {
                    attr {
                        height(48f)
                        marginTop(if (index == 0) 0f else 8f)
                        padding(0f, 16f, 0f, 16f)
                        borderRadius(12f)
                        flexDirectionRow()
                        alignItemsCenter()
                        backgroundColor(if (selected) SDColors.white20 else SDColors.white5)
                        if (selected) border(Border(1f, BorderStyle.SOLID, SDColors.brandPurple))
                    }
                    event {
                        click {
                            handle.dismiss()
                            onSelect(index)
                        }
                    }
                    Text {
                        attr {
                            flex(1f)
                            text(option)
                            fontSize(14f)
                            fontWeight500()
                            color(if (selected) Color.WHITE else SDColors.white80)
                            lines(1)
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 锚点弹出菜单（Dart HandleOptionWidget）
// ============================================================================

/**
 * Dart `HandleOptionItem`。
 * @param id 选中标识（与 [showPopupMenu] 的 selectedId 比较）
 */
class SDPopupMenuItem(
    val title: String,
    val id: String = "",
    val icon: String = "",
)

/**
 * Dart `HandleOptionWidget` → 锚点弹出菜单：最小宽 90、圆角 10、白 50% 底 + 2px 白 60% 描边，
 * 选中项紫色底（#7F58F7），点菜单外关闭。
 *
 * Kuikly 里取不到「某个视图在屏幕上的位置」的同步值，所以锚点用**点击事件的页面坐标**：
 * ```
 * event { click { p -> ctx.showPopupMenu(p.pageX, p.pageY, items, selectedId = shot.mode) { item -> … } } }
 * ```
 * @param anchorX / anchorY 菜单左上角（页面坐标）；靠近右 / 下边缘时自动收回屏幕内
 */
fun BasePager.showPopupMenu(
    anchorX: Float,
    anchorY: Float,
    items: List<SDPopupMenuItem>,
    selectedId: String = "",
    minWidth: Float = 90f,
    selectedColor: Color = SDColors.brandPurple,
    onSelect: (item: SDPopupMenuItem) -> Unit,
): DialogHandle {
    val estimatedHeight = items.size * 36f + 8f
    val left = anchorX.coerceIn(8f, (screenWidth - minWidth - 8f).coerceAtLeast(8f))
    val top = anchorY.coerceIn(statusBarHeight, (screenHeight - estimatedHeight - safeBottom - 8f).coerceAtLeast(statusBarHeight))
    return showDialog(
        align = DialogEntry.ALIGN_FILL,
        maskColor = Color.TRANSPARENT,
    ) { handle ->
        View {
            attr { flex(1f) }
            event { click { handle.dismiss() } }
            View {
                attr {
                    absolutePosition(top = top, left = left)
                    minWidth(minWidth)
                    borderRadius(10f)
                    overflow(true)
                    border(Border(2f, BorderStyle.SOLID, SDColors.white60))
                }
                // 毛玻璃底（Dart BackdropFilter σ10）+ 白 50% 着色
                Blur {
                    attr {
                        absolutePositionAllZero()
                        blurRadius(10f)
                    }
                }
                View {
                    attr {
                        absolutePositionAllZero()
                        backgroundColor(SDColors.white50)
                    }
                }
                View {
                    attr { padding(4f, 8f, 4f, 8f) }
                    items.forEachIndexed { index, item ->
                        val selected = selectedId.isNotEmpty() && selectedId == item.id
                        View {
                            attr {
                                flexDirectionRow()
                                alignItemsCenter()
                                padding(8f, 4f, 8f, 4f)
                                if (selected) backgroundColor(selectedColor)
                                val tl = if (index == 0) 8f else 0f
                                val bl = if (index == items.size - 1) 8f else 0f
                                borderRadius(tl, tl, bl, bl)
                            }
                            event {
                                click {
                                    handle.dismiss()
                                    onSelect(item)
                                }
                            }
                            if (item.icon.isNotEmpty()) {
                                Image {
                                    attr {
                                        size(16f, 16f)
                                        marginRight(4f)
                                        src(ImageUri.commonAssets(item.icon))
                                        resizeContain()
                                        tintColor(Color.WHITE)
                                    }
                                }
                            }
                            Text {
                                attr {
                                    text(item.title)
                                    fontSize(14f)
                                    fontWeight600()
                                    fontFamily(com.sondo.app.ui.SDFont.poppins)
                                    color(Color.WHITE)
                                    lines(2)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
